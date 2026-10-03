/* 发药窗口子系统(P1): 窗口维护(类型/策略/开关) + 工作站↔窗口 + 科室定向规则 + 跨药房配置 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 窗口类型 */
  var WINDOW_TYPES = [
    { v: 'WEST', l: '西药', t: 'primary' },
    { v: 'CHINESE_PATENT', l: '中成药', t: 'primary' },
    { v: 'HERB', l: '草药', t: 'success' },
    { v: 'NARCOTIC', l: '精麻', t: 'danger' },
    { v: 'TOXIC', l: '毒性', t: 'warning' },
    { v: 'DECOCT', l: '代煎', t: 'info' },
    { v: 'EXPRESS', l: '快递', t: 'info' }
  ];
  /* 分配策略: 1剩余量最小 2平均轮询 3定向 */
  var ASSIGN_STRATEGIES = [
    { v: 1, l: '剩余量最小' },
    { v: 2, l: '平均轮询' },
    { v: 3, l: '定向窗口' }
  ];
  function wtLabel(v) { for (var i = 0; i < WINDOW_TYPES.length; i++) { if (WINDOW_TYPES[i].v === v) { return WINDOW_TYPES[i].l; } } return v || '-'; }
  function wtTag(v) { for (var i = 0; i < WINDOW_TYPES.length; i++) { if (WINDOW_TYPES[i].v === v) { return WINDOW_TYPES[i].t; } } return 'info'; }
  function stLabel(v) { for (var i = 0; i < ASSIGN_STRATEGIES.length; i++) { if (ASSIGN_STRATEGIES[i].v === v) { return ASSIGN_STRATEGIES[i].l; } } return v == null ? '-' : v; }

  /* 药房下拉共用(启用中药房; 组件 data 需声明 pharmacyDefs: []) */
  function loadPharmacies(vm) {
    return HIS.get('/api/his/pharmacy/pharmacy-def').then(function (list) {
      vm.pharmacyDefs = list || [];
    }).catch(HIS.notifyError);
  }
  function pharmacyName(defs, id) {
    if (id === null || id === undefined || id === '') { return '-'; }
    for (var i = 0; i < (defs || []).length; i++) { if (defs[i].id === id) { return defs[i].name; } }
    return '药房#' + id;
  }

  /* ================= 窗口维护 ================= */
  HIS.views.PharmacyWindowManage = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], pharmacyDefs: [], filterPharmacy: null,
        typeOpts: WINDOW_TYPES, strategyOpts: ASSIGN_STRATEGIES,
        dlgVisible: false, saving: false,
        form: blankForm()
      };
    },
    created: function () { var vm = this; loadPharmacies(vm).then(function () { vm.load(); }); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/his/pharmacy/window/list' + (vm.filterPharmacy ? '?pharmacyId=' + vm.filterPharmacy : '');
        HIS.get(url).then(function (list) { vm.list = list || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      pn: function (id) { return pharmacyName(this.pharmacyDefs, id); },
      openAdd: function () {
        if (!this.pharmacyDefs.length) { ElementPlus.ElMessage.warning('请先在药房管理中创建药房'); return; }
        this.form = blankForm();
        this.form.pharmacyId = this.filterPharmacy || this.pharmacyDefs[0].id;
        this.dlgVisible = true;
      },
      openEdit: function (row) {
        this.form = {
          id: row.id, orgId: row.orgId, pharmacyId: row.pharmacyId, code: row.code, name: row.name,
          windowType: row.windowType || '', assignStrategy: row.assignStrategy == null ? 1 : row.assignStrategy,
          isDefault: row.isDefault == null ? 0 : row.isDefault, openStatus: row.openStatus == null ? 1 : row.openStatus,
          signinRequired: row.signinRequired == null ? 0 : row.signinRequired, traceRequired: row.traceRequired == null ? 0 : row.traceRequired,
          sortNo: row.sortNo == null ? 0 : row.sortNo, status: row.status == null ? 1 : row.status
        };
        this.dlgVisible = true;
      },
      save: function () {
        var vm = this;
        if (!vm.form.pharmacyId) { ElementPlus.ElMessage.warning('请选择所属药房'); return; }
        if (!(vm.form.code || '').trim()) { ElementPlus.ElMessage.warning('请填写窗口编码'); return; }
        if (!(vm.form.name || '').trim()) { ElementPlus.ElMessage.warning('请填写窗口名称'); return; }
        vm.saving = true;
        HIS.post('/api/his/pharmacy/window/save', vm.form).then(function (d) {
          HIS.notifySuccess((vm.form.id ? '窗口已更新: ' : '窗口已新增: ') + ((d && d.name) || ''));
          vm.dlgVisible = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      toggleOpen: function (row) {
        var vm = this; var open = row.openStatus !== 1;
        HIS.post('/api/his/pharmacy/window/' + row.id + '/toggle-open?open=' + (open ? 'true' : 'false')).then(function () {
          HIS.notifySuccess(open ? '已开窗' : '已关窗'); vm.load();
        }).catch(HIS.notifyError);
      },
      toggleStatus: function (row) {
        var vm = this; var on = row.status !== 1;
        HIS.post('/api/his/pharmacy/window/' + row.id + '/toggle-status?enabled=' + (on ? 'true' : 'false')).then(function () {
          HIS.notifySuccess(on ? '已启用' : '已停用'); vm.load();
        }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除窗口「' + row.name + '」？', '删除窗口', { type: 'warning' }).then(function () {
          return HIS.del('/api/his/pharmacy/window/' + row.id);
        }).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      wtLabel: wtLabel, wtTag: wtTag, stLabel: stLabel
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">发药窗口维护 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(药房下细分窗口 · 智能分窗/定向/兜底 · 维护仅牵头机构管理员)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterPharmacy" placeholder="全部药房" clearable style="width:180px;" @change="load"><el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option></el-select>',
      '    <el-button v-if="lead" type="primary" @click="openAdd">新增窗口</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 个窗口</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column prop="code" label="编码" width="110"></el-table-column>',
      '    <el-table-column prop="name" label="名称" width="130"></el-table-column>',
      '    <el-table-column label="所属药房" width="120"><template #default="s">{{ pn(s.row.pharmacyId) }}</template></el-table-column>',
      '    <el-table-column label="类型" width="90" align="center"><template #default="s"><el-tag size="small" :type="wtTag(s.row.windowType)">{{ wtLabel(s.row.windowType) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="策略" width="110" align="center"><template #default="s">{{ stLabel(s.row.assignStrategy) }}</template></el-table-column>',
      '    <el-table-column label="兜底" width="70" align="center"><template #default="s"><el-tag v-if="s.row.isDefault===1" size="small" type="warning">默认</el-tag><span v-else>-</span></template></el-table-column>',
      '    <el-table-column label="开窗" width="80" align="center"><template #default="s"><el-tag size="small" :type="s.row.openStatus===1?\'success\':\'info\'">{{ s.row.openStatus===1?\'开\':\'关\' }}</el-tag></template></el-table-column>',
      '    <el-table-column label="签到" width="70" align="center"><template #default="s">{{ s.row.signinRequired===1?\'需\':\'-\' }}</template></el-table-column>',
      '    <el-table-column label="追溯" width="70" align="center"><template #default="s">{{ s.row.traceRequired===1?\'强制\':\'-\' }}</template></el-table-column>',
      '    <el-table-column prop="sortNo" label="排序" width="60" align="center"></el-table-column>',
      '    <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?\'启用\':\'停用\' }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="230" fixed="right"><template #default="s">',
      '      <template v-if="lead">',
      '        <el-button link type="primary" size="small" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button link size="small" @click="toggleOpen(s.row)">{{ s.row.openStatus===1?\'关窗\':\'开窗\' }}</el-button>',
      '        <el-button link size="small" @click="toggleStatus(s.row)">{{ s.row.status===1?\'停用\':\'启用\' }}</el-button>',
      '        <el-button link type="danger" size="small" @click="del(s.row)">删除</el-button>',
      '      </template><span v-else style="color:var(--yb-ink-4);">-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlgVisible" :title="form.id?\'编辑窗口\':\'新增窗口\'" width="560px">',
      '    <el-form :model="form" label-width="110px">',
      '      <el-form-item label="所属药房" required><el-select v-model="form.pharmacyId" style="width:100%"><el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="窗口编码" required><el-input v-model="form.code" placeholder="如 W-01(同药房唯一)" maxlength="32"></el-input></el-form-item>',
      '      <el-form-item label="窗口名称" required><el-input v-model="form.name" placeholder="如 1号西药窗口" maxlength="64"></el-input></el-form-item>',
      '      <el-form-item label="窗口类型"><el-select v-model="form.windowType" clearable placeholder="普通窗口可不选" style="width:100%"><el-option v-for="t in typeOpts" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="分配策略"><el-select v-model="form.assignStrategy" style="width:100%"><el-option v-for="o in strategyOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="兜底默认窗口"><el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0"></el-switch><span style="margin-left:8px;color:var(--yb-ink-2);font-size:12px;">全部关窗时回落到此窗口</span></el-form-item>',
      '      <el-form-item label="开窗状态"><el-switch v-model="form.openStatus" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="需患者签到"><el-switch v-model="form.signinRequired" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="强制追溯码"><el-switch v-model="form.traceRequired" :active-value="1" :inactive-value="0"></el-switch><span style="margin-left:8px;color:var(--yb-ink-2);font-size:12px;">P3 追溯闭环依赖</span></el-form-item>',
      '      <el-form-item label="排序号"><el-input-number v-model="form.sortNo" :min="0" :max="9999" controls-position="right" style="width:140px"></el-input-number></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlgVisible=false">取 消</el-button><el-button type="primary" :loading="saving" @click="save">保 存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
  function blankForm() {
    return { id: null, orgId: null, pharmacyId: null, code: '', name: '', windowType: '', assignStrategy: 1, isDefault: 0, openStatus: 1, signinRequired: 0, traceRequired: 0, sortNo: 0, status: 1 };
  }

  /* ================= 工作站↔窗口 ================= */
  HIS.views.WindowWorkstation = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], windows: [], curWindow: null,
        dlgVisible: false, saving: false, form: { id: null, windowId: null, userId: null, staffId: null, remark: '' }
      };
    },
    created: function () { this.loadWindows(); },
    methods: {
      loadWindows: function () {
        var vm = this;
        HIS.get('/api/his/pharmacy/window/list').then(function (list) {
          vm.windows = list || [];
          if (vm.curWindow) { vm.load(); }
        }).catch(HIS.notifyError);
      },
      wname: function (id) { for (var i = 0; i < this.windows.length; i++) { if (this.windows[i].id === id) { return this.windows[i].name; } } return id == null ? '-' : '窗口#' + id; },
      load: function () {
        if (!this.curWindow) { this.list = []; return; }
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/window/workstation?windowId=' + vm.curWindow).then(function (list) { vm.list = list || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      openAdd: function () {
        if (!this.curWindow) { ElementPlus.ElMessage.warning('请先选择窗口'); return; }
        this.form = { id: null, windowId: this.curWindow, userId: null, staffId: null, remark: '' };
        this.dlgVisible = true;
      },
      save: function () {
        var vm = this;
        if (!vm.form.userId && !vm.form.staffId) { ElementPlus.ElMessage.warning('用户ID或职工ID至少填一项'); return; }
        vm.saving = true;
        HIS.post('/api/his/pharmacy/window/workstation/save', vm.form).then(function () {
          HIS.notifySuccess('工作站已绑定'); vm.dlgVisible = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认解除该工作站与窗口的绑定？', '解绑', { type: 'warning' }).then(function () {
          return HIS.del('/api/his/pharmacy/window/workstation/' + row.id);
        }).then(function () { HIS.notifySuccess('已解绑'); vm.load(); }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">发药工作站 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(登录用户/职工绑定发药窗口 · 维护仅牵头机构管理员)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="curWindow" placeholder="选择窗口" clearable style="width:220px;" @change="load"><el-option v-for="w in windows" :key="w.id" :label="w.name+\' (\'+w.code+\')\'" :value="w.id"></el-option></el-select>',
      '    <el-button v-if="lead" type="primary" :disabled="!curWindow" @click="openAdd">绑定工作站</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">{{ curWindow ? wname(curWindow)+\' 共 \'+list.length+\' 个绑定\' : \'请选择窗口\' }}</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column label="窗口" width="180"><template #default="s">{{ wname(s.row.windowId) }}</template></el-table-column>',
      '    <el-table-column prop="userId" label="用户ID" width="120"><template #default="s">{{ s.row.userId==null?\'-\':s.row.userId }}</template></el-table-column>',
      '    <el-table-column prop="staffId" label="职工ID" width="120"><template #default="s">{{ s.row.staffId==null?\'-\':s.row.staffId }}</template></el-table-column>',
      '    <el-table-column prop="remark" label="备注" show-overflow-tooltip><template #default="s">{{ s.row.remark||\'-\' }}</template></el-table-column>',
      '    <el-table-column label="操作" width="100" fixed="right"><template #default="s">',
      '      <el-button v-if="lead" link type="danger" size="small" @click="del(s.row)">解绑</el-button><span v-else>-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlgVisible" title="绑定工作站" width="480px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="窗口"><el-input :value="wname(form.windowId)" disabled></el-input></el-form-item>',
      '      <el-form-item label="用户ID"><el-input-number v-model="form.userId" :min="1" controls-position="right" style="width:180px" placeholder="sys_user.id"></el-input-number></el-form-item>',
      '      <el-form-item label="职工ID"><el-input-number v-model="form.staffId" :min="1" controls-position="right" style="width:180px" placeholder="his_staff.id"></el-input-number></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark" maxlength="120"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlgVisible=false">取 消</el-button><el-button type="primary" :loading="saving" @click="save">保 存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 科室→窗口规则 ================= */
  HIS.views.WindowDeptRule = {
    components: { 'dept-tree-picker': HIS.components.DeptTreePicker },
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], windows: [], depts: [],
        dlgVisible: false, saving: false, form: { id: null, deptId: null, windowId: null, remark: '' }
      };
    },
    created: function () { var vm = this; this.load(); this.loadWindows(); this.loadDepts(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/window/dept-rule').then(function (list) { vm.list = list || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadWindows: function () { var vm = this; HIS.get('/api/his/pharmacy/window/list').then(function (l) { vm.windows = l || []; }).catch(HIS.notifyError); },
      loadDepts: function () { var vm = this; HIS.get('/api/his/dept/enabled').then(function (l) { vm.depts = l || []; }).catch(HIS.notifyError); },
      wname: function (id) { for (var i = 0; i < this.windows.length; i++) { if (this.windows[i].id === id) { return this.windows[i].name; } } return id == null ? '-' : '窗口#' + id; },
      dname: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return id == null ? '-' : '科室#' + id; },
      openAdd: function () { this.form = { id: null, deptId: null, windowId: null, remark: '' }; this.dlgVisible = true; },
      save: function () {
        var vm = this;
        if (!vm.form.deptId || !vm.form.windowId) { ElementPlus.ElMessage.warning('请选择科室与窗口'); return; }
        vm.saving = true;
        HIS.post('/api/his/pharmacy/window/dept-rule/save', vm.form).then(function () {
          HIS.notifySuccess('规则已保存'); vm.dlgVisible = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除「' + vm.dname(row.deptId) + ' → ' + vm.wname(row.windowId) + '」定向规则？', '删除规则', { type: 'warning' }).then(function () {
          return HIS.del('/api/his/pharmacy/window/dept-rule/' + row.id);
        }).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">开单科室定向窗口 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(急诊/发热门诊等固定投递窗口 · 维护仅牵头机构管理员)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openAdd">新增规则</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 条规则</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column label="开单科室" width="200"><template #default="s">{{ dname(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column label="定向窗口" width="200"><template #default="s">{{ wname(s.row.windowId) }}</template></el-table-column>',
      '    <el-table-column prop="remark" label="备注" show-overflow-tooltip><template #default="s">{{ s.row.remark||\'-\' }}</template></el-table-column>',
      '    <el-table-column label="操作" width="100" fixed="right"><template #default="s">',
      '      <el-button v-if="lead" link type="danger" size="small" @click="del(s.row)">删除</el-button><span v-else>-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlgVisible" title="新增定向规则" width="480px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="开单科室" required><dept-tree-picker v-model="form.deptId" :options="depts" placeholder="选择开单科室" /></el-form-item>',
      '      <el-form-item label="定向窗口" required><el-select v-model="form.windowId" filterable style="width:100%"><el-option v-for="w in windows" :key="w.id" :label="w.name+\' (\'+w.code+\')\'" :value="w.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark" maxlength="120"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlgVisible=false">取 消</el-button><el-button type="primary" :loading="saving" @click="save">保 存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 跨药房配置 ================= */
  HIS.views.PharmacyCrossConfig = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], pharmacyDefs: [],
        dlgVisible: false, saving: false, form: { id: null, sourcePharmacyId: null, targetPharmacyId: null, allowCrossStatus: 0, enabled: 1, remark: '' }
      };
    },
    created: function () { var vm = this; loadPharmacies(vm).then(function () { vm.load(); }); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/window/cross-config').then(function (list) { vm.list = list || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      pn: function (id) { return pharmacyName(this.pharmacyDefs, id); },
      openAdd: function () { this.form = { id: null, sourcePharmacyId: null, targetPharmacyId: null, allowCrossStatus: 0, enabled: 1, remark: '' }; this.dlgVisible = true; },
      save: function () {
        var vm = this;
        if (!vm.form.sourcePharmacyId || !vm.form.targetPharmacyId) { ElementPlus.ElMessage.warning('请选择源药房与目标药房'); return; }
        if (vm.form.sourcePharmacyId === vm.form.targetPharmacyId) { ElementPlus.ElMessage.warning('源与目标药房不能相同'); return; }
        vm.saving = true;
        HIS.post('/api/his/pharmacy/window/cross-config/save', vm.form).then(function () {
          HIS.notifySuccess('跨药房配置已保存'); vm.dlgVisible = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      toggle: function (row) {
        var vm = this; var on = row.enabled !== 1;
        HIS.post('/api/his/pharmacy/window/cross-config/' + row.id + '/toggle?enabled=' + (on ? 'true' : 'false')).then(function () {
          HIS.notifySuccess(on ? '已启用' : '已停用'); vm.load();
        }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除该跨药房配置？', '删除', { type: 'warning' }).then(function () {
          return HIS.del('/api/his/pharmacy/window/cross-config/' + row.id);
        }).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">跨药房发药配置 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(源药房→目标药房改派白名单 · 未配置的药房保持旧改派行为 · 维护仅牵头机构管理员)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openAdd">新增配置</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 条配置</span>',
      '  </div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;" title="源药房一旦建立白名单(受控), 其处方改派仅允许投递到启用的目标药房; 未建立任何白名单的药房改派不受限制(向后兼容)"></el-alert>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column label="源药房" width="160"><template #default="s">{{ pn(s.row.sourcePharmacyId) }}</template></el-table-column>',
      '    <el-table-column label="目标药房" width="160"><template #default="s">{{ pn(s.row.targetPharmacyId) }}</template></el-table-column>',
      '    <el-table-column label="可跨状态" width="100" align="center"><template #default="s">{{ s.row.allowCrossStatus===1?\'是\':\'否\' }}</template></el-table-column>',
      '    <el-table-column label="启用" width="90" align="center"><template #default="s"><el-tag size="small" :type="s.row.enabled===1?\'success\':\'info\'">{{ s.row.enabled===1?\'启用\':\'停用\' }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="remark" label="备注" show-overflow-tooltip><template #default="s">{{ s.row.remark||\'-\' }}</template></el-table-column>',
      '    <el-table-column label="操作" width="150" fixed="right"><template #default="s">',
      '      <template v-if="lead"><el-button link size="small" @click="toggle(s.row)">{{ s.row.enabled===1?\'停用\':\'启用\' }}</el-button>',
      '      <el-button link type="danger" size="small" @click="del(s.row)">删除</el-button></template><span v-else>-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlgVisible" title="新增跨药房配置" width="480px">',
      '    <el-form :model="form" label-width="100px">',
      '      <el-form-item label="源药房" required><el-select v-model="form.sourcePharmacyId" style="width:100%"><el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="目标药房" required><el-select v-model="form.targetPharmacyId" style="width:100%"><el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="允许跨状态"><el-switch v-model="form.allowCrossStatus" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark" maxlength="120"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlgVisible=false">取 消</el-button><el-button type="primary" :loading="saving" @click="save">保 存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

})();
