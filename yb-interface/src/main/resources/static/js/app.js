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
        { key: 'dept', label: '科室管理', phase: 'P1a' },
        { key: 'staff', label: '职工管理', phase: 'P1a' },
        { key: 'schedule', label: '排班号源', phase: 'P1a' },
        { key: 'charge-item', label: '收费项目对照', phase: 'P1a' }
      ]
    },
    {
      group: '医保字典', children: [
        { key: 'dict-download', label: '字典下载', phase: 'P1a' },
        { key: 'dict-version', label: '版本状态', phase: 'P1a' },
        { key: 'dict-map', label: '目录对照', phase: 'P1a' }
      ]
    },
    {
      group: '门诊挂号台', children: [
        { key: 'patient', label: '患者建档/查询', phase: 'P1b' },
        { key: 'register', label: '门诊挂号', phase: 'P1b' },
        { key: 'unregister', label: '退号', phase: 'P1b' }
      ]
    },
    {
      group: '医生站', children: [
        { key: 'doctor-queue', label: '候诊列表', phase: 'P1c' },
        { key: 'doctor-work', label: '接诊工作台', phase: 'P1c' }
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
    }
  ];

  function findItem(key) {
    for (var i = 0; i < MENU.length; i++) {
      var m = MENU[i];
      if (m.key === key) { return m; }
      if (m.children) {
        for (var j = 0; j < m.children.length; j++) {
          if (m.children[j].key === key) { return m.children[j]; }
        }
      }
    }
    return null;
  }

  /* ===== 登录 / 注册页 ===== */
  var LoginPage = {
    emits: ['logged'],
    data: function () {
      return {
        tab: 'login',
        loading: false,
        loginForm: { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' },
        regForm: {
          tenantCode: '', tenantName: '', contact: '', phone: '', address: '',
          fixmedinsCode: '', fixmedinsName: '', mdtrtareaAdmvs: '', insuplcAdmdvs: '',
          apiUrl: '', mockEnabled: 1,
          adminUsername: '', adminPassword: '', adminName: ''
        }
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
      },
      doRegister: function () {
        var vm = this;
        var f = vm.regForm;
        if (!f.tenantCode || !f.tenantName || !f.adminUsername || !f.adminPassword) {
          ElementPlus.ElMessage.warning('请填写医院码、医院名称、管理员账号与密码'); return;
        }
        vm.loading = true;
        HIS.post('/api/auth/register', f)
          .then(function () {
            ElementPlus.ElMessage.success('医院注册成功，请使用管理员账号登录');
            vm.loginForm.tenantCode = f.tenantCode;
            vm.loginForm.username = f.adminUsername;
            vm.loginForm.password = '';
            vm.tab = 'login';
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      }
    },
    template: [
      '<div class="login-wrap">',
      '  <div class="login-box" style="width:460px;">',
      '    <h2>医保原生 HIS</h2>',
      '    <div class="sub">多租户 · 医院信息系统 · 医保接口原生对接</div>',
      '    <el-tabs v-model="tab" stretch>',
      '      <el-tab-pane label="登录" name="login">',
      '        <el-form :model="loginForm" label-width="80px" @submit.prevent>',
      '          <el-form-item label="医院码"><el-input v-model="loginForm.tenantCode" placeholder="医院登录码"></el-input></el-form-item>',
      '          <el-form-item label="账号"><el-input v-model="loginForm.username" placeholder="账号"></el-input></el-form-item>',
      '          <el-form-item label="密码"><el-input v-model="loginForm.password" type="password" show-password @keyup.enter="doLogin" placeholder="密码"></el-input></el-form-item>',
      '          <el-button type="primary" style="width:100%" :loading="loading" @click="doLogin">登 录</el-button>',
      '          <div class="login-links"><span style="color:#909399;">演示账号：H42010000000 / admin / admin123</span></div>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="医院注册" name="register">',
      '        <el-form :model="regForm" label-width="92px" size="small">',
      '          <el-form-item label="医院码"><el-input v-model="regForm.tenantCode" placeholder="唯一登录码，如 H42010000000"></el-input></el-form-item>',
      '          <el-form-item label="医院名称"><el-input v-model="regForm.tenantName"></el-input></el-form-item>',
      '          <el-form-item label="联系人"><el-input v-model="regForm.contact"></el-input></el-form-item>',
      '          <el-form-item label="联系电话"><el-input v-model="regForm.phone"></el-input></el-form-item>',
      '          <el-form-item label="机构编号"><el-input v-model="regForm.fixmedinsCode" placeholder="医保定点机构编号"></el-input></el-form-item>',
      '          <el-form-item label="医保区划"><el-input v-model="regForm.mdtrtareaAdmvs" placeholder="如 420100"></el-input></el-form-item>',
      '          <el-form-item label="模拟平台"><el-switch v-model="regForm.mockEnabled" :active-value="1" :inactive-value="0" active-text="模拟" inactive-text="真实"></el-switch></el-form-item>',
      '          <el-divider content-position="left">管理员账号</el-divider>',
      '          <el-form-item label="管理员账号"><el-input v-model="regForm.adminUsername"></el-input></el-form-item>',
      '          <el-form-item label="管理员密码"><el-input v-model="regForm.adminPassword" type="password" show-password></el-input></el-form-item>',
      '          <el-form-item label="管理员姓名"><el-input v-model="regForm.adminName"></el-input></el-form-item>',
      '          <el-button type="primary" style="width:100%" :loading="loading" @click="doRegister">注 册</el-button>',
      '        </el-form>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ===== 主布局 ===== */
  var AppLayout = {
    props: ['user'],
    emits: ['logout'],
    data: function () {
      return { activeKey: 'dashboard', menu: MENU };
    },
    computed: {
      currentItem: function () { return findItem(this.activeKey); },
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
      roleName: function () { return HIS.roleLabel(this.user && this.user.role); }
    },
    methods: {
      onSelect: function (key) { this.activeKey = key; },
      onCmd: function (c) { if (c === 'logout') { this.$emit('logout'); } }
    },
    template: [
      '<div class="layout">',
      '  <div class="layout-header">',
      '    <span class="logo">医保原生 HIS</span>',
      '    <span class="hosp">{{ user.tenantName || "-" }}</span>',
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
      '    <div class="layout-aside">',
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
