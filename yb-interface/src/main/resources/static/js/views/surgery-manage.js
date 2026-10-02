/* ============================================================================
 * 手麻记费: 手术管理(SurgeryManage) + 麻醉记录(AnesthesiaRecord) + 手术记费(SurgeryFee)
 * 后端契约:
 *   /api/his/surgery          手术列表/详情/申请/编辑/排程/开始/结束/完成/取消/today/room/list
 *   /api/his/anesthesia       麻醉记录(术前/术后评估 JSON、体征 vital_signs、事件 anesthesia_events)
 *   /api/his/surgery-fee      费用明细/汇总/自动计时计费
 *   /api/his/surgery-material 耗材登记/退回/汇总
 *   手麻P0: /unarranged-list /anesthesia-list /room-board /register-query
 *           /schedule-from-apply/{applyId} /urgent-schedule /{id}/check-in|transfer|reschedule|cancel-schedule|cancel-complete
 *           /notify/pending-count ; /api/his/surgery-fee-tpl list/apply(导入模板记账)
 * 字段口径:
 *   手术列表/今日排程/在院患者为手写 SQL 返回蛇形键(直接作表格 prop);
 *   详情内的 surgery/team、麻醉、费用、耗材为实体接口返回驼峰键。
 * 注册: HIS.views.SurgeryManage / HIS.views.AnesthesiaRecord / HIS.views.SurgeryFee
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 手麻模块私有样式一次性注入(无构建架构, 避免改动公共 css 与其他会话冲突) */
  (function ensureSurgStyles() {
    if (document.getElementById('surg-style')) { return; }
    var st = document.createElement('style');
    st.id = 'surg-style';
    st.textContent = [
      /* 小节标题 / 只读信息块 */
      '.surg-section-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); }',
      '.surg-info { background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:10px 14px; font-size:13px; color:var(--yb-ink-2); line-height:2; }',
      '.surg-info b { color:var(--yb-ink-1); }',
      /* 金额数字 */
      '.surg-money { font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); font-weight:600; color:var(--yb-ink-1); }',
      '.surg-money-red { color:var(--yb-danger); }',
      /* 今日看板: 手术间卡片 */
      '.surg-room-card { border:1px solid var(--yb-border); border-radius:var(--yb-r-md); background:var(--yb-surface); padding:10px 12px; height:100%; box-sizing:border-box; min-height:150px; }',
      '.surg-room-title { font-size:14px; font-weight:700; color:var(--yb-ink-1); border-bottom:1px solid var(--yb-border-light); padding-bottom:6px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; }',
      '.surg-room-title .cnt { font-size:12px; font-weight:400; color:var(--yb-ink-3); }',
      '.surg-room-item { padding:6px 8px; border-radius:var(--yb-r-sm); border:1px solid var(--yb-border-light); margin-bottom:6px; cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease); }',
      '.surg-room-item:hover { box-shadow:var(--yb-sh-2); }',
      '.surg-room-item .line1 { font-size:13px; font-weight:600; color:var(--yb-ink-1); }',
      '.surg-room-item .line2 { font-size:12px; color:var(--yb-ink-2); margin-top:1px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.surg-room-item .line3 { font-size:12px; color:var(--yb-ink-3); margin-top:2px; display:flex; justify-content:space-between; align-items:center; }',
      '.surg-room-empty { color:var(--yb-ink-4); font-size:12px; text-align:center; padding:26px 0; }',
      /* 汇总表合计行 */
      '.surg-sum-total td { font-weight:700 !important; background:var(--yb-gold-bg) !important; color:var(--yb-gold) !important; }',
      /* 分类色点 */
      '.surg-cat-dot { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:4px; vertical-align:middle; }',
      /* 图表容器 */
      '.surg-chart { height:280px; width:100%; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface); }',
      '.surg-chart-tall { height:300px; width:100%; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface); }',
      /* 自动计时标记 */
      '.surg-flag-auto { color:var(--yb-warning); font-size:12px; margin-left:6px; }',
      /* 明细操作列紧凑 */
      '.surg-op .el-button + .el-button { margin-left:2px; }',
      /* 手麻P0: 排程板拖拽 */
      '.surg-board-card { cursor:grab; }',
      '.surg-board-card.dragging { opacity:.4; }',
      '.surg-board-col { min-height:60px; }',
      '.surg-board-col.drag-over { background:var(--yb-brand-soft, rgba(64,158,255,.08)); outline:1px dashed var(--yb-brand); outline-offset:-2px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 常量映射 ===== */
  const SURGERY_STATUS = { 1: '申请', 2: '排程', 3: '术中', 4: '术后', 5: '完成', 6: '取消', 7: '已报到' };
  const SURGERY_STATUS_TYPE = { 1: 'info', 2: 'warning', 3: 'danger', 4: '', 5: 'success', 6: 'info', 7: 'primary' };
  const SURGERY_LEVEL = { 1: '一级', 2: '二级', 3: '三级', 4: '四级' };
  const ASA_GRADE = { 1: 'ASA I', 2: 'ASA II', 3: 'ASA III', 4: 'ASA IV', 5: 'ASA V' };
  const INCISION_TYPE = { 1: '清洁', 2: '清洁-污染', 3: '污染', 4: '感染' };
  const ANESTHESIA_TYPE = { 1: '全身麻醉', 2: '局部麻醉', 3: '椎管内麻醉', 4: '神经阻滞', 5: '复合麻醉', 6: '其他' };
  /* 手麻P0 字典(未安排池/急诊直排/模板导入) */
  const VISIT_TYPE = { 1: '住院', 2: '门诊', 3: '日间' };
  const DEADLINE = { 1: '择期', 2: '限期', 3: '急诊' };
  const DEADLINE_TYPE = { 1: 'info', 2: 'warning', 3: 'danger' };
  const TPL_LEVEL = { 1: '个人', 2: '科室', 3: '全院' };
  const FEE_CATEGORY = { 1: '手术费', 2: '麻醉费', 3: '监测费', 4: '耗材费', 5: '药品费', 6: '其他' };
  const FEE_CATEGORY_COLOR = { 1: '#409eff', 2: '#67c23a', 3: '#e6a23c', 4: '#f56c6c', 5: '#909399', 6: '#c0c4cc' };
  /* 手麻P1: 手术医嘱(复用住院医嘱链)标签映射 */
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  const ORDER_PHASE = { 1: '术前', 2: '术中', 3: '术后' };
  const ORDER_PHASE_TYPE = { 1: 'info', 2: 'danger', 3: 'warning' };
  const ORDER_STATUS = { 1: '新开', 2: '已审核', 3: '执行中', 4: '已完成', 5: '已停止', 6: '已作废' };
  const ORDER_STATUS_TYPE = { 1: 'primary', 2: 'success', 3: 'warning', 4: 'success', 5: 'info', 6: 'danger' };
  const SEND_PHARM = { 0: '未发送', 1: '已发送', 2: '已撤回' };
  /* 手麻P3: 退药申请标志 + 复苏(PACU)状态/去向字典 */
  const RETURN_APPLY = { 0: '无', 1: '申请中', 2: '已退药' };
  const PACU_STATUS = { 1: '复苏中', 2: '已出' };
  const PACU_STATUS_TYPE = { 1: 'warning', 2: 'success' };
  const PACU_DEST = { 1: '回病房', 2: '转ICU', 3: '门诊随访' };
  /* 生命体征折线色: HR红 SBP蓝 DBP浅蓝 SpO2紫 Temp橙 RR绿 */
  const VITAL_COLORS = { hr: '#c74f4f', sbp: '#2c78c7', dbp: '#8ab6e0', spo2: '#9b59b6', temp: '#d98b32', rr: '#3c862d' };

  /* ===== 下拉选项 ===== */
  function optsOf(map) {
    return Object.keys(map).map(function (k) { return { value: Number(k), label: map[k] }; });
  }
  const STATUS_OPTS = optsOf(SURGERY_STATUS);
  const SURGERY_LEVEL_OPTS = optsOf(SURGERY_LEVEL);
  const ASA_OPTS = optsOf(ASA_GRADE);
  const INCISION_OPTS = optsOf(INCISION_TYPE);
  const ANE_TYPE_OPTS = optsOf(ANESTHESIA_TYPE);
  const FEE_CAT_OPTS = optsOf(FEE_CATEGORY);
  const ORDER_CAT_OPTS = optsOf(ORDER_CATEGORY);
  const ORDER_PHASE_OPTS = optsOf(ORDER_PHASE);
  /* 手麻P2d: 一体化模块(1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗) + 新生儿分娩方式 */
  const MODULE_TYPE = { 1: '手术室', 2: 'DSA', 3: '产科分娩', 4: '内镜', 5: '麻醉治疗' };
  const MODULE_OPTS = optsOf(MODULE_TYPE);
  const BIRTH_TYPE = { 1: '顺产', 2: '剖宫产', 3: '产钳', 4: '臀助', 5: '其他' };
  const BIRTH_TYPE_OPTS = optsOf(BIRTH_TYPE);
  const NEWBORN_STATUS = { 1: '在院', 2: '已转科', 3: '已出院' };
  const TIME_SLOTS = (function () {
    var out = [];
    for (var h = 8; h <= 20; h++) {
      out.push({ value: pad2(h) + ':00-' + pad2(h + 1) + ':00' });
    }
    return out;
  })();
  const EVENT_TYPE_OPTS = [
    { value: '诱导', label: '诱导' }, { value: '插管', label: '插管' }, { value: '切皮', label: '切皮' },
    { value: '出血', label: '出血' }, { value: '用药', label: '用药' }, { value: '异常', label: '异常' },
    { value: '其他', label: '其他' }
  ];
  const AIRWAY_OPTS = [
    { value: '正常', label: '正常' }, { value: '困难气道', label: '困难气道' },
    { value: '张口受限', label: '张口受限' }, { value: '颈椎活动受限', label: '颈椎活动受限' }
  ];
  const MALLAMPATI_OPTS = [
    { value: 'I级', label: 'I级' }, { value: 'II级', label: 'II级' }, { value: 'III级', label: 'III级' }, { value: 'IV级', label: 'IV级' }
  ];
  const DEST_OPTS = [
    { value: '返回病房', label: '返回病房' }, { value: 'ICU', label: 'ICU' }, { value: 'PACU', label: 'PACU' }
  ];
  const ANALGESIA_OPTS = [
    { value: '无', label: '无' }, { value: 'PCIA', label: 'PCIA(静脉自控)' },
    { value: 'PCEA', label: 'PCEA(硬膜外自控)' }, { value: '口服', label: '口服镇痛' }, { value: '其他', label: '其他' }
  ];

  /* ===== 工具函数 ===== */
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function money(v) { return (v === null || v === undefined || v === '') ? '0.00' : Number(v).toFixed(2); }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function fmtDate(v) { return (v === null || v === undefined || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 10); }
  function fmtDT(v) { return (v === null || v === undefined || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 16); }
  /* 时间戳取 HH:mm(图表 X 轴 / 列表短时间) */
  function hmOf(v) {
    if (v === null || v === undefined || v === '') { return ''; }
    var s = String(v).replace('T', ' ');
    var i = s.indexOf(' ');
    return i >= 0 ? s.slice(i + 1, i + 6) : s.slice(0, 5);
  }
  function today() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  /* Date → yyyy-MM-dd(排程板拖拽改日默认当前板日期) */
  function fmtD(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  /* 当前时间戳 yyyy-MM-dd HH:mm:ss(体征/事件录入默认值) */
  function nowDT() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
      + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }
  /* HTML 转义: 确认框消息拼接用户输入(姓名/项目名)前必须转义, 防存储型 XSS */
  function escHtml(v) {
    return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function confirmBox(msg, title) {
    return ElementPlus.ElMessageBox.confirm(msg, title || '操作确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' });
  }
  function isCancel(e) { return e === 'cancel' || e === 'close'; }
  /* 手麻P0: 带必填校验的原因输入框(退回/作废) */
  function promptReason(title, placeholder) {
    return ElementPlus.ElMessageBox.prompt(placeholder || '请填写原因', title, {
      inputType: 'textarea', inputValidator: function (v) { return (v && v.trim()) ? true : '内容不能为空'; }
    });
  }
  function nvl(v, d) { return (v === null || v === undefined || v === '') ? d : v; }
  /* 安全解析 JSON 字符串数组 / 对象(麻醉记录的 vital_signs/anesthesia_events/preAssessment 等) */
  function jsonArr(s) {
    if (!s) { return []; }
    if (Array.isArray(s)) { return s; }
    try { var a = JSON.parse(s); return Array.isArray(a) ? a : []; } catch (e) { return []; }
  }
  function jsonObj(s) {
    if (!s) { return {}; }
    if (typeof s === 'object' && !Array.isArray(s)) { return s; }
    try {
      var o = JSON.parse(s);
      return (o && typeof o === 'object' && !Array.isArray(o)) ? o : {};
    } catch (e) { return {}; }
  }

  /* ===== 文案映射 ===== */
  function stLabel(s) { return SURGERY_STATUS[s] || (s === null || s === undefined ? '-' : s); }
  function stTag(s) { var t = SURGERY_STATUS_TYPE[s]; return t === undefined ? 'info' : t; }
  function levelLabel(v) { return SURGERY_LEVEL[v] || orDash(v); }
  function asaLabel(v) { return ASA_GRADE[v] || orDash(v); }
  function incisionLabel(v) { return INCISION_TYPE[v] || orDash(v); }
  function aneTypeLabel(v) { return ANESTHESIA_TYPE[v] || orDash(v); }
  function feeCatLabel(v) { return FEE_CATEGORY[v] || '其他'; }
  function feeCatColor(v) { return FEE_CATEGORY_COLOR[v] || '#c0c4cc'; }
  function vtLabel(v) { return VISIT_TYPE[v] || orDash(v); }
  function dlLabel(v) { return DEADLINE[v] || orDash(v); }
  function dlTag(v) { return DEADLINE_TYPE[v] || 'info'; }
  function tplLevelLabel(v) { return TPL_LEVEL[v] || orDash(v); }
  function orderCatLabel(v) { return ORDER_CATEGORY[v] || orDash(v); }
  function phaseLabel(v) { return ORDER_PHASE[v] || '-'; }
  function phaseTag(v) { return ORDER_PHASE_TYPE[v] || 'info'; }
  function orderStLabel(v) { return ORDER_STATUS[v] || orDash(v); }
  function orderStTag(v) { return ORDER_STATUS_TYPE[v] || 'info'; }
  function sendPharmLabel(v) { return SEND_PHARM[v] || ''; }
  function returnApplyLabel(v) { return RETURN_APPLY[v] || ''; }
  function pacuStatusLabel(v) { return PACU_STATUS[v] || orDash(v); }
  function pacuStatusTag(v) { var t = PACU_STATUS_TYPE[v]; return t === undefined ? 'info' : t; }
  function pacuDestLabel(v) { return PACU_DEST[v] || orDash(v); }
  function moduleLabel(v) { return MODULE_TYPE[v || 1] || orDash(v); }
  function birthTypeLabel(v) { return BIRTH_TYPE[v] || orDash(v); }
  function newbornStatusLabel(v) { return NEWBORN_STATUS[v] || orDash(v); }
  function newbornStatusTag(v) { return v === 1 ? 'success' : (v === 2 ? 'warning' : 'info'); }

  /* ===== 表单空白模板 ===== */
  function blankVital() {
    return { time: nowDT(), hr: null, sbp: null, dbp: null, spo2: null, temp: null, etco2: null, rr: null };
  }
  function blankEvent() {
    return { time: nowDT(), eventType: '诱导', description: '' };
  }
  /* 手麻P1: 手术医嘱开立表单默认值(阶段默认术中, 因从已排程手术发起) */
  function blankSoOrderForm() {
    return { orderPhase: 2, orderCategory: 1, orderContent: '', drugId: null, chargeItemId: null,
      spec: '', dosage: '', dosageUnit: '', usageCode: null, freqCode: null, quantity: 1,
      proxyOn: false, proxyDoctorId: null, proxyReason: '' };
  }
  /* 手麻P2d: 新生儿建档表单默认值(birthTime 预填当前, 分娩方式默认剖宫产关联分娩手术) */
  function blankNewbornForm(surgeryId, motherInpVisitId) {
    return { surgeryId: surgeryId || null, motherInpVisitId: motherInpVisitId || null,
      babyName: '', babySex: null, birthTime: nowDT(), apgar1: null, apgar5: null, apgar10: null,
      weightG: null, heightCm: null, birthType: 2, remark: '' };
  }

  /* ===== 共享混入: 模板可直接使用的工具代理 + 时间段选项 ===== */
  var surgMixin = {
    data: function () { return { timeSlots: TIME_SLOTS }; },
    methods: {
      money: money, orDash: orDash, fmtDate: fmtDate, fmtDT: fmtDT, hmOf: hmOf,
      stLabel: stLabel, stTag: stTag, levelLabel: levelLabel, asaLabel: asaLabel,
      incisionLabel: incisionLabel, aneTypeLabel: aneTypeLabel,
      feeCatLabel: feeCatLabel, feeCatColor: feeCatColor,
      vtLabel: vtLabel, dlLabel: dlLabel, dlTag: dlTag, tplLevelLabel: tplLevelLabel,
      orderCatLabel: orderCatLabel, phaseLabel: phaseLabel, phaseTag: phaseTag,
      jsonArr: jsonArr,
      orderStLabel: orderStLabel, orderStTag: orderStTag, sendPharmLabel: sendPharmLabel,
      returnApplyLabel: returnApplyLabel, pacuStatusLabel: pacuStatusLabel,
      pacuStatusTag: pacuStatusTag, pacuDestLabel: pacuDestLabel,
      moduleLabel: moduleLabel, birthTypeLabel: birthTypeLabel, newbornStatusLabel: newbornStatusLabel,
      newbornStatusTag: newbornStatusTag
    }
  };

  /* ========================================================================
   * 2. AnesthesiaRecord 麻醉记录(术前评估 / 术中监测 / 术后评估)
   * 用法: 由 SurgeryManage 以 :surgery-id 传入(锁定手术); 或独立菜单打开(自由选择手术)。
   * 数据: 术前/术后评估存 preAssessment/postAssessment JSON; 体征/事件存 vital_signs/anesthesia_events JSON 数组。
   * ====================================================================== */
  HIS.views.AnesthesiaRecord = {
    mixins: [surgMixin],
    props: {
      surgeryId: { type: Number, default: null }
    },
    template: [
      '<div class="surg-ane">',
      '  <div v-if="!lock" class="toolbar" style="margin-bottom:12px">',
      '    <el-select v-model="curSid" filterable :loading="surLoading" placeholder="选择手术（患者 - 手术名 - 日期）" style="width:380px" @change="loadAll">',
      '      <el-option v-for="s in surOptions" :key="s.id" :label="surLabel(s)" :value="s.id"></el-option>',
      '    </el-select>',
      '    <span style="color:var(--yb-ink-3);font-size:12px">选择手术后可填写麻醉记录</span>',
      '  </div>',
      '  <div v-if="surgery" class="surg-info" style="margin-bottom:12px">',
      '    <b>{{ patient ? patient.name : "-" }}</b>　住院号: {{ patient ? patient.inp_no : "-" }}　{{ surgery.surgeryName }}　主刀: {{ teamName("surgeon") }}　',
      '    手术状态: <el-tag size="small" :type="stTag(surgery.status)">{{ stLabel(surgery.status) }}</el-tag>　',
      '    麻醉记录: <el-tag size="small" :type="recTag">{{ recLabel }}</el-tag>',
      '  </div>',
      '  <el-empty v-if="!curSid" description="请先选择手术"></el-empty>',
      '  <el-tabs v-else v-model="activeTab" @tab-change="onTab">',
      '    <el-tab-pane label="术前评估" name="pre">',
      '      <el-form :model="preForm" label-width="112px" style="max-width:920px" v-loading="loading">',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="ASA分级">',
      '            <el-select v-model="preForm.asaGrade" clearable placeholder="选择ASA分级" style="width:100%">',
      '              <el-option v-for="o in asaOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="麻醉类型">',
      '            <el-select v-model="preForm.anesthesiaType" clearable placeholder="选择麻醉类型" style="width:100%">',
      '              <el-option v-for="o in aneTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-form-item label="具体麻醉方式">',
      '          <el-input v-model="preForm.anesthesiaMethod" maxlength="100" placeholder="如: 静吸复合全麻 / 腰硬联合麻醉 / 臂丛神经阻滞"></el-input>',
      '        </el-form-item>',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="气道评估">',
      '            <el-select v-model="preForm.airway" clearable placeholder="选择气道评估" style="width:100%">',
      '              <el-option v-for="o in airwayOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="Mallampati分级">',
      '            <el-select v-model="preForm.mallampati" clearable placeholder="选择分级" style="width:100%">',
      '              <el-option v-for="o in mallaOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-form-item label="过敏史">',
      '          <el-input v-model="preForm.allergy" type="textarea" :rows="2" maxlength="200" placeholder="药物/食物过敏史, 无则填无"></el-input>',
      '        </el-form-item>',
      '        <el-form-item label="合并症">',
      '          <el-input v-model="preForm.complications" type="textarea" :rows="2" maxlength="200" placeholder="高血压/糖尿病/冠心病等"></el-input>',
      '        </el-form-item>',
      '        <el-form-item label="术前用药">',
      '          <el-input v-model="preForm.preMedication" type="textarea" :rows="2" maxlength="200" placeholder="如: 阿托品0.5mg 术前30min 肌注"></el-input>',
      '        </el-form-item>',
      '        <el-form-item>',
      '          <el-button type="primary" :loading="saving" @click="savePre">保存术前评估</el-button>',
      '          <span style="color:var(--yb-ink-3);font-size:12px;margin-left:8px">{{ recordId ? "已建立麻醉记录" : "保存后自动建立麻醉记录" }}</span>',
      '        </el-form-item>',
      '      </el-form>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="术中监测" name="vital">',
      '      <div ref="vitalChart" class="surg-chart-tall"></div>',
      '      <el-row :gutter="14" style="margin-top:14px">',
      '        <el-col :span="15">',
      '          <div class="surg-section-title">生命体征记录',
      '            <el-button size="small" type="primary" plain style="margin-left:8px" @click="openVitalAdd">记录体征</el-button>',
      '          </div>',
      '          <el-table :data="vitals" border size="small" max-height="300">',
      '            <el-table-column label="时间" width="80" align="center"><template #default="s">{{ hmOf(s.row.time) }}</template></el-table-column>',
      '            <el-table-column label="HR" width="62" align="center"><template #default="s">{{ orDash(s.row.hr) }}</template></el-table-column>',
      '            <el-table-column label="SBP" width="62" align="center"><template #default="s">{{ orDash(s.row.sbp) }}</template></el-table-column>',
      '            <el-table-column label="DBP" width="62" align="center"><template #default="s">{{ orDash(s.row.dbp) }}</template></el-table-column>',
      '            <el-table-column label="SpO2" width="64" align="center"><template #default="s">{{ orDash(s.row.spo2) }}</template></el-table-column>',
      '            <el-table-column label="Temp" width="62" align="center"><template #default="s">{{ orDash(s.row.temp) }}</template></el-table-column>',
      '            <el-table-column label="ETCO2" width="66" align="center"><template #default="s">{{ orDash(s.row.etco2) }}</template></el-table-column>',
      '            <el-table-column label="RR" width="60" align="center"><template #default="s">{{ orDash(s.row.rr) }}</template></el-table-column>',
      '          </el-table>',
      '          <div v-if="!vitals.length" style="color:var(--yb-ink-4);font-size:12px;text-align:center;padding:10px 0">暂无体征数据, 点击上方"记录体征"开始录入</div>',
      '        </el-col>',
      '        <el-col :span="9">',
      '          <div class="surg-section-title">术中事件',
      '            <el-button size="small" type="primary" plain style="margin-left:8px" @click="openEventAdd">记录事件</el-button>',
      '          </div>',
      '          <div v-for="(ev, i) in events" :key="i" style="border:1px solid var(--yb-border-light);border-radius:var(--yb-r-sm);padding:6px 10px;margin-bottom:6px">',
      '            <div style="font-size:13px;font-weight:600;color:var(--yb-ink-1)">{{ hmOf(ev.time) }}　{{ ev.eventType }}</div>',
      '            <div style="font-size:12px;color:var(--yb-ink-3);margin-top:2px">{{ ev.description || "（无描述）" }}</div>',
      '          </div>',
      '          <div v-if="!events.length" style="color:var(--yb-ink-4);font-size:12px;text-align:center;padding:10px 0">暂无术中事件</div>',
      '        </el-col>',
      '      </el-row>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="术后评估" name="post">',
      '      <el-form :model="postForm" label-width="112px" style="max-width:920px" v-loading="loading">',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="苏醒时间">',
      '            <el-date-picker v-model="postForm.wakeTime" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择苏醒时间" style="width:100%"></el-date-picker>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="Aldrete评分">',
      '            <el-input-number v-model="postForm.aldrete" :min="0" :max="10" style="width:100%"></el-input-number>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="术后镇痛">',
      '            <el-select v-model="postForm.analgesia" clearable placeholder="选择镇痛方式" style="width:100%">',
      '              <el-option v-for="o in analgesiaOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="转归">',
      '            <el-select v-model="postForm.destination" clearable placeholder="选择转归去向" style="width:100%">',
      '              <el-option v-for="o in destOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-form-item label="并发症">',
      '          <el-input v-model="postForm.complications" type="textarea" :rows="2" maxlength="200" placeholder="恶心呕吐/苏醒延迟/低体温等, 无则填无"></el-input>',
      '        </el-form-item>',
      '        <el-form-item>',
      '          <el-button type="primary" :loading="saving" @click="savePost">保存术后评估</el-button>',
      '          <el-button type="success" :disabled="!recordId" @click="complete">完成麻醉记录</el-button>',
      '        </el-form-item>',
      '      </el-form>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '  <el-dialog v-model="vitalDialog" title="记录体征" width="580px" append-to-body>',
      '    <el-form :model="vitalForm" label-width="92px">',
      '      <el-form-item label="时间">',
      '        <el-input v-model="vitalForm.time" placeholder="yyyy-MM-dd HH:mm:ss"></el-input>',
      '      </el-form-item>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="HR(bpm)"><el-input-number v-model="vitalForm.hr" :min="0" :max="300" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="SBP(mmHg)"><el-input-number v-model="vitalForm.sbp" :min="0" :max="300" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="DBP(mmHg)"><el-input-number v-model="vitalForm.dbp" :min="0" :max="200" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="SpO2(%)"><el-input-number v-model="vitalForm.spo2" :min="0" :max="100" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="Temp(℃)"><el-input-number v-model="vitalForm.temp" :min="30" :max="45" :precision="1" :step="0.1" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="ETCO2"><el-input-number v-model="vitalForm.etco2" :min="0" :max="99" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="RR(次/分)"><el-input-number v-model="vitalForm.rr" :min="0" :max="99" style="width:100%"></el-input-number></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="vitalDialog = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitVital">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="eventDialog" title="记录术中事件" width="520px" append-to-body>',
      '    <el-form :model="eventForm" label-width="92px">',
      '      <el-form-item label="时间">',
      '        <el-input v-model="eventForm.time" placeholder="yyyy-MM-dd HH:mm:ss"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="事件类型">',
      '        <el-select v-model="eventForm.eventType" style="width:100%">',
      '          <el-option v-for="o in eventTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="描述">',
      '        <el-input v-model="eventForm.description" type="textarea" :rows="3" maxlength="200" placeholder="事件描述（如: 出血约200ml, 使用麻黄素6mg）"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="eventDialog = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitEvent">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        curSid: this.surgeryId,
        surOptions: [],
        surLoading: false,
        surgery: null,
        patient: null,
        team: {},
        record: null,
        recordId: null,
        activeTab: 'pre',
        preForm: { asaGrade: null, anesthesiaType: null, anesthesiaMethod: '', airway: '', mallampati: '', allergy: '', complications: '', preMedication: '' },
        postForm: { wakeTime: '', aldrete: null, analgesia: '', complications: '', destination: '' },
        vitals: [],
        events: [],
        vitalDialog: false,
        vitalForm: blankVital(),
        eventDialog: false,
        eventForm: blankEvent(),
        saving: false,
        loading: false,
        chart: null,
        asaOpts: ASA_OPTS,
        aneTypeOpts: ANE_TYPE_OPTS,
        airwayOpts: AIRWAY_OPTS,
        mallaOpts: MALLAMPATI_OPTS,
        analgesiaOpts: ANALGESIA_OPTS,
        destOpts: DEST_OPTS,
        eventTypeOpts: EVENT_TYPE_OPTS
      };
    },
    computed: {
      lock: function () { return this.surgeryId !== null && this.surgeryId !== undefined; },
      recTag: function () {
        if (!this.record) { return 'info'; }
        return this.record.status === 2 ? 'success' : 'warning';
      },
      recLabel: function () {
        if (!this.record) { return '未建立'; }
        return this.record.status === 2 ? '已完成' : '记录中';
      }
    },
    watch: {
      surgeryId: function (v) {
        this.curSid = v;
        if (v) { this.loadAll(); }
      }
    },
    methods: {
      /* 独立菜单打开时: 拉取近100台手术供选择 */
      loadSurgeries: function () {
        var vm = this;
        vm.surLoading = true;
        var q = '/api/his/surgery/list?page=1&size=100';
        var org = HIS.currentOrgId();
        if (org) { q += '&orgId=' + org; }
        HIS.get(q).then(function (d) {
          vm.surOptions = (d && d.records) || [];
          vm.surLoading = false;
        }).catch(function (e) { vm.surLoading = false; HIS.notifyError(e); });
      },
      surLabel: function (s) {
        return orDash(s.patient_name) + ' - ' + orDash(s.surgery_name) + ' - ' + fmtDate(s.schedule_date);
      },
      teamName: function (key) {
        var t = this.team ? this.team[key] : null;
        return (t && t.name) ? t.name : '-';
      },
      /* 加载手术详情 + 麻醉记录 */
      loadAll: function () {
        var vm = this;
        if (!vm.curSid) { return; }
        vm.loading = true;
        HIS.get('/api/his/surgery/' + vm.curSid).then(function (d) {
          vm.surgery = d ? d.surgery : null;
          vm.patient = d ? d.patient : null;
          vm.team = (d && d.team) || {};
          return HIS.get('/api/his/anesthesia/' + vm.curSid);
        }).then(function (r) {
          vm.loading = false;
          vm.applyRecord(r);
          if (vm.activeTab === 'vital') {
            vm.$nextTick(function () { vm.renderChart(); });
          }
        }).catch(function (e) { vm.loading = false; HIS.notifyError(e); });
      },
      /* 麻醉记录 -> 表单/列表回填(JSON 字符串安全解析) */
      applyRecord: function (r) {
        this.record = r || null;
        this.recordId = r ? r.id : null;
        var pre = r ? jsonObj(r.preAssessment) : {};
        this.preForm = {
          asaGrade: nvl(pre.asaGrade, this.surgery ? this.surgery.asaGrade : null),
          anesthesiaType: r ? r.anesthesiaType : null,
          anesthesiaMethod: r && r.anesthesiaMethod ? r.anesthesiaMethod : '',
          airway: nvl(pre.airway, ''),
          mallampati: nvl(pre.mallampati, ''),
          allergy: nvl(pre.allergy, ''),
          complications: nvl(pre.complications, ''),
          preMedication: nvl(pre.preMedication, '')
        };
        var post = r ? jsonObj(r.postAssessment) : {};
        this.postForm = {
          wakeTime: nvl(post.wakeTime, ''),
          aldrete: nvl(post.aldrete, null),
          analgesia: nvl(post.analgesia, ''),
          complications: nvl(post.complications, ''),
          destination: nvl(post.destination, '')
        };
        this.vitals = r ? jsonArr(r.vitalSigns) : [];
        this.events = r ? jsonArr(r.anesthesiaEvents) : [];
      },
      buildPreJson: function () {
        return JSON.stringify({
          asaGrade: this.preForm.asaGrade,
          airway: this.preForm.airway,
          mallampati: this.preForm.mallampati,
          allergy: this.preForm.allergy,
          complications: this.preForm.complications,
          preMedication: this.preForm.preMedication
        });
      },
      buildPostJson: function () {
        return JSON.stringify({
          wakeTime: this.postForm.wakeTime,
          aldrete: this.postForm.aldrete,
          analgesia: this.postForm.analgesia,
          complications: this.postForm.complications,
          destination: this.postForm.destination
        });
      },
      savePre: function () {
        if (!this.curSid) { return; }
        this.submitRecord({
          surgeryId: this.curSid,
          anesthesiaType: this.preForm.anesthesiaType,
          anesthesiaMethod: this.preForm.anesthesiaMethod,
          preAssessment: this.buildPreJson(),
          postAssessment: this.record ? this.record.postAssessment : null
        }, '术前评估已保存');
      },
      savePost: function () {
        if (!this.curSid) { return; }
        this.submitRecord({
          surgeryId: this.curSid,
          anesthesiaType: this.preForm.anesthesiaType,
          anesthesiaMethod: this.preForm.anesthesiaMethod,
          preAssessment: this.record ? this.record.preAssessment : this.buildPreJson(),
          postAssessment: this.buildPostJson()
        }, '术后评估已保存');
      },
      /* 麻醉记录保存: 无记录则创建(POST), 有则更新(PUT); 成功后回读刷新 */
      submitRecord: function (body, msg) {
        var vm = this;
        vm.saving = true;
        var p = vm.recordId
          ? HIS.put('/api/his/anesthesia/' + vm.recordId, body)
          : HIS.post('/api/his/anesthesia', body);
        p.then(function () { return HIS.get('/api/his/anesthesia/' + vm.curSid); })
          .then(function (r) {
            vm.saving = false;
            vm.applyRecord(r);
            HIS.notifySuccess(msg);
          })
          .catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      complete: function () {
        var vm = this;
        if (!vm.recordId) { HIS.notifyError('暂无麻醉记录'); return; }
        confirmBox('确认完成该手术的麻醉记录？完成后状态将标记为已完成。', '完成麻醉记录')
          .then(function () { return HIS.put('/api/his/anesthesia/' + vm.recordId + '/complete'); })
          .then(function () { return HIS.get('/api/his/anesthesia/' + vm.curSid); })
          .then(function (r) { vm.applyRecord(r); HIS.notifySuccess('麻醉记录已完成'); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 术中监测 ===== */
      onTab: function (name) {
        var vm = this;
        if ((name || vm.activeTab) === 'vital') {
          vm.$nextTick(function () { vm.renderChart(); });
        }
      },
      renderChart: function () {
        var vm = this;
        var dom = vm.$refs.vitalChart;
        if (!window.echarts || !dom) { return; }
        if (vm.chart) { vm.chart.dispose(); vm.chart = null; }
        var T = HIS.theme || {};
        var rows = vm.vitals || [];
        var times = rows.map(function (r) { return hmOf(r.time); });
        function pick(k) {
          return rows.map(function (r) {
            var v = r[k];
            return (v === null || v === undefined || v === '') ? null : Number(v);
          });
        }
        var c = VITAL_COLORS;
        var xAxis = { type: 'category', boundaryGap: false, data: times };
        var y0 = { type: 'value', name: 'mmHg/bpm', min: 0, max: 200 };
        var y1 = { type: 'value', name: '%/℃', min: 30, max: 100 };
        if (T.catAxis) { xAxis = T.catAxis(xAxis); }
        if (T.valAxis) { y0 = T.valAxis(y0); y1 = T.valAxis(y1); }
        var legendData = ['HR', 'SBP', 'DBP', 'SpO2', 'Temp', 'RR'];
        var chart = window.echarts.init(dom, 'yb');
        vm.chart = chart;
        chart.setOption({
          tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
          legend: T.legend ? T.legend({ data: legendData, top: 0 }) : { data: legendData, top: 0 },
          grid: { left: 48, right: 52, top: 38, bottom: 28 },
          xAxis: xAxis,
          yAxis: [y0, y1],
          series: [
            { name: 'HR', type: 'line', smooth: true, symbolSize: 6, data: pick('hr'), yAxisIndex: 0, itemStyle: { color: c.hr } },
            { name: 'SBP', type: 'line', smooth: true, symbolSize: 6, data: pick('sbp'), yAxisIndex: 0, itemStyle: { color: c.sbp } },
            { name: 'DBP', type: 'line', smooth: true, symbolSize: 6, data: pick('dbp'), yAxisIndex: 0, itemStyle: { color: c.dbp } },
            { name: 'SpO2', type: 'line', smooth: true, symbolSize: 6, data: pick('spo2'), yAxisIndex: 1, itemStyle: { color: c.spo2 } },
            { name: 'Temp', type: 'line', smooth: true, symbolSize: 6, data: pick('temp'), yAxisIndex: 1, itemStyle: { color: c.temp } },
            { name: 'RR', type: 'line', smooth: true, symbolSize: 6, data: pick('rr'), yAxisIndex: 0, itemStyle: { color: c.rr } }
          ]
        });
        /* 抽屉/页签动画结束后再 resize 一次, 保证画布尺寸正确 */
        setTimeout(function () {
          try { if (vm.chart && !vm.chart.isDisposed()) { vm.chart.resize(); } } catch (e) { }
        }, 320);
      },
      onResize: function () {
        try { if (this.chart && !this.chart.isDisposed()) { this.chart.resize(); } } catch (e) { }
      },
      openVitalAdd: function () {
        if (!this.recordId) { HIS.notifyError('请先在术前评估页保存麻醉基础信息'); return; }
        this.vitalForm = blankVital();
        this.vitalDialog = true;
      },
      submitVital: function () {
        var vm = this;
        var f = vm.vitalForm;
        if (!f.time) { HIS.notifyError('请填写记录时间'); return; }
        if (f.hr === null && f.sbp === null && f.dbp === null && f.spo2 === null && f.temp === null && f.etco2 === null && f.rr === null) {
          HIS.notifyError('至少填写一项体征数据'); return;
        }
        vm.saving = true;
        HIS.post('/api/his/anesthesia/' + vm.recordId + '/vital-sign', {
          time: f.time, hr: f.hr, sbp: f.sbp, dbp: f.dbp, spo2: f.spo2, temp: f.temp, etco2: f.etco2, rr: f.rr
        }).then(function () { return HIS.get('/api/his/anesthesia/' + vm.curSid); })
          .then(function (r) {
            vm.saving = false;
            vm.vitalDialog = false;
            vm.applyRecord(r);
            vm.$nextTick(function () { vm.renderChart(); });
            HIS.notifySuccess('体征已记录');
          })
          .catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      openEventAdd: function () {
        if (!this.recordId) { HIS.notifyError('请先在术前评估页保存麻醉基础信息'); return; }
        this.eventForm = blankEvent();
        this.eventDialog = true;
      },
      submitEvent: function () {
        var vm = this;
        var f = vm.eventForm;
        if (!f.eventType) { HIS.notifyError('请选择事件类型'); return; }
        vm.saving = true;
        HIS.post('/api/his/anesthesia/' + vm.recordId + '/event', {
          time: f.time, eventType: f.eventType, description: f.description
        }).then(function () { return HIS.get('/api/his/anesthesia/' + vm.curSid); })
          .then(function (r) {
            vm.saving = false;
            vm.eventDialog = false;
            vm.applyRecord(r);
            HIS.notifySuccess('事件已记录');
          })
          .catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      }
    },
    mounted: function () {
      var vm = this;
      window.addEventListener('resize', vm.onResize);
      if (!vm.lock) { vm.loadSurgeries(); }
      if (vm.curSid) { vm.loadAll(); }
    },
    unmounted: function () {
      window.removeEventListener('resize', this.onResize);
      if (this.chart) { this.chart.dispose(); this.chart = null; }
    }
  };

  /* 时间戳转 Date(兼容 'yyyy-MM-ddTHH:mm:ss' 与 'yyyy-MM-dd HH:mm:ss') */
  function parseTs(v) {
    if (!v) { return null; }
    var s = String(v).replace('T', ' ').replace(/-/g, '/');
    var d = new Date(s);
    return isNaN(d.getTime()) ? null : d;
  }

  /* ========================================================================
   * 3. SurgeryFee 手麻记费工作台(手术选择 + 费用明细 + 耗材明细 + 汇总饼图)
   * 用法: 由 SurgeryManage 以 :surgery-id 传入(锁定手术); 或独立菜单打开(自由选择手术)。
   * ====================================================================== */
  HIS.views.SurgeryFee = {
    mixins: [surgMixin],
    props: {
      surgeryId: { type: Number, default: null }
    },
    template: [
      '<div class="surg-fee">',
      '  <div class="toolbar" style="margin-bottom:12px">',
      '    <el-select v-if="!lock" v-model="curSid" filterable :loading="surLoading" placeholder="选择手术（患者 - 手术名 - 日期）" style="width:380px" @change="loadAll">',
      '      <el-option v-for="s in surOptions" :key="s.id" :label="surLabel(s)" :value="s.id"></el-option>',
      '    </el-select>',
      '    <el-button type="warning" :loading="autoLoading" :disabled="!curSid" @click="autoCalc">自动计费(按手术时长)</el-button>',
      '    <el-button :disabled="!curSid" @click="loadAll">刷新</el-button>',
      '  </div>',
      '  <div v-if="surgery" class="surg-info" style="margin-bottom:14px">',
      '    患者: <b>{{ patient ? patient.name : "-" }}</b>　{{ surgery.surgeryName }}　主刀: {{ teamName("surgeon") }}　',
      '    排程: {{ fmtDT(surgery.scheduleDate) }}　实际: {{ fmtDT(surgery.startTime) }} ~ {{ fmtDT(surgery.endTime) }}　',
      '    时长: {{ durText }}　状态: <el-tag size="small" :type="stTag(surgery.status)">{{ stLabel(surgery.status) }}</el-tag>',
      '    <el-tag v-if="surgery.visitType === 2 || surgery.visitType === 3" size="small" type="warning" style="margin-left:6px">门诊/日间</el-tag>',
      '  </div>',
      '  <div v-if="surgery && (surgery.visitType === 2 || surgery.visitType === 3)" class="surg-info" style="margin-bottom:14px;border-color:var(--yb-warning)">门诊/日间手术费用已双写门诊收费单据(his_order), 请引导患者到收费处缴费或医生站诊间结算</div>',
      '  <el-empty v-if="!curSid" description="请先选择手术"></el-empty>',
      '  <template v-else>',
      '    <div class="surg-section-title">费用明细</div>',
      '    <div class="toolbar" style="margin-bottom:8px">',
      '      <el-radio-group v-model="catFilter" size="small">',
      '        <el-radio-button :label="0">全部</el-radio-button>',
      '        <el-radio-button v-for="o in feeCatOpts" :key="o.value" :label="o.value">{{ o.label }}</el-radio-button>',
      '      </el-radio-group>',
      '      <el-button type="primary" size="small" @click="openFeeAdd">添加费用</el-button>',
      '      <el-button type="success" size="small" plain @click="openTplDlg">导入费用模板</el-button>',
      '      <span style="font-size:12px;color:var(--yb-ink-3)">模板按 个人→科室→全院 可见性排序</span>',
      '    </div>',
      '    <el-table :data="filteredFees" border size="small" v-loading="loading">',
      '      <el-table-column prop="itemName" label="项目名称" min-width="200"></el-table-column>',
      '      <el-table-column prop="itemCode" label="编码" width="130"></el-table-column>',
      '      <el-table-column label="分类" width="96" align="center"><template #default="s"><span class="surg-cat-dot" :style="{ background: feeCatColor(s.row.feeCategory) }"></span>{{ feeCatLabel(s.row.feeCategory) }}</template></el-table-column>',
      '      <el-table-column label="数量" width="76" align="right"><template #default="s">{{ qtyText(s.row.quantity) }}</template></el-table-column>',
      '      <el-table-column label="单价" width="90" align="right"><template #default="s">{{ money(s.row.unitPrice) }}</template></el-table-column>',
      '      <el-table-column label="金额" width="100" align="right"><template #default="s"><span class="surg-money" :class="{ \'surg-money-red\': s.row.status === 2 }">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '      <el-table-column label="计费方式" width="110" align="center"><template #default="s">{{ autoText(s.row) }}</template></el-table-column>',
      '      <el-table-column label="操作" width="80" align="center"><template #default="s"><el-button v-if="s.row.status !== 2" link type="danger" @click="refundFee(s.row)">退费</el-button><span v-else style="color:var(--yb-ink-4)">已退</span></template></el-table-column>',
      '    </el-table>',
      '    <div class="surg-section-title" style="margin-top:16px">耗材明细</div>',
      '    <div class="toolbar" style="margin-bottom:8px">',
      '      <el-button type="primary" size="small" @click="openMatAdd">添加耗材</el-button>',
      '      <span style="color:var(--yb-ink-3);font-size:12px">高值耗材逐台登记(批号/供应商可追溯), 登记后同步计入住院费用</span>',
      '    </div>',
      '    <el-table :data="mats" border size="small">',
      '      <el-table-column prop="materialName" label="耗材名称" min-width="180"></el-table-column>',
      '      <el-table-column prop="materialCode" label="编码" width="120"></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="110"></el-table-column>',
      '      <el-table-column prop="batchNo" label="批号" width="110"></el-table-column>',
      '      <el-table-column label="数量" width="70" align="right"><template #default="s">{{ qtyText(s.row.quantity) }}</template></el-table-column>',
      '      <el-table-column label="单价" width="90" align="right"><template #default="s">{{ money(s.row.unitPrice) }}</template></el-table-column>',
      '      <el-table-column label="金额" width="100" align="right"><template #default="s"><span class="surg-money">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '      <el-table-column prop="supplier" label="供应商" min-width="130"></el-table-column>',
      '      <el-table-column label="操作" width="80" align="center"><template #default="s"><el-button link type="danger" @click="returnMat(s.row)">退回</el-button></template></el-table-column>',
      '    </el-table>',
      '    <div class="surg-section-title" style="margin-top:16px">费用汇总</div>',
      '    <el-row :gutter="14">',
      '      <el-col :span="12">',
      '        <div ref="pieChart" class="surg-chart"></div>',
      '      </el-col>',
      '      <el-col :span="12">',
      '        <el-table :data="sumRows" border size="small">',
      '          <el-table-column label="费用分类" min-width="130"><template #default="s"><span class="surg-cat-dot" :style="{ background: feeCatColor(s.row.cat) }"></span>{{ s.row.name }}</template></el-table-column>',
      '          <el-table-column label="金额(元)" width="140" align="right"><template #default="s"><span class="surg-money">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '        </el-table>',
      '        <div class="surg-info" style="margin-top:10px">',
      '          耗材合计: <span class="surg-money">¥{{ money(matTotal) }}</span>　',
      '          费用总计: <span class="surg-money" style="font-size:16px;color:var(--yb-gold)">¥{{ money(grandTotal) }}</span>',
      '        </div>',
      '      </el-col>',
      '    </el-row>',
      '  </template>',
      '  <el-dialog v-model="feeDialog" title="添加费用" width="580px" append-to-body>',
      '    <el-form :model="feeForm" label-width="96px">',
      '      <el-form-item label="费用分类" required>',
      '        <el-select v-model="feeForm.feeCategory" style="width:100%">',
      '          <el-option v-for="o in feeCatOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="收费项目" required>',
      '        <el-select v-model="feeForm.chargeItemId" filterable remote reserve-keyword :remote-method="searchItems" :loading="itemLoading" placeholder="输入名称 / 编码 / 拼音检索收费项目" style="width:100%" @change="onPickItem">',
      '          <el-option v-for="it in itemOptions" :key="it.id" :label="itemLabel(it)" :value="it.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="数量"><el-input-number v-model="feeForm.quantity" :min="0.01" :precision="2" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="单价(元)"><el-input-number v-model="feeForm.unitPrice" :min="0" :precision="2" style="width:100%"></el-input-number></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="金额(元)">',
      '        <span class="surg-money" style="font-size:16px">¥{{ feeFormAmount }}</span>',
      '        <span style="color:var(--yb-ink-3);font-size:12px;margin-left:8px">按数量 × 单价自动计算</span>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="feeDialog = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitFee">确认计费</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="matDialog" title="添加耗材" width="620px" append-to-body>',
      '    <el-form :model="matForm" label-width="96px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="耗材名称" required><el-input v-model="matForm.materialName" maxlength="100" placeholder="耗材名称"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="编码"><el-input v-model="matForm.materialCode" maxlength="50" placeholder="耗材编码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规格"><el-input v-model="matForm.spec" maxlength="50" placeholder="规格型号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="批号"><el-input v-model="matForm.batchNo" maxlength="50" placeholder="生产批号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="数量"><el-input-number v-model="matForm.quantity" :min="0.01" :precision="2" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="单价(元)"><el-input-number v-model="matForm.unitPrice" :min="0" :precision="2" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="供应商"><el-input v-model="matForm.supplier" maxlength="100" placeholder="供应商"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="金额(元)">',
      '        <span class="surg-money" style="font-size:16px">¥{{ matFormAmount }}</span>',
      '        <span style="color:var(--yb-ink-3);font-size:12px;margin-left:8px">按数量 × 单价自动计算</span>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="matDialog = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitMat">确认登记</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ===== 手麻P0: 费用模板导入 ===== */
      '  <el-dialog v-model="tplDialog" title="导入费用模板" width="680px" append-to-body>',
      '    <el-select v-model="tplPickId" filterable placeholder="选择可见模板(个人/科室/全院)" style="width:100%" @change="onTplPick" :loading="tplLoading">',
      '      <el-option v-for="(t, i) in tplList" :key="i" :label="tplText(t)" :value="i"></el-option>',
      '    </el-select>',
      '    <template v-if="tplPick">',
      '      <div class="surg-info" style="margin:10px 0">手术: {{ orDash(tplPick.tpl.surgeryName) }}　共 {{ (tplPick.items || []).length }} 项, 合计 ¥{{ money(tplPickSum) }}</div>',
      '      <el-table :data="tplPick.items || []" border size="small" max-height="300">',
      '        <el-table-column prop="itemName" label="项目" min-width="160"></el-table-column>',
      '        <el-table-column prop="itemCode" label="编码" width="120"></el-table-column>',
      '        <el-table-column label="分类" width="90" align="center"><template #default="s">{{ feeCatLabel(s.row.feeCategory) }}</template></el-table-column>',
      '        <el-table-column label="数量" width="70" align="right"><template #default="s">{{ qtyText(s.row.quantity) }}</template></el-table-column>',
      '        <el-table-column label="单价" width="90" align="right"><template #default="s">{{ money(s.row.unitPrice) }}</template></el-table-column>',
      '        <el-table-column label="金额" width="100" align="right"><template #default="s"><span class="surg-money">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '      </el-table>',
      '    </template>',
      '    <el-empty v-else description="选择模板后预览明细" :image-size="60"></el-empty>',
      '    <template #footer>',
      '      <el-button @click="tplDialog = false">取消</el-button>',
      '      <el-button type="primary" :disabled="!tplPick" :loading="tplApplying" @click="doTplApply">一键导入记账</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        curSid: this.surgeryId,
        surOptions: [],
        surLoading: false,
        surgery: null,
        patient: null,
        team: {},
        fees: [],
        mats: [],
        feeSummary: null,
        matSummary: null,
        catFilter: 0,
        feeDialog: false,
        feeForm: { feeCategory: 1, chargeItemId: null, itemName: '', itemCode: '', unitPrice: null, quantity: 1 },
        itemOptions: [],
        itemLoading: false,
        matDialog: false,
        matForm: { materialName: '', materialCode: '', spec: '', batchNo: '', quantity: 1, unitPrice: null, supplier: '' },
        loading: false,
        saving: false,
        autoLoading: false,
        pieChart: null,
        feeCatOpts: FEE_CAT_OPTS,
        /* 手麻P0: 模板导入 */
        tplDialog: false, tplList: [], tplLoading: false, tplPickId: null, tplApplying: false
      };
    },
    computed: {
      lock: function () { return this.surgeryId !== null && this.surgeryId !== undefined; },
      filteredFees: function () {
        var f = this.catFilter;
        if (!f) { return this.fees || []; }
        return (this.fees || []).filter(function (x) { return x.feeCategory === f; });
      },
      /* 手术时长(未结束按当前时间滚动计算) */
      durText: function () {
        var s = this.surgery;
        var start = s ? parseTs(s.startTime) : null;
        if (!start) { return '未开始'; }
        var end = s.endTime ? parseTs(s.endTime) : new Date();
        var mins = Math.round((end - start) / 60000);
        return (mins > 0 ? mins : 0) + ' 分钟';
      },
      /* 汇总分类行: 优先服务端 summary.items, 缺失时按本地未退费行回退聚合 */
      sumRows: function () {
        var s = this.feeSummary;
        var items = null;
        if (s && Array.isArray(s.items)) { items = s.items; }
        else if (Array.isArray(s)) { items = s; }
        if (!items || !items.length) {
          var map = {};
          (this.fees || []).forEach(function (f) {
            if (f.status === 2) { return; }
            var c = f.feeCategory || 6;
            map[c] = (map[c] || 0) + Number(f.amount || 0);
          });
          items = Object.keys(map).map(function (k) {
            return { feeCategory: Number(k), totalAmount: map[k] };
          });
        }
        return items.map(function (it) {
          var cat = it.feeCategory;
          return { cat: cat, name: it.feeCategoryName || feeCatLabel(cat), amount: Number(it.totalAmount || 0) };
        });
      },
      matTotal: function () {
        return Number((this.matSummary && this.matSummary.totalAmount) || 0);
      },
      feeSum: function () {
        var t = 0;
        this.sumRows.forEach(function (r) { t += Number(r.amount || 0); });
        return t;
      },
      grandTotal: function () { return this.feeSum + this.matTotal; },
      /* 饼图数据: 分类占比(耗材合计并入耗材费一块) */
      pieData: function () {
        var rows = this.sumRows.map(function (r) {
          return { name: r.name, value: Number(r.amount || 0), itemStyle: { color: feeCatColor(r.cat) } };
        }).filter(function (r) { return r.value > 0; });
        var mt = this.matTotal;
        if (mt > 0) {
          var hit = false;
          for (var i = 0; i < rows.length; i++) {
            if (rows[i].name === feeCatLabel(4)) { rows[i].value += mt; hit = true; break; }
          }
          if (!hit) { rows.push({ name: feeCatLabel(4), value: mt, itemStyle: { color: feeCatColor(4) } }); }
        }
        return rows;
      },
      feeFormAmount: function () {
        return (Number(this.feeForm.quantity || 0) * Number(this.feeForm.unitPrice || 0)).toFixed(2);
      },
      matFormAmount: function () {
        return (Number(this.matForm.quantity || 0) * Number(this.matForm.unitPrice || 0)).toFixed(2);
      },
      /* 手麻P0: 选中模板与明细合计 */
      tplPick: function () {
        return (this.tplPickId === null || this.tplPickId === undefined) ? null : (this.tplList[this.tplPickId] || null);
      },
      tplPickSum: function () {
        var t = 0;
        ((this.tplPick && this.tplPick.items) || []).forEach(function (it) { t += Number(it.amount || 0); });
        return t;
      }
    },
    watch: {
      surgeryId: function (v) {
        this.curSid = v;
        if (v) { this.loadAll(); }
      }
    },
    methods: {
      loadSurgeries: function () {
        var vm = this;
        vm.surLoading = true;
        var q = '/api/his/surgery/list?page=1&size=100';
        var org = HIS.currentOrgId();
        if (org) { q += '&orgId=' + org; }
        HIS.get(q).then(function (d) {
          vm.surOptions = (d && d.records) || [];
          vm.surLoading = false;
        }).catch(function (e) { vm.surLoading = false; HIS.notifyError(e); });
      },
      surLabel: function (s) {
        return orDash(s.patient_name) + ' - ' + orDash(s.surgery_name) + ' - ' + fmtDate(s.schedule_date);
      },
      teamName: function (key) {
        var t = this.team ? this.team[key] : null;
        return (t && t.name) ? t.name : '-';
      },
      qtyText: function (v) {
        if (v === null || v === undefined || v === '') { return '-'; }
        var n = Number(v);
        if (isNaN(n)) { return String(v); }
        return Math.round(n) === n ? String(Math.round(n)) : String(n);
      },
      autoText: function (row) {
        if (row.status === 2) { return '已退费'; }
        if (row.autoFlag === 1) {
          return row.durationMinutes ? ('自动·' + row.durationMinutes + '分钟') : '自动计时';
        }
        return '手动';
      },
      loadAll: function () {
        var vm = this;
        if (!vm.curSid) { return; }
        vm.loading = true;
        Promise.all([
          HIS.get('/api/his/surgery/' + vm.curSid),
          HIS.get('/api/his/surgery-fee/list/' + vm.curSid),
          HIS.get('/api/his/surgery-material/list/' + vm.curSid),
          HIS.get('/api/his/surgery-fee/summary/' + vm.curSid),
          HIS.get('/api/his/surgery-material/summary/' + vm.curSid)
        ]).then(function (rs) {
          var d = rs[0] || {};
          vm.surgery = d.surgery || null;
          vm.patient = d.patient || null;
          vm.team = d.team || {};
          vm.fees = rs[1] || [];
          vm.mats = rs[2] || [];
          vm.feeSummary = rs[3] || null;
          vm.matSummary = rs[4] || null;
          vm.loading = false;
          vm.$nextTick(function () { vm.renderPie(); });
        }).catch(function (e) { vm.loading = false; HIS.notifyError(e); });
      },
      /* ===== 费用明细 ===== */
      openFeeAdd: function () {
        if (!this.curSid) { return; }
        this.feeForm = { feeCategory: this.catFilter || 1, chargeItemId: null, itemName: '', itemCode: '', unitPrice: null, quantity: 1 };
        this.itemOptions = [];
        this.feeDialog = true;
        this.searchItems('');
      },
      searchItems: function (kw) {
        var vm = this;
        vm.itemLoading = true;
        HIS.get('/api/org-catalog/available/charge?page=1&size=30&keyword=' + encodeURIComponent(kw || ''))
          .then(function (d) {
            vm.itemOptions = (d && d.records) || [];
            vm.itemLoading = false;
          }).catch(function (e) { vm.itemLoading = false; HIS.notifyError(e); });
      },
      itemLabel: function (it) {
        var p = (it.execPrice !== null && it.execPrice !== undefined) ? it.execPrice : it.price;
        return it.itemName + (it.itemCode ? '（' + it.itemCode + '）' : '')
          + ((p !== null && p !== undefined) ? '  ¥' + Number(p).toFixed(2) : '');
      },
      onPickItem: function (id) {
        for (var i = 0; i < this.itemOptions.length; i++) {
          var it = this.itemOptions[i];
          if (it.id === id) {
            this.feeForm.itemName = it.itemName;
            this.feeForm.itemCode = it.itemCode;
            var p = (it.execPrice !== null && it.execPrice !== undefined) ? it.execPrice : it.price;
            if (p !== null && p !== undefined) { this.feeForm.unitPrice = Number(p); }
            break;
          }
        }
      },
      submitFee: function () {
        var vm = this;
        var f = vm.feeForm;
        var it = null;
        for (var i = 0; i < vm.itemOptions.length; i++) {
          if (vm.itemOptions[i].id === f.chargeItemId) { it = vm.itemOptions[i]; break; }
        }
        if (!it) { HIS.notifyError('请选择收费项目'); return; }
        if (f.quantity === null || f.quantity === undefined || Number(f.quantity) <= 0) { HIS.notifyError('数量必须大于0'); return; }
        if (f.unitPrice === null || f.unitPrice === undefined || Number(f.unitPrice) < 0) { HIS.notifyError('单价不能为空'); return; }
        vm.saving = true;
        HIS.post('/api/his/surgery-fee', {
          surgeryId: vm.curSid,
          inpVisitId: vm.surgery ? vm.surgery.inpVisitId : null,
          chargeItemId: f.chargeItemId,
          itemName: it.itemName,
          itemCode: it.itemCode,
          feeCategory: f.feeCategory,
          quantity: f.quantity,
          unitPrice: f.unitPrice,
          amount: Number((Number(f.quantity) * Number(f.unitPrice)).toFixed(2)),
          autoFlag: 0
        }).then(function () {
          vm.saving = false;
          vm.feeDialog = false;
          HIS.notifySuccess('费用已添加');
          vm.loadAll();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      refundFee: function (row) {
        var vm = this;
        confirmBox('确认对【' + escHtml(row.itemName) + '】退费 ' + money(row.amount) + ' 元？退费后将同步冲减住院费用。', '退费确认')
          .then(function () { return HIS.del('/api/his/surgery-fee/' + row.id); })
          .then(function () {
            HIS.notifySuccess('已退费');
            vm.loadAll();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      autoCalc: function () {
        var vm = this;
        confirmBox('将按手术实际时长自动生成计时计费记录(手术费/麻醉费/监测费), 确认执行？', '自动计费')
          .then(function () { vm.autoLoading = true; return HIS.post('/api/his/surgery-fee/auto-calc', { surgeryId: vm.curSid }); })
          .then(function (r) {
            vm.autoLoading = false;
            if (r && r.fee) {
              var d = r.durationMinutes === null || r.durationMinutes === undefined ? '-' : r.durationMinutes;
              HIS.notifySuccess('已生成计时费用: 时长' + d + '分钟, 金额¥' + money(r.fee.amount));
            } else {
              HIS.notifySuccess('自动计费已执行');
            }
            vm.loadAll();
          })
          .catch(function (e) { vm.autoLoading = false; if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 耗材明细 ===== */
      openMatAdd: function () {
        if (!this.curSid) { return; }
        this.matForm = { materialName: '', materialCode: '', spec: '', batchNo: '', quantity: 1, unitPrice: null, supplier: '' };
        this.matDialog = true;
      },
      submitMat: function () {
        var vm = this;
        var f = vm.matForm;
        if (!f.materialName) { HIS.notifyError('请填写耗材名称'); return; }
        if (f.quantity === null || f.quantity === undefined || Number(f.quantity) <= 0) { HIS.notifyError('数量必须大于0'); return; }
        if (f.unitPrice === null || f.unitPrice === undefined || Number(f.unitPrice) < 0) { HIS.notifyError('单价不能为空'); return; }
        vm.saving = true;
        HIS.post('/api/his/surgery-material', {
          surgeryId: vm.curSid,
          materialName: f.materialName,
          materialCode: f.materialCode,
          spec: f.spec,
          batchNo: f.batchNo,
          quantity: f.quantity,
          unitPrice: f.unitPrice,
          amount: Number((Number(f.quantity) * Number(f.unitPrice)).toFixed(2)),
          supplier: f.supplier
        }).then(function () {
          vm.saving = false;
          vm.matDialog = false;
          HIS.notifySuccess('耗材已登记');
          vm.loadAll();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      returnMat: function (row) {
        var vm = this;
        confirmBox('确认退回耗材【' + escHtml(row.materialName) + '】？退回后将联动退费并冲减住院费用。', '耗材退回')
          .then(function () { return HIS.del('/api/his/surgery-material/' + row.id); })
          .then(function () {
            HIS.notifySuccess('耗材已退回');
            vm.loadAll();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 手麻P0: 费用模板导入 ===== */
      openTplDlg: function () {
        var vm = this;
        if (!vm.curSid) { return; }
        vm.tplPickId = null;
        vm.tplDialog = true;
        vm.tplLoading = true;
        HIS.get('/api/his/surgery-fee-tpl/list').then(function (l) {
          vm.tplList = l || [];
          vm.tplLoading = false;
        }).catch(function (e) { vm.tplLoading = false; HIS.notifyError(e); });
      },
      tplText: function (m) {
        var t = (m && m.tpl) || {};
        var sum = 0;
        ((m && m.items) || []).forEach(function (it) { sum += Number(it.amount || 0); });
        return '[' + tplLevelLabel(t.tplLevel) + '] ' + orDash(t.tplName)
          + ' · ' + ((m.items || []).length) + '项 ¥' + money(sum)
          + (t.surgeryName ? (' · ' + t.surgeryName) : '');
      },
      onTplPick: function () { /* 预览由 computed 驱动 */ },
      doTplApply: function () {
        var vm = this;
        var p = vm.tplPick;
        if (!p) { return; }
        confirmBox('确认将模板【' + escHtml(p.tpl.tplName) + '】的 ' + ((p.items || []).length) + ' 项明细一键导入记账？住院记入住院费用, 门诊/日间双写门诊收费单据。', '导入模板')
          .then(function () {
            vm.tplApplying = true;
            return HIS.post('/api/his/surgery-fee-tpl/apply?surgeryId=' + vm.curSid + '&tplId=' + p.tpl.id);
          }).then(function (r) {
            vm.tplApplying = false;
            vm.tplDialog = false;
            HIS.notifySuccess('已导入 ' + ((r && r.count) || (p.items || []).length) + ' 项费用');
            vm.loadAll();
          }).catch(function (e) { vm.tplApplying = false; if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 汇总饼图 ===== */
      renderPie: function () {
        var vm = this;
        var dom = vm.$refs.pieChart;
        if (!window.echarts || !dom) { return; }
        if (vm.pieChart) { vm.pieChart.dispose(); vm.pieChart = null; }
        var data = vm.pieData;
        if (!data.length) { return; }
        var T = HIS.theme || {};
        var chart = window.echarts.init(dom, 'yb');
        vm.pieChart = chart;
        chart.setOption({
          tooltip: T.tooltip ? T.tooltip({ trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' }) : { trigger: 'item' },
          legend: T.legend ? T.legend({ bottom: 0, left: 'center', type: 'scroll' }) : { bottom: 0, left: 'center' },
          series: [{
            type: 'pie',
            radius: ['38%', '62%'],
            center: ['50%', '44%'],
            itemStyle: { borderColor: '#fff', borderWidth: 2 },
            label: { color: (T.ink3 || '#5a6a7e'), fontSize: 11, formatter: '{b} {d}%' },
            data: data
          }]
        });
        setTimeout(function () {
          try { if (vm.pieChart && !vm.pieChart.isDisposed()) { vm.pieChart.resize(); } } catch (e) { }
        }, 320);
      },
      onResize: function () {
        try { if (this.pieChart && !this.pieChart.isDisposed()) { this.pieChart.resize(); } } catch (e) { }
      }
    },
    mounted: function () {
      var vm = this;
      window.addEventListener('resize', vm.onResize);
      if (!vm.lock) { vm.loadSurgeries(); }
      if (vm.curSid) { vm.loadAll(); }
    },
    unmounted: function () {
      window.removeEventListener('resize', this.onResize);
      if (this.pieChart) { this.pieChart.dispose(); this.pieChart = null; }
    }
  };

  /* 新增/编辑手术表单空白模板 */
  function blankSurgForm() {
    return {
      inpVisitId: null,
      surgeryCode: '',
      surgeryName: '',
      surgeryLevel: null,
      surgeonId: null,
      firstAssistantId: null,
      secondAssistantId: null,
      anesthesiologistId: null,
      anesthesiaNurseId: null,
      instrumentNurseId: null,
      circulatingNurseId: null,
      deptId: null,
      asaGrade: null,
      incisionType: null,
      scheduleDate: '',
      scheduleTime: '',
      roomNo: ''
    };
  }

  /* ========================================================================
   * 1. SurgeryManage 手术管理主页
   * Tab: 手术未安排 / 手术已安排(列表+排程板+登记报到) / 手术已完成 / 麻醉已安排
   *      / 手术列表(全量) / 今日看板
   * 状态机: 1申请→2排程→7已报到→3术中→4术后→5完成 / 6取消(申请/排程/报到可退回)
   * 手麻P0: 申请池安排(schedule-from-apply)/急诊直排(urgent)/报到(check-in)/调配(transfer)
   *          退回(cancel-schedule)/取消完成(cancel-complete)/排程板拖拽(reschedule)
   * 麻醉记录/记费 以抽屉承载 AnesthesiaRecord / SurgeryFee 子组件(传 surgery-id 锁定)。
   * ====================================================================== */
  HIS.views.SurgeryManage = {
    mixins: [surgMixin],
    components: {
      'anesthesia-record': HIS.views.AnesthesiaRecord,
      'surgery-fee': HIS.views.SurgeryFee
    },
    template: [
      '<div class="surg-manage">',
      '  <div class="page-title">手术管理</div>',
      '  <el-tabs v-model="tab" @tab-change="onTabChange">',
      '    <el-tab-pane label="手术列表" name="list">',
      '      <div class="toolbar" style="margin-bottom:10px">',
      '        <el-select v-model="filters.deptId" clearable filterable placeholder="科室" size="small" style="width:150px">',
      '          <el-option v-for="d in refDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '        </el-select>',
      '        <el-select v-model="filters.moduleType" clearable placeholder="一体化模块" size="small" style="width:130px">',
      '          <el-option v-for="o in moduleOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '        <el-date-picker v-model="filters.dateRange" type="daterange" size="small" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '        <el-select v-model="filters.status" clearable placeholder="状态" size="small" style="width:110px">',
      '          <el-option v-for="o in statusOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '        <el-input v-model="filters.kw" clearable placeholder="本页搜索: 姓名/手术/医师/住院号" size="small" style="width:230px"></el-input>',
      '        <el-button type="primary" size="small" @click="onSearch">查询</el-button>',
      '        <el-button type="primary" size="small" plain @click="openCreate">新增手术</el-button>',
      '      </div>',
      '      <el-table :data="filteredRows" border size="small" v-loading="loading" max-height="calc(100vh - 240px)">',
      '        <el-table-column label="序号" width="56" align="center"><template #default="s">{{ seqNo(s.$index) }}</template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者姓名" width="92"></el-table-column>',
      '        <el-table-column prop="inp_no" label="住院号" width="120"></el-table-column>',
      '        <el-table-column prop="surgery_name" label="手术名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="模块" width="82" align="center"><template #default="s">{{ moduleLabel(s.row.module_type) }}</template></el-table-column>',
      '        <el-table-column label="手术级别" width="82" align="center"><template #default="s">{{ levelLabel(s.row.surgery_level) }}</template></el-table-column>',
      '        <el-table-column prop="surgeon_name" label="主刀医师" width="92"></el-table-column>',
      '        <el-table-column prop="room_no" label="手术间" width="86"></el-table-column>',
      '        <el-table-column label="手术日期" width="106"><template #default="s">{{ fmtDate(s.row.schedule_date) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="stTag(s.row.status)">{{ stLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="256" fixed="right"><template #default="s">',
      '          <span class="surg-op">',
      '            <template v-if="s.row.status === 1">',
      '              <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '              <el-button link type="primary" @click="openSchedule(s.row)">排程</el-button>',
      '              <el-button link type="danger" @click="doCancel(s.row)">取消</el-button>',
      '            </template>',
      '            <template v-else-if="s.row.status === 2">',
      '              <el-button link type="success" @click="doStart(s.row)">开始手术</el-button>',
      '              <el-button link type="danger" @click="doCancel(s.row)">取消</el-button>',
      '            </template>',
      '            <template v-else-if="s.row.status === 3">',
      '              <el-button link type="warning" @click="doEnd(s.row)">结束手术</el-button>',
      '              <el-button link type="primary" @click="openAne(s.row.id)">麻醉记录</el-button>',
      '              <el-button link type="primary" @click="openFee(s.row.id)">记费</el-button>',
      '            </template>',
      '            <template v-else-if="s.row.status === 4">',
      '              <el-button link type="success" @click="doComplete(s.row)">完成</el-button>',
      '              <el-button link type="primary" @click="openAne(s.row.id)">麻醉记录</el-button>',
      '              <el-button link type="primary" @click="openFee(s.row.id)">记费</el-button>',
      '            </template>',
      '            <template v-else>',
      '              <el-button link type="primary" @click="openDetail(s.row.id)">查看</el-button>',
      '              <el-button v-if="s.row.status === 5" link type="primary" @click="openFee(s.row.id)">记费</el-button>',
      '            </template>',
      '          </span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </el-tab-pane>',
      /* ================= 手麻P0: 手术未安排(申请待安排池) ================= */
      '    <el-tab-pane name="unarranged">',
      '      <template #label><span>手术未安排<el-badge v-if="unarrangedRows.length > 0" :value="unarrangedRows.length" style="margin-left:6px"/></span></template>',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">',
      '        <span style="font-size:13px;color:var(--yb-ink-2)">病区已复核待安排的手术申请, 按时限(急诊优先)排序; 手术安排入口以手术室为主</span>',
      '        <el-button type="primary" size="small" plain @click="loadUnarranged">刷新</el-button>',
      '        <el-button type="danger" size="small" plain @click="openUrgent" v-if="canArrange">急诊直接安排</el-button>',
      '      </div>',
      '      <el-table :data="unarrangedRows" border size="small" v-loading="unarrangedLoading" max-height="calc(100vh - 268px)">',
      '        <el-table-column label="序号" width="52" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '        <el-table-column prop="apply_no" label="申请单号" width="140"></el-table-column>',
      '        <el-table-column label="类型" width="58" align="center"><template #default="s"><el-tag size="small" :type="s.row.visit_type === 1 ? \'\' : \'warning\'">{{ vtLabel(s.row.visit_type) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者" width="84"></el-table-column>',
      '        <el-table-column label="病历号" width="110"><template #default="s">{{ orDash(s.row.medical_no) }}</template></el-table-column>',
      '        <el-table-column prop="surgery_name" label="手术名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="级别" width="60" align="center"><template #default="s">{{ levelLabel(s.row.surgery_level) }}</template></el-table-column>',
      '        <el-table-column prop="surgeon_name" label="拟主刀" width="84"><template #default="s">{{ orDash(s.row.surgeon_name) }}</template></el-table-column>',
      '        <el-table-column label="期望时间" width="130"><template #default="s">{{ fmtDT(s.row.expect_time) }}</template></el-table-column>',
      '        <el-table-column label="时限" width="58" align="center"><template #default="s"><el-tag size="small" :type="dlTag(s.row.deadline_type)">{{ dlLabel(s.row.deadline_type) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="apply_dept_name" label="申请科室" width="100" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="操作" width="110" align="center" fixed="right"><template #default="s"><el-button v-if="canArrange" link type="primary" @click="openSchedApply(s.row)">安排手术</el-button></template></el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!unarrangedLoading && !unarrangedRows.length" description="暂无待安排申请" :image-size="60"></el-empty>',
      '    </el-tab-pane>',
      /* ================= 手麻P0: 手术已安排(报到/流转/调配 + 列表/排程板) ================= */
      '    <el-tab-pane label="手术已安排" name="arranged">',
      '      <div class="surg-info" style="margin-bottom:10px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">',
      '        <b style="white-space:nowrap">登记报到</b>',
      '        <el-input v-model="regKw" clearable placeholder="申请单号(SQ…)/病历号/姓名, 回车检索" size="small" style="width:280px" @keyup.enter="doRegSearch"></el-input>',
      '        <el-button type="primary" size="small" @click="doRegSearch">检索</el-button>',
      '        <el-button v-if="pendingNotify > 0" size="small" type="warning" plain @click="goNotify">待处理通知 {{ pendingNotify }}</el-button>',
      '      </div>',
      '      <el-table v-if="regRows.length" :data="regRows" border size="small" highlight-current-row @current-change="onRegPick" style="margin-bottom:10px" max-height="220">',
      '        <el-table-column prop="apply_no" label="申请单号" width="140"><template #default="s">{{ orDash(s.row.apply_no) }}</template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者" width="90"></el-table-column>',
      '        <el-table-column label="病历号" width="110"><template #default="s">{{ orDash(s.row.inp_no) }}</template></el-table-column>',
      '        <el-table-column prop="surgery_name" label="手术名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="手术间/日期" width="140"><template #default="s">{{ orDash(s.row.room_no) }} {{ fmtDate(s.row.schedule_date) }}</template></el-table-column>',
      '        <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="stTag(s.row.status)">{{ stLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="100" align="center"><template #default="s">',
      '          <el-button v-if="s.row.status === 2 && canOps" link type="success" @click.stop="doCheckInRow(s.row)">报到登记</el-button>',
      '          <span v-else-if="s.row.status === 7" style="font-size:12px;color:var(--yb-ink-3)">已报到</span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">',
      '        <el-radio-group v-model="arrangedView" size="small" @change="onArrangedView">',
      '          <el-radio-button value="list">列表</el-radio-button><el-radio-button value="board">排程板</el-radio-button>',
      '        </el-radio-group>',
      '        <template v-if="arrangedView === \'list\'">',
      '          <el-date-picker v-model="arrFilters.dateRange" type="daterange" size="small" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:230px"></el-date-picker>',
      '          <el-select v-model="arrFilters.status" clearable placeholder="状态" size="small" style="width:110px">',
      '            <el-option :value="2" label="已排程"></el-option><el-option :value="7" label="已报到"></el-option>',
      '          </el-select>',
      '        </template>',
      '        <template v-else>',
      '          <el-date-picker v-model="boardDate" type="date" size="small" value-format="YYYY-MM-DD" :clearable="false" style="width:130px" @change="loadRoomBoard"></el-date-picker>',
      '          <span style="font-size:12px;color:var(--yb-ink-3)">拖拽手术卡片到其他手术间列即改排(reschedule), 时间冲突后端拒绝并提示</span>',
      '        </template>',
      '        <el-button type="primary" size="small" @click="refreshArranged">查询</el-button>',
      '      </div>',
      '      <template v-if="arrangedView === \'list\'">',
      '        <el-table :data="arrangedRows" border size="small" v-loading="arrangedLoading" max-height="calc(100vh - 388px)">',
      '          <el-table-column label="序号" width="52" align="center"><template #default="s">{{ (arrP - 1) * arrSize + s.$index + 1 }}</template></el-table-column>',
      '          <el-table-column prop="patient_name" label="患者" width="84"></el-table-column>',
      '          <el-table-column prop="inp_no" label="病历号" width="110"><template #default="s">{{ orDash(s.row.inp_no) }}</template></el-table-column>',
      '          <el-table-column label="类型" width="58" align="center"><template #default="s">{{ vtLabel(s.row.visit_type) }}</template></el-table-column>',
      '          <el-table-column prop="surgery_name" label="手术名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="room_no" label="手术间" width="76"><template #default="s">{{ orDash(s.row.room_no) }}</template></el-table-column>',
      '          <el-table-column label="日期" width="98"><template #default="s">{{ fmtDate(s.row.schedule_date) }}</template></el-table-column>',
      '          <el-table-column label="时间段" width="104"><template #default="s">{{ orDash(s.row.schedule_time) }}</template></el-table-column>',
      '          <el-table-column prop="surgeon_name" label="主刀" width="80"><template #default="s">{{ orDash(s.row.surgeon_name) }}</template></el-table-column>',
      '          <el-table-column label="报到时间" width="126"><template #default="s">{{ s.row.register_time ? fmtDT(s.row.register_time) : "-" }}</template></el-table-column>',
      '          <el-table-column label="状态" width="78" align="center"><template #default="s"><el-tag size="small" :type="stTag(s.row.status)">{{ stLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="操作" width="300" fixed="right"><template #default="s">',
      '            <span class="surg-op" v-if="canOps">',
      '              <el-button v-if="s.row.status === 2" link type="success" @click="doCheckIn(s.row)">报到</el-button>',
      '              <el-button v-if="s.row.status === 2 || s.row.status === 7" link type="primary" @click="doStart(s.row)">开始</el-button>',
      '              <el-button v-if="s.row.status === 3" link type="warning" @click="doEnd(s.row)">结束</el-button>',
      '              <el-button v-if="s.row.status === 4" link type="success" @click="doComplete(s.row)">完成</el-button>',
      '              <el-button v-if="s.row.status === 2 || s.row.status === 7" link type="warning" @click="openTransfer(s.row)">调配</el-button>',
      '              <el-button v-if="s.row.status === 2 || s.row.status === 7" link type="danger" @click="doCancelSchedule(s.row)">退回</el-button>',
      '            </span>',
      '            <el-button link type="primary" @click="openAne(s.row.id)">麻醉</el-button>',
      '            <el-button link type="primary" @click="openFee(s.row.id)">记费</el-button>',
      '            <el-button link type="primary" @click="openDetail(s.row.id)">查看</el-button>',
      '          </template></el-table-column>',
      '        </el-table>',
      '        <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, prev, pager, next" :total="arrTotal" :page-size="arrSize" :current-page="arrP" @current-change="onArrPage"></el-pagination>',
      '      </template>',
      '      <div v-else v-loading="rbLoading" class="surg-board-col" @dragover.prevent="onDragOver($event)" @drop="onDropToBoard($event, null)">',
      '        <el-table :data="rb.rooms || []" border size="small">',
      '          <el-table-column prop="roomNo" label="手术间" width="92" align="center"></el-table-column>',
      '          <el-table-column label="当日占用(时间顺序)" min-width="880">',
      '            <template #default="s">',
      '              <div class="surg-board-col" :class="{ \'drag-over\': dragOverRoom === s.row.roomNo }" @dragover.prevent="onDragOver($event, s.row.roomNo)" @dragleave="onDragLeave(s.row.roomNo)" @drop.stop="onDropToBoard($event, s.row.roomNo)">',
      '                <el-tag v-if="s.row.count > 0" size="small" type="info" style="margin:0 6px 6px 0">{{ s.row.count }} 台</el-tag>',
      '                <div v-for="c in (s.row.surgeries || [])" :key="c.id" class="surg-room-item surg-board-card" draggable="true" @dragstart="onDragStart(c)" @dragend="dragCard = null" @click="openDetail(c.id)">',
      '                  <div class="line1">{{ hmOf(c.schedule_time) || "待定" }}　{{ c.patient_name || "-" }}<el-tag size="small" :type="stTag(c.status)" style="margin-left:6px">{{ stLabel(c.status) }}</el-tag><el-tag v-if="c.visit_type !== 1" size="small" type="warning" style="margin-left:4px">{{ vtLabel(c.visit_type) }}</el-tag></div>',
      '                  <div class="line2">{{ c.surgery_name }}　主刀: {{ orDash(c.surgeon_name) }}</div>',
      '                  <div class="line3"><span>{{ orDash(c.apply_no) }}　{{ orDash(c.dept_name) }}</span></div>',
      '                </div>',
      '                <div v-if="!(s.row.surgeries || []).length" class="surg-room-empty">空手术间 — 可拖入卡片</div>',
      '              </div>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '      </div>',
      '    </el-tab-pane>',
      /* ================= 手麻P0: 手术已完成 ================= */
      '    <el-tab-pane label="手术已完成" name="finished">',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">',
      '        <el-date-picker v-model="finFilters.dateRange" type="daterange" size="small" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="完成日起" end-placeholder="完成日止" style="width:230px"></el-date-picker>',
      '        <el-select v-model="finFilters.visitType" clearable placeholder="就诊类型" size="small" style="width:110px">',
      '          <el-option :value="1" label="住院"></el-option><el-option :value="2" label="门诊"></el-option><el-option :value="3" label="日间"></el-option>',
      '        </el-select>',
      '        <el-input v-model="finFilters.kw" clearable placeholder="本页搜索: 姓名/手术/主刀/病历号" size="small" style="width:220px"></el-input>',
      '        <el-button type="primary" size="small" @click="loadFinished">查询</el-button>',
      '      </div>',
      '      <el-table :data="filteredFinished" border size="small" v-loading="finLoading" max-height="calc(100vh - 290px)">',
      '        <el-table-column label="序号" width="52" align="center"><template #default="s">{{ (finP - 1) * finSize + s.$index + 1 }}</template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者" width="84"></el-table-column>',
      '        <el-table-column prop="inp_no" label="病历号" width="110"><template #default="s">{{ orDash(s.row.inp_no) }}</template></el-table-column>',
      '        <el-table-column label="类型" width="58" align="center"><template #default="s">{{ vtLabel(s.row.visit_type) }}</template></el-table-column>',
      '        <el-table-column prop="surgery_name" label="手术名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="surgeon_name" label="主刀" width="80"><template #default="s">{{ orDash(s.row.surgeon_name) }}</template></el-table-column>',
      '        <el-table-column label="手术日期" width="98"><template #default="s">{{ fmtDate(s.row.schedule_date) }}</template></el-table-column>',
      '        <el-table-column label="开始/结束" width="140"><template #default="s">{{ hmOf(s.row.start_time) || "-" }} ~ {{ hmOf(s.row.end_time) || "-" }}</template></el-table-column>',
      '        <el-table-column label="操作" width="200" fixed="right"><template #default="scope">',
      '          <el-button link type="primary" @click="openFee(scope.row.id)">记费/补退</el-button>',
      '          <el-button link type="primary" @click="openAne(scope.row.id)">麻醉记录</el-button>',
      '          <el-button v-if="canOps" link type="danger" @click="doCancelComplete(scope.row)">取消完成</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, prev, pager, next" :total="finTotal" :page-size="finSize" :current-page="finP" @current-change="onFinPage"></el-pagination>',
      '    </el-tab-pane>',
      /* ================= 手麻P0: 麻醉已安排 ================= */
      '    <el-tab-pane label="麻醉已安排" name="anes">',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center">',
      '        <span style="font-size:13px;color:var(--yb-ink-2)">已排程/已报到且建麻醉记录的手术; 术前评估未填红标, 点击进入麻醉记录填写与调整</span>',
      '        <el-button type="primary" size="small" plain @click="loadAnes">刷新</el-button>',
      '      </div>',
      '      <el-table :data="anesRows" border size="small" v-loading="anesLoading" max-height="calc(100vh - 268px)">',
      '        <el-table-column label="序号" width="52" align="center"><template #default="s">{{ s.$index + 1 }}</template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者" width="84"><template #default="s">{{ orDash(s.row.patient_name) }}</template></el-table-column>',
      '        <el-table-column prop="inp_no" label="病历号" width="110"><template #default="s">{{ orDash(s.row.inp_no) }}</template></el-table-column>',
      '        <el-table-column prop="surgery_name" label="手术名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="手术间/日期" width="150"><template #default="s">{{ orDash(s.row.room_no) }} {{ fmtDate(s.row.schedule_date) }} {{ orDash(s.row.schedule_time) }}</template></el-table-column>',
      '        <el-table-column prop="anesthesiologist_name" label="麻醉医师" width="92"><template #default="s">{{ orDash(s.row.anesthesiologist_name) }}</template></el-table-column>',
      '        <el-table-column label="麻醉方式" width="96" align="center"><template #default="s">{{ aneTypeLabel(s.row.anesthesia_type) }}</template></el-table-column>',
      '        <el-table-column label="术前评估" width="86" align="center"><template #default="s"><el-tag size="small" :type="s.row.assessed ? \'success\' : \'danger\'">{{ s.row.assessed ? "已填" : "未填" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="状态" width="78" align="center"><template #default="s"><el-tag size="small" :type="stTag(s.row.status)">{{ stLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="120" align="center" fixed="right"><template #default="s"><el-button link type="primary" @click="openAne(s.row.id)">麻醉记录</el-button></template></el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!anesLoading && !anesRows.length" description="暂无麻醉已安排手术" :image-size="60"></el-empty>',
      '    </el-tab-pane>',
      /* ================= 手麻P3b: 复苏(PACU)工作台 ================= */
      '    <el-tab-pane label="复苏(PACU)" name="pacu">',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">',
      '        <el-select v-model="pacuFilters.status" clearable placeholder="状态" size="small" style="width:110px">',
      '          <el-option :value="1" label="复苏中"></el-option><el-option :value="2" label="已出"></el-option>',
      '        </el-select>',
      '        <el-input v-model="pacuFilters.kw" clearable placeholder="姓名/住院号" size="small" style="width:200px" @keyup.enter="loadPacu"></el-input>',
      '        <el-button type="primary" size="small" @click="loadPacu">刷新</el-button>',
      '      </div>',
      '      <el-table :data="pacuRows" border size="small" v-loading="pacuLoading" max-height="calc(100vh - 268px)">',
      '        <el-table-column label="序号" width="52" align="center"><template #default="s">{{ (pacuP - 1) * pacuSize + s.$index + 1 }}</template></el-table-column>',
      '        <el-table-column prop="patientName" label="患者" width="84"><template #default="s">{{ orDash(s.row.patientName) }}</template></el-table-column>',
      '        <el-table-column prop="inpNo" label="住院号" width="110"><template #default="s">{{ orDash(s.row.inpNo) }}</template></el-table-column>',
      '        <el-table-column prop="surgeryName" label="手术" min-width="140" show-overflow-tooltip><template #default="s">{{ orDash(s.row.surgeryName) }}</template></el-table-column>',
      '        <el-table-column label="入复苏" width="130"><template #default="s">{{ fmtDT(s.row.admitTime) }}</template></el-table-column>',
      '        <el-table-column label="Aldrete入" width="76" align="center"><template #default="s">{{ orDash(s.row.aldreteAdmit) }}</template></el-table-column>',
      '        <el-table-column label="体征" width="56" align="center"><template #default="s">{{ jsonArr(s.row.vitalsJson).length }}</template></el-table-column>',
      '        <el-table-column label="状态" width="72" align="center"><template #default="s"><el-tag size="small" :type="pacuStatusTag(s.row.status)">{{ pacuStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="180" align="center" fixed="right"><template #default="s">',
      '          <el-button v-if="s.row.status === 1" link type="primary" @click="openPacuVital(s.row)">体征登记</el-button>',
      '          <el-button v-if="s.row.status === 1" link type="success" @click="openPacuDischarge(s.row)">出复苏</el-button>',
      '          <el-button link type="info" @click="viewPacu(s.row)">查看</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, prev, pager, next" :total="pacuTotal" :page-size="pacuSize" :current-page="pacuP" @current-change="onPacuPage"></el-pagination>',
      '      <el-empty v-if="!pacuLoading && !pacuRows.length" description="暂无复苏记录" :image-size="60"></el-empty>',
      '    </el-tab-pane>',
      '    <el-tab-pane label="今日看板" name="board">',
      '      <div v-loading="boardLoading">',
      '        <div style="margin-bottom:10px;color:var(--yb-ink-2);font-size:13px">日期: {{ board.date || "—" }}　今日共 {{ board.total }} 台手术, 点击卡片查看手术详情</div>',
      '        <el-row :gutter="12">',
      '          <el-col v-for="r in board.rooms" :key="r.roomNo" :span="6" style="margin-bottom:12px">',
      '            <div class="surg-room-card">',
      '              <div class="surg-room-title"><span>{{ r.roomNo }}</span><span class="cnt">{{ r.count }} 台</span></div>',
      '              <div v-if="r.surgeries && r.surgeries.length">',
      '                <div v-for="s in r.surgeries" :key="s.id" class="surg-room-item" @click="openDetail(s.id)">',
      '                  <div class="line1">{{ hmOf(s.schedule_time) || "待定" }}　{{ s.patient_name || "-" }}</div>',
      '                  <div class="line2">{{ s.surgery_name }}</div>',
      '                  <div class="line3"><span>主刀: {{ s.surgeon_name || "-" }}</span><el-tag size="small" :type="stTag(s.status)">{{ stLabel(s.status) }}</el-tag></div>',
      '                </div>',
      '              </div>',
      '              <div v-else class="surg-room-empty">暂无排程</div>',
      '            </div>',
      '          </el-col>',
      '        </el-row>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '  <el-dialog v-model="dlgVisible" :title="dlgTitle" width="780px" :close-on-click-modal="false" append-to-body>',
      '    <el-steps :active="step" finish-status="success" simple style="margin-bottom:14px">',
      '      <el-step title="选择患者"></el-step>',
      '      <el-step title="手术信息"></el-step>',
      '      <el-step title="人员配置"></el-step>',
      '      <el-step title="排程(可选)"></el-step>',
      '    </el-steps>',
      '    <div v-show="step === 0">',
      '      <el-form label-width="90px">',
      '        <el-form-item label="选择患者" required>',
      '          <el-select v-model="form.inpVisitId" filterable remote reserve-keyword :remote-method="searchPatients" :loading="ptLoading" placeholder="输入姓名 / 住院号检索在院患者" style="width:100%" @change="onPickPatient">',
      '            <el-option v-for="p in patients" :key="p.id" :label="ptLabel(p)" :value="p.id"></el-option>',
      '          </el-select>',
      '        </el-form-item>',
      '      </el-form>',
      '      <div v-if="patientPicked" class="surg-info">',
      '        <b>{{ patientPicked.name }}</b>　{{ patientPicked.gender_name }}　{{ patientPicked.age }}岁　住院号: {{ patientPicked.inp_no }}　科室: {{ deptNameOf(patientPicked.dept_id) }}',
      '      </div>',
      '    </div>',
      '    <div v-show="step === 1">',
      '      <el-form :model="form" label-width="90px">',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="手术编码"><el-input v-model="form.surgeryCode" maxlength="50" placeholder="如 ICD-9-CM 手术编码"></el-input></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="手术名称" required><el-input v-model="form.surgeryName" maxlength="100" placeholder="手术名称"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="手术级别">',
      '            <el-select v-model="form.surgeryLevel" clearable placeholder="选择级别" style="width:100%">',
      '              <el-option v-for="o in surgLevelOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="ASA分级">',
      '            <el-select v-model="form.asaGrade" clearable placeholder="选择ASA分级" style="width:100%">',
      '              <el-option v-for="o in asaOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="切口类型">',
      '            <el-select v-model="form.incisionType" clearable placeholder="选择切口类型" style="width:100%">',
      '              <el-option v-for="o in incisionOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="手术科室">',
      '            <el-select v-model="form.deptId" clearable filterable placeholder="选择科室" style="width:100%">',
      '              <el-option v-for="d in refDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '    </div>',
      '    <div v-show="step === 2">',
      '      <el-form :model="form" label-width="90px">',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="主刀医师" required>',
      '            <el-select v-model="form.surgeonId" filterable clearable placeholder="选择医师" style="width:100%">',
      '              <el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="一助">',
      '            <el-select v-model="form.firstAssistantId" filterable clearable placeholder="选择医师" style="width:100%">',
      '              <el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="二助">',
      '            <el-select v-model="form.secondAssistantId" filterable clearable placeholder="选择医师" style="width:100%">',
      '              <el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="麻醉医师">',
      '            <el-select v-model="form.anesthesiologistId" filterable clearable placeholder="选择医师" style="width:100%">',
      '              <el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="麻醉护士">',
      '            <el-select v-model="form.anesthesiaNurseId" filterable clearable placeholder="选择护士" style="width:100%">',
      '              <el-option v-for="s in nurses" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="器械护士">',
      '            <el-select v-model="form.instrumentNurseId" filterable clearable placeholder="选择护士" style="width:100%">',
      '              <el-option v-for="s in nurses" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="巡回护士">',
      '            <el-select v-model="form.circulatingNurseId" filterable clearable placeholder="选择护士" style="width:100%">',
      '              <el-option v-for="s in nurses" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '    </div>',
      '    <div v-show="step === 3">',
      '      <el-form :model="form" label-width="90px">',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="手术日期">',
      '            <el-date-picker v-model="form.scheduleDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%"></el-date-picker>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="时间段">',
      '            <el-select v-model="form.scheduleTime" clearable placeholder="选择时间段" style="width:100%">',
      '              <el-option v-for="t in timeSlots" :key="t.value" :label="t.value" :value="t.value"></el-option>',
      '            </el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-form-item label="手术间">',
      '          <el-select v-model="form.roomNo" clearable placeholder="选择手术间" style="width:280px">',
      '            <el-option v-for="r in rooms" :key="r" :label="r" :value="r"></el-option>',
      '          </el-select>',
      '        </el-form-item>',
      '      </el-form>',
      '      <div style="color:var(--yb-ink-3);font-size:12px;margin-left:90px">排程为可选项: 不填则先保存为“申请”状态, 后续可单独排程</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button v-if="step > 0" @click="prevStep">上一步</el-button>',
      '      <el-button v-if="step < 3" type="primary" @click="nextStep">下一步</el-button>',
      '      <el-button v-if="step === 3 || editId" type="primary" :loading="saving" @click="submitForm">{{ editId ? "保存修改" : "提交" }}</el-button>',
      '      <el-button @click="dlgVisible = false">取消</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="schVisible" title="手术排程" width="500px" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:12px">{{ schTitle }}</div>',
      '    <el-form :model="schForm" label-width="90px">',
      '      <el-form-item label="手术日期" required>',
      '        <el-date-picker v-model="schForm.scheduleDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%"></el-date-picker>',
      '      </el-form-item>',
      '      <el-form-item label="时间段">',
      '        <el-select v-model="schForm.scheduleTime" clearable placeholder="选择时间段" style="width:100%">',
      '          <el-option v-for="t in timeSlots" :key="t.value" :label="t.value" :value="t.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="手术间">',
      '        <el-select v-model="schForm.roomNo" clearable placeholder="选择手术间" style="width:100%">',
      '          <el-option v-for="r in rooms" :key="r" :label="r" :value="r"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="schVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitSchedule">确认排程</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ================= 手麻P0: 申请池安排 / 急诊直排 / 调配 / 拖拽改排 ================= */
      '  <el-dialog v-model="schedApplyVisible" :title="schedUrgent ? \'急诊直接安排\' : \'安排手术\'" width="720px" :close-on-click-modal="false" append-to-body>',
      '    <div v-if="!schedUrgent" class="surg-info" style="margin-bottom:12px">{{ schedApplyTitle }}</div>',
      '    <template v-if="schedUrgent">',
      '      <el-form :model="urgForm" label-width="96px">',
      '        <el-form-item label="就诊类型" required>',
      '          <el-radio-group v-model="urgForm.visitType" size="small">',
      '            <el-radio-button :value="1">住院</el-radio-button><el-radio-button :value="2">门诊</el-radio-button><el-radio-button :value="3">日间</el-radio-button>',
      '          </el-radio-group>',
      '        </el-form-item>',
      '        <el-form-item label="患者" required>',
      '          <el-select v-if="urgForm.visitType === 1" v-model="urgForm.inpVisitId" filterable remote reserve-keyword :remote-method="searchPatients" :loading="urgPtLoading" placeholder="姓名/住院号检索在院患者" style="width:100%">',
      '            <el-option v-for="p in patients" :key="p.id" :label="ptLabel(p)" :value="p.id"></el-option>',
      '          </el-select>',
      '          <el-select v-else v-model="urgForm.visitId" filterable remote reserve-keyword :remote-method="searchOutpVisits" :loading="urgPtLoading" placeholder="姓名/门诊号检索就诊记录" style="width:100%">',
      '            <el-option v-for="v in outpVisits" :key="v.visit_id" :label="outpPtLabel(v)" :value="v.visit_id"></el-option>',
      '          </el-select>',
      '        </el-form-item>',
      '        <el-row :gutter="16">',
      '          <el-col :span="12"><el-form-item label="手术名称" required><el-input v-model="urgForm.surgeryName" maxlength="100"></el-input></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="手术编码"><el-input v-model="urgForm.surgeryCode" maxlength="50"></el-input></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="手术级别">',
      '            <el-select v-model="urgForm.surgeryLevel" clearable placeholder="选择级别" style="width:100%"><el-option v-for="o in surgLevelOpts" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="麻醉方式">',
      '            <el-select v-model="urgForm.anesthesiaType" clearable placeholder="选择麻醉方式" style="width:100%"><el-option v-for="o in aneTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="期望时间"><el-date-picker v-model="urgForm.expectTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="精确到分" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="术前诊断"><el-input v-model="urgForm.preOpDiag" maxlength="100"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '        <div class="surg-info" style="margin:0 0 12px 96px">急诊免病区复核: 自动建档已安排申请单并直接进入排程</div>',
      '      </el-form>',
      '    </template>',
      '    <el-form :model="schedForm" label-width="96px">',
      '      <el-row :gutter="16">',
      '        <el-col :span="12"><el-form-item label="手术日期" required><el-date-picker v-model="schedForm.scheduleDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="时间段" required><el-select v-model="schedForm.scheduleTime" clearable placeholder="HH:mm段, 精确到分" filterable allow-create default-first-option style="width:100%"><el-option v-for="t in timeSlots" :key="t.value" :label="t.value" :value="t.value"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="手术间"><el-select v-model="schedForm.roomNo" clearable placeholder="选择手术间" style="width:100%"><el-option v-for="r in rooms" :key="r" :label="r" :value="r"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="主刀医师">',
      '          <el-select v-model="schedForm.surgeonId" filterable clearable placeholder="默认沿用申请拟主刀" style="width:100%"><el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option></el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="麻醉医师"><el-select v-model="schedForm.anesthesiologistId" filterable clearable placeholder="选择医师" style="width:100%"><el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="器械护士"><el-select v-model="schedForm.instrumentNurseId" filterable clearable placeholder="选择护士" style="width:100%"><el-option v-for="s in nurses" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="巡回护士"><el-select v-model="schedForm.circulatingNurseId" filterable clearable placeholder="选择护士" style="width:100%"><el-option v-for="s in nurses" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="schedApplyVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitSchedApply">{{ schedUrgent ? \'急诊安排\' : \'确认安排\' }}</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="transVisible" title="手术间调配" width="440px" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:12px">{{ transTitle }}</div>',
      '    <el-form label-width="90px">',
      '      <el-form-item label="目标手术间" required>',
      '        <el-select v-model="transRoom" placeholder="选择手术间" style="width:100%"><el-option v-for="r in rooms" :key="r" :label="r" :value="r"></el-option></el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <div style="font-size:12px;color:var(--yb-ink-3)">调配将产生"安排变动"通知(type2), 时间冲突将被拒绝</div>',
      '    <template #footer>',
      '      <el-button @click="transVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="doTransfer">确认调配</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dropVisible" title="拖拽改排确认" width="480px" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:12px">{{ dropTitle }}</div>',
      '    <el-form :model="dropForm" label-width="90px">',
      '      <el-form-item label="目标手术间" required><el-input v-model="dropForm.roomNo" disabled></el-input></el-form-item>',
      '      <el-form-item label="手术日期"><el-date-picker v-model="dropForm.scheduleDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item>',
      '      <el-form-item label="时间段"><el-select v-model="dropForm.scheduleTime" clearable placeholder="不改动" filterable allow-create default-first-option style="width:100%"><el-option v-for="t in timeSlots" :key="t.value" :label="t.value" :value="t.value"></el-option></el-select></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dropVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitDrop">确认改排</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-drawer v-model="detailVisible" title="手术详情" size="640px" :destroy-on-close="true">',
      '    <div v-loading="detailLoading">',
      '      <template v-if="detail">',
      '        <div class="surg-section-title">患者信息</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="姓名">{{ detail.patient ? detail.patient.name : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="住院号">{{ detail.patient ? detail.patient.inp_no : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="性别">{{ detail.patient ? detail.patient.gender_name : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="年龄">{{ detail.patient ? detail.patient.age : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="身份证">{{ detail.patient ? detail.patient.id_card : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="电话">{{ detail.patient ? detail.patient.phone : "-" }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="surg-section-title" style="margin-top:14px">手术信息</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="手术名称">{{ detail.surgery.surgeryName }}</el-descriptions-item>',
      '          <el-descriptions-item label="手术编码">{{ orDash(detail.surgery.surgeryCode) }}</el-descriptions-item>',
      '          <el-descriptions-item label="手术级别">{{ levelLabel(detail.surgery.surgeryLevel) }}</el-descriptions-item>',
      '          <el-descriptions-item label="ASA分级">{{ asaLabel(detail.surgery.asaGrade) }}</el-descriptions-item>',
      '          <el-descriptions-item label="切口类型">{{ incisionLabel(detail.surgery.incisionType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="状态"><el-tag size="small" :type="stTag(detail.surgery.status)">{{ stLabel(detail.surgery.status) }}</el-tag></el-descriptions-item>',
      '          <el-descriptions-item label="手术日期">{{ fmtDate(detail.surgery.scheduleDate) }}</el-descriptions-item>',
      '          <el-descriptions-item label="时间段">{{ orDash(detail.surgery.scheduleTime) }}</el-descriptions-item>',
      '          <el-descriptions-item label="手术间">{{ orDash(detail.surgery.roomNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="科室">{{ orDash(detail.deptName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="一体化模块">{{ moduleLabel(detail.surgery.moduleType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="开始时间">{{ fmtDT(detail.surgery.startTime) }}</el-descriptions-item>',
      '          <el-descriptions-item label="结束时间">{{ fmtDT(detail.surgery.endTime) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="surg-section-title" style="margin-top:14px">手术团队</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="主刀医师">{{ teamName("surgeon") }}</el-descriptions-item>',
      '          <el-descriptions-item label="一助">{{ teamName("firstAssistant") }}</el-descriptions-item>',
      '          <el-descriptions-item label="二助">{{ teamName("secondAssistant") }}</el-descriptions-item>',
      '          <el-descriptions-item label="麻醉医师">{{ teamName("anesthesiologist") }}</el-descriptions-item>',
      '          <el-descriptions-item label="麻醉护士">{{ teamName("anesthesiaNurse") }}</el-descriptions-item>',
      '          <el-descriptions-item label="器械护士">{{ teamName("instrumentNurse") }}</el-descriptions-item>',
      '          <el-descriptions-item label="巡回护士">{{ teamName("circulatingNurse") }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="surg-section-title" style="margin-top:14px">',
      '          <span>术中医嘱</span>',
      '          <el-button v-if="detail.surgery.visitType === 1" link type="primary" size="small" style="float:right;margin-top:-2px" @click="openSoDialog">+ 开立医嘱</el-button>',
      '          <el-button v-if="detail.surgery.visitType === 1" link type="success" size="small" style="float:right;margin-top:-2px;margin-right:10px" @click="openTplDialog">套用模板</el-button>',
      '        </div>',
      '        <div v-if="detail.surgery.visitType !== 1" class="surg-info" style="margin-bottom:8px">门诊/日间手术的用药通过「手术记费」记账, 不进入住院医嘱链。</div>',
      '        <el-table v-else :data="soOrders" border size="small" v-loading="soLoading" max-height="300" empty-text="暂无手术医嘱">',
      '          <el-table-column label="阶段" width="56" align="center"><template #default="s"><el-tag size="small" :type="phaseTag(s.row.orderPhase)">{{ phaseLabel(s.row.orderPhase) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="分类" width="52" align="center"><template #default="s">{{ orderCatLabel(s.row.orderCategory) }}</template></el-table-column>',
      '          <el-table-column label="医嘱内容" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.orderContent }}<el-tag v-if="s.row.proxyDoctorId" size="small" type="info" style="margin-left:4px">代开</el-tag></template></el-table-column>',
      '          <el-table-column label="状态" width="64" align="center"><template #default="s"><el-tag size="small" :type="orderStTag(s.row.orderStatus)">{{ orderStLabel(s.row.orderStatus) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="药房" width="62" align="center"><template #default="s">{{ (s.row.orderCategory === 1 && s.row.drugId) ? (sendPharmLabel(s.row.sendPharmStatus) || "未发送") : "-" }}</template></el-table-column>',
      '          <el-table-column label="执行" width="56" align="center"><template #default="s"><el-tag v-if="s.row.execDone" size="small" type="success" :title="fmtDT(s.row.execTimeLast)">已执行</el-tag><span v-else>-</span></template></el-table-column>',
      '          <el-table-column label="退药" width="62" align="center"><template #default="s"><el-tag v-if="s.row.returnApplyFlag === 1" size="small" type="warning" :title="s.row.returnApplyReason">申请中</el-tag><el-tag v-else-if="s.row.returnApplyFlag === 2" size="small" type="success">已退药</el-tag><span v-else>-</span></template></el-table-column>',
      '          <el-table-column label="操作" width="210" align="center" fixed="right"><template #default="s">',
      '            <el-button v-if="!s.row.execDone && (s.row.orderStatus === 1 || s.row.orderStatus === 2)" link type="success" @click="execSoOrder(s.row)">执行</el-button>',
      '            <el-button v-if="s.row.orderCategory === 1 && s.row.drugId && s.row.sendPharmStatus !== 1" link type="primary" @click="sendSoPharmacy(s.row)">发送药房</el-button>',
      '            <el-button v-if="s.row.orderCategory === 1 && s.row.drugId && s.row.sendPharmStatus === 1" link type="warning" @click="recallSoPharmacy(s.row)">撤回</el-button>',
      '            <el-button v-if="s.row.orderCategory === 1 && s.row.drugId && s.row.dispenseStatus === 1 && !s.row.execDone && s.row.returnApplyFlag !== 1" link type="warning" @click="returnApplySo(s.row)">申请退药</el-button>',
      '            <el-button v-if="s.row.orderStatus === 1 && !s.row.execDone" link type="danger" @click="cancelSoOrder(s.row)">作废</el-button>',
      '          </template></el-table-column>',
      '        </el-table>',
      '        <div class="surg-section-title" style="margin-top:14px" v-if="detail.surgery.moduleType === 3">',
      '          <span>新生儿管理</span>',
      '          <el-button link type="primary" size="small" style="float:right;margin-top:-2px" @click="openNewbornDlg">+ 新生儿建档</el-button>',
      '        </div>',
      '        <div v-if="detail.surgery.moduleType === 3 && nbGuide && nbGuide.needRegister" class="surg-info" style="margin-bottom:8px">该分娩手术尚未登记新生儿档案(分娩完成时不自动建档), 请点击右上「新生儿建档」补录。</div>',
      '        <el-table v-if="detail.surgery.moduleType === 3" :data="nbList" border size="small" v-loading="nbLoading" max-height="240" empty-text="暂无新生儿档案">',
      '          <el-table-column label="姓名" min-width="100"><template #default="s">{{ orDash(s.row.baby_name) }}</template></el-table-column>',
      '          <el-table-column label="性别" width="56" align="center"><template #default="s">{{ s.row.baby_sex === 2 ? "女" : (s.row.baby_sex === 1 ? "男" : "-") }}</template></el-table-column>',
      '          <el-table-column label="出生时间" width="140"><template #default="s">{{ fmtDT(s.row.birth_time) }}</template></el-table-column>',
      '          <el-table-column label="Apgar 1/5/10" width="100" align="center"><template #default="s">{{ orDash(s.row.apgar_1) }} / {{ orDash(s.row.apgar_5) }} / {{ orDash(s.row.apgar_10) }}</template></el-table-column>',
      '          <el-table-column label="体重(g)" width="76" align="center"><template #default="s">{{ orDash(s.row.weight_g) }}</template></el-table-column>',
      '          <el-table-column label="身长(cm)" width="76" align="center"><template #default="s">{{ orDash(s.row.height_cm) }}</template></el-table-column>',
      '          <el-table-column label="分娩方式" width="80" align="center"><template #default="s">{{ birthTypeLabel(s.row.birth_type) }}</template></el-table-column>',
      '          <el-table-column label="住院号" width="110"><template #default="s">{{ orDash(s.row.baby_inp_no) }}</template></el-table-column>',
      '          <el-table-column label="状态" width="72" align="center"><template #default="s"><el-tag size="small" :type="newbornStatusTag(s.row.status)">{{ newbornStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        </el-table>',
      '        <div class="surg-section-title" style="margin-top:14px">',
      '          <span>PACU 复苏</span>',
      '          <el-button v-if="detail.surgery.status === 4 && !pacuHasInProgress" link type="primary" size="small" style="float:right;margin-top:-2px" @click="openPacuAdmit">入复苏(PACU)</el-button>',
      '          <el-button v-if="pacuHasInProgress" link type="warning" size="small" style="float:right;margin-top:-2px;margin-right:10px" @click="gotoPacuTab">去复苏Tab处理</el-button>',
      '        </div>',
      '        <div v-if="detail.surgery.status === 4 && !pacuHasInProgress && !pacuList.length" class="surg-info" style="margin-bottom:8px">该手术已结束尚未完成, 可在右上「入复苏(PACU)」建立复苏单; 存在在途复苏单时需先出复苏才能完成手术。</div>',
      '        <el-table v-if="pacuList.length" :data="pacuList" border size="small" v-loading="pacuDetailLoading" max-height="220" empty-text="暂无复苏记录">',
      '          <el-table-column label="入复苏" width="130"><template #default="s">{{ fmtDT(s.row.admitTime) }}</template></el-table-column>',
      '          <el-table-column label="入复苏Aldrete" width="90" align="center"><template #default="s">{{ orDash(s.row.aldreteAdmit) }}</template></el-table-column>',
      '          <el-table-column label="体征条数" width="70" align="center"><template #default="s">{{ jsonArr(s.row.vitalsJson).length }}</template></el-table-column>',
      '          <el-table-column label="状态" width="72" align="center"><template #default="s"><el-tag size="small" :type="pacuStatusTag(s.row.status)">{{ pacuStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="出复苏" width="130"><template #default="s">{{ fmtDT(s.row.dischargeTime) }}</template></el-table-column>',
      '          <el-table-column label="出复苏去向" width="90" align="center"><template #default="s">{{ s.row.status === 2 ? pacuDestLabel(s.row.dischargeDest) : "-" }}</template></el-table-column>',
      '        </el-table>',
      '      </template>',
      '    </div>',
      '  </el-drawer>',
      '  <el-drawer v-model="aneVisible" title="麻醉记录" size="980px" :destroy-on-close="true">',
      '    <anesthesia-record v-if="aneSid" :surgery-id="aneSid"></anesthesia-record>',
      '  </el-drawer>',
      '  <el-drawer v-model="feeVisible" title="手术记费" size="1150px" :destroy-on-close="true">',
      '    <surgery-fee v-if="feeSid" :surgery-id="feeSid"></surgery-fee>',
      '  </el-drawer>',
      '  <el-dialog v-model="soDlgVisible" title="开立手术医嘱" width="560px" :close-on-click-modal="false" append-to-body>',
      '    <el-form label-width="80px" size="small">',
      '      <el-form-item label="阶段"><el-radio-group v-model="soForm.orderPhase"><el-radio-button :value="2">术中</el-radio-button><el-radio-button :value="3">术后</el-radio-button></el-radio-group></el-form-item>',
      '      <el-form-item label="分类"><el-select v-model="soForm.orderCategory" style="width:160px" @change="onSoCatChange"><el-option v-for="c in soCatOpts" :key="c.value" :label="c.label" :value="c.value"></el-option></el-select></el-form-item>',
      '      <el-form-item label="药品" v-if="soForm.orderCategory === 1">',
      '        <el-select v-model="soPickDrugId" style="width:100%" filterable remote reserve-keyword clearable :remote-method="soDrugSearch" :loading="soDrugLoading" placeholder="输入通用名检索" @change="onSoPickDrug">',
      '          <el-option v-for="d in soDrugOptions" :key="d.id" :label="(d.genericName || \'\') + \' \' + (d.spec || \'\')" :value="d.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="收费项目" v-else>',
      '        <el-select v-model="soPickChargeId" style="width:100%" filterable remote reserve-keyword clearable :remote-method="soChargeSearch" :loading="soChargeLoading" placeholder="输入项目名检索" @change="onSoPickCharge">',
      '          <el-option v-for="x in soChargeOptions" :key="x.id" :label="x.itemName" :value="x.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="内容"><el-input v-model="soForm.orderContent" type="textarea" :rows="2" placeholder="医嘱内容"></el-input></el-form-item>',
      '      <el-form-item label="剂量用法" v-if="soForm.orderCategory === 1">',
      '        <el-input v-model="soForm.dosage" style="width:88px" placeholder="剂量"></el-input>',
      '        <el-input v-model="soForm.dosageUnit" style="width:76px;margin-left:6px" placeholder="单位"></el-input>',
      '        <el-select v-model="soForm.usageCode" clearable filterable style="width:116px;margin-left:6px" placeholder="用法"><el-option v-for="u in soUsageOpts" :key="u.code" :label="u.name" :value="u.code"></el-option></el-select>',
      '        <el-select v-model="soForm.freqCode" clearable filterable style="width:116px;margin-left:6px" placeholder="频次"><el-option v-for="f in soFreqOpts" :key="f.code" :label="f.name" :value="f.code"></el-option></el-select>',
      '      </el-form-item>',
      '      <el-form-item label="数量" v-if="soForm.orderCategory === 1"><el-input-number v-model="soForm.quantity" :min="1" size="small"></el-input-number></el-form-item>',
      '      <el-form-item label="代开"><el-switch v-model="soForm.proxyOn"></el-switch><span style="margin-left:10px;font-size:12px;color:var(--yb-ink-3)">以其他医师名义开立(权限按其口径校验)</span></el-form-item>',
      '      <el-form-item label="代开医师" v-if="soForm.proxyOn"><el-select v-model="soForm.proxyDoctorId" filterable clearable style="width:220px" placeholder="选择医师"><el-option v-for="s in doctors" :key="s.id" :label="staffLabel(s)" :value="s.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="代开原因" v-if="soForm.proxyOn"><el-input v-model="soForm.proxyReason" placeholder="必填, 留痕"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="soDlgVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="soSaving" @click="submitSoOrder">提交</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="tplDlgVisible" title="套用手术医嘱模板" width="620px" :close-on-click-modal="false" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:8px">仅列出适用场景=手术医嘱且启用的模板; 套用后按模板明细批量开立到本手术, 开立阶段可覆盖模板默认阶段。</div>',
      '    <div style="margin-bottom:8px;font-size:13px">开立阶段: <el-radio-group v-model="tplPick.orderPhase" size="small"><el-radio-button :value="1">术前</el-radio-button><el-radio-button :value="2">术中</el-radio-button><el-radio-button :value="3">术后</el-radio-button></el-radio-group></div>',
      '    <el-table :data="tplList" border size="small" v-loading="tplLoading" max-height="280" empty-text="暂无手术医嘱模板(请到住院医嘱模板维护新建, 适用场景=手术医嘱)">',
      '      <el-table-column label="选择" width="56" align="center"><template #default="s"><el-button v-if="tplPick.templateId === s.row.id" link type="success">已选</el-button><el-button v-else link @click="pickTpl(s.row)">选择</el-button></template></el-table-column>',
      '      <el-table-column label="模板名称" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.templateName }}</template></el-table-column>',
      '      <el-table-column label="级别" width="70" align="center"><template #default="s">{{ tplLevelLabel(s.row.templateType) }}</template></el-table-column>',
      '      <el-table-column label="默认阶段" width="80" align="center"><template #default="s">{{ s.row.surgeryPhase ? phaseLabel(s.row.surgeryPhase) : "-" }}</template></el-table-column>',
      '      <el-table-column label="使用次数" width="76" align="center"><template #default="s">{{ orDash(s.row.usageCount) }}</template></el-table-column>',
      '    </el-table>',
      '    <template #footer>',
      '      <el-button size="small" @click="tplDlgVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :disabled="!tplPick.templateId" :loading="tplSaving" @click="applyTemplate">套用并批量开嘱</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="nbDlgVisible" title="新生儿建档" width="560px" :close-on-click-modal="false" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="姓名" required><el-input v-model="nbForm.babyName" placeholder="新生儿姓名(必填)"></el-input></el-form-item>',
      '      <el-form-item label="性别"><el-radio-group v-model="nbForm.babySex"><el-radio-button :value="1">男</el-radio-button><el-radio-button :value="2">女</el-radio-button></el-radio-group></el-form-item>',
      '      <el-form-item label="出生时间"><el-date-picker v-model="nbForm.birthTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="默认当前" style="width:200px"></el-date-picker></el-form-item>',
      '      <el-form-item label="Apgar评分"><el-input-number v-model="nbForm.apgar1" :min="0" :max="10" controls-position="right" style="width:84px"></el-input-number><span style="margin:0 4px">/</span><el-input-number v-model="nbForm.apgar5" :min="0" :max="10" controls-position="right" style="width:84px"></el-input-number><span style="margin:0 4px">/</span><el-input-number v-model="nbForm.apgar10" :min="0" :max="10" controls-position="right" style="width:84px"></el-input-number><span style="margin-left:6px;font-size:12px;color:var(--yb-ink-3)">1分/5分/10分</span></el-form-item>',
      '      <el-form-item label="体重/身长"><el-input-number v-model="nbForm.weightG" :min="0" :max="10000" controls-position="right" style="width:100px"></el-input-number><span style="margin:0 4px">g,</span><el-input-number v-model="nbForm.heightCm" :min="0" :max="100" controls-position="right" style="width:92px"></el-input-number><span style="margin-left:4px">cm</span></el-form-item>',
      '      <el-form-item label="分娩方式"><el-select v-model="nbForm.birthType" style="width:140px"><el-option v-for="o in birthTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="nbForm.remark" type="textarea" :rows="2" placeholder="选填"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="nbDlgVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="nbSaving" @click="submitNewborn">建档</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ================= 手麻P3b: PACU 入复苏 / 体征登记 / 出复苏 ================= */
      '  <el-dialog v-model="pacuAdmitVisible" title="入复苏(PACU)" width="460px" :close-on-click-modal="false" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:8px">仅已结束手术(术后状态)且无在途复苏单可入复苏。</div>',
      '    <el-form label-width="110px" size="small">',
      '      <el-form-item label="入复苏Aldrete"><el-input-number v-model="pacuAdmitForm.aldreteAdmit" :min="0" :max="10" controls-position="right" style="width:120px"></el-input-number><span style="margin-left:6px;font-size:12px;color:var(--yb-ink-3)">0-10, 选填</span></el-form-item>',
      '      <el-form-item label="入复苏备注"><el-input v-model="pacuAdmitForm.admitNote" type="textarea" :rows="2" placeholder="选填"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="pacuAdmitVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="pacuSaving" @click="submitPacuAdmit">确认入复苏</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="pacuVitalVisible" title="生命体征登记" width="560px" :close-on-click-modal="false" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:8px">{{ pacuVitalRow ? (pacuVitalRow.patientName || "") + " · 已登记 " + jsonArr(pacuVitalRow.vitalsJson).length + " 条" : "" }}</div>',
      '    <el-form :inline="true" label-width="64px" size="small">',
      '      <el-form-item label="血压"><el-input v-model="pacuVitalForm.hp" placeholder="如 120/80" style="width:110px"></el-input></el-form-item>',
      '      <el-form-item label="心率"><el-input v-model="pacuVitalForm.hr" placeholder="bpm" style="width:84px"></el-input></el-form-item>',
      '      <el-form-item label="体温"><el-input v-model="pacuVitalForm.tm" placeholder="℃" style="width:84px"></el-input></el-form-item>',
      '      <el-form-item label="脉搏"><el-input v-model="pacuVitalForm.p" placeholder="" style="width:84px"></el-input></el-form-item>',
      '      <el-form-item label="SpO2"><el-input v-model="pacuVitalForm.s" placeholder="%" style="width:84px"></el-input></el-form-item>',
      '      <el-form-item><el-button type="primary" :loading="pacuSaving" @click="submitPacuVital">添加</el-button></el-form-item>',
      '    </el-form>',
      '    <div style="font-size:12px;color:var(--yb-ink-3)">时间取当前; 可连续多次添加。</div>',
      '    <template #footer>',
      '      <el-button size="small" @click="pacuVitalVisible = false">关闭</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="pacuDischargeVisible" title="出复苏" width="480px" :close-on-click-modal="false" append-to-body>',
      '    <div class="surg-info" style="margin-bottom:8px">Aldrete ≥ 9 分直接放行; 低于 9 分必须在备注中说明理由。</div>',
      '    <el-form label-width="110px" size="small">',
      '      <el-form-item label="出复苏Aldrete" required><el-input-number v-model="pacuDischargeForm.aldreteDischarge" :min="0" :max="10" controls-position="right" style="width:120px"></el-input-number></el-form-item>',
      '      <el-form-item label="去向" required><el-select v-model="pacuDischargeForm.dischargeDest" placeholder="选择去向" style="width:160px"><el-option :value="1" label="回病房"></el-option><el-option :value="2" label="转ICU"></el-option><el-option :value="3" label="门诊随访"></el-option></el-select></el-form-item>',
      '      <el-form-item label="备注/理由"><el-input v-model="pacuDischargeForm.dischargeNote" type="textarea" :rows="2" placeholder="Aldrete<9时必填理由"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="pacuDischargeVisible = false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="pacuSaving" @click="submitPacuDischarge">确认出复苏</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        tab: 'unarranged',
        rows: [],
        total: 0,
        page: 1,
        size: 20,
        loading: false,
        filters: { deptId: null, dateRange: [], status: [], kw: '', moduleType: null },
        moduleOpts: MODULE_OPTS,
        birthTypeOpts: BIRTH_TYPE_OPTS,
        refDepts: [],
        doctors: [],
        nurses: [],
        rooms: [],
        statusOpts: STATUS_OPTS,
        surgLevelOpts: SURGERY_LEVEL_OPTS,
        asaOpts: ASA_OPTS,
        incisionOpts: INCISION_OPTS,
        board: { date: '', total: 0, rooms: [] },
        boardLoading: false,
        dlgVisible: false,
        editId: null,
        step: 0,
        form: blankSurgForm(),
        patients: [],
        ptLoading: false,
        patientPicked: null,
        saving: false,
        schVisible: false,
        schId: null,
        schTitle: '',
        schForm: { scheduleDate: '', scheduleTime: '', roomNo: '' },
        detailVisible: false,
        detail: null,
        detailLoading: false,
        aneVisible: false,
        aneSid: null,
        feeVisible: false,
        feeSid: null,
        /* ===== 手麻P0: 角色矩阵/四状态Tab/排程板/报到/通知角标 ===== */
        canArrange: false,
        canOps: false,
        pendingNotify: 0,
        unarrangedRows: [], unarrangedLoading: false,
        arrangedView: 'list',
        arrangedRows: [], arrTotal: 0, arrP: 1, arrSize: 20, arrangedLoading: false,
        arrFilters: { dateRange: [], status: null },
        finRows: [], finTotal: 0, finP: 1, finSize: 20, finLoading: false,
        finFilters: { dateRange: [], visitType: null, kw: '' },
        anesRows: [], anesLoading: false,
        regKw: '', regRows: [], regSel: null,
        rb: { date: '', total: 0, rooms: [] }, rbLoading: false, boardDate: today(),
        dragCard: null, dragOverRoom: '',
        schedApplyVisible: false, schedUrgent: false, schedApplyRow: null, schedApplyTitle: '',
        schedForm: { scheduleDate: '', scheduleTime: '', roomNo: '', surgeonId: null, anesthesiologistId: null, instrumentNurseId: null, circulatingNurseId: null },
        urgForm: { visitType: 1, inpVisitId: null, visitId: null, surgeryCode: '', surgeryName: '', surgeryLevel: null, anesthesiaType: null, expectTime: '', preOpDiag: '' },
        outpVisits: [], urgPtLoading: false,
        transVisible: false, transId: null, transTitle: '', transRoom: '',
        dropVisible: false, dropCard: null, dropTitle: '',
        dropForm: { roomNo: '', scheduleDate: '', scheduleTime: '' },
        /* ===== 手麻P1: 术中医嘱区 ===== */
        soOrders: [], soLoading: false,
        soDlgVisible: false, soSaving: false,
        soForm: blankSoOrderForm(),
        soPickDrugId: null, soPickChargeId: null,
        soDrugOptions: [], soChargeOptions: [], soUsageOpts: [], soFreqOpts: [],
        soDrugLoading: false, soChargeLoading: false, soDictLoaded: false,
        soCatOpts: ORDER_CAT_OPTS,
        /* ===== 手麻P2b: 手术医嘱模板套用(apply_scene=2) ===== */
        tplDlgVisible: false, tplSaving: false, tplList: [], tplLoading: false,
        tplPick: { templateId: null, orderPhase: 2 },
        /* ===== 手麻P2d: 新生儿建档(分娩联动) ===== */
        nbGuide: null, nbList: [], nbLoading: false, nbDlgVisible: false, nbSaving: false, nbForm: blankNewbornForm(),
        /* ===== 手麻P3b: 复苏(PACU) ===== */
        pacuRows: [], pacuTotal: 0, pacuP: 1, pacuSize: 20, pacuLoading: false,
        pacuFilters: { status: null, kw: '' },
        pacuList: [], pacuDetailLoading: false, pacuHasInProgress: false,
        pacuSaving: false,
        pacuAdmitVisible: false, pacuAdmitForm: { surgeryId: null, aldreteAdmit: null, admitNote: '' },
        pacuVitalVisible: false, pacuVitalRow: null, pacuVitalForm: { hp: '', hr: '', tm: '', p: '', s: '' },
        pacuDischargeVisible: false, pacuDischargeRow: null,
        pacuDischargeForm: { aldreteDischarge: 10, dischargeDest: 1, dischargeNote: '' }
      };
    },
    computed: {
      dlgTitle: function () { return this.editId ? '编辑手术' : '新增手术'; },
      /* 本页关键字过滤(列表接口无 keyword 参数) */
      filteredRows: function () {
        var kw = (this.filters.kw || '').trim().toLowerCase();
        if (!kw) { return this.rows || []; }
        return (this.rows || []).filter(function (r) {
          return String(r.patient_name || '').toLowerCase().indexOf(kw) >= 0
            || String(r.surgery_name || '').toLowerCase().indexOf(kw) >= 0
            || String(r.surgeon_name || '').toLowerCase().indexOf(kw) >= 0
            || String(r.inp_no || '').toLowerCase().indexOf(kw) >= 0;
        });
      },
      /* 手麻P0: 已完成Tab本页关键字过滤 */
      filteredFinished: function () {
        var kw = (this.finFilters.kw || '').trim().toLowerCase();
        if (!kw) { return this.finRows || []; }
        return (this.finRows || []).filter(function (r) {
          return String(r.patient_name || '').toLowerCase().indexOf(kw) >= 0
            || String(r.surgery_name || '').toLowerCase().indexOf(kw) >= 0
            || String(r.surgeon_name || '').toLowerCase().indexOf(kw) >= 0
            || String(r.inp_no || '').toLowerCase().indexOf(kw) >= 0;
        });
      }
    },
    methods: {
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/surgery/list?page=' + vm.page + '&size=' + vm.size;
        var org = HIS.currentOrgId();
        if (org) { q += '&orgId=' + org; }
        if (vm.filters.deptId) { q += '&deptId=' + vm.filters.deptId; }
        if (vm.filters.moduleType) { q += '&moduleType=' + vm.filters.moduleType; }
        if (vm.filters.dateRange && vm.filters.dateRange.length === 2) {
          q += '&startDate=' + vm.filters.dateRange[0] + '&endDate=' + vm.filters.dateRange[1];
        }
        if (vm.filters.status && vm.filters.status.length) { q += '&statuses=' + vm.filters.status.join(','); }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          vm.loading = false;
        }).catch(function (e) { vm.loading = false; HIS.notifyError(e); });
      },
      onSearch: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      refresh: function () {
        this.loadPendingCount();
        if (this.tab === 'list') { this.load(); }
        else if (this.tab === 'board') { this.loadBoard(); }
        else if (this.tab === 'unarranged') { this.loadUnarranged(); }
        else if (this.tab === 'arranged') { this.refreshArranged(); }
        else if (this.tab === 'finished') { this.loadFinished(); }
        else if (this.tab === 'anes') { this.loadAnes(); }
        else if (this.tab === 'pacu') { this.loadPacu(); }
        else { this.load(); }
      },
      onTabChange: function (name) {
        var n = name || this.tab;
        if (n === 'board') { this.loadBoard(); }
        else if (n === 'unarranged') { this.loadUnarranged(); }
        else if (n === 'arranged') { this.refreshArranged(); }
        else if (n === 'finished') { this.loadFinished(); }
        else if (n === 'anes') { this.loadAnes(); }
        else if (n === 'pacu') { this.loadPacu(); }
        else if (n === 'list') { this.load(); }
      },
      loadBoard: function () {
        var vm = this;
        vm.boardLoading = true;
        var q = '/api/his/surgery/schedule/today';
        var org = HIS.currentOrgId();
        if (org) { q += '?orgId=' + org; }
        HIS.get(q).then(function (d) {
          vm.board = d || { date: '', total: 0, rooms: [] };
          vm.boardLoading = false;
        }).catch(function (e) { vm.boardLoading = false; HIS.notifyError(e); });
      },
      loadRooms: function () {
        var vm = this;
        HIS.get('/api/his/surgery/room/list').then(function (l) { vm.rooms = l || []; }).catch(function () { });
      },
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.refDepts = l || []; }).catch(function () { });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师')).then(function (l) { vm.doctors = l || []; }).catch(function () { });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('护士')).then(function (l) { vm.nurses = l || []; }).catch(function () { });
      },
      staffLabel: function (s) { return s.staffName + (s.titleName ? '（' + s.titleName + '）' : ''); },
      deptNameOf: function (id) {
        if (!id) { return '-'; }
        for (var i = 0; i < this.refDepts.length; i++) {
          if (this.refDepts[i].id === id) { return this.refDepts[i].deptName; }
        }
        return '-';
      },
      teamName: function (key) {
        var t = this.detail && this.detail.team ? this.detail.team[key] : null;
        return (t && t.name) ? t.name : '-';
      },
      /* ===== 新增 / 编辑 ===== */
      openCreate: function () {
        this.editId = null;
        this.step = 0;
        this.form = blankSurgForm();
        this.patientPicked = null;
        this.dlgVisible = true;
        this.searchPatients('');
      },
      openEdit: function (row) {
        var vm = this;
        vm.editId = row.id;
        vm.step = 1;
        vm.form = {
          inpVisitId: row.inp_visit_id,
          surgeryCode: row.surgery_code || '',
          surgeryName: row.surgery_name || '',
          surgeryLevel: row.surgery_level || null,
          surgeonId: row.surgeon_id || null,
          firstAssistantId: null,
          secondAssistantId: null,
          anesthesiologistId: null,
          anesthesiaNurseId: null,
          instrumentNurseId: null,
          circulatingNurseId: null,
          deptId: row.dept_id || null,
          asaGrade: row.asa_grade || null,
          incisionType: row.incision_type || null,
          scheduleDate: '',
          scheduleTime: '',
          roomNo: ''
        };
        vm.patientPicked = null;
        vm.dlgVisible = true;
        /* 团队七角色不在列表行内, 从详情接口补全 */
        HIS.get('/api/his/surgery/' + row.id).then(function (d) {
          var s = (d && d.surgery) || {};
          vm.form.firstAssistantId = s.firstAssistantId || null;
          vm.form.secondAssistantId = s.secondAssistantId || null;
          vm.form.anesthesiologistId = s.anesthesiologistId || null;
          vm.form.anesthesiaNurseId = s.anesthesiaNurseId || null;
          vm.form.instrumentNurseId = s.instrumentNurseId || null;
          vm.form.circulatingNurseId = s.circulatingNurseId || null;
          vm.patientPicked = (d && d.patient) || null;
        }).catch(function () { });
      },
      nextStep: function () {
        if (this.step === 0 && !this.form.inpVisitId) { HIS.notifyError('请先选择患者'); return; }
        if (this.step === 1 && !this.form.surgeryName) { HIS.notifyError('请填写手术名称'); return; }
        if (this.step === 2 && !this.form.surgeonId) { HIS.notifyError('请选择主刀医师'); return; }
        if (this.step < 3) { this.step += 1; }
      },
      prevStep: function () { if (this.step > 0) { this.step -= 1; } },
      searchPatients: function (kw) {
        var vm = this;
        vm.ptLoading = true;
        var q = '/api/his/inp/patients?visitStatus=2&page=1&size=50';
        var org = HIS.currentOrgId();
        if (org) { q += '&orgId=' + org; }
        if (kw) { q += '&keyword=' + encodeURIComponent(kw); }
        HIS.get(q).then(function (d) {
          vm.patients = (d && d.records) || [];
          vm.ptLoading = false;
        }).catch(function (e) { vm.ptLoading = false; HIS.notifyError(e); });
      },
      ptLabel: function (p) {
        return orDash(p.patient_name) + (p.gender_name ? '·' + p.gender_name : '')
          + (p.age !== null && p.age !== undefined ? '·' + p.age + '岁' : '')
          + '  住院号 ' + orDash(p.inp_no);
      },
      onPickPatient: function (id) {
        var p = null;
        for (var i = 0; i < this.patients.length; i++) {
          if (this.patients[i].id === id) { p = this.patients[i]; break; }
        }
        this.patientPicked = p;
        if (p && p.dept_id && !this.form.deptId) { this.form.deptId = p.dept_id; }
      },
      submitForm: function () {
        var vm = this;
        var f = vm.form;
        if (!f.inpVisitId) { HIS.notifyError('请先选择患者'); vm.step = 0; return; }
        if (!f.surgeryName) { HIS.notifyError('请填写手术名称'); vm.step = 1; return; }
        if (!f.surgeonId) { HIS.notifyError('请选择主刀医师'); vm.step = 2; return; }
        var body = {
          inpVisitId: f.inpVisitId,
          surgeryCode: f.surgeryCode,
          surgeryName: f.surgeryName,
          surgeryLevel: f.surgeryLevel,
          surgeonId: f.surgeonId,
          firstAssistantId: f.firstAssistantId,
          secondAssistantId: f.secondAssistantId,
          anesthesiologistId: f.anesthesiologistId,
          anesthesiaNurseId: f.anesthesiaNurseId,
          instrumentNurseId: f.instrumentNurseId,
          circulatingNurseId: f.circulatingNurseId,
          deptId: f.deptId,
          asaGrade: f.asaGrade,
          incisionType: f.incisionType
        };
        vm.saving = true;
        var p = vm.editId
          ? HIS.put('/api/his/surgery/' + vm.editId, body)
          : HIS.post('/api/his/surgery', body);
        p.then(function (s) {
          /* 创建时若填写了排程信息, 创建成功后自动排程(1->2) */
          if (!vm.editId && f.scheduleDate && s && s.id) {
            return HIS.put('/api/his/surgery/' + s.id + '/schedule', {
              scheduleDate: f.scheduleDate,
              scheduleTime: f.scheduleTime,
              roomNo: f.roomNo
            });
          }
          return null;
        }).then(function () {
          vm.saving = false;
          vm.dlgVisible = false;
          HIS.notifySuccess(vm.editId ? '手术已更新' : '手术已创建');
          vm.load();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      /* ===== 排程 ===== */
      openSchedule: function (row) {
        this.schId = row.id;
        this.schTitle = orDash(row.patient_name) + ' - ' + orDash(row.surgery_name);
        this.schForm = {
          scheduleDate: row.schedule_date ? String(row.schedule_date).slice(0, 10) : today(),
          scheduleTime: row.schedule_time || '',
          roomNo: row.room_no || ''
        };
        this.schVisible = true;
      },
      submitSchedule: function () {
        var vm = this;
        if (!vm.schForm.scheduleDate) { HIS.notifyError('请选择手术日期'); return; }
        vm.saving = true;
        HIS.put('/api/his/surgery/' + vm.schId + '/schedule', {
          scheduleDate: vm.schForm.scheduleDate,
          scheduleTime: vm.schForm.scheduleTime,
          roomNo: vm.schForm.roomNo
        }).then(function () {
          vm.saving = false;
          vm.schVisible = false;
          HIS.notifySuccess('排程成功');
          vm.refresh();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      /* ===== 状态流转 ===== */
      doStart: function (row) {
        var vm = this;
        confirmBox('确认开始手术【' + escHtml(row.patient_name) + ' - ' + escHtml(row.surgery_name) + '】？', '开始手术')
          .then(function () { return HIS.put('/api/his/surgery/' + row.id + '/start'); })
          .then(function () { HIS.notifySuccess('手术已开始'); vm.refresh(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doEnd: function (row) {
        var vm = this;
        confirmBox('确认结束手术【' + escHtml(row.patient_name) + ' - ' + escHtml(row.surgery_name) + '】？结束后可进行麻醉记录完善与计费。', '结束手术')
          .then(function () { return HIS.put('/api/his/surgery/' + row.id + '/end'); })
          .then(function () { HIS.notifySuccess('手术已结束'); vm.refresh(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doComplete: function (row) {
        var vm = this;
        confirmBox('确认完成手术【' + escHtml(row.patient_name) + ' - ' + escHtml(row.surgery_name) + '】？', '完成手术')
          .then(function () { return HIS.put('/api/his/surgery/' + row.id + '/complete'); })
          .then(function () { HIS.notifySuccess('手术已完成'); vm.refresh(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doCancel: function (row) {
        var vm = this;
        confirmBox('确认取消手术【' + escHtml(row.patient_name) + ' - ' + escHtml(row.surgery_name) + '】？', '取消手术')
          .then(function () { return HIS.put('/api/his/surgery/' + row.id + '/cancel'); })
          .then(function () { HIS.notifySuccess('手术已取消'); vm.refresh(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 详情 / 子页面 ===== */
      openDetail: function (id) {
        var vm = this;
        vm.detail = null;
        vm.detailLoading = true;
        vm.detailVisible = true;
        vm.nbGuide = null; vm.nbList = [];
        vm.pacuList = []; vm.pacuHasInProgress = false;
        HIS.get('/api/his/surgery/' + id).then(function (d) {
          vm.detail = d || null;
          vm.detailLoading = false;
          vm.loadSurgeryOrders();
          vm.loadDetailPacu();
          if (d && d.surgery && Number(d.surgery.moduleType) === 3) { vm.loadNewbornGuide(d.surgery); }
        }).catch(function (e) { vm.detailLoading = false; HIS.notifyError(e); });
      },
      /* ===== 手麻P1: 术中医嘱区方法 ===== */
      loadSurgeryOrders: function () {
        var vm = this;
        var s = vm.detail && vm.detail.surgery;
        if (!s || s.visitType !== 1) { vm.soOrders = []; return; }
        vm.soLoading = true;
        HIS.get('/api/his/surgery/' + HIS.idParam(s.id) + '/orders')
          .then(function (l) { vm.soOrders = l || []; })
          .catch(function (e) { vm.soOrders = []; HIS.notifyError(e); })
          .finally(function () { vm.soLoading = false; });
      },
      openSoDialog: function () {
        var vm = this;
        vm.soForm = blankSoOrderForm();
        vm.soPickDrugId = null; vm.soPickChargeId = null;
        vm.soDrugOptions = []; vm.soChargeOptions = [];
        if (!vm.soDictLoaded) {
          vm.soDictLoaded = true;
          HIS.get('/api/org-catalog/available/med-dict?dictType=usage').then(function (l) { vm.soUsageOpts = l || []; }).catch(function () { });
          HIS.get('/api/org-catalog/available/med-dict?dictType=freq').then(function (l) { vm.soFreqOpts = l || []; }).catch(function () { });
        }
        vm.soDlgVisible = true;
      },
      onSoCatChange: function () {
        var vm = this;
        vm.soForm.drugId = null; vm.soForm.chargeItemId = null;
        vm.soPickDrugId = null; vm.soPickChargeId = null;
      },
      soDrugSearch: function (query) {
        var vm = this;
        var kw = String(query || '').trim();
        if (!kw) { vm.soDrugOptions = []; return; }
        vm.soDrugLoading = true;
        HIS.get('/api/org-catalog/available/drug?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.soDrugOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.soDrugLoading = false; });
      },
      onSoPickDrug: function (id) {
        var vm = this;
        vm.soPickDrugId = null;
        var d = vm.soDrugOptions.find(function (x) { return HIS.sameId(x.id, id); });
        if (!d) { return; }
        vm.soForm.drugId = d.id;
        vm.soForm.spec = d.spec || '';
        if (!vm.soForm.dosageUnit) { vm.soForm.dosageUnit = d.doseUnit || d.minUnit || ''; }
        if (!vm.soForm.orderContent) { vm.soForm.orderContent = (d.genericName || '') + (d.spec ? ' ' + d.spec : ''); }
      },
      soChargeSearch: function (query) {
        var vm = this;
        var kw = String(query || '').trim();
        if (!kw) { vm.soChargeOptions = []; return; }
        vm.soChargeLoading = true;
        HIS.get('/api/org-catalog/available/charge?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.soChargeOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.soChargeLoading = false; });
      },
      onSoPickCharge: function (id) {
        var vm = this;
        vm.soPickChargeId = null;
        var it = vm.soChargeOptions.find(function (x) { return HIS.sameId(x.id, id); });
        if (!it) { return; }
        vm.soForm.chargeItemId = it.id;
        vm.soForm.spec = it.spec || '';
        if (!vm.soForm.orderContent) { vm.soForm.orderContent = it.itemName || ''; }
      },
      submitSoOrder: function () {
        var vm = this;
        var f = vm.soForm;
        var s = vm.detail && vm.detail.surgery;
        if (!s) { return; }
        if (!f.orderContent || !String(f.orderContent).trim()) { HIS.notifyError('请填写医嘱内容'); return; }
        if (f.orderCategory === 1 && !f.drugId) { HIS.notifyError('请选择药品'); return; }
        if (f.orderCategory !== 1 && !f.chargeItemId) { HIS.notifyError('请选择收费项目'); return; }
        if (f.proxyOn && !f.proxyDoctorId) { HIS.notifyError('代开需选择被代开医师'); return; }
        if (f.proxyOn && !(f.proxyReason && String(f.proxyReason).trim())) { HIS.notifyError('请填写代开原因'); return; }
        var body = {
          orderType: 2,
          orderCategory: Number(f.orderCategory),
          orderContent: String(f.orderContent).trim(),
          drugId: f.orderCategory === 1 ? f.drugId : null,
          chargeItemId: f.orderCategory === 1 ? null : f.chargeItemId,
          spec: f.spec || null,
          dosage: f.orderCategory === 1 ? (f.dosage || null) : null,
          dosageUnit: f.orderCategory === 1 ? (f.dosageUnit || null) : null,
          usageCode: f.orderCategory === 1 ? (f.usageCode || null) : null,
          freqCode: f.orderCategory === 1 ? (f.freqCode || null) : null,
          quantity: f.orderCategory === 1 ? (f.quantity || 1) : null,
          orderPhase: Number(f.orderPhase),
          proxyDoctorId: f.proxyOn ? f.proxyDoctorId : null,
          proxyReason: f.proxyOn ? String(f.proxyReason).trim() : null
        };
        vm.soSaving = true;
        HIS.post('/api/his/surgery/' + HIS.idParam(s.id) + '/order', body)
          .then(function () {
            HIS.notifySuccess && HIS.notifySuccess('医嘱已开立');
            vm.soDlgVisible = false;
            vm.loadSurgeryOrders();
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.soSaving = false; });
      },
      sendSoPharmacy: function (row) {
        var vm = this;
        HIS.post('/api/his/inp/order/' + HIS.idParam(row.id) + '/send-pharmacy')
          .then(function () { HIS.notifySuccess && HIS.notifySuccess('已发送药房'); vm.loadSurgeryOrders(); })
          .catch(function (e) { HIS.notifyError(e); });
      },
      recallSoPharmacy: function (row) {
        var vm = this;
        HIS.post('/api/his/inp/order/' + HIS.idParam(row.id) + '/recall-pharmacy')
          .then(function () { HIS.notifySuccess && HIS.notifySuccess('已撤回发送'); vm.loadSurgeryOrders(); })
          .catch(function (e) { HIS.notifyError(e); });
      },
      cancelSoOrder: function (row) {
        var vm = this;
        confirmBox('确认作废该手术医嘱? 仅新开未执行医嘱可作废。', '作废确认')
          .then(function () { return HIS.put('/api/his/inp/order/' + HIS.idParam(row.id) + '/cancel'); })
          .then(function () { HIS.notifySuccess && HIS.notifySuccess('已作废'); vm.loadSurgeryOrders(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* 手麻P3a: 手术侧执行留痕(弹备注确认) */
      execSoOrder: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('确认执行该医嘱? 可填执行备注(选填)', '医嘱执行确认',
          { inputType: 'textarea', confirmButtonText: '确定执行', cancelButtonText: '取消' }).then(function (r) {
          var remark = (r && r.value) ? String(r.value).trim() : '';
          return HIS.post('/api/his/inp/order/' + HIS.idParam(row.id) + '/execute', { remark: remark });
        }).then(function () {
          HIS.notifySuccess && HIS.notifySuccess('已执行留痕'); vm.loadSurgeryOrders();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* 手麻P3a: 已发药未执行药品医嘱发起退药申请(reason 必填) */
      returnApplySo: function (row) {
        var vm = this;
        promptReason('退药申请', '请填写退药原因(必填)').then(function (r) {
          var reason = (r && r.value) ? String(r.value).trim() : '';
          if (!reason) { HIS.notifyError('退药原因不能为空'); return Promise.reject('cancel'); }
          return HIS.post('/api/his/inp/order/' + HIS.idParam(row.id) + '/return-apply', { reason: reason });
        }).then(function () {
          HIS.notifySuccess && HIS.notifySuccess('退药申请已提交, 待药房处理'); vm.loadSurgeryOrders();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 手麻P3b: 复苏(PACU) ===== */
      loadPacu: function () {
        var vm = this;
        vm.pacuLoading = true;
        var q = '/api/his/surgery-pacu/list?page=' + vm.pacuP + '&size=' + vm.pacuSize;
        var org = HIS.currentOrgId();
        if (org) { q += '&orgId=' + org; }
        if (vm.pacuFilters.status) { q += '&status=' + vm.pacuFilters.status; }
        if (vm.pacuFilters.kw) { q += '&kw=' + encodeURIComponent(vm.pacuFilters.kw); }
        HIS.get(q).then(function (d) {
          vm.pacuRows = (d && d.records) || [];
          vm.pacuTotal = (d && d.total) || 0;
          vm.pacuLoading = false;
        }).catch(function (e) { vm.pacuLoading = false; HIS.notifyError(e); });
      },
      onPacuPage: function (p) { this.pacuP = p; this.loadPacu(); },
      /* 详情抽屉回显本手术复苏单 */
      loadDetailPacu: function () {
        var vm = this;
        var s = vm.detail && vm.detail.surgery;
        if (!s) { vm.pacuList = []; vm.pacuHasInProgress = false; return; }
        vm.pacuDetailLoading = true;
        HIS.get('/api/his/surgery-pacu/by-surgery/' + HIS.idParam(s.id))
          .then(function (l) {
            vm.pacuList = l || [];
            vm.pacuHasInProgress = vm.pacuList.some(function (x) { return Number(x.status) === 1; });
          })
          .catch(function (e) { vm.pacuList = []; vm.pacuHasInProgress = false; HIS.notifyError(e); })
          .finally(function () { vm.pacuDetailLoading = false; });
      },
      openPacuAdmit: function () {
        var vm = this;
        var s = vm.detail && vm.detail.surgery;
        if (!s) { return; }
        vm.pacuAdmitForm = { surgeryId: s.id, aldreteAdmit: null, admitNote: '' };
        vm.pacuAdmitVisible = true;
      },
      submitPacuAdmit: function () {
        var vm = this;
        vm.pacuSaving = true;
        HIS.post('/api/his/surgery-pacu/' + HIS.idParam(vm.pacuAdmitForm.surgeryId) + '/admit',
          { aldreteAdmit: vm.pacuAdmitForm.aldreteAdmit, admitNote: vm.pacuAdmitForm.admitNote })
          .then(function () {
            HIS.notifySuccess && HIS.notifySuccess('已入复苏');
            vm.pacuAdmitVisible = false;
            vm.loadDetailPacu();
            if (vm.tab === 'pacu') { vm.loadPacu(); }
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.pacuSaving = false; });
      },
      openPacuVital: function (row) {
        this.pacuVitalRow = row;
        this.pacuVitalForm = { hp: '', hr: '', tm: '', p: '', s: '' };
        this.pacuVitalVisible = true;
      },
      submitPacuVital: function () {
        var vm = this;
        var f = vm.pacuVitalForm;
        if (!f.hp && !f.hr && !f.tm && !f.p && !f.s) { HIS.notifyError('请至少填写一项体征'); return; }
        vm.pacuSaving = true;
        HIS.post('/api/his/surgery-pacu/' + HIS.idParam(vm.pacuVitalRow.id) + '/vital',
          { hp: f.hp || null, hr: f.hr || null, tm: f.tm || null, p: f.p || null, s: f.s || null })
          .then(function (updated) {
            if (updated) { vm.pacuVitalRow = updated; }
            HIS.notifySuccess && HIS.notifySuccess('体征已登记');
            vm.pacuVitalForm = { hp: '', hr: '', tm: '', p: '', s: '' };
            vm.loadPacu();
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.pacuSaving = false; });
      },
      openPacuDischarge: function (row) {
        this.pacuDischargeRow = row;
        this.pacuDischargeForm = { aldreteDischarge: 10, dischargeDest: 1, dischargeNote: '' };
        this.pacuDischargeVisible = true;
      },
      submitPacuDischarge: function () {
        var vm = this;
        var f = vm.pacuDischargeForm;
        if (f.aldreteDischarge === null || f.aldreteDischarge === undefined) { HIS.notifyError('请填入复苏Aldrete评分'); return; }
        if (!f.dischargeDest) { HIS.notifyError('请选择去向'); return; }
        if (Number(f.aldreteDischarge) < 9 && !(f.dischargeNote && String(f.dischargeNote).trim())) {
          HIS.notifyError('Aldrete<9分须在备注说明理由'); return;
        }
        vm.pacuSaving = true;
        HIS.post('/api/his/surgery-pacu/' + HIS.idParam(vm.pacuDischargeRow.id) + '/discharge',
          { aldreteDischarge: f.aldreteDischarge, dischargeDest: f.dischargeDest, dischargeNote: f.dischargeNote || null })
          .then(function () {
            HIS.notifySuccess && HIS.notifySuccess('已出复苏');
            vm.pacuDischargeVisible = false;
            vm.loadPacu();
            if (vm.detailVisible) { vm.loadDetailPacu(); }
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.pacuSaving = false; });
      },
      viewPacu: function (row) {
        var vm = this;
        var vits = jsonArr(row.vitalsJson);
        var lines = ['患者: ' + orDash(row.patientName) + '  住院号: ' + orDash(row.inpNo),
          '入复苏: ' + fmtDT(row.admitTime) + '  Aldrete入: ' + orDash(row.aldreteAdmit),
          '状态: ' + pacuStatusLabel(row.status)];
        if (Number(row.status) === 2) {
          lines.push('出复苏: ' + fmtDT(row.dischargeTime) + '  Aldrete出: ' + orDash(row.aldreteDischarge) + '  去向: ' + pacuDestLabel(row.dischargeDest));
          if (row.dischargeNote) { lines.push('备注/理由: ' + row.dischargeNote); }
        }
        lines.push('体征登记 ' + vits.length + ' 条');
        vits.slice(-6).forEach(function (v, i) {
          lines.push('  #' + (i + 1) + ' ' + (v.t || '') + ' 血压' + (v.hp || '-') + ' 心率' + (v.hr || '-')
            + ' 体温' + (v.tm || '-') + ' 脉搏' + (v.p || '-') + ' SpO2' + (v.s || '-'));
        });
        ElementPlus.ElMessageBox.alert(escHtml(lines.join('\n')).replace(/\n/g, '<br/>'), '复苏详情',
          { dangerouslyUseHTMLString: true, confirmButtonText: '关闭' });
      },
      gotoPacuTab: function () {
        this.tab = 'pacu';
        this.pacuP = 1;
        this.loadPacu();
      },
      /* ===== 手麻P2b: 手术医嘱模板套用 ===== */
      openTplDialog: function () {
        var vm = this;
        vm.tplPick = { templateId: null, orderPhase: 2 };
        vm.tplDlgVisible = true;
        vm.tplLoading = true;
        HIS.get('/api/his/inp/order-template/list?page=1&size=200&applyScene=2&status=1')
          .then(function (d) { vm.tplList = (d && d.records) || []; })
          .catch(function (e) { vm.tplList = []; HIS.notifyError(e); })
          .finally(function () { vm.tplLoading = false; });
      },
      pickTpl: function (row) {
        var vm = this;
        vm.tplPick.templateId = row.id;
        if (row.surgeryPhase) { vm.tplPick.orderPhase = Number(row.surgeryPhase); }
      },
      applyTemplate: function () {
        var vm = this;
        var s = vm.detail && vm.detail.surgery;
        if (!s || !vm.tplPick.templateId) { return; }
        vm.tplSaving = true;
        HIS.post('/api/his/surgery/' + HIS.idParam(s.id) + '/order/from-template',
          { templateId: vm.tplPick.templateId, orderPhase: Number(vm.tplPick.orderPhase) })
          .then(function (list) {
            HIS.notifySuccess && HIS.notifySuccess('模板套用完成, 共开立 ' + ((list && list.length) || 0) + ' 条医嘱');
            vm.tplDlgVisible = false;
            vm.loadSurgeryOrders();
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.tplSaving = false; });
      },
      /* ===== 手麻P2d: 新生儿建档(分娩联动) ===== */
      loadNewbornGuide: function (surgery) {
        var vm = this;
        vm.nbLoading = true;
        HIS.get('/api/his/newborn/guide/' + HIS.idParam(surgery.id))
          .then(function (g) {
            vm.nbGuide = g || null;
            var mid = (g && g.motherInpVisitId) || surgery.inpVisitId;
            if (mid) {
              return HIS.get('/api/his/newborn/list?motherInpVisitId=' + HIS.idParam(mid));
            }
            vm.nbList = [];
            return null;
          })
          .then(function (l) { if (l) { vm.nbList = l; } })
          .catch(function (e) { vm.nbList = []; HIS.notifyError(e); })
          .finally(function () { vm.nbLoading = false; });
      },
      openNewbornDlg: function () {
        var vm = this;
        var s = vm.detail && vm.detail.surgery;
        if (!s) { return; }
        vm.nbForm = blankNewbornForm(s.id, (vm.nbGuide && vm.nbGuide.motherInpVisitId) || s.inpVisitId);
        vm.nbDlgVisible = true;
      },
      submitNewborn: function () {
        var vm = this;
        var f = vm.nbForm;
        if (!f.motherInpVisitId) { HIS.notifyError('该手术无住院就诊标识, 无法新生儿建档'); return; }
        if (!f.babyName || !String(f.babyName).trim()) { HIS.notifyError('请填写新生儿姓名'); return; }
        vm.nbSaving = true;
        HIS.post('/api/his/newborn/register', {
          motherInpVisitId: f.motherInpVisitId,
          surgeryId: f.surgeryId,
          babyName: String(f.babyName).trim(),
          babySex: f.babySex,
          birthTime: f.birthTime || null,
          apgar1: f.apgar1, apgar5: f.apgar5, apgar10: f.apgar10,
          weightG: f.weightG, heightCm: f.heightCm,
          birthType: f.birthType, remark: f.remark || null
        })
          .then(function () {
            HIS.notifySuccess && HIS.notifySuccess('新生儿建档完成');
            vm.nbDlgVisible = false;
            vm.loadNewbornGuide(vm.detail.surgery);
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.nbSaving = false; });
      },
      openAne: function (id) { this.aneSid = id; this.aneVisible = true; },
      openFee: function (id) { this.feeSid = id; this.feeVisible = true; },
      /* ==================== 手麻P0 ==================== */
      /* 角色×功能矩阵: 安排/急诊=医师或管理员; 报到流转退回调配=医师/护士/管理员; 记费模板/通知不受限 */
      loadRoles: function () {
        var admin = HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
        this.canArrange = admin || HIS.hasRole('DOCTOR');
        this.canOps = admin || HIS.hasRole('DOCTOR') || HIS.hasRole('NURSE');
      },
      loadPendingCount: function () {
        var vm = this;
        HIS.get('/api/his/surgery/notify/pending-count').then(function (c) { vm.pendingNotify = c || 0; }).catch(function () { });
      },
      goNotify: function () {
        if (HIS.go) { HIS.go('surgery-apply'); } else { HIS.notifyError('请到菜单【手术申请管理-通知管理】处理'); }
      },
      /* ===== 手术未安排池 ===== */
      loadUnarranged: function () {
        var vm = this;
        vm.unarrangedLoading = true;
        HIS.get('/api/his/surgery/unarranged-list').then(function (l) {
          vm.unarrangedRows = l || [];
          vm.unarrangedLoading = false;
        }).catch(function (e) { vm.unarrangedLoading = false; HIS.notifyError(e); });
      },
      openSchedApply: function (row) {
        this.schedUrgent = false;
        this.schedApplyRow = row;
        this.schedApplyTitle = orDash(row.apply_no) + '　' + orDash(row.patient_name) + ' - ' + orDash(row.surgery_name)
          + '（' + levelLabel(row.surgery_level) + '　拟主刀: ' + orDash(row.surgeon_name) + '）';
        this.schedForm = {
          scheduleDate: row.expect_time ? String(row.expect_time).replace('T', ' ').slice(0, 10) : today(),
          scheduleTime: '', roomNo: '',
          surgeonId: row.surgeon_id || null, anesthesiologistId: null, instrumentNurseId: null, circulatingNurseId: null
        };
        this.schedApplyVisible = true;
      },
      openUrgent: function () {
        this.schedUrgent = true;
        this.schedApplyRow = null;
        this.urgForm = { visitType: 1, inpVisitId: null, visitId: null, surgeryCode: '', surgeryName: '', surgeryLevel: null, anesthesiaType: null, expectTime: '', preOpDiag: '' };
        this.schedForm = { scheduleDate: today(), scheduleTime: '', roomNo: '', surgeonId: null, anesthesiologistId: null, instrumentNurseId: null, circulatingNurseId: null };
        this.schedApplyVisible = true;
        this.searchPatients('');
      },
      searchOutpVisits: function (kw) {
        var vm = this;
        vm.urgPtLoading = true;
        var q = '/api/his/surgery-apply/visit-search';
        if (kw) { q += '?keyword=' + encodeURIComponent(kw); }
        HIS.get(q).then(function (l) {
          vm.outpVisits = l || [];
          vm.urgPtLoading = false;
        }).catch(function (e) { vm.urgPtLoading = false; HIS.notifyError(e); });
      },
      outpPtLabel: function (v) {
        return orDash(v.patient_name) + (v.gender_name ? '·' + v.gender_name : '')
          + '  门诊号 ' + orDash(v.ipt_otp_no) + '  ' + orDash(v.dept_name);
      },
      schedBody: function () {
        var f = this.schedForm;
        return {
          scheduleDate: f.scheduleDate, scheduleTime: f.scheduleTime, roomNo: f.roomNo,
          surgeonId: f.surgeonId, anesthesiologistId: f.anesthesiologistId,
          instrumentNurseId: f.instrumentNurseId, circulatingNurseId: f.circulatingNurseId
        };
      },
      submitSchedApply: function () {
        var vm = this;
        var f = vm.schedForm;
        if (!f.scheduleDate) { HIS.notifyError('请选择手术日期'); return; }
        if (f.roomNo && !f.scheduleTime) { HIS.notifyError('选定手术间后须填时间段(冲突校验需要)'); return; }
        var p;
        if (vm.schedUrgent) {
          var u = vm.urgForm;
          if (!u.inpVisitId && !u.visitId) { HIS.notifyError('请选择患者'); return; }
          if (!u.surgeryName) { HIS.notifyError('请填写手术名称'); return; }
          var body = vm.schedBody();
          body.visitType = u.visitType;
          body.inpVisitId = u.visitType === 1 ? u.inpVisitId : null;
          body.visitId = u.visitType === 1 ? null : u.visitId;
          body.surgeryCode = u.surgeryCode;
          body.surgeryName = u.surgeryName;
          body.surgeryLevel = u.surgeryLevel;
          body.anesthesiaType = u.anesthesiaType;
          body.deadlineType = 3;
          body.expectTime = u.expectTime || null;
          body.preOpDiag = u.preOpDiag;
          p = HIS.post('/api/his/surgery/urgent-schedule', body);
        } else {
          p = HIS.post('/api/his/surgery/schedule-from-apply/' + vm.schedApplyRow.id, vm.schedBody());
        }
        vm.saving = true;
        p.then(function () {
          vm.saving = false;
          vm.schedApplyVisible = false;
          HIS.notifySuccess(vm.schedUrgent ? '急诊手术已安排' : '手术已安排');
          vm.loadUnarranged();
          vm.loadPendingCount();
          if (vm.tab === 'arranged') { vm.refreshArranged(); }
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      /* ===== 手术已安排(列表+排程板) ===== */
      refreshArranged: function () {
        if (this.arrangedView === 'board') { this.loadRoomBoard(); } else { this.loadArranged(); }
      },
      onArrangedView: function () { this.refreshArranged(); },
      loadArranged: function () {
        var vm = this;
        vm.arrangedLoading = true;
        var q = '/api/his/surgery/list?page=' + vm.arrP + '&size=' + vm.arrSize + '&statuses=2,7';
        if (vm.arrFilters.status) { q += '&statusEq=' + vm.arrFilters.status; }
        if (vm.arrFilters.dateRange && vm.arrFilters.dateRange.length === 2) {
          q += '&startDate=' + vm.arrFilters.dateRange[0] + '&endDate=' + vm.arrFilters.dateRange[1];
        }
        HIS.get(q).then(function (d) {
          var recs = (d && d.records) || [];
          var eq = vm.arrFilters.status;
          vm.arrangedRows = eq ? recs.filter(function (r) { return r.status === eq; }) : recs;
          vm.arrTotal = (d && d.total) || 0;
          vm.arrangedLoading = false;
        }).catch(function (e) { vm.arrangedLoading = false; HIS.notifyError(e); });
      },
      onArrPage: function (p) { this.arrP = p; this.loadArranged(); },
      loadRoomBoard: function () {
        var vm = this;
        vm.rbLoading = true;
        var q = '/api/his/surgery/room-board?date=' + (vm.boardDate || today());
        HIS.get(q).then(function (d) {
          vm.rb = d || { date: '', total: 0, rooms: [] };
          vm.rbLoading = false;
        }).catch(function (e) { vm.rbLoading = false; HIS.notifyError(e); });
      },
      /* ===== 登记报到 ===== */
      doRegSearch: function () {
        var vm = this;
        if (!vm.regKw) { HIS.notifyError('请输入申请单号/病历号/姓名'); return; }
        HIS.get('/api/his/surgery/register-query?keyword=' + encodeURIComponent(vm.regKw)).then(function (l) {
          vm.regRows = l || [];
          if (!vm.regRows.length) { HIS.notifyError('未找到待报到手术(仅状态: 已排程/已报到)'); }
        }).catch(function (e) { HIS.notifyError(e); });
      },
      onRegPick: function (row) { this.regSel = row; },
      doCheckInRow: function (row) {
        var vm = this;
        confirmBox('确认为【' + escHtml(row.patient_name) + '】办理手术报到登记？报到后状态置为已报到(可开始手术)。', '登记报到')
          .then(function () { return HIS.put('/api/his/surgery/' + row.id + '/check-in'); })
          .then(function () {
            HIS.notifySuccess('已报到');
            vm.doRegSearch();
            if (vm.tab === 'arranged') { vm.refreshArranged(); }
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doCheckIn: function (row) { this.doCheckInRow(row); },
      /* ===== 调配 / 退回 / 取消完成 ===== */
      openTransfer: function (row) {
        this.transId = row.id;
        this.transTitle = orDash(row.patient_name) + ' - ' + orDash(row.surgery_name) + '　当前手术间: ' + orDash(row.room_no) + '　' + fmtDate(row.schedule_date) + ' ' + orDash(row.schedule_time);
        this.transRoom = '';
        this.transVisible = true;
      },
      doTransfer: function () {
        var vm = this;
        if (!vm.transRoom) { HIS.notifyError('请选择目标手术间'); return; }
        vm.saving = true;
        HIS.put('/api/his/surgery/' + vm.transId + '/transfer', { roomNo: vm.transRoom }).then(function () {
          vm.saving = false;
          vm.transVisible = false;
          HIS.notifySuccess('已调配, 产生安排变动通知');
          vm.refreshArranged();
          vm.loadPendingCount();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      doCancelSchedule: function (row) {
        var vm = this;
        promptReason('退回手术【' + row.patient_name + ' - ' + row.surgery_name + '】', '请填写退回原因')
          .then(function (r) { return HIS.put('/api/his/surgery/' + row.id + '/cancel-schedule', { reason: r.value }); })
          .then(function () {
            HIS.notifySuccess('已退回, 来源申请回待安排池');
            vm.refreshArranged();
            vm.loadUnarranged();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 手术已完成 / 取消完成 ===== */
      loadFinished: function () {
        var vm = this;
        vm.finLoading = true;
        var q = '/api/his/surgery/list?page=' + vm.finP + '&size=' + vm.finSize + '&statuses=5';
        if (vm.finFilters.visitType) { q += '&visitType=' + vm.finFilters.visitType; }
        if (vm.finFilters.dateRange && vm.finFilters.dateRange.length === 2) {
          q += '&startDate=' + vm.finFilters.dateRange[0] + '&endDate=' + vm.finFilters.dateRange[1];
        }
        HIS.get(q).then(function (d) {
          vm.finRows = (d && d.records) || [];
          vm.finTotal = (d && d.total) || 0;
          vm.finLoading = false;
        }).catch(function (e) { vm.finLoading = false; HIS.notifyError(e); });
      },
      onFinPage: function (p) { this.finP = p; this.loadFinished(); },
      doCancelComplete: function (row) {
        var vm = this;
        confirmBox('确认取消完成【' + escHtml(row.patient_name) + ' - ' + escHtml(row.surgery_name) + '】？取消后回到术后状态, 可补录费用/退费。', '取消完成')
          .then(function () { return HIS.put('/api/his/surgery/' + row.id + '/cancel-complete'); })
          .then(function () { HIS.notifySuccess('已取消完成'); vm.loadFinished(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 麻醉已安排 ===== */
      loadAnes: function () {
        var vm = this;
        vm.anesLoading = true;
        HIS.get('/api/his/surgery/anesthesia-list').then(function (l) {
          vm.anesRows = l || [];
          vm.anesLoading = false;
        }).catch(function (e) { vm.anesLoading = false; HIS.notifyError(e); });
      },
      /* ===== 排程板拖拽 ===== */
      onDragStart: function (c) { this.dragCard = c; },
      onDragOver: function (ev, room) {
        ev.dataTransfer.dropEffect = 'move';
        this.dragOverRoom = room || '';
      },
      onDragLeave: function (room) {
        if (this.dragOverRoom === room) { this.dragOverRoom = ''; }
      },
      onDropToBoard: function (ev, room) {
        this.dragOverRoom = '';
        var c = this.dragCard;
        this.dragCard = null;
        if (!c || !this.canOps) { return; }
        if (!room || room === '未安排') { HIS.notifyError('请拖入具体手术间列'); return; }
        if (room === c.room_no) { HIS.notifyError('目标手术间未变化'); return; }
        this.dropCard = c;
        this.dropTitle = orDash(c.patient_name) + ' - ' + orDash(c.surgery_name) + '　原: ' + orDash(c.room_no) + ' ' + (c.schedule_time || '待定');
        this.dropForm = { roomNo: room, scheduleDate: this.rb.date || today(), scheduleTime: c.schedule_time || '' };
        this.dropVisible = true;
      },
      submitDrop: function () {
        var vm = this;
        var c = vm.dropCard;
        if (!c) { return; }
        if (!vm.dropForm.scheduleDate) { HIS.notifyError('请选择手术日期'); return; }
        if (!vm.dropForm.scheduleTime) { HIS.notifyError('请选择时间段(冲突校验需要)'); return; }
        var body = {
          scheduleDate: vm.dropForm.scheduleDate, scheduleTime: vm.dropForm.scheduleTime, roomNo: vm.dropForm.roomNo,
          surgeonId: c.surgeon_id || null
        };
        vm.saving = true;
        HIS.put('/api/his/surgery/' + c.id + '/reschedule', body).then(function () {
          vm.saving = false;
          vm.dropVisible = false;
          HIS.notifySuccess('已改排, 产生安排变动通知');
          vm.loadRoomBoard();
          vm.loadPendingCount();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      }
    },
    mounted: function () {
      this.loadRefs();
      this.loadRooms();
      this.loadRoles();
      this.loadPendingCount();
      this.loadUnarranged();
    }
  };
})();
