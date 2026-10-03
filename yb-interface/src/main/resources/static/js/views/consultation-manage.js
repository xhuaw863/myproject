/* 会诊管理页(P6-3): HIS.views.ConsultationManage —— 全院会诊流转驾驶舱(多维查询/统计分析/超时预警 三页签)。
 * 后端契约(P6-2 已交付, ConsultationFlowController): /api/his/consultation/*
 *   POST /apply · PUT /{id}/accept|complete|reject|cancel|evaluate|urge|link-order · POST /check-timeout
 *   GET /list(分页 IPage) · GET /by-visit · GET /statistics · GET /{id}
 * 列表行契约(HisInpConsultation 实体): {id, visitType(1住院/2门诊), visitId, applyDeptId/Name, applyDoctorId/Name,
 *   targetDeptId/Name, targetDoctorId/Name, consultCategory(实体 String 型字符串码: within_dept科内/cross_dept科间/
 *   external院外/mdt, 展示层兼容数字码1/2/3/4), urgencyLevel(1普通/2急/3特急), status(1申请/2受理/3完成/4拒绝/5取消),
 *   applyReason, applySummary, consultOpinion, applyTime, responseTime, consultTime(完成时间), responseDeadline,
 *   timeoutNotified(0/1), evalByApplicant(+Note)/evalByInvitee(+Note)/evalTime(双向评价), consultRecordId, orderId}
 * 统计 /statistics(P6-2 已交付): {total, byStatus, byType(consult_type非 consultCategory), byUrgency, avgResponseMinutes,
 *   timeoutCount, timeoutRate(百分数0-100), topApplyDepts[], topTargetDepts[], topDoctors[](申请医师Top10),
 *   urgentResponded, urgentOnTime, urgentOnTimeRate(百分数), avgEvalApplicant, avgEvalInvitee} ——
 *   会诊类型分布(consultCategory口径)与医生受邀排行后端未覆盖, 由区间明细(≤500条)前端兑底计算。
 * 预警口径: 处理中(status 1/2)且 timeout_notified=1 或前端按 deadline 判定已超时 → 红区; 响应时限剩余≤30分钟 → 黄区。
 *   时限: 特急即刻/急10分钟/普通24小时(后端 response_deadline 落库, 无值时按 applyTime+时限推算)。
 * 列表规范: 序号列跨页连续 · el-pagination 带 sizes · 默认20行/页([10,20,50,100])。注册: HIS.views.ConsultationManage(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 常量 ================= */
  var API = '/api/his/consultation';

  /* 会诊分类(实体 VARCHAR 字符串码, P6-2/P6-4 提交主口径): 展示层兼容数字码1/2/3/4 */
  var CATEGORY = { within_dept: '科内会诊', cross_dept: '科间会诊', external: '院外会诊', mdt: 'MDT会诊', '1': '科内会诊', '2': '科间会诊', '3': '院外会诊', '4': 'MDT会诊' };
  var CATEGORY_TAG = { within_dept: 'info', cross_dept: '', external: 'warning', mdt: 'success', '1': 'info', '2': '', '3': 'warning', '4': 'success' };
  var STATUS = { 1: '已申请', 2: '已受理', 3: '已完成', 4: '已拒绝', 5: '已取消' };
  /* 申请蓝/受理黄/完成绿/拒绝红/取消灰 */
  var STATUS_TAG = { 1: '', 2: 'warning', 3: 'success', 4: 'danger', 5: 'info' };
  var URGENCY = { 1: '普通', 2: '急', 3: '特急' };
  /* 普通灰/急橙/特急红 */
  var URGENCY_TAG = { 1: 'info', 2: 'warning', 3: 'danger' };
  var VISIT_TYPE = { 1: '住院', 2: '门诊' };
  /* 响应时限 SLA(分钟): 无 responseDeadline 时按申请时间 + SLA 推算(与后端 computeDeadline 同口径: 特急即刻/急10分钟/普通24小时) */
  var SLA_MIN = { 1: 1440, 2: 10, 3: 0 };
  var NEAR_MIN = 30; /* 临近超时阈值(分钟) */

  var CATEGORY_OPTS = [{ v: 'within_dept', l: '科内会诊' }, { v: 'cross_dept', l: '科间会诊' }, { v: 'external', l: '院外会诊' }, { v: 'mdt', l: 'MDT会诊' }];
  var URGENCY_OPTS = [{ v: 1, l: '普通' }, { v: 2, l: '急' }, { v: 3, l: '特急' }];
  var STATUS_OPTS = [{ v: 1, l: '已申请' }, { v: 2, l: '已受理' }, { v: 3, l: '已完成' }, { v: 4, l: '已拒绝' }, { v: 5, l: '已取消' }];
  var VISIT_TYPE_OPTS = [{ v: 1, l: '住院' }, { v: 2, l: '门诊' }];

  /* ================= 工具 ================= */
  function pad2(n) { return ('0' + n).slice(-2); }
  function currentMonth() { var d = new Date(); return d.getFullYear() + '-' + pad2(d.getMonth() + 1); }
  function monthRange(m) {
    var y = Number(String(m).slice(0, 4)), mo = Number(String(m).slice(5, 7));
    var last = new Date(y, mo, 0).getDate();
    return [m + '-01', m + '-' + pad2(last)];
  }
  function parseT(v) {
    if (!v) { return null; }
    var d = new Date(String(v).replace('T', ' ').replace(/-/g, '/'));
    return isNaN(d.getTime()) ? null : d;
  }
  function timeText(v) {
    if (!v) { return '-'; }
    var s = String(v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }
  function fmtDT(d) { return d ? d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) : '-'; }
  /* 两时间差(分钟), 任一端缺失返回 null */
  function minutesBetween(a, b) {
    var d1 = parseT(a), d2 = parseT(b);
    if (!d1 || !d2) { return null; }
    return Math.max(0, Math.round((d2.getTime() - d1.getTime()) / 60000));
  }
  function fmtDur(mins) {
    if (mins == null) { return '-'; }
    mins = Math.max(0, Math.round(mins));
    if (mins < 1) { return '1分钟内'; }
    var d = Math.floor(mins / 1440), h = Math.floor((mins % 1440) / 60), m = mins % 60;
    if (d) { return d + '天' + (h ? h + '小时' : ''); }
    if (h) { return h + '小时' + (m ? m + '分' : ''); }
    return m + '分钟';
  }
  /* 多候选取第一个有效数值 */
  function pickNum() {
    for (var i = 0; i < arguments.length; i++) {
      var v = arguments[i];
      if (v != null && v !== '' && !isNaN(Number(v))) { return Number(v); }
    }
    return null;
  }
  function pickArr(o, keys) {
    if (!o) { return null; }
    for (var i = 0; i < keys.length; i++) { var v = o[keys[i]]; if (Array.isArray(v)) { return v; } }
    return null;
  }
  /* 服务端排行项 → {name,count} 归一(兼容 name/deptName/doctorName/label/category 等键, count 兼容 cnt) */
  function normRank(arr) {
    return (arr || []).map(function (x) {
      if (x == null) { return { name: '-', count: 0 }; }
      if (typeof x !== 'object') { return { name: String(x), count: 0 }; }
      var code = x.category != null ? x.category : (x.cat != null ? x.cat : x.consultCategory);
      return {
        name: x.name || x.deptName || x.doctorName || (code != null ? CATEGORY[String(code)] : null) || x.label || x.key || '-',
        count: pickNum(x.count, x.value, x.num, x.cnt) || 0
      };
    });
  }
  function rankOf(map, topN) {
    var arr = [];
    for (var k in map) { if (Object.prototype.hasOwnProperty.call(map, k)) { arr.push({ name: k, count: map[k] }); } }
    arr.sort(function (a, b) { return b.count - a.count; });
    return arr.slice(0, topN || 10);
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

  /* ================= 私有样式(cm- 前缀, 一次性注入不碰公共 css) ================= */
  (function ensureConsultStyles() {
    if (document.getElementById('cm-styles')) { return; }
    var css = [
      /* 筛选区/表格/分页: 配合 .cd-fill.cd-tabs 的 flex 高度链 */
      '.cm-filter{flex:none;background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:8px;padding:12px 12px 0;margin-bottom:10px;}',
      '.cm-filter .el-form-item{margin-bottom:10px;margin-right:12px;}',
      '.cm-table-wrap{flex:1;min-height:0;}',
      '.cm-pager{flex:none;display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:8px 2px 0;}',
      '.cm-note{font-size:12px;color:var(--yb-ink-4);line-height:1.7;}',
      /* 超时行浅红(覆盖斑马纹与 hover, 色值取 --yb-fill-danger 同族) */
      '.el-table .cm-row-timeout > td{background:#fdf0ef !important;}',
      '.el-table .cm-row-timeout:hover > td{background:#fbe3e1 !important;}',
      /* 统计页 */
      '.cm-scroll{flex:1;min-height:0;overflow:auto;padding:2px 2px 24px;}',
      '.cm-stats-bar{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:12px;flex-wrap:wrap;}',
      '.cm-kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:12px;}',
      '.cm-kpi{background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:10px;padding:12px 14px;}',
      '.cm-kpi-t{font-size:12px;color:var(--yb-ink-3);}',
      '.cm-kpi-n{font-size:22px;font-weight:700;color:var(--yb-ink-1);margin-top:4px;}',
      '.cm-kpi-s{font-size:11px;color:var(--yb-ink-4);margin-top:2px;}',
      '.cm-grid2{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px;}',
      '.cm-panel{background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:10px;padding:14px;}',
      '.cm-panel-t{font-size:13px;font-weight:600;color:var(--yb-ink-1);}',
      '.cm-bar-row{display:flex;align-items:center;gap:8px;margin:6px 0;font-size:12px;}',
      '.cm-bar-name{width:118px;flex:none;text-align:right;color:var(--yb-ink-2);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
      '.cm-bar-track{flex:1;height:14px;background:var(--yb-surface-3);border-radius:4px;overflow:hidden;}',
      '.cm-bar-fill{height:100%;border-radius:4px;background:var(--yb-fill-info);min-width:2px;transition:width .4s;}',
      '.cm-bar-val{width:92px;flex:none;color:var(--yb-ink-3);white-space:nowrap;}',
      '.cm-big-num{font-size:26px;font-weight:700;color:var(--yb-danger);margin:4px 0 8px;}',
      /* 预警页双区 */
      '.cm-zone{border-radius:10px;padding:12px 14px;margin-bottom:12px;border:1px solid;}',
      '.cm-zone-danger{border-color:#f2cfcf;background:#fdf4f3;}',
      '.cm-zone-warn{border-color:var(--yb-warning-border);background:var(--yb-warning-bg);}',
      '.cm-zone-hd{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:10px;flex-wrap:wrap;}',
      '.cm-zone-t{font-size:13px;font-weight:700;}',
      '.cm-zone-danger .cm-zone-t{color:var(--yb-danger);}',
      '.cm-zone-warn .cm-zone-t{color:var(--yb-warning-strong);}',
      /* 详情抽屉 */
      '.cm-sec-t{font-size:13px;font-weight:600;color:var(--yb-ink-1);margin:16px 0 8px;padding-left:8px;border-left:3px solid var(--yb-brand);}',
      '.cm-dl{display:grid;grid-template-columns:1fr 1fr;gap:0 22px;}',
      '.cm-dl .it{border-bottom:1px dashed var(--yb-divider);padding:6px 0;}',
      '.cm-dl .k{font-size:12px;color:var(--yb-ink-3);}',
      '.cm-dl .v{font-size:13px;color:var(--yb-ink-1);font-weight:500;margin-top:2px;}',
      '.cm-block{background:var(--yb-surface-2);border:1px solid var(--yb-divider);border-radius:8px;padding:10px 12px;font-size:13px;line-height:1.8;color:var(--yb-ink-1);white-space:pre-wrap;word-break:break-all;}',
      '.cm-eval-score{font-size:20px;font-weight:700;color:var(--yb-brand);margin:6px 0 4px;}'
    ].join('\n');
    var el = document.createElement('style');
    el.id = 'cm-styles';
    el.textContent = css;
    document.head.appendChild(el);
  })();

  /* ================= 组件 ================= */
  HIS.views.ConsultationManage = {
    name: 'ConsultationManage',
    data: function () {
      return {
        tab: 'list',
        loading: false,
        stLoading: false,
        warnLoading: false,
        exporting: false,
        /* 筛选条件(默认本月) */
        f: { dateRange: monthRange(currentMonth()), applyDeptId: null, targetDeptId: null, category: null, urgency: null, status: null, visitType: null },
        /* 参考数据 */
        depts: [],
        staffs: [],
        /* 列表(默认20行/页) */
        rows: [],
        total: 0,
        page: 1,
        size: 20,
        /* 详情抽屉 */
        dtVisible: false,
        dtLoading: false,
        dtRow: null,
        /* 统计: 服务端 /statistics 原始 + 本月明细兜底 */
        st: {},
        stRows: [],
        /* 预警 */
        warnOverdue: [],
        warnNearing: [],
        /* 下拉选项(常量注入, 避免模板直引文件级变量) */
        categoryOpts: CATEGORY_OPTS,
        urgencyOpts: URGENCY_OPTS,
        statusOpts: STATUS_OPTS,
        visitTypeOpts: VISIT_TYPE_OPTS
      };
    },
    computed: {
      deptMap: function () {
        var m = {};
        (this.depts || []).forEach(function (d) { m[HIS.idKey(d.id)] = d.deptName; });
        return m;
      },
      staffMap: function () {
        var m = {};
        (this.staffs || []).forEach(function (s) { m[HIS.idKey(s.id)] = s.staffName; });
        return m;
      },
      /* 本月明细兜底统计(服务端 /statistics 字段缺失时的前端计算口径) */
      statsFallback: function () {
        var vm = this;
        var rows = this.stRows || [];
        var out = { total: rows.length, urgentTotal: 0, urgentOk: 0, respSum: 0, respCnt: 0, scoreSum: 0, scoreCnt: 0, pending: 0, overdue: 0, applyDept: {}, targetDept: {}, cat: {}, docApply: {}, docTarget: {} };
        var now = Date.now();
        rows.forEach(function (r) {
          var ck = r.consultCategory == null ? '-' : String(r.consultCategory);
          out.cat[ck] = (out.cat[ck] || 0) + 1;
          var an = r.applyDeptName || vm.deptText(r.applyDeptId);
          if (an && an !== '-') { out.applyDept[an] = (out.applyDept[an] || 0) + 1; }
          var tn = r.targetDeptName || vm.deptText(r.targetDeptId);
          if (tn && tn !== '-') { out.targetDept[tn] = (out.targetDept[tn] || 0) + 1; }
          var ad = r.applyDoctorName || vm.doctorText(r.applyDoctorId);
          if (ad && ad !== '-') { out.docApply[ad] = (out.docApply[ad] || 0) + 1; }
          var td = r.targetDoctorName || vm.doctorText(r.targetDoctorId);
          if (td && td !== '-') { out.docTarget[td] = (out.docTarget[td] || 0) + 1; }
          var resp = minutesBetween(r.applyTime, r.responseTime);
          if (resp != null) { out.respSum += resp; out.respCnt++; }
          if (Number(r.urgencyLevel) >= 2) {
            out.urgentTotal++;
            if (resp != null && resp <= 10) { out.urgentOk++; }
          }
          var sc = vm.scoreNum(r);
          if (sc != null) { out.scoreSum += sc; out.scoreCnt++; }
          var stt = Number(r.status);
          if (stt === 1 || stt === 2) {
            out.pending++;
            var dl = vm.deadlineOf(r);
            if (Number(r.timeoutNotified) === 1 || (dl && now > dl.getTime())) { out.overdue++; }
          }
        });
        return out;
      },
      /* KPI 四卡(服务端键优先, 后端 urgentOnTimeRate/timeoutRate 恒为百分数0-100不做小数换算) */
      stKpis: function () {
        var s = this.st || {};
        var fb = this.statsFallback;
        var total = pickNum(s.total, s.consultTotal);
        if (total == null) { total = fb.total; }
        var rate = pickNum(s.urgentOnTimeRate, s.urgentResponseRate, s.responseOnTimeRate, s.onTimeRate);
        if (rate == null) { rate = fb.urgentTotal ? fb.urgentOk * 100 / fb.urgentTotal : null; }
        var avgResp = pickNum(s.avgResponseMinutes, s.avgRespMinutes, s.avgResponse);
        if (avgResp == null) { avgResp = fb.respCnt ? fb.respSum / fb.respCnt : null; }
        /* 双向评价均分: 服务端申请方/受邀方均分取均值, 仅单侧存在取单侧 */
        var avgScore = pickNum(s.avgScore, s.avgEvalScore);
        if (avgScore == null) {
          var sa = pickNum(s.avgEvalApplicant), sb = pickNum(s.avgEvalInvitee);
          if (sa != null && sb != null) { avgScore = (sa + sb) / 2; }
          else if (sa != null) { avgScore = sa; }
          else if (sb != null) { avgScore = sb; }
        }
        if (avgScore == null) { avgScore = fb.scoreCnt ? fb.scoreSum / fb.scoreCnt : null; }
        return [
          { t: '会诊总数', n: String(total), s: '统计区间内全部会诊' },
          { t: '急会诊响应达标率', n: rate == null ? '-' : rate.toFixed(1) + '%', s: '急/特急 10 分钟内响应占比' },
          { t: '平均响应时间', n: avgResp == null ? '-' : avgResp.toFixed(1), s: '单位: 分钟(申请 → 受理)' },
          { t: '双向评价均分', n: avgScore == null ? '-' : avgScore.toFixed(1), s: '申请/受邀双向评分均值' }
        ];
      },
      applyDeptBars: function () {
        var arr = pickArr(this.st, ['topApplyDepts', 'applyDeptRank', 'applyDeptTop', 'byApplyDept', 'deptApplyRank']);
        if (arr && arr.length) { return normRank(arr).slice(0, 10); }
        return rankOf(this.statsFallback.applyDept, 10);
      },
      targetDeptBars: function () {
        var arr = pickArr(this.st, ['topTargetDepts', 'targetDeptRank', 'targetDeptTop', 'byTargetDept', 'deptTargetRank']);
        if (arr && arr.length) { return normRank(arr).slice(0, 10); }
        return rankOf(this.statsFallback.targetDept, 10);
      },
      categoryBars: function () {
        /* 后端 byType 为 consult_type(普通/急/MDT)分组, 与 consultCategory 分布语义不同, 不采用 */
        var arr = pickArr(this.st, ['categoryDist', 'typeDist', 'byCategory', 'consultCategoryDist']);
        if (arr && arr.length) { return normRank(arr); }
        var fb = this.statsFallback.cat;
        return ['within_dept', 'cross_dept', 'external', 'mdt'].map(function (k) { return { name: CATEGORY[k], count: fb[k] || 0 }; });
      },
      timeoutInfo: function () {
        var s = this.st || {};
        var fb = this.statsFallback;
        /* 后端 timeoutRate 恒为百分数(0-100, 超时已通知占全部会诊比), 不做小数换算 */
        var rate = pickNum(s.timeoutRate, s.timeoutRatio, s.overdueRate);
        if (rate == null) { rate = fb.pending ? fb.overdue * 100 / fb.pending : null; }
        var count = pickNum(s.timeoutCount, s.overdueCount);
        if (count == null) { count = fb.overdue; }
        return { rate: rate, rateText: rate == null ? '-' : rate.toFixed(1) + '%', count: count, pending: fb.pending };
      },
      doctorApplyRows: function () {
        var arr = pickArr(this.st, ['topDoctors', 'doctorApplyRank', 'applyDoctorRank', 'byApplyDoctor']);
        if (arr && arr.length) { return normRank(arr).slice(0, 10); }
        return rankOf(this.statsFallback.docApply, 10);
      },
      doctorTargetRows: function () {
        var arr = pickArr(this.st, ['doctorTargetRank', 'targetDoctorRank', 'byTargetDoctor']);
        if (arr && arr.length) { return normRank(arr).slice(0, 10); }
        return rankOf(this.statsFallback.docTarget, 10);
      }
    },
    created: function () {
      this.loadRefs();
    },
    mounted: function () {
      this.loadList();
    },
    methods: {
      timeText: timeText,
      fmtDT: fmtDT, /* 文件级助手须以 methods 暴露, 模板仅访问组件作用域 */
      /* ---------- 翻译 ---------- */
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      deptText: function (id, name) {
        if (name) { return name; }
        return id == null ? '-' : (this.deptMap[HIS.idKey(id)] || String(id));
      },
      doctorText: function (id, name) {
        if (name) { return name; }
        return id == null ? '-' : (this.staffMap[HIS.idKey(id)] || String(id));
      },
      typeText: function (v) { return v == null || v === '' ? '-' : (CATEGORY[String(v)] || '-'); },
      typeTag: function (v) { var t = CATEGORY_TAG[String(v)]; return t === undefined ? 'info' : t; },
      urgencyText: function (v) { return URGENCY[Number(v)] || '-'; },
      urgencyTag: function (v) { return URGENCY_TAG[Number(v)] || 'info'; },
      statusText: function (v) { return STATUS[Number(v)] || '-'; },
      statusTag: function (v) { var t = STATUS_TAG[Number(v)]; return t === undefined ? 'info' : t; },
      visitTypeText: function (v) { return v == null || v === '' ? '-' : (VISIT_TYPE[Number(v)] || String(v)); },
      /* 双向评价: evaluations[] 优先(预估契约), 兑底实体字段 evalByApplicant/evalByInvitee(P6-2 已交付) */
      evalInfo: function (row, type) {
        if (!row) { return { score: null, note: '' }; }
        var ev = row.evaluations;
        if (Array.isArray(ev)) {
          for (var i = 0; i < ev.length; i++) {
            if (Number(ev[i] && ev[i].evaluatorType) === type) {
              return { score: pickNum(ev[i].score, ev[i].evalScore), note: ev[i].note || ev[i].evalNote || '' };
            }
          }
        }
        if (type === 1) { return { score: pickNum(row.evalByApplicant, row.applyScore), note: row.evalByApplicantNote || row.applyEvalNote || '' }; }
        return { score: pickNum(row.evalByInvitee, row.targetScore), note: row.evalByInviteeNote || row.targetEvalNote || '' };
      },
      scoreNum: function (row) {
        var arr = [];
        var a = this.evalInfo(row, 1).score, b = this.evalInfo(row, 2).score;
        if (a != null) { arr.push(a); }
        if (b != null) { arr.push(b); }
        if (!arr.length) { return null; }
        return arr.reduce(function (x, y) { return x + y; }, 0) / arr.length;
      },
      scoreText: function (row) {
        var n = this.scoreNum(row);
        return n == null ? '-' : n.toFixed(1);
      },
      /* ---------- 时限/预警口径 ---------- */
      deadlineOf: function (row) {
        if (!row) { return null; }
        var d = parseT(row.responseDeadline);
        if (d) { return d; }
        var ap = parseT(row.applyTime);
        if (!ap) { return null; }
        return new Date(ap.getTime() + (SLA_MIN[Number(row.urgencyLevel)] || 1440) * 60000);
      },
      isOverdue: function (row) {
        var stt = Number(row && row.status);
        if (stt !== 1 && stt !== 2) { return false; }
        if (Number(row.timeoutNotified) === 1) { return true; }
        var dl = this.deadlineOf(row);
        return !!dl && Date.now() > dl.getTime();
      },
      rowClass: function (ctx) { return this.isOverdue(ctx.row) ? 'cm-row-timeout' : ''; },
      overText: function (row) {
        var dl = this.deadlineOf(row);
        if (!dl) { return '-'; }
        return fmtDur((Date.now() - dl.getTime()) / 60000);
      },
      nearText: function (row) {
        var dl = this.deadlineOf(row);
        if (!dl) { return '-'; }
        return '剩 ' + fmtDur((dl.getTime() - Date.now()) / 60000);
      },
      /* ---------- 列表 ---------- */
      rangeText: function (sep) {
        var dr = this.f.dateRange;
        return (dr && dr.length === 2) ? (dr[0] + (sep || '~') + dr[1]) : '本月';
      },
      buildListQuery: function () {
        var p = ['page=' + this.page, 'size=' + this.size];
        var f = this.f;
        if (f.dateRange && f.dateRange.length === 2) {
          p.push('startDate=' + encodeURIComponent(f.dateRange[0]));
          p.push('endDate=' + encodeURIComponent(f.dateRange[1]));
        }
        if (f.applyDeptId != null && f.applyDeptId !== '') { p.push('applyDeptId=' + HIS.idParam(f.applyDeptId)); }
        if (f.targetDeptId != null && f.targetDeptId !== '') { p.push('targetDeptId=' + HIS.idParam(f.targetDeptId)); }
        if (f.category != null && f.category !== '') { p.push('consultCategory=' + f.category); }
        if (f.urgency != null && f.urgency !== '') { p.push('urgencyLevel=' + f.urgency); }
        if (f.status != null && f.status !== '') { p.push('status=' + f.status); }
        if (f.visitType != null && f.visitType !== '') { p.push('visitType=' + f.visitType); }
        return p.join('&');
      },
      loadList: function () {
        var vm = this;
        vm.loading = true;
        HIS.get(API + '/list?' + vm.buildListQuery()).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = Number((d && d.total) || 0);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onSearch: function () { this.page = 1; this.loadList(); },
      onReset: function () {
        this.f = { dateRange: monthRange(currentMonth()), applyDeptId: null, targetDeptId: null, category: null, urgency: null, status: null, visitType: null };
        this.onSearch();
      },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },
      onTab: function () {
        if (this.tab === 'stats') { this.loadStats(); }
        else if (this.tab === 'warn') { this.loadWarnings(); }
      },
      /* ---------- 参考数据 ---------- */
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (list) { vm.depts = list || []; }).catch(function () { });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师')).then(function (list) { vm.staffs = list || []; }).catch(function () { });
      },
      /* ---------- 详情抽屉 ---------- */
      openDetail: function (row) {
        var vm = this;
        vm.dtRow = row;
        vm.dtVisible = true;
        vm.dtLoading = true;
        HIS.get(API + '/' + HIS.idParam(row.id)).then(function (d) {
          if (d) { vm.dtRow = Object.assign({}, row, d); }
        }).catch(function () { /* 详情端点不可用时保留列表行数据展示 */ })
          .finally(function () { vm.dtLoading = false; });
      },
      /* ---------- 催促(P6-2 已交付端点 PUT /{id}/urge: 向受邀科室重发SSE催促提醒, 不改状态/时限) ---------- */
      urge: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认向「' + vm.deptText(row.targetDeptId, row.targetDeptName) + '」发送催促提醒？',
          '催促会诊', { type: 'warning', confirmButtonText: '发送催促', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.put(API + '/' + HIS.idParam(row.id) + '/urge');
        }).then(function () {
          HIS.notifySuccess('已发送催促提醒');
          if (vm.tab === 'warn') { vm.loadWarnings(); }
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ---------- 统计 ---------- */
      buildStatQuery: function () {
        var p = [];
        var dr = this.f.dateRange;
        if (dr && dr.length === 2) {
          p.push('startDate=' + encodeURIComponent(dr[0]));
          p.push('endDate=' + encodeURIComponent(dr[1]));
        }
        return p.join('&');
      },
      fetchMonthRows: function () {
        var vm = this;
        var q = ['page=1', 'size=500'];
        var dr = vm.f.dateRange;
        if (dr && dr.length === 2) {
          q.push('startDate=' + encodeURIComponent(dr[0]));
          q.push('endDate=' + encodeURIComponent(dr[1]));
        }
        return HIS.get(API + '/list?' + q.join('&'))
          .then(function (d) { return (d && d.records) || []; })
          .catch(function () { return []; });
      },
      loadStats: function () {
        var vm = this;
        vm.stLoading = true;
        Promise.all([
          HIS.get(API + '/statistics?' + vm.buildStatQuery()).catch(function () { return {}; }),
          vm.fetchMonthRows()
        ]).then(function (rs) {
          vm.st = rs[0] || {};
          vm.stRows = rs[1] || [];
        }).catch(HIS.notifyError).finally(function () { vm.stLoading = false; });
      },
      barPct: function (v, rows) {
        var max = rows && rows.length ? Number(rows[0].count) || 0 : 0;
        return max ? Math.round(v * 100 / max * 10) / 10 : 0;
      },
      catTotal: function () {
        var t = 0;
        (this.categoryBars || []).forEach(function (b) { t += Number(b.count) || 0; });
        return t;
      },
      catPct: function (v) {
        var t = this.catTotal();
        return t ? (v * 100 / t).toFixed(1) + '%' : '0%';
      },
      exportCsv: function () {
        var vm = this;
        var rows = [];
        vm.stKpis.forEach(function (k) { rows.push(['KPI', k.t, k.n]); });
        rows.push(['超时统计', '超时率', vm.timeoutInfo.rateText]);
        rows.push(['超时统计', '已超时(处理中)', vm.timeoutInfo.count]);
        rows.push(['超时统计', '处理中会诊数', vm.timeoutInfo.pending]);
        [
          ['科室申请排行', vm.applyDeptBars],
          ['受邀科室排行', vm.targetDeptBars],
          ['会诊类型分布', vm.categoryBars],
          ['医生申请排行', vm.doctorApplyRows],
          ['医生受邀排行', vm.doctorTargetRows]
        ].forEach(function (sec) {
          (sec[1] || []).forEach(function (b, i) { rows.push([sec[0], (i + 1) + '. ' + b.name, b.count]); });
        });
        vm.exporting = true;
        downloadCsv('会诊统计_' + vm.rangeText('_') + '.csv', toCsv(['分类', '项目', '数量/数值'], rows));
        HIS.notifySuccess('CSV 已导出');
        vm.exporting = false;
      },
      /* ---------- 超时预警 ---------- */
      fetchPending: function (status) {
        /* timeout=1 为预估入参: 后端支持则服务端过滤, 未支持时由前端按 deadline 兜底判定 */
        return HIS.get(API + '/list?status=' + status + '&page=1&size=200&timeout=1')
          .then(function (d) { return (d && d.records) || []; })
          .catch(function () { return []; });
      },
      loadWarnings: function () {
        var vm = this;
        vm.warnLoading = true;
        Promise.all([vm.fetchPending(1), vm.fetchPending(2)]).then(function (rs) {
          var all = (rs[0] || []).concat(rs[1] || []);
          var now = Date.now();
          var od = [], nr = [];
          all.forEach(function (r) {
            var dl = vm.deadlineOf(r);
            if (!dl) { return; }
            var t = dl.getTime();
            if (vm.isOverdue(r)) { od.push(r); }
            else if (t - now <= NEAR_MIN * 60000) { nr.push(r); }
          });
          function byDl(a, b) {
            var da = vm.deadlineOf(a), db = vm.deadlineOf(b);
            return (da ? da.getTime() : 0) - (db ? db.getTime() : 0);
          }
          od.sort(byDl);
          nr.sort(byDl);
          vm.warnOverdue = od;
          vm.warnNearing = nr;
        }).catch(HIS.notifyError).finally(function () { vm.warnLoading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill cd-tabs">',
      '  <div class="page-title">会诊管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">全院会诊流转 · 多维检索 · 统计预警</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',

      /* ===== Tab1 多维查询 ===== */
      '    <el-tab-pane label="多维查询" name="list">',
      '      <div class="cm-filter">',
      '        <el-form inline size="small">',
      '          <el-form-item label="日期">',
      '            <el-date-picker v-model="f.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px;"></el-date-picker>',
      '          </el-form-item>',
      '          <el-form-item label="申请科室">',
      '            <el-select v-model="f.applyDeptId" clearable filterable placeholder="全部" style="width:150px;">',
      '              <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="受邀科室">',
      '            <el-select v-model="f.targetDeptId" clearable filterable placeholder="全部" style="width:150px;">',
      '              <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="会诊类型">',
      '            <el-select v-model="f.category" clearable placeholder="全部" style="width:120px;">',
      '              <el-option v-for="o in categoryOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="紧急程度">',
      '            <el-select v-model="f.urgency" clearable placeholder="全部" style="width:100px;">',
      '              <el-option v-for="o in urgencyOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="状态">',
      '            <el-select v-model="f.status" clearable placeholder="全部" style="width:110px;">',
      '              <el-option v-for="o in statusOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="就诊类型">',
      '            <el-select v-model="f.visitType" clearable placeholder="全部" style="width:100px;">',
      '              <el-option v-for="o in visitTypeOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item>',
      '            <el-button type="primary" :loading="loading" @click="onSearch">查询</el-button>',
      '            <el-button @click="onReset">重置</el-button>',
      '          </el-form-item>',
      '        </el-form>',
      '      </div>',
      '      <div class="cm-table-wrap" v-loading="loading">',
      '        <el-table :data="rows" height="100%" size="small" border :row-class-name="rowClass">',
      '          <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '          <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '            <template #default="s">{{ s.row.patientName || \'-\' }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="申请科室 → 受邀科室" min-width="200" show-overflow-tooltip>',
      '            <template #default="s">{{ deptText(s.row.applyDeptId, s.row.applyDeptName) }} → {{ deptText(s.row.targetDeptId, s.row.targetDeptName) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="会诊类型" width="100" align="center">',
      '            <template #default="s"><el-tag size="small" :type="typeTag(s.row.consultCategory)" disable-transitions>{{ typeText(s.row.consultCategory) }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="紧急程度" width="86" align="center">',
      '            <template #default="s"><el-tag size="small" :type="urgencyTag(s.row.urgencyLevel)" disable-transitions>{{ urgencyText(s.row.urgencyLevel) }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="状态" width="86" align="center">',
      '            <template #default="s"><el-tag size="small" :type="statusTag(s.row.status)" disable-transitions>{{ statusText(s.row.status) }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="申请时间" width="140">',
      '            <template #default="s">{{ timeText(s.row.applyTime) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="响应时间" width="140">',
      '            <template #default="s">{{ timeText(s.row.responseTime) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="完成时间" width="140">',
      '            <template #default="s">{{ timeText(s.row.finishTime || s.row.consultTime) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="评分" width="70" align="center">',
      '            <template #default="s">{{ scoreText(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="122" fixed="right" align="center">',
      '            <template #default="s">',
      '              <el-button link type="primary" size="small" @click="openDetail(s.row)">详情</el-button>',
      '              <el-button v-if="Number(s.row.status) === 1 || Number(s.row.status) === 2" link type="warning" size="small" @click="urge(s.row)">催促</el-button>',
      '            </template>',
      '          </el-table-column>',
      '          <template #empty><span class="cm-note">暂无会诊记录</span></template>',
      '        </el-table>',
      '      </div>',
      '      <div class="cm-pager">',
      '        <span class="cm-note">共 {{ total }} 条会诊</span>',
      '        <el-pagination small background layout="total, sizes, prev, pager, next" :total="total" :page-size="size"',
      '                       :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '      </div>',
      '    </el-tab-pane>',

      /* ===== Tab2 统计分析 ===== */
      '    <el-tab-pane label="统计分析" name="stats">',
      '      <div class="cm-scroll" v-loading="stLoading">',
      '        <div class="cm-stats-bar">',
      '          <span class="cm-note">统计区间: {{ rangeText(\' ~ \') }} · 数据源: /statistics(会诊类型分布与受邀医生排行后端未覆盖, 由区间明细≤500条前端兑底计算)</span>',
      '          <span>',
      '            <el-button size="small" @click="loadStats">刷新</el-button>',
      '            <el-button size="small" :loading="exporting" @click="exportCsv">导出CSV</el-button>',
      '          </span>',
      '        </div>',
      '        <div class="cm-kpis">',
      '          <div v-for="k in stKpis" :key="k.t" class="cm-kpi"><div class="cm-kpi-t">{{ k.t }}</div><div class="cm-kpi-n">{{ k.n }}</div><div class="cm-kpi-s">{{ k.s }}</div></div>',
      '        </div>',
      '        <div class="cm-grid2">',
      '          <div class="cm-panel">',
      '            <div class="cm-panel-t" style="margin-bottom:8px;">科室申请排行 TOP10</div>',
      '            <template v-if="applyDeptBars.length">',
      '              <div v-for="(b, i) in applyDeptBars" :key="\'a\' + i" class="cm-bar-row">',
      '                <span class="cm-bar-name" :title="b.name">{{ i + 1 }}. {{ b.name }}</span>',
      '                <div class="cm-bar-track"><div class="cm-bar-fill" :style="{ width: barPct(b.count, applyDeptBars) + \'%\' }"></div></div>',
      '                <span class="cm-bar-val">{{ b.count }}</span>',
      '              </div>',
      '            </template>',
      '            <div v-else class="cm-note">区间内暂无会诊数据</div>',
      '          </div>',
      '          <div class="cm-panel">',
      '            <div class="cm-panel-t" style="margin-bottom:8px;">受邀科室排行 TOP10</div>',
      '            <template v-if="targetDeptBars.length">',
      '              <div v-for="(b, i) in targetDeptBars" :key="\'t\' + i" class="cm-bar-row">',
      '                <span class="cm-bar-name" :title="b.name">{{ i + 1 }}. {{ b.name }}</span>',
      '                <div class="cm-bar-track"><div class="cm-bar-fill" :style="{ width: barPct(b.count, targetDeptBars) + \'%\', background: \'var(--yb-fill-warning)\' }"></div></div>',
      '                <span class="cm-bar-val">{{ b.count }}</span>',
      '              </div>',
      '            </template>',
      '            <div v-else class="cm-note">区间内暂无会诊数据</div>',
      '          </div>',
      '        </div>',
      '        <div class="cm-grid2">',
      '          <div class="cm-panel">',
      '            <div class="cm-panel-t" style="margin-bottom:8px;">会诊类型分布</div>',
      '            <template v-if="catTotal()">',
      '              <div v-for="(b, i) in categoryBars" :key="\'c\' + i" class="cm-bar-row">',
      '                <span class="cm-bar-name">{{ b.name }}</span>',
      '                <div class="cm-bar-track"><div class="cm-bar-fill" :style="{ width: barPct(b.count, categoryBars) + \'%\', background: \'var(--yb-brand)\' }"></div></div>',
      '                <span class="cm-bar-val">{{ b.count }} ({{ catPct(b.count) }})</span>',
      '              </div>',
      '            </template>',
      '            <div v-else class="cm-note">区间内暂无会诊数据</div>',
      '          </div>',
      '          <div class="cm-panel">',
      '            <div class="cm-panel-t" style="margin-bottom:8px;">超时率统计 <span class="cm-note">(处理中会诊的超时占比)</span></div>',
      '            <template v-if="timeoutInfo.pending">',
      '              <div class="cm-big-num">{{ timeoutInfo.rateText }}</div>',
      '              <div class="cm-bar-row">',
      '                <span class="cm-bar-name">超时率</span>',
      '                <div class="cm-bar-track"><div class="cm-bar-fill" :style="{ width: Math.min(100, Number(timeoutInfo.rate) || 0) + \'%\', background: \'var(--yb-fill-danger)\' }"></div></div>',
      '                <span class="cm-bar-val">{{ timeoutInfo.rateText }}</span>',
      '              </div>',
      '              <div class="cm-note">已超时 {{ timeoutInfo.count }} 例 / 处理中 {{ timeoutInfo.pending }} 例(兜底口径基于区间明细, 明细超500条时为近似值)</div>',
      '            </template>',
      '            <div v-else class="cm-note">区间内暂无处理中会诊, 无超时数据</div>',
      '          </div>',
      '        </div>',
      '        <div class="cm-grid2">',
      '          <div class="cm-panel">',
      '            <div class="cm-panel-t" style="margin-bottom:8px;">医生申请排行 TOP10</div>',
      '            <el-table v-if="doctorApplyRows.length" :data="doctorApplyRows" size="small" border>',
      '              <el-table-column label="排名" width="60" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '              <el-table-column label="医生" prop="name" min-width="110" show-overflow-tooltip></el-table-column>',
      '              <el-table-column label="申请次数" prop="count" width="90" align="center"></el-table-column>',
      '            </el-table>',
      '            <div v-else class="cm-note">暂无数据</div>',
      '          </div>',
      '          <div class="cm-panel">',
      '            <div class="cm-panel-t" style="margin-bottom:8px;">医生受邀排行 TOP10</div>',
      '            <el-table v-if="doctorTargetRows.length" :data="doctorTargetRows" size="small" border>',
      '              <el-table-column label="排名" width="60" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '              <el-table-column label="医生" prop="name" min-width="110" show-overflow-tooltip></el-table-column>',
      '              <el-table-column label="受邀次数" prop="count" width="90" align="center"></el-table-column>',
      '            </el-table>',
      '            <div v-else class="cm-note">暂无数据</div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ===== Tab3 超时预警 ===== */
      '    <el-tab-pane label="超时预警" name="warn">',
      '      <div class="cm-scroll" v-loading="warnLoading">',
      '        <div class="cm-zone cm-zone-danger">',
      '          <div class="cm-zone-hd">',
      '            <span class="cm-zone-t">已超时会诊({{ warnOverdue.length }})</span>',
      '            <el-button size="small" @click="loadWarnings">刷新</el-button>',
      '          </div>',
      '          <el-table v-if="warnOverdue.length" :data="warnOverdue" size="small" border>',
      '            <el-table-column type="index" label="序号" width="55" align="center"></el-table-column>',
      '            <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ s.row.patientName || \'-\' }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="申请科室 → 受邀科室" min-width="190" show-overflow-tooltip>',
      '              <template #default="s">{{ deptText(s.row.applyDeptId, s.row.applyDeptName) }} → {{ deptText(s.row.targetDeptId, s.row.targetDeptName) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="紧急程度" width="80" align="center">',
      '              <template #default="s"><el-tag size="small" :type="urgencyTag(s.row.urgencyLevel)" disable-transitions>{{ urgencyText(s.row.urgencyLevel) }}</el-tag></template>',
      '            </el-table-column>',
      '            <el-table-column label="申请时间" width="140">',
      '              <template #default="s">{{ timeText(s.row.applyTime) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="已超时" width="110" align="center">',
      '              <template #default="s"><span style="color:var(--yb-danger);font-weight:600;">{{ overText(s.row) }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column label="操作" width="80" align="center">',
      '              <template #default="s"><el-button link type="warning" size="small" @click="urge(s.row)">催促</el-button></template>',
      '            </el-table-column>',
      '          </el-table>',
      '          <div v-else class="cm-note">暂无超时会诊</div>',
      '        </div>',
      '        <div class="cm-zone cm-zone-warn">',
      '          <div class="cm-zone-hd">',
      '            <span class="cm-zone-t">临近超时({{ warnNearing.length }}) · 剩余 ≤ {{ 30 }} 分钟</span>',
      '          </div>',
      '          <el-table v-if="warnNearing.length" :data="warnNearing" size="small" border>',
      '            <el-table-column type="index" label="序号" width="55" align="center"></el-table-column>',
      '            <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ s.row.patientName || \'-\' }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="申请科室 → 受邀科室" min-width="190" show-overflow-tooltip>',
      '              <template #default="s">{{ deptText(s.row.applyDeptId, s.row.applyDeptName) }} → {{ deptText(s.row.targetDeptId, s.row.targetDeptName) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="紧急程度" width="80" align="center">',
      '              <template #default="s"><el-tag size="small" :type="urgencyTag(s.row.urgencyLevel)" disable-transitions>{{ urgencyText(s.row.urgencyLevel) }}</el-tag></template>',
      '            </el-table-column>',
      '            <el-table-column label="申请时间" width="140">',
      '              <template #default="s">{{ timeText(s.row.applyTime) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="剩余时限" width="110" align="center">',
      '              <template #default="s"><span style="color:var(--yb-warning-strong);font-weight:600;">{{ nearText(s.row) }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column label="操作" width="80" align="center">',
      '              <template #default="s"><el-button link type="warning" size="small" @click="urge(s.row)">催促</el-button></template>',
      '            </el-table-column>',
      '          </el-table>',
      '          <div v-else class="cm-note">暂无临近超时会诊</div>',
      '        </div>',
      '        <div class="cm-note">口径: 已超时 = 处理中(status 1/2)且后端标记 timeout_notified=1 或前端按响应时限判定已过期; 临近超时 = 响应时限剩余 ≤ 30 分钟。响应时限: 特急即刻 / 急 10 分钟 / 普通 24 小时(后端 response_deadline 落库, 无值时按申请时间 + 时限推算)。预警列表不分页, 单状态最多拉取 200 条。</div>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',

      /* ===== 详情抽屉 ===== */
      '  <el-drawer v-model="dtVisible" title="会诊详情" size="46%">',
      '    <div v-if="dtRow" v-loading="dtLoading" style="min-height:200px;">',
      '      <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">',
      '        <span style="font-size:16px;font-weight:600;color:var(--yb-ink-1);">{{ dtRow.patientName || \'未名患者\' }}</span>',
      '        <el-tag size="small" :type="visitTypeText(dtRow.visitType) === \'-\' ? \'info\' : \'primary\'" disable-transitions>{{ visitTypeText(dtRow.visitType) }}</el-tag>',
      '        <el-tag size="small" :type="urgencyTag(dtRow.urgencyLevel)" disable-transitions>{{ urgencyText(dtRow.urgencyLevel) }}</el-tag>',
      '        <el-tag size="small" :type="statusTag(dtRow.status)" disable-transitions>{{ statusText(dtRow.status) }}</el-tag>',
      '        <el-tag v-if="isOverdue(dtRow)" size="small" type="danger" effect="dark" disable-transitions>已超时</el-tag>',
      '        <span class="cm-note">#{{ dtRow.id }}</span>',
      '      </div>',
      '      <div class="cm-sec-t">基本信息</div>',
      '      <div class="cm-dl">',
      '        <div class="it"><div class="k">就诊类型</div><div class="v">{{ visitTypeText(dtRow.visitType) }}</div></div>',
      '        <div class="it"><div class="k">就诊ID</div><div class="v">{{ dtRow.visitId == null ? \'-\' : dtRow.visitId }}</div></div>',
      '        <div class="it"><div class="k">申请科室</div><div class="v">{{ deptText(dtRow.applyDeptId, dtRow.applyDeptName) }}</div></div>',
      '        <div class="it"><div class="k">申请医师</div><div class="v">{{ doctorText(dtRow.applyDoctorId, dtRow.applyDoctorName) }}</div></div>',
      '        <div class="it"><div class="k">受邀科室</div><div class="v">{{ deptText(dtRow.targetDeptId, dtRow.targetDeptName) }}</div></div>',
      '        <div class="it"><div class="k">受邀医师</div><div class="v">{{ doctorText(dtRow.targetDoctorId, dtRow.targetDoctorName) }}</div></div>',
      '        <div class="it"><div class="k">会诊类型</div><div class="v">{{ typeText(dtRow.consultCategory) }}</div></div>',
      '        <div class="it"><div class="k">响应时限</div><div class="v">{{ deadlineOf(dtRow) ? fmtDT(deadlineOf(dtRow)) : \'-\' }}</div></div>',
      '        <div class="it"><div class="k">申请时间</div><div class="v">{{ timeText(dtRow.applyTime) }}</div></div>',
      '        <div class="it"><div class="k">响应时间</div><div class="v">{{ timeText(dtRow.responseTime) }}</div></div>',
      '        <div class="it"><div class="k">完成时间</div><div class="v">{{ timeText(dtRow.finishTime || dtRow.consultTime) }}</div></div>',
      '        <div class="it"><div class="k">双向评分</div><div class="v">{{ scoreText(dtRow) }}</div></div>',
      '      </div>',
      '      <div class="cm-sec-t">病情摘要</div>',
      '      <div class="cm-block">{{ dtRow.conditionSummary || \'未填写\' }}</div>',
      '      <div class="cm-sec-t">申请理由</div>',
      '      <div class="cm-block">{{ dtRow.applyReason || \'未填写\' }}</div>',
      '      <div class="cm-sec-t">会诊意见</div>',
      '      <div class="cm-block">{{ dtRow.consultOpinion || (Number(dtRow.status) === 3 ? \'未填写\' : (Number(dtRow.status) === 2 ? \'会诊完成后由受邀方填写\' : \'会诊受理后填写\')) }}</div>',
      '      <div class="cm-sec-t">双向评价</div>',
      '      <div class="cm-grid2">',
      '        <div class="cm-panel">',
      '          <div class="cm-panel-t">申请方评价（对受邀科室）</div>',
      '          <div class="cm-eval-score">{{ evalInfo(dtRow, 1).score == null ? \'未评价\' : evalInfo(dtRow, 1).score + \' 分\' }}</div>',
      '          <div class="cm-note">{{ evalInfo(dtRow, 1).note || \'无评语\' }}</div>',
      '        </div>',
      '        <div class="cm-panel">',
      '          <div class="cm-panel-t">受邀方评价（对申请科室）</div>',
      '          <div class="cm-eval-score">{{ evalInfo(dtRow, 2).score == null ? \'未评价\' : evalInfo(dtRow, 2).score + \' 分\' }}</div>',
      '          <div class="cm-note">{{ evalInfo(dtRow, 2).note || \'无评语\' }}</div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };
})();
