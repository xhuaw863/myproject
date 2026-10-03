/* 病历质控工作台(P5b-3): HIS.views.EmrQualityConsole —— 左侧六模块导航 + 右侧内容区的一体化质控驾驶舱。
 * 模块: 1评分标准维护(GET /score-standards + PUT /score-standard/{id}) 2时效规则维护(rules ruleType=2 + 超时/临近超时清单)
 *       3内涵规则维护(rules rule_category 五类过滤 + 弹窗编辑) 4病历查询(质控专用预估端点 + 缺陷/等级详情 + 重评分)
 *       5人工质控(抽检/队列/审核工作台(Tiptap只读+缺陷卡片)/整改跟踪) 6统计分析(KPI/等级分布/缺陷分布/排行/环比/CSV导出)。
 * 后端契约(已就绪): /api/his/emr/quality/*(EmrQualityController) | /api/his/emr/timeliness/*(EmrTimelinessController)
 *                   | /api/his/inp/record/{id}(病历详情, 审核工作台全文渲染)。
 * 预估端点(P5b-2 Felix 交付后对齐): /api/his/emr/manual-qc/*(sample/assign/review/defect/notice 系列按任务约定路径硬编码)
 *                   及 GET /quality/records(质控病历查询)/GET /quality/doctor-ranking(医生排行)。
 * 权限: 评分标准与规则写操作后端 requireLeadOrg, 前端按 HIS.isLead() 禁用; 人工质控/抽检面向质控员(本机构写档位)。
 * 评分语义: 100分起扣, 甲≥90/乙70-89/丙<70, severity=3 一票否决直接丙。注册: HIS.views.EmrQualityConsole(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 常量 ================= */
  var QP = '/api/his/emr/quality';
  var TL = '/api/his/emr/timeliness';
  var MQ = '/api/his/emr/manual-qc';

  var NAVS = [
    { key: 'standard', icon: '📋', name: '评分标准', sub: '五类标准·权重维护' },
    { key: 'timeliness', icon: '⏱️', name: '时效规则', sub: '23项时限·超时预警' },
    { key: 'content', icon: '🧠', name: '内涵规则', sub: '五大类内涵质控' },
    { key: 'query', icon: '🔍', name: '病历查询', sub: '评分/等级/缺陷检索' },
    { key: 'manual', icon: '✒️', name: '人工质控', sub: '抽检·审核·整改跟踪' },
    { key: 'stats', icon: '📊', name: '统计分析', sub: 'KPI·排行·月度环比' }
  ];

  var RECORD_TYPES = [
    { v: 1, l: '1-入院记录' }, { v: 2, l: '2-首次病程' }, { v: 3, l: '3-日常病程' }, { v: 4, l: '4-上级查房' },
    { v: 5, l: '5-术前小结' }, { v: 6, l: '6-手术记录' }, { v: 7, l: '7-术后病程' }, { v: 8, l: '8-出院小结' }, { v: 9, l: '9-死亡记录' }
  ];
  var SEVERITIES = [{ v: 1, l: '1-提醒' }, { v: 2, l: '2-扣分' }, { v: 3, l: '3-一票否决' }];
  var CONTROL_LEVELS = [{ v: 1, l: '提醒' }, { v: 2, l: '拦截' }, { v: 3, l: '禁止' }];
  var QC_STAGES = [{ v: 0, l: '通用' }, { v: 1, l: '运行(签名前)' }, { v: 2, l: '归档' }];
  var CONTENT_CATS = [
    { v: 'item_value', l: '取值符合' }, { v: 'item_compare', l: '项目对比' },
    { v: 'disease', l: '病种关联' }, { v: 'calculation', l: '计算校验' }, { v: 'event', l: '事件时序' }
  ];
  var STD_CATS = ['时效', '完整', '逻辑', '规范', '内涵'];
  var GRADES = ['甲', '乙', '丙'];
  /* 整改通知状态机(预估, 与 MrManualQcController 交付对齐): 0待整改 1已整改待复核 2复核通过 3已驳回 4申诉中 5申诉处理完毕 */
  var NOTICE_STATUS = {
    0: { l: '待整改', t: 'warning' }, 1: { l: '已整改待复核', t: 'primary' }, 2: { l: '复核通过', t: 'success' },
    3: { l: '已驳回', t: 'danger' }, 4: { l: '申诉中', t: 'warning' }, 5: { l: '申诉处理完毕', t: 'info' }
  };
  var DEFECT_STATUS = { 0: { l: '未整改', t: 'danger' }, 1: { l: '已整改', t: 'success' }, 2: { l: '已申诉', t: 'warning' }, 3: { l: '申诉驳回', t: 'info' }, 4: { l: '豁免', t: 'info' } };
  /* 人工质控队列状态(预估): 0待审核 1审核中 2已完成 */
  var QUEUE_STATUS = { 0: { l: '待审核', t: 'info' }, 1: { l: '审核中', t: 'warning' }, 2: { l: '已完成', t: 'success' } };

  /* ================= 工具 ================= */
  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function currentMonth() { var d = new Date(); return d.getFullYear() + '-' + pad2(d.getMonth() + 1); }
  function monthRange(m) {
    var y = Number(String(m).slice(0, 4)), mo = Number(String(m).slice(5, 7));
    var last = new Date(y, mo, 0).getDate();
    return [m + '-01', m + '-' + pad2(last)];
  }
  function prevMonth(m) {
    var y = Number(String(m).slice(0, 4)), mo = Number(String(m).slice(5, 7)) - 1;
    if (mo <= 0) { y -= 1; mo = 12; }
    return y + '-' + pad2(mo);
  }
  function text(v) { return v == null ? '' : String(v); }
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function num(v, dft) { var n = Number(v); return isNaN(n) ? (dft == null ? null : dft) : n; }
  /* 时限性规则 rule_config → 可读时限(cfg.hours/cfg.days) */
  function parseDeadline(cfgStr) {
    var out = [];
    try {
      var cfg = JSON.parse(cfgStr || '{}');
      if (cfg.days != null) { out.push(cfg.days + '天'); }
      if (cfg.hours != null) { out.push(cfg.hours + '小时'); }
      if (!out.length && cfg.max != null) { out.push(cfg.max); }
    } catch (e) { return '-'; }
    return out.length ? out.join(' ') : '-';
  }
  function ruleCatName(c) {
    for (var i = 0; i < CONTENT_CATS.length; i++) { if (CONTENT_CATS[i].v === c) { return CONTENT_CATS[i].l; } }
    return c || '-';
  }
  function labelOf(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === Number(v)) { return list[i].l; } }
    return '-';
  }
  function typeName(t) { return labelOf(RECORD_TYPES, t); }
  /* 病历 content 字段 → Tiptap document(JSON/HTML/纯文本三态) */
  function parseDocRaw(raw) {
    var s = String(raw == null ? '' : raw).trim();
    if (!s) { return { type: 'doc', content: [{ type: 'paragraph' }] }; }
    if (s.charAt(0) === '{') {
      try {
        var o = JSON.parse(s);
        if (o && o.type === 'doc' && o.content) { return o; }
        if (o && o.content) { return { type: 'doc', content: o.content }; }
      } catch (e) { /* 落纯文本 */ }
      return { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: s }] }] };
    }
    return s; /* HTML 字符串 createEditor 原生支持 */
  }
  /* content 任意形态 → 纯文本(Tiptap 不可用时的兜底渲染) */
  function extractPlainText(raw) {
    var s = String(raw == null ? '' : raw).trim();
    if (s.charAt(0) === '{') {
      try {
        var out = [];
        (function walk(o) {
          if (Array.isArray(o)) { o.forEach(walk); return; }
          if (o && typeof o === 'object') {
            if (typeof o.text === 'string') { out.push(o.text); }
            if (typeof o.content !== 'undefined') { walk(o.content); }
          }
        }(JSON.parse(s)));
        if (out.length) { return out.join('\n'); }
      } catch (e) { /* 原样 */ }
    }
    return s.replace(/<[^>]+>/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  }
  /* CSV 组装(逗号/引号/换行转义 + BOM 保证 Excel 中文不乱码) */
  function toCsv(headers, rows) {
    function cell(v) {
      var s = v == null ? '' : String(v);
      return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    }
    var lines = [headers.map(cell).join(',')];
    (rows || []).forEach(function (r) { lines.push((r || []).map(cell).join(',')); });
    return '\ufeff' + lines.join('\r\n');
  }
  function downloadCsv(name, content) {
    var blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1500);
  }

  function emptyRuleForm() {
    return {
      id: null, ruleCode: '', ruleName: '', recordType: null, ruleType: 4, ruleCategory: 'item_value',
      deductScore: 5, severity: 2, controlLevel: 2, qcStage: 0, status: 1, description: '', cfgText: '{}'
    };
  }
  function emptyDefectForm() {
    return { id: null, ruleId: null, defectDesc: '', deductScore: null, severity: null };
  }
  function emptyNoticeForm() {
    return { recordId: null, defectIdsText: '', deadline: '', remark: '' };
  }

  /* ================= 私有样式 ================= */
  (function ensureEqcStyles() {
    if (document.getElementById('eqc-styles')) { return; }
    var css = [
      '.eqc-wrap{display:flex;height:100%;min-height:0;}',
      '.eqc-nav{width:216px;flex:none;background:var(--yb-surface);border-right:1px solid var(--yb-border);display:flex;flex-direction:column;padding:14px 10px;gap:4px;overflow-y:auto;}',
      '.eqc-nav-brand{font-weight:700;font-size:15px;color:var(--yb-ink-1);padding:2px 10px 12px;letter-spacing:.5px;}',
      '.eqc-nav-item{display:flex;gap:10px;align-items:flex-start;padding:9px 10px;border-radius:8px;cursor:pointer;border:1px solid transparent;}',
      '.eqc-nav-item:hover{background:var(--yb-surface-2);}',
      '.eqc-nav-item.on{background:var(--yb-info-light);border-color:#cfe3f8;}',
      '.eqc-nav-item.on .eqc-nav-name{color:var(--yb-brand);font-weight:600;}',
      '.eqc-nav-ico{font-size:15px;line-height:19px;}',
      '.eqc-nav-name{font-size:13px;color:var(--yb-ink-1);line-height:19px;}',
      '.eqc-nav-sub{font-size:11px;color:var(--yb-ink-4);margin-top:2px;}',
      '.eqc-nav-foot{margin-top:auto;padding:12px 10px 2px;font-size:11px;color:var(--yb-ink-4);line-height:1.7;}',
      '.eqc-main{flex:1;min-width:0;overflow-y:auto;padding:2px 20px 40px 18px;}',
      '.eqc-hd{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:14px 0 12px;}',
      '.eqc-hd-t{font-size:16px;font-weight:600;color:var(--yb-ink-1);}',
      '.eqc-hd-s{font-size:12px;color:var(--yb-ink-3);margin-top:4px;}',
      '.eqc-kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:2px 0 14px;}',
      '.eqc-kpi{background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:10px;padding:12px 14px;}',
      '.eqc-kpi-t{font-size:12px;color:var(--yb-ink-3);}',
      '.eqc-kpi-n{font-size:22px;font-weight:700;color:var(--yb-ink-1);margin-top:4px;}',
      '.eqc-kpi-s{font-size:11px;color:var(--yb-ink-4);margin-top:2px;}',
      '.eqc-panel{background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:10px;padding:14px;margin-bottom:14px;}',
      '.eqc-panel-hd{display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;}',
      '.eqc-panel-t{font-size:13px;font-weight:600;color:var(--yb-ink-1);}',
      '.eqc-cards2{display:grid;grid-template-columns:1fr 1fr;gap:12px;}',
      '.eqc-cards3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;}',
      '.eqc-bar-row{display:flex;align-items:center;gap:8px;margin:6px 0;font-size:12px;}',
      '.eqc-bar-name{width:110px;flex:none;text-align:right;color:var(--yb-ink-2);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
      '.eqc-bar-track{flex:1;height:14px;background:var(--yb-surface-3);border-radius:4px;overflow:hidden;}',
      '.eqc-bar-fill{height:100%;border-radius:4px;background:var(--yb-fill-info);min-width:2px;}',
      '.eqc-bar-val{width:76px;flex:none;color:var(--yb-ink-3);white-space:nowrap;}',
      '.eqc-grade-strip{display:flex;height:22px;border-radius:6px;overflow:hidden;margin:8px 0 8px;background:var(--yb-surface-3);}',
      '.eqc-grade-seg{display:flex;align-items:center;justify-content:center;font-size:11px;color:#fff;min-width:26px;}',
      '.eqc-legend{display:flex;gap:14px;font-size:11px;color:var(--yb-ink-3);flex-wrap:wrap;}',
      '.eqc-legend i{display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:4px;vertical-align:-1px;}',
      '.eqc-defect-card{border:1px solid var(--yb-border);border-radius:8px;padding:10px 12px;margin-bottom:8px;background:var(--yb-surface);}',
      '.eqc-defect-auto{border-left:3px solid var(--yb-fill-info);}',
      '.eqc-defect-manual{border-left:3px solid var(--yb-fill-warning);}',
      '.eqc-defect-hd{display:flex;align-items:center;gap:8px;flex-wrap:wrap;}',
      '.eqc-defect-name{font-weight:600;font-size:13px;color:var(--yb-ink-1);}',
      '.eqc-defect-desc{font-size:12px;color:var(--yb-ink-2);margin:6px 0;}',
      '.eqc-defect-ft{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;}',
      '.eqc-review-split{display:flex;gap:14px;min-height:420px;}',
      '.eqc-review-doc{flex:1.4;min-width:0;border:1px solid var(--yb-border);border-radius:8px;overflow:auto;max-height:62vh;padding:8px;background:var(--yb-surface);}',
      '.eqc-review-side{flex:1;min-width:330px;max-height:62vh;overflow-y:auto;}',
      '.eqc-doc-fallback{white-space:pre-wrap;font-size:13px;line-height:1.8;color:var(--yb-ink-1);padding:8px;font-family:inherit;}',
      '.eqc-note{font-size:12px;color:var(--yb-ink-4);line-height:1.7;}',
      '.eqc-steps{display:flex;gap:6px;margin-bottom:12px;flex-wrap:wrap;}',
      '.eqc-mt8{margin-top:8px;}'
    ].join('\n');
    var el = document.createElement('style');
    el.id = 'eqc-styles';
    el.textContent = css;
    document.head.appendChild(el);
  })();

  /* ================= 组件 ================= */
  HIS.views.EmrQualityConsole = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        /* 全局 */
        module: 'standard', navs: NAVS, isLead: false, user: HIS.getUser() || {},
        depts: [], allRules: [],
        recordTypes: RECORD_TYPES, severities: SEVERITIES, controlLevels: CONTROL_LEVELS,
        qcStages: QC_STAGES, contentCats: CONTENT_CATS, grades: GRADES,

        /* M1 评分标准 */
        stdLoading: false, standards: [], stdActive: STD_CATS.slice(0, 1), stdSavingId: null, stdDrafts: {},

        /* M2 时效 */
        tlLoading: false, tlSavingId: null, tlDrafts: {},
        overdueList: [], overdueLoading: false,
        nearList: [], nearLoading: false,

        /* M3 内涵规则 */
        ctFilterRecordType: null, ctFilterCat: null, ctFilterStatus: null, ctFilterKw: '',
        ctPage: 1, ctSize: 20,
        ctDlg: false, ctSaving: false, ctTitle: '新建内涵规则', ctForm: emptyRuleForm(),

        /* M4 病历查询 */
        q: { deptId: null, doctor: '', dateRange: null, recordType: null, scoreMin: null, scoreMax: null, grade: null, status: null },
        qLoading: false, qRows: [], qSelected: [], qSearched: false, qPage: 1, qPageSize: 20,
        qDetail: false, qDetailLoading: false, qRecord: null, qDefects: [], qDefectsLoading: false, qGrade: null,

        /* M5 人工质控 */
        mqTab: 'sample', mqMonth: currentMonth(),
        sampleForm: { deptId: null, ratio: 30 }, sampling: false, sampleResult: null,
        queue: [], queueLoading: false, queueSelected: [], assigning: false,
        rv: { visible: false, loading: false, record: null, defects: [], defectsLoading: false, grade: '甲', opinion: '', finishing: false, editorSeq: 0, contentRaw: '' },
        rvWrapper: null,
        dfDlg: false, dfSaving: false, dfTitle: '新增缺陷', dfForm: emptyDefectForm(),
        notices: [], noticesLoading: false, noticeFilterStatus: null,
        ntDlg: false, ntSending: false, ntForm: emptyNoticeForm(),
        ntReview: { visible: false, saving: false, row: null, pass: true, opinion: '' },
        ntAppeal: { visible: false, saving: false, row: null, approve: true, opinion: '' },
        ntDetail: { visible: false, row: null },

        /* M6 统计分析 */
        stMonth: currentMonth(),
        stLoading: false, stReport: null, stPrevReport: null, stDefects: null, stPrevDefects: null,
        stDoctorRows: [], stDoctorLoading: false, stDoctorNote: ''
      };
    },
    computed: {
      /* ---- M1: 五大类分组(组内保持后端排序) ---- */
      stdGroups: function () {
        var src = this.standards || [], groups = [];
        STD_CATS.forEach(function (c) {
          var rows = src.filter(function (s) { return s.category === c; });
          if (rows.length) { groups.push({ cat: c, rows: rows }); }
        });
        /* 兜底: 后端出现未知分类时归入"其他"组, 不丢数据 */
        var known = {};
        STD_CATS.forEach(function (c) { known[c] = true; });
        var rest = src.filter(function (s) { return !known[s.category]; });
        if (rest.length) { groups.push({ cat: '其他', rows: rest }); }
        return groups;
      },
      /* ---- M2 ---- */
      tlRules: function () { return (this.allRules || []).filter(function (r) { return Number(r.ruleType) === 2; }); },
      overdueCount: function () { return (this.overdueList || []).length; },
      nearCount: function () { return (this.nearList || []).length; },
      /* ---- M3: 内涵五类过滤 + 客户端分页(500+条) ---- */
      contentRules: function () {
        var vm = this;
        var cats = {};
        CONTENT_CATS.forEach(function (c) { cats[c.v] = true; });
        var kw = String(vm.ctFilterKw || '').trim().toLowerCase();
        return (vm.allRules || []).filter(function (r) {
          if (!cats[r.ruleCategory]) { return false; }
          if (vm.ctFilterRecordType != null && Number(r.recordType) !== Number(vm.ctFilterRecordType)) { return false; }
          if (vm.ctFilterCat && r.ruleCategory !== vm.ctFilterCat) { return false; }
          if (vm.ctFilterStatus != null && Number(r.status) !== Number(vm.ctFilterStatus)) { return false; }
          if (kw && (text(r.ruleCode) + ' ' + text(r.ruleName)).toLowerCase().indexOf(kw) < 0) { return false; }
          return true;
        });
      },
      ctTotal: function () { return this.contentRules.length; },
      ctPagedList: function () {
        var start = (this.ctPage - 1) * this.ctSize;
        return this.contentRules.slice(start, start + this.ctSize);
      },
      /* ---- M4: 缺陷编辑弹窗可选规则(仅启用状态) ---- */
      enabledRules: function () {
        return (this.allRules || []).filter(function (r) { return Number(r.status) === 1; });
      },
      /* ---- M5 ---- */
      canMqWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN') || HIS.hasRole('QC');
      },
      queueStatus: function () {
        var vm = this;
        return function (s) { var x = QUEUE_STATUS[Number(s)]; return x ? x.l : '-'; };
      },
      queueStatusTag: function () {
        return function (s) { var x = QUEUE_STATUS[Number(s)]; return x ? x.t : 'info'; };
      },
      noticeStatus: function () {
        return function (s) { var x = NOTICE_STATUS[Number(s)]; return x ? x.l : '-'; };
      },
      noticeStatusTag: function () {
        return function (s) { var x = NOTICE_STATUS[Number(s)]; return x ? x.t : 'info'; };
      },
      defectStatus: function () {
        return function (s) { var x = DEFECT_STATUS[Number(s)]; return x ? x.l : '-'; };
      },
      defectStatusTag: function () {
        return function (s) { var x = DEFECT_STATUS[Number(s)]; return x ? x.t : 'info'; };
      },
      /* ---- M6: KPI 四卡(抽检率取人工质控进度预估字段, 缺数据显示 —) ---- */
      kpiCards: function () {
        var r = this.stReport || {};
        var ev = num(r.evaluatedCount, 0);
        var unq = num(r.unqualifiedCount, 0);
        var dist = r.scoreDistribution || {};
        var gradeA = num(dist['90-100'], 0);
        return [
          { t: '抽检率', n: this.sampleRateText, s: '人工质控抽检覆盖(本月)' },
          { t: '合格率', n: ev ? ((ev - unq) * 100 / ev).toFixed(1) + '%' : '—', s: '评分≥60分占比' },
          { t: '甲级率', n: ev ? (gradeA * 100 / ev).toFixed(1) + '%' : '—', s: '评分≥90分(甲)占比' },
          { t: '平均分', n: r.avgScore == null ? '—' : Number(r.avgScore).toFixed(1), s: '百分制起扣100分' }
        ];
      },
      sampleRateText: function () { return '—'; },
      /* 甲乙丙等级分布(近似口径): 甲=90-100档, 乙=80-89档, 丙=60-79档+60以下(60-79档中70-79实为乙, 精确口径以逐份评级为准) */
      gradeDist: function () {
        var r = this.stReport || {};
        var d = r.scoreDistribution || {};
        var a = num(d['90-100'], 0), b = num(d['80-89'], 0);
        var c = num(d['60-79'], 0) + num(d['60以下'], 0);
        var total = a + b + c;
        var palette = { 甲: 'var(--yb-fill-success)', 乙: 'var(--yb-fill-warning)', 丙: 'var(--yb-fill-danger)' };
        return { total: total, palette: palette, rows: [
          { g: '甲', v: a }, { g: '乙', v: b }, { g: '丙', v: c }
        ] };
      },
      defectTypeRows: function () {
        var d = (this.stDefects && this.stDefects.byType) || {};
        return Object.keys(d).map(function (k) { return { name: k, value: num(d[k], 0) }; })
          .sort(function (x, y) { return y.value - x.value; });
      },
      defectSeverityRows: function () {
        var d = (this.stDefects && this.stDefects.bySeverity) || {};
        return Object.keys(d).map(function (k) { return { name: k, value: num(d[k], 0) }; });
      },
      defectDeptRows: function () {
        return ((this.stDefects && this.stDefects.byDept) || []).slice(0, 10);
      },
      /* 科室排行(平均分/缺陷密度): /ranking + /defect-stats.byDept 合并 */
      deptRankRows: function () {
        var vm = this;
        var byDept = {};
        ((this.stDefects && this.stDefects.byDept) || []).forEach(function (x) { byDept[x.deptName] = num(x.count, 0); });
        return ((this.stReport && this.stReport.deptRanking) || []).map(function (r) {
          var cnt = byDept[r.deptName] || 0;
          return Object.assign({}, r, { defectCount: cnt, defectDensity: num(r.recordCount, 0) ? (cnt / Number(r.recordCount)).toFixed(2) : '0.00' });
        });
      },
      /* 月度环比(本月 vs 上月) */
      monthRows: function () {
        function pick(rep, key) { return rep == null || rep[key] == null ? null : Number(rep[key]); }
        var cur = this.stReport, prev = this.stPrevReport;
        var cd = this.stDefects, pd = this.stPrevDefects;
        var defs = [
          { k: '病历总数', c: pick(cur, 'totalRecords'), p: pick(prev, 'totalRecords') },
          { k: '已评分', c: pick(cur, 'evaluatedCount'), p: pick(prev, 'evaluatedCount') },
          { k: '平均分', c: pick(cur, 'avgScore'), p: pick(prev, 'avgScore'), fixed: 1 },
          { k: '不合格数', c: pick(cur, 'unqualifiedCount'), p: pick(prev, 'unqualifiedCount') },
          { k: '一票否决', c: pick(cur, 'fatalCount'), p: pick(prev, 'fatalCount') },
          { k: '缺陷总数', c: cd ? num(cd.total, 0) : null, p: pd ? num(pd.total, 0) : null }
        ];
        return defs.map(function (d) {
          var diff = (d.c == null || d.p == null) ? null : (d.c - d.p);
          var pct = (d.c == null || d.p == null || Number(d.p) === 0) ? null : (diff * 100 / Number(d.p));
          return Object.assign({}, d, {
            cText: d.c == null ? '—' : (d.fixed ? Number(d.c).toFixed(d.fixed) : String(d.c)),
            pText: d.p == null ? '—' : (d.fixed ? Number(d.p).toFixed(d.fixed) : String(d.p)),
            diffText: diff == null ? '—' : (diff > 0 ? '+' + diff : String(diff)),
            pctText: pct == null ? '' : '(' + (pct > 0 ? '+' : '') + pct.toFixed(1) + '%)',
            up: diff != null && diff > 0 && d.k !== '平均分'
          });
        });
      }
    },
    created: function () {
      this.isLead = typeof HIS.isLead === 'function' ? !!HIS.isLead() : false;
      this.loadDepts();
      this.loadStandards();
    },
    beforeUnmount: function () {
      this.destroyReviewEditor();
    },
    methods: {
      /* ================= 全局 ================= */
      switchModule: function (key) {
        var vm = this;
        vm.module = key;
        /* 懒加载: 首次进入模块时拉取数据 */
        if (key === 'timeliness') { vm.loadTimeliness(); }
        else if (key === 'content') { vm.loadAllRules().then(function () { /* computed 即时过滤 */ }); }
        else if (key === 'manual') { vm.loadQueue(); vm.loadNotices(); }
        else if (key === 'stats') { vm.loadStats(); }
      },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (d) {
          vm.depts = (d || []).map(function (x) { return { id: x.id, name: x.deptName }; });
        }).catch(function () { vm.depts = []; });
      },
      loadAllRules: function () {
        var vm = this;
        if (vm._rulesLoaded) { return Promise.resolve(); }
        return HIS.get(QP + '/rules').then(function (d) {
          vm.allRules = d || [];
          vm._rulesLoaded = true;
        }).catch(function (e) { HIS.notifyError(e); });
      },

      /* ================= M1 评分标准维护 ================= */
      loadStandards: function () {
        var vm = this;
        vm.stdLoading = true;
        HIS.get(QP + '/score-standards').then(function (d) {
          vm.standards = d || [];
          vm.standards.forEach(function (s) {
            vm.$set(vm.stdDrafts, vm.draftKey(s.id), {
              weight: s.weight == null ? null : Number(s.weight),
              status: Number(s.status) === 1,
              standardName: text(s.standardName),
              description: text(s.description)
            });
          });
        }).catch(HIS.notifyError).finally(function () { vm.stdLoading = false; });
      },
      draftKey: function (id) { return HIS.idKey(id) || '_'; },
      saveStd: function (row) {
        var vm = this;
        if (!vm.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护评分标准'); return; }
        var dr = vm.stdDrafts[vm.draftKey(row.id)] || {};
        var body = { weight: dr.weight == null ? null : Number(dr.weight), status: dr.status ? 1 : 0 };
        if (text(dr.standardName).trim() && dr.standardName !== row.standardName) { body.standardName = text(dr.standardName).trim(); }
        if (text(dr.description) !== text(row.description)) { body.description = text(dr.description); }
        vm.stdSavingId = row.id;
        HIS.put(QP + '/score-standard/' + HIS.idParam(row.id), body)
          .then(function () { HIS.notifySuccess('评分标准已更新'); vm.loadStandards(); })
          .catch(HIS.notifyError)
          .finally(function () { vm.stdSavingId = null; });
      },

      /* ================= M2 时效规则维护 ================= */
      loadTimeliness: function () {
        var vm = this;
        vm.loadAllRules().then(function () {
          /* 初始化行内编辑草稿(避免 v-model 到 undefined) */
          vm.allRules.forEach(function (r) {
            if (Number(r.ruleType) === 2) { vm.$set(vm.tlDrafts, vm.draftKey(r.id), {}); }
          });
        });
        vm.loadOverdue();
        vm.loadNear();
      },
      loadOverdue: function () {
        var vm = this;
        vm.overdueLoading = true;
        HIS.get(TL + '/overdue-list').then(function (d) { vm.overdueList = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.overdueLoading = false; });
      },
      loadNear: function () {
        var vm = this;
        vm.nearLoading = true;
        HIS.get(TL + '/near-deadline?withinHours=4').then(function (d) { vm.nearList = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.nearLoading = false; });
      },
      remainingText: function (m) {
        var min = Number(m.remainingMinutes);
        if (isNaN(min)) { return '-'; }
        if (min >= 0) { return '剩 ' + (min >= 60 ? Math.floor(min / 60) + '小时' + (min % 60 ? (min % 60) + '分' : '') : min + '分'); }
        var over = -min;
        return '超时 ' + (over >= 60 ? Math.floor(over / 60) + '小时' + (over % 60 ? (over % 60) + '分' : '') : over + '分');
      },
      saveTlRow: function (row) {
        var vm = this;
        if (!vm.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护质控规则'); return; }
        var dr = vm.tlDrafts[vm.draftKey(row.id)] || {};
        var body = {};
        if (dr.severity != null) { body.severity = Number(dr.severity); }
        if (dr.controlLevel != null) { body.controlLevel = Number(dr.controlLevel); }
        if (!Object.keys(body).length) { ElementPlus.ElMessage.info('未修改'); return; }
        /* 注: 后端 EmrQualityRuleDTO 暂无 controlLevel 映射, 保存后该列沿用原值, 待 DTO 扩展后生效 */
        vm.tlSavingId = row.id;
        HIS.put(QP + '/rule/' + HIS.idParam(row.id), body)
          .then(function () { HIS.notifySuccess('时效规则已更新'); vm._rulesLoaded = false; return vm.loadAllRules(); })
          .catch(HIS.notifyError)
          .finally(function () { vm.tlSavingId = null; });
      },

      /* ================= M3 内涵规则维护 ================= */
      safeParseCfg: function (s) {
        try { return JSON.parse(s || '{}') || {}; } catch (e) { return {}; }
      },
      ctResetPage: function () { this.ctPage = 1; },
      ctOpenCreate: function () {
        if (!this.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护质控规则'); return; }
        this.ctForm = emptyRuleForm();
        this.ctTitle = '新建内涵规则';
        this.ctDlg = true;
      },
      ctOpenEdit: function (row) {
        if (!this.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护质控规则'); return; }
        var f = emptyRuleForm();
        f.id = row.id; f.ruleCode = row.ruleCode; f.ruleName = row.ruleName;
        f.recordType = row.recordType == null ? null : Number(row.recordType);
        f.ruleType = row.ruleType == null ? 4 : Number(row.ruleType);
        f.ruleCategory = row.ruleCategory || 'item_value';
        f.deductScore = row.deductScore == null ? null : Number(row.deductScore);
        f.severity = row.severity == null ? 2 : Number(row.severity);
        f.controlLevel = row.controlLevel == null ? row.severity : Number(row.controlLevel);
        f.qcStage = row.qcStage == null ? 0 : Number(row.qcStage);
        f.status = Number(row.status);
        f.description = text(row.description);
        f.cfgText = JSON.stringify(this.safeParseCfg(row.ruleConfig), null, 2);
        this.ctForm = f;
        this.ctTitle = '编辑内涵规则 #' + HIS.idKey(row.id);
        this.ctDlg = true;
      },
      ctFormatCfg: function () {
        try {
          this.ctForm.cfgText = JSON.stringify(JSON.parse(this.ctForm.cfgText || '{}'), null, 2);
          ElementPlus.ElMessage.success('已格式化');
        } catch (e) { ElementPlus.ElMessage.error('JSON 格式错误: ' + e.message); }
      },
      ctValidate: function () {
        var f = this.ctForm;
        if (!text(f.ruleCode).trim()) { ElementPlus.ElMessage.warning('规则编码不能为空'); return false; }
        if (!text(f.ruleName).trim()) { ElementPlus.ElMessage.warning('规则名称不能为空'); return false; }
        if (!CONTENT_CATS.some(function (c) { return c.v === f.ruleCategory; })) { ElementPlus.ElMessage.warning('内涵子类不合法'); return false; }
        try { JSON.parse(f.cfgText || '{}'); } catch (e) { ElementPlus.ElMessage.warning('rule_config 不是合法 JSON'); return false; }
        return true;
      },
      ctSave: function () {
        var vm = this;
        if (!vm.ctValidate()) { return; }
        var f = vm.ctForm;
        /* 注: ruleConfig JSON 内建议携带 qcStage/controlLevel/category 字段 —— 后端 EmrQualityRuleDTO
         * 暂无 qcStage/controlLevel/ruleCategory 独立列映射, 独立字段随 DTO 扩展后生效 */
        var body = {
          ruleCode: text(f.ruleCode).trim(), ruleName: text(f.ruleName).trim(),
          recordType: f.recordType, ruleType: Number(f.ruleType),
          ruleConfig: f.cfgText || '{}',
          deductScore: f.deductScore == null || f.deductScore === '' ? null : Number(f.deductScore),
          severity: Number(f.severity),
          description: text(f.description),
          status: Number(f.status)
        };
        vm.ctSaving = true;
        var req = f.id ? HIS.put(QP + '/rule/' + HIS.idParam(f.id), body) : HIS.post(QP + '/rule', body);
        req.then(function () {
          HIS.notifySuccess(f.id ? '内涵规则已更新' : '内涵规则已新建');
          vm.ctDlg = false;
          vm._rulesLoaded = false;
          return vm.loadAllRules();
        }).catch(HIS.notifyError).finally(function () { vm.ctSaving = false; });
      },
      ctToggle: function (row) {
        var vm = this;
        if (!vm.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护质控规则'); return; }
        var next = Number(row.status) === 1 ? 0 : 1;
        HIS.put(QP + '/rule/' + HIS.idParam(row.id), { status: next })
          .then(function () {
            HIS.notifySuccess(next === 1 ? '规则已启用' : '规则已停用');
            vm._rulesLoaded = false;
            return vm.loadAllRules();
          }).catch(HIS.notifyError);
      },
      stageName: function (v) { var x = QC_STAGES.filter(function (s) { return s.v === Number(v); })[0]; return x ? x.l : '-'; },
      sevTag: function (s) { return Number(s) === 3 ? 'danger' : (Number(s) === 1 ? 'warning' : 'info'); },
      sevName: function (s) { var x = SEVERITIES.filter(function (s2) { return s2.v === Number(s); })[0]; return x ? x.l.replace(/^\d-/, '') : '-'; },
      ctrlName: function (v) { var x = CONTROL_LEVELS.filter(function (c) { return c.v === Number(v); })[0]; return x ? x.l : '-'; },
      typeName: function (t) { return labelOf(RECORD_TYPES, t); },
      ruleCatName: function (c) { return ruleCatName(c); },
      parseDeadline: function (s) { return parseDeadline(s); },
      ctOnSize: function (s) { this.ctSize = s; this.ctPage = 1; },

      /* ================= M4 病历查询 ================= */
      qReset: function () {
        this.q = { deptId: null, doctor: '', dateRange: null, recordType: null, scoreMin: null, scoreMax: null, grade: null, status: null };
      },
      qPager: function () {
        var vm = this;
        return function (i) { return (vm.qPage - 1) * vm.qPageSize + i + 1; };
      },
      runQuery: function () {
        var vm = this;
        var dr = vm.q.dateRange || [];
        var qs = '?page=1&size=200';
        if (vm.q.deptId != null) { qs += '&deptId=' + HIS.idParam(vm.q.deptId); }
        if (text(vm.q.doctor).trim()) { qs += '&doctor=' + encodeURIComponent(text(vm.q.doctor).trim()); }
        if (dr[0]) { qs += '&startDate=' + dr[0]; }
        if (dr[1]) { qs += '&endDate=' + dr[1]; }
        if (vm.q.recordType != null) { qs += '&recordType=' + vm.q.recordType; }
        if (vm.q.scoreMin != null) { qs += '&scoreMin=' + vm.q.scoreMin; }
        if (vm.q.scoreMax != null) { qs += '&scoreMax=' + vm.q.scoreMax; }
        if (vm.q.grade) { qs += '&grade=' + encodeURIComponent(vm.q.grade); }
        if (vm.q.status != null) { qs += '&status=' + vm.q.status; }
        vm.qLoading = true; vm.qSearched = true; vm.qPage = 1;
        /* 质控专用病历查询为预估端点(P5c 就绪后对齐); 兼容 IPage {records} 与数组两种返回 */
        HIS.get(QP + '/records' + qs).then(function (d) {
          if (Array.isArray(d)) { vm.qRows = d; }
          else { vm.qRows = (d && (d.records || d.list)) || []; }
        }).catch(HIS.notifyError).finally(function () { vm.qLoading = false; });
      },
      qPagedList: function () {
        var start = (this.qPage - 1) * this.qPageSize;
        return this.qRows.slice(start, start + this.qPageSize);
      },
      gradeOf: function (row) {
        var s = row.qualityScore == null ? null : Number(row.qualityScore);
        return s == null ? '—' : (s >= 90 ? '甲' : (s >= 70 ? '乙' : '丙'));
      },
      gradeTag: function (g) { return g === '甲' ? 'success' : (g === '乙' ? 'warning' : (g === '丙' ? 'danger' : 'info')); },
      qOpenDetail: function (row) {
        var vm = this;
        var rid = row.id != null ? row.id : row.recordId;
        vm.qDetail = true; vm.qDetailLoading = true;
        vm.qRecord = row; vm.qDefects = []; vm.qGrade = null;
        Promise.all([
          HIS.get(QP + '/defects/' + HIS.idParam(rid)),
          HIS.get(QP + '/grade/' + HIS.idParam(rid)).catch(function () { return null; }),
          HIS.get('/api/his/inp/record/' + HIS.idParam(rid)).catch(function () { return null; })
        ]).then(function (rs) {
          vm.qDefects = rs[0] || [];
          vm.qGrade = rs[1];
          if (rs[2]) { vm.qRecord = Object.assign({}, row, rs[2]); }
        }).catch(HIS.notifyError).finally(function () { vm.qDetailLoading = false; });
      },
      qReeval: function (row) {
        var vm = this;
        var rid = row.id != null ? row.id : row.recordId;
        return HIS.post(QP + '/evaluate-content?recordId=' + HIS.idParam(rid) + '&stage=2')
          .then(function (d) {
            var n = (d || []).length;
            HIS.notifySuccess('归档内涵质控完成, 本次命中 ' + n + ' 条缺陷');
            if (vm.qDetail && vm.qRecord && HIS.idKey(vm.qRecord.id) === HIS.idKey(rid)) { vm.qOpenDetail(vm.qRecord); }
          });
      },
      qBatchReeval: function () {
        var vm = this;
        if (!vm.qSelected.length) { ElementPlus.ElMessage.warning('请先勾选病历'); return; }
        vm.qLoading = true;
        var ok = 0, fail = 0;
        var chain = Promise.resolve();
        vm.qSelected.forEach(function (row) {
          chain = chain.then(function () {
            return vm.qReeval(row).then(function () { ok++; }).catch(function () { fail++; });
          });
        });
        chain.finally(function () {
          vm.qLoading = false;
          ElementPlus.ElMessage.success('批量重评分完成: 成功 ' + ok + (fail ? ', 失败 ' + fail : ''));
          vm.runQuery();
        });
      },

      /* ================= M5 人工质控 ================= */
      loadQueue: function () {
        var vm = this;
        vm.queueLoading = true;
        /* 预估端点 GET /tasks?month=; 失败降级 GET /progress?month= 取 items/tasks/records 数组字段 */
        HIS.get(MQ + '/tasks?month=' + encodeURIComponent(vm.mqMonth))
          .then(function (d) { vm.queue = Array.isArray(d) ? d : ((d && (d.records || d.list)) || []); })
          .catch(function () {
            HIS.get(MQ + '/progress?month=' + encodeURIComponent(vm.mqMonth)).then(function (d) {
              vm.queue = (d && (d.items || d.tasks || d.records)) || [];
            }).catch(function () { vm.queue = []; });
          })
          .finally(function () { vm.queueLoading = false; });
      },
      doSample: function () {
        var vm = this;
        if (!vm.canMqWrite) { ElementPlus.ElMessage.warning('仅质控管理角色可执行抽检'); return; }
        vm.sampling = true; vm.sampleResult = null;
        /* P5b-2实际契约: POST /sample?deptId=&month=&sampleRate= (@RequestParam) */
        HIS.post(MQ + '/sample?deptId=' + encodeURIComponent(vm.sampleForm.deptId || '') + '&month=' + encodeURIComponent(vm.mqMonth) + '&sampleRate=' + (vm.sampleForm.ratio || 30)).then(function (d) {
          vm.sampleResult = d;
          HIS.notifySuccess('抽检完成');
          vm.loadQueue();
        }).catch(HIS.notifyError).finally(function () { vm.sampling = false; });
      },
      assignSelected: function () {
        var vm = this;
        var ids = vm.queueSelected.map(function (r) { return r.recordId != null ? r.recordId : r.id; });
        if (!ids.length) { ElementPlus.ElMessage.warning('请先勾选待分配病历'); return; }
        vm.assigning = true;
        /* 预估契约: POST /assign {recordIds} — 分配给当前质控员 */
        HIS.post(MQ + '/assign', { recordIds: ids })
          .then(function () { HIS.notifySuccess('已分配 ' + ids.length + ' 份'); vm.loadQueue(); })
          .catch(HIS.notifyError).finally(function () { vm.assigning = false; });
      },
      openReview: function (row) {
        var vm = this;
        var rid = row.recordId != null ? row.recordId : row.id;
        vm.rv = { visible: true, loading: true, record: row, defects: [], defectsLoading: false, grade: QUEUE_STATUS[Number(row.status)] && Number(row.status) === 2 ? '甲' : '甲', opinion: '', finishing: false, editorSeq: (vm.rv.editorSeq || 0) + 1, contentRaw: '' };
        vm.destroyReviewEditor();
        /* 开始审核(留痕, 失败不阻断) + 病历全文 + 缺陷列表 并行装配 */
        HIS.post(MQ + '/review/start/' + HIS.idParam(rid), {}).catch(function () { /* 已开始过等场景 */ });
        Promise.all([
          HIS.get('/api/his/inp/record/' + HIS.idParam(rid)),
          vm.loadReviewDefects(rid)
        ]).then(function (rs) {
          vm.rv.record = Object.assign({}, row, rs[0] || {});
          vm.rv.contentRaw = (rs[0] && rs[0].content) || '';
          vm.rv.loading = false;
          vm.$nextTick(function () { vm.buildReviewEditor(); });
        }).catch(function (e) {
          vm.rv.loading = false;
          HIS.notifyError(e);
        });
      },
      loadReviewDefects: function (rid) {
        var vm = this;
        vm.rv.defectsLoading = true;
        return HIS.get(QP + '/defects/' + HIS.idParam(rid))
          .then(function (d) { vm.rv.defects = d || []; })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.rv.defectsLoading = false; });
      },
      buildReviewEditor: function () {
        var vm = this;
        var host = vm.$refs.rvDoc;
        if (!host) { return; }
        var seq = (vm.rv.editorSeq = (vm.rv.editorSeq || 0) + 1);
        var raw = vm.rv.contentRaw;
        if (!HIS.EmrEditor || typeof HIS.EmrEditor.createEditor !== 'function') {
          host.innerHTML = '<pre class="eqc-doc-fallback">' + escapeHtml(extractPlainText(raw)) + '</pre>';
          return;
        }
        HIS.EmrEditor.createEditor({
          container: host,
          mode: 'preview',
          readOnly: true,
          document: parseDocRaw(raw)
        }).then(function (w) {
          if (seq !== vm.rv.editorSeq) { try { w.destroy(); } catch (e) { /* 被新批次取代 */ } return; }
          vm.destroyReviewEditor();
          vm.rvWrapper = w;
        }).catch(function () {
          host.innerHTML = '<pre class="eqc-doc-fallback">' + escapeHtml(extractPlainText(raw)) + '</pre>';
        });
      },
      destroyReviewEditor: function () {
        var vm = this;
        vm.rv.editorSeq = (vm.rv.editorSeq || 0) + 1;   /* 使在途 createEditor 失效 */
        if (vm.rvWrapper) {
          try { vm.rvWrapper.destroy(); } catch (e) { /* noop */ }
          vm.rvWrapper = null;
        }
      },
      closeReview: function () {
        this.rv.visible = false;
        this.destroyReviewEditor();
      },
      activateDefect: function (d) {
        var vm = this;
        var rid = vm.rv.record && (vm.rv.record.recordId != null ? vm.rv.record.recordId : vm.rv.record.id);
        HIS.put(MQ + '/defect/' + HIS.idParam(d.id) + '/activate', {})
          .then(function () { HIS.notifySuccess('机器缺陷已激活'); vm.loadReviewDefects(rid); })
          .catch(HIS.notifyError);
      },
      dfOpenNew: function () {
        var f = emptyDefectForm();
        var firstEnabled = (this.allRules || []).filter(function (r) { return Number(r.status) === 1; })[0];
        if (firstEnabled) { f.ruleId = firstEnabled.id; f.deductScore = firstEnabled.deductScore == null ? null : Number(firstEnabled.deductScore); f.severity = Number(firstEnabled.severity); }
        this.dfForm = f;
        this.dfTitle = '新增缺陷';
        this.dfDlg = true;
      },
      dfOpenEdit: function (d) {
        this.dfForm = {
          id: d.id, ruleId: d.ruleId || null,
          defectDesc: text(d.defectDesc),
          deductScore: d.deductScore == null ? null : Number(d.deductScore),
          severity: d.severity == null ? null : Number(d.severity)
        };
        this.dfTitle = '编辑缺陷 #' + HIS.idKey(d.id);
        this.dfDlg = true;
      },
      onDfRuleChange: function (rid) {
        var r = (this.allRules || []).filter(function (x) { return HIS.idKey(x.id) === HIS.idKey(rid); })[0];
        if (r) {
          if (this.dfForm.deductScore == null) { this.dfForm.deductScore = r.deductScore == null ? null : Number(r.deductScore); }
          if (this.dfForm.severity == null) { this.dfForm.severity = Number(r.severity); }
        }
      },
      dfSave: function () {
        var vm = this;
        var rid = vm.rv.record && (vm.rv.record.recordId != null ? vm.rv.record.recordId : vm.rv.record.id);
        var f = vm.dfForm;
        if (!f.ruleId) { ElementPlus.ElMessage.warning('请选择质控规则'); return; }
        if (!text(f.defectDesc).trim()) { ElementPlus.ElMessage.warning('请填写缺陷描述'); return; }
        var body = {
          recordId: rid,
          ruleId: f.ruleId,
          defectDesc: text(f.defectDesc).trim(),
          deductScore: f.deductScore == null ? null : Number(f.deductScore),
          severity: f.severity == null ? null : Number(f.severity)
        };
        vm.dfSaving = true;
        var req = f.id ? HIS.put(MQ + '/defect/' + HIS.idParam(f.id), body) : HIS.post(MQ + '/defect', body);
        req.then(function () {
          HIS.notifySuccess(f.id ? '缺陷已更新' : '缺陷已登记');
          vm.dfDlg = false;
          vm.loadReviewDefects(rid);
        }).catch(HIS.notifyError).finally(function () { vm.dfSaving = false; });
      },
      dfRemove: function (d) {
        var vm = this;
        var rid = vm.rv.record && (vm.rv.record.recordId != null ? vm.rv.record.recordId : vm.rv.record.id);
        ElementPlus.ElMessageBox.confirm('确认删除该缺陷「' + text(d.ruleName || d.defectDesc) + '」？', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del(MQ + '/defect/' + HIS.idParam(d.id)); })
          .then(function () { HIS.notifySuccess('已删除'); vm.loadReviewDefects(rid); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      finishReview: function () {
        var vm = this;
        var rid = vm.rv.record && (vm.rv.record.recordId != null ? vm.rv.record.recordId : vm.rv.record.id);
        vm.rv.finishing = true;
        /* P5b-2实际契约: POST /review/finish/{recordId}?grade= (@RequestParam) */
        HIS.post(MQ + '/review/finish/' + HIS.idParam(rid) + '?grade=' + encodeURIComponent(vm.rv.grade))
          .then(function () {
            HIS.notifySuccess('审核完成, 评级 ' + vm.rv.grade);
            vm.closeReview();
            vm.loadQueue();
          }).catch(HIS.notifyError).finally(function () { vm.rv.finishing = false; });
      },

      /* ---- M5 整改跟踪 ---- */
      loadNotices: function () {
        var vm = this;
        vm.noticesLoading = true;
        /* P5b-2实际契约: GET /notices?startDate=&endDate=&status= (@RequestParam) */
        var qs = '?startDate=' + encodeURIComponent(vm.mqMonth + '-01') + '&endDate=' + encodeURIComponent(vm.mqMonth + '-31');
        if (vm.noticeFilterStatus != null && vm.noticeFilterStatus !== '') { qs += '&status=' + vm.noticeFilterStatus; }
        HIS.get(MQ + '/notices' + qs).then(function (d) {
          vm.notices = Array.isArray(d) ? d : ((d && (d.records || d.list)) || []);
        }).catch(function () { vm.notices = []; })
          .finally(function () { vm.noticesLoading = false; });
      },
      ntOpenDlg: function () {
        if (!this.canMqWrite) { ElementPlus.ElMessage.warning('仅质控管理角色可下发整改通知'); return; }
        var f = emptyNoticeForm();
        if (this.queueSelected.length) { f.recordId = this.queueSelected[0].recordId != null ? this.queueSelected[0].recordId : this.queueSelected[0].id; }
        this.ntForm = f;
        this.ntDlg = true;
      },
      ntSend: function () {
        var vm = this;
        var f = vm.ntForm;
        if (!f.recordId) { ElementPlus.ElMessage.warning('请填写病历ID'); return; }
        var ids = String(f.defectIdsText || '').split(/[,，;；\s]+/).map(function (x) { return x.trim(); }).filter(Boolean);
        if (!ids.length) { ElementPlus.ElMessage.warning('请填写缺陷ID(可从审核工作台缺陷卡片复制)'); return; }
        vm.ntSending = true;
        /* P5b-2实际契约: POST /notice @RequestBody HisEmrQcNotice(defectIds为JSON字符串, deadline→requireRectifyDate) */
        HIS.post(MQ + '/notice', {
          recordId: f.recordId,
          defectIds: JSON.stringify(ids),
          requireRectifyDate: f.deadline || null
        }).then(function () {
          HIS.notifySuccess('整改通知已下发');
          vm.ntDlg = false;
          vm.loadNotices();
        }).catch(HIS.notifyError).finally(function () { vm.ntSending = false; });
      },
      ntOpenDetail: function (row) { this.ntDetail = { visible: true, row: row }; },
      ntOpenReview: function (row) {
        this.ntReview = { visible: true, saving: false, row: row, pass: true, opinion: '' };
      },
      ntDoReview: function () {
        var vm = this;
        vm.ntReview.saving = true;
        /* P5b-2实际契约: PUT /notice/{id}/review-rectify?passed=&comment= (@RequestParam) */
        HIS.put(MQ + '/notice/' + HIS.idParam(vm.ntReview.row.id) + '/review-rectify?passed=' + !!vm.ntReview.pass + '&comment=' + encodeURIComponent(text(vm.ntReview.opinion).trim())).then(function () {
          HIS.notifySuccess(vm.ntReview.pass ? '复核通过' : '已驳回');
          vm.ntReview.visible = false;
          vm.loadNotices();
        }).catch(HIS.notifyError).finally(function () { vm.ntReview.saving = false; });
      },
      ntOpenAppeal: function (row) {
        this.ntAppeal = { visible: true, saving: false, row: row, approve: true, opinion: '' };
      },
      ntDoAppeal: function () {
        var vm = this;
        vm.ntAppeal.saving = true;
        /* P5b-2实际契约: PUT /notice/{id}/handle-appeal?accepted=&comment= (@RequestParam) */
        HIS.put(MQ + '/notice/' + HIS.idParam(vm.ntAppeal.row.id) + '/handle-appeal?accepted=' + !!vm.ntAppeal.approve + '&comment=' + encodeURIComponent(text(vm.ntAppeal.opinion).trim())).then(function () {
          HIS.notifySuccess(vm.ntAppeal.approve ? '申诉成立, 已豁免' : '申诉驳回');
          vm.ntAppeal.visible = false;
          vm.loadNotices();
        }).catch(HIS.notifyError).finally(function () { vm.ntAppeal.saving = false; });
      },

      /* ================= M6 统计分析 ================= */
      loadStats: function () {
        var vm = this;
        var cur = monthRange(vm.stMonth), prev = monthRange(prevMonth(vm.stMonth));
        vm.stLoading = true;
        Promise.all([
          HIS.get(QP + '/report?startDate=' + cur[0] + '&endDate=' + cur[1]),
          HIS.get(QP + '/report?startDate=' + prev[0] + '&endDate=' + prev[1]).catch(function () { return null; }),
          HIS.get(QP + '/defect-stats?month=' + encodeURIComponent(vm.stMonth)),
          HIS.get(QP + '/defect-stats?month=' + encodeURIComponent(prevMonth(vm.stMonth))).catch(function () { return null; }),
          /* 医生排行为预估端点(P5c 就绪后对齐), 失败不阻断其余统计 */
          HIS.get(QP + '/doctor-ranking?month=' + encodeURIComponent(vm.stMonth)).catch(function () { return null; })
        ]).then(function (rs) {
          vm.stReport = rs[0] || null;
          vm.stPrevReport = rs[1];
          vm.stDefects = rs[2] || null;
          vm.stPrevDefects = rs[3];
          if (rs[4]) { vm.stDoctorRows = Array.isArray(rs[4]) ? rs[4] : ((rs[4].records) || []); vm.stDoctorNote = ''; }
          else { vm.stDoctorRows = []; vm.stDoctorNote = '医生排行接口(预估端点)就绪后展示'; }
        }).catch(HIS.notifyError).finally(function () { vm.stLoading = false; });
      },
      barPct: function (v, max) { return max ? Math.max(2, Math.round(v * 100 / max)) : 2; },
      exportPerfCsv: function () {
        var vm = this;
        /* 质控员绩效: 按审核人聚合队列(审核份数/已完成/甲级份数) */
        var map = {};
        (vm.queue || []).forEach(function (r) {
          var name = text(r.qcUserName || r.assigneeName || r.auditName || '未分配');
          var o = map[name] = map[name] || { reviewer: name, total: 0, done: 0, gradeA: 0 };
          o.total++;
          if (Number(r.status) === 2) {
            o.done++;
            var g = r.grade || vm.gradeOf(r);
            if (g === '甲') { o.gradeA++; }
          }
        });
        var rows = Object.keys(map).map(function (k) { return map[k]; });
        if (!rows.length) { rows = [{ reviewer: '（本月队列无数据）', total: 0, done: 0, gradeA: 0 }]; }
        downloadCsv('质控员绩效_' + vm.mqMonth + '.csv', toCsv(
          ['质控员', '分配份数', '已完成', '甲级份数', '完成率'],
          rows.map(function (r) { return [r.reviewer, r.total, r.done, r.gradeA, r.total ? (r.done * 100 / r.total).toFixed(1) + '%' : '-']; })
        ));
      },
      exportRectifyCsv: function () {
        var vm = this;
        var rows = (vm.notices || []).map(function (n) {
          return [
            text(n.id), text(n.deptName || n.wardName || ''), text(n.patientName || ''),
            text(n.recordId), num(n.defectCount, 0), text(n.deadline || ''),
            vm.noticeStatus(n.status), text(n.remark || '')
          ];
        });
        if (!rows.length) { rows = [['（本月无通知单）', '', '', '', '', '', '', '']]; }
        downloadCsv('临床整改跟踪_' + vm.mqMonth + '.csv', toCsv(
          ['通知单ID', '科室/病区', '患者', '病历ID', '缺陷数', '整改期限', '状态', '备注'], rows
        ));
      }

    },
    /* ================= 模板 ================= */
    template: [
      '<div class="eqc-wrap">',
      /* ---- 左侧导航 ---- */
      '  <div class="eqc-nav">',
      '    <div class="eqc-nav-brand">病历质控工作台</div>',
      '    <div v-for="n in navs" :key="n.key" class="eqc-nav-item" :class="{on: module===n.key}" @click="switchModule(n.key)">',
      '      <span class="eqc-nav-ico">{{ n.icon }}</span>',
      '      <div><div class="eqc-nav-name">{{ n.name }}</div><div class="eqc-nav-sub">{{ n.sub }}</div></div>',
      '    </div>',
      '    <div class="eqc-nav-foot">',
      '      {{ isLead ? "牵头机构 · 可维护规则/标准" : "非牵头机构 · 只读查阅" }}',
      '      <div>评分语义: 100分起扣 · 甲≥90 / 乙70-89 / 丙<70</div>',
      '    </div>',
      '  </div>',
      /* ---- 右侧内容区 ---- */
      '  <div class="eqc-main">',

      /* ===== M1 评分标准维护 ===== */
      '    <div v-if="module===\'standard\'" v-loading="stdLoading">',
      '      <div class="eqc-hd">',
      '        <div>',
      '          <div class="eqc-hd-t">评分标准维护</div>',
      '          <div class="eqc-hd-s">卫健委电子病历应用水平五类评分标准(时效/完整/逻辑/规范/内涵) · 权重×基准分 · 更新须点「保存」落库</div>',
      '        </div>',
      '        <el-button size="small" @click="loadStandards">刷新</el-button>',
      '      </div>',
      '      <el-collapse v-model="stdActive">',
      '        <el-collapse-item v-for="g in stdGroups" :key="g.cat" :name="g.cat">',
      '          <template #title>',
      '            <span style="font-weight:600;">{{ g.cat }}类标准</span>',
      '            <el-tag size="small" style="margin-left:8px;">{{ g.rows.length }} 条</el-tag>',
      '          </template>',
      '          <el-table :data="g.rows" size="small" border stripe max-height="420">',
      '            <el-table-column prop="standardCode" label="编码" width="190" show-overflow-tooltip></el-table-column>',
      '            <el-table-column label="名称" min-width="220">',
      '              <template #default="s">',
      '                <el-input v-if="isLead" v-model="stdDrafts[draftKey(s.row.id)].standardName" size="small"></el-input>',
      '                <span v-else>{{ s.row.standardName }}</span>',
      '              </template>',
      '            </el-table-column>',
      '            <el-table-column label="病历类型" width="110">',
      '              <template #default="s">{{ s.row.recordType == null ? "通用" : typeName(s.row.recordType) }}</template>',
      '            </el-table-column>',
      '            <el-table-column prop="baseScore" label="基准分" width="80" align="center"></el-table-column>',
      '            <el-table-column label="权重" width="160">',
      '              <template #default="s">',
      '                <el-input-number v-if="isLead" v-model="stdDrafts[draftKey(s.row.id)].weight" :min="0.1" :max="10" :step="0.1" size="small" controls-position="right" style="width:130px;"></el-input-number>',
      '                <span v-else>{{ s.row.weight }}</span>',
      '              </template>',
      '            </el-table-column>',
      '            <el-table-column label="状态" width="90" align="center">',
      '              <template #default="s">',
      '                <el-switch v-if="isLead" v-model="stdDrafts[draftKey(s.row.id)].status"></el-switch>',
      '                <el-tag v-else size="small" :type="Number(s.row.status)===1 ? \'success\' : \'info\'">{{ Number(s.row.status)===1 ? "启用" : "停用" }}</el-tag>',
      '              </template>',
      '            </el-table-column>',
      '            <el-table-column label="操作" width="90" fixed="right">',
      '              <template #default="s">',
      '                <el-button v-if="isLead" link type="primary" size="small" :loading="stdSavingId===s.row.id" @click="saveStd(s.row)">保存</el-button>',
      '                <span v-else>-</span>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </el-collapse-item>',
      '      </el-collapse>',
      '      <div class="eqc-note eqc-mt8">共 {{ standards.length }} 条标准 · 白名单字段(名称/基准分/权重/说明/状态) · 基准分供查阅, 调整权重/启停后点「保存」</div>',
      '    </div>',

      /* ===== M2 时效规则维护 ===== */
      '    <div v-else-if="module===\'timeliness\'">',
      '      <div class="eqc-hd">',
      '        <div>',
      '          <div class="eqc-hd-t">时效规则维护</div>',
      '          <div class="eqc-hd-s">病历书写时限规则(规则类型=时限性) · 严重度/控制级别行内调整 · 后台每30分钟扫描自动落超时缺陷</div>',
      '        </div>',
      '        <el-button size="small" @click="loadTimeliness">刷新</el-button>',
      '      </div>',
      '      <div class="eqc-cards2">',
      '        <div class="eqc-panel" v-loading="overdueLoading">',
      '          <div class="eqc-panel-hd"><span class="eqc-panel-t" style="color:var(--yb-danger);">超时病历实时统计({{ overdueCount }})</span><span class="eqc-note">在院未签名且已过截止, 前5条</span></div>',
      '          <el-table :data="overdueList.slice(0, 5)" size="small" border max-height="195" empty-text="暂无超时病历">',
      '            <el-table-column prop="patientName" label="患者" width="80"></el-table-column>',
      '            <el-table-column prop="typeLabel" label="类型" width="95"></el-table-column>',
      '            <el-table-column prop="deadlineTime" label="截止时间" width="140"></el-table-column>',
      '            <el-table-column label="状态"><template #default="s"><span style="color:var(--yb-danger);">{{ remainingText(s.row) }}</span></template></el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="eqc-panel" v-loading="nearLoading">',
      '          <div class="eqc-panel-hd"><span class="eqc-panel-t" style="color:var(--yb-warning);">临近超时预警({{ nearCount }})</span><span class="eqc-note">4小时内到期, 前5条</span></div>',
      '          <el-table :data="nearList.slice(0, 5)" size="small" border max-height="195" empty-text="暂无临近超时">',
      '            <el-table-column prop="patientName" label="患者" width="80"></el-table-column>',
      '            <el-table-column prop="typeLabel" label="类型" width="95"></el-table-column>',
      '            <el-table-column prop="deadlineTime" label="截止时间" width="140"></el-table-column>',
      '            <el-table-column label="剩余"><template #default="s"><span style="color:var(--yb-warning);">{{ remainingText(s.row) }}</span></template></el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '      <div class="eqc-panel" v-loading="tlLoading">',
      '        <div class="eqc-panel-hd"><span class="eqc-panel-t">时效规则列表</span><span class="eqc-note">共 {{ tlRules.length }} 条 · 严重度/控制级别留空=保持原值</span></div>',
      '        <el-table :data="tlRules" size="small" border stripe max-height="420">',
      '          <el-table-column prop="ruleCode" label="编码" width="170" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="ruleName" label="规则名称" min-width="180" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="时限" width="110"><template #default="s">{{ parseDeadline(s.row.ruleConfig) }}</template></el-table-column>',
      '          <el-table-column label="严重度" width="160">',
      '            <template #default="s">',
      '              <el-select v-if="isLead" v-model="tlDrafts[draftKey(s.row.id)].severity" size="small" placeholder="保持原值" clearable style="width:125px;">',
      '                <el-option v-for="t in severities" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '              </el-select>',
      '              <el-tag v-else size="small" :type="sevTag(s.row.severity)">{{ sevName(s.row.severity) }}</el-tag>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="控制级别" width="140">',
      '            <template #default="s">',
      '              <el-select v-if="isLead" v-model="tlDrafts[draftKey(s.row.id)].controlLevel" size="small" placeholder="保持原值" clearable style="width:105px;">',
      '                <el-option v-for="t in controlLevels" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '              </el-select>',
      '              <span v-else>{{ ctrlName(s.row.controlLevel) }}</span>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="状态" width="80" align="center">',
      '            <template #default="s"><el-tag size="small" :type="Number(s.row.status)===1?\'success\':\'info\'">{{ Number(s.row.status)===1?"启用":"停用" }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="90" fixed="right">',
      '            <template #default="s">',
      '              <el-button v-if="isLead" link type="primary" size="small" :loading="tlSavingId===s.row.id" @click="saveTlRow(s.row)">保存</el-button>',
      '              <span v-else>-</span>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '        <div class="eqc-note eqc-mt8">注: 控制级别为 P5a 预留列, 后端 EmrQualityRuleDTO 扩展映射后保存生效</div>',
      '      </div>',
      '    </div>',

      /* ===== M3 内涵规则维护 ===== */
      '    <div v-else-if="module===\'content\'">',
      '      <div class="eqc-hd">',
      '        <div>',
      '          <div class="eqc-hd-t">内涵规则维护</div>',
      '          <div class="eqc-hd-s">内涵质控五大类(取值符合/项目对比/病种关联/计算校验/事件时序) · 运行(签名前)/归档环节标记 · 检查条件 JSON</div>',
      '        </div>',
      '        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">',
      '          <el-select v-model="ctFilterRecordType" placeholder="病历类型" clearable size="small" style="width:130px;" @change="ctResetPage">',
      '            <el-option v-for="t in recordTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '          </el-select>',
      '          <el-select v-model="ctFilterCat" placeholder="内涵子类" clearable size="small" style="width:120px;" @change="ctResetPage">',
      '            <el-option v-for="t in contentCats" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '          </el-select>',
      '          <el-select v-model="ctFilterStatus" placeholder="状态" clearable size="small" style="width:100px;" @change="ctResetPage">',
      '            <el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option>',
      '          </el-select>',
      '          <el-input v-model="ctFilterKw" placeholder="编码/名称关键字" clearable size="small" style="width:160px;" @input="ctResetPage"></el-input>',
      '          <el-button size="small" type="primary" :disabled="!isLead" @click="ctOpenCreate">+ 新建内涵规则</el-button>',
      '        </div>',
      '      </div>',
      '      <div class="eqc-panel">',
      '        <el-table :data="ctPagedList" v-loading="tlLoading" size="small" border stripe max-height="470">',
      '          <el-table-column prop="ruleCode" label="编码" width="160" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="ruleName" label="规则名称" min-width="170" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="内涵子类" width="100"><template #default="s">{{ ruleCatName(s.row.ruleCategory) }}</template></el-table-column>',
      '          <el-table-column label="病历类型" width="105"><template #default="s">{{ s.row.recordType == null ? "通用" : typeName(s.row.recordType) }}</template></el-table-column>',
      '          <el-table-column label="扣分" width="70" align="center"><template #default="s">{{ s.row.deductScore == null ? "-" : s.row.deductScore }}</template></el-table-column>',
      '          <el-table-column label="严重度" width="100" align="center"><template #default="s"><el-tag size="small" :type="sevTag(s.row.severity)">{{ sevName(s.row.severity) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="环节" width="115" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ stageName(s.row.qcStage) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="状态" width="90" align="center">',
      '            <template #default="s">',
      '              <el-switch v-if="isLead" :model-value="Number(s.row.status)===1" @change="ctToggle(s.row)"></el-switch>',
      '              <el-tag v-else size="small" :type="Number(s.row.status)===1?\'success\':\'info\'">{{ Number(s.row.status)===1?"启用":"停用" }}</el-tag>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="80" fixed="right">',
      '            <template #default="s"><el-button v-if="isLead" link type="primary" size="small" @click="ctOpenEdit(s.row)">编辑</el-button></template>',
      '          </el-table-column>',
      '        </el-table>',
      '        <div style="display:flex;justify-content:space-between;align-items:center;margin-top:10px;flex-wrap:wrap;gap:8px;">',
      '          <span class="eqc-note">共 {{ ctTotal }} 条内涵规则</span>',
      '          <el-pagination v-model:currentPage="ctPage" :page-size="ctSize" :page-sizes="[20, 50, 100]" :total="ctTotal" layout="total, sizes, prev, pager, next" small @size-change="ctOnSize"></el-pagination>',
      '        </div>',
      '      </div>',
      /* ---- 内涵规则编辑弹窗 ---- */
      '      <el-dialog v-model="ctDlg" :title="ctTitle" width="680px" top="6vh">',
      '        <el-form label-width="100px" size="small">',
      '          <el-row :gutter="12">',
      '            <el-col :span="12"><el-form-item label="规则编码"><el-input v-model="ctForm.ruleCode" :disabled="!!ctForm.id" placeholder="如 CONTENT_CALC_01"></el-input></el-form-item></el-col>',
      '            <el-col :span="12"><el-form-item label="规则名称"><el-input v-model="ctForm.ruleName"></el-input></el-form-item></el-col>',
      '          </el-row>',
      '          <el-row :gutter="12">',
      '            <el-col :span="8"><el-form-item label="内涵子类">',
      '              <el-select v-model="ctForm.ruleCategory" style="width:100%;"><el-option v-for="t in contentCats" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '            <el-col :span="8"><el-form-item label="病历类型">',
      '              <el-select v-model="ctForm.recordType" clearable placeholder="空=通用" style="width:100%;"><el-option v-for="t in recordTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '            <el-col :span="8"><el-form-item label="质控环节">',
      '              <el-select v-model="ctForm.qcStage" style="width:100%;"><el-option v-for="t in qcStages" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '          </el-row>',
      '          <el-row :gutter="12">',
      '            <el-col :span="6"><el-form-item label="扣分分值"><el-input-number v-model="ctForm.deductScore" :min="0" :max="100" controls-position="right" style="width:100%;"></el-input-number></el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="严重程度">',
      '              <el-select v-model="ctForm.severity" style="width:100%;" @change="ctForm.controlLevel = ctForm.severity"><el-option v-for="t in severities" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="控制级别">',
      '              <el-select v-model="ctForm.controlLevel" style="width:100%;"><el-option v-for="t in controlLevels" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '            <el-col :span="6"><el-form-item label="状态">',
      '              <el-select v-model="ctForm.status" style="width:100%;"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '          </el-row>',
      '          <el-form-item label="规则说明"><el-input v-model="ctForm.description" type="textarea" :rows="2"></el-input></el-form-item>',
      '          <el-form-item label="检查条件">',
      '            <el-input v-model="ctForm.cfgText" type="textarea" :rows="8" style="font-family:Consolas,monospace;" placeholder="JSON 检查条件, 如 {&quot;category&quot;:&quot;calculation&quot;,&quot;left&quot;:&quot;dosage&quot;,&quot;op&quot;:&quot;le&quot;,&quot;rightValue&quot;:&quot;max_dose&quot;}"></el-input>',
      '            <div style="margin-top:6px;"><el-button size="small" @click="ctFormatCfg">格式化 JSON</el-button></div>',
      '          </el-form-item>',
      '        </el-form>',
      '        <template #footer>',
      '          <el-button size="small" @click="ctDlg=false">取消</el-button>',
      '          <el-button size="small" type="primary" :loading="ctSaving" @click="ctSave">保存</el-button>',
      '        </template>',
      '      </el-dialog>',
      '    </div>',

      /* ===== M4 病历查询 ===== */
      '    <div v-if="module===\'query\'">',
      '      <div class="eqc-hd">',
      '        <div>',
      '          <div class="eqc-hd-t">病历查询</div>',
      '          <div class="eqc-hd-s">按科室/医生/日期/评分/等级检索病历, 点击行查看质控详情(缺陷+评级), 支持归档内涵重评分</div>',
      '        </div>',
      '      </div>',
      '      <div class="eqc-panel">',
      '        <el-form inline size="small">',
      '          <el-form-item label="科室">',
      '            <el-select v-model="q.deptId" clearable filterable placeholder="全部" style="width:150px;">',
      '              <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="医生"><el-input v-model="q.doctor" clearable placeholder="姓名/ID" style="width:130px;"></el-input></el-form-item>',
      '          <el-form-item label="日期">',
      '            <el-date-picker v-model="q.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="~" start-placeholder="开始" end-placeholder="结束" style="width:240px;"></el-date-picker>',
      '          </el-form-item>',
      '          <el-form-item label="病历类型">',
      '            <el-select v-model="q.recordType" clearable placeholder="全部" style="width:130px;">',
      '              <el-option v-for="t in recordTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="评分">',
      '            <el-input-number v-model="q.scoreMin" :min="0" :max="100" :controls="false" placeholder="低" style="width:64px;"></el-input-number>',
      '            <span style="margin:0 4px;color:var(--yb-ink-4);">~</span>',
      '            <el-input-number v-model="q.scoreMax" :min="0" :max="100" :controls="false" placeholder="高" style="width:64px;"></el-input-number>',
      '          </el-form-item>',
      '          <el-form-item label="等级">',
      '            <el-select v-model="q.grade" clearable placeholder="全部" style="width:84px;">',
      '              <el-option v-for="g in grades" :key="g" :label="g" :value="g"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="状态">',
      '            <el-select v-model="q.status" clearable placeholder="全部" style="width:100px;">',
      '              <el-option label="草稿" :value="1"></el-option>',
      '              <el-option label="已提交" :value="2"></el-option>',
      '              <el-option label="已审核" :value="3"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item>',
      '            <el-button type="primary" :loading="qLoading" @click="runQuery(1)">查询</el-button>',
      '            <el-button @click="qReset">重置</el-button>',
      '          </el-form-item>',
      '        </el-form>',
      '      </div>',
      '      <div class="eqc-panel">',
      '        <div class="eqc-panel-hd">',
      '          <div class="eqc-panel-t">病历列表 <span v-if="qSearched" class="eqc-note">共 {{ qRows.length }} 份 · 已选 {{ qSelected.length }} 份</span></div>',
      '          <el-button size="small" type="primary" plain :disabled="!qSelected.length" :loading="qLoading" @click="qBatchReeval">批量重新评分({{ qSelected.length }})</el-button>',
      '        </div>',
      '        <el-table :data="qPagedList()" v-loading="qLoading" size="small" border stripe style="cursor:pointer;" @selection-change="function(v){ qSelected = v }" @row-click="qOpenDetail">',
      '          <el-table-column type="selection" width="42" :selectable="function(){ return canMqWrite; }"></el-table-column>',
      '          <el-table-column label="#" width="56"><template #default="s">{{ (qPage-1)*qPageSize + s.$index + 1 }}</template></el-table-column>',
      '          <el-table-column label="病历ID" width="90"><template #default="s">{{ s.row.id != null ? s.row.id : s.row.recordId }}</template></el-table-column>',
      '          <el-table-column label="患者" min-width="90"><template #default="s">{{ s.row.patientName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="科室" min-width="110"><template #default="s">{{ s.row.deptName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="医生" min-width="90"><template #default="s">{{ s.row.doctorName || s.row.visitDoctorName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="病历类型" min-width="110"><template #default="s">{{ s.row.typeLabel || typeName(s.row.recordType) }}</template></el-table-column>',
      '          <el-table-column label="评分" width="84" align="center">',
      '            <template #default="s"><span :style="{fontWeight:600,color:(s.row.qualityScore!=null&&Number(s.row.qualityScore)<70)?\'var(--yb-danger)\':\'var(--yb-ink-1)\'}">{{ s.row.qualityScore == null ? \'-\' : Number(s.row.qualityScore).toFixed(1) }}</span></template>',
      '          </el-table-column>',
      '          <el-table-column label="等级" width="70" align="center">',
      '            <template #default="s"><el-tag v-if="gradeOf(s.row)!==\'—\'" size="small" :type="gradeTag(gradeOf(s.row))">{{ gradeOf(s.row) }}</el-tag><span v-else>-</span></template>',
      '          </el-table-column>',
      '          <el-table-column label="状态" width="84" align="center">',
      '            <template #default="s"><el-tag size="small" effect="plain" :type="Number(s.row.status)===3?\'success\':(Number(s.row.status)===2?\'primary\':\'info\')">{{ Number(s.row.status)===3?\'已审核\':(Number(s.row.status)===2?\'已提交\':\'草稿\') }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="132" fixed="right">',
      '            <template #default="s">',
      '              <el-button link type="primary" size="small" @click.stop="qOpenDetail(s.row)">详情</el-button>',
      '              <el-button v-if="canMqWrite" link type="warning" size="small" @click.stop="qReeval(s.row)">重评分</el-button>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '        <div v-if="!qSearched" class="eqc-note" style="padding:14px 0 2px;">请设置条件后点击「查询」; 质控专用病历查询为预估端点(P5c 就绪后对齐), 就绪前此处可能无数据</div>',
      '        <div style="display:flex;justify-content:flex-end;margin-top:10px;">',
      '          <el-pagination v-model:currentPage="qPage" :page-size="qPageSize" :page-sizes="[20, 50, 100]" :total="qRows.length" layout="total, sizes, prev, pager, next" small @size-change="function(s){ qPageSize = s; qPage = 1 }"></el-pagination>',
      '        </div>',
      '      </div>',
      /* ---- M4 质控详情抽屉 ---- */
      '      <el-drawer v-model="qDetail" title="质控详情" size="46%">',
      '        <div v-loading="qDetailLoading" style="min-height:220px;">',
      '          <template v-if="qRecord">',
      '            <div style="font-size:14px;font-weight:600;color:var(--yb-ink-1);margin-bottom:10px;">{{ qRecord.title || typeName(qRecord.recordType) || \'病历\' }} <span class="eqc-note" style="font-weight:400;">#{{ qRecord.id != null ? qRecord.id : qRecord.recordId }} · {{ qRecord.patientName || \'-\' }} · {{ qRecord.deptName || \'-\' }}</span></div>',
      '            <div class="eqc-cards3" style="margin-bottom:12px;">',
      '              <div class="eqc-kpi"><div class="eqc-kpi-t">综合评分</div><div class="eqc-kpi-n">{{ qRecord.qualityScore == null ? \'-\' : Number(qRecord.qualityScore).toFixed(1) }}</div><div class="eqc-kpi-s">100分起扣</div></div>',
      '              <div class="eqc-kpi"><div class="eqc-kpi-t">等级(按分值)</div><div class="eqc-kpi-n" :style="{color: gradeOf(qRecord)===\'甲\'?\'var(--yb-success)\':(gradeOf(qRecord)===\'乙\'?\'var(--yb-warning)\':(gradeOf(qRecord)===\'丙\'?\'var(--yb-danger)\':\'var(--yb-ink-3)\'))}">{{ gradeOf(qRecord) }}</div><div class="eqc-kpi-s">甲≥90 / 乙70-89 / 丙&lt;70</div></div>',
      '              <div class="eqc-kpi"><div class="eqc-kpi-t">缺陷数</div><div class="eqc-kpi-n">{{ qDefects.length }}</div><div class="eqc-kpi-s">机检+人工</div></div>',
      '            </div>',
      '            <div v-if="qGrade" class="eqc-note" style="margin-bottom:12px;">质控评级接口: <b style="color:var(--yb-ink-2);">{{ typeof qGrade === \'object\' ? (qGrade.grade || qGrade.level || \'-\') : qGrade }}</b></div>',
      '            <div class="eqc-panel-hd">',
      '              <div class="eqc-panel-t">缺陷列表</div>',
      '              <el-button v-if="canMqWrite" size="small" type="warning" plain @click="qReeval(qRecord)">重新评分(归档内涵)</el-button>',
      '            </div>',
      '            <el-table :data="qDefects" size="small" border>',
      '              <el-table-column label="缺陷规则" min-width="150"><template #default="s">{{ s.row.ruleName || s.row.ruleCode || \'-\' }}</template></el-table-column>',
      '              <el-table-column label="描述" min-width="180"><template #default="s">{{ s.row.defectDesc || \'-\' }}</template></el-table-column>',
      '              <el-table-column label="扣分" width="64" align="center"><template #default="s">{{ s.row.deductScore == null ? \'-\' : s.row.deductScore }}</template></el-table-column>',
      '              <el-table-column label="严重度" width="92" align="center"><template #default="s"><el-tag size="small" :type="sevTag(s.row.severity)">{{ sevName(s.row.severity) }}</el-tag></template></el-table-column>',
      '              <el-table-column label="状态" width="92" align="center"><template #default="s"><el-tag size="small" effect="plain" :type="defectStatusTag(s.row.status)">{{ defectStatus(s.row.status) }}</el-tag></template></el-table-column>',
      '            </el-table>',
      '          </template>',
      '        </div>',
      '      </el-drawer>',
      '    </div>',

      /* ===== M5 人工质控 ===== */
      '    <div v-if="module===\'manual\'">',
      '      <div class="eqc-hd">',
      '        <div>',
      '          <div class="eqc-hd-t">人工质控</div>',
      '          <div class="eqc-hd-s">抽检 → 分配 → 审核 → 整改跟踪闭环; 人工质控接口为预估路径(P5b-2 交付后对齐)</div>',
      '        </div>',
      '        <el-date-picker v-model="mqMonth" type="month" value-format="YYYY-MM" :clearable="false" style="width:130px;" @change="function(){ loadQueue(); loadNotices() }"></el-date-picker>',
      '      </div>',
      '      <el-radio-group v-model="mqTab" size="small" style="margin-bottom:12px;">',
      '        <el-radio-button label="sample">抽检设置</el-radio-button>',
      '        <el-radio-button label="queue">待审核队列</el-radio-button>',
      '        <el-radio-button label="notice">整改跟踪</el-radio-button>',
      '      </el-radio-group>',

      /* ---- 抽检设置 ---- */
      '      <div v-if="mqTab===\'sample\'" class="eqc-panel">',
      '        <div class="eqc-panel-t" style="margin-bottom:10px;">一键抽检</div>',
      '        <el-form inline size="small">',
      '          <el-form-item label="科室">',
      '            <el-select v-model="sampleForm.deptId" clearable filterable placeholder="全院" style="width:170px;">',
      '              <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="月份"><el-date-picker v-model="mqMonth" type="month" value-format="YYYY-MM" :clearable="false" style="width:130px;"></el-date-picker></el-form-item>',
      '          <el-form-item label="抽检比例">',
      '            <el-slider v-model="sampleForm.ratio" :min="10" :max="100" :step="5" style="width:200px;"></el-slider>',
      '          </el-form-item>',
      '          <el-form-item><el-button type="primary" :loading="sampling" @click="doSample">一键抽检({{ sampleForm.ratio }}%)</el-button></el-form-item>',
      '        </el-form>',
      '        <div class="eqc-note">按月对选中科室(空=全院)已提交/已审核病历按比例随机抽样, 生成质控任务并进入待审核队列。</div>',
      '        <div v-if="sampleResult" class="eqc-mt8" style="padding:10px 12px;background:var(--yb-info-light);border-radius:8px;">',
      '          <div class="eqc-panel-t" style="margin-bottom:4px;">抽检结果</div>',
      '          <pre class="eqc-doc-fallback">{{ typeof sampleResult === \'object\' ? JSON.stringify(sampleResult, null, 2) : sampleResult }}</pre>',
      '        </div>',
      '      </div>',

      /* ---- 待审核队列 ---- */
      '      <div v-if="mqTab===\'queue\'" class="eqc-panel">',
      '        <div class="eqc-panel-hd">',
      '          <div class="eqc-panel-t">待审核队列 <span class="eqc-note">(共 {{ queue.length }} 份 · 已选 {{ queueSelected.length }} 份)</span></div>',
      '          <div>',
      '            <el-button size="small" :disabled="!queueSelected.length" :loading="assigning" @click="assignSelected">分配给我({{ queueSelected.length }})</el-button>',
      '            <el-button size="small" @click="loadQueue">刷新</el-button>',
      '            <el-button size="small" type="warning" plain @click="ntOpenDlg">下发整改通知</el-button>',
      '          </div>',
      '        </div>',
      '        <el-table :data="queue" v-loading="queueLoading" size="small" border stripe @selection-change="function(v){ queueSelected = v }">',
      '          <el-table-column type="selection" width="42"></el-table-column>',
      '          <el-table-column label="病历ID" width="90"><template #default="s">{{ s.row.recordId != null ? s.row.recordId : s.row.id }}</template></el-table-column>',
      '          <el-table-column label="患者" min-width="90"><template #default="s">{{ s.row.patientName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="科室" min-width="110"><template #default="s">{{ s.row.deptName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="病历类型" min-width="110"><template #default="s">{{ s.row.typeLabel || typeName(s.row.recordType) }}</template></el-table-column>',
      '          <el-table-column label="质控员" min-width="90"><template #default="s">{{ s.row.qcUserName || s.row.assigneeName || \'未分配\' }}</template></el-table-column>',
      '          <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="queueStatusTag(s.row.status)">{{ queueStatus(s.row.status) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="评级" width="70" align="center"><template #default="s">{{ s.row.grade || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="操作" width="100" fixed="right"><template #default="s"><el-button link type="primary" size="small" @click="openReview(s.row)">开始审核</el-button></template></el-table-column>',
      '        </el-table>',
      '        <div v-if="!queue.length && !queueLoading" class="eqc-note" style="padding:12px 0 2px;">本月暂无抽检任务; 请先在「抽检设置」执行一键抽检(队列接口为预估端点, Felix 交付后自动对齐)</div>',
      '      </div>',

      /* ---- 整改跟踪 ---- */
      '      <div v-if="mqTab===\'notice\'" class="eqc-panel">',
      '        <div class="eqc-panel-hd">',
      '          <div class="eqc-panel-t">整改通知单 <span class="eqc-note">(共 {{ notices.length }} 条)</span></div>',
      '          <div style="display:flex;gap:8px;align-items:center;">',
      '            <el-select v-model="noticeFilterStatus" clearable placeholder="全部状态" size="small" style="width:140px;" @change="loadNotices">',
      '              <el-option label="待整改" :value="0"></el-option>',
      '              <el-option label="已整改待复核" :value="1"></el-option>',
      '              <el-option label="复核通过" :value="2"></el-option>',
      '              <el-option label="已驳回" :value="3"></el-option>',
      '              <el-option label="申诉中" :value="4"></el-option>',
      '              <el-option label="申诉处理完毕" :value="5"></el-option>',
      '            </el-select>',
      '            <el-button size="small" @click="loadNotices">刷新</el-button>',
      '            <el-button size="small" type="warning" plain @click="ntOpenDlg">下发整改通知</el-button>',
      '          </div>',
      '        </div>',
      '        <el-table :data="notices" v-loading="noticesLoading" size="small" border stripe>',
      '          <el-table-column label="通知ID" width="90"><template #default="s">{{ s.row.id }}</template></el-table-column>',
      '          <el-table-column label="病历ID" width="90"><template #default="s">{{ s.row.recordId }}</template></el-table-column>',
      '          <el-table-column label="科室/病区" min-width="110"><template #default="s">{{ s.row.deptName || s.row.wardName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="患者" min-width="90"><template #default="s">{{ s.row.patientName || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="缺陷数" width="76" align="center"><template #default="s">{{ s.row.defectCount != null ? s.row.defectCount : \'-\' }}</template></el-table-column>',
      '          <el-table-column label="整改期限" width="100" align="center"><template #default="s">{{ s.row.deadline || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="状态" width="120" align="center"><template #default="s"><el-tag size="small" :type="noticeStatusTag(s.row.status)">{{ noticeStatus(s.row.status) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="操作" width="170" fixed="right">',
      '            <template #default="s">',
      '              <el-button link type="primary" size="small" @click="ntOpenDetail(s.row)">详情</el-button>',
      '              <el-button v-if="Number(s.row.status)===1" link type="success" size="small" @click="ntOpenReview(s.row)">复核</el-button>',
      '              <el-button v-if="Number(s.row.status)===4" link type="warning" size="small" @click="ntOpenAppeal(s.row)">处理申诉</el-button>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '        <div v-if="!notices.length && !noticesLoading" class="eqc-note" style="padding:12px 0 2px;">本月暂无整改通知单</div>',
      '      </div>',

      /* ---- 审核工作台(Tiptap 只读 + 缺陷卡片) ---- */
      '      <el-dialog v-model="rv.visible" title="人工质控 · 审核工作台" width="92%" top="4vh" @closed="destroyReviewEditor">',
      '        <div v-loading="rv.loading" style="min-height:320px;">',
      '          <template v-if="rv.record">',
      '            <div class="eqc-note" style="margin-bottom:8px;">',
      '              病历 #{{ rv.record.recordId != null ? rv.record.recordId : rv.record.id }} · {{ rv.record.patientName || \'-\' }} · {{ rv.record.deptName || \'-\' }} · {{ rv.record.typeLabel || typeName(rv.record.recordType) }}',
      '              <el-tag v-if="rv.record.status != null" size="small" style="margin-left:8px;" :type="queueStatusTag(rv.record.status)">{{ queueStatus(rv.record.status) }}</el-tag>',
      '            </div>',
      '            <div class="eqc-review-split">',
      '              <div class="eqc-review-doc"><div ref="rvDoc"></div></div>',
      '              <div class="eqc-review-side">',
      '                <div class="eqc-panel-hd"><div class="eqc-panel-t">缺陷卡片({{ rv.defects.length }})</div><el-button size="small" type="primary" plain @click="dfOpenNew">新增缺陷</el-button></div>',
      '                <div v-loading="rv.defectsLoading" style="min-height:120px;">',
      '                  <div v-for="d in rv.defects" :key="d.id" class="eqc-defect-card" :class="Number(d.autoGenerated)===1 ? \'eqc-defect-auto\' : \'eqc-defect-manual\'">',
      '                    <div class="eqc-defect-hd">',
      '                      <span class="eqc-defect-name">{{ d.ruleName || d.ruleCode || \'自定义缺陷\' }}</span>',
      '                      <el-tag size="small" effect="plain" :type="Number(d.autoGenerated)===1?\'primary\':\'warning\'">{{ Number(d.autoGenerated)===1?\'机器\':\'人工\' }}</el-tag>',
      '                      <el-tag v-if="Number(d.autoGenerated)===1 && Number(d.status)===0" size="small" type="danger" effect="plain">待激活</el-tag>',
      '                    </div>',
      '                    <div class="eqc-defect-desc">{{ d.defectDesc || \'-\' }}</div>',
      '                    <div class="eqc-defect-ft">',
      '                      <span class="eqc-note">扣 {{ d.deductScore == null ? \'-\' : d.deductScore }} 分 · {{ sevName(d.severity) }} · <el-tag size="small" effect="plain" :type="defectStatusTag(d.status)">{{ defectStatus(d.status) }}</el-tag></span>',
      '                      <span>',
      '                        <el-button v-if="Number(d.autoGenerated)===1 && Number(d.status)===0" link type="success" size="small" @click="activateDefect(d)">激活</el-button>',
      '                        <el-button link type="primary" size="small" @click="dfOpenEdit(d)">编辑</el-button>',
      '                        <el-button link type="danger" size="small" @click="dfRemove(d)">删除</el-button>',
      '                      </span>',
      '                    </div>',
      '                  </div>',
      '                  <div v-if="!rv.defects.length && !rv.defectsLoading" class="eqc-note">暂无缺陷记录; 可点击「新增缺陷」登记人工发现的问题</div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '            <div style="display:flex;align-items:center;gap:14px;margin-top:14px;flex-wrap:wrap;">',
      '              <span style="font-size:13px;color:var(--yb-ink-2);">甲乙丙评级:</span>',
      '              <el-radio-group v-model="rv.grade">',
      '                <el-radio v-for="g in grades" :key="g" :label="g">{{ g }}级</el-radio>',
      '              </el-radio-group>',
      '              <el-input v-model="rv.opinion" placeholder="审核意见(可选)" size="small" style="width:300px;"></el-input>',
      '              <div style="flex:1;"></div>',
      '              <el-button size="small" @click="closeReview">取消</el-button>',
      '              <el-button size="small" type="primary" :loading="rv.finishing" @click="finishReview">完成审核</el-button>',
      '            </div>',
      '          </template>',
      '        </div>',
      '      </el-dialog>',

      /* ---- 缺陷编辑弹窗 ---- */
      '      <el-dialog v-model="dfDlg" :title="dfTitle" width="520px" append-to-body>',
      '        <el-form label-width="90px" size="small">',
      '          <el-form-item label="质控规则">',
      '            <el-select v-model="dfForm.ruleId" filterable style="width:100%;" @change="onDfRuleChange">',
      '              <el-option v-for="r in enabledRules" :key="r.id" :label="(r.ruleCode ? r.ruleCode + \' \' : \'\') + r.ruleName" :value="r.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="缺陷描述"><el-input v-model="dfForm.defectDesc" type="textarea" :rows="3" placeholder="缺陷描述(可自定义补充说明)"></el-input></el-form-item>',
      '          <el-row :gutter="12">',
      '            <el-col :span="10"><el-form-item label="扣分"><el-input-number v-model="dfForm.deductScore" :min="0" :max="100" controls-position="right" style="width:100%;"></el-input-number></el-form-item></el-col>',
      '            <el-col :span="10"><el-form-item label="严重度">',
      '              <el-select v-model="dfForm.severity" style="width:100%;"><el-option v-for="t in severities" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '            </el-form-item></el-col>',
      '          </el-row>',
      '        </el-form>',
      '        <template #footer>',
      '          <el-button size="small" @click="dfDlg=false">取消</el-button>',
      '          <el-button size="small" type="primary" :loading="dfSaving" @click="dfSave">保存</el-button>',
      '        </template>',
      '      </el-dialog>',

      /* ---- 下发整改通知弹窗 ---- */
      '      <el-dialog v-model="ntDlg" title="下发整改通知" width="520px" append-to-body>',
      '        <el-form label-width="90px" size="small">',
      '          <el-form-item label="病历ID"><el-input-number v-model="ntForm.recordId" :min="1" :controls="false" style="width:100%;" placeholder="病历ID"></el-input-number></el-form-item>',
      '          <el-form-item label="缺陷ID"><el-input v-model="ntForm.defectIdsText" type="textarea" :rows="2" placeholder="多个缺陷ID用逗号分隔(可在审核工作台缺陷卡片查看)"></el-input></el-form-item>',
      '          <el-form-item label="整改期限"><el-date-picker v-model="ntForm.deadline" type="date" value-format="YYYY-MM-DD" style="width:100%;"></el-date-picker></el-form-item>',
      '          <el-form-item label="通知说明"><el-input v-model="ntForm.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '        </el-form>',
      '        <template #footer>',
      '          <el-button size="small" @click="ntDlg=false">取消</el-button>',
      '          <el-button size="small" type="primary" :loading="ntSending" @click="ntSend">下发</el-button>',
      '        </template>',
      '      </el-dialog>',

      /* ---- 复核整改 / 处理申诉弹窗 ---- */
      '      <el-dialog v-model="ntReview.visible" title="复核整改" width="480px" append-to-body>',
      '        <el-radio-group v-model="ntReview.pass">',
      '          <el-radio :label="true">复核通过</el-radio>',
      '          <el-radio :label="false">驳回重改</el-radio>',
      '        </el-radio-group>',
      '        <el-input v-model="ntReview.opinion" type="textarea" :rows="3" placeholder="复核意见" class="eqc-mt8"></el-input>',
      '        <template #footer>',
      '          <el-button size="small" @click="ntReview.visible=false">取消</el-button>',
      '          <el-button size="small" type="primary" :loading="ntReview.saving" @click="ntDoReview">提交</el-button>',
      '        </template>',
      '      </el-dialog>',
      '      <el-dialog v-model="ntAppeal.visible" title="处理申诉" width="480px" append-to-body>',
      '        <el-radio-group v-model="ntAppeal.approve">',
      '          <el-radio :label="true">申诉成立(缺陷豁免)</el-radio>',
      '          <el-radio :label="false">申诉驳回</el-radio>',
      '        </el-radio-group>',
      '        <el-input v-model="ntAppeal.opinion" type="textarea" :rows="3" placeholder="处理意见" class="eqc-mt8"></el-input>',
      '        <template #footer>',
      '          <el-button size="small" @click="ntAppeal.visible=false">取消</el-button>',
      '          <el-button size="small" type="primary" :loading="ntAppeal.saving" @click="ntDoAppeal">提交</el-button>',
      '        </template>',
      '      </el-dialog>',

      /* ---- 通知详情弹窗 ---- */
      '      <el-dialog v-model="ntDetail.visible" title="整改通知详情" width="580px" append-to-body>',
      '        <el-descriptions v-if="ntDetail.row" :column="2" size="small" border>',
      '          <el-descriptions-item label="通知ID">{{ ntDetail.row.id }}</el-descriptions-item>',
      '          <el-descriptions-item label="病历ID">{{ ntDetail.row.recordId }}</el-descriptions-item>',
      '          <el-descriptions-item label="科室/病区">{{ ntDetail.row.deptName || ntDetail.row.wardName || \'-\' }}</el-descriptions-item>',
      '          <el-descriptions-item label="患者">{{ ntDetail.row.patientName || \'-\' }}</el-descriptions-item>',
      '          <el-descriptions-item label="状态"><el-tag size="small" :type="noticeStatusTag(ntDetail.row.status)">{{ noticeStatus(ntDetail.row.status) }}</el-tag></el-descriptions-item>',
      '          <el-descriptions-item label="整改期限">{{ ntDetail.row.deadline || \'-\' }}</el-descriptions-item>',
      '          <el-descriptions-item label="缺陷ID" :span="2">{{ Array.isArray(ntDetail.row.defectIds) ? ntDetail.row.defectIds.join(\', \') : (ntDetail.row.defectIds || \'-\') }}</el-descriptions-item>',
      '          <el-descriptions-item label="通知说明" :span="2">{{ ntDetail.row.remark || \'-\' }}</el-descriptions-item>',
      '          <el-descriptions-item label="整改记录" :span="2">{{ ntDetail.row.rectifyNote || \'暂无\' }}<div v-if="ntDetail.row.rectifyTime" class="eqc-note">整改时间: {{ ntDetail.row.rectifyTime }}</div></el-descriptions-item>',
      '          <el-descriptions-item label="申诉记录" :span="2">{{ ntDetail.row.appealReason || \'暂无\' }}<div v-if="ntDetail.row.appealHandleNote" class="eqc-note">处理意见: {{ ntDetail.row.appealHandleNote }}</div></el-descriptions-item>',
      '        </el-descriptions>',
      '      </el-dialog>',
      '    </div>',

      /* ===== M6 统计分析 ===== */
      '    <div v-if="module===\'stats\'">',
      '      <div class="eqc-hd">',
      '        <div>',
      '          <div class="eqc-hd-t">统计分析</div>',
      '          <div class="eqc-hd-s">质控 KPI / 甲乙丙等级分布 / 缺陷分布 / 科室与医生排行 / 月度环比 / CSV 导出</div>',
      '        </div>',
      '        <div style="display:flex;gap:8px;align-items:center;">',
      '          <el-date-picker v-model="stMonth" type="month" value-format="YYYY-MM" :clearable="false" style="width:130px;" @change="loadStats"></el-date-picker>',
      '          <el-button size="small" @click="loadStats">刷新</el-button>',
      '          <el-button size="small" @click="exportPerfCsv">导出质控员绩效</el-button>',
      '          <el-button size="small" @click="exportRectifyCsv">导出临床整改</el-button>',
      '        </div>',
      '      </div>',
      '      <div v-loading="stLoading" style="min-height:200px;">',
      '        <div class="eqc-kpis">',
      '          <div v-for="k in kpiCards" :key="k.t" class="eqc-kpi"><div class="eqc-kpi-t">{{ k.t }}</div><div class="eqc-kpi-n">{{ k.n }}</div><div class="eqc-kpi-s">{{ k.s }}</div></div>',
      '        </div>',
      '        <div class="eqc-cards2">',
      '          <div class="eqc-panel">',
      '            <div class="eqc-panel-t" style="margin-bottom:6px;">甲乙丙等级分布 <span class="eqc-note">(近似口径: 甲=90-100/乙=80-89/丙=其余)</span></div>',
      '            <div class="eqc-grade-strip">',
      '              <div v-for="g in gradeDist.rows" :key="g.g" class="eqc-grade-seg" :style="{width: (gradeDist.total ? (g.v*100/gradeDist.total) : 0) + \'%\', background: gradeDist.palette[g.g], minWidth: g.v ? \'26px\' : \'0\'}" :title="g.g + \' \' + g.v + \'份\'">{{ g.v ? g.g : \'\' }}</div>',
      '            </div>',
      '            <div class="eqc-legend">',
      '              <span v-for="g in gradeDist.rows" :key="g.g"><i :style="{background: gradeDist.palette[g.g]}"></i>{{ g.g }} {{ g.v }}份 ({{ gradeDist.total ? (g.v*100/gradeDist.total).toFixed(1) : 0 }}%)</span>',
      '              <span class="eqc-note">共 {{ gradeDist.total }} 份已评分</span>',
      '            </div>',
      '          </div>',
      '          <div class="eqc-panel">',
      '            <div class="eqc-panel-t" style="margin-bottom:6px;">缺陷严重度分布</div>',
      '            <template v-if="defectSeverityRows.length">',
      '              <div v-for="s in defectSeverityRows" :key="s.name" class="eqc-bar-row">',
      '                <span class="eqc-bar-name">{{ s.name || \'未知\' }}</span>',
      '                <div class="eqc-bar-track"><div class="eqc-bar-fill" :style="{width: barPct(s.value, stDefects && stDefects.total) + \'%\'}"></div></div>',
      '                <span class="eqc-bar-val">{{ s.value }}{{ stDefects && stDefects.total ? \' (\' + (s.value*100/stDefects.total).toFixed(1) + \'%)\' : \'\' }}</span>',
      '              </div>',
      '            </template>',
      '            <div v-else class="eqc-note">本月暂无缺陷数据</div>',
      '          </div>',
      '        </div>',
      '        <div class="eqc-panel">',
      '          <div class="eqc-panel-t" style="margin-bottom:6px;">缺陷类型分布</div>',
      '          <template v-if="defectTypeRows.length">',
      '            <div v-for="t in defectTypeRows.slice(0, 12)" :key="t.name" class="eqc-bar-row">',
      '              <span class="eqc-bar-name" :title="t.name">{{ t.name }}</span>',
      '              <div class="eqc-bar-track"><div class="eqc-bar-fill" :style="{width: barPct(t.value, defectTypeRows[0].value) + \'%\'}"></div></div>',
      '              <span class="eqc-bar-val">{{ t.value }}</span>',
      '            </div>',
      '          </template>',
      '          <div v-else class="eqc-note">本月暂无缺陷数据</div>',
      '        </div>',
      '        <div class="eqc-cards2">',
      '          <div class="eqc-panel">',
      '            <div class="eqc-panel-t" style="margin-bottom:8px;">缺陷科室 Top10</div>',
      '            <template v-if="defectDeptRows.length">',
      '              <div v-for="(d, i) in defectDeptRows" :key="d.deptId" class="eqc-bar-row">',
      '                <span class="eqc-bar-name" :title="d.deptName">{{ i + 1 }}. {{ d.deptName || \'-\' }}</span>',
      '                <div class="eqc-bar-track"><div class="eqc-bar-fill" :style="{width: barPct(d.count, defectDeptRows[0].count) + \'%\', background: \'var(--yb-fill-danger)\'}"></div></div>',
      '                <span class="eqc-bar-val">{{ d.count }}</span>',
      '              </div>',
      '            </template>',
      '            <div v-else class="eqc-note">暂无数据</div>',
      '          </div>',
      '          <div class="eqc-panel">',
      '            <div class="eqc-panel-t" style="margin-bottom:8px;">月度环比(本月 vs 上月)</div>',
      '            <el-table :data="monthRows" size="small" border>',
      '              <el-table-column label="指标" prop="k" min-width="90"></el-table-column>',
      '              <el-table-column label="本月" min-width="80"><template #default="s">{{ s.row.cText }}</template></el-table-column>',
      '              <el-table-column label="上月" min-width="80"><template #default="s">{{ s.row.pText }}</template></el-table-column>',
      '              <el-table-column label="环比" min-width="120">',
      '                <template #default="s"><span :style="{color: s.row.diffText===\'—\'?\'var(--yb-ink-4)\':(s.row.up?\'var(--yb-danger)\':\'var(--yb-success)\')}">{{ s.row.diffText }} {{ s.row.pctText }}</span></template>',
      '              </el-table-column>',
      '            </el-table>',
      '          </div>',
      '        </div>',
      '        <div class="eqc-panel">',
      '          <div class="eqc-panel-t" style="margin-bottom:8px;">科室排行(点击列头排序)</div>',
      '          <el-table :data="deptRankRows" size="small" border stripe>',
      '            <el-table-column label="排名" width="64" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '            <el-table-column label="科室" prop="deptName" min-width="120" sortable></el-table-column>',
      '            <el-table-column label="病历数" prop="recordCount" width="90" align="center" sortable></el-table-column>',
      '            <el-table-column label="已评分" prop="evaluatedCount" width="90" align="center" sortable></el-table-column>',
      '            <el-table-column label="平均分" width="100" align="center" sortable :sort-method="function(a, b){ return Number(a.avgScore || 0) - Number(b.avgScore || 0) }">',
      '              <template #default="s">{{ s.row.avgScore == null ? \'-\' : Number(s.row.avgScore).toFixed(1) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="缺陷数" prop="defectCount" width="90" align="center" sortable></el-table-column>',
      '            <el-table-column label="缺陷密度" prop="defectDensity" width="100" align="center" sortable></el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="eqc-panel">',
      '          <div class="eqc-panel-t" style="margin-bottom:8px;">医生排行 <span v-if="stDoctorNote" class="eqc-note">({{ stDoctorNote }})</span></div>',
      '          <el-table v-if="stDoctorRows.length" :data="stDoctorRows" size="small" border stripe>',
      '            <el-table-column label="排名" width="64" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '            <el-table-column label="医生" min-width="100"><template #default="s">{{ s.row.doctorName || s.row.name || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="科室" min-width="110"><template #default="s">{{ s.row.deptName || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="病历数" width="90" align="center"><template #default="s">{{ s.row.recordCount != null ? s.row.recordCount : \'-\' }}</template></el-table-column>',
      '            <el-table-column label="平均分" width="100" align="center"><template #default="s">{{ s.row.avgScore == null ? \'-\' : Number(s.row.avgScore).toFixed(1) }}</template></el-table-column>',
      '            <el-table-column label="甲级率" width="100" align="center"><template #default="s">{{ s.row.gradeARate != null ? (Number(s.row.gradeARate)*100).toFixed(1) + \'%\' : \'-\' }}</template></el-table-column>',
      '          </el-table>',
      '          <div v-else class="eqc-note">医生排行数据(预估端点 /quality/doctor-ranking)就绪后展示</div>',
      '        </div>',
      '      </div>',
      '    </div>',

      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
