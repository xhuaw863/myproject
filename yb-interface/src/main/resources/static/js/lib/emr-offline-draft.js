/* ==================================================================
 * emr-offline-draft.js — EMR 离线草稿(IndexedDB 自动保存)
 * ------------------------------------------------------------------
 * 依赖: 无(独立 IIFE; 运行时接收 HIS.EmrEditor.createEditor 的 wrapper 对象)。
 * 提供: HIS.EmrOfflineDraft — 浏览器本地离线草稿存储与编辑器自动保存。
 *   init()                                 打开库并清理过期草稿 → Promise<是否可用>
 *   saveDraft(key, json, meta)             保存草稿(配额不足时先清理最旧草稿再重试一次)
 *   loadDraft(key)                         读取草稿完整对象(含 documentJson)或 null
 *   deleteDraft(key)                       删除草稿
 *   listDrafts()                           列出全部草稿元数据(不含 documentJson), 按时间倒序
 *   hasDraft(key)                          快速存在性检查
 *   startAutoSave(key, wrapper, meta, ms)  定时自动保存 + 切后台兜底 + 未备份离开提醒
 *   stopAutoSave(key)                      停止单个 key 的自动保存(清定时器/事件/提醒)
 *   stopAll()                              停止全部自动保存(路由切换/编辑器销毁时调用)
 *   getConflictInfo(key, serverVersion)    本地草稿与服务器版本的冲突判定
 * 说明:
 *   - draftKey 由调用方定义(如 record_{id} / template_{id}), 本模块不规定格式;
 *   - 变更检测: wrapper.toJSON() 序列化后取 djb2 哈希, 与上次保存哈希一致则跳过写入;
 *   - IndexedDB 不可用(隐私模式)或配额不足时静默降级, 仅 console.warn, 绝不抛出阻断编辑器;
 *   - beforeunload 仅在存在未备份到本地的改动时挂载, 保存成功/停止自动保存后自动卸载。
 * 注册: HIS.EmrOfflineDraft(须在 app.js 之前加载)。
 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});

  /* ================= 常量 ================= */
  const DB_NAME = 'emr_drafts';
  const DB_VERSION = 1;
  const STORE_NAME = 'drafts';
  const AUTO_SAVE_INTERVAL = 30000;                 /* 自动保存间隔(ms) */
  const MIN_SAVE_INTERVAL = 5000;                   /* 间隔下限(防误传 0/负数导致高频空转) */
  const MAX_DRAFT_AGE = 7 * 24 * 60 * 60 * 1000;    /* 草稿最长保留 7 天 */
  const QUOTA_PURGE_COUNT = 3;                      /* 配额不足时先清理的最旧草稿条数 */

  let _dbPromise = null;                            /* 库连接单例(失败后置空允许重试) */

  /* ================= 通用工具 ================= */
  function warn(msg) {
    try { console.warn('[EmrOfflineDraft] ' + msg); } catch (e) { /* noop */ }
  }
  function info(msg) {
    try { console.info('[EmrOfflineDraft] ' + msg); } catch (e) { /* noop */ }
  }
  /* djb2 字符串哈希(拼接源串长度降低碰撞), 用于低成本变更检测, 避免深比较 */
  function hashStr(str) {
    const s = String(str == null ? '' : str);
    let h = 5381;
    for (let i = 0; i < s.length; i++) { h = ((h << 5) + h + s.charCodeAt(i)) | 0; }
    return (h >>> 0).toString(36) + '_' + s.length.toString(36);
  }
  function isQuotaError(e) {
    return !!(e && (e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED' ||
      /quota/i.test(e.message || '')));
  }

  /* ================= IndexedDB 低层封装(Promise 化) ================= */
  /* openDB() — 惰性打开库 v1: 单对象仓 drafts[keyPath=draftKey] + savedAt 索引; 失败后允许重试 */
  function openDB() {
    if (_dbPromise) { return _dbPromise; }
    _dbPromise = new Promise(function (resolve, reject) {
      let req;
      if (!window.indexedDB) { reject(new Error('当前浏览器不支持 IndexedDB')); return; }
      try { req = window.indexedDB.open(DB_NAME, DB_VERSION); }
      catch (e) { reject(e); return; }
      req.onupgradeneeded = function (ev) {
        const db = ev.target.result;
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          const store = db.createObjectStore(STORE_NAME, { keyPath: 'draftKey' });
          store.createIndex('savedAt', 'savedAt', { unique: false });   /* 过期清理/配额淘汰走索引游标, 不加载文档体 */
        }
      };
      req.onsuccess = function () {
        const db = req.result;
        db.onversionchange = function () {                        /* 其他标签页升级版本: 让位关闭 */
          try { db.close(); } catch (e) { /* noop */ }
          _dbPromise = null;
        };
        resolve(db);
      };
      req.onerror = function () { reject(req.error || new Error('IndexedDB 打开失败')); };
      /* onblocked: 其他标签页占用旧版本时等待其关闭, 不视为失败 */
    });
    _dbPromise.catch(function () { _dbPromise = null; });          /* 失败后允许下次重试 */
    return _dbPromise;
  }
  /* 只读请求包装: fn(store) 返回 IDBRequest */
  function idbReq(fn) {
    return openDB().then(function (db) {
      return new Promise(function (resolve, reject) {
        const req = fn(db.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME));
        req.onsuccess = function () { resolve(req.result); };
        req.onerror = function () { reject(req.error || new Error('IndexedDB 读取失败')); };
      });
    });
  }
  /* 读写事务包装: fn(store) 在事务内发出写请求, oncomplete 即落盘成功 */
  function idbTx(fn) {
    return openDB().then(function (db) {
      return new Promise(function (resolve, reject) {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        fn(tx.objectStore(STORE_NAME));
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { reject(tx.error || new Error('IndexedDB 写入失败')); };
        tx.onabort = function () { reject(tx.error || new Error('IndexedDB 写入中止')); };
      });
    });
  }
  /* 按 savedAt 索引 keyCursor 批量删除: range=null 时升序(最旧在前), limit=0 表示不限量; 返回删除条数 */
  function idbPurge(range, limit) {
    return openDB().then(function (db) {
      return new Promise(function (resolve, reject) {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        let req;
        try { req = tx.objectStore(STORE_NAME).index('savedAt').openKeyCursor(range || null); }
        catch (e) { resolve(0); return; }                          /* 索引缺失等情况: 跳过清理不报错 */
        let removed = 0;
        req.onsuccess = function () {
          const cur = req.result;
          if (!cur || (limit > 0 && removed >= limit)) { return; }
          cur.delete();
          removed++;
          cur.continue();
        };
        tx.oncomplete = function () { resolve(removed); };
        tx.onerror = function () { reject(tx.error || new Error('草稿清理失败')); };
        tx.onabort = function () { reject(tx.error || new Error('草稿清理中止')); };
      });
    });
  }
  function idbGet(key) { return idbReq(function (s) { return s.get(key); }); }
  function idbCount(key) { return idbReq(function (s) { return s.count(key); }); }
  function idbGetAll() { return idbReq(function (s) { return s.getAll(); }); }
  function idbPut(record) { return idbTx(function (s) { s.put(record); }); }
  function idbDelete(key) { return idbTx(function (s) { s.delete(key); }); }
  function idbDeleteByAge(cutoff) {
    if (typeof IDBKeyRange === 'undefined') { return Promise.resolve(0); }
    return idbPurge(IDBKeyRange.upperBound(cutoff), 0);            /* savedAt <= cutoff */
  }
  function idbDeleteOldest(count) { return idbPurge(null, count); }

  /* ================= beforeunload 守卫(仅存在未备份改动时挂载) ================= */
  function onBeforeUnload(e) {
    let dirty = false;
    try { dirty = Object.keys(EmrOfflineDraft._dirty).some(function (k) { return EmrOfflineDraft._dirty[k]; }); }
    catch (err) { /* noop */ }
    if (!dirty) { return; }
    e.preventDefault();
    e.returnValue = '您有未保存的病历内容，确定离开吗？';
    return e.returnValue;
  }
  function syncUnloadGuard() {
    const any = Object.keys(EmrOfflineDraft._dirty).some(function (k) { return EmrOfflineDraft._dirty[k]; });
    if (any && !EmrOfflineDraft._unloadBound) {
      window.addEventListener('beforeunload', onBeforeUnload);
      EmrOfflineDraft._unloadBound = true;
    } else if (!any && EmrOfflineDraft._unloadBound) {
      window.removeEventListener('beforeunload', onBeforeUnload);
      EmrOfflineDraft._unloadBound = false;
    }
  }

  /* ================= 主模块 ================= */
  const EmrOfflineDraft = {
    _db: null,
    _initPromise: null,
    _disabled: false,          /* IndexedDB 不可用: 全部操作静默降级 */
    _autoSaveTimers: {},       /* draftKey -> intervalId */
    _sessions: {},             /* draftKey -> { wrapper, metadata, unsub, boundVis } */
    _lastHashes: {},           /* draftKey -> 最近保存内容哈希 */
    _dirty: {},                /* draftKey -> 存在未备份改动 */
    _saving: {},               /* draftKey -> 保存进行中(防 tick 重入) */
    _unloadBound: false,

    /* ---- init() — 打开库 + 清理过期草稿; 返回 Promise<boolean> 是否可用(不抛出) ---- */
    init: function () {
      const self = this;
      if (this._initPromise) { return this._initPromise; }
      this._initPromise = openDB().then(function (db) {
        self._db = db;
        return self._cleanup();
      }).then(function (removed) {
        if (removed > 0) { info('已清理过期草稿 ' + removed + ' 条'); }
        return true;
      }).catch(function (e) {
        self._disabled = true;
        warn('IndexedDB 不可用, 离线草稿功能已禁用: ' + (e && e.message));
        return false;
      });
      return this._initPromise;
    },

    /* ---- saveDraft(key, documentJson, metadata) — 保存草稿(永不抛出) ----
     * metadata: { staffId, patientName, recordType, version }
     * staffId 建议传字符串(雪花ID数字形态存在精度丢失风险); documentJson 为 JSON 串或对象 */
    saveDraft: function (draftKey, documentJson, metadata) {
      const self = this;
      if (this._disabled || !draftKey) { return Promise.resolve(false); }
      let jsonStr;
      try { jsonStr = typeof documentJson === 'string' ? documentJson : JSON.stringify(documentJson); }
      catch (e) { jsonStr = null; }
      if (!jsonStr) { return Promise.resolve(false); }
      const meta = metadata || {};
      const record = {
        draftKey: String(draftKey),
        documentJson: jsonStr,
        savedAt: Date.now(),
        staffId: meta.staffId == null ? null : String(meta.staffId),
        patientName: meta.patientName || '',
        recordType: meta.recordType || '',
        version: meta.version == null ? null : meta.version
      };
      return idbPut(record).then(function () { self._afterSaved(record); return true; })
        .catch(function (e) {
          if (!isQuotaError(e)) { warn('保存草稿失败(' + record.draftKey + '): ' + (e && e.message)); return false; }
          /* 配额不足: 先淘汰最旧草稿, 再重试一次 */
          return idbDeleteOldest(QUOTA_PURGE_COUNT).then(function () { return idbPut(record); })
            .then(function () { self._afterSaved(record); return true; })
            .catch(function (e2) { warn('保存草稿仍失败(空间不足): ' + (e2 && e2.message)); return false; });
        });
    },
    _afterSaved: function (record) { this._lastHashes[record.draftKey] = hashStr(record.documentJson); },
    /* ---- loadDraft(key) — 返回完整草稿对象(含 documentJson 字符串)或 null ---- */
    loadDraft: function (draftKey) {
      if (this._disabled || !draftKey) { return Promise.resolve(null); }
      return idbGet(String(draftKey)).then(function (d) { return d || null; })
        .catch(function (e) { warn('读取草稿失败(' + draftKey + '): ' + (e && e.message)); return null; });
    },

    /* ---- deleteDraft(key) — 删除草稿, 返回 Promise<boolean> ---- */
    deleteDraft: function (draftKey) {
      const self = this;
      if (this._disabled || !draftKey) { return Promise.resolve(false); }
      const key = String(draftKey);
      return idbDelete(key).then(function () {
        delete self._lastHashes[key];
        return true;
      }).catch(function (e) { warn('删除草稿失败(' + key + '): ' + (e && e.message)); return false; });
    },

    /* ---- listDrafts() — 全部草稿元数据(不含 documentJson), 按保存时间倒序 ---- */
    listDrafts: function () {
      if (this._disabled) { return Promise.resolve([]); }
      return idbGetAll().then(function (rows) {
        return (rows || []).map(function (d) {
          return {
            draftKey: d.draftKey,
            savedAt: d.savedAt || 0,
            staffId: d.staffId == null ? null : d.staffId,
            patientName: d.patientName || '',
            recordType: d.recordType || '',
            version: d.version == null ? null : d.version,
            size: typeof d.documentJson === 'string' ? d.documentJson.length : 0    /* 序列化字节近似值 */
          };
        }).sort(function (a, b) { return b.savedAt - a.savedAt; });
      }).catch(function (e) { warn('枚举草稿失败: ' + (e && e.message)); return []; });
    },

    /* ---- hasDraft(key) — 快速存在性检查 ---- */
    hasDraft: function (draftKey) {
      if (this._disabled || !draftKey) { return Promise.resolve(false); }
      return idbCount(String(draftKey)).then(function (n) { return (n || 0) > 0; })
        .catch(function () { return false; });
    },

    /* ---- startAutoSave(key, editorWrapper, metadata, intervalMs) — 定时自动保存 ---- */
    startAutoSave: function (draftKey, editorWrapper, metadata, intervalMs) {
      const self = this;
      if (this._disabled || !draftKey || !editorWrapper) { return false; }
      this.stopAutoSave(draftKey);                                     /* 同 key 重复启动: 先清理旧会话 */
      const iv = Math.max(MIN_SAVE_INTERVAL, Number(intervalMs) || AUTO_SAVE_INTERVAL);
      const session = { wrapper: editorWrapper, metadata: metadata || {}, unsub: null, boundVis: null };
      /* 基线: 记录打开时内容哈希, 未编辑不产生草稿; 编辑后由 _tick 比对落库 */
      const base = this._jsonOfWrapper(editorWrapper);
      if (base != null) { this._lastHashes[draftKey] = hashStr(base); }
      this._sessions[draftKey] = session;
      this._autoSaveTimers[draftKey] = setInterval(function () { self._tick(draftKey); }, iv);
      /* 切后台(visibilitychange hidden)立即兜底保存, 不等下一个定时周期 */
      session.boundVis = function () {
        if (document.visibilityState === 'hidden') { self._tick(draftKey); }
      };
      document.addEventListener('visibilitychange', session.boundVis);
      /* 编辑器每次更新即标脏并挂离开提醒, 保存成功后自动清除 */
      if (typeof editorWrapper.on === 'function') {
        session.unsub = editorWrapper.on('update', function () { self._markDirty(draftKey); });
      }
      return true;
    },

    /* ---- stopAutoSave(key) — 清定时器/事件/离开提醒 ---- */
    stopAutoSave: function (draftKey) {
      const session = this._sessions[draftKey];
      if (session) {
        if (session.unsub) { try { session.unsub(); } catch (e) { /* noop */ } }
        if (session.boundVis) { document.removeEventListener('visibilitychange', session.boundVis); }
        delete this._sessions[draftKey];
      }
      if (this._autoSaveTimers[draftKey] != null) {
        clearInterval(this._autoSaveTimers[draftKey]);
        delete this._autoSaveTimers[draftKey];
      }
      delete this._saving[draftKey];
      this._markClean(draftKey);
    },

    /* ---- stopAll() — 停止全部自动保存(路由切换/编辑器销毁时调用) ---- */
    stopAll: function () {
      const self = this;
      Object.keys(this._sessions).forEach(function (k) { self.stopAutoSave(k); });
      Object.keys(this._autoSaveTimers).forEach(function (k) {          /* 兜底: 无会话的遗留定时器 */
        clearInterval(self._autoSaveTimers[k]);
        delete self._autoSaveTimers[k];
      });
    },

    /* ---- _tick — 自动保存单次节拍: 内容有变化才写入(哈希比对) ---- */
    _tick: function (draftKey) {
      const self = this;
      const session = this._sessions[draftKey];
      if (!session || !session.wrapper || this._saving[draftKey]) { return Promise.resolve(false); }
      const jsonStr = this._jsonOfWrapper(session.wrapper);
      if (jsonStr == null) { return Promise.resolve(false); }
      if (hashStr(jsonStr) === this._lastHashes[draftKey]) {
        this._markClean(draftKey);                                    /* 无变化(含撤销回原样): 恢复干净态 */
        return Promise.resolve(false);
      }
      this._markDirty(draftKey);
      this._saving[draftKey] = true;
      return this.saveDraft(draftKey, jsonStr, session.metadata).then(function (ok) {
        delete self._saving[draftKey];
        if (ok) { self._refreshDirty(draftKey); }                     /* 处理"保存期间仍在输入"的竞态 */
        return ok;
      }, function () {
        delete self._saving[draftKey];
        return false;
      });
    },

    /* 保存落库后重比编辑器当前内容: 一致才灭脏, 否则保持未备份提醒 */
    _refreshDirty: function (draftKey) {
      const session = this._sessions[draftKey];
      if (!session || !session.wrapper) { return; }
      const jsonStr = this._jsonOfWrapper(session.wrapper);
      if (jsonStr != null && hashStr(jsonStr) === this._lastHashes[draftKey]) {
        this._markClean(draftKey);
      }
    },

    _markDirty: function (draftKey) {
      this._dirty[draftKey] = true;
      syncUnloadGuard();
    },
    _markClean: function (draftKey) {
      delete this._dirty[draftKey];
      syncUnloadGuard();
    },

    /* ---- getConflictInfo(key, serverVersion) — 本地草稿与服务器版本冲突判定 ----
     * 返回 { exists, hasConflict, draftTime, serverVersion, draftVersion, draft };
     * hasConflict=true: 草稿保存时的版本(meta.version)与服务器当前版本不一致, 恢复前需人工确认 */
    getConflictInfo: function (draftKey, serverVersion) {
      const serverV = serverVersion == null ? null : String(serverVersion);
      return this.loadDraft(draftKey).then(function (d) {
        if (!d) { return { exists: false, hasConflict: false, draft: null, draftTime: null, serverVersion: serverV, draftVersion: null }; }
        const localV = d.version == null ? null : String(d.version);
        return {
          exists: true,
          hasConflict: !!(localV != null && serverV != null && localV !== serverV),
          draft: d,
          draftTime: d.savedAt || 0,
          serverVersion: serverV,
          draftVersion: localV
        };
      });
    },

    /* ---- _hash(str) — 变更检测哈希(暴露供宿主排查) ---- */
    _hash: function (str) { return hashStr(str); },

    /* ---- _cleanup() — 删除超过 MAX_DRAFT_AGE 的草稿, 返回 Promise<删除条数> ---- */
    _cleanup: function () {
      if (this._disabled) { return Promise.resolve(0); }
      return idbDeleteByAge(Date.now() - MAX_DRAFT_AGE).catch(function (e) {
        warn('过期草稿清理失败: ' + (e && e.message));
        return 0;
      });
    },

    /* ---- _jsonOfWrapper(wrapper) — wrapper.toJSON() → JSON 串; 失败返回 null ---- */
    _jsonOfWrapper: function (wrapper) {
      try {
        if (!wrapper || typeof wrapper.toJSON !== 'function') { return null; }
        return JSON.stringify(wrapper.toJSON());
      } catch (e) { warn('序列化编辑器内容失败: ' + (e && e.message)); return null; }
    }
  };

  HIS.EmrOfflineDraft = EmrOfflineDraft;
})();
