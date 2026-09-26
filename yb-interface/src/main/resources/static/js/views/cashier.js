/* 收费结算台: 待收费(收费结算/收据预览) + 收费记录(退费/导出) + 退费记录 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量: 明细项目类型 / 单状态 / 险种 ===== */
  var ITEM_TYPES = { 1: '药品费', 2: '检查费', 3: '治疗费', 4: '材料费' };
  var ITEM_TYPE_TAGS = { 1: 'danger', 2: 'warning', 3: 'success', 4: 'info' };
  var INSU_TYPES = { '310': '310-职工医保', '390': '390-居民医保', '340': '340-工伤保险', '391': '391-城乡居民' };
  var STATUS_LIST = [{ v: 1, l: '已收费' }, { v: 2, l: '已退费' }];
  /* 支付方式(与后端 payments 枚举一致; INSURANCE 由医保结算自动落库, 前端不可选) */
  var PAY_METHODS = { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', CARD: '银行卡', INSURANCE: '医保', FREE: '免收' };
  var PAY_METHOD_OPTS = [{ v: 'CASH', l: '现金' }, { v: 'WECHAT', l: '微信' }, { v: 'ALIPAY', l: '支付宝' }, { v: 'CARD', l: '银行卡' }, { v: 'FREE', l: '免收' }];
  /* 发票类型 / 发票状态 / 号段状态 */
  var INVOICE_TYPES = { NORMAL: '正常', VOID: '作废', RED: '红冲' };
  var INVOICE_TYPE_TAGS = { NORMAL: 'success', VOID: 'warning', RED: 'danger' };
  var INVOICE_STATUS = { 1: '正常', 2: '已作废', 3: '已红冲' };
  var INVOICE_STATUS_TAGS = { 1: 'success', 2: 'warning', 3: 'danger' };
  var POOL_TYPES = { NORMAL: '纸质发票', ELECTRONIC: '电子发票' };
  var POOL_STATUS = { 0: '未启用', 1: '使用中', 2: '已用完' };
  var POOL_STATUS_TAGS = { 0: 'info', 1: 'success', 2: 'danger' };

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
    var m = /原单[:：]\s*([A-Z0-9]+)/i.exec(remark || '');
    return m ? m[1] : '-';
  }
  function parseRefundReason(remark) {
    var m = /退费原因[:：]\s*([^;]+)/.exec(remark || '');
    return m ? m[1].trim() : '-';
  }
  /* 支付方式中文映射(未知值原样展示, 空值 --) */
  function payMethodLabel(v) { return (v === null || v === undefined || v === '') ? '--' : (PAY_METHODS[v] || v); }
  /* 退费单类型: 备注含"部分退费"标记=部分退费, 否则全额退费(全额/部分退费单均带 originBillId, 不能用其区分) */
  function refundKindOf(row) { return /部分退费/.test((row && row.remark) || '') ? 'partial' : 'full'; }
  /* ISO 时间串 -> yyyy-MM-dd HH:mm:ss */
  function fmtTime(v) { return (v === null || v === undefined || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 19); }
  /* 发票序号格式化(8位前导零) */
  function noFmt(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    var s = String(v);
    while (s.length < 8) { s = '0' + s; }
    return s;
  }
  /* 金额带符号(冲销记录负数显示 -¥x.xx) */
  function signedMoney(v) { var n = Number(v) || 0; return (n < 0 ? '-¥' : '¥') + money(Math.abs(n)); }
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
      orgNameOf: function () { var u = HIS.getUser() || {}; return u.orgName || u.tenantName || '-'; },
      /* 支付方式中文映射(收据/列表共用) */
      payMethodLabel: payMethodLabel
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
      '      <tr><td>单号: {{ orDash(rcpBill().billNo) }}</td><td style="text-align:right;">发票号: {{ orDash(rcpBill().invoiceNo) }}</td></tr>',
      '      <tr><td>时间: {{ orDash(rcpBill().chargeTime) }}</td><td style="text-align:right;">支付方式: {{ payMethodLabel(rcpBill().payMethod) }}</td></tr>',
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
        payType: 'yb', payRows: [], payMethodOpts: PAY_METHOD_OPTS,
        /* 收据 */
        receiptVisible: false, receiptData: null,
        /* 定时器 */
        refreshTimer: null
      };
    },
    created: function () { this.load(); this.startAutoRefresh(); },
    beforeUnmount: function () { this.stopAutoRefresh(); },
    beforeDestroy: function () { this.stopAutoRefresh(); },
    computed: {
      /* 支付明细合计 */
      payTotal: function () {
        var t = 0;
        (this.payRows || []).forEach(function (p) { t += (Number(p.amount) || 0); });
        return t;
      },
      /* 合计是否等于应付金额(自费结算提交前校验) */
      payOk: function () {
        var due = Number((this.chargeSummary || {}).totalAmount) || 0;
        return Math.abs(this.payTotal - due) < 0.005;
      }
    },
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
        vm.payType = 'yb'; vm.payRows = [];
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
      /* 结算方式切换: 切到自费时初始化支付明细(默认一行现金=应付金额) */
      onPayTypeChange: function () {
        if (this.payType === 'self' && !this.payRows.length) { this.resetPayRows(); }
      },
      resetPayRows: function () {
        this.payRows = [{ payMethod: 'CASH', amount: money((this.chargeSummary || {}).totalAmount) }];
      },
      /* 新增支付行: 金额默认=应付金额-已填合计 */
      addPayRow: function () {
        var due = Number((this.chargeSummary || {}).totalAmount) || 0;
        var remain = due - this.payTotal;
        this.payRows.push({ payMethod: 'CASH', amount: remain > 0 ? money(remain) : '0.00' });
      },
      removePayRow: function (i) {
        if (this.payRows.length <= 1) { return; }
        this.payRows.splice(i, 1);
      },
      /* 确认收费: 二次确认 -> 医保结算(2206+2207) 或 自费(可混合支付) -> 展示收据 */
      doCharge: function () {
        var vm = this;
        if (!vm.currentVisit) { return; }
        if (!vm.chargeItems.length) { ElementPlus.ElMessage.warning('该就诊无待收费费用明细'); return; }
        /* 自费: 构建 payments 混合支付明细并校验金额守恒; 医保: 自付金额以结算结果为准, 不传 payments */
        var payments = null;
        var payDesc = '';
        if (vm.payType === 'self') {
          if (!vm.payOk) {
            ElementPlus.ElMessage.warning('支付明细合计必须等于应付金额 ¥' + money((vm.chargeSummary || {}).totalAmount));
            return;
          }
          payments = vm.payRows.map(function (p) {
            return { payMethod: p.payMethod, amount: Number((Number(p.amount) || 0).toFixed(2)) };
          });
          payDesc = vm.payRows.map(function (p) { return payMethodLabel(p.payMethod) + ' ¥' + money(p.amount); }).join(' + ');
        }
        var msg = '患者: <b>' + orDash(vm.chargePatient.name) + '</b><br/>'
          + '待收金额: <b style="color:#F56C6C;font-size:15px;">' + fYen(vm.chargeSummary.totalAmount) + '</b><br/>'
          + (vm.payType === 'yb'
            ? '结算方式: <b>医保结算</b> (基金/个账自动结算, 自付部分默认现金收取)<br/>'
            : '结算方式: <b>自费结算</b><br/>支付明细: <b>' + payDesc + '</b><br/>')
          + '<br/>确认对该就诊执行收费?';
        ElementPlus.ElMessageBox.confirm(msg, '收费确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认收费', cancelButtonText: '取消'
        }).then(function () {
          vm.charging = true;
          var url = vm.payType === 'yb' ? '/api/his/cashier/charge' : '/api/his/cashier/self-pay';
          var u = HIS.getUser() || {};
          var body = { visitId: vm.currentVisit.visit_id, orgId: u.orgId, payType: vm.payType };
          if (payments) { body.payments = payments; }
          return HIS.post(url, body)
            .then(function (d) {
              var bill = (d && d.bill) || {};
              HIS.notifySuccess('收费成功, 单号: ' + (bill.billNo || '') + (bill.invoiceNo ? ', 发票号: ' + bill.invoiceNo : ''));
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
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
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
      '        <el-radio-group v-model="payType" @change="onPayTypeChange">',
      '          <el-radio label="yb">医保结算</el-radio>',
      '          <el-radio label="self">自费结算</el-radio>',
      '        </el-radio-group>',
      '        <span style="color:#909399;font-size:12px;">医保结算将调用 2206 预结算与 2207 结算; 自费不走医保通道</span>',
      '      </div>',
      '      <el-alert v-if="payType===\'yb\'" type="info" :closable="false" show-icon style="margin-top:12px;" title="医保自付部分默认按现金收取, 金额以医保结算结果为准"></el-alert>',
      '      <div v-else style="margin-top:12px;border:1px solid #e4e7ed;border-radius:4px;padding:12px 14px;background:#fafbfc;">',
      '        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">',
      '          <span style="font-weight:600;color:#303133;">支付方式(混合支付)</span>',
      '          <span style="color:#606266;">应付金额: <b style="color:#F56C6C;font-size:16px;">{{ fYen(chargeSummary.totalAmount) }}</b></span>',
      '        </div>',
      '        <div v-for="(p,i) in payRows" :key="i" style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">',
      '          <el-select v-model="p.payMethod" size="small" style="width:150px;">',
      '            <el-option v-for="o in payMethodOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '          </el-select>',
      '          <el-input v-model="p.amount" size="small" style="width:150px;" placeholder="金额"><template #prefix>¥</template></el-input>',
      '          <span v-if="i===0" style="color:#909399;font-size:12px;">默认一行现金, 可添加多种支付方式混合支付</span>',
      '          <el-button link type="danger" :disabled="payRows.length<=1" style="margin-left:auto;" @click="removePayRow(i)">删除</el-button>',
      '        </div>',
      '        <div style="display:flex;justify-content:space-between;align-items:center;margin-top:4px;">',
      '          <el-button size="small" type="primary" plain @click="addPayRow">+ 添加支付方式</el-button>',
      '          <span style="color:#606266;">合计: <b v-if="payOk" style="color:#67C23A;">¥{{ money(payTotal) }}</b><b v-else style="color:#F56C6C;">¥{{ money(payTotal) }}</b>',
      '            <span v-if="!payOk" style="color:#F56C6C;font-size:12px;">(须等于应付金额 {{ fYen(chargeSummary.totalAmount) }})</span></span>',
      '        </div>',
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
        /* 部分退费对话框 */
        partialVisible: false, partialBill: null, partialItems: [], partialReason: '',
        partialLoading: false, partialRefunding: false,
        /* 发票作废对话框 */
        voidVisible: false, voidRow: null, voidInvoiceId: null, voidReason: '', voiding: false,
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
      rowStatusLabel: rowStatusLabel, rowStatusTag: rowStatusTag,
      /* ---- 部分退费 ---- */
      /* 打开部分退费: 拉取收费单明细(billItemId/qty/refundedQty/price) */
      openPartialRefund: function (row) {
        var vm = this;
        vm.partialBill = row;
        vm.partialReason = '';
        vm.partialItems = [];
        vm.partialVisible = true;
        vm.partialLoading = true;
        HIS.get('/api/his/cashier/receipt/' + row.id).then(function (d) {
          vm.partialItems = ((d && d.items) || []).map(function (it) {
            it.checked = false; it.refundQty = null;
            return it;
          });
        }).catch(HIS.notifyError).finally(function () { vm.partialLoading = false; });
      },
      /* 单行可退数量 = 数量 - 已退数量 */
      partialRemain: function (it) {
        var rem = (Number(it.qty) || 0) - (Number(it.refundedQty) || 0);
        return rem > 0 ? rem : 0;
      },
      /* 勾选行自动带出可退数量; 取消勾选清空本次数量 */
      onPartialCheck: function (it) {
        if (it.checked && (it.refundQty === null || it.refundQty === undefined || it.refundQty === '')) {
          it.refundQty = this.partialRemain(it);
        }
        if (!it.checked) { it.refundQty = null; }
      },
      partialLineAmount: function (it) {
        return money((Number(it.refundQty) || 0) * (Number(it.price) || 0));
      },
      partialTotal: function () {
        var t = 0;
        (this.partialItems || []).forEach(function (it) {
          if (it.checked) { t += (Number(it.refundQty) || 0) * (Number(it.price) || 0); }
        });
        return t;
      },
      /* 确认部分退费: 逐行校验数量 -> 二次确认 -> POST /partial-refund */
      doPartialRefund: function () {
        var vm = this;
        var items = [];
        var err = '';
        for (var i = 0; i < vm.partialItems.length; i++) {
          var it = vm.partialItems[i];
          if (!it.checked) { continue; }
          var q = Number(it.refundQty) || 0;
          var rem = vm.partialRemain(it);
          if (q <= 0) { err = '明细[' + it.itemName + ']本次退费数量必须大于0'; break; }
          if (q > rem) { err = '明细[' + it.itemName + ']本次退费数量不能超过可退数量 ' + qtyFmt(rem); break; }
          items.push({ billItemId: it.id, refundQty: q });
        }
        if (err) { ElementPlus.ElMessage.warning(err); return; }
        if (!items.length) { ElementPlus.ElMessage.warning('请勾选退费明细并填写本次退费数量'); return; }
        if (!vm.partialReason || !vm.partialReason.trim()) { ElementPlus.ElMessage.warning('请填写退费原因'); return; }
        var b = vm.partialBill || {};
        var msg = '确认部分退费？<br/>'
          + '原单: <b>' + orDash(b.billNo) + '</b><br/>'
          + '患者: <b>' + orDash(b.patientName) + '</b><br/>'
          + '退费金额: <b style="color:#F56C6C;font-size:15px;">¥' + money(vm.partialTotal()) + '</b><br/>'
          + '<span style="color:#909399;font-size:12px;">医保单部分退费不线上撤销结算, 需线下处理</span>';
        ElementPlus.ElMessageBox.confirm(msg, '部分退费确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认退费', cancelButtonText: '取消'
        }).then(function () {
          vm.partialRefunding = true;
          return HIS.post('/api/his/cashier/partial-refund', { billId: b.id, items: items, reason: vm.partialReason.trim() })
            .then(function (d) {
              HIS.notifySuccess('部分退费成功' + (d && d.billNo ? ', 退费单号: ' + d.billNo : ''));
              vm.partialVisible = false;
              vm.load();
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.partialRefunding = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ---- 发票作废 ---- */
      /* 按发票号定位发票记录(同日发票列表内匹配 invoiceNo+billId)后打开确认 */
      openVoidInvoice: function (row) {
        var vm = this;
        var day = String(row.chargeTime || '').slice(0, 10);
        if (!day) { HIS.notifyError(new Error('收费时间缺失, 无法定位发票记录')); return; }
        var q = '/api/his/cashier/invoices?page=1&size=200&startDate=' + day + '&endDate=' + day;
        HIS.get(q).then(function (d) {
          var recs = (d && d.records) || [];
          var hit = null;
          for (var i = 0; i < recs.length; i++) {
            if (recs[i].invoiceNo === row.invoiceNo) {
              hit = recs[i];
              if (recs[i].billId === row.id) { break; }
            }
          }
          if (!hit) { throw new Error('未找到发票记录: ' + row.invoiceNo); }
          if (hit.status !== 1) { throw new Error('该发票已' + (hit.status === 2 ? '作废' : '红冲') + ', 不能重复操作'); }
          vm.voidRow = row;
          vm.voidInvoiceId = hit.id;
          vm.voidReason = '';
          vm.voidVisible = true;
        }).catch(HIS.notifyError);
      },
      /* 确认作废: 必填原因 -> POST /invoice/{id}/void */
      doVoidInvoice: function () {
        var vm = this;
        if (!vm.voidInvoiceId) { ElementPlus.ElMessage.warning('未定位到发票记录'); return; }
        if (!vm.voidReason || !vm.voidReason.trim()) { ElementPlus.ElMessage.warning('请填写作废原因'); return; }
        vm.voiding = true;
        HIS.post('/api/his/cashier/invoice/' + vm.voidInvoiceId + '/void?reason=' + encodeURIComponent(vm.voidReason.trim()))
          .then(function () {
            HIS.notifySuccess('发票作废成功');
            vm.voidVisible = false;
            vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.voiding = false; });
      }
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
      '    <el-table-column label="支付方式" width="86" align="center"><template #default="s">{{ payMethodLabel(s.row.payMethod) }}</template></el-table-column>',
      '    <el-table-column label="发票号" width="140"><template #default="s">{{ s.row.invoiceNo || \'--\' }}</template></el-table-column>',
      '    <el-table-column prop="chargeBy" label="收费员" width="90"></el-table-column>',
      '    <el-table-column prop="chargeTime" label="收费时间" width="160"></el-table-column>',
      '    <el-table-column label="状态" width="84" align="center"><template #default="s"><el-tag size="small" :type="rowStatusTag(s.row)">{{ rowStatusLabel(s.row) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="252" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openReceiptOf(s.row.id)">查看收据</el-button>',
      '      <el-button v-if="s.row.billType===1 && s.row.status===1" link type="danger" @click="openRefund(s.row)">退费</el-button>',
      '      <el-button v-if="s.row.billType===1 && s.row.status===1" link type="warning" @click="openPartialRefund(s.row)">部分退费</el-button>',
      '      <el-button v-if="s.row.invoiceNo" link type="danger" @click="openVoidInvoice(s.row)">作废</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
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
      /* ---- 部分退费对话框 ---- */
      '  <el-dialog v-model="partialVisible" title="部分退费" width="880px" top="6vh">',
      '    <div v-loading="partialLoading" element-loading-text="加载收费明细..." style="min-height:120px;">',
      '      <el-descriptions :column="3" border size="small" style="margin-bottom:12px;">',
      '        <el-descriptions-item label="原单号">{{ orDash(partialBill && partialBill.billNo) }}</el-descriptions-item>',
      '        <el-descriptions-item label="患者姓名">{{ orDash(partialBill && partialBill.patientName) }}</el-descriptions-item>',
      '        <el-descriptions-item label="收费金额"><span style="color:#F56C6C;font-weight:700;">¥{{ money(partialBill && partialBill.totalAmount) }}</span></el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-table :data="partialItems" border size="small" max-height="320">',
      '        <el-table-column width="46" align="center"><template #default="s"><el-checkbox v-model="s.row.checked" :disabled="partialRemain(s.row)<=0" @change="onPartialCheck(s.row)"></el-checkbox></template></el-table-column>',
      '        <el-table-column prop="itemName" label="项目名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="96" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="数量" width="68" align="right"><template #default="s">{{ qtyFmt(s.row.qty) }}</template></el-table-column>',
      '        <el-table-column label="已退" width="68" align="right"><template #default="s">{{ qtyFmt(s.row.refundedQty) }}</template></el-table-column>',
      '        <el-table-column label="可退" width="68" align="right"><template #default="s">{{ qtyFmt(partialRemain(s.row)) }}</template></el-table-column>',
      '        <el-table-column label="本次退费" width="110" align="center"><template #default="s"><el-input-number v-model="s.row.refundQty" :min="0" :max="partialRemain(s.row)" :disabled="!s.row.checked" :controls="false" size="small" style="width:90px;"></el-input-number></template></el-table-column>',
      '        <el-table-column label="单价" width="86" align="right"><template #default="s">{{ money4(s.row.price) }}</template></el-table-column>',
      '        <el-table-column label="退费金额" width="96" align="right"><template #default="s"><span v-if="s.row.checked" style="color:#F56C6C;">¥{{ partialLineAmount(s.row) }}</span><span v-else style="color:#c0c4cc;">-</span></template></el-table-column>',
      '      </el-table>',
      '      <div v-if="!partialLoading && !partialItems.length" style="text-align:center;color:#909399;font-size:13px;padding:16px 0;">无明细数据</div>',
      '      <div style="margin-top:12px;font-weight:600;color:#303133;">退费原因 <span style="color:#F56C6C;">*</span></div>',
      '      <el-input v-model="partialReason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="请输入退费原因(必填)" style="margin-top:6px;"></el-input>',
      '      <div style="display:flex;justify-content:flex-end;align-items:center;margin-top:10px;font-size:14px;color:#303133;">退费合计: <b style="color:#F56C6C;font-size:18px;margin-left:6px;">¥{{ money(partialTotal()) }}</b></div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="partialVisible=false">取消</el-button>',
      '      <el-button type="danger" :loading="partialRefunding" @click="doPartialRefund">确认退费</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 发票作废对话框 ---- */
      '  <el-dialog v-model="voidVisible" title="发票作废" width="480px">',
      '    <el-descriptions :column="1" border size="small" style="margin-bottom:12px;">',
      '      <el-descriptions-item label="收费单号">{{ orDash(voidRow && voidRow.billNo) }}</el-descriptions-item>',
      '      <el-descriptions-item label="发票号">{{ orDash(voidRow && voidRow.invoiceNo) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <el-alert type="warning" :closable="false" show-icon title="作废后生成原号+V 冲销记录且金额取负, 不可恢复" style="margin-bottom:12px;"></el-alert>',
      '    <div style="margin-bottom:8px;font-weight:600;color:#303133;">作废原因 <span style="color:#F56C6C;">*</span></div>',
      '    <el-input v-model="voidReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请输入作废原因(必填)"></el-input>',
      '    <template #footer>',
      '      <el-button @click="voidVisible=false">取消</el-button>',
      '      <el-button type="danger" :loading="voiding" @click="doVoidInvoice">确认作废</el-button>',
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
      /* 退费类型: 备注含"部分退费"标记=部分退费, 否则全额退费(全额/部分退费单均带 originBillId, 不能用其区分) */
      refundKindLabel: function (row) { return refundKindOf(row) === 'partial' ? '部分退费' : '全额退费'; },
      refundKindTag: function (row) { return refundKindOf(row) === 'partial' ? 'warning' : 'danger'; },
      money: money, money4: money4, qtyFmt: qtyFmt, orDash: orDash
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">退费记录 <span style="font-size:12px;color:#909399;font-weight:normal;">(TF 退费单; 区分全额/部分退费; 原收费单号与原因取自退费备注)</span></div>',
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
      '    <el-table-column label="退费类型" width="92" align="center"><template #default="s"><el-tag size="small" :type="refundKindTag(s.row)">{{ refundKindLabel(s.row) }}</el-tag></template></el-table-column>',
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
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      receiptTpl(),
      '</div>'
    ].join('\n')
  };

  /* ================= 发票管理(号段配置 + 发票记录) ================= */
  HIS.views.InvoiceManage = {
    data: function () {
      return {
        /* 发票号段/作废/红冲为财务票据维护: 后端 requireLeadWrite 仅牵头机构管理员可写, 前端同步隐藏写按钮 */
        lead: HIS.isLead(),
        activeTab: 'pool',
        /* 号段管理 */
        poolLoading: false, pools: [], poolTotal: 0, poolPage: 1, poolSize: 20,
        poolTypeOpts: [{ v: 'NORMAL', l: '纸质发票(NORMAL)' }, { v: 'ELECTRONIC', l: '电子发票(ELECTRONIC)' }],
        poolEditVisible: false, poolEditTitle: '新增号段', poolSaving: false,
        poolForm: { id: null, poolCode: '', invoiceType: 'NORMAL', prefix: '', startNo: null, endNo: null },
        /* 发票记录 */
        invLoading: false, invoices: [], invTotal: 0, invPage: 1, invSize: 20,
        invStatus: null, invDateRange: [],
        invStatusOpts: [{ v: 1, l: '正常' }, { v: 2, l: '已作废' }, { v: 3, l: '已红冲' }],
        /* 发票作废/红冲对话框 */
        invActVisible: false, invActRow: null, invActType: 'void', invActReason: '', invActing: false
      };
    },
    /* 双 Tab 数据一次拉齐, 避免 el-tabs 懒加载事件兼容问题 */
    created: function () { this.loadPools(); this.loadInvoices(); },
    methods: {
      /* ---- 号段管理 ---- */
      loadPools: function () {
        var vm = this; vm.poolLoading = true;
        HIS.get('/api/his/cashier/invoice-pool?page=' + vm.poolPage + '&size=' + vm.poolSize)
          .then(function (d) { vm.pools = (d && d.records) || []; vm.poolTotal = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.poolLoading = false; });
      },
      onPoolPage: function (p) { this.poolPage = p; this.loadPools(); },
      onPoolSize: function (s) { this.poolSize = s; this.onPoolPage(1); },
      poolSeqNo: function (i) { return (this.poolPage - 1) * this.poolSize + i + 1; },
      poolTypeLabel: function (t) { return POOL_TYPES[t] || orDash(t); },
      poolTypeTag: function (t) { return t === 'ELECTRONIC' ? 'warning' : 'primary'; },
      poolStatusLabel: function (s) { return POOL_STATUS[s] || orDash(s); },
      poolStatusTag: function (s) { return POOL_STATUS_TAGS[s] || 'info'; },
      /* 号段总号数(含起止) */
      poolQty: function (p) {
        var q = (Number(p.endNo) || 0) - (Number(p.startNo) || 0) + 1;
        return q > 0 ? q : 0;
      },
      /* 已用号 = currentNo - startNo + 1(新增未启用 currentNo=startNo-1 -> 0), 越界收敛 */
      poolUsed: function (p) {
        var used = (p.currentNo === null || p.currentNo === undefined)
          ? 0 : (Number(p.currentNo) - Number(p.startNo) + 1);
        if (used < 0) { used = 0; }
        var q = this.poolQty(p);
        if (q > 0 && used > q) { used = q; }
        return used;
      },
      poolUsedText: function (p) { return this.poolUsed(p) + ' / ' + this.poolQty(p); },
      poolPercent: function (p) {
        var q = this.poolQty(p);
        return q > 0 ? Math.round(this.poolUsed(p) * 100 / q) : 0;
      },
      poolProgressStatus: function (s) { return s === 2 ? 'exception' : (s === 1 ? 'success' : undefined); },
      openPoolAdd: function () {
        this.poolEditTitle = '新增号段';
        this.poolForm = { id: null, poolCode: '', invoiceType: 'NORMAL', prefix: '', startNo: null, endNo: null };
        this.poolEditVisible = true;
      },
      openPoolEdit: function (row) {
        this.poolEditTitle = '编辑号段';
        this.poolForm = {
          id: row.id, poolCode: row.poolCode, invoiceType: row.invoiceType,
          prefix: row.prefix || '', startNo: row.startNo, endNo: row.endNo
        };
        this.poolEditVisible = true;
      },
      /* 保存号段: 前端校验 -> POST(带 orgId; 后端校验编码唯一/区间不交叉/已分配号不越界) */
      doSavePool: function () {
        var vm = this;
        var f = vm.poolForm || {};
        if (!f.poolCode || !String(f.poolCode).trim()) { ElementPlus.ElMessage.warning('请填写号段编码'); return; }
        if (f.startNo === null || f.startNo === undefined || f.endNo === null || f.endNo === undefined) {
          ElementPlus.ElMessage.warning('请填写起始号/结束号'); return;
        }
        if (Number(f.startNo) >= Number(f.endNo)) { ElementPlus.ElMessage.warning('起始号必须小于结束号'); return; }
        var u = HIS.getUser() || {};
        var body = {
          id: f.id, orgId: u.orgId, poolCode: String(f.poolCode).trim(), invoiceType: f.invoiceType,
          prefix: f.prefix ? String(f.prefix).trim() : '', startNo: Number(f.startNo), endNo: Number(f.endNo)
        };
        vm.poolSaving = true;
        HIS.post('/api/his/cashier/invoice-pool', body)
          .then(function () {
            HIS.notifySuccess(f.id ? '号段已保存' : '号段已新增, 请点击启用生效');
            vm.poolEditVisible = false;
            vm.loadPools();
          })
          .catch(HIS.notifyError).finally(function () { vm.poolSaving = false; });
      },
      /* 启用号段: 同机构同发票类型旧使用中号段自动让位 */
      activatePool: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认启用号段 <b>' + orDash(row.poolCode) + '</b>?<br/>启用后同机构同发票类型的旧使用中号段将自动停用/结转。',
          '启用号段', { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '启用', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.post('/api/his/cashier/invoice-pool/' + row.id + '/activate')
            .then(function () { HIS.notifySuccess('号段已启用: ' + row.poolCode); vm.loadPools(); })
            .catch(HIS.notifyError);
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ---- 发票记录 ---- */
      loadInvoices: function () {
        var vm = this; vm.invLoading = true;
        var q = '/api/his/cashier/invoices?page=' + vm.invPage + '&size=' + vm.invSize;
        if (vm.invStatus !== null && vm.invStatus !== undefined && vm.invStatus !== '') { q += '&status=' + vm.invStatus; }
        if (vm.invDateRange && vm.invDateRange.length === 2) { q += '&startDate=' + vm.invDateRange[0] + '&endDate=' + vm.invDateRange[1]; }
        HIS.get(q).then(function (d) { vm.invoices = (d && d.records) || []; vm.invTotal = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.invLoading = false; });
      },
      invSearch: function () { this.invPage = 1; this.loadInvoices(); },
      onInvPage: function (p) { this.invPage = p; this.loadInvoices(); },
      onInvSize: function (s) { this.invSize = s; this.onInvPage(1); },
      invSeqNo: function (i) { return (this.invPage - 1) * this.invSize + i + 1; },
      invTypeLabel: function (t) { return INVOICE_TYPES[t] || orDash(t); },
      invTypeTag: function (t) { return INVOICE_TYPE_TAGS[t] || 'info'; },
      invStatusLabel: function (s) { return INVOICE_STATUS[s] || orDash(s); },
      invStatusTag: function (s) { return INVOICE_STATUS_TAGS[s] || 'info'; },
      /* 打开作废/红冲对话框(type: 'void'|'red') */
      openInvAct: function (row, type) {
        this.invActRow = row;
        this.invActType = type;
        this.invActReason = '';
        this.invActVisible = true;
      },
      /* 确认作废/红冲: 必填原因 -> POST /invoice/{id}/{void|red}?reason= */
      doInvAct: function () {
        var vm = this;
        var act = vm.invActType === 'red' ? '红冲' : '作废';
        if (!vm.invActReason || !vm.invActReason.trim()) { ElementPlus.ElMessage.warning('请输入' + act + '原因'); return; }
        var row = vm.invActRow || {};
        vm.invActing = true;
        HIS.post('/api/his/cashier/invoice/' + row.id + '/' + (vm.invActType === 'red' ? 'red' : 'void')
          + '?reason=' + encodeURIComponent(vm.invActReason.trim()))
          .then(function () {
            HIS.notifySuccess('发票已' + act + ': ' + orDash(row.invoiceNo));
            vm.invActVisible = false;
            vm.loadInvoices();
          })
          .catch(HIS.notifyError).finally(function () { vm.invActing = false; });
      },
      /* 导出 xlsx(与列表同日期口径) */
      doExportInvoices: function () {
        var vm = this;
        var q = '/api/his/cashier/export/invoices?';
        if (vm.invDateRange && vm.invDateRange.length === 2) { q += 'startDate=' + vm.invDateRange[0] + '&endDate=' + vm.invDateRange[1]; }
        HIS.download(q, '发票记录.xlsx').catch(HIS.notifyError);
      },
      money: money, orDash: orDash, fmtTime: fmtTime, noFmt: noFmt, signedMoney: signedMoney
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">发票管理 <span style="font-size:12px;color:#909399;font-weight:normal;">(号段配置与发票记录; 收费时按使用中号段自动取号开票)</span></div>',
      '  <el-tabs v-model="activeTab">',
      '    <el-tab-pane label="号段管理" name="pool">',
      '      <div class="toolbar">',
      '        <el-button v-if="lead" type="primary" @click="openPoolAdd">新增号段</el-button>',
      '        <el-button @click="loadPools">刷新</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ poolTotal }} 个号段</span>',
      '        <span style="color:#909399;font-size:12px;margin-left:auto;">同机构同发票类型仅一个使用中号段; 取号=前缀+8位序号</span>',
      '      </div>',
      '      <el-table :data="pools" v-loading="poolLoading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="60" :index="poolSeqNo"></el-table-column>',
      '        <el-table-column prop="poolCode" label="号段编码" width="120"></el-table-column>',
      '        <el-table-column label="发票类型" width="106" align="center"><template #default="s"><el-tag size="small" :type="poolTypeTag(s.row.invoiceType)">{{ poolTypeLabel(s.row.invoiceType) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="前缀" width="76" align="center"><template #default="s">{{ s.row.prefix || \'--\' }}</template></el-table-column>',
      '        <el-table-column label="起止号" width="190"><template #default="s">{{ noFmt(s.row.startNo) }} ~ {{ noFmt(s.row.endNo) }}</template></el-table-column>',
      '        <el-table-column label="已用/总数" width="96" align="right"><template #default="s">{{ poolUsedText(s.row) }}</template></el-table-column>',
      '        <el-table-column label="使用进度" min-width="150"><template #default="s"><el-progress :percentage="poolPercent(s.row)" :stroke-width="12" :status="poolProgressStatus(s.row.status)"></el-progress></template></el-table-column>',
      '        <el-table-column label="状态" width="84" align="center"><template #default="s"><el-tag size="small" :type="poolStatusTag(s.row.status)">{{ poolStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="分配人" width="90"><template #default="s">{{ s.row.allocBy || \'--\' }}</template></el-table-column>',
      '        <el-table-column label="分配时间" width="150"><template #default="s">{{ fmtTime(s.row.allocTime) }}</template></el-table-column>',
      '        <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '          <el-button v-if="lead" link type="primary" @click="openPoolEdit(s.row)">编辑</el-button>',
      '          <el-button v-if="lead && s.row.status===0" link type="success" @click="activatePool(s.row)">启用</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="poolTotal" :page-size="poolSize" :page-sizes="[10, 20, 50, 100]" :current-page="poolPage" @current-change="onPoolPage" @size-change="onPoolSize"></el-pagination>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="发票记录" name="invoice">',
      '      <div class="toolbar">',
      '        <el-date-picker v-model="invDateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '        <el-select v-model="invStatus" placeholder="全部状态" clearable style="width:130px">',
      '          <el-option v-for="o in invStatusOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '        <el-button type="primary" @click="invSearch">查询</el-button>',
      '        <el-button @click="loadInvoices">刷新</el-button>',
      '        <el-button type="success" plain @click="doExportInvoices">导出</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ invTotal }} 条</span>',
      '      </div>',
      '      <el-table :data="invoices" v-loading="invLoading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="60" :index="invSeqNo"></el-table-column>',
      '        <el-table-column prop="invoiceNo" label="发票号" width="150"></el-table-column>',
      '        <el-table-column label="类型" width="80" align="center"><template #default="s"><el-tag size="small" :type="invTypeTag(s.row.invoiceType)">{{ invTypeLabel(s.row.invoiceType) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="开票金额" width="110" align="right"><template #default="s">{{ signedMoney(s.row.amount) }}</template></el-table-column>',
      '        <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '        <el-table-column label="收费单ID" width="88" align="center"><template #default="s">{{ orDash(s.row.billId) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="84" align="center"><template #default="s"><el-tag size="small" :type="invStatusTag(s.row.status)">{{ invStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="作废/红冲原因" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.voidReason || \'--\' }}</template></el-table-column>',
      '        <el-table-column label="操作人" width="90"><template #default="s">{{ s.row.voidBy || \'--\' }}</template></el-table-column>',
      '        <el-table-column label="创建时间" width="150"><template #default="s">{{ fmtTime(s.row.createTime) }}</template></el-table-column>',
      '        <el-table-column label="操作" width="120" fixed="right"><template #default="s">',
      '          <el-button v-if="lead && s.row.status===1 && s.row.invoiceType===\'NORMAL\'" link type="warning" @click="openInvAct(s.row, \'void\')">作废</el-button>',
      '          <el-button v-if="lead && s.row.status===1 && s.row.invoiceType===\'NORMAL\'" link type="danger" @click="openInvAct(s.row, \'red\')">红冲</el-button>',
      '          <span v-if="!lead || s.row.status!==1 || s.row.invoiceType!==\'NORMAL\'" style="color:#c0c4cc;font-size:12px;">-</span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="invTotal" :page-size="invSize" :page-sizes="[10, 20, 50, 100]" :current-page="invPage" @current-change="onInvPage" @size-change="onInvSize"></el-pagination>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      /* ---- 作废/红冲对话框 ---- */
      '  <el-dialog v-model="invActVisible" :title="invActType===\'red\' ? \'发票红冲\' : \'发票作废\'" width="480px">',
      '    <el-descriptions :column="1" border size="small" style="margin-bottom:12px;">',
      '      <el-descriptions-item label="发票号">{{ orDash(invActRow && invActRow.invoiceNo) }}</el-descriptions-item>',
      '      <el-descriptions-item label="患者姓名">{{ orDash(invActRow && invActRow.patientName) }}</el-descriptions-item>',
      '      <el-descriptions-item label="开票金额">{{ signedMoney(invActRow && invActRow.amount) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <el-alert v-if="invActType===\'void\'" type="warning" :closable="false" show-icon title="作废后生成原号+V冲销记录且金额取负, 不可恢复" style="margin-bottom:12px;"></el-alert>',
      '    <el-alert v-else type="error" :closable="false" show-icon title="红冲后生成原号+R冲销记录且金额取负, 不可恢复" style="margin-bottom:12px;"></el-alert>',
      '    <div style="margin-bottom:8px;font-weight:600;color:#303133;">{{ invActType===\'red\' ? \'红冲\' : \'作废\' }}原因 <span style="color:#F56C6C;">*</span></div>',
      '    <el-input v-model="invActReason" type="textarea" :rows="3" maxlength="200" show-word-limit :placeholder="\'请输入\' + (invActType===\'red\' ? \'红冲\' : \'作废\') + \'原因(必填)\'"></el-input>',
      '    <template #footer>',
      '      <el-button @click="invActVisible=false">取消</el-button>',
      '      <el-button type="danger" :loading="invActing" @click="doInvAct">确认{{ invActType===\'red\' ? \'红冲\' : \'作废\' }}</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 新增/编辑号段对话框 ---- */
      '  <el-dialog v-model="poolEditVisible" :title="poolEditTitle" width="520px">',
      '    <el-form label-width="90px">',
      '      <el-form-item label="号段编码" required>',
      '        <el-input v-model="poolForm.poolCode" maxlength="32" placeholder="如 FP2024(同机构唯一)"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="发票类型" required>',
      '        <el-select v-model="poolForm.invoiceType" style="width:100%;">',
      '          <el-option v-for="o in poolTypeOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="号前缀">',
      '        <el-input v-model="poolForm.prefix" maxlength="16" placeholder="可空; 取号=前缀+8位序号"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="起始号" required>',
      '        <el-input-number v-model="poolForm.startNo" :min="1" :controls="false" style="width:100%;" placeholder="起始号(含)"></el-input-number>',
      '      </el-form-item>',
      '      <el-form-item label="结束号" required>',
      '        <el-input-number v-model="poolForm.endNo" :min="1" :controls="false" style="width:100%;" placeholder="结束号(含)"></el-input-number>',
      '      </el-form-item>',
      '    </el-form>',
      '    <el-alert type="info" :closable="false" show-icon title="号段区间不可与同机构已有号段重叠; 新增后为未启用状态, 需启用后收费才可用" style="margin-top:4px;"></el-alert>',
      '    <template #footer>',
      '      <el-button @click="poolEditVisible=false">取消</el-button>',
      '      <el-button type="primary" :loading="poolSaving" @click="doSavePool">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
