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

  /* 请领状态: 0草稿 1待审核 2已发货 3已收货 -1已驳回 -2已作废 */
  var REQ_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '待审核', t: 'warning' },
    { v: 2, l: '已发货', t: 'primary' },
    { v: 3, l: '已收货', t: 'success' },
    { v: -1, l: '已驳回', t: 'danger' },
    { v: -2, l: '已作废', t: 'info' }
  ];
  function reqLabel(v) { var s = findStatus(REQ_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function reqTag(v) { var s = findStatus(REQ_STATUS, v); return s ? s.t : 'info'; }
  /* 药库类型(来源药库联动选药范围, defs 找不到返回空串) */
  function whTypeById(defs, id) {
    for (var i = 0; i < (defs || []).length; i++) { if (defs[i].id === id) { return defs[i].warehouseType; } }
    return '';
  }

  /* ================= 药品请领(药房→药库) =================
   * 后端 /api/his/requisition: page/detail/{id}/available/create/approve/receive/void;
   * 请领为本机构自治业务(写走 requireSelfOrgWrite, 不限牵头), 读隔离走 scopeOrgId(前端不传 orgId);
   * 状态机 0草稿 1待审核 2已发货 3已收货 -1已驳回 -2已作废;
   * 审核发货=对来源药库FIFO出库, 确认收货=对药房库存位入库; 可用库存经 /available 超量提示(仅提示不强制, 发货以实批为准)。
   * 选药复用机构开展药品目录 /api/his/stock/drug-catalog(按来源药库 warehouseType 联动); 药房/药库下拉复用共用接口。
   */
  HIS.views.RequisitionManage = {
    data: function () {
      return {
        lead: HIS.isLead(),
        loading: false, list: [], total: 0, page: 1, size: 20,
        filterPharmacy: '', filterStatus: null, keyword: '',
        pharmacyDefs: [], warehouseDefs: [], statusOpts: REQ_STATUS,
        /* 发起请领对话框 */
        createDlg: false, saving: false,
        form: { pharmacyId: '', toWarehouseId: null, remark: '', items: [] },
        /* 药品选择器 */
        drugDlg: false, drugLoading: false, drugList: [], drugTotal: 0, drugPage: 1, drugSize: 10, drugKeyword: '', pendingIdx: null,
        /* 详情对话框 */
        detailDlg: false, detailLoading: false, detailMain: null, detailItems: []
      };
    },
    computed: {
      totalAmount: function () {
        var s = 0;
        for (var i = 0; i < this.form.items.length; i++) {
          var it = this.form.items[i];
          s += (Number(it.qtyApply) || 0) * (Number(it.retailPrice) || 0);
        }
        return s;
      }
    },
    created: function () { loadPharmacyOpts(this); this.loadWarehouses(); this.load(); },
    methods: {
      /* ---- 列表 ---- */
      loadWarehouses: function () {
        var vm = this;
        HIS.get('/api/his/stock/warehouse-def').then(function (list) { vm.warehouseDefs = list || []; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/requisition/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterPharmacy) { q += '&pharmacyId=' + vm.filterPharmacy; }
        if (vm.filterStatus !== null && vm.filterStatus !== '' && vm.filterStatus !== undefined) { q += '&status=' + vm.filterStatus; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ---- 发起请领 ---- */
      openCreate: function () { this.form = { pharmacyId: '', toWarehouseId: null, remark: '', items: [] }; this.createDlg = true; },
      onPharmacyChange: function () {
        var p = null;
        for (var i = 0; i < this.pharmacyDefs.length; i++) { if (this.pharmacyDefs[i].id === this.form.pharmacyId) { p = this.pharmacyDefs[i]; break; } }
        this.form.toWarehouseId = (p && p.warehouseId) ? p.warehouseId : null;
        this.refreshAvailable();
      },
      addItem: function () { this.form.items.push({ drugCatalogId: null, drugCode: '', drugName: '', spec: '', qtyApply: null, retailPrice: null, available: null }); },
      removeItem: function (idx) { this.form.items.splice(idx, 1); },
      /* 来源药库该药可用库存(超量提示); 未选来源药库则不校验 */
      loadAvailable: function (it) {
        var vm = this;
        if (!it || !it.drugCatalogId) { return; }
        if (!vm.form.toWarehouseId) { it.available = null; return; }
        HIS.get('/api/his/requisition/available?warehouseId=' + vm.form.toWarehouseId + '&drugCatalogId=' + it.drugCatalogId)
          .then(function (q) { it.available = (q == null ? 0 : Number(q)); });
      },
      refreshAvailable: function () { for (var i = 0; i < this.form.items.length; i++) { this.loadAvailable(this.form.items[i]); } },
      overApply: function (it) { return it.available != null && it.drugCatalogId && (Number(it.qtyApply) || 0) > Number(it.available); },
      /* ---- 药品选择器 ---- */
      openPicker: function (idx) { this.pendingIdx = idx; this.drugKeyword = ''; this.drugPage = 1; this.drugList = []; this.drugDlg = true; this.loadDrugs(); },
      loadDrugs: function () {
        var vm = this; vm.drugLoading = true;
        var q = '/api/his/stock/drug-catalog?page=' + vm.drugPage + '&size=' + vm.drugSize;
        var wt = whTypeById(vm.warehouseDefs, vm.form.toWarehouseId);
        if (wt === 'WESTERN' || wt === 'TCM') { q += '&warehouseType=' + wt; }
        if (vm.drugKeyword) { q += '&keyword=' + encodeURIComponent(vm.drugKeyword); }
        HIS.get(q).then(function (d) { vm.drugList = (d && d.records) || []; vm.drugTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.drugLoading = false; });
      },
      searchDrugs: function () { this.drugPage = 1; this.loadDrugs(); },
      onDrugPage: function (p) { this.drugPage = p; this.loadDrugs(); },
      pickDrug: function (d) {
        var it = this.form.items[this.pendingIdx];
        this.drugDlg = false;
        if (!it) { return; }
        it.drugCatalogId = d.id; it.drugCode = d.drugCode;
        it.drugName = d.genericName || d.tradeName || d.drugCode;
        it.spec = d.spec || '';
        it.retailPrice = (d.retailPrice != null) ? Number(d.retailPrice) : null;
        this.loadAvailable(it);
      },
      /* ---- 提交/审核/收货/作废 ---- */
      submitCreate: function () {
        var vm = this;
        if (!vm.form.pharmacyId) { ElementPlus.ElMessage.warning('请选择请领药房'); return; }
        var items = vm.form.items.filter(function (it) { return it.drugCatalogId; });
        if (!items.length) { ElementPlus.ElMessage.warning('请至少添加一个药品'); return; }
        for (var i = 0; i < items.length; i++) {
          if (!(Number(items[i].qtyApply) > 0)) { ElementPlus.ElMessage.warning('请领数量须大于0: ' + items[i].drugName); return; }
        }
        vm.saving = true;
        HIS.post('/api/his/requisition', {
          pharmacyId: vm.form.pharmacyId, toWarehouseId: vm.form.toWarehouseId || null,
          submit: true, remark: vm.form.remark,
          items: items.map(function (it) {
            return { drugCatalogId: it.drugCatalogId, drugCode: it.drugCode, drugName: it.drugName, spec: it.spec, qtyApply: Number(it.qtyApply), retailPrice: it.retailPrice != null ? Number(it.retailPrice) : null };
          })
        }).then(function (d) {
          HIS.notifySuccess('请领单已提交, 单号 ' + ((d && d.reqNo) || ''));
          vm.createDlg = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      approve: function (row, ok) {
        var vm = this;
        var tip = ok ? '确认审核通过并发货? 将从来源药库按有效期FIFO出库并扣减药库库存。' : '确认驳回该请领单?';
        ElementPlus.ElMessageBox.confirm(tip, '请领审核 - ' + row.reqNo, {
          type: ok ? 'success' : 'warning', confirmButtonText: ok ? '审核发货' : '驳回', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/requisition/' + row.id + '/approve?approved=' + ok);
        }).then(function () {
          HIS.notifySuccess(ok ? '已发货, 请药房在列表中确认收货' : '已驳回该请领单');
          vm.load();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      receive: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认收货? 将按发货数量对药房库存位入库, 药房库存增加。', '请领收货 - ' + row.reqNo, {
          type: 'success', confirmButtonText: '确认收货', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/requisition/' + row.id + '/receive');
        }).then(function () { HIS.notifySuccess('已确认收货, 药房库存已增加'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      voidOne: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废该请领单? 作废后不可恢复。', '请领作废 - ' + row.reqNo, {
          type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/requisition/' + row.id + '/void');
        }).then(function () { HIS.notifySuccess('已作废'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      openDetail: function (row) {
        var vm = this;
        vm.detailDlg = true; vm.detailLoading = true; vm.detailMain = null; vm.detailItems = [];
        HIS.get('/api/his/requisition/' + row.id).then(function (d) {
          vm.detailMain = (d && d.main) || null; vm.detailItems = (d && d.items) || [];
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      warehouseName: function (id) {
        if (id === null || id === undefined || id === '') { return '-'; }
        for (var i = 0; i < this.warehouseDefs.length; i++) { if (this.warehouseDefs[i].id === id) { return this.warehouseDefs[i].name; } }
        return '药库#' + id;
      },
      reqLabel: reqLabel, reqTag: reqTag, pharmacyName: pharmacyName, money: money, fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药品请领 <span style="font-size:12px;color:#909399;font-weight:normal;">(药房→药库 · 发起请领 → 药库审核发货 → 药房确认收货)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterPharmacy" placeholder="全部药房" clearable style="width:150px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:120px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="请领单号/药品名称" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button type="primary" @click="openCreate">发起请领</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="reqNo" label="请领单号" width="150"></el-table-column>',
      '    <el-table-column label="请领药房" width="110"><template #default="s">{{ pharmacyName(pharmacyDefs, s.row.pharmacyId) }}</template></el-table-column>',
      '    <el-table-column label="来源药库" width="120"><template #default="s">{{ warehouseName(s.row.toWarehouseId) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="reqTag(s.row.status)">{{ reqLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="applyBy" label="申请人" width="90"></el-table-column>',
      '    <el-table-column label="申请时间" width="160"><template #default="s">{{ fmtTime(s.row.applyTime) }}</template></el-table-column>',
      '    <el-table-column label="金额" width="100" align="right"><template #default="s">￥{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="250" fixed="right"><template #default="s">',
      '      <el-button link type="info" size="small" @click="openDetail(s.row)">详情</el-button>',
      '      <el-button v-if="s.row.status===1" link type="success" size="small" @click="approve(s.row, true)">审核发货</el-button>',
      '      <el-button v-if="s.row.status===1" link type="danger" size="small" @click="approve(s.row, false)">驳回</el-button>',
      '      <el-button v-if="s.row.status===2" link type="primary" size="small" @click="receive(s.row)">确认收货</el-button>',
      '      <el-button v-if="s.row.status===0||s.row.status===1" link type="warning" size="small" @click="voidOne(s.row)">作废</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 发起请领对话框 ---- */
      '  <el-dialog v-model="createDlg" title="发起药品请领" width="900px" top="6vh">',
      '    <div class="toolbar" style="margin-bottom:8px;">',
      '      <span style="font-weight:600;">请领药房</span>',
      '      <el-select v-model="form.pharmacyId" placeholder="选择药房" style="width:180px" @change="onPharmacyChange">',
      '        <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '      </el-select>',
      '      <span style="font-weight:600;margin-left:12px;">来源药库</span>',
      '      <el-select v-model="form.toWarehouseId" placeholder="默认药房关联药库" clearable style="width:200px" @change="refreshAvailable">',
      '        <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '      </el-select>',
      '    </div>',
      '    <el-table :data="form.items" border size="small" max-height="320">',
      '      <el-table-column type="index" label="#" width="45"></el-table-column>',
      '      <el-table-column label="药品" min-width="230"><template #default="s">',
      '        <span v-if="s.row.drugName">{{ s.row.drugName }} <span style="color:#909399;">{{ s.row.spec }}</span> <el-button link type="info" size="small" @click="openPicker(s.$index)">重选</el-button></span>',
      '        <el-button v-else link type="primary" @click="openPicker(s.$index)">选择药品</el-button>',
      '      </template></el-table-column>',
      '      <el-table-column label="可用库存" width="90" align="right"><template #default="s"><span v-if="s.row.available!=null">{{ s.row.available }}</span><span v-else style="color:#c0c4cc;">-</span></template></el-table-column>',
      '      <el-table-column label="请领数量" width="135"><template #default="s"><el-input-number v-model="s.row.qtyApply" :min="0" :precision="2" controls-position="right" size="small" style="width:120px"></el-input-number></template></el-table-column>',
      '      <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ money(s.row.retailPrice) }}</template></el-table-column>',
      '      <el-table-column label="小计" width="100" align="right"><template #default="s">￥{{ money((Number(s.row.qtyApply)||0)*(Number(s.row.retailPrice)||0)) }}</template></el-table-column>',
      '      <el-table-column label="校验" width="70" align="center"><template #default="s"><el-tag v-if="overApply(s.row)" size="small" type="danger">超量</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="60"><template #default="s"><el-button link type="danger" size="small" @click="removeItem(s.$index)">删除</el-button></template></el-table-column>',
      '    </el-table>',
      '    <div class="toolbar" style="margin-top:8px;">',
      '      <el-button @click="addItem">+ 添加药品</el-button>',
      '      <el-input v-model="form.remark" placeholder="备注(可选)" style="width:280px;"></el-input>',
      '      <span style="flex:1;"></span>',
      '      <span style="color:#909399;font-size:13px;">合计 ￥{{ money(totalAmount) }}</span>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="createDlg=false">取 消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitCreate">提交请领</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 药品选择器 ---- */
      '  <el-dialog v-model="drugDlg" title="选择药品(机构开展药品目录)" width="860px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-input v-model="drugKeyword" placeholder="药品名称/编码/拼音简码" clearable style="width:280px" @keyup.enter="searchDrugs"></el-input>',
      '      <el-button type="primary" @click="searchDrugs">查询</el-button>',
      '      <span style="flex:1;"></span>',
      '      <span style="color:#909399;font-size:12px;">共 {{ drugTotal }} 条</span>',
      '    </div>',
      '    <el-table :data="drugList" v-loading="drugLoading" border size="small" height="320">',
      '      <el-table-column prop="drugCode" label="编码" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="genericName" label="通用名" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="dosformName" label="剂型" width="80"></el-table-column>',
      '      <el-table-column prop="manufacturer" label="厂家" min-width="140" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="minUnit" label="单位" width="60"></el-table-column>',
      '      <el-table-column label="零售价" width="80" align="right"><template #default="s">{{ s.row.retailPrice }}</template></el-table-column>',
      '      <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="pickDrug(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="drugTotal" :page-size="drugSize" :current-page="drugPage" @current-change="onDrugPage"></el-pagination>',
      '  </el-dialog>',
      /* ---- 详情对话框 ---- */
      '  <el-dialog v-model="detailDlg" title="请领单详情" width="820px" top="6vh">',
      '    <div v-loading="detailLoading">',
      '      <el-descriptions v-if="detailMain" :column="3" border size="small" style="margin-bottom:12px;">',
      '        <el-descriptions-item label="请领单号">{{ detailMain.reqNo }}</el-descriptions-item>',
      '        <el-descriptions-item label="请领药房">{{ pharmacyName(pharmacyDefs, detailMain.pharmacyId) }}</el-descriptions-item>',
      '        <el-descriptions-item label="来源药库">{{ warehouseName(detailMain.toWarehouseId) }}</el-descriptions-item>',
      '        <el-descriptions-item label="状态">{{ reqLabel(detailMain.status) }}</el-descriptions-item>',
      '        <el-descriptions-item label="申请人">{{ detailMain.applyBy || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="申请时间">{{ fmtTime(detailMain.applyTime) }}</el-descriptions-item>',
      '        <el-descriptions-item label="审核人">{{ detailMain.approveBy || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="收货人">{{ detailMain.receiveBy || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="金额">￥{{ money(detailMain.totalAmount) }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-table :data="detailItems" border size="small" max-height="300">',
      '        <el-table-column type="index" label="#" width="45"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="qtyApply" label="请领" width="70" align="right"></el-table-column>',
      '        <el-table-column prop="qtyApproved" label="发货" width="70" align="right"></el-table-column>',
      '        <el-table-column prop="qtyReceived" label="实收" width="70" align="right"></el-table-column>',
      '        <el-table-column prop="batchNo" label="批次" width="120" show-overflow-tooltip><template #default="s">{{ s.row.batchNo || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="小计" width="90" align="right"><template #default="s">￥{{ money(s.row.amount) }}</template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="detailDlg=false">关 闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* 调拨状态: 0草稿 1待调出确认 2已调出 3已调入 -2已作废 */
  var TRF_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '待调出确认', t: 'warning' },
    { v: 2, l: '已调出', t: 'primary' },
    { v: 3, l: '已调入', t: 'success' },
    { v: -2, l: '已作废', t: 'info' }
  ];
  function trfLabel(v) { var s = findStatus(TRF_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function trfTag(v) { var s = findStatus(TRF_STATUS, v); return s ? s.t : 'info'; }
  function trfKindLabel(k) {
    var m = { WH2PHARMACY: '药库→药房', PHARMACY2PHARMACY: '药房→药房', WH2WH: '药库→药库' };
    return m[k] || k || '-';
  }

  /* ================= 库存调拨(药库/药房库存位之间, 批次随行) =================
   * 后端 /api/his/transfer: page/detail/{id}/locations/available/create/ship/receive/void;
   * 限同机构(读 scopeOrgId, 写 requireSelfOrgWrite); 状态机 0草稿 1待调出 2已调出 3已调入 -2已作废;
   * 调出=对调出库位 out_type=4 出库(指定批次), 调入=对调入库位 in_type=4 入库(保留同批次效期), 数量守恒;
   * 库位下拉 /locations 返回药库+药房库存位(kind区分); 明细从调出库位库存批次选(/api/his/stock/page?warehouseId=库位), 锁定批号/价格并以库存量硬校验超量。
   */
  HIS.views.TransferManage = {
    data: function () {
      return {
        lead: HIS.isLead(),
        loading: false, list: [], total: 0, page: 1, size: 20, filterStatus: null, keyword: '',
        locations: [], statusOpts: TRF_STATUS,
        createDlg: false, saving: false,
        form: { fromLocationId: null, toLocationId: null, remark: '', items: [] },
        batchDlg: false, batchLoading: false, batchList: [], batchTotal: 0, batchPage: 1, batchSize: 10, batchKeyword: '', pendingIdx: null,
        detailDlg: false, detailLoading: false, detailMain: null, detailItems: []
      };
    },
    computed: {
      totalAmount: function () {
        var s = 0;
        for (var i = 0; i < this.form.items.length; i++) {
          var it = this.form.items[i];
          var p = (it.retailPrice != null) ? Number(it.retailPrice) : Number(it.costPrice || 0);
          s += (Number(it.qty) || 0) * (p || 0);
        }
        return s;
      }
    },
    created: function () { this.loadLocations(); this.load(); },
    methods: {
      loadLocations: function () {
        var vm = this;
        HIS.get('/api/his/transfer/locations').then(function (list) { vm.locations = list || []; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/transfer/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterStatus !== null && vm.filterStatus !== '' && vm.filterStatus !== undefined) { q += '&status=' + vm.filterStatus; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ---- 发起调拨 ---- */
      openCreate: function () { this.form = { fromLocationId: null, toLocationId: null, remark: '', items: [] }; this.createDlg = true; },
      addItem: function () {
        if (!this.form.fromLocationId) { ElementPlus.ElMessage.warning('请先选择调出库位, 再选择库存批次'); return; }
        this.form.items.push({ drugCatalogId: null, drugCode: '', drugName: '', spec: '', batchNo: '', qty: null, costPrice: null, retailPrice: null, stockQty: null });
        this.openBatchDlg(this.form.items.length - 1);
      },
      removeItem: function (idx) { this.form.items.splice(idx, 1); },
      overQty: function (it) { return it.stockQty != null && (Number(it.qty) || 0) > Number(it.stockQty); },
      /* ---- 批次选择器(仅调出库位可用批次) ---- */
      openBatchDlg: function (idx) { this.pendingIdx = idx; this.batchKeyword = ''; this.batchPage = 1; this.batchList = []; this.batchDlg = true; this.loadBatches(); },
      loadBatches: function () {
        var vm = this; vm.batchLoading = true;
        var q = '/api/his/stock/page?page=' + vm.batchPage + '&size=' + vm.batchSize;
        if (vm.form.fromLocationId) { q += '&warehouseId=' + vm.form.fromLocationId; }
        if (vm.batchKeyword) { q += '&keyword=' + encodeURIComponent(vm.batchKeyword); }
        HIS.get(q).then(function (d) {
          vm.batchList = ((d && d.records) || []).filter(function (r) { return r.status === 1 && Number(r.qty) > 0; });
          vm.batchTotal = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.batchLoading = false; });
      },
      searchBatches: function () { this.batchPage = 1; this.loadBatches(); },
      onBatchPage: function (p) { this.batchPage = p; this.loadBatches(); },
      pickBatch: function (b) {
        var it = this.form.items[this.pendingIdx];
        this.batchDlg = false;
        if (!it) { return; }
        it.drugCatalogId = b.drugCatalogId; it.drugCode = b.drugCode; it.drugName = b.drugName;
        it.spec = b.spec || ''; it.batchNo = b.batchNo || '';
        it.costPrice = b.costPrice; it.retailPrice = b.retailPrice; it.stockQty = b.qty;
      },
      submitCreate: function () {
        var vm = this;
        if (!vm.form.fromLocationId) { ElementPlus.ElMessage.warning('请选择调出库位'); return; }
        if (!vm.form.toLocationId) { ElementPlus.ElMessage.warning('请选择调入库位'); return; }
        if (String(vm.form.fromLocationId) === String(vm.form.toLocationId)) { ElementPlus.ElMessage.warning('调出与调入库位不能相同'); return; }
        var items = vm.form.items.filter(function (it) { return it.drugCatalogId && it.batchNo; });
        if (!items.length) { ElementPlus.ElMessage.warning('请至少选择一条库存批次明细'); return; }
        for (var i = 0; i < items.length; i++) {
          var it = items[i];
          if (!(Number(it.qty) > 0)) { ElementPlus.ElMessage.warning('调拨数量须大于0: ' + it.drugName); return; }
          if (vm.overQty(it)) { ElementPlus.ElMessage.warning('超出调出库位该批次库存(' + it.stockQty + '): ' + it.drugName + ' ' + it.batchNo); return; }
        }
        vm.saving = true;
        HIS.post('/api/his/transfer', {
          fromLocationId: vm.form.fromLocationId, toLocationId: vm.form.toLocationId, submit: true, remark: vm.form.remark,
          items: items.map(function (it) { return { drugCatalogId: it.drugCatalogId, drugCode: it.drugCode, drugName: it.drugName, spec: it.spec, batchNo: it.batchNo, qty: Number(it.qty) }; })
        }).then(function (d) {
          HIS.notifySuccess('调拨单已创建, 单号 ' + ((d && d.transferNo) || ''));
          vm.createDlg = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* ---- 调出/调入/作废/详情 ---- */
      ship: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认调出? 将从调出库位按指定批次出库并扣减库存。', '调拨调出 - ' + row.transferNo, { type: 'warning', confirmButtonText: '确认调出', cancelButtonText: '取消' })
          .then(function () { return HIS.post('/api/his/transfer/' + row.id + '/ship'); })
          .then(function () { HIS.notifySuccess('已调出, 请调入库位确认调入'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      receive: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认调入? 将按调出批次同批号/效期入到调入库位。', '调拨调入 - ' + row.transferNo, { type: 'success', confirmButtonText: '确认调入', cancelButtonText: '取消' })
          .then(function () { return HIS.post('/api/his/transfer/' + row.id + '/receive'); })
          .then(function () { HIS.notifySuccess('已调入, 调入库位库存已增加'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      voidOne: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废该调拨单? 作废后不可恢复。', '调拨作废 - ' + row.transferNo, { type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消' })
          .then(function () { return HIS.post('/api/his/transfer/' + row.id + '/void'); })
          .then(function () { HIS.notifySuccess('已作废'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      openDetail: function (row) {
        var vm = this;
        vm.detailDlg = true; vm.detailLoading = true; vm.detailMain = null; vm.detailItems = [];
        HIS.get('/api/his/transfer/' + row.id).then(function (d) {
          vm.detailMain = (d && d.main) || null; vm.detailItems = (d && d.items) || [];
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      locationName: function (id) {
        if (id === null || id === undefined || id === '') { return '-'; }
        for (var i = 0; i < this.locations.length; i++) { if (this.locations[i].id === id) { return this.locations[i].name; } }
        return '库位#' + id;
      },
      locationKindLabel: function (id) {
        for (var i = 0; i < this.locations.length; i++) { if (this.locations[i].id === id) { return this.locations[i].kind === 'PHARMACY' ? '药房' : '药库'; } }
        return '';
      },
      trfLabel: trfLabel, trfTag: trfTag, trfKindLabel: trfKindLabel, money: money, fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">库存调拨 <span style="font-size:12px;color:#909399;font-weight:normal;">(药库/药房库存位之间 · 建单 → 调出 → 调入 · 批次随行数量守恒)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:130px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="调拨单号/药品名称" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button type="primary" @click="openCreate">新建调拨</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="transferNo" label="调拨单号" width="150"></el-table-column>',
      '    <el-table-column label="调出库位" width="150"><template #default="s">{{ locationName(s.row.fromLocationId) }} <span style="color:#909399;">{{ locationKindLabel(s.row.fromLocationId) }}</span></template></el-table-column>',
      '    <el-table-column label="调入库位" width="150"><template #default="s">{{ locationName(s.row.toLocationId) }} <span style="color:#909399;">{{ locationKindLabel(s.row.toLocationId) }}</span></template></el-table-column>',
      '    <el-table-column label="类型" width="100" align="center"><template #default="s"><el-tag size="small" type="info">{{ trfKindLabel(s.row.kind) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="状态" width="110" align="center"><template #default="s"><el-tag size="small" :type="trfTag(s.row.status)">{{ trfLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="调出/调入人" width="120"><template #default="s">{{ s.row.shipBy || \'-\' }} / {{ s.row.receiveBy || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="金额" width="100" align="right"><template #default="s">￥{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="230" fixed="right"><template #default="s">',
      '      <el-button link type="info" size="small" @click="openDetail(s.row)">详情</el-button>',
      '      <el-button v-if="s.row.status===1" link type="warning" size="small" @click="ship(s.row)">确认调出</el-button>',
      '      <el-button v-if="s.row.status===2" link type="success" size="small" @click="receive(s.row)">确认调入</el-button>',
      '      <el-button v-if="s.row.status===0||s.row.status===1" link type="danger" size="small" @click="voidOne(s.row)">作废</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 新建调拨对话框 ---- */
      '  <el-dialog v-model="createDlg" title="新建库存调拨" width="900px" top="6vh">',
      '    <div class="toolbar" style="margin-bottom:8px;">',
      '      <span style="font-weight:600;">调出库位</span>',
      '      <el-select v-model="form.fromLocationId" placeholder="选择调出库位" filterable style="width:210px">',
      '        <el-option v-for="l in locations" :key="l.id" :label="l.name + \'(\' + (l.kind===\'PHARMACY\'?\'药房\':\'药库\') + \')\'" :value="l.id"></el-option>',
      '      </el-select>',
      '      <span style="font-weight:600;margin-left:12px;">调入库位</span>',
      '      <el-select v-model="form.toLocationId" placeholder="选择调入库位" filterable style="width:210px">',
      '        <el-option v-for="l in locations" :key="l.id" :disabled="l.id===form.fromLocationId" :label="l.name + \'(\' + (l.kind===\'PHARMACY\'?\'药房\':\'药库\') + \')\'" :value="l.id"></el-option>',
      '      </el-select>',
      '    </div>',
      '    <el-table :data="form.items" border size="small" max-height="320">',
      '      <el-table-column type="index" label="#" width="45"></el-table-column>',
      '      <el-table-column label="药品/批次" min-width="230"><template #default="s">',
      '        <span v-if="s.row.drugName">{{ s.row.drugName }} <span style="color:#909399;">{{ s.row.spec }} / 批 {{ s.row.batchNo }}</span> <el-button link type="info" size="small" @click="openBatchDlg(s.$index)">重选</el-button></span>',
      '        <el-button v-else link type="primary" @click="openBatchDlg(s.$index)">选择批次</el-button>',
      '      </template></el-table-column>',
      '      <el-table-column label="库存量" width="85" align="right"><template #default="s">{{ s.row.stockQty }}</template></el-table-column>',
      '      <el-table-column label="调拨数量" width="135"><template #default="s"><el-input-number v-model="s.row.qty" :min="0" :precision="2" controls-position="right" size="small" style="width:120px"></el-input-number></template></el-table-column>',
      '      <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ money(s.row.retailPrice) }}</template></el-table-column>',
      '      <el-table-column label="校验" width="70" align="center"><template #default="s"><el-tag v-if="overQty(s.row)" size="small" type="danger">超量</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="60"><template #default="s"><el-button link type="danger" size="small" @click="removeItem(s.$index)">删除</el-button></template></el-table-column>',
      '    </el-table>',
      '    <div class="toolbar" style="margin-top:8px;">',
      '      <el-button @click="addItem">+ 添加批次</el-button>',
      '      <el-input v-model="form.remark" placeholder="备注(可选)" style="width:280px;"></el-input>',
      '      <span style="flex:1;"></span>',
      '      <span style="color:#909399;font-size:13px;">合计(零售) ￥{{ money(totalAmount) }}</span>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="createDlg=false">取 消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitCreate">提交调拨</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 批次选择器 ---- */
      '  <el-dialog v-model="batchDlg" title="选择库存批次(调出库位可用批次)" width="860px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-input v-model="batchKeyword" placeholder="药品名称/编码/批号" clearable style="width:280px" @keyup.enter="searchBatches"></el-input>',
      '      <el-button type="primary" @click="searchBatches">查询</el-button>',
      '      <span style="flex:1;"></span>',
      '      <span style="color:#909399;font-size:12px;">共 {{ batchTotal }} 条</span>',
      '    </div>',
      '    <el-table :data="batchList" v-loading="batchLoading" border size="small" height="320">',
      '      <el-table-column prop="drugName" label="药品" min-width="160" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="batchNo" label="批号" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="效期" width="110"><template #default="s">{{ s.row.expDate ? String(s.row.expDate).slice(0,10) : \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="qty" label="库存" width="80" align="right"></el-table-column>',
      '      <el-table-column label="零售价" width="80" align="right"><template #default="s">{{ money(s.row.retailPrice) }}</template></el-table-column>',
      '      <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="pickBatch(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="batchTotal" :page-size="batchSize" :current-page="batchPage" @current-change="onBatchPage"></el-pagination>',
      '  </el-dialog>',
      /* ---- 详情对话框 ---- */
      '  <el-dialog v-model="detailDlg" title="调拨单详情" width="820px" top="6vh">',
      '    <div v-loading="detailLoading">',
      '      <el-descriptions v-if="detailMain" :column="3" border size="small" style="margin-bottom:12px;">',
      '        <el-descriptions-item label="调拨单号">{{ detailMain.transferNo }}</el-descriptions-item>',
      '        <el-descriptions-item label="调出库位">{{ locationName(detailMain.fromLocationId) }}</el-descriptions-item>',
      '        <el-descriptions-item label="调入库位">{{ locationName(detailMain.toLocationId) }}</el-descriptions-item>',
      '        <el-descriptions-item label="类型">{{ trfKindLabel(detailMain.kind) }}</el-descriptions-item>',
      '        <el-descriptions-item label="状态">{{ trfLabel(detailMain.status) }}</el-descriptions-item>',
      '        <el-descriptions-item label="金额">￥{{ money(detailMain.totalAmount) }}</el-descriptions-item>',
      '        <el-descriptions-item label="调出人">{{ detailMain.shipBy || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="调出时间">{{ fmtTime(detailMain.shipTime) }}</el-descriptions-item>',
      '        <el-descriptions-item label="调入时间">{{ fmtTime(detailMain.receiveTime) }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-table :data="detailItems" border size="small" max-height="300">',
      '        <el-table-column type="index" label="#" width="45"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="batchNo" label="批号" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="qty" label="数量" width="70" align="right"></el-table-column>',
      '        <el-table-column label="进价" width="80" align="right"><template #default="s">{{ money(s.row.costPrice) }}</template></el-table-column>',
      '        <el-table-column label="零售价" width="80" align="right"><template #default="s">{{ money(s.row.retailPrice) }}</template></el-table-column>',
      '        <el-table-column label="小计" width="90" align="right"><template #default="s">￥{{ money(s.row.amount) }}</template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="detailDlg=false">关 闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* 调价单状态: 0草稿 1已生效 2已作废 */
  var PA_STATUS = [
    { v: 0, l: '草稿', t: 'info' },
    { v: 1, l: '已生效', t: 'success' },
    { v: 2, l: '已作废', t: 'danger' }
  ];
  function paLabel(v) { var s = findStatus(PA_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function paTag(v) { var s = findStatus(PA_STATUS, v); return s ? s.t : 'info'; }

  /* ================= 药品调价单(药库/药房统一, 批次调价) =================
   * 后端 /api/his/price-adjust: page/detail/{id}/preview/create/confirm/void;
   * 目录价格是医共体级(牵头统一维护): 写操作 requireLeadWrite(仅牵头, 前端按 lead 隐藏按钮并以后端403兑底), 读为租户内共享;
   * 流程: 选药填新进/零售价 → 预览影响(在库量/金额变动) → 存草稿 → 生效(更新目录当前价+全租户在库零售价+写 his_price_adjust 留痕)/作废;
   * 状态机 0草稿 1已生效 2已作废; 明细新价为空表示该字段不调。
   */
  HIS.views.PriceAdjust = {
    data: function () {
      return {
        lead: HIS.isLead(),
        loading: false, list: [], total: 0, page: 1, size: 20, filterStatus: null, keyword: '',
        statusOpts: PA_STATUS,
        createDlg: false, saving: false, previewing: false,
        form: { effectiveDate: '', reason: '', items: [] },
        previewRows: [], previewTotal: null,
        drugDlg: false, drugLoading: false, drugList: [], drugTotal: 0, drugPage: 1, drugSize: 10, drugKeyword: '', pendingIdx: null,
        detailDlg: false, detailLoading: false, detailMain: null, detailItems: []
      };
    },
    computed: {
      diffTotal: function () {
        var s = 0;
        for (var i = 0; i < this.form.items.length; i++) {
          var it = this.form.items[i];
          if (it.newRetail != null && it.newRetail !== '' && it.impactStockQty != null) {
            s += (Number(it.newRetail) - Number(it.oldRetail || 0)) * Number(it.impactStockQty);
          }
        }
        return s;
      }
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/price-adjust/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterStatus !== null && vm.filterStatus !== '' && vm.filterStatus !== undefined) { q += '&status=' + vm.filterStatus; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ---- 新建调价 ---- */
      openCreate: function () { this.form = { effectiveDate: today(), reason: '', items: [] }; this.previewRows = []; this.previewTotal = null; this.createDlg = true; },
      addItem: function () { this.form.items.push({ drugCatalogId: null, drugCode: '', drugName: '', spec: '', oldPurchase: null, oldRetail: null, newPurchase: null, newRetail: null, impactStockQty: null }); this.openPicker(this.form.items.length - 1); },
      removeItem: function (idx) { this.form.items.splice(idx, 1); },
      openPicker: function (idx) { this.pendingIdx = idx; this.drugKeyword = ''; this.drugPage = 1; this.drugList = []; this.drugDlg = true; this.loadDrugs(); },
      loadDrugs: function () {
        var vm = this; vm.drugLoading = true;
        var q = '/api/his/stock/drug-catalog?page=' + vm.drugPage + '&size=' + vm.drugSize;
        if (vm.drugKeyword) { q += '&keyword=' + encodeURIComponent(vm.drugKeyword); }
        HIS.get(q).then(function (d) { vm.drugList = (d && d.records) || []; vm.drugTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.drugLoading = false; });
      },
      searchDrugs: function () { this.drugPage = 1; this.loadDrugs(); },
      onDrugPage: function (p) { this.drugPage = p; this.loadDrugs(); },
      pickDrug: function (d) {
        var it = this.form.items[this.pendingIdx];
        this.drugDlg = false;
        if (!it) { return; }
        it.drugCatalogId = d.id; it.drugCode = d.drugCode;
        it.drugName = d.genericName || d.tradeName || d.drugCode;
        it.spec = d.spec || '';
        it.oldPurchase = (d.purchasePrice != null) ? Number(d.purchasePrice) : null;
        it.oldRetail = (d.retailPrice != null) ? Number(d.retailPrice) : null;
      },
      validForm: function (needNew) {
        var vm = this;
        if (!vm.form.effectiveDate) { ElementPlus.ElMessage.warning('请选择生效日期'); return false; }
        if (!(vm.form.reason || '').trim()) { ElementPlus.ElMessage.warning('请填写调价原因'); return false; }
        var items = vm.form.items.filter(function (it) { return it.drugCatalogId; });
        if (!items.length) { ElementPlus.ElMessage.warning('请至少添加一个调价药品'); return false; }
        if (needNew) {
          for (var i = 0; i < items.length; i++) {
            var it = items[i];
            var hasNew = (it.newPurchase != null && it.newPurchase !== '') || (it.newRetail != null && it.newRetail !== '');
            if (!hasNew) { ElementPlus.ElMessage.warning('请为 ' + it.drugName + ' 至少填写新进价或新零售价(留空表示不调)'); return false; }
          }
        }
        return true;
      },
      buildItems: function () {
        return this.form.items.filter(function (it) { return it.drugCatalogId; }).map(function (it) {
          return {
            drugCatalogId: it.drugCatalogId,
            newPurchase: (it.newPurchase == null || it.newPurchase === '') ? null : Number(it.newPurchase),
            newRetail: (it.newRetail == null || it.newRetail === '') ? null : Number(it.newRetail)
          };
        });
      },
      preview: function () {
        var vm = this;
        if (!vm.validForm(true)) { return; }
        vm.previewing = true;
        HIS.post('/api/his/price-adjust/preview', { effectiveDate: vm.form.effectiveDate, reason: vm.form.reason, scope: 'DRUG', remark: '', items: vm.buildItems() })
          .then(function (d) {
            vm.previewRows = (d && d.rows) || [];
            vm.previewTotal = (d && d.totalDiffAmount != null) ? Number(d.totalDiffAmount) : 0;
            /* 回填在库量到表单行, 供实时金额影响展示 */
            var byId = {}; for (var i = 0; i < vm.previewRows.length; i++) { byId[vm.previewRows[i].drugCatalogId] = vm.previewRows[i]; }
            for (var j = 0; j < vm.form.items.length; j++) { var r = byId[vm.form.items[j].drugCatalogId]; if (r) { vm.form.items[j].impactStockQty = r.impactStockQty; } }
            HIS.notifySuccess('已预览 ' + vm.previewRows.length + ' 个药品的调价影响');
          }).catch(HIS.notifyError).finally(function () { vm.previewing = false; });
      },
      save: function () {
        var vm = this;
        if (!vm.validForm(true)) { return; }
        vm.saving = true;
        HIS.post('/api/his/price-adjust', { effectiveDate: vm.form.effectiveDate, reason: vm.form.reason, scope: 'DRUG', remark: '', items: vm.buildItems() })
          .then(function (d) { HIS.notifySuccess('调价草稿已保存, 单号 ' + ((d && d.adjustNo) || '') + ', 请在列表生效'); vm.createDlg = false; vm.load(); })
          .catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      confirm: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('生效后将更新所选药品的目录当前价与全租户在库零售价, 并写入调价留痕。确认生效 ' + row.adjustNo + '？', '调价生效', { type: 'warning', confirmButtonText: '确认生效', cancelButtonText: '取消' })
          .then(function () { return HIS.post('/api/his/price-adjust/' + row.id + '/confirm'); })
          .then(function () { HIS.notifySuccess('调价已生效'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      voidOne: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废该调价草稿? 作废后不可恢复。', '调价作废 - ' + row.adjustNo, { type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消' })
          .then(function () { return HIS.post('/api/his/price-adjust/' + row.id + '/void'); })
          .then(function () { HIS.notifySuccess('已作废'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      openDetail: function (row) {
        var vm = this;
        vm.detailDlg = true; vm.detailLoading = true; vm.detailMain = null; vm.detailItems = [];
        HIS.get('/api/his/price-adjust/' + row.id).then(function (d) {
          vm.detailMain = (d && d.main) || null; vm.detailItems = (d && d.items) || [];
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      paLabel: paLabel, paTag: paTag, money: money, fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药品调价 <span style="font-size:12px;color:#909399;font-weight:normal;">(批次调价 · 预览影响 → 存草稿 → 生效 · 仅牵头机构可维护)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:120px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="调价单号/原因" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新建调价单</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="adjustNo" label="调价单号" width="150"></el-table-column>',
      '    <el-table-column prop="effectiveDate" label="生效日期" width="110"></el-table-column>',
      '    <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="paTag(s.row.status)">{{ paLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="reason" label="调价原因" min-width="160" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="在库金额影响" width="130" align="right"><template #default="s">￥{{ money(s.row.totalDiffAmount) }}</template></el-table-column>',
      '    <el-table-column prop="operator" label="操作人" width="90"></el-table-column>',
      '    <el-table-column label="生效时间" width="160"><template #default="s">{{ fmtTime(s.row.effectTime) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="180" fixed="right"><template #default="s">',
      '      <el-button link type="info" size="small" @click="openDetail(s.row)">详情</el-button>',
      '      <template v-if="lead && s.row.status===0">',
      '        <el-button link type="success" size="small" @click="confirm(s.row)">生效</el-button>',
      '        <el-button link type="danger" size="small" @click="voidOne(s.row)">作废</el-button>',
      '      </template>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 新建调价对话框 ---- */
      '  <el-dialog v-model="createDlg" title="新建药品调价单" width="960px" top="5vh">',
      '    <div class="toolbar" style="margin-bottom:8px;">',
      '      <span style="font-weight:600;">生效日期</span>',
      '      <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:160px;"></el-date-picker>',
      '      <span style="font-weight:600;margin-left:12px;">调价原因</span>',
      '      <el-input v-model="form.reason" placeholder="如: 集采中选价调整" style="width:260px;"></el-input>',
      '    </div>',
      '    <el-table :data="form.items" border size="small" max-height="280">',
      '      <el-table-column type="index" label="#" width="45"></el-table-column>',
      '      <el-table-column label="药品" min-width="200"><template #default="s">',
      '        <span v-if="s.row.drugName">{{ s.row.drugName }} <span style="color:#909399;">{{ s.row.spec }}</span> <el-button link type="info" size="small" @click="openPicker(s.$index)">重选</el-button></span>',
      '        <el-button v-else link type="primary" @click="openPicker(s.$index)">选择药品</el-button>',
      '      </template></el-table-column>',
      '      <el-table-column label="原进价" width="90" align="right"><template #default="s">{{ money(s.row.oldPurchase) }}</template></el-table-column>',
      '      <el-table-column label="新进价" width="125"><template #default="s"><el-input-number v-model="s.row.newPurchase" :min="0" :precision="4" :controls="false" size="small" placeholder="不调" style="width:110px;"></el-input-number></template></el-table-column>',
      '      <el-table-column label="原零售价" width="90" align="right"><template #default="s">{{ money(s.row.oldRetail) }}</template></el-table-column>',
      '      <el-table-column label="新零售价" width="125"><template #default="s"><el-input-number v-model="s.row.newRetail" :min="0" :precision="4" :controls="false" size="small" placeholder="不调" style="width:110px;"></el-input-number></template></el-table-column>',
      '      <el-table-column label="在库量" width="80" align="right"><template #default="s">{{ s.row.impactStockQty != null ? s.row.impactStockQty : \'-\' }}</template></el-table-column>',
      '      <el-table-column label="操作" width="60"><template #default="s"><el-button link type="danger" size="small" @click="removeItem(s.$index)">删除</el-button></template></el-table-column>',
      '    </el-table>',
      '    <div class="toolbar" style="margin-top:8px;">',
      '      <el-button @click="addItem">+ 添加药品</el-button>',
      '      <el-button :loading="previewing" @click="preview">预览影响</el-button>',
      '      <span style="flex:1;"></span>',
      '      <span style="color:#909399;font-size:13px;">在库金额影响合计 ￥{{ money(diffTotal) }}</span>',
      '    </div>',
      '    <div v-if="previewRows.length" style="margin-top:10px;">',
      '      <el-divider content-position="left">预览结果(共 {{ previewRows.length }} 个药品 · 在库金额影响合计 ￥{{ money(previewTotal) }})</el-divider>',
      '      <el-table :data="previewRows" border size="small" max-height="200">',
      '        <el-table-column prop="drugName" label="药品" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="进价" width="140" align="right"><template #default="s">{{ money(s.row.oldPurchase) }} → {{ money(s.row.newPurchase) }}</template></el-table-column>',
      '        <el-table-column label="零售价" width="140" align="right"><template #default="s">{{ money(s.row.oldRetail) }} → {{ money(s.row.newRetail) }}</template></el-table-column>',
      '        <el-table-column prop="impactStockQty" label="在库量" width="80" align="right"></el-table-column>',
      '        <el-table-column label="零售金额影响" width="120" align="right"><template #default="s">￥{{ money(s.row.retailDiffAmount) }}</template></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="createDlg=false">取 消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 药品选择器 ---- */
      '  <el-dialog v-model="drugDlg" title="选择药品(医共体开展药品目录)" width="820px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-input v-model="drugKeyword" placeholder="药品名称/编码/拼音简码" clearable style="width:280px" @keyup.enter="searchDrugs"></el-input>',
      '      <el-button type="primary" @click="searchDrugs">查询</el-button>',
      '      <span style="flex:1;"></span><span style="color:#909399;font-size:12px;">共 {{ drugTotal }} 条</span>',
      '    </div>',
      '    <el-table :data="drugList" v-loading="drugLoading" border size="small" height="320">',
      '      <el-table-column prop="drugCode" label="编码" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="genericName" label="通用名" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="进价" width="90" align="right"><template #default="s">{{ s.row.purchasePrice }}</template></el-table-column>',
      '      <el-table-column label="零售价" width="90" align="right"><template #default="s">{{ s.row.retailPrice }}</template></el-table-column>',
      '      <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="pickDrug(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="drugTotal" :page-size="drugSize" :current-page="drugPage" @current-change="onDrugPage"></el-pagination>',
      '  </el-dialog>',
      /* ---- 详情对话框 ---- */
      '  <el-dialog v-model="detailDlg" title="调价单详情" width="860px" top="6vh">',
      '    <div v-loading="detailLoading">',
      '      <el-descriptions v-if="detailMain" :column="3" border size="small" style="margin-bottom:12px;">',
      '        <el-descriptions-item label="调价单号">{{ detailMain.adjustNo }}</el-descriptions-item>',
      '        <el-descriptions-item label="生效日期">{{ detailMain.effectiveDate }}</el-descriptions-item>',
      '        <el-descriptions-item label="状态">{{ paLabel(detailMain.status) }}</el-descriptions-item>',
      '        <el-descriptions-item label="调价原因" :span="2">{{ detailMain.reason || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="在库金额影响">￥{{ money(detailMain.totalDiffAmount) }}</el-descriptions-item>',
      '        <el-descriptions-item label="操作人">{{ detailMain.operator || \'-\' }}</el-descriptions-item>',
      '        <el-descriptions-item label="生效时间">{{ fmtTime(detailMain.effectTime) }}</el-descriptions-item>',
      '        <el-descriptions-item label="备注">{{ detailMain.remark || \'-\' }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-table :data="detailItems" border size="small" max-height="300">',
      '        <el-table-column type="index" label="#" width="45"></el-table-column>',
      '        <el-table-column prop="drugName" label="药品" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="进价" width="150" align="right"><template #default="s">{{ money(s.row.oldPurchase) }} → {{ money(s.row.newPurchase) }}</template></el-table-column>',
      '        <el-table-column label="零售价" width="150" align="right"><template #default="s">{{ money(s.row.oldRetail) }} → {{ money(s.row.newRetail) }}</template></el-table-column>',
      '        <el-table-column prop="impactStockQty" label="在库量" width="80" align="right"></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <template #footer><el-button @click="detailDlg=false">关 闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 进销存台账(药库/药房库存位通用, 只读) =================
   * 后端 GET /api/his/stock/ledger: 期初+入-出=期末 对账; warehouseId 传药房库存位 id 即为该药房库存台账;
   * 库位下拉复用 /api/his/transfer/locations(药库+药房库存位); 读走 scopeOrgId(前端不传 orgId);
   * 日期区间留空=全部历史(期末即当前快照); 药品关键字为前端行内过滤(接口按 drugCatalogId 精确过滤, 本页不强制); 导出 /export 同口径。
   */
  HIS.views.DrugLedger = {
    data: function () {
      return {
        loading: false, rows: [], summary: {}, exporting: false,
        locations: [], warehouseId: '', dateRange: [], drugKw: ''
      };
    },
    computed: {
      filteredRows: function () {
        var kw = (this.drugKw || '').trim().toLowerCase();
        if (!kw) { return this.rows; }
        return this.rows.filter(function (r) {
          return String(r.drugName || '').toLowerCase().indexOf(kw) >= 0 || String(r.drugCode || '').toLowerCase().indexOf(kw) >= 0;
        });
      },
      locName: function () {
        var id = this.warehouseId;
        if (id === '' || id === null || id === undefined) { return '全部库位'; }
        for (var i = 0; i < this.locations.length; i++) { if (this.locations[i].id === id) { return this.locations[i].name; } }
        return '库位#' + id;
      }
    },
    created: function () { this.loadLocations(); this.load(); },
    methods: {
      loadLocations: function () {
        var vm = this;
        HIS.get('/api/his/transfer/locations').then(function (list) { vm.locations = list || []; }).catch(HIS.notifyError);
      },
      buildQuery: function () {
        var vm = this; var parts = [];
        if (vm.warehouseId) { parts.push('warehouseId=' + vm.warehouseId); }
        if (vm.dateRange && vm.dateRange.length === 2) { parts.push('startDate=' + vm.dateRange[0]); parts.push('endDate=' + vm.dateRange[1]); }
        return parts.length ? ('?' + parts.join('&')) : '';
      },
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/stock/ledger' + vm.buildQuery()).then(function (d) {
          vm.rows = (d && d.rows) || []; vm.summary = (d && d.summary) || {};
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      doExport: function () {
        var vm = this; vm.exporting = true;
        HIS.download('/api/his/stock/ledger/export' + vm.buildQuery(), '进销存台账.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      seqNo: function (i) { return i + 1; },
      money: money
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">进销存台账 <span style="font-size:12px;color:#909399;font-weight:normal;">(期初+本期入-本期出=期末 · 选药房库存位即该药房库存台账 · 日期留空看全部历史)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="warehouseId" placeholder="全部库位" clearable filterable style="width:220px" @change="load">',
      '      <el-option v-for="l in locations" :key="l.id" :label="l.name + \'(\' + (l.kind===\'PHARMACY\'?\'药房\':\'药库\') + \')\'" :value="l.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px" @change="load"></el-date-picker>',
      '    <el-input v-model="drugKw" placeholder="药品名称/编码(行内过滤)" clearable style="width:220px"></el-input>',
      '    <el-button type="primary" @click="load">查询</el-button>',
      '    <el-button type="success" :loading="exporting" @click="doExport">导出</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">{{ locName }} · 共 {{ filteredRows.length }} 行</span>',
      '  </div>',
      '  <el-row :gutter="12" style="margin-bottom:10px;">',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">期初数量<br/><b>{{ summary.totalOpeningQty != null ? summary.totalOpeningQty : 0 }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">入库数量<br/><b style="color:#67c23a;">{{ summary.totalInQty != null ? summary.totalInQty : 0 }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">出库数量<br/><b style="color:#e6a23c;">{{ summary.totalOutQty != null ? summary.totalOutQty : 0 }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">期末数量<br/><b>{{ summary.totalClosingQty != null ? summary.totalClosingQty : 0 }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">入库金额(进价)<br/><b>￥{{ money(summary.totalInAmount) }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">出库金额(零售)<br/><b>￥{{ money(summary.totalOutAmount) }}</b></div></el-col>',
      '  </el-row>',
      '  <el-table :data="filteredRows" v-loading="loading" border stripe size="small" max-height="540">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="drugCode" label="药品编码" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="drugName" label="药品名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="openingQty" label="期初数量" width="90" align="right"></el-table-column>',
      '    <el-table-column prop="inQty" label="入库数量" width="90" align="right"></el-table-column>',
      '    <el-table-column label="入库金额" width="100" align="right"><template #default="s">￥{{ money(s.row.inAmount) }}</template></el-table-column>',
      '    <el-table-column prop="outQty" label="出库数量" width="90" align="right"></el-table-column>',
      '    <el-table-column label="出库金额" width="100" align="right"><template #default="s">￥{{ money(s.row.outAmount) }}</template></el-table-column>',
      '    <el-table-column prop="closingQty" label="期末数量" width="90" align="right"></el-table-column>',
      '    <el-table-column label="期末金额(进价)" width="120" align="right"><template #default="s">￥{{ money(s.row.closingCostAmount) }}</template></el-table-column>',
      '    <el-table-column label="当前零售价" width="110" align="right"><template #default="s">￥{{ money(s.row.retailPrice) }}</template></el-table-column>',
      '  </el-table>',
      '</div>'
    ].join('\n')
  };

  /* 追溯码状态: 0在库 1已发药 2已退货 3已报废/调拨在途 9已上报; 报送 upload_status 0未报送 9已报送 */
  var TRACE_STATUS = [
    { v: 0, l: '在库', t: 'info' },
    { v: 1, l: '已发药', t: 'primary' },
    { v: 2, l: '已退货', t: 'warning' },
    { v: 3, l: '报废/在途', t: 'danger' },
    { v: 9, l: '已上报', t: 'success' }
  ];
  var UPLOAD_STATUS = [{ v: 0, l: '未报送', t: 'info' }, { v: 9, l: '已报送', t: 'success' }];
  function traceLabel(v) { var s = findStatus(TRACE_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function traceTag(v) { var s = findStatus(TRACE_STATUS, v); return s ? s.t : 'info'; }
  function uploadLabel(v) { var s = findStatus(UPLOAD_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function uploadTag(v) { var s = findStatus(UPLOAD_STATUS, v); return s ? s.t : 'info'; }

  /* ================= 医保药品追溯码(药库/药房) =================
   * 后端 /api/his/trace: page/statistics/collect/bind/status/upload;
   * 采集与绑定为本机构药事过程: 写 requireSelfOrgWrite(不限牵头), 读 scopeOrgId;
   * 流程: 入库采集(扫描清单或批量占位码) → 在库 → 发药绑定(置已发药+患者/就诊/发药ID) → 退货/报废状态流转 → Mock 2404 报送;
   * 唯一键 tenant+trace_code(重复拦截); 库位复用 his_warehouse_def.id(药库或药房库存位)。
   */
  HIS.views.TraceCodeManage = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20, selection: [],
        filterLocation: '', filterStatus: null, filterUpload: null, filterBatch: '', keyword: '',
        locations: [], statusOpts: TRACE_STATUS, uploadOpts: UPLOAD_STATUS,
        stats: { total: 0, uploaded: 0, pendingUpload: 0, byStatus: {} },
        collectDlg: false, collecting: false,
        cform: { locationId: null, drugCatalogId: null, drugCode: '', drugName: '', batchNo: '', codesText: '', autoQty: 0 },
        drugDlg: false, drugLoading: false, drugList: [], drugTotal: 0, drugPage: 1, drugSize: 10, drugKeyword: '',
        bindDlg: false, binding: false,
        bform: { dispenseId: null, patientId: null, visitId: null, requiredPackQty: null },
        uploading: false
      };
    },
    created: function () { this.loadLocations(); this.load(); this.loadStats(); },
    methods: {
      loadLocations: function () {
        var vm = this;
        HIS.get('/api/his/transfer/locations').then(function (list) { vm.locations = list || []; }).catch(HIS.notifyError);
      },
      loadStats: function () {
        var vm = this;
        var q = '/api/his/trace/statistics' + (vm.filterLocation ? ('?locationId=' + vm.filterLocation) : '');
        HIS.get(q).then(function (d) { vm.stats = d || { total: 0, uploaded: 0, pendingUpload: 0, byStatus: {} }; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var parts = ['page=' + vm.page, 'size=' + vm.size];
        if (vm.filterLocation) { parts.push('locationId=' + vm.filterLocation); }
        if (vm.filterStatus !== null && vm.filterStatus !== '' && vm.filterStatus !== undefined) { parts.push('status=' + vm.filterStatus); }
        if (vm.filterUpload !== null && vm.filterUpload !== '' && vm.filterUpload !== undefined) { parts.push('uploadStatus=' + vm.filterUpload); }
        if (vm.filterBatch) { parts.push('batchNo=' + encodeURIComponent(vm.filterBatch)); }
        if (vm.keyword) { parts.push('keyword=' + encodeURIComponent(vm.keyword)); }
        HIS.get('/api/his/trace/page?' + parts.join('&')).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSelection: function (rows) { this.selection = rows || []; },
      selectedCodes: function () { return this.selection.map(function (r) { return r.traceCode; }); },
      /* ---- 采集 ---- */
      openCollect: function () { this.cform = { locationId: this.filterLocation || null, drugCatalogId: null, drugCode: '', drugName: '', batchNo: '', codesText: '', autoQty: 0 }; this.collectDlg = true; },
      parseCodes: function () {
        var t = this.cform.codesText || '';
        return t.split(/[\s,;、]+/).map(function (x) { return x.trim(); }).filter(function (x) { return x.length; });
      },
      openDrugPicker: function () { this.drugKeyword = ''; this.drugPage = 1; this.drugList = []; this.drugDlg = true; this.loadDrugs(); },
      loadDrugs: function () {
        var vm = this; vm.drugLoading = true;
        var q = '/api/his/stock/drug-catalog?page=' + vm.drugPage + '&size=' + vm.drugSize;
        if (vm.drugKeyword) { q += '&keyword=' + encodeURIComponent(vm.drugKeyword); }
        HIS.get(q).then(function (d) { vm.drugList = (d && d.records) || []; vm.drugTotal = Number((d && d.total) || 0); })
          .catch(HIS.notifyError).finally(function () { vm.drugLoading = false; });
      },
      searchDrugs: function () { this.drugPage = 1; this.loadDrugs(); },
      onDrugPage: function (p) { this.drugPage = p; this.loadDrugs(); },
      pickDrug: function (d) {
        this.cform.drugCatalogId = d.id; this.cform.drugCode = d.drugCode;
        this.cform.drugName = d.genericName || d.tradeName || d.drugCode;
        this.drugDlg = false;
      },
      submitCollect: function () {
        var vm = this;
        if (!vm.cform.locationId) { ElementPlus.ElMessage.warning('请选择库位'); return; }
        if (!vm.cform.drugCatalogId) { ElementPlus.ElMessage.warning('请选择药品'); return; }
        if (!(vm.cform.batchNo || '').trim()) { ElementPlus.ElMessage.warning('请填写批次号'); return; }
        var codes = vm.parseCodes();
        if (!codes.length && !(Number(vm.cform.autoQty) > 0)) { ElementPlus.ElMessage.warning('请录入追溯码清单, 或填写自动生成数量'); return; }
        vm.collecting = true;
        HIS.post('/api/his/trace/collect', {
          locationId: vm.cform.locationId, drugCatalogId: vm.cform.drugCatalogId, batchNo: vm.cform.batchNo.trim(),
          codes: codes.length ? codes : null, autoGenerateQty: codes.length ? null : Number(vm.cform.autoQty)
        }).then(function (d) {
          HIS.notifySuccess('采集完成: 新增 ' + ((d && d.collected) || 0) + ' , 重复 ' + ((d && d.duplicated) || 0));
          vm.collectDlg = false; vm.load(); vm.loadStats();
        }).catch(HIS.notifyError).finally(function () { vm.collecting = false; });
      },
      /* ---- 发药绑定 ---- */
      openBind: function () {
        var codes = this.selectedCodes();
        if (!codes.length) { ElementPlus.ElMessage.warning('请先勾选待绑定的在库追溯码'); return; }
        var notIn = this.selection.filter(function (r) { return r.status !== 0; }).length;
        if (notIn) { ElementPlus.ElMessage.warning('仅「在库」码可绑定, 选中含 ' + notIn + ' 条非在库码将被后端忽略'); }
        this.bform = { dispenseId: null, patientId: null, visitId: null, requiredPackQty: null };
        this.bindDlg = true;
      },
      submitBind: function () {
        var vm = this;
        var codes = vm.selectedCodes();
        if (!codes.length) { ElementPlus.ElMessage.warning('无选中追溯码'); return; }
        vm.binding = true;
        HIS.post('/api/his/trace/bind', {
          traceCodes: codes, dispenseId: vm.bform.dispenseId || null, patientId: vm.bform.patientId || null,
          visitId: vm.bform.visitId || null, requiredPackQty: (vm.bform.requiredPackQty === null || vm.bform.requiredPackQty === '') ? null : Number(vm.bform.requiredPackQty)
        }).then(function (d) {
          var msg = '绑定成功 ' + ((d && d.bound) || 0) + ' 条';
          if (d && d.missing && d.missing.length) { msg += ', 缺失 ' + d.missing.length + ' 条'; }
          if (d && d.mismatch) { msg += ' (码数与应发最小包装数不符, 仅提示)'; }
          HIS.notifySuccess(msg); vm.bindDlg = false; vm.load(); vm.loadStats();
        }).catch(HIS.notifyError).finally(function () { vm.binding = false; });
      },
      /* ---- 状态流转 ---- */
      changeStatus: function (target, label) {
        var vm = this;
        var codes = vm.selectedCodes();
        if (!codes.length) { ElementPlus.ElMessage.warning('请先勾选追溯码'); return; }
        ElementPlus.ElMessageBox.confirm('确认将选中的 ' + codes.length + ' 条追溯码置为「' + label + '」?', '追溯码状态流转', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/trace/status', { traceCodes: codes, status: target }); })
          .then(function (n) { HIS.notifySuccess('已更新 ' + (n || 0) + ' 条'); vm.load(); vm.loadStats(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ---- Mock 报送 ---- */
      doUpload: function () {
        var vm = this;
        var q = vm.filterLocation ? ('?locationId=' + vm.filterLocation) : '';
        ElementPlus.ElMessageBox.confirm('确认对' + (vm.filterLocation ? '当前库位' : '全部库位') + '已发药未报送的追溯码执行 Mock 2404 报送?', '追溯码报送', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/trace/upload' + q); })
          .then(function (d) { HIS.notifySuccess('已报送 ' + ((d && d.uploaded) || 0) + ' 条, 回执 ' + ((d && d.receipt) || '')); vm.load(); vm.loadStats(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      locationName: function (id) {
        if (id === null || id === undefined || id === '') { return '-'; }
        for (var i = 0; i < this.locations.length; i++) { if (this.locations[i].id === id) { return this.locations[i].name; } }
        return '库位#' + id;
      },
      onFilterChange: function () { this.loadStats(); this.search(); },
      reload: function () { this.load(); this.loadStats(); },
      traceLabel: traceLabel, traceTag: traceTag, uploadLabel: uploadLabel, uploadTag: uploadTag, fmtTime: fmtTime
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医保药品追溯码 <span style="font-size:12px;color:#909399;font-weight:normal;">(入库采集 → 发药绑定 → 状态流转 → Mock报送 · 本机构可维护)</span></div>',
      '  <el-row :gutter="12" style="margin-bottom:10px;">',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">追溯码总数<br/><b>{{ stats.total }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">已报送<br/><b style="color:#67c23a;">{{ stats.uploaded }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">待报送<br/><b style="color:#e6a23c;">{{ stats.pendingUpload }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">在库<br/><b>{{ (stats.byStatus && stats.byStatus.status_0) || 0 }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">已发药<br/><b>{{ (stats.byStatus && stats.byStatus.status_1) || 0 }}</b></div></el-col>',
      '    <el-col :span="4"><div class="page-card" style="padding:10px;text-align:center;">退货/报废<br/><b>{{ ((stats.byStatus && stats.byStatus.status_2) || 0) + ((stats.byStatus && stats.byStatus.status_3) || 0) }}</b></div></el-col>',
      '  </el-row>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterLocation" placeholder="全部库位" clearable filterable style="width:200px" @change="onFilterChange">',
      '      <el-option v-for="l in locations" :key="l.id" :label="l.name + \'(\' + (l.kind===\'PHARMACY\'?\'药房\':\'药库\') + \')\'" :value="l.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:120px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-select v-model="filterUpload" placeholder="全部报送" clearable style="width:120px" @change="search">',
      '      <el-option v-for="u in uploadOpts" :key="u.v" :label="u.l" :value="u.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="filterBatch" placeholder="批次号" clearable style="width:130px" @keyup.enter="search"></el-input>',
      '    <el-input v-model="keyword" placeholder="追溯码" clearable style="width:180px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="reload">刷新</el-button>',
      '  </div>',
      '  <div class="toolbar" style="margin-bottom:6px;">',
      '    <el-button type="primary" @click="openCollect">追溯码采集</el-button>',
      '    <el-button type="success" :disabled="!selection.length" @click="openBind">发药绑定(选中{{ selection.length }})</el-button>',
      '    <el-button :disabled="!selection.length" @click="changeStatus(0, \'回库\')">回库</el-button>',
      '    <el-button :disabled="!selection.length" @click="changeStatus(2, \'已退货\')">退货</el-button>',
      '    <el-button :disabled="!selection.length" @click="changeStatus(3, \'报废/在途\')">报废/在途</el-button>',
      '    <el-button type="warning" :loading="uploading" @click="doUpload">Mock 2404 报送</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" @selection-change="onSelection">',
      '    <el-table-column type="selection" width="45"></el-table-column>',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="traceCode" label="追溯码" min-width="180" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="drugCode" label="药品编码" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="batchNo" label="批次" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="库位" width="120"><template #default="s">{{ locationName(s.row.locationId) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="100" align="center"><template #default="s"><el-tag size="small" :type="traceTag(s.row.status)">{{ traceLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="报送" width="90" align="center"><template #default="s"><el-tag size="small" :type="uploadTag(s.row.uploadStatus)">{{ uploadLabel(s.row.uploadStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="患者/就诊/发药" width="140"><template #default="s">{{ s.row.patientId || \'-\' }} / {{ s.row.visitId || \'-\' }} / {{ s.row.dispenseId || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="报送时间" width="160"><template #default="s">{{ s.row.uploadTime || \'-\' }}</template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 采集对话框 ---- */
      '  <el-dialog v-model="collectDlg" title="追溯码采集" width="620px">',
      '    <el-form label-width="90px">',
      '      <el-form-item label="库位" required>',
      '        <el-select v-model="cform.locationId" placeholder="药库/药房库存位" filterable style="width:100%">',
      '          <el-option v-for="l in locations" :key="l.id" :label="l.name + \'(\' + (l.kind===\'PHARMACY\'?\'药房\':\'药库\') + \')\'" :value="l.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="药品" required>',
      '        <el-input v-model="cform.drugName" readonly placeholder="选择药品" style="width:78%;">',
      '          <template #append><el-button @click="openDrugPicker">选择</el-button></template>',
      '        </el-input>',
      '      </el-form-item>',
      '      <el-form-item label="批次号" required><el-input v-model="cform.batchNo" placeholder="如 B20260101" maxlength="64" style="width:70%;"></el-input></el-form-item>',
      '      <el-form-item label="追溯码清单"><el-input v-model="cform.codesText" type="textarea" :rows="4" placeholder="每行一个(空格/逗号分隔亦可); 无扫码器时可用下方自动生成"></el-input></el-form-item>',
      '      <el-form-item label="自动生成"><el-input-number v-model="cform.autoQty" :min="0" :max="500" controls-position="right" style="width:140px"></el-input-number><span style="color:#909399;font-size:12px;margin-left:8px;">清单为空时按此数量生成占位码</span></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="collectDlg=false">取 消</el-button><el-button type="primary" :loading="collecting" @click="submitCollect">确认采集</el-button></template>',
      '  </el-dialog>',
      /* ---- 绑定对话框 ---- */
      '  <el-dialog v-model="bindDlg" title="追溯码发药绑定" width="520px">',
      '    <el-alert type="info" :closable="false" show-icon :title="\'已选中 \' + selection.length + \' 条追溯码, 将绑定到以下发药信息并置为已发药\'" style="margin-bottom:12px;"></el-alert>',
      '    <el-form label-width="110px">',
      '      <el-form-item label="发药记录ID"><el-input v-model="bform.dispenseId" placeholder="可选"></el-input></el-form-item>',
      '      <el-form-item label="患者ID"><el-input v-model="bform.patientId" placeholder="可选"></el-input></el-form-item>',
      '      <el-form-item label="就诊ID"><el-input v-model="bform.visitId" placeholder="可选"></el-input></el-form-item>',
      '      <el-form-item label="应发最小包数"><el-input v-model="bform.requiredPackQty" placeholder="可选: 与码数比对仅软提示"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="bindDlg=false">取 消</el-button><el-button type="primary" :loading="binding" @click="submitBind">确认绑定</el-button></template>',
      '  </el-dialog>',
      /* ---- 选药器 ---- */
      '  <el-dialog v-model="drugDlg" title="选择药品" width="760px" top="8vh">',
      '    <div class="toolbar"><el-input v-model="drugKeyword" placeholder="药品名称/编码/拼音" clearable style="width:260px" @keyup.enter="searchDrugs"></el-input><el-button type="primary" @click="searchDrugs">查询</el-button></div>',
      '    <el-table :data="drugList" v-loading="drugLoading" border size="small" height="300">',
      '      <el-table-column prop="drugCode" label="编码" width="110"></el-table-column>',
      '      <el-table-column prop="genericName" label="通用名" min-width="160" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="pickDrug(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" small background layout="total, prev, pager, next" :total="drugTotal" :page-size="drugSize" :current-page="drugPage" @current-change="onDrugPage"></el-pagination>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
