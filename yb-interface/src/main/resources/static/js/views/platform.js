/* 平台管理: 医院信息 + 用户管理 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ============ 医院信息 / 医保配置 ============ */
  HIS.views.TenantInfo = {
    data: function () {
      return {
        loading: false,
        saving: false,
        scope: 'org',
        orgName: '',
        inherited: {},
        form: { mockEnabled: 1, status: 1 }
      };
    },
    created: function () { this.load(); },
    computed: {
      /* 仅牵头机构管理员/平台超管可切换并维护医共体(租户)默认配置 */
      canTenantScope: function () {
        var u = HIS.getUser() || {};
        return HIS.isLead() || u.role === 'SUPER_ADMIN';
      }
    },
    methods: {
      inhPh: function (key, def) {
        var v = (this.inherited || {})[key];
        return v ? ('留空继承医共体默认: ' + v) : def;
      },
      onScopeChange: function () { this.load(); },
      load: function () {
        var vm = this;
        vm.loading = true;
        var url = vm.scope === 'org' ? '/api/sys/org/yb-config' : '/api/sys/tenant/current';
        HIS.get(url)
          .then(function (d) {
            if (!d) { return; }
            if (vm.scope === 'org') {
              vm.inherited = d.inherited || {};
              vm.orgName = d.orgName || '';
              vm.form = Object.assign({}, d, { contact: d.leader });
            } else {
              vm.inherited = {};
              vm.orgName = '';
              vm.form = d;
            }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      save: function () {
        var vm = this;
        vm.saving = true;
        if (vm.scope === 'org') {
          var body = Object.assign({}, vm.form, { leader: vm.form.contact });
          HIS.put('/api/sys/org/yb-config', body)
            .then(function () { HIS.notifySuccess('已保存本机构配置'); return vm.load(); })
            .catch(HIS.notifyError)
            .finally(function () { vm.saving = false; });
        } else {
          HIS.put('/api/sys/tenant/current', vm.form)
            .then(function () { HIS.notifySuccess('已保存医共体默认配置'); return vm.load(); })
            .catch(HIS.notifyError)
            .finally(function () { vm.saving = false; });
        }
      }
    },
    template: [
      '<div class="page-card" v-loading="loading">',
      '  <div class="page-title">医院信息 / 医保接口配置<span v-if="scope===\'org\' && orgName" style="font-size:13px;color:#909399;font-weight:normal;margin-left:8px;">当前机构: {{ orgName }}</span></div>',
      '  <div class="toolbar" v-if="canTenantScope" style="margin-bottom:10px;">',
      '    <el-radio-group v-model="scope" size="small" @change="onScopeChange">',
      '      <el-radio-button label="org">本机构</el-radio-button>',
      '      <el-radio-button label="tenant">医共体默认</el-radio-button>',
      '    </el-radio-group>',
      '  </div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px;"',
      '    :title="scope===\'org\' ? \'此处维护本机构(当前登录机构)的医保对接参数; 留空项运行时继承医共体默认。开启“模拟平台”后所有医保交易走本地Mock。\' : \'此处维护医共体(租户)级默认医保参数, 各机构留空时继承; 仅牵头机构管理员/平台超管可改。\'"></el-alert>',
      '  <el-form :model="form" label-width="140px" size="default">',
      '    <el-divider content-position="left">基本信息</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="医院登录码"><el-input v-model="form.tenantCode" disabled></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="医共体(租户)名称"><el-input v-model="form.tenantName" disabled></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contact"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="医院地址"><el-input v-model="form.address"></el-input></el-form-item></el-col>',
      '    </el-row>',
      '    <el-divider content-position="left">医保机构参数</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="定点医药机构编号"><el-input v-model="form.fixmedinsCode" :placeholder="inhPh(\'fixmedinsCode\',\'fixmedins_code\')"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="定点医药机构名称"><el-input v-model="form.fixmedinsName" :placeholder="inhPh(\'fixmedinsName\',\'默认同机构名称\')"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="参保地医保区划"><el-input v-model="form.insuplcAdmdvs" :placeholder="inhPh(\'insuplcAdmdvs\',\'insuplc_admdvs\')"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="就诊地医保区划"><el-input v-model="form.mdtrtareaAdmvs" :placeholder="inhPh(\'mdtrtareaAdmvs\',\'mdtrtarea_admvs\')"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="接口版本号"><el-input v-model="form.infver" :placeholder="inhPh(\'infver\',\'\')"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="接收系统编码"><el-input v-model="form.recerSysCode" :placeholder="inhPh(\'recerSysCode\',\'\')"></el-input></el-form-item></el-col>',
      '    </el-row>',
      '    <el-divider content-position="left">经办人与安全</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="经办人类别"><el-input v-model="form.opterType" placeholder="opter_type"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="经办人编号"><el-input v-model="form.opter" placeholder="opter"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="经办人姓名"><el-input v-model="form.opterName" placeholder="opter_name"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="签章编号"><el-input v-model="form.signNo" placeholder="sign_no"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="加密方式"><el-input v-model="form.encType" placeholder="enc_type"></el-input></el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="SM2私钥"><el-input v-model="form.sm2PrivateKey" placeholder="留空或含*则不修改"></el-input></el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="SM2公钥"><el-input v-model="form.sm2PublicKey"></el-input></el-form-item></el-col>',
      '    </el-row>',
      '    <el-divider content-position="left">接口地址与模式</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="24"><el-form-item label="医保接口地址"><el-input v-model="form.apiUrl" placeholder="api_url"></el-input></el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="文件下载地址"><el-input v-model="form.fileDownloadUrl" placeholder="file_download_url"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="模拟平台模式">',
      '        <el-switch v-model="form.mockEnabled" :active-value="1" :inactive-value="0" active-text="模拟" inactive-text="真实"></el-switch>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '    <el-form-item>',
      '      <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>',
      '      <el-button @click="load">重新加载</el-button>',
      '    </el-form-item>',
      '  </el-form>',
      '</div>'
    ].join('\n')
  };

  /* ============ 用户管理 ============ */
  HIS.views.UserManage = {
    data: function () {
      return {
        loading: false,
        list: [],
        page: 1,
        size: 20,
        roles: [],
        orgs: [],
        depts: [],
        staffs: [],
        menuMap: {},
        roleMenuNames: [],
        roleIsAll: false,
        deptScopeArr: [],
        activeTab: 'account',
        lead: HIS.isLead(),
        filterOrg: null,
        keyword: '',
        filterRole: '',
        filterStatus: null,
        dialogVisible: false,
        editing: false,
        form: this.emptyForm()
      };
    },
    created: function () {
      /* 非牵头: 锁定本机构(后端亦强制), 只读 */
      if (!this.lead) { this.filterOrg = (HIS.getUser() || {}).orgId || null; }
      this.loadMeta(); this.load();
    },
    computed: {
      /* 查询过滤(前端即时): 关键字命中账号/姓名/电话, 角色/状态精确匹配; 无条件时返回全量 */
      filteredList: function () {
        var vm = this;
        var kw = (vm.keyword || '').trim().toLowerCase();
        var st = (vm.filterStatus === '' || vm.filterStatus == null) ? null : vm.filterStatus;
        if (!kw && !vm.filterRole && st === null) { return vm.list || []; }
        return (vm.list || []).filter(function (r) {
          if (kw && String(r.username || '').toLowerCase().indexOf(kw) < 0
            && String(r.realName || '').toLowerCase().indexOf(kw) < 0
            && String(r.phone || '').indexOf(kw) < 0) { return false; }
          if (vm.filterRole && !vm.roleMatch(r, vm.filterRole)) { return false; }
          if (st !== null && r.status !== st) { return false; }
          return true;
        });
      },
      /* 当前页数据(客户端分页) */
      pagedList: function () {
        var s = (this.page - 1) * this.size;
        return this.filteredList.slice(s, s + this.size);
      },
      /* 科室下拉选项: 带机构前缀消歧(医共体多机构) */
      deptOptions: function () {
        var vm = this;
        return (vm.depts || []).map(function (d) {
          var on = vm.orgName(d.orgId);
          return { id: d.id, label: (on && on !== '-' ? on + ' / ' : '') + d.deptName };
        });
      },
      /* 授权科室分组选项: 按科室大类分组(门诊/住院/病区护理/医技/行政后勤);
       * 大类节点(level=1)仅作分组容器不可选, 三级窗口/诊室缩进展示 */
      deptGroups: function () {
        var vm = this;
        var groups = {};
        var order = [];
        (vm.depts || []).forEach(function (d) {
          if (d.deptLevel === 1) { return; }
          var cat = d.deptCategory || '其他';
          if (!groups[cat]) { groups[cat] = []; order.push(cat); }
          var prefix = d.deptLevel === 3 ? '　└ ' : '';
          var on = vm.orgName(d.orgId);
          groups[cat].push({ id: d.id, label: prefix + d.deptName + (on && on !== '-' ? ' (' + on + ')' : '') });
        });
        return order.map(function (c) { return { category: c, options: groups[c] }; });
      }
    },
    methods: {
      emptyForm: function () {
        return { id: null, username: '', password: '', realName: '', role: 'DOCTOR', roleId: null, orgId: null, staffId: null, deptId: null, deptScope: '', phone: '', status: 1, loginOrgIds: [] };
      },
      loadMeta: function () {
        var vm = this;
        var os = vm.filterOrg ? ('?orgId=' + vm.filterOrg) : '';
        HIS.loadRoles()
          .then(function (d) { vm.roles = d || []; })
          .catch(function () { vm.roles = HIS.ROLES.map(function (r) { return { value: r.value, label: r.label }; }); });
        HIS.get('/api/sys/org/tree')
          .then(function (d) { vm.orgs = HIS.flattenOrgs(d || []); })
          .catch(function () { vm.orgs = []; });
        HIS.get('/api/his/dept/enabled' + os).then(function (d) { vm.depts = d || []; }).catch(function () { vm.depts = []; });
        HIS.get('/api/his/staff/list' + os).then(function (d) { vm.staffs = d || []; }).catch(function () { vm.staffs = []; });
        HIS.get('/api/sys/menu/tree').then(function (d) { vm.menuMap = vm.buildMenuMap(d || [], {}); }).catch(function () { vm.menuMap = {}; });
      },
      onOrgChange: function () { this.loadMeta(); this.load(); },
      onQueryChange: function () { this.page = 1; },
      /* 角色匹配: 兼容 roleId(自定义角色)与 role(内置枚举) */
      roleMatch: function (row, v) {
        if (!v) { return true; }
        for (var i = 0; i < this.roles.length; i++) {
          if (this.roles[i].value === v) { return row.roleId === this.roles[i].id || row.role === v; }
        }
        return row.role === v;
      },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSizeChange: function (sz) { this.size = sz; this.page = 1; },
      onPageChange: function (p) { this.page = p; },
      roleDisplay: function (row) {
        for (var i = 0; i < this.roles.length; i++) {
          if (this.roles[i].id === row.roleId || this.roles[i].value === row.role) { return this.roles[i].label; }
        }
        return HIS.roleLabel(row.role);
      },
      orgName: function (id) {
        for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } }
        return '-';
      },
      /* 归属机构变更: 确保其始终在可登录机构集内(默认可登录机构不可取消) */
      onHomeOrgChange: function (val) {
        if (!this.form.loginOrgIds) { this.form.loginOrgIds = []; }
        if (val && this.form.loginOrgIds.indexOf(val) < 0) { this.form.loginOrgIds.push(val); }
      },
      /* 可登录机构列显示(名称顿号连接) */
      loginOrgText: function (row) {
        var vm = this;
        var ids = row.loginOrgIds && row.loginOrgIds.length ? row.loginOrgIds : (row.orgId ? [row.orgId] : []);
        if (!ids.length) { return '-'; }
        return ids.map(function (id) { return vm.orgName(id); }).join('、');
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/sys/user/list' + (vm.filterOrg ? ('?orgId=' + vm.filterOrg) : ''))
          .then(function (d) { vm.list = d || []; vm.page = 1; })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate: function () {
        this.editing = false;
        this.form = this.emptyForm();
        this.deptScopeArr = [];
        this.activeTab = 'account';
        this.roleMenuNames = []; this.roleIsAll = false;
        this.dialogVisible = true;
      },
      openEdit: function (row) {
        this.editing = true;
        this.form = { id: row.id, username: row.username, password: '', realName: row.realName, role: row.role, roleId: row.roleId, orgId: row.orgId, staffId: row.staffId, deptId: row.deptId, deptScope: row.deptScope || '', phone: row.phone, status: row.status == null ? 1 : row.status, loginOrgIds: (row.loginOrgIds && row.loginOrgIds.length) ? row.loginOrgIds.slice() : (row.orgId ? [row.orgId] : []) };
        this.deptScopeArr = row.deptScope ? String(row.deptScope).split(',').filter(function (x) { return x !== ''; }).map(function (x) { return Number(x); }) : [];
        this.activeTab = 'account';
        this.loadRoleMenus(row.roleId);
        this.dialogVisible = true;
      },
      submit: function () {
        var vm = this;
        if (!vm.editing && (!vm.form.username || !vm.form.password)) {
          ElementPlus.ElMessage.warning('账号与密码不能为空'); return;
        }
        vm.form.deptScope = (vm.deptScopeArr || []).join(',');
        /* 归属机构(默认可登录机构)强制纳入可登录机构集 */
        if (vm.form.orgId && (vm.form.loginOrgIds || []).indexOf(vm.form.orgId) < 0) {
          vm.form.loginOrgIds = (vm.form.loginOrgIds || []).concat([vm.form.orgId]);
        }
        var p = vm.editing ? HIS.put('/api/sys/user', vm.form) : HIS.post('/api/sys/user', vm.form);
        p.then(function () {
          HIS.notifySuccess(vm.editing ? '修改成功' : '新增成功');
          vm.dialogVisible = false; vm.load();
        }).catch(HIS.notifyError);
      },
      resetPwd: function (row) {
        ElementPlus.ElMessageBox.prompt('请输入新密码(至少6位)', '重置密码 - ' + row.username, {
          inputType: 'password',
          inputValidator: function (v) { return (v && v.length >= 6) ? true : '密码至少6位'; }
        }).then(function (r) {
          return HIS.post('/api/sys/user/' + row.id + '/reset-password?password=' + encodeURIComponent(r.value));
        }).then(function () { HIS.notifySuccess('密码已重置'); }).catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      toggleStatus: function (row) {
        var target = row.status === 1 ? 0 : 1;
        HIS.post('/api/sys/user/' + row.id + '/status?status=' + target)
          .then(function () { HIS.notifySuccess(target === 1 ? '已启用' : '已停用'); row.status = target; })
          .catch(HIS.notifyError);
      },
      remove: function (row) {
        HIS.del('/api/sys/user/' + row.id)
          .then(function () { HIS.notifySuccess('已删除'); this.load(); }.bind(this))
          .catch(HIS.notifyError);
      },
      roleLabel: function (c) { return HIS.roleLabel(c); },
      /* 菜单树扁平为 id→menuName 映射(供角色菜单预览) */
      buildMenuMap: function (nodes, map) {
        var vm = this;
        (nodes || []).forEach(function (n) {
          map[n.id] = n.menuName;
          if (n.children && n.children.length) { vm.buildMenuMap(n.children, map); }
        });
        return map;
      },
      /* 加载指定角色已授权菜单(只读预览): allMenus 角色显示"全部菜单" */
      loadRoleMenus: function (roleId) {
        var vm = this;
        vm.roleMenuNames = []; vm.roleIsAll = false;
        if (!roleId) { return; }
        var role = null;
        for (var i = 0; i < vm.roles.length; i++) { if (vm.roles[i].id === roleId) { role = vm.roles[i]; break; } }
        if (role && role.allMenus === 1) { vm.roleIsAll = true; return; }
        HIS.get('/api/sys/role/' + roleId + '/menus').then(function (ids) {
          vm.roleMenuNames = (ids || []).map(function (id) { return vm.menuMap[id]; }).filter(function (n) { return !!n; });
        }).catch(function () { vm.roleMenuNames = []; });
      },
      deptName: function (id) {
        for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } }
        return id ? ('#' + id) : '-';
      },
      staffName: function (id) {
        for (var i = 0; i < this.staffs.length; i++) { if (this.staffs[i].id === id) { return this.staffs[i].staffName + '(' + this.staffs[i].staffNo + ')'; } }
        return id ? ('#' + id) : '-';
      },
      scopeText: function (row) {
        var s = row.deptScope ? String(row.deptScope).split(',').filter(function (x) { return x !== ''; }) : [];
        return s.length ? (s.length + ' 个科室') : '仅主属科室';
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">用户管理</div>',
      '  <el-alert v-if="!lead" type="warning" :closable="false" show-icon style="margin-bottom:10px;" title="非牵头机构: 仅展示本机构用户, 只读不可维护。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterOrg" placeholder="全部机构" clearable filterable :disabled="!lead" style="width:200px" @change="onOrgChange"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="账号/姓名/电话" clearable style="width:170px" @input="onQueryChange" @clear="onQueryChange"></el-input>',
      '    <el-select v-model="filterRole" placeholder="全部角色" clearable style="width:130px" @change="onQueryChange"><el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value"></el-option></el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:110px" @change="onQueryChange"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新增用户</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ filteredList.length }} 个账号</span>',
      '  </div>',
      '  <el-table :data="pagedList" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="username" label="账号" width="140"></el-table-column>',
      '    <el-table-column prop="realName" label="姓名" width="120"></el-table-column>',
      '    <el-table-column label="角色" width="130"><template #default="s"><el-tag size="small">{{ roleDisplay(s.row) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="归属机构" min-width="160" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column label="可登录机构" min-width="180" show-overflow-tooltip><template #default="s">{{ loginOrgText(s.row) }}</template></el-table-column>',
      '    <el-table-column prop="phone" label="联系电话" width="140"></el-table-column>',
      '    <el-table-column label="关联职工" width="150" show-overflow-tooltip><template #default="s">{{ staffName(s.row.staffId) }}</template></el-table-column>',
      '    <el-table-column label="主属科室" width="120" show-overflow-tooltip><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column label="授权科室" width="110"><template #default="s"><el-tag size="small" :type="s.row.deptScope?\'warning\':\'info\'">{{ scopeText(s.row) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="状态" width="90"><template #default="s">',
      '      <el-tag :type="s.row.status === 1 ? \'success\' : \'info\'" size="small">{{ s.row.status === 1 ? "启用" : "停用" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" width="230" fixed="right">',
      '      <template #default="s">',
      '        <el-button v-if="lead" link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button v-if="lead" link type="warning" @click="resetPwd(s.row)">重置密码</el-button>',
      '        <el-button v-if="lead" link :type="s.row.status === 1 ? \'info\' : \'success\'" @click="toggleStatus(s.row)">{{ s.row.status === 1 ? "停用" : "启用" }}</el-button>',
      '        <el-popconfirm v-if="lead" title="确认删除该账号？" @confirm="remove(s.row)">',
      '          <template #reference><el-button link type="danger">删除</el-button></template>',
      '        </el-popconfirm>',
      '        <span v-if="!lead" style="color:#909399;font-size:12px;">只读</span>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:10px;" background :current-page="page" :page-size="size" :page-sizes="[10,20,50,100]" :total="filteredList.length" layout="total, sizes, prev, pager, next, jumper" @size-change="onSizeChange" @current-change="onPageChange"></el-pagination>',
      '  <el-dialog v-model="dialogVisible" :title="editing ? \'编辑用户\' : \'新增用户\'" width="640px">',
      '    <el-tabs v-model="activeTab">',
      '      <el-tab-pane label="账号信息" name="account">',
      '        <el-form :model="form" label-width="90px">',
      '          <el-form-item label="账号"><el-input v-model="form.username" :disabled="editing" placeholder="登录账号"></el-input></el-form-item>',
      '          <el-form-item label="密码" v-if="!editing"><el-input v-model="form.password" type="password" show-password placeholder="至少6位"></el-input></el-form-item>',
      '          <el-form-item label="姓名"><el-input v-model="form.realName"></el-input></el-form-item>',
      '          <el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item>',
      '          <el-form-item label="归属机构"><el-select v-model="form.orgId" style="width:100%" clearable filterable placeholder="选择归属机构(行政所属、默认可登录)" @change="onHomeOrgChange"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="可登录机构"><el-select v-model="form.loginOrgIds" multiple clearable filterable style="width:100%" placeholder="多点执业: 可登录的多个机构(归属机构默认已含且不可取消)"><el-option v-for="o in orgs" :key="o.id" :label="o.label + (o.id===form.orgId?\' (归属·默认)\':\'\')" :value="o.id" :disabled="o.id===form.orgId"></el-option></el-select></el-form-item>',
      '          <el-form-item label=" "><span style="color:#909399;font-size:12px;">归属机构为默认可登录机构, 始终保留; 可再勾选本医共体内其他机构以支持多点执业。登录后默认进入归属机构, 可在顶部"切换机构"。仅本医共体(同租户)机构可选。</span></el-form-item>',
      '          <el-form-item label="关联职工"><el-select v-model="form.staffId" style="width:100%" clearable filterable placeholder="关联 his_staff(操作留痕/电子签名)"><el-option v-for="st in staffs" :key="st.id" :label="st.staffName + \'(\' + st.staffNo + \')\'" :value="st.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="状态" v-if="editing"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="角色权限" name="role">',
      '        <el-form :model="form" label-width="90px">',
      '          <el-form-item label="角色"><el-select v-model="form.roleId" style="width:100%" placeholder="选择角色" @change="loadRoleMenus(form.roleId)"><el-option v-for="r in roles" :key="r.id || r.value" :label="r.label" :value="r.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="菜单权限">',
      '            <div v-if="roleIsAll" style="line-height:24px;"><el-tag type="danger" size="small">全部菜单(管理员角色)</el-tag></div>',
      '            <div v-else-if="roleMenuNames.length" style="line-height:28px;"><el-tag v-for="(n,i) in roleMenuNames" :key="i" size="small" style="margin:2px 4px 2px 0;">{{ n }}</el-tag></div>',
      '            <span v-else style="color:#909399;font-size:12px;">尚未选择角色, 或该角色未分配菜单</span>',
      '          </el-form-item>',
      '        </el-form>',
      '        <el-alert type="info" :closable="false" title="菜单权限(功能权限)由「系统管理 → 角色权限」统一授权, 此处为只读预览。"></el-alert>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="科室权限" name="dept">',
      '        <el-form :model="form" label-width="90px">',
      '          <el-form-item label="主属科室"><el-select v-model="form.deptId" style="width:100%" clearable filterable placeholder="该账号编制主属科室"><el-option-group v-for="g in deptGroups" :key="g.category" :label="g.category"><el-option v-for="d in g.options" :key="d.id" :label="d.label" :value="d.id"></el-option></el-option-group></el-select></el-form-item>',
      '          <el-form-item label="授权科室"><el-select v-model="deptScopeArr" multiple clearable filterable style="width:100%" placeholder="可登录/执业的多个科室(数据权限); 留空=仅主属科室"><el-option-group v-for="g in deptGroups" :key="g.category" :label="g.category"><el-option v-for="d in g.options" :key="d.id" :label="d.label" :value="d.id"></el-option></el-option-group></el-select></el-form-item>',
      '          <el-form-item label=" "><span style="color:#909399;font-size:12px;">已授权 {{ deptScopeArr.length }} 个科室。一个医生可登录多个住院科室、护士可进多个病区、药师可进多个药房。科室权限决定账号可见的就诊/患者数据范围(数据权限), 与角色菜单权限(功能权限)相互独立。</span></el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '    <template #footer>',
      '      <el-button @click="dialogVisible = false">取消</el-button>',
      '      <el-button type="primary" @click="submit">确定</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ============ 医院管理(平台超级管理员专属: 跨租户开通/停用医院) ============ */
  HIS.views.HospitalManage = {
    data: function () {
      return { loading: false, saving: false, list: [], dlg: false, form: this.empty() };
    },
    created: function () { this.load(); },
    methods: {
      empty: function () {
        return {
          tenantCode: '', tenantName: '', contact: '', phone: '', address: '',
          fixmedinsCode: '', fixmedinsName: '', mdtrtareaAdmvs: '', insuplcAdmdvs: '',
          apiUrl: '', mockEnabled: 1,
          adminUsername: '', adminPassword: '', adminName: ''
        };
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/sys/tenant/list')
          .then(function (d) { vm.list = d || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate: function () { this.form = this.empty(); this.dlg = true; },
      submit: function () {
        var vm = this;
        var f = vm.form;
        if (!f.tenantCode || !f.tenantName || !f.adminUsername || !f.adminPassword) {
          ElementPlus.ElMessage.warning('请填写医院码、医院名称、初始管理员账号与密码'); return;
        }
        vm.saving = true;
        HIS.post('/api/sys/tenant/open', f)
          .then(function () {
            HIS.notifySuccess('医院开通成功, 已即时生效(可用初始管理员登录)');
            vm.dlg = false; vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.saving = false; });
      },
      toggleStatus: function (row) {
        var target = row.status === 1 ? 0 : 1;
        HIS.post('/api/sys/tenant/' + row.id + '/status?status=' + target)
          .then(function () { HIS.notifySuccess(target === 1 ? '已启用' : '已停用'); row.status = target; })
          .catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card" v-loading="loading">',
      '  <div class="page-title">医院管理<span style="font-size:12px;color:#909399;font-weight:normal;margin-left:8px;">平台超级管理员 · 跨租户开通与管理</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;" title="新医院由此统一开通: 建租户 + 医保配置 + 默认机构 + 初始管理员账号, 开通即时生效。之后由该医院管理员登录自行维护科室/职工并分配用户权限。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate">开通新医院</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 家医院</span>',
      '  </div>',
      '  <el-table :data="list" border stripe size="small" style="width:100%">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="tenantCode" label="医院码" width="150"></el-table-column>',
      '    <el-table-column prop="tenantName" label="医共体(租户)名称" min-width="180" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="fixmedinsCode" label="定点机构编号" width="140"></el-table-column>',
      '    <el-table-column prop="mdtrtareaAdmvs" label="医保区划" width="100"></el-table-column>',
      '    <el-table-column prop="contact" label="联系人" width="90"></el-table-column>',
      '    <el-table-column prop="phone" label="联系电话" width="130"></el-table-column>',
      '    <el-table-column label="平台" width="80"><template #default="s"><el-tag size="small" :type="s.row.mockEnabled===1?\'warning\':\'success\'">{{ s.row.mockEnabled===1?"模拟":"真实" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="90" fixed="right"><template #default="s"><el-button link :type="s.row.status===1?\'info\':\'success\'" @click="toggleStatus(s.row)">{{ s.row.status===1?"停用":"启用" }}</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" title="开通新医院" width="720px" top="6vh">',
      '    <el-form :model="form" label-width="140px" size="small">',
      '      <el-divider content-position="left">医院基本信息</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="医院码" required><el-input v-model="form.tenantCode" placeholder="唯一登录码, 如 H42010000000"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医共体(租户)名称" required><el-input v-model="form.tenantName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contact"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="医院地址"><el-input v-model="form.address"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">医保接口配置</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="定点机构编号"><el-input v-model="form.fixmedinsCode" placeholder="fixmedins_code"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="定点机构名称"><el-input v-model="form.fixmedinsName" placeholder="默认同医院名称"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="就诊地医保区划"><el-input v-model="form.mdtrtareaAdmvs" placeholder="如 420100"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="参保地医保区划"><el-input v-model="form.insuplcAdmdvs" placeholder="如 420100"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="医保接口地址"><el-input v-model="form.apiUrl" placeholder="api_url, 留空用默认"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="模拟平台"><el-switch v-model="form.mockEnabled" :active-value="1" :inactive-value="0" active-text="模拟" inactive-text="真实"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">初始管理员账号</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="管理员账号" required><el-input v-model="form.adminUsername" placeholder="该医院首次登录账号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="管理员密码" required><el-input v-model="form.adminPassword" type="password" show-password placeholder="至少6位"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="管理员姓名"><el-input v-model="form.adminName"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-alert type="success" :closable="false" title="开通后系统自动创建默认县级机构并绑定该管理员, 即时生效, 无需重启。"></el-alert>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dlg=false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submit">开通</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
