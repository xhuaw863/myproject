/* 供货商(企业)字典维护: 全局标准字典(L0), 归"医共体管理›基础数据", 由牵头机构系统管理员维护(平台超管亦可)。
   数据由医保各目录生产企业去重汇总导入; 医共体药品/耗材目录的企业字段以编码引用本字典。
   维护走机构级专用端点(/api/supplier-dict/*), 只读浏览(目录企业下拉)走通用 /api/std-dict/query/supplier。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var KEY = 'supplier';
  var TYPES = ['生产企业', '上市许可持有人', '生产企业/上市许可持有人', '经营企业', '制剂配制单位'];
  var CATS = [
    { v: 'drug', l: '药品目录' },
    { v: 'consumable', l: '耗材目录' },
    { v: 'ivd', l: '体外诊断试剂' },
    { v: 'preparation', l: '医疗机构制剂' }
  ];

  /* 文本字段清单(新增/编辑/回显共用): 与 std_supplier 新增列一一对应 */
  var SUP_TEXT = ['sup_code', 'sup_name', 'sup_short_name', 'sup_type', 'uscc', 'legal_person', 'reg_capital', 'estab_date', 'reg_address', 'business_scope',
    'license_no', 'license_expiry', 'license_authority', 'gmp_gsp_no', 'gmp_gsp_expiry',
    'contact_person', 'contact_phone', 'fax', 'email', 'bank_name', 'bank_account', 'tax_no', 'invoice_title',
    'two_ticket_flag', 'platform_code', 'delivery_area', 'blacklist_flag'];
  function newForm(code) {
    var f = { vali_flag: '1', src_catalog: [] };
    SUP_TEXT.forEach(function (k) { f[k] = ''; });
    if (code) { f.sup_code = code; }
    return f;
  }
  function formFromRow(d) {
    d = d || {};
    var f = newForm();
    SUP_TEXT.forEach(function (k) { if (d[k] != null) { f[k] = String(d[k]); } });
    f.vali_flag = (d.vali_flag != null && d.vali_flag !== '') ? String(d.vali_flag) : '1';
    f.src_catalog = d.src_catalog ? String(d.src_catalog).split(',') : [];
    return f;
  }

  HIS.views.SupplierDict = {
    data: function () {
      return {
        keyword: '', page: 1, size: 20, loading: false, list: [], total: 0,
        dlg: false, editing: false, saving: false, editId: null,
        form: newForm(),
        typeOpts: TYPES, catOpts: CATS, importing: false
      };
    },
    computed: {
      ph: function () { return '企业名称/编码/拼音简码检索'; }
    },
    created: function () { this.search(); },
    methods: {
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      search: function () { this.page = 1; this.fetch(); },
      onPage: function (p) { this.page = p; this.fetch(); },
      onSize: function (s) { this.size = s; this.page = 1; this.fetch(); },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/supplier-dict/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      catText: function (extra) {
        if (!extra) { return '—'; }
        var map = {}; CATS.forEach(function (c) { map[c.v] = c.l; });
        return String(extra).split(',').map(function (v) { return map[v] || v; }).join('、');
      },
      openCreate: function () {
        this.form = newForm(this.genCode());
        this.editing = false; this.editId = null; this.dlg = true;
      },
      openEdit: function (row) {
        var vm = this;
        HIS.get('/api/supplier-dict/row/' + row.id).then(function (d) {
          vm.form = formFromRow(d);
          vm.editing = true; vm.editId = row.id; vm.dlg = true;
        }).catch(HIS.notifyError);
      },
      genCode: function () {
        return 'GYS' + String(Date.now()).slice(-7);
      },
      save: function () {
        var vm = this; var f = vm.form;
        if (!f.sup_code || !String(f.sup_code).trim()) { ElementPlus.ElMessage.warning('供货商编码必填'); return; }
        if (!f.sup_name || !String(f.sup_name).trim()) { ElementPlus.ElMessage.warning('企业名称必填'); return; }
        var body = {};
        SUP_TEXT.forEach(function (k) { body[k] = f[k] != null ? String(f[k]).trim() : ''; });
        body.vali_flag = f.vali_flag || '1';
        body.src_catalog = (f.src_catalog && f.src_catalog.length) ? f.src_catalog.join(',') : '';
        vm.saving = true;
        var p = vm.editing
          ? HIS.put('/api/supplier-dict/' + vm.editId, body)
          : HIS.post('/api/supplier-dict', body);
        p.then(function () {
          HIS.notifySuccess(vm.editing ? '修改成功' : '新增成功');
          vm.dlg = false; vm.fetch();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除供货商【' + row.name + '】? 若已被药品/耗材目录引用, 删除后目录侧编码将查不到名称。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/supplier-dict/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.fetch(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      doImport: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '从医保各目录(药品/耗材/体外诊断试剂/医疗机构制剂)去重汇总企业, 全量重写供货商字典(约1.8万条)。将清空现有 std_supplier 后重建, 手工新增的条目会被覆盖。确认继续?',
          '从医保目录去重导入', { type: 'warning' })
          .then(function () {
            vm.importing = true;
            return HIS.get('/api/supplier-dict/import');
          }).then(function (d) {
            if (d && d.status === 'SUCCESS') { HIS.notifySuccess('导入成功: ' + d.rows + ' 家企业'); vm.search(); }
            else { ElementPlus.ElMessage.error('导入失败: ' + ((d && d.message) || '未知错误')); }
          }).catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } })
            .finally(function () { vm.importing = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">供货商(企业)字典维护</div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;"',
      '    title="供货商(企业)字典: 由医保药品/耗材/体外诊断试剂/医疗机构制剂各目录的生产企业名去重汇总(全局共享, 不分医院)。医共体药品/耗材目录的\u0027生产企业/上市许可持有人\u0027按本字典编码引用, 取代自由汉字文本。由牵头机构系统管理员维护(平台超管亦可)。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" :placeholder="ph" clearable style="width:280px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button type="success" @click="openCreate">新增供货商</el-button>',
      '    <el-button type="warning" :loading="importing" @click="doImport">从医保目录去重导入</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 家企业</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="code" label="供货商编码" width="140" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" label="企业名称" min-width="260" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="pyCode" label="拼音码" width="120" show-overflow-tooltip><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="spec" label="企业类型" width="160" show-overflow-tooltip><template #default="s">{{ s.row.spec || \u0027—\u0027 }}</template></el-table-column>',
      '    <el-table-column label="来源目录" min-width="180" show-overflow-tooltip><template #default="s">{{ catText(s.row.extra) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="editing ? (\'编辑供货商 #\' + editId) : \'新增供货商\'" width="900px" top="6vh">',
      '    <el-form label-position="top" size="small">',
      '      <el-divider content-position="left">基础信息</el-divider>',
      '      <el-row :gutter="14">',
      '        <el-col :span="8"><el-form-item label="供货商编码(必填, 唯一)"><el-input v-model="form.sup_code" placeholder="如 GYS0000001"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="企业简称"><el-input v-model="form.sup_short_name" placeholder="企业简称/常用名"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="统一社会信用代码"><el-input v-model="form.uscc" placeholder="18位社会信用代码"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="企业名称(必填)"><el-input v-model="form.sup_name" placeholder="企业全称"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="企业类型"><el-select v-model="form.sup_type" filterable allow-create clearable style="width:100%" placeholder="选择或输入"><el-option v-for="t in typeOpts" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="状态"><el-select v-model="form.vali_flag" style="width:100%"><el-option label="有效" value="1"></el-option><el-option label="作废" value="0"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="法定代表人"><el-input v-model="form.legal_person"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="注册资本(万元)"><el-input v-model="form.reg_capital"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="成立日期"><el-input v-model="form.estab_date" placeholder="YYYY-MM-DD"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="注册地址"><el-input v-model="form.reg_address"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="生产/经营范围"><el-input type="textarea" :rows="2" v-model="form.business_scope"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">资质证照</el-divider>',
      '      <el-row :gutter="14">',
      '        <el-col :span="8"><el-form-item label="药品生产/经营许可证号"><el-input v-model="form.license_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="许可证有效期"><el-input v-model="form.license_expiry" placeholder="YYYY-MM-DD"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="许可证发证机关"><el-input v-model="form.license_authority"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="GMP/GSP证书号"><el-input v-model="form.gmp_gsp_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="GMP/GSP证书有效期"><el-input v-model="form.gmp_gsp_expiry" placeholder="YYYY-MM-DD"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">联系与开票</el-divider>',
      '      <el-row :gutter="14">',
      '        <el-col :span="8"><el-form-item label="联系人"><el-input v-model="form.contact_person"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="联系电话"><el-input v-model="form.contact_phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="传真"><el-input v-model="form.fax"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="电子邮箱"><el-input v-model="form.email"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="纳税人识别号"><el-input v-model="form.tax_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="开户银行"><el-input v-model="form.bank_name"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="银行账号"><el-input v-model="form.bank_account"></el-input></el-form-item></el-col>',
      '        <el-col :span="16"><el-form-item label="发票抬头"><el-input v-model="form.invoice_title"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">招采与合规</el-divider>',
      '      <el-row :gutter="14">',
      '        <el-col :span="8"><el-form-item label="是否两票制"><el-select v-model="form.two_ticket_flag" clearable style="width:100%" placeholder="未填"><el-option label="是" value="1"></el-option><el-option label="否" value="0"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="失信/黑名单"><el-select v-model="form.blacklist_flag" clearable style="width:100%" placeholder="未填"><el-option label="是" value="1"></el-option><el-option label="否" value="0"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="招采/挂网平台编码"><el-input v-model="form.platform_code"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="配送区域"><el-input v-model="form.delivery_area" placeholder="如: 湖北省孝感市"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="来源目录"><el-select v-model="form.src_catalog" multiple clearable style="width:100%" placeholder="企业出现的目录(可多选)"><el-option v-for="c in catOpts" :key="c.v" :label="c.l" :value="c.v"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
