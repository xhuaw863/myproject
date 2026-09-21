/* 医保原生HIS - 应用入口: 登录页 + 主布局 + 菜单路由 + Vue挂载 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};
  var createApp = Vue.createApp;

  /* ===== 菜单定义 =====
   * comp: HIS.views 中的组件键(有则渲染该页); 无则渲染 Placeholder(建设中)。
   */
  var MENU = [
    { key: 'dashboard', label: '工作台', comp: 'Dashboard' },
    {
      group: '平台管理', children: [
        { key: 'tenant-info', label: '医院信息', comp: 'TenantInfo' },
        { key: 'user-manage', label: '用户管理', comp: 'UserManage' }
      ]
    },
    {
      group: '基础数据', children: [
        { key: 'dept', label: '科室管理', comp: 'DeptManage' },
        { key: 'staff', label: '职工管理', comp: 'StaffManage' },
        { key: 'schedule', label: '排班号源', comp: 'ScheduleManage' },
        { key: 'charge-item', label: '收费项目对照', comp: 'ChargeItemManage' }
      ]
    },
    {
      group: '医保字典', children: [
        { key: 'dict-download', label: '字典下载', comp: 'DictDownload' },
        { key: 'dict-version', label: '版本状态', comp: 'DictVersion' },
        { key: 'catalog-map', label: '三目录医保对照', comp: 'CatalogMap' }
      ]
    },
    {
      group: '标准字典', children: [
        { key: 'std-dict-browse', label: '字典浏览', comp: 'StdDictBrowse' },
        { key: 'std-dict-import', label: '提取入库', comp: 'StdDictImport' },
        { key: 'area-code', label: '行政区划', comp: 'AreaManage' }
      ]
    },
    {
      group: '门诊挂号台', children: [
        { key: 'patient', label: '患者建档/查询', comp: 'PatientManage' },
        { key: 'register', label: '门诊挂号', comp: 'RegistrationDesk' },
        { key: 'unregister', label: '退号', comp: 'UnregisterDesk' }
      ]
    },
    {
      group: '医生站', children: [
        { key: 'doctor-queue', label: '候诊列表', comp: 'DoctorQueue' },
        { key: 'doctor-work', label: '接诊工作台', comp: 'DoctorWork' }
      ]
    },
    {
      group: '药房', children: [
        { key: 'dispense-todo', label: '待发药', phase: 'P1d' },
        { key: 'dispense', label: '调剂发药', phase: 'P1d' },
        { key: 'drug-return', label: '退药', phase: 'P1d' }
      ]
    },
    {
      group: '药库', children: [
        { key: 'wh-drug', label: '药品目录', phase: 'P1d' },
        { key: 'wh-in', label: '采购入库', phase: 'P1d' },
        { key: 'wh-stock', label: '库存/流水', phase: 'P1d' },
        { key: 'wh-check', label: '盘点', phase: 'P1d' }
      ]
    },
    {
      group: '收费结算台', children: [
        { key: 'charge-todo', label: '待收费', phase: 'P1e' },
        { key: 'charge-setl', label: '医保结算', phase: 'P1e' },
        { key: 'charge-refund', label: '退费', phase: 'P1e' }
      ]
    },
    {
      group: '查询报表', children: [
        { key: 'rpt-setl', label: '结算记录', phase: 'P1f' },
        { key: 'rpt-daily', label: '门诊日结', phase: 'P1f' }
      ]
    },
    { key: 'community-dict', label: '医共体字典', comp: 'CommunityDict' },
    { key: 'org-catalog', label: '机构目录选用', comp: 'OrgCatalog' }
  ];

  function findItem(key) {
    return findItemIn(MENU, key);
  }

  /* 在给定菜单结构中按键查找项(兼容顶级项与目录子项) */
  function findItemIn(menu, key) {
    for (var i = 0; i < (menu || []).length; i++) {
      var m = menu[i];
      if (m.key === key) { return m; }
      if (m.children) {
        for (var j = 0; j < m.children.length; j++) {
          if (m.children[j].key === key) { return m.children[j]; }
        }
      }
    }
    return null;
  }

  /* 取菜单首个可渲染项的键(动态菜单加载后校正默认激活项) */
  function firstKey(menu) {
    for (var i = 0; i < (menu || []).length; i++) {
      var m = menu[i];
      if (m.key) { return m.key; }
      if (m.children && m.children.length) { return m.children[0].key; }
    }
    return 'dashboard';
  }

  /* 将后端菜单树节点({menuKey,menuName,comp,phase,children})归一为渲染结构 */
  function normalizeMenu(nodes) {
    var out = [];
    (nodes || []).forEach(function (n) {
      if (n.children && n.children.length) {
        out.push({
          group: n.menuName,
          children: n.children.map(function (c) {
            return { key: c.menuKey, label: c.menuName, comp: c.comp, phase: c.phase };
          })
        });
      } else {
        out.push({ key: n.menuKey, label: n.menuName, comp: n.comp, phase: n.phase });
      }
    });
    return out;
  }

  /* ===== 登录页 =====
   * 医院开通不再自助注册: 由平台超级管理员登录后台「医院管理」统一开通并分配权限。
   */
  var LoginPage = {
    emits: ['logged'],
    data: function () {
      return {
        loading: false,
        loginForm: { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' }
      };
    },
    methods: {
      doLogin: function () {
        var vm = this;
        if (!vm.loginForm.tenantCode || !vm.loginForm.username || !vm.loginForm.password) {
          ElementPlus.ElMessage.warning('请填写医院码、账号与密码'); return;
        }
        vm.loading = true;
        HIS.post('/api/auth/login', vm.loginForm)
          .then(function (d) {
            HIS.setToken(d.token);
            HIS.setUser(d);
            HIS.notifySuccess('登录成功');
            vm.$emit('logged');
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      }
    },
    template: [
      '<div class="login-wrap">',
      '  <div class="login-box" style="width:420px;">',
      '    <h2>医保原生 HIS</h2>',
      '    <div class="sub">多租户 · 医院信息系统 · 医保接口原生对接</div>',
      '    <el-form :model="loginForm" label-width="80px" @submit.prevent>',
      '      <el-form-item label="医院码"><el-input v-model="loginForm.tenantCode" placeholder="医院登录码"></el-input></el-form-item>',
      '      <el-form-item label="账号"><el-input v-model="loginForm.username" placeholder="账号"></el-input></el-form-item>',
      '      <el-form-item label="密码"><el-input v-model="loginForm.password" type="password" show-password @keyup.enter="doLogin" placeholder="密码"></el-input></el-form-item>',
      '      <el-button type="primary" style="width:100%" :loading="loading" @click="doLogin">登 录</el-button>',
      '      <div class="login-links"><span style="color:#909399;">医院管理员：H42010000000 / admin / admin123</span></div>',
      '      <div class="login-links"><span style="color:#909399;">平台超管(开通医院)：PLATFORM / superadmin / admin123</span></div>',
      '    </el-form>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ===== 主布局 ===== */
  var AppLayout = {
    props: ['user'],
    emits: ['logout'],
    data: function () {
      return { activeKey: 'dashboard', menu: MENU, collapsed: localStorage.getItem('his-aside-collapsed') === '1' };
    },
    computed: {
      currentItem: function () { return findItemIn(this.menu, this.activeKey); },
      currentComp: function () {
        var it = this.currentItem;
        if (it && it.comp && HIS.views[it.comp]) { return HIS.views[it.comp]; }
        return HIS.views.Placeholder;
      },
      currentProps: function () {
        var it = this.currentItem;
        if (it && !it.comp) { return { title: it.label, phase: it.phase || 'P1' }; }
        return {};
      },
      roleName: function () {
        var u = this.user || {};
        return u.roleName || HIS.roleLabel(u.role);
      }
    },
    methods: {
      onSelect: function (key) { this.activeKey = key; },
      onCmd: function (c) { if (c === 'logout') { this.$emit('logout'); } },
      toggleAside: function () {
        this.collapsed = !this.collapsed;
        localStorage.setItem('his-aside-collapsed', this.collapsed ? '1' : '0');
      }
    },
    mounted: function () {
      var vm = this;
      /* 全局视图跳转: 供列表页跳转到工作台等场景 */
      HIS.go = function (key) { vm.activeKey = key; };
      /* 动态菜单: 按角色从后端加载; 失败回退静态 MENU 防锁死 */
      HIS.get('/api/auth/menus')
        .then(function (nodes) {
          if (nodes && nodes.length) {
            vm.menu = normalizeMenu(nodes);
            if (!findItemIn(vm.menu, vm.activeKey)) { vm.activeKey = firstKey(vm.menu); }
          }
        })
        .catch(function () { /* 保留静态 MENU 兜底 */ });
    },
    template: [
      '<div class="layout">',
      '  <div class="layout-header">',
      '    <span class="logo">医保原生 HIS</span>',
      '    <span class="hosp">租户: {{ user.tenantName || "-" }}</span>',
      '    <span class="hosp" v-if="user.orgName" style="opacity:.85;">机构: {{ user.orgName }}</span>',
      '    <span class="spacer"></span>',
      '    <a class="hosp" href="/verify/index.html" target="_blank" style="text-decoration:none;cursor:pointer;">医保验证台</a>',
      '    <el-dropdown @command="onCmd">',
      '      <span class="user">{{ user.realName || user.username }}（{{ roleName }}）<span style="margin-left:4px;">▾</span></span>',
      '      <template #dropdown>',
      '        <el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu>',
      '      </template>',
      '    </el-dropdown>',
      '  </div>',
      '  <div class="layout-body">',
      '    <div class="layout-aside" :class="{\'is-collapsed\':collapsed}">',
      '      <div class="aside-bar">',
      '        <el-button link size="small" :title="collapsed?\'展开菜单\':\'收起菜单\'" @click="toggleAside">{{ collapsed?"\u00bb":"\u00ab" }}</el-button>',
      '      </div>',
      /* 不用 v-show 隐藏: v-show 会触发 el-menu 内置 collapse 过渡, 展开后残留内联 width:0 裁掉全部菜单项;
         改由 CSS .layout-aside.is-collapsed .el-menu{display:none} 控制显隐 */
      '      <el-menu :default-active="activeKey" @select="onSelect">',
      '        <template v-for="m in menu">',
      '          <el-menu-item v-if="!m.group" :index="m.key">{{ m.label }}</el-menu-item>',
      '          <el-sub-menu v-else :index="m.group">',
      '            <template #title><span>{{ m.group }}</span></template>',
      '            <el-menu-item v-for="c in m.children" :key="c.key" :index="c.key">{{ c.label }}</el-menu-item>',
      '          </el-sub-menu>',
      '        </template>',
      '      </el-menu>',
      '    </div>',
      '    <div class="layout-main">',
      '      <component :is="currentComp" v-bind="currentProps" :key="activeKey"></component>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ===== 根组件 ===== */
  var Root = {
    data: function () {
      return { logged: !!(HIS.getToken() && HIS.getUser()) };
    },
    computed: {
      user: function () { return HIS.getUser() || {}; }
    },
    methods: {
      onLogged: function () { this.logged = true; },
      onLogout: function () { HIS.logout(); this.logged = false; }
    },
    components: { 'login-page': LoginPage, 'app-layout': AppLayout },
    template: [
      '<login-page v-if="!logged" @logged="onLogged"></login-page>',
      '<app-layout v-else :user="user" @logout="onLogout"></app-layout>'
    ].join('\n')
  };

  /* 401 全局处理: 清除登录态并回到登录页 */
  HIS.onUnauthorized = function () {
    ElementPlus.ElMessage.error('登录已过期，请重新登录');
    setTimeout(function () { location.reload(); }, 800);
  };

  var app = createApp(Root);
  app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
  app.mount('#app');
})();
