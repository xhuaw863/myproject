/* vital-sign-chart.js — 住院护士站标准体温单(Canvas 版, P4a-4, HIS.components.InpVitalSignChart)
 * ------------------------------------------------------------------
 * 用途: 按卫生部《体温单》标准格式, 以原生 Canvas 2D 绘制 7 天×6 时段(02/06/10/14/18/22)的
 *       体温/脉搏/呼吸/血压曲线与出入量页脚, 供护理记录页签嵌入与打印归档。
 * 特性:
 *   - 42 列标准网格: 顶部 日期/住院天数/术后天数/时段 表头, 主区体温 35~42℃(0.2℃一格, 37℃红线),
 *     右轴脉搏刻度(1℃=20 次/分), 呼吸黑虚线(映射主区下带), 血压绿色竖条(收缩压顶/舒张压底)。
 *   - 体温标记按类型区分: ●口温 ×腋温 ○肛温 △耳温(红); 脉搏蓝实心圆; MEWS≥5 时段列淡红预警背景;
 *     体温≥39℃ 点位橙色高亮; 死亡/出院事件后曲线断开。
 *   - 新生儿模板: 体温 34~40℃ / 心率 80~200(同 1℃=20 比例)。
 *   - 页脚 8 行: 呼吸/血压/大便次数/尿量/引流量/体重/过敏药物/皮试。
 *   - P8a-2 节气标注: 日期轴按窗口拉取 GET /api/emr/solar-term(后端 2020-2035 查表),
 *     窗口含节气日时日期文字上移半行, 当日在其下方以小字灰色标注节气名(接口未就绪静默降级)。
 *   - 7 天窗口前后翻页(不超过今天), canvas 恒定逻辑宽 1200px, 容器横向滚动;
 *     内部像素按 devicePixelRatio 放大保证 HiDPI 锐利; 打印以 ≥2x 离屏重绘导出 PNG 新窗打印。
 * 数据契约(后端未就绪时优雅降级, 不打断页面):
 *   GET /api/his/inp/nursing/vital-sign/chart-data?inpVisitId={id}&days=7&startDate=&endDate=
 *   → { vitals:[{recordTime,temperature,tempType,pulse,respiration,systolicBp,diastolicBp,
 *                spo2,painScore,mewsScore,stoolCount,urineMl,drainMl,weight}],
 *       events:[{eventType,eventTime}], admitDate, surgeryDates:[], allergy?, skinTest? }
 *   接口 404 时自动回退旧接口 /api/his/inp/nursing/temperature/{visitId}(仅体温/脉搏, 行内提示降级)。
 *   字段同时兼容蛇形命名(record_time/temp_type/...), 事件类型兼容 admit/surgery/transfer-in/
 *   transfer-out/delivery/death/discharge 及常见英文别名。
 * 注册: HIS.components.InpVitalSignChart + 全局标签 <dw-inp-vital-sign-chart> 双保险
 *       (须在 inp-nurse.js 之前加载; 依赖 api.js 的 HIS.get/idParam)。
 * 接入: P4a-5 由 inp-nurse.js 以局部组件方式集成(传 :inp-visit-id 必填/:patient-id/:chart-type);
 *       集成前可经全局标签在任意已登录页面独立验证(未传 inpVisitId 时展示空表不报错)。
 */
