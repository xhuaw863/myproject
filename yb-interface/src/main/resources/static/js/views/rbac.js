/* 系统管理 - 角色权限(RoleManage) + 菜单管理(MenuManage) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 菜单树扁平化(供上级下拉/展示) */
  function flattenMenus(nodes) {
    var out = [];
    (function walk(list, depth) {
      (list || []).forEach(function (n) {
        var pad = '';
        for (var i = 0; i < depth; i++) { pad += '　'; }
        out.push({ id: n.id, label: pad + n.menuName, menuKey: n.menuKey, menuType: n.menuType });
        if (n.children && n.children.length) { walk(n.children, depth + 1); }
      });
    })(nodes, 0);
    return out;
  }

  /* ============ 角色权限管理 ============ */
  HIS.views.RoleManage = {
    data: function () {
      return {
        loading: false, saving: false, list: [],
        dlg: false, editing: false, form: this.emptyRole(),
        menuDlg: false, menuTree: [], checkedKeys: [], currentRole: null, treeProps: { label: 'menuName', children: 'children' }
      };
    },
    created: function () { this.load(); },
    methods: {
      emptyRole: function () { return { id: null, roleCode: '', roleName: '', remark: '', status: 1 }; },
      isGlobal: function (row) { return row.tenantId == null || row.roleType === 1; },
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/sys/role/list')
          .then(function (d) { vm.list = d || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate: function () { this.editing = false; this.form = this.emptyRole(); this.dlg = true; },
      openEdit: function (row) {
        this.editing = true;
        this.form = { id: row.id, roleCode: row.roleCode, roleName: row.roleName, remark: row.remark || '', status: row.status == null ? 1 : row.status };
        this.dlg = true;
      },
      submit: function () {
        var vm = this; var f = vm.form;
        if (!f.roleCode || !String(f.roleCode).trim()) { ElementPlus.ElMessage.warning('请填写角色编码'); return; }
        if (!f.roleName || !f.roleName.trim()) { ElementPlus.ElMessage.warning('请填写角色名称'); return; }
        var body = Object.assign({}, f, { roleCode: String(f.roleCode).trim(), roleName: f.roleName.trim() });
        vm.saving = true;
        var req = vm.editing ? HIS.put('/api/sys/role', body) : HIS.post('/api/sys/role', body);
        req.then(function () { HIS.notifySuccess(vm.editing ? '已保存' : '已新增'); vm.dlg = false; vm.load(); })
          .catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除角色「' + row.roleName + '」?', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/sys/role/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      },
      openMenus: function (row) {
        var vm = this; vm.currentRole = row;
        Promise.all([
          HIS.get('/api/sys/menu/tree'),
          HIS.get('/api/sys/role/' + row.id + '/menus')
        ]).then(function (res) {
          vm.menuTree = res[0] || [];
          vm.checkedKeys = res[1] || [];
          vm.menuDlg = true;
        }).catch(HIS.notifyError);
      },
      saveMenus: function () {
        var vm = this;
        var keys = vm.$refs.menuTreeRef ? vm.$refs.menuTreeRef.getCheckedKeys() : vm.checkedKeys;
        vm.saving = true;
        HIS.put('/api/sys/role/' + vm.currentRole.id + '/menus', { menuIds: keys })
          .then(function () { HIS.notifySuccess('菜单授权已保存'); vm.menuDlg = false; })
          .catch(HIS.notifyError).finally(function () { vm.saving = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">角色权限 <span style="font-size:12px;color:#909399;font-weight:normal;">(全局预置角色只读 · 租户自定义角色可增改删/授权)</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="ADMIN/SUPER_ADMIN 拥有全部菜单; 其余角色按分配的菜单树授权。用户登录后左侧菜单按其角色动态下发。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate">新增角色</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="roleCode" label="角色编码" width="150"></el-table-column>',
      '    <el-table-column prop="roleName" label="角色名称" width="150"></el-table-column>',
      '    <el-table-column label="类型" width="120"><template #default="s">',
      '      <el-tag size="small" :type="isGlobal(s.row) ? \'warning\' : \'success\'">{{ isGlobal(s.row) ? "全局预置" : "租户自定义" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="菜单范围" width="110"><template #default="s">',
      '      <el-tag size="small" :type="s.row.allMenus === 1 ? \'danger\' : \'info\'">{{ s.row.allMenus === 1 ? "全部菜单" : "按授权" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="状态" width="80"><template #default="s">',
      '      <el-tag :type="s.row.status === 1 ? \'success\' : \'info\'" size="small">{{ s.row.status === 1 ? "启用" : "停用" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" width="240" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openMenus(s.row)" :disabled="isGlobal(s.row)">分配菜单</el-button>',
      '      <el-button link type="primary" @click="openEdit(s.row)" :disabled="isGlobal(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)" :disabled="isGlobal(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑角色\':\'新增角色\'" width="460px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="角色编码"><el-input v-model="form.roleCode" :disabled="editing" placeholder="如 WARD_ADMIN"></el-input></el-form-item>',
      '      <el-form-item label="角色名称"><el-input v-model="form.roleName"></el-input></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '      <el-form-item label="状态" v-if="editing">',
      '        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '  <el-dialog v-model="menuDlg" :title="\'分配菜单 - \' + (currentRole ? currentRole.roleName : \'\')" width="460px">',
      '    <el-tree ref="menuTreeRef" :data="menuTree" :props="treeProps" node-key="id" show-checkbox default-expand-all :default-checked-keys="checkedKeys"></el-tree>',
      '    <template #footer><el-button @click="menuDlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMenus">保存授权</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ============ 菜单管理 ============ */
  HIS.views.MenuManage = {
    data: function () {
      return {
        loading: false, saving: false, tree: [], flat: [],
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.load(); },
    methods: {
      empty: function () {
        return { id: null, parentId: 0, menuKey: '', menuName: '', menuType: 2, comp: '', phase: '', icon: '', sortNo: 0, visible: 1, status: 1 };
      },
      typeLabel: function (t) { return Number(t) === 1 ? '目录' : '菜单'; },
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/sys/menu/tree')
          .then(function (d) { vm.tree = d || []; vm.flat = flattenMenus(vm.tree); })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate: function (row) {
        this.editing = false;
        var f = this.empty();
        if (row && row.id) { f.parentId = row.id; f.menuType = 2; }
        this.form = f; this.dlg = true;
      },
      openEdit: function (row) {
        this.editing = true;
        this.form = {
          id: row.id, parentId: row.parentId == null ? 0 : row.parentId, menuKey: row.menuKey, menuName: row.menuName,
          menuType: Number(row.menuType) || 2, comp: row.comp || '', phase: row.phase || '', icon: row.icon || '',
          sortNo: row.sortNo == null ? 0 : row.sortNo, visible: row.visible == null ? 1 : row.visible, status: row.status == null ? 1 : row.status
        };
        this.dlg = true;
      },
      submit: function () {
        var vm = this; var f = vm.form;
        if (!f.menuKey || !String(f.menuKey).trim()) { ElementPlus.ElMessage.warning('请填写菜单键'); return; }
        if (!f.menuName || !f.menuName.trim()) { ElementPlus.ElMessage.warning('请填写菜单名称'); return; }
        var body = Object.assign({}, f, {
          menuKey: String(f.menuKey).trim(), menuName: f.menuName.trim(),
          menuType: Number(f.menuType), parentId: (f.parentId == null || f.parentId === '') ? 0 : Number(f.parentId)
        });
        vm.saving = true;
        var req = vm.editing ? HIS.put('/api/sys/menu', body) : HIS.post('/api/sys/menu', body);
        req.then(function () { HIS.notifySuccess(vm.editing ? '已保存' : '已新增'); vm.dlg = false; vm.load(); })
          .catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除菜单「' + row.menuName + '」? 有下级菜单时需先删下级。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/sys/menu/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">菜单管理 <span style="font-size:12px;color:#909399;font-weight:normal;">(全局菜单真源 · 仅 ADMIN 可维护)</span></div>',
      '  <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="菜单为全局真源, 修改将影响所有租户。菜单键需与前端组件路由键一致; comp 为 HIS.views 组件名, 目录留空。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate(null)">新增顶级菜单</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="tree" v-loading="loading" border stripe size="small" row-key="id" :tree-props="{ children: \'children\' }" default-expand-all>',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="menuName" label="菜单名称" min-width="180"></el-table-column>',
      '    <el-table-column prop="menuKey" label="菜单键" width="150"></el-table-column>',
      '    <el-table-column label="类型" width="80"><template #default="s"><el-tag size="small" :type="s.row.menuType === 1 ? \'warning\' : \'\'">{{ typeLabel(s.row.menuType) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="comp" label="组件(comp)" width="150"></el-table-column>',
      '    <el-table-column prop="phase" label="阶段" width="80"></el-table-column>',
      '    <el-table-column prop="sortNo" label="排序" width="70"></el-table-column>',
      '    <el-table-column label="显示" width="70"><template #default="s">{{ s.row.visible === 1 ? "是" : "否" }}</template></el-table-column>',
      '    <el-table-column label="操作" width="220" fixed="right"><template #default="s">',
      '      <el-button link type="success" @click="openCreate(s.row)">加子项</el-button>',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑菜单\':\'新增菜单\'" width="520px">',
      '    <el-form :model="form" label-width="100px">',
      '      <el-form-item label="上级菜单">',
      '        <el-select v-model="form.parentId" style="width:100%" filterable>',
      '          <el-option :value="0" label="顶级"></el-option>',
      '          <el-option v-for="m in flat" :key="m.id" :label="m.label" :value="m.id" :disabled="editing && m.id === form.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="菜单键"><el-input v-model="form.menuKey" placeholder="=前端组件路由键, 如 user-manage"></el-input></el-form-item>',
      '      <el-form-item label="菜单名称"><el-input v-model="form.menuName"></el-input></el-form-item>',
      '      <el-form-item label="类型">',
      '        <el-radio-group v-model="form.menuType">',
      '          <el-radio :label="1">目录</el-radio><el-radio :label="2">菜单</el-radio>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="组件名"><el-input v-model="form.comp" placeholder="HIS.views 组件名(目录留空), 如 UserManage"></el-input></el-form-item>',
      '      <el-form-item label="阶段占位"><el-input v-model="form.phase" placeholder="无 comp 时显示建设中, 如 P1d"></el-input></el-form-item>',
      '      <el-form-item label="图标"><el-input v-model="form.icon"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input v-model.number="form.sortNo"></el-input></el-form-item>',
      '      <el-form-item label="是否显示"><el-switch v-model="form.visible" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
