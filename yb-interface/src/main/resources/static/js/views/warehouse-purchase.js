/* 药库管理系统升级 批次A: 药品采购全流程(供应商主数据 / 智能采购规则 / 采购计划智能生成+审批 / 采购订单转单+引入入库+集采上传Mock)
 * 后端: /api/warehouse/purchase/* (PurchaseController)
 * 读: 后端 scopeOrgId 机构隔离; 写: requireLeadWrite 仅牵头机构管理员, 前端按 lead 隐藏写按钮并以后端403兜底。
 * 引入入库生成采购入库单草稿(复用 /api/his/stock/in), 在"采购入库"页确认入库。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 状态/枚举 ===== */
  var PLAN_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '待审', t: 'warning' },
    { v: 2, l: '已审', t: 'success' },
    { v: 3, l: '已驳回', t: 'danger' },
    { v: 9, l: '已转订单', t: 'primary' },
    { v: -2, l: '作废', t: 'info' }
  ];
  var ORDER_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '已下单', t: 'warning' },
    { v: 2, l: '部分到货', t: 'primary' },
    { v: 3, l: '已完成', t: 'success' },
    { v: -2, l: '作废', t: 'info' }
  ];
  var UPLOAD_STATUS = [
    { v: 0, l: '未上传', t: 'info' },
    { v: 9, l: '已上传', t: 'success' }
  ];
  function stLabel(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].l; } }
    return (v === null || v === undefined) ? '-' : v;
  }
  function stTag(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].t; } }
    return 'info';
  }
  function planStatusLabel(v) { return stLabel(PLAN_STATUS, v); }
  function planStatusTag(v) { return stTag(PLAN_STATUS, v); }
  function orderStatusLabel(v) { return stLabel(ORDER_STATUS, v); }
  function orderStatusTag(v) { return stTag(ORDER_STATUS, v); }
  function uploadStatusLabel(v) { return stLabel(UPLOAD_STATUS, v); }
  function uploadStatusTag(v) { return stTag(UPLOAD_STATUS, v); }
  function money(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    var n = Number(v);
    return isNaN(n) ? v : n.toFixed(2);
  }
  function qty(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    return String(Number(v));
  }
  function dt(v) { return v ? String(v).replace('T', ' ').substring(0, 16) : '-'; }

  /* 加载可用药库下拉(本用户授权科室过滤) */
  function loadWarehouses(vm) {
    return HIS.get('/api/his/stock/warehouse-def').then(function (d) { vm.warehouses = d || []; }).catch(HIS.notifyError);
  }

  /* ==================== 供应商管理 ==================== */
  HIS.views.SupplierManage = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], total: 0, page: 1, size: 20, keyword: '',
        dlg: false, saving: false, togglingId: null,
        form: { id: null, supplierCode: '', supplierName: '', contact: '', phone: '', address: '', settleCycle: null, jtFlag: 0, status: 1, remark: '' }
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/warehouse/purchase/supplier/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { url += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(url).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      openCreate: function () {
        this.form = { id: null, supplierCode: '', supplierName: '', contact: '', phone: '', address: '', settleCycle: null, jtFlag: 0, status: 1, remark: '' };
        this.dlg = true;
      },
      openEdit: function (row) {
        this.form = {
          id: row.id, supplierCode: row.supplierCode, supplierName: row.supplierName,
          contact: row.contact || '', phone: row.phone || '', address: row.address || '',
          settleCycle: row.settleCycle, jtFlag: row.jtFlag || 0, status: row.status, remark: row.remark || ''
        };
        this.dlg = true;
      },
      save: function () {
        var vm = this;
        if (!String(vm.form.supplierCode || '').trim()) { ElementPlus.ElMessage.warning('请填写供应商编码'); return; }
        if (!String(vm.form.supplierName || '').trim()) { ElementPlus.ElMessage.warning('请填写供应商名称'); return; }
        vm.saving = true;
        HIS.post('/api/warehouse/purchase/supplier', vm.form).then(function () {
          HIS.notifySuccess(vm.form.id ? '供应商已更新' : '供应商已新增');
          vm.dlg = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      onToggle: function (row, val) {
        var vm = this; var enable = !!val;
        vm.togglingId = row.id;
        HIS.post('/api/warehouse/purchase/supplier/' + row.id + '/toggle?enabled=' + enable).then(function () {
          HIS.notifySuccess((enable ? '已启用: ' : '已停用: ') + row.supplierName); vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.togglingId = null; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">供应商管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(采购订单/入库的供应商档案 · 集采标识用于智能采购与上传)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新增供应商</el-button>',
      '    <el-input v-model="keyword" placeholder="名称/编码" clearable style="width:200px;" @keyup.enter="search"></el-input>',
      '    <el-button @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="#" width="50"></el-table-column>',
      '    <el-table-column prop="supplierCode" label="编码" width="130" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="supplierName" label="名称" min-width="180" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="contact" label="联系人" width="90"></el-table-column>',
      '    <el-table-column prop="phone" label="电话" width="130"></el-table-column>',
      '    <el-table-column prop="settleCycle" label="结算周期" width="90" align="right"><template #default="s">{{ s.row.settleCycle ? s.row.settleCycle + "天" : "-" }}</template></el-table-column>',
      '    <el-table-column label="集采" width="70" align="center"><template #default="s"><el-tag v-if="s.row.jtFlag===1" size="small" type="warning">集采</el-tag><span v-else>-</span></template></el-table-column>',
      '    <el-table-column label="状态" width="140"><template #default="s">',
      '      <el-switch :model-value="s.row.status===1" :disabled="!lead || togglingId===s.row.id" @change="onToggle(s.row, $event)"></el-switch>',
      '      <span style="margin-left:8px;font-size:12px;">{{ s.row.status===1 ? "启用" : "停用" }}</span>',
      '    </template></el-table-column>',
      '    <el-table-column v-if="lead" label="操作" width="80"><template #default="s"><el-button link type="primary" @click="openEdit(s.row)">编辑</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="p=>{page=p;load()}" style="margin-top:8px;"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="form.id ? \'编辑供应商\' : \'新增供应商\'" width="560px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="编码" required><el-input v-model="form.supplierCode" placeholder="租户内唯一"></el-input></el-form-item>',
      '      <el-form-item label="名称" required><el-input v-model="form.supplierName"></el-input></el-form-item>',
      '      <el-form-item label="联系人"><el-input v-model="form.contact"></el-input></el-form-item>',
      '      <el-form-item label="电话"><el-input v-model="form.phone"></el-input></el-form-item>',
      '      <el-form-item label="地址"><el-input v-model="form.address"></el-input></el-form-item>',
      '      <el-form-item label="结算周期"><el-input-number v-model="form.settleCycle" :min="0" controls-position="right" style="width:140px;"></el-input-number> 天</el-form-item>',
      '      <el-form-item label="集采供应商"><el-switch v-model="form.jtFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 采购规则 ==================== */
  HIS.views.PurchaseRule = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], warehouses: [], whFilter: null,
        dlg: false, saving: false,
        form: { id: null, orgId: null, warehouseId: null, ruleName: '', referMonths: 1, loQty: null, hiQty: null, dispenseWeight: 1, stockoutWeight: 1, abcARatio: 0.8, abcBRatio: 0.95, enabled: 1, remark: '' }
      };
    },
    created: function () { this.load(); loadWarehouses(this); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/warehouse/purchase/rule/list';
        if (vm.whFilter) { url += '?warehouseId=' + vm.whFilter; }
        HIS.get(url).then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      whName: function (id) {
        if (id === null || id === undefined || id === '') { return '全院默认'; }
        for (var i = 0; i < this.warehouses.length; i++) { if (this.warehouses[i].id === id) { return this.warehouses[i].name; } }
        return '库#' + id;
      },
      openCreate: function () {
        this.form = { id: null, orgId: null, warehouseId: null, ruleName: '', referMonths: 1, loQty: null, hiQty: null, dispenseWeight: 1, stockoutWeight: 1, abcARatio: 0.8, abcBRatio: 0.95, enabled: 1, remark: '' };
        this.dlg = true;
      },
      openEdit: function (row) {
        this.form = JSON.parse(JSON.stringify(row));
        this.dlg = true;
      },
      save: function () {
        var vm = this;
        if (!String(vm.form.ruleName || '').trim()) { ElementPlus.ElMessage.warning('请填写规则名称'); return; }
        vm.saving = true;
        HIS.post('/api/warehouse/purchase/rule', vm.form).then(function () {
          HIS.notifySuccess('规则已保存'); vm.dlg = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除规则 [' + row.ruleName + '] ?', '删除规则', { type: 'warning' }).then(function () {
          HIS.del('/api/warehouse/purchase/rule/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError);
        }).catch(function () { });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">采购规则 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按药库配置高低储/参考月数/权重/ABC占比, 驱动智能采购建议量)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新增规则</el-button>',
      '    <el-select v-model="whFilter" clearable placeholder="全部药库" style="width:160px;" @change="load"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select>',
      '    <el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column prop="ruleName" label="规则名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="适用药库" width="130"><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column prop="referMonths" label="参考月数" width="80" align="right"></el-table-column>',
      '    <el-table-column label="低储" width="90" align="right"><template #default="s">{{ qty(s.row.loQty) }}</template></el-table-column>',
      '    <el-table-column label="高储" width="90" align="right"><template #default="s">{{ qty(s.row.hiQty) }}</template></el-table-column>',
      '    <el-table-column label="发药权重" width="80" align="right"><template #default="s">{{ qty(s.row.dispenseWeight) }}</template></el-table-column>',
      '    <el-table-column label="出库权重" width="80" align="right"><template #default="s">{{ qty(s.row.stockoutWeight) }}</template></el-table-column>',
      '    <el-table-column label="ABC阈值" width="110" align="center"><template #default="s">{{ qty(s.row.abcARatio) }} / {{ qty(s.row.abcBRatio) }}</template></el-table-column>',
      '    <el-table-column label="启用" width="80" align="center"><template #default="s"><el-tag size="small" :type="s.row.enabled===1?\'success\':\'info\'">{{ s.row.enabled===1?"是":"否" }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="lead" label="操作" width="120"><template #default="s"><el-button link type="primary" @click="openEdit(s.row)">编辑</el-button><el-button link type="danger" @click="del(s.row)">删除</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="form.id ? \'编辑规则\' : \'新增规则\'" width="560px">',
      '    <el-form :model="form" label-width="100px">',
      '      <el-form-item label="规则名称" required><el-input v-model="form.ruleName"></el-input></el-form-item>',
      '      <el-form-item label="适用药库"><el-select v-model="form.warehouseId" clearable placeholder="空=全院默认" style="width:100%;"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="参考月数"><el-input-number v-model="form.referMonths" :min="1" :max="24" controls-position="right" style="width:140px;"></el-input-number></el-form-item>',
      '      <el-form-item label="低储标准"><el-input-number v-model="form.loQty" :min="0" :precision="2" controls-position="right" style="width:160px;"></el-input-number></el-form-item>',
      '      <el-form-item label="高储标准"><el-input-number v-model="form.hiQty" :min="0" :precision="2" controls-position="right" style="width:160px;"></el-input-number></el-form-item>',
      '      <el-form-item label="发药量权重"><el-input-number v-model="form.dispenseWeight" :min="0" :step="0.1" :precision="2" controls-position="right" style="width:140px;"></el-input-number></el-form-item>',
      '      <el-form-item label="出库量权重"><el-input-number v-model="form.stockoutWeight" :min="0" :step="0.1" :precision="2" controls-position="right" style="width:140px;"></el-input-number></el-form-item>',
      '      <el-form-item label="A类占比"><el-input-number v-model="form.abcARatio" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" style="width:140px;"></el-input-number></el-form-item>',
      '      <el-form-item label="B类占比"><el-input-number v-model="form.abcBRatio" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" style="width:140px;"></el-input-number></el-form-item>',
      '      <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 采购计划 ==================== */
  HIS.views.PurchasePlan = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], total: 0, page: 1, size: 20,
        warehouses: [], suppliers: [], filterWh: null, filterStatus: null,
        genDlg: false, gen: { warehouseId: null, majorClass: '', remark: '' }, genLoading: false,
        detailDlg: false, detail: { main: {}, items: [] }, detailLoading: false, acting: false
      };
    },
    created: function () { this.load(); loadWarehouses(this); this.loadSuppliers(); },
    methods: {
      planStatusLabel: planStatusLabel, planStatusTag: planStatusTag, money: money, qty: qty, dt: dt,
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/warehouse/purchase/plan/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterWh) { url += '&warehouseId=' + vm.filterWh; }
        if (vm.filterStatus !== null && vm.filterStatus !== '') { url += '&status=' + vm.filterStatus; }
        HIS.get(url).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadSuppliers: function () {
        var vm = this;
        HIS.get('/api/warehouse/purchase/supplier/list').then(function (d) { vm.suppliers = d || []; }).catch(HIS.notifyError);
      },
      search: function () { this.page = 1; this.load(); },
      whName: function (id) {
        if (!id) { return '全部'; }
        for (var i = 0; i < this.warehouses.length; i++) { if (this.warehouses[i].id === id) { return this.warehouses[i].name; } }
        return '库#' + id;
      },
      openGen: function () { this.gen = { warehouseId: null, majorClass: '', remark: '' }; this.genDlg = true; },
      doGenerate: function () {
        var vm = this; vm.genLoading = true;
        var url = '/api/warehouse/purchase/plan/auto-generate?';
        if (vm.gen.warehouseId) { url += 'warehouseId=' + vm.gen.warehouseId + '&'; }
        if (vm.gen.majorClass) { url += 'majorClass=' + encodeURIComponent(vm.gen.majorClass) + '&'; }
        if (vm.gen.remark) { url += 'remark=' + encodeURIComponent(vm.gen.remark) + '&'; }
        HIS.post(url).then(function (d) {
          HIS.notifySuccess('已生成采购计划: ' + (d && d.planNo)); vm.genDlg = false; vm.search();
        }).catch(HIS.notifyError).finally(function () { vm.genLoading = false; });
      },
      openDetail: function (row) {
        var vm = this; vm.detailLoading = true; vm.detailDlg = true; vm.detail = { main: {}, items: [] };
        HIS.get('/api/warehouse/purchase/plan/' + row.id).then(function (d) { vm.detail = d || { main: {}, items: [] }; })
          .catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      act: function (fn) {
        var vm = this; vm.acting = true;
        fn().then(function () { HIS.notifySuccess('操作成功'); vm.detailDlg = false; vm.load(); })
          .catch(HIS.notifyError).finally(function () { vm.acting = false; });
      },
      submitPlan: function (row) { var vm = this; this.act(function () { return HIS.post('/api/warehouse/purchase/plan/' + row.id + '/submit'); }); },
      approve: function (row) { var vm = this; this.act(function () { return HIS.post('/api/warehouse/purchase/plan/' + row.id + '/approve'); }); },
      reject: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('驳回原因(可选)', '驳回计划', { type: 'warning' }).then(function (r) {
          vm.act(function () { return HIS.post('/api/warehouse/purchase/plan/' + row.id + '/reject?reason=' + encodeURIComponent(r.value || '')); });
        }).catch(function () { });
      },
      convert: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认将计划 [' + row.planNo + '] 转为采购订单? 转后计划锁定不可再编辑。', '转采购订单', { type: 'info' }).then(function () {
          vm.act(function () { return HIS.post('/api/warehouse/purchase/plan/' + row.id + '/convert'); });
        }).catch(function () { });
      },
      voidPlan: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废计划 [' + row.planNo + '] ?', '作废计划', { type: 'warning' }).then(function () {
          vm.act(function () { return HIS.del('/api/warehouse/purchase/plan/' + row.id); });
        }).catch(function () { });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">采购计划 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(智能生成/手工编制 → 提交 → 审批 → 转采购订单)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openGen">智能生成</el-button>',
      '    <el-select v-model="filterWh" clearable placeholder="全部药库" style="width:150px;" @change="search"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select>',
      '    <el-select v-model="filterStatus" clearable placeholder="全部状态" style="width:130px;" @change="search"><el-option label="草稿" :value="0"></el-option><el-option label="待审" :value="1"></el-option><el-option label="已审" :value="2"></el-option><el-option label="已驳回" :value="3"></el-option><el-option label="已转订单" :value="9"></el-option></el-select>',
      '    <el-button @click="search">查询</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column prop="planNo" label="计划单号" width="160" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="药库" width="120"><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column label="生成方式" width="90" align="center"><template #default="s"><el-tag size="small" :type="s.row.genType===\'auto\'?\'primary\':\'info\'">{{ s.row.genType==="auto"?"智能":"手工" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="金额" width="110" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="planStatusTag(s.row.status)">{{ planStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="createBy" label="创建人" width="90"></el-table-column>',
      '    <el-table-column label="操作" min-width="240"><template #default="s">',
      '      <el-button link type="primary" @click="openDetail(s.row)">明细</el-button>',
      '      <template v-if="lead">',
      '        <el-button v-if="s.row.status===0" link type="primary" @click="submitPlan(s.row)">提交</el-button>',
      '        <el-button v-if="s.row.status===1" link type="success" @click="approve(s.row)">审批</el-button>',
      '        <el-button v-if="s.row.status===1" link type="danger" @click="reject(s.row)">驳回</el-button>',
      '        <el-button v-if="s.row.status===2" link type="warning" @click="convert(s.row)">转订单</el-button>',
      '        <el-button v-if="s.row.status===0||s.row.status===1||s.row.status===3" link @click="voidPlan(s.row)">作废</el-button>',
      '      </template>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="p=>{page=p;load()}" style="margin-top:8px;"></el-pagination>',
      /* 智能生成对话框 */
      '  <el-dialog v-model="genDlg" title="智能生成采购计划" width="520px">',
      '    <el-form :model="gen" label-width="90px">',
      '      <el-form-item label="目标药库"><el-select v-model="gen.warehouseId" clearable placeholder="空=本机构全部在库药品" style="width:100%;"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="药品大类"><el-input v-model="gen.majorClass" placeholder="按大类模糊筛选候选, 空=全部"></el-input></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="gen.remark"></el-input></el-form-item>',
      '      <div style="color:var(--yb-ink-2);font-size:12px;">读取在仓库存 + 上月出入库/发药量, 按采购规则计算建议量并生成草稿计划(仅建议量&gt;0纳入)。</div>',
      '    </el-form>',
      '    <template #footer><el-button @click="genDlg=false">取消</el-button><el-button type="primary" :loading="genLoading" @click="doGenerate">生成</el-button></template>',
      '  </el-dialog>',
      /* 明细对话框 */
      '  <el-dialog v-model="detailDlg" title="采购计划明细" width="860px">',
      '    <div v-loading="detailLoading">',
      '      <div style="margin-bottom:8px;">单号 <b>{{ detail.main.planNo }}</b> · 药库 {{ whName(detail.main.warehouseId) }} · 状态 <el-tag size="small" :type="planStatusTag(detail.main.status)">{{ planStatusLabel(detail.main.status) }}</el-tag> · 金额 {{ money(detail.main.totalAmount) }}</div>',
      '      <el-table :data="detail.items" border stripe size="small" max-height="420">',
      '        <el-table-column prop="drugCode" label="编码" width="110"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="ABC" width="55" align="center"><template #default="s">{{ s.row.abcClass }}</template></el-table-column>',
      '        <el-table-column label="当前库存" width="80" align="right"><template #default="s">{{ qty(s.row.curStock) }}</template></el-table-column>',
      '        <el-table-column label="低储" width="60" align="right"><template #default="s">{{ qty(s.row.loQty) }}</template></el-table-column>',
      '        <el-table-column label="高储" width="60" align="right"><template #default="s">{{ qty(s.row.hiQty) }}</template></el-table-column>',
      '        <el-table-column label="上月出" width="70" align="right"><template #default="s">{{ qty(s.row.lastMonthOut) }}</template></el-table-column>',
      '        <el-table-column label="发药量" width="70" align="right"><template #default="s">{{ qty(s.row.dispenseQty) }}</template></el-table-column>',
      '        <el-table-column label="建议量" width="80" align="right"><template #default="s"><b>{{ qty(s.row.qtySuggest) }}</b></template></el-table-column>',
      '        <el-table-column label="进价" width="80" align="right"><template #default="s">{{ money(s.row.price) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="90" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="detailDlg=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 采购订单 ==================== */
  HIS.views.PurchaseOrder = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, list: [], total: 0, page: 1, size: 20,
        warehouses: [], suppliers: [], filterWh: null, filterSupplier: null, filterStatus: null,
        detailDlg: false, detail: { main: {}, items: [] }, detailLoading: false, acting: false
      };
    },
    created: function () { this.load(); loadWarehouses(this); this.loadSuppliers(); },
    methods: {
      orderStatusLabel: orderStatusLabel, orderStatusTag: orderStatusTag, uploadStatusLabel: uploadStatusLabel, uploadStatusTag: uploadStatusTag, money: money, qty: qty, dt: dt,
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/warehouse/purchase/order/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterWh) { url += '&warehouseId=' + vm.filterWh; }
        if (vm.filterSupplier) { url += '&supplierId=' + vm.filterSupplier; }
        if (vm.filterStatus !== null && vm.filterStatus !== '') { url += '&status=' + vm.filterStatus; }
        HIS.get(url).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadSuppliers: function () {
        var vm = this;
        HIS.get('/api/warehouse/purchase/supplier/list').then(function (d) { vm.suppliers = d || []; }).catch(HIS.notifyError);
      },
      search: function () { this.page = 1; this.load(); },
      whName: function (id) {
        if (!id) { return '全部'; }
        for (var i = 0; i < this.warehouses.length; i++) { if (this.warehouses[i].id === id) { return this.warehouses[i].name; } }
        return '库#' + id;
      },
      supplierName: function (id) {
        if (!id) { return '-'; }
        for (var i = 0; i < this.suppliers.length; i++) { if (this.suppliers[i].id === id) { return this.suppliers[i].supplierName; } }
        return '供#' + id;
      },
      openDetail: function (row) {
        var vm = this; vm.detailLoading = true; vm.detailDlg = true; vm.detail = { main: {}, items: [] };
        HIS.get('/api/warehouse/purchase/order/' + row.id).then(function (d) { vm.detail = d || { main: {}, items: [] }; })
          .catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      act: function (fn, okMsg) {
        var vm = this; vm.acting = true;
        fn().then(function (r) { if (okMsg) { HIS.notifySuccess(okMsg); } vm.detailDlg = false; vm.load(); return r; })
          .catch(HIS.notifyError).finally(function () { vm.acting = false; });
      },
      place: function (row) { this.act(function () { return HIS.post('/api/warehouse/purchase/order/' + row.id + '/place'); }, '已下单'); },
      upload: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('集采/统采上传(本期 Mock 占位, 仅落上传状态与回执, 不接真实外网)。确认上传 [' + row.orderNo + '] ?', '集采上传', { type: 'info' }).then(function () {
          vm.act(function () { return HIS.post('/api/warehouse/purchase/order/' + row.id + '/upload'); }, '已上传(Mock)');
        }).catch(function () { });
      },
      importIn: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('将订单明细引入为采购入库单草稿(可在"采购入库"页补录批次/效期后确认入库)。确认引入 [' + row.orderNo + '] ?', '引入入库', { type: 'info' }).then(function () {
          vm.act(function () { return HIS.post('/api/warehouse/purchase/order/' + row.id + '/import-in'); }, '已生成入库单草稿, 请到采购入库页确认');
        }).catch(function () { });
      },
      voidOrder: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废订单 [' + row.orderNo + '] ?', '作废订单', { type: 'warning' }).then(function () {
          vm.act(function () { return HIS.del('/api/warehouse/purchase/order/' + row.id); }, '已作废');
        }).catch(function () { });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">采购订单 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(计划转单/手工 → 下单 → 集采上传(Mock) → 引入采购入库)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterWh" clearable placeholder="全部药库" style="width:150px;" @change="search"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select>',
      '    <el-select v-model="filterSupplier" clearable placeholder="全部供应商" style="width:170px;" @change="search"><el-option v-for="sp in suppliers" :key="sp.id" :label="sp.supplierName" :value="sp.id"></el-option></el-select>',
      '    <el-select v-model="filterStatus" clearable placeholder="全部状态" style="width:130px;" @change="search"><el-option label="草稿" :value="0"></el-option><el-option label="已下单" :value="1"></el-option><el-option label="部分到货" :value="2"></el-option><el-option label="已完成" :value="3"></el-option></el-select>',
      '    <el-button @click="search">查询</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column prop="orderNo" label="订单号" width="160" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="供应商" width="150"><template #default="s">{{ supplierName(s.row.supplierId) }}</template></el-table-column>',
      '    <el-table-column label="药库" width="110"><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column label="金额" width="110" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="orderStatusTag(s.row.status)">{{ orderStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="上传" width="80" align="center"><template #default="s"><el-tag size="small" :type="uploadStatusTag(s.row.uploadStatus)">{{ uploadStatusLabel(s.row.uploadStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" min-width="260"><template #default="s">',
      '      <el-button link type="primary" @click="openDetail(s.row)">明细</el-button>',
      '      <template v-if="lead">',
      '        <el-button v-if="s.row.status===0" link type="success" @click="place(s.row)">下单</el-button>',
      '        <el-button v-if="s.row.status!==undefined && s.row.uploadStatus!==9 && s.row.status!==-2" link type="warning" @click="upload(s.row)">上传</el-button>',
      '        <el-button v-if="s.row.status===1||s.row.status===2" link type="primary" @click="importIn(s.row)">引入入库</el-button>',
      '        <el-button v-if="s.row.status===0||s.row.status===1" link @click="voidOrder(s.row)">作废</el-button>',
      '      </template>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="p=>{page=p;load()}" style="margin-top:8px;"></el-pagination>',
      '  <el-dialog v-model="detailDlg" title="采购订单明细" width="820px">',
      '    <div v-loading="detailLoading">',
      '      <div style="margin-bottom:8px;">订单号 <b>{{ detail.main.orderNo }}</b> · 供应商 {{ supplierName(detail.main.supplierId) }} · 药库 {{ whName(detail.main.warehouseId) }} · 状态 <el-tag size="small" :type="orderStatusTag(detail.main.status)">{{ orderStatusLabel(detail.main.status) }}</el-tag> · 金额 {{ money(detail.main.totalAmount) }}</div>',
      '      <el-table :data="detail.items" border stripe size="small" max-height="420">',
      '        <el-table-column prop="drugCode" label="编码" width="120"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="manufacturer" label="厂家" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="订购量" width="80" align="right"><template #default="s">{{ qty(s.row.qty) }}</template></el-table-column>',
      '        <el-table-column label="已到货" width="80" align="right"><template #default="s">{{ qty(s.row.qtyReceived) }}</template></el-table-column>',
      '        <el-table-column label="进价" width="80" align="right"><template #default="s">{{ money(s.row.price) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="90" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="detailDlg=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 批次B 枚举/辅助 ==================== */
  var PURCHASE_MODE = [
    { v: 1, l: '正常', t: 'success' },
    { v: 2, l: '挂账', t: 'warning' },
    { v: 3, l: '票未到(仅单据)', t: 'info' }
  ];
  var IN_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '已确认', t: 'success' },
    { v: 2, l: '已作废', t: 'danger' }
  ];
  var ACCEPT_STATUS = [
    { v: 0, l: '未验收', t: 'warning' },
    { v: 1, l: '已验收', t: 'success' }
  ];
  function pmLabel(v) { return stLabel(PURCHASE_MODE, v); }
  function pmTag(v) { return stTag(PURCHASE_MODE, v); }
  function inStatusLabel(v) { return stLabel(IN_STATUS, v); }
  function inStatusTag(v) { return stTag(IN_STATUS, v); }
  function acceptLabel(v) { return stLabel(ACCEPT_STATUS, v == null ? 1 : v); }
  function acceptTag(v) { return stTag(ACCEPT_STATUS, v == null ? 1 : v); }

  function loadSuppliers(vm) {
    return HIS.get('/api/warehouse/purchase/supplier/list').then(function (d) { vm.suppliers = d || []; }).catch(HIS.notifyError);
  }

  /* ==================== 采购入库已整合(2026-10): PurchaseStockIn 视图已下线, 功能(购入方式/发票/定向出库/多单位/验收/冲红)并入 warehouse.js 的 StockInManage · 菜单 purchase-stock-in 已退役 ==================== */

  /* ==================== 财务验收(单张/按供应商集中 + 平账记录) ==================== */
  HIS.views.StockAccept = {
    data: function () {
      return {
        lead: HIS.isLead(), tab: 'pending', suppliers: [],
        pLoading: false, pending: [], pTotal: 0, pPage: 1, pSize: 20, filterSupplier: null,
        aLoading: false, accepts: [], aTotal: 0, aPage: 1, aSize: 20,
        bLoading: false, balances: [], bTotal: 0, bPage: 1, bSize: 20,
        detailDlg: false, detailLoading: false, detail: { main: {}, items: [] }, acting: false
      };
    },
    created: function () { this.loadPending(); loadSuppliers(this); },
    methods: {
      money: money, qty: qty, dt: dt,
      onTab: function (name) {
        if (name === 'pending') { this.loadPending(); }
        else if (name === 'accept') { this.loadAccept(); }
        else { this.loadBalance(); }
      },
      supplierName: function (id) {
        if (!id) { return '-'; }
        for (var i = 0; i < this.suppliers.length; i++) { if (this.suppliers[i].id === id) { return this.suppliers[i].supplierName; } }
        return '供#' + id;
      },
      loadPending: function () {
        var vm = this; vm.pLoading = true;
        var url = '/api/warehouse/accept/pending?page=' + vm.pPage + '&size=' + vm.pSize;
        if (vm.filterSupplier) { url += '&supplierId=' + vm.filterSupplier; }
        HIS.get(url).then(function (d) { vm.pending = (d && d.records) || []; vm.pTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.pLoading = false; });
      },
      loadAccept: function () {
        var vm = this; vm.aLoading = true;
        HIS.get('/api/warehouse/accept/page?page=' + vm.aPage + '&size=' + vm.aSize).then(function (d) { vm.accepts = (d && d.records) || []; vm.aTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.aLoading = false; });
      },
      loadBalance: function () {
        var vm = this; vm.bLoading = true;
        HIS.get('/api/warehouse/accept/balance?page=' + vm.bPage + '&size=' + vm.bSize).then(function (d) { vm.balances = (d && d.records) || []; vm.bTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.bLoading = false; });
      },
      acceptSingle: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('对入库单 ' + row.inNo + ' 做财务验收(结论=合格)? 验收后回写为已验收。', '单张验收', { type: 'info' }).then(function () {
          vm.acting = true;
          HIS.post('/api/warehouse/accept/single?stockInId=' + row.id + '&conclusion=1').then(function (a) {
            HIS.notifySuccess('验收完成: ' + a.acceptNo); vm.loadPending();
          }).catch(HIS.notifyError).finally(function () { vm.acting = false; });
        }).catch(function () { });
      },
      acceptSupplier: function () {
        var vm = this;
        if (!vm.filterSupplier) { ElementPlus.ElMessage.warning('请先在上方选择供应商'); return; }
        ElementPlus.ElMessageBox.confirm('对该供应商全部待验收入库单集中验收(结论=合格)?', '集中验收', { type: 'info' }).then(function () {
          vm.acting = true;
          HIS.post('/api/warehouse/accept/supplier?supplierId=' + vm.filterSupplier + '&conclusion=1').then(function (a) {
            HIS.notifySuccess('集中验收完成: ' + a.acceptNo + ' 覆盖' + a.billCount + '单'); vm.loadPending();
          }).catch(HIS.notifyError).finally(function () { vm.acting = false; });
        }).catch(function () { });
      },
      openDetail: function (row) {
        var vm = this; vm.detailLoading = true; vm.detailDlg = true; vm.detail = { main: {}, items: [] };
        HIS.get('/api/warehouse/accept/' + row.id).then(function (d) { vm.detail = d || { main: {}, items: [] }; })
          .catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">财务验收 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(挂账/票未到入库的财务确认 · 单张/按供应商集中验收 · 未验收出库平账记录)</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',
      '    <el-tab-pane label="待验收入库" name="pending">',
      '      <div class="toolbar">',
      '        <el-select v-model="filterSupplier" clearable placeholder="按供应商筛选" style="width:200px;" @change="pPage=1;loadPending()"><el-option v-for="sp in suppliers" :key="sp.id" :label="sp.supplierName" :value="sp.id"></el-option></el-select>',
      '        <el-button v-if="lead" type="warning" :disabled="!filterSupplier" @click="acceptSupplier">按供应商集中验收</el-button>',
      '        <el-button @click="loadPending">刷新</el-button>',
      '      </div>',
      '      <el-table :data="pending" v-loading="pLoading" border stripe size="small" height="100%">',
      '        <el-table-column prop="inNo" label="入库单号" width="160"></el-table-column>',
      '        <el-table-column prop="supplier" label="供应商" width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="金额" width="110" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '        <el-table-column label="确认时间" width="150"><template #default="s">{{ dt(s.row.confirmTime) }}</template></el-table-column>',
      '        <el-table-column v-if="lead" label="操作" width="100"><template #default="s"><el-button link type="primary" @click="acceptSingle(s.row)">验收</el-button></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination background layout="total, prev, pager, next" :total="pTotal" :page-size="pSize" :current-page="pPage" @current-change="p=>{pPage=p;loadPending()}" style="margin-top:8px;"></el-pagination>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="验收单" name="accept">',
      '      <el-table :data="accepts" v-loading="aLoading" border stripe size="small" height="100%">',
      '        <el-table-column prop="acceptNo" label="验收单号" width="160"></el-table-column>',
      '        <el-table-column label="方式" width="90" align="center"><template #default="s">{{ s.row.acceptType===2?"集中":"单张" }}</template></el-table-column>',
      '        <el-table-column label="供应商" width="150"><template #default="s">{{ supplierName(s.row.supplierId) }}</template></el-table-column>',
      '        <el-table-column label="覆盖单数" width="80" align="right"><template #default="s">{{ s.row.billCount }}</template></el-table-column>',
      '        <el-table-column label="金额" width="110" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '        <el-table-column prop="acceptBy" label="验收人" width="90"></el-table-column>',
      '        <el-table-column label="验收时间" width="150"><template #default="s">{{ dt(s.row.acceptTime) }}</template></el-table-column>',
      '        <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" @click="openDetail(s.row)">明细</el-button></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination background layout="total, prev, pager, next" :total="aTotal" :page-size="aSize" :current-page="aPage" @current-change="p=>{aPage=p;loadAccept()}" style="margin-top:8px;"></el-pagination>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="平账记录" name="balance">',
      '      <el-table :data="balances" v-loading="bLoading" border stripe size="small" height="100%">',
      '        <el-table-column prop="drugName" label="药品" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批号" width="120"></el-table-column>',
      '        <el-table-column label="原挂账进价" width="100" align="right"><template #default="s">{{ money(s.row.origInPrice) }}</template></el-table-column>',
      '        <el-table-column label="实际结转价" width="100" align="right"><template #default="s">{{ money(s.row.actualInPrice) }}</template></el-table-column>',
      '        <el-table-column label="数量" width="80" align="right"><template #default="s">{{ qty(s.row.qty) }}</template></el-table-column>',
      '        <el-table-column label="进价差" width="100" align="right"><template #default="s"><b>{{ money(s.row.diffAmount) }}</b></template></el-table-column>',
      '        <el-table-column prop="balanceDate" label="平账日期" width="110"></el-table-column>',
      '        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip></el-table-column>',
      '      </el-table>',
      '      <el-pagination background layout="total, prev, pager, next" :total="bTotal" :page-size="bSize" :current-page="bPage" @current-change="p=>{bPage=p;loadBalance()}" style="margin-top:8px;"></el-pagination>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '  <el-dialog v-model="detailDlg" title="验收单明细" width="820px">',
      '    <div v-loading="detailLoading">',
      '      <div style="margin-bottom:8px;">验收单号 <b>{{ detail.main.acceptNo }}</b> · 供应商 {{ supplierName(detail.main.supplierId) }} · 覆盖 {{ detail.main.billCount }} 单 · 金额 {{ money(detail.main.totalAmount) }}</div>',
      '      <el-table :data="detail.items" border stripe size="small" max-height="420">',
      '        <el-table-column prop="drugCode" label="编码" width="110"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批号" width="120"></el-table-column>',
      '        <el-table-column label="数量" width="80" align="right"><template #default="s">{{ qty(s.row.qty) }}</template></el-table-column>',
      '        <el-table-column label="进价" width="90" align="right"><template #default="s">{{ money(s.row.costPrice) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="100" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="detailDlg=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
