/* 门诊收费工作站(同屏): 今日统计 + 左侧待收费 + 右侧明细结算 + 底部今日收费记录(收据/票据/退费) */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量与工具(与 cashier.js 同口径, 独立文件不共享 IIFE 私有函数) ===== */
  var ITEM_TYPES = { 1: '药品费', 2: '检查费', 3: '治疗费', 4: '材料费' };
  var ITEM_TYPE_TAGS = { 1: 'danger', 2: 'warning', 3: 'success', 4: 'info' };
  var INSU_TYPES = { '310': '310-职工医保', '390': '390-居民医保', '340': '340-工伤保险', '391': '391-城乡居民' };
  var PAY_METHODS = { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', CARD: '银行卡', INSURANCE: '医保', FREE: '免收' };
  var PAY_METHOD_OPTS = [{ v: 'CASH', l: '现金' }, { v: 'WECHAT', l: '微信' }, { v: 'ALIPAY', l: '支付宝' }, { v: 'CARD', l: '银行卡' }, { v: 'FREE', l: '免收' }];

  function money(v) { return (v === null || v === undefined || v === '') ? '0.00' : Number(v).toFixed(2); }
  function money4(v) { return (v === null || v === undefined || v === '') ? '0.0000' : Number(v).toFixed(4); }
  function qtyFmt(v) { var n = Number(v) || 0; return String(parseFloat(n.toFixed(3))); }
  function fYen(v) { return '¥' + money(v); }
  function pct(v) { var n = Number(v) || 0; if (n <= 0) { return '0%'; } return (+(n * 100).toFixed(2)) + '%'; }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function itemTypeLabel(t) { return ITEM_TYPES[t] || '其他'; }
  function itemTypeTag(t) { return ITEM_TYPE_TAGS[t] || 'info'; }
  function insuLabel(v) { return INSU_TYPES[v] || v || '自费'; }
  function genderLabel(v) { return (v === '1' || v === 1) ? '男' : ((v === '2' || v === 2) ? '女' : (v || '-')); }
  function payMethodLabel(v) { return (v === null || v === undefined || v === '') ? '--' : (PAY_METHODS[v] || v); }
  function sortByType(items) {
    var arr = (items || []).slice();
    arr.sort(function (a, b) { return (a.itemType || 9) - (b.itemType || 9); });
    return arr;
  }
  function today() {
    var d = new Date();
    var m = d.getMonth() + 1, day = d.getDate();
    return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
  }
  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  /* 新开打印窗口并注入 HTML(与 doctor/print-helper.js 同范式, 不用 document.write) */
  function openPrintDoc(title, css, html) {
    var win = window.open('', '_blank', 'width=860,height=760');
    if (!win) {
      ElementPlus.ElMessage.warning('浏览器阻止了打印窗口, 请允许本站弹出窗口');
      return;
    }
    var doc = win.document;
    doc.title = title;
    var meta = doc.createElement('meta'); meta.setAttribute('charset', 'UTF-8'); doc.head.appendChild(meta);
    var style = doc.createElement('style');
    style.textContent = css;
    doc.head.appendChild(style);
    var parsed = new DOMParser().parseFromString('<body>' + html + '</body>', 'text/html');
    Array.prototype.slice.call(parsed.body.childNodes).forEach(function (node) {
      doc.body.appendChild(doc.importNode(node, true));
    });
    win.focus();
    setTimeout(function () { try { win.print(); } catch (e) { /* 用户仍可用浏览器菜单打印 */ } }, 300);
  }

  /* ================= 打印: 内部收费收据 ================= */
  HIS.printChargeReceipt = function (data) {
    data = data || {};
    var b = data.bill || {}, p = data.patient || {}, items = data.items || [];
    var neg = b.billType === 2;
    function sgn(v) { return (neg ? '-¥' : '¥') + money(v); }
    var rows = items.map(function (it) {
      return '<tr><td>' + esc(it.itemName) + (it.spec ? ' <span class="spec">' + esc(it.spec) + '</span>' : '')
        + '</td><td class="r">' + qtyFmt(it.qty) + '</td><td class="r">' + money4(it.price) + '</td><td class="r">' + money(it.amount) + '</td></tr>';
    }).join('');
    var html = '<div class="sheet">'
      + '<div class="t1">' + esc(HIS.getHospitalName ? HIS.getHospitalName() : '医院') + '</div>'
      + '<div class="t2">门诊收费收据' + (neg ? '(退费)' : '') + '</div>'
      + '<table class="meta"><tr><td>单号: ' + esc(b.billNo) + '</td><td class="r">发票号: ' + esc(b.invoiceNo || '--') + '</td></tr>'
      + '<tr><td>时间: ' + esc(b.chargeTime) + '</td><td class="r">支付: ' + esc(payMethodLabel(b.payMethod)) + '</td></tr>'
      + '<tr><td>患者: ' + esc(p.name) + ' ' + esc(genderLabel(p.gender)) + ' ' + (p.age == null ? '' : p.age + '岁') + '</td><td class="r">科室: ' + esc(p.deptName) + '</td></tr></table>'
      + '<table class="items"><thead><tr><th>项目</th><th class="r" style="width:60px">数量</th><th class="r" style="width:80px">单价</th><th class="r" style="width:80px">金额</th></tr></thead>'
      + '<tbody>' + (rows || '<tr><td colspan="4" style="text-align:center">无明细</td></tr>') + '</tbody></table>'
      + '<div class="total">' + (neg ? '退费金额' : '合计金额') + ': <b>' + sgn(b.totalAmount) + '</b></div>'
      + '<table class="meta"><tr><td>基金支付: ' + sgn(b.fundPay) + '</td><td>个账支付: ' + sgn(b.acctPay) + '</td></tr>'
      + '<tr><td>现金支付: ' + sgn(b.cashPay) + '</td><td>个人自付: ' + sgn(b.selfPay) + '</td></tr>'
      + (b.setlId ? '<tr><td colspan="2">医保结算ID: ' + esc(b.setlId) + '</td></tr>' : '') + '</table>'
      + '<div class="foot"><span>收费员: ' + esc(b.chargeBy) + '</span><span>此收据为门急诊收费凭证, 退费须凭本收据办理</span></div>'
      + '</div>';
    openPrintDoc('门诊收费收据', '@page{size:A4;margin:14mm}body{font-family:SimSun,serif;font-size:13px;color:#111;margin:24px}'
      + '.sheet{max-width:720px;margin:0 auto;border:1px solid #333;padding:18px 24px;background:#fffef9}'
      + '.t1{text-align:center;font-size:19px;font-weight:bold;letter-spacing:2px}.t2{text-align:center;font-size:16px;font-weight:bold;letter-spacing:10px;margin:6px 0 10px}'
      + 'table{width:100%;border-collapse:collapse}td,th{padding:4px 6px;font-size:12px;text-align:left;vertical-align:top}'
      + '.meta td{line-height:1.9}.r{text-align:right}'
      + '.items th{border-bottom:1px solid #333}.items td{border-bottom:1px dashed #bbb;padding:3px 6px}'
      + '.items .spec{color:#777;font-size:11px}'
      + '.total{border-top:2px solid #333;margin-top:8px;padding-top:8px;text-align:right;font-size:15px}'
      + '.total b{color:#c00}'
      + '.foot{border-top:1px dashed #999;margin-top:12px;padding-top:8px;display:flex;justify-content:space-between;font-size:12px;color:#444}'
      + '</style>', html);
  };

  /* ================= 打印: 正式门诊收费票据(财综〔2012〕3号式样) ================= */
  HIS.printChargeTicket = function (d) {
    d = d || {};
    var h = d.header || {}, p = d.patient || {}, pay = d.pay || {}, f = d.footer || {};
    var items = d.items || [], cats = d.cats || [];
    var neg = h.billType === 2;
    function sgn(v) { return (neg ? '-' : '') + money(v); }
    var lines = items.map(function (it) {
      return '<tr><td>' + esc(it.itemName) + (it.spec ? '<span class="spec">/' + esc(it.spec) + '</span>' : '')
        + '</td><td class="c">' + esc(it.rebateClass || '') + '</td><td class="r">' + qtyFmt(it.qty)
        + '</td><td class="r">' + sgn(it.amount) + '</td><td class="r">' + sgn(it.selfCost) + '</td><td class="r">' + sgn(it.outOfScope) + '</td></tr>';
    }).join('');
    var catRows = cats.map(function (c, i) {
      var cls = 'c' + (i % 2 === 0 ? 'a' : 'b') + (i >= 6 ? ' wide' : '');
      return '<td class="' + cls + '">' + esc(c.name) + '</td><td class="ramt">' + (Number(c.amount) ? money(c.amount) : '') + '</td>';
    }).join('');
    var html = '<div class="sheet">'
      + '<div class="code">票据代码: 420101 　　　票据号码: ' + esc(h.invoiceNo || '') + ' 　　　校验码: ******</div>'
      + '<div class="t1">' + esc(h.orgName || '') + '</div>'
      + '<div class="t2">' + esc(h.title) + esc(h.subTitle || '') + (neg ? '<span class="neg">（退费票据）</span>' : '') + '</div>'
      + '<div class="bar"><span>业务流水号: ' + esc(h.billNo || '') + '</span><span>' + esc(h.year) + '年' + esc(h.month) + '月' + esc(h.day) + '日</span><span class="no">NO.' + esc(h.invoiceNo || '0000000000') + '</span></div>'
      + '<table class="prow"><tr><td>姓名: <b>' + esc(p.name) + '</b></td><td>性别: ' + esc(p.gender || '') + '</td><td>医保类型: ' + esc(p.insuTypeName || '自费') + '</td></tr>'
      + '<tr><td>医保付费方式: ' + esc(p.insuPayWay || '自费') + '</td><td colspan="2">社会保障号码: ' + esc(p.idCard || '') + '</td></tr></table>'
      + '<table class="det"><thead><tr><th style="width:34%">项目/规格</th><th style="width:11%">报销类别</th><th class="r" style="width:9%">数量</th><th class="r" style="width:14%">金额</th><th class="r" style="width:16%">自费自理</th><th class="r" style="width:16%">其中:医保政策<br>范围外自费</th></tr></thead>'
      + '<tbody>' + (lines || '<tr><td colspan="6" style="text-align:center">(无明细)</td></tr>') + '</tbody></table>'
      + '<div class="sumline">'
      + '<span class="lbl">合计（大写）</span><span class="upper">' + esc(pay.upper || '') + '</span>'
      + '<span class="lbl">（小写）</span><span class="lower">' + (neg ? '-' : '') + '￥' + money(pay.totalAmount) + '</span></div>'
      + '<div class="pays">'
      + '<div class="pay-lbl">医保支付</div><div class="pay-grid">'
      + '<span>医保统筹支付 <b>' + sgn(pay.fundPay) + '</b></span><span>个人账户支付 <b>' + sgn(pay.acctPay) + '</b></span><span>其他医保支付 <b>' + sgn(pay.otherPay) + '</b></span></div>'
      + '<div class="pay-lbl">个人支付</div><div class="pay-grid">'
      + '<span>个人现金支付 <b>' + sgn(pay.cashPay) + '</b></span><span>个人自付 <b>' + sgn(pay.selfPay) + '</b></span><span>其中:范围外自费 <b>' + sgn(pay.outOfScopeTotal) + '</b></span></div></div>'
      + '<div class="foot"><span>收款单位（章）: ' + esc(h.orgName || '') + '</span><span>收款人（签章）: ' + esc(f.chargeBy || '') + '</span><span>' + esc(f.chargeTime || '') + '</span></div>'
      + (f.setlId ? '<div class="sn">医保结算ID: ' + esc(f.setlId) + '</div>' : '')
      + '<div class="cats"><div class="cats-t">医疗费用汇总（财务归集口径 11 类）</div><table>'
      + '<tr>' + catRows + '</tr></table></div>'
      + '<div class="note">本票据为财政监制门诊收费票据式样演示: 明细含报销类别/自费自理/医保政策范围外自费, 汇总按诊察/检查/化验/治疗/手术/卫生材料/西药/中草药/中成药/药事服务/一般诊疗 11 类归集。</div>'
      + '</div>';
    openPrintDoc('门诊收费票据', '@page{size:A4;margin:12mm}body{font-family:SimSun,serif;font-size:12px;color:#111;margin:20px}'
      + '.sheet{max-width:780px;margin:0 auto;border:2px double #1a5c9e;padding:14px 18px}'
      + '.code{color:#666;font-size:11px}.t1{text-align:center;font-size:15px;font-weight:bold;margin-top:2px}'
      + '.t2{text-align:center;font-size:19px;font-weight:bold;letter-spacing:6px;color:#1a5c9e;border-bottom:2px solid #1a5c9e;padding-bottom:6px;margin-bottom:4px}'
      + '.t2 .neg{color:#c00;font-size:13px;letter-spacing:2px}'
      + '.bar{display:flex;justify-content:space-between;font-size:11px;color:#444;border-bottom:1px solid #1a5c9e;padding-bottom:3px}'
      + '.bar .no{font-family:Consolas,monospace;font-weight:bold;color:#c00}'
      + 'table{width:100%;border-collapse:collapse}td,th{border:1px solid #1a5c9e;padding:3px 6px;font-size:11px;text-align:left;vertical-align:top}'
      + '.prow{margin:4px 0}.prow td{border:none;padding:2px 4px}'
      + '.det th{background:#eef4fa;text-align:left}.det .r,.det td.r{text-align:right}.det .c{text-align:center}'
      + '.det .spec{color:#777;font-size:10px}'
      + '.sumline{border:1px solid #1a5c9e;border-top:none;padding:5px 8px;display:flex;align-items:baseline;gap:8px}'
      + '.sumline .lbl{color:#333}.upper{font-size:15px;font-weight:bold;letter-spacing:1px;border-bottom:1px solid #999;min-width:220px}'
      + '.lower{font-size:15px;font-weight:bold;color:#c00;margin-left:auto}'
      + '.pays{border:1px solid #1a5c9e;border-top:none;padding:6px 8px;display:flex;flex-direction:column;gap:4px}'
      + '.pay-lbl{font-weight:bold;color:#1a5c9e;font-size:11px}.pay-grid{display:flex;gap:24px;flex-wrap:wrap;font-size:12px}'
      + '.pay-grid b{font-family:Consolas,monospace}'
      + '.foot{display:flex;justify-content:space-between;border:1px solid #1a5c9e;border-top:none;padding:6px 8px;font-size:11px}'
      + '.sn{font-size:10px;color:#888;margin-top:3px}'
      + '.cats{margin-top:14px}.cats-t{font-weight:bold;font-size:12px;margin-bottom:4px}'
      + '.cats td{font-size:11px}.cats .ca{background:#f6f9fc;width:16%}.cats .cb{width:16%}.cats td.wide{width:20%}'
      + '.cats .ramt{text-align:right;width:12%;font-family:Consolas,monospace}'
      + '.note{margin-top:10px;color:#999;font-size:10px}'
      + '</style>', html);
  };

  /* ================= 工作站视图 ================= */
  HIS.views.ChargeWorkstation = {
    name: 'ChargeWorkstation',
    data: function () {
      return {
        summary: {},
        /* 左侧待收费 */
        todoLoading: false, todoList: [], todoTotal: 0, todoPage: 1, todoSize: 20, todoKeyword: '',
        selected: null,
        /* 右侧结算 */
        detailLoading: false, patient: {}, items: [], detailSummary: {},
        payType: 'yb', payRows: [], payMethodOpts: PAY_METHOD_OPTS, charging: false,
        /* 右下今日收费记录 */
        todayLoading: false, todayList: [], todayTotal: 0, todayPage: 1, todaySize: 10,
        /* 对话框 */
        receiptVisible: false, receiptData: null,
        ticketVisible: false, ticketLoading: false, ticketData: null,
        refundVisible: false, refundBill: null, refundReason: '', refunding: false,
        partialVisible: false, partialBill: null, partialItems: [], partialReason: '',
        partialLoading: false, partialRefunding: false,
        settling: false,
        timer: null
      };
    },
    computed: {
      payTotal: function () {
        var t = 0;
        (this.payRows || []).forEach(function (p) { t += (Number(p.amount) || 0); });
        return t;
      },
      payOk: function () {
        var due = Number((this.detailSummary || {}).totalAmount) || 0;
        return Math.abs(this.payTotal - due) < 0.005;
      },
      /* 乙类先自付合计(票面口径预览) */
      selfCostTotal: function () {
        var t = 0;
        (this.items || []).forEach(function (it) { t += Number(it.selfCost) || 0; });
        return t;
      },
      outOfScopeTotal: function () {
        var t = 0;
        (this.items || []).forEach(function (it) { t += Number(it.outOfScope) || 0; });
        return t;
      }
    },
    created: function () {
      this.loadSummary(); this.loadTodo(); this.loadToday(); this.startTimer();
    },
    beforeUnmount: function () { this.stopTimer(); },
    beforeDestroy: function () { this.stopTimer(); },
    methods: {
      /* ---- 数据加载 ---- */
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/his/cashier/daily-summary').then(function (d) { vm.summary = d || {}; })
          .catch(function () { /* 统计卡失败不弹扰 */ });
      },
      loadTodo: function () {
        var vm = this; vm.todoLoading = true;
        var q = '/api/his/cashier/todo?page=' + vm.todoPage + '&size=' + vm.todoSize;
        if (vm.todoKeyword) { q += '&keyword=' + encodeURIComponent(vm.todoKeyword); }
        HIS.get(q).then(function (d) {
          vm.todoList = (d && d.records) || []; vm.todoTotal = (d && d.total) || 0;
          if (vm.selected) {
            var still = vm.todoList.some(function (r) { return r.visit_id === vm.selected.visit_id; });
            if (!still) { vm.clearSelection(); }
          }
        }).catch(HIS.notifyError).finally(function () { vm.todoLoading = false; });
      },
      todoSearch: function () { this.todoPage = 1; this.loadTodo(); },
      onTodoPage: function (p) { this.todoPage = p; this.loadTodo(); },
      loadToday: function () {
        var vm = this; vm.todayLoading = true;
        var d = today();
        HIS.get('/api/his/cashier/bills?page=' + vm.todayPage + '&size=' + vm.todaySize + '&startDate=' + d + '&endDate=' + d)
          .then(function (r) { vm.todayList = (r && r.records) || []; vm.todayTotal = (r && r.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.todayLoading = false; });
      },
      onTodayPage: function (p) { this.todayPage = p; this.loadToday(); },
      refreshAll: function () { this.loadSummary(); this.loadTodo(); this.loadToday(); },
      startTimer: function () {
        var vm = this; this.stopTimer();
        this.timer = setInterval(function () { vm.loadTodo(); vm.loadSummary(); }, 30000);
      },
      stopTimer: function () { if (this.timer) { clearInterval(this.timer); this.timer = null; } },
      /* ---- 选人拉明细 ---- */
      pickVisit: function (row) {
        var vm = this;
        if (vm.selected && vm.selected.visit_id === row.visit_id) { return; }
        vm.selected = row;
        vm.detailLoading = true;
        vm.payType = row.insutype && row.insutype !== '510' ? 'yb' : 'self';
        vm.payRows = [];
        vm.patient = {}; vm.items = []; vm.detailSummary = {};
        HIS.get('/api/his/cashier/bill/' + row.visit_id).then(function (d) {
          vm.patient = (d && d.patient) || {};
          vm.items = sortByType((d && d.items) || []);
          vm.detailSummary = (d && d.summary) || {};
          if (vm.payType === 'self') { vm.resetPayRows(); }
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      clearSelection: function () {
        this.selected = null; this.patient = {}; this.items = []; this.detailSummary = {}; this.payRows = [];
      },
      /* 明细按项目类型纵向合并 */
      groupSpan: function (p) {
        if (!p.column || p.column.label !== '项目类型') { return; }
        var list = this.items;
        var cur = list[p.rowIndex];
        var prev = p.rowIndex > 0 ? list[p.rowIndex - 1] : null;
        if (prev && prev.itemType === cur.itemType) { return { rowspan: 0, colspan: 0 }; }
        var n = 1;
        for (var i = p.rowIndex + 1; i < list.length; i++) {
          if (list[i].itemType === cur.itemType) { n++; } else { break; }
        }
        return { rowspan: n, colspan: 1 };
      },
      summaryMethod: function (param) {
        var vm = this;
        return param.columns.map(function (c, i) {
          if (i === 0) { return '合计'; }
          if (c.label === '数量') {
            var q = 0;
            for (var k = 0; k < vm.items.length; k++) { q += Number(vm.items[k].qty) || 0; }
            return qtyFmt(q);
          }
          if (c.label === '金额') { return money(vm.detailSummary.totalAmount); }
          if (c.label === '自费自理') { return money(vm.selfCostTotal); }
          if (c.label === '范围外自费') { return money(vm.outOfScopeTotal); }
          return '';
        });
      },
      /* ---- 结算 ---- */
      onPayTypeChange: function () {
        if (this.payType === 'self' && !this.payRows.length) { this.resetPayRows(); }
      },
      resetPayRows: function () {
        this.payRows = [{ payMethod: 'CASH', amount: money((this.detailSummary || {}).totalAmount) }];
      },
      addPayRow: function () {
        var due = Number((this.detailSummary || {}).totalAmount) || 0;
        var remain = due - this.payTotal;
        this.payRows.push({ payMethod: 'CASH', amount: remain > 0 ? money(remain) : '0.00' });
      },
      removePayRow: function (i) {
        if (this.payRows.length <= 1) { return; }
        this.payRows.splice(i, 1);
      },
      doCharge: function () {
        var vm = this;
        if (!vm.selected) { return; }
        if (!vm.items.length) { ElementPlus.ElMessage.warning('该就诊无待收费费用明细'); return; }
        var payments = null;
        var payDesc = '';
        if (vm.payType === 'self') {
          if (!vm.payOk) {
            ElementPlus.ElMessage.warning('支付明细合计必须等于应付金额 ¥' + money((vm.detailSummary || {}).totalAmount));
            return;
          }
          payments = vm.payRows.map(function (p) {
            return { payMethod: p.payMethod, amount: Number((Number(p.amount) || 0).toFixed(2)) };
          });
          payDesc = vm.payRows.map(function (p) { return payMethodLabel(p.payMethod) + ' ¥' + money(p.amount); }).join(' + ');
        }
        var msg = '患者: <b>' + orDash(vm.patient.name) + '</b><br/>'
          + '待收金额: <b style="color:#F56C6C;font-size:15px;">' + fYen(vm.detailSummary.totalAmount) + '</b><br/>'
          + (vm.payType === 'yb'
            ? '结算方式: <b>医保结算</b> (2206预结算+2207结算, 自付部分默认现金)<br/>'
            : '结算方式: <b>自费结算</b><br/>支付明细: <b>' + payDesc + '</b><br/>')
          + '<br/>确认对该就诊执行收费?';
        ElementPlus.ElMessageBox.confirm(msg, '收费确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认收费', cancelButtonText: '取消'
        }).then(function () {
          vm.charging = true;
          var url = vm.payType === 'yb' ? '/api/his/cashier/charge' : '/api/his/cashier/self-pay';
          var u = HIS.getUser() || {};
          var body = { visitId: vm.selected.visit_id, orgId: u.orgId, payType: vm.payType };
          if (payments) { body.payments = payments; }
          return HIS.post(url, body).then(function (d) {
            var bill = (d && d.bill) || {};
            HIS.notifySuccess('收费成功, 单号: ' + (bill.billNo || '') + (bill.invoiceNo ? ', 发票号: ' + bill.invoiceNo : ''));
            vm.receiptData = (d && d.receipt) || null;
            if (vm.receiptData) { vm.receiptVisible = true; }
            vm.clearSelection();
            vm.refreshAll();
          }).catch(HIS.notifyError).finally(function () { vm.charging = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ---- 收据 / 正式票据 ---- */
      openReceipt: function (billId) {
        var vm = this;
        vm.receiptData = null; vm.receiptVisible = true;
        HIS.get('/api/his/cashier/receipt/' + billId).then(function (d) { vm.receiptData = d; })
          .catch(HIS.notifyError);
      },
      printReceipt: function () {
        if (this.receiptData) { HIS.printChargeReceipt(this.receiptData); }
      },
      openTicket: function (billId) {
        var vm = this;
        vm.ticketData = null; vm.ticketVisible = true; vm.ticketLoading = true;
        HIS.get('/api/his/cashier/invoice-print/' + billId).then(function (d) { vm.ticketData = d; })
          .catch(HIS.notifyError).finally(function () { vm.ticketLoading = false; });
      },
      printTicket: function () {
        if (this.ticketData) { HIS.printChargeTicket(this.ticketData); }
      },
      /* ---- 退费 / 部分退费 ---- */
      openRefund: function (row) {
        this.refundBill = row; this.refundReason = ''; this.refundVisible = true;
      },
      doRefund: function () {
        var vm = this;
        if (!vm.refundReason || !vm.refundReason.trim()) { ElementPlus.ElMessage.warning('请填写退费原因'); return; }
        var b = vm.refundBill || {};
        var msg = '确认退费？退费后将撤销医保结算<br/>'
          + '单号: <b>' + orDash(b.billNo) + '</b>　患者: <b>' + orDash(b.patientName) + '</b><br/>'
          + '退费金额: <b style="color:#F56C6C;font-size:15px;">¥' + money(b.totalAmount) + '</b>';
        ElementPlus.ElMessageBox.confirm(msg, '退费确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认退费', cancelButtonText: '取消'
        }).then(function () {
          vm.refunding = true;
          return HIS.post('/api/his/cashier/refund', { billId: b.id, reason: vm.refundReason.trim() })
            .then(function (d) {
              HIS.notifySuccess('退费成功' + (d && d.billNo ? ', 退费单号: ' + d.billNo : ''));
              vm.refundVisible = false;
              vm.refreshAll();
            }).catch(HIS.notifyError).finally(function () { vm.refunding = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      openPartialRefund: function (row) {
        var vm = this;
        vm.partialBill = row; vm.partialReason = ''; vm.partialItems = [];
        vm.partialVisible = true; vm.partialLoading = true;
        HIS.get('/api/his/cashier/receipt/' + row.id).then(function (d) {
          vm.partialItems = ((d && d.items) || []).map(function (it) {
            it.checked = false; it.refundQty = null;
            return it;
          });
        }).catch(HIS.notifyError).finally(function () { vm.partialLoading = false; });
      },
      partialRemain: function (it) {
        var rem = (Number(it.qty) || 0) - (Number(it.refundedQty) || 0);
        return rem > 0 ? rem : 0;
      },
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
        var msg = '确认部分退费？<br/>原单: <b>' + orDash(b.billNo) + '</b>　患者: <b>' + orDash(b.patientName) + '</b><br/>'
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
              vm.refreshAll();
            }).catch(HIS.notifyError).finally(function () { vm.partialRefunding = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ---- 日结 ---- */
      doDailySettle: function () {
        var vm = this;
        if (vm.summary.settled) { ElementPlus.ElMessage.info('今日已日结'); return; }
        ElementPlus.ElMessageBox.confirm(
          '对今日（' + today() + '）全部收费/退费执行日结, 日结后当日单据不可再退费。确认日结？',
          '门诊日结', { type: 'warning', confirmButtonText: '确认日结', cancelButtonText: '取消' }
        ).then(function () {
          vm.settling = true;
          HIS.post('/api/his/cashier/daily-settle?date=' + today(), {}).then(function (d) {
            HIS.notifySuccess('日结完成: 收费 ' + (d.totalCount || 0) + ' 笔 ¥' + money(d.totalAmount)
              + ', 退费 ' + (d.refundCount || 0) + ' 笔 ¥' + money(d.refundAmount));
            vm.loadSummary();
          }).catch(HIS.notifyError).finally(function () { vm.settling = false; });
        }).catch(function () {});
      },
      /* ---- 展示工具 ---- */
      rcpBill: function () { return (this.receiptData && this.receiptData.bill) || {}; },
      rcpPatient: function () { return (this.receiptData && this.receiptData.patient) || {}; },
      rcpItems: function () { return (this.receiptData && this.receiptData.items) || []; },
      rcpSigned: function (v) { return (this.rcpBill().billType === 2 ? '-¥' : '¥') + money(v); },
      rowStatusLabel: function (row) {
        if (row.billType === 2) { return '已退费'; }
        return row.status === 0 ? '待收费' : (row.status === 1 ? '已收费' : '已退费');
      },
      rowStatusTag: function (row) {
        if (row.billType === 2) { return 'danger'; }
        return row.status === 1 ? 'success' : (row.status === 2 ? 'danger' : 'info');
      },
      money: money, money4: money4, qtyFmt: qtyFmt, fYen: fYen, orDash: orDash, pct: pct,
      itemTypeLabel: itemTypeLabel, itemTypeTag: itemTypeTag, insuLabel: insuLabel,
      genderLabel: genderLabel, payMethodLabel: payMethodLabel
    },
    template: [
      '<div class="cw-workstation">',
      /* ===== 顶部统计卡 ===== */
      '  <div class="cw-summary-bar">',
      '    <div class="cw-card"><div class="icon icon--orange">待</div><div><div class="num">{{ summary.todoCount || 0 }}</div><div class="label">待收费人数</div></div></div>',
      '    <div class="cw-card"><div class="icon icon--blue">收</div><div><div class="num">{{ summary.chargeCount || 0 }} 笔</div><div class="label">今日收费 ¥{{ money(summary.chargeAmount) }}</div></div></div>',
      '    <div class="cw-card"><div class="icon icon--red">退</div><div><div class="num">{{ summary.refundCount || 0 }} 笔</div><div class="label">今日退费 ¥{{ money(summary.refundAmount) }}</div></div></div>',
      '    <div class="cw-card"><div class="icon icon--green">现</div><div><div class="num">¥{{ money(summary.cashTotal) }}</div><div class="label">今日现金净额</div></div></div>',
      '    <div class="cw-card"><div class="icon icon--purple">票</div><div><div class="num">{{ summary.invoiceCount || 0 }}</div><div class="label">今日已开票</div></div></div>',
      '    <div class="cw-card cw-card--settle">',
      '      <div class="icon" :class="summary.settled ? \'icon--green\' : \'icon--grey\'">{{ summary.settled ? "结" : "未" }}</div>',
      '      <div><div class="num" style="font-size:15px;">{{ summary.settled ? "已日结" : "未日结" }}</div><div class="label">{{ summary.settled ? orDash(summary.settleTime).slice(0,19) : "门诊日结" }}</div></div>',
      '      <el-button v-if="!summary.settled" size="small" type="warning" plain :loading="settling" style="margin-left:auto;" @click="doDailySettle">立即日结</el-button>',
      '    </div>',
      '  </div>',
      /* ===== 主体: 左待收费 | 右结算明细 ===== */
      '  <div class="cw-body">',
      '    <div class="cw-left">',
      '      <div class="cw-panel-title">待收费 <span class="sub">完成接诊未收费 · {{ todoTotal }} 人 · 30秒自动刷新</span></div>',
      '      <div style="padding:0 10px 8px;">',
      '        <el-input v-model="todoKeyword" placeholder="姓名/就诊号 回车检索" size="small" clearable @keyup.enter="todoSearch" @clear="todoSearch"></el-input>',
      '      </div>',
      '      <div class="cw-todo-list" v-loading="todoLoading" element-loading-text="加载待收费...">',
      '        <div v-for="t in todoList" :key="t.visit_id" :class="[\'cw-todo-item\', selected && selected.visit_id===t.visit_id ? \'is-active\' : \'\']" @click="pickVisit(t)">',
      '          <div class="ln1"><b>{{ t.patient_name }}</b><span class="meta">{{ genderLabel(t.gender) }} / {{ orDash(t.age) }}岁</span>',
      '            <span class="amt">¥{{ money(t.total_amount) }}</span></div>',
      '          <div class="ln2">{{ orDash(t.dept_name) }} · {{ orDash(t.doctor_name) }}<el-tag size="small" effect="plain" style="margin-left:6px;">{{ insuLabel(t.insutype) }}</el-tag></div>',
      '          <div class="ln3">{{ orDash(t.visit_no) }}　完成: {{ orDash(t.finish_time).slice(11,16) }}</div>',
      '        </div>',
      '        <div v-if="!todoLoading && !todoList.length" class="cw-empty-tip">暂无待收费患者</div>',
      '      </div>',
      '      <el-pagination v-if="todoTotal>todoSize" small layout="prev, pager, next" :total="todoTotal" :page-size="todoSize" :current-page="todoPage" @current-change="onTodoPage" style="padding:6px 10px;justify-content:center;"></el-pagination>',
      '    </div>',
      '    <div class="cw-main" v-loading="detailLoading" element-loading-text="加载费用明细...">',
      '      <div v-if="!selected" class="cw-placeholder">',
      '        <div style="font-size:40px;">￥</div><div>从左侧选择待收费患者, 核对费用后同屏完成结算</div>',
      '      </div>',
      '      <template v-else>',
      '        <div class="cw-patient-bar">',
      '          <span class="nm">{{ orDash(patient.name) }}</span>',
      '          <span>{{ genderLabel(patient.gender) }} {{ patient.age==null ? "" : patient.age + "岁" }}</span>',
      '          <el-tag size="small" :type="payType===\'yb\' ? \'primary\' : \'info\'">{{ insuLabel(patient.insuType) }}</el-tag>',
      '          <span>{{ orDash(patient.deptName) }} · {{ orDash(patient.drName) }}</span>',
      '          <span class="dim">就诊号 {{ orDash(patient.visitNo) }}</span>',
      '          <span class="dim" v-if="patient.mdtrtId">医保就诊ID {{ patient.mdtrtId }}</span>',
      '          <span class="dim">{{ orDash(patient.idCard) }}</span>',
      '          <span style="margin-left:auto;color:#F56C6C;font-weight:700;font-size:18px;">{{ fYen(detailSummary.totalAmount) }}</span>',
      '        </div>',
      /* ---- 费用明细(含票据口径三列) ---- */
      '        <el-table :data="items" border size="small" max-height="330" :span-method="groupSpan" show-summary :summary-method="summaryMethod" class="cw-items">',
      '          <el-table-column label="项目类型" width="80" align="center"><template #default="s"><el-tag size="small" :type="itemTypeTag(s.row.itemType)">{{ itemTypeLabel(s.row.itemType) }}</el-tag></template></el-table-column>',
      '          <el-table-column prop="itemName" label="项目名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="100" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="数量" width="60" align="right"><template #default="s">{{ qtyFmt(s.row.qty) }}</template></el-table-column>',
      '          <el-table-column label="单价" width="80" align="right"><template #default="s">{{ money4(s.row.price) }}</template></el-table-column>',
      '          <el-table-column label="金额" width="86" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '          <el-table-column label="报销类别" width="86" align="center"><template #default="s"><el-tag size="small" :type="s.row.rebateClass===\'自费\' ? \'info\' : (s.row.rebateClass===\'医保乙类\' ? \'warning\' : (s.row.rebateClass===\'医保丙类\' ? \'danger\' : \'success\'))" effect="plain">{{ orDash(s.row.rebateClass) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="自费自理" width="80" align="right"><template #default="s">{{ money(s.row.selfCost) }}</template></el-table-column>',
      '          <el-table-column label="范围外自费" width="86" align="right"><template #default="s">{{ money(s.row.outOfScope) }}</template></el-table-column>',
      '          <el-table-column label="医保编码" width="110" show-overflow-tooltip><template #default="s">{{ orDash(s.row.medListCodg) }}</template></el-table-column>',
      '          <el-table-column label="自付比例" width="70" align="right"><template #default="s">{{ pct(s.row.ratio) }}</template></el-table-column>',
      '        </el-table>',
      '        <div class="cw-fee-bar">',
      '          <span>药品费 <b>{{ fYen(detailSummary.drugAmount) }}</b></span>',
      '          <span>检查费 <b>{{ fYen(detailSummary.examAmount) }}</b></span>',
      '          <span>治疗费 <b>{{ fYen(detailSummary.treatAmount) }}</b></span>',
      '          <span>材料费 <b>{{ fYen(detailSummary.materialAmount) }}</b></span>',
      '          <span style="margin-left:auto;">自费自理 <b style="color:#e6a23c;">{{ fYen(selfCostTotal) }}</b>　范围外自费 <b style="color:#f56c6c;">{{ fYen(outOfScopeTotal) }}</b></span>',
      '        </div>',
      /* ---- 结算区 ---- */
      '        <div class="cw-settle-area">',
      '          <div class="cw-settle-left">',
      '            <el-radio-group v-model="payType" @change="onPayTypeChange">',
      '              <el-radio label="yb">医保结算</el-radio><el-radio label="self">自费结算</el-radio>',
      '            </el-radio-group>',
      '            <div v-if="payType===\'yb\'" class="cw-settle-tip">调用 2206 预结算 + 2207 结算; 基金/个账自动扣减, 自付部分默认现金收取</div>',
      '            <div v-else class="cw-pay-rows">',
      '              <div v-for="(p,i) in payRows" :key="i" class="cw-pay-row">',
      '                <el-select v-model="p.payMethod" size="small" style="width:110px;">',
      '                  <el-option v-for="o in payMethodOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '                </el-select>',
      '                <el-input v-model="p.amount" size="small" style="width:120px;"><template #prefix>¥</template></el-input>',
      '                <el-button link type="danger" :disabled="payRows.length<=1" @click="removePayRow(i)">删除</el-button>',
      '              </div>',
      '              <div style="display:flex;align-items:center;gap:10px;">',
      '                <el-button size="small" type="primary" plain @click="addPayRow">+ 混合支付</el-button>',
      '                <span :style="payOk ? \'color:#67c23a;\' : \'color:#f56c6c;\'">合计 ¥{{ money(payTotal) }}{{ payOk ? \'\' : \' (须等于应付 \' + fYen(detailSummary.totalAmount) + \')\' }}</span>',
      '              </div>',
      '            </div>',
      '          </div>',
      '          <el-button type="primary" size="large" class="cw-charge-btn" :loading="charging" :disabled="!items.length" @click="doCharge">确认收费</el-button>',
      '        </div>',
      '      </template>',
      /* ---- 今日收费记录(同屏处理) ---- */
      '      <div class="cw-today">',
      '        <div class="cw-panel-title">今日收费记录 <span class="sub">共 {{ todayTotal }} 条 · 收据/票据/退费就地处理</span>',
      '          <el-button link type="primary" size="small" style="margin-left:auto;" @click="loadToday">刷新</el-button></div>',
      '        <el-table :data="todayList" v-loading="todayLoading" border size="small" max-height="240">',
      '          <el-table-column prop="billNo" label="单号" width="150"></el-table-column>',
      '          <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '          <el-table-column label="类型" width="70" align="center"><template #default="s"><el-tag size="small" :type="s.row.billType===2 ? \'danger\' : \'primary\'">{{ s.row.billType===2 ? "退费" : "收费" }}</el-tag></template></el-table-column>',
      '          <el-table-column label="金额" width="96" align="right"><template #default="s"><span style="font-weight:700;" :style="s.row.billType===2 ? \'color:#67c23a\' : \'color:#F56C6C\'">{{ s.row.billType===2 ? \'-¥\' : \'¥\' }}{{ money(s.row.totalAmount) }}</span></template></el-table-column>',
      '          <el-table-column label="基金/个账" width="118" align="right"><template #default="s">{{ money(s.row.fundPay) }} / {{ money(s.row.acctPay) }}</template></el-table-column>',
      '          <el-table-column label="支付" width="66" align="center"><template #default="s">{{ payMethodLabel(s.row.payMethod) }}</template></el-table-column>',
      '          <el-table-column label="发票号" width="126"><template #default="s">{{ s.row.invoiceNo || \'--\' }}</template></el-table-column>',
      '          <el-table-column prop="chargeTime" label="时间" width="150"></el-table-column>',
      '          <el-table-column label="状态" width="76" align="center"><template #default="s"><el-tag size="small" :type="rowStatusTag(s.row)">{{ rowStatusLabel(s.row) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="操作" min-width="230" fixed="right"><template #default="s">',
      '            <el-button link type="primary" @click="openReceipt(s.row.id)">收据</el-button>',
      '            <el-button link type="warning" @click="openTicket(s.row.id)">票据</el-button>',
      '            <el-button v-if="s.row.billType===1 && s.row.status===1" link type="danger" @click="openRefund(s.row)">退费</el-button>',
      '            <el-button v-if="s.row.billType===1 && s.row.status===1" link type="danger" plain @click="openPartialRefund(s.row)">部分退</el-button>',
      '          </template></el-table-column>',
      '        </el-table>',
      '        <el-pagination v-if="todayTotal>todaySize" small layout="prev, pager, next" :total="todayTotal" :page-size="todaySize" :current-page="todayPage" @current-change="onTodayPage" style="padding:6px 10px;justify-content:flex-end;"></el-pagination>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ===== 收据对话框 ===== */
      '  <el-dialog v-model="receiptVisible" title="收费收据" width="720px" top="3vh">',
      '    <div v-if="!receiptData" style="height:220px;" v-loading="true" element-loading-text="加载收据..."></div>',
      '    <div v-else class="cw-receipt">',
      '      <div class="r1">{{ (HIS_getHospitalName()) }}</div>',
      '      <div class="r2">门诊收费收据<span v-if="rcpBill().billType===2">(退费)</span></div>',
      '      <table class="rmeta">',
      '        <tr><td>单号: {{ orDash(rcpBill().billNo) }}</td><td class="rt">发票号: {{ orDash(rcpBill().invoiceNo) }}</td></tr>',
      '        <tr><td>时间: {{ orDash(rcpBill().chargeTime) }}</td><td class="rt">支付: {{ payMethodLabel(rcpBill().payMethod) }}</td></tr>',
      '        <tr><td>患者: {{ orDash(rcpPatient().name) }} {{ genderLabel(rcpPatient().gender) }}</td><td class="rt">科室: {{ orDash(rcpPatient().deptName) }}</td></tr>',
      '      </table>',
      '      <table class="ritems"><thead><tr><th>项目</th><th class="rt" style="width:60px">数量</th><th class="rt" style="width:80px">单价</th><th class="rt" style="width:80px">金额</th></tr></thead>',
      '        <tbody><tr v-for="(it,i) in rcpItems()" :key="i"><td>{{ it.itemName }}<span v-if="it.spec" class="rspec">　{{ it.spec }}</span></td><td class="rt">{{ qtyFmt(it.qty) }}</td><td class="rt">{{ money4(it.price) }}</td><td class="rt">{{ money(it.amount) }}</td></tr>',
      '        <tr v-if="!rcpItems().length"><td colspan="4" style="text-align:center;color:#999;">无明细</td></tr></tbody></table>',
      '      <div class="rtotal">{{ rcpBill().billType===2 ? "退费金额" : "合计金额" }}: <b>{{ rcpSigned(rcpBill().totalAmount) }}</b></div>',
      '      <table class="rmeta">',
      '        <tr><td>基金支付: {{ rcpSigned(rcpBill().fundPay) }}</td><td class="rt">个账支付: {{ rcpSigned(rcpBill().acctPay) }}</td></tr>',
      '        <tr><td>现金支付: {{ rcpSigned(rcpBill().cashPay) }}</td><td class="rt">个人自付: {{ rcpSigned(rcpBill().selfPay) }}</td></tr>',
      '      </table>',
      '      <div class="rfoot"><span>收费员: {{ orDash(rcpBill().chargeBy) }}</span><span>此收据为门急诊收费凭证, 退费须凭本收据办理</span></div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="receiptVisible=false">关闭</el-button>',
      '      <el-button type="primary" :disabled="!receiptData" @click="printReceipt">打印收据</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ===== 正式票据预览对话框 ===== */
      '  <el-dialog v-model="ticketVisible" title="门诊收费票据(财综〔2012〕3号式样)" width="780px" top="3vh">',
      '    <div v-if="ticketLoading" style="height:260px;" v-loading="true" element-loading-text="加载票据..."></div>',
      '    <div v-else-if="ticketData" class="cw-ticket">',
      '      <div class="tk-org">{{ ticketData.header.orgName }}</div>',
      '      <div class="tk-title">{{ ticketData.header.title }}{{ ticketData.header.subTitle }}<span v-if="ticketData.header.billType===2" class="tk-neg">（退费票据）</span></div>',
      '      <div class="tk-bar"><span>业务流水号: {{ orDash(ticketData.header.billNo) }}</span><span>{{ ticketData.header.date }}</span><span class="tk-no">NO.{{ orDash(ticketData.header.invoiceNo) }}</span></div>',
      '      <div class="tk-prow">姓名: <b>{{ orDash(ticketData.patient.name) }}</b>　性别: {{ orDash(ticketData.patient.gender) }}　医保类型: {{ ticketData.patient.insuTypeName }}　医保付费方式: {{ ticketData.patient.insuPayWay }}</div>',
      '      <div class="tk-prow">社会保障号码(身份证): {{ orDash(ticketData.patient.idCard) }}　医保号: {{ orDash(ticketData.patient.psnNo) }}</div>',
      '      <el-table :data="ticketData.items" size="small" border max-height="220">',
      '        <el-table-column prop="itemName" label="项目/规格" min-width="170"><template #default="s">{{ s.row.itemName }}<span v-if="s.row.spec" class="tk-spec">/{{ s.row.spec }}</span></template></el-table-column>',
      '        <el-table-column label="报销类别" width="90" align="center"><template #default="s">{{ orDash(s.row.rebateClass) }}</template></el-table-column>',
      '        <el-table-column label="数量" width="62" align="right"><template #default="s">{{ qtyFmt(s.row.qty) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="86" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '        <el-table-column label="自费自理" width="86" align="right"><template #default="s">{{ money(s.row.selfCost) }}</template></el-table-column>',
      '        <el-table-column label="其中:范围外自费" width="110" align="right"><template #default="s">{{ money(s.row.outOfScope) }}</template></el-table-column>',
      '      </el-table>',
      '      <div class="tk-sum">合计（大写） <b class="tk-upper">{{ ticketData.pay.upper }}</b><span class="tk-lower">（小写）{{ ticketData.header.billType===2 ? "-" : "" }}￥{{ money(ticketData.pay.totalAmount) }}</span></div>',
      '      <div class="tk-pays">',
      '        <span>医保统筹支付 <b>{{ money(ticketData.pay.fundPay) }}</b></span><span>个人账户支付 <b>{{ money(ticketData.pay.acctPay) }}</b></span><span>其他医保支付 <b>{{ money(ticketData.pay.otherPay) }}</b></span>',
      '        <span>个人现金支付 <b>{{ money(ticketData.pay.cashPay) }}</b></span><span>个人自付 <b>{{ money(ticketData.pay.selfPay) }}</b></span><span>范围外自费 <b>{{ money(ticketData.pay.outOfScopeTotal) }}</b></span>',
      '      </div>',
      '      <div class="tk-cats-title">医疗费用汇总（财务归集 11 类）</div>',
      '      <table class="tk-cats"><tr v-for="(pair,i) in ticketCatPairs()" :key="i">',
      '        <td class="cname">{{ pair[0] ? pair[0].name : "" }}</td><td class="camt">{{ pair[0] && Number(pair[0].amount) ? money(pair[0].amount) : "" }}</td>',
      '        <td class="cname">{{ pair[1] ? pair[1].name : "" }}</td><td class="camt">{{ pair[1] && Number(pair[1].amount) ? money(pair[1].amount) : "" }}</td>',
      '        <td class="cname">{{ pair[2] ? pair[2].name : "" }}</td><td class="camt">{{ pair[2] && Number(pair[2].amount) ? money(pair[2].amount) : "" }}</td>',
      '      </tr></table>',
      '      <div class="tk-foot"><span>收款单位（章）: {{ orDash(ticketData.header.orgName) }}</span><span>收款人: {{ orDash(ticketData.footer.chargeBy) }}</span><span>{{ orDash(ticketData.footer.chargeTime) }}</span></div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="ticketVisible=false">关闭</el-button>',
      '      <el-button type="primary" :disabled="!ticketData" @click="printTicket">打印票据</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ===== 退费对话框 ===== */
      '  <el-dialog v-model="refundVisible" title="收费退费" width="520px">',
      '    <el-descriptions :column="2" border size="small" style="margin-bottom:12px;">',
      '      <el-descriptions-item label="收费单号">{{ orDash(refundBill && refundBill.billNo) }}</el-descriptions-item>',
      '      <el-descriptions-item label="患者姓名">{{ orDash(refundBill && refundBill.patientName) }}</el-descriptions-item>',
      '      <el-descriptions-item label="收费金额"><span style="color:#F56C6C;font-weight:700;">¥{{ money(refundBill && refundBill.totalAmount) }}</span></el-descriptions-item>',
      '      <el-descriptions-item label="收费时间">{{ orDash(refundBill && refundBill.chargeTime) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <el-alert type="warning" :closable="false" show-icon title="退费后撤销医保结算并回写就诊为未收费; 已日结的收费单不可退费" style="margin-bottom:12px;"></el-alert>',
      '    <div style="margin-bottom:8px;font-weight:600;">退费原因 <span style="color:#F56C6C;">*</span></div>',
      '    <el-input v-model="refundReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请输入退费原因(必填)"></el-input>',
      '    <template #footer>',
      '      <el-button @click="refundVisible=false">取消</el-button>',
      '      <el-button type="danger" :loading="refunding" @click="doRefund">确认退费</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ===== 部分退费对话框 ===== */
      '  <el-dialog v-model="partialVisible" title="部分退费" width="860px" top="6vh">',
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
      '      <div style="margin-top:12px;font-weight:600;">退费原因 <span style="color:#F56C6C;">*</span></div>',
      '      <el-input v-model="partialReason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="请输入退费原因(必填)" style="margin-top:6px;"></el-input>',
      '      <div style="display:flex;justify-content:flex-end;align-items:center;margin-top:10px;font-size:14px;">退费合计: <b style="color:#F56C6C;font-size:18px;margin-left:6px;">¥{{ money(partialTotal()) }}</b></div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="partialVisible=false">取消</el-button>',
      '      <el-button type="danger" :loading="partialRefunding" @click="doPartialRefund">确认退费</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* 票据预览 11 类三列成对渲染 */
  HIS.views.ChargeWorkstation.methods.ticketCatPairs = function () {
    var cats = (this.ticketData && this.ticketData.cats) || [];
    var pairs = [];
    for (var i = 0; i < cats.length; i += 3) {
      pairs.push([cats[i], cats[i + 1], cats[i + 2]]);
    }
    return pairs;
  };
  /* 收据对话框中的医院名 */
  HIS.views.ChargeWorkstation.methods.HIS_getHospitalName = function () {
    return (HIS.getHospitalName && HIS.getHospitalName()) || '医院';
  };
})();
