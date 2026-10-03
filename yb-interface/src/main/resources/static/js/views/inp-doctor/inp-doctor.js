/* 住院医生站 - 工作台主框架: 左侧病区/患者列表(280px) + 右侧患者信息条与六页签(概览/医嘱/诊断/病历/费用/临床路径)
 * 依赖加载顺序(index.html): inp-patient-overview.js -> inp-order-panel.js -> inp-diag-panel.js
 *                          -> inp-record-panel.js -> inp-pathway-panel.js -> inp-doctor.js(本文件, 最后)
 * 接口: GET /api/his/inp/bed/ward/list?orgId= | GET /api/his/inp/doctor/patients?wardId=&keyword=&page=&size=
 *       GET /api/his/dept/list (deptId -> deptName 映射)
 *       GET /api/his/inp/dashboard/doctor (我的工作台统计, 见 InpDoctorDashboard)
 *       GET /api/his/inp/doctor/patient/{id}/summary + /api/his/inp/allergy/list (横幅补挂) | PUT /api/his/inp/visit/{id}/transfer (转科)
 * 注册: HIS.views.InpDoctorWorkstation (由 app.js 按 comp 名挂载); 样式以 <style id="inp-doctor-css"> 注入, 前缀 iw-* */
;(function () {
  const HIS = (window.HIS = window.HIS || {});

  const VISIT_STATUS = { 1: '待入院', 2: '在院', 3: '出院办理中', 4: '已出院', 5: '已取消' };
  const VISIT_STATUS_TAG = { 1: 'info', 2: 'success', 3: 'warning', 4: '', 5: 'info' };

  function genderText(value) {
    if (value === '1' || value === '男') { return '男'; }
    if (value === '2' || value === '女') { return '女'; }
    return value || '';
  }

  function dateText(value) {
    if (!value) { return '-'; }
    return String(value).replace('T', ' ').substring(0, 10);
  }

  function admitDays(admitDate) {
    if (!admitDate) { return '-'; }
    const ts = new Date(String(admitDate).replace(' ', 'T')).getTime();
    if (isNaN(ts)) { return '-'; }
    return Math.max(1, Math.floor((Date.now() - ts) / 86400000) + 1);
  }

  /* 内联 SVG 图标(项目未引入图标库, 统一 24 视框 / currentColor 染色), 供页签 #label v-html 使用 */
  const WS_ICONS = {
    report: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"/><path d="M8 15l2.5-3 2 2L15 10"/></svg>',
    emr: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3M11 8v6M8 11h6"/></svg>'
  };

  /* ============================================================
   * 样式(注入一次): 复用 theme.css 的 --yb-* 令牌, 不改动既有 CSS 文件
   * ============================================================ */
  const CSS = `
/* ===== 工作台骨架 ===== */
.iw-workbench { display:flex; height:calc(100vh - 88px); min-height:520px; overflow:hidden; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); }
.iw-side { width:280px; flex:none; display:flex; flex-direction:column; border-right:1px solid var(--yb-border); background:var(--yb-surface-2); }
.iw-side-head { padding:12px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); }
.iw-side-body { flex:1; min-height:0; }
.iw-main { flex:1; min-width:0; display:flex; flex-direction:column; overflow:hidden; }
/* ===== 左栏折叠: 头栏切换按钮 + 折叠态细导轨 ===== */
.iw-side-bar { display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; }
.iw-side-bar b { font-size:var(--yb-fs-md); color:var(--yb-ink-1); }
.iw-collapse-btn { width:22px; height:22px; line-height:1; flex:none; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); background:var(--yb-surface); color:var(--yb-ink-2); cursor:pointer; font-size:13px; transition:background var(--yb-dur) var(--yb-ease),color var(--yb-dur) var(--yb-ease); }
.iw-collapse-btn:hover { background:var(--yb-surface-3); color:var(--yb-brand); }
.iw-rail { width:34px; flex:none; display:flex; flex-direction:column; align-items:center; padding-top:10px; gap:12px; border-right:1px solid var(--yb-border); background:var(--yb-surface-2); }
.iw-rail-text { writing-mode:vertical-rl; letter-spacing:.24em; font-size:var(--yb-fs-cap); color:var(--yb-ink-3); }

/* ===== 左侧患者卡片 ===== */
.iw-patient { display:flex; gap:10px; align-items:center; padding:10px 12px; border-bottom:1px solid var(--yb-divider); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }
.iw-patient:hover { background:var(--yb-surface-3); }
.iw-patient--active { background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }
.iw-patient-bed { width:46px; height:46px; flex:none; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:1px; border-radius:var(--yb-r-sm); background:var(--yb-surface-3); color:var(--yb-ink-2); }
.iw-patient-bed b { font-size:var(--yb-fs-base); line-height:1.1; }
.iw-patient-bed span { font-size:var(--yb-fs-cap); color:var(--yb-ink-4); }
.iw-patient--active .iw-patient-bed { background:var(--yb-brand); color:#fff; }
.iw-patient--active .iw-patient-bed span { color:rgba(255,255,255,.75); }
.iw-patient-main { flex:1; min-width:0; }
.iw-patient-main .r1 { display:flex; align-items:baseline; gap:6px; }
.iw-patient-main .r1 .nm { font-size:var(--yb-fs-md); color:var(--yb-ink-1); }
.iw-patient-main .r1 .meta { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.iw-patient-main .r2 { margin-top:3px; display:flex; gap:8px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.iw-patient-main .r2 .num { font-family:var(--yb-font-mono); }

/* ===== 患者信息条 ===== */
.iw-pt-banner { display:flex; flex-wrap:wrap; align-items:center; gap:6px 22px; padding:10px 16px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); font-size:var(--yb-fs-base); color:var(--yb-ink-2); }
.iw-pt-banner .pn { font-size:var(--yb-fs-lg); font-weight:600; color:var(--yb-ink-1); }
.iw-pt-banner .pn .meta { margin-left:8px; font-size:var(--yb-fs-sm); font-weight:400; color:var(--yb-ink-3); }
.iw-pt-banner .iv b { color:var(--yb-ink-1); font-weight:600; }
.iw-banner-right { margin-left:auto; }

/* ===== 页签容器 ===== */
.iw-tabs { flex:1; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.iw-tabs .el-tabs__header { margin:0; padding:0 16px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); }
.iw-tabs .el-tabs__nav-wrap::after { display:none; }
.iw-tabs .el-tabs__content { flex:1; min-height:0; overflow:hidden; }
.iw-tabs .el-tab-pane { height:100%; }
.iw-placeholder { flex:1; display:flex; flex-direction:column; gap:8px; align-items:center; justify-content:center; color:var(--yb-ink-4); font-size:var(--yb-fs-lg); }

/* ===== 面板通用 ===== */
.iw-panel-body { height:100%; padding:14px 16px; overflow:auto; box-sizing:border-box; }
.iw-panel-body--flush { padding:0; overflow:hidden; display:flex; flex-direction:column; }
.iw-card { background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); padding:12px 14px; }
.iw-sect-title { display:flex; align-items:center; gap:8px; font-size:var(--yb-fs-md); font-weight:600; color:var(--yb-ink-1); margin-bottom:10px; }
.iw-count { display:inline-flex; min-width:18px; height:18px; padding:0 5px; align-items:center; justify-content:center; border-radius:var(--yb-r-pill); background:var(--yb-brand-subtle); color:var(--yb-brand); font-size:var(--yb-fs-cap); font-weight:600; }
.iw-dim { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.iw-em { color:var(--yb-brand); }
.iw-empty-line { padding:14px 0; text-align:center; color:var(--yb-ink-4); font-size:var(--yb-fs-sm); }
.iw-hint { margin-top:10px; padding:8px 10px; border-radius:var(--yb-r-sm); background:var(--yb-surface-2); border:1px dashed var(--yb-border-strong); color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.iw-grow { flex:1; min-width:0; }

/* ===== 工具条 / 分页 ===== */
.iw-toolbar { flex:none; display:flex; align-items:center; gap:10px; padding:10px 14px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); flex-wrap:wrap; }
.iw-toolbar-info { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.iw-toolbar-info b { color:var(--yb-ink-1); }
.iw-toolbar-right { margin-left:auto; display:inline-flex; align-items:center; gap:8px; }
.iw-pager { flex:none; display:flex; justify-content:flex-end; padding:8px 14px; border-top:1px solid var(--yb-border); background:var(--yb-surface); }
.iw-table-scroll { flex:1; min-height:0; padding:0 14px 10px; }

/* ===== 标签 ===== */
.iw-tag { display:inline-flex; align-items:center; padding:0 7px; height:20px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-sm); line-height:1; background:var(--yb-surface-3); color:var(--yb-ink-2); white-space:nowrap; }
.iw-tag--success { background:var(--yb-success-bg); color:var(--yb-success); }
.iw-tag--danger { background:var(--yb-danger-bg); color:var(--yb-danger); }
.iw-tag--plain { background:var(--yb-surface-2); color:var(--yb-ink-3); border:1px solid var(--yb-border); }
.iw-tag--long { background:var(--yb-success-bg); color:var(--yb-success); }
.iw-tag--temp { background:var(--yb-info-light); color:var(--yb-info); }

/* ===== 金额 ===== */
.iw-money { font-family:var(--yb-font-mono); color:var(--yb-gold); font-variant-numeric:tabular-nums; }
.iw-money--danger { color:var(--yb-danger); }

/* ===== 轻量表格 / 占比条 ===== */
.iw-table { width:100%; border-collapse:collapse; font-size:var(--yb-fs-base); }
.iw-table th, .iw-table td { padding:7px 10px; border-bottom:1px solid var(--yb-divider); text-align:left; color:var(--yb-ink-2); }
.iw-table th { background:var(--yb-surface-2); color:var(--yb-ink-3); font-weight:600; font-size:var(--yb-fs-sm); }
.iw-table--wide th, .iw-table--wide td { padding:9px 12px; }
.iw-table-total td { font-weight:600; color:var(--yb-ink-1); background:var(--yb-surface-2); }
.iw-bar { display:inline-block; width:calc(100% - 56px); height:8px; border-radius:var(--yb-r-pill); background:var(--yb-surface-3); overflow:hidden; vertical-align:middle; }
.iw-bar-inner { height:100%; border-radius:var(--yb-r-pill); background:linear-gradient(90deg, var(--yb-brand), var(--yb-info)); }
.iw-pct { margin-left:8px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }

/* ===== 医嘱行(长期绿 / 临时蓝 左边框; 成组缩进) ===== */
.el-table tr.iw-order--long td:first-child { box-shadow:inset 3px 0 0 var(--yb-fill-success); }
.el-table tr.iw-order--temp td:first-child { box-shadow:inset 3px 0 0 var(--yb-fill-info); }
.el-table tr.iw-order--grp-first td, .el-table tr.iw-order--grp-child td { background:var(--yb-surface-2); }
.el-table tr.iw-order--grp-child td:first-child { padding-left:22px; }
.iw-grp { display:inline-block; padding:0 6px; border-radius:var(--yb-r-sm); background:var(--yb-brand-subtle); color:var(--yb-brand); font-size:var(--yb-fs-cap); font-weight:600; }
.iw-grp-child { color:var(--yb-ink-4); }

/* ===== 表单行 / 下拉选项 ===== */
.iw-form { display:flex; flex-direction:column; gap:12px; }
.iw-form-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.iw-form-row .lb { flex:none; width:64px; text-align:right; color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.iw-form-row .lb ~ .lb { margin-left:4px; }
.iw-opt { display:flex; align-items:center; gap:10px; }
.iw-opt .nm { color:var(--yb-ink-1); }
.iw-opt .sub { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.iw-opt .price { margin-left:auto; font-family:var(--yb-font-mono); color:var(--yb-gold); }
.iw-picked { display:flex; align-items:center; gap:16px; flex-wrap:wrap; padding:8px 10px; border-radius:var(--yb-r-sm); background:var(--yb-brand-subtle); font-size:var(--yb-fs-sm); color:var(--yb-ink-2); }
.iw-picked .clip { flex-basis:100%; color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }

/* ===== 概览: 床头卡 / 双列 / 诊断 / 统计 / 费用 ===== */
.iw-headcard { display:flex; align-items:center; gap:16px; padding:14px; border:1px solid var(--yb-border); border-radius:var(--yb-r-md); background:linear-gradient(180deg, var(--yb-surface), var(--yb-surface-2)); }
.iw-headcard-bed { width:64px; height:64px; flex:none; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; border-radius:var(--yb-r-md); background:var(--yb-brand); color:#fff; }
.iw-headcard-bed .no { font-size:var(--yb-fs-lg); font-weight:700; }
.iw-headcard-bed .lb { font-size:var(--yb-fs-cap); opacity:.78; }
.iw-headcard-main { flex:1; min-width:0; }
.iw-headcard-line1 { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.iw-headcard-line1 .nm { font-size:var(--yb-fs-xl); color:var(--yb-ink-1); }
.iw-headcard-line1 .meta { color:var(--yb-ink-3); font-size:var(--yb-fs-base); }
.iw-headcard-line2 { margin-top:8px; display:flex; flex-wrap:wrap; gap:6px 18px; color:var(--yb-ink-2); font-size:var(--yb-fs-base); }
.iw-headcard-line2 b { color:var(--yb-ink-1); }
.iw-headcard-right { flex:none; display:flex; flex-direction:column; align-items:flex-end; gap:4px; }
.iw-headcard-right .lb { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }

.iw-two-col { display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-top:12px; }
.iw-two-col--gap { margin-top:0; }
@media (max-width:1200px) { .iw-two-col { grid-template-columns:1fr; } }

.iw-diaggroups { display:flex; flex-direction:column; gap:10px; }
.iw-dg-head { display:flex; align-items:center; gap:6px; font-size:var(--yb-fs-sm); font-weight:600; color:var(--yb-ink-2); }
.iw-dg-list { margin-top:6px; display:flex; flex-direction:column; gap:4px; }
.iw-dg-item { display:flex; align-items:center; gap:8px; font-size:var(--yb-fs-base); }
.iw-dg-item .code { flex:none; width:64px; font-family:var(--yb-font-mono); font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.iw-dg-item .name { color:var(--yb-ink-1); }

.iw-stat-grid { display:grid; grid-template-columns:repeat(4, 1fr); gap:10px; }
.iw-stat { display:flex; flex-direction:column; align-items:center; gap:2px; padding:10px 6px; border-radius:var(--yb-r-sm); background:var(--yb-surface-2); border:1px solid var(--yb-border-light); }
.iw-stat .v { font-size:var(--yb-fs-2xl); font-weight:700; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; }
.iw-stat .l { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.iw-stat--hl { background:var(--yb-brand-subtle); border-color:var(--yb-brand-border); }
.iw-stat--hl .v { color:var(--yb-brand); }
.iw-stat--danger .v { color:var(--yb-danger); }
.iw-stat--big .v { font-size:var(--yb-fs-xl); }

.iw-chiprow { margin-top:10px; display:flex; flex-wrap:wrap; gap:6px; }
.iw-chip { padding:2px 9px; border-radius:var(--yb-r-pill); background:var(--yb-surface-2); border:1px solid var(--yb-border); color:var(--yb-ink-2); font-size:var(--yb-fs-sm); }
.iw-chip.is-zero { color:var(--yb-ink-4); }

.iw-charge-sum { display:flex; flex-wrap:wrap; gap:8px 24px; padding:8px 0 12px; font-size:var(--yb-fs-base); color:var(--yb-ink-2); }
.iw-charge-sum .item b { margin-left:2px; }
.iw-charge-cards { display:grid; grid-template-columns:repeat(4, 1fr); gap:12px; margin-bottom:12px; }
@media (max-width:1200px) { .iw-charge-cards { grid-template-columns:repeat(2, 1fr); } }

/* ===== 诊断面板 ===== */
.iw-diag-sec { display:flex; flex-direction:column; }
.iw-diag-lines { display:flex; flex-direction:column; }
.iw-diag-line { display:flex; align-items:center; gap:8px; padding:7px 0; border-bottom:1px dashed var(--yb-divider); font-size:var(--yb-fs-base); }
.iw-diag-line:last-child { border-bottom:none; }
.iw-diag-line .code { flex:none; width:70px; font-family:var(--yb-font-mono); font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.iw-diag-line .name { flex:1; min-width:0; color:var(--yb-ink-1); }
.iw-diag-line .ops { flex:none; }

/* ===== 病历面板 ===== */
.iw-record-wrap { display:flex; height:100%; overflow:hidden; }
.iw-record-side { width:200px; flex:none; display:flex; flex-direction:column; border-right:1px solid var(--yb-border); background:var(--yb-surface-2); }
.iw-record-side-head { padding:10px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); text-align:center; }
.iw-record-side-body { flex:1; min-height:0; }
.iw-record-grp-head { display:flex; align-items:center; gap:6px; padding:7px 12px; font-size:var(--yb-fs-sm); font-weight:600; color:var(--yb-ink-3); background:var(--yb-surface-2); border-bottom:1px solid var(--yb-divider); }
.iw-record-item { padding:8px 12px; border-bottom:1px solid var(--yb-divider); cursor:pointer; background:var(--yb-surface); transition:background var(--yb-dur) var(--yb-ease); }
.iw-record-item:hover { background:var(--yb-surface-3); }
.iw-record-item.is-active { background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }
.iw-record-item .t { display:flex; align-items:center; gap:6px; font-size:var(--yb-fs-base); color:var(--yb-ink-1); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.iw-record-item .m { margin-top:4px; display:flex; align-items:center; gap:8px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.iw-record-main { flex:1; min-width:0; display:flex; flex-direction:column; overflow:hidden; background:var(--yb-surface); }
.iw-record-head { flex:none; display:flex; align-items:center; gap:10px; padding:12px 16px; border-bottom:1px solid var(--yb-border); }
.iw-record-form { flex:1; min-height:0; overflow:auto; padding:14px 16px; }
.iw-rf-field { display:flex; gap:10px; margin-bottom:12px; }
.iw-rf-field .lb { flex:none; width:78px; padding-top:6px; text-align:right; color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.iw-record-actions { flex:none; display:flex; align-items:center; gap:10px; padding:10px 16px; border-top:1px solid var(--yb-border); background:var(--yb-surface-2); }
.iw-record-placeholder { flex:1; display:flex; flex-direction:column; gap:8px; align-items:center; justify-content:center; color:var(--yb-ink-4); font-size:var(--yb-fs-lg); }

/* ===== 我的工作台(仪表盘) ===== */
.iw-dash { height:100%; overflow:auto; padding:14px 16px; box-sizing:border-box; background:var(--yb-surface); }
.iw-dcard { position:relative; height:80px; box-sizing:border-box; display:flex; flex-direction:column; justify-content:center; padding:12px 16px 12px 22px; border-radius:8px; background:var(--yb-surface); box-shadow:var(--yb-sh-1); cursor:pointer; overflow:hidden; transition:box-shadow var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }
.iw-dcard::before { content:''; position:absolute; left:0; top:0; bottom:0; width:4px; background:linear-gradient(180deg, var(--dc-a, var(--yb-brand)), var(--dc-b, var(--yb-info))); }
.iw-dcard:hover { box-shadow:var(--yb-sh-2); transform:translateY(-1px); }
.iw-dcard .v { font-size:32px; line-height:1.1; font-weight:700; color:var(--dc-v, var(--yb-ink-1)); font-variant-numeric:tabular-nums; }
.iw-dcard .l { margin-top:2px; font-size:14px; color:var(--yb-text-secondary, var(--yb-ink-3)); }
.iw-dcard--warning { --dc-a:var(--yb-warning); --dc-b:var(--yb-warning-strong); --dc-v:var(--yb-warning); }
.iw-dcard--primary { --dc-a:var(--yb-brand); --dc-b:var(--yb-info); --dc-v:var(--yb-primary, var(--yb-brand)); }
.iw-dcard--success { --dc-a:var(--yb-success); --dc-b:var(--yb-fill-success, var(--yb-success-strong)); --dc-v:var(--yb-success); }
.iw-dcard--danger { --dc-a:var(--yb-danger); --dc-b:var(--yb-danger-strong); --dc-v:var(--yb-danger); }
.iw-dash-panel { box-sizing:border-box; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); padding:12px 14px; }
.iw-dash-card-hd { display:flex; align-items:center; gap:8px; margin-bottom:8px; font-size:var(--yb-fs-md); font-weight:600; color:var(--yb-ink-1); }
.iw-dash-chart { width:100%; height:248px; }
.iw-dash-timeline { padding:4px 2px 0; max-height:252px; overflow:auto; }
.iw-dash-empty { padding:26px 0; text-align:center; color:var(--yb-ink-4); font-size:var(--yb-fs-sm); }
.iw-dash-todo { margin-top:12px; }
.iw-op-item { display:flex; align-items:baseline; gap:8px; flex-wrap:wrap; }
.iw-op-item .nm { color:var(--yb-ink-1); }
.iw-op-meta { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); margin-top:2px; }
.iw-deadline-over { color:var(--yb-danger); font-weight:600; font-variant-numeric:tabular-nums; }
.iw-deadline-ok { color:var(--yb-ink-2); font-variant-numeric:tabular-nums; }
.el-table .iw-todo--over td { background:var(--yb-danger-bg) !important; }
/* 危急值待办卡片(T37): 红色告警条, 点击跳转危急值管理页 */
.iw-crit-todo { display:flex; align-items:center; gap:10px; padding:9px 14px; margin-bottom:10px; border-radius:var(--yb-r-md); background:var(--yb-danger-bg); border:1px solid var(--yb-danger-border); border-left:4px solid var(--yb-danger); cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease); }
.iw-crit-todo:hover { box-shadow:var(--yb-sh-2); }
.iw-crit-todo .lv { flex:none; font-size:var(--yb-fs-sm); font-weight:700; color:var(--yb-danger-strong); background:var(--yb-surface); border:1px solid var(--yb-danger-border); border-radius:var(--yb-r-sm); padding:1px 8px; }
.iw-crit-todo .num { font-size:22px; line-height:1; font-weight:700; color:var(--yb-danger); font-variant-numeric:tabular-nums; }
.iw-crit-todo .txt { font-size:var(--yb-fs-sm); color:var(--yb-ink-2); }
.iw-crit-todo .go { margin-left:auto; font-size:var(--yb-fs-sm); font-weight:600; color:var(--yb-danger-strong); }

/* ===== 患者固定横幅(48px) ===== */
.iw-pb { height:48px; flex:none; display:flex; align-items:center; gap:12px; padding:0 16px; box-sizing:border-box; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); overflow:hidden; }
.iw-pb-bed { width:36px; height:36px; flex:none; display:flex; flex-direction:column; align-items:center; justify-content:center; border-radius:6px; background:var(--yb-brand); color:#fff; line-height:1.05; }
.iw-pb-bed b { font-size:var(--yb-fs-base); font-weight:700; }
.iw-pb-bed span { font-size:10px; opacity:.8; }
.iw-pb-name { font-size:16px; font-weight:700; color:var(--yb-ink-1); white-space:nowrap; }
.iw-pb-meta { font-size:14px; color:var(--yb-ink-3); white-space:nowrap; }
.iw-pb-sep { width:1px; height:18px; flex:none; background:var(--yb-border-strong); }
.iw-pb-kv { font-size:var(--yb-fs-base); color:var(--yb-ink-3); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.iw-pb-kv b { color:var(--yb-ink-1); font-weight:600; }
.iw-pb-diag { max-width:300px; }
.iw-pb-tag { display:inline-flex; align-items:center; height:20px; padding:0 8px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-sm); line-height:1; white-space:nowrap; }
.iw-pb-tag--allergy { background:var(--yb-danger); color:#fff; font-weight:600; animation:iwPbBlink 1.1s ease-in-out infinite; }
.iw-pb-tag--allergy-none { background:var(--yb-success-bg); color:var(--yb-success); }
.iw-pb-tag--insur { background:var(--yb-info-light); color:var(--yb-info); }
.iw-pb-nurse { display:inline-flex; align-items:center; height:20px; padding:0 8px; border-radius:var(--yb-r-sm); color:#fff; font-size:var(--yb-fs-sm); line-height:1; white-space:nowrap; }
.iw-pb-nurse--1 { background:var(--yb-danger); }
.iw-pb-nurse--2 { background:var(--yb-warning); }
.iw-pb-nurse--3 { background:var(--yb-gold); }
.iw-pb-nurse--4 { background:var(--yb-success); }
.iw-pb-bal { font-family:var(--yb-font-mono); color:var(--yb-gold); font-size:var(--yb-fs-base); font-variant-numeric:tabular-nums; white-space:nowrap; }
.iw-pb-right { margin-left:auto; display:inline-flex; align-items:center; gap:8px; }
@keyframes iwPbBlink { 0%,100% { opacity:1; } 50% { opacity:.45; } }

/* ===== 右键上下文菜单 ===== */
.iw-ctx { position:fixed; z-index:3000; min-width:168px; padding:4px; box-sizing:border-box; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-3); }
.iw-ctx-item { display:flex; align-items:center; gap:8px; padding:7px 10px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-base); color:var(--yb-ink-2); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }
.iw-ctx-item:hover { background:var(--yb-brand-subtle); color:var(--yb-brand); }
.iw-ctx-item.is-disabled { color:var(--yb-ink-4); cursor:not-allowed; background:transparent; }
.iw-ctx-item.is-danger:hover { background:var(--yb-danger-bg); color:var(--yb-danger); }
.iw-ctx-sep { height:1px; margin:4px 6px; background:var(--yb-divider); }
.iw-ctx-ico { width:14px; height:14px; flex:none; }

/* ===== 图标操作按钮(四个子面板共用) ===== */
.iw-ops { display:inline-flex; align-items:center; gap:2px; }
.iw-icobtn { display:inline-flex; align-items:center; justify-content:center; width:26px; height:26px; padding:0; border:none; border-radius:50%; background:transparent; color:var(--yb-ink-3); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease), color var(--yb-dur) var(--yb-ease); vertical-align:middle; }
.iw-icobtn:hover { background:var(--yb-brand-subtle); color:var(--yb-brand); }
.iw-icobtn.is-danger:hover { background:var(--yb-danger-bg); color:var(--yb-danger); }
.iw-icobtn.is-success:hover { background:var(--yb-success-bg); color:var(--yb-success); }
.iw-icobtn.is-warning:hover { background:var(--yb-warning-bg); color:var(--yb-warning-strong); }
.iw-icobtn[disabled] { cursor:not-allowed; opacity:.45; }
.iw-icobtn[disabled]:hover { background:transparent; color:var(--yb-ink-3); }
.iw-ico { width:15px; height:15px; display:block; }
.iw-ico-more { width:16px; height:16px; display:block; }

/* ===== 批次A: 患者三形态视图 / 检索筛选 / 图形化标识 (私有前缀 idp-) ===== */
.idp-view-seg { display:flex; gap:0; width:100%; margin-bottom:8px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); overflow:hidden; }
.idp-view-seg button { flex:1; padding:4px 0; border:none; border-right:1px solid var(--yb-border); background:var(--yb-surface); color:var(--yb-ink-3); font-size:var(--yb-fs-sm); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease),color var(--yb-dur) var(--yb-ease); }
.idp-view-seg button:last-child { border-right:none; }
.idp-view-seg button:hover { background:var(--yb-surface-3); color:var(--yb-brand); }
.idp-view-seg button.is-on { background:var(--yb-brand); color:#fff; font-weight:600; }
.idp-filters { display:flex; gap:6px; margin-bottom:8px; }
.idp-filters .el-select { flex:1; min-width:0; }
.idp-mark { background:var(--yb-gold-bg, #fff3d6); color:var(--yb-gold, #b8860b); border-radius:2px; padding:0 1px; font-weight:600; }
.idp-flags { display:inline-flex; align-items:center; gap:4px; flex-wrap:wrap; }
.idp-badge { display:inline-flex; align-items:center; height:16px; padding:0 6px; border-radius:var(--yb-r-pill); font-size:11px; line-height:1; font-weight:600; white-space:nowrap; }
.idp-badge--allergy { background:var(--yb-danger); color:#fff; }
.idp-badge--owed { background:var(--yb-warning); color:#fff; }
.idp-badge--critical { background:var(--yb-danger-bg); color:var(--yb-danger); border:1px solid var(--yb-danger-border); animation:iwpBreath 1.3s ease-in-out infinite; }
.idp-badge--nurse1 { background:var(--yb-danger); color:#fff; }
.idp-badge--nurse2 { background:var(--yb-warning); color:#fff; }
.idp-badge--nurse3 { background:var(--yb-gold); color:#fff; }
.idp-badge--nurse4 { background:var(--yb-success); color:#fff; }
.idp-badge--pathway { background:var(--yb-info-light); color:var(--yb-info); }
.idp-badge--surgery { background:var(--yb-brand-subtle); color:var(--yb-brand); }
.idp-badge--key { background:var(--yb-surface-3); color:var(--yb-ink-2); border:1px solid var(--yb-border-strong); }
.idp-badge--insur { background:var(--yb-info-light); color:var(--yb-info); }
@keyframes iwpBreath { 0%,100% { opacity:1; } 50% { opacity:.4; } }
/* 细卡(card): 富信息行 */
.idp-card { display:flex; gap:10px; align-items:flex-start; padding:10px 12px; border-bottom:1px solid var(--yb-divider); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }
.idp-card:hover { background:var(--yb-surface-3); }
.idp-card.is-active { background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }
.idp-card .idp-diag { margin-top:4px; font-size:var(--yb-fs-sm); color:var(--yb-ink-2); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.idp-card .idp-dept { margin-top:3px; font-size:var(--yb-fs-cap); color:var(--yb-ink-4); }
/* 简卡(slim): 单行紧凑 */
.idp-slim { display:flex; align-items:center; gap:8px; padding:7px 12px; border-bottom:1px solid var(--yb-divider); cursor:pointer; font-size:var(--yb-fs-base); transition:background var(--yb-dur) var(--yb-ease); }
.idp-slim:hover { background:var(--yb-surface-3); }
.idp-slim.is-active { background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }
.idp-slim .bed { flex:none; min-width:30px; text-align:center; font-family:var(--yb-font-mono); font-weight:700; color:var(--yb-ink-1); }
.idp-slim .nm { flex:none; font-weight:600; color:var(--yb-ink-1); }
.idp-slim .dg { flex:1; min-width:0; color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }

/* ===== 批次B: 工作台流转处理聚合条 (私有前缀 idash-) ===== */
.idash-flow { margin-top:12px; }
.idash-flow-row { display:flex; flex-wrap:wrap; gap:10px; }
.idash-chip { flex:1; min-width:150px; display:flex; align-items:center; gap:8px; padding:10px 14px; border:1px solid var(--yb-border); border-radius:var(--yb-r-md); background:var(--yb-surface); cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease),transform var(--yb-dur) var(--yb-ease); }
.idash-chip:hover { box-shadow:var(--yb-sh-2); transform:translateY(-1px); }
.idash-chip .k { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.idash-chip .n { margin-left:auto; font-size:20px; font-weight:700; line-height:1; font-variant-numeric:tabular-nums; color:var(--yb-ink-1); }
.idash-chip.is-alert { border-color:var(--yb-danger-border); background:var(--yb-danger-bg); }
.idash-chip.is-alert .n { color:var(--yb-danger); }
.idash-chip.is-warn .n { color:var(--yb-warning); }
.idash-chip .dot { width:8px; height:8px; border-radius:50%; background:var(--yb-brand); flex:none; }
.idash-empty-tip { padding:24px; text-align:center; color:var(--yb-ink-4); font-size:var(--yb-fs-base); }
.idash-sub { font-size:12px; color:var(--yb-ink-4); }
`;

  function ensureStyle() {
    if (document.getElementById('inp-doctor-css')) { return; }
    const style = document.createElement('style');
    style.id = 'inp-doctor-css';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  /* 险种编码 -> 名称(与门诊 patient-banner.js 同口径) */
  const INSUR_TYPES = { '310': '职工医保', '390': '居民医保', '340': '工伤保险', '391': '城乡居民' };
  const NURSING_LEVELS = { 1: '特级护理', 2: '一级护理', 3: '二级护理', 4: '三级护理' };

  /* ============================================================
   * PatientBanner 患者固定横幅(48px): 床号/姓名/住院号/诊断/过敏/医保/护理/余额
   * 数据 = 患者列表行 + 工作站补挂(nursingLevel/insutype 取自 summary, allergyInfo 取自过敏档案)
   * ============================================================ */
  const PatientBanner = {
    name: 'PatientBanner',
    props: { patient: { type: Object, default: null } },
    computed: {
      p() { return this.patient || {}; },
      genderAge() {
        const g = genderText(this.p.gender);
        const age = this.p.age != null && this.p.age !== '' ? this.p.age + '岁' : '';
        return [g, age].filter(Boolean).join(' · ');
      },
      diagText() {
        const d = String(this.p.diagnosisName || this.p.admitDiag || '').trim();
        if (!d) { return '暂无诊断'; }
        return d.length > 20 ? d.substring(0, 20) + '…' : d;
      },
      /* 'has' 红闪烁标签 / 'none' 绿色无过敏 / 'unknown' 数据未加载, 不渲染 */
      allergyState() {
        const a = this.p.allergyInfo;
        if (a === undefined || a === null) { return 'unknown'; }
        if (Array.isArray(a)) { return a.length ? 'has' : 'none'; }
        return String(a).trim() ? 'has' : 'none';
      },
      allergyText() {
        const a = this.p.allergyInfo;
        if (Array.isArray(a)) { return '过敏: ' + a.join('、'); }
        return '过敏: ' + String(a || '');
      },
      insuranceText() {
        return INSUR_TYPES[String(this.p.insutype || '')] || '';
      },
      nursingText() {
        return NURSING_LEVELS[Number(this.p.nursingLevel)] || '';
      },
      nursingCls() {
        return 'iw-pb-nurse--' + Number(this.p.nursingLevel);
      },
      admitDaysText() {
        if (this.p.admitDays != null && this.p.admitDays !== '') { return this.p.admitDays; }
        return admitDays(this.p.admitDate);
      },
      balanceText() {
        const v = this.p.depositBalance;
        if (v == null || v === '') { return '--'; }
        const n = Number(v);
        return isNaN(n) ? String(v) : n.toFixed(2);
      }
    },
    template: `
      <div class="iw-pb">
        <div class="iw-pb-bed"><b>{{ p.bedNo || '—' }}</b><span>床</span></div>
        <span class="iw-pb-name">{{ p.patientName || '-' }}</span>
        <span class="iw-pb-meta" v-if="genderAge">{{ genderAge }}</span>
        <span class="iw-pb-sep"></span>
        <span class="iw-pb-kv">住院号 <b>{{ p.inpNo || '-' }}</b></span>
        <span class="iw-pb-sep"></span>
        <span class="iw-pb-kv iw-pb-diag" :title="p.diagnosisName || p.admitDiag || ''">诊断 <b>{{ diagText }}</b></span>
        <span class="iw-pb-tag iw-pb-tag--allergy" v-if="allergyState === 'has'" :title="allergyText">{{ allergyText }}</span>
        <span class="iw-pb-tag iw-pb-tag--allergy-none" v-else-if="allergyState === 'none'">无过敏</span>
        <span class="iw-pb-tag iw-pb-tag--insur" v-if="insuranceText">{{ insuranceText }}</span>
        <span class="iw-pb-kv">入院 <b>第{{ admitDaysText }}天</b></span>
        <span class="iw-pb-nurse" :class="nursingCls" v-if="nursingText">{{ nursingText }}</span>
        <span class="iw-pb-bal">余额 ¥{{ balanceText }}</span>
        <span class="iw-pb-right"><slot></slot></span>
      </div>
    `
  };
  HIS.components = HIS.components || {};
  HIS.components.PatientBanner = PatientBanner;

  /* ============================================================
   * InpDoctorDashboard 我的工作台: 4统计卡 + 近7日入出院趋势 + 今日手术排程 + 待办列表
   * 接口: GET /api/his/inp/dashboard/doctor (deptId 可省, 服务端回落登录医生科室)
   * 刷新: 30s 定时 silent 轮询; 点击统计卡/待办 emit('go-tab') 交主框架切换页签
   * ============================================================ */
  const InpDoctorDashboard = {
    name: 'InpDoctorDashboard',
    emits: ['go-tab'],
    data() {
      return { loading: false, data: null, nowTs: Date.now(), timer: null, chart: null, resizeTimer: null, criticalCount: 0, transferPending: 0, myCritical: 0, abxAlerts: [], abxAlertVisible: false };
    },
    computed: {
      ds() { return this.data || {}; },
      todoStats() { return this.ds.todoStats || {}; },
      patientStats() { return this.ds.patientStats || {}; },
      statCards() {
        const t = this.todoStats;
        const ps = this.patientStats;
        return [
          { key: 'record', label: '待写病历', value: Number(t.pendingRecords) || 0, cls: 'iw-dcard--warning', tab: 'record' },
          { key: 'consult', label: '待处理会诊', value: Number(t.pendingConsults) || 0, cls: 'iw-dcard--primary', tab: 'consult' },
          { key: 'patients', label: '在院患者', value: Number(ps.total) || 0, cls: 'iw-dcard--primary', tab: 'overview' },
          { key: 'admit', label: '今日入 / 出', value: (Number(ps.todayAdmit) || 0) + ' / ' + (Number(ps.todayDischarge) || 0), cls: 'iw-dcard--success', tab: 'overview' }
        ];
      },
      trendRows() { return this.ds.recentAdmissions || []; },
      surgeries() { return this.ds.todaySurgeries || []; },
      todoList() {
        const rows = this.ds.deadlineAlerts || [];
        const now = this.nowTs;
        return rows.slice(0, 10).map((r, i) => {
          const dl = this.parseTs(r.deadlineTime);
          const diff = dl ? dl - now : null;
          const over = diff != null && diff <= 0;
          return {
            key: r.id != null ? String(r.id) : 't' + i,
            inpVisitId: r.inpVisitId,
            typeText: '病历',
            tagType: 'warning',
            bedNo: r.bedNo,
            patientName: r.patientName || '-',
            summary: r.title || '病历待写',
            over: over,
            leftText: diff == null ? '-' : (over ? '已超时 ' + this.durText(-diff) : '剩 ' + this.durText(diff))
          };
        });
      }
    },
    methods: {
      parseTs(v) {
        if (!v) { return null; }
        const t = new Date(String(v).replace(' ', 'T')).getTime();
        return isNaN(t) ? null : t;
      },
      durText(ms) {
        if (ms < 60000) { return '不到1分钟'; }
        const m = Math.floor(ms / 60000);
        if (m >= 1440) { return Math.floor(m / 1440) + '天' + Math.floor((m % 1440) / 60) + '小时'; }
        if (m >= 60) { const h = Math.floor(m / 60); const mm = m % 60; return h + '小时' + (mm ? mm + '分' : ''); }
        return m + '分钟';
      },
      /* 手术排程时间: 兼容 ISO 字符串与 epoch 毫秒(Jackson 默认序列化 java.sql.Timestamp) */
      fmtTime(v) {
        if (v == null || v === '') { return '待定'; }
        if (typeof v === 'number') {
          const d = new Date(v);
          return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
        }
        const s = String(v).replace('T', ' ');
        return s.length >= 16 ? s.substring(11, 16) : s;
      },
      load(silent) {
        const vm = this;
        if (!silent) { vm.loading = true; }
        const u = HIS.getUser() || {};
        const deptId = u.deptId != null ? u.deptId : u.dept_id;
        let url = '/api/his/inp/dashboard/doctor';
        if (deptId != null && deptId !== '') { url += '?deptId=' + encodeURIComponent(deptId); }
        /* 危急值未处理计数(T37): 并行拉取, 失败静默(不影响主看板) */
        HIS.get('/api/his/inp/critical-value/unhandled-count').then(function (n) {
          vm.criticalCount = Number(n) || 0;
        }).catch(function () { /* 静默 */ });
        /* 批次B 流转处理聚合: 待审批转科(status=1) + 本人待办危急值(my-pending), 并行静默 */
        HIS.get('/api/his/inp/transfer/list?status=1&page=1&size=1').then(function (d) {
          vm.transferPending = Number(d && d.total) || 0;
        }).catch(function () { /* 静默 */ });
        HIS.get('/api/medtech/critical-values/my-pending').then(function (list) {
          vm.myCritical = (list || []).length;
        }).catch(function () { /* 静默 */ });
        /* 阶段2b: 本人长期抗菌医嘱处方权到期/越级告警扫描(只读), 并行静默 */
        HIS.get('/api/his/inp/order/abx-expiry-alerts').then(function (list) {
          vm.abxAlerts = list || [];
        }).catch(function () { vm.abxAlerts = []; });
        return HIS.get(url)
          .then(function (data) {
            vm.data = data || {};
            vm.nowTs = Date.now();
            vm.$nextTick(function () { vm.renderChart(); });
          })
          .catch(function (e) { if (!silent) { HIS.notifyError(e); } })
          .finally(function () { vm.loading = false; });
      },
      renderChart() {
        const el = this.$refs.chart;
        const rows = this.trendRows;
        if (!el || !window.echarts || !rows.length) { return; }
        if (!this.chart) { this.chart = window.echarts.init(el, 'yb'); }
        const T = HIS.theme || {};
        const dates = rows.map(function (r) { return String(r.date || '').substring(5); });
        const admits = rows.map(function (r) { return Number(r.admitCount) || 0; });
        const dis = rows.map(function (r) { return Number(r.dischargeCount) || 0; });
        this.chart.setOption({
          color: [T.brand || '#1a5c9e', T.success || '#3c862d'],
          tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
          legend: T.legend ? T.legend({ data: ['入院', '出院'], right: 0, top: 0 }) : { data: ['入院', '出院'], right: 0, top: 0 },
          grid: { left: 8, right: 12, top: 36, bottom: 4, containLabel: true },
          xAxis: Object.assign({ type: 'category', boundaryGap: false, data: dates }, T.catAxis ? T.catAxis() : {}),
          yAxis: Object.assign({ type: 'value', minInterval: 1 }, T.valAxis ? T.valAxis() : {}),
          series: [
            { name: '入院', type: 'line', smooth: true, symbolSize: 6, data: admits, areaStyle: { opacity: .08 } },
            { name: '出院', type: 'line', smooth: true, symbolSize: 6, data: dis, areaStyle: { opacity: .08 } }
          ]
        }, true);
      },
      onResize() {
        const vm = this;
        if (this.resizeTimer) { clearTimeout(this.resizeTimer); }
        this.resizeTimer = setTimeout(function () { if (vm.chart) { vm.chart.resize(); } }, 120);
      },
      goCard(c) { this.$emit('go-tab', c.tab); },
      goTodo(row) { this.$emit('go-tab', 'record', row); },
      /* 危急值待办卡片点击: 跳转危急值管理页(T37, HIS.go 由 AppLayout 提供) */
      goCritical() { if (typeof HIS.go === 'function') { HIS.go('critical-value'); } },
      /* 批次B: 待审批转科 → 在院患者管理页(转科审批中心) */
      goTransferApproval() { if (typeof HIS.go === 'function') { HIS.go('inp-patient-list'); } },
      /* 阶段2b: 抗菌到期/越级告警 → 展开待办清单弹窗 */
      goAbxAlerts() { this.abxAlertVisible = true; },
      abxAlertText(t) {
        return t === 'expired' ? '处方权已过期' : (t === 'soon' ? '处方权将到期'
          : (t === 'no-auth' ? '无抗菌处方权' : (t === 'under-level' ? '抗菌级别越级' : (t || '提醒'))));
      },
      rowClass({ row }) { return row && row.over ? 'iw-todo--over' : ''; }
    },
    mounted() {
      const vm = this;
      this.load(false);
      this.timer = setInterval(function () { vm.load(true); }, 30000);
      window.addEventListener('resize', this.onResize);
    },
    beforeUnmount() {
      if (this.timer) { clearInterval(this.timer); this.timer = null; }
      if (this.resizeTimer) { clearTimeout(this.resizeTimer); this.resizeTimer = null; }
      window.removeEventListener('resize', this.onResize);
      if (this.chart) { this.chart.dispose(); this.chart = null; }
    },
    template: `
      <div class="iw-dash" v-loading="loading">
        <el-row :gutter="12">
          <el-col :span="6" v-for="c in statCards" :key="c.key">
            <div class="iw-dcard" :class="c.cls" @click="goCard(c)">
              <div class="v">{{ c.value }}</div>
              <div class="l">{{ c.label }}</div>
            </div>
          </el-col>
        </el-row>

        <el-row :gutter="12" style="margin-top:12px">
          <el-col :span="16">
            <div class="iw-dash-panel">
              <div class="iw-dash-card-hd">近7日入出院趋势</div>
              <div ref="chart" class="iw-dash-chart" v-show="trendRows.length"></div>
              <div v-if="!trendRows.length" class="iw-dash-empty">暂无趋势数据</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="iw-dash-panel">
              <div class="iw-dash-card-hd">今日手术排程 <span class="iw-count" v-if="surgeries.length">{{ surgeries.length }}</span></div>
              <el-timeline class="iw-dash-timeline" v-if="surgeries.length">
                <el-timeline-item v-for="s in surgeries" :key="s.id" :timestamp="fmtTime(s.scheduleTime)" placement="top" size="normal">
                  <div class="iw-op-item">
                    <span class="nm">{{ s.patientName }}<template v-if="s.bedNo">({{ s.bedNo }}床)</template></span>
                    <span class="iw-op-meta">{{ s.surgeryName }}</span>
                  </div>
                  <div class="iw-op-meta" v-if="s.roomNo || s.surgeonName">{{ [s.roomNo, s.surgeonName].filter(Boolean).join(' · ') }}</div>
                </el-timeline-item>
              </el-timeline>
              <div v-else class="iw-dash-empty">今日无手术安排</div>
            </div>
          </el-col>
        </el-row>

        <div class="iw-dash-panel idash-flow">
          <div class="iw-dash-card-hd">今日病人 · 流转处理</div>
          <div class="idash-flow-row">
            <div class="idash-chip" @click="goCard({ tab: 'consult' })">
              <span class="dot"></span><span class="k">待处理会诊</span><span class="n">{{ Number(todoStats.pendingConsults) || 0 }}</span>
            </div>
            <div class="idash-chip" :class="{'is-warn': transferPending>0}" @click="goTransferApproval">
              <span class="dot"></span><span class="k">待审批转科</span><span class="n">{{ transferPending }}</span>
            </div>
            <div class="idash-chip" @click="goCard({ tab: 'overview' })">
              <span class="dot"></span><span class="k">今日手术</span><span class="n">{{ surgeries.length }}</span>
            </div>
            <div class="idash-chip" :class="{'is-alert': myCritical>0}" @click="goCritical">
              <span class="dot"></span><span class="k">危急值待办</span><span class="n">{{ myCritical }}</span>
            </div>
            <div class="idash-chip" @click="goCard({ tab: 'overview' })">
              <span class="dot"></span><span class="k">今日入 / 出 / 检</span><span class="n">{{ Number(patientStats.todayAdmit)||0 }} / {{ Number(patientStats.todayDischarge)||0 }} / {{ Number(patientStats.todayExam)||0 }}</span>
            </div>
            <div class="idash-chip" :class="{'is-alert': abxAlerts.length>0}" @click="goAbxAlerts">
              <span class="dot"></span><span class="k">抗菌到期/越级提醒</span><span class="n">{{ abxAlerts.length }}</span>
            </div>
          </div>
        </div>

        <el-dialog v-model="abxAlertVisible" title="抗菌药物处方权到期 / 越级提醒" width="760px" append-to-body>
          <div v-if="!abxAlerts.length" class="idash-empty-tip">本人长期抗菌医嘱未发现处方权到期或越级风险</div>
          <el-table v-else :data="abxAlerts" border size="small" max-height="420">
            <el-table-column label="患者" width="120"><template #default="s">{{ s.row.patientName }}<div class="idash-sub">就诊ID {{ s.row.inpVisitId }}</div></template></el-table-column>
            <el-table-column label="医嘱/药品" min-width="180"><template #default="s">{{ s.row.drugName || s.row.orderContent }}<div class="idash-sub">{{ s.row.abxGradeName || ('分级'+s.row.abxGrade) }}</div></template></el-table-column>
            <el-table-column label="开嘱医生" width="140"><template #default="s">{{ s.row.doctorName || ('医生'+s.row.doctorId) }}<div class="idash-sub">抗菌级别 {{ s.row.antibioticLevelName || s.row.antibioticLevel || '无' }}</div></template></el-table-column>
            <el-table-column label="处方权有效期" width="130"><template #default="s">{{ s.row.rxValidUntil || '-' }}</template></el-table-column>
            <el-table-column label="提醒" width="130"><template #default="s"><el-tag :type="s.row.alertType==='soon' ? 'warning' : 'danger'" size="small" disable-transitions>{{ abxAlertText(s.row.alertType) }}</el-tag></template></el-table-column>
          </el-table>
          <template #footer><el-button size="small" @click="abxAlertVisible=false">关闭</el-button></template>
        </el-dialog>

        <div class="iw-dash-panel iw-dash-todo">
          <div class="iw-dash-card-hd">待办事项 <span class="iw-count" v-if="todoList.length">{{ todoList.length }}</span></div>
          <div class="iw-crit-todo" @click="goCritical" title="点击前往危急值管理">
            <span class="lv">危急值</span>
            <span class="num">{{ criticalCount }}</span>
            <span class="txt">条待处理(通知 / 确认 / 处置)</span>
            <span class="go">去处理 →</span>
          </div>
          <el-table v-if="todoList.length" :data="todoList" size="small" :row-class-name="rowClass">
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.tagType" disable-transitions>{{ row.typeText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="患者" width="150">
              <template #default="{ row }">{{ row.bedNo ? row.bedNo + '床 ' : '' }}{{ row.patientName }}</template>
            </el-table-column>
            <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip></el-table-column>
            <el-table-column label="剩余时限" width="140">
              <template #default="{ row }">
                <span :class="row.over ? 'iw-deadline-over' : 'iw-deadline-ok'">{{ row.leftText }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click="goTodo(row)">去处理</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="iw-dash-empty">暂无待办事项</div>
        </div>
      </div>
    `
  };
  HIS.components.InpDoctorDashboard = InpDoctorDashboard;

  /* 关键词搜索防抖计时器(模块级, 本视图单实例) */
  let searchTimer = null;

  const InpDoctorWorkstation = {
    name: 'InpDoctorWorkstation',
    components: {
      'patient-banner': PatientBanner,
      'inp-doctor-dashboard': InpDoctorDashboard,
      'inp-patient-overview': HIS.components.InpPatientOverview,
      'inp-charge-overview': HIS.components.InpChargeOverview,
      'inp-order-panel': HIS.components.InpOrderPanel,
      'inp-diag-panel': HIS.components.InpDiagPanel,
      'inp-record-panel': HIS.components.InpRecordPanel,
      'inp-emr-writer': HIS.components.InpEmrWriter,
      'inp-pathway-panel': HIS.components.InpPathwayPanel,
      'inp-consult-panel': HIS.components.InpConsultPanel,
      'inp-consent-panel': HIS.components.InpConsentPanel,
      'inp-report': HIS.components.InpReportPanel,
      'inp-emrquery': HIS.components.InpEmrqueryPanel,
      'inp-casepage-panel': HIS.components.InpCasePagePanel,
      'inp-rxreview-panel': HIS.components.InpRxReviewPanel
    },
    data() {
      return {
        loadingPatients: false,
        /* 左侧患者列表栏折叠态(true=收起为细导轨) */
        sideCollapsed: false,
        wardId: null,
        wards: [],
        keyword: '',
        /* 视图范围: mine=本人管床(默认); dept=本科室; all=本机构全部。仅管理员可切 dept/all */
        scope: 'mine',
        patients: [],
        total: 0,
        currentVisitId: null,
        currentVisit: null,
        activeTab: 'dashboard',
        deptMap: {},
        /* 批次A: 左患者栏三形态视图(list=列表 / card=细卡 / slim=简卡) + 前端检索筛选 */
        viewMode: 'list',
        filterDeptId: null,
        filterDoctor: null,
        bannerPatient: null,
        bannerSeq: 0,
        ctx: { visible: false, x: 0, y: 0, patient: null },
        transfer: { visible: false, patient: null, targetWardId: null, targetBedId: null, reason: '', beds: [], submitting: false }
      };
    },
    computed: {
      /* 管理员角色可在医生站切换查看本科室/全部管床患者(普通医生恒为本人管床) */
      canBroadenView() {
        return !!(HIS.hasRole && (HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN')));
      },
      doctorName() {
        const u = HIS.getUser();
        return (u && (u.realName || u.name || u.username)) || '-';
      },
      /* 右键菜单防溢出定位(菜单宽约176, 高约260) */
      ctxStyle() {
        const x = Math.min(this.ctx.x, Math.max(window.innerWidth - 184, 0));
        const y = Math.min(this.ctx.y, Math.max(window.innerHeight - 268, 0));
        return { left: x + 'px', top: y + 'px' };
      },
      /* 病案首页页签可见性: 仅出院办理中(3)/已出院(4)显示 */
      showCasePage() {
        const v = this.currentVisit;
        const vs = v && v.visitStatus != null ? Number(v.visitStatus) : NaN;
        return !isNaN(vs) && vs >= 3 && vs !== 5;
      },
      /* 批次A: 前端筛选(科室/诊疗组)在当前结果集上收窄; 关键字交服务端, 床号命中亦在此补过 */
      viewPatients() {
        let list = this.patients || [];
        const kw = String(this.keyword || '').trim();
        if (this.filterDeptId != null && this.filterDeptId !== '') {
          list = list.filter((p) => String(p.deptId) === String(this.filterDeptId));
        }
        if (this.filterDoctor) {
          list = list.filter((p) => String(p.doctorName || '') === String(this.filterDoctor));
        }
        if (kw) {
          const k = kw.toLowerCase();
          list = list.filter(function (p) {
            return String(p.bedNo || '').toLowerCase().indexOf(k) >= 0
              || String(p.patientName || '').toLowerCase().indexOf(k) >= 0
              || String(p.inpNo || '').toLowerCase().indexOf(k) >= 0;
          });
        }
        return list;
      },
      /* 科室/诊疗组下拉项: 取当前患者行内已存在的 deptId/doctorName 去重 */
      deptOptions() {
        const map = {};
        (this.patients || []).forEach((p) => { if (p.deptId != null) { map[String(p.deptId)] = this.deptMap[p.deptId] || ('科室' + p.deptId); } });
        return Object.keys(map).map((id) => ({ id: id, name: map[id] }));
      },
      doctorOptions() {
        const set = [];
        (this.patients || []).forEach(function (p) { const d = p.doctorName; if (d && set.indexOf(d) < 0) { set.push(d); } });
        return set.map(function (d) { return { name: d }; });
      }
    },
    methods: {
      genderText,
      dateText,
      admitDays,
      visitStatusText(v) { return VISIT_STATUS[v] || '-'; },
      visitStatusTag(v) { return VISIT_STATUS_TAG[v] || ''; },
      deptName(id) { return this.deptMap[id] || '-'; },
      isCurrent(p) { return String(this.currentVisitId) === String(p.id); },
      /* ---- 批次A: 三形态视图 / 高亮 / 图形化标识 ---- */
      tabIcon(k) { return WS_ICONS[k] || ''; },
      setView(m) { this.viewMode = m; },
      resetFilters() { this.filterDeptId = null; this.filterDoctor = null; },
      /* 命中高亮: 先转义 HTML 再包裹关键词(大小写不敏感), 无命中原样返回转义文本 */
      hl(value) {
        const s = value == null ? '' : String(value);
        const esc = s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        const kw = String(this.keyword || '').trim();
        if (!kw) { return esc; }
        const ekw = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        try {
          return esc.replace(new RegExp('(' + ekw + ')', 'gi'), '<mark class="idp-mark">$1</mark>');
        } catch (e) { return esc; }
      },
      diagOf(p) { return p.diagnosisName || p.admitDiag || ''; },
      /* 标识徽标: 仅据行内已有字段渲染, 数据缺失项不显示(不臆造)。 */
      patientBadges(p) {
        const out = [];
        if (p.depositBalance != null && p.depositBalance !== '' && Number(p.depositBalance) < 0) { out.push({ cls: 'idp-badge--owed', text: '欠费' }); }
        if (p.criticalFlag === 1 || p.criticalFlag === true || Number(p.criticalFlag) === 1) { out.push({ cls: 'idp-badge--critical', text: '危重' }); }
        const allergy = p.allergyInfo;
        if (typeof allergy === 'string' && allergy.trim()) { out.push({ cls: 'idp-badge--allergy', text: '过敏' }); }
        else if (Array.isArray(allergy) && allergy.length) { out.push({ cls: 'idp-badge--allergy', text: '过敏' }); }
        if (p.nursingLevel != null && NURSING_LEVELS[Number(p.nursingLevel)]) { out.push({ cls: 'idp-badge--nurse' + Number(p.nursingLevel), text: NURSING_LEVELS[Number(p.nursingLevel)] }); }
        if (p.pathwayFlag === 1 || p.pathwayFlag === true || Number(p.pathwayFlag) === 1) { out.push({ cls: 'idp-badge--pathway', text: '路径' }); }
        if (p.surgeryFlag === 1 || p.surgeryFlag === true || Number(p.surgeryFlag) === 1) { out.push({ cls: 'idp-badge--surgery', text: '手术' }); }
        if (p.keyPatientFlag === 1 || p.keyPatientFlag === true || Number(p.keyPatientFlag) === 1) { out.push({ cls: 'idp-badge--key', text: '重点' }); }
        const ins = INSUR_TYPES[String(p.insutype || '')];
        if (ins) { out.push({ cls: 'idp-badge--insur', text: ins }); }
        return out;
      },
      loadWards() {
        const vm = this;
        const orgId = HIS.currentOrgId ? HIS.currentOrgId() : null;
        return HIS.get('/api/his/inp/bed/ward/list' + (orgId ? '?orgId=' + encodeURIComponent(orgId) : ''))
          .then(function (list) { vm.wards = list || []; })
          .catch(function () { vm.wards = []; });
      },
      loadDepts() {
        const vm = this;
        return HIS.get('/api/his/dept/list')
          .then(function (list) {
            const map = {};
            (list || []).forEach(function (d) { map[d.id] = d.deptName; });
            vm.deptMap = map;
          })
          .catch(function () { /* 科室名仅展示用, 失败静默 */ });
      },
      /* 患者列表: 服务端已按当前医生管床范围过滤; immediate=true 立即查询, 否则 300ms 防抖 */
      loadPatients(immediate) {
        const vm = this;
        if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; }
        const run = function () {
          vm.loadingPatients = true;
          let url = '/api/his/inp/doctor/patients?page=1&size=50';
          if (vm.canBroadenView && vm.scope && vm.scope !== 'mine') { url += '&scope=' + encodeURIComponent(vm.scope); }
          if (vm.wardId) { url += '&wardId=' + encodeURIComponent(vm.wardId); }
          const kw = String(vm.keyword || '').trim();
          if (kw) { url += '&keyword=' + encodeURIComponent(kw); }
          HIS.get(url)
            .then(function (data) {
              vm.patients = (data && data.records) || [];
              vm.total = Number(data && data.total) || vm.patients.length;
              /* 当前选中患者仍在列表中时同步刷新信息条(床位/状态可能已变化) */
              if (vm.currentVisitId) {
                const hit = vm.patients.find(p => String(p.id) === String(vm.currentVisitId));
                if (hit) {
                  vm.currentVisit = hit;
                  /* 列表刷新同步横幅基线字段(床位/余额等), 已补挂的护理等级/过敏信息保留 */
                  if (vm.bannerPatient) { vm.bannerPatient = Object.assign({}, vm.bannerPatient, hit); }
                }
              }
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.loadingPatients = false; });
        };
        if (immediate) { run(); } else { searchTimer = setTimeout(run, 300); }
      },
      selectPatient(p, tab) {
        const target = tab || 'overview';
        if (String(this.currentVisitId) === String(p.id)) {
          if (tab) { this.activeTab = tab; }
          return;
        }
        this.currentVisitId = p.id;
        this.currentVisit = p;
        this.activeTab = target;
        this.loadBannerExtras(p);
      },
      /* 横幅数据补挂: 基线=患者列表行; summary 补护理等级/险种/诊断名, 过敏档案补 allergyInfo(空串=无过敏, 未加载=undefined) */
      loadBannerExtras(p) {
        const vm = this;
        const seq = ++this.bannerSeq;
        this.bannerPatient = Object.assign({}, p, { diagnosisName: p.diagnosisName || p.admitDiag || '' });
        HIS.get('/api/his/inp/doctor/patient/' + p.id + '/summary')
          .then(function (data) {
            if (seq !== vm.bannerSeq) { return; }
            const v = (data && data.visit) || {};
            const patch = {};
            if (v.nursingLevel != null) { patch.nursingLevel = v.nursingLevel; }
            if (v.insutype) { patch.insutype = v.insutype; }
            const ds = (data && data.diagnoses) || [];
            if (!vm.bannerPatient.diagnosisName && ds.length) {
              patch.diagnosisName = ds[0].diagName || ds[0].diagnosisName || '';
            }
            vm.bannerPatient = Object.assign({}, vm.bannerPatient, patch);
          })
          .catch(function () { /* 横幅补挂失败静默, 基线字段仍可展示 */ });
        HIS.get('/api/his/inp/allergy/list?visitId=' + encodeURIComponent(p.id))
          .then(function (list) {
            if (seq !== vm.bannerSeq) { return; }
            const names = (list || []).map(function (a) { return a.allergenName || ''; }).filter(Boolean);
            vm.bannerPatient = Object.assign({}, vm.bannerPatient, { allergyInfo: names.join('、') });
          })
          .catch(function () { /* 过敏档取数失败保持未知态, 不误报无过敏 */ });
      },
      /* ---- 右键上下文菜单(患者卡片) ---- */
      openPatientCtx(e, p) {
        this.ctx = { visible: true, x: e.clientX, y: e.clientY, patient: p };
      },
      closeCtx() {
        if (this.ctx.visible) { this.ctx.visible = false; }
      },
      ctxAction(kind) {
        const p = this.ctx.patient;
        this.closeCtx();
        if (!p) { return; }
        if (kind === 'wristband') { this.printWristband(p); return; }
        if (kind === 'transfer') { this.openTransfer(p); return; }
        this.selectPatient(p, kind);
      },
      /* 工作台统计卡/待办 -> 切换页签(非 dashboard/overview 需已选患者) */
      onDashTab(name) {
        if (!name) { return; }
        if (name === 'dashboard' || name === 'overview') { this.activeTab = name; return; }
        if (!this.currentVisitId) {
          ElementPlus.ElMessage.warning('请先从左侧选择患者');
          return;
        }
        this.activeTab = name;
      },
      /* 病历书写器 -> 全景时间线: 跳转 emr-patient-timeline 页(HIS.go 由 AppLayout 提供) */
      handleOpenTimeline(patientId) {
        if (typeof HIS.go === 'function') { HIS.go('emr-patient-timeline'); }
      },
      printWristband(p) {
        const visitId = (p && p.id) || this.currentVisitId;
        if (!visitId) { return; }
        HIS.get('/api/his/inp/print/render/wristband?visitId=' + encodeURIComponent(visitId))
          .then(function (html) { HIS.printHtmlFrame(html, '腕带打印 - ' + ((p && p.patientName) || '')); })
          .catch(HIS.notifyError);
      },
      /* ---- 转科 ---- */
      openTransfer(p) {
        this.transfer = { visible: true, patient: p, targetWardId: null, targetBedId: null, reason: '', beds: [], submitting: false };
      },
      loadTransferBeds(wardId) {
        const vm = this;
        this.transfer.targetBedId = null;
        this.transfer.beds = [];
        if (!wardId) { return; }
        const orgId = HIS.currentOrgId ? HIS.currentOrgId() : null;
        HIS.get('/api/his/inp/bed/list?wardId=' + encodeURIComponent(wardId) + (orgId ? '&orgId=' + encodeURIComponent(orgId) : ''))
          .then(function (list) {
            vm.transfer.beds = (list || []).filter(function (b) { return Number(b.status) === 0; });
          })
          .catch(function () { vm.transfer.beds = []; });
      },
      submitTransfer() {
        const vm = this;
        const t = this.transfer;
        if (!t.patient) { return; }
        if (!t.targetWardId || !t.targetBedId) {
          ElementPlus.ElMessage.warning('请选择目标病区与床位');
          return;
        }
        t.submitting = true;
        HIS.put('/api/his/inp/visit/' + t.patient.id + '/transfer', {
          targetWardId: t.targetWardId,
          targetBedId: t.targetBedId,
          reason: t.reason || ''
        })
          .then(function () {
            ElementPlus.ElMessage.success('转科成功');
            t.visible = false;
            vm.loadPatients(true);
          })
          .catch(HIS.notifyError)
          .finally(function () { t.submitting = false; });
      },
      refresh() {
        this.loadPatients(true);
        /* 页签内容组件由 :key=visitId+v-if 控制, 数据刷新走各自的刷新按钮/重新挂载 */
      }
    },
    mounted() {
      ensureStyle();
      this.loadWards();
      this.loadDepts();
      this.loadPatients(true);
      /* 点击任意处 / 在别处右键 自动关闭上下文菜单 */
      this._onDocClick = this.closeCtx.bind(this);
      this._onDocCtxMenu = this.closeCtx.bind(this);
      document.addEventListener('click', this._onDocClick);
      document.addEventListener('contextmenu', this._onDocCtxMenu);
    },
    beforeUnmount() {
      this.bannerSeq++;
      if (this._onDocClick) { document.removeEventListener('click', this._onDocClick); this._onDocClick = null; }
      if (this._onDocCtxMenu) { document.removeEventListener('contextmenu', this._onDocCtxMenu); this._onDocCtxMenu = null; }
    },
    template: `
      <div class="iw-workbench">
        <!-- 折叠态: 细长导轨, 点击任意处展开 -->
        <div v-if="sideCollapsed" class="iw-rail" style="cursor:pointer" title="展开患者列表" @click="sideCollapsed=false">
          <button class="iw-collapse-btn" @click.stop="sideCollapsed=false">\u226b</button>
          <div class="iw-rail-text">患者列表{{ total ? ' (' + total + ')' : '' }}</div>
        </div>
        <aside v-else class="iw-side">
          <div class="iw-side-head">
            <div class="iw-side-bar">
              <b>患者列表{{ total ? ' (' + total + ')' : '' }}</b>
              <button class="iw-collapse-btn" title="折叠患者列表" @click="sideCollapsed=true">\u226a</button>
            </div>
            <el-radio-group v-if="canBroadenView" v-model="scope" size="small" style="width:100%;margin-bottom:8px"
                            @change="loadPatients(true)">
              <el-radio-button label="mine">本人管床</el-radio-button>
              <el-radio-button label="dept">本科室</el-radio-button>
              <el-radio-button label="all">全部</el-radio-button>
            </el-radio-group>
            <el-select v-model="wardId" placeholder="全部病区" clearable size="small" style="width:100%;margin-bottom:8px"
                       @change="loadPatients(true)">
              <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>
            </el-select>
            <el-input v-model="keyword" placeholder="床号 / 姓名 / 住院号" size="small" clearable @input="loadPatients(false)"></el-input>
            <div class="idp-view-seg" style="margin-top:8px">
              <button :class="{'is-on': viewMode==='list'}" @click="setView('list')">列表</button>
              <button :class="{'is-on': viewMode==='card'}" @click="setView('card')">细卡</button>
              <button :class="{'is-on': viewMode==='slim'}" @click="setView('slim')">简卡</button>
            </div>
            <div class="idp-filters">
              <el-select v-model="filterDeptId" placeholder="全部科室" clearable size="small">
                <el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id"></el-option>
              </el-select>
              <el-select v-model="filterDoctor" placeholder="全部诊疗组" clearable size="small">
                <el-option v-for="doc in doctorOptions" :key="doc.name" :label="doc.name" :value="doc.name"></el-option>
              </el-select>
            </div>
          </div>
          <el-scrollbar class="iw-side-body" v-loading="loadingPatients">
            <!-- 列表(list): 保留原有卡片形态 -->
            <div v-if="viewMode==='list'" class="iw-patient" v-for="p in viewPatients" :key="p.id"
                 :class="{'iw-patient--active': isCurrent(p)}" @click="selectPatient(p)"
                 @contextmenu.prevent.stop="openPatientCtx($event, p)">
              <div class="iw-patient-bed">
                <b v-html="hl(p.bedNo || '—')"></b>
                <span>床</span>
              </div>
              <div class="iw-patient-main">
                <div class="r1">
                  <b class="nm" v-html="hl(p.patientName)"></b>
                  <span class="meta">{{ genderText(p.gender) }}{{ p.age != null ? ' · ' + p.age + '岁' : '' }}</span>
                </div>
                <div class="r2">
                  <span class="num" v-html="hl(p.inpNo)"></span>
                  <span>入院{{ admitDays(p.admitDate) }}天</span>
                  <span v-if="scope!=='mine' && p.doctorName" style="color:var(--yb-ink-3)">· {{ p.doctorName }}</span>
                </div>
                <div class="idp-flags" v-if="patientBadges(p).length" style="margin-top:4px">
                  <span v-for="(b,bi) in patientBadges(p)" :key="bi" class="idp-badge" :class="b.cls">{{ b.text }}</span>
                </div>
              </div>
              <el-tag size="small" :type="visitStatusTag(p.visitStatus)" disable-transitions>{{ visitStatusText(p.visitStatus) }}</el-tag>
            </div>
            <!-- 细卡(card): 含标识行的富卡 -->
            <div v-else-if="viewMode==='card'" class="idp-card" v-for="p in viewPatients" :key="p.id"
                 :class="{'is-active': isCurrent(p)}" @click="selectPatient(p)"
                 @contextmenu.prevent.stop="openPatientCtx($event, p)">
              <div class="iw-patient-bed">
                <b v-html="hl(p.bedNo || '—')"></b>
                <span>床</span>
              </div>
              <div class="iw-patient-main">
                <div class="r1">
                  <b class="nm" v-html="hl(p.patientName)"></b>
                  <span class="meta">{{ genderText(p.gender) }}{{ p.age != null ? ' · ' + p.age + '岁' : '' }}</span>
                  <el-tag size="small" :type="visitStatusTag(p.visitStatus)" disable-transitions style="margin-left:auto">{{ visitStatusText(p.visitStatus) }}</el-tag>
                </div>
                <div class="r2">
                  <span class="num" v-html="hl(p.inpNo)"></span>
                  <span>入院{{ admitDays(p.admitDate) }}天</span>
                  <span v-if="p.doctorName" style="color:var(--yb-ink-3)">· {{ p.doctorName }}</span>
                </div>
                <div class="idp-diag" :title="diagOf(p)">{{ diagOf(p) || '暂无诊断' }}</div>
                <div class="idp-dept" v-if="p.deptId">{{ deptName(p.deptId) }}</div>
                <div class="idp-flags" v-if="patientBadges(p).length" style="margin-top:5px">
                  <span v-for="(b,bi) in patientBadges(p)" :key="bi" class="idp-badge" :class="b.cls">{{ b.text }}</span>
                </div>
              </div>
            </div>
            <!-- 简卡(slim): 单行紧凑 床号+姓名+诊断+标识图标 -->
            <div v-else class="idp-slim" v-for="p in viewPatients" :key="p.id"
                 :class="{'is-active': isCurrent(p)}" @click="selectPatient(p)"
                 @contextmenu.prevent.stop="openPatientCtx($event, p)">
              <span class="bed" v-html="hl(p.bedNo || '—')"></span>
              <span class="nm" v-html="hl(p.patientName)"></span>
              <span class="dg">{{ diagOf(p) }}</span>
              <span class="idp-flags">
                <span v-for="(b,bi) in patientBadges(p)" :key="bi" class="idp-badge" :class="b.cls" :title="b.text">{{ b.text }}</span>
              </span>
            </div>
            <div v-if="!viewPatients.length && !loadingPatients" class="iw-empty-line">
              暂无患者{{ wardId ? '(当前病区)' : '' }}, {{ scope==='mine' ? '仅显示本人管床的在院患者' : (scope==='dept' ? '本科室暂无在院患者' : '本机构暂无在院患者') }}
            </div>
          </el-scrollbar>
        </aside>

        <section class="iw-main">
          <patient-banner v-if="bannerPatient" :patient="bannerPatient">
            <el-tag size="small" :type="visitStatusTag(bannerPatient.visitStatus)" disable-transitions>{{ visitStatusText(bannerPatient.visitStatus) }}</el-tag>
            <el-button size="small" :loading="loadingPatients" @click="refresh">刷新</el-button>
          </patient-banner>

          <el-tabs v-model="activeTab" class="iw-tabs">
            <el-tab-pane label="我的工作台" name="dashboard">
              <inp-doctor-dashboard v-if="activeTab === 'dashboard'" @go-tab="onDashTab"></inp-doctor-dashboard>
            </el-tab-pane>
            <el-tab-pane label="概览" name="overview">
              <inp-patient-overview v-if="activeTab === 'overview' && currentVisitId" :visit-id="currentVisitId" :key="'ov-' + currentVisitId"></inp-patient-overview>
              <div v-else-if="activeTab === 'overview'" class="iw-placeholder" style="height:100%">
                <span>请从左侧选择患者</span>
                <span class="iw-dim">支持姓名 / 住院号检索, 右键患者卡片可快捷开医嘱、写病历</span>
              </div>
            </el-tab-pane>
            <el-tab-pane label="医嘱" name="order" :disabled="!currentVisitId">
              <inp-order-panel v-if="activeTab === 'order'" :visit-id="currentVisitId" :key="'od-' + currentVisitId"></inp-order-panel>
            </el-tab-pane>
            <el-tab-pane label="诊断" name="diag" :disabled="!currentVisitId">
              <inp-diag-panel v-if="activeTab === 'diag'" :visit-id="currentVisitId" :key="'dg-' + currentVisitId"></inp-diag-panel>
            </el-tab-pane>
            <el-tab-pane label="病历" name="record" :disabled="!currentVisitId">
              <inp-emr-writer v-if="activeTab === 'record' && currentVisitId"
                :inp-visit-id="currentVisitId"
                :patient-id="currentVisit ? currentVisit.patientId : null"
                :dept-code="currentVisit ? String(currentVisit.deptId || '') : ''"
                :bed-no="currentVisit ? (currentVisit.bedNo || '') : ''"
                :patient-name="currentVisit ? (currentVisit.patientName || '') : ''"
                :patient="currentVisit"
                @open-timeline="handleOpenTimeline"
                :key="'rc-' + currentVisitId">
              </inp-emr-writer>
            </el-tab-pane>
            <el-tab-pane label="费用" name="charge" :disabled="!currentVisitId">
              <inp-charge-overview v-if="activeTab === 'charge'" :visit-id="currentVisitId" :key="'ch-' + currentVisitId"></inp-charge-overview>
            </el-tab-pane>
            <el-tab-pane label="临床路径" name="pathway" :disabled="!currentVisitId">
              <inp-pathway-panel v-if="activeTab === 'pathway'" :visit-id="currentVisitId" :key="'pw-' + currentVisitId"></inp-pathway-panel>
            </el-tab-pane>
            <el-tab-pane label="会诊" name="consult" :disabled="!currentVisitId">
              <inp-consult-panel v-if="activeTab === 'consult'" :visit-id="currentVisitId" :key="'cs-' + currentVisitId"></inp-consult-panel>
            </el-tab-pane>
            <el-tab-pane label="知情同意" name="consent" :disabled="!currentVisitId">
              <inp-consent-panel v-if="activeTab === 'consent'" :visit-id="currentVisitId" :key="'ct-' + currentVisitId"></inp-consent-panel>
            </el-tab-pane>
            <el-tab-pane name="report" lazy :disabled="!currentVisitId">
              <template #label><span style="display:inline-flex;align-items:center;gap:4px"><i v-html="tabIcon('report')"></i>报告</span></template>
              <inp-report v-if="activeTab === 'report' && currentVisitId" :patient-id="currentVisit ? currentVisit.patientId : null" :visit-id="currentVisitId" :key="'rp-' + currentVisitId"></inp-report>
            </el-tab-pane>
            <el-tab-pane name="emrquery" lazy>
              <template #label><span style="display:inline-flex;align-items:center;gap:4px"><i v-html="tabIcon('emr')"></i>病历查询</span></template>
              <inp-emrquery v-if="activeTab === 'emrquery'" :visit-id="currentVisitId" :key="'eq-' + (currentVisitId || 'none')"></inp-emrquery>
            </el-tab-pane>
            <el-tab-pane label="处方点评" name="rxreview" lazy :disabled="!currentVisitId">
              <inp-rxreview-panel v-if="activeTab === 'rxreview' && currentVisitId" :patient-id="currentVisit ? currentVisit.patientId : null" :visit-id="currentVisitId" :key="'rr-' + currentVisitId"></inp-rxreview-panel>
            </el-tab-pane>
            <el-tab-pane v-if="showCasePage" label="病案首页" name="casepage">
              <inp-casepage-panel v-if="activeTab === 'casepage'" :visit-id="currentVisitId" :visit-status="currentVisit ? currentVisit.visitStatus : null" :key="'cp-' + currentVisitId"></inp-casepage-panel>
            </el-tab-pane>
          </el-tabs>
        </section>

        <!-- 患者卡片右键菜单 -->
        <div class="iw-ctx" v-if="ctx.visible" :style="ctxStyle" @contextmenu.prevent>
          <div class="iw-ctx-item" @click="ctxAction('overview')">查看概览</div>
          <div class="iw-ctx-item" @click="ctxAction('order')">开医嘱</div>
          <div class="iw-ctx-item" @click="ctxAction('record')">写病历</div>
          <div class="iw-ctx-item" @click="ctxAction('consult')">申请会诊</div>
          <div class="iw-ctx-sep"></div>
          <div class="iw-ctx-item" @click="ctxAction('wristband')">打印腕带</div>
          <div class="iw-ctx-item" @click="ctxAction('transfer')">转科</div>
        </div>

        <!-- 转科对话框 -->
        <el-dialog v-model="transfer.visible" title="转科" width="460px" append-to-body>
          <div class="iw-form">
            <div class="iw-form-row">
              <span class="lb">患者</span>
              <span>{{ transfer.patient ? (transfer.patient.bedNo ? transfer.patient.bedNo + '床 ' : '') + transfer.patient.patientName : '-' }}</span>
            </div>
            <div class="iw-form-row">
              <span class="lb">目标病区</span>
              <el-select v-model="transfer.targetWardId" placeholder="选择目标病区" style="width:240px" @change="loadTransferBeds">
                <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>
              </el-select>
            </div>
            <div class="iw-form-row">
              <span class="lb">目标床位</span>
              <el-select v-model="transfer.targetBedId" placeholder="选择空床" style="width:240px" :disabled="!transfer.targetWardId">
                <el-option v-for="b in transfer.beds" :key="b.id" :label="(b.roomNo ? b.roomNo + '房 ' : '') + (b.bedNo || '')" :value="b.id"></el-option>
              </el-select>
            </div>
            <div class="iw-form-row">
              <span class="lb">转科原因</span>
              <el-input v-model="transfer.reason" placeholder="选填" style="width:240px"></el-input>
            </div>
          </div>
          <template #footer>
            <el-button @click="transfer.visible = false">取消</el-button>
            <el-button type="primary" :loading="transfer.submitting" @click="submitTransfer">确认转科</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.views = HIS.views || {};
  HIS.views.InpDoctorWorkstation = InpDoctorWorkstation;

  /* 依赖自检: 子面板脚本须先于本文件加载(挂载 HIS.components), 缺失仅告警不阻断 */
  (function checkDeps() {
    const required = {
      InpPatientOverview: '概览页签',
      InpChargeOverview: '费用页签',
      InpOrderPanel: '医嘱页签',
      InpDiagPanel: '诊断页签',
      InpRecordPanel: '病历页签(旧)',
      InpEmrWriter: '病历书写器(P2)',
      InpPathwayPanel: '临床路径页签',
      InpConsultPanel: '会诊页签',
      InpConsentPanel: '知情同意页签',
      InpReportPanel: '报告页签',
      InpEmrqueryPanel: '病历查询页签',
      InpCasePagePanel: '病案首页页签'
    };
    const missing = Object.keys(required).filter(k => !HIS.components || !HIS.components[k]);
    if (missing.length) {
      console.warn('[住院医生站] 子面板组件缺失: ' + missing.map(k => k + '(' + required[k] + ')').join(', ')
        + '。请确认脚本加载顺序: inp-patient-overview.js -> inp-order-panel.js -> inp-diag-panel.js -> inp-record-panel.js -> inp-pathway-panel.js -> inp-consult-panel.js -> inp-consent-panel.js -> inp-casepage-panel.js -> inp-doctor.js');
    }
  })();
})();
