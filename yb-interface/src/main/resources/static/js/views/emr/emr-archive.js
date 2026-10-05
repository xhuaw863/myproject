/* 病历归档工作台(P7a-4): HIS.views.EmrArchive —— 顶部四KPI(72h提交率/返修率/本月归档数/逾期未归档)
 * + 五页签(待归档/归档管理/封存管理/统计/Webhook订阅)的病案归档全流程管控台。
 * 后端契约(P7a-5 已与 EmrArchiveController/InpPrintController 对齐):
 *   GET  /list?deptId&status&doctorName&startDate&endDate&page&size  归档列表(IPage)
 *   GET  /overdue?deptId 逾期未归档 · GET /stats?deptId&startDate&endDate 统计 · GET /{recordId} 详情
 *   POST /submit/{recordId} · /batch-submit(body=[recordIds]) · /recall/{recordId}?reason=
 *        /seal/{recordId}?reason= · /unseal/{recordId} · /urge/{recordId}(归档催促, SSE走QC_REMINDER通道)
 *   PUT  /recall/{recordId}/approve?approved=true|false
 * PDF: GET /api/his/inp/print/emr-pdf/{recordId}(InpPrint P7a-3 Tiptap→PDF 流, download=true 附件)
 *   防篡改回查: GET /api/his/inp/print/emr-pdf/{recordId}/verify → {valid,expected,actual,generatedTime,message?}
 * 兼容性兜底: ①API前缀双探(/api/emr/archive → /api/his/emr/archive, GET 端点缺失自动切换并缓存);
 *   ②列表响应 records/rows/list 与 total/count 多键名归一; ③/list 不可用回退 /overdue 全量+前端过滤分页;
 *   ④逾期档位筛选走全量拉取+前端过滤分页(半开区间 3-7=[3,7) 7-14=[7,14) >14=[14,∞));
 *   ⑤KPI/统计/记录字段多键名兼容, 缺失显示 —; ⑥/batch-submit 缺失降级逐条 /submit。
 * 状态口径(P7a-5 与后端对齐): 1草稿 2已提交 3已审核(待归档/退回返修) 4已归档 5召回中 6已封存;
 *   待归档页查已审核(3, 仅该状态可提交归档); 返修中=已审核且 recall_approved=1(前端过滤+本地分页);
 *   召回审批 recall_approved: 0待审/1通过/2驳回(his_inp_medical_record P7a-1 扩展列)。
 * 逾期口径: 逾期天数=出院日期自然日差(后端 overdueDays 字段优先), 超72h(>3天)即逾期;
 *   天数文字 >7红/3-7黄, 行浅红强警示仅 >7 天档。PDF: 查看转 blob 新窗口, 下载走 HIS.download。
 * 列表规范: 序号列跨页连续 · el-pagination 带 sizes · 默认20行/页([10,20,50,100])。
 * Webhook订阅(P7b-1 EmrInteropController, 前缀 /api/emr/interop/webhook, 维护档位=机构管理员):
 *   GET /list 订阅列表 · POST / 新建 · PUT /{id} 更新(仅覆写非空字段) · DELETE /{id} 删除(逻辑删);
 *   POST /{id}/test 连通性测试({success,error?,callbackUrl,elapsedMs,failCount,status}) · GET /event-types
 *   事件类型清单([{name,displayName}])。eventTypes 逗号分隔串, 表单双向 split/join; 本地分页(20/页);
 *   fail_count>=5 熔断: 行浅红强警示 + 状态列"已熔断"(danger) + 失败计数红字。
 * 注册: HIS.views.EmrArchive(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 常量 ================= */
  /* API 前缀候选: 任务契约前缀优先, 项目惯例 /api/his/emr 前缀兜底(首包实测对不上时自动切换) */
  var API_CANDIDATES = ['/api/emr/archive', '/api/his/emr/archive'];

  var ST = { DRAFT: 1, SUBMITTED: 2, REVIEWED: 3, ARCHIVED: 4, RECALL: 5, SEALED: 6 };
  var ST_META = {
    1: { l: '草稿', t: 'info' }, 2: { l: '已提交', t: 'primary' }, 3: { l: '已审核', t: 'warning' },
    4: { l: '已归档', t: 'success' }, 5: { l: '召回中', t: 'danger' }, 6: { l: '已封存', t: 'info' }
  };
  /* 召回审批(recall_approved): 0待审/1通过/2驳回 */
  var RECALL_META = { 0: { l: '待审批', t: 'warning' }, 1: { l: '已通过', t: 'success' }, 2: { l: '已驳回', t: 'danger' } };
  /* 九类结构化文书(record_type 1-9) */
  var RECORD_TYPES = ['-', '入院记录', '首次病程', '日常病程', '上级查房', '术前小结', '手术记录', '术后病程', '出院小结', '死亡记录'];
  /* 逾期天数额度(半开区间, 过滤口径与文案一致) */
  var OVERDUE_OPTS = [{ v: '3-7', l: '3-7天' }, { v: '7-14', l: '7-14天' }, { v: '14+', l: '>14天' }];
  var PAGE_SIZES = [10, 20, 50, 100];
  /* Webhook 订阅 API(P7b-1 EmrInteropController, 前缀固定不走双探) + 熔断阈值 */
  var INTEROP_API = '/api/emr/interop/webhook';
  var WK_FUSE_LIMIT = 5;

  /* ================= 工具 ================= */
  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function parseT(v) {
    if (!v) { return null; }
    var d = new Date(String(v).replace('T', ' ').replace(/-/g, '/'));
    return isNaN(d.getTime()) ? null : d;
  }
  function fmtDT(v) {
    if (v == null || v === '') { return '-'; }
    var d = parseT(v);
    if (!d) { return String(v).replace('T', ' ').substring(0, 16); }
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  function date10(v) {
    if (v == null || v === '') { return ''; }
    return String(v).replace('T', ' ').substring(0, 10);
  }
  /* 多候选取第一个有效数值(兼容后端字段口径差异) */
  function pickNum() {
    for (var i = 0; i < arguments.length; i++) {
      var v = arguments[i];
      if (v != null && v !== '' && !isNaN(Number(v))) { return Number(v); }
    }
    return null;
  }
  /* 多候选取第一个非空字符串 */
  function pickStr() {
    for (var i = 0; i < arguments.length; i++) {
      var v = arguments[i];
      if (v != null && String(v) !== '') { return String(v); }
    }
    return null;
  }
  function pickArr(o, keys) {
    if (!o) { return null; }
    for (var i = 0; i < keys.length; i++) { var v = o[keys[i]]; if (Array.isArray(v)) { return v; } }
    return null;
  }
  /* 记录主键(归档记录或病历记录 id, 雪花ID为字符串) */
  function recId(row) {
    if (!row) { return null; }
    var v = row.id != null ? row.id : (row.recordId != null ? row.recordId : row.record_id);
    return v == null ? null : v;
  }
  /* 列表响应归一: 数组直返; 对象取 records/rows/list/data 首个数组 */
  function normRecords(d) {
    if (!d) { return []; }
    if (Array.isArray(d)) { return d; }
    var keys = ['records', 'rows', 'list', 'data', 'items'];
    for (var i = 0; i < keys.length; i++) { if (Array.isArray(d[keys[i]])) { return d[keys[i]]; } }
    return [];
  }
  function normTotal(d, arr) {
    if (!d || Array.isArray(d)) { return (arr || d || []).length; }
    var t = pickNum(d.total, d.count, d.totalCount, d.totalElements);
    return t == null ? normRecords(d).length : t;
  }
  /* 端点缺失判定: HTTP 404/405、响应非JSON/网络层失败(供前缀双探与降级兜底) */
  function isEndpointMissing(e) {
    var m = String((e && e.message) || e || '');
    return /(^|\D)(404|405)(\D|$)/.test(m) || /响应解析失败/.test(m) || /Failed to fetch/i.test(m) || /NetworkError/i.test(m);
  }
  /* CSV 组装(BOM 防 Excel 中文乱码; 逗号/引号/换行转义) */
  function toCsvRows(rows) {
    return '\ufeff' + (rows || []).map(function (row) {
      return (row || []).map(function (cell) {
        var s = cell == null ? '' : String(cell);
        return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
      }).join(',');
    }).join('\r\n');
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
  /* 近 n 个自然月标签(含当月) */
  function lastMonths(n) {
    var out = [], now = new Date();
    for (var i = n - 1; i >= 0; i--) {
      var m = new Date(now.getFullYear(), now.getMonth() - i, 1);
      out.push(m.getFullYear() + '-' + pad2(m.getMonth() + 1));
    }
    return out;
  }
  function monthRangeOf(m) {
    var y = Number(String(m).slice(0, 4)), mo = Number(String(m).slice(5, 7));
    var last = new Date(y, mo, 0).getDate();
    return [m + '-01', m + '-' + pad2(last)];
  }
  /* KPI 色调: 72h提交率(>90绿 / 70-90黄 / <70红) */
  function tone72(v) { return v == null ? 'blue' : (v > 90 ? 'green' : (v >= 70 ? 'yellow' : 'red')); }
  /* KPI 色调: 返修率(<5绿 / 5-15黄 / >15红) */
  function toneRepair(v) { return v == null ? 'blue' : (v < 5 ? 'green' : (v <= 15 ? 'yellow' : 'red')); }
  function pctText(v) { return v == null ? '—' : Number(v).toFixed(1) + '%'; }

  /* ================= API 前缀双探 =================
   * 首个 GET 按主前缀请求, 端点缺失(404/405/非JSON/网络层)时切备用前缀并缓存, 之后全部请求走缓存前缀。
   * 写操作(submit/recall/seal...)不参与探测(避免副作用重放), 一律使用当前缓存前缀。 */
  var apiIdx = 0;
  function apiPath(p) { return API_CANDIDATES[apiIdx] + p; }
  function getA(path) {
    return HIS.get(apiPath(path)).catch(function (e) {
      if (apiIdx === 0 && isEndpointMissing(e)) {
        apiIdx = 1;
        return HIS.get(apiPath(path));
      }
      throw e;
    });
  }
  function postA(path, body) { return HIS.post(apiPath(path), body); }
  function putA(path, body) { return HIS.put(apiPath(path), body); }

  /* ================= 私有样式(ea- 前缀, 一次性注入; 高度链复用 him.css .cd-fill/.cd-tabs 规范) ================= */
  (function ensureArchiveStyles() {
    if (document.getElementById('ea-styles')) { return; }
    var css = [
      /* 顶部 KPI 行: 在 .cd-fill flex 列中固定高度不参与拉伸 */
      '.cd-fill > .ea-kpis { flex: none; }',
      '.ea-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 10px; }',
      '.ea-kpi { background: var(--yb-surface, #fff); border: 1px solid var(--yb-border, #e3e8ef); border-radius: 10px; padding: 12px 14px; position: relative; overflow: hidden; }',
      '.ea-kpi::before { content: ""; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: var(--yb-brand, #3b82f6); }',
      '.ea-kpi.tone-green::before { background: var(--yb-success, #3c862d); }',
      '.ea-kpi.tone-yellow::before { background: var(--yb-warning, #e6a23c); }',
      '.ea-kpi.tone-red::before { background: var(--yb-danger, #c74f4f); }',
      '.ea-kpi-t { font-size: 12px; color: var(--yb-ink-3, #5a6a7e); }',
      '.ea-kpi-n { font-size: 24px; font-weight: 700; color: var(--yb-ink-1, #1f2d3d); margin-top: 4px; font-variant-numeric: tabular-nums; }',
      '.ea-kpi-n.green { color: var(--yb-success, #3c862d); }',
      '.ea-kpi-n.yellow { color: var(--yb-warning-strong, #a26b1b); }',
      '.ea-kpi-n.red { color: var(--yb-danger, #c74f4f); }',
      '.ea-kpi-s { font-size: 11px; color: var(--yb-ink-4, #8994a5); margin-top: 2px; }',
      /* 筛选/工具条/表格/分页: 配合 .cd-tabs 页签内 flex 高度链 */
      '.ea-filter { flex: none; background: var(--yb-surface, #fff); border: 1px solid var(--yb-border, #e3e8ef); border-radius: 8px; padding: 12px 12px 0; margin-bottom: 10px; }',
      '.ea-filter .el-form-item { margin-bottom: 10px; margin-right: 12px; }',
      '.ea-bar { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }',
      '.ea-table-wrap { flex: 1; min-height: 0; }',
      '.ea-subwrap { flex: 1; min-height: 0; display: flex; flex-direction: column; }',
      '.ea-pager { flex: none; display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 8px 2px 0; }',
      '.ea-note { font-size: 12px; color: var(--yb-ink-4, #8994a5); line-height: 1.7; }',
      /* 逾期天数分档: >7天红 / 3-7天黄; 行浅红强警示(>7天档) */
      '.ea-day-danger { color: var(--yb-danger, #c74f4f); font-weight: 700; }',
      '.ea-day-warn { color: var(--yb-warning-strong, #a26b1b); font-weight: 600; }',
      '.el-table .ea-row-timeout > td { background: #fdf0ef !important; }',
      '.el-table .ea-row-timeout:hover > td { background: #fbe3e1 !important; }',
      /* Webhook 熔断行(fail_count>=5): 行浅红强警示 */
      '.el-table .ea-row-fuse > td { background: #fdf0ef !important; }',
      '.el-table .ea-row-fuse:hover > td { background: #fbe3e1 !important; }',
      /* 统计页 */
      '.ea-scroll { flex: 1; min-height: 0; overflow: auto; padding: 2px 2px 24px; }',
      '.ea-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px; }',
      '.ea-panel { background: var(--yb-surface, #fff); border: 1px solid var(--yb-border, #e3e8ef); border-radius: 10px; padding: 14px; }',
      '.ea-panel-t { font-size: 13px; font-weight: 600; color: var(--yb-ink-1, #1f2d3d); margin-bottom: 8px; }',
      '.ea-bar-row { display: flex; align-items: center; gap: 8px; margin: 6px 0; font-size: 12px; }',
      '.ea-bar-name { width: 130px; flex: none; text-align: right; color: var(--yb-ink-2, #3d4a5c); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      '.ea-bar-track { flex: 1; height: 14px; background: var(--yb-surface-3, #eef2f7); border-radius: 4px; overflow: hidden; }',
      '.ea-bar-fill { height: 100%; border-radius: 4px; background: var(--yb-fill-info, #dbeafe); min-width: 2px; transition: width .4s; }',
      '.ea-bar-val { width: 118px; flex: none; color: var(--yb-ink-3, #5a6a7e); white-space: nowrap; }',
      /* 详情抽屉 */
      '.ea-dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0 22px; }',
      '.ea-dl .it { border-bottom: 1px dashed var(--yb-divider, #eef2f7); padding: 6px 0; }',
      '.ea-dl .k { font-size: 12px; color: var(--yb-ink-3, #5a6a7e); }',
      '.ea-dl .v { font-size: 13px; color: var(--yb-ink-1, #1f2d3d); font-weight: 500; margin-top: 2px; }',
      '.ea-sec-t { font-size: 13px; font-weight: 600; color: var(--yb-ink-1, #1f2d3d); margin: 16px 0 8px; padding-left: 8px; border-left: 3px solid var(--yb-brand, #3b82f6); }',
      '.ea-block { background: var(--yb-surface-2, #f7f9fc); border: 1px solid var(--yb-divider, #eef2f7); border-radius: 8px; padding: 10px 12px; font-size: 13px; line-height: 1.8; color: var(--yb-ink-1, #1f2d3d); white-space: pre-wrap; word-break: break-all; }'
    ].join('\n');
    var el = document.createElement('style');
    el.id = 'ea-styles';
    el.textContent = css;
    document.head.appendChild(el);
  })();

  /* ================= 组件 ================= */
  HIS.views.EmrArchive = {
    name: 'EmrArchive',
    data: function () {
      return {
        tab: 'pending',
        /* 顶部 KPI(始终可见) */
        kpiLoading: false,
        stats: {},
        overdueList: [],
        /* Tab1 待归档 */
        f: { dateRange: null, deptId: null, doctorName: '', overdueRange: null },
        pg: { rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false },
        selRows: [],
        localMode: false,       /* 全量拉取+前端过滤分页模式(逾期档筛选/端点降级时启用) */
        pendingCache: [],
        /* Tab2 归档管理(三个子列表独立分页) */
        archSub: 'archived',
        arch: {
          archived: { rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false },
          recall: { rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false },
          repair: { rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false }
        },
        /* Tab3 封存管理 */
        seal: { rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false },
        /* Tab4 统计 */
        statLoaded: false,
        stLoading: false,
        trendLoading: false,
        deptRank: [],
        trendRows: [],
        exporting: false,
        /* 参考数据 */
        depts: [],
        /* 弹窗/抽屉 */
        dlg: { visible: false, loading: false, row: null },
        recallDlg: { visible: false, row: null, reason: '', submitting: false },
        sealDlg: { visible: false, row: null, reason: '', submitting: false },
        /* 行内加载标记(与记录主键比较) */
        submittingId: null,
        urgingId: null,
        auditBusyId: null,
        sealingId: null,
        verifyBusyId: null,
        batchSubmitting: false,
        /* Tab5 Webhook 订阅(P7b-1 互操作; 本地分页) */
        wk: {
          all: [], rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false,
          types: [], typesLoaded: false,
          dlg: {
            visible: false, editId: null, submitting: false,
            form: { subscriberName: '', callbackUrl: '', eventList: [], secretKey: '', statusOn: true }
          },
          testingId: null
        },
        /* SSE 退订函数(I EmrSseClient on 返回值) */
        sseOffs: []
      };
    },
    computed: {
      overdueOpts: function () { return OVERDUE_OPTS; },
      pageSizes: function () { return PAGE_SIZES; },
      /* 科室下拉 id→名称 映射 */
      deptMap: function () {
        var m = {};
        (this.depts || []).forEach(function (d) { m[HIS.idKey(d.id)] = d.deptName; });
        return m;
      },
      /* 顶部四 KPI 卡: 72h提交率/返修率/本月归档数/逾期未归档 */
      kpis: function () {
        var s = this.stats || {};
        var r72 = pickNum(s.submitRate72h, s.rate72h, s.submitRate, s.h72SubmitRate, s.submitRateH72, s.onTimeRate);
        var repair = pickNum(s.repairRate, s.reworkRate, s.returnRate, s.modifyRate);
        var archived = pickNum(s.monthArchiveCount, s.monthArchivedCount, s.archivedCount, s.archiveCount, s.monthCount, s.monthlyArchived);
        var overdue = pickNum(s.overdueCount, s.overdueUnarchived, s.overdueNum, s.overdue);
        if (overdue == null && this.overdueList.length) { overdue = this.overdueList.length; }
        return [
          { key: 'r72', title: '72h提交率', value: pctText(r72), tone: tone72(r72), sub: '出院后72小时内提交占比' },
          { key: 'repair', title: '返修率', value: pctText(repair), tone: toneRepair(repair), sub: '归档后返修病历占比' },
          { key: 'archived', title: '本月归档数', value: archived == null ? '—' : String(archived), tone: 'blue', sub: '自然月累计(份)' },
          { key: 'overdue', title: '逾期未归档', value: overdue == null ? '—' : String(overdue), tone: overdue == null ? 'blue' : (overdue > 0 ? 'red' : 'green'), sub: '超72h未提交(份)' }
        ];
      },
      /* 上月环比基数(优先 stats.prev/compare, 次多键名平铺, 末月度趋势倒数第二条兜底) */
      prevNumbers: function () {
        var s = this.stats || {};
        var p = s.prev || s.compare || s.lastMonth || s.prevMonth || {};
        var out = {
          r72: pickNum(p.submitRate72h, p.rate72h, p.submitRate, s.rate72hPrev, s.prevRate72h, s.submitRate72hPrev, s.prevSubmitRate72h, s.rate72hMom),
          repair: pickNum(p.repairRate, p.reworkRate, s.repairRatePrev, s.prevRepairRate, s.repairRateMom),
          archived: pickNum(p.monthArchiveCount, p.archivedCount, s.monthArchiveCountPrev, s.prevMonthArchiveCount, s.archivedCountMom),
          overdue: pickNum(p.overdueCount, s.overdueCountPrev, s.prevOverdueCount, s.overdueCountMom)
        };
        var tr = this.trendRows || [];
        if (tr.length >= 2) {
          var prev = tr[tr.length - 2];
          if (out.archived == null && prev.count != null) { out.archived = prev.count; }
          if (out.r72 == null && prev.rate != null) { out.r72 = prev.rate; }
        }
        return out;
      },
      /* 统计页 KPI 四卡(含环比箭头; 环比差值为百分点/绝对数) */
      statKpis: function () {
        var km = {};
        (this.kpis || []).forEach(function (k) { km[k.key] = k; });
        var prev = this.prevNumbers || {};
        var defs = [
          { key: 'r72', title: '72h提交率', num: this.rawNum('r72'), prevV: prev.r72, unit: '%' },
          { key: 'repair', title: '返修率', num: this.rawNum('repair'), prevV: prev.repair, unit: '%' },
          { key: 'archived', title: '本月归档总数', num: this.rawNum('archived'), prevV: prev.archived, unit: '' },
          { key: 'overdue', title: '逾期未归档', num: this.rawNum('overdue'), prevV: prev.overdue, unit: '' }
        ];
        return defs.map(function (d) {
          var k = km[d.key] || {};
          var diff = (d.num == null || d.prevV == null) ? null : Number((d.num - d.prevV).toFixed(1));
          d.curText = d.num == null ? '—' : (d.unit === '%' ? Number(d.num).toFixed(1) + '%' : String(d.num));
          d.diffText = diff == null ? '环比 —'
            : (diff > 0 ? '环比 ↑ ' : (diff < 0 ? '环比 ↓ ' : '环比 — ')) + Math.abs(diff).toFixed(1) + (d.unit === '%' && diff !== 0 ? 'pp' : '');
          d.tone = k.tone || 'blue';
          return d;
        });
      },
      /* Webhook 事件类型 name→displayName 映射(清单未加载时回退枚举名展示) */
      wkTypeMap: function () {
        var m = {};
        (this.wk.types || []).forEach(function (t) { m[t.name] = t.displayName || t.name; });
        return m;
      }
    },
    created: function () {
      this.loadRefs();
    },
    mounted: function () {
      var vm = this;
      vm.loadStats();
      vm.loadOverdue();
      vm.loadPending();
      vm.bindSse();
    },
    beforeUnmount: function () {
      var vm = this;
      (vm.sseOffs || []).forEach(function (off) { try { off(); } catch (e) { /* 忽略退订异常 */ } });
      vm.sseOffs = [];
    },
    methods: {
      /* ---------- 模板辅助(文件级助手仅 methods 暴露可达) ---------- */
      fmtDT: fmtDT,
      recIdOf: function (row) { return recId(row); },
      seqPending: function (i) { return (this.pg.page - 1) * this.pg.size + i + 1; },
      seqArch: function (i) { var p = this.arch[this.archSub]; return (p.page - 1) * p.size + i + 1; },
      seqRecall: function (i) { var p = this.arch.recall; return (p.page - 1) * p.size + i + 1; },
      seqRepair: function (i) { var p = this.arch.repair; return (p.page - 1) * p.size + i + 1; },
      seqSeal: function (i) { var p = this.seal; return (p.page - 1) * p.size + i + 1; },
      /* ---------- 字段翻译(多键名兼容) ---------- */
      patientName: function (row) { return row ? (pickStr(row.patientName, row.name, row.patient) || '未名患者') : '-'; },
      admissionNo: function (row) { return (row && pickStr(row.admissionNo, row.inpNo, row.visitNo, row.hospitalNo, row.inpatientNo, row.admNo)) || '-'; },
      deptText: function (row) {
        if (!row) { return '-'; }
        var name = pickStr(row.deptName, row.departmentName);
        if (name) { return name; }
        var id = pickStr(row.deptId, row.departmentId);
        if (id) { return this.deptMap[HIS.idKey(id)] || id; }
        return '-';
      },
      doctorText: function (row) { return (row && pickStr(row.doctorName, row.attendingDoctorName, row.docName, row.doctor)) || '-'; },
      dischargeDate: function (row) { return date10(row && pickStr(row.dischargeDate, row.dischargeTime, row.outDate)) || '-'; },
      recordTypeText: function (row) {
        if (!row) { return '-'; }
        var n = pickStr(row.recordTypeName, row.typeName, row.emrTypeName);
        if (n) { return n; }
        var t = pickNum(row.recordType, row.type);
        return (t != null && RECORD_TYPES[t]) ? RECORD_TYPES[t] : '-';
      },
      statusText: function (v) { var m = ST_META[Number(v)]; return m ? m.l : (v == null || v === '' ? '-' : ('状态' + v)); },
      statusTag: function (v) { var m = ST_META[Number(v)]; return m ? m.t : 'info'; },
      recallText: function (v) { var m = RECALL_META[Number(v)]; return m ? m.l : '-'; },
      recallTag: function (v) { var m = RECALL_META[Number(v)]; return m ? m.t : 'info'; },
      archiveTimeOf: function (row) { return fmtDT(row && pickStr(row.archiveTime, row.archivedTime, row.archiveDate)); },
      archiveByOf: function (row) { return (row && pickStr(row.archiveBy, row.archiverName, row.archiveUserName, row.operator)) || '-'; },
      recallTimeOf: function (row) { return fmtDT(row && pickStr(row.recallTime, row.recallApplyTime, row.applyTime)); },
      recallByOf: function (row) { return (row && pickStr(row.recallBy, row.recallApplicant, row.applyBy, row.applicantName, row.recallUserName)) || '-'; },
      recallReasonOf: function (row) { return (row && pickStr(row.recallReason, row.applyReason, row.reason)) || '-'; },
      recallApprovedOf: function (row) { return pickNum(row && row.recallApproved, row && row.recallApprove); },
      sealTimeOf: function (row) { return fmtDT(row && pickStr(row.sealTime, row.sealedTime, row.sealDate)); },
      sealByOf: function (row) { return (row && pickStr(row.sealBy, row.sealerName, row.sealUserName)) || '-'; },
      sealReasonOf: function (row) { return (row && pickStr(row.sealReason, row.reason)) || '-'; },
      hasPdf: function (row) { return !!(row && (pickStr(row.pdfPath, row.pdfUrl) || row.pdfGeneratedTime || row.pdfGenerated)); },
      pdfText: function (row) { return this.hasPdf(row) ? '已生成' : '未生成'; },
      pdfTag: function (row) { return this.hasPdf(row) ? 'success' : 'info'; },
      /* ---------- 逾期口径 ---------- */
      overdueDaysOf: function (row) {
        var v = pickNum(row && row.overdueDays, row && row.overdueDay, row && row.overdueNum);
        if (v != null) { return v; }
        var d = parseT(row && pickStr(row.dischargeDate, row.dischargeTime, row.outDate));
        if (!d) { return null; }
        var a = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
        var n = new Date();
        var b = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
        return Math.max(0, Math.round((b - a) / 86400000));
      },
      overdueText: function (row) { var d = this.overdueDaysOf(row); return d == null ? '-' : (d + '天'); },
      overdueCls: function (row) {
        var d = this.overdueDaysOf(row);
        if (d == null) { return ''; }
        if (d > 7) { return 'ea-day-danger'; }
        if (d >= 3) { return 'ea-day-warn'; }
        return '';
      },
      /* 行浅红: 超期 >7 天强警示档(与天数红字同档) */
      rowClsPending: function (ctx) {
        var d = this.overdueDaysOf(ctx && ctx.row);
        return d != null && d > 7 ? 'ea-row-timeout' : '';
      },
      onSelChange: function (rows) { this.selRows = rows || []; },

      /* ================= 顶部 KPI / 逾期清单 ================= */
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (list) { vm.depts = list || []; }).catch(function () { /* 科室下拉降级为空 */ });
      },
      monthRange: function () {
        var n = new Date();
        return [n.getFullYear() + '-' + pad2(n.getMonth() + 1) + '-01', today()];
      },
      loadStats: function () {
        var vm = this;
        var r = vm.monthRange();
        vm.kpiLoading = true;
        return getA('/stats?startDate=' + r[0] + '&endDate=' + r[1]).then(function (d) {
          vm.stats = (d && typeof d === 'object' && !Array.isArray(d)) ? d : {};
        }).catch(function (e) {
          if (!isEndpointMissing(e)) { HIS.notifyError(e); }
        }).finally(function () { vm.kpiLoading = false; });
      },
      loadOverdue: function () {
        var vm = this;
        return getA('/overdue').then(function (d) {
          vm.overdueList = normRecords(d);
        }).catch(function () { vm.overdueList = []; });
      },
      /* KPI 原始数值(供统计页环比; 与 kpis computed 同口径) */
      rawNum: function (key) {
        var s = this.stats || {};
        if (key === 'r72') { return pickNum(s.submitRate72h, s.rate72h, s.submitRate, s.h72SubmitRate, s.submitRateH72, s.onTimeRate); }
        if (key === 'repair') { return pickNum(s.repairRate, s.reworkRate, s.returnRate, s.modifyRate); }
        if (key === 'archived') { return pickNum(s.monthArchiveCount, s.monthArchivedCount, s.archivedCount, s.archiveCount, s.monthCount, s.monthlyArchived); }
        if (key === 'overdue') {
          var v = pickNum(s.overdueCount, s.overdueUnarchived, s.overdueNum, s.overdue);
          return v == null && this.overdueList.length ? this.overdueList.length : v;
        }
        return null;
      },

      /* ================= Tab1 待归档 ================= */
      buildPendingQuery: function () {
        var p = [];
        var f = this.f;
        if (f.dateRange && f.dateRange.length === 2) {
          p.push('startDate=' + encodeURIComponent(f.dateRange[0]));
          p.push('endDate=' + encodeURIComponent(f.dateRange[1]));
        }
        if (f.deptId != null && f.deptId !== '') { p.push('deptId=' + HIS.idParam(f.deptId)); }
        var name = String(f.doctorName || '').trim();
        if (name) { p.push('doctorName=' + encodeURIComponent(name)); }
        return p.join('&');
      },
      loadPending: function (resetPage) {
        var vm = this;
        if (resetPage) { vm.pg.page = 1; }
        if (vm.f.overdueRange) { return vm.loadPendingLocal(); }   /* 逾期档筛选: 全量拉取+前端过滤分页 */
        var q = vm.buildPendingQuery();
        vm.pg.loading = true;
        return getA('/list?status=' + ST.REVIEWED + (q ? '&' + q : '') + '&page=' + vm.pg.page + '&size=' + vm.pg.size)
          .then(function (d) {
            vm.pg.rows = normRecords(d);
            vm.pg.total = normTotal(d, vm.pg.rows);
            vm.pg.loaded = true;
            vm.localMode = false;
          })
          .catch(function (e) {
            if (isEndpointMissing(e)) { return vm.loadPendingLocal(); }   /* /list 不可用 → /overdue 回退 */
            vm.pg.rows = []; vm.pg.total = 0;
            HIS.notifyError(e);
          })
          .finally(function () { vm.pg.loading = false; });
      },
      loadPendingLocal: function () {
        /* 兜底模式: /list 取全量(≤500) → /overdue 降级 → 前端过滤+本地分页 */
        var vm = this;
        vm.localMode = true;
        vm.pg.loading = true;
        var q = vm.buildPendingQuery();
        return getA('/list?status=' + ST.REVIEWED + (q ? '&' + q : '') + '&page=1&size=500')
          .catch(function (e) {
            if (!isEndpointMissing(e)) { throw e; }
            var dq = (vm.f.deptId == null || vm.f.deptId === '') ? '' : ('deptId=' + HIS.idParam(vm.f.deptId));
            return getA('/overdue' + (dq ? '?' + dq : '')).catch(function () { return []; });
          })
          .then(function (d) {
            vm.pg.loaded = true;
            vm.applyPendingFilter(normRecords(d));
          })
          .catch(function (e) {
            vm.pg.rows = []; vm.pg.total = 0;
            HIS.notifyError(e);
          })
          .finally(function () { vm.pg.loading = false; });
      },
      applyPendingFilter: function (arr) {
        var vm = this;
        var list = (arr || []).filter(function (row) { return vm.passPendingFilter(row); });
        vm.pendingCache = list;
        vm.pg.total = list.length;
        var start = (vm.pg.page - 1) * vm.pg.size;
        vm.pg.rows = list.slice(start, start + vm.pg.size);
      },
      passPendingFilter: function (row) {
        var f = this.f;
        var name = String(f.doctorName || '').trim();
        var doc = pickStr(row && row.doctorName, row && row.attendingDoctorName, row && row.docName, row && row.doctor) || '';
        if (name && doc.indexOf(name) < 0) { return false; }
        if (f.dateRange && f.dateRange.length === 2) {
          var dd = date10(row && pickStr(row.dischargeDate, row.dischargeTime, row.outDate));
          if (!dd || dd < f.dateRange[0] || dd > f.dateRange[1]) { return false; }
        }
        if (f.deptId != null && f.deptId !== '') {
          var rid = row && pickStr(row.deptId, row.departmentId);
          if (rid && !HIS.sameId(rid, f.deptId)) { return false; }
        }
        var r = f.overdueRange;
        if (r) {
          var d = this.overdueDaysOf(row);
          if (d == null) { return false; }
          if (r === '3-7' && !(d >= 3 && d < 7)) { return false; }
          if (r === '7-14' && !(d >= 7 && d < 14)) { return false; }
          if (r === '14+' && !(d >= 14)) { return false; }
        }
        return true;
      },
      onPendingSearch: function () { this.pg.page = 1; this.loadPending(); },
      onPendingReset: function () {
        this.f = { dateRange: null, deptId: null, doctorName: '', overdueRange: null };
        this.pg.page = 1;
        this.localMode = false;
        this.pendingCache = [];
        this.loadPending();
      },
      onPendingPage: function (p) {
        this.pg.page = p;
        if (this.localMode && this.pendingCache.length) { this.applyPendingFilter(this.pendingCache); return; }
        this.loadPending();
      },
      onPendingSize: function (s) {
        this.pg.size = s;
        this.pg.page = 1;
        if (this.localMode && this.pendingCache.length) { this.applyPendingFilter(this.pendingCache); return; }
        this.loadPending();
      },

      /* ================= Tab2/3 归档管理 · 封存管理 ================= */
      loadArch: function (which, resetPage) {
        var vm = this;
        var p = vm.arch[which];
        if (!p) { return; }
        if (which === 'repair') { return vm.loadRepairLocal(resetPage); }
        var stMap = { archived: ST.ARCHIVED, recall: ST.RECALL };
        if (resetPage) { p.page = 1; }
        p.loading = true;
        return getA('/list?status=' + stMap[which] + '&page=' + p.page + '&size=' + p.size)
          .then(function (d) {
            p.rows = normRecords(d);
            p.total = normTotal(d, p.rows);
            p.loaded = true;
          })
          .catch(function (e) {
            p.rows = []; p.total = 0;
            if (!isEndpointMissing(e)) { HIS.notifyError(e); }
          })
          .finally(function () { p.loading = false; });
      },
      /* 返修中 = 已审核(3)且召回审批通过(recall_approved=1): 后端 /list 仅单状态过滤,
       * 拉取已审核全量后前端过滤退回返修行 + 本地分页(500 上限与待归档兜底同口径) */
      loadRepairLocal: function (resetPage) {
        var vm = this;
        var p = vm.arch.repair;
        if (resetPage) { p.page = 1; }
        p.loading = true;
        return getA('/list?status=' + ST.REVIEWED + '&page=1&size=500')
          .then(function (d) {
            var rows = normRecords(d).filter(function (r) { return vm.recallApprovedOf(r) === 1; });
            p.total = rows.length;
            var start = (p.page - 1) * p.size;
            p.rows = rows.slice(start, start + p.size);
            p.loaded = true;
          })
          .catch(function (e) {
            p.rows = []; p.total = 0;
            if (!isEndpointMissing(e)) { HIS.notifyError(e); }
          })
          .finally(function () { p.loading = false; });
      },
      ensureArchSub: function () {
        var p = this.arch[this.archSub];
        if (p && !p.loaded) { this.loadArch(this.archSub); }
      },
      onArchPage: function (v) { this.arch[this.archSub].page = v; this.loadArch(this.archSub); },
      onArchSize: function (s) { var p = this.arch[this.archSub]; p.size = s; p.page = 1; this.loadArch(this.archSub); },
      loadSeal: function (resetPage) {
        var vm = this;
        var p = vm.seal;
        if (resetPage) { p.page = 1; }
        p.loading = true;
        return getA('/list?status=' + ST.SEALED + '&page=' + p.page + '&size=' + p.size)
          .then(function (d) { p.rows = normRecords(d); p.total = normTotal(d, p.rows); p.loaded = true; })
          .catch(function (e) { p.rows = []; p.total = 0; if (!isEndpointMissing(e)) { HIS.notifyError(e); } })
          .finally(function () { p.loading = false; });
      },
      onSealPage: function (v) { this.seal.page = v; this.loadSeal(); },
      onSealSize: function (s) { this.seal.size = s; this.seal.page = 1; this.loadSeal(); },

      /* ================= Tab4 统计 ================= */
      onTab: function (name) {
        var vm = this;
        if (name === 'archive') { vm.ensureArchSub(); }
        else if (name === 'seal') { if (!vm.seal.loaded) { vm.loadSeal(); } }
        else if (name === 'stats') { vm.loadStatPanel(); }
        else if (name === 'webhook') { vm.ensureWebhook(); }
      },
      loadStatPanel: function () {
        var vm = this;
        if (vm.statLoaded) { return; }
        vm.statLoaded = true;
        vm.stLoading = true;
        return Promise.all([vm.loadTrend(), vm.loadDeptRank()]).finally(function () { vm.stLoading = false; });
      },
      reloadStat: function () {
        var vm = this;
        vm.statLoaded = true;
        vm.stLoading = true;
        return Promise.all([vm.loadStats(), vm.loadTrend(), vm.loadDeptRank()]).finally(function () { vm.stLoading = false; });
      },
      loadTrend: function () {
        var vm = this;
        var s = vm.stats || {};
        var arr = pickArr(s, ['monthlyTrend', 'monthTrend', 'trend', 'monthlyStats', 'monthStats', 'trendList']);
        if (arr && arr.length) { vm.trendRows = vm.normTrend(arr); return Promise.resolve(); }
        /* 后端未返趋势: 近6自然月并行 stats 查询兜底 */
        vm.trendLoading = true;
        var months = lastMonths(6);
        return Promise.all(months.map(function (m) {
          var r = monthRangeOf(m);
          return getA('/stats?startDate=' + r[0] + '&endDate=' + r[1]).catch(function () { return null; });
        })).then(function (rs) {
          vm.trendRows = months.map(function (m, i) {
            var st = rs[i] || {};
            return {
              month: m,
              count: pickNum(st.monthArchiveCount, st.monthArchivedCount, st.archivedCount, st.archiveCount, st.monthCount) || 0,
              rate: pickNum(st.submitRate72h, st.rate72h, st.submitRate, st.h72SubmitRate, st.onTimeRate)
            };
          });
        }).finally(function () { vm.trendLoading = false; });
      },
      normTrend: function (arr) {
        return (arr || []).map(function (x) {
          if (x == null || typeof x !== 'object') { return { month: String(x == null ? '-' : x), count: 0, rate: null }; }
          return {
            month: pickStr(x.month, x.ym, x.monthLabel, x.period) || '-',
            count: pickNum(x.count, x.archivedCount, x.archiveCount, x.total, x.value, x.num) || 0,
            rate: pickNum(x.rate, x.rate72h, x.submitRate, x.submitRate72h, x.percent)
          };
        });
      },
      loadDeptRank: function () {
        var vm = this;
        var s = vm.stats || {};
        var arr = pickArr(s, ['deptRanking', 'deptRank', 'deptProgress', 'byDept', 'deptStats', 'deptArchiveRank']);
        vm.deptRank = (arr || []).map(function (x) {
          if (x == null) { return null; }
          if (typeof x !== 'object') { return { name: String(x), archived: 0, should: null, rate: 0 }; }
          var archived = pickNum(x.archivedCount, x.archived, x.count, x.value, x.archivedTotal) || 0;
          var should = pickNum(x.shouldCount, x.totalCount, x.should, x.total, x.shouldTotal);
          var rate = pickNum(x.rate, x.percent, x.completionRate);
          if (rate == null) { rate = (should && should > 0) ? Number((archived * 100 / should).toFixed(1)) : 0; }
          return { name: pickStr(x.deptName, x.name, x.label) || '-', archived: archived, should: should, rate: rate };
        }).filter(function (x) { return !!x; }).sort(function (a, b) { return b.rate - a.rate; }).slice(0, 10);
      },
      exportCsv: function () {
        var vm = this;
        var r = vm.monthRange();
        var rows = [
          ['病历归档工作台统计'],
          ['导出时间', new Date().toLocaleString()],
          ['统计区间', r[0] + ' ~ ' + r[1] + '(自然月, 环比对比上月)'],
          [],
          ['核心指标', '数值', '环比']
        ];
        (vm.statKpis || []).forEach(function (k) { rows.push([k.title, k.curText, k.diffText]); });
        rows.push([]);
        rows.push(['科室归档进度排行(TOP10)']);
        rows.push(['名次', '科室', '已归档', '应归档', '完成率']);
        (vm.deptRank || []).forEach(function (d, i) {
          rows.push([i + 1, d.name, d.archived, d.should == null ? '' : d.should, d.rate == null ? '' : d.rate + '%']);
        });
        rows.push([]);
        rows.push(['月度趋势(近6月)']);
        rows.push(['月份', '归档数', '72h提交率']);
        (vm.trendRows || []).forEach(function (t) {
          rows.push([t.month, t.count, t.rate == null ? '' : Number(t.rate).toFixed(1) + '%']);
        });
        vm.exporting = true;
        downloadCsv('病历归档统计_' + r[0] + '_' + r[1] + '.csv', toCsvRows(rows));
        HIS.notifySuccess('CSV 已导出');
        vm.exporting = false;
      },

      /* ================= Tab5 Webhook 订阅(P7b-1 互操作) ================= */
      seqWk: function (i) { return (this.wk.page - 1) * this.wk.size + i + 1; },
      wkId: function (row) { return HIS.idKey(row && row.id); },
      wkName: function (row) { return (row && row.subscriberName) || '-'; },
      wkFailCount: function (row) { return pickNum(row && row.failCount) || 0; },
      wkFused: function (row) { return this.wkFailCount(row) >= WK_FUSE_LIMIT; },
      wkStatusText: function (row) {
        if (this.wkFused(row)) { return '已熔断'; }
        return Number(row && row.status) === 1 ? '启用' : '禁用';
      },
      wkStatusTag: function (row) {
        if (this.wkFused(row)) { return 'danger'; }
        return Number(row && row.status) === 1 ? 'success' : 'info';
      },
      /* 熔断行(fail_count>=5)浅红强警示 */
      wkRowCls: function (ctx) { return this.wkFused(ctx && ctx.row) ? 'ea-row-fuse' : ''; },
      /* 订阅事件逗号分隔串拆分(表单回显/列表 tag) */
      wkEventList: function (row) {
        return String((row && row.eventTypes) || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean);
      },
      wkEventLabel: function (t) { return this.wkTypeMap[t] || t; },
      ensureWebhook: function () {
        var vm = this;
        if (!vm.wk.loaded) { vm.loadWebhooks(); }
        if (!vm.wk.typesLoaded) { vm.loadWebhookTypes(); }
      },
      loadWebhooks: function () {
        var vm = this;
        vm.wk.loading = true;
        return HIS.get(INTEROP_API + '/list').then(function (d) {
          vm.wk.all = Array.isArray(d) ? d : [];
          vm.wk.loaded = true;
          vm.applyWkPage();
        }).catch(function (e) {
          vm.wk.all = []; vm.wk.rows = []; vm.wk.total = 0;
          HIS.notifyError(e);
        }).finally(function () { vm.wk.loading = false; });
      },
      /* 事件类型清单([{name,displayName}], 供新增/编辑多选下拉) */
      loadWebhookTypes: function () {
        var vm = this;
        return HIS.get(INTEROP_API + '/event-types').then(function (d) {
          vm.wk.types = Array.isArray(d) ? d : [];
          vm.wk.typesLoaded = true;
        }).catch(function () { vm.wk.types = []; });   /* 清单缺失: 回退枚举名展示 */
      },
      applyWkPage: function () {
        var vm = this;
        vm.wk.total = vm.wk.all.length;
        var start = (vm.wk.page - 1) * vm.wk.size;
        vm.wk.rows = vm.wk.all.slice(start, start + vm.wk.size);
      },
      onWkPage: function (p) { this.wk.page = p; this.applyWkPage(); },
      onWkSize: function (s) { this.wk.size = s; this.wk.page = 1; this.applyWkPage(); },
      wkOpenCreate: function () {
        this.wk.dlg = {
          visible: true, editId: null, submitting: false,
          form: { subscriberName: '', callbackUrl: '', eventList: [], secretKey: '', statusOn: true }
        };
      },
      wkOpenEdit: function (row) {
        this.wk.dlg = {
          visible: true, editId: this.wkId(row), submitting: false,
          form: {
            subscriberName: row.subscriberName || '',
            callbackUrl: row.callbackUrl || '',
            eventList: this.wkEventList(row),
            secretKey: row.secretKey || '',
            statusOn: Number(row.status) === 1
          }
        };
      },
      wkSave: function () {
        var vm = this;
        var d = vm.wk.dlg, f = d.form;
        var name = String(f.subscriberName || '').trim();
        var url = String(f.callbackUrl || '').trim();
        if (!name) { ElementPlus.ElMessage.warning('请填写订阅名称'); return; }
        if (!/^https?:\/\/.+/i.test(url)) { ElementPlus.ElMessage.warning('回调URL须以 http:// 或 https:// 开头'); return; }
        if (!f.eventList || !f.eventList.length) { ElementPlus.ElMessage.warning('请至少选择一种订阅事件类型'); return; }
        var body = { subscriberName: name, callbackUrl: url, eventTypes: f.eventList.join(','), secretKey: String(f.secretKey || '').trim() };
        var req;
        if (d.editId) {
          body.status = f.statusOn ? 1 : 0;   /* 空密钥不覆盖(后端仅覆写非空字段) */
          req = HIS.put(INTEROP_API + '/' + HIS.idParam(d.editId), body);
        } else {
          req = HIS.post(INTEROP_API, body);
        }
        d.submitting = true;
        req.then(function () {
          HIS.notifySuccess(d.editId ? '订阅已更新' : '订阅已创建');
          d.visible = false;
          vm.loadWebhooks();
        }).catch(HIS.notifyError).finally(function () { d.submitting = false; });
      },
      wkDelete: function (row) {
        var vm = this;
        var id = vm.wkId(row);
        ElementPlus.ElMessageBox.confirm(
          '确认删除订阅「' + vm.wkName(row) + '」？删除后该回调地址将不再收到任何病历事件推送。',
          '删除订阅', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.del(INTEROP_API + '/' + HIS.idParam(id));
        }).then(function () {
          HIS.notifySuccess('订阅已删除');
          vm.loadWebhooks();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* 连通性测试: 同步真实投递 TEST 事件, 展示成功/失败+耗时; 失败计数变化后刷新列表 */
      wkTest: function (row) {
        var vm = this;
        var id = vm.wkId(row);
        vm.wk.testingId = id;
        HIS.post(INTEROP_API + '/' + HIS.idParam(id) + '/test').then(function (d) {
          var ms = d && d.elapsedMs != null ? d.elapsedMs + 'ms' : '—';
          if (d && d.success) {
            ElementPlus.ElMessage.success('推送成功 · 耗时 ' + ms);
          } else {
            ElementPlus.ElMessageBox.alert(
              '回调地址未成功接收测试报文(耗时 ' + ms + ')。请检查回调服务可用性与网络策略。' + (d && d.error ? '\n' + d.error : ''),
              '测试推送失败', { type: 'error' }
            );
          }
          vm.loadWebhooks();
        }).catch(HIS.notifyError).finally(function () { vm.wk.testingId = null; });
      },

      /* ================= 流转操作 ================= */
      submitOne: function (row) {
        var vm = this;
        var id = recId(row);
        ElementPlus.ElMessageBox.confirm(
          '确认提交「' + vm.patientName(row) + '」的病历归档？提交后将生成归档PDF并进入已归档列表。',
          '提交归档', { type: 'warning', confirmButtonText: '提交归档', cancelButtonText: '取消' }
        ).then(function () {
          vm.submittingId = id;
          return postA('/submit/' + HIS.idParam(id));
        }).then(function () {
          HIS.notifySuccess('已提交归档');
          vm.refreshCurrent();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.submittingId = null; });
      },
      batchSubmit: function () {
        var vm = this;
        var ids = (vm.selRows || []).map(recId).filter(function (x) { return x != null; });
        if (!ids.length) { return; }
        ElementPlus.ElMessageBox.confirm(
          '确认对已选 ' + ids.length + ' 份病历批量提交归档？',
          '批量归档', { type: 'warning', confirmButtonText: '批量归档', cancelButtonText: '取消' }
        ).then(function () {
          vm.batchSubmitting = true;
          return postA('/batch-submit', ids);
        }).then(function () {
          HIS.notifySuccess('批量归档已提交(' + ids.length + ' 份)');
          vm.selRows = [];
          vm.refreshCurrent();
        }).catch(function (e) {
          if (e === 'cancel' || e === 'close') { return; }
          if (isEndpointMissing(e)) { return vm.submitEach(ids); }
          HIS.notifyError(e);
        }).finally(function () { vm.batchSubmitting = false; });
      },
      submitEach: function (ids) {
        /* /batch-submit 缺失降级: 逐条 /submit 兜底(部分失败明确提示) */
        var vm = this;
        return Promise.all(ids.map(function (id) {
          return postA('/submit/' + HIS.idParam(id)).then(function () { return true; }).catch(function () { return false; });
        })).then(function (rs) {
          var ok = rs.filter(Boolean).length;
          if (ok) { HIS.notifySuccess('已提交归档 ' + ok + '/' + ids.length + ' 份'); }
          if (ok < ids.length) { ElementPlus.ElMessage.warning((ids.length - ok) + ' 份提交失败, 请稍后重试'); }
          vm.selRows = [];
          vm.refreshCurrent();
        });
      },
      urge: function (row) {
        var vm = this;
        var id = recId(row);
        ElementPlus.ElMessageBox.confirm(
          '确认向「' + vm.doctorText(row) + '」医生发送归档催促提醒(实时推送)？',
          '催促归档', { type: 'warning', confirmButtonText: '发送催促', cancelButtonText: '取消' }
        ).then(function () {
          vm.urgingId = id;
          return postA('/urge/' + HIS.idParam(id));
        }).then(function () {
          HIS.notifySuccess('已发送催促提醒');
        }).catch(function (e) {
          if (e === 'cancel' || e === 'close') { return; }
          if (isEndpointMissing(e)) { ElementPlus.ElMessage.warning('催促通道不可用, 请稍后重试。'); return; }
          HIS.notifyError(e);
        }).finally(function () { vm.urgingId = null; });
      },
      openDetail: function (row) {
        var vm = this;
        vm.dlg.row = row;
        vm.dlg.visible = true;
        vm.dlg.loading = true;
        getA('/' + HIS.idParam(recId(row))).then(function (d) {
          if (d && typeof d === 'object' && !Array.isArray(d)) { vm.dlg.row = Object.assign({}, row, d); }
        }).catch(function () { /* 详情端点缺失: 保留列表行数据展示 */ })
          .finally(function () { vm.dlg.loading = false; });
      },
      openRecall: function (row) { this.recallDlg = { visible: true, row: row, reason: '', submitting: false }; },
      doRecall: function () {
        var vm = this;
        var d = vm.recallDlg;
        var reason = String(d.reason || '').trim();
        if (reason.length < 4) { ElementPlus.ElMessage.warning('请填写召回原因(不少于4个字)'); return; }
        d.submitting = true;
        postA('/recall/' + HIS.idParam(recId(d.row)) + '?reason=' + encodeURIComponent(reason))
          .then(function () {
            HIS.notifySuccess('召回申请已提交, 待审批');
            d.visible = false;
            vm.refreshCurrent();
          }).catch(HIS.notifyError).finally(function () { d.submitting = false; });
      },
      approveRecall: function (row, approved) {
        var vm = this;
        var id = recId(row);
        var act = approved ? '通过' : '驳回';
        ElementPlus.ElMessageBox.confirm(
          '确认' + act + '「' + vm.patientName(row) + '」的召回申请？' + (approved ? '通过后病历将进入返修/整改状态。' : ''),
          '召回审批', { type: approved ? 'info' : 'warning', confirmButtonText: '确认' + act, cancelButtonText: '取消' }
        ).then(function () {
          vm.auditBusyId = id;
          return putA('/recall/' + HIS.idParam(id) + '/approve?approved=' + (approved ? 'true' : 'false'));
        }).then(function () {
          HIS.notifySuccess('已' + act + '召回申请');
          vm.refreshCurrent();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.auditBusyId = null; });
      },
      openSeal: function (row) { this.sealDlg = { visible: true, row: row, reason: '', submitting: false }; },
      doSeal: function () {
        var vm = this;
        var d = vm.sealDlg;
        var reason = String(d.reason || '').trim();
        if (reason.length < 4) { ElementPlus.ElMessage.warning('请填写封存原因(不少于4个字)'); return; }
        d.submitting = true;
        postA('/seal/' + HIS.idParam(recId(d.row)) + '?reason=' + encodeURIComponent(reason))
          .then(function () {
            HIS.notifySuccess('病历已封存');
            d.visible = false;
            vm.refreshCurrent();
          }).catch(HIS.notifyError).finally(function () { d.submitting = false; });
      },
      unseal: function (row) {
        var vm = this;
        var id = recId(row);
        ElementPlus.ElMessageBox.confirm(
          '确认解除「' + vm.patientName(row) + '」的病历封存？解封为受控操作, 将记入操作留痕, 仅病案管理权限可执行。',
          '解除封存', { type: 'warning', confirmButtonText: '解除封存', cancelButtonText: '取消' }
        ).then(function () {
          vm.sealingId = id;
          return postA('/unseal/' + HIS.idParam(id));
        }).then(function () {
          HIS.notifySuccess('已解除封存');
          vm.refreshCurrent();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.sealingId = null; });
      },
      viewPdf: function (row) {
        var vm = this;
        var w = window.open('', '_blank');
        vm.fetchPdfBlobUrl(row).then(function (u) {
          if (u) {
            if (w) { w.location.href = u; } else { window.open(u, '_blank'); }
          } else {
            if (w) { w.close(); }
            ElementPlus.ElMessage.warning('PDF 未生成或暂不可访问(后端生成后可见)');
          }
        });
      },
      fetchPdfBlobUrl: function (row) {
        /* 病历PDF走 InpPrint P7a-3 真实端点(/api/his/inp/print/emr-pdf/{id}, inline); 失败回落 pdfPath 直链 */
        var headers = {};
        var token = HIS.getToken();
        if (token) { headers['Authorization'] = 'Bearer ' + token; }
        var direct = row && pickStr(row.pdfPath, row.pdfUrl);
        return fetch('/api/his/inp/print/emr-pdf/' + HIS.idParam(recId(row)), { headers: headers }).then(function (resp) {
          var ct = resp.headers.get('Content-Type') || '';
          if (!resp.ok || ct.indexOf('json') >= 0) { throw new Error('pdf-unavailable'); }
          return resp.blob();
        }).then(function (b) { return URL.createObjectURL(b); })
          .catch(function () { return (direct && /^https?:/i.test(direct)) ? direct : null; });
      },
      downloadPdf: function (row) {
        var vm = this;
        var name = vm.patientName(row) + '_病历.pdf';
        return HIS.download('/api/his/inp/print/emr-pdf/' + HIS.idParam(recId(row)) + '?download=true', name).catch(function (e) {
          var direct = row && pickStr(row.pdfPath, row.pdfUrl);
          if (direct && /^https?:/i.test(direct)) { return HIS.download(direct, name); }
          HIS.notifyError(e);
        });
      },
      verifyPdfIntegrity: function (row) {
        /* 归档PDF防篡改回查: 调 InpPrint /emr-pdf/{id}/verify 重算 SHA-256 与落库 pdf_sha256 比对 */
        var vm = this;
        var id = recId(row);
        vm.verifyBusyId = id;
        HIS.get('/api/his/inp/print/emr-pdf/' + HIS.idParam(id) + '/verify').then(function (d) {
          d = d || {};
          var ok = d.valid === true;
          function esc(v) {
            return String(v == null ? '—' : v).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; });
          }
          var html = '<div style="line-height:1.9;">'
            + '<div>校验结果: <b style="color:' + (ok ? '#67c23a' : '#f56c6c') + ';">'
            + (ok ? '通过 · 文件未被篡改' : '不通过 · 文件可能已被替换/截断') + '</b></div>'
            + '<div>生成时间: ' + esc(fmtDT(d.generatedTime)) + '</div>'
            + '<div style="word-break:break-all;">期望指纹: ' + esc(d.expected) + '</div>'
            + '<div style="word-break:break-all;">实际指纹: ' + esc(d.actual) + '</div>'
            + (d.message ? '<div>提示: ' + esc(d.message) + '</div>' : '')
            + '</div>';
          ElementPlus.ElMessageBox.alert(html, ok ? '防篡改校验通过' : '防篡改校验未通过',
            { type: ok ? 'success' : 'error', dangerouslyUseHTMLString: true });
        }).catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.verifyBusyId = null; });
      },

      /* ================= 刷新 / SSE ================= */
      refreshCurrent: function () {
        var vm = this;
        vm.loadStats();
        if (vm.tab === 'pending') { vm.loadPending(); }
        else if (vm.tab === 'archive') { vm.loadArch(vm.archSub); }
        else if (vm.tab === 'seal') { vm.loadSeal(); }
        else if (vm.tab === 'stats') { vm.reloadStat(); }
      },
      bindSse: function () {
        /* 归档域实时事件(归档/召回/封存/解封)触发当前视图轻量刷新 */
        var vm = this;
        var c = window.HIS && HIS.EmrSseClient;
        if (!c || typeof c.on !== 'function') { return; }
        ['RECORD_ARCHIVED', 'RECORD_RECALLED', 'RECORD_SEALED', 'RECORD_UNSEALED'].forEach(function (t) {
          var off = c.on(t, function () { vm.onSseEvent(); });
          if (typeof off === 'function') { vm.sseOffs.push(off); }
        });
      },
      onSseEvent: function () {
        var vm = this;
        vm.loadStats();
        if (vm.tab === 'pending') { vm.loadPending(); }
        else if (vm.tab === 'archive') { vm.loadArch(vm.archSub); }
        else if (vm.tab === 'seal') { vm.loadSeal(); }
      }
    },
    template: [
      '<div class="page-card cd-fill cd-tabs">',
      '  <div class="page-title">病历归档工作台 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">归档全流程管控 · 72h提交率 · 返修追踪 · 封存留痕</span></div>',

      /* ===== 顶部 KPI 卡(始终可见): 72h提交率/返修率/本月归档数/逾期未归档 ===== */,
      '  <div class="ea-kpis" v-loading="kpiLoading">',
      '    <div v-for="k in kpis" :key="k.key" class="ea-kpi" :class="\'tone-\' + k.tone">',
      '      <div class="ea-kpi-t">{{ k.title }}</div>',
      '      <div class="ea-kpi-n" :class="k.tone">{{ k.value }}</div>',
      '      <div class="ea-kpi-s">{{ k.sub }}</div>',
      '    </div>',
      '  </div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',

      /* ===== Tab1 待归档(默认): 筛选 + 逾期分档 + 批量归档 ===== */,
      '    <el-tab-pane label="待归档" name="pending">',
      '      <div class="ea-filter">',
      '        <el-form inline size="small">',
      '          <el-form-item label="出院日期">',
      '            <el-date-picker v-model="f.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px;"></el-date-picker>',
      '          </el-form-item>',
      '          <el-form-item label="科室">',
      '            <el-select v-model="f.deptId" clearable filterable placeholder="全部" style="width:160px;">',
      '              <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="医生姓名">',
      '            <el-input v-model="f.doctorName" clearable placeholder="主管医生" style="width:130px;" @keyup.enter="onPendingSearch"></el-input>',
      '          </el-form-item>',
      '          <el-form-item label="逾期天数">',
      '            <el-select v-model="f.overdueRange" clearable placeholder="全部" style="width:110px;">',
      '              <el-option v-for="o in overdueOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item>',
      '            <el-button type="primary" size="small" :loading="pg.loading" @click="onPendingSearch">查询</el-button>',
      '            <el-button size="small" @click="onPendingReset">重置</el-button>',
      '          </el-form-item>',
      '        </el-form>',
      '      </div>',
      '      <div class="ea-bar">',
      '        <div>',
      '          <el-button type="primary" size="small" :disabled="!selRows.length" :loading="batchSubmitting" @click="batchSubmit">批量归档</el-button>',
      '          <span class="ea-note" style="margin-left:8px;">已选 {{ selRows.length }} 份</span>',
      '          <span class="ea-note" style="margin-left:8px;" v-if="localMode">逾期档位筛选采用全量拉取 + 本地分页</span>',
      '        </div>',
      '        <el-button size="small" @click="loadPending()">刷新</el-button>',
      '      </div>',
      '      <div class="ea-table-wrap">',
      '        <el-table :data="pg.rows" height="100%" size="small" border v-loading="pg.loading" :row-class-name="rowClsPending" @selection-change="onSelChange" empty-text="暂无待归档病历">',
      '          <el-table-column type="selection" width="42"></el-table-column>',
      '          <el-table-column type="index" :index="seqPending" label="序号" width="55" align="center"></el-table-column>',
      '          <el-table-column label="患者姓名" min-width="90" show-overflow-tooltip>',
      '            <template #default="s">{{ patientName(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="住院号" width="110" show-overflow-tooltip>',
      '            <template #default="s">{{ admissionNo(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="科室" min-width="110" show-overflow-tooltip>',
      '            <template #default="s">{{ deptText(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="主管医生" width="90" show-overflow-tooltip>',
      '            <template #default="s">{{ doctorText(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="出院日期" width="100">',
      '            <template #default="s">{{ dischargeDate(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="逾期天数" width="90" align="center">',
      '            <template #default="s"><span :class="overdueCls(s.row)">{{ overdueText(s.row) }}</span></template>',
      '          </el-table-column>',
      '          <el-table-column label="病历类型" width="100" show-overflow-tooltip>',
      '            <template #default="s">{{ recordTypeText(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="状态" width="84" align="center">',
      '            <template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusText(s.row.status) }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="200" fixed="right" align="center">',
      '            <template #default="s">',
      '              <el-button link type="primary" size="small" :loading="submittingId === recIdOf(s.row)" @click="submitOne(s.row)">提交归档</el-button>',
      '              <el-button link type="warning" size="small" :loading="urgingId === recIdOf(s.row)" @click="urge(s.row)">催促</el-button>',
      '              <el-button link size="small" @click="openDetail(s.row)">查看病历</el-button>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '      </div>',
      '      <div class="ea-pager">',
      '        <el-pagination small background layout="total, sizes, prev, pager, next" :total="pg.total" :page-size="pg.size" :page-sizes="pageSizes" :current-page="pg.page" @current-change="onPendingPage" @size-change="onPendingSize"></el-pagination>',
      '      </div>',
      '    </el-tab-pane>',

      /* ===== Tab2 归档管理: 已归档/召回中/返修中 三子列表 ===== */,
      '    <el-tab-pane label="归档管理" name="archive">',
      '      <div class="ea-bar">',
      '        <el-radio-group v-model="archSub" size="small" @change="ensureArchSub">',
      '          <el-radio-button label="archived">已归档</el-radio-button>',
      '          <el-radio-button label="recall">召回中</el-radio-button>',
      '          <el-radio-button label="repair">返修中</el-radio-button>',
      '        </el-radio-group>',
      '        <el-button size="small" @click="loadArch(archSub)">刷新</el-button>',
      '      </div>',

      /* ---- 已归档: 召回申请 / PDF 查看下载 / 封存 ---- */,
      '      <div class="ea-subwrap" v-if="archSub === \'archived\'">',
      '        <div class="ea-table-wrap">',
      '          <el-table :data="arch.archived.rows" height="100%" size="small" border v-loading="arch.archived.loading" empty-text="暂无已归档病历">',
      '            <el-table-column type="index" :index="seqArch" label="序号" width="55" align="center"></el-table-column>',
      '            <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ patientName(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="住院号" width="110" show-overflow-tooltip>',
      '              <template #default="s">{{ admissionNo(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="科室" min-width="100" show-overflow-tooltip>',
      '              <template #default="s">{{ deptText(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="主管医生" width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ doctorText(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="归档时间" width="130">',
      '              <template #default="s">{{ archiveTimeOf(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="归档人" width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ archiveByOf(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="PDF状态" width="88" align="center">',
      '              <template #default="s"><el-tag size="small" :type="pdfTag(s.row)">{{ pdfText(s.row) }}</el-tag></template>',
      '            </el-table-column>',
      '            <el-table-column label="操作" width="340" fixed="right" align="center">',
      '              <template #default="s">',
      '                <el-button link type="primary" size="small" :disabled="!hasPdf(s.row)" @click="viewPdf(s.row)">查看PDF</el-button>',
      '                <el-button link type="primary" size="small" :disabled="!hasPdf(s.row)" @click="downloadPdf(s.row)">下载PDF</el-button>',
      '                <el-button link type="success" size="small" :disabled="!hasPdf(s.row)" :loading="verifyBusyId === recIdOf(s.row)" @click="verifyPdfIntegrity(s.row)">防篡改校验</el-button>',
      '                <el-button link type="warning" size="small" @click="openRecall(s.row)">召回申请</el-button>',
      '                <el-button link type="danger" size="small" @click="openSeal(s.row)">封存</el-button>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="ea-pager">',
      '          <el-pagination small background layout="total, sizes, prev, pager, next" :total="arch.archived.total" :page-size="arch.archived.size" :page-sizes="pageSizes" :current-page="arch.archived.page" @current-change="onArchPage" @size-change="onArchSize"></el-pagination>',
      '        </div>',
      '      </div>',

      /* ---- 召回中: 审批通过 / 驳回(二次确认) ---- */,
      '      <div class="ea-subwrap" v-else-if="archSub === \'recall\'">',
      '        <div class="ea-table-wrap">',
      '          <el-table :data="arch.recall.rows" height="100%" size="small" border v-loading="arch.recall.loading" empty-text="暂无召回申请">',
      '            <el-table-column type="index" :index="seqRecall" label="序号" width="55" align="center"></el-table-column>',
      '            <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ patientName(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="住院号" width="110" show-overflow-tooltip>',
      '              <template #default="s">{{ admissionNo(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="科室" min-width="100" show-overflow-tooltip>',
      '              <template #default="s">{{ deptText(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="申请人" width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ recallByOf(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="申请时间" width="130">',
      '              <template #default="s">{{ recallTimeOf(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="召回原因" min-width="170" show-overflow-tooltip>',
      '              <template #default="s">{{ recallReasonOf(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="审批状态" width="90" align="center">',
      '              <template #default="s"><el-tag size="small" :type="recallTag(recallApprovedOf(s.row))">{{ recallText(recallApprovedOf(s.row)) }}</el-tag></template>',
      '            </el-table-column>',
      '            <el-table-column label="操作" width="150" fixed="right" align="center">',
      '              <template #default="s">',
      '                <el-button link type="success" size="small" :loading="auditBusyId === recIdOf(s.row)" :disabled="recallApprovedOf(s.row) === 1 || recallApprovedOf(s.row) === 2" @click="approveRecall(s.row, true)">通过</el-button>',
      '                <el-button link type="danger" size="small" :loading="auditBusyId === recIdOf(s.row)" :disabled="recallApprovedOf(s.row) === 1 || recallApprovedOf(s.row) === 2" @click="approveRecall(s.row, false)">驳回</el-button>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="ea-pager">',
      '          <el-pagination small background layout="total, sizes, prev, pager, next" :total="arch.recall.total" :page-size="arch.recall.size" :page-sizes="pageSizes" :current-page="arch.recall.page" @current-change="onArchPage" @size-change="onArchSize"></el-pagination>',
      '        </div>',
      '      </div>',

      /* ---- 返修中: 查看病历 / 重新提交归档 ---- */,
      '      <div class="ea-subwrap" v-else>',
      '        <div class="ea-table-wrap">',
      '          <el-table :data="arch.repair.rows" height="100%" size="small" border v-loading="arch.repair.loading" empty-text="暂无返修中病历">',
      '            <el-table-column type="index" :index="seqRepair" label="序号" width="55" align="center"></el-table-column>',
      '            <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ patientName(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="住院号" width="110" show-overflow-tooltip>',
      '              <template #default="s">{{ admissionNo(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="科室" min-width="100" show-overflow-tooltip>',
      '              <template #default="s">{{ deptText(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="主管医生" width="90" show-overflow-tooltip>',
      '              <template #default="s">{{ doctorText(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="召回原因" min-width="170" show-overflow-tooltip>',
      '              <template #default="s">{{ recallReasonOf(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="状态" width="84" align="center">',
      '              <template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusText(s.row.status) }}</el-tag></template>',
      '            </el-table-column>',
      '            <el-table-column label="操作" width="170" fixed="right" align="center">',
      '              <template #default="s">',
      '                <el-button link size="small" @click="openDetail(s.row)">查看病历</el-button>',
      '                <el-button link type="primary" size="small" :loading="submittingId === recIdOf(s.row)" @click="submitOne(s.row)">重新归档</el-button>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="ea-pager">',
      '          <el-pagination small background layout="total, sizes, prev, pager, next" :total="arch.repair.total" :page-size="arch.repair.size" :page-sizes="pageSizes" :current-page="arch.repair.page" @current-change="onArchPage" @size-change="onArchSize"></el-pagination>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ===== Tab3 封存管理: 解封为受控操作 ===== */,
      '    <el-tab-pane label="封存管理" name="seal">',
      '      <div class="ea-bar">',
      '        <span class="ea-note">封存病历将被锁定, 不可修改与召回; 解封为受控操作, 记入操作留痕, 仅病案管理权限可执行</span>',
      '        <el-button size="small" @click="loadSeal()">刷新</el-button>',
      '      </div>',
      '      <div class="ea-table-wrap">',
      '        <el-table :data="seal.rows" height="100%" size="small" border v-loading="seal.loading" empty-text="暂无已封存病历">',
      '          <el-table-column type="index" :index="seqSeal" label="序号" width="55" align="center"></el-table-column>',
      '          <el-table-column label="患者" min-width="90" show-overflow-tooltip>',
      '            <template #default="s">{{ patientName(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="住院号" width="110" show-overflow-tooltip>',
      '            <template #default="s">{{ admissionNo(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="科室" min-width="100" show-overflow-tooltip>',
      '            <template #default="s">{{ deptText(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="封存时间" width="130">',
      '            <template #default="s">{{ sealTimeOf(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="封存人" width="90" show-overflow-tooltip>',
      '            <template #default="s">{{ sealByOf(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="封存原因" min-width="180" show-overflow-tooltip>',
      '            <template #default="s">{{ sealReasonOf(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="130" fixed="right" align="center">',
      '            <template #default="s">',
      '              <el-button link size="small" @click="openDetail(s.row)">详情</el-button>',
      '              <el-button link type="danger" size="small" :loading="sealingId === recIdOf(s.row)" @click="unseal(s.row)">解封</el-button>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '      </div>',
      '      <div class="ea-pager">',
      '        <el-pagination small background layout="total, sizes, prev, pager, next" :total="seal.total" :page-size="seal.size" :page-sizes="pageSizes" :current-page="seal.page" @current-change="onSealPage" @size-change="onSealSize"></el-pagination>',
      '      </div>',
      '    </el-tab-pane>',

      /* ===== Tab4 统计仪表盘: KPI环比 + 科室排行 + 月度趋势 + 导出 ===== */,
      '    <el-tab-pane label="统计" name="stats">',
      '      <div class="ea-scroll" v-loading="stLoading">',
      '        <div class="ea-bar">',
      '          <span class="ea-note">统计口径: 自然月累计, 环比对比上月(差值为百分点/绝对数)</span>',
      '          <div>',
      '            <el-button size="small" :loading="stLoading" @click="reloadStat">刷新</el-button>',
      '            <el-button size="small" type="primary" :loading="exporting" @click="exportCsv">导出CSV</el-button>',
      '          </div>',
      '        </div>',
      '        <div class="ea-kpis">',
      '          <div v-for="k in statKpis" :key="k.key" class="ea-kpi" :class="\'tone-\' + k.tone">',
      '            <div class="ea-kpi-t">{{ k.title }}</div>',
      '            <div class="ea-kpi-n" :class="k.tone">{{ k.curText }}</div>',
      '            <div class="ea-kpi-s">{{ k.diffText }}</div>',
      '          </div>',
      '        </div>',
      '        <div class="ea-grid2">',
      '          <div class="ea-panel">',
      '            <div class="ea-panel-t">科室归档进度排行(TOP10)</div>',
      '            <div v-if="!deptRank.length" class="ea-note">暂无数据(后端 /stats 返回 deptRanking/deptRank 字段后显示)</div>',
      '            <div class="ea-bar-row" v-for="(d, i) in deptRank" :key="d.name + i">',
      '              <span class="ea-bar-name">{{ i + 1 }}. {{ d.name }}</span>',
      '              <div class="ea-bar-track">',
      '                <div class="ea-bar-fill" :style="{ width: Math.min(100, d.rate || 0) + \'%\', background: (d.rate >= 90 ? \'var(--yb-success)\' : (d.rate >= 70 ? \'var(--yb-warning)\' : \'var(--yb-danger)\')) }"></div>',
      '              </div>',
      '              <span class="ea-bar-val">{{ d.archived }}{{ d.should == null ? \'\' : \'/\' + d.should }} 份 · {{ d.rate == null ? \'—\' : d.rate + \'%\' }}</span>',
      '            </div>',
      '          </div>',
      '          <div class="ea-panel">',
      '            <div class="ea-panel-t">月度趋势(近6月)</div>',
      '            <el-table :data="trendRows" size="small" border v-loading="trendLoading" max-height="300" empty-text="暂无趋势数据">',
      '              <el-table-column prop="month" label="月份" width="100"></el-table-column>',
      '              <el-table-column prop="count" label="归档数" width="90" align="center"></el-table-column>',
      '              <el-table-column label="72h提交率" align="center">',
      '                <template #default="s">{{ s.row.rate == null ? \'—\' : Number(s.row.rate).toFixed(1) + \'%\' }}</template>',
      '              </el-table-column>',
      '            </el-table>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ===== Tab5 Webhook 订阅(P7b-1): 事件推送管理 · 熔断警示 ===== */,
      '    <el-tab-pane label="Webhook订阅" name="webhook">',
      '      <div class="ea-bar">',
      '        <div>',
      '          <el-button type="primary" size="small" @click="wkOpenCreate">新增订阅</el-button>',
      '          <span class="ea-note" style="margin-left:8px;">外部系统订阅病历事件(归档/召回/封存等), 平台向回调地址推送 HMAC-SHA256 签名报文; 连续失败≥5次熔断</span>',
      '        </div>',
      '        <el-button size="small" :loading="wk.loading" @click="loadWebhooks">刷新</el-button>',
      '      </div>',
      '      <div class="ea-table-wrap">',
      '        <el-table :data="wk.rows" height="100%" size="small" border v-loading="wk.loading" :row-class-name="wkRowCls" empty-text="暂无 Webhook 订阅">',
      '          <el-table-column type="index" :index="seqWk" label="序号" width="55" align="center"></el-table-column>',
      '          <el-table-column label="订阅名称" min-width="120" show-overflow-tooltip>',
      '            <template #default="s">{{ wkName(s.row) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="回调URL" min-width="210" show-overflow-tooltip>',
      '            <template #default="s">{{ s.row.callbackUrl || \'-\' }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="订阅事件" min-width="180">',
      '            <template #default="s">',
      '              <el-tag v-for="t in wkEventList(s.row)" :key="t" size="small" type="info" style="margin:1px 4px 1px 0;">{{ wkEventLabel(t) }}</el-tag>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="状态" width="84" align="center">',
      '            <template #default="s"><el-tag size="small" :type="wkStatusTag(s.row)">{{ wkStatusText(s.row) }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="最后推送时间" width="130">',
      '            <template #default="s">{{ fmtDT(s.row.lastPushTime) }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="失败次数" width="84" align="center">',
      '            <template #default="s"><span :class="{ \'ea-day-danger\': wkFused(s.row), \'ea-day-warn\': wkFailCount(s.row) > 0 && !wkFused(s.row) }">{{ wkFailCount(s.row) }}</span></template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="170" fixed="right" align="center">',
      '            <template #default="s">',
      '              <el-button link type="primary" size="small" :loading="wk.testingId === wkId(s.row)" @click="wkTest(s.row)">测试推送</el-button>',
      '              <el-button link size="small" @click="wkOpenEdit(s.row)">编辑</el-button>',
      '              <el-button link type="danger" size="small" @click="wkDelete(s.row)">删除</el-button>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '      </div>',
      '      <div class="ea-pager">',
      '        <el-pagination small background layout="total, sizes, prev, pager, next" :total="wk.total" :page-size="wk.size" :page-sizes="pageSizes" :current-page="wk.page" @current-change="onWkPage" @size-change="onWkSize"></el-pagination>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',

      /* ===== 病历详情抽屉 ===== */,
      '  <el-drawer v-model="dlg.visible" title="病历归档详情" size="540px">',
      '    <div v-if="dlg.row" v-loading="dlg.loading" style="min-height:200px;">',
      '      <div class="ea-dl">',
      '        <div class="it"><div class="k">患者姓名</div><div class="v">{{ patientName(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">住院号</div><div class="v">{{ admissionNo(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">科室</div><div class="v">{{ deptText(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">主管医生</div><div class="v">{{ doctorText(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">出院日期</div><div class="v">{{ dischargeDate(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">病历类型</div><div class="v">{{ recordTypeText(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">当前状态</div><div class="v"><el-tag size="small" :type="statusTag(dlg.row.status)">{{ statusText(dlg.row.status) }}</el-tag></div></div>',
      '        <div class="it"><div class="k">逾期天数</div><div class="v"><span :class="overdueCls(dlg.row)">{{ overdueText(dlg.row) }}</span></div></div>',
      '        <div class="it"><div class="k">归档时间</div><div class="v">{{ archiveTimeOf(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">归档人</div><div class="v">{{ archiveByOf(dlg.row) }}</div></div>',
      '        <div class="it"><div class="k">PDF状态</div><div class="v"><el-tag size="small" :type="pdfTag(dlg.row)">{{ pdfText(dlg.row) }}</el-tag></div></div>',
      '      </div>',
      '      <template v-if="dlg.row.recallTime || dlg.row.recallReason || dlg.row.recallBy">',
      '        <div class="ea-sec-t">召回信息</div>',
      '        <div class="ea-dl">',
      '          <div class="it"><div class="k">申请人</div><div class="v">{{ recallByOf(dlg.row) }}</div></div>',
      '          <div class="it"><div class="k">申请时间</div><div class="v">{{ recallTimeOf(dlg.row) }}</div></div>',
      '          <div class="it"><div class="k">审批状态</div><div class="v"><el-tag size="small" :type="recallTag(recallApprovedOf(dlg.row))">{{ recallText(recallApprovedOf(dlg.row)) }}</el-tag></div></div>',
      '        </div>',
      '        <div class="ea-block" style="margin-top:8px;">{{ recallReasonOf(dlg.row) }}</div>',
      '      </template>',
      '      <template v-if="dlg.row.sealTime || dlg.row.sealReason || dlg.row.sealBy">',
      '        <div class="ea-sec-t">封存信息</div>',
      '        <div class="ea-dl">',
      '          <div class="it"><div class="k">封存人</div><div class="v">{{ sealByOf(dlg.row) }}</div></div>',
      '          <div class="it"><div class="k">封存时间</div><div class="v">{{ sealTimeOf(dlg.row) }}</div></div>',
      '        </div>',
      '        <div class="ea-block" style="margin-top:8px;">{{ sealReasonOf(dlg.row) }}</div>',
      '      </template>',
      '      <div style="margin-top:18px;" v-if="hasPdf(dlg.row)">',
      '        <el-button size="small" @click="viewPdf(dlg.row)">查看PDF</el-button>',
      '        <el-button size="small" @click="downloadPdf(dlg.row)">下载PDF</el-button>',
      '      </div>',
      '    </div>',
      '  </el-drawer>',

      /* ===== 弹窗: 召回申请 / 病历封存 ===== */,
      '  <el-dialog v-model="recallDlg.visible" title="召回申请" width="480px" append-to-body>',
      '    <div class="ea-note" style="margin-bottom:8px;">患者: {{ recallDlg.row ? patientName(recallDlg.row) : \'-\' }} · 召回后病历进入审批流程, 通过后转返修整改</div>',
      '    <el-input v-model="recallDlg.reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请填写召回原因(不少于4个字)"></el-input>',
      '    <template #footer>',
      '      <el-button size="small" @click="recallDlg.visible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="recallDlg.submitting" @click="doRecall">提交申请</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="sealDlg.visible" title="病历封存" width="480px" append-to-body>',
      '    <div class="ea-note" style="margin-bottom:8px;">患者: {{ sealDlg.row ? patientName(sealDlg.row) : \'-\' }} · 封存后病历不可修改, 解封需病案管理权限并记入留痕</div>',
      '    <el-input v-model="sealDlg.reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请填写封存原因(不少于4个字, 如医疗纠纷取证保全)"></el-input>',
      '    <template #footer>',
      '      <el-button size="small" @click="sealDlg.visible = false">取消</el-button>',
      '      <el-button size="small" type="danger" :loading="sealDlg.submitting" @click="doSeal">确认封存</el-button>',
      '    </template>',
      '  </el-dialog>',
      
      /* ===== 弹窗: Webhook 订阅新增/编辑(P7b-1) ===== */,
      '  <el-dialog v-model="wk.dlg.visible" :title="wk.dlg.editId ? \'编辑订阅\' : \'新增订阅\'" width="560px" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="订阅名称" required>',
      '        <el-input v-model="wk.dlg.form.subscriberName" maxlength="60" placeholder="如: 区域平台/互联网医院"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="回调URL" required>',
      '        <el-input v-model="wk.dlg.form.callbackUrl" placeholder="https://example.com/api/emr/webhook"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="事件类型" required>',
      '        <el-select v-model="wk.dlg.form.eventList" multiple placeholder="选择订阅事件(可多选)" style="width:100%;">',
      '          <el-option v-for="t in wk.types" :key="t.name" :label="t.displayName || t.name" :value="t.name"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="签名密钥">',
      '        <el-input v-model="wk.dlg.form.secretKey" :placeholder="wk.dlg.editId ? \'留空保持原密钥不变\' : \'留空由后端自动生成(HMAC-SHA256 签名)\'"></el-input>',
      '      </el-form-item>',
      '      <el-form-item v-if="wk.dlg.editId" label="状态">',
      '        <el-switch v-model="wk.dlg.form.statusOn" active-text="启用" inactive-text="禁用"></el-switch>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="wk.dlg.visible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="wk.dlg.submitting" @click="wkSave">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
