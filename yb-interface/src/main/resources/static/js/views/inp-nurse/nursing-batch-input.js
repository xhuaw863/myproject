/* ==================================================================
 * nursing-batch-input.js — 护理站批量生命体征录入组件(住院护士站 P4b-3)
 * ------------------------------------------------------------------
 * 定位: 病区护士一轮巡回快速录入生命体征, 双模式:
 *   ①按项目: 选指标(体温/脉搏/心率/呼吸/收缩压/舒张压/血氧/疼痛评分)后
 *            逐床一行输入, 实时校验+状态判定, 一键批量保存;
 *   ②按患者: 单患者全项表单(含血糖/意识/体重/出入量), 上次记录值作占位
 *            参考, 单条保存。
 * 状态口径(按项目): ✓已录(绿) / ⚠待测(黄, 本时段未测) /
 *   ○漏测(灰+删除线, 本时段未测且上一时段亦无) / ⚠异常(红, 超警戒或超范围);
 * 时段分桶与后端 NursingVitalSignService.slotOf 一致:
 *   <4→02:00 <8→06:00 <12→10:00 <16→14:00 <20→18:00 其余→22:00。
 * 依赖(index.html 先于本文件加载 api.js; 无构建、无 ES module):
 *   - HIS.request(HIS.get/post): R{code,msg,data} 信封, code=0 成功
 *   - HIS.id/idKey/idParam: 19位雪花ID全链路字符串化治理
 * 后端契约(NursingVitalSignController, P4a 已部署):
 *   - GET  /api/his/inp/nursing/vital-sign/list?inpVisitId=&start=&end=
 *          体征列表(闭区间, 测量时间倒序; start 缺省不过滤)
 *   - POST /api/his/inp/nursing/vital-sign         单条(自动 MEWS 评分+预警 SSE)
 *   - POST /api/his/inp/nursing/vital-sign/batch   批量(≤100条/批, 失败整体回滚)
 * 宿主集成: props wardId/patients(护士站患者列表, 由宿主注入), @saved 事件
 *   携带保存条数; 注册 HIS.components.InpNursingBatchInput + 全局标签
 *   dw-inp-nursing-batch-input 双保险(P4b-5 由 inp-nurse.js 集成)。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 nbi- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('nursing-batch-input-style')) { return; }
    var st = document.createElement('style');
    st.id = 'nursing-batch-input-style';
    st.textContent = [
      /* ---- 根与顶栏 ---- */
      '.nbi-root { display:flex; flex-direction:column; min-width:0; font-size:13px; color:var(--yb-ink-1,#1c2430); }',
      '.nbi-bar { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:8px 10px; padding:8px 12px; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px 6px 0 0; }',
      '.nbi-lab { font-size:12px; color:var(--yb-ink-3,#606266); }',
      '.nbi-spacer { flex:1 1 auto; }',
      '.nbi-loading { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      '.nbi-sel { width:132px; }',
      '.nbi-sel-narrow { width:104px; }',
      '.nbi-sel-mid { width:170px; }',
      '.nbi-sel-patient { width:200px; }',
      /* ---- 按项目: 表格 ---- */
      '.nbi-table-wrap { border:1px solid var(--yb-border,#dfe4eb); border-top:none; border-radius:0 0 6px 6px; background:var(--yb-surface,#fff); max-height:56vh; overflow:auto; }',
      '.nbi-grid { display:grid; grid-template-columns:64px minmax(84px,1fr) 92px minmax(88px,.9fr) 150px 104px; align-items:center; gap:0 8px; padding:0 12px; }',
      '.nbi-head { position:sticky; top:0; z-index:2; min-height:34px; background:#f7f8fa; border-bottom:1px solid var(--yb-border,#dfe4eb); font-weight:600; font-size:12px; color:var(--yb-ink-3,#606266); }',
      '.nbi-row { min-height:42px; border-bottom:1px solid #f2f4f7; }',
      '.nbi-row:last-child { border-bottom:none; }',
      '.nbi-row.st-pending { background:rgba(230,162,60,.10); }',
      '.nbi-row.st-missed { background:rgba(144,147,153,.13); }',
      '.nbi-row.st-missed .nbi-bed, .nbi-row.st-missed .nbi-name, .nbi-row.st-missed .nbi-level, .nbi-row.st-missed .nbi-last { color:#909399; }',
      '.nbi-row.st-missed .nbi-last { text-decoration:line-through; }',
      '.nbi-row.st-abnormal { background:rgba(245,108,108,.08); }',
      '.nbi-bed { font-weight:600; }',
      '.nbi-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.nbi-last { color:var(--yb-ink-3,#606266); }',
      '.nbi-cell .el-input__inner { text-align:center; font-weight:600; }',
      '.nbi-status { font-size:12px; white-space:nowrap; }',
      '.nbi-status.c-ok { color:#67C23A; }',
      '.nbi-status.c-pending { color:#E6A23C; }',
      '.nbi-status.c-missed { color:#909399; }',
      '.nbi-status.c-abnormal { color:#F56C6C; font-weight:600; }',
      '.nbi-status.c-loading { color:#C0C4CC; }',
      /* ---- 实时校验描边(红=超范围禁存 / 橙=临床警戒) ---- */
      '.nbi-root .is-bad .el-input__wrapper { box-shadow:0 0 0 1px #F56C6C inset; }',
      '.nbi-root .is-warn .el-input__wrapper { box-shadow:0 0 0 1px #E6A23C inset; }',
      /* ---- 空态与汇总栏 ---- */
      '.nbi-empty { padding:26px 12px; text-align:center; color:var(--yb-ink-4,#8994a5); background:var(--yb-surface,#fff); }',
      '.nbi-empty-lg { border:1px solid var(--yb-border,#dfe4eb); border-top:none; border-radius:0 0 6px 6px; }',
      '.nbi-sum { flex:none; display:flex; flex-wrap:wrap; gap:6px 16px; align-items:center; margin-top:6px; padding:8px 12px; background:#f7f8fa; border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; font-size:12px; color:var(--yb-ink-3,#606266); }',
      '.nbi-sum b { color:var(--yb-ink-1,#1c2430); }',
      '.nbi-sum-bad { color:#F56C6C; font-weight:600; }',
      '.nbi-sum-tip { margin-left:auto; color:#a8b0bd; }',
      /* ---- 按患者: 全项表单 ---- */
      '.nbi-form { display:grid; grid-template-columns:repeat(auto-fill,minmax(330px,1fr)); gap:10px 18px; padding:12px; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-top:none; }',
      '.nbi-cellgroup { display:flex; flex-wrap:wrap; align-items:center; gap:8px 12px; min-width:0; }',
      '.nbi-field { display:flex; align-items:center; gap:6px; }',
      '.nbi-flabel { flex:none; font-size:12px; color:var(--yb-ink-3,#606266); }',
      '.nbi-unit { flex:none; font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      '.nbi-hint { padding:6px 12px; font-size:12px; color:#B88230; background:rgba(230,162,60,.10); border:1px solid rgba(230,162,60,.35); border-top:none; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  var SLOTS = ['02:00', '06:00', '10:00', '14:00', '18:00', '22:00'];
  var LEVEL_FULL = { 1: '特级护理', 2: '一级护理', 3: '二级护理', 4: '三级护理' };
  var MEWS_TEXT = { 1: '黄色', 2: '橙色', 3: '红色' };
  var TEMP_TYPES = [
    { value: 1, label: '口温' }, { value: 2, label: '腋温' },
    { value: 3, label: '肛温' }, { value: 4, label: '耳温' }
  ];
  var CONSCIOUS_OPTS = ['清醒', '嗜睡', '模糊', '昏迷'].map(function (s) { return { value: s, label: s }; });

  /* 体征字段定义: min/max=后端硬校验范围(超范围禁存, 与 validateRanges 对齐);
   * warnHigh/warnLow/warnGte=临床警戒阈值(允许保存, 前端标红橙并提示) */
  var FORM_DEFS = {
    temperature:  { key: 'temperature',  label: '体温',     unit: '℃',     min: 34,  max: 43,    decimals: 1, warnHigh: 39,   warnLow: 35.5 },
    pulse:        { key: 'pulse',        label: '脉搏',     unit: '次/分',  min: 20,  max: 250,   decimals: 0, warnHigh: 120,  warnLow: 50 },
    heartRate:    { key: 'heartRate',    label: '心率',     unit: '次/分',  min: 20,  max: 250,   decimals: 0, warnHigh: 120,  warnLow: 50 },
    respiration:  { key: 'respiration',  label: '呼吸',     unit: '次/分',  min: 4,   max: 60,    decimals: 0, warnHigh: 30,   warnLow: 8 },
    systolicBp:   { key: 'systolicBp',   label: '收缩压',   unit: 'mmHg',   min: 40,  max: 300,   decimals: 0, warnHigh: 180,  warnLow: 90 },
    diastolicBp:  { key: 'diastolicBp',  label: '舒张压',   unit: 'mmHg',   min: 20,  max: 200,   decimals: 0, warnHigh: 110 },
    spo2:         { key: 'spo2',         label: '血氧',     unit: '%',      min: 50,  max: 100,   decimals: 0, warnLow: 90 },
    bloodGlucose: { key: 'bloodGlucose', label: '血糖',     unit: 'mmol/L', min: 0,   max: 40,    decimals: 1, warnHigh: 16.7, warnLow: 3.9 },
    painScore:    { key: 'painScore',    label: '疼痛评分', unit: '分',     min: 0,   max: 10,    decimals: 0, warnGte: 7 },
    weight:       { key: 'weight',       label: '体重',     unit: 'kg',     min: 0.1, max: 300,   decimals: 1 },
    stoolCount:   { key: 'stoolCount',   label: '大便',     unit: '次',     min: 0,   max: 99,    decimals: 0 },
    urineMl:      { key: 'urineMl',      label: '尿量',     unit: 'ml',     min: 0,   max: 99999, decimals: 0 },
    drainMl:      { key: 'drainMl',      label: '引流',     unit: 'ml',     min: 0,   max: 99999, decimals: 0 }
  };
  var NUM_KEYS = Object.keys(FORM_DEFS);
  /* 按项目模式的 8 项指标(顺序即下拉顺序) */
  var DEFS = ['temperature', 'pulse', 'heartRate', 'respiration', 'systolicBp', 'diastolicBp', 'spo2', 'painScore']
    .map(function (k) { return FORM_DEFS[k]; });

  /* 按患者模式表单版式: 每格 1~3 个字段(与临床录入手顺一致) */
  function cell(defKey, width, label) {
    var src = FORM_DEFS[defKey], d = {};
    for (var k in src) { d[k] = src[k]; }
    d.width = width;
    if (label) { d.label = label; }
    return d;
  }
  var FORM_CELLS = [
    [cell('temperature', 104), { type: 'select', key: 'tempType', label: '类型', width: 84, options: TEMP_TYPES }],
    [cell('pulse', 96)],
    [cell('heartRate', 96)],
    [cell('respiration', 96)],
    [cell('systolicBp', 104), cell('diastolicBp', 104)],
    [cell('spo2', 88)],
    [cell('bloodGlucose', 96)],
    [cell('painScore', 88, '疼痛'), { type: 'select', key: 'consciousness', label: '意识', width: 92, options: CONSCIOUS_OPTS }],
    [cell('weight', 96)],
    [cell('stoolCount', 80), cell('urineMl', 96), cell('drainMl', 96)]
  ];

  /* ================= 工具函数 ================= */
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function todayStr() { return fmtDate(new Date()); }
  function daysAgoStr(n) { var d = new Date(); d.setDate(d.getDate() - n); return fmtDate(d); }

  /* 解析后端时间串: 兼容 'yyyy-MM-ddTHH:mm:ss'(Jackson ISO)与 'yyyy-MM-dd HH:mm[:ss]' */
  function parseTs(raw) {
    if (raw === null || raw === undefined) { return null; }
    var m = String(raw).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/);
    if (!m) { return null; }
    return { y: +m[1], mo: +m[2], d: +m[3], h: +m[4], mi: +m[5], s: +(m[6] || 0) };
  }
  /* 时段分桶(与后端 slotOf 一致): <4→0(02:00) <8→1(06:00) <12→2(10:00) <16→3(14:00) <20→4(18:00) 其余→5(22:00) */
  function bucketOfHour(h) {
    if (h < 4) { return 0; }
    if (h < 8) { return 1; }
    if (h < 12) { return 2; }
    if (h < 16) { return 3; }
    if (h < 20) { return 4; }
    return 5;
  }
  function tsVal(rec) {
    var t = parseTs(pickVal(rec, 'recordTime'));
    if (!t) { return 0; }
    return (((t.y * 100 + t.mo) * 100 + t.d) * 100 + t.h) * 10000 + t.mi * 100 + t.s;
  }
  function descByTs(a, b) { return tsVal(b) - tsVal(a); }
  /* 字段双键取值: 驼峰优先, 回落 snake_case(防后端序列化口径差异) */
  function pickVal(rec, key) {
    if (!rec) { return null; }
    var v = rec[key];
    if (v === undefined) {
      var snake = key.replace(/[A-Z]/g, function (c) { return '_' + c.toLowerCase(); });
      v = rec[snake];
    }
    return v === undefined ? null : v;
  }
  /* 默认时段=当前最近时段(环形距离平局取较早) */
  function nearestSlotIdx() {
    var d = new Date();
    var mins = d.getHours() * 60 + d.getMinutes();
    var best = 0, bestDist = Infinity;
    for (var i = 0; i < SLOTS.length; i++) {
      var t = Number(SLOTS[i].slice(0, 2)) * 60 + Number(SLOTS[i].slice(3, 5));
      var dist = Math.abs(t - mins);
      if (dist < bestDist) { bestDist = dist; best = i; }
    }
    return best;
  }
  /* 床号自然排序(与护士站 byBedNo 口径一致), 无床号置后 */
  function bedCompare(a, b) {
    var x = a.bedNo === null || a.bedNo === undefined ? '' : String(a.bedNo);
    var y = b.bedNo === null || b.bedNo === undefined ? '' : String(b.bedNo);
    if (x && !y) { return -1; }
    if (!x && y) { return 1; }
    return x.localeCompare(y, 'zh-CN', { numeric: true });
  }
  /* 术后天数(lastSurgeryDate → 距今自然日差; 缺失/非法返回 null) */
  function postOpDaysOf(p) {
    var raw = pickVal(p, 'lastSurgeryDate');
    if (!raw) { return null; }
    var m = String(raw).match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!m) { return null; }
    var surg = new Date(+m[1], +m[2] - 1, +m[3]);
    var now = new Date();
    var today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return Math.round((today.getTime() - surg.getTime()) / 86400000);
  }
  function freshForm() {
    return {
      temperature: '', tempType: 2, pulse: '', heartRate: '', respiration: '',
      systolicBp: '', diastolicBp: '', spo2: '', bloodGlucose: '', painScore: '',
      consciousness: '', weight: '', stoolCount: '', urineMl: '', drainMl: ''
    };
  }
  function toast(type, text) {
    var EP = global.ElementPlus || {};
    var fn = EP.ElMessage && EP.ElMessage[type];
    if (typeof fn === 'function') {
      try { fn({ message: text, duration: type === 'error' ? 3500 : 2400 }); return; } catch (e) { /* 降级 */ }
    }
    if (type === 'error' && typeof global.alert === 'function') { global.alert(text); }
  }
  function confirmBox(text) {
    var EP = global.ElementPlus || {};
    if (EP.ElMessageBox && typeof EP.ElMessageBox.confirm === 'function') {
      return EP.ElMessageBox.confirm(text, '提示', {
        type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消'
      });
    }
    return Promise.resolve();
  }

  /* ================= 组件 ================= */
  var InpNursingBatchInput = {
    name: 'InpNursingBatchInput',

    props: {
      wardId: { type: [String, Number], required: true },
      patients: { type: Array, default: function () { return []; } }
    },

    emits: ['saved'],

    data: function () {
      return {
        uid: 'nbi' + Math.random().toString(36).slice(2, 8),
        mode: 'item',            /* item=按项目 / patient=按患者 */
        itemKey: 'temperature',  /* 按项目模式当前指标 */
        slotIdx: 3,              /* 时段(created 按现在时间修正为最近) */
        inputs: {},              /* pk -> 本次输入串(undefined=未触碰; ''=已清空) */
        records: {},             /* pk -> 今日记录数组(倒序) | undefined=未加载 */
        ctx: {},                 /* pk -> 近30天记录(倒序; 按患者模式占位符, 懒加载) */
        ctxLoading: false,
        levelFilter: [],         /* 护理等级多选(空=全部) */
        postOpFilter: 0,         /* 术后 N 天内(0=不限) */
        patientSel: '',          /* 按患者模式当前患者 pk */
        form: freshForm(),       /* 按患者模式表单(数值项以字符串承载) */
        saving: false,
        loading: false,
        defs: DEFS,
        slots: SLOTS,
        formCells: FORM_CELLS,
        statusText: { ok: '✓已录', pending: '⚠待测', missed: '○漏测', abnormal: '⚠异常', loading: '…' },
        levelOptions: [
          { value: 1, label: '特级' }, { value: 2, label: '一级' },
          { value: 3, label: '二级' }, { value: 4, label: '三级' }
        ],
        postOpOptions: [
          { value: 0, label: '术后不限' }, { value: 3, label: '术后3天内' },
          { value: 7, label: '术后7天内' }, { value: 14, label: '术后14天内' },
          { value: 30, label: '术后30天内' }
        ],
        _reloadTimer: null
      };
    },

    computed: {
      isItem: function () { return this.mode === 'item'; },
      itemDef: function () { return FORM_DEFS[this.itemKey] || DEFS[0]; },

      /* 过滤(护理等级/术后)+ 床号自然排序 后的病区患者 */
      visiblePatients: function () {
        var lf = this.levelFilter || [], pf = Number(this.postOpFilter) || 0;
        return (this.patients || []).filter(function (p) {
          if (!HIS.idKey(p.id)) { return false; }
          if (lf.length && lf.indexOf(Number(p.nursingLevel)) < 0) { return false; }
          if (pf > 0) {
            var d = postOpDaysOf(p);
            if (d === null || d < 0 || d > pf) { return false; }
          }
          return true;
        }).slice().sort(bedCompare);
      },

      /* 底部汇总: 已录(含异常, 均有值)/待测/漏测/异常 */
      stat: function () {
        var vm = this, done = 0, pending = 0, missed = 0, abnormal = 0;
        this.visiblePatients.forEach(function (p) {
          var st = vm.rowState(p).st;
          if (st === 'abnormal') { abnormal += 1; done += 1; }
          else if (st === 'ok') { done += 1; }
          else if (st === 'pending') { pending += 1; }
          else if (st === 'missed') { missed += 1; }
        });
        return { done: done, total: this.visiblePatients.length, pending: pending, missed: missed, abnormal: abnormal };
      },

      /* 全部待保存的新值数(不受列表过滤影响, 与 saveBatch 收集口径一致) */
      dirtyCount: function () {
        var vm = this, n = 0;
        (this.patients || []).forEach(function (p) { if (vm.dirtyOf(p)) { n += 1; } });
        return n;
      },

      formStat: function () {
        var vm = this, filled = 0, abnormal = 0;
        NUM_KEYS.forEach(function (k) {
          var s = String(vm.form[k] === null || vm.form[k] === undefined ? '' : vm.form[k]).trim();
          if (s === '') { return; }
          filled += 1;
          var v = vm.validate(FORM_DEFS[k], s);
          if (v.level === 'error' || v.level === 'warn') { abnormal += 1; }
        });
        return { filled: filled, abnormal: abnormal };
      },
      anyFilled: function () { return this.formStat.filled > 0; },

      /* 按患者模式患者下拉(全病区, 床号排序) */
      patientOptions: function () {
        return (this.patients || []).filter(function (p) { return !!HIS.idKey(p.id); })
          .slice().sort(bedCompare)
          .map(function (p) {
            return { pk: HIS.idKey(p.id), label: (p.bedNo ? p.bedNo + '-' : '') + (p.patientName || '') };
          });
      },
      /* 按患者模式: 当前时段是否已有记录(提示复测语义) */
      slotRecSel: function () {
        return this.patientSel ? this.recInSlot(this.patientSel, this.slotIdx) : null;
      }
    },

    watch: {
      /* 病区/患者列表变更(宿主切换病区或刷新): 延迟防抖后整体重置并重新预取 */
      patients: { deep: true, handler: function () { this.scheduleReload(); } },
      wardId: function () { this.scheduleReload(); }
    },

    created: function () {
      this.slotIdx = nearestSlotIdx();
    },
    mounted: function () {
      this.loadAll(false);
    },
    beforeUnmount: function () {
      if (this._reloadTimer) { clearTimeout(this._reloadTimer); this._reloadTimer = null; }
    },

    methods: {
      /* ---------- 时段 ---------- */
      slotDateTime: function () { return todayStr() + ' ' + SLOTS[this.slotIdx] + ':00'; },
      curBucket: function () { return bucketOfHour(new Date().getHours()); },

      /* ---------- 校验与清洗 ---------- */
      validate: function (def, raw) {
        if (!def) { return { level: '', tip: '' }; }
        var s = String(raw === null || raw === undefined ? '' : raw).trim();
        if (s === '') { return { level: '', tip: '' }; }
        var n = Number(s);
        if (isNaN(n)) { return { level: 'error', tip: def.label + '请输入数字' }; }
        if (n < def.min || n > def.max) {
          return { level: 'error', tip: def.label + '超出允许范围(' + def.min + '~' + def.max + (def.unit || '') + ')' };
        }
        if (def.warnHigh !== undefined && n > def.warnHigh) {
          return { level: 'warn', tip: def.label + ' ' + s + (def.unit || '') + ' 偏高(>' + def.warnHigh + ')' };
        }
        if (def.warnLow !== undefined && n < def.warnLow) {
          return { level: 'warn', tip: def.label + ' ' + s + (def.unit || '') + ' 偏低(<' + def.warnLow + ')' };
        }
        if (def.warnGte !== undefined && n >= def.warnGte) {
          return { level: 'warn', tip: def.label + ' ' + s + ' 分较高(≥' + def.warnGte + '), 需镇痛关注' };
        }
        return { level: 'ok', tip: '' };
      },
      /* 输入实时清洗: 仅保留数字与一个小数点, 按 decimals 截断小数位 */
      sanitize: function (def, val) {
        var s = String(val === null || val === undefined ? '' : val);
        var out = '', dot = false;
        for (var i = 0; i < s.length; i++) {
          var c = s.charAt(i);
          if (c >= '0' && c <= '9') { out += c; }
          else if (c === '.' && def.decimals > 0 && !dot) { dot = true; out += c; }
        }
        if (def.decimals > 0) {
          var p = out.split('.');
          if (p.length > 1 && p[1].length > def.decimals) { out = p[0] + '.' + p[1].slice(0, def.decimals); }
        }
        return out;
      },

      /* ---------- 记录访问(今日) ---------- */
      recInSlot: function (pk, idx) {
        var list = this.records[pk];
        if (!list) { return null; }
        for (var i = 0; i < list.length; i++) {
          var t = parseTs(pickVal(list[i], 'recordTime'));
          if (t && bucketOfHour(t.h) === idx) { return list[i]; }
        }
        return null;
      },
      /* 本时段已有记录中当前指标的现值(未录/该指标为空 → null) */
      baseOf: function (pk) {
        var rec = this.recInSlot(pk, this.slotIdx);
        if (!rec) { return null; }
        var v = pickVal(rec, this.itemKey);
        return v === null || v === undefined ? null : v;
      },
      /* 行状态判定: 输入优先(硬超范围/警戒→异常; 有值且正常→已录), 无输入回落记录口径 */
      rowState: function (p) {
        var def = this.itemDef;
        var pk = HIS.idKey(p.id);
        var raw = this.inputs[pk];
        var s = String(raw === undefined || raw === null ? '' : raw).trim();
        if (s !== '') {
          var v = this.validate(def, s);
          return { st: (v.level === 'error' || v.level === 'warn') ? 'abnormal' : 'ok', tip: v.tip };
        }
        if (this.records[pk] === undefined) { return { st: 'loading', tip: '' }; }
        var rec = this.recInSlot(pk, this.slotIdx);
        if (rec) {
          var bv = pickVal(rec, def.key);
          if (bv !== null && bv !== undefined) {
            var v2 = this.validate(def, bv);
            return { st: (v2.level === 'error' || v2.level === 'warn') ? 'abnormal' : 'ok', tip: v2.tip };
          }
        }
        /* 未录: 当前时段已到点(或回溯补录)时, 上一时段亦无 → 漏测; 否则待测 */
        if (this.slotIdx > 0 && this.slotIdx <= this.curBucket()) {
          var prev = this.recInSlot(pk, this.slotIdx - 1);
          var pv = prev ? pickVal(prev, def.key) : null;
          if (pv === null || pv === undefined) {
            return { st: 'missed', tip: '上一时段(' + SLOTS[this.slotIdx - 1] + ')未测' };
          }
        }
        return { st: 'pending', tip: '本时段(' + SLOTS[this.slotIdx] + ')待测' };
      },
      statusOf: function (p) { return this.rowState(p).st; },
      tipOf: function (p) { return this.rowState(p).tip; },
      rowCls: function (p) { return 'st-' + this.statusOf(p); },
      statCls: function (p) { return 'nbi-status c-' + this.statusOf(p); },
      /* 上次值: 本时段之前(更早时段)最近一条含当前指标的值 */
      lastText: function (p) {
        var pk = HIS.idKey(p.id);
        var list = this.records[pk];
        if (!list) { return '-'; }
        for (var i = 0; i < list.length; i++) {
          var t = parseTs(pickVal(list[i], 'recordTime'));
          if (!t) { continue; }
          if (bucketOfHour(t.h) < this.slotIdx) {
            var v = pickVal(list[i], this.itemKey);
            if (v !== null && v !== undefined) { return String(v); }
          }
        }
        return '-';
      },
      levelText: function (p) { return LEVEL_FULL[Number(p.nursingLevel)] || '-'; },
      emptyText: function () {
        return (this.patients && this.patients.length) ? '无符合过滤条件的患者' : '暂无患者数据';
      },

      /* ---------- 按项目: 输入/状态/焦点 ---------- */
      displayValue: function (p) {
        var pk = HIS.idKey(p.id);
        var raw = this.inputs[pk];
        if (raw !== undefined) { return raw; }
        var b = this.baseOf(pk);
        return b === null ? '' : String(b);
      },
      inputPlaceholder: function (p) {
        return this.baseOf(HIS.idKey(p.id)) === null ? '待录' : '';
      },
      onInput: function (p, val) {
        this.inputs[HIS.idKey(p.id)] = this.sanitize(this.itemDef, val);
      },
      inputClass: function (p) {
        var pk = HIS.idKey(p.id);
        var raw = this.inputs[pk];
        var s = String(raw === undefined || raw === null ? '' : raw).trim();
        if (s === '') { return ''; }
        var v = this.validate(this.itemDef, s);
        if (v.level === 'error') { return 'is-bad'; }
        if (v.level === 'warn') { return 'is-warn'; }
        return '';
      },
      dirtyOf: function (p) {
        var pk = HIS.idKey(p.id);
        var raw = this.inputs[pk];
        if (raw === undefined || raw === null) { return false; }
        var s = String(raw).trim();
        if (s === '') { return false; }
        var b = this.baseOf(pk);
        if (b === null) { return true; }
        return Number(s) !== Number(b);
      },
      touchedCount: function () {
        var vm = this, n = 0;
        (this.patients || []).forEach(function (p) {
          var raw = vm.inputs[HIS.idKey(p.id)];
          if (raw !== undefined && String(raw).trim() !== '') { n += 1; }
        });
        return n;
      },
      rowDomId: function (i) { return this.uid + '-row-' + i; },
      /* 回车 → 聚焦下一床输入框(实例 uid 前缀防多实例重复 id) */
      focusNext: function (i) {
        var el = document.getElementById(this.uid + '-row-' + (i + 1));
        var inp = el ? el.querySelector('input') : null;
        if (inp) { inp.focus(); }
      },

      /* ---------- 按项目: 顶栏受控选择(有草稿时确认后切换) ---------- */
      onItemSelect: function (val) {
        if (val === this.itemKey) { return; }
        var vm = this, n = this.touchedCount();
        var apply = function () { vm.itemKey = val; vm.inputs = {}; };
        if (n > 0) {
          confirmBox('切换项目将清空已填写的 ' + n + ' 个值, 是否继续?').then(apply).catch(function () { });
        } else { apply(); }
      },
      onSlotSelect: function (val) {
        if (val === this.slotIdx) { return; }
        var vm = this, n = this.touchedCount();
        var apply = function () { vm.slotIdx = val; vm.inputs = {}; };
        if (n > 0) {
          confirmBox('切换时段将清空已填写的 ' + n + ' 个值, 是否继续?').then(apply).catch(function () { });
        } else { apply(); }
      },

      /* ---------- 数据加载(并发池, 上限 6) ---------- */
      scheduleReload: function () {
        var vm = this;
        if (this._reloadTimer) { clearTimeout(this._reloadTimer); }
        this._reloadTimer = setTimeout(function () {
          vm._reloadTimer = null;
          vm.resetAll();
          vm.loadAll(false);
        }, 80);
      },
      resetAll: function () {
        this.inputs = {};
        this.records = {};
        this.ctx = {};
        this.patientSel = '';
        this.form = freshForm();
      },
      reload: function () { this.records = {}; this.loadAll(false); },
      loadAll: function (force) {
        var vm = this;
        var targets = [];
        (this.patients || []).forEach(function (p) {
          var pk = HIS.idKey(p.id);
          if (!pk) { return; }
          if (!force && vm.records[pk] !== undefined) { return; }
          targets.push(p);
        });
        if (!targets.length) { vm.loading = false; return Promise.resolve(); }
        vm.loading = true;
        var idx = 0, active = 0, settled = 0, failed = 0;
        return new Promise(function (resolve) {
          function done() {
            settled += 1; active -= 1;
            if (settled === targets.length) {
              vm.loading = false;
              if (failed) { toast('warning', '有 ' + failed + ' 名患者记录加载失败(可点刷新重试)'); }
              resolve();
            } else { pump(); }
          }
          function fetchOne(p) {
            active += 1;
            vm.fetchToday(p).catch(function () {
              failed += 1;
              vm.records[HIS.idKey(p.id)] = [];
            }).then(done);
          }
          function pump() {
            while (active < 6 && idx < targets.length) {
              fetchOne(targets[idx]);
              idx += 1;
            }
          }
          pump();
        });
      },
      /* 当日体征记录(宿主以 props 提供患者, 每人一次列表请求) */
      fetchToday: function (p) {
        var vm = this;
        var pk = HIS.idKey(p.id);
        return HIS.get('/api/his/inp/nursing/vital-sign/list?inpVisitId=' + HIS.idParam(p.id)
            + '&start=' + todayStr())
          .then(function (list) {
            vm.records[pk] = (Array.isArray(list) ? list : []).slice().sort(descByTs);
          });
      },
      /* 按患者模式: 近30天记录懒加载(占位符来源) */
      ensureCtx: function () {
        var vm = this;
        var pk = this.patientSel;
        if (!pk || this.ctx[pk] !== undefined) { return; }
        var p = this.patientByPk(pk);
        if (!p) { return; }
        this.ctxLoading = true;
        HIS.get('/api/his/inp/nursing/vital-sign/list?inpVisitId=' + HIS.idParam(p.id)
            + '&start=' + daysAgoStr(29) + '&end=' + todayStr())
          .then(function (list) {
            vm.ctx[pk] = (Array.isArray(list) ? list : []).slice().sort(descByTs);
          })
          .catch(function () { vm.ctx[pk] = []; })
          .then(function () { vm.ctxLoading = false; });
      },

      /* ---------- 按患者: 选择/表单 ---------- */
      patientByPk: function (pk) {
        var hit = null;
        (this.patients || []).some(function (p) {
          if (HIS.sameId(p.id, pk)) { hit = p; return true; }
          return false;
        });
        return hit;
      },
      onPatientSelect: function (val) {
        if (val === this.patientSel) { return; }
        var vm = this;
        var apply = function () {
          vm.patientSel = val;
          vm.form = freshForm();
          vm.ensureCtx();
        };
        if (this.anyFilled) {
          confirmBox('切换患者将清空已填写的体征项, 是否继续?').then(apply).catch(function () { });
        } else { apply(); }
      },
      fStyle: function (f) { return { width: (f.width || 100) + 'px' }; },
      phOf: function (key) {
        var pk = this.patientSel;
        if (!pk) { return ''; }
        var list = this.ctx[pk];
        if (!list || !list.length) { return ''; }
        for (var i = 0; i < list.length; i++) {
          var v = pickVal(list[i], key);
          if (v !== null && v !== undefined) { return String(v); }
        }
        return '';
      },
      formTip: function (f) {
        var s = String(this.form[f.key] === null || this.form[f.key] === undefined ? '' : this.form[f.key]).trim();
        if (s === '') { return ''; }
        var v = this.validate(FORM_DEFS[f.key], s);
        return (v.level === 'error' || v.level === 'warn') ? v.tip : '';
      },
      formCls: function (f) {
        var s = String(this.form[f.key] === null || this.form[f.key] === undefined ? '' : this.form[f.key]).trim();
        if (s === '') { return ''; }
        var v = this.validate(FORM_DEFS[f.key], s);
        return v.level === 'error' ? 'is-bad' : (v.level === 'warn' ? 'is-warn' : '');
      },
      onFormInput: function (f, val) {
        this.form[f.key] = this.sanitize(FORM_DEFS[f.key], val);
      },
      resetForm: function () { this.form = freshForm(); },
      badCls: function (n) { return n > 0 ? 'nbi-sum-bad' : ''; },

      /* ---------- 保存 ---------- */
      /* 批量保存(按项目模式): 收集全部(不受过滤影响)已改动的非空输入, 硬校验通过后 ≤100/批提交 */
      saveBatch: function () {
        var vm = this;
        var def = this.itemDef;
        var payload = [], problems = [];
        (this.patients || []).forEach(function (p) {
          var pk = HIS.idKey(p.id);
          if (!pk) { return; }
          var raw = vm.inputs[pk];
          if (raw === undefined || raw === null) { return; }
          var s = String(raw).trim();
          if (s === '') { return; }
          var v = vm.validate(def, s);
          if (v.level === 'error') {
            problems.push((p.bedNo ? p.bedNo + ' ' : '') + (p.patientName || '') + ': ' + v.tip);
            return;
          }
          var dto = {
            inpVisitId: HIS.id(p.id),
            patientId: HIS.id(p.patientId),
            recordTime: vm.slotDateTime()
          };
          dto[def.key] = Number(s);
          payload.push(dto);
        });
        if (problems.length) {
          toast('error', '存在超范围输入, 请先修正: ' + problems.slice(0, 2).join('; ')
            + (problems.length > 2 ? ' 等' : ''));
          return;
        }
        if (!payload.length) { toast('info', '没有需要保存的新值'); return; }
        var chunks = [];
        for (var i = 0; i < payload.length; i += 100) { chunks.push(payload.slice(i, i + 100)); }
        this.saving = true;
        var savedAll = [];
        (function next(err) {
          if (err) { vm.saving = false; toast('error', err.message || '保存失败'); return; }
          if (!chunks.length) { vm.saving = false; vm.afterSaved(savedAll); return; }
          HIS.post('/api/his/inp/nursing/vital-sign/batch', chunks.shift()).then(function (list) {
            savedAll = savedAll.concat(Array.isArray(list) ? list : []);
            next(null);
          }).catch(function (e) { next(e); });
        })(null);
      },
      /* 单条保存(按患者模式): 全项表单 → 一条体征记录 */
      saveOne: function () {
        var vm = this;
        var p = this.patientByPk(this.patientSel);
        if (!p) { toast('warning', '请先选择患者'); return; }
        var dto = {
          inpVisitId: HIS.id(p.id),
          patientId: HIS.id(p.patientId),
          recordTime: this.slotDateTime()
        };
        var problems = [];
        NUM_KEYS.forEach(function (key) {
          var s = String(vm.form[key] === null || vm.form[key] === undefined ? '' : vm.form[key]).trim();
          if (s === '') { return; }
          var v = vm.validate(FORM_DEFS[key], s);
          if (v.level === 'error') { problems.push(v.tip); return; }
          dto[key] = Number(s);
        });
        if (dto.systolicBp !== undefined && dto.diastolicBp !== undefined && dto.systolicBp < dto.diastolicBp) {
          problems.push('收缩压不得低于舒张压');
        }
        if (dto.temperature !== undefined) { dto.tempType = Number(this.form.tempType) || 2; }
        if (this.form.consciousness) { dto.consciousness = this.form.consciousness; }
        if (problems.length) {
          toast('error', '请先修正: ' + problems.slice(0, 2).join('; ') + (problems.length > 2 ? ' 等' : ''));
          return;
        }
        var hasAny = NUM_KEYS.some(function (k) { return dto[k] !== undefined; });
        if (!hasAny) { toast('warning', '请至少填写一项体征'); return; }
        this.saving = true;
        HIS.post('/api/his/inp/nursing/vital-sign', dto).then(function (rec) {
          vm.saving = false;
          vm.form = freshForm();
          vm.afterSaved(rec ? [rec] : []);
        }).catch(function (e) {
          vm.saving = false;
          toast('error', e.message || '保存失败');
        });
      },
      /* 保存成功回写: 合并到 records(及已加载的 ctx), 清空对应草稿, 提示+事件 */
      afterSaved: function (saved) {
        var vm = this;
        var byPk = {};
        (saved || []).forEach(function (rec) {
          var pk = HIS.idKey(pickVal(rec, 'inpVisitId'));
          if (!pk) { return; }
          (byPk[pk] = byPk[pk] || []).push(rec);
        });
        Object.keys(byPk).forEach(function (pk) {
          var list = vm.records[pk];
          if (list === undefined) { list = vm.records[pk] = []; }
          byPk[pk].forEach(function (rec) { list.push(rec); });
          list.sort(descByTs);
          delete vm.inputs[pk];
          var ctxList = vm.ctx[pk];
          if (ctxList) {
            byPk[pk].forEach(function (rec) { ctxList.push(rec); });
            ctxList.sort(descByTs);
          }
        });
        var n = (saved || []).length;
        if (n > 0) { toast('success', '已保存 ' + n + ' 条体征记录'); }
        var worst = 0, worstScore = 0;
        (saved || []).forEach(function (rec) {
          var lv = Number(pickVal(rec, 'mewsLevel')) || 0;
          if (lv > worst) { worst = lv; worstScore = Number(pickVal(rec, 'mewsScore')) || 0; }
        });
        if (worst >= 2) {
          toast('warning', '其中存在 MEWS ' + worstScore + ' 分(' + MEWS_TEXT[worst] + ')预警记录, 请关注病情变化');
        }
        this.$emit('saved', n);
      }
    },

    /* ================= 模板 ================= */
    template: [
      '<div class="nbi-root">',
      /* ---- 顶栏: 模式切换 + 上下文控件 ---- */
      '  <div class="nbi-bar">',
      '    <el-radio-group v-model="mode" size="small">',
      '      <el-radio-button label="item">按项目</el-radio-button>',
      '      <el-radio-button label="patient">按患者</el-radio-button>',
      '    </el-radio-group>',
      '    <template v-if="isItem">',
      '      <span class="nbi-lab">项目</span>',
      '      <el-select :model-value="itemKey" size="small" class="nbi-sel" @change="onItemSelect">',
      '        <el-option v-for="d in defs" :key="d.key" :label="d.label" :value="d.key"></el-option>',
      '      </el-select>',
      '      <span class="nbi-lab">时间</span>',
      '      <el-select :model-value="slotIdx" size="small" class="nbi-sel-narrow" @change="onSlotSelect">',
      '        <el-option v-for="(s, i) in slots" :key="i" :label="s" :value="i"></el-option>',
      '      </el-select>',
      '      <el-select v-model="levelFilter" multiple collapse-tags clearable size="small" class="nbi-sel-mid" placeholder="护理等级">',
      '        <el-option v-for="o in levelOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '      </el-select>',
      '      <el-select v-model="postOpFilter" size="small" class="nbi-sel-narrow">',
      '        <el-option v-for="o in postOpOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '      </el-select>',
      '      <span class="nbi-spacer"></span>',
      '      <span v-if="loading" class="nbi-loading">记录加载中…</span>',
      '      <el-button size="small" :disabled="loading" @click="reload">刷新</el-button>',
      '      <el-button type="primary" size="small" :loading="saving" :disabled="!dirtyCount" @click="saveBatch">全部保存({{ dirtyCount }})</el-button>',
      '    </template>',
      '    <template v-else>',
      '      <span class="nbi-lab">患者</span>',
      '      <el-select :model-value="patientSel" filterable size="small" class="nbi-sel-patient" placeholder="选择患者" @change="onPatientSelect">',
      '        <el-option v-for="o in patientOptions" :key="o.pk" :label="o.label" :value="o.pk"></el-option>',
      '      </el-select>',
      '      <span class="nbi-lab">时间</span>',
      '      <el-select :model-value="slotIdx" size="small" class="nbi-sel-narrow" @change="onSlotSelect">',
      '        <el-option v-for="(s, i) in slots" :key="i" :label="s" :value="i"></el-option>',
      '      </el-select>',
      '      <span class="nbi-spacer"></span>',
      '      <span v-if="ctxLoading" class="nbi-loading">历史值加载中…</span>',
      '      <el-button size="small" :disabled="!anyFilled" @click="resetForm">清空</el-button>',
      '      <el-button type="primary" size="small" :loading="saving" :disabled="!patientSel" @click="saveOne">保存</el-button>',
      '    </template>',
      '  </div>',
      /* ---- 按项目: 逐床表格 ---- */
      '  <template v-if="isItem">',
      '    <div class="nbi-table-wrap">',
      '      <div class="nbi-grid nbi-head">',
      '        <span>床号</span><span>姓名</span><span>护理等级</span><span>上次值</span><span>本次值</span><span>状态</span>',
      '      </div>',
      '      <div v-if="!visiblePatients.length" class="nbi-empty">{{ emptyText() }}</div>',
      '      <div v-for="(p, i) in visiblePatients" :key="p.id" class="nbi-grid nbi-row" :class="rowCls(p)" :id="rowDomId(i)">',
      '        <span class="nbi-bed">{{ p.bedNo || "-" }}</span>',
      '        <span class="nbi-name" :title="p.patientName">{{ p.patientName }}</span>',
      '        <span class="nbi-level">{{ levelText(p) }}</span>',
      '        <span class="nbi-last">{{ lastText(p) }}</span>',
      '        <span class="nbi-cell" :class="inputClass(p)">',
      '          <el-input size="small" :model-value="displayValue(p)" :placeholder="inputPlaceholder(p)" @input="onInput(p, $event)" @keydown.enter.prevent="focusNext(i)"></el-input>',
      '        </span>',
      '        <el-tooltip :content="tipOf(p)" :disabled="!tipOf(p)" placement="left">',
      '          <span :class="statCls(p)">{{ statusText[statusOf(p)] }}</span>',
      '        </el-tooltip>',
      '      </div>',
      '    </div>',
      '    <div class="nbi-sum">',
      '      <span>已录 <b>{{ stat.done }}</b>/{{ stat.total }} 人</span>',
      '      <span>待测 {{ stat.pending }} 人</span>',
      '      <span>漏测 {{ stat.missed }} 人</span>',
      '      <span :class="badCls(stat.abnormal)">异常 {{ stat.abnormal }} 项</span>',
      '      <span class="nbi-sum-tip">Tab/回车 切换下一位; 红框为超范围需修正</span>',
      '    </div>',
      '  </template>',
      /* ---- 按患者: 全项表单 ---- */
      '  <template v-else>',
      '    <div v-if="!patientSel" class="nbi-empty nbi-empty-lg">请先选择患者(下拉支持按床号/姓名检索)</div>',
      '    <template v-else>',
      '      <div class="nbi-form">',
      '        <div v-for="(cell, ci) in formCells" :key="ci" class="nbi-cellgroup">',
      '          <div v-for="f in cell" :key="f.key" class="nbi-field">',
      '            <label class="nbi-flabel">{{ f.label }}</label>',
      '            <el-select v-if="f.type === \'select\'" v-model="form[f.key]" size="small" :style="fStyle(f)">',
      '              <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '            <template v-else>',
      '              <el-tooltip :content="formTip(f)" :disabled="!formTip(f)" placement="top">',
      '                <el-input size="small" :style="fStyle(f)" :class="formCls(f)" :model-value="form[f.key]" :placeholder="phOf(f.key)" @input="onFormInput(f, $event)"></el-input>',
      '              </el-tooltip>',
      '              <span v-if="f.unit" class="nbi-unit">{{ f.unit }}</span>',
      '            </template>',
      '          </div>',
      '        </div>',
      '      </div>',
      '      <div v-if="slotRecSel" class="nbi-hint">此时段({{ slots[slotIdx] }})已有记录, 保存将新增一条复测记录; 灰色占位为该患者最近一次值</div>',
      '      <div class="nbi-sum">',
      '        <span>已填 <b>{{ formStat.filled }}</b> 项</span>',
      '        <span :class="badCls(formStat.abnormal)">异常 {{ formStat.abnormal }} 项</span>',
      '        <span class="nbi-sum-tip">留空项不提交; 占位值为上次记录仅供参考</span>',
      '      </div>',
      '    </template>',
      '  </template>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.InpNursingBatchInput = InpNursingBatchInput;
  /* 全局标签注册: app 就绪后挂 <dw-inp-nursing-batch-input>(脚本先于 app.js 加载, 故延时至 DOMContentLoaded;
   * P4b-5 集成后 inp-nurse.js 亦可经 HIS.components 局部声明, 局部声明优先于全局标签) */
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-nursing-batch-input', InpNursingBatchInput); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
