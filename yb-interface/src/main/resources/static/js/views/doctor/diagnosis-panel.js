/* 门诊医生站诊断录入面板: 检索统一字典 his_diag_dict(医共体诊断字典启用项), 诊断类别 diagClass 随就诊落库
   OP-B 诊断进阶: 主次诊断/诊断助手(历史+科室高频+个人常用)/疾病报卡/诊断→医嘱模板调入/中医证候配对/口腔牙位图 */
;(function () {
  /* 诊断类别(与后端 his_diag_dict.dict_type 白名单一致) */
  const DIAG_CLASSES = [
    { v: 'west', l: '西医诊断' },
    { v: 'tcm', l: '中医诊断' },
    { v: 'symp', l: '中医症候' },
    { v: 'oper', l: '手术代码' },
    { v: 'tumor', l: '肿瘤代码' }
  ];
  /* 报卡类型(与后端 his_disease_report.report_type 对齐) */
  const REPORT_TYPES = [{ v: 1, l: '法定传染病' }, { v: 2, l: '慢性病' }, { v: 3, l: '其他' }];
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
  /* 口腔相关关键字(命中则展示牙位图入口) */
  function isOralDiag(diag) {
    var nm = (diag && (diag.diagName || diag.name)) || '';
    return /牙|齿|口|颌|龈/.test(nm);
  }

  /* ===== P8a-3 中医诊断-证候组合工具 ===== */
  /* 组合唯一键: 诊断码 + (证候码 或 证候名) */
  function tcmKey(diagCode, syndromeCode, syndromeName) {
    var sk = window.HIS.idKey(syndromeCode) || String(syndromeName == null ? '' : syndromeName).trim();
    return window.HIS.idKey(diagCode) + '|' + sk;
  }
  /* 回拆已保存行"病名 证候证"(无空格/证候不以"证"结尾视为普通行, 不纳入组合协同) */
  function splitTcmAssembled(text) {
    var s = String(text == null ? '' : text).trim();
    var i = s.indexOf(' ');
    if (i <= 0) { return null; }
    var name = s.slice(0, i).trim();
    var syn = s.slice(i + 1).trim();
    if (!name || !syn || syn.charAt(syn.length - 1) !== '证') { return null; }
    return { diagName: name, syndromeName: syn };
  }

  const DiagnosisPanel = {
    name: 'DwDiagnosisPanel',
    components: { 'dw-tooth-chart': window.HIS.components.DwToothChart, 'tcm-diag-selector': window.HIS.components.TcmDiagSelector },
    inject: ['currentVisit'],
    emits: ['update-diagnoses', 'apply-template'],
    provide: function () {
      var vm = this;
      return { diagnoses: Vue.computed(function () { return vm.selectedDiagnoses; }) };
    },
    template: `
      <section class="dw-panel dw-diagnosis-panel" :class="{ 'is-folded': folded }">
        <header class="dw-panel-header">
          <span>诊断录入 <span class="dim">{{ selectedDiagnoses.length }} 条</span></span>
          <button class="dw-collapse-btn" :title="folded ? '展开诊断面板' : '折叠诊断面板'" @click="toggleFold">{{ folded ? '▸' : '▾' }}</button>
        </header>

        <div v-if="!currentVisit" class="dw-slim-empty">未选择患者, 诊断录入暂不可用</div>
        <div v-else-if="!folded" class="dw-diag-body">
          <div class="dw-diag-pick">
            <el-select v-model="diagClass" size="small" style="width:104px;flex:none" :disabled="readOnly" @change="onDiagClassChange">
              <el-option v-for="c in diagClasses" :key="c.v" :label="c.l" :value="c.v"></el-option>
            </el-select>
            <el-select v-if="!isTcmMode" v-model="pickDiagCode" size="small" filterable remote reserve-keyword clearable :remote-method="remoteSearchDiag" :loading="loading" :disabled="readOnly" style="flex:1;min-width:0" placeholder="检索诊断：名称 / 编码 / 拼音简码，选中即添加" @change="onPickDiag">
              <el-option v-for="r in results" :key="r.code" :label="r.name" :value="r.code">
                <div class="dw-diag-opt"><span class="nm">{{ r.name }}</span><span class="code">{{ r.code }}</span><span class="extra">{{ r.extra || r.category || '' }}</span></div>
              </el-option>
            </el-select>
          </div>
          <!-- P8a-3 中医(tcm)/症候(symp)模式: 中医诊断-证候组合选择器替代普通诊断输入, 结果回写下方已选列表 -->
          <tcm-diag-selector v-if="isTcmMode && !readOnly" v-model="tcmCombos" visit-type="outpatient" style="margin-top:8px"></tcm-diag-selector>
          <div v-if="isTcmMode && readOnly" class="dim" style="font-size:12px;margin-top:6px">只读模式: 中医诊断-证候组合见下方已选列表</div>
          <div class="dw-diag-common-wrap" v-if="!isTcmMode">
            <div class="dw-diag-tabs">
              <button class="dw-diag-tab" :class="{'is-active': commonTab==='personal'}" @click="switchCommonTab('personal')">我的常用</button>
              <button class="dw-diag-tab" :class="{'is-active': commonTab==='dept'}" @click="switchCommonTab('dept')">科室诊断</button>
              <button class="dw-diag-tab" :class="{'is-active': commonTab==='assistant'}" @click="switchCommonTab('assistant')">智能助手</button>
              <span class="dim" style="margin-left:auto;font-size:12px">已选 {{ selectedDiagnoses.length }} 条</span>
            </div>

            <!-- 智能助手: 患者历史 / 本科室高频 / 个人常用 三类沉淀, 点击即加入 -->
            <div class="dw-diag-assistant" v-if="commonTab==='assistant'">
              <div v-if="assistantLoading" class="dw-slim-empty">正在聚合诊断候选…</div>
              <template v-else>
                <div class="dw-asst-group" v-if="assistant.history && assistant.history.length">
                  <div class="dw-asst-title">患者历史诊断</div>
                  <div class="dw-diag-common">
                    <button v-for="a in assistant.history" :key="'h'+a.code" class="dw-tag" :class="isAdded(a) ? 'dw-tag--success' : 'dw-tag--info'" :disabled="readOnly || isAdded(a)" @click="addFromAssistant(a)">{{ a.name }}</button>
                  </div>
                </div>
                <div class="dw-asst-group" v-if="assistant.deptFrequent && assistant.deptFrequent.length">
                  <div class="dw-asst-title">本科室高频</div>
                  <div class="dw-diag-common">
                    <button v-for="a in assistant.deptFrequent" :key="'d'+a.code" class="dw-tag" :class="isAdded(a) ? 'dw-tag--success' : 'dw-tag--warning'" :disabled="readOnly || isAdded(a)" @click="addFromAssistant(a)">{{ a.name }}<span class="dim" v-if="a.count">·{{ a.count }}</span></button>
                  </div>
                </div>
                <div class="dw-asst-group" v-if="assistant.personalFrequent && assistant.personalFrequent.length">
                  <div class="dw-asst-title">我的常用</div>
                  <div class="dw-diag-common">
                    <button v-for="a in assistant.personalFrequent" :key="'p'+a.code" class="dw-tag" :class="isAdded(a) ? 'dw-tag--success' : 'dw-tag--info'" :disabled="readOnly || isAdded(a)" @click="addFromAssistant(a)">{{ a.name }}<span class="dim" v-if="a.count">·{{ a.count }}</span></button>
                  </div>
                </div>
                <div v-if="!(assistant.history||[]).length && !(assistant.deptFrequent||[]).length && !(assistant.personalFrequent||[]).length" class="dw-slim-empty">暂无助手候选, 开具并保存诊断后自动沉淀高频项</div>
              </template>
            </div>

            <!-- 常用诊断(个人/科室模板) -->
            <div class="dw-diag-common" v-else-if="commonDiagnosisRows.length">
              <button v-for="item in commonDiagnosisRows" :key="item.code" class="dw-tag" :class="isAdded(item) ? 'dw-tag--success' : 'dw-tag--info'" :disabled="readOnly || isAdded(item)" @click="addDiagnosis(item)">{{ item.name }}</button>
            </div>
            <div v-else class="dw-slim-empty">暂无{{ commonTab==='personal' ? '个人常用' : '科室' }}诊断, 可用已选行的「★常用」沉淀, 或 <a href="javascript:void(0)" @click="gotoTemplateManage">去维护</a></div>
          </div>

          <div class="dw-section" style="margin-top:10px">已选诊断 <span class="dw-section-extra">主诊断固定首位 · 共 {{ selectedDiagnoses.length }} 条</span></div>
          <div class="dw-diag-rows" v-if="selectedDiagnoses.length">
            <div class="dw-diag-row" v-for="(diag, idx) in selectedDiagnoses" :key="diag._key">
              <span class="ord">{{ idx + 1 }}</span>
              <span class="dw-tag" :class="diag.maindiagFlag === '1' ? 'dw-tag--info' : ''">{{ diag.maindiagFlag === '1' ? '★主' : '次' }}</span>
              <span class="nm" :title="diag.diagName + (diag.toothPosition ? ' [' + diag.toothPosition + ']' : '')">{{ diag.diagName }}</span>
              <span class="dw-tag dw-tag--info" v-if="diag.toothPosition" title="牙位">{{ diag.toothPosition }}</span>
              <span class="code">{{ diag.diagCode }}</span>
              <span class="dim" style="font-size:12px">{{ classLabel(diag.diagClass) }}</span>
              <el-select v-model="diag.diagType" size="small" :disabled="readOnly" @change="notifyChange" style="width:96px"><el-option label="初诊" value="1"></el-option><el-option label="复诊" value="2"></el-option><el-option label="疑似" value="3"></el-option></el-select>
              <span class="dw-tag" :class="diag.mapped !== false ? 'dw-tag--success' : 'dw-tag--warning'">{{ diag.mapped !== false ? '✓对照' : '!未对照' }}</span>
              <el-button v-if="diag.maindiagFlag !== '1'" link type="primary" size="small" :disabled="readOnly" @click="setMain(idx)">设主</el-button>
              <el-dropdown trigger="click" :disabled="readOnly" @command="onRowCmd(idx, $event)">
                <el-button link size="small" title="更多行操作">⋯</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="diag.diagClass === 'tcm'" command="symp">配证候</el-dropdown-item>
                    <el-dropdown-item v-if="isOral(diag)" command="tooth">牙位</el-dropdown-item>
                    <el-dropdown-item command="report">疾病报卡</el-dropdown-item>
                    <el-dropdown-item command="common">★ 加入常用</el-dropdown-item>
                    <el-dropdown-item command="remove" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
          <div v-else class="dw-collapse-empty">尚未添加诊断</div>
        </div>

        <!-- 口腔牙位图对话框: 选中牙位拼进 tooth_position(FDI 编码), 仅口腔诊断入口触发 -->
        <el-dialog v-model="toothVisible" title="口腔牙位图 (FDI)" width="380px" append-to-body>
          <dw-tooth-chart v-model="toothTemp"></dw-tooth-chart>
          <template #footer>
            <el-button size="small" @click="toothVisible=false">取消</el-button>
            <el-button size="small" type="primary" @click="saveTooth">确定</el-button>
          </template>
        </el-dialog>

        <!-- 疾病报卡对话框: 与诊断关联留痕, 落 his_disease_report -->
        <el-dialog v-model="reportVisible" title="疾病报卡" width="460px" append-to-body>
          <el-form :model="reportForm" label-width="88px" size="small">
            <el-form-item label="诊断"><el-input :value="reportForm.diagName" disabled/></el-form-item>
            <el-form-item label="报卡类型">
              <el-select v-model="reportForm.reportType" style="width:100%">
                <el-option v-for="t in reportTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="报卡内容"><el-input v-model="reportForm.reportContent" type="textarea" :rows="3" placeholder="发病时间/接触史/初步处置等(选填)"/></el-form-item>
            <div class="dim">提交后生成待报记录并留痕, 上报通道对接由后续批次承接。</div>
          </el-form>
          <template #footer>
            <el-button size="small" @click="reportVisible=false">取消</el-button>
            <el-button size="small" type="primary" :loading="reportSaving" @click="submitReport">提交报卡</el-button>
          </template>
        </el-dialog>
      </section>
    `,
    data: function () {
      return {
        keyword: '',
        diagClass: 'west',
        diagClasses: DIAG_CLASSES,
        reportTypes: REPORT_TYPES,
        pickDiagCode: null,
        results: [],
        loading: false,
        selectedDiagnoses: [],
        /* P8a-3 中医诊断-证候组合(真源; 与 selectedDiagnoses 中 _fromTcm 行双向收敛) */
        tcmCombos: [],
        commonTab: 'personal',
        personalDiags: [],
        deptDiags: [],
        requestSerial: 0,
        /* OP-B 诊断助手 */
        assistant: { history: [], deptFrequent: [], personalFrequent: [] },
        assistantLoaded: false,
        assistantLoading: false,
        /* OP-B 牙位图 */
        toothVisible: false,
        toothEditIndex: -1,
        toothTemp: '',
        /* OP-B 疾病报卡 */
        reportVisible: false,
        reportSaving: false,
        reportForm: { diagCode: '', diagName: '', reportType: 1, reportContent: '' },
        /* U1: 折叠记忆(localStorage) */
        folded: window.localStorage.getItem('dw.diag.folded') === '1'
      };
    },
    computed: {
      isExpanded: function () { return true; },
      readOnly: function () { return !this.currentVisit || Number(this.currentVisit.visitStatus) >= 3; },
      visitId: function () { return this.currentVisit && this.currentVisit.id; },
      /* P8a-3 中医模式: 中医诊断/中医症候 类别时以组合选择器替代普通诊断检索 */
      isTcmMode: function () { return this.diagClass === 'tcm' || this.diagClass === 'symp'; },
      patientId: function () { var v = this.currentVisit || {}; return v.patientId || null; },
      deptId: function () { var v = this.currentVisit || {}; return v.deptId || null; },
      staffId: function () { var v = this.currentVisit || {}; return v.staffId || v.drId || null; },
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
      /* 冷启动(无就诊)刻意不加 immediate: immediate 期初始化会在子组件 setup 内同步 $emit('update-diagnoses') 改父 provide 的诊断状态,
         叠加同挂的诊前预问诊对话框触发 Vue 渲染风暴(整个医生工作站卡死无响应)。data 默认值已是空态, 选中患者(visitId 变化)时再初始化即可。 */
      visitId: function (id) { this.loadExisting(id); this.loadCommonDiags(); this.assistantLoaded = false; },
      /* P8a-3 组合列表变更 → 诊断行幂等收敛(选择器 v-model / 删行回筛均经此) */
      tcmCombos: function (nv) { this.syncTcmCombos(Array.isArray(nv) ? nv : []); }
    },
    methods: {
      isOral: isOralDiag,
      /* ===== U1: 折叠记忆与定位 / 行操作下拉命令路由 ===== */
      toggleFold: function () {
        this.folded = !this.folded;
        try { window.localStorage.setItem('dw.diag.folded', this.folded ? '1' : '0'); } catch (e) { /* 隐私模式忽略 */ }
      },
      revealForLocate: function () { this.folded = false; },
      onRowCmd: function (idx, cmd) {
        var diag = this.selectedDiagnoses[idx];
        if (!diag) { return; }
        if (cmd === 'symp') { this.pairSyndrome(); }
        else if (cmd === 'tooth') { this.openTooth(idx); }
        else if (cmd === 'report') { this.openReport(diag); }
        else if (cmd === 'common') { this.markCommon(diag); }
        else if (cmd === 'remove') { this.removeDiagnosis(idx); }
      },
      switchCommonTab: function (tab) {
        this.commonTab = tab;
        if (tab === 'assistant' && !this.assistantLoaded) { this.loadAssistant(); }
      },
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
      /* 诊断助手聚合: 患者历史 / 本科室高频 / 个人常用 */
      loadAssistant: function () {
        var vm = this;
        vm.assistantLoading = true;
        var q = '/api/his/diagnosis/assistant?limit=15';
        if (vm.patientId) { q += '&patientId=' + encodeURIComponent(vm.patientId); }
        if (vm.deptId) { q += '&deptId=' + encodeURIComponent(vm.deptId); }
        if (vm.staffId) { q += '&staffId=' + encodeURIComponent(vm.staffId); }
        window.HIS.get(q).then(function (d) {
          d = d || {};
          vm.assistant = { history: d.history || [], deptFrequent: d.deptFrequent || [], personalFrequent: d.personalFrequent || [] };
          vm.assistantLoaded = true;
        }).catch(function () { vm.assistant = { history: [], deptFrequent: [], personalFrequent: [] }; }).finally(function () { vm.assistantLoading = false; });
      },
      /* 助手候选点击加入(统一按 code/name/clazz 规整为诊断行) */
      addFromAssistant: function (a) {
        this.addDiagnosis({ code: a.code, ybCode: a.code, name: a.name, diagClass: a.clazz || 'west', mapped: true });
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
      /* 中医证候配对: 切换到证候类别并聚焦检索, 由医生续配本次 tcm 病名对应证候 */
      pairSyndrome: function () {
        this.diagClass = 'symp';
        this.results = [];
        ElementPlus.ElMessage.info('已切换到"中医症候", 检索并添加证候即与上方病名配对(同存 his_diagnosis, 类别 symp)');
      },

      /* ---------- P8a-3 中医诊断-证候组合联动 ---------- */
      /* 诊断行键(与组合键同构) */
      tcmRowKeyOf: function (row) {
        return tcmKey(row.diagCode, row._tcmSyndromeCode, row._tcmSyndromeName);
      },
      /* 组合 → 诊断行 构造(名称即拼装文本"病名 证候证", 落 his_diagnosis 单行) */
      buildTcmRow: function (c) {
        return {
          _key: 'tcm-' + Date.now() + '-' + Math.random().toString(16).slice(2),
          diagCode: c.diagCode,
          diagName: c.assembledText || c.diagName,
          diagClass: this.isTcmMode ? this.diagClass : 'tcm',
          diagType: '1',
          maindiagFlag: '0',
          diagSrtNo: this.selectedDiagnoses.length + 1,
          toothPosition: null,
          mapped: !!c.diagCode,
          _fromTcm: true,
          _tcmSyndromeCode: c.syndromeCode || null,
          _tcmSyndromeName: c.syndromeName || ''
        };
      },
      /* 幂等收敛: 删除失联组合行, 补插缺失组合行(组合列表为真源; 值同则不动) */
      syncTcmCombos: function (combos) {
        var vm = this;
        var want = {};
        (combos || []).forEach(function (c) { want[tcmKey(c.diagCode, c.syndromeCode, c.syndromeName)] = true; });
        var kept = vm.selectedDiagnoses.filter(function (row) {
          return !row._fromTcm || !!want[vm.tcmRowKeyOf(row)];
        });
        var changed = kept.length !== vm.selectedDiagnoses.length;
        if (changed) { vm.selectedDiagnoses = kept; }
        var have = {};
        vm.selectedDiagnoses.forEach(function (row) { if (row._fromTcm) { have[vm.tcmRowKeyOf(row)] = true; } });
        (combos || []).forEach(function (c) {
          var k = tcmKey(c.diagCode, c.syndromeCode, c.syndromeName);
          if (have[k]) { return; }
          have[k] = true;
          changed = true;
          vm.selectedDiagnoses.push(vm.buildTcmRow(c));
        });
        if (changed) { vm.normalize(); }
      },
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
          toothPosition: item.toothPosition || null,
          /* 对照徽标: 统一字典条目医保码(ybCode)非空即可直接作 2203 diseCodg; 常用项/存量回显无 ybCode 但有码也视为可用 */
          mapped: item.mapped !== undefined ? item.mapped : !!(item.ybCode || item.diagCode || item.code)
        });
        this.normalize();
        this.checkDiagTemplate(this.selectedDiagnoses[this.selectedDiagnoses.length - 1]);
      },
      /* OP-B 诊断→医嘱/处方模板提示: 命中映射则询问是否调入, 确认后经主编排中继落到处方/医嘱面板 */
      checkDiagTemplate: function (diag) {
        var vm = this;
        if (!diag || !diag.diagCode) { return; }
        var q = '/api/his/diag-template-link?diagCode=' + encodeURIComponent(diag.diagCode);
        if (vm.deptId) { q += '&deptId=' + encodeURIComponent(vm.deptId); }
        window.HIS.get(q).then(function (links) {
          links = links || [];
          if (!links.length) { return; }
          var names = links.map(function (l) { return l.templateName + '(' + (l.templateType === 'order_set' ? '医嘱' : '处方') + ')'; }).join('、');
          ElementPlus.ElMessageBox.confirm('诊断「' + diag.diagName + '」关联模板: ' + names + '。是否调入第一个?', '诊断关联模板', { type: 'info', confirmButtonText: '调入', cancelButtonText: '不用了' })
            .then(function () { vm.$emit('apply-template', links[0]); })
            .catch(function () {});
        }).catch(function () {});
      },
      /* OP-B 牙位图 */
      openTooth: function (idx) {
        var diag = this.selectedDiagnoses[idx];
        if (!diag) { return; }
        this.toothEditIndex = idx;
        this.toothTemp = diag.toothPosition || '';
        this.toothVisible = true;
      },
      saveTooth: function () {
        var diag = this.selectedDiagnoses[this.toothEditIndex];
        if (diag) { diag.toothPosition = this.toothTemp || null; this.notifyChange(); }
        this.toothVisible = false;
      },
      /* OP-B 疾病报卡 */
      openReport: function (diag) {
        if (!diag) { return; }
        this.reportForm = { diagCode: diag.diagCode, diagName: diag.diagName, reportType: 1, reportContent: '' };
        this.reportVisible = true;
      },
      submitReport: function () {
        var vm = this;
        var visit = vm.currentVisit || {};
        if (!visit.id) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        vm.reportSaving = true;
        window.HIS.post('/api/his/disease-report', {
          visitId: visit.id,
          patientId: vm.patientId,
          diagCode: vm.reportForm.diagCode,
          diagName: vm.reportForm.diagName,
          reportType: vm.reportForm.reportType,
          reportContent: vm.reportForm.reportContent
        }).then(function () {
          ElementPlus.ElMessage.success('报卡已登记(待报)');
          vm.reportVisible = false;
        }).catch(function (e) { if (window.HIS.notifyError) { window.HIS.notifyError(e); } }).finally(function () { vm.reportSaving = false; });
      },
      removeDiagnosis: function (index) {
        var row = this.selectedDiagnoses[index];
        var removedMain = row && row.maindiagFlag === '1';
        /* P8a-3 删组合行时同步从组合列表移除(经 watch 收敛), 避免选择器已选区残留 */
        if (row && row._fromTcm) {
          var key = this.tcmRowKeyOf(row);
          this.tcmCombos = this.tcmCombos.filter(function (c) {
            return tcmKey(c.diagCode, c.syndromeCode, c.syndromeName) !== key;
          });
        }
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
          return { id: diag.id, visitId: diag.visitId, diagCode: diag.diagCode, diagName: diag.diagName, diagClass: diag.diagClass || null, diagType: diag.diagType || '1', maindiagFlag: diag.maindiagFlag, diagSrtNo: diag.diagSrtNo, toothPosition: diag.toothPosition || null, valiFlag: diag.valiFlag || '1' };
        });
      },
      notifyChange: function () { this.$emit('update-diagnoses', this.cleanDiagnoses()); },
      loadExisting: function (id) {
        var vm = this;
        vm.selectedDiagnoses = [];
        vm.tcmCombos = [];
        vm.results = [];
        vm.keyword = '';
        vm.pickDiagCode = null;
        if (!id) { vm.notifyChange(); return; }
        var requestedId = id;
        window.HIS.get('/api/his/visit/detail?id=' + encodeURIComponent(id)).then(function (data) {
          if (vm.visitId !== requestedId) { return; }
          var combos = [];
          vm.selectedDiagnoses = ((data && data.diagnoses) || []).map(function (diag, index) {
            var row = Object.assign({}, diag, { _key: 'saved-' + (diag.id || index), mapped: !!diag.diagCode });
            /* P8a-3 中医行回拆"病名 证候证"重建组合(his_diagnosis 无证候列, 名称即拼装文本) */
            if ((row.diagClass === 'tcm' || row.diagClass === 'symp') && row.diagName) {
              var sp = splitTcmAssembled(row.diagName);
              if (sp) {
                row._fromTcm = true;
                row._tcmSyndromeCode = null;
                row._tcmSyndromeName = sp.syndromeName;
                combos.push({
                  diagCode: row.diagCode,
                  diagName: sp.diagName,
                  syndromeCode: null,
                  syndromeName: sp.syndromeName,
                  assembledText: row.diagName
                });
              }
            }
            return row;
          });
          vm.tcmCombos = combos;
          vm.normalize(true);
          vm.notifyChange();
        }).catch(function () { if (vm.visitId === requestedId) { vm.selectedDiagnoses = []; vm.tcmCombos = []; vm.notifyChange(); } });
      }
    }
  };

  window.HIS = window.HIS || {};
  window.HIS.components = window.HIS.components || {};
  window.HIS.components.DwDiagnosisPanel = DiagnosisPanel;
})();
