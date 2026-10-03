/*!
 * tiptap-loader.js - 懒加载入口(与项目无构建/vendor 本地化约定一致)
 * 用法: HIS 任意脚本中调用 TiptapLoader.ensure() → Promise<window.Tiptap>
 *   TiptapLoader.ensure().then(function (T) { new T.Editor({...}); });
 * - 首次调用时动态注入 <script src="tiptap-bundle.js?v=...">, 之后复用同一 Promise
 * - 失败时清空缓存 Promise, 允许下次重试
 * - bundle 版本变更时同步修改下方 TIPTAP_BUNDLE_VERSION 以击穿浏览器缓存
 */
(function (global) {
  'use strict';

  var TIPTAP_BUNDLE_VERSION = '20261002a';
  var BUNDLE_URL = '/vendor/tiptap/tiptap-bundle.js?v=' + TIPTAP_BUNDLE_VERSION;

  var pending = null;

  function inject() {
    return new Promise(function (resolve, reject) {
      if (global.Tiptap) { resolve(global.Tiptap); return; }
      var s = document.createElement('script');
      s.src = BUNDLE_URL;
      s.async = true;
      s.onload = function () {
        if (global.Tiptap) {
          resolve(global.Tiptap);
        } else {
          reject(new Error('tiptap-bundle.js loaded but window.Tiptap missing'));
        }
      };
      s.onerror = function () {
        reject(new Error('failed to load ' + BUNDLE_URL));
      };
      document.head.appendChild(s);
    });
  }

  global.TiptapLoader = {
    /** 加载(或复用已加载的) Tiptap, 返回 Promise<window.Tiptap> */
    ensure: function () {
      if (global.Tiptap) { return Promise.resolve(global.Tiptap); }
      if (!pending) {
        pending = inject().catch(function (err) {
          pending = null; // 允许重试
          throw err;
        });
      }
      return pending;
    },
    /** bundle 是否已就绪(同步判断) */
    ready: function () {
      return !!global.Tiptap;
    },
    bundleUrl: BUNDLE_URL,
  };
})(window);
