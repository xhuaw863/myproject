/* T48 P3 移动护理终端: 独立 Vue3 单页(无 Element Plus, 原生 CSS)。
 * 功能页: 登录 → 首页(体征录入/医嘱执行/输液管理/扫码核对)。
 * 后端复用住院护士站既有接口(登录态与主系统同键共享):
 *   登录      POST /api/auth/login {tenantCode, username, password}
 *   病区      GET  /api/his/inp/bed/ward/list
 *   病区患者  GET  /api/his/inp/nurse/patients?wardId=&keyword=
 *   执行计划  GET  /api/his/inp/order-exec/plan?wardId=&date=&page&size (整病区, 前端分组)
 *   批量执行  POST /api/his/inp/order-exec/execute [execId,...] (needDoubleCheck 时提示回护士站)
 *   体征录入  POST /api/his/inp/nursing/ {inpVisitId, recordType:1, content:JSON}
 *   扫码识别  GET  /api/his/inp/patients?keyword=&visitStatus=2 + /api/his/inp/allergy/list?visitId=
 */
;(function () {
  'use strict';

  var TOKEN_KEY = 'yb_his_token';   /* 与主系统 api.js 同键: 可共享登录态 */
  var USER_KEY = 'yb_his_user';

  /* ===== 令牌与用户 ===== */
  function getToken() { try { return localStorage.getItem(TOKEN_KEY); } catch (e) { return null; } }
  function setToken(t) { try { if (t) { localStorage.setItem(TOKEN_KEY, t); } else { localStorage.removeItem(TOKEN_KEY); } } catch (e) { } }
  function getUser() { try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch (e) { return null; } }
  function setUser(u) { try { if (u) { localStorage.setItem(USER_KEY, JSON.stringify(u)); } else { localStorage.removeItem(USER_KEY); } } catch (e) { } }

  /* ===== 迷你请求层(R{code,msg,data} 信封, 401 回登录页) ===== */
  var onUnauthorized = function () { };
  function request(method, url, body) {
    var headers = { 'Content-Type': 'application/json' };
    var token = getToken();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    return fetch(url, { method: method, headers: headers, body: body == null ? undefined : JSON.stringify(body) })
      .then(function (resp) { return resp.json(); })
      .then(function (data) {
        if (data && data.code === 401) {
          setToken(null); setUser(null);
          onUnauthorized();
          throw new Error(data.msg || '登录已过期');
        }
        if (!data || data.code !== 0) { throw new Error((data && data.msg) || '请求失败'); }
        return data.data;
      });
  }
  function get(url) { return request('GET', url); }
  function post(url, body) { return request('POST', url, body); }

  /* ===== 工具 ===== */
  function pad2(n) { return ('0' + n).slice(-2); }
  function nowHM() { var d = new Date(); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }
  function fmtTime(v) {
    if (v == null || v === '') { return '-'; }
    if (typeof v === 'number') {
      var d = new Date(v);
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    }
    return String(v).replace('T', ' ').substring(0, 16);
  }
  function isOverdue(planTime) {
    if (!planTime) { return false; }
    var d = new Date(String(planTime).replace(' ', 'T'));
    return !isNaN(d.getTime()) && d.getTime() < Date.now();
  }
  function genderText(g) {
    var v = String(g || '');
    if (v === '1' || v === '男') { return '男'; }
    if (v === '2' || v === '女') { return '女'; }
    return v || '-';
  }
  function num(v) { var n = parseFloat(v); return isNaN(n) ? null : n; }

  /* ===== 常量(与后端枚举一致) ===== */
  var CAT = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  var ST = { 1: '待执行', 2: '已执行', 3: '未执行' };

  /* ===== 图标(内联 SVG, stroke 风格) ===== */
  var I = {
    heart: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20.3 4.6 13a4.8 4.8 0 0 1 0-6.9 4.9 4.9 0 0 1 6.9 0l.5.5.5-.5a4.9 4.9 0 0 1 6.9 0 4.8 4.8 0 0 1 0 6.9z"/><path d="M12 8.5v4"/><path d="M10 10.5h4"/></svg>',
    pill: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="8" width="18" height="8" rx="4"/><path d="M12 8v8"/><path d="M7.5 11.5h1M9 13.5h2.5M15 11.5h1.5M15.5 13.5h1"/></svg>',
    drip: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6"/><rect x="7.5" y="5" width="9" height="7" rx="2"/><path d="M12 12v3"/><path d="M10.5 17.5h3l-.6 3h-1.8z"/><path d="M9.5 8h5"/></svg>',
    scan: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2"/><path d="M7 12h10"/></svg>'
  };

  var MobileApp = {
    data: function () {
      return {
        page: (getToken() && getUser()) ? 'home' : 'login',
        logging: false,
        loginForm: { tenantCode: 'H42010000000', username: '', password: '' },
        user: getUser() || {},
        /* 病区上下文 */
        wards: [],
        wardId: null,
        wardPatients: [],
        /* 医嘱执行 / 输液管理(共用执行计划数据) */
        plan: [],
        planLoading: false,
        sel: {},
        executing: false,
        /* 体征录入 */
        patId: null,
        vitals: { temperature: '', pulse: '', respiration: '', systolicBp: '', diastolicBp: '', spo2: '' },
        submittingVitals: false,
        /* 扫码核对 */
        scanInput: '',
        scanning: false,
        scanPatient: null,
        scanOrders: [],
        scanSel: {},
        scanAllergies: [],
        /* toast */
        toastMsg: '',
        toastShow: false,
        toastTimer: null,
        /* 模板常量 */
        icons: I,
        catMap: CAT,
        stMap: ST
      };
    },
    computed: {
      wardName: function () {
        var vm = this;
        var w = this.wards.filter(function (x) { return String(x.id) === String(vm.wardId); })[0];
        return w ? w.wardName : '-';
      },
      /* 医嘱执行页: 待执行计划按患者分组(全部/单患者两种视图) */
      pendingOrders: function () {
        return this.plan.filter(function (r) { return Number(r.execStatus) === 1; });
      },
      orderGroups: function () {
        return this.groupByPatient(this.pendingOrders);
      },
      /* 输液管理页: 药品类别(orderCategory=1)待执行 */
      infusionOrders: function () {
        return this.pendingOrders.filter(function (r) { return Number(r.orderCategory) === 1; });
      },
      infusionGroups: function () {
        return this.groupByPatient(this.infusionOrders);
      },
      selectedExecIds: function () {
        var ids = [];
        this.pendingOrders.forEach(function (r) { if (r.__on) { ids.push(r.id); } });
        return ids;
      },
      infusionSelIds: function () {
        var ids = [];
        this.infusionOrders.forEach(function (r) { if (r.__on) { ids.push(r.id); } });
        return ids;
      },
      /* 体征录入目标患者 */
      vitalsPatient: function () {
        var vm = this;
        return this.wardPatients.filter(function (p) { return String(p.id) === String(vm.patId); })[0] || null;
      },
      scanPending: function () {
        return this.scanOrders.filter(function (r) { return Number(r.execStatus) === 1; });
      },
      scanSelIds: function () {
        var ids = [];
        this.scanPending.forEach(function (r) { if (r.__on) { ids.push(r.id); } });
        return ids;
      },
      scanAllergyText: function () {
        return (this.scanAllergies || []).map(function (a) { return a.allergenName; }).filter(Boolean).join('、');
      }
    },
    mounted: function () {
      var vm = this;
      onUnauthorized = function () {
        vm.page = 'login';
        vm.toast('登录已过期, 请重新登录');
      };
      if (vm.page === 'home') { vm.loadWards(); }
    },
    methods: {
      /* ===== 通用 ===== */
      go: function (p) { this.page = p; },
      toast: function (msg) {
        var vm = this;
        vm.toastMsg = msg;
        vm.toastShow = true;
        if (vm.toastTimer) { clearTimeout(vm.toastTimer); }
        vm.toastTimer = setTimeout(function () { vm.toastShow = false; }, 2200);
      },
      genderText: genderText,
      fmtTime: fmtTime,
      isOverdue: isOverdue,

      /* ===== 登录 / 登出 ===== */
      doLogin: function () {
        var vm = this;
        var f = vm.loginForm;
        if (!f.tenantCode || !f.username || !f.password) { vm.toast('请填写医院码、账号与密码'); return; }
        vm.logging = true;
        post('/api/auth/login', f)
          .then(function (d) {
            setToken(d.token);
            setUser(d);
            vm.user = d || {};
            vm.page = 'home';
            vm.toast('登录成功, ' + (d.realName || d.username));
            vm.loadWards();
          })
          .catch(function (e) { vm.toast(e.message || '登录失败'); })
          .finally(function () { vm.logging = false; });
      },
      logout: function () {
        var vm = this;
        setToken(null); setUser(null);
        vm.user = {};
        vm.page = 'login';
        vm.loginForm.password = '';
      },

      /* ===== 病区与患者 ===== */
      loadWards: function () {
        var vm = this;
        get('/api/his/inp/bed/ward/list')
          .then(function (list) {
            vm.wards = list || [];
            if (vm.wards.length && vm.wardId == null) {
              vm.wardId = vm.wards[0].id;
              vm.onWardChange();
            }
          })
          .catch(function (e) { vm.toast(e.message || '病区加载失败'); });
      },
      onWardChange: function () {
        var vm = this;
        vm.patId = null;
        vm.loadPatients();
        vm.loadPlan();
      },
      loadPatients: function () {
        var vm = this;
        if (vm.wardId == null) { vm.wardPatients = []; return; }
        get('/api/his/inp/nurse/patients?wardId=' + encodeURIComponent(vm.wardId) + '&page=1&size=100')
          .then(function (d) { vm.wardPatients = (d && d.records) || []; })
          .catch(function () { vm.wardPatients = []; });
      },
      pickPatient: function (p) {
        this.patId = (this.patId === p.id) ? null : p.id;
      },

      /* ===== 执行计划(医嘱执行 / 输液管理共用) ===== */
      loadPlan: function () {
        var vm = this;
        if (vm.wardId == null) { vm.plan = []; return; }
        vm.planLoading = true;
        get('/api/his/inp/order-exec/plan?wardId=' + encodeURIComponent(vm.wardId) + '&page=1&size=100')
          .then(function (d) {
            vm.plan = ((d && d.records) || []).map(function (r) { r.__on = false; return r; });
            vm.sel = {};
          })
          .catch(function (e) { vm.toast(e.message || '执行计划加载失败'); })
          .finally(function () { vm.planLoading = false; });
      },
      groupByPatient: function (rows) {
        var vm = this;
        var map = {};
        var order = [];
        rows.forEach(function (r) {
          var k = String(r.inpVisitId);
          if (!map[k]) {
            map[k] = { visitId: r.inpVisitId, name: r.patientName, bedNo: r.bedNo, inpNo: r.inpNo, rows: [] };
            order.push(k);
          }
          map[k].rows.push(r);
        });
        var groups = order.map(function (k) { return map[k]; });
        groups.sort(function (a, b) {
          var x = a.bedNo == null ? '' : String(a.bedNo);
          var y = b.bedNo == null ? '' : String(b.bedNo);
          return x.localeCompare(y, 'zh-CN', { numeric: true });
        });
        return groups;
      },
      toggle: function (row) {
        if (Number(row.execStatus) !== 1) { return; }
        row.__on = !row.__on;
      },
      toggleGroup: function (g) {
        var vm = this;
        var allOn = g.rows.every(function (r) { return r.__on || Number(r.execStatus) !== 1; });
        g.rows.forEach(function (r) { if (Number(r.execStatus) === 1) { r.__on = !allOn; } });
      },
      /* 批量执行(医嘱/输液/扫码共用): needDoubleCheck 场景提示回护士站 */
      execIds: function (ids, after) {
        var vm = this;
        if (!ids || !ids.length) { vm.toast('请先勾选待执行的医嘱'); return; }
        vm.executing = true;
        post('/api/his/inp/order-exec/execute', ids)
          .then(function (d) {
            if (d && d.needDoubleCheck) {
              vm.toast('含高警示/双人核对医嘱, 请在护士工作站完成');
              return;
            }
            var done = d && d.executed != null ? d.executed : ids.length;
            vm.toast('已执行 ' + done + ' 项');
            if (after) { after(); } else { vm.loadPlan(); }
          })
          .catch(function (e) { vm.toast(e.message || '执行失败'); })
          .finally(function () { vm.executing = false; });
      },
      execOrders: function () { this.execIds(this.selectedExecIds); },
      execInfusion: function () { this.execIds(this.infusionSelIds); },
      execScan: function () {
        var vm = this;
        vm.execIds(vm.scanSelIds, function () { vm.loadScanOrders(); });
      },

      /* ===== 体征录入 ===== */
      submitVitals: function () {
        var vm = this;
        if (!vm.vitalsPatient) { vm.toast('请先选择患者'); return; }
        var v = vm.vitals;
        var c = {
          time: nowHM(),
          temperature: num(v.temperature),
          pulse: num(v.pulse),
          respiration: num(v.respiration),
          systolicBp: num(v.systolicBp),
          diastolicBp: num(v.diastolicBp),
          spo2: num(v.spo2)
        };
        var empty = c.temperature == null && c.pulse == null && c.respiration == null
          && c.systolicBp == null && c.diastolicBp == null && c.spo2 == null;
        if (empty) { vm.toast('请至少录入一项体征'); return; }
        vm.submittingVitals = true;
        post('/api/his/inp/nursing/', {
          inpVisitId: vm.vitalsPatient.id,
          recordType: 1,
          content: JSON.stringify(c)
        }).then(function () {
          vm.toast('体征已记录(体温单)');
          vm.vitals = { temperature: '', pulse: '', respiration: '', systolicBp: '', diastolicBp: '', spo2: '' };
        }).catch(function (e) {
          vm.toast(e.message || '提交失败');
        }).finally(function () { vm.submittingVitals = false; });
      },

      /* ===== 扫码核对 ===== */
      doScan: function () {
        var vm = this;
        var kw = String(vm.scanInput || '').trim();
        if (!kw) { vm.toast('请扫描腕带或输入住院号/姓名'); return; }
        vm.scanning = true;
        setTimeout(function () { vm.scanning = false; }, 600);
        get('/api/his/inp/patients?keyword=' + encodeURIComponent(kw) + '&visitStatus=2&page=1&size=5')
          .then(function (d) {
            var rows = (d && d.records) || [];
            if (!rows.length) {
              vm.scanPatient = null;
              vm.toast('未找到匹配的在院患者');
              return;
            }
            if (rows.length > 1) {
              vm.toast('匹配 ' + rows.length + ' 位患者, 已取首位, 请补全住院号');
            }
            vm.bindScanPatient(rows[0]);
          })
          .catch(function (e) { vm.toast(e.message || '查询失败'); });
      },
      bindScanPatient: function (p) {
        var vm = this;
        vm.scanPatient = p;
        vm.scanAllergies = [];
        vm.scanOrders = [];
        get('/api/his/inp/allergy/list?visitId=' + encodeURIComponent(p.id))
          .then(function (list) { vm.scanAllergies = list || []; })
          .catch(function () { });
        vm.loadScanOrders();
      },
      loadScanOrders: function () {
        var vm = this;
        var p = vm.scanPatient;
        if (!p || p.ward_id == null) { vm.scanOrders = []; return; }
        get('/api/his/inp/order-exec/plan?wardId=' + encodeURIComponent(p.ward_id) + '&page=1&size=100')
          .then(function (d) {
            vm.scanOrders = ((d && d.records) || [])
              .filter(function (r) { return String(r.inpVisitId) === String(p.id); })
              .map(function (r) { r.__on = false; return r; });
          })
          .catch(function () { vm.scanOrders = []; });
      },
      toggleScan: function (row) {
        if (Number(row.execStatus) !== 1) { return; }
        row.__on = !row.__on;
      },
      clearScan: function () {
        var vm = this;
        vm.scanInput = '';
        vm.scanPatient = null;
        vm.scanOrders = [];
        vm.scanAllergies = [];
      }
    },
    template: [
      '<div class="m-app">',

      /* ---------- 登录页 ---------- */
      '<div class="m-login" v-if="page === \'login\'">',
      '  <div class="logo">',
      '    <div class="mark"><svg viewBox="0 0 24 24" fill="none" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20.3 4.6 13a4.8 4.8 0 0 1 0-6.9 4.9 4.9 0 0 1 6.9 0l.5.5.5-.5a4.9 4.9 0 0 1 6.9 0 4.8 4.8 0 0 1 0 6.9z"/><path d="M12 8.5v4M10 10.5h4"/></svg></div>',
      '    <h1>移动护理终端</h1>',
      '    <p>MOBILE NURSING · BEDSIDE</p>',
      '  </div>',
      '  <div class="card">',
      '    <div class="m-field"><label>医院码</label><input v-model="loginForm.tenantCode" placeholder="医院登录码"></div>',
      '    <div class="m-field"><label>账号</label><input v-model="loginForm.username" placeholder="护士账号"></div>',
      '    <div class="m-field"><label>密码</label><input v-model="loginForm.password" type="password" placeholder="密码" @keyup.enter="doLogin"></div>',
      '    <button class="m-btn" :disabled="logging" @click="doLogin">{{ logging ? "登录中" : "登 录" }}</button>',
      '  </div>',
      '  <div class="hint">演示账号: <b>H42010000000 / admin / admin123</b><br/>与 HIS 主系统共享登录态</div>',
      '</div>',

      /* ---------- 首页 ---------- */
      '<template v-else-if="page === \'home\'">',
      '<div class="m-page">',
      '  <div class="m-header">',
      '    <div class="row1">',
      '      <div style="min-width:0;">',
      '        <div class="who">{{ user.realName || user.username || "-" }}</div>',
      '        <div class="org">{{ user.tenantName || "-" }} · {{ user.orgName || "-" }}</div>',
      '      </div>',
      '      <span class="grow"></span>',
      '      <button class="logout" @click="logout">退出</button>',
      '    </div>',
      '    <div class="m-ward">',
      '      <span class="lb">当前病区</span>',
      '      <select v-model="wardId" @change="onWardChange">',
      '        <option v-for="w in wards" :key="w.id" :value="w.id">{{ w.wardName }}</option>',
      '      </select>',
      '      <span class="cnt" v-if="wardPatients.length">{{ wardPatients.length }}人</span>',
      '    </div>',
      '  </div>',
      '  <div class="m-main">',
      '    <div class="m-grid">',
      '      <button class="m-tile" @click="go(\'vitals\')">',
      '        <div class="ic ic-danger" v-html="icons.heart"></div>',
      '        <div class="t">体征录入</div>',
      '        <div class="d">T / P / R / BP / SpO₂ 床旁采集</div>',
      '      </button>',
      '      <button class="m-tile" @click="go(\'orders\')">',
      '        <div class="ic ic-brand" v-html="icons.pill"></div>',
      '        <div class="t">医嘱执行</div>',
      '        <div class="d">本病区当日执行计划勾选执行</div>',
      '      </button>',
      '      <button class="m-tile" @click="go(\'infusion\')">',
      '        <div class="ic ic-success" v-html="icons.drip"></div>',
      '        <div class="t">输液管理</div>',
      '        <div class="d">药品类计划按患者巡视执行</div>',
      '      </button>',
      '      <button class="m-tile" @click="go(\'scan\')">',
      '        <div class="ic ic-warning" v-html="icons.scan"></div>',
      '        <div class="t">扫码核对</div>',
      '        <div class="d">扫腕带识别患者后床旁执行</div>',
      '      </button>',
      '    </div>',
      '    <div style="margin-top:16px;font-size:11px;color:var(--yb-ink-4);text-align:center;line-height:1.8;">',
      '      移动护理终端 · 演示模式(P3 UI模拟)<br/>高警示/双人核对医嘱请在护士工作站完成',
      '    </div>',
      '  </div>',
      '</div>',
      '</template>',

      /* ---------- 体征录入 ---------- */
      '<div class="m-page" v-else-if="page === \'vitals\'">',
      '  <div class="m-topbar"><button class="back" @click="go(\'home\')">‹</button><span class="t">体征录入</span><span class="sub">TEMP CHART</span></div>',
      '  <div class="m-main">',
      '    <div class="m-sec-title">选择患者(再点取消)</div>',
      '    <div class="m-pat-chips" v-if="wardPatients.length">',
      '      <div class="m-chip" v-for="p in wardPatients" :key="p.id" :class="{ \'is-on\': String(patId) === String(p.id) }" @click="pickPatient(p)">',
      '        <span class="nm">{{ p.patientName }}</span>',
      '        <span class="bd"><b>{{ p.bedNo || "-" }}</b>床 · {{ genderText(p.gender) }} {{ p.age }}岁</span>',
      '        <span class="no">{{ p.inpNo }}</span>',
      '      </div>',
      '    </div>',
      '    <div class="m-loading" v-else>患者加载中</div>',
      '    <template v-if="vitalsPatient">',
      '      <div class="m-card m-pat-card">',
      '        <div class="nm">{{ vitalsPatient.patientName }} <span style="font-size:13px;color:var(--yb-ink-3);font-weight:600;">{{ genderText(vitalsPatient.gender) }} · {{ vitalsPatient.age }}岁</span></div>',
      '        <div class="row"><span>床号 <b>{{ vitalsPatient.bedNo || "-" }}</b></span><span>记录时间 <b class="mono">自动取当前</b></span></div>',
      '      </div>',
      '      <div class="m-card">',
      '        <div class="m-sec-title">生命体征(体温单记录)</div>',
      '        <div class="m-vitals">',
      '          <div class="m-vital is-danger"><div class="k">体温 <span class="u">℃</span></div><input v-model="vitals.temperature" inputmode="decimal" placeholder="36.5"></div>',
      '          <div class="m-vital"><div class="k">脉搏 <span class="u">次/分</span></div><input v-model="vitals.pulse" inputmode="numeric" placeholder="80"></div>',
      '          <div class="m-vital"><div class="k">呼吸 <span class="u">次/分</span></div><input v-model="vitals.respiration" inputmode="numeric" placeholder="18"></div>',
      '          <div class="m-vital is-brand"><div class="k">收缩压 <span class="u">mmHg</span></div><input v-model="vitals.systolicBp" inputmode="numeric" placeholder="120"></div>',
      '          <div class="m-vital is-brand"><div class="k">舒张压 <span class="u">mmHg</span></div><input v-model="vitals.diastolicBp" inputmode="numeric" placeholder="80"></div>',
      '          <div class="m-vital"><div class="k">SpO₂ <span class="u">%</span></div><input v-model="vitals.spo2" inputmode="numeric" placeholder="98"></div>',
      '        </div>',
      '      </div>',
      '    </template>',
      '    <div class="m-empty" v-else><span class="big">♥</span>请从上方患者列表选择录入对象</div>',
      '  </div>',
      '  <div class="m-actionbar" v-if="vitalsPatient">',
      '    <span class="tip">提交后写入体温单(护理记录)</span><span class="grow"></span>',
      '    <button class="m-btn" style="width:auto;padding:0 26px;letter-spacing:.1em;" :disabled="submittingVitals" @click="submitVitals">{{ submittingVitals ? "提交中" : "提交体征" }}</button>',
      '  </div>',
      '</div>',

      /* ---------- 医嘱执行 ---------- */
      '<div class="m-page" v-else-if="page === \'orders\'">',
      '  <div class="m-topbar"><button class="back" @click="go(\'home\')">‹</button><span class="t">医嘱执行</span><span class="sub">{{ wardName }} · {{ pendingOrders.length }}待执行</span></div>',
      '  <div class="m-main">',
      '    <div class="m-loading" v-if="planLoading">执行计划加载中</div>',
      '    <template v-else-if="orderGroups.length">',
      '      <div class="m-card" v-for="g in orderGroups" :key="g.visitId">',
      '        <div class="m-pat-head" @click="toggleGroup(g)">',
      '          <span class="bdno">{{ g.bedNo || "-" }}</span><span class="nm">{{ g.name }}</span>',
      '          <span class="no">{{ g.inpNo }}</span>',
      '        </div>',
      '        <div class="m-order" v-for="r in g.rows" :key="r.id" :class="{ \'is-on\': r.__on, \'is-done\': Number(r.execStatus) === 2 }" @click="toggle(r)">',
      '          <span class="box">✓</span>',
      '          <div class="bd">',
      '            <div class="nm">{{ r.orderContent }}<span v-if="Number(r.highAlertFlag) === 1" class="m-tag high">高警示</span><span v-if="Number(r.skinTestFlag) === 1" class="m-tag skin">需皮试</span></div>',
      '            <div class="meta">',
      '              <span class="tm" :class="{ ovd: isOverdue(r.planTime) && Number(r.execStatus) === 1 }">{{ r.planTime }}</span>',
      '              <span v-if="r.dosage" class="ds">{{ r.dosage }}{{ r.dosageUnit || "" }}</span>',
      '              <span>{{ catMap[r.orderCategory] || "-" }}</span>',
      '              <span v-if="r.freqCode">{{ r.freqCode }}</span>',
      '              <span class="m-tag" :class="Number(r.execStatus) === 1 ? \'wait\' : (Number(r.execStatus) === 2 ? \'done\' : \'skip\')">{{ stMap[r.execStatus] }}</span>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </template>',
      '    <div class="m-empty" v-else><span class="big">✓</span>本病区今日暂无待执行医嘱</div>',
      '  </div>',
      '  <div class="m-actionbar">',
      '    <span class="tip">已选 {{ selectedExecIds.length }} 项 · 点卡片勾选/取消</span><span class="grow"></span>',
      '    <button class="m-btn" style="width:auto;padding:0 26px;letter-spacing:.1em;" :disabled="executing || !selectedExecIds.length" @click="execOrders">{{ executing ? "执行中" : "确认执行" }}</button>',
      '  </div>',
      '</div>',

      /* ---------- 输液管理 ---------- */
      '<div class="m-page" v-else-if="page === \'infusion\'">',
      '  <div class="m-topbar"><button class="back" @click="go(\'home\')">‹</button><span class="t">输液管理</span><span class="sub">{{ wardName }} · {{ infusionOrders.length }}组</span></div>',
      '  <div class="m-main">',
      '    <div class="m-loading" v-if="planLoading">执行计划加载中</div>',
      '    <template v-else-if="infusionGroups.length">',
      '      <div class="m-card" v-for="g in infusionGroups" :key="g.visitId">',
      '        <div class="m-pat-head" @click="toggleGroup(g)">',
      '          <span class="bdno">{{ g.bedNo || "-" }}</span><span class="nm">{{ g.name }}</span>',
      '          <span class="no">{{ g.inpNo }}</span>',
      '        </div>',
      '        <div class="m-order" v-for="r in g.rows" :key="r.id" :class="{ \'is-on\': r.__on, \'is-done\': Number(r.execStatus) === 2 }" @click="toggle(r)">',
      '          <span class="box">✓</span>',
      '          <div class="bd">',
      '            <div class="nm">{{ r.orderContent }}</div>',
      '            <div class="meta">',
      '              <span class="tm" :class="{ ovd: isOverdue(r.planTime) && Number(r.execStatus) === 1 }">{{ r.planTime }}</span>',
      '              <span class="ds">{{ r.dosage }}{{ r.dosageUnit || "" }}</span>',
      '              <span v-if="r.usageCode">{{ r.usageCode }}</span>',
      '              <span v-if="r.freqCode">{{ r.freqCode }}</span>',
      '              <span class="m-tag" :class="Number(r.execStatus) === 1 ? \'wait\' : (Number(r.execStatus) === 2 ? \'done\' : \'skip\')">{{ stMap[r.execStatus] }}</span>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </template>',
      '    <div class="m-empty" v-else><span class="big">▧</span>本病区今日暂无输液类待执行医嘱</div>',
      '  </div>',
      '  <div class="m-actionbar">',
      '    <span class="tip">已选 {{ infusionSelIds.length }} 组</span><span class="grow"></span>',
      '    <button class="m-btn" style="width:auto;padding:0 26px;letter-spacing:.1em;" :disabled="executing || !infusionSelIds.length" @click="execInfusion">{{ executing ? "执行中" : "执行输液" }}</button>',
      '  </div>',
      '</div>',

      /* ---------- 扫码核对 ---------- */
      '<div class="m-page" v-else-if="page === \'scan\'">',
      '  <div class="m-topbar"><button class="back" @click="go(\'home\')">‹</button><span class="t">扫码核对</span><span class="sub">WRISTBAND</span></div>',
      '  <div class="m-main">',
      '    <div class="m-scanbox">',
      '      <div class="laser" v-if="scanning"></div>',
      '      <div class="t">扫描患者腕带</div>',
      '      <input v-model="scanInput" placeholder="腕带条码 / 住院号 / 姓名" @keyup.enter="doScan">',
      '      <button class="go" @click="doScan">扫 码</button>',
      '    </div>',
      '    <template v-if="scanPatient">',
      '      <div class="m-card m-pat-card" style="margin-top:13px;">',
      '        <div class="nm">{{ scanPatient.patient_name }} <span style="font-size:13px;color:var(--yb-ink-3);font-weight:600;">{{ genderText(scanPatient.gender_name || scanPatient.gender) }} · {{ scanPatient.age }}岁</span></div>',
      '        <div class="row">',
      '          <span>床号 <b>{{ scanPatient.bed_no || "-" }}</b></span>',
      '          <span>病区 <b>{{ scanPatient.ward_name || "-" }}</b></span>',
      '          <span>住院号 <b class="mono">{{ scanPatient.inp_no }}</b></span>',
      '        </div>',
      '        <div class="allergy" :class="scanAllergyText ? \'hit\' : \'safe\'">{{ scanAllergyText ? ("⚠ 过敏: " + scanAllergyText) : "未登记药物过敏" }}</div>',
      '      </div>',
      '      <div class="m-card" v-if="scanPending.length">',
      '        <div class="m-sec-title">待执行医嘱({{ scanPending.length }})</div>',
      '        <div class="m-order" v-for="r in scanOrders" :key="r.id" :class="{ \'is-on\': r.__on, \'is-done\': Number(r.execStatus) === 2 }" @click="toggleScan(r)">',
      '          <span class="box">✓</span>',
      '          <div class="bd">',
      '            <div class="nm">{{ r.orderContent }}<span v-if="Number(r.highAlertFlag) === 1" class="m-tag high">高警示</span></div>',
      '            <div class="meta">',
      '              <span class="tm" :class="{ ovd: isOverdue(r.planTime) && Number(r.execStatus) === 1 }">{{ r.planTime }}</span>',
      '              <span v-if="r.dosage" class="ds">{{ r.dosage }}{{ r.dosageUnit || "" }}</span>',
      '              <span>{{ catMap[r.orderCategory] || "-" }}</span>',
      '              <span class="m-tag" :class="Number(r.execStatus) === 1 ? \'wait\' : (Number(r.execStatus) === 2 ? \'done\' : \'skip\')">{{ stMap[r.execStatus] }}</span>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '      <div class="m-empty" v-else><span class="big">✓</span>该患者今日执行计划均已完成</div>',
      '    </template>',
      '    <div class="m-empty" v-else><span class="big">⌗</span>扫描腕带后展示患者信息与待执行医嘱</div>',
      '  </div>',
      '  <div class="m-actionbar" v-if="scanPatient && scanPending.length">',
      '    <span class="tip">已选 {{ scanSelIds.length }} 项</span><span class="grow"></span>',
      '    <button class="m-btn" style="width:auto;padding:0 26px;letter-spacing:.1em;" :disabled="executing || !scanSelIds.length" @click="execScan">{{ executing ? "执行中" : "床旁执行" }}</button>',
      '  </div>',
      '</div>',

      /* ---------- 兜底 ---------- */
      '<div class="m-page" v-else><div class="m-empty">未知页面</div></div>',

      /* ---------- toast ---------- */
      '<div class="m-toast" :class="{ show: toastShow }">{{ toastMsg }}</div>',
      '</div>'
    ].join('\n')
  };

  Vue.createApp(MobileApp).mount('#app');
})();
