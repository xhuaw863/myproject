/* 住院医生站 - 医嘱面板: 长期/临时医嘱列表 + 开立对话框(type-ahead 药品/项目检索 + 成组清单) + 停止/作废
 * 接口: GET /api/his/inp/order/list | POST /api/his/inp/order | POST /api/his/inp/order/batch
 *       PUT /api/his/inp/order/{id}/stop | PUT /api/his/inp/order/{id}/cancel
 *       POST /api/his/inp/order/renew/{orderId}(T42续开: 仅已停止长期医嘱) | GET /api/his/inp/order/copy-data/{orderId}(副本预填)
 *       GET /api/his/inp/order-template/list | POST /api/his/inp/order/from-template(模板/套餐开嘱)
 *       POST /api/his/inp/order/rational-check(合理用药审查: 配伍/过敏/剂量 + 高警示/双人核对)
 * 检索源: /api/org-catalog/available/drug(本机构可开药品) /available/charge(收费项目) /available/med-dict(用法/频次)
 * 注册: HIS.components.InpOrderPanel (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 面板私有样式(高警示闪烁/审查提示条), 一次性注入 */
  (function ensureStyles() {
    if (document.getElementById('inp-order-extra-style')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-order-extra-style';
    st.textContent = [
      '@keyframes iwHighBlink { 50% { opacity:0.15; } }',
      '.iw-high-alert { color:var(--yb-danger); font-weight:700; font-size:16px; margin-left:4px; cursor:help; animation:iwHighBlink 0.9s step-start infinite; }',
      '.iw-warn-item { padding:6px 10px; margin-bottom:6px; background:var(--yb-warning-bg); border:1px solid var(--yb-warning-border); border-radius:var(--yb-r-sm); color:var(--yb-warning-strong); font-size:13px; }',
      '.iw-warn-item.danger { background:var(--yb-danger-bg); border-color:var(--yb-danger-border); color:var(--yb-danger-strong); }',
      /* 医疗单导航轨 + 右表单 */
      '.iw-mf { display:flex; gap:14px; align-items:stretch; }',
      '.iw-mf-rail { flex:none; width:96px; display:flex; flex-direction:column; gap:4px; padding:4px 0; border-right:1px solid var(--yb-border); }',
      '.iw-mf-item { position:relative; text-align:center; padding:8px 6px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-sm); color:var(--yb-ink-2); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease),color var(--yb-dur) var(--yb-ease); }',
      '.iw-mf-item:hover { background:var(--yb-surface-3); }',
      '.iw-mf-item.is-active { background:var(--yb-brand-subtle); color:var(--yb-brand); font-weight:600; }',
      '.iw-mf-item.is-active::before { content:\'\'; position:absolute; left:-4px; top:8px; bottom:8px; width:3px; border-radius:2px; background:var(--yb-brand); }',
      '.iw-mf-item .k { display:block; font-size:var(--yb-fs-cap); color:var(--yb-ink-4); margin-top:2px; }',
      '.iw-mf-body { flex:1; min-width:0; }',
      /* 模式标记 chip 行 + 徽标 */
      '.iw-mk { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:10px; }',
      '.iw-mk .lbl { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }',
      '.iw-badge { display:inline-flex; align-items:center; gap:3px; padding:0 7px; height:20px; border-radius:var(--yb-r-pill); font-size:var(--yb-fs-cap); line-height:1; white-space:nowrap; }',
      '.iw-badge--skin { background:var(--yb-fill-teal); color:#fff; }',
      '.iw-badge--abx { background:var(--yb-fill-purple); color:#fff; }',
      '.iw-badge--standby { background:var(--yb-fill-warning); color:#fff; }',
      '.iw-badge--emerg { background:var(--yb-fill-danger); color:#fff; }',
      '.iw-badge--linked { background:var(--yb-fill-info); color:#fff; }',
      /* 库存 / 换算预览 */
      '.iw-stock { font-size:var(--yb-fs-cap); padding:1px 6px; border-radius:var(--yb-r-sm); }',
      '.iw-stock.ok { background:var(--yb-success-bg); color:var(--yb-success); }',
      '.iw-stock.few { background:var(--yb-warning-bg); color:var(--yb-warning-strong); }',
      '.iw-stock.none { background:var(--yb-danger-bg); color:var(--yb-danger); }',
      '.iw-conv { display:flex; gap:14px; flex-wrap:wrap; padding:6px 10px; margin-top:8px; border-radius:var(--yb-r-sm); background:var(--yb-surface-2); font-size:var(--yb-fs-sm); color:var(--yb-ink-2); }',
      '.iw-conv b { color:var(--yb-ink-1); font-family:var(--yb-font-mono); }',
      /* 闭环时间线 */
      '.iw-tl { position:relative; margin:8px 0 0; padding-left:6px; }',
      '.iw-tl-item { position:relative; display:flex; align-items:center; gap:10px; padding:0 0 16px 22px; }',
      '.iw-tl-item::before { content:\'\'; position:absolute; left:6px; top:2px; bottom:-2px; width:2px; background:var(--yb-border); }',
      '.iw-tl-item:last-child::before { display:none; }',
      '.iw-tl-item .dot { position:absolute; left:1px; top:1px; width:12px; height:12px; border-radius:50%; background:var(--yb-surface); border:2px solid var(--yb-border-strong); }',
      '.iw-tl-item.is-done .dot { background:var(--yb-brand); border-color:var(--yb-brand); }',
      '.iw-tl-item .lb { font-size:var(--yb-fs-base); color:var(--yb-ink-1); }',
      '.iw-tl-item:not(.is-done) .lb { color:var(--yb-ink-4); }',
      '.iw-tl-item .tm { margin-left:auto; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); font-family:var(--yb-font-mono); }',
      '.iw-tl-item .nt { font-size:var(--yb-fs-cap); color:var(--yb-danger-strong); }',
      /* 说明书网格 */
      '.iw-ldesc { display:grid; grid-template-columns:repeat(2,1fr); gap:2px 18px; font-size:var(--yb-fs-base); }',
      '.iw-ldesc .kv { display:flex; gap:8px; padding:5px 0; border-bottom:1px dashed var(--yb-divider); }',
      '.iw-ldesc .kv span { color:var(--yb-ink-3); flex:none; width:82px; }',
      '.iw-ldesc .kv b { color:var(--yb-ink-1); font-weight:600; }',
      '@media (prefers-reduced-motion:reduce) { .iw-high-alert { animation:none; } .iw-mf-item { transition:none; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  const ORDER_TYPE = { 1: '长期', 2: '临时' };
  const ORDER_STATUS = { 1: '新开', 2: '已审核', 3: '执行中', 4: '已完成', 5: '已停止', 6: '已作废' };
  const ORDER_STATUS_TYPE = { 1: 'info', 2: 'warning', 3: 'success', 4: 'success', 5: 'danger', 6: 'info' };
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };

  /* ===== 医疗单导航轨(录入对话框左栏): kind=子表单形态, cat 映射 ORDER_CATEGORY, kw=分类前缀键 ===== */
  const MED_FORMS = [
    { kind: 'drug', cat: 1, name: '西成药', kw: 'y' },
    { kind: 'herb', cat: 1, name: '草药', kw: 'y' },
    { kind: 'lab', cat: 3, name: '检验', kw: 'l' },
    { kind: 'exam', cat: 2, name: '检查', kw: 'c' },
    { kind: 'treat', cat: 4, name: '治疗', kw: 'z' },
    { kind: 'nursing', cat: 5, name: '护理', kw: 'h' },
    { kind: 'diet', cat: 6, name: '饮食', kw: 'e' },
    { kind: 'surgery', cat: 2, name: '手术', kw: 's' },
    { kind: 'consult', cat: 7, name: '会诊', kw: 'u' },
    { kind: 'text', cat: 7, name: '文字医嘱', kw: 'w' }
  ];
  const MED_FORM_MAP = {};
  MED_FORMS.forEach(function (f) { MED_FORM_MAP[f.kind] = f; });
  /* 检索框首字符前缀 → 医疗单 kind(全键盘快速切类) */
  const PREFIX_KIND = { y: 'drug', c: 'exam', l: 'lab', z: 'treat', h: 'nursing', s: 'surgery', w: 'text' };
  /* 输液类用法/剂型关键词(命中即纳入当日液体总量统计并开放滴速录入) */
  const IV_KEYWORDS = ['静滴', '静脉滴注', '静脉点滴', '静点', '静注', '静脉注射', '静脉', '泵入', '输注', '滴注', 'ivgtt', 'iv', '微泵'];
  /* 药品库存模糊档阈值(最小单位): >=充足 下界, >=少量 下界, 其余无 */
  const STOCK_ENOUGH = 100, STOCK_FEW = 20;

  /* 医嘱内容结构化标记: 前缀 [急诊]/[出院带药×N天]/[长期备用]/[临时备用] + 后缀 " | key:val" */
  function decodeMarks(content) {
    const raw = String(content || '');
    const m = { emergency: false, dischargeDays: 0, standby: '', linkedSkin: false, dripRate: '', sites: '', film: '', report: false, metrics: '', specimen: '', base: raw };
    let body = raw;
    if (body.indexOf('[急诊]') >= 0) { m.emergency = true; body = body.replace('[急诊]', ''); }
    const dd = body.match(/\[出院带药×(\d+)天\]/);
    if (dd) { m.dischargeDays = Number(dd[1]) || 0; body = body.replace(dd[0], ''); }
    if (body.indexOf('[长期备用]') >= 0) { m.standby = 'PRN'; body = body.replace('[长期备用]', ''); }
    if (body.indexOf('[临时备用]') >= 0) { m.standby = 'SOS'; body = body.replace('[临时备用]', ''); }
    const parts = body.split(' | ');
    body = parts[0];
    for (let i = 1; i < parts.length; i++) {
      const p = parts[i], idx = p.indexOf(':'), k = idx < 0 ? p : p.substring(0, idx), v = idx < 0 ? '' : p.substring(idx + 1);
      if (k === '滴速') { m.dripRate = v; }
      else if (k === '部位') { m.sites = v; }
      else if (k === '胶片') { m.film = v; }
      else if (k === '图文报告') { m.report = true; }
      else if (k === '指标') { m.metrics = v; }
      else if (k === '标本') { m.specimen = v; }
      else if (k === '关联' && v === '皮试') { m.linkedSkin = true; }
    }
    m.base = body.trim();
    return m;
  }
  /* 用量→是否输液类: 依 用法名/剂型名 关键词粗判 */
  function isInfusion(usageName, dosformName) {
    const s = (String(usageName || '') + String(dosformName || '')).toLowerCase();
    for (let i = 0; i < IV_KEYWORDS.length; i++) { if (s.indexOf(IV_KEYWORDS[i]) >= 0) { return true; } }
    return false;
  }
  /* 剂量→发药数量(最小单位): 按 unit_dose 除后依 round_rule 取整 */
  function calcDispenseQty(dose, unitDose, roundRule) {
    const d = Number(dose), u = Number(unitDose);
    if (!d || !u || u <= 0) { return null; }
    const raw = d / u;
    if (Number(roundRule) === 2) { return Math.floor(raw); }
    if (Number(roundRule) === 3) { return Math.round(raw); }
    return Math.ceil(raw);
  }

  /* 批次D: 药品→处方权限 type 映射(优先级 麻醉>精一>精二>抗菌), 据 drugClass/drugClassName/abxGrade 判定; 无命中返回 '' 不拦截 */
  const PRESCRIBE_LABELS = { narcotic: '麻醉药品', psych1: '第一类精神药品', psych2: '第二类精神药品', abx: '抗菌药物' };
  function prescribeTypeOf(drug) {
    if (!drug) { return ''; }
    const cls = String(drug.drugClass || '') + ' ' + String(drug.drugClassName || '');
    if (cls.indexOf('麻醉') >= 0) { return 'narcotic'; }
    if (cls.indexOf('第一类精神') >= 0 || cls.indexOf('精神一') >= 0) { return 'psych1'; }
    if (cls.indexOf('第二类精神') >= 0 || cls.indexOf('精神二') >= 0) { return 'psych2'; }
    if (drug.abxGrade != null && String(drug.abxGrade).trim() !== '') { return 'abx'; }
    return '';
  }

  /* 内联 SVG 图标(项目未引入图标库, 统一 24 视框 / currentColor 染色), 经 v-html 渲染 */
  const ICONS = {
    pause: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M9 5v14M15 5v14"/></svg>',
    ban: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M5.7 5.7l12.6 12.6"/></svg>',
    copy: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
    renew: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>',
    exec: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>',
    more: '<svg class="iw-ico-more" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>',
    skin: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3c3 4 5 6.5 5 9a5 5 0 0 1-10 0c0-2.5 2-5 5-9z"/></svg>',
    doc: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h6M9 17h6"/></svg>',
    timeline: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3v18"/><circle cx="6" cy="7" r="2"/><circle cx="6" cy="14" r="2"/><path d="M10 7h9M10 14h6"/></svg>'
  };

  function money(value) {
    const n = Number(value);
    return isNaN(n) ? '0.00' : n.toFixed(2);
  }

  function shortTime(value) {
    if (!value) { return '-'; }
    const s = String(value).replace('T', ' ');
    return s.length >= 16 ? s.substring(5, 16) : s;
  }

  function emptyForm() {
    return {
      orderType: 1,
      orderCategory: 1,
      orderContent: '',
      drugId: null,
      chargeItemId: null,
      spec: '',
      dosage: '',
      dosageUnit: '',
      usageCode: null,
      freqCode: null,
      quantity: 1
    };
  }

  const InpOrderPanel = {
    name: 'InpOrderPanel',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return {
        loading: false,
        orders: [],
        total: 0,
        page: 1,
        size: 50,
        filterType: 0,
        filterStatus: null,
        filterCategory: 0,
        filterDeptId: null,
        deptMap: {},
        deptFilterIds: [],
        dialogVisible: false,
        form: emptyForm(),
        pickedDrug: null,
        pickedCharge: null,
        pickDrugId: null,
        pickChargeId: null,
        drugOptions: [],
        drugSearching: false,
        chargeOptions: [],
        chargeSearching: false,
        usageOptions: [],
        freqOptions: [],
        dictLoaded: false,
        pendingItems: [],
        submitting: false,
        statuses: ORDER_STATUS,
        categories: ORDER_CATEGORY,
        contentTouched: false,
        /* 模板医嘱/套餐对话框 */
        tplVisible: false, tplMode: 1,
        tplRows: [], tplTotal: 0, tplPage: 1, tplSize: 10, tplLoading: false, tplApplying: false,
        tplKeyword: '', tplTypeFilter: null,
        /* 合理用药审查 */
        checkVisible: false, checkLoading: false, checkResult: null, overrideAccepted: false,
        /* 医保预审提示(T42): 开立/续开回执的无医保编码预警, 列表上方告警条 3 秒自动消失 */
        insTip: null,
        /* 操作图标 / 行右键菜单 / 执行详情 */
        icons: ICONS,
        ctx: { visible: false, x: 0, y: 0, row: null },
        execVisible: false, execRow: null,
        /* ===== 医疗单录入工作台(批次A/B/C) ===== */
        medForms: MED_FORMS,
        mfKind: 'drug',
        mark: { emergency: false, dischargeDays: 0, standby: '' },
        dripRate: '',
        skinLinked: false,
        examSel: { sites: [], film: '', report: false, contrast: '平扫' },
        labSel: { specimen: '', metrics: '' },
        /* 医保检查控费加收双开关(后端 inpatient.exam_part_surcharge_enabled / exam_contrast_surcharge_enabled, 默认停用; 未启用时隐藏对应加收预估避免误导) */
        examPartEnabled: false,
        examContrastEnabled: false,
        siteOptions: ['头部', '颈部', '胸部', '腹部', '骨盆', '脊柱', '左上肢', '右上肢', '左下肢', '右下肢'],
        specimenOptions: ['全血', '血清', '血浆', '尿液', '粪便', '痰液', '分泌物', '脑脊液', '胸腹水'],
        filmOptions: ['纸质(按项目)', '纸质(按部位)', '数字胶片'],
        /* ===== 医嘱说明书 / 闭环详情(批次D) ===== */
        leafletVisible: false, leafletLoading: false, leafletData: null, leafletName: '',
        execTimeline: [],
        /* ===== 手麻P1: 发起手术申请(当前住院患者) ===== */
        surgApplyVisible: false, surgApplySaving: false,
        surgApplyForm: { visitType: 1, inpVisitId: null, surgeryCode: '', surgeryName: '', surgeryLevel: null, anesthesiaType: null, surgeonId: null, expectTime: '', deadlineType: 1, preOpDiag: '', applyReason: '' },
        surgCandidates: [], surgCandLoading: false,
        surgLevelOpts: [{ value: 1, label: '一级' }, { value: 2, label: '二级' }, { value: 3, label: '三级' }, { value: 4, label: '四级' }],
        surgAneOpts: [{ value: 1, label: '全身麻醉' }, { value: 2, label: '局部麻醉' }, { value: 3, label: '椎管内麻醉' }, { value: 4, label: '神经阻滞' }, { value: 5, label: '复合麻醉' }, { value: 6, label: '其他' }]
      };
    },
    computed: {
      isDrug() { return Number(this.form.orderCategory) === 1; },
      /* 住院检查计价部位数(按已选部位去重计数) */
      examSiteCount() {
        const s = (this.examSel && this.examSel.sites) || [];
        const uniq = {};
        s.forEach(function (x) { if (x) { uniq[x] = 1; } });
        return Object.keys(uniq).length || 0;
      },
      /* 多部位/增强加收预估(仅前端提示, 实际以服务端计费规则为准): 放射类 CT(2103*)/MR(2102*)/X线(2101*) 及超声(22xx) 第2个部位起每个按主项目50%; CT/MR平扫项目选"增强"再一次性加收50% */
      examSurchargeEstimate() {
        if (Number(this.form.orderCategory) !== 2) { return 0; }
        const price = Number(this.pickedCharge && (this.pickedCharge.execPrice != null ? this.pickedCharge.execPrice : this.pickedCharge.price)) || 0;
        if (price <= 0) { return 0; }
        const code = String((this.pickedCharge && this.pickedCharge.itemCode) || '');
        const name = String((this.pickedCharge && this.pickedCharge.itemName) || '');
        const n = this.examSiteCount;
        const contrast = this.examSel && this.examSel.contrast === '增强';
        const isImg = code.indexOf('2101') === 0 || code.indexOf('2102') === 0 || code.indexOf('2103') === 0 || code.indexOf('22') === 0;
        let sur = 0;
        if (this.examPartEnabled && isImg && n > 1) { sur += price * 0.5 * (n - 1); }
        const isCtMrPlain = (code.indexOf('2102') === 0 || code.indexOf('2103') === 0) && name.indexOf('增强') < 0 && name.indexOf('造影') < 0;
        if (this.examContrastEnabled && contrast && isCtMrPlain) { sur += price * 0.5; }
        return sur;
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
      pendingTotal() {
        return this.pendingItems.reduce((sum, it) => sum + (Number(it.quantity) || 0) * (Number(it._price) || 0), 0);
      },
      drugUnitPrice() { return this.pickedDrug ? Number(this.pickedDrug.retailPrice) || 0 : 0; },
      /* ===== 医疗单工作台派生 ===== */
      mfInfo() { return MED_FORM_MAP[this.mfKind] || MED_FORMS[0]; },
      /* 当前在用(未停未废)的输液类医嘱清单(供当日液体总量提示) */
      ivActiveOrders() {
        const vm = this;
        return this.orders.filter(function (o) {
          if (!(o.orderStatus >= 1 && o.orderStatus <= 3)) { return false; }
          const usageName = vm.usageMap[o.usageCode] || o.usageCode || '';
          return Number(o.orderCategory) === 1 && isInfusion(usageName, '');
        });
      },
      /* 录入表单是否输液类(命中则开放滴速并计入总量) */
      isInfusionNow() {
        if (!this.isDrug || !this.pickedDrug) { return false; }
        return isInfusion(this.usageMap[this.form.usageCode] || '', this.pickedDrug.dosformName || this.pickedDrug.dosform || '');
      },
      /* 剂量→发药量/包装换算预览 */
      dispensePreview() {
        const d = this.pickedDrug;
        if (!d || !this.form.dosage) { return null; }
        const min = calcDispenseQty(this.form.dosage, d.unitDose, d.roundRule);
        const ratio = Number(d.packRatio) || 0;
        const packs = (min != null && ratio > 0) ? Math.ceil(min / ratio) : null;
        return { min: min, minUnit: d.minUnit || '', packs: packs, packUnit: d.packUnit || '' };
      },
      /* 库存模糊档(仅 available/drug 回填 stockQty 时可见) */
      stockText() {
        const q = this.pickedDrug && this.pickedDrug.stockQty;
        if (q == null || q === '') { return null; }
        const n = Number(q);
        if (isNaN(n)) { return null; }
        if (n >= STOCK_ENOUGH) { return { t: '充足', cls: 'ok' }; }
        if (n >= STOCK_FEW) { return { t: '少量', cls: 'few' }; }
        return { t: '无', cls: 'none' };
      },
      /* 列表按分类客户端过滤(当前页) */
      shownOrders() {
        if (!this.filterCategory) { return this.orders; }
        return this.orders.filter(o => Number(o.orderCategory) === Number(this.filterCategory));
      },
      /* 行右键菜单防溢出定位 */
      ctxStyle() {
        const x = Math.min(this.ctx.x, Math.max(window.innerWidth - 184, 0));
        const y = Math.min(this.ctx.y, Math.max(window.innerHeight - 184, 0));
        return { left: x + 'px', top: y + 'px' };
      }
    },
    watch: {
      visitId: { immediate: true, handler() { this.resetList(); } }
    },
    methods: {
      money,
      /* ===== 手麻P1: 发起手术申请(当前住院患者, 主刀按级别权限过滤) ===== */
      openSurgApply() {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        const vm = this;
        vm.surgApplyForm = { visitType: 1, inpVisitId: HIS.id(vm.visitId), surgeryCode: '', surgeryName: '', surgeryLevel: null, anesthesiaType: null, surgeonId: null, expectTime: '', deadlineType: 1, preOpDiag: '', applyReason: '' };
        vm.surgCandidates = [];
        vm.surgApplyVisible = true;
        vm.loadSurgCandidates();
      },
      loadSurgCandidates() {
        const vm = this;
        vm.surgCandLoading = true;
        let q = '/api/his/surgery-auth-rule/candidates?';
        if (vm.surgApplyForm.surgeryCode) { q += 'surgeryCode=' + encodeURIComponent(vm.surgApplyForm.surgeryCode) + '&'; }
        if (vm.surgApplyForm.surgeryName) { q += 'surgeryName=' + encodeURIComponent(vm.surgApplyForm.surgeryName) + '&'; }
        if (vm.surgApplyForm.surgeryLevel) { q += 'surgeryLevel=' + vm.surgApplyForm.surgeryLevel; }
        HIS.get(q).then(function (l) {
          vm.surgCandidates = l || [];
          vm.surgCandLoading = false;
          if (vm.surgApplyForm.surgeonId) {
            const hit = vm.surgCandidates.filter(function (c) { return HIS.sameId(c.id, vm.surgApplyForm.surgeonId) && c.allowed; });
            if (!hit.length && vm.surgCandidates.length) { vm.surgApplyForm.surgeonId = null; }
          }
        }).catch(function (e) { vm.surgCandLoading = false; HIS.notifyError(e); });
      },
      submitSurgApply() {
        const vm = this;
        const f = vm.surgApplyForm;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        if (!f.surgeryName) { HIS.notifyError('请填写手术名称'); return; }
        if (!f.surgeonId) { HIS.notifyError('请选择拟主刀医师'); return; }
        const body = {
          visitType: 1, inpVisitId: HIS.id(vm.visitId), visitId: null,
          surgeryCode: f.surgeryCode, surgeryName: f.surgeryName, surgeryLevel: f.surgeryLevel,
          anesthesiaType: f.anesthesiaType, surgeonId: f.surgeonId,
          expectTime: f.expectTime || null, deadlineType: f.deadlineType,
          preOpDiag: f.preOpDiag, applyReason: f.applyReason, specialReq: ''
        };
        vm.surgApplySaving = true;
        HIS.post('/api/his/surgery-apply', body).then(function (a) {
          vm.surgApplySaving = false;
          vm.surgApplyVisible = false;
          HIS.notifySuccess('手术申请已提交: ' + ((a && a.applyNo) ? a.applyNo : '') + ', 请到病区复核');
        }).catch(function (e) { vm.surgApplySaving = false; HIS.notifyError(e); });
      },
      orderTypeText(v) { return ORDER_TYPE[v] || '-'; },
      statusText(v) { return ORDER_STATUS[v] || '-'; },
      statusTag(v) { return ORDER_STATUS_TYPE[v] || ''; },
      categoryText(v) { return ORDER_CATEGORY[v] || '-'; },
      usageText(code) { return this.usageMap[code] || code || '-'; },
      freqText(code) { return this.freqMap[code] || code || '-'; },
      timeText: shortTime,
      resetList() {
        this.page = 1;
        this.filterType = 0;
        this.filterStatus = null;
        this.filterCategory = 0;
        this.filterDeptId = null;
        this.orders = [];
        this.total = 0;
        if (this.visitId) { this.load(); }
      },
      loadDepts() {
        const vm = this;
        HIS.get('/api/his/dept/list').then(function (list) {
          const map = {};
          (list || []).forEach(function (d) { map[d.id] = d.deptName; });
          vm.deptMap = map;
        }).catch(function () { /* 科室名仅展示用, 失败静默 */ });
      },
      deptNameOf(id) { return this.deptMap[id] || this.deptMap[String(id)] || ('科室' + id); },
      load() {
        const vm = this;
        if (!vm.visitId) { return; }
        vm.loading = true;
        let url = '/api/his/inp/order/list?inpVisitId=' + HIS.idParam(vm.visitId)
          + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.filterType) { url += '&orderType=' + vm.filterType; }
        if (vm.filterStatus) { url += '&orderStatus=' + vm.filterStatus; }
        if (vm.filterDeptId) { url += '&deptId=' + HIS.idParam(vm.filterDeptId); }
        HIS.get(url)
          .then(function (data) {
            const list = (data && data.records) || [];
            vm.decorate(list);
            vm.orders = list;
            vm.total = Number(data && data.total) || list.length;
            /* 未施加开单科室过滤时, 从本页不同 orderDeptId 沉淀下拉选项(供切换) */
            if (!vm.filterDeptId) {
              const seen = {}, ids = [];
              list.forEach(function (r) { if (r.orderDeptId != null) { const k = String(r.orderDeptId); if (!seen[k]) { seen[k] = 1; ids.push(r.orderDeptId); } } });
              vm.deptFilterIds = ids;
            }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      /* 成组标注 + 行标记解析: 同 groupNo 相邻行归同组(后端按时间倒序, 组内天然相邻), 组首行显组序号; 预解析行标记供图标化 */
      decorate(list) {
        let seq = 0;
        const seen = {};
        let prevGroup = null;
        (list || []).forEach(function (row) {
          if (row.groupNo) {
            if (seen[row.groupNo] == null) { seen[row.groupNo] = ++seq; }
            row._groupLabel = '组' + seen[row.groupNo];
            row._groupFirst = row.groupNo !== prevGroup;
          } else {
            row._groupLabel = '';
            row._groupFirst = false;
          }
          prevGroup = row.groupNo;
          row._marks = decodeMarks(row.orderContent);
        });
      },
      rowClass({ row }) {
        const cls = [Number(row.orderType) === 1 ? 'iw-order--long' : 'iw-order--temp'];
        if (row._groupFirst) { cls.push('iw-order--grp-first'); }
        else if (row.groupNo) { cls.push('iw-order--grp-child'); }
        return cls.join(' ');
      },
      onPageChange(p) { this.page = p; this.load(); },
      /* 过滤条件变化: 重置到第一页再查 */
      onFilterChange() { this.page = 1; this.load(); },
      /* ===== 开立对话框 ===== */
      openDialog() {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.resetEntry('drug');
        this.dialogVisible = true;
        this.loadMedDict();
      },
      /* 复位录入工作台(表单 + 医疗单形态 + 逐行瞬时态) */
      resetEntry(kind) {
        this.form = emptyForm();
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.contentTouched = false;
        this.checkResult = null;
        this.overrideAccepted = false;
        const k = kind || this.mfKind || 'drug';
        this.mfKind = k;
        this.form.orderCategory = (MED_FORM_MAP[k] || MED_FORMS[0]).cat;
        this.resetTransient();
      },
      /* 仅清逐行附加标记/记费选择(加入一条后连续录入时调用) */
      resetTransient() {
        this.mark = { emergency: false, dischargeDays: 0, standby: '' };
        this.dripRate = '';
        this.skinLinked = false;
        this.examSel = { sites: [], film: '', report: false, contrast: '平扫' };
        this.labSel = { specimen: '', metrics: '' };
      },
      /* 切换医疗单形态(导航轨/前缀路由共用) */
      selectForm(kind) {
        if (!MED_FORM_MAP[kind]) { return; }
        if (kind === this.mfKind) { return; }
        this.mfKind = kind;
        this.form.orderCategory = MED_FORM_MAP[kind].cat;
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.form.drugId = null;
        this.form.chargeItemId = null;
        this.form.spec = '';
        this.form.dosage = '';
        this.form.usageCode = null;
        this.form.freqCode = null;
        if (!this.contentTouched) { this.form.orderContent = ''; }
        this.resetTransient();
      },
      /* 前缀路由: 首字符命中分类前缀且与当前形态不同 → 切形并以剩余字符重新检索 */
      routePrefix(kw) {
        const s = String(kw || '').trim();
        const kind = PREFIX_KIND[s.charAt(0)];
        if (!kind) { return null; }
        const rest = s.substring(1).trim();
        if (kind !== this.mfKind) { this.selectForm(kind); }
        return rest;
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
      remoteDrugSearch(query) {
        const vm = this;
        let kw = String(query || '').trim();
        const routed = vm.routePrefix(kw);
        if (routed !== null) {
          if (!vm.isDrug) { vm.drugOptions = []; vm.remoteChargeSearch(routed); return; }
          kw = routed;
        }
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
        const drug = vm.drugOptions.find(d => HIS.sameId(d.id, id));
        if (!drug) { return; }
        vm.pickedDrug = drug;
        vm.form.drugId = drug.id;
        vm.form.spec = drug.spec || '';
        if (!vm.form.dosageUnit) { vm.form.dosageUnit = drug.doseUnit || drug.minUnit || ''; }
        if (!vm.contentTouched) { vm.form.orderContent = vm.composeDrugContent(); }
        /* 选药即触发合理用药审查(配伍/过敏/重复用药/剂量/皮试) */
        vm.checkResult = null;
        vm.overrideAccepted = false;
        vm.rationalCheck();
        /* B3: 选药后自动聚焦剂量框(el-select 选后会回抢焦点, 须延时夺回, 同门诊经验) */
        setTimeout(function () { if (vm.$refs.inpDosage && !vm.form.dosage) { vm.$refs.inpDosage.focus(); } }, 220);
      },
      /* 频次日需识别备用医嘱(PRN 长期备用 / SOS 临时备用) */
      detectStandby(freqCode) {
        if (!freqCode) { return ''; }
        const s = (String(freqCode) + (this.freqMap[freqCode] || '')).toLowerCase();
        if (s.indexOf('sos') >= 0 || s.indexOf('临时备用') >= 0 || s.indexOf('需要时') >= 0) { return 'SOS'; }
        if (s.indexOf('prn') >= 0 || s.indexOf('长期备用') >= 0) { return 'PRN'; }
        return '';
      },
      onFreqChange() {
        this.mark.standby = this.detectStandby(this.form.freqCode);
        if (!this.contentTouched) { this.form.orderContent = this.composeDrugContent(); }
      },
      remoteChargeSearch(query) {
        const vm = this;
        let kw = String(query || '').trim();
        const routed = vm.routePrefix(kw);
        if (routed !== null) {
          if (vm.isDrug) { vm.chargeOptions = []; vm.remoteDrugSearch(routed); return; }
          kw = routed;
        }
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
        const item = vm.chargeOptions.find(x => HIS.sameId(x.id, id));
        if (!item) { return; }
        vm.pickedCharge = item;
        vm.form.chargeItemId = item.id;
        vm.form.spec = item.spec || '';
        if (!vm.contentTouched) { vm.form.orderContent = item.itemName || ''; }
      },
      composeDrugContent() {
        const d = this.pickedDrug;
        if (!d) { return this.form.orderContent || ''; }
        let text = d.genericName || d.itemName || '';
        if (this.form.dosage) { text += ' ' + this.form.dosage + (this.form.dosageUnit || ''); }
        const usage = this.usageMap[this.form.usageCode];
        if (usage) { text += ' ' + usage; }
        const freq = this.freqMap[this.form.freqCode];
        if (freq) { text += ' ' + freq; }
        return text;
      },
      autoCompose() {
        if (this.isDrug) { this.form.orderContent = this.composeDrugContent(); }
        else if (this.pickedCharge) { this.form.orderContent = this.pickedCharge.itemName || ''; }
        this.contentTouched = false;
      },
      onContentInput() { this.contentTouched = true; },
      onCategoryChange() {
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.form.drugId = null;
        this.form.chargeItemId = null;
        this.form.spec = '';
        this.form.dosage = '';
        this.form.usageCode = null;
        this.form.freqCode = null;
        this.contentTouched = false;
      },
      /* ===== 内容标记组装与业务联动(批次B/C) ===== */
      /* 当前用法是否输液类(不依赖 pickedDrug, 供内容组装用) */
      isInfusionUsage() {
        const usageName = this.usageMap[this.form.usageCode] || '';
        const df = this.pickedDrug ? (this.pickedDrug.dosformName || this.pickedDrug.dosform || '') : '';
        return isInfusion(usageName, df);
      },
      /* 将逐行标记/记费选择拼接为最终 orderContent(前缀[...]+后缀 | k:v) */
      assembleContent(base) {
        let c = String(base || '').trim();
        const pre = [];
        if (this.mark.emergency) { pre.push('[急诊]'); }
        if (Number(this.mark.dischargeDays) > 0) { pre.push('[出院带药×' + Number(this.mark.dischargeDays) + '天]'); }
        if (this.mark.standby === 'PRN') { pre.push('[长期备用]'); }
        else if (this.mark.standby === 'SOS') { pre.push('[临时备用]'); }
        if (pre.length) { c = pre.join('') + c; }
        const suf = [];
        if (this.isDrug && this.isInfusionUsage() && this.dripRate) { suf.push('滴速:' + this.dripRate); }
        if (this.mfKind === 'exam') {
          if (this.examSel.sites && this.examSel.sites.length) { suf.push('部位:' + this.examSel.sites.join(',')); }
          if (this.examSel.film) { suf.push('胶片:' + this.examSel.film); }
          if (this.examSel.report) { suf.push('图文报告'); }
        } else if (this.mfKind === 'lab') {
          if (this.labSel.specimen) { suf.push('标本:' + this.labSel.specimen); }
          if (this.labSel.metrics) { suf.push('指标:' + this.labSel.metrics); }
        }
        if (this.skinLinked) { suf.push('关联:皮试'); }
        if (suf.length) { c = c + ' | ' + suf.join(' | '); }
        return c;
      },
      /* 一键追加皮试医嘱(与主医嘱同批提交→同组) */
      addSkinTest() {
        const d = this.pickedDrug;
        if (!d) { return; }
        this.pendingItems.push({
          orderType: 2, orderCategory: 1, orderContent: '皮试（' + (d.genericName || '') + '）',
          drugId: d.id, chargeItemId: null, spec: d.spec || null, dosage: null, dosageUnit: null,
          usageCode: null, freqCode: null, quantity: 1,
          _typeText: '临时', _categoryText: '药品', _price: 0, _doseText: '皮试', _skin: true
        });
        this.skinLinked = true;
        ElementPlus.ElMessage.success('已添加皮试医嘱, 将与主医嘱同组提交');
      },
      /* 药品说明书(按 drugId 回查整行目录) */
      openLeaflet(row) {
        const vm = this;
        const drugId = row && row.drugId;
        if (!drugId) { ElementPlus.ElMessage.warning('该医嘱未关联药品'); return; }
        vm.leafletData = null; vm.leafletName = row.orderContent || ''; vm.leafletVisible = true; vm.leafletLoading = true;
        HIS.get('/api/community-dict/drug/' + HIS.idParam(drugId))
          .then(function (d) { vm.leafletData = d || null; })
          .catch(HIS.notifyError)
          .finally(function () { vm.leafletLoading = false; });
      },
      /* 闭环时间线节点(依状态机与时间列派生) */
      buildTimeline(row) {
        const st = Number(row.orderStatus);
        const ps = Number(row.pharmAuditStatus);
        return [
          { label: '开立', time: row.startTime, done: true },
          { label: '药师审核', time: row.pharmAuditTime, done: ps >= 2, note: ps === 3 ? ('驳回: ' + (row.pharmRejectReason || '')) : (ps === 1 ? '待审' : '') },
          { label: '护士审核', time: row.auditTime, done: st >= 2 },
          { label: '执行', time: '', done: st >= 3 && st <= 5 },
          { label: '停嘱', time: row.stopTime, done: st === 4 || st === 5 },
          { label: st === 6 ? '作废' : '完成', time: '', done: st === 4 || st === 6 }
        ];
      },
      validateForm() {
        if (this.isDrug && !this.form.drugId) {
          ElementPlus.ElMessage.warning('药品类医嘱请先检索并选中药品');
          return false;
        }
        if (this.isDrug && this.checkResult && !this.checkResult.passed && !this.overrideAccepted) {
          ElementPlus.ElMessage.warning('该药品合理用药审查未通过, 请先确认「知情开具」');
          this.checkVisible = true;
          return false;
        }
        if (!this.isDrug && this.form.drugId) { this.form.drugId = null; }
        const content = String(this.form.orderContent || '').trim();
        if (!content && this.isDrug) { this.form.orderContent = this.composeDrugContent(); }
        if (!String(this.form.orderContent || '').trim()) {
          ElementPlus.ElMessage.warning('请填写医嘱内容(或检索选中项目后自动生成)');
          return false;
        }
        if (!this.form.quantity || Number(this.form.quantity) <= 0) {
          ElementPlus.ElMessage.warning('数量必须大于 0');
          return false;
        }
        if (this.isDrug && this.pickedDrug && this.pickedDrug.maxQtyOnce != null && this.pickedDrug.maxQtyOnce !== '') {
          const cap = Number(this.pickedDrug.maxQtyOnce);
          if (cap > 0 && Number(this.form.quantity) > cap) {
            ElementPlus.ElMessage.warning('已达该药品单次最大量 ' + cap + (this.pickedDrug.minUnit || '') + ', 请调整数量');
            return false;
          }
        }
        return true;
      },
      /* 重复开嘱检测: 同分类+同药品/项目+同用法频次, 命中在执行医嘱或本批清单 */
      findDuplicate(item) {
        if (item._skin) { return null; }
        const same = (a, b) => (a == null && b == null) || HIS.sameId(a, b);
        const eqOpt = (a, b) => String(a || '') === String(b || '');
        for (let i = 0; i < this.orders.length; i++) {
          const o = this.orders[i];
          if (!(o.orderStatus >= 1 && o.orderStatus <= 3)) { continue; }
          if (Number(o.orderCategory) === Number(item.orderCategory)
            && (same(o.drugId, item.drugId) || same(o.chargeItemId, item.chargeItemId))
            && eqOpt(o.usageCode, item.usageCode) && eqOpt(o.freqCode, item.freqCode)) { return o; }
        }
        for (let j = 0; j < this.pendingItems.length; j++) {
          const p = this.pendingItems[j];
          if (Number(p.orderCategory) === Number(item.orderCategory)
            && (same(p.drugId, item.drugId) || same(p.chargeItemId, item.chargeItemId))
            && eqOpt(p.usageCode, item.usageCode) && eqOpt(p.freqCode, item.freqCode)) { return p; }
        }
        return null;
      },
      addToPending() {
        if (!this.validateForm()) { return; }
        if (this.isDrug && this.pickedDrug) {
          const vm = this;
          this.checkPrescribeAuth(this.pickedDrug).then(function (ok) { if (ok) { vm.doAddToPending(); } });
          return;
        }
        this.doAddToPending();
      },
      /* ===== B3 键盘流: Enter 逐框跳转, 数量 Enter 即加入清单并回检索连续录入(对齐门诊处方键盘闭环) ===== */
      kbdFocus(refName) {
        const c = this.$refs[refName];
        if (c && c.focus) { c.focus(); }
      },
      kbdAddPending() {
        const vm = this;
        const before = this.pendingItems.length;
        this.addToPending();
        /* 仅当清单确实新增(未撞校验/重复确认)才回焦检索框; el-select 选后回焦需延时抢回焦点 */
        setTimeout(function () {
          if (!vm.dialogVisible || vm.pendingItems.length <= before) { return; }
          const target = vm.isDrug ? vm.$refs.selDrugSearch : vm.$refs.selChargeSearch;
          if (target && target.focus) { target.focus(); }
        }, 260);
      },
      /* 批次D: 处方权限只读校验(镜像门诊 prescription-panel.checkPrescribeAuth); staffId 省略→后端取登录医生 */
      checkPrescribeAuth(drug) {
        const type = prescribeTypeOf(drug);
        if (!type) { return Promise.resolve(true); }
        return HIS.get('/api/his/staff/prescribe-auth?type=' + encodeURIComponent(type))
          .then(function (data) {
            if (data && data.found && data.typeAllowed === false) {
              ElementPlus.ElMessage.warning('您无' + (PRESCRIBE_LABELS[type] || type) + '处方权限' + (data.expired ? '(处方权已过期)' : '') + ', 不能开立「' + (drug.itemName || drug.genericName || '') + '」');
              return false;
            }
            return true;
          }).catch(function () { return true; });
      },
      doAddToPending() {
        const f = this.form;
        const item = {
          orderType: Number(f.orderType),
          orderCategory: Number(f.orderCategory),
          orderContent: this.assembleContent(f.orderContent),
          drugId: this.isDrug ? f.drugId : null,
          chargeItemId: this.isDrug ? null : f.chargeItemId,
          spec: f.spec || null,
          dosage: this.isDrug ? (f.dosage || null) : null,
          dosageUnit: this.isDrug ? (f.dosageUnit || null) : null,
          usageCode: this.isDrug ? (f.usageCode || null) : null,
          freqCode: this.isDrug ? (f.freqCode || null) : null,
          quantity: Number(f.quantity) || 1,
          _typeText: ORDER_TYPE[f.orderType] || '-',
          _categoryText: ORDER_CATEGORY[f.orderCategory] || '-',
          _price: this.isDrug
            ? Number(this.pickedDrug && this.pickedDrug.retailPrice) || 0
            : Number(this.pickedCharge && (this.pickedCharge.execPrice != null ? this.pickedCharge.execPrice : this.pickedCharge.price)) || 0,
          _doseText: this.isDrug
            ? [f.dosage ? f.dosage + (f.dosageUnit || '') : '', this.usageMap[f.usageCode] || '', this.freqMap[f.freqCode] || ''].filter(Boolean).join(' ')
            : ''
        };
        if (Number(f.orderCategory) === 2) {
          item.examPart = (this.examSel.sites || []).join(',') || null;
          item.siteCount = this.examSiteCount || null;
          item.contrastMode = this.examSel.contrast || null;
        }
        const dup = this.findDuplicate(item);
        if (dup) {
          ElementPlus.ElMessageBox.confirm('已存在相同药品/项目与用法频次的医嘱「' + (dup.orderContent || '') + '」, 确认重复开立?', '重复开嘱提醒', {
            type: 'warning', confirmButtonText: '仍要开立', cancelButtonText: '取消'
          }).then(() => this.commitPending(item)).catch(function () { /* 取消 */ });
          return;
        }
        this.commitPending(item);
      },
      /* 确认后的入清单与连续录入复位(保留医疗单形态与类型) */
      commitPending(item) {
        this.pendingItems.push(item);
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.form.drugId = null;
        this.form.chargeItemId = null;
        this.form.orderContent = '';
        this.form.spec = '';
        this.form.dosage = '';
        this.form.usageCode = null;
        this.form.freqCode = null;
        this.form.quantity = 1;
        this.contentTouched = false;
        this.resetTransient();
      },
      removePending(index) { this.pendingItems.splice(index, 1); },
      buildDto(item) {
        const dto = {
          inpVisitId: HIS.id(this.visitId),
          orderType: item.orderType,
          orderCategory: item.orderCategory,
          orderContent: item.orderContent,
          drugId: HIS.id(item.drugId) || null,
          chargeItemId: HIS.id(item.chargeItemId) || null,
          spec: item.spec || null,
          dosage: item.dosage || null,
          dosageUnit: item.dosageUnit || null,
          usageCode: item.usageCode || null,
          freqCode: item.freqCode || null,
          quantity: item.quantity
        };
        if (Number(item.orderCategory) === 2) {
          dto.examPart = item.examPart || null;
          dto.siteCount = item.siteCount || null;
          dto.contrastMode = item.contrastMode || null;
        }
        return dto;
      },
      submitOrders() {
        const vm = this;
        if (vm.submitting) { return; }
        /* 批次D: 无清单的单条直提路径同样过处方权限门控 */
        if (!vm.pendingItems.length && vm.isDrug && vm.pickedDrug) {
          vm.checkPrescribeAuth(vm.pickedDrug).then(function (ok) { if (ok) { vm._submitOrders(); } });
          return;
        }
        vm._submitOrders();
      },
      _submitOrders() {
        const vm = this;
        if (vm.submitting) { return; }
        if (!vm.pendingItems.length) {
          if (!vm.validateForm()) { return; }
          const f = vm.form;
          const single = {
            orderType: Number(f.orderType),
            orderCategory: Number(f.orderCategory),
            orderContent: vm.assembleContent(f.orderContent),
            drugId: vm.isDrug ? f.drugId : null,
            chargeItemId: vm.isDrug ? null : f.chargeItemId,
            spec: f.spec || null,
            dosage: vm.isDrug ? (f.dosage || null) : null,
            dosageUnit: vm.isDrug ? (f.dosageUnit || null) : null,
            usageCode: vm.isDrug ? (f.usageCode || null) : null,
            freqCode: vm.isDrug ? (f.freqCode || null) : null,
            quantity: Number(f.quantity) || 1
          };
          vm.submitting = true;
          if (Number(f.orderCategory) === 2) {
            single.examPart = (vm.examSel.sites || []).join(',') || null;
            single.siteCount = vm.examSiteCount || null;
            single.contrastMode = vm.examSel.contrast || null;
          }
          HIS.post('/api/his/inp/order', vm.buildDto(single)).then(function (order) {
            HIS.notifySuccess('医嘱已开立: ' + ((order && order.orderContent) || '成功')
              + (order && order.insuranceCategory ? '(' + order.insuranceCategory + ')' : ''));
            /* 医保预审提示(T42, 非阻断): 无医保编码项目将全额自费 */
            if (order && order.insuranceWarning) { vm.showInsuranceTip(order.insuranceWarning); }
            vm.dialogVisible = false;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
          return;
        }
        const items = vm.pendingItems.map(it => vm.buildDto(it));
        vm.submitting = true;
        HIS.post('/api/his/inp/order/batch', items).then(function (list) {
          HIS.notifySuccess('成组医嘱已开立 ' + ((list && list.length) || items.length) + ' 条');
          vm.showBatchInsuranceTip(list);
          vm.dialogVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      /* ===== 停止 / 作废 ===== */
      canStop(row) { return Number(row.orderStatus) === 2 || Number(row.orderStatus) === 3; },
      canCancel(row) { return Number(row.orderStatus) === 1; },
      /* ===== 续开(T42) / 医保预审提示 ===== */
      canRenew(row) { return Number(row.orderStatus) === 5 && Number(row.orderType) === 1; },
      doRenew(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认续开医嘱「' + row.orderContent + '」？将按原医嘱要素(药品/剂量/用法/频次)重新开立长期医嘱, 新医嘱自原停嘱次日开始。',
          '续开医嘱确认', { type: 'info', confirmButtonText: '续开', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.post('/api/his/inp/order/renew/' + HIS.idParam(row.id));
        }).then(function (order) {
          HIS.notifySuccess('续开成功');
          if (order && order.insuranceWarning) { vm.showInsuranceTip(order.insuranceWarning); }
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* 医保预审提示条: 列表上方橙色告警, 3 秒自动消失或手动关闭 */
      showInsuranceTip(msg) {
        const vm = this;
        vm.insTip = msg;
        if (vm._insTimer) { clearTimeout(vm._insTimer); }
        vm._insTimer = setTimeout(function () { vm.insTip = null; vm._insTimer = null; }, 3000);
      },
      clearInsTip() {
        this.insTip = null;
        if (this._insTimer) { clearTimeout(this._insTimer); this._insTimer = null; }
      },
      /* 批量回执中的医保预警汇总(单条展示原文, 多条按计数) */
      showBatchInsuranceTip(list) {
        const warns = (list || []).filter(function (o) { return o && o.insuranceWarning; });
        if (!warns.length) { return; }
        this.showInsuranceTip(warns.length === 1
          ? warns[0].insuranceWarning
          : '其中 ' + warns.length + ' 条医嘱项目无医保编码, 将全额自费');
      },
      doStop(row) {
        const vm = this;
        const groupTip = row.groupNo ? '该医嘱属于成组医嘱(' + row._groupLabel + '), 同组在执行/已审核成员将一并停止。' : '';
        ElementPlus.ElMessageBox.confirm('确认停止医嘱「' + row.orderContent + '」？' + groupTip, '停止医嘱确认', {
          type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/order/' + HIS.idParam(row.id) + '/stop');
        }).then(function () {
          HIS.notifySuccess('医嘱已停止');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      doCancel(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废医嘱「' + row.orderContent + '」？作废后已记账的临时医嘱费用将同步冲销。', '作废医嘱确认', {
          type: 'warning', confirmButtonText: '作废', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/order/' + HIS.idParam(row.id) + '/cancel');
        }).then(function () {
          HIS.notifySuccess('医嘱已作废');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 模板医嘱 / 医嘱套餐 ===== */
      openTemplates(mode) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.tplMode = mode;
        this.tplKeyword = '';
        this.tplTypeFilter = null;
        this.tplPage = 1;
        this.tplVisible = true;
        this.loadTemplates();
      },
      loadTemplates(page) {
        const vm = this;
        if (page) { vm.tplPage = page; }
        vm.tplLoading = true;
        let url = '/api/his/inp/order-template/list?page=' + vm.tplPage + '&size=' + vm.tplSize
          + '&scopeType=' + (vm.tplMode === 2 ? 2 : 1)
          + '&status=1'; // 仅启用模板可被引用(后端开嘱仍会兜底拦截停用模板)
        if (vm.tplKeyword) { url += '&keyword=' + encodeURIComponent(vm.tplKeyword); }
        if (vm.tplTypeFilter) { url += '&templateType=' + vm.tplTypeFilter; }
        HIS.get(url).then(function (d) {
          vm.tplRows = (d && d.records) || [];
          vm.tplTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.tplLoading = false; });
      },
      tplTypeText(t) { return t === 1 ? '个人' : (t === 2 ? '科室' : (t === 3 ? '全院' : '-')); },
      tplItemCount(items) {
        if (!items) { return 0; }
        try {
          const arr = typeof items === 'string' ? JSON.parse(items) : items;
          return Array.isArray(arr) ? arr.length : 0;
        } catch (e) { return 0; }
      },
      applyTemplate(row) {
        const vm = this;
        if (vm.tplApplying) { return; }
        ElementPlus.ElMessageBox.confirm('确认按模板「' + row.templateName + '」为当前患者开立医嘱? 开立后可在列表中调整/停止。', vm.tplMode === 2 ? '套餐开嘱确认' : '模板开嘱确认', {
          type: 'info', confirmButtonText: '开立', cancelButtonText: '取消'
        }).then(function () {
          vm.tplApplying = true;
          return HIS.post('/api/his/inp/order/from-template', { visitId: HIS.id(vm.visitId), templateId: HIS.id(row.id) });
        }).then(function (list) {
          HIS.notifySuccess('已开立 ' + ((list && list.length) || 0) + ' 条医嘱');
          vm.showBatchInsuranceTip(list);
          vm.tplVisible = false;
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.tplApplying = false; });
      },
      /* ===== 合理用药审查 ===== */
      rationalCheck() {
        const vm = this;
        if (!vm.visitId || !vm.form.drugId) { return; }
        vm.checkLoading = true;
        HIS.post('/api/his/inp/order/rational-check', {
          visitId: HIS.id(vm.visitId), drugId: HIS.id(vm.form.drugId), dosage: vm.form.dosage || null
        }).then(function (d) {
          vm.checkResult = d || null;
          const warn = d && d.warnings && d.warnings.length;
          if (d && (!d.passed || warn || d.highAlert || d.doubleCheck)) { vm.checkVisible = true; }
        }).catch(function (e) {
          /* 审查服务异常不阻断开嘱(本地留痕即可) */
          console.warn('[合理用药审查]', (e && e.message) || e);
          vm.checkResult = null;
        }).finally(function () { vm.checkLoading = false; });
      },
      /* 知情开具: 审查未通过时人工覆盖(责任自负) */
      overrideOk() {
        this.overrideAccepted = true;
        this.checkVisible = false;
        ElementPlus.ElMessage.warning('已标记「知情开具」, 请在提交前再次核对');
      },
      /* 取消选药(从审查对话框退出) */
      cancelPick() {
        this.pickedDrug = null;
        this.form.drugId = null;
        this.checkResult = null;
        this.overrideAccepted = false;
        this.checkVisible = false;
      },
      /* ===== 复制开立: 预填开立对话框(反解析行标记回填工作台, 内容视为已编辑) ===== */
      copyOrder(row) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        const m = decodeMarks(row.orderContent);
        this.form = {
          orderType: Number(row.orderType) || 1,
          orderCategory: Number(row.orderCategory) || 1,
          orderContent: m.base,
          drugId: row.drugId || null,
          chargeItemId: row.chargeItemId || null,
          spec: row.spec || '',
          dosage: row.dosage || '',
          dosageUnit: row.dosageUnit || '',
          usageCode: row.usageCode || null,
          freqCode: row.freqCode || null,
          quantity: row.quantity != null ? Number(row.quantity) : 1
        };
        this.mfKind = this.inferKind(Number(row.orderCategory), m);
        this.mark = { emergency: m.emergency, dischargeDays: m.dischargeDays, standby: m.standby };
        this.dripRate = m.dripRate;
        this.skinLinked = m.linkedSkin;
        this.examSel = { sites: m.sites ? m.sites.split(',') : [], film: m.film, report: m.report, contrast: m.contrastMode || '平扫' };
        this.labSel = { specimen: m.specimen, metrics: m.metrics };
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.pendingItems = [];
        this.checkResult = null;
        this.overrideAccepted = false;
        this.contentTouched = true;
        this.dialogVisible = true;
        this.loadMedDict();
        /* 复制药品医嘱: 重新走合理用药审查 */
        if (this.form.orderCategory === 1 && this.form.drugId) { this.rationalCheck(); }
      },
      /* 由行分类与标记推断医疗单形态 */
      inferKind(cat, m) {
        if (m.sites || m.film || m.report) { return 'exam'; }
        if (m.specimen || m.metrics) { return 'lab'; }
        const map = { 1: 'drug', 2: 'exam', 3: 'lab', 4: 'treat', 5: 'nursing', 6: 'diet' };
        return map[cat] || 'text';
      },
      /* 执行详情 + 闭环时间线(执行明细由护士站执行环节产生) */
      showExec(row) {
        this.execRow = row;
        this.execTimeline = this.buildTimeline(row);
        this.execVisible = true;
      },
      onMoreCmd(row, cmd) {
        if (cmd === 'copy') { this.copyOrder(row); }
        else if (cmd === 'renew') { if (this.canRenew(row)) { this.doRenew(row); } }
        else if (cmd === 'exec') { this.showExec(row); }
        else if (cmd === 'leaflet') { this.openLeaflet(row); }
      },
      /* ---- 行右键上下文菜单 ---- */
      onRowCtx(row, column, event) {
        if (event) {
          event.preventDefault();
          event.stopPropagation();
        }
        this.ctx = { visible: true, x: event ? event.clientX : 0, y: event ? event.clientY : 0, row: row };
      },
      closeCtx() {
        if (this.ctx.visible) { this.ctx.visible = false; }
      },
      ctxCmd(cmd) {
        const row = this.ctx.row;
        this.closeCtx();
        if (!row) { return; }
        if (cmd === 'copy') { this.copyOrder(row); }
        else if (cmd === 'renew') { if (this.canRenew(row)) { this.doRenew(row); } }
        else if (cmd === 'exec') { this.showExec(row); }
        else if (cmd === 'leaflet') { this.openLeaflet(row); }
        else if (cmd === 'stop') { if (this.canStop(row)) { this.doStop(row); } }
        else if (cmd === 'cancel') { if (this.canCancel(row)) { this.doCancel(row); } }
      }
    },
    mounted() {
      this.loadDepts();
      /* 拉取住院检查控费加收双开关(默认停用), 驱动加收预估显隐; 拉取失败保持默认停用不阻断面板 */
      const vmSw = this;
      if (window.HIS && HIS.get) {
        HIS.get('/api/sys/param/resolve/inpatient.exam_part_surcharge_enabled').then(function (v) {
          vmSw.examPartEnabled = String(v).toLowerCase() === 'true';
        }).catch(function () { /* 保持默认停用 */ });
        HIS.get('/api/sys/param/resolve/inpatient.exam_contrast_surcharge_enabled').then(function (v) {
          vmSw.examContrastEnabled = String(v).toLowerCase() === 'true';
        }).catch(function () { /* 保持默认停用 */ });
      }
      /* 点击任意处 / 在别处右键 自动关闭行右键菜单 */
      this._onDocClick = this.closeCtx.bind(this);
      this._onDocCtxMenu = this.closeCtx.bind(this);
      document.addEventListener('click', this._onDocClick);
      document.addEventListener('contextmenu', this._onDocCtxMenu);
    },
    beforeUnmount() {
      if (this._onDocClick) { document.removeEventListener('click', this._onDocClick); this._onDocClick = null; }
      if (this._onDocCtxMenu) { document.removeEventListener('contextmenu', this._onDocCtxMenu); this._onDocCtxMenu = null; }
      if (this._insTimer) { clearTimeout(this._insTimer); this._insTimer = null; }
    },
    template: `
      <div class="iw-panel-body iw-panel-body--flush">
        <div class="iw-toolbar">
          <el-radio-group v-model="filterType" size="small" @change="onFilterChange">
            <el-radio-button :label="0">全部</el-radio-button>
            <el-radio-button :label="1">长期</el-radio-button>
            <el-radio-button :label="2">临时</el-radio-button>
          </el-radio-group>
          <el-select v-model="filterStatus" placeholder="全部状态" size="small" clearable style="width:130px" @change="onFilterChange">
            <el-option v-for="(label, key) in statuses" :key="key" :label="label" :value="Number(key)"></el-option>
          </el-select>
          <el-select v-model="filterCategory" placeholder="全部分类" size="small" clearable style="width:120px">
            <el-option v-for="(label, key) in categories" :key="key" :label="label" :value="Number(key)"></el-option>
          </el-select>
          <el-select v-model="filterDeptId" placeholder="全部开单科室" size="small" clearable style="width:150px" @change="onFilterChange" v-if="deptFilterIds.length">
            <el-option v-for="d in deptFilterIds" :key="d" :label="deptNameOf(d)" :value="d"></el-option>
          </el-select>
          <span class="iw-toolbar-info" v-if="filterCategory">当前页按分类过滤 <b>{{ shownOrders.length }}</b> / 本页 {{ orders.length }} 条</span>
          <span class="iw-toolbar-info" v-else>共 <b>{{ total }}</b> 条</span>
          <span class="iw-toolbar-right">
            <el-button size="small" @click="openTemplates(1)">模板医嘱</el-button>
            <el-button size="small" @click="openTemplates(2)">医嘱套餐</el-button>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button size="small" type="warning" plain @click="openSurgApply">发起手术申请</el-button>
            <el-button size="small" type="primary" @click="openDialog">开立医嘱</el-button>
          </span>
        </div>

        <!-- 医保预审提示(T42, 非阻断): 开立/续开回执的无医保编码预警, 3秒自动消失 -->
        <el-alert v-if="insTip" type="warning" :title="insTip" show-icon closable style="margin:0 0 6px" @close="clearInsTip"></el-alert>

        <div class="iw-table-scroll" v-loading="loading">
          <el-table :data="shownOrders" height="100%" size="small" border :row-class-name="rowClass" :row-key="row => row.id"
                    @row-contextmenu="onRowCtx">
            <el-table-column label="组" width="50" align="center">
              <template #default="s">
                <span v-if="s.row._groupFirst" class="iw-grp">{{ s.row._groupLabel }}</span>
                <span v-else-if="s.row.groupNo" class="iw-grp-child">↳</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="62" align="center">
              <template #default="s">
                <span class="iw-tag" :class="Number(s.row.orderType) === 1 ? 'iw-tag--long' : 'iw-tag--temp'">{{ orderTypeText(s.row.orderType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="62" align="center">
              <template #default="s">{{ categoryText(s.row.orderCategory) }}</template>
            </el-table-column>
            <el-table-column label="医嘱内容" min-width="230" show-overflow-tooltip>
              <template #default="s">
                <span class="iw-badge iw-badge--emerg" v-if="s.row._marks && s.row._marks.emergency">急诊</span>
                <span class="iw-badge iw-badge--standby" v-if="s.row._marks && s.row._marks.standby">{{ s.row._marks.standby === 'PRN' ? '长期备用' : '临时备用' }}</span>
                <span class="iw-badge iw-badge--linked" v-if="s.row._marks && s.row._marks.dischargeDays">出院带药×{{ s.row._marks.dischargeDays }}天</span>
                <span class="iw-badge iw-badge--skin" v-if="s.row._marks && s.row._marks.linkedSkin">已开皮试</span>
                <span>{{ (s.row._marks && s.row._marks.base) || s.row.orderContent }}</span>
                <span class="iw-badge iw-badge--skin" v-if="s.row._marks && s.row._marks.dripRate" :title="'滴速 ' + s.row._marks.dripRate + ' 滴/分'">滴速{{ s.row._marks.dripRate }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip>
              <template #default="s">{{ s.row.spec || '-' }}</template>
            </el-table-column>
            <el-table-column label="剂量" width="86">
              <template #default="s">{{ s.row.dosage ? (s.row.dosage + (s.row.dosageUnit || '')) : '-' }}</template>
            </el-table-column>
            <el-table-column label="用法" width="80" show-overflow-tooltip>
              <template #default="s">{{ s.row.usageCode ? usageText(s.row.usageCode) : '-' }}</template>
            </el-table-column>
            <el-table-column label="频次" width="86" show-overflow-tooltip>
              <template #default="s">{{ s.row.freqCode ? freqText(s.row.freqCode) : '-' }}</template>
            </el-table-column>
            <el-table-column label="数量" width="62" align="right">
              <template #default="s">{{ s.row.quantity != null ? s.row.quantity : '-' }}</template>
            </el-table-column>
            <el-table-column label="单价" width="82" align="right">
              <template #default="s"><span class="iw-money">{{ s.row.unitPrice != null ? money(s.row.unitPrice) : '-' }}</span></template>
            </el-table-column>
            <el-table-column label="开始时间" width="118">
              <template #default="s"><span :title="s.row.startTime">{{ timeText(s.row.startTime) }}</span></template>
            </el-table-column>
            <el-table-column label="停止时间" width="118">
              <template #default="s">{{ s.row.stopTime ? timeText(s.row.stopTime) : '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="76" align="center">
              <template #default="s">
                <el-tag size="small" :type="statusTag(s.row.orderStatus)" disable-transitions>{{ statusText(s.row.orderStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="药审" width="64" align="center">
              <template #default="s">
                <el-tooltip v-if="Number(s.row.pharmAuditStatus) === 3"
                            :content="'药审驳回: ' + (s.row.pharmRejectReason || '未填写原因') + ' — 可右键复制开立修改后重新提交'"
                            placement="top" :show-after="200">
                  <el-tag size="small" type="danger" disable-transitions>驳回</el-tag>
                </el-tooltip>
                <el-tag v-else-if="Number(s.row.pharmAuditStatus) === 1" size="small" type="warning" disable-transitions>待审</el-tag>
                <el-tag v-else-if="Number(s.row.pharmAuditStatus) === 2" size="small" type="success" disable-transitions>通过</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="134" fixed="right" align="center">
              <template #default="s">
                <span class="iw-ops">
                  <el-tooltip v-if="canRenew(s.row)" content="续开医嘱(按原医嘱要素重新开立长期医嘱)" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-success" @click.stop="doRenew(s.row)" v-html="icons.renew"></button>
                  </el-tooltip>
                  <el-tooltip content="停止" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-danger" :disabled="!canStop(s.row)" @click.stop="doStop(s.row)" v-html="icons.pause"></button>
                  </el-tooltip>
                  <el-tooltip content="作废" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-danger" :disabled="!canCancel(s.row)" @click.stop="doCancel(s.row)" v-html="icons.ban"></button>
                  </el-tooltip>
                  <el-tooltip content="更多操作" placement="top" :show-after="200">
                    <el-dropdown trigger="click" @command="cmd => onMoreCmd(s.row, cmd)">
                      <button type="button" class="iw-icobtn" v-html="icons.more"></button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="copy">复制开立</el-dropdown-item>
                          <el-dropdown-item v-if="s.row.drugId" command="leaflet">查看药品说明书</el-dropdown-item>
                          <el-dropdown-item v-if="canRenew(s.row)" command="renew" divided>续开医嘱</el-dropdown-item>
                          <el-dropdown-item command="exec">查看执行详情</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </el-tooltip>
                </span>
              </template>
            </el-table-column>
            <template #empty><div class="iw-empty-line">暂无医嘱, 点击右上「开立医嘱」开始录入</div></template>
          </el-table>
        </div>

        <div class="iw-pager" v-if="total > size">
          <el-pagination small background layout="prev, pager, next" :total="total" :page-size="size"
                         :current-page="page" @current-change="onPageChange"></el-pagination>
        </div>

        <!-- 开立医嘱对话框 -->
        <el-dialog v-model="dialogVisible" title="开立医嘱" width="840px" top="5vh" :close-on-click-modal="false">
          <!-- 模式标记行: 长期/临时 + 急诊/出院带药/备用 -->
          <div class="iw-mk">
            <span class="lbl">类型</span>
            <el-radio-group v-model="form.orderType" size="small">
              <el-radio-button :label="1">长期</el-radio-button>
              <el-radio-button :label="2">临时</el-radio-button>
            </el-radio-group>
            <span class="lbl">标记</span>
            <el-check-tag :checked="mark.emergency" @change="v => mark.emergency = v">急诊</el-check-tag>
            <el-check-tag v-if="isDrug && Number(form.orderType) === 1" :checked="mark.dischargeDays > 0" @change="v => mark.dischargeDays = v ? 7 : 0">出院带药</el-check-tag>
            <el-input-number v-if="mark.dischargeDays > 0" v-model="mark.dischargeDays" size="small" :min="1" :max="90" style="width:104px"></el-input-number>
            <span v-if="mark.standby" class="iw-badge iw-badge--standby" title="由频次自动识别的备用医嘱">{{ mark.standby === 'PRN' ? '长期备用' : '临时备用' }}</span>
            <span class="iw-dim" style="margin-left:auto">{{ Number(form.orderType) === 2 ? '临时医嘱开立即记账计入住院费用' : '长期医嘱由执行环节逐日生成费用' }}</span>
          </div>

          <div class="iw-mf">
            <!-- 左: 医疗单类型导航轨 -->
            <div class="iw-mf-rail">
              <div v-for="f in medForms" :key="f.kind" class="iw-mf-item" :class="{ 'is-active': mfKind === f.kind }" @click="selectForm(f.kind)">{{ f.name }}<span class="k">{{ f.kw }}</span></div>
            </div>

            <!-- 右: 对应医疗单子表单 -->
            <div class="iw-mf-body">
              <!-- 药品 / 草药 -->
              <template v-if="isDrug">
                <div class="iw-form-row">
                  <span class="lb">药品检索</span>
                  <el-select class="iw-grow" ref="selDrugSearch" v-model="pickDrugId" size="small" filterable remote reserve-keyword clearable
                             :remote-method="remoteDrugSearch" :loading="drugSearching" placeholder="通用名 / 编码 / 拼音简码; 首字 y药 c检 l验 z治 h护 s术 w字 可快速切类; 选药后自动跳剂量" @change="onPickDrug">
                    <el-option v-for="d in drugOptions" :key="d.id" :label="(d.genericName || '') + ' ' + (d.spec || '')" :value="d.id">
                      <div class="iw-opt">
                        <span class="nm">{{ d.genericName }}</span>
                        <span class="sub">{{ d.spec }}</span>
                        <span class="price">¥{{ money(d.retailPrice) }}</span>
                        <span class="iw-tag iw-tag--plain" v-if="!d.ybDrugCode">自费</span>
                      </div>
                    </el-option>
                  </el-select>
                </div>
                <div class="iw-picked" v-if="pickedDrug">
                  <span>规格 <b>{{ pickedDrug.spec || '-' }}</b></span>
                  <span>零售价 <b class="iw-money">{{ money(pickedDrug.retailPrice) }}</b> 元/{{ pickedDrug.minUnit || '单位' }}</span>
                  <span class="iw-tag iw-tag--plain" v-if="pickedDrug.drugClassName">{{ pickedDrug.drugClassName }}</span>
                  <span class="iw-badge iw-badge--abx" v-if="pickedDrug.abxGradeName">{{ pickedDrug.abxGradeName }}</span>
                  <span class="iw-badge iw-badge--skin" v-if="Number(pickedDrug.skinTestFlag) === 1">需皮试</span>
                  <span class="iw-stock" :class="stockText.cls" v-if="stockText">库存{{ stockText.t }}</span>
                  <span v-if="checkLoading" class="iw-dim">合理用药审查中...</span>
                  <span v-if="checkResult && checkResult.highAlert" class="iw-high-alert" title="高警示药品: 开立/调配/执行需严格核对">⚠</span>
                  <span v-if="checkResult && checkResult.doubleCheck" class="iw-tag iw-tag--plain" style="color:var(--yb-warning-strong);border-color:var(--yb-warning-border)" title="需双人核对">双人核对</span>
                </div>
                <div class="iw-form-row" v-if="pickedDrug && Number(pickedDrug.skinTestFlag) === 1 && !skinLinked">
                  <span class="lb"></span>
                  <el-button size="small" type="warning" plain @click="addSkinTest">自动添加皮试医嘱(与主医嘱同组提交)</el-button>
                </div>
                <div class="iw-conv" v-if="dispensePreview">
                  <span>每次剂量 <b>{{ form.dosage }}{{ form.dosageUnit }}</b></span>
                  <span v-if="dispensePreview.min != null">≈ 发药 <b>{{ dispensePreview.min }}</b> {{ dispensePreview.minUnit }}</span>
                  <span v-if="dispensePreview.packs != null">≈ <b>{{ dispensePreview.packs }}</b> {{ dispensePreview.packUnit }}</span>
                  <span class="iw-dim" v-if="isInfusionNow">输液类 · 计入当日液体总量</span>
                </div>
                <div class="iw-form-row">
                  <span class="lb">剂量</span>
                  <el-input ref="inpDosage" v-model="form.dosage" size="small" style="width:96px" placeholder="如 0.5" @keyup.enter="form.dosageUnit ? kbdFocus('selUsage') : kbdFocus('inpDosageUnit')"></el-input>
                  <el-input ref="inpDosageUnit" v-model="form.dosageUnit" size="small" style="width:80px" placeholder="单位" @keyup.enter="kbdFocus('selUsage')"></el-input>
                  <span class="lb">用法</span>
                  <el-select ref="selUsage" v-model="form.usageCode" size="small" clearable filterable style="width:120px" placeholder="用法" @keyup.enter="kbdFocus('selFreq')">
                    <el-option v-for="u in usageOptions" :key="u.code" :label="u.name" :value="u.code"></el-option>
                  </el-select>
                  <span class="lb">频次</span>
                  <el-select ref="selFreq" v-model="form.freqCode" size="small" clearable filterable style="width:120px" placeholder="频次" @change="onFreqChange" @keyup.enter="kbdFocus('inpQty')">
                    <el-option v-for="f in freqOptions" :key="f.code" :label="f.name" :value="f.code"></el-option>
                  </el-select>
                </div>
                <div class="iw-form-row" v-if="isInfusionNow">
                  <span class="lb">滴速</span>
                  <el-input v-model="dripRate" size="small" style="width:130px" placeholder="如 40滴/分"></el-input>
                  <span class="iw-dim">当前在用输液医嘱 {{ ivActiveOrders.length }} 条</span>
                </div>
              </template>

              <!-- 检查 -->
              <template v-else-if="mfKind === 'exam'">
                <div class="iw-form-row">
                  <span class="lb">检查项目</span>
                  <el-select class="iw-grow" ref="selChargeSearch" v-model="pickChargeId" size="small" filterable remote reserve-keyword clearable
                             :remote-method="remoteChargeSearch" :loading="chargeSearching" placeholder="检查项目名称 / 编码, 选中带出执行价" @change="onPickCharge">
                    <el-option v-for="c in chargeOptions" :key="c.id" :label="(c.itemName || '') + ' ' + (c.spec || '')" :value="c.id">
                      <div class="iw-opt"><span class="nm">{{ c.itemName }}</span><span class="sub">{{ c.itemCode }}</span><span class="price">¥{{ money(c.execPrice != null ? c.execPrice : c.price) }}</span></div>
                    </el-option>
                  </el-select>
                </div>
                <div class="iw-form-row">
                  <span class="lb">检查部位</span>
                  <el-select class="iw-grow" v-model="examSel.sites" size="small" multiple collapse-tags collapse-tags-tooltip clearable placeholder="可多选(按部位加收预估)">
                    <el-option v-for="s in siteOptions" :key="s" :label="s" :value="s"></el-option>
                  </el-select>
                </div>
                <div class="iw-form-row">
                  <span class="lb">造影方式</span>
                  <el-radio-group v-model="examSel.contrast" size="small">
                    <el-radio-button label="平扫">平扫</el-radio-button>
                    <el-radio-button label="增强">增强</el-radio-button>
                  </el-radio-group>
                </div>
                <div class="iw-form-row">
                  <span class="lb">胶片/报告</span>
                  <el-select v-model="examSel.film" size="small" clearable placeholder="胶片类型" style="width:160px">
                    <el-option v-for="f in filmOptions" :key="f" :label="f" :value="f"></el-option>
                  </el-select>
                  <el-checkbox v-model="examSel.report">图文报告</el-checkbox>
                </div>
                <div class="iw-conv" v-if="pickedCharge">
                  <span>项目基准价 <b class="iw-money">{{ money(pickedCharge.execPrice != null ? pickedCharge.execPrice : pickedCharge.price) }}</b> 元</span>
                  <span>计价部位 <b>{{ examSiteCount }}</b> 个{{ examSel.contrast==='增强' ? '（增强）' : '' }}</span>
                  <span class="iw-dim" v-if="examSurchargeEstimate>0">加收预估 ￥{{ money(examSurchargeEstimate) }}（多部位第2个起每个50%、增强另按主项目50%，实际以计费规则为准）</span>
                  <span class="iw-dim" v-else>按部位/增强加收由计费规则落库（当前机构住院控费开关停用则不自动加收）</span>
                </div>
              </template>

              <!-- 检验 -->
              <template v-else-if="mfKind === 'lab'">
                <div class="iw-form-row">
                  <span class="lb">检验项目</span>
                  <el-select class="iw-grow" ref="selChargeSearch" v-model="pickChargeId" size="small" filterable remote reserve-keyword clearable
                             :remote-method="remoteChargeSearch" :loading="chargeSearching" placeholder="检验项目名称 / 编码" @change="onPickCharge">
                    <el-option v-for="c in chargeOptions" :key="c.id" :label="(c.itemName || '') + ' ' + (c.spec || '')" :value="c.id">
                      <div class="iw-opt"><span class="nm">{{ c.itemName }}</span><span class="sub">{{ c.itemCode }}</span><span class="price">¥{{ money(c.execPrice != null ? c.execPrice : c.price) }}</span></div>
                    </el-option>
                  </el-select>
                </div>
                <div class="iw-form-row">
                  <span class="lb">标本类型</span>
                  <el-select v-model="labSel.specimen" size="small" clearable placeholder="标本" style="width:150px">
                    <el-option v-for="s in specimenOptions" :key="s" :label="s" :value="s"></el-option>
                  </el-select>
                  <span class="lb">指标组合</span>
                  <el-input v-model="labSel.metrics" size="small" style="width:160px" placeholder="如 血常规五项"></el-input>
                </div>
                <div class="iw-conv" v-if="pickedCharge">
                  <span>项目基准价 <b class="iw-money">{{ money(pickedCharge.execPrice != null ? pickedCharge.execPrice : pickedCharge.price) }}</b> 元</span>
                  <span class="iw-dim">指标个数记入内容后缀, 真实加收顺延计费规则</span>
                </div>
              </template>

              <!-- 文字医嘱 -->
              <template v-else-if="mfKind === 'text'">
                <div class="iw-form-row">
                  <span class="lb">医嘱内容</span>
                  <el-input class="iw-grow" v-model="form.orderContent" size="small" type="textarea" :rows="2" placeholder="直接填写文字医嘱内容(护理/膳食/健康指导等)" @input="onContentInput"></el-input>
                </div>
                <div class="iw-dim" style="margin-top:4px">文字医嘱用于无需计价的指令性内容, 提交后可在列表继续维护。</div>
              </template>

              <!-- 治疗 / 护理 / 饮食 / 手术 / 会诊 -->
              <template v-else>
                <div class="iw-form-row">
                  <span class="lb">{{ mfInfo.name }}项目</span>
                  <el-select class="iw-grow" ref="selChargeSearch" v-model="pickChargeId" size="small" filterable remote reserve-keyword clearable
                             :remote-method="remoteChargeSearch" :loading="chargeSearching" placeholder="项目名称 / 编码, 也可留空开纯文字医嘱" @change="onPickCharge">
                    <el-option v-for="c in chargeOptions" :key="c.id" :label="(c.itemName || '') + ' ' + (c.spec || '')" :value="c.id">
                      <div class="iw-opt"><span class="nm">{{ c.itemName }}</span><span class="sub">{{ c.itemCode }}</span><span class="sub">{{ c.spec }}</span><span class="price">¥{{ money(c.execPrice != null ? c.execPrice : c.price) }}</span></div>
                    </el-option>
                  </el-select>
                </div>
                <div class="iw-picked" v-if="pickedCharge">
                  <span>编码 <b>{{ pickedCharge.itemCode || '-' }}</b></span>
                  <span>执行价 <b class="iw-money">{{ money(pickedCharge.execPrice != null ? pickedCharge.execPrice : pickedCharge.price) }}</b> 元/{{ pickedCharge.unit || '次' }}</span>
                  <span v-if="pickedCharge.itemContent" class="clip">{{ pickedCharge.itemContent }}</span>
                </div>
              </template>

              <!-- 共用: 医嘱内容 + 数量 -->
              <div class="iw-form-row" v-if="mfKind !== 'text'">
                <span class="lb">医嘱内容</span>
                <el-input class="iw-grow" v-model="form.orderContent" size="small" placeholder="自动生成或手动输入" @input="onContentInput">
                  <template #append><el-button @click="autoCompose">自动生成</el-button></template>
                </el-input>
              </div>
              <div class="iw-form-row">
                <span class="lb">数量</span>
                <el-input-number ref="inpQty" v-model="form.quantity" size="small" :min="0.01" :step="1" :precision="4" style="width:130px" @keyup.enter="kbdAddPending"></el-input-number>
                <span class="iw-dim">Enter 即加入清单并回到检索继续录; 药品类数量按最小发药单位计</span>
              </div>
            </div>
          </div>

          <div class="iw-sect-title" style="margin-top:12px">
            医嘱清单
            <span class="iw-count" v-if="pendingItems.length">{{ pendingItems.length }}</span>
            <span class="iw-dim" style="font-weight:400;margin-left:8px">多条一并提交将自动成组(同组联动停止/作废)</span>
            <span class="iw-toolbar-right" v-if="pendingItems.length">预估金额 <b class="iw-money" style="margin-left:4px">{{ money(pendingTotal) }}</b> 元</span>
          </div>
          <el-table v-if="pendingItems.length" :data="pendingItems" size="small" border max-height="200">
            <el-table-column label="类型" width="58" align="center">
              <template #default="s">{{ s.row._typeText }}</template>
            </el-table-column>
            <el-table-column label="分类" width="58" align="center">
              <template #default="s">{{ s.row._categoryText }}</template>
            </el-table-column>
            <el-table-column prop="orderContent" label="内容" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="剂量用法" width="150" show-overflow-tooltip>
              <template #default="s">{{ s.row._doseText || '-' }}</template>
            </el-table-column>
            <el-table-column label="数量" width="60" align="right">
              <template #default="s">{{ s.row.quantity }}</template>
            </el-table-column>
            <el-table-column label="单价" width="76" align="right">
              <template #default="s">{{ s.row._price ? money(s.row._price) : '服务端定价' }}</template>
            </el-table-column>
            <el-table-column label="" width="52" align="center">
              <template #default="s"><el-button link type="danger" size="small" @click="removePending(s.$index)">删</el-button></template>
            </el-table-column>
          </el-table>
          <div v-else class="iw-empty-line" style="border:1px dashed var(--yb-border);border-radius:4px;padding:10px;text-align:center">
            暂未添加医嘱条目, 填写上方信息后点击「加入清单」; 或直接点击「提交医嘱」开立单条
          </div>

          <template #footer>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button @click="addToPending">加入清单</el-button>
            <el-button type="primary" :loading="submitting" @click="submitOrders">
              {{ pendingItems.length ? ('提交医嘱(' + pendingItems.length + '条)') : '提交医嘱' }}
            </el-button>
          </template>
        </el-dialog>

        <!-- 模板医嘱 / 医嘱套餐 -->
        <el-dialog v-model="tplVisible" :title="tplMode === 2 ? '医嘱套餐' : '模板医嘱'" width="720px" top="6vh">
          <div class="iw-toolbar" style="margin-bottom:8px">
            <el-input v-model="tplKeyword" size="small" placeholder="模板名称 / 病种编码" clearable style="width:230px"
                      @keyup.enter="loadTemplates(1)" @clear="loadTemplates(1)"></el-input>
            <el-select v-model="tplTypeFilter" size="small" placeholder="全部范围" clearable style="width:120px" @change="loadTemplates(1)">
              <el-option :value="1" label="个人"></el-option>
              <el-option :value="2" label="科室"></el-option>
              <el-option :value="3" label="全院"></el-option>
            </el-select>
            <el-button size="small" type="primary" @click="loadTemplates(1)">搜索</el-button>
            <span class="iw-toolbar-info">共 <b>{{ tplTotal }}</b> 个</span>
          </div>
          <el-table :data="tplRows" v-loading="tplLoading" size="small" border max-height="380">
            <el-table-column prop="templateName" label="模板名称" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="范围" width="76" align="center">
              <template #default="s">
                <el-tag size="small" :type="s.row.templateType === 1 ? 'success' : (s.row.templateType === 2 ? 'warning' : 'info')" disable-transitions>{{ tplTypeText(s.row.templateType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="条目数" width="70" align="center">
              <template #default="s">{{ tplItemCount(s.row.items) }}</template>
            </el-table-column>
            <el-table-column label="病种" width="96">
              <template #default="s">{{ s.row.diseaseCode || '-' }}</template>
            </el-table-column>
            <el-table-column label="使用次数" width="80" align="center">
              <template #default="s">{{ s.row.usageCount || 0 }}</template>
            </el-table-column>
            <el-table-column label="操作" width="96" align="center">
              <template #default="s">
                <el-button link type="primary" size="small" :disabled="tplApplying" @click="applyTemplate(s.row)">应用开嘱</el-button>
              </template>
            </el-table-column>
            <template #empty><div class="iw-empty-line">暂无模板</div></template>
          </el-table>
          <div class="iw-pager" v-if="tplTotal > tplSize">
            <el-pagination small background layout="prev, pager, next" :total="tplTotal" :page-size="tplSize"
                           :current-page="tplPage" @current-change="loadTemplates"></el-pagination>
          </div>
        </el-dialog>

        <!-- 合理用药审查结果 -->
        <el-dialog v-model="checkVisible" title="合理用药审查" width="560px">
          <template v-if="checkResult">
            <el-alert v-if="checkResult.passed && !(checkResult.warnings && checkResult.warnings.length)"
                      type="success" :closable="false" title="审查通过"></el-alert>
            <div v-if="checkResult.warnings && checkResult.warnings.length">
              <div class="iw-sect-title" style="margin-bottom:6px">风险提示</div>
              <div v-for="(w, i) in checkResult.warnings" :key="i" class="iw-warn-item danger">⚠ {{ w }}</div>
            </div>
            <div v-if="checkResult.highAlert" class="iw-warn-item danger">⚠ 高警示药品: 开立/调配/执行需严格双人核对</div>
            <div v-if="checkResult.doubleCheck" class="iw-warn-item">此药品需双人核对: 执行环节请由两名护士核对签字</div>
            <div v-if="!checkResult.passed" style="color:var(--yb-danger);font-size:13px;margin-top:8px">
              审查未通过(过敏冲突或重复用药)。如确需开立, 请点击「知情开具」, 开立人承担相应责任。
            </div>
          </template>
          <template #footer>
            <el-button @click="cancelPick">取消选药</el-button>
            <el-button v-if="checkResult && !checkResult.passed" type="warning" @click="overrideOk">知情开具</el-button>
            <el-button v-else type="primary" @click="checkVisible = false">继续开立</el-button>
          </template>
        </el-dialog>

        <!-- 医嘱行右键菜单 -->
        <div class="iw-ctx" v-if="ctx.visible" :style="ctxStyle" @contextmenu.prevent>
          <div class="iw-ctx-item" @click="ctxCmd('copy')"><span v-html="icons.copy"></span>复制开立</div>
          <div class="iw-ctx-item" v-if="canRenew(ctx.row)" @click="ctxCmd('renew')"><span v-html="icons.renew"></span>续开医嘱</div>
          <div class="iw-ctx-item" @click="ctxCmd('exec')"><span v-html="icons.exec"></span>查看执行详情</div>
          <div class="iw-ctx-item" v-if="ctx.row && ctx.row.drugId" @click="ctxCmd('leaflet')"><span v-html="icons.doc"></span>查看药品说明书</div>
          <div class="iw-ctx-sep"></div>
          <div class="iw-ctx-item" :class="{ 'is-disabled': !canStop(ctx.row) }" @click="ctxCmd('stop')">停止医嘱</div>
          <div class="iw-ctx-item" :class="{ 'is-disabled': !canCancel(ctx.row) }" @click="ctxCmd('cancel')">作废医嘱</div>
        </div>

        <!-- 执行详情 -->
        <el-dialog v-model="execVisible" title="医嘱执行详情" width="580px">
          <template v-if="execRow">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="医嘱内容" :span="2">{{ execRow.orderContent }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ orderTypeText(execRow.orderType) }}</el-descriptions-item>
              <el-descriptions-item label="分类">{{ categoryText(execRow.orderCategory) }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag size="small" :type="statusTag(execRow.orderStatus)" disable-transitions>{{ statusText(execRow.orderStatus) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="数量">{{ execRow.quantity != null ? execRow.quantity : '-' }}</el-descriptions-item>
              <el-descriptions-item label="剂量">{{ execRow.dosage ? (execRow.dosage + (execRow.dosageUnit || '')) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="用法 / 频次">{{ [execRow.usageCode ? usageText(execRow.usageCode) : '', execRow.freqCode ? freqText(execRow.freqCode) : ''].filter(Boolean).join(' / ') || '-' }}</el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ timeText(execRow.startTime) }}</el-descriptions-item>
              <el-descriptions-item label="停止时间">{{ execRow.stopTime ? timeText(execRow.stopTime) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="成组医嘱" :span="2">{{ execRow.groupNo ? ((execRow._groupLabel || '成组') + ' (' + execRow.groupNo + ')') : '非成组医嘱' }}</el-descriptions-item>
            </el-descriptions>
            <div class="iw-sect-title" style="margin:12px 0 2px">闭环轨迹</div>
            <div class="iw-tl">
              <div v-for="(t, i) in execTimeline" :key="i" class="iw-tl-item" :class="{ 'is-done': t.done }">
                <span class="dot"></span>
                <span class="lb">{{ t.label }}</span>
                <span class="nt" v-if="t.note">{{ t.note }}</span>
                <span class="tm">{{ t.time ? timeText(t.time) : (t.done ? '已完成' : '待处理') }}</span>
              </div>
            </div>
            <div class="iw-hint">执行明细(执行时间 / 执行人 / 双人核对)由护士站执行环节产生, 完整记录见护士站「医嘱执行」。</div>
          </template>
          <template #footer>
            <el-button type="primary" @click="execVisible = false">关闭</el-button>
          </template>
        </el-dialog>

        <!-- 药品说明书(按 drugId 回查本机构目录关键字段) -->
        <el-dialog v-model="leafletVisible" title="药品说明书" width="640px" top="6vh">
          <div v-loading="leafletLoading">
            <div class="iw-sect-title" style="margin-bottom:6px">{{ (leafletData && (leafletData.genericName || leafletData.tradeName)) || leafletName }}</div>
            <div class="iw-ldesc" v-if="leafletData">
              <div class="kv"><span>商品名</span><b>{{ leafletData.tradeName || '-' }}</b></div>
              <div class="kv"><span>剂型</span><b>{{ leafletData.dosformName || leafletData.dosform || '-' }}</b></div>
              <div class="kv"><span>规格</span><b>{{ leafletData.spec || '-' }}</b></div>
              <div class="kv"><span>生产企业</span><b>{{ leafletData.manufacturer || '-' }}</b></div>
              <div class="kv"><span>管理类别</span><b>{{ leafletData.drugClassName || '-' }}</b></div>
              <div class="kv"><span>抗菌分级</span><b>{{ leafletData.abxGradeName || '-' }}</b></div>
              <div class="kv"><span>皮试要求</span><b>{{ Number(leafletData.skinTestFlag) === 1 ? '需皮试' : '否' }}</b></div>
              <div class="kv"><span>单次最大量</span><b>{{ leafletData.maxQtyOnce != null ? (leafletData.maxQtyOnce + (leafletData.minUnit || '')) : '-' }}</b></div>
              <div class="kv"><span>剂量单位</span><b>{{ leafletData.doseUnit || '-' }}</b></div>
              <div class="kv"><span>每单位含药</span><b>{{ leafletData.unitDose != null ? leafletData.unitDose : '-' }}</b></div>
              <div class="kv"><span>包装换算</span><b>{{ leafletData.packRatio != null ? (leafletData.packRatio + (leafletData.minUnit || '') + '/' + (leafletData.packUnit || '')) : '-' }}</b></div>
              <div class="kv"><span>医保甲乙</span><b>{{ leafletData.chrgitmLvName || '自费' }}</b></div>
              <div class="kv"><span>零售价</span><b class="iw-money">{{ leafletData.retailPrice != null ? money(leafletData.retailPrice) : '-' }}</b></div>
              <div class="kv"><span>OTC</span><b>{{ Number(leafletData.otcFlag) === 1 ? '是' : '否' }}</b></div>
              <div class="kv"><span>基本药物</span><b>{{ Number(leafletData.essentialFlag) === 1 ? '是' : '否' }}</b></div>
              <div class="kv"><span>妊娠分级</span><b>{{ leafletData.pregClass || '-' }}</b></div>
            </div>
            <div v-else-if="!leafletLoading" class="iw-empty-line" style="padding:16px;text-align:center">未获取到该药品的目录信息</div>
          </div>
          <template #footer>
            <el-button type="primary" @click="leafletVisible = false">关闭</el-button>
          </template>
        </el-dialog>

        <!-- 手麻P1: 发起手术申请对话框(当前住院患者) -->
        <el-dialog v-model="surgApplyVisible" title="发起手术申请" width="640px" :close-on-click-modal="false" append-to-body>
          <el-form :model="surgApplyForm" label-width="96px" size="small">
            <el-form-item label="住院患者">
              <el-input :model-value="visitId ? ('当前就诊ID ' + visitId) : '未选择患者'" disabled></el-input>
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="12"><el-form-item label="手术编码"><el-input v-model="surgApplyForm.surgeryCode" maxlength="50" placeholder="ICD-9-CM-3"></el-input></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="手术名称" required><el-input v-model="surgApplyForm.surgeryName" maxlength="100" placeholder="手术名称" @change="loadSurgCandidates"></el-input></el-form-item></el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12"><el-form-item label="手术级别">
                <el-select v-model="surgApplyForm.surgeryLevel" clearable placeholder="选择级别" style="width:100%" @change="loadSurgCandidates">
                  <el-option v-for="o in surgLevelOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>
                </el-select>
              </el-form-item></el-col>
              <el-col :span="12"><el-form-item label="拟麻醉方式">
                <el-select v-model="surgApplyForm.anesthesiaType" clearable placeholder="选择麻醉方式" style="width:100%">
                  <el-option v-for="o in surgAneOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>
                </el-select>
              </el-form-item></el-col>
            </el-row>
            <el-form-item label="拟主刀医师" required>
              <el-select v-model="surgApplyForm.surgeonId" filterable :loading="surgCandLoading" placeholder="按手术级别自动过滤权限, 无权限医师灰显" style="width:100%">
                <el-option v-for="c in surgCandidates" :key="c.id" :label="c.staff_name + (c.allowed ? '' : '（无权限）')" :value="c.id" :disabled="!c.allowed">
                  <span :style="c.allowed ? '' : 'color:var(--yb-ink-4)'">{{ c.staff_name }}</span>
                  <span style="float:right;font-size:12px">{{ c.allowed ? (c.surgery_level ? (c.surgery_level + '级') : '') : c.reason }}</span>
                </el-option>
              </el-select>
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="12"><el-form-item label="期望手术时间">
                <el-date-picker v-model="surgApplyForm.expectTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="精确到分" style="width:100%"></el-date-picker>
              </el-form-item></el-col>
              <el-col :span="12"><el-form-item label="手术时限">
                <el-radio-group v-model="surgApplyForm.deadlineType">
                  <el-radio-button :value="1">择期</el-radio-button><el-radio-button :value="2">限期</el-radio-button><el-radio-button :value="3">急诊</el-radio-button>
                </el-radio-group>
              </el-form-item></el-col>
            </el-row>
            <el-form-item label="术前诊断"><el-input v-model="surgApplyForm.preOpDiag" maxlength="100"></el-input></el-form-item>
            <el-form-item label="病情简介"><el-input v-model="surgApplyForm.applyReason" type="textarea" :rows="2" maxlength="300"></el-input></el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="surgApplyVisible = false">取消</el-button>
            <el-button size="small" type="primary" :loading="surgApplySaving" @click="submitSurgApply">提交申请</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpOrderPanel = InpOrderPanel;
})();
