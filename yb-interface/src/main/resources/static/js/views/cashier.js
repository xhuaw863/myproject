/* 收费结算台: 待收费(收费结算/收据预览) + 收费记录(退费/导出) + 退费记录 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量: 明细项目类型 / 单状态 / 险种 ===== */
  var ITEM_TYPES = { 1: '药品费', 2: '检查费', 3: '治疗费', 4: '材料费' };
  var ITEM_TYPE_TAGS = { 1: 'danger', 2: 'warning', 3: 'success', 4: 'info' };
  var INSU_TYPES = { '310': '310-职工医保', '390': '390-居民医保', '340': '340-工伤保险', '391': '391-城乡居民' };
  var STATUS_LIST = [{ v: 1, l: '已收费' }, { v: 2, l: '已退费' }];

  /* ===== 工具函数 ===== */
  function money(v) { return (v === null || v === undefined || v === '') ? '0.00' : Number(v).toFixed(2); }
  function money4(v) { return (v === null || v === undefined || v === '') ? '0.0000' : Number(v).toFixed(4); }
  /* 数量: 去掉小数尾零(如 2.000 -> 2, 1.500 -> 1.5) */
  function qtyFmt(v) { var n = Number(v) || 0; return String(parseFloat(n.toFixed(3))); }
  function fYen(v) { return '¥' + money(v); }
  /* 自付比例(0-1) -> 百分比 */
  function pct(v) { var n = Number(v) || 0; if (n <= 0) { return '0%'; } return (+(n * 100).toFixed(2)) + '%'; }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function itemTypeLabel(t) { return ITEM_TYPES[t] || '其他'; }
  function itemTypeTag(t) { return ITEM_TYPE_TAGS[t] || 'info'; }
  function insuLabel(v) { return INSU_TYPES[v] || v || '-'; }
  /* 退费单备注解析: "原单:SFxxx; 退费原因:yyy" */
  function parseOrigBillNo(remark) {
    var m = /原单[:：]\s*([^;；\s]+)/.exec(remark || '');
    return m ? m[1] : '-';
  }
  function parseRefundReason(remark) {
    var m = /退费原因[:：]\s*([^;]+)/.exec(remark || '');
    return m ? m[1].trim() : '-';
  }
  /* 行状态(结合单据类型): 退费单直接展示"已退费" */
  function rowStatusLabel(row) {
    if (row.billType === 2) { return '已退费'; }
    return row.status === 0 ? '待收费' : (row.status === 1 ? '已收费' : '已退费');
  }
  function rowStatusTag(row) {
    if (row.billType === 2) { return 'danger'; }
    return row.status === 1 ? 'success' : (row.status === 2 ? 'danger' : 'info');
  }
  /* 明细按项目类型(1药品/2检查/3治疗/4材料)排序, 供分组展示 */
  function sortByType(items) {
    var arr = (items || []).slice();
    arr.sort(function (a, b) { return (a.itemType || 9) - (b.itemType || 9); });
    return arr;
  }

  /* ===== 收据预览共享块(ChargeTodo 收费成功直接展示 / ChargeSetl、ChargeRefund 按单号拉取) ===== */
  var receiptMixin = {
    methods: {
      /* 按收费单ID拉取收据数据 {bill, items, patient} */
      openReceiptOf: function (billId) {
        var vm = this;
        vm.receiptData = null; vm.receiptVisible = true;
        HIS.get('/api/his/cashier/receipt/' + billId)
          .then(function (d) { vm.receiptData = d; })
          .catch(HIS.notifyError);
      },
      rcpBill: function () { return (this.receiptData && this.receiptData.bill) || {}; },
      rcpPatient: function () { return (this.receiptData && this.receiptData.patient) || {}; },
      rcpItems: function () { return (this.receiptData && this.receiptData.items) || []; },
      /* 收据金额: 退费单加负号 */
      rcpSigned: function (v) { return (this.rcpBill().billType === 2 ? '-¥' : '¥') + money(v); },
      rcpPatientLine: function () {
        var p = this.rcpPatient();
        var s = (p.name || '-') + '　' + (p.gender || '-');
        if (p.age !== null && p.age !== undefined && p.age !== '') { s += '　' + p.age + '岁'; }
        return s;
      },
      hospitalName: function () {
        var u = HIS.getUser() || {};
        return u.tenantName || u.orgName || '医院';
      },
      orgNameOf: function () { var u = HIS.getUser() || {}; return u.orgName || u.tenantName || '-'; }
    }
  };

  /* 收据打印样式模板(仿真纸质收据: 双线框/虚线分隔/明细表/支付构成) */
  function receiptTpl() {
    return [
      '<el-dialog v-model="receiptVisible" title="收费收据" width="720px" top="3vh">',
      '  <div v-if="!receiptData" style="height:220px;" v-loading="true" element-loading-text="加载收据..."></div>',
      '  <div v-else style="border:1px solid #33404f;padding:20px 28px;background:#fffef9;color:#1f2430;border-radius:2px;">',
      '    <div style="text-align:center;font-size:20px;font-weight:700;letter-spacing:3px;font-family:\'Microsoft YaHei\',sans-serif;">{{ hospitalName() }}</div>',
      '    <div style="text-align:center;font-size:17px;font-weight:600;letter-spacing:12px;margin:10px 0 2px;">门诊收费收据</div>',
      '    <div v-if="rcpBill().billType===2" style="text-align:center;font-size:12px;color:#e6a23c;letter-spacing:6px;">—— 退 费 凭 证 ——</div>',
      '    <div style="border-top:3px double #33404f;margin:12px 0 10px;"></div>',
      '    <table style="width:100%;font-size:13px;line-height:2;font-family:SimSun,serif;">',
      '      <tr><td>单号: {{ orDash(rcpBill().billNo) }}</td><td style="text-align:right;">时间: {{ orDash(rcpBill().chargeTime) }}</td></tr>',
      '      <tr><td>患者: {{ rcpPatientLine() }}</td><td style="text-align:right;">身份证: {{ orDash(rcpPatient().idCard) }}</td></tr>',
      '      <tr><td>就诊号: {{ orDash(rcpPatient().visitNo) }}</td><td style="text-align:right;">医保就诊ID: {{ orDash(rcpPatient().mdtrtId) }}</td></tr>',
      '    </table>',
      '    <div style="border-top:1px dashed #8a94a6;margin:10px 0;"></div>',
      '    <table style="width:100%;font-size:13px;border-collapse:collapse;font-family:SimSun,serif;">',
      '      <thead><tr style="border-bottom:1px solid #33404f;">',
      '        <th style="text-align:left;padding:4px 0;">项目</th>',
      '        <th style="text-align:right;width:70px;">数量</th>',
      '        <th style="text-align:right;width:90px;">单价</th>',
      '        <th style="text-align:right;width:100px;">金额</th>',
      '      </tr></thead>',
      '      <tbody>',
      '        <tr v-for="(it,i) in rcpItems()" :key="i">',
      '          <td style="padding:3px 0;">{{ it.itemName }}<span v-if="it.spec" style="color:#7a8494;font-size:12px;">　{{ it.spec }}</span></td>',
      '          <td style="text-align:right;">{{ qtyFmt(it.qty) }}</td>',
      '          <td style="text-align:right;">{{ money4(it.price) }}</td>',
      '          <td style="text-align:right;">{{ money(it.amount) }}</td>',
      '        </tr>',
      '        <tr v-if="!rcpItems().length"><td colspan="4" style="text-align:center;color:#8a94a6;">无明细</td></tr>',
      '      </tbody>',
      '    </table>',
      '    <div style="border-top:1px solid #33404f;margin-top:8px;padding-top:8px;display:flex;justify-content:space-between;font-size:16px;font-weight:700;">',
      '      <span>{{ rcpBill().billType===2 ? \'退费金额\' : \'合计金额\' }}</span>',
      '      <span style="color:#c45656;">{{ rcpSigned(rcpBill().totalAmount) }}</span>',
      '    </div>',
      '    <div style="border-top:1px dashed #8a94a6;margin:10px 0;"></div>',
      '    <table style="width:100%;font-size:13px;font-family:SimSun,serif;line-height:2;">',
      '      <tr><td>基金支付: {{ rcpSigned(rcpBill().fundPay) }}</td><td>个账支付: {{ rcpSigned(rcpBill().acctPay) }}</td></tr>',
      '      <tr><td>现金支付: {{ rcpSigned(rcpBill().cashPay) }}</td><td>个人自付: {{ rcpSigned(rcpBill().selfPay) }}</td></tr>',
      '      <tr><td colspan="2">医保结算ID: {{ orDash(rcpBill().setlId) }}</td></tr>',
      '    </table>',
      '    <div style="border-top:1px dashed #8a94a6;margin:10px 0;"></div>',
      '    <table style="width:100%;font-size:13px;font-family:SimSun,serif;line-height:2;">',
      '      <tr><td>收费员: {{ orDash(rcpBill().chargeBy) }}</td><td style="text-align:right;">{{ rcpBill().billType===2 ? \'退费\' : \'收费\' }}时间: {{ orDash(rcpBill().chargeTime) }}</td></tr>',
      '      <tr><td>{{ rcpBill().billType===2 ? \'退费机构\' : \'收费机构\' }}: {{ orgNameOf() }}</td><td style="text-align:right;">单号: {{ orDash(rcpBill().billNo) }}</td></tr>',
      '    </table>',
      '    <div style="border-top:3px double #33404f;margin:12px 0 8px;"></div>',
      '    <div style="text-align:center;font-size:11px;color:#8a94a6;letter-spacing:1px;">此收据为门急诊收费凭证, 请妥善保存; 退费须凭本收据办理</div>',
      '  </div>',
      '  <template #footer><el-button @click="receiptVisible=false">关闭</el-button></template>',
      '</el-dialog>'
    ].join('\n');
  }

  /* ================= 待收费 ================= */
  HIS.views.ChargeTodo = {
    mixins: [receiptMixin],
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        keyword: '',
        /* 收费对话框 */
        chargeVisible: false, chargeLoading: false, charging: false,
        currentVisit: null, chargePatient: {}, chargeItems: [], chargeSummary: {},
        payType: 'yb',
        /* 收据 */
        receiptVisible: false, receiptData: null,
        /* 定时器 */
        refreshTimer: null
      };
    },
    created: function () { this.load(); this.startAutoRefresh(); },
    beforeUnmount: function () { this.stopAutoRefresh(); },
    beforeDestroy: function () { this.stopAutoRefresh(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/cashier/todo?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 打开收费对话框: 拉取费用明细(处方+检查单汇总) */
      openCharge: function (row) {
        var vm = this;
        vm.currentVisit = row;
        vm.chargeVisible = true;
        vm.chargeLoading = true;
        vm.payType = 'yb';
        vm.chargePatient = {}; vm.chargeItems = []; vm.chargeSummary = {};
        HIS.get('/api/his/cashier/bill/' + row.visit_id).then(function (d) {
          vm.chargePatient = (d && d.patient) || {};
          vm.chargeItems = sortByType((d && d.items) || []);
          vm.chargeSummary = (d && d.summary) || {};
        }).catch(HIS.notifyError).finally(function () { vm.chargeLoading = false; });
      },
      /* 明细表分组: 相同项目类型纵向合并"项目类型"列 */
      groupSpan: function (p) {
        if (!p.column || p.column.label !== '项目类型') { return; }
        var list = this.chargeItems;
        var cur = list[p.rowIndex];
        var prev = p.rowIndex > 0 ? list[p.rowIndex - 1] : null;
        if (prev && prev.itemType === cur.itemType) { return { rowspan: 0, colspan: 0 }; }
        var n = 1;
        for (var i = p.rowIndex + 1; i < list.length; i++) {
          if (list[i].itemType === cur.itemType) { n++; } else { break; }
        }
        return { rowspan: n, colspan: 1 };
      },
      /* 合计行: 数量合计 + 金额合计(取后端汇总) */
      summaryMethod: function (param) {
        var vm = this;
        return param.columns.map(function (c, i) {
          if (i === 0) { return '合计'; }
          if (c.label === '数量') {
            var q = 0;
            for (var k = 0; k < vm.chargeItems.length; k++) { q += Number(vm.chargeItems[k].qty) || 0; }
            return qtyFmt(q);
          }
          if (c.label === '金额') { return money(vm.chargeSummary.totalAmount); }
          return '';
        });
      },
      /* 确认收费: 二次确认 -> 医保结算(2206+2207) 或 自费 -> 展示收据 */
      doCharge: function () {
        var vm = this;
        if (!vm.currentVisit) { return; }
        if (!vm.chargeItems.length) { ElementPlus.ElMessage.warning('该就诊无待收费费用明细'); return; }
        var method = vm.payType === 'yb' ? '医保结算' : '自费/现金';
        var msg = '患者: <b>' + orDash(vm.chargePatient.name) + '</b><br/>'
          + '待收金额: <b style="color:#F56C6C;font-size:15px;">' + fYen(vm.chargeSummary.totalAmount) + '</b><br/>'
          + '结算方式: <b>' + method + '</b><br/><br/>确认对该就诊执行收费?';
        ElementPlus.ElMessageBox.confirm(msg, '收费确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认收费', cancelButtonText: '取消'
        }).then(function () {
          vm.charging = true;
          var url = vm.payType === 'yb' ? '/api/his/cashier/charge' : '/api/his/cashier/self-pay';
          var u = HIS.getUser() || {};
          return HIS.post(url, { visitId: vm.currentVisit.visit_id, orgId: u.orgId, payType: vm.payType })
            .then(function (d) {
              HIS.notifySuccess('收费成功, 单号: ' + ((d && d.bill && d.bill.billNo) || ''));
              vm.chargeVisible = false;
              vm.receiptData = (d && d.receipt) || null;
              if (vm.receiptData) { vm.receiptVisible = true; }
              vm.load();
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.charging = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      startAutoRefresh: function () {
        var vm = this;
        this.stopAutoRefresh();
        this.refreshTimer = setInterval(function () { vm.load(); }, 30000);
      },
      stopAutoRefresh: function () {
        if (this.refreshTimer) { clearInterval(this.refreshTimer); this.refreshTimer = null; }
      },
      money: money, money4: money4, qtyFmt: qtyFmt, fYen: fYen, orDash: orDash, pct: pct,
      itemTypeLabel: itemTypeLabel, itemTypeTag: itemTypeTag, insuLabel: insuLabel
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">待收费 <span style="font-size:12px;color:#909399;font-weight:normal;">(已完成接诊未收费的就诊; 收费后进入收费记录)</span></div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="患者姓名/就诊号" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 人</span>',
      '    <span style="color:#909399;font-size:12px;margin-left:auto;">列表每 30 秒自动刷新</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="visit_no" label="就诊号" width="130"></el-table-column>',
      '    <el-table-column prop="patient_name" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column prop="id_card" label="身份证号" width="160" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="dept_name" label="科室" width="120"></el-table-column>',
      '    <el-table-column prop="doctor_name" label="医生" width="100"></el-table-column>',
      '    <el-table-column prop="visit_time" label="就诊时间" width="160"></el-table-column>',
      '    <el-table-column label="待收金额" width="110" align="right"><template #default="s"><span style="color:#F56C6C;font-weight:700;">¥{{ money(s.row.total_amount) }}</span></template></el-table-column>',
      '    <el-table-column label="操作" width="100" fixed="right"><template #default="s">',
      '      <el-button type="primary" size="small" @click="openCharge(s.row)">收费</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 收费结算对话框 ---- */
      '  <el-dialog v-model="chargeVisible" title="收费结算" width="900px" top="5vh">',
      '    <div v-loading="chargeLoading" element-loading-text="加载费用明细..." style="min-height:140px;">',
      '      <el-descriptions :column="4" border size="small" style="margin-bottom:14px;">',
      '        <el-descriptions-item label="姓名">{{ orDash(chargePatient.name) }}</el-descriptions-item>',
      '        <el-descriptions-item label="性别">{{ orDash(chargePatient.gender) }}</el-descriptions-item>',
      '        <el-descriptions-item label="年龄">{{ orDash(chargePatient.age) }}</el-descriptions-item>',
      '        <el-descriptions-item label="参保类型">{{ insuLabel(chargePatient.insuType) }}</el-descriptions-item>',
      '        <el-descriptions-item label="身份证号" :span="2">{{ orDash(chargePatient.idCard) }}</el-descriptions-item>',
      '        <el-descriptions-item label="就诊号" :span="2">{{ orDash(chargePatient.visitNo) }}</el-descriptions-item>',
      '        <el-descriptions-item label="科室">{{ orDash(chargePatient.deptName) }}</el-descriptions-item>',
      '        <el-descriptions-item label="医生">{{ orDash(chargePatient.drName) }}</el-descriptions-item>',
      '        <el-descriptions-item label="医保就诊ID" :span="2">{{ orDash(chargePatient.mdtrtId) }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-table :data="chargeItems" border size="small" max-height="300" :span-method="groupSpan" show-summary :summary-method="summaryMethod">',
      '        <el-table-column label="项目类型" width="86" align="center"><template #default="s"><el-tag size="small" :type="itemTypeTag(s.row.itemType)">{{ itemTypeLabel(s.row.itemType) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="itemCode" label="项目编码" width="100"></el-table-column>',
      '        <el-table-column prop="itemName" label="项目名称" width="180" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="数量" width="70" align="right"><template #default="s">{{ qtyFmt(s.row.qty) }}</template></el-table-column>',
      '        <el-table-column label="单价" width="90" align="right"><template #default="s">{{ money4(s.row.price) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="100" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '        <el-table-column label="医保编码" width="120"><template #default="s">{{ orDash(s.row.medListCodg) }}</template></el-table-column>',
      '        <el-table-column label="自付比例" width="80" align="right"><template #default="s">{{ pct(s.row.ratio) }}</template></el-table-column>',
      '      </el-table>',
      '      <el-descriptions :column="5" border style="margin-top:14px;">',
      '        <el-descriptions-item label="总金额"><span style="color:#F56C6C;font-size:20px;font-weight:700;">{{ fYen(chargeSummary.totalAmount) }}</span></el-descriptions-item>',
      '        <el-descriptions-item label="药品费">{{ fYen(chargeSummary.drugAmount) }}</el-descriptions-item>',
      '        <el-descriptions-item label="检查费">{{ fYen(chargeSummary.examAmount) }}</el-descriptions-item>',
      '        <el-descriptions-item label="治疗费">{{ fYen(chargeSummary.treatAmount) }}</el-descriptions-item>',
      '        <el-descriptions-item label="材料费">{{ fYen(chargeSummary.materialAmount) }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <div style="display:flex;align-items:center;gap:12px;margin-top:16px;">',
      '        <span style="font-weight:600;color:#303133;">结算方式：</span>',
      '        <el-radio-group v-model="payType">',
      '          <el-radio label="yb">医保结算</el-radio>',
      '          <el-radio label="self">自费/现金</el-radio>',
      '        </el-radio-group>',
      '        <span style="color:#909399;font-size:12px;">医保结算将调用 2206 预结算与 2207 结算; 自费不走医保通道</span>',
      '      </div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="chargeVisible=false">取消</el-button>',
      '      <el-button type="primary" :loading="charging" @click="doCharge">确认收费</el-button>',
      '    </template>',
      '  </el-dialog>',
      receiptTpl(),
      '</div>'
    ].join('\n')
  };

  /* ================= 收费/结算记录 ================= */
  HIS.views.ChargeSetl = {
    mixins: [receiptMixin],
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        filterStatus: null, dateRange: [], keyword: '',
        statusOpts: STATUS_LIST,
        /* 退费对话框 */
        refundVisible: false, refundBill: null, refundReason: '', refunding: false,
        /* 收据 */
        receiptVisible: false, receiptData: null
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/cashier/bills?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterStatus !== null && vm.filterStatus !== undefined && vm.filterStatus !== '') { q += '&status=' + vm.filterStatus; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      billTypeLabel: function (t) { return t === 2 ? '退费' : '收费'; },
      billTypeTag: function (t) { return t === 2 ? 'danger' : 'primary'; },
      /* 打开退费对话框(仅收费单且已收费状态可退) */
      openRefund: function (row) {
        this.refundBill = row;
        this.refundReason = '';
        this.refundVisible = true;
      },
      /* 确认退费: 必填原因 -> 二次确认(撤销医保结算) -> POST /refund */
      doRefund: function () {
        var vm = this;
        if (!vm.refundReason || !vm.refundReason.trim()) { ElementPlus.ElMessage.warning('请填写退费原因'); return; }
        var b = vm.refundBill || {};
        var msg = '确认退费？退费后将撤销医保结算<br/>'
          + '单号: <b>' + orDash(b.billNo) + '</b><br/>'
          + '患者: <b>' + orDash(b.patientName) + '</b><br/>'
          + '退费金额: <b style="color:#F56C6C;font-size:15px;">¥' + money(b.totalAmount) + '</b>';
        ElementPlus.ElMessageBox.confirm(msg, '退费确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认退费', cancelButtonText: '取消'
        }).then(function () {
          vm.refunding = true;
          return HIS.post('/api/his/cashier/refund', { billId: b.id, reason: vm.refundReason.trim() })
            .then(function (d) {
              HIS.notifySuccess('退费成功' + (d && d.billNo ? ', 退费单号: ' + d.billNo : ''));
              vm.refundVisible = false;
              vm.load();
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.refunding = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* 导出 xlsx(与列表同机构/日期口径) */
      doExport: function () {
        var vm = this;
        var q = '/api/his/cashier/export?';
        if (vm.dateRange && vm.dateRange.length === 2) { q += 'startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        HIS.download(q, '收费记录.xlsx').catch(HIS.notifyError);
      },
      money: money, money4: money4, qtyFmt: qtyFmt, orDash: orDash,
      rowStatusLabel: rowStatusLabel, rowStatusTag: rowStatusTag
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">收费/结算记录 <span style="font-size:12px;color:#909399;font-weight:normal;">(含退费单; 已日结的收费单不可退费)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:130px">',
      '      <el-option v-for="o in statusOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '    <el-input v-model="keyword" placeholder="患者姓名/收费单号" clearable style="width:200px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <el-button type="success" plain @click="doExport">导出</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="billNo" label="收费单号" width="160"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column label="单据类型" width="86" align="center"><template #default="s"><el-tag size="small" :type="billTypeTag(s.row.billType)">{{ billTypeLabel(s.row.billType) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="总金额" width="100" align="right"><template #default="s"><span style="color:#F56C6C;font-weight:700;">{{ s.row.billType===2 ? \'-¥\' : \'¥\' }}{{ money(s.row.totalAmount) }}</span></template></el-table-column>',
      '    <el-table-column label="基金支付" width="92" align="right"><template #default="s">{{ money(s.row.fundPay) }}</template></el-table-column>',
      '    <el-table-column label="个账支付" width="92" align="right"><template #default="s">{{ money(s.row.acctPay) }}</template></el-table-column>',
      '    <el-table-column label="自付金额" width="92" align="right"><template #default="s">{{ money(s.row.selfPay) }}</template></el-table-column>',
      '    <el-table-column prop="chargeBy" label="收费员" width="90"></el-table-column>',
      '    <el-table-column prop="chargeTime" label="收费时间" width="160"></el-table-column>',
      '    <el-table-column label="状态" width="84" align="center"><template #default="s"><el-tag size="small" :type="rowStatusTag(s.row)">{{ rowStatusLabel(s.row) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="160" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openReceiptOf(s.row.id)">查看收据</el-button>',
      '      <el-button v-if="s.row.billType===1 && s.row.status===1" link type="danger" @click="openRefund(s.row)">退费</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 退费对话框 ---- */
      '  <el-dialog v-model="refundVisible" title="收费退费" width="520px">',
      '    <el-descriptions :column="2" border size="small" style="margin-bottom:12px;">',
      '      <el-descriptions-item label="收费单号">{{ orDash(refundBill && refundBill.billNo) }}</el-descriptions-item>',
      '      <el-descriptions-item label="患者姓名">{{ orDash(refundBill && refundBill.patientName) }}</el-descriptions-item>',
      '      <el-descriptions-item label="收费金额"><span style="color:#F56C6C;font-weight:700;">¥{{ money(refundBill && refundBill.totalAmount) }}</span></el-descriptions-item>',
      '      <el-descriptions-item label="收费时间">{{ orDash(refundBill && refundBill.chargeTime) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <el-alert type="warning" :closable="false" show-icon title="退费后将撤销医保结算并回写就诊为未收费; 已日结的收费单不可退费" style="margin-bottom:12px;"></el-alert>',
      '    <div style="margin-bottom:8px;font-weight:600;color:#303133;">退费原因 <span style="color:#F56C6C;">*</span></div>',
      '    <el-input v-model="refundReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请输入退费原因(必填)"></el-input>',
      '    <template #footer>',
      '      <el-button @click="refundVisible=false">取消</el-button>',
      '      <el-button type="danger" :loading="refunding" @click="doRefund">确认退费</el-button>',
      '    </template>',
      '  </el-dialog>',
      receiptTpl(),
      '</div>'
    ].join('\n')
  };

  /* ================= 退费记录 ================= */
  HIS.views.ChargeRefund = {
    mixins: [receiptMixin],
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        dateRange: [], keyword: '',
        receiptVisible: false, receiptData: null
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/cashier/bills?billType=2&page=' + vm.page + '&size=' + vm.size;
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      origBillNo: function (row) { return parseOrigBillNo(row.remark); },
      reasonOf: function (row) { return parseRefundReason(row.remark); },
      money: money, money4: money4, qtyFmt: qtyFmt, orDash: orDash
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">退费记录 <span style="font-size:12px;color:#909399;font-weight:normal;">(TF 退费单; 原收费单号与原因取自退费备注)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '    <el-input v-model="keyword" placeholder="退费单号/患者姓名" clearable style="width:200px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="billNo" label="退费单号" width="160"></el-table-column>',
      '    <el-table-column label="原收费单号" width="160"><template #default="s">{{ origBillNo(s.row) }}</template></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column label="退费金额" width="110" align="right"><template #default="s"><span style="color:#F56C6C;font-weight:700;">-¥{{ money(s.row.totalAmount) }}</span></template></el-table-column>',
      '    <el-table-column label="退费原因" min-width="200" show-overflow-tooltip><template #default="s">{{ reasonOf(s.row) }}</template></el-table-column>',
      '    <el-table-column prop="chargeTime" label="退费时间" width="160"></el-table-column>',
      '    <el-table-column prop="chargeBy" label="退费操作员" width="110"></el-table-column>',
      '    <el-table-column label="操作" width="110" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openReceiptOf(s.row.id)">查看收据</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      receiptTpl(),
      '</div>'
    ].join('\n')
  };
})();
