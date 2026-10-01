/* 首页编目(病案统计科侧): 编目列表(过滤/我的待办) + 编目编辑器(首页头/诊断/手术/扩展/留痕)。
 * 后端 /api/his/mr/catalog: list / generate/{visitId} / {visitId}(详情) / {visitId}/header(PUT)
 *   / {visitId}/diags(PUT) / {visitId}/opers(PUT) / {visitId}/others/{recType}(PUT)
 *   / {visitId}/finalize(POST) / {visitId}/validate(GET)。
 * 铁律: 编目侧不回写临床首页, 仅在编目态快照(his_mr_catalog/diag/oper/other)上修订; 已锁定(409)不可改。
 * 校验: 右侧强制/非强制错误项, 点击定位切换左栏 Tab; 强制类未清不可定稿。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var DIAG_TYPES = [
    { value: 'outp', label: '门急诊' }, { value: 'adm', label: '入院' },
    { value: 'dmain', label: '出院主诊' }, { value: 'dother', label: '出院次诊' },
    { value: 'path', label: '病理' }, { value: 'injure', label: '损伤中毒外因' }, { value: 'infect', label: '院内感染' }
  ];
  var REC_TYPES = [
    { key: 'transfer', label: '转科记录', cols: ['code', 'name'] },
    { key: 'allergy', label: '过敏药物', cols: ['code', 'name'] },
    { key: 'icu', label: '重症监护', cols: ['name', 'beginTime', 'endTime'] }
  ];

  HIS.views.MrCatalog = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        /* 列表态 */
        view: 'list',
        loading: false,
        rows: [], page: 1, size: 20, total: 0,
        keyword: '', catalogStatus: null, deptId: null, myTodo: false,
        depts: [], deptsLoading: false,
        /* 详情态 */
        detailLoading: false, saving: false, activeTab: 'header',
        visitId: null,
        catalog: null,
        header: {},
        summary: {},
        diags: [], opers: [],
        others: { transfer: [], allergy: [], icu: [] },
        errors: [], logs: [],
        /* P3-D 临床首页调阅 */
        clinicalPage: null, clinicalLoading: false,
        /* 定位高亮 */
        flashField: '',
        diagTypes: DIAG_TYPES,
        recTypes: REC_TYPES
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      },
      locked: function () { return this.catalog && Number(this.catalog.lockStatus) === 1; },
      finalized: function () { return this.catalog && Number(this.catalog.catalogStatus) >= 3; },
      /* 可编辑判定须与后端 requireEditable 对齐: 仅锁定(lock=1)禁改; 定稿(3)但未锁(如质控解锁退回)仍可改。 */
      editable: function () { return this.canWrite && !this.locked; },
      forceErrs: function () { return (this.errors || []).filter(function (e) { return e.ruleCategory === '强制'; }); },
      softErrs: function () { return (this.errors || []).filter(function (e) { return e.ruleCategory !== '强制'; }); }
    },
    created: function () {
      this.loadDepts();
      this.loadList();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ================= 列表 ================= */
      loadDepts: function () {
        var vm = this; vm.deptsLoading = true;
        HIS.get('/api/his/dept/tree').then(function (d) {
          var out = [];
          (function walk(list, depth) {
            (list || []).forEach(function (n) {
              var pad = ''; for (var i = 0; i < depth; i++) { pad += '　'; }
              out.push({ id: n.id, label: pad + (n.deptName || ('科室' + n.id)), deptName: n.deptName, deptCode: n.deptCode });
              if (n.children && n.children.length) { walk(n.children, depth + 1); }
            });
          })(d || [], 0);
          vm.depts = out;
        }).catch(function () { vm.depts = []; }).finally(function () { vm.deptsLoading = false; });
      },
      loadList: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/mr/catalog/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.catalogStatus != null) { q += '&catalogStatus=' + vm.catalogStatus; }
        if (vm.deptId != null) { q += '&deptId=' + HIS.idParam(vm.deptId); }
        /* catalogerId 落的是编目员 his_staff.id, 故"我的待办"按登录用户 staffId 过滤 */
        if (vm.myTodo && HIS.getUser() && HIS.getUser().staffId != null) { q += '&catalogerId=' + HIS.idParam(HIS.getUser().staffId); }
        HIS.get(q).then(function (d) { vm.rows = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadList(); },
      reset: function () { this.keyword = ''; this.catalogStatus = null; this.deptId = null; this.myTodo = false; this.page = 1; this.loadList(); },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },

      /* ================= 详情 ================= */
      openDetail: function (row) {
        var vm = this;
        vm.visitId = HIS.id(row.visitId);
        vm.view = 'detail';
        vm.loadDetail();
      },
      backList: function () { vm_resetDetail(this); this.view = 'list'; this.loadList(); },
      loadDetail: function () {
        var vm = this; vm.detailLoading = true; vm.activeTab = 'header'; vm.clinicalPage = null;
        HIS.get('/api/his/mr/catalog/' + HIS.idParam(vm.visitId)).then(function (d) {
          var c = d.catalog || {};
          vm.catalog = c;
          vm.header = {
            mainDiagCode: c.mainDiagCode || '', mainDiagName: c.mainDiagName || '',
            isTcm: Number(c.isTcm || 0),
            admissionDate: (c.admissionDate || '').slice(0, 10),
            dischargeDate: (c.dischargeDate || '').slice(0, 10),
            /* dept id 后端雪花/Long 以字符串下发, 与 el-option :value="d.id"(字符串)同型才能命中并显示科室名; Number() 化会退化成裸 ID 显示(#5) */
            dischargeDeptId: HIS.id(c.dischargeDeptId),
            admissionDeptId: HIS.id(c.admissionDeptId),
            catalogerId: HIS.id(c.catalogerId),
            catalogerName: c.catalogerName || ''
          };
          try { vm.summary = c.summary ? JSON.parse(c.summary) : {}; } catch (e) { vm.summary = {}; }
          vm.diags = (d.diags || []).map(normalizeDiag);
          vm.opers = (d.opers || []).map(normalizeOper);
          var others = { transfer: [], allergy: [], icu: [] };
          (d.others || []).forEach(function (o) { if (others[o.recType]) { others[o.recType].push(normalizeOther(o)); } });
          vm.others = others;
          vm.errors = d.errors || [];
          vm.logs = d.logs || [];
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },

      /* ================= 诊断明细 ================= */
      addDiag: function () {
        this.diags.push({ id: null, diagType: 'dother', clinicalCode: '', clinicalName: '', ybCode: '', ybName: '', ybSortNo: null, reportFlag: 0, grayFlag: 0, mainFlag: 0, doctorDesc: '' });
      },
      delDiag: function (i) { this.diags.splice(i, 1); },
      saveDiags: function () {
        var vm = this;
        vm.withSave(HIS.put('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/diags', vm.diags.map(stripDiag)), '诊断明细已保存');
      },

      /* ================= 手术明细 ================= */
      addOper: function () {
        this.opers.push({ id: null, clinicalCode: '', clinicalName: '', ybCode: '', ybName: '', ybSortNo: null, reportFlag: 0, mainFlag: 0, grayFlag: 0, operDate: '', surgeonName: '', anesthesia: '', incisionType: '', healLevel: '' });
      },
      delOper: function (i) { this.opers.splice(i, 1); },
      saveOpers: function () {
        var vm = this;
        vm.withSave(HIS.put('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/opers', vm.opers.map(stripOper)), '手术明细已保存');
      },

      /* ================= 扩展记录 ================= */
      addOther: function (type) { this.others[type].push({ id: null, code: '', name: '', detail: '', beginTime: '', endTime: '' }); },
      delOther: function (type, i) { this.others[type].splice(i, 1); },
      saveOthers: function (type) {
        var vm = this;
        vm.withSave(HIS.put('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/others/' + type, vm.others[type].map(stripOther)), (vm.recLabel(type) + '已保存'));
      },
      recLabel: function (t) { for (var i = 0; i < REC_TYPES.length; i++) { if (REC_TYPES[i].key === t) { return REC_TYPES[i].label; } } return t; },

      /* ================= 首页头保存 ================= */
      saveHeader: function () {
        var vm = this;
        var body = Object.assign({}, vm.header);
        vm.withSave(HIS.put('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/header', body), '首页头信息已保存');
      },

      /* ================= 校验 / 定稿 ================= */
      doValidate: function () {
        var vm = this;
        HIS.get('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/validate').then(function (list) {
          vm.errors = list || [];
          if (!vm.errors.length) { HIS.notifySuccess('校验通过, 无错误项'); }
          else { ElementPlus.ElMessage.warning('存在 ' + vm.errors.length + ' 项质控提示'); }
        }).catch(HIS.notifyError);
      },
      doFinalize: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('定稿前请确保已保存头/诊断/手术改动。定稿后进入审核队列, 强制类错误未清将拦截。确认定稿?', '编目定稿', { type: 'warning' })
          .then(function () {
            return HIS.post('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/finalize', {});
          }).then(function (errs) {
            vm.errors = errs || [];
            HIS.notifySuccess('已定稿');
            vm.loadDetail();
          }).catch(function (e) { if (e && e.message) { HIS.notifyError(e); } });
      },

      /* ================= 定位 ================= */
      locate: function (e) {
        var fk = e.fieldKey || '';
        if (fk.indexOf('diag') === 0) { this.activeTab = 'diag'; }
        else if (fk.indexOf('oper') === 0) { this.activeTab = 'oper'; }
        else if (fk.indexOf('other') === 0 || fk.indexOf('transfer') === 0 || fk.indexOf('allergy') === 0 || fk.indexOf('icu') === 0) { this.activeTab = 'other'; }
        else { this.activeTab = 'header'; }
        var vm = this;
        vm.flashField = fk;
        setTimeout(function () { vm.flashField = ''; }, 1600);
      },

      /* 通用保存包装 */
      withSave: function (promise, okMsg) {
        var vm = this; vm.saving = true;
        promise.then(function () { HIS.notifySuccess(okMsg); return vm.reloadValidate(); })
          .catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      reloadValidate: function () {
        var vm = this;
        return HIS.get('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/validate').then(function (list) { vm.errors = list || []; }).catch(function () { });
      },

      /* 展示辅助 */
      catStatusText: function (s) { return { 1: '待编目', 2: '编目中', 3: '已编目' }[Number(s)] || '-'; },
      auditStatusText: function (s) { return { 1: '未审核', 2: '已审核', 3: '已确认' }[Number(s)] || '-'; },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (HIS.sameId(this.depts[i].id, id)) { return this.depts[i].deptName; } } return ''; },
      money: function (v) { if (v == null || v === '') { return '-'; } var n = Number(v); return isNaN(n) ? v : n.toFixed(2); },
      /* P3-D 懒加载临床首页 */
      loadClinicalPage: function () {
        var vm = this;
        if (vm.clinicalPage) return;
        vm.clinicalLoading = true;
        HIS.get('/api/his/mr/catalog/' + HIS.idParam(vm.visitId) + '/clinical-page').then(function (d) {
          vm.clinicalPage = d || {};
        }).catch(function () { vm.clinicalPage = {}; }).finally(function () { vm.clinicalLoading = false; });
      },
      cpStatusText: function (s) { return { 1: '草稿', 2: '已提交', 3: '已审核' }[Number(s)] || '-'; }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading || detailLoading">',
      /* ========== 列表态 ========== */
      '  <template v-if="view===\'list\'">',
      '    <div class="page-title">首页编目 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">读取临床首页生成编目快照 · 按国家临床版修订诊断/手术并对照医保版 · 校验后定稿</span></div>',
      '    <div class="toolbar" style="margin-bottom:10px;flex:none;">',
      '      <el-input v-model="keyword" size="small" placeholder="姓名 / 住院号 / 病案号" clearable style="width:200px;" @keyup.enter="search"></el-input>',
      '      <el-select v-model="catalogStatus" size="small" clearable placeholder="编目状态" style="width:120px;">',
      '        <el-option label="待编目" :value="1"></el-option><el-option label="编目中" :value="2"></el-option><el-option label="已编目" :value="3"></el-option>',
      '      </el-select>',
      '      <el-select v-model="deptId" size="small" filterable clearable placeholder="出院科室" style="width:180px" :loading="deptsLoading" :filter-method="kwFilter(\'dept\')">',
      '        <el-option v-for="d in kwOptions(\'dept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '      </el-select>',
      '      <el-checkbox v-model="myTodo" size="small" @change="search">我的待办</el-checkbox>',
      '      <el-button size="small" type="primary" @click="search">查询</el-button>',
      '      <el-button size="small" @click="reset">重置</el-button>',
      '    </div>',
      '    <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无编目病案(请先在病案分配页分配)">',
      '      <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '      <el-table-column prop="catalogNo" label="病案号" width="120"></el-table-column>',
      '      <el-table-column prop="patientName" label="姓名" width="90"></el-table-column>',
      '      <el-table-column prop="genderName" label="性别" width="55" align="center"></el-table-column>',
      '      <el-table-column prop="inpNo" label="住院号" width="110"></el-table-column>',
      '      <el-table-column prop="dischargeDeptName" label="出院科室" min-width="110"></el-table-column>',
      '      <el-table-column prop="mainDiagName" label="主要诊断" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="编目" width="80" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ catStatusText(s.row.catalogStatus) }}</el-tag></template></el-table-column>',
      '      <el-table-column label="审核" width="80" align="center"><template #default="s"><el-tag size="small" type="info" effect="plain">{{ auditStatusText(s.row.auditStatus) }}</el-tag></template></el-table-column>',
      '      <el-table-column label="锁定" width="60" align="center"><template #default="s"><el-tag v-if="Number(s.row.lockStatus)===1" size="small" type="danger">锁</el-tag><span v-else>-</span></template></el-table-column>',
      '      <el-table-column prop="catalogerName" label="编目员" width="90"></el-table-column>',
      '      <el-table-column label="操作" width="80" align="center" fixed="right"><template #default="s"><el-button type="primary" link size="small" @click="openDetail(s.row)">编目</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '      :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </template>',

      /* ========== 详情态 ========== */
      '  <template v-else>',
      '    <div class="toolbar" style="margin-bottom:8px;flex:none;">',
      '      <el-button size="small" @click="backList">← 返回列表</el-button>',
      '      <span style="font-weight:600;margin-left:8px;">{{ catalog ? catalog.catalogNo : \'\' }}</span>',
      '      <el-tag size="small" effect="plain" style="margin-left:8px;">编目:{{ catStatusText(catalog && catalog.catalogStatus) }}</el-tag>',
      '      <el-tag size="small" type="info" effect="plain">审核:{{ auditStatusText(catalog && catalog.auditStatus) }}</el-tag>',
      '      <el-tag v-if="locked" size="small" type="danger">已锁定</el-tag>',
      '      <el-tag v-if="finalized && !locked" size="small" type="success">已定稿</el-tag>',
      '      <span style="flex:1"></span>',
      '      <el-button size="small" :loading="saving" @click="doValidate">校验</el-button>',
      '      <el-button v-if="canWrite && !finalized && !locked" size="small" type="success" :loading="saving" @click="doFinalize">定稿</el-button>',
      '    </div>',
      '    <el-alert v-if="locked" type="error" :closable="false" show-icon style="margin-bottom:8px;flex:none;" title="该病案已锁定, 需质控人员在质量审核页解锁后方可修改。"></el-alert>',
      '    <div style="display:flex;gap:12px;flex:1;min-height:0;">',
      /* ---- 左: 编辑区 tabs ---- */
      '      <div style="flex:1;min-width:0;display:flex;flex-direction:column;">',
      '        <el-tabs v-model="activeTab" style="flex:1;min-height:0;display:flex;flex-direction:column;">',
      /* 首页头 */
      '          <el-tab-pane label="首页信息" name="header">',
      '            <el-form label-width="110px" size="small" :disabled="!editable" style="max-height:100%;overflow:auto;">',
      '              <el-divider content-position="left">主要诊断与标志</el-divider>',
      '              <el-row :gutter="12">',
      '                <el-col :span="8"><el-form-item label="主诊编码" :class="flashField===\'catalog.mainDiagCode\'?\'mr-flash\':\'\'" ><el-input v-model="header.mainDiagCode"></el-input></el-form-item></el-col>',
      '                <el-col :span="10"><el-form-item label="主诊名称"><el-input v-model="header.mainDiagName"></el-input></el-form-item></el-col>',
      '                <el-col :span="6"><el-form-item label="中医病案"><el-switch v-model="header.isTcm" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '              </el-row>',
      '              <el-divider content-position="left">住院信息</el-divider>',
      '              <el-row :gutter="12">',
      '                <el-col :span="8"><el-form-item label="入院日期"><el-date-picker v-model="header.admissionDate" type="date" value-format="YYYY-MM-DD" style="width:100%;"></el-date-picker></el-form-item></el-col>',
      '                <el-col :span="8"><el-form-item label="出院日期" :class="flashField===\'catalog.dischargeDate\'?\'mr-flash\':\'\'"><el-date-picker v-model="header.dischargeDate" type="date" value-format="YYYY-MM-DD" style="width:100%;"></el-date-picker></el-form-item></el-col>',
      '                <el-col :span="8"><el-form-item label="住院天数"><el-input :model-value="catalog ? catalog.losDays : \'\'" disabled></el-input></el-form-item></el-col>',
      '              </el-row>',
      '              <el-row :gutter="12">',
      '                <el-col :span="12"><el-form-item label="入院科室"><el-select v-model="header.admissionDeptId" filterable clearable style="width:100%;" :filter-method="kwFilter(\'ad\')"><el-option v-for="d in kwOptions(\'ad\', depts, [\'label\',\'deptName\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option></el-select></el-form-item></el-col>',
      '                <el-col :span="12"><el-form-item label="出院科室" :class="flashField===\'catalog.dischargeDeptId\'?\'mr-flash\':\'\'"><el-select v-model="header.dischargeDeptId" filterable clearable style="width:100%;" :filter-method="kwFilter(\'dd\')"><el-option v-for="d in kwOptions(\'dd\', depts, [\'label\',\'deptName\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option></el-select></el-form-item></el-col>',
      '              </el-row>',
      '              <el-divider content-position="left">费用概览(临床首页快照 · 只读)</el-divider>',
      '              <el-descriptions :column="4" border size="small">',
      '                <el-descriptions-item label="总费用">{{ money(summary.totalCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="药费">{{ money(summary.drugCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="检查费">{{ money(summary.examCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="治疗费">{{ money(summary.treatmentCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="床位费">{{ money(summary.bedCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="护理费">{{ money(summary.nursingCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="材料费">{{ money(summary.materialCost) }}</el-descriptions-item>',
      '                <el-descriptions-item label="自付">{{ money(summary.selfPay) }}</el-descriptions-item>',
      '              </el-descriptions>',
      '            </el-form>',
      '            <div style="margin-top:8px;"><el-button v-if="editable" type="primary" size="small" :loading="saving" @click="saveHeader">保存首页头</el-button></div>',
      '          </el-tab-pane>',
      /* 诊断 */
      '          <el-tab-pane label="诊断明细" name="diag">',
      '            <div style="margin-bottom:6px;"><el-button v-if="editable" size="small" @click="addDiag">新增诊断</el-button>',
      '              <el-button v-if="editable" type="primary" size="small" :loading="saving" @click="saveDiags">保存诊断</el-button></div>',
      '            <el-table :data="diags" border size="small" max-height="calc(100vh - 320px)" :class="flashField && flashField.indexOf(\'diag\')===0 ? \'mr-flash\' : \'\'">',
      '              <el-table-column type="index" label="#" width="45" align="center"></el-table-column>',
      '              <el-table-column label="类型" width="120"><template #default="s"><el-select v-model="s.row.diagType" size="small" :disabled="!editable"><el-option v-for="t in diagTypes" :key="t.value" :label="t.label" :value="t.value"></el-option></el-select></template></el-table-column>',
      '              <el-table-column label="国临版编码" width="120"><template #default="s"><el-input v-model="s.row.clinicalCode" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="国临版名称" min-width="150"><template #default="s"><el-input v-model="s.row.clinicalName" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="医保版编码" width="120"><template #default="s"><el-input v-model="s.row.ybCode" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="医保版名称" min-width="140"><template #default="s"><el-input v-model="s.row.ybName" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="位序" width="70"><template #default="s"><el-input-number v-model="s.row.ybSortNo" size="small" :min="0" :controls="false" :disabled="!editable" style="width:100%;"></el-input-number></template></el-table-column>',
      '              <el-table-column label="主诊" width="60" align="center"><template #default="s"><el-checkbox v-model="s.row.mainFlag" :true-label="1" :false-label="0" :disabled="!editable"></el-checkbox></template></el-table-column>',
      '              <el-table-column label="上报" width="60" align="center"><template #default="s"><el-checkbox v-model="s.row.reportFlag" :true-label="1" :false-label="0" :disabled="!editable"></el-checkbox></template></el-table-column>',
      '              <el-table-column label="灰码" width="60" align="center"><template #default="s"><el-tag v-if="Number(s.row.grayFlag)===1" size="small" type="warning">灰</el-tag></template></el-table-column>',
      '              <el-table-column label="操作" width="60" align="center"><template #default="s"><el-button v-if="editable" type="danger" link size="small" @click="delDiag(s.$index)">删</el-button></template></el-table-column>',
      '            </el-table>',
      '          </el-tab-pane>',
      /* 手术 */
      '          <el-tab-pane label="手术操作" name="oper">',
      '            <div style="margin-bottom:6px;"><el-button v-if="editable" size="small" @click="addOper">新增手术</el-button>',
      '              <el-button v-if="editable" type="primary" size="small" :loading="saving" @click="saveOpers">保存手术</el-button></div>',
      '            <el-table :data="opers" border size="small" max-height="calc(100vh - 320px)" :class="flashField && flashField.indexOf(\'oper\')===0 ? \'mr-flash\' : \'\'">',
      '              <el-table-column type="index" label="#" width="45" align="center"></el-table-column>',
      '              <el-table-column label="国临版编码" width="120"><template #default="s"><el-input v-model="s.row.clinicalCode" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="国临版名称" min-width="150"><template #default="s"><el-input v-model="s.row.clinicalName" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="医保版编码" width="110"><template #default="s"><el-input v-model="s.row.ybCode" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="医保版名称" min-width="130"><template #default="s"><el-input v-model="s.row.ybName" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="手术日期" width="130"><template #default="s"><el-date-picker v-model="s.row.operDate" type="date" value-format="YYYY-MM-DD" size="small" :disabled="!editable" style="width:100%;"></el-date-picker></template></el-table-column>',
      '              <el-table-column label="术者" width="90"><template #default="s"><el-input v-model="s.row.surgeonName" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '              <el-table-column label="主刀" width="60" align="center"><template #default="s"><el-checkbox v-model="s.row.mainFlag" :true-label="1" :false-label="0" :disabled="!editable"></el-checkbox></template></el-table-column>',
      '              <el-table-column label="上报" width="60" align="center"><template #default="s"><el-checkbox v-model="s.row.reportFlag" :true-label="1" :false-label="0" :disabled="!editable"></el-checkbox></template></el-table-column>',
      '              <el-table-column label="操作" width="60" align="center"><template #default="s"><el-button v-if="editable" type="danger" link size="small" @click="delOper(s.$index)">删</el-button></template></el-table-column>',
      '            </el-table>',
      '          </el-tab-pane>',
      /* 扩展 */
      '          <el-tab-pane label="转科/过敏/重症" name="other">',
      '            <div v-for="rt in recTypes" :key="rt.key" style="margin-bottom:12px;">',
      '              <div style="display:flex;align-items:center;margin-bottom:4px;"><b style="font-size:13px;">{{ rt.label }}</b>',
      '                <span style="flex:1"></span>',
      '                <el-button v-if="editable" size="small" @click="addOther(rt.key)">新增</el-button>',
      '                <el-button v-if="editable" type="primary" size="small" :loading="saving" @click="saveOthers(rt.key)">保存</el-button></div>',
      '              <el-table :data="others[rt.key]" border size="small" max-height="200" empty-text="暂无记录">',
      '                <el-table-column type="index" label="#" width="45" align="center"></el-table-column>',
      '                <el-table-column label="编码" width="120"><template #default="s"><el-input v-model="s.row.code" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '                <el-table-column label="名称" min-width="150"><template #default="s"><el-input v-model="s.row.name" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '                <el-table-column v-if="rt.key===\'icu\'" label="开始" width="150"><template #default="s"><el-date-picker v-model="s.row.beginTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" size="small" :disabled="!editable" style="width:100%;"></el-date-picker></template></el-table-column>',
      '                <el-table-column v-if="rt.key===\'icu\'" label="结束" width="150"><template #default="s"><el-date-picker v-model="s.row.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" size="small" :disabled="!editable" style="width:100%;"></el-date-picker></template></el-table-column>',
      '                <el-table-column label="备注/详情" min-width="150"><template #default="s"><el-input v-model="s.row.detail" size="small" :disabled="!editable"></el-input></template></el-table-column>',
      '                <el-table-column label="操作" width="60" align="center"><template #default="s"><el-button v-if="editable" type="danger" link size="small" @click="delOther(rt.key, s.$index)">删</el-button></template></el-table-column>',
      '              </el-table>',
      '            </div>',
      '          </el-tab-pane>',
      /* P3-D 临床首页调阅 */
      '          <el-tab-pane label="临床首页" name="clinical" @tab-click="loadClinicalPage">',
      '            <div v-loading="clinicalLoading" style="padding:8px;">',
      '              <div v-if="!clinicalPage && !clinicalLoading" style="color:#909399;">加载中…</div>',
      '              <div v-if="clinicalPage">',
      '                <el-descriptions :column="2" border size="small" title="临床首页原始数据(只读)">',
      '                  <el-descriptions-item label="状态">{{ cpStatusText(clinicalPage.status) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="住院天数">{{ clinicalPage.losDays || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="入院日期">{{ (clinicalPage.admissionDate||\'\').replace(\'T\',\' \').slice(0,10) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="出院日期">{{ (clinicalPage.dischargeDate||\'\').replace(\'T\',\' \').slice(0,10) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="入院诊断">{{ clinicalPage.admissionDiagName || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="出院主诊">{{ clinicalPage.dischargeMainDiagName || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="出院主诊编码">{{ clinicalPage.dischargeMainDiagCode || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="病理诊断">{{ clinicalPage.pathologyDiag || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="损伤中毒编码">{{ clinicalPage.injuryPoisonCode || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="质控评分">{{ clinicalPage.qualityScore || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="总费用">{{ money(clinicalPage.totalCost) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="医保支付">{{ money(clinicalPage.insurancePay) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="药品费">{{ money(clinicalPage.drugCost) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="检查费">{{ money(clinicalPage.examCost) }}</el-descriptions-item>',
      '                  <el-descriptions-item label="其他诊断" :span="2">{{ clinicalPage.dischargeOtherDiags || \'-\' }}</el-descriptions-item>',
      '                  <el-descriptions-item label="手术记录" :span="2">{{ clinicalPage.operationRecords || \'-\' }}</el-descriptions-item>',
      '                </el-descriptions>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* 留痕 */
      '          <el-tab-pane label="修改留痕" name="log">',
      '            <el-table :data="logs" border size="small" max-height="calc(100vh - 300px)" empty-text="暂无修改记录">',
      '              <el-table-column type="index" label="#" width="45" align="center"></el-table-column>',
      '              <el-table-column prop="opType" label="类型" width="90"></el-table-column>',
      '              <el-table-column prop="fieldKey" label="字段" width="140"></el-table-column>',
      '              <el-table-column prop="oldVal" label="原值" min-width="120" show-overflow-tooltip></el-table-column>',
      '              <el-table-column prop="newVal" label="新值" min-width="120" show-overflow-tooltip></el-table-column>',
      '              <el-table-column prop="opUser" label="操作人" width="90"></el-table-column>',
      '              <el-table-column label="时间" width="150"><template #default="s">{{ (s.row.opTime||\'\').replace(\'T\',\' \').slice(0,19) }}</template></el-table-column>',
      '            </el-table>',
      '          </el-tab-pane>',
      '        </el-tabs>',
      '      </div>',
      /* ---- 右: 质控错误项 ---- */
      '      <div style="width:300px;flex:none;border:1px solid var(--yb-border);border-radius:4px;padding:10px;display:flex;flex-direction:column;min-height:0;">',
      '        <div style="font-weight:600;margin-bottom:8px;flex:none;">质控错误项 ({{ errors.length }})</div>',
      '        <div style="overflow:auto;flex:1;min-height:0;">',
      '          <div v-if="!errors.length" style="color:var(--yb-ink-2);font-size:13px;">校验通过, 暂无错误项。</div>',
      '          <div v-for="(e,i) in forceErrs" :key="\'f\'+i" class="mr-err mr-err-force" @click="locate(e)">',
      '            <el-tag size="small" type="danger">强制</el-tag> <span>{{ e.errorMsg }}</span></div>',
      '          <div v-for="(e,i) in softErrs" :key="\'s\'+i" class="mr-err" @click="locate(e)">',
      '            <el-tag size="small" type="warning">非强制</el-tag> <span>{{ e.errorMsg }}</span></div>',
      '        </div>',
      '        <div style="font-size:12px;color:var(--yb-ink-2);margin-top:6px;flex:none;">点击错误项可定位到对应编辑区。</div>',
      '      </div>',
      '    </div>',
      '  </template>',
      '</div>'
    ].join('\n')
  };

  /* ================= 文件级归一/剥离助手 ================= */
  function numOr(v, def) { var n = Number(v); return isNaN(n) ? def : n; }
  function normalizeDiag(d) {
    return {
      id: d.id, diagType: d.diagType || 'dother', clinicalCode: d.clinicalCode || '', clinicalName: d.clinicalName || '',
      ybCode: d.ybCode || '', ybName: d.ybName || '', ybSortNo: d.ybSortNo == null ? null : numOr(d.ybSortNo, null),
      reportFlag: numOr(d.reportFlag, 0), grayFlag: numOr(d.grayFlag, 0), mainFlag: numOr(d.mainFlag, 0), doctorDesc: d.doctorDesc || ''
    };
  }
  function normalizeOper(o) {
    return {
      id: o.id, clinicalCode: o.clinicalCode || '', clinicalName: o.clinicalName || '', ybCode: o.ybCode || '', ybName: o.ybName || '',
      ybSortNo: o.ybSortNo == null ? null : numOr(o.ybSortNo, null), reportFlag: numOr(o.reportFlag, 0), mainFlag: numOr(o.mainFlag, 0), grayFlag: numOr(o.grayFlag, 0),
      operDate: (o.operDate || '').slice(0, 10), surgeonName: o.surgeonName || '', anesthesia: o.anesthesia || '', incisionType: o.incisionType || '', healLevel: o.healLevel || ''
    };
  }
  function normalizeOther(o) {
    return { id: o.id, code: o.code || '', name: o.name || '', detail: o.detail || '', beginTime: o.beginTime || '', endTime: o.endTime || '' };
  }
  function stripDiag(d) { return { diagType: d.diagType, clinicalCode: d.clinicalCode, clinicalName: d.clinicalName, ybCode: d.ybCode, ybName: d.ybName, ybSortNo: d.ybSortNo, reportFlag: d.reportFlag, grayFlag: d.grayFlag, mainFlag: d.mainFlag, doctorDesc: d.doctorDesc }; }
  function stripOper(o) { return { clinicalCode: o.clinicalCode, clinicalName: o.clinicalName, ybCode: o.ybCode, ybName: o.ybName, ybSortNo: o.ybSortNo, reportFlag: o.reportFlag, mainFlag: o.mainFlag, grayFlag: o.grayFlag, operDate: o.operDate, surgeonName: o.surgeonName, anesthesia: o.anesthesia, incisionType: o.incisionType, healLevel: o.healLevel }; }
  function stripOther(o) { return { code: o.code, name: o.name, detail: o.detail, beginTime: o.beginTime, endTime: o.endTime }; }
  function vm_resetDetail(vm) { vm.catalog = null; vm.diags = []; vm.opers = []; vm.others = { transfer: [], allergy: [], icu: [] }; vm.errors = []; vm.logs = []; }
})();
