/* emr-drawing-panel.js — 病历医学图示画板(全局单例, HIS.EmrDrawingPanel)
 * ------------------------------------------------------------------
 * 用途: 病历编辑器"医学图示"节点(emrDrawing, attrs: svgData/annotations/title)的配套画板 ——
 *       弹窗内以原生 Canvas 2D 绘制标注(画笔/直线/矩形/圆形/文字/橡皮/选择), 保存时导出
 *       正文 SVG 串(可直接插入文档并被打印预览渲染) + 可再编辑的 annotations JSON。
 * 用法(全局单例, 反复 open 复用同一实例):
 *   HIS.EmrDrawingPanel.open({
 *     svgData: '', annotations: '', title: '示意图', templateSvg: '',
 *     onSave(svgData, annotationsJson) { ... }
 *   });
 * 数据模型(annotations JSON; 坐标一律 800x600 逻辑空间, 与导出 SVG viewBox 同构):
 *   { strokes: [ {type:'pen',points:[[x,y],...],color,width}
 *               |{type:'line'|'rect',from:[x,y],to:[x,y],color,width}
 *               |{type:'ellipse',center:[x,y],rx,ry,color,width}
 *               |{type:'text',pos:[x,y],text,color,fontSize} ],
 *     templateId: null, templateSvg: '' }
 * 后端契约(可选; 未就绪时优雅降级为空白底图, 不打断绘制):
 *   GET /api/his/emr/drawing-template/list   → [{id,name},...] 模板列表
 *   GET /api/his/emr/drawing-template/{id}   → 模板 SVG(裸串或 {svgData})
 * 交互要点: 快照栈撤销/重做(Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z, 上限60);
 *   选择工具点击选中(虚线框高亮)可拖动重定位, Delete 删除; 文字工具在点击处浮层输入(Enter 确认);
 *   鼠标+触摸双通道; HiDPI 按 devicePixelRatio 扩内部像素; CSS 端等比缩放自适应弹窗宽度(窗口 resize 重排)。
 * 注册: HIS.EmrDrawingPanel(须在 app.js 之前加载; 各宿主视图运行时调用 open)。
 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});

  /* ================= 常量 ================= */
  const LOGIC_W = 800, LOGIC_H = 600;          /* 逻辑坐标空间(与导出 SVG viewBox 一致) */
  const MAX_HIST = 60;                          /* 撤销快照栈上限 */
  const SAMPLE_D2 = 4;                          /* 画笔采样阈值(2px², 防抖点) */
  const DEF_COLOR = '#d93025';                  /* 默认标注色(医学红) */
  const TOOLS = [
    { t: 'pen', l: '画笔' }, { t: 'line', l: '直线' }, { t: 'rect', l: '矩形' },
    { t: 'ellipse', l: '圆形' }, { t: 'text', l: '文字' }, { t: 'eraser', l: '橡皮' }, { t: 'select', l: '选择' }
  ];
  const CURSORS = { text: 'text', select: 'move' };   /* 其余工具统一 crosshair */
  const COLOR_PRESETS = ['#d93025', '#1c2430', '#1a5c9e', '#2e7d32', '#e6a23c', '#8e24aa', '#ffffff'];
  const HINTS = {
    pen: '画笔: 按住鼠标拖动绘制笔迹',
    line: '直线: 按住拖动, 松开完成',
    rect: '矩形: 按住拖动, 松开完成',
    ellipse: '圆形: 按住拖动, 松开完成',
    text: '文字: 点击画布输入标注文字, Enter 确认',
    eraser: '橡皮: 点击要删除的标注',
    select: '选择: 点击选中标注后拖动移动, Delete 删除'
  };

  /* ================= 工具函数 ================= */
  function num(v, d) { const n = Number(v); return isFinite(n) ? n : d; }
  function r1(n) { return Math.round(n * 10) / 10; }
  function r4(n) { return Math.round(n * 10000) / 10000; }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function safeColor(c) { return /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(String(c || '')) ? String(c).toLowerCase() : ''; }
  function isSvgString(s) { return typeof s === 'string' && s.replace(/^\s+/, '').indexOf('<svg') === 0; }
  function ptOf(p) { return [num(p && p[0], 0), num(p && p[1], 0)]; }
  function cloneStrokes(list) { return JSON.parse(JSON.stringify(list)); }

  /* 单条标注规范化(载入/提交统一入口): 非法类型或缺失关键几何返回 null; 颜色/线宽/字号收敛合法域 */
  function sanitizeStroke(s) {
    if (!s || typeof s !== 'object') { return null; }
    const color = safeColor(s.color) || DEF_COLOR;
    const w = Math.min(24, Math.max(1, num(s.width, 2)));
    if (s.type === 'pen') {
      const pts = [];
      (Array.isArray(s.points) ? s.points : []).forEach(function (p) { pts.push(ptOf(p)); });
      if (!pts.length) { return null; }
      return { type: 'pen', points: pts, color: color, width: w };
    }
    if (s.type === 'line' || s.type === 'rect') {
      return { type: s.type, from: ptOf(s.from), to: ptOf(s.to), color: color, width: w };
    }
    if (s.type === 'ellipse') {
      return { type: 'ellipse', center: ptOf(s.center), rx: Math.abs(num(s.rx, 0)), ry: Math.abs(num(s.ry, 0)), color: color, width: w };
    }
    if (s.type === 'text') {
      const t = String(s.text == null ? '' : s.text).slice(0, 200);
      if (!t) { return null; }
      return { type: 'text', pos: ptOf(s.pos), text: t, color: color, fontSize: Math.min(96, Math.max(8, num(s.fontSize, 14))) };
    }
    return null;
  }

  /* annotations 解析(串或对象; 非法返回 null): {strokes, templateId, templateSvg} */
  function parseAnnotations(raw) {
    if (!raw) { return null; }
    let obj = raw;
    if (typeof raw === 'string') {
      try { obj = JSON.parse(raw); } catch (e) { return null; }
    }
    if (!obj || typeof obj !== 'object') { return null; }
    const strokes = [];
    (Array.isArray(obj.strokes) ? obj.strokes : []).forEach(function (s) {
      const st = sanitizeStroke(s);
      if (st) { strokes.push(st); }
    });
    return {
      strokes: strokes,
      templateId: obj.templateId != null && obj.templateId !== '' ? String(obj.templateId) : null,
      templateSvg: isSvgString(obj.templateSvg) ? obj.templateSvg : ''
    };
  }

  /* 文本近似宽(CJK 按 1em, 其余按 0.58em): 供包围盒/命中判定 */
  function textWidthOf(s) {
    const fs = s.fontSize || 14;
    const str = String(s.text || '');
    let w = 0;
    for (let i = 0; i < str.length; i++) { w += str.charCodeAt(i) > 255 ? fs : fs * 0.58; }
    return w;
  }
  function strokeBBox(s) {
    if (s.type === 'pen') {
      let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity;
      s.points.forEach(function (p) {
        x1 = Math.min(x1, p[0]); y1 = Math.min(y1, p[1]);
        x2 = Math.max(x2, p[0]); y2 = Math.max(y2, p[1]);
      });
      return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
    }
    if (s.type === 'line' || s.type === 'rect') {
      return {
        x: Math.min(s.from[0], s.to[0]), y: Math.min(s.from[1], s.to[1]),
        w: Math.abs(s.to[0] - s.from[0]), h: Math.abs(s.to[1] - s.from[1])
      };
    }
    if (s.type === 'ellipse') {
      return { x: s.center[0] - s.rx, y: s.center[1] - s.ry, w: s.rx * 2, h: s.ry * 2 };
    }
    return { x: s.pos[0], y: s.pos[1], w: textWidthOf(s), h: Math.round((s.fontSize || 14) * 1.25) };
  }

  /* 点到线段距离(命中/橡皮判定) */
  function distSeg(px, py, x1, y1, x2, y2) {
    const dx = x2 - x1, dy = y2 - y1;
    const len2 = dx * dx + dy * dy;
    let t = len2 ? ((px - x1) * dx + (py - y1) * dy) / len2 : 0;
    t = Math.max(0, Math.min(1, t));
    const qx = x1 + t * dx, qy = y1 + t * dy;
    return Math.hypot(px - qx, py - qy);
  }
  function hitStroke(s, x, y) {
    const tol = 5 + (s.width || 2) / 2;
    if (s.type === 'pen') {
      const pts = s.points;
      if (pts.length === 1) { return Math.hypot(x - pts[0][0], y - pts[0][1]) <= tol; }
      for (let i = 1; i < pts.length; i++) {
        if (distSeg(x, y, pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]) <= tol) { return true; }
      }
      return false;
    }
    if (s.type === 'line') { return distSeg(x, y, s.from[0], s.from[1], s.to[0], s.to[1]) <= tol; }
    if (s.type === 'rect') {
      const x1 = Math.min(s.from[0], s.to[0]), y1 = Math.min(s.from[1], s.to[1]);
      const x2 = Math.max(s.from[0], s.to[0]), y2 = Math.max(s.from[1], s.to[1]);
      return distSeg(x, y, x1, y1, x2, y1) <= tol || distSeg(x, y, x2, y1, x2, y2) <= tol ||
        distSeg(x, y, x2, y2, x1, y2) <= tol || distSeg(x, y, x1, y2, x1, y1) <= tol;
    }
    if (s.type === 'ellipse') {
      const rx = Math.max(s.rx, 0.5), ry = Math.max(s.ry, 0.5);
      const dx = (x - s.center[0]) / rx, dy = (y - s.center[1]) / ry;
      const k = Math.sqrt(dx * dx + dy * dy);
      return Math.abs(k - 1) * Math.min(rx, ry) <= tol + 3;   /* 径向偏差近似到边界距离 */
    }
    if (s.type === 'text') {
      const b = strokeBBox(s);
      const dx = Math.max(b.x - x, 0, x - (b.x + b.w));
      const dy = Math.max(b.y - y, 0, y - (b.y + b.h));
      return Math.hypot(dx, dy) <= tol;
    }
    return false;
  }

  /* 平移复制(选择工具拖动) */
  function offsetStroke(s, dx, dy) {
    const out = Object.assign({}, s);
    if (s.type === 'pen') { out.points = s.points.map(function (p) { return [p[0] + dx, p[1] + dy]; }); }
    else if (s.type === 'ellipse') { out.center = [s.center[0] + dx, s.center[1] + dy]; }
    else if (s.type === 'text') { out.pos = [s.pos[0] + dx, s.pos[1] + dy]; }
    else { out.from = [s.from[0] + dx, s.from[1] + dy]; out.to = [s.to[0] + dx, s.to[1] + dy]; }
    return out;
  }

  /* 画布渲染(工作在 800x600 逻辑空间, 由调用方设置 scale 变换) */
  function drawStroke(ctx, s) {
    ctx.strokeStyle = s.color;
    ctx.fillStyle = s.color;
    ctx.lineWidth = s.width;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    if (s.type === 'pen') {
      const pts = s.points;
      if (pts.length === 1) {
        ctx.beginPath();
        ctx.arc(pts[0][0], pts[0][1], Math.max(0.6, s.width / 2), 0, Math.PI * 2);
        ctx.fill();
        return;
      }
      ctx.beginPath();
      ctx.moveTo(pts[0][0], pts[0][1]);
      for (let i = 1; i < pts.length; i++) { ctx.lineTo(pts[i][0], pts[i][1]); }
      ctx.stroke();
      return;
    }
    if (s.type === 'line') { ctx.beginPath(); ctx.moveTo(s.from[0], s.from[1]); ctx.lineTo(s.to[0], s.to[1]); ctx.stroke(); return; }
    if (s.type === 'rect') {
      const b = strokeBBox(s);
      ctx.beginPath(); ctx.rect(b.x, b.y, b.w, b.h); ctx.stroke();
      return;
    }
    if (s.type === 'ellipse') {
      ctx.beginPath();
      ctx.ellipse(s.center[0], s.center[1], Math.max(s.rx, 0.1), Math.max(s.ry, 0.1), 0, 0, Math.PI * 2);
      ctx.stroke();
      return;
    }
    if (s.type === 'text') {
      ctx.font = (s.fontSize || 14) + 'px sans-serif';
      ctx.textBaseline = 'hanging';   /* 与导出 SVG dominant-baseline="hanging" 对齐 */
      ctx.fillText(s.text, s.pos[0], s.pos[1]);
    }
  }
  function drawSelBox(ctx, s) {
    const b = strokeBBox(s);
    ctx.save();
    ctx.setLineDash([4, 3]);
    ctx.strokeStyle = '#1a5c9e';
    ctx.lineWidth = 1;
    ctx.strokeRect(b.x - 4, b.y - 4, b.w + 8, b.h + 8);
    ctx.restore();
  }

  /* ================= SVG 导出 ================= */
  function strokeToSvg(s) {
    const c = esc(s.color), w = r1(s.width);
    if (s.type === 'pen') {
      if (s.points.length === 1) {
        return '<circle cx="' + r1(s.points[0][0]) + '" cy="' + r1(s.points[0][1]) +
          '" r="' + r1(Math.max(0.6, s.width / 2)) + '" fill="' + c + '"/>';
      }
      const d = s.points.map(function (p, i) { return (i ? 'L' : 'M') + r1(p[0]) + ' ' + r1(p[1]); }).join(' ');
      return '<path d="' + d + '" fill="none" stroke="' + c + '" stroke-width="' + w +
        '" stroke-linecap="round" stroke-linejoin="round"/>';
    }
    if (s.type === 'line') {
      return '<line x1="' + r1(s.from[0]) + '" y1="' + r1(s.from[1]) + '" x2="' + r1(s.to[0]) + '" y2="' + r1(s.to[1]) +
        '" stroke="' + c + '" stroke-width="' + w + '" stroke-linecap="round"/>';
    }
    if (s.type === 'rect') {
      return '<rect x="' + r1(Math.min(s.from[0], s.to[0])) + '" y="' + r1(Math.min(s.from[1], s.to[1])) +
        '" width="' + r1(Math.abs(s.to[0] - s.from[0])) + '" height="' + r1(Math.abs(s.to[1] - s.from[1])) +
        '" fill="none" stroke="' + c + '" stroke-width="' + w + '"/>';
    }
    if (s.type === 'ellipse') {
      return '<ellipse cx="' + r1(s.center[0]) + '" cy="' + r1(s.center[1]) + '" rx="' + r1(s.rx) + '" ry="' + r1(s.ry) +
        '" fill="none" stroke="' + c + '" stroke-width="' + w + '"/>';
    }
    if (s.type === 'text') {
      return '<text x="' + r1(s.pos[0]) + '" y="' + r1(s.pos[1]) + '" font-size="' + r1(s.fontSize) +
        '" font-family="sans-serif" dominant-baseline="hanging" fill="' + c + '">' + esc(s.text) + '</text>';
    }
    return '';
  }

  /* 提取模板 SVG 内层内容; 模板视图尺寸与 800x600 不一致时包 <g> 等比缩放平移对齐 */
  function innerSvgOf(tpl) {
    if (!isSvgString(tpl)) { return ''; }
    const head = tpl.match(/<svg\b([^>]*)>/i);
    const body = tpl.match(/<svg\b[^>]*>([\s\S]*)<\/svg>\s*$/i);
    if (!head || !body) { return ''; }
    const inner = body[1].replace(/<script[\s\S]*?<\/script>/gi, '');   /* 模板不携脚本 */
    const attrs = head[1] || '';
    const vb = attrs.match(/viewBox\s*=\s*["']\s*([\d.eE+-]+)[\s,]+([\d.eE+-]+)[\s,]+([\d.eE+-]+)[\s,]+([\d.eE+-]+)\s*["']/i);
    let vx = 0, vy = 0, vw = 0, vh = 0;
    if (vb) { vx = num(vb[1], 0); vy = num(vb[2], 0); vw = num(vb[3], 0); vh = num(vb[4], 0); }
    if (!(vw > 0) || !(vh > 0)) {
      const wm = attrs.match(/width\s*=\s*["']?([\d.]+)/i), hm = attrs.match(/height\s*=\s*["']?([\d.]+)/i);
      vx = 0; vy = 0;
      vw = wm ? num(wm[1], 0) : 0; vh = hm ? num(hm[1], 0) : 0;
    }
    if (vw > 0 && vh > 0 && (vx !== 0 || vy !== 0 || vw !== LOGIC_W || vh !== LOGIC_H)) {
      const sx = LOGIC_W / vw, sy = LOGIC_H / vh;
      return '<g transform="translate(' + r1(-vx * sx) + ' ' + r1(-vy * sy) + ') scale(' + r4(sx) + ' ' + r4(sy) + ')">' + inner + '</g>';
    }
    return inner;
  }

  /* 组合导出: 白底 → 模板内层(矢量嵌入) → 标注元素(与画布显示同坐标空间) */
  function buildSvgString(strokes, templateSvg) {
    const out = [
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + LOGIC_W + ' ' + LOGIC_H +
      '" width="' + LOGIC_W + '" height="' + LOGIC_H + '">',
      '<rect x="0" y="0" width="' + LOGIC_W + '" height="' + LOGIC_H + '" fill="#ffffff"/>'
    ];
    const inner = innerSvgOf(templateSvg);
    if (inner) { out.push('<g>' + inner + '</g>'); }
    strokes.forEach(function (s) { out.push(strokeToSvg(s)); });
    out.push('</svg>');
    return out.join('');
  }

  /* ================= 私有样式(一次性注入 head, 无构建组件不依赖外部样式文件) ================= */
  (function ensureStyles() {
    if (document.getElementById('edp-style')) { return; }
    const st = document.createElement('style');
    st.id = 'edp-style';
    st.textContent = [
      /* 弹窗: 90% 视宽 + 1000px 上限(EP class 落点随版本变化, 两种选择器都覆盖) */
      '.edp-dialog .el-dialog, .edp-dialog.el-dialog { max-width:1000px; }',
      '.edp-dialog .el-dialog__body, .edp-dialog.el-dialog .el-dialog__body { padding:10px 16px 14px; }',
      '.edp-head { display:flex; align-items:baseline; gap:10px; }',
      '.edp-title { font-size:15px; font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      '.edp-sub { font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      /* 工具栏 */
      '.edp-bar { display:flex; flex-wrap:wrap; align-items:center; gap:8px; padding:8px 10px; background:var(--yb-surface-2,#f7f9fc); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; }',
      '.edp-bar .el-button + .el-button { margin-left:0; }',
      '.edp-lb { font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.edp-sep { width:1px; height:18px; background:var(--yb-border,#dfe4eb); }',
      '.edp-grow { flex:1; }',
      /* 画板 */
      '.edp-stage { position:relative; margin:12px auto 0; width:100%; }',
      '.edp-canvas { display:block; margin:0 auto; background:#fff; border:1px solid var(--yb-border-strong,#ccd4de); border-radius:4px; touch-action:none; }',
      '.edp-text-edit { position:absolute; z-index:5; width:200px; transform:translate(-4px,-2px); }',
      '.edp-status { display:flex; align-items:center; gap:8px; margin-top:8px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.edp-hint { color:var(--yb-ink-4,#8994a5); }',
      '.edp-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:12px; }',
      '.edp-actions .el-button + .el-button { margin-left:0; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 画板组件(全局单例) ================= */
  const EmrDrawingPanelDialog = {
    name: 'EmrDrawingPanelDialog',
    data() {
      return {
        visible: false,
        tools: TOOLS,
        colors: COLOR_PRESETS,
        tool: 'pen',
        color: DEF_COLOR,
        width: 2,
        fontSize: 14,
        strokes: [],
        draft: null,          /* 拖拽中的临时图形(松手提交前) */
        selIdx: -1,           /* 选择工具当前选中下标 */
        hist: [],             /* 撤销快照栈(存储 strokes 深拷贝) */
        histIdx: 0,
        /* 底图模板 */
        templates: [], templateId: null, templateSvg: '',
        tplReady: false, tplLoading: false,
        /* 文字浮层输入: mx/my=逻辑坐标, sx/sy=画布内 CSS 坐标 */
        tEd: { on: false, mx: 0, my: 0, sx: 0, sy: 0, val: '' },
        ctx: { title: '', onSave: null },
        /* 会话内实例状态 */
        _bound: false, _mode: 'idle', _start: { x: 0, y: 0 }, _orig: null, _moved: false,
        _tplImg: null, _tplUrl: null
      };
    },
    computed: {
      canUndo() { return this.histIdx > 0; },
      canRedo() { return this.histIdx < this.hist.length - 1; },
      cursorCss() { return CURSORS[this.tool] || 'crosshair'; },
      hintText() { return HINTS[this.tool] || ''; }
    },
    watch: {
      color(v) { if (!v) { this.color = DEF_COLOR; } }   /* 清空取色器时回落默认色 */
    },
    mounted() {
      document.addEventListener('keydown', this.onKey);
    },
    methods: {
      /* ---------- 对外入口: 打开画板(复用单例, 每次 open 全量重置会话态) ---------- */
      open(options) {
        const vm = this;
        const o = options || {};
        vm.ctx = { title: o.title ? String(o.title) : '', onSave: typeof o.onSave === 'function' ? o.onSave : null };
        vm.tool = 'pen';
        vm.selIdx = -1;
        vm.draft = null;
        vm._mode = 'idle';
        vm._orig = null;
        vm._moved = false;
        vm.tEd = { on: false, mx: 0, my: 0, sx: 0, sy: 0, val: '' };
        vm.strokes = [];
        vm.templateSvg = '';
        vm.templateId = null;
        vm.tplLoading = false;
        /* 恢复再编辑数据: annotations 优先(含已存底图); 无 annotations 的旧 svgData 降级为底图, 可在其上补注 */
        const ann = parseAnnotations(o.annotations);
        if (ann) {
          vm.strokes = ann.strokes;
          vm.templateId = ann.templateId;
          vm.templateSvg = ann.templateSvg;
        } else if (isSvgString(o.svgData)) {
          vm.templateSvg = o.svgData;
        }
        if (!vm.templateSvg && isSvgString(o.templateSvg)) { vm.templateSvg = o.templateSvg; }
        vm.resetHistory();
        vm.loadTplImage();
        vm.loadTemplates();
        vm.visible = true;
      },
      onOpened() {
        this.bindCanvas();
        this.fitCanvas();
      },

      /* ---------- 画布绑定(鼠标+触摸双通道; 拖动中画布外仍跟踪) ---------- */
      bindCanvas() {
        const vm = this;
        const c = vm.$refs.pad;
        if (!c) { return; }
        if (!vm._bound) {
          vm._bound = true;
          const down = (e) => vm.onDown(e);
          const move = (e) => vm.onMove(e);
          const up = (e) => vm.onUp(e);
          c.addEventListener('mousedown', down);
          c.addEventListener('touchstart', down, { passive: false });
          /* 画布外继续拖动/抬起兜底(仅拖拽激活时消费, 空闲时零开销) */
          window.addEventListener('mousemove', move);
          window.addEventListener('mouseup', up);
          window.addEventListener('touchmove', move, { passive: false });
          window.addEventListener('touchend', up);
          window.addEventListener('touchcancel', up);
          window.addEventListener('resize', function () { if (vm.visible) { vm.fitCanvas(); } });
        }
      },
      /* 事件坐标 → 800x600 逻辑坐标(考虑 CSS 缩放, 越界收敛到画布内) */
      pos(e) {
        const c = this.$refs.pad;
        const r = c.getBoundingClientRect();
        const pt = (e.touches && e.touches[0]) ? e.touches[0] : e;
        const x = (pt.clientX - r.left) * (LOGIC_W / r.width);
        const y = (pt.clientY - r.top) * (LOGIC_H / r.height);
        return { x: Math.max(0, Math.min(LOGIC_W, r1(x))), y: Math.max(0, Math.min(LOGIC_H, r1(y))) };
      },
      onDown(e) {
        const vm = this;
        if (!vm.visible) { return; }
        if (e.button != null && e.button !== 0 && !e.touches) { return; }   /* 仅左键 / 触摸 */
        e.preventDefault();
        const p = vm.pos(e);
        if (vm.tool === 'text') { vm.openTextEdit(p); return; }
        if (vm.tool === 'eraser') { vm.eraseAt(p); return; }
        if (vm.tool === 'select') {
          const hit = vm.hitAt(p);
          vm.selIdx = hit;
          if (hit >= 0) {
            vm._mode = 'move';
            vm._start = { x: p.x, y: p.y };
            vm._orig = cloneStrokes([vm.strokes[hit]])[0];
            vm._moved = false;
          }
          vm.redraw();
          return;
        }
        vm._mode = 'draw';
        vm._start = { x: p.x, y: p.y };
        vm._moved = false;
        const st = { color: vm.color || DEF_COLOR, width: vm.width || 2 };
        if (vm.tool === 'pen') { vm.draft = Object.assign({ type: 'pen', points: [[p.x, p.y]] }, st); }
        else if (vm.tool === 'line') { vm.draft = Object.assign({ type: 'line', from: [p.x, p.y], to: [p.x, p.y] }, st); }
        else if (vm.tool === 'rect') { vm.draft = Object.assign({ type: 'rect', from: [p.x, p.y], to: [p.x, p.y] }, st); }
        else if (vm.tool === 'ellipse') { vm.draft = Object.assign({ type: 'ellipse', center: [p.x, p.y], rx: 0, ry: 0 }, st); }
        vm.redraw();
      },
      onMove(e) {
        const vm = this;
        if (vm._mode === 'idle') { return; }
        e.preventDefault();
        const p = vm.pos(e);
        if (vm._mode === 'draw' && vm.draft) {
          if (vm.tool === 'pen') {
            const pts = vm.draft.points;
            const last = pts[pts.length - 1];
            const dx = p.x - last[0], dy = p.y - last[1];
            if (dx * dx + dy * dy < SAMPLE_D2) { return; }
            pts.push([p.x, p.y]);
          } else if (vm.tool === 'line' || vm.tool === 'rect') {
            vm.draft.to = [p.x, p.y];
          } else if (vm.tool === 'ellipse') {
            vm.draft.center = [(vm._start.x + p.x) / 2, (vm._start.y + p.y) / 2];
            vm.draft.rx = Math.abs(p.x - vm._start.x) / 2;
            vm.draft.ry = Math.abs(p.y - vm._start.y) / 2;
          }
          vm._moved = true;
          vm.redraw();
        } else if (vm._mode === 'move' && vm.selIdx >= 0 && vm._orig) {
          const dx = p.x - vm._start.x, dy = p.y - vm._start.y;
          if (dx === 0 && dy === 0) { return; }
          vm._moved = true;
          vm.strokes.splice(vm.selIdx, 1, offsetStroke(vm._orig, dx, dy));
          vm.redraw();
        }
      },
      onUp() {
        const vm = this;
        if (vm._mode === 'idle') { return; }
        if (vm._mode === 'draw') {
          const st = vm.commitDraft();
          if (st) { vm.strokes.push(st); vm.pushHistory(); }
          vm.draft = null;
          vm.redraw();
        } else if (vm._mode === 'move') {
          if (vm._moved) { vm.pushHistory(); }
          vm._orig = null;
        }
        vm._mode = 'idle';
        vm._moved = false;
      },
      /* 拖拽图形定稿: 线/矩形过短或圆太小视为误触丢弃; 规范化后入栈 */
      commitDraft() {
        const s = this.draft;
        if (!s) { return null; }
        if (s.type === 'line' && s.from[0] === s.to[0] && s.from[1] === s.to[1]) { return null; }
        if (s.type === 'rect' && Math.abs(s.to[0] - s.from[0]) < 1 && Math.abs(s.to[1] - s.from[1]) < 1) { return null; }
        if (s.type === 'ellipse' && s.rx < 0.5 && s.ry < 0.5) { return null; }
        return sanitizeStroke(s);
      },

      /* ---------- 文字标注(浮层输入, Enter/失焦确认, Esc 取消) ---------- */
      openTextEdit(p) {
        const vm = this;
        if (vm.tEd.on) { vm.commitText(); }
        const c = vm.$refs.pad;
        if (!c) { return; }
        const r = c.getBoundingClientRect();
        const sx = (p.x / LOGIC_W) * r.width;
        const sy = (p.y / LOGIC_H) * r.height;
        vm.tEd = {
          on: true, mx: p.x, my: p.y,
          sx: Math.min(sx, Math.max(0, r.width - 208)), sy: sy,
          val: ''
        };
        vm.$nextTick(function () {
          const i = vm.$refs.teInput;
          if (i && i.focus) { i.focus(); }
        });
      },
      commitText() {
        const vm = this;
        if (!vm.tEd.on) { return; }
        const v = String(vm.tEd.val || '').trim();
        if (v) {
          vm.strokes.push({
            type: 'text', pos: [vm.tEd.mx, vm.tEd.my], text: v.slice(0, 200),
            color: vm.color || DEF_COLOR, fontSize: vm.fontSize || 14
          });
          vm.pushHistory();
        }
        vm.tEd.on = false;
        vm.redraw();
      },
      cancelText() { this.tEd.on = false; this.redraw(); },

      /* ---------- 命中 / 橡皮 / 删除 ---------- */
      hitAt(p) {
        const list = this.strokes;
        for (let i = list.length - 1; i >= 0; i--) {          /* 后绘者在上: 自顶向下命中 */
          if (hitStroke(list[i], p.x, p.y)) { return i; }
        }
        return -1;
      },
      eraseAt(p) {
        const i = this.hitAt(p);
        if (i < 0) { return; }
        this.strokes.splice(i, 1);
        this.selIdx = -1;
        this.pushHistory();
        this.redraw();
      },
      removeSelected() {
        const vm = this;
        if (vm.selIdx < 0 || !vm.strokes[vm.selIdx]) { return; }
        vm.strokes.splice(vm.selIdx, 1);
        vm.selIdx = -1;
        vm.pushHistory();
        vm.redraw();
      },

      /* ---------- 撤销 / 重做 / 清除 ---------- */
      pushHistory() {
        const vm = this;
        const hist = vm.hist.slice(0, vm.histIdx + 1);
        hist.push(cloneStrokes(vm.strokes));
        while (hist.length > MAX_HIST) { hist.shift(); }
        vm.hist = hist;
        vm.histIdx = hist.length - 1;
      },
      resetHistory() {
        this.hist = [cloneStrokes(this.strokes)];
        this.histIdx = 0;
      },
      undo() {
        const vm = this;
        if (vm.histIdx <= 0) { return; }
        vm.histIdx--;
        vm.strokes = cloneStrokes(vm.hist[vm.histIdx]);
        vm.selIdx = -1;
        vm.redraw();
      },
      redo() {
        const vm = this;
        if (vm.histIdx >= vm.hist.length - 1) { return; }
        vm.histIdx++;
        vm.strokes = cloneStrokes(vm.hist[vm.histIdx]);
        vm.selIdx = -1;
        vm.redraw();
      },
      clearAll() {
        const vm = this;
        if (!vm.strokes.length) { return; }
        ElementPlus.ElMessageBox.confirm('确认清除全部标注内容?', '清除确认', { type: 'warning' })
          .then(function () {
            vm.strokes = [];
            vm.selIdx = -1;
            vm.pushHistory();
            vm.redraw();
          })
          .catch(function () { /* 用户取消 */ });
      },

      /* ---------- 底图模板 ---------- */
      loadTemplates() {
        const vm = this;
        vm.tplReady = false;
        HIS.get('/api/his/emr/drawing-template/list').then(function (list) {
          vm.templates = (list || []).map(function (t) {
            return { id: HIS.id(t.id), name: t.name || t.templateName || ('模板 ' + t.id) };
          });
          vm.tplReady = true;
        }).catch(function () {
          /* 后端未就绪: 优雅降级为空底图, 仅停用下拉提示, 不打断绘制 */
          vm.templates = [];
          vm.tplReady = true;
        });
      },
      onTemplateChange(id) {
        const vm = this;
        if (!id || id === '__none__') { vm.templateId = null; vm.templateSvg = ''; vm.loadTplImage(); return; }
        vm.tplLoading = true;
        HIS.get('/api/his/emr/drawing-template/' + HIS.idParam(id)).then(function (d) {
          const svg = typeof d === 'string' ? d : (d && (d.svgData || d.svg || d.content)) || '';
          if (!isSvgString(svg)) { throw new Error('模板内容无效'); }
          vm.templateSvg = svg;
          vm.loadTplImage();
        }).catch(function (e) {
          HIS.notifyError(e);
          vm.templateId = null;
          vm.templateSvg = '';
          vm.loadTplImage();
        }).finally(function () { vm.tplLoading = false; });
      },
      /* 底图 SVG → Image(供 drawImage); 大图走 Blob URL, 失败回落 data URI */
      loadTplImage() {
        const vm = this;
        if (vm._tplUrl) { try { URL.revokeObjectURL(vm._tplUrl); } catch (e) { /* noop */ } vm._tplUrl = null; }
        vm._tplImg = null;
        if (!isSvgString(vm.templateSvg)) { vm.redraw(); return; }
        let url = '';
        try { url = URL.createObjectURL(new Blob([vm.templateSvg], { type: 'image/svg+xml;charset=utf-8' })); }
        catch (e) { url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(vm.templateSvg); }
        vm._tplUrl = url;
        const img = new Image();
        img.onload = function () { vm._tplImg = img; vm.redraw(); };
        img.onerror = function () { vm._tplImg = null; vm.redraw(); };
        img.src = url;
      },

      /* ---------- 尺寸 / 重绘 ---------- */
      /* 自适应: 宽度取 弹窗可用宽/视口高换算/800 三者最小; 内部像素 ×dpr 保 HiDPI 清晰 */
      fitCanvas() {
        const vm = this;
        const c = vm.$refs.pad;
        if (!c) { return; }
        const stage = vm.$refs.stage;
        const availW = (stage && stage.clientWidth) ? stage.clientWidth : LOGIC_W;
        const availH = Math.max(240, Math.round((window.innerHeight || 800) * 0.62));
        let cssW = Math.min(LOGIC_W, availW, Math.round(availH * LOGIC_W / LOGIC_H));
        cssW = Math.max(320, cssW);
        const cssH = Math.round(cssW * LOGIC_H / LOGIC_W);
        const dpr = Math.min(3, Math.max(1, window.devicePixelRatio || 1));
        c.style.width = cssW + 'px';
        c.style.height = cssH + 'px';
        const bw = Math.round(cssW * dpr), bh = Math.round(cssH * dpr);
        if (c.width !== bw || c.height !== bh) { c.width = bw; c.height = bh; }
        vm.redraw();
      },
      /* 全量重绘: 白底 → 底图 → 标注 → 拖拽草稿 → 选中高亮 */
      redraw() {
        const vm = this;
        const c = vm.$refs.pad;
        if (!c) { return; }
        const ctx = c.getContext('2d');
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.clearRect(0, 0, c.width, c.height);
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, c.width, c.height);
        ctx.setTransform(c.width / LOGIC_W, 0, 0, c.height / LOGIC_H, 0, 0);
        if (vm._tplImg) { try { ctx.drawImage(vm._tplImg, 0, 0, LOGIC_W, LOGIC_H); } catch (e) { /* noop */ } }
        const list = vm.strokes;
        for (let i = 0; i < list.length; i++) { drawStroke(ctx, list[i]); }
        if (vm.draft) { drawStroke(ctx, vm.draft); }
        if (vm.selIdx >= 0 && list[vm.selIdx]) { drawSelBox(ctx, list[vm.selIdx]); }
        ctx.setTransform(1, 0, 0, 1, 0, 0);
      },

      /* ---------- 保存 / 取消 ---------- */
      save() {
        const vm = this;
        if (vm.tEd.on) { vm.commitText(); }
        if (!vm.strokes.length && !isSvgString(vm.templateSvg)) {
          ElementPlus.ElMessage.warning('尚未绘制任何内容');
          return;
        }
        const annotations = JSON.stringify({
          strokes: vm.strokes,
          templateId: vm.templateId || null,
          templateSvg: vm.templateSvg || ''
        });
        const svgData = buildSvgString(vm.strokes, vm.templateSvg);
        vm.visible = false;
        const cb = vm.ctx && vm.ctx.onSave;
        if (typeof cb === 'function') {
          try { cb(svgData, annotations); } catch (e) { /* 宿主回调异常不扩散 */ }
        }
      },
      onCancel() { this.visible = false; },

      /* ---------- 快捷键 ---------- */
      onKey(e) {
        const vm = this;
        if (!vm.visible) { return; }
        if ((e.ctrlKey || e.metaKey) && !vm.tEd.on) {
          const k = e.key || '';
          if (k === 'z' || k === 'Z') { e.preventDefault(); if (e.shiftKey) { vm.redo(); } else { vm.undo(); } return; }
          if (k === 'y' || k === 'Y') { e.preventDefault(); vm.redo(); return; }
        }
        if (!vm.tEd.on && vm.selIdx >= 0 && (e.key === 'Delete' || e.key === 'Backspace')) {
          e.preventDefault();
          vm.removeSelected();
        }
      }
    },
    template: [
      '<el-dialog v-model="visible" width="90%" append-to-body class="edp-dialog" :close-on-click-modal="false" @opened="onOpened">',
      '  <template #header>',
      '    <div class="edp-head">',
      '      <span class="edp-title">医学图示编辑器</span>',
      '      <span class="edp-sub" v-if="ctx.title">{{ ctx.title }}</span>',
      '    </div>',
      '  </template>',
      '  <div class="edp-bar">',
      '    <el-button v-for="t in tools" :key="t.t" size="small" :type="tool === t.t ? \'primary\' : \'\'" @click="tool = t.t">{{ t.l }}</el-button>',
      '    <span class="edp-sep"></span>',
      '    <span class="edp-lb">颜色</span>',
      '    <el-color-picker v-model="color" size="small" :predefine="colors"></el-color-picker>',
      '    <span class="edp-lb">线宽</span>',
      '    <el-input-number v-model="width" size="small" :min="1" :max="12" controls-position="right" style="width:84px"></el-input-number>',
      '    <span class="edp-lb">字号</span>',
      '    <el-input-number v-model="fontSize" size="small" :min="10" :max="72" controls-position="right" style="width:84px"></el-input-number>',
      '    <span class="edp-sep"></span>',
      '    <el-button size="small" :disabled="!canUndo" @click="undo">撤销</el-button>',
      '    <el-button size="small" :disabled="!canRedo" @click="redo">重做</el-button>',
      '    <el-button size="small" type="danger" plain :disabled="!strokes.length" @click="clearAll">清除</el-button>',
      '    <span class="edp-grow"></span>',
      '    <span class="edp-lb">底图</span>',
      '    <el-select v-model="templateId" size="small" style="width:220px" clearable placeholder="无底图" :loading="tplLoading" @change="onTemplateChange">',
      '      <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id"></el-option>',
      '      <el-option v-if="tplReady && !templates.length" value="__none__" label="暂无可用模板" disabled></el-option>',
      '    </el-select>',
      '  </div>',
      '  <div class="edp-stage" ref="stage">',
      '    <canvas ref="pad" class="edp-canvas" :style="{ cursor: cursorCss }"></canvas>',
      '    <div class="edp-text-edit" v-if="tEd.on" :style="{ left: tEd.sx + \'px\', top: tEd.sy + \'px\' }">',
      '      <el-input ref="teInput" v-model="tEd.val" size="small" placeholder="输入文字, Enter 确认" @keyup.enter="commitText" @keydown.esc.stop="cancelText" @blur="commitText"></el-input>',
      '    </div>',
      '  </div>',
      '  <div class="edp-status">',
      '    <span class="edp-hint">{{ hintText }}</span>',
      '    <span class="edp-grow"></span>',
      '    <span>标注 {{ strokes.length }} 个<template v-if="selIdx >= 0"> · 已选 #{{ selIdx + 1 }} (Delete 删除)</template></span>',
      '  </div>',
      '  <div class="edp-actions">',
      '    <el-button size="small" @click="onCancel">取消</el-button>',
      '    <el-button size="small" type="primary" @click="save">保存</el-button>',
      '  </div>',
      '</el-dialog>'
    ].join('\n')
  };

  /* ================= 全局单例: 独立 Vue 应用挂载到 body 末尾(反复 open 复用) ================= */
  let _vm = null;
  function ensureVm() {
    if (_vm) { return _vm; }
    const host = document.createElement('div');
    host.id = 'emr-drawing-panel-host';
    document.body.appendChild(host);
    const app = Vue.createApp(EmrDrawingPanelDialog);
    /* 全局 ElementPlus 插件(带中文 locale), 使宿主应用内的 el-* 组件可用 */
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    _vm = app.mount(host);
    return _vm;
  }

  /* ================= 对外注册 ================= */
  HIS.EmrDrawingPanel = {
    /**
     * 打开医学图示画板(全局单例弹窗)。
     * @param {Object} options
     * @param {string}   [options.svgData]      现有 SVG 内容(无有效 annotations 时降级为底图展示)
     * @param {string}   [options.annotations]  再编辑数据(JSON 串): {strokes, templateId, templateSvg}
     * @param {string}   [options.title]        图示标题(弹窗副标题展示)
     * @param {Function} [options.onSave]       保存回调(svgData, annotationsJson)
     * @param {string}   [options.templateSvg]  可选底图模板 SVG(annotations 内已存底图时以 annotations 为准)
     */
    open(options) {
      return ensureVm().open(options);
    }
  };
})();
