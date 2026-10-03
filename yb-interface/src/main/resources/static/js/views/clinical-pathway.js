/* 临床路径模板管理: 左侧模板列表(336px, 搜索/状态筛选/分页) + 右侧「路径纵览头 + 监护时间轴节点编辑器」
 * 设计语言: 医疗监护仪轨迹 —— 品牌渐变纵览头 + 大数字指标带(--yb-fs-2xl 对 11px 注标) +
 *           ECG 虚线轴日节点 + 任务类型色相条(医/护/检/验/教) + hover 才浮现的行操作
 * 接口: GET/POST /api/his/pathway/template (list?orgId=&keyword=&status=&page=&size= | POST | PUT /{id} | PUT /{id}/status | POST /{id}/copy)
 *       GET /api/his/pathway/template/{id} -> {template, nodes:[{node, tasks}]}
 *       /api/his/pathway/node (GET /list/{templateId} | POST | PUT /{id} | DELETE /{id})
 *       /api/his/pathway/task (POST | PUT /{id} | DELETE /{id})
 * 检索源: /api/community-dict/diag-dict/page?dictType=west&status=1 (ICD-10 诊断)
 *         /api/org-catalog/available/drug|charge|med-dict (本机构可开药品/收费项目/用法频次)
 * 注册: HIS.views.ClinicalPathwayManage (须在 app.js 之前加载); 样式以 <style id="clinical-pathway-css"> 注入, 前缀 cp-* */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  const TASK_TYPE = { 1: '医嘱', 2: '护理', 3: '检查', 4: '检验', 5: '宣教' };
  const ORDER_TYPE = { 1: '长期', 2: '临时' };
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  const PATHWAY_STATUS = { 1: '启用', 0: '停用' };
  /* 任务类型色相: 医嘱=品牌蓝 护理=青 检查=琥珀 检验=紫 宣教=绿(全部取自 theme.css --yb-fill-*) */
  const TASK_TYPE_TOKEN = { 1: '--yb-fill-info', 2: '--yb-fill-teal', 3: '--yb-fill-warning', 4: '--yb-fill-purple', 5: '--yb-fill-success' };

  function money(value) {
    const n = Number(value);
    return isNaN(n) ? '0.00' : n.toFixed(2);
  }

  function text(v) { return v == null ? '' : String(v); }

  /* ============================================================
   * 样式(注入一次): 复用 theme.css 的 --yb-* 令牌, 不改动既有 CSS 文件
   * ============================================================ */
  const CSS = `
.cp-root { height:calc(100vh - 88px); min-height:520px; display:flex; flex-direction:column; }
.cp-caption { flex:none; display:flex; align-items:baseline; gap:10px; margin-bottom:10px; }
.cp-caption .t { font-size:var(--yb-fs-xl); font-weight:700; letter-spacing:var(--yb-tracking-tight); color:var(--yb-ink-1); border-left:3px solid var(--yb-brand); padding-left:11px; }
.cp-caption .hint { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.cp-workbench { flex:1; min-height:0; display:flex; overflow:hidden; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); }

/* ===== 左栏: 模板列表 ===== */
.cp-side { width:336px; flex:none; display:flex; flex-direction:column; border-right:1px solid var(--yb-border); background:var(--yb-surface-2); }
.cp-side-head { flex:none; padding:12px 12px 10px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border-light); }
.cp-side-tools { display:flex; gap:8px; align-items:center; }
.cp-side-tools .grow { flex:1; min-width:0; }
.cp-side-count { margin-top:8px; display:flex; align-items:center; gap:5px; font-size:var(--yb-fs-cap); letter-spacing:.04em; color:var(--yb-ink-4); font-variant-numeric:tabular-nums; }
.cp-side-count b { color:var(--yb-ink-3); font-weight:700; }
.cp-side-body { flex:1; min-height:0; }
/* 卡片式条目: 左侧 3px 状态脊(绿=启用/灰=停用), hover 浮现动作簇, 选中态浅底+加粗脊 */
.cp-tpl { position:relative; margin:8px 10px; padding:10px 12px 9px 14px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); cursor:pointer; background:var(--yb-surface); box-shadow:var(--yb-sh-1); transition:box-shadow var(--yb-dur) var(--yb-ease), border-color var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }
.cp-tpl::before { content:''; position:absolute; left:0; top:0; bottom:0; width:3px; border-radius:var(--yb-r-md) 0 0 var(--yb-r-md); background:var(--yb-ink-disabled); transition:width var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease); }
.cp-tpl.is-on::before { background:var(--yb-fill-success); }
.cp-tpl:hover { border-color:var(--yb-brand-border); box-shadow:var(--yb-sh-2); transform:translateY(-1px); }
.cp-tpl.is-active { border-color:var(--yb-brand-border); background:var(--yb-brand-subtle); }
.cp-tpl.is-active::before { width:4px; background:var(--yb-brand); }
.cp-tpl .r1 { display:flex; align-items:center; gap:6px; }
.cp-tpl .r1 .nm { flex:1; min-width:0; font-size:var(--yb-fs-md); font-weight:600; color:var(--yb-ink-1); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; letter-spacing:var(--yb-tracking-tight); }
.cp-tpl .st { flex:none; width:7px; height:7px; border-radius:50%; background:var(--yb-ink-disabled); }
.cp-tpl.is-on .st { background:var(--yb-fill-success); box-shadow:0 0 0 3px var(--yb-success-bg); }
.cp-tpl .r2 { margin-top:5px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cp-tpl .r2 .icd { font-family:var(--yb-font-mono); font-size:var(--yb-fs-cap); color:var(--yb-ink-4); margin-left:4px; }
.cp-tpl .r3 { margin-top:7px; display:flex; align-items:center; gap:9px; font-size:var(--yb-fs-cap); color:var(--yb-ink-3); font-variant-numeric:tabular-nums; }
.cp-tpl .r3 .code { font-family:var(--yb-font-mono); color:var(--yb-ink-4); max-width:112px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cp-tpl .r3 .ver { padding:0 5px; height:16px; line-height:16px; border-radius:var(--yb-r-pill); background:var(--yb-surface-3); color:var(--yb-ink-3); font-weight:600; }
.cp-tpl.is-active .r3 .ver { background:var(--yb-surface); }
.cp-tpl .r3 .money { margin-left:auto; color:var(--yb-gold); font-family:var(--yb-font-mono); }
/* 卡片操作簇: hover/选中才浮现, 不压诊断信息行 */
.cp-tpl-ops { position:absolute; right:8px; bottom:7px; display:flex; gap:2px; opacity:0; transform:translateY(3px); transition:opacity var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }
.cp-tpl:hover .cp-tpl-ops, .cp-tpl.is-active .cp-tpl-ops { opacity:1; transform:none; }
.cp-empty-line { padding:26px 0; text-align:center; color:var(--yb-ink-4); font-size:var(--yb-fs-sm); }
.cp-side-pager { flex:none; display:flex; justify-content:center; padding:7px 0; border-top:1px solid var(--yb-border-light); background:var(--yb-surface); }

/* ===== 右栏: 详情/编辑器 ===== */
.cp-main { flex:1; min-width:0; display:flex; flex-direction:column; overflow:hidden; background:var(--yb-surface); }
/* 空态: 深墨蓝大水印 + 指引 */
.cp-empty { flex:1; display:flex; flex-direction:column; gap:10px; align-items:center; justify-content:center; color:var(--yb-ink-4); background:radial-gradient(62% 52% at 50% 40%, var(--yb-surface-2) 0%, var(--yb-surface) 100%); }
.cp-empty .wm { font-size:64px; font-weight:800; line-height:1; color:transparent; -webkit-text-stroke:1.5px var(--yb-brand-border); letter-spacing:.06em; }
.cp-empty .big { font-size:var(--yb-fs-xl); font-weight:700; color:var(--yb-ink-2); letter-spacing:var(--yb-tracking-tight); }
.cp-empty .sub { font-size:var(--yb-fs-md); color:var(--yb-ink-3); }
.cp-empty .flow { margin-top:2px; display:flex; align-items:center; gap:8px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.cp-empty .flow i { font-style:normal; padding:3px 10px; border:1px dashed var(--yb-brand-border); border-radius:var(--yb-r-pill); background:var(--yb-surface); }
.cp-detail { flex:1; min-height:0; display:flex; flex-direction:column; overflow:hidden; }

/* 路径纵览头: 深墨蓝渐变(呼应顶栏/登录页), 白字大标题 + 右置大数字指标带 */
.cp-head { flex:none; position:relative; overflow:hidden; display:flex; gap:20px; padding:16px 20px 14px; color:#fff; background:var(--yb-header-grad); }
.cp-head::after { content:''; position:absolute; left:0; right:0; bottom:10px; height:26px; opacity:.16; pointer-events:none; background:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='26' viewBox='0 0 240 26'%3E%3Cpath d='M0 13h40l8-9 7 18 6-9h44l9-6 6 12 7-6h103' fill='none' stroke='%23ffffff' stroke-width='1.4'/%3E%3C/svg%3E") repeat-x left center; animation:cp-ecg 18s linear infinite; }
@keyframes cp-ecg { from { background-position-x:0; } to { background-position-x:-240px; } }
.cp-head-main { flex:1; min-width:0; position:relative; z-index:1; }
.cp-head .badges { display:flex; align-items:center; gap:7px; flex-wrap:wrap; }
.cp-head .badges .nm { font-size:var(--yb-fs-xl); font-weight:700; letter-spacing:var(--yb-tracking-tight); }
.cp-head .chip { display:inline-flex; align-items:center; padding:0 8px; height:20px; border-radius:var(--yb-r-pill); font-size:var(--yb-fs-cap); font-weight:600; line-height:1; background:var(--yb-header-chip); color:#fff; letter-spacing:.02em; }
.cp-head .chip.on { background:rgba(78,154,62,.9); }
.cp-head .chip.off { background:rgba(255,255,255,.2); color:var(--yb-header-ink-2); }
.cp-head .chip.code { font-family:var(--yb-font-mono); font-weight:500; letter-spacing:0; }
.cp-head .meta { margin-top:9px; display:flex; flex-wrap:wrap; gap:4px 20px; font-size:var(--yb-fs-base); color:var(--yb-header-ink-2); }
.cp-head .meta b { color:#fff; font-weight:600; }
.cp-head .meta .mono { font-family:var(--yb-font-mono); font-variant-numeric:tabular-nums; }
.cp-head .desc { margin-top:7px; font-size:var(--yb-fs-sm); color:var(--yb-header-ink-2); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
/* 指标带: 28px 大数字 vs 11px 注标, 等宽数字, 金色列专用于钱 */
.cp-stats { flex:none; position:relative; z-index:1; display:flex; align-items:stretch; }
.cp-stat { min-width:92px; padding:2px 16px; display:flex; flex-direction:column; justify-content:center; border-left:1px solid rgba(255,255,255,.16); }
.cp-stat:first-child { border-left:none; }
.cp-stat .num { font-size:var(--yb-fs-2xl); font-weight:700; line-height:1.05; letter-spacing:var(--yb-tracking-tight); font-variant-numeric:tabular-nums; }
.cp-stat .num .u { font-size:var(--yb-fs-sm); font-weight:500; margin-left:3px; color:var(--yb-header-ink-2); }
.cp-stat .num.gold { color:#f2d9a7; }
.cp-stat .lbl { margin-top:5px; font-size:var(--yb-fs-cap); letter-spacing:.08em; color:var(--yb-header-ink-2); white-space:nowrap; }
.cp-head-ops { flex:none; position:relative; z-index:1; display:flex; flex-direction:column; gap:8px; align-items:flex-end; justify-content:center; }
.cp-head-ops .row { display:flex; gap:6px; }
.cp-head-ops .hint { max-width:150px; font-size:var(--yb-fs-cap); line-height:1.5; color:var(--yb-header-ink-2); text-align:right; }
.cp-head .el-button { font-weight:600; }

/* ===== 监护时间轴节点编辑器 ===== */
.cp-body { flex:1; min-height:0; overflow:auto; padding:18px 20px 26px; background:var(--yb-surface); }
.cp-timeline { position:relative; padding-left:46px; }
/* 轨迹轴: 品牌淡线渐隐入底, 节点串其上 */
.cp-timeline::before { content:''; position:absolute; left:17px; top:10px; bottom:10px; width:2px; background:linear-gradient(180deg, var(--yb-brand) 0%, var(--yb-brand-border) 55%, var(--yb-border-light) 100%); border-radius:1px; }
.cp-node { position:relative; margin-bottom:14px; animation:cp-node-in .3s var(--yb-ease) both; }
@keyframes cp-node-in { from { opacity:0; transform:translateY(7px); } to { opacity:1; transform:none; } }
@media (prefers-reduced-motion: reduce) { .cp-node, .cp-head::after { animation:none; } }
/* 日徽标: 圆章浮于轴点, D1/D2 监护编号语言; 首末节点实心 */
.cp-day { position:absolute; left:-46px; top:8px; width:36px; height:36px; border-radius:50%; background:var(--yb-surface); border:2px solid var(--yb-brand-border); box-sizing:border-box; display:flex; flex-direction:column; align-items:center; justify-content:center; line-height:1; box-shadow:0 1px 3px rgba(16,24,40,.10); transition:border-color var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease); }
.cp-day b { font-size:14px; font-weight:700; color:var(--yb-brand); font-variant-numeric:tabular-nums; letter-spacing:-.02em; }
.cp-day i { font-style:normal; font-size:9px; color:var(--yb-ink-4); margin-top:1px; letter-spacing:.04em; }
.cp-node.is-edge .cp-day { background:var(--yb-brand); border-color:var(--yb-brand-strong); }
.cp-node.is-edge .cp-day b { color:#fff; }
.cp-node.is-edge .cp-day i { color:rgba(255,255,255,.75); }
.cp-node:hover .cp-day { border-color:var(--yb-brand); }
/* 节点卡 */
.cp-node-card { border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface); box-shadow:var(--yb-sh-1); overflow:hidden; transition:box-shadow var(--yb-dur) var(--yb-ease), border-color var(--yb-dur) var(--yb-ease); }
.cp-node:hover .cp-node-card { border-color:var(--yb-brand-border); box-shadow:var(--yb-sh-2); }
/* ECG 引线: 徽标与卡片之间的监护连线 */
.cp-node-head { display:flex; align-items:center; gap:9px; padding:9px 12px; background:linear-gradient(180deg, var(--yb-surface), var(--yb-surface-2)); border-bottom:1px dashed var(--yb-border); }
.cp-node-head .nm { color:var(--yb-ink-1); font-weight:700; font-size:var(--yb-fs-md); letter-spacing:var(--yb-tracking-tight); }
.cp-node-head .desc { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:320px; }
.cp-node-head .count { flex:none; padding:0 7px; height:18px; line-height:18px; border-radius:var(--yb-r-pill); background:var(--yb-brand-subtle); color:var(--yb-brand); font-size:var(--yb-fs-cap); font-weight:700; font-variant-numeric:tabular-nums; }
/* 类型分布微条: 该日任务按类型着色的比例条 */
.cp-dist { flex:none; width:84px; height:6px; border-radius:3px; overflow:hidden; display:flex; background:var(--yb-surface-3); }
.cp-dist i { height:100%; }
.cp-node-head .ops { margin-left:auto; flex:none; display:flex; align-items:center; opacity:.25; transition:opacity var(--yb-dur) var(--yb-ease); }
.cp-node-head:hover .ops { opacity:1; }
.cp-node-tasks { padding:4px 0 0; background:var(--yb-surface); }

/* 任务行: 类型色相条 + 主副信息层级 + hover 浮现操作; 去掉 el-table 的重边框 */
.cp-task { display:flex; align-items:center; gap:10px; padding:8px 12px 8px 14px; position:relative; border-bottom:1px solid var(--yb-divider); transition:background var(--yb-dur) var(--yb-ease); }
.cp-task::before { content:''; position:absolute; left:0; top:6px; bottom:6px; width:3px; border-radius:0 2px 2px 0; background:var(--cp-tc, var(--yb-ink-disabled)); }
.cp-task:hover { background:var(--yb-info-light); }
.cp-task:last-child { border-bottom:none; }
.cp-task .tt { flex:none; width:44px; text-align:center; font-size:var(--yb-fs-cap); font-weight:700; letter-spacing:.06em; color:var(--cp-tc, var(--yb-ink-3)); }
.cp-task .main { flex:1; min-width:0; }
.cp-task .c1 { font-size:var(--yb-fs-md); color:var(--yb-ink-1); font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cp-task .c2 { margin-top:2px; display:flex; align-items:center; gap:7px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cp-task .c2 .mono { font-family:var(--yb-font-mono); font-size:var(--yb-fs-cap); color:var(--yb-ink-4); }
.cp-task .c2 .money { font-family:var(--yb-font-mono); color:var(--yb-gold); font-variant-numeric:tabular-nums; }
.cp-task .flag { flex:none; font-size:var(--yb-fs-cap); font-weight:700; letter-spacing:.08em; padding:0 6px; height:18px; line-height:18px; border-radius:var(--yb-r-sm); }
.cp-task .flag.req { color:var(--yb-danger); background:var(--yb-danger-bg); border:1px solid var(--yb-danger-border); }
.cp-task .flag.opt { color:var(--yb-ink-3); background:var(--yb-surface-2); border:1px solid var(--yb-border); }
.cp-task .ops { flex:none; width:112px; display:flex; justify-content:flex-end; gap:2px; opacity:0; transform:translateX(4px); transition:opacity var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }
.cp-task:hover .ops { opacity:1; transform:none; }
.cp-node-add { display:flex; align-items:center; justify-content:center; gap:6px; margin:8px 12px 12px; padding:8px 0; border:1px dashed var(--yb-border-strong); border-radius:var(--yb-r-sm); color:var(--yb-ink-3); font-size:var(--yb-fs-sm); cursor:pointer; background:transparent; transition:all var(--yb-dur) var(--yb-ease); width:calc(100% - 24px); }
.cp-node-add:hover { border-color:var(--yb-brand); color:var(--yb-brand); background:var(--yb-info-light); }
/* 追加天数: 轴末端虚线圆点 */
.cp-add-day { position:relative; padding-left:0; }
.cp-add-day::before { content:''; position:absolute; left:-35px; top:12px; width:14px; height:14px; border-radius:50%; background:var(--yb-surface); border:2px dashed var(--yb-border-strong); box-sizing:content-box; }
.cp-sec-label { display:flex; align-items:center; gap:8px; margin:2px 0 12px; font-size:var(--yb-fs-sm); font-weight:600; letter-spacing:.06em; color:var(--yb-ink-3); }
.cp-sec-label::after { content:''; flex:1; height:1px; background:var(--yb-border-light); }

/* ===== 对话框与表单 ===== */
.cp-form-grid { display:grid; grid-template-columns:1fr 1fr; gap:0 14px; }
.cp-form-sec { display:flex; align-items:center; gap:8px; margin:2px 0 12px; font-size:var(--yb-fs-sm); font-weight:700; letter-spacing:.05em; color:var(--yb-brand); }
.cp-form-sec::before { content:''; width:3px; height:12px; border-radius:2px; background:var(--yb-brand); }
.cp-form-sec .sub { font-weight:400; color:var(--yb-ink-4); letter-spacing:0; }
.cp-picked { display:flex; align-items:center; gap:14px; flex-wrap:wrap; padding:8px 11px; border-radius:var(--yb-r-sm); background:var(--yb-brand-subtle); border:1px solid var(--yb-brand-border); font-size:var(--yb-fs-sm); color:var(--yb-ink-2); margin:0 0 14px; }
.cp-picked b { font-weight:600; }
.cp-picked .money { font-family:var(--yb-font-mono); color:var(--yb-gold); font-variant-numeric:tabular-nums; }
.cp-chip { display:inline-flex; align-items:center; padding:0 7px; height:20px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-sm); line-height:1; background:var(--yb-surface-2); color:var(--yb-ink-3); border:1px solid var(--yb-border); white-space:nowrap; }
.cp-dim { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.cp-opt { display:flex; align-items:center; gap:10px; }
.cp-opt .nm { color:var(--yb-ink-1); }
.cp-opt .sub { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.cp-opt .money { margin-left:auto; font-family:var(--yb-font-mono); color:var(--yb-gold); font-size:var(--yb-fs-sm); }
/* 任务对话框: 类型/分类改为带色分的胶囊选择, 强化"这是哪类任务"的感知 */
.cp-seg { display:flex; gap:6px; flex-wrap:wrap; }
.cp-seg .seg { display:inline-flex; align-items:center; gap:5px; padding:0 11px; height:28px; border:1px solid var(--yb-border-strong); border-radius:var(--yb-r-pill); background:var(--yb-surface); color:var(--yb-ink-2); font-size:var(--yb-fs-sm); font-weight:500; cursor:pointer; transition:all var(--yb-dur) var(--yb-ease); line-height:1; }
.cp-seg .seg .dot { width:7px; height:7px; border-radius:50%; background:var(--cp-tc, var(--yb-ink-disabled)); }
.cp-seg .seg:hover { border-color:var(--yb-brand-border); color:var(--yb-brand); }
.cp-seg .seg.is-on { border-color:var(--yb-brand); background:var(--yb-brand-subtle); color:var(--yb-brand-strong); font-weight:600; box-shadow:0 0 0 2px rgba(26,92,158,.08); }
`;

  function ensureStyle() {
    if (document.getElementById('clinical-pathway-css')) { return; }
    const style = document.createElement('style');
    style.id = 'clinical-pathway-css';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  function emptyTplForm() {
    return {
      pathwayCode: '', pathwayName: '', diseaseCode: '', diseaseName: '',
      deptId: null, avgLength: 7, totalCost: null, description: ''
    };
  }

  function emptyTaskForm() {
    return {
      taskType: 1, orderType: 1, orderCategory: 1,
      orderContent: '', spec: '', dosage: '', dosageUnit: '',
      usageCode: null, freqCode: null, quantity: 1, unitPrice: null,
      drugId: null, chargeItemId: null, isMandatory: 1
    };
  }

  /* 关键词搜索防抖计时器(模块级, 本视图单实例) */
  let searchTimer = null;

  const ClinicalPathwayManage = {
    name: 'ClinicalPathwayManage',
    components: { 'dept-tree-picker': HIS.components.DeptTreePicker },
    data() {
      return {
        /* 左栏列表 */
        loadingList: false,
        templates: [],
        total: 0,
        page: 1,
        size: 20,
        keyword: '',
        filterStatus: null,
        /* 右侧详情 */
        selectedId: null,
        detailLoading: false,
        detail: null,
        collapsedNodes: {},
        /* 基础数据 */
        depts: [],
        usageOptions: [],
        freqOptions: [],
        dictLoaded: false,
        /* 模板对话框 */
        tplDialog: false,
        tplSaving: false,
        tplEditId: null,
        tplForm: emptyTplForm(),
        diagPickCode: null,
        diagOptions: [],
        diagSearching: false,
        /* 节点对话框 */
        nodeDialog: false,
        nodeSaving: false,
        nodeEditId: null,
        nodeForm: { dayNo: 1, nodeName: '', nodeDesc: '' },
        /* 任务对话框 */
        taskDialog: false,
        taskSaving: false,
        taskEditId: null,
        taskNode: null,
        taskForm: emptyTaskForm(),
        pickedDrug: null,
        pickedCharge: null,
        pickDrugId: null,
        pickChargeId: null,
        drugOptions: [],
        drugSearching: false,
        chargeOptions: [],
        chargeSearching: false,
        contentTouched: false
      };
    },
    computed: {
      currentTemplate() { return this.detail ? this.detail.template : null; },
      nodes() { return (this.detail && this.detail.nodes) || []; },
      maxDay() {
        return this.nodes.reduce((m, n) => Math.max(m, Number(n.dayNo) || 0), 0);
      },
      taskTotal() {
        return this.nodes.reduce((s, n) => s + ((n.tasks || []).length), 0);
      },
      deptMap() {
        const map = {};
        this.depts.forEach(d => { map[d.id] = d.deptName; });
        return map;
      },
      usageMap() {
        const map = {};
        this.usageOptions.forEach(o => { map[o.code] = o.name; });
        return map;
      },
      freqMap() {
        const map = {};
        this.freqOptions.forEach(o => { map[o.code] = o.name; });
        return map;
      },
      isDrugTask() { return Number(this.taskForm.orderCategory) === 1; },
      taskNodeLabel() {
        return this.taskNode ? ('第' + this.taskNode.dayNo + '天 · ' + (this.taskNode.nodeName || '')) : '';
      }
    },
    created() {
      ensureStyle();
      this.loadDepts();
      this.loadMedDict();
      this.loadTemplates(true);
    },
    methods: {
      text,
      money,
      statusText(v) { return PATHWAY_STATUS[v] == null ? '-' : PATHWAY_STATUS[v]; },
      taskTypeText(v) { return TASK_TYPE[v] || '-'; },
      orderTypeText(v) { return ORDER_TYPE[v] || '-'; },
      categoryText(v) { return ORDER_CATEGORY[v] || '-'; },
      deptName(id) { return this.deptMap[id] || (id == null ? '-' : '#' + id); },
      usageName(code) { return this.usageMap[code] || code || '-'; },
      freqName(code) { return this.freqMap[code] || code || '-'; },
      /* 任务类型色(监护色带): CSS 变量引用令牌, 保证全站只此一处调色 */
      taskColor(v) {
        const token = TASK_TYPE_TOKEN[v];
        return token ? 'var(' + token + ')' : 'var(--yb-ink-disabled)';
      },
      /* 该日任务类型分布(按 taskType 计数, 保持 1..5 顺序) */
      taskDist(node) {
        const cnt = {};
        (node.tasks || []).forEach(t => {
          const k = Number(t.taskType) || 1;
          cnt[k] = (cnt[k] || 0) + 1;
        });
        return Object.keys(cnt).sort().map(k => ({ type: Number(k), n: cnt[k] }));
      },
      /* 任务副行语义摘要: 药品给 剂量/用法/频次, 其余给 分类·长期/临时·单价 */
      taskDose(t) {
        if (Number(t.orderCategory) !== 1) { return ''; }
        const parts = [];
        if (t.dosage) { parts.push(t.dosage + (t.dosageUnit || '')); }
        if (t.usageCode) { parts.push(this.usageName(t.usageCode)); }
        if (t.freqCode) { parts.push(this.freqName(t.freqCode)); }
        return parts.join(' · ');
      },
      /* ===== 左栏: 列表 ===== */
      loadTemplates(immediate) {
        const vm = this;
        if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; }
        const run = function () {
          vm.loadingList = true;
          let url = '/api/his/pathway/template/list?page=' + vm.page + '&size=' + vm.size;
          const kw = String(vm.keyword || '').trim();
          if (kw) { url += '&keyword=' + encodeURIComponent(kw); }
          if (vm.filterStatus != null && vm.filterStatus !== '') { url += '&status=' + vm.filterStatus; }
          HIS.get(url)
            .then(function (data) {
              vm.templates = (data && data.records) || [];
              vm.total = Number(data && data.total) || vm.templates.length;
              /* 选中项若仍在列表中, 保持高亮; 首屏无选中且有数据时不自动选中(避免误改) */
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.loadingList = false; });
        };
        if (immediate) { run(); } else { searchTimer = setTimeout(run, 300); }
      },
      onPageChange(p) { this.page = p; this.loadTemplates(true); },
      selectTemplate(t) {
        if (String(this.selectedId) === String(t.id)) { return; }
        this.selectedId = t.id;
        this.collapsedNodes = {};
        this.loadDetail(t.id);
      },
      loadDetail(id) {
        const vm = this;
        const tid = id;
        vm.detailLoading = true;
        vm.detail = null;
        HIS.get('/api/his/pathway/template/' + encodeURIComponent(tid))
          .then(function (data) {
            if (String(vm.selectedId) !== String(tid)) { return; }
            vm.detail = data || null;
          })
          .catch(function (e) {
            if (String(vm.selectedId) === String(tid)) { HIS.notifyError(e); }
          })
          .finally(function () { vm.detailLoading = false; });
      },
      refreshAll() {
        this.loadTemplates(true);
        if (this.selectedId) { this.loadDetail(this.selectedId); }
      },
      loadDepts() {
        const vm = this;
        HIS.get('/api/his/dept/enabled')
          .then(function (list) { vm.depts = list || []; })
          .catch(function () { vm.depts = []; });
      },
      loadMedDict() {
        const vm = this;
        if (vm.dictLoaded) { return; }
        vm.dictLoaded = true;
        HIS.get('/api/org-catalog/available/med-dict?dictType=usage')
          .then(function (list) { vm.usageOptions = list || []; }).catch(function () { vm.usageOptions = []; });
        HIS.get('/api/org-catalog/available/med-dict?dictType=freq')
          .then(function (list) { vm.freqOptions = list || []; }).catch(function () { vm.freqOptions = []; });
      },
      isCollapsed(node) { return !!this.collapsedNodes[node.id]; },
      toggleNode(node) { this.collapsedNodes[node.id] = !this.collapsedNodes[node.id]; },
      /* ===== 模板: 新建/编辑/启停/复制 ===== */
      openTplCreate() {
        this.tplEditId = null;
        this.tplForm = emptyTplForm();
        this.diagPickCode = null;
        this.diagOptions = [];
        this.tplDialog = true;
      },
      openTplEdit() {
        const t = this.currentTemplate;
        if (!t) { return; }
        this.tplEditId = t.id;
        this.tplForm = {
          pathwayCode: t.pathwayCode || '',
          pathwayName: t.pathwayName || '',
          diseaseCode: t.diseaseCode || '',
          diseaseName: t.diseaseName || '',
          deptId: t.deptId,
          avgLength: t.avgLength != null ? t.avgLength : 7,
          totalCost: t.totalCost != null ? Number(t.totalCost) : null,
          description: t.description || ''
        };
        this.diagPickCode = t.diseaseCode || null;
        this.diagOptions = [];
        this.tplDialog = true;
      },
      remoteDiagSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.diagOptions = []; return; }
        vm.diagSearching = true;
        HIS.get('/api/community-dict/diag-dict/page?dictType=west&status=1&page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.diagOptions = (data && data.records) || []; })
          .catch(function () { vm.diagOptions = []; })
          .finally(function () { vm.diagSearching = false; });
      },
      onPickDiag(code) {
        const d = this.diagOptions.find(x => String(x.code) === String(code));
        if (!d) { return; }
        this.tplForm.diseaseCode = d.code || '';
        this.tplForm.diseaseName = d.name || '';
      },
      saveTpl() {
        const vm = this;
        if (vm.tplSaving) { return; }
        const name = String(vm.tplForm.pathwayName || '').trim();
        if (!name) { ElementPlus.ElMessage.warning('请填写路径名称'); return; }
        const f = vm.tplForm;
        const payload = {
          pathwayCode: String(f.pathwayCode || '').trim() || null,
          pathwayName: name,
          diseaseCode: String(f.diseaseCode || '').trim() || null,
          diseaseName: String(f.diseaseName || '').trim() || null,
          deptId: f.deptId != null ? Number(f.deptId) : null,
          avgLength: f.avgLength != null ? Number(f.avgLength) : null,
          totalCost: f.totalCost != null && f.totalCost !== '' ? Number(f.totalCost) : null,
          description: String(f.description || '').trim() || null
        };
        vm.tplSaving = true;
        const req = vm.tplEditId
          ? HIS.put('/api/his/pathway/template/' + encodeURIComponent(vm.tplEditId), payload)
          : HIS.post('/api/his/pathway/template', payload);
        req.then(function (t) {
          HIS.notifySuccess(vm.tplEditId ? '模板已更新' : '模板已创建: ' + (t && t.pathwayCode || name));
          vm.tplDialog = false;
          vm.loadTemplates(true);
          if (!vm.tplEditId && t && t.id) {
            vm.selectedId = t.id;
            vm.collapsedNodes = {};
            vm.loadDetail(t.id);
          } else if (vm.tplEditId) {
            vm.loadDetail(vm.tplEditId);
          }
        }).catch(HIS.notifyError).finally(function () { vm.tplSaving = false; });
      },
      toggleStatus() {
        const vm = this;
        const t = vm.currentTemplate;
        if (!t) { return; }
        const next = Number(t.status) === 1 ? '停用' : '启用';
        ElementPlus.ElMessageBox.confirm('确认' + next + '路径模板「' + t.pathwayName + '」？' + (next === '停用' ? '停用后不可用于新入径, 已在径患者不受影响。' : ''), next + '确认', {
          type: 'warning', confirmButtonText: next, cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/pathway/template/' + encodeURIComponent(t.id) + '/status');
        }).then(function () {
          HIS.notifySuccess('模板已' + next);
          vm.refreshAll();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      copyTemplate() {
        const vm = this;
        const t = vm.currentTemplate;
        if (!t) { return; }
        ElementPlus.ElMessageBox.confirm('将深拷贝「' + t.pathwayName + '」的全部节点与任务并升级版本(编码追加 -vN, 新版本默认启用), 确认复制？', '复制新版本', {
          type: 'info', confirmButtonText: '复制', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/pathway/template/' + encodeURIComponent(t.id) + '/copy', {});
        }).then(function (n) {
          HIS.notifySuccess('已复制为 V' + (n.version || '?') + ' 新版本: ' + (n.pathwayCode || ''));
          vm.selectedId = n.id;
          vm.collapsedNodes = {};
          vm.loadTemplates(true);
          vm.loadDetail(n.id);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 节点: 添加天数/编辑/删除 ===== */
      openNodeCreate() {
        const day = this.maxDay + 1;
        this.nodeEditId = null;
        this.nodeForm = { dayNo: day, nodeName: '第' + day + '天', nodeDesc: '' };
        this.nodeDialog = true;
      },
      openNodeEdit(node) {
        this.nodeEditId = node.id;
        this.nodeForm = {
          dayNo: node.dayNo,
          nodeName: node.nodeName || '',
          nodeDesc: node.nodeDesc || ''
        };
        this.nodeDialog = true;
      },
      saveNode() {
        const vm = this;
        if (vm.nodeSaving) { return; }
        const t = vm.currentTemplate;
        if (!t) { return; }
        const dayNo = Number(vm.nodeForm.dayNo);
        if (!dayNo || dayNo < 1) { ElementPlus.ElMessage.warning('第X天必须为不小于1的整数'); return; }
        const payload = {
          templateId: t.id,
          dayNo: dayNo,
          nodeName: String(vm.nodeForm.nodeName || '').trim() || ('第' + dayNo + '天'),
          nodeDesc: String(vm.nodeForm.nodeDesc || '').trim() || null,
          sortNo: 0
        };
        vm.nodeSaving = true;
        const req = vm.nodeEditId
          ? HIS.put('/api/his/pathway/node/' + encodeURIComponent(vm.nodeEditId), payload)
          : HIS.post('/api/his/pathway/node', payload);
        req.then(function () {
          HIS.notifySuccess(vm.nodeEditId ? '节点已更新' : '已添加第' + dayNo + '天节点');
          vm.nodeDialog = false;
          vm.loadDetail(t.id);
        }).catch(HIS.notifyError).finally(function () { vm.nodeSaving = false; });
      },
      removeNode(node) {
        const vm = this;
        const t = vm.currentTemplate;
        if (!t) { return; }
        ElementPlus.ElMessageBox.confirm('确认删除「第' + node.dayNo + '天 · ' + (node.nodeName || '') + '」节点及其下全部任务？已入径患者的执行记录不受影响。', '删除节点', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/pathway/node/' + encodeURIComponent(node.id));
        }).then(function () {
          HIS.notifySuccess('节点已删除');
          vm.loadDetail(t.id);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 任务: 添加/编辑/删除 ===== */
      openTaskCreate(node) {
        this.taskEditId = null;
        this.taskNode = node;
        this.taskForm = emptyTaskForm();
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.contentTouched = false;
        this.taskDialog = true;
        this.loadMedDict();
      },
      openTaskEdit(node, task) {
        this.taskEditId = task.id;
        this.taskNode = node;
        this.taskForm = {
          taskType: task.taskType != null ? task.taskType : 1,
          orderType: task.orderType != null ? task.orderType : 1,
          orderCategory: task.orderCategory != null ? task.orderCategory : 1,
          orderContent: task.orderContent || '',
          spec: task.spec || '',
          dosage: task.dosage || '',
          dosageUnit: task.dosageUnit || '',
          usageCode: task.usageCode || null,
          freqCode: task.freqCode || null,
          quantity: task.quantity != null ? Number(task.quantity) : 1,
          unitPrice: task.unitPrice != null ? Number(task.unitPrice) : null,
          drugId: task.drugId || null,
          chargeItemId: task.chargeItemId || null,
          isMandatory: Number(task.isMandatory) === 1 ? 1 : 0
        };
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.contentTouched = true;
        this.taskDialog = true;
        this.loadMedDict();
      },
      remoteDrugSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.drugOptions = []; return; }
        vm.drugSearching = true;
        HIS.get('/api/org-catalog/available/drug?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.drugOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.drugSearching = false; });
      },
      onPickDrug(id) {
        const vm = this;
        vm.pickDrugId = null;
        const drug = vm.drugOptions.find(d => String(d.id) === String(id));
        if (!drug) { return; }
        vm.pickedDrug = drug;
        vm.taskForm.drugId = drug.id;
        vm.taskForm.spec = drug.spec || '';
        if (!vm.taskForm.dosageUnit) { vm.taskForm.dosageUnit = drug.doseUnit || drug.minUnit || ''; }
        if (drug.retailPrice != null) { vm.taskForm.unitPrice = Number(drug.retailPrice); }
        if (!vm.contentTouched) { vm.taskForm.orderContent = vm.composeDrugContent(); }
      },
      remoteChargeSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.chargeOptions = []; return; }
        vm.chargeSearching = true;
        HIS.get('/api/org-catalog/available/charge?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.chargeOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.chargeSearching = false; });
      },
      onPickCharge(id) {
        const vm = this;
        vm.pickChargeId = null;
        const item = vm.chargeOptions.find(x => String(x.id) === String(id));
        if (!item) { return; }
        vm.pickedCharge = item;
        vm.taskForm.chargeItemId = item.id;
        vm.taskForm.spec = item.spec || '';
        const price = item.execPrice != null ? item.execPrice : item.price;
        if (price != null) { vm.taskForm.unitPrice = Number(price); }
        if (!vm.contentTouched) { vm.taskForm.orderContent = item.itemName || ''; }
      },
      composeDrugContent() {
        const f = this.taskForm;
        const d = this.pickedDrug;
        if (!d) { return f.orderContent || ''; }
        let txt = d.genericName || d.itemName || '';
        if (f.dosage) { txt += ' ' + f.dosage + (f.dosageUnit || ''); }
        const usage = this.usageMap[f.usageCode];
        if (usage) { txt += ' ' + usage; }
        const freq = this.freqMap[f.freqCode];
        if (freq) { txt += ' ' + freq; }
        return txt;
      },
      autoCompose() {
        if (this.isDrugTask) { this.taskForm.orderContent = this.composeDrugContent(); }
        else if (this.pickedCharge) { this.taskForm.orderContent = this.pickedCharge.itemName || ''; }
        this.contentTouched = false;
      },
      onContentInput() { this.contentTouched = true; },
      onCategoryChange() {
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.taskForm.drugId = null;
        this.taskForm.chargeItemId = null;
        this.taskForm.spec = '';
        this.taskForm.dosage = '';
        this.taskForm.usageCode = null;
        this.taskForm.freqCode = null;
        this.taskForm.unitPrice = null;
        this.contentTouched = false;
      },
      saveTask() {
        const vm = this;
        if (vm.taskSaving) { return; }
        const node = vm.taskNode;
        const tpl = vm.currentTemplate;
        if (!node || !tpl) { return; }
        const f = vm.taskForm;
        if (vm.isDrugTask && !f.drugId) {
          ElementPlus.ElMessage.warning('药品类任务请先检索并选中药品');
          return;
        }
        let content = String(f.orderContent || '').trim();
        if (!content && vm.isDrugTask) { content = vm.composeDrugContent(); }
        if (!content) { ElementPlus.ElMessage.warning('请填写医嘱内容(或检索选中项目后自动生成)'); return; }
        if (!f.quantity || Number(f.quantity) <= 0) { ElementPlus.ElMessage.warning('数量必须大于 0'); return; }
        const payload = {
          nodeId: node.id,
          templateId: tpl.id,
          taskType: Number(f.taskType) || 1,
          orderType: Number(f.orderType) || 1,
          orderCategory: Number(f.orderCategory) || 1,
          orderContent: content,
          drugId: vm.isDrugTask ? f.drugId : null,
          chargeItemId: vm.isDrugTask ? null : (f.chargeItemId || null),
          spec: f.spec || null,
          dosage: vm.isDrugTask ? (f.dosage || null) : null,
          dosageUnit: vm.isDrugTask ? (f.dosageUnit || null) : null,
          usageCode: vm.isDrugTask ? (f.usageCode || null) : null,
          freqCode: vm.isDrugTask ? (f.freqCode || null) : null,
          quantity: Number(f.quantity) || 1,
          unitPrice: f.unitPrice != null && f.unitPrice !== '' ? Number(f.unitPrice) : null,
          isMandatory: Number(f.isMandatory) === 1 ? 1 : 0,
          sortNo: 0
        };
        vm.taskSaving = true;
        const req = vm.taskEditId
          ? HIS.put('/api/his/pathway/task/' + encodeURIComponent(vm.taskEditId), payload)
          : HIS.post('/api/his/pathway/task', payload);
        req.then(function () {
          HIS.notifySuccess(vm.taskEditId ? '任务已更新' : '任务已添加');
          vm.taskDialog = false;
          vm.loadDetail(tpl.id);
        }).catch(HIS.notifyError).finally(function () { vm.taskSaving = false; });
      },
      removeTask(node, task) {
        const vm = this;
        const tpl = vm.currentTemplate;
        if (!tpl) { return; }
        ElementPlus.ElMessageBox.confirm('确认删除任务「' + (task.orderContent || '') + '」？', '删除任务', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/pathway/task/' + encodeURIComponent(task.id));
        }).then(function () {
          HIS.notifySuccess('任务已删除');
          vm.loadDetail(tpl.id);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      }
    },
    mounted() {
      ensureStyle();
    },
    template: `
      <div class="cp-root">
        <div class="cp-caption">
          <span class="t">临床路径模板管理</span>
          <span class="hint">按「模板 → 第X天节点 → 任务」三级定义病种入径标准, 必做任务入径后自动转医嘱</span>
        </div>
        <div class="cp-workbench">
        <aside class="cp-side">
          <div class="cp-side-head">
            <div class="cp-side-tools">
              <el-input class="grow" v-model="keyword" placeholder="路径 / 诊断名称" size="small" clearable
                        @input="loadTemplates(false)" @keyup.enter="loadTemplates(true)">
                <template #prefix><span style="font-size:12px;color:var(--yb-icon)">⌕</span></template>
              </el-input>
              <el-select v-model="filterStatus" placeholder="全部" size="small" clearable style="width:88px" @change="loadTemplates(true)">
                <el-option label="启用" :value="1"></el-option>
                <el-option label="停用" :value="0"></el-option>
              </el-select>
              <el-button size="small" type="primary" @click="openTplCreate">新建</el-button>
            </div>
            <div class="cp-side-count">共 <b>{{ total }}</b> 条路径模板 · 本页 <b>{{ templates.length }}</b></div>
          </div>
          <el-scrollbar class="cp-side-body" v-loading="loadingList">
            <div v-for="t in templates" :key="t.id" class="cp-tpl"
                 :class="{'is-active': String(selectedId) === String(t.id), 'is-on': Number(t.status) === 1}"
                 @click="selectTemplate(t)">
              <div class="r1">
                <span class="st"></span>
                <b class="nm" :title="t.pathwayName">{{ t.pathwayName }}</b>
              </div>
              <div class="r2" :title="t.diseaseName">{{ t.diseaseName || '未绑定诊断' }}<span class="icd" v-if="t.diseaseCode">{{ t.diseaseCode }}</span></div>
              <div class="r3">
                <span class="code" :title="t.pathwayCode">{{ t.pathwayCode }}</span>
                <span class="ver">V{{ t.version || 1 }}</span>
                <span>{{ t.avgLength != null ? '住院 ' + t.avgLength + ' 天' : '住院 -' }}</span>
                <span class="money" v-if="t.totalCost != null">¥{{ money(t.totalCost) }}</span>
              </div>
              <div class="cp-tpl-ops" @click.stop>
                <el-button link size="small" @click="selectTemplate(t); openTplEdit(t)" v-if="String(selectedId) === String(t.id)">编辑</el-button>
                <el-button link size="small" @click="selectTemplate(t)">打开</el-button>
              </div>
            </div>
            <div v-if="!templates.length && !loadingList" class="cp-empty-line">暂无路径模板<br>点上方「新建」创建第一个病种入径标准</div>
          </el-scrollbar>
          <div class="cp-side-pager" v-if="total > size">
            <el-pagination small background layout="prev, pager, next" :total="total" :page-size="size"
                           :current-page="page" @current-change="onPageChange"></el-pagination>
          </div>
        </aside>

        <section class="cp-main">
          <div v-if="!selectedId" class="cp-empty">
            <div class="wm">CP</div>
            <span class="big">临床路径模板工作台</span>
            <span class="sub">左侧选择一个模板, 查看并编辑其逐日节点与任务</span>
            <div class="flow"><i>适用诊断 ICD-10</i><span>→</span><i>平均住院日</i><span>→</span><i>第1~N天节点</i><span>→</span><i>任务自动转医嘱</i></div>
            <el-button type="primary" plain style="margin-top:10px" @click="openTplCreate">新建路径模板</el-button>
          </div>
          <div v-else class="cp-detail" v-loading="detailLoading">
            <template v-if="currentTemplate">
              <!-- 路径纵览头: 深墨蓝渐变 + ECG 基线 + 大数字指标带 -->
              <header class="cp-head">
                <div class="cp-head-main">
                  <div class="badges">
                    <span class="nm">{{ currentTemplate.pathwayName }}</span>
                    <span class="chip" :class="Number(currentTemplate.status) === 1 ? 'on' : 'off'">{{ statusText(currentTemplate.status) }}</span>
                    <span class="chip">版本 V{{ currentTemplate.version || 1 }}</span>
                    <span class="chip code">{{ currentTemplate.pathwayCode || '-' }}</span>
                  </div>
                  <div class="meta">
                    <span>适用诊断 <b>{{ currentTemplate.diseaseName || '-' }}</b><span v-if="currentTemplate.diseaseCode" class="mono"> {{ currentTemplate.diseaseCode }}</span></span>
                    <span>科室 <b>{{ deptName(currentTemplate.deptId) }}</b></span>
                  </div>
                  <div class="desc" v-if="currentTemplate.description">{{ currentTemplate.description }}</div>
                </div>
                <div class="cp-stats">
                  <div class="cp-stat">
                    <span class="num">{{ currentTemplate.avgLength != null ? currentTemplate.avgLength : '-' }}<span class="u">天</span></span>
                    <span class="lbl">平均住院日</span>
                  </div>
                  <div class="cp-stat">
                    <span class="num gold">{{ money(currentTemplate.totalCost) }}</span>
                    <span class="lbl">预估总费用(元)</span>
                  </div>
                  <div class="cp-stat">
                    <span class="num">{{ nodes.length }}<span class="u">节点</span></span>
                    <span class="lbl">逐日定义</span>
                  </div>
                  <div class="cp-stat">
                    <span class="num">{{ taskTotal }}<span class="u">项</span></span>
                    <span class="lbl">任务总数</span>
                  </div>
                </div>
                <div class="cp-head-ops">
                  <div class="row">
                    <el-button size="small" @click="openTplEdit">编辑信息</el-button>
                    <el-button size="small" :type="Number(currentTemplate.status) === 1 ? 'warning' : 'success'" plain @click="toggleStatus">{{ Number(currentTemplate.status) === 1 ? '停用' : '启用' }}</el-button>
                    <el-button size="small" @click="copyTemplate">复制新版本</el-button>
                  </div>
                  <span class="hint">节点任务修改立即生效; 已入径患者按旧快照执行</span>
                </div>
              </header>

              <div class="cp-body">
                <div class="cp-sec-label">诊疗路径时间轴 · 共 {{ nodes.length }} 天节点 / {{ taskTotal }} 项任务</div>
                <div class="cp-timeline">
                  <div class="cp-node" v-for="(n, idx) in nodes" :key="n.id"
                       :class="{'is-edge': idx === 0 || n.dayNo === maxDay}"
                       :style="{animationDelay: Math.min(idx, 8) * 40 + 'ms'}">
                    <div class="cp-day"><b>{{ n.dayNo }}</b><i>DAY</i></div>
                    <div class="cp-node-card">
                      <div class="cp-node-head">
                        <span class="nm">{{ n.nodeName }}</span>
                        <span class="desc" v-if="n.nodeDesc" :title="n.nodeDesc">{{ n.nodeDesc }}</span>
                        <span class="count">{{ (n.tasks || []).length }} 任务</span>
                        <span class="cp-dist" v-if="(n.tasks || []).length" :title="'任务类型分布'">
                          <i v-for="d in taskDist(n)" :key="d.type" :style="{width: (100 * d.n / (n.tasks || []).length) + '%', background: taskColor(d.type)}"></i>
                        </span>
                        <span class="ops">
                          <el-button link size="small" @click="toggleNode(n)">{{ isCollapsed(n) ? '展开' : '收起' }}</el-button>
                          <el-button link type="primary" size="small" @click="openNodeEdit(n)">编辑</el-button>
                          <el-button link type="danger" size="small" @click="removeNode(n)">删除</el-button>
                        </span>
                      </div>
                      <div class="cp-node-tasks" v-show="!isCollapsed(n)">
                        <div class="cp-task" v-for="t in n.tasks || []" :key="t.id" :style="{'--cp-tc': taskColor(t.taskType)}">
                          <span class="tt">{{ taskTypeText(t.taskType) }}</span>
                          <div class="main">
                            <div class="c1" :title="t.orderContent">{{ t.orderContent }}</div>
                            <div class="c2">
                              <span>{{ categoryText(t.orderCategory) }} · {{ orderTypeText(t.orderType) }}</span>
                              <span v-if="taskDose(t)">{{ taskDose(t) }}</span>
                              <span class="mono" v-if="t.spec">{{ t.spec }}</span>
                              <span class="money" v-if="t.unitPrice != null">¥{{ money(t.unitPrice) }} × {{ t.quantity }}</span>
                            </div>
                          </div>
                          <span class="flag" :class="Number(t.isMandatory) === 1 ? 'req' : 'opt'">{{ Number(t.isMandatory) === 1 ? '必做' : '可选' }}</span>
                          <span class="ops">
                            <el-button link type="primary" size="small" @click="openTaskEdit(n, t)">编辑</el-button>
                            <el-button link type="danger" size="small" @click="removeTask(n, t)">删除</el-button>
                          </span>
                        </div>
                        <div v-if="!(n.tasks || []).length" class="cp-empty-line" style="padding:16px 0">该天暂无任务 —— 添加后入径患者将按日执行</div>
                        <button class="cp-node-add" @click="openTaskCreate(n)">＋ 添加任务<span style="color:var(--yb-ink-4)">（医嘱 / 护理 / 检查 / 检验 / 宣教）</span></button>
                      </div>
                    </div>
                  </div>

                  <div class="cp-add-day">
                    <el-button size="small" type="primary" plain @click="openNodeCreate">＋ 追加天数节点</el-button>
                    <span class="cp-dim" style="margin-left:8px">已配置 {{ nodes.length }} 天, 新节点默认第 {{ maxDay + 1 }} 天</span>
                  </div>
                </div>
              </div>
            </template>
            <div v-else-if="!detailLoading" class="cp-empty">
              <div class="wm">?</div>
              <span class="big">模板加载失败或已删除</span>
              <span class="sub">请从左侧重新选择</span>
            </div>
          </div>
        </section>
        </div>

        <!-- 模板基本信息对话框 -->
        <el-dialog v-model="tplDialog" :title="tplEditId ? '编辑路径模板' : '新建路径模板'" width="640px" top="6vh" :close-on-click-modal="false">
          <el-form label-width="92px" size="small">
            <div class="cp-form-sec">基本信息<span class="sub">编码留空自动生成</span></div>
            <div class="cp-form-grid">
              <el-form-item label="路径编码"><el-input v-model="tplForm.pathwayCode" placeholder="如 PATH001, 留空自动"></el-input></el-form-item>
              <el-form-item label="路径名称" required><el-input v-model="tplForm.pathwayName" placeholder="如: 社区获得性肺炎(成人)"></el-input></el-form-item>
            </div>
            <div class="cp-form-sec">适用条件<span class="sub">诊断决定入径匹配口径</span></div>
            <el-form-item label="诊断检索">
              <el-select v-model="diagPickCode" filterable remote reserve-keyword clearable style="width:100%"
                         :remote-method="remoteDiagSearch" :loading="diagSearching"
                         placeholder="ICD-10 检索: 编码 / 名称 / 拼音简码, 选中回填诊断编码与名称" @change="onPickDiag">
                <el-option v-for="d in diagOptions" :key="d.id || d.code" :label="(d.code || '') + ' ' + (d.name || '')" :value="d.code">
                  <div class="cp-opt"><span class="nm">{{ d.name }}</span><span class="sub">{{ d.code }}</span></div>
                </el-option>
              </el-select>
            </el-form-item>
            <div class="cp-form-grid">
              <el-form-item label="诊断编码"><el-input v-model="tplForm.diseaseCode" placeholder="ICD-10 编码"></el-input></el-form-item>
              <el-form-item label="诊断名称"><el-input v-model="tplForm.diseaseName" placeholder="可检索回填或手填"></el-input></el-form-item>
              <el-form-item label="适用科室">
                <dept-tree-picker v-model="tplForm.deptId" :options="depts" placeholder="选择科室" />
              </el-form-item>
              <el-form-item label="平均住院日"><el-input-number v-model="tplForm.avgLength" :min="1" :max="365" controls-position="right" style="width:130px"></el-input-number></el-form-item>
              <el-form-item label="预估费用">
                <el-input-number v-model="tplForm.totalCost" :min="0" :precision="2" :step="100" controls-position="right" style="width:170px"></el-input-number>
                <span class="cp-dim" style="margin-left:6px">元</span>
              </el-form-item>
            </div>
            <div class="cp-form-sec">说明</div>
            <el-form-item label="路径描述"><el-input v-model="tplForm.description" type="textarea" :rows="3" placeholder="路径适用条件 / 入径标准 / 变异口径等说明(选填)"></el-input></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="tplDialog = false">取消</el-button>
            <el-button type="primary" :loading="tplSaving" @click="saveTpl">保存</el-button>
          </template>
        </el-dialog>

        <!-- 节点对话框 -->
        <el-dialog v-model="nodeDialog" :title="nodeEditId ? '编辑节点' : '添加天数'" width="480px" :close-on-click-modal="false">
          <el-form label-width="86px" size="small">
            <el-form-item label="第X天" required>
              <el-input-number v-model="nodeForm.dayNo" :min="1" :max="365" controls-position="right" style="width:130px"></el-input-number>
              <span class="cp-dim" style="margin-left:8px">同一天可添加多个节点(如"上午/下午")</span>
            </el-form-item>
            <el-form-item label="节点名称"><el-input v-model="nodeForm.nodeName" placeholder="默认: 第X天, 如: 入院首日 / 术前日"></el-input></el-form-item>
            <el-form-item label="节点描述"><el-input v-model="nodeForm.nodeDesc" type="textarea" :rows="2" placeholder="该天诊疗重点说明(选填)"></el-input></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="nodeDialog = false">取消</el-button>
            <el-button type="primary" :loading="nodeSaving" @click="saveNode">保存</el-button>
          </template>
        </el-dialog>

        <!-- 任务对话框 -->
        <el-dialog v-model="taskDialog" :title="taskEditId ? '编辑任务' : '添加任务'" width="720px" top="5vh" :close-on-click-modal="false">
          <el-form label-width="86px" size="small">
            <div class="cp-form-sec">任务定位<span class="sub">{{ taskNodeLabel }}</span></div>
            <el-form-item label="任务类型">
              <div class="cp-seg">
                <span v-for="(label, key) in {1:'医嘱',2:'护理',3:'检查',4:'检验',5:'宣教'}" :key="key"
                      class="seg" :class="{'is-on': Number(taskForm.taskType) === Number(key)}"
                      :style="{'--cp-tc': taskColor(Number(key))}" @click="taskForm.taskType = Number(key)">
                  <span class="dot"></span>{{ label }}
                </span>
              </div>
            </el-form-item>
            <div class="cp-form-grid">
              <el-form-item label="医嘱类型">
                <el-radio-group v-model="taskForm.orderType">
                  <el-radio-button :label="1">长期</el-radio-button>
                  <el-radio-button :label="2">临时</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="医嘱分类">
                <el-select v-model="taskForm.orderCategory" style="width:100%" @change="onCategoryChange">
                  <el-option v-for="(label, key) in {1:'药品',2:'检查',3:'检验',4:'治疗',5:'护理',6:'膳食',7:'其他'}" :key="key" :label="label" :value="Number(key)"></el-option>
                </el-select>
              </el-form-item>
            </div>

            <div class="cp-form-sec">项目来源<span class="sub">检索选中后自动带出规格与价格</span></div>
            <el-form-item v-if="isDrugTask" label="药品检索">
              <el-select class="grow" style="width:100%" v-model="pickDrugId" filterable remote reserve-keyword clearable
                         :remote-method="remoteDrugSearch" :loading="drugSearching"
                         placeholder="通用名 / 编码 / 拼音简码" @change="onPickDrug">
                <el-option v-for="d in drugOptions" :key="d.id" :label="(d.genericName || '') + ' ' + (d.spec || '')" :value="d.id">
                  <div class="cp-opt">
                    <span class="nm">{{ d.genericName }}</span>
                    <span class="sub">{{ d.spec }}</span>
                    <span class="money">¥{{ money(d.retailPrice) }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
            <el-form-item v-else label="项目检索">
              <el-select class="grow" style="width:100%" v-model="pickChargeId" filterable remote reserve-keyword clearable
                         :remote-method="remoteChargeSearch" :loading="chargeSearching"
                         placeholder="收费项目名称 / 编码, 选中带出执行价(也可留空填纯文字任务)" @change="onPickCharge">
                <el-option v-for="c in chargeOptions" :key="c.id" :label="(c.itemName || '') + ' ' + (c.spec || '')" :value="c.id">
                  <div class="cp-opt">
                    <span class="nm">{{ c.itemName }}</span>
                    <span class="sub">{{ c.itemCode }}</span>
                    <span class="money">¥{{ money(c.execPrice != null ? c.execPrice : c.price) }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>

            <div class="cp-picked" v-if="isDrugTask && pickedDrug">
              <span>规格 <b>{{ pickedDrug.spec || '-' }}</b></span>
              <span>零售价 <b class="money">{{ money(pickedDrug.retailPrice) }}</b> 元/{{ pickedDrug.minUnit || '单位' }}</span>
              <span>剂型 {{ pickedDrug.dosformName || pickedDrug.dosform || '-' }}</span>
            </div>
            <div class="cp-picked" v-if="!isDrugTask && pickedCharge">
              <span>编码 <b>{{ pickedCharge.itemCode || '-' }}</b></span>
              <span>执行价 <b class="money">{{ money(pickedCharge.execPrice != null ? pickedCharge.execPrice : pickedCharge.price) }}</b> 元/{{ pickedCharge.unit || '次' }}</span>
            </div>

            <div class="cp-form-sec" v-if="isDrugTask">用药方案</div>
            <div class="cp-form-grid" v-if="isDrugTask">
              <el-form-item label="规格"><el-input v-model="taskForm.spec" placeholder="选中药品自动带出"></el-input></el-form-item>
              <el-form-item label="剂量">
                <el-input v-model="taskForm.dosage" style="width:110px" placeholder="如 0.5"></el-input>
                <el-input v-model="taskForm.dosageUnit" style="width:96px;margin-left:8px" placeholder="单位"></el-input>
              </el-form-item>
              <el-form-item label="用法">
                <el-select v-model="taskForm.usageCode" clearable filterable style="width:100%" placeholder="用法" @change="!contentTouched && autoCompose()">
                  <el-option v-for="u in usageOptions" :key="u.code" :label="u.name" :value="u.code"></el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="频次">
                <el-select v-model="taskForm.freqCode" clearable filterable style="width:100%" placeholder="频次" @change="!contentTouched && autoCompose()">
                  <el-option v-for="f in freqOptions" :key="f.code" :label="f.name" :value="f.code"></el-option>
                </el-select>
              </el-form-item>
            </div>

            <div class="cp-form-sec">医嘱内容与执行</div>
            <el-form-item label="医嘱内容">
              <el-input v-model="taskForm.orderContent" placeholder="自动拼接(药品: 名称+剂量+用法+频次)或手动输入" @input="onContentInput">
                <template #append><el-button @click="autoCompose">自动生成</el-button></template>
              </el-input>
            </el-form-item>
            <div class="cp-form-grid">
              <el-form-item label="数量"><el-input-number v-model="taskForm.quantity" :min="0.01" :step="1" :precision="2" controls-position="right" style="width:150px"></el-input-number></el-form-item>
              <el-form-item label="是否必做">
                <el-switch v-model="taskForm.isMandatory" :active-value="1" :inactive-value="0" active-text="必做" inactive-text="可选"></el-switch>
                <span class="cp-dim" style="margin-left:8px">必做任务自动转医嘱</span>
              </el-form-item>
            </div>
          </el-form>
          <template #footer>
            <el-button @click="taskDialog = false">取消</el-button>
            <el-button type="primary" :loading="taskSaving" @click="saveTask">保存</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.views.ClinicalPathwayManage = ClinicalPathwayManage;
})();
