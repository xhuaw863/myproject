/* 医保原生HIS - 前端基础库: 令牌管理 + HTTP封装 + 通用常量 */
(function () {
  var HIS = (window.HIS = window.HIS || {});

  var TOKEN_KEY = 'yb_his_token';
  var USER_KEY = 'yb_his_user';

  /* ===== 令牌 / 登录用户 ===== */
  HIS.getToken = function () { return localStorage.getItem(TOKEN_KEY); };
  HIS.setToken = function (t) {
    if (t) { localStorage.setItem(TOKEN_KEY, t); } else { localStorage.removeItem(TOKEN_KEY); }
  };
  HIS.getUser = function () {
    try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch (e) { return null; }
  };
  HIS.setUser = function (u) {
    if (u) { localStorage.setItem(USER_KEY, JSON.stringify(u)); } else { localStorage.removeItem(USER_KEY); }
  };
  HIS.logout = function () { HIS.setToken(null); HIS.setUser(null); };

  /* ===== 角色字典 ===== */
  HIS.ROLES = [
    { value: 'ADMIN', label: '系统管理员' },
    { value: 'REGISTRAR', label: '挂号员' },
    { value: 'DOCTOR', label: '医生' },
    { value: 'PHARMACIST', label: '药师' },
    { value: 'CASHIER', label: '收费员' },
    { value: 'NURSE', label: '护士' }
  ];
  HIS.roleLabel = function (code) {
    for (var i = 0; i < HIS.ROLES.length; i++) {
      if (HIS.ROLES[i].value === code) { return HIS.ROLES[i].label; }
    }
    return code || '-';
  };

  /* ===== HTTP 封装 =====
   * 后端统一响应体 R{code,msg,data}: code=0 成功; code=401 未登录。
   * 成功返回 data; 失败抛出 Error(msg)。
   */
  HIS.request = function (method, url, body) {
    var headers = { 'Content-Type': 'application/json' };
    var token = HIS.getToken();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    return fetch(url, {
      method: method,
      headers: headers,
      body: body == null ? undefined : JSON.stringify(body)
    }).then(function (resp) {
      return resp.json().catch(function () { throw new Error('响应解析失败(HTTP ' + resp.status + ')'); });
    }).then(function (data) {
      if (data && data.code === 401) {
        HIS.logout();
        if (typeof HIS.onUnauthorized === 'function') { HIS.onUnauthorized(); }
        throw new Error(data.msg || '未登录或登录已过期');
      }
      if (!data || data.code !== 0) {
        throw new Error((data && data.msg) || '请求失败');
      }
      return data.data;
    });
  };
  HIS.get = function (url) { return HIS.request('GET', url); };
  HIS.post = function (url, body) { return HIS.request('POST', url, body); };
  HIS.put = function (url, body) { return HIS.request('PUT', url, body); };
  HIS.del = function (url) { return HIS.request('DELETE', url); };

  /* 原始请求: 附带令牌, 原样返回解析后的 JSON(不校验 code 信封)。
   * 用于返回 YbResponse/Map 等非 R 结构的旧接口(如字典下载)。
   */
  HIS.raw = function (method, url, body) {
    var headers = { 'Content-Type': 'application/json' };
    var token = HIS.getToken();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    return fetch(url, {
      method: method,
      headers: headers,
      body: body == null ? undefined : JSON.stringify(body)
    }).then(function (resp) {
      return resp.json().catch(function () { throw new Error('响应解析失败(HTTP ' + resp.status + ')'); });
    }).then(function (data) {
      if (data && data.code === 401) {
        HIS.logout();
        if (typeof HIS.onUnauthorized === 'function') { HIS.onUnauthorized(); }
        throw new Error(data.msg || '未登录或登录已过期');
      }
      return data;
    });
  };

  /* 统一异常提示 */
  HIS.notifyError = function (e) {
    if (window.ElementPlus && ElementPlus.ElMessage) {
      ElementPlus.ElMessage.error((e && e.message) || String(e));
    } else {
      alert((e && e.message) || String(e));
    }
  };
  HIS.notifySuccess = function (msg) {
    if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.success(msg || '操作成功'); }
  };

  HIS.views = HIS.views || {};
})();
