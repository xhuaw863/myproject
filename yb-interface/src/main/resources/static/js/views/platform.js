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
        return HIS.isLead() || HIS.hasRole('SUPER_ADMIN');
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
      '  <div class="page-title">医院信息 / 医保接口配置<span v-if="scope===\'org\' && orgName" style="font-size:13px;color:var(--yb-ink-2);font-weight:normal;margin-left:8px;">当前机构: {{ orgName }}</span></div>',
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
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        list: [],
        total: 0,
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
        /* 左栏机构树(与科室管理同构): 过滤关键字/折叠态/收缩态/就绪标记 */
        orgKw: '',
        orgFolded: {},
        orgsCollapsed: (function () { try { return localStorage.getItem('his.userOrgsCollapsed') === '1'; } catch (e) { return false; } })(),
        listLoaded: false,
        /* 左树角标数据源: 服务端 /org-counts 返回的 机构id→启用数 (全作用域, 与右栏筛选无关) */
        counts: null,
        /* 选机构时是否级联含下级机构账号(客户端子树过滤; 仅牵头机构生效) */
        withSubOrgs: false,
        /* 显示模式: 默认由租户参数 system.list_default_paged 控制, 用户手动切换后以本地偏好为准 */
        paged: HIS.pagedDefault('userPaged2'),
        /* 列设置: 低频列(可登录机构/关联职工/授权科室)默认隐藏 */
        colDefs: [
          { key: 'role', label: '角色' }, { key: 'homeOrg', label: '归属机构' }, { key: 'loginOrg', label: '可登录机构' },
          { key: 'phone', label: '联系电话' }, { key: 'staff', label: '关联职工' }, { key: 'dept', label: '主属科室' },
          { key: 'scope', label: '授权科室' }, { key: 'status', label: '状态' }
        ],
        colOff: (function () {
          try { return JSON.parse(localStorage.getItem('his.userCols') || '{"loginOrg":1,"staff":1,"scope":1}'); } catch (e) { return { loginOrg: 1, staff: 1, scope: 1 }; }
        })(),
        dialogVisible: false,
        editing: false,
        form: this.emptyForm()
      };
    },
    created: function () {
      var vm = this;
      /* 非牵头: 锁定本机构(后端亦强制), 只读 */
      if (!vm.lead) { vm.filterOrg = (HIS.getUser() || {}).orgId || null; }
      vm.loadMeta(); vm.load(); vm.loadCounts(); vm.loadStatic();
      /* 首屏默认收缩到二级(与科室管理同口径); 机构树仅在进页/手动刷新时重拉(#6) */
      vm.loadOrgs().then(function () {
        if (!Object.keys(vm.orgFolded).length) { vm.collapseOrgToLevel(2); }
      });
    },
    computed: {
      /* 服务端分页: list 即当前页(或全量模式一次性拉取的结果), 直接渲染 */
      pagedList: function () { return this.list; },
      /* 左栏机构树是否处于过滤态(强制展平 + caret 置灰) */
      searching: function () { return !!String(this.orgKw || '').trim(); },
      /* 机构父链索引(共享缓存): 前序展平中向前最近更小 orgLevel 即父机构 */
      orgParentIdx: function () {
        var orgs = this.orgs; var par = {};
        for (var i = 0; i < orgs.length; i++) {
          for (var j = i - 1; j >= 0; j--) { if ((orgs[j].orgLevel || 1) < (orgs[i].orgLevel || 1)) { par[orgs[i].id] = orgs[j].id; break; } }
        }
        return par;
      },
      /* 机构/科室/职工 id→显示名 索引(随源数组变化才重算): 单元格查表 O(1), 替代逐行线性扫描 */
      orgNameMap: function () { var m = {}; this.orgs.forEach(function (o) { m[o.id] = String(o.label).trim(); }); return m; },
      deptNameMap: function () { var m = {}; this.depts.forEach(function (d) { m[d.id] = d.deptName; }); return m; },
      staffNameMap: function () { var m = {}; this.staffs.forEach(function (s) { m[s.id] = s.staffName + '(' + s.staffNo + ')'; }); return m; },
      /* 机构启用账号数角标(含下级机构累计): 数据源改为服务端 /org-counts(全作用域, 与右栏筛选无关) */
      orgCountMap: function () {
        var vm = this;
        if (!vm.counts) { return null; }
        var direct = vm.counts; var total = 0;
        Object.keys(direct).forEach(function (k) { total += Number(direct[k]) || 0; });
        var orgs = vm.orgs; var sub = {}; var par = vm.orgParentIdx;
        orgs.forEach(function (o) { sub[o.id] = Number(direct[o.id] != null ? direct[o.id] : direct[String(o.id)]) || 0; });
        for (var k = orgs.length - 1; k >= 0; k--) { var pid = par[orgs[k].id]; if (pid != null && sub[pid] != null) { sub[pid] += sub[orgs[k].id]; } }
        var m = { all: total };
        orgs.forEach(function (o) { m['org-' + o.id] = sub[o.id] || 0; });
        return m;
      },
      /* 左栏机构列表(与科室管理同构): 过滤态强制展平保留命中祖先链+选中项; 否则按 orgFolded 跳子树 */
      visibleOrgs: function () {
        var vm = this; var out = [];
        var kw = String(vm.orgKw || '').trim();
        if (kw) {
          var byId = {};
          vm.orgs.forEach(function (o) { byId[o.id] = o; });
          var par = vm.orgParentIdx;
          var keep = {};
          var markUp = function (o) { var c = o; while (c && !keep[c.id]) { keep[c.id] = 1; c = par[c.id] ? byId[par[c.id]] : null; } };
          vm.orgs.forEach(function (o) { if (HIS.kwMatch(o, kw, ['label', 'orgCode', 'pyCode'])) { markUp(o); } });
          if (vm.filterOrg && byId[vm.filterOrg]) { markUp(byId[vm.filterOrg]); }
          vm.orgs.forEach(function (o) { if (keep[o.id]) { out.push(o); } });
          return out;
        }
        var skipping = null;
        vm.orgs.forEach(function (o) {
          if (skipping !== null) {
            if ((o.depth || 0) > skipping) { return; }
            skipping = null;
          }
          out.push(o);
          if (o.hasKids && vm.orgFolded[o.id]) { skipping = o.depth || 0; }
        });
        return out;
      },
      /* 右栏计数文案: 列表含停用, 与左栏"启用"角标口径不同, 明示避免误解 */
      countHint: function () {
        var s = '共 ' + this.total + ' 个账号';
        return (this.filterStatus === '' || this.filterStatus == null) ? s + '（含停用 · 左栏角标为启用数）' : s;
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
          groups[cat].push({ id: d.id, label: prefix + d.deptName + (on && on !== '-' ? ' (' + on + ')' : ''), pyCode: d.pyCode, abbrCode: d.abbrCode });
        });
        return order.map(function (c) { return { category: c, options: groups[c] }; });
      }
    },
    methods: {
      emptyForm: function () {
        return { id: null, username: '', password: '', realName: '', role: 'DOCTOR', roleId: null, roleIds: [], orgId: null, staffId: null, deptId: null, deptScope: '', phone: '', status: 1, loginOrgIds: [] };
      },
      loadMeta: function () {
        var vm = this;
        /* 候选与列表口径对齐(#2): 牵头拉全量(级联含下级时列表会出现下级账号, 精确拉会致 #id 回显与编辑候选缺失); 非牵头后端已限本机构 */
        var os = vm.lead ? '' : ('?orgId=' + vm.filterOrg);
        HIS.loadRoles()
          .then(function (d) { vm.roles = d || []; })
          .catch(function () { vm.roles = HIS.ROLES.map(function (r) { return { value: r.value, label: r.label }; }); });
        HIS.get('/api/his/dept/enabled' + os).then(function (d) { vm.depts = d || []; }).catch(function () { vm.depts = []; });
        /* 职工候选(数千行)仅弹窗"关联职工"列/下拉需要, 移出首屏: 由 loadStaffs 在打开对话框时懒加载 */
      },
      /* 懒加载职工候选: 仅牵头可维护账号, 打开新增/编辑对话框(或启用关联职工列)时才拉, 不阻塞列表首屏 */
      loadStaffs: function () {
        var vm = this;
        if (vm._staffsLoading) { return; }
        vm._staffsLoading = true;
        var os = vm.lead ? '' : ('?orgId=' + vm.filterOrg);
        HIS.get('/api/his/staff/list' + os).then(function (d) { vm.staffs = d || []; })
          .catch(function () { vm.staffs = []; })
          .finally(function () { vm._staffsLoading = false; });
      },
      /* 静态元数据(菜单字典): 仅进页与手动刷新时拉取, 不随左树点选重复请求(#6) */
      loadStatic: function () {
        var vm = this;
        HIS.get('/api/sys/menu/tree').then(function (d) { vm.menuMap = vm.buildMenuMap(d || [], {}); }).catch(function () { vm.menuMap = {}; });
      },
      /* 左栏机构树数据源(可重放): 非牵头仅列本机构 */
      loadOrgs: function () {
        var vm = this;
        return HIS.get('/api/sys/org/tree').then(function (d) {
          vm.orgs = HIS.flattenOrgs(d || []);
          if (!vm.lead) { vm.orgs = vm.orgs.filter(function (o) { return o.id === vm.filterOrg; }); }
        }).catch(function () { vm.orgs = []; });
      },
      /* 机构子树 id 集合(含自身): 前序展平数组中向后取 depth 更深的连续段(客户端级联过滤用) */
      orgSubtreeSet: function (rootId) {
        var orgs = this.orgs; var idx = -1;
        for (var i = 0; i < orgs.length; i++) { if (orgs[i].id === rootId) { idx = i; break; } }
        var set = {}; set[rootId] = 1;
        if (idx < 0) { return set; }
        var rd = orgs[idx].depth || 0;
        for (var j = idx + 1; j < orgs.length; j++) { if ((orgs[j].depth || 0) <= rd) { break; } set[orgs[j].id] = 1; }
        return set;
      },
      /* 左栏点选机构: 回第1页并按新作用域重查(纯客户端过滤已退役) */
      selectOrg: function (id) {
        this.filterOrg = id; this.page = 1; this.load();
      },
      /* 刷新按钮: 重拉列表与全部元数据/静态树 */
      refresh: function () { this.loadMeta(); this.load(); this.loadCounts(); this.loadStatic(); this.loadOrgs(); },
      /* 左栏收缩/展开切换并持久化 */
      toggleOrgs: function () {
        this.orgsCollapsed = !this.orgsCollapsed;
        try { localStorage.setItem('his.userOrgsCollapsed', this.orgsCollapsed ? '1' : '0'); } catch (e) { }
      },
      toggleOrgFold: function (o) { this.orgFolded[o.id] = !this.orgFolded[o.id]; },
      /* 机构树标签高亮: 转义 HTML 后将命中子串包入 <mark>(大小写不敏感) */
      hl: function (text) {
        var t = String(text == null ? '' : text);
        var esc = function (s) { return s.replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); };
        var kw = String(this.orgKw || '').trim();
        if (!kw) { return esc(t); }
        var lower = t.toLowerCase(); var k = kw.toLowerCase(); var out = ''; var i = 0;
        for (;;) {
          var idx = lower.indexOf(k, i);
          if (idx < 0) { out += esc(t.slice(i)); break; }
          out += esc(t.slice(i, idx)) + '<mark class="kw-hit">' + esc(t.slice(idx, idx + kw.length)) + '</mark>';
          i = idx + kw.length;
        }
        return out;
      },
      /* 左栏机构悬停提示: 名称+编码+拼音 */
      orgTitle: function (o) {
        var parts = [String(o.label || '').trim()];
        if (o.orgCode) { parts.push('编码 ' + o.orgCode); }
        if (o.pyCode) { parts.push('拼音 ' + o.pyCode); }
        return parts.join(' / ');
      },
      /* 机构树批量展开/收缩到二级/到层级(搜索态强制展平, 先清关键字) */
      expandAllOrg: function () { this.orgKw = ''; this.orgFolded = {}; },
      collapseAllOrg: function () {
        this.orgKw = ''; var f = {}; this.orgs.forEach(function (o) { if (o.hasKids && (o.depth || 0) >= 1) { f[o.id] = true; } }); this.orgFolded = f;
      },
      collapseOrgToLevel: function (n) {
        this.orgKw = '';
        if (!n) { this.orgFolded = {}; return; }
        var cap = n - 1; var f = {};
        this.orgs.forEach(function (o) { if (o.hasKids && (o.depth || 0) >= cap) { f[o.id] = true; } });
        this.orgFolded = f;
      },
      /* 列设置: 勾选=显示(colOff 置0), 取消=隐藏(置1); 持久化 */
      colShow: function (key) { return !this.colOff[key]; },
      onColToggle: function (key, shown) { this.colOff[key] = shown ? 0 : 1; this.onColChange(); if (key === 'staff' && shown && !this.staffs.length) { this.loadStaffs(); } },
      onColChange: function () { try { localStorage.setItem('his.userCols', JSON.stringify(this.colOff)); } catch (e) { } },
      /* 分页/全量模式切换(持久化): 超阈值弹确认软提示, 用户确认后仍可全量; 阈值读租户参数 */
      onPagedToggle: function () {
        var vm = this;
        var threshold = (HIS.params && HIS.params.listFullThreshold) || 2000;
        if (!this.paged && this.total > threshold) {
          ElementPlus.ElMessageBox.confirm(
            '当前范围共 ' + this.total + ' 条，全量显示可能卡顿数秒，是否继续？',
            '提示', { confirmButtonText: '继续全量', cancelButtonText: '保持分页', type: 'warning' }
          ).then(function () {
            vm.page = 1;
            try { localStorage.setItem('his.userPaged2', '0'); } catch (e) { }
            vm.load();
          }).catch(function () {
            vm.paged = true;
            try { localStorage.setItem('his.userPaged2', '1'); } catch (e) { }
          });
          return;
        }
        this.page = 1;
        try { localStorage.setItem('his.userPaged2', this.paged ? '1' : '0'); } catch (e) { }
        this.load();
      },
      /* 关键字输入: 防抖 250ms 后回第1页重查, 避免逐字符请求 */
      onQueryChange: function () {
        var vm = this; vm.page = 1;
        clearTimeout(vm._qt); vm._qt = setTimeout(function () { vm.load(); }, 250);
      },
      /* 离散筛选(角色/状态/级联开关): 立即回第1页重查 */
      onFilterChange: function () { this.page = 1; this.load(); },
      /* 角色匹配: 兼容 roleId(自定义角色)/roleIds(多角色关联)与 role(内置枚举) */
      roleMatch: function (row, v) {
        if (!v) { return true; }
        for (var i = 0; i < this.roles.length; i++) {
          if (this.roles[i].value === v) {
            if (row.role === v) { return true; }
            if (this.roles[i].id != null) {
              if (row.roleId === this.roles[i].id) { return true; }
              if (row.roleIds && row.roleIds.indexOf(this.roles[i].id) >= 0) { return true; }
            }
            return false;
          }
        }
        return row.role === v;
      },
      seqNo: function (i) { return this.paged ? (this.page - 1) * this.size + i + 1 : i + 1; },
      onSizeChange: function (sz) { this.size = sz; this.page = 1; this.load(); },
      onPageChange: function (p) { this.page = p; this.load(); },
      roleDisplay: function (row) {
        for (var i = 0; i < this.roles.length; i++) {
          if (this.roles[i].id === row.roleId || this.roles[i].value === row.role) { return this.roles[i].label; }
        }
        return HIS.roleLabel(row.role);
      },
      /* 列表多角色标签: 关联表 roleIds 口径(主角色置首), 无关联行回落主角色/旧 role 字符串 */
      roleTags: function (row) {
        var vm = this;
        var ids = (row.roleIds && row.roleIds.length) ? row.roleIds : (row.roleId ? [row.roleId] : []);
        var out = [];
        ids.forEach(function (id) {
          var label = null;
          for (var i = 0; i < vm.roles.length; i++) { if (vm.roles[i].id === id) { label = vm.roles[i].label; break; } }
          if (!label && id === row.roleId) { label = vm.roleDisplay(row); }
          out.push(label || ('#' + id));
        });
        if (!out.length) { out.push(vm.roleDisplay(row)); }
        return out;
      },
      orgName: function (id) { var n = this.orgNameMap[id]; return n != null ? n : '-'; },
      /* 归属机构变更: 确保其始终在可登录机构集内(默认可登录机构不可取消);
       * 并重置不属于新机构的跨机构残留(关联职工/主属科室)(#1) */
      onHomeOrgChange: function (val) {
        if (!this.form.loginOrgIds) { this.form.loginOrgIds = []; }
        if (val && this.form.loginOrgIds.indexOf(val) < 0) { this.form.loginOrgIds.push(val); }
        var reset = [];
        var vm = this;
        var st = val ? (vm.staffs || []).filter(function (x) { return x.id === vm.form.staffId; })[0] : null;
        if (vm.form.staffId && val && (!st || st.orgId !== val)) { vm.form.staffId = null; reset.push('关联职工'); }
        var dp = val ? (vm.depts || []).filter(function (x) { return x.id === vm.form.deptId; })[0] : null;
        if (vm.form.deptId && val && (!dp || dp.orgId !== val)) { vm.form.deptId = null; reset.push('主属科室'); }
        if (reset.length) { ElementPlus.ElMessage.info('归属机构已变更, ' + reset.join('\u3001') + '不属于新机构已重置; 授权科室/可登录机构若涉多点执业请自行核对'); }
      },
      /* 可登录机构列显示(名称顿号连接) */
      loginOrgText: function (row) {
        var vm = this;
        var ids = row.loginOrgIds && row.loginOrgIds.length ? row.loginOrgIds : (row.orgId ? [row.orgId] : []);
        if (!ids.length) { return '-'; }
        return ids.map(function (id) { return vm.orgName(id); }).join('、');
      },
      /* 服务端分页拉当前页: 机构(可级联子树)/关键字/角色/状态 全交后端, 不再一次性拉全量 */
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/sys/user/page?1=1';
        var orgIds = vm.currentOrgIds();
        if (orgIds) { q += '&orgIds=' + orgIds; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.filterRole) { q += '&role=' + encodeURIComponent(vm.filterRole); }
        if (vm.filterStatus !== '' && vm.filterStatus != null) { q += '&status=' + vm.filterStatus; }
        q += '&page=' + vm.page + '&size=' + (vm.paged ? vm.size : 100000);
        HIS.get(q)
          .then(function (d) {
            vm.list = (d && d.records) || []; vm.total = Number((d && d.total) || 0);
            /* 兆底提示: 全量模式下作用域切换后行数超阈, 仅警告不拦截(用户已显式确认全量) */
            if (!vm.paged && vm.total > ((HIS.params && HIS.params.listFullThreshold) || 2000)) {
              ElementPlus.ElMessage.info('当前范围共 ' + vm.total + ' 条，全量渲染可能需要数秒');
            }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.listLoaded = true; vm.loading = false; });
      },
      /* 当前作用域要下发的机构ID串: 全部机构→null(不过滤); 选中且牵头含下级→子树集; 否则精确单机构 */
      currentOrgIds: function () {
        var vm = this;
        if (!vm.filterOrg) { return null; }
        if (vm.lead && vm.withSubOrgs) { return Object.keys(vm.orgSubtreeSet(vm.filterOrg)).join(','); }
        return vm.filterOrg;
      },
      /* 左树角标: 全作用域(不含右栏筛选)按机构启用数, 仅随增删改/状态变更刷新 */
      loadCounts: function () {
        var vm = this;
        HIS.get('/api/sys/user/org-counts').then(function (d) { vm.counts = d || {}; }).catch(function () { vm.counts = null; });
      },
      /* 数据变更后统一刷新: 列表 + 角标 */
      reload: function () { this.load(); this.loadCounts(); },
      openCreate: function () {
        this.editing = false;
        this.form = this.emptyForm();
        this.deptScopeArr = [];
        this.activeTab = 'account';
        this.roleMenuNames = []; this.roleIsAll = false;
        this.loadStaffs();
        this.dialogVisible = true;
      },
      openEdit: function (row) {
        this.editing = true;
        this.form = { id: row.id, username: row.username, password: '', realName: row.realName, role: row.role, roleId: row.roleId, roleIds: (function () { var arr = (row.roleIds && row.roleIds.length) ? row.roleIds.slice() : (row.roleId ? [row.roleId] : []); if (row.roleId) { var k = arr.indexOf(row.roleId); if (k > 0) { arr.splice(k, 1); arr.unshift(row.roleId); } else if (k < 0) { arr.unshift(row.roleId); } } return arr; })(), orgId: row.orgId, staffId: row.staffId, deptId: row.deptId, deptScope: row.deptScope || '', phone: row.phone, status: row.status == null ? 1 : row.status, loginOrgIds: (row.loginOrgIds && row.loginOrgIds.length) ? row.loginOrgIds.slice() : (row.orgId ? [row.orgId] : []) };
        this.deptScopeArr = row.deptScope ? String(row.deptScope).split(',').filter(function (x) { return x !== ''; }).map(function (x) { return Number(x); }) : [];
        this.activeTab = 'account';
        this.loadRoleMenus(this.form.roleIds);
        this.loadStaffs();
        this.dialogVisible = true;
      },
      /* 保存前基础校验: 新增账号/密码必填(密码至少6位), 姓名必填, 电话若填做格式约束 */
      validateForm: function () {
        var f = this.form;
        if (!this.editing) {
          if (!String(f.username || '').trim()) { return '账号不能为空'; }
          if (!f.password || String(f.password).length < 6) { return '密码至少6位'; }
        }
        if (!String(f.realName || '').trim()) { return '姓名必填'; }
        var ph = String(f.phone || '').trim();
        if (ph && !/^[0-9+\-() ]{6,20}$/.test(ph)) { return '联系电话格式不正确'; }
        return null;
      },
      submit: function () {
        var vm = this;
        var err = vm.validateForm();
        if (err) { ElementPlus.ElMessage.warning(err); return; }
        vm.form.deptScope = (vm.deptScopeArr || []).join(',');
        /* 主角色同步: 首个选中角色即主角色(显示与 sys_user.role_id 口径) */
        if (vm.form.roleIds && vm.form.roleIds.length) { vm.form.roleId = vm.form.roleIds[0]; }
        /* 归属机构(默认可登录机构)强制纳入可登录机构集 */
        if (vm.form.orgId && (vm.form.loginOrgIds || []).indexOf(vm.form.orgId) < 0) {
          vm.form.loginOrgIds = (vm.form.loginOrgIds || []).concat([vm.form.orgId]);
        }
        var p = vm.editing ? HIS.put('/api/sys/user', vm.form) : HIS.post('/api/sys/user', vm.form);
        p.then(function () {
          HIS.notifySuccess(vm.editing ? '修改成功' : '新增成功');
          vm.dialogVisible = false; vm.reload();
        }).catch(HIS.notifyError);
      },
      resetPwd: function (row) {
        ElementPlus.ElMessageBox.prompt('请输入新密码(至少6位)', '重置密码 - ' + row.username, {
          inputType: 'password',
          inputValidator: function (v) { return (v && v.length >= 6) ? true : '密码至少6位'; }
        }).then(function (r) {
          /* 密码走 POST body, 不再经 URL query 落 access 日志(#4) */
          return HIS.post('/api/sys/user/' + row.id + '/reset-password', { password: r.value });
        }).then(function () { HIS.notifySuccess('密码已重置'); }).catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      /* 当前登录账号行: 禁删自己(#3, 后端同步兼带守卫) */
      isSelf: function (row) {
        var u = HIS.getUser() || {};
        return !!row.id && row.id === (u.userId || u.id);
      },
      toggleStatus: function (row) {
        var vm = this;
        var target = row.status === 1 ? 0 : 1;
        HIS.post('/api/sys/user/' + row.id + '/status?status=' + target)
          .then(function () { HIS.notifySuccess(target === 1 ? '已启用' : '已停用'); row.status = target; vm.loadCounts(); })
          .catch(HIS.notifyError);
      },
      remove: function (row) {
        HIS.del('/api/sys/user/' + row.id)
          .then(function () { HIS.notifySuccess('已删除'); this.reload(); }.bind(this))
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
      /* 加载所选角色并集已授权菜单(只读预览): 任一 allMenus 角色即显示"全部菜单" */
      loadRoleMenus: function (roleIds) {
        var vm = this;
        vm.roleMenuNames = []; vm.roleIsAll = false;
        var ids = (roleIds || []).filter(function (x) { return x != null; });
        if (!ids.length) { return; }
        var rest = [];
        ids.forEach(function (roleId) {
          var role = null;
          for (var i = 0; i < vm.roles.length; i++) { if (vm.roles[i].id === roleId) { role = vm.roles[i]; break; } }
          if (role && role.allMenus === 1) { vm.roleIsAll = true; } else { rest.push(roleId); }
        });
        if (!rest.length) { return; }
        Promise.all(rest.map(function (roleId) { return HIS.get('/api/sys/role/' + roleId + '/menus').catch(function () { return []; }); }))
          .then(function (lists) {
            var seen = {}; var names = [];
            lists.forEach(function (midList) {
              (midList || []).forEach(function (id) {
                if (!seen[id]) { seen[id] = 1; var n = vm.menuMap[id]; if (n) { names.push(n); } }
              });
            });
            vm.roleMenuNames = names;
          });
      },
      /* 多角色变更: 首个选中项同步为主角色(显示口径), 菜单预览取各角色并集 */
      onRolesChange: function (ids) {
        this.form.roleId = ids && ids.length ? ids[0] : null;
        this.loadRoleMenus(ids);
      },
      deptName: function (id) { var n = this.deptNameMap[id]; return n != null ? n : (id ? ('#' + id) : '-'); },
      staffName: function (id) { var n = this.staffNameMap[id]; return n != null ? n : (id ? ('#' + id) : '-'); },
      scopeText: function (row) {
        var s = row.deptScope ? String(row.deptScope).split(',').filter(function (x) { return x !== ''; }) : [];
        return s.length ? (s.length + ' 个科室') : '仅主属科室';
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">用户管理</div>',
      '  <el-alert v-if="!lead" type="warning" :closable="false" show-icon style="margin-bottom:10px;" title="非牵头机构: 仅展示本机构用户, 只读不可维护。"></el-alert>',
      '  <div class="dept-split">',
      '    <div class="dept-orgs" :class="{collapsed: orgsCollapsed}">',
      '      <div class="dept-orgs-hd">',
      '        <span v-show="!orgsCollapsed">机构列表</span>',
      '        <span v-show="orgsCollapsed" class="dept-orgs-vt">机构列表</span>',
      '        <el-button link size="small" class="dept-orgs-tg" :title="orgsCollapsed?\'展开机构列表\':\'收缩机构列表\'" @click="toggleOrgs">{{ orgsCollapsed ? "\u00bb" : "\u00ab" }}</el-button>',
      '      </div>',
      '      <div v-show="!orgsCollapsed" style="padding:6px 8px 0;">',
      '        <el-input v-model="orgKw" size="small" clearable placeholder="过滤机构名/编码"></el-input>',
      '        <div class="dept-tree-tools">',
      '          <span class="dept-tree-tools-lb">层级</span>',
      '          <el-button link size="small" class="dept-tree-btn" title="展开全部层级" @click="expandAllOrg">展开</el-button>',
      '          <el-dropdown trigger="click" class="dept-tree-drop" @command="collapseOrgToLevel">',
      '            <el-button link size="small" class="dept-tree-btn" title="展开/收缩到指定层级">到层级\u25be</el-button>',
      '            <template #dropdown>',
      '              <el-dropdown-menu>',
      '                <el-dropdown-item :command="1">一级（顶级机构）</el-dropdown-item>',
      '                <el-dropdown-item :command="2">二级（卫生院/社区）</el-dropdown-item>',
      '                <el-dropdown-item :command="3">三级（卫生室）</el-dropdown-item>',
      '              </el-dropdown-menu>',
      '            </template>',
      '          </el-dropdown>',
      '          <el-button link size="small" class="dept-tree-btn" title="收缩到二级(显示二级医疗机构)" @click="collapseAllOrg">收缩</el-button>',
      '        </div>',
      '      </div>',
      '      <el-scrollbar v-show="!orgsCollapsed">',
      '        <div v-if="lead" class="dept-org-item" :class="{active: filterOrg===null}" @click="selectOrg(null)"><span class="tree-caret"></span><span>全部机构</span><span v-if="orgCountMap" class="tree-cnt" title="启用账号数(不含停用)">{{ orgCountMap.all }}</span></div>',
      '        <div v-for="o in visibleOrgs" :key="o.id" class="dept-org-item" :title="orgTitle(o)" :class="{active: filterOrg===o.id}" @click="selectOrg(o.id)"><span v-if="o.hasKids" class="tree-caret" :class="{\'is-inert\': searching}" :title="searching?\'过滤态自动展开全部层级, 清空关键字后可折叠\':\'折叠/展开\'" @click.stop="searching ? null : toggleOrgFold(o)">{{ (searching || !orgFolded[o.id]) ? \'▾\' : \'▸\' }}</span><span v-else class="tree-caret"></span><span v-html="hl(o.label)"></span><span v-if="orgCountMap && orgCountMap[\'org-\'+o.id]" class="tree-cnt" title="启用账号数(含下级机构, 不含停用)">{{ orgCountMap[\'org-\'+o.id] }}</span></div>',
      '        <div v-if="orgKw && !visibleOrgs.length" class="dept-tree-empty">无匹配机构</div>',
      '      </el-scrollbar>',
      '    </div>',
      '    <div class="dept-main">',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="账号/姓名/电话" clearable style="width:170px" @input="onQueryChange" @clear="onQueryChange"></el-input>',
      '    <el-select v-model="filterRole" placeholder="全部角色" clearable style="width:130px" @change="onQueryChange"><el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value"></el-option></el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:110px" @change="onQueryChange"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-checkbox v-if="lead" v-model="withSubOrgs" @change="onFilterChange" :disabled="!filterOrg" :title="filterOrg ? \'勾选后选中机构时级联显示下级机构的账号; 默认仅显示选中机构本身的账号\' : \'先在左侧选中机构后可用\'" style="margin-left:4px;">含下级机构</el-checkbox>',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新增用户</el-button>',
      '    <el-button @click="refresh">刷新</el-button>',
      '    <el-popover placement="bottom" :width="180" trigger="click">',
      '      <template #reference><el-button link type="primary" size="small" style="margin-left:6px;">列设置</el-button></template>',
      '      <div style="max-height:260px;overflow:auto;">',
      '        <el-checkbox v-for="c in colDefs" :key="c.key" :model-value="colShow(c.key)" @change="onColToggle(c.key, $event)" style="display:block;margin:2px 0;">{{ c.label }}</el-checkbox>',
      '      </div>',
      '    </el-popover>',
      '    <el-radio-group v-model="paged" size="small" @change="onPagedToggle" title="显示模式: 分页=每页固定行数; 全量=一次性展示全部" style="margin-left:6px;">',
      '      <el-radio-button :label="false">全量</el-radio-button>',
      '      <el-radio-button :label="true">分页</el-radio-button>',
      '    </el-radio-group>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">点击左侧机构查看其账号 · {{ countHint }}</span>',
      '  </div>',
      '  <div class="table-box">',
      '  <el-table :data="pagedList" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="username" label="账号" width="140"></el-table-column>',
      '    <el-table-column prop="realName" label="姓名" width="120"></el-table-column>',
      '    <el-table-column v-if="colShow(\'role\')" label="角色" width="150"><template #default="s">',
      '      <el-tag v-for="(n, i) in roleTags(s.row).slice(0, 2)" :key="i" size="small" :type="i===0?\'primary\':\'info\'" style="margin:1px 3px 1px 0;">{{ n }}</el-tag>',
      '      <el-tag v-if="roleTags(s.row).length > 2" size="small" type="info" :title="roleTags(s.row).join(\' / \')" style="margin:1px 0;">+{{ roleTags(s.row).length - 2 }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column v-if="colShow(\'homeOrg\')" label="归属机构" min-width="160" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'loginOrg\')" label="可登录机构" min-width="180" show-overflow-tooltip><template #default="s">{{ loginOrgText(s.row) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'phone\')" prop="phone" label="联系电话" width="140"></el-table-column>',
      '    <el-table-column v-if="colShow(\'staff\')" label="关联职工" width="150" show-overflow-tooltip><template #default="s">{{ staffName(s.row.staffId) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'dept\')" label="主属科室" width="120" show-overflow-tooltip><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'scope\')" label="授权科室" width="110"><template #default="s"><el-tag size="small" :type="s.row.deptScope?\'warning\':\'info\'">{{ scopeText(s.row) }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="colShow(\'status\')" label="状态" width="90"><template #default="s">',
      '      <el-tag :type="s.row.status === 1 ? \'success\' : \'info\'" size="small">{{ s.row.status === 1 ? "启用" : "停用" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" width="230" fixed="right">',
      '      <template #default="s"><div style="white-space:nowrap">',
      '        <el-button v-if="lead" link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button v-if="lead" link type="warning" @click="resetPwd(s.row)">重置密码</el-button>',
      '        <el-button v-if="lead" link :type="s.row.status === 1 ? \'info\' : \'success\'" @click="toggleStatus(s.row)">{{ s.row.status === 1 ? "停用" : "启用" }}</el-button>',
      '        <el-popconfirm v-if="lead && !isSelf(s.row)" title="确认删除该账号？" @confirm="remove(s.row)">',
      '          <template #reference><el-button link type="danger">删除</el-button></template>',
      '        </el-popconfirm>',
      '        <el-button v-if="lead && isSelf(s.row)" link type="danger" disabled title="不能删除自己的账号(防误操作锁死)">删除</el-button>',
      '        <span v-if="!lead" style="color:var(--yb-ink-2);font-size:12px;">只读</span>',
      '      </div></template>',
      '    </el-table-column>',
      '  </el-table>',
      '  </div>',
      '  <div v-if="paged" class="pager">',
      '    <el-pagination background :current-page="page" :page-size="size" :page-sizes="[10,20,50,100]" :total="total" layout="total, sizes, prev, pager, next, jumper" @size-change="onSizeChange" @current-change="onPageChange"></el-pagination>',
      '  </div>',
      '    </div>',
      '  </div>',
      '  <el-dialog v-model="dialogVisible" :title="editing ? \'编辑用户\' : \'新增用户\'" width="640px">',
      '    <el-tabs v-model="activeTab">',
      '      <el-tab-pane label="账号信息" name="account">',
      '        <el-form :model="form" label-width="90px">',
      '          <el-form-item label="账号"><el-input v-model="form.username" :disabled="editing" placeholder="登录账号"></el-input></el-form-item>',
      '          <el-form-item label="密码" v-if="!editing"><el-input v-model="form.password" type="password" show-password placeholder="至少6位"></el-input></el-form-item>',
      '          <el-form-item label="姓名"><el-input v-model="form.realName"></el-input></el-form-item>',
      '          <el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item>',
      '          <el-form-item label="归属机构"><el-select v-model="form.orgId" style="width:100%" clearable filterable placeholder="选择归属机构(行政所属、默认可登录; 可输拼音简码)" :filter-method="kwFilter(\'homeOrg\')" @change="onHomeOrgChange"><el-option v-for="o in kwOptions(\'homeOrg\', orgs, [\'label\',\'orgCode\',\'pyCode\'])" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="可登录机构"><el-select v-model="form.loginOrgIds" multiple clearable filterable style="width:100%" placeholder="多点执业: 可登录的多个机构(归属机构默认已含且不可取消; 可输拼音简码)" :filter-method="kwFilter(\'loginOrg\')"><el-option v-for="o in kwOptions(\'loginOrg\', orgs, [\'label\',\'orgCode\',\'pyCode\'])" :key="o.id" :label="o.label + (o.id===form.orgId?\' (归属·默认)\':\'\')" :value="o.id" :disabled="o.id===form.orgId"></el-option></el-select></el-form-item>',
      '          <el-form-item label=" "><span style="color:var(--yb-ink-2);font-size:12px;">归属机构为默认可登录机构, 始终保留; 可再勾选本医共体内其他机构以支持多点执业。登录后默认进入归属机构, 可在顶部"切换机构"。仅本医共体(同租户)机构可选。</span></el-form-item>',
      '          <el-form-item label="关联职工"><el-select v-model="form.staffId" style="width:100%" clearable filterable placeholder="关联 his_staff(操作留痕/电子签名; 可输拼音简码)" :filter-method="kwFilter(\'staff\')"><el-option v-for="st in kwOptions(\'staff\', staffs, [\'staffName\',\'staffNo\',\'pyCode\',\'abbrCode\'])" :key="st.id" :label="st.staffName + \'(\' + st.staffNo + \')\'" :value="st.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="状态" v-if="editing"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="角色权限" name="role">',
      '        <el-form :model="form" label-width="90px">',
      '          <el-form-item label="角色"><el-select v-model="form.roleIds" multiple clearable style="width:100%" placeholder="选择角色(可多选, 首个为主角色)" @change="onRolesChange"><el-option v-for="r in roles" :key="r.id || r.value" :label="r.label" :value="r.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label=" "><span style="color:var(--yb-ink-2);font-size:12px;">医共体一人多角色: 权限为各角色并集; 首个选中项为主角色, 用于顶栏显示与旧数据兜底。</span></el-form-item>',
      '          <el-form-item label="菜单权限">',
      '            <div v-if="roleIsAll" style="line-height:24px;"><el-tag type="danger" size="small">全部菜单(管理员角色)</el-tag></div>',
      '            <div v-else-if="roleMenuNames.length" style="line-height:28px;"><el-tag v-for="(n,i) in roleMenuNames" :key="i" size="small" style="margin:2px 4px 2px 0;">{{ n }}</el-tag></div>',
      '            <span v-else style="color:var(--yb-ink-2);font-size:12px;">尚未选择角色, 或该角色未分配菜单</span>',
      '          </el-form-item>',
      '        </el-form>',
      '        <el-alert type="info" :closable="false" title="菜单权限(功能权限)由「医共体管理 → 角色权限」统一授权, 此处为只读预览。"></el-alert>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="科室权限" name="dept">',
      '        <el-form :model="form" label-width="90px">',
      '          <el-form-item label="主属科室"><el-select v-model="form.deptId" style="width:100%" clearable filterable placeholder="该账号编制主属科室(可输拼音简码)" :filter-method="kwFilter(\'uDept\')"><el-option-group v-for="g in kwGroupOptions(\'uDept\', deptGroups, [\'label\',\'pyCode\',\'abbrCode\'])" :key="g.category" :label="g.category"><el-option v-for="d in g.options" :key="d.id" :label="d.label" :value="d.id"></el-option></el-option-group></el-select></el-form-item>',
      '          <el-form-item label="授权科室"><el-select v-model="deptScopeArr" multiple clearable filterable style="width:100%" placeholder="可登录/执业的多个科室(数据权限); 留空=仅主属科室; 可输拼音简码" :filter-method="kwFilter(\'uScope\')"><el-option-group v-for="g in kwGroupOptions(\'uScope\', deptGroups, [\'label\',\'pyCode\',\'abbrCode\'])" :key="g.category" :label="g.category"><el-option v-for="d in g.options" :key="d.id" :label="d.label" :value="d.id"></el-option></el-option-group></el-select></el-form-item>',
      '          <el-form-item label=" "><span style="color:var(--yb-ink-2);font-size:12px;">已授权 {{ deptScopeArr.length }} 个科室。一个医生可登录多个住院科室、护士可进多个病区、药师可进多个药房。科室权限决定账号可见的就诊/患者数据范围(数据权限), 与角色菜单权限(功能权限)相互独立。</span></el-form-item>',
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
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">医院管理<span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;margin-left:8px;">平台超级管理员 · 跨租户开通与管理</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;" title="新医院由此统一开通: 建租户 + 医保配置 + 默认机构 + 初始管理员账号, 开通即时生效。之后由该医院管理员登录自行维护科室/职工并分配用户权限。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate">开通新医院</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 家医院</span>',
      '  </div>',
      '  <el-table :data="list" border stripe size="small" height="100%" style="width:100%">',
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
