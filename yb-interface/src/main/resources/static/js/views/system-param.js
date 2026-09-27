/* 系统参数管理: 四级作用域(全局0/租户1/机构2/科室3)参数定义与覆盖值维护。
 * 顶部作用域选择器(类型radio + 机构/科室联动下拉) + 左侧分组导航(el-menu) + 右侧参数表格。
 * 权限分层(与后端 SystemParamController 动态定档对齐):
 *  - SUPER_ADMIN: 全部4级(全局默认值/定义/分组维护专属);
 *  - ADMIN(牵头): 租户/机构/科室级覆盖;
 *  - ORG_ADMIN: 机构(锁定本机构)/科室级覆盖;
 *  - 其他角色: 菜单不下发, 误入时显示无权提示(后端 requireAdmin 亦 403 兜底)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 数据类型展示字典(后端 DATA_TYPES 兼容 integer/boolean 长名, 展示归一为短名) */
  function dtLabel(dt) {
    var d = String(dt || 'string').toLowerCase();
    if (d === 'integer') { return '整数'; }
    if (d === 'boolean') { return '布尔'; }
    return { string: '文本', int: '整数', decimal: '小数', bool: '布尔', enum: '枚举' }[d] || d;
  }
  function dtNorm(dt) {
    var d = String(dt || 'string').toLowerCase();
    if (d === 'integer') { return 'int'; }
    if (d === 'boolean') { return 'bool'; }
    return d;
  }
  var SCOPE_LABELS = { 0: '全局', 1: '租户', 2: '机构', 3: '科室' };

  /* 解析 enum_options 为 [{value,label}]: 兼容 JSON 数组([{value,label}]) 与逗号分隔两种格式。
   * 后端校验口径为逗号分隔(split(",") 精确等值), 故种子/录入以逗号分隔为准, JSON 仅为展示兼容。 */
  function parseEnumOpts(opts) {
    var s = String(opts == null ? '' : opts).trim();
    if (!s) { return []; }
    if (s.charAt(0) === '[') {
      try {
        var arr = JSON.parse(s);
        return (arr || []).map(function (o) {
          return (typeof o === 'object' && o != null)
            ? { value: String(o.value), label: String(o.label != null ? o.label : o.value) }
            : { value: String(o), label: String(o) };
        }).filter(function (o) { return o.value !== ''; });
      } catch (e) { /* 非法 JSON 回退逗号分隔 */ }
    }
    return s.split(',').map(function (v) {
      var t = v.trim();
      return t ? { value: t, label: t } : null;
    }).filter(Boolean);
  }

  HIS.views.SystemParam = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      var u = HIS.getUser() || {};
      return {
        user: u,
        role: u.role || '',
        loading: false,
        /* 分组导航: '__all__'=全部(不筛选) */
        groups: [],
        activeGroup: '__all__',
        /* 参数列表(scope 接口全量行, 前端按分组过滤+分页) */
        rows: [],
        page: 1,
        size: 20,
        paged: (function () { try { return localStorage.getItem('his.sysParamPaged') !== '0'; } catch (e) { return true; } })(),
        /* 作用域选择 */
        scopeLevel: 2,
        scopeOrgId: u.orgId || null,
        scopeDeptId: null,
        orgs: [],
        depts: [],
        deptsLoading: false,
        /* 覆盖/默认值编辑弹窗 */
        edit: { visible: false, mode: 'override', row: null, value: '', saving: false },
        /* 参数定义弹窗(仅超管; form 在打开时重置) */
        def: { visible: false, editing: false, saving: false, form: {} },
        /* 分组管理弹窗(仅超管) */
        grp: { visible: false, saving: false, rows: [] }
      };
    },
    created: function () {
      var vm = this;
      /* 默认作用域: 超管看全局默认; 其余管理员看本机构(机构级) */
      vm.scopeLevel = vm.isSuper ? 0 : 2;
      vm.loadGroups();
      vm.loadOrgs();
      vm.loadParams();
    },
    computed: {
      isSuper: function () { return this.role === 'SUPER_ADMIN'; },
      isAdmin: function () { return this.role === 'ADMIN'; },
      isOrgAdmin: function () { return this.role === 'ORG_ADMIN'; },
      lead: function () { return !!(this.user && this.user.leadOrg); },
      /* 可维护者(菜单本就只下发给三个管理员角色, 此处兜底误入场景) */
      canAccess: function () { return this.isSuper || this.isAdmin || this.isOrgAdmin; },
      /* 作用域可选档位(与后端 guardByScope 动态定档一致) */
      levelAllowed: function () {
        return function (lv) {
          if (this.isSuper) { return true; }
          if (this.isAdmin) { return lv >= (this.lead ? 1 : 2); }
          if (this.isOrgAdmin) { return lv >= 2; }
          return false;
        };
      },
      /* 是否可在当前作用域写覆盖值(行级还须 allow_scope 允许) */
      canWriteScope: function () {
        var lv = this.scopeLevel;
        if (lv === 0) { return this.isSuper; }
        if (lv === 1) { return this.isSuper || (this.isAdmin && this.lead); }
        return this.isSuper || this.isAdmin || this.isOrgAdmin;
      },
      /* 机构下拉是否可选(超管/牵头ADMIN可选全医共体, 其余锁定本机构) */
      canPickOrg: function () { return this.isSuper || (this.isAdmin && this.lead); },
      /* 按分组过滤后的行 */
      filteredRows: function () {
        var vm = this;
        if (vm.activeGroup === '__all__' || !vm.activeGroup) { return vm.rows; }
        return (vm.rows || []).filter(function (r) { return r.group_code === vm.activeGroup; });
      },
      pagedList: function () {
        if (!this.paged) { return this.filteredRows; }
        var s = (this.page - 1) * this.size;
        return this.filteredRows.slice(s, s + this.size);
      },
      /* 当前作用域描述(工具栏右侧徽标) */
      scopeDesc: function () {
        var vm = this;
        var base = SCOPE_LABELS[vm.scopeLevel] || ('层级' + vm.scopeLevel);
        if (vm.scopeLevel === 0) { return base + '默认值(平台统一定义)'; }
        if (vm.scopeLevel === 1) { return base + '级(当前医共体)'; }
        if (vm.scopeLevel === 2) {
          var on = vm.orgName(vm.scopeOrgId);
          return base + '级 · ' + (on || '请选择机构');
        }
        var dn = vm.deptName(vm.scopeDeptId);
        return base + '级 · ' + (dn || '请选择科室');
      },
      /* 科室级未选科室时右侧不拉列表 */
      scopeReady: function () {
        if (this.scopeLevel === 2) { return !!this.scopeOrgId; }
        if (this.scopeLevel === 3) { return !!this.scopeOrgId && !!this.scopeDeptId; }
        return true;
      }
    },
    methods: {
      /* 文件级工具函数暴露给模板(运行时编译模板仅可访问组件作用域) */
      dtLabel: function (dt) { return dtLabel(dt); },
      dtNorm: function (dt) { return dtNorm(dt); },
      parseEnumOpts: function (opts) { return parseEnumOpts(opts); },
      /* 数值控件边界(undefined=无约束); 避免 -Infinity/Infinity 在模板作用域外解析差异 */
      valMin: function (v) { return v == null || v === '' ? undefined : Number(v); },
      valMax: function (v) { return v == null || v === '' ? undefined : Number(v); },
      /* ================= 取数 ================= */
      loadGroups: function () {
        var vm = this;
        HIS.get('/api/sys/param/groups').then(function (d) {
          vm.groups = d || [];
          /* 当前分组被删时回退"全部" */
          if (vm.activeGroup !== '__all__') {
            var hit = vm.groups.some(function (g) { return g.groupCode === vm.activeGroup; });
            if (!hit) { vm.activeGroup = '__all__'; }
          }
        }).catch(HIS.notifyError);
      },
      loadOrgs: function () {
        var vm = this;
        return HIS.get('/api/sys/org/tree').then(function (d) {
          vm.orgs = HIS.flattenOrgs(d || []);
          /* 非牵头/机构管理员: 锁定本机构(后端写入归属校验亦强制) */
          if (!vm.canPickOrg) { vm.orgs = vm.orgs.filter(function (o) { return o.id === vm.scopeOrgId; }); }
        }).catch(function () { vm.orgs = []; });
      },
      loadDepts: function () {
        var vm = this;
        if (vm.scopeLevel !== 3 || !vm.scopeOrgId) { vm.depts = []; return; }
        vm.deptsLoading = true;
        HIS.get('/api/his/dept/tree?orgId=' + vm.scopeOrgId).then(function (d) {
          var out = [];
          (function walk(list, depth) {
            (list || []).forEach(function (n) {
              var pad = '';
              for (var i = 0; i < depth; i++) { pad += '　'; }
              out.push({ id: n.id, label: pad + (n.deptName || ('科室' + n.id)), deptName: n.deptName, deptCode: n.deptCode, depth: depth });
              if (n.children && n.children.length) { walk(n.children, depth + 1); }
            });
          })(d || [], 0);
          vm.depts = out;
        }).catch(function () { vm.depts = []; }).finally(function () { vm.deptsLoading = false; });
      },
      loadParams: function () {
        var vm = this;
        if (!vm.scopeReady) { vm.rows = []; return; }
        vm.loading = true;
        var q = '/api/sys/param/scope?scopeLevel=' + vm.scopeLevel;
        if (vm.scopeLevel === 2 || vm.scopeLevel === 3) { q += '&scopeId=' + vm.scopeId(); }
        if (vm.activeGroup !== '__all__') { q += '&groupCode=' + encodeURIComponent(vm.activeGroup); }
        HIS.get(q).then(function (d) {
          vm.rows = d || [];
          vm.page = 1;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 当前作用域对象ID(1级不传由后端按租户兜底, 0级固定0) */
      scopeId: function () {
        var lv = this.scopeLevel;
        if (lv === 0) { return 0; }
        if (lv === 2) { return this.scopeOrgId; }
        if (lv === 3) { return this.scopeDeptId; }
        return null;
      },
      orgName: function (id) {
        for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } }
        return '';
      },
      deptName: function (id) {
        for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return String(this.depts[i].deptName || '').trim(); } }
        return '';
      },

      /* ================= 作用域切换 ================= */
      onScopeLevelChange: function () {
        var vm = this;
        /* 机构级/科室级默认带出本机构; 非牵头锁定不可换 */
        if (vm.scopeLevel === 2 || vm.scopeLevel === 3) {
          if (!vm.canPickOrg) { vm.scopeOrgId = (HIS.getUser() || {}).orgId || null; }
          else if (!vm.orgs.some(function (o) { return o.id === vm.scopeOrgId; })) { vm.scopeOrgId = (HIS.getUser() || {}).orgId || null; }
        }
        vm.scopeDeptId = null;
        vm.loadDepts();
        vm.loadParams();
      },
      onScopeOrgChange: function () {
        this.scopeDeptId = null;
        this.loadDepts();
        this.loadParams();
      },
      onScopeDeptChange: function () { this.loadParams(); },
      onGroupSelect: function (key) {
        this.activeGroup = key;
        this.loadParams();
      },

      /* ================= 值渲染辅助 ================= */
      enumOpts: function (row) { return parseEnumOpts(row.enum_options); },
      enumLabel: function (row, val) {
        var opts = parseEnumOpts(row.enum_options);
        for (var i = 0; i < opts.length; i++) { if (opts[i].value === String(val)) { return opts[i].label; } }
        return val;
      },
      isBool: function (row) { return dtNorm(row.data_type) === 'bool'; },
      isInt: function (row) { return dtNorm(row.data_type) === 'int'; },
      isDecimal: function (row) { return dtNorm(row.data_type) === 'decimal'; },
      isEnum: function (row) { return dtNorm(row.data_type) === 'enum'; },
      /* 来源标签: 全局行=全局默认; 覆盖行=本级设置; 其余=继承 */
      srcTag: function (row) {
        if (this.scopeLevel === 0) { return { text: '全局默认', type: 'primary' }; }
        if (row.override_value != null && String(row.override_value) !== '') {
          return { text: '本级设置', type: 'danger' };
        }
        return { text: '继承(全局默认)', type: 'info' };
      },
      /* 行是否允许在当前作用域覆盖(allow_scope 层级列表包含当前层级) */
      scopeAllowedFor: function (row) {
        var allow = String(row.allow_scope || '0,1,2,3').split(',');
        var lv = String(this.scopeLevel);
        for (var i = 0; i < allow.length; i++) { if (allow[i].trim() === lv) { return true; } }
        return false;
      },
      /* 约束提示文本(min/max/required/allow_scope) */
      constraintText: function (row) {
        var parts = [];
        if (row.required === 1 || row.required === '1') { parts.push('必填'); }
        if (row.min_value != null) { parts.push('最小 ' + row.min_value); }
        if (row.max_value != null) { parts.push('最大 ' + row.max_value); }
        if (this.isEnum(row) && row.enum_options) { parts.push('可选: ' + row.enum_options); }
        return parts.join(' · ');
      },
      allowScopeText: function (row) {
        var vm = this;
        return String(row.allow_scope || '0,1,2,3').split(',').map(function (s) {
          return SCOPE_LABELS[+s.trim()] || s;
        }).join('/');
      },

      /* ================= 分页 ================= */
      seqNo: function (i) { return this.paged ? (this.page - 1) * this.size + i + 1 : i + 1; },
      onPage: function (p) { this.page = p; },
      onSize: function (s) { this.size = s; this.page = 1; },
      onPagedToggle: function () {
        this.page = 1;
        try { localStorage.setItem('his.sysParamPaged', this.paged ? '1' : '0'); } catch (e) { }
      },

      /* ================= 覆盖值 / 默认值编辑 ================= */
      openEdit: function (row) {
        var vm = this;
        var mode = vm.scopeLevel === 0 ? 'default' : 'override';
        var init = mode === 'override' ? row.override_value : row.effective_value;
        vm.edit = { visible: true, mode: mode, row: row, value: init == null ? '' : String(init), saving: false };
      },
      saveEdit: function () {
        var vm = this;
        var row = vm.edit.row;
        var v = String(vm.edit.value == null ? '' : vm.edit.value).trim();
        /* 必填校验(后端 validateValue 同口径) */
        if (!v) {
          if (row.required === 1 || row.required === '1') {
            ElementPlus.ElMessage.warning('参数[' + (row.param_name || row.param_key) + ']为必填, 不能为空');
            return;
          }
          /* 覆盖模式留空 => 恢复继承(删除本级覆盖行) */
          if (vm.edit.mode === 'override') {
            vm.deleteOverride(row);
            return;
          }
        }
        vm.edit.saving = true;
        var body = { paramKey: row.param_key, value: v, scopeLevel: vm.scopeLevel, scopeId: vm.scopeId() };
        HIS.post('/api/sys/param/save', body).then(function () {
          HIS.notifySuccess(vm.edit.mode === 'default' ? '默认值已更新(全局生效)' : '已保存本级覆盖值');
          vm.edit.visible = false;
          return vm.loadParams();
        }).catch(HIS.notifyError).finally(function () { vm.edit.saving = false; });
      },
      deleteOverride: function (row) {
        var vm = this;
        var done = function () {
          HIS.notifySuccess('已删除本级覆盖, 恢复继承全局默认');
          vm.edit && (vm.edit.visible = false);
          vm.loadParams();
        };
        if (vm.edit && vm.edit.row === row) {
          /* 弹窗内留空提交: 直接执行恢复继承 */
          vm.edit.saving = true;
          HIS.del('/api/sys/param/override?paramKey=' + encodeURIComponent(row.param_key)
            + '&scopeLevel=' + vm.scopeLevel + '&scopeId=' + vm.scopeId())
            .then(done).catch(HIS.notifyError).finally(function () { vm.edit.saving = false; });
          return;
        }
        ElementPlus.ElMessageBox.confirm(
          '删除参数「' + (row.param_name || row.param_key) + '」的本级覆盖值, 恢复继承上级/全局默认？',
          '恢复继承', { type: 'warning', confirmButtonText: '恢复继承', cancelButtonText: '取消' }
        ).then(function () {
          HIS.del('/api/sys/param/override?paramKey=' + encodeURIComponent(row.param_key)
            + '&scopeLevel=' + vm.scopeLevel + '&scopeId=' + vm.scopeId())
            .then(done).catch(HIS.notifyError);
        }).catch(function () { /* 取消 */ });
      },

      /* ================= 参数定义维护(仅超管) ================= */
      emptyDef: function () {
        return {
          paramKey: '', paramName: '', groupCode: '', dataType: 'string', defaultValue: '',
          enumOptions: '', minValue: null, maxValue: null, required: 0,
          allowScopeArr: ['0', '1', '2', '3'], remark: ''
        };
      },
      openDefCreate: function () {
        this.def = { visible: true, editing: false, saving: false, form: this.emptyDef() };
        if (this.activeGroup !== '__all__') { this.def.form.groupCode = this.activeGroup; }
      },
      openDefEdit: function (row) {
        var allow = String(row.allow_scope || '0,1,2,3').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
        this.def = {
          visible: true, editing: true, saving: false,
          form: {
            paramKey: row.param_key, paramName: row.param_name || '',
            groupCode: row.group_code || '', dataType: dtNorm(row.data_type),
            defaultValue: row.default_value == null ? '' : String(row.default_value),
            enumOptions: row.enum_options || '',
            minValue: row.min_value == null ? null : Number(row.min_value),
            maxValue: row.max_value == null ? null : Number(row.max_value),
            required: row.required === 1 || row.required === '1' ? 1 : 0,
            allowScopeArr: allow, remark: row.remark || ''
          }
        };
      },
      saveDef: function () {
        var vm = this;
        var f = vm.def.form;
        if (!f.paramKey || !f.paramKey.trim()) { ElementPlus.ElMessage.warning('请填写参数键(如 system.session_timeout)'); return; }
        if (!f.paramName || !f.paramName.trim()) { ElementPlus.ElMessage.warning('请填写参数名称'); return; }
        if (f.dataType === 'enum' && !f.enumOptions.trim()) { ElementPlus.ElMessage.warning('枚举类型须填写枚举选项(逗号分隔, 如 10,20,50)'); return; }
        if (!f.allowScopeArr.length) { ElementPlus.ElMessage.warning('请至少勾选一个允许配置的作用域层级'); return; }
        if (f.required === 1 && !String(f.defaultValue == null ? '' : f.defaultValue).trim()) {
          ElementPlus.ElMessage.warning('必填参数的默认值不能为空'); return;
        }
        var body = {
          paramKey: f.paramKey.trim(), paramName: f.paramName.trim(),
          groupCode: f.groupCode || null, dataType: f.dataType,
          defaultValue: f.defaultValue == null ? '' : String(f.defaultValue).trim(),
          enumOptions: f.dataType === 'enum' ? f.enumOptions.trim() : null,
          minValue: f.minValue == null ? null : f.minValue, maxValue: f.maxValue == null ? null : f.maxValue,
          required: f.required, allowScope: f.allowScopeArr.join(','), remark: f.remark || null
        };
        vm.def.saving = true;
        var req = vm.def.editing ? HIS.put('/api/sys/param/definition', body) : HIS.post('/api/sys/param/definition', body);
        req.then(function () {
          HIS.notifySuccess(vm.def.editing ? '参数定义已更新' : '参数定义已创建');
          vm.def.visible = false;
          vm.loadParams();
        }).catch(HIS.notifyError).finally(function () { vm.def.saving = false; });
      },
      removeDef: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '删除参数定义「' + (row.param_name || row.param_key) + '」将级联删除全部作用域覆盖行, 解析将不再命中, 确认删除？',
          '删除参数定义', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
        ).then(function () {
          HIS.del('/api/sys/param/definition/' + encodeURIComponent(row.param_key))
            .then(function () { HIS.notifySuccess('参数定义已删除'); vm.loadParams(); })
            .catch(HIS.notifyError);
        }).catch(function () { /* 取消 */ });
      },

      /* ================= 分组管理(仅超管) ================= */
      openGroupDialog: function () {
        var vm = this;
        vm.grp.rows = (vm.groups || []).map(function (g) {
          return { id: g.id, groupCode: g.groupCode, groupName: g.groupName, sortNo: g.sortNo, remark: g.remark, _new: false, _dirty: false };
        });
        vm.grp.visible = true;
      },
      addGroupRow: function () {
        this.grp.rows.push({ id: null, groupCode: '', groupName: '', sortNo: (this.grp.rows.length + 1), remark: '', _new: true, _dirty: true });
      },
      removeGroupRow: function (idx) {
        var vm = this;
        var row = vm.grp.rows[idx];
        if (!row) { return; }
        if (row._new || row.id == null) { vm.grp.rows.splice(idx, 1); return; }
        ElementPlus.ElMessageBox.confirm(
          '删除分组「' + (row.groupName || row.groupCode) + '」？该分组下的参数将变为未分组(参数定义不删除)。',
          '删除分组', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
        ).then(function () {
          HIS.del('/api/sys/param/group/' + row.id).then(function () {
            HIS.notifySuccess('分组已删除');
            vm.grp.rows.splice(idx, 1);
            vm.loadGroups();
            if (vm.activeGroup === row.groupCode) { vm.activeGroup = '__all__'; vm.loadParams(); }
          }).catch(HIS.notifyError);
        }).catch(function () { /* 取消 */ });
      },
      saveGroupRow: function (row) {
        var vm = this;
        if (!row.groupCode || !row.groupCode.trim()) { ElementPlus.ElMessage.warning('分组编码不能为空'); return; }
        if (!row.groupName || !row.groupName.trim()) { ElementPlus.ElMessage.warning('分组名称不能为空'); return; }
        var body = {
          id: row._new ? null : row.id,
          groupCode: row.groupCode.trim(), groupName: row.groupName.trim(),
          sortNo: row.sortNo == null ? 0 : row.sortNo, remark: row.remark || null
        };
        row._saving = true;
        HIS.post('/api/sys/param/group', body).then(function () {
          HIS.notifySuccess(row._new ? '分组已新增' : '分组已保存');
          return vm.loadGroups();
        }).then(function () {
          /* 以服务端为准重建行(带回填 id) */
          vm.grp.rows = (vm.groups || []).map(function (g) {
            return { id: g.id, groupCode: g.groupCode, groupName: g.groupName, sortNo: g.sortNo, remark: g.remark, _new: false, _dirty: false };
          });
        }).catch(HIS.notifyError).finally(function () { row._saving = false; });
      }
    },
    template: [
      '<div class="page-card" v-loading="loading">',
      '  <div class="page-title">系统参数 <span style="font-size:12px;color:#909399;font-weight:normal;">多级作用域配置 · 全局默认 → 租户/机构/科室逐级覆盖 · 留空继承</span></div>',
      '  <el-alert v-if="!canAccess" type="warning" :closable="false" show-icon title="系统参数维护仅对机构管理员及以上角色开放, 您的账号无权访问。"></el-alert>',
      '  <template v-else>',
      /* ---- 顶部作用域选择器 ---- */
      '    <div class="toolbar" style="margin-bottom:12px;">',
      '      <el-radio-group v-model="scopeLevel" size="small" @change="onScopeLevelChange">',
      '        <el-radio-button :label="0" :disabled="!levelAllowed(0)">全局默认</el-radio-button>',
      '        <el-radio-button :label="1" :disabled="!levelAllowed(1)">租户级</el-radio-button>',
      '        <el-radio-button :label="2" :disabled="!levelAllowed(2)">机构级</el-radio-button>',
      '        <el-radio-button :label="3" :disabled="!levelAllowed(3)">科室级</el-radio-button>',
      '      </el-radio-group>',
      '      <el-select v-if="scopeLevel===2 || scopeLevel===3" v-model="scopeOrgId" size="small" filterable',
      '        :disabled="!canPickOrg" style="width:230px" placeholder="选择机构"',
      '        :filter-method="kwFilter(\'scopeOrg\')" @change="onScopeOrgChange"',
      '        :title="canPickOrg ? \'选择要查看/配置的机构\' : \'仅可维护本机构(机构管理员)\'">',
      '        <el-option v-for="o in kwOptions(\'scopeOrg\', orgs, [\'label\',\'orgCode\',\'pyCode\'])" :key="o.id" :label="o.label" :value="o.id"></el-option>',
      '      </el-select>',
      '      <el-select v-if="scopeLevel===3" v-model="scopeDeptId" size="small" filterable :loading="deptsLoading"',
      '        style="width:230px" placeholder="选择科室(含下级科室)" clearable',
      '        :filter-method="kwFilter(\'scopeDept\')" @change="onScopeDeptChange">',
      '        <el-option v-for="d in kwOptions(\'scopeDept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="d.id" :label="d.label" :value="d.id"></el-option>',
      '      </el-select>',
      '      <el-tag size="small" type="info" effect="plain" style="margin-left:2px;">{{ scopeDesc }}</el-tag>',
      '      <span style="flex:1"></span>',
      '      <el-tag v-if="scopeLevel===0" size="small" type="warning" effect="plain">全局默认值与参数定义仅平台超管可维护</el-tag>',
      '      <el-tag v-else-if="!canWriteScope" size="small" type="info" effect="plain">当前角色在此作用域只读</el-tag>',
      '    </div>',
      '    <el-alert v-if="scopeLevel===3 && !scopeDeptId" type="info" :closable="false" show-icon style="margin-bottom:10px;"',
      '      title="请先选择机构与科室, 查看该科室的参数覆盖(未覆盖参数继承机构/租户/全局默认)。"></el-alert>',
      '    <div style="display:flex;gap:12px;align-items:stretch;">',
      /* ---- 左侧分组导航 ---- */
      '      <div style="width:190px;flex:none;border:1px solid #e4e7ed;border-radius:4px;padding:8px;display:flex;flex-direction:column;gap:8px;">',
      '        <div style="font-size:13px;color:#606266;font-weight:600;">参数分组</div>',
      '        <el-menu :default-active="activeGroup" style="border-right:none;" @select="onGroupSelect">',
      '          <el-menu-item index="__all__" style="height:36px;line-height:36px;">全部参数</el-menu-item>',
      '          <el-menu-item v-for="g in groups" :key="g.groupCode" :index="g.groupCode" style="height:36px;line-height:36px;" :title="g.groupCode">',
      '            <span style="display:inline-block;max-width:130px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">{{ g.groupName || g.groupCode }}</span>',
      '          </el-menu-item>',
      '        </el-menu>',
      '        <el-button v-if="isSuper" size="small" plain style="width:100%;" @click="openGroupDialog">管理分组</el-button>',
      '      </div>',
      /* ---- 右侧参数表格 ---- */
      '      <div style="flex:1;min-width:0;">',
      '        <div class="toolbar" style="margin-bottom:10px;">',
      '          <el-button size="small" @click="loadParams">刷新</el-button>',
      '          <el-button v-if="isSuper && scopeLevel===0" type="primary" size="small" @click="openDefCreate">新增参数</el-button>',
      '          <span style="flex:1"></span>',
      '          <el-checkbox v-model="paged" size="small" @change="onPagedToggle" style="margin-right:4px;">分页</el-checkbox>',
      '          <el-pagination v-if="paged" small background layout="total, sizes, prev, pager, next" :total="filteredRows.length"',
      '            :page-size="size" :current-page="page" :page-sizes="[20, 50, 100]"',
      '            @current-change="onPage" @size-change="onSize"></el-pagination>',
      '        </div>',
      '        <el-table :data="pagedList" border size="small" style="width:100%" empty-text="暂无参数(该分组/作用域下无参数定义)">',
      '          <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '          <el-table-column label="参数名" min-width="200">',
      '            <template #default="s">',
      '              <div style="font-weight:500;">{{ s.row.param_name }}</div>',
      '              <div style="font-size:12px;color:#909399;">{{ s.row.param_key }}</div>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="当前生效值" min-width="150">',
      '            <template #default="s">',
      '              <el-switch v-if="isBool(s.row)" :model-value="String(s.row.effective_value) === \'true\'" disabled active-text="开" inactive-text="关"></el-switch>',
      '              <span v-else-if="isEnum(s.row)" style="font-weight:500;">{{ enumLabel(s.row, s.row.effective_value) }}</span>',
      '              <span v-else style="font-weight:500;">{{ s.row.effective_value == null || s.row.effective_value === \'\' ? \'-\' : s.row.effective_value }}</span>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="类型" width="70" align="center">',
      '            <template #default="s"><el-tag size="small" effect="plain">{{ dtLabel(s.row.data_type) }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="来源" width="130" align="center">',
      '            <template #default="s"><el-tag size="small" :type="srcTag(s.row).type">{{ srcTag(s.row).text }}</el-tag></template>',
      '          </el-table-column>',
      '          <el-table-column label="约束" min-width="150">',
      '            <template #default="s">',
      '              <div style="font-size:12px;color:#606266;">{{ constraintText(s.row) || \'-\' }}</div>',
      '              <div style="font-size:12px;color:#909399;">可配置层级: {{ allowScopeText(s.row) }}</div>',
      '            </template>',
      '          </el-table-column>',
      '          <el-table-column label="操作" width="230" align="center" fixed="right">',
      '            <template #default="s">',
      '              <template v-if="scopeLevel===0">',
      '                <el-button v-if="isSuper" type="primary" link size="small" @click="openEdit(s.row)">编辑默认值</el-button>',
      '                <el-button v-if="isSuper" link size="small" @click="openDefEdit(s.row)">编辑定义</el-button>',
      '                <el-button v-if="isSuper" type="danger" link size="small" @click="removeDef(s.row)">删除</el-button>',
      '              </template>',
      '              <template v-else>',
      '                <el-button v-if="canWriteScope && s.row.override_value != null && String(s.row.override_value) !== \'\'" type="primary" link size="small" @click="openEdit(s.row)">编辑</el-button>',
      '                <el-button v-if="canWriteScope && s.row.override_value != null && String(s.row.override_value) !== \'\'" type="warning" link size="small" @click="deleteOverride(s.row)">恢复继承</el-button>',
      '                <el-button v-if="canWriteScope && (s.row.override_value == null || String(s.row.override_value) === \'\') && scopeAllowedFor(s.row)" type="primary" plain link size="small" @click="openEdit(s.row)">覆盖</el-button>',
      '                <span v-if="!scopeAllowedFor(s.row)" style="font-size:12px;color:#c0c4cc;">不可在此级配置</span>',
      '              </template>',
      '            </template>',
      '          </el-table-column>',
      '        </el-table>',
      '      </div>',
      '    </div>',
      '  </template>',
      '</div>',
      /* ---- 覆盖值/默认值编辑弹窗 ---- */
      '<el-dialog v-model="edit.visible" :title="edit.mode===\'default\' ? (\'编辑默认值 - \' + (edit.row ? edit.row.param_name : \'\')) : (\'覆盖参数 - \' + (edit.row ? edit.row.param_name : \'\'))" width="520px" destroy-on-close>',
      '  <template v-if="edit.row">',
      '    <el-alert v-if="edit.mode===\'override\'" type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '      :title="\'全局默认值: \' + (edit.row.default_value == null || edit.row.default_value === \'\' ? \'(空)\' : (isBool(edit.row) ? (String(edit.row.default_value) === \'true\' ? \'开\' : \'关\') : (isEnum(edit.row) ? enumLabel(edit.row, edit.row.default_value) : edit.row.default_value))) + \' · 留空提交将删除本级覆盖恢复继承\'"></el-alert>',
      '    <el-form label-width="110px" size="default">',
      '      <el-form-item label="参数键"><span style="font-size:13px;color:#606266;">{{ edit.row.param_key }}</span></el-form-item>',
      '      <el-form-item label="参数值" required>',
      '        <el-input v-if="dtNorm(edit.row.data_type)===\'string\'" v-model="edit.value" :placeholder="constraintText(edit.row) || \'请输入参数值\'" clearable></el-input>',
      '        <el-input-number v-else-if="isInt(edit.row)" :model-value="edit.value === \'\' ? undefined : Number(edit.value)" @update:model-value="edit.value = ($event == null ? \'\' : String($event))" :precision="0" :min="valMin(edit.row.min_value)" :max="valMax(edit.row.max_value)" style="width:100%;" controls-position="right"></el-input-number>',
      '        <el-input-number v-else-if="isDecimal(edit.row)" :model-value="edit.value === \'\' ? undefined : Number(edit.value)" @update:model-value="edit.value = ($event == null ? \'\' : String($event))" :precision="2" :min="valMin(edit.row.min_value)" :max="valMax(edit.row.max_value)" style="width:100%;" controls-position="right"></el-input-number>',
      '        <el-switch v-else-if="isBool(edit.row)" :model-value="edit.value === \'true\'" @update:model-value="edit.value = $event ? \'true\' : \'false\'" active-text="开(true)" inactive-text="关(false)"></el-switch>',
      '        <el-select v-else-if="isEnum(edit.row)" v-model="edit.value" style="width:100%;" placeholder="选择枚举值">',
      '          <el-option v-for="o in enumOpts(edit.row)" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '        <el-input v-else v-model="edit.value" type="textarea" :rows="6" placeholder="请输入参数值"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="校验规则"><span style="font-size:12px;color:#909399;">{{ constraintText(edit.row) || \'无\' }}</span></el-form-item>',
      '      <el-form-item v-if="edit.row.remark" label="备注"><span style="font-size:12px;color:#606266;">{{ edit.row.remark }}</span></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="edit.visible = false">取消</el-button>',
      '      <el-button v-if="edit.mode===\'override\' && edit.row && edit.row.override_value != null && String(edit.row.override_value) !== \'\'" type="warning" plain :loading="edit.saving" @click="deleteOverride(edit.row)">恢复继承</el-button>',
      '      <el-button type="primary" :loading="edit.saving" @click="saveEdit">保存</el-button>',
      '    </template>',
      '  </template>',
      '</el-dialog>',
      /* ---- 参数定义弹窗(仅超管) ---- */
      '<el-dialog v-model="def.visible" :title="def.editing ? \'编辑参数定义 - \' + def.form.paramKey : \'新增参数定义\'" width="620px" destroy-on-close>',
      '  <el-form :model="def.form" label-width="110px" size="default">',
      '    <el-row :gutter="12">',
      '      <el-col :span="12"><el-form-item label="参数键" required>',
      '        <el-input v-model="def.form.paramKey" :disabled="def.editing" placeholder="如 system.session_timeout"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="参数名称" required>',
      '        <el-input v-model="def.form.paramName" placeholder="如 会话超时时间(分钟)"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="所属分组">',
      '        <el-select v-model="def.form.groupCode" clearable style="width:100%;" placeholder="选择分组(可空)">',
      '          <el-option v-for="g in groups" :key="g.groupCode" :label="g.groupName || g.groupCode" :value="g.groupCode"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="数据类型" required>',
      '        <el-select v-model="def.form.dataType" style="width:100%;">',
      '          <el-option label="文本(string)" value="string"></el-option>',
      '          <el-option label="整数(int)" value="int"></el-option>',
      '          <el-option label="小数(decimal)" value="decimal"></el-option>',
      '          <el-option label="布尔(bool)" value="bool"></el-option>',
      '          <el-option label="枚举(enum)" value="enum"></el-option>',
      '        </el-select>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="默认值" :required="def.form.required === 1">',
      '        <el-input v-if="def.form.dataType===\'string\'" v-model="def.form.defaultValue" placeholder="全局默认值"></el-input>',
      '        <el-input-number v-else-if="def.form.dataType===\'int\'" :model-value="def.form.defaultValue === \'\' ? undefined : Number(def.form.defaultValue)" @update:model-value="def.form.defaultValue = ($event == null ? \'\' : String($event))" :precision="0" :min="valMin(def.form.minValue)" :max="valMax(def.form.maxValue)" style="width:100%;" controls-position="right"></el-input-number>',
      '        <el-input-number v-else-if="def.form.dataType===\'decimal\'" :model-value="def.form.defaultValue === \'\' ? undefined : Number(def.form.defaultValue)" @update:model-value="def.form.defaultValue = ($event == null ? \'\' : String($event))" :precision="2" :min="valMin(def.form.minValue)" :max="valMax(def.form.maxValue)" style="width:100%;" controls-position="right"></el-input-number>',
      '        <el-switch v-else-if="def.form.dataType===\'bool\'" :model-value="def.form.defaultValue === \'true\'" @update:model-value="def.form.defaultValue = $event ? \'true\' : \'false\'"></el-switch>',
      '        <template v-else-if="def.form.dataType===\'enum\'">',
      '          <el-select v-model="def.form.defaultValue" style="width:100%;" placeholder="选择默认值" clearable>',
      '            <el-option v-for="o in parseEnumOpts(def.form.enumOptions)" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '          </el-select>',
      '        </template>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item v-if="def.form.dataType===\'enum\'" label="枚举选项" required>',
      '        <el-input v-model="def.form.enumOptions" placeholder="逗号分隔, 如 10,20,50,100"></el-input>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item v-if="def.form.dataType===\'int\' || def.form.dataType===\'decimal\'" label="最小值">',
      '        <el-input-number v-model="def.form.minValue" :precision="def.form.dataType===\'int\' ? 0 : 2" style="width:100%;" controls-position="right"></el-input-number>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item v-if="def.form.dataType===\'int\' || def.form.dataType===\'decimal\'" label="最大值">',
      '        <el-input-number v-model="def.form.maxValue" :precision="def.form.dataType===\'int\' ? 0 : 2" style="width:100%;" controls-position="right"></el-input-number>',
      '      </el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="是否必填">',
      '        <el-switch v-model="def.form.required" :active-value="1" :inactive-value="0" active-text="必填" inactive-text="可空"></el-switch>',
      '      </el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="允许配置层级" required>',
      '        <el-checkbox-group v-model="def.form.allowScopeArr">',
      '          <el-checkbox label="0">全局</el-checkbox>',
      '          <el-checkbox label="1">租户</el-checkbox>',
      '          <el-checkbox label="2">机构</el-checkbox>',
      '          <el-checkbox label="3">科室</el-checkbox>',
      '        </el-checkbox-group>',
      '      </el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="备注">',
      '        <el-input v-model="def.form.remark" type="textarea" :rows="2" placeholder="参数用途说明(选填)"></el-input>',
      '      </el-form-item></el-col>',
      '    </el-row>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="def.visible = false">取消</el-button>',
      '    <el-button type="primary" :loading="def.saving" @click="saveDef">保存定义</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 分组管理弹窗(仅超管) ---- */
      '<el-dialog v-model="grp.visible" title="管理参数分组" width="640px" destroy-on-close>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;"',
      '    title="分组为全局共享(跨租户), 仅平台超级管理员可维护; 删除分组不影响参数定义, 参数将显示在全部列表中。"></el-alert>',
      '  <el-table :data="grp.rows" border size="small" max-height="420">',
      '    <el-table-column label="分组编码" width="150">',
      '      <template #default="s"><el-input v-model="s.row.groupCode" size="small" :disabled="!s.row._new" placeholder="如 outpatient"></el-input></template>',
      '    </el-table-column>',
      '    <el-table-column label="分组名称" min-width="140">',
      '      <template #default="s"><el-input v-model="s.row.groupName" size="small" placeholder="如 门诊业务"></el-input></template>',
      '    </el-table-column>',
      '    <el-table-column label="排序" width="90">',
      '      <template #default="s"><el-input-number v-model="s.row.sortNo" size="small" :precision="0" controls-position="right" style="width:100%;"></el-input-number></template>',
      '    </el-table-column>',
      '    <el-table-column label="备注" min-width="120">',
      '      <template #default="s"><el-input v-model="s.row.remark" size="small"></el-input></template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="130" align="center">',
      '      <template #default="s">',
      '        <el-button type="primary" link size="small" :loading="s.row._saving" @click="saveGroupRow(s.row)">{{ s.row._new ? \'新增\' : \'保存\' }}</el-button>',
      '        <el-button type="danger" link size="small" @click="removeGroupRow(s.$index)">删除</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <div style="margin-top:10px;">',
      '    <el-button size="small" type="primary" plain @click="addGroupRow">新增分组</el-button>',
      '  </div>',
      '</el-dialog>'
    ].join('\n')
  };
})();
