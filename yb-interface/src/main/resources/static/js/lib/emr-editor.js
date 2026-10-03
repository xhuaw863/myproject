/* ==================================================================
 * emr-editor.js — EMR 编辑器引擎(Tiptap 封装)
 * ------------------------------------------------------------------
 * 依赖: emr-extensions.js(须先加载, 提供 HIS.EmrExtensions)
 *       tiptap-bundle(经 TiptapLoader.ensure() 懒加载后 window.Tiptap 就绪)。
 * 提供: HIS.EmrEditor.createEditor(options) → 编辑器包装对象
 *       EmrToolbar 工具栏 | EmrFieldRenderer/EmrSectionRenderer 宿主渲染组件
 *       EmrMacroResolver 宏预览 | EmrPrintPreview 打印预览 | EmrDiffViewer 版本对比
 *       EmrNlgPanel 自然语言预览(nlgEnabled) | EmrCdssPanel 临床提醒(cdssEnabled)
 * 用法:
 *   const w = await HIS.EmrEditor.createEditor({ container: '#doc', mode: 'edit',
 *     onSave(w){...}, onFieldChange(changes, fields){...} });
 *   HIS.EmrEditor.EmrToolbar.mount('#bar', w);   // 或 createEditor 传 toolbarContainer
 * 注册: HIS.EmrEditor(须在各 views / app.js 之前加载)。
 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});

  /* ================= 常量 ================= */
  const DEFAULT_DOC = { type: 'doc', content: [{ type: 'paragraph' }] };
  const VT_LABEL = { text: '文本', number: '数字', date: '日期', select: '单选', multiselect: '多选', checkbox: '复选', dict: '字典' };
  const ALIGNS = ['left', 'center', 'right', 'justify'];
  const DRAW_CATS = { body_front: '躯干前', body_back: '躯干后', head: '头部', oral: '口腔', hand: '手部', foot: '足部', wound: '伤口', custom: '自定义' };
  const SCOPE_LABELS = { 0: '全院', 1: '科室', 2: '个人' };
  const FRAG_RESOLVE_URL = '/api/his/emr/fragment/resolve';
  const FRAG_LIST_URL = '/api/his/emr/fragment/list';
  const DRAW_TPL_URL = '/api/his/emr/drawing-template';
  const NLG_DOC_URL = '/api/his/emr/nlg/generate-document';
  const CDSS_DOC_URL = '/api/his/emr/cdss/evaluate-document';

  /* 打印窗口样式(独立于页面 theme, 纸质病历观感) */
  const PP_CSS = [
    'body { font-family: "SimSun", "Songti SC", serif; color: #000; margin: 0; }',
    '.emr-pp { max-width: 794px; margin: 0 auto; padding: 32px 40px; font-size: 14px; line-height: 1.9; }',
    '.emr-pp h1, .emr-pp h2, .emr-pp h3, .emr-pp h4 { margin: .8em 0 .4em; } .emr-pp p { margin: .5em 0; }',
    '.emr-pp table { border-collapse: collapse; width: 100%; margin: 8px 0; } .emr-pp td, .emr-pp th { border: 1px solid #000; padding: 4px 8px; }',
    '.emr-pp th { background: #f2f2f2; }',
    '.emr-pp-field { border-bottom: 1px solid #000; padding: 0 6px; }',
    '.emr-pp-sec-title { font-weight: bold; margin: 12px 0 4px; font-size: 15px; }',
    '.emr-pp-macro { font-weight: 600; }',
    '.emr-pp-pagebreak { page-break-after: always; border-top: 1px dashed #999; margin: 24px 0 8px; text-align: center; color: #999; font-size: 12px; }',
    '.emr-pp-pagebreak::after { content: "（以下分页）"; }',
    '.emr-pp-fragment { border: 1px dashed #999; padding: 6px 10px; margin: 6px 0; color: #555; }',
    '.emr-pp-drawing { text-align: center; margin: 8px 0; } .emr-pp-drawing svg { max-width: 100%; height: auto; }',
    '.emr-print-hidden { display: none !important; }',
    '@page { margin: 18mm 16mm; }',
    '.emr-pp-toolbar { position: fixed; right: 16px; top: 12px; } .emr-pp-toolbar button { padding: 6px 18px; margin-left: 8px; cursor: pointer; }',
    '@media print { .emr-pp-toolbar { display: none; } }'
  ].join('\n');

  /* ================= 工具 ================= */
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function safeColor(c) { return /^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(String(c || '')) ? c : ''; }
  function safeSrc(src) {
    const s = String(src || '');
    return /^(https?:|data:image\/|\/|\.\/|\.\.\/)/i.test(s) ? s : '';
  }
  function resolveContainer(c) {
    if (!c) { return null; }
    if (typeof c === 'string') { return document.querySelector(c); }
    return c && c.nodeType === 1 ? c : null;
  }
  function fmtDate(d, withTime) {
    const p = function (n) { return (n < 10 ? '0' : '') + n; };
    const s = d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
    return withTime ? s + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) : s;
  }
  function EX() { return HIS.EmrExtensions || {}; }
  function fmtVal(v) {
    const f = EX().formatFieldValue;
    return f ? f(v) : (v == null ? '' : String(v));
  }

  /* ================= 宏变量解析器(客户端预览) ================= */
  function patientOf(ctx, key) { return (ctx.patient && ctx.patient[key]) || ctx[key] || ''; }
  const MACRO_PRESETS = [
    { code: 'patientName', label: '患者姓名', source: 'patient', resolve: function (c) { return patientOf(c, 'name'); } },
    { code: 'patientGender', label: '患者性别', source: 'patient', resolve: function (c) { return patientOf(c, 'gender'); } },
    { code: 'patientAge', label: '患者年龄', source: 'patient', resolve: function (c) { return patientOf(c, 'age'); } },
    { code: 'inpatientNo', label: '住院号', source: 'patient', resolve: function (c) { return patientOf(c, 'inpatientNo') || patientOf(c, 'inpNo'); } },
    { code: 'deptName', label: '科室', source: 'visit', resolve: function (c) { return c.deptName || (c.visit && c.visit.deptName) || ''; } },
    { code: 'doctorName', label: '医师签名', source: 'staff', resolve: function (c) { return c.doctorName || ((typeof HIS.getUser === 'function' ? HIS.getUser() : null) || {}).userName || ''; } },
    { code: 'today', label: '当前日期', source: 'system', resolve: function () { return fmtDate(new Date()); } },
    { code: 'now', label: '当前时间', source: 'system', resolve: function () { return fmtDate(new Date(), true); } }
  ];
  const macroRegistry = {};
  const EmrMacroResolver = {
    presets: MACRO_PRESETS,
    /* register(code, valueOrFn) — 注册/覆盖宏解析器; fn(ctx) 可返回 Promise */
    register: function (code, resolver) { macroRegistry[code] = resolver; },
    unregister: function (code) { delete macroRegistry[code]; },
    resolve: function (code, ctx) {
      const r = macroRegistry[code];
      if (typeof r === 'function') { return Promise.resolve().then(function () { return r(ctx); }); }
      if (r != null) { return Promise.resolve(r); }
      const p = MACRO_PRESETS.filter(function (m) { return m.code === code; })[0];
      return p ? Promise.resolve().then(function () { return p.resolve(ctx || {}); }) : Promise.resolve(null);
    },
    /* resolveAll(editorOrWrapper, ctx) — 解析文档内全部宏并回写 resolvedValue, 返回 {code:value} */
    resolveAll: function (editorOrWrapper, ctx) {
      const ed = editorOrWrapper && editorOrWrapper.editor ? editorOrWrapper.editor : editorOrWrapper;
      if (!ed || !ed.state) { return Promise.resolve({}); }
      const codes = {};
      ed.state.doc.descendants(function (n) {
        if (n.type.name === 'emrMacro' && n.attrs.macroCode) { codes[n.attrs.macroCode] = 1; }
      });
      const self = this;
      return Promise.all(Object.keys(codes).map(function (c) {
        return self.resolve(c, ctx).then(function (v) { return [c, v]; }).catch(function () { return [c, null]; });
      })).then(function (pairs) {
        const map = {};
        if (typeof ed.commands.resolveEmrMacro === 'function') {
          const chain = ed.chain().focus();
          pairs.forEach(function (kv) { if (kv[1] != null && kv[1] !== '') { map[kv[0]] = kv[1]; chain.resolveEmrMacro(kv[0], String(kv[1])); } });
          chain.run();
        }
        return map;
      });
    }
  };

  /* ================= 打印预览(JSON → 干净 HTML) ================= */
  function collectFieldsFromJSON(json) {
    const map = {};
    (function walk(n) {
      if (!n) { return; }
      if (n.type === 'emrField' && n.attrs && n.attrs.fieldKey) { map[n.attrs.fieldKey] = n.attrs.value; }
      (n.content || []).forEach(walk);
    })(json);
    return map;
  }
  /* extractDocumentFields(doc) — 收集全部 emrField 的 {fieldKey,fieldName}(按 fieldKey 去重; 兼容 PM Node 与 JSON 两种形态) */
  function extractDocumentFields(doc) {
    const fields = [];
    (function walk(n) {
      if (!n) { return; }
      const tn = n.type && n.type.name ? n.type.name : n.type;
      if (tn === 'emrField') {
        const a = n.attrs || {};
        if (a.fieldKey) { fields.push({ fieldKey: a.fieldKey, fieldName: a.fieldName || a.fieldKey }); }
      }
      const items = n.content ? (n.content.content || n.content) : null;
      if (Array.isArray(items)) { items.forEach(walk); }
    })(doc);
    const seen = {};
    return fields.filter(function (f) { if (seen[f.fieldKey]) { return false; } seen[f.fieldKey] = 1; return true; });
  }
  /* 片段打印展开: 收集 fragmentId → POST /fragment/resolve 批量解析为 {fragmentId: 文档JSON} */
  function normFragmentId(id) { return String(id == null ? '' : id).replace(/^F-/i, ''); }
  function collectFragmentIdsFromJSON(json) {
    const ids = {};
    (function walk(n) {
      if (!n) { return; }
      if (n.type === 'emrFragment' && n.attrs && n.attrs.fragmentId != null && n.attrs.fragmentId !== '') {
        const nid = normFragmentId(n.attrs.fragmentId); if (/^\d+$/.test(nid)) { ids[nid] = 1; }
      }
      (n.content || []).forEach(walk);
    })(json);
    return Object.keys(ids);
  }
  function resolveFragmentDocs(json) {
    const ids = collectFragmentIdsFromJSON(json);
    /* ID 按字符串下发: 雪花ID超 JS 安全整数, 数字化会静默丢精度(后端 Jackson 可把 "123" 强转为 Long) */
    if (!ids.length || typeof HIS.post !== 'function') { return Promise.resolve({}); }
    return HIS.post(FRAG_RESOLVE_URL, ids).then(function (m) {
      const out = {};
      Object.keys(m || {}).forEach(function (k) {
        let doc = m[k];
        if (typeof doc === 'string') { try { doc = JSON.parse(doc); } catch (e) { doc = null; } }
        if (doc && typeof doc === 'object') { out[normFragmentId(k)] = doc; }
      });
      return out;
    }).catch(function () { return {}; });                        /* 解析失败不阻断打印, 留"未展开"占位 */
  }
  function textStyleCss(a) {
    const css = [];
    if (a) {
      const c = safeColor(a.color); if (c) { css.push('color:' + c); }
      if (a.fontFamily) { css.push("font-family:'" + String(a.fontFamily).replace(/['";{}]/g, '') + "'"); }
      if (a.fontSize && /^[0-9.]+(px|pt|em|rem|%)$/.test(String(a.fontSize))) { css.push('font-size:' + a.fontSize); }
    }
    return css.join(';');
  }
  function alignAttr(a) {
    return a && a.textAlign && ALIGNS.indexOf(a.textAlign) >= 0 ? ' style="text-align:' + a.textAlign + '"' : '';
  }
  function withMarks(html, marks) {
    (marks || []).forEach(function (m) {
      switch (m.type) {
        case 'emrPrintControl': html = ''; break;                       /* 打印隐藏: 内容整体剔除 */
        case 'bold': html = '<strong>' + html + '</strong>'; break;
        case 'italic': html = '<em>' + html + '</em>'; break;
        case 'underline': html = '<u>' + html + '</u>'; break;
        case 'strike': html = '<s>' + html + '</s>'; break;
        case 'code': html = '<code>' + html + '</code>'; break;
        case 'color': { const c = safeColor(m.attrs && m.attrs.color); html = c ? '<span style="color:' + c + '">' + html + '</span>' : html; break; }
        case 'textStyle': { const s = textStyleCss(m.attrs); html = s ? '<span style="' + s + '">' + html + '</span>' : html; break; }
      }
    });
    return html;
  }
  function textOf(n) {
    let s = '';
    (n.content || []).forEach(function (c) { if (c.type === 'text') { s += c.text || ''; } });
    return s;
  }
  function renderNode(n, ctx) {
    if (!n) { return ''; }
    const a = n.attrs || {};
    const kids = function () { return renderInline(n.content, ctx); };
    switch (n.type) {
      case 'text': return withMarks(escapeHtml(n.text || ''), n.marks);
      case 'hardBreak': return '<br>';
      case 'paragraph': return '<p' + alignAttr(a) + '>' + kids() + '</p>';
      case 'heading': { const lv = a.level || 1; return '<h' + lv + alignAttr(a) + '>' + kids() + '</h' + lv + '>'; }
      case 'bulletList': return '<ul>' + kids() + '</ul>';
      case 'orderedList': return '<ol>' + kids() + '</ol>';
      case 'listItem': return '<li>' + kids() + '</li>';
      case 'blockquote': return '<blockquote>' + kids() + '</blockquote>';
      case 'codeBlock': return '<pre><code>' + escapeHtml(textOf(n)) + '</code></pre>';
      case 'horizontalRule': return '<hr>';
      case 'image': {
        const src = safeSrc(a.src);
        return src ? '<img src="' + escapeHtml(src) + '" alt="' + escapeHtml(a.alt || '') + '"' +
          (a.title ? ' title="' + escapeHtml(a.title) + '"' : '') + ' style="max-width:100%">' : '';
      }
      case 'table': return '<table>' + kids() + '</table>';
      case 'tableRow': return '<tr>' + kids() + '</tr>';
      case 'tableCell': return '<td>' + kids() + '</td>';
      case 'tableHeader': return '<th>' + kids() + '</th>';
      case 'emrField': {
        const v = fmtVal(a.value);
        return '<span class="emr-pp-field">' + (a.fieldName ? escapeHtml(a.fieldName) + '：' : '') +
          (v === '' ? '&nbsp;' : escapeHtml(v)) + '</span>';
      }
      case 'emrMacro': {
        const rv = (a.resolvedValue != null && a.resolvedValue !== '') ? String(a.resolvedValue) : null;
        return '<span class="emr-pp-macro">' + escapeHtml(rv || ('【' + (a.macroCode || '宏变量') + '】')) + '</span>';
      }
      case 'emrSection':
        if (a.printHidden) { return ''; }                                /* 打印隐藏章节整体剔除; 折叠态打印时强制展开 */
        return '<div class="emr-pp-section">' +
          (a.title || a.sectionKey ? '<div class="emr-pp-sec-title">' + escapeHtml(a.title || a.sectionKey) + '</div>' : '') +
          kids() + '</div>';
      case 'emrFragment': {
        const fd = ctx.frags ? ctx.frags[normFragmentId(a.fragmentId)] : null;
        if (fd && (ctx.fragDepth || 0) < 5) {                             /* 已解析: 递归渲染片段文档(限深防循环引用) */
          return '<div class="emr-pp-fragment">' + (a.title ? '<div class="emr-pp-sec-title">' + escapeHtml(a.title) + '</div>' : '') +
            renderNode(fd, Object.assign({}, ctx, { fragDepth: (ctx.fragDepth || 0) + 1 })) + '</div>';
        }
        return '<div class="emr-pp-fragment">［引用片段: ' + escapeHtml(a.title || a.fragmentId || '') + '（未展开）］</div>';
      }
      case 'emrConditionalBlock':
        return EX().evaluateCondition ? (EX().evaluateCondition(a, ctx.fields) ? kids() : '') : kids();
      case 'emrPageBreak': return '<div class="emr-pp-pagebreak"></div>';
      case 'emrDrawing': {
        const svg = typeof a.svgData === 'string' && a.svgData.replace(/^\s+/, '').indexOf('<svg') === 0 ? a.svgData : '';
        return svg ? '<div class="emr-pp-drawing">' + svg +
          (a.title ? '<div class="emr-pp-cap">' + escapeHtml(a.title) + '</div>' : '') + '</div>' : '';
      }
      default: return kids();                                            /* doc 及未知节点: 递归内容 */
    }
  }
  function renderInline(nodes, ctx) {
    let out = '';
    (nodes || []).forEach(function (n) { out += renderNode(n, ctx); });
    return out;
  }
  const EmrPrintPreview = {
    /* generateHTML(json, {fields, frags}) — 字段控件→纯文本值, 宏→解析值, 打印隐藏剔除, 条件块求值;
     * frags: {fragmentId: 文档JSON} 已解析片段(可选, 缺省片段以"未展开"占位) */
    generateHTML: function (json, options) {
      if (!json) { return ''; }
      const o = options || {};
      const fields = Object.assign(collectFieldsFromJSON(json), o.fields || {});
      return renderNode(json, { fields: fields, frags: o.frags || {} });
    },
    /* generateHTMLAsync(json, {fields}) — 先批量解析文档内片段引用(POST /fragment/resolve)再渲染 */
    generateHTMLAsync: function (json, options) {
      if (!json) { return Promise.resolve(''); }
      const o = options || {};
      return resolveFragmentDocs(json).then(function (frags) { o.frags = frags; return EmrPrintPreview.generateHTML(json, o); });
    },
    /* print(editorOrJson, {title, fields, printScript, autoPrint}) — 先解析片段再新窗口预览并打印(返回 Promise<window>) */
    print: function (editorOrJson, options) {
      const o = options || {};
      const json = editorOrJson && typeof editorOrJson.getJSON === 'function' ? editorOrJson.getJSON() : editorOrJson;
      if (!json) { return Promise.resolve(null); }
      return EmrPrintPreview.generateHTMLAsync(json, o).then(function (body) {
        const printScript = o.printScript ? String(o.printScript).replace(/<\/script/gi, '<\\/script') : '';
        const w = window.open('', '_blank', 'width=980,height=760');
        if (!w) {
          if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.error('打印窗口被浏览器拦截，请允许弹窗后重试'); }
          return null;
        }
        w.document.open();
        w.document.write([
          '<!DOCTYPE html><html><head><meta charset="utf-8"><title>', escapeHtml(o.title || '病历打印预览'),
          '</title><style>', PP_CSS, '</style></head><body>',
          '<div class="emr-pp-toolbar"><button onclick="window.print()">打印</button><button onclick="window.close()">关闭</button></div>',
          '<div class="emr-pp">', body, '</div>',
          printScript ? '<script>' + printScript + '<\/script>' : '',
          '</body></html>'
        ].join(''));
        w.document.close();
        if (o.autoPrint !== false) { setTimeout(function () { try { w.focus(); w.print(); } catch (e) { /* noop */ } }, 350); }
        return w;
      });
    }
  };

  /* ================= 版本对比(扁平化 + LCS 逐行 diff) ================= */
  function flattenDoc(json) {
    const lines = [];
    let indent = 0;
    const pad = function () { return new Array(indent + 1).join('  '); };
    function inlineText(n) {
      let s = '';
      (n.content || []).forEach(function (c) {
        if (c.type === 'text') { s += c.text || ''; }
        else if (c.type === 'emrField') { s += '「' + (c.attrs.fieldName || c.attrs.fieldKey || '') + '：' + fmtVal(c.attrs.value) + '」'; }
        else if (c.type === 'emrMacro') { s += '【' + (c.attrs.macroCode || '') + (c.attrs.resolvedValue ? '=' + c.attrs.resolvedValue : '') + '】'; }
        else if (c.type === 'hardBreak') { s += ' '; }
        else if (c.content) { s += inlineText(c); }
      });
      return s;
    }
    (function walk(n) {
      if (!n) { return; }
      const a = n.attrs || {};
      switch (n.type) {
        case 'heading': lines.push(pad() + '######'.slice(0, a.level || 1) + ' ' + inlineText(n)); break;
        case 'paragraph': lines.push(pad() + inlineText(n)); break;
        case 'blockquote': lines.push(pad() + '> ' + inlineText(n)); break;
        case 'codeBlock': lines.push(pad() + '`` ' + textOf(n)); break;
        case 'emrSection':
          lines.push(pad() + '◆ ' + (a.title || a.sectionKey || '章节'));
          indent++; (n.content || []).forEach(walk); indent--; break;
        case 'emrConditionalBlock':
          lines.push(pad() + '? 条件: ' + (a.conditionFieldKey || '?') + ' ' + (a.conditionOperator || '') + ' ' + (a.conditionValue == null ? '' : a.conditionValue));
          indent++; (n.content || []).forEach(walk); indent--; break;
        case 'listItem': (n.content || []).forEach(walk); break;
        case 'bulletList': case 'orderedList': (n.content || []).forEach(walk); break;
        case 'table':
          (n.content || []).forEach(function (row) {
            const cells = (row.content || []).map(function (cell) { return inlineText(cell).trim(); });
            lines.push(pad() + '| ' + cells.join(' | ') + ' |');
          });
          break;
        case 'emrFragment': lines.push(pad() + '§ 片段#' + (a.fragmentId || '') + (a.title ? ' ' + a.title : '')); break;
        case 'emrPageBreak': lines.push(pad() + '———— 分页 ————'); break;
        case 'emrDrawing': lines.push(pad() + '[医学图示' + (a.title ? ': ' + a.title : '') + ']'); break;
        case 'horizontalRule': lines.push(pad() + '────────'); break;
        default: (n.content || []).forEach(walk);
      }
    })(json);
    return lines;
  }
  function diffLines(a, b) {
    const n = a.length, m = b.length, MAX = 600;
    if (n > MAX || m > MAX) { return null; }                            /* 行数过大: 停用 LCS 防卡顿 */
    const w = m + 1;
    const dp = new Array((n + 1) * w).fill(0);
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        dp[i * w + j] = a[i] === b[j] ? dp[(i + 1) * w + (j + 1)] + 1 : Math.max(dp[(i + 1) * w + j], dp[i * w + (j + 1)]);
      }
    }
    const ops = [];
    let i = 0, j = 0;
    while (i < n && j < m) {
      if (a[i] === b[j]) { ops.push({ op: 'same', text: a[i] }); i++; j++; }
      else if (dp[(i + 1) * w + j] >= dp[i * w + (j + 1)]) { ops.push({ op: 'del', text: a[i] }); i++; }
      else { ops.push({ op: 'add', text: b[j] }); j++; }
    }
    while (i < n) { ops.push({ op: 'del', text: a[i++] }); }
    while (j < m) { ops.push({ op: 'add', text: b[j++] }); }
    return ops;
  }
  const EmrDiffViewer = {
    name: 'EmrDiffViewer',
    props: {
      oldDoc: { type: Object, default: null },
      newDoc: { type: Object, default: null },
      oldLabel: { type: String, default: '旧版本' },
      newLabel: { type: String, default: '新版本' }
    },
    data: function () { return { ops: [], oversized: false }; },
    watch: {
      oldDoc: { immediate: true, handler: 'rebuild' },
      newDoc: { immediate: true, handler: 'rebuild' }
    },
    computed: {
      rows: function () {
        return this.ops.map(function (o) {
          if (o.op === 'same') { return { l: o.text, r: o.text, lc: 'emr-diff-same', rc: 'emr-diff-same' }; }
          if (o.op === 'del') { return { l: o.text, r: '', lc: 'emr-diff-del', rc: 'emr-diff-empty' }; }
          return { l: '', r: o.text, lc: 'emr-diff-empty', rc: 'emr-diff-add' };
        });
      },
      stat: function () {
        let d = 0, ad = 0;
        this.ops.forEach(function (o) { if (o.op === 'del') { d++; } else if (o.op === 'add') { ad++; } });
        return { del: d, add: ad };
      }
    },
    methods: {
      rebuild: function () {
        if (!this.oldDoc && !this.newDoc) { this.ops = []; this.oversized = false; return; }
        const ops = diffLines(flattenDoc(this.oldDoc), flattenDoc(this.newDoc));
        this.oversized = ops === null;
        this.ops = ops || [];
      }
    },
    template: [
      '<div class="emr-diff">',
      '  <div class="emr-diff-bar">',
      '    <span>差异: 删除 <b class="emr-diff-del-text">{{ stat.del }}</b> 行 / 新增 <b class="emr-diff-add-text">{{ stat.add }}</b> 行</span>',
      '  </div>',
      '  <div v-if="oversized" class="emr-diff-warn">文档行数过大（超过 600 行），已停用逐行对比。</div>',
      '  <table v-else class="emr-diff-table">',
      '    <thead><tr><th style="width:50%">{{ oldLabel }}</th><th style="width:50%">{{ newLabel }}</th></tr></thead>',
      '    <tbody>',
      '      <tr v-for="(r, i) in rows" :key="i"><td :class="r.lc" v-text="r.l"></td><td :class="r.rc" v-text="r.r"></td></tr>',
      '    </tbody>',
      '  </table>',
      '</div>'
    ].join('\n')
  };
  EmrDiffViewer.mount = function (el, props) {
    const app = Vue.createApp(EmrDiffViewer, props || {});
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= 字段渲染组件(宿主侧独立使用, 与 NodeView 行为一致) ================= */
  const EmrFieldRenderer = {
    name: 'EmrFieldRenderer',
    props: {
      field: { type: Object, required: true },                          /* emrField attrs */
      modelValue: { type: null, default: null },
      disabled: Boolean
    },
    emits: ['update:modelValue'],
    data: function () {
      const f = this.field || {};
      const vt = f.valueType || 'text';
      const isMulti = vt === 'multiselect' || vt === 'checkbox';
      let v = f.value;
      if (isMulti) {
        if (Array.isArray(v)) { v = v.slice(); }
        else if (typeof v === 'string' && v.indexOf('[') === 0) {
          try { const arr = JSON.parse(v); v = Array.isArray(arr) ? arr : []; } catch (e) { v = []; }
        } else { v = []; }
      } else { v = v == null ? (vt === 'number' ? null : '') : v; }
      return { val: v, remote: [], loading: false };
    },
    computed: {
      vt: function () { return (this.field.valueType || 'text'); },
      isMulti: function () { return this.vt === 'multiselect' || this.vt === 'checkbox'; },
      isDict: function () { return !!this.field.dictSource && (this.vt === 'select' || this.vt === 'multiselect' || this.vt === 'dict'); },
      localOpts: function () { return EX().parseFieldOptions ? EX().parseFieldOptions(this.field.options) : []; },
      candidates: function () { return this.remote.length ? this.remote : this.localOpts; }
    },
    watch: {
      modelValue: function (v) {
        if (!EX().valuesEqual || !EX().valuesEqual(v, this.val)) { this.val = v == null ? (this.vt === 'number' ? null : '') : v; }
      },
      val: {
        deep: true,
        handler: function (v) { this.$emit('update:modelValue', this.isMulti ? (Array.isArray(v) ? v.slice() : []) : v); }
      }
    },
    created: function () {
      if (this.isDict && typeof HIS.emrLoadDict === 'function') { this.search(''); }
    },
    methods: {
      search: function (kw) {
        const self = this;
        if (!this.isDict || typeof HIS.emrLoadDict !== 'function') { return; }
        self.loading = true;
        Promise.resolve(HIS.emrLoadDict(self.field.dictSource, kw)).then(function (list) {
          self.remote = (list || []).map(function (o) { return { label: o.name, value: o.name }; });
        }).catch(function () {}).then(function () { self.loading = false; });
      }
    },
    template: [
      '<span class="emr-fr">',
      '  <el-input v-if="vt === \'text\'" v-model="val" size="small" style="width:180px" :disabled="disabled" :placeholder="field.placeholder || \'\'"></el-input>',
      '  <el-input-number v-else-if="vt === \'number\'" v-model="val" size="small" controls-position="right" style="width:140px" :disabled="disabled"></el-input-number>',
      '  <el-date-picker v-else-if="vt === \'date\'" v-model="val" type="date" value-format="YYYY-MM-DD" size="small" style="width:170px" :disabled="disabled"></el-date-picker>',
      '  <el-select v-else-if="vt === \'select\' || vt === \'dict\'" v-model="val" size="small" filterable clearable style="width:210px" :disabled="disabled" :loading="loading" :remote="isDict" :remote-method="search">',
      '    <el-option v-for="o in candidates" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '  </el-select>',
      '  <el-select v-else-if="vt === \'multiselect\'" v-model="val" size="small" multiple filterable clearable collapse-tags style="width:260px" :disabled="disabled" :loading="loading" :remote="isDict" :remote-method="search">',
      '    <el-option v-for="o in candidates" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '  </el-select>',
      '  <el-checkbox-group v-else-if="vt === \'checkbox\'" v-model="val" :disabled="disabled">',
      '    <el-checkbox v-for="o in candidates" :key="o.value" :label="o.value">{{ o.label }}</el-checkbox>',
      '  </el-checkbox-group>',
      '  <el-input v-else v-model="val" size="small" style="width:180px" :disabled="disabled"></el-input>',
      '  <span v-if="field.unit" class="emr-f-unit">{{ field.unit }}</span>',
      '</span>'
    ].join('\n')
  };
  /* Vue 3 已移除 $on: 静态挂载用 h() 渲染函数直传 listener props */
  EmrFieldRenderer.mount = function (el, field, handlers) {
    const h = Vue.h;
    const onValue = handlers && handlers.onValue;
    const app = Vue.createApp({
      render: function () {
        return h(EmrFieldRenderer, {
          field: field,
          modelValue: field && field.value != null ? field.value : null,
          disabled: !!(handlers && handlers.disabled),
          'onUpdate:modelValue': function (v) { if (onValue) { onValue(v); } }
        });
      }
    });
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= 章节渲染组件(宿主侧独立使用) ================= */
  const EmrSectionRenderer = {
    name: 'EmrSectionRenderer',
    props: {
      section: { type: Object, required: true },                        /* emrSection attrs */
      collapsed: Boolean
    },
    emits: ['toggle', 'toggle-lock', 'toggle-print'],
    computed: {
      a: function () { return this.section || {}; },
      modeLabel: function () { return (EX().EDIT_MODE_LABEL || {})[this.a.editMode] || ''; }
    },
    template: [
      '<div class="emr-section" :class="{ \'emr-section--collapsed\': collapsed, \'emr-section--locked\': a.locked, \'emr-section--print-hidden\': a.printHidden }">',
      '  <div class="emr-section-head">',
      '    <span v-if="a.collapsible !== false" class="emr-section-toggle" title="折叠/展开" @click="$emit(\'toggle\')">{{ collapsed ? \'▸\' : \'▾\' }}</span>',
      '    <span class="emr-section-title">{{ a.title || a.sectionKey || \'章节\' }}</span>',
      '    <span v-if="modeLabel" class="emr-section-badge">{{ modeLabel }}</span>',
      '    <span class="emr-section-flag" :class="{ \'emr-section-flag--on\': a.locked }" title="锁定/解锁章节" @click="$emit(\'toggle-lock\')">{{ a.locked ? \'已锁\' : \'锁定\' }}</span>',
      '    <span class="emr-section-flag" :class="{ \'emr-section-flag--on\': a.printHidden }" title="打印时隐藏/显示" @click="$emit(\'toggle-print\')">{{ a.printHidden ? \'不打印\' : \'打印隐藏\' }}</span>',
      '  </div>',
      '  <div class="emr-section-body"><slot></slot></div>',
      '</div>'
    ].join('\n')
  };
  /* Vue 3 已移除 $on: 静态挂载用 h() 渲染函数直传 listener props */
  EmrSectionRenderer.mount = function (el, section, handlers) {
    const h = Vue.h;
    const hd = handlers || {};
    const app = Vue.createApp({
      render: function () {
        return h(EmrSectionRenderer, {
          section: section,
          collapsed: !!(section && section.collapsed),
          onToggle: hd.toggle,
          'onToggle-lock': hd['toggle-lock'],
          'onToggle-print': hd['toggle-print']
        });
      }
    });
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= NLG 自然语言预览面板(编辑器底部, 可折叠) ================= */
  const EmrNlgPanel = {
    name: 'EmrNlgPanel',
    data: function () { return { sections: [], loading: false, failed: false, collapsed: false }; },
    emits: ['refresh', 'toggle-collapse'],
    methods: {
      /* setState(patch) — 控制器直改实例状态(Vue 全局构建下的最简驱动方式) */
      setState: function (patch) {
        const self = this;
        Object.keys(patch || {}).forEach(function (k) { self[k] = patch[k]; });
      }
    },
    template: [
      '<div class="emr-nlg-panel">',
      '  <div class="emr-nlg-head" title="点击折叠/展开" @click="$emit(\'toggle-collapse\')">',
      '    <span class="emr-nlg-arrow">{{ collapsed ? \'▸\' : \'▾\' }}</span>',
      '    <span class="emr-nlg-title">自然语言预览</span>',
      '    <span v-if="loading" class="emr-nlg-loading"><i class="emr-nlg-spin"></i>生成中…</span>',
      '    <span v-else-if="failed" class="emr-nlg-failed">NLG 服务不可用</span>',
      '    <span class="emr-nlg-gap"></span>',
      '    <el-button size="small" text type="primary" @click.stop="$emit(\'refresh\')">刷新</el-button>',
      '  </div>',
      '  <div v-show="!collapsed" class="emr-nlg-body">',
      '    <div v-if="!sections.length && !loading && !failed" class="emr-nlg-empty">暂无生成内容，录入数据元后自动生成</div>',
      '    <div v-for="(s, i) in sections" :key="s.key || i" class="emr-nlg-section">',
      '      <div class="emr-nlg-sec-name">{{ s.title }}</div>',
      '      <div class="emr-nlg-text">{{ s.text }}</div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
  EmrNlgPanel.mount = function (el, handlers) {
    const hd = handlers || {};
    const app = Vue.createApp(EmrNlgPanel, { onRefresh: hd.refresh, 'onToggle-collapse': hd.toggleCollapse });
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= CDSS 临床提醒面板(右侧滑入浮层) ================= */
  const CDSS_LEVELS = {
    block: { label: '拦截', icon: '🔴' },
    warning: { label: '警告', icon: '🟡' },
    info: { label: '信息', icon: '🔵' }
  };
  const EmrCdssPanel = {
    name: 'EmrCdssPanel',
    data: function () { return { visible: false, loading: false, failed: false, alerts: [], levels: CDSS_LEVELS }; },
    emits: ['close'],
    computed: {
      counts: function () {
        const c = { block: 0, warning: 0, info: 0 };
        this.alerts.forEach(function (a) { c[a && CDSS_LEVELS[a.level] ? a.level : 'info']++; });
        return c;
      },
      ordered: function () {
        return this.alerts.slice().sort(function (a, b) { return severityRank(a && a.level) - severityRank(b && b.level); });
      }
    },
    methods: {
      setState: function (patch) {
        const self = this;
        Object.keys(patch || {}).forEach(function (k) { self[k] = patch[k]; });
      },
      levelOf: function (a) { return a && CDSS_LEVELS[a.level] ? a.level : 'info'; },
      isLink: function (s) { return /^https?:\/\//i.test(String(s || '')); }
    },
    template: [
      '<div class="emr-cdss-panel" :class="{ \'emr-cdss-panel--open\': visible }">',
      '  <div class="emr-cdss-head">',
      '    <span class="emr-cdss-title">临床提醒</span>',
      '    <el-button size="small" text @click="$emit(\'close\')">关闭</el-button>',
      '  </div>',
      '  <div class="emr-cdss-summary">',
      '    <span class="emr-cdss-chip emr-cdss-chip--block">🔴 拦截 {{ counts.block }} 项</span>',
      '    <span class="emr-cdss-chip emr-cdss-chip--warning">🟡 警告 {{ counts.warning }} 项</span>',
      '    <span class="emr-cdss-chip emr-cdss-chip--info">🔵 信息 {{ counts.info }} 项</span>',
      '  </div>',
      '  <div class="emr-cdss-body" v-loading="loading">',
      '    <div v-if="failed" class="emr-cdss-note">临床提醒服务不可用</div>',
      '    <div v-else-if="!alerts.length && !loading" class="emr-cdss-note">未发现需关注的临床提醒</div>',
      '    <div v-for="(a, i) in ordered" :key="(a && a.ruleId) || i" class="emr-cdss-alert" :class="\'emr-cdss-alert--\' + levelOf(a)">',
      '      <div class="emr-cdss-alert-name">{{ levels[levelOf(a)].icon }} {{ a.ruleName || a.ruleCode || \'临床提醒\' }}<span class="emr-cdss-level-tag">{{ levels[levelOf(a)].label }}</span></div>',
      '      <div v-if="a.message" class="emr-cdss-alert-msg">{{ a.message }}</div>',
      '      <a v-if="isLink(a.knowledgeSource)" class="emr-cdss-alert-src" :href="a.knowledgeSource" target="_blank" rel="noopener">知识来源</a>',
      '      <div v-else-if="a.knowledgeSource" class="emr-cdss-alert-src">知识来源：{{ a.knowledgeSource }}</div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
  EmrCdssPanel.mount = function (el, handlers) {
    const hd = handlers || {};
    const app = Vue.createApp(EmrCdssPanel, { onClose: hd.close });
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= NLG / CDSS 控制器(createEditor 内装配) ================= */
  function severityRank(level) { return level === 'block' ? 0 : (level === 'warning' ? 1 : 2); }
  function collectSectionsFromJSON(json) {
    const list = [];
    (function walk(n) {
      if (!n) { return; }
      if (n.type === 'emrSection' && n.attrs && n.attrs.sectionKey) { list.push({ key: n.attrs.sectionKey, title: n.attrs.title || n.attrs.sectionKey }); }
      (n.content || []).forEach(walk);
    })(json);
    return list;
  }
  /* createNlgController — 字段变更防抖 2s → POST /nlg/generate-document, 结果按章节展示;
   * 面板挂载于编辑器 DOM 之后(容器内底部), 服务不可用降级提示, 不阻断编辑 */
  function createNlgController(opts) {
    let wrapper = null, mountEl = null, panel = null, timer = null, seq = 0;
    let visible = true, collapsed = false;
    function applyVisible() { if (mountEl) { mountEl.style.display = visible ? '' : 'none'; } }
    function ensureMounted() {
      if (panel || !wrapper) { return; }
      const anchor = wrapper.editor && wrapper.editor.view ? wrapper.editor.view.dom : null;
      if (!anchor || !anchor.parentNode) { return; }                     /* 宿主未就绪, 稍后 setVisible/refresh 重试 */
      mountEl = document.createElement('div');
      mountEl.className = 'emr-nlg-host';
      anchor.insertAdjacentElement('afterend', mountEl);
      panel = EmrNlgPanel.mount(mountEl, {
        refresh: function () { generate(); },
        toggleCollapse: function () { collapsed = !collapsed; if (panel) { panel.vm.setState({ collapsed: collapsed }); } }
      });
      panel.vm.setState({ collapsed: collapsed });
      applyVisible();
    }
    function generate() {
      if (!wrapper) { return; }
      const mySeq = ++seq;
      if (panel) { panel.vm.setState({ loading: true, failed: false }); }
      if (typeof HIS.post !== 'function') { if (panel) { panel.vm.setState({ loading: false, failed: true, sections: [] }); } return; }
      HIS.post(NLG_DOC_URL, { documentJson: wrapper.toJSON(), recordType: opts.recordType || '' })
        .then(function (m) {
          if (mySeq !== seq || !panel) { return; }
          const titleOf = {};
          collectSectionsFromJSON(wrapper.toJSON()).forEach(function (s) { titleOf[s.key] = s.title; });
          const list = Object.keys(m || {}).map(function (k) {
            return { key: k, title: titleOf[k] || k, text: m[k] == null ? '' : String(m[k]) };
          }).filter(function (s) { return s.text !== ''; });
          panel.vm.setState({ sections: list, loading: false, failed: false });
        })
        .catch(function () {
          if (mySeq !== seq || !panel) { return; }
          panel.vm.setState({ sections: [], loading: false, failed: true });
        });
    }
    function schedule() {
      if (!visible) { return; }                                          /* 面板隐藏时不打扰后端 */
      if (timer) { clearTimeout(timer); }
      timer = setTimeout(function () { timer = null; generate(); }, 2000);
    }
    return {
      attach: function (w) { wrapper = w; if (visible) { ensureMounted(); } },
      schedule: schedule,
      refresh: function () { ensureMounted(); generate(); },
      setVisible: function (v) {
        visible = !!v;
        if (visible) { ensureMounted(); }
        applyVisible();
      },
      isVisible: function () { return visible; },
      destroy: function () {
        if (timer) { clearTimeout(timer); timer = null; }
        seq++;
        if (panel) { try { panel.app.unmount(); } catch (e) { /* noop */ } panel = null; }
        if (mountEl && mountEl.parentNode) { mountEl.parentNode.removeChild(mountEl); }
        mountEl = null;
      }
    };
  }
  /* createCdssController — POST /cdss/evaluate-document; block 级令 canProceed=false 拦截保存;
   * 仅 info(或空)结果 10s 后自动收起, block/warning 常驻待处理 */
  function createCdssController(opts) {
    let wrapper = null, mountEl = null, panel = null, seq = 0, autoTimer = null;
    function clearAuto() { if (autoTimer) { clearTimeout(autoTimer); autoTimer = null; } }
    function ensureMounted() {
      if (panel) { return; }
      mountEl = document.createElement('div');
      document.body.appendChild(mountEl);
      panel = EmrCdssPanel.mount(mountEl, { close: function () { setVisible(false); } });
    }
    function setVisible(v) {
      if (v) { ensureMounted(); }
      if (panel) { panel.vm.setState({ visible: !!v }); }
      if (!v) { clearAuto(); }
      if (wrapper) {
        wrapper.cdssShown = !!v;
        wrapper.emit('toolbar', wrapper.getToolbarState());
      }
    }
    function evaluate() {
      if (!wrapper) { return Promise.resolve({ canProceed: true, alerts: [] }); }
      ensureMounted();
      clearAuto();
      seq++;
      const mySeq = seq;
      panel.vm.setState({ visible: true, loading: true, failed: false, alerts: [] });
      wrapper.cdssShown = true;
      wrapper.emit('toolbar', wrapper.getToolbarState());
      if (typeof HIS.post !== 'function') {
        panel.vm.setState({ loading: false, failed: true });
        return Promise.resolve({ canProceed: true, alerts: [], failed: true });
      }
      return HIS.post(CDSS_DOC_URL, {
        documentJson: wrapper.toJSON(),
        recordType: opts.recordType || '',
        deptCode: opts.deptCode || ''
      }).then(function (list) {
        if (mySeq !== seq || !panel) { return { canProceed: true, alerts: [] }; }
        const alerts = (Array.isArray(list) ? list : []).slice().sort(function (a, b) { return severityRank(a && a.level) - severityRank(b && b.level); });
        panel.vm.setState({ alerts: alerts, loading: false, visible: true });
        const hard = alerts.filter(function (a) { return a && a.level === 'block'; }).length;
        if (!hard && !alerts.some(function (a) { return a && a.level === 'warning'; })) {
          autoTimer = setTimeout(function () { autoTimer = null; setVisible(false); }, 10000);
        }
        return { canProceed: hard === 0, alerts: alerts };
      }).catch(function () {
        if (mySeq !== seq || !panel) { return { canProceed: true, alerts: [] }; }
        panel.vm.setState({ loading: false, failed: true, alerts: [] });
        return { canProceed: true, alerts: [], failed: true };
      });
    }
    return {
      attach: function (w) { wrapper = w; },
      evaluate: evaluate,
      setVisible: setVisible,
      destroy: function () {
        clearAuto(); seq++;
        if (panel) { try { panel.app.unmount(); } catch (e) { /* noop */ } panel = null; }
        if (mountEl && mountEl.parentNode) { mountEl.parentNode.removeChild(mountEl); }
        mountEl = null;
      }
    };
  }

  /* ================= 工具栏组件 ================= */
  function newFieldForm() {
    return { fieldKey: '', fieldName: '', valueType: 'text', dictSource: '', options: '', required: false, readonly: false, noCopy: false };
  }
  const EmrToolbar = {
    name: 'EmrToolbar',
    props: {
      wrapper: { type: Object, required: true },                        /* createEditor 返回的包装对象 */
      compact: Boolean                                                    /* 紧凑模式: 隐藏撤销/重做 */
    },
    data: function () {
      return {
        ts: {},
        dlgField: false, dlgSection: false, dlgMacro: false, dlgFragment: false, dlgTable: false,
        dlgConditional: false, dlgDrawing: false,
        f: newFieldForm(),
        s: { title: '', editMode: 'mixed' },
        m: { macroCode: 'patientName', dataSource: 'patient' },
        g: { fragmentId: '', sourceTemplateId: '', version: '1', title: '' },
        t: { rows: 3, cols: 3, header: true },
        c: { fieldKey: '', operator: 'eq', value: '' },
        d: { title: '', templateId: '' },
        docFields: [],
        operators: EX().OPERATORS || [{ v: 'eq', l: '等于' }, { v: 'ne', l: '不等于' }, { v: 'contains', l: '包含' }, { v: 'empty', l: '为空' }, { v: 'notEmpty', l: '不为空' }],
        drawTemplates: [], drawTplLoading: false, drawTplFailed: false,
        fragList: [], fragLoading: false, fragMode: 'browse', fragOffline: false, fragSelected: null, fragKeyword: '',
        valueTypes: (EX().VALUE_TYPES || ['text']).map(function (v) { return { v: v, l: VT_LABEL[v] || v }; }),
        macroPresets: EmrMacroResolver.presets
      };
    },
    created: function () {
      const self = this;
      this.refresh();
      this._unsub = this.wrapper.on('toolbar', function () { self.refresh(); });
    },
    beforeUnmount: function () { if (this._unsub) { this._unsub(); } },
    methods: {
      refresh: function () { this.ts = this.wrapper.getToolbarState ? this.wrapper.getToolbarState() : {}; },
      cmd: function (name, arg) {
        const ch = this.wrapper.editor.chain().focus();
        const fn = ch[name];
        if (typeof fn !== 'function') { return; }
        try { (arg === undefined ? fn.call(ch) : fn.call(ch, arg)).run(); } catch (e) { /* 只读态命令静默失败 */ }
      },
      onColor: function (c) {
        const ch = this.wrapper.editor.chain().focus();
        try { if (c) { ch.setColor(c).run(); } else { ch.unsetColor().run(); } } catch (e) { /* noop */ }
      },
      onInsert: function (key) {
        const self = this;
        if (key === 'pagebreak') {
          try { this.wrapper.editor.chain().focus().insertEmrPageBreak().run(); } catch (e) { /* noop */ }
          return;
        }
        if (key === 'drawing') { self.d = { title: '', templateId: '' }; self.loadDrawTemplates(); self.dlgDrawing = true; return; }  /* 弹窗选底图模板 */
        if (key === 'conditional') {                                      /* 条件块: 数据元选项取自当前文档 */
          self.c = { fieldKey: '', operator: 'eq', value: '' };
          self.docFields = typeof self.wrapper.extractDocumentFields === 'function' ? self.wrapper.extractDocumentFields() : [];
          self.dlgConditional = true;
          return;
        }
        if (key === 'fragment') {                                         /* 片段: 浏览选择, 服务不可用回退手动 */
          self.fragMode = 'browse'; self.fragList = []; self.fragSelected = null; self.fragKeyword = ''; self.fragOffline = false;
          self.g = { fragmentId: '', sourceTemplateId: '', version: '1', title: '' };
          self.loadFragments(); self.dlgFragment = true;
          return;
        }
        const map = { field: 'dlgField', section: 'dlgSection', macro: 'dlgMacro', table: 'dlgTable' };
        if (map[key]) { self[map[key]] = true; }
      },
      onSave: function () { this.wrapper.emit('save', this.wrapper); },
      onPrint: function () { EmrPrintPreview.print(this.wrapper.editor, { title: '病历打印预览' }); },
      /* --- NLG/CDSS: 开关底部预览面板 / 手动触发临床提醒评估 --- */
      onNlgToggle: function () {
        const w = this.wrapper;
        if (typeof w.setNlgVisible === 'function') { w.setNlgVisible(!w.nlgShown); }
      },
      onCdss: function () {
        const w = this.wrapper;
        if (typeof w.evaluateCdss === 'function') { w.evaluateCdss(); }
      },
      confirmField: function () {
        this.wrapper.editor.chain().focus().insertEmrField({
          fieldKey: this.f.fieldKey || ('field_' + Date.now()),
          fieldName: this.f.fieldName || '', valueType: this.f.valueType,
          dictSource: this.f.dictSource || null,
          options: this.f.options || null,
          required: this.f.required, readonly: this.f.readonly, noCopy: this.f.noCopy
        }).run();
        this.dlgField = false; this.f = newFieldForm();
      },
      confirmSection: function () {
        this.wrapper.editor.chain().focus().insertEmrSection({ title: this.s.title || '新章节', editMode: this.s.editMode }).run();
        this.dlgSection = false; this.s = { title: '', editMode: 'mixed' };
      },
      confirmMacro: function () {
        this.wrapper.editor.chain().focus().insertEmrMacro({ macroCode: this.m.macroCode, dataSource: this.m.dataSource || null }).run();
        this.dlgMacro = false;
      },
      /* --- 条件块: 数据元取自当前文档, 为空/不为空运算符无需比较值 --- */
      confirmConditional: function () {
        if (!this.c.fieldKey) { if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.warning('请选择数据元'); } return; }
        const eo = this.c.operator === 'empty' || this.c.operator === 'notEmpty';
        this.wrapper.editor.chain().focus().insertEmrConditionalBlock({
          conditionFieldKey: this.c.fieldKey, conditionOperator: this.c.operator, conditionValue: eo ? '' : this.c.value, visible: true
        }).run();
        this.dlgConditional = false;
      },
      /* --- 医学图示: 选底图模板则取模板 SVG 存入节点, 未选/失败降级为仅标题空白图 --- */
      confirmDrawing: function () {
        const self = this;
        const title = this.d.title || '';
        const tplId = this.d.templateId;
        const insert = function (svgData) {
          try { self.wrapper.editor.chain().focus().insertEmrDrawing({ title: title, svgData: svgData || '' }).run(); } catch (e) { /* noop */ }
          self.dlgDrawing = false;
        };
        if (!tplId || typeof HIS.get !== 'function') { insert(''); return; }
        HIS.get(DRAW_TPL_URL + '/' + HIS.idParam(tplId)).then(function (tp) {
          const svg = typeof tp === 'string' ? tp : ((tp && (tp.svgTemplate || tp.svgData || tp.svg || tp.content)) || '');
          insert(typeof svg === 'string' && svg.replace(/^\s+/, '').indexOf('<svg') === 0 ? svg : '');
        }).catch(function () { insert(''); });
      },
      loadDrawTemplates: function () {
        const self = this;
        self.drawTplLoading = true; self.drawTplFailed = false;
        if (typeof HIS.get !== 'function') { self.drawTemplates = []; self.drawTplFailed = true; self.drawTplLoading = false; return; }
        HIS.get(DRAW_TPL_URL + '/list').then(function (list) {
          const rows = Array.isArray(list) ? list : ((list && list.records) || []);
          self.drawTemplates = rows.map(function (x) { return { id: HIS.id(x.id), name: x.title || x.name || ('模板 ' + x.id), cat: DRAW_CATS[x.category] || x.category || '' }; });
        }).catch(function () { self.drawTemplates = []; self.drawTplFailed = true; }).then(function () { self.drawTplLoading = false; });
      },
      /* --- 片段引用: 浏览模式(远程检索选择), 服务不可用回退手动输入(fragOffline) --- */
      loadFragments: function () {
        const self = this;
        self.fragLoading = true;
        if (typeof HIS.get !== 'function') { self.fragList = []; self.fragMode = 'manual'; self.fragOffline = true; self.fragLoading = false; return; }
        HIS.get(FRAG_LIST_URL + '?keyword=' + encodeURIComponent((self.fragKeyword || '').trim()) + '&page=1&size=100')
          .then(function (d) {
            self.fragList = (d && d.records) || (Array.isArray(d) ? d : []) || [];
            self.fragMode = 'browse'; self.fragOffline = false;
          }).catch(function () { self.fragList = []; self.fragMode = 'manual'; self.fragOffline = true; })
          .then(function () { self.fragLoading = false; });
      },
      onFragPick: function (row) { this.fragSelected = row || null; },
      scopeLabel: function (lv) { return SCOPE_LABELS[Number(lv)] || '全院'; },
      confirmFragment: function () {
        const ch = this.wrapper.editor.chain().focus();
        if (this.fragMode === 'browse') {
          const s = this.fragSelected;
          if (!s) { if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.warning('请先在列表中选择片段'); } return; }
          ch.insertEmrFragment({ fragmentId: HIS.id(s.id), sourceTemplateId: '', version: s.version == null ? '1' : String(s.version), title: s.title || s.code || '' }).run();
        } else {
          ch.insertEmrFragment({
            fragmentId: this.g.fragmentId || ('F-' + Date.now()),
            sourceTemplateId: this.g.sourceTemplateId, version: this.g.version, title: this.g.title
          }).run();
        }
        this.dlgFragment = false;
        this.fragSelected = null;
        this.g = { fragmentId: '', sourceTemplateId: '', version: '1', title: '' };
      },
      confirmTable: function () {
        this.wrapper.editor.chain().focus().insertTable(this.t.rows, this.t.cols, this.t.header).run();
        this.dlgTable = false;
      }
    },
    template: [
      '<div class="emr-toolbar" role="toolbar">',
      '  <el-button-group>',
      '    <el-button size="small" :type="ts.bold ? \'primary\' : \'\'" title="加粗 Ctrl+B" @click="cmd(\'toggleBold\')"><b>B</b></el-button>',
      '    <el-button size="small" :type="ts.italic ? \'primary\' : \'\'" title="斜体 Ctrl+I" @click="cmd(\'toggleItalic\')"><i>I</i></el-button>',
      '    <el-button size="small" :type="ts.underline ? \'primary\' : \'\'" title="下划线 Ctrl+U" @click="cmd(\'toggleUnderline\')"><u>U</u></el-button>',
      '    <el-button size="small" :type="ts.strike ? \'primary\' : \'\'" title="删除线" @click="cmd(\'toggleStrike\')"><s>S</s></el-button>',
      '  </el-button-group>',
      '  <el-button-group>',
      '    <el-button v-for="lv in 3" :key="lv" size="small" :type="ts.heading === lv ? \'primary\' : \'\'" :title="\'标题 \' + lv" @click="cmd(\'toggleHeading\', { level: lv })">H{{ lv }}</el-button>',
      '  </el-button-group>',
      '  <el-button-group>',
      '    <el-button size="small" :type="ts.bulletList ? \'primary\' : \'\'" title="无序列表" @click="cmd(\'toggleBulletList\')">• 列表</el-button>',
      '    <el-button size="small" :type="ts.orderedList ? \'primary\' : \'\'" title="有序列表" @click="cmd(\'toggleOrderedList\')">1. 列表</el-button>',
      '  </el-button-group>',
      '  <el-button-group>',
      '    <el-button v-for="al in aligns" :key="al" size="small" :type="ts.textAlign === al ? \'primary\' : \'\'" :title="\'对齐: \' + al" @click="cmd(\'setTextAlign\', al)">{{ alignLabel[al] }}</el-button>',
      '  </el-button-group>',
      '  <el-color-picker size="small" :model-value="ts.color" title="文字颜色" @change="onColor"></el-color-picker>',
      '  <el-button size="small" :type="ts.printHidden ? \'warning\' : \'\'" title="标记选中内容打印时隐藏" @click="cmd(\'toggleEmrPrintControl\')">不打印</el-button>',
      '  <el-dropdown trigger="click" @command="onInsert">',
      '    <el-button size="small" class="emr-toolbar-insert">插入 ▾</el-button>',
      '    <template #dropdown>',
      '      <el-dropdown-menu>',
      '        <el-dropdown-item command="field">数据元</el-dropdown-item>',
      '        <el-dropdown-item command="section">章节</el-dropdown-item>',
      '        <el-dropdown-item command="macro">宏变量</el-dropdown-item>',
      '        <el-dropdown-item command="fragment">片段引用</el-dropdown-item>',
      '        <el-dropdown-item command="table" divided>表格</el-dropdown-item>',
      '        <el-dropdown-item command="drawing">医学图示</el-dropdown-item>',
      '        <el-dropdown-item command="pagebreak">分页符</el-dropdown-item>',
      '        <el-dropdown-item command="conditional">条件块</el-dropdown-item>',
      '      </el-dropdown-menu>',
      '    </template>',
      '  </el-dropdown>',
      '  <el-button-group v-if="!compact">',
      '    <el-button size="small" :disabled="!ts.canUndo" title="撤销 Ctrl+Z" @click="cmd(\'undo\')">↶</el-button>',
      '    <el-button size="small" :disabled="!ts.canRedo" title="重做 Ctrl+Y" @click="cmd(\'redo\')">↷</el-button>',
      '  </el-button-group>',
      '  <span class="emr-toolbar-gap"></span>',
      '  <el-button size="small" @click="onPrint">打印预览</el-button>',
      '  <el-button v-if="wrapper.options.nlgEnabled" size="small" :type="ts.nlg ? \'primary\' : \'\'" title="显示/隐藏自然语言预览" @click="onNlgToggle">自然语言预览</el-button>',
      '  <el-button v-if="wrapper.options.cdssEnabled" size="small" :type="ts.cdss ? \'warning\' : \'\'" plain title="临床提醒(CDSS)" @click="onCdss">临床提醒</el-button>',
      '  <el-button size="small" type="primary" @click="onSave">保存</el-button>',
      '  <el-dialog v-model="dlgField" title="插入数据元" width="540px" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="字段名"><el-input v-model="f.fieldName" placeholder="如: 主诉"></el-input></el-form-item>',
      '      <el-form-item label="字段标识"><el-input v-model="f.fieldKey" placeholder="如: chiefComplaint, 留空自动生成"></el-input></el-form-item>',
      '      <el-form-item label="值类型">',
      '        <el-select v-model="f.valueType" style="width:100%">',
      '          <el-option v-for="t2 in valueTypes" :key="t2.v" :label="t2.l" :value="t2.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item v-if="f.valueType === \'select\' || f.valueType === \'multiselect\' || f.valueType === \'dict\'" label="字典源">',
      '        <el-input v-model="f.dictSource" placeholder="diag / drug / charge, 留空用手动选项"></el-input>',
      '      </el-form-item>',
      '      <el-form-item v-if="!f.dictSource && (f.valueType === \'select\' || f.valueType === \'multiselect\' || f.valueType === \'checkbox\')" label="选项">',
      '        <el-input v-model="f.options" type="textarea" :rows="2" placeholder="逗号分隔, 如: 阳性,阴性"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="属性">',
      '        <el-checkbox v-model="f.required">必填</el-checkbox>',
      '        <el-checkbox v-model="f.readonly">只读</el-checkbox>',
      '        <el-checkbox v-model="f.noCopy">禁止复制值</el-checkbox>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgField = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmField">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dlgSection" title="插入章节" width="440px" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="章节标题"><el-input v-model="s.title" placeholder="如: 入院记录"></el-input></el-form-item>',
      '      <el-form-item label="编辑模式">',
      '        <el-select v-model="s.editMode" style="width:100%">',
      '          <el-option label="混合（表单 + 自由文本）" value="mixed"></el-option>',
      '          <el-option label="表单（仅数据元）" value="form"></el-option>',
      '          <el-option label="自由（纯文本）" value="free"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgSection = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmSection">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dlgMacro" title="插入宏变量" width="440px" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="宏">',
      '        <el-select v-model="m.macroCode" filterable allow-create default-first-option style="width:100%">',
      '          <el-option v-for="p in macroPresets" :key="p.code" :label="p.label + \'（\' + p.code + \'）\'" :value="p.code"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="数据来源"><el-input v-model="m.dataSource" placeholder="如 patient / visit / system"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgMacro = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmMacro">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dlgConditional" title="插入条件块" width="480px" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="数据元"><el-select v-model="c.fieldKey" filterable style="width:100%" placeholder="选择本文档中的数据元"><el-option v-for="fd in docFields" :key="fd.fieldKey" :label="fd.fieldName + \'（\' + fd.fieldKey + \'）\'" :value="fd.fieldKey"></el-option></el-select></el-form-item>',
      '      <el-form-item v-if="!docFields.length"><span class="emr-ins-note">当前文档暂无数据元，请先插入数据元再配置条件</span></el-form-item>',
      '      <el-form-item label="运算符"><el-select v-model="c.operator" style="width:100%"><el-option v-for="op in operators" :key="op.v" :label="op.l" :value="op.v"></el-option></el-select></el-form-item>',
      '      <el-form-item v-if="c.operator !== \'empty\' && c.operator !== \'notEmpty\'" label="比较值"><el-input v-model="c.value" placeholder="与数据元值比较的内容"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgConditional = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmConditional">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dlgDrawing" title="插入医学图示" width="480px" append-to-body>',
      '    <el-form label-width="92px" size="small">',
      '      <el-form-item label="标题"><el-input v-model="d.title" placeholder="图示标题, 如: 体表标记"></el-input></el-form-item>',
      '      <el-form-item label="底图模板">',
      '        <el-select v-model="d.templateId" clearable filterable :loading="drawTplLoading" style="width:100%" placeholder="选择底图模板(可留空)">',
      '          <el-option v-for="tp in drawTemplates" :key="tp.id" :label="tp.name" :value="tp.id"><span>{{ tp.name }}</span><span v-if="tp.cat" class="emr-draw-cat">{{ tp.cat }}</span></el-option>',
      '        </el-select>',
      '        <div class="emr-ins-note">{{ drawTplFailed ? \'暂无底图模板\' : \'选择底图后插入, 可点击图示继续标注\' }}</div>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgDrawing = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmDrawing">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dlgFragment" title="插入片段引用" width="580px" append-to-body>',
      '    <template v-if="fragMode === \'browse\'">',
      '      <div class="emr-frag-search">',
      '        <el-input v-model="fragKeyword" size="small" clearable placeholder="按名称/编码检索" style="width:220px" @keyup.enter="loadFragments" @clear="loadFragments"></el-input>',
      '        <el-button size="small" type="primary" plain @click="loadFragments">搜索</el-button>',
      '        <el-button size="small" text type="primary" @click="fragMode = \'manual\'">手动输入</el-button>',
      '      </div>',
      '      <el-table :data="fragList" v-loading="fragLoading" size="small" height="280" highlight-current-row @current-change="onFragPick">',
      '        <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="code" label="编码" min-width="110" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="作用域" width="76"><template #default="s">{{ scopeLabel(s.row.scopeLevel) }}</template></el-table-column>',
      '        <el-table-column label="版本" width="68"><template #default="s">{{ s.row.version == null ? \'-\' : \'v\' + s.row.version }}</template></el-table-column>',
      '      </el-table>',
      '      <div v-if="!fragLoading && !fragList.length" class="emr-ins-note">未找到可用片段，可点击「手动输入」直接填写引用信息</div>',
      '    </template>',
      '    <el-form v-else label-width="92px" size="small">',
      '      <div class="emr-ins-note">{{ fragOffline ? \'服务不可用，手动输入\' : \'手动输入片段引用信息\' }}</div>',
      '      <el-form-item label="片段 ID"><el-input v-model="g.fragmentId" placeholder="留空自动生成"></el-input></el-form-item>',
      '      <el-form-item label="来源模板"><el-input v-model="g.sourceTemplateId" placeholder="来源模板 ID"></el-input></el-form-item>',
      '      <el-form-item label="版本"><el-input v-model="g.version"></el-input></el-form-item>',
      '      <el-form-item label="标题"><el-input v-model="g.title" placeholder="片段显示标题"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgFragment = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmFragment">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="dlgTable" title="插入表格" width="400px" append-to-body>',
      '    <el-form label-width="72px" size="small">',
      '      <el-form-item label="行数"><el-input-number v-model="t.rows" :min="1" :max="20"></el-input-number></el-form-item>',
      '      <el-form-item label="列数"><el-input-number v-model="t.cols" :min="1" :max="10"></el-input-number></el-form-item>',
      '      <el-form-item label="表头"><el-checkbox v-model="t.header">首行作为表头</el-checkbox></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgTable = false">取消</el-button>',
      '      <el-button size="small" type="primary" @click="confirmTable">插入</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    computed: {
      aligns: function () { return ALIGNS; },
      alignLabel: function () { return { left: '左', center: '中', right: '右', justify: '均' }; }
    }
  };
  EmrToolbar.mount = function (el, wrapper) {
    const app = Vue.createApp(EmrToolbar, { wrapper: wrapper });
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= 编辑器包装(wrapper) ================= */
  function createWrapper(editor, opts, hostEl) {
    let destroyed = false;
    let mode = opts.mode || 'edit';
    let listeners = {};
    let lastFields = null;
    let toolbar = null;

    function emit(evt, payload) {
      (listeners[evt] || []).slice().forEach(function (cb) { try { cb(payload); } catch (e) { /* 宿主回调异常不扩散 */ } });
    }
    function on(evt, cb) { (listeners[evt] = listeners[evt] || []).push(cb); return function () { off(evt, cb); }; }
    function off(evt, cb) { listeners[evt] = (listeners[evt] || []).filter(function (x) { return x !== cb; }); }

    /* --- 字段提取: { fieldKey: value } --- */
    function extractFields() {
      const map = {};
      editor.state.doc.descendants(function (n) {
        if (n.type.name === 'emrField' && n.attrs.fieldKey) { map[n.attrs.fieldKey] = n.attrs.value; }
      });
      return map;
    }
    /* --- 字段提取(明细, 含 attrs 与文档位置) --- */
    function extractFieldsDetail() {
      const list = [];
      editor.state.doc.descendants(function (n, pos) {
        if (n.type.name === 'emrField') { list.push(Object.assign({ pos: pos }, n.attrs)); }
      });
      return list;
    }

    const wrapper = {
      editor: editor,
      options: opts,
      mode: mode,
      /* --- 工具栏状态快照(激活态/能力) --- */
      getToolbarState: function () {
        const empty = {};
        if (destroyed) { return empty; }
        try {
          let heading = 0;
          for (let i = 1; i <= 6; i++) { if (editor.isActive('heading', { level: i })) { heading = i; break; } }
          const align = ALIGNS.filter(function (al) { return al !== 'left' && editor.isActive({ textAlign: al }); })[0];
          return {
            bold: editor.isActive('bold'), italic: editor.isActive('italic'),
            underline: editor.isActive('underline'), strike: editor.isActive('strike'),
            heading: heading, bulletList: editor.isActive('bulletList'),
            orderedList: editor.isActive('orderedList'), blockquote: editor.isActive('blockquote'),
            textAlign: align || 'left',
            color: (editor.getAttributes('textStyle') || {}).color || '',
            printHidden: editor.isActive('emrPrintControl'),
            inTable: editor.isActive('table'),
            canUndo: editor.can().undo(), canRedo: editor.can().redo(),
            nlg: !!wrapper.nlgShown, cdss: !!wrapper.cdssShown
          };
        } catch (e) { return empty; }
      },
      toJSON: function () { return editor.getJSON(); },
      getHTML: function () { return editor.getHTML(); },
      fromJSON: function (json) { editor.commands.setContent(json || DEFAULT_DOC, true); return wrapper; },
      extractFields: extractFields,
      extractFieldsDetail: extractFieldsDetail,
      extractDocumentFields: function () { return extractDocumentFields(editor.state.doc); },
      /* resolveMacros(ctx) — 客户端宏预览: 解析并回写文档 */
      resolveMacros: function (ctx) { return EmrMacroResolver.resolveAll(editor, ctx); },
      print: function (printOpts) { return EmrPrintPreview.print(editor, printOpts); },
      focus: function () { editor.commands.focus(); return wrapper; },
      /* setMode('edit'|'design'|'preview') — design 强化结构描边, preview 只读 */
      setMode: function (m) {
        if (['edit', 'design', 'preview'].indexOf(m) < 0) { m = 'edit'; }
        mode = m; wrapper.mode = m;
        editor.setEditable(m !== 'preview' && opts.readOnly !== true);
        applyMode();
        emit('mode', m);
        return wrapper;
      },
      /* mountTo(el) — 后挂载(创建时未传 container 的场景) */
      mountTo: function (el) {
        const target = resolveContainer(el);
        if (!target) { throw new Error('EmrEditor.mountTo: 目标容器不存在'); }
        hostEl = target;
        target.classList.add('emr-doc');
        target.appendChild(editor.view.dom);
        applyMode();
        return wrapper;
      },
      on: on, off: off, emit: emit,
      destroy: function () {
        if (destroyed) { return; }
        destroyed = true;
        emit('destroy', wrapper);
        listeners = {};
        if (toolbar) { try { toolbar.app.unmount(); } catch (e) { /* noop */ } toolbar = null; }
        try { editor.destroy(); } catch (e) { /* noop */ }
      }
    };
    wrapper.setToolbar = function (t) { toolbar = t; };

    function applyMode() {
      if (!hostEl) { return; }
      hostEl.classList.remove('emr-doc--edit', 'emr-doc--design', 'emr-doc--preview');
      hostEl.classList.add('emr-doc--' + mode);
    }

    /* --- 字段自动变更检测(onUpdate → onFieldChange) --- */
    lastFields = extractFields();
    editor.on('update', function () {
      const fields = extractFields();
      const keys = {};
      Object.keys(fields).forEach(function (k) { keys[k] = 1; });
      Object.keys(lastFields).forEach(function (k) { keys[k] = 1; });
      const changes = Object.keys(keys).filter(function (k) {
        return !(EX().valuesEqual && EX().valuesEqual(fields[k], lastFields[k]));
      }).map(function (k) { return { fieldKey: k, value: fields[k] == null ? null : fields[k] }; });
      lastFields = fields;
      if (changes.length && typeof opts.onFieldChange === 'function') { opts.onFieldChange(changes, fields); }
      emit('update', wrapper);
    });
    editor.on('transaction', function () { emit('toolbar', wrapper.getToolbarState()); });
    on('save', function () { if (typeof opts.onSave === 'function') { opts.onSave(wrapper); } });

    applyMode();
    return wrapper;
  }

  /* ================= createEditor(引擎入口) ================= */
  async function createEditor(options) {
    const opts = Object.assign({
      mode: 'edit',                     /* edit | design | preview */
      readOnly: false,
      container: null,                  /* selector | HTMLElement, 可省略后 mountTo */
      toolbarContainer: null,           /* 提供则自动挂载工具栏 */
      document: null,                   /* Tiptap JSON 或 HTML 字符串 */
      placeholder: '输入病历内容，或使用工具栏插入结构化元素…',
      onSave: null,                     /* (wrapper) => {} */
      onFieldChange: null,              /* (changes, fields) => {} */
      nlgEnabled: false,                /* NLG 自然语言预览: 底部面板 + 字段变更防抖 2s 生成 */
      cdssEnabled: false,               /* CDSS 临床提醒: 保存前评估, block 级拦截, 右侧提醒面板 */
      recordType: '',                   /* 病历类型(NLG 映射模板 scope 0/1/2; CDSS 规则过滤) */
      deptCode: ''                      /* 科室编码(CDSS 规则过滤) */
    }, options || {});
    const T = await TiptapLoader.ensure();
    if (!HIS.EmrExtensions) { throw new Error('HIS.EmrExtensions 缺失: 请先加载 emr-extensions.js'); }

    const ext = HIS.EmrExtensions.buildAll(T);
    const extensions = [
      /* StarterKit v2.27 内含 history, 显式关闭改用独立 History(扩展清单要求) */
      T.StarterKit.configure({ history: false }),
      T.History,
      T.Underline,
      T.TextAlign.configure({ types: ['heading', 'paragraph'] }),
      T.Color, T.TextStyle,
      T.Image.configure({ inline: false, allowBase64: true }),
      T.Placeholder.configure({ placeholder: opts.placeholder }),
      ext.EmrTable, T.TableRow, T.TableCell, T.TableHeader,
      ext.EmrField, ext.EmrSection, ext.EmrMacro, ext.EmrPrintControl,
      ext.EmrFragment, ext.EmrConditionalBlock, ext.EmrPageBreak, ext.EmrDrawing
    ];

    let hostEl = resolveContainer(opts.container);
    if (hostEl) { hostEl.classList.add('emr-doc'); }
    const editorOpts = {
      extensions: extensions,
      content: opts.document || opts.content || DEFAULT_DOC,
      editable: !opts.readOnly && opts.mode !== 'preview',
      onUpdate: function () { /* 实际转发在 wrapper 内经 editor.on 注册, 此处保留钩子位 */ }
    };
    if (hostEl) { editorOpts.element = hostEl; }
    const editor = new T.Editor(editorOpts);
    const wrapper = createWrapper(editor, opts, hostEl);

    /* --- NLG / CDSS 装配: 包装宿主回调与 destroy, 不改动 wrapper 既有行为 --- */
    let nlg = null, cdss = null;
    if (opts.nlgEnabled) {
      const hostFieldChange = opts.onFieldChange;
      opts.onFieldChange = function (changes, fields) {
        if (typeof hostFieldChange === 'function') { try { hostFieldChange(changes, fields); } catch (e) { /* 宿主回调异常不扩散 */ } }
        if (nlg) { nlg.schedule(); }
      };
    }
    if (opts.cdssEnabled) {
      const hostOnSave = opts.onSave;
      opts.onSave = function (w) {
        w.evaluateCdss().then(function (r) {
          if (r && r.canProceed) { if (typeof hostOnSave === 'function') { hostOnSave(w); } return; }
          if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.error('存在拦截级临床提醒，已阻止保存'); }
        });
      };
    }
    if (opts.nlgEnabled) {
      nlg = createNlgController(opts);
      nlg.attach(wrapper);
      wrapper.nlgShown = nlg.isVisible();
      wrapper.refreshNlg = function () { nlg.refresh(); return wrapper; };
      wrapper.setNlgVisible = function (v) { nlg.setVisible(v); wrapper.nlgShown = nlg.isVisible(); wrapper.emit('toolbar', wrapper.getToolbarState()); return wrapper; };
    }
    if (opts.cdssEnabled) {
      cdss = createCdssController(opts);
      cdss.attach(wrapper);
      wrapper.cdssShown = false;
      wrapper.evaluateCdss = function () { return cdss.evaluate(); };
      wrapper.setCdssVisible = function (v) { cdss.setVisible(v); return wrapper; };
    }
    if (nlg || cdss) {
      const hostDestroy = wrapper.destroy;
      wrapper.destroy = function () {
        if (nlg) { try { nlg.destroy(); } catch (e) { /* noop */ } }
        if (cdss) { try { cdss.destroy(); } catch (e) { /* noop */ } }
        hostDestroy();
      };
    }

    if (opts.toolbarContainer) {
      const tEl = resolveContainer(opts.toolbarContainer);
      if (tEl) { wrapper.setToolbar(EmrToolbar.mount(tEl, wrapper)); }
    }
    return wrapper;
  }

  /* ================= 样式(工具栏/编辑器宿主/diff) ================= */
  (function injectCss() {
    if (document.getElementById('emr-editor-css')) { return; }
    const st = document.createElement('style');
    st.id = 'emr-editor-css';
    st.textContent = [
      /* ---- 工具栏 ---- */
      '.emr-toolbar { display:flex; flex-wrap:wrap; align-items:center; gap:8px; padding:6px 10px; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px 6px 0 0; }',
      '.emr-toolbar .el-button + .el-button { margin-left:0; } .emr-toolbar .el-button-group + .el-button-group { margin-left:0; }',
      '.emr-toolbar .el-color-picker, .emr-toolbar .el-dropdown { margin:0 2px; }',
      '.emr-toolbar-insert { margin-left:2px !important; } .emr-toolbar-gap { flex:1; }',
      /* ---- 编辑器宿主 ---- */
      '.emr-editor-host { border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; background:var(--yb-surface,#fff); }',
      '.emr-editor-host:focus-within { border-color:var(--yb-brand,#1a5c9e); box-shadow:0 0 0 2px rgba(26,92,158,.08); }',
      '.emr-doc .ProseMirror { min-height:320px; padding:12px 16px; outline:none; font-size:14px; line-height:1.8; color:var(--yb-ink-1,#1c2430); }',
      '.emr-doc .ProseMirror > * + * { margin-top:.6em; }',
      '.emr-doc .ProseMirror p.is-editor-empty:first-child::before { content:attr(data-placeholder); color:var(--yb-ink-4,#8994a5); float:left; height:0; pointer-events:none; }',
      '.emr-doc--preview .ProseMirror { min-height:120px; background:var(--yb-surface-2,#f7f9fc); }',
      /* ---- 表格(官方 Table 基础样式) ---- */
      '.emr-doc .ProseMirror table { border-collapse:collapse; width:100%; margin:8px 0; overflow:hidden; table-layout:fixed; }',
      '.emr-doc .ProseMirror td, .emr-doc .ProseMirror th { border:1px solid var(--yb-border-strong,#ccd4de); padding:4px 8px; vertical-align:top; position:relative; min-width:64px; }',
      '.emr-doc .ProseMirror th { background:var(--yb-surface-2,#f7f9fc); font-weight:600; text-align:left; }',
      '.emr-doc .ProseMirror .selectedCell::after { content:""; position:absolute; inset:0; background:rgba(26,92,158,.12); pointer-events:none; }',
      /* ---- 其他块级 ---- */
      '.emr-doc .ProseMirror blockquote { border-left:3px solid var(--yb-border-strong,#ccd4de); padding-left:12px; color:var(--yb-ink-3,#5a6a7e); margin:8px 0; }',
      '.emr-doc .ProseMirror pre { background:var(--yb-ink-1,#1c2430); color:#e8ecf2; border-radius:6px; padding:10px 12px; font-family:Consolas,monospace; font-size:13px; }',
      '.emr-doc .ProseMirror img { max-width:100%; }',
      /* ---- 字段渲染组件(宿主侧) ---- */
      '.emr-fr { display:inline-flex; align-items:center; gap:4px; } .emr-f-unit { color:var(--yb-ink-3,#5a6a7e); font-size:12px; }',
      /* ---- 版本对比 ---- */
      '.emr-diff { border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:auto; max-height:70vh; background:var(--yb-surface,#fff); }',
      '.emr-diff-bar { padding:6px 12px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); background:var(--yb-surface-2,#f7f9fc); border-bottom:1px solid var(--yb-border-light,#ebeff4); }',
      '.emr-diff-del-text { color:var(--yb-danger,#c74f4f); } .emr-diff-add-text { color:var(--yb-success,#3c862d); }',
      '.emr-diff-warn { padding:8px 12px; color:var(--yb-warning,#a26b1b); background:var(--yb-warning-bg,#fbf3e6); border-bottom:1px solid var(--yb-warning-border,#f0ddc0); font-size:12px; }',
      '.emr-diff-table { width:100%; border-collapse:collapse; font-family:Consolas,monospace; font-size:12px; table-layout:fixed; }',
      '.emr-diff-table th { position:sticky; top:0; z-index:1; background:var(--yb-surface-2,#f7f9fc); border:1px solid var(--yb-border,#dfe4eb); padding:4px 10px; text-align:left; }',
      '.emr-diff-table td { border:1px solid var(--yb-border-light,#ebeff4); padding:2px 10px; white-space:pre-wrap; word-break:break-all; vertical-align:top; }',
      '.emr-diff-del { background:var(--yb-danger-bg,#fdf0f0); color:var(--yb-danger,#c74f4f); text-decoration:line-through; }',
      '.emr-diff-add { background:var(--yb-success-bg,#eef6ec); color:var(--yb-success,#3c862d); }',
      '.emr-diff-empty { background:var(--yb-canvas,#f2f4f8); }',
      /* ---- 插入对话框辅助(对话框 append-to-body, 须全局作用域) ---- */
      '.emr-ins-note { font-size:12px; color:var(--yb-ink-4,#8994a5); line-height:1.6; }',
      '.emr-frag-search { display:flex; align-items:center; gap:8px; margin-bottom:8px; }',
      '.emr-draw-cat { float:right; font-size:12px; color:var(--yb-ink-4,#8994a5); margin-left:16px; }',
      /* ---- NLG 自然语言预览面板 ---- */
      '.emr-nlg-panel { border-top:1px dashed var(--yb-border,#dfe4eb); background:var(--yb-surface,#fff); max-height:200px; display:flex; flex-direction:column; }',
      '.emr-nlg-head { display:flex; align-items:center; gap:8px; padding:4px 12px; background:var(--yb-surface-2,#f7f9fc); cursor:pointer; user-select:none; flex:none; }',
      '.emr-nlg-arrow { color:var(--yb-ink-3,#5a6a7e); font-size:12px; }',
      '.emr-nlg-title { font-weight:600; font-size:13px; color:var(--yb-ink-1,#1c2430); }',
      '.emr-nlg-gap { flex:1; }',
      '.emr-nlg-loading { font-size:12px; color:var(--yb-brand,#1a5c9e); } .emr-nlg-failed { font-size:12px; color:var(--yb-danger,#c74f4f); }',
      '.emr-nlg-spin { display:inline-block; width:10px; height:10px; margin-right:4px; border:2px solid var(--yb-brand,#1a5c9e); border-top-color:transparent; border-radius:50%; animation:emr-nlg-rotate .8s linear infinite; vertical-align:-1px; }',
      '@keyframes emr-nlg-rotate { to { transform:rotate(360deg); } }',
      '.emr-nlg-body { overflow-y:auto; padding:6px 12px; }',
      '.emr-nlg-section { padding:6px 0; } .emr-nlg-section + .emr-nlg-section { border-top:1px dashed var(--yb-border-light,#ebeff4); }',
      '.emr-nlg-sec-name { font-size:12px; font-weight:600; color:var(--yb-ink-3,#5a6a7e); margin-bottom:2px; }',
      '.emr-nlg-text { font-size:13px; line-height:1.7; color:var(--yb-ink-1,#1c2430); white-space:pre-wrap; word-break:break-all; }',
      '.emr-nlg-empty { font-size:12px; color:var(--yb-ink-4,#8994a5); padding:6px 0; }',
      /* ---- CDSS 临床提醒面板(右侧滑入) ---- */
      '.emr-cdss-panel { position:fixed; top:56px; right:0; width:340px; max-height:calc(100vh - 96px); display:flex; flex-direction:column; background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-right:none; border-radius:8px 0 0 8px; box-shadow:-6px 0 24px rgba(28,36,48,.14); z-index:1500; transform:translateX(110%); transition:transform .25s ease; }',
      '.emr-cdss-panel--open { transform:translateX(0); }',
      '.emr-cdss-head { display:flex; align-items:center; justify-content:space-between; padding:8px 12px; border-bottom:1px solid var(--yb-border-light,#ebeff4); background:var(--yb-surface-2,#f7f9fc); flex:none; }',
      '.emr-cdss-title { font-weight:600; font-size:13px; color:var(--yb-ink-1,#1c2430); }',
      '.emr-cdss-summary { display:flex; gap:8px; padding:8px 12px; border-bottom:1px solid var(--yb-border-light,#ebeff4); flex:none; }',
      '.emr-cdss-chip { padding:2px 8px; border-radius:10px; font-size:12px; white-space:nowrap; }',
      '.emr-cdss-chip--block { background:var(--yb-danger-bg,#fdf0f0); color:var(--yb-danger,#c74f4f); }',
      '.emr-cdss-chip--warning { background:var(--yb-warning-bg,#fbf3e6); color:var(--yb-warning,#a26b1b); }',
      '.emr-cdss-chip--info { background:#eaf3fb; color:#2a6aa9; }',
      '.emr-cdss-body { overflow-y:auto; padding:8px 12px; flex:1; min-height:48px; }',
      '.emr-cdss-note { font-size:12px; color:var(--yb-ink-4,#8994a5); padding:8px 0; }',
      '.emr-cdss-alert { border-radius:6px; padding:8px 10px; margin-bottom:8px; border:1px solid transparent; }',
      '.emr-cdss-alert--block { background:var(--yb-danger-bg,#fdf0f0); border-color:#f2c8c8; }',
      '.emr-cdss-alert--warning { background:var(--yb-warning-bg,#fbf3e6); border-color:#f0ddc0; }',
      '.emr-cdss-alert--info { background:#eaf3fb; border-color:#cfe2f3; }',
      '.emr-cdss-alert-name { font-weight:700; font-size:13px; margin-bottom:2px; }',
      '.emr-cdss-alert--block .emr-cdss-alert-name { color:var(--yb-danger,#c74f4f); }',
      '.emr-cdss-alert--warning .emr-cdss-alert-name { color:var(--yb-warning,#a26b1b); }',
      '.emr-cdss-alert--info .emr-cdss-alert-name { color:#2a6aa9; }',
      '.emr-cdss-level-tag { float:right; font-size:11px; font-weight:400; opacity:.85; }',
      '.emr-cdss-alert-msg { font-size:12px; line-height:1.6; color:var(--yb-ink-2,#3a4757); word-break:break-all; }',
      '.emr-cdss-alert-src { display:inline-block; margin-top:4px; font-size:12px; color:var(--yb-brand,#1a5c9e); text-decoration:none; }',
      'a.emr-cdss-alert-src:hover { text-decoration:underline; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 对外注册 ================= */
  HIS.EmrEditor = {
    createEditor: createEditor,
    DEFAULT_DOC: DEFAULT_DOC,
    EmrToolbar: EmrToolbar,
    EmrFieldRenderer: EmrFieldRenderer,
    EmrSectionRenderer: EmrSectionRenderer,
    EmrMacroResolver: EmrMacroResolver,
    EmrPrintPreview: EmrPrintPreview,
    EmrDiffViewer: EmrDiffViewer,
    EmrNlgPanel: EmrNlgPanel,
    EmrCdssPanel: EmrCdssPanel
  };
})();
