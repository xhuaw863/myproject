/* ==================================================================
 * pipe-manager.js — 护理管道管理组件(住院护士站 P4c-3)
 * ------------------------------------------------------------------
 * 定位: 病区护士对留置管道(中心静脉/尿管/鼻胃管/胸腔引流/PICC/气管套管等)
 *       的全生命周期管理: 置管登记 → 巡视评估 → 定期更换 → 拔管(含意外脱出)。
 * 版式: 顶栏(新增置管/成人儿童切换/在管与已拔计数) + 主体双栏
 *       (左 人体图 SVG 点击部位快速置管 + 管道点标注 + 超期预警条 /
 *        右 管道卡片列表[在管在前, 拔管历史可折叠] + 操作按钮)。
 * 依赖(index.html 先于本文件加载 api.js; 无构建、无 ES module):
 *   - HIS.request(HIS.get/post/put): R{code,msg,data} 信封, code=0 成功
 *   - HIS.id/idKey/idParam: 19位雪花ID全链路字符串化治理
 * 后端契约(P4c-1 管道接口, 表 his_nursing_pipe):
 *   - GET  /api/his/inp/nursing/pipe/list?inpVisitId=     就诊管道列表
 *   - POST /api/his/inp/nursing/pipe                      新增置管
 *           body: {inpVisitId, patientId, pipeType, pipeName, insertTime,
 *                  insertSite, bodyPartSvgData, riskLevel, expectedRemoveDate, note}
 *   - PUT  /api/his/inp/nursing/pipe/{id}/assess?riskLevel=&note=   巡视评估(查询参数)
 *   - PUT  /api/his/inp/nursing/pipe/{id}/replace?note=             记录更换(查询参数, 可省)
 *   - PUT  /api/his/inp/nursing/pipe/{id}/remove?removeType=2|3     拔管(2正常拔 3意外脱出)
 *   枚举: pipeType=central_venous/urinary/nasogastric/chest_tube/drain/
 *         tracheostomy/picc/other; riskLevel 1低 2中 3高;
 *         status 1在管 2已拔 3意外脱出; expectedRemoveDate=yyyy-MM-dd
 *   载入失败优雅降级(显示"服务暂不可用"+重试), 不阻断页面其余功能。
 * 注册: HIS.components.InpPipeManager + 全局标签 dw-inp-pipe-manager 双保险。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 pm- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('inp-pipe-manager-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-pipe-manager-style';
    st.textContent = [
      '.pm-root { flex:1 1 auto; display:flex; flex-direction:column; min-height:480px; min-width:0; font-size:13px; color:var(--yb-ink-1,#1c2430); background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:hidden; }',
      /* ---- 顶栏 ---- */
      '.pm-bar { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:8px 10px; padding:8px 12px; border-bottom:1px solid var(--yb-border,#dfe4eb); }',
      '.pm-icon { display:inline-flex; width:13px; height:13px; vertical-align:-2px; }',
      '.pm-icon svg { width:100%; height:100%; }',
      '.pm-stat { display:flex; align-items:center; gap:10px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.pm-stat b { font-size:14px; color:var(--yb-ink-1,#1c2430); }',
      '.pm-c-active { color:var(--yb-brand,#1a5c9e) !important; }',
      '.pm-spacer { flex:1 1 auto; }',
      '.pm-loading { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      /* ---- 主体双栏 ---- */
      '.pm-body { flex:1; display:flex; min-height:0; }',
      '.pm-figure { width:320px; flex:none; display:flex; flex-direction:column; align-items:center; gap:6px; padding:12px 10px; border-right:1px solid var(--yb-border,#dfe4eb); background:var(--yb-surface-2,#f7f9fc); min-height:0; overflow-y:auto; }',
      '.pm-human { width:100%; max-width:280px; height:auto; color:var(--yb-ink-3,#5a6a7e); }',
      '.pm-shape path, .pm-shape circle { fill:none; stroke:currentColor; stroke-width:2; stroke-linecap:round; stroke-linejoin:round; }',
      '.pm-shape.is-child { transform:translate(16px,30px) scale(.88); transform-origin:center; }',
      '.pm-site { fill:rgba(26,92,158,.14); stroke:var(--yb-brand,#1a5c9e); stroke-width:1.6; cursor:pointer; transition:fill .15s, r .15s; }',
      '.pm-site:hover { fill:rgba(26,92,158,.4); }',
      '.pm-dot circle { stroke:#fff; stroke-width:1.5; cursor:pointer; }',
      '.pm-dot circle { fill:var(--yb-ink-4,#8994a5); }',
      '.pm-dot.risk-1 circle { fill:#3c9d3c; }',
      '.pm-dot.risk-2 circle { fill:#e6a23c; }',
      '.pm-dot.risk-3 circle { fill:#e0493a; }',
      '.pm-dot:hover circle { stroke:#1c2430; }',
      '.pm-figure-hint { font-size:11px; color:var(--yb-ink-4,#8994a5); text-align:center; }',
      /* ---- 超期预警条 ---- */
      '.pm-overdue-bar { width:100%; display:flex; align-items:flex-start; gap:6px; padding:7px 9px; margin-top:2px; font-size:12px; line-height:1.6; color:#a3281c; background:rgba(224,73,58,.09); border:1px solid rgba(224,73,58,.4); border-radius:6px; cursor:pointer; }',
      '.pm-overdue-bar .pm-icon { flex:none; margin-top:2px; width:14px; height:14px; }',
      /* ---- 右栏列表 ---- */
      '.pm-list { flex:1; min-width:0; min-height:0; overflow-y:auto; padding:10px 12px; display:flex; flex-direction:column; gap:8px; }',
      '.pm-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; padding:30px 12px; color:var(--yb-ink-4,#8994a5); font-size:13px; }',
      '.pm-card { border:1px solid var(--yb-border-light,#ebeff4); border-left-width:3px; border-radius:6px; padding:9px 11px; display:flex; flex-direction:column; gap:6px; transition:border-color .15s, box-shadow .15s; }',
      '.pm-card.risk-1 { border-left-color:#3c9d3c; }',
      '.pm-card.risk-2 { border-left-color:#e6a23c; }',
      '.pm-card.risk-3 { border-left-color:#e0493a; }',
      '.pm-card.is-focus { border-color:var(--yb-brand,#1a5c9e); box-shadow:0 0 0 2px rgba(26,92,158,.18); }',
      '.pm-card.is-removed { opacity:.78; border-left-color:var(--yb-border-strong,#ccd4de); }',
      '.pm-card-head { display:flex; align-items:center; gap:7px; }',
      '.pm-card-risk-dot { flex:none; width:9px; height:9px; border-radius:50%; }',
      '.pm-card-risk-dot.risk-1 { background:#3c9d3c; }',
      '.pm-card-risk-dot.risk-2 { background:#e6a23c; }',
      '.pm-card-risk-dot.risk-3 { background:#e0493a; }',
      '.pm-card-name { font-size:13px; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.pm-card-day { flex:none; font-size:12px; color:var(--yb-brand,#1a5c9e); font-weight:600; }',
      '.pm-card-head .el-tag { margin-left:auto; }',
      '.pm-card-meta, .pm-card-meta2 { display:flex; flex-wrap:wrap; gap:4px 12px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.pm-card-note { font-size:12px; color:var(--yb-ink-4,#8994a5); line-height:1.6; word-break:break-all; }',
      '.pm-card-actions { display:flex; gap:6px; }',
      '.pm-card-actions .el-button + .el-button { margin-left:0; }',
      '.pm-overdue-text { color:#e0493a; font-weight:600; }',
      '.pm-hist-toggle { align-self:flex-start; display:flex; align-items:center; gap:5px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); cursor:pointer; padding:2px 0; user-select:none; }',
      '.pm-hist-toggle:hover { color:var(--yb-brand,#1a5c9e); }',
      '.pm-dlg-pipe { margin:-6px 0 10px; font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      /* ---- 窄屏回落 ---- */
      '@media (max-width: 1080px) { .pm-body { flex-direction:column; } .pm-figure { width:auto; border-right:none; border-bottom:1px solid var(--yb-border,#dfe4eb); } .pm-human { max-width:220px; } }',
      /* ---- 滚动条 ---- */
      '.pm-list::-webkit-scrollbar, .pm-figure::-webkit-scrollbar { width:6px; }',
      '.pm-list::-webkit-scrollbar-thumb, .pm-figure::-webkit-scrollbar-thumb { background:var(--yb-border-strong,#ccd4de); border-radius:3px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  var PIPE_TYPES = [
    { value: 'central_venous', label: '中心静脉导管', risk: 3 },
    { value: 'urinary', label: '留置尿管', risk: 1 },
    { value: 'nasogastric', label: '鼻胃管', risk: 1 },
    { value: 'chest_tube', label: '胸腔引流管', risk: 3 },
    { value: 'drain', label: '引流管', risk: 2 },
    { value: 'tracheostomy', label: '气管套管', risk: 3 },
    { value: 'picc', label: 'PICC 导管', risk: 3 },
    { value: 'other', label: '其他管道', risk: 1 }
  ];
  var TYPE_MAP = {};
  PIPE_TYPES.forEach(function (t) { TYPE_MAP[t.value] = t; });
  var RISK_LABELS = { 1: '低危', 2: '中危', 3: '高危' };
  var RISK_TAGS = { 1: 'success', 2: 'warning', 3: 'danger' };
  var STATUS_LABELS = { 1: '在管', 2: '已拔管', 3: '意外脱出' };

  /* 人体图可点击部位点位(前视图 viewBox 0 0 196 380; 成人/儿童两套坐标) */
  var SITE_SETS = {
    adult: [
      { key: 'nasal', label: '鼻腔', x: 98, y: 26 },
      { key: 'trachea', label: '气管', x: 98, y: 44 },
      { key: 'r_neck', label: '右颈内', x: 106, y: 58 },
      { key: 'l_neck', label: '左颈内', x: 90, y: 58 },
      { key: 'r_subclav', label: '右锁骨下', x: 110, y: 90 },
      { key: 'l_subclav', label: '左锁骨下', x: 86, y: 90 },
      { key: 'r_chest', label: '右胸腔', x: 112, y: 116 },
      { key: 'l_chest', label: '左胸腔', x: 84, y: 116 },
      { key: 'r_arm', label: '右上臂', x: 134, y: 120 },
      { key: 'l_arm', label: '左上臂', x: 62, y: 120 },
      { key: 'r_abd', label: '右腹部', x: 109, y: 146 },
      { key: 'l_abd', label: '左腹部', x: 87, y: 146 },
      { key: 'r_groin', label: '右腹股沟', x: 108, y: 158 },
      { key: 'l_groin', label: '左腹股沟', x: 88, y: 158 },
      { key: 'bladder', label: '会阴/下腹', x: 98, y: 172 },
      { key: 'r_leg', label: '右大腿', x: 112, y: 212 },
      { key: 'l_leg', label: '左大腿', x: 84, y: 212 }
    ],
    child: [
      { key: 'nasal', label: '鼻腔', x: 98, y: 34 },
      { key: 'trachea', label: '气管', x: 98, y: 48 },
      { key: 'r_neck', label: '右颈内', x: 105, y: 58 },
      { key: 'l_neck', label: '左颈内', x: 91, y: 58 },
      { key: 'r_subclav', label: '右锁骨下', x: 107, y: 86 },
      { key: 'l_subclav', label: '左锁骨下', x: 89, y: 86 },
      { key: 'r_chest', label: '右胸腔', x: 107, y: 108 },
      { key: 'l_chest', label: '左胸腔', x: 89, y: 108 },
      { key: 'r_arm', label: '右上臂', x: 126, y: 112 },
      { key: 'l_arm', label: '左上臂', x: 70, y: 112 },
      { key: 'r_abd', label: '右腹部', x: 105, y: 136 },
      { key: 'l_abd', label: '左腹部', x: 91, y: 136 },
      { key: 'bladder', label: '会阴/下腹', x: 98, y: 158 },
      { key: 'r_leg', label: '右大腿', x: 108, y: 192 },
      { key: 'l_leg', label: '左大腿', x: 88, y: 192 }
    ]
  };

  /* 内联 SVG 图标(feather 风格, viewBox 24, stroke currentColor) */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    plus: svg('<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>'),
    syringe: svg('<path d="m18 2 4 4"/><path d="m17 7 3-3"/><path d="M19 9 8.7 19.3c-1 1-2.5 1-3.4 0l-.6-.6c-1-1-1-2.5 0-3.4L15 5"/><path d="m9 11 4 4"/><path d="m5 19-3 3"/><path d="m14 4 6 6"/>'),
    alert: svg('<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    history: svg('<path d="M3 3v5h5"/><path d="M3.05 13A9 9 0 1 0 6 5.3L3 8"/><polyline points="12 7 12 12 15 15"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>')
  };

  /* ================= 工具 ================= */
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function nowStr() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' '
      + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }
  function todayStr() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  /* 'yyyy-MM-ddTHH:mm:ss'(Jackson ISO) / 'yyyy-MM-dd HH:mm:ss' → 显示用 */
  function shortTime(v) {
    var s = String(v == null ? '' : v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }
  function dateOf(v) {
    var s = String(v == null ? '' : v).replace('T', ' ');
    return s.length >= 10 ? s.substring(0, 10) : s;
  }
  function parseTs(raw) {
    if (raw === null || raw === undefined) { return null; }
    var m = String(raw).match(/^(\d{4})-(\d{2})-(\d{2})[T ]?(\d{2})?:?(\d{2})?/);
    if (!m) { return null; }
    return { y: +m[1], mo: +m[2], d: +m[3] };
  }
  function toast(type, text) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessage) { EP.ElMessage[type](text); }
    else { try { console.log('[pm:' + type + '] ' + text); } catch (e) { /* noop */ } }
  }
  function confirmBox(text, title) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessageBox) {
      return EP.ElMessageBox.confirm(text, title || '确认', {
        type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消'
      });
    }
    return global.Promise ? global.Promise.reject(new Error('cancel')) : null;
  }
  /* 宽松 JSON 解析(对象原样返回, 失败返回 null) */
  function parseLoose(v) {
    if (v == null || typeof v === 'object') { return v; }
    try { return JSON.parse(v); } catch (e) { return null; }
  }

  /* ================= 组件 ================= */
  var InpPipeManager = {
    name: 'InpPipeManager',
    props: {
      inpVisitId: { type: [String, Number], default: null },
      patientId: { type: [String, Number], default: null }
    },
    data: function () {
      return {
        icons: ICONS,
        typeOptions: PIPE_TYPES,
        bodyView: 'adult',
        pipes: [],
        loading: false,
        loadFailed: false,
        loaded: false,
        saving: false,
        showRemoved: false,
        focusedId: '',
        /* 新增置管对话框 */
        newVisible: false,
        form: this.freshForm(),
        sitePos: null,
        /* 评估对话框 */
        assessVisible: false,
        assessTarget: null,
        assessForm: { riskLevel: 1, note: '' },
        /* 拔管对话框 */
        removeVisible: false,
        removeTarget: null,
        removeForm: { removeType: 2 }
      };
    },
    computed: {
      hasVisit: function () {
        return this.inpVisitId != null && String(this.inpVisitId).length > 0;
      },
      activeSites: function () { return SITE_SETS[this.bodyView] || SITE_SETS.adult; },
      activePipes: function () {
        return (this.pipes || []).filter(function (p) { return Number(p.status) === 1; });
      },
      removedPipes: function () {
        return (this.pipes || []).filter(function (p) { return Number(p.status) !== 1; });
      },
      overduePipes: function () {
        var vm = this;
        return this.activePipes.filter(function (p) { return vm.isOverdue(p); });
      },
      overdueText: function () {
        var names = this.overduePipes.map(function (p) {
          return (p.pipeName || '管道') + (p.expectedRemoveDate ? '(' + dateOf(p.expectedRemoveDate) + ')' : '');
        });
        var s = names.slice(0, 3).join('、');
        return names.length > 3 ? s + ' 等' : s;
      },
      /* 图上管道点(在管管道; 坐标优先 bodyPartSvgData, 否则按部位名匹配当前视图点位) */
      plottedPipes: function () {
        var vm = this;
        return this.activePipes.map(function (p) {
          var pos = vm.pipePos(p);
          if (!pos) { return null; }
          return Object.assign({}, p, { _x: pos.x, _y: pos.y });
        }).filter(Boolean);
      }
    },
    watch: {
      inpVisitId: function () { this.resetForVisit(); }
    },
    mounted: function () {
      this.loadPipes();
    },
    methods: {
      freshForm: function () {
        return {
          pipeType: 'central_venous', pipeName: '中心静脉导管', insertTime: nowStr(),
          insertSite: '', riskLevel: 3, expectedRemoveDate: '', note: ''
        };
      },
      /* ---------- 数据加载 ---------- */
      resetForVisit: function () {
        this.pipes = [];
        this.loaded = false;
        this.loadFailed = false;
        this.showRemoved = false;
        this.focusedId = '';
        this.loadPipes();
      },
      loadPipes: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.pipes = []; return; }
        vm.loading = true;
        vm.loadFailed = false;
        HIS.get('/api/his/inp/nursing/pipe/list?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .then(function (rows) {
            vm.pipes = (Array.isArray(rows) ? rows : []).map(function (r) {
              return Object.assign({}, r, { id: HIS.id(r.id) });
            });
            vm.loaded = true;
          })
          .catch(function () {
            vm.pipes = [];
            vm.loadFailed = true;
          })
          .finally(function () { vm.loading = false; });
      },
      afterChanged: function (msg) {
        toast('success', msg);
        this.loadPipes();
      },

      /* ---------- 展示辅助 ---------- */
      typeLabel: function (v) {
        var t = TYPE_MAP[v];
        return t ? t.label : (v || '管道');
      },
      riskLabel: function (v) { return RISK_LABELS[Number(v)] || '低危'; },
      riskTag: function (v) { return RISK_TAGS[Number(v)] || 'info'; },
      statusLabel: function (v) { return STATUS_LABELS[Number(v)] || '-'; },
      shortTime: shortTime,
      dateOf: dateOf,
      /* 置管天数(置管当天=Day 1; 无置管时间返回 '-') */
      dayOf: function (p) {
        var t = parseTs(p && p.insertTime);
        if (!t) { return '-'; }
        var d0 = new Date(t.y, t.mo - 1, t.d);
        var now = new Date();
        var d1 = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        return Math.max(1, Math.round((d1.getTime() - d0.getTime()) / 86400000) + 1);
      },
      isOverdue: function (p) {
        if (!p || Number(p.status) !== 1 || !p.expectedRemoveDate) { return false; }
        return String(p.expectedRemoveDate).slice(0, 10) < todayStr();
      },
      cardDomId: function (p) { return 'pm-card-' + HIS.idKey(p.id); },
      /* 管道图上坐标: 优先解析 bodyPartSvgData(视图匹配时直用, 跨视图按部位名匹配), 否则按部位名匹配 */
      pipePos: function (p) {
        var o = parseLoose(p && p.bodyPartSvgData);
        if (o && o.x != null && o.y != null) {
          if (!o.view || o.view === this.bodyView) { return { x: Number(o.x), y: Number(o.y) }; }
        }
        var site = String((p && p.insertSite) || '').trim();
        if (!site) { return null; }
        var hit = null;
        (this.activeSites || []).some(function (pt) {
          if (site.indexOf(pt.label) >= 0 || pt.label.indexOf(site) >= 0) { hit = pt; return true; }
          return false;
        });
        return hit ? { x: hit.x, y: hit.y } : null;
      },
      onSiteClick: function (pt) {
        this.openNewDialog(pt);
      },
      focusPipe: function (p) {
        var vm = this;
        vm.focusedId = HIS.idKey(p.id);
        vm.$nextTick(function () {
          var el = global.document.getElementById(vm.cardDomId(p));
          if (el && el.scrollIntoView) { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
          setTimeout(function () {
            if (vm.focusedId === HIS.idKey(p.id)) { vm.focusedId = ''; }
          }, 2400);
        });
      },
      scrollToList: function () {
        var host = this.$refs.listHost;
        if (host && host.scrollTo) { host.scrollTo({ top: 0, behavior: 'smooth' }); }
      },

      /* ---------- 新增置管 ---------- */
      openNewDialog: function (pt) {
        var vm = this;
        if (!vm.hasVisit) { toast('warning', '请先在护士站选择患者'); return; }
        vm.form = vm.freshForm();
        vm.sitePos = null;
        if (pt) {
          vm.form.insertSite = pt.label;
          vm.sitePos = { key: pt.key, label: pt.label, x: pt.x, y: pt.y };
        }
        vm.newVisible = true;
      },
      onTypeChange: function (val) {
        var t = TYPE_MAP[val];
        if (!t) { return; }
        /* 名称与风险等级未手动改过时随类型联动, 已手动修改保留 */
        var def = t.label;
        var cur = String(this.form.pipeName || '').trim();
        var isAuto = !cur || Object.keys(TYPE_MAP).some(function (k) { return TYPE_MAP[k].label === cur; });
        if (isAuto) { this.form.pipeName = def; }
        this.form.riskLevel = t.risk;
      },
      submitNew: function () {
        var vm = this;
        var f = vm.form;
        if (!vm.hasVisit) { toast('warning', '请先选择患者'); return; }
        if (!f.pipeType) { toast('warning', '请选择管道类型'); return; }
        if (!String(f.pipeName || '').trim()) { toast('warning', '请填写管道名称'); return; }
        var payload = {
          inpVisitId: HIS.id(vm.inpVisitId),
          patientId: HIS.id(vm.patientId),
          pipeType: f.pipeType,
          pipeName: String(f.pipeName).trim(),
          insertTime: f.insertTime || nowStr(),
          insertSite: String(f.insertSite || '').trim(),
          riskLevel: Number(f.riskLevel) || 1,
          expectedRemoveDate: f.expectedRemoveDate || null,
          note: String(f.note || '').trim() || null
        };
        if (vm.sitePos) {
          payload.bodyPartSvgData = JSON.stringify({
            view: vm.bodyView, x: vm.sitePos.x, y: vm.sitePos.y, siteKey: vm.sitePos.key
          });
        }
        vm.saving = true;
        HIS.post('/api/his/inp/nursing/pipe', payload).then(function () {
          vm.saving = false;
          vm.newVisible = false;
          vm.afterChanged('置管登记已保存');
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      },

      /* ---------- 评估 ---------- */
      openAssess: function (p) {
        this.assessTarget = p;
        this.assessForm = { riskLevel: Number(p.riskLevel) || 1, note: '' };
        this.assessVisible = true;
      },
      submitAssess: function () {
        var vm = this;
        var p = vm.assessTarget;
        if (!p) { return; }
        vm.saving = true;
        HIS.put('/api/his/inp/nursing/pipe/' + HIS.idParam(p.id)
          + '/assess?riskLevel=' + (Number(vm.assessForm.riskLevel) || 1)
          + '&note=' + encodeURIComponent(String(vm.assessForm.note || '').trim())).then(function () {
          vm.saving = false;
          vm.assessVisible = false;
          vm.afterChanged('巡视评估已记录');
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      },

      /* ---------- 更换 ---------- */
      doReplace: function (p) {
        var vm = this;
        confirmBox('确认登记「' + (p.pipeName || '管道') + '」已更换? 将记录最后更换时间。', '记录更换').then(function () {
          vm.saving = true;
          HIS.put('/api/his/inp/nursing/pipe/' + HIS.idParam(p.id) + '/replace', {}).then(function () {
            vm.saving = false;
            vm.afterChanged('更换记录已保存');
          }).catch(function (e) {
            vm.saving = false;
            HIS.notifyError(e);
          });
        }).catch(function () { /* 取消 */ });
      },

      /* ---------- 拔管 ---------- */
      openRemove: function (p) {
        this.removeTarget = p;
        this.removeForm = { removeType: 2 };
        this.removeVisible = true;
      },
      submitRemove: function () {
        var vm = this;
        var p = vm.removeTarget;
        if (!p) { return; }
        var removeType = Number(vm.removeForm.removeType) === 3 ? 3 : 2;
        vm.saving = true;
        HIS.put('/api/his/inp/nursing/pipe/' + HIS.idParam(p.id) + '/remove?removeType=' + removeType).then(function () {
          vm.saving = false;
          vm.removeVisible = false;
          vm.afterChanged(removeType === 3 ? '已登记意外脱出' : '拔管已登记');
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      }
    },

    /* ================= 模板 ================= */
    template: [
      '<div class="pm-root">',
      /* ---- 顶栏 ---- */
      '  <div class="pm-bar">',
      '    <el-button type="primary" size="small" @click="openNewDialog(null)"><span class="pm-icon" v-html="icons.plus"></span> 新增置管</el-button>',
      '    <el-radio-group v-model="bodyView" size="small">',
      '      <el-radio-button label="adult">成人</el-radio-button>',
      '      <el-radio-button label="child">儿童</el-radio-button>',
      '    </el-radio-group>',
      '    <span class="pm-stat">',
      '      <span>在管: <b class="pm-c-active">{{ activePipes.length }}</b></span>',
      '      <span>已拔: <b>{{ removedPipes.length }}</b></span>',
      '    </span>',
      '    <span class="pm-spacer"></span>',
      '    <span v-if="loading" class="pm-loading">加载中…</span>',
      '    <el-button size="small" text @click="loadPipes" title="刷新"><span class="pm-icon" v-html="icons.refresh"></span></el-button>',
      '  </div>',
      /* ---- 主体双栏 ---- */
      '  <div class="pm-body">',
      /* 左: 人体图 */
      '    <div class="pm-figure">',
      '      <svg viewBox="0 0 196 380" class="pm-human">',
      '        <g class="pm-shape" :class="{ \'is-child\': bodyView === \'child\' }">',
      '          <circle cx="98" cy="30" r="16"></circle>',
      '          <path d="M89 46 L89 60 M107 46 L107 60"></path>',
      '          <path d="M71 64 L125 64 L129 96 L126 150 Q98 160 70 150 L67 96 Z"></path>',
      '          <path d="M74 68 L53 118 L49 168"></path>',
      '          <path d="M122 68 L143 118 L147 168"></path>',
      '          <path d="M82 154 L77 238 L75 328"></path>',
      '          <path d="M114 154 L119 238 L121 328"></path>',
      '        </g>',
      '        <g class="pm-sites">',
      '          <circle v-for="pt in activeSites" :key="pt.key" class="pm-site" :cx="pt.x" :cy="pt.y" r="6.5" @click="onSiteClick(pt)">',
      '            <title>{{ pt.label }} · 点击新增置管</title>',
      '          </circle>',
      '        </g>',
      '        <g class="pm-dots">',
      '          <g v-for="p in plottedPipes" :key="\'dot-\' + p.id" class="pm-dot" :class="\'risk-\' + (p.riskLevel || 1)" @click.stop="focusPipe(p)">',
      '            <circle :cx="p._x" :cy="p._y" r="5.5"></circle>',
      '            <title>{{ p.pipeName }}（{{ riskLabel(p.riskLevel) }}·Day {{ dayOf(p) }}）</title>',
      '          </g>',
      '        </g>',
      '      </svg>',
      '      <div class="pm-figure-hint">点击图中部位快速新增置管; 圆点为在管管道(红/橙/绿=高/中/低危)</div>',
      '      <div v-if="overduePipes.length" class="pm-overdue-bar" @click="scrollToList">',
      '        <span class="pm-icon" v-html="icons.alert"></span>',
      '        <span>{{ overduePipes.length }} 条管道已超预计拔管日期: {{ overdueText }}</span>',
      '      </div>',
      '    </div>',
      /* 右: 管道列表 */
      '    <div class="pm-list" ref="listHost">',
      '      <div v-if="!hasVisit" class="pm-empty">',
      '        <span style="width:40px;height:40px;opacity:.5" v-html="icons.user"></span>',
      '        <div>请先在护士站患者列表选择患者</div>',
      '      </div>',
      '      <div v-else-if="loadFailed" class="pm-empty">',
      '        <div>管道服务暂不可用</div>',
      '        <el-button size="small" @click="loadPipes">重试</el-button>',
      '      </div>',
      '      <div v-else-if="!activePipes.length && !removedPipes.length && !loading" class="pm-empty">',
      '        <span style="width:36px;height:36px;opacity:.5" v-html="icons.syringe"></span>',
      '        <div>暂无管道记录, 点击「新增置管」或左侧人体图开始登记</div>',
      '      </div>',
      '      <div v-for="p in activePipes" :key="p.id" class="pm-card" :class="[\'risk-\' + (p.riskLevel || 1), { \'is-focus\': focusedId === String(p.id) }]" :id="cardDomId(p)">',
      '        <div class="pm-card-head">',
      '          <span class="pm-card-risk-dot" :class="\'risk-\' + (p.riskLevel || 1)"></span>',
      '          <span class="pm-card-name" :title="p.pipeName">{{ p.pipeName || typeLabel(p.pipeType) }}</span>',
      '          <span class="pm-card-day">Day {{ dayOf(p) }}</span>',
      '          <el-tag size="small" :type="riskTag(p.riskLevel)" disable-transitions>{{ riskLabel(p.riskLevel) }}</el-tag>',
      '        </div>',
      '        <div class="pm-card-meta">',
      '          <span v-if="p.insertSite">{{ p.insertSite }}</span>',
      '          <span v-if="p.insertTime">置管 {{ shortTime(p.insertTime) }}</span>',
      '          <span>{{ typeLabel(p.pipeType) }}</span>',
      '        </div>',
      '        <div v-if="p.expectedRemoveDate || p.lastAssessTime || p.lastReplaceTime" class="pm-card-meta2">',
      '          <span v-if="p.expectedRemoveDate" :class="{ \'pm-overdue-text\': isOverdue(p) }">预计拔管 {{ dateOf(p.expectedRemoveDate) }}{{ isOverdue(p) ? " · 已超期" : "" }}</span>',
      '          <span v-if="p.lastAssessTime">评估 {{ shortTime(p.lastAssessTime) }}</span>',
      '          <span v-if="p.lastReplaceTime">更换 {{ shortTime(p.lastReplaceTime) }}</span>',
      '        </div>',
      '        <div v-if="p.note" class="pm-card-note">{{ p.note }}</div>',
      '        <div class="pm-card-actions">',
      '          <el-button size="small" @click="openAssess(p)">评估</el-button>',
      '          <el-button size="small" @click="doReplace(p)">更换</el-button>',
      '          <el-button size="small" type="danger" plain @click="openRemove(p)">拔管</el-button>',
      '        </div>',
      '      </div>',
      '      <template v-if="removedPipes.length">',
      '        <div class="pm-hist-toggle" @click="showRemoved = !showRemoved">',
      '          <span class="pm-icon" v-html="icons.history"></span> 拔管历史({{ removedPipes.length }}) {{ showRemoved ? "▲" : "▼" }}',
      '        </div>',
      '        <template v-if="showRemoved">',
      '          <div v-for="p in removedPipes" :key="p.id" class="pm-card is-removed">',
      '            <div class="pm-card-head">',
      '              <span class="pm-card-name">{{ p.pipeName || typeLabel(p.pipeType) }}</span>',
      '              <el-tag size="small" :type="Number(p.status) === 3 ? \'danger\' : \'info\'" disable-transitions>{{ statusLabel(p.status) }}</el-tag>',
      '            </div>',
      '            <div class="pm-card-meta">',
      '              <span v-if="p.insertSite">{{ p.insertSite }}</span>',
      '              <span v-if="p.actualRemoveTime">拔管 {{ shortTime(p.actualRemoveTime) }}</span>',
      '              <span v-if="p.insertTime">置管 {{ dateOf(p.insertTime) }}</span>',
      '            </div>',
      '            <div v-if="p.note" class="pm-card-note">{{ p.note }}</div>',
      '          </div>',
      '        </template>',
      '      </template>',
      '    </div>',
      '  </div>',
      /* ---- 新增置管对话框 ---- */
      '  <el-dialog v-model="newVisible" title="新增置管" width="560px" append-to-body :close-on-click-modal="false">',
      '    <el-form label-width="86px" label-position="right">',
      '      <el-form-item label="管道类型" required>',
      '        <el-select v-model="form.pipeType" style="width:100%" @change="onTypeChange">',
      '          <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="管道名称" required>',
      '        <el-input v-model="form.pipeName" maxlength="100" placeholder="如: 右锁骨下中心静脉导管"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="置管时间">',
      '        <el-date-picker v-model="form.insertTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" placeholder="默认当前时间"></el-date-picker>',
      '      </el-form-item>',
      '      <el-form-item label="置管部位">',
      '        <el-input v-model="form.insertSite" maxlength="100" placeholder="点击左侧人体图选择或手动输入"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="风险等级">',
      '        <el-radio-group v-model="form.riskLevel">',
      '          <el-radio-button :label="1">低危</el-radio-button>',
      '          <el-radio-button :label="2">中危</el-radio-button>',
      '          <el-radio-button :label="3">高危</el-radio-button>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="预计拔管">',
      '        <el-date-picker v-model="form.expectedRemoveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="预计拔管日期"></el-date-picker>',
      '      </el-form-item>',
      '      <el-form-item label="备注">',
      '        <el-input v-model="form.note" type="textarea" :rows="2" maxlength="500" placeholder="固定方式/深度/注意事项等"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="newVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitNew">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 评估对话框 ---- */
      '  <el-dialog v-model="assessVisible" title="管道巡视评估" width="460px" append-to-body>',
      '    <div class="pm-dlg-pipe">{{ assessTarget ? (assessTarget.pipeName || typeLabel(assessTarget.pipeType)) : "" }}<span v-if="assessTarget && assessTarget.insertSite"> · {{ assessTarget.insertSite }}</span></div>',
      '    <el-form label-width="86px">',
      '      <el-form-item label="风险等级">',
      '        <el-radio-group v-model="assessForm.riskLevel">',
      '          <el-radio-button :label="1">低危</el-radio-button>',
      '          <el-radio-button :label="2">中危</el-radio-button>',
      '          <el-radio-button :label="3">高危</el-radio-button>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="评估备注">',
      '        <el-input v-model="assessForm.note" type="textarea" :rows="2" maxlength="500" placeholder="穿刺点/固定/通畅情况…"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="assessVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitAssess">提交评估</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 拔管对话框 ---- */
      '  <el-dialog v-model="removeVisible" title="拔管登记" width="460px" append-to-body>',
      '    <div class="pm-dlg-pipe">{{ removeTarget ? (removeTarget.pipeName || typeLabel(removeTarget.pipeType)) : "" }}<span v-if="removeTarget && removeTarget.insertSite"> · {{ removeTarget.insertSite }}</span></div>',
      '    <el-form label-width="86px">',
      '      <el-form-item label="拔管方式">',
      '        <el-radio-group v-model="removeForm.removeType">',
      '          <el-radio-button :label="2">正常拔管</el-radio-button>',
      '          <el-radio-button :label="3">意外脱出</el-radio-button>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <div style="margin:-4px 0 4px 86px;font-size:11px;color:var(--yb-ink-4,#8994a5)">意外脱出可在后续「评估」中补记脱出经过</div>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="removeVisible = false">取消</el-button>',
      '      <el-button type="danger" :loading="saving" @click="submitRemove">确认拔管</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.InpPipeManager = InpPipeManager;
  /* 全局标签注册: app 就绪后挂 <dw-inp-pipe-manager>(脚本先于 app.js 加载, 故延时至 DOMContentLoaded;
   * P4c-5 集成后 inp-nurse.js 亦可经 HIS.components 局部声明, 局部声明优先于全局标签) */
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-pipe-manager', InpPipeManager); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
