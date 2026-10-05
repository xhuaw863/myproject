/* EMR 结构化病历 · Tiptap 自定义扩展集(HIS.EmrExtensions) —— 病历编辑器的节点/标记定义层
 * ------------------------------------------------------------------
 * 依赖: tiptap-bundle(vendor, 经 TiptapLoader.ensure() 懒加载后 window.Tiptap 就绪)。
 *       本文件加载期不触碰 Tiptap(全部工厂在调用时才取 window.Tiptap), 可安全随 index.html 预载。
 * 扩展(9 个): EmrField 行内数据元(EP 控件 NodeView) | EmrSection 章节(折叠/锁定/打印隐藏)
 *   EmrTable 官方 Table + tableId/rowIds 留痕属性 | EmrMacro 宏变量 | EmrPrintControl 打印隐藏标记
 *   EmrFragment 片段引用 | EmrConditionalBlock 条件显示块 | EmrPageBreak 分页符 | EmrDrawing 医学图示
 * 值域检索复用 emr-field.js 的 HIS.emrLoadDict(同页多字段共享候选缓存)。
 * 用法: const ext = HIS.EmrExtensions.buildAll(await TiptapLoader.ensure());
 * 注册: HIS.EmrExtensions(须在 emr-editor.js / 各 views / app.js 之前加载)。
 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});

  /* ================= 常量 ================= */
  const VALUE_TYPES = ['text', 'textarea', 'number', 'date', 'select', 'multiselect', 'checkbox', 'dict', 'vitals'];
  const EDIT_MODE_LABEL = { form: '表单', free: '自由', mixed: '混合' };
  const OPERATORS = [
    { v: 'eq', l: '等于' }, { v: 'ne', l: '不等于' }, { v: 'contains', l: '包含' }, { v: 'empty', l: '为空' }, { v: 'notEmpty', l: '不为空' }
  ];

  /* ================= 工具 ================= */
  function needTiptap(t) {
    const T = t || window.Tiptap;
    if (!T) { throw new Error('Tiptap 未加载: 请先 await TiptapLoader.ensure()'); }
    return T;
  }
  function jstr(v) { try { return v == null ? null : JSON.stringify(v); } catch (e) { return null; } }
  function jparse(s, fb) {
    if (s == null || s === '') { return fb; }
    try { const v = JSON.parse(s); return v == null ? fb : v; } catch (e) { return fb; }
  }
  function isBlankValue(v) {
    return v == null || v === '' || (Array.isArray(v) && v.length === 0);
  }
  /* 宽松相等: 空值(null/''/[])互通, 避免控件初始化把 null 洗成 '' 产生幽灵事务 */
  function valuesEqual(a, b) {
    if (a === b) { return true; }
    const ba = isBlankValue(a), bb = isBlankValue(b);
    if (ba || bb) { return ba && bb; }
    if (Array.isArray(a) && Array.isArray(b)) {
      return a.length === b.length && a.every(function (x, i) { return x === b[i]; });
    }
    return false;
  }
  function formatFieldValue(v) {
    if (v == null) { return ''; }
    if (Array.isArray(v)) { return v.map(function (x) { return x == null ? '' : String(x); }).join('、'); }
    if (typeof v === 'object') { try { return JSON.stringify(v); } catch (e) { return ''; } }
    return String(v);
  }
  /* options 规范化: [{label,value}] 数组 / JSON 字符串 / 逗号分隔字符串(旧契约) */
  function parseFieldOptions(o) {
    if (!o) { return []; }
    if (Array.isArray(o)) {
      return o.map(function (x) {
        return typeof x === 'object' ? { label: String(x.label != null ? x.label : x.value), value: x.value != null ? x.value : x.label } : { label: String(x), value: String(x) };
      });
    }
    const s = String(o);
    if (s.replace(/^\s+/, '').indexOf('[') === 0) { const arr = jparse(s, null); if (Array.isArray(arr)) { return parseFieldOptions(arr); } }
    return s.split(/[\r\n,，;；]+/).map(function (x) { return x.trim(); }).filter(Boolean).map(function (x) { return { label: x, value: x }; });
  }
  /* 生命体征以可读字符串入 structure，兼容既有 SOAP/打印/医保读侧；控件内拆成 T/P/R/BP 四项编辑。 */
  function parseVitals(v) {
    const out = { temperature: '', pulse: '', respiration: '', systolicBp: '', diastolicBp: '' };
    if (v && typeof v === 'object') {
      out.temperature = v.temperature == null ? '' : String(v.temperature);
      out.pulse = v.pulse == null ? '' : String(v.pulse);
      out.respiration = v.respiration == null ? '' : String(v.respiration);
      out.systolicBp = v.systolicBp == null ? (v.systolic == null ? '' : String(v.systolic)) : String(v.systolicBp);
      out.diastolicBp = v.diastolicBp == null ? (v.diastolic == null ? '' : String(v.diastolic)) : String(v.diastolicBp);
      return out;
    }
    const s = String(v == null ? '' : v);
    const pick = function (re) { const m = re.exec(s); return m ? m[1] : ''; };
    out.temperature = pick(/(?:^|\s)T\s*[:：]?\s*(\d+(?:\.\d+)?)/i);
    out.pulse = pick(/(?:^|\s)P\s*[:：]?\s*(\d+)/i);
    out.respiration = pick(/(?:^|\s)R\s*[:：]?\s*(\d+)/i);
    const bp = /(?:^|\s)BP\s*[:：]?\s*(\d*)\s*\/\s*(\d*)/i.exec(s);
    if (bp) { out.systolicBp = bp[1] || ''; out.diastolicBp = bp[2] || ''; }
    return out;
  }
  function formatVitals(v) {
    const a = v || {};
    const parts = [];
    if (String(a.temperature || '').trim()) { parts.push('T ' + String(a.temperature).trim() + '℃'); }
    if (String(a.pulse || '').trim()) { parts.push('P ' + String(a.pulse).trim() + '次/分'); }
    if (String(a.respiration || '').trim()) { parts.push('R ' + String(a.respiration).trim() + '次/分'); }
    if (String(a.systolicBp || '').trim() || String(a.diastolicBp || '').trim()) {
      parts.push('BP ' + String(a.systolicBp || '').trim() + '/' + String(a.diastolicBp || '').trim() + 'mmHg');
    }
    return parts.join('　');
  }

  /* ================= 条件求值(条件块 NodeView 与打印预览共用) ================= */
  function evaluateCondition(attrs, fieldsMap) {
    const a = attrs || {};
    const v = fieldsMap ? fieldsMap[a.conditionFieldKey] : undefined;
    const cv = a.conditionValue == null ? '' : String(a.conditionValue);
    const sv = v == null ? '' : (Array.isArray(v) ? v.join(',') : String(v));
    switch (a.conditionOperator) {
      case 'eq': return sv === cv;
      case 'ne': return sv !== cv;
      case 'contains': return Array.isArray(v) ? v.some(function (x) { return String(x) === cv; }) : (cv !== '' && sv.indexOf(cv) >= 0);
      case 'empty': return sv === '';
      case 'notEmpty': return sv !== '';
      default: return true;
    }
  }
  function collectFieldValuesFromDoc(doc) {
    const map = {};
    if (!doc || typeof doc.descendants !== 'function') { return map; }
    doc.descendants(function (n) {
      if (n.type.name === 'emrField' && n.attrs.fieldKey) { map[n.attrs.fieldKey] = n.attrs.value; }
    });
    return map;
  }

  /* ================= 设计模式检测(条件块暗显用: editor.options.emrDesignMode 或宿主 .emr-doc--design 类) ================= */
  function isDesignMode(editor) {
    if (!editor) { return false; }
    if (editor.options && editor.options.emrDesignMode === true) { return true; }
    try {
      const host = editor.view && editor.view.dom ? editor.view.dom.closest('.emr-doc') : null;
      return !!(host && host.classList.contains('emr-doc--design'));
    } catch (e) { return false; }
  }

  /* ================= 字段控件工厂(EP 迷你 Vue 应用, 无构建环境 NodeView 标准姿势) =================
   * spec: { attrs, isDisabled():bool, onValue(v) } → { app, vm }  宿主调用 app.unmount() 清理 */
  function createFieldControl(hostEl, spec) {
    const a = (spec && spec.attrs) || {};
    const vt = String(a.valueType || 'text');
    const isMulti = vt === 'multiselect' || vt === 'checkbox';
    const isDict = !!a.dictSource && (vt === 'select' || vt === 'multiselect' || vt === 'dict');
    const localOpts = parseFieldOptions(a.options);
    const dictLoader = (typeof HIS.emrLoadDict === 'function') ? HIS.emrLoadDict : null;
    const hasVue = !!(window.Vue && window.Vue.createApp);
    const hasEp = !!window.ElementPlus;

    function initVal(v) {
      if (isMulti) {
        if (Array.isArray(v)) { return v.slice(); }
        if (typeof v === 'string' && v.replace(/^\s+/, '').indexOf('[') === 0) { const arr = jparse(v, null); if (Array.isArray(arr)) { return arr; } }
        return [];
      }
      return v == null ? (vt === 'number' ? null : '') : v;
    }
    /* 无 Vue/EP 时兜底为原生控件, 保证引擎在极端环境不崩 */
    const tplNative = vt === 'textarea'
      ? '<textarea class="emr-f-native emr-f-native--textarea" v-model="val" :disabled="dis()" :placeholder="ph" @input="onNative"></textarea>'
      : '<input class="emr-f-native" v-model="val" :disabled="dis()" @input="onNative"/>';
    const tplMap = {
      text: '<el-input v-model="val" size="small" style="width:170px" :disabled="dis()" :placeholder="ph" @change="commit"></el-input>',
      textarea: '<span class="emr-f-textarea" :class="{ \'is-focus\': focused }"><el-input v-model="val" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" resize="vertical" :disabled="dis()" :placeholder="ph" @focus="focused = true" @blur="focused = false" @input="commit"></el-input><span v-if="candidates.length" class="emr-f-quick"><span class="emr-f-quick-label">快捷填入</span><el-button v-for="o in candidates" :key="o.value" size="small" plain :disabled="dis()" :title="o.label" @click="useQuick(o.value)">{{ o.label }}</el-button></span><span v-if="candidates.length" class="emr-f-quick-menu"><el-dropdown trigger="click" :disabled="dis()" popper-class="emr-quick-popper" @command="useQuick"><el-button size="small" plain :disabled="dis()">常用语 ▾</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="o in candidates" :key="o.value" :command="o.value">{{ o.label }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></span></span>',
      number: '<el-input-number v-model="val" size="small" controls-position="right" style="width:132px" :disabled="dis()" @change="commit"></el-input-number>',
      date: '<el-date-picker v-model="val" type="date" value-format="YYYY-MM-DD" size="small" style="width:164px" :disabled="dis()" :placeholder="ph || \'年-月-日\'" @change="commit"></el-date-picker>',
      select: '<el-select v-model="val" size="small" filterable clearable style="width:200px" :disabled="dis()" :loading="loading" :remote="isRemote" :remote-method="search" :placeholder="ph || \'请选择\'" @change="commit"><el-option v-for="o in candidates" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select>',
      dict: '<el-select v-model="val" size="small" filterable clearable style="width:200px" :disabled="dis()" :loading="loading" :remote="isRemote" :remote-method="search" :placeholder="ph || \'输入检索\'" @change="commit"><el-option v-for="o in candidates" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select>',
      multiselect: '<el-select v-model="val" size="small" multiple filterable clearable collapse-tags collapse-tags-tooltip style="width:264px" :disabled="dis()" :loading="loading" :remote="isRemote" :remote-method="search" :placeholder="ph || \'可多选\'" @change="commit"><el-option v-for="o in candidates" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select>',
      checkbox: '<el-checkbox-group v-model="val" :disabled="dis()" @change="commit"><el-checkbox v-for="o in candidates" :key="o.value" :label="o.value">{{ o.label }}</el-checkbox></el-checkbox-group>',
      vitals: '<span class="emr-f-vitals"><label>T<el-input v-model="vitals.temperature" size="small" placeholder="36.5" :disabled="dis()" @input="commitVitals"></el-input><i>℃</i></label><label>P<el-input v-model="vitals.pulse" size="small" placeholder="76" :disabled="dis()" @input="commitVitals"></el-input><i>次/分</i></label><label>R<el-input v-model="vitals.respiration" size="small" placeholder="18" :disabled="dis()" @input="commitVitals"></el-input><i>次/分</i></label><label>BP<el-input v-model="vitals.systolicBp" size="small" placeholder="120" :disabled="dis()" @input="commitVitals"></el-input><b>/</b><el-input v-model="vitals.diastolicBp" size="small" placeholder="80" :disabled="dis()" @input="commitVitals"></el-input><i>mmHg</i></label><el-button size="small" plain :disabled="dis()" title="仅在已测量且确认生命体征正常时使用" @click="fillNormalVitals">填正常参考值</el-button></span>'
    };
    let tpl = tplMap[vt] || tplMap.text;
    if (!hasVue || !hasEp) { tpl = tplNative; }
    else if (a.unit && vt !== 'textarea' && vt !== 'vitals') { tpl = '<span class="emr-f-ctl-in">' + tpl + '<span class="emr-f-unit">' + String(a.unit).replace(/</g, '&lt;') + '</span></span>'; }

    const app = Vue.createApp({
      data() {
        return { val: initVal(a.value), vitals: parseVitals(a.value), opts: localOpts, remote: [], loading: false, focused: false, isRemote: isDict, ph: a.placeholder || '', _dis: false };
      },
      computed: {
        candidates: function () { return this.remote.length ? this.remote : this.opts; }
      },
      watch: {
        val: {
          deep: true,
          handler: function (v) {
            if (spec.onValue) { spec.onValue(isMulti ? (Array.isArray(v) ? v.slice() : []) : v); }
          }
        }
      },
      created: function () {
        const vm = this;
        if (isDict && dictLoader) {
          vm.loading = true;
          Promise.resolve(dictLoader(a.dictSource, '')).then(function (list) {
            vm.remote = (list || []).map(function (o) { return { label: o.name, value: o.name }; });
          }).catch(function () { vm.remote = []; }).then(function () { vm.loading = false; });
        }
      },
      methods: {
        dis: function () { return !!(spec.isDisabled && spec.isDisabled()); },
        commit: function () { /* v-model 已驱动 watch, 此处仅保留显式 input/change 钩子位 */ },
        onNative: function () { /* 原生兜底输入 */ },
        useQuick: function (phrase) {
          const p = String(phrase == null ? '' : phrase).trim();
          const old = String(this.val == null ? '' : this.val).trim();
          if (!p || old.indexOf(p) >= 0) { return; }
          this.val = old ? old + '\n' + p : p;
        },
        commitVitals: function () { this.val = formatVitals(this.vitals); },
        fillNormalVitals: function () {
          this.vitals = { temperature: '36.5', pulse: '76', respiration: '18', systolicBp: '120', diastolicBp: '80' };
          this.commitVitals();
        },
        search: function (kw) {
          const vm = this;
          if (!isDict || !dictLoader) { return; }
          vm.loading = true;
          Promise.resolve(dictLoader(a.dictSource, kw)).then(function (list) {
            vm.remote = (list || []).map(function (o) { return { label: o.name, value: o.name }; });
          }).catch(function () {}).then(function () { vm.loading = false; });
        },
        setValue: function (v) {
          this.val = initVal(v);
          if (vt === 'vitals') { this.vitals = parseVitals(v); }
        }
      },
      template: tpl
    });
    if (hasEp) { app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn }); }
    const vm = app.mount(hostEl);
    return { app: app, vm: vm };
  }

  /* ================= EmrField NodeView(行内数据元) ================= */
  function createFieldNodeView(ctx) {
    let node = ctx.node;
    const editor = ctx.editor;
    const getPos = ctx.getPos;
    const dom = document.createElement('span');
    dom.setAttribute('data-emr-field', node.attrs.fieldKey || '');

    let app = null, vm = null;
    let chipEl = null, hostEl = null, valEl = null;
    let mountedVT = null, mountedRO = null;

    function isDisabled() { return !editor.isEditable || node.attrs.readonly === true; }
    function pushValue(v) {
      if (isDisabled()) { return; }
      if (valuesEqual(v, node.attrs.value)) { return; }
      if (typeof getPos !== 'function') { return; }
      try {
        const pos = getPos();
        if (typeof pos !== 'number' || pos < 0) { return; }
        editor.view.dispatch(editor.view.state.tr.setNodeMarkup(pos, undefined, Object.assign({}, node.attrs, { value: v })));
      } catch (e) { /* 节点可能在微任务间隙被删除, 静默丢弃 */ }
    }
    function buildChip() {
      chipEl = document.createElement('span');
      chipEl.className = 'emr-f-chip'; chipEl.contentEditable = 'false';
      syncChip();
      dom.appendChild(chipEl);
    }
    function syncChip() {
      if (!chipEl) { return; }
      const a = node.attrs;
      chipEl.textContent = a.fieldName || a.fieldKey || '数据元';
      chipEl.classList.toggle('emr-f-chip--req', a.required === true);
      chipEl.title = [a.fieldKey, '类型:' + (a.valueType || 'text'), a.dictSource ? '值域:' + a.dictSource : '',
        a.required ? '必填' : '', a.readonly ? '只读' : '', a.noCopy ? '禁止复制值' : ''].filter(Boolean).join(' · ');
    }
    function buildReadonlyValue() {
      valEl = document.createElement('span');
      valEl.className = 'emr-f-val';
      syncReadonlyValue();
      dom.appendChild(valEl);
    }
    function syncReadonlyValue() {
      if (!valEl) { return; }
      const d = formatFieldValue(node.attrs.value);
      valEl.textContent = d === '' ? '\u00a0' : d;
      valEl.classList.toggle('emr-f-val--blank', d === '');
    }
    function mountControl() {
      hostEl = document.createElement('span');
      hostEl.className = 'emr-f-ctl'; dom.appendChild(hostEl);
      const ctl = createFieldControl(hostEl, { attrs: node.attrs, isDisabled: isDisabled, onValue: pushValue });
      app = ctl.app; vm = ctl.vm;
    }
    function teardownControl() {
      if (app) { try { app.unmount(); } catch (e) { /* noop */ } }
      app = null; vm = null; hostEl = null;
    }
    function syncControlValue() {
      if (vm && typeof vm.setValue === 'function' && !valuesEqual(vm.val, node.attrs.value)) { vm.setValue(node.attrs.value); }
    }
    function syncStateClasses() {
      const a = node.attrs;
      dom.className = 'emr-field emr-field--' + (a.valueType || 'text') +
        (isDisabled() ? ' emr-field--ro' : '') + (a.required === true && isBlankValue(a.value) ? ' emr-field--req-empty' : '');
    }
    function render() {
      const a = node.attrs;
      const ro = isDisabled();
      const vt = a.valueType || 'text';
      if (mountedVT !== vt || mountedRO !== ro) {
        teardownControl();
        dom.innerHTML = '';
        chipEl = null; valEl = null;
        buildChip();
        if (ro) { buildReadonlyValue(); } else { mountControl(); }
        mountedVT = vt; mountedRO = ro;
      } else {
        syncChip();
        if (ro) { syncReadonlyValue(); } else { syncControlValue(); }
      }
      syncStateClasses();
    }
    render();

    return {
      dom: dom,
      update: function (updatedNode) {
        if (updatedNode.type !== node.type) { return false; }
        node = updatedNode;
        render();
        return true;
      },
      ignoreMutation: function () { return true; },          /* atom 节点: 控件/Vue 的 DOM 变更与文档无关 */
      stopEvent: function (e) {                              /* 控件内部事件全部自留, 防止 PM 抢焦点 */
        const t = e.target;
        return !!(t && dom.contains(t));
      },
      destroy: function () { teardownControl(); dom.innerHTML = ''; }
    };
  }

  /* ================= EmrSection 头部构建(NodeView 与宿主 UI 复用同源) ================= */
  function buildSectionHead(a, handlers) {
    const frag = document.createDocumentFragment();
    const mk = function (cls, txt, title) {
      const s = document.createElement('span');
      s.className = cls;
      if (txt) { s.textContent = txt; }
      if (title) { s.title = title; }
      return s;
    };
    const stop = function (e) { e.preventDefault(); e.stopPropagation(); };
    if (a.collapsible !== false) {
      const tg = mk('emr-section-toggle', a.collapsed ? '▸' : '▾', a.collapsed ? '展开章节' : '折叠章节');
      tg.addEventListener('mousedown', stop);
      tg.addEventListener('click', function (e) { stop(e); if (handlers.onToggle) { handlers.onToggle(); } });
      frag.appendChild(tg);
    }
    frag.appendChild(mk('emr-section-title', a.title || a.sectionKey || '章节'));
    if (a.editMode && a.editMode !== 'mixed') { frag.appendChild(mk('emr-section-badge', EDIT_MODE_LABEL[a.editMode] || a.editMode, '编辑模式')); }
    const lock = mk('emr-section-flag emr-section-flag--lock' + (a.locked ? ' emr-section-flag--on' : ''), '已锁',
      a.locked ? '章节已锁定(点击解锁)' : '锁定本章节');
    lock.addEventListener('mousedown', stop);
    lock.addEventListener('click', function (e) { stop(e); if (handlers.onToggleLock) { handlers.onToggleLock(); } });
    frag.appendChild(lock);
    const pr = mk('emr-section-flag emr-section-flag--print' + (a.printHidden ? ' emr-section-flag--on' : ''), '不打印',
      a.printHidden ? '本章节不进入打印(点击恢复)' : '标记为打印时隐藏');
    pr.addEventListener('mousedown', stop);
    pr.addEventListener('click', function (e) { stop(e); if (handlers.onTogglePrint) { handlers.onTogglePrint(); } });
    frag.appendChild(pr);
    return frag;
  }

  /* ================= EmrSection NodeView ================= */
  function createSectionNodeView(ctx) {
    let node = ctx.node;
    const editor = ctx.editor;
    const getPos = ctx.getPos;
    const dom = document.createElement('div');
    const head = document.createElement('div');
    head.className = 'emr-section-head';
    head.contentEditable = 'false';
    const body = document.createElement('div');
    body.className = 'emr-section-body';
    dom.appendChild(head);
    dom.appendChild(body);

    function setAttrs(patch) {
      if (typeof getPos !== 'function') { return; }
      try {
        const pos = getPos();
        if (typeof pos !== 'number' || pos < 0) { return; }
        editor.view.dispatch(editor.view.state.tr.setNodeMarkup(pos, undefined, Object.assign({}, node.attrs, patch)));
      } catch (e) { /* noop */ }
    }
    function renderHead() {
      const a = node.attrs;
      dom.className = 'emr-section' + (a.collapsed ? ' emr-section--collapsed' : '') +
        (a.locked ? ' emr-section--locked' : '') + (a.printHidden ? ' emr-section--print-hidden' : '');
      dom.setAttribute('data-emr-section', a.sectionKey || '');
      head.innerHTML = '';
      head.appendChild(buildSectionHead(a, {
        onToggle: function () { if (node.attrs.collapsible !== false) { setAttrs({ collapsed: !node.attrs.collapsed }); } },
        onToggleLock: function () { setAttrs({ locked: !node.attrs.locked }); },
        onTogglePrint: function () { setAttrs({ printHidden: !node.attrs.printHidden }); }
      }));
    }
    renderHead();

    return {
      dom: dom,
      contentDOM: body,
      update: function (n2) {
        if (n2.type !== node.type) { return false; }
        node = n2;
        renderHead();
        return true;
      },
      ignoreMutation: function (m) { return !body.contains(m.target); },  /* 头部徽标重绘忽略, 正文变更归 PM */
      stopEvent: function (e) {                                          /* 头部交互(折叠/锁定)不进编辑器 */
        const t = e.target;
        return !!(t && head.contains(t));
      },
      destroy: function () { head.innerHTML = ''; }
    };
  }

  /* ================= EmrConditionalBlock NodeView(按数据元值实时求值) ================= */
  function exprText(a) {
    const op = (OPERATORS.filter(function (o) { return o.v === a.conditionOperator; })[0] || {}).l || a.conditionOperator || '';
    const rhs = (a.conditionOperator === 'empty' || a.conditionOperator === 'notEmpty') ? '' : String(a.conditionValue == null ? '' : a.conditionValue);
    return (a.conditionFieldKey || '?') + ' ' + op + (rhs ? ' ' + rhs : '');
  }
  function createConditionalNodeView(ctx) {
    let node = ctx.node;
    const editor = ctx.editor;
    const dom = document.createElement('div');
    const head = document.createElement('div');
    head.className = 'emr-cond-head emr-cond-indicator';   /* 指示条: 浅蓝底显示条件表达式 */
    head.contentEditable = 'false';
    const body = document.createElement('div');
    body.className = 'emr-cond-body';
    dom.appendChild(head);
    dom.appendChild(body);

    function renderHead(hidden, design) {
      const a = node.attrs;
      dom.className = 'emr-cond' + (hidden ? (design ? ' emr-cond--design-hidden' : ' emr-cond--hidden') : '');
      dom.setAttribute('data-emr-cond', 'true');
      dom.setAttribute('data-cond-field', a.conditionFieldKey || '');
      head.innerHTML = '';
      const tag = document.createElement('span');
      tag.className = 'emr-cond-tag'; tag.textContent = '条件';
      const ex = document.createElement('span');
      ex.className = 'emr-cond-expr';
      ex.textContent = exprText(a) + (hidden ? (design ? '（未满足, 设计模式暗态显示）' : '（未满足, 内容已隐藏）') : '');
      head.appendChild(tag); head.appendChild(ex);
    }
    function evaluate() {
      const design = isDesignMode(editor);
      const ok = evaluateCondition(node.attrs, collectFieldValuesFromDoc(editor.state.doc));
      renderHead(!ok, design);
      return ok;
    }
    const onTx = function () { evaluate(); };
    editor.on('transaction', onTx);
    /* 设计模式经 setMode 切宿主类(emr-doc--design), 不一定伴随事务: 监听宿主 class 补求值 */
    let mo = null;
    try {
      const host = editor.view.dom.closest('.emr-doc');
      if (host && typeof MutationObserver === 'function') { mo = new MutationObserver(onTx); mo.observe(host, { attributes: true, attributeFilter: ['class'] }); }
    } catch (e) { /* 宿主未挂载时跳过, 事务监听兜底 */ }
    evaluate();

    return {
      dom: dom,
      contentDOM: body,
      update: function (n2) {
        if (n2.type !== node.type) { return false; }
        node = n2;
        evaluate();
        return true;
      },
      ignoreMutation: function (m) { return !body.contains(m.target); },
      stopEvent: function (e) { const t = e.target; return !!(t && head.contains(t)); },
      destroy: function () { editor.off('transaction', onTx); if (mo) { mo.disconnect(); } }
    };
  }

  /* ================= EmrDrawing NodeView(空态点击卡片 / 有图悬浮编辑, 宿主画板经 HIS.EmrDrawingPanel 接入) ================= */
  function createDrawingNodeView(ctx) {
    let node = ctx.node;
    const editor = ctx.editor;
    const getPos = ctx.getPos;
    const dom = document.createElement('div');

    function setAttrs(patch) {
      if (typeof getPos !== 'function') { return; }
      try {
        const pos = getPos();
        if (typeof pos !== 'number' || pos < 0) { return; }
        editor.view.dispatch(editor.view.state.tr.setNodeMarkup(pos, undefined, Object.assign({}, node.attrs, patch)));
      } catch (e) { /* 节点可能在微任务间隙被删除, 静默丢弃 */ }
    }
    /* 唤起宿主画板(若已接入); 回写走 setNodeMarkup 精确更新本节点(全局 setEmrDrawingSvg 会误伤多图文档) */
    function openPanel() {
      const a = node.attrs;
      if (!(HIS.EmrDrawingPanel && typeof HIS.EmrDrawingPanel.open === 'function')) { return; }
      HIS.EmrDrawingPanel.open({
        title: a.title || '', svgData: a.svgData || '', annotations: a.annotations || '',
        onSave: function (result) {
          const r = (typeof result === 'string') ? { svgData: result } : (result || {});
          setAttrs({ svgData: r.svgData || '', annotations: r.annotations != null ? String(r.annotations) : a.annotations });
        }
      });
    }
    function render() {
      const a = node.attrs;
      const svg = typeof a.svgData === 'string' ? a.svgData : '';
      const hasSvg = svg.replace(/^\s+/, '').indexOf('<svg') === 0;   /* 仅接受 svg 根字符串(文档自有数据), 防杂散 HTML 注入 */
      dom.className = 'emr-drawing' + (hasSvg ? '' : ' emr-drawing--empty');
      dom.setAttribute('data-emr-drawing', 'true');
      if (a.title) { dom.setAttribute('data-title', a.title); }
      dom.innerHTML = '';
      if (hasSvg) {
        const wrap = document.createElement('div');
        wrap.className = 'emr-drawing-svg emr-drawing-wrap';
        wrap.innerHTML = svg;
        const ov = document.createElement('div');
        ov.className = 'emr-drawing-overlay'; ov.textContent = '点击编辑';
        wrap.appendChild(ov);
        wrap.addEventListener('click', openPanel);
        dom.appendChild(wrap);
      } else {
        const card = document.createElement('div');
        card.className = 'emr-drawing-card';
        card.addEventListener('click', openPanel);
        const ic = document.createElement('div'); ic.className = 'emr-drawing-icon'; ic.textContent = '🖼️';
        const tx = document.createElement('div'); tx.className = 'emr-drawing-text'; tx.textContent = '点击编辑医学图示';
        card.appendChild(ic); card.appendChild(tx);
        dom.appendChild(card);
      }
      if (a.title) {
        const cap = document.createElement('div');
        cap.className = 'emr-drawing-cap'; cap.textContent = a.title;
        dom.appendChild(cap);
      }
    }
    render();
    return {
      dom: dom,
      update: function (n2) { if (n2.type !== node.type) { return false; } node = n2; render(); return true; },
      ignoreMutation: function () { return true; },
      stopEvent: function () { return true; }   /* 图内点击不扰动正文光标(绘制/编辑交互由 NodeView 接管) */
    };
  }

  /* ================= 扩展工厂 ================= */
  function extField(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrField',
      group: 'inline',
      inline: true,
      atom: true,

      addAttributes() {
        return {
          fieldKey: { default: '' }, fieldName: { default: '' }, value: { default: null },
          valueType: { default: 'text' }, dictSource: { default: null }, options: { default: null },
          required: { default: false }, readonly: { default: false }, noCopy: { default: false },
          placeholder: { default: '' }, unit: { default: '' }, defaultMacro: { default: '' }
        };
      },

      parseHTML() {
        return [{
          tag: 'span[data-emr-field]',
          getAttrs: function (el) {
            return {
              fieldKey: el.getAttribute('data-emr-field') || '', fieldName: el.getAttribute('data-field-name') || '',
              value: jparse(el.getAttribute('data-value'), null), valueType: el.getAttribute('data-value-type') || 'text',
              dictSource: el.getAttribute('data-dict-source') || null, options: el.getAttribute('data-options') || null,
              required: el.getAttribute('data-required') === '1', readonly: el.getAttribute('data-readonly') === '1',
              noCopy: el.getAttribute('data-nocopy') === '1',
              placeholder: el.getAttribute('data-placeholder') || '', unit: el.getAttribute('data-unit') || '',
              defaultMacro: el.getAttribute('data-default-macro') || ''
            };
          }
        }];
      },

      renderHTML({ node, HTMLAttributes }) {
        const a = node.attrs;
        return ['span', T.mergeAttributes(HTMLAttributes, {
          'class': 'emr-field emr-field--' + (a.valueType || 'text'),
          'data-emr-field': a.fieldKey || '', 'data-field-name': a.fieldName || '',
          'data-value-type': a.valueType || 'text', 'data-dict-source': a.dictSource || '',
          'data-value': jstr(a.value) || '',
          'data-options': typeof a.options === 'string' ? a.options : (jstr(a.options) || ''),
          'data-required': a.required ? '1' : '', 'data-readonly': a.readonly ? '1' : '',
          'data-nocopy': a.noCopy ? '1' : '',
          'data-placeholder': a.placeholder || '', 'data-unit': a.unit || '',
          'data-default-macro': a.defaultMacro || ''
        }), (a.fieldName ? a.fieldName + '：' : '') + formatFieldValue(a.value)];
      },

      addNodeView() {
        return function (props) { return createFieldNodeView(props); };
      },

      addCommands() {
        return {
          insertEmrField: (attrs) => ({ commands }) => commands.insertContent({
            type: this.name,
            attrs: Object.assign({ fieldKey: 'field_' + Date.now(), fieldName: '新数据元', valueType: 'text' }, attrs || {})
          })
        };
      }
    });
  }

  function extSection(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrSection',
      group: 'block',
      content: 'block+',
      defining: true,

      addAttributes() {
        return {
          sectionKey: { default: '' }, title: { default: '' }, editMode: { default: 'mixed' },
          collapsible: { default: true }, printHidden: { default: false },
          locked: { default: false }, collapsed: { default: false }
        };
      },

      parseHTML() {
        return [{
          tag: 'div[data-emr-section]',
          getAttrs: function (el) {
            const titleEl = el.querySelector('.emr-section-title');
            return {
              sectionKey: el.getAttribute('data-section-key') || el.getAttribute('data-emr-section') || '',
              title: el.getAttribute('data-title') || (titleEl ? titleEl.textContent : '') || '',
              editMode: el.getAttribute('data-edit-mode') || 'mixed', collapsible: el.getAttribute('data-collapsible') !== '0',
              printHidden: el.getAttribute('data-print-hidden') === '1', locked: el.getAttribute('data-locked') === '1',
              collapsed: el.classList.contains('emr-section--collapsed')
            };
          }
        }];
      },

      renderHTML({ node, HTMLAttributes }) {
        const a = node.attrs;
        return ['div', T.mergeAttributes(HTMLAttributes, {
          'class': 'emr-section' + (a.collapsed ? ' emr-section--collapsed' : '') +
            (a.locked ? ' emr-section--locked' : '') + (a.printHidden ? ' emr-section--print-hidden' : ''),
          'data-emr-section': a.sectionKey || '', 'data-section-key': a.sectionKey || '',
          'data-title': a.title || '', 'data-edit-mode': a.editMode || 'mixed',
          'data-collapsible': a.collapsible === false ? '0' : '1',
          'data-print-hidden': a.printHidden ? '1' : '', 'data-locked': a.locked ? '1' : ''
        }), ['div', { 'class': 'emr-section-head' },
          ['span', { 'class': 'emr-section-title' }, a.title || a.sectionKey || '章节']
        ], ['div', { 'class': 'emr-section-body' }, 0]];
      },

      addNodeView() {
        return function (props) { return createSectionNodeView(props); };
      },

      addCommands() {
        return {
          insertEmrSection: (attrs) => ({ commands }) => commands.insertContent({
            type: this.name,
            attrs: Object.assign({ sectionKey: 'sec_' + Date.now(), title: '新章节', editMode: 'mixed', collapsible: true, printHidden: false, locked: false, collapsed: false }, attrs || {}),
            content: [{ type: 'paragraph' }]
          }),
          toggleEmrSectionCollapse: () => ({ state, dispatch }) => {
            const $from = state.selection.$from;
            for (let d = $from.depth; d > 0; d--) {
              const n = $from.node(d);
              if (n.type.name === 'emrSection' && n.attrs.collapsible !== false) {
                if (dispatch) {
                  dispatch(state.tr.setNodeMarkup($from.before(d), undefined, Object.assign({}, n.attrs, { collapsed: !n.attrs.collapsed })));
                }
                return true;
              }
            }
            return false;
          }
        };
      }
    });
  }

  function extTable(t) {
    const T = needTiptap(t);
    /* 在官方 Table 上追记行级留痕属性(编辑/命令行为与官方完全一致) */
    return T.Table.extend({
      addAttributes() {
        const parent = this.parent ? this.parent() : {};
        return Object.assign({}, parent, {
          tableId: {
            default: null,
            parseHTML: function (el) { return el.getAttribute('data-table-id') || null; },
            renderHTML: function (attrs) { return attrs.tableId ? { 'data-table-id': attrs.tableId } : {}; }
          },
          rowIds: {
            default: null,
            parseHTML: function (el) { return el.getAttribute('data-row-ids') || null; },
            renderHTML: function (attrs) { return attrs.rowIds ? { 'data-row-ids': attrs.rowIds } : {}; }
          }
        });
      },
      addCommands() {
        const parentCmds = this.parent ? this.parent() : {};
        return Object.assign({}, parentCmds, {
          setEmrTableMeta: (patch) => ({ state, dispatch }) => {
            const $from = state.selection.$from;
            for (let d = $from.depth; d > 0; d--) {
              const n = $from.node(d);
              if (n.type.name === 'table') {
                if (dispatch) {
                  dispatch(state.tr.setNodeMarkup($from.before(d), undefined, Object.assign({}, n.attrs, patch)));
                }
                return true;
              }
            }
            return false;
          }
        });
      }
    });
  }

  function extMacro(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrMacro',
      group: 'inline',
      inline: true,
      atom: true,

      addAttributes() {
        return { macroCode: { default: '' }, dataSource: { default: null }, resolvedValue: { default: null } };
      },

      parseHTML() {
        return [{
          tag: 'span[data-emr-macro]',
          getAttrs: function (el) {
            return {
              macroCode: el.getAttribute('data-macro-code') || '', dataSource: el.getAttribute('data-macro-source') || null,
              resolvedValue: el.getAttribute('data-resolved') || null
            };
          }
        }];
      },

      renderHTML({ node, HTMLAttributes }) {
        const a = node.attrs;
        const resolved = (a.resolvedValue != null && a.resolvedValue !== '') ? String(a.resolvedValue) : null;
        return ['span', T.mergeAttributes(HTMLAttributes, {
          'class': 'emr-macro' + (resolved ? ' emr-macro--resolved' : ''),
          'data-emr-macro': a.macroCode || '', 'data-macro-code': a.macroCode || '',
          'data-macro-source': a.dataSource || '', 'data-resolved': resolved || ''
        }), resolved || ('【' + (a.macroCode || '宏变量') + '】')];
      },

      addCommands() {
        return {
          insertEmrMacro: (attrs) => ({ commands }) => commands.insertContent({
            type: this.name,
            attrs: Object.assign({ macroCode: 'patientName', dataSource: 'patient' }, attrs || {})
          }),
          resolveEmrMacro: (macroCode, value) => ({ state, dispatch }) => {
            let changed = false;
            const tr = state.tr;
            state.doc.descendants(function (node, pos) {
              if (node.type.name === 'emrMacro' && node.attrs.macroCode === macroCode) {
                tr.setNodeMarkup(pos, undefined, Object.assign({}, node.attrs, { resolvedValue: value == null ? null : String(value) }));
                changed = true;
              }
            });
            if (changed && dispatch) { dispatch(tr); }
            return changed;
          }
        };
      }
    });
  }

  function extPrintControl(t) {
    const T = needTiptap(t);
    return T.Mark.create({
      name: 'emrPrintControl',
      /* 打印隐藏标记: 编辑态虚线框可见, 打印介质一律隐藏(见文末 @media print) */
      inclusive: false,

      parseHTML() {
        return [{ tag: 'span[data-emr-print]' }];
      },

      renderHTML({ HTMLAttributes }) {
        return ['span', T.mergeAttributes(HTMLAttributes, { 'class': 'emr-print-hidden', 'data-emr-print': '1' })];
      },

      addCommands() {
        return {
          toggleEmrPrintControl: () => ({ commands }) => commands.toggleMark(this.name),
          setEmrPrintControl: () => ({ commands }) => commands.setMark(this.name),
          unsetEmrPrintControl: () => ({ commands }) => commands.unsetMark(this.name)
        };
      }
    });
  }

  /* ================= EmrFragment NodeView(引用片段卡片: 图标 + 标题 + 版本徽章 + 刷新事件) ================= */
  function createFragmentNodeView(ctx) {
    let node = ctx.node;
    const dom = document.createElement('div');
    function render() {
      const a = node.attrs;
      dom.className = 'emr-fragment-card';
      dom.setAttribute('data-emr-fragment', 'true');
      dom.setAttribute('data-fragment-id', a.fragmentId || '');
      dom.title = ['#' + (a.fragmentId || '未指定'), a.sourceTemplateId ? '来源:' + a.sourceTemplateId : ''].filter(Boolean).join(' · ');
      dom.innerHTML = '';
      const head = document.createElement('div');
      head.className = 'frag-head';
      const ic = document.createElement('span'); ic.className = 'frag-icon'; ic.textContent = '📄';
      const tt = document.createElement('span'); tt.className = 'frag-title'; tt.textContent = a.title || a.fragmentId || '片段引用';
      head.appendChild(ic); head.appendChild(tt);
      if (a.version) {
        const vb = document.createElement('span'); vb.className = 'frag-version'; vb.textContent = 'v' + a.version;
        head.appendChild(vb);
      }
      const btn = document.createElement('button');
      btn.className = 'frag-refresh'; btn.type = 'button'; btn.textContent = '刷新'; btn.title = '检查片段版本更新';
      btn.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation();   /* 版本核查交由宿主监听(未来接片段版本比对) */
        window.dispatchEvent(new CustomEvent('emr-fragment-refresh', { detail: {
          fragmentId: a.fragmentId, sourceTemplateId: a.sourceTemplateId, version: a.version, title: a.title } }));
      });
      head.appendChild(btn);
      const note = document.createElement('div'); note.className = 'frag-note'; note.textContent = '引用片段 · 打印时展开为完整内容';
      dom.appendChild(head); dom.appendChild(note);
    }
    render();
    return {
      dom: dom,
      update: function (n2) { if (n2.type !== node.type) { return false; } node = n2; render(); return true; },
      ignoreMutation: function () { return true; },
      stopEvent: function () { return true; }   /* 卡片整体自留(刷新按钮点击不进编辑器) */
    };
  }

  function extFragment(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrFragment',
      group: 'block',
      atom: true,

      addAttributes() {
        return { fragmentId: { default: '' }, sourceTemplateId: { default: '' }, version: { default: '' }, title: { default: '' } };
      },

      parseHTML() {
        return [{
          tag: 'div[data-emr-fragment]',
          getAttrs: function (el) {
            return {
              fragmentId: el.getAttribute('data-fragment-id') || '', sourceTemplateId: el.getAttribute('data-source-template') || '',
              version: el.getAttribute('data-version') || '', title: el.getAttribute('data-title') || ''
            };
          }
        }];
      },

      renderHTML({ node, HTMLAttributes }) {
        const a = node.attrs;
        const info = ['#' + (a.fragmentId || '未指定'), a.sourceTemplateId ? '来源:' + a.sourceTemplateId : '', a.version ? 'v' + a.version : ''].filter(Boolean).join(' · ');
        return ['div', T.mergeAttributes(HTMLAttributes, {
          'class': 'emr-fragment', 'data-emr-fragment': 'true',
          'data-fragment-id': a.fragmentId || '', 'data-source-template': a.sourceTemplateId || '',
          'data-version': a.version || '', 'data-title': a.title || ''
        }), ['span', { 'class': 'emr-fragment-tag' }, '片段'], ['span', { 'class': 'emr-fragment-info' }, info],
        ['span', { 'class': 'emr-fragment-hint' }, '引用片段, 打印时由服务端展开为完整内容']];
      },

      addNodeView() {
        return function (props) { return createFragmentNodeView(props); };
      },

      addCommands() {
        return {
          insertEmrFragment: (attrs) => ({ commands }) => commands.insertContent({
            type: this.name,
            attrs: Object.assign({ fragmentId: 'F-' + Date.now(), sourceTemplateId: '', version: '1', title: '片段引用' }, attrs || {})
          })
        };
      }
    });
  }

  function extConditionalBlock(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrConditionalBlock',
      group: 'block',
      content: 'block+',

      addAttributes() {
        return {
          conditionFieldKey: { default: '' }, conditionOperator: { default: 'eq' }, conditionValue: { default: '' },
          visible: { default: true }   /* 求值结果缓存位(打印/检索方重算, 编辑器不回写避免事务风暴) */
        };
      },

      parseHTML() {
        return [{
          tag: 'div[data-emr-cond]',
          getAttrs: function (el) {
            return {
              conditionFieldKey: el.getAttribute('data-cond-field') || '', conditionOperator: el.getAttribute('data-cond-op') || 'eq',
              conditionValue: el.getAttribute('data-cond-value') || ''
            };
          }
        }];
      },

      renderHTML({ node, HTMLAttributes }) {
        const a = node.attrs;
        return ['div', T.mergeAttributes(HTMLAttributes, {
          'class': 'emr-cond', 'data-emr-cond': a.conditionFieldKey || '',
          'data-cond-field': a.conditionFieldKey || '', 'data-cond-op': a.conditionOperator || 'eq',
          'data-cond-value': a.conditionValue == null ? '' : String(a.conditionValue)
        }), ['div', { 'class': 'emr-cond-head' }, ['span', { 'class': 'emr-cond-tag' }, '条件'],
        ['span', { 'class': 'emr-cond-expr' }, exprText(a)]], ['div', { 'class': 'emr-cond-body' }, 0]];
      },

      addNodeView() {
        return function (props) { return createConditionalNodeView(props); };
      },

      addCommands() {
        return {
          insertEmrConditionalBlock: (attrs) => ({ commands }) => commands.insertContent({
            type: this.name,
            attrs: Object.assign({ conditionFieldKey: '', conditionOperator: 'eq', conditionValue: '', visible: true }, attrs || {}),
            content: [{ type: 'paragraph' }]
          })
        };
      }
    });
  }

  function extPageBreak(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrPageBreak',
      group: 'block',
      atom: true,

      parseHTML() {
        return [{ tag: 'div[data-emr-pagebreak]' }];
      },

      renderHTML({ HTMLAttributes }) {
        return ['div', T.mergeAttributes(HTMLAttributes, { 'class': 'emr-page-break', 'data-emr-pagebreak': '1' }),
        ['span', { 'class': 'emr-page-break-label' }, '分页']];
      },

      addCommands() {
        return {
          insertEmrPageBreak: () => ({ commands }) => commands.insertContent({ type: this.name })
        };
      }
    });
  }

  function extDrawing(t) {
    const T = needTiptap(t);
    return T.Node.create({
      name: 'emrDrawing',
      group: 'block',
      atom: true,

      addAttributes() {
        return { svgData: { default: '' }, annotations: { default: '' }, title: { default: '' } };
      },

      parseHTML() {
        return [{
          tag: 'div[data-emr-drawing]',
          getAttrs: function (el) {
            return {
              svgData: el.getAttribute('data-svg') || '', annotations: el.getAttribute('data-annotations') || '',
              title: el.getAttribute('data-title') || ''
            };
          }
        }];
      },

      renderHTML({ node, HTMLAttributes }) {
        const a = node.attrs;
        return ['div', T.mergeAttributes(HTMLAttributes, {
          'class': 'emr-drawing' + (a.svgData ? '' : ' emr-drawing--empty'),
          'data-emr-drawing': 'true', 'data-svg': a.svgData || '',
          'data-annotations': a.annotations || '', 'data-title': a.title || ''
        }), a.svgData ? '' : '（医学图示）'];
      },

      addNodeView() {
        return function (props) { return createDrawingNodeView(props); };
      },

      addCommands() {
        return {
          insertEmrDrawing: (attrs) => ({ commands }) => commands.insertContent({
            type: this.name,
            attrs: Object.assign({ svgData: '', annotations: '', title: '' }, attrs || {})
          }),
          setEmrDrawingSvg: (svgData, annotations) => ({ state, dispatch }) => {
            let changed = false;
            const tr = state.tr;
            state.doc.descendants(function (node, pos) {
              if (node.type.name === 'emrDrawing') {
                tr.setNodeMarkup(pos, undefined, Object.assign({}, node.attrs, {
                  svgData: svgData || '', annotations: annotations != null ? String(annotations) : node.attrs.annotations
                }));
                changed = true;
              }
            });
            if (changed && dispatch) { dispatch(tr); }
            return changed;
          }
        };
      }
    });
  }

  /* ================= 汇总构建 ================= */
  function buildAll(t) {
    const T = needTiptap(t);
    return {
      EmrField: extField(T),
      EmrSection: extSection(T),
      EmrTable: extTable(T),
      EmrMacro: extMacro(T),
      EmrPrintControl: extPrintControl(T),
      EmrFragment: extFragment(T),
      EmrConditionalBlock: extConditionalBlock(T),
      EmrPageBreak: extPageBreak(T),
      EmrDrawing: extDrawing(T)
    };
  }

  /* ================= 样式(编辑器内作用域 .emr-doc; 全局仅 @media print 约定) =================
   * 注意: 全局 .emr-field 已被 emr-field.js(表单行布局)占用, 编辑器内一律以 .emr-doc 前缀覆盖。 */
  (function injectCss() {
    if (document.getElementById('emr-extensions-css')) { return; }
    const st = document.createElement('style');
    st.id = 'emr-extensions-css';
    st.textContent = [
      /* ---- 数据元(行内原子): 标签 chip + EP 控件 ---- */
      '.emr-doc .emr-field { display:inline-flex; align-items:center; gap:4px; margin:0 2px; vertical-align:middle; border-radius:3px; }',
      '.emr-doc .emr-f-chip { display:inline-flex; align-items:center; color:var(--yb-ink-3,#5a6a7e); font-size:12px; white-space:nowrap; }',
      '.emr-doc .emr-f-chip--req::before { content:"*"; color:var(--yb-danger,#c74f4f); margin-right:1px; }',
      '.emr-doc .emr-field--req-empty .emr-f-chip { color:var(--yb-danger,#c74f4f); }',
      '.emr-doc .emr-f-ctl, .emr-doc .emr-f-ctl-in { display:inline-flex; align-items:center; gap:4px; min-width:0; }',
      '.emr-doc .emr-field--textarea, .emr-doc .emr-field--vitals { display:flex; align-items:flex-start; width:calc(100% - 4px); margin:2px; }',
      '.emr-doc .emr-field--textarea .emr-f-ctl, .emr-doc .emr-field--vitals .emr-f-ctl { flex:1; min-width:0; }',
      '.emr-doc .emr-f-textarea { display:flex; flex:1; min-width:0; flex-direction:column; gap:5px; } .emr-doc .emr-f-textarea>.el-textarea { width:100%; }',
      '.emr-doc .emr-f-quick { display:flex; align-items:center; gap:4px; flex-wrap:wrap; } .emr-doc .emr-f-quick-label { color:var(--yb-ink-4,#8994a5); font-size:11px; }',
      '.emr-doc .emr-f-quick .el-button { max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; margin-left:0; }',
      '.emr-doc .emr-f-quick-menu { display:none; flex:none; }',
      '.emr-quick-popper .el-dropdown-menu { max-width:440px; } .emr-quick-popper .el-dropdown-menu__item { height:auto; min-height:32px; white-space:normal; word-break:break-all; line-height:1.5; padding:6px 12px; }',
      '.emr-doc .emr-f-vitals { display:flex; align-items:center; gap:8px; flex-wrap:wrap; } .emr-doc .emr-f-vitals label { display:inline-flex; align-items:center; gap:3px; font-size:12px; color:var(--yb-ink-2,#3d4a5c); }',
      '.emr-doc .emr-f-vitals label .el-input { width:58px; } .emr-doc .emr-f-vitals label i { font-style:normal; color:var(--yb-ink-4,#8994a5); } .emr-doc .emr-f-vitals label b { font-weight:400; }',
      '.emr-doc .emr-f-unit { color:var(--yb-ink-3,#5a6a7e); font-size:12px; } .emr-doc .emr-f-native { border:1px solid var(--yb-border-strong,#ccd4de); border-radius:4px; height:24px; padding:0 6px; font-size:12px; outline:none; } .emr-doc .emr-f-native--textarea { width:100%; min-height:54px; height:auto; padding:6px; }',
      /* 只读态: 值以下划线呈现(贴近纸质病历留痕) */
      '.emr-doc .emr-field--ro { background:var(--yb-surface-2,#f7f9fc); padding:0 4px; border-bottom:1px dotted var(--yb-border-strong,#ccd4de); }',
      '.emr-doc .emr-f-val { color:var(--yb-ink-1,#1c2430); min-width:56px; display:inline-block; text-align:center; border-bottom:1px solid var(--yb-ink-3,#5a6a7e); padding:0 6px; line-height:1.6; }',
      '.emr-doc .emr-f-val--blank { min-width:88px; }',
      /* ---- 章节 ---- */
      '.emr-doc .emr-section { border:1px solid var(--yb-border-light,#ebeff4); border-left:3px solid var(--yb-brand,#1a5c9e); border-radius:4px; margin:10px 0; }',
      '.emr-doc .emr-section-head { display:flex; align-items:center; gap:8px; padding:6px 10px; background:var(--yb-surface-2,#f7f9fc); border-bottom:1px solid var(--yb-border-light,#ebeff4); user-select:none; }',
      '.emr-doc .emr-section-toggle { cursor:pointer; color:var(--yb-ink-3,#5a6a7e); width:14px; text-align:center; font-size:10px; } .emr-doc .emr-section-toggle:hover { color:var(--yb-brand,#1a5c9e); }',
      '.emr-doc .emr-section-title { font-weight:600; color:var(--yb-ink-1,#1c2430); font-size:14px; }',
      '.emr-doc .emr-section-badge { font-size:11px; color:var(--yb-ink-3,#5a6a7e); border:1px solid var(--yb-border,#dfe4eb); border-radius:3px; padding:0 4px; line-height:16px; }',
      '.emr-doc .emr-section-flag { display:none; cursor:pointer; font-size:11px; color:var(--yb-ink-4,#8994a5); border:1px dashed var(--yb-border,#dfe4eb); border-radius:3px; padding:0 4px; line-height:16px; }',
      '.emr-doc .emr-section-head:hover .emr-section-flag { display:inline; }',
      '.emr-doc .emr-section-flag--on { display:inline; }',
      '.emr-doc .emr-section-flag--lock--on { color:var(--yb-warning,#a26b1b); border-color:var(--yb-warning-border,#f0ddc0); background:var(--yb-warning-bg,#fbf3e6); }',
      '.emr-doc .emr-section-flag--print--on { color:var(--yb-danger,#c74f4f); border-color:var(--yb-danger-border,#f3d4d4); background:var(--yb-danger-bg,#fdf0f0); }',
      '.emr-doc .emr-section-body { padding:8px 12px; } .emr-doc .emr-section--collapsed > .emr-section-body { display:none; }',
      '.emr-doc .emr-section--locked > .emr-section-head { background:var(--yb-warning-bg,#fbf3e6); } .emr-doc .emr-section--print-hidden > .emr-section-head { opacity:.6; }',
      /* ---- 宏变量 ---- */
      '.emr-doc .emr-macro { display:inline-block; background:var(--yb-info-light,#f5f9ff); color:var(--yb-link,#2c78c7); border:1px solid #cfe1f4; border-radius:3px; padding:0 5px; font-size:12px; margin:0 2px; line-height:1.6; }',
      '.emr-doc .emr-macro--resolved { background:var(--yb-success-bg,#eef6ec); color:var(--yb-success,#3c862d); border-color:var(--yb-success-border,#d5e8cf); }',
      /* ---- 打印隐藏标记: 编辑态虚线框, 打印介质隐藏(全局约定, 打印窗口同规则) ---- */
      '.emr-doc .emr-print-hidden { border:1px dashed var(--yb-warning,#a26b1b); border-radius:3px; padding:0 3px; }',
      '@media print { .emr-print-hidden { display:none !important; } }',   /* 全局约定: 浏览器直打与打印窗口同规则 */
      /* ---- 分页符 ---- */
      '.emr-doc .emr-page-break { position:relative; margin:16px 0; border-top:1px dashed var(--yb-border-strong,#ccd4de); text-align:center; user-select:none; }',
      '.emr-doc .emr-page-break .emr-page-break-label { position:relative; top:-9px; background:var(--yb-surface,#fff); padding:0 10px; color:var(--yb-ink-4,#8994a5); font-size:12px; letter-spacing:.5em; }',
      /* ---- 片段引用 ---- */
      '.emr-doc .emr-fragment { display:flex; align-items:center; gap:8px; flex-wrap:wrap; border:1px dashed var(--yb-fill-purple,#9b59b6); border-radius:6px; padding:10px 12px; margin:8px 0; background:rgba(155,89,182,.04); }',
      '.emr-doc .emr-fragment-tag { font-size:12px; color:#fff; background:var(--yb-fill-purple,#9b59b6); border-radius:3px; padding:1px 6px; }',
      '.emr-doc .emr-fragment-info { font-size:12px; color:var(--yb-ink-2,#3d4a5c); font-variant-numeric:tabular-nums; } .emr-doc .emr-fragment-hint { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      /* ---- 条件块 ---- */
      '.emr-doc .emr-cond { border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; margin:8px 0; }',
      '.emr-doc .emr-cond-head { display:flex; gap:6px; align-items:center; padding:4px 10px; background:var(--yb-surface-2,#f7f9fc); border-bottom:1px solid var(--yb-border-light,#ebeff4); font-size:12px; color:var(--yb-ink-3,#5a6a7e); user-select:none; }',
      '.emr-doc .emr-cond-tag { font-size:11px; color:var(--yb-ink-3,#5a6a7e); border:1px solid var(--yb-border,#dfe4eb); border-radius:3px; padding:0 4px; } .emr-doc .emr-cond-expr { font-variant-numeric:tabular-nums; }',
      '.emr-doc .emr-cond-body { padding:6px 12px; } .emr-doc .emr-cond--hidden > .emr-cond-body { display:none; }',
      '.emr-doc .emr-cond--hidden > .emr-cond-head { color:var(--yb-ink-4,#8994a5); background:var(--yb-canvas,#f2f4f8); border-bottom:none; }',
      /* ---- 医学图示 ---- */
      '.emr-doc .emr-drawing { border:1px dashed var(--yb-border-strong,#ccd4de); border-radius:6px; margin:8px 0; padding:10px; text-align:center; color:var(--yb-ink-3,#5a6a7e); font-size:12px; }',
      '.emr-doc .emr-drawing svg, .emr-doc .emr-drawing-svg svg { max-width:100%; height:auto; } .emr-doc .emr-drawing-cap { margin-top:6px; color:var(--yb-ink-2,#3d4a5c); }',
      /* ---- 设计模式: 数据元/宏/片段强化描边, 便于模板维护者辨识结构 ---- */
      '.emr-doc.emr-doc--design .emr-field { outline:1px dashed var(--yb-brand-border,#bacee2); outline-offset:1px; }',
      '.emr-doc.emr-doc--design .emr-macro { outline:1px dashed #cfe1f4; outline-offset:1px; }',
      /* ---- 医学图示: 空态点击卡片 / 有图悬浮编辑覆盖层 ---- */
      '.emr-doc .emr-drawing--empty { border:none; padding:0; }',
      '.emr-doc .emr-drawing-card { border:2px dashed #d9d9d9; border-radius:8px; padding:32px; text-align:center; cursor:pointer; background:#fafafa; transition:border-color .2s; user-select:none; }',
      '.emr-doc .emr-drawing-card:hover { border-color:#409eff; background:#f0f7ff; }',
      '.emr-doc .emr-drawing-icon { font-size:28px; margin-bottom:8px; } .emr-doc .emr-drawing-text { color:#595959; font-size:13px; }',
      '.emr-doc .emr-drawing-wrap { position:relative; cursor:pointer; }',
      '.emr-doc .emr-drawing-overlay { position:absolute; inset:0; background:rgba(0,0,0,.3); display:flex; align-items:center; justify-content:center; color:#fff; font-size:14px; opacity:0; transition:opacity .2s; border-radius:4px; }',
      '.emr-doc .emr-drawing-wrap:hover .emr-drawing-overlay { opacity:1; }',
      /* ---- 条件块指示条 + 设计模式暗显(暗态保留可见可编辑, 便于模板维护者处理被条件隐藏的内容) ---- */
      '.emr-doc .emr-cond-indicator { background:#e6f7ff; border-bottom:1px solid #91d5ff; padding:4px 12px; font-size:12px; color:#096dd9; border-radius:4px 4px 0 0; }',
      '.emr-doc .emr-cond--design-hidden { opacity:.4; position:relative; }',
      '.emr-doc .emr-cond--design-hidden::after { content:"条件隐藏"; position:absolute; top:4px; right:8px; background:#faad14; color:#fff; font-size:11px; padding:1px 6px; border-radius:3px; }',
      /* ---- 片段引用卡片(NodeView 版; 静态回退仍用 .emr-fragment) ---- */
      '.emr-doc .emr-fragment-card { border:1px dashed #d3adf7; border-radius:6px; padding:12px 16px; background:#f9f0ff; margin:8px 0; user-select:none; }',
      '.emr-doc .emr-fragment-card .frag-head { display:flex; align-items:center; gap:8px; }',
      '.emr-doc .emr-fragment-card .frag-title { font-weight:600; color:#531dab; }',
      '.emr-doc .emr-fragment-card .frag-version { display:inline-block; background:#d3adf7; color:#fff; font-size:11px; padding:0 6px; border-radius:3px; margin-left:8px; }',
      '.emr-doc .emr-fragment-card .frag-note { font-size:12px; color:#8c8c8c; margin-top:6px; }',
      '.emr-doc .emr-fragment-card .frag-refresh { margin-left:auto; font-size:12px; color:#531dab; background:transparent; border:1px solid #d3adf7; border-radius:4px; padding:1px 8px; cursor:pointer; } .emr-doc .emr-fragment-card .frag-refresh:hover { background:#f0e6fa; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 对外注册 ================= */
  HIS.EmrExtensions = {
    VALUE_TYPES: VALUE_TYPES,
    EDIT_MODE_LABEL: EDIT_MODE_LABEL,
    OPERATORS: OPERATORS,
    /* 各扩展工厂(可单独取用; t 可省略, 默认 window.Tiptap) */
    EmrField: extField,
    EmrSection: extSection,
    EmrTable: extTable,
    EmrMacro: extMacro,
    EmrPrintControl: extPrintControl,
    EmrFragment: extFragment,
    EmrConditionalBlock: extConditionalBlock,
    EmrPageBreak: extPageBreak,
    EmrDrawing: extDrawing,
    /* 一次构建全部(编辑器 createEditor 使用) */
    buildAll: buildAll,
    /* 工具(打印预览/检索/宿主 UI 复用) */
    evaluateCondition: evaluateCondition,
    collectFieldValuesFromDoc: collectFieldValuesFromDoc,
    createFieldControl: createFieldControl,
    buildSectionHead: buildSectionHead,
    formatFieldValue: formatFieldValue,
    parseFieldOptions: parseFieldOptions,
    parseVitals: parseVitals,
    formatVitals: formatVitals,
    valuesEqual: valuesEqual,
    isBlankValue: isBlankValue
  };
})();
