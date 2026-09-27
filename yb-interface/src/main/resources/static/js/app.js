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
      group: '医共体管理', children: [
        { key: 'tenant-info', label: '医院信息', comp: 'TenantInfo' },
        { key: 'user-manage', label: '用户管理', comp: 'UserManage' },
        { key: 'dept', label: '科室管理', comp: 'DeptManage' },
        { key: 'staff', label: '职工管理', comp: 'StaffManage' },
        { key: 'patient', label: '档案管理', comp: 'PatientManage' },
        { key: 'org-manage', label: '机构管理', comp: 'OrgManage' },
        { key: 'role-manage', label: '角色权限', comp: 'RoleManage' },
        { key: 'menu-manage', label: '菜单管理', comp: 'MenuManage' },
        { key: 'system-param', label: '系统参数', comp: 'SystemParam' },
        {
          group: '基础数据', children: [
            { key: 'catalog-map', label: '医保目录对照', comp: 'CatalogMap' },
            { key: 'community-dict', label: '医共体字典', comp: 'CommunityDict' }
          ]
        },
        {
          group: '医保字典', children: [
            { key: 'dict-download', label: '字典下载', comp: 'DictDownload' },
            { key: 'dict-version', label: '版本状态', comp: 'DictVersion' }
          ]
        },
        {
          group: '标准字典', children: [
            { key: 'std-dict-browse', label: '字典浏览', comp: 'StdDictBrowse' },
            { key: 'std-dict-import', label: '提取入库', comp: 'StdDictImport' },
            { key: 'area-code', label: '行政区划', comp: 'AreaManage' }
          ]
        }
      ]
    },
    {
      group: '机构维护管理', children: [
        { key: 'org-catalog', label: '机构目录选用', comp: 'OrgCatalog' }
      ]
    },
    {
      group: '门诊挂号收费', children: [
        { key: 'register', label: '门诊挂号', comp: 'RegistrationDesk' },
        { key: 'unregister', label: '退号换号', comp: 'UnregisterDesk' },
        { key: 'schedule', label: '排班号源', comp: 'ScheduleManage' },
        { key: 'reg_stats', label: '挂号统计', comp: 'RegStatistics' },
        { key: 'reg_detail', label: '挂号明细', comp: 'RegDetailQuery' },
        { key: 'charge-ws', label: '门诊收费', comp: 'ChargeWorkstation' },
        { key: 'charge-todo', label: '待收费', comp: 'ChargeTodo' },
        { key: 'charge-setl', label: '收费结算记录', comp: 'ChargeSetl' },
        { key: 'charge-refund', label: '退费记录', comp: 'ChargeRefund' },
        { key: 'invoice-mgr', label: '发票管理', comp: 'InvoiceManage' },
        { key: 'charge-rpt', label: '收费统计', comp: 'ChargeReport' },
        { key: 'rpt-setl', label: '结算记录', comp: 'SettleRecords' },
        { key: 'rpt-daily', label: '门诊日结', comp: 'DailySettle' }
      ]
    },
    {
      group: '门诊医生站', children: [
        { key: 'doctor-ws', label: '门诊医生工作站', comp: 'DoctorWorkstation' },
        { key: 'doctor-worklog', label: '医生工作日志', comp: 'DoctorWorklog' }
      ]
    },
    {
      group: '药房系统', children: [
        { key: 'dispense-todo', label: '待发药', comp: 'DispenseTodo' },
        { key: 'dispense', label: '调剂发药', comp: 'DispenseRecord' },
        { key: 'drug-return', label: '退药', comp: 'DrugReturn' },
        { key: 'pharmacy-def', label: '药房管理', comp: 'PharmacyDef' },
        { key: 'pharmacy-rpt', label: '药房统计', comp: 'PharmacyReport' },
        { key: 'req-mgr', label: '药品请领', comp: 'RequisitionManage' },
        { key: 'trace-code', label: '药品追溯码', comp: 'TraceCodeManage' },
        { key: 'price-mgr', label: '药房定价', comp: 'PharmacyPriceManage' }
      ]
    },
    {
      group: '药库系统', children: [
        { key: 'wh-drug', label: '药品目录', comp: 'DrugCatalogView' },
        { key: 'wh-in', label: '采购入库', comp: 'StockInManage' },
        { key: 'wh-out', label: '出库管理', comp: 'StockOutManage' },
        { key: 'wh-stock', label: '库存/流水', comp: 'DrugStock' },
        { key: 'wh-check', label: '盘点', comp: 'StockCheck' },
        { key: 'warehouse-def', label: '药库管理', comp: 'WarehouseDef' },
        { key: 'warehouse-rpt', label: '药库统计', comp: 'WarehouseReport' },
        { key: 'trf-mgr', label: '库存调拨', comp: 'TransferManage' },
        { key: 'price-adjust', label: '药品调价', comp: 'PriceAdjust' },
        { key: 'stock-ledger', label: '进销存台账', comp: 'DrugLedger' }
      ]
    },
    /* 2026-09 三模块基座(静态兑底菜单, 与 RbacInitializer 动态菜单同 key 同名):
     * 护士站(nurse.js)/治疗管理(treatment.js)/医技管理(medtech.js) */
    {
      group: '门诊护士站', children: [
        { key: 'nurse-pending', label: '待执行医嘱', comp: 'NursePending' },
        { key: 'nurse-skin-test', label: '皮试管理', comp: 'NurseSkinTest' },
        { key: 'nurse-infusion', label: '输液管理', comp: 'NurseInfusion' },
        { key: 'nurse-allergy', label: '过敏档案', comp: 'NurseAllergy' },
        { key: 'nurse-exec-log', label: '执行记录查询', comp: 'NurseExecLog' }
      ]
    },
    {
      group: '治疗管理', children: [
        { key: 'treatment-pending', label: '待执行治疗', comp: 'TreatmentPending' },
        { key: 'treatment-plan', label: '疗程管理', comp: 'TreatmentPlan' },
        { key: 'treatment-equip', label: '设备管理', comp: 'TreatmentEquipment' },
        { key: 'treatment-log', label: '治疗记录查询', comp: 'TreatmentLog' }
      ]
    },
    {
      group: '医技管理', children: [
        { key: 'medtech-specimen', label: '标本管理', comp: 'MedtechSpecimen' },
        { key: 'medtech-report', label: '报告工作站', comp: 'MedtechReport' },
        { key: 'medtech-critical', label: '危急值管理', comp: 'MedtechCritical' },
        { key: 'medtech-critical-rule', label: '危急值规则', comp: 'MedtechCriticalRule' },
        { key: 'medtech-report-query', label: '报告查询', comp: 'MedtechReportQuery' }
      ]
    }
  ];

  function findItem(key) {
    return findItemIn(MENU, key);
  }

  /* 在给定菜单结构中按键查找项(递归, 兼容任意层级目录子项) */
  function findItemIn(menu, key) {
    for (var i = 0; i < (menu || []).length; i++) {
      var m = menu[i];
      if (m.key === key) { return m; }
      if (m.children) {
        var hit = findItemIn(m.children, key);
        if (hit) { return hit; }
      }
    }
    return null;
  }

  /* 取菜单首个可渲染项的键(递归, 动态菜单加载后校正默认激活项) */
  function firstKey(menu) {
    for (var i = 0; i < (menu || []).length; i++) {
      var m = menu[i];
      if (m.key) { return m.key; }
      if (m.children && m.children.length) {
        var k = firstKey(m.children);
        if (k) { return k; }
      }
    }
    return 'dashboard';
  }

  /* 将后端菜单树节点({menuKey,menuName,comp,phase,children})归一为渲染结构(递归, 支持任意层级) */
  function normalizeMenu(nodes) {
    var out = [];
    (nodes || []).forEach(function (n) {
      if (n.children && n.children.length) {
        out.push({ group: n.menuName, children: normalizeMenu(n.children) });
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
      '      <div style="margin:-6px 0 12px 80px;"><el-checkbox v-model="rememberPwd">记住密码</el-checkbox><span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">默认不保存密码; 勾选后密码存于本机浏览器, 共享终端请勿勾选</span></div>',
      '      <el-button type="primary" style="width:100%" :loading="loading" @click="doLogin">登 录</el-button>',
      '      <div class="login-links"><span style="color:var(--yb-ink-2);">医院管理员：H42010000000 / admin / admin123</span></div>',
      '      <div class="login-links"><span style="color:var(--yb-ink-2);">平台超管(开通医院)：PLATFORM / superadmin / admin123</span></div>',
      '    </el-form>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ===== 递归菜单渲染组件(支持任意层级: 目录用 el-sub-menu 内嵌本组件, 叶子用 el-menu-item) =====
   * Element Plus 菜单靠 provide/inject 建立父子上下文, 跨本组件边界仍生效, 故嵌套安全;
   * 根节点为 <template v-for> 的 fragment(Vue3 多根), 输出直挂父 el-menu/el-sub-menu 的 <ul>。
   */
  var MenuNav = {
    name: 'MenuNav',
    props: { items: { type: Array, default: function () { return []; } } },
    template: [
      '<template v-for="m in items">',
      '  <el-menu-item v-if="!m.group" :key="m.key" :index="m.key">{{ m.label }}</el-menu-item>',
      '  <el-sub-menu v-else :key="m.group" :index="m.group">',
      '    <template #title><span>{{ m.group }}</span></template>',
      '    <menu-nav :items="m.children"></menu-nav>',
      '  </el-sub-menu>',
      '</template>'
    ].join('')
  };

  /* ===== 主布局 ===== */
  var AppLayout = {
    props: ['user'],
    emits: ['logout'],
    data: function () {
      return {
        activeKey: 'dashboard', menu: MENU,
        collapsed: localStorage.getItem('his-aside-collapsed') === '1',
        /* 固定开关: 默认不固定=菜单自动隐藏(2026-09); 固定后常驻展开不再自动隐藏 */
        pinned: localStorage.getItem('his-aside-pinned') === '1',
        hoverOpen: false
      };
    },
    computed: {
      /* 三态: 固定且未手动收起=常规流内展开; 否则仅留 40px 入口条;
       * 悬停(且未处于展开态)时菜单以浮层面板弹出, 不挤压内容区 */
      asideExpanded: function () { return this.pinned && !this.collapsed; },
      asideFloating: function () { return this.hoverOpen && !this.asideExpanded; },
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
      /* 顶栏头像圈取姓氏一字(中文姓名取首字; 无姓名则退到账号首字母) */
      avatarChar: function () {
        var u = this.user || {};
        var s = u.realName || u.username || '';
        return s.charAt(0).toUpperCase();
      },
      /* 可登录机构(多点执业): >1 时顶栏展示"切换机构" */
      allowedOrgs: function () { return (this.user || {}).allowedOrgs || []; }
    },
    methods: {
      onSelect: function (key) {
        this.activeKey = key;
        /* 自动隐藏模式下选完菜单即收回浮层, 避免面板持续遮挡内容区 */
        if (!this.asideExpanded) { this.hoverOpen = false; }
      },
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
      onAsideEnter: function () { this.hoverOpen = true; },
      onAsideLeave: function () { this.hoverOpen = false; },
      /* 固定/取消固定: 两种情况都回到"未手动收起"态, 固定后即常驻展开 */
      togglePin: function () {
        this.pinned = !this.pinned;
        this.collapsed = false;
        localStorage.setItem('his-aside-pinned', this.pinned ? '1' : '0');
        localStorage.setItem('his-aside-collapsed', '0');
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
      '      <span class="user" :data-avatar="avatarChar">{{ user.realName || user.username }}（{{ roleName }}）<span style="margin-left:4px;">▾</span></span>',
      '      <template #dropdown>',
      '        <el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu>',
      '      </template>',
      '    </el-dropdown>',
      '  </div>',
      '  <div class="layout-body">',
      '    <div class="layout-aside" :class="{\'is-collapsed\': !asideExpanded && !asideFloating, \'is-floating\': asideFloating}" @mouseenter="onAsideEnter" @mouseleave="onAsideLeave">',
      '      <div class="aside-bar">',
      /* 固定开关: 两态用形状区分而非仅靠颜色(用户反馈颜色不够明显)——
         未固定=倒向右倾的空心图钉(可滑走) + 钉板虚影; 已固定=正立实心图钉钉在横线上(钉住)。
         内联 SVG 手绘, 不走 CDN/图标包; 倒图钉用 SVG transform 属性旋转(避开 CSS transform-box 差异) */
      '        <el-button link size="small" class="aside-pin" :class="{\'is-on\':pinned}" :aria-label="pinned?\'取消固定菜单\':\'固定菜单\'" :title="pinned?\'当前: 已固定(菜单常驻) — 点击恢复自动隐藏\':\'当前: 未固定(菜单自动隐藏) — 点击固定常驻\'" @click="togglePin">',
      '          <svg v-if="pinned" class="pin-ico" viewBox="0 0 16 16" width="18" height="18" aria-hidden="true">',
      '            <path class="pin-head" d="M6.4 2.6h3.2l-.5 2.7 2.2 2.1H4.7l2.2-2.1z"></path>',
      '            <path class="pin-needle" d="M8 7.4v5.8"></path>',
      '            <path class="pin-board" d="M4 14.2h8"></path>',
      '          </svg>',
      '          <svg v-else class="pin-ico" viewBox="0 0 16 16" width="18" height="18" aria-hidden="true">',
      '            <g transform="rotate(24 8 13.2)">',
      '              <path class="pin-head" d="M6.4 2.6h3.2l-.5 2.7 2.2 2.1H4.7l2.2-2.1z"></path>',
      '              <path class="pin-needle" d="M8 7.4v5.8"></path>',
      '            </g>',
      '            <path class="pin-ghost" d="M3.6 14.2h8.8"></path>',
      '          </svg>',
      '        </el-button>',
      '        <el-button v-if="pinned" link size="small" :title="collapsed?\'展开菜单\':\'收起菜单\'" @click="toggleAside">{{ collapsed?"\u00bb":"\u00ab" }}</el-button>',
      '      </div>',
      /* 不用 v-show 隐藏: v-show 会触发 el-menu 内置 collapse 过渡, 展开后残留内联 width:0 裁掉全部菜单项;
         改由 CSS .layout-aside.is-collapsed .aside-menu-wrap{display:none} 控制显隐 */
      '      <div class="aside-menu-wrap">',
      '        <el-menu :default-active="activeKey" @select="onSelect">',
      '          <menu-nav :items="menu"></menu-nav>',
      '        </el-menu>',
      '      </div>',
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
  app.component('menu-nav', MenuNav);
  app.mount('#app');
})();
