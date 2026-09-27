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

  /* 最近登录账号缓存(最多5个, 最近一次在前; 登录页默认选最近一条并回填)。
   * 安全加固: 默认只缓存医院码+账号, 密码仅在用户显式勾选"记住密码"时才落地。
   * v2 换键并清除旧版(曾明文存密码)缓存。 */
  var RECENT_KEY = 'yb_his_recent_accounts_v2';
  try { localStorage.removeItem('yb_his_recent_accounts'); } catch (e) { }
  HIS.getRecentAccounts = function () {
    try { var a = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); return Array.isArray(a) ? a : []; } catch (e) { return []; }
  };
  HIS.rememberAccount = function (acc, rememberPwd) {
    if (!acc || !acc.username) { return; }
    try {
      var list = HIS.getRecentAccounts().filter(function (x) {
        return !(x.tenantCode === acc.tenantCode && x.username === acc.username);
      });
      list.unshift({ tenantCode: acc.tenantCode, username: acc.username, password: rememberPwd ? acc.password : null });
      if (list.length > 5) { list = list.slice(0, 5); }
      localStorage.setItem(RECENT_KEY, JSON.stringify(list));
    } catch (e) { }
  };

  /* 是否牵头机构(org_level=1): 取自登录响应 leadOrg 字段;
   * 牵头可维护全医共体基础数据, 非牵头仅本机构且只读(前端隐藏写按钮, 后端 403 兜底)。 */
  HIS.isLead = function () {
    var u = HIS.getUser();
    return !!(u && u.leadOrg);
  };

  /* 当前登录机构ID(多点执业切换后即为目标机构) */
  HIS.currentOrgId = function () {
    var u = HIS.getUser();
    return u ? u.orgId : null;
  };

  /* 医共体一人多角色: 任一角色命中即拥有(登录响应 roles 数组并集口径); roles 缺失回落主角色(旧登录会话兼容) */
  HIS.hasRole = function (code) {
    var u = HIS.getUser();
    if (!u) { return false; }
    if (u.roles && u.roles.length) { return u.roles.indexOf(code) >= 0; }
    return u.role === code;
  };

  /* 是否可维护本机构级业务数据(排班号源): 本机构管理员 ADMIN/ORG_ADMIN 或平台超管。
   * 排班为机构自己的业务过程, 不再限定牵头身份, 每个机构自治自己的号源。 */
  HIS.canMaintainSelfOrg = function () {
    return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
  };

  /* ===== 角色字典 ===== */
  HIS.ROLES = [
    { value: 'ADMIN', label: '系统管理员' },
    { value: 'ORG_ADMIN', label: '机构系统管理员' },
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
        out.push({ id: n.id, label: pad + n.orgName, orgLevel: n.orgLevel, orgCode: n.orgCode, pyCode: n.pyCode, depth: depth, hasKids: !!(n.children && n.children.length) });
        if (n.children && n.children.length) { walk(n.children, depth + 1); }
      });
    })(nodes, 0);
    return out;
  };

  /* ===== 关键字本地匹配助手 =====
   * 供所有本地 el-select filterable / 客户端过滤列表统一使用:
   * kw 为空→全部命中; 否则 kw 小写后对 item 指定 fields 任一字段做不区分大小写包含判断。
   * 用法: :filter-method="function(q){ return HIS.kwMatch(opt, q, ['label','code','pyCode','abbrCode']) }"
   */
  HIS.kwMatch = function (item, kw, fields) {
    if (!kw) { return true; }
    if (!item) { return false; }
    var q = String(kw).toLowerCase();
    for (var i = 0; i < fields.length; i++) {
      var v = item[fields[i]];
      if (v != null && String(v).toLowerCase().indexOf(q) >= 0) { return true; }
    }
    return false;
  };

  /**
   * 本地下拉简码检索 mixin: el-select 搭配
   *   :filter-method="kwFilter(key)" + v-for="x in kwOptions(key, list, fields)"
   * 输入关键字同时命中名称/编码/拼音码/自定义码(各字段不区分大小写包含)。
   * 需拼音检索的下拉组件声明 mixins: [HIS.kwSelectMixin] 即可, selQ 由各组件独立持有。
   */
  HIS.kwSelectMixin = {
    data: function () { return { selQ: {} }; },
    methods: {
      kwFilter: function (key) {
        var vm = this;
        return function (q) { vm.selQ[key] = q || ''; };
      },
      kwOptions: function (key, list, fields) {
        var q = this.selQ[key] || '';
        if (!q) { return list || []; }
        return (list || []).filter(function (it) { return HIS.kwMatch(it, q, fields); });
      },
      /* 分组选项(el-option-group)版本: 逐组过滤并丢弃空组 */
      kwGroupOptions: function (key, groups, fields) {
        var vm = this;
        if (!this.selQ[key]) { return groups || []; }
        return (groups || []).map(function (g) {
          return { category: g.category, options: vm.kwOptions(key, g.options, fields) };
        }).filter(function (g) { return g.options.length; });
      }
    }
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

  /* 带令牌下载二进制文件(如导出 Excel): 从 Content-Disposition 取文件名, 触发浏览器保存, 返回文件名 */
  HIS.download = function (url, fallbackName) {
    var headers = {};
    var token = HIS.getToken();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    return fetch(url, { headers: headers }).then(function (resp) {
      if (!resp.ok) { throw new Error('下载失败(HTTP ' + resp.status + ')'); }
      var cd = resp.headers.get('Content-Disposition') || '';
      var name = fallbackName || 'download';
      var m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd);
      if (m) {
        try { name = decodeURIComponent(m[1]); } catch (e) { name = m[1]; }
      }
      return resp.blob().then(function (b) { return { blob: b, name: name }; });
    }).then(function (r) {
      var a = document.createElement('a');
      var u = URL.createObjectURL(r.blob);
      a.href = u; a.download = r.name;
      document.body.appendChild(a); a.click();
      setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 1000);
      return r.name;
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
