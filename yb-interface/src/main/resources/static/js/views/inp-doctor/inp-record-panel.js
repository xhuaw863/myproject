/* 住院医生站 - 病历面板: 九类文书 左列表 + 右编辑器(结构化模板表单)
 * 接口: GET /api/his/inp/record/list/{visitId} | GET /api/his/inp/record/{id}
 *       POST /api/his/inp/record | PUT /api/his/inp/record/{id} | PUT /{id}/submit | PUT /{id}/audit
 *       POST /{id}/sign?signLevel=attending|director (三级签名, 须先经签名板 round_sign 留痕)
 *       GET /{id}/versions | GET /version/{versionId} | GET /version/diff?v1=&v2= (版本历史/详情/对比)
 * 结构化模板: GET /api/his/emr/template/list(按类别分组) | GET /api/his/emr/template/{id}(类别) | GET /{id}/fields(字段定义)
 *       POST /api/his/inp/record/from-template {visitId,templateId} → 预填充落库记录
 *       POST /api/his/emr/macro/resolve {visitId,macroCodes} → Map<macroCode,value>
 * 质控: POST /api/his/emr/quality/evaluate/{recordId} → {score,hasFatal,details[]} (保存后自动刷新)
 * 打印: GET /api/his/inp/print/render/emr?recordId=xx → HTML → HIS.printHtmlFrame
 * 状态机: 1草稿(可编辑/保存/提交) -> 2已提交(可审核, 只读) -> 3已审核(只读)
 * 无 templateId 的病历保留 content 纯文本编辑(向后兼容)。
 * 富文本模式: content 以 '<' 开头的病历走 wangEditor v5(本地 js/lib/wangeditor) 富编辑,
 *       结构化/纯文本病历可经切换按钮转富文本(既有输入拼段落HTML), 富文书打印走本地直渲染;
 *       wangEditor 未加载时自动回退 contenteditable+execCommand 迷你工具栏。
 * 注册: HIS.components.InpRecordPanel (须在 inp-doctor.js 之前加载)
 * 三级签名链(T43): 查房记录(record_type=4) 住院医师提交→主治签→主任签; 顶部状态条 +
 *       待签级别按当前用户职称(myTitleCode)显示签名按钮; 版本历史抽屉支持双版本勾选行级 diff 对比。 */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 面板私有样式(时限横幅/质控评分/模板选择), 一次性注入 */
  (function ensureStyles() {
    if (document.getElementById('inp-record-extra-style')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-record-extra-style';
    st.textContent = [
      '@keyframes iwDeadlineBlink { 50% { opacity:0.4; } }',
      '.iw-deadline-banner { flex:none; display:flex; align-items:center; gap:8px; margin:10px 16px 0; padding:8px 12px; border-radius:var(--yb-r-sm); border:1px solid transparent; font-size:var(--yb-fs-base); font-weight:600; }',
      '.iw-deadline-banner.ok { background:var(--yb-success-bg); border-color:var(--yb-success-border); color:var(--yb-success-strong); }',
      '.iw-deadline-banner.urgent { background:var(--yb-warning-bg); border-color:var(--yb-warning-border); color:var(--yb-warning-strong); animation:iwDeadlineBlink 1.2s ease-in-out infinite; }',
      '.iw-deadline-banner.over { background:var(--yb-danger-bg); border-color:var(--yb-danger-border); color:var(--yb-danger-strong); animation:iwDeadlineBlink 1.2s ease-in-out infinite; }',
      '.iw-req-star { color:var(--yb-danger); margin-right:2px; }',
      '.iw-rf-emr { display:flex; align-items:flex-start; gap:8px; }',
      '.iw-rf-emr .emr-field { flex:1; min-width:0; margin:6px 0; }',
      '.iw-qc-score { font-size:40px; font-weight:700; line-height:1.1; font-variant-numeric:tabular-nums; }',
      '.iw-qc-score.good { color:var(--yb-success-strong); }',
      '.iw-qc-score.mid { color:var(--yb-warning-strong); }',
      '.iw-qc-score.bad { color:var(--yb-danger-strong); }',
      '.iw-qc-row { display:flex; align-items:flex-start; gap:8px; padding:9px 0; border-bottom:1px dashed var(--yb-divider); font-size:var(--yb-fs-base); }',
      '.iw-qc-row:last-child { border-bottom:none; }',
      '.iw-qc-row .icon { flex:none; font-weight:700; }',
      '.iw-qc-row.passed .icon { color:var(--yb-success); }',
      '.iw-qc-row.failed .icon { color:var(--yb-danger); }',
      '.iw-tpl-group-head { margin:10px 0 6px; font-size:var(--yb-fs-sm); font-weight:600; color:var(--yb-ink-2); }',
      '.iw-tpl-card { padding:10px 12px; margin-bottom:8px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); cursor:pointer; transition:all var(--yb-dur) var(--yb-ease); }',
      '.iw-tpl-card:hover { border-color:var(--yb-brand-border); background:var(--yb-brand-subtle); }',
      '.iw-tpl-card .n { font-weight:600; color:var(--yb-ink-1); }',
      /* 列表项 hover 快捷操作 / 按钮前置图标 */
      '.iw-record-item { position:relative; }',
      '.iw-rop { position:absolute; right:6px; top:6px; display:inline-flex; align-items:center; justify-content:center; width:22px; height:22px; padding:0; border:none; border-radius:50%; background:var(--yb-surface); color:var(--yb-ink-3); cursor:pointer; box-shadow:var(--yb-sh-1); opacity:0; transition:opacity var(--yb-dur) var(--yb-ease), color var(--yb-dur) var(--yb-ease); }',
      '.iw-record-item:hover .iw-rop { opacity:1; }',
      '.iw-rop:hover { color:var(--yb-brand); }',
      '.iw-rop .iw-ico { width:13px; height:13px; }',
      '.iw-bicon { display:inline-flex; align-items:center; margin-right:4px; vertical-align:-2px; }',
      '.iw-bicon .iw-ico { width:13px; height:13px; }',
      /* 富文本编辑区(wangEditor v5 / contenteditable 兜底) */
      '.iw-rich-bar { display:flex; align-items:center; gap:10px; margin-bottom:10px; }',
      '.iw-rich-bar .iw-dim { font-size:var(--yb-fs-sm); }',
      '.iw-rich-wrap { display:flex; flex-direction:column; min-width:0; }',
      '.iw-wang-toolbar { border:1px solid var(--yb-border); border-bottom:none; border-radius:var(--yb-r-sm) var(--yb-r-sm) 0 0; overflow:hidden; }',
      '.iw-wang-editor { height:480px; overflow:auto; border:1px solid var(--yb-border); border-radius:0 0 var(--yb-r-sm) var(--yb-r-sm); }',
      '.iw-mini-tools { display:flex; flex-wrap:wrap; gap:4px; padding:6px 8px; border:1px solid var(--yb-border); border-bottom:none; border-radius:var(--yb-r-sm) var(--yb-r-sm) 0 0; background:var(--yb-surface); }',
      '.iw-mini-tools button { min-width:30px; height:26px; padding:0 8px; border:1px solid var(--yb-border); border-radius:4px; background:var(--yb-surface); color:var(--yb-ink-1); font-size:12px; cursor:pointer; }',
      '.iw-mini-tools button:hover { border-color:var(--yb-brand); color:var(--yb-brand); }',
      '.iw-editable { min-height:400px; max-height:560px; overflow:auto; padding:12px 16px; border:1px solid var(--yb-border); border-radius:0 0 var(--yb-r-sm) var(--yb-r-sm); outline:none; background:var(--yb-surface); font-size:var(--yb-fs-base); line-height:1.7; word-break:break-all; }',
      '.iw-editable:focus { border-color:var(--yb-brand); }',
      /* ===== T43 三级签名链 ===== */
      '.iw-sign-chain { display:flex; align-items:center; gap:8px; margin:10px 16px 0; padding:9px 12px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); background:var(--yb-surface); flex-wrap:wrap; }',
      '.iw-sc-node { display:inline-flex; align-items:center; gap:6px; padding:4px 12px; border-radius:999px; border:1px dashed var(--yb-border-strong); font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }',
      '.iw-sc-node .tag { font-weight:700; }',
      '.iw-sc-node .sub { font-size:12px; font-variant-numeric:tabular-nums; }',
      '.iw-sc-node.done { border-style:solid; border-color:var(--yb-success-border); background:var(--yb-success-bg); color:var(--yb-success-strong); }',
      '.iw-sc-node.pending { border-style:solid; border-color:var(--yb-warning-border); background:var(--yb-warning-bg); color:var(--yb-warning-strong); }',
      '.iw-sc-arrow { flex:none; color:var(--yb-ink-4); font-size:12px; }',
      '.iw-sc-spacer { flex:1; }',
      /* ===== T43 版本历史 ===== */
      '.iw-vrow { display:flex; align-items:flex-start; gap:10px; padding:10px 2px; border-bottom:1px dashed var(--yb-divider); }',
      '.iw-vrow:last-child { border-bottom:none; }',
      '.iw-vrow .body { flex:1; min-width:0; }',
      '.iw-vrow .n { font-weight:600; color:var(--yb-ink-1); }',
      '.iw-vrow .m { font-size:12px; color:var(--yb-ink-3); margin-top:2px; }',
      '.iw-vtype { display:inline-block; padding:1px 8px; margin-left:6px; border-radius:999px; font-size:12px; font-weight:400; border:1px solid var(--yb-border); color:var(--yb-ink-2); }',
      '.iw-vfoot { display:flex; align-items:center; gap:8px; padding-top:12px; margin-top:6px; border-top:1px solid var(--yb-border); }',
      '.iw-vpre { max-height:56vh; overflow:auto; margin:0; padding:10px 12px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); background:var(--yb-surface); font-size:12px; line-height:1.6; white-space:pre-wrap; word-break:break-all; }',
      /* ===== T43 版本对比 ===== */
      '.iw-diff-sum { display:flex; align-items:center; gap:14px; margin-bottom:8px; font-size:var(--yb-fs-sm); color:var(--yb-ink-2); }',
      '.iw-diff-sum .add { color:var(--yb-success-strong); }',
      '.iw-diff-sum .del { color:var(--yb-danger-strong); }',
      '.iw-diff-sum .modify { color:var(--yb-warning-strong); }',
      '.iw-diff-heads { display:grid; grid-template-columns:1fr 1fr; gap:0 10px; margin-bottom:6px; }',
      '.iw-diff-col-head { padding:6px 8px; border-radius:4px; background:var(--yb-brand-subtle); color:var(--yb-ink-2); font-size:12px; }',
      '.iw-diff-wrap { display:grid; grid-template-columns:1fr 1fr; gap:2px 10px; max-height:58vh; overflow:auto; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); padding:8px 0; }',
      '.iw-diff-line { display:flex; gap:6px; padding:2px 6px; border-radius:4px; font-size:12px; line-height:1.65; }',
      '.iw-diff-line .ln { flex:none; width:30px; text-align:right; color:var(--yb-ink-4); font-variant-numeric:tabular-nums; user-select:none; }',
      '.iw-diff-line .tx { flex:1; min-width:0; white-space:pre-wrap; word-break:break-all; }',
      '.iw-diff-line.is-add { background:var(--yb-success-bg); }',
      '.iw-diff-line.is-del { background:var(--yb-danger-bg); }',
      '.iw-diff-line.is-modify { background:var(--yb-warning-bg); }',
      '.iw-diff-line.is-empty { background:var(--yb-divider); opacity:0.35; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  const RECORD_TYPE = {
    1: '入院记录', 2: '首次病程', 3: '日常病程', 4: '查房记录', 5: '术前小结',
    6: '手术记录', 7: '术后病程', 8: '出院小结', 9: '死亡记录'
  };
  const RECORD_STATUS = { 1: '草稿', 2: '已提交', 3: '已审核' };
  const RECORD_STATUS_TYPE = { 1: 'info', 2: 'warning', 3: 'success' };

  /* 内联 SVG 图标(项目未引入图标库, 统一 24 视框 / currentColor 染色), 经 v-html 渲染 */
  const ICONS = {
    printer: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9V3h12v6"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="7" rx="1"/></svg>',
    save: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><path d="M17 21v-8H7v8"/><path d="M7 3v5h8"/></svg>',
    send: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13"/><path d="M22 2 l-7 20-4-9-9-4 20-7z"/></svg>',
    check: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="m8.5 12.5 2.5 2.5 5-5"/></svg>',
    clock: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>'
  };

  /* 结构化模板类别(与后端 EmrTemplateService 种子一致) */
  const TPL_CATEGORY = {
    1: '入院记录', 2: '首次病程', 3: '日常病程', 4: '上级医师查房',
    5: '手术记录', 6: '术后病程', 7: '出院小结', 8: '死亡记录'
  };
  /* 上级查房(类别4)查房级别: 与 his_inp_medical_record.round_level 注释一致 */
  const ROUND_LEVELS = [
    { value: 1, label: '住院医师' },
    { value: 2, label: '主治医师' },
    { value: 3, label: '主任医师' }
  ];

  /* 各文书类型编辑字段(无模板病历时使用, 通用文本结构兜底) */
  const RECORD_FORMS = {
    1: [
      { key: 'chiefComplaint', label: '主诉', type: 'textarea', rows: 1 },
      { key: 'presentIllness', label: '现病史', type: 'textarea', rows: 3 },
      { key: 'pastHistory', label: '既往史', type: 'textarea', rows: 2 },
      { key: 'personalHistory', label: '个人史', type: 'textarea', rows: 2 },
      { key: 'familyHistory', label: '家族史', type: 'textarea', rows: 2 },
      { key: 'physicalExam', label: '体格检查', type: 'textarea', rows: 3 },
      { key: 'auxiliaryExam', label: '辅助检查', type: 'textarea', rows: 2 },
      { key: 'preliminaryDiag', label: '初步诊断', type: 'textarea', rows: 2 }
    ],
    3: [
      { key: 'recordDate', label: '日期', type: 'input', placeholder: '如 2026-09-28' },
      { key: 'courseContent', label: '病程内容', type: 'textarea', rows: 6 },
      { key: 'doctorSign', label: '医师签名', type: 'input' }
    ],
    4: [
      { key: 'roundDate', label: '查房日期', type: 'input', placeholder: '如 2026-09-28' },
      { key: 'directorOpinion', label: '主任意见', type: 'textarea', rows: 4 },
      { key: 'treatmentAdjust', label: '治疗调整', type: 'textarea', rows: 4 }
    ],
    8: [
      { key: 'admitDate', label: '入院日期', type: 'input', placeholder: '如 2026-09-20' },
      { key: 'dischargeDate', label: '出院日期', type: 'input', placeholder: '如 2026-09-28' },
      { key: 'admitDiag', label: '入院诊断', type: 'textarea', rows: 2 },
      { key: 'dischargeDiag', label: '出院诊断', type: 'textarea', rows: 2 },
      { key: 'treatmentCourse', label: '治疗经过', type: 'textarea', rows: 4 },
      { key: 'dischargeAdvice', label: '出院医嘱', type: 'textarea', rows: 3 }
    ]
  };
  const GENERIC_FORM = [
    { key: 'text', label: '记录内容', type: 'textarea', rows: 8 },
    { key: 'doctorSign', label: '医师签名', type: 'input' }
  ];

  /* ===== 富文本编辑 ===== */
  /* wangEditor v5 可用性检测(本地 js/lib/wangeditor, 加载失败回退 contenteditable) */
  const RICH_ENGINE = (typeof window !== 'undefined' && window.wangEditor
    && typeof window.wangEditor.createEditor === 'function') ? 'wangeditor' : 'editable';

  /* contenteditable 迷你工具栏(execCommand, wangEditor 不可用时的兜底) */
  const MINI_TOOLS = [
    { cmd: 'formatBlock', arg: '<h2>', label: '标题' },
    { cmd: 'formatBlock', arg: '<p>', label: '正文' },
    { cmd: 'bold', label: 'B', sty: 'font-weight:700' },
    { cmd: 'italic', label: 'I', sty: 'font-style:italic' },
    { cmd: 'underline', label: 'U', sty: 'text-decoration:underline' },
    { cmd: 'strikeThrough', label: 'S', sty: 'text-decoration:line-through' },
    { cmd: 'insertUnorderedList', label: '• 列表' },
    { cmd: 'insertOrderedList', label: '1. 列表' }
  ];

  /* HTML 转义(结构化字段转富文本段落用) */
  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function timeText(value, shortMode) {
    if (!value) { return '-'; }
    const s = String(value).replace('T', ' ');
    if (shortMode) { return s.length >= 16 ? s.substring(5, 16) : s; }
    return s.length >= 16 ? s.substring(0, 16) : s;
  }

  function today() {
    const d = new Date();
    const p = n => (n < 10 ? '0' : '') + n;
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
  }

  /* 毫秒差 → "Xh Xm" */
  function fmtDuration(ms) {
    const m = Math.max(0, Math.floor(Math.abs(ms) / 60000));
    return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
  }

  const InpRecordPanel = {
    name: 'InpRecordPanel',
    components: { 'emr-field': (window.HIS.components || {}).EmrField },
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return {
        loadingSide: false,
        loading: false,
        saving: false,
        list: [],
        current: null,
        title: '',
        formContent: {},
        recordTypes: RECORD_TYPE,
        /* 结构化模板 */
        structFields: [],
        structForm: {},
        currentCategory: null,
        tplCategoryCache: {},
        /* 模板选择 */
        tplVisible: false,
        tplLoading: false,
        tplList: [],
        applyingTpl: false,
        /* 质控评分 */
        qualityVisible: false,
        qualityLoading: false,
        quality: null,
        /* 查房级别/上级医师 */
        roundLevel: null,
        attendingDoctorId: null,
        doctors: [],
        roundLevels: ROUND_LEVELS,
        /* 时限横幅/打印/宏 */
        nowTs: Date.now(),
        printing: false,
        macroFilling: false,
        /* 操作图标 */
        icons: ICONS,
        /* 富文本编辑: editorMode 'struct'结构化|'rich'富文本 */
        editorMode: 'struct',
        richContent: '',
        editorInstance: null,
        toolbarInstance: null,
        richEngine: RICH_ENGINE,
        miniTools: MINI_TOOLS,
        /* T43 三级签名链/版本历史 */
        myTitleCode: null,        /* 当前用户职称编码(CV08.30.005, 懒加载) */
        signing: false,           /* 签名请求中 */
        versions: [],             /* 版本快照列表 */
        versionVisible: false,    /* 版本历史抽屉 */
        versionLoading: false,
        versionSel: [],           /* 勾选版本(最多2) */
        previewVisible: false,    /* 单版本快照预览 */
        previewLoading: false,
        previewData: null,
        previewTitle: '',
        diffVisible: false,       /* 版本对比弹窗 */
        diffLoading: false,
        diffData: null,
        /* Phase D 互操作导出 + SM2 可靠电子签名 */
        exporting: false,         /* 导出请求中 */
        sigDlgVisible: false,     /* 电子签名(SM2)弹窗 */
        sigChain: [],             /* 有效签名链 */
        sigLoading: false,
        sigStage: 'author',       /* 当前选择的签名环节 */
        sigStages: [
          { value: 'author', label: '书写医师' },
          { value: 'resident', label: '住院医师' },
          { value: 'attending', label: '主治医师' },
          { value: 'director', label: '主任医师' }
        ],
        sigSigning: false,
        sigVerifying: false,
        verifyResult: null        /* 验签结果 {passed,sigValid,digestMatch,certSn,signerName,signTime} */
      };
    },
    computed: {
      schema() { return this.schemaOf(this.current && this.current.recordType); },
      readonly() { return !!this.current && Number(this.current.status) !== 1; },
      isStruct() { return !!(this.current && this.current.templateId); },
      isRich() { return this.editorMode === 'rich'; },
      groupedRecords() {
        const byType = {};
        this.list.forEach(function (r) {
          const t = String(Number(r.recordType) || 0);
          (byType[t] = byType[t] || []).push(r);
        });
        return Object.keys(byType).sort((a, b) => Number(a) - Number(b)).map(function (k) {
          return { type: Number(k), label: RECORD_TYPE[k] || ('类型' + k), items: byType[k] };
        });
      },
      tplGroups() {
        const byCat = {};
        (this.tplList || []).forEach(function (t) {
          const c = t.templateCategory != null ? Number(t.templateCategory) : 0;
          (byCat[c] = byCat[c] || []).push(t);
        });
        return Object.keys(byCat).sort((a, b) => Number(a) - Number(b)).map(function (k) {
          return { category: Number(k), label: TPL_CATEGORY[k] || ('类别' + k), items: byCat[k] };
        });
      },
      /* 时限横幅: >2h 绿 / 0-2h 黄闪烁 / 超时 红闪烁 */
      deadlineInfo() {
        const d = this.current && this.current.deadlineTime;
        if (!d) { return null; }
        const ts = new Date(String(d).replace(' ', 'T')).getTime();
        if (isNaN(ts)) { return null; }
        const diff = ts - this.nowTs;
        if (diff > 0) {
          return { cls: diff > 7200000 ? 'ok' : 'urgent', text: '距书写截止还有 ' + fmtDuration(diff) };
        }
        return { cls: 'over', text: '已超时 ' + fmtDuration(diff) };
      },
      qualityScore() {
        if (this.quality && this.quality.score != null) { return Number(this.quality.score); }
        if (this.current && this.current.qualityScore != null) { return Number(this.current.qualityScore); }
        return null;
      },
      scoreCls() {
        const s = this.qualityScore;
        if (s == null) { return ''; }
        return s >= 90 ? 'good' : (s >= 70 ? 'mid' : 'bad');
      },
      qcButtonType() {
        const s = this.qualityScore;
        if (s == null) { return 'info'; }
        return s >= 90 ? 'success' : (s >= 70 ? 'warning' : 'danger');
      },
      qualityScoreText() {
        const s = this.qualityScore;
        return s == null ? '未评' : s + '分';
      },
      qualityHint() {
        if (this.quality && this.quality.hasFatal) { return '存在一票否决项, 得分归零'; }
        return '满分100分, 90分及以上为甲级';
      },
      qcDetailList() {
        return this.quality && this.quality.details ? this.quality.details : [];
      },
      /* ===== T43 三级签名链 ===== */
      isRoundRecord() {
        return !!(this.current && Number(this.current.recordType) === 4);
      },
      /* 住院医师级是否已提交: 状态已推进 或 版本历史存在 submit 快照 */
      roundSubmitted() {
        if (Number(this.current && this.current.status) >= 2) { return true; }
        return (this.versions || []).some(function (v) { return v.operateType === 'submit'; });
      },
      /* 最近一次住院医师提交(版本历史中 versionNo 最大的 submit 版本) */
      roundSubmitInfo() {
        let latest = null;
        (this.versions || []).forEach(function (v) {
          if (v.operateType !== 'submit') { return; }
          if (!latest || Number(v.versionNo) > Number(latest.versionNo)) { latest = v; }
        });
        return latest;
      },
      attendingSigned() { return !!(this.current && this.current.attendingSignId != null); },
      directorSigned() { return !!(this.current && this.current.directorSignId != null); },
      /* 当前用户职称匹配的待签级别(显示签名按钮) */
      canSignAttending() {
        return this.isRoundRecord && this.myTitleCode === '3' && !this.attendingSigned
          && Number(this.current && this.current.status) === 1;
      },
      canSignDirector() {
        return this.isRoundRecord && (this.myTitleCode === '1' || this.myTitleCode === '2')
          && !this.directorSigned && Number(this.current && this.current.status) === 2;
      },
      /* 版本快照预览文本(content 优先, 回退 structure) */
      previewText() {
        if (!this.previewData) { return ''; }
        const c = this.previewData.contentSnapshot;
        if (c != null && String(c).length) { return String(c); }
        const s = this.previewData.structureSnapshot;
        return s == null ? '' : String(s);
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler() {
          this.current = null;
          this.title = '';
          this.formContent = {};
          this.structFields = [];
          this.structForm = {};
          this.currentCategory = null;
          this.quality = null;
          this.roundLevel = null;
          this.attendingDoctorId = null;
          /* T43 状态复位 */
          this.versions = [];
          this.versionSel = [];
          this.versionVisible = false;
          this.previewVisible = false;
          this.previewData = null;
          this.diffVisible = false;
          this.diffData = null;
          /* 富文本状态复位(编辑器容器随 v-if 销毁, 实例须同步销毁) */
          this.editorMode = 'struct';
          this.richContent = '';
          this.destroyRichEditor();
          this.load();
        }
      },
      readonly() { this.applyRichReadOnly(); }
    },
    mounted() {
      const vm = this;
      /* 时限横幅每分钟刷新一次 */
      this.deadlineTimer = setInterval(function () { vm.nowTs = Date.now(); }, 60000);
    },
    beforeUnmount() {
      if (this.deadlineTimer) { clearInterval(this.deadlineTimer); this.deadlineTimer = null; }
      this.destroyRichEditor();
    },
    methods: {
      timeText,
      schemaOf(type) { return RECORD_FORMS[Number(type)] || GENERIC_FORM; },
      typeText(v) { return RECORD_TYPE[v] || '-'; },
      statusText(v) { return RECORD_STATUS[v] || '-'; },
      statusTag(v) { return RECORD_STATUS_TYPE[v] || ''; },
      doctorLabel(d) { return d.staffName + (d.titleName ? '（' + d.titleName + '）' : ''); },
      fieldMax(f) {
        const n = Number(f && f.maxLength);
        return n > 0 ? n : undefined;
      },
      fieldOptions(f) {
        if (!f || !f.options) { return []; }
        return String(f.options).split(/[,，]/).map(function (s) { return s.trim(); }).filter(Boolean);
      },
      isReq(f) { return !!(f && String(f.required) === 'true'); },
      hasMacro(f) { return !!(f && f.defaultMacro); },
      severityText(sev, deduct) {
        if (Number(sev) === 3) { return '一票否决'; }
        if (Number(sev) === 1) { return '警告'; }
        return Number(deduct) > 0 ? '-' + deduct + '分' : '扣分';
      },
      isActive(row) {
        return !!(this.current && this.current.id != null && HIS.sameId(this.current.id, row.id));
      },
      load() {
        const vm = this;
        if (!vm.visitId) { vm.list = []; return Promise.resolve(); }
        vm.loadingSide = true;
        return HIS.get('/api/his/inp/record/list/' + HIS.idParam(vm.visitId))
          .then(function (data) { vm.list = data || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.loadingSide = false; });
      },
      parseContent(content, recordType) {
        let obj = {};
        if (content) {
          if (typeof content === 'string') {
            try { obj = JSON.parse(content) || {}; } catch (e) { obj = { text: String(content) }; }
          } else if (typeof content === 'object') {
            obj = content;
          }
        }
        const schema = this.schemaOf(recordType);
        const out = {};
        schema.forEach(function (f) { out[f.key] = obj[f.key] != null ? obj[f.key] : ''; });
        /* 兼容纯文本旧数据: 首个字段缺失时用 text 兜底 */
        if (obj.text && schema.length && !String(out[schema[0].key] || '').length) {
          out[schema[0].key] = obj.text;
        }
        return out;
      },
      open(row) {
        const vm = this;
        if (!row) { return; }
        const hydrate = function (rec) {
          vm.current = rec;
          vm.title = rec.title || '';
          vm.formContent = vm.parseContent(rec.content, rec.recordType);
          vm.quality = null;
          vm.roundLevel = rec.roundLevel != null ? Number(rec.roundLevel) : null;
          vm.attendingDoctorId = rec.attendingDoctorId != null ? HIS.id(rec.attendingDoctorId) : null;
          /* 富文书识别: content 为 HTML('<'开头) → 富文本模式; 其余(JSON/纯文本/空) → 结构化 */
          const rc = typeof rec.content === 'string' ? rec.content : '';
          if (rc.trim().charAt(0) === '<') {
            vm.editorMode = 'rich';
            vm.richContent = rec.content;
          } else {
            vm.editorMode = 'struct';
            vm.richContent = '';
          }
          if (rec.templateId) {
            vm.hydrateStruct(rec);
            vm.loadTemplateCategory(rec.templateId);
          } else {
            vm.structFields = [];
            vm.structForm = {};
            vm.currentCategory = null;
          }
          /* T43: 查房记录加载版本列表(判定住院医师已提交) + 当前用户职称(签名按钮) */
          if (Number(rec.recordType) === 4 && rec.id) {
            vm.loadVersions(true);
            vm.loadMyTitle();
          } else {
            vm.versions = [];
          }
          vm.$nextTick(function () { vm.syncRichEditor(); });
        };
        if (!row.id) { hydrate(row); return; }
        vm.loading = true;
        HIS.get('/api/his/inp/record/' + HIS.idParam(row.id))
          .then(function (full) { hydrate(full || row); })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      reloadCurrent() {
        if (this.current && this.current.id) { this.open(this.current); }
      },
      /* 结构化字段: structureData 优先, 旧数据回退 content JSON */
      hydrateStruct(rec) {
        const vm = this;
        let obj = {};
        const raw = rec.structureData || (rec.content && String(rec.content).charAt(0) === '{' ? rec.content : null);
        if (raw) { try { obj = JSON.parse(raw) || {}; } catch (e) { obj = {}; } }
        vm.structForm = obj;
        vm.loadDoctors();
        vm.loadStructFields(rec.templateId);
      },
      loadStructFields(templateId) {
        const vm = this;
        if (templateId == null) { return Promise.resolve(); }
        return HIS.get('/api/his/emr/template/' + HIS.idParam(templateId) + '/defs')
          .then(function (fields) {
            vm.structFields = (Array.isArray(fields) ? fields : [])
              .filter(function (f) { return f && f.fieldKey; });
            vm.structFields.forEach(function (f) {
              const multi = f.type === 'checkbox' || f.type === 'multiselect' || f.type === 'table';
              if (vm.structForm[f.fieldKey] === undefined) {
                vm.$set ? vm.$set(vm.structForm, f.fieldKey, multi ? [] : '') : (vm.structForm[f.fieldKey] = multi ? [] : '');
              }
            });
          })
          .catch(function (e) { console.warn('病历模板字段加载失败', e); });
      },
      loadTemplateCategory(templateId) {
        const vm = this;
        if (templateId == null) { vm.currentCategory = null; return; }
        if (vm.tplCategoryCache[HIS.idKey(templateId)] != null) { vm.currentCategory = vm.tplCategoryCache[HIS.idKey(templateId)]; return; }
        HIS.get('/api/his/emr/template/' + HIS.idParam(templateId))
          .then(function (tpl) {
            const c = tpl && tpl.templateCategory != null ? Number(tpl.templateCategory) : null;
            vm.tplCategoryCache[HIS.idKey(templateId)] = c;
            vm.currentCategory = c;
          })
          .catch(function () { vm.currentCategory = null; });
      },
      loadDoctors() {
        const vm = this;
        if (vm.doctors && vm.doctors.length) { return; }
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师'))
          .then(function (list) { vm.doctors = list || []; })
          .catch(function () { });
      },
      /* ===== 模板选择 / 建档 ===== */
      openTplPicker() {
        const vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        vm.tplVisible = true;
        vm.loadTplList();
      },
      loadTplList() {
        const vm = this;
        vm.tplLoading = true;
        HIS.get('/api/his/emr/template/list')
          .then(function (list) { vm.tplList = list || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.tplLoading = false; });
      },
      applyTemplate(tpl) {
        const vm = this;
        if (!tpl || vm.applyingTpl) { return; }
        vm.applyingTpl = true;
        HIS.post('/api/his/inp/record/from-template', { visitId: HIS.id(vm.visitId), templateId: HIS.id(tpl.id) })
          .then(function (rec) {
            vm.tplVisible = false;
            HIS.notifySuccess('已按「' + (tpl.templateName || '模板') + '」生成病历, 请完善内容');
            /* 类别入缓存, 避免再次请求 */
            if (tpl.templateCategory != null) { vm.tplCategoryCache[HIS.idKey(tpl.id)] = Number(tpl.templateCategory); }
            const r = rec || {};
            if (r.templateId == null) { r.templateId = tpl.id; }
            if (r.id) {
              return vm.load().then(function () { vm.open(r); });
            }
            return vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.applyingTpl = false; });
      },
      /* ===== 宏自动填充 ===== */
      pickMacroValue(map, f) {
        if (!map) { return null; }
        if (map[f.defaultMacro] != null && String(map[f.defaultMacro]).length) { return map[f.defaultMacro]; }
        if (map[f.fieldKey] != null && String(map[f.fieldKey]).length) { return map[f.fieldKey]; }
        const keys = Object.keys(map);
        return keys.length ? map[keys[0]] : null;
      },
      resolveMacro(f) {
        const vm = this;
        if (!vm.visitId || !f || !f.defaultMacro) { return; }
        HIS.post('/api/his/emr/macro/resolve', { visitId: HIS.id(vm.visitId), macroCodes: [f.defaultMacro] })
          .then(function (map) {
            const v = vm.pickMacroValue(map, f);
            if (v != null && String(v).length) {
              vm.structForm[f.fieldKey] = v;
              HIS.notifySuccess('已自动填充「' + (f.label || f.fieldKey) + '」');
            } else {
              ElementPlus.ElMessage.warning('宏 ' + f.defaultMacro + ' 暂无可解析值');
            }
          })
          .catch(HIS.notifyError);
      },
      resolveAllMacros() {
        const vm = this;
        const fields = (vm.structFields || []).filter(function (f) { return f.defaultMacro; });
        if (!fields.length) { ElementPlus.ElMessage.info('当前模板无自动填充宏'); return; }
        const codes = [];
        fields.forEach(function (f) { if (codes.indexOf(f.defaultMacro) < 0) { codes.push(f.defaultMacro); } });
        vm.macroFilling = true;
        HIS.post('/api/his/emr/macro/resolve', { visitId: HIS.id(vm.visitId), macroCodes: codes })
          .then(function (map) {
            let n = 0;
            fields.forEach(function (f) {
              const v = vm.pickMacroValue(map, f);
              if (v != null && String(v).length) { vm.structForm[f.fieldKey] = v; n++; }
            });
            HIS.notifySuccess('已自动填充 ' + n + ' 个字段');
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.macroFilling = false; });
      },
      /* ===== 质控评分 ===== */
      refreshQuality(quiet) {
        const vm = this;
        if (!vm.current || !vm.current.id) { return Promise.resolve(); }
        if (!quiet) { vm.qualityLoading = true; }
        return HIS.post('/api/his/emr/quality/evaluate/' + HIS.idParam(vm.current.id))
          .then(function (data) { vm.quality = data || null; })
          .catch(function (e) { if (!quiet) { HIS.notifyError(e); } })
          .finally(function () { vm.qualityLoading = false; });
      },
      openQuality() {
        const vm = this;
        if (!vm.current || !vm.current.id) { ElementPlus.ElMessage.warning('请先保存草稿后再进行质控评分'); return; }
        vm.qualityVisible = true;
        vm.refreshQuality(false);
      },
      /* ===== 打印: 编辑器当前文书 / 列表项快捷打印(同一渲染管线) ===== */
      printRecord() {
        /* 富文书打印走本地直渲染(已是格式化 HTML) */
        if (this.editorMode === 'rich') { this.printRichLocal(); return; }
        this.printRow(this.current, this.title);
      },
      printRow(row, title) {
        const vm = this;
        if (!row || !row.id) { ElementPlus.ElMessage.warning('请先保存草稿后再打印'); return; }
        /* 富文书列表快捷打印: content 已是格式化 HTML, 本地直渲染 */
        const c = typeof row.content === 'string' ? row.content.trim() : '';
        if (c.charAt(0) === '<') {
          vm.printRichHtml(row.content, title || row.title || '病历打印', row.recordType);
          return;
        }
        vm.printing = true;
        HIS.get('/api/his/inp/print/render/emr?recordId=' + HIS.idParam(row.id))
          .then(function (html) {
            if (!html) { ElementPlus.ElMessage.warning('打印模板暂无内容'); return; }
            const docTitle = title || row.title || '病历打印';
            if (typeof HIS.printHtmlFrame === 'function') { HIS.printHtmlFrame(html, docTitle); }
            else if (typeof HIS.openPrintWindow === 'function') { HIS.openPrintWindow(docTitle, html); }
            else { ElementPlus.ElMessage.warning('打印组件不可用'); }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.printing = false; });
      },
      /* ===== Phase D 互操作导出(FHIR R4 / WS-T500 CDA) ===== */
      /* 导出端点返回原始文档体(FHIR 为 application/fhir+json), 不能用 HIS.download(会把 json 当错误信封),
       * 故携 Bearer 头 fetch 取文本后本地 Blob 下载 */
      downloadExport(fmt) {
        const vm = this;
        if (!vm.current || !vm.current.id) { ElementPlus.ElMessage.warning('请先保存草稿后再导出'); return; }
        if (vm.exporting) { return; }
        vm.exporting = true;
        const url = '/api/emr/export/1/' + HIS.idParam(vm.current.id) + '?format=' + encodeURIComponent(fmt);
        const headers = {};
        const token = HIS.getToken();
        if (token) { headers['Authorization'] = 'Bearer ' + token; }
        fetch(url, { headers: headers }).then(function (resp) {
          if (!resp.ok) { throw new Error('导出失败(HTTP ' + resp.status + ')'); }
          return resp.text();
        }).then(function (text) {
          const isCda = fmt === 'cda';
          const blob = new Blob([text], { type: (isCda ? 'application/xml' : 'application/json') + ';charset=utf-8' });
          const u = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = u; a.download = 'emr_ipd_' + HIS.idParam(vm.current.id) + '.' + (isCda ? 'xml' : 'json');
          document.body.appendChild(a); a.click();
          setTimeout(function () { URL.revokeObjectURL(u); if (a.parentNode) { a.parentNode.removeChild(a); } }, 1000);
          HIS.notifySuccess('已导出 ' + (isCda ? 'WS/T 500 CDA' : 'FHIR R4 Bundle') + ' 文档');
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      /* ===== Phase D 病历可靠电子签名(SM2) ===== */
      stageText(s) {
        const m = { author: '书写医师', resident: '住院医师', attending: '主治医师', director: '主任医师', doctor: '接诊医师' };
        return m[s] || s || '-';
      },
      openSigDialog() {
        const vm = this;
        if (!vm.current || !vm.current.id) { ElementPlus.ElMessage.warning('请先保存草稿后再签名'); return; }
        vm.sigDlgVisible = true;
        vm.verifyResult = null;
        vm.loadSigChain();
      },
      loadSigChain() {
        const vm = this;
        if (!vm.current || !vm.current.id) { vm.sigChain = []; return Promise.resolve(); }
        vm.sigLoading = true;
        return HIS.get('/api/emr/sign/chain/1/' + HIS.idParam(vm.current.id))
          .then(function (d) { vm.sigChain = d || []; })
          .catch(function () { vm.sigChain = []; })
          .finally(function () { vm.sigLoading = false; });
      },
      /* SM2 签名: 先静默保存草稿(确保签的是当前编辑内容), 再经签名板捕获, 最后落 his_emr_signature */
      doSm2Sign() {
        const vm = this;
        if (!vm.current) { return; }
        if (!vm.sigStage) { ElementPlus.ElMessage.warning('请选择签名环节'); return; }
        if (!HIS.SignaturePad || typeof HIS.SignaturePad.open !== 'function') {
          ElementPlus.ElMessage.warning('电子签名组件未加载, 无法签名'); return;
        }
        if (vm.sigSigning) { return; }
        vm.saveDraft(true).then(function () {
          if (!vm.current || !vm.current.id) { throw new Error('保存后未取得病历ID'); }
          return HIS.SignaturePad.open({ actionType: 'emr_sm2_sign', refType: 'medical_record', refId: HIS.id(vm.current.id) });
        }).then(function (r) {
          vm.sigSigning = true;
          return HIS.post('/api/emr/sign/1/' + HIS.idParam(vm.current.id) + '?stage=' + encodeURIComponent(vm.sigStage),
            { signImg: r && r.signImgUrl });
        }).then(function () {
          HIS.notifySuccess('SM2 可靠电子签名完成');
          vm.verifyResult = null;
          return vm.loadSigChain();
        }).catch(function (e) {
          if (e === 'cancelled') { ElementPlus.ElMessage.info('已取消签名'); return; }
          if (e !== 'busy' && e !== 'no-record' && e !== 'no-title') { HIS.notifyError(e); }
        }).finally(function () { vm.sigSigning = false; });
      },
      doSm2Verify() {
        const vm = this;
        if (!vm.current || !vm.current.id) { return; }
        if (vm.sigVerifying) { return; }
        vm.sigVerifying = true;
        const url = '/api/emr/sign/verify/1/' + HIS.idParam(vm.current.id)
          + (vm.sigStage ? ('?stage=' + encodeURIComponent(vm.sigStage)) : '');
        HIS.get(url).then(function (d) { vm.verifyResult = d || null; })
          .catch(HIS.notifyError)
          .finally(function () { vm.sigVerifying = false; });
      },
      /* ===== 空白新建(向后兼容纯文本编辑) ===== */
      openCreate(type) {
        const vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        const t = Number(type) || 3;
        const draft = {
          id: null,
          inpVisitId: HIS.id(vm.visitId),
          recordType: t,
          title: (RECORD_TYPE[t] || '病历文书') + ' ' + today(),
          content: null,
          status: 1,
          recordTime: new Date().toISOString(),
          _isNew: true
        };
        vm.list.unshift(draft);
        vm.open(draft);
      },
      buildContent() {
        if (this.editorMode === 'rich') { return this.getRichHtml(); }
        if (this.isStruct) { return JSON.stringify(this.structForm); }
        return JSON.stringify(this.formContent);
      },
      /* 富文本内容: 优先编辑器实例实时取值, 回退缓存/wangEditor 未初始化的输入 */
      getRichHtml() {
        const vm = this;
        if (vm.editorInstance) {
          try { return vm.editorInstance.getHtml(); } catch (e) { /* 实例异常回退缓存 */ }
        }
        const el = vm.$refs.richEditable;
        if (el) { return el.innerHTML; }
        return vm.richContent || '';
      },
      savePayload() {
        const payload = { title: String(this.title || '').trim(), content: this.buildContent() };
        if (this.editorMode === 'rich') {
          /* 富文书: 仅标题+HTML(后端 sanitizeHtml 白名单净化), 不携带结构化扩展字段 */
          return payload;
        }
        if (this.isStruct) {
          payload.structureData = payload.content;
          if (this.roundLevel != null) { payload.roundLevel = this.roundLevel; }
          if (this.attendingDoctorId != null) { payload.attendingDoctorId = HIS.id(this.attendingDoctorId); }
        }
        return payload;
      },
      saveDraft(silent) {
        const vm = this;
        if (vm.saving) { return Promise.reject('busy'); }
        if (!vm.current) { return Promise.reject('no-record'); }
        const payload = vm.savePayload();
        if (!payload.title) { ElementPlus.ElMessage.warning('请填写文书标题'); return Promise.reject('no-title'); }
        vm.saving = true;
        const req = vm.current.id
          ? HIS.put('/api/his/inp/record/' + HIS.idParam(vm.current.id), payload)
          : HIS.post('/api/his/inp/record', Object.assign({
              inpVisitId: HIS.id(vm.visitId),
              recordType: Number(vm.current.recordType)
            }, payload));
        return req.then(function (saved) {
          if (!silent) { HIS.notifySuccess('草稿已保存'); }
          if (saved && saved.id) {
            vm.current = Object.assign({}, vm.current, saved, { _isNew: false });
            const idx = vm.list.findIndex(r => r._isNew && !r.id);
            if (idx >= 0) { vm.list.splice(idx, 1, vm.current); }
          }
          return vm.load().then(function () {
            /* 保存后静默刷新质控评分 */
            if (vm.current && vm.current.id) { return vm.refreshQuality(true); }
          });
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close' && e !== 'busy' && e !== 'no-title') { HIS.notifyError(e); }
          return Promise.reject(e);
        }).finally(function () { vm.saving = false; });
      },
      submit() {
        const vm = this;
        if (!vm.current) { return; }
        const doSubmit = function () {
          vm.saving = true;
          return HIS.put('/api/his/inp/record/' + HIS.idParam(vm.current.id) + '/submit')
            .then(function (saved) {
              HIS.notifySuccess(vm.submitSuccessMsg(saved));
              vm.load();
              vm.reloadCurrent();
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.saving = false; });
        };
        if (!vm.current.id || Number(vm.current.status) === 1) {
          vm.saveDraft(true).then(doSubmit).catch(function () {});
        } else {
          doSubmit();
        }
      },
      audit() {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认审核通过「' + (vm.current.title || '') + '」？审核后文书不可再修改。', '审核确认', {
          type: 'warning', confirmButtonText: '审核通过', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/record/' + HIS.idParam(vm.current.id) + '/audit');
        }).then(function () {
          HIS.notifySuccess('文书已审核');
          vm.load();
          vm.reloadCurrent();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* 提交成功提示(查房记录按三级签名链的推进位置区分文案) */
      submitSuccessMsg(saved) {
        const rec = saved || {};
        if (Number(rec.recordType) !== 4) { return '文书已提交, 待审核'; }
        if (rec.directorSignId != null) { return '主任签名完成, 文书已审核归档'; }
        if (rec.attendingSignId != null) { return '主治签名完成, 待主任医师签名'; }
        return '已提交, 待主治医师签名';
      },
      /* ===== T43 三级签名链 ===== */
      /* 当前用户职称(懒加载一次): HIS.getUser().staffId → /api/his/staff/{id}.titleCode */
      loadMyTitle() {
        const vm = this;
        if (vm.myTitleCode !== null) { return; }
        const u = HIS.getUser ? HIS.getUser() : null;
        const staffId = u && u.staffId != null ? u.staffId : null;
        if (!staffId) { vm.myTitleCode = ''; return; }
        HIS.get('/api/his/staff/' + HIS.idParam(staffId))
          .then(function (s) { vm.myTitleCode = s && s.titleCode != null ? String(s.titleCode) : ''; })
          .catch(function () { vm.myTitleCode = ''; });
      },
      /* 三级签名(主治/主任): 先经电子签名板留痕(round_sign), 成功后调 /sign 推进状态 */
      doSign(level) {
        const vm = this;
        if (!vm.current || !vm.current.id) { ElementPlus.ElMessage.warning('请先保存草稿'); return; }
        if (vm.signing) { return; }
        if (!HIS.SignaturePad || typeof HIS.SignaturePad.open !== 'function') {
          ElementPlus.ElMessage.warning('电子签名组件未加载, 无法签名');
          return;
        }
        const label = level === 'attending' ? '主治医师签名' : '主任医师签名';
        HIS.SignaturePad.open({ actionType: 'round_sign', refType: 'medical_record', refId: HIS.id(vm.current.id) })
          .then(function () {
            vm.signing = true;
            return HIS.post('/api/his/inp/record/' + HIS.idParam(vm.current.id)
              + '/sign?signLevel=' + encodeURIComponent(level));
          })
          .then(function (rec) {
            /* P5a-4: 签名响应附带质控结果(qcResult), 禁止/拦截级阻断签名时不下发成功提示 */
            var qc = rec && (rec.qcResult || (rec.data && rec.data.qcResult));
            if (qc && qc.forbidden && qc.forbidden.length) {
              ElementPlus.ElMessage.error('签名被禁止: '
                + qc.forbidden.map(function (d) { return d.ruleName || '质控规则'; }).join('、'));
              vm.load();
              vm.reloadCurrent();
              return;
            }
            if (qc && qc.blocks && qc.blocks.length) {
              ElementPlus.ElMessage.warning('存在 ' + qc.blocks.length + ' 项拦截级缺陷, 请整改后重新签名');
              ElementPlus.ElMessageBox.alert(
                qc.blocks.map(function (d) { return '· ' + (d.ruleName || '质控规则') + (d.defectDesc ? ('：' + d.defectDesc) : ''); }).join('；'),
                '质控拦截 · 签名被阻断', { confirmButtonText: '知道了' }).catch(function () {});
              vm.load();
              vm.reloadCurrent();
              return;
            }
            if (qc && qc.warnings && qc.warnings.length && ElementPlus.ElNotification) {
              ElementPlus.ElNotification({ title: '质控提醒', message: '存在 ' + qc.warnings.length + ' 项待整改缺陷', type: 'warning', duration: 5000 });
            }
            HIS.notifySuccess(label + '完成');
            vm.load();
            vm.reloadCurrent();
          })
          .catch(function (e) {
            if (e === 'cancelled') { ElementPlus.ElMessage.info('已取消签名'); return; }
            HIS.notifyError(e);
          })
          .finally(function () { vm.signing = false; });
      },
      /* ===== T43 版本历史 ===== */
      loadVersions(quiet) {
        const vm = this;
        if (!vm.current || !vm.current.id) { vm.versions = []; return Promise.resolve(); }
        vm.versionLoading = true;
        return HIS.get('/api/his/inp/record/' + HIS.idParam(vm.current.id) + '/versions')
          .then(function (list) { vm.versions = list || []; })
          .catch(function (e) { if (!quiet) { HIS.notifyError(e); } })
          .finally(function () { vm.versionLoading = false; });
      },
      openVersions() {
        const vm = this;
        if (!vm.current || !vm.current.id) { ElementPlus.ElMessage.warning('请先保存草稿后再查看版本历史'); return; }
        vm.versionSel = [];
        vm.versionVisible = true;
        vm.loadVersions(false);
      },
      /* 勾选版本(最多2个, 超出替换最早勾选) */
      toggleVersion(id) {
        const vm = this;
        const idx = vm.versionSel.findIndex(function (x) { return HIS.sameId(x, id); });
        if (idx >= 0) { vm.versionSel.splice(idx, 1); return; }
        if (vm.versionSel.length >= 2) { vm.versionSel.shift(); }
        vm.versionSel.push(id);
      },
      /* 版本快照是否已勾选(支持19位字符串ID) */
      isVersionSelected(id) {
        return (this.versionSel || []).some(function (x) { return HIS.sameId(x, id); });
      },
      versionOf(id) {
        return (this.versions || []).filter(function (v) { return HIS.sameId(v.id, id); })[0] || null;
      },
      /* 单版本快照预览 */
      previewVersion(v) {
        const vm = this;
        if (!v) { return; }
        vm.previewTitle = 'V' + v.versionNo + ' · ' + (v.operatorName || '-') + ' · ' + (v.operateTime || '-');
        vm.previewData = null;
        vm.previewVisible = true;
        vm.previewLoading = true;
        HIS.get('/api/his/inp/record/version/' + HIS.idParam(v.id))
          .then(function (d) { vm.previewData = d || null; })
          .catch(HIS.notifyError)
          .finally(function () { vm.previewLoading = false; });
      },
      /* 对比选中版本(版本号小者为基线 v1) */
      diffSelected() {
        const vm = this;
        if (vm.versionSel.length !== 2) { return; }
        let v1 = vm.versionSel[0];
        let v2 = vm.versionSel[1];
        const a = vm.versionOf(v1);
        const b = vm.versionOf(v2);
        if (a && b && Number(a.versionNo) > Number(b.versionNo)) {
          v1 = vm.versionSel[1];
          v2 = vm.versionSel[0];
        }
        vm.diffData = null;
        vm.diffVisible = true;
        vm.diffLoading = true;
        HIS.get('/api/his/inp/record/version/diff?v1=' + HIS.idParam(v1) + '&v2=' + HIS.idParam(v2))
          .then(function (d) { vm.diffData = d || null; })
          .catch(HIS.notifyError)
          .finally(function () { vm.diffLoading = false; });
      },
      operateTypeText(t) {
        const m = { save: '保存', submit: '提交', audit: '审核', sign: '签名' };
        return m[t] || (t || '-');
      },
      /* 对比行样式: 左列取 v1Text / 右列取 v2Text, 缺失侧置灰, 增绿/删红/改黄 */
      diffLineCls(d, side) {
        if (!d) { return ''; }
        const text = side === 'v1' ? d.v1Text : d.v2Text;
        if (text == null) { return 'is-empty'; }
        if (d.type === 'add') { return 'is-add'; }
        if (d.type === 'del') { return 'is-del'; }
        if (d.type === 'modify') { return 'is-modify'; }
        return '';
      },
      /* ===== 富文本编辑(wangEditor v5 / contenteditable 兜底) ===== */
      /* 初始化 wangEditor(仅一次): 容器就绪后 createEditor+createToolbar, 失败降级 editable */
      initRichEditor() {
        const vm = this;
        if (vm.richEngine !== 'wangeditor' || vm.editorInstance) { return; }
        const box = vm.$refs.richEditorBox;
        const bar = vm.$refs.richToolbar;
        if (!box || !bar) { return; }
        const wang = window.wangEditor;
        try {
          vm.editorInstance = wang.createEditor({
            selector: box,
            html: vm.richContent || '<p><br></p>',
            config: {
              placeholder: '请输入病历内容...',
              onChange: function (editor) { vm.richContent = editor.getHtml(); }
            }
          });
          vm.toolbarInstance = wang.createToolbar({
            editor: vm.editorInstance,
            selector: bar,
            config: { excludeKeys: ['uploadVideo', 'insertLink', 'codeBlock', 'code'] }
          });
          vm.applyRichReadOnly();
        } catch (e) {
          console.warn('wangEditor 初始化失败, 回退 contenteditable', e);
          vm.destroyRichEditor();
          vm.richEngine = 'editable';
        }
      },
      destroyRichEditor() {
        const vm = this;
        try { if (vm.toolbarInstance) { vm.toolbarInstance.destroy(); } } catch (e) { /* 已随DOM销毁 */ }
        try { if (vm.editorInstance) { vm.editorInstance.destroy(); } } catch (e) { /* 同上 */ }
        vm.toolbarInstance = null;
        vm.editorInstance = null;
      },
      /* 内容/只读态同步: 病历切换或模式切换后调用(实例未建则初始化, editable 引擎回填 innerHTML) */
      syncRichEditor() {
        const vm = this;
        if (vm.editorMode !== 'rich' || !vm.current) { return; }
        if (vm.richEngine === 'wangeditor') {
          if (!vm.editorInstance) { vm.initRichEditor(); return; }
          try { vm.editorInstance.setHtml(vm.richContent || '<p><br></p>'); } catch (e) { console.warn('富文本内容同步失败', e); }
          vm.applyRichReadOnly();
          return;
        }
        const el = vm.$refs.richEditable;
        if (el) { el.innerHTML = vm.richContent || ''; }
      },
      applyRichReadOnly() {
        const vm = this;
        if (vm.richEngine !== 'wangeditor' || !vm.editorInstance) { return; }
        try {
          if (vm.readonly) { vm.editorInstance.disable(); } else { vm.editorInstance.enable(); }
        } catch (e) { /* ignore */ }
      },
      /* contenteditable 迷你工具栏指令 */
      execMiniTool(t) {
        const vm = this;
        const el = vm.$refs.richEditable;
        if (el) { el.focus(); }
        try { document.execCommand(t.cmd, false, t.arg || null); } catch (e) { /* 部分指令不可用 */ }
        if (el) { vm.richContent = el.innerHTML; }
      },
      onEditableInput(e) { this.richContent = e.target.innerHTML; },
      /* 结构化输入 → 段落 HTML(切富文本时携带既有输入, 字段值转义防注入) */
      structToHtml() {
        const vm = this;
        const fields = vm.isStruct ? vm.structFields : vm.schema;
        const form = vm.isStruct ? vm.structForm : vm.formContent;
        const parts = [];
        (fields || []).forEach(function (f) {
          const key = f.fieldKey || f.key;
          const val = form ? form[key] : '';
          if (val != null && String(val).length) {
            parts.push('<p><strong>' + escapeHtml(f.label || key) + '：</strong>'
              + escapeHtml(String(val)).replace(/\r?\n/g, '<br>') + '</p>');
          }
        });
        return parts.length ? parts.join('') : '<p><br></p>';
      },
      structHasInput() {
        const form = this.isStruct ? this.structForm : this.formContent;
        if (!form) { return false; }
        return Object.keys(form).some(function (k) { return form[k] != null && String(form[k]).length; });
      },
      /* 富文本 → 纯文本(切回结构化时回填首字段) */
      htmlToText(html) {
        const div = document.createElement('div');
        div.innerHTML = html || '';
        return (div.innerText || div.textContent || '').trim();
      },
      /* 编辑模式切换: struct→rich 转换既有输入; rich→struct 二次确认后丢格式提纯文本 */
      switchEditorMode(mode) {
        const vm = this;
        if (mode === vm.editorMode || !vm.current) { return; }
        if (mode === 'rich') {
          vm.richContent = vm.structHasInput() ? vm.structToHtml() : (vm.richContent || '<p><br></p>');
          vm.editorMode = 'rich';
          vm.$nextTick(function () { vm.syncRichEditor(); });
          return;
        }
        ElementPlus.ElMessageBox.confirm(
          '切换为结构化模式将丢失富文本格式（内容转为纯文本），是否继续？', '模式切换',
          { type: 'warning', confirmButtonText: '继续切换', cancelButtonText: '取消' }
        ).then(function () {
          const text = vm.htmlToText(vm.getRichHtml());
          vm.editorMode = 'struct';
          if (!text) { return; }
          if (vm.isStruct) {
            const first = (vm.structFields || [])[0];
            if (first && first.fieldKey && !String(vm.structForm[first.fieldKey] || '').length) {
              vm.structForm[first.fieldKey] = text;
            }
          } else {
            const first = (vm.schema || [])[0];
            if (first && first.key && !String(vm.formContent[first.key] || '').length) {
              vm.formContent[first.key] = text;
            }
          }
        }).catch(function () { /* 取消则保持富文本 */ });
      },
      /* 富文本文书打印: 内容已是格式化 HTML, 本地直渲染(后端管线面向结构化 JSON 字段) */
      printRichLocal() {
        const vm = this;
        vm.printRichHtml(vm.getRichHtml(), vm.title || (vm.current && vm.current.title) || '病历打印',
          vm.current && vm.current.recordType);
      },
      printRichHtml(body, docTitle, recordType) {
        const vm = this;
        body = body || '';
        if (!String(body).replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim().length) {
          ElementPlus.ElMessage.warning('暂无可打印的病历内容');
          return;
        }
        const html = [
          '<!DOCTYPE html><html><head><meta charset="UTF-8">',
          '<title>' + escapeHtml(docTitle) + '</title>',
          '<style>body{font-family:"Microsoft YaHei",SimSun,sans-serif;margin:24px 32px;line-height:1.8;font-size:14px;color:#222;}',
          '.doc-head{text-align:center;border-bottom:2px solid #333;padding-bottom:10px;margin-bottom:16px;}',
          '.doc-head h1{font-size:18px;margin:0 0 6px;}',
          '.doc-meta{display:flex;justify-content:space-between;font-size:12px;color:#555;}',
          'table{border-collapse:collapse;}td,th{border:1px solid #333;padding:4px 8px;}',
          'img{max-width:100%;}blockquote{margin:8px 0;padding:6px 12px;border-left:3px solid #999;color:#444;background:#f7f7f7;}</style></head><body>',
          '<div class="doc-head"><h1>' + escapeHtml(docTitle) + '</h1>',
          '<div class="doc-meta"><span>' + escapeHtml(vm.typeText(recordType)) + '</span>',
          '<span>打印时间: ' + new Date().toLocaleString() + '</span></div></div>',
          body,
          '</body></html>'
        ].join('');
        if (typeof HIS.printHtmlFrame === 'function') { HIS.printHtmlFrame(html, docTitle); }
        else if (typeof HIS.openPrintWindow === 'function') { HIS.openPrintWindow(docTitle, html); }
        else { ElementPlus.ElMessage.warning('打印组件不可用'); }
      }
    },
    template: `
      <div class="iw-record-wrap">
        <aside class="iw-record-side">
          <div class="iw-record-side-head">
            <el-button size="small" type="primary" style="width:100%" @click="openTplPicker">+ 从模板新建</el-button>
            <el-dropdown trigger="click" @command="openCreate" style="width:100%;margin-top:6px">
              <el-button size="small" style="width:100%">空白新建</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="(label, key) in recordTypes" :key="key" :command="Number(key)">{{ label }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <div class="iw-dim" style="margin-top:6px">共 {{ list.length }} 份文书</div>
          </div>
          <el-scrollbar class="iw-record-side-body" v-loading="loadingSide">
            <template v-for="g in groupedRecords" :key="g.type">
              <div class="iw-record-grp-head">{{ g.label }}<span class="iw-count">{{ g.items.length }}</span></div>
              <div class="iw-record-item" v-for="r in g.items" :key="r.id || r.title"
                   :class="{'is-active': isActive(r)}" @click="open(r)">
                <div class="t">
                  <span class="iw-tag iw-tag--plain" v-if="r._isNew">未保存</span>
                  {{ r.title || g.label }}
                </div>
                <div class="m">
                  <span>{{ timeText(r.recordTime, true) }}</span>
                  <el-tag v-if="!r._isNew" size="small" :type="statusTag(r.status)" disable-transitions>{{ statusText(r.status) }}</el-tag>
                </div>
                <button type="button" class="iw-rop" v-if="r.id" title="打印此文书" @click.stop="printRow(r, r.title)" v-html="icons.printer"></button>
              </div>
            </template>
            <div v-if="!list.length" class="iw-empty-line">暂无病历文书, 点击「从模板新建」创建</div>
          </el-scrollbar>
        </aside>

        <section class="iw-record-main" v-loading="loading">
          <template v-if="current">
            <div class="iw-record-head">
              <span class="iw-tag" :class="readonly ? 'iw-tag--plain' : 'iw-tag--success'">{{ typeText(current.recordType) }}</span>
              <span class="iw-tag iw-tag--plain" v-if="isStruct">结构化模板</span>
              <span class="iw-tag iw-tag--plain" v-if="isRich">富文本</span>
              <el-input v-model="title" size="small" class="iw-grow" :disabled="readonly" placeholder="文书标题"></el-input>
              <el-button v-if="current.id" size="small" plain :type="qcButtonType" @click="openQuality">质控 {{ qualityScoreText }}</el-button>
              <el-tag v-if="current.id" size="small" :type="statusTag(current.status)" disable-transitions>{{ statusText(current.status) }}</el-tag>
              <el-tag v-else size="small" type="info" disable-transitions>未保存</el-tag>
            </div>
            <!-- T43 三级签名链状态条(仅查房记录, 已建档显示) -->
            <div v-if="isRoundRecord && current.id" class="iw-sign-chain">
              <span class="iw-sc-node" :class="roundSubmitted ? 'done' : ''">
                <span class="tag">{{ roundSubmitted ? '✓' : '…' }}</span>住院医师
                <span class="sub" v-if="roundSubmitted">{{ roundSubmitInfo ? roundSubmitInfo.operatorName + ' ' + timeText(roundSubmitInfo.operateTime, true) : '已提交' }}</span>
                <span class="sub" v-else>编写中</span>
              </span>
              <span class="iw-sc-arrow">→</span>
              <span class="iw-sc-node" :class="attendingSigned ? 'done' : (canSignAttending ? 'pending' : '')">
                <span class="tag">{{ attendingSigned ? '✓' : '…' }}</span>主治签
                <span class="sub" v-if="attendingSigned">{{ timeText(current.attendingSignTime) }}</span>
                <span class="sub" v-else-if="canSignAttending">待您签名</span>
                <span class="sub" v-else>待签</span>
              </span>
              <span class="iw-sc-arrow">→</span>
              <span class="iw-sc-node" :class="directorSigned ? 'done' : (canSignDirector ? 'pending' : '')">
                <span class="tag">{{ directorSigned ? '✓' : '…' }}</span>主任签
                <span class="sub" v-if="directorSigned">{{ timeText(current.directorSignTime) }}</span>
                <span class="sub" v-else-if="canSignDirector">待您签名</span>
                <span class="sub" v-else>待签</span>
              </span>
              <span class="iw-sc-spacer"></span>
              <el-button v-if="canSignAttending" size="small" type="primary" :loading="signing" @click="doSign('attending')">主治签名</el-button>
              <el-button v-if="canSignDirector" size="small" type="primary" :loading="signing" @click="doSign('director')">主任签名</el-button>
            </div>
            <div v-if="deadlineInfo" class="iw-deadline-banner" :class="deadlineInfo.cls">⏰ {{ deadlineInfo.text }}</div>
            <div class="iw-record-form">
              <!-- 编辑模式切换(草稿可切; 富文书加载后自动进入 rich) -->
              <div class="iw-rich-bar">
                <el-radio-group :model-value="editorMode" size="small" :disabled="readonly" @change="switchEditorMode">
                  <el-radio-button label="struct">结构化</el-radio-button>
                  <el-radio-button label="rich">富文本</el-radio-button>
                </el-radio-group>
                <span class="iw-dim" v-if="isRich && richEngine === 'editable'">富文本编辑器未加载, 已启用简易编辑模式</span>
              </div>
              <!-- 富文本编辑区(wangEditor v5 / contenteditable 兜底) -->
              <div class="iw-rich-wrap" v-show="isRich">
                <template v-if="richEngine === 'wangeditor'">
                  <div ref="richToolbar" class="iw-wang-toolbar"></div>
                  <div ref="richEditorBox" class="iw-wang-editor"></div>
                </template>
                <template v-else>
                  <div class="iw-mini-tools" v-if="!readonly">
                    <button type="button" v-for="t in miniTools" :key="t.cmd + (t.arg || '')"
                            :title="t.arg || t.label" :style="t.sty || ''" @click="execMiniTool(t)">{{ t.label }}</button>
                  </div>
                  <div ref="richEditable" class="iw-editable" data-ph="请输入病历内容..."
                       :contenteditable="readonly ? 'false' : 'true'" @input="onEditableInput"></div>
                </template>
              </div>
              <!-- 结构化模板表单 -->
              <template v-if="isStruct && !isRich">
                <div style="margin-bottom:10px;display:flex;align-items:center;gap:8px">
                  <el-button size="small" :disabled="readonly" :loading="macroFilling" @click="resolveAllMacros">一键填充所有宏</el-button>
                  <span class="iw-dim">结构化模板字段, 红色星号为必填项</span>
                </div>
                <template v-for="f in structFields">
                  <div class="iw-rf-emr" :key="f.fieldKey">
                    <emr-field :field="f" :model="structForm" :readonly="readonly" class="iw-grow"></emr-field>
                    <el-button v-if="hasMacro(f) && !readonly" size="small" @click="resolveMacro(f)">自动填充</el-button>
                  </div>
                </template>
                <div class="iw-rf-field" v-if="currentCategory === 4">
                  <span class="lb">查房级别</span>
                  <el-select v-model="roundLevel" :disabled="readonly" clearable style="width:150px" placeholder="选择查房级别">
                    <el-option v-for="r in roundLevels" :key="r.value" :label="r.label" :value="r.value"></el-option>
                  </el-select>
                  <span class="lb" style="margin-left:8px">上级医师</span>
                  <el-select v-model="attendingDoctorId" :disabled="readonly" filterable clearable style="width:200px" placeholder="选择上级医师">
                    <el-option v-for="d in doctors" :key="d.id" :label="doctorLabel(d)" :value="d.id"></el-option>
                  </el-select>
                </div>
              </template>
              <!-- 纯文本结构表单(无模板病历, 向后兼容) -->
              <div class="iw-rf-field" v-for="f in schema" :key="f.key" v-if="!isStruct && !isRich">
                <span class="lb">{{ f.label }}</span>
                <el-input v-if="f.type === 'textarea'" v-model="formContent[f.key]" type="textarea" :rows="f.rows || 3"
                          :disabled="readonly" class="iw-grow"></el-input>
                <el-input v-else v-model="formContent[f.key]" size="small" :disabled="readonly"
                          :placeholder="f.placeholder || ''" style="width:220px"></el-input>
              </div>
            </div>
            <div class="iw-record-actions">
              <span class="iw-dim">
                创建 {{ timeText(current.recordTime) }}
                <template v-if="current.deadlineTime"> | 截止 {{ timeText(current.deadlineTime) }}</template>
                <template v-if="Number(current.status) === 2"> | 已提交待审核, 医生不可再编辑</template>
                <template v-if="Number(current.status) === 3"> | 已审核归档</template>
              </span>
              <span class="iw-toolbar-right">
                <el-dropdown size="small" trigger="click" :disabled="!current.id || exporting" @command="downloadExport" style="margin-right:8px">
                  <el-button size="small" :loading="exporting"><span class="iw-bicon" v-html="icons.send"></span>互操作导出</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="fhir">FHIR R4 Bundle(Document)</el-dropdown-item>
                      <el-dropdown-item command="cda">WS/T 500 CDA 文档</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button size="small" :disabled="!current.id" @click="openSigDialog">电子签名(SM2)</el-button>
                <el-button size="small" :loading="printing" @click="printRecord"><span class="iw-bicon" v-html="icons.printer"></span>打印</el-button>
                <el-button size="small" :disabled="!current.id" @click="openVersions"><span class="iw-bicon" v-html="icons.clock"></span>版本历史</el-button>
                <el-button size="small" :disabled="readonly" :loading="saving" @click="saveDraft(false)"><span class="iw-bicon" v-html="icons.save"></span>保存草稿</el-button>
                <el-button size="small" type="primary" :disabled="readonly" :loading="saving" @click="submit"><span class="iw-bicon" v-html="icons.send"></span>提交</el-button>
                <el-button size="small" type="success" v-if="Number(current.status) === 2" :loading="saving" @click="audit"><span class="iw-bicon" v-html="icons.check"></span>审核通过</el-button>
              </span>
            </div>
          </template>
          <div v-else class="iw-record-placeholder">
            <div>请选择左侧病历文书</div>
            <div class="iw-dim">「从模板新建」按结构化模板建档并即时质控; 「空白新建」沿用纯文本编辑; 提交后经审核归档</div>
          </div>
        </section>

        <!-- 模板选择对话框 -->
        <el-dialog v-model="tplVisible" title="选择病历模板" width="620px" append-to-body>
          <div v-loading="tplLoading" style="max-height:440px;overflow:auto;padding-right:4px">
            <template v-for="g in tplGroups" :key="g.category">
              <div class="iw-tpl-group-head">{{ g.label }}</div>
              <div class="iw-tpl-card" v-for="t in g.items" :key="t.id" @click="applyTemplate(t)">
                <div class="n">{{ t.templateName }}</div>
                <div class="iw-dim" style="font-size:12px;margin-top:2px">
                  <span>{{ t.templateCode }}</span>
                  <span v-if="t.recordType"> · 文书类型 {{ typeText(t.recordType) }}</span>
                  <span> · {{ t.deptId ? '科室模板' : '全院通用' }}</span>
                </div>
              </div>
            </template>
            <div v-if="!tplGroups.length && !tplLoading" class="iw-empty-line" style="padding:24px 0">暂无可用模板</div>
          </div>
        </el-dialog>

        <!-- 质控评分抽屉 -->
        <el-drawer v-model="qualityVisible" title="病历质控评分" size="430px" append-to-body>
          <div v-loading="qualityLoading" style="padding:0 4px">
            <div style="text-align:center;padding:4px 0 14px;border-bottom:1px solid var(--yb-border)">
              <div class="iw-qc-score" :class="scoreCls">{{ qualityScoreText }}</div>
              <div class="iw-dim" style="margin-top:4px">{{ qualityHint }}</div>
              <el-button size="small" style="margin-top:10px" @click="refreshQuality(false)">重新评分</el-button>
            </div>
            <div v-for="(d, i) in qcDetailList" :key="i" class="iw-qc-row" :class="d.passed ? 'passed' : 'failed'">
              <span class="icon">{{ d.passed ? '✓' : '✗' }}</span>
              <div style="flex:1;min-width:0">
                <div style="font-weight:600">{{ d.ruleName }}</div>
                <div v-if="!d.passed && d.message" class="iw-dim" style="margin-top:2px">{{ d.message }}</div>
              </div>
              <span v-if="!d.passed" class="iw-dim" style="flex:none">{{ severityText(d.severity, d.deductScore) }}</span>
            </div>
            <div v-if="!qcDetailList.length && !qualityLoading" class="iw-empty-line" style="padding:20px 0">暂无质控明细, 点击「重新评分」</div>
          </div>
        </el-drawer>

        <!-- 版本历史抽屉(T43): 勾选两个版本后行级对比 -->
        <el-drawer v-model="versionVisible" title="病历版本历史" size="460px" append-to-body>
          <div v-loading="versionLoading">
            <div class="iw-vrow" v-for="v in versions" :key="v.id">
              <el-checkbox :model-value="isVersionSelected(v.id)" @change="toggleVersion(v.id)"></el-checkbox>
              <div class="body">
                <div class="n">V{{ v.versionNo }}<span class="iw-vtype">{{ operateTypeText(v.operateType) }}</span></div>
                <div class="m">{{ v.operatorName || '-' }} · {{ v.operateTime || '-' }}</div>
              </div>
              <el-button size="small" text type="primary" @click="previewVersion(v)">查看</el-button>
            </div>
            <div v-if="!versions.length && !versionLoading" class="iw-empty-line" style="padding:24px 0">暂无版本快照(保存/提交/审核后自动留存)</div>
            <div class="iw-vfoot">
              <span class="iw-dim">已选 {{ versionSel.length }}/2 个版本</span>
              <span style="flex:1"></span>
              <el-button size="small" type="primary" :disabled="versionSel.length !== 2" @click="diffSelected">对比选中版本</el-button>
            </div>
          </div>
        </el-drawer>

        <!-- 单版本快照预览(T43) -->
        <el-dialog v-model="previewVisible" :title="'版本快照预览 · ' + previewTitle" width="640px" append-to-body>
          <div v-loading="previewLoading" style="min-height:80px">
            <pre class="iw-vpre" v-if="previewText">{{ previewText }}</pre>
            <div v-else-if="!previewLoading" class="iw-empty-line" style="padding:20px 0">该版本无快照内容</div>
          </div>
        </el-dialog>

        <!-- 版本内容对比(T43): 左旧右新, 增行绿/删行红/改行黄/缺失侧置灰 -->
        <el-dialog v-model="diffVisible" title="版本内容对比" width="880px" append-to-body>
          <div v-loading="diffLoading" style="min-height:120px">
            <template v-if="diffData">
              <div class="iw-diff-sum">
                <span>差异共 {{ (diffData.diffs || []).length }} 行</span>
                <span>新增 <b class="add">+{{ diffData.summary.add }}</b></span>
                <span>删除 <b class="del">-{{ diffData.summary.del }}</b></span>
                <span>修改 <b class="modify">~{{ diffData.summary.modify }}</b></span>
                <span>未变 {{ diffData.summary.same }}</span>
              </div>
              <div class="iw-diff-heads">
                <div class="iw-diff-col-head">V{{ diffData.v1.versionNo }} · {{ diffData.v1.operatorName }} · {{ diffData.v1.operateTime }}</div>
                <div class="iw-diff-col-head">V{{ diffData.v2.versionNo }} · {{ diffData.v2.operatorName }} · {{ diffData.v2.operateTime }}</div>
              </div>
              <div class="iw-diff-wrap">
                <template v-for="(d, i) in diffData.diffs" :key="'d' + i">
                  <div class="iw-diff-line" :class="diffLineCls(d, 'v1')">
                    <span class="ln">{{ d.line }}</span>
                    <span class="tx">{{ d.v1Text == null ? '' : d.v1Text }}</span>
                  </div>
                  <div class="iw-diff-line" :class="diffLineCls(d, 'v2')">
                    <span class="ln">{{ d.line }}</span>
                    <span class="tx">{{ d.v2Text == null ? '' : d.v2Text }}</span>
                  </div>
                </template>
              </div>
            </template>
            <div v-else-if="!diffLoading" class="iw-empty-line" style="padding:20px 0">暂无可对比的内容</div>
          </div>
        </el-dialog>

        <!-- Phase D 病历可靠电子签名(SM2): 环节选择 + 签名/验签 + 有效签名链 -->
        <el-dialog v-model="sigDlgVisible" title="病历可靠电子签名(SM2)" width="680px" append-to-body>
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;flex-wrap:wrap">
            <span style="font-size:13px">签名环节</span>
            <el-select v-model="sigStage" size="small" style="width:140px">
              <el-option v-for="s in sigStages" :key="s.value" :label="s.label" :value="s.value"></el-option>
            </el-select>
            <el-button size="small" type="primary" :loading="sigSigning" @click="doSm2Sign">SM2 签名</el-button>
            <el-button size="small" :loading="sigVerifying" @click="doSm2Verify">验签</el-button>
            <el-button size="small" @click="loadSigChain">刷新链</el-button>
          </div>
          <div v-if="verifyResult" style="padding:8px 12px;margin-bottom:10px;border:1px solid var(--yb-border);border-radius:var(--yb-r-sm);background:var(--yb-surface);font-size:13px">
            <span>{{ stageText(verifyResult.stage) }} · {{ verifyResult.signerName || '-' }} · </span>
            <el-tag size="small" :type="verifyResult.passed ? 'success' : 'danger'">{{ verifyResult.passed ? '验签通过' : '验签失败' }}</el-tag>
            <span style="margin-left:8px">签名有效 {{ verifyResult.sigValid ? '✓' : '✗' }} / 摘要一致 {{ verifyResult.digestMatch ? '✓' : '✗' }}</span>
            <span style="margin-left:8px">证书 {{ verifyResult.certSn || '-' }} · {{ timeText(verifyResult.signTime) }}</span>
          </div>
          <div class="iw-tpl-group-head">有效签名链</div>
          <el-table :data="sigChain" size="small" v-loading="sigLoading" empty-text="暂无签名" max-height="280">
            <el-table-column label="环节" width="90"><template #default="s">{{ stageText(s.row.stage) }}</template></el-table-column>
            <el-table-column prop="signerName" label="签名人" width="100"></el-table-column>
            <el-table-column prop="provider" label="算法" width="70"></el-table-column>
            <el-table-column prop="certSn" label="证书号" min-width="120"></el-table-column>
            <el-table-column label="签名时间" width="130"><template #default="s">{{ timeText(s.row.signTime) }}</template></el-table-column>
          </el-table>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpRecordPanel = InpRecordPanel;
})();
