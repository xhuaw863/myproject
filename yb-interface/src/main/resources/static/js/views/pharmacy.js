/* 药房工作站: 待发药(调剂发药·库存核对·双签) + 发药记录(查询/导出) + 退药(申请/审批·回补库存) + 药房定义管理(机构级多药房) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 发药记录状态: 0待发药 1已调配 2已发药 3已退药 */
  var DISPENSE_STATUS = [
    { v: 0, l: '待发药', t: 'info' },
    { v: 1, l: '已调配', t: 'warning' },
    { v: 2, l: '已发药', t: 'success' },
    { v: 3, l: '已退药', t: 'warning' }
  ];
  /* 退药状态: 0待审核 1已退药 2已驳回 */
  var RETURN_STATUS = [
    { v: 0, l: '待审核', t: 'warning' },
    { v: 1, l: '已退药', t: 'success' },
    { v: 2, l: '已驳回', t: 'danger' }
  ];
  function findStatus(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i]; } }
    return null;
  }
  function dispLabel(v) { var s = findStatus(DISPENSE_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function dispTag(v) { var s = findStatus(DISPENSE_STATUS, v); return s ? s.t : 'info'; }
  function retLabel(v) { var s = findStatus(RETURN_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function retTag(v) { var s = findStatus(RETURN_STATUS, v); return s ? s.t : 'info'; }
  function money(n) { return (n === null || n === undefined || n === '') ? '0.00' : Number(n).toFixed(2); }
  function today() {
    var d = new Date();
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var day = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + day;
  }
  /* 药房类型: OUTPATIENT-门诊药房 / INPATIENT-住院药房 / TCM-中药房 */
  var PHARMACY_TYPES = [
    { v: 'OUTPATIENT', l: '门诊', t: 'primary' },
    { v: 'INPATIENT', l: '住院', t: 'warning' },
    { v: 'TCM', l: '中药', t: 'success' }
  ];
  function pharmacyTypeLabel(v) {
    for (var i = 0; i < PHARMACY_TYPES.length; i++) { if (PHARMACY_TYPES[i].v === v) { return PHARMACY_TYPES[i].l; } }
    return (v === null || v === undefined || v === '') ? '-' : v;
  }
  function pharmacyTypeTag(v) {
    for (var i = 0; i < PHARMACY_TYPES.length; i++) { if (PHARMACY_TYPES[i].v === v) { return PHARMACY_TYPES[i].t; } }
    return 'info';
  }
  /* 药房列表加载(启用中的药房, 机构首次访问后端自动建默认门诊药房): 供各页药房下拉共用,
   * 组件 data 需声明 pharmacyDefs: [] */
  function loadPharmacyOpts(vm) {
    HIS.get('/api/his/pharmacy/pharmacy-def').then(function (list) {
      vm.pharmacyDefs = list || [];
    }).catch(HIS.notifyError);
  }
  /* pharmacyId 取药房名称(未匹配/历史已停用药房显示 '-') */
  function pharmacyName(defs, id) {
    if (id === null || id === undefined || id === '') { return '-'; }
    for (var i = 0; i < (defs || []).length; i++) { if (defs[i].id === id) { return defs[i].name; } }
    return '-';
  }
  /* LocalDateTime 序列化为 ISO(含T), 展示转空格并截到秒 */
  function fmtTime(v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; }

  /* ================= 待发药(调剂发药) =================
   * 后端 /todo 返回 JdbcTemplate 行(snake_case 键), 加载时归一为 camelCase 供模板直取;
   * /detail 的明细键为 drugName/qty/usageName/freqName(与规格说明略有差异), 同样归一。
   */
  HIS.views.DispenseTodo = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20, keyword: '',
        /* 药房过滤(空=全部药房; 中药房只看中药处方) */
        pharmacyId: '', pharmacyDefs: [],
        /* 发药详情对话框 */
        dispenseVisible: false, detailLoading: false,
        dispenseRx: null, dispenseItems: [], detailOrgId: null,
        checkBy: '', dispenseRemark: '', dispensing: false
      };
    },
    computed: {
      /* 任一明细库存不足则禁发 */
      hasInsufficientStock: function () {
        for (var i = 0; i < this.dispenseItems.length; i++) {
          if (this.dispenseItems[i].stockSufficient === false) { return true; }
        }
        return false;
      }
    },
    created: function () { loadPharmacyOpts(this); this.load(); this.startAutoRefresh(); },
    /* Vue3 销毁钩子(beforeDestroy为Vue2名称, 在Vue3中被忽略): 清理自动刷新定时器防泄漏 */
    beforeUnmount: function () { this.stopAutoRefresh(); },
    methods: {
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/his/pharmacy/todo?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.pharmacyId) { q += '&pharmacyId=' + vm.pharmacyId; }
        HIS.get(q).then(function (d) {
          vm.list = ((d && d.records) || []).map(function (r) {
            return {
              prescriptionId: r.prescriptionId != null ? r.prescriptionId : r.prescription_id,
              rxNo: r.rxNo || r.prescription_no || r.rx_no,
              visitNo: r.visitNo || r.visit_no,
              patientName: r.patientName || r.patient_name,
              doctorName: r.doctorName || r.doctor_name,
              deptName: r.deptName || r.dept_name,
              totalAmount: r.totalAmount != null ? r.totalAmount : r.total_amount,
              prescribeTime: r.prescribeTime || r.prescribe_time,
              itemCount: r.itemCount != null ? r.itemCount : r.item_count
            };
          });
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      openDispense: function (row) {
        var vm = this;
        vm.dispenseVisible = true;
        vm.detailLoading = true;
        vm.dispenseRx = null; vm.dispenseItems = []; vm.detailOrgId = null;
        vm.checkBy = ''; vm.dispenseRemark = '';
        HIS.get('/api/his/pharmacy/detail/' + row.prescriptionId).then(function (d) {
          var p = (d && d.prescription) || {};
          vm.dispenseRx = {
            id: p.id,
            rxNo: p.rxNo || p.prescriptionNo,
            visitNo: p.visitNo,
            patientName: p.patientName,
            doctorName: p.doctorName,
            deptName: p.deptName,
            totalAmount: p.totalAmount,
            createTime: p.createTime
          };
          vm.dispenseItems = ((d && d.items) || []).map(function (it) {
            return {
              id: it.id,
              drugCode: it.drugCode,
              itemName: it.itemName || it.drugName,
              spec: it.spec,
              /* 后端无剂型字段, 以发药单位兜底展示 */
              dosform: it.dosform || it.unit,
              quantity: it.quantity != null ? it.quantity : it.qty,
              price: it.price,
              amount: it.amount,
              usageMethod: it.usageMethod || it.usageName,
              frequency: it.frequency || it.freqName,
              stockQty: it.stockQty,
              stockSufficient: it.stockSufficient
            };
          });
          vm.detailOrgId = (d && d.orgId) || null;
        }).catch(function (e) {
          HIS.notifyError(e);
          vm.dispenseVisible = false;
        }).finally(function () { vm.detailLoading = false; });
      },
      doDispense: function () {
        var vm = this;
        if (!vm.dispenseRx || !vm.dispenseRx.id) { ElementPlus.ElMessage.warning('处方信息未加载'); return; }
        if (vm.hasInsufficientStock) { ElementPlus.ElMessage.warning('存在库存不足的药品, 无法发药'); return; }
        var rx = vm.dispenseRx;
        ElementPlus.ElMessageBox.confirm(
          '确认对处方 ' + rx.rxNo + '（' + rx.patientName + '，金额￥' + money(rx.totalAmount) + '）执行发药？发药将按有效期FIFO扣减药库库存。',
          '发药确认',
          { type: 'warning', confirmButtonText: '确认发药', cancelButtonText: '取消' }
        ).then(function () {
          vm.dispensing = true;
          return HIS.post('/api/his/pharmacy/dispense', {
            prescriptionId: rx.id, orgId: vm.detailOrgId, pharmacyId: vm.pharmacyId || null,
            checkBy: vm.checkBy, remark: vm.dispenseRemark
          });
        }).then(function (d) {
          HIS.notifySuccess('发药成功, 发药单号 ' + ((d && d.dispenseNo) || ''));
          vm.dispenseVisible = false;
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.dispensing = false; });
      },
      startAutoRefresh: function () {
        var vm = this;
        vm.refreshTimer = setInterval(function () { vm.load(true); }, 30 * 1000);
      },
      stopAutoRefresh: function () {
        if (this.refreshTimer) { clearInterval(this.refreshTimer); this.refreshTimer = null; }
      },
      money: money
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">待发药 <span style="font-size:12px;color:#909399;font-weight:normal;">(已开立未发药处方 · 先开先发 · 列表每30秒自动刷新)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable style="width:150px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="患者姓名/处方号" clearable style="width:240px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 张 · 每30秒自动刷新</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="rxNo" label="处方号" width="150"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="120"></el-table-column>',
      '    <el-table-column prop="doctorName" label="医生" width="100"></el-table-column>',
      '    <el-table-column prop="prescribeTime" label="开方时间" width="160"></el-table-column>',
      '    <el-table-column prop="itemCount" label="药品数" width="80" align="center"></el-table-column>',
      '    <el-table-column label="金额" width="100" align="right"><template #default="s">￥{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="100" fixed="right"><template #default="s">',
      '      <el-button type="primary" size="small" @click="openDispense(s.row)">发药</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 调剂发药对话框 ---- */
      '  <el-dialog v-model="dispenseVisible" title="调剂发药" width="950px" top="6vh">',
      '    <div v-loading="detailLoading">',
      '      <el-descriptions v-if="dispenseRx" :column="4" border size="small" style="margin-bottom:12px;">',
      '        <el-descriptions-item label="处方号">{{ dispenseRx.rxNo }}</el-descriptions-item>',
      '        <el-descriptions-item label="患者姓名">{{ dispenseRx.patientName }}</el-descriptions-item>',
      '        <el-descriptions-item label="科室">{{ dispenseRx.deptName || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="医生">{{ dispenseRx.doctorName || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="就诊号">{{ dispenseRx.visitNo || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="开方时间">{{ dispenseRx.createTime || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="总金额"><span style="color:#f56c6c;font-weight:600;">￥{{ money(dispenseRx.totalAmount) }}</span></el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-alert v-if="hasInsufficientStock" type="error" show-icon :closable="false" title="存在库存不足的药品, 无法发药, 请先补货或联系药库" style="margin-bottom:10px;"></el-alert>',
      '      <el-table :data="dispenseItems" border size="small" max-height="360">',
      '        <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '        <el-table-column prop="itemName" label="药品名称" width="170" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="剂型/单位" width="90"><template #default="s">{{ s.row.dosform || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="quantity" label="数量" width="70" align="right"></el-table-column>',
      '        <el-table-column label="单价" width="90" align="right"><template #default="s">{{ money(s.row.price) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="90" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '        <el-table-column label="用法" width="100"><template #default="s">{{ s.row.usageMethod || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="频次" width="80"><template #default="s">{{ s.row.frequency || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="库存" width="80" align="right"><template #default="s">',
      '          <span :style="s.row.stockSufficient===false?\'color:#f56c6c;font-weight:700;\':\'\'">{{ s.row.stockQty }}</span>',
      '        </template></el-table-column>',
      '        <el-table-column label="库存状态" width="90" align="center"><template #default="s">',
      '          <el-tag size="small" :type="s.row.stockSufficient===false?\'danger\':\'success\'">{{ s.row.stockSufficient===false?\'不足\':\'充足\' }}</el-tag>',
      '        </template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer>',
      '      <div style="display:flex;align-items:center;gap:10px;">',
      '        <el-input v-model="checkBy" placeholder="核对药师(双签)" style="width:200px;"></el-input>',
      '        <el-input v-model="dispenseRemark" placeholder="备注(可选)" style="width:300px;"></el-input>',
      '        <span style="flex:1;"></span>',
      '        <el-button @click="dispenseVisible=false">取 消</el-button>',
      '        <el-tooltip content="库存不足, 无法发药" placement="top" :disabled="!hasInsufficientStock">',
      '          <span style="display:inline-block;">',
      '            <el-button type="primary" :loading="dispensing" :disabled="hasInsufficientStock || detailLoading || !dispenseItems.length" @click="doDispense">确认发药</el-button>',
      '          </span>',
      '        </el-tooltip>',
      '      </div>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 调剂发药记录 ================= */
  HIS.views.DispenseRecord = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        filterStatus: null, dateRange: [], keyword: '', exporting: false,
        /* 药房过滤(空=全部药房; 列表/导出同口径) */
        pharmacyId: '', pharmacyDefs: [],
        statusOpts: [{ v: 2, l: '已发药' }, { v: 3, l: '已退药' }]
      };
    },
    created: function () {
      this.dateRange = [today(), today()];
      loadPharmacyOpts(this);
      this.load();
    },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/pharmacy/records?page=' + vm.page + '&size=' + vm.size;
        if (vm.pharmacyId) { q += '&pharmacyId=' + vm.pharmacyId; }
        if (vm.filterStatus !== null && vm.filterStatus !== '' && vm.filterStatus !== undefined) { q += '&status=' + vm.filterStatus; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 导出与列表同药房/日期区间口径(后端导出按机构/药房/日期, 不含状态/关键词) */
      doExport: function () {
        var vm = this; vm.exporting = true;
        var parts = [];
        if (vm.pharmacyId) { parts.push('pharmacyId=' + vm.pharmacyId); }
        if (vm.dateRange && vm.dateRange.length === 2) { parts.push('startDate=' + vm.dateRange[0]); parts.push('endDate=' + vm.dateRange[1]); }
        var q = parts.length ? '?' + parts.join('&') : '';
        HIS.download('/api/his/pharmacy/export' + q, '发药记录.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      /* 跳转退药页并自动带入发药单号(DrugReturn.created 消费后清除) */
      goReturn: function (row) {
        HIS.pendingReturnDispenseNo = row.dispenseNo;
        HIS.go('drug-return');
      },
      dispLabel: dispLabel,
      dispTag: dispTag,
      pharmacyName: pharmacyName,
      money: money,
      fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">调剂发药记录 <span style="font-size:12px;color:#909399;font-weight:normal;">(发药/核对双签留痕 · 导出按药房+日期区间口径)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable style="width:150px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:130px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px" @change="search"></el-date-picker>',
      '    <el-input v-model="keyword" placeholder="患者姓名/发药单号" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" :loading="exporting" @click="doExport">导出</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="dispenseNo" label="发药单号" width="150"></el-table-column>',
      '    <el-table-column label="药房" width="100"><template #default="s">{{ pharmacyName(pharmacyDefs, s.row.pharmacyId) }}</template></el-table-column>',
      '    <el-table-column label="处方号/ID" width="130"><template #default="s">{{ s.row.rxNo || s.row.prescriptionId || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="120"></el-table-column>',
      '    <el-table-column prop="doctorName" label="医生" width="100"></el-table-column>',
      '    <el-table-column label="发药时间" width="160"><template #default="s">{{ fmtTime(s.row.dispenseTime) }}</template></el-table-column>',
      '    <el-table-column prop="dispenseBy" label="发药人" width="90"></el-table-column>',
      '    <el-table-column prop="checkBy" label="核对人" width="90"></el-table-column>',
      '    <el-table-column label="金额" width="100" align="right"><template #default="s">￥{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="dispTag(s.row.status)">{{ dispLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="80" fixed="right"><template #default="s">',
      '      <el-button v-if="s.row.status===2" link type="warning" size="small" @click="goReturn(s.row)">退药</el-button>',
      '      <span v-else style="color:#c0c4cc;">-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* ================= 退药(申请 + 审批 + 记录) ================= */
  HIS.views.DrugReturn = {
    data: function () {
      return {
        /* 新建退药申请 */
        searchDispenseNo: '', dispSearchLoading: false, dispense: null,
        /* 药房过滤(限定发药记录查询范围; 退药记录行无药房字段不做过滤) */
        pharmacyId: '', pharmacyDefs: [],
        returnReason: '', submitting: false,
        /* 退药记录(按状态页签): 0待审核 1已退药 2已驳回 */
        returnTab: '0',
        loading: false, list: [], total: 0, page: 1, size: 20
      };
    },
    created: function () {
      loadPharmacyOpts(this);
      this.load();
      /* 从「调剂发药记录」页跳转带入的发药单号: 消费后立即清除并自动查询 */
      if (HIS.pendingReturnDispenseNo) {
        this.searchDispenseNo = HIS.pendingReturnDispenseNo;
        HIS.pendingReturnDispenseNo = null;
        this.searchDispense();
      }
    },
    methods: {
      /* ---- 退药申请 ---- */
      searchDispense: function () {
        var vm = this;
        var no = (vm.searchDispenseNo || '').trim();
        if (!no) { ElementPlus.ElMessage.warning('请输入发药单号'); return; }
        vm.dispSearchLoading = true; vm.dispense = null;
        var q = '/api/his/pharmacy/records?status=2&page=1&size=20&keyword=' + encodeURIComponent(no);
        if (vm.pharmacyId) { q += '&pharmacyId=' + vm.pharmacyId; }
        HIS.get(q)
          .then(function (d) {
            var recs = (d && d.records) || [];
            var hit = null;
            for (var i = 0; i < recs.length; i++) { if (recs[i].dispenseNo === no) { hit = recs[i]; break; } }
            if (!hit && recs.length) { hit = recs[0]; }
            if (hit) { vm.dispense = hit; }
            else { ElementPlus.ElMessage.warning('未找到已发药状态的单号: ' + no + (vm.pharmacyId ? '(当前药房范围内)' : '')); }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.dispSearchLoading = false; });
      },
      /* 药房范围切换: 已有单号则重新查询, 否则清空当前命中(避免跨药房残留) */
      onPharmacyChange: function () {
        if ((this.searchDispenseNo || '').trim()) { this.searchDispense(); }
        else { this.dispense = null; }
      },
      submitReturn: function () {
        var vm = this;
        if (!vm.dispense) { ElementPlus.ElMessage.warning('请先查询发药记录'); return; }
        var reason = (vm.returnReason || '').trim();
        if (!reason) { ElementPlus.ElMessage.warning('请填写退药原因'); return; }
        ElementPlus.ElMessageBox.confirm(
          '确认为发药单 ' + vm.dispense.dispenseNo + '（' + vm.dispense.patientName + '，金额￥' + money(vm.dispense.totalAmount) + '）提交退药申请？',
          '退药申请',
          { type: 'warning', confirmButtonText: '提交申请', cancelButtonText: '取消' }
        ).then(function () {
          vm.submitting = true;
          return HIS.post('/api/his/pharmacy/return', { dispenseId: vm.dispense.id, reason: reason });
        }).then(function (d) {
          HIS.notifySuccess('退药申请已提交, 退药单号 ' + ((d && d.returnNo) || ''));
          vm.dispense = null; vm.returnReason = ''; vm.searchDispenseNo = '';
          vm.returnTab = '0'; vm.page = 1; vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.submitting = false; });
      },
      /* ---- 退药记录 ---- */
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/returns?page=' + vm.page + '&size=' + vm.size + '&status=' + vm.returnTab)
          .then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ---- 退药审批 ---- */
      approve: function (row, ok) {
        var vm = this;
        var tip = ok ? '确认通过退药？药品将退回药库库存' : '确认驳回退药申请？';
        ElementPlus.ElMessageBox.confirm(tip, '退药审批 - ' + row.returnNo, {
          type: ok ? 'success' : 'warning',
          confirmButtonText: ok ? '通过' : '驳回',
          cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/pharmacy/return/' + row.id + '/approve?approved=' + ok);
        }).then(function () {
          HIS.notifySuccess(ok ? '已通过退药, 药品已按发药批次回补药库库存' : '已驳回该退药申请');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      retLabel: retLabel,
      retTag: retTag,
      pharmacyName: pharmacyName,
      money: money,
      fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">退药 <span style="font-size:12px;color:#909399;font-weight:normal;">(仅已发药单可退 · 审核通过后按发药批次回补药库库存)</span></div>',
      /* ---- 新建退药申请 ---- */
      '  <el-divider content-position="left">新建退药申请</el-divider>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable style="width:150px" @change="onPharmacyChange">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-input v-model="searchDispenseNo" placeholder="发药单号(如 FY202609250001)" clearable style="width:280px" @keyup.enter="searchDispense"></el-input>',
      '    <el-button type="primary" :loading="dispSearchLoading" @click="searchDispense">查询发药记录</el-button>',
      '    <span style="color:#909399;font-size:13px;">仅「已发药」状态的单据可申请; 选择药房可限定查询范围; 也可在「调剂发药记录」页点"退药"自动带入单号</span>',
      '  </div>',
      '  <div v-if="dispense" style="margin-bottom:14px;">',
      '    <el-descriptions :column="3" border size="small" style="margin-bottom:12px;">',
      '      <el-descriptions-item label="发药单号">{{ dispense.dispenseNo }}</el-descriptions-item>',
      '      <el-descriptions-item label="患者姓名">{{ dispense.patientName }}</el-descriptions-item>',
      '      <el-descriptions-item label="医生">{{ dispense.doctorName || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="科室">{{ dispense.deptName || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="药房">{{ pharmacyName(pharmacyDefs, dispense.pharmacyId) }}</el-descriptions-item>',
      '      <el-descriptions-item label="发药金额"><span style="color:#f56c6c;font-weight:600;">￥{{ money(dispense.totalAmount) }}</span></el-descriptions-item>',
      '      <el-descriptions-item label="发药时间">{{ fmtTime(dispense.dispenseTime) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <el-input v-model="returnReason" type="textarea" :rows="2" placeholder="退药原因(必填), 如: 患者用药后不适 / 医师停嘱" style="max-width:760px;"></el-input>',
      '    <div class="toolbar" style="justify-content:flex-end;max-width:760px;margin-bottom:0;">',
      '      <el-button type="warning" :loading="submitting" @click="submitReturn">提交退药申请</el-button>',
      '    </div>',
      '  </div>',
      '  <el-alert v-else type="info" :closable="false" show-icon title="输入发药单号查询已发药记录, 查到后填写退药原因提交申请" style="margin-bottom:8px;"></el-alert>',
      /* ---- 退药记录 ---- */
      '  <el-divider content-position="left">退药记录</el-divider>',
      '  <el-tabs v-model="returnTab" @tab-change="onTab">',
      '    <el-tab-pane label="待审核" name="0"></el-tab-pane>',
      '    <el-tab-pane label="已退药" name="1"></el-tab-pane>',
      '    <el-tab-pane label="已驳回" name="2"></el-tab-pane>',
      '  </el-tabs>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="returnNo" label="退药单号" width="150"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column prop="reason" label="退药原因" width="200" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="退药金额" width="100" align="right"><template #default="s">￥{{ money(s.row.returnAmount) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="retTag(s.row.status)">{{ retLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="申请时间" width="160"><template #default="s">{{ fmtTime(s.row.createTime) }}</template></el-table-column>',
      '    <el-table-column prop="approveBy" label="审批人" width="90"></el-table-column>',
      '    <el-table-column label="审批时间" width="160"><template #default="s">{{ fmtTime(s.row.approveTime) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="160" fixed="right"><template #default="s">',
      '      <template v-if="s.row.status===0">',
      '        <el-button type="success" size="small" @click="approve(s.row, true)">通过</el-button>',
      '        <el-button type="danger" size="small" @click="approve(s.row, false)">驳回</el-button>',
      '      </template>',
      '      <span v-else style="color:#909399;font-size:12px;">已处理</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* ================= 药房定义管理(机构级多药房) =================
   * 列表: GET /api/his/pharmacy/pharmacy-def(后端仅返回启用中的药房, 机构首次访问自动建默认门诊药房);
   * 保存: POST /api/his/pharmacy/pharmacy-def(id空=新增, code同机构唯一, 关联药库须存在且启用);
   * 启停: POST /api/his/pharmacy/pharmacy-def/{id}/toggle?enabled= (停用后行从列表消失, 历史单据不受影响);
   * 写操作仅牵头机构管理员(后端 requireLeadWrite): 前端按 lead 隐藏按钮并以后端403兜底。
   * 关联药库选项: GET /api/his/stock/warehouse-def(启用中的药库, 与后端保存校验同口径)。
   */
  HIS.views.PharmacyDef = {
    data: function () {
      return {
        lead: HIS.isLead(),
        loading: false, list: [],
        warehouseDefs: [],
        typeOpts: PHARMACY_TYPES,
        /* 新增/编辑弹窗 */
        dlgVisible: false, saving: false,
        form: { id: null, code: '', name: '', pharmacyType: 'OUTPATIENT', warehouseId: null, location: '', sortNo: 0 }
      };
    },
    created: function () { this.load(); this.loadWarehouses(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/pharmacy-def').then(function (list) {
          vm.list = list || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 药库下拉选项(启用中的药库; 保存时后端同样校验关联药库须启用) */
      loadWarehouses: function () {
        var vm = this;
        HIS.get('/api/his/stock/warehouse-def').then(function (list) {
          vm.warehouseDefs = list || [];
        }).catch(HIS.notifyError);
      },
      openAdd: function () {
        this.form = { id: null, code: '', name: '', pharmacyType: 'OUTPATIENT', warehouseId: null, location: '', sortNo: 0 };
        this.dlgVisible = true;
      },
      openEdit: function (row) {
        this.form = {
          id: row.id, code: row.code, name: row.name,
          pharmacyType: row.pharmacyType || 'OUTPATIENT',
          warehouseId: (row.warehouseId === null || row.warehouseId === undefined) ? null : row.warehouseId,
          location: row.location || '',
          sortNo: (row.sortNo === null || row.sortNo === undefined) ? 0 : row.sortNo
        };
        this.dlgVisible = true;
      },
      /* 保存(新增/编辑): orgId 由后端按登录机构解析, 编辑时归属机构不可迁移 */
      save: function () {
        var vm = this;
        if (!(vm.form.code || '').trim()) { ElementPlus.ElMessage.warning('请填写药房编码'); return; }
        if (!(vm.form.name || '').trim()) { ElementPlus.ElMessage.warning('请填写药房名称'); return; }
        vm.saving = true;
        HIS.post('/api/his/pharmacy/pharmacy-def', {
          id: vm.form.id, code: vm.form.code, name: vm.form.name,
          pharmacyType: vm.form.pharmacyType, warehouseId: vm.form.warehouseId,
          location: vm.form.location, sortNo: vm.form.sortNo
        }).then(function (d) {
          HIS.notifySuccess((vm.form.id ? '药房已更新: ' : '药房已新增: ') + ((d && d.name) || ''));
          vm.dlgVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* 启停: 停用后从列表与各工作站药房下拉中移除 */
      toggle: function (row) {
        var vm = this;
        var off = row.status === 1;
        var tip = off
          ? '停用后「' + row.name + '」将从本列表及待发药/发药记录/退药页的药房下拉中移除, 历史发药/退药单据不受影响。确认停用？'
          : '确认启用药房「' + row.name + '」？';
        ElementPlus.ElMessageBox.confirm(tip, off ? '停用药房' : '启用药房', {
          type: 'warning', confirmButtonText: off ? '确认停用' : '确认启用', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/pharmacy/pharmacy-def/' + row.id + '/toggle?enabled=' + (off ? 'false' : 'true'));
        }).then(function () {
          HIS.notifySuccess((off ? '已停用: ' : '已启用: ') + row.name);
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* 关联药库名称(药库已停用不在选项中时提示其ID) */
      warehouseName: function (id) {
        if (id === null || id === undefined || id === '') { return '-'; }
        for (var i = 0; i < this.warehouseDefs.length; i++) {
          if (this.warehouseDefs[i].id === id) { return this.warehouseDefs[i].name; }
        }
        return '药库#' + id;
      },
      pharmacyTypeLabel: pharmacyTypeLabel,
      pharmacyTypeTag: pharmacyTypeTag
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药房管理 <span style="font-size:12px;color:#909399;font-weight:normal;">(机构级多药房 · 待发药/发药记录/退药按药房过滤 · 维护仅牵头机构管理员)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openAdd">新增药房</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 个启用中药房</span>',
      '  </div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;" title="列表仅显示启用中的药房; 停用后药房从各工作站药房下拉中移除, 历史发药/退药单据不受影响"></el-alert>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="code" label="编码" width="130"></el-table-column>',
      '    <el-table-column prop="name" label="名称" width="150"></el-table-column>',
      '    <el-table-column label="类型" width="90" align="center"><template #default="s">',
      '      <el-tag size="small" :type="pharmacyTypeTag(s.row.pharmacyType)">{{ pharmacyTypeLabel(s.row.pharmacyType) }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="关联药库" width="140"><template #default="s">{{ warehouseName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column label="位置" width="140" show-overflow-tooltip><template #default="s">{{ s.row.location || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="sortNo" label="排序" width="70" align="center"></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s">',
      '      <el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?\'启用\':\'停用\' }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '      <template v-if="lead">',
      '        <el-button link type="primary" size="small" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button v-if="s.row.status===1" link type="danger" size="small" @click="toggle(s.row)">停用</el-button>',
      '        <el-button v-else link type="success" size="small" @click="toggle(s.row)">启用</el-button>',
      '      </template>',
      '      <span v-else style="color:#c0c4cc;">-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      /* ---- 新增/编辑弹窗 ---- */
      '  <el-dialog v-model="dlgVisible" :title="form.id?\'编辑药房\':\'新增药房\'" width="520px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="药房编码" required><el-input v-model="form.code" placeholder="如 PH-01(同机构唯一)" maxlength="32"></el-input></el-form-item>',
      '      <el-form-item label="药房名称" required><el-input v-model="form.name" placeholder="如 门诊药房" maxlength="64"></el-input></el-form-item>',
      '      <el-form-item label="药房类型">',
      '        <el-select v-model="form.pharmacyType" style="width:100%">',
      '          <el-option v-for="t in typeOpts" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="关联药库">',
      '        <el-select v-model="form.warehouseId" clearable placeholder="选择药库(可选)" style="width:100%">',
      '          <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="位置"><el-input v-model="form.location" placeholder="如 1楼取药窗口" maxlength="64"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input-number v-model="form.sortNo" :min="0" :max="9999" controls-position="right" style="width:140px"></el-input-number></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dlgVisible=false">取 消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="save">保 存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