(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 常量 ================= */
  var LOGIC_W = 1200;                       /* 逻辑画布宽(固定, 容器横向滚动) */
  var LEFT = 52;                            /* 左轴区宽(温度刻度/行标签) */
  var RIGHT = 44;                           /* 右轴区宽(脉搏刻度) */
  var COLS = 42;                            /* 7 天 × 6 时段 */
  var SLOTS = [2, 6, 10, 14, 18, 22];       /* 标准测量时段(整点) */

  /* 配色(任务规范): 体温红 / 脉搏蓝 / 呼吸黑虚线 / 血压绿 / 网格灰 / 37℃线浅红 */
  var TEMP_COLOR = '#E53935';
  var PULSE_COLOR = '#1E88E5';
  var RESP_COLOR = '#333333';
  var BP_COLOR = '#43A047';
  var GRID_COLOR = '#E0E0E0';
  var GRID_37 = '#FFCDD2';
  var INK = '#37474F';                      /* 外框/主线条 */
  var INK_SOFT = '#B0BEC5';                 /* 整度线/天分隔线 */
  var INK_LIGHT = '#ECEFF1';                /* 细分隔线 */
  var TEXT_MAIN = '#1c2430';
  var TEXT_SUB = '#546E7A';

  /* 成人 / 新生人两套刻度(1℃ = 20 次/分, 脉搏与体温共轴) */
  var MODES = {
    adult: { key: 'adult', label: '成人', tempMin: 35, tempMax: 42, pulseMin: 40, pulseMax: 180, bpmPerC: 20 },
    newborn: { key: 'newborn', label: '新生儿', tempMin: 34, tempMax: 40, pulseMin: 80, pulseMax: 200, bpmPerC: 20 }
  };

  /* 体温类型标记: 1口温● 2腋温× 3肛温○ 4耳温△(兼容英文别名) */
  var TEMP_TYPE_KEYS = { 1: 1, 2: 2, 3: 3, 4: 4, oral: 1, mouth: 1, axillary: 2, armpit: 2, rectal: 3, anal: 3, tympanic: 4, ear: 4 };

  /* 临床事件: 标签 + 颜色(入院/转入蓝, 手术/出院/死亡红, 转出灰, 分娩紫) */
  var EVENT_DEFS = {
    admit: { label: '入院', color: '#1E88E5' }, admission: { label: '入院', color: '#1E88E5' },
    surgery: { label: '手术', color: '#E53935' }, op: { label: '手术', color: '#E53935' },
    transferin: { label: '转入', color: '#1E88E5' }, transferout: { label: '转出', color: '#78909C' },
    delivery: { label: '分娩', color: '#8E24AA' }, birth: { label: '分娩', color: '#8E24AA' },
    death: { label: '死亡', color: '#B71C1C' }, discharge: { label: '出院', color: '#E53935' },
    /* P4c-4 临床事件接口新增类型: 抢救(resuscitation, 兼容 rescue 别名) */
    resuscitation: { label: '抢救', color: '#E65100' }, rescue: { label: '抢救', color: '#E65100' }
  };

  /* 页脚数据行(顺序即卫生部体温单栏序; get 返回 null 则该列留空) */
  var FOOTER_ROWS = [
    { key: 'resp', label: '呼吸(次/分)', get: function (v) { return v.respiration; } },
    { key: 'bp', label: '血压(mmHg)', get: function (v) {
        if (v.systolicBp == null && v.diastolicBp == null) { return null; }
        return (v.systolicBp == null ? '-' : v.systolicBp) + '/' + (v.diastolicBp == null ? '-' : v.diastolicBp);
      } },
    { key: 'stool', label: '大便(次)', get: function (v) { return v.stoolCount; } },
    { key: 'urine', label: '尿量(ml)', get: function (v) { return v.urineMl; } },
    { key: 'drain', label: '引流(ml)', get: function (v) { return v.drainMl; } },
    { key: 'weight', label: '体重(kg)', get: function (v) { return v.weight == null ? null : Math.round(v.weight * 10) / 10; } },
    { key: 'allergy', label: '过敏药物', special: 'allergy' },
    { key: 'skin', label: '皮试', special: 'skinTest' }
  ];

  /* ================= 工具函数(纯函数, 便于单测) ================= */
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtD(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  /* 本地时区解析 'YYYY-MM-DD'(截断时间部分), 非法返回 null */
  function parseD(s) {
    var p = String(s || '').substring(0, 10).split('-');
    if (p.length < 3 || !p[0] || !+p[0]) { return null; }
    return new Date(+p[0], (+p[1] || 1) - 1, +p[2] || 1);
  }
  function addDays(d, n) { var x = new Date(d.getTime()); x.setDate(x.getDate() + n); return x; }
  function dayDiff(a, b) { return Math.round((a - b) / 86400000); }
  /* 数值收敛: 空串/非数返回 null */
  function num(v) { if (v == null || v === '') { return null; } var n = Number(v); return isFinite(n) ? n : null; }
  /* 驼峰/蛇形双键取值(兼容后端 Map 蛇形输出与实体驼峰输出) */
  function pick(v, camel, snake) {
    if (!v) { return null; }
    if (v[camel] != null && v[camel] !== '') { return v[camel]; }
    if (snake && v[snake] != null && v[snake] !== '') { return v[snake]; }
    return null;
  }
  /* 测量时间解析 → {day:'YYYY-MM-DD', hour:0-23}; 兼容 'T'/空格分隔, 日期非补零 */
  function tsOf(s) {
    var m = String(s || '').replace('T', ' ').match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ ](\d{1,2}))?/);
    if (!m) { return null; }
    return { day: m[1] + '-' + pad2(+m[2]) + '-' + pad2(+m[3]), hour: m[4] != null ? +m[4] : 12 };
  }
  /* 测量/事件时间 → 本地毫秒(分钟精度); 用于出院/死亡后的曲线断线判定(列序数同列时也能区分先后) */
  function parseTs(s) {
    var m = String(s || '').replace('T', ' ').match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ ](\d{1,2})(?::(\d{1,2}))?)?/);
    if (!m) { return null; }
    return new Date(+m[1], (+m[2] || 1) - 1, +m[3] || 1, +(m[4] || 0), +(m[5] || 0)).getTime();
  }
  /* 小时 → 最近标准时段槽(0-5, 环形 24h 距离, 平局取较早槽) */
  function slotOfHour(h) {
    var best = 0, bestD = 99;
    for (var i = 0; i < 6; i++) {
      var d0 = Math.abs(h - SLOTS[i]);
      var d = Math.min(d0, 24 - d0);
      if (d < bestD) { bestD = d; best = i; }
    }
    return best;
  }
  function evKey(type) { return String(type || '').toLowerCase().replace(/[-_\s]/g, ''); }
  /* vital 原始行 → 驼峰规范化(排序/绘制的唯一数据形态) */
  function normVital(v) {
    var tt = pick(v, 'tempType', 'temp_type');
    var ttk = TEMP_TYPE_KEYS[String(tt == null ? '' : tt).toLowerCase()] || (num(tt) >= 1 && num(tt) <= 4 ? num(tt) : 1);
    return {
      recordTime: pick(v, 'recordTime', 'record_time'),
      tsMs: parseTs(pick(v, 'recordTime', 'record_time')),
      temperature: num(pick(v, 'temperature')),
      tempType: ttk,
      pulse: num(pick(v, 'pulse')),
      heartRate: num(pick(v, 'heartRate', 'heart_rate')),
      respiration: num(pick(v, 'respiration')),
      systolicBp: num(pick(v, 'systolicBp', 'systolic_bp')),
      diastolicBp: num(pick(v, 'diastolicBp', 'diastolic_bp')),
      spo2: num(pick(v, 'spo2', 'spo_2')),
      painScore: num(pick(v, 'painScore', 'pain_score')),
      mewsScore: num(pick(v, 'mewsScore', 'mews_score')),
      stoolCount: num(pick(v, 'stoolCount', 'stool_count')),
      urineMl: num(pick(v, 'urineMl', 'urine_ml')),
      drainMl: num(pick(v, 'drainMl', 'drain_ml')),
      weight: num(pick(v, 'weight'))
    };
  }
  function cmpTs(a, b) {
    var ta = String(a.recordTime || '').replace('T', ' ');
    var tb = String(b.recordTime || '').replace('T', ' ');
    return ta < tb ? -1 : (ta > tb ? 1 : 0);
  }
  /* Canvas 基元: 横线/竖线(对齐 0.5px 逻辑像素, 由 dpr 变换负责平滑) */
  function hline(ctx, x1, y, x2, color, w) {
    ctx.strokeStyle = color; ctx.lineWidth = w || 1;
    ctx.beginPath(); ctx.moveTo(x1, y); ctx.lineTo(x2, y); ctx.stroke();
  }
  function vline(ctx, x, y1, y2, color, w) {
    ctx.strokeStyle = color; ctx.lineWidth = w || 1;
    ctx.beginPath(); ctx.moveTo(x, y1); ctx.lineTo(x, y2); ctx.stroke();
  }
  /* HTML 转义(打印窗口标题等动态文本入 document.write 前统一转义) */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ================= 私有样式(一次性注入 head, vsc- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('vsc-style')) { return; }
    var st = document.createElement('style');
    st.id = 'vsc-style';
    st.textContent = [
      '.vsc-root { border:1px solid var(--yb-border-light,#dfe4eb); border-radius:var(--yb-r-md,8px); background:var(--yb-surface,#fff); padding:10px 12px 12px; }',
      '.vsc-toolbar { display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:8px; }',
      '.vsc-toolbar .el-button + .el-button { margin-left:0; }',
      '.vsc-range { font-weight:700; color:var(--yb-ink-1,#1c2430); font-variant-numeric:tabular-nums; min-width:196px; text-align:center; font-size:13px; }',
      '.vsc-spacer { flex:1; }',
      '.vsc-scroll { overflow-x:auto; overflow-y:hidden; background:var(--yb-surface,#fff); border-radius:4px; }',
      '.vsc-canvas { display:block; }',
      '.vsc-legend { display:flex; flex-wrap:wrap; align-items:center; gap:4px 16px; margin-top:8px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.vsc-legend b { font-weight:700; color:var(--yb-ink-2,#333); margin:0 1px; }',
      '.vsc-line { display:inline-block; width:16px; border-top:2px solid #999; vertical-align:3px; margin-right:3px; }',
      '.vsc-line.vsc-dash { border-top-style:dashed; }',
      '.vsc-dot { display:inline-block; width:8px; height:8px; border-radius:50%; vertical-align:0; margin-right:3px; }',
      '.vsc-bp { display:inline-block; width:3px; height:12px; vertical-align:-2px; margin:0 4px 0 1px; }',
      '.vsc-block { display:inline-block; width:12px; height:12px; background:rgba(229,57,53,.12); border:1px solid rgba(229,57,53,.35); vertical-align:-2px; margin-right:4px; }',
      '.vsc-tip { margin-top:6px; font-size:12px; color:#B8820C; background:var(--yb-fill-warning, #FDF6EC); border:1px solid #E6A23C55; border-radius:var(--yb-r-sm,4px); padding:5px 10px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 组件 ================= */
  var InpVitalSignChart = {
    name: 'InpVitalSignChart',
    props: {
      inpVisitId: { type: [String, Number], required: true },
      patientId: { type: [String, Number], default: null },
      chartType: { type: String, default: 'adult' }        /* 'adult' | 'newborn' */
    },
    data: function () {
      return {
        loading: false,
        loadError: false,                                   /* chart-data 与回退接口均失败 */
        degraded: false,                                    /* 回退旧体温接口(仅体温/脉搏) */
        localType: this.chartType === 'newborn' ? 'newborn' : 'adult',
        winEnd: fmtD(new Date()),                           /* 窗口末天(含), 默认今天 */
        payload: null,                                      /* {vitals, events, admitDate, surgeryDates, ...} */
        pendingClinicalEvents: [],                          /* P4c-4 临床事件时间轴暂存(payload 未到时先落住) */
        solarTerms: {},                                     /* P8a-2 节气日 → 节气名(当前窗口缓存, 接口未就绪为空表) */
        solarTermKey: ''                                    /* 已请求窗口标识(同窗口不重复请求) */
      };
    },
    computed: {
      todayStr: function () { return fmtD(new Date()); },
      winStart: function () {
        var e = parseD(this.winEnd);
        return e ? fmtD(addDays(e, -6)) : this.todayStr;
      },
      winStartText: function () { return this.winStart; },
      winEndText: function () { return this.winEnd; },
      /* 窗口 7 天日期数组(升序) */
      dates: function () {
        var arr = [], s = parseD(this.winStart);
        if (!s) { s = parseD(this.todayStr); }
        for (var i = 0; i < 7; i++) { arr.push(fmtD(addDays(s, i))); }
        return arr;
      },
      isCurrentWeek: function () { return this.winEnd === this.todayStr; },
      canNext: function () { return this.winEnd < this.todayStr; },
      hasVitals: function () { return !!(this.payload && (this.payload.vitals || []).length); },
      hasData: function () { return !!(this.payload && ((this.payload.vitals || []).length || (this.payload.events || []).length)); }
    },
    watch: {
      inpVisitId: function () {
        this.winEnd = this.todayStr;
        this.load();
      },
      chartType: function (v) { this.localType = v === 'newborn' ? 'newborn' : 'adult'; },
      localType: function () { this.$emit('update:chartType', this.localType); this.draw(); }
    },
    created: function () {
      var vm = this;
      vm.onResize = function () { vm.draw(); };             /* dpr/窗口变化重绘 */
      window.addEventListener('resize', vm.onResize);
    },
    mounted: function () { this.load(); },
    beforeUnmount: function () {
      if (this.onResize) { window.removeEventListener('resize', this.onResize); }
    },
    methods: {
      /* ---------- 数据加载 ---------- */
      load: function () {
        var vm = this;
        vm.loadSolarTerms();                                 /* P8a-2 节气标注: 窗口变化时拉取并缓存 */
        if (!vm.inpVisitId) {
          vm.payload = null; vm.loadError = false; vm.degraded = false;
          vm.draw();
          return;
        }
        vm.loading = true; vm.loadError = false; vm.degraded = false;
        /* P4c-4 临床事件时间轴并行拉取(手动登记+自动触发全量: 入院/手术/转科/分娩/死亡/抢救等),
         * 与 chart-data 内置 events 合并后供事件行绘制; 接口失败静默, 不阻断体温单主区 */
        var reqVisit = vm.inpVisitId;
        HIS.get('/api/his/inp/nursing/clinical-event/list?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .then(function (rows) {
            if (!HIS.sameId(reqVisit, vm.inpVisitId)) { return; }   /* 就诊已切换, 丢弃过期回写 */
            vm.pendingClinicalEvents = rows || [];
            vm.applyClinicalEvents();
          }).catch(function () { /* 临床事件接口未就绪: 事件行仅展示 chart-data 内置事件 */ });
        var url = '/api/his/inp/nursing/vital-sign/chart-data?inpVisitId=' + HIS.idParam(vm.inpVisitId) +
          '&days=7&startDate=' + vm.winStart + '&endDate=' + vm.winEnd;
        HIS.get(url).then(function (data) {
          vm.acceptPayload(data && typeof data === 'object' ? data : {});
        }).catch(function () {
          /* 结构化接口未就绪: 回退旧体温接口(仅体温/脉搏), 保持曲线可用 */
          return HIS.get('/api/his/inp/nursing/temperature/' + HIS.idParam(vm.inpVisitId)).then(function (rows) {
            var vitals = (rows || []).map(function (r) {
              return { recordTime: pick(r, 'time', 'record_time'), temperature: pick(r, 'temperature'), pulse: pick(r, 'pulse') };
            });
            vm.acceptPayload({ vitals: vitals, events: [] });
            vm.degraded = true;
          }).catch(function () {
            vm.acceptPayload({ vitals: [], events: [] });
            vm.loadError = true;
          });
        }).finally(function () { vm.loading = false; });
      },
      acceptPayload: function (data) {
        var vm = this;
        vm.payload = {
          vitals: (data.vitals || []).map(normVital).sort(cmpTs),
          events: data.events || [],
          admitDate: pick(data, 'admitDate', 'admit_date') || '',
          surgeryDates: data.surgeryDates || [],
          allergy: pick(data, 'allergy', 'allergy_drugs') || '',
          skinTest: pick(data, 'skinTest', 'skin_test') || ''
        };
        vm.applyClinicalEvents();                           /* 合并可能已先行到达的临床事件时间轴 */
        vm.$nextTick(function () { vm.draw(); });
      },

      /* ---------- P8a-2: 节气标注(日期轴时令信息) ----------
       * 数据源 GET /api/emr/solar-term?start=&end= → [{date, name}](后端 2020-2035 查表推算);
       * 同窗口仅请求一次(key 防重), 窗口翻页/回本周重新拉取; 接口未就绪静默降级为无标注,
       * 失败不记忆 key(下次 load 重试), 到达后重绘画布在日期文字下方以小字灰色标注节气名。 */
      loadSolarTerms: function () {
        var vm = this;
        var key = vm.winStart + '~' + vm.winEnd;
        if (vm.solarTermKey === key) { return; }
        vm.solarTermKey = key;
        HIS.get('/api/emr/solar-term?start=' + vm.winStart + '&end=' + vm.winEnd)
          .then(function (rows) {
            var map = {};
            (Array.isArray(rows) ? rows : []).forEach(function (r) {
              if (r && r.date) { map[String(r.date).substring(0, 10)] = r.name || ''; }
            });
            vm.solarTerms = map;
            vm.draw();
          }).catch(function () {
            vm.solarTerms = {};
            vm.solarTermKey = '';                             /* 失败不记忆: 下次窗口加载重试 */
          });
      },

      /* ---------- P4c-4: 临床事件时间轴合并(体温单事件行数据增强) ----------
       * 数据源 GET /api/his/inp/nursing/clinical-event/list?inpVisitId= (事件时间升序);
       * 与 chart-data 内置 events 按「事件类型+分钟时刻」去重求并, 归一为 {eventType,eventTime} 形态;
       * 事件行绘制沿用 buildModel/drawEvents(eventTime 映射日期列×时段槽, 类型→EVENT_DEFS 标签/颜色)。 */
      applyClinicalEvents: function () {
        var vm = this;
        if (!vm.payload) { return; }                        /* 主数据未到: 暂存于 pendingClinicalEvents 待合并 */
        var extra = vm.pendingClinicalEvents || [];
        if (!extra.length) { return; }
        var seen = {};
        function key(e) {
          return evKey(pick(e, 'eventType', 'event_type')) + '|' +
            String(pick(e, 'eventTime', 'event_time') || '').replace('T', ' ').substring(0, 16);
        }
        var merged = (vm.payload.events || []).slice();
        merged.forEach(function (e) { seen[key(e)] = 1; });
        extra.forEach(function (e) {
          var k = key(e);
          if (k !== '|' && !seen[k]) {
            merged.push({ eventType: pick(e, 'eventType', 'event_type'), eventTime: pick(e, 'eventTime', 'event_time') });
            seen[k] = 1;
          }
        });
        vm.payload.events = merged;
        vm.draw();
      },

      /* ---------- 窗口导航 ---------- */
      prevWeek: function () {
        var e = parseD(this.winEnd);
        if (e) { this.winEnd = fmtD(addDays(e, -7)); this.load(); }
      },
      nextWeek: function () {
        if (!this.canNext) { return; }
        var e = parseD(this.winEnd);
        if (e) { this.winEnd = fmtD(addDays(e, 7)); this.load(); }
      },
      goToday: function () { this.winEnd = this.todayStr; this.load(); },

      /* ---------- 模型(把 vitals/events 归并到 42 列) ---------- */
      buildModel: function () {
        var vm = this;
        var dates = vm.dates;
        var dayIdx = {};
        dates.forEach(function (d, i) { dayIdx[d] = i; });
        var cells = {};                                     /* ord(0-41) → 该列最新 vital(缺失字段回填) */
        var events = {};                                    /* ord → [{label,color}] */
        var breakTs = [];                                   /* 死亡/出院事件毫秒时刻(曲线断线判定) */
        (vm.payload ? vm.payload.vitals : []).forEach(function (v) {
          var ts = tsOf(v.recordTime);
          if (!ts || dayIdx[ts.day] === undefined) { return; }
          var ord = dayIdx[ts.day] * 6 + slotOfHour(ts.hour);
          var old = cells[ord];
          if (old) {
            /* 同列多笔(如 06:00 测体征 + 07:00 补出入量): 新记录整体优先,
             * 新记录为空的字段由旧记录回填, 避免体温点被出入量补录抹掉 */
            ['temperature', 'tempType', 'pulse', 'respiration', 'systolicBp', 'diastolicBp',
              'mewsScore', 'stoolCount', 'urineMl', 'drainMl', 'weight'].forEach(function (k) {
              if (old[k] != null && v[k] == null) { v[k] = old[k]; }
            });
          }
          cells[ord] = v;
        });
        (vm.payload ? vm.payload.events : []).forEach(function (e) {
          var ts = tsOf(pick(e, 'eventTime', 'event_time'));
          var def = EVENT_DEFS[evKey(pick(e, 'eventType', 'event_type'))];
          if (!ts || !def || dayIdx[ts.day] === undefined) { return; }
          var ord = dayIdx[ts.day] * 6 + slotOfHour(ts.hour);
          (events[ord] = events[ord] || []).push({ label: def.label, color: def.color });
          var k = evKey(pick(e, 'eventType', 'event_type'));
          if (k === 'death' || k === 'discharge') {
            var bts = parseTs(pick(e, 'eventTime', 'event_time'));
            if (bts != null) { breakTs.push(bts); }
          }
        });
        var ords = Object.keys(cells).map(Number).sort(function (a, b) { return a - b; });
        /* 住院/术后天数行所需的"相对窗口首日"天数 */
        var d0 = parseD(dates[0]);
        var admitOrd = null, surgOrd = null;
        if (vm.payload && vm.payload.admitDate) {
          var ad = parseD(vm.payload.admitDate);
          if (ad) { admitOrd = dayDiff(ad, d0); }
        }
        var sd = (vm.payload && vm.payload.surgeryDates || []).filter(Boolean);
        if (sd.length) {
          var sdd = parseD(sd[sd.length - 1]);
          if (sdd) { surgOrd = dayDiff(sdd, d0); }
        }
        return {
          dates: dates, cells: cells, ords: ords, events: events, breakTs: breakTs,
          admitOrd: admitOrd, surgOrd: surgOrd,
          allergy: (vm.payload && vm.payload.allergy) || '',
          skinTest: (vm.payload && vm.payload.skinTest) || ''
        };
      },

      /* ---------- 布局度量 ---------- */
      layoutOf: function () {
        var mode = this.localType === 'newborn' ? MODES.newborn : MODES.adult;
        var hasSurg = !!(this.payload && (this.payload.surgeryDates || []).filter(Boolean).length);
        var y = 8;
        var rows = [];
        function row(id, h) { rows.push({ id: id, y: y, h: h }); y += h; }
        row('title', 30);
        row('date', 24);
        row('days', 20);
        if (hasSurg) { row('surg', 20); }
        row('time', 22);
        row('event', 26);
        var gridY0 = y, gridH = 400, gridY1 = gridY0 + gridH;
        var rowH = 24;
        var footTop = gridY1;
        var footRows = FOOTER_ROWS.map(function (r, i) {
          return { key: r.key, label: r.label, special: r.special || null, get: r.get || null, y: footTop + i * rowH };
        });
        var H = footTop + FOOTER_ROWS.length * rowH + 12;
        return {
          mode: mode, H: H, rowH: rowH,
          x0: LEFT, x1: LOGIC_W - RIGHT, colW: (LOGIC_W - LEFT - RIGHT) / COLS,
          rows: rows, gridY0: gridY0, gridY1: gridY1, gridH: gridH,
          footTop: footTop, footBottom: footTop + FOOTER_ROWS.length * rowH, footRows: footRows,
          headerTop: rows[0].y + rows[0].h
        };
      },
      rowById: function (L, id) {
        for (var i = 0; i < L.rows.length; i++) { if (L.rows[i].id === id) { return L.rows[i]; } }
        return null;
      },
      /* ---------- 坐标换算 ---------- */
      colX: function (L, ord) { return L.x0 + (ord + 0.5) * L.colW; },
      dayCx: function (L, i) { return L.x0 + (i * 6 + 3) * L.colW; },
      tempY: function (L, t) {
        var m = L.mode;
        var tc = Math.max(m.tempMin, Math.min(m.tempMax, t));
        return L.gridY0 + ((m.tempMax - tc) / (m.tempMax - m.tempMin)) * L.gridH;
      },
      /* 脉搏与体温共轴: 1℃ = bpmPerC 次/分, 越界钳制 */
      pulseY: function (L, p) {
        var m = L.mode;
        var pc = Math.max(m.pulseMin, Math.min(m.pulseMax, p));
        return this.tempY(L, m.tempMin + (pc - m.pulseMin) / m.bpmPerC);
      },
      /* 呼吸映射主区下带 1℃ 区间(视觉位于脉搏下方): 0-40 次/分 → tempMin..tempMin+1 */
      respY: function (L, r) {
        var rc = Math.max(0, Math.min(40, r));
        return this.tempY(L, L.mode.tempMin + rc / 40);
      },
      /* 两数据点之间是否隔有死亡/出院事件(按毫秒时刻严格界定, 同列先后也能区分) */
      crossesBreak: function (M, tsA, tsB) {
        var lo = Math.min(tsA, tsB), hi = Math.max(tsA, tsB);
        for (var i = 0; i < M.breakTs.length; i++) {
          if (M.breakTs[i] > lo && M.breakTs[i] < hi) { return true; }
        }
        return false;
      },
      /* 序列归并: 相邻有值列连成段, 缺值/断线事件处截断; 返回 {segs, pts} */
      collectSeries: function (M, L, getVal, getY) {
        var vm = this;
        var segs = [], seg = [], pts = [];
        function flush() { if (seg.length > 1) { segs.push(seg); } seg = []; }
        M.ords.forEach(function (ord) {
          var v = M.cells[ord];
          var val = getVal(v);
          if (val == null || !isFinite(val)) { flush(); return; }
          if (seg.length && vm.crossesBreak(M, seg[seg.length - 1].ts, v.tsMs)) { flush(); }
          var pt = { ord: ord, val: val, v: v, ts: v.tsMs, x: vm.colX(L, ord), y: getY(val) };
          seg.push(pt);
          pts.push(pt);
        });
        flush();
        return { segs: segs, pts: pts };
      },
      polyline: function (ctx, seg, color, width, dash) {
        ctx.save();
        if (dash) { ctx.setLineDash(dash); }
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.moveTo(seg[0].x, seg[0].y);
        for (var i = 1; i < seg.length; i++) { ctx.lineTo(seg[i].x, seg[i].y); }
        ctx.stroke();
        ctx.restore();
      },

      /* ---------- 绘制: 数据点符号 ---------- */
      drawTempPoint: function (ctx, x, y, type) {
        ctx.strokeStyle = TEMP_COLOR;
        ctx.fillStyle = TEMP_COLOR;
        ctx.lineWidth = 1.6;
        ctx.lineCap = 'round';
        var t = Number(type) || 1;
        if (t === 2) {                                      /* 腋温 × */
          ctx.beginPath();
          ctx.moveTo(x - 3.4, y - 3.4); ctx.lineTo(x + 3.4, y + 3.4);
          ctx.moveTo(x + 3.4, y - 3.4); ctx.lineTo(x - 3.4, y + 3.4);
          ctx.stroke();
        } else if (t === 3) {                               /* 肛温 ○ 空心圆 */
          ctx.beginPath(); ctx.arc(x, y, 3.2, 0, Math.PI * 2);
          ctx.fillStyle = '#ffffff'; ctx.fill();
          ctx.strokeStyle = TEMP_COLOR; ctx.stroke();
        } else if (t === 4) {                               /* 耳温 △ 实心三角 */
          ctx.beginPath();
          ctx.moveTo(x, y - 4.2); ctx.lineTo(x + 3.9, y + 2.9); ctx.lineTo(x - 3.9, y + 2.9);
          ctx.closePath(); ctx.fill();
        } else {                                            /* 口温 ● 实心圆 */
          ctx.beginPath(); ctx.arc(x, y, 3.2, 0, Math.PI * 2); ctx.fill();
        }
      },
      drawPulsePoint: function (ctx, x, y) {
        ctx.fillStyle = PULSE_COLOR;
        ctx.beginPath(); ctx.arc(x, y, 2.8, 0, Math.PI * 2); ctx.fill();
      },
      drawRespPoint: function (ctx, x, y) {
        ctx.strokeStyle = RESP_COLOR;
        ctx.fillStyle = '#ffffff';
        ctx.lineWidth = 1.3;
        ctx.beginPath(); ctx.arc(x, y, 2.6, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      },

      /* ---------- 绘制: 结构分区 ---------- */
      drawPaper: function (ctx, L) {
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, LOGIC_W, L.H);
        ctx.strokeStyle = INK;
        ctx.lineWidth = 1.2;
        ctx.strokeRect(0.5, 0.5, LOGIC_W - 1, L.H - 1);
      },
      drawTitle: function (ctx, L) {
        var r = this.rowById(L, 'title');
        ctx.textBaseline = 'middle';
        ctx.fillStyle = TEXT_MAIN;
        ctx.font = 'bold 16px "Microsoft YaHei", "PingFang SC", sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('体 温 单', LOGIC_W / 2, r.y + r.h / 2 + 1);
        ctx.font = '10px Consolas, monospace';
        ctx.fillStyle = '#90A4AE';
        ctx.textAlign = 'left';
        ctx.fillText('VITAL-SIGN CHART', L.x0, r.y + r.h / 2 - 4);
        ctx.textAlign = 'right';
        ctx.fillText(L.mode.label + ' · 7天/页', L.x1, r.y + r.h / 2 - 4);
        /* 标题下双线(医疗文书版式) */
        var y1 = r.y + r.h - 3;
        hline(ctx, L.x0, y1, L.x1, INK, 1.4);
        hline(ctx, L.x0, y1 + 2.5, L.x1, INK, 0.5);
      },
      drawHeader: function (ctx, M, L) {
        var vm = this;
        var rDate = this.rowById(L, 'date');
        var rDays = this.rowById(L, 'days');
        var rSurg = this.rowById(L, 'surg');
        var rTime = this.rowById(L, 'time');
        ctx.textBaseline = 'middle';
        /* 日期行(每天居中跨 6 列); P8a-2: 窗口含节气日时日期文字上移半行,
         * 当日在日期文字下方以小字灰色标注节气名(无节气窗口保持居中版式) */
        var termMap = vm.solarTerms || {};
        var hasTerm = false;
        M.dates.forEach(function (d) { if (termMap[d]) { hasTerm = true; } });
        ctx.fillStyle = TEXT_MAIN;
        ctx.font = '11px Consolas, "Courier New", monospace';
        ctx.textAlign = 'center';
        M.dates.forEach(function (d, i) {
          var cx = vm.dayCx(L, i);
          var term = termMap[d];
          if (hasTerm) {
            ctx.fillText(d, cx, rDate.y + 8.5);
            if (term) {
              ctx.font = '9px "Microsoft YaHei", sans-serif';
              ctx.fillStyle = TEXT_SUB;
              ctx.fillText(term, cx, rDate.y + 18.5);
              ctx.font = '11px Consolas, "Courier New", monospace';
              ctx.fillStyle = TEXT_MAIN;
            }
          } else {
            ctx.fillText(d, cx, rDate.y + rDate.h / 2);
          }
        });
        /* 行标签(框外左轴区, 右对齐) */
        ctx.font = '10.5px "Microsoft YaHei", sans-serif';
        ctx.fillStyle = TEXT_SUB;
        ctx.textAlign = 'right';
        ctx.fillText('住院天数', L.x0 - 6, rDays.y + rDays.h / 2);
        if (rSurg) { ctx.fillText('术后天数', L.x0 - 6, rSurg.y + rSurg.h / 2); }
        ctx.fillText('时间', L.x0 - 6, rTime.y + rTime.h / 2);
        /* 住院天数(入院日=第1天; 窗口早于入院日留空) */
        if (M.admitOrd != null) {
          ctx.fillStyle = TEXT_MAIN;
          ctx.textAlign = 'center';
          ctx.font = '10.5px Consolas, monospace';
          M.dates.forEach(function (d, i) {
            var n = i - M.admitOrd + 1;
            if (n >= 1) { ctx.fillText(String(n), vm.dayCx(L, i), rDays.y + rDays.h / 2); }
          });
        }
        /* 术后天数(手术当日=第0天) */
        if (rSurg && M.surgOrd != null) {
          ctx.fillStyle = TEXT_MAIN;
          ctx.textAlign = 'center';
          ctx.font = '10.5px Consolas, monospace';
          M.dates.forEach(function (d, i) {
            var n = i - M.surgOrd;
            if (n >= 0) { ctx.fillText(String(n), vm.dayCx(L, i), rSurg.y + rSurg.h / 2); }
          });
        }
        /* 时段行(42 列 2/6/10/14/18/22) */
        ctx.fillStyle = TEXT_MAIN;
        ctx.font = '10.5px Consolas, monospace';
        ctx.textAlign = 'center';
        for (var s = 0; s < COLS; s++) {
          ctx.fillText(String(SLOTS[s % 6]), vm.colX(L, s), rTime.y + rTime.h / 2);
        }
        /* 分隔线: 各行底线(手术行底=时间行顶不重复画) */
        [rDate, rDays, rSurg, rTime].forEach(function (r) {
          if (r) { hline(ctx, L.x0, r.y + r.h, L.x1, INK_LIGHT, 1); }
        });
        if (rTime) { hline(ctx, L.x0, rTime.y, L.x1, INK_LIGHT, 1); }
        /* 天分隔竖线(标题底 → 网格顶, 覆盖事件行) */
        for (var i = 0; i <= 7; i++) {
          var x = L.x0 + i * 6 * L.colW;
          vline(ctx, x, L.headerTop, L.gridY0, (i === 0 || i === 7) ? INK_SOFT : INK_LIGHT, 1);
        }
        /* header 区外框 */
        ctx.strokeStyle = INK;
        ctx.lineWidth = 1.2;
        ctx.strokeRect(L.x0, L.headerTop, L.x1 - L.x0, L.gridY0 - L.headerTop);
      },
      drawEvents: function (ctx, M, L) {
        var rEv = this.rowById(L, 'event');
        if (!rEv) { return; }
        var vm = this;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'center';
        ctx.font = 'bold 10.5px "Microsoft YaHei", sans-serif';
        Object.keys(M.events).map(Number).forEach(function (ord) {
          var list = M.events[ord];
          var x = vm.colX(L, ord);
          for (var k = 0; k < list.length && k < 2; k++) {
            ctx.fillStyle = list[k].color;
            ctx.fillText(list[k].label, x, rEv.y + 9 + k * 12);
          }
          if (list.length > 2) {                            /* 同列超过 2 个事件以 +N 提示 */
            ctx.fillStyle = TEXT_SUB;
            ctx.fillText('+' + (list.length - 2), x, rEv.y + 9 + 24);
          }
        });
      },
      drawGrid: function (ctx, M, L) {
        var vm = this;
        var m = L.mode;
        var rows = Math.round((m.tempMax - m.tempMin) / 0.2);
        /* 1) MEWS≥5 预警列淡红背景(网格线之下) */
        ctx.fillStyle = 'rgba(229, 57, 53, 0.10)';
        M.ords.forEach(function (ord) {
          if (Number(M.cells[ord].mewsScore) >= 5) {
            ctx.fillRect(L.x0 + ord * L.colW, L.gridY0, L.colW, L.gridH);
          }
        });
        /* 2) 水平线: 0.2℃ 细线, 整度线加深, 37℃ 浅红 */
        for (var k = 0; k <= rows; k++) {
          var y = L.gridY0 + (L.gridH * k) / rows;
          var t = Math.round((m.tempMax - k * 0.2) * 10) / 10;
          var whole = Math.abs(t - Math.round(t)) < 1e-9;
          if (whole && t === 37) {
            hline(ctx, L.x0, y, L.x1, GRID_37, 1.4);
          } else {
            hline(ctx, L.x0, y, L.x1, whole ? INK_SOFT : GRID_COLOR, whole ? 1 : 0.6);
          }
        }
        /* 3) 竖线: 列间细线, 天边界加深 */
        for (var i = 0; i <= COLS; i++) {
          var x = L.x0 + i * L.colW;
          vline(ctx, x, L.gridY0, L.gridY1, (i % 6 === 0) ? INK_SOFT : GRID_COLOR, (i % 6 === 0) ? 1 : 0.6);
        }
        /* 4) 外框 */
        ctx.strokeStyle = INK;
        ctx.lineWidth = 1.4;
        ctx.strokeRect(L.x0, L.gridY0, L.x1 - L.x0, L.gridH);
        /* 5) 左轴温度刻度(整度) */
        ctx.textBaseline = 'middle';
        ctx.fillStyle = TEXT_MAIN;
        ctx.font = '11px Consolas, monospace';
        ctx.textAlign = 'center';
        for (var t2 = m.tempMin; t2 <= m.tempMax; t2++) {
          ctx.fillText(String(t2), (L.x0 + 6) / 2 + 2, vm.tempY(L, t2));
        }
        /* 6) 右轴脉搏刻度(1℃ = bpmPerC 次/分, 与体温共轴) */
        ctx.fillStyle = PULSE_COLOR;
        for (var t3 = m.tempMin; t3 <= m.tempMax; t3++) {
          var bpm = m.pulseMin + (t3 - m.tempMin) * m.bpmPerC;
          ctx.fillText(String(bpm), (L.x1 + LOGIC_W - 6) / 2, vm.tempY(L, t3));
        }
      },
      drawBP: function (ctx, M, L) {
        var vm = this;
        M.ords.forEach(function (ord) {
          var v = M.cells[ord];
          if (v.systolicBp == null && v.diastolicBp == null) { return; }
          var x = vm.colX(L, ord);
          var yTop = vm.pulseY(L, v.systolicBp != null ? v.systolicBp : v.diastolicBp);
          var yBot = vm.pulseY(L, v.diastolicBp != null ? v.diastolicBp : v.systolicBp);
          ctx.strokeStyle = BP_COLOR;
          ctx.lineCap = 'butt';
          ctx.lineWidth = 2;
          ctx.beginPath(); ctx.moveTo(x, yTop); ctx.lineTo(x, yBot); ctx.stroke();
          ctx.lineWidth = 1.2;
          ctx.beginPath(); ctx.moveTo(x - 3, yTop); ctx.lineTo(x + 3, yTop); ctx.stroke();
          ctx.beginPath(); ctx.moveTo(x - 3, yBot); ctx.lineTo(x + 3, yBot); ctx.stroke();
        });
      },
      drawRespiration: function (ctx, M, L) {
        var vm = this;
        var s = vm.collectSeries(M, L, function (v) { return v.respiration; }, function (r) { return vm.respY(L, r); });
        s.segs.forEach(function (seg) { vm.polyline(ctx, seg, RESP_COLOR, 1.2, [5, 3]); });
        s.pts.forEach(function (p) { vm.drawRespPoint(ctx, p.x, p.y); });
      },
      drawTemperature: function (ctx, M, L) {
        var vm = this;
        var s = vm.collectSeries(M, L, function (v) { return v.temperature; }, function (t) { return vm.tempY(L, t); });
        /* 体温≥39℃ 点位橙色高亮区 */
        s.pts.forEach(function (p) {
          if (p.val >= 39) {
            ctx.fillStyle = 'rgba(255, 152, 0, 0.22)';
            ctx.beginPath(); ctx.arc(p.x, p.y, 10, 0, Math.PI * 2); ctx.fill();
          }
        });
        s.segs.forEach(function (seg) { vm.polyline(ctx, seg, TEMP_COLOR, 1.6); });
        s.pts.forEach(function (p) { vm.drawTempPoint(ctx, p.x, p.y, p.v.tempType); });
      },
      drawPulse: function (ctx, M, L) {
        var vm = this;
        var s = vm.collectSeries(M, L, function (v) { return v.pulse; }, function (p) { return vm.pulseY(L, p); });
        s.segs.forEach(function (seg) { vm.polyline(ctx, seg, PULSE_COLOR, 1.4); });
        s.pts.forEach(function (p) { vm.drawPulsePoint(ctx, p.x, p.y); });
      },
      drawFooter: function (ctx, M, L) {
        var vm = this;
        ctx.textBaseline = 'middle';
        L.footRows.forEach(function (row) {
          var yc = row.y + L.rowH / 2;
          /* 行分隔横线 */
          hline(ctx, L.x0, row.y, L.x1, INK_LIGHT, 1);
          /* 左标签(框外右对齐) */
          ctx.fillStyle = TEXT_SUB;
          ctx.font = '11px "Microsoft YaHei", sans-serif';
          ctx.textAlign = 'right';
          ctx.fillText(row.label, L.x0 - 6, yc);
          /* 过敏药物/皮试: 文本行(过敏通常入院首日评估, 显示在窗口首列起) */
          if (row.special === 'allergy') {
            var al = String(M.allergy || '').trim();
            if (al) {
              ctx.fillStyle = '#C62828';
              ctx.textAlign = 'left';
              ctx.font = '10.5px "Microsoft YaHei", sans-serif';
              ctx.fillText(al.substring(0, 40), L.x0 + 4, yc);
            }
            return;
          }
          if (row.special === 'skinTest') {
            var sk = String(M.skinTest || '').trim();
            if (sk) {
              ctx.fillStyle = TEXT_MAIN;
              ctx.textAlign = 'left';
              ctx.font = '10.5px "Microsoft YaHei", sans-serif';
              ctx.fillText(sk.substring(0, 40), L.x0 + 4, yc);
            }
            return;
          }
          /* 数值行: 该列归并记录取字段 */
          ctx.fillStyle = TEXT_MAIN;
          ctx.font = '10.5px Consolas, monospace';
          ctx.textAlign = 'center';
          M.ords.forEach(function (ord) {
            var text = row.get(M.cells[ord]);
            if (text == null) { return; }
            ctx.fillText(String(text), vm.colX(L, ord), yc);
          });
        });
        /* 页脚底线/天分隔/外框 */
        hline(ctx, L.x0, L.footBottom, L.x1, INK_LIGHT, 1);
        for (var i = 0; i <= 7; i++) {
          var x = L.x0 + i * 6 * L.colW;
          vline(ctx, x, L.footTop, L.footBottom, (i === 0 || i === 7) ? INK_SOFT : INK_LIGHT, 1);
        }
        ctx.strokeStyle = INK;
        ctx.lineWidth = 1.2;
        ctx.strokeRect(L.x0, L.footTop, L.x1 - L.x0, L.footBottom - L.footTop);
      },

      /* ---------- 主渲染入口 ---------- */
      paint: function (ctx, M, L) {
        this.drawPaper(ctx, L);
        this.drawTitle(ctx, L);
        this.drawHeader(ctx, M, L);
        this.drawEvents(ctx, M, L);
        this.drawGrid(ctx, M, L);
        this.drawBP(ctx, M, L);
        this.drawRespiration(ctx, M, L);
        this.drawTemperature(ctx, M, L);
        this.drawPulse(ctx, M, L);
        this.drawFooter(ctx, M, L);
      },
      draw: function () {
        var vm = this;
        var c = vm.$refs.chartCanvas;
        if (!c) { return; }
        var M = vm.buildModel();
        var L = vm.layoutOf();
        var dpr = Math.min(3, Math.max(1, window.devicePixelRatio || 1));
        var bw = Math.round(LOGIC_W * dpr), bh = Math.round(L.H * dpr);
        if (c.width !== bw || c.height !== bh) { c.width = bw; c.height = bh; }
        c.style.width = LOGIC_W + 'px';
        c.style.height = L.H + 'px';
        var ctx = c.getContext('2d');
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.clearRect(0, 0, c.width, c.height);
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, c.width, c.height);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        vm.paint(ctx, M, L);
        ctx.setTransform(1, 0, 0, 1, 0, 0);
      },
      /* 离屏按指定 dpr 整图重绘(打印输出用, 不触碰显示画布) */
      renderToCanvas: function (dpr) {
        var vm = this;
        var M = vm.buildModel();
        var L = vm.layoutOf();
        var c = document.createElement('canvas');
        c.width = Math.round(LOGIC_W * dpr);
        c.height = Math.round(L.H * dpr);
        var ctx = c.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, c.width, c.height);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        vm.paint(ctx, M, L);
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        return c;
      },
      /* ---------- 打印: ≥2x 离屏重绘 → PNG 新窗横版打印 ---------- */
      printChart: function () {
        var vm = this;
        if (!vm.$refs.chartCanvas) { return; }
        var dpr = Math.max(2, window.devicePixelRatio || 1);
        var url;
        try {
          url = vm.renderToCanvas(dpr).toDataURL('image/png');
        } catch (e) {
          ElementPlus.ElMessage.error('体温单导出失败: ' + (e && e.message ? e.message : '未知错误'));
          return;
        }
        var win = window.open('', '_blank', 'width=1280,height=920');
        if (!win) {
          ElementPlus.ElMessage.warning('浏览器拦截了打印窗口, 请允许本站弹窗后重试');
          return;
        }
        var title = '体温单_' + vm.winStart + '_至_' + vm.winEnd;
        var titleEsc = esc(title);
        win.document.write([
          '<!DOCTYPE html><html><head><meta charset="utf-8"><title>', titleEsc, '</title>',
          '<style>',
          'body { margin:0; font-family:"Microsoft YaHei",sans-serif; background:#fff; }',
          '.hd { text-align:center; font-size:13px; color:#555; padding:8px 0 4px; }',
          'img { display:block; width:100%; max-width:1240px; margin:0 auto; }',
          '@media print { .hd { display:none; } @page { size:landscape; margin:8mm; } }',
          '</style></head><body>',
          '<div class="hd">', titleEsc, '（横版打印）</div>',
          '<img src="', url, '" onload="setTimeout(function(){window.focus();window.print();},150)">',
          '</body></html>'
        ].join(''));
        win.document.close();
      }
    },
    template: [
      '<div class="vsc-root">',
      '  <div class="vsc-toolbar">',
      '    <el-button size="small" @click="prevWeek">← 前7天</el-button>',
      '    <span class="vsc-range">{{ winStartText }} ~ {{ winEndText }}</span>',
      '    <el-button size="small" :disabled="!canNext" @click="nextWeek">后7天 →</el-button>',
      '    <el-button v-if="!isCurrentWeek" size="small" text type="primary" @click="goToday">回到本周</el-button>',
      '    <span class="vsc-spacer"></span>',
      '    <el-radio-group v-model="localType" size="small">',
      '      <el-radio-button label="adult">成人</el-radio-button>',
      '      <el-radio-button label="newborn">新生儿</el-radio-button>',
      '    </el-radio-group>',
      '    <el-button size="small" type="primary" plain @click="printChart">打印体温单</el-button>',
      '  </div>',
      '  <div class="vsc-scroll" v-loading="loading">',
      '    <canvas ref="chartCanvas" class="vsc-canvas"></canvas>',
      '  </div>',
      '  <div class="vsc-legend">',
      '    <span><i class="vsc-line" style="border-color:' + TEMP_COLOR + '"></i>体温 <b>●</b>口温 <b>×</b>腋温 <b>○</b>肛温 <b>△</b>耳温</span>',
      '    <span><i class="vsc-line" style="border-color:' + PULSE_COLOR + '"></i><i class="vsc-dot" style="background:' + PULSE_COLOR + '"></i>脉搏</span>',
      '    <span><i class="vsc-line vsc-dash" style="border-color:' + RESP_COLOR + '"></i>呼吸</span>',
      '    <span><i class="vsc-bp" style="background:' + BP_COLOR + '"></i>血压(收缩压↑/舒张压↓)</span>',
      '    <span><i class="vsc-block"></i>MEWS≥5 预警列</span>',
      '    <span>体温≥39℃ 高亮 · 37℃ 红线 · <b>红字</b>事件标记</span>',
      '    <span>节气以日期下方小字标注</span>',
      '  </div>',
      '  <div class="vsc-tip" v-if="loadError">体温单数据接口暂不可用，已展示空白标准格式（可打印留档）。请确认后端 /api/his/inp/nursing/vital-sign/chart-data 已部署。</div>',
      '  <div class="vsc-tip" v-else-if="degraded">结构化生命体征接口暂未就绪，已按旧体温接口降级展示体温/脉搏曲线（呼吸、血压、出入量等缺失）。</div>',
      '  <div class="vsc-tip" v-else-if="!loading && !hasData">该 7 天窗口暂无生命体征记录，空白标准格式可直接打印留档。</div>',
      '</div>'
    ].join('\n')
  };

  /* ================= 对外注册 ================= */
  HIS.components.InpVitalSignChart = InpVitalSignChart;
  /* 全局标签注册: app 就绪后挂 <dw-inp-vital-sign-chart>(脚本先于 app.js 加载, 故延至 DOMContentLoaded;
   * P4a-5 集成后 inp-nurse.js 亦可经 HIS.components 局部声明, 局部声明优先于全局标签) */
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-vital-sign-chart', InpVitalSignChart); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})();
