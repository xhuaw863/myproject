/* ==================================================================
 * outp-emr-writer.js — 门诊病历 Tiptap 书写器(病历P3-3, 双栏工作台)
 * ------------------------------------------------------------------
 * 定位: 替代门诊表单式 emr-panel(DwEmrPanel) 的 Tiptap 富文本病历书写组件。
 * 版式: 头栏(模板/患者/动作) + 主体双栏(左 Tiptap 编辑器 / 右 320px 助手面板)
 *       + 底部 NLG 自然语言预览条(字段变更防抖 1s)。
 * 依赖(均由 index.html 先行加载, 本文件无构建、无 ES module):
 *   - HIS.request(api.js): HIS.get/post/put + notifyError/notifySuccess
 *   - HIS.id/idKey/sameId/idParam: 19位雪花ID全链路字符串化治理
 *   - HIS.EmrEditor(emr-editor.js): createEditor/wrapper/工具栏/打印
 *   - HIS.EmrOfflineDraft(emr-offline-draft.js): IndexedDB 离线草稿
 * 后端契约:
 *   - GET  /api/his/emr/template/list?scope=2&mine=true   门诊模板列表(含 document Tiptap JSON)
 *   - POST /api/his/outp/emr/resolve-tiptap-macros?visitId= 宏解析(P3新, body携Tiptap JSON;
 *           失败回退既有 POST /api/his/outp/emr/macro/resolve → {macroCode:值})
 *   - POST /api/his/visit/save-draft(经 doctor.js save-draft 事件中继)  草稿双轨: content(Tiptap JSON)+structure(fieldKey→值)
 *   - POST /api/his/visit/finish(经 doctor.js buildFinishPayload 并入完成请求)
 *   - GET  /api/his/outp/emr/versions?visitId=            版本列表
 *   - GET  /api/his/outp/emr/version/{id}                 版本详情(contentSnapshot/structureSnapshot)
 *   - GET  /api/his/outp/emr/version-diff?v1=&v2=         行级差异
 *   - POST /api/his/outp/emr/quality?visitId=             完整性质控
 *   - GET/POST 常用语: /api/his/emr/phrase/list, /api/his/emr/phrase/use/{id}
 *   - POST /api/his/emr/cdss/evaluate-document            CDSS 文档级评估(body {documentJson: JSON串})
 *   - POST /api/his/emr/nlg/generate-document             整文自然语言生成(→ Map<sectionKey,文本>)
 *   - GET  /api/medtech/reports/patient/{pid}?reportType=lab|exam  检查检验报告(失败降级)
 * 接口兼容: doctor.js 经 $refs 调用, 与 emr-panel.js 同签名 ——
 *   emitSave(submit)/validateForFinish()/buildFinishPayload()/appendTreatment(text)/
 *   appendAuxExam(text)/appendVital(text)/applyPreConsult(payload)/quoteHistory(history)/
 *   quoteExamReport(rep)/resetFromVisit(visit)
 * 注册: HIS.components.OutpEmrWriter(P3-4 由 doctor.js 集成 <dw-outp-emr-writer>;
 *       app 就绪时追加全局标签注册, 未集成前亦可独立验证)。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 oew- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('outp-emr-writer-style')) { return; }
    var st = document.createElement('style');
    st.id = 'outp-emr-writer-style';
    st.textContent = [
      /* ---- 根与头栏 ---- */
      '.oew-root { flex:1 1 auto; display:flex; flex-direction:column; min-height:480px; min-width:0; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:hidden; }',
      '.oew-header { flex:none; display:flex; align-items:center; gap:10px; padding:8px 12px; background:var(--yb-surface,#fff); border-bottom:1px solid var(--yb-border,#dfe4eb); flex-wrap:wrap; }',
      '.oew-title { display:flex; align-items:center; gap:6px; font-size:14px; font-weight:700; color:var(--yb-ink-1,#1c2430); white-space:nowrap; }',
      '.oew-icon { display:inline-flex; width:14px; height:14px; vertical-align:-2px; }',
      '.oew-icon svg { width:100%; height:100%; }',
      '.oew-tpl-select { width:200px; }',
      '.oew-meta { display:flex; align-items:baseline; gap:8px; min-width:0; overflow:hidden; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.oew-meta-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); white-space:nowrap; }',
      '.oew-actions { margin-left:auto; display:flex; align-items:center; gap:6px; flex:none; }',
      '.oew-actions .el-button + .el-button { margin-left:0; }',
      /* ---- 主体双栏 ---- */
      '.oew-body { flex:1; display:flex; min-height:0; }',
      '.oew-center { flex:1; display:flex; flex-direction:column; min-width:0; min-height:0; padding:10px; gap:8px; }',
      '.oew-editor-zone { flex:1; display:flex; flex-direction:column; min-height:0; }',
      '.oew-toolbar-host { flex:none; }',
      '.oew-editor-host { flex:1; min-height:0; overflow-y:auto; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-top:none; border-radius:0 0 6px 6px; }',
      '.oew-editor-host .emr-doc .ProseMirror { min-height:100%; }',
      '.oew-statusbar { flex:none; display:flex; align-items:center; gap:12px; padding:5px 12px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; flex-wrap:wrap; }',
      '.oew-statusbar-title { color:var(--yb-ink-1,#1c2430); font-weight:600; max-width:42%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.oew-dirty { color:var(--yb-warning,#a26b1b); }',
      '.oew-draft-chip { color:var(--yb-success,#3c862d); }',
      /* ---- 空态与降级 ---- */
      '.oew-empty-root { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--yb-ink-4,#8994a5); }',
      '.oew-empty-root svg { width:48px; height:48px; opacity:.5; }',
      '.oew-empty-root-text { font-size:13px; }',
      '.oew-empty-inner { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--yb-ink-4,#8994a5); }',
      '.oew-empty-inner svg { width:40px; height:40px; opacity:.5; }',
      '.oew-nodoc { flex:1; display:flex; flex-direction:column; gap:8px; min-height:0; }',
      '.oew-nodoc .el-textarea { flex:1; min-height:0; }',
      '.oew-nodoc .el-textarea__inner { height:100%; resize:none; font-size:14px; line-height:1.8; }',
      /* ---- 历史纯文本病历(只读回显) ---- */
      '.oew-legacy-block { flex:1; min-height:0; overflow-y:auto; padding:10px 12px; display:flex; flex-direction:column; gap:8px; }',
      '.oew-legacy-banner { display:flex; align-items:center; gap:6px; padding:6px 10px; font-size:12px; color:var(--yb-warning,#a26b1b); background:var(--yb-warning-bg,#fbf3e6); border:1px solid #f0ddc0; border-radius:6px; }',
      '.oew-legacy-row { display:flex; gap:10px; font-size:13px; line-height:1.8; border-bottom:1px dashed var(--yb-border-light,#ebeff4); padding:4px 0; }',
      '.oew-legacy-row .lb { flex:none; width:76px; color:var(--yb-ink-3,#5a6a7e); font-weight:600; }',
      '.oew-legacy-row .vl { flex:1; color:var(--yb-ink-1,#1c2430); white-space:pre-wrap; word-break:break-all; }',
      /* ---- 右栏: 助手面板 ---- */
      '.oew-right { width:320px; flex:none; display:flex; flex-direction:column; min-height:0; background:var(--yb-surface,#fff); border-left:1px solid var(--yb-border,#dfe4eb); }',
      '.oew-right.is-collapsed { display:none; }',
      '.oew-right .el-tabs { flex:1; display:flex; flex-direction:column; min-height:0; }',
      '.oew-right .el-tabs__header { margin-bottom:0; flex:none; }',
      '.oew-right .el-tabs__nav-scroll { padding:0 6px; }',
      '.oew-right .el-tabs__item { height:34px; line-height:34px; padding:0 9px; font-size:12px; }',
      '.oew-right .el-tabs__content { flex:1; overflow-y:auto; min-height:0; }',
      '.oew-tab-body { padding:10px; display:flex; flex-direction:column; gap:8px; }',
      '.oew-tab-tool { display:flex; gap:6px; align-items:center; flex:none; flex-wrap:wrap; }',
      '.oew-tab-empty { font-size:12px; color:var(--yb-ink-4,#8994a5); text-align:center; padding:14px 4px; line-height:1.7; }',
      /* ---- Tab1 常用语 ---- */
      '.oew-phrase { padding:7px 9px; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; cursor:pointer; display:flex; flex-direction:column; gap:4px; }',
      '.oew-phrase:hover { border-color:var(--yb-brand,#1a5c9e); background:#f5f9fd; }',
      '.oew-phrase-text { font-size:13px; color:var(--yb-ink-1,#1c2430); line-height:1.6; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }',
      '.oew-phrase-foot { display:flex; align-items:center; gap:6px; font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.oew-phrase-use { background:var(--yb-surface-2,#f7f9fc); border-radius:8px; padding:0 7px; line-height:15px; }',
      /* ---- Tab2 历史病历 ---- */
      '.oew-his-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:4px; }',
      '.oew-his-title { font-size:13px; color:var(--yb-ink-1,#1c2430); font-weight:600; word-break:break-all; }',
      '.oew-his-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); display:flex; gap:6px; flex-wrap:wrap; }',
      '.oew-his-line { font-size:12px; color:var(--yb-ink-3,#5a6a7e); line-height:1.6; word-break:break-all; }',
      /* ---- Tab3 检查报告 ---- */
      '.oew-rep-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:4px; }',
      '.oew-rep-card.is-abnormal { border-color:#f2c8c8; background:var(--yb-danger-bg,#fdf0f0); }',
      '.oew-rep-head { display:flex; align-items:center; gap:6px; }',
      '.oew-rep-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.oew-rep-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.oew-rep-text { font-size:12px; color:var(--yb-ink-2,#3a4757); line-height:1.6; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }',
      /* ---- Tab4 CDSS ---- */
      '.oew-cdss-summary { display:flex; gap:6px; flex-wrap:wrap; }',
      '.oew-cdss-chip { padding:2px 9px; border-radius:10px; font-size:12px; }',
      '.oew-cdss-chip.is-block { background:var(--yb-danger-bg,#fdf0f0); color:var(--yb-danger,#c74f4f); }',
      '.oew-cdss-chip.is-warning { background:var(--yb-warning-bg,#fbf3e6); color:var(--yb-warning,#a26b1b); }',
      '.oew-cdss-chip.is-info { background:#eaf3fb; color:#2a6aa9; }',
      '.oew-cdss-alert { border-radius:6px; padding:8px 10px; border:1px solid transparent; }',
      '.oew-cdss-alert.is-block { background:var(--yb-danger-bg,#fdf0f0); border-color:#f2c8c8; }',
      '.oew-cdss-alert.is-warning { background:var(--yb-warning-bg,#fbf3e6); border-color:#f0ddc0; }',
      '.oew-cdss-alert.is-info { background:#eaf3fb; border-color:#cfe2f3; }',
      '.oew-cdss-name { font-size:13px; font-weight:700; margin-bottom:2px; display:flex; align-items:center; gap:6px; }',
      '.oew-cdss-alert.is-block .oew-cdss-name { color:var(--yb-danger,#c74f4f); }',
      '.oew-cdss-alert.is-warning .oew-cdss-name { color:var(--yb-warning,#a26b1b); }',
      '.oew-cdss-alert.is-info .oew-cdss-name { color:#2a6aa9; }',
      '.oew-cdss-level { margin-left:auto; font-size:11px; font-weight:400; opacity:.85; }',
      '.oew-cdss-msg { font-size:12px; line-height:1.6; color:var(--yb-ink-2,#3a4757); word-break:break-all; }',
      /* ---- Tab5 版本 ---- */
      '.oew-ver-card { display:flex; align-items:center; gap:8px; padding:7px 9px; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; cursor:pointer; }',
      '.oew-ver-card:hover { border-color:var(--yb-brand,#1a5c9e); }',
      '.oew-ver-card.is-sel { border-color:var(--yb-brand,#1a5c9e); background:#eaf3fb; }',
      '.oew-ver-main { flex:1; min-width:0; }',
      '.oew-ver-no { font-size:13px; font-weight:700; color:var(--yb-brand,#1a5c9e); }',
      '.oew-ver-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); margin-top:1px; }',
      '.oew-ver-ops { display:flex; gap:4px; }',
      '.oew-diff-chips { display:flex; gap:8px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); padding:2px 0 6px; flex-wrap:wrap; }',
      '.oew-diff-table { width:100%; border-collapse:collapse; font-family:Consolas,monospace; font-size:12px; table-layout:fixed; }',
      '.oew-diff-table th { position:sticky; top:0; z-index:1; background:var(--yb-surface-2,#f7f9fc); border:1px solid var(--yb-border,#dfe4eb); padding:4px 10px; text-align:left; }',
      '.oew-diff-table td { border:1px solid var(--yb-border-light,#ebeff4); padding:2px 10px; white-space:pre-wrap; word-break:break-all; vertical-align:top; }',
      '.oew-diff-table .is-add { background:var(--yb-success-bg,#eef6ec); color:var(--yb-success,#3c862d); }',
      '.oew-diff-table .is-del { background:var(--yb-danger-bg,#fdf0f0); color:var(--yb-danger,#c74f4f); text-decoration:line-through; }',
      '.oew-diff-table .is-modify { background:var(--yb-warning-bg,#fbf3e6); }',
      '.oew-diff-table .is-empty { background:var(--yb-canvas,#f2f4f8); color:var(--yb-ink-4,#8994a5); }',
      '.oew-ver-preview { max-height:56vh; overflow-y:auto; padding:4px 2px; white-space:pre-wrap; word-break:break-all; font-size:13px; line-height:1.8; color:var(--yb-ink-1,#1c2430); }',
      /* ---- 底部 NLG 自然语言预览条 ---- */
      '.oew-nlg { flex:none; border-top:1px solid var(--yb-border,#dfe4eb); background:var(--yb-surface-2,#f7f9fc); }',
      '.oew-nlg-head { display:flex; align-items:center; gap:8px; padding:5px 12px; font-size:12px; font-weight:600; color:var(--yb-ink-3,#5a6a7e); cursor:pointer; user-select:none; }',
      '.oew-nlg-head .oew-icon { color:var(--yb-brand,#1a5c9e); }',
      '.oew-nlg-tip { font-weight:400; color:var(--yb-ink-4,#8994a5); }',
      '.oew-nlg-ops { margin-left:auto; display:flex; align-items:center; gap:4px; }',
      '.oew-nlg-body { max-height:168px; overflow-y:auto; padding:2px 12px 10px; display:flex; flex-direction:column; gap:6px; }',
      '.oew-nlg-sec { display:flex; align-items:flex-start; gap:8px; font-size:13px; line-height:1.7; color:var(--yb-ink-1,#1c2430); }',
      '.oew-nlg-sec-title { flex:none; font-weight:600; color:var(--yb-brand,#1a5c9e); }',
      '.oew-nlg-sec-text { flex:1; min-width:0; white-space:pre-wrap; word-break:break-all; }',
      '.oew-nlg-sec .el-button { flex:none; }',
      /* ---- 窄屏回落: 助手面板下沉, 编辑器定高 ---- */
      '@media (max-width: 1180px) { .oew-body { flex-direction:column; } .oew-right { width:auto; max-height:44vh; border-left:none; border-top:1px solid var(--yb-border,#dfe4eb); } .oew-editor-host { min-height:300px; } }',
      /* ---- 滚动条 ---- */
      '.oew-editor-host::-webkit-scrollbar, .oew-right .el-tabs__content::-webkit-scrollbar, .oew-nlg-body::-webkit-scrollbar, .oew-legacy-block::-webkit-scrollbar { width:6px; }',
      '.oew-editor-host::-webkit-scrollbar-thumb, .oew-right .el-tabs__content::-webkit-scrollbar-thumb, .oew-nlg-body::-webkit-scrollbar-thumb, .oew-legacy-block::-webkit-scrollbar-thumb { background:var(--yb-border-strong,#ccd4de); border-radius:3px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  /* 门诊 SOAP 章节键(his_emr_template scope=2 种子 OUTP_SOAP_SECTIONS 同口径;
   * 章节 emrSection attrs.sectionKey 与数据元 emrField attrs.fieldKey 双写一致) */
  var SOAP_KEYS = {
    chief: 'chiefComplaint',        /* 主诉 */
    present: 'presentIllness',      /* 现病史 */
    past: 'pastHistory',            /* 既往史 */
    allergy: 'allergyHistory',      /* 过敏史 */
    physical: 'physicalExam',       /* 体格检查(体征引用目标) */
    aux: 'auxExam',                 /* 辅助检查 */
    treatment: 'treatmentOpinion',  /* 处理意见 */
    followup: 'followupNote'        /* 随访备注 */
  };
  /* 病历类型(门诊 scope=2, 与 NLG/CDSS 规则的 recordType 门诊口径一致) */
  var RECORD_TYPE_OUTP = '2';
  /* 常用语类别(与 his_emr_phrase.category 口径一致) */
  var PHRASE_CATEGORIES = [
    { value: '', label: '全部' },
    { value: 'chief_complaint', label: '主诉' },
    { value: 'present_illness', label: '现病史' },
    { value: 'past_history', label: '既往史' },
    { value: 'physical_exam', label: '体格检查' },
    { value: 'diagnosis', label: '诊断' },
    { value: 'treatment', label: '处理' }
  ];
  var OPERATE_TYPES = { save: '保存', submit: '提交', audit: '审核', sign: '签名' };
  var SOAP_LABELS = { chiefComplaint: '主诉', presentIllness: '现病史', pastHistory: '既往史', allergyHistory: '过敏史', physicalExam: '体格检查', auxExam: '辅助检查', treatmentOpinion: '处理意见', followupNote: '随访备注' };

  /* 内联 SVG 图标(feather 风格, viewBox 24, stroke currentColor) */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    file: svg('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>'),
    save: svg('<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>'),
    send: svg('<path d="m22 2-7 20-4-9-9-4z"/><path d="M22 2 11 13"/>'),
    printer: svg('<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/>'),
    clock: svg('<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>'),
    search: svg('<circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>'),
    file2: svg('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>'),
    alert: svg('<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>'),
    layers: svg('<polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/>'),
    history: svg('<path d="M3 3v5h5"/><path d="M3.05 13A9 9 0 1 0 6 5.3L3 8"/><polyline points="12 7 12 12 15 15"/>'),
    message: svg('<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>'),
    clipboard: svg('<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/>'),
    activity: svg('<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>'),
    panel: svg('<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="15" y1="3" x2="15" y2="21"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    check: svg('<polyline points="20 6 9 17 4 12"/>')
  };

  /* ================= 纯工具函数 ================= */
  function fmtDT(v) {
    if (v == null || v === '') { return '-'; }
    var s = String(v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }
  function fmtHM(ts) {
    var d = new Date(Number(ts) || 0);
    if (isNaN(d.getTime())) { return ''; }
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }
  /* 轻提示(Element Plus 缺失时降级 console, 绝不抛错) */
  function toast(type, text) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessage) { EP.ElMessage[type](text); }
    else { try { console.log('[oew:' + type + '] ' + text); } catch (e) { /* noop */ } }
  }
  function confirmBox(text, title, opts) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessageBox) {
      return EP.ElMessageBox.confirm(text, title || '确认', opts || {});
    }
    return global.Promise ? global.Promise.reject(new Error('cancel')) : null;
  }
  /* 空值判定(value 为 null/''/空数组 均视为空; 0/false 等为有效值) */
  function isBlankValue(v) {
    if (v == null) { return true; }
    if (Array.isArray(v)) { return v.length === 0; }
    var s = String(v);
    return s === '' || s === '[]' || s === '{}';
  }
  /* 值等价比较(JSON 序列化兜底多选数组等结构) */
  function sameValue(a, b) {
    if (a === b) { return true; }
    try { return JSON.stringify(a) === JSON.stringify(b); } catch (e) { return false; }
  }
  /* ---- 旧内容归一化为 Tiptap 文档(纯文本模板降级/引用预览共用) ---- */
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
  /* Tiptap 文档(或任意节点) → 纯文本(版本快照/NLG 回退展示用) */
  function docToPlainText(doc) {
    var lines = [];
    (function walk(node) {
      if (!node) { return; }
      if (node.type === 'text' && node.text != null) {
        lines.push(node.text);
        return;
      }
      var kids = node.content || [];
      if (!kids.length && node.type && node.type !== 'doc') {
        lines.push('');                                   /* 空块级节点 → 空行 */
        return;
      }
      kids.forEach(walk);
      if (node.type && node.type !== 'text' && node.type !== 'doc' && kids.length) {
        lines.push('');                                   /* 块级节点之间补换行 */
      }
    })(doc);
    var out = [];
    var buf = '';
    var flush = function () {
      if (buf.length) { out.push(buf); buf = ''; }
    };
    lines.forEach(function (ln) {
      if (ln === '') { flush(); out.push(''); }
      else { buf += ln; }
    });
    flush();
    return out.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  }
  /* 快照内容(可能是 Tiptap JSON / 旧 HTML / 键值 JSON) → 纯文本 */
  function plainTextOf(snap) {
    var s = String(snap == null ? '' : snap).trim();
    if (!s) { return '(无内容)'; }
    var first = s.charAt(0);
    if (first === '{' || first === '[') {
      try {
        var obj = JSON.parse(s);
        if (obj && typeof obj === 'object' && !Array.isArray(obj) && obj.type === 'doc') {
          return docToPlainText(obj) || '(无内容)';
        }
        var paras = [];
        Object.keys(obj || {}).forEach(function (k) {
          var v = obj[k];
          if (v != null && String(v).length) { paras.push(k + '：' + v); }
        });
        return paras.join('\n') || '(无内容)';
      } catch (e) { /* fallthrough */ }
    }
    if (first === '<') {
      /* DOMParser 离线解析提取纯文本(不执行脚本/不加载资源, 避免 innerHTML 解析副作用) */
      try {
        var parsed = new global.DOMParser().parseFromString(s, 'text/html');
        return ((parsed.body && parsed.body.textContent) || '').trim() || '(无内容)';
      } catch (e) { return '(无内容)'; }
    }
    return s;
  }

  /* ================= 组件 ================= */
  var OutpEmrWriter = {
    name: 'OutpEmrWriter',
    inject: ['currentVisit', 'currentPatient', 'visitHistory', 'diagnoses'],
    emits: ['save-draft', 'submit'],
    data: function () {
      return {
        icons: ICONS,
        phraseCategories: PHRASE_CATEGORIES,
        /* 模板与编辑器 */
        templates: [],
        templatesLoading: false,
        selectedTemplateId: null,
        templateNoDoc: false,        /* 模板无 document(Tiptap JSON) → 富文本不可用降级 */
        plainFallback: '',           /* 无文档模板的纯文本降级书写区 */
        editorLoading: false,
        editorWrapper: null,
        dirty: false,
        saving: false,
        /* 存量病历回填(visit.structure) */
        pendingRestore: null,        /* {templateId, structure} */
        /* 离线草稿 */
        draftKey: '',
        draftSavedText: '',
        /* 右侧助手面板 */
        panelVisible: true,
        activeTab: 'phrase',
        /* Tab1 常用语 */
        phrases: [],
        phraseLoading: false,
        phraseCategory: '',
        phraseKeyword: '',
        /* Tab3 检查报告 */
        labReports: [],
        examReports: [],
        reportLoading: false,
        reportFailed: false,
        reportLoaded: false,
        reportTab: 'lab',
        /* Tab4 CDSS */
        cdssAlerts: [],
        cdssLoading: false,
        cdssFailed: false,
        cdssEvaluated: false,
        /* Tab5 版本 */
        versions: [],
        versionLoading: false,
        versionSel: [],
        diffVisible: false,
        diffData: null,
        diffLoading: false,
        versionVisible: false,
        versionPreview: '',
        versionPreviewLoading: false,
        versionPreviewTitle: '',
        /* NLG 自然语言预览 */
        nlgSections: [],
        nlgLoading: false,
        nlgFailed: false,
        nlgCollapsed: false,
        /* 完整性质控 */
        quality: null,
        qualityLoading: false
      };
    },
    computed: {
      visit: function () { return this.currentVisit || null; },
      visitId: function () {
        var v = this.visit;
        return v ? (v.id != null ? v.id : v.visitId) : null;
      },
      hasVisit: function () { return this.visitId != null && String(this.visitId) !== ''; },
      patientId: function () {
        var p = this.currentPatient || {};
        var v = this.visit || {};
        return p.id || p.patientId || v.patientId || null;
      },
      patientLabel: function () {
        var p = this.currentPatient || {};
        var v = this.visit || {};
        return v.patientName || p.name || p.patientName || '';
      },
      /* 只读: 无就诊或就诊已完成(>=3) */
      readOnly: function () {
        var v = this.visit;
        return !v || Number(v.visitStatus) >= 3;
      },
      canEdit: function () {
        var v = this.visit;
        return !!v && Number(v.visitStatus) === 2;
      },
      histories: function () { return Array.isArray(this.visitHistory) ? this.visitHistory : []; },
      diagnosisList: function () { return Array.isArray(this.diagnoses) ? this.diagnoses : []; },
      selectedTemplate: function () {
        var vm = this;
        var id = vm.selectedTemplateId;
        if (id == null) { return null; }
        return (vm.templates || []).filter(function (t) { return String(t.id) === String(id); })[0] || null;
      },
      /* 历史「纯文本病历」: 已完成且无 structure 但存在 SOAP 文本列 → 只读回显 */
      legacyHasText: function () {
        var v = this.visit || {};
        return ['chiefComplaint', 'presentIllness', 'physicalExam', 'auxExam', 'treatmentOpinion', 'pastHistory']
          .some(function (k) { return v[k] != null && String(v[k]).trim() !== ''; });
      },
      legacyText: function () {
        return !!this.visit && !this.visit.structure && this.readOnly && this.legacyHasText;
      },
      legacySoap: function () {
        var v = this.visit || {};
        var diag = (this.diagnosisList || []).map(function (d) { return d.diagName; }).filter(Boolean).join('、');
        var rows = [
          { label: '就诊时间', value: v.visitTime },
          { label: '主诉', value: v.chiefComplaint },
          { label: '现病史', value: v.presentIllness },
          { label: '既往史', value: v.pastHistory },
          { label: '过敏史', value: v.allergyHistory },
          { label: '体格检查', value: v.physicalExam },
          { label: '辅助检查', value: v.auxExam },
          { label: '诊断', value: diag },
          { label: '处理意见', value: v.treatmentOpinion },
          { label: '随访', value: v.followupNote }
        ];
        return rows.filter(function (r) { return r.value != null && String(r.value).trim() !== ''; });
      },
      /* 常用语前端二次过滤(类别已在请求侧过滤, 此处为关键词) */
      filteredPhrases: function () {
        var kw = String(this.phraseKeyword || '').trim().toLowerCase();
        if (!kw) { return this.phrases || []; }
        return (this.phrases || []).filter(function (p) {
          return String(p.content || '').toLowerCase().indexOf(kw) >= 0;
        });
      },
      cdssCounts: function () {
        var c = { block: 0, warning: 0, info: 0 };
        (this.cdssAlerts || []).forEach(function (a) {
          var lv = a && a.level;
          if (lv === 'block' || lv === 'warning' || lv === 'info') { c[lv]++; }
        });
        return c;
      },
      deptCode: function () {
        var v = this.visit || {};
        return v.deptCode || '';
      }
    },
    watch: {
      currentVisit: {
        immediate: true,
        deep: true,
        handler: function (visit) { this.resetFromVisit(visit); }
      },
      activeTab: function (tab) {
        var vm = this;
        if (tab === 'phrase' && !vm.phrases.length && !vm.phraseLoading) { vm.loadPhrases(); }
        if (tab === 'report' && !vm.reportLoaded && !vm.reportLoading) { vm.loadReports(); }
        if (tab === 'cdss' && vm.hasVisit && !vm.cdssEvaluated) { vm.evaluateCdssTab(); }
        if (tab === 'version' && vm.hasVisit && !vm.versions.length && !vm.versionLoading) { vm.loadVersions(); }
      }
    },
    created: function () {
      var vm = this;
      /* Ctrl/Cmd+S 全局快捷键(实例级绑定, 卸载时移除) */
      vm._onKeydown = function (ev) {
        if ((ev.ctrlKey || ev.metaKey) && String(ev.key || '').toLowerCase() === 's') {
          ev.preventDefault();
          if (vm.canEdit) { vm.emitSave(false); }
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
    },
    beforeUnmount: function () {
      var vm = this;
      global.document.removeEventListener('keydown', vm._onKeydown);
      if (vm._nlgTimer) { clearTimeout(vm._nlgTimer); vm._nlgTimer = null; }
      vm.destroyEditor();
    },

    methods: {
      /* ================= 通用 ================= */
      fmtDT: fmtDT,

      /* ================= 编辑器内容读写(内部) ================= */
      currentDocJSON: function () {
        var w = this.editorWrapper;
        return (w && typeof w.toJSON === 'function') ? w.toJSON() : null;
      },
      currentFieldMap: function () {
        var w = this.editorWrapper;
        return (w && typeof w.extractFields === 'function') ? (w.extractFields() || {}) : {};
      },
      ready: function () {
        return !!(this.editorWrapper && typeof this.editorWrapper.toJSON === 'function');
      },
      /* 批量回填 emrField 取值(visit.structure fieldKey→值; 服务端为真源, 直接覆盖) */
      setFieldValues: function (map) {
        var w = this.editorWrapper;
        var ed = w && w.editor;
        if (!ed || !ed.state || !map || typeof map !== 'object') { return 0; }
        var applied = 0;
        var tr = ed.state.tr;
        ed.state.doc.descendants(function (n, pos) {
          if (n.type.name !== 'emrField' || !n.attrs.fieldKey) { return; }
          var v = map[n.attrs.fieldKey];
          if (v == null || v === '') { return; }
          if (sameValue(n.attrs.value, v)) { return; }
          tr.setNodeMarkup(pos, null, Object.assign({}, n.attrs, { value: v }));
          applied++;
        });
        if (applied) {
          try { ed.view.dispatch(tr); this.dirty = true; } catch (e) { return 0; }
        }
        return applied;
      },
      /* 定位章节(emrSection attrs.sectionKey), 返回末尾插入位(inner end)或 null */
      findSectionEnd: function (ed, sectionKey) {
        var hit = null;
        ed.state.doc.descendants(function (n, pos) {
          if (hit != null) { return false; }
          if (n.type.name === 'emrSection' && n.attrs.sectionKey === sectionKey) {
            hit = pos + n.nodeSize - 1;
            return false;
          }
          return true;
        });
        return hit;
      },
      /* 定位数据元(emrField attrs.fieldKey), 返回其宿主段落文本末位或 null */
      findFieldTextEnd: function (ed, fieldKey) {
        var hit = null;
        ed.state.doc.descendants(function (n, pos) {
          if (hit != null) { return false; }
          if (n.type.name === 'emrField' && n.attrs.fieldKey === fieldKey) { hit = pos; }
        });
        if (hit == null) { return null; }
        try {
          var $pos = ed.state.doc.resolve(hit + 1);
          return $pos.end(Math.max(0, $pos.depth - 1));
        } catch (e) { return null; }
      },
      /* 追加文本到章节: 优先 emrSection 尾部新起段落, 回退 emrField 宿主段落尾插 */
      appendSectionText: function (sectionKey, text) {
        var w = this.editorWrapper;
        var ed = w && w.editor;
        var t = String(text == null ? '' : text).trim();
        if (!ed || !t) { return false; }
        var at = this.findSectionEnd(ed, sectionKey);
        var mode = 'section';
        if (at == null) {
          at = this.findFieldTextEnd(ed, sectionKey);
          mode = 'field';
        }
        if (at == null) { return false; }
        try {
          var schema = ed.state.schema;
          var tr;
          if (mode === 'section') {
            tr = ed.state.tr.insert(at, schema.nodes.paragraph.create(null, schema.text(t)));
          } else {
            tr = ed.state.tr.insert(at, schema.text(t));
          }
          ed.view.dispatch(tr);
          this.dirty = true;
          return true;
        } catch (e) {
          return false;
        }
      },
      /* 章节现有纯文本(体征引用去重用) */
      sectionPlainText: function (sectionKey) {
        var w = this.editorWrapper;
        var ed = w && w.editor;
        if (!ed || !ed.state) { return ''; }
        var out = '';
        ed.state.doc.descendants(function (n) {
          if (out) { return false; }
          if (n.type.name === 'emrSection' && n.attrs.sectionKey === sectionKey) {
            out = docToPlainText(n.toJSON());
          }
        });
        return out;
      },
      /* 文档中是否存在某章节/数据元(章节键与数据元键同口径) */
      hasDocKey: function (key) {
        var w = this.editorWrapper;
        var ed = w && w.editor;
        if (!ed || !ed.state || !key) { return false; }
        var hit = false;
        ed.state.doc.descendants(function (n) {
          if (hit) { return false; }
          if (n.type.name === 'emrSection' && n.attrs.sectionKey === key) { hit = true; }
          else if (n.type.name === 'emrField' && n.attrs.fieldKey === key) { hit = true; }
        });
        return hit;
      },
      /* 必填数据元缺失清单(extractFieldsDetail 携 required/valueType) */
      missingRequired: function () {
        var w = this.editorWrapper;
        var miss = [];
        var detail = (w && typeof w.extractFieldsDetail === 'function') ? (w.extractFieldsDetail() || []) : [];
        detail.forEach(function (f) {
          var req = f.required === true || f.required === 'true' || f.required === '1' || f.required === 1;
          if (req && isBlankValue(f.value)) { miss.push(f.fieldName || f.fieldKey); }
        });
        return miss;
      },

      /* ================= 模板装载 ================= */
      loadTemplates: function () {
        var vm = this;
        if (!HIS.get) { return global.Promise ? global.Promise.resolve() : null; }
        vm.templatesLoading = true;
        return HIS.get('/api/his/emr/template/list?scope=2&mine=true')
          .then(function (list) {
            vm.templates = (Array.isArray(list) ? list : []).map(function (t) {
              return Object.assign({}, t, { id: HIS.id(t.id), name: t.templateName || t.name || ('模板' + t.id) });
            });
            /* 存量病历史回填: 模板晚于就诊到达时补一次自动选择 */
            if (vm.pendingRestore) { vm.autoSelectTemplate(); }
          })
          .catch(function (e) {
            vm.templates = [];
            HIS.notifyError(e);
          })
          .finally(function () { vm.templatesLoading = false; });
      },
      onTemplateChange: function (id) {
        if (!id) { this.destroyEditor(); this.templateNoDoc = false; return; }
        this.applyTemplate();
      },
      /* 存量就诊自动选模板(仅当该模板可见) */
      autoSelectTemplate: function () {
        var vm = this;
        var pr = vm.pendingRestore;
        if (!pr || !pr.templateId) { return; }
        var exists = (vm.templates || []).some(function (t) { return String(t.id) === String(pr.templateId); });
        if (exists) {
          if (String(vm.selectedTemplateId || '') !== String(pr.templateId)) {
            vm.selectedTemplateId = pr.templateId;
            vm.applyTemplate();
          }
        } else if (vm.templates.length && !vm.templatesLoading) {
          /* 模板已被删除/不可见: 置空选择并提示 */
          vm.selectedTemplateId = null;
          vm.pendingRestore = null;
          toast('warning', '原病历模板已不可见, 请重新选择模板');
        }
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
          toast('warning', '该模板暂不支持富文本编辑');
          return;
        }
        vm.$nextTick(function () { vm.buildEditor(doc); });
      },

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
          mode: 'edit',
          readOnly: vm.readOnly,
          document: doc,
          placeholder: '书写门诊病历内容…',
          nlgEnabled: false,           /* NLG 由本组件底部预览条接管(防抖 1s) */
          cdssEnabled: false,          /* CDSS 由右侧页签手动评估 */
          recordType: RECORD_TYPE_OUTP,
          deptCode: vm.deptCode || '',
          onSave: function () { vm.emitSave(false); },
          onFieldChange: function () { vm.dirty = true; }
        }).then(function (wrapper) {
          if (seq !== vm._editorSeq) { try { wrapper.destroy(); } catch (e) { /* 已被更新批次取代 */ } return; }
          vm.editorWrapper = wrapper;
          vm.editorLoading = false;
          if (typeof wrapper.on === 'function') {
            vm._unsubUpdate = wrapper.on('update', function () {
              vm.dirty = true;
              vm.scheduleNlg();
            });
          }
          /* 存量结构化病历史回填: 仅当所选模板与原模板一致 */
          var pr = vm.pendingRestore;
          if (pr && pr.structure && String(pr.templateId) === String(vm.selectedTemplateId)) {
            try { vm.setFieldValues(JSON.parse(pr.structure)); } catch (e) { /* 非法结构忽略 */ }
          }
          vm.pendingRestore = null;
          if (!vm.readOnly) { try { wrapper.focus(); } catch (e) { /* noop */ } }
          /* 门诊宏解析(服务端优先; 失败回退既有宏解析接口; 均失败保留宏占位) */
          vm.resolveMacros();
          /* 离线草稿: 可编辑态开启自动保存 + 冲突检测 */
          if (!vm.readOnly) {
            vm.startDraft();
            vm.checkDraftConflict();
          }
          vm.scheduleNlg();          /* 初始生成一次 */
        }).catch(function (e) {
          if (seq === vm._editorSeq) { vm.editorLoading = false; }
          HIS.notifyError(e);
        });
      },
      destroyEditor: function () {
        var vm = this;
        vm._editorSeq = (vm._editorSeq || 0) + 1;   /* 使在途 createEditor 失效 */
        vm.stopDraft();
        if (vm._nlgTimer) { clearTimeout(vm._nlgTimer); vm._nlgTimer = null; }
        if (vm._unsubUpdate) { try { vm._unsubUpdate(); } catch (e) { /* noop */ } vm._unsubUpdate = null; }
        if (vm.editorWrapper) {
          try { vm.editorWrapper.destroy(); } catch (e) { /* noop */ }
          vm.editorWrapper = null;
        }
      },

      /* ================= 宏解析(服务端优先, 双端点回退) ================= */
      resolveMacros: function () {
        var vm = this;
        if (!vm.ready() || vm.readOnly || !vm.visitId || !HIS.post) { return; }
        var json = vm.currentDocJSON();
        var applyMap = function (map) {
          var ed = vm.editorWrapper && vm.editorWrapper.editor;
          if (!ed || !map || typeof map !== 'object') { return; }
          if (typeof ed.commands.resolveEmrMacro !== 'function') { return; }
          var chain = ed.chain().focus();
          Object.keys(map).forEach(function (code) {
            var v = map[code];
            if (v != null && v !== '') {
              try { chain.resolveEmrMacro(code, String(v)); } catch (e) { /* 单宏失败不扩散 */ }
            }
          });
          try { chain.run(); } catch (e) { /* noop */ }
        };
        /* P3 新端点: body 携 Tiptap JSON(字符串, 与后端 String.valueOf+fastjson 解析口径一致);
         * 若直接返回解析后文档(type=doc)则整文回写 */
        HIS.post('/api/his/outp/emr/resolve-tiptap-macros?visitId=' + HIS.idParam(vm.visitId),
          { documentJson: json ? JSON.stringify(json) : '' })
          .then(function (d) {
            if (d && d.type === 'doc' && vm.ready()) { vm.editorWrapper.fromJSON(d); return; }
            applyMap(d);
          })
          .catch(function () {
            /* 回退: 既有门诊宏解析({macroCode: 值}) */
            HIS.post('/api/his/outp/emr/macro/resolve?visitId=' + HIS.idParam(vm.visitId), {})
              .then(applyMap)
              .catch(function () { /* 宏解析不可用: 保留宏占位, 不阻断书写 */ });
          });
      },

      /* ================= 就诊切换(resetFromVisit) ================= */
      resetFromVisit: function (visit) {
        var vm = this;
        var key = HIS.idKey(visit && (visit.id != null ? visit.id : visit && visit.visitId));
        if (key && key === vm._loadedVisitKey) {
          /* 同一就诊的属性更新(如完成接诊回写状态): 仅同步编辑器只读态, 不重建(保未保存书写) */
          vm.syncEditorMode();
          return;
        }
        vm._loadedVisitKey = key;
        vm.destroyEditor();
        vm.selectedTemplateId = null;
        vm.templateNoDoc = false;
        vm.plainFallback = '';
        vm.dirty = false;
        vm.saving = false;
        vm.nlgSections = []; vm.nlgFailed = false;
        vm.cdssAlerts = []; vm.cdssEvaluated = false; vm.cdssFailed = false;
        vm.versions = []; vm.versionSel = [];
        vm.quality = null;
        vm.draftSavedText = '';
        vm.labReports = []; vm.examReports = []; vm.reportLoaded = false; vm.reportFailed = false;
        if (!visit) { vm.pendingRestore = null; return; }
        /* 预登记存量结构化病历(编辑器建好后回填) */
        vm.pendingRestore = visit.emrTemplateId != null
          ? { templateId: HIS.id(visit.emrTemplateId), structure: visit.structure || '' }
          : null;
        if (vm.templates.length) { vm.autoSelectTemplate(); }
        else if (!vm.templatesLoading) { vm.loadTemplates(); }
      },
      /* 就诊状态变化同步编辑器可编辑性(接诊中↔已完成) */
      syncEditorMode: function () {
        var vm = this;
        var w = vm.editorWrapper;
        if (w && typeof w.setMode === 'function') {
          try { w.setMode(vm.readOnly ? 'preview' : 'edit'); } catch (e) { /* noop */ }
        }
      },

      /* ================= 保存 / 提交(双轨: content Tiptap JSON + structure 字段值) ================= */
      emitSave: function (submit) {
        var vm = this;
        if (vm.legacyText) { toast('warning', '历史文本病历为只读, 无法在此编辑保存'); return; }
        if (!vm.selectedTemplateId) { toast('warning', '请先选择门诊病历模板'); return; }
        if (vm.readOnly) { toast('warning', '当前就诊状态不可编辑病历'); return; }
        if (!vm.ready()) { toast('warning', '编辑器尚未就绪'); return; }
        /* 提交才做必填校验; 暂存为过程稿不拦截(切换患者自动暂存不打扰) */
        if (submit) {
          var miss = vm.missingRequired();
          if (miss.length) { toast('warning', '请填写必填项：' + miss.join('、')); return; }
        }
        var json = vm.currentDocJSON();
        var payload = {
          visitId: vm.visitId,
          emrTemplateId: vm.selectedTemplateId,
          /* 富文本轨: Tiptap JSON 串(P3 后端 content 列) */
          content: JSON.stringify(json),
          /* 字段值轨: fieldKey→值(与既有完整性质控/完成派生 SOAP 兼容) */
          structure: JSON.stringify(vm.currentFieldMap())
        };
        vm.$emit('save-draft', payload);
        if (submit) { vm.$emit('submit', payload); }
        vm.dirty = false;
        vm.resetDraftAfterSave();
        HIS.notifySuccess(submit ? '门诊病历已提交保存' : '门诊病历草稿已暂存(F3)');
      },
      /* 供主编排(完成接诊 F4)调用: 须选模板且必填数据元齐备 */
      validateForFinish: function () {
        var vm = this;
        if (vm.legacyText) { return true; }
        if (!vm.selectedTemplateId) { toast('warning', '请先选择门诊病历模板并完成书写'); return false; }
        if (!vm.ready()) { toast('warning', '编辑器尚未就绪, 无法完成接诊'); return false; }
        var miss = vm.missingRequired();
        if (miss.length) { toast('warning', '请填写必填项：' + miss.join('、')); return false; }
        return true;
      },
      /* 供主编排完成接诊取 payload: content(Tiptap JSON 串) + structure(字段值串) 双轨交付 */
      buildFinishPayload: function () {
        var vm = this;
        if (vm.legacyText || !vm.ready()) { return {}; }
        return {
          emrTemplateId: vm.selectedTemplateId,
          content: JSON.stringify(vm.currentDocJSON()),
          structure: JSON.stringify(vm.currentFieldMap())
        };
      },

      /* ================= 病历追加/引用(doctor.js $refs 中继, 与 emr-panel 同签名) ================= */
      /* 处方/医嘱【插入病历】→ 追加到「处理意见」章节 */
      appendTreatment: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.checkAppendable('处理意见')) { return; }
        if (this.appendSectionText(SOAP_KEYS.treatment, t)) {
          toast('success', '已插入处理意见, 请核对后随病历保存(F3)');
        } else {
          toast('warning', '当前模板无「处理意见」章节, 无法插入');
        }
      },
      /* 报告/危急值摘要 → 追加到「辅助检查」章节 */
      appendAuxExam: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.checkAppendable('辅助检查')) { return; }
        if (this.appendSectionText(SOAP_KEYS.aux, t)) {
          toast('success', '已插入辅助检查, 请核对后随病历保存(F3)');
        } else {
          toast('warning', '当前模板无「辅助检查」章节, 无法插入');
        }
      },
      /* 体征摘要 → 引用到「体格检查」章节(去重) */
      appendVital: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.checkAppendable('体格检查')) { return; }
        var line = '生命体征: ' + t;
        if (this.sectionPlainText(SOAP_KEYS.physical).indexOf(line) >= 0) {
          toast('info', '体征摘要已存在, 未重复插入');
          return;
        }
        if (this.appendSectionText(SOAP_KEYS.physical, line)) {
          toast('success', '已引用体征到体格检查, 请核对后随病历保存(F3)');
        } else {
          toast('warning', '当前模板无「体格检查」章节, 无法引用');
        }
      },
      /* 追加前置守卫: 只读/编辑器未就绪返回 true(已提示) */
      checkAppendable: function (label) {
        if (this.legacyText || this.readOnly) { toast('warning', '病历只读, 无法插入摘要'); return true; }
        if (!this.ready()) { toast('warning', '请先选择病历模板'); return true; }
        return false;
      },
      /* 预问诊引用 → 回填「主诉/现病史」数据元 */
      applyPreConsult: function (payload) {
        payload = payload || {};
        if (this.legacyText || this.readOnly) { toast('warning', '病历只读, 无法引用预问诊'); return; }
        if (!this.ready()) { toast('warning', '请先选择病历模板'); return; }
        var vm = this;
        var applied = 0;
        if (payload.chiefComplaint && vm.hasDocKey(SOAP_KEYS.chief)) {
          var mChief = {}; mChief[SOAP_KEYS.chief] = payload.chiefComplaint;
          applied += vm.setFieldValues(mChief);
        }
        if (payload.presentIllness && vm.hasDocKey(SOAP_KEYS.present)) {
          var mPresent = {}; mPresent[SOAP_KEYS.present] = payload.presentIllness;
          applied += vm.setFieldValues(mPresent);
        }
        if (applied) { toast('success', '已引用预问诊 ' + applied + ' 项到当前病历'); }
        else { toast('warning', '当前模板无可对齐的主诉/现病史字段'); }
      },
      /* 历史就诊引用 → 按 sectionKey/fieldKey 对齐回填(仅当前模板存在的章节) */
      quoteHistory: function (history) {
        if (!history) { return; }
        if (this.legacyText || this.readOnly) { toast('warning', '病历只读, 无法引用'); return; }
        if (!this.ready()) { toast('warning', '请先选择病历模板'); return; }
        var vm = this;
        var map = {};
        [SOAP_KEYS.chief, SOAP_KEYS.present, SOAP_KEYS.past, SOAP_KEYS.physical, SOAP_KEYS.aux, SOAP_KEYS.treatment]
          .forEach(function (k) {
            var v = history[k];
            if (v != null && String(v).trim() !== '' && vm.hasDocKey(k)) { map[k] = v; }
          });
        var applied = vm.setFieldValues(map);
        if (applied) { toast('success', '已引用历史就诊 ' + applied + ' 项到当前病历'); }
        else { toast('warning', '历史就诊无可对齐到当前模板的字段'); }
      },
      /* 医技报告引用 → 摘要追加到「辅助检查」(与 emr-panel.quoteExamReport 同语义) */
      quoteExamReport: function (rep) {
        if (!rep) { return; }
        var type = rep.reportType === 'lab' ? '检验' : '检查';
        var name = rep.itemNames || rep.orderName || rep.reportNo || '未命名项目';
        var date = String(rep.reportTime || rep.auditTime || rep.createTime || '').slice(0, 10);
        var conclusion = this.reportConclusionOf(rep);
        if (!conclusion) { toast('info', '该报告暂无结论可引用'); return; }
        this.appendAuxExam('【' + type + '】' + name + '：' + conclusion + (date ? '（' + date + '）' : ''));
      },

      /* ================= 光标插入(常用语) ================= */
      insertAtCursor: function (text) {
        var vm = this;
        if (!vm.ready()) { toast('warning', '请先选择病历模板'); return false; }
        if (vm.readOnly) { toast('warning', '当前病历为只读模式, 不可插入内容'); return false; }
        try {
          vm.editorWrapper.editor.commands.insertContent(String(text == null ? '' : text));
          vm.dirty = true;
          return true;
        } catch (e) {
          toast('error', '插入失败');
          return false;
        }
      },

      /* ================= 离线草稿 ================= */
      startDraft: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.editorWrapper || !vm.visitId) { return; }
        vm.draftKey = 'outp:' + HIS.idKey(vm.visitId);
        var u = (typeof HIS.getUser === 'function') ? HIS.getUser() : null;
        try {
          HIS.EmrOfflineDraft.startAutoSave(vm.draftKey, vm.editorWrapper, {
            staffId: u && u.staffId != null ? HIS.id(u.staffId) : null,
            patientName: vm.patientLabel,
            recordType: '门诊病历'
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
        if (!HIS.EmrOfflineDraft || !vm.visitId || !vm.ready()) { return; }
        var key = 'outp:' + HIS.idKey(vm.visitId);
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
              vm.scheduleNlg();
              toast('success', '离线草稿已恢复');
            } catch (e) {
              toast('error', '草稿内容解析失败');
            }
          }).catch(function () {
            HIS.EmrOfflineDraft.deleteDraft(key);
          });
        }).catch(function () { /* 草稿库不可用静默 */ });
      },
      /* 保存成功后清空本地草稿并重启自动保存(重置基线) */
      resetDraftAfterSave: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.draftKey) { return; }
        var key = vm.draftKey;
        HIS.EmrOfflineDraft.stopAutoSave(key);
        HIS.EmrOfflineDraft.deleteDraft(key).then(function () {
          if (vm.draftKey === key) { vm.draftSavedText = '已同步 ' + fmtHM(Date.now()); vm.startDraft(); }
        }).catch(function () { /* noop */ });
      },

      /* ================= NLG 自然语言预览(底部条, 防抖 1s) ================= */
      scheduleNlg: function () {
        var vm = this;
        if (vm._nlgTimer) { clearTimeout(vm._nlgTimer); }
        vm._nlgTimer = setTimeout(function () {
          vm._nlgTimer = null;
          vm.generateNlg();
        }, 1000);
      },
      generateNlg: function () {
        var vm = this;
        if (!vm.ready() || !HIS.post) { return; }
        var json = vm.currentDocJSON();
        if (!json) { return; }
        var mySeq = (vm._nlgSeq = (vm._nlgSeq || 0) + 1);
        vm.nlgLoading = true;
        vm.nlgFailed = false;
        HIS.post('/api/his/emr/nlg/generate-document', {
          documentJson: JSON.stringify(json),    /* 传 JSON 串: 后端 String.valueOf + fastjson 解析 */
          recordType: RECORD_TYPE_OUTP
        }).then(function (m) {
          if (mySeq !== vm._nlgSeq) { return; }
          var titleOf = vm.sectionTitleMap();
          vm.nlgSections = Object.keys(m || {}).map(function (k) {
            return { key: k, title: titleOf[k] || k, text: m[k] == null ? '' : String(m[k]) };
          }).filter(function (s) { return s.text !== ''; });
          vm.nlgLoading = false;
        }).catch(function () {
          if (mySeq !== vm._nlgSeq) { return; }
          vm.nlgSections = [];
          vm.nlgFailed = true;
          vm.nlgLoading = false;
        });
      },
      /* 章节键 → 标题映射(NLG 分组名与文档章节对齐) */
      sectionTitleMap: function () {
        var w = this.editorWrapper;
        var ed = w && w.editor;
        var map = {};
        if (ed && ed.state) {
          ed.state.doc.descendants(function (n) {
            if (n.type.name === 'emrSection' && n.attrs.sectionKey) {
              map[n.attrs.sectionKey] = n.attrs.title || SOAP_LABELS[n.attrs.sectionKey] || n.attrs.sectionKey;
            }
          });
        }
        Object.keys(SOAP_LABELS).forEach(function (k) { if (!map[k]) { map[k] = SOAP_LABELS[k]; } });
        return map;
      },
      /* NLG 段落回填章节(生成文本按 sectionKey 落回对应章节尾部, 供医生取用) */
      insertNlgSection: function (s) {
        if (!s || !s.key || !s.text) { return; }
        if (this.checkAppendable(s.title || '章节')) { return; }
        if (this.appendSectionText(s.key, s.text)) { toast('success', '已插入「' + (s.title || s.key) + '」生成文本'); }
        else { toast('warning', '当前模板无「' + (s.title || s.key) + '」章节, 无法插入'); }
      },
      toggleNlg: function () { this.nlgCollapsed = !this.nlgCollapsed; },

      /* ================= 完整性质控 ================= */
      evaluateQuality: function (silent) {
        var vm = this;
        var vid = vm.visitId;
        if (!vid || !HIS.post) { return; }
        vm.qualityLoading = true;
        HIS.post('/api/his/outp/emr/quality?visitId=' + HIS.idParam(vid), {})
          .then(function (d) {
            vm.quality = d || null;
            if (!silent && d) {
              var miss = (d.missing || []).map(function (m) { return m.label; }).join('、');
              toast('info', '完整性 ' + d.score + ' 分（必填 ' + d.filledRequired + '/' + d.totalRequired + '）' + (miss ? '，缺失：' + miss : ''));
            }
          })
          .catch(function () { if (!silent) { toast('warning', '质控评分失败(病历可能尚未保存)'); } })
          .finally(function () { vm.qualityLoading = false; });
      },

      /* ================= 打印 ================= */
      printRecord: function () {
        var vm = this;
        if (!vm.ready()) { toast('warning', '请先选择模板并书写内容后再打印'); return; }
        var title = '门诊病历' + (vm.patientLabel ? ' - ' + vm.patientLabel : '');
        if (typeof vm.editorWrapper.print === 'function') {
          try { vm.editorWrapper.print({ title: title }); return; } catch (e) { /* 回退提示 */ }
        }
        toast('warning', '打印组件不可用');
      },

      /* ================= Tab1: 常用语 ================= */
      loadPhrases: function () {
        var vm = this;
        if (!HIS.get) { return; }
        vm.phraseLoading = true;
        var q = '/api/his/emr/phrase/list?category=' + encodeURIComponent(vm.phraseCategory || '');
        if (vm.deptCode) { q += '&deptCode=' + encodeURIComponent(vm.deptCode); }
        HIS.get(q)
          .then(function (list) { vm.phrases = Array.isArray(list) ? list : []; })
          .catch(function () { vm.phrases = []; })
          .finally(function () { vm.phraseLoading = false; });
      },
      /* 点击常用语: 插入光标处 + 使用计数 fire-and-forget */
      usePhrase: function (p) {
        var vm = this;
        if (!p || !p.content) { return; }
        if (vm.insertAtCursor(p.content)) {
          if (p.id != null && HIS.post) {
            HIS.post('/api/his/emr/phrase/use/' + HIS.idParam(p.id)).catch(function () { /* 计数失败忽略 */ });
          }
        }
      },
      phraseCategoryLabel: function (v) {
        var hit = null;
        PHRASE_CATEGORIES.forEach(function (c) { if (c.value === v) { hit = c; } });
        return hit ? hit.label : (v || '通用');
      },

      /* ================= Tab3: 检查报告 ================= */
      loadReports: function () {
        var vm = this;
        vm.labReports = []; vm.examReports = []; vm.reportFailed = false;
        if (!vm.patientId || !HIS.get) { return; }
        vm.reportLoading = true;
        var base = '/api/medtech/reports/patient/' + HIS.idParam(vm.patientId);
        global.Promise.all([
          HIS.get(base + '?reportType=lab').catch(function () { return null; }),
          HIS.get(base + '?reportType=exam').catch(function () { return null; })
        ]).then(function (rs) {
          if (!rs || (rs[0] == null && rs[1] == null)) {
            vm.reportFailed = true;              /* 报告服务不可用: 优雅降级为空态 */
            return;
          }
          var pub = function (list) {
            return (Array.isArray(list) ? list : []).filter(function (r) { return Number(r.status) === 2; }).slice(0, 30);
          };
          vm.labReports = pub(rs[0]);
          vm.examReports = pub(rs[1]);
        }).finally(function () {
          vm.reportLoading = false;
          vm.reportLoaded = true;
        });
      },
      isReportAbnormal: function (r) {
        return !!(r && (Number(r.critical_flag) > 0 || Number(r.abnormal_flag) > 0));
      },
      reportTimeOf: function (r) { return fmtDT(r && (r.report_time || r.reportTime)); },
      reportConclusionOf: function (r) {
        if (!r) { return ''; }
        var direct = String(r.conclusion || r.examConclusion || r.resultSummary || '').trim();
        if (direct) { return direct; }
        var findings = String(r.examFindings || '').trim();
        var items = Array.isArray(r.resultItems) ? r.resultItems : [];
        var joined = items.map(function (it) {
          var v = it.resultValue != null ? it.resultValue : (it.value != null ? it.value : '');
          return v === '' ? '' : (it.itemName + ' ' + v + (it.resultUnit || ''));
        }).filter(Boolean).join('；');
        return [findings, joined].filter(Boolean).join(' | ');
      },
      insertReportSummary: function (r) {
        if (!r) { return; }
        var type = r.reportType === 'lab' ? '检验' : '检查';
        var name = r.itemNames || r.orderName || r.reportNo || r.report_no || '报告';
        var lines = ['【' + type + '】' + name + '（' + this.reportTimeOf(r) + '）'];
        if (this.isReportAbnormal(r)) { lines.push('（存在异常标记）'); }
        var c = this.reportConclusionOf(r);
        if (c) { lines.push(c); }
        this.appendAuxExam(lines.join('\n'));
      },

      /* ================= Tab4: CDSS ================= */
      evaluateCdssTab: function () {
        var vm = this;
        if (!vm.ready()) {
          if (vm.hasVisit) { toast('warning', '请先选择病历模板'); }
          return;
        }
        vm.cdssLoading = true;
        vm.cdssFailed = false;
        HIS.post('/api/his/emr/cdss/evaluate-document', {
          documentJson: JSON.stringify(vm.currentDocJSON()),   /* JSON 串: 后端 String.valueOf+fastjson 解析 */
          recordType: RECORD_TYPE_OUTP,
          deptCode: vm.deptCode || ''
        }).then(function (list) {
          vm.cdssAlerts = Array.isArray(list) ? list : [];
          vm.cdssEvaluated = true;
        }).catch(function () {
          vm.cdssAlerts = [];
          vm.cdssFailed = true;
          vm.cdssEvaluated = true;
        }).finally(function () { vm.cdssLoading = false; });
      },
      cdssLevelOf: function (a) {
        var lv = a && a.level;
        return (lv === 'block' || lv === 'warning' || lv === 'info') ? lv : 'info';
      },
      cdssLevelText: function (lv) {
        return lv === 'block' ? '拦截' : (lv === 'warning' ? '警告' : '信息');
      },

      /* ================= Tab5: 版本 ================= */
      loadVersions: function () {
        var vm = this;
        if (!vm.visitId || !HIS.get) { vm.versions = []; vm.versionSel = []; return; }
        vm.versionLoading = true;
        HIS.get('/api/his/outp/emr/versions?visitId=' + HIS.idParam(vm.visitId))
          .then(function (list) { vm.versions = Array.isArray(list) ? list : []; })
          .catch(function () { vm.versions = []; })
          .finally(function () { vm.versionLoading = false; });
      },
      operateTypeText: function (t) { return OPERATE_TYPES[t] || (t || '-'); },
      /* 勾选版本(最多2个, 超出替换最早) */
      toggleVersion: function (id) {
        var vm = this;
        var idx = vm.versionSel.findIndex(function (x) { return HIS.sameId(x, id); });
        if (idx >= 0) { vm.versionSel.splice(idx, 1); return; }
        if (vm.versionSel.length >= 2) { vm.versionSel.shift(); }
        vm.versionSel.push(id);
      },
      isVersionSelected: function (id) {
        return (this.versionSel || []).some(function (x) { return HIS.sameId(x, id); });
      },
      versionOf: function (id) {
        return (this.versions || []).filter(function (v) { return HIS.sameId(v.id, id); })[0] || null;
      },
      /* 双版本对比(版本号小者为基线) */
      diffSelected: function () {
        var vm = this;
        if (vm.versionSel.length !== 2) { return; }
        var v1 = vm.versionSel[0], v2 = vm.versionSel[1];
        var a = vm.versionOf(v1), b = vm.versionOf(v2);
        if (a && b && Number(a.versionNo) > Number(b.versionNo)) { v1 = vm.versionSel[1]; v2 = vm.versionSel[0]; }
        vm.diffData = null;
        vm.diffVisible = true;
        vm.diffLoading = true;
        HIS.get('/api/his/outp/emr/version-diff?v1=' + HIS.idParam(v1) + '&v2=' + HIS.idParam(v2))
          .then(function (d) { vm.diffData = d || null; })
          .catch(HIS.notifyError)
          .finally(function () { vm.diffLoading = false; });
      },
      diffLineCls: function (d, side) {
        if (!d) { return ''; }
        var text = side === 'v1' ? d.v1Text : d.v2Text;
        if (text == null) { return 'is-empty'; }
        if (d.type === 'add') { return 'is-add'; }
        if (d.type === 'del') { return 'is-del'; }
        if (d.type === 'modify') { return 'is-modify'; }
        return '';
      },
      /* 单版本快照预览 */
      previewVersion: function (v) {
        var vm = this;
        if (!v) { return; }
        vm.versionVisible = true;
        vm.versionPreviewLoading = true;
        vm.versionPreviewTitle = 'V' + v.versionNo + ' · ' + (v.operatorName || '-') + ' · ' + fmtDT(v.operateTime);
        vm.versionPreview = '';
        HIS.get('/api/his/outp/emr/version/' + HIS.idParam(v.id))
          .then(function (d) {
            var snap = (d && (d.contentSnapshot || d.structureSnapshot)) || '';
            vm.versionPreview = plainTextOf(snap);
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.versionPreviewLoading = false; });
      },

      /* ================= 头栏动作 ================= */
      togglePanel: function () { this.panelVisible = !this.panelVisible; }
    },

    /* ================= 模板(字符串数组拼接, 无模板字符串) ================= */
    template: [
      '<div class="oew-root">',
      /* ---- 头栏 ---- */
      '  <div class="oew-header">',
      '    <span class="oew-title"><span class="oew-icon" v-html="icons.file"></span> 门诊病历</span>',
      '    <el-select v-model="selectedTemplateId" class="oew-tpl-select" size="small" filterable clearable placeholder="选择病历模板" :disabled="readOnly" :loading="templatesLoading" @change="onTemplateChange">',
      '      <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id"></el-option>',
      '    </el-select>',
      '    <div class="oew-meta" v-if="patientLabel">',
      '      <span class="oew-meta-name">{{ patientLabel }}</span>',
      '      <span v-if="visit && visit.visitNo">{{ visit.visitNo }}</span>',
      '      <span v-if="visit && visit.deptName">{{ visit.deptName }}</span>',
      '      <span v-if="visit && visit.visitTime">{{ fmtDT(visit.visitTime) }}</span>',
      '    </div>',
      '    <div class="oew-actions">',
      '      <el-button size="small" :disabled="!hasVisit" :loading="qualityLoading" @click="evaluateQuality(false)">质控{{ quality && quality.score != null ? \' \' + quality.score + \'分\' : \'\' }}</el-button>',
      '      <el-button size="small" :disabled="!canEdit || saving" :loading="saving" @click="emitSave(false)"><span class="oew-icon" v-html="icons.save"></span> 暂存(F3)</el-button>',
      '      <el-button size="small" type="primary" :disabled="!canEdit || saving" @click="emitSave(true)"><span class="oew-icon" v-html="icons.send"></span> 提交病历</el-button>',
      '      <el-button size="small" @click="printRecord"><span class="oew-icon" v-html="icons.printer"></span> 打印</el-button>',
      '      <el-button size="small" text @click="togglePanel" :title="panelVisible ? \'收起助手\' : \'展开助手\'"><span class="oew-icon" v-html="icons.panel"></span></el-button>',
      '    </div>',
      '  </div>',
      /* ---- 主体双栏 ---- */
      '  <div class="oew-body">',
      '    <div class="oew-center">',
      /* 无就诊空态 */
      '      <div v-if="!hasVisit" class="oew-empty-root">',
      '        <span v-html="icons.user"></span>',
      '        <div class="oew-empty-root-text">请先从左侧队列选择患者</div>',
      '      </div>',
      '      <template v-else>',
      /* 历史纯文本病历只读回显 */
      '        <div v-if="legacyText" class="oew-legacy-block">',
      '          <div class="oew-legacy-banner"><span class="oew-icon" v-html="icons.alert"></span> 该病历为 Tiptap 升级前的历史文本病历，仅作只读展示；如需富文本书写请在接诊中选择模板新建。</div>',
      '          <div class="oew-legacy-row" v-for="row in legacySoap" :key="row.label">',
      '            <span class="lb">{{ row.label }}</span><span class="vl">{{ row.value }}</span>',
      '          </div>',
      '        </div>',
      '        <template v-else>',
      /* 未选模板 */
      '          <div v-if="!selectedTemplateId" class="oew-empty-inner">',
      '            <span v-html="icons.file2"></span>',
      '            <div class="oew-empty-root-text">请选择门诊病历模板开始书写</div>',
      '          </div>',
      /* 模板无 document → 纯文本降级书写 */
      '          <div v-else-if="templateNoDoc" class="oew-nodoc">',
      '            <el-alert type="warning" :closable="false" show-icon title="该模板暂不支持富文本编辑" description="当前模板未配置 Tiptap 文档，已降级为纯文本书写；内容将随病历一并保存。"></el-alert>',
      '            <el-input v-model="plainFallback" type="textarea" :rows="10" :disabled="readOnly" placeholder="以纯文本书写病历内容…"></el-input>',
      '          </div>',
      /* Tiptap 编辑器 */
      '          <div v-else class="oew-editor-zone">',
      '            <div ref="toolbarHost" class="oew-toolbar-host" v-loading="editorLoading" element-loading-text="加载编辑器…"></div>',
      '            <div ref="editorHost" class="oew-editor-host"></div>',
      '            <div class="oew-statusbar">',
      '              <span class="oew-statusbar-title">{{ selectedTemplate ? selectedTemplate.name : "门诊病历" }}</span>',
      '              <el-tag v-if="readOnly" size="small" type="info" disable-transitions>只读模式</el-tag>',
      '              <template v-else>',
      '                <span :class="dirty ? \'oew-dirty\' : \'\'">{{ dirty ? "● 有未保存修改 (Ctrl+S)" : "○ 已同步" }}</span>',
      '              </template>',
      '              <span v-if="draftSavedText" class="oew-draft-chip">离线草稿 {{ draftSavedText }}</span>',
      '            </div>',
      '          </div>',
      '        </template>',
      '      </template>',
      '    </div>',
      /* ---- 右栏: 助手面板 ---- */
      '    <div class="oew-right" :class="{ \'is-collapsed\': !panelVisible }">',
      '      <el-tabs v-model="activeTab">',
      /* Tab1 常用语 */
      '        <el-tab-pane name="phrase">',
      '          <template #label><span class="oew-icon" v-html="icons.message"></span> 常用语</template>',
      '          <div class="oew-tab-body">',
      '            <div class="oew-tab-tool">',
      '              <el-select v-model="phraseCategory" size="small" style="width:104px" @change="loadPhrases">',
      '                <el-option v-for="c in phraseCategories" :key="c.value" :label="c.label" :value="c.value"></el-option>',
      '              </el-select>',
      '              <el-input v-model="phraseKeyword" size="small" clearable placeholder="筛选" style="flex:1">',
      '                <template #prefix><span class="oew-icon" v-html="icons.search"></span></template>',
      '              </el-input>',
      '              <el-button size="small" text @click="loadPhrases"><span class="oew-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="phraseLoading" style="min-height:60px">',
      '              <div v-if="!filteredPhrases.length && !phraseLoading" class="oew-tab-empty">暂无常用语</div>',
      '              <div v-for="p in filteredPhrases" :key="p.id" class="oew-phrase" @click="usePhrase(p)" title="点击插入光标处">',
      '                <div class="oew-phrase-text">{{ p.content }}</div>',
      '                <div class="oew-phrase-foot">',
      '                  <span>{{ phraseCategoryLabel(p.category) }}</span>',
      '                  <span class="oew-phrase-use">已用 {{ p.usageCount || 0 }} 次</span>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab2 历史病历 */
      '        <el-tab-pane name="history">',
      '          <template #label><span class="oew-icon" v-html="icons.history"></span> 历史病历</template>',
      '          <div class="oew-tab-body">',
      '            <div v-if="!histories.length" class="oew-tab-empty">暂无历史就诊记录</div>',
      '            <div v-for="h in histories" :key="h.id" class="oew-his-card">',
      '              <div class="oew-his-title">{{ h.deptName || "-" }} · {{ h.mainDiagName || h.diagnosisName || "未记录诊断" }}</div>',
      '              <div class="oew-his-meta">',
      '                <span>{{ fmtDT(h.visitTime || h.workDate) }}</span>',
      '                <span v-if="h.doctorName">{{ h.doctorName }}</span>',
      '              </div>',
      '              <div v-if="h.chiefComplaint" class="oew-his-line">{{ h.chiefComplaint }}</div>',
      '              <div>',
      '                <el-button size="small" text type="primary" :disabled="readOnly" @click="quoteHistory(h)">引用本次记录</el-button>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab3 检查报告 */
      '        <el-tab-pane name="report">',
      '          <template #label><span class="oew-icon" v-html="icons.clipboard"></span> 报告</template>',
      '          <div class="oew-tab-body">',
      '            <div class="oew-tab-tool">',
      '              <el-radio-group v-model="reportTab" size="small">',
      '                <el-radio-button label="lab">检验</el-radio-button>',
      '                <el-radio-button label="exam">检查</el-radio-button>',
      '              </el-radio-group>',
      '              <el-button size="small" text @click="loadReports"><span class="oew-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="reportLoading" style="min-height:60px">',
      '              <div v-if="!hasVisit" class="oew-tab-empty">请先选择患者</div>',
      '              <div v-else-if="reportFailed" class="oew-tab-empty">报告服务暂不可用</div>',
      '              <div v-else-if="reportTab === \'lab\' && !labReports.length && !reportLoading" class="oew-tab-empty">暂无检验报告</div>',
      '              <div v-else-if="reportTab === \'exam\' && !examReports.length && !reportLoading" class="oew-tab-empty">暂无检查报告</div>',
      '              <template v-if="reportTab === \'lab\'">',
      '                <div v-for="r in labReports" :key="r.id" class="oew-rep-card" :class="{ \'is-abnormal\': isReportAbnormal(r) }">',
      '                  <div class="oew-rep-head"><span class="oew-rep-name">{{ r.itemNames || r.orderName || r.reportNo || "检验报告" }}</span><el-tag v-if="isReportAbnormal(r)" size="small" type="danger" disable-transitions>异常</el-tag></div>',
      '                  <div class="oew-rep-meta">{{ reportTimeOf(r) }}</div>',
      '                  <div v-if="reportConclusionOf(r)" class="oew-rep-text">{{ reportConclusionOf(r) }}</div>',
      '                  <div><el-button size="small" text type="primary" :disabled="readOnly" @click="insertReportSummary(r)">插入辅助检查</el-button></div>',
      '                </div>',
      '              </template>',
      '              <template v-else>',
      '                <div v-for="r in examReports" :key="r.id" class="oew-rep-card" :class="{ \'is-abnormal\': isReportAbnormal(r) }">',
      '                  <div class="oew-rep-head"><span class="oew-rep-name">{{ r.itemNames || r.orderName || r.reportNo || "检查报告" }}</span><el-tag v-if="isReportAbnormal(r)" size="small" type="danger" disable-transitions>异常</el-tag></div>',
      '                  <div class="oew-rep-meta">{{ reportTimeOf(r) }}</div>',
      '                  <div v-if="reportConclusionOf(r)" class="oew-rep-text">{{ reportConclusionOf(r) }}</div>',
      '                  <div><el-button size="small" text type="primary" :disabled="readOnly" @click="insertReportSummary(r)">插入辅助检查</el-button></div>',
      '                </div>',
      '              </template>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab4 CDSS */
      '        <el-tab-pane name="cdss">',
      '          <template #label><span class="oew-icon" v-html="icons.activity"></span> CDSS</template>',
      '          <div class="oew-tab-body">',
      '            <div class="oew-tab-tool">',
      '              <div class="oew-cdss-summary">',
      '                <span class="oew-cdss-chip is-block">拦截 {{ cdssCounts.block }}</span>',
      '                <span class="oew-cdss-chip is-warning">警告 {{ cdssCounts.warning }}</span>',
      '                <span class="oew-cdss-chip is-info">信息 {{ cdssCounts.info }}</span>',
      '              </div>',
      '              <el-button size="small" text style="margin-left:auto" @click="evaluateCdssTab"><span class="oew-icon" v-html="icons.refresh"></span> 评估</el-button>',
      '            </div>',
      '            <div v-loading="cdssLoading" style="min-height:60px">',
      '              <div v-if="cdssFailed" class="oew-tab-empty">临床提醒服务不可用</div>',
      '              <div v-else-if="!cdssAlerts.length && !cdssLoading" class="oew-tab-empty">{{ cdssEvaluated ? "未发现需关注的临床提醒" : "点击评估检查当前病历" }}</div>',
      '              <div v-for="(a, i) in cdssAlerts" :key="(a && a.ruleId) || i" class="oew-cdss-alert" :class="\'is-\' + cdssLevelOf(a)">',
      '                <div class="oew-cdss-name">{{ cdssLevelOf(a) === \'block\' ? \'🔴\' : (cdssLevelOf(a) === \'warning\' ? \'🟡\' : \'🔵\') }} {{ a.ruleName || a.ruleCode || "临床提醒" }}<span class="oew-cdss-level">{{ cdssLevelText(cdssLevelOf(a)) }}</span></div>',
      '                <div v-if="a.message" class="oew-cdss-msg">{{ a.message }}</div>',
      '                <div v-if="a.knowledgeSource" class="oew-cdss-msg">知识来源：{{ a.knowledgeSource }}</div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      /* Tab5 版本 */
      '        <el-tab-pane name="version">',
      '          <template #label><span class="oew-icon" v-html="icons.layers"></span> 版本</template>',
      '          <div class="oew-tab-body">',
      '            <div class="oew-tab-tool">',
      '              <span style="font-size:12px;color:var(--yb-ink-3,#5a6a7e)">勾选 2 个版本可对比</span>',
      '              <el-button size="small" text style="margin-left:auto" @click="loadVersions"><span class="oew-icon" v-html="icons.refresh"></span></el-button>',
      '            </div>',
      '            <div v-loading="versionLoading" style="min-height:60px">',
      '              <div v-if="!versions.length && !versionLoading" class="oew-tab-empty">暂无版本记录(保存/提交后生成)</div>',
      '              <div v-for="v in versions" :key="v.id" class="oew-ver-card" :class="{ \'is-sel\': isVersionSelected(v.id) }" @click="toggleVersion(v.id)">',
      '                <div class="oew-ver-main">',
      '                  <div><span class="oew-ver-no">V{{ v.versionNo }}</span> <el-tag size="small" disable-transitions>{{ operateTypeText(v.operateType) }}</el-tag></div>',
      '                  <div class="oew-ver-meta">{{ v.operatorName || "-" }} · {{ fmtDT(v.operateTime) }}</div>',
      '                </div>',
      '                <div class="oew-ver-ops" @click.stop>',
      '                  <el-button size="small" text type="primary" @click="previewVersion(v)">详情</el-button>',
      '                </div>',
      '              </div>',
      '              <el-button v-if="versionSel.length === 2" size="small" type="primary" plain style="width:100%" @click="diffSelected">对比所选版本</el-button>',
      '            </div>',
      '          </div>',
      '        </el-tab-pane>',
      '      </el-tabs>',
      '    </div>',
      '  </div>',
      /* ---- 底部 NLG 自然语言预览条 ---- */
      '  <div class="oew-nlg" v-if="hasVisit && !legacyText && selectedTemplateId && !templateNoDoc">',
      '    <div class="oew-nlg-head" @click="toggleNlg">',
      '      <span class="oew-icon" v-html="icons.activity"></span> 自然语言预览',
      '      <span class="oew-nlg-tip">字段变更 1 秒后自动生成</span>',
      '      <div class="oew-nlg-ops" @click.stop>',
      '        <el-button size="small" text :loading="nlgLoading" @click="generateNlg"><span v-if="!nlgLoading" class="oew-icon" v-html="icons.refresh"></span></el-button>',
      '        <el-button size="small" text @click="toggleNlg">{{ nlgCollapsed ? "展开" : "收起" }}</el-button>',
      '      </div>',
      '    </div>',
      '    <div v-show="!nlgCollapsed" class="oew-nlg-body">',
      '      <div v-if="nlgFailed" class="oew-tab-empty">自然语言服务暂不可用</div>',
      '      <div v-else-if="!nlgSections.length && !nlgLoading" class="oew-tab-empty">暂无生成内容(填写数据元后自动生成)</div>',
      '      <div v-for="s in nlgSections" :key="s.key" class="oew-nlg-sec">',
      '        <span class="oew-nlg-sec-title">【{{ s.title }}】</span>',
      '        <span class="oew-nlg-sec-text">{{ s.text }}</span>',
      '        <el-button v-if="!readOnly && hasDocKey(s.key)" size="small" text type="primary" @click="insertNlgSection(s)">插入</el-button>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ---- 版本对比对话框 ---- */
      '  <el-dialog v-model="diffVisible" title="版本对比" width="860px" append-to-body>',
      '    <div v-loading="diffLoading" style="min-height:120px">',
      '      <div v-if="diffData && diffData.summary" class="oew-diff-chips">',
      '        <span>新增 {{ diffData.summary.add || 0 }} 行</span>',
      '        <span>删除 {{ diffData.summary.del || 0 }} 行</span>',
      '        <span>修改 {{ diffData.summary.modify || 0 }} 行</span>',
      '        <span>相同 {{ diffData.summary.same || 0 }} 行</span>',
      '      </div>',
      '      <div v-if="diffData && diffData.diffs && diffData.diffs.length" style="max-height:56vh;overflow:auto">',
      '        <table class="oew-diff-table">',
      '          <thead><tr><th style="width:44px">行</th><th>基线版本</th><th>对比版本</th></tr></thead>',
      '          <tbody>',
      '            <tr v-for="(d, i) in diffData.diffs" :key="i">',
      '              <td>{{ d.line }}</td>',
      '              <td :class="diffLineCls(d, \'v1\')">{{ d.v1Text == null ? "(空)" : d.v1Text }}</td>',
      '              <td :class="diffLineCls(d, \'v2\')">{{ d.v2Text == null ? "(空)" : d.v2Text }}</td>',
      '            </tr>',
      '          </tbody>',
      '        </table>',
      '      </div>',
      '      <div v-else-if="!diffLoading" class="oew-tab-empty">两版本内容一致或暂无差异</div>',
      '    </div>',
      '    <template #footer><el-button size="small" @click="diffVisible = false">关闭</el-button></template>',
      '  </el-dialog>',
      /* ---- 版本详情预览对话框 ---- */
      '  <el-dialog v-model="versionVisible" :title="versionPreviewTitle" width="640px" append-to-body>',
      '    <div class="oew-ver-preview" v-loading="versionPreviewLoading">{{ versionPreview }}</div>',
      '    <template #footer><el-button size="small" @click="versionVisible = false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.OutpEmrWriter = OutpEmrWriter;
  /* 全局标签注册: app 就绪后挂 <dw-outp-emr-writer>(脚本先于 app.js 加载, 故延时至 DOMContentLoaded;
   * P3-4 集成后 doctor.js 亦可经 HIS.components 局部声明, 局部声明优先于全局标签) */
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-outp-emr-writer', OutpEmrWriter); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
