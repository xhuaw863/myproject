/* 系统管理 - 机构管理: 医共体县/乡/村三级机构树维护 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var LEVELS = [
    { v: 1, l: '县级(牵头)', t: 'danger' },
    { v: 2, l: '乡镇', t: 'warning' },
    { v: 3, l: '村', t: 'success' }
  ];
  /* 收费价格档次: 医共体分级价格执行档(决定收费项目取 price_l1/l2/l3) */
  var PRICE_LV = [
    { v: 1, l: '一级( price_l1)' },
    { v: 2, l: '二级( price_l2)' },
    { v: 3, l: '三级( price_l3)' }
  ];
  function levelLabel(v) {
    for (var i = 0; i < LEVELS.length; i++) { if (LEVELS[i].v === Number(v)) { return LEVELS[i].l; } }
    return v == null ? '-' : String(v);
  }
  function levelTag(v) {
    for (var i = 0; i < LEVELS.length; i++) { if (LEVELS[i].v === Number(v)) { return LEVELS[i].t; } }
    return 'info';
  }

  HIS.views.OrgManage = {
    data: function () {
      return {
        loading: false, saving: false,
        tree: [], flat: [], levelOpts: LEVELS, priceLvOpts: PRICE_LV,
        orgTypeOpts: [], orgTypeMap: {}, areaOpts: [], areaLoading: false,
        fixTypeOpts: [], fixTypeMap: {}, hospLvOpts: [], hospLvMap: {},
        dlg: false, editing: false, activeTab: 'basic', form: this.empty()
      };
    },
    created: function () { this.loadOrgTypes(); this.load(); },
    methods: {
      loadOrgTypes: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'MEDINS_TYPE').then(function (list) {
          vm.orgTypeOpts = list || []; vm.orgTypeMap = HIS.dictMap(list);
        }).catch(HIS.notifyError);
        HIS.stdValues('cv_code', 'fixmedins_type').then(function (list) {
          vm.fixTypeOpts = list || []; vm.fixTypeMap = HIS.dictMap(list);
        }).catch(HIS.notifyError);
        HIS.stdValues('cv_code', 'hosp_lv').then(function (list) {
          vm.hospLvOpts = list || []; vm.hospLvMap = HIS.dictMap(list);
        }).catch(HIS.notifyError);
      },
      areaRemoteSearch: function (q) {
        var vm = this;
        if (!q) { return; }
        vm.areaLoading = true;
        HIS.areaSearch(q).then(function (list) {
          vm.areaOpts = (list || []).map(function (a) { return { code: String(a.code), name: a.name }; });
        }).catch(HIS.notifyError).finally(function () { vm.areaLoading = false; });
      },
      empty: function () {
        return {
          id: null, orgCode: '', orgName: '', orgLevel: 1, parentId: 0, orgType: '',
          fixmedinsCode: '', fixmedinsName: '', uscc: '', fixmedinsType: '', hospLv: '',
          pdLicenseNo: '', bedCnt: null, priceLv: null, admvsCode: '', leader: '', phone: '', address: '',
          mdtrtareaAdmvs: '', insuplcAdmdvs: '', apiUrl: '', fileDownloadUrl: '', recerSysCode: '',
          infver: '', opterType: '', opter: '', opterName: '', signNo: '',
          sm2PrivateKey: '', sm2PublicKey: '', encType: '', mockEnabled: null,
          sortNo: 0, status: 1
        };
      },
      levelLabel: levelLabel,
      levelTag: levelTag,
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/sys/org/tree')
          .then(function (d) { vm.tree = d || []; vm.flat = HIS.flattenOrgs(vm.tree); })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate: function (row) {
        this.editing = false;
        this.areaOpts = [];
        var f = this.empty();
        /* 从某机构行新增下级: 预置上级并按级别推断 */
        if (row && row.id) {
          f.parentId = row.id;
          f.orgLevel = Math.min(3, (Number(row.orgLevel) || 0) + 1);
        }
        this.form = f; this.activeTab = 'basic'; this.dlg = true;
      },
      openEdit: function (row) {
        this.editing = true;
        var vm = this;
        this.form = {
          id: row.id, orgCode: row.orgCode, orgName: row.orgName,
          orgLevel: Number(row.orgLevel) || 1, parentId: row.parentId == null ? 0 : row.parentId,
          orgType: row.orgType || '', fixmedinsCode: row.fixmedinsCode || '', admvsCode: row.admvsCode || '',
          fixmedinsName: row.fixmedinsName || '', uscc: row.uscc || '',
          fixmedinsType: row.fixmedinsType || '', hospLv: row.hospLv || '',
          pdLicenseNo: row.pdLicenseNo || '', bedCnt: row.bedCnt == null ? null : row.bedCnt,
          priceLv: row.priceLv == null ? null : Number(row.priceLv),
          leader: row.leader || '', phone: row.phone || '', address: row.address || '',
          mdtrtareaAdmvs: row.mdtrtareaAdmvs || '', insuplcAdmdvs: row.insuplcAdmdvs || '',
          apiUrl: row.apiUrl || '', fileDownloadUrl: row.fileDownloadUrl || '', recerSysCode: row.recerSysCode || '',
          infver: row.infver || '', opterType: row.opterType || '', opter: row.opter || '', opterName: row.opterName || '',
          signNo: row.signNo || '', sm2PrivateKey: row.sm2PrivateKey || '', sm2PublicKey: row.sm2PublicKey || '',
          encType: row.encType || '', mockEnabled: row.mockEnabled == null ? null : Number(row.mockEnabled),
          sortNo: row.sortNo == null ? 0 : row.sortNo, status: row.status == null ? 1 : row.status
        };
        this.areaOpts = row.admvsCode ? [{ code: String(row.admvsCode), name: String(row.admvsCode) }] : [];
        if (row.admvsCode) {
          HIS.areaName(row.admvsCode).then(function (nm) {
            if (nm) { vm.areaOpts = [{ code: String(row.admvsCode), name: nm }]; }
          });
        }
        this.activeTab = 'basic';
        this.dlg = true;
      },
      submit: function () {
        var vm = this; var f = vm.form;
        if (!f.orgCode || !String(f.orgCode).trim()) { ElementPlus.ElMessage.warning('请填写机构编码'); return; }
        if (!f.orgName || !f.orgName.trim()) { ElementPlus.ElMessage.warning('请填写机构名称'); return; }
        var body = Object.assign({}, f, {
          orgCode: String(f.orgCode).trim(), orgName: f.orgName.trim(),
          orgLevel: Number(f.orgLevel), parentId: (f.parentId == null || f.parentId === '') ? 0 : Number(f.parentId),
          priceLv: (f.priceLv == null || f.priceLv === '') ? null : Number(f.priceLv)
        });
        if (body.editing) { delete body.editing; }
        vm.saving = true;
        var req = vm.editing ? HIS.put('/api/sys/org', body) : HIS.post('/api/sys/org', body);
        req.then(function () {
          HIS.notifySuccess(vm.editing ? '已保存' : '已新增');
          vm.dlg = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除机构「' + row.orgName + '」? 有下级机构或已关联用户时不可删除。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/sys/org/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">机构管理 <span style="font-size:12px;color:#909399;font-weight:normal;">(医共体县/乡/村三级 · 全医共体共享一套)</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="牵头机构(ADMIN)统一维护全医共体机构树。机构仅作数据归属标注, 患者档案/基础字典在医共体内跨机构共享。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate(null)">新增县级机构</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="tree" v-loading="loading" border stripe size="small" row-key="id" :tree-props="{ children: \'children\' }" default-expand-all>',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="orgName" label="机构名称" min-width="220"></el-table-column>',
      '    <el-table-column prop="orgCode" label="机构编码" width="150"></el-table-column>',
      '    <el-table-column label="级别" width="120"><template #default="s"><el-tag size="small" :type="levelTag(s.row.orgLevel)">{{ levelLabel(s.row.orgLevel) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="机构类型" width="140"><template #default="s">{{ s.row.orgTypeName || orgTypeMap[s.row.orgType] || s.row.orgType || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="fixmedinsCode" label="定点机构编号" width="150"></el-table-column>',
      '    <el-table-column prop="leader" label="负责人" width="100"></el-table-column>',
      '    <el-table-column prop="phone" label="联系电话" width="130"></el-table-column>',
      '    <el-table-column label="状态" width="80"><template #default="s">',
      '      <el-tag :type="s.row.status === 1 ? \'success\' : \'info\'" size="small">{{ s.row.status === 1 ? "启用" : "停用" }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="操作" width="220" fixed="right"><template #default="s">',
      '      <el-button link type="success" @click="openCreate(s.row)">加下级</el-button>',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑机构\':\'新增机构\'" width="680px" top="6vh">',
      '    <el-form :model="form" label-width="110px">',
      '      <el-tabs v-model="activeTab">',
      '        <el-tab-pane label="基本信息" name="basic">',
      '          <div style="max-height:50vh;overflow-y:auto;padding-right:6px;">',
      '      <el-form-item label="机构编码"><el-input v-model="form.orgCode" placeholder="医共体内唯一, 如 H42010000000"></el-input></el-form-item>',
      '      <el-form-item label="机构名称"><el-input v-model="form.orgName"></el-input></el-form-item>',
      '      <el-form-item label="机构级别">',
      '        <el-select v-model="form.orgLevel" style="width:100%">',
      '          <el-option v-for="o in levelOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="上级机构">',
      '        <el-select v-model="form.parentId" style="width:100%" filterable>',
      '          <el-option :value="0" label="无(顶级/县级牵头)"></el-option>',
      '          <el-option v-for="o in flat" :key="o.id" :label="o.label" :value="o.id" :disabled="editing && o.id === form.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="机构类型">',
      '        <el-select v-model="form.orgType" style="width:100%" filterable clearable placeholder="选择医保字典 MEDINS_TYPE">',
      '          <el-option v-for="o in orgTypeOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="定点机构编号"><el-input v-model="form.fixmedinsCode" placeholder="fixmedins_code(机构级)"></el-input></el-form-item>',
      '      <el-form-item label="定点机构名称"><el-input v-model="form.fixmedinsName" placeholder="医保登记的定点医药机构名称"></el-input></el-form-item>',
      '      <el-form-item label="统一社会信用代码"><el-input v-model="form.uscc" placeholder="uscc"></el-input></el-form-item>',
      '      <el-form-item label="定点机构类型">',
      '        <el-select v-model="form.fixmedinsType" style="width:100%" filterable clearable placeholder="选择医保字典 fixmedins_type">',
      '          <el-option v-for="o in fixTypeOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="医院等级">',
      '        <el-select v-model="form.hospLv" style="width:100%" filterable clearable placeholder="选择医保字典 hosp_lv(影响起付线/报销比例)">',
      '          <el-option v-for="o in hospLvOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="执业许可证号"><el-input v-model="form.pdLicenseNo" placeholder="医疗机构执业许可证号"></el-input></el-form-item>',
      '      <el-form-item label="收费价格档次">',
      '        <el-select v-model="form.priceLv" style="width:100%" clearable placeholder="医共体分级价格执行档(决定收费项目取哪一档价)">',
      '          <el-option v-for="o in priceLvOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="编制床位数"><el-input v-model.number="form.bedCnt" type="number" placeholder="卫统/评审/绩效"></el-input></el-form-item>',
      '      <el-form-item label="行政区划码">',
      '        <el-select v-model="form.admvsCode" style="width:100%" filterable remote clearable :remote-method="areaRemoteSearch" :loading="areaLoading" placeholder="输入名称/编码检索 area_code_2021">',
      '          <el-option v-for="a in areaOpts" :key="a.code" :label="a.name + \' (\' + a.code + \')\'" :value="a.code"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="负责人"><el-input v-model="form.leader"></el-input></el-form-item>',
      '      <el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item>',
      '      <el-form-item label="机构地址"><el-input v-model="form.address"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input v-model.number="form.sortNo"></el-input></el-form-item>',
      '      <el-form-item label="状态">',
      '        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>',
      '      </el-form-item>',
      '          </div>',
      '        </el-tab-pane>',
      '        <el-tab-pane label="医保接口配置(机构级)" name="yb">',
      '          <div style="max-height:50vh;overflow-y:auto;padding-right:6px;">',
      '            <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;"',
      '              title="机构级医保接口参数; 留空则继承租户/全局配置。"></el-alert>',
      '      <el-form-item label="就医地区划"><el-input v-model="form.mdtrtareaAdmvs" placeholder="mdtrtarea_admvs"></el-input></el-form-item>',
      '      <el-form-item label="参保地区划"><el-input v-model="form.insuplcAdmdvs" placeholder="insuplc_admdvs(存储备用)"></el-input></el-form-item>',
      '      <el-form-item label="医保接口地址"><el-input v-model="form.apiUrl" placeholder="api_url"></el-input></el-form-item>',
      '      <el-form-item label="文件下载地址"><el-input v-model="form.fileDownloadUrl" placeholder="file_download_url"></el-input></el-form-item>',
      '      <el-form-item label="接收系统编码"><el-input v-model="form.recerSysCode" placeholder="recer_sys_code"></el-input></el-form-item>',
      '      <el-form-item label="接口版本号"><el-input v-model="form.infver" placeholder="infver, 如 1.0"></el-input></el-form-item>',
      '      <el-form-item label="经办人类别"><el-input v-model="form.opterType" placeholder="opter_type"></el-input></el-form-item>',
      '      <el-form-item label="经办人编号"><el-input v-model="form.opter" placeholder="opter"></el-input></el-form-item>',
      '      <el-form-item label="经办人姓名"><el-input v-model="form.opterName" placeholder="opter_name"></el-input></el-form-item>',
      '      <el-form-item label="签名号"><el-input v-model="form.signNo" placeholder="sign_no"></el-input></el-form-item>',
      '      <el-form-item label="加密方式"><el-input v-model="form.encType" placeholder="enc_type"></el-input></el-form-item>',
      '      <el-form-item label="SM2私钥"><el-input v-model="form.sm2PrivateKey" type="textarea" :rows="2" placeholder="只写不回显; 留空则不修改原私钥"></el-input></el-form-item>',
      '      <el-form-item label="SM2公钥"><el-input v-model="form.sm2PublicKey" type="textarea" :rows="2" placeholder="sm2_public_key"></el-input></el-form-item>',
      '      <el-form-item label="平台模式">',
      '        <el-select v-model="form.mockEnabled" style="width:100%" clearable placeholder="留空=继承租户/全局">',
      '          <el-option :value="1" label="模拟平台"></el-option>',
      '          <el-option :value="0" label="真实平台"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '          </div>',
      '        </el-tab-pane>',
      '      </el-tabs>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
