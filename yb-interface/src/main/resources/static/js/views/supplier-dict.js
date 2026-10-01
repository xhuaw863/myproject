/* 企业字典维护: 全局标准字典(L0), 归"医共体管理›基础数据", 由牵头机构系统管理员维护(平台超管亦可)。
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
  var DOC_TYPES = ['许可证', 'GMP', 'GSP', '营业执照', '其他'];

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
        dlg: false, editing: false, saving: false, editId: null, activeTab: 'basic',
        form: newForm(),
        typeOpts: TYPES, catOpts: CATS, docTypeOpts: DOC_TYPES, importing: false,
        /* 资质文件管理 */
        fileList: [], fileLoading: false, uploadDocType: '许可证', uploadRemark: ''
      };
    },
    computed: {
      ph: function () { return '企业名称/编码/拼音简码检索'; },
      uploadHeaders: function () { return { Authorization: 'Bearer ' + (HIS.getToken && HIS.getToken()) }; },
      uploadData: function () { return { supCode: this.form.sup_code || '', docType: this.uploadDocType, remark: this.uploadRemark }; },
      uploadUrl: function () { return '/api/supplier-dict/upload-file'; }
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
        this.editing = false; this.editId = null; this.activeTab = 'basic';
        this.fileList = []; this.uploadDocType = '许可证'; this.uploadRemark = '';
        this.dlg = true;
      },
      openEdit: function (row) {
        var vm = this;
        HIS.get('/api/supplier-dict/row/' + row.id).then(function (d) {
          vm.form = formFromRow(d);
          vm.editing = true; vm.editId = row.id; vm.activeTab = 'basic';
          vm.uploadDocType = '许可证'; vm.uploadRemark = '';
          vm.loadFiles();
          vm.dlg = true;
        }).catch(HIS.notifyError);
      },
      genCode: function () {
        return 'GYS' + String(Date.now()).slice(-7);
      },
      save: function () {
        var vm = this; var f = vm.form;
        if (!f.sup_code || !String(f.sup_code).trim()) { ElementPlus.ElMessage.warning('企业编码必填'); return; }
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
        ElementPlus.ElMessageBox.confirm('确认删除企业【' + row.name + '】? 若已被药品/耗材目录引用, 删除后目录侧编码将查不到名称。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/supplier-dict/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.fetch(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      doImport: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '从医保各目录(药品/耗材/体外诊断试剂/医疗机构制剂)去重汇总企业, 全量重写企业字典(约1.8万条)。将清空现有 std_supplier 后重建, 手工新增的条目会被覆盖。确认继续?',
          '从医保目录去重导入', { type: 'warning' })
          .then(function () {
            vm.importing = true;
            return HIS.get('/api/supplier-dict/import');
          }).then(function (d) {
            if (d && d.status === 'SUCCESS') { HIS.notifySuccess('导入成功: ' + d.rows + ' 家企业'); vm.search(); }
            else { ElementPlus.ElMessage.error('导入失败: ' + ((d && d.message) || '未知错误')); }
          }).catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } })
            .finally(function () { vm.importing = false; });
      },
      /* ---- 资质文件管理 ---- */
      loadFiles: function () {
        var vm = this;
        if (!vm.form.sup_code) { vm.fileList = []; return; }
        vm.fileLoading = true;
        HIS.get('/api/supplier-dict/files?supCode=' + encodeURIComponent(vm.form.sup_code)).then(function (d) {
          vm.fileList = d || [];
        }).catch(function () { vm.fileList = []; }).finally(function () { vm.fileLoading = false; });
      },
      onUploadSuccess: function (res) {
        if (res && res.code === 0) { HIS.notifySuccess('文件上传成功'); this.loadFiles(); }
        else { ElementPlus.ElMessage.error((res && res.message) || '上传失败'); }
      },
      onUploadError: function () { ElementPlus.ElMessage.error('文件上传失败'); },
      delFile: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除文件【' + row.file_name + '】?', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/supplier-dict/file/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.loadFiles(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      fmtSize: function (bytes) {
        if (!bytes) { return '-'; }
        if (bytes < 1024) { return bytes + ' B'; }
        if (bytes < 1048576) { return (bytes / 1024).toFixed(1) + ' KB'; }
        return (bytes / 1048576).toFixed(1) + ' MB';
      },
      isZip: function (name) {
        if (!name) { return false; }
        var lower = String(name).toLowerCase();
        return lower.endsWith('.zip') || lower.endsWith('.rar');
      },
      previewFile: function (row) {
        var url = '/uploads/' + row.file_path;
        if (this.isZip(row.file_name)) {
          var a = document.createElement('a'); a.href = url; a.download = row.file_name;
          document.body.appendChild(a); a.click(); document.body.removeChild(a);
        } else {
          window.open(url, '_blank');
        }
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">企业字典 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(全局共享 · 医保各目录企业去重汇总 · 供药品/耗材目录企业字段引用)</span></div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" :placeholder="ph" clearable style="width:280px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button type="success" @click="openCreate">新增企业</el-button>',
      '    <el-button type="warning" :loading="importing" @click="doImport">从医保目录去重导入</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;margin-left:auto;">共 {{ total }} 家企业</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="code" label="企业编码" width="130" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" label="企业名称" min-width="240" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="pyCode" label="拼音码" width="100" show-overflow-tooltip><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="spec" label="企业类型" width="150" show-overflow-tooltip><template #default="s">{{ s.row.spec || \u0027—\u0027 }}</template></el-table-column>',
      '    <el-table-column label="来源目录" min-width="160" show-overflow-tooltip><template #default="s">{{ catText(s.row.extra) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag :type="s.row.valiFlag===\'0\' ? \'danger\' : \'success\'" size="small">{{ s.row.valiFlag===\'0\' ? \'作废\' : \'有效\' }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',

      /* ===== 编辑弹窗: 三 Tab 页维护 ===== */
      '  <el-dialog v-model="dlg" :title="editing ? (\'编辑企业 #\' + editId) : \'新增企业\'" width="860px" top="4vh" :close-on-click-modal="false">',
      '    <el-tabs v-model="activeTab" type="border-card">',

      /* -- Tab 1: 基础信息 -- */
      '    <el-tab-pane label="基础信息" name="basic">',
      '      <el-form label-position="top" size="small">',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="企业编码 *"><el-input v-model="form.sup_code" placeholder="如 GYS0000001"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="企业简称"><el-input v-model="form.sup_short_name" placeholder="常用简称"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="企业类型"><el-select v-model="form.sup_type" filterable allow-create clearable style="width:100%" placeholder="选择或输入"><el-option v-for="t in typeOpts" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="24"><el-form-item label="企业名称 *"><el-input v-model="form.sup_name" placeholder="企业全称"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="统一社会信用代码"><el-input v-model="form.uscc" placeholder="18位"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="法定代表人"><el-input v-model="form.legal_person"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="注册资本(万元)"><el-input v-model="form.reg_capital"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="成立日期"><el-input v-model="form.estab_date" placeholder="YYYY-MM-DD"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="状态"><el-select v-model="form.vali_flag" style="width:100%"><el-option label="有效" value="1"></el-option><el-option label="作废" value="0"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="来源目录"><el-select v-model="form.src_catalog" multiple collapse-tags clearable style="width:100%" placeholder="可多选"><el-option v-for="c in catOpts" :key="c.v" :label="c.l" :value="c.v"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="24"><el-form-item label="注册地址"><el-input v-model="form.reg_address"></el-input></el-form-item></el-col>',
      '          <el-col :span="24"><el-form-item label="生产/经营范围"><el-input type="textarea" :rows="2" v-model="form.business_scope"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '    </el-tab-pane>',

      /* -- Tab 2: 资质证照 -- */
      '    <el-tab-pane label="资质证照" name="qual">',
      '      <el-form label-position="top" size="small">',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="生产/经营许可证号"><el-input v-model="form.license_no"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="许可证有效期"><el-input v-model="form.license_expiry" placeholder="YYYY-MM-DD"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="发证机关"><el-input v-model="form.license_authority"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="GMP/GSP 证书号"><el-input v-model="form.gmp_gsp_no"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="GMP/GSP 有效期"><el-input v-model="form.gmp_gsp_expiry" placeholder="YYYY-MM-DD"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '      <div class="sup-section-title" style="margin-top:20px;">资质文件档案</div>',
      '      <div style="display:flex;gap:10px;align-items:flex-end;margin-bottom:12px;flex-wrap:wrap;">',
      '        <el-select v-model="uploadDocType" size="small" style="width:120px;" placeholder="资料类型"><el-option v-for="d in docTypeOpts" :key="d" :label="d" :value="d"></el-option></el-select>',
      '        <el-input v-model="uploadRemark" size="small" style="width:180px;" placeholder="备注(可选)"></el-input>',
      '        <el-upload :action="uploadUrl" :headers="uploadHeaders" :data="uploadData" :show-file-list="false"',
      '          accept=".pdf,.jpg,.jpeg,.png,.zip,.rar" :on-success="onUploadSuccess" :on-error="onUploadError"',
      '          :disabled="!form.sup_code">',
      '          <el-button type="primary" size="small" :disabled="!form.sup_code">上传文件(PDF/图片/压缩包)</el-button>',
      '        </el-upload>',
      '      </div>',
      '      <el-table :data="fileList" v-loading="fileLoading" border size="small" max-height="220px" empty-text="暂无资质文件">',
      '        <el-table-column prop="doc_type" label="类型" width="90"></el-table-column>',
      '        <el-table-column prop="file_name" label="文件名" min-width="200" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="大小" width="80"><template #default="s">{{ fmtSize(s.row.file_size) }}</template></el-table-column>',
      '        <el-table-column prop="upload_by" label="上传人" width="90"></el-table-column>',
      '        <el-table-column prop="upload_time" label="上传时间" width="150" show-overflow-tooltip><template #default="s">{{ s.row.upload_time ? String(s.row.upload_time).substring(0,16) : \'-\' }}</template></el-table-column>',
      '        <el-table-column label="操作" width="120"><template #default="s">',
      '          <el-button link type="primary" size="small" @click="previewFile(s.row)">{{ isZip(s.row.file_name) ? "下载" : "预览" }}</el-button>',
      '          <el-button link type="danger" size="small" @click="delFile(s.row)">删除</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <div v-if="!editing" style="margin-top:8px;font-size:12px;color:var(--yb-ink-2);">提示: 需先保存企业基本信息后，方可上传资质文件。</div>',
      '    </el-tab-pane>',

      /* -- Tab 3: 联系 / 招采 -- */
      '    <el-tab-pane label="联系 / 招采" name="contact">',
      '      <el-form label-position="top" size="small">',
      '        <div class="sup-section-title">联系与开票</div>',
      '        <el-row :gutter="16">',
      '          <el-col :span="6"><el-form-item label="联系人"><el-input v-model="form.contact_person"></el-input></el-form-item></el-col>',
      '          <el-col :span="6"><el-form-item label="联系电话"><el-input v-model="form.contact_phone"></el-input></el-form-item></el-col>',
      '          <el-col :span="6"><el-form-item label="传真"><el-input v-model="form.fax"></el-input></el-form-item></el-col>',
      '          <el-col :span="6"><el-form-item label="电子邮箱"><el-input v-model="form.email"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="纳税人识别号"><el-input v-model="form.tax_no"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="开户银行"><el-input v-model="form.bank_name"></el-input></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="银行账号"><el-input v-model="form.bank_account"></el-input></el-form-item></el-col>',
      '          <el-col :span="24"><el-form-item label="发票抬头"><el-input v-model="form.invoice_title"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '        <div class="sup-section-title">招采与合规</div>',
      '        <el-row :gutter="16">',
      '          <el-col :span="8"><el-form-item label="是否两票制"><el-select v-model="form.two_ticket_flag" clearable style="width:100%" placeholder="未填"><el-option label="是" value="1"></el-option><el-option label="否" value="0"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="失信/黑名单"><el-select v-model="form.blacklist_flag" clearable style="width:100%" placeholder="未填"><el-option label="是" value="1"></el-option><el-option label="否" value="0"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="8"><el-form-item label="招采/挂网平台编码"><el-input v-model="form.platform_code"></el-input></el-form-item></el-col>',
      '          <el-col :span="24"><el-form-item label="配送区域"><el-input v-model="form.delivery_area" placeholder="如: 湖北省孝感市"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '    </el-tab-pane>',

      '    </el-tabs>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
