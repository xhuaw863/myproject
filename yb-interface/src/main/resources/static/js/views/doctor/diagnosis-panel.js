/* 门诊医生站诊断录入面板 */
;(function () {
  const COMMON_DIAGNOSES = [
    { code: 'I10.x00', name: '原发性高血压', extra: '循环系统疾病' },
    { code: 'E11.900', name: '2型糖尿病', extra: '内分泌疾病' },
    { code: 'J06.900', name: '急性上呼吸道感染', extra: '呼吸系统疾病' },
    { code: 'M51.202', name: '腰椎间盘突出症', extra: '肌肉骨骼疾病' },
    { code: 'K52.900', name: '急性胃肠炎', extra: '消化系统疾病' },
    { code: 'J20.900', name: '急性支气管炎', extra: '呼吸系统疾病' },
    { code: 'K29.700', name: '胃炎', extra: '消化系统疾病' },
    { code: 'N39.000', name: '泌尿道感染', extra: '泌尿系统疾病' },
    { code: 'R51.x00', name: '头痛', extra: '症状与体征' },
    { code: 'R10.400', name: '腹痛', extra: '症状与体征' }
  ];

  const DiagnosisPanel = {
    name: 'DwDiagnosisPanel',
    inject: ['currentVisit', 'expandedPanel'],
    emits: ['update-diagnoses', 'request-expand', 'request-collapse'],
    provide: function () {
      var vm = this;
      return { diagnoses: Vue.computed(function () { return vm.selectedDiagnoses; }) };
    },
    template: `
      <section class="dw-panel" :class="{ 'is-expanded': isExpanded }">
        <header class="dw-panel-header">
          <span>诊断录入 <span class="dim">{{ selectedDiagnoses.length }} 条</span></span>
          <button class="dw-btn-icon dw-expand-btn" :title="isExpanded ? '还原' : '放大'" @click="toggleExpand">{{ isExpanded ? '↙' : '↗' }}</button>
        </header>

        <div v-if="!currentVisit" class="dw-empty"><el-empty description="请选择患者后录入诊断"></el-empty></div>
        <div v-else style="padding:12px">
          <div class="dw-search-row">
            <el-input v-model="keyword" clearable placeholder="疾病名称 / 编码 / 拼音简码" @keyup.enter="handleEnter" @clear="results=[]">
              <template #append><el-button :loading="loading" @click="searchDiagnoses(false)">检索</el-button></template>
            </el-input>
          </div>

          <el-table :data="results" v-loading="loading" border stripe size="small" :height="isExpanded ? 280 : 160" style="margin-bottom:10px" @row-dblclick="addDiagnosis">
            <el-table-column type="index" label="序号" width="52"></el-table-column>
            <el-table-column prop="code" label="编码" width="120" show-overflow-tooltip></el-table-column>
            <el-table-column prop="name" label="诊断名称" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column prop="extra" label="分类" min-width="120" show-overflow-tooltip></el-table-column>
            <el-table-column label="操作" width="82" align="center">
              <template #default="scope">
                <span v-if="isAdded(scope.row)" class="dw-tag">已添加</span>
                <el-button v-else link type="primary" :disabled="readOnly" @click="addDiagnosis(scope.row)">添加</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="dw-section">已选诊断 <span class="dw-section-extra">主诊断固定首位</span></div>
          <el-table :data="selectedDiagnoses" border stripe size="small" :height="isExpanded ? 330 : undefined" row-key="_key">
            <el-table-column type="index" label="序号" width="52"></el-table-column>
            <el-table-column label="主/次" width="74" align="center">
              <template #default="scope">
                <span v-if="scope.row.maindiagFlag === '1'" class="dw-tag dw-tag--info">★ 主</span>
                <span v-else class="dw-tag">次</span>
              </template>
            </el-table-column>
            <el-table-column prop="diagCode" label="编码" width="118" show-overflow-tooltip></el-table-column>
            <el-table-column prop="diagName" label="诊断名称" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="诊断类型" width="122">
              <template #default="scope">
                <el-select v-model="scope.row.diagType" size="small" :disabled="readOnly" @change="notifyChange">
                  <el-option label="初诊" value="1"></el-option>
                  <el-option label="复诊" value="2"></el-option>
                  <el-option label="疑似（待查/待排）" value="3"></el-option>
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="医保对照" width="94" align="center">
              <template #default="scope">
                <span class="dw-tag" :class="scope.row.mapped !== false ? 'dw-tag--success' : 'dw-tag--warning'">{{ scope.row.mapped !== false ? '✓ 已对照' : '! 未对照' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="顺序" width="82" align="center">
              <template #default="scope">
                <el-button link size="small" :disabled="readOnly || scope.$index <= 1" @click="move(scope.$index, -1)">↑</el-button>
                <el-button link size="small" :disabled="readOnly || scope.$index === 0 || scope.$index >= selectedDiagnoses.length - 1" @click="move(scope.$index, 1)">↓</el-button>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="scope">
                <el-button v-if="scope.row.maindiagFlag !== '1'" link type="primary" size="small" :disabled="readOnly" @click="setMain(scope.$index)">设为主诊断</el-button>
                <el-button link type="danger" size="small" :disabled="readOnly" @click="removeDiagnosis(scope.$index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!selectedDiagnoses.length" class="dw-collapse-empty">尚未添加诊断</div>

          <div class="dw-section" v-if="isExpanded || selectedDiagnoses.length < 4">常用诊断 <span class="dw-section-extra">最近使用 Top 10</span></div>
          <div v-if="isExpanded || selectedDiagnoses.length < 4" style="display:flex;gap:7px;flex-wrap:wrap">
            <button v-for="item in commonDiagnoses" :key="item.code" class="dw-tag" :class="isAdded(item) ? 'dw-tag--success' : 'dw-tag--info'" :disabled="readOnly || isAdded(item)" style="cursor:pointer" @click="addDiagnosis(item)">{{ item.name }}</button>
          </div>
        </div>
      </section>
    `,
    data: function () {
      return {
        keyword: '',
        results: [],
        loading: false,
        selectedDiagnoses: [],
        commonDiagnoses: COMMON_DIAGNOSES,
        requestSerial: 0
      };
    },
    computed: {
      isExpanded: function () { return this.expandedPanel === 'diagnosis'; },
      readOnly: function () { return !this.currentVisit || Number(this.currentVisit.visitStatus) >= 3; },
      visitId: function () { return this.currentVisit && this.currentVisit.id; }
    },
    watch: {
      visitId: {
        immediate: true,
        handler: function (id) { this.loadExisting(id); }
      }
    },
    methods: {
      toggleExpand: function () { this.$emit(this.isExpanded ? 'request-collapse' : 'request-expand', 'diagnosis'); },
      searchDiagnoses: function (addFirst) {
        var vm = this;
        var serial = ++vm.requestSerial;
        vm.loading = true;
        var url = '/api/dict/query/catalog?type=disease&page=1&size=' + (vm.isExpanded ? 50 : 20);
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
          diagType: item.diagType || '1',
          maindiagFlag: isFirst ? '1' : '0',
          diagSrtNo: this.selectedDiagnoses.length + 1,
          mapped: item.mapped !== undefined ? item.mapped : !!(item.diagCode || item.code)
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
          return { id: diag.id, visitId: diag.visitId, diagCode: diag.diagCode, diagName: diag.diagName, diagType: diag.diagType || '1', maindiagFlag: diag.maindiagFlag, diagSrtNo: diag.diagSrtNo, valiFlag: diag.valiFlag || '1' };
        });
      },
      notifyChange: function () { this.$emit('update-diagnoses', this.cleanDiagnoses()); },
      loadExisting: function (id) {
        var vm = this;
        vm.selectedDiagnoses = [];
        vm.results = [];
        vm.keyword = '';
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