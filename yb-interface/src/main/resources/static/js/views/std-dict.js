/* 标准字典: 浏览/对照查询 + 提取入库(导入) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 24 类标准字典: v=后端type; st=标准类型; doc=来源文档; cols=列标签(统一 {code,name,spec,extra}) */
  var STD_TYPES = [
    { v: 'cv_code', l: '医保字典值域代码(第6章)', st: '医保字典', doc: '湖北省医保定点医药机构接口规范V1.2.02·第6章 字典表', cols: ['值代码', '值名称', '字典类型', '类型代码'] },
    { v: 'drug', l: '西药中成药(湖北医保)', st: '医保字典', doc: '湖北省医保药品(西药、中成药)编码数据库·2024-12-02', cols: ['药品代码', '注册名称', '实际规格', '生产企业'] },
    { v: 'consumable', l: '医用耗材(20位)', st: '医保字典', doc: '湖北省医用耗材(20位)编码数据库·2024-11-29', cols: ['耗材代码', '医保通用名', '三级分类', '耗材企业'] },
    { v: 'med_service', l: '医疗服务项目', st: '医保字典', doc: '湖北省医疗服务项目编码数据库·2024-11-29', cols: ['地方项目代码', '地方项目名称', '计价单位', '国家项目名称'] },
    { v: 'tcm', l: '中药饮片', st: '医保字典', doc: '湖北省医保药品(中药饮片)编码数据库·2024-11-28', cols: ['饮片代码', '饮片名称', '功效分类', '药材名称'] },
    { v: 'preparation', l: '医疗机构制剂', st: '医保字典', doc: '湖北省医保药品(医疗机构制剂)编码数据库·2024-11-25', cols: ['制剂代码', '制剂名称', '规格', '申请单位'] },
    { v: 'ivd', l: '体外诊断试剂', st: '医保字典', doc: '湖北省医保体外诊断试剂编码数据库·2024-11-26', cols: ['试剂分类代码', '产品名称', '包装规格', '检测指标'] },
    { v: 'cons_item_rel', l: '耗材-服务项目对应', st: '医保字典', doc: '湖北省医用耗材与医疗服务项目对应关系·2024-12-02', cols: ['耗材代码20位', '医用材料品种', '类别(项目)名称', '诊疗项目编码'] },
    { v: 'icd10', l: '医保ICD10疾病诊断', st: '医保字典', doc: '医保ICD10疾病诊断分类与代码 v2.0(湖北省医保编码)', cols: ['诊断代码', '诊断名称', '类目名称', '亚目名称'] },
    { v: 'icd9', l: '医保ICD9手术操作', st: '医保字典', doc: '医保ICD9手术操作分类与代码 v2.0(湖北省医保编码)', cols: ['手术操作代码', '手术操作名称', '类目名称', '亚目名称'] },
    { v: 'icd10_nat', l: '国家临床版疾病', st: '国家临床标准', doc: '疾病分类与代码国家临床版2.0(2022汇总版)', cols: ['主要编码', '疾病名称', '附加编码', '来源'] },
    { v: 'icd9_nat', l: '国家临床版手术操作', st: '国家临床标准', doc: '手术操作分类代码国家临床版(3.0/2.0)', cols: ['主要编码', '手术操作名称', '类别', '来源'] },
    { v: 'morphology', l: '肿瘤形态学M码', st: '国家临床标准', doc: '肿瘤形态学编码(国家临床版2.0/医保版)', cols: ['形态学编码', '形态学名称', '肿瘤/细胞类型', '来源'] },
    { v: 'tcm_disease', l: '中医疾病分类与代码', st: '中医标准', doc: '中医疾病分类与代码数据·20191201', cols: ['疾病分类代码', '疾病分类名称', '专科系统', '科别类目'] },
    { v: 'tcm_syndrome', l: '中医证候分类与代码', st: '中医标准', doc: '中医证候分类与代码数据·20191201', cols: ['证候分类代码', '证候分类名称', '证候属性', '证候类目'] },
    { v: 'tcm_mapping', l: '中医新老对照', st: '中医标准', doc: '中医疾病新老对照(修订版)', cols: ['修订版代码', '修订版名称', '原名称', '映射类型'], ph: '编码/名称/原名称/映射类型检索' },
    { v: 'tcm_disease_new', l: '中医疾病分类与代码(新版)', st: '中医标准', doc: '中医临床诊疗术语 第1部分：疾病(修订版·GB/T 15657-2021)', cols: ['疾病代码', '疾病名称', '编号', '可选用词'] },
    { v: 'tcm_syndrome_new', l: '中医证候分类与代码(新版)', st: '中医标准', doc: '中医临床诊疗术语 第2部分：证候(修订版·GB/T 15657-2021)', cols: ['证候代码', '证候名称', '编号', '可选用词'] },
    { v: 'wst364', l: 'WS/T 364 值域代码', st: '卫生健康标准', doc: 'WS/T 364—2023 卫生健康信息数据元值域代码', cols: ['值', '值含义', '所属代码表', 'CV标识'] },
    { v: 'hbvalue', l: '湖北采集规范值域代码', st: '卫生健康标准', doc: '湖北省健康医疗大数据采集规范--数据元值域代码20240826', cols: ['值', '值含义', '所属代码表', '代码表标识'] },
    { v: 'whvalue', l: '武汉平台值域代码', st: '卫生健康标准', doc: '武汉市全民健康信息平台数据元值域代码规范20240905_V1(20250326修订)', cols: ['值', '值含义', '所属代码表', '代码表标识'] },
{ v: 'msi_nat', l: '全国医疗服务项目技术规范(2023年版)', st: '物价标准', doc: '全国医疗服务项目技术规范(2023年版).xlsx 原样全列导入(不合并/不对照)', cols: ['项目编码', '项目名称', '计量单位', '收费票据分类'], ph: '编码/名称/英文名/分类/必需耗材/分类码检索' },
{ v: 'msi_cat', l: '医疗服务项目物价分类(2023技术规范)', st: '物价标准', doc: '全国医疗服务项目技术规范(2023年版)·项目分类(类/章/节三级, 原生字母码)', cols: ['分类码', '分类名称', '上级码', '项目数'], ph: '分类码/名称/上级码检索' },
{ v: 'msi_hb', l: '湖北省医疗服务价格项目及医保支付目录(2023版)', st: '物价标准', doc: '湖北省医疗服务价格项目及医保支付目录(2023版).xlsx 基础项+子项原样导入(不合并/不对照)', cols: ['项目编码', '项目名称', '计价单位', '医保支付类别'], ph: '编码/名称/分类/备注检索' },
{ v: 'msi_fin', l: '医疗服务项目相关财务归集口径规范', st: '物价标准', doc: '医疗服务项目相关财务归集口径规范.pdf 全表行原样导入(含2023/2012/2001码, 不做对照加工)', cols: ['2023版编码', '2023版名称', '收费票据分类', '会计科目分类'], ph: '2023/2012/2001编码或名称检索' },
{ v: 'mr_cost_class', l: '病案首页费用分类', st: '物价标准', doc: '医疗服务项目相关财务归集口径规范·病案首页费用分类', cols: ['分项编码', '费用分项名称', '所属大类', '原始完整值'], ph: '大类/分项/原始值检索' },
{ v: 'invoice_class', l: '收费票据分类', st: '物价标准', doc: '医疗服务项目相关财务归集口径规范·收费票据分类', cols: ['分类编码', '收费票据分类', '对应会计科目分类', '归集项目数'], ph: '票据分类/会计科目检索' },
{ v: 'acct_class', l: '会计科目分类', st: '物价标准', doc: '医疗服务项目相关财务归集口径规范·会计科目分类', cols: ['分类编码', '会计科目分类', '对应收费票据分类', '归集项目数'], ph: '会计科目/票据分类检索' }
  ];

  /* 按标准类型分组(供下拉 el-option-group), 固定顺序 */
  var STD_TYPE_ORDER = ['医保字典', '国家临床标准', '中医标准', '卫生健康标准', '物价标准'];

  function typeMeta(v) {
    for (var i = 0; i < STD_TYPES.length; i++) { if (STD_TYPES[i].v === v) { return STD_TYPES[i]; } }
    return { v: v, l: v, cols: ['编码', '名称', '规格/分类', '附加'] };
  }

  /* 检索框占位文案: 统一追加拼音简码提示(后端检索列已并入 py_code/源pinyin) */
  function kwPh(ph) {
    var base = ph || '编码/名称检索';
    if (base.indexOf('拼音') >= 0) { return base; }
    return base.replace(/检索$/, '') + '/拼音简码检索';
  }

  /* ================= 标准字典浏览/对照 ================= */
  HIS.views.StdDictBrowse = {
    data: function () {
      return {
        types: STD_TYPES, dictType: 'cv_code', keyword: '', page: 1, size: 20,
        loading: false, list: [], total: 0
      };
    },
    computed: {
      cols: function () { return typeMeta(this.dictType).cols; },
      curMeta: function () { var m = typeMeta(this.dictType); return { st: m.st || '', doc: m.doc || '' }; },
      ph: function () { return kwPh(typeMeta(this.dictType).ph); },
      groups: function () {
        var out = [];
        STD_TYPE_ORDER.forEach(function (st) {
          var items = STD_TYPES.filter(function (t) { return t.st === st; });
          if (items.length) { out.push({ st: st, items: items }); }
        });
        return out;
      }
    },
    created: function () { this.search(); },
    methods: {
      search: function () { this.page = 1; this.fetch(); },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/std-dict/query/' + vm.dictType + '?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      onTypeChange: function () { this.keyword = ''; this.search(); }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">标准字典浏览</div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;"',
      '    title="以湖北省医保编码数据库为主、纳入国家临床版与中医分类等权威标准字典(全局共享, 不分医院)。业务开单/对照时以此为准。"></el-alert>',
      '  <el-alert v-if="curMeta.doc" type="success" :closable="false" show-icon style="margin-bottom:14px;">',
      '    <template #title>',
      '      <span>标准类型：<el-tag size="small" type="info">{{ curMeta.st }}</el-tag></span>',
      '      <span style="margin-left:18px;">来源文档：<b>{{ curMeta.doc }}</b></span>',
      '    </template>',
      '  </el-alert>',
      '  <div class="toolbar">',
      '    <el-select v-model="dictType" style="width:240px" @change="onTypeChange"><el-option-group v-for="g in groups" :key="g.st" :label="g.st"><el-option v-for="t in g.items" :key="t.v" :label="t.l" :value="t.v"></el-option></el-option-group></el-select>',
      '    <el-input v-model="keyword" :placeholder="ph" clearable style="width:280px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="code" :label="cols[0]" width="220" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" :label="cols[1]" min-width="240" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="pyCode" label="拼音码" width="120" show-overflow-tooltip><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="spec" :label="cols[2]" min-width="140" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="extra" :label="cols[3]" min-width="160" show-overflow-tooltip></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* ================= 标准字典提取入库 ================= */
  HIS.views.StdDictImport = {
    data: function () {
      return { types: STD_TYPES, busy: false, busyLabel: '', results: [], versions: [], vloading: false };
    },
    created: function () { this.loadVersions(); },
    methods: {
      doImport: function (t) {
        var vm = this; vm.busy = true; vm.busyLabel = t.l;
        HIS.get('/api/std-dict/import/' + t.v).then(function (d) {
          vm.push(t.l, d);
          if (d && d.status === 'SUCCESS') { HIS.notifySuccess(t.l + ' 导入成功: ' + d.rows + ' 行'); }
          else { ElementPlus.ElMessage.error(t.l + ' 导入失败: ' + ((d && d.message) || '未知错误')); }
          vm.loadVersions();
        }).catch(HIS.notifyError).finally(function () { vm.busy = false; vm.busyLabel = ''; });
      },
      doImportAll: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('全量导入约 73 万行, 大表耗时较长(数分钟), 确认继续?', '导入全部标准字典', { type: 'warning' })
          .then(function () {
            vm.busy = true; vm.busyLabel = '全部';
            return HIS.get('/api/std-dict/import/all');
          }).then(function (d) {
            ((d && d.details) || []).forEach(function (it) {
              var m = null; for (var i = 0; i < STD_TYPES.length; i++) { if (STD_TYPES[i].v === it.dict) { m = STD_TYPES[i]; } }
              vm.push(m ? m.l : it.dict, it);
            });
            HIS.notifySuccess('全量导入完成');
            vm.loadVersions();
          }).catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } })
            .finally(function () { vm.busy = false; vm.busyLabel = ''; });
      },
      push: function (label, d) {
        var ok = d && d.status === 'SUCCESS';
        this.results.unshift({
          time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          label: label, ok: ok, rows: (d && d.rows) || 0,
          ms: (d && d.elapsedMs) || 0,
          msg: (d && d.message) || (ok ? '成功' : '失败')
        });
        if (this.results.length > 40) { this.results.pop(); }
      },
      loadVersions: function () {
        var vm = this; vm.vloading = true;
        HIS.get('/api/std-dict/versions').then(function (d) { vm.versions = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.vloading = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">标准字典提取入库</div>',
      '  <el-alert type="warning" :closable="false" show-icon style="margin-bottom:14px;"',
      '    title="从“字典标准”文件夹的 xlsx 提取标准字典写入 std_* 表。导入为幂等(先清空后全量写入), 可安全重复执行。大表(药品24万/耗材对应25万)耗时较长。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button v-for="t in types" :key="t.v" size="small" :loading="busy && busyLabel===t.l" @click="doImport(t)">{{ t.l }}</el-button>',
      '    <el-button type="warning" :loading="busy && busyLabel===\'全部\'" @click="doImportAll">导入全部</el-button>',
      '  </div>',
      '  <el-table :data="results" size="small" border stripe style="margin-top:8px;">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="time" label="时间" width="100"></el-table-column>',
      '    <el-table-column prop="label" label="字典" min-width="180"></el-table-column>',
      '    <el-table-column label="结果" width="90"><template #default="s"><el-tag size="small" :type="s.row.ok?\'success\':\'danger\'">{{ s.row.ok?"成功":"失败" }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="rows" label="行数" width="100"></el-table-column>',
      '    <el-table-column prop="ms" label="耗时(ms)" width="100"></el-table-column>',
      '    <el-table-column prop="msg" label="信息" min-width="200" show-overflow-tooltip></el-table-column>',
      '  </el-table>',
      '  <div v-if="!results.length" style="color:#909399;font-size:13px;margin:8px 0;">尚未执行导入。</div>',
      '  <el-divider content-position="left">导入登记(std_dict_version)</el-divider>',
      '  <div class="toolbar"><el-button size="small" @click="loadVersions">刷新</el-button></div>',
      '  <el-table :data="versions" v-loading="vloading" size="small" border stripe>',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="dictKey" label="标识" width="140"></el-table-column>',
      '    <el-table-column prop="dictName" label="字典名称" min-width="180"></el-table-column>',
      '    <el-table-column prop="ver" label="版本" width="110"></el-table-column>',
      '    <el-table-column prop="rowCount" label="行数" width="100"></el-table-column>',
      '    <el-table-column prop="sheet" label="来源sheet" width="100"></el-table-column>',
      '    <el-table-column label="状态" width="90"><template #default="s"><el-tag size="small" :type="s.row.status===\'SUCCESS\'?\'success\':\'danger\'">{{ s.row.status }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="importTime" label="导入时间" min-width="170"></el-table-column>',
      '  </el-table>',
      '</div>'
    ].join('\n')
  };

  /* ================= 标准字典维护(仅平台超级管理员: 逐值 CRUD) ================= */
  HIS.views.StdDictMaintain = {
    data: function () {
      return {
        dictType: 'cv_code', keyword: '', page: 1, size: 20,
        loading: false, list: [], total: 0,
        columns: [], dlg: false, editing: false, saving: false, editId: null, form: {}
      };
    },
    computed: {
      cols: function () { return typeMeta(this.dictType).cols; },
      curMeta: function () { var m = typeMeta(this.dictType); return { st: m.st || '', doc: m.doc || '' }; },
      ph: function () { return kwPh(typeMeta(this.dictType).ph); },
      editCols: function () {
        return (this.columns || []).filter(function (c) { return String(c.name).toLowerCase() !== 'id' && !c.auto; });
      },
      groups: function () {
        var out = [];
        STD_TYPE_ORDER.forEach(function (st) {
          var items = STD_TYPES.filter(function (t) { return t.st === st; });
          if (items.length) { out.push({ st: st, items: items }); }
        });
        return out;
      }
    },
    created: function () { this.reload(); },
    methods: {
      label: function (c) {
        var t = (c.comment && String(c.comment).trim()) ? String(c.comment).trim() : c.name;
        return t;
      },
      reload: function () { this.loadColumns(); this.search(); },
      loadColumns: function () {
        var vm = this;
        HIS.get('/api/std-dict/maintain/' + vm.dictType + '/columns')
          .then(function (d) { vm.columns = d || []; })
          .catch(HIS.notifyError);
      },
      search: function () { this.page = 1; this.fetch(); },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/std-dict/maintain/' + vm.dictType + '/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      onTypeChange: function () { this.keyword = ''; this.reload(); },
      openCreate: function () {
        var f = {};
        this.editCols.forEach(function (c) { f[c.name] = ''; });
        this.form = f; this.editing = false; this.editId = null; this.dlg = true;
      },
      openEdit: function (row) {
        var vm = this;
        HIS.get('/api/std-dict/maintain/' + vm.dictType + '/row/' + row.id)
          .then(function (d) {
            var f = {};
            vm.editCols.forEach(function (c) { f[c.name] = (d && d[c.name] != null) ? d[c.name] : ''; });
            vm.form = f; vm.editing = true; vm.editId = row.id; vm.dlg = true;
          }).catch(HIS.notifyError);
      },
      save: function () {
        var vm = this; vm.saving = true;
        var p = vm.editing
          ? HIS.put('/api/std-dict/maintain/' + vm.dictType + '/' + vm.editId, vm.form)
          : HIS.post('/api/std-dict/maintain/' + vm.dictType, vm.form);
        p.then(function () {
          HIS.notifySuccess(vm.editing ? '修改成功' : '新增成功');
          vm.dlg = false; vm.fetch();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除该字典值? 删除后不可恢复。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/std-dict/maintain/' + vm.dictType + '/' + row.id); })
          .then(function () { HIS.notifySuccess('已删除'); vm.fetch(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">标准字典维护</div>',
      '  <el-alert type="warning" :closable="false" show-icon style="margin-bottom:10px;"',
      '    title="仅平台超级管理员可维护。标准字典为全局共享的权威参照数据, 增删改将直接影响所有医院的业务对照, 请谨慎操作。"></el-alert>',
      '  <el-alert v-if="curMeta.doc" type="success" :closable="false" show-icon style="margin-bottom:14px;">',
      '    <template #title>',
      '      <span>标准类型：<el-tag size="small" type="info">{{ curMeta.st }}</el-tag></span>',
      '      <span style="margin-left:18px;">来源文档：<b>{{ curMeta.doc }}</b></span>',
      '    </template>',
      '  </el-alert>',
      '  <div class="toolbar">',
      '    <el-select v-model="dictType" style="width:240px" @change="onTypeChange"><el-option-group v-for="g in groups" :key="g.st" :label="g.st"><el-option v-for="t in g.items" :key="t.v" :label="t.l" :value="t.v"></el-option></el-option-group></el-select>',
      '    <el-input v-model="keyword" :placeholder="ph" clearable style="width:260px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button type="success" @click="openCreate">新增字典值</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="code" :label="cols[0]" width="200" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" :label="cols[1]" min-width="220" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="pyCode" label="拼音码" width="110" show-overflow-tooltip><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="spec" :label="cols[2]" min-width="130" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="extra" :label="cols[3]" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="editing ? (\'编辑字典值 #\' + editId) : \'新增字典值\'" width="820px" top="6vh">',
      '    <div style="max-height:62vh;overflow:auto;padding-right:6px;">',
      '      <el-form label-position="top" size="small">',
      '        <el-row :gutter="14">',
      '          <el-col :span="12" v-for="c in editCols" :key="c.name">',
      '            <el-form-item :label="label(c)">',
      '              <el-input v-model="form[c.name]" :placeholder="c.name + \' · \' + c.type" clearable></el-input>',
      '            </el-form-item>',
      '          </el-col>',
      '        </el-row>',
      '      </el-form>',
      '    </div>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
