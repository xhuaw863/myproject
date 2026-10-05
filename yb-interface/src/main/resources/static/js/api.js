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
    { value: 'NURSE', label: '护士' },
    { value: 'THERAPIST', label: '治疗师' },
    { value: 'TECHNICIAN', label: '医技人员' },
    { value: 'MR_INPUT', label: '病案录入组' },
    { value: 'MR_CATALOG', label: '病案编目组' },
    { value: 'MR_REVIEW', label: '病案质控组' }
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

  /* ===== 雪花ID统一治理 =====
   * 后端 Long 型 ID(19位雪花ID)统一以带引号 JSON 字符串下发(见 JacksonConfig),
   * 因 JS Number 安全整数上限 2^53-1, 直接转 Number 会静默丢精度, 故前端全链路以字符串承载 ID。
   * 页面契约: 跨页/回填传递 ID 用 HIS.id; 对象 key、DOM 标记用 HIS.idKey;
   * 拼接 URL 参数用 HIS.idParam; 相等比较一律 HIS.sameId, 禁止 === 直接比对 ID。
   */
  /* ID 原样规范化: null/undefined 保持原值(保留"无ID"语义, 回填表单/请求体时不得改写成字符串); 其余 String(value) */
  HIS.id = function (value) {
    if (value === null || value === undefined) { return value; }
    return String(value);
  };
  /* ID 键值化: 空值(null/undefined/'')统一归一为 ''(可安全作对象 key/DOM 标识/URL 片段); 其余 String(value) */
  HIS.idKey = function (value) {
    if (value === null || value === undefined || value === '') { return ''; }
    return String(value);
  };
  /* ID 相等判断: 经 idKey 归一后比较 —— 双方都空视为相同, 一方空一方非空视为不同, 非空按字符串严格比较 */
  HIS.sameId = function (a, b) {
    return HIS.idKey(a) === HIS.idKey(b);
  };
  /* ID 转 URL 查询参数: 空值输出空串, 其余 encodeURIComponent 后的字符串 */
  HIS.idParam = function (value) {
    return encodeURIComponent(HIS.idKey(value));
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
      /* 全局 Long->String 序列化(雪花ID防精度)会把分页计数字段 total/page/size 一并变成字符串,
       * 而 el-pagination 的 total 需为数字(否则内部就绪判定失败渲染为空)。此处仅归一化顶层计数字段,
       * 不触碰 records 内的 ID(仍需字符串), 一次性覆盖所有基于 MyBatis-Plus IPage 的分页接口。 */
      var payload = data.data;
      if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
        if (payload.total != null) { payload.total = Number(payload.total); }
        if (payload.page != null) { payload.page = Number(payload.page); }
        if (payload.size != null) { payload.size = Number(payload.size); }
      }
      return payload;
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
      /* 后端全局异常处理器对 BizException 返 HTTP 200 + R JSON 信封: 若不判 Content-Type,
       * 导出失败会把 JSON 错误体当文件存盘并误报成功。JSON 响应一律拆出 msg 抛错。 */
      var ct = resp.headers.get('Content-Type') || '';
      if (ct.indexOf('json') >= 0) {
        return resp.json().then(function (j) { throw new Error((j && j.msg) || '下载失败'); });
      }
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

  /* ===== 费别/支付方式字典轻量缓存(消费端共享: 挂号/收费/住院) =====
   * loadFeePayDict(scene): 拉 /api/his/fee-pay-dict/reg-options?scene= 缓存该场景的 feeTypes/payMethods;
   *   重复调用命中缓存复用同一 Promise; 失败回落空数组(消费端保留原硬编码兜底, 不阻断页面)。
   * feeTypeLabel/payMethodLabel: 字典优先(费别按 code, 支付按 code 或 legacy_codes 命中历史旧值),
   *   未命中回落传入原值(未知历史值原样展示)。 */
  HIS._fpCache = HIS._fpCache || {};
  HIS.loadFeePayDict = function (scene) {
    scene = String(scene || 'OTP').toUpperCase();
    var slot = HIS._fpCache[scene];
    if (slot && slot.promise) { return slot.promise; }
    slot = HIS._fpCache[scene] = { feeTypes: [], payMethods: [] };
    slot.promise = HIS.get('/api/his/fee-pay-dict/reg-options?scene=' + scene).then(function (d) {
      slot.feeTypes = (d && d.feeTypes) || [];
      slot.payMethods = (d && d.payMethods) || [];
      return slot;
    }).catch(function () { slot.promise = null; return slot; });
    return slot.promise;
  };
  HIS.feeTypeOpts = function (scene) { var s = HIS._fpCache[String(scene || 'OTP').toUpperCase()]; return s ? (s.feeTypes || []) : []; };
  HIS.payMethodOpts = function (scene) { var s = HIS._fpCache[String(scene || 'OTP').toUpperCase()]; return s ? (s.payMethods || []) : []; };
  HIS.feeTypeLabel = function (code) {
    if (code === null || code === undefined || code === '') { return '-'; }
    var target = String(code).toUpperCase();
    var scenes = Object.keys(HIS._fpCache);
    for (var i = 0; i < scenes.length; i++) {
      var ft = HIS._fpCache[scenes[i]].feeTypes || [];
      for (var j = 0; j < ft.length; j++) { if (String(ft[j].code).toUpperCase() === target) { return ft[j].name; } }
    }
    /* 内置历史码中文兜底(字典未加载时) */
    if (target === 'SELF') { return '自费'; }
    if (target === 'INSURANCE') { return '医保'; }
    return String(code);
  };
  HIS.payMethodLabel = function (code) {
    if (code === null || code === undefined || code === '') { return '-'; }
    var raw = String(code);
    var target = raw.toUpperCase();
    var scenes = Object.keys(HIS._fpCache);
    for (var i = 0; i < scenes.length; i++) {
      var pm = HIS._fpCache[scenes[i]].payMethods || [];
      for (var j = 0; j < pm.length; j++) {
        var p = pm[j];
        if (String(p.code).toUpperCase() === target) { return p.name; }
        if (p.legacyCodes && String(p.legacyCodes).split(',').some(function (lc) { return lc.trim().toUpperCase() === target; })) { return p.name; }
      }
    }
    return raw;
  };

  /* ===== 医疗类别字典轻量缓存(消费端共享: 挂号/入院) =====
   * loadMedTypeDict(scene): 拉 /api/his/med-type/options?scene=OTP|IPT 缓存该场景启用且按登录机构级别开放的条目;
   *   重复调用命中缓存复用同一 Promise; 失败回落空数组(消费端保留原硬编码兜底, 不阻断页面)。
   * medTypeOpts(scene): 取已缓存列表; medTypeLabel(code, fallbackMap): 字典优先, 未命中回落传入 map 或原值。 */
  HIS._medTypeCache = HIS._medTypeCache || {};
  HIS.loadMedTypeDict = function (scene) {
    scene = String(scene || 'OTP').toUpperCase() === 'IPT' ? 'IPT' : 'OTP';
    var slot = HIS._medTypeCache[scene];
    if (slot && slot.promise) { return slot.promise; }
    slot = HIS._medTypeCache[scene] = { list: [] };
    slot.promise = HIS.get('/api/his/med-type/options?scene=' + scene).then(function (d) {
      slot.list = d || [];
      return slot.list;
    }).catch(function () { slot.promise = null; return slot.list || []; });
    return slot.promise;
  };
  HIS.medTypeOpts = function (scene) {
    var s = HIS._medTypeCache[String(scene || 'OTP').toUpperCase() === 'IPT' ? 'IPT' : 'OTP'];
    return s ? (s.list || []) : [];
  };
  HIS.medTypeLabel = function (code, fallbackMap) {
    if (code === null || code === undefined || code === '') { return code; }
    var target = String(code);
    var scenes = Object.keys(HIS._medTypeCache);
    for (var i = 0; i < scenes.length; i++) {
      var l = (HIS._medTypeCache[scenes[i]] && HIS._medTypeCache[scenes[i]].list) || [];
      for (var j = 0; j < l.length; j++) { if (String(l[j].code) === target) { return l[j].name; } }
    }
    if (fallbackMap && fallbackMap[target] != null) { return fallbackMap[target]; }
    return target;
  };

  /* 列表显示模式默认值(全局共享): 优先本地偏好 his.<key>('0'=全量,其余=分页),
   * 无本地偏好时回落租户参数 system.list_default_paged(默认分页)。各视图 data() 内调用。 */
  HIS.pagedDefault = function (key) {
    try {
      var v = localStorage.getItem('his.' + key);
      if (v !== null) { return v !== '0'; }
      return !(window.HIS && HIS.params && HIS.params.listDefaultPaged === 'false');
    } catch (e) { return true; }
  };

  /* ===== 字典下拉取数(字典化录入: 医共体统一字典优先) =====
   * HIS.stdValues(type, code): type=cv_code|wst364|hbvalue|whvalue, code=分组编码(dict_code/cv_code)
   * 口径(2026-09 字典分层原则): 医疗业务值域一律从医共体统一字典 his_val_dict 取数(
   *   /api/community-dict/val-dict/values?dictType=type:code, 启用项);
   *   统一字典该组未导入(空集)时回落标准值域直查防业务白屏, 并 console 提醒待导入。
   * 返回 [{code,name}]; 带内存缓存, 同一值域只请求一次。
   */
  var _dictCache = {};
  HIS.stdValues = function (type, code) {
    var key = type + ':' + code;
    if (_dictCache[key]) { return Promise.resolve(_dictCache[key]); }
    return HIS.get('/api/community-dict/val-dict/values?dictType=' + encodeURIComponent(key))
      .then(function (list) {
        if (list && list.length) { _dictCache[key] = list; return list; }
        if (window.console) { console.warn('[字典分层] 医共体值域字典未导入 ' + key + ', 临时回落标准字典, 请在医共体字典-值域字典页导入'); }
        return HIS.get('/api/std-dict/query/values?type=' + encodeURIComponent(type) + '&code=' + encodeURIComponent(code));
      })
      .then(function (list) { _dictCache[key] = list || []; return _dictCache[key]; });
  };
  /* 按统一字典 dict_type 原样取启用项: 供卫健归一化域名类值域(如 '药品剂型', 无 type:code 结构);
   * 空集不回落标准字典(无同名 std 域), 直接返回空数组由调用方兜底。 */
  HIS.valByType = function (dictType) {
    var key = 'vt:' + dictType;
    if (_dictCache[key]) { return Promise.resolve(_dictCache[key]); }
    return HIS.get('/api/community-dict/val-dict/values?dictType=' + encodeURIComponent(dictType))
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

  /* ===== 门诊班次字典(his_shift_dict, L1 全局缓存) =====
   * 挂号/排班/周视图的时段数据源: HIS.shiftDict(cb) 首次拉取后缓存,
   * 字典维护页保存后调 HIS.clearShifts() 失效; 接口异常/空字典回落存量三值防白屏。
   */
  var SHIFT_FALLBACK = [{ v: 'am', l: '上午', st: '', et: '' }, { v: 'pm', l: '下午', st: '', et: '' }, { v: 'night', l: '晚间', st: '', et: '' }];
  HIS.shiftDict = function (cb) {
    if (HIS._shifts && HIS._shifts.length) {
      if (cb) { cb(HIS._shifts); }
      return Promise.resolve(HIS._shifts);
    }
    return HIS.get('/api/community-dict/shift-dict/values').then(function (l) {
      HIS._shifts = (l || []).map(function (s) {
        return { v: s.code, l: s.name, st: s.startTime || '', et: s.endTime || '' };
      });
      if (!HIS._shifts.length) { HIS._shifts = SHIFT_FALLBACK.slice(); }
      if (cb) { cb(HIS._shifts); }
      return HIS._shifts;
    }).catch(function () {
      HIS._shifts = SHIFT_FALLBACK.slice();
      if (cb) { cb(HIS._shifts); }
      return HIS._shifts;
    });
  };
  /* 同步取缓存选项(未加载返回 null, 调用方自备兜底常量) */
  HIS.shiftOptions = function () { return HIS._shifts; };
  /* 班次码→名称(缓存未命中返回 null 由调用方兜底); 模板展示用可附起止时间 */
  HIS.shiftLabel = function (code) {
    if (!HIS._shifts || !code) { return null; }
    for (var i = 0; i < HIS._shifts.length; i++) {
      if (HIS._shifts[i].v === code) { return HIS._shifts[i].l; }
    }
    return null;
  };
  HIS.shiftRange = function (code) {
    if (!HIS._shifts || !code) { return ''; }
    for (var i = 0; i < HIS._shifts.length; i++) {
      var s = HIS._shifts[i];
      if (s.v === code) { return s.st && s.et ? s.st + '-' + s.et : (s.st || s.et || ''); }
    }
    return '';
  };
  HIS.clearShifts = function () { HIS._shifts = null; };

  HIS.views = HIS.views || {};
})();
