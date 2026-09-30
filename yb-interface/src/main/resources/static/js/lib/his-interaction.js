/* ============================================================================
 * HIS 全局交互层: 右键上下文菜单(HIS.contextMenu) + 快捷键注册表(HIS.shortcuts)
 *
 * 约束与定位:
 *   - 纯 DOM/原生事件实现, 不依赖 Vue 组件树与第三方库; 在 index.html 中先于
 *     app.js 加载, 任意视图(含字符串模板组件/表格行/树节点)均可直接调用;
 *   - 视觉统一走 theme.css 设计令牌(--yb-*); :hover / 动画无法内联,
 *     经一次性 <style> 注入(幂等);
 *   - 快捷键为"注册表 + 栈顶优先"模型: 同一组合键多次注册时, 最后注册的
 *     handler 先响应(页面级可覆盖默认级); 支持按 id / 按 scope 注销,
 *     供组件卸载时清理, 防栈内残留失效 handler。
 *
 * 用法:
 *   // 右键菜单
 *   el.addEventListener('contextmenu', function (e) {
 *     e.preventDefault();
 *     HIS.contextMenu(e, [
 *       { label: '查看详情', icon: 'View', action: function () { ... } },
 *       { divider: true },
 *       { label: '删除', icon: 'Delete', action: function () { ... }, disabled: true }
 *     ], { width: 180 });
 *   });
 *
 *   // 快捷键: combo 大小写不敏感, 支持 escape→esc / arrowup→up / cmd→ctrl 等别名
 *   var id = HIS.shortcuts.register('ctrl+s', function () { ... }, 'staff-page');
 *   HIS.shortcuts.unregister(id);                // 按 id 注销
 *   HIS.shortcuts.unregisterScope('staff-page'); // 组件卸载时按作用域注销
 * ========================================================================== */
