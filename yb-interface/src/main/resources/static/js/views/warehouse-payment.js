/* 药库管理系统升级 批次C: 供应商付款处理 + 应付账款账龄分析
 * 后端: /api/warehouse/payment/* (SupplierPaymentController)
 * 读: 后端 scopeOrgId 机构隔离; 写: requireLeadWrite 仅牵头机构管理员, 前端按 lead 隐藏写按钮并以后端403兜底。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 枚举/辅助 ===== */
  var PAY_METHOD = [
    { v: 1, l: '全额付清', t: 'success' },
    { v: 2, l: '输入总额', t: 'primary' },
    { v: 3, l: '部分分摊', t: 'warning' }
  ];
  var PAY_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '已确认', t: 'success' }
  ];
  var PAID_STATUS = [
    { v: 0, l: '未付', t: 'info' },
    { v: 1, l: '部分', t: 'warning' },
    { v: 2, l: '已付', t: 'success' }
  ];
  function stLabel(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].l; } }
    return (v === null || v === undefined) ? '-' : v;
  }
  function stTag(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].t; } }
    return 'info';
  }
  function pmLabel(v) { return stLabel(PAY_METHOD, v); }
  function pmTag(v) { return stTag(PAY_METHOD, v); }
  function payStatusLabel(v) { return stLabel(PAY_STATUS, v); }
  function payStatusTag(v) { return stTag(PAY_STATUS, v); }
  function paidStatusLabel(v) { return stLabel(PAID_STATUS, v == null ? 0 : v); }
  function paidStatusTag(v) { return stTag(PAID_STATUS, v == null ? 0 : v); }
  function money(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    var n = Number(v);
    return isNaN(n) ? v : n.toFixed(2);
  }
  function dt(v) { return v ? String(v).replace('T', ' ').substring(0, 16) : '-'; }
  function unpaidOf(row) { return (Number(row.totalAmount) || 0) - (Number(row.paidAmount) || 0); }

  function loadSuppliers(vm) {
    return HIS.get('/api/warehouse/purchase/supplier/list').then(function (d) { vm.suppliers = d || []; }).catch(HIS.notifyError);
  }

  /* ==================== 供应商付款(编制/三方式/确认) ==================== */
  HIS.views.SupplierPayment = {
    data: function () {
      return {
        lead: HIS.isLead(), tab: 'unpaid', suppliers: [],
        uLoading: false, unpaid: [], uTotal: 0, uPage: 1, uSize: 20, curSupplier: null, selection: [],
        pLoading: false, pays: [], pTotal: 0, pPage: 1, pSize: 20,
        createDlg: false, saving: false,
        form: { payDate: '', payMethod: 1, amount: null, payChannel: '', remark: '' },
        rows: [],
        viewDlg: false, viewLoading: false, view: { main: {}, items: [] }
      };
    },
    created: function () { loadSuppliers(this); },
    computed: {
      rowsTotal: function () {
        var s = 0;
        for (var i = 0; i < this.rows.length; i++) { s += Number(this.rows[i].alloc) || 0; }
        return s.toFixed(2);
      }
    },
    methods: {
      pmLabel: pmLabel, pmTag: pmTag, payStatusLabel: payStatusLabel, payStatusTag: payStatusTag,
      paidStatusLabel: paidStatusLabel, paidStatusTag: paidStatusTag, money: money, dt: dt, unpaidOf: unpaidOf,
      supplierName: function (id) {
        if (!id) { return '-'; }
        for (var i = 0; i < this.suppliers.length; i++) { if (this.suppliers[i].id === id) { return this.suppliers[i].supplierName; } }
        return '供#' + id;
      },
      onTab: function (name) { if (name === 'unpaid') { this.loadUnpaid(); } else { this.loadPays(); } },
      loadUnpaid: function () {
        var vm = this;
        if (!vm.curSupplier) { vm.unpaid = []; vm.uTotal = 0; return; }
        vm.uLoading = true;
        HIS.get('/api/warehouse/payment/unpaid?supplierId=' + vm.curSupplier + '&page=' + vm.uPage + '&size=' + vm.uSize)
          .then(function (d) { vm.unpaid = (d && d.records) || []; vm.uTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.uLoading = false; });
      },
      searchUnpaid: function () { this.uPage = 1; this.loadUnpaid(); },
      onSelChange: function (rows) { this.selection = rows || []; },
      loadPays: function () {
        var vm = this; vm.pLoading = true;
        var url = '/api/warehouse/payment/page?page=' + vm.pPage + '&size=' + vm.pSize;
        if (vm.curSupplier) { url += '&supplierId=' + vm.curSupplier; }
        HIS.get(url).then(function (d) { vm.pays = (d && d.records) || []; vm.pTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.pLoading = false; });
      },
      searchPays: function () { this.pPage = 1; this.loadPays(); },
      openCreate: function () {
        var vm = this;
        if (!vm.curSupplier) { ElementPlus.ElMessage.warning('请先选择供应商并查询未结入库单'); return; }
        var src = vm.selection.length ? vm.selection : vm.unpaid;
        if (!src.length) { ElementPlus.ElMessage.warning('无未结入库单可付款'); return; }
        vm.rows = src.map(function (r) { return { stockInId: r.id, inNo: r.inNo, totalAmount: r.totalAmount, paidAmount: r.paidAmount, unpaid: unpaidOf(r), alloc: unpaidOf(r) }; });
        vm.form = { payDate: '', payMethod: 1, amount: null, payChannel: '', remark: '' };
        vm.createDlg = true;
      },
      onMethodChange: function () {
        var vm = this;
        if (vm.form.payMethod === 1) {
          for (var i = 0; i < vm.rows.length; i++) { vm.rows[i].alloc = vm.rows[i].unpaid; }
        } else if (vm.form.payMethod === 3) {
          for (var j = 0; j < vm.rows.length; j++) { vm.rows[j].alloc = 0; }
        }
      },
      save: function () {
        var vm = this;
        if (!vm.rows.length) { ElementPlus.ElMessage.warning('无结算明细'); return; }
        if (vm.form.payMethod === 2 && !(Number(vm.form.amount) > 0)) { ElementPlus.ElMessage.warning('输入总额方式请填写付款总额'); return; }
        var items = vm.rows.map(function (r) { return { stockInId: r.stockInId, paidAmount: Number(r.alloc) || 0 }; });
        var payload = {
          supplierId: vm.curSupplier, payDate: vm.form.payDate || null, payMethod: vm.form.payMethod,
          amount: vm.form.payMethod === 2 ? Number(vm.form.amount) : null,
          payChannel: vm.form.payChannel || null, remark: vm.form.remark || null, items: items
        };
        vm.saving = true;
        HIS.post('/api/warehouse/payment/create', payload).then(function (m) {
          HIS.notifySuccess('付款单已建: ' + m.payNo + ' (草稿)');
          vm.createDlg = false; vm.tab = 'pay'; vm.loadPays();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      confirmPay: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认付款单 ' + row.payNo + ' 并回写来源入库单已付标记?', '确认付款', { type: 'warning' }).then(function () {
          HIS.post('/api/warehouse/payment/' + row.id + '/confirm').then(function () { HIS.notifySuccess('已确认付款'); vm.loadPays(); }).catch(HIS.notifyError);
        }).catch(function () { });
      },
      voidPay: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('作废草稿付款单 ' + row.payNo + '?', '作废', { type: 'warning' }).then(function () {
          HIS.del('/api/warehouse/payment/' + row.id).then(function () { HIS.notifySuccess('已作废'); vm.loadPays(); }).catch(HIS.notifyError);
        }).catch(function () { });
      },
      openView: function (row) {
        var vm = this; vm.viewLoading = true; vm.viewDlg = true; vm.view = { main: {}, items: [] };
        HIS.get('/api/warehouse/payment/' + row.id).then(function (d) { vm.view = d || { main: {}, items: [] }; })
          .catch(HIS.notifyError).finally(function () { vm.viewLoading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">供应商付款 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按供应商拉未结入库单 · 全额/输入总额/部分分摊三方式 · 确认回写已付)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="curSupplier" clearable filterable placeholder="选择供应商" style="width:220px;" @change="searchUnpaid"><el-option v-for="sp in suppliers" :key="sp.id" :label="sp.supplierName" :value="sp.id"></el-option></el-select>',
      '    <el-button @click="tab===\'unpaid\'?loadUnpaid():loadPays()">刷新</el-button>',
      '  </div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',
      '    <el-tab-pane label="未结入库单" name="unpaid">',
      '      <div class="toolbar">',
      '        <el-button v-if="lead" type="primary" :disabled="!curSupplier" @click="openCreate">新建付款单(未结{{unpaid.length}}单/选中{{selection.length}})</el-button>',
      '        <span v-if="!curSupplier" style="color:var(--yb-ink-2);font-size:12px;">请先选择供应商</span>',
      '      </div>',
      '      <el-table :data="unpaid" v-loading="uLoading" border stripe size="small" height="100%" @selection-change="onSelChange">',
      '        <el-table-column type="selection" width="42"></el-table-column>',
      '        <el-table-column prop="inNo" label="入库单号" width="160"></el-table-column>',
      '        <el-table-column prop="invoiceNo" label="发票号" width="130" show-overflow-tooltip><template #default="s">{{ s.row.invoiceNo || "-" }}</template></el-table-column>',
      '        <el-table-column label="应付额" width="110" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '        <el-table-column label="已付额" width="110" align="right"><template #default="s">{{ money(s.row.paidAmount) }}</template></el-table-column>',
      '        <el-table-column label="未结额" width="110" align="right"><template #default="s"><b>{{ money(unpaidOf(s.row)) }}</b></template></el-table-column>',
      '        <el-table-column label="付款状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="paidStatusTag(s.row.paidStatus)">{{ paidStatusLabel(s.row.paidStatus) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="确认时间" min-width="150"><template #default="s">{{ dt(s.row.confirmTime) }}</template></el-table-column>',
      '      </el-table>',
      '      <el-pagination background layout="total, prev, pager, next" :total="uTotal" :page-size="uSize" :current-page="uPage" @current-change="p=>{uPage=p;loadUnpaid()}" style="margin-top:8px;"></el-pagination>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="付款单" name="pay">',
      '      <el-table :data="pays" v-loading="pLoading" border stripe size="small" height="100%">',
      '        <el-table-column prop="payNo" label="付款单号" width="160"></el-table-column>',
      '        <el-table-column label="供应商" width="150"><template #default="s">{{ supplierName(s.row.supplierId) }}</template></el-table-column>',
      '        <el-table-column prop="payDate" label="付款日期" width="110"></el-table-column>',
      '        <el-table-column label="方式" width="90" align="center"><template #default="s"><el-tag size="small" :type="pmTag(s.row.payMethod)">{{ pmLabel(s.row.payMethod) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="金额" width="110" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="payStatusTag(s.row.status)">{{ payStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="confirmBy" label="确认人" width="90"></el-table-column>',
      '        <el-table-column label="操作" min-width="160"><template #default="s">',
      '          <el-button link type="primary" @click="openView(s.row)">查看</el-button>',
      '          <template v-if="lead">',
      '            <el-button v-if="s.row.status===0" link type="success" @click="confirmPay(s.row)">确认付款</el-button>',
      '            <el-button v-if="s.row.status===0" link @click="voidPay(s.row)">作废</el-button>',
      '          </template>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination background layout="total, prev, pager, next" :total="pTotal" :page-size="pSize" :current-page="pPage" @current-change="p=>{pPage=p;loadPays()}" style="margin-top:8px;"></el-pagination>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      /* 新建付款对话框 */
      '  <el-dialog v-model="createDlg" title="新建付款单" width="900px" top="6vh">',
      '    <el-form :model="form" label-width="90px" size="small">',
      '      <el-row :gutter="10">',
      '        <el-col :span="8"><el-form-item label="供应商"><el-input :model-value="supplierName(curSupplier)" readonly></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="付款日期"><el-date-picker v-model="form.payDate" type="date" value-format="YYYY-MM-DD" style="width:100%;"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="付款方式"><el-select v-model="form.payMethod" style="width:100%;" @change="onMethodChange"><el-option label="全额付清" :value="1"></el-option><el-option label="输入总额" :value="2"></el-option><el-option label="部分分摊" :value="3"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="10">',
      '        <el-col :span="8"><el-form-item v-if="form.payMethod===2" label="付款总额"><el-input-number v-model="form.amount" :min="0" :precision="2" :controls="false" style="width:100%;"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="支付渠道"><el-input v-model="form.payChannel" placeholder="转账/现金等"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="备注"><el-input v-model="form.remark"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <div style="color:var(--yb-ink-2);font-size:12px;margin:0 0 6px 90px;">{{ form.payMethod===1?"全额: 各单一次付清未结额":(form.payMethod===2?"输入总额: 确认后按顺序分摊至各单(不超未结额), 明细额建单时自动计算":"部分分摊: 逐单填写本次付款额(不超未结额)") }}</div>',
      '    </el-form>',
      '    <div style="margin-bottom:6px;">结算明细 · 合计本次付款 ￥<b>{{ rowsTotal }}</b></div>',
      '    <el-table :data="rows" border size="small" max-height="300">',
      '      <el-table-column prop="inNo" label="入库单号" width="160"></el-table-column>',
      '      <el-table-column label="应付额" width="110" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '      <el-table-column label="已付额" width="110" align="right"><template #default="s">{{ money(s.row.paidAmount) }}</template></el-table-column>',
      '      <el-table-column label="未结额" width="110" align="right"><template #default="s">{{ money(s.row.unpaid) }}</template></el-table-column>',
      '      <el-table-column label="本次付款额" width="150"><template #default="s"><el-input-number v-model="s.row.alloc" :min="0" :max="s.row.unpaid" :precision="2" :controls="false" :disabled="form.payMethod!==3" size="small" style="width:100%;"></el-input-number></template></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="createDlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>',
      '  </el-dialog>',
      /* 查看对话框 */
      '  <el-dialog v-model="viewDlg" title="付款单详情" width="820px">',
      '    <div v-loading="viewLoading">',
      '      <div style="margin-bottom:8px;">{{ view.main.payNo }} · 供应商 {{ supplierName(view.main.supplierId) }} · {{ view.main.payDate }} · <el-tag size="small" :type="pmTag(view.main.payMethod)">{{ pmLabel(view.main.payMethod) }}</el-tag> · 金额 {{ money(view.main.amount) }}</div>',
      '      <el-table :data="view.items" border stripe size="small" max-height="420">',
      '        <el-table-column prop="stockInNo" label="入库单号" width="160"></el-table-column>',
      '        <el-table-column label="应付额" width="110" align="right"><template #default="s">{{ money(s.row.inAmount) }}</template></el-table-column>',
      '        <el-table-column label="结算前已付" width="110" align="right"><template #default="s">{{ money(s.row.paidBefore) }}</template></el-table-column>',
      '        <el-table-column label="本次分摊" width="110" align="right"><template #default="s"><b>{{ money(s.row.paidAmount) }}</b></template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="viewDlg=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 应付账款 + 账龄分析 ==================== */
  HIS.views.PayableReport = {
    data: function () {
      return {
        suppliers: [], curSupplier: null, loading: false, loaded: false,
        data: { totalPayable: 0, billCount: 0, aging: {}, bills: [] }
      };
    },
    created: function () { loadSuppliers(this); },
    computed: {
      agingRows: function () {
        var a = this.data.aging || {};
        return [
          { label: '0-30天', v: a.d0_30 },
          { label: '31-60天', v: a.d31_60 },
          { label: '61-90天', v: a.d61_90 },
          { label: '90天以上', v: a.d90_plus }
        ];
      }
    },
    methods: {
      money: money, dt: dt,
      supplierName: function (id) {
        if (!id) { return '全部'; }
        for (var i = 0; i < this.suppliers.length; i++) { if (this.suppliers[i].id === id) { return this.suppliers[i].supplierName; } }
        return '供#' + id;
      },
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/warehouse/payment/payable';
        if (vm.curSupplier) { url += '?supplierId=' + vm.curSupplier; }
        HIS.get(url).then(function (d) { vm.data = d || { totalPayable: 0, billCount: 0, aging: {}, bills: [] }; vm.loaded = true; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">应付账款 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(入库确认额-已付额 · 按入库日期账龄分档)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="curSupplier" clearable filterable placeholder="全部供应商" style="width:220px;" @change="load"><el-option v-for="sp in suppliers" :key="sp.id" :label="sp.supplierName" :value="sp.id"></el-option></el-select>',
      '    <el-button type="primary" @click="load">查询</el-button>',
      '  </div>',
      '  <div v-loading="loading" style="height:calc(100% - 90px);overflow:auto;">',
      '    <template v-if="loaded">',
      '      <div class="stat-grid" style="grid-template-columns:repeat(3,1fr);">',
      '        <div class="stat-card"><div class="num">{{ supplierName(curSupplier) }}</div><div class="lbl">供应商</div></div>',
      '        <div class="stat-card"><div class="num">{{ data.billCount }}</div><div class="lbl">未结单数</div></div>',
      '        <div class="stat-card"><div class="num" style="color:var(--yb-danger);">￥{{ money(data.totalPayable) }}</div><div class="lbl">应付总额</div></div>',
      '      </div>',
      '      <div style="font-weight:600;margin-bottom:6px;">账龄分析</div>',
      '      <el-table :data="agingRows" border size="small" style="margin-bottom:14px;width:520px;">',
      '        <el-table-column prop="label" label="区间" width="140"></el-table-column>',
      '        <el-table-column label="金额" align="right"><template #default="s">{{ money(s.row.v) }}</template></el-table-column>',
      '      </el-table>',
      '      <div style="font-weight:600;margin-bottom:6px;">未结入库单明细</div>',
      '      <el-table :data="data.bills" border stripe size="small">',
      '        <el-table-column prop="inNo" label="入库单号" width="160"></el-table-column>',
      '        <el-table-column prop="supplier" label="供应商" width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="invoiceNo" label="发票号" width="120"><template #default="s">{{ s.row.invoiceNo || "-" }}</template></el-table-column>',
      '        <el-table-column label="应付额" width="100" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '        <el-table-column label="已付额" width="100" align="right"><template #default="s">{{ money(s.row.paidAmount) }}</template></el-table-column>',
      '        <el-table-column label="未结额" width="100" align="right"><template #default="s"><b>{{ money(s.row.unpaidAmount) }}</b></template></el-table-column>',
      '        <el-table-column prop="baseDate" label="入库日期" width="110"></el-table-column>',
      '        <el-table-column label="账龄(天)" width="90" align="right"><template #default="s">{{ s.row.agingDays }}</template></el-table-column>',
      '      </el-table>',
      '    </template>',
      '    <el-empty v-else description="选择供应商或点击查询"></el-empty>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
