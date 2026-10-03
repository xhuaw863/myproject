/* ==================================================================
 * nursing-writer.js — 护理文书 Tiptap 书写器(住院护士站 P4a-3)
 * ------------------------------------------------------------------
 * 定位: 护士站结构化护理文书书写组件(Tiptap 富文本), 供 InpNursingRecord /
 *       InpNurseStation 以局部组件方式集成(P4a-5), 亦可经全局标签独立验证。
 * 版式: 头栏(记录类型/模板/患者/动作) + 主体双栏(左 Tiptap 编辑器 /
 *       右 280px 助手面板[体征|常用语|历史|医嘱], 可收起) + 底部状态栏。
 * 依赖(均由 index.html 先行加载, 本文件无构建、无 ES module):
 *   - HIS.request(api.js): HIS.get/post/put + notifyError/notifySuccess
 *   - HIS.id/idKey/sameId/idParam: 19位雪花ID全链路字符串化治理
 *   - HIS.hasRole/getUser: 角色档位判定(书写权限)
 *   - HIS.EmrEditor(emr-editor.js): createEditor/wrapper/工具栏/打印
 *   - HIS.EmrOfflineDraft(emr-offline-draft.js): IndexedDB 离线草稿(30s 自动保存)
 * 后端契约(P4a-5 扩展, 本组件全部优雅降级不阻断书写):
 *   - GET  /api/his/inp/nursing/template/list?recordType=  护理文书模板列表
 *           (失败回退既有 GET /api/his/emr/template/list?scope=1&mine=true)
 *   - 记录类型: nursing_record(护理记录) assessment(评估单) transfer(交接单)
 *               consent(告知书) nursing_plan(护理计划)
 *   - GET  /api/his/inp/nursing/vital-sign/list?inpVisitId=&start=&end=  体征
 *           (失败回退既有 GET /api/his/inp/nursing/temperature/{visitId})
 *   - GET  /api/his/emr/phrase/list?category=nursing   常用语(空回落全部类别)
 *   - GET  /api/his/inp/nursing/list?inpVisitId=       护理记录历史
 *           (失败回退既有 GET /api/his/inp/nursing/list/{visitId})
 *   - GET  /api/his/inp/order/list?inpVisitId=&page=&size=  医嘱列表
 *           (active 语义由前端过滤: order_status 2已审核/3执行中)
 *   - 保存双轨: 本组件仅 emit('save-record', payload), 由宿主组件落库
 *           (payload.content=Tiptap JSON 串, structure=fieldKey→值 串, P4a-5 接通)
 * 权限档位(bizRole): view(查看) < write(书写) < edit_others(修改他人) < lock(锁定);
 *   ADMIN/ORG_ADMIN/SUPER_ADMIN→lock, NURSE/DOCTOR→write, 其余→view;
 *   会话缺失时放行(write), 由后端兜底 —— 与 inp-emr-writer 同姿势。
 * 宿主集成接口(经 $refs 调用, 签名稳定):
 *   getContent()/setContent(json)/resetEditor()/emitSave()/
 *   canWrite/canEditOthers/canLock/bizRoleLevel
 * 注册: HIS.components.InpNursingWriter + 全局标签 dw-inp-nursing-writer 双保险。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 nw- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('nursing-writer-style')) { return; }
    var st = document.createElement('style');
    st.id = 'nursing-writer-style';
    st.textContent = [
      /* ---- 根与头栏 ---- */
      '.nw-root { flex:1 1 auto; display:flex; flex-direction:column; min-height:480px; min-width:0; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:hidden; }',
      '.nw-header { flex:none; display:flex; align-items:center; gap:10px; padding:8px 12px; background:var(--yb-surface,#fff); border-bottom:1px solid var(--yb-border,#dfe4eb); flex-wrap:wrap; }',
      '.nw-title { display:flex; align-items:center; gap:6px; font-size:14px; font-weight:700; color:var(--yb-ink-1,#1c2430); white-space:nowrap; }',
      '.nw-icon { display:inline-flex; width:14px; height:14px; vertical-align:-2px; }',
      '.nw-icon svg { width:100%; height:100%; }',
      '.nw-type-select { width:118px; }',
      '.nw-tpl-select { width:200px; }',
      '.nw-meta { display:flex; align-items:baseline; gap:8px; min-width:0; overflow:hidden; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.nw-meta-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); white-space:nowrap; }',
      '.nw-actions { margin-left:auto; display:flex; align-items:center; gap:6px; flex:none; }',
      '.nw-actions .el-button + .el-button { margin-left:0; }',
      /* ---- 主体双栏 ---- */
      '.nw-body { flex:1; display:flex; min-height:0; }',
      '.nw-center { flex:1; display:flex; flex-direction:column; min-width:0; min-height:0; padding:10px; gap:8px; }',
      '.nw-editor-zone { flex:1; display:flex; flex-direction:column; min-height:0; }',
      '.nw-toolbar-host { flex:none; }',
      '.nw-editor-host { flex:1; min-height:0; overflow-y:auto; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-top:none; border-radius:0 0 6px 6px; }',
      '.nw-editor-host .emr-doc .ProseMirror { min-height:100%; }',
      /* ---- 空态与降级 ---- */
      '.nw-empty-root { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--yb-ink-4,#8994a5); }',
      '.nw-empty-root svg { width:48px; height:48px; opacity:.5; }',
      '.nw-empty-root-text { font-size:13px; }',
      '.nw-empty-inner { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--yb-ink-4,#8994a5); }',
      '.nw-empty-inner svg { width:40px; height:40px; opacity:.5; }',
      '.nw-nodoc { flex:1; display:flex; flex-direction:column; gap:8px; min-height:0; }',
      '.nw-nodoc .el-textarea { flex:1; min-height:0; }',
      '.nw-nodoc .el-textarea__inner { height:100%; resize:none; font-size:14px; line-height:1.8; }',
      /* ---- 底部状态栏 ---- */
      '.nw-statusbar { flex:none; display:flex; align-items:center; gap:12px; padding:5px 12px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); background:var(--yb-surface,#fff); border-top:1px solid var(--yb-border,#dfe4eb); flex-wrap:wrap; }',
      '.nw-statusbar-title { color:var(--yb-ink-1,#1c2430); font-weight:600; max-width:42%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.nw-dirty { color:var(--yb-warning,#a26b1b); }',
      '.nw-saved { color:var(--yb-ink-3,#5a6a7e); }',
      '.nw-draft-chip { color:var(--yb-success,#3c862d); }',
      /* ---- 右栏: 助手面板(280px, 可收起) ---- */
      '.nw-right { width:280px; flex:none; display:flex; flex-direction:column; min-height:0; background:var(--yb-surface,#fff); border-left:1px solid var(--yb-border,#dfe4eb); }',
      '.nw-right.is-collapsed { display:none; }',
      '.nw-right .el-tabs { flex:1; display:flex; flex-direction:column; min-height:0; }',
      '.nw-right .el-tabs__header { margin-bottom:0; flex:none; }',
      '.nw-right .el-tabs__nav-scroll { padding:0 6px; }',
      '.nw-right .el-tabs__item { height:34px; line-height:34px; padding:0 9px; font-size:12px; }',
      '.nw-right .el-tabs__content { flex:1; overflow-y:auto; min-height:0; }',
      '.nw-tab-body { padding:10px; display:flex; flex-direction:column; gap:8px; }',
      '.nw-tab-tool { display:flex; gap:6px; align-items:center; flex:none; flex-wrap:wrap; }',
      '.nw-tab-empty { font-size:12px; color:var(--yb-ink-4,#8994a5); text-align:center; padding:14px 4px; line-height:1.7; }',
      /* ---- Tab1 体征数据(紧凑网格小表) ---- */
      '.nw-vital-table { display:flex; flex-direction:column; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; overflow:hidden; }',
      '.nw-vital-tr { display:grid; grid-template-columns:70px 1fr 1fr 1fr 1.5fr; align-items:center; font-size:11px; line-height:1.5; padding:4px 6px; border-bottom:1px solid var(--yb-border-light,#ebeff4); }',
      '.nw-vital-tr:last-child { border-bottom:none; }',
      '.nw-vital-tr.is-head { background:var(--yb-surface-2,#f7f9fc); color:var(--yb-ink-3,#5a6a7e); font-weight:600; }',
      '.nw-vital-tr:not(.is-head) { cursor:pointer; }',
      '.nw-vital-tr:not(.is-head):hover { background:#f5f9fd; }',
      '.nw-vital-tr span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:center; }',
      '.nw-vital-tr span:first-child { text-align:left; color:var(--yb-ink-3,#5a6a7e); }',
      /* ---- Tab2 常用语 ---- */
      '.nw-phrase { padding:7px 9px; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; cursor:pointer; display:flex; flex-direction:column; gap:4px; }',
      '.nw-phrase:hover { border-color:var(--yb-brand,#1a5c9e); background:#f5f9fd; }',
      '.nw-phrase-text { font-size:13px; color:var(--yb-ink-1,#1c2430); line-height:1.6; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }',
      '.nw-phrase-foot { display:flex; align-items:center; gap:6px; font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.nw-phrase-use { background:var(--yb-surface-2,#f7f9fc); border-radius:8px; padding:0 7px; line-height:15px; }',
      /* ---- Tab3 历史记录 ---- */
      '.nw-his-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:4px; }',
      '.nw-his-head { display:flex; align-items:center; gap:6px; }',
      '.nw-his-time { font-size:12px; font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      '.nw-his-line { font-size:12px; color:var(--yb-ink-3,#5a6a7e); line-height:1.6; word-break:break-all; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }',
      /* ---- Tab4 医嘱摘要 ---- */
      '.nw-ord-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; cursor:pointer; display:flex; flex-direction:column; gap:4px; }',
      '.nw-ord-card:hover { border-color:var(--yb-brand,#1a5c9e); background:#f5f9fd; }',
      '.nw-ord-head { display:flex; align-items:center; gap:6px; }',
      '.nw-ord-text { font-size:12px; color:var(--yb-ink-1,#1c2430); line-height:1.6; word-break:break-all; }',
      '.nw-ord-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); display:flex; gap:6px; flex-wrap:wrap; }',
      /* ---- 窄屏回落: 助手面板下沉, 编辑器定高 ---- */
      '@media (max-width: 1180px) { .nw-body { flex-direction:column; } .nw-right { width:auto; max-height:44vh; border-left:none; border-top:1px solid var(--yb-border,#dfe4eb); } .nw-editor-host { min-height:300px; } }',
      /* ---- 滚动条 ---- */
      '.nw-editor-host::-webkit-scrollbar, .nw-right .el-tabs__content::-webkit-scrollbar { width:6px; }',
      '.nw-editor-host::-webkit-scrollbar-thumb, .nw-right .el-tabs__content::-webkit-scrollbar-thumb { background:var(--yb-border-strong,#ccd4de); border-radius:3px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  /* 护理文书记录类型(P4a-5 模板端点 recordType 参数口径) */
  var RECORD_TYPE_OPTIONS = [
    { value: 'nursing_record', label: '护理记录' },
    { value: 'assessment', label: '评估单' },
    { value: 'transfer', label: '交接单' },
    { value: 'consent', label: '告知书' },
    { value: 'nursing_plan', label: '护理计划' }
  ];
  /* 常用语类别(护理专用类; 未配置时回落全部类别) */
  var PHRASE_CATEGORY_NURSING = 'nursing';
  /* 历史页签: his_nursing_record.record_type 标签(与护士站护理记录口径一致) */
  var NURSING_TYPE_LABELS = { 1: '体温记录', 2: '护理评估', 3: '护理计划', 4: '护理措施', 5: '护理总结' };
  /* 医嘱标签: his_inp_order.order_type / order_status */
  var ORDER_TYPE_LABELS = { 1: '长期', 2: '临时' };
  var ORDER_STATUS_LABELS = { 1: '新开', 2: '已审核', 3: '执行中', 4: '已完成', 5: '已停止', 6: '已作废' };
  /* 权限档位标签 */
  var BIZ_ROLE_LABELS = { view: '查看', write: '书写', edit_others: '修改他人', lock: '锁定' };
  /* 体征回看窗口(72 小时) */
  var VITAL_RANGE_MS = 72 * 60 * 60 * 1000;
  var EMPTY_DOC = { type: 'doc', content: [{ type: 'paragraph' }] };

  /* 内联 SVG 图标(feather 风格, viewBox 24, stroke currentColor) */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    clipboard: svg('<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="13" y2="16"/>'),
    save: svg('<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>'),
    printer: svg('<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/>'),
    activity: svg('<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>'),
    message: svg('<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>'),
    history: svg('<path d="M3 3v5h5"/><path d="M3.05 13A9 9 0 1 0 6 5.3L3 8"/><polyline points="12 7 12 12 15 15"/>'),
    layers: svg('<polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/>'),
    file: svg('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>'),
    panel: svg('<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="15" y1="3" x2="15" y2="21"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    search: svg('<circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>'),
    alert: svg('<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>')
  };

  /* ================= 纯工具函数 ================= */
  function fmtHM(ts) {
    var d = new Date(Number(ts) || 0);
    if (isNaN(d.getTime())) { return ''; }
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }
  /* Date → 'YYYY-MM-DD HH:mm:ss'(体征区间参数) */
  function dtLocal(d) {
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }
  /* '2026-10-03T08:00' / '2026-10-03 08:00:00' → '10-03 08:00'(窄表时间列) */
  function shortTime(v) {
    var s = String(v == null ? '' : v).replace('T', ' ');
    return s.length >= 16 ? s.substring(5, 16) : s;
  }
  /* 轻提示(Element Plus 缺失时降级 console, 绝不抛错) */
  function toast(type, text) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessage) { EP.ElMessage[type](text); }
    else { try { console.log('[nw:' + type + '] ' + text); } catch (e) { /* noop */ } }
  }
  function confirmBox(text, title, opts) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessageBox) {
      return EP.ElMessageBox.confirm(text, title || '确认', opts || {});
    }
    return global.Promise ? global.Promise.reject(new Error('cancel')) : null;
  }
  /* 宽松 JSON 解析(对象原样返回, 串解析失败返回 null) */
  function parseLoose(v) {
    if (v == null || typeof v === 'object') { return v; }
    try { return JSON.parse(v); } catch (e) { return null; }
  }
  /* 纯文本 → Tiptap 段落文档(纯文本降级轨/引用构建共用) */
  function textToDoc(text) {
    var lines = String(text == null ? '' : text).split(/\r?\n/);
    var paras = [];
    lines.forEach(function (ln) {
      var s = ln.replace(/\u00a0/g, ' ').trim();
      if (!s) { return; }
      paras.push({ type: 'paragraph', content: [{ type: 'text', text: s }] });
    });
    if (!paras.length) { paras.push({ type: 'paragraph' }); }
    return { type: 'doc', content: paras };
  }
  /* 体征行归一化: 兼容 P4a-5 直列形态与既有体温端点 content JSON 形态 */
  function vitalFromRow(row) {
    if (!row) { return null; }
    var o = (typeof row.content === 'string' || row.content == null)
      ? (parseLoose(row.content) || {})
      : (row.content || {});
    function pick(a, b) { return (a != null && a !== '') ? a : ((b != null && b !== '') ? b : null); }
    var bp = pick(o.blood_pressure, row.blood_pressure);
    var sys = pick(o.systolicBp, row.systolicBp), dia = pick(o.diastolicBp, row.diastolicBp);
    if ((sys == null || dia == null) && bp) {
      var m = String(bp).split('/');
      if (sys == null && m.length > 1) { sys = Number(m[0]); }
      if (dia == null && m.length > 1) { dia = Number(m[1]); }
    }
    return {
      time: pick(o.time, pick(row.recordTime, row.measureTime)) || row.createTime || '',
      temperature: pick(o.temperature, row.temperature),
      pulse: pick(o.pulse, row.pulse),
      respiration: pick(o.respiration, row.respiration),
      systolicBp: sys, diastolicBp: dia,
      nurse: row.createBy || row.nurseName || ''
    };
  }
  /* 角色命中(旧会话/缺 api.js 时安全降级) */
  function roleHas(code) {
    try { return !!(HIS.hasRole && HIS.hasRole(code)); } catch (e) { return false; }
  }

  /* ================= 组件 ================= */
  var InpNursingWriter = {
    name: 'InpNursingWriter',
    emits: ['save-record'],
    props: {
      inpVisitId: { type: [String, Number], default: null },
      patientId: { type: [String, Number], default: null },
      wardId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    data: function () {
      return {
        icons: ICONS,
        recordTypeOptions: RECORD_TYPE_OPTIONS,
        /* 记录类型(模板筛选 + 保存 payload 口径) */
        recordType: 'nursing_record',
        /* 模板与编辑器 */
        templates: [],
        templatesLoading: false,
        selectedTemplateId: null,
        blankMode: false,            /* 无模板空白书写(开箱可用兜底) */
        templateNoDoc: false,        /* 模板无 document(Tiptap JSON) → 纯文本降级 */
        plainFallback: '',
        pendingContent: null,        /* setContent 先于编辑器就绪时的暂存文档 */
        editorLoading: false,
        editorWrapper: null,
        dirty: false,
        lastSavedText: '',
        /* 离线草稿 */
        draftKey: '',
        draftSavedText: '',
        /* 右侧助手面板 */
        panelVisible: true,
        activeTab: 'vital',
        /* Tab1 体征数据 */
        vitals: [],
        vitalLoading: false,
        vitalFailed: false,
        vitalLoaded: false,
        /* Tab2 常用语 */
        phrases: [],
        phraseLoading: false,
        phraseKeyword: '',
        /* Tab3 历史记录 */
        histories: [],
        historyLoading: false,
        historyLoaded: false,
        /* Tab4 医嘱摘要 */
        orders: [],
        orderLoading: false,
        orderFailed: false,
        orderLoaded: false
      };
    },
    computed: {
      hasVisit: function () {
        return this.inpVisitId != null && String(this.inpVisitId).length > 0;
      },
      patientLabel: function () {
        var p = this.patient || {};
        return p.patientName || p.name || '';
      },
      /* 权限档位: lock > edit_others > write > view(会话缺失放行, 后端兜底) */
      bizRoleLevel: function () {
        var u = (typeof HIS.getUser === 'function') ? HIS.getUser() : null;
        if (!u) { return 'write'; }
        var admin = roleHas('ADMIN') || roleHas('ORG_ADMIN') || roleHas('SUPER_ADMIN');
        if (admin) { return 'lock'; }
        if (roleHas('NURSE') || roleHas('DOCTOR')) { return 'write'; }
        return 'view';
      },
      bizRoleLabel: function () {
        return BIZ_ROLE_LABELS[this.bizRoleLevel] || this.bizRoleLevel;
      },
      canWrite: function () {
        return ['write', 'edit_others', 'lock'].indexOf(this.bizRoleLevel) >= 0;
      },
      canEditOthers: function () {
        return ['edit_others', 'lock'].indexOf(this.bizRoleLevel) >= 0;
      },
      canLock: function () {
        return this.bizRoleLevel === 'lock';
      },
      selectedTemplate: function () {
        var vm = this;
        var id = vm.selectedTemplateId;
        if (id == null) { return null; }
        return (vm.templates || []).filter(function (t) { return String(t.id) === String(id); })[0] || null;
      },
      statusbarTitle: function () {
        if (this.selectedTemplate) { return this.selectedTemplate.name; }
        if (this.blankMode) { return '空白护理文书'; }
        return '护理文书';
      },
      /* 常用语前端二次过滤(类别已在请求侧过滤, 此处为关键词) */
      filteredPhrases: function () {
        var kw = String(this.phraseKeyword || '').trim().toLowerCase();
        if (!kw) { return this.phrases || []; }
        return (this.phrases || []).filter(function (p) {
          return String(p.content || '').toLowerCase().indexOf(kw) >= 0;
        });
      }
    },
    watch: {
      inpVisitId: function () { this.resetForVisit(); },
      activeTab: function (tab) {
        var vm = this;
        if (tab === 'vital' && !vm.vitalLoaded && !vm.vitalLoading) { vm.loadVitals(); }
        if (tab === 'phrase' && !vm.phrases.length && !vm.phraseLoading) { vm.loadPhrases(); }
        if (tab === 'history' && !vm.historyLoaded && !vm.historyLoading) { vm.loadHistory(); }
        if (tab === 'order' && !vm.orderLoaded && !vm.orderLoading) { vm.loadOrders(); }
      }
    },
    created: function () {
      /* Ctrl/Cmd+S 全局快捷键(实例级绑定, 卸载时移除) */
      var vm = this;
      vm._onKeydown = function (ev) {
        if ((ev.ctrlKey || ev.metaKey) && String(ev.key || '').toLowerCase() === 's') {
          ev.preventDefault();
          if (vm.canWrite) { vm.emitSave(); }
        }
      };
    },
    mounted: function () {
      var vm = this;
      global.document.addEventListener('keydown', vm._onKeydown);
      /* 离线草稿库预热(失败静默, IndexedDB 不可用不影响书写) */
      if (HIS.EmrOfflineDraft && typeof HIS.EmrOfflineDraft.init === 'function') {
        HIS.EmrOfflineDraft.init().catch(function () { /* noop */ });
      }
      vm.loadTemplates();
      if (vm.hasVisit) { vm.loadVitals(); }
    },
    beforeUnmount: function () {
      var vm = this;
      global.document.removeEventListener('keydown', vm._onKeydown);
      vm.destroyEditor();
    },

    methods: {
      /* ================= 编辑器内容读写(宿主集成接口) ================= */
      ready: function () {
        return !!(this.editorWrapper && typeof this.editorWrapper.toJSON === 'function');
      },
      /* 当前 Tiptap JSON(未就绪返回 null; 纯文本降级轨归一化为段落文档) */
      getContent: function () {
        var vm = this;
        if (vm.ready()) { return vm.editorWrapper.toJSON(); }
        if (vm.templateNoDoc && String(vm.plainFallback || '').trim() !== '') {
          return textToDoc(vm.plainFallback);
        }
        return null;
      },
      currentDocJSON: function () { return this.getContent(); },
      /* 字段值轨: fieldKey→值(与 P2/P3 书写器双轨口径一致) */
      currentFieldMap: function () {
        var w = this.editorWrapper;
        return (w && typeof w.extractFields === 'function') ? (w.extractFields() || {}) : {};
      },
      /* 载入内容(Tiptap JSON 对象/串; 纯文本串归一化为段落文档)。
       * 编辑器未就绪时暂存 pendingContent, 建好编辑器后自动回填。 */
      setContent: function (json) {
        var vm = this;
        if (json == null || json === '') { return false; }
        var doc = (typeof json === 'string') ? parseLoose(json) : json;
        if (!doc || doc.type !== 'doc' || !Array.isArray(doc.content)) {
          doc = textToDoc(typeof json === 'string' ? json : '');
        }
        if (vm.ready()) {
          try { vm.editorWrapper.fromJSON(doc); vm.dirty = true; return true; } catch (e) { return false; }
        }
        vm.pendingContent = doc;
        return true;
      },
      /* 清空并重置: 有模板 → 重载模板; 空白模式 → 重建空白; 否则回到空态 */
      resetEditor: function () {
        var vm = this;
        vm.pendingContent = null;
        vm.dirty = false;
        vm.lastSavedText = '';
        vm.draftSavedText = '';
        if (vm.selectedTemplateId && vm.selectedTemplate) { vm.applyTemplate(); return; }
        if (vm.blankMode) { vm.buildEditor(EMPTY_DOC); return; }
        vm.destroyEditor();
      },

      /* ================= 保存(双轨, 事件中继给宿主) ================= */
      /* emit('save-record', {inpVisitId, templateId, content: Tiptap JSON 串,
       * structure: 字段值串, recordType, patientId, wardId}); 落库由宿主完成(P4a-5) */
      emitSave: function () {
        var vm = this;
        if (!vm.hasVisit) { toast('warning', '请先在护士站选择住院患者'); return false; }
        if (!vm.canWrite) { toast('warning', '当前角色(' + vm.bizRoleLabel + ')无护理文书书写权限'); return false; }
        var json = null;
        if (vm.templateNoDoc) {
          if (String(vm.plainFallback || '').trim() === '') { toast('warning', '请先书写文书内容'); return false; }
          json = textToDoc(vm.plainFallback);
        } else {
          if (!vm.ready()) { toast('warning', '请先选择文书模板或新建空白文书'); return false; }
          json = vm.currentDocJSON();
        }
        var payload = {
          inpVisitId: HIS.id(vm.inpVisitId),
          patientId: HIS.id(vm.patientId),
          wardId: HIS.id(vm.wardId),
          templateId: HIS.id(vm.selectedTemplateId),
          recordType: vm.recordType,
          /* 富文本轨: Tiptap JSON 串 */
          content: JSON.stringify(json),
          /* 字段值轨: fieldKey→值(兼容后续完整性质控/结构化检索) */
          structure: JSON.stringify(vm.currentFieldMap())
        };
        vm.$emit('save-record', payload);
        vm.dirty = false;
        vm.lastSavedText = '已提交保存请求 ' + fmtHM(Date.now());
        vm.resetDraftAfterSave();
        return true;
      },

      /* ================= 模板装载 ================= */
      loadTemplates: function () {
        var vm = this;
        if (!HIS.get) { return; }
        vm.templatesLoading = true;
        /* P4a-5 护理模板端点优先; 失败回退既有 EMR 模板(scope=1), 保证开箱可用 */
        HIS.get('/api/his/inp/nursing/template/list?recordType=' + encodeURIComponent(vm.recordType || 'nursing_record'))
          .catch(function () { return HIS.get('/api/his/emr/template/list?scope=1&mine=true'); })
          .then(function (list) {
            vm.templates = (Array.isArray(list) ? list : []).map(function (t) {
              return Object.assign({}, t, { id: HIS.id(t.id), name: t.templateName || t.name || ('模板' + t.id) });
            });
          })
          .catch(function (e) {
            vm.templates = [];
            HIS.notifyError(e);
          })
          .finally(function () { vm.templatesLoading = false; });
      },
      onRecordTypeChange: function () {
        var vm = this;
        vm.selectedTemplateId = null;
        vm.blankMode = false;
        vm.templateNoDoc = false;
        vm.plainFallback = '';
        vm.pendingContent = null;
        vm.dirty = false;
        vm.lastSavedText = '';
        vm.destroyEditor();
        vm.loadTemplates();
      },
      onTemplateChange: function (id) {
        var vm = this;
        vm.blankMode = false;
        if (!id) { vm.destroyEditor(); vm.templateNoDoc = false; return; }
        vm.applyTemplate();
      },
      /* 应用模板: 有 document → 建 Tiptap 编辑器; 无 → 降级纯文本区 */
      applyTemplate: function () {
        var vm = this;
        var tpl = vm.selectedTemplate;
        vm.destroyEditor();
        vm.templateNoDoc = false;
        vm.plainFallback = '';
        if (!tpl) { return; }
        var doc = null;
        if (tpl.document) {
          try { doc = typeof tpl.document === 'string' ? JSON.parse(tpl.document) : tpl.document; } catch (e) { doc = null; }
        }
        if (!doc || doc.type !== 'doc' || !Array.isArray(doc.content)) {
          vm.templateNoDoc = true;
          toast('info', '该模板未配置富文本结构, 已降级为纯文本书写');
          return;
        }
        vm.$nextTick(function () { vm.buildEditor(doc); });
      },
      /* 空白书写(无可用模板时的开箱兜底) */
      startBlank: function () {
        var vm = this;
        vm.selectedTemplateId = null;
        vm.templateNoDoc = false;
        vm.plainFallback = '';
        vm.blankMode = true;
        vm.$nextTick(function () { vm.buildEditor(EMPTY_DOC); });
      },
      onPlainInput: function () { this.dirty = true; },

      /* ================= Tiptap 编辑器 ================= */
      buildEditor: function (doc) {
        var vm = this;
        var seq = (vm._editorSeq = (vm._editorSeq || 0) + 1);
        if (!HIS.EmrEditor || typeof HIS.EmrEditor.createEditor !== 'function') {
          toast('error', '编辑器组件未加载(emr-editor.js)');
          return;
        }
        if (!vm.$refs.editorHost) { return; }
        vm.editorLoading = true;
        HIS.EmrEditor.createEditor({
          container: vm.$refs.editorHost,
          toolbarContainer: vm.$refs.toolbarHost,
          mode: 'edit',                    /* 引擎档位 edit|design|preview; 只读经 readOnly 控制 */
          readOnly: !vm.canWrite,
          document: doc,
          placeholder: '书写护理文书内容…',
          nlgEnabled: false,               /* NLG/CDSS 为病历域特性, 护理文书暂不启用 */
          cdssEnabled: false,
          recordType: '1',                 /* 住院 scope(NLG 映射预留) */
          onSave: function () { vm.emitSave(); },
          onFieldChange: function () { vm.dirty = true; }
        }).then(function (wrapper) {
          if (seq !== vm._editorSeq) { try { wrapper.destroy(); } catch (e) { /* 已被更新批次取代 */ } return; }
          vm.editorWrapper = wrapper;
          vm.editorLoading = false;
          if (typeof wrapper.on === 'function') {
            vm._unsubUpdate = wrapper.on('update', function () { vm.dirty = true; });
          }
          /* setContent 先于就绪到达的暂存文档回填 */
          if (vm.pendingContent) {
            try { wrapper.fromJSON(vm.pendingContent); vm.dirty = true; } catch (e) { /* 非法内容忽略 */ }
            vm.pendingContent = null;
          }
          if (vm.canWrite) { try { wrapper.focus(); } catch (e) { /* noop */ } }
          /* 离线草稿: 可写态开启自动保存 + 冲突检测 */
          if (vm.canWrite) {
            vm.startDraft();
            vm.checkDraftConflict();
          }
        }).catch(function (e) {
          if (seq === vm._editorSeq) {
            vm.editorLoading = false;
            vm.blankMode = false;
          }
          HIS.notifyError(e);
        });
      },
      destroyEditor: function () {
        var vm = this;
        vm._editorSeq = (vm._editorSeq || 0) + 1;   /* 使在途 createEditor 失效 */
        vm.stopDraft();
        if (vm._unsubUpdate) { try { vm._unsubUpdate(); } catch (e) { /* noop */ } vm._unsubUpdate = null; }
        if (vm.editorWrapper) {
          try { vm.editorWrapper.destroy(); } catch (e) { /* noop */ }
          vm.editorWrapper = null;
        }
      },

      /* ================= 就诊切换 ================= */
      resetForVisit: function () {
        var vm = this;
        vm.destroyEditor();
        vm.selectedTemplateId = null;
        vm.blankMode = false;
        vm.templateNoDoc = false;
        vm.plainFallback = '';
        vm.pendingContent = null;
        vm.dirty = false;
        vm.lastSavedText = '';
        vm.draftSavedText = '';
        /* 右栏就诊相关数据源重建(当前页签立即刷新, 其余页签懒加载时重取) */
        vm.vitals = []; vm.vitalLoaded = false; vm.vitalFailed = false;
        vm.histories = []; vm.historyLoaded = false;
        vm.orders = []; vm.orderLoaded = false; vm.orderFailed = false;
        if (vm.activeTab === 'vital') { vm.loadVitals(); }
      },

      /* ================= 光标插入 ================= */
      /* 单行文本插入(常用语) */
      insertAtCursor: function (text) {
        var vm = this;
        if (!vm.ready()) { toast('warning', '请先选择文书模板或新建空白文书'); return false; }
        if (!vm.canWrite) { toast('warning', '当前为只读模式, 不可插入内容'); return false; }
        try {
          vm.editorWrapper.editor.commands.insertContent(String(text == null ? '' : text));
          vm.dirty = true;
          return true;
        } catch (e) {
          toast('error', '插入失败');
          return false;
        }
      },
      /* 多行文本 → 段落文档片段插入(体征摘要/记录引用/医嘱摘要) */
      insertParagraphs: function (text) {
        var vm = this;
        if (!vm.ready()) { toast('warning', '请先选择文书模板或新建空白文书'); return false; }
        if (!vm.canWrite) { toast('warning', '当前为只读模式, 不可插入内容'); return false; }
        var lines = String(text == null ? '' : text).split(/\r?\n/).filter(function (s) { return s.trim() !== ''; });
        if (!lines.length) { return false; }
        var frag = { type: 'doc', content: lines.map(function (ln) {
          return { type: 'paragraph', content: [{ type: 'text', text: ln.trim() }] };
        }) };
        try {
          vm.editorWrapper.editor.commands.insertContent(frag);
          vm.dirty = true;
          return true;
        } catch (e) {
          toast('error', '插入失败');
          return false;
        }
      },

      /* ================= 离线草稿(IndexedDB 30s 自动保存) ================= */
      startDraft: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.editorWrapper || !vm.hasVisit) { return; }
        vm.draftKey = 'nursing:' + HIS.idKey(vm.inpVisitId) + ':' + HIS.idKey(vm.selectedTemplateId || 'blank');
        var u = (typeof HIS.getUser === 'function') ? HIS.getUser() : null;
        try {
          HIS.EmrOfflineDraft.startAutoSave(vm.draftKey, vm.editorWrapper, {
            staffId: u && u.staffId != null ? HIS.id(u.staffId) : null,
            patientName: vm.patientLabel,
            recordType: '护理文书'
          }, 30000);
          vm.draftSavedText = '';
        } catch (e) { /* IndexedDB 不可用静默降级 */ }
      },
      stopDraft: function () {
        var vm = this;
        if (vm.draftKey && HIS.EmrOfflineDraft) {
          try { HIS.EmrOfflineDraft.stopAutoSave(vm.draftKey); } catch (e) { /* noop */ }
        }
        vm.draftKey = '';
        vm.draftSavedText = '';
      },
      /* 载入时检测本地离线草稿: 存在则询问恢复 */
      checkDraftConflict: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.hasVisit || !vm.ready()) { return; }
        var key = 'nursing:' + HIS.idKey(vm.inpVisitId) + ':' + HIS.idKey(vm.selectedTemplateId || 'blank');
        HIS.EmrOfflineDraft.getConflictInfo(key, null).then(function (info) {
          if (!info || !info.exists || !info.draft) { return; }
          var d = new Date(Number(info.draftTime) || 0);
          var p = function (n) { return (n < 10 ? '0' : '') + n; };
          var time = isNaN(d.getTime()) ? '-' : (d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()));
          confirmBox('检测到本地离线草稿(' + time + '), 是否恢复到编辑器?', '恢复离线草稿', {
            type: 'warning', confirmButtonText: '恢复草稿', cancelButtonText: '放弃草稿'
          }).then(function () {
            if (!vm.editorWrapper || !info.draft.documentJson) { return; }
            try {
              var json = typeof info.draft.documentJson === 'string'
                ? JSON.parse(info.draft.documentJson) : info.draft.documentJson;
              vm.editorWrapper.fromJSON(json);
              vm.dirty = true;
              toast('success', '离线草稿已恢复');
            } catch (e) {
              toast('error', '草稿内容解析失败');
            }
          }).catch(function () {
            HIS.EmrOfflineDraft.deleteDraft(key);
          });
        }).catch(function () { /* 草稿库不可用静默 */ });
      },
      /* 保存请求发出后清空本地草稿并重启自动保存(重置基线) */
      resetDraftAfterSave: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.draftKey) { return; }
        var key = vm.draftKey;
        HIS.EmrOfflineDraft.stopAutoSave(key);
        HIS.EmrOfflineDraft.deleteDraft(key).then(function () {
          if (vm.draftKey === key) { vm.draftSavedText = '已同步 ' + fmtHM(Date.now()); vm.startDraft(); }
        }).catch(function () { /* noop */ });
      },

      /* ================= Tab1: 体征数据 ================= */
      loadVitals: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.vitals = []; return; }
        vm.vitalLoading = true;
        vm.vitalFailed = false;
        var end = new Date();
        var start = new Date(end.getTime() - VITAL_RANGE_MS);
        /* P4a-5 体征端点优先; 失败回退既有体温端点(content JSON 形态) */
        HIS.get('/api/his/inp/nursing/vital-sign/list?inpVisitId=' + HIS.idParam(vm.inpVisitId)
            + '&start=' + encodeURIComponent(dtLocal(start)) + '&end=' + encodeURIComponent(dtLocal(end)))
          .catch(function () { return HIS.get('/api/his/inp/nursing/temperature/' + HIS.idParam(vm.inpVisitId)); })
          .then(function (rows) {
            var list = (Array.isArray(rows) ? rows : []).map(vitalFromRow).filter(Boolean);
            list.sort(function (a, b) { return String(b.time).localeCompare(String(a.time)); });
            vm.vitals = list.slice(0, 30);
            vm.vitalLoaded = true;
          })
          .catch(function () {
            vm.vitals = [];
            vm.vitalFailed = true;
          })
          .finally(function () { vm.vitalLoading = false; });
      },
      vitalTextOf: function (v) {
        if (!v) { return ''; }
        var parts = [];
        if (v.temperature != null && v.temperature !== '') { parts.push('体温 ' + v.temperature + '℃'); }
        if (v.pulse != null && v.pulse !== '') { parts.push('脉搏 ' + v.pulse + '次/分'); }
        if (v.respiration != null && v.respiration !== '') { parts.push('呼吸 ' + v.respiration + '次/分'); }
        if (v.systolicBp != null && v.systolicBp !== '' && v.diastolicBp != null && v.diastolicBp !== '') {
          parts.push('血压 ' + v.systolicBp + '/' + v.diastolicBp + 'mmHg');
        }
        var t = shortTime(v.time);
        return parts.join('，') + (t ? '（' + t + '）' : '');
      },
      insertVital: function (v) {
        var t = this.vitalTextOf(v);
        if (!t) { return; }
        if (this.insertParagraphs('生命体征：' + t)) { toast('success', '体征摘要已插入光标处'); }
      },

      /* ================= Tab2: 常用语 ================= */
      loadPhrases: function () {
        var vm = this;
        if (!HIS.get) { return; }
        vm.phraseLoading = true;
        /* 护理类常用语优先; 未配置(nursing 类为空)时回落全部类别 */
        HIS.get('/api/his/emr/phrase/list?category=' + encodeURIComponent(PHRASE_CATEGORY_NURSING))
          .then(function (list) {
            if (list && list.length) { vm.phrases = list; return null; }
            return HIS.get('/api/his/emr/phrase/list?category=');
          })
          .then(function (list) {
            if (list) { vm.phrases = Array.isArray(list) ? list : []; }
          })
          .catch(function () { vm.phrases = []; })
          .finally(function () { vm.phraseLoading = false; });
      },
      usePhrase: function (p) {
        var vm = this;
        if (!p || !p.content) { return; }
        if (vm.insertAtCursor(p.content)) {
          if (p.id != null && HIS.post) {
            HIS.post('/api/his/emr/phrase/use/' + HIS.idParam(p.id)).catch(function () { /* 计数失败忽略 */ });
          }
        }
      },

      /* ================= Tab3: 历史记录 ================= */
      loadHistory: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.histories = []; return; }
        vm.historyLoading = true;
        /* P4a-5 query 形态优先; 失败回退既有路径参数形态 */
        HIS.get('/api/his/inp/nursing/list?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .catch(function () { return HIS.get('/api/his/inp/nursing/list/' + HIS.idParam(vm.inpVisitId)); })
          .then(function (rows) { vm.histories = (Array.isArray(rows) ? rows : []).slice(0, 40); })
          .catch(function () { vm.histories = []; })
          .finally(function () {
            vm.historyLoading = false;
            vm.historyLoaded = true;
          });
      },
      nursingTypeLabel: function (v) {
        var key = v == null ? '' : String(Number(v) || v);
        return NURSING_TYPE_LABELS[key] || '护理记录';
      },
      /* 历史行摘要: 服务端 textSummary(Tiptap 密文轨派生)优先; 否则 content JSON(.text / 体征形态 / 其余键值)兜底 */
      recordSummary: function (row) {
        if (row && row.textSummary) { return String(row.textSummary); }
        var o = parseLoose(row && row.content);
        if (o && o.text != null && String(o.text).trim() !== '') { return String(o.text).trim(); }
        if (o && (o.temperature != null || o.pulse != null || o.systolicBp != null)) {
          return this.vitalTextOf(vitalFromRow(row));
        }
        if (o && !Array.isArray(o)) {
          var joined = Object.keys(o).map(function (k) {
            return (o[k] == null || o[k] === '') ? '' : (k + ':' + o[k]);
          }).filter(Boolean).join('；');
          if (joined) { return joined; }
        }
        return String((row && row.content) || '').trim() || '(空)';
      },
      quoteRecord: function (row) {
        var vm = this;
        if (!row) { return; }
        var t = shortTime(row.recordTime || row.createTime);
        var text = '【引用护理记录】' + (t ? t + ' ' : '') + vm.nursingTypeLabel(row.recordType)
          + '：' + vm.recordSummary(row);
        if (vm.insertParagraphs(text)) { toast('success', '护理记录已引用到光标处'); }
      },

      /* ================= Tab4: 医嘱摘要 ================= */
      loadOrders: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.orders = []; return; }
        vm.orderLoading = true;
        vm.orderFailed = false;
        /* 既有 /api/his/inp/order/list 返回 IPage; active 语义(已审核2/执行中3)由前端过滤,
         * P4a-5 若服务端增加 status=active 参数则结果一致 */
        HIS.get('/api/his/inp/order/list?inpVisitId=' + HIS.idParam(vm.inpVisitId) + '&page=1&size=50')
          .then(function (d) {
            var rows = (d && d.records) || (Array.isArray(d) ? d : []);
            vm.orders = rows.filter(function (o) {
              var s = Number(o && (o.orderStatus != null ? o.orderStatus : o.order_status));
              return s === 2 || s === 3;
            }).slice(0, 30);
            vm.orderLoaded = true;
          })
          .catch(function () {
            vm.orders = [];
            vm.orderFailed = true;
          })
          .finally(function () { vm.orderLoading = false; });
      },
      orderTypeLabel: function (v) { return ORDER_TYPE_LABELS[Number(v)] || '-'; },
      orderStatusLabel: function (v) { return ORDER_STATUS_LABELS[Number(v)] || '-'; },
      orderTextOf: function (o) {
        if (!o) { return ''; }
        var c = String(o.orderContent || '').trim();
        var dose = [o.dosage, o.dosageUnit].filter(function (x) { return x != null && String(x) !== ''; }).join('');
        if (dose && c.indexOf(dose) < 0) { c = (c ? c + ' ' : '') + dose; }
        var parts = [ORDER_TYPE_LABELS[Number(o.orderType)] || '医嘱', c || '(无内容)'];
        if (o.freqCode) { parts.push(o.freqCode); }
        if (Number(o.highAlertFlag) === 1) { parts.push('高警示'); }
        var t = shortTime(o.startTime);
        if (t) { parts.push('开始 ' + t); }
        return parts.join(' · ');
      },
      insertOrderSummary: function (o) {
        var t = this.orderTextOf(o);
        if (!t) { return; }
        if (this.insertParagraphs('【在执行医嘱】' + t)) { toast('success', '医嘱摘要已插入光标处'); }
      },

      /* ================= 打印 ================= */
      printRecord: function () {
        var vm = this;
        if (!vm.ready()) { toast('warning', '请先选择模板并书写内容后再打印'); return; }
        var title = '护理文书' + (vm.selectedTemplate ? ' - ' + vm.selectedTemplate.name : '')
          + (vm.patientLabel ? ' - ' + vm.patientLabel : '');
        if (typeof vm.editorWrapper.print === 'function') {
          try { vm.editorWrapper.print({ title: title }); return; } catch (e) { /* 回退提示 */ }
        }
        toast('warning', '打印组件不可用');
      },

      /* ================= 头栏动作 ================= */
      togglePanel: function () { this.panelVisible = !this.panelVisible; },
      shortTime: shortTime,
      valOr: function (v) { return (v == null || v === '') ? '-' : v; }
    },

    /* ================= 模板(字符串数组拼接, 无模板字符串) ================= */
    template: [
      '<div class="nw-root">',
      /* ---- 头栏 ---- */
      '  <div class="nw-header">',
      '    <span class="nw-title"><span class="nw-icon" v-html="icons.clipboard"></span> 护理文书</span>',
      '    <el-select v-model="recordType" class="nw-type-select" size="small" :disabled="!canWrite" @change="onRecordTypeChange">',
      '      <el-option v-for="o in recordTypeOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-select v-model="selectedTemplateId" class="nw-tpl-select" size="small" filterable clearable placeholder="选择文书模板" :loading="templatesLoading" :disabled="!canWrite" @change="onTemplateChange">',
      '      <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id"></el-option>',
      '    </el-select>',
      '    <div class="nw-meta" v-if="patientLabel">',
      '      <span class="nw-meta-name">{{ patientLabel }}</span>',
      '      <span v-if="inpVisitId">住院号 {{ inpVisitId }}</span>',
      '    </div>',
      '    <div class="nw-actions">',
      '      <el-button size="small" type="primary" :disabled="!canWrite" @click="emitSave"><span class="nw-icon" v-html="icons.save"></span> 保存(Ctrl+S)</el-button>',
      '      <el-button size="small" @click="printRecord"><span class="nw-icon" v-html="icons.printer"></span> 打印</el-button>',
      '      <el-button size="small" text @click="togglePanel" :title="panelVisible ? \'收起助手\' : \'展开助手\'"><span class="nw-icon" v-html="icons.panel"></span></el-button>',
      '    </div>',
      '  </div>',
      /* ---- 主体双栏 ---- */
      '  <div class="nw-body">',
      '    <div class="nw-center">',
      /* 无就诊空态 */
      '      <div v-if="!hasVisit" class="nw-empty-root">',
      '        <span v-html="icons.user"></span>',
      '        <div class="nw-empty-root-text">请先在护士站患者列表选择患者</div>',
      '      </div>',
      '      <template v-else>',
      /* 模板无 document → 纯文本降级书写 */
      '        <div v-if="templateNoDoc" class="nw-nodoc">',
      '          <el-alert type="warning" :closable="false" show-icon title="该模板暂不支持富文本编辑" description="当前模板未配置 Tiptap 文档，已降级为纯文本书写；内容将随文书一并保存。"></el-alert>',
      '          <el-input v-model="plainFallback" type="textarea" :rows="10" :disabled="!canWrite" placeholder="以纯文本书写护理文书内容…" @input="onPlainInput"></el-input>',
      '        </div>',
      /* 编辑器(已选模板或空白书写) */
      '        <div v-else-if="selectedTemplateId || blankMode" class="nw-editor-zone">',
      '          <div ref="toolbarHost" class="nw-toolbar-host" v-loading="editorLoading" element-loading-text="加载编辑器…"></div>',
      '          <div ref="editorHost" class="nw-editor-host"></div>',
      '        </div>',
      /* 未选模板空态 */
      '        <div v-else class="nw-empty-inner">',
      '          <span v-html="icons.file"></span>',
      '          <div class="nw-empty-root-text">请选择护理文书模板开始书写</div>',
      '          <div v-if="!templates.length && !templatesLoading" class="nw-tab-empty">暂无可用模板(护理模板库待 P4a-5 接入)</div>',
      '          <el-button size="small" type="primary" plain :disabled="!canWrite" @click="startBlank">新建空白文书</el-button>',
      '        </div>',
      '      </template>',
      '    </div>',
      /* ---- 右栏: 助手面板(280px, 可收起) ---- */
      '    <div class="nw-right" :class="{ \'is-collapsed\': !panelVisible }">',
      '      <el-tabs v-model="activeTab">',
      /* Tab1 体征数据 */
      '        <el-tab-pane name="vital">',
      '          <template #label><span class="nw-icon" v-html="icons.activity"></span> 体征</template>',
      '          <div class="nw-tab-body">',
      '            <div class="nw-tab-tool">',
      '              <span style="font-size:12px;color:var(--yb-ink-4,#8994a5)">近 72 小时 · 点击行插入摘要</span>',
      '              <el-button size="small" text style="margin-left:auto" @click="loadVitals"><span class="nw-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="vitalLoading" style="min-height:60px">',
      '              <div v-if="!hasVisit" class="nw-tab-empty">请先选择患者</div>',
      '              <div v-else-if="vitalFailed" class="nw-tab-empty">体征数据暂不可用</div>',
      '              <div v-else-if="!vitals.length && !vitalLoading" class="nw-tab-empty">暂无体征记录</div>',
      '              <div v-else class="nw-vital-table">',
      '                <div class="nw-vital-tr is-head"><span>时间</span><span>T℃</span><span>P</span><span>R</span><span>BP</span></div>',
      '                <div v-for="(v, i) in vitals" :key="i" class="nw-vital-tr" @click="insertVital(v)" title="点击插入体征摘要">',
      '                  <span>{{ shortTime(v.time) }}</span>',
      '                  <span>{{ valOr(v.temperature) }}</span>',
      '                  <span>{{ valOr(v.pulse) }}</span>',
      '                  <span>{{ valOr(v.respiration) }}</span>',
      '                  <span>{{ v.systolicBp != null ? v.systolicBp + "/" + valOr(v.diastolicBp) : "-" }}</span>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab2 常用语 */
      '        <el-tab-pane name="phrase">',
      '          <template #label><span class="nw-icon" v-html="icons.message"></span> 常用语</template>',
      '          <div class="nw-tab-body">',
      '            <div class="nw-tab-tool">',
      '              <el-input v-model="phraseKeyword" size="small" clearable placeholder="筛选" style="flex:1">',
      '                <template #prefix><span class="nw-icon" v-html="icons.search"></span></template>',
      '              </el-input>',
      '              <el-button size="small" text @click="loadPhrases"><span class="nw-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="phraseLoading" style="min-height:60px">',
      '              <div v-if="!filteredPhrases.length && !phraseLoading" class="nw-tab-empty">暂无常用语</div>',
      '              <div v-for="p in filteredPhrases" :key="p.id" class="nw-phrase" @click="usePhrase(p)" title="点击插入光标处">',
      '                <div class="nw-phrase-text">{{ p.content }}</div>',
      '                <div class="nw-phrase-foot"><span class="nw-phrase-use">已用 {{ p.usageCount || 0 }} 次</span></div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab3 历史记录 */
      '        <el-tab-pane name="history">',
      '          <template #label><span class="nw-icon" v-html="icons.history"></span> 历史</template>',
      '          <div class="nw-tab-body">',
      '            <div class="nw-tab-tool">',
      '              <span style="font-size:12px;color:var(--yb-ink-4,#8994a5)">本次住院护理记录 · 点击引用</span>',
      '              <el-button size="small" text style="margin-left:auto" @click="loadHistory"><span class="nw-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="historyLoading" style="min-height:60px">',
      '              <div v-if="!hasVisit" class="nw-tab-empty">请先选择患者</div>',
      '              <div v-else-if="!histories.length && !historyLoading" class="nw-tab-empty">暂无历史护理记录</div>',
      '              <div v-for="h in histories" :key="h.id" class="nw-his-card" style="cursor:pointer" title="点击引用到光标处" @click="quoteRecord(h)">',
      '                <div class="nw-his-head">',
      '                  <span class="nw-his-time">{{ shortTime(h.recordTime || h.createTime) }}</span>',
      '                  <el-tag size="small" disable-transitions>{{ nursingTypeLabel(h.recordType) }}</el-tag>',
      '                  <span style="margin-left:auto;font-size:11px;color:var(--yb-ink-4,#8994a5)">{{ h.createBy || "" }}</span>',
      '                </div>',
      '                <div class="nw-his-line">{{ recordSummary(h) }}</div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab4 医嘱摘要 */
      '        <el-tab-pane name="order">',
      '          <template #label><span class="nw-icon" v-html="icons.layers"></span> 医嘱</template>',
      '          <div class="nw-tab-body">',
      '            <div class="nw-tab-tool">',
      '              <span style="font-size:12px;color:var(--yb-ink-4,#8994a5)">在执行医嘱 · 点击插入摘要</span>',
      '              <el-button size="small" text style="margin-left:auto" @click="loadOrders"><span class="nw-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="orderLoading" style="min-height:60px">',
      '              <div v-if="!hasVisit" class="nw-tab-empty">请先选择患者</div>',
      '              <div v-else-if="orderFailed" class="nw-tab-empty">医嘱数据暂不可用</div>',
      '              <div v-else-if="!orders.length && !orderLoading" class="nw-tab-empty">暂无在执行医嘱</div>',
      '              <div v-for="o in orders" :key="o.id" class="nw-ord-card" title="点击插入医嘱摘要" @click="insertOrderSummary(o)">',
      '                <div class="nw-ord-head">',
      '                  <el-tag size="small" :type="Number(o.orderType) === 1 ? \'\' : \'warning\'" disable-transitions>{{ orderTypeLabel(o.orderType) }}</el-tag>',
      '                  <el-tag size="small" type="success" disable-transitions>{{ orderStatusLabel(o.orderStatus) }}</el-tag>',
      '                  <el-tag v-if="Number(o.highAlertFlag) === 1" size="small" type="danger" disable-transitions>高警示</el-tag>',
      '                </div>',
      '                <div class="nw-ord-text">{{ o.orderContent || "(无内容)" }}</div>',
      '                <div class="nw-ord-meta">',
      '                  <span v-if="o.dosage">{{ o.dosage }}{{ o.dosageUnit || "" }}</span>',
      '                  <span v-if="o.freqCode">{{ o.freqCode }}</span>',
      '                  <span v-if="o.startTime">{{ shortTime(o.startTime) }}</span>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      '      </el-tabs>',
      '    </div>',
      '  </div>',
      /* ---- 底部状态栏 ---- */
      '  <div class="nw-statusbar">',
      '    <span class="nw-statusbar-title">{{ statusbarTitle }}</span>',
      '    <el-tag size="small" :type="canWrite ? \'success\' : \'info\'" disable-transitions>{{ bizRoleLabel }}</el-tag>',
      '    <el-tag v-if="!canWrite && hasVisit" size="small" type="info" disable-transitions>只读模式</el-tag>',
      '    <template v-else-if="canWrite && (selectedTemplateId || blankMode || templateNoDoc)">',
      '      <span :class="dirty ? \'nw-dirty\' : \'\'">{{ dirty ? "● 有未保存修改 (Ctrl+S)" : "○ 已同步" }}</span>',
      '    </template>',
      '    <span v-if="lastSavedText" class="nw-saved">{{ lastSavedText }}</span>',
      '    <span v-if="draftSavedText" class="nw-draft-chip">离线草稿 {{ draftSavedText }}</span>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.InpNursingWriter = InpNursingWriter;
  /* 全局标签注册: app 就绪后挂 <dw-inp-nursing-writer>(脚本先于 app.js 加载, 故延时至 DOMContentLoaded;
   * P4a-5 集成后 inp-nurse.js 亦可经 HIS.components 局部声明, 局部声明优先于全局标签) */
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-nursing-writer', InpNursingWriter); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
