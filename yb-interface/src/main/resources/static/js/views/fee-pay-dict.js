/* 患者费别 / 支付方式 自定义字典维护页(机构级, 门诊住院统一维护; 2026-10 费别与支付自定义字典)。
   单组件两页签: 患者费别(含结算通道/控费规则/自付比例/优惠方式/支付方式白名单) 与 支付方式(含分类/找零/预交金/日结/退费)。
   维护走机构级专用端点(/api/his/fee-pay-dict/*), 写守卫在 Service(requireLeadOrg: 仅牵头机构 ADMIN 可维护, 医疗机构管理员/非牵头只读); 内置项 auto_flag=1 编码锁定且禁删。
   牵头总览下字典跨机构聚合(每机构各一套), 故版式镜像用户管理/科室管理: 左栏机构树(可搜索/折叠/收缩, 点选驱动)+右栏两页签, 附「含下级机构」级联(后端 withSubOrgs+牵头 subtreeIds in 过滤)与机构列回显。
   scope 列以逗号分隔 token(OTP门诊/IPT住院/BOTH通用), 两页签维护页用「门诊/住院」两个勾选框表达。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var BASE = '/api/his/fee-pay-dict';
  /* 结算通道(仅数据/展示, 本期不改 2201 触发条件) */
  var CHANNELS = [
    { v: 'INSURANCE', l: '医保结算' }, { v: 'SELF', l: '自费' }, { v: 'GOV', l: '公费' },
    { v: 'UNIT', l: '单位' }, { v: 'HOSP', l: '本院职工' }, { v: 'HELP', l: '医疗救助' }, { v: 'OTHER', l: '其他' }
  ];
  /* 优惠方式 */
  var DISCOUNT_MODES = [
    { v: 'NONE', l: '无优惠' }, { v: 'RATE', l: '按比例优惠' }, { v: 'AMOUNT', l: '固定减免金额' }, { v: 'FULL', l: '全额免单' }
  ];
  var CTL_SCENES = [{ v: 'OTP', l: '门诊' }, { v: 'IPT', l: '住院' }, { v: 'BOTH', l: '通用' }];
  var PAY_KINDS = [
    { v: 'CASH', l: '现金' }, { v: 'ELECTRONIC', l: '电子支付' }, { v: 'CREDIT', l: '记账/挂账' },
    { v: 'FREE', l: '免费' }, { v: 'DEPOSIT', l: '预交金' }, { v: 'INSURANCE', l: '医保' }
  ];
  var REFUND_WAYS = [{ v: 'ORIGIN', l: '原路退回' }, { v: 'CASH', l: '现金退付' }, { v: 'ACCOUNT', l: '退预交金账户' }];
  var SCENE_OPTS = [{ v: 'OTP', l: '门诊' }, { v: 'IPT', l: '住院' }];

  function scopeToArr(scope) {
    var s = String(scope || 'BOTH').toUpperCase();
    if (s === 'BOTH') { return ['OTP', 'IPT']; }
    var a = []; if (s.indexOf('OTP') >= 0) { a.push('OTP'); } if (s.indexOf('IPT') >= 0) { a.push('IPT'); }
    return a.length ? a : ['OTP', 'IPT'];
  }
  function arrToScope(a) {
    a = a || [];
    var otp = a.indexOf('OTP') >= 0, ipt = a.indexOf('IPT') >= 0;
    return (otp && ipt) || (!otp && !ipt) ? 'BOTH' : (otp ? 'OTP' : 'IPT');
  }
  /* 空串→null(避免 BigDecimal/数值列绑定空串报错); 数字串→Number */
  function numOrNull(v) {
    if (v === null || v === undefined || v === '') { return null; }
    var n = Number(v); return isNaN(n) ? null : n;
  }
  function parsePayLimit(json) {
    var o = { otp: [], ipt: [] };
    if (!json) { return o; }
    try { var p = JSON.parse(json); o.otp = (p && p.otp) || []; o.ipt = (p && p.ipt) || []; } catch (e) { /* 容错: 非JSON按空 */ }
    return o;
  }

  function newFeeForm() {
    return {
      code: '', name: '', scopeArr: ['OTP', 'IPT'], channel: '', insutype: '',
      ctlFlag: 0, ctlHard: 0, ctlScene: 'BOTH', ctlAmount: '', ctlIptAmount: '', ctlDayAmount: '',
      selfpayRate: '', prepayRate: '', discountMode: 'NONE', discountRate: '', discountAmount: '',
      payLimitOtp: [], payLimitIpt: [], sortNo: 0, status: 1, memo: ''
    };
  }
  function feeFormFromRow(d) {
    d = d || {};
    var f = newFeeForm();
    f.code = d.code || ''; f.name = d.name || ''; f.scopeArr = scopeToArr(d.scope);
    f.channel = d.channel || ''; f.insutype = d.insutype || '';
    f.ctlFlag = d.ctlFlag == null ? 0 : Number(d.ctlFlag); f.ctlHard = d.ctlHard == null ? 0 : Number(d.ctlHard);
    f.ctlScene = d.ctlScene || 'BOTH';
    f.ctlAmount = d.ctlAmount == null ? '' : d.ctlAmount; f.ctlIptAmount = d.ctlIptAmount == null ? '' : d.ctlIptAmount;
    f.ctlDayAmount = d.ctlDayAmount == null ? '' : d.ctlDayAmount;
    f.selfpayRate = d.selfpayRate == null ? '' : d.selfpayRate; f.prepayRate = d.prepayRate == null ? '' : d.prepayRate;
    f.discountMode = d.discountMode || 'NONE';
    f.discountRate = d.discountRate == null ? '' : d.discountRate; f.discountAmount = d.discountAmount == null ? '' : d.discountAmount;
    var pl = parsePayLimit(d.payLimitJson); f.payLimitOtp = pl.otp; f.payLimitIpt = pl.ipt;
    f.sortNo = d.sortNo == null ? 0 : Number(d.sortNo); f.status = d.status == null ? 1 : Number(d.status);
    f.memo = d.memo || '';
    return f;
  }
  function newPayForm() {
    return { code: '', name: '', scopeArr: ['OTP', 'IPT'], legacyCodes: '', payKind: 'CASH', changeFlag: 0, depositFlag: 0, dayendFlag: 1, refundWay: 'ORIGIN', sortNo: 0, status: 1, memo: '' };
  }
  function payFormFromRow(d) {
    d = d || {};
    var f = newPayForm();
    f.code = d.code || ''; f.name = d.name || ''; f.scopeArr = scopeToArr(d.scope);
    f.legacyCodes = d.legacyCodes || ''; f.payKind = d.payKind || 'CASH';
    f.changeFlag = d.changeFlag == null ? 0 : Number(d.changeFlag);
    f.depositFlag = d.depositFlag == null ? 0 : Number(d.depositFlag);
    f.dayendFlag = d.dayendFlag == null ? 1 : Number(d.dayendFlag);
    f.refundWay = d.refundWay || 'ORIGIN';
    f.sortNo = d.sortNo == null ? 0 : Number(d.sortNo); f.status = d.status == null ? 1 : Number(d.status);
    f.memo = d.memo || '';
    return f;
  }

  HIS.views.FeePayDict = {
    data: function () {
      return {
        activeTab: 'fee',
        /* 患者费别列表 */
        feeKeyword: '', feeScope: '', feeStatus: null, feePage: 1, feeSize: 20,
        feeLoading: false, feeList: [], feeTotal: 0,
        /* 支付方式列表 */
        payKeyword: '', payScope: '', payStatus: null, payPage: 1, paySize: 20,
        payLoading: false, payList: [], payTotal: 0,
        /* 左栏机构树(仅牵头加载, 两页签共用作用域; 镜像用户管理/科室管理) */
        orgs: [], filterOrg: null, withSubOrgs: false,
        orgKw: '', orgFolded: {},
        orgsCollapsed: (function () { try { return localStorage.getItem('his.feePayOrgsCollapsed') === '1'; } catch (e) { return false; } })(),
        /* 费别弹窗 */
        feeDlg: false, feeEditing: false, feeSaving: false, feeEditId: null, feeActiveTab: 'basic', feeAuto: false,
        feeForm: newFeeForm(),
        /* 支付弹窗 */
        payDlg: false, payEditing: false, paySaving: false, payEditId: null, payAuto: false,
        payForm: newPayForm(),
        /* 选项常量 */
        channelOpts: CHANNELS, discountOpts: DISCOUNT_MODES, ctlSceneOpts: CTL_SCENES,
        payKindOpts: PAY_KINDS, refundOpts: REFUND_WAYS, sceneOpts: SCENE_OPTS,
        /* 支付方式白名单候选(费别弹窗用): 打开弹窗时按被编辑行的机构拉取(2026-10-03 修同名膨胀: options 不传 orgId 时牵头聚合全部机构×每套支付字典) */
        payPool: [], payPoolOrg: null
      };
    },
    computed: {
      canWrite: function () { return !!(HIS.isLead && HIS.isLead() && HIS.hasRole && HIS.hasRole('ADMIN')); },
      /* 牵头机构总览时字典跨机构聚合, 展示机构列+机构筛选以区分归属; 非牵头仅机要本机构无需 */
      showOrg: function () { return !!(HIS.isLead && HIS.isLead()); },
      orgMap: function () { var m = {}; (this.orgs || []).forEach(function (o) { m[String(o.id)] = String(o.label || o.orgName || '').trim(); }); return m; },
      /* 左栏机构树是否处于过滤态(强制展平 + caret 置灰) */
      searching: function () { return !!String(this.orgKw || '').trim(); },
      /* 机构父链索引: 前序展平数组中向前最近更小 orgLevel 即父机构(搜索保留父链用) */
      orgParentIdx: function () {
        var orgs = this.orgs; var par = {};
        for (var i = 0; i < orgs.length; i++) {
          for (var j = i - 1; j >= 0; j--) { if ((orgs[j].orgLevel || 1) < (orgs[i].orgLevel || 1)) { par[orgs[i].id] = orgs[j].id; break; } }
        }
        return par;
      },
      /* 左栏机构列表: 过滤态忽略折叠强制展平并保留命中祖先链+选中项; 否则按 orgFolded 跳过子树 */
      visibleOrgs: function () {
        var vm = this; var out = [];
        var kw = String(vm.orgKw || '').trim();
        if (kw) {
          var byId = {};
          vm.orgs.forEach(function (o) { byId[o.id] = o; });
          var par = vm.orgParentIdx;
          var keep = {};
          var markUp = function (o) { var c = o; while (c && !keep[c.id]) { keep[c.id] = 1; c = par[c.id] ? byId[par[c.id]] : null; } };
          vm.orgs.forEach(function (o) { if (HIS.kwMatch(o, kw, ['label', 'orgCode', 'pyCode'])) { markUp(o); } });
          /* 作用域可见性: 已选机构及其祖先始终保留 */
          if (vm.filterOrg && byId[vm.filterOrg]) { markUp(byId[vm.filterOrg]); }
          vm.orgs.forEach(function (o) { if (keep[o.id]) { out.push(o); } });
          return out;
        }
        var skipping = null;
        vm.orgs.forEach(function (o) {
          if (skipping !== null) {
            if ((o.depth || 0) > skipping) { return; }
            skipping = null;
          }
          out.push(o);
          if (o.hasKids && vm.orgFolded[o.id]) { skipping = o.depth || 0; }
        });
        return out;
      },
      feePh: function () { return '编码/名称/拼音简码检索'; },
      payPh: function () { return '编码/名称/拼音简码检索'; },
      channelMap: function () { var m = {}; CHANNELS.forEach(function (c) { m[c.v] = c.l; }); return m; },
      payKindMap: function () { var m = {}; PAY_KINDS.forEach(function (c) { m[c.v] = c.l; }); return m; },
      refundMap: function () { var m = {}; REFUND_WAYS.forEach(function (c) { m[c.v] = c.l; }); return m; },
      discountMap: function () { var m = {}; DISCOUNT_MODES.forEach(function (c) { m[c.v] = c.l; }); return m; }
    },
    created: function () {
      var vm = this;
      vm.fetchFee(); vm.fetchPay();
      /* 左栏机构树: 首屏默认收缩到二级(与用户管理/科室管理同口径) */
      if (vm.showOrg) { vm.loadOrgs().then(function () { vm.collapseOrgToLevel(2); }); }
    },
    methods: {
      feeSeq: function (i) { return (this.feePage - 1) * this.feeSize + i + 1; },
      paySeq: function (i) { return (this.payPage - 1) * this.paySize + i + 1; },
      scopeText: function (scope) {
        var a = scopeToArr(scope);
        return (a.indexOf('OTP') >= 0 ? '门诊' : '') + (a.length === 2 ? '/' : '') + (a.indexOf('IPT') >= 0 ? '住院' : '');
      },
      /* ===== 左栏机构树(镜像用户管理/科室管理: 点选驱动两页签共用作用域) ===== */
      loadOrgs: function () {
        var vm = this;
        return HIS.get('/api/sys/org/tree').then(function (d) { vm.orgs = HIS.flattenOrgs(d || []); }).catch(function () { vm.orgs = []; });
      },
      /* 点选机构: null=全部机构; 两页签各自回第1页重查 */
      selectOrg: function (id) { if (this.filterOrg === id) { return; } this.filterOrg = id; this.searchFee(); this.searchPay(); },
      /* 含下级机构开关变化: 维持当前选中机构重查两页签 */
      reloadScope: function () { this.searchFee(); this.searchPay(); },
      /* 左栏收缩/展开切换并持久化 */
      toggleOrgs: function () {
        this.orgsCollapsed = !this.orgsCollapsed;
        try { localStorage.setItem('his.feePayOrgsCollapsed', this.orgsCollapsed ? '1' : '0'); } catch (e) { }
      },
      /* 折叠/展开机构下级子树(仅显隐, 不触发筛选) */
      toggleOrgFold: function (o) { this.orgFolded[o.id] = !this.orgFolded[o.id]; },
      /* 机构树标签高亮: 转义 HTML 后将命中子串包入 <mark>(大小写不敏感) */
      hl: function (text) {
        var t = String(text == null ? '' : text);
        var esc = function (s) { return s.replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); };
        var kw = String(this.orgKw || '').trim();
        if (!kw) { return esc(t); }
        var lower = t.toLowerCase(); var k = kw.toLowerCase(); var out = ''; var i = 0;
        for (;;) {
          var idx = lower.indexOf(k, i);
          if (idx < 0) { out += esc(t.slice(i)); break; }
          out += esc(t.slice(i, idx)) + '<mark class="kw-hit">' + esc(t.slice(idx, idx + kw.length)) + '</mark>';
          i = idx + kw.length;
        }
        return out;
      },
      /* 左栏机构悬停提示: 名称+编码+拼音, 命中编码/拼音而无可见高亮时可解释 */
      orgTitle: function (o) {
        var parts = [String(o.label || '').trim()];
        if (o.orgCode) { parts.push('编码 ' + o.orgCode); }
        if (o.pyCode) { parts.push('拼音 ' + o.pyCode); }
        return parts.join(' / ');
      },
      /* 机构树批量展开/收缩/到层级(搜索态强制展平, 先清关键字使操作即时可见) */
      expandAllOrg: function () { this.orgKw = ''; this.orgFolded = {}; },
      collapseAllOrg: function () {
        this.orgKw = ''; var f = {}; this.orgs.forEach(function (o) { if (o.hasKids && (o.depth || 0) >= 1) { f[o.id] = true; } }); this.orgFolded = f;
      },
      collapseOrgToLevel: function (n) {
        this.orgKw = '';
        if (!n) { this.orgFolded = {}; return; }
        var cap = n - 1; var f = {};
        this.orgs.forEach(function (o) { if (o.hasKids && (o.depth || 0) >= cap) { f[o.id] = true; } });
        this.orgFolded = f;
      },
      /* ===== 患者费别 ===== */
      searchFee: function () { this.feePage = 1; this.fetchFee(); },
      onFeePage: function (p) { this.feePage = p; this.fetchFee(); },
      onFeeSize: function (s) { this.feeSize = s; this.feePage = 1; this.fetchFee(); },
      fetchFee: function () {
        var vm = this; vm.feeLoading = true;
        var q = BASE + '/fee-type/page?page=' + vm.feePage + '&size=' + vm.feeSize;
        if (vm.feeKeyword) { q += '&keyword=' + encodeURIComponent(vm.feeKeyword); }
        if (vm.feeScope) { q += '&scope=' + vm.feeScope; }
        if (vm.feeStatus !== null && vm.feeStatus !== '') { q += '&status=' + vm.feeStatus; }
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg + '&withSubOrgs=' + (vm.withSubOrgs ? 'true' : 'false'); }
        HIS.get(q).then(function (d) {
          vm.feeList = (d && d.records) || []; vm.feeTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.feeLoading = false; });
      },
      /* 白名单候选按机构懒加载: 牵头编辑他机构行时传该行 orgId(后端 scopeOrgId 兜底, 非牵头恒锁本机构); 同码去重防重复渲染 */
      loadPayPool: function (orgId) {
        var vm = this;
        vm.payPoolOrg = orgId || HIS.currentOrgId() || '';
        HIS.get(BASE + '/pay-method/options?orgId=' + encodeURIComponent(vm.payPoolOrg)).then(function (d) {
          var seen = {}, out = [];
          (d || []).forEach(function (p) { if (!seen[p.code]) { seen[p.code] = 1; out.push(p); } });
          vm.payPool = out;
        }).catch(function () { vm.payPool = []; });
      },
      openFeeCreate: function () {
        this.feeForm = newFeeForm(); this.feeEditing = false; this.feeEditId = null; this.feeAuto = false; this.feeActiveTab = 'basic';
        this.loadPayPool(HIS.currentOrgId());
        this.feeDlg = true;
      },
      openFeeEdit: function (row) {
        this.feeForm = feeFormFromRow(row); this.feeEditing = true; this.feeEditId = row.id;
        this.feeAuto = row.autoFlag === 1; this.feeActiveTab = 'basic'; this.feeDlg = true;
        /* 候选跟随该行机构(牵头总览可编辑任意机构, 不随机构重拉会沿用上一家候选致 code 对不上) */
        if (row.orgId !== this.payPoolOrg) { this.loadPayPool(row.orgId); }
      },
      saveFee: function () {
        var vm = this; var f = vm.feeForm;
        if (!vm.canWrite) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护费别与支付'); return; }
        if (!f.code || !String(f.code).trim()) { ElementPlus.ElMessage.warning('费别编码必填'); return; }
        if (!f.name || !String(f.name).trim()) { ElementPlus.ElMessage.warning('费别名称必填'); return; }
        var body = {
          code: String(f.code).trim(), name: String(f.name).trim(), scope: arrToScope(f.scopeArr),
          channel: f.channel || null, insutype: f.insutype || null,
          ctlFlag: Number(f.ctlFlag), ctlHard: Number(f.ctlHard), ctlScene: f.ctlScene || null,
          ctlAmount: numOrNull(f.ctlAmount), ctlIptAmount: numOrNull(f.ctlIptAmount), ctlDayAmount: numOrNull(f.ctlDayAmount),
          selfpayRate: numOrNull(f.selfpayRate), prepayRate: numOrNull(f.prepayRate),
          discountMode: f.discountMode || 'NONE', discountRate: numOrNull(f.discountRate), discountAmount: numOrNull(f.discountAmount),
          sortNo: numOrNull(f.sortNo) == null ? 0 : Number(f.sortNo), status: Number(f.status), memo: f.memo || null
        };
        var pl = {}; if (f.payLimitOtp && f.payLimitOtp.length) { pl.otp = f.payLimitOtp; } if (f.payLimitIpt && f.payLimitIpt.length) { pl.ipt = f.payLimitIpt; }
        body.payLimitJson = (pl.otp || pl.ipt) ? JSON.stringify(pl) : null;
        vm.feeSaving = true;
        var p = vm.feeEditing
          ? HIS.put(BASE + '/fee-type/update?id=' + vm.feeEditId, body)
          : HIS.post(BASE + '/fee-type/create', body);
        p.then(function () {
          HIS.notifySuccess(vm.feeEditing ? '修改成功' : '新增成功');
          vm.feeDlg = false; vm.fetchFee();
        }).catch(HIS.notifyError).finally(function () { vm.feeSaving = false; });
      },
      delFee: function (row) {
        var vm = this;
        if (!vm.canWrite) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护费别与支付'); return; }
        ElementPlus.ElMessageBox.confirm('确认删除费别【' + row.name + '】? 历史挂号/住院单据按编码引用, 删除后名称将无法回显。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del(BASE + '/fee-type/delete?id=' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.fetchFee(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      /* ===== 支付方式 ===== */
      searchPay: function () { this.payPage = 1; this.fetchPay(); },
      onPayPage: function (p) { this.payPage = p; this.fetchPay(); },
      onPaySize: function (s) { this.paySize = s; this.payPage = 1; this.fetchPay(); },
      fetchPay: function () {
        var vm = this; vm.payLoading = true;
        var q = BASE + '/pay-method/page?page=' + vm.payPage + '&size=' + vm.paySize;
        if (vm.payKeyword) { q += '&keyword=' + encodeURIComponent(vm.payKeyword); }
        if (vm.payScope) { q += '&scope=' + vm.payScope; }
        if (vm.payStatus !== null && vm.payStatus !== '') { q += '&status=' + vm.payStatus; }
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg + '&withSubOrgs=' + (vm.withSubOrgs ? 'true' : 'false'); }
        HIS.get(q).then(function (d) {
          vm.payList = (d && d.records) || []; vm.payTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.payLoading = false; });
      },
      openPayCreate: function () {
        this.payForm = newPayForm(); this.payEditing = false; this.payEditId = null; this.payAuto = false;
        this.payDlg = true;
      },
      openPayEdit: function (row) {
        this.payForm = payFormFromRow(row); this.payEditing = true; this.payEditId = row.id; this.payAuto = row.autoFlag === 1;
        this.payDlg = true;
      },
      savePay: function () {
        var vm = this; var f = vm.payForm;
        if (!vm.canWrite) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护费别与支付'); return; }
        if (!f.code || !String(f.code).trim()) { ElementPlus.ElMessage.warning('支付方式编码必填'); return; }
        if (!f.name || !String(f.name).trim()) { ElementPlus.ElMessage.warning('支付方式名称必填'); return; }
        var body = {
          code: String(f.code).trim().toUpperCase(), name: String(f.name).trim(), scope: arrToScope(f.scopeArr),
          legacyCodes: f.legacyCodes ? String(f.legacyCodes).trim() : null, payKind: f.payKind || null,
          changeFlag: Number(f.changeFlag), depositFlag: Number(f.depositFlag), dayendFlag: Number(f.dayendFlag),
          refundWay: f.refundWay || null, sortNo: numOrNull(f.sortNo) == null ? 0 : Number(f.sortNo), status: Number(f.status), memo: f.memo || null
        };
        vm.paySaving = true;
        var p = vm.payEditing
          ? HIS.put(BASE + '/pay-method/update?id=' + vm.payEditId, body)
          : HIS.post(BASE + '/pay-method/create', body);
        p.then(function () {
          HIS.notifySuccess(vm.payEditing ? '修改成功' : '新增成功');
          vm.payDlg = false; vm.fetchPay();
        }).catch(HIS.notifyError).finally(function () { vm.paySaving = false; });
      },
      delPay: function (row) {
        var vm = this;
        if (!vm.canWrite) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护费别与支付'); return; }
        ElementPlus.ElMessageBox.confirm('确认删除支付方式【' + row.name + '】? 历史收费/预交金单据按编码引用, 删除后名称将无法回显。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del(BASE + '/pay-method/delete?id=' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.fetchPay(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">费别与支付 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(机构级自定义 · 门诊/住院统一维护 · 内置项禁删且编码锁定<span v-if="!canWrite"> · 只读查阅(仅牵头机构管理员可维护)</span></span>)</div>',
      '  <div class="dept-split fp-split">',
      '    <div v-if="showOrg" class="dept-orgs" :class="{collapsed: orgsCollapsed}">',
      '      <div class="dept-orgs-hd">',
      '        <span v-show="!orgsCollapsed">机构列表</span>',
      '        <span v-show="orgsCollapsed" class="dept-orgs-vt">机构列表</span>',
      '        <el-button link size="small" class="dept-orgs-tg" :title="orgsCollapsed?\'展开机构列表\':\'收缩机构列表\'" @click="toggleOrgs">{{ orgsCollapsed ? "\u00bb" : "\u00ab" }}</el-button>',
      '      </div>',
      '      <div v-show="!orgsCollapsed" style="padding:6px 8px 0;">',
      '        <el-input v-model="orgKw" size="small" clearable placeholder="过滤机构名/编码"></el-input>',
      '        <div class="dept-tree-tools">',
      '          <span class="dept-tree-tools-lb">层级</span>',
      '          <el-button link size="small" class="dept-tree-btn" title="展开全部层级" @click="expandAllOrg">展开</el-button>',
      '          <el-dropdown trigger="click" class="dept-tree-drop" @command="collapseOrgToLevel">',
      '            <el-button link size="small" class="dept-tree-btn" title="展开/收缩到指定层级">到层级\u25be</el-button>',
      '            <template #dropdown>',
      '              <el-dropdown-menu>',
      '                <el-dropdown-item :command="1">一级（顶级机构）</el-dropdown-item>',
      '                <el-dropdown-item :command="2">二级（卫生院/社区）</el-dropdown-item>',
      '                <el-dropdown-item :command="3">三级（卫生室）</el-dropdown-item>',
      '              </el-dropdown-menu>',
      '            </template>',
      '          </el-dropdown>',
      '          <el-button link size="small" class="dept-tree-btn" title="收缩到二级(显示二级医疗机构)" @click="collapseAllOrg">收缩</el-button>',
      '        </div>',
      '      </div>',
      '      <el-scrollbar v-show="!orgsCollapsed">',
      '        <div class="dept-org-item" :class="{active: filterOrg===null}" @click="selectOrg(null)"><span class="tree-caret"></span><span>全部机构</span></div>',
      '        <div v-for="o in visibleOrgs" :key="o.id" class="dept-org-item" :title="orgTitle(o)" :class="{active: filterOrg===o.id}" @click="selectOrg(o.id)"><span v-if="o.hasKids" class="tree-caret" :class="{\'is-inert\': searching}" :title="searching?\'过滤态自动展开全部层级, 清空关键字后可折叠\':\'折叠/展开\'" @click.stop="searching ? null : toggleOrgFold(o)">{{ (searching || !orgFolded[o.id]) ? \'▾\' : \'▸\' }}</span><span v-else class="tree-caret"></span><span v-html="hl(o.label)"></span></div>',
      '        <div v-if="orgKw && !visibleOrgs.length" class="dept-tree-empty">无匹配机构</div>',
      '      </el-scrollbar>',
      '    </div>',
      '    <div class="dept-main">',
      '      <el-tabs v-model="activeTab">',

      /* ================= 患者费别 页签 ================= */
      '  <el-tab-pane label="患者费别" name="fee">',
      '    <div class="toolbar">',
      '      <el-input v-model="feeKeyword" :placeholder="feePh" clearable style="width:220px" @keyup.enter="searchFee"></el-input>',
      '      <el-select v-model="feeScope" placeholder="适用场景" clearable style="width:130px"><el-option label="门诊" value="OTP"></el-option><el-option label="住院" value="IPT"></el-option><el-option label="通用" value="BOTH"></el-option></el-select>',
      '      <el-select v-model="feeStatus" placeholder="状态" clearable style="width:110px"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '      <el-checkbox v-if="showOrg" v-model="withSubOrgs" @change="reloadScope" :disabled="!filterOrg" :title="filterOrg ? \'勾选后选中机构时级联显示下级机构的费别; 默认仅显示选中机构本身的费别\' : \'先在左侧选中机构后可用\'" style="margin-left:4px;">含下级机构</el-checkbox>',
      '      <el-button type="primary" @click="searchFee">检索</el-button>',
      '      <el-button v-if="canWrite" type="success" @click="openFeeCreate">新增费别</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;margin-left:auto;">共 {{ feeTotal }} 项</span>',
      '    </div>',
      '    <el-table :data="feeList" v-loading="feeLoading" border stripe size="small" height="100%">',
      '      <el-table-column type="index" label="序号" width="60" :index="feeSeq"></el-table-column>',
      '      <el-table-column prop="code" label="编码" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="name" label="费别名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '      <el-table-column v-if="showOrg" label="机构" width="150" show-overflow-tooltip><template #default="s">{{ orgMap[String(s.row.orgId)] || s.row.orgId || \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '      <el-table-column label="适用场景" width="100"><template #default="s">{{ scopeText(s.row.scope) }}</template></el-table-column>',
      '      <el-table-column label="通道" width="100"><template #default="s">{{ channelMap[s.row.channel] || s.row.channel || \'-\' }}</template></el-table-column>',
      '      <el-table-column label="优惠" width="110"><template #default="s">{{ discountMap[s.row.discountMode] || \'无\' }}</template></el-table-column>',
      '      <el-table-column label="控费" width="60" align="center"><template #default="s"><el-tag v-if="s.row.ctlFlag===1" type="warning" size="small">开</el-tag><span v-else>—</span></template></el-table-column>',
      '      <el-table-column label="内置" width="60" align="center"><template #default="s"><el-tag v-if="s.row.autoFlag===1" size="small">内置</el-tag><span v-else>—</span></template></el-table-column>',
      '      <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag :type="s.row.status===0 ? \'info\' : \'success\'" size="small">{{ s.row.status===0 ? \'停用\' : \'启用\' }}</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="120" fixed="right"><template #default="s">',
      '        <el-button v-if="canWrite" link type="primary" @click="openFeeEdit(s.row)">编辑</el-button>',
      '        <el-button v-if="canWrite && s.row.autoFlag!==1" link type="danger" @click="delFee(s.row)">删除</el-button>',
      '      </template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="feeTotal" :page-size="feeSize" :page-sizes="[10,20,50,100]" :current-page="feePage" @current-change="onFeePage" @size-change="onFeeSize"></el-pagination>',
      '  </el-tab-pane>',

      /* ================= 支付方式 页签 ================= */
      '  <el-tab-pane label="支付方式" name="pay">',
      '    <div class="toolbar">',
      '      <el-input v-model="payKeyword" :placeholder="payPh" clearable style="width:220px" @keyup.enter="searchPay"></el-input>',
      '      <el-select v-model="payScope" placeholder="适用场景" clearable style="width:130px"><el-option label="门诊" value="OTP"></el-option><el-option label="住院" value="IPT"></el-option><el-option label="通用" value="BOTH"></el-option></el-select>',
      '      <el-select v-model="payStatus" placeholder="状态" clearable style="width:110px"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '      <el-checkbox v-if="showOrg" v-model="withSubOrgs" @change="reloadScope" :disabled="!filterOrg" :title="filterOrg ? \'勾选后选中机构时级联显示下级机构的支付方式; 默认仅显示选中机构本身的支付方式\' : \'先在左侧选中机构后可用\'" style="margin-left:4px;">含下级机构</el-checkbox>',
      '      <el-button type="primary" @click="searchPay">检索</el-button>',
      '      <el-button v-if="canWrite" type="success" @click="openPayCreate">新增支付方式</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;margin-left:auto;">共 {{ payTotal }} 项</span>',
      '    </div>',
      '    <el-table :data="payList" v-loading="payLoading" border stripe size="small" height="100%">',
      '      <el-table-column type="index" label="序号" width="60" :index="paySeq"></el-table-column>',
      '      <el-table-column prop="code" label="规范码" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column v-if="showOrg" label="机构" width="150" show-overflow-tooltip><template #default="s">{{ orgMap[String(s.row.orgId)] || s.row.orgId || \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '      <el-table-column label="适用场景" width="100"><template #default="s">{{ scopeText(s.row.scope) }}</template></el-table-column>',
      '      <el-table-column label="分类" width="90"><template #default="s">{{ payKindMap[s.row.payKind] || s.row.payKind || \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="legacyCodes" label="历史旧值" width="120" show-overflow-tooltip><template #default="s">{{ s.row.legacyCodes || \'-\' }}</template></el-table-column>',
      '      <el-table-column label="预交金" width="70" align="center"><template #default="s"><el-tag v-if="s.row.depositFlag===1" type="success" size="small">可充</el-tag><span v-else>—</span></template></el-table-column>',
      '      <el-table-column label="内置" width="60" align="center"><template #default="s"><el-tag v-if="s.row.autoFlag===1" size="small">内置</el-tag><span v-else>—</span></template></el-table-column>',
      '      <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag :type="s.row.status===0 ? \'info\' : \'success\'" size="small">{{ s.row.status===0 ? \'停用\' : \'启用\' }}</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="120" fixed="right"><template #default="s">',
      '        <el-button v-if="canWrite" link type="primary" @click="openPayEdit(s.row)">编辑</el-button>',
      '        <el-button v-if="canWrite && s.row.autoFlag!==1" link type="danger" @click="delPay(s.row)">删除</el-button>',
      '      </template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="payTotal" :page-size="paySize" :page-sizes="[10,20,50,100]" :current-page="payPage" @current-change="onPayPage" @size-change="onPaySize"></el-pagination>',
      '  </el-tab-pane>',

      '      </el-tabs>',
      '    </div>',
      '  </div>',

      /* ================= 费别编辑弹窗 ================= */
      '  <el-dialog v-model="feeDlg" :title="feeEditing ? (\'编辑费别 #\' + feeEditId) : \'新增费别\'" width="880px" top="4vh" :close-on-click-modal="false">',
      '    <el-tabs v-model="feeActiveTab" type="border-card">',
      '    <el-tab-pane label="基础与场景" name="basic">',
      '      <el-form label-position="top" size="small">',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="费别编码 *"><el-input v-model="feeForm.code" :disabled="feeEditing" placeholder="如 GONGFEI(历史项 self/insurance 锁定)"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="费别名称 *"><el-input v-model="feeForm.name" placeholder="如 公费/本院职工"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="适用场景"><el-checkbox-group v-model="feeForm.scopeArr"><el-checkbox v-for="s in sceneOpts" :key="s.v" :label="s.v">{{ s.l }}</el-checkbox></el-checkbox-group></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="结算通道"><el-select v-model="feeForm.channel" clearable style="width:100%" placeholder="选择通道"><el-option v-for="c in channelOpts" :key="c.v" :label="c.l" :value="c.v"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="关联医保险种"><el-input v-model="feeForm.insutype" placeholder="仅医保通道, 如 3100(可留空)"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="状态"><el-select v-model="feeForm.status" style="width:100%"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select></el-form-item></el-col>',
      '        </el-row>',
      '        <el-divider content-position="left">支付方式白名单(留空=不限)</el-divider>',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="门诊可用支付方式"><el-select v-model="feeForm.payLimitOtp" multiple collapse-tags clearable style="width:100%" placeholder="不限"><el-option v-for="p in payPool" :key="p.code" :label="p.name" :value="p.code"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="住院可用支付方式"><el-select v-model="feeForm.payLimitIpt" multiple collapse-tags clearable style="width:100%" placeholder="不限"><el-option v-for="p in payPool" :key="p.code" :label="p.name" :value="p.code"></el-option></el-select></el-form-item></el-col>',
      '        </el-row>',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="排序号"><el-input v-model.number="feeForm.sortNo" type="number"></el-input></el-form-item></el-col>',
      '          <el-col :span="16"><el-form-item label="备注"><el-input v-model="feeForm.memo"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '    </el-tab-pane>',

      '    <el-tab-pane label="控费与自付" name="ctl">',
      '      <el-form label-position="top" size="small">',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="启用控费"><el-switch v-model="feeForm.ctlFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.ctlFlag===1"><el-form-item label="控费强度"><el-select v-model="feeForm.ctlHard" style="width:100%"><el-option label="超阈提示" :value="0"></el-option><el-option label="强阻断" :value="1"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.ctlFlag===1"><el-form-item label="控费适用场景"><el-select v-model="feeForm.ctlScene" style="width:100%"><el-option v-for="c in ctlSceneOpts" :key="c.v" :label="c.l" :value="c.v"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.ctlFlag===1"><el-form-item label="门诊次均限额(元)"><el-input v-model="feeForm.ctlAmount" type="number"></el-input></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.ctlFlag===1"><el-form-item label="住院次均限额(元)"><el-input v-model="feeForm.ctlIptAmount" type="number"></el-input></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.ctlFlag===1"><el-form-item label="住院日均限额(元)"><el-input v-model="feeForm.ctlDayAmount" type="number"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="院内自付比例(%)"><el-input v-model="feeForm.selfpayRate" type="number" placeholder="0~100"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="住院预交金测算比例(%)"><el-input v-model="feeForm.prepayRate" type="number"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '        <div style="font-size:12px;color:var(--yb-ink-2);">说明: 控费限额本期仅在结算前作提示位, 不做资金冻结; 自付比例叠加于目录自付之上。</div>',
      '      </el-form>',
      '    </el-tab-pane>',

      '    <el-tab-pane label="优惠方式" name="discount">',
      '      <el-form label-position="top" size="small">',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="优惠方式"><el-select v-model="feeForm.discountMode" style="width:100%"><el-option v-for="d in discountOpts" :key="d.v" :label="d.l" :value="d.v"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.discountMode===\'RATE\'"><el-form-item label="优惠比例(%)"><el-input v-model="feeForm.discountRate" type="number" placeholder="如 50 表示半价"></el-input></el-form-item></el-col>',
      '          <el-col :span="8" v-if="feeForm.discountMode===\'AMOUNT\'"><el-form-item label="固定减免金额(元)"><el-input v-model="feeForm.discountAmount" type="number"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '        <div style="font-size:12px;color:var(--yb-ink-2);">说明: 挂号时若前端未显式选择减免, 按本费别优惠方式对挂号费自动计算; 前端显式减免优先。FULL=全额免, NONE=不优惠。</div>',
      '      </el-form>',
      '    </el-tab-pane>',
      '    </el-tabs>',
      '    <template #footer><el-button @click="feeDlg=false">取消</el-button><el-button type="primary" :loading="feeSaving" @click="saveFee">保存</el-button></template>',
      '  </el-dialog>',

      /* ================= 支付方式编辑弹窗 ================= */
      '  <el-dialog v-model="payDlg" :title="payEditing ? (\'编辑支付方式 #\' + payEditId) : \'新增支付方式\'" width="720px" top="6vh" :close-on-click-modal="false">',
      '    <el-form label-position="top" size="small">',
      '      <el-row :gutter="16">',
      '        <el-col :span="8"><el-form-item label="规范码 * (大写)"><el-input v-model="payForm.code" :disabled="payEditing" placeholder="如 UNIONPAY"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="名称 *"><el-input v-model="payForm.name" placeholder="如 银联卡"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="适用场景"><el-checkbox-group v-model="payForm.scopeArr"><el-checkbox v-for="s in sceneOpts" :key="s.v" :label="s.v">{{ s.l }}</el-checkbox></el-checkbox-group></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="分类"><el-select v-model="payForm.payKind" style="width:100%"><el-option v-for="k in payKindOpts" :key="k.v" :label="k.l" :value="k.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="退费方式"><el-select v-model="payForm.refundWay" style="width:100%"><el-option v-for="r in refundOpts" :key="r.v" :label="r.l" :value="r.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="状态"><el-select v-model="payForm.status" style="width:100%"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="历史旧值映射(逗号分隔, 如 cash,1)"><el-input v-model="payForm.legacyCodes" placeholder="存量数据/挂号小写/住院数字码, 供回显归一"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="需找零"><el-switch v-model="payForm.changeFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="可充住院预交金"><el-switch v-model="payForm.depositFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="纳入日结"><el-switch v-model="payForm.dayendFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="排序号"><el-input v-model.number="payForm.sortNo" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="16"><el-form-item label="备注"><el-input v-model="payForm.memo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer><el-button @click="payDlg=false">取消</el-button><el-button type="primary" :loading="paySaving" @click="savePay">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
