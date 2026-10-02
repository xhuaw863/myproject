/* 住院登记结算: 入院登记(InpAdmission) + 在院患者管理(InpPatientList·工作台) + 床位管理(InpBedManage)
 *            + 预交金管理(InpDeposit) + 费用清单(InpChargeList) + 出院结算(InpSettle) + 住院日报(InpDailySummary)
 * 后端契约: /api/his/inp(admit/patients/visit/transfer/discharge-apply/cancel)
 *          + /api/his/inp/bed(ward/list、bed list、overview、启停翻转)
 *          + /api/his/inp/deposit(缴纳退还/流水/余额) + /api/his/inp/settle(charge/pre/settle/daily)
 * 字段口径: /patients 列表返回蛇形键(直接作表格 prop); 实体接口(visit/bed/ward/deposit/settle)返回驼峰键。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 住院模块私有样式一次性注入(无构建架构, 避免改动公共 css 与其他会话冲突) */
  (function ensureInpStyles() {
    if (document.getElementById('inp-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-style';
    st.textContent = [
      /* 预交金余额大字 */
      '.inp-balance-num { font-size:28px; font-weight:700; color:var(--yb-gold); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.inp-balance-label { color:var(--yb-ink-3); font-size:13px; }',
      '.inp-money { font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.inp-money-in { color:var(--yb-success); font-weight:600; }',
      '.inp-money-out { color:var(--yb-danger); font-weight:600; }',
      /* 统计卡片(日报/结算) */
      '.inp-stat-card { border:1px solid var(--yb-border); border-radius:var(--yb-r-md); background:var(--yb-surface); padding:14px 18px; height:100%; box-sizing:border-box; }',
      '.inp-stat-num { font-size:28px; font-weight:700; color:var(--yb-brand); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.inp-stat-num.gold { color:var(--yb-gold); }',
      '.inp-stat-label { color:var(--yb-ink-3); font-size:13px; margin-top:4px; }',
      /* 床位一览卡片 */
      '.inp-bed-card { border:2px solid var(--yb-border); border-radius:var(--yb-r-md); padding:8px 4px; text-align:center; cursor:pointer; background:var(--yb-surface); transition:box-shadow var(--yb-dur) var(--yb-ease); }',
      '.inp-bed-card:hover { box-shadow:var(--yb-sh-2); }',
      '.inp-bed-card .no { font-size:14px; font-weight:700; color:var(--yb-ink-1); }',
      '.inp-bed-card .sub { font-size:12px; margin-top:2px; color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.inp-bed-empty { border-color:var(--yb-fill-success); }',
      '.inp-bed-empty .sub { color:var(--yb-success); font-weight:600; }',
      '.inp-bed-occupied { border-color:var(--yb-fill-info); background:var(--yb-info-light); }',
      '.inp-bed-occupied .sub { color:var(--yb-link); font-weight:600; }',
      '.inp-bed-disabled { border-color:var(--yb-border-strong); background:var(--yb-surface-3); opacity:.78; }',
      '.inp-bed-disabled .sub { color:var(--yb-ink-4); }',
      /* 小节标题 / 只读信息块 */
      '.inp-section-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); }',
      '.inp-info { background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:10px 14px; font-size:13px; color:var(--yb-ink-2); line-height:2; }',
      '.inp-info b { color:var(--yb-ink-1); }',
      /* 汇总表合计行 */
      '.inp-sum-total td { font-weight:700 !important; background:var(--yb-gold-bg) !important; color:var(--yb-gold) !important; }',
      '.inp-card { border:1px solid var(--yb-border); background:var(--yb-surface); border-radius:var(--yb-r-md); padding:12px 14px; }',
      /* 结算单样式 */
      '.inp-slip { border:1px solid var(--yb-border-strong); border-radius:var(--yb-r-md); padding:18px 24px; background:var(--yb-surface); }',
      '.inp-slip-title { text-align:center; font-size:17px; font-weight:700; letter-spacing:4px; color:var(--yb-ink-1); margin-bottom:12px; }',
      /* 过敏史动态行 */
      '.inp-allergy-row { display:flex; gap:8px; align-items:center; margin-bottom:8px; }',
      /* 过敏/欠费警示小图标(表格内) */
      '.inp-allergy-icon { color:var(--yb-fill-danger); font-size:15px; vertical-align:-2px; margin-left:2px; cursor:help; }',
      '.inp-debt-icon { color:var(--yb-fill-warning); font-size:15px; vertical-align:-2px; margin-left:2px; cursor:help; }',
      /* 预警数字卡片(可点击展开) */
      '.inp-alert-card { border:1px solid var(--yb-danger-border); background:var(--yb-danger-bg); border-radius:var(--yb-r-md); padding:12px 16px; cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease); }',
      '.inp-alert-card:hover { box-shadow:var(--yb-sh-2); }',
      '.inp-alert-num { font-size:28px; font-weight:700; color:var(--yb-danger); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      /* 床位性别约束边框(1蓝男/2粉女, 无令牌的装饰粉用字面量) */
      '.inp-bed-male { border-color:var(--yb-fill-info) !important; }',
      '.inp-bed-female { border-color:#dd87ac !important; }',
      /* 过敏史醒目卡片(概览/列表通用) */
      '.inp-allergy-card { background:var(--yb-fill-danger); color:#fff; border-radius:var(--yb-r-md); padding:12px 16px; }',
      '.inp-allergy-card .t { font-weight:700; font-size:14px; margin-bottom:6px; }',
      '.inp-allergy-none { background:var(--yb-success-bg); color:var(--yb-success-strong); border:1px solid var(--yb-success-border); border-radius:var(--yb-r-md); padding:10px 16px; font-weight:600; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* Task#30 商业化升级样式(独立 id, 避免与既有 #inp-style 冲突) */
  (function ensureInpStyles30() {
    if (document.getElementById('inp-style-30')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-style-30';
    st.textContent = [
      /* 1. 顶部经营统计卡片栏(6卡) */
      '.inp-glance-row { margin-bottom:10px; }',
      '.inp-glance { position:relative; height:60px; box-sizing:border-box; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:6px; padding:7px 12px; cursor:pointer; overflow:hidden; box-shadow:var(--yb-sh-1); transition:box-shadow var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }',
      '.inp-glance::after { content:""; position:absolute; left:0; right:0; bottom:0; height:2px; background:var(--g); }',
      '.inp-glance:hover { box-shadow:var(--yb-sh-2); transform:translateY(-1px); }',
      '.inp-glance.is-on { box-shadow:var(--yb-sh-2), 0 0 0 2px var(--g); }',
      '.inp-glance .g-num { font-size:24px; line-height:26px; font-weight:700; font-variant-numeric:tabular-nums; color:var(--g); }',
      '.inp-glance .g-arrow { font-size:13px; margin-left:2px; }',
      '.inp-glance .g-label { font-size:12px; color:var(--yb-ink-3); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.inp-glance.g-total { --g:var(--yb-brand); }',
      '.inp-glance.g-admit { --g:var(--yb-success); }',
      '.inp-glance.g-discharge { --g:var(--yb-warning); }',
      '.inp-glance.g-critical { --g:var(--yb-danger); }',
      '.inp-glance.g-arrears { --g:var(--yb-danger); }',
      '.inp-glance.g-warn { --g:var(--yb-gold); }',
      /* 2. 床位卡片增强(120px): 占用/空床/停用/危重呼吸灯/拖拽态 */
      '.inp-bed2 { position:relative; height:120px; box-sizing:border-box; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); padding:7px 10px; cursor:pointer; overflow:hidden; display:flex; flex-direction:column; gap:3px; transition:box-shadow var(--yb-dur) var(--yb-ease), border-color var(--yb-dur) var(--yb-ease); }',
      '.inp-bed2:hover { box-shadow:var(--yb-sh-2); }',
      '.inp-bed2[draggable="true"] { cursor:grab; }',
      '.inp-bed2 .b2-head { display:flex; align-items:center; justify-content:space-between; gap:4px; }',
      '.inp-bed2 .b2-no { font-size:18px; font-weight:700; line-height:1.1; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; }',
      '.inp-bed2 .b2-name { font-size:14px; font-weight:600; color:var(--yb-ink-1); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.inp-bed2 .b2-meta { font-size:12px; font-weight:400; color:var(--yb-ink-3); }',
      '.inp-bed2 .b2-line { display:flex; align-items:center; gap:6px; font-size:12px; color:var(--yb-ink-3); white-space:nowrap; overflow:hidden; }',
      '.inp-bed2 .el-tag { height:18px; line-height:16px; padding:0 5px; }',
      '.inp-bed2 .b2-diag { font-size:12px; color:var(--yb-ink-3); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.inp-bed2 .b2-empty { font-size:13px; color:var(--yb-ink-4); display:flex; align-items:center; }',
      '.inp-bed2.is-free { background:var(--yb-surface-2); border-style:dashed; }',
      '.inp-bed2.is-free .b2-no { color:var(--yb-ink-3); }',
      '.inp-bed2.is-off { background:var(--yb-surface-3); opacity:.78; }',
      '.inp-bed2 .lv-dot { display:inline-block; width:12px; height:12px; border-radius:3px; flex:none; }',
      '.lv-1 { background:var(--yb-fill-danger); }',
      '.lv-2 { background:var(--yb-fill-warning); }',
      '.lv-3 { background:#cfa72c; }',
      '.lv-4 { background:var(--yb-fill-success); }',
      '.gender-m { color:var(--yb-fill-info); font-weight:700; }',
      '.gender-f { color:#d666a0; font-weight:700; }',
      /* 危重患者: 红色呼吸灯(无 --yb-danger-rgb 令牌, 用字面量 rgba(199,79,79,x)) */
      '@keyframes inp-breath { 0%,100% { box-shadow:0 0 0 0 rgba(199,79,79,.42); } 50% { box-shadow:0 0 8px 2px rgba(199,79,79,.22); } }',
      '.inp-bed2.is-critical { border-color:rgba(199,79,79,.55); animation:inp-breath 2.4s ease-in-out infinite; }',
      /* 拖拽换床: 源卡半透明, 空床落点蓝虚线高亮 */
      '.inp-bed2.is-dragging { opacity:.5; }',
      '.inp-bed2.is-drop { border:2px dashed var(--yb-brand) !important; background:var(--yb-brand-subtle); }',
      /* 按房间分组视图 */
      '.inp-room-card { margin-bottom:10px; border:1px solid var(--yb-border-light); }',
      '.inp-room-card .el-card__header { padding:9px 14px; border-bottom:1px solid var(--yb-divider); }',
      '.inp-room-card .el-card__body { padding:12px 14px; }',
      '.inp-room-beds { display:grid; grid-template-columns:repeat(auto-fill, minmax(152px, 1fr)); gap:10px; }',
      /* 3. 日报 sparkline + 环比 */
      '.inp-stat-flex { display:flex; align-items:center; justify-content:space-between; gap:8px; }',
      '.inp-spark { width:50px; height:20px; flex:none; }',
      '.inp-delta { margin-top:6px; font-size:12px; }',
      '.inp-delta.up { color:var(--yb-success); }',
      '.inp-delta.down { color:var(--yb-danger); }',
      '.inp-delta.flat { color:var(--yb-ink-4); }',
      /* 4. 表格操作列图标按钮 */
      '.inp-op { display:inline-flex; align-items:center; justify-content:center; width:26px; height:26px; margin-right:6px; border-radius:var(--yb-r-sm); color:var(--yb-icon); cursor:pointer; vertical-align:middle; transition:color var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease); }',
      '.inp-op:hover { color:var(--yb-brand); background:var(--yb-brand-subtle); }',
      '.inp-op.is-danger:hover { color:var(--yb-danger); background:var(--yb-danger-bg); }',
      '.inp-op.is-ok:hover { color:var(--yb-success); background:var(--yb-success-bg); }',
      '.inp-op.is-off { color:var(--yb-ink-disabled); cursor:not-allowed; }',
      /* 床位悬停气泡 */
      '.inp-bed-pop .el-descriptions__label { width:64px; }',
      '.inp-bed-pop { pointer-events:none; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 常量 ===== */
  var VISIT_STATUS = { 1: '待入院', 2: '在院', 3: '出院办理中', 4: '已出院', 5: '已取消' };
  var VISIT_STATUS_TYPE = { 1: 'warning', 2: 'success', 3: '', 4: 'info', 5: 'danger' };
  var STATUS_OPTIONS = [
    { v: 1, l: '待入院' }, { v: 2, l: '在院' }, { v: 3, l: '出院办理中' },
    { v: 4, l: '已出院' }, { v: 5, l: '已取消' }
  ];
  var FEE_TYPE_MAP = { 1: '西药费', 2: '中药费', 3: '检查费', 4: '检验费', 5: '治疗费', 6: '护理费', 7: '材料费', 8: '床位费', 9: '其他' };
  var FEE_TYPE_TAG = { 1: 'danger', 2: 'warning', 3: 'primary', 4: 'success', 5: 'info', 6: 'warning', 7: 'info', 8: 'primary', 9: 'info' };
  var FEE_TYPE_OPTIONS = [
    { v: 1, l: '西药费' }, { v: 2, l: '中药费' }, { v: 3, l: '检查费' }, { v: 4, l: '检验费' },
    { v: 5, l: '治疗费' }, { v: 6, l: '护理费' }, { v: 7, l: '材料费' }, { v: 8, l: '床位费' }, { v: 9, l: '其他' }
  ];
  var PAY_TYPES = [{ v: 1, l: '现金' }, { v: 2, l: '微信' }, { v: 3, l: '支付宝' }, { v: 4, l: '银行卡' }];
  var DIRECTIONS = [{ v: 1, l: '缴纳' }, { v: 2, l: '退还' }];
  var BED_TYPES = { 1: '普通', 2: '抢救', 3: '监护', 4: '隔离' };
  var BED_TYPE_OPTIONS = [{ v: 1, l: '普通' }, { v: 2, l: '抢救' }, { v: 3, l: '监护' }, { v: 4, l: '隔离' }];
  var BED_STATUS = { 0: '空床', 1: '占用', 2: '停用' };
  var BED_STATUS_TAG = { 0: 'success', 1: 'primary', 2: 'info' };
  var DIAG_TYPES = { 1: '入院诊断', 2: '补充诊断', 3: '术后诊断', 4: '出院诊断' };
  var SETTLE_TYPES = { 1: '出院结算', 2: '中途结算', 3: '退费' };
  /* ---------- 模型增强扩展: 等级/过敏/扩展登记 常量 ---------- */
  var CONTACT_RELATIONS = ['父母', '配偶', '子女', '兄弟姐妹', '其他'];
  var BLOOD_TYPE_OPTIONS = ['A', 'B', 'AB', 'O', '不详', '未查'];
  var ADMIT_SOURCES = { 1: '门诊', 2: '急诊', 3: '转诊', 4: '其他' };
  var ADMIT_SOURCE_OPTIONS = [{ v: 1, l: '门诊' }, { v: 2, l: '急诊' }, { v: 3, l: '转诊' }, { v: 4, l: '其他' }];
  var NURSING_LEVELS = { 1: '特级护理', 2: '一级护理', 3: '二级护理', 4: '三级护理' };
  var NURSING_LEVEL_TAG = { 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' };
  var NURSING_LEVEL_OPTIONS = [{ v: 1, l: '特级' }, { v: 2, l: '一级' }, { v: 3, l: '二级' }, { v: 4, l: '三级' }];
  var CONDITION_LEVELS = { 1: '危', 2: '重', 3: '一般' };
  var CONDITION_LEVEL_TAG = { 1: 'danger', 2: 'warning', 3: 'success' };
  var ALLERGY_TYPES = { 1: '药物', 2: '食物', 3: '环境', 4: '其他' };
  var ALLERGY_TYPE_OPTIONS = [{ v: 1, l: '药物' }, { v: 2, l: '食物' }, { v: 3, l: '环境' }, { v: 4, l: '其他' }];
  var ALLERGY_SEVERITY = { 1: '轻', 2: '中', 3: '重' };
  var ALLERGY_SEVERITY_OPTIONS = [{ v: 1, l: '轻' }, { v: 2, l: '中' }, { v: 3, l: '重' }];
  var BED_LEVELS = { 1: '普通', 2: '单间', 3: '监护', 4: '特需' };
  var BED_LEVEL_TAG = { 1: 'info', 2: 'primary', 3: 'warning', 4: '' };
  var BED_LEVEL_OPTIONS = [{ v: 1, l: '普通' }, { v: 2, l: '单间' }, { v: 3, l: '监护' }, { v: 4, l: '特需' }];
  var ALERT_TYPES = { 1: '日限额', 2: '总限额', 3: '预交金不足', 4: '大额费用' };
  var ALERT_TYPE_TAG = { 1: 'warning', 2: 'warning', 3: 'danger', 4: 'danger' };

  /* 医疗类别(住院)本地回退: 优先取医保字典, 失败时用此列表 */
  var MED_TYPE_FALLBACK = [
    { v: '21', l: '普通住院' }, { v: '22', l: '单病种' }, { v: '23', l: '日间手术' },
    { v: '24', l: '意外伤害' }, { v: '25', l: '工伤' }, { v: '26', l: '生育' }
  ];

  /* ===== 工具函数 ===== */
  function money(v) { return (v === null || v === undefined || v === '') ? '0.00' : Number(v).toFixed(2); }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function fmtTime(v) { return (v === null || v === undefined || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 19); }
  function fmtDate(v) { return (v === null || v === undefined || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 10); }
  function today() {
    var d = new Date();
    var m = d.getMonth() + 1, day = d.getDate();
    return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
  }
  function genderText(g) { return (g === '1' || g === 1) ? '男' : ((g === '2' || g === 2) ? '女' : orDash(g)); }
  function statusLabel(s) { return VISIT_STATUS[s] || (s == null ? '-' : s); }
  function statusTag(s) { var t = VISIT_STATUS_TYPE[s]; return t === undefined ? 'info' : t; }
  function feeTypeLabel(t) { return FEE_TYPE_MAP[t] || '其他'; }
  function feeTypeTag(t) { return FEE_TYPE_TAG[t] || 'info'; }
  function bedTypeLabel(t) { return BED_TYPES[t] || orDash(t); }
  function bedStatusLabel(s) { return BED_STATUS[s] || orDash(s); }
  function bedStatusTag(s) { return BED_STATUS_TAG[s] || 'info'; }
  function diagTypeLabel(t) { return DIAG_TYPES[t] || orDash(t); }
  function settleTypeLabel(v) { return SETTLE_TYPES[v] || orDash(v); }
  function findIn(list, prop, v) {
    for (var i = 0; i < list.length; i++) { if (list[i][prop] === v) { return list[i]; } }
    return null;
  }
  function payTypeLabel(v) { var o = findIn(PAY_TYPES, 'v', v); return o ? o.l : orDash(v); }
  function directionLabel(v) { var o = findIn(DIRECTIONS, 'v', v); return o ? o.l : orDash(v); }
  function nursingLevelLabel(v) { return NURSING_LEVELS[v] || orDash(v); }
  function nursingLevelTag(v) { var t = NURSING_LEVEL_TAG[v]; return t === undefined ? 'info' : t; }
  function conditionLevelLabel(v) { return CONDITION_LEVELS[v] || orDash(v); }
  function conditionLevelTag(v) { var t = CONDITION_LEVEL_TAG[v]; return t === undefined ? 'info' : t; }
  function allergyTypeLabel(v) { return ALLERGY_TYPES[v] || orDash(v); }
  function allergySeverityLabel(v) { return ALLERGY_SEVERITY[v] || orDash(v); }
  function allergySeverityTag(v) { return v === 3 ? 'danger' : (v === 2 ? 'warning' : 'info'); }
  function bedLevelLabel(v) { return BED_LEVELS[v] || orDash(v); }
  function bedLevelTag(v) { var t = BED_LEVEL_TAG[v]; return t === undefined ? 'info' : t; }
  /* 特需(4)无对应语义令牌, 用填充紫描边区分 */
  function bedLevelStyle(v) { return v === 4 ? 'color:var(--yb-fill-purple);border-color:var(--yb-fill-purple)' : ''; }
  function admitSourceLabel(v) { return ADMIT_SOURCES[v] || orDash(v); }
  function alertTypeLabel(v) { return ALERT_TYPES[v] || orDash(v); }
  function alertTypeTag(v) { var t = ALERT_TYPE_TAG[v]; return t === undefined ? 'info' : t; }
  /* 支付方式(医保2304 setlinfo扩展键, 值域不定原样展示; 缺失显示-) */
  function payMethodLabel(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    return String(v);
  }
  /* HTML 转义: 确认框消息拼接用户输入(姓名等)前必须转义, 防存储型 XSS */
  function escHtml(v) {
    return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function confirmBox(msg, title) {
    return ElementPlus.ElMessageBox.confirm(msg, title || '操作确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' });
  }
  function isCancel(e) { return e === 'cancel' || e === 'close'; }
  /* 打印: 打印API返回的HTML → 隐藏iframe → window.print()(调起系统打印预览) */
  function printHtml(html, title) {
    if (!html) { HIS.notifyError(new Error('打印内容为空')); return; }
    var old = document.getElementById('inp-print-frame');
    if (old && old.parentNode) { old.parentNode.removeChild(old); }
    var fr = document.createElement('iframe');
    fr.id = 'inp-print-frame';
    fr.setAttribute('title', title || '打印');
    fr.style.cssText = 'position:fixed;right:0;bottom:0;width:1px;height:1px;border:0;visibility:hidden';
    document.body.appendChild(fr);
    var doc = fr.contentWindow.document;
    doc.open(); doc.write(html); doc.close();
    setTimeout(function () {
      try { fr.contentWindow.focus(); fr.contentWindow.print(); } catch (e) { console.warn('打印失败', e); }
    }, 300);
  }
  /* 挂到 HIS 供医生站等模块复用(病历/结算单打印) */
  HIS.printHtmlFrame = printHtml;

  /* ===== 共享混入 ===== */
  /* 参照数据(科室/职工)加载: 组件经 mixins 引入, 获得 refDepts/refStaffs 与名称映射 */
  var refMixin = {
    data: function () { return { refDepts: [], refStaffs: [] }; },
    computed: {
      deptMap: function () {
        var m = {}; (this.refDepts || []).forEach(function (d) { m[HIS.idKey(d.id)] = d.deptName; }); return m;
      },
      staffMap: function () {
        var m = {}; (this.refStaffs || []).forEach(function (s) { m[HIS.idKey(s.id)] = s.staffName; }); return m;
      },
      /* 住院科室: 大类含"住院"的科室级节点; 无匹配时回退全部科室级(deptLevel=2) */
      inpDepts: function () {
        var all = this.refDepts || [];
        var hit = all.filter(function (d) { return (d.deptCategory || '').indexOf('住院') >= 0 && d.deptLevel !== 1; });
        if (hit.length) { return hit; }
        return all.filter(function (d) { return d.deptLevel === 2; });
      }
    },
    methods: {
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (list) { vm.refDepts = list || []; }).catch(function () { });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师')).then(function (list) {
          vm.refStaffs = list || [];
        }).catch(function () { });
      },
      deptName: function (id) { return this.deptMap[HIS.idKey(id)] || orDash(id); },
      staffName: function (id) { return this.staffMap[HIS.idKey(id)] || orDash(id); },
      /* 医生下拉显示名: 姓名(职称) */
      staffLabel: function (s) { return s.staffName + (s.titleName ? '（' + s.titleName + '）' : ''); },
      /* 病区下拉显示名: 名称(楼栋楼层) */
      wardLabel: function (w) { return w.wardName + (w.building ? '（' + w.building + (w.floor || '') + '）' : ''); },
      /* 床位下拉显示名: 床号·房间(类型) */
      bedLabel: function (b) {
        return '床' + b.bedNo + (b.roomNo ? ' · ' + b.roomNo + '房' : '') + '（' + bedTypeLabel(b.bedType) + '）';
      }
    }
  };
  /* 列表序号: seqNo 依赖分页(page/size); idx 为纯序号 */
  var listMixin = {
    methods: {
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      idx: function (i) { return i + 1; }
    }
  };
  /* 服务端分页事件: 依赖组件的 load() */
  var pageMixin = {
    methods: {
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); }
    }
  };

  /* ===== Task#30 基础设施: 内联 SVG 图标(项目无图标包, 不新增依赖) =====
   * 每个图标 = path/circle 子元素描述; 24x24 viewBox, 线性描边, 颜色随 currentColor。
   * 用法: 组件局部注册 components: INP_ICONS -> <component :is="ic.YbiView" :size="16"/>
   */
  var INP_ICON_DEFS = {
    view: [{ p: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z' }, { c: [12, 12, 3] }],
    switch: [{ p: 'M17 1l4 4-4 4' }, { p: 'M3 11V9a4 4 0 0 1 4-4h14' }, { p: 'M7 23l-4-4 4-4' }, { p: 'M21 13v2a4 4 0 0 1-4 4H3' }],
    upload: [{ p: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4' }, { p: 'M17 8l-5-5-5 5' }, { p: 'M12 3v12' }],
    close: [{ p: 'M18 6 6 18' }, { p: 'M6 6l12 12' }],
    edit: [{ p: 'M12 20h9' }, { p: 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z' }],
    power: [{ p: 'M18.36 6.64a9 9 0 1 1-12.73 0' }, { p: 'M12 2v10' }],
    check: [{ p: 'M20 6 9 17l-5-5' }],
    ban: [{ c: [12, 12, 10] }, { p: 'M4.93 4.93l14.14 14.14' }],
    lock: [{ p: 'M4 11h16v10H4z' }, { p: 'M8 11V7a4 4 0 0 1 8 0v4' }],
    more: [{ c: [12, 5, 1] }, { c: [12, 12, 1] }, { c: [12, 19, 1] }]
  };
  /* 图标组件工厂: ybiIcon('view') -> Vue 组件 */
  function ybiIcon(name) {
    return {
      name: 'Ybi' + name.charAt(0).toUpperCase() + name.slice(1),
      props: { size: { type: [Number, String], default: 14 } },
      render: function () {
        var h = window.Vue.h;
        var px = parseFloat(this.size) || 14;
        var kids = (INP_ICON_DEFS[name] || []).map(function (it) {
          if (it.c) { return h('circle', { cx: it.c[0], cy: it.c[1], r: it.c[2] }); }
          return h('path', { d: it.p });
        });
        return h('svg', {
          viewBox: '0 0 24 24', width: px, height: px, fill: 'none',
          stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
          'aria-hidden': 'true', style: 'display:inline-block;vertical-align:-2px'
        }, kids);
      }
    };
  }
  var INP_ICONS = {};
  Object.keys(INP_ICON_DEFS).forEach(function (k) {
    INP_ICONS['Ybi' + k.charAt(0).toUpperCase() + k.slice(1)] = ybiIcon(k);
  });

  /* 住院天数(入院当天记第1天; 无日期/-1) */
  function stayDays(admitDate) {
    if (!admitDate) { return null; }
    var t = new Date(String(admitDate).slice(0, 10).replace(/-/g, '/')).getTime();
    if (isNaN(t)) { return null; }
    var n = new Date();
    var m = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
    return Math.max(1, Math.round((m - t) / 86400000) + 1);
  }
  /* 床号分组(按房间视图): roomNo 优先 -> 'X号房'; 否则取床号数字前缀 -> 'X号房间' */
  function bedGroupOf(b) {
    if (b.roomNo) { return { key: 'r' + b.roomNo, label: b.roomNo + '号房', sort: Number(b.roomNo) || 0 }; }
    var m = /^\d+/.exec(String(b.bedNo || ''));
    if (!m) { return { key: 'zz', label: '其他床位', sort: 99999 }; }
    var d = m[0].replace(/^0+/, '') || m[0];
    var g = d.length >= 3 ? d.charAt(0) : d;
    return { key: 'g' + g, label: g + '号房间', sort: Number(g) || 0 };
  }
  /* 日报 sparkline 配置(画布字面量色, 与 HIS.theme 同值成对维护; area 为半透明填充) */
  var SPARK_DEFS = [
    { key: 'admit', color: '#3c862d', area: 'rgba(60,134,45,.14)' },
    { key: 'discharge', color: '#a26b1b', area: 'rgba(162,107,27,.14)' },
    { key: 'inHospital', color: '#1a5c9e', area: 'rgba(26,92,158,.14)' },
    { key: 'fee', color: '#9c6d25', area: 'rgba(156,109,37,.14)' }
  ];

  /* ========================================================================
   * 1. InpAdmission 入院登记
   * ====================================================================== */
  HIS.views.InpAdmission = {
    mixins: [refMixin],
    template: [
      '<div class="inp-admission">',
      '  <el-tabs v-model="tab">',
      '  <el-tab-pane label="入院登记" name="admit">',
      '  <div v-if="preHint" class="inp-info" style="margin-bottom:12px;border-left:3px solid var(--yb-gold);color:var(--yb-gold)">预入院模式: 无需选择病区/床位, 提交后在「预入院管理」页签确认入院时再分配床位</div>',
      '  <div class="inp-section-title">① 患者检索</div>',
      '  <div class="toolbar">',
      '    <el-input v-model="patientKw" placeholder="姓名/身份证/患者号/拼音简码" clearable style="width:300px" @keyup.enter="searchPatients"></el-input>',
      '    <el-button type="primary" :loading="searching" @click="searchPatients">检索</el-button>',
      '    <span style="color:var(--yb-ink-3);font-size:12px">回车检索; 从结果中选择患者后办理入院</span>',
      '  </div>',
      '  <el-table v-if="patientOptions.length" :data="patientOptions" border size="small" style="margin-bottom:14px">',
      '    <el-table-column label="序号" width="60" align="center"><template #default="s">{{ idx(s.$index) }}</template></el-table-column>',
      '    <el-table-column prop="patientNo" label="患者号" width="130"></el-table-column>',
      '    <el-table-column prop="name" label="姓名" width="100"></el-table-column>',
      '    <el-table-column label="性别" width="60"><template #default="s">{{ s.row.genderName || genderText(s.row.gender) }}</template></el-table-column>',
      '    <el-table-column prop="age" label="年龄" width="70"></el-table-column>',
      '    <el-table-column prop="idCard" label="身份证号" min-width="170"></el-table-column>',
      '    <el-table-column prop="phone" label="电话" width="130"></el-table-column>',
      '    <el-table-column label="医保" min-width="110"><template #default="s">{{ orDash(s.row.insutypeName || s.row.insutype) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="90" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="pickPatient(s.row)">选择</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <div v-if="patient" class="inp-info" style="margin-bottom:14px">',
      '    <b>{{ patient.name }}</b>　{{ patient.genderName || genderText(patient.gender) }}　{{ patient.age !== null && patient.age !== undefined ? patient.age + \'岁\' : \'\' }}　',
      '    身份证: {{ orDash(patient.idCard) }}　电话: {{ orDash(patient.phone) }}　',
      '    医保人员编号: {{ orDash(patient.psnNo) }}　险种: {{ orDash(patient.insutypeName || patient.insutype) }}　',
      '    <el-button link type="danger" @click="clearPatient">清除所选</el-button>',
      '  </div>',
      '  <div v-if="certList.length && !preHint" class="inp-cert-bar" style="border:1px solid var(--yb-border);border-radius:var(--yb-r-md);padding:10px 12px;margin-bottom:14px;background:var(--yb-surface-2)">',
      '    <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">',
      '      <b style="color:var(--yb-brand)">⛁ 待入院住院证</b>',
      '      <span style="font-size:12px;color:var(--yb-ink-3)">点选持证入院: 自动预填拟收科室/入院诊断, 登记后证自动核销</span>',
      '      <span style="flex:1"></span>',
      '      <el-button v-if="certId" link size="small" @click="clearCert">取消选证</el-button>',
      '    </div>',
      '    <el-table :data="certList" border size="small" max-height="180" :row-class-name="certRowClass">',
      '      <el-table-column label="序号" width="56" align="center"><template #default="s">{{ idx(s.$index) }}</template></el-table-column>',
      '      <el-table-column prop="admitDeptName" label="拟收科室" width="120"></el-table-column>',
      '      <el-table-column prop="admitDiagnosis" label="入院诊断" min-width="160" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="紧急" width="72" align="center"><template #default="s"><el-tag :type="urgencyTone(s.row.urgency)" size="small">{{ urgencyText(s.row.urgency) }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="applyDrName" label="开证医生" width="90"></el-table-column>',
      '      <el-table-column label="开证时间" width="150"><template #default="s">{{ fmtTime(s.row.applyTime) }}</template></el-table-column>',
      '      <el-table-column label="操作" width="86" fixed="right"><template #default="s">',
      '        <el-button v-if="s.row.id !== certId" link type="primary" @click="pickCert(s.row)">用此证</el-button>',
      '        <el-tag v-else type="success" size="small">已选</el-tag>',
      '      </template></el-table-column>',
      '    </el-table>',
      '  </div>',
      '  <div class="inp-section-title">② 入院登记信息</div>',
      '  <el-form :model="form" label-width="96px" style="max-width:880px">',
      '    <el-row :gutter="16">',
      '      <el-col :span="preHint ? 24 : 12"><el-form-item label="住院科室" required>',
      '        <el-select v-model="form.deptId" filterable placeholder="选择住院科室" style="width:100%" @change="onDeptChange">',
      '          <el-option v-for="d in inpDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12" v-if="!preHint"><el-form-item label="病区" required>',
      '        <el-select v-model="form.wardId" filterable placeholder="选择病区" style="width:100%" @change="onWardChange">',
      '          <el-option v-for="w in wardOptions" :key="w.id" :label="wardLabel(w)" :value="w.id"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12" v-if="!preHint"><el-form-item label="床位" required>',
      '        <el-select v-model="form.bedId" filterable placeholder="选择空床" style="width:100%">',
      '          <el-option v-for="b in bedOptions" :key="b.id" :label="bedLabel(b)" :value="b.id"></el-option>',
      '        </el-select>',
      '        <div v-if="form.wardId && !bedOptions.length" style="color:var(--yb-warning);font-size:12px;margin-top:4px">该病区当前无空床</div>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="主治医生" required>',
      '        <el-select v-model="form.doctorId" filterable placeholder="选择主治医生" style="width:100%">',
      '          <el-option v-for="s in refStaffs" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="医疗类别">',
      '        <el-select v-model="form.medType" style="width:100%">',
      '          <el-option v-for="m in medTypes" :key="m.v" :label="m.l" :value="m.v"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="医保人员编号">',
      '        <el-input v-model="form.psnNo" placeholder="选填; 选择患者后自动带入"></el-input>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="血型">',
      '        <el-select v-model="form.bloodType" clearable placeholder="选择血型" style="width:100%">',
      '          <el-option v-for="b in bloodTypes" :key="b" :label="b" :value="b"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="入院来源">',
      '        <el-select v-model="form.admitSource" clearable placeholder="选择入院来源" style="width:100%">',
      '          <el-option v-for="o in admitSources" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="护理等级">',
      '        <el-select v-model="form.nursingLevel" clearable placeholder="选择护理等级" style="width:100%">',
      '          <el-option v-for="o in nursingLevels" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="饮食类型">',
      '        <el-input v-model="form.dietType" maxlength="30" placeholder="如: 普食/流质/糖尿病饮食"></el-input>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="预交金预警线">',
      '        <el-input-number v-model="form.depositWarningAmount" :min="0" :precision="2" :step="100" style="width:100%" placeholder="余额低于该值触发预警"></el-input-number>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="预计出院日期">',
      '        <el-date-picker v-model="form.expectedDischargeDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="选择预计出院日期"></el-date-picker>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-form-item label="入院诊断" required>',
      '      <el-input v-model="form.admitDiag" type="textarea" :rows="3" placeholder="请输入入院诊断(记入住院病案首页)"></el-input>',
      '    </el-form-item>',
      '    <el-divider content-position="left">联系人信息</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="8"><el-form-item label="联系人姓名">',
      '        <el-input v-model="form.contactName" maxlength="30" placeholder="姓名"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="8"><el-form-item label="联系电话">',
      '        <el-input v-model="form.contactPhone" maxlength="20" placeholder="手机/座机"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="8"><el-form-item label="与患者关系">',
      '        <el-select v-model="form.contactRelation" clearable placeholder="选择关系" style="width:100%">',
      '          <el-option v-for="r in contactRelations" :key="r" :label="r" :value="r"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-divider content-position="left">担保人信息</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="8"><el-form-item label="担保人姓名">',
      '        <el-input v-model="form.guarantorName" maxlength="30" placeholder="姓名"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="8"><el-form-item label="担保人电话">',
      '        <el-input v-model="form.guarantorPhone" maxlength="20" placeholder="手机/座机"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="8"><el-form-item label="担保人证件号">',
      '        <el-input v-model="form.guarantorIdNo" maxlength="18" placeholder="身份证号"></el-input>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-divider content-position="left">过敏史</el-divider>',
      '    <div v-for="(a, ai) in allergRows" :key="ai" class="inp-allergy-row">',
      '      <el-select v-model="a.allergyType" style="width:110px">',
      '        <el-option v-for="o in allergyTypes" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '      </el-select>',
      '      <el-input v-model="a.allergenName" placeholder="过敏原名称(如: 青霉素/花生)" style="flex:1"></el-input>',
      '      <el-select v-model="a.severity" style="width:100px">',
      '        <el-option v-for="o in allergySeverities" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '      </el-select>',
      '      <el-input v-model="a.reactionDesc" placeholder="反应描述(如: 皮疹/呼吸困难)" style="flex:1.4"></el-input>',
      '      <el-button link type="danger" @click="removeAllergyRow(ai)">删除</el-button>',
      '    </div>',
      '    <div style="display:flex;align-items:center;gap:10px">',
      '      <el-button size="small" @click="addAllergyRow">+ 添加过敏记录</el-button>',
      '      <span v-if="!allergRows.length" style="color:var(--yb-ink-4);font-size:12px">无过敏史可留空不填; 已填写的过敏记录将随入院登记一并保存</span>',
      '    </div>',
      '    <el-form-item style="margin-top:16px">',
      '      <el-button type="primary" :loading="submitting" @click="submit">{{ preHint ? "提交预入院登记" : "办理入院登记" }}</el-button>',
      '      <el-button v-if="!preHint" plain @click="goPreMode">登记为预入院</el-button>',
      '      <el-button v-else @click="exitPreMode">退出预入院模式</el-button>',
      '      <el-button @click="resetAll">重置</el-button>',
      '    </el-form-item>',
      '  </el-form>',
      '  </el-tab-pane>',
      '  <el-tab-pane label="预入院管理" name="pre">',
      '    <div class="toolbar">',
      '      <el-select v-model="preDeptId" placeholder="全部科室" clearable filterable style="width:200px" @change="loadPreAdmissions">',
      '        <el-option v-for="d in inpDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '      </el-select>',
      '      <el-button :loading="preListLoading" @click="loadPreAdmissions">刷新</el-button>',
      '      <el-button type="primary" plain @click="newPreMode">新建预入院</el-button>',
      '      <span style="color:var(--yb-ink-3);font-size:12px">预入院登记不占用床位, 确认入院时再分配床位并补落入院诊断</span>',
      '    </div>',
      '    <el-table :data="preList" border size="small" v-loading="preListLoading">',
      '      <el-table-column label="序号" width="60" align="center"><template #default="s">{{ idx(s.$index) }}</template></el-table-column>',
      '      <el-table-column prop="inp_no" label="住院号" width="140"></el-table-column>',
      '      <el-table-column prop="patient_name" label="患者姓名" width="100"></el-table-column>',
      '      <el-table-column label="性别" width="60"><template #default="s">{{ s.row.gender_name || genderText(s.row.gender) }}</template></el-table-column>',
      '      <el-table-column prop="age" label="年龄" width="70"></el-table-column>',
      '      <el-table-column label="身份证号" min-width="170"><template #default="s">{{ orDash(s.row.id_card) }}</template></el-table-column>',
      '      <el-table-column label="拟入科室" width="140"><template #default="s">{{ deptName(s.row.dept_id) }}</template></el-table-column>',
      '      <el-table-column label="主治医生" width="110"><template #default="s">{{ staffName(s.row.doctor_id) }}</template></el-table-column>',
      '      <el-table-column label="预入院时间" width="160"><template #default="s">{{ fmtTime(s.row.pre_admit_time) }}</template></el-table-column>',
      '      <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag type="warning" size="small">待入院</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="160" fixed="right"><template #default="s">',
      '        <el-button link type="primary" @click="openConfirm(s.row)">确认入院</el-button>',
      '        <el-button link type="danger" @click="cancelPre(s.row)">取消</el-button>',
      '      </template></el-table-column>',
      '    </el-table>',
      '    <div v-if="!preList.length && !preListLoading" style="color:var(--yb-ink-3);font-size:13px;margin-top:10px">暂无预入院患者; 可在「入院登记」页签点击「登记为预入院」创建</div>',
      '  </el-tab-pane>',
      '  </el-tabs>',
      '  <el-dialog v-model="confirmDlg.show" title="确认入院 · 分配床位" width="520px" append-to-body>',
      '    <div v-if="confirmDlg.row" class="inp-info" style="margin-bottom:12px">',
      '      <b>{{ confirmDlg.row.patient_name }}</b>　住院号: {{ confirmDlg.row.inp_no }}　拟入科室: {{ deptName(confirmDlg.row.dept_id) }}',
      '    </div>',
      '    <el-form label-width="76px">',
      '      <el-form-item label="病区" required>',
      '        <el-select v-model="confirmDlg.wardId" filterable placeholder="选择病区" style="width:100%" @change="loadConfirmBeds">',
      '          <el-option v-for="w in confirmWardOptions" :key="w.id" :label="wardLabel(w)" :value="w.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="床位" required>',
      '        <el-select v-model="confirmDlg.bedId" filterable placeholder="选择空床" style="width:100%">',
      '          <el-option v-for="b in confirmBeds" :key="b.id" :label="bedLabel(b)" :value="b.id"></el-option>',
      '        </el-select>',
      '        <div v-if="confirmDlg.wardId && !confirmBeds.length" style="color:var(--yb-warning);font-size:12px;margin-top:4px">该病区当前无空床</div>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="confirmDlg.show=false">取消</el-button>',
      '      <el-button type="primary" :loading="confirmDlg.loading" :disabled="!confirmDlg.bedId" @click="doConfirmAdmit">确认入院</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        patientKw: '', patientOptions: [], searching: false, patient: null,
        form: {
          deptId: null, wardId: null, bedId: null, doctorId: null, admitDiag: '', medType: '21', psnNo: '',
          contactName: '', contactPhone: '', contactRelation: '',
          guarantorName: '', guarantorPhone: '', guarantorIdNo: '',
          bloodType: '', admitSource: null, nursingLevel: null, dietType: '',
          depositWarningAmount: null, expectedDischargeDate: ''
        },
        allergRows: [],
        /* 持证入院: 选中患者的待入院有效住院证(status=1) */
        certList: [], certId: null, certLoading: false,
        contactRelations: CONTACT_RELATIONS, bloodTypes: BLOOD_TYPE_OPTIONS, admitSources: ADMIT_SOURCE_OPTIONS,
        nursingLevels: NURSING_LEVEL_OPTIONS, allergyTypes: ALLERGY_TYPE_OPTIONS, allergySeverities: ALLERGY_SEVERITY_OPTIONS,
        wards: [], beds: [], medTypes: MED_TYPE_FALLBACK.slice(),
        submitting: false,
        tab: 'admit', preHint: false,
        preList: [], preListLoading: false, preDeptId: null,
        confirmBeds: [],
        confirmDlg: { show: false, row: null, wardId: null, bedId: null, loading: false }
      };
    },
    computed: {
      /* 选中科室下的病区(病区未绑定科室时恒显示) */
      wardOptions: function () {
        var deptId = this.form.deptId;
        return (this.wards || []).filter(function (w) {
          return !deptId || HIS.sameId(w.deptId, deptId) || w.deptId === null || w.deptId === undefined;
        });
      },
      /* 仅空床可选 */
      bedOptions: function () {
        return (this.beds || []).filter(function (b) { return b.status === 0; });
      },
      /* 确认入院对话框: 病区选项(按拟入科室匹配; 未绑定科室的病区恒显示) */
      confirmWardOptions: function () {
        var deptId = this.confirmDlg.row ? this.confirmDlg.row.dept_id : null;
        return (this.wards || []).filter(function (w) {
          return !deptId || HIS.sameId(w.deptId, deptId) || w.deptId === null || w.deptId === undefined;
        });
      }
    },
    methods: {
      genderText: genderText, orDash: orDash, fmtTime: fmtTime,
      idx: function (i) { return i + 1; },
      /* 患者检索: /api/his/patient/page */
      searchPatients: function () {
        var vm = this;
        var kw = (vm.patientKw || '').trim();
        if (!kw) { HIS.notifyError(new Error('请输入检索关键字')); return; }
        vm.searching = true;
        HIS.get('/api/his/patient/page?page=1&size=15&keyword=' + encodeURIComponent(kw)).then(function (d) {
          vm.patientOptions = (d && d.records) || [];
          if (!vm.patientOptions.length) { ElementPlus.ElMessage.warning('未找到匹配的患者档案'); }
        }).catch(HIS.notifyError).finally(function () { vm.searching = false; });
      },
      pickPatient: function (p) {
        this.patient = p;
        this.form.psnNo = p.psnNo || '';
        this.patientOptions = [];
        HIS.notifySuccess('已选择患者: ' + p.name);
        this.loadPendingCerts(p);
      },
      /* 待入院住院证查询: GET /api/his/admission-cert/pending (持证入院选证预填) */
      loadPendingCerts: function (p) {
        var vm = this;
        vm.certList = []; vm.certId = null;
        if (!p || !p.id) { return; }
        vm.certLoading = true;
        HIS.get('/api/his/admission-cert/pending?patientId=' + HIS.idParam(p.id)).then(function (list) {
          vm.certList = list || [];
          if (vm.certList.length) { ElementPlus.ElMessage.info('该患者有 ' + vm.certList.length + ' 张待入院住院证, 可选择持证入院自动预填'); }
        }).catch(function () { vm.certList = []; }).finally(function () { vm.certLoading = false; });
      },
      /* 选用住院证: 回填拟收科室(需为可选住院科室)与入院诊断(空才填, 不覆盖手工录入) */
      pickCert: function (c) {
        var vm = this;
        vm.certId = c.id;
        var inDepts = (vm.inpDepts || []).some(function (d) { return HIS.sameId(d.id, c.admitDeptId); });
        if (c.admitDeptId && inDepts && !vm.form.deptId) {
          vm.form.deptId = c.admitDeptId;
        } else if (c.admitDeptId && !inDepts) {
          ElementPlus.ElMessage.warning('证上拟收科室「' + (c.admitDeptName || '') + '」不是住院科室, 请手动选择');
        }
        if (c.admitDiagnosis && !vm.form.admitDiag) {
          vm.form.admitDiag = c.admitDiagnosis;
        }
      },
      clearCert: function () { this.certId = null; },
      certRowClass: function (a) { return HIS.sameId(a.row.id, this.certId) ? 'current-row' : ''; },
      urgencyText: function (u) { return Number(u) === 3 ? '危急' : (Number(u) === 2 ? '急' : '普通'); },
      urgencyTone: function (u) { return Number(u) === 3 ? 'danger' : (Number(u) === 2 ? 'warning' : 'info'); },
      clearPatient: function () {
        this.patient = null;
        this.form.psnNo = '';
        this.certList = []; this.certId = null;
      },
      onDeptChange: function () {
        this.form.wardId = null; this.form.bedId = null; this.beds = [];
      },
      onWardChange: function () {
        this.form.bedId = null;
        this.loadBeds();
      },
      loadWards: function () {
        var vm = this;
        HIS.get('/api/his/inp/bed/ward/list').then(function (list) { vm.wards = list || []; }).catch(function () { });
      },
      loadBeds: function () {
        var vm = this;
        if (!vm.form.wardId) { vm.beds = []; return; }
        HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(vm.form.wardId)).then(function (list) {
          vm.beds = list || [];
        }).catch(HIS.notifyError);
      },
      loadMedTypes: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'med_type').then(function (list) {
          if (list && list.length) {
            vm.medTypes = list.map(function (o) { return { v: o.code, l: o.name }; });
          }
        }).catch(function () { /* 无字典时保留本地回退 */ });
      },
      addAllergyRow: function () {
        this.allergRows.push({ allergyType: 1, allergenName: '', severity: 1, reactionDesc: '' });
      },
      removeAllergyRow: function (i) { this.allergRows.splice(i, 1); },
      submit: function () {
        var vm = this;
        if (!vm.patient) { HIS.notifyError(new Error('请先检索并选择患者')); return; }
        if (!vm.form.deptId) { HIS.notifyError(new Error('请选择' + (vm.preHint ? '拟入科室' : '住院科室'))); return; }
        if (!vm.preHint && !vm.form.wardId) { HIS.notifyError(new Error('请选择病区')); return; }
        if (!vm.preHint && !vm.form.bedId) { HIS.notifyError(new Error('请选择床位')); return; }
        if (!vm.form.doctorId) { HIS.notifyError(new Error('请选择主治医生')); return; }
        if (!vm.form.admitDiag || !vm.form.admitDiag.trim()) { HIS.notifyError(new Error('请填写入院诊断')); return; }
        /* 过敏史: 随入院/预入院登记一并提交(后端 InpAdmitDTO.allergies 逐条落库 his_inp_allergy) */
        var allergies = (vm.allergRows || []).filter(function (a) { return a.allergenName && a.allergenName.trim(); })
          .map(function (a) {
            return {
              allergyType: a.allergyType, allergenName: a.allergenName.trim(),
              severity: a.severity, reactionDesc: (a.reactionDesc || '').trim() || null
            };
          });
        var body = {
          patientId: HIS.id(vm.patient.id), deptId: HIS.id(vm.form.deptId),
          wardId: HIS.id(vm.preHint ? (vm.form.wardId || null) : vm.form.wardId),
          bedId: HIS.id(vm.preHint ? null : vm.form.bedId),
          doctorId: HIS.id(vm.form.doctorId), admitDiag: vm.form.admitDiag.trim(),
          medType: vm.form.medType || null, psnNo: vm.form.psnNo || null,
          insutype: vm.patient.insutype || null,
          contactName: vm.form.contactName || null, contactPhone: vm.form.contactPhone || null,
          contactRelation: vm.form.contactRelation || null,
          guarantorName: vm.form.guarantorName || null, guarantorPhone: vm.form.guarantorPhone || null,
          guarantorIdNo: vm.form.guarantorIdNo || null,
          allergies: allergies,
          /* 持证入院: 仅正式登记消费证; 预入院不传(确认入院时再持证) */
          certId: vm.preHint ? null : (vm.certId ? HIS.id(vm.certId) : null)
        };
        vm.submitting = true;
        if (vm.preHint) {
          /* 预入院登记: POST /pre-admit (status=1待入院, 不分配床位) */
          HIS.post('/api/his/inp/pre-admit', body).then(function (visit) {
            HIS.notifySuccess('预入院登记成功' + (visit && visit.inpNo ? '，住院号: ' + visit.inpNo : '') + '；请在「预入院管理」页签确认入院并分配床位');
            vm.resetAll();
            vm.tab = 'pre';
          }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
          return;
        }
        HIS.post('/api/his/inp/admit', body).then(function (visit) {
          HIS.notifySuccess('入院登记成功' + (visit && visit.inpNo ? '，住院号: ' + visit.inpNo : ''));
          if (visit && visit.id) { vm.saveExtInfo(visit.id); }
          vm.resetAll();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      /* ===== 预入院 ===== */
      goPreMode: function () { this.preHint = true; },
      exitPreMode: function () { this.preHint = false; },
      /* 预入院管理页签「新建预入院」: 切回入院登记页签并进入预入院模式 */
      newPreMode: function () { this.preHint = true; this.tab = 'admit'; },
      /* 预入院列表: GET /pre-admissions (+deptId 可选过滤拟入科室) */
      loadPreAdmissions: function () {
        var vm = this;
        vm.preListLoading = true;
        var url = '/api/his/inp/pre-admissions' + (vm.preDeptId ? '?deptId=' + HIS.idParam(vm.preDeptId) : '');
        HIS.get(url).then(function (list) { vm.preList = list || []; })
          .catch(HIS.notifyError).finally(function () { vm.preListLoading = false; });
      },
      /* 确认入院: 打开分配床位对话框 */
      openConfirm: function (row) {
        this.confirmBeds = [];
        this.confirmDlg = { show: true, row: row, wardId: null, bedId: null, loading: false };
      },
      loadConfirmBeds: function () {
        var vm = this;
        vm.confirmDlg.bedId = null;
        if (!vm.confirmDlg.wardId) { vm.confirmBeds = []; return; }
        HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(vm.confirmDlg.wardId)).then(function (list) {
          vm.confirmBeds = (list || []).filter(function (b) { return b.status === 0; });
        }).catch(HIS.notifyError);
      },
      /* 确认入院: POST /pre-admit/{id}/confirm?bedId= (1待入院 -> 2在院+占床) */
      doConfirmAdmit: function () {
        var vm = this;
        var row = vm.confirmDlg.row;
        if (!row || !vm.confirmDlg.bedId) { HIS.notifyError(new Error('请选择床位')); return; }
        vm.confirmDlg.loading = true;
        HIS.post('/api/his/inp/pre-admit/' + HIS.idParam(row.id) + '/confirm?bedId=' + HIS.idParam(vm.confirmDlg.bedId)).then(function (visit) {
          HIS.notifySuccess('确认入院成功' + (visit && visit.inpNo ? '，住院号: ' + visit.inpNo : ''));
          vm.confirmDlg.show = false;
          vm.loadPreAdmissions();
        }).catch(HIS.notifyError).finally(function () { vm.confirmDlg.loading = false; });
      },
      /* 取消预入院: POST /pre-admit/{id}/cancel (1待入院 -> 5已取消) */
      cancelPre: function (row) {
        var vm = this;
        confirmBox('确认取消患者「' + escHtml(row.patient_name || '') + '」的预入院登记?取消后可在入院登记页重新办理。', '取消预入院').then(function () {
          return HIS.post('/api/his/inp/pre-admit/' + HIS.idParam(row.id) + '/cancel');
        }).then(function () {
          HIS.notifySuccess('预入院已取消');
          vm.loadPreAdmissions();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* 扩展在院信息(血型/入院来源/护理等级/饮食/预警线/预计出院日期): 入院登记成功后保存(白名单字段) */
      saveExtInfo: function (visitId) {
        var vm = this;
        var src = {
          bloodType: vm.form.bloodType, admitSource: vm.form.admitSource,
          nursingLevel: vm.form.nursingLevel, dietType: vm.form.dietType,
          depositWarningAmount: vm.form.depositWarningAmount, expectedDischargeDate: vm.form.expectedDischargeDate
        };
        var ext = {};
        Object.keys(src).forEach(function (k) {
          var v = src[k];
          if (v !== null && v !== undefined && String(v).trim() !== '') { ext[k] = v; }
        });
        if (!Object.keys(ext).length) { return; }
        HIS.put('/api/his/inp/visit/' + HIS.idParam(visitId) + '/info', ext).catch(function (e) {
          console.warn('在院扩展信息保存失败(不影响入院主流程): ', e && e.message);
        });
      },
      resetAll: function () {
        this.patientKw = ''; this.patientOptions = []; this.patient = null; this.beds = []; this.allergRows = [];
        this.certList = []; this.certId = null;
        this.form = {
          deptId: null, wardId: null, bedId: null, doctorId: null, admitDiag: '', medType: this.form.medType, psnNo: '',
          contactName: '', contactPhone: '', contactRelation: '',
          guarantorName: '', guarantorPhone: '', guarantorIdNo: '',
          bloodType: '', admitSource: null, nursingLevel: null, dietType: '',
          depositWarningAmount: null, expectedDischargeDate: ''
        };
      }
    },
    watch: {
      /* 切到预入院页签时加载待入院列表 */
      tab: function (v) { if (v === 'pre') { this.loadPreAdmissions(); } }
    },
    mounted: function () {
      this.loadRefs();
      this.loadWards();
      this.loadMedTypes();
    }
  };

  /* ========================================================================
   * 2. InpPatientList 在院患者管理(主工作台: 筛选/分页/详情抽屉/转科转床/出院申请/取消入院)
   * ====================================================================== */
  HIS.views.InpPatientList = {
    mixins: [refMixin, listMixin, pageMixin],
    components: Object.assign({}, INP_ICONS),
    template: [
      '<div class="inp-patient-list cd-fill is-cascade">',
      '  <div class="toolbar">',
      '    <el-select v-model="q.wardId" placeholder="全部病区" clearable filterable style="width:170px" @change="onQuery">',
      '      <el-option v-for="w in wards" :key="w.id" :label="wardLabel(w)" :value="w.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="q.deptId" placeholder="全部科室" clearable filterable style="width:170px" @change="onQuery">',
      '      <el-option v-for="d in inpDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="q.visitStatus" placeholder="全部状态" clearable style="width:140px" @change="onQuery">',
      '      <el-option v-for="s in statusOptions" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="q.keyword" placeholder="住院号/姓名/身份证/拼音简码" clearable style="width:230px" @keyup.enter="onQuery" @clear="onQuery"></el-input>',
      '    <el-button type="primary" @click="onQuery">查询</el-button>',
      '    <el-button @click="resetQuery">重置</el-button>',
      '    <div style="flex:1"></div>',
      '    <span style="color:var(--yb-ink-3);font-size:12px">共 {{ total }} 条</span>',
      '  </div>',
      /* ---- Task#30 经营统计卡片栏(6卡): 欠费/危重可点击当前页快捷筛选 ---- */
      '  <el-row :gutter="12" class="inp-glance-row">',
      '    <el-col :span="4"><div class="inp-glance g-total"><div class="g-num">{{ glance.total }}</div><div class="g-label">在院总数</div></div></el-col>',
      '    <el-col :span="4"><div class="inp-glance g-admit"><div class="g-num">{{ glance.todayAdmit }}<span class="g-arrow">↑</span></div><div class="g-label">今日入院</div></div></el-col>',
      '    <el-col :span="4"><div class="inp-glance g-discharge"><div class="g-num">{{ glance.todayDischarge }}<span class="g-arrow">↓</span></div><div class="g-label">今日出院</div></div></el-col>',
      '    <el-col :span="4"><div class="inp-glance g-critical" :class="{ \'is-on\': quickFilter===\'critical\' }" @click="toggleQuick(\'critical\')"><div class="g-num">{{ glance.critical }}</div><div class="g-label">危重患者</div></div></el-col>',
      '    <el-col :span="4"><div class="inp-glance g-arrears" :class="{ \'is-on\': quickFilter===\'debt\' }" @click="toggleQuick(\'debt\')"><div class="g-num">{{ glance.arrears }}</div><div class="g-label">欠费患者</div></div></el-col>',
      '    <el-col :span="4"><div class="inp-glance g-warn"><div class="g-num">{{ glance.depositWarning }}</div><div class="g-label">预交金预警</div></div></el-col>',
      '  </el-row>',
      '  <div v-if="quickFilter" style="margin:-2px 0 8px">',
      '    <el-tag :type="quickFilter===\'debt\' ? \'danger\' : \'warning\'" size="small" closable @close="quickFilter=\'\'">{{ quickFilter===\'debt\' ? "快捷筛选: 欠费患者(预交金余额<0)" : "快捷筛选: 危重患者(病情危/重)" }} · 按当前页本地筛选</el-tag>',
      '  </div>',
      '  <el-table :data="displayRows" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column label="序号" width="60" align="center"><template #default="s">{{ seqNo(s.$index) }}</template></el-table-column>',
      '    <el-table-column prop="inp_no" label="住院号" width="150"></el-table-column>',
      '    <el-table-column label="姓名" width="108"><template #default="s">',
      '      <span>{{ s.row.patient_name }}</span>',
      '      <el-tooltip v-if="allergyMap[s.row.id] && allergyMap[s.row.id].length" :content="allergySummary(s.row.id)" placement="top">',
      '        <span class="inp-allergy-icon" role="img" aria-label="过敏史">⚠</span>',
      '      </el-tooltip>',
      '    </template></el-table-column>',
      '    <el-table-column label="性别" width="55" align="center"><template #default="s">{{ s.row.gender_name || genderText(s.row.gender) }}</template></el-table-column>',
      '    <el-table-column prop="age" label="年龄" width="60" align="center"></el-table-column>',
      '    <el-table-column label="科室" min-width="120"><template #default="s">{{ deptName(s.row.dept_id) }}</template></el-table-column>',
      '    <el-table-column prop="ward_name" label="病区" min-width="110"></el-table-column>',
      '    <el-table-column prop="bed_no" label="床位号" width="80" align="center"></el-table-column>',
      '    <el-table-column label="入院日期" width="105"><template #default="s">{{ fmtDate(s.row.admit_date) }}</template></el-table-column>',
      '    <el-table-column label="主治医生" width="95"><template #default="s">{{ staffName(s.row.doctor_id) }}</template></el-table-column>',
      '    <el-table-column label="护理等级" width="92" align="center"><template #default="s">',
      '      <el-tag v-if="rowNursing(s.row) !== null && rowNursing(s.row) !== undefined" :type="nursingLevelTag(rowNursing(s.row))" size="small">{{ nursingLevelLabel(rowNursing(s.row)) }}</el-tag>',
      '      <span v-else>-</span>',
      '    </template></el-table-column>',
      '    <el-table-column label="病情" width="70" align="center"><template #default="s">',
      '      <el-tag v-if="rowCondition(s.row) !== null && rowCondition(s.row) !== undefined" :type="conditionLevelTag(rowCondition(s.row))" size="small">{{ conditionLevelLabel(rowCondition(s.row)) }}</el-tag>',
      '      <span v-else>-</span>',
      '    </template></el-table-column>',
      '    <el-table-column label="状态" width="100" align="center"><template #default="s">',
      '      <el-tag :type="statusTag(s.row.visit_status)" size="small">{{ statusLabel(s.row.visit_status) }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="预交金" width="122" align="right"><template #default="s">',
      '      <span class="inp-money">¥{{ money(s.row.deposit_balance) }}</span>',
      '      <el-tooltip v-if="isDebt(s.row)" :content="debtTip(s.row)" placement="top">',
      '        <span class="inp-debt-icon" role="img" aria-label="欠费预警">⚠</span>',
      '      </el-tooltip>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" width="128" fixed="right"><template #default="s">',
      '      <el-tooltip v-for="op in opSet(s.row).main" :key="op.key" :content="op.label" placement="top" :show-after="300">',
      '        <span class="inp-op" :class="{ \'is-danger\': op.danger }" @click="onOp(s.row, op.key)"><component :is="op.icon" :size="16"></component></span>',
      '      </el-tooltip>',
      '      <el-dropdown v-if="opSet(s.row).more.length" trigger="click" @command="function(k){ onOp(s.row, k) }">',
      '        <span class="inp-op"><component :is="ic.YbiMore" :size="16"></component></span>',
      '        <template #dropdown>',
      '          <el-dropdown-menu>',
      '            <el-dropdown-item v-for="op in opSet(s.row).more" :key="op.key" :command="op.key">{{ op.label }}</el-dropdown-item>',
      '          </el-dropdown-menu>',
      '        </template>',
      '      </el-dropdown>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ---- 详情抽屉 ---- */
      '  <el-drawer v-model="detailVisible" title="住院就诊详情" size="760px">',
      '    <div v-loading="detailLoading" element-loading-text="加载中..." style="min-height:200px">',
      '      <template v-if="detail">',
      '        <div class="inp-section-title">就诊信息</div>',
      '        <el-descriptions :column="3" border size="small" style="margin-bottom:16px">',
      '          <el-descriptions-item label="住院号">{{ orDash(detail.visit.inpNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="状态"><el-tag :type="statusTag(detail.visit.visitStatus)" size="small">{{ statusLabel(detail.visit.visitStatus) }}</el-tag></el-descriptions-item>',
      '          <el-descriptions-item label="医疗类别">{{ orDash(medTypeLabel(detail.visit.medType)) }}</el-descriptions-item>',
      '          <el-descriptions-item label="住院科室">{{ detailDeptName() }}</el-descriptions-item>',
      '          <el-descriptions-item label="病区">{{ detail.ward ? detail.ward.wardName : orDash(detail.visit.wardId) }}</el-descriptions-item>',
      '          <el-descriptions-item label="床位">{{ detailBedText() }}</el-descriptions-item>',
      '          <el-descriptions-item label="主治医生">{{ staffName(detail.visit.doctorId) }}</el-descriptions-item>',
      '          <el-descriptions-item label="入院日期">{{ fmtTime(detail.visit.admitDate) }}</el-descriptions-item>',
      '          <el-descriptions-item label="出院日期">{{ detail.visit.dischargeDate ? fmtTime(detail.visit.dischargeDate) : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="累计费用"><span class="inp-money">¥{{ money(detail.visit.totalCost) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="预交金余额"><span class="inp-money">¥{{ money(detail.visit.depositBalance) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="医保就诊ID">{{ orDash(detail.visit.mdtrtId) }}</el-descriptions-item>',
      '          <el-descriptions-item label="护理等级"><el-tag v-if="detail.visit.nursingLevel" :type="nursingLevelTag(detail.visit.nursingLevel)" size="small">{{ nursingLevelLabel(detail.visit.nursingLevel) }}</el-tag><span v-else>-</span></el-descriptions-item>',
      '          <el-descriptions-item label="病情等级"><el-tag v-if="detail.visit.conditionLevel" :type="conditionLevelTag(detail.visit.conditionLevel)" size="small">{{ conditionLevelLabel(detail.visit.conditionLevel) }}</el-tag><span v-else>-</span></el-descriptions-item>',
      '          <el-descriptions-item label="血型">{{ orDash(detail.visit.bloodType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="入院来源">{{ admitSourceLabel(detail.visit.admitSource) }}</el-descriptions-item>',
      '          <el-descriptions-item label="饮食类型">{{ orDash(detail.visit.dietType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="预交金预警线"><span class="inp-money">¥{{ money(detail.visit.depositWarningAmount) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="预计出院日期">{{ fmtDate(detail.visit.expectedDischargeDate) }}</el-descriptions-item>',
      '          <el-descriptions-item label="联系人" :span="2">{{ contactText(detail.visit.contactName, detail.visit.contactPhone, detail.visit.contactRelation) }}</el-descriptions-item>',
      '          <el-descriptions-item label="担保人" :span="3">{{ contactText(detail.visit.guarantorName, detail.visit.guarantorPhone, detail.visit.guarantorIdNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="入院诊断" :span="3">{{ orDash(detail.visit.admitDiag) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="inp-section-title">过敏史</div>',
      '        <div v-if="allergyMap[detail.visit.id] && allergyMap[detail.visit.id].length" style="margin-bottom:16px">',
      '          <el-tag v-for="a in allergyMap[detail.visit.id]" :key="a.id" :type="allergySeverityTag(a.severity)" size="small" style="margin:0 6px 6px 0">{{ allergyTypeLabel(a.allergyType) }}·{{ a.allergenName }}({{ allergySeverityLabel(a.severity) }})</el-tag>',
      '        </div>',
      '        <div v-else class="inp-allergy-none" style="margin-bottom:16px">无已知过敏</div>',
      '        <div class="inp-section-title">患者信息</div>',
      '        <el-descriptions :column="3" border size="small" style="margin-bottom:16px">',
      '          <el-descriptions-item label="姓名">{{ detailPatient().name || "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="性别">{{ detailPatient().gender_name || genderText(detailPatient().gender) }}</el-descriptions-item>',
      '          <el-descriptions-item label="年龄">{{ detailPatient().age !== null && detailPatient().age !== undefined ? detailPatient().age : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="身份证号">{{ orDash(detailPatient().id_card) }}</el-descriptions-item>',
      '          <el-descriptions-item label="电话">{{ orDash(detailPatient().phone) }}</el-descriptions-item>',
      '          <el-descriptions-item label="患者号">{{ orDash(detailPatient().patient_no) }}</el-descriptions-item>',
      '          <el-descriptions-item label="险种">{{ orDash(detailPatient().insutype_name || detailPatient().insutype) }}</el-descriptions-item>',
      '          <el-descriptions-item label="医保人员编号" :span="2">{{ orDash(detailPatient().psn_no) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="inp-section-title">诊断记录</div>',
      '        <el-table :data="detail.diagnoses || []" border size="small">',
      '          <el-table-column label="类型" width="100"><template #default="s">{{ diagTypeLabel(s.row.diagType) }}</template></el-table-column>',
      '          <el-table-column prop="diagCode" label="编码" width="110"></el-table-column>',
      '          <el-table-column prop="diagName" label="诊断名称" min-width="160"></el-table-column>',
      '          <el-table-column label="主诊断" width="80" align="center"><template #default="s">',
      '            <el-tag v-if="s.row.isMain===1" type="danger" size="small">主</el-tag><span v-else>-</span>',
      '          </template></el-table-column>',
      '          <el-table-column label="诊断时间" width="150"><template #default="s">{{ fmtTime(s.row.diagTime) }}</template></el-table-column>',
      '        </el-table>',
      '      </template>',
      '      <el-empty v-else-if="!detailLoading" description="暂无数据"></el-empty>',
      '    </div>',
      '  </el-drawer>',
      /* ---- 转科/转床对话框 ---- */
      '  <el-dialog v-model="transferVisible" title="转科/转床" width="560px">',
      '    <div class="inp-info" style="margin-bottom:14px">',
      '      患者: <b>{{ transferRow ? transferRow.patient_name : "" }}</b>　',
      '      当前: {{ transferRow ? transferRow.ward_name : "" }} {{ transferRow ? transferRow.bed_no : "" }}床',
      '    </div>',
      '    <el-form :model="transferForm" label-width="86px">',
      '      <el-form-item label="目标病区" required>',
      '        <el-select v-model="transferForm.targetWardId" filterable placeholder="选择目标病区" style="width:100%" @change="onTransferWardChange">',
      '          <el-option v-for="w in wards" :key="w.id" :label="wardLabel(w)" :value="w.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="目标床位" required>',
      '        <el-select v-model="transferForm.targetBedId" filterable placeholder="选择空床" style="width:100%">',
      '          <el-option v-for="b in transferBeds" :key="b.id" :label="bedLabel(b)" :value="b.id"></el-option>',
      '        </el-select>',
      '        <div v-if="transferForm.targetWardId && !transferBeds.length" style="color:var(--yb-warning);font-size:12px;margin-top:4px">该病区当前无空床</div>',
      '      </el-form-item>',
      '      <el-form-item label="目标科室">',
      '        <el-select v-model="transferForm.targetDeptId" filterable clearable placeholder="不变更则留空" style="width:100%">',
      '          <el-option v-for="d in inpDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="主治医生">',
      '        <el-select v-model="transferForm.targetDoctorId" filterable clearable placeholder="不变更则留空" style="width:100%">',
      '          <el-option v-for="s in refStaffs" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="原因">',
      '        <el-input v-model="transferForm.reason" type="textarea" :rows="2" placeholder="转科/转床原因(选填)"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="transferVisible=false">取消</el-button>',
      '      <el-button type="primary" :loading="transferLoading" @click="submitTransfer">确认转床</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        loading: false, rows: [], total: 0, page: 1, size: 20,
        q: { wardId: null, deptId: null, visitStatus: null, keyword: '' },
        statusOptions: STATUS_OPTIONS, wards: [],
        glance: { total: 0, todayAdmit: 0, todayDischarge: 0, critical: 0, arrears: 0, depositWarning: 0 },
        quickFilter: '',
        detailVisible: false, detailLoading: false, detail: null,
        allergyMap: {}, extMap: {},
        transferVisible: false, transferLoading: false, transferRow: null,
        transferBeds: [],
        transferForm: { targetWardId: null, targetBedId: null, targetDeptId: null, targetDoctorId: null, reason: '' }
      };
    },
    computed: {
      ic: function () { return INP_ICONS; },
      /* 快捷筛选视图(欠费=预交金余额<0; 危重=病情1危/2重), 仅对当前页本地过滤 */
      displayRows: function () {
        var vm = this;
        var f = this.quickFilter;
        if (!f) { return this.rows; }
        if (f === 'debt') { return (this.rows || []).filter(function (r) { return Number(r.deposit_balance || 0) < 0; }); }
        return (this.rows || []).filter(function (r) { var c = vm.rowCondition(r); return c === 1 || c === 2; });
      }
    },
    methods: {
      orDash: orDash, fmtDate: fmtDate, fmtTime: fmtTime, money: money,
      genderText: genderText, statusLabel: statusLabel, statusTag: statusTag,
      diagTypeLabel: diagTypeLabel,
      nursingLevelLabel: nursingLevelLabel, nursingLevelTag: nursingLevelTag,
      conditionLevelLabel: conditionLevelLabel, conditionLevelTag: conditionLevelTag,
      allergyTypeLabel: allergyTypeLabel, allergySeverityLabel: allergySeverityLabel, allergySeverityTag: allergySeverityTag,
      admitSourceLabel: admitSourceLabel,
      /* 联系人单行文本: 姓名 电话(关系), 全空回退 '-' */
      contactText: function (name, phone, extra) {
        var parts = [];
        if (name) { parts.push(name); }
        if (phone) { parts.push(phone); }
        if (extra) { parts.push('(' + extra + ')'); }
        return parts.length ? parts.join(' ') : '-';
      },
      /* 医疗类别显示: 字典值与本地回退皆可读 */
      medTypeLabel: function (v) {
        if (v === null || v === undefined || v === '') { return '-'; }
        var o = findIn(MED_TYPE_FALLBACK, 'v', v);
        return o ? o.l : v;
      },
      /* ---- Task#30 经营统计卡片: 数据源 /api/his/inp/dashboard/patient-stats ---- */
      loadStats: function () {
        var vm = this;
        var p = new URLSearchParams();
        if (vm.q.wardId) { p.append('wardId', vm.q.wardId); }
        if (vm.q.deptId) { p.append('deptId', vm.q.deptId); }
        var qs = p.toString();
        HIS.get('/api/his/inp/dashboard/patient-stats' + (qs ? '?' + qs : '')).then(function (d) {
          d = d || {};
          vm.glance = {
            total: Number(d.total || 0), todayAdmit: Number(d.todayAdmit || 0),
            todayDischarge: Number(d.todayDischarge || 0), critical: Number(d.critical || 0),
            arrears: Number(d.arrears || 0), depositWarning: Number(d.depositWarning || 0)
          };
        }).catch(function () { /* 统计栏静默降级, 不打断主列表 */ });
      },
      /* 点击危重/欠费卡片: 切换当前页快捷筛选 */
      toggleQuick: function (k) {
        this.quickFilter = (this.quickFilter === k) ? '' : k;
        if (this.quickFilter) {
          ElementPlus.ElMessage.info('已按当前页筛选' + (k === 'debt' ? '欠费' : '危重') + '患者 ' + this.displayRows.length + ' 条');
        }
      },
      /* ---- Task#30 操作列: 图标组(>3个时前2个直显 + 下拉更多) ---- */
      opSet: function (row) {
        var st = row.visit_status;
        var ops = [{ key: 'detail', label: '详情', icon: INP_ICONS.YbiView }];
        if (st === 1 || st === 2) { ops.push({ key: 'transfer', label: '转科/转床', icon: INP_ICONS.YbiSwitch }); }
        if (st === 2) { ops.push({ key: 'discharge', label: '出院申请', icon: INP_ICONS.YbiUpload }); }
        if (st === 1 || st === 2 || st === 3) { ops.push({ key: 'cancel', label: '取消入院', icon: INP_ICONS.YbiClose, danger: true }); }
        if (ops.length > 3) { return { main: ops.slice(0, 2), more: ops.slice(2) }; }
        return { main: ops, more: [] };
      },
      onOp: function (row, key) {
        if (key === 'detail') { this.openDetail(row); }
        else if (key === 'transfer') { this.openTransfer(row); }
        else if (key === 'discharge') { this.dischargeApply(row); }
        else if (key === 'cancel') { this.cancelVisit(row); }
      },
      onQuery: function () { this.page = 1; this.load(); },
      resetQuery: function () {
        this.q = { wardId: null, deptId: null, visitStatus: null, keyword: '' };
        this.page = 1; this.load();
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        var p = new URLSearchParams();
        p.append('page', vm.page); p.append('size', vm.size);
        if (vm.q.wardId) { p.append('wardId', vm.q.wardId); }
        if (vm.q.deptId) { p.append('deptId', vm.q.deptId); }
        if (vm.q.visitStatus !== null && vm.q.visitStatus !== undefined && vm.q.visitStatus !== '') {
          p.append('visitStatus', vm.q.visitStatus);
        }
        if (vm.q.keyword && vm.q.keyword.trim()) { p.append('keyword', vm.q.keyword.trim()); }
        HIS.get('/api/his/inp/patients?' + p.toString()).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          vm.loadRowExt();
          vm.loadStats();
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 列表行扩展信息: 并行拉取过敏史(必有) + 行内缺失的护理/病情/预警线(接口扩展前兜底) */
      loadRowExt: function () {
        var vm = this;
        vm.allergyMap = {}; vm.extMap = {};
        var needExt = vm.rows.length > 0 && vm.rows[0].nursing_level === undefined;
        vm.rows.forEach(function (r) {
          var vid = HIS.idKey(r.id);
          HIS.get('/api/his/inp/allergy/list?visitId=' + HIS.idParam(vid)).then(function (list) {
            vm.allergyMap[vid] = list || [];
          }).catch(function () { vm.allergyMap[vid] = []; });
          if (needExt) {
            HIS.get('/api/his/inp/visit/' + HIS.idParam(vid)).then(function (d) {
              var v = (d && d.visit) || {};
              vm.extMap[vid] = {
                nursingLevel: v.nursingLevel, conditionLevel: v.conditionLevel,
                depositWarningAmount: v.depositWarningAmount
              };
            }).catch(function () { });
          }
        });
      },
      /* 过敏摘要(姓名后红色图标 tooltip) */
      allergySummary: function (vid) {
        var list = this.allergyMap[HIS.idKey(vid)] || [];
        return list.map(function (a) {
          return allergyTypeLabel(a.allergyType) + '·' + a.allergenName + '(' + allergySeverityLabel(a.severity) + ')';
        }).join('；') || '无过敏记录';
      },
      /* 行内护理等级: 优先列表蛇形键, 回落详情兜底 map */
      rowNursing: function (r) {
        if (r.nursing_level !== undefined && r.nursing_level !== null) { return r.nursing_level; }
        var e = this.extMap[HIS.idKey(r.id)];
        return e ? e.nursingLevel : null;
      },
      rowCondition: function (r) {
        if (r.condition_level !== undefined && r.condition_level !== null) { return r.condition_level; }
        var e = this.extMap[HIS.idKey(r.id)];
        return e ? e.conditionLevel : null;
      },
      rowWarnLine: function (r) {
        if (r.deposit_warning_amount !== undefined && r.deposit_warning_amount !== null) { return Number(r.deposit_warning_amount); }
        var e = this.extMap[HIS.idKey(r.id)];
        return (e && e.depositWarningAmount !== null && e.depositWarningAmount !== undefined) ? Number(e.depositWarningAmount) : null;
      },
      /* 欠费标识: 预交金余额 < 预警线(预警线>0 时才判定) */
      isDebt: function (r) {
        var warn = this.rowWarnLine(r);
        if (warn === null || !(warn > 0)) { return false; }
        return Number(r.deposit_balance || 0) < warn;
      },
      debtTip: function (r) {
        return '预交金余额 ¥' + money(r.deposit_balance) + ' 低于预警线 ¥' + money(this.rowWarnLine(r));
      },
      loadWards: function () {
        var vm = this;
        HIS.get('/api/his/inp/bed/ward/list').then(function (list) { vm.wards = list || []; }).catch(function () { });
      },
      /* ---- 详情抽屉 ---- */
      openDetail: function (row) {
        var vm = this;
        vm.detail = null; vm.detailVisible = true; vm.detailLoading = true;
        HIS.get('/api/his/inp/visit/' + HIS.idParam(row.id)).then(function (d) { vm.detail = d; })
          .catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      detailPatient: function () { return (this.detail && this.detail.patient) || {}; },
      detailDeptName: function () {
        var v = this.detail && this.detail.visit;
        return v ? this.deptName(v.deptId) : '-';
      },
      detailBedText: function () {
        var b = this.detail && this.detail.bed;
        if (!b) { return '-'; }
        return b.bedNo + '床' + (b.roomNo ? ' · ' + b.roomNo + '房' : '');
      },
      /* ---- 转科/转床 ---- */
      openTransfer: function (row) {
        this.transferRow = row;
        this.transferVisible = true;
        this.transferBeds = [];
        this.transferForm = {
          targetWardId: row.ward_id || null, targetBedId: null,
          targetDeptId: row.dept_id || null, targetDoctorId: row.doctor_id || null, reason: ''
        };
        this.loadTransferBeds();
      },
      onTransferWardChange: function () {
        this.transferForm.targetBedId = null;
        this.loadTransferBeds();
      },
      loadTransferBeds: function () {
        var vm = this;
        var wardId = vm.transferForm.targetWardId;
        if (!wardId) { vm.transferBeds = []; return; }
        HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(wardId)).then(function (list) {
          vm.transferBeds = (list || []).filter(function (b) { return b.status === 0; });
        }).catch(HIS.notifyError);
      },
      submitTransfer: function () {
        var vm = this;
        if (!vm.transferForm.targetWardId) { HIS.notifyError(new Error('请选择目标病区')); return; }
        if (!vm.transferForm.targetBedId) { HIS.notifyError(new Error('请选择目标床位')); return; }
        vm.transferLoading = true;
        HIS.put('/api/his/inp/visit/' + HIS.idParam(vm.transferRow.id) + '/transfer', {
          targetWardId: HIS.id(vm.transferForm.targetWardId), targetBedId: HIS.id(vm.transferForm.targetBedId),
          targetDeptId: HIS.id(vm.transferForm.targetDeptId), targetDoctorId: HIS.id(vm.transferForm.targetDoctorId),
          reason: vm.transferForm.reason || null
        }).then(function () {
          HIS.notifySuccess('转科/转床完成');
          vm.transferVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.transferLoading = false; });
      },
      /* ---- 出院申请(2在院 -> 3出院办理中) ---- */
      dischargeApply: function (row) {
        var vm = this;
        confirmBox('确认为患者「' + escHtml(row.patient_name) + '」提交出院申请?提交后进入出院办理中, 可办理结算。', '出院申请').then(function () {
          return HIS.put('/api/his/inp/visit/' + HIS.idParam(row.id) + '/discharge-apply');
        }).then(function () {
          HIS.notifySuccess('已提交出院申请');
          vm.load();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ---- 取消入院(1/2/3 -> 5已取消, 释放床位) ---- */
      cancelVisit: function (row) {
        var vm = this;
        confirmBox('确认取消患者「' + escHtml(row.patient_name) + '」的入院登记?取消后将释放床位且不可恢复。', '取消入院').then(function () {
          return HIS.put('/api/his/inp/visit/' + HIS.idParam(row.id) + '/cancel');
        }).then(function () {
          HIS.notifySuccess('已取消入院');
          vm.load();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      }
    },
    mounted: function () {
      var vm = this;
      this.loadRefs();
      this.loadWards();
      this.load();
      /* Task#30 统计卡片 30s 轮询刷新 */
      this._glanceTimer = setInterval(function () { vm.loadStats(); }, 30000);
    },
    beforeUnmount: function () {
      if (this._glanceTimer) { clearInterval(this._glanceTimer); this._glanceTimer = null; }
    }
  };

  /* ========================================================================
   * 2.9 InpBedCard 床位卡片(Task#30): 120px 信息增强 + 悬停气泡 + 拖拽换床
   *     平铺/按房间两视图共用; 纯展示组件, 患者/扩展数据由 InpBedManage 组合传入
   * ====================================================================== */
  var InpBedCard = {
    name: 'InpBedCard',
    props: {
      bed: { type: Object, required: true },
      pat: { type: Object, default: null },
      ext: { type: Object, default: null },
      cls: { type: String, default: '' },
      desc: { type: Array, default: function () { return []; } },
      canDrag: { type: Boolean, default: false }
    },
    emits: ['pick', 'dragstart', 'dragend', 'dragenter', 'dragleave', 'drop'],
    computed: {
      showPop: function () { return this.bed.status === 1 && !!this.pat; },
      metaText: function () {
        var p = this.pat || {};
        var parts = [];
        var g = p.gender_name || genderText(p.gender);
        if (g && g !== '-') { parts.push(g); }
        if (p.age !== null && p.age !== undefined && p.age !== '') { parts.push(p.age + '岁'); }
        return parts.join(' · ');
      },
      stayD: function () { return stayDays((this.pat || {}).admit_date || (this.ext || {}).admitDate); },
      nursingVal: function () {
        var p = this.pat || {};
        if (p.nursing_level !== undefined && p.nursing_level !== null) { return p.nursing_level; }
        return (this.ext || {}).nursingLevel || null;
      },
      /* 医保tag: 医疗类别(21普通住院等), 无值不展示 */
      medText: function () {
        var v = (this.ext || {}).medType;
        if (v === undefined || v === null || v === '') { v = (this.pat || {}).med_type; }
        if (v === undefined || v === null || v === '') { return ''; }
        var o = findIn(MED_TYPE_FALLBACK, 'v', v);
        return o ? o.l : String(v);
      },
      diagText: function () {
        var d = (this.ext || {}).admitDiag || (this.pat || {}).admit_diag;
        return (d === null || d === undefined || d === '') ? '-' : String(d);
      }
    },
    methods: {
      bedLevelLabel: bedLevelLabel, bedLevelTag: bedLevelTag, bedLevelStyle: bedLevelStyle,
      nursingLevelLabel: nursingLevelLabel,
      onPick: function () { this.$emit('pick', this.bed); },
      onDragStart: function (e) {
        if (!this.canDrag) { return; }
        this.$emit('dragstart', this.bed, e);
      },
      onDragEnd: function (e) { this.$emit('dragend', this.bed, e); },
      /* 仅空床允许作为 drop 目标(dragover preventDefault 是 drop 触发的前提) */
      onDragOver: function (e) {
        if (this.bed.status === 0) { e.preventDefault(); }
      },
      onDragEnter: function (e) { this.$emit('dragenter', this.bed, e); },
      /* relatedTarget 仍在卡片内部(子元素间移动)时不视为离开 */
      onDragLeave: function (e) {
        var rt = e.relatedTarget;
        if (!rt || !e.currentTarget.contains(rt)) { this.$emit('dragleave', this.bed, e); }
      },
      onDrop: function (e) {
        if (this.bed.status === 0) { e.preventDefault(); this.$emit('drop', this.bed, e); }
      }
    },
    template: [
      '<el-popover :disabled="!showPop" trigger="hover" placement="top" :width="280" popper-class="inp-bed-pop">',
      '  <el-descriptions :column="2" size="small" border>',
      '    <el-descriptions-item v-for="d in desc" :key="d.label" :label="d.label" :span="d.span || 1">{{ d.value }}</el-descriptions-item>',
      '  </el-descriptions>',
      '  <template #reference>',
      '    <div class="inp-bed2" :class="cls" :draggable="canDrag" @click="onPick" @dragstart="onDragStart" @dragend="onDragEnd" @dragover="onDragOver" @dragenter="onDragEnter" @dragleave="onDragLeave" @drop="onDrop">',
      '      <div class="b2-head">',
      '        <span class="b2-no">{{ bed.bedNo }}</span>',
      '        <el-tag v-if="bed.bedLevel" :type="bedLevelTag(bed.bedLevel)" :style="bedLevelStyle(bed.bedLevel)" size="small" effect="plain">{{ bedLevelLabel(bed.bedLevel) }}</el-tag>',
      '      </div>',
      '      <template v-if="bed.status === 1">',
      '        <div class="b2-line"><span class="b2-name">{{ pat ? pat.patient_name : "占用" }}</span><span class="b2-meta">{{ metaText }}</span></div>',
      '        <div class="b2-line">',
      '          <span v-if="stayD !== null">住院{{ stayD }}天</span>',
      '          <span v-if="nursingVal" class="lv-dot" :class="\'lv-\' + nursingVal" :title="\'护理等级: \' + nursingLevelLabel(nursingVal)"></span>',
      '          <el-tag v-if="medText" class="b2-med" size="small" effect="plain">{{ medText }}</el-tag>',
      '        </div>',
      '        <div class="b2-diag" :title="diagText">{{ diagText }}</div>',
      '      </template>',
      '      <div v-else-if="bed.status === 2" class="b2-empty">停用</div>',
      '      <div v-else class="b2-empty">',
      '        <span>空床</span>',
      '        <span v-if="bed.genderLimit === 1" class="gender-m" style="margin-left:6px" title="限男">♂</span>',
      '        <span v-else-if="bed.genderLimit === 2" class="gender-f" style="margin-left:6px" title="限女">♀</span>',
      '      </div>',
      '    </div>',
      '  </template>',
      '</el-popover>'
    ].join('\n')
  };

  /* ========================================================================
   * 3. InpBedManage 床位管理(病区CRUD + 床位CRUD/启停 + 床位一览卡片/按房间/拖拽换床)
   * ====================================================================== */
  HIS.views.InpBedManage = {
    mixins: [refMixin],
    components: Object.assign({ InpBedCard: InpBedCard }, INP_ICONS),
    template: [
      '<div class="inp-bed-mgmt">',
      '  <div v-if="!canWrite" style="margin-bottom:10px;color:var(--yb-warning);font-size:12px">当前账号无本机构床位维护权限, 仅可查看(需机构管理员角色)</div>',
      '  <el-row :gutter="12">',
      '    <el-col :span="7">',
      '      <div class="inp-card">',
      '        <div class="inp-section-title" style="margin-bottom:8px">病区列表</div>',
      '        <div class="toolbar" style="margin-bottom:8px">',
      '          <el-button v-if="canWrite" type="primary" size="small" @click="openWardEdit(null)">新增病区</el-button>',
      '          <el-button v-if="canWrite" size="small" :disabled="!currentWard" @click="openWardEdit(currentWard)">编辑</el-button>',
      '          <el-button v-if="canWrite" size="small" type="danger" plain :disabled="!currentWard" @click="deleteWard">删除</el-button>',
      '        </div>',
      '        <el-table ref="wardTable" :data="wardRows" border size="small" highlight-current-row row-key="id" height="430" @current-change="onWardPick">',
      '          <el-table-column prop="wardName" label="病区名称" min-width="100"></el-table-column>',
      '          <el-table-column prop="wardCode" label="编码" width="76"></el-table-column>',
      '          <el-table-column label="空/占/停" width="88" align="center">',
      '            <template #default="s">{{ ovOf(s.row.id).empty }}/{{ ovOf(s.row.id).occupied }}/{{ ovOf(s.row.id).disabled }}</template>',
      '          </el-table-column>',
      '          <el-table-column label="状态" width="70" align="center"><template #default="s">',
      '            <el-tag :type="s.row.status===1 ? \'success\' : \'info\'" size="small">{{ s.row.status===1 ? "启用" : "停用" }}</el-tag>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="17">',
      '      <div class="inp-card" style="margin-bottom:12px">',
      '        <div class="inp-section-title" style="margin-bottom:8px">床位管理{{ currentWard ? " · " + currentWard.wardName : "" }}</div>',
      '        <div class="toolbar" style="margin-bottom:8px">',
      '          <el-button v-if="canWrite" type="primary" size="small" :disabled="!currentWard" @click="openBedEdit(null)">新增床位</el-button>',
      '          <span v-if="!currentWard" style="color:var(--yb-ink-3);font-size:12px">请先在左侧选择病区</span>',
      '        </div>',
      '        <el-table :data="bedRows" v-loading="bedLoading" border size="small" height="228">',
      '          <el-table-column label="序号" width="55" align="center"><template #default="s">{{ idx(s.$index) }}</template></el-table-column>',
      '          <el-table-column prop="bedNo" label="床号" width="84"></el-table-column>',
      '          <el-table-column prop="roomNo" label="房间号" width="84"></el-table-column>',
      '          <el-table-column label="类型" width="80"><template #default="s">{{ bedTypeLabel(s.row.bedType) }}</template></el-table-column>',
      '          <el-table-column label="等级" width="86" align="center"><template #default="s">',
      '            <el-tag v-if="s.row.bedLevel" :type="bedLevelTag(s.row.bedLevel)" :style="bedLevelStyle(s.row.bedLevel)" size="small" effect="plain">{{ bedLevelLabel(s.row.bedLevel) }}</el-tag>',
      '            <span v-else>-</span>',
      '          </template></el-table-column>',
      '          <el-table-column label="性别限制" width="80" align="center"><template #default="s">{{ genderLimitLabel(s.row.genderLimit) }}</template></el-table-column>',
      '          <el-table-column label="状态" width="88" align="center"><template #default="s">',
      '            <el-tag :type="bedStatusTag(s.row.status)" size="small">{{ bedStatusLabel(s.row.status) }}</el-tag>',
      '          </template></el-table-column>',
      '          <el-table-column label="患者" min-width="100"><template #default="s">{{ bedPatientName(s.row) }}</template></el-table-column>',
      '          <el-table-column label="日费(元)" width="90" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.dailyPrice) }}</span></template></el-table-column>',
      '          <el-table-column label="操作" width="96" fixed="right"><template #default="s">',
      '            <el-tooltip v-if="canWrite" content="编辑床位" placement="top" :show-after="300"><span class="inp-op" @click="openBedEdit(s.row)"><component :is="ic.YbiEdit" :size="16"></component></span></el-tooltip>',
      '            <el-tooltip v-if="canWrite && s.row.status===1" content="占用中, 不可停用" placement="top" :show-after="300"><span class="inp-op is-off"><component :is="ic.YbiLock" :size="16"></component></span></el-tooltip>',
      '            <el-tooltip v-else-if="canWrite" :content="s.row.status===0 ? \'停用床位\' : \'启用床位\'" placement="top" :show-after="300"><span class="inp-op" :class="s.row.status===0 ? \'is-danger\' : \'is-ok\'" @click="toggleBed(s.row)"><component :is="s.row.status===0 ? ic.YbiBan : ic.YbiPower" :size="16"></component></span></el-tooltip>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </div>',
      '      <div class="inp-card">',
      '        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">',
      '          <div class="inp-section-title" style="margin:0">床位一览{{ currentWard ? " · " + currentWard.wardName : "" }}</div>',
      '          <el-radio-group v-model="bedView" size="small">',
      '            <el-radio-button label="flat">平铺</el-radio-button>',
      '            <el-radio-button label="room">按房间</el-radio-button>',
      '          </el-radio-group>',
      '        </div>',
      '        <div v-if="!bedRows.length" style="color:var(--yb-ink-3);font-size:13px;padding:20px 0;text-align:center">{{ currentWard ? "该病区暂无床位" : "请先在左侧选择病区" }}</div>',
      '        <template v-else-if="bedView===\'flat\'">',
      '          <el-row :gutter="10">',
      '            <el-col :span="4" v-for="b in bedRows" :key="b.id" style="margin-bottom:10px">',
      '              <inp-bed-card :bed="b" :pat="bedPats[idKey(b.id)]" :ext="bedExtOf(b)" :cls="bedCardCls2(b)" :desc="bedDescOf(b)" :can-drag="canDragBed(b)" @pick="onBedCardClick" @dragstart="onBedDragStart" @dragend="onBedDragEnd" @dragenter="onBedDragEnter" @dragleave="onBedDragLeave" @drop="onBedDrop"></inp-bed-card>',
      '            </el-col>',
      '          </el-row>',
      '        </template>',
      '        <template v-else>',
      '          <el-card v-for="g in roomGroups" :key="g.key" class="inp-room-card" shadow="never">',
      '            <template #header>',
      '              <div style="display:flex;align-items:center;justify-content:space-between">',
      '                <span style="font-weight:600;font-size:13px">{{ g.label }}</span>',
      '                <span style="font-size:12px;color:var(--yb-ink-3)">{{ g.beds.length }}床 · 占用{{ g.occ }} · 空{{ g.free }} · 停用{{ g.off }}</span>',
      '              </div>',
      '            </template>',
      '            <div class="inp-room-beds">',
      '              <inp-bed-card v-for="b in g.beds" :key="b.id" :bed="b" :pat="bedPats[idKey(b.id)]" :ext="bedExtOf(b)" :cls="bedCardCls2(b)" :desc="bedDescOf(b)" :can-drag="canDragBed(b)" @pick="onBedCardClick" @dragstart="onBedDragStart" @dragend="onBedDragEnd" @dragenter="onBedDragEnter" @dragleave="onBedDragLeave" @drop="onBedDrop"></inp-bed-card>',
      '            </div>',
      '          </el-card>',
      '        </template>',
      '        <div style="margin-top:6px;font-size:12px;color:var(--yb-ink-3)">图例: <span style="color:var(--yb-success)">绿=空床</span>　<span style="color:var(--yb-link)">蓝=占用</span>　<span style="color:var(--yb-ink-4)">灰=停用</span>　<span style="color:var(--yb-fill-info)">蓝框=男床</span>　<span style="color:#dd87ac">粉框=女床</span>　<span style="color:var(--yb-fill-danger)">红呼吸灯=危重</span>　<span style="color:var(--yb-brand)">拖占用床至空床=换床</span></div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      /* ---- 病区编辑对话框 ---- */
      '  <el-dialog v-model="wardVisible" :title="wardForm.id ? \'编辑病区\' : \'新增病区\'" width="480px">',
      '    <el-form :model="wardForm" label-width="82px">',
      '      <el-form-item label="病区名称" required><el-input v-model="wardForm.wardName" placeholder="如: 内科一病区"></el-input></el-form-item>',
      '      <el-form-item label="病区编码"><el-input v-model="wardForm.wardCode" placeholder="选填"></el-input></el-form-item>',
      '      <el-form-item label="楼栋"><el-input v-model="wardForm.building" placeholder="如: 住院部A楼"></el-input></el-form-item>',
      '      <el-form-item label="楼层"><el-input v-model="wardForm.floor" placeholder="如: 5F"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-radio-group v-model="wardForm.status"><el-radio :label="1">启用</el-radio><el-radio :label="0">停用</el-radio></el-radio-group></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="wardForm.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="wardVisible=false">取消</el-button>',
      '      <el-button type="primary" :loading="wardSaving" @click="saveWard">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 床位编辑对话框 ---- */
      '  <el-dialog v-model="bedVisible" :title="bedForm.id ? \'编辑床位\' : \'新增床位\'" width="460px">',
      '    <el-form :model="bedForm" label-width="82px">',
      '      <el-form-item label="床号" required><el-input v-model="bedForm.bedNo" placeholder="如: 01"></el-input></el-form-item>',
      '      <el-form-item label="房间号"><el-input v-model="bedForm.roomNo" placeholder="如: 501"></el-input></el-form-item>',
      '      <el-form-item label="床位类型">',
      '        <el-select v-model="bedForm.bedType" style="width:100%"><el-option v-for="t in bedTypeOptions" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '      </el-form-item>',
      '      <el-form-item label="床位等级">',
      '        <el-select v-model="bedForm.bedLevel" clearable placeholder="选择床位等级" style="width:100%"><el-option v-for="t in bedLevelOptions" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '      </el-form-item>',
      '      <el-form-item label="性别限制">',
      '        <el-select v-model="bedForm.genderLimit" style="width:100%">',
      '          <el-option :value="0" label="无限制"></el-option>',
      '          <el-option :value="1" label="限男"></el-option>',
      '          <el-option :value="2" label="限女"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="日费(元)"><el-input-number v-model="bedForm.dailyPrice" :min="0" :precision="2" :step="10" style="width:100%"></el-input-number></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="bedVisible=false">取消</el-button>',
      '      <el-button type="primary" :loading="bedSaving" @click="saveBed">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        canWrite: false,
        wardRows: [], currentWard: null, ovRows: [],
        bedRows: [], bedPats: {}, bedExtMap: {}, bedLoading: false,
        bedView: 'flat', dragBedId: null, dragVisit: null, dropBedId: null,
        bedTypeOptions: BED_TYPE_OPTIONS, bedLevelOptions: BED_LEVEL_OPTIONS,
        wardVisible: false, wardSaving: false,
        wardForm: { id: null, wardName: '', wardCode: '', building: '', floor: '', status: 1, remark: '' },
        bedVisible: false, bedSaving: false,
        bedForm: { id: null, bedNo: '', roomNo: '', bedType: 1, dailyPrice: 0, bedLevel: null, genderLimit: 0 }
      };
    },
    computed: {
      ic: function () { return INP_ICONS; },
      /* Task#30 按房间分组视图: roomNo 优先, 否则床号数字前缀(101/102/103 -> 1号房间) */
      roomGroups: function () {
        var groups = {};
        (this.bedRows || []).forEach(function (b) {
          var g = bedGroupOf(b);
          if (!groups[g.key]) { groups[g.key] = { key: g.key, label: g.label, sort: g.sort, beds: [], occ: 0, free: 0, off: 0 }; }
          var gr = groups[g.key];
          gr.beds.push(b);
          if (b.status === 1) { gr.occ += 1; } else if (b.status === 0) { gr.free += 1; } else { gr.off += 1; }
        });
        return Object.keys(groups).map(function (k) { return groups[k]; })
          .sort(function (a, b) { return (a.sort - b.sort) || a.key.localeCompare(b.key); })
          .map(function (gr) {
            gr.beds.sort(function (a, b) { return String(a.bedNo).localeCompare(String(b.bedNo), 'zh', { numeric: true }); });
            return gr;
          });
      }
    },
    methods: {
      money: money, orDash: orDash, bedTypeLabel: bedTypeLabel,
      /* 雪花ID键规范化(供模板 bedPats[idKey(b.id)] 使用, 与 HIS.idKey 同源) */
      idKey: HIS.idKey,
      bedStatusLabel: bedStatusLabel, bedStatusTag: bedStatusTag,
      bedLevelLabel: bedLevelLabel, bedLevelTag: bedLevelTag, bedLevelStyle: bedLevelStyle,
      genderLimitLabel: function (g) { return g === 1 ? '限男' : (g === 2 ? '限女' : '无限制'); },
      idx: function (i) { return i + 1; },
      loadWards: function () {
        var vm = this;
        return HIS.get('/api/his/inp/bed/ward/list').then(function (list) {
          vm.wardRows = list || [];
          if (vm.currentWard) {
            var hit = null;
            vm.wardRows.forEach(function (w) { if (HIS.sameId(w.id, vm.currentWard.id)) { hit = w; } });
            vm.currentWard = hit;
            vm.$nextTick(function () {
              if (hit && vm.$refs.wardTable) { vm.$refs.wardTable.setCurrentRow(hit); }
            });
          }
        }).catch(HIS.notifyError);
      },
      loadOverview: function () {
        var vm = this;
        HIS.get('/api/his/inp/bed/overview').then(function (list) { vm.ovRows = list || []; }).catch(function () { });
      },
      ovOf: function (wardId) {
        var hit = null;
        (this.ovRows || []).forEach(function (o) { if (HIS.sameId(o.wardId, wardId)) { hit = o; } });
        return hit || { empty: 0, occupied: 0, disabled: 0, total: 0 };
      },
      onWardPick: function (w) {
        this.currentWard = w || null;
        this.loadBeds();
      },
      loadBeds: function () {
        var vm = this;
        if (!vm.currentWard) { vm.bedRows = []; vm.bedPats = {}; vm.bedExtMap = {}; return; }
        vm.bedLoading = true;
        HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(vm.currentWard.id)).then(function (list) {
          vm.bedRows = list || [];
        }).catch(HIS.notifyError).finally(function () { vm.bedLoading = false; });
        /* Task#30: 在院/出院办理中患者(蛇形键)构建 床位ID -> 患者 映射; 卡片/悬停气泡/拖拽换床共用 */
        HIS.get('/api/his/inp/patients?wardId=' + HIS.idParam(vm.currentWard.id) + '&page=1&size=300').then(function (d) {
          var m = {};
          ((d && d.records) || []).forEach(function (r) {
            if (r.bed_id && (r.visit_status === 2 || r.visit_status === 3)) { m[HIS.idKey(r.bed_id)] = r; }
          });
          vm.bedPats = m;
          vm.loadBedExt();
        }).catch(function () { vm.bedPats = {}; });
      },
      /* 批量补查扩展信息(护理等级/病情/诊断/险种): 6 路并发, 结果缓存 bedExtMap(键=visitId) */
      loadBedExt: function () {
        var vm = this;
        vm.bedExtMap = {};
        var ids = [];
        Object.keys(vm.bedPats || {}).forEach(function (k) {
          var r = vm.bedPats[k];
          if (r && (r.nursing_level === undefined || r.condition_level === undefined)) { ids.push(HIS.idKey(r.id)); }
        });
        if (!ids.length) { return; }
        var i = 0;
        function next() {
          if (i >= ids.length) { return; }
          var vid = ids[i];
          i += 1;
          HIS.get('/api/his/inp/visit/' + HIS.idParam(vid)).then(function (d) {
            var v = (d && d.visit) || {};
            vm.bedExtMap[vid] = {
              nursingLevel: v.nursingLevel, conditionLevel: v.conditionLevel,
              doctorId: v.doctorId, admitDiag: v.admitDiag, admitDate: v.admitDate,
              inpNo: v.inpNo, medType: v.medType, depositBalance: v.depositBalance
            };
          }).catch(function () { }).finally(next);
        }
        for (var n = 0; n < 6; n++) { next(); }
      },
      bedExtOf: function (b) {
        var p = this.bedPats[HIS.idKey(b.id)];
        return p ? (this.bedExtMap[HIS.idKey(p.id)] || null) : null;
      },
      /* Task#30 拖拽换床: 仅对在院(visit_status=2)占用床开放(后端 transfer 要求) */
      canDragBed: function (b) {
        var p = this.bedPats[HIS.idKey(b.id)];
        return !!this.canWrite && b.status === 1 && !!p && p.visit_status === 2;
      },
      isCriticalBed: function (b) {
        var p = this.bedPats[HIS.idKey(b.id)];
        if (!p) { return false; }
        var c = (p.condition_level !== undefined && p.condition_level !== null)
          ? p.condition_level : ((this.bedExtMap[HIS.idKey(p.id)] || {}).conditionLevel);
        return c === 1 || c === 2;
      },
      /* Task#30 卡片组合类: 占用/空/停 + 性别框 + 危重呼吸灯 + 拖拽态 */
      bedCardCls2: function (b) {
        var cls = b.status === 1 ? 'is-occ' : (b.status === 2 ? 'is-off' : 'is-free');
        if (b.genderLimit === 1) { cls += ' inp-bed-male'; }
        else if (b.genderLimit === 2) { cls += ' inp-bed-female'; }
        if (b.status === 1 && this.isCriticalBed(b)) { cls += ' is-critical'; }
        if (this.dragBedId === b.id || HIS.sameId(this.dragBedId, b.id)) { cls += ' is-dragging'; }
        if (HIS.sameId(this.dropBedId, b.id)) { cls += ' is-drop'; }
        return cls;
      },
      /* 悬停气泡内容(el-descriptions): 缺失字段回退 '-' */
      bedDescOf: function (b) {
        var p = this.bedPats[HIS.idKey(b.id)];
        if (!p) { return []; }
        var e = this.bedExtMap[HIS.idKey(p.id)] || {};
        var nl = (p.nursing_level !== undefined && p.nursing_level !== null) ? p.nursing_level : e.nursingLevel;
        var bal = (p.deposit_balance === undefined || p.deposit_balance === null) ? e.depositBalance : p.deposit_balance;
        var age = (p.age === null || p.age === undefined || p.age === '') ? '-' : (p.age + '岁');
        return [
          { label: '姓名', value: orDash(p.patient_name) },
          { label: '性别年龄', value: (p.gender_name || genderText(p.gender)) + ' · ' + age },
          { label: '住院号', value: orDash(p.inp_no || e.inpNo), span: 2 },
          { label: '入院日期', value: fmtDate(p.admit_date || e.admitDate) },
          { label: '主管医生', value: this.staffName(p.doctor_id || e.doctorId) },
          { label: '护理等级', value: nl !== null && nl !== undefined ? nursingLevelLabel(nl) : '-', span: 2 },
          { label: '预交金余额', value: '¥' + money(bal), span: 2 },
          { label: '入院诊断', value: orDash(p.admit_diag || e.admitDiag), span: 2 }
        ];
      },
      bedPatientName: function (b) {
        if (b.status === 2) { return '停用'; }
        if (b.status !== 1) { return '空'; }
        var p = this.bedPats[HIS.idKey(b.id)];
        return (p && p.patient_name) || '占用';
      },
      openWardEdit: function (w) {
        this.wardForm = w ? {
          id: HIS.id(w.id), wardName: w.wardName, wardCode: w.wardCode, building: w.building,
          floor: w.floor, status: w.status == null ? 1 : w.status, remark: w.remark
        } : { id: null, wardName: '', wardCode: '', building: '', floor: '', status: 1, remark: '' };
        this.wardVisible = true;
      },
      saveWard: function () {
        var vm = this;
        if (!vm.wardForm.wardName || !vm.wardForm.wardName.trim()) { HIS.notifyError(new Error('请填写病区名称')); return; }
        vm.wardSaving = true;
        HIS.post('/api/his/inp/bed/ward', vm.wardForm).then(function () {
          HIS.notifySuccess('病区已保存');
          vm.wardVisible = false;
          vm.loadWards(); vm.loadOverview();
        }).catch(HIS.notifyError).finally(function () { vm.wardSaving = false; });
      },
      deleteWard: function () {
        var vm = this;
        if (!vm.currentWard) { return; }
        confirmBox('确认删除病区「' + escHtml(vm.currentWard.wardName) + '」?病区内存在床位时不允许删除。', '删除病区').then(function () {
          return HIS.del('/api/his/inp/bed/ward/' + HIS.idParam(vm.currentWard.id));
        }).then(function () {
          HIS.notifySuccess('病区已删除');
          vm.currentWard = null; vm.bedRows = []; vm.bedPats = {}; vm.bedExtMap = {};
          vm.loadWards(); vm.loadOverview();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      openBedEdit: function (b) {
        if (!this.currentWard) { HIS.notifyError(new Error('请先选择病区')); return; }
        this.bedForm = b ? {
          id: HIS.id(b.id), bedNo: b.bedNo, roomNo: b.roomNo, bedType: b.bedType == null ? 1 : b.bedType,
          dailyPrice: b.dailyPrice == null ? 0 : Number(b.dailyPrice),
          bedLevel: b.bedLevel == null ? null : b.bedLevel, genderLimit: b.genderLimit == null ? 0 : b.genderLimit
        } : { id: null, bedNo: '', roomNo: '', bedType: 1, dailyPrice: 0, bedLevel: null, genderLimit: 0 };
        this.bedVisible = true;
      },
      saveBed: function () {
        var vm = this;
        if (!vm.bedForm.bedNo || !vm.bedForm.bedNo.trim()) { HIS.notifyError(new Error('请填写床位号')); return; }
        var body = {
          id: HIS.id(vm.bedForm.id), wardId: HIS.id(vm.currentWard.id), bedNo: vm.bedForm.bedNo.trim(),
          roomNo: vm.bedForm.roomNo || null, bedType: vm.bedForm.bedType,
          dailyPrice: vm.bedForm.dailyPrice,
          bedLevel: vm.bedForm.bedLevel == null ? null : vm.bedForm.bedLevel,
          genderLimit: vm.bedForm.genderLimit == null ? 0 : vm.bedForm.genderLimit
        };
        vm.bedSaving = true;
        HIS.post('/api/his/inp/bed', body).then(function () {
          HIS.notifySuccess('床位已保存');
          vm.bedVisible = false;
          vm.loadBeds(); vm.loadOverview();
        }).catch(HIS.notifyError).finally(function () { vm.bedSaving = false; });
      },
      toggleBed: function (b) {
        var vm = this;
        var toStop = b.status === 0;
        confirmBox('确认' + (toStop ? '停用' : '启用') + '床位「' + escHtml(vm.currentWard.wardName) + ' ' + escHtml(b.bedNo) + '床」?', toStop ? '停用床位' : '启用床位').then(function () {
          return HIS.put('/api/his/inp/bed/' + HIS.idParam(b.id) + '/status');
        }).then(function () {
          HIS.notifySuccess(toStop ? '床位已停用' : '床位已启用');
          vm.loadBeds(); vm.loadOverview();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ---- Task#30 拖拽换床: 源=占用床(在院), 目标=空床 ---- */
      onBedDragStart: function (b, e) {
        if (!this.canDragBed(b) || !e || !e.dataTransfer) { return; }
        this.dragBedId = HIS.id(b.id);
        this.dragVisit = this.bedPats[HIS.idKey(b.id)] || null;
        this.dropBedId = null;
        try {
          e.dataTransfer.effectAllowed = 'move';
          e.dataTransfer.setData('text/plain', String(b.id));
        } catch (err) { /* 旧浏览器 setData 可能失败, 不阻断拖拽 */ }
      },
      onBedDragEnd: function () {
        this.dragBedId = null; this.dragVisit = null; this.dropBedId = null;
      },
      onBedDragEnter: function (b) {
        if (!this.dragBedId || b.status !== 0 || HIS.sameId(b.id, this.dragBedId)) { return; }
        this.dropBedId = HIS.id(b.id);
      },
      onBedDragLeave: function (b) {
        if (HIS.sameId(this.dropBedId, b.id)) { this.dropBedId = null; }
      },
      /* 落床确认: 复用 PUT visit transfer(后端转床语义); 成功后刷新床位与病区统计 */
      onBedDrop: function (b) {
        var vm = this;
        var p = vm.dragVisit;
        var src = null;
        if (!vm.dragBedId || !p || b.status !== 0) { return; }
        (vm.bedRows || []).forEach(function (x) { if (HIS.sameId(x.id, vm.dragBedId)) { src = x; } });
        vm.dropBedId = null;
        confirmBox('确认将 ' + escHtml(p.patient_name) + ' 从 ' + escHtml(src ? src.bedNo : '--') + '床 换到 ' + escHtml(b.bedNo) + '床?', '转床确认').then(function () {
          return HIS.put('/api/his/inp/visit/' + HIS.idParam(p.id) + '/transfer', {
            targetWardId: HIS.id(b.wardId || vm.currentWard.id), targetBedId: HIS.id(b.id),
            targetDeptId: HIS.id(p.dept_id || null), targetDoctorId: HIS.id(p.doctor_id || null),
            reason: '床位拖拽换床 ' + (src ? src.bedNo : '--') + ' -> ' + b.bedNo
          });
        }).then(function () {
          HIS.notifySuccess('换床完成');
          vm.loadBeds(); vm.loadOverview();
        }).catch(function (err) { if (!isCancel(err)) { HIS.notifyError(err); } }).finally(function () { vm.onBedDragEnd(); });
      },
      onBedCardClick: function (b) {
        var p = this.bedPats[HIS.idKey(b.id)];
        if (b.status === 1) {
          ElementPlus.ElMessage.info('床位' + b.bedNo + ' 已被患者「' + ((p && p.patient_name) || '未知') + '」占用'
            + (p && p.visit_status === 2 ? ', 可拖拽至空床换床' : ''));
          return;
        }
        if (this.canWrite) { this.openBedEdit(b); }
      }
    },
    mounted: function () {
      this.canWrite = HIS.canMaintainSelfOrg();
      this.loadRefs();
      this.loadWards();
      this.loadOverview();
    }
  };

  /* 患者下拉选项文案(住院中患者列表记录, 蛇形键) */
  function visitLabelOf(v) {
    if (!v) { return '-'; }
    return v.inp_no + ' · ' + v.patient_name
      + (v.ward_name ? '（' + v.ward_name + (v.bed_no ? ' ' + v.bed_no + '床' : '') + '）' : '');
  }
  /* 拉取患者(用于下拉选择): 取最近 300 条 */
  function fetchVisits() {
    return HIS.get('/api/his/inp/patients?page=1&size=300').then(function (d) { return (d && d.records) || []; });
  }

  /* ========================================================================
   * 4. InpDeposit 预交金管理(缴纳/退还 + 余额大字 + 流水)
   * ====================================================================== */
  HIS.views.InpDeposit = {
    mixins: [refMixin],
    template: [
      '<div class="inp-deposit">',
      '  <div class="toolbar">',
      '    <el-select v-model="visitId" filterable placeholder="选择患者(住院号/姓名)" style="width:340px" @change="onPickVisit">',
      '      <el-option v-for="v in visits" :key="v.id" :label="visitLabel(v)" :value="v.id"></el-option>',
      '    </el-select>',
      '    <el-button @click="loadVisits">刷新患者</el-button>',
      '    <span style="color:var(--yb-ink-3);font-size:12px">仅待入院/在院/出院办理中的患者可收退预交金</span>',
      '  </div>',
      '  <el-row :gutter="12" style="margin-bottom:12px">',
      '    <el-col :span="6">',
      '      <div class="inp-alert-card" @click="openAlertList">',
      '        <div class="inp-alert-num">{{ alertPatients.length }}</div>',
      '        <div class="inp-stat-label">预交金不足预警患者 · 点击查看列表</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      '  <el-row :gutter="12">',
      '    <el-col :span="9">',
      '      <div class="inp-card">',
      '        <div class="inp-section-title" style="margin-bottom:10px">预交金操作</div>',
      '        <div style="text-align:center;padding:8px 0 16px">',
      '          <div class="inp-balance-label">当前预交金余额</div>',
      '          <div class="inp-balance-num">¥ {{ balance === null ? "--" : money(balance) }}</div>',
      '        </div>',
      '        <el-form :model="form" label-width="86px">',
      '          <el-form-item label="操作方向">',
      '            <el-radio-group v-model="form.direction">',
      '              <el-radio :label="1">缴纳</el-radio>',
      '              <el-radio :label="2">退还</el-radio>',
      '            </el-radio-group>',
      '          </el-form-item>',
      '          <el-form-item label="金额(元)" required>',
      '            <el-input-number v-model="form.amount" :min="0.01" :precision="2" :step="100" style="width:100%" placeholder="请输入金额"></el-input-number>',
      '          </el-form-item>',
      '          <el-form-item label="支付方式">',
      '            <el-radio-group v-model="form.payType">',
      '              <el-radio v-for="p in payTypes" :key="p.v" :label="p.v">{{ p.l }}</el-radio>',
      '            </el-radio-group>',
      '          </el-form-item>',
      '          <el-form-item label="备注"><el-input v-model="form.remark" placeholder="选填"></el-input></el-form-item>',
      '          <el-form-item>',
      '            <el-button type="primary" :loading="submitting" :disabled="!visitId" @click="submit">确认{{ form.direction===1 ? "缴纳" : "退还" }}</el-button>',
      '          </el-form-item>',
      '        </el-form>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="15">',
      '      <div class="inp-card">',
      '        <div class="inp-section-title" style="margin-bottom:10px">预交金流水</div>',
      '        <el-table :data="flowPaged" v-loading="flowLoading" border size="small" height="420">',
      '          <el-table-column label="序号" width="55" align="center"><template #default="s">{{ flowSeq(s.$index) }}</template></el-table-column>',
      '          <el-table-column label="时间" width="150"><template #default="s">{{ fmtTime(s.row.createTime) }}</template></el-table-column>',
      '          <el-table-column label="方向" width="72" align="center"><template #default="s">',
      '            <el-tag :type="s.row.direction===1 ? \'success\' : \'danger\'" size="small">{{ directionLabel(s.row.direction) }}</el-tag>',
      '          </template></el-table-column>',
      '          <el-table-column label="金额" width="110" align="right"><template #default="s">',
      '            <span class="inp-money" :class="s.row.direction===1 ? \'inp-money-in\' : \'inp-money-out\'">{{ (s.row.direction===1 ? "+" : "-") + money(s.row.amount) }}</span>',
      '          </template></el-table-column>',
      '          <el-table-column label="支付方式" width="90" align="center"><template #default="s">{{ payTypeLabel(s.row.payType) }}</template></el-table-column>',
      '          <el-table-column label="操作后余额" width="110" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.balanceAfter) }}</span></template></el-table-column>',
      '          <el-table-column label="单号" width="150"><template #default="s">{{ orDash(s.row.receiptNo) }}</template></el-table-column>',
      '          <el-table-column label="备注" min-width="110"><template #default="s">{{ orDash(s.row.remark) }}</template></el-table-column>',
      '        </el-table>',
      '        <el-pagination style="margin-top:10px;justify-content:flex-end" background layout="total, sizes, prev, pager, next" :total="flowAll.length" :page-size="flowSize" :page-sizes="[10, 20, 50, 100]" :current-page="flowPage" @current-change="onFlowPage" @size-change="onFlowSize"></el-pagination>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      /* ---- 预警患者列表对话框 ---- */
      '  <el-dialog v-model="alertVisible" title="预交金不足预警患者" width="780px">',
      '    <el-table :data="alertPatients" border size="small" max-height="420">',
      '      <el-table-column prop="inp_no" label="住院号" width="150"></el-table-column>',
      '      <el-table-column prop="patient_name" label="姓名" width="100"></el-table-column>',
      '      <el-table-column label="科室" min-width="110"><template #default="s">{{ deptName(s.row.dept_id) }}</template></el-table-column>',
      '      <el-table-column label="当前余额" width="110" align="right"><template #default="s"><span class="inp-money inp-money-out">¥{{ money(s.row.deposit_balance) }}</span></template></el-table-column>',
      '      <el-table-column label="预警线" width="105" align="right"><template #default="s"><span class="inp-money">¥{{ money(s.row.deposit_warning_amount) }}</span></template></el-table-column>',
      '      <el-table-column label="缺口" width="105" align="right"><template #default="s"><span class="inp-money inp-money-out">¥{{ money(s.row.gap_amount) }}</span></template></el-table-column>',
      '      <el-table-column label="操作" width="80" align="center"><template #default="s">',
      '        <el-button link type="primary" size="small" @click="pickAlertPatient(s.row)">去缴费</el-button>',
      '      </template></el-table-column>',
      '    </el-table>',
      '    <div v-if="!alertPatients.length" style="color:var(--yb-ink-4);text-align:center;padding:16px 0">当前无预交金不足预警患者</div>',
      '    <template #footer>',
      '      <el-button @click="alertVisible=false">关闭</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        visits: [], visitId: null, balance: null,
        alertPatients: [], alertVisible: false,
        flowAll: [], flowLoading: false, flowPage: 1, flowSize: 20,
        form: { direction: 1, amount: null, payType: 1, remark: '' },
        payTypes: PAY_TYPES, submitting: false
      };
    },
    computed: {
      flowPaged: function () {
        var start = (this.flowPage - 1) * this.flowSize;
        return this.flowAll.slice(start, start + this.flowSize);
      }
    },
    methods: {
      money: money, orDash: orDash, fmtTime: fmtTime,
      payTypeLabel: payTypeLabel, directionLabel: directionLabel, visitLabel: visitLabelOf,
      flowSeq: function (i) { return (this.flowPage - 1) * this.flowSize + i + 1; },
      loadVisits: function () {
        var vm = this;
        fetchVisits().then(function (list) {
          vm.visits = list.filter(function (r) {
            return r.visit_status === 1 || r.visit_status === 2 || r.visit_status === 3;
          });
        }).catch(HIS.notifyError);
      },
      /* 预交金不足预警患者清单(在院且余额低于预警线, 缺口降序) */
      loadAlerts: function () {
        var vm = this;
        HIS.get('/api/his/inp/fee-alert/warning-patients').then(function (list) {
          vm.alertPatients = list || [];
        }).catch(function () { });
      },
      openAlertList: function () {
        this.alertVisible = true;
        this.loadAlerts();
      },
      /* 去缴费: 选中该患者并加载余额/流水 */
      pickAlertPatient: function (r) {
        this.visitId = HIS.id(r.visit_id);
        this.alertVisible = false;
        this.onPickVisit();
      },
      onPickVisit: function () {
        this.balance = null;
        this.flowAll = []; this.flowPage = 1;
        this.loadBalance();
        this.loadFlows();
      },
      loadBalance: function () {
        var vm = this;
        if (!vm.visitId) { return; }
        HIS.get('/api/his/inp/deposit/balance/' + HIS.idParam(vm.visitId)).then(function (b) {
          vm.balance = (b === null || b === undefined) ? 0 : Number(b);
        }).catch(HIS.notifyError);
      },
      loadFlows: function () {
        var vm = this;
        if (!vm.visitId) { return; }
        vm.flowLoading = true;
        HIS.get('/api/his/inp/deposit/list?inpVisitId=' + HIS.idParam(vm.visitId)).then(function (list) {
          vm.flowAll = list || [];
          vm.flowPage = 1;
        }).catch(HIS.notifyError).finally(function () { vm.flowLoading = false; });
      },
      onFlowPage: function (p) { this.flowPage = p; },
      onFlowSize: function (s) { this.flowSize = s; this.flowPage = 1; },
      submit: function () {
        var vm = this;
        if (!vm.visitId) { HIS.notifyError(new Error('请先选择患者')); return; }
        var amount = Number(vm.form.amount);
        if (!amount || amount <= 0) { HIS.notifyError(new Error('请输入正确的金额')); return; }
        if (vm.form.direction === 2 && vm.balance !== null && amount > vm.balance) {
          HIS.notifyError(new Error('退还金额不能超过当前余额 ¥' + money(vm.balance)));
          return;
        }
        vm.submitting = true;
        HIS.post('/api/his/inp/deposit', {
          inpVisitId: HIS.id(vm.visitId), amount: amount, payType: vm.form.payType,
          direction: vm.form.direction, remark: vm.form.remark || null
        }).then(function (res) {
          HIS.notifySuccess(vm.form.direction === 1 ? '预交金缴纳成功' : '预交金退还成功');
          if (res && res.balance !== null && res.balance !== undefined) { vm.balance = Number(res.balance); }
          vm.form.amount = null; vm.form.remark = '';
          vm.loadFlows();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      }
    },
    mounted: function () {
      this.loadRefs();
      this.loadVisits();
      this.loadAlerts();
    }
  };

  /* 数量去尾零(2.000 -> 2) */
  function qtyFmt(v) { var n = Number(v) || 0; return String(parseFloat(n.toFixed(3))); }

  /* ========================================================================
   * 5. InpChargeList 费用清单(明细分页 + 类别饼图 + 汇总 + 手动补录)
   * ====================================================================== */
  HIS.views.InpChargeList = {
    mixins: [listMixin, pageMixin],
    components: Object.assign({}, INP_ICONS),
    template: [
      '<div class="inp-charge-list">',
      '  <el-tabs v-model="activeTab" @tab-change="onTabChange">',
      '    <el-tab-pane label="费用明细" name="detail">',
      '  <div class="toolbar">',
      '    <el-select v-model="visitId" filterable placeholder="选择患者(住院号/姓名)" style="width:300px" @change="onPickVisit">',
      '      <el-option v-for="v in visits" :key="v.id" :label="visitLabel(v)" :value="v.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="onQuery"></el-date-picker>',
      '    <el-select v-model="feeType" placeholder="费用类别" clearable style="width:130px" @change="onQuery">',
      '      <el-option v-for="f in feeTypeOptions" :key="f.v" :label="f.l" :value="f.v"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="onQuery">查询</el-button>',
      '    <div style="flex:1"></div>',
      '    <el-button type="primary" plain :disabled="!visitId" @click="openAdd">手动补录费用</el-button>',
      '  </div>',
      '  <el-row :gutter="12">',
      '    <el-col :span="16">',
      '      <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="calc(100vh - 220px)">',
      '        <el-table-column label="序号" width="55" align="center"><template #default="s">{{ seqNo(s.$index) }}</template></el-table-column>',
      '        <el-table-column prop="chargeDate" label="日期" width="100"></el-table-column>',
      '        <el-table-column prop="itemName" label="项目名称" min-width="130"></el-table-column>',
      '        <el-table-column prop="itemCode" label="项目编码" width="100"></el-table-column>',
      '        <el-table-column label="数量" width="68" align="right"><template #default="s">{{ qtyFmt(s.row.quantity) }}</template></el-table-column>',
      '        <el-table-column label="单价" width="82" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.unitPrice) }}</span></template></el-table-column>',
      '        <el-table-column label="金额" width="92" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '        <el-table-column label="类别" width="80" align="center"><template #default="s">',
      '          <el-tag :type="feeTypeTag(s.row.feeType)" size="small">{{ feeTypeLabel(s.row.feeType) }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column label="状态" width="70" align="center"><template #default="s">',
      '          <el-tag :type="s.row.status===2 ? \'danger\' : \'success\'" size="small">{{ s.row.status===2 ? "退费" : "正常" }}</el-tag>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </el-col>',
      '    <el-col :span="8">',
      '      <div class="inp-card" style="margin-bottom:12px">',
      '        <div class="inp-section-title" style="margin-bottom:6px">费用类别占比</div>',
      '        <div v-show="summaryItems.length" ref="pieChart" style="height:230px"></div>',
      '        <div v-if="!summaryItems.length" style="height:230px;display:flex;align-items:center;justify-content:center;color:var(--yb-ink-4);font-size:13px">暂无费用数据</div>',
      '      </div>',
      '      <div class="inp-card">',
      '        <div class="inp-section-title" style="margin-bottom:6px">费用汇总</div>',
      '        <el-table :data="summaryItems" border size="small" show-summary :summary-method="summaryMethod">',
      '          <el-table-column prop="feeTypeName" label="类别"></el-table-column>',
      '          <el-table-column label="数量" width="66" align="right"><template #default="s">{{ qtyFmt(s.row.totalQuantity) }}</template></el-table-column>',
      '          <el-table-column label="金额(元)" width="96" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.totalAmount) }}</span></template></el-table-column>',
      '        </el-table>',
      '        <div style="margin-top:10px;text-align:right;color:var(--yb-ink-2);font-size:13px">总金额: <span class="inp-money" style="color:var(--yb-gold);font-size:18px;font-weight:700">¥{{ money(summaryObj.totalAmount) }}</span></div>',
      '      </div>',
      /* ---- Task#30 每日费用趋势(柱状图): 明细按 chargeDate 分组汇总 ---- */
      '      <div class="inp-card" style="margin-top:12px">',
      '        <div class="inp-section-title" style="margin-bottom:6px">每日费用趋势</div>',
      '        <div v-show="trendDates.length" v-loading="trendLoading" ref="trendChart" style="height:190px"></div>',
      '        <div v-if="!trendDates.length" style="height:190px;display:flex;align-items:center;justify-content:center;color:var(--yb-ink-4);font-size:13px">{{ visitId ? "暂无费用数据" : "请先选择患者" }}</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="待审核" name="alert">',
      '      <div class="toolbar" style="margin-bottom:10px">',
      '        <span style="color:var(--yb-ink-3);font-size:13px">费用预警复核(限额超支/预交金不足/大额费用)</span>',
      '        <el-select v-model="alertTypeFilter" placeholder="全部类型" clearable style="width:150px" @change="loadAlerts(1)">',
      '          <el-option v-for="t in alertTypeOptions" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '        </el-select>',
      '        <el-checkbox v-model="alertMineOnly" :disabled="!visitId" @change="loadAlerts(1)">仅看当前所选患者</el-checkbox>',
      '        <el-button :loading="alertLoading" @click="loadAlerts(1)">刷新</el-button>',
      '        <div style="flex:1"></div>',
      '        <span style="color:var(--yb-ink-3);font-size:12px">共 {{ alertTotal }} 条</span>',
      '      </div>',
      '      <el-table :data="alertRows" v-loading="alertLoading" border stripe size="small" max-height="calc(100vh - 210px)">',
      '        <el-table-column label="序号" width="55" align="center"><template #default="s">{{ (alertPage - 1) * alertSize + s.$index + 1 }}</template></el-table-column>',
      '        <el-table-column label="预警类型" width="110" align="center"><template #default="s">',
      '          <el-tag :type="alertTypeTag(s.row.alertType)" size="small">{{ alertTypeLabel(s.row.alertType) }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column label="患者" min-width="140"><template #default="s">{{ alertVisitName(s.row.inpVisitId) }}</template></el-table-column>',
      '        <el-table-column label="预警金额" width="105" align="right"><template #default="s"><span class="inp-money" style="color:var(--yb-danger);font-weight:600">¥{{ money(s.row.alertAmount) }}</span></template></el-table-column>',
      '        <el-table-column label="阈值" width="100" align="right"><template #default="s"><span class="inp-money">¥{{ money(s.row.thresholdAmount) }}</span></template></el-table-column>',
      '        <el-table-column label="触发时间" width="150"><template #default="s">{{ fmtTime(s.row.createTime || s.row.alertTime) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="84" align="center"><template #default="s">',
      '          <el-tag :type="alertStatusTag(s.row)" size="small">{{ alertStatusText(s.row) }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column label="处理信息" min-width="150"><template #default="s">',
      '          <span v-if="s.row.handleResult" style="font-size:12px;color:var(--yb-ink-2)">{{ fmtTime(s.row.handleTime) }}　{{ orDash(s.row.overrideReason) }}</span>',
      '          <span v-else>-</span>',
      '        </template></el-table-column>',
      '        <el-table-column label="操作" width="96" fixed="right"><template #default="s">',
      '          <template v-if="!s.row.handleResult">',
      '            <el-tooltip content="通过(放行)" placement="top" :show-after="300"><span class="inp-op is-ok" @click="handleAlert(s.row, 1)"><component :is="ic.YbiCheck" :size="16"></component></span></el-tooltip>',
      '            <el-tooltip content="拒绝(拦截)" placement="top" :show-after="300"><span class="inp-op is-danger" @click="handleAlert(s.row, 2)"><component :is="ic.YbiBan" :size="16"></component></span></el-tooltip>',
      '          </template>',
      '          <span v-else style="color:var(--yb-ink-4);font-size:12px">已处理</span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end" background layout="total, prev, pager, next" :total="alertTotal" :page-size="alertSize" :current-page="alertPage" @current-change="loadAlerts"></el-pagination>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="日清单" name="bill">',
      '      <div class="toolbar" style="margin-bottom:10px">',
      '        <span style="color:var(--yb-ink-3);font-size:13px">清单日期</span>',
      '        <el-date-picker v-model="billDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:150px" @change="loadBill"></el-date-picker>',
      '        <el-button type="primary" :loading="billLoading" @click="loadBill">查询日清单</el-button>',
      '        <el-button :loading="billGenLoading" :disabled="!visitId" @click="genBill">生成日清单</el-button>',
      '        <div style="flex:1"></div>',
      '        <el-button type="primary" plain :disabled="!billObj" @click="printBill">打印日清单</el-button>',
      '      </div>',
      '      <el-alert v-if="!visitId" type="info" :closable="false" title="请先在「费用明细」页签选择患者, 再查看日清单"></el-alert>',
      '      <el-alert v-else-if="!billObj" type="warning" :closable="false" :title="billDate + \' 的日清单尚未生成, 可点击「生成日清单」按当日已记账费用生成\'"></el-alert>',
      '      <template v-else>',
      '        <el-descriptions :column="4" border size="small" style="margin-bottom:12px">',
      '          <el-descriptions-item label="清单日期">{{ orDash(billObj.billDate) }}</el-descriptions-item>',
      '          <el-descriptions-item label="当日费用合计"><span class="inp-money">¥{{ money(billObj.totalAmount) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="累计费用(截至当日)"><span class="inp-money">¥{{ money(billObj.cumulativeAmount) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="预交金余额"><span class="inp-money">¥{{ money(billObj.depositBalance) }}</span></el-descriptions-item>',
      '        </el-descriptions>',
      '        <el-table :data="billItems" border stripe size="small" show-summary :summary-method="billSummaryMethod" max-height="calc(100vh - 330px)">',
      '          <el-table-column label="序号" width="55" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '          <el-table-column prop="itemName" label="项目名称" min-width="150"></el-table-column>',
      '          <el-table-column prop="itemCode" label="项目编码" width="110"></el-table-column>',
      '          <el-table-column label="类别" width="90" align="center"><template #default="s"><el-tag :type="feeTypeTag(s.row.feeType)" size="small">{{ feeTypeLabel(s.row.feeType) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="数量" width="80" align="right"><template #default="s">{{ qtyFmt(s.row.quantity) }}</template></el-table-column>',
      '          <el-table-column label="单价" width="90" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.unitPrice) }}</span></template></el-table-column>',
      '          <el-table-column label="金额" width="100" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '        </el-table>',
      '        <div style="margin-top:8px;color:var(--yb-ink-3);font-size:12px">共 {{ billItems.length }} 项　当日合计: <span class="inp-money" style="color:var(--yb-gold);font-weight:700">¥{{ money(billObj.totalAmount) }}</span>　{{ billObj.printedFlag===1 ? "已打印" : "未打印" }}</div>',
      '      </template>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      /* ---- 手动补录对话框 ---- */
      '  <el-dialog v-model="addVisible" title="手动补录费用" width="480px">',
      '    <el-form :model="addForm" label-width="82px">',
      '      <el-form-item label="项目名称" required><el-input v-model="addForm.itemName" placeholder="如: 一次性注射器"></el-input></el-form-item>',
      '      <el-form-item label="项目编码"><el-input v-model="addForm.itemCode" placeholder="选填"></el-input></el-form-item>',
      '      <el-form-item label="费用类别" required>',
      '        <el-select v-model="addForm.feeType" style="width:100%"><el-option v-for="f in feeTypeOptions" :key="f.v" :label="f.l" :value="f.v"></el-option></el-select>',
      '      </el-form-item>',
      '      <el-form-item label="单价(元)" required><el-input-number v-model="addForm.unitPrice" :min="0.01" :precision="2" style="width:100%"></el-input-number></el-form-item>',
      '      <el-form-item label="数量" required><el-input-number v-model="addForm.quantity" :min="0.01" :precision="2" :step="1" style="width:100%"></el-input-number></el-form-item>',
      '      <el-form-item label="记账日期" required><el-date-picker v-model="addForm.chargeDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item>',
      '      <div style="color:var(--yb-ink-3);font-size:12px;padding-left:82px">金额=单价×数量, 由服务端计算并累计到患者费用</div>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="addVisible=false">取消</el-button>',
      '      <el-button type="primary" :loading="addSaving" @click="submitAdd">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        visits: [], visitId: null, dateRange: null, feeType: null,
        trendDates: [], trendValues: [], trendLoading: false,
        rows: [], total: 0, page: 1, size: 20, loading: false,
        summaryObj: { items: [], totalAmount: 0 },
        /* 页签与扩展数据: 明细/待审核(费用预警)/日清单 */
        activeTab: 'detail',
        alertRows: [], alertTotal: 0, alertPage: 1, alertSize: 10, alertLoading: false,
        alertTypeFilter: null, alertMineOnly: true,
        alertTypeOptions: Object.keys(ALERT_TYPES).map(function (k) { return { v: Number(k), l: ALERT_TYPES[k] }; }),
        billDate: today(), billObj: null, billLoading: false, billGenLoading: false,
        feeTypeOptions: FEE_TYPE_OPTIONS,
        addVisible: false, addSaving: false,
        addForm: { itemName: '', itemCode: '', quantity: 1, unitPrice: null, feeType: 1, chargeDate: today() }
      };
    },
    computed: {
      ic: function () { return INP_ICONS; },
      summaryItems: function () { return (this.summaryObj && this.summaryObj.items) || []; },
      /* 日清单明细(items 后端为 JSON 字符串, 兼容已解析数组) */
      billItems: function () {
        var s = this.billObj && this.billObj.items;
        if (!s) { return []; }
        if (Object.prototype.toString.call(s) === '[object Array]') { return s; }
        try {
          var a = JSON.parse(s);
          return Object.prototype.toString.call(a) === '[object Array]' ? a : [];
        } catch (e) { return []; }
      }
    },
    methods: {
      money: money, qtyFmt: qtyFmt, orDash: orDash, fmtTime: fmtTime,
      feeTypeLabel: feeTypeLabel, feeTypeTag: feeTypeTag,
      alertTypeLabel: alertTypeLabel, alertTypeTag: alertTypeTag,
      visitLabel: visitLabelOf,
      loadVisits: function () {
        var vm = this;
        fetchVisits().then(function (list) { vm.visits = list; }).catch(HIS.notifyError);
      },
      onPickVisit: function () {
        this.onQuery();
        if (this.activeTab === 'alert') { this.loadAlerts(1); }
        if (this.activeTab === 'bill') { this.billObj = null; }
        this.loadTrend();
      },
      onQuery: function () { this.page = 1; this.loadCharges(); this.loadSummary(); },
      loadCharges: function () {
        var vm = this;
        if (!vm.visitId) { vm.rows = []; vm.total = 0; return; }
        vm.loading = true;
        var p = new URLSearchParams();
        p.append('inpVisitId', vm.visitId); p.append('page', vm.page); p.append('size', vm.size);
        if (vm.dateRange && vm.dateRange.length === 2) {
          p.append('startDate', vm.dateRange[0]); p.append('endDate', vm.dateRange[1]);
        }
        if (vm.feeType) { p.append('feeType', vm.feeType); }
        HIS.get('/api/his/inp/settle/charge/list?' + p.toString()).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadSummary: function () {
        var vm = this;
        if (!vm.visitId) { vm.summaryObj = { items: [], totalAmount: 0 }; vm.$nextTick(function () { vm.renderPie(); }); return; }
        HIS.get('/api/his/inp/settle/charge/summary/' + HIS.idParam(vm.visitId)).then(function (d) {
          vm.summaryObj = d || { items: [], totalAmount: 0 };
          vm.$nextTick(function () { vm.renderPie(); });
        }).catch(HIS.notifyError);
      },
      /* ECharts 饼图(canvas 不识别 CSS 变量, 一律用 'yb' 主题字面量色) */
      renderPie: function () {
        var el = this.$refs.pieChart;
        if (!el || !window.echarts) { return; }
        if (this._pie) { this._pie.dispose(); this._pie = null; }
        var items = this.summaryItems;
        if (!items.length) { return; }
        var chart = window.echarts.init(el, 'yb');
        this._pie = chart;
        chart.setOption({
          tooltip: HIS.theme.tooltip({ trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' }),
          legend: HIS.theme.legend({ bottom: 0, left: 'center', type: 'scroll' }),
          series: [{
            type: 'pie', radius: ['36%', '60%'], center: ['50%', '44%'],
            itemStyle: { borderColor: '#fff', borderWidth: 2 },
            label: { color: HIS.theme.ink3, fontSize: 11, formatter: '{b} {d}%' },
            data: items.map(function (it) { return { name: it.feeTypeName, value: Number(it.totalAmount) }; })
          }]
        });
      },
      /* ---- Task#30 每日费用趋势: 分页拉齐明细按 chargeDate 分组(退费按负计入) ---- */
      loadTrend: function () {
        var vm = this;
        if (!vm.visitId) {
          vm.trendDates = []; vm.trendValues = [];
          if (vm._trend) { vm._trend.dispose(); vm._trend = null; }
          return;
        }
        var vid = HIS.id(vm.visitId);
        vm.trendLoading = true;
        var sums = {};
        var page = 1;
        var size = 200;
        function fetchPage() {
          var p = new URLSearchParams();
          p.append('inpVisitId', vid); p.append('page', page); p.append('size', size);
          return HIS.get('/api/his/inp/settle/charge/list?' + p.toString()).then(function (d) {
            if (!HIS.sameId(vm.visitId, vid)) { return null; }
            var recs = (d && d.records) || [];
            recs.forEach(function (r) {
              var day = String(r.chargeDate || '').slice(0, 10);
              if (!day) { return; }
              var amt = Number(r.amount || 0);
              if (r.status === 2) { amt = -amt; }
              sums[day] = (sums[day] || 0) + amt;
            });
            if (recs.length >= size && page < 25) { page += 1; return fetchPage(); }
            return null;
          });
        }
        fetchPage().then(function () {
          if (!HIS.sameId(vm.visitId, vid)) { return; }
          var dates = Object.keys(sums).sort();
          vm.trendDates = dates;
          vm.trendValues = dates.map(function (d) { return Number(sums[d].toFixed(2)); });
          vm.$nextTick(function () { vm.renderTrend(); });
        }).catch(HIS.notifyError).finally(function () { if (HIS.sameId(vm.visitId, vid)) { vm.trendLoading = false; } });
      },
      /* ECharts 每日费用柱图(canvas 不识别 CSS 变量, 用 'yb' 主题字面量色) */
      renderTrend: function () {
        var el = this.$refs.trendChart;
        if (!el || !window.echarts) { return; }
        if (this._trend) { this._trend.dispose(); this._trend = null; }
        if (!this.trendDates.length) { return; }
        var chart = window.echarts.init(el, 'yb');
        this._trend = chart;
        chart.setOption({
          tooltip: HIS.theme.tooltip({
            trigger: 'axis', axisPointer: { type: 'shadow' },
            formatter: function (ps) {
              var p0 = (ps && ps[0]) || {};
              return (p0.name || '') + '<br/>当日费用: ¥' + money(p0.value || 0);
            }
          }),
          grid: { left: 12, right: 14, top: 18, bottom: 8, containLabel: true },
          xAxis: HIS.theme.catAxis({ type: 'category', data: this.trendDates, axisLabel: { fontSize: 11, formatter: function (v) { return String(v).slice(5); } } }),
          yAxis: HIS.theme.valAxis({ type: 'value' }),
          series: [{
            name: '当日费用', type: 'bar', barMaxWidth: 18,
            itemStyle: { color: HIS.theme.link, borderRadius: [3, 3, 0, 0] },
            data: this.trendValues
          }]
        });
        chart.resize();
      },
      onWinResize: function () { if (this._pie) { this._pie.resize(); } if (this._trend) { this._trend.resize(); } },
      summaryMethod: function (param) {
        var vm = this;
        return param.columns.map(function (c, i) {
          if (i === 0) { return '合计'; }
          if (i === param.columns.length - 1) { return '¥' + money(vm.summaryObj.totalAmount); }
          return '';
        });
      },
      openAdd: function () {
        this.addForm = { itemName: '', itemCode: '', quantity: 1, unitPrice: null, feeType: 1, chargeDate: today() };
        this.addVisible = true;
      },
      submitAdd: function () {
        var vm = this;
        if (!vm.addForm.itemName || !vm.addForm.itemName.trim()) { HIS.notifyError(new Error('请填写项目名称')); return; }
        var price = Number(vm.addForm.unitPrice);
        if (!price || price <= 0) { HIS.notifyError(new Error('请输入正确的单价')); return; }
        var qty = Number(vm.addForm.quantity);
        if (!qty || qty <= 0) { HIS.notifyError(new Error('请输入正确的数量')); return; }
        if (!vm.addForm.chargeDate) { HIS.notifyError(new Error('请选择记账日期')); return; }
        vm.addSaving = true;
        HIS.post('/api/his/inp/settle/charge', {
          inpVisitId: HIS.id(vm.visitId), itemName: vm.addForm.itemName.trim(), itemCode: vm.addForm.itemCode || null,
          quantity: qty, unitPrice: price, feeType: vm.addForm.feeType, chargeDate: vm.addForm.chargeDate
        }).then(function () {
          HIS.notifySuccess('费用补录成功');
          vm.addVisible = false;
          vm.loadCharges(); vm.loadSummary(); vm.loadTrend();
        }).catch(HIS.notifyError).finally(function () { vm.addSaving = false; });
      },
      /* ---- 页签切换: 待审核/日清单懒加载 ---- */
      onTabChange: function (name) {
        if (name === 'alert') { this.loadAlerts(1); }
        if (name === 'bill') { this.billObj = null; }
        if (name === 'detail' && this._trend) { var tr = this._trend; this.$nextTick(function () { tr.resize(); }); }
      },
      /* ---- 待审核(费用预警复核) ---- */
      alertVisitName: function (vid) {
        var hit = null;
        (this.visits || []).forEach(function (v) { if (HIS.sameId(v.id, vid)) { hit = v; } });
        return hit ? (hit.patient_name + ' · ' + hit.inp_no) : ('就诊#' + vid);
      },
      alertStatusText: function (r) { return r.handleResult === 1 ? '已放行' : (r.handleResult === 2 ? '已拦截' : '待处理'); },
      alertStatusTag: function (r) { return r.handleResult === 1 ? 'success' : (r.handleResult === 2 ? 'danger' : 'warning'); },
      loadAlerts: function (page) {
        var vm = this;
        if (page) { vm.alertPage = page; }
        vm.alertLoading = true;
        var p = new URLSearchParams();
        p.append('page', vm.alertPage); p.append('size', vm.alertSize);
        if (vm.alertMineOnly && vm.visitId) { p.append('visitId', vm.visitId); }
        if (vm.alertTypeFilter) { p.append('alertType', vm.alertTypeFilter); }
        HIS.get('/api/his/inp/fee-alert/list?' + p.toString()).then(function (d) {
          vm.alertRows = (d && d.records) || [];
          vm.alertTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.alertLoading = false; });
      },
      /* 复核预警: result 1=通过(放行) 2=拒绝(拦截, 必填原因) */
      handleAlert: function (row, result) {
        var vm = this;
        var done = function (reason) {
          return HIS.put('/api/his/inp/fee-alert/' + HIS.idParam(row.id) + '/handle', { result: result, reason: reason || null });
        };
        if (result === 2) {
          ElementPlus.ElMessageBox.prompt('请输入拦截原因(将写入预警处理记录)', '拦截预警 - ' + alertTypeLabel(row.alertType), {
            inputPlaceholder: '如: 超限额费用需科主任审批', confirmButtonText: '确认拦截', cancelButtonText: '取消',
            inputValidator: function (v) { return (v && v.trim()) ? true : '请填写拦截原因'; }
          }).then(function (r) { return done(r.value.trim()); }).then(function () {
            HIS.notifySuccess('已拦截该笔费用预警');
            vm.loadAlerts();
          }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
        } else {
          confirmBox('确认放行「' + escHtml(vm.alertVisitName(row.inpVisitId)) + '」的' + escHtml(alertTypeLabel(row.alertType)) + '预警(金额 ¥' + money(row.alertAmount) + ')?', '放行确认')
            .then(function () { return done('人工放行'); })
            .then(function () { HIS.notifySuccess('已放行'); vm.loadAlerts(); })
            .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
        }
      },
      /* ---- 日清单 ---- */
      loadBill: function () {
        var vm = this;
        if (!vm.visitId) { vm.billObj = null; return; }
        vm.billLoading = true;
        HIS.get('/api/his/inp/daily-bill?visitId=' + HIS.idParam(vm.visitId) + '&date=' + vm.billDate).then(function (d) {
          vm.billObj = d || null;
        }).catch(function (e) {
          vm.billObj = null;
          var m = String((e && e.message) || e || '');
          if (m.indexOf('未生成') < 0 && m.indexOf('不存在') < 0 && m.indexOf('404') < 0) { HIS.notifyError(e); }
        }).finally(function () { vm.billLoading = false; });
      },
      genBill: function () {
        var vm = this;
        if (!vm.visitId) { HIS.notifyError(new Error('请先选择患者')); return; }
        vm.billGenLoading = true;
        HIS.post('/api/his/inp/daily-bill/generate?visitId=' + HIS.idParam(vm.visitId) + '&date=' + vm.billDate).then(function (d) {
          vm.billObj = d || null;
          HIS.notifySuccess('日清单已生成');
        }).catch(HIS.notifyError).finally(function () { vm.billGenLoading = false; });
      },
      printBill: function () {
        var vm = this;
        if (!vm.visitId) { return; }
        HIS.get('/api/his/inp/print/render/daily-bill?visitId=' + HIS.idParam(vm.visitId) + '&date=' + vm.billDate).then(function (html) {
          printHtml(html, '住院日清单');
          /* 打印留痕(失败不影响打印) */
          if (vm.billObj && vm.billObj.id) {
            HIS.put('/api/his/inp/daily-bill/' + HIS.idParam(vm.billObj.id) + '/printed').catch(function () { });
          }
        }).catch(HIS.notifyError);
      },
      billSummaryMethod: function (param) {
        var vm = this;
        return param.columns.map(function (c, i) {
          if (i === 0) { return '合计'; }
          if (i === param.columns.length - 1) { return '¥' + money(vm.billObj ? vm.billObj.totalAmount : 0); }
          return '';
        });
      }
    },
    mounted: function () {
      this.loadVisits();
      window.addEventListener('resize', this.onWinResize);
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.onWinResize);
      if (this._pie) { this._pie.dispose(); this._pie = null; }
      if (this._trend) { this._trend.dispose(); this._trend = null; }
    }
  };

  /* ========================================================================
   * 6. InpSettle 出院结算(步骤条: 选择患者 -> 费用确认 -> 医保预结算 -> 正式结算)
   * ====================================================================== */
  HIS.views.InpSettle = {
    mixins: [refMixin],
    template: [
      '<div class="inp-settle">',
      '  <el-steps :active="stepActive" finish-status="success" align-center style="margin-bottom:20px">',
      '    <el-step title="选择患者" description="出院办理中"></el-step>',
      '    <el-step title="费用确认"></el-step>',
      '    <el-step title="医保预结算"></el-step>',
      '    <el-step title="正式结算"></el-step>',
      '  </el-steps>',
      /* ---- Step1 选择患者 ---- */
      '  <div v-if="step===1" class="inp-card" style="max-width:720px;margin:0 auto">',
      '    <div class="inp-section-title">选择待结算患者(在院 · 可中途结算 / 出院办理中 · 办出院结算)</div>',
      '    <el-select v-model="visitId" filterable placeholder="选择患者(住院号/姓名)" style="width:100%" @change="onPickVisit">',
      '      <el-option v-for="v in visits" :key="v.id" :label="visitLabel(v) + (v.visit_status === 2 ? \' 〔在院〕\' : \' 〔出院办理中〕\')" :value="v.id"></el-option>',
      '    </el-select>',
      '    <div v-if="!visits.length" style="color:var(--yb-ink-3);font-size:13px;margin-top:10px">暂无在院或出院办理中的患者</div>',
      '    <div v-if="pickedVisit" class="inp-info" style="margin-top:14px">',
      '      <b>{{ pickedVisit.patient_name }}</b>　{{ pickedVisit.gender_name || genderText(pickedVisit.gender) }}　{{ pickedVisit.age }}岁　住院号: {{ pickedVisit.inp_no }}　<el-tag :type="pickedVisit.visit_status === 2 ? \'success\' : \'warning\'" size="small">{{ pickedVisit.visit_status === 2 ? \'在院\' : \'出院办理中\' }}</el-tag><br>',
      '      病区: {{ pickedVisit.ward_name }} {{ pickedVisit.bed_no }}床　累计费用: ¥{{ money(pickedVisit.total_cost) }}　预交金余额: ¥{{ money(pickedVisit.deposit_balance) }}',
      '    </div>',
      '    <div v-if="pickedVisit" class="inp-card" style="margin-top:14px;background:var(--yb-surface)">',
      '      <div class="inp-section-title">结算历史</div>',
      '      <el-timeline v-if="historyList.length" style="padding-left:4px">',
      '        <el-timeline-item v-for="h in historyList" :key="h.id" :timestamp="fmtTime(h.settleTime)" placement="top" :type="historyItemType(h)">',
      '          <el-tag :type="historyItemType(h)" size="small">{{ settleTypeLabel(h.settleType) }}</el-tag>',
      '          <el-tag v-if="h.ybStatus === 4" type="info" size="small" style="margin-left:6px">已撤销</el-tag>',
      '          <span style="margin-left:8px">金额: <b class="inp-money">¥{{ money(h.totalAmount) }}</b></span>',
      '          <span style="margin-left:8px;color:var(--yb-ink-3)">单号: {{ orDash(h.settleNo) }}</span>',
      '        </el-timeline-item>',
      '      </el-timeline>',
      '      <div v-else style="color:var(--yb-ink-3);font-size:13px">暂无结算记录</div>',
      '    </div>',
      '    <div style="margin-top:16px;text-align:right">',
      '      <el-button type="primary" :disabled="!visitId" @click="goStep2">下一步: 费用确认</el-button>',
      '    </div>',
      '  </div>',
      /* ---- Step2 费用确认 ---- */
      '  <div v-if="step===2">',
      '    <div class="inp-card" style="margin-bottom:12px">',
      '      <div class="inp-section-title">费用汇总{{ pickedVisit ? " · " + pickedVisit.patient_name : "" }}</div>',
      '      <el-table :data="summaryItems" border size="small" show-summary :summary-method="summaryMethod">',
      '        <el-table-column prop="feeTypeName" label="费用类别"></el-table-column>',
      '        <el-table-column label="数量" width="100" align="right"><template #default="s">{{ qtyFmt(s.row.totalQuantity) }}</template></el-table-column>',
      '        <el-table-column label="金额(元)" width="140" align="right"><template #default="s"><span class="inp-money">{{ money(s.row.totalAmount) }}</span></template></el-table-column>',
      '      </el-table>',
      '      <el-row :gutter="12" style="margin-top:14px">',
      '        <el-col :span="8"><div class="inp-info">费用总额: <b class="inp-money">¥{{ money(summaryObj.totalAmount) }}</b></div></el-col>',
      '        <el-col :span="8"><div class="inp-info">预交金余额: <b class="inp-money">¥{{ money(pickedVisit ? pickedVisit.deposit_balance : 0) }}</b></div></el-col>',
      '        <el-col :span="8"><div class="inp-info">预计补缴: <b class="inp-money">¥{{ money(preNeedPay) }}</b><span style="color:var(--yb-ink-4);font-size:12px"> (以预结算为准)</span></div></el-col>',
      '      </el-row>',
      '    </div>',
      '    <div style="text-align:right">',
      '      <el-button @click="step=1">上一步</el-button>',
      '      <el-button type="primary" :loading="preLoading" @click="doPre">预结算</el-button>',
      '    </div>',
      '  </div>',
      /* ---- Step3 医保预结算 ---- */
      '  <div v-if="step===3">',
      '    <div class="inp-card" style="margin-bottom:12px">',
      '      <div class="inp-section-title">预结算结果</div>',
      '      <el-alert v-if="!preResult" type="info" :closable="false" title="尚未执行预结算"></el-alert>',
      '      <template v-else>',
      '        <div v-if="preResult.scopeStart" style="color:var(--yb-ink-3);font-size:12px;margin-bottom:8px">本次中途结算区间: {{ preResult.scopeStart }} ~ {{ preResult.scopeEnd }} (仅汇总该区间内未结算费用)</div>',
      '        <el-descriptions :column="3" border size="small" style="margin-bottom:12px">',
      '          <el-descriptions-item label="患者">{{ preResult.patientName }}</el-descriptions-item>',
      '          <el-descriptions-item label="住院号">{{ preResult.inpNo }}</el-descriptions-item>',
      '          <el-descriptions-item label="医保身份">{{ preResult.ybFlag ? "医保" : "自费" }}</el-descriptions-item>',
      '          <el-descriptions-item label="医疗费总额"><span class="inp-money">¥{{ money(preResult.totalAmount) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="预交金余额"><span class="inp-money">¥{{ money(preResult.depositBalance) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="需补缴金额"><span class="inp-money">¥{{ money(preResult.needPay) }}</span></el-descriptions-item>',
      '        </el-descriptions>',
      '        <template v-if="preResult.setlInfo">',
      '          <div class="inp-section-title" style="margin-top:4px">医保结算明细(2303试算)</div>',
      '          <el-descriptions :column="3" border size="small">',
      '            <el-descriptions-item label="基金支付"><span class="inp-money">¥{{ money(preResult.setlInfo.fundPaySumamt) }}</span></el-descriptions-item>',
      '            <el-descriptions-item label="个人负担"><span class="inp-money">¥{{ money(preResult.setlInfo.psnPartAmt) }}</span></el-descriptions-item>',
      '            <el-descriptions-item label="个账支付"><span class="inp-money">¥{{ money(preResult.setlInfo.acctPay) }}</span></el-descriptions-item>',
      '            <el-descriptions-item label="个人现金"><span class="inp-money">¥{{ money(preResult.setlInfo.psnCashPay) }}</span></el-descriptions-item>',
      '            <el-descriptions-item label="医疗费总额" :span="2"><span class="inp-money">¥{{ money(preResult.setlInfo.medfeeSumamt) }}</span></el-descriptions-item>',
      '          </el-descriptions>',
      '        </template>',
      '        <el-alert v-else type="warning" :closable="false" style="margin-top:10px" title="非医保患者(或医保信息不完整), 仅做院内试算"></el-alert>',
      '      </template>',
      '    </div>',
      '    <div style="text-align:right">',
      '      <el-button @click="step=2">上一步</el-button>',
      '      <el-button type="warning" :loading="preLoading" @click="doPre">重新预结算</el-button>',
      '      <el-button v-if="isMidVisit" type="warning" :loading="settleLoading" :disabled="!preResult" @click="doMidSettle">确认中途结算</el-button>',
      '      <el-button v-else type="primary" :loading="settleLoading" :disabled="!preResult" @click="doSettle">确认结算(出院)</el-button>',
      '    </div>',
      '  </div>',
      /* ---- Step4 结算单 ---- */
      '  <div v-if="step===4">',
      '    <div style="max-width:780px;margin:0 auto">',
      '      <div class="inp-slip">',
      '        <div class="inp-slip-title">住院结算单</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="结算单号">{{ orDash(settleResult.settle.settleNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="结算类型">{{ settleTypeLabel(settleResult.settle.settleType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="医疗费总额"><span class="inp-money">¥{{ money(settleResult.settle.totalAmount) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="基金支付"><span class="inp-money">¥{{ money(settleResult.settle.fundPay) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="个账支付"><span class="inp-money">¥{{ money(settleResult.settle.acctPay) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="个人负担"><span class="inp-money">¥{{ money(settleResult.settle.selfPay) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="预交金抵扣"><span class="inp-money">¥{{ money(settleResult.settle.depositDeduct) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="现金支付"><span class="inp-money">¥{{ money(settleResult.settle.cashPay) }}</span></el-descriptions-item>',
      '          <el-descriptions-item label="结算时间">{{ fmtTime(settleResult.settle.settleTime) }}</el-descriptions-item>',
      '          <el-descriptions-item label="医保结算状态">{{ settleResult.settle.ybStatus===2 ? "已结算" : "未结算/自费" }}</el-descriptions-item>',
      '          <el-descriptions-item label="DRG分组编码">{{ orDash(settleResult.settle.drgGroupCode) }}</el-descriptions-item>',
      '          <el-descriptions-item label="DIP编码">{{ orDash(settleResult.settle.dipCode) }}</el-descriptions-item>',
      '          <el-descriptions-item label="支付方式">{{ payMethodLabel(settleResult.settle.payMethod) }}</el-descriptions-item>',
      '          <el-descriptions-item v-if="settleResult.scopeStart" label="结算区间" :span="2">{{ settleResult.scopeStart }} ~ {{ settleResult.scopeEnd }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div style="margin-top:16px;text-align:center;font-size:15px">',
      '          <template v-if="Number(settleResult.refundAmount) > 0">',
      '            <span style="color:var(--yb-success);font-weight:700">应退患者: ¥{{ money(settleResult.refundAmount) }}</span>',
      '          </template>',
      '          <template v-else-if="Number(settleResult.cashPay) > 0">',
      '            <span style="color:var(--yb-danger);font-weight:700">应补收: ¥{{ money(settleResult.cashPay) }}</span>',
      '          </template>',
      '          <template v-else><span style="color:var(--yb-success);font-weight:700">预交金足额抵扣, 无需退补</span></template>',
      '        </div>',
      '        <div v-if="settleResult.settle.ybStatus===2" style="margin-top:8px;text-align:center;color:var(--yb-ink-3);font-size:12px">医保结算已完成(2304), 基金支付 ¥{{ money(settleResult.settle.fundPay) }} / 个账 ¥{{ money(settleResult.settle.acctPay) }}</div>',
      '      </div>',
      '      <div style="margin-top:16px;text-align:right">',
      '        <el-button @click="printSlip">打印结算单</el-button>',
      '        <el-button type="primary" @click="resetFlow">完成, 继续下一单</el-button>',
      '      </div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        step: 1, visits: [], visitId: null,
        summaryObj: { items: [], totalAmount: 0 },
        preResult: null, preLoading: false,
        settleResult: null, settleLoading: false,
        historyList: []
      };
    },
    computed: {
      stepActive: function () { return this.settleResult ? 4 : Math.min(this.step - 1, 3); },
      pickedVisit: function () {
        if (!this.visitId) { return null; }
        for (var i = 0; i < this.visits.length; i++) {
          if (HIS.sameId(this.visits[i].id, this.visitId)) { return this.visits[i]; }
        }
        return null;
      },
      summaryItems: function () { return (this.summaryObj && this.summaryObj.items) || []; },
      preNeedPay: function () {
        var total = Number(this.summaryObj.totalAmount || 0);
        var balance = Number(this.pickedVisit ? this.pickedVisit.deposit_balance : 0) || 0;
        return Math.max(0, total - balance);
      },
      /* 结算模式: 在院(2)可办中途结算; 出院办理中(3)办出院结算 */
      isMidVisit: function () { return !!(this.pickedVisit && this.pickedVisit.visit_status === 2); }
    },
    methods: {
      money: money, qtyFmt: qtyFmt, orDash: orDash, fmtTime: fmtTime,
      genderText: genderText, settleTypeLabel: settleTypeLabel, payMethodLabel: payMethodLabel, visitLabel: visitLabelOf,
      loadVisits: function () {
        var vm = this;
        fetchVisits().then(function (list) {
          /* 在院(2)可中途结算; 出院办理中(3)办出院结算 */
          vm.visits = list.filter(function (r) { return r.visit_status === 2 || r.visit_status === 3; });
        }).catch(HIS.notifyError);
      },
      onPickVisit: function () { this.preResult = null; this.settleResult = null; this.loadHistory(); },
      /* 结算历史: GET /settlements/{visitId} (含中途/出院/退费; 已撤销 ybStatus=4) */
      loadHistory: function () {
        var vm = this;
        if (!vm.visitId) { vm.historyList = []; return; }
        HIS.get('/api/his/inp/settle/settlements/' + HIS.idParam(vm.visitId)).then(function (list) {
          vm.historyList = list || [];
        }).catch(function () { vm.historyList = []; });
      },
      historyItemType: function (h) {
        if (h.ybStatus === 4) { return 'info'; }
        if (h.settleType === 2) { return 'warning'; }
        if (h.settleType === 3) { return 'danger'; }
        return 'success';
      },
      goStep2: function () {
        var vm = this;
        if (!vm.visitId) { HIS.notifyError(new Error('请先选择患者')); return; }
        vm.loadSummary();
        vm.step = 2;
      },
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/his/inp/settle/charge/summary/' + HIS.idParam(vm.visitId)).then(function (d) {
          vm.summaryObj = d || { items: [], totalAmount: 0 };
        }).catch(HIS.notifyError);
      },
      /* 预结算: POST /pre?visitId= (医保患者透传2303试算) */
      doPre: function () {
        var vm = this;
        vm.preLoading = true;
        HIS.post('/api/his/inp/settle/pre?visitId=' + HIS.idParam(vm.visitId)).then(function (d) {
          vm.preResult = d;
          vm.step = 3;
          HIS.notifySuccess('预结算完成');
        }).catch(HIS.notifyError).finally(function () { vm.preLoading = false; });
      },
      /* 正式结算: POST /settle {inpVisitId, settleType:1出院结算} */
      doSettle: function () {
        var vm = this;
        if (!vm.summaryObj || !Number(vm.summaryObj.totalAmount)) {
          HIS.notifyError(new Error('该患者暂无费用明细, 不能办理结算'));
          return;
        }
        confirmBox('确认对患者「' + escHtml(vm.pickedVisit ? vm.pickedVisit.patient_name : '') + '」执行出院结算?结算后就诊状态变为已出院并释放床位。', '正式结算').then(function () {
          vm.settleLoading = true;
          return HIS.post('/api/his/inp/settle', { inpVisitId: HIS.id(vm.visitId), settleType: 1 });
        }).then(function (res) {
          vm.settleResult = res;
          vm.settleLoading = false;
          vm.step = 4;
          HIS.notifySuccess('结算完成');
        }).catch(function (e) {
          vm.settleLoading = false;
          if (!isCancel(e)) { HIS.notifyError(e); }
        });
      },
      /* 中途结算: POST /settle {inpVisitId, settleType:2} (区间=上次结算次日~今日; 不改状态不释放床位) */
      doMidSettle: function () {
        var vm = this;
        if (!vm.summaryObj || !Number(vm.summaryObj.totalAmount)) {
          HIS.notifyError(new Error('该患者本次结算区间内暂无未结算费用'));
          return;
        }
        var range = (vm.preResult && vm.preResult.scopeStart)
          ? ('本次结算区间 ' + vm.preResult.scopeStart + ' ~ ' + vm.preResult.scopeEnd + '。') : '';
        confirmBox('确认对患者「' + escHtml(vm.pickedVisit ? vm.pickedVisit.patient_name : '') + '」执行中途结算?' + range
          + '结算后患者继续在院, 不释放床位; 区间内费用将锁定并完成医保结算。', '中途结算').then(function () {
          vm.settleLoading = true;
          return HIS.post('/api/his/inp/settle', { inpVisitId: HIS.id(vm.visitId), settleType: 2 });
        }).then(function (res) {
          vm.settleResult = res;
          vm.settleLoading = false;
          vm.step = 4;
          HIS.notifySuccess('中途结算完成');
          vm.loadHistory();
        }).catch(function (e) {
          vm.settleLoading = false;
          if (!isCancel(e)) { HIS.notifyError(e); }
        });
      },
      /* 打印结算单: 打印API渲染HTML → 隐藏iframe → window.print() */
      printSlip: function () {
        var vm = this;
        var sid = vm.settleResult && vm.settleResult.settle ? vm.settleResult.settle.id : null;
        if (!sid) { HIS.notifyError(new Error('结算单ID缺失, 无法打印')); return; }
        HIS.get('/api/his/inp/print/render/settlement?settleId=' + HIS.idParam(sid)).then(function (html) {
          printHtml(html, '住院结算单');
        }).catch(HIS.notifyError);
      },
      resetFlow: function () {
        this.step = 1; this.visitId = null;
        this.summaryObj = { items: [], totalAmount: 0 };
        this.preResult = null; this.settleResult = null;
        this.historyList = [];
        this.loadVisits();
      },
      summaryMethod: function (param) {
        var vm = this;
        return param.columns.map(function (c, i) {
          if (i === 0) { return '合计'; }
          if (i === param.columns.length - 1) { return '¥' + money(vm.summaryObj.totalAmount); }
          return '';
        });
      }
    },
    mounted: function () { this.loadVisits(); }
  };

  /* ========================================================================
   * 7. InpDailySummary 住院日报(日期 + 四项统计卡 + 结算/预交金汇总 + 构成柱图)
   * ====================================================================== */
  HIS.views.InpDailySummary = {
    template: [
      '<div class="inp-daily">',
      '  <div class="toolbar">',
      '    <span style="color:var(--yb-ink-3);font-size:13px">统计日期</span>',
      '    <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:160px" @change="load"></el-date-picker>',
      '    <el-button type="primary" :loading="loading" @click="load">查询</el-button>',
      '  </div>',
      '  <el-row :gutter="12" style="margin-bottom:14px">',
      '    <el-col :span="6"><div class="inp-stat-card"><div class="inp-stat-flex"><div class="inp-stat-num">{{ daily.visit.admitCount || 0 }}</div><div class="inp-spark" ref="sparkAdmit"></div></div><div class="inp-stat-label">今日入院数</div><div class="inp-delta" :class="deltaCls(\'admit\')">{{ deltaText(\'admit\') }}</div></div></el-col>',
      '    <el-col :span="6"><div class="inp-stat-card"><div class="inp-stat-flex"><div class="inp-stat-num">{{ daily.visit.dischargeCount || 0 }}</div><div class="inp-spark" ref="sparkDischarge"></div></div><div class="inp-stat-label">今日出院数</div><div class="inp-delta" :class="deltaCls(\'discharge\')">{{ deltaText(\'discharge\') }}</div></div></el-col>',
      '    <el-col :span="6"><div class="inp-stat-card"><div class="inp-stat-flex"><div class="inp-stat-num">{{ daily.visit.inHospitalCount || 0 }}</div><div class="inp-spark" ref="sparkInHospital"></div></div><div class="inp-stat-label">当前在院数</div><div class="inp-delta" :class="deltaCls(\'inHospital\')">{{ deltaText(\'inHospital\') }}</div></div></el-col>',
      '    <el-col :span="6"><div class="inp-stat-card"><div class="inp-stat-flex"><div class="inp-stat-num gold">¥{{ money(daily.settle.totalAmount) }}</div><div class="inp-spark" ref="sparkFee"></div></div><div class="inp-stat-label">今日结算费用合计</div><div class="inp-delta" :class="deltaCls(\'fee\')">{{ deltaText(\'fee\') }}</div></div></el-col>',
      '  </el-row>',
      '  <el-row :gutter="12">',
      '    <el-col :span="12">',
      '      <div class="inp-card" style="margin-bottom:12px">',
      '        <div class="inp-section-title">结算汇总</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="结算笔数">{{ daily.settle.count || 0 }}</el-descriptions-item>',
      '          <el-descriptions-item label="费用总额">¥{{ money(daily.settle.totalAmount) }}</el-descriptions-item>',
      '          <el-descriptions-item label="个人负担">¥{{ money(daily.settle.selfPay) }}</el-descriptions-item>',
      '          <el-descriptions-item label="基金支付">¥{{ money(daily.settle.fundPay) }}</el-descriptions-item>',
      '          <el-descriptions-item label="个账支付">¥{{ money(daily.settle.acctPay) }}</el-descriptions-item>',
      '          <el-descriptions-item label="现金支付">¥{{ money(daily.settle.cashPay) }}</el-descriptions-item>',
      '          <el-descriptions-item label="预交金抵扣">¥{{ money(daily.settle.depositDeduct) }}</el-descriptions-item>',
      '          <el-descriptions-item label="退款金额">¥{{ money(daily.settle.refundAmount) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '      </div>',
      '      <div class="inp-card">',
      '        <div class="inp-section-title">预交金收退汇总</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="缴纳笔数">{{ daily.deposit.payCount || 0 }}</el-descriptions-item>',
      '          <el-descriptions-item label="缴纳金额">¥{{ money(daily.deposit.payAmount) }}</el-descriptions-item>',
      '          <el-descriptions-item label="退还笔数">{{ daily.deposit.refundCount || 0 }}</el-descriptions-item>',
      '          <el-descriptions-item label="退还金额">¥{{ money(daily.deposit.refundAmount) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <div class="inp-card" style="height:100%">',
      '        <div class="inp-section-title">当日结算金额构成</div>',
      '        <div ref="barChart" style="height:320px"></div>',
      '        <div style="color:var(--yb-ink-3);font-size:12px;margin-top:6px">后续可扩展: 在院趋势/床位使用率/科室收入分布等图表</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        date: today(), loading: false,
        /* Task#30: sparkline 近7日趋势(无历史API, 先用固定 mock 展示UI效果; delta 与末两位自洽) */
        sparkData: {
          admit: [12, 15, 13, 18, 20, 16, 19],
          discharge: [9, 11, 10, 12, 10, 8, 6],
          inHospital: [25, 26, 27, 26, 28, 27, 28],
          fee: [8200, 9100, 8600, 9800, 10500, 9600, 10200]
        },
        daily: {
          settle: { count: 0, totalAmount: 0, selfPay: 0, fundPay: 0, cashPay: 0, acctPay: 0, depositDeduct: 0, refundAmount: 0 },
          visit: { admitCount: 0, dischargeCount: 0, inHospitalCount: 0 },
          deposit: { payCount: 0, payAmount: 0, refundCount: 0, refundAmount: 0 }
        }
      };
    },
    methods: {
      money: money,
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/his/inp/settle/daily?date=' + vm.date).then(function (d) {
          if (d) {
            vm.daily = { settle: d.settle || {}, visit: d.visit || {}, deposit: d.deposit || {} };
          }
          vm.$nextTick(function () { vm.renderBar(); vm.renderSparks(); });
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* ECharts 柱图(结算金额构成; 'yb' 主题保证中性标尺) */
      renderBar: function () {
        var el = this.$refs.barChart;
        if (!el || !window.echarts) { return; }
        if (this._bar) { this._bar.dispose(); this._bar = null; }
        var s = this.daily.settle || {};
        var chart = window.echarts.init(el, 'yb');
        this._bar = chart;
        chart.setOption({
          tooltip: HIS.theme.tooltip({ trigger: 'axis', axisPointer: { type: 'shadow' } }),
          grid: { left: 12, right: 16, top: 30, bottom: 8, containLabel: true },
          xAxis: HIS.theme.catAxis({ type: 'category', data: ['个人负担', '基金支付', '个账支付', '现金支付', '预交金抵扣', '退款'] }),
          yAxis: HIS.theme.valAxis({ type: 'value' }),
          series: [{
            type: 'bar', barWidth: 32,
            itemStyle: { borderRadius: [4, 4, 0, 0] },
            data: [
              Number(s.selfPay || 0), Number(s.fundPay || 0), Number(s.acctPay || 0),
              Number(s.cashPay || 0), Number(s.depositDeduct || 0), Number(s.refundAmount || 0)
            ]
          }]
        });
        chart.resize();
      },
      /* ---- Task#30 sparkline 微折线 + 环比 ---- */
      renderSparks: function () {
        var vm = this;
        if (!window.echarts) { return; }
        var refMap = { admit: 'sparkAdmit', discharge: 'sparkDischarge', inHospital: 'sparkInHospital', fee: 'sparkFee' };
        this._sparks = this._sparks || {};
        SPARK_DEFS.forEach(function (def) {
          var el = vm.$refs[refMap[def.key]];
          if (!el) { return; }
          if (vm._sparks[def.key]) { vm._sparks[def.key].dispose(); vm._sparks[def.key] = null; }
          var data = (vm.sparkData || {})[def.key] || [];
          var chart = window.echarts.init(el, 'yb');
          vm._sparks[def.key] = chart;
          chart.setOption({
            grid: { left: 1, right: 1, top: 3, bottom: 1 },
            tooltip: { show: false },
            xAxis: { type: 'category', show: false, boundaryGap: false, data: data.map(function (d, i) { return i; }) },
            yAxis: { type: 'value', show: false, min: function (v) { return v.min - (v.max - v.min) * 0.3; } },
            series: [{
              type: 'line', data: data, smooth: true, symbol: 'none',
              lineStyle: { width: 1.6, color: def.color },
              areaStyle: { color: def.area }
            }]
          });
        });
      },
      deltaOf: function (key) {
        var arr = (this.sparkData || {})[key] || [];
        if (arr.length < 2) { return null; }
        return Number(arr[arr.length - 1]) - Number(arr[arr.length - 2]);
      },
      deltaCls: function (key) {
        var d = this.deltaOf(key);
        if (d === null || d === 0) { return 'flat'; }
        return d > 0 ? 'up' : 'down';
      },
      deltaText: function (key) {
        var d = this.deltaOf(key);
        if (d === null) { return '较昨日 -'; }
        if (d === 0) { return '较昨日 持平'; }
        var v = Math.abs(d);
        var txt = key === 'fee' ? ('¥' + money(v)) : v;
        return '较昨日 ' + (d > 0 ? '+' : '-') + txt + ' ' + (d > 0 ? '↑' : '↓');
      },
      onWinResize: function () {
        if (this._bar) { this._bar.resize(); }
        var sp = this._sparks || {};
        Object.keys(sp).forEach(function (k) { if (sp[k]) { sp[k].resize(); } });
      }
    },
    mounted: function () {
      this.load();
      window.addEventListener('resize', this.onWinResize);
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.onWinResize);
      if (this._bar) { this._bar.dispose(); this._bar = null; }
      var sp = this._sparks || {};
      Object.keys(sp).forEach(function (k) { if (sp[k]) { sp[k].dispose(); } });
      this._sparks = {};
    }
  };

  /* ========================================================================
   * P4 住院发药增强: InpDispenseWork 住院发药工作台 / InpDischargePickup 出院带药取药核发 / InpDispenseHistory 历史发药查询
   * ====================================================================== */
  HIS.views.InpDispenseWork = {
    template: [
      '<div class="inp-dispense">',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" clearable placeholder="住院药房(默认全院FIFO)" style="width:210px" @change="loadQueue">',
      '      <el-option v-for="p in pharmDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="药品/患者/医嘱" style="width:200px" clearable @keyup.enter="search"></el-input>',
      '    <el-button @click="search">查询</el-button>',
      '    <el-button @click="loadQueue">刷新</el-button>',
      '    <el-tag v-if="autoDispense" type="success" size="small">自动发药已开启</el-tag>',
      '    <el-button type="primary" :disabled="selection.length===0" @click="openBatch">集中发药({{ selection.length }})</el-button>',
      '  </div>',
      '  <el-table :data="rows" v-loading="loading" border size="small" @selection-change="onSel">',
      '    <el-table-column type="selection" width="42"></el-table-column>',
      '    <el-table-column label="患者" width="90"><template #default="s">{{ orDash(s.row.patientName) }}</template></el-table-column>',
      '    <el-table-column label="住院号" width="130"><template #default="s">{{ orDash(s.row.inpNo) }}</template></el-table-column>',
      '    <el-table-column label="病区/床" width="120"><template #default="s">{{ orDash(s.row.wardName) }} {{ orDash(s.row.bedNo) }}</template></el-table-column>',
      '    <el-table-column label="药品/规格" min-width="200"><template #default="s">{{ orDash(s.row.drugName) }} / {{ orDash(s.row.drugSpec || s.row.orderSpec) }}</template></el-table-column>',
      '    <el-table-column label="频次/用法" width="110"><template #default="s">{{ orDash(s.row.freqCode) }} {{ orDash(s.row.usageCode) }}</template></el-table-column>',
      '    <el-table-column label="应发" width="85" align="right"><template #default="s">{{ fmtQty(s.row.shouldQty) }}{{ orDash(s.row.unit) }}</template></el-table-column>',
      '    <el-table-column label="包装比" width="70" align="center"><template #default="s">{{ s.row.packRatio || 1 }}</template></el-table-column>',
      '    <el-table-column label="全院库存" width="90" align="right"><template #default="s"><span :style="{color: insufficient(s.row) ? \'var(--yb-danger)\' : \'var(--yb-ink-1)\'}">{{ fmtQty(s.row.availStock) }}</span></template></el-table-column>',
      '    <el-table-column label="可冲抵" width="80" align="right"><template #default="s">{{ fmtQty(s.row.stagingAvail) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="90" align="center"><template #default="s"><el-button link type="primary" size="small" @click="openDispense(s.row)">发药</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:10px;justify-content:flex-end" background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '  <el-dialog v-model="dlg.visible" title="住院发药" width="640px">',
      '    <div v-if="dlg.order">',
      '      <div style="margin-bottom:8px">{{ orDash(dlg.order.patientName) }} · {{ orDash(dlg.order.drugName) }} · 应发 {{ fmtQty(dlg.order.shouldQty) }}{{ orDash(dlg.order.unit) }}</div>',
      '      <el-descriptions v-if="dlg.pv" :column="2" border size="small">',
      '        <el-descriptions-item label="应发量">{{ fmtQty(dlg.pv.shouldQty) }}</el-descriptions-item>',
      '        <el-descriptions-item label="冲抵量">{{ fmtQty(dlg.pv.offsetQty) }}</el-descriptions-item>',
      '        <el-descriptions-item label="实发量">{{ fmtQty(dlg.pv.actualQty) }}</el-descriptions-item>',
      '        <el-descriptions-item label="多发(取整)">{{ fmtQty(dlg.pv.overQty) }}</el-descriptions-item>',
      '        <el-descriptions-item label="整包装">×{{ dlg.pv.packRatio }} 共 {{ dlg.pv.packQty }} 包</el-descriptions-item>',
      '        <el-descriptions-item label="全院库存">{{ fmtQty(dlg.pv.availStock) }} <el-tag v-if="!dlg.pv.sufficient" type="danger" size="small">缺药</el-tag></el-descriptions-item>',
      '      </el-descriptions>',
      '      <div v-if="dlg.pv?.sufficient === false" style="margin-top:12px">',
      '        <div class="inp-section-title">缺药替换候选(同本位码/规格, 异厂家)</div>',
      '        <el-select v-model="dlg.replaceCatalogId" clearable placeholder="选择替换药品" style="width:100%">',
      '          <el-option v-for="c in dlg.candidates" :key="c.drugCatalogId" :label="c.drugName + \' /\' + orDash(c.manufacturer) + \' 存\' + fmtQty(c.availStock)" :value="c.drugCatalogId"></el-option>',
      '        </el-select>',
      '        <el-radio-group v-if="dlg.replaceCatalogId" v-model="dlg.replaceScope" style="margin-top:6px">',
      '          <el-radio :label="1">仅本次</el-radio><el-radio :label="2">本次及后续全部</el-radio>',
      '        </el-radio-group>',
      '      </div>',
      '      <el-form label-width="80px" style="margin-top:12px">',
      '        <el-form-item label="出院带药"><el-switch v-model="dlg.dischargePick"></el-switch></el-form-item>',
      '        <el-form-item label="核对人"><el-input v-model="dlg.checkBy" placeholder="选填(双签)"></el-input></el-form-item>',
      '        <el-form-item label="备注"><el-input v-model="dlg.remark"></el-input></el-form-item>',
      '      </el-form>',
      '    </div>',
      '    <template #footer><el-button @click="dlg.visible=false">取消</el-button><el-button type="primary" :loading="dlg.submitting" :disabled="!dlg.pv" @click="submitDispense">确认发药</el-button></template>',
      '  </el-dialog>',
      '  <el-dialog v-model="batch.visible" title="集中发药" width="520px">',
      '    <div>已选 {{ batch.list.length }} 条医嘱, 将按整包装取整并扣减库存发药。</div>',
      '    <el-form label-width="80px" style="margin-top:10px">',
      '      <el-form-item label="出院带药"><el-switch v-model="batch.dischargePick"></el-switch></el-form-item>',
      '      <el-form-item label="核对人"><el-input v-model="batch.checkBy"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="batch.visible=false">取消</el-button><el-button type="primary" :loading="batch.submitting" @click="submitBatch">确认发药</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        pharmDefs: [], pharmacyId: null, keyword: '', rows: [], total: 0, page: 1, size: 20,
        loading: false, selection: [], autoDispense: false,
        dlg: { visible: false, order: null, pv: null, candidates: [], replaceCatalogId: null, replaceScope: 1, dischargePick: false, checkBy: '', remark: '', submitting: false },
        batch: { visible: false, list: [], dischargePick: false, checkBy: '', submitting: false }
      };
    },
    methods: {
      orDash: orDash,
      fmtQty: function (v) { return qtyFmt(v); },
      insufficient: function (r) { return Number(r.availStock) < Number(r.shouldQty); },
      loadPharm: function () {
        var vm = this;
        HIS.get('/api/his/pharmacy/pharmacy-def').then(function (l) { vm.pharmDefs = l || []; }).catch(function () { });
      },
      search: function () { this.page = 1; this.loadQueue(); },
      onPage: function (p) { this.page = p; this.loadQueue(); },
      loadQueue: function () {
        var vm = this; vm.loading = true;
        var url = '/api/his/inp/dispense/queue?page=' + vm.page + '&size=' + vm.size
          + (vm.pharmacyId ? ('&pharmacyId=' + HIS.idParam(vm.pharmacyId)) : '')
          + (vm.keyword ? ('&keyword=' + encodeURIComponent(vm.keyword)) : '');
        HIS.get(url).then(function (d) {
          vm.rows = (d && d.rows) || []; vm.total = Number(d && d.total) || 0; vm.autoDispense = !!(d && d.autoDispense);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onSel: function (s) { this.selection = s || []; },
      openDispense: function (row) {
        var vm = this;
        vm.dlg.order = row; vm.dlg.pv = null; vm.dlg.candidates = []; vm.dlg.replaceCatalogId = null; vm.dlg.replaceScope = 1;
        vm.dlg.dischargePick = false; vm.dlg.checkBy = ''; vm.dlg.remark = ''; vm.dlg.visible = true;
        var u = '/api/his/inp/dispense/preview?orderId=' + HIS.idParam(row.orderId) + (vm.pharmacyId ? ('&pharmacyId=' + HIS.idParam(vm.pharmacyId)) : '');
        HIS.get(u).then(function (pv) { vm.dlg.pv = pv; if (pv && !pv.sufficient) { vm.loadCandidates(row); } }).catch(HIS.notifyError);
      },
      loadCandidates: function (row) {
        var vm = this;
        var u = '/api/his/inp/dispense/replace-candidates?orderId=' + HIS.idParam(row.orderId) + (vm.pharmacyId ? ('&pharmacyId=' + HIS.idParam(vm.pharmacyId)) : '');
        HIS.get(u).then(function (c) { vm.dlg.candidates = c || []; }).catch(function () { });
      },
      submitDispense: function () {
        var vm = this; var o = vm.dlg.order; if (!o) { return; }
        vm.dlg.submitting = true;
        var item = { orderId: HIS.id(o.orderId) };
        if (vm.dlg.replaceCatalogId) { item.replaceDrugCatalogId = HIS.id(vm.dlg.replaceCatalogId); item.replaceScope = vm.dlg.replaceScope; item.replaceReason = '缺药替换'; }
        HIS.post('/api/his/inp/dispense/dispense', {
          pharmacyId: vm.pharmacyId ? HIS.id(vm.pharmacyId) : null, dischargePick: vm.dlg.dischargePick,
          checkBy: vm.dlg.checkBy || null, remark: vm.dlg.remark || null, items: [item]
        }).then(function () { HIS.notifySuccess('发药成功'); vm.dlg.visible = false; vm.loadQueue(); }).catch(HIS.notifyError).finally(function () { vm.dlg.submitting = false; });
      },
      openBatch: function () { this.batch.list = this.selection.slice(); this.batch.visible = true; },
      submitBatch: function () {
        var vm = this; vm.batch.submitting = true;
        var items = vm.batch.list.map(function (r) { return { orderId: HIS.id(r.orderId) }; });
        HIS.post('/api/his/inp/dispense/dispense', {
          pharmacyId: vm.pharmacyId ? HIS.id(vm.pharmacyId) : null, dischargePick: vm.batch.dischargePick,
          checkBy: vm.batch.checkBy || null, items: items
        }).then(function () { HIS.notifySuccess('集中发药成功'); vm.batch.visible = false; vm.selection = []; vm.loadQueue(); }).catch(HIS.notifyError).finally(function () { vm.batch.submitting = false; });
      }
    },
    mounted: function () { this.loadPharm(); this.loadQueue(); }
  };

  HIS.views.InpDischargePickup = {
    template: [
      '<div class="inp-pickup">',
      '  <div class="toolbar">',
      '    <el-select v-model="status" clearable placeholder="状态(全部)" style="width:170px" @change="load">',
      '      <el-option label="待取药" :value="1"></el-option><el-option label="已取待发药核" :value="2"></el-option><el-option label="已二次核发" :value="3"></el-option>',
      '    </el-select>',
      '    <el-input v-model="kw" placeholder="住院号/患者" style="width:180px" clearable @keyup.enter="load"></el-input>',
      '    <el-button @click="load">查询</el-button><el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="rows" v-loading="loading" border size="small">',
      '    <el-table-column label="患者" width="100"><template #default="s">{{ orDash(s.row.patientName) }}</template></el-table-column>',
      '    <el-table-column label="就诊ID" width="160"><template #default="s">{{ s.row.inpVisitId }}</template></el-table-column>',
      '    <el-table-column label="药品/规格" min-width="180"><template #default="s">{{ orDash(s.row.drugName) }} / {{ orDash(s.row.spec) }}</template></el-table-column>',
      '    <el-table-column label="数量" width="80" align="right"><template #default="s">{{ fmtQty(s.row.qty) }}</template></el-table-column>',
      '    <el-table-column label="发票号" width="160"><template #default="s">{{ orDash(s.row.invoiceNo) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="120" align="center"><template #default="s"><el-tag :type="stType(s.row.pickupStatus)" size="small">{{ stText(s.row.pickupStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="取药/核发" min-width="150"><template #default="s">{{ orDash(s.row.pickupBy) }} / {{ orDash(s.row.verify2By) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="150" align="center"><template #default="s">',
      '      <el-button v-if="s.row.pickupStatus===1" link type="primary" size="small" @click="openPickup(s.row)">取药</el-button>',
      '      <el-button v-if="s.row.pickupStatus===2" link type="warning" size="small" @click="verify2(s.row)">二次核发</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg.visible" title="出院带药取药" width="420px">',
      '    <el-form label-width="90px"><el-form-item label="发票号"><el-input v-model="dlg.invoiceNo" placeholder="留空自动生成"></el-input></el-form-item></el-form>',
      '    <template #footer><el-button @click="dlg.visible=false">取消</el-button><el-button type="primary" :loading="dlg.submitting" @click="submitPickup">确认取药</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () { return { rows: [], loading: false, status: null, kw: '', dlg: { visible: false, row: null, invoiceNo: '', submitting: false } }; },
    methods: {
      orDash: orDash, fmtQty: function (v) { return qtyFmt(v); },
      stText: function (s) { return s === 1 ? '待取药' : s === 2 ? '已取待发药核' : s === 3 ? '已二次核发' : ''; },
      stType: function (s) { return s === 1 ? 'info' : s === 2 ? 'warning' : 'success'; },
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/his/inp/dispense/pickup' + (vm.status ? ('?status=' + vm.status) : '');
        HIS.get(url).then(function (l) {
          var rows = l || [];
          if (vm.kw) { var k = String(vm.kw).trim(); rows = rows.filter(function (r) { return String(r.inpVisitId).indexOf(k) >= 0 || String(r.patientName || '').indexOf(k) >= 0; }); }
          vm.rows = rows;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      openPickup: function (row) { this.dlg.row = row; this.dlg.invoiceNo = ''; this.dlg.visible = true; },
      submitPickup: function () {
        var vm = this; vm.dlg.submitting = true;
        HIS.post('/api/his/inp/dispense/pickup/confirm', { pickupId: HIS.id(vm.dlg.row.id), invoiceNo: vm.dlg.invoiceNo || null }).then(function () { HIS.notifySuccess('取药成功'); vm.dlg.visible = false; vm.load(); }).catch(HIS.notifyError).finally(function () { vm.dlg.submitting = false; });
      },
      verify2: function (row) {
        var vm = this;
        HIS.post('/api/his/inp/dispense/pickup/verify2', { pickupId: HIS.id(row.id) }).then(function () { HIS.notifySuccess('二次核发成功'); vm.load(); }).catch(HIS.notifyError);
      }
    },
    mounted: function () { this.load(); }
  };

  HIS.views.InpDispenseHistory = {
    template: [
      '<div class="inp-dhistory">',
      '  <div class="toolbar">',
      '    <el-radio-group v-model="dimension" @change="search"><el-radio-button label="dispense">发药</el-radio-button><el-radio-button label="return">退药</el-radio-button></el-radio-group>',
      '    <el-input v-model="keyword" placeholder="单号/患者/药品/病区" style="width:200px" clearable @keyup.enter="search"></el-input>',
      '    <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始" style="width:150px"></el-date-picker>',
      '    <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束" style="width:150px"></el-date-picker>',
      '    <el-button @click="search">查询</el-button>',
      '  </div>',
      '  <el-table :data="rows" v-loading="loading" border size="small">',
      '    <el-table-column v-if="dimension===\'dispense\'" label="发药单号" width="140"><template #default="s">{{ orDash(s.row.dispenseNo) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'return\'" label="退药单号" width="150"><template #default="s">{{ orDash(s.row.returnNo) }}</template></el-table-column>',
      '    <el-table-column label="患者" width="90"><template #default="s">{{ orDash(s.row.patientName) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'dispense\'" label="病区" width="100"><template #default="s">{{ orDash(s.row.wardName) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'dispense\'" label="药品" min-width="160"><template #default="s">{{ orDash(s.row.drugName) }} / {{ orDash(s.row.spec) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'dispense\'" label="应/冲/实/多" width="160" align="right"><template #default="s">{{ fmtQty(s.row.shouldQty) }} / {{ fmtQty(s.row.offsetQty) }} / {{ fmtQty(s.row.actualQty) }} / {{ fmtQty(s.row.overQty) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'dispense\'" label="已退" width="70" align="right"><template #default="s">{{ fmtQty(s.row.returnQty) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'dispense\'" label="替换" width="70" align="center"><template #default="s"><el-tag v-if="s.row.replaceFlag" type="warning" size="small">替换</el-tag><span v-else>-</span></template></el-table-column>',
      '    <el-table-column v-if="dimension===\'return\'" label="去向" width="100" align="center"><template #default="s"><el-tag :type="s.row.keepWard?\'warning\':\'info\'" size="small">{{ s.row.keepWard?\'暂存病区\':\'退回药房\' }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="dimension===\'return\'" label="金额" width="100" align="right"><template #default="s">{{ money(s.row.returnAmount) }}</template></el-table-column>',
      '    <el-table-column label="时间" width="150"><template #default="s">{{ dimension===\'dispense\' ? orDash(s.row.dispenseTime) : orDash(s.row.returnTime) }}</template></el-table-column>',
      '    <el-table-column v-if="dimension===\'dispense\'" label="操作" width="90" align="center"><template #default="s"><el-button link type="danger" size="small" v-if="Number(s.row.actualQty)>Number(s.row.returnQty)" @click="openReturn(s.row)">退药</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:10px;justify-content:flex-end" background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '  <el-dialog v-model="dlg.visible" title="住院退药" width="440px">',
      '    <div v-if="dlg.row" style="margin-bottom:8px">{{ orDash(dlg.row.drugName) }} · 实发 {{ fmtQty(dlg.row.actualQty) }} · 已退 {{ fmtQty(dlg.row.returnQty) }}</div>',
      '    <el-form label-width="100px">',
      '      <el-form-item label="退药数量"><el-input-number v-model="dlg.qty" :min="0.001" :step="1" style="width:100%"></el-input-number></el-form-item>',
      '      <el-form-item label="去向"><el-radio-group v-model="dlg.keepWard"><el-radio :label="true">暂存病区(供冲抵)</el-radio><el-radio :label="false" :disabled="dlg.fullOffset">退回药房(回库)</el-radio></el-radio-group></el-form-item>',
      '      <el-form-item v-if="dlg.fullOffset"><span style="color:#e6a23c;font-size:12px">该发药为全额冲抵（未出库、无批次可回退），仅可暂存病区供下次冲抵。</span></el-form-item>',
      '      <el-form-item label="原因"><el-input v-model="dlg.reason" type="textarea"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg.visible=false">取消</el-button><el-button type="primary" :loading="dlg.submitting" @click="submitReturn">确认退药</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () { return { dimension: 'dispense', keyword: '', startDate: '', endDate: '', rows: [], total: 0, page: 1, size: 20, loading: false, dlg: { visible: false, row: null, qty: null, keepWard: false, fullOffset: false, reason: '', submitting: false } }; },
    methods: {
      orDash: orDash, money: money, fmtQty: function (v) { return qtyFmt(v); },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/his/inp/dispense/history?dimension=' + vm.dimension + '&page=' + vm.page + '&size=' + vm.size
          + (vm.keyword ? ('&keyword=' + encodeURIComponent(vm.keyword)) : '')
          + (vm.startDate ? ('&startDate=' + vm.startDate) : '')
          + (vm.endDate ? ('&endDate=' + vm.endDate) : '');
        HIS.get(url).then(function (d) { vm.rows = (d && d.rows) || []; vm.total = Number(d && d.total) || 0; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      openReturn: function (row) { this.dlg.row = row; this.dlg.qty = Number(row.actualQty) - Number(row.returnQty); this.dlg.fullOffset = (Number(row.actualQty) || 0) === 0; this.dlg.keepWard = this.dlg.fullOffset; this.dlg.reason = ''; this.dlg.visible = true; },
      submitReturn: function () {
        var vm = this; if (!vm.dlg.qty || vm.dlg.qty <= 0) { HIS.notifyError(new Error('退药数量须大于0')); return; }
        vm.dlg.submitting = true;
        HIS.post('/api/his/inp/dispense/return', { dispenseId: HIS.id(vm.dlg.row.id), qty: vm.dlg.qty, reason: vm.dlg.reason || null, keepWard: vm.dlg.keepWard }).then(function () { HIS.notifySuccess('退药成功'); vm.dlg.visible = false; vm.load(); }).catch(HIS.notifyError).finally(function () { vm.dlg.submitting = false; });
      }
    },
    mounted: function () { this.load(); }
  };

  /* 组件注册完成: InpAdmission / InpPatientList / InpBedManage / InpDeposit / InpChargeList / InpSettle / InpDailySummary / InpDispenseWork / InpDischargePickup / InpDispenseHistory */
})();
