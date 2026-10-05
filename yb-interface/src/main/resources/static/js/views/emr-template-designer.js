/* 病历模板设计器(结构化): HIS.views['emr-template-designer'] —— 三栏式 Tiptap 模板设计页。
 * 顶栏: 层级过滤(0全院/1科室/2个人) + 模板选择 + 视图模式(设计/编辑/预览) + 新建/从数据集创建/保存/另存为/删除/传播更新/打印预览。
 * 左栏: 数据集元素树(章节→小节→数据元), 数据元可拖拽(拖到编辑器指定位置)或双击(插入光标处)插入 emrField 节点。
 * 中栏: EmrToolbar + HIS.EmrEditor Tiptap 编辑器(设计描边/编辑/只读预览)。
 * 右栏: 模板信息 + 选中节点属性(EmrField/EmrSection/EmrMacro/EmrConditionalBlock/EmrDrawing/EmrFragment 分派) + 母板锁定章节清单 + 打印脚本(print_script)。
 * 后端契约(/api/his/emr/template, EmrTemplateController/EmrTemplateService):
 *   GET  /listByScope?scopeLevel=&deptId=&staffId=  按层级合并视图(个人覆盖科室覆盖全院; 缺省全三级; 只返回启用模板)
 *   GET  /{id}            模板详情(含 document=Tiptap JSON 字符串 / printScript / lockedSections / parentTemplateId)
 *   POST /                新建 {templateCode, templateName, ownerScope(global/dept/personal), scopeLevel, document, printScript, lockedSections}
 *   PUT  /{id}            更新(非 null 字段生效, 版本自增; document 须为 type=doc JSON 字符串)
 *   DELETE /{id}          逻辑删除(按归属层级鉴权: 个人=本人/科室=同科室/全院=牵头机构)
 *   POST /propagate/{id}  母板锁定章节向下传播(个人模板拒绝; 子模板>10 转后台异步) → {children, updated, async, message}
 *   POST /createFromDataset {datasetId, name, scopeLevel} 由数据集生成模板(章节/小节/数据元 → emrSection/emrField)
 *   GET  /api/his/emr/dataset/list?page=&size=   数据集分页(左栏下拉)
 *   GET  /api/his/emr/dataset/{id}               数据集结构 {dataset, chapters:[{...,elements,sections:[{...,elements}]}]}
 * 口径归一(编辑器 <-> 后端):
 *   1) 章节节点: 后端 propagate/batchReplaceSection 以 emrSection.attrs.key 命中章节, 编辑器 schema 用 sectionKey;
 *      加载时 key→sectionKey(空时补), 保存时双写 key=sectionKey。
 *   2) 值类型: 后端 mapValueType 产出 'multiSelect'/datetime/textarea, 编辑器 VALUE_TYPES 为 multiselect 等,
 *      插入/加载统一经 mapValueType 归一。
 * 注册: HIS.views['emr-template-designer'](与动态菜单 comp 同值; 须在 app.js 之前加载, index.html 引入)。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 常量 ================= */
  var SCOPE_LEVELS = [
    { v: 0, l: '全院', t: 'warning' },
    { v: 1, l: '科室', t: 'primary' },
    { v: 2, l: '个人', t: 'success' }
  ];
  var LEVEL_FILTERS = [{ v: -1, l: '全部层级' }, { v: 0, l: '全院' }, { v: 1, l: '科室' }, { v: 2, l: '个人' }];
  var OWNER_BY_LEVEL = { 0: 'global', 1: 'dept', 2: 'personal' };
  var VALUE_TYPES = ['text', 'textarea', 'number', 'date', 'select', 'multiselect', 'checkbox', 'dict', 'vitals'];
  var VT_LABEL = { text: '单行文本', textarea: '长文本/快捷短语', number: '数字', date: '日期', select: '单选', multiselect: '多选', checkbox: '复选', dict: '字典', vitals: '生命体征' };
  var EDIT_MODES = [{ v: 'form', l: '表单' }, { v: 'free', l: '自由' }, { v: 'mixed', l: '混合' }];
  var EDITOR_MODES = [{ v: 'design', l: '设计' }, { v: 'edit', l: '编辑' }, { v: 'preview', l: '预览' }];
  var KIND_LABEL = { emrField: '数据元', emrSection: '章节', emrMacro: '宏变量', emrConditionalBlock: '条件块', emrDrawing: '医学图示', emrFragment: '片段引用' };
  /* 条件块运算符(优先取扩展层同源 OPERATORS, 扩展未就绪时回落内置清单) */
  var COND_OPERATORS = (HIS.EmrExtensions && HIS.EmrExtensions.OPERATORS) ||
    [{ v: 'eq', l: '等于' }, { v: 'ne', l: '不等于' }, { v: 'contains', l: '包含' }, { v: 'empty', l: '为空' }, { v: 'notEmpty', l: '不为空' }];

  /* ================= 工具 ================= */
  function text(v) { return v == null ? '' : String(v); }
  /* 数据元字段类型 → 编辑器 valueType 归一: 兼容后端 mapValueType 的 multiSelect/datetime。 */
  function mapValueType(t) {
    var s = text(t).toLowerCase();
    if (s === 'multiselect') { return 'multiselect'; }
    if (s === 'datetime') { return 'date'; }
    return VALUE_TYPES.indexOf(s) >= 0 ? s : 'text';
  }
  function safeParse(s) { try { var v = typeof s === 'string' ? JSON.parse(s) : s; return v || null; } catch (e) { return null; } }
  function defaultPrintConfig() {
    return {
      paperSize: 'A4', orientation: 'portrait',
      margins: { top: 18, right: 16, bottom: 18, left: 16 },
      header: { enabled: false, content: '' }, footer: { enabled: false, content: '' },
      showPageNumber: true
    };
  }
  function normalizePrintConfig(value) {
    var input = safeParse(value) || {};
    var base = defaultPrintConfig();
    var orientation = input.orientation === 'landscape' ? 'landscape' : 'portrait';
    var margins = input.margins || {};
    function margin(name) {
      var n = Number(margins[name]);
      return isFinite(n) ? Math.max(0, Math.min(50, Math.round(n))) : base.margins[name];
    }
    return {
      paperSize: 'A4', orientation: orientation,
      margins: { top: margin('top'), right: margin('right'), bottom: margin('bottom'), left: margin('left') },
      header: { enabled: !!(input.header && input.header.enabled), content: text(input.header && input.header.content) },
      footer: { enabled: !!(input.footer && input.footer.enabled), content: text(input.footer && input.footer.content) },
      showPageNumber: input.showPageNumber !== false
    };
  }
  function parseFieldDefs(value) {
    var parsed = safeParse(value);
    if (Array.isArray(parsed)) { return parsed; }
    return parsed && Array.isArray(parsed.fields) ? parsed.fields : [];
  }
  function fieldAttrs(def) {
    def = def || {};
    return {
      fieldKey: text(def.fieldKey || def.fieldCode || def.key),
      fieldName: text(def.fieldName || def.label || def.name),
      value: def.value == null ? (def.defaultValue == null ? null : def.defaultValue) : def.value,
      valueType: mapValueType(def.valueType || def.type || def.fieldType),
      dictSource: def.dictSource || def.dictRef || null,
      options: def.options == null ? null : def.options,
      placeholder: text(def.placeholder), unit: text(def.unit), defaultMacro: text(def.defaultMacro),
      required: def.required === true || Number(def.required) === 1,
      readonly: def.readonly === true || Number(def.readonly) === 1,
      noCopy: def.noCopy === true || Number(def.noCopy) === 1
    };
  }
  /* 历史字段画布模板转为章节+字段文档，仅在内存中转换，首次保存时再持久化。 */
  function fieldsToDocument(value) {
    var defs = parseFieldDefs(value);
    if (!defs.length) { return null; }
    var doc = { type: 'doc', content: [] };
    var section = null;
    function target() { return section ? section.content : doc.content; }
    defs.forEach(function (def, idx) {
      var type = text(def && (def.type || def.valueType || def.fieldType)).toLowerCase();
      if (type === 'section') {
        var key = text(def.fieldKey || def.fieldCode || def.key) || ('section_' + idx);
        section = {
          type: 'emrSection',
          attrs: { sectionKey: key, key: key, title: text(def.label || def.fieldName || def.name) || '未命名章节', editMode: text(def.editMode) || 'mixed', collapsible: def.collapsible !== false, printHidden: !!def.printHidden, locked: !!def.locked, collapsed: false },
          content: [{ type: 'paragraph' }]
        };
        doc.content.push(section);
        return;
      }
      var attrs = fieldAttrs(def);
      if (!attrs.fieldKey) { attrs.fieldKey = 'field_' + idx; }
      var paragraph = { type: 'paragraph', content: [{ type: 'emrField', attrs: attrs }] };
      target().push(paragraph);
    });
    if (!doc.content.length) { doc.content.push({ type: 'paragraph' }); }
    return doc;
  }
  /* 文档回抽 fields，按 fieldKey 合并历史定义，保留未知字典/宏/质控属性。 */
  function documentToFields(json, legacy) {
    var old = {};
    (legacy || []).forEach(function (f) {
      var k = text(f && (f.fieldKey || f.fieldCode || f.key));
      if (k) { old[k] = Object.assign({}, f); }
    });
    var out = [];
    walkDoc(json, function (n) {
      var a = n.attrs || {};
      if (n.type === 'emrSection') {
        var sk = text(a.sectionKey || a.key);
        if (!sk) { return; }
        out.push(Object.assign({}, old[sk] || {}, { fieldKey: sk, label: text(a.title) || sk, type: 'section', required: false, editMode: a.editMode || 'mixed', collapsible: a.collapsible !== false, printHidden: !!a.printHidden, locked: !!a.locked }));
      } else if (n.type === 'emrField') {
        var fk = text(a.fieldKey);
        if (!fk) { return; }
        var f = Object.assign({}, old[fk] || {});
        f.fieldKey = fk; f.label = text(a.fieldName) || fk; f.type = mapValueType(a.valueType);
        f.required = !!a.required; f.readonly = !!a.readonly; f.noCopy = !!a.noCopy;
        f.dictSource = a.dictSource || null; f.options = a.options == null ? null : a.options;
        f.placeholder = text(a.placeholder); f.unit = text(a.unit); f.defaultMacro = text(a.defaultMacro);
        out.push(f);
      }
    });
    return out;
  }
  /* lockedSections 解析: JSON 数组字符串 或 逗号分隔兜底 */
  function parseKeys(s) {
    var v = safeParse(s);
    if (Array.isArray(v)) { return v.map(function (x) { return text(x); }).filter(Boolean); }
    return text(s).split(/[,，;；]/).map(function (x) { return x.trim(); }).filter(Boolean);
  }
  function walkDoc(node, fn) {
    if (!node) { return; }
    fn(node);
    (node.content || []).forEach(function (c) { walkDoc(c, fn); });
  }
  /* 加载归一: emrSection.attrs.key → sectionKey(createFromDataset 后端以 key 落 attrs); valueType 归一 */
  function normalizeDocIn(json) {
    walkDoc(json, function (n) {
      var a = n.attrs || {};
      if (n.type === 'emrSection' && !text(a.sectionKey) && text(a.key)) { a.sectionKey = text(a.key); }
      if (n.type === 'emrField' && a.valueType) { a.valueType = mapValueType(a.valueType); }
    });
    return json;
  }
  /* 保存归一: emrSection 双写 key=sectionKey(后端 propagate/batchReplaceSection 以 attrs.key 命中章节) */
  function normalizeDocOut(json) {
    walkDoc(json, function (n) {
      var a = n.attrs || {};
      if (n.type === 'emrSection' && text(a.sectionKey)) { a.key = text(a.sectionKey); }
    });
    return json;
  }
  /* 母板锁定清单 → 文档章节 locked 标记(加载时同步视觉高亮: .emr-section--locked) */
  function applyLockedFlags(json, keys) {
    if (!json || !keys || !keys.length) { return json; }
    walkDoc(json, function (n) {
      var a = n.attrs || {};
      if (n.type === 'emrSection') {
        var k = text(a.sectionKey) || text(a.key);
        if (k && keys.indexOf(k) >= 0) { a.locked = true; }
      }
    });
    return json;
  }
  /* 数据集数据元 → 插入载荷(拖拽与双击共用) */
  function elToPayload(el) {
    el = el || {};
    return {
      fieldKey: text(el.fieldKey), fieldName: text(el.fieldName),
      valueType: mapValueType(el.fieldType),
      dictSource: text(el.dictSource) || null,
      required: Number(el.required) === 1, readonly: Number(el.readonly) === 1, noCopy: Number(el.noCopy) === 1
    };
  }
  function genCode(prefix) { return text(prefix) + '_' + Date.now(); }
  /* 仅接受 <svg> 根字符串, 并做基础白名单清洗: 移除 script/foreignObject、事件属性与 javascript 伪协议 */
  function sanitizeSvg(v) {
    var s = typeof v === 'string' ? v : '';
    s = s.replace(/<!--[\s\S]*?-->/g, '');
    s = s.replace(/<script[\s\S]*?<\/script>/gi, '');
    s = s.replace(/<script\b[^>]*\/?>/gi, '');
    s = s.replace(/<foreignObject[\s\S]*?<\/foreignObject>/gi, '');
    s = s.replace(/<foreignObject\b[^>]*\/?>/gi, '');
    s = s.replace(/\son[a-z0-9_-]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '');
    s = s.replace(/(?:href|xlink:href|src)\s*=\s*("javascript:[^"]*"|'javascript:[^']*'|javascript:[^\s>]+)/gi, '');
    return s;
  }
  function validSvg(v) {
    var s = typeof v === 'string' ? v : '';
    s = s.replace(/^\s+/, '');
    if (s.indexOf('<svg') !== 0) { return ''; }
    return sanitizeSvg(s);
  }

  /* 组件私有样式: 三栏布局 + 数据元树 + 属性面板(不动全局 his.css) */
  (function injectCss() {
    var st = document.getElementById('emr-tpl-designer-css');
    if (!st) { st = document.createElement('style'); st.id = 'emr-tpl-designer-css'; document.head.appendChild(st); }
    st.textContent = [
      '.etd-shell { background:#f3f5f7; } .etd-shell>.page-title { color:#17324d; letter-spacing:.02em; }',
      '.etd-top { flex:none; flex-wrap:wrap; gap:8px; margin-bottom:6px; background:#fff; border:1px solid var(--yb-border,#dfe4eb); border-radius:4px; padding:6px 8px; }',
      '.etd-top .el-button+.el-button { margin-left:0; }',
      '.etd-main { flex:1; min-height:0; display:flex; gap:8px; }',
      '.etd-left { flex:none; width:232px; min-width:232px; display:flex; flex-direction:column; min-height:0; border:1px solid var(--yb-border,#dfe4eb); border-radius:4px; padding:8px; background:#fff; }',
      '.etd-tree { flex:1; min-height:0; overflow:auto; margin-top:8px; }',
      '.etd-tree .el-tree-node__content { height:28px; }',
      '.etd-tip { flex:none; margin-top:6px; font-size:12px; color:var(--yb-ink-4,#8994a5); line-height:1.7; }',
      '.etd-mid { flex:1; min-width:0; display:flex; flex-direction:column; min-height:0; border:1px solid #cfd6df; background:#fff; }',
      '.etd-toolbar-host { flex:none; } .etd-toolbar-host .emr-toolbar { border-width:0 0 1px; border-radius:0; background:#f8fafc; }',
      '.etd-canvasbar { flex:none; display:flex; align-items:center; gap:8px; min-height:34px; padding:4px 10px; background:#fff; border-bottom:1px solid #d8dee7; font-size:12px; color:#536273; }',
      '.etd-canvasbar .etd-spacer { flex:1; } .etd-page-state { font-variant-numeric:tabular-nums; }',
      '.etd-editor-host { flex:1; min-height:0; overflow:auto; border:0; border-radius:0; transition:border-color .15s, box-shadow .15s, background .15s; }',
      '.etd-editor-host.etd-drag-over { border-color:var(--yb-brand,#1a5c9e); box-shadow:inset 0 0 0 2px rgba(26,92,158,.14); background:var(--yb-brand-subtle,#eaf1f8); }',
      '.etd-editor-host .ProseMirror { min-height:100%; box-sizing:border-box; }',
      '.etd-editor-host .ProseMirror-selectednode { outline:2px solid var(--yb-brand,#1a5c9e); outline-offset:1px; border-radius:3px; }',
      '.etd-right { flex:none; width:286px; min-width:286px; display:flex; flex-direction:column; min-height:0; overflow:auto; border:1px solid var(--yb-border,#dfe4eb); border-radius:4px; padding:8px 10px; background:#fff; }',
      '.etd-card { border-bottom:1px dashed var(--yb-divider,#f0f3f7); padding-bottom:10px; margin-bottom:10px; }',
      '.etd-card:last-child { border-bottom:none; margin-bottom:0; }',
      '.etd-card-hd { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); margin-bottom:8px; display:flex; align-items:center; gap:6px; }',
      '.etd-hint { font-size:12px; color:var(--yb-ink-4,#8994a5); line-height:1.8; }',
      '.etd-meta { font-size:12px; color:var(--yb-ink-2,#3d4a5c); line-height:2.1; }',
      '.etd-meta b { color:var(--yb-ink-1,#1c2430); font-weight:600; }',
      '.etd-form .el-form-item { margin-bottom:10px; }',
      '.etd-form .el-form-item__label { font-size:12px; }',
      '.etd-tags { display:flex; flex-wrap:wrap; gap:6px; }',
      '.etd-tn { display:flex; align-items:center; gap:6px; flex:1; min-width:0; overflow:hidden; }',
      '.etd-tn-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.etd-tn-key { font-size:11px; color:var(--yb-ink-4,#8994a5); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.etd-tn--element { cursor:grab; }',
      '.etd-tn--element:active { cursor:grabbing; }',
      '.etd-component-group { margin-top:10px; } .etd-component-title { color:#64748b; font-size:11px; font-weight:700; letter-spacing:.08em; margin:0 0 6px; }',
      '.etd-component-grid { display:grid; grid-template-columns:1fr 1fr; gap:6px; } .etd-component-grid .el-button { margin:0; justify-content:flex-start; border-radius:3px; }',
      '.etd-left-tabs { margin-bottom:8px; } .etd-legacy-alert { flex:none; margin-bottom:6px; }',
      '.etd-print-grid { display:grid; grid-template-columns:1fr 1fr; gap:6px 8px; } .etd-print-grid .el-form-item { margin-bottom:8px; }',
      '.etd-script textarea { font-family:Consolas,monospace; font-size:12px; }',
      '.etd-draw-preview { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; background:var(--yb-surface-2,#f7f9fc); padding:6px; max-height:150px; overflow:hidden; text-align:center; }',
      '.etd-draw-preview svg { max-width:100%; max-height:136px; height:auto; }',
      '.etd-act-row { display:flex; gap:6px; width:100%; } .etd-act-row .el-button { flex:1; margin-left:0; } .etd-frag-search { display:flex; align-items:center; gap:8px; margin-bottom:8px; }',
      /* 高级版: 版本抽屉 / 批注栏 */
      '.etd-ver-pick { display:flex; align-items:center; margin-bottom:10px; }',
      '.etd-ver-diff { margin-bottom:12px; }',
      '.etd-ver-list-hd { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); border-top:1px solid var(--yb-border-light,#ebeff4); padding-top:8px; }',
      '.etd-anno-card { flex:1; display:flex; flex-direction:column; min-height:0; } .etd-anno-host { flex:1; min-height:120px; overflow:hidden; }'
    ].join('\n');
  })();

  HIS.views['emr-template-designer'] = {
    name: 'EmrTemplateDesigner',
    data: function () {
      return {
        isLead: false,
        /* 顶栏 */
        scopeFilter: -1, levelFilters: LEVEL_FILTERS, scopeLevels: SCOPE_LEVELS,
        valueTypes: VALUE_TYPES.map(function (v) { return { v: v, l: VT_LABEL[v] }; }),
        editModes: EDIT_MODES, editorModes: EDITOR_MODES, macroPresets: [],
        /* 模板选择 */
        tplLoading: false, allTemplates: [], currentTplId: '', current: null,
        tplFormName: '', saving: false,
        /* 文档附加载荷 */
        printScript: '', printConfig: defaultPrintConfig(), legacyFields: [], legacyConverted: false,
        lockedKeys: [], lockedDirty: false,
        /* 编辑器 */
        editorMode: 'design', editorLoading: false, zoom: 0.9, pageCount: 1, paginationDiagnostics: [],
        leftTab: 'components',
        componentGroups: [
          { title: '结构', items: [{ k: 'section', l: '章节' }, { k: 'field', l: '数据元' }, { k: 'conditional', l: '条件块' }] },
          { title: '内容', items: [{ k: 'macro', l: '宏变量' }, { k: 'table', l: '表格' }, { k: 'drawing', l: '医学图示' }] },
          { title: '复用与分页', items: [{ k: 'fragment', l: '片段' }, { k: 'pagebreak', l: '分页符' }] }
        ],
        /* 数据集(左栏) */
        datasets: [], dsListLoading: false, datasetId: '', treeLoading: false,
        dsDataset: null, chapters: [],
        /* 拖拽 */
        dragOver: false,
        /* 选中节点属性(selDrawingSvg: 选中图示节点的 SVG 预览串; docTick: 编辑器事务计数) */
        sel: null, selForm: {}, selDrawingSvg: '', docTick: 0, condOperators: COND_OPERATORS,
        /* 片段浏览(更换片段弹窗)与版本检查 */
        fragDlg: false, fragLoading: false, fragList: [], fragKeyword: '', fragPick: null, fragChecking: false,
        /* 弹窗 */
        newDlg: false, newSaving: false, newForm: { templateCode: '', templateName: '', scopeLevel: 2, parentTemplateId: '', datasetId: '' },
        dsDlg: false, dsSaving: false, dsForm: { name: '', scopeLevel: 2 },
        copyDlg: false, copySaving: false, copyForm: { templateCode: '', templateName: '', scopeLevel: 2 },
        /* 高级版: 版本抽屉 / 发布审批 / 修订与批注 */
        rightTab: 'props', verDrawer: false, verList: [], verLoading: false, verSelA: '', verSelB: '', verSummary: '', verDiffOk: false,
        pubStatus: 3, approvalBusy: false, changeSummary: '',
        trackOn: false, annoCount: 0
      };
    },
    computed: {
      /* 模板下拉选项(按层级过滤; id 统一转字符串防雪花精度问题) */
      tplOptions: function () {
        var f = this.scopeFilter;
        var out = [];
        (this.allTemplates || []).forEach(function (t) {
          if (!(f == null || f < 0 || Number(t.scopeLevel) === f)) { return; }
          out.push({ id: HIS.id(t.id), label: text(t.templateName), level: Number(t.scopeLevel), version: t.version, raw: t });
        });
        return out;
      },
      /* 数据集下拉选项 */
      dsOptions: function () {
        return (this.datasets || []).map(function (d) {
          return { id: HIS.id(d.id), label: text(d.name) + ' (' + text(d.code) + ')', raw: d };
        });
      },
      /* 左栏元素树: 章节 → (直属数据元 + 小节 → 数据元) */
      treeData: function () {
        var out = [];
        (this.chapters || []).forEach(function (c) {
          var ck = text(c.chapterKey);
          var kids = [];
          (c.elements || []).forEach(function (e) {
            kids.push({ id: 'e:' + text(e.id), kind: 'element', label: text(e.fieldName), el: e });
          });
          (c.sections || []).forEach(function (s) {
            kids.push({
              id: 's:' + ck + '|' + text(s.sectionKey) + '|' + text(s.sectionName), kind: 'section', label: text(s.sectionName),
              sectionKey: text(s.sectionKey),
              children: (s.elements || []).map(function (e) {
                return { id: 'e:' + text(e.id), kind: 'element', label: text(e.fieldName), el: e };
              })
            });
          });
          out.push({ id: 'c:' + ck, kind: 'chapter', label: text(c.chapterName), chapterKey: ck, children: kids });
        });
        return out;
      },
      selKindLabel: function () { return this.sel ? (KIND_LABEL[this.sel.kind] || '节点') : ''; },
      selIsDict: function () { return ['select', 'multiselect', 'dict'].indexOf(text(this.selForm.valueType)) >= 0; },
      selHasOpts: function () { return ['select', 'multiselect', 'checkbox', 'textarea'].indexOf(text(this.selForm.valueType)) >= 0; },
      /* 当前文档数据元清单(条件块字段下拉数据源; 依赖 docTick 随编辑器事务失效重算) */
      documentFields: function () {
        void this.docTick;
        var w = this._editor;
        if (!w || typeof w.extractDocumentFields !== 'function') { return []; }
        try { return w.extractDocumentFields() || []; } catch (e) { return []; }
      },
      /* 选中章节是否已在母板锁定清单中 */
      lockInList: function () {
        if (!this.sel || this.sel.kind !== 'emrSection') { return false; }
        var k = text(this.selForm.sectionKey);
        return !!k && this.lockedKeys.indexOf(k) >= 0;
      },
      /* 父模板名称(从当前列表解析, 不在列表时回落 id) */
      parentName: function () {
        var p = this.current && this.current.parentTemplateId;
        if (!p) { return ''; }
        var hit = (this.allTemplates || []).filter(function (t) { return HIS.sameId(t.id, p); })[0];
        return hit ? text(hit.templateName) : ('#' + text(p));
      },
      /* 新建模板的可选父模板: 层级严格高于当前表单层级(全院 → 科室 → 个人) */
      parentCandidates: function () {
        var lv = Number(this.newForm.scopeLevel);
        return (this.tplOptions || []).filter(function (o) { return o.level < lv; });
      },
      /* 审核权口径与后端 requireApproveReviewer 对齐: ADMIN/SUPER_ADMIN(后端兜底 403) */
      canReview: function () {
        return !!(HIS.hasRole && (HIS.hasRole('ADMIN') || HIS.hasRole('SUPER_ADMIN')));
      }
    },
    watch: {
      currentTplId: function (nv) { this.loadTemplate(nv); },
      scopeFilter: function () { this.fetchTemplates(); },
      printConfig: {
        deep: true,
        handler: function (value) { if (this._editor) { this._editor.setPrintConfig(value); } }
      }
    },
    created: function () {
      this.isLead = typeof HIS.isLead === 'function' ? !!HIS.isLead() : false;
      if (HIS.EmrEditor && HIS.EmrEditor.EmrMacroResolver) { this.macroPresets = HIS.EmrEditor.EmrMacroResolver.presets || []; }
      this.fetchTemplates();
      this.fetchDatasets();
    },
    mounted: function () { this.initEditor(); },
    beforeUnmount: function () {
      this._destroyed = true;
      this.unmountAnno();
      this.unmountVerDiff();
      var w = this._editor;
      this._editor = null;
      if (w) { try { w.destroy(); } catch (e) { /* noop */ } }
    },
    methods: {
      /* ===== 基础展示 ===== */
      levelLabel: function (v) {
        var o = SCOPE_LEVELS.filter(function (x) { return x.v === Number(v); })[0];
        return o ? o.l : (v == null ? '-' : v);
      },
      levelTag: function (v) {
        var o = SCOPE_LEVELS.filter(function (x) { return x.v === Number(v); })[0];
        return o ? o.t : 'info';
      },
      vtLabel: function (t) { return VT_LABEL[mapValueType(t)] || mapValueType(t); },

      /* ===== 编辑器生命周期 ===== */
      initEditor: function () {
        var vm = this;
        if (!HIS.EmrEditor || typeof HIS.EmrEditor.createEditor !== 'function') {
          ElementPlus.ElMessage.error('编辑器引擎未加载: 请确认 emr-extensions.js / emr-editor.js 已在 index.html 中引入');
          return;
        }
        vm.editorLoading = true;
        HIS.EmrEditor.createEditor({
          container: vm.$refs.editorHost,
          toolbarContainer: vm.$refs.toolbarHost,
          mode: vm.editorMode, pageCanvas: true, printConfig: vm.printConfig,
          placeholder: '从左侧插入结构化组件，像编辑 Word 文档一样完成病历版式…',
          onSave: function () { vm.save(); }
        }).then(function (w) {
          if (vm._destroyed) { try { w.destroy(); } catch (e) { /* noop */ } return; }
          vm._editor = w;
          w.setZoom(vm.zoom);
          w.on('toolbar', function () { vm.docTick++; vm.refreshSel(); });   /* docTick: documentFields 等依赖文档内容的计算属性失效重算 */
          w.on('pagination', function (state) { vm.pageCount = state.pageCount || 1; vm.paginationDiagnostics = state.diagnostics || []; });
          /* 编辑器就绪前已选择模板的场景: 补载文档 */
          if (vm.current) {
            var doc = safeParse(vm.current.document) || fieldsToDocument(vm.current.fields);
            vm.legacyConverted = !safeParse(vm.current.document) && !!doc;
            if (doc) { applyLockedFlags(normalizeDocIn(doc), vm.lockedKeys); }
            w.fromJSON(doc || null);
          }
        }).catch(function (e) {
          ElementPlus.ElMessage.error('编辑器初始化失败: ' + ((e && e.message) || e));
        }).finally(function () { vm.editorLoading = false; });
      },
      onModeChange: function (m) {
        if (this._editor) { this._editor.setMode(m); }
      },
      setZoom: function (value) {
        this.zoom = Math.max(0.5, Math.min(1.5, Number(value) || 1));
        if (this._editor) { this._editor.setZoom(this.zoom); }
      },
      fitWidth: function () {
        var host = this.$refs.editorHost;
        if (!host) { return; }
        var width = this.printConfig.orientation === 'landscape' ? 1122 : 794;
        this.setZoom(Math.max(0.5, Math.min(1.25, (host.clientWidth - 84) / width)));
      },
      insertComponent: function (key) {
        var ed = this._editor && this._editor.editor;
        if (!ed) { ElementPlus.ElMessage.warning('编辑器尚未就绪'); return; }
        try {
          if (key === 'field') { ed.chain().focus().insertEmrField({ fieldKey: genCode('field'), fieldName: '新数据元', valueType: 'text' }).run(); }
          else if (key === 'section') { ed.chain().focus().insertEmrSection({ sectionKey: genCode('section'), title: '新章节', editMode: 'mixed' }).run(); }
          else if (key === 'macro') { ed.chain().focus().insertEmrMacro({ macroCode: 'patientName', dataSource: 'patient' }).run(); }
          else if (key === 'conditional') { ed.chain().focus().insertEmrConditionalBlock({ conditionFieldKey: this.documentFields.length ? this.documentFields[0].fieldKey : '', conditionOperator: 'eq', conditionValue: '', visible: true }).run(); }
          else if (key === 'table') { ed.chain().focus().insertTable(3, 3, true).run(); }
          else if (key === 'drawing') { ed.chain().focus().insertEmrDrawing({ title: '医学图示', svgData: '' }).run(); }
          else if (key === 'fragment') { ed.chain().focus().insertEmrFragment({ fragmentId: '', sourceTemplateId: '', version: '1', title: '片段引用' }).run(); }
          else if (key === 'pagebreak') { ed.chain().focus().insertEmrPageBreak().run(); }
        } catch (e) { HIS.notifyError(new Error('插入组件失败：' + ((e && e.message) || e))); }
      },

      /* ===== 模板查询与装载 ===== */
      fetchTemplates: function () {
        var vm = this;
        var lvl = vm.scopeFilter == null || vm.scopeFilter < 0 ? 2 : vm.scopeFilter;
        vm.tplLoading = true;
        HIS.get('/api/his/emr/template/listByScope?scopeLevel=' + lvl).then(function (d) {
          vm.allTemplates = Array.isArray(d) ? d : ((d && d.records) || []);
        }).catch(HIS.notifyError).finally(function () { vm.tplLoading = false; });
      },
      loadTemplate: function (id) {
        var vm = this;
        if (!id) {
          vm.current = null; vm.tplFormName = '';
          vm.printScript = ''; vm.printConfig = defaultPrintConfig(); vm.legacyFields = []; vm.legacyConverted = false;
          vm.lockedKeys = []; vm.lockedDirty = false; vm.pubStatus = 3;
          if (vm._editor) { vm._editor.fromJSON(null); }
          vm.clearSel();
          vm.unmountAnno();
          return;
        }
        HIS.get('/api/his/emr/template/' + HIS.idParam(id)).then(function (tpl) {
          if (!tpl) { return; }
          vm.current = tpl;
          vm.tplFormName = text(tpl.templateName);
          vm.pubStatus = tpl.publishStatus == null ? 3 : Number(tpl.publishStatus);
          vm.unmountAnno();                       /* 换模板后批注面板目标失效: 卸载待重挂 */
          vm.printScript = text(tpl.printScript);
          vm.printConfig = normalizePrintConfig(tpl.printConfig);
          vm.legacyFields = parseFieldDefs(tpl.fields);
          vm.lockedKeys = parseKeys(tpl.lockedSections);
          vm.lockedDirty = false;
          var storedDoc = safeParse(tpl.document);
          var doc = storedDoc || fieldsToDocument(vm.legacyFields);
          if (vm._editor) { vm._editor.setPrintConfig(vm.printConfig); }
          vm.legacyConverted = !storedDoc && !!doc;
          if (vm._editor) {
            if (doc) { applyLockedFlags(normalizeDocIn(doc), vm.lockedKeys); }
            vm._editor.fromJSON(doc || null);
          }
          vm.clearSel();
        }).catch(HIS.notifyError);
      },
      /* 列表刷新后选中指定模板(新建/克隆/数据集创建后调用) */
      refreshAndSelect: function (id) {
        var vm = this;
        var sid = HIS.id(id);
        if (!sid) { return; }
        if (HIS.sameId(vm.currentTplId, sid)) { vm.loadTemplate(sid); } else { vm.currentTplId = sid; }
        vm.fetchTemplates();
      },

      /* ===== 高级版: 发布审批 ===== */
      pubLabel: function (v) { return { 0: '草稿', 1: '待审', 2: '已驳回', 3: '已发布' }[Number(v)] || '草稿'; },
      pubTag: function (v) { return { 0: 'info', 1: 'warning', 2: 'danger', 3: 'success' }[Number(v)] || 'info'; },
      reloadCurrent: function (sid) { var vm = this; return HIS.get('/api/his/emr/template/' + HIS.idParam(sid)).then(function (d) { if (d) { vm.current = d; vm.pubStatus = d.publishStatus == null ? 3 : Number(d.publishStatus); vm.tplFormName = text(d.templateName); } }); },
      submitReview: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { ElementPlus.ElMessage.warning('请先选择模板'); return; }
        ElementPlus.ElMessageBox.confirm('确认提交模板「' + text(tpl.templateName) + '」进入审核流程? 提交后待管理员审核发布。', '提交审核', { type: 'info' })
          .then(function () { vm.approvalBusy = true; return HIS.post('/api/his/emr/template/' + HIS.idParam(tpl.id) + '/submit', {}); })
          .then(function () { ElementPlus.ElMessage.success('已提交审核'); return vm.reloadCurrent(tpl.id); })
          .catch(HIS.notifyError).finally(function () { vm.approvalBusy = false; });
      },
      retractReview: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { return; }
        HIS.post('/api/his/emr/template/' + HIS.idParam(tpl.id) + '/retract', {})
          .then(function () { ElementPlus.ElMessage.success('已撤回草稿'); return vm.reloadCurrent(tpl.id); })
          .catch(HIS.notifyError);
      },
      approveReview: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { return; }
        ElementPlus.ElMessageBox.prompt('审核意见(可选)', '审核通过并发布', { inputValue: '' })
          .then(function (ref) { vm.approvalBusy = true; return HIS.post('/api/his/emr/template/' + HIS.idParam(tpl.id) + '/approve?opinion=' + encodeURIComponent(ref.value || '')); })
          .then(function () { ElementPlus.ElMessage.success('已审核通过并发布'); return vm.reloadCurrent(tpl.id); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } })
          .finally(function () { vm.approvalBusy = false; });
      },
      rejectReview: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { return; }
        ElementPlus.ElMessageBox.prompt('驳回意见(必填)', '审核驳回', { inputValidator: function (v) { return (v && v.trim()) ? true : '驳回必须填写意见'; } })
          .then(function (ref) { vm.approvalBusy = true; return HIS.post('/api/his/emr/template/' + HIS.idParam(tpl.id) + '/reject?opinion=' + encodeURIComponent(ref.value.trim())); })
          .then(function () { ElementPlus.ElMessage.success('已驳回'); return vm.reloadCurrent(tpl.id); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } })
          .finally(function () { vm.approvalBusy = false; });
      },

      /* ===== 高级版: 版本历史抽屉 ===== */
      openVerDrawer: function () {
        if (!this.current) { ElementPlus.ElMessage.warning('请先选择模板'); return; }
        this.verDrawer = true; this.fetchVersions();
      },
      fetchVersions: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { return; }
        vm.verLoading = true; vm.unmountVerDiff(); vm.verDiffOk = false;
        HIS.get('/api/his/emr/template/' + HIS.idParam(tpl.id) + '/versions').then(function (d) {
          vm.verList = Array.isArray(d) ? d : ((d && d.records) || []);
          if (vm.verList.length >= 2) { vm.verSelA = HIS.id(vm.verList[1].id); vm.verSelB = HIS.id(vm.verList[0].id); }
          else if (vm.verList.length === 1) { vm.verSelA = ''; vm.verSelB = HIS.id(vm.verList[0].id); }
        }).catch(HIS.notifyError).finally(function () { vm.verLoading = false; });
      },
      verLabel: function (v) {
        var op = { save: '保存', publish: '发布', rollback: '回滚' }[v.operateType] || v.operateType || '操作';
        return 'v' + v.versionNo + ' · ' + op + ' · ' + text(v.operatorName) + ' · ' + text(v.changeSummary);
      },
      verById: function (vid) { return (this.verList || []).filter(function (v) { return HIS.sameId(v.id, vid); })[0]; },
      verIdStr: function (v) { return HIS.id(v.id); },
      compareVersions: function () {
        var vm = this, tpl = vm.current;
        if (!tpl || !vm.verSelA || !vm.verSelB) { ElementPlus.ElMessage.warning('请选择要对比的两个版本'); return; }
        var va = vm.verById(vm.verSelA), vb = vm.verById(vm.verSelB);
        Promise.all([
          HIS.get('/api/his/emr/template/version/' + HIS.idParam(vm.verSelA)),
          HIS.get('/api/his/emr/template/version/' + HIS.idParam(vm.verSelB))
        ]).then(function (arr) {
          var da = safeParse(arr[0] && arr[0].document), db = safeParse(arr[1] && arr[1].document);
          vm._diffProps = {
            oldDoc: da, newDoc: db,
            oldLabel: 'v' + (va ? va.versionNo : '?'), newLabel: 'v' + (vb ? vb.versionNo : '?'),
            oldMeta: va ? text(va.changeSummary) : '', newMeta: vb ? text(vb.changeSummary) : '',
            canRollback: true,
            onRollback: function () { vm.rollbackTo(va ? va.versionNo : null); }
          };
          vm.verDiffOk = true;
          vm.$nextTick(function () { vm.mountVerDiff(); });
        }).catch(HIS.notifyError);
      },
      mountVerDiff: function () {
        this.unmountVerDiff();
        var host = this.$refs.verDiffHost;
        if (!host || !this._diffProps || !HIS.EmrEditor || !HIS.EmrEditor.EmrDiffViewer) { return; }
        host.innerHTML = '';
        this._verDiff = HIS.EmrEditor.EmrDiffViewer.mount(host, this._diffProps);
      },
      unmountVerDiff: function () {
        var m = this._verDiff;
        this._verDiff = null;
        if (m) { try { m.app.unmount(); } catch (e) { /* noop */ } }
      },
      rollbackTo: function (versionNo) {
        var vm = this, tpl = vm.current;
        if (!tpl || versionNo == null) { return; }
        ElementPlus.ElMessageBox.confirm('确认回滚到 v' + versionNo + '? 将写回该版本文档并生成新版本(不覆盖历史)。', '回滚确认', { type: 'warning' })
          .then(function () {
            return HIS.post('/api/his/emr/template/' + HIS.idParam(tpl.id) + '/rollback?versionNo=' + versionNo + '&summary=' + encodeURIComponent('回滚自 v' + versionNo));
          })
          .then(function () {
            ElementPlus.ElMessage.success('已回滚, 正在重载模板…');
            vm.verDrawer = false;
            return HIS.get('/api/his/emr/template/' + HIS.idParam(tpl.id));
          })
          .then(function (d) { if (d) { vm.current = d; vm.pubStatus = d.publishStatus == null ? 3 : Number(d.publishStatus); var doc = safeParse(d.document); if (vm._editor) { vm._editor.fromJSON(doc || null); } } vm.fetchTemplates(); })
          .catch(HIS.notifyError);
      },

      /* ===== 高级版: 修订模式与批注 ===== */
      toggleTrack: function (on) {
        if (!this._editor) { ElementPlus.ElMessage.warning('编辑器尚未就绪'); this.trackOn = false; return; }
        var u = (HIS.getUser && HIS.getUser()) || {};
        this._editor.toggleTrack(!!on, { name: u.realName || u.username, id: u.userId });
        this.trackOn = !!on;
        ElementPlus.ElMessage[on ? 'success' : 'info'](on ? '已开启修订模式, 后续插入/删除将留痕' : '已关闭修订模式');
      },
      acceptAllTrack: function () {
        if (!this._editor) { return; }
        var vm = this;
        ElementPlus.ElMessageBox.confirm('接受全部修订? 删除线内容将被移除, 下划线内容并入正文。', '接受修订', { type: 'success' })
          .then(function () { vm._editor.acceptAllTrack(); vm.docTick++; ElementPlus.ElMessage.success('已接受全部修订'); })
          .catch(function () { /* noop */ });
      },
      rejectAllTrack: function () {
        if (!this._editor) { return; }
        var vm = this;
        ElementPlus.ElMessageBox.confirm('拒绝全部修订? 新增内容将被移除, 删除内容恢复为正文。', '拒绝修订', { type: 'warning' })
          .then(function () { vm._editor.rejectAllTrack(); vm.docTick++; ElementPlus.ElMessage.success('已拒绝全部修订'); })
          .catch(function () { /* noop */ });
      },
      mountAnno: function () {
        var vm = this;
        if (!vm.current) { ElementPlus.ElMessage.warning('请先选择模板'); return; }
        if (!HIS.EmrEditor || !HIS.EmrEditor.EmrAnnotationPanel) { ElementPlus.ElMessage.error('批注面板未就绪'); return; }
        vm.$nextTick(function () {
          vm.unmountAnno();
          var host = vm.$refs.annoHost;
          if (!host) { return; }
          host.innerHTML = '';
          vm._annoM = HIS.EmrEditor.EmrAnnotationPanel.mount(host, {
            wrapper: vm._editor, targetType: 'template', targetId: HIS.id(vm.current.id),
            onCount: function (n) { vm.annoCount = n; }
          });
          vm._editor.on('commentClick', function (evt) { vm.rightTab = 'anno'; });
        });
      },
      unmountAnno: function () {
        var m = this._annoM;
        this._annoM = null;
        if (m) { try { m.app.unmount(); } catch (e) { /* noop */ } }
      },

      /* ===== 保存 ===== */
      save: function () {
        var vm = this;
        if (!vm.current) { ElementPlus.ElMessage.warning('请先选择或新建模板'); return; }
        if (!vm._editor) { ElementPlus.ElMessage.warning('编辑器尚未就绪'); return; }
        /* 高级版: 存在未处理修订留痕时先提醒(保存不会自动接受/拒绝) */
        if (vm._editor.hasUnresolvedTrack()) {
          ElementPlus.ElMessageBox.confirm('文档存在未处理的修订留痕, 保存后将随模板持久化。可先在顶栏「接受/拒绝」处理。', '未处理修订', { type: 'warning', confirmButtonText: '仍然保存', cancelButtonText: '去处理' })
            .then(function () { vm.doSave(); })
            .catch(function () { /* 用户取消 */ });
          return;
        }
        vm.doSave();
      },
      doSave: function () {
        var vm = this;
        var tpl = vm.current;
        if (!tpl) { ElementPlus.ElMessage.warning('请先选择或新建模板'); return; }
        var normalizedDoc = normalizeDocOut(vm._editor.toJSON());
        var body = {
          templateName: text(vm.tplFormName).trim() || text(tpl.templateName),
          document: JSON.stringify(normalizedDoc),
          rawFields: documentToFields(normalizedDoc, vm.legacyFields),
          printScript: text(vm.printScript),
          printConfig: JSON.stringify(normalizePrintConfig(vm.printConfig))
        };
        if (text(vm.changeSummary).trim()) { body.changeSummary = text(vm.changeSummary).trim(); }
        if (vm.lockedDirty) { body.lockedSections = JSON.stringify(vm.lockedKeys); }
        vm.saving = true;
        HIS.put('/api/his/emr/template/' + HIS.idParam(tpl.id), body).then(function () {
          vm.lockedDirty = false;
          vm.changeSummary = '';
          HIS.notifySuccess('模板已保存(版本自增)');
          return HIS.get('/api/his/emr/template/' + HIS.idParam(tpl.id));
        }).then(function (d) {
          if (d) { vm.current = d; vm.tplFormName = text(d.templateName); vm.legacyFields = parseFieldDefs(d.fields); vm.legacyConverted = false; vm.pubStatus = d.publishStatus == null ? 3 : Number(d.publishStatus); }
          vm.fetchTemplates();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      openNew: function () {
        this.newForm = {
          templateCode: genCode('EMR_NEW'), templateName: '', scopeLevel: this.isLead ? 0 : 2,
          parentTemplateId: '', datasetId: this.datasetId || ''
        };
        this.newDlg = true;
      },
      submitNew: function () {
        var vm = this, f = vm.newForm;
        if (!text(f.templateName).trim()) { ElementPlus.ElMessage.warning('请填写模板名称'); return; }
        if (!text(f.templateCode).trim()) { ElementPlus.ElMessage.warning('请填写模板编码'); return; }
        vm.newSaving = true;
        HIS.post('/api/his/emr/template', {
          templateCode: text(f.templateCode).trim(), templateName: text(f.templateName).trim(),
          ownerScope: OWNER_BY_LEVEL[Number(f.scopeLevel)], scopeLevel: Number(f.scopeLevel),
          parentTemplateId: f.parentTemplateId || null, datasetId: f.datasetId || null,
          document: JSON.stringify({ type: 'doc', content: [{ type: 'paragraph' }] }),
          rawFields: [], printConfig: JSON.stringify(defaultPrintConfig())
        }).then(function (tpl) {
          HIS.notifySuccess('模板已创建');
          vm.newDlg = false;
          vm.refreshAndSelect(tpl && tpl.id);
        }).catch(HIS.notifyError).finally(function () { vm.newSaving = false; });
      },
      openDsCreate: function () {
        if (!this.datasetId) { ElementPlus.ElMessage.warning('请先在左栏选择数据集'); return; }
        this.dsForm = { name: (this.dsDataset ? text(this.dsDataset.name) : '') + '模板', scopeLevel: this.isLead ? 0 : 2 };
        this.dsDlg = true;
      },
      submitDsCreate: function () {
        var vm = this, f = vm.dsForm;
        if (!text(f.name).trim()) { ElementPlus.ElMessage.warning('请填写模板名称'); return; }
        vm.dsSaving = true;
        HIS.post('/api/his/emr/template/createFromDataset', {
          datasetId: vm.datasetId, name: text(f.name).trim(), scopeLevel: Number(f.scopeLevel)
        }).then(function (tpl) {
          HIS.notifySuccess('已由数据集生成模板');
          vm.dsDlg = false;
          vm.refreshAndSelect(tpl && tpl.id);
        }).catch(HIS.notifyError).finally(function () { vm.dsSaving = false; });
      },
      openSaveAs: function () {
        var tpl = this.current;
        if (!tpl) { ElementPlus.ElMessage.warning('请先选择模板'); return; }
        this.copyForm = {
          templateCode: genCode('EMR_CP'), templateName: text(tpl.templateName) + '(副本)',
          scopeLevel: tpl.scopeLevel != null ? Number(tpl.scopeLevel) : 2
        };
        this.copyDlg = true;
      },
      submitSaveAs: function () {
        var vm = this, f = vm.copyForm, tpl = vm.current;
        if (!tpl || !vm._editor) { return; }
        if (!text(f.templateCode).trim()) { ElementPlus.ElMessage.warning('请填写模板编码'); return; }
        if (!text(f.templateName).trim()) { ElementPlus.ElMessage.warning('请填写模板名称'); return; }
        vm.copySaving = true;
        HIS.post('/api/his/emr/template', {
          templateCode: text(f.templateCode).trim(), templateName: text(f.templateName).trim(),
          ownerScope: OWNER_BY_LEVEL[Number(f.scopeLevel)], scopeLevel: Number(f.scopeLevel),
          document: JSON.stringify(normalizeDocOut(vm._editor.toJSON())),
          rawFields: documentToFields(normalizeDocOut(vm._editor.toJSON()), vm.legacyFields),
          printScript: text(vm.printScript), printConfig: JSON.stringify(normalizePrintConfig(vm.printConfig)),
          lockedSections: JSON.stringify(vm.lockedKeys || []),
          datasetId: tpl.datasetId || null,
          recordType: tpl.recordType != null ? tpl.recordType : null,
          templateCategory: tpl.templateCategory != null ? tpl.templateCategory : null,
          scope: tpl.scope != null ? tpl.scope : null
        }).then(function (nt) {
          HIS.notifySuccess('已另存为新模板');
          vm.copyDlg = false;
          vm.refreshAndSelect(nt && nt.id);
        }).catch(HIS.notifyError).finally(function () { vm.copySaving = false; });
      },
      removeTpl: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { ElementPlus.ElMessage.warning('请先选择模板'); return; }
        ElementPlus.ElMessageBox.confirm('确认删除模板「' + text(tpl.templateName) + '」? 删除后不可恢复。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/his/emr/template/' + HIS.idParam(tpl.id)); })
          .then(function () {
            HIS.notifySuccess('模板已删除');
            vm.currentTplId = '';
            vm.current = null;
            vm.fetchTemplates();
          })
          .catch(function (x) { if (x !== 'cancel' && x !== 'close' && x && x.message) { HIS.notifyError(x); } });
      },
      propagate: function () {
        var vm = this, tpl = vm.current;
        if (!tpl) { ElementPlus.ElMessage.warning('请先选择模板'); return; }
        if (Number(tpl.scopeLevel) === 2) { ElementPlus.ElMessage.warning('个人模板不支持向下传播(仅全院/科室母板)'); return; }
        if (!vm.lockedKeys.length) { ElementPlus.ElMessage.warning('母板未锁定任何章节: 请先在右栏「母板锁定章节」中加入章节'); return; }
        ElementPlus.ElMessageBox.confirm('确认将母板锁定章节的内容下发到全部直接子模板? 子模板对应章节的内容将被覆盖。', '传播确认', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/emr/template/propagate/' + HIS.idParam(tpl.id), {}); })
          .then(function (d) {
            d = d || {};
            if (d.message) { ElementPlus.ElMessage.info(d.message); }
            else { HIS.notifySuccess('传播完成: 子模板 ' + (d.children == null ? '-' : d.children) + ' 个, 更新 ' + (d.updated == null ? '-' : d.updated) + ' 个'); }
          })
          .catch(function (x) { if (x !== 'cancel' && x !== 'close' && x && x.message) { HIS.notifyError(x); } });
      },
      doPrint: function () {
        var vm = this;
        if (!vm._editor) { ElementPlus.ElMessage.warning('编辑器尚未就绪'); return; }
        vm._editor.print({
          title: (vm.current ? text(vm.current.templateName) : '病历模板') + ' - 打印预览',
          printScript: text(vm.printScript), printConfig: normalizePrintConfig(vm.printConfig),
          context: { templateName: vm.current ? text(vm.current.templateName) : '病历模板', name: '示例患者', patientName: '示例患者', visitNo: '示例就诊号', organizationName: '示例医疗机构' },
          autoPrint: false
        });
      },

      /* ===== 数据集(左栏) ===== */
      fetchDatasets: function () {
        var vm = this;
        vm.dsListLoading = true;
        HIS.get('/api/his/emr/dataset/list?page=1&size=200').then(function (d) {
          vm.datasets = (d && d.records) || [];
        }).catch(HIS.notifyError).finally(function () { vm.dsListLoading = false; });
      },
      onDatasetChange: function (id) {
        var vm = this;
        vm.chapters = []; vm.dsDataset = null;
        if (id) { vm.loadDatasetTree(id); }
      },
      loadDatasetTree: function (id) {
        var vm = this;
        vm.treeLoading = true;
        HIS.get('/api/his/emr/dataset/' + HIS.idParam(id)).then(function (d) {
          vm.dsDataset = (d && d.dataset) || null;
          vm.chapters = (d && d.chapters) || [];
        }).catch(function (e) {
          vm.chapters = []; vm.dsDataset = null;
          HIS.notifyError(e);
        }).finally(function () { vm.treeLoading = false; });
      },

      /* ===== 拖拽与插入(核心交互) ===== */
      onElDragStart: function (e, data) {
        if (!data || data.kind !== 'element' || !data.el) { return; }
        var payload = elToPayload(data.el);
        this._dragEl = payload;
        try {
          e.dataTransfer.setData('application/x-emr-element', JSON.stringify(payload));
          e.dataTransfer.setData('text/plain', payload.fieldName || payload.fieldKey || '数据元');
          e.dataTransfer.effectAllowed = 'copy';
        } catch (err) { /* noop */ }
      },
      onElDragEnd: function () {
        this._dragEl = null;
        this.dragOver = false;
      },
      onDragOver: function (e) {
        if (!this._dragEl) { return; }  /* 仅本页数据元拖拽高亮(编辑器内文本拖拽等不干扰) */
        if (e.dataTransfer) { try { e.dataTransfer.dropEffect = 'copy'; } catch (err) { /* noop */ } }
        if (!this.dragOver) { this.dragOver = true; }
      },
      onDragLeave: function (e) {
        var host = this.$refs.editorHost;
        if (host && e.relatedTarget && host.contains(e.relatedTarget)) { return; }
        this.dragOver = false;
      },
      /* capture 阶段拦截: 仅处理本页数据元拖拽, 其余(编辑器内部拖拽)放行给 ProseMirror */
      onDropCapture: function (e) {
        var payload = this._dragEl;
        if (!payload) {
          try {
            var raw = e.dataTransfer && e.dataTransfer.getData('application/x-emr-element');
            if (raw) { payload = JSON.parse(raw); }
          } catch (err) { payload = null; }
        }
        this._dragEl = null;
        this.dragOver = false;
        if (!payload) { return; }
        e.preventDefault();
        e.stopPropagation();
        this.insertField(payload, e.clientX, e.clientY);
      },
      insertField: function (payload, cx, cy) {
        var vm = this;
        if (!payload) { return; }
        var ed = vm._editor && vm._editor.editor;
        if (!ed) { ElementPlus.ElMessage.warning('编辑器尚未就绪, 请稍候重试'); return; }
        var attrs = {
          fieldKey: payload.fieldKey || genCode('field'),
          fieldName: payload.fieldName || payload.fieldKey || '新数据元',
          valueType: payload.valueType || 'text',
          dictSource: payload.dictSource || null,
          required: !!payload.required, readonly: !!payload.readonly, noCopy: !!payload.noCopy
        };
        var pos = null;
        if (typeof cx === 'number' && typeof cy === 'number') {
          try {
            var c = ed.view.posAtCoords({ left: cx, top: cy });
            if (c && typeof c.pos === 'number') { pos = c.pos; }
          } catch (err) { /* noop */ }
        }
        try {
          if (pos == null) { ed.chain().focus().insertEmrField(attrs).run(); }
          else { ed.chain().focus().insertContentAt(pos, { type: 'emrField', attrs: attrs }).run(); }
        } catch (err) {
          HIS.notifyError(new Error('插入数据元失败: ' + ((err && err.message) || err)));
        }
      },
      /* 双击左栏数据元: 插入到当前光标处 */
      onElDblClick: function (data) {
        if (!data || data.kind !== 'element' || !data.el) { return; }
        this.insertField(elToPayload(data.el));
      },

      /* ===== 节点选中与属性 ===== */
      onEditorClick: function (e) {
        var vm = this, ed = vm._editor && vm._editor.editor;
        if (!ed) { return; }
        var el = e.target, found = null, kind = null;
        var host = vm.$refs.editorHost;
        while (el && el.nodeType === 1 && el !== host) {
          if (el.hasAttribute) {
            if (el.hasAttribute('data-emr-field')) { kind = 'emrField'; found = el; break; }
            if (el.hasAttribute('data-emr-section')) { kind = 'emrSection'; found = el; break; }
            if (el.hasAttribute('data-emr-macro')) { kind = 'emrMacro'; found = el; break; }
            if (el.hasAttribute('data-emr-cond')) { kind = 'emrConditionalBlock'; found = el; break; }
            if (el.hasAttribute('data-emr-drawing')) { kind = 'emrDrawing'; found = el; break; }
            if (el.hasAttribute('data-emr-fragment')) { kind = 'emrFragment'; found = el; break; }
          }
          el = el.parentElement;
        }
        if (!found) { vm.clearSel(); return; }
        var pos = null;
        try { pos = ed.view.posAtDOM(found, 0); } catch (err) { pos = null; }
        if (pos == null) { vm.clearSel(); return; }
        var node = ed.state.doc.nodeAt(pos);
        if (!node || node.type.name !== kind) {
          var alt = pos > 0 ? ed.state.doc.nodeAt(pos - 1) : null;
          if (alt && alt.type.name === kind) { node = alt; pos = pos - 1; }
          else { vm.clearSel(); return; }
        }
        vm.setSel(kind, pos, node.attrs);
        /* 同步 ProseMirror NodeSelection 以获得编辑器内选中描边 */
        try { ed.chain().setNodeSelection(pos).run(); } catch (err) { /* noop */ }
      },
      setSel: function (kind, pos, attrs) {
        this.sel = { kind: kind, pos: pos };
        this.selForm = this.pickAttrs(kind, attrs);
        this.selDrawingSvg = kind === 'emrDrawing' ? validSvg(attrs && attrs.svgData) : '';
      },
      clearSel: function () { this.sel = null; this.selForm = {}; this.selDrawingSvg = ''; },
      pickAttrs: function (kind, a) {
        a = a || {};
        if (kind === 'emrField') {
          return {
            fieldKey: text(a.fieldKey), fieldName: text(a.fieldName),
            valueType: mapValueType(a.valueType), dictSource: text(a.dictSource),
            options: typeof a.options === 'string' ? a.options : (a.options ? JSON.stringify(a.options) : ''),
            placeholder: text(a.placeholder), unit: text(a.unit), defaultMacro: text(a.defaultMacro),
            required: !!a.required, readonly: !!a.readonly, noCopy: !!a.noCopy
          };
        }
        if (kind === 'emrSection') {
          return {
            sectionKey: text(a.sectionKey), title: text(a.title),
            editMode: a.editMode || 'mixed', collapsible: a.collapsible !== false,
            printHidden: !!a.printHidden, locked: !!a.locked
          };
        }
        if (kind === 'emrMacro') { return { macroCode: text(a.macroCode), dataSource: text(a.dataSource) }; }
        if (kind === 'emrConditionalBlock') {
          return { conditionFieldKey: text(a.conditionFieldKey), conditionOperator: a.conditionOperator || 'eq', conditionValue: a.conditionValue == null ? '' : String(a.conditionValue), visible: a.visible !== false };
        }
        if (kind === 'emrDrawing') { return { title: text(a.title), hasSvg: !!validSvg(a.svgData), annotations: text(a.annotations) }; }
        if (kind === 'emrFragment') { return { fragmentId: text(a.fragmentId), title: text(a.title), sourceTemplateId: text(a.sourceTemplateId), version: text(a.version) }; }
        return {};
      },
      /* 事务后刷新选中表单(位置漂移时不强清, 待用户重新点击纠偏) */
      refreshSel: function () {
        var vm = this;
        if (!vm.sel) { return; }
        var ed = vm._editor && vm._editor.editor;
        if (!ed) { return; }
        var node = ed.state.doc.nodeAt(vm.sel.pos);
        if (!node || node.type.name !== vm.sel.kind) { return; }
        var f = vm.pickAttrs(vm.sel.kind, node.attrs);
        if (JSON.stringify(f) !== JSON.stringify(vm.selForm)) { vm.selForm = f; }
        if (vm.sel.kind === 'emrDrawing') {           /* 图示可能经节点内画板更新, 预览随事务同步 */
          var svg = validSvg(node.attrs.svgData);
          if (svg !== vm.selDrawingSvg) { vm.selDrawingSvg = svg; }
        }
      },
      /* 表单变更 → 回写节点 attrs */
      applySel: function (key) {
        var vm = this, sel = vm.sel;
        if (!sel) { return; }
        var ed = vm._editor && vm._editor.editor;
        if (!ed) { return; }
        var node = ed.state.doc.nodeAt(sel.pos);
        if (!node || node.type.name !== sel.kind) {
          vm.clearSel();
          ElementPlus.ElMessage.warning('节点位置已变化, 请在编辑器中重新点击目标节点');
          return;
        }
        var v = vm.selForm[key];
        if (key === 'options' || key === 'dictSource' || key === 'defaultMacro') { v = text(v).trim() || null; }
        var attrs = Object.assign({}, node.attrs);
        attrs[key] = v;
        ed.view.dispatch(ed.view.state.tr.setNodeMarkup(sel.pos, undefined, attrs));
      },
      removeSelNode: function () {
        var vm = this;
        if (!vm.sel) { return; }
        var ed = vm._editor && vm._editor.editor;
        if (!ed) { return; }
        var node = ed.state.doc.nodeAt(vm.sel.pos);
        if (!node || node.type.name !== vm.sel.kind) { vm.clearSel(); return; }
        ElementPlus.ElMessageBox.confirm('确认删除选中的「' + vm.selKindLabel + '」节点?', '删除确认', { type: 'warning' })
          .then(function () {
            ed.chain().focus().deleteRange({ from: vm.sel.pos, to: vm.sel.pos + node.nodeSize }).run();
            vm.clearSel();
          })
          .catch(function () { /* 取消 */ });
      },

      /* ===== 条件块 / 医学图示 / 片段引用(右栏属性面板扩展) ===== */
      /* 运算符切换: 为空/不为空无需比较值, 顺手清空回写 */
      onCondOperatorChange: function () {
        this.applySel('conditionOperator');
        var op = this.selForm.conditionOperator;
        if ((op === 'empty' || op === 'notEmpty') && this.selForm.conditionValue !== '') { this.selForm.conditionValue = ''; this.applySel('conditionValue'); }
      },
      /* 整块回写选中节点 attrs(编辑图示/更换片段/版本刷新共用); 节点失效时提示并清选 */
      applySelPatch: function (patch) {
        var vm = this, sel = vm.sel, ed = vm._editor && vm._editor.editor;
        if (!sel || !ed) { return false; }
        var node = ed.state.doc.nodeAt(sel.pos);
        if (!node || node.type.name !== sel.kind) {
          vm.clearSel();
          ElementPlus.ElMessage.warning('节点位置已变化, 请在编辑器中重新点击目标节点');
          return false;
        }
        ed.view.dispatch(ed.view.state.tr.setNodeMarkup(sel.pos, undefined, Object.assign({}, node.attrs, patch)));
        return true;
      },
      /* 编辑图示: 唤起全局画板(HIS.EmrDrawingPanel), 保存回调整块回写 svgData/annotations */
      editDrawing: function () {
        var vm = this;
        if (!vm.sel || vm.sel.kind !== 'emrDrawing') { return; }
        if (!(HIS.EmrDrawingPanel && typeof HIS.EmrDrawingPanel.open === 'function')) {
          ElementPlus.ElMessage.warning('画板组件未加载(emr-drawing-panel.js), 请检查 index.html 引入');
          return;
        }
        HIS.EmrDrawingPanel.open({
          title: vm.selForm.title || '', svgData: vm.selDrawingSvg, annotations: vm.selForm.annotations || '',
          onSave: function (svgData, annotationsJson) {
            vm.applySelPatch({ svgData: svgData || '', annotations: annotationsJson == null ? '' : String(annotationsJson) });
          }
        });
      },
      /* 清除图示: 二次确认后清空 svgData/annotations(节点与标题保留) */
      clearDrawing: function () {
        var vm = this;
        if (!vm.selForm.hasSvg) { return; }
        ElementPlus.ElMessageBox.confirm('确认清除该图示的绘制内容与标注? 节点与标题将保留。', '清除确认', { type: 'warning' })
          .then(function () { vm.applySelPatch({ svgData: '', annotations: '' }); })
          .catch(function () { /* 取消 */ });
      },
      /* 片段版本检查: GET /fragment/{id} 与当前引用版本比对, 有新版时可一键刷新引用信息 */
      checkFragmentUpdate: function () {
        var vm = this;
        var fid = text(vm.selForm.fragmentId).replace(/^F-/i, '');
        if (!fid) { ElementPlus.ElMessage.warning('该节点未指定片段ID, 无法检查更新'); return; }
        vm.fragChecking = true;
        HIS.get('/api/his/emr/fragment/' + HIS.idParam(fid)).then(function (f) {
          if (!f || !f.id) { ElementPlus.ElMessage.info('未找到该片段(可能已被删除)'); return; }
          var remote = Number(f.version) || 0, mine = Number(vm.selForm.version) || 0;
          if (remote <= mine) { ElementPlus.ElMessage.success('片段引用已是最新版本 v' + (mine || '-')); return; }
          return ElementPlus.ElMessageBox.confirm(
            '片段「' + text(f.title) + '」已更新到 v' + remote + ' (当前引用 v' + (mine || '-') + '), 是否刷新引用信息?',
            '发现新版本', { type: 'info', confirmButtonText: '更新引用', cancelButtonText: '暂不更新' }
          ).then(function () {
            vm.applySelPatch({ version: String(remote), title: text(f.title) });
            HIS.notifySuccess('片段引用已更新到 v' + remote);
          }).catch(function () { /* 暂不更新 */ });
        }).catch(function (e) { if (e && e !== 'cancel' && e !== 'close' && e.message) { HIS.notifyError(e); } })
          .finally(function () { vm.fragChecking = false; });
      },
      /* 更换片段: 浏览片段库(与编辑器工具栏同源接口), 选定后整块刷新引用 attrs */
      openFragPick: function () { this.fragDlg = true; this.fragPick = null; this.loadFragList(); },
      loadFragList: function () {
        var vm = this;
        vm.fragLoading = true;
        HIS.get('/api/his/emr/fragment/list?keyword=' + encodeURIComponent(text(vm.fragKeyword).trim()) + '&page=1&size=100').then(function (d) {
          vm.fragList = (d && d.records) || (Array.isArray(d) ? d : []);
        }).catch(function (e) { vm.fragList = []; HIS.notifyError(e); }).finally(function () { vm.fragLoading = false; });
      },
      confirmFragReplace: function () {
        var vm = this, s = vm.fragPick;
        if (!s) { ElementPlus.ElMessage.warning('请先在列表中选择片段'); return; }
        var title = text(s.title) || text(s.code);
        if (vm.applySelPatch({ fragmentId: HIS.id(s.id), sourceTemplateId: '', version: s.version == null ? '1' : String(s.version), title: title })) {
          HIS.notifySuccess('已更换为片段「' + title + '」');
          vm.fragDlg = false;
        }
      },

      /* ===== 母板锁定章节清单 ===== */
      toggleLockKey: function (key) {
        key = text(key);
        if (!key) { ElementPlus.ElMessage.warning('该章节未设置 sectionKey, 无法加入锁定清单'); return; }
        var i = this.lockedKeys.indexOf(key);
        var on = i < 0;
        if (on) { this.lockedKeys.push(key); } else { this.lockedKeys.splice(i, 1); }
        this.lockedDirty = true;
        this.syncSectionLockAttr(key, on);
      },
      /* 清单变更时同步文档中同名章节节点的 locked 视觉标记 */
      syncSectionLockAttr: function (key, locked) {
        var vm = this, ed = vm._editor && vm._editor.editor;
        if (!ed) { return; }
        var tr = null;
        ed.state.doc.descendants(function (n, pos) {
          if (n.type.name === 'emrSection' && text(n.attrs.sectionKey) === key) {
            tr = tr || ed.state.tr;
            tr.setNodeMarkup(pos, undefined, Object.assign({}, n.attrs, { locked: locked }));
          }
        });
        if (tr) { ed.view.dispatch(tr); }
      }
    },
    template: [
      '<div class="page-card cd-fill etd-shell">',
      '  <div class="page-title">病历模板设计器 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">A4 所见即所得 · 结构化数据元 · 三级继承</span> <el-tag size="small" effect="plain" :type="isLead?\'success\':\'info\'" style="margin-left:6px;">{{ isLead ? \'牵头机构 · 可维护全院\' : \'院内成员\' }}</el-tag></div>',
      /* ===== 顶栏: 层级过滤 / 模板选择 / 视图模式 ===== */
      '  <div class="toolbar etd-top">',
      '    <el-select v-model="scopeFilter" size="small" style="width:112px">',
      '      <el-option v-for="f in levelFilters" :key="f.v" :label="f.l" :value="f.v"></el-option>',
      '    </el-select>',
      '    <el-select v-model="currentTplId" size="small" filterable clearable placeholder="选择模板…" :loading="tplLoading" style="width:280px">',
      '      <el-option v-for="o in tplOptions" :key="o.id" :label="o.label + \' (\' + levelLabel(o.level) + \')\'" :value="o.id"></el-option>',
      '    </el-select>',
      '    <el-button size="small" @click="fetchTemplates">刷新</el-button>',
      '    <el-radio-group v-model="editorMode" size="small" @change="onModeChange">',
      '      <el-radio-button v-for="m in editorModes" :key="m.v" :label="m.v">{{ m.l }}</el-radio-button>',
      '    </el-radio-group>',
      '    <el-tag v-if="current && current.parentTemplateId" size="small" effect="plain" type="info">继承自: {{ parentName }}</el-tag>',
      '  </div>',
      /* ===== 顶栏: 操作按钮 ===== */
      '  <div class="toolbar etd-top">',
      '    <el-button size="small" type="primary" @click="openNew">新建模板</el-button>',
      '    <el-button size="small" @click="openDsCreate">从数据集创建</el-button>',
      '    <el-button size="small" type="primary" plain :disabled="!current || saving" :loading="saving" @click="save">保存</el-button>',
      '    <el-button size="small" :disabled="!current" @click="openSaveAs">另存为</el-button>',
      '    <el-button size="small" type="danger" plain :disabled="!current" @click="removeTpl">删除</el-button>',
      '    <el-button size="small" type="warning" plain :disabled="!current || Number(current.scopeLevel)===2" @click="propagate">传播更新</el-button>',
      '    <el-button size="small" @click="doPrint">打印预览</el-button>',
      /* ---- 高级版: 版本 / 审批 / 修订 / 批注 ---- */
      '    <el-divider direction="vertical"></el-divider>',
      '    <el-button size="small" :disabled="!current" @click="openVerDrawer">版本历史</el-button>',
      '    <el-tag v-if="current" size="small" :type="pubTag(pubStatus)" effect="plain">{{ pubLabel(pubStatus) }}</el-tag>',
      '    <el-button v-if="current && pubStatus!==1" size="small" type="primary" plain :loading="approvalBusy" @click="submitReview">提交审核</el-button>',
      '    <template v-if="current && pubStatus===1">',
      '      <el-button v-if="canReview" size="small" type="success" plain :loading="approvalBusy" @click="approveReview">审核发布</el-button>',
      '      <el-button v-if="canReview" size="small" type="danger" plain :loading="approvalBusy" @click="rejectReview">驳回</el-button>',
      '      <el-button size="small" plain :loading="approvalBusy" @click="retractReview">撤回草稿</el-button>',
      '    </template>',
      '    <el-switch v-model="trackOn" active-text="修订" size="small" style="margin:0 6px" @change="toggleTrack" :disabled="!current"></el-switch>',
      '    <template v-if="trackOn"><el-button size="small" type="success" plain @click="acceptAllTrack">接受全部</el-button><el-button size="small" plain @click="rejectAllTrack">拒绝全部</el-button></template>',
      '    <el-badge :value="annoCount" :hidden="!annoCount" style="margin-left:6px"><el-button size="small" :disabled="!current" @click="rightTab===\'anno\' ? (rightTab=\'props\', unmountAnno()) : mountAnno()">批注</el-button></el-badge>',
      '    <el-input v-model="changeSummary" size="small" placeholder="变更说明(记入版本历史)" style="width:180px;margin-left:6px;"></el-input>',
      '  </div>',
      '  <el-alert v-if="legacyConverted" class="etd-legacy-alert" type="warning" :closable="false" show-icon title="已将历史字段画布模板转换为类 Word 文档；保存后将持久化新文档，字段标识和原始属性会完整保留。"></el-alert>',
      /* ===== 三栏主体 ===== */
      '  <div class="etd-main">',
      /* ---- 左栏: 数据集元素树 ---- */
      '    <div class="etd-left">',
      '      <el-radio-group v-model="leftTab" size="small" class="etd-left-tabs"><el-radio-button label="components">插入组件</el-radio-button><el-radio-button label="dataset">数据元库</el-radio-button></el-radio-group>',
      '      <template v-if="leftTab===\'components\'">',
      '        <div class="etd-component-group" v-for="g in componentGroups" :key="g.title"><div class="etd-component-title">{{ g.title }}</div><div class="etd-component-grid"><el-button v-for="it in g.items" :key="it.k" size="small" plain @click="insertComponent(it.k)">{{ it.l }}</el-button></div></div>',
      '        <div class="etd-tip">组件插入当前光标位置；选中组件后在右侧配置业务属性。</div>',
      '      </template>',
      '      <template v-else>',
      '      <el-select v-model="datasetId" size="small" filterable clearable placeholder="选择数据集…" :loading="dsListLoading" @change="onDatasetChange" style="width:100%">',
      '        <el-option v-for="o in dsOptions" :key="o.id" :label="o.label" :value="o.id"></el-option>',
      '      </el-select>',
      '      <div class="etd-tree" v-loading="treeLoading">',
      '        <div class="etd-hint" v-if="!datasetId" style="text-align:center;padding:30px 0;">选择数据集后展示 章节 / 小节 / 数据元</div>',
      '        <el-tree v-else :data="treeData" node-key="id" default-expand-all :expand-on-click-node="false" :props="{ children: \'children\', label: \'label\' }">',
      '          <template #default="{ data }">',
      '            <span class="etd-tn" :class="{ \'etd-tn--element\': data.kind===\'element\' }" :draggable="data.kind===\'element\'" @dragstart="onElDragStart($event, data)" @dragend="onElDragEnd" @dblclick="onElDblClick(data)">',
      '              <span class="etd-tn-name">{{ data.label }}</span>',
      '              <template v-if="data.kind===\'element\'">',
      '                <el-tag size="small" effect="plain" type="info">{{ vtLabel(data.el.fieldType) }}</el-tag>',
      '                <el-tag size="small" effect="plain" type="danger" v-if="Number(data.el.required)===1">必填</el-tag>',
      '                <span class="etd-tn-key">{{ data.el.fieldKey }}</span>',
      '              </template>',
      '              <span v-else class="etd-tn-key">{{ data.kind===\'chapter\' ? data.chapterKey : data.sectionKey }}</span>',
      '            </span>',
      '          </template>',
      '        </el-tree>',
      '      </div>',
      '      <div class="etd-tip">数据元拖拽到中间编辑器可插入到指定位置；双击插入到光标处。</div>',
      '      </template>',
      '    </div>',
      /* ---- 中栏: Tiptap 编辑器 ---- */
      '    <div class="etd-mid">',
      '      <div class="etd-toolbar-host" ref="toolbarHost"></div>',
      '      <div class="etd-canvasbar"><b>A4 {{ printConfig.orientation===\'landscape\' ? \'横向\' : \'纵向\' }}</b><el-button-group><el-button size="small" @click="setZoom(.75)">75%</el-button><el-button size="small" @click="setZoom(.9)">90%</el-button><el-button size="small" @click="setZoom(1)">100%</el-button><el-button size="small" @click="setZoom(1.25)">125%</el-button></el-button-group><el-button size="small" @click="fitWidth">适合宽度</el-button><span class="etd-spacer"></span><span class="etd-page-state">缩放 {{ Math.round(zoom*100) }}% · {{ pageCount }} 页</span><el-tag v-if="paginationDiagnostics.length" size="small" type="danger">{{ paginationDiagnostics.length }} 项越界</el-tag></div>',
      '      <div class="etd-editor-host" ref="editorHost" v-loading="editorLoading" :class="{ \'etd-drag-over\': dragOver }" @click="onEditorClick" @dragover.prevent.capture="onDragOver" @dragleave="onDragLeave" @drop.capture="onDropCapture"></div>',
      '    </div>',
      /* ---- 右栏: 属性面板 / 批注面板(高级版标签页) ---- */
      '    <div class="etd-right">',
      '      <el-radio-group v-model="rightTab" size="small" style="width:100%;margin-bottom:6px;display:flex;">',
      '        <el-radio-button label="props" style="flex:1;">属性</el-radio-button>',
      '        <el-radio-button label="anno" style="flex:1;">批注{{ annoCount ? \' (\' + annoCount + \')\' : \'\' }}</el-radio-button>',
      '      </el-radio-group>',
      '      <template v-if="rightTab===\'props\'">',
      /* 模板信息 */
      '      <div class="etd-card">',
      '        <div class="etd-card-hd">模板信息</div>',
      '        <template v-if="current">',
      '          <el-form class="etd-form" label-width="64px" size="small">',
      '            <el-form-item label="名称"><el-input v-model="tplFormName" placeholder="模板名称(保存时提交)"></el-input></el-form-item>',
      '          </el-form>',
      '          <div class="etd-meta">',
      '            <div>编码: <b>{{ current.templateCode }}</b></div>',
      '            <div>层级: <el-tag size="small" effect="plain" :type="levelTag(current.scopeLevel)">{{ levelLabel(current.scopeLevel) }}</el-tag>',
      '              <span v-if="current.version != null"> 版本 v{{ current.version }}</span>',
      '              <el-tag size="small" style="margin-left:6px;" :type="Number(current.status)===1?\'success\':\'info\'">{{ Number(current.status)===1?\'启用\':\'停用\' }}</el-tag>',
      '              <el-tag size="small" style="margin-left:4px;" :type="pubTag(pubStatus)">{{ pubLabel(pubStatus) }}</el-tag>',
      '            </div>',
      '            <div v-if="current.parentTemplateId">继承自: <b>{{ parentName }}</b></div>',
      '            <div v-if="current.datasetId">数据集ID: {{ current.datasetId }}</div>',
      '          </div>',
      '        </template>',
      '        <div v-else class="etd-hint">未选择模板 — 从顶部下拉选择, 或使用「新建模板 / 从数据集创建」</div>',
      '      </div>',
      /* 选中节点属性 */
      '      <div class="etd-card">',
      '        <div class="etd-card-hd">节点属性 <span v-if="sel" class="etd-hint">({{ selKindLabel }})</span></div>',
      '        <div v-if="!sel" class="etd-hint">在编辑器中点击 数据元 / 章节 / 宏变量 / 条件块 / 医学图示 / 片段 可编辑其属性; 点击正文空白处回到本面板。</div>',
      '        <el-form v-else-if="sel.kind===\'emrField\'" class="etd-form" label-width="72px" size="small">',
      '          <el-form-item label="字段标识"><el-input v-model="selForm.fieldKey" @change="applySel(\'fieldKey\')"></el-input></el-form-item>',
      '          <el-form-item label="字段名称"><el-input v-model="selForm.fieldName" @change="applySel(\'fieldName\')"></el-input></el-form-item>',
      '          <el-form-item label="值类型"><el-select v-model="selForm.valueType" style="width:100%" @change="applySel(\'valueType\')"><el-option v-for="vt in valueTypes" :key="vt.v" :label="vt.l" :value="vt.v"></el-option></el-select></el-form-item>',
      '          <el-form-item label="字典来源" v-if="selIsDict"><el-input v-model="selForm.dictSource" placeholder="如 std_gender / diag" @change="applySel(\'dictSource\')"></el-input></el-form-item>',
      '          <el-form-item :label="selForm.valueType===\'textarea\' ? \'快捷短语\' : \'选项\'" v-if="selHasOpts"><el-input v-model="selForm.options" type="textarea" :rows="3" :placeholder="selForm.valueType===\'textarea\' ? \'每行一条快捷短语，点击后仍可编辑\' : \'逗号分隔, 如: 是,否\'" @change="applySel(\'options\')"></el-input></el-form-item>',
      '          <el-form-item label="占位提示"><el-input v-model="selForm.placeholder" @change="applySel(\'placeholder\')"></el-input></el-form-item>',
      '          <el-form-item label="单位" v-if="selForm.valueType!==\'textarea\' && selForm.valueType!==\'vitals\'"><el-input v-model="selForm.unit" placeholder="如 mmHg" @change="applySel(\'unit\')"></el-input></el-form-item>',
      '          <el-form-item label="默认宏"><el-select v-model="selForm.defaultMacro" filterable clearable allow-create default-first-option style="width:100%" placeholder="仅空字段自动带入" @change="applySel(\'defaultMacro\')"><el-option v-for="p in macroPresets" :key="p.code" :label="p.label + \'（\' + p.code + \'）\'" :value="p.code"></el-option></el-select></el-form-item>',
      '          <el-form-item label="必填"><el-switch v-model="selForm.required" @change="applySel(\'required\')"></el-switch></el-form-item>',
      '          <el-form-item label="只读"><el-switch v-model="selForm.readonly" @change="applySel(\'readonly\')"></el-switch></el-form-item>',
      '          <el-form-item label="防复制"><el-switch v-model="selForm.noCopy" @change="applySel(\'noCopy\')"></el-switch></el-form-item>',
      '        </el-form>',
      '        <el-form v-else-if="sel.kind===\'emrSection\'" class="etd-form" label-width="72px" size="small">',
      '          <el-form-item label="章节标识"><el-input v-model="selForm.sectionKey" placeholder="保存时双写为后端 attrs.key" @change="applySel(\'sectionKey\')"></el-input></el-form-item>',
      '          <el-form-item label="标题"><el-input v-model="selForm.title" @change="applySel(\'title\')"></el-input></el-form-item>',
      '          <el-form-item label="编辑模式"><el-select v-model="selForm.editMode" style="width:100%" @change="applySel(\'editMode\')"><el-option v-for="m in editModes" :key="m.v" :label="m.l" :value="m.v"></el-option></el-select></el-form-item>',
      '          <el-form-item label="可折叠"><el-switch v-model="selForm.collapsible" @change="applySel(\'collapsible\')"></el-switch></el-form-item>',
      '          <el-form-item label="不打印"><el-switch v-model="selForm.printHidden" @change="applySel(\'printHidden\')"></el-switch></el-form-item>',
      '          <el-form-item label="章节锁定"><el-switch v-model="selForm.locked" @change="applySel(\'locked\')"></el-switch></el-form-item>',
      '          <el-form-item label="传播"><el-button size="small" style="width:100%" :type="lockInList?\'warning\':\'default\'" :disabled="!selForm.sectionKey" @click="toggleLockKey(selForm.sectionKey)">{{ lockInList ? \'移出母板锁定清单\' : \'加入母板锁定清单\' }}</el-button></el-form-item>',
      '        </el-form>',
      '        <el-form v-else-if="sel.kind===\'emrMacro\'" class="etd-form" label-width="72px" size="small">',
      '          <el-form-item label="宏代码"><el-select v-model="selForm.macroCode" filterable allow-create default-first-option style="width:100%" @change="applySel(\'macroCode\')"><el-option v-for="p in macroPresets" :key="p.code" :label="p.label + \' (\' + p.code + \')\'" :value="p.code"></el-option></el-select></el-form-item>',
      '          <el-form-item label="数据来源"><el-input v-model="selForm.dataSource" placeholder="patient / visit / system" @change="applySel(\'dataSource\')"></el-input></el-form-item>',
      '        </el-form>',
      '        <el-form v-else-if="sel.kind===\'emrConditionalBlock\'" class="etd-form" label-width="72px" size="small">',
      '          <el-form-item label="数据元"><el-select v-model="selForm.conditionFieldKey" filterable clearable style="width:100%" placeholder="选择本文档中的数据元" @change="applySel(\'conditionFieldKey\')"><el-option v-for="f in documentFields" :key="f.fieldKey" :label="f.fieldName + \'（\' + f.fieldKey + \'）\'" :value="f.fieldKey"></el-option></el-select></el-form-item>',
      '          <el-form-item v-if="!documentFields.length"><span class="etd-hint">当前文档暂无数据元, 请先插入数据元再配置条件</span></el-form-item>',
      '          <el-form-item label="运算符"><el-select v-model="selForm.conditionOperator" style="width:100%" @change="onCondOperatorChange"><el-option v-for="op in condOperators" :key="op.v" :label="op.l + \'(\' + op.v + \')\'" :value="op.v"></el-option></el-select></el-form-item>',
      '          <el-form-item label="比较值" v-if="selForm.conditionOperator!==\'empty\' && selForm.conditionOperator!==\'notEmpty\'"><el-input v-model="selForm.conditionValue" placeholder="与数据元值比较的内容" @change="applySel(\'conditionValue\')"></el-input></el-form-item>',
      '          <el-form-item label="默认可见"><el-switch v-model="selForm.visible" @change="applySel(\'visible\')"></el-switch></el-form-item>',
      '        </el-form>',
      '        <el-form v-else-if="sel.kind===\'emrDrawing\'" class="etd-form" label-width="72px" size="small">',
      '          <el-form-item label="标题"><el-input v-model="selForm.title" placeholder="图示标题, 如: 体表标记" @change="applySel(\'title\')"></el-input></el-form-item>',
      '          <el-form-item label="图示预览"><div class="etd-draw-preview" v-if="selForm.hasSvg" v-html="selDrawingSvg"></div><div v-else class="etd-hint">暂无图示内容 — 点击「编辑图示」开始绘制</div></el-form-item>',
      '          <el-form-item label="操作"><div class="etd-act-row"><el-button size="small" type="primary" plain @click="editDrawing">编辑图示</el-button><el-button size="small" type="danger" plain :disabled="!selForm.hasSvg" @click="clearDrawing">清除图示</el-button></div></el-form-item>',
      '        </el-form>',
      '        <template v-else-if="sel.kind===\'emrFragment\'">',
      '          <div class="etd-meta">',
      '            <div>标题: <b>{{ selForm.title || \'-\' }}</b></div>',
      '            <div>片段ID: <b>{{ selForm.fragmentId || \'-\' }}</b></div>',
      '            <div>版本: <el-tag v-if="selForm.version" size="small" effect="plain">v{{ selForm.version }}</el-tag><span v-else class="etd-hint">-</span></div>',
      '            <div>来源模板: <b>{{ selForm.sourceTemplateId || \'-\' }}</b></div>',
      '          </div>',
      '          <div class="etd-act-row" style="margin-top:8px;"><el-button size="small" :loading="fragChecking" @click="checkFragmentUpdate">检查更新</el-button><el-button size="small" type="primary" plain @click="openFragPick">更换片段</el-button></div>',
      '          <div class="etd-hint" style="margin-top:6px;">引用片段在打印/上报时由服务端展开为完整内容。</div>',
      '        </template>',
      '        <div v-if="sel" style="margin-top:2px;"><el-button link type="danger" size="small" @click="removeSelNode">删除该节点</el-button></div>',
      '      </div>',
      /* 母板锁定章节清单 */
      '      <div class="etd-card">',
      '        <div class="etd-card-hd">母板锁定章节 <span class="etd-hint">(随「传播更新」下发子模板)</span></div>',
      '        <div v-if="!lockedKeys.length" class="etd-hint">未锁定章节 — 在编辑器点击章节节点后, 于节点属性中「加入母板锁定清单」</div>',
      '        <div v-else class="etd-tags">',
      '          <el-tag v-for="k in lockedKeys" :key="k" size="small" type="warning" closable @close="toggleLockKey(k)">{{ k }}</el-tag>',
      '        </div>',
      '      </div>',
      /* 结构化打印配置 */
      '      <div class="etd-card">',
      '        <div class="etd-card-hd">页面与打印</div>',
      '        <el-form class="etd-form" label-position="top" size="small">',
      '          <el-form-item label="纸张方向"><el-radio-group v-model="printConfig.orientation"><el-radio-button label="portrait">纵向</el-radio-button><el-radio-button label="landscape">横向</el-radio-button></el-radio-group></el-form-item>',
      '          <div class="etd-print-grid">',
      '            <el-form-item label="上边距(mm)"><el-input-number v-model="printConfig.margins.top" :min="0" :max="50" controls-position="right" style="width:100%"></el-input-number></el-form-item>',
      '            <el-form-item label="下边距(mm)"><el-input-number v-model="printConfig.margins.bottom" :min="0" :max="50" controls-position="right" style="width:100%"></el-input-number></el-form-item>',
      '            <el-form-item label="左边距(mm)"><el-input-number v-model="printConfig.margins.left" :min="0" :max="50" controls-position="right" style="width:100%"></el-input-number></el-form-item>',
      '            <el-form-item label="右边距(mm)"><el-input-number v-model="printConfig.margins.right" :min="0" :max="50" controls-position="right" style="width:100%"></el-input-number></el-form-item>',
      '          </div>',
      '          <el-form-item label="页眉"><el-switch v-model="printConfig.header.enabled"></el-switch><el-input v-if="printConfig.header.enabled" v-model="printConfig.header.content" style="margin-top:6px" placeholder="支持模板名称、患者信息和页码宏"></el-input></el-form-item>',
      '          <el-form-item label="页脚"><el-switch v-model="printConfig.footer.enabled"></el-switch><el-input v-if="printConfig.footer.enabled" v-model="printConfig.footer.content" style="margin-top:6px" placeholder="支持模板宏与页码宏"></el-input></el-form-item>',
      '          <el-form-item label="显示页码"><el-switch v-model="printConfig.showPageNumber"></el-switch></el-form-item>',
      '        </el-form>',
      '        <el-alert v-if="paginationDiagnostics.length" type="warning" :closable="false" show-icon :title="\'发现 \' + paginationDiagnostics.length + \' 项分页越界，请调整内容或页边距\'"></el-alert>',
      '        <el-collapse v-if="printScript" style="margin-top:8px"><el-collapse-item title="历史打印脚本（兼容保留）" name="legacy"><el-input v-model="printScript" type="textarea" :rows="4" class="etd-script"></el-input></el-collapse-item></el-collapse>',
      '        <div style="margin-top:8px;display:flex;align-items:center;gap:8px;"><el-button size="small" type="primary" plain @click="doPrint">逐页预览</el-button><span class="etd-hint">正式打印不输出诊断层。</span></div>',
      '      </div>',
      '      </template>',
      '      <div v-else class="etd-card etd-anno-card"><div class="etd-card-hd">批注与协作审阅</div><div ref="annoHost" class="etd-anno-host"></div></div>',
      '    </div>',
      '  </div>',
      /* ===== 新建模板 ===== */
      '  <el-dialog v-model="newDlg" title="新建模板" width="480px" append-to-body :close-on-click-modal="false">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;">新模板文档为空白结构, 创建后可在编辑器中自由编辑或从左侧数据集插入数据元。</el-alert>',
      '    <el-form label-width="84px" size="small">',
      '      <el-form-item label="模板名称"><el-input v-model="newForm.templateName" placeholder="如 心内科入院记录"></el-input></el-form-item>',
      '      <el-form-item label="模板编码"><el-input v-model="newForm.templateCode" placeholder="租户内唯一(默认自动生成)"></el-input></el-form-item>',
      '      <el-form-item label="归属层级">',
      '        <el-select v-model="newForm.scopeLevel" style="width:100%">',
      '          <el-option :value="0" label="全院(仅牵头机构管理员)" :disabled="!isLead"></el-option>',
      '          <el-option :value="1" label="科室(本科室共享)"></el-option>',
      '          <el-option :value="2" label="个人(仅本人)"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="父模板">',
      '        <el-select v-model="newForm.parentTemplateId" filterable clearable placeholder="可选: 继承母板(层级须更高)" style="width:100%">',
      '          <el-option v-for="o in parentCandidates" :key="o.id" :label="o.label + \' (\' + levelLabel(o.level) + \')\'" :value="o.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="关联数据集">',
      '        <el-select v-model="newForm.datasetId" filterable clearable placeholder="可选: 关联数据集" style="width:100%">',
      '          <el-option v-for="o in dsOptions" :key="o.id" :label="o.label" :value="o.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="newDlg=false">取消</el-button><el-button size="small" type="primary" :loading="newSaving" @click="submitNew">创建</el-button></template>',
      '  </el-dialog>',
      /* ===== 从数据集创建 ===== */
      '  <el-dialog v-model="dsDlg" title="从数据集创建模板" width="480px" append-to-body :close-on-click-modal="false">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;">数据集「{{ dsDataset ? dsDataset.name : \'-\' }}」将按 章节 → 小节 → 数据元 生成 Tiptap 文档结构(emrSection / emrField)。</el-alert>',
      '    <el-form label-width="84px" size="small">',
      '      <el-form-item label="模板名称"><el-input v-model="dsForm.name"></el-input></el-form-item>',
      '      <el-form-item label="归属层级">',
      '        <el-select v-model="dsForm.scopeLevel" style="width:100%">',
      '          <el-option :value="0" label="全院(仅牵头机构管理员)" :disabled="!isLead"></el-option>',
      '          <el-option :value="1" label="科室(本科室共享)"></el-option>',
      '          <el-option :value="2" label="个人(仅本人)"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="dsDlg=false">取消</el-button><el-button size="small" type="primary" :loading="dsSaving" @click="submitDsCreate">创建</el-button></template>',
      '  </el-dialog>',
      /* ===== 另存为 ===== */
      '  <el-dialog v-model="copyDlg" title="另存为新模板" width="480px" append-to-body :close-on-click-modal="false">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;">当前编辑器中的文档与母板锁定清单将复制到新模板。</el-alert>',
      '    <el-form label-width="84px" size="small">',
      '      <el-form-item label="模板名称"><el-input v-model="copyForm.templateName"></el-input></el-form-item>',
      '      <el-form-item label="模板编码"><el-input v-model="copyForm.templateCode" placeholder="租户内唯一(默认自动生成)"></el-input></el-form-item>',
      '      <el-form-item label="归属层级">',
      '        <el-select v-model="copyForm.scopeLevel" style="width:100%">',
      '          <el-option :value="0" label="全院(仅牵头机构管理员)" :disabled="!isLead"></el-option>',
      '          <el-option :value="1" label="科室(本科室共享)"></el-option>',
      '          <el-option :value="2" label="个人(仅本人)"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="copyDlg=false">取消</el-button><el-button size="small" type="primary" :loading="copySaving" @click="submitSaveAs">保存副本</el-button></template>',
      '  </el-dialog>',
      /* ===== 更换片段(浏览片段库, 与编辑器工具栏同源接口) ===== */
      '  <el-dialog v-model="fragDlg" title="更换片段引用" width="560px" append-to-body :close-on-click-modal="false">',
      '    <div class="etd-frag-search">',
      '      <el-input v-model="fragKeyword" size="small" clearable placeholder="按名称/编码检索" style="width:220px" @keyup.enter="loadFragList" @clear="loadFragList"></el-input>',
      '      <el-button size="small" type="primary" plain @click="loadFragList">搜索</el-button>',
      '    </div>',
      '    <el-table :data="fragList" v-loading="fragLoading" size="small" height="280" highlight-current-row @current-change="fragPick = $event">',
      '      <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="code" label="编码" min-width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="作用域" width="70"><template #default="s">{{ levelLabel(s.row.scopeLevel) }}</template></el-table-column>',
      '      <el-table-column label="版本" width="64"><template #default="s">{{ s.row.version == null ? \'-\' : \'v\' + s.row.version }}</template></el-table-column>',
      '    </el-table>',
      '    <div v-if="!fragLoading && !fragList.length" class="etd-hint">未找到可用片段, 可调整关键词后重新搜索</div>',
      '    <template #footer><el-button size="small" @click="fragDlg=false">取消</el-button><el-button size="small" type="primary" :disabled="!fragPick" @click="confirmFragReplace">更换</el-button></template>',
      '  </el-dialog>',
      /* ===== 版本历史抽屉(高级版): 列表 + 两版对比 + 回滚 ===== */
      '  <el-drawer v-model="verDrawer" title="版本历史" size="64%" :destroy-on-close="true">',
      '    <div class="etd-ver-pick">',
      '      <el-select v-model="verSelA" size="small" placeholder="旧版本…" style="width:280px" filterable>',
      '        <el-option v-for="v in verList" :key="\'a\'+v.id" :label="verLabel(v)" :value="verIdStr(v)"></el-option>',
      '      </el-select>',
      '      <span style="margin:0 6px">→</span>',
      '      <el-select v-model="verSelB" size="small" placeholder="新版本…" style="width:280px" filterable>',
      '        <el-option v-for="v in verList" :key="\'b\'+v.id" :label="verLabel(v)" :value="verIdStr(v)"></el-option>',
      '      </el-select>',
      '      <el-button size="small" type="primary" style="margin-left:8px" :disabled="!verSelA || !verSelB || verSelA===verSelB" @click="compareVersions">对比</el-button>',
      '    </div>',
      '    <div ref="verDiffHost" class="etd-ver-diff" v-if="verDiffOk"></div>',
      '    <div class="etd-ver-list-hd">版本时间线</div>',
      '    <el-timeline v-loading="verLoading" style="padding-left:4px;margin-top:8px;">',
      '      <el-timeline-item v-for="v in verList" :key="v.id" :timestamp="v.createTime || \'-\'" :type="v.operateType===\'rollback\' ? \"warning\" : (v.operateType===\'publish\' ? \'success\' : \'primary\')" placement="top">',
      '        <b>v{{ v.versionNo }}</b> · {{ { save: \'保存\', publish: \'发布\', rollback: \'回滚\' }[v.operateType] || v.operateType }} · {{ v.operatorName || \'-\' }}',
      '        <div style="color:#8994a5;font-size:12px;">{{ v.changeSummary || \'(无变更说明)\' }}</div>',
      '      </el-timeline-item>',
      '      <el-timeline-item v-if="!verLoading && !verList.length" timestamp="-" type="info">暂无历史版本 — 保存/发布/回滚后自动留存</el-timeline-item>',
      '    </el-timeline>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };
})();
