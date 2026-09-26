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
        { key: 'user-manage', label: '用户管理', comp: 'UserManage' },
        { key: 'dept', label: '科室管理', comp: 'DeptManage' },
        { key: 'staff', label: '职工管理', comp: 'StaffManage' }
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
        { key: 'unregister', label: '退号换号', comp: 'UnregisterDesk' },
        { key: 'schedule', label: '排班号源', comp: 'ScheduleManage' },
        { key: 'reg_stats', label: '挂号统计', comp: 'RegStatistics' },
        { key: 'reg_detail', label: '挂号明细', comp: 'RegDetailQuery' }
      ]
    },
    {
      group: '医生站', children: [
        { key: 'doctor-ws', label: '门诊医生站', comp: 'DoctorWorkstation' },
        { key: 'doctor-worklog', label: '医生工作日志', comp: 'DoctorWorklog' }
      ]
    },
    {
      group: '药房', children: [
        { key: 'dispense-todo', label: '待发药', comp: 'DispenseTodo' },
        { key: 'dispense', label: '调剂发药', comp: 'DispenseRecord' },
        { key: 'drug-return', label: '退药', comp: 'DrugReturn' },
        { key: 'pharmacy-def', label: '药房管理', comp: 'PharmacyDef' },
        { key: 'pharmacy-rpt', label: '药房统计', comp: 'PharmacyReport' }
      ]
    },
    {
      group: '药库', children: [
        { key: 'wh-drug', label: '药品目录', comp: 'DrugCatalogView' },
        { key: 'wh-in', label: '采购入库', comp: 'StockInManage' },
        { key: 'wh-out', label: '出库管理', comp: 'StockOutManage' },
        { key: 'wh-stock', label: '库存/流水', comp: 'DrugStock' },
        { key: 'wh-check', label: '盘点', comp: 'StockCheck' },
        { key: 'warehouse-def', label: '药库管理', comp: 'WarehouseDef' },
        { key: 'warehouse-rpt', label: '药库统计', comp: 'WarehouseReport' }
      ]
    },
    {
      group: '收费结算台', children: [
        { key: 'charge-todo', label: '待收费', comp: 'ChargeTodo' },
        { key: 'charge-setl', label: '医保结算', comp: 'ChargeSetl' },
        { key: 'charge-refund', label: '退费', comp: 'ChargeRefund' },
        { key: 'invoice-mgr', label: '发票管理', comp: 'InvoiceManage' },
        { key: 'charge-rpt', label: '收费统计', comp: 'ChargeReport' }
      ]
    },
    {
      group: '查询报表', children: [
        { key: 'rpt-setl', label: '结算记录', comp: 'SettleRecords' },
        { key: 'rpt-daily', label: '门诊日结', comp: 'DailySettle' }
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
        recent: [],
        recentIdx: 0,
        rememberPwd: false,
        loginForm: { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' }
      };
    },
    created: function () {
      this.recent = HIS.getRecentAccounts();
      if (this.recent.length) { this.applyRecent(0); }
    },
    methods: {
      applyRecent: function (i) {
        var a = this.recent[i];
        if (!a) { return; }
        this.recentIdx = i;
        this.loginForm = { tenantCode: a.tenantCode, username: a.username, password: a.password || '' };
      },
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
            HIS.rememberAccount(vm.loginForm, vm.rememberPwd);
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
      '      <el-form-item label="最近账号" v-if="recent.length">',
      '        <el-select v-model="recentIdx" style="width:100%" placeholder="选择最近登录账号" @change="applyRecent">',
      '          <el-option v-for="(a,i) in recent" :key="i" :label="a.username + \' @ \' + a.tenantCode" :value="i"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="医院码"><el-input v-model="loginForm.tenantCode" placeholder="医院登录码"></el-input></el-form-item>',
      '      <el-form-item label="账号"><el-input v-model="loginForm.username" placeholder="账号"></el-input></el-form-item>',
      '      <el-form-item label="密码"><el-input v-model="loginForm.password" type="password" show-password @keyup.enter="doLogin" placeholder="密码"></el-input></el-form-item>',
      '      <div style="margin:-6px 0 12px 80px;"><el-checkbox v-model="rememberPwd">记住密码</el-checkbox><span style="color:#909399;font-size:12px;margin-left:8px;">默认不保存密码; 勾选后密码存于本机浏览器, 共享终端请勿勾选</span></div>',
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
      },
      /* 可登录机构(多点执业): >1 时顶栏展示"切换机构" */
      allowedOrgs: function () { return (this.user || {}).allowedOrgs || []; }
    },
    methods: {
      onSelect: function (key) { this.activeKey = key; },
      onCmd: function (c) { if (c === 'logout') { this.$emit('logout'); } },
      /* 切换活动机构: 重签令牌后整页重载(菜单/权限随新机构上下文重建) */
      onSwitchOrg: function (orgId) {
        if (!orgId || orgId === (this.user || {}).orgId) { return; }
        HIS.post('/api/auth/switch-org', { orgId: orgId })
          .then(function (d) {
            HIS.setToken(d.token);
            HIS.setUser(d);
            HIS.notifySuccess('已切换到 ' + (d.orgName || '目标机构'));
            setTimeout(function () { location.reload(); }, 300);
          })
          .catch(HIS.notifyError);
      },
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
      '    <el-dropdown v-if="allowedOrgs.length > 1" @command="onSwitchOrg" style="margin:0 4px;">',
      '      <span class="hosp" style="cursor:pointer;">机构: {{ user.orgName }} ▾</span>',
      '      <template #dropdown><el-dropdown-menu>',
      '        <el-dropdown-item v-for="o in allowedOrgs" :key="o.orgId" :command="o.orgId" :disabled="o.orgId === user.orgId">{{ o.orgName }}{{ o.home ? " (归属)" : "" }}</el-dropdown-item>',
      '      </el-dropdown-menu></template>',
      '    </el-dropdown>',
      '    <span class="hosp" v-else-if="user.orgName" style="opacity:.85;">机构: {{ user.orgName }}</span>',
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
      /* user 存为 data 快照: computed 直接读 localStorage 无响应式依赖,
       * 登出再登入同会话时会命中永久缓存(顶栏机构徽标显示上一个账号), 改在登录/登出时主动刷新 */
      return { logged: !!(HIS.getToken() && HIS.getUser()), user: HIS.getUser() || {} };
    },
    methods: {
      onLogged: function () { this.user = HIS.getUser() || {}; this.logged = true; },
      onLogout: function () { HIS.logout(); this.user = {}; this.logged = false; }
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
