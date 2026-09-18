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
        form: { mockEnabled: 1, status: 1 }
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/sys/tenant/current')
          .then(function (d) { if (d) { vm.form = d; } })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      save: function () {
        var vm = this;
        vm.saving = true;
        HIS.put('/api/sys/tenant/current', vm.form)
          .then(function () { HIS.notifySuccess('保存成功'); return vm.load(); })
          .catch(HIS.notifyError)
          .finally(function () { vm.saving = false; });
      }
    },
    template: [
      '<div class="page-card" v-loading="loading">',
      '  <div class="page-title">医院信息 / 医保接口配置</div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px;"',
      '    title="此处维护本院医保对接参数。开启“模拟平台”后所有医保交易走本地Mock，便于演示与联调。"></el-alert>',
      '  <el-form :model="form" label-width="140px" size="default">',
      '    <el-divider content-position="left">基本信息</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="医院登录码"><el-input v-model="form.tenantCode" disabled></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="医院名称"><el-input v-model="form.tenantName" disabled></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contact"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '      <el-col :span="24"><el-form-item label="医院地址"><el-input v-model="form.address"></el-input></el-form-item></el-col>',
      '    </el-row>',
      '    <el-divider content-position="left">医保机构参数</el-divider>',
      '    <el-row :gutter="16">',
      '      <el-col :span="12"><el-form-item label="定点医药机构编号"><el-input v-model="form.fixmedinsCode" placeholder="fixmedins_code"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="定点医药机构名称"><el-input v-model="form.fixmedinsName" placeholder="fixmedins_name"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="参保地医保区划"><el-input v-model="form.insuplcAdmdvs" placeholder="insuplc_admdvs"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="就诊地医保区划"><el-input v-model="form.mdtrtareaAdmvs" placeholder="mdtrtarea_admvs"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="接口版本号"><el-input v-model="form.infver"></el-input></el-form-item></el-col>',
      '      <el-col :span="12"><el-form-item label="接收系统编码"><el-input v-model="form.recerSysCode"></el-input></el-form-item></el-col>',
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
        roles: HIS.ROLES,
        dialogVisible: false,
        editing: false,
        form: this.emptyForm()
      };
    },
    created: function () { this.load(); },
    methods: {
      emptyForm: function () {
        return { id: null, username: '', password: '', realName: '', role: 'DOCTOR', staffId: null, deptId: null, phone: '', status: 1 };
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/sys/user/list')
          .then(function (d) { vm.list = d || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate: function () {
        this.editing = false;
        this.form = this.emptyForm();
        this.dialogVisible = true;
      },
      openEdit: function (row) {
        this.editing = true;
        this.form = { id: row.id, username: row.username, password: '', realName: row.realName, role: row.role, staffId: row.staffId, deptId: row.deptId, phone: row.phone, status: row.status == null ? 1 : row.status };
        this.dialogVisible = true;
      },
      submit: function () {
        var vm = this;
        if (!vm.editing && (!vm.form.username || !vm.form.password)) {
          ElementPlus.ElMessage.warning('账号与密码不能为空'); return;
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
      roleLabel: function (c) { return HIS.roleLabel(c); }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">用户管理</div>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate">新增用户</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 个账号（本院）</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="username" label="账号" width="140"></el-table-column>',
      '    <el-table-column prop="realName" label="姓名" width="120"></el-table-column>',
      '    <el-table-column label="角色" width="120"><template #default="s"><el-tag size="small">{{ roleLabel(s.row.role) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="phone" label="联系电话" width="140"></el-table-column>',
      '    <el-table-column prop="staffId" label="职工ID" width="90"></el-table-column>',
      '    <el-table-column prop="deptId" label="科室ID" width="90"></el-table-column>',
      '    <el-table-column label="状态" width="90"><template #default="s">',
      '      <el-tag :type="s.row.status === 1 ? \'success\' : \'info\'" size="small">{{ s.row.status === 1 ? "启用" : "停用" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" min-width="220">',
      '      <template #default="s">',
      '        <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button link type="warning" @click="resetPwd(s.row)">重置密码</el-button>',
      '        <el-button link :type="s.row.status === 1 ? \'info\' : \'success\'" @click="toggleStatus(s.row)">{{ s.row.status === 1 ? "停用" : "启用" }}</el-button>',
      '        <el-popconfirm title="确认删除该账号？" @confirm="remove(s.row)">',
      '          <template #reference><el-button link type="danger">删除</el-button></template>',
      '        </el-popconfirm>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dialogVisible" :title="editing ? \'编辑用户\' : \'新增用户\'" width="480px">',
      '    <el-form :model="form" label-width="90px">',
      '      <el-form-item label="账号"><el-input v-model="form.username" :disabled="editing" placeholder="登录账号"></el-input></el-form-item>',
      '      <el-form-item label="密码" v-if="!editing"><el-input v-model="form.password" type="password" show-password placeholder="至少6位"></el-input></el-form-item>',
      '      <el-form-item label="姓名"><el-input v-model="form.realName"></el-input></el-form-item>',
      '      <el-form-item label="角色">',
      '        <el-select v-model="form.role" style="width:100%">',
      '          <el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item>',
      '      <el-form-item label="职工ID"><el-input v-model.number="form.staffId" placeholder="关联his_staff(P1a)"></el-input></el-form-item>',
      '      <el-form-item label="科室ID"><el-input v-model.number="form.deptId" placeholder="关联his_dept(P1a)"></el-input></el-form-item>',
      '      <el-form-item label="状态" v-if="editing">',
      '        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dialogVisible = false">取消</el-button>',
      '      <el-button type="primary" @click="submit">确定</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