(function () {
  var HIS = (window.HIS = window.HIS || {});

  /* ==========================================================================
   * 一、右键上下文菜单
   * ========================================================================== */

  /* Element Plus 图标名 → Unicode 符号: 项目未注册 EP 图标包(无构建约束),
   * 用字符符号保证零依赖; 未命中的 icon 静默降级为纯文字项(更可靠)。
   * 非 BMP 字符统一 \u 转义写法, 规避任何编码链路风险。 */
  var CTX_GLYPHS = {
    View: '\uD83D\uDC41',         /* 👁 查看 */
    Search: '\uD83D\uDD0D',       /* 🔍 搜索 */
    Edit: '\u270F\uFE0F',         /* ✏️ 编辑 */
    Delete: '\uD83D\uDDD1\uFE0F', /* 🗑️ 删除 */
    DeleteFilled: '\uD83D\uDDD1\uFE0F',
    Printer: '\uD83D\uDDA8\uFE0F',/* 🖨️ 打印 */
    Document: '\uD83D\uDCC4',     /* 📄 文档 */
    CopyDocument: '\u29C9',       /* ⧉ 复制 */
    List: '\uD83D\uDCCB',         /* 📋 列表 */
    Tickets: '\uD83C\uDFAB',      /* 🎫 票据 */
    Refresh: '\u21BB',            /* ↻ 刷新 */
    RefreshRight: '\u21BB',
    RefreshLeft: '\u21BA',
    Plus: '\uFF0B',               /* ＋ 新增 */
    Minus: '\uFF0D',              /* － 移除 */
    Download: '\u2B07\uFE0F',     /* ⬇️ 导出 */
    Upload: '\u2B06\uFE0F',       /* ⬆️ 上传 */
    Close: '\u2715',              /* ✕ 关闭 */
    Lock: '\uD83D\uDD12',         /* 🔒 锁定 */
    Unlock: '\uD83D\uDD13',       /* 🔓 解锁 */
    User: '\uD83D\uDC64',         /* 👤 人员 */
    Setting: '\u2699\uFE0F',      /* ⚙️ 设置 */
    Star: '\u2605',               /* ★ 收藏 */
    Collection: '\u2605',
    Warning: '\u26A0\uFE0F',      /* ⚠️ 警告 */
    WarningFilled: '\u26A0\uFE0F',
    InfoFilled: '\u2139\uFE0F',   /* ℹ️ 信息 */
    Link: '\uD83D\uDD17',         /* 🔗 关联 */
    Check: '\u2713',              /* ✓ 校验 */
    CircleCheck: '\u2714',
    Bell: '\uD83D\uDD14',         /* 🔔 提醒 */
    Calendar: '\uD83D\uDCC5',     /* 📅 日程 */
    Clock: '\uD83D\uDD50',        /* 🕐 时钟 */
    Timer: '\u23F1\uFE0F',        /* ⏱️ 计时 */
    Money: '\uD83D\uDCB0',        /* 💰 金额 */
    Coin: '\uD83E\uDE99',
    Histogram: '\uD83D\uDCCA',    /* 📊 图表 */
    TrendCharts: '\uD83D\uDCC8',
    DataAnalysis: '\uD83D\uDCCA',
    Folder: '\uD83D\uDCC1',       /* 📁 目录 */
    Files: '\uD83D\uDDC2\uFE0F',  /* 🗂️ 档案 */
    Notebook: '\uD83D\uDCD3',     /* 📓 记录 */
    Position: '\uD83D\uDCCD',     /* 📍 定位 */
    Promotion: '\uD83D\uDCE2',    /* 📢 通报 */
    Message: '\uD83D\uDCAC',      /* 💬 消息 */
    Phone: '\u260E\uFE0F',        /* ☎️ 电话 */
    Aim: '\uD83C\uDFAF'           /* 🎯 目标 */
  };

  var CTX_CLASS = 'his-ctx-menu';
  var _ctxEl = null;    /* 菜单单例 DOM: 创建后复用, 关闭时仅摘离文档 */
  var _ctxClose = null; /* 当前打开菜单的关闭函数(统一清理全局监听) */

  /* 一次性注入菜单静态样式(幂等) */
  function ensureCtxStyle() {
    if (document.getElementById('his-ctx-style')) { return; }
    var st = document.createElement('style');
    st.id = 'his-ctx-style';
    st.textContent = [
      '.' + CTX_CLASS + '{position:fixed;z-index:9999;min-width:160px;background:var(--yb-surface,#fff);',
      'border-radius:var(--yb-r-md,8px);border:1px solid var(--yb-border,#e4e7ed);',
      'box-shadow:var(--yb-sh-pop,0 4px 16px rgba(0,0,0,.12));padding:4px 0;',
      'font-size:var(--yb-fs-base,13px);color:var(--yb-ink-1,#303133);line-height:1.4;',
      'animation:his-ctx-in var(--yb-dur,.16s) var(--yb-ease,ease-out);}',
      '.' + CTX_CLASS + ' .his-ctx-item{padding:8px 16px;cursor:pointer;display:flex;align-items:center;gap:8px;}',
      /* hover 语言与 theme.css 的 el-dropdown 精修一致: 品牌浅底 + 品牌文字色 */
      '.' + CTX_CLASS + ' .his-ctx-item:hover{background:var(--yb-brand-subtle,#f0f7ff);color:var(--yb-brand,#1a5c9e);}',
      '.' + CTX_CLASS + ' .his-ctx-item.is-disabled{opacity:.5;pointer-events:none;}',
      '.' + CTX_CLASS + ' .his-ctx-icon{flex:none;width:16px;text-align:center;opacity:.72;}',
      '.' + CTX_CLASS + ' .his-ctx-divider{height:1px;margin:4px 0;background:var(--yb-border,#e4e7ed);}',
      '@keyframes his-ctx-in{from{opacity:0;transform:scale(.97) translateY(-2px);}to{opacity:1;transform:none;}}'
    ].join('\n');
    document.head.appendChild(st);
  }

  /**
   * 打开右键上下文菜单
   * @param {MouseEvent} event   原生鼠标事件(取 clientX/clientY 定位; 调用方应先 preventDefault)
   * @param {Array}      items   菜单项 [{ label, icon, action, disabled, divider }]
   *                             - icon:     Element Plus 图标名(见 CTX_GLYPHS, 未命中则纯文字)
   *                             - action:   点击回调(菜单先关闭再执行, 不被菜单遮挡)
   *                             - disabled: 置灰不可点
   *                             - divider:  true 渲染分割线(其余字段忽略)
   * @param {Object}     options { width: 180 } 可选, 菜单最小宽度(默认 160)
   */
  HIS.contextMenu = function (event, items, options) {
    options = options || {};
    /* 任何新菜单打开前先关闭旧菜单(含其全局监听清理) */
    if (_ctxClose) { _ctxClose(); }
    if (!items || !items.length) { return; }

    ensureCtxStyle();

    /* 单例容器: 首次创建后复用, 关闭时仅摘离文档 */
    if (!_ctxEl) {
      _ctxEl = document.createElement('div');
      _ctxEl.className = CTX_CLASS;
    }
    var menu = _ctxEl;
    /* 清空旧菜单项: 纯 DOM 摘除(避免 innerHTML 解析路径, 菜单项文本一律 textContent) */
    while (menu.firstChild) { menu.removeChild(menu.firstChild); }

    /* 渲染菜单项(纯 DOM; label 走 textContent, 天然防注入) */
    for (var i = 0; i < items.length; i++) {
      var it = items[i] || {};
      if (it.divider) {
        var dv = document.createElement('div');
        dv.className = 'his-ctx-divider';
        menu.appendChild(dv);
        continue;
      }
      var row = document.createElement('div');
      row.className = 'his-ctx-item' + (it.disabled ? ' is-disabled' : '');
      var glyph = CTX_GLYPHS[it.icon];
      if (glyph) {
        var ic = document.createElement('span');
        ic.className = 'his-ctx-icon';
        ic.textContent = glyph;
        row.appendChild(ic);
      }
      var lb = document.createElement('span');
      lb.textContent = it.label || '';
      row.appendChild(lb);
      if (!it.disabled && typeof it.action === 'function') {
        row.addEventListener('click', (function (action) {
          return function (e) {
            e.stopPropagation();            /* 菜单内部点击不算"点击其他区域" */
            if (_ctxClose) { _ctxClose(); } /* 先关菜单再执行业务, 避免弹层遮挡后续交互 */
            try { action(e); }
            catch (err) { console.error('[HIS.contextMenu] action error:', err); }
          };
        })(it.action));
      }
      menu.appendChild(row);
    }

    /* 定位: 先隐藏挂载量取实际尺寸, 再按视口边界校正(右/下溢出时回退) */
    document.body.appendChild(menu);
    menu.style.visibility = 'hidden';
    menu.style.minWidth = (options.width || 160) + 'px';
    menu.style.left = '0px';
    menu.style.top = '0px';
    var w = menu.offsetWidth, h = menu.offsetHeight;
    var x = (event ? event.clientX : 0) + 2;
    var y = (event ? event.clientY : 0) + 2;
    var vw = window.innerWidth || document.documentElement.clientWidth;
    var vh = window.innerHeight || document.documentElement.clientHeight;
    if (x + w > vw - 8) { x = Math.max(8, vw - w - 8); }
    if (y + h > vh - 8) { y = Math.max(8, vh - h - 8); }
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';
    menu.style.visibility = '';

    /* 全局关闭: 点击菜单外任意处 / 右键他处 / 任意滚动 / 窗口缩放或失焦 */
    var closed = false;
    var onDocClick = function () { if (_ctxClose) { _ctxClose(); } };
    var onDocCtx = function () { if (_ctxClose) { _ctxClose(); } };
    var onWinChange = function () { if (_ctxClose) { _ctxClose(); } };
    _ctxClose = function () {
      if (closed) { return; }
      closed = true;
      _ctxClose = null;
      document.removeEventListener('click', onDocClick);
      document.removeEventListener('contextmenu', onDocCtx, true);
      document.removeEventListener('scroll', onWinChange, true);
      window.removeEventListener('resize', onWinChange);
      window.removeEventListener('blur', onWinChange);
      if (menu.parentNode) { menu.parentNode.removeChild(menu); }
    };
    document.addEventListener('click', onDocClick);
    /* contextmenu 用捕获: 右键他处时旧菜单先于业务 handler 关闭, 随后新菜单正常打开 */
    document.addEventListener('contextmenu', onDocCtx, true);
    document.addEventListener('scroll', onWinChange, true);
    window.addEventListener('resize', onWinChange);
    window.addEventListener('blur', onWinChange);
  };

  /* 编程式关闭当前菜单(一般无需手动调用) */
  HIS.contextMenu.close = function () { if (_ctxClose) { _ctxClose(); } };

  /* ==========================================================================
   * 二、全局快捷键注册表
   * ========================================================================== */

  /* 键名别名归一(注册端与事件端共用): 让 'escape' / 'alt+arrowup' / 'cmd+s'
   * 等自然写法与 _buildKey 的输出一致命中 */
  var KEY_ALIAS = {
    escape: 'esc',
    arrowup: 'up', arrowdown: 'down', arrowleft: 'left', arrowright: 'right',
    spacebar: 'space', ' ': 'space',
    del: 'delete',
    return: 'enter',
    cmd: 'ctrl', command: 'ctrl', meta: 'ctrl', control: 'ctrl'
  };
  var MOD_ORDER = ['ctrl', 'alt', 'shift']; /* 修饰键固定顺序, 保证两端键串一致 */

  HIS.shortcuts = {
    _handlers: {},          /* { 'ctrl+s': [{ id, handler, scope }] } 同键叠加, 栈顶优先 */
    _active: true,          /* 总开关: false 时暂停分发 */
    _inited: false,
    _idSeq: 0,
    _defaultsDone: false,

    /** 安装全局 keydown 分发(幂等); app.js 启动时调用, 并自动注册默认快捷键 */
    init: function () {
      if (this._inited) { return; }
      this._inited = true;
      var self = this;
      document.addEventListener('keydown', function (e) {
        if (!self._active) { return; }
        var key = self._buildKey(e);
        /* 输入控件保护: 焦点在 INPUT/TEXTAREA/SELECT/可编辑区时只放行 Esc,
         * 防止录入过程中误触发保存/打印等业务动作 */
        if (self._inEditor(e) && key !== 'esc') { return; }
        var handlers = self._handlers[key];
        if (!handlers || !handlers.length) { return; }
        var h = handlers[handlers.length - 1]; /* 栈顶优先: 页面级覆盖默认级 */
        var consumed = true;
        try {
          /* handler 返回 false 表示未消费(如 F5 无刷新锚点时放行浏览器默认刷新) */
          consumed = h.handler(e) !== false;
        } catch (err) {
          console.error('[HIS.shortcuts] handler error (' + key + '):', err);
          consumed = true; /* 出错也视为已消费, 防 F5 误刷新丢数据 */
        }
        if (consumed) { e.preventDefault(); e.stopPropagation(); }
      });
      /* 默认全局快捷键随 init 自动注册(app.js 亦可显式再调 _defaults, 幂等防重) */
      this._defaults();
    },

    /**
     * 注册快捷键
     * @param {String}   combo   组合键: 'ctrl+s' / 'f5' / 'alt+up' / 'escape'
     *                           (大小写与空格不敏感; 支持 arrowup/escape/space/del/cmd 等别名)
     * @param {Function} handler 处理函数; 返回 false 表示未消费(放行浏览器默认行为)
     * @param {String}   scope   作用域标记(默认 'global'), 供 unregisterScope 批量注销
     * @returns {Number} 注册 id(-1 表示参数非法), 供 unregister 精确注销
     */
    register: function (combo, handler, scope) {
      if (!combo || typeof handler !== 'function') { return -1; }
      var key = this._normalizeCombo(combo);
      if (!key) { return -1; }
      if (!this._handlers[key]) { this._handlers[key] = []; }
      var id = ++this._idSeq;
      this._handlers[key].push({ id: id, handler: handler, scope: scope || 'global' });
      return id;
    },

    /** 按 id 注销(register 的返回值) */
    unregister: function (id) {
      var keys = Object.keys(this._handlers);
      for (var i = 0; i < keys.length; i++) {
        var list = this._handlers[keys[i]];
        if (!list) { continue; }
        this._handlers[keys[i]] = list.filter(function (h) { return h.id !== id; });
      }
    },

    /** 按作用域注销全部快捷键(组件卸载时清理, 防栈内残留失效 handler) */
    unregisterScope: function (scope) {
      var keys = Object.keys(this._handlers);
      for (var i = 0; i < keys.length; i++) {
        var list = this._handlers[keys[i]];
        if (!list) { continue; }
        this._handlers[keys[i]] = list.filter(function (h) { return h.scope !== scope; });
      }
    },

    /** 焦点是否处于录入控件内(含 contenteditable 富文本区) */
    _inEditor: function (e) {
      var el = (e && e.target) || document.activeElement;
      if (!el) { return false; }
      var tag = el.tagName;
      return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true;
    },

    /** 由键盘事件构建标准化键串: 修饰键固定 ctrl>alt>shift 顺序 + 归一化主键 */
    _buildKey: function (e) {
      var parts = [];
      if (e.ctrlKey || e.metaKey) { parts.push('ctrl'); }
      if (e.altKey) { parts.push('alt'); }
      if (e.shiftKey) { parts.push('shift'); }
      var k = (e.key || '').toLowerCase();
      if (KEY_ALIAS[k] !== undefined) { k = KEY_ALIAS[k]; }
      parts.push(k);
      return parts.join('+');
    },

    /** 注册端 combo 归一化: 别名映射 + 修饰键去重排序, 输出与 _buildKey 对齐 */
    _normalizeCombo: function (combo) {
      var raw = String(combo).toLowerCase().replace(/\s+/g, '').split('+')
        .filter(function (p) { return p !== ''; });
      if (!raw.length) { return ''; }
      var main = raw.pop();
      if (KEY_ALIAS[main] !== undefined) { main = KEY_ALIAS[main]; }
      var mods = [];
      for (var i = 0; i < raw.length; i++) {
        var m = KEY_ALIAS[raw[i]] !== undefined ? KEY_ALIAS[raw[i]] : raw[i];
        if (MOD_ORDER.indexOf(m) >= 0 && mods.indexOf(m) < 0) { mods.push(m); }
      }
      mods.sort(function (a, b) { return MOD_ORDER.indexOf(a) - MOD_ORDER.indexOf(b); });
      mods.push(main);
      return mods.join('+');
    }
  };

  /* ==========================================================================
   * 三、默认全局快捷键(init 时自动注册, 幂等防重)
   * 约定: 页面将可被快捷键驱动的按钮标记 data-shortcut 属性:
   *   data-shortcut="refresh" → F5 / data-shortcut="print" → F8
   * ========================================================================== */

  /* 取选择器命中的"最上层可见"节点(倒序遍历: 后挂载的 DOM 在叠层之上)。
   * 可见性用 getClientRects 判定 —— fixed 弹层(overlay)的 offsetParent
   * 恒为 null, 不能用 offsetParent 方案 */
  function topShown(selector, root) {
    var nodes = (root || document).querySelectorAll(selector);
    for (var i = nodes.length - 1; i >= 0; i--) {
      if (nodes[i].getClientRects().length) { return nodes[i]; }
    }
    return null;
  }

  HIS.shortcuts._defaults = function () {
    var S = HIS.shortcuts;
    if (S._defaultsDone) { return; }
    S._defaultsDone = true;

    /* Ctrl+S: 触发最上层对话框/抽屉内的主按钮(保存); 无弹层时也拦截,
     * 避免误触浏览器"保存网页"对话框 */
    S.register('ctrl+s', function () {
      var layer = topShown('.el-dialog') || topShown('.el-drawer');
      if (!layer) { return; }
      var btns = layer.querySelectorAll('.el-button--primary:not(.is-disabled):not(.is-loading)');
      for (var i = btns.length - 1; i >= 0; i--) {
        if (btns[i].getClientRects().length) { btns[i].click(); return; }
      }
    }, 'defaults');

    /* F5: 刷新当前列表; 页面未标记 refresh 锚点时返回 false, 放行浏览器默认刷新 */
    S.register('f5', function () {
      var btn = topShown('[data-shortcut="refresh"]');
      if (!btn) { return false; }
      btn.click();
    }, 'defaults');

    /* F8: 打印; 页面未标记 print 锚点时返回 false 放行 */
    S.register('f8', function () {
      var btn = topShown('[data-shortcut="print"]');
      if (!btn) { return false; }
      btn.click();
    }, 'defaults');

    /* Esc: 关闭最上层抽屉/对话框(Element Plus 已内置 Esc 关闭, 此处为兜底)。
     * 焦点在录入控件上时返回 false 让位: select/date-picker 等组件自身的
     * Esc 语义优先(先关自身浮层, 不连带关闭外层对话框) */
    S.register('esc', function (e) {
      var t = e && e.target;
      var tag = t && t.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) {
        return false;
      }
      var close = topShown('.el-drawer__close-btn') || topShown('.el-dialog__headerbtn');
      if (close) { close.click(); }
      else { return false; }
    }, 'defaults');
  };
})();
