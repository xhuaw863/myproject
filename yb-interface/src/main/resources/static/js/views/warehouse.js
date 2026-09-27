/* 药库: 库存总览(库存/预警/流水) + 采购入库 + 出库管理 + 药库定义管理 + 药品目录 + 盘点管理
 * 后端: /api/his/stock (DrugStockController, 批次级库存记账/乐观锁扣减/FIFO, 机构级多药库)
 * 药品选择: /api/his/stock/drug-catalog(机构开展目录, 按 warehouseType 联动中药范围);
 * 出库批次: /api/his/stock/page(库存批次, 可按 warehouseId 过滤)
 * 读: 后端 scopeOrgId 自动隔离(非牵头锁定本院, 前端不传 orgId);
 * 写(新建/确认/作废/盘点/药库维护): 后端 requireLeadWrite 仅牵头机构管理员, 前端按 lead 隐藏按钮并以后端403兜底。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量 ===== */
  var IN_TYPES = [
    { v: 1, l: '采购' },
    { v: 2, l: '退药回库' },
    { v: 3, l: '盘盈' },
    { v: 4, l: '调拨入' }
  ];
  var OUT_TYPES = [
    { v: 1, l: '处方发药' },
    { v: 2, l: '报损' },
    { v: 3, l: '盘亏' },
    { v: 4, l: '调拨出' }
  ];
  /* 单据状态: 0草稿 1已确认 2已作废 */
  var BIZ_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '已确认', t: 'success' },
    { v: 2, l: '已作废', t: 'danger' }
  ];

  function typeLabel(types, v) {
    for (var i = 0; i < types.length; i++) { if (types[i].v === v) { return types[i].l; } }
    return (v === null || v === undefined || v === '') ? '-' : v;
  }
  function inTypeLabel(v) { return typeLabel(IN_TYPES, v); }
  function outTypeLabel(v) { return typeLabel(OUT_TYPES, v); }
  function statusLabel(v) {
    for (var i = 0; i < BIZ_STATUS.length; i++) { if (BIZ_STATUS[i].v === v) { return BIZ_STATUS[i].l; } }
    return '-';
  }
  function statusTag(v) {
    for (var i = 0; i < BIZ_STATUS.length; i++) { if (BIZ_STATUS[i].v === v) { return BIZ_STATUS[i].t; } }
    return 'info';
  }

  /* 药库类型: WESTERN-西药 / TCM-中药 / MIXED-综合 */
  var WH_TYPES = [
    { v: 'WESTERN', l: '西药' },
    { v: 'TCM', l: '中药' },
    { v: 'MIXED', l: '综合' }
  ];
  function whTypeLabel(v) {
    for (var i = 0; i < WH_TYPES.length; i++) { if (WH_TYPES[i].v === v) { return WH_TYPES[i].l; } }
    return (v === null || v === undefined || v === '') ? '-' : v;
  }

  /* 盘点单状态: 0进行中 1已完成 2已作废 */
  var CHECK_STATUS = [
    { v: 0, l: '进行中', t: 'warning' },
    { v: 1, l: '已完成', t: 'success' },
    { v: 2, l: '已作废', t: 'info' }
  ];
  function checkStatusLabel(v) {
    for (var i = 0; i < CHECK_STATUS.length; i++) { if (CHECK_STATUS[i].v === v) { return CHECK_STATUS[i].l; } }
    return '-';
  }
  function checkStatusTag(v) {
    for (var i = 0; i < CHECK_STATUS.length; i++) { if (CHECK_STATUS[i].v === v) { return CHECK_STATUS[i].t; } }
    return 'info';
  }
  /* 金额: 单据/合计2位小数, 进价4位小数 */
  function money2(n) { return (n === null || n === undefined || n === '') ? '-' : Number(n).toFixed(2); }
  function money4(n) { return (n === null || n === undefined || n === '') ? '-' : Number(n).toFixed(4); }
  /* 日期: LocalDateTime/LocalDate(ISO, 含T) -> yyyy-MM-dd / yyyy-MM-dd HH:mm */
  function fmtDate(v) { return v ? String(v).slice(0, 10) : '-'; }
  function fmtTime(v) { return v ? String(v).replace('T', ' ').slice(0, 16) : '-'; }
  /* 距有效期天数(负数=已过期); 无法解析返回 null */
  function expDays(v) {
    if (!v) { return null; }
    var d = new Date(String(v).slice(0, 10) + 'T00:00:00');
    if (isNaN(d.getTime())) { return null; }
    var t = new Date(); t.setHours(0, 0, 0, 0);
    return Math.floor((d.getTime() - t.getTime()) / 86400000);
  }
  /* 效期状态: expired=已过期(红) soon=30天内到期(橙) */
  function expState(v) {
    var d = expDays(v);
    if (d === null) { return ''; }
    if (d < 0) { return 'expired'; }
    if (d <= 30) { return 'soon'; }
    return '';
  }
  /* 药库名称映射(defs 来自 /api/his/stock/warehouse-def) */
  function whNameById(defs, id) {
    for (var i = 0; i < (defs || []).length; i++) { if (defs[i].id === id) { return defs[i].name; } }
    return '-';
  }
  /* 药库类型查询(defs 中找不到返回空串, 调用方按 全部/综合 处理) */
  function whTypeById(defs, id) {
    for (var i = 0; i < (defs || []).length; i++) { if (defs[i].id === id) { return defs[i].warehouseType; } }
    return '';
  }

  /* ================= 库存总览(库存列表 / 低库存预警 / 出入库流水) ================= */
  HIS.views.DrugStock = {
    mixins: [HIS.whScopeMixin],
    data: function () {
      return {
        activeTab: 'stock',
        /* 库存列表 */
        loading: false, list: [], total: 0, page: 1, size: 20,
        keyword: '', lowStock: false,
        /* 低库存预警 */
        alertList: [], alertLoading: false,
        /* 出入库流水 */
        flowLoading: false, flowList: [], flowTotal: 0, flowPage: 1, flowSize: 20,
        flowRange: []
      };
    },
    created: function () { var vm = this; this.loadWarehouseDefs().then(function () { vm.load(); }); },
    methods: {
      whParam: function () { return this.warehouseId ? '&warehouseId=' + this.warehouseId : ''; },
      whName: function (id) { return whNameById(this.warehouseDefs, id); },
      /* 药库过滤切换: 按当前页签重查 */
      onWarehouseChange: function () {
        if (this.activeTab === 'stock') { this.page = 1; this.load(); }
        else if (this.activeTab === 'alert') { this.loadAlert(); }
        else if (this.activeTab === 'flow') { this.flowPage = 1; this.loadFlow(); }
      },
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/his/stock/page?page=' + vm.page + '&size=' + vm.size + vm.whParam();
        if (vm.keyword) { url += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.lowStock) { url += '&lowStock=true'; }
        HIS.get(url).then(function (data) {
          vm.list = (data && data.records) || [];
          vm.total = Number((data && data.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      doExport: function () {
        HIS.download('/api/his/stock/export' + (this.warehouseId ? '?warehouseId=' + this.warehouseId : ''), '药品库存.xlsx').then(function (name) {
          HIS.notifySuccess('已导出: ' + name);
        }).catch(HIS.notifyError);
      },
      /* 低库存预警 */
      loadAlert: function () {
        var vm = this; vm.alertLoading = true;
        HIS.get('/api/his/stock/alert' + (vm.warehouseId ? '?warehouseId=' + vm.warehouseId : '')).then(function (d) { vm.alertList = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.alertLoading = false; });
      },
      /* 出入库流水(已确认单据明细, 确认时间倒序) */
      loadFlow: function () {
        var vm = this; vm.flowLoading = true;
        var url = '/api/his/stock/flow?page=' + vm.flowPage + '&size=' + vm.flowSize + vm.whParam();
        if (vm.flowRange && vm.flowRange.length === 2) {
          url += '&startDate=' + vm.flowRange[0] + '&endDate=' + vm.flowRange[1];
        }
        HIS.get(url).then(function (d) {
          vm.flowList = (d && d.records) || [];
          vm.flowTotal = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.flowLoading = false; });
      },
      searchFlow: function () { this.flowPage = 1; this.loadFlow(); },
      /* 切页签总是重查: 药库过滤变化后旧数据不可复用 */
      onTab: function (t) {
        if (t === 'alert') { this.loadAlert(); }
        if (t === 'flow') { this.loadFlow(); }
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      onFlowPage: function (p) { this.flowPage = p; this.loadFlow(); },
      onFlowSize: function (s) { this.flowSize = s; this.flowPage = 1; this.loadFlow(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      flowSeqNo: function (i) { return (this.flowPage - 1) * this.flowSize + i + 1; },
      inTypeLabel: inTypeLabel,
      outTypeLabel: outTypeLabel,
      money2: money2,
      money4: money4,
      fmtDate: fmtDate,
      fmtTime: fmtTime,
      expState: expState,
      expDays: expDays
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">库存总览 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(批次级库存 · 低库存/效期预警 · 出入库流水)</span></div>',
      '  <div class="toolbar" style="margin-bottom:6px;">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">当前药库</span>',
      '    <el-select v-model="warehouseId" style="width:200px" :disabled="whSingle" @change="onWarehouseChange">',
      '      <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '    </el-select>',
      '    <span v-if="currentWarehouse" style="color:var(--yb-link);font-size:13px;font-weight:600;">{{ currentWhLabel }}</span>',
      '    <span v-else style="color:var(--yb-warning);font-size:13px;">暂无可操作药库(需在药库管理绑定归属科室并在用户管理授权)</span>',
      '    <span style="color:var(--yb-ink-2);font-size:12px;">当前药库作用于库存列表 / 低库存预警 / 出入库流水 · 切换器仅列授权药库</span>',
      '  </div>',
      '  <el-tabs v-model="activeTab" @tab-change="onTab">',
      /* ---- 库存列表 ---- */
      '    <el-tab-pane label="库存列表" name="stock">',
      '      <div class="toolbar">',
      '        <el-input v-model="keyword" placeholder="药品名称/编码/批号/厂家" clearable style="width:240px" @keyup.enter="search" @clear="search"></el-input>',
      '        <el-switch v-model="lowStock" active-text="仅显示低库存" @change="search"></el-switch>',
      '        <el-button type="primary" @click="search">查询</el-button>',
      '        <el-button @click="load">刷新</el-button>',
      '        <el-button @click="doExport">导出Excel</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条库存记录</span>',
      '      </div>',
      '      <el-table :data="list" v-loading="loading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '        <el-table-column label="药库" width="110" show-overflow-tooltip><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '        <el-table-column prop="drugCode" label="药品编码" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="drugName" label="药品名称" width="180" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="dosform" label="剂型" width="80"></el-table-column>',
      '        <el-table-column prop="manufacturer" label="生产厂家" width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批次号" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="库存数量" width="100" align="right"><template #default="s">',
      '          <span :style="{color: (Number(s.row.qty) <= Number(s.row.warnQty)) ? \'var(--yb-danger)\' : \'var(--yb-ink-1)\', fontWeight: (Number(s.row.qty) <= Number(s.row.warnQty)) ? 700 : 400}">{{ s.row.qty }}</span>',
      '        </template></el-table-column>',
      '        <el-table-column label="进价" width="90" align="right"><template #default="s">{{ money4(s.row.costPrice) }}</template></el-table-column>',
      '        <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ money4(s.row.retailPrice) }}</template></el-table-column>',
      '        <el-table-column label="有效期" width="110"><template #default="s">',
      '          <span v-if="expState(s.row.expDate)===\'expired\'" style="color:var(--yb-danger);font-weight:700;" title="已过期">{{ fmtDate(s.row.expDate) }} 已过期</span>',
      '          <span v-else-if="expState(s.row.expDate)===\'soon\'" style="color:var(--yb-warning);font-weight:600;" :title="\'剩余\'+expDays(s.row.expDate)+\'天\'">{{ fmtDate(s.row.expDate) }} ({{ expDays(s.row.expDate) }}天)</span>',
      '          <span v-else>{{ fmtDate(s.row.expDate) }}</span>',
      '        </template></el-table-column>',
      '        <el-table-column prop="warnQty" label="预警量" width="80" align="right"></el-table-column>',
      '        <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"正常":"停用" }}</el-tag></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </el-tab-pane>',
      /* ---- 低库存预警 ---- */
      '    <el-tab-pane name="alert">',
      '      <template #label>低库存预警<el-badge v-if="alertList.length" :value="alertList.length" type="danger" style="margin-left:4px;"></el-badge></template>',
      '      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px;" title="库存数量 ≤ 预警量 且未停用的批次, 请及时采购补货"></el-alert>',
      '      <el-table :data="alertList" v-loading="alertLoading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '        <el-table-column label="药库" width="110" show-overflow-tooltip><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '        <el-table-column prop="drugCode" label="药品编码" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="drugName" label="药品名称" width="200" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="manufacturer" label="生产厂家" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批次号" width="120"></el-table-column>',
      '        <el-table-column label="库存/预警量" width="110" align="right"><template #default="s"><span style="color:var(--yb-danger);font-weight:700;">{{ s.row.qty }}</span> / {{ s.row.warnQty }}</template></el-table-column>',
      '        <el-table-column label="有效期" width="110"><template #default="s">',
      '          <span v-if="expState(s.row.expDate)===\'expired\'" style="color:var(--yb-danger);font-weight:700;">{{ fmtDate(s.row.expDate) }} 已过期</span>',
      '          <span v-else>{{ fmtDate(s.row.expDate) }}</span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      /* ---- 出入库流水 ---- */
      '    <el-tab-pane label="出入库流水" name="flow">',
      '      <div class="toolbar">',
      '        <el-date-picker v-model="flowRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="确认开始日期" end-placeholder="确认结束日期" style="width:280px" @change="searchFlow"></el-date-picker>',
      '        <el-button type="primary" @click="searchFlow">查询</el-button>',
      '        <el-button @click="loadFlow">刷新</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">已确认单据明细(按确认时间倒序) · 共 {{ flowTotal }} 条</span>',
      '      </div>',
      '      <el-table :data="flowList" v-loading="flowLoading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="60" :index="flowSeqNo"></el-table-column>',
      '        <el-table-column label="确认时间" width="140"><template #default="s">{{ fmtTime(s.row.opTime) }}</template></el-table-column>',
      '        <el-table-column label="方向" width="80"><template #default="s"><el-tag size="small" :type="s.row.flowType===\'IN\'?\'success\':\'warning\'">{{ s.row.flowTypeName }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="billNo" label="单据号" width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="业务类型" width="90"><template #default="s">{{ s.row.flowType===\'IN\'?inTypeLabel(s.row.billType):outTypeLabel(s.row.billType) }}</template></el-table-column>',
      '        <el-table-column prop="drugCode" label="药品编码" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="drugName" label="药品名称" width="170" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批次号" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="qty" label="数量" width="80" align="right"></el-table-column>',
      '        <el-table-column label="进价" width="90" align="right"><template #default="s">{{ money4(s.row.costPrice) }}</template></el-table-column>',
      '        <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ money4(s.row.retailPrice) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="90" align="right"><template #default="s">{{ money2(s.row.amount) }}</template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next, jumper" :total="flowTotal" :page-size="flowSize" :page-sizes="[20, 50, 100]" :current-page="flowPage" @current-change="onFlowPage" @size-change="onFlowSize"></el-pagination>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };

  /* ================= 入库管理(左列表 + 右详情/编辑, 药品选择器) =================
   * 单据生命周期: 新建(可编辑) --保存草稿--> 草稿(只读, 可确认/作废) --确认--> 已确认(入库upsert库存)。
   * 后端无草稿更新接口: 已保存单据只读展示, 需修改请作废后重建。 */
  HIS.views.StockInManage = {
    mixins: [HIS.whScopeMixin],
    data: function () {
      return {
        lead: HIS.isLead(),
        /* 左侧入库单列表 */
        loading: false, inList: [], inTotal: 0, inPage: 1, inSize: 20,
        filterStatus: null, dateRange: [],
        /* 右侧当前单据: current=主表, currentItems=明细; isNew=新建可编辑 */
        current: null, currentItems: [], isNew: false, detailLoading: false,
        saving: false,
        inTypes: IN_TYPES,
        /* 药品选择器 */
        drugDlg: false, drugKeyword: '', drugList: [], drugLoading: false,
        drugTotal: 0, drugPage: 1, drugSize: 20, pendingIdx: null
      };
    },
    computed: {
      /* 仅新建模式可编辑(已保存单据后端无更新接口, 一律只读) */
      editable: function () { return this.isNew; },
      itemsTotal: function () {
        var s = 0;
        for (var i = 0; i < this.currentItems.length; i++) {
          s += (Number(this.currentItems[i].qty) || 0) * (Number(this.currentItems[i].costPrice) || 0);
        }
        return s.toFixed(2);
      },
      /* 选药范围提示: 由目标药库类型联动(后端按库类型过滤中药属性) */
      drugScopeHint: function () {
        var t = whTypeById(this.warehouseDefs, this.current && this.current.warehouseId);
        if (t === 'WESTERN') { return '西药库: 仅可选西药(已排除中药/中成药)'; }
        if (t === 'TCM') { return '中药库: 仅可选中药/中成药'; }
        return '综合库/未选库: 可选本机构开展的全部药品';
      }
    },
    created: function () { var vm = this; this.loadWarehouseDefs().then(function () { vm.load(); }); },
    methods: {
      /* ---- 左侧列表 ---- */
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/stock/in/page?page=' + vm.inPage + '&size=' + vm.inSize;
        if (vm.warehouseId) { q += '&warehouseId=' + vm.warehouseId; }
        if (vm.filterStatus !== null && vm.filterStatus !== '') { q += '&status=' + vm.filterStatus; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        HIS.get(q).then(function (d) {
          vm.inList = (d && d.records) || [];
          vm.inTotal = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.inPage = 1; this.load(); },
      onInPage: function (p) { this.inPage = p; this.load(); },
      onInSize: function (s) { this.inSize = s; this.inPage = 1; this.load(); },
      inSeqNo: function (i) { return (this.inPage - 1) * this.inSize + i + 1; },
      whName: function (id) { return whNameById(this.warehouseDefs, id); },
      /* 建单默认药库: 优先工具栏过滤药库, 其次唯一启用库 */
      prePickWarehouse: function () {
        if (this.warehouseId) { return this.warehouseId; }
        return this.warehouseDefs.length === 1 ? this.warehouseDefs[0].id : null;
      },
      /* 选中已有单据: 加载详情(只读) */
      onSelectIn: function (row) {
        if (!row) { return; }
        this.loadDetail(row.id);
      },
      loadDetail: function (id) {
        var vm = this; vm.detailLoading = true;
        HIS.get('/api/his/stock/in/' + id).then(function (d) {
          vm.current = (d && d.main) || null;
          vm.currentItems = (d && d.items) || [];
          vm.isNew = false;
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      /* ---- 新建/编辑 ---- */
      createNew: function () {
        this.current = { id: null, inNo: '(保存后生成)', warehouseId: this.prePickWarehouse(), inType: 1, supplier: '', supplierContact: '', remark: '', status: 0, totalAmount: 0 };
        this.currentItems = [];
        this.isNew = true;
      },
      emptyItem: function () {
        return { drugCatalogId: null, drugCode: '', drugName: '', spec: '', batchNo: '', manufacturer: '', qty: null, costPrice: null, retailPrice: null, prodDate: '', expDate: '', amount: null };
      },
      addItem: function () {
        this.currentItems.push(this.emptyItem());
        this.openDrugDlg(this.currentItems.length - 1);
      },
      removeItem: function (i) { this.currentItems.splice(i, 1); },
      lineAmount: function (it) {
        return ((Number(it.qty) || 0) * (Number(it.costPrice) || 0)).toFixed(2);
      },
      /* ---- 药品选择器(机构开展药品目录, 按目标药库类型联动范围) ---- */
      openDrugDlg: function (idx) {
        if (!this.current || !this.current.warehouseId) {
          ElementPlus.ElMessage.warning('请先选择目标药库, 再选择药品(中药库仅可入中药/中成药)');
          return;
        }
        this.pendingIdx = idx;
        this.drugKeyword = ''; this.drugPage = 1; this.drugList = [];
        this.drugDlg = true;
        this.loadDrugs();
      },
      loadDrugs: function () {
        var vm = this; vm.drugLoading = true;
        var q = '/api/his/stock/drug-catalog?page=' + vm.drugPage + '&size=' + vm.drugSize;
        var wt = whTypeById(vm.warehouseDefs, vm.current && vm.current.warehouseId);
        if (wt === 'WESTERN' || wt === 'TCM') { q += '&warehouseType=' + wt; }
        if (vm.drugKeyword) { q += '&keyword=' + encodeURIComponent(vm.drugKeyword); }
        HIS.get(q).then(function (d) {
          vm.drugList = (d && d.records) || [];
          vm.drugTotal = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.drugLoading = false; });
      },
      searchDrugs: function () { this.drugPage = 1; this.loadDrugs(); },
      onDrugPage: function (p) { this.drugPage = p; this.loadDrugs(); },
      /* 选中药品: 回填目录ID/编码/名称/规格/厂家, 带出参考价(进价=进货价, 零售价) */
      pickDrug: function (d) {
        var it = this.currentItems[this.pendingIdx];
        if (!it) { this.drugDlg = false; return; }
        it.drugCatalogId = d.id;
        it.drugCode = d.drugCode;
        it.drugName = d.genericName || d.tradeName || d.drugCode;
        it.spec = d.spec || '';
        it.manufacturer = d.manufacturer || '';
        if (it.costPrice === null || it.costPrice === undefined) { it.costPrice = d.purchasePrice != null ? d.purchasePrice : null; }
        if (it.retailPrice === null || it.retailPrice === undefined) { it.retailPrice = d.retailPrice != null ? d.retailPrice : null; }
        this.drugDlg = false;
      },
      /* ---- 校验/保存草稿/确认/作废 ---- */
      validate: function () {
        var vm = this;
        if (!vm.current.warehouseId) { ElementPlus.ElMessage.warning('请先选择目标药库'); return false; }
        if (!vm.currentItems.length) { ElementPlus.ElMessage.warning('请至少添加一条入库明细'); return false; }
        for (var i = 0; i < vm.currentItems.length; i++) {
          var it = vm.currentItems[i];
          if (!it.drugCatalogId || !it.drugCode || !it.drugName) {
            ElementPlus.ElMessage.warning('第' + (i + 1) + '行请先选择药品'); return false;
          }
          if (!it.batchNo || !String(it.batchNo).trim()) {
            ElementPlus.ElMessage.warning('第' + (i + 1) + '行请填写批次号'); return false;
          }
          if (!(Number(it.qty) > 0)) {
            ElementPlus.ElMessage.warning('第' + (i + 1) + '行数量必须大于0'); return false;
          }
        }
        return true;
      },
      buildPayload: function () {
        var vm = this;
        return {
          warehouseId: vm.current.warehouseId,
          inType: vm.current.inType,
          supplier: vm.current.supplier,
          supplierContact: vm.current.supplierContact,
          remark: vm.current.remark,
          items: vm.currentItems.map(function (it) {
            return {
              drugCatalogId: it.drugCatalogId, drugCode: it.drugCode, drugName: it.drugName,
              spec: it.spec, batchNo: String(it.batchNo || '').trim(), manufacturer: it.manufacturer,
              qty: it.qty, costPrice: it.costPrice, retailPrice: it.retailPrice,
              prodDate: it.prodDate || null, expDate: it.expDate || null,
              amount: (Number(it.qty) || 0) * (Number(it.costPrice) || 0)
            };
          })
        };
      },
      saveDraft: function () {
        var vm = this;
        if (!vm.validate()) { return; }
        vm.saving = true;
        HIS.post('/api/his/stock/in', vm.buildPayload()).then(function (m) {
          HIS.notifySuccess('入库单已保存草稿: ' + m.inNo);
          vm.load();
          vm.loadDetail(m.id);
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* 确认入库: 新建单先落草稿再确认; 已有草稿单直接确认。确认后库存upsert, 不可作废 */
      confirmBill: function () {
        var vm = this;
        if (vm.isNew) {
          if (!vm.validate()) { return; }
          ElementPlus.ElMessageBox.confirm('将保存入库单并立即确认入库(写入库存), 该操作不可作废。是否继续？', '确认入库', { type: 'warning' })
            .then(function () {
              vm.saving = true;
              return HIS.post('/api/his/stock/in', vm.buildPayload()).then(function (m) {
                return HIS.post('/api/his/stock/in/' + m.id + '/confirm').then(function () {
                  HIS.notifySuccess('已确认入库: ' + m.inNo + ' 金额￥' + vm.itemsTotal);
                  vm.load(); vm.loadDetail(m.id);
                });
              });
            })
            .catch(function (e) { if (e && e.message) { HIS.notifyError(e); } })
            .finally(function () { vm.saving = false; });
        } else {
          ElementPlus.ElMessageBox.confirm('确认入库后库存将增加且单据不可作废。是否继续？', '确认入库', { type: 'warning' })
            .then(function () {
              vm.saving = true;
              return HIS.post('/api/his/stock/in/' + vm.current.id + '/confirm').then(function () {
                HIS.notifySuccess('已确认入库: ' + vm.current.inNo);
                vm.load(); vm.loadDetail(vm.current.id);
              });
            })
            .catch(function (e) { if (e && e.message) { HIS.notifyError(e); } })
            .finally(function () { vm.saving = false; });
        }
      },
      voidBill: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('作废后不可恢复, 如需修改请新建入库单。确认作废 ' + vm.current.inNo + ' ？', '作废入库单', { type: 'warning' })
          .then(function () {
            HIS.del('/api/his/stock/in/' + vm.current.id).then(function () {
              HIS.notifySuccess('已作废: ' + vm.current.inNo);
              vm.current = null; vm.currentItems = [];
              vm.load();
            }).catch(HIS.notifyError);
          })
          .catch(function () { });
      },
      inTypeLabel: inTypeLabel,
      statusLabel: statusLabel,
      statusTag: statusTag,
      money2: money2,
      fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">采购入库 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(草稿→确认入库写库存 → 作废仅草稿态)</span></div>',
      '  <div class="dept-split">',
      /* ---- 左侧: 入库单列表 ---- */
      '    <div style="width:400px; flex:none; border-right:1px solid var(--yb-border-light); padding-right:16px; display:flex; flex-direction:column; overflow:hidden;">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-select v-model="filterStatus" placeholder="全部状态" clearable size="small" style="width:110px" @change="search">',
      '          <el-option label="草稿" :value="0"></el-option><el-option label="已确认" :value="1"></el-option><el-option label="已作废" :value="2"></el-option>',
      '        </el-select>',
      '        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" size="small" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:250px" @change="search"></el-date-picker>',
      '      </div>',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <span style="font-weight:600;color:var(--yb-ink-1);font-size:13px;">当前药库</span>',
      '        <el-select v-model="warehouseId" size="small" style="width:150px" :disabled="whSingle" @change="search">',
      '          <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '        </el-select>',
      '        <el-tag v-if="currentWarehouse" size="small">{{ currentWhLabel }}</el-tag>',
      '        <el-tag v-else size="small" type="warning">无可操作药库</el-tag>',
      '        <el-button v-if="lead" type="primary" size="small" @click="createNew">新建入库单</el-button>',
      '        <el-button size="small" @click="load">刷新</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ inTotal }} 单</span>',
      '      </div>',
      '      <el-table :data="inList" v-loading="loading" highlight-current-row size="small" border style="width:100%;" @current-change="onSelectIn">',
      '        <el-table-column type="index" label="序号" width="55" :index="inSeqNo"></el-table-column>',
      '        <el-table-column prop="inNo" label="入库单号" width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="药库" width="95" show-overflow-tooltip><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '        <el-table-column label="类型" width="80"><template #default="s">{{ inTypeLabel(s.row.inType) }}</template></el-table-column>',
      '        <el-table-column label="总金额" width="90" align="right"><template #default="s">{{ money2(s.row.totalAmount) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="75"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="创建时间" width="110" show-overflow-tooltip><template #default="s">{{ fmtTime(s.row.createTime) }}</template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="inTotal" :page-size="inSize" :current-page="inPage" @current-change="onInPage"></el-pagination>',
      '    </div>',
      /* ---- 右侧: 详情/编辑 ---- */
      '    <div v-loading="detailLoading" style="flex:1; min-width:0; padding-left:16px; overflow:auto;">',
      '      <el-alert v-if="!current" type="info" :closable="false" show-icon title="请从左侧选择入库单, 或点击「新建入库单」创建草稿"></el-alert>',
      '      <div v-else>',
      '        <div class="toolbar" style="margin-bottom:10px;">',
      '          <span style="font-size:15px;font-weight:600;color:var(--yb-ink-1);">入库单 {{ current.inNo }}</span>',
      '          <el-tag :type="statusTag(current.status)">{{ statusLabel(current.status) }}</el-tag>',
      '          <el-tag v-if="isNew" type="warning" size="small">新建未保存</el-tag>',
      '          <span style="color:var(--yb-ink-2);font-size:12px;">创建 {{ fmtTime(current.createTime) || \'-\' }}<template v-if="current.confirmBy"> · 确认人 {{ current.confirmBy }} {{ fmtTime(current.confirmTime) }}</template></span>',
      '        </div>',
      '        <el-alert v-if="!isNew && current.status===0" type="info" :closable="false" show-icon style="margin-bottom:12px;" title="草稿单据只读(不支持修改), 如需调整请作废后重新创建"></el-alert>',
      '        <el-form :model="current" :disabled="!editable" label-width="100px" size="default">',
      '          <el-row :gutter="12">',
      '            <el-col :span="6"><el-form-item label="入库类型"><el-select v-model="current.inType" style="width:100%"><el-option v-for="t in inTypes" :key="t.v" :label="t.v + \'-\' + t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="目标药库" required><el-select v-model="current.warehouseId" style="width:100%" placeholder="必选"><el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="供应商"><el-input v-model="current.supplier" placeholder="供应商名称"></el-input></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="联系方式"><el-input v-model="current.supplierContact" placeholder="电话/联系人"></el-input></el-form-item></el-col>',
      '            <el-col :span="24"><el-form-item label="备注"><el-input v-model="current.remark" type="textarea" :rows="2"></el-input></el-form-item></el-col>',
      '          </el-row>',
      '        </el-form>',
      '        <el-divider content-position="left">入库明细({{ currentItems.length }}条)</el-divider>',
      '        <el-table :data="currentItems" border size="small">',
      '          <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '          <el-table-column label="药品名称" min-width="170"><template #default="s">',
      '            <el-button v-if="editable" link type="primary" @click="openDrugDlg(s.$index)">{{ s.row.drugName || \'点击选择药品\' }}</el-button>',
      '            <span v-else>{{ s.row.drugName }}</span>',
      '          </template></el-table-column>',
      '          <el-table-column prop="drugCode" label="编码" width="110" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="批次号" width="130"><template #default="s"><el-input v-if="editable" v-model="s.row.batchNo" size="small" placeholder="批号"></el-input><span v-else>{{ s.row.batchNo }}</span></template></el-table-column>',
      '          <el-table-column prop="manufacturer" label="生产厂家" width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="数量" width="120"><template #default="s"><el-input-number v-if="editable" v-model="s.row.qty" :min="0" :precision="2" size="small" controls-position="right" style="width:100px"></el-input-number><span v-else>{{ s.row.qty }}</span></template></el-table-column>',
      '          <el-table-column label="进价" width="130"><template #default="s"><el-input-number v-if="editable" v-model="s.row.costPrice" :min="0" :precision="4" size="small" controls-position="right" style="width:110px"></el-input-number><span v-else>{{ s.row.costPrice }}</span></template></el-table-column>',
      '          <el-table-column label="零售价" width="130"><template #default="s"><el-input-number v-if="editable" v-model="s.row.retailPrice" :min="0" :precision="4" size="small" controls-position="right" style="width:110px"></el-input-number><span v-else>{{ s.row.retailPrice }}</span></template></el-table-column>',
      '          <el-table-column label="生产日期" width="140"><template #default="s"><el-date-picker v-if="editable" v-model="s.row.prodDate" type="date" value-format="YYYY-MM-DD" size="small" placeholder="选择日期" style="width:120px"></el-date-picker><span v-else>{{ s.row.prodDate }}</span></template></el-table-column>',
      '          <el-table-column label="有效期" width="140"><template #default="s"><el-date-picker v-if="editable" v-model="s.row.expDate" type="date" value-format="YYYY-MM-DD" size="small" placeholder="选择日期" style="width:120px"></el-date-picker><span v-else>{{ s.row.expDate }}</span></template></el-table-column>',
      '          <el-table-column label="小计" width="90" align="right"><template #default="s">{{ lineAmount(s.row) }}</template></el-table-column>',
      '          <el-table-column v-if="editable" label="操作" width="60"><template #default="s"><el-button link type="danger" @click="removeItem(s.$index)">删除</el-button></template></el-table-column>',
      '        </el-table>',
      '        <div class="toolbar" style="margin-top:12px;">',
      '          <el-button v-if="editable" @click="addItem">添加药品行</el-button>',
      '          <span style="flex:1;"></span>',
      '          <span style="color:var(--yb-ink-2);font-size:14px;">合计金额 <b style="color:var(--yb-danger);">￥{{ itemsTotal }}</b></span>',
      '          <el-button v-if="editable && lead" :loading="saving" @click="saveDraft">保存草稿</el-button>',
      '          <el-button v-if="lead && (editable || current.status===0)" type="primary" :loading="saving" @click="confirmBill">确认入库</el-button>',
      '          <el-button v-if="lead && !editable && current.status===0" type="danger" @click="voidBill">作废</el-button>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ---- 药品选择器 ---- */
      '  <el-dialog v-model="drugDlg" title="选择药品(机构开展药品目录)" width="860px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-input v-model="drugKeyword" placeholder="药品名称/编码/拼音简码" clearable style="width:280px" @keyup.enter="searchDrugs"></el-input>',
      '      <el-button type="primary" @click="searchDrugs">查询</el-button>',
      '      <span style="color:var(--yb-warning);font-size:12px;">{{ drugScopeHint }}</span>',
      '      <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ drugTotal }} 条</span>',
      '    </div>',
      '    <el-table :data="drugList" v-loading="drugLoading" border size="small" height="320">',
      '      <el-table-column prop="drugCode" label="药品编码" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="genericName" label="通用名" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="tradeName" label="商品名" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="dosformName" label="剂型" width="80"></el-table-column>',
      '      <el-table-column prop="manufacturer" label="生产厂家" min-width="140" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="minUnit" label="单位" width="60"></el-table-column>',
      '      <el-table-column label="零售价" width="80" align="right"><template #default="s">{{ s.row.retailPrice }}</template></el-table-column>',
      '      <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="pickDrug(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="drugTotal" :page-size="drugSize" :current-page="drugPage" @current-change="onDrugPage"></el-pagination>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 出库管理(左列表 + 右详情/编辑, 批次选择器) =================
   * 与入库同构, 差异: 明细从库存批次选取(锁定批号/价格), 小计=数量×零售价;
   * 确认出库按批次乐观锁扣减(不足报错), 作废仅草稿态。 */
  HIS.views.StockOutManage = {
    mixins: [HIS.whScopeMixin],
    data: function () {
      return {
        lead: HIS.isLead(),
        /* 左侧出库单列表 */
        loading: false, outList: [], outTotal: 0, outPage: 1, outSize: 20,
        filterStatus: null, dateRange: [],
        /* 右侧当前单据 */
        current: null, currentItems: [], isNew: false, detailLoading: false,
        saving: false,
        outTypes: OUT_TYPES,
        /* 库存批次选择器 */
        batchDlg: false, batchKeyword: '', batchList: [], batchLoading: false,
        batchTotal: 0, batchPage: 1, batchSize: 20, pendingIdx: null
      };
    },
    computed: {
      editable: function () { return this.isNew; },
      /* 出库合计 = Σ 数量×零售价(零售价空则×进价, 与后端 calcOutAmount 同口径) */
      itemsTotal: function () {
        var s = 0;
        for (var i = 0; i < this.currentItems.length; i++) {
          var it = this.currentItems[i];
          var p = (it.retailPrice !== null && it.retailPrice !== undefined && it.retailPrice !== '') ? Number(it.retailPrice) : Number(it.costPrice);
          s += (Number(it.qty) || 0) * (p || 0);
        }
        return s.toFixed(2);
      }
    },
    created: function () { var vm = this; this.loadWarehouseDefs().then(function () { vm.load(); }); },
    methods: {
      /* ---- 左侧列表 ---- */
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/stock/out/page?page=' + vm.outPage + '&size=' + vm.outSize;
        if (vm.warehouseId) { q += '&warehouseId=' + vm.warehouseId; }
        if (vm.filterStatus !== null && vm.filterStatus !== '') { q += '&status=' + vm.filterStatus; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        HIS.get(q).then(function (d) {
          vm.outList = (d && d.records) || [];
          vm.outTotal = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.outPage = 1; this.load(); },
      onOutPage: function (p) { this.outPage = p; this.load(); },
      onOutSize: function (s) { this.outSize = s; this.outPage = 1; this.load(); },
      outSeqNo: function (i) { return (this.outPage - 1) * this.outSize + i + 1; },
      whName: function (id) { return whNameById(this.warehouseDefs, id); },
      /* 建单默认药库: 优先工具栏过滤药库, 其次唯一启用库 */
      prePickWarehouse: function () {
        if (this.warehouseId) { return this.warehouseId; }
        return this.warehouseDefs.length === 1 ? this.warehouseDefs[0].id : null;
      },
      onSelectOut: function (row) {
        if (!row) { return; }
        this.loadDetail(row.id);
      },
      loadDetail: function (id) {
        var vm = this; vm.detailLoading = true;
        HIS.get('/api/his/stock/out/' + id).then(function (d) {
          vm.current = (d && d.main) || null;
          vm.currentItems = (d && d.items) || [];
          vm.isNew = false;
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      /* ---- 新建/编辑 ---- */
      createNew: function () {
        this.current = { id: null, outNo: '(保存后生成)', warehouseId: this.prePickWarehouse(), outType: 2, refId: null, refNo: '', remark: '', status: 0, totalAmount: 0 };
        this.currentItems = [];
        this.isNew = true;
      },
      emptyItem: function () {
        return { drugStockId: null, drugCatalogId: null, drugCode: '', drugName: '', spec: '', batchNo: '', qty: null, costPrice: null, retailPrice: null, expDate: '', stockQty: null };
      },
      addItem: function () {
        this.currentItems.push(this.emptyItem());
        this.openBatchDlg(this.currentItems.length - 1);
      },
      removeItem: function (i) { this.currentItems.splice(i, 1); },
      lineAmount: function (it) {
        var p = (it.retailPrice !== null && it.retailPrice !== undefined && it.retailPrice !== '') ? Number(it.retailPrice) : Number(it.costPrice);
        return ((Number(it.qty) || 0) * (p || 0)).toFixed(2);
      },
      /* ---- 库存批次选择器(从来源药库选可用批次, 锁定批号与价格) ---- */
      openBatchDlg: function (idx) {
        if (!this.current || !this.current.warehouseId) {
          ElementPlus.ElMessage.warning('请先选择来源药库, 再选择库存批次');
          return;
        }
        this.pendingIdx = idx;
        this.batchKeyword = ''; this.batchPage = 1; this.batchList = [];
        this.batchDlg = true;
        this.loadBatches();
      },
      loadBatches: function () {
        var vm = this; vm.batchLoading = true;
        var q = '/api/his/stock/page?page=' + vm.batchPage + '&size=' + vm.batchSize;
        if (vm.current && vm.current.warehouseId) { q += '&warehouseId=' + vm.current.warehouseId; }
        if (vm.batchKeyword) { q += '&keyword=' + encodeURIComponent(vm.batchKeyword); }
        HIS.get(q).then(function (d) {
          /* 仅列可出库批次: 未停用且有库存 */
          vm.batchList = ((d && d.records) || []).filter(function (r) { return r.status === 1 && Number(r.qty) > 0; });
          vm.batchTotal = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.batchLoading = false; });
      },
      searchBatches: function () { this.batchPage = 1; this.loadBatches(); },
      onBatchPage: function (p) { this.batchPage = p; this.loadBatches(); },
      /* 选中批次: 回填批次ID(drugStockId)+药品信息+批号+价格+效期, 并记录可出库存量 */
      pickBatch: function (b) {
        var it = this.currentItems[this.pendingIdx];
        if (!it) { this.batchDlg = false; return; }
        it.drugStockId = b.id;
        it.drugCatalogId = b.drugCatalogId;
        it.drugCode = b.drugCode;
        it.drugName = b.drugName;
        it.spec = b.spec || '';
        it.batchNo = b.batchNo || '';
        it.costPrice = b.costPrice;
        it.retailPrice = b.retailPrice;
        it.expDate = b.expDate ? String(b.expDate).slice(0, 10) : '';
        it.stockQty = b.qty;
        this.batchDlg = false;
      },
      /* ---- 校验/保存草稿/确认/作废 ---- */
      validate: function () {
        var vm = this;
        if (!vm.current.warehouseId) { ElementPlus.ElMessage.warning('请先选择来源药库'); return false; }
        if (!vm.currentItems.length) { ElementPlus.ElMessage.warning('请至少添加一条出库明细'); return false; }
        for (var i = 0; i < vm.currentItems.length; i++) {
          var it = vm.currentItems[i];
          if (!it.drugStockId) {
            ElementPlus.ElMessage.warning('第' + (i + 1) + '行请先选择库存批次'); return false;
          }
          if (!(Number(it.qty) > 0)) {
            ElementPlus.ElMessage.warning('第' + (i + 1) + '行数量必须大于0'); return false;
          }
          if (it.stockQty !== null && it.stockQty !== undefined && Number(it.qty) > Number(it.stockQty)) {
            ElementPlus.ElMessage.warning('第' + (i + 1) + '行数量超过该批次库存(' + it.stockQty + '), 可分批出库或改用FIFO'); return false;
          }
        }
        return true;
      },
      buildPayload: function () {
        var vm = this;
        return {
          warehouseId: vm.current.warehouseId,
          outType: vm.current.outType,
          refNo: vm.current.refNo,
          remark: vm.current.remark,
          /* 指定批次出库: 仅传批次ID+数量, 药品/批号/价格由后端按库存带出(以库存为准防篡改) */
          items: vm.currentItems.map(function (it) {
            return { drugStockId: it.drugStockId, qty: it.qty };
          })
        };
      },
      saveDraft: function () {
        var vm = this;
        if (!vm.validate()) { return; }
        vm.saving = true;
        HIS.post('/api/his/stock/out', vm.buildPayload()).then(function (m) {
          HIS.notifySuccess('出库单已保存草稿: ' + m.outNo);
          vm.load();
          vm.loadDetail(m.id);
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      confirmBill: function () {
        var vm = this;
        if (vm.isNew) {
          if (!vm.validate()) { return; }
          ElementPlus.ElMessageBox.confirm('将保存出库单并立即确认出库(扣减库存), 库存不足将失败。是否继续？', '确认出库', { type: 'warning' })
            .then(function () {
              vm.saving = true;
              return HIS.post('/api/his/stock/out', vm.buildPayload()).then(function (m) {
                return HIS.post('/api/his/stock/out/' + m.id + '/confirm').then(function () {
                  HIS.notifySuccess('已确认出库: ' + m.outNo + ' 金额￥' + vm.itemsTotal);
                  vm.load(); vm.loadDetail(m.id);
                });
              });
            })
            .catch(function (e) { if (e && e.message) { HIS.notifyError(e); } })
            .finally(function () { vm.saving = false; });
        } else {
          ElementPlus.ElMessageBox.confirm('确认出库将按批次扣减库存(乐观锁), 不可作废。是否继续？', '确认出库', { type: 'warning' })
            .then(function () {
              vm.saving = true;
              return HIS.post('/api/his/stock/out/' + vm.current.id + '/confirm').then(function () {
                HIS.notifySuccess('已确认出库: ' + vm.current.outNo);
                vm.load(); vm.loadDetail(vm.current.id);
              });
            })
            .catch(function (e) { if (e && e.message) { HIS.notifyError(e); } })
            .finally(function () { vm.saving = false; });
        }
      },
      voidBill: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('作废后不可恢复, 如需调整请新建出库单。确认作废 ' + vm.current.outNo + ' ？', '作废出库单', { type: 'warning' })
          .then(function () {
            HIS.del('/api/his/stock/out/' + vm.current.id).then(function () {
              HIS.notifySuccess('已作废: ' + vm.current.outNo);
              vm.current = null; vm.currentItems = [];
              vm.load();
            }).catch(HIS.notifyError);
          })
          .catch(function () { });
      },
      outTypeLabel: outTypeLabel,
      statusLabel: statusLabel,
      statusTag: statusTag,
      money2: money2,
      fmtTime: fmtTime,
      expStateOf: expState
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">出库管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(报损/盘亏/调拨出 · 按批次扣减库存)</span></div>',
      '  <div class="dept-split">',
      /* ---- 左侧: 出库单列表 ---- */
      '    <div style="width:400px; flex:none; border-right:1px solid var(--yb-border-light); padding-right:16px; display:flex; flex-direction:column; overflow:hidden;">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-select v-model="filterStatus" placeholder="全部状态" clearable size="small" style="width:110px" @change="search">',
      '          <el-option label="草稿" :value="0"></el-option><el-option label="已确认" :value="1"></el-option><el-option label="已作废" :value="2"></el-option>',
      '        </el-select>',
      '        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" size="small" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:250px" @change="search"></el-date-picker>',
      '      </div>',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <span style="font-weight:600;color:var(--yb-ink-1);font-size:13px;">当前药库</span>',
      '        <el-select v-model="warehouseId" size="small" style="width:150px" :disabled="whSingle" @change="search">',
      '          <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '        </el-select>',
      '        <el-tag v-if="currentWarehouse" size="small">{{ currentWhLabel }}</el-tag>',
      '        <el-tag v-else size="small" type="warning">无可操作药库</el-tag>',
      '        <el-button v-if="lead" type="primary" size="small" @click="createNew">新建出库单</el-button>',
      '        <el-button size="small" @click="load">刷新</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ outTotal }} 单</span>',
      '      </div>',
      '      <el-table :data="outList" v-loading="loading" highlight-current-row size="small" border style="width:100%;" @current-change="onSelectOut">',
      '        <el-table-column type="index" label="序号" width="55" :index="outSeqNo"></el-table-column>',
      '        <el-table-column prop="outNo" label="出库单号" width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="药库" width="95" show-overflow-tooltip><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '        <el-table-column label="类型" width="90"><template #default="s">{{ outTypeLabel(s.row.outType) }}</template></el-table-column>',
      '        <el-table-column prop="refNo" label="关联单据" width="110" show-overflow-tooltip><template #default="s">{{ s.row.refNo || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="总金额" width="85" align="right"><template #default="s">{{ money2(s.row.totalAmount) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="75"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="outTotal" :page-size="outSize" :current-page="outPage" @current-change="onOutPage"></el-pagination>',
      '    </div>',
      /* ---- 右侧: 详情/编辑 ---- */
      '    <div v-loading="detailLoading" style="flex:1; min-width:0; padding-left:16px; overflow:auto;">',
      '      <el-alert v-if="!current" type="info" :closable="false" show-icon title="请从左侧选择出库单, 或点击「新建出库单」创建草稿"></el-alert>',
      '      <div v-else>',
      '        <div class="toolbar" style="margin-bottom:10px;">',
      '          <span style="font-size:15px;font-weight:600;color:var(--yb-ink-1);">出库单 {{ current.outNo }}</span>',
      '          <el-tag :type="statusTag(current.status)">{{ statusLabel(current.status) }}</el-tag>',
      '          <el-tag v-if="isNew" type="warning" size="small">新建未保存</el-tag>',
      '          <span style="color:var(--yb-ink-2);font-size:12px;">创建 {{ fmtTime(current.createTime) || \'-\' }}<template v-if="current.confirmBy"> · 确认人 {{ current.confirmBy }} {{ fmtTime(current.confirmTime) }}</template></span>',
      '        </div>',
      '        <el-alert v-if="!isNew && current.status===0" type="info" :closable="false" show-icon style="margin-bottom:12px;" title="草稿单据只读(不支持修改), 如需调整请作废后重新创建"></el-alert>',
      '        <el-form :model="current" :disabled="!editable" label-width="100px" size="default">',
      '          <el-row :gutter="12">',
      '            <el-col :span="6"><el-form-item label="出库类型"><el-select v-model="current.outType" style="width:100%"><el-option v-for="t in outTypes" :key="t.v" :label="t.v + \'-\' + t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="来源药库" required><el-select v-model="current.warehouseId" style="width:100%" placeholder="必选"><el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="关联单据号"><el-input v-model="current.refNo" placeholder="处方号/调拨单号等(可选)"></el-input></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="备注"><el-input v-model="current.remark" placeholder="报损原因/调拨事由等"></el-input></el-form-item></el-col>',
      '          </el-row>',
      '        </el-form>',
      '        <el-divider content-position="left">出库明细({{ currentItems.length }}条 · 批次与价格取自库存, 不可改)</el-divider>',
      '        <el-table :data="currentItems" border size="small">',
      '          <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '          <el-table-column label="药品名称" min-width="170"><template #default="s">',
      '            <el-button v-if="editable" link type="primary" @click="openBatchDlg(s.$index)">{{ s.row.drugName || \'点击选择库存批次\' }}</el-button>',
      '            <span v-else>{{ s.row.drugName }}</span>',
      '          </template></el-table-column>',
      '          <el-table-column prop="drugCode" label="编码" width="110" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="batchNo" label="批次号" width="120" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="可出库存" width="90" align="right"><template #default="s"><span v-if="editable && s.row.stockQty!==null" style="color:var(--yb-ink-2);">{{ s.row.stockQty }}</span><span v-else>-</span></template></el-table-column>',
      '          <el-table-column label="数量" width="120"><template #default="s"><el-input-number v-if="editable" v-model="s.row.qty" :min="0" :precision="2" size="small" controls-position="right" style="width:100px"></el-input-number><span v-else>{{ s.row.qty }}</span></template></el-table-column>',
      '          <el-table-column label="进价" width="90" align="right"><template #default="s">{{ s.row.costPrice }}</template></el-table-column>',
      '          <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ s.row.retailPrice }}</template></el-table-column>',
      '          <el-table-column label="有效期" width="105"><template #default="s">{{ s.row.expDate || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="小计" width="90" align="right"><template #default="s">{{ lineAmount(s.row) }}</template></el-table-column>',
      '          <el-table-column v-if="editable" label="操作" width="60"><template #default="s"><el-button link type="danger" @click="removeItem(s.$index)">删除</el-button></template></el-table-column>',
      '        </el-table>',
      '        <div class="toolbar" style="margin-top:12px;">',
      '          <el-button v-if="editable" @click="addItem">添加库存批次</el-button>',
      '          <span style="flex:1;"></span>',
      '          <span style="color:var(--yb-ink-2);font-size:14px;">合计金额 <b style="color:var(--yb-danger);">￥{{ itemsTotal }}</b></span>',
      '          <el-button v-if="editable && lead" :loading="saving" @click="saveDraft">保存草稿</el-button>',
      '          <el-button v-if="lead && (editable || current.status===0)" type="primary" :loading="saving" @click="confirmBill">确认出库</el-button>',
      '          <el-button v-if="lead && !editable && current.status===0" type="danger" @click="voidBill">作废</el-button>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ---- 库存批次选择器 ---- */
      '  <el-dialog v-model="batchDlg" title="选择库存批次(可出库批次)" width="900px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-input v-model="batchKeyword" placeholder="药品名称/编码/批号/厂家" clearable style="width:280px" @keyup.enter="searchBatches"></el-input>',
      '      <el-button type="primary" @click="searchBatches">查询</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:12px;">仅显示该药库未停用且有库存的批次</span>',
      '    </div>',
      '    <el-table :data="batchList" v-loading="batchLoading" border size="small" height="320">',
      '      <el-table-column prop="drugCode" label="药品编码" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="drugName" label="药品名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="batchNo" label="批次号" width="115" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="库存量" width="85" align="right"><template #default="s"><b>{{ s.row.qty }}</b></template></el-table-column>',
      '      <el-table-column label="进价" width="90" align="right"><template #default="s">{{ s.row.costPrice }}</template></el-table-column>',
      '      <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ s.row.retailPrice }}</template></el-table-column>',
      '      <el-table-column label="有效期" width="105"><template #default="s">',
      '        <span v-if="expStateOf(s.row.expDate)===\'expired\'" style="color:var(--yb-danger);font-weight:700;">{{ String(s.row.expDate||\'\').slice(0,10) }} 过期</span>',
      '        <span v-else>{{ s.row.expDate ? String(s.row.expDate).slice(0,10) : \'-\' }}</span>',
      '      </template></el-table-column>',
      '      <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="pickBatch(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="batchTotal" :page-size="batchSize" :current-page="batchPage" @current-change="onBatchPage"></el-pagination>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 药库定义管理(机构级多药库: 编码唯一/停用守卫) =================
   * 写(新增/编辑/启停)仅牵头管理员(后端 requireLeadWrite 403 兜底); 列表含停用(?includeDisabled=true)。
   * 停用后该库不再出现在出入库/盘点下拉; 库内存在有量库存时后端拒绝停用。 */
  HIS.views.WarehouseDef = {
    data: function () {
      return {
        lead: HIS.isLead(),
        loading: false, list: [],
        dlg: false, saving: false, togglingId: null,
        whTypes: WH_TYPES, deptDefs: [],
        form: { id: null, code: '', name: '', warehouseType: 'MIXED', location: '', manager: '', sortNo: 0, orgId: null, status: 1, deptId: null }
      };
    },
    created: function () { this.load(); this.loadDepts(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/stock/warehouse-def?includeDisabled=true').then(function (d) { vm.list = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      typeLabel: whTypeLabel,
      /* 归属科室下拉(本机构启用科室; 药库与科室一一对应, 后端校验重复绑定) */
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (list) { vm.deptDefs = list || []; }).catch(HIS.notifyError);
      },
      deptName: function (id) {
        if (id === null || id === undefined || id === '') { return '-'; }
        for (var i = 0; i < this.deptDefs.length; i++) { if (this.deptDefs[i].id === id) { return this.deptDefs[i].deptName; } }
        return '科室#' + id;
      },
      openCreate: function () {
        this.form = { id: null, code: '', name: '', warehouseType: 'MIXED', location: '', manager: '', sortNo: 0, orgId: null, status: 1, deptId: null };
        this.dlg = true;
      },
      openEdit: function (row) {
        this.form = { id: row.id, code: row.code, name: row.name, warehouseType: row.warehouseType || 'MIXED', location: row.location || '', manager: row.manager || '', sortNo: row.sortNo || 0, orgId: row.orgId, status: row.status, deptId: (row.deptId === null || row.deptId === undefined) ? null : row.deptId };
        this.dlg = true;
      },
      save: function () {
        var vm = this;
        if (!vm.form.code || !String(vm.form.code).trim()) { ElementPlus.ElMessage.warning('请填写药库编码(如 WH-WEST-01)'); return; }
        if (!vm.form.name || !String(vm.form.name).trim()) { ElementPlus.ElMessage.warning('请填写药库名称'); return; }
        vm.saving = true;
        HIS.post('/api/his/stock/warehouse-def', vm.form).then(function () {
          HIS.notifySuccess(vm.form.id ? '药库已更新: ' + vm.form.name : '药库已新增: ' + vm.form.name);
          vm.dlg = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* 启停: 停用需二次确认(后端校验库内无有量库存) */
      onToggle: function (row, val) {
        var vm = this;
        var enable = !!val;
        var doIt = function () {
          vm.togglingId = row.id;
          HIS.post('/api/his/stock/warehouse-def/' + row.id + '/toggle?enabled=' + enable).then(function () {
            HIS.notifySuccess((enable ? '已启用: ' : '已停用: ') + row.name);
            vm.load();
          }).catch(HIS.notifyError).finally(function () { vm.togglingId = null; });
        };
        if (!enable) {
          ElementPlus.ElMessageBox.confirm('停用后该药库不再出现在出入库/盘点下拉; 库内存在有量库存时后端将拒绝停用。确认停用 [' + row.name + '] ？', '停用药库', { type: 'warning' }).then(doIt).catch(function () { });
        } else {
          doIt();
        }
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药库管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(机构级多药库: 西药库/中药库/综合库 · 库内库存独立记账)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新增药库</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ list.length }} 个药库(含停用)</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="code" label="编码" width="140" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="类型" width="90"><template #default="s"><el-tag size="small" :type="s.row.warehouseType===\'WESTERN\'?\'primary\':(s.row.warehouseType===\'TCM\'?\'warning\':\'info\')">{{ typeLabel(s.row.warehouseType) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="归属科室" width="130" show-overflow-tooltip><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column prop="location" label="位置" width="140" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="manager" label="负责人" width="100" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="sortNo" label="排序" width="70" align="right"></el-table-column>',
      '    <el-table-column label="状态" width="160"><template #default="s">',
      '      <el-switch :model-value="s.row.status===1" :disabled="!lead || togglingId===s.row.id" @change="onToggle(s.row, $event)"></el-switch>',
      '      <span style="margin-left:8px;font-size:12px;" :style="{color: s.row.status===1 ? \'var(--yb-success)\' : \'var(--yb-ink-2)\'}">{{ s.row.status===1 ? \'启用\' : \'停用\' }}</span>',
      '    </template></el-table-column>',
      '    <el-table-column v-if="lead" label="操作" width="90"><template #default="s"><el-button link type="primary" @click="openEdit(s.row)">编辑</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="form.id ? \'编辑药库\' : \'新增药库\'" width="520px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="编码" required><el-input v-model="form.code" placeholder="如 WH-WEST-01(机构内唯一)"></el-input></el-form-item>',
      '      <el-form-item label="名称" required><el-input v-model="form.name" placeholder="如 西药库"></el-input></el-form-item>',
      '      <el-form-item label="类型"><el-select v-model="form.warehouseType" style="width:100%"><el-option v-for="t in whTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="归属科室"><el-select v-model="form.deptId" clearable placeholder="选择归属科室(与科室一一对应)" style="width:100%"><el-option v-for="d in deptDefs" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="位置"><el-input v-model="form.location" placeholder="如 1号楼1层"></el-input></el-form-item>',
      '      <el-form-item label="负责人"><el-input v-model="form.manager" placeholder="药库负责人"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input-number v-model="form.sortNo" :min="0" controls-position="right" style="width:140px"></el-input-number></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 药品目录(机构开展目录只读浏览, warehouseType 过滤中药属性) ================= */
  HIS.views.DrugCatalogView = {
    data: function () {
      return { loading: false, list: [], total: 0, page: 1, size: 20, type: '', keyword: '' };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/stock/drug-catalog?page=' + vm.page + '&size=' + vm.size;
        if (vm.type) { q += '&warehouseType=' + vm.type; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      money4: money4
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药品目录 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(本机构已开展药品 · 类型过滤: 西药排除中药/中成药)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="type" placeholder="全部类型" clearable style="width:150px" @change="search">',
      '      <el-option label="全部" value=""></el-option><el-option label="西药" value="WESTERN"></el-option><el-option label="中药" value="TCM"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="通用名/商品名/编码/拼音简码" clearable style="width:260px" @keyup.enter="search" @clear="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="drugCode" label="药品编码" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="genericName" label="通用名" min-width="170" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="tradeName" label="商品名" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="majorClass" label="大类" width="100" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="dosformName" label="剂型" width="90" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="manufacturer" label="生产厂家" min-width="160" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ money4(s.row.retailPrice) }}</template></el-table-column>',
      '    <el-table-column label="医保等级" width="90"><template #default="s">{{ s.row.chrgitmLvName || \'-\' }}</template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* ================= 盘点管理(按药库整库盘点) =================
   * 流程: 新建(快照该库有量批次) → 逐行录实盘(失焦自动PUT保存) → 确认(差异生成盘盈入库/盘亏出库) / 作废(仅进行中)。
   * 写操作均需牵头管理员(后端 requireLeadWrite), 非牵头仅可见[查看详情]。 */
  HIS.views.StockCheck = {
    mixins: [HIS.whScopeMixin],
    data: function () {
      return {
        lead: HIS.isLead(),
        loading: false, list: [], total: 0, page: 1, size: 20,
        dateRange: [],
        /* 新建盘点 */
        createDlg: false, createWarehouseId: null, creating: false,
        /* 录入/查看弹窗 */
        dlg: false, readonly: false, dlgLoading: false, savingAll: false,
        cur: { main: null, items: [] }, saveTip: ''
      };
    },
    created: function () { var vm = this; this.loadWarehouseDefs().then(function () { vm.load(); }); },
    methods: {
      whName: function (id) { return whNameById(this.warehouseDefs, id); },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/stock/check/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.warehouseId) { q += '&warehouseId=' + vm.warehouseId; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      statusLabel: checkStatusLabel,
      statusTag: checkStatusTag,
      money2: money2,
      fmtDate: fmtDate,
      fmtTime: fmtTime,
      /* ---- 新建盘点(快照药库有量批次) ---- */
      openCreate: function () {
        this.createWarehouseId = this.warehouseId ? this.warehouseId : (this.warehouseDefs.length === 1 ? this.warehouseDefs[0].id : null);
        this.createDlg = true;
      },
      createCheck: function () {
        var vm = this;
        if (!vm.createWarehouseId) { ElementPlus.ElMessage.warning('请选择要盘点的药库'); return; }
        vm.creating = true;
        HIS.post('/api/his/stock/check?warehouseId=' + vm.createWarehouseId).then(function (m) {
          HIS.notifySuccess('盘点单已创建: ' + (m && m.checkNo));
          vm.createDlg = false;
          vm.load();
          if (m && m.id) { vm.openEntry(m.id, false); }
        }).catch(HIS.notifyError).finally(function () { vm.creating = false; });
      },
      /* ---- 录入/查看弹窗: 已完成/已作废强制只读 ---- */
      openEntry: function (id, readonly) {
        var vm = this;
        vm.dlg = true; vm.readonly = !!readonly; vm.saveTip = '';
        vm.cur = { main: null, items: [] };
        vm.dlgLoading = true;
        HIS.get('/api/his/stock/check/' + id).then(function (d) {
          vm.cur = { main: (d && d.main) || null, items: (d && d.items) || [] };
          vm.readonly = !!(readonly || (vm.cur.main && vm.cur.main.status !== 0));
        }).catch(HIS.notifyError).finally(function () { vm.dlgLoading = false; });
      },
      /* 逐行失焦保存(PUT 幂等): 成功后本地回算差异 */
      onActualChange: function (row) {
        var vm = this;
        if (vm.readonly || !vm.cur.main) { return; }
        if (row.actualQty === null || row.actualQty === undefined || row.actualQty === '') { return; }
        HIS.put('/api/his/stock/check/' + vm.cur.main.id + '/item/' + row.id + '?actualQty=' + row.actualQty).then(function () {
          row.diffQty = Number(row.actualQty) - Number(row.systemQty || 0);
          vm.saveTip = '已保存: ' + row.drugName + ' 实盘 ' + row.actualQty;
        }).catch(HIS.notifyError);
      },
      /* 保存(全量重提已填行, 幂等) */
      saveAll: function () {
        var vm = this;
        if (vm.readonly || !vm.cur.main) { return; }
        var filled = vm.cur.items.filter(function (it) { return it.actualQty !== null && it.actualQty !== undefined && it.actualQty !== ''; });
        if (!filled.length) { ElementPlus.ElMessage.warning('请先录入实盘数量'); return; }
        vm.savingAll = true;
        var chain = Promise.resolve();
        filled.forEach(function (it) {
          chain = chain.then(function () {
            return HIS.put('/api/his/stock/check/' + vm.cur.main.id + '/item/' + it.id + '?actualQty=' + it.actualQty).then(function () {
              it.diffQty = Number(it.actualQty) - Number(it.systemQty || 0);
            });
          });
        });
        chain.then(function () {
          vm.saveTip = '已保存 ' + filled.length + ' 行实盘数量';
          HIS.notifySuccess('实盘数量已保存 ' + filled.length + ' 行');
        }).catch(HIS.notifyError).finally(function () { vm.savingAll = false; });
      },
      rowDiff: function (r) {
        if (r.actualQty === null || r.actualQty === undefined || r.actualQty === '') { return null; }
        return Number(r.actualQty) - Number(r.systemQty || 0);
      },
      diffText: function (r) {
        var d = this.rowDiff(r);
        if (d === null) { return '--'; }
        return (d > 0 ? '+' : '') + d;
      },
      diffColor: function (r) {
        var d = this.rowDiff(r);
        if (d === null || d === 0) { return 'var(--yb-ink-2)'; }
        return d > 0 ? 'var(--yb-success)' : 'var(--yb-danger)';
      },
      /* 确认盘点(popconfirm 二次确认后): 差异生成盘盈入库/盘亏出库并自动确认 */
      confirmCheck: function (row) {
        var vm = this;
        HIS.post('/api/his/stock/check/' + row.id + '/confirm').then(function () {
          HIS.notifySuccess('盘点已确认: ' + row.checkNo + '(差异已生成盘盈/盘亏单据)');
          vm.load();
          if (vm.dlg && vm.cur.main && vm.cur.main.id === row.id) { vm.openEntry(row.id, true); }
        }).catch(HIS.notifyError);
      },
      voidCheck: function (row) {
        var vm = this;
        HIS.del('/api/his/stock/check/' + row.id).then(function () {
          HIS.notifySuccess('盘点单已作废: ' + row.checkNo);
          if (vm.dlg && vm.cur.main && vm.cur.main.id === row.id) { vm.dlg = false; }
          vm.load();
        }).catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">盘点管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按药库整库盘点 · 确认后差异生成盘盈入库/盘亏出库)</span></div>',
      '  <div class="toolbar">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">当前药库</span>',
      '    <el-select v-model="warehouseId" style="width:170px" :disabled="whSingle" @change="search">',
      '      <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '    </el-select>',
      '    <el-tag v-if="currentWarehouse" size="small">{{ currentWhLabel }}</el-tag>',
      '    <el-tag v-else size="small" type="warning">无可操作药库</el-tag>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="盘点开始日期" end-placeholder="盘点结束日期" style="width:290px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button v-if="lead" @click="openCreate">新建盘点</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 张盘点单</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="checkNo" label="盘点单号" width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="药库" width="120" show-overflow-tooltip><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column label="盘点日期" width="110"><template #default="s">{{ fmtDate(s.row.checkDate) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="90"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="盘盈金额" width="100" align="right"><template #default="s"><span v-if="Number(s.row.profitAmount)>0" style="color:var(--yb-success);font-weight:600;">{{ money2(s.row.profitAmount) }}</span><span v-else>-</span></template></el-table-column>',
      '    <el-table-column label="盘亏金额" width="100" align="right"><template #default="s"><span v-if="Number(s.row.lossAmount)>0" style="color:var(--yb-danger);font-weight:600;">{{ money2(s.row.lossAmount) }}</span><span v-else>-</span></template></el-table-column>',
      '    <el-table-column label="盘点人" width="100" show-overflow-tooltip><template #default="s">{{ s.row.checkBy || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="确认" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.confirmBy ? (s.row.confirmBy + \' \' + fmtTime(s.row.confirmTime)) : \'-\' }}</template></el-table-column>',
      '    <el-table-column label="操作" width="210" fixed="right"><template #default="s">',
      '      <template v-if="lead && s.row.status===0">',
      '        <el-button link type="primary" @click="openEntry(s.row.id, false)">录入实盘</el-button>',
      '        <el-popconfirm title="确认后差异将生成盘盈入库/盘亏出库并自动确认, 不可再修改。是否继续？" width="280" @confirm="confirmCheck(s.row)">',
      '          <template #reference><el-button link type="warning">确认盘点</el-button></template>',
      '        </el-popconfirm>',
      '        <el-popconfirm title="作废后不可恢复, 该盘点记录仅保留痕迹。确认作废？" width="240" @confirm="voidCheck(s.row)">',
      '          <template #reference><el-button link type="danger">作废</el-button></template>',
      '        </el-popconfirm>',
      '      </template>',
      '      <el-button v-if="!lead || s.row.status!==0" link type="primary" @click="openEntry(s.row.id, true)">查看详情</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 新建盘点 ---- */
      '  <el-dialog v-model="createDlg" title="新建盘点" width="460px">',
      '    <el-form label-width="90px">',
      '      <el-form-item label="盘点药库" required>',
      '        <el-select v-model="createWarehouseId" placeholder="选择要盘点的药库" style="width:100%"><el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <el-alert type="info" :closable="false" show-icon title="将快照该药库所有有量批次为盘点明细; 同药库存在进行中的盘点单时后端将拒绝"></el-alert>',
      '    <template #footer><el-button @click="createDlg=false">取消</el-button><el-button type="primary" :loading="creating" @click="createCheck">创建盘点</el-button></template>',
      '  </el-dialog>',
      /* ---- 录入/查看弹窗 ---- */
      '  <el-dialog v-model="dlg" :title="(readonly ? \'盘点详情 - \' : \'盘点录入 - \') + (cur.main ? cur.main.checkNo : \'\')" width="960px" top="5vh">',
      '    <div v-loading="dlgLoading">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">药库 {{ cur.main ? whName(cur.main.warehouseId) : \'-\' }} · 盘点日期 {{ cur.main ? fmtDate(cur.main.checkDate) : \'-\' }}</span>',
      '        <el-tag v-if="cur.main" size="small" :type="statusTag(cur.main.status)">{{ statusLabel(cur.main.status) }}</el-tag>',
      '        <span style="flex:1;"></span>',
      '        <span v-if="saveTip" style="color:var(--yb-success);font-size:12px;">{{ saveTip }}</span>',
      '        <span v-if="!readonly" style="color:var(--yb-ink-2);font-size:12px;">实盘数量每次失焦自动保存</span>',
      '      </div>',
      '      <el-table :data="cur.items" border size="small" height="430">',
      '        <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="170" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批号" width="115" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="系统数量" width="95" align="right"><template #default="s">{{ s.row.systemQty }}</template></el-table-column>',
      '        <el-table-column label="实盘数量" width="150"><template #default="s">',
      '          <el-input-number v-if="!readonly" v-model="s.row.actualQty" :min="0" :precision="2" size="small" controls-position="right" style="width:130px" @change="onActualChange(s.row)"></el-input-number>',
      '          <span v-else style="padding-left:12px;">{{ s.row.actualQty === null || s.row.actualQty === undefined ? \'-\' : s.row.actualQty }}</span>',
      '        </template></el-table-column>',
      '        <el-table-column label="差异" width="95" align="right"><template #default="s"><span :style="{color: diffColor(s.row), fontWeight: rowDiff(s.row) === 0 ? 400 : 700}">{{ diffText(s.row) }}</span></template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer>',
      '      <el-button v-if="!readonly" :loading="savingAll" @click="saveAll">保存</el-button>',
      '      <el-button @click="dlg=false">关闭</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

})();
