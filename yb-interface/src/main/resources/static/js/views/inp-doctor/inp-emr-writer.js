/* ==================================================================
 * inp-emr-writer.js — 住院病历书写器(病历P2-4, Tiptap 三栏工作台)
 * ------------------------------------------------------------------
 * 定位: 替代表单式 inp-record-panel 的生产级病历书写组件。
 * 依赖(均由 index.html 先行加载, 本文件无构建、无 ES module):
 *   - HIS.request(api.js): HIS.get/post/put/del + notifyError/notifySuccess
 *   - HIS.id/idKey/sameId/idParam: 19位雪花ID全链路字符串化治理
 *   - HIS.EmrEditor(emr-editor.js): createEditor/wrapper/工具栏/NLG/CDSS
 *   - HIS.EmrOfflineDraft(emr-offline-draft.js): IndexedDB 离线草稿
 *   - HIS.SignaturePad(signature-pad.js): SM2 手写签名板(可选)
 * 后端契约:
 *   - GET  /api/his/inp/record/grouped?inpVisitId=   分组列表(P2)
 *   - GET  /api/his/inp/record/{id}                  详情(content 格式探测解密)
 *   - POST /api/his/inp/record/from-template         按模板建档 {visitId,templateId}
 *   - POST /api/his/inp/record                       空白建档 {inpVisitId,recordType,title}
 *   - PUT  /api/his/inp/record/{id}                  保存(草稿态, 双轨 content+structureData)
 *   - PUT  /api/his/inp/record/{id}/submit           提交
 *   - DELETE /api/his/inp/record/{id}                删除(草稿态)
 *   - GET/POST 版本: /{id}/versions, /version/{vid}, /version/diff?v1=&v2=
 *   - GET  /api/his/emr/template/list                模板列表(templateCategory 与类型1-8对应)
 *   - GET/POST 常用语: /api/his/emr/phrase/list, /api/his/emr/phrase/use/{id}
 *   - GET/POST 签名: /api/emr/sign/chain/{recordType}, /api/emr/sign/status/1/{id},
 *                    /api/emr/sign/1/{id}/rule?stage=
 *   - P8b-2 多方式签名: POST /api/emr/ca-sign/sign-image|sign-ca(手写/CA, 文字签名走原规则链),
 *                    POST /api/emr/ca-sign/verify/{signatureId}(单条验签),
 *                    POST /api/emr/ca-sign/patient-sign/{signatureId}(患者/家属签名留存),
 *                    GET  /api/emr/ca-sign/sign-info/{recordId}(签名记录: 方式/验签标签)
 *   - P5a-4 签名质控: 签名响应附带 qcResult{passed,warnings[],blocks[],forbidden[]},
 *                    禁止/拦截级阻断签名(弹整改抽屉), 提醒级放行(通知+质控页签展示)
 *   - P5b-4 质控页签: GET  /api/his/emr/quality/defects/{recordId}  缺陷记录(整改状态闭环),
 *                    GET /api/his/emr/timeliness/check/{recordId}   时效检查(倒计时展示);
 *                    SSE 订阅 EMR_QC_DEADLINE_WARN/EMR_QC_OVERDUE/EMR_QC_NOTICE/EMR_QC_APPEAL_RESULT
 *                    (HIS.EmrSseClient, recordId 匹配当前病历才刷新页签, 组件卸载退订)
 *   - POST /api/his/emr/cdss/evaluate-document      CDSS 文档级评估
 *   - GET  /api/his/emr/timeline/patient/{pid}      患者全景时间线(可选, 失败降级)
 *   - GET  /api/medtech/reports/patient/{pid}       检查检验报告(可选, 失败降级)
 * 注册: HIS.components.InpEmrWriter(Task#29 由 inp-doctor.js 集成)。
 * ==================================================================
 */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 iew- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('inp-emr-writer-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-emr-writer-style';
    st.textContent = [
      /* ---- 根与头部 ---- */
      '.iew-root { display:flex; flex-direction:column; height:100%; min-height:0; background:var(--yb-canvas,#f2f4f8); }',
      '.iew-header { flex:none; display:flex; align-items:center; gap:12px; padding:8px 14px; background:var(--yb-surface,#fff); border-bottom:1px solid var(--yb-border,#dfe4eb); }',
      '.iew-patient { display:flex; align-items:baseline; gap:10px; min-width:0; overflow:hidden; }',
      '.iew-patient-name { font-size:16px; font-weight:700; color:var(--yb-ink-1,#1c2430); white-space:nowrap; }',
      '.iew-patient-meta { font-size:12px; color:var(--yb-ink-3,#5a6a7e); display:flex; gap:10px; white-space:nowrap; overflow:hidden; }',
      '.iew-header-actions { margin-left:auto; display:flex; align-items:center; gap:8px; flex:none; }',
      '.iew-header-actions .el-button + .el-button { margin-left:0; }',
      '.iew-icon { display:inline-flex; width:14px; height:14px; vertical-align:-2px; }',
      '.iew-icon svg { width:100%; height:100%; }',
      /* ---- 主体三栏 ---- */
      '.iew-body { flex:1; display:flex; min-height:0; }',
      /* ---- 左栏: 病历文件夹 ---- */
      '.iew-left { width:240px; flex:none; display:flex; flex-direction:column; min-height:0; background:var(--yb-surface,#fff); border-right:1px solid var(--yb-border,#dfe4eb); }',
      '.iew-left-head { flex:none; padding:8px; border-bottom:1px solid var(--yb-border-light,#ebeff4); display:flex; flex-direction:column; gap:6px; }',
      '.iew-new-btn { width:100%; }',
      '.iew-groups { flex:1; overflow-y:auto; padding:2px 0 8px; }',
      '.iew-group-head { display:flex; align-items:center; gap:4px; padding:7px 10px 5px; font-size:12px; font-weight:600; color:var(--yb-ink-3,#5a6a7e); cursor:pointer; user-select:none; }',
      '.iew-group-head:hover { color:var(--yb-brand,#1a5c9e); }',
      '.iew-group-arrow { display:inline-flex; width:12px; height:12px; transition:transform .15s ease; }',
      '.iew-group-arrow svg { width:100%; height:100%; }',
      '.iew-group-head.is-open .iew-group-arrow { transform:rotate(90deg); }',
      '.iew-group-count { margin-left:auto; font-size:11px; font-weight:400; color:var(--yb-ink-4,#8994a5); background:var(--yb-surface-2,#f7f9fc); border-radius:8px; padding:0 7px; line-height:16px; }',
      '.iew-rec { padding:6px 10px 6px 24px; cursor:pointer; border-left:2px solid transparent; }',
      '.iew-rec:hover { background:var(--yb-surface-2,#f7f9fc); }',
      '.iew-rec.is-active { background:#eaf3fb; border-left-color:var(--yb-brand,#1a5c9e); }',
      '.iew-rec-title { font-size:13px; color:var(--yb-ink-1,#1c2430); line-height:1.45; word-break:break-all; display:flex; align-items:center; gap:5px; }',
      '.iew-rec-title .el-tag { flex:none; transform:scale(.9); }',
      '.iew-rec-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); margin-top:2px; display:flex; gap:6px; align-items:center; flex-wrap:wrap; }',
      '.iew-rec-dl { color:var(--yb-danger,#c74f4f); }',
      '.iew-rec-dl.is-warn { color:var(--yb-warning,#a26b1b); }',
      '.iew-list-empty { padding:18px 10px; font-size:12px; color:var(--yb-ink-4,#8994a5); text-align:center; }',
      /* ---- 中栏: 编辑器 ---- */
      '.iew-center { flex:1; display:flex; flex-direction:column; min-width:0; min-height:0; padding:10px; gap:8px; }',
      '.iew-editor-zone { flex:1; display:flex; flex-direction:column; min-height:0; }',
      '.iew-toolbar-host { flex:none; }',
      '.iew-editor-host { flex:1; min-height:0; overflow-y:auto; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-top:none; border-radius:0 0 6px 6px; }',
      '.iew-editor-host .emr-doc .ProseMirror { min-height:100%; }',
      '.iew-statusbar { flex:none; display:flex; align-items:center; gap:12px; padding:5px 12px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; flex-wrap:wrap; }',
      '.iew-statusbar-title { color:var(--yb-ink-1,#1c2430); font-weight:600; max-width:40%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.iew-dirty { color:var(--yb-warning,#a26b1b); }',
      '.iew-draft-chip { color:var(--yb-success,#3c862d); }',
      '.iew-legacy { flex:none; display:flex; align-items:center; gap:6px; padding:6px 12px; font-size:12px; color:var(--yb-warning,#a26b1b); background:var(--yb-warning-bg,#fbf3e6); border:1px solid #f0ddc0; border-radius:6px; }',
      '.iew-loading-box { flex:1; display:flex; align-items:center; justify-content:center; }',
      /* ---- 空态 ---- */
      '.iew-empty-root { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--yb-ink-4,#8994a5); }',
      '.iew-empty-root svg { width:52px; height:52px; opacity:.5; }',
      '.iew-empty-root-text { font-size:14px; }',
      '.iew-empty-inner { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--yb-ink-4,#8994a5); }',
      '.iew-empty-inner svg { width:44px; height:44px; opacity:.5; }',
      /* ---- 右栏: 助手面板 ---- */
      '.iew-right { width:320px; flex:none; display:flex; flex-direction:column; min-height:0; background:var(--yb-surface,#fff); border-left:1px solid var(--yb-border,#dfe4eb); }',
      '.iew-right.is-collapsed { display:none; }',
      '.iew-right .el-tabs { flex:1; display:flex; flex-direction:column; min-height:0; }',
      '.iew-right .el-tabs__header { margin-bottom:0; flex:none; }',
      '.iew-right .el-tabs__nav-scroll { padding:0 6px; }',
      '.iew-right .el-tabs__item { height:34px; line-height:34px; padding:0 9px; font-size:12px; }',
      '.iew-right .el-tabs__content { flex:1; overflow-y:auto; min-height:0; }',
      '.iew-tab-body { padding:10px; display:flex; flex-direction:column; gap:8px; }',
      '.iew-tab-tool { display:flex; gap:6px; align-items:center; flex:none; flex-wrap:wrap; }',
      '.iew-tab-empty { font-size:12px; color:var(--yb-ink-4,#8994a5); text-align:center; padding:14px 4px; line-height:1.7; }',
      /* ---- Tab1 常用语 ---- */
      '.iew-phrase { padding:7px 9px; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; cursor:pointer; display:flex; flex-direction:column; gap:4px; }',
      '.iew-phrase:hover { border-color:var(--yb-brand,#1a5c9e); background:#f5f9fd; }',
      '.iew-phrase-text { font-size:13px; color:var(--yb-ink-1,#1c2430); line-height:1.6; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }',
      '.iew-phrase-foot { display:flex; align-items:center; gap:6px; font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.iew-phrase-use { background:var(--yb-surface-2,#f7f9fc); border-radius:8px; padding:0 7px; line-height:15px; }',
      /* ---- Tab2 历史引用 ---- */
      '.iew-quote-sec { display:flex; flex-direction:column; gap:6px; }',
      '.iew-quote-sec-title { font-size:12px; font-weight:600; color:var(--yb-ink-3,#5a6a7e); display:flex; align-items:center; gap:4px; }',
      '.iew-quote-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:4px; }',
      '.iew-quote-card-title { font-size:13px; color:var(--yb-ink-1,#1c2430); font-weight:600; word-break:break-all; }',
      '.iew-quote-card-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); display:flex; gap:6px; flex-wrap:wrap; }',
      '.iew-quote-card-ops { display:flex; gap:6px; }',
      '.iew-quote-line { font-size:12px; color:var(--yb-ink-3,#5a6a7e); line-height:1.6; word-break:break-all; }',
      '.iew-quote-preview { max-height:52vh; overflow-y:auto; padding:4px 2px; white-space:pre-wrap; word-break:break-all; font-size:13px; line-height:1.8; color:var(--yb-ink-1,#1c2430); }',
      /* ---- Tab3 检查报告 ---- */
      '.iew-report-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:4px; }',
      '.iew-report-card.is-abnormal { border-color:#f2c8c8; background:var(--yb-danger-bg,#fdf0f0); }',
      '.iew-report-head { display:flex; align-items:center; gap:6px; }',
      '.iew-report-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.iew-report-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.iew-report-text { font-size:12px; color:var(--yb-ink-2,#3a4757); line-height:1.6; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }',
      /* ---- Tab4 CDSS ---- */
      '.iew-cdss-summary { display:flex; gap:6px; flex-wrap:wrap; }',
      '.iew-cdss-chip { padding:2px 9px; border-radius:10px; font-size:12px; }',
      '.iew-cdss-chip.is-block { background:var(--yb-danger-bg,#fdf0f0); color:var(--yb-danger,#c74f4f); }',
      '.iew-cdss-chip.is-warning { background:var(--yb-warning-bg,#fbf3e6); color:var(--yb-warning,#a26b1b); }',
      '.iew-cdss-chip.is-info { background:#eaf3fb; color:#2a6aa9; }',
      '.iew-cdss-alert { border-radius:6px; padding:8px 10px; border:1px solid transparent; }',
      '.iew-cdss-alert.is-block { background:var(--yb-danger-bg,#fdf0f0); border-color:#f2c8c8; }',
      '.iew-cdss-alert.is-warning { background:var(--yb-warning-bg,#fbf3e6); border-color:#f0ddc0; }',
      '.iew-cdss-alert.is-info { background:#eaf3fb; border-color:#cfe2f3; }',
      '.iew-cdss-alert-name { font-size:13px; font-weight:700; margin-bottom:2px; display:flex; align-items:center; gap:6px; }',
      '.iew-cdss-alert.is-block .iew-cdss-alert-name { color:var(--yb-danger,#c74f4f); }',
      '.iew-cdss-alert.is-warning .iew-cdss-alert-name { color:var(--yb-warning,#a26b1b); }',
      '.iew-cdss-alert.is-info .iew-cdss-alert-name { color:#2a6aa9; }',
      '.iew-cdss-level { margin-left:auto; font-size:11px; font-weight:400; opacity:.85; }',
      '.iew-cdss-msg { font-size:12px; line-height:1.6; color:var(--yb-ink-2,#3a4757); word-break:break-all; }',
      '.iew-cdss-src { font-size:12px; color:var(--yb-brand,#1a5c9e); text-decoration:none; display:inline-block; margin-top:3px; }',
      'a.iew-cdss-src:hover { text-decoration:underline; }',
      /* ---- Tab5 签名 ---- */
      '.iew-sign-steps { display:flex; flex-direction:column; gap:0; }',
      '.iew-sign-step { display:flex; gap:10px; }',
      '.iew-sign-rail { display:flex; flex-direction:column; align-items:center; flex:none; }',
      '.iew-sign-dot { width:22px; height:22px; border-radius:50%; display:flex; align-items:center; justify-content:center; font-size:12px; flex:none; border:1px solid var(--yb-border-strong,#ccd4de); color:var(--yb-ink-4,#8994a5); background:var(--yb-surface,#fff); }',
      '.iew-sign-dot svg { width:12px; height:12px; }',
      '.iew-sign-step.is-done .iew-sign-dot { background:var(--yb-success-bg,#eef6ec); border-color:var(--yb-success,#3c862d); color:var(--yb-success,#3c862d); }',
      '.iew-sign-step.is-pending .iew-sign-dot { border-color:var(--yb-brand,#1a5c9e); color:var(--yb-brand,#1a5c9e); }',
      '.iew-sign-line { width:2px; flex:1; min-height:14px; background:var(--yb-border-light,#ebeff4); margin:2px 0; }',
      '.iew-sign-step.is-done + .iew-sign-step .iew-sign-line { background:var(--yb-success,#3c862d); }',
      '.iew-sign-main { flex:1; min-width:0; padding-bottom:12px; }',
      '.iew-sign-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); display:flex; align-items:center; gap:6px; flex-wrap:wrap; }',
      /* ---- P5a-4 质控拦截抽屉 ---- */
      '.iew-qc-panel { display:flex; flex-direction:column; gap:8px; }',
      '.iew-qc-tip { font-size:12px; line-height:1.7; color:var(--yb-ink-2,#3a4757); background:var(--yb-warning-bg,#fbf3e6); border:1px solid #f0ddc0; border-radius:6px; padding:8px 10px; }',
      /* ---- P5b-4 质控页签: 时效条/缺陷记录卡 ---- */
      '.iew-qc-timeliness { display:flex; align-items:center; gap:8px; font-size:12px; line-height:1.6; border-radius:6px; padding:7px 10px; border:1px solid transparent; }',
      '.iew-qc-timeliness.is-over { color:var(--yb-danger,#c74f4f); background:var(--yb-danger-bg,#fdf0f0); border-color:#f2c8c8; }',
      '.iew-qc-timeliness.is-warn { color:var(--yb-warning,#a26b1b); background:var(--yb-warning-bg,#fbf3e6); border-color:#f0ddc0; }',
      '.iew-qc-timeliness.is-ok { color:var(--yb-success,#3c862d); background:var(--yb-success-bg,#eef6ec); border-color:#d5e8cf; }',
      '.iew-qc-timeliness-main { flex:1; min-width:0; }',
      '.iew-qc-timeliness-sub { font-size:11px; opacity:.85; margin-top:1px; }',
      '.iew-qc-sec-title { font-size:12px; font-weight:600; color:var(--yb-ink-3,#5a6a7e); display:flex; align-items:center; gap:4px; margin-top:4px; }',
      '.iew-qc-defect { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:4px; }',
      '.iew-qc-defect-head { display:flex; align-items:center; gap:6px; flex-wrap:wrap; }',
      '.iew-qc-defect-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.iew-qc-defect-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.iew-sign-info { font-size:11px; color:var(--yb-ink-4,#8994a5); margin-top:2px; }',
      /* ---- Tab6 版本 ---- */
      '.iew-ver-card { display:flex; align-items:center; gap:8px; padding:7px 9px; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; cursor:pointer; }',
      '.iew-ver-card:hover { border-color:var(--yb-brand,#1a5c9e); }',
      '.iew-ver-card.is-sel { border-color:var(--yb-brand,#1a5c9e); background:#eaf3fb; }',
      '.iew-ver-main { flex:1; min-width:0; }',
      '.iew-ver-no { font-size:13px; font-weight:700; color:var(--yb-brand,#1a5c9e); }',
      '.iew-ver-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); margin-top:1px; }',
      '.iew-ver-ops { display:flex; gap:4px; }',
      '.iew-diff-chips { display:flex; gap:8px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); padding:2px 0 6px; flex-wrap:wrap; }',
      '.iew-diff-table { width:100%; border-collapse:collapse; font-family:Consolas,monospace; font-size:12px; table-layout:fixed; }',
      '.iew-diff-table th { position:sticky; top:0; z-index:1; background:var(--yb-surface-2,#f7f9fc); border:1px solid var(--yb-border,#dfe4eb); padding:4px 10px; text-align:left; }',
      '.iew-diff-table td { border:1px solid var(--yb-border-light,#ebeff4); padding:2px 10px; white-space:pre-wrap; word-break:break-all; vertical-align:top; }',
      '.iew-diff-table .is-add { background:var(--yb-success-bg,#eef6ec); color:var(--yb-success,#3c862d); }',
      '.iew-diff-table .is-del { background:var(--yb-danger-bg,#fdf0f0); color:var(--yb-danger,#c74f4f); text-decoration:line-through; }',
      '.iew-diff-table .is-modify { background:var(--yb-warning-bg,#fbf3e6); }',
      '.iew-diff-table .is-empty { background:var(--yb-canvas,#f2f4f8); color:var(--yb-ink-4,#8994a5); }',
      '.iew-ver-preview { max-height:56vh; overflow-y:auto; padding:4px 2px; white-space:pre-wrap; word-break:break-all; font-size:13px; line-height:1.8; color:var(--yb-ink-1,#1c2430); }',
      /* ---- 新建对话框 ---- */
      '.iew-create-types { max-height:52vh; overflow-y:auto; display:flex; flex-direction:column; gap:12px; padding-top:12px; }',
      '.iew-create-group-title { font-size:12px; font-weight:600; color:var(--yb-ink-3,#5a6a7e); margin-bottom:6px; }',
      '.iew-create-cards { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; }',
      '.iew-create-card { border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; padding:10px 8px; text-align:center; font-size:13px; color:var(--yb-ink-2,#3a4757); cursor:pointer; display:flex; flex-direction:column; align-items:center; gap:5px; transition:border-color .15s ease, box-shadow .15s ease; }',
      '.iew-create-card:hover { border-color:var(--yb-brand,#1a5c9e); color:var(--yb-brand,#1a5c9e); }',
      '.iew-create-card.is-disabled { opacity:.4; cursor:not-allowed; }',
      '.iew-create-card.is-disabled:hover { border-color:var(--yb-border,#dfe4eb); color:var(--yb-ink-2,#3a4757); }',
      '.iew-create-card svg { width:20px; height:20px; }',
      '.iew-create-tpl-head { display:flex; align-items:center; justify-content:space-between; padding:10px 0 6px; font-size:13px; color:var(--yb-ink-2,#3a4757); }',
      '.iew-create-tpl-list { max-height:44vh; overflow-y:auto; display:flex; flex-direction:column; gap:8px; }',
      '.iew-create-tpl { border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; padding:9px 12px; cursor:pointer; }',
      '.iew-create-tpl:hover { border-color:var(--yb-brand,#1a5c9e); background:#f5f9fd; }',
      '.iew-create-tpl-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      '.iew-create-tpl-desc { font-size:11px; color:var(--yb-ink-4,#8994a5); margin-top:2px; }',
      '.iew-create-tpl-empty { font-size:12px; color:var(--yb-ink-4,#8994a5); text-align:center; padding:12px 0; }',
      /* ---- P8a-3 引用草药方对话框 ---- */
      '.iew-herb-pick { cursor:pointer; }',
      '.iew-herb-on td.el-table__cell { background:var(--yb-brand-bg,#eaf3fb); }',
      '.iew-herb-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      '.iew-herb-preview { max-height:220px; overflow:auto; padding:10px 12px; border:1px dashed var(--yb-border,#dfe4eb); border-radius:6px; background:var(--yb-surface-2,#f7f9fc); white-space:pre-wrap; word-break:break-all; font-size:13px; line-height:1.8; color:var(--yb-ink-1,#1c2430); }',
      '.iew-herb-hint { font-size:12px; color:var(--yb-ink-4,#8994a5); margin-top:6px; }',
      /* ---- P8b-2 多方式签名: 下拉触发角标/签名记录卡/CA 弹窗 ---- */
      '.iew-dd-caret { display:inline-flex; width:11px; height:11px; vertical-align:-1px; transform:rotate(90deg); }',
      '.iew-sign-rec-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:7px 9px; display:flex; flex-direction:column; gap:6px; }',
      '.iew-sign-rec-head { display:flex; align-items:center; gap:6px; flex-wrap:wrap; font-size:12px; }',
      '.iew-sign-rec-title { font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      '.iew-sign-rec-meta { color:var(--yb-ink-4,#8994a5); font-size:11px; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.iew-sign-rec-tags { margin-left:auto; display:flex; gap:4px; flex:none; }',
      '.iew-sign-rec-ops { display:flex; gap:2px; }',
      '.iew-sign-rec-ops .el-button + .el-button { margin-left:0; }',
      '.iew-sign-rec-imgs { display:flex; gap:8px; flex-wrap:wrap; align-items:flex-end; }',
      '.iew-sign-rec-img { border:1px solid var(--yb-border-light,#ebeff4); border-radius:4px; background:#fff; max-height:40px; padding:1px; }',
      '.iew-ca-tip { display:flex; align-items:flex-start; gap:6px; font-size:12px; line-height:1.7; color:var(--yb-ink-2,#3a4757); background:var(--yb-warning-bg,#fbf3e6); border:1px solid #f0ddc0; border-radius:6px; padding:8px 10px; margin-bottom:10px; }',
      '.iew-ca-tip .iew-icon { flex:none; margin-top:3px; }',
      /* ---- 滚动条 ---- */
      '.iew-left .iew-groups::-webkit-scrollbar, .iew-editor-host::-webkit-scrollbar, .iew-right .el-tabs__content::-webkit-scrollbar { width:6px; }',
      '.iew-left .iew-groups::-webkit-scrollbar-thumb, .iew-editor-host::-webkit-scrollbar-thumb, .iew-right .el-tabs__content::-webkit-scrollbar-thumb { background:var(--yb-border-strong,#ccd4de); border-radius:3px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  /* 记录类型(1-15, 与后端 RECORD_TYPE_LABELS/签名规则 record_type 同口径) */
  var RECORD_TYPES = {
    1: '入院记录', 2: '首次病程记录', 3: '日常病程记录', 4: '查房记录',
    5: '术前小结', 6: '手术记录', 7: '术后病程记录', 8: '出院小结',
    9: '死亡记录', 10: '病案首页', 11: '交接班记录', 12: '转科记录',
    13: '知情同意书', 14: '讨论记录', 15: '会诊记录'
  };
  /* 新建对话框的 15 张类型卡(按 4 组陈列) */
  var TYPE_GROUPS = [
    { label: '入院出院类', items: [1, 8, 9, 10] },
    { label: '病程类', items: [2, 3, 4, 7] },
    { label: '手术类', items: [5, 6] },
    { label: '其他文书', items: [11, 12, 13, 14, 15] }
  ];
  /* 分组 key → 展示名(grouped 接口兜底) */
  var GROUP_NAMES = { admit_discharge: '入院出院类', progress: '病程类', surgery: '手术类', other: '其他' };
  var RECORD_STATUS = { 1: '草稿', 2: '已提交', 3: '已审核' };
  var RECORD_STATUS_TYPE = { 1: 'info', 2: 'warning', 3: 'success' };
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
  /* 签名链阶段 → 角色名 */
  var STAGE_LABELS = { author: '书写医师', resident: '住院医师', attending: '主治医师', director: '主任医师', doctor: '接诊医师' };
  /* 阶段 → 可签署职称档位上限(≤ 该值可签; 后端规则为准, 前端仅做交互预判) */
  var STAGE_TITLE_MAX = { author: 9, resident: 4, attending: 3, director: 2 };
  var OPERATE_TYPES = { save: '保存', submit: '提交', audit: '审核', sign: '签名' };
  var EMPTY_DOC = { type: 'doc', content: [{ type: 'paragraph' }] };

  /* 内联 SVG 图标(feather 风格, viewBox 24, stroke currentColor) */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    plus: svg('<path d="M12 5v14M5 12h14"/>'),
    save: svg('<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>'),
    send: svg('<path d="m22 2-7 20-4-9-9-4z"/><path d="M22 2 11 13"/>'),
    pen: svg('<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/>'),
    printer: svg('<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/>'),
    clock: svg('<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>'),
    search: svg('<circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>'),
    file: svg('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>'),
    folder: svg('<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>'),
    chevron: svg('<polyline points="9 18 15 12 9 6"/>'),
    check: svg('<polyline points="20 6 9 17 4 12"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    alert: svg('<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>'),
    layers: svg('<polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/>'),
    history: svg('<path d="M3 3v5h5"/><path d="M3.05 13A9 9 0 1 0 6 5.3L3 8"/><polyline points="12 7 12 12 15 15"/>'),
    message: svg('<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>'),
    clipboard: svg('<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/>'),
    activity: svg('<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>'),
    panel: svg('<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="15" y1="3" x2="15" y2="21"/>'),
    bed: svg('<path d="M2 4v16"/><path d="M2 8h18a2 2 0 0 1 2 2v10"/><path d="M2 17h20"/><path d="M6 8v9"/>'),
    leaf: svg('<path d="M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10Z"/><path d="M2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12"/>')
  };

  /* ================= 纯工具函数 ================= */
  function todayText() {
    var d = new Date();
    var m = String(d.getMonth() + 1);
    var day = String(d.getDate());
    return d.getFullYear() + '-' + (m.length < 2 ? '0' + m : m) + '-' + (day.length < 2 ? '0' + day : day);
  }
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
    else { try { console.log('[iew:' + type + '] ' + text); } catch (e) { /* noop */ } }
  }
  function confirmBox(text, title, opts) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessageBox) {
      return EP.ElMessageBox.confirm(text, title || '确认', opts || {});
    }
    return global.Promise ? global.Promise.reject(new Error('cancel')) : null;
  }
  /* P5b-4 SSE 事件类型判定: 整改通知/申诉结果需独立弹窗(不随当前病历过滤, 全量知会) */
  function typeIsNoticeOrAppeal(env) {
    var t = env && env.type;
    return t === 'EMR_QC_NOTICE' || t === 'EMR_QC_APPEAL_RESULT';
  }

  /* ---- 旧内容归一化为 Tiptap 文档 ---- */
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
  /* 旧键值 JSON(表单时代) → 段落文档 */
  function formJsonToDoc(obj) {
    var paras = [];
    Object.keys(obj || {}).forEach(function (k) {
      var v = obj[k];
      if (v == null) { return; }
      var vs = String(v);
      if (!vs.length) { return; }
      paras.push({ type: 'paragraph', content: [{ type: 'text', text: k + '：' + vs }] });
    });
    if (!paras.length) { paras.push({ type: 'paragraph' }); }
    return { type: 'doc', content: paras };
  }
  /* 旧富文本 HTML → 提取文本转段落(白名单净化交给后端, 此处只取文本) */
  function htmlToDoc(html) {
    var el = document.createElement('div');
    el.innerHTML = String(html || '');
    return textToDoc(el.textContent || '');
  }

  /* ================= 组件 ================= */
  var InpEmrWriter = {
    name: 'InpEmrWriter',
    emits: ['open-timeline'],
    props: {
      inpVisitId: { type: [String, Number], default: null },
      patientId: { type: [String, Number], default: null },
      deptCode: { type: String, default: '' },
      bedNo: { type: String, default: '' },
      patientName: { type: String, default: '' },
      patient: { type: Object, default: null }
    },
    data: function () {
      return {
        icons: ICONS,
        phraseCategories: PHRASE_CATEGORIES,
        /* 列表 */
        groups: [],
        totalRecords: 0,
        listLoading: false,
        searchKey: '',
        collapsedGroups: {},
        /* 当前记录与编辑器 */
        current: null,
        editorLoading: false,
        editorWrapper: null,
        legacyMode: false,
        dirty: false,
        saving: false,
        submitting: false,
        /* 离线草稿 */
        draftKey: '',
        draftSavedText: '',
        /* 新建对话框 */
        createVisible: false,
        createStep: 1,
        createType: null,
        tplList: [],
        tplLoading: false,
        creating: false,
        /* 右侧面板 */
        panelVisible: true,
        activeTab: 'phrase',
        /* Tab1 常用语 */
        phrases: [],
        phraseLoading: false,
        phraseCategory: '',
        phraseKeyword: '',
        /* Tab2 历史引用 */
        timeline: [],
        timelineLoading: false,
        timelineFailed: false,
        quoteVisible: false,
        quoteTitle: '',
        quoteText: '',
        quoteLoading: false,
        quoteTarget: null,
        /* Tab3 检查报告 */
        labReports: [],
        examReports: [],
        reportLoading: false,
        reportFailed: false,
        reportTab: 'lab',
        /* Tab4 CDSS */
        cdssAlerts: [],
        cdssLoading: false,
        cdssFailed: false,
        cdssEvaluated: false,
        /* Tab5 签名 */
        signStatus: null,
        signRules: [],
        signLoading: false,
        signing: false,
        /* P8b-2 多方式签名: CA 弹窗 + 签名记录(方式/验签标签) */
        caVisible: false,
        caPin: '',
        caStage: '',
        caSignList: [],
        caSignLoading: false,
        verifyLoadingId: null,
        /* P5a-4 签名质控(由签名响应 qcResult 驱动) */
        qcForbidden: [],
        qcBlocks: [],
        qcWarnings: [],
        showQcPanel: false,
        /* P5b-4 质控页签: 持久化缺陷记录 + 时效检查(刷新按钮/切页签/SSE 事件触发) */
        qcDefects: [],
        qcDefectLoading: false,
        qcLoaded: false,
        timeliness: null,
        timelinessAt: 0,
        timelinessLoading: false,
        /* Tab6 版本 */
        versions: [],
        versionLoading: false,
        versionSel: [],
        diffVisible: false,
        diffData: null,
        diffLoading: false,
        versionVisible: false,
        versionPreview: null,
        versionPreviewLoading: false,
        versionPreviewTitle: '',
        /* P8a-3 引用药方(住院草药处方, 端点在 P8a-2 HisPrescriptionService) */
        herbVisible: false,
        herbLoading: false,
        herbList: [],
        herbSelRow: null,
        herbText: '',
        herbTextLoading: false,
        /* 身份与杂项 */
        myStaffId: null,
        myTitleCode: null,
        nowTs: Date.now()
      };
    },
    computed: {
      patientLabel: function () {
        return this.patientName || (this.patient && this.patient.name) || '';
      },
      hasPatient: function () {
        return !!(this.inpVisitId != null && String(this.inpVisitId).length);
      },
      /* 按搜索词过滤分组(保留组结构, 空组剔除) */
      filteredGroups: function () {
        var kw = String(this.searchKey || '').trim().toLowerCase();
        var out = [];
        (this.groups || []).forEach(function (g) {
          var recs = (g && g.records) || [];
          if (kw) {
            recs = recs.filter(function (r) {
              var t = String(r.title || '').toLowerCase();
              var l = String(r.typeLabel || '').toLowerCase();
              return t.indexOf(kw) >= 0 || l.indexOf(kw) >= 0;
            });
          }
          if (recs.length) {
            out.push({ key: g.key, name: g.name || GROUP_NAMES[g.key] || '其他', count: recs.length, records: recs });
          }
        });
        return out;
      },
      /* 本次就诊的其他病历(历史引用数据源) */
      otherRecords: function () {
        var vm = this;
        var curKey = vm.current ? HIS.idKey(vm.current.id) : '';
        var all = [];
        (vm.groups || []).forEach(function (g) {
          ((g && g.records) || []).forEach(function (r) { all.push(r); });
        });
        return all.filter(function (r) {
          return curKey === '' || !HIS.sameId(r.id, curKey);
        });
      },
      /* 权限: 编辑(草稿态 + 本人或主治及以上) */
      canEdit: function () {
        if (!this.current || Number(this.current.status) !== 1) { return false; }
        if (!this.myStaffId) { return true; }            /* 身份未知时放行, 由后端兜底 */
        var isOwner = this.current.doctorId != null && HIS.sameId(this.current.doctorId, this.myStaffId);
        var senior = this.myTitleCode !== '' && this.myTitleCode !== null && Number(this.myTitleCode) <= 3;
        return isOwner || senior;
      },
      canSubmit: function () {
        return this.canEdit;
      },
      canDelete: function () {
        if (!this.current || Number(this.current.status) !== 1) { return false; }
        if (!this.myStaffId) { return true; }
        var isOwner = this.current.doctorId != null && HIS.sameId(this.current.doctorId, this.myStaffId);
        var senior = this.myTitleCode !== '' && this.myTitleCode !== null && Number(this.myTitleCode) <= 3;
        return isOwner || senior;
      },
      /* 签名链展示步骤: status 优先, 草稿(无 status)回退规则链 */
      signSteps: function () {
        var st = this.signStatus;
        if (st && Array.isArray(st.chain) && st.chain.length) {
          return st.chain.slice().sort(function (a, b) {
            return (Number(a.stageOrder) || 0) - (Number(b.stageOrder) || 0);
          });
        }
        return (this.signRules || []).map(function (r) {
          return { stage: r.stage, stageOrder: r.stageOrder, required: r.required !== false, completed: false };
        });
      },
      /* 第一个待签署阶段(头栏"签名"按钮定位用) */
      pendingStage: function () {
        var pending = null;
        this.signSteps.forEach(function (s) {
          if (!pending && !s.completed && s.required !== false) { pending = s; }
        });
        return pending;
      },
      canSignNow: function () {
        var p = this.pendingStage;
        return !!(p && this.canSignStage(p.stage));
      },
      cdssCounts: function () {
        var c = { block: 0, warning: 0, info: 0 };
        (this.cdssAlerts || []).forEach(function (a) {
          var lv = a && a.level;
          if (lv === 'block' || lv === 'warning' || lv === 'info') { c[lv]++; }
        });
        return c;
      },
      /* 常用语前端二次过滤(类别已在请求侧过滤, 此处为关键词) */
      filteredPhrases: function () {
        var kw = String(this.phraseKeyword || '').trim().toLowerCase();
        if (!kw) { return this.phrases || []; }
        return (this.phrases || []).filter(function (p) {
          return String(p.content || '').toLowerCase().indexOf(kw) >= 0;
        });
      },
      /* 新建对话框: 类型分组卡片 */
      createTypeGroups: function () {
        var vm = this;
        return TYPE_GROUPS.map(function (g) {
          return { label: g.label, items: g.items.map(function (t) { return { type: t, label: RECORD_TYPES[t] }; }) };
        });
      },
      createTypeLabel: function () {
        return this.createType != null ? (RECORD_TYPES[Number(this.createType)] || '病历文书') : '';
      },
      /* 当前类型可用模板(templateCategory 与记录类型 1-8 对应) */
      templatesForType: function () {
        var t = this.createType != null ? Number(this.createType) : null;
        return (this.tplList || []).filter(function (tpl) {
          return tpl && tpl.templateCategory != null && Number(tpl.templateCategory) === t;
        });
      },
      /* P5b-4 质控缺陷记录: severity 降序(禁止3→拦截2→提醒1), 同级新近在前 */
      qcSortedDefects: function () {
        return (this.qcDefects || []).slice().sort(function (a, b) {
          var d = (Number(b.severity) || 0) - (Number(a.severity) || 0);
          if (d !== 0) { return d; }
          return String(b.createTime || '').localeCompare(String(a.createTime || ''));
        });
      },
      /* 时效条样式档: overdue/warn/ok/null(未检查或无时限) */
      timelinessCls: function () {
        var st = this.timeliness && this.timeliness.status;
        if (st === 'overdue') { return 'is-over'; }
        if (st === 'warning') { return 'is-warn'; }
        if (st === 'normal') { return 'is-ok'; }
        return null;
      },
      /* 时效主文案: 剩余分钟随本地时钟逐分钟递减(timelinessAt 为检查基准时刻) */
      timelinessMain: function () {
        var t = this.timeliness;
        if (!t || !t.deadlineTime) { return ''; }
        var remain = this.remainMinutes();
        if (remain == null) { return ''; }
        if (remain <= 0) { return '⏰ 已超过书写时限 ' + Math.abs(Math.round(remain)) + ' 分钟'; }
        if (remain < 60) { return '⏰ 距书写截止还有 ' + Math.max(1, Math.round(remain)) + ' 分钟'; }
        return '⏰ 距书写截止还有 ' + Math.floor(remain / 60) + ' 小时 ' + Math.round(remain % 60) + ' 分钟';
      },
      timelinessSub: function () {
        var t = this.timeliness;
        if (!t) { return ''; }
        var txt = '截止 ' + fmtDT(t.deadlineTime);
        var rules = (t.rules || []).length;
        if (rules) { txt += ' · 匹配 ' + rules + ' 条时限规则'; }
        return txt;
      },
      /* 时限状态: null 无 / 'over' 已超时 / 'warn' 2小时内 */
      currentDeadline: function () {
        var dl = this.current && this.current.deadlineTime;
        if (!dl || Number(this.current.status) !== 1) { return null; }
        var t = new Date(String(dl).replace('T', ' ').replace(/-/g, '/')).getTime();
        if (isNaN(t)) { return null; }
        var diff = t - this.nowTs;
        if (diff < 0) { return 'over'; }
        if (diff <= 2 * 60 * 60 * 1000) { return 'warn'; }
        return 'ok';
      }
    },
    watch: {
      inpVisitId: function () { this.onVisitChange(); },
      activeTab: function (tab) {
        var vm = this;
        if (tab === 'phrase' && !vm.phrases.length && !vm.phraseLoading) { vm.loadPhrases(); }
        if (tab === 'sign' && vm.current && vm.current.id) { vm.loadSignInfo(); }
        if (tab === 'version' && vm.current && vm.current.id && !vm.versions.length) { vm.loadVersions(); }
        if (tab === 'cdss' && vm.current && !vm.cdssEvaluated) { vm.evaluateCdssTab(); }
        /* P5b-4: 首次切入质控页签时拉取缺陷记录与时效检查(后续由刷新按钮/SSE 事件驱动) */
        if (tab === 'qc' && vm.current && vm.current.id && !vm.qcLoaded) { vm.refreshQcData(); }
      }
    },
    created: function () {
      /* Ctrl/Cmd+S 全局快捷键(实例级绑定, 卸载时移除) */
      var vm = this;
      vm._onKeydown = function (ev) {
        if ((ev.ctrlKey || ev.metaKey) && String(ev.key || '').toLowerCase() === 's') {
          ev.preventDefault();
          if (vm.current && vm.canEdit) { vm.save(false, false); }
        }
      };
      /* P5b-4: 订阅 SSE 质控事件(HIS.EmrSseClient.on 返回退订函数, 卸载时逐个调用)。
       * 弹窗提示由铃铛统一弹出, 此处仅处理与当前病历相关的页签数据刷新。 */
      vm._sseUnsubs = [];
      var sse = HIS.EmrSseClient;
      if (sse && typeof sse.on === 'function') {
        ['EMR_QC_DEADLINE_WARN', 'EMR_QC_OVERDUE', 'EMR_QC_NOTICE', 'EMR_QC_APPEAL_RESULT'].forEach(function (t) {
          var off = sse.on(t, function (env) { vm.onQcSseEvent(env); });
          if (typeof off === 'function') { vm._sseUnsubs.push(off); }
        });
      }
    },
    mounted: function () {
      var vm = this;
      vm.loadIdentity();
      vm._deadlineTimer = setInterval(function () { vm.nowTs = Date.now(); }, 60000);
      global.document.addEventListener('keydown', vm._onKeydown);
      /* 离线草稿库预热(失败静默, IndexedDB 不可用不影响书写) */
      if (HIS.EmrOfflineDraft && typeof HIS.EmrOfflineDraft.init === 'function') {
        HIS.EmrOfflineDraft.init().catch(function () { /* noop */ });
      }
      if (vm.hasPatient) {
        vm.loadGroups(true);
        vm.loadPanelData();
      }
    },
    beforeUnmount: function () {
      var vm = this;
      if (vm._deadlineTimer) { clearInterval(vm._deadlineTimer); vm._deadlineTimer = null; }
      global.document.removeEventListener('keydown', vm._onKeydown);
      /* P5b-4: 退订 SSE 质控事件, 防止组件销毁后回调访问已失效实例 */
      (vm._sseUnsubs || []).forEach(function (off) { try { off(); } catch (e) { /* noop */ } });
      vm._sseUnsubs = null;
      vm.destroyEditor();
    },

    methods: {
      /* ================= 通用 ================= */
      fmtDT: fmtDT,
      /* ================= 身份与权限 ================= */
      /* 当前用户: staffId + 职称(懒加载一次; titleCode=5 实习医生仅可建日常病程) */
      loadIdentity: function () {
        var vm = this;
        var u = (typeof HIS.getUser === 'function') ? HIS.getUser() : null;
        vm.myStaffId = u && u.staffId != null ? HIS.id(u.staffId) : null;
        if (!vm.myStaffId || vm.myTitleCode !== null) { return; }
        HIS.get('/api/his/staff/' + HIS.idParam(vm.myStaffId))
          .then(function (s) { vm.myTitleCode = s && s.titleCode != null ? String(s.titleCode) : ''; })
          .catch(function () { vm.myTitleCode = ''; });
      },
      canCreateType: function (type) {
        if (this.myTitleCode === null || this.myTitleCode === '') { return true; }  /* 未知职称放行, 后端兜底 */
        if (String(this.myTitleCode) === '5') { return Number(type) === 3; }
        return true;
      },

      /* ================= 左栏: 分组列表 ================= */
      loadGroups: function (autoSelect) {
        var vm = this;
        if (!vm.hasPatient) { vm.groups = []; vm.totalRecords = 0; return global.Promise ? global.Promise.resolve() : null; }
        vm.listLoading = true;
        return HIS.get('/api/his/inp/record/grouped?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .then(function (data) {
            var d = data || {};
            var groups = Array.isArray(d.groups) ? d.groups : [];
            /* 归一化: key/name 兜底 + 记录字段字符串化 */
            groups.forEach(function (g) {
              g.name = g.name || GROUP_NAMES[g.key] || '其他';
              (g.records || []).forEach(function (r) {
                r.id = HIS.id(r.id);
                r.doctorId = HIS.id(r.doctorId);
              });
            });
            vm.groups = groups;
            vm.totalRecords = Number(d.total) || 0;
            if (autoSelect) { vm.autoSelectRecord(); }
          })
          .catch(function (e) {
            vm.groups = []; vm.totalRecords = 0;
            HIS.notifyError(e);
          })
          .finally(function () { vm.listLoading = false; });
      },
      /* 自动选中: 最新草稿优先, 否则第一条 */
      autoSelectRecord: function () {
        var first = null;
        var firstDraft = null;
        (this.groups || []).some(function (g) {
          return ((g && g.records) || []).some(function (r) {
            if (!first) { first = r; }
            if (!firstDraft && Number(r.status) === 1) { firstDraft = r; }
            return !!firstDraft;
          });
        });
        var target = firstDraft || first;
        if (target) { this.selectRecord(target); }
      },
      toggleGroup: function (key) {
        var m = Object.assign({}, this.collapsedGroups);
        m[key] = !m[key];
        this.collapsedGroups = m;
      },
      isGroupOpen: function (key) { return !this.collapsedGroups[key]; },
      isActiveRow: function (row) {
        return !!(this.current && this.current.id != null && HIS.sameId(this.current.id, row.id));
      },
      typeLabelOf: function (t) { return RECORD_TYPES[Number(t)] || '病历'; },
      statusTextOf: function (v) { return RECORD_STATUS[Number(v)] || '-'; },
      statusTagOf: function (v) { return RECORD_STATUS_TYPE[Number(v)] || 'info'; },
      /* 行级时限指示: 已超时红 / 2小时内黄 */
      deadlineOf: function (row) {
        if (!row || !row.deadlineTime || Number(row.status) !== 1) { return null; }
        var t = new Date(String(row.deadlineTime).replace('T', ' ').replace(/-/g, '/')).getTime();
        if (isNaN(t)) { return null; }
        var diff = t - this.nowTs;
        if (diff < 0) { return { cls: 'iew-rec-dl', text: '已超时' }; }
        if (diff <= 2 * 60 * 60 * 1000) { return { cls: 'iew-rec-dl is-warn', text: Math.max(1, Math.round(diff / 60000)) + '分钟内' }; }
        return null;
      },

      /* ================= 中栏: 记录选择与编辑器 ================= */
      onVisitChange: function () {
        var vm = this;
        vm.destroyEditor();
        vm.current = null;
        vm.dirty = false;
        vm.legacyMode = false;
        vm.groups = [];
        vm.totalRecords = 0;
        vm.searchKey = '';
        vm.signStatus = null; vm.signRules = [];
        vm.caSignList = []; vm.caVisible = false; vm.caPin = ''; vm.caStage = '';
        vm.qcForbidden = []; vm.qcBlocks = []; vm.qcWarnings = []; vm.showQcPanel = false;
        vm.resetQcTabData();
        vm.versions = []; vm.versionSel = [];
        vm.cdssAlerts = []; vm.cdssEvaluated = false; vm.cdssFailed = false;
        vm.draftSavedText = '';
        vm.quoteTarget = null;
        if (vm.hasPatient) {
          vm.loadGroups(true);
          vm.loadPanelData();
        }
      },
      selectRecord: function (row) {
        var vm = this;
        if (!row || row.id == null) { return; }
        if (vm.current && HIS.sameId(vm.current.id, row.id) && !vm.editorLoading) { return; }
        vm.destroyEditor();
        vm.current = null;                       /* v-if 触发编辑区 DOM 重建 */
        vm.legacyMode = false;
        vm.dirty = false;
        vm.draftSavedText = '';
        vm.qcForbidden = []; vm.qcBlocks = []; vm.qcWarnings = []; vm.showQcPanel = false;
        vm.resetQcTabData();
        vm.editorLoading = true;
        HIS.get('/api/his/inp/record/' + HIS.idParam(row.id))
          .then(function (rec) {
            if (!rec) { throw new Error('病历详情为空'); }
            rec.id = HIS.id(rec.id);
            rec.doctorId = HIS.id(rec.doctorId);
            vm.current = Object.assign({}, row, rec);
            /* 面板联动数据 */
            vm.loadSignInfo();
            vm.loadVersions();
            vm.cdssAlerts = []; vm.cdssEvaluated = false;
            /* 内容归一化 → 编辑器(等 v-if 渲染出容器) */
            var norm = vm.normalizeContent(vm.current);
            vm.legacyMode = !!norm.legacy;
            vm.$nextTick(function () { vm.buildEditor(norm.doc); });
          })
          .catch(function (e) {
            vm.editorLoading = false;
            HIS.notifyError(e);
          });
      },
      /* 内容归一化: content(密文已由后端解密)优先, structureData 兜底;
       * '<' 旧 HTML / 旧键值 JSON → 段落文档 + 兼容横幅 */
      normalizeContent: function (rec) {
        var raw = '';
        if (rec && typeof rec.content === 'string' && rec.content.length) { raw = rec.content; }
        else if (rec && typeof rec.structureData === 'string' && rec.structureData.length) { raw = rec.structureData; }
        else if (rec && rec.structureData && typeof rec.structureData === 'object') { return { doc: rec.structureData, legacy: false }; }
        if (!raw || !String(raw).trim()) { return { doc: null, legacy: false }; }
        var t = String(raw).trim();
        var first = t.charAt(0);
        if (first === '<') { return { doc: htmlToDoc(t), legacy: true }; }
        if (first === '{' || first === '[') {
          try {
            var obj = JSON.parse(t);
            if (obj && typeof obj === 'object' && !Array.isArray(obj) && obj.type === 'doc') {
              return { doc: obj, legacy: false };
            }
            if (Array.isArray(obj)) { return { doc: textToDoc(JSON.stringify(obj)), legacy: true }; }
            return { doc: formJsonToDoc(obj), legacy: true };
          } catch (e) { /* 非法 JSON 走纯文本 */ }
        }
        return { doc: textToDoc(raw), legacy: true };
      },
      /* 创建 Tiptap 编辑器(先销毁旧实例; createEditor 为异步) */
      buildEditor: function (doc) {
        var vm = this;
        var seq = (vm._editorSeq = (vm._editorSeq || 0) + 1);
        if (!HIS.EmrEditor || typeof HIS.EmrEditor.createEditor !== 'function') {
          vm.editorLoading = false;
          toast('error', '编辑器组件未加载(emr-editor.js)');
          return;
        }
        var editable = vm.canEdit;
        HIS.EmrEditor.createEditor({
          container: vm.$refs.editorHost,
          toolbarContainer: vm.$refs.toolbarHost,
          mode: 'edit',
          readOnly: !editable,
          document: doc || EMPTY_DOC,
          placeholder: '开始书写病历内容…',
          nlgEnabled: true,
          cdssEnabled: editable,
          recordType: vm.current && vm.current.recordType != null ? String(vm.current.recordType) : '',
          deptCode: vm.deptCode || '',
          onSave: function () { vm.save(false, true); },
          onFieldChange: function () { vm.dirty = true; }
        }).then(function (wrapper) {
          if (seq !== vm._editorSeq) { try { wrapper.destroy(); } catch (e) { /* 已被更新批次取代 */ } return; }
          vm.editorWrapper = wrapper;
          vm.editorLoading = false;
          if (typeof wrapper.on === 'function') {
            wrapper.on('update', function () { vm.dirty = true; });
          }
          if (editable && wrapper.editor) { try { wrapper.focus(); } catch (e) { /* noop */ } }
          /* 离线草稿: 仅草稿态开启自动保存 + 冲突检测 */
          if (Number(vm.current.status) === 1) {
            vm.startDraft();
            vm.checkDraftConflict();
          }
        }).catch(function (e) {
          if (seq === vm._editorSeq) { vm.editorLoading = false; }
          HIS.notifyError(e);
        });
      },
      destroyEditor: function () {
        var vm = this;
        vm._editorSeq = (vm._editorSeq || 0) + 1;   /* 使在途 createEditor 失效 */
        vm.stopDraft();
        if (vm.editorWrapper) {
          try { vm.editorWrapper.destroy(); } catch (e) { /* noop */ }
          vm.editorWrapper = null;
        }
      },

      /* ================= 离线草稿 ================= */
      draftKeyOf: function (recordId) {
        return 'inp:' + HIS.idKey(this.inpVisitId) + ':record:' + HIS.idKey(recordId);
      },
      startDraft: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.editorWrapper || !vm.current || !vm.current.id) { return; }
        vm.draftKey = vm.draftKeyOf(vm.current.id);
        try {
          HIS.EmrOfflineDraft.startAutoSave(vm.draftKey, vm.editorWrapper, {
            staffId: vm.myStaffId,
            patientName: vm.patientLabel,
            recordType: vm.typeLabelOf(vm.current.recordType),
            version: vm.current.version != null ? String(vm.current.version) : null
          }, 30000);
          vm.draftSavedText = '';
        } catch (e) { /* IndexedDB 不可用静默降级 */ }
      },
      stopDraft: function () {
        if (this.draftKey && HIS.EmrOfflineDraft) {
          try { HIS.EmrOfflineDraft.stopAutoSave(this.draftKey); } catch (e) { /* noop */ }
        }
        this.draftKey = '';
        this.draftSavedText = '';
      },
      /* 打开草稿记录时检测本地离线草稿: 较新则询问恢复 */
      checkDraftConflict: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.current || !vm.current.id) { return; }
        var key = vm.draftKeyOf(vm.current.id);
        var serverV = vm.current.version != null ? String(vm.current.version) : null;
        HIS.EmrOfflineDraft.getConflictInfo(key, serverV).then(function (info) {
          if (!info || !info.exists || !info.draft) { return; }
          var d = new Date(Number(info.draftTime) || 0);
          var p = function (n) { return (n < 10 ? '0' : '') + n; };
          var time = isNaN(d.getTime()) ? '-' : (d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()));
          var tip = info.hasConflict
            ? '本地离线草稿(' + time + ')基于旧版本生成, 恢复可能覆盖服务器上的后续修改。'
            : '检测到本地离线草稿(' + time + '), 是否恢复到编辑器?';
          confirmBox(tip, '恢复离线草稿', {
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
      /* 保存成功后清空本地草稿并重启自动保存(重置基线) */
      resetDraftAfterSave: function () {
        var vm = this;
        if (!HIS.EmrOfflineDraft || !vm.draftKey) { return; }
        var key = vm.draftKey;
        HIS.EmrOfflineDraft.stopAutoSave(key);
        HIS.EmrOfflineDraft.deleteDraft(key).then(function () {
          if (vm.draftKey === key) { vm.draftSavedText = ''; vm.startDraft(); }
        }).catch(function () { /* noop */ });
      },

      /* ================= 保存 / 提交 / 删除 ================= */
      /* 保存: Tiptap JSON 双轨写(content + structureData 同值);
       * skipCdss=true 时跳过前端 CDSS 评估(工具栏保存路径已由 wrapper 包装评估过) */
      save: function (silent, skipCdss) {
        var vm = this;
        if (vm.saving) { return global.Promise ? global.Promise.reject('busy') : null; }
        if (!vm.current || !vm.current.id) { toast('warning', '尚未打开病历'); return global.Promise ? global.Promise.reject('no-record') : null; }
        if (!vm.canEdit) { toast('warning', '当前状态不可编辑'); return global.Promise ? global.Promise.reject('readonly') : null; }
        if (!vm.editorWrapper || typeof vm.editorWrapper.toJSON !== 'function') {
          toast('warning', '编辑器尚未就绪'); return global.Promise ? global.Promise.reject('no-editor') : null;
        }
        var doPut = function () {
          var json = vm.editorWrapper.toJSON();
          var payload = {
            title: String(vm.current.title || '').trim(),
            content: JSON.stringify(json),
            structureData: JSON.stringify(json)
          };
          if (!payload.title) { toast('warning', '请填写文书标题'); return global.Promise ? global.Promise.reject('no-title') : null; }
          vm.saving = true;
          return HIS.put('/api/his/inp/record/' + HIS.idParam(vm.current.id), payload)
            .then(function (saved) {
              if (!silent) { HIS.notifySuccess('草稿已保存'); }
              vm.dirty = false;
              if (saved && saved.id) {
                var merged = Object.assign({}, vm.current, saved);
                merged.id = HIS.id(saved.id);
                /* doctorId 仅在有值时覆盖(防 null 冲掉归属判定) */
                if (saved.doctorId != null) { merged.doctorId = HIS.id(saved.doctorId); }
                vm.current = merged;
              }
              vm.draftSavedText = '已同步 ' + fmtHM(Date.now());
              vm.resetDraftAfterSave();
              vm.loadGroups(false);
              return vm.current;
            })
            .catch(function (e) {
              HIS.notifyError(e);
              return (global.Promise ? global.Promise.reject(e) : null);
            })
            .finally(function () { vm.saving = false; });
        };
        /* 保存前 CDSS 拦截级评估(仅编辑态且启用; 评估失败放行, 由后端兜底) */
        if (!skipCdss && vm.editorWrapper && typeof vm.editorWrapper.evaluateCdss === 'function') {
          return vm.editorWrapper.evaluateCdss().then(function (r) {
            if (r && r.canProceed === false) {
              toast('error', '存在拦截级临床提醒，已阻止保存');
              return (global.Promise ? global.Promise.reject('cdss-block') : null);
            }
            return doPut();
          });
        }
        return doPut();
      },
      /* 提交: 先静默保存, 成功后置为已提交(进入签名链) */
      submitRecord: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { return; }
        if (vm.submitting) { return; }
        confirmBox('提交后文书将进入签名审核流程, 提交前请确认内容完整。', '提交确认', {
          type: 'warning', confirmButtonText: '提交', cancelButtonText: '再改改'
        }).then(function () {
          vm.submitting = true;
          var id = vm.current.id;
          return vm.save(true, false).then(function () {
            return HIS.put('/api/his/inp/record/' + HIS.idParam(id) + '/submit');
          }).then(function () {
            HIS.notifySuccess('文书已提交');
            /* 从刷新后的分组里重新定位该记录并强制重载(状态已变化) */
            return vm.loadGroups(false).then(function () {
              var row = null;
              (vm.groups || []).forEach(function (g) {
                ((g && g.records) || []).forEach(function (r) {
                  if (HIS.sameId(r.id, id)) { row = r; }
                });
              });
              vm.current = null;              /* 解除同 id 短路, 强制重开 */
              if (row) { vm.selectRecord(row); } else { vm.destroyEditor(); }
            });
          });
        }).catch(function (e) {
          var silent = ['cancel', 'close', 'busy', 'cdss-block', 'no-record', 'no-title', 'no-editor', 'readonly'];
          if (silent.indexOf(e) < 0) { HIS.notifyError(e); }
        }).finally(function () { vm.submitting = false; });
      },
      deleteRecord: function () {
        var vm = this;
        if (!vm.current || !vm.current.id || !vm.canDelete) { return; }
        confirmBox('确认删除草稿「' + (vm.current.title || '') + '」? 删除后不可恢复。', '删除确认', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/inp/record/' + HIS.idParam(vm.current.id));
        }).then(function () {
          HIS.notifySuccess('草稿已删除');
          if (vm.draftKey) { HIS.EmrOfflineDraft && HIS.EmrOfflineDraft.deleteDraft(vm.draftKey); }
          vm.destroyEditor();
          vm.current = null;
          return vm.loadGroups(true);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },

      /* ================= 右侧面板: 公共 ================= */
      loadPanelData: function () {
        var vm = this;
        vm.loadPhrases();
        vm.loadTimeline();
        vm.loadReports();
      },
      togglePanel: function () { this.panelVisible = !this.panelVisible; },
      /* 光标处插入文本(Tiptap insertContent, 含换行自动分段) */
      insertAtCursor: function (text) {
        var vm = this;
        if (!vm.editorWrapper || !vm.editorWrapper.editor) { toast('warning', '请先打开病历'); return false; }
        if (!vm.canEdit) { toast('warning', '当前病历为只读模式, 不可插入内容'); return false; }
        try {
          vm.editorWrapper.editor.commands.insertContent(String(text == null ? '' : text));
          vm.dirty = true;
          return true;
        } catch (e) {
          toast('error', '插入失败');
          return false;
        }
      },

      /* ================= 新建对话框(类型 → 模板 → 建档) ================= */
      openCreate: function () {
        var vm = this;
        if (!vm.hasPatient) { toast('warning', '请先从左侧选择住院患者'); return; }
        vm.createVisible = true;
        vm.createStep = 1;
        vm.createType = null;
        if (!vm.tplList.length) { vm.loadTemplates(); }
      },
      /* 全量模板一次拉取(对话框复用, templateCategory 与类型 1-8 对应) */
      loadTemplates: function () {
        var vm = this;
        vm.tplLoading = true;
        HIS.get('/api/his/emr/template/list')
          .then(function (list) { vm.tplList = Array.isArray(list) ? list : []; })
          .catch(function (e) { vm.tplList = []; HIS.notifyError(e); })
          .finally(function () { vm.tplLoading = false; });
      },
      chooseType: function (type) {
        var vm = this;
        if (!vm.canCreateType(type)) {
          toast('warning', '实习医生仅可书写「日常病程记录」');
          return;
        }
        vm.createType = type;
        vm.createStep = 2;
      },
      /* 按模板建档: 后端完成宏解析/时限计算/要素同步, 返回完整记录 */
      createFromTemplate: function (tpl) {
        var vm = this;
        if (!tpl || vm.creating) { return; }
        vm.creating = true;
        HIS.post('/api/his/inp/record/from-template', {
          visitId: HIS.id(vm.inpVisitId),
          templateId: HIS.id(tpl.id)
        }).then(function (rec) {
          vm.createVisible = false;
          HIS.notifySuccess('已按「' + (tpl.templateName || '模板') + '」生成病历, 请完善内容');
          return vm.loadGroups(false).then(function () {
            if (rec && rec.id) { vm.selectRecord(Object.assign({}, rec, { id: HIS.id(rec.id) })); }
            else { vm.autoSelectRecord(); }
          });
        }).catch(HIS.notifyError).finally(function () { vm.creating = false; });
      },
      /* 空白文书建档(无模板类型/自由书写) */
      createBlank: function () {
        var vm = this;
        if (vm.creating) { return; }
        var type = Number(vm.createType) || 3;
        vm.creating = true;
        HIS.post('/api/his/inp/record', {
          inpVisitId: HIS.id(vm.inpVisitId),
          recordType: type,
          title: (RECORD_TYPES[type] || '病历文书') + ' ' + todayText(),
          content: null
        }).then(function (rec) {
          vm.createVisible = false;
          HIS.notifySuccess('空白病历已创建');
          return vm.loadGroups(false).then(function () {
            if (rec && rec.id) { vm.selectRecord(Object.assign({}, rec, { id: HIS.id(rec.id) })); }
            else { vm.autoSelectRecord(); }
          });
        }).catch(HIS.notifyError).finally(function () { vm.creating = false; });
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

      /* ================= Tab2: 历史引用 ================= */
      /* 患者全景时间线(可选能力: 失败降级为仅展示本次病历) */
      loadTimeline: function () {
        var vm = this;
        vm.timeline = []; vm.timelineFailed = false;
        if (!vm.patientId || !HIS.get) { return; }
        vm.timelineLoading = true;
        HIS.get('/api/his/emr/timeline/patient/' + HIS.idParam(vm.patientId))
          .then(function (list) { vm.timeline = Array.isArray(list) ? list : []; })
          .catch(function () { vm.timelineFailed = true; })
          .finally(function () { vm.timelineLoading = false; });
      },
      timelineTypeText: function (t) { return t === 'inpatient' ? '住院' : (t === 'outpatient' ? '门诊' : '记录'); },
      timelineDiag: function (it) {
        var d = (it && it.details) || {};
        return d.diagnosisText || d.admitDiag || d.chiefComplaint || '';
      },
      /* 预览其他病历(只读摘要: 旧 HTML/JSON 提取纯文本) */
      previewQuote: function (row) {
        var vm = this;
        if (!row || row.id == null) { return; }
        vm.quoteVisible = true;
        vm.quoteLoading = true;
        vm.quoteTitle = row.title || '';
        vm.quoteText = '';
        vm.quoteTarget = row;
        HIS.get('/api/his/inp/record/' + HIS.idParam(row.id))
          .then(function (rec) {
            var norm = vm.normalizeContent(rec || {});
            vm.quoteText = docToPlainText(norm.doc || EMPTY_DOC);
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.quoteLoading = false; });
      },
      /* 引用插入: 带来源注释 */
      insertQuote: function (row) {
        var vm = this;
        if (!row || row.id == null) { return; }
        HIS.get('/api/his/inp/record/' + HIS.idParam(row.id))
          .then(function (rec) {
            var norm = vm.normalizeContent(rec || {});
            var text = docToPlainText(norm.doc || EMPTY_DOC);
            var src = '【引用自：' + (row.title || '病历') + ' ' + fmtDT(row.recordTime || rec.recordTime) + '】';
            if (vm.insertAtCursor(src + '\n' + text)) { toast('success', '已插入引用'); }
          })
          .catch(HIS.notifyError);
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
            /* 报告服务不可用: 优雅降级为空态 */
            vm.reportFailed = true; return;
          }
          vm.labReports = Array.isArray(rs[0]) ? rs[0] : [];
          vm.examReports = Array.isArray(rs[1]) ? rs[1] : [];
        }).finally(function () { vm.reportLoading = false; });
      },
      isReportAbnormal: function (r) {
        return !!(r && (Number(r.critical_flag) > 0 || Number(r.abnormal_flag) > 0));
      },
      reportTimeOf: function (r) { return fmtDT(r && (r.report_time || r.reportTime)); },
      reportConclusionOf: function (r) {
        var c = r && (r.conclusion || r.findings);
        return c == null ? '' : String(c);
      },
      /* 报告摘要插入(结论 + 关键异常项) */
      insertReportSummary: function (r) {
        var vm = this;
        if (!r) { return; }
        var lines = [];
        lines.push('【检查报告：' + (r.report_type || r.reportType || '报告') + ' ' + (r.report_no || r.reportNo || '') + '】');
        if (r.report_time || r.reportTime) { lines.push('报告时间：' + vm.reportTimeOf(r)); }
        if (vm.isReportAbnormal(r)) { lines.push('（存在异常标记）'); }
        var c = vm.reportConclusionOf(r);
        if (c) { lines.push('结论：' + c); }
        if (vm.insertAtCursor(lines.join('\n'))) { toast('success', '已插入报告摘要'); }
      },

      /* ================= P8a-3: 引用草药方 ================= */
      /* 打开"引用草药方"对话框: 拉取患者本次就诊的草药处方列表 */
      openHerbFormulas: function () {
        var vm = this;
        vm.herbVisible = true;
        vm.herbSelRow = null;
        vm.herbText = '';
        vm.loadHerbFormulas();
      },
      /* 草药处方列表(端点由 P8a-2 提供; 兼容数组/records/list 信封, 字段命名渐进收敛) */
      loadHerbFormulas: function () {
        var vm = this;
        vm.herbList = [];
        vm.herbLoading = true;
        var q = '/api/his/prescription/herb-formulas?patientId=' + HIS.idParam(vm.patientId)
          + '&visitId=' + HIS.idParam(vm.inpVisitId);
        HIS.get(q).then(function (data) {
          vm.herbList = Array.isArray(data) ? data : ((data && (data.records || data.list)) || []);
        }).catch(function (e) {
          vm.herbList = [];
          if (vm.herbVisible) { HIS.notifyError(e); }
        }).finally(function () { vm.herbLoading = false; });
      },
      /* 行字段访问器: 兼容 P8a-2 最终字段命名(方名/日期/药味数) */
      herbRowId: function (r) { return r ? (r.prescriptionId != null ? r.prescriptionId : r.id) : null; },
      herbRowName: function (r) {
        if (!r) { return '-'; }
        return r.formulaName || r.prescriptionName || r.name || r.title || '草药处方';
      },
      herbRowDate: function (r) {
        if (!r) { return '-'; }
        return fmtDT(r.formulaTime || r.prescriptionTime || r.prescribeTime || r.visitDate || r.createTime);
      },
      herbRowCount: function (r) {
        if (!r) { return '-'; }
        var n = r.herbCount != null ? r.herbCount : (r.itemCount != null ? r.itemCount : (r.count != null ? r.count : r.num));
        return n == null ? '-' : n;
      },
      herbRowClass: function (obj) {
        var row = obj && obj.row;
        var isOn = this.herbSelRow && row && HIS.sameId(this.herbRowId(this.herbSelRow), this.herbRowId(row));
        return isOn ? 'iew-herb-pick iew-herb-on' : 'iew-herb-pick';
      },
      /* 选中行 → 取格式化文本(兼容 string 与 {text|formulaText|content} 信封) */
      pickHerb: function (row) {
        var vm = this;
        if (!row) { return; }
        vm.herbSelRow = row;
        vm.herbText = '';
        var pid = vm.herbRowId(row);
        if (pid == null || pid === '') { return; }
        vm.herbTextLoading = true;
        HIS.get('/api/his/prescription/formula-text?prescriptionId=' + HIS.idParam(pid))
          .then(function (data) {
            if (typeof data === 'string') { vm.herbText = data; return; }
            vm.herbText = data && (data.text || data.formulaText || data.content) ? String(data.text || data.formulaText || data.content) : '';
          })
          .catch(function (e) { vm.herbText = ''; HIS.notifyError(e); })
          .finally(function () { vm.herbTextLoading = false; });
      },
      /* 插入编辑器光标处 */
      insertHerbFormula: function () {
        var vm = this;
        if (!vm.herbText) { toast('warning', '请先选择草药处方'); return; }
        if (vm.insertAtCursor(vm.herbText)) {
          toast('success', '已插入草药方');
          vm.herbVisible = false;
        }
      },

      /* ================= Tab4: CDSS ================= */
      /* 文档级 CDSS 评估(与 emr-editor.js 同端点; 此处独立渲染卡片) */
      evaluateCdssTab: function () {
        var vm = this;
        if (!vm.editorWrapper) {
          if (vm.current) { toast('warning', '编辑器尚未就绪'); }
          return;
        }
        vm.cdssLoading = true; vm.cdssFailed = false;
        HIS.post('/api/his/emr/cdss/evaluate-document', {
          documentJson: vm.editorWrapper.toJSON(),
          recordType: vm.current && vm.current.recordType != null ? String(vm.current.recordType) : '',
          deptCode: vm.deptCode || ''
        }).then(function (list) {
          vm.cdssAlerts = Array.isArray(list) ? list : [];
          vm.cdssEvaluated = true;
        }).catch(function () {
          vm.cdssAlerts = []; vm.cdssFailed = true; vm.cdssEvaluated = true;
        }).finally(function () { vm.cdssLoading = false; });
      },
      cdssLevelOf: function (a) {
        var lv = a && a.level;
        return (lv === 'block' || lv === 'warning' || lv === 'info') ? lv : 'info';
      },
      cdssLevelText: function (lv) {
        return lv === 'block' ? '拦截' : (lv === 'warning' ? '警告' : '信息');
      },
      isHttpLink: function (s) { return /^https?:\/\//i.test(String(s || '')); },

      /* ================= Tab5: 签名 ================= */
      /* 规则链 + 实际状态并行拉取(任一失败保持空态) */
      loadSignInfo: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { vm.signStatus = null; vm.signRules = []; vm.caSignList = []; return; }
        vm.signLoading = true;
        var rt = vm.current.recordType != null ? Number(vm.current.recordType) : 0;
        global.Promise.all([
          HIS.get('/api/emr/sign/chain/' + rt).catch(function () { return []; }),
          HIS.get('/api/emr/sign/status/1/' + HIS.idParam(vm.current.id)).catch(function () { return null; })
        ]).then(function (rs) {
          vm.signRules = Array.isArray(rs[0]) ? rs[0] : [];
          vm.signStatus = rs[1] || null;
        }).finally(function () { vm.signLoading = false; });
        vm.loadCaSignInfo();   /* P8b-2: 多方式签名记录(方式/验签标签) */
      },
      /* P8b-2 签名记录(多方式签名与验签状态); 失败静默(签名表扩展列未迁移时详情仍可用) */
      loadCaSignInfo: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { vm.caSignList = []; return global.Promise ? global.Promise.resolve() : null; }
        vm.caSignLoading = true;
        return HIS.get('/api/emr/ca-sign/sign-info/' + HIS.idParam(vm.current.id))
          .then(function (list) { vm.caSignList = Array.isArray(list) ? list : []; })
          .catch(function () { vm.caSignList = []; })
          .finally(function () { vm.caSignLoading = false; });
      },
      stageText: function (s) { return STAGE_LABELS[s] || s || '-'; },
      /* 阶段可签预判: 职称档位(后端规则链校验为准); 实习医生不可签任何阶段 */
      canSignStage: function (stage) {
        if (!this.current || !this.current.id) { return false; }
        if (this.myTitleCode === null) { return false; }
        if (String(this.myTitleCode) === '5') { return false; }
        if (stage === 'author') {
          /* 书写医师: 本人 */
          return !!(this.myStaffId && this.current.doctorId != null && HIS.sameId(this.current.doctorId, this.myStaffId));
        }
        var max = STAGE_TITLE_MAX[stage];
        if (max == null) { return true; }
        if (this.myTitleCode === '') { return true; }      /* 职称未知放行, 后端兜底 */
        return Number(this.myTitleCode) <= max;
      },
      /* 规则驱动签署(文字签名, 原有 EmrSignatureService 流程): 先 SignaturePad 捕获手写(可用时),
       * 再 POST /rule?stage=; P8b-2 起由签名方式下拉触发 */
      doSignRule: function (stage) {
        var vm = this;
        if (!vm.current || !vm.current.id || vm.signing) { return; }
        if (!vm.canSignStage(stage)) { toast('warning', '当前职称不可签署该环节'); return; }
        var apply = function (signImg) {
          vm.signing = true;
          return HIS.post('/api/emr/sign/1/' + HIS.idParam(vm.current.id) + '/rule?stage=' + encodeURIComponent(stage),
            signImg ? { signImg: signImg } : {})
            .then(function (res) { return vm.handleSignResult(res, stage); })
            .catch(HIS.notifyError)
            .finally(function () { vm.signing = false; });
        };
        if (HIS.SignaturePad && typeof HIS.SignaturePad.open === 'function') {
          HIS.SignaturePad.open({ actionType: 'emr_sm2_sign', refType: 'medical_record', refId: HIS.id(vm.current.id) })
            .then(function (r) { return apply(r && r.signImgUrl); })
            .catch(function (e) {
              if (e === 'cancelled') { toast('info', '已取消签名'); }
              else if (e !== 'busy') { HIS.notifyError(e); }
            });
        } else {
          apply(null);
        }
      },
      /* P5a-4/P8b-2 签名响应统一处理: qcResult 三档(禁止/拦截/提醒) + 成功提示 + 状态与签名记录刷新;
       * 三种签名方式(文字规则链/手写 sign-image/CA sign-ca)共用 */
      handleSignResult: function (res, stage) {
        var vm = this;
        /* P5a-4: 签名响应附带质控结果(qcResult 兼容顶层/信封 data 两种形态) */
        var qc = res && (res.qcResult || (res.data && res.data.qcResult));
        if (qc && Array.isArray(qc.forbidden) && qc.forbidden.length) {
          vm.qcForbidden = qc.forbidden; vm.qcBlocks = qc.blocks || []; vm.qcWarnings = qc.warnings || [];
          vm.activeTab = 'qc';
          toast('error', '签名被禁止: ' + qc.forbidden.map(function (d) { return d.ruleName || d.ruleCode || '质控规则'; }).join('、'));
          return null;
        }
        if (qc && Array.isArray(qc.blocks) && qc.blocks.length) {
          vm.qcForbidden = qc.forbidden || []; vm.qcBlocks = qc.blocks; vm.qcWarnings = qc.warnings || [];
          vm.showQcPanel = true;
          toast('warning', '存在 ' + qc.blocks.length + ' 项拦截级缺陷, 请整改后重新签名');
          return null;
        }
        if (qc && Array.isArray(qc.warnings) && qc.warnings.length) {
          vm.qcForbidden = []; vm.qcBlocks = []; vm.qcWarnings = qc.warnings;
          var EP = global.ElementPlus;
          if (EP && EP.ElNotification) {
            EP.ElNotification({ title: '质控提醒', message: '存在 ' + qc.warnings.length + ' 项待整改缺陷, 详见右侧「质控」页签', type: 'warning', duration: 5000 });
          }
        }
        var done = res && res.allComplete;
        var extra = res && res.signModeName ? ' · ' + res.signModeName : '';   /* P8b-2: 响应附签名方式名 */
        HIS.notifySuccess(vm.stageText(stage) + '签名完成' + extra + (done ? ', 签名链已闭环' : ''));
        return vm.loadSignInfo();
      },
      /* P8b-2 签名方式下拉指令分发: text=原规则链(文字) / image=ca-sign sign-image(手写) / ca=PIN 码弹窗 */
      onSignCommand: function (mode, stage) {
        var vm = this;
        if (mode === 'image') { vm.doSignImage(stage); return; }
        if (mode === 'ca') { vm.openCaSign(stage); return; }
        vm.doSignRule(stage);
      },
      /* P8b-2 手写签名: SignaturePad 捕获手写图(载荷 signImg 为 base64) → POST /api/emr/ca-sign/sign-image */
      doSignImage: function (stage) {
        var vm = this;
        if (!vm.current || !vm.current.id || vm.signing) { return; }
        if (!vm.canSignStage(stage)) { toast('warning', '当前职称不可签署该环节'); return; }
        if (!HIS.SignaturePad || typeof HIS.SignaturePad.open !== 'function') {
          toast('warning', '签名板组件未加载(signature-pad.js)');
          return;
        }
        HIS.SignaturePad.open({ actionType: 'emr_ca_image_sign', refType: 'medical_record', refId: HIS.id(vm.current.id) })
          .then(function (r) {
            /* SignaturePad 成功载荷: signImg(手写 base64, P8b-2 新增; 预设签名时为 URL 均可提交) */
            var img = r && (r.signImg || r.signImgUrl);
            if (!img) { toast('warning', '未获取到手写签名图'); return null; }
            vm.signing = true;
            return HIS.post('/api/emr/ca-sign/sign-image', {
              recordId: HIS.id(vm.current.id),
              signerId: vm.myStaffId ? HIS.id(vm.myStaffId) : null,
              imageBase64: img
            }).then(function (res) { return vm.handleSignResult(res, stage); });
          })
          .catch(function (e) {
            if (e === 'cancelled') { toast('info', '已取消签名'); }
            else if (e !== 'busy') { HIS.notifyError(e); }
          })
          .finally(function () { vm.signing = false; });
      },
      /* P8b-2 CA数字签名: 打开 PIN 码弹窗(模拟模式 certSn=SIMULATED, 算法 SM2) */
      openCaSign: function (stage) {
        var vm = this;
        if (!vm.current || !vm.current.id) { return; }
        if (!vm.canSignStage(stage)) { toast('warning', '当前职称不可签署该环节'); return; }
        vm.caStage = stage;
        vm.caPin = '';
        vm.caVisible = true;
        vm.$nextTick(function () {
          var i = vm.$refs.caPinInput;
          if (i && i.focus) { i.focus(); }
        });
      },
      /* CA 确认签名; 预留 window.caClientCallback 回调点(外部注入时由真实 UKey 客户端交互,
       * done(err, {certSn, signatureAlgorithm}) 回传证书信息, 未注入走模拟模式 SIMULATED/SM2) */
      submitCaSign: function () {
        var vm = this;
        if (!vm.current || !vm.current.id || vm.signing) { return; }
        if (!vm.caPin) { toast('warning', '请输入UKey PIN码'); return; }
        var stage = vm.caStage;
        var finish = function (certSn, algo) {
          vm.signing = true;
          HIS.post('/api/emr/ca-sign/sign-ca', {
            recordId: HIS.id(vm.current.id),
            signerId: vm.myStaffId ? HIS.id(vm.myStaffId) : null,
            certSn: certSn || 'SIMULATED',
            signatureAlgorithm: algo || 'SM2'
          }).then(function (res) {
            vm.caVisible = false;
            return vm.handleSignResult(res, stage);
          }).catch(HIS.notifyError).finally(function () { vm.signing = false; });
        };
        if (typeof global.caClientCallback === 'function') {
          try {
            global.caClientCallback({
              recordId: HIS.id(vm.current.id),
              stage: stage,
              pin: vm.caPin,
              done: function (err, cert) {
                if (err) { toast('error', 'CA客户端交互失败: ' + (err && err.message ? err.message : err)); return; }
                finish(cert && cert.certSn, cert && cert.signatureAlgorithm);
              }
            });
          } catch (e) { toast('error', 'CA客户端调用异常'); }
        } else {
          finish('SIMULATED', 'SM2');
        }
      },
      /* P8b-2 单条验签: POST verify/{signatureId}, 结果回写 verifyResult/verifyTime 后刷新标签 */
      verifyCaSign: function (sig) {
        var vm = this;
        if (!sig || sig.id == null || vm.verifyLoadingId != null) { return; }
        vm.verifyLoadingId = sig.id;
        HIS.post('/api/emr/ca-sign/verify/' + HIS.idParam(sig.id))
          .then(function (r) {
            if (r && r.message) { toast(r.passed === false ? 'error' : 'success', r.message); }
            return vm.loadCaSignInfo();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.verifyLoadingId = null; });
      },
      /* P8b-2 患者/家属签名留存: 双签名板(患者无法签字时仅家属) → POST patient-sign/{signatureId} */
      patientSign: function (sig) {
        var vm = this;
        if (!sig || sig.id == null || vm.signing) { return; }
        if (!HIS.SignaturePad || typeof HIS.SignaturePad.openPatientSign !== 'function') {
          toast('warning', '签名板组件未加载或不支持患者签名');
          return;
        }
        HIS.SignaturePad.openPatientSign({
          title: '患者/家属签名 · ' + vm.stageText(sig.stage),
          onSave: function (imgs) {
            vm.signing = true;
            HIS.post('/api/emr/ca-sign/patient-sign/' + HIS.idParam(sig.id), {
              patientImage: (imgs && imgs.patientImage) || null,
              familyImage: (imgs && imgs.familyImage) || null
            }).then(function () {
              HIS.notifySuccess('患者/家属签名已留存');
              return vm.loadCaSignInfo();
            }).catch(HIS.notifyError).finally(function () { vm.signing = false; });
          }
        });
      },
      /* 签名方式标签: signMode 1文字/2手写(图片)/3CA(与 his_emr_signature.sign_mode 同口径) */
      signModeText: function (m) {
        var n = Number(m);
        return n === 3 ? 'CA签名' : (n === 2 ? '手写签名' : '文字签名');
      },
      signModeTag: function (m) {
        var n = Number(m);
        return n === 3 ? '' : (n === 2 ? 'warning' : 'info');
      },
      /* 验签状态标签: verifyResult 0未验(灰)/1已验(绿)/2验证失败(红) */
      verifyTextOf: function (v) {
        var n = Number(v);
        return n === 1 ? '已验' : (n === 2 ? '验证失败' : '未验');
      },
      verifyTagOf: function (v) {
        var n = Number(v);
        return n === 1 ? 'success' : (n === 2 ? 'danger' : 'info');
      },
      goSignTab: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { toast('warning', '请先保存草稿'); return; }
        vm.panelVisible = true;
        vm.activeTab = 'sign';
        vm.loadSignInfo();
      },
      /* P5a-4: 整改后重签(关闭拦截抽屉并定位签名页签) */
      reSignAfterRectify: function () {
        this.showQcPanel = false;
        this.goSignTab();
      },

      /* ================= P5b-4: 质控页签(SSE 联动 + 缺陷记录 + 时效倒计时) ================= */
      /* 切换病历/就诊时重置页签数据(qcLoaded 归零, 下次切入重新拉取) */
      resetQcTabData: function () {
        this.qcDefects = [];
        this.qcLoaded = false;
        this.timeliness = null;
        this.timelinessAt = 0;
      },
      /* SSE 质控事件处理: 信封 data.recordId 与当前病历匹配才刷新页签;
       * 整改通知/申诉结果直接弹窗(可能与当前病历无关, 需即时知晓), 其余仅刷新 */
      onQcSseEvent: function (env) {
        var vm = this;
        var data = (env && env.data && typeof env.data === 'object') ? env.data : {};
        var msg = data.message || data.msg || '';
        var EP = global.ElementPlus;
        if (typeIsNoticeOrAppeal(env)) {
          if (EP && EP.ElNotification) {
            var isAppeal = env.type === 'EMR_QC_APPEAL_RESULT';
            EP.ElNotification({
              title: isAppeal ? '申诉结果' : '整改通知',
              message: msg || (isAppeal ? '缺陷申诉已审核, 请查看质控结果' : '收到整改通知单, 请及时整改缺陷'),
              type: isAppeal ? 'info' : 'warning',
              duration: 0,
              position: 'top-right'
            });
          }
        }
        /* recordId 未携带或不匹配当前病历时不刷新页签(避免误覆盖其它病历的数据;
         * 后端 String.valueOf(null) 会产出 "null" 字符串, 一并排除) */
        if (!vm.current || !vm.current.id || data.recordId == null || data.recordId === 'null') { return; }
        if (!HIS.sameId(HIS.id(data.recordId), vm.current.id)) { return; }
        vm.refreshQcData();
        /* 时效预警/超时: 若质控页签未展开, 切过去让医生第一时间看到缺陷明细 */
        if (!typeIsNoticeOrAppeal(env) && vm.activeTab !== 'qc') { vm.activeTab = 'qc'; vm.panelVisible = true; }
      },
      /* 并行拉取缺陷记录 + 时效检查(独立 catch, 一个失败不拖垮另一个) */
      refreshQcData: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { return; }
        var rid = HIS.idParam(vm.current.id);
        vm.qcDefectLoading = true;
        vm.timelinessLoading = true;
        HIS.get('/api/his/emr/quality/defects/' + rid)
          .then(function (list) { vm.qcDefects = Array.isArray(list) ? list : []; vm.qcLoaded = true; })
          .catch(function () { vm.qcDefects = []; vm.qcLoaded = true; })
          .finally(function () { vm.qcDefectLoading = false; });
        HIS.get('/api/his/emr/timeliness/check/' + rid)
          .then(function (t) {
            vm.timeliness = (t && t.deadlineTime) ? t : null;
            vm.timelinessAt = Date.now();
          })
          .catch(function () { vm.timeliness = null; vm.timelinessAt = 0; })
          .finally(function () { vm.timelinessLoading = false; });
      },
      /* 剩余分钟 = 后端检查时的剩余值 - 距今流逝分钟(依赖 nowTs 每分钟跳动, 倒计时感) */
      remainMinutes: function () {
        var t = this.timeliness;
        if (!t || t.remainingMinutes == null || !this.timelinessAt) { return null; }
        var elapsed = (this.nowTs - this.timelinessAt) / 60000;
        return Number(t.remainingMinutes) - elapsed;
      },
      /* 缺陷严重度(1提醒/2拦截/3禁止)→ 文案与样式档 */
      severityTextOf: function (v) {
        var s = Number(v);
        return s === 3 ? '禁止' : (s === 2 ? '拦截' : (s === 1 ? '提醒' : '未知'));
      },
      severityTagOf: function (v) {
        var s = Number(v);
        return s === 3 ? 'danger' : (s === 2 ? 'warning' : 'info');
      },
      /* 缺陷整改状态(0未整改/1已整改/2已申诉/3申诉驳回/4豁免)→ 文案与样式档 */
      defectStatusTextOf: function (v) {
        var s = Number(v);
        return s === 1 ? '已整改' : (s === 2 ? '已申诉' : (s === 3 ? '申诉驳回' : (s === 4 ? '已豁免' : '未整改')));
      },
      defectStatusTagOf: function (v) {
        var s = Number(v);
        return s === 1 ? 'success' : (s === 2 ? 'warning' : (s === 3 ? 'danger' : (s === 4 ? 'info' : 'danger')));
      },

      /* ================= Tab6: 版本 ================= */
      loadVersions: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { vm.versions = []; vm.versionSel = []; return; }
        vm.versionLoading = true;
        HIS.get('/api/his/inp/record/' + HIS.idParam(vm.current.id) + '/versions')
          .then(function (list) { vm.versions = Array.isArray(list) ? list : []; })
          .catch(function () { vm.versions = []; })
          .finally(function () { vm.versionLoading = false; });
      },
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
      operateTypeText: function (t) { return OPERATE_TYPES[t] || (t || '-'); },
      /* 双版本对比(版本号小者为基线) */
      diffSelected: function () {
        var vm = this;
        if (vm.versionSel.length !== 2) { return; }
        var v1 = vm.versionSel[0], v2 = vm.versionSel[1];
        var a = vm.versionOf(v1), b = vm.versionOf(v2);
        if (a && b && Number(a.versionNo) > Number(b.versionNo)) { v1 = vm.versionSel[1]; v2 = vm.versionSel[0]; }
        vm.diffData = null; vm.diffVisible = true; vm.diffLoading = true;
        HIS.get('/api/his/inp/record/version/diff?v1=' + HIS.idParam(v1) + '&v2=' + HIS.idParam(v2))
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
        HIS.get('/api/his/inp/record/version/' + HIS.idParam(v.id))
          .then(function (d) {
            var snap = (d && (d.contentSnapshot || d.structureSnapshot)) || '';
            vm.versionPreview = plainTextOf(snap);
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.versionPreviewLoading = false; });
      },

      /* ================= 头栏动作 ================= */
      printRecord: function () {
        var vm = this;
        if (!vm.current || !vm.current.id) { toast('warning', '请先保存草稿后再打印'); return; }
        /* 优先编辑器本地直渲染(Tiptap) */
        if (vm.editorWrapper && typeof vm.editorWrapper.print === 'function') {
          try { vm.editorWrapper.print({ title: vm.current.title || '病历打印' }); return; } catch (e) { /* 回退后端 */ }
        }
        HIS.get('/api/his/inp/print/render/emr?recordId=' + HIS.idParam(vm.current.id))
          .then(function (html) {
            if (!html) { toast('warning', '打印模板暂无内容'); return; }
            if (typeof HIS.printHtmlFrame === 'function') { HIS.printHtmlFrame(html, vm.current.title || '病历打印'); }
            else if (typeof HIS.openPrintWindow === 'function') { HIS.openPrintWindow(vm.current.title || '病历打印', html); }
            else { toast('warning', '打印组件不可用'); }
          })
          .catch(HIS.notifyError);
      },
      openTimeline: function () {
        this.$emit('open-timeline', this.patientId != null ? HIS.id(this.patientId) : null);
      }
    },

    /* ================= 模板(字符串数组拼接, 无模板字符串) ================= */
    template: [
      '<div class="iew-root">',
      /* ---- 无患者空态 ---- */
      '  <div v-if="!hasPatient" class="iew-empty-root">',
      '    <span v-html="icons.user"></span>',
      '    <div class="iew-empty-root-text">请先从左侧选择住院患者</div>',
      '  </div>',
      /* ---- 主体 ---- */
      '  <template v-else>',
      '    <div class="iew-header">',
      '      <div class="iew-patient">',
      '        <span class="iew-patient-name">{{ patientLabel || "-" }}</span>',
      '        <span class="iew-patient-meta">',
      '          <span v-if="bedNo">床位 {{ bedNo }}</span>',
      '          <span v-if="patient && patient.inpNo">住院号 {{ patient.inpNo }}</span>',
      '          <span v-if="patient && patient.genderText">{{ patient.genderText }}</span>',
      '          <span v-if="patient && patient.ageText">{{ patient.ageText }}</span>',
      '        </span>',
      '      </div>',
      '      <div class="iew-header-actions">',
      '        <el-button size="small" type="primary" @click="openCreate"><span class="iew-icon" v-html="icons.plus"></span> 新建病历</el-button>',
      '        <el-button size="small" :disabled="!canEdit || saving" :loading="saving" @click="save(false, false)"><span class="iew-icon" v-html="icons.save"></span> 保存</el-button>',
      '        <el-button size="small" type="success" :disabled="!canSubmit || submitting" :loading="submitting" @click="submitRecord"><span class="iew-icon" v-html="icons.send"></span> 提交</el-button>',
      '        <el-button size="small" type="warning" plain v-if="canSignNow" @click="goSignTab"><span class="iew-icon" v-html="icons.pen"></span> 签名</el-button>',
      '        <el-button size="small" @click="printRecord"><span class="iew-icon" v-html="icons.printer"></span> 打印</el-button>',
      '        <el-button size="small" :disabled="!canEdit" @click="openHerbFormulas"><span class="iew-icon" v-html="icons.leaf"></span> 引用药方</el-button>',
      '        <el-button size="small" @click="openTimeline"><span class="iew-icon" v-html="icons.clock"></span> 全景时间线</el-button>',
      '        <el-button size="small" text @click="togglePanel" :title="panelVisible ? \'收起助手\' : \'展开助手\'"><span class="iew-icon" v-html="icons.panel"></span></el-button>',
      '      </div>',
      '    </div>',
      '    <div class="iew-body">',
      /* ---- 左栏: 病历文件夹 ---- */
      '      <div class="iew-left">',
      '        <div class="iew-left-head">',
      '          <el-button class="iew-new-btn" size="small" type="primary" plain @click="openCreate"><span class="iew-icon" v-html="icons.plus"></span> 新建病历</el-button>',
      '          <el-input v-model="searchKey" size="small" clearable placeholder="搜索病历标题">',
      '            <template #prefix><span class="iew-icon" v-html="icons.search"></span></template>',
      '          </el-input>',
      '        </div>',
      '        <div class="iew-groups" v-loading="listLoading">',
      '          <div v-if="!filteredGroups.length && !listLoading" class="iew-list-empty">{{ searchKey ? "未找到匹配病历" : "暂无病历，点击新建病历开始书写" }}</div>',
      '          <div v-for="g in filteredGroups" :key="g.key">',
      '            <div class="iew-group-head" :class="{ \'is-open\': isGroupOpen(g.key) }" @click="toggleGroup(g.key)">',
      '              <span class="iew-group-arrow" v-html="icons.chevron"></span>',
      '              <span>{{ g.name }}</span>',
      '              <span class="iew-group-count">{{ g.count }}</span>',
      '            </div>',
      '            <div v-show="isGroupOpen(g.key)">',
      '              <div v-for="r in g.records" :key="r.id" class="iew-rec" :class="{ \'is-active\': isActiveRow(r) }" @click="selectRecord(r)">',
      '                <div class="iew-rec-title">',
      '                  <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">{{ r.title }}</span>',
      '                  <el-tag size="small" :type="statusTagOf(r.status)" disable-transitions>{{ statusTextOf(r.status) }}</el-tag>',
      '                </div>',
      '                <div class="iew-rec-meta">',
      '                  <span>{{ r.typeLabel || typeLabelOf(r.recordType) }}</span>',
      '                  <span>{{ (r.recordTime || "").substring(0, 10) }}</span>',
      '                  <span v-if="deadlineOf(r)" :class="deadlineOf(r).cls">⏰ {{ deadlineOf(r).text }}</span>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      /* ---- 中栏: 编辑器 ---- */
      '      <div class="iew-center">',
      '        <div v-if="!current && !editorLoading" class="iew-empty-inner">',
      '          <span v-html="icons.file"></span>',
      '          <div class="iew-empty-root-text">{{ totalRecords ? "请从左侧选择病历" : "暂无病历，点击新建病历开始书写" }}</div>',
      '        </div>',
      '        <div v-else class="iew-editor-zone">',
      '          <div v-if="legacyMode" class="iew-legacy"><span class="iew-icon" v-html="icons.alert"></span> 该病历为旧版格式内容，已转换为纯文本段落，请核对后保存升级为新版结构化文书</div>',
      '          <div v-if="!current" class="iew-loading-box" v-loading="true" element-loading-text="加载病历…"></div>',
      '          <template v-else>',
      '            <div ref="toolbarHost" class="iew-toolbar-host" v-loading="editorLoading" element-loading-text="加载编辑器…"></div>',
      '            <div ref="editorHost" class="iew-editor-host"></div>',
      '          </template>',
      '          <div v-if="current" class="iew-statusbar">',
      '            <span class="iew-statusbar-title">{{ current.title }}</span>',
      '            <el-tag size="small" :type="statusTagOf(current.status)" disable-transitions>{{ statusTextOf(current.status) }}</el-tag>',
      '            <span>{{ typeLabelOf(current.recordType) }}</span>',
      '            <span v-if="canEdit" :class="dirty ? \'iew-dirty\' : \'\'">{{ dirty ? "● 有未保存修改 (Ctrl+S)" : "○ 已同步" }}</span>',
      '            <span v-else>只读模式</span>',
      '            <span v-if="draftSavedText" class="iew-draft-chip">离线草稿 {{ draftSavedText }}</span>',
      '            <span v-if="currentDeadline === \'over\'" class="iew-rec-dl">⏰ 已超书写时限</span>',
      '            <span v-else-if="currentDeadline === \'warn\'" class="iew-rec-dl is-warn">⏰ 临书写时限</span>',
      '          </div>',
      '        </div>',
      '      </div>',
      /* ---- 右栏: 助手面板 ---- */
      '      <div class="iew-right" :class="{ \'is-collapsed\': !panelVisible }">',
      '        <el-tabs v-model="activeTab">',
      /* Tab1 常用语 */
      '          <el-tab-pane name="phrase">',
      '            <template #label><span class="iew-icon" v-html="icons.message"></span> 常用语</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-tab-tool">',
      '                <el-select v-model="phraseCategory" size="small" style="width:110px" @change="loadPhrases">',
      '                  <el-option v-for="c in phraseCategories" :key="c.value" :label="c.label" :value="c.value"></el-option>',
      '                </el-select>',
      '                <el-input v-model="phraseKeyword" size="small" clearable placeholder="筛选" style="flex:1"></el-input>',
      '                <el-button size="small" text @click="loadPhrases"><span class="iew-icon" v-html="icons.refresh"></span></el-button>',
      '              </div>',
      '              <div v-loading="phraseLoading" style="min-height:60px">',
      '                <div v-if="!filteredPhrases.length && !phraseLoading" class="iew-tab-empty">暂无常用语</div>',
      '                <div v-for="p in filteredPhrases" :key="p.id" class="iew-phrase" @click="usePhrase(p)" title="点击插入光标处">',
      '                  <div class="iew-phrase-text">{{ p.content }}</div>',
      '                  <div class="iew-phrase-foot">',
      '                    <span>{{ phraseCategoryLabel(p.category) }}</span>',
      '                    <span class="iew-phrase-use">已用 {{ p.usageCount || 0 }} 次</span>',
      '                  </div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* Tab2 历史引用 */
      '          <el-tab-pane name="quote">',
      '            <template #label><span class="iew-icon" v-html="icons.history"></span> 引用</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-quote-sec">',
      '                <div class="iew-quote-sec-title"><span class="iew-icon" v-html="icons.folder"></span> 本次住院病历 ({{ otherRecords.length }})</div>',
      '                <div v-if="!otherRecords.length" class="iew-tab-empty">暂无可引用病历</div>',
      '                <div v-for="r in otherRecords" :key="r.id" class="iew-quote-card">',
      '                  <div class="iew-quote-card-title">{{ r.title }}</div>',
      '                  <div class="iew-quote-card-meta"><span>{{ r.typeLabel || typeLabelOf(r.recordType) }}</span><span>{{ (r.recordTime || "").substring(0, 10) }}</span><el-tag size="small" :type="statusTagOf(r.status)" disable-transitions>{{ statusTextOf(r.status) }}</el-tag></div>',
      '                  <div class="iew-quote-card-ops">',
      '                    <el-button size="small" text type="primary" @click="previewQuote(r)">预览</el-button>',
      '                    <el-button size="small" text type="primary" @click="insertQuote(r)">插入引用</el-button>',
      '                  </div>',
      '                </div>',
      '              </div>',
      '              <div class="iew-quote-sec">',
      '                <div class="iew-quote-sec-title"><span class="iew-icon" v-html="icons.clock"></span> 历史就诊</div>',
      '                <div v-if="timelineFailed" class="iew-tab-empty">时间线服务暂不可用</div>',
      '                <div v-else-if="!timeline.length && !timelineLoading" class="iew-tab-empty">暂无历史就诊记录</div>',
      '                <div v-for="(it, i) in timeline" :key="i" class="iew-quote-card">',
      '                  <div class="iew-quote-card-title">{{ timelineTypeText(it.type) }} · {{ (it.deptName || "-") }}</div>',
      '                  <div class="iew-quote-card-meta">',
      '                    <span>{{ fmtDT(it.time) }}</span>',
      '                    <span v-if="it.doctorName">{{ it.doctorName }}</span>',
      '                  </div>',
      '                  <div v-if="timelineDiag(it)" class="iew-quote-line">诊断/主诉：{{ timelineDiag(it) }}</div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* Tab3 检查报告 */
      '          <el-tab-pane name="report">',
      '            <template #label><span class="iew-icon" v-html="icons.clipboard"></span> 报告</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-tab-tool">',
      '                <el-radio-group v-model="reportTab" size="small">',
      '                  <el-radio-button label="lab">检验</el-radio-button>',
      '                  <el-radio-button label="exam">检查</el-radio-button>',
      '                </el-radio-group>',
      '                <el-button size="small" text @click="loadReports"><span class="iew-icon" v-html="icons.refresh"></span></el-button>',
      '              </div>',
      '              <div v-loading="reportLoading" style="min-height:60px">',
      '                <div v-if="reportFailed" class="iew-tab-empty">报告服务暂不可用</div>',
      '                <div v-else-if="reportTab === \'lab\' && !labReports.length && !reportLoading" class="iew-tab-empty">暂无检验报告</div>',
      '                <div v-else-if="reportTab === \'exam\' && !examReports.length && !reportLoading" class="iew-tab-empty">暂无检查报告</div>',
      '                <template v-if="reportTab === \'lab\'">',
      '                  <div v-for="r in labReports" :key="r.id" class="iew-report-card" :class="{ \'is-abnormal\': isReportAbnormal(r) }">',
      '                    <div class="iew-report-head"><span class="iew-report-name">{{ r.report_type || r.reportType || "检验报告" }}</span><el-tag v-if="isReportAbnormal(r)" size="small" type="danger" disable-transitions>异常</el-tag></div>',
      '                    <div class="iew-report-meta">{{ r.report_no || r.reportNo || "-" }} · {{ reportTimeOf(r) }}</div>',
      '                    <div v-if="reportConclusionOf(r)" class="iew-report-text">{{ reportConclusionOf(r) }}</div>',
      '                    <div><el-button size="small" text type="primary" @click="insertReportSummary(r)">插入摘要</el-button></div>',
      '                  </div>',
      '                </template>',
      '                <template v-else>',
      '                  <div v-for="r in examReports" :key="r.id" class="iew-report-card" :class="{ \'is-abnormal\': isReportAbnormal(r) }">',
      '                    <div class="iew-report-head"><span class="iew-report-name">{{ r.report_type || r.reportType || "检查报告" }}</span><el-tag v-if="isReportAbnormal(r)" size="small" type="danger" disable-transitions>异常</el-tag></div>',
      '                    <div class="iew-report-meta">{{ r.report_no || r.reportNo || "-" }} · {{ reportTimeOf(r) }}</div>',
      '                    <div v-if="reportConclusionOf(r)" class="iew-report-text">{{ reportConclusionOf(r) }}</div>',
      '                    <div><el-button size="small" text type="primary" @click="insertReportSummary(r)">插入摘要</el-button></div>',
      '                  </div>',
      '                </template>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* Tab4 CDSS */
      '          <el-tab-pane name="cdss">',
      '            <template #label><span class="iew-icon" v-html="icons.activity"></span> CDSS</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-tab-tool">',
      '                <div class="iew-cdss-summary">',
      '                  <span class="iew-cdss-chip is-block">拦截 {{ cdssCounts.block }}</span>',
      '                  <span class="iew-cdss-chip is-warning">警告 {{ cdssCounts.warning }}</span>',
      '                  <span class="iew-cdss-chip is-info">信息 {{ cdssCounts.info }}</span>',
      '                </div>',
      '                <el-button size="small" text style="margin-left:auto" @click="evaluateCdssTab"><span class="iew-icon" v-html="icons.refresh"></span> 评估</el-button>',
      '              </div>',
      '              <div v-loading="cdssLoading" style="min-height:60px">',
      '                <div v-if="cdssFailed" class="iew-tab-empty">临床提醒服务不可用</div>',
      '                <div v-else-if="!cdssAlerts.length && !cdssLoading" class="iew-tab-empty">{{ cdssEvaluated ? "未发现需关注的临床提醒" : "点击评估检查当前文书" }}</div>',
      '                <div v-for="(a, i) in cdssAlerts" :key="(a && a.ruleId) || i" class="iew-cdss-alert" :class="\'is-\' + cdssLevelOf(a)">',
      '                  <div class="iew-cdss-alert-name">{{ cdssLevelOf(a) === \'block\' ? \'🔴\' : (cdssLevelOf(a) === \'warning\' ? \'🟡\' : \'🔵\') }} {{ a.ruleName || a.ruleCode || "临床提醒" }}<span class="iew-cdss-level">{{ cdssLevelText(cdssLevelOf(a)) }}</span></div>',
      '                  <div v-if="a.message" class="iew-cdss-msg">{{ a.message }}</div>',
      '                  <a v-if="isHttpLink(a.knowledgeSource)" class="iew-cdss-src" :href="a.knowledgeSource" target="_blank" rel="noopener">知识来源</a>',
      '                  <div v-else-if="a.knowledgeSource" class="iew-cdss-msg">知识来源：{{ a.knowledgeSource }}</div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* Tab5 签名 */
      '          <el-tab-pane name="sign">',
      '            <template #label><span class="iew-icon" v-html="icons.pen"></span> 签名</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-tab-tool">',
      '                <span style="font-size:12px;color:var(--yb-ink-3,#5a6a7e)">签名规则链</span>',
      '                <el-button size="small" text style="margin-left:auto" @click="loadSignInfo"><span class="iew-icon" v-html="icons.refresh"></span></el-button>',
      '              </div>',
      '              <div v-loading="signLoading" style="min-height:80px">',
      '                <div v-if="!signSteps.length" class="iew-tab-empty">{{ current ? "当前病历类型未配置签名规则链" : "请先打开病历" }}</div>',
      '                <div v-else class="iew-sign-steps">',
      '                  <div v-for="(s, i) in signSteps" :key="(s.stage || i)" class="iew-sign-step" :class="{ \'is-done\': s.completed, \'is-pending\': !s.completed }">',
      '                    <div class="iew-sign-rail">',
      '                      <div class="iew-sign-dot"><span v-if="s.completed" v-html="icons.check"></span><span v-else>{{ i + 1 }}</span></div>',
      '                      <div v-if="i < signSteps.length - 1" class="iew-sign-line"></div>',
      '                    </div>',
      '                    <div class="iew-sign-main">',
      '                      <div class="iew-sign-name">{{ stageText(s.stage) }}<el-tag v-if="s.completed" size="small" type="success" disable-transitions>已签</el-tag><el-tag v-else-if="s.required === false" size="small" type="info" disable-transitions>可选</el-tag></div>',
      '                      <div class="iew-sign-info">{{ s.completed ? ((s.signerName || "-") + " · " + fmtDT(s.signTime)) : "待签署" }}</div>',
      '                      <div v-if="!s.completed && s.required !== false && current && current.id" style="margin-top:4px">',
      '                        <el-dropdown trigger="click" @command="onSignCommand($event, s.stage)">',
      '                          <el-button size="small" type="primary" plain :disabled="!canSignStage(s.stage) || signing" :loading="signing && pendingStage && pendingStage.stage === s.stage">签署方式 <span class="iew-icon iew-dd-caret" v-html="icons.chevron"></span></el-button>',
      '                          <template #dropdown>',
      '                            <el-dropdown-menu>',
      '                              <el-dropdown-item command="text">文字签名(密码验证)</el-dropdown-item>',
      '                              <el-dropdown-item command="image">手写签名(签名板)</el-dropdown-item>',
      '                              <el-dropdown-item command="ca">CA数字签名(UKey)</el-dropdown-item>',
      '                            </el-dropdown-menu>',
      '                          </template>',
      '                        </el-dropdown>',
      '                      </div>',
      '                    </div>',
      '                  </div>',
      '                </div>',
      /* P8b-2 签名记录: 方式标签 + 验签状态标签 + 单条验签/患者家属签名入口 */
      '                <div v-if="caSignList.length" class="iew-sign-recs">',
      '                  <div class="iew-qc-sec-title"><span class="iew-icon" v-html="icons.pen"></span> 签名记录 ({{ caSignList.length }})</div>',
      '                  <div v-for="sig in caSignList" :key="sig.id" class="iew-sign-rec-card">',
      '                    <div class="iew-sign-rec-head">',
      '                      <span class="iew-sign-rec-title">{{ stageText(sig.stage) }}</span>',
      '                      <span class="iew-sign-rec-meta">{{ sig.signerName || "-" }} · {{ fmtDT(sig.signTime) }}</span>',
      '                      <span class="iew-sign-rec-tags">',
      '                        <el-tag size="small" :type="signModeTag(sig.signMode)" disable-transitions>{{ signModeText(sig.signMode) }}</el-tag>',
      '                        <el-tag size="small" :type="verifyTagOf(sig.verifyResult)" effect="plain" disable-transitions>{{ verifyTextOf(sig.verifyResult) }}</el-tag>',
      '                      </span>',
      '                    </div>',
      '                    <div v-if="sig.signImage || sig.patientSignImage || sig.familySignImage" class="iew-sign-rec-imgs">',
      '                      <img v-if="sig.signImage" class="iew-sign-rec-img" :src="sig.signImage" alt="手写签名">',
      '                      <img v-if="sig.patientSignImage" class="iew-sign-rec-img" :src="sig.patientSignImage" alt="患者签名">',
      '                      <img v-if="sig.familySignImage" class="iew-sign-rec-img" :src="sig.familySignImage" alt="家属签名">',
      '                    </div>',
      '                    <div class="iew-sign-rec-ops">',
      '                      <el-button size="small" text type="primary" :loading="verifyLoadingId === sig.id" @click="verifyCaSign(sig)">验签</el-button>',
      '                      <el-button size="small" text type="primary" :disabled="signing" @click="patientSign(sig)">患者/家属签名</el-button>',
      '                    </div>',
      '                  </div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* Tab7 质控(P5a-4 签名质控结果 + P5b-4 缺陷记录/时效倒计时/SSE 联动) */
      '          <el-tab-pane name="qc">',
      '            <template #label><span class="iew-icon" v-html="icons.check"></span> 质控</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-tab-tool">',
      '                <div class="iew-cdss-summary">',
      '                  <span class="iew-cdss-chip is-block">禁止 {{ qcForbidden.length }}</span>',
      '                  <span class="iew-cdss-chip is-warning">拦截 {{ qcBlocks.length }}</span>',
      '                  <span class="iew-cdss-chip is-info">提醒 {{ qcWarnings.length }}</span>',
      '                </div>',
      '                <el-button size="small" text style="margin-left:auto" :loading="qcDefectLoading || timelinessLoading" @click="refreshQcData"><span v-if="!(qcDefectLoading || timelinessLoading)" class="iew-icon" v-html="icons.refresh"></span> 刷新质控</el-button>',
      '              </div>',
      '              <div v-if="timelinessMain" class="iew-qc-timeliness" :class="timelinessCls">',
      '                <div class="iew-qc-timeliness-main">',
      '                  <div>{{ timelinessMain }}</div>',
      '                  <div class="iew-qc-timeliness-sub">{{ timelinessSub }}</div>',
      '                </div>',
      '              </div>',
      '              <div v-if="!qcForbidden.length && !qcBlocks.length && !qcWarnings.length && !qcSortedDefects.length" class="iew-tab-empty">暂无质控结果(签名时自动检查)</div>',
      '              <div v-for="(d, i) in qcForbidden" :key="\'f\' + i" class="iew-cdss-alert is-block">',
      '                <div class="iew-cdss-alert-name">⛔ {{ d.ruleName || d.ruleCode || "质控规则" }}<span class="iew-cdss-level">禁止·一票否决</span></div>',
      '                <div v-if="d.defectDesc" class="iew-cdss-msg">{{ d.defectDesc }}</div>',
      '              </div>',
      '              <div v-for="(d, i) in qcBlocks" :key="\'b\' + i" class="iew-cdss-alert is-warning">',
      '                <div class="iew-cdss-alert-name">🟡 {{ d.ruleName || d.ruleCode || "质控规则" }}<span class="iew-cdss-level">拦截{{ d.deductScore != null ? "·扣" + d.deductScore + "分" : "" }}</span></div>',
      '                <div v-if="d.defectDesc" class="iew-cdss-msg">{{ d.defectDesc }}</div>',
      '              </div>',
      '              <div v-for="(d, i) in qcWarnings" :key="\'w\' + i" class="iew-cdss-alert is-info">',
      '                <div class="iew-cdss-alert-name">🔵 {{ d.ruleName || d.ruleCode || "质控规则" }}<span class="iew-cdss-level">提醒</span></div>',
      '                <div v-if="d.defectDesc" class="iew-cdss-msg">{{ d.defectDesc }}</div>',
      '              </div>',
      '              <div v-if="qcSortedDefects.length" class="iew-qc-sec-title"><span class="iew-icon" v-html="icons.alert"></span> 质控缺陷记录 ({{ qcSortedDefects.length }})</div>',
      '              <div v-for="d in qcSortedDefects" :key="d.id" class="iew-qc-defect">',
      '                <div class="iew-qc-defect-head">',
      '                  <span class="iew-qc-defect-name" :title="d.ruleName || d.ruleCode || \'质控缺陷\'">{{ d.ruleName || d.ruleCode || "质控缺陷" }}</span>',
      '                  <el-tag size="small" :type="severityTagOf(d.severity)" disable-transitions>{{ severityTextOf(d.severity) }}</el-tag>',
      '                  <el-tag size="small" :type="defectStatusTagOf(d.status)" effect="plain" disable-transitions>{{ defectStatusTextOf(d.status) }}</el-tag>',
      '                </div>',
      '                <div v-if="d.defectDesc" class="iew-cdss-msg">{{ d.defectDesc }}</div>',
      '                <div class="iew-qc-defect-meta">{{ d.defectType || "-" }}<span v-if="d.deductScore != null"> · 扣 {{ d.deductScore }} 分</span><span v-if="d.createTime"> · {{ fmtDT(d.createTime) }}</span><span v-if="Number(d.status) === 1 && d.rectifyTime"> · 整改于 {{ fmtDT(d.rectifyTime) }}</span></div>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      /* Tab6 版本 */
      '          <el-tab-pane name="version">',
      '            <template #label><span class="iew-icon" v-html="icons.layers"></span> 版本</template>',
      '            <div class="iew-tab-body">',
      '              <div class="iew-tab-tool">',
      '                <span style="font-size:12px;color:var(--yb-ink-3,#5a6a7e)">勾选 2 个版本可对比</span>',
      '                <el-button size="small" text style="margin-left:auto" @click="loadVersions"><span class="iew-icon" v-html="icons.refresh"></span></el-button>',
      '              </div>',
      '              <div v-loading="versionLoading" style="min-height:60px">',
      '                <div v-if="!versions.length && !versionLoading" class="iew-tab-empty">{{ current ? "暂无版本记录(保存/提交后生成)" : "请先打开病历" }}</div>',
      '                <div v-for="v in versions" :key="v.id" class="iew-ver-card" :class="{ \'is-sel\': isVersionSelected(v.id) }" @click="toggleVersion(v.id)">',
      '                  <div class="iew-ver-main">',
      '                    <div><span class="iew-ver-no">V{{ v.versionNo }}</span> <el-tag size="small" disable-transitions>{{ operateTypeText(v.operateType) }}</el-tag></div>',
      '                    <div class="iew-ver-meta">{{ v.operatorName || "-" }} · {{ fmtDT(v.operateTime) }}</div>',
      '                  </div>',
      '                  <div class="iew-ver-ops" @click.stop>',
      '                    <el-button size="small" text type="primary" @click="previewVersion(v)">详情</el-button>',
      '                  </div>',
      '                </div>',
      '                <el-button v-if="versionSel.length === 2" size="small" type="primary" plain style="width:100%" @click="diffSelected">对比所选版本</el-button>',
      '              </div>',
      '            </div>',
      '          </el-tab-pane>',
      '        </el-tabs>',
      '      </div>',
      '    </div>',
      '  </template>',
      /* ---- 新建对话框 ---- */
      '  <el-dialog v-model="createVisible" title="新建病历" width="720px" append-to-body :close-on-click-modal="false">',
      '    <el-steps :active="createStep" simple finish-status="success">',
      '      <el-step title="1. 选择病历类型"></el-step>',
      '      <el-step title="2. 选择模板"></el-step>',
      '    </el-steps>',
      '    <div v-if="createStep === 1" class="iew-create-types">',
      '      <div v-for="g in createTypeGroups" :key="g.label">',
      '        <div class="iew-create-group-title">{{ g.label }}</div>',
      '        <div class="iew-create-cards">',
      '          <div v-for="t in g.items" :key="t.type" class="iew-create-card" :class="{ \'is-disabled\': !canCreateType(t.type) }" @click="chooseType(t.type)">',
      '            <span v-html="icons.file"></span>',
      '            <span>{{ t.label }}</span>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '    <div v-else>',
      '      <div class="iew-create-tpl-head">',
      '        <span>已选类型：<b>{{ createTypeLabel }}</b></span>',
      '        <el-button size="small" text @click="createStep = 1">重选类型</el-button>',
      '      </div>',
      '      <div class="iew-create-tpl-list" v-loading="tplLoading">',
      '        <div class="iew-create-tpl" @click="createBlank">',
      '          <div class="iew-create-tpl-name">空白文书</div>',
      '          <div class="iew-create-tpl-desc">不使用模板，从空白文档开始自由书写</div>',
      '        </div>',
      '        <div v-for="t in templatesForType" :key="t.id" class="iew-create-tpl" @click="createFromTemplate(t)">',
      '          <div class="iew-create-tpl-name">{{ t.templateName }}</div>',
      '          <div class="iew-create-tpl-desc">{{ t.templateCode || "-" }}</div>',
      '        </div>',
      '        <div v-if="!tplLoading && !templatesForType.length" class="iew-create-tpl-empty">该类型暂无对应模板，可直接使用空白文书</div>',
      '      </div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button size="small" @click="createVisible = false">取消</el-button>',
      '      <el-button v-if="createStep === 2" size="small" type="primary" :loading="creating" @click="createBlank">直接创建空白文书</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 引用预览对话框 ---- */
      '  <el-dialog v-model="quoteVisible" :title="quoteTitle || \'病历预览\'" width="640px" append-to-body>',
      '    <div class="iew-quote-preview" v-loading="quoteLoading">{{ quoteText }}</div>',
      '    <template #footer>',
      '      <el-button size="small" @click="quoteVisible = false">关闭</el-button>',
      '      <el-button size="small" type="primary" :disabled="!quoteText || !quoteTarget" @click="insertQuote(quoteTarget); quoteVisible = false">插入引用</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- P8a-3 引用草药方对话框 ---- */
      '  <el-dialog v-model="herbVisible" title="引用草药方" width="680px" append-to-body>',
      '    <div class="iew-herb-hint" style="margin:0 0 8px">选择本次就诊的草药处方, 预览格式化文本后插入到当前光标处</div>',
      '    <el-table :data="herbList" size="small" height="240" v-loading="herbLoading" highlight-current-row :row-class-name="herbRowClass" @row-click="pickHerb">',
      '      <el-table-column label="方名" min-width="180" show-overflow-tooltip>',
      '        <template #default="{ row }"><span class="iew-herb-name">{{ herbRowName(row) }}</span></template>',
      '      </el-table-column>',
      '      <el-table-column label="日期" width="150"><template #default="{ row }">{{ herbRowDate(row) }}</template></el-table-column>',
      '      <el-table-column label="药味数" width="80"><template #default="{ row }">{{ herbRowCount(row) }}</template></el-table-column>',
      '    </el-table>',
      '    <div v-if="herbSelRow" style="margin-top:10px">',
      '      <div class="iew-herb-preview" v-loading="herbTextLoading">{{ herbText || "(未获取到格式化文本)" }}</div>',
      '      <div class="iew-herb-hint">插入后可在编辑器中继续编辑; 文本来源于草药处方明细</div>',
      '    </div>',
      '    <div v-else-if="!herbLoading && !herbList.length" class="iew-herb-hint" style="margin-top:10px">暂无可引用的草药处方, 可先在处方/医嘱中开具草药方</div>',
      '    <template #footer>',
      '      <el-button size="small" @click="herbVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :disabled="!herbSelRow || !herbText || herbTextLoading" @click="insertHerbFormula">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- P8b-2 CA数字签名弹窗(模拟模式, 预留 window.caClientCallback 回调点) ---- */
      '  <el-dialog v-model="caVisible" title="CA数字签名认证" width="430px" append-to-body :close-on-click-modal="false">',
      '    <div class="iew-ca-tip"><span class="iew-icon" v-html="icons.alert"></span> 请插入UKey并输入PIN码完成CA数字签名(当前为模拟模式: 证书序列号 SIMULATED, 算法 SM2)。</div>',
      '    <el-input ref="caPinInput" v-model="caPin" type="password" show-password placeholder="UKey PIN码" @keyup.enter="submitCaSign"></el-input>',
      '    <template #footer>',
      '      <el-button size="small" @click="caVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="signing" @click="submitCaSign">确认签名</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 版本对比对话框 ---- */
      '  <el-dialog v-model="diffVisible" title="版本对比" width="860px" append-to-body>',
      '    <div v-loading="diffLoading" style="min-height:120px">',
      '      <div v-if="diffData && diffData.summary" class="iew-diff-chips">',
      '        <span>新增 {{ diffData.summary.add || 0 }} 行</span>',
      '        <span>删除 {{ diffData.summary.del || 0 }} 行</span>',
      '        <span>修改 {{ diffData.summary.modify || 0 }} 行</span>',
      '        <span>相同 {{ diffData.summary.same || 0 }} 行</span>',
      '      </div>',
      '      <div v-if="diffData && diffData.diffs && diffData.diffs.length" style="max-height:56vh;overflow:auto">',
      '        <table class="iew-diff-table">',
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
      '      <div v-else-if="!diffLoading" class="iew-tab-empty">两版本内容一致或暂无差异</div>',
      '    </div>',
      '    <template #footer><el-button size="small" @click="diffVisible = false">关闭</el-button></template>',
      '  </el-dialog>',
      /* ---- 版本详情预览对话框 ---- */
      '  <el-dialog v-model="versionVisible" :title="versionPreviewTitle" width="640px" append-to-body>',
      '    <div class="iew-ver-preview" v-loading="versionPreviewLoading">{{ versionPreview }}</div>',
      '    <template #footer><el-button size="small" @click="versionVisible = false">关闭</el-button></template>',
      '  </el-dialog>',
      /* ---- P5a-4 质控拦截抽屉(卡签名整改面板) ---- */
      '  <el-drawer v-model="showQcPanel" title="质控拦截 · 签名被阻断" size="420px" append-to-body>',
      '    <div class="iew-qc-panel">',
      '      <div class="iew-qc-tip">存在拦截级质控缺陷，签名已被阻断。请按下方缺陷整改后，点击「整改后重签」重新发起签名。</div>',
      '      <div v-for="(d, i) in qcBlocks" :key="i" class="iew-cdss-alert is-warning">',
      '        <div class="iew-cdss-alert-name">🟡 {{ d.ruleName || d.ruleCode || "质控规则" }}<span class="iew-cdss-level">{{ d.deductScore != null ? "扣 " + d.deductScore + " 分" : "拦截级" }}</span></div>',
      '        <div v-if="d.defectDesc" class="iew-cdss-msg">{{ d.defectDesc }}</div>',
      '      </div>',
      '      <div v-if="qcForbidden.length" class="iew-cdss-alert is-block">',
      '        <div class="iew-cdss-alert-name">⛔ 另有 {{ qcForbidden.length }} 项禁止级缺陷<span class="iew-cdss-level">须联系质控员豁免</span></div>',
      '      </div>',
      '      <el-button type="primary" style="width:100%; margin-top:4px" @click="reSignAfterRectify">整改后重签</el-button>',
      '    </div>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };

  /* ================= 组件级辅助(模板/方法引用的纯函数) ================= */
  /* Tiptap 文档 → 纯文本(引用预览/插入用) */
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
    /* 合并文本片段并规范空行 */
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
  /* 快照内容(可能是 Tiptap JSON / 旧 HTML / 旧键值 JSON) → 纯文本 */
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
        return docToPlainText(formJsonToDoc(obj));
      } catch (e) { /* fallthrough */ }
    }
    if (first === '<') {
      var el = document.createElement('div');
      el.innerHTML = s;
      return (el.textContent || '').trim() || '(无内容)';
    }
    return s;
  }

  /* ================= 注册 ================= */
  HIS.components.InpEmrWriter = InpEmrWriter;
})(window);
