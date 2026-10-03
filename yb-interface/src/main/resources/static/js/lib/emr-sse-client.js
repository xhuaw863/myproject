/* ==================================================================
 * emr-sse-client.js — 病历实时通知 SSE 客户端 + 顶栏实时铃铛
 * ------------------------------------------------------------------
 * 依赖: api.js(HIS.getToken/HIS.getUser, 须先加载); Vue3 / Element Plus 全局可用。
 * 后端: GET /api/his/emr/sse/connect(text/event-stream)。鉴权走 AuthInterceptor 的
 *       Authorization: Bearer 头 —— 浏览器原生 EventSource 无法携带自定义请求头,
 *       故有令牌时一律 fetch + ReadableStream 手工解析 SSE 帧; 仅无令牌(假想的 Cookie
 *       会话部署)才降级原生 EventSource(命名事件需逐类型注册, 本项目恒有令牌不走此分支)。
 * 事件契约: SSE event 名 = 后端 EmrEventType 枚举名; data 为 JSON 信封
 *       { type, displayName, targetUserId, targetDeptId, data, timestamp },
 *       业务负载在 data.message(文案)/data.recordId 等; 心跳与首帧 connected 均为注释行。
 * 提供: HIS.EmrSseClient 单例 ——
 *   connect()/disconnect()/isConnected()/getStatus()  连接管理(指数退避重连 1s→30s 封顶)
 *   on(type, cb)/off(type, cb)                       事件订阅(支持 '*' 通配), on 返回退订函数
 *   getNotifications()/getUnreadCount()              最近 50 条通知(内存)/未读数
 *   markAsRead(id)/markAllRead()/clearAll()          已读与清理
 *   NotificationBell / ConnectionStatus              顶栏铃铛与连接状态点组件(含 .mount)
 * 多标签页: 后端同用户新连接替换旧连接, 多标签直连会互踢成秒级重连风暴; 本客户端以
 *   localStorage 心跳租约(8s 过期)选举唯一"连接标签页", 事件经 BroadcastChannel 中继到
 *   同浏览器全部标签页; 无 BroadcastChannel 时待机标签页仅显示协同状态不收事件。
 * 自动生命周期: 载入时已登录 → 2s 后自动连接; beforeunload/登出 → 断开并让出租约;
 *   端点不可用(403/404/405/流式能力缺失) → console.warn 静默停用(铃铛自动隐藏)。
 * 注册: HIS.EmrSseClient(须在 app.js 之前加载; 顶栏经其 NotificationBell 挂载)。
 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});

  /* ================= 常量 ================= */
  const SSE_URL = '/api/his/emr/sse/connect';
  const MAX_NOTIFICATIONS = 50;        /* 内存通知上限(超出裁最旧) */
  const BACKOFF_BASE_MS = 1000;        /* 重连退避起点 1s */
  const BACKOFF_MAX_MS = 30000;        /* 重连退避上限 30s */
  const AUTO_CONNECT_DELAY_MS = 2000;  /* 载入后自动连接延迟 */
  const STANDBY_POLL_MS = 6000;        /* 待机标签页重新竞选周期 */
  const LEADER_TTL_MS = 8000;          /* 连接标签页租约有效期 */
  const LEADER_KEY = 'his_emr_sse_leader';
  const TAB_ID = 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

  /* 事件类型元数据(与后端 EmrEventType 对齐): label=中文兜底名(线上优先取信封 displayName),
   * kind=ElNotification 样式档(error 级不自动关闭须人工确认)。 */
  const EVENT_TYPES = {
    QC_REMINDER:         { label: '病历质控提醒',   kind: 'warning' },
    QC_PENALTY:          { label: '病历质控扣分',   kind: 'error' },
    SIGNATURE_REQUIRED:  { label: '病历待签名提醒', kind: 'info' },
    RECORD_LOCKED:       { label: '病历锁定通知',   kind: 'warning' },
    RECORD_UNLOCKED:     { label: '病历解锁通知',   kind: 'info' },
    CONSULTATION_UPDATE: { label: '会诊状态更新', kind: 'info' },
    TEMPLATE_UPDATED:    { label: '病历模板更新', kind: 'info' },
    /* P5b-4 质控时效/整改闭环四事件(与 EmrEventType 同名对齐): 预警提醒、超时/整改必达, 申诉结果普通知会 */
    EMR_QC_DEADLINE_WARN: { label: '病历时效临近超时预警', kind: 'warning' },
    EMR_QC_OVERDUE:       { label: '病历时效超时通知',     kind: 'error' },
    EMR_QC_NOTICE:        { label: '病历质控整改通知',     kind: 'warning' },
    EMR_QC_APPEAL_RESULT: { label: '病历缺陷申诉结果通知', kind: 'info' }
  };

  /* 连接状态元数据: dot=状态点色档 ok绿(在线)/mid黄(过渡)/off红(断开)/dis灰(停用) */
  const STATUS_META = {
    connected:    { label: '已连接',   dot: 'ok',  hint: '实时通知通道在线' },
    connecting:   { label: '连接中',   dot: 'mid', hint: '正在建立实时连接' },
    reconnecting: { label: '重连中',   dot: 'mid', hint: '连接中断, 正在自动重连' },
    standby:      { label: '窗口协同', dot: 'mid', hint: '连接由同浏览器其他标签页保持, 事件已实时同步' },
    disconnected: { label: '未连接',   dot: 'off', hint: '实时通知通道未连接' },
    disabled:     { label: '已停用',   dot: 'dis', hint: '实时通知不可用, 已静默停用' }
  };

  /* 条目图标/配色按 kind 分档(16x16 手绘内联 SVG evenodd 镂空, 项目未引图标包) */
  const KIND_ICONS = {
    info:    'M8 1.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM8 4.3a.95.95 0 1 1 0 1.9.95.95 0 0 1 0-1.9zM7.2 6.7h1.6v5.2H7.2z',
    warning: 'M8 1.7 15.3 14.3H.7zM7.25 6h1.5v5h-1.5zM7.2 12h1.6v1.6h-1.6z',
    error:   'M8 1.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM5.6 6.9 6.9 5.6 8 6.7 9.1 5.6 10.4 6.9 9.3 8 10.4 9.1 9.1 10.4 8 9.3 6.9 10.4 5.6 9.1 6.7 8z'
  };
  const KIND_COLORS = { info: 'var(--yb-brand)', warning: 'var(--yb-warning)', error: 'var(--yb-danger)' };

  /* ================= 状态 ================= */
  /* 响应式状态(Vue.reactive, 组件直读自动刷新; 无 Vue 环境退化为普通对象)。
   * store 只存原始值(time 为毫秒时间戳), 规避 reactive 代理 Date 原生方法的兼容风险。 */
  const canReactive = typeof Vue !== 'undefined' && typeof Vue.reactive === 'function';
  const store = canReactive
    ? Vue.reactive({ status: (HIS.getUser() && HIS.getToken()) ? 'connecting' : 'disconnected', notifications: [], unreadCount: 0 })
    : { status: 'disconnected', notifications: [], unreadCount: 0 };
  const conn = { want: false, disabled: false, backoff: 0, timer: null, abort: null, es: null };
  const handlers = {};   /* eventType -> [callback, ...] */
  let seq = 0;           /* 通知自增序号(拼唯一 id) */

  /* ================= 工具 ================= */
  function metaOf(type) { return EVENT_TYPES[type] || { label: '病历实时消息', kind: 'info' }; }
  function setStatus(s) { store.status = s; }
  function recountUnread() {
    let n = 0;
    store.notifications.forEach(function (x) { if (!x.read) { n++; } });
    store.unreadCount = n;
  }
  function pad2(v) { return (v < 10 ? '0' : '') + v; }
  /* 相对时间: 刚刚 / X分钟前 / X小时前 / 昨天 HH:mm / MM-dd HH:mm */
  function fmtRelative(ts) {
    const d = new Date(Number(ts));
    if (!ts || isNaN(d.getTime())) { return ''; }
    const diff = Math.max(0, Date.now() - d.getTime());
    if (diff < 60000) { return '刚刚'; }
    if (diff < 3600000) { return Math.floor(diff / 60000) + '分钟前'; }
    const today = new Date(); today.setHours(0, 0, 0, 0);
    if (d.getTime() >= today.getTime()) { return Math.floor(diff / 3600000) + '小时前'; }
    const hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    if (d.getTime() >= today.getTime() - 86400000) { return '昨天 ' + hm; }
    return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + hm;
  }

  /* ================= SSE 帧解析(fetch 流式消费) =================
   * 事件块以空行分隔; 行首 ':' 为注释(心跳/首帧 connected 均为注释行, 整块忽略);
   * 多行 data 以 '\n' 拼接(兼容 JSON 内换行被拆行写出的场景)。 */
  function parseEventBlock(block) {
    let name = '';
    const dataLines = [];
    block.split('\n').forEach(function (line) {
      if (!line || line.charAt(0) === ':') { return; }
      const ci = line.indexOf(':');
      const field = ci < 0 ? line : line.slice(0, ci);
      let val = ci < 0 ? '' : line.slice(ci + 1);
      if (val.charAt(0) === ' ') { val = val.slice(1); }
      if (field === 'event') { name = val; } else if (field === 'data') { dataLines.push(val); }
    });
    if (!name && !dataLines.length) { return null; }
    return { name: name, data: dataLines.join('\n') };
  }
  function createStreamParser(onEvent) {
    let buffer = '';
    return function feed(text) {
      buffer = (buffer + text).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {      /* 末块可能残缺, 留待下个分片补全 */
        const block = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const evt = parseEventBlock(block);
        if (evt) { onEvent(evt); }
      }
    };
  }

  /* ================= 多标签页协同(单用户单连接约束) ================= */
  let relay = null;
  let leaderTimer = null;
  try {
    if (typeof BroadcastChannel !== 'undefined') { relay = new BroadcastChannel('his_emr_sse'); }
  } catch (e) { relay = null; }
  if (relay) {
    /* 中继仅来自其他标签页(BroadcastChannel 不回投发送方), 收到即本地分发且不再转发 */
    relay.onmessage = function (ev) {
      const msg = ev && ev.data;
      if (msg && msg.tabId !== TAB_ID && msg.envelope) { dispatchEnvelope(msg.envelope, true); }
    };
  }
  function relayEnvelope(env) {
    if (relay) { try { relay.postMessage({ tabId: TAB_ID, envelope: env }); } catch (e) { /* 通道关闭放弃中继 */ } }
  }
  function readLeader() {
    try {
      const v = JSON.parse(localStorage.getItem(LEADER_KEY) || 'null');
      return v && v.tabId && v.ts ? v : null;
    } catch (e) { return null; }
  }
  function isLeaderFree() {
    const l = readLeader();
    return !l || l.tabId === TAB_ID || (Date.now() - l.ts > LEADER_TTL_MS);
  }
  function claimLeader() {
    try { localStorage.setItem(LEADER_KEY, JSON.stringify({ tabId: TAB_ID, ts: Date.now() })); } catch (e) { /* 私有模式等场景退化为各标签直连 */ }
  }
  function startLeaderKeepalive() {
    stopLeaderKeepalive();
    leaderTimer = setInterval(function () {
      const l = readLeader();
      if (!l || l.tabId === TAB_ID) { claimLeader(); }
    }, 3000);
  }
  function stopLeaderKeepalive() {
    if (leaderTimer) { clearInterval(leaderTimer); leaderTimer = null; }
  }
  function releaseLeader() {
    stopLeaderKeepalive();
    const l = readLeader();
    if (l && l.tabId === TAB_ID) { try { localStorage.removeItem(LEADER_KEY); } catch (e) { /* noop */ } }
  }

  /* ================= 连接管理 ================= */
  function markConnected() {
    conn.backoff = 0;                    /* 成功即重置退避 */
    setStatus('connected');
  }
  /* 指数退避: 1s → 2s → 4s → 8s → 16s → 30s 封顶(此后恒 30s) */
  function scheduleReconnect() {
    const delay = conn.backoff > 0 ? Math.min(conn.backoff * 2, BACKOFF_MAX_MS) : BACKOFF_BASE_MS;
    conn.backoff = delay;
    setStatus('reconnecting');
    conn.timer = setTimeout(attempt, delay);
  }
  function enterStandby() {
    stopLeaderKeepalive();
    setStatus('standby');
    if (conn.timer) { clearTimeout(conn.timer); }
    conn.timer = setTimeout(attempt, STANDBY_POLL_MS);
  }
  function disableClient(reason) {
    conn.disabled = true;
    conn.want = false;
    if (conn.timer) { clearTimeout(conn.timer); conn.timer = null; }
    stopLeaderKeepalive();
    releaseLeader();
    setStatus('disabled');
    if (window.console) { console.warn('[EmrSseClient] 实时通知已停用: ' + reason); }
  }
  function onConnectionFailure(err) {
    if (!conn.want) { setStatus('disconnected'); return; }
    const code = err && err.status;
    if (code === 401) {
      /* 登录态失效: 与 api.js 同语义(logout 会经包装顺带断开本连接, 再统一提示重载) */
      try { if (typeof HIS.logout === 'function') { HIS.logout(); } } catch (e) { /* noop */ }
      if (typeof HIS.onUnauthorized === 'function') { HIS.onUnauthorized(); }
      return;
    }
    if (code === 403 || code === 404 || code === 405) {
      disableClient('SSE 端点不可用(HTTP ' + code + ')');
      return;
    }
    scheduleReconnect();                 /* 网络中断/5xx/流异常 → 退避重连 */
  }
  function onStreamEnd() {
    /* 流正常结束: 服务端 5 分钟超时主动 complete, 或连接被同用户新连接(其他标签页)替换 */
    if (!conn.want) { setStatus('disconnected'); return; }
    const l = readLeader();
    if (l && l.tabId !== TAB_ID && (Date.now() - l.ts) <= LEADER_TTL_MS) { enterStandby(); return; }
    scheduleReconnect();
  }
  function attempt() {
    if (conn.timer) { clearTimeout(conn.timer); conn.timer = null; }
    if (!conn.want || conn.disabled) { return; }
    if (store.status === 'connected' || store.status === 'connecting') { return; }
    if (!isLeaderFree()) { enterStandby(); return; }
    claimLeader();
    startLeaderKeepalive();
    setStatus('connecting');
    if (HIS.getToken()) { connectViaFetch(); }
    else if (typeof EventSource !== 'undefined') { connectViaEventSource(); }
    else { disableClient('当前环境不支持实时连接(fetch 流式/EventSource 均不可用)'); }
  }
  /* 有令牌主通道: fetch + ReadableStream 逐分片解析(原生 EventSource 带不了鉴权头) */
  function connectViaFetch() {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    conn.abort = ctrl;
    const headers = { 'Accept': 'text/event-stream' };
    const token = HIS.getToken();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    fetch(SSE_URL, { method: 'GET', headers: headers, cache: 'no-store', credentials: 'same-origin', signal: ctrl ? ctrl.signal : undefined })
      .then(function (resp) {
        const ct = resp.headers.get('Content-Type') || '';
        if (!resp.ok || ct.indexOf('text/event-stream') < 0) {
          /* 全局异常处理器对 BizException 返 HTTP 200 + R JSON 信封: 拆信封取业务码再判失败 */
          return resp.json().catch(function () { return null; }).then(function (j) {
            const e = new Error((j && j.msg) || 'SSE 连接失败(HTTP ' + resp.status + ')');
            e.status = (j && j.code) || resp.status;
            throw e;
          });
        }
        if (!resp.body || typeof resp.body.getReader !== 'function') {
          disableClient('当前环境不支持流式读取(ReadableStream 不可用)');
          return null;
        }
        markConnected();
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        const feed = createStreamParser(handleSseEvent);
        (function pump() {
          return reader.read().then(function (r) {
            if (r.done) { onStreamEnd(); return; }
            feed(decoder.decode(r.value, { stream: true }));
            return pump();
          });
        })();
        return null;
      })
      .catch(function (err) {
        if (!conn.want) { return; }
        if (ctrl && ctrl.signal && ctrl.signal.aborted) { return; }  /* 主动断开, 静默 */
        onConnectionFailure(err);
      });
  }
  /* 无令牌降级通道(Cookie 会话部署): 浏览器自动重连, CLOSED 后才接管自管退避 */
  function connectViaEventSource() {
    let es = null;
    try { es = new EventSource(SSE_URL); } catch (e) { disableClient('EventSource 创建失败: ' + (e && e.message)); return; }
    conn.es = es;
    es.onopen = function () { markConnected(); };
    es.onerror = function () {
      if (!conn.want) { return; }
      if (es.readyState === EventSource.CLOSED) { scheduleReconnect(); }
      else { setStatus('reconnecting'); }   /* CONNECTING: 浏览器内置重连中 */
    };
    Object.keys(EVENT_TYPES).forEach(function (name) {
      es.addEventListener(name, function (ev) { handleSseEvent({ name: name, data: ev && ev.data }); });
    });
  }

  /* ================= 事件分发 ================= */
  function handleSseEvent(evt) {
    let env = null;
    if (evt.data) { try { env = JSON.parse(evt.data); } catch (e) { env = null; } }
    if (!env || typeof env !== 'object') { env = { type: evt.name, displayName: '', data: {}, timestamp: Date.now() }; }
    if (!env.type) { env.type = evt.name || 'MESSAGE'; }
    dispatchEnvelope(env, false);
  }
  /* 分发三通道: 通知列表(响应式) + ElNotification 弹出 + 订阅回调; 连接页再向其他标签页中继 */
  function dispatchEnvelope(env, fromRelay) {
    const meta = metaOf(env.type);
    const data = env.data && typeof env.data === 'object' ? env.data : {};
    addNotification({
      id: 'n' + (++seq) + '-' + (env.timestamp || Date.now()),
      type: env.type,
      title: env.displayName || meta.label,
      message: data.message || data.msg || '',
      data: data,
      time: Number(env.timestamp) || Date.now(),   /* 后端 Long→String 序列化, 统一数字化 */
      read: false
    });
    if (!document.hidden) { showToast(env.displayName || meta.label, data, meta); }
    fireHandlers(env.type, env);
    fireHandlers('*', env);
    if (!fromRelay) { relayEnvelope(env); }
  }
  function addNotification(n) {
    store.notifications.unshift(n);                                   /* 就地变更, 外部引用保持有效 */
    if (store.notifications.length > MAX_NOTIFICATIONS) { store.notifications.splice(MAX_NOTIFICATIONS); }
    recountUnread();
  }
  function fireHandlers(type, env) {
    (handlers[type] || []).slice().forEach(function (cb) {
      try { cb(env); } catch (e) { if (window.console) { console.error('[EmrSseClient] 事件回调异常', e); } }
    });
  }
  /* 弹出策略: error 不自动关闭(须人工确认), warning 8s, info 5s; 均右上角 */
  function showToast(title, data, meta) {
    if (!window.ElementPlus || !ElementPlus.ElNotification) { return; }
    const kind = meta.kind || 'info';
    ElementPlus.ElNotification({
      title: title,
      message: data.message || data.msg || '',
      type: kind,
      duration: kind === 'error' ? 0 : (kind === 'warning' ? 8000 : 5000),
      position: 'top-right'
    });
  }

  /* ================= 对外单例 ================= */
  const client = {
    SSE_URL: SSE_URL,
    EVENT_TYPES: EVENT_TYPES,
    _handlers: handlers,
    _notifications: store.notifications,   /* 响应式数组引用(实现保证只就地变更) */
    _store: store,
    /* connect() — 建立连接(幂等: 已连接/连接中直接返回; 显式重连可重置停用位) */
    connect: function () {
      if (conn.want && (store.status === 'connected' || store.status === 'connecting')) { return true; }
      conn.disabled = false;
      conn.want = true;
      attempt();
      return true;
    },
    /* disconnect() — 主动断开并让出连接租约(其他标签页即时接管) */
    disconnect: function () {
      conn.want = false;
      if (conn.timer) { clearTimeout(conn.timer); conn.timer = null; }
      if (conn.abort) { try { conn.abort.abort(); } catch (e) { /* noop */ } conn.abort = null; }
      if (conn.es) { try { conn.es.close(); } catch (e) { /* noop */ } conn.es = null; }
      stopLeaderKeepalive();
      releaseLeader();
      setStatus('disconnected');
    },
    isConnected: function () { return store.status === 'connected'; },
    getStatus: function () { return store.status; },
    /* on(type, cb) — 订阅事件(支持 '*' 通配); 返回退订函数 */
    on: function (eventType, callback) {
      if (typeof eventType !== 'string' || typeof callback !== 'function') { return function () {}; }
      (handlers[eventType] = handlers[eventType] || []).push(callback);
      return function () { client.off(eventType, callback); };
    },
    off: function (eventType, callback) {
      handlers[eventType] = (handlers[eventType] || []).filter(function (x) { return x !== callback; });
    },
    getNotifications: function () { return store.notifications.slice(); },
    getUnreadCount: function () { return store.unreadCount; },
    markAsRead: function (id) {
      let hit = false;
      store.notifications.forEach(function (n) { if (n.id === id) { n.read = true; hit = true; } });
      if (hit) { recountUnread(); }
      return hit;
    },
    markAllRead: function () {
      store.notifications.forEach(function (n) { n.read = true; });
      store.unreadCount = 0;
    },
    clearAll: function () {
      store.notifications.length = 0;
      store.unreadCount = 0;
    }
  };
  HIS.EmrSseClient = client;

  /* ================= 样式注入(走设计令牌, 与 notification-bell 同语言) ================= */
  (function injectCss() {
    if (document.getElementById('emr-sse-client-css')) { return; }
    const st = document.createElement('style');
    st.id = 'emr-sse-client-css';
    st.textContent = [
      /* 触发器: 顶栏深底白色实时铃铛(带音波弧线, 与站内通知铃铛区分) */
      '.esb-bell { display:inline-block; position:relative; margin-right:16px; }',
      '.esb-trigger { position:relative; display:flex; align-items:center; justify-content:center; width:32px; height:32px; border-radius:var(--yb-r-pill); color:var(--yb-header-ink); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }',
      '.esb-trigger:hover { background:var(--yb-header-hover); }',
      '.esb-trigger .el-badge__content { font-size:10px; height:16px; line-height:14px; padding:0 4px; }',
      /* 连接状态点: 触发器右下角描边圆点(描边色近似顶栏渐变底形成"镂空"感), 在线绿点呼吸 */
      '.esb-dot { position:absolute; right:2px; bottom:2px; width:7px; height:7px; border-radius:50%; box-shadow:0 0 0 2px var(--yb-header-bg, #17406b); }',
      '.esb-dot--inline { position:static; box-shadow:none; }',
      '.esb-dot--ok { background:var(--yb-success); animation:esb-pulse 2.4s ease-out infinite; }',
      '.esb-dot--mid { background:var(--yb-warning); }',
      '.esb-dot--off { background:var(--yb-danger); }',
      '.esb-dot--dis { background:var(--yb-ink-4); }',
      '@keyframes esb-pulse { 0% { box-shadow:0 0 0 2px var(--yb-header-bg, #17406b), 0 0 0 2px rgba(60,134,45,.5); } 70% { box-shadow:0 0 0 2px var(--yb-header-bg, #17406b), 0 0 0 6px rgba(60,134,45,0); } 100% { box-shadow:0 0 0 2px var(--yb-header-bg, #17406b), 0 0 0 0 rgba(60,134,45,0); } }',
      '@media (prefers-reduced-motion: reduce) { .esb-dot--ok { animation:none; } }',
      /* 弹层面板 */
      '.esb-panel { display:flex; flex-direction:column; max-height:430px; }',
      '.esb-head { display:flex; align-items:center; gap:8px; padding-bottom:10px; border-bottom:1px solid var(--yb-border); font-weight:600; font-size:15px; color:var(--yb-ink-1); }',
      '.esb-head-sub { font-size:12px; font-weight:400; color:var(--yb-ink-3); font-variant-numeric:tabular-nums; }',
      '.esb-head-status { margin-left:auto; display:inline-flex; align-items:center; gap:5px; font-size:12px; font-weight:400; color:var(--yb-ink-3); }',
      '.esb-list { flex:1; overflow-y:auto; max-height:300px; }',
      '.esb-empty { text-align:center; padding:36px 0 28px; color:var(--yb-ink-3); font-size:13px; }',
      '.esb-empty-sub { margin-top:6px; font-size:12px; color:var(--yb-ink-4); }',
      '.esb-item { display:flex; align-items:flex-start; gap:8px; padding:10px 12px; border-bottom:1px solid var(--yb-border-light); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }',
      '.esb-item:hover { background:var(--yb-surface-2); }',
      '.esb-item.is-unread { background:var(--el-color-primary-light-9); }',
      '.esb-item.is-unread:hover { background:var(--el-color-primary-light-8); }',
      '.esb-item-ico { flex:none; margin-top:2px; }',
      '.esb-item-main { flex:1; min-width:0; }',
      '.esb-item-title { font-size:13px; line-height:1.4; color:var(--yb-ink-1); }',
      '.esb-item.is-unread .esb-item-title { font-weight:600; }',
      '.esb-item-msg { font-size:12px; color:var(--yb-ink-3); margin-top:2px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.esb-item-time { font-size:12px; color:var(--yb-ink-4); margin-top:2px; font-variant-numeric:tabular-nums; }',
      '.esb-dot-unread { width:6px; height:6px; border-radius:50%; background:var(--yb-brand); margin-top:6px; flex-shrink:0; }',
      '.esb-foot { display:flex; justify-content:space-between; align-items:center; padding-top:8px; border-top:1px solid var(--yb-border); }',
      /* 独立连接状态指示器(ConnectionStatus) */
      '.esb-statusdot { display:inline-flex; align-items:center; gap:5px; font-size:12px; color:var(--yb-ink-3); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 顶栏实时通知铃铛(响应式直读 store, 自动更新) ================= */
  const NotificationBell = {
    name: 'EmrSseBell',
    data: function () { return { popVisible: false }; },
    computed: {
      status: function () { return store.status; },
      statusMeta: function () { return STATUS_META[store.status] || STATUS_META.disconnected; },
      unread: function () { return store.unreadCount; },
      list: function () { return store.notifications; },
      triggerTitle: function () { return '病历实时通知 · ' + this.statusMeta.label; }
    },
    mounted: function () {
      /* 兜底连接: 载入时未登录、登录后才渲染主布局的场景(登录页不整页重载);
       * 挂载后 2s 连接, connect 幂等, 与载入自动连接并存不会重复建连 */
      setTimeout(function () {
        if (HIS.getUser() && HIS.getToken() && !conn.want && store.status !== 'disabled') { client.connect(); }
      }, AUTO_CONNECT_DELAY_MS);
    },
    methods: {
      kindOf: function (n) { return metaOf(n.type).kind; },
      iconOf: function (n) { return KIND_ICONS[this.kindOf(n)] || KIND_ICONS.info; },
      colorOf: function (n) { return KIND_COLORS[this.kindOf(n)] || 'var(--yb-brand)'; },
      fmtTime: fmtRelative,
      read: function (n) { client.markAsRead(n.id); },
      markAllRead: function () { client.markAllRead(); },
      clearAll: function () { client.clearAll(); }
    },
    template: `
<div class="esb-bell" v-if="status !== 'disabled'">
  <el-popover placement="bottom-end" :width="360" trigger="click" v-model:visible="popVisible">
    <template #reference>
      <div class="esb-trigger" role="button" tabindex="0" :title="triggerTitle" aria-label="病历实时通知">
        <el-badge :value="unread" :hidden="!unread" :max="99">
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" style="display:block;">
            <path fill="currentColor" d="M9.8 2.2a6.1 6.1 0 0 0-6.1 6.1v3l-1.25 2.5a1.05 1.05 0 0 0 .94 1.52h12.82a1.05 1.05 0 0 0 .94-1.52L15.9 11.3v-3A6.1 6.1 0 0 0 9.8 2.2z"></path>
            <path fill="currentColor" d="M7.6 17.1a2.2 2.2 0 0 0 4.4 0z"></path>
            <path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M19.6 5.4a2.8 2.8 0 0 1 0 5.2"></path>
            <path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M21.4 3.6a4.9 4.9 0 0 1 0 8.2"></path>
          </svg>
        </el-badge>
        <span class="esb-dot" :class="'esb-dot--' + statusMeta.dot" aria-hidden="true"></span>
      </div>
    </template>
    <div class="esb-panel">
      <div class="esb-head">
        <span>病历实时通知</span>
        <span v-if="unread" class="esb-head-sub">未读 {{ unread }} 条</span>
        <span class="esb-head-status" :title="statusMeta.hint">
          <span class="esb-dot esb-dot--inline" :class="'esb-dot--' + statusMeta.dot" aria-hidden="true"></span>
          {{ statusMeta.label }}
        </span>
      </div>
      <div class="esb-list">
        <div v-if="!list.length" class="esb-empty">
          暂无实时通知
          <div class="esb-empty-sub">{{ statusMeta.hint }}</div>
        </div>
        <div v-for="n in list" :key="n.id" class="esb-item" :class="{ 'is-unread': !n.read }" @click="read(n)">
          <svg class="esb-item-ico" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" :style="{ color: colorOf(n) }">
            <path fill="currentColor" fill-rule="evenodd" :d="iconOf(n)"></path>
          </svg>
          <div class="esb-item-main">
            <div class="esb-item-title">{{ n.title }}</div>
            <div v-if="n.message" class="esb-item-msg" :title="n.message">{{ n.message }}</div>
            <div class="esb-item-time">{{ fmtTime(n.time) }}</div>
          </div>
          <span v-if="!n.read" class="esb-dot-unread" aria-hidden="true"></span>
        </div>
      </div>
      <div class="esb-foot">
        <el-button link size="small" :disabled="!unread" @click="markAllRead">标记全部已读</el-button>
        <el-button link size="small" :disabled="!list.length" @click="clearAll">清除</el-button>
      </div>
    </div>
  </el-popover>
</div>`
  };
  NotificationBell.mount = function (el) {
    const app = Vue.createApp(NotificationBell);
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  /* ================= 独立连接状态指示器(可单独挂载复用) ================= */
  const ConnectionStatus = {
    name: 'EmrSseStatus',
    computed: {
      statusMeta: function () { return STATUS_META[store.status] || STATUS_META.disconnected; }
    },
    template: `
<span class="esb-statusdot" :title="statusMeta.hint">
  <span class="esb-dot esb-dot--inline" :class="'esb-dot--' + statusMeta.dot" aria-hidden="true"></span>
  <span>{{ statusMeta.label }}</span>
</span>`
  };
  ConnectionStatus.mount = function (el) {
    const app = Vue.createApp(ConnectionStatus);
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    return { app: app, vm: app.mount(typeof el === 'string' ? document.querySelector(el) : el) };
  };

  client.NotificationBell = NotificationBell;
  client.ConnectionStatus = ConnectionStatus;

  /* ================= 自动生命周期 ================= */
  /* 页面卸载: 断开并让出租约, 其他标签页即时接管 */
  window.addEventListener('beforeunload', function () { client.disconnect(); });
  /* 登出即断开: 包装 HIS.logout 透传原实现(防重复包装) */
  if (typeof HIS.logout === 'function' && !HIS.logout.__sseWrapped) {
    const origLogout = HIS.logout;
    const wrapped = function () {
      try { client.disconnect(); } catch (e) { /* noop */ }
      return origLogout.apply(HIS, arguments);
    };
    wrapped.__sseWrapped = true;
    HIS.logout = wrapped;
  }
  /* 载入即登录: 2s 后自动连接(再校验登录态, 防登出竞态); 登录页场景由铃铛挂载兜底 */
  if (HIS.getUser() && HIS.getToken()) {
    setTimeout(function () {
      if (HIS.getUser() && HIS.getToken() && !conn.want) { client.connect(); }
    }, AUTO_CONNECT_DELAY_MS);
  }
  /* 连接标签页让位(releaseLeader 触发 storage 事件): 待机标签页立即竞选接管 */
  window.addEventListener('storage', function (ev) {
    if (ev && ev.key === LEADER_KEY && conn.want && store.status === 'standby') { attempt(); }
  });
})();
