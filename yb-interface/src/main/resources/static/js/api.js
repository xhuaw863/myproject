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

  /* 动态角色: 从后端拉取可见角色(全局预置 + 租户自定义)。
   * 返回 [{id, value:roleCode, label:roleName, roleType, allMenus, tenantId}], 供下拉/标签渲染。
   */
  HIS.loadRoles = function () {
    return HIS.get('/api/sys/role/list').then(function (list) {
      return (list || []).map(function (r) {
        return {
          id: r.id, value: r.roleCode, label: r.roleName,
          roleType: r.roleType, allMenus: r.allMenus, tenantId: r.tenantId
        };
      });
    });
  };

  /* 机构树扁平化: 将 /api/sys/org/tree 展平为带缩进名称的下拉选项 [{id, label, orgLevel}] */
  HIS.flattenOrgs = function (nodes) {
    var out = [];
    (function walk(list, depth) {
      (list || []).forEach(function (n) {
        var pad = '';
        for (var i = 0; i < depth; i++) { pad += '　'; }
        out.push({ id: n.id, label: pad + n.orgName, orgLevel: n.orgLevel, orgCode: n.orgCode });
        if (n.children && n.children.length) { walk(n.children, depth + 1); }
      });
    })(nodes, 0);
    return out;
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

  /* ===== 字典下拉取数(字典化录入: 医保字典优先) =====
   * HIS.stdValues(type, code): type=cv_code|wst364|hbvalue|whvalue, code=分组编码(dict_code/cv_code)
   * 返回 [{code,name}]; 带内存缓存, 同一值域只请求一次。
   */
  var _dictCache = {};
  HIS.stdValues = function (type, code) {
    var key = type + ':' + code;
    if (_dictCache[key]) { return Promise.resolve(_dictCache[key]); }
    return HIS.get('/api/std-dict/query/values?type=' + encodeURIComponent(type) + '&code=' + encodeURIComponent(code))
      .then(function (list) { _dictCache[key] = list || []; return _dictCache[key]; });
  };
  /* 值域转 {code:name} 映射, 供表格显示 */
  HIS.dictMap = function (list) {
    var m = {}; (list || []).forEach(function (o) { m[o.code] = o.name; }); return m;
  };
  /* 行政区划检索(area_code_2021): 按名称/编码模糊, 返回 [{code,name,level,pcode}] */
  HIS.areaSearch = function (keyword) {
    return HIS.get('/api/his/area/page?page=1&size=50&keyword=' + encodeURIComponent(keyword || ''))
      .then(function (d) { return (d && d.records) || []; });
  };
  /* 行政区划编码取名称(祖先路径末级) */
  HIS.areaName = function (code) {
    if (!code) { return Promise.resolve(''); }
    return HIS.get('/api/his/area/path?code=' + encodeURIComponent(code))
      .then(function (list) { return (list && list.length) ? list[list.length - 1].name : ''; })
      .catch(function () { return ''; });
  };
  /* 行政区划级联下钻: 取某父级的直接下级(供 el-cascader 懒加载)。
   * pcode 为空/0 时取省级(level=1); 否则按 pcode 取下级。返回 [{value:code,label:name,level,leaf}]。
   */
  HIS.areaChildren = function (pcode, level) {
    var q = '/api/his/area/page?page=1&size=1000';
    if (pcode) { q += '&pcode=' + encodeURIComponent(pcode); }
    if (level) { q += '&level=' + encodeURIComponent(level); }
    return HIS.get(q).then(function (d) {
      return ((d && d.records) || []).map(function (a) {
        return { value: String(a.code), label: a.name, level: a.level, leaf: (a.level >= 5) };
      });
    });
  };

  /* ===== 文件上传(图片: 头像/签名等) =====
   * HIS.upload(file, biz): 以 multipart/form-data 上传至 /api/file/upload, 附带令牌。
   * biz 为业务分类目录(如 staff_avatar/staff_sign), 返回 {url,name,size}。
   */
  HIS.upload = function (file, biz) {
    var fd = new FormData();
    fd.append('file', file);
    if (biz) { fd.append('biz', biz); }
    var headers = {};
    var token = HIS.getToken();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    return fetch('/api/file/upload', { method: 'POST', headers: headers, body: fd })
      .then(function (resp) {
        return resp.json().catch(function () { throw new Error('响应解析失败(HTTP ' + resp.status + ')'); });
      })
      .then(function (data) {
        if (data && data.code === 401) {
          HIS.logout();
          if (typeof HIS.onUnauthorized === 'function') { HIS.onUnauthorized(); }
          throw new Error(data.msg || '未登录或登录已过期');
        }
        if (!data || data.code !== 0) { throw new Error((data && data.msg) || '上传失败'); }
        return data.data;
      });
  };

  HIS.views = HIS.views || {};
})();
