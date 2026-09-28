/* 门诊医生站诊断录入面板: 检索统一字典 his_diag_dict(医共体诊断字典启用项), 诊断类别 diagClass 随就诊落库 */
;(function () {
  /* 诊断类别(与后端 his_diag_dict.dict_type 白名单一致) */
  const DIAG_CLASSES = [
    { v: 'west', l: '西医诊断' },
    { v: 'tcm', l: '中医诊断' },
    { v: 'symp', l: '中医症候' },
    { v: 'oper', l: '手术代码' },
    { v: 'tumor', l: '肿瘤代码' }
  ];
  /* 常用诊断(医保版ICD-10码, 与统一字典 west 类同源; ybCode=code 保持"已对照"徽标) */
  const COMMON_DIAGNOSES = [
    { code: 'I10.x00', ybCode: 'I10.x00', name: '原发性高血压', extra: '循环系统疾病' },
    { code: 'E11.900', ybCode: 'E11.900', name: '2型糖尿病', extra: '内分泌疾病' },
    { code: 'J06.900', ybCode: 'J06.900', name: '急性上呼吸道感染', extra: '呼吸系统疾病' },
    { code: 'M51.202', ybCode: 'M51.202', name: '腰椎间盘突出症', extra: '肌肉骨骼疾病' },
    { code: 'K52.900', ybCode: 'K52.900', name: '急性胃肠炎', extra: '消化系统疾病' },
    { code: 'J20.900', ybCode: 'J20.900', name: '急性支气管炎', extra: '呼吸系统疾病' },
    { code: 'K29.700', ybCode: 'K29.700', name: '胃炎', extra: '消化系统疾病' },
    { code: 'N39.000', ybCode: 'N39.000', name: '泌尿道感染', extra: '泌尿系统疾病' },
    { code: 'R51.x00', ybCode: 'R51.x00', name: '头痛', extra: '症状与体征' },
    { code: 'R10.400', ybCode: 'R10.400', name: '腹痛', extra: '症状与体征' }
  ];

  const DiagnosisPanel = {
    name: 'DwDiagnosisPanel',
    inject: ['currentVisit'],
    emits: ['update-diagnoses'],
    provide: function () {
      var vm = this;
      return { diagnoses: Vue.computed(function () { return vm.selectedDiagnoses; }) };
    },
    template: `
      <section class="dw-panel">
        <header class="dw-panel-header">
          <span>诊断录入 <span class="dim">{{ selectedDiagnoses.length }} 条</span></span>
        </header>

        <div v-if="!currentVisit" class="dw-empty"><el-empty description="请选择患者后录入诊断"></el-empty></div>
        <div v-else style="padding:10px 12px">
          <div class="dw-diag-pick" style="display:flex;gap:8px;align-items:center">
            <el-select v-model="diagClass" size="small" style="width:104px;flex:none" :disabled="readOnly" @change="onDiagClassChange">
              <el-option v-for="c in diagClasses" :key="c.v" :label="c.l" :value="c.v"></el-option>
            </el-select>
            <el-select v-model="pickDiagCode" size="small" filterable remote reserve-keyword clearable :remote-method="remoteSearchDiag" :loading="loading" :disabled="readOnly" style="flex:1;min-width:0" placeholder="检索诊断：名称 / 编码 / 拼音简码，选中即添加" @change="onPickDiag">
              <el-option v-for="r in results" :key="r.code" :label="r.name" :value="r.code">
                <div class="dw-diag-opt"><span class="nm">{{ r.name }}</span><span class="code">{{ r.code }}</span><span class="extra">{{ r.extra || r.category || '' }}</span></div>
              </el-option>
            </el-select>
          </div>
          <div class="dw-diag-common-wrap">
            <div class="dw-diag-tabs">
              <button class="dw-diag-tab" :class="{'is-active': commonTab==='personal'}" @click="switchCommonTab('personal')">我的常用</button>
              <button class="dw-diag-tab" :class="{'is-active': commonTab==='dept'}" @click="switchCommonTab('dept')">科室诊断</button>
              <span class="dim" style="margin-left:auto;font-size:12px">已选 {{ selectedDiagnoses.length }} 条</span>
            </div>
            <div class="dw-diag-common" v-if="commonDiagnosisRows.length">
              <button v-for="item in commonDiagnosisRows" :key="item.code" class="dw-tag" :class="isAdded(item) ? 'dw-tag--success' : 'dw-tag--info'" :disabled="readOnly || isAdded(item)" style="cursor:pointer" @click="addDiagnosis(item)">{{ item.name }}</button>
            </div>
            <div v-else class="dw-slim-empty">暂无{{ commonTab==='personal' ? '个人常用' : '科室' }}诊断, 可用已选行的「★常用」沉淀, 或 <a href="javascript:void(0)" @click="gotoTemplateManage">去维护</a></div>
          </div>

          <div class="dw-section" style="margin-top:10px">已选诊断 <span class="dw-section-extra">主诊断固定首位 · 共 {{ selectedDiagnoses.length }} 条</span></div>
          <div class="dw-diag-rows" v-if="selectedDiagnoses.length">
            <div class="dw-diag-row" v-for="(diag, idx) in selectedDiagnoses" :key="diag._key">
              <span class="ord">{{ idx + 1 }}</span>
              <span class="dw-tag" :class="diag.maindiagFlag === '1' ? 'dw-tag--info' : ''">{{ diag.maindiagFlag === '1' ? '★主' : '次' }}</span>
              <span class="nm" :title="diag.diagName">{{ diag.diagName }}</span>
              <span class="code">{{ diag.diagCode }}</span>
              <span class="dim" style="font-size:12px">{{ classLabel(diag.diagClass) }}</span>
              <el-select v-model="diag.diagType" size="small" :disabled="readOnly" @change="notifyChange" style="width:96px"><el-option label="初诊" value="1"></el-option><el-option label="复诊" value="2"></el-option><el-option label="疑似" value="3"></el-option></el-select>
              <span class="dw-tag" :class="diag.mapped !== false ? 'dw-tag--success' : 'dw-tag--warning'">{{ diag.mapped !== false ? '✓对照' : '!未对照' }}</span>
              <el-button v-if="diag.maindiagFlag !== '1'" link type="primary" size="small" :disabled="readOnly" @click="setMain(idx)">设主</el-button>
              <el-button link type="warning" size="small" :disabled="readOnly" title="加入我的常用诊断" @click="markCommon(diag)">★常用</el-button>
              <el-button link type="danger" size="small" :disabled="readOnly" @click="removeDiagnosis(idx)">删</el-button>
            </div>
          </div>
          <div v-else class="dw-collapse-empty">尚未添加诊断</div>
        </div>
      </section>
    `,
    data: function () {
      return {
        keyword: '',
        diagClass: 'west',
        diagClasses: DIAG_CLASSES,
        pickDiagCode: null,
        results: [],
        loading: false,
        selectedDiagnoses: [],
        commonTab: 'personal',
        personalDiags: [],
        deptDiags: [],
        requestSerial: 0
      };
    },
    computed: {
      isExpanded: function () { return true; },
      readOnly: function () { return !this.currentVisit || Number(this.currentVisit.visitStatus) >= 3; },
      visitId: function () { return this.currentVisit && this.currentVisit.id; },
      /* 常用页签数据源: 模板有数据用模板; 两类皆空时仅个人页签兜底硬编码常用项(提示去维护) */
      commonDiagnosisRows: function () {
        var list = this.commonTab === 'personal' ? this.personalDiags : this.deptDiags;
        if (!list.length && !this.deptDiags.length && !this.personalDiags.length && this.commonTab === 'personal') {
          return COMMON_DIAGNOSES;
        }
        return list;
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler: function (id) { this.loadExisting(id); this.loadCommonDiags(); }
      }
    },
    methods: {
      switchCommonTab: function (tab) { this.commonTab = tab; },
      gotoTemplateManage: function () { window.HIS.go('medical-template'); },
      /* 拉取个人/科室常用诊断模板(diag_personal / diag_dept), content=JSON{code,name,category} */
      loadCommonDiags: function () {
        var vm = this;
        var visit = vm.currentVisit || {};
        function parseRows(list) {
          return (list || []).map(function (tpl) {
            var c = tpl.content;
            try { c = typeof c === 'string' ? JSON.parse(c || '{}') : (c || {}); } catch (e) { c = {}; }
            var name = c.name || tpl.name;
            if (!name) { return null; }
            return { code: c.code || tpl.name, ybCode: c.code || tpl.name, name: name, extra: c.category || '', diagClass: c.category || 'west' };
          }).filter(Boolean);
        }
        window.HIS.get('/api/his/template/list?type=diag_personal').then(function (d) { vm.personalDiags = parseRows(d); }).catch(function () { vm.personalDiags = []; });
        window.HIS.get('/api/his/template/list?type=diag_dept').then(function (d) { vm.deptDiags = parseRows(d); }).catch(function () { vm.deptDiags = []; });
      },
      /* ★常用: 已选诊断存为个人常用诊断模板(显式传 staffId, 口径同处方存模板) */
      markCommon: function (diag) {
        var vm = this;
        var visit = vm.currentVisit || {};
        if (!diag || !diag.diagName) { return; }
        window.HIS.post('/api/his/template/create', {
          templateType: 'diag_personal', name: diag.diagName,
          staffId: visit.staffId || visit.drId || null,
          content: JSON.stringify({ code: diag.diagCode, name: diag.diagName, category: diag.diagClass || 'west' }),
          sortOrder: 0, status: 1
        }).then(function () {
          ElementPlus.ElMessage.success('已加入我的常用诊断');
          vm.loadCommonDiags();
        }).catch(function (e) { if (window.HIS.notifyError) { window.HIS.notifyError(e); } });
      },
      classLabel: function (t) {
        var o = DIAG_CLASSES.find(function (c) { return c.v === t; });
        return o ? o.l : (t || '');
      },
      onDiagClassChange: function () { this.results = []; this.pickDiagCode = null; },
      searchDiagnoses: function (addFirst) {
        var vm = this;
        var serial = ++vm.requestSerial;
        vm.loading = true;
        var url = '/api/community-dict/diag-dict/page?status=1&dictType=' + vm.diagClass + '&page=1&size=' + (vm.isExpanded ? 50 : 20);
        if (vm.keyword.trim()) { url += '&keyword=' + encodeURIComponent(vm.keyword.trim()); }
        return window.HIS.get(url).then(function (data) {
          if (serial !== vm.requestSerial) { return; }
          vm.results = (data && data.records) || [];
          if (addFirst) {
            var first = vm.results.find(function (row) { return !vm.isAdded(row); });
            if (first) { vm.addDiagnosis(first); }
          }
        }).catch(window.HIS.notifyError).finally(function () { if (serial === vm.requestSerial) { vm.loading = false; } });
      },
      handleEnter: function () {
        var first = this.results.find(function (row) { return !this.isAdded(row); }, this);
        if (first) { this.addDiagnosis(first); return; }
        this.searchDiagnoses(true);
      },
      /* type-ahead 诊断检索: 医共体统一诊断字典启用项, 选中即加入已选列表 */
      remoteSearchDiag: function (query) {
        var vm = this;
        var kw = String(query || '').trim();
        if (!kw) { vm.results = []; return; }
        var serial = ++vm.requestSerial;
        vm.loading = true;
        window.HIS.get('/api/community-dict/diag-dict/page?status=1&dictType=' + vm.diagClass + '&page=1&size=30&keyword=' + encodeURIComponent(kw)).then(function (data) {
          if (serial !== vm.requestSerial) { return; }
          vm.results = (data && data.records) || [];
        }).catch(window.HIS.notifyError).finally(function () { if (serial === vm.requestSerial) { vm.loading = false; } });
      },
      onPickDiag: function (code) {
        var vm = this;
        if (!code) { return; }
        var row = vm.results.find(function (r) { return (r.diagCode || r.code) === code; });
        vm.pickDiagCode = null;
        if (row) { vm.addDiagnosis(row); }
      },
      isAdded: function (item) {
        var code = item.diagCode || item.code;
        return this.selectedDiagnoses.some(function (diag) { return diag.diagCode === code; });
      },
      addDiagnosis: function (item) {
        if (this.readOnly || !item || this.isAdded(item)) { return; }
        var isFirst = this.selectedDiagnoses.length === 0;
        this.selectedDiagnoses.push({
          _key: item._key || ('diag-' + Date.now() + '-' + Math.random().toString(16).slice(2)),
          diagCode: item.diagCode || item.code,
          diagName: item.diagName || item.name,
          diagClass: item.diagClass || item.dictType || this.diagClass,
          diagType: item.diagType || '1',
          maindiagFlag: isFirst ? '1' : '0',
          diagSrtNo: this.selectedDiagnoses.length + 1,
          /* 对照徽标: 统一字典条目医保码(ybCode)非空即可直接作 2203 diseCodg; 常用项/存量回显无 ybCode 但有码也视为可用 */
          mapped: item.mapped !== undefined ? item.mapped : !!(item.ybCode || item.diagCode || item.code)
        });
        this.normalize();
      },
      removeDiagnosis: function (index) {
        var removedMain = this.selectedDiagnoses[index] && this.selectedDiagnoses[index].maindiagFlag === '1';
        this.selectedDiagnoses.splice(index, 1);
        if (removedMain && this.selectedDiagnoses.length) { this.selectedDiagnoses[0].maindiagFlag = '1'; }
        this.normalize();
      },
      setMain: function (index) {
        var row = this.selectedDiagnoses.splice(index, 1)[0];
        this.selectedDiagnoses.forEach(function (diag) { diag.maindiagFlag = '0'; });
        row.maindiagFlag = '1';
        this.selectedDiagnoses.unshift(row);
        this.normalize();
      },
      move: function (index, offset) {
        var target = index + offset;
        if (index <= 0 || target <= 0 || target >= this.selectedDiagnoses.length) { return; }
        var row = this.selectedDiagnoses.splice(index, 1)[0];
        this.selectedDiagnoses.splice(target, 0, row);
        this.normalize();
      },
      normalize: function (silent) {
        var mainIndex = this.selectedDiagnoses.findIndex(function (diag) { return diag.maindiagFlag === '1'; });
        if (mainIndex > 0) { this.selectedDiagnoses.unshift(this.selectedDiagnoses.splice(mainIndex, 1)[0]); }
        this.selectedDiagnoses.forEach(function (diag, index) {
          diag.maindiagFlag = index === 0 ? '1' : '0';
          diag.diagSrtNo = index + 1;
        });
        if (!silent) { this.notifyChange(); }
      },
      cleanDiagnoses: function () {
        return this.selectedDiagnoses.map(function (diag) {
          return { id: diag.id, visitId: diag.visitId, diagCode: diag.diagCode, diagName: diag.diagName, diagClass: diag.diagClass || null, diagType: diag.diagType || '1', maindiagFlag: diag.maindiagFlag, diagSrtNo: diag.diagSrtNo, valiFlag: diag.valiFlag || '1' };
        });
      },
      notifyChange: function () { this.$emit('update-diagnoses', this.cleanDiagnoses()); },
      loadExisting: function (id) {
        var vm = this;
        vm.selectedDiagnoses = [];
        vm.results = [];
        vm.keyword = '';
        vm.pickDiagCode = null;
        if (!id) { vm.notifyChange(); return; }
        var requestedId = id;
        window.HIS.get('/api/his/visit/detail?id=' + encodeURIComponent(id)).then(function (data) {
          if (vm.visitId !== requestedId) { return; }
          vm.selectedDiagnoses = ((data && data.diagnoses) || []).map(function (diag, index) {
            return Object.assign({}, diag, { _key: 'saved-' + (diag.id || index), mapped: !!diag.diagCode });
          });
          vm.normalize(true);
          vm.notifyChange();
        }).catch(function () { if (vm.visitId === requestedId) { vm.selectedDiagnoses = []; vm.notifyChange(); } });
      }
    }
  };

  window.HIS = window.HIS || {};
  window.HIS.components = window.HIS.components || {};
  window.HIS.components.DwDiagnosisPanel = DiagnosisPanel;
})();