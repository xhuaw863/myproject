/* 医共体统一字典(L2, 牵头机构维护): 收费项目/药品/耗材三目录 + 调价记录 + 标准字典导入 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 列表显示模式默认值: 委托全局 HIS.pagedDefault(见 api.js), 键保持 his.dict.<tab>Paged 不变 */
  function dictPagedDefault(tab) { return HIS.pagedDefault('dict.' + tab + 'Paged'); }

  var ITEM_TYPES = ['药品', '诊疗', '耗材', '其他'];
  /* 医保对照状态快筛(四目录通用): 空=全部 / 1=已对照 / 0=未对照 */
  var MAPPED_OPTS = [{ v: '1', l: '已对照' }, { v: '0', l: '未对照' }];
  var ROUND_RULES = [{ v: 1, l: '向上取整' }, { v: 2, l: '向下取整' }, { v: 3, l: '四舍五入' }];
  var PRICE_LV = [{ v: 1, l: '一级价格' }, { v: 2, l: '二级价格' }, { v: 3, l: '三级价格' }];
  /* 用药字典(用法/频次)导入源: 从医保/医疗标准值域拉取, value=type:code */
  var MED_IMPORT_SRCS = {
    usage: [
      { v: 'cv_code:drug_medc_way_code', l: '医保用药途径代码(cv_code)' },
      { v: 'hbvalue:CV06.00.102', l: '湖北用药途径代码(hbvalue)' },
      { v: 'wst364:CV06.00.102', l: '医疗标准编码·用药途径CV06.00.102(WS364)' }
    ],
    freq: [
      { v: 'cv_code:used_frqu', l: '医保药物使用频次(cv_code)' },
      { v: 'hbvalue:CV06.00.228', l: '湖北药物使用频次代码(hbvalue)' },
      { v: 'wst364:CV06.00.228', l: '医疗标准编码·使用频次CV06.00.228(WS364)' }
    ]
  };
  /* 诊断字典五类(his_diag_dict.dict_type): 统一字典维护页与导入源下拉共用 */
  var DIAG_TYPES = [
    { v: 'west', l: '西医诊断' },
    { v: 'tcm', l: '中医诊断' },
    { v: 'symp', l: '中医症候' },
    { v: 'oper', l: '手术代码' },
    { v: 'tumor', l: '肿瘤代码' }
  ];
  /* 值域字典分组(his_val_dict.dict_type = 标准源键:分组码): 预置医疗业务全部直连点(2026-09 字典分层收口),
     维护页可按组从基本字典整组导入; 业务下拉(HIS.stdValues)统一从本字典取数 */
  var VAL_TYPES = [
    { v: 'cv_code:gend', l: '性别代码' },
    { v: 'cv_code:insutype', l: '险种类型' },
    { v: 'cv_code:med_type', l: '医保医疗类别' },
    { v: 'cv_code:mdtrt_cert_type', l: '就诊凭证类型' },
    { v: 'cv_code:psn_cert_type', l: '人员证件类型' },
    { v: 'cv_code:naty', l: '民族代码' },
    { v: 'cv_code:caty', l: '科目类别(执业范围)' },
    { v: 'cv_code:oprn_lv_code', l: '手术级别代码' },
    { v: 'cv_code:reg_level', l: '号别代码' },
    { v: 'cv_code:dosform', l: '剂型代码' },
    { v: 'cv_code:chrgitm_lv', l: '医保收费项目等级(甲乙丙)' },
    { v: 'cv_code:drug_class', l: '药品管理类别' },
    { v: 'cv_code:storage_cond', l: '储存条件' },
    { v: 'cv_code:dose_unit', l: '剂量单位' },
    { v: 'cv_code:pack_unit', l: '包装单位' },
    { v: 'cv_code:fixmedins_type', l: '定点医疗机构类型' },
    { v: 'cv_code:hosp_lv', l: '医院等级' },
    { v: 'cv_code:MEDINS_TYPE', l: '机构类型(医保)' },
    { v: 'wst364:CV08.30.005', l: '专业技术职务等级(WS364)' },
    { v: 'hbvalue:HBCV08.50.029', l: '抗菌药物分级(湖北采集规范)' },
    { v: 'hbvalue:CV02.01.202', l: '湖北职业代码' },
    { v: 'hbvalue:GB/T 2261.2-2003', l: '婚姻状况(GB/T 2261.2)' },
    { v: 'hbvalue:GB/T 2659.1-2022', l: '国家与地区代码(GB/T 2659.1)' },
    { v: 'hbvalue:GB/T 4658-2006', l: '学历代码(GB/T 4658)' },
    { v: 'hbvalue:GB/T 4761-2008', l: '家庭关系代码(GB/T 4761)' },
    { v: 'whvalue:CT98.00.024', l: '执业类别(武汉平台)' }
  ];
  /* 预设分组友好标签映射: 动态下拉中命中预设项时用更可读的中文标签, 未命中(如卫健按域导入的归一化域名)则用库内 type_name */
  var VAL_LABEL = {};
  VAL_TYPES.forEach(function (t) { VAL_LABEL[t.v] = t.l; });
  /* 标准字典导入源: 类型 -> 可选字典(诊断类携 diagType=目标字典类别) */
  var IMPORT_DICTS = {
    drug: [{ key: 'drug', label: '湖北医保药品(西药/中成药)' }],
    cons: [{ key: 'consumable', label: '湖北医用耗材(20位)' }],
    charge: [
      { key: 'msi_hb', label: '湖北医疗服务价格项目(2023)' },
      { key: 'msi_nat', label: '全国医疗服务项目技术规范(2023)' },
      { key: 'med_service', label: '湖北医疗服务项目编码库' }
    ],
    diag: [
      { key: 'icd10', label: '医保ICD10疾病诊断(西医诊断)', diagType: 'west' },
      { key: 'icd10_nat', label: '国家临床版疾病分类与代码(西医诊断)', diagType: 'west' },
      { key: 'tcm_disease_new', label: '中医疾病分类与代码(新版)', diagType: 'tcm' },
      { key: 'tcm_disease', label: '中医疾病分类与代码(医保版)', diagType: 'tcm' },
      { key: 'tcm_syndrome_new', label: '中医证候分类与代码(新版)', diagType: 'symp' },
      { key: 'tcm_syndrome', label: '中医证候分类与代码(医保版)', diagType: 'symp' },
      { key: 'icd9', label: '医保ICD9手术操作(手术代码)', diagType: 'oper' },
      { key: 'icd9_nat', label: '国家临床版手术操作分类与代码(手术代码)', diagType: 'oper' },
      { key: 'morphology', label: '肿瘤形态学编码(肿瘤代码)', diagType: 'tumor' }
    ],
    val: [
      { key: 'wst364', label: 'WS/T 364 值域代码(国标·优先)' },
      { key: 'hbvalue', label: '湖北采集规范值域代码(省标·补充)' }
    ]
  };

  function clean(f) {
    delete f.createTime; delete f.updateTime; delete f.createBy; delete f.updateBy; delete f.deleted;
    return f;
  }

  HIS.views.CommunityDict = {
    data: function () {
      return {
        activeTab: 'charge',
        /* 字典下拉选项 */
        dosformOpts: [], chrgLvOpts: [], drugClassOpts: [], storageOpts: [], majorClassOpts: [],
        invClassOpts: [], acctClassOpts: [], mrCostOpts: [], msiCatMap: {}, msiCatOpts: [],
        /* 收费项目左目录树: 原始字典记录(拼树用) + 维度选项 */
        mrCostRaw: [], msiCatList: [],
        chgDims: [{ v: 'cat', l: '物价分类目录(类>章>节)' }, { v: 'inv', l: '收费票据分类' }, { v: 'acct', l: '会计科目分类' }, { v: 'mr', l: '病案首页归并' }],
        doseUnitOpts: [], packUnitOpts: [], abxOpts: [], negoFlagOpts: [],
        yesNoOpts: [{ code: '0', name: '否' }, { code: '1', name: '是' }],
        pregOpts: [{ code: 'A', name: 'A(安全)' }, { code: 'B', name: 'B' }, { code: 'C', name: 'C' }, { code: 'D', name: 'D' }, { code: 'X', name: 'X(禁忌)' }],
        /* 供货商(企业)字典远程下拉: 按目录企业槽位分别缓存候选, 避免多框互污 */
        supplierOpts: { drugMfr: [], drugHold: [], consMfr: [] }, supplierLoading: false,
        itemTypes: ITEM_TYPES, roundRules: ROUND_RULES, priceLv: PRICE_LV, mappedOpts: MAPPED_OPTS,
        /* 收费项目 */
        chg: { loading: false, paged: dictPagedDefault('charge'), list: [], total: 0, page: 1, size: 20, keyword: '', itemType: '', mapped: '',
          dim: 'cat', treeKw: '', sel: null, folded: {}, counts: null },
        chgDlg: false, chgEditing: false, chgImport: false, chgForm: this.emptyCharge(), chgYb: {},
        /* 药品 */
        drug: { loading: false, paged: dictPagedDefault('drug'), list: [], total: 0, page: 1, size: 20, keyword: '', mapped: '' },
        drugDlg: false, drugEditing: false, drugImport: false, drugForm: this.emptyDrug(), drugYb: {},
        /* 耗材 */
        cons: { loading: false, paged: dictPagedDefault('cons'), list: [], total: 0, page: 1, size: 20, keyword: '', mapped: '' },
        consDlg: false, consEditing: false, consImport: false, consForm: this.emptyCons(), consYb: {},
        /* 诊断字典(西医/中医/症候/手术/肿瘤) */
        diagTypes: DIAG_TYPES,
        diag: { loading: false, paged: dictPagedDefault('diag'), list: [], total: 0, page: 1, size: 20, keyword: '', dictType: 'west', mapped: '' },
        diagDlg: false, diagEditing: false, diagImport: false, diagForm: this.emptyDiag(),
        /* 值域字典(业务自由值域统一取数源) */
        valTypes: VAL_TYPES,
        val: { loading: false, paged: dictPagedDefault('val'), list: [], total: 0, page: 1, size: 50, keyword: '', dictType: 'cv_code:gend' },
        valDlg: false, valEditing: false, valForm: this.emptyVal('cv_code:gend'),
        /* 调价记录 */
        adj: { loading: false, paged: dictPagedDefault('adjust'), list: [], total: 0, page: 1, size: 20, catalogType: '' },
        /* 字段级修改记录(价格/医保码之外的全部字段变更) */
        elog: { loading: false, paged: dictPagedDefault('elog'), list: [], total: 0, page: 1, size: 20, catalogType: '', keyword: '', range: [] },
        adjDlg: false, adjForm: this.emptyAdjust(),
        /* 标准字典导入 */
        impType: 'drug', impDictKey: 'drug', impDicts: IMPORT_DICTS.drug,
        std: { loading: false, paged: dictPagedDefault('import'), list: [], total: 0, page: 1, size: 20, keyword: '' },
        stdBatchLoading: false,
        stdDetailDlg: false, stdDetailTitle: '', stdDetailRows: [], stdDetailLoading: false,
        valDomains: { loading: false, list: [] }, valSel: [],
        /* 用药字典(用法/用药频次) */
        med: {
          usage: { loading: false, paged: dictPagedDefault('usage'), list: [], total: 0, page: 1, size: 20, keyword: '' },
          freq: { loading: false, paged: dictPagedDefault('freq'), list: [], total: 0, page: 1, size: 20, keyword: '' }
        },
        medDlg: false, medEditing: false, medForm: this.emptyMed('usage'),
        medImpDlg: false, medImpType: 'usage', medImpSrcs: MED_IMPORT_SRCS.usage,
        medImpSrc: 'cv_code:drug_medc_way_code', medImpVals: [], medImpSel: [], medImpLoading: false
      };
    },
    created: function () {
      this.loadDicts();
      this.loadValTypes();
      this.loadChargeCounts();
      this.syncPaged('charge');
      this.loadCharge();
    },
    computed: {
      /* 当前维度的分类树(含子树级联值与滚动计数): roots + byKey */
      chgTreeData: function () {
        var vm = this; var dim = vm.chg.dim;
        var cntKey = { cat: 'cat', inv: 'invoice', acct: 'acct', mr: 'mr' }[dim] || 'cat';
        var cnt = ((vm.chg.counts || {})[cntKey]) || {};
        var byKey = {}; var all = [];
        function mk(key, label, parent, ownVals) {
          var n = { key: key, label: label, parent: parent || '', _own: ownVals || [], values: [], kids: [], cnt: 0 };
          byKey[key] = n; all.push(n); return n;
        }
        if (dim === 'cat') {
          /* 物价分类: std_msi_cat 沿 parent_code 拼三层级; 节点值=纯分类码(落库口径 cat_code) */
          (vm.msiCatList || []).forEach(function (o) {
            var p = o.p && byKey['cat|' + o.p] ? 'cat|' + o.p : '';
            mk('cat|' + o.code, o.name, p, [o.code]);
          });
        } else if (dim === 'mr') {
          /* 病案首页: 大类(spec) > 分项(raw_value); 分项节点值=raw_value(落库口径), 大类自身无值 */
          (vm.mrCostRaw || []).forEach(function (o) {
            var cn = o.spec || '其他';
            var ck = 'mr|@' + cn;
            if (!byKey[ck]) { mk(ck, cn, '', []); }
            var v = o.extra || o.name; var k = 'mr|' + v;
            if (byKey[k]) { return; }
            mk(k, o.name, ck, [v]);
          });
        } else {
          var opts = dim === 'inv' ? vm.invClassOpts : vm.acctClassOpts;
          (opts || []).forEach(function (o) { if (!byKey[dim + '|' + o.v]) { mk(dim + '|' + o.v, o.l || o.v, '', [o.v]); } });
        }
        /* 未分类节点(该列无值的项目) */
        var emptyCnt = cnt[''] || 0;
        if (emptyCnt > 0) { mk(dim + '|__EMPTY__', '未分类', '', ['__EMPTY__']); }
        /* 挂接子节点 + 计算缩进层级 */
        var roots = [];
        all.forEach(function (n) {
          if (n.parent && byKey[n.parent]) { byKey[n.parent].kids.push(n); }
        });
        function depth(n, guard) {
          if (!n.parent || !byKey[n.parent] || guard > 8) { return 0; }
          return 1 + depth(byKey[n.parent], guard + 1);
        }
        all.forEach(function (n) { n.pad = depth(n, 0); if (!n.parent) { roots.push(n); } });
        /* 级联值: 自身落库值 + 全部后代值(父节点点击含子树); 计数同构滚动汇总(带 seen 守卫防脏数据成环) */
        function collect(n, seen) {
          if (seen[n.key]) { return { vals: [], sum: 0 }; }
          seen[n.key] = true;
          var vals = n._own.slice(); var s = 0;
          n._own.forEach(function (v) { s += (v === '__EMPTY__' ? (cnt[''] || 0) : (cnt[v] || 0)); });
          n.kids.forEach(function (k) {
            var r = collect(k, seen); vals = vals.concat(r.vals); s += r.sum;
          });
          n.values = vals; n.cnt = s;
          return { vals: vals, sum: s };
        }
        roots.forEach(function (r) { collect(r, {}); });
        return { roots: roots, byKey: byKey };
      },
      /* 展平渲染行: 折叠态跳过子树; 搜索态强制展平且仅保留命中节点及其父链 */
      chgTreeRows: function () {
        var vm = this; var d = vm.chgTreeData;
        var kw = String(vm.chg.treeKw || '').trim().toLowerCase();
        var out = [];
        function hit(n) {
          if (kw && String(n.label).toLowerCase().indexOf(kw) >= 0) { return true; }
          for (var i = 0; i < n.kids.length; i++) { if (hit(n.kids[i])) { return true; } }
          return !kw;
        }
        function walk(list) {
          list.forEach(function (n) {
            if (kw && !hit(n)) { return; }
            out.push(n);
            if (!kw && vm.chg.folded[n.key]) { return; }
            walk(n.kids);
          });
        }
        walk(d.roots);
        return out;
      },
      chgAllCnt: function () {
        var c = ((this.chg.counts || {})[{ cat: 'cat', inv: 'invoice', acct: 'acct', mr: 'mr' }[this.chg.dim]] || {});
        var s = 0; for (var k in c) { s += c[k] || 0; } return s;
      },
      chgSelKey: function () { return this.chg.sel ? this.chg.sel.key : ''; },
      chgSelDimLabel: function () {
        var d = (this.chgDims || []).filter((function (vm) { return function (x) { return x.v === vm.chg.dim; }; }(this)))[0];
        return d ? d.l : '分类';
      }
    },
    methods: {
      loadDicts: function () {
        var vm = this;
        HIS.valByType('药品剂型').then(function (l) { vm.dosformOpts = l || []; }).catch(function () {});
        HIS.valByType('药品大类').then(function (l) { vm.majorClassOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'chrgitm_lv').then(function (l) { vm.chrgLvOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'drug_class').then(function (l) { vm.drugClassOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'storage_cond').then(function (l) { vm.storageOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'dose_unit').then(function (l) { vm.doseUnitOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'pack_unit').then(function (l) { vm.packUnitOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'hi_nego_drug_flag').then(function (l) { vm.negoFlagOpts = l || []; }).catch(function () {});
        HIS.stdValues('hbvalue', 'HBCV08.50.029').then(function (l) { vm.abxOpts = l || []; }).catch(function () {});
        /* 费用分类三字典(物价标准·财务归集口径): 票据分类/会计科目/病案首页归并 */
        HIS.get('/api/std-dict/query/invoice_class?page=1&size=100').then(function (d) {
          vm.invClassOpts = ((d && d.records) || []).map(function (o) { return { v: o.name, l: o.name, acct: o.spec }; });
        }).catch(function () {});
        HIS.get('/api/std-dict/query/acct_class?page=1&size=100').then(function (d) {
          vm.acctClassOpts = ((d && d.records) || []).map(function (o) { return { v: o.name, l: o.name, inv: o.spec }; });
        }).catch(function () {});
        HIS.get('/api/std-dict/query/mr_cost_class?page=1&size=100').then(function (d) {
          vm.mrCostRaw = (d && d.records) || [];
          vm.mrCostOpts = ((d && d.records) || []).map(function (o) {
            return { v: o.extra || o.name, l: o.extra || (o.spec + '：' + o.name) };
          });
        }).catch(function () {});
        /* 物价分类字典(msi_cat): code→{n:名称, p:上级码}, 供 cat_code 翻转显示与下拉选项 */
        HIS.get('/api/std-dict/query/msi_cat?page=1&size=600').then(function (d) {
          var m = {}; var opts = [];
          ((d && d.records) || []).forEach(function (o) {
            m[o.code] = { n: o.name, p: o.spec };
            /* 记录按层级序返回, 父节点已入 map, 可直接拼全路径 */
            var node = m[o.code]; var parts = [node.n]; var cur = node; var guard = 0;
            while (cur.p && m[cur.p] && guard++ < 5) { cur = m[cur.p]; parts.unshift(cur.n); }
            opts.push({ v: o.code, l: parts.join(' > ') });
          });
          vm.msiCatMap = m; vm.msiCatOpts = opts;
          vm.msiCatList = ((d && d.records) || []).map(function (o) { return { code: o.code, name: o.name, p: o.spec }; });
        }).catch(function () {});
      },
      onTab: function (name) {
        this.syncPaged(name);
        if (name === 'charge') { this.loadCharge(); }
        else if (name === 'drug') { this.loadDrug(); }
        else if (name === 'cons') { this.loadCons(); }
        else if (name === 'diag') { this.loadDiag(); }
        else if (name === 'val') { this.loadVal(); }
        else if (name === 'adjust') { this.loadAdjust(); }
        else if (name === 'elog') { this.loadElog(); }
        else if (name === 'import') { this.loadStd(); }
        else if (name === 'usage') { this.loadMed('usage'); }
        else if (name === 'freq') { this.loadMed('freq'); }
      },
      seq: function (state) { return function (i) { return (state.page - 1) * state.size + i + 1; }; },
      /* ==== 分页/全量双模式(统一字典各列表, 对齐列表统一规范): 全量仅把请求 size 置大, 不改各 load 函数 ==== */
      /* tab 名 → 该列表状态对象(usage/freq 挂在 med 下) */
      dictState: function (tab) {
        var m = { charge: 'chg', drug: 'drug', cons: 'cons', diag: 'diag', val: 'val', adjust: 'adj', elog: 'elog', import: 'std' };
        if (m[tab]) { return this[m[tab]]; }
        if (tab === 'usage' || tab === 'freq') { return this.med[tab]; }
        return null;
      },
      /* 依 paged 同步请求 size: 分页用每页行数(首次记为 _base), 全量用超大 size 一次取回 */
      syncPaged: function (tab) {
        var st = this.dictState(tab); if (!st) { return; }
        if (st._base === undefined) { st._base = st.size; }
        st.size = st.paged ? st._base : 100000;
      },
      /* 分页/全量切换(本地持久化): 切全量且总数超阈值时软提示确认, 取消则回分页 */
      onDictPaged: function (tab) {
        var vm = this; var st = this.dictState(tab); if (!st) { return; }
        var threshold = (HIS.params && HIS.params.listFullThreshold) || 2000;
        function persist() { try { localStorage.setItem('his.dict.' + tab + 'Paged', st.paged ? '1' : '0'); } catch (e) { } }
        function apply() { persist(); st.page = 1; vm.syncPaged(tab); vm.onTab(tab); }
        if (!st.paged && st.total > threshold) {
          ElementPlus.ElMessageBox.confirm(
            '当前范围共 ' + st.total + ' 条，全量显示可能卡顿数秒，是否继续？',
            '提示', { confirmButtonText: '继续全量', cancelButtonText: '保持分页', type: 'warning' }
          ).then(apply).catch(function () { st.paged = true; persist(); });
        } else { apply(); }
      },

      /* ============ 字段级修改记录 ============ */
      elCatText: function (c) { return { charge: '收费项目', drug: '药品', cons: '耗材' }[c] || c; },
      loadElog: function () {
        var vm = this; vm.elog.loading = true;
        var q = '/api/community-dict/edit-log/page?page=' + vm.elog.page + '&size=' + vm.elog.size;
        if (vm.elog.catalogType) { q += '&catalog=' + vm.elog.catalogType; }
        if (vm.elog.keyword) { q += '&keyword=' + encodeURIComponent(vm.elog.keyword); }
        if (vm.elog.range && vm.elog.range.length === 2) { q += '&start=' + vm.elog.range[0] + '&end=' + vm.elog.range[1]; }
        HIS.get(q).then(function (d) { vm.elog.list = (d && d.records) || []; vm.elog.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.elog.loading = false; });
      },
      elogSearch: function () { this.elog.page = 1; this.loadElog(); },
      elogPage: function (p) { this.elog.page = p; this.loadElog(); },
      elogSize: function (s) { this.elog.size = s; this.elog.page = 1; this.loadElog(); },

      /* ============ 收费项目 ============ */
      /* 编辑改医保码二次确认: 原码非空且被改动/清除时显式确认(后端同步做有效性校验与对照留痕) */
      confirmYbChange: function (orig, neu, label, onOk) {
        orig = (orig || '').trim(); neu = (neu || '').trim();
        if (!orig || orig === neu) { onOk(); return; }
        ElementPlus.ElMessageBox.confirm(
          '原' + label + '为 ' + orig + (neu ? ', 改为 ' + neu + '？' : ', 确认清除？') + '变更将记入医保对照留痕',
          '确认变更医保码', { type: 'warning', confirmButtonText: '确认变更', cancelButtonText: '取消' }
        ).then(onOk).catch(function () {});
      },
      /* 医保码→标准字典回查医保名称+甲乙分类(编码), 存入 xxxYb 供弹窗显示与不一致提示 */
      loadYbInfo: function (catalog, code, key) {
        var vm = this; code = (code || '').trim();
        if (!code) { vm[key] = {}; return; }
        HIS.get('/api/community-dict/yb-info?catalog=' + catalog + '&code=' + encodeURIComponent(code))
          .then(function (d) { vm[key] = d || {}; }).catch(function () { vm[key] = {}; });
      },
      /* 甲乙丙类编码(1/2/3/4) -> 名称, 取 cv_code:chrgitm_lv 下拉选项 */
      ybLvLabel: function (code) {
        if (code === null || code === undefined || code === '') return '';
        var o = (this.chrgLvOpts || []).filter(function (x) { return String(x.code) === String(code); })[0];
        return o ? o.name : String(code);
      },
      /* 保存前: 医保甲乙分类与表单甲乙丙类不一致时提示(仅当保存会保留手填值即 willSync=false 时才弹), 可继续保存 */
      confirmLvMismatch: function (ybInfo, formLv, willSync, onOk) {
        var vm = this;
        var ybLv = (ybInfo && ybInfo.chrgitmLv) ? String(ybInfo.chrgitmLv) : '';
        var cur = (formLv || '').trim();
        if (willSync || !ybLv || !cur || ybLv === cur) { onOk(); return; }
        ElementPlus.ElMessageBox.confirm(
          '医保目录甲乙分类为「' + vm.ybLvLabel(ybLv) + '」，与当前填写「' + vm.ybLvLabel(cur) + '」不一致。确认仍按当前填写保存？',
          '甲乙分类不一致提醒', { type: 'warning', confirmButtonText: '仍按当前保存', cancelButtonText: '返回修改' }
        ).then(onOk).catch(function () {});
      },
      emptyCharge: function () {
        return {
          id: null, itemCode: '', itemName: '', itemType: '诊疗', itemCat: '', spec: '', unit: '',
          price: null, priceL1: null, priceL2: null, priceL3: null, natItemCode: '', locItemCode: '',
          itemContent: '', itemExcluded: '', invoiceClass: '', acctClass: '', mrCostClass: '', catCode: '', deptCaty: '',
          medListCodg: '', medChrgitmType: '', chrgitmLv: '', selfpayProp: null,
          status: 1, effDate: '', endDate: '', memo: ''
        };
      },
      loadCharge: function () {
        var vm = this; vm.chg.loading = true;
        var q = '/api/community-dict/charge/page?page=' + vm.chg.page + '&size=' + vm.chg.size;
        if (vm.chg.keyword) { q += '&keyword=' + encodeURIComponent(vm.chg.keyword); }
        if (vm.chg.itemType) { q += '&itemType=' + encodeURIComponent(vm.chg.itemType); }
        if (vm.chg.mapped) { q += '&mapped=' + encodeURIComponent(vm.chg.mapped); }
        /* 左目录树分类过滤: 节点 values 为子树级联集(父节点含全部后代), 竖线分隔 */
        if (vm.chg.sel && vm.chg.sel.values && vm.chg.sel.values.length) {
          var p = { cat: 'catCodes', inv: 'invoiceClass', acct: 'acctClass', mr: 'mrCostClass' }[vm.chg.sel.dim];
          if (p) { q += '&' + p + '=' + encodeURIComponent(vm.chg.sel.values.join('|')); }
        }
        HIS.get(q).then(function (d) { vm.chg.list = (d && d.records) || []; vm.chg.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.chg.loading = false; });
      },
      /* 左目录树: 分类计数(节点角标); 全局 Long→String 序列化故逐值 Number 化后再参与算术 */
      loadChargeCounts: function () {
        var vm = this;
        HIS.get('/api/community-dict/charge/class-counts').then(function (d) {
          var out = {};
          Object.keys(d || {}).forEach(function (k) {
            var m = {};
            Object.keys(d[k] || {}).forEach(function (c) { m[c] = Number(d[k][c]) || 0; });
            out[k] = m;
          });
          vm.chg.counts = out;
        }).catch(function () {});
      },
      chgDimChange: function () { this.chg.sel = null; this.chg.treeKw = ''; this.chg.page = 1; this.loadCharge(); },
      chgNodeClick: function (n) {
        var vm = this;
        if (vm.chg.sel && vm.chg.sel.key === n.key) { vm.chgClearSel(); return; }
        vm.chg.sel = { key: n.key, label: n.label, dim: vm.chg.dim, values: n.values };
        vm.chg.page = 1; vm.loadCharge();
      },
      chgClearSel: function () { this.chg.sel = null; this.chg.page = 1; this.loadCharge(); },
      chgToggleFold: function (n) { this.chg.folded[n.key] = !this.chg.folded[n.key]; },
      /* 树节点关键字高亮(先转义后包 mark, 与职工管理左树同口径) */
      chgHl: function (s) {
        var kw = String(this.chg.treeKw || '').trim();
        var esc = String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; });
        if (!kw) { return esc; }
        var i = esc.toLowerCase().indexOf(kw.toLowerCase());
        if (i < 0) { return esc; }
        return esc.slice(0, i) + '<mark class="kw-hit">' + esc.slice(i, i + kw.length) + '</mark>' + esc.slice(i + kw.length);
      },
      chgSearch: function () { this.chg.page = 1; this.loadCharge(); },
      chgPage: function (p) { this.chg.page = p; this.loadCharge(); },
      chgSize: function (s) { this.chg.size = s; this.chg.page = 1; this.loadCharge(); },
      /* 票据分类↔会计科目 1:1 联动补全(财务归集口径), 仅在对方为空时填 */
      onInvClassChange: function (v) {
        var f = this.chgForm; if (f.acctClass) { return; }
        for (var i = 0; i < this.invClassOpts.length; i++) {
          if (this.invClassOpts[i].v === v && this.invClassOpts[i].acct) { f.acctClass = this.invClassOpts[i].acct; return; }
        }
      },
      onAcctClassChange: function (v) {
        var f = this.chgForm; if (f.invoiceClass) { return; }
        for (var i = 0; i < this.acctClassOpts.length; i++) {
          if (this.acctClassOpts[i].v === v && this.acctClassOpts[i].inv) { f.invoiceClass = this.acctClassOpts[i].inv; return; }
        }
      },
      /* 物价分类码→名称翻转显示(std_msi_cat, 沿 parent_code 拼全路径 类>章>节) */
      catPath: function (code) {
        if (!code) { return ''; }
        var m = this.msiCatMap; var node = m[code];
        if (!node) { return code; }
        var parts = [node.n]; var cur = node; var guard = 0;
        while (cur.p && m[cur.p] && guard++ < 5) { cur = m[cur.p]; parts.unshift(cur.n); }
        return parts.join(' > ');
      },
      chgAdd: function () { this.chgEditing = false; this.chgImport = false; this.chgOrigYb = ''; this.chgForm = this.emptyCharge(); this.chgYb = {}; this.chgDlg = true; },
      chgEdit: function (row) { this.chgEditing = true; this.chgImport = false; this.chgOrigYb = row.medListCodg || ''; this.chgForm = clean(Object.assign(this.emptyCharge(), row)); this.loadYbInfo('charge', row.medListCodg, 'chgYb'); this.chgDlg = true; },
      chgSubmit: function () {
        var vm = this; var f = vm.chgForm;
        if (!f.itemCode || !f.itemName) { ElementPlus.ElMessage.warning('项目编码与名称必填'); return; }
        if (vm.chgImport) { HIS.post('/api/community-dict/charge/import', f).then(function () { HIS.notifySuccess('保存成功'); vm.chgDlg = false; vm.loadCharge(); }).catch(HIS.notifyError); return; }
        vm.confirmYbChange(vm.chgEditing ? vm.chgOrigYb : '', f.medListCodg, '医保目录编码', function () {
          // 新增或改医保码时后端强制把甲乙丙类同步为医保分类(手填值会被覆盖) → 仅编辑未改码时才提示不一致
          var willSync = !vm.chgEditing || ((vm.chgOrigYb || '') !== (f.medListCodg || ''));
          vm.confirmLvMismatch(vm.chgYb, f.chrgitmLv, willSync, function () {
            var p = vm.chgEditing ? HIS.put('/api/community-dict/charge', f) : HIS.post('/api/community-dict/charge', f);
            p.then(function () { HIS.notifySuccess('保存成功'); vm.chgDlg = false; vm.loadCharge(); vm.loadChargeCounts(); }).catch(HIS.notifyError);
          });
        });
      },
      chgDel: function (row) { var vm = this; HIS.del('/api/community-dict/charge/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadCharge(); vm.loadChargeCounts(); }).catch(HIS.notifyError); },
      chgAdjust: function (row) {
        this.adjForm = this.emptyAdjust();
        this.adjForm.catalogType = 'charge'; this.adjForm.catalogId = row.id; this.adjForm.catalogName = row.itemName;
        this.adjForm.priceField = 'price_l1'; this.adjForm.orgLevel = 1;
        this.adjDlg = true;
      },

      /* ============ 药品 ============ */
      emptyDrug: function () {
        return {
          id: null, drugCode: '', ybDrugCode: '', drugStdCode: '', approvalNo: '',
          genericName: '', tradeName: '', majorClass: '', dosform: '', spec: '', manufacturer: '', manufacturerCode: '', mktHolder: '', mktHolderCode: '',
          chrgitmLv: '', selfpayProp: null, payStdPrep: '', negoFlag: '', msdFlag: '', ltdSelfFlag: '', limitScope: '',
          doseUnit: '', unitDose: null, minUnit: '', packUnit: '', packRatio: null, roundRule: 1,
          purchasePrice: null, retailPrice: null, zeroMargin: 1,
          drugClass: '', abxGrade: '', otcFlag: 0, essentialFlag: 0, pregClass: '', skinTestFlag: 0,
          storageCond: '', maxQtyOnce: null, status: 1, effDate: '', endDate: '', memo: '',
          /* P3 追溯码发药闭环: 三码校验字段 + 药品级追溯强制开关 */
          commodityCode: '', supervisionCode: '', traceFlag: 0
        };
      },
      loadDrug: function () {
        var vm = this; vm.drug.loading = true;
        var q = '/api/community-dict/drug/page?page=' + vm.drug.page + '&size=' + vm.drug.size;
        if (vm.drug.keyword) { q += '&keyword=' + encodeURIComponent(vm.drug.keyword); }
        if (vm.drug.mapped) { q += '&mapped=' + encodeURIComponent(vm.drug.mapped); }
        HIS.get(q).then(function (d) { vm.drug.list = (d && d.records) || []; vm.drug.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.drug.loading = false; });
      },
      drugSearch: function () { this.drug.page = 1; this.loadDrug(); },
      drugPage: function (p) { this.drug.page = p; this.loadDrug(); },
      drugSize: function (s) { this.drug.size = s; this.drug.page = 1; this.loadDrug(); },
      /* ==== 供货商(企业)字典远程下拉: 名称/编码/拼音检索 + 选中回填名称 ==== */
      supSearch: function (slot, q) {
        var vm = this;
        if (!q || !String(q).trim()) { vm.supplierOpts[slot] = []; return; }
        vm.supplierLoading = true;
        HIS.get('/api/std-dict/query/supplier?page=1&size=30&keyword=' + encodeURIComponent(String(q).trim()))
          .then(function (d) {
            vm.supplierOpts[slot] = ((d && d.records) || []).map(function (o) { return { code: o.code, name: o.name, type: o.spec }; });
          }).catch(function () {}).finally(function () { vm.supplierLoading = false; });
      },
      supChange: function (form, codeField, nameField, slot) {
        var code = form[codeField];
        var hit = (this.supplierOpts[slot] || []).filter(function (o) { return o.code === code; })[0];
        form[nameField] = hit ? hit.name : '';
      },
      supSeed: function (form, codeField, nameField, slot) {
        this.supplierOpts[slot] = (form[codeField]) ? [{ code: form[codeField], name: form[nameField] || form[codeField] }] : [];
      },
      drugAdd: function () { this.drugEditing = false; this.drugImport = false; this.drugOrigYb = ''; this.drugForm = this.emptyDrug(); this.drugYb = {}; this.supplierOpts.drugMfr = []; this.supplierOpts.drugHold = []; this.drugDlg = true; },
      drugEdit: function (row) {
        this.drugEditing = true; this.drugImport = false; this.drugOrigYb = row.ybDrugCode || '';
        this.drugForm = clean(Object.assign(this.emptyDrug(), row)); this.loadYbInfo('drug', row.ybDrugCode, 'drugYb');
        this.supSeed(this.drugForm, 'manufacturerCode', 'manufacturer', 'drugMfr');
        this.supSeed(this.drugForm, 'mktHolderCode', 'mktHolder', 'drugHold');
        this.drugDlg = true;
      },
      drugSubmit: function () {
        var vm = this; var f = vm.drugForm;
        if (!f.genericName) { ElementPlus.ElMessage.warning('通用名必填'); return; }
        if (!f.drugCode && !f.ybDrugCode) { ElementPlus.ElMessage.warning('院内码或医保码至少填一项'); return; }
        if (vm.drugImport) { HIS.post('/api/community-dict/drug/import', f).then(function () { HIS.notifySuccess('保存成功'); vm.drugDlg = false; vm.loadDrug(); }).catch(HIS.notifyError); return; }
        vm.confirmYbChange(vm.drugEditing ? vm.drugOrigYb : '', f.ybDrugCode, '医保药品码', function () {
          // 新增或改医保码时后端强制把甲乙丙类同步为医保分类(手填值会被覆盖) → 仅编辑未改码时才提示不一致
          var willSync = !vm.drugEditing || ((vm.drugOrigYb || '') !== (f.ybDrugCode || ''));
          vm.confirmLvMismatch(vm.drugYb, f.chrgitmLv, willSync, function () {
            var p = vm.drugEditing ? HIS.put('/api/community-dict/drug', f) : HIS.post('/api/community-dict/drug', f);
            p.then(function () { HIS.notifySuccess('保存成功'); vm.drugDlg = false; vm.loadDrug(); }).catch(HIS.notifyError);
          });
        });
      },
      drugDel: function (row) { var vm = this; HIS.del('/api/community-dict/drug/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadDrug(); }).catch(HIS.notifyError); },
      drugAdjust: function (row, field) {
        this.adjForm = this.emptyAdjust();
        this.adjForm.catalogType = 'drug'; this.adjForm.catalogId = row.id; this.adjForm.catalogName = row.genericName;
        this.adjForm.priceField = field; this.adjForm.orgLevel = null;
        this.adjDlg = true;
      },

      /* ============ 耗材 ============ */
      emptyCons: function () {
        return {
          id: null, consCode: '', ybConsCode: '', regCertNo: '', name: '', cat1: '', cat2: '', cat3: '',
          specModel: '', material: '', feature: '', manufacturer: '', manufacturerCode: '',
          minUnit: '', packUnit: '', packRatio: null,
          purchasePrice: null, chargePrice: null, chargeFlag: 1, chrgitmLv: '', selfpayProp: null, payStd: '',
          highValueFlag: 0, implantFlag: 0, sterileFlag: 0, status: 1, effDate: '', endDate: '', memo: ''
        };
      },
      loadCons: function () {
        var vm = this; vm.cons.loading = true;
        var q = '/api/community-dict/cons/page?page=' + vm.cons.page + '&size=' + vm.cons.size;
        if (vm.cons.keyword) { q += '&keyword=' + encodeURIComponent(vm.cons.keyword); }
        if (vm.cons.mapped) { q += '&mapped=' + encodeURIComponent(vm.cons.mapped); }
        HIS.get(q).then(function (d) { vm.cons.list = (d && d.records) || []; vm.cons.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.cons.loading = false; });
      },
      consSearch: function () { this.cons.page = 1; this.loadCons(); },
      consPage: function (p) { this.cons.page = p; this.loadCons(); },
      consSize: function (s) { this.cons.size = s; this.cons.page = 1; this.loadCons(); },
      consAdd: function () { this.consEditing = false; this.consImport = false; this.consOrigYb = ''; this.consForm = this.emptyCons(); this.consYb = {}; this.supplierOpts.consMfr = []; this.consDlg = true; },
      consEdit: function (row) { this.consEditing = true; this.consImport = false; this.consOrigYb = row.ybConsCode || ''; this.consForm = clean(Object.assign(this.emptyCons(), row)); this.loadYbInfo('cons', row.ybConsCode, 'consYb'); this.supSeed(this.consForm, 'manufacturerCode', 'manufacturer', 'consMfr'); this.consDlg = true; },
      consSubmit: function () {
        var vm = this; var f = vm.consForm;
        if (!f.name) { ElementPlus.ElMessage.warning('耗材名称必填'); return; }
        if (!f.consCode && !f.ybConsCode) { ElementPlus.ElMessage.warning('院内码或医保码至少填一项'); return; }
        if (vm.consImport) { HIS.post('/api/community-dict/cons/import', f).then(function () { HIS.notifySuccess('保存成功'); vm.consDlg = false; vm.loadCons(); }).catch(HIS.notifyError); return; }
        vm.confirmYbChange(vm.consEditing ? vm.consOrigYb : '', f.ybConsCode, '医保耗材码', function () {
          // 新增或改医保码时后端强制把甲乙丙类同步为医保分类(手填值会被覆盖) → 仅编辑未改码时才提示不一致
          var willSync = !vm.consEditing || ((vm.consOrigYb || '') !== (f.ybConsCode || ''));
          vm.confirmLvMismatch(vm.consYb, f.chrgitmLv, willSync, function () {
            var p = vm.consEditing ? HIS.put('/api/community-dict/cons', f) : HIS.post('/api/community-dict/cons', f);
            p.then(function () { HIS.notifySuccess('保存成功'); vm.consDlg = false; vm.loadCons(); }).catch(HIS.notifyError);
          });
        });
      },
      consDel: function (row) { var vm = this; HIS.del('/api/community-dict/cons/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadCons(); }).catch(HIS.notifyError); },
      consAdjust: function (row, field) {
        this.adjForm = this.emptyAdjust();
        this.adjForm.catalogType = 'cons'; this.adjForm.catalogId = row.id; this.adjForm.catalogName = row.name;
        this.adjForm.priceField = field; this.adjForm.orgLevel = null;
        this.adjDlg = true;
      },

      /* ============ 调价 ============ */
      emptyAdjust: function () {
        return {
          catalogType: 'charge', catalogId: null, catalogName: '', priceField: 'price_l1',
          newPrice: null, orgLevel: 1, adjustDocNo: '', effDate: '', reason: ''
        };
      },
      adjTypeChange: function () {
        var t = this.adjForm.catalogType;
        if (t === 'charge') { this.adjForm.priceField = 'price_l1'; this.adjForm.orgLevel = 1; }
        else if (t === 'drug') { this.adjForm.priceField = 'retail_price'; this.adjForm.orgLevel = null; }
        else { this.adjForm.priceField = 'charge_price'; this.adjForm.orgLevel = null; }
      },
      adjFieldOptions: function () {
        var t = this.adjForm.catalogType;
        if (t === 'drug') { return [{ v: 'retail_price', l: '零售价(最小单位)' }, { v: 'purchase_price', l: '进货价(最小单位)' }]; }
        if (t === 'cons') { return [{ v: 'charge_price', l: '收费价' }, { v: 'purchase_price', l: '进货价' }]; }
        return [{ v: 'price_l1', l: '一级价格' }, { v: 'price_l2', l: '二级价格' }, { v: 'price_l3', l: '三级价格' }];
      },
      adjFieldChange: function () {
        var f = this.adjForm.priceField;
        if (this.adjForm.catalogType === 'charge' && f && f.indexOf('price_l') === 0) {
          this.adjForm.orgLevel = Number(f.charAt(7));
        }
      },
      loadAdjust: function () {
        var vm = this; vm.adj.loading = true;
        var q = '/api/community-dict/price-adjust/page?page=' + vm.adj.page + '&size=' + vm.adj.size;
        if (vm.adj.catalogType) { q += '&catalogType=' + encodeURIComponent(vm.adj.catalogType); }
        HIS.get(q).then(function (d) { vm.adj.list = (d && d.records) || []; vm.adj.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.adj.loading = false; });
      },
      adjSearch: function () { this.adj.page = 1; this.loadAdjust(); },
      adjPage: function (p) { this.adj.page = p; this.loadAdjust(); },
      adjSize: function (s) { this.adj.size = s; this.adj.page = 1; this.loadAdjust(); },
      adjSubmit: function () {
        var vm = this; var f = vm.adjForm;
        if (!f.adjustDocNo) { ElementPlus.ElMessage.warning('调价文号必填'); return; }
        if (!f.effDate) { ElementPlus.ElMessage.warning('生效日期必填'); return; }
        if (f.newPrice == null || f.newPrice === '') { ElementPlus.ElMessage.warning('新价必填'); return; }
        HIS.post('/api/community-dict/price-adjust', f).then(function () {
          HIS.notifySuccess('调价成功'); vm.adjDlg = false;
          vm.loadAdjust();
          if (vm.activeTab === 'charge') { vm.loadCharge(); }
          else if (vm.activeTab === 'drug') { vm.loadDrug(); }
          else if (vm.activeTab === 'cons') { vm.loadCons(); }
        }).catch(HIS.notifyError);
      },

      /* ============ 标准字典导入 ============ */
      impTypeChange: function () {
        this.impDicts = IMPORT_DICTS[this.impType] || [];
        this.impDictKey = this.impDicts.length ? this.impDicts[0].key : '';
        this.std.page = 1; this.std.keyword = '';
        if (this.impType === 'val') { this.loadValDomains(); } else { this.loadStd(); }
      },
      loadStd: function () {
        var vm = this;
        if (!vm.impDictKey) { return; }
        vm.std.loading = true;
        var q = '/api/community-dict/std/page?dictKey=' + encodeURIComponent(vm.impDictKey)
          + '&page=' + vm.std.page + '&size=' + vm.std.size;
        if (vm.std.keyword) { q += '&keyword=' + encodeURIComponent(vm.std.keyword); }
        HIS.get(q).then(function (d) { vm.std.list = (d && d.records) || []; vm.std.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.std.loading = false; });
      },
      stdSearch: function () { if (this.impType === 'val') { this.loadValDomains(); return; } this.std.page = 1; this.loadStd(); },
      stdPage: function (p) { this.std.page = p; this.loadStd(); },
      stdSize: function (s) { this.std.size = s; this.std.page = 1; this.loadStd(); },
      /* 卫健值域"域清单"(国标>省标 归一化名称, 按域挑选导入) */
      loadValDomains: function () {
        var vm = this;
        if (!vm.impDictKey) { return; }
        vm.valDomains.loading = true; vm.valSel = [];
        var q = '/api/community-dict/val-dict/domains?src=' + encodeURIComponent(vm.impDictKey);
        if (vm.std.keyword) { q += '&keyword=' + encodeURIComponent(vm.std.keyword); }
        HIS.get(q).then(function (d) { vm.valDomains.list = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.valDomains.loading = false; });
      },
      valSelChange: function (rows) { this.valSel = rows || []; },
      valSelectable: function (row) { return !row.review; },
      valImportSelected: function () {
        var vm = this;
        if (!vm.valSel.length) { return; }
        var refs = vm.valSel.map(function (r) {
          return { src: vm.impDictKey, groupCode: r.groupCode, normName: r.normName, rawName: r.rawName };
        });
        ElementPlus.ElMessageBox.confirm(
          '将选中 ' + refs.length + ' 个值域导入医共体统一字典(国标>省标·同名同码取国标·同名不同码不自动并): 值域类别用归一化域名。继续?',
          '按域导入', { type: 'warning' }
        ).then(function () {
          vm.stdBatchLoading = true;
          HIS.post('/api/community-dict/val-dict/import-domains', refs).then(function (r) {
            var msg = '导入完成: 值域 ' + ((r && r.imported) || 0) + ' 个, 新增 ' + ((r && r.rowsInserted) || 0)
              + ' 行, 更新 ' + ((r && r.rowsUpdated) || 0) + ' 行';
            if (r && r.redirected && r.redirected.length) { msg += '; 国标优先取国标 ' + r.redirected.length + ' 个'; }
            if (r && r.needsReview && r.needsReview.length) { msg += '; 同名不同码待核 ' + r.needsReview.length + ' 个(未导入)'; }
            HIS.notifySuccess(msg);
            vm.loadValDomains();
            vm.loadValTypes();
          }).catch(HIS.notifyError).finally(function () { vm.stdBatchLoading = false; });
        }).catch(function () {});
      },
      /* 标准字典全表批量导入(收费项目/诊断字典; 幂等: 已存在编码更新) */
      stdBatchImport: function () {
        var vm = this;
        var d = (vm.impDicts || []).filter(function (x) { return x.key === vm.impDictKey; })[0];
        if (vm.impType === 'diag') {
          var dtype = (d && d.diagType) || '';
          var tl = DIAG_TYPES.filter(function (t) { return t.v === dtype; })[0];
          ElementPlus.ElMessageBox.confirm(
            '将「' + ((d && d.label) || vm.impDictKey) + '」全部行批量导入医共体诊断字典(' + ((tl && tl.l) || dtype) + '): 编码已存在则更新名称/类目/来源, 否则新增; 源字典可达数万行, 需等待数十秒。继续?',
            '批量导入', { type: 'warning' }
          ).then(function () {
            vm.stdBatchLoading = true;
            HIS.post('/api/community-dict/diag-dict/import-batch?dictType=' + encodeURIComponent(dtype)
              + '&dictKey=' + encodeURIComponent(vm.impDictKey), {})
              .then(function (r) {
                HIS.notifySuccess('批量导入完成: 新增 ' + ((r && r.inserted) || 0) + ' 条, 更新 ' + ((r && r.updated) || 0) + ' 条 (源字典共 ' + ((r && r.total) || 0) + ' 行)');
                vm.diag.page = 1; vm.diag.dictType = dtype; vm.loadDiag();
              }).catch(HIS.notifyError).finally(function () { vm.stdBatchLoading = false; });
          }).catch(function () {});
          return;
        }
        ElementPlus.ElMessageBox.confirm(
          '将「' + ((d && d.label) || vm.impDictKey) + '」全部项目批量导入医共体收费项目目录, 已存在编码自动跳过; 价格等管理字段导入后补录。继续?',
          '批量导入', { type: 'warning' }
        ).then(function () {
          vm.stdBatchLoading = true;
          HIS.post('/api/community-dict/charge/import-batch?dictKey=' + encodeURIComponent(vm.impDictKey), {})
            .then(function (r) {
              HIS.notifySuccess('批量导入完成: 新增 ' + ((r && r.imported) || 0) + ' 条, 跳过 ' + ((r && r.skipped) || 0) + ' 条 (共 ' + ((r && r.total) || 0) + ' 行)');
              vm.loadCharge();
            }).catch(HIS.notifyError).finally(function () { vm.stdBatchLoading = false; });
        }).catch(function () {});
      },
      /* 查看标准字典行完整内容(物价等): 拉取带中文列名的字段列表 */
      showStdDetail: function (row) {
        var vm = this;
        vm.stdDetailTitle = (row.code || '') + ' ' + (row.name || '');
        vm.stdDetailRows = []; vm.stdDetailLoading = true; vm.stdDetailDlg = true;
        HIS.get('/api/community-dict/std/detail?dictKey=' + encodeURIComponent(vm.impDictKey) + '&stdId=' + row.id)
          .then(function (d) { vm.stdDetailRows = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.stdDetailLoading = false; });
      },

      /* ============ 用药字典(用法/用药频次) ============ */
      emptyMed: function (t) {
        return {
          id: null, dictType: t || 'usage', code: '', name: '', ybCode: '',
          dailyTimes: null, sortNo: 0, status: 1, memo: '', srcType: '', srcDoc: '', srcCode: ''
        };
      },
      loadMed: function (t) {
        var vm = this; var st = vm.med[t]; st.loading = true;
        var q = '/api/community-dict/med-dict/page?dictType=' + t + '&page=' + st.page + '&size=' + st.size;
        if (st.keyword) { q += '&keyword=' + encodeURIComponent(st.keyword); }
        HIS.get(q).then(function (d) { st.list = (d && d.records) || []; st.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { st.loading = false; });
      },
      medSearch: function (t) { this.med[t].page = 1; this.loadMed(t); },
      medPage: function (t, p) { this.med[t].page = p; this.loadMed(t); },
      medSize: function (t, s) { this.med[t].size = s; this.med[t].page = 1; this.loadMed(t); },
      medAdd: function (t) { this.medEditing = false; this.medForm = this.emptyMed(t); this.medDlg = true; },
      medEdit: function (row) { this.medEditing = true; this.medForm = clean(Object.assign(this.emptyMed(row.dictType), row)); this.medDlg = true; },
      medSubmit: function () {
        var vm = this; var f = vm.medForm;
        if (!f.name) { ElementPlus.ElMessage.warning('名称必填'); return; }
        if (!f.code) { ElementPlus.ElMessage.warning('院内编码必填'); return; }
        var p = vm.medEditing ? HIS.put('/api/community-dict/med-dict', f) : HIS.post('/api/community-dict/med-dict', f);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.medDlg = false; vm.loadMed(f.dictType); }).catch(HIS.notifyError);
      },
      medDel: function (row) { var vm = this; HIS.del('/api/community-dict/med-dict/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadMed(row.dictType); }).catch(HIS.notifyError); },
      /* 从医保标准值域批量导入用法/频次 */
      openMedImp: function (t) {
        this.medImpType = t; this.medImpSrcs = MED_IMPORT_SRCS[t] || [];
        this.medImpSrc = this.medImpSrcs.length ? this.medImpSrcs[0].v : '';
        this.medImpSel = []; this.loadMedImpVals(); this.medImpDlg = true;
      },
      loadMedImpVals: function () {
        var vm = this;
        if (!vm.medImpSrc) { vm.medImpVals = []; return; }
        var idx = vm.medImpSrc.indexOf(':'); var type = vm.medImpSrc.substring(0, idx); var code = vm.medImpSrc.substring(idx + 1);
        var src = null;
        for (var i = 0; i < vm.medImpSrcs.length; i++) { if (vm.medImpSrcs[i].v === vm.medImpSrc) { src = vm.medImpSrcs[i]; } }
        vm.medImpLoading = true;
        HIS.stdValues(type, code).then(function (l) {
          vm.medImpVals = (l || []).map(function (o) {
            return { code: o.code, name: o.name, ybCode: o.code, srcType: type, srcDoc: src ? src.l : '', srcCode: o.code, dailyTimes: null };
          });
        }).catch(HIS.notifyError).finally(function () { vm.medImpLoading = false; });
      },
      onMedImpSel: function (rows) { this.medImpSel = rows || []; },
      doMedImp: function () {
        var vm = this;
        if (!vm.medImpSel.length) { ElementPlus.ElMessage.warning('请勾选要导入的值域项'); return; }
        var items = vm.medImpSel.map(function (r) {
          return { code: r.code, name: r.name, ybCode: r.ybCode, srcType: r.srcType, srcDoc: r.srcDoc, srcCode: r.srcCode, dailyTimes: r.dailyTimes, status: 1 };
        });
        HIS.post('/api/community-dict/med-dict/import-batch', { dictType: vm.medImpType, items: items })
          .then(function (n) { HIS.notifySuccess('已导入 ' + (n || 0) + ' 项'); vm.medImpDlg = false; vm.loadMed(vm.medImpType); })
          .catch(HIS.notifyError);
      },

      /* ============ 诊断字典(西医/中医/症候/手术/肿瘤) ============ */
      emptyDiag: function () {
        return {
          id: null, dictType: 'west', code: '', name: '', ybCode: '', category: '',
          sortNo: 0, status: 1, memo: '', pyCode: '', abbrCode: '', srcType: '', srcDoc: '', srcCode: ''
        };
      },
      diagTypeLabel: function (t) {
        var o = DIAG_TYPES.filter(function (x) { return x.v === t; })[0];
        return o ? o.l : (t || '');
      },
      loadDiag: function () {
        var vm = this; vm.diag.loading = true;
        var q = '/api/community-dict/diag-dict/page?dictType=' + vm.diag.dictType
          + '&page=' + vm.diag.page + '&size=' + vm.diag.size;
        if (vm.diag.keyword) { q += '&keyword=' + encodeURIComponent(vm.diag.keyword); }
        if (vm.diag.mapped) { q += '&mapped=' + encodeURIComponent(vm.diag.mapped); }
        HIS.get(q).then(function (d) { vm.diag.list = (d && d.records) || []; vm.diag.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.diag.loading = false; });
      },
      diagTypeChange: function () { this.diag.page = 1; this.loadDiag(); },
      diagSearch: function () { this.diag.page = 1; this.loadDiag(); },
      diagPage: function (p) { this.diag.page = p; this.loadDiag(); },
      diagSize: function (s) { this.diag.size = s; this.diag.page = 1; this.loadDiag(); },
      diagAdd: function () {
        this.diagEditing = false; this.diagImport = false;
        this.diagForm = this.emptyDiag(); this.diagForm.dictType = this.diag.dictType; this.diagDlg = true;
      },
      diagEdit: function (row) {
        this.diagEditing = true; this.diagImport = false;
        this.diagForm = clean(Object.assign(this.emptyDiag(), row)); this.diagDlg = true;
      },
      diagSubmit: function () {
        var vm = this; var f = vm.diagForm;
        if (!f.code) { ElementPlus.ElMessage.warning('编码必填'); return; }
        if (!f.name) { ElementPlus.ElMessage.warning('名称必填'); return; }
        // 新增/逐行导入同走 POST(后端按 dict_type+code 幂等); 编辑走 PUT
        var p = vm.diagEditing ? HIS.put('/api/community-dict/diag-dict', f) : HIS.post('/api/community-dict/diag-dict', f);
        p.then(function () {
          HIS.notifySuccess('保存成功'); vm.diagDlg = false;
          vm.diag.page = 1; vm.diag.dictType = f.dictType; vm.loadDiag();
        }).catch(HIS.notifyError);
      },
      diagDel: function (row) { var vm = this; HIS.del('/api/community-dict/diag-dict/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadDiag(); }).catch(HIS.notifyError); },

      /* ============ 值域字典(业务自由值域, dict_type=标准源键:分组码) ============ */
      emptyVal: function (t) {
        return {
          id: null, dictType: t || 'cv_code:gend', typeName: '', code: '', name: '', ybCode: '',
          sortNo: 0, status: 1, memo: '', pyCode: '', abbrCode: '', srcType: '', srcDoc: '', srcCode: ''
        };
      },
      valLabel: function (t) {
        var o = (this.valTypes || []).filter(function (x) { return x.v === t; })[0] || VAL_TYPES.filter(function (x) { return x.v === t; })[0];
        return o ? o.l : (t || '');
      },
      /* 动态加载类别下拉: 以库内实际 dict_type 为准(方案2根治), 预设白名单仅作标签美化与未导入项补位 */
      loadValTypes: function () {
        var vm = this;
        HIS.get('/api/community-dict/val-dict/types').then(function (list) {
          var seen = {}; var out = [];
          (list || []).forEach(function (t) {
            if (!t || !t.v || seen[t.v]) { return; }
            seen[t.v] = 1;
            out.push({ v: t.v, l: VAL_LABEL[t.v] || t.l || t.v, count: t.count });
          });
          VAL_TYPES.forEach(function (t) {
            if (!seen[t.v]) { seen[t.v] = 1; out.push({ v: t.v, l: t.l, count: 0 }); }
          });
          vm.valTypes = out;
          if (vm.val.dictType && !seen[vm.val.dictType] && out.length) { vm.val.dictType = out[0].v; }
        }).catch(function () {});
      },
      loadVal: function () {
        var vm = this; vm.val.loading = true;
        var q = '/api/community-dict/val-dict/page?dictType=' + encodeURIComponent(vm.val.dictType)
          + '&page=' + vm.val.page + '&size=' + vm.val.size;
        if (vm.val.keyword) { q += '&keyword=' + encodeURIComponent(vm.val.keyword); }
        HIS.get(q).then(function (d) { vm.val.list = (d && d.records) || []; vm.val.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.val.loading = false; });
      },
      valTypeChange: function () { this.val.page = 1; this.loadVal(); },
      valSearch: function () { this.val.page = 1; this.loadVal(); },
      valPage: function (p) { this.val.page = p; this.loadVal(); },
      valSize: function (s) { this.val.size = s; this.val.page = 1; this.loadVal(); },
      valAdd: function () { this.valEditing = false; this.valForm = this.emptyVal(this.val.dictType); this.valDlg = true; },
      valEdit: function (row) { this.valEditing = true; this.valForm = clean(Object.assign(this.emptyVal(row.dictType), row)); this.valDlg = true; },
      valSubmit: function () {
        var vm = this; var f = vm.valForm;
        if (!f.code) { ElementPlus.ElMessage.warning('值编码必填'); return; }
        if (!f.name) { ElementPlus.ElMessage.warning('值名称必填'); return; }
        var p = vm.valEditing ? HIS.put('/api/community-dict/val-dict', f) : HIS.post('/api/community-dict/val-dict', f);
        p.then(function () {
          HIS.notifySuccess('保存成功'); vm.valDlg = false;
          vm.val.page = 1; vm.val.dictType = f.dictType; vm.loadVal();
        }).catch(HIS.notifyError);
      },
      valDel: function (row) { var vm = this; HIS.del('/api/community-dict/val-dict/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadVal(); }).catch(HIS.notifyError); },
      /* 整组导入: 从对应基本字典(std_*值域表)拉全组幂等 upsert, 组行数十~数百级 */
      valImportStd: function () {
        var vm = this; var t = vm.val.dictType;
        ElementPlus.ElMessageBox.confirm('从标准值域「' + (vm.valLabel(t)) + '」整组导入到医共体统一字典(已存在项自动更新)?', '整组导入', { type: 'info' })
          .then(function () {
            vm.val.loading = true;
            HIS.post('/api/community-dict/val-dict/import-batch?dictType=' + encodeURIComponent(t), {})
              .then(function (r) {
                r = r || {};
                HIS.notifySuccess('导入完成: 源 ' + (r.total || 0) + ' 行, 新增 ' + (r.inserted || 0) + ', 更新 ' + (r.updated || 0));
                vm.val.page = 1; vm.loadVal(); vm.loadValTypes();
              })
              .catch(HIS.notifyError)
              .finally(function () { vm.val.loading = false; });
          }).catch(function () {});
      },
      /* 诊断字典页快捷跳转导入Tab(预选诊断类与当前浏览类别对应的源) */
      gotoDiagImport: function () {
        this.impType = 'diag';
        this.impDicts = IMPORT_DICTS.diag;
        var cur = this.diag.dictType;
        var first = IMPORT_DICTS.diag.filter(function (x) { return x.diagType === cur; })[0];
        this.impDictKey = (first || IMPORT_DICTS.diag[0]).key;
        this.std.page = 1; this.std.keyword = '';
        this.activeTab = 'import';
        this.loadStd();
      },

      /* 选择标准字典行 -> 拉取映射预览 -> 打开对应目录补录弹窗 */
      pickStd: function (row) {
        var vm = this;
        if (vm.impType === 'drug') {
          HIS.get('/api/community-dict/std-preview/drug?stdId=' + row.id).then(function (d) {
            vm.drugEditing = false; vm.drugImport = true; vm.drugForm = Object.assign(vm.emptyDrug(), d || {});
            vm.activeTab = 'drug'; vm.drugDlg = true;
          }).catch(HIS.notifyError);
        } else if (vm.impType === 'cons') {
          HIS.get('/api/community-dict/std-preview/cons?stdId=' + row.id).then(function (d) {
            vm.consEditing = false; vm.consImport = true; vm.consForm = Object.assign(vm.emptyCons(), d || {});
            vm.activeTab = 'cons'; vm.consDlg = true;
          }).catch(HIS.notifyError);
        } else if (vm.impType === 'diag') {
          var dd = (vm.impDicts || []).filter(function (x) { return x.key === vm.impDictKey; })[0];
          HIS.get('/api/community-dict/std-preview/diag?dictType=' + encodeURIComponent((dd && dd.diagType) || 'west')
            + '&dictKey=' + encodeURIComponent(vm.impDictKey) + '&stdId=' + row.id).then(function (d) {
            vm.diagEditing = false; vm.diagImport = true;
            vm.diagForm = Object.assign(vm.emptyDiag(), d || {});
            vm.activeTab = 'diag'; vm.diagDlg = true;
          }).catch(HIS.notifyError);
        } else {
          HIS.get('/api/community-dict/std-preview/charge?dictKey=' + encodeURIComponent(vm.impDictKey) + '&stdId=' + row.id).then(function (d) {
            vm.chgEditing = false; vm.chgImport = true; vm.chgForm = Object.assign(vm.emptyCharge(), d || {});
            vm.activeTab = 'charge'; vm.chgDlg = true;
          }).catch(HIS.notifyError);
        }
      }
    },
    template: [
      '<div class="page-card cd-fill cd-tabs">',
      '  <div class="page-title">医共体统一字典 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(牵头机构统一维护三目录 · 含分级价格 · 调价留痕)</span></div>',
      '  <el-tabs v-model="activeTab" @tab-change="onTab">',

      /* ---- 收费项目目录 ---- */
      '    <el-tab-pane label="收费项目目录" name="charge">',
      '      <div class="dept-split fill">',
      '        <div class="dept-orgs">',
      '          <div class="dept-orgs-hd"><span>分类目录树</span></div>',
      '          <div style="padding:6px 8px 0;">',
      '            <el-select v-model="chg.dim" size="small" style="width:100%" @change="chgDimChange"><el-option v-for="d in chgDims" :key="d.v" :label="d.l" :value="d.v"></el-option></el-select>',
      '            <el-input v-model="chg.treeKw" size="small" clearable placeholder="过滤目录名称" style="margin-top:6px"></el-input>',
      '          </div>',
      '          <el-scrollbar>',
      '            <div class="dept-org-item" :class="{active: !chg.sel}" @click="chgClearSel"><span class="tree-caret"></span><span>全部项目</span><span class="tree-cnt">{{ chgAllCnt }}</span></div>',
      '            <div v-for="n in chgTreeRows" :key="n.key" class="dept-org-item" :style="{paddingLeft: (12 + n.pad * 16) + \'px\'}" :class="{active: chgSelKey === n.key}" :title="n.label" @click="chgNodeClick(n)">',
      '              <span v-if="n.kids.length" class="tree-caret" :class="{\'is-inert\': !!String(chg.treeKw||\'\').trim()}" :title="chg.treeKw?\'过滤态自动展开全部, 清空关键字后可折叠\':\'折叠/展开\'" @click.stop="chg.treeKw ? null : chgToggleFold(n)">{{ (!chg.treeKw && chg.folded[n.key]) ? \'▸\' : \'▾\' }}</span>',
      '              <span v-else class="tree-caret"></span>',
      '              <span v-html="chgHl(n.label)"></span>',
      '              <span v-if="n.cnt" class="tree-cnt" title="项目数(含下级分类)">{{ n.cnt }}</span>',
      '            </div>',
      '            <div v-if="chg.treeKw && !chgTreeRows.length" class="dept-tree-empty">无匹配目录</div>',
      '          </el-scrollbar>',
      '        </div>',
      '        <div class="dept-main">',
      '          <div class="toolbar">',
      '            <el-select v-model="chg.itemType" placeholder="全部大类" clearable style="width:120px" @change="chgSearch"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '            <el-input v-model="chg.keyword" placeholder="名称/编码/拼音简码/医保码" clearable style="width:220px" @keyup.enter="chgSearch"></el-input>',
      '            <el-select v-model="chg.mapped" placeholder="对照状态" clearable style="width:110px" @change="chgSearch"><el-option v-for="o in mappedOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select>',
      '            <el-button @click="chgSearch">查询</el-button>',
      '            <el-button type="primary" @click="chgAdd">新增项目</el-button>',
      '            <span v-if="chg.sel" class="filter-chip" :title="chg.sel.label"><span v-text="chgSelDimLabel"></span>：{{ chg.sel.label }}</span><span v-if="chg.sel" class="filter-chip-x" @click="chgClearSel">×</span>',
      '            <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ chg.total }} 项</span>',
      '            <el-radio-group v-model="chg.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'charge\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '          </div>',
      '          <div class="table-box">',
      '          <el-table :data="chg.list" v-loading="chg.loading" border stripe size="small" height="100%">',
      '            <el-table-column type="index" label="序号" width="55" :index="seq(chg)"></el-table-column>',
      '            <el-table-column prop="itemCode" label="院内编码" width="110"></el-table-column>',
      '            <el-table-column prop="itemName" label="项目名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '            <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '            <el-table-column prop="medListCodg" label="医保编码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.medListCodg || "—" }}</template></el-table-column>',
      '            <el-table-column prop="ybName" label="医保名称" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.ybName || "—" }}</template></el-table-column>',
      '            <el-table-column prop="itemType" label="大类" width="70"></el-table-column>',
      '            <el-table-column prop="unit" label="单位" width="60"></el-table-column>',
      '            <el-table-column label="一级价" width="80"><template #default="s">{{ s.row.priceL1 }}</template></el-table-column>',
      '            <el-table-column label="二级价" width="80"><template #default="s">{{ s.row.priceL2 }}</template></el-table-column>',
      '            <el-table-column label="三级价" width="80"><template #default="s">{{ s.row.priceL3 }}</template></el-table-column>',
      '            <el-table-column prop="invoiceClass" label="票据分类" min-width="110" show-overflow-tooltip><template #default="s">{{ s.row.invoiceClass || "—" }}</template></el-table-column>',
      '            <el-table-column prop="acctClass" label="会计科目" min-width="110" show-overflow-tooltip><template #default="s">{{ s.row.acctClass || "—" }}</template></el-table-column>',
      '            <el-table-column prop="mrCostClass" label="病案首页归并" min-width="140" show-overflow-tooltip><template #default="s">{{ s.row.mrCostClass || "—" }}</template></el-table-column>',
      '            <el-table-column label="物价分类" min-width="200" show-overflow-tooltip><template #default="s"><span :title="s.row.catCode">{{ catPath(s.row.catCode) || "—" }}</span></template></el-table-column>',
      '            <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '            <el-table-column label="操作" width="180" fixed="right"><template #default="s">',
      '              <el-button link type="primary" @click="chgEdit(s.row)">编辑</el-button>',
      '              <el-button link type="warning" @click="chgAdjust(s.row)">调价</el-button>',
      '              <el-popconfirm title="确认删除？" @confirm="chgDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '            </template></el-table-column>',
      '          </el-table>',
      '          </div>',
      '          <el-pagination v-if="chg.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="chg.total" :page-size="chg.size" :page-sizes="[10,20,50,100]" :current-page="chg.page" @current-change="chgPage" @size-change="chgSize"></el-pagination>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ---- 药品目录 ---- */
      '    <el-tab-pane label="药品目录" name="drug">',
      '      <div class="toolbar">',
      '        <el-input v-model="drug.keyword" placeholder="通用名/商品名/编码/拼音简码" clearable style="width:240px" @keyup.enter="drugSearch"></el-input>',
      '        <el-select v-model="drug.mapped" placeholder="对照状态" clearable style="width:110px" @change="drugSearch"><el-option v-for="o in mappedOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select>',
      '        <el-button @click="drugSearch">查询</el-button>',
      '        <el-button type="primary" @click="drugAdd">新增药品</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ drug.total }} 项</span>',
      '        <el-radio-group v-model="drug.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'drug\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-table :data="drug.list" v-loading="drug.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(drug)"></el-table-column>',
      '        <el-table-column prop="drugCode" label="院内码" width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="genericName" label="通用名" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="ybDrugCode" label="医保编码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybDrugCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="ybName" label="医保名称" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybName || "—" }}</template></el-table-column>',
      '        <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="换算" width="150"><template #default="s">{{ s.row.unitDose }}{{ s.row.doseUnit || \'\' }}/{{ s.row.minUnit || \'\' }} ×{{ s.row.packRatio || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="retailPrice" label="零售价/最小" width="100"></el-table-column>',
      '        <el-table-column prop="packPrice" label="大包装参考" width="100"></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="230" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="drugEdit(s.row)">编辑</el-button>',
      '          <el-button link type="warning" @click="drugAdjust(s.row,\'retail_price\')">调零售价</el-button>',
      '          <el-button link type="warning" @click="drugAdjust(s.row,\'purchase_price\')">调进货价</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="drugDel(s.row)"><template #reference><el-button link type="danger">删</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="drug.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="drug.total" :page-size="drug.size" :page-sizes="[10,20,50,100]" :current-page="drug.page" @current-change="drugPage" @size-change="drugSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 耗材目录 ---- */
      '    <el-tab-pane label="耗材目录" name="cons">',
      '      <div class="toolbar">',
      '        <el-input v-model="cons.keyword" placeholder="名称/编码/拼音简码/注册证号" clearable style="width:240px" @keyup.enter="consSearch"></el-input>',
      '        <el-select v-model="cons.mapped" placeholder="对照状态" clearable style="width:110px" @change="consSearch"><el-option v-for="o in mappedOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select>',
      '        <el-button @click="consSearch">查询</el-button>',
      '        <el-button type="primary" @click="consAdd">新增耗材</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ cons.total }} 项</span>',
      '        <el-radio-group v-model="cons.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'cons\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-table :data="cons.list" v-loading="cons.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(cons)"></el-table-column>',
      '        <el-table-column prop="consCode" label="院内码" width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="耗材名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="ybConsCode" label="医保编码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybConsCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="ybName" label="医保名称" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybName || "—" }}</template></el-table-column>',
      '        <el-table-column prop="specModel" label="规格型号" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="manufacturer" label="生产企业" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="收费" width="90"><template #default="s">{{ s.row.chargeFlag===1?s.row.chargePrice:"包含性" }}</template></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="200" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="consEdit(s.row)">编辑</el-button>',
      '          <el-button link type="warning" @click="consAdjust(s.row,\'charge_price\')">调价</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="consDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="cons.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="cons.total" :page-size="cons.size" :page-sizes="[10,20,50,100]" :current-page="cons.page" @current-change="consPage" @size-change="consSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 诊断字典(西医/中医/症候/手术/肿瘤) ---- */
      '    <el-tab-pane label="诊断字典" name="diag">',
      '      <div class="toolbar">',
      '        <el-select v-model="diag.dictType" style="width:120px" @change="diagTypeChange"><el-option v-for="t in diagTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '        <el-input v-model="diag.keyword" placeholder="名称/编码/拼音简码/医保码/类目" clearable style="width:240px" @keyup.enter="diagSearch"></el-input>',
      '        <el-select v-model="diag.mapped" placeholder="对照状态" clearable style="width:110px" @change="diagSearch"><el-option v-for="o in mappedOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select>',
      '        <el-button @click="diagSearch">查询</el-button>',
      '        <el-button type="primary" @click="diagAdd">新增条目</el-button>',
      '        <el-button type="success" @click="gotoDiagImport">从标准字典批量导入</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ diag.total }} 条</span>',
      '        <el-radio-group v-model="diag.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'diag\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="西医诊断/中医诊断/症候/手术/肿瘤五类统一字典: 导入源字典(ICD-10/ICD-9/形态学/中医病证)可达数万行, 建议用关键字检索验证而非翻页; 医生站诊断录入统一检索本字典启用项。"></el-alert>',
      '      <el-table :data="diag.list" v-loading="diag.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(diag)"></el-table-column>',
      '        <el-table-column prop="code" label="编码" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保码" width="120" show-overflow-tooltip><template #default="s">{{ s.row.ybCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="category" label="类目" min-width="140" show-overflow-tooltip><template #default="s">{{ s.row.category || "—" }}</template></el-table-column>',
      '        <el-table-column prop="srcDoc" label="来源" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.srcDoc || "院内自定义" }}</template></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="diagEdit(s.row)">编辑</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="diagDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="diag.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="diag.total" :page-size="diag.size" :page-sizes="[10,20,50,100]" :current-page="diag.page" @current-change="diagPage" @size-change="diagSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 值域字典(业务自由值域统一取数源) ---- */
      '    <el-tab-pane label="值域字典" name="val">',
      '      <div class="toolbar">',
      '        <el-select v-model="val.dictType" style="width:250px" @change="valTypeChange"><el-option v-for="t in valTypes" :key="t.v" :label="t.l + \' · \' + t.v" :value="t.v"></el-option></el-select>',
      '        <el-input v-model="val.keyword" placeholder="名称/编码/拼音简码" clearable style="width:200px" @keyup.enter="valSearch"></el-input>',
      '        <el-button @click="valSearch">查询</el-button>',
      '        <el-button type="primary" @click="valAdd">新增条目</el-button>',
      '        <el-button type="success" @click="valImportStd">从标准值域整组导入</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ val.total }} 条</span>',
      '        <el-radio-group v-model="val.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'val\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="字典分层口径: 医疗业务下拉(性别/险种/剂型/号别/抗菌分级等值域)统一从本字典取数; 基本字典(std_*值域)仅作导入源。新增值域分组可在「标准源键:分组码」约定下扩展, 整组导入自动溯源。医生站/门诊/住院/基础数据页下拉已接入本字典。"></el-alert>',
      '      <el-table :data="val.list" v-loading="val.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(val)"></el-table-column>',
      '        <el-table-column prop="code" label="值编码" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="值名称" min-width="180" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保码" width="110"><template #default="s">{{ s.row.ybCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="typeName" label="值域组名" min-width="130" show-overflow-tooltip><template #default="s">{{ s.row.typeName || "—" }}</template></el-table-column>',
      '        <el-table-column prop="srcDoc" label="来源" min-width="180" show-overflow-tooltip><template #default="s">{{ s.row.srcDoc || "院内自定义" }}</template></el-table-column>',
      '        <el-table-column prop="sortNo" label="排序" width="70"></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="valEdit(s.row)">编辑</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="valDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="val.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="val.total" :page-size="val.size" :page-sizes="[20,50,100,200]" :current-page="val.page" @current-change="valPage" @size-change="valSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 调价记录 ---- */
      '    <el-tab-pane label="调价记录" name="adjust">',
      '      <div class="toolbar">',
      '        <el-select v-model="adj.catalogType" placeholder="全部目录" clearable style="width:130px" @change="adjSearch">',
      '          <el-option label="收费项目" value="charge"></el-option><el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option>',
      '        </el-select>',
      '        <el-button @click="adjSearch">查询</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ adj.total }} 条</span>',
      '        <el-radio-group v-model="adj.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'adjust\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-table :data="adj.list" v-loading="adj.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(adj)"></el-table-column>',
      '        <el-table-column prop="catalogType" label="目录" width="70"></el-table-column>',
      '        <el-table-column prop="catalogName" label="项目" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="priceLabel" label="调价项" width="120"></el-table-column>',
      '        <el-table-column prop="oldPrice" label="原价" width="80"></el-table-column>',
      '        <el-table-column prop="newPrice" label="新价" width="80"></el-table-column>',
      '        <el-table-column prop="adjustDocNo" label="调价文号" width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="effDate" label="生效日期" width="110"></el-table-column>',
      '        <el-table-column prop="operatorName" label="操作人" width="90"></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="adj.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="adj.total" :page-size="adj.size" :page-sizes="[10,20,50,100]" :current-page="adj.page" @current-change="adjPage" @size-change="adjSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 修改记录(字段级留痕) ---- */
      '    <el-tab-pane label="修改记录" name="elog">',
      '      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="逐字段记录三目录编辑保存的变更(修改前/后); 价格调整见「调价记录」页签, 医保码变更见「医共体管理→基础数据→医保目录对照」的对照变更记录。"></el-alert>',
      '      <div class="toolbar">',
      '        <el-select v-model="elog.catalogType" placeholder="全部目录" clearable style="width:130px" @change="elogSearch"><el-option label="收费项目" value="charge"></el-option><el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option></el-select>',
      '        <el-date-picker v-model="elog.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:240px" @change="elogSearch"></el-date-picker>',
      '        <el-input v-model="elog.keyword" placeholder="院内码/名称/字段" clearable style="width:200px" @keyup.enter="elogSearch"></el-input>',
      '        <el-button @click="elogSearch">查询</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ elog.total }} 条</span>',
      '        <el-radio-group v-model="elog.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'elog\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-table :data="elog.list" v-loading="elog.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(elog)"></el-table-column>',
      '        <el-table-column prop="changeTime" label="变更时间" width="160"></el-table-column>',
      '        <el-table-column label="目录" width="90"><template #default="s">{{ elCatText(s.row.catalogType) }}</template></el-table-column>',
      '        <el-table-column prop="itemCode" label="院内码" width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="itemName" label="名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="fieldLabel" label="变更字段" width="130"></el-table-column>',
      '        <el-table-column prop="oldValue" label="修改前" min-width="140" show-overflow-tooltip><template #default="s">{{ s.row.oldValue || "—" }}</template></el-table-column>',
      '        <el-table-column prop="newValue" label="修改后" min-width="140" show-overflow-tooltip><template #default="s">{{ s.row.newValue || "—" }}</template></el-table-column>',
      '        <el-table-column prop="source" label="来源" width="70"></el-table-column>',
      '        <el-table-column prop="operatorName" label="操作人" width="90"><template #default="s">{{ s.row.operatorName || s.row.operator || "—" }}</template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="elog.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="elog.total" :page-size="elog.size" :page-sizes="[10,20,50,100]" :current-page="elog.page" @current-change="elogPage" @size-change="elogSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 标准字典导入 ---- */
      '    <el-tab-pane label="标准字典导入" name="import">',
      '      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="从标准字典(L1)勾选行导入医共体目录(L2): 自动带出编码/名称/规格等, 弹窗补录分级价格/三级单位换算/管理分类后按医保编码幂等写入。"></el-alert>',
      '      <div class="toolbar">',
      '        <el-select v-model="impType" style="width:120px" @change="impTypeChange">',
      '          <el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option><el-option label="收费项目" value="charge"></el-option><el-option label="诊断字典" value="diag"></el-option><el-option label="值域字典" value="val"></el-option>',
      '        </el-select>',
      '        <el-select v-model="impDictKey" style="width:280px" @change="stdSearch"><el-option v-for="d in impDicts" :key="d.key" :label="d.label" :value="d.key"></el-option></el-select>',
      '        <el-input v-model="std.keyword" placeholder="编码/名称检索" clearable style="width:200px" @keyup.enter="stdSearch"></el-input>',
      '        <el-button @click="stdSearch">查询</el-button>',
      '        <el-button v-if="impType===\'charge\'||impType===\'diag\'" type="warning" :loading="stdBatchLoading" @click="stdBatchImport">批量导入全库</el-button>',
      '        <el-button v-if="impType===\'val\'" type="primary" :disabled="!valSel.length" :loading="stdBatchLoading" @click="valImportSelected">导入选中域({{ valSel.length }})</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ impType===\'val\' ? valDomains.list.length : std.total }} 条</span>',
      '        <el-radio-group v-if="impType!==\'val\'" v-model="std.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'import\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <template v-if="impType===\'val\'">',
      '        <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px;" title="卫健值域按国标>省标合并: 同名同码自动取国标; 标“待核”的是同名但码不同(不自动并), 不可勾选; 市标(武汉)按决策不并入。勾选域后点“导入选中域”。"></el-alert>',
      '        <el-table :data="valDomains.list" v-loading="valDomains.loading" border stripe size="small" height="100%" @selection-change="valSelChange">',
      '          <el-table-column type="selection" width="45" :selectable="valSelectable"></el-table-column>',
      '          <el-table-column prop="normName" label="域名称(归一)" min-width="170" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="groupCode" label="分组码" width="150" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="rawName" label="标准原名" min-width="180" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="count" label="值数" width="70" align="center"></el-table-column>',
      '          <el-table-column label="状态" width="230"><template #default="s"><el-tag size="small" :type="s.row.review?\'danger\':(s.row.covered?\'warning\':\'success\')">{{ s.row.status }}</el-tag></template></el-table-column>',
      '        </el-table>',
      '      </template>',
      '      <template v-else>',
      '      <el-table :data="std.list" v-loading="std.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(std)"></el-table-column>',
      '        <el-table-column prop="code" label="编码" width="200" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="spec" label="规格/单位" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="extra" label="附加" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '          <el-button link type="info" @click="showStdDetail(s.row)">详情</el-button>',
      '          <el-button link type="primary" @click="pickStd(s.row)">导入</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="std.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="std.total" :page-size="std.size" :page-sizes="[10,20,50,100]" :current-page="std.page" @current-change="stdPage" @size-change="stdSize"></el-pagination>',
      '      </template>',
      '    </el-tab-pane>',

      /* ---- 用法(给药途径) ---- */
      '    <el-tab-pane label="用法(给药途径)" name="usage">',
      '      <div class="toolbar">',
      '        <el-input v-model="med.usage.keyword" placeholder="名称/院内码/拼音简码" clearable style="width:220px" @keyup.enter="medSearch(\'usage\')"></el-input>',
      '        <el-button @click="medSearch(\'usage\')">查询</el-button>',
      '        <el-button type="primary" @click="medAdd(\'usage\')">新增用法</el-button>',
      '        <el-button type="success" @click="openMedImp(\'usage\')">从医保值域导入</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ med.usage.total }} 项</span>',
      '        <el-radio-group v-model="med.usage.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'usage\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-table :data="med.usage.list" v-loading="med.usage.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(med.usage)"></el-table-column>',
      '        <el-table-column prop="code" label="院内码" width="110"></el-table-column>',
      '        <el-table-column prop="name" label="用法名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保值域码" width="120"></el-table-column>',
      '        <el-table-column prop="srcDoc" label="来源" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="sortNo" label="排序" width="70"></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="medEdit(s.row)">编辑</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="medDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="med.usage.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="med.usage.total" :page-size="med.usage.size" :page-sizes="[10,20,50,100]" :current-page="med.usage.page" @current-change="function(p){medPage(\'usage\',p)}" @size-change="function(s){medSize(\'usage\',s)}"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 用药频次 ---- */
      '    <el-tab-pane label="用药频次" name="freq">',
      '      <div class="toolbar">',
      '        <el-input v-model="med.freq.keyword" placeholder="名称/院内码/拼音简码" clearable style="width:220px" @keyup.enter="medSearch(\'freq\')"></el-input>',
      '        <el-button @click="medSearch(\'freq\')">查询</el-button>',
      '        <el-button type="primary" @click="medAdd(\'freq\')">新增频次</el-button>',
      '        <el-button type="success" @click="openMedImp(\'freq\')">从医保值域导入</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ med.freq.total }} 项</span>',
      '        <el-radio-group v-model="med.freq.paged" size="small" style="margin-left:8px" @change="onDictPaged(\'freq\')"><el-radio-button :label="true">分页</el-radio-button><el-radio-button :label="false">全量</el-radio-button></el-radio-group>',
      '      </div>',
      '      <el-table :data="med.freq.list" v-loading="med.freq.loading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(med.freq)"></el-table-column>',
      '        <el-table-column prop="code" label="院内码" width="110"></el-table-column>',
      '        <el-table-column prop="name" label="频次名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="每日次数" width="90"><template #default="s">{{ s.row.dailyTimes!=null?s.row.dailyTimes:"-" }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保值域码" width="110"></el-table-column>',
      '        <el-table-column prop="srcDoc" label="来源" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="medEdit(s.row)">编辑</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="medDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination v-if="med.freq.paged" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="med.freq.total" :page-size="med.freq.size" :page-sizes="[10,20,50,100]" :current-page="med.freq.page" @current-change="function(p){medPage(\'freq\',p)}" @size-change="function(s){medSize(\'freq\',s)}"></el-pagination>',
      '    </el-tab-pane>',

      '  </el-tabs>',

      /* ==== 收费项目弹窗 ==== */
      '  <el-dialog v-model="chgDlg" :title="chgImport?\'导入收费项目(补录)\':(chgEditing?\'编辑收费项目\':\'新增收费项目\')" width="760px" top="6vh">',
      '    <el-form :model="chgForm" label-width="110px"><div style="max-height:60vh;overflow-y:auto;padding-right:6px;">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="院内编码"><el-input v-model="chgForm.itemCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="项目名称"><el-input v-model="chgForm.itemName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="chgForm.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="chgForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="项目大类"><el-select v-model="chgForm.itemType" style="width:100%"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="单位"><el-input v-model="chgForm.unit"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="一级价"><el-input v-model.number="chgForm.priceL1" type="number" :disabled="chgEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="二级价"><el-input v-model.number="chgForm.priceL2" type="number" :disabled="chgEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="三级价"><el-input v-model.number="chgForm.priceL3" type="number" :disabled="chgEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="全国编码"><el-input v-model="chgForm.natItemCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="湖北编码"><el-input v-model="chgForm.locItemCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="票据分类"><el-select v-model="chgForm.invoiceClass" clearable filterable style="width:100%" @change="onInvClassChange"><el-option v-for="o in invClassOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="会计科目"><el-select v-model="chgForm.acctClass" clearable filterable style="width:100%" @change="onAcctClassChange"><el-option v-for="o in acctClassOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="病案首页归并"><el-select v-model="chgForm.mrCostClass" clearable filterable style="width:100%"><el-option v-for="o in mrCostOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="物价分类"><el-select v-model="chgForm.catCode" clearable filterable style="width:100%" placeholder="导入自动带入, 可改选"><el-option v-for="o in msiCatOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医疗科室类别"><el-input v-model="chgForm.deptCaty"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保目录编码"><el-input v-model="chgForm.medListCodg" @change="loadYbInfo(\'charge\', $event, \'chgYb\')"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保名称"><el-input :value="chgYb.name || \'—\'" readonly></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="项目内涵"><el-input v-model="chgForm.itemContent" type="textarea" :rows="2"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="除外内容"><el-input v-model="chgForm.itemExcluded" type="textarea" :rows="2"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="甲乙丙类"><el-select v-model="chgForm.chrgitmLv" clearable style="width:100%"><el-option v-for="o in chrgLvOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自付比例"><el-input v-model.number="chgForm.selfpayProp" type="number" placeholder="0-1"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="生效日期"><el-date-picker v-model="chgForm.effDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="chgForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="备注"><el-input v-model="chgForm.memo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-alert v-if="chgEditing" type="warning" :closable="false" show-icon title="价格已锁定, 修改价格请使用「调价」功能(需录调价文号+生效日期, 全程留痕)。"></el-alert>',
      '    </div></el-form>',
      '    <template #footer><el-button @click="chgDlg=false">取消</el-button><el-button type="primary" @click="chgSubmit">确定</el-button></template>',
      '  </el-dialog>',

      /* ==== 药品弹窗 ==== */
      '  <el-dialog v-model="drugDlg" :title="drugImport?\'导入药品(补录管理字段)\':(drugEditing?\'编辑药品\':\'新增药品\')" width="820px" top="5vh">',
      '    <el-form :model="drugForm" label-width="120px"><div style="max-height:64vh;overflow-y:auto;padding-right:6px;">',
      '      <el-divider content-position="left">编码与来源</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="院内药品码"><el-input v-model="drugForm.drugCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保药品码"><el-input v-model="drugForm.ybDrugCode" @change="loadYbInfo(\'drug\', $event, \'drugYb\')"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保名称"><el-input :value="drugYb.name || \'—\'" readonly></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="本位码"><el-input v-model="drugForm.drugStdCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="批准文号"><el-input v-model="drugForm.approvalNo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">名称与分类</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="通用名"><el-input v-model="drugForm.genericName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="drugForm.pyCode" disabled placeholder="保存时按通用名自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="drugForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="商品名"><el-input v-model="drugForm.tradeName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="大类"><el-select v-model="drugForm.majorClass" clearable filterable style="width:100%" placeholder="从标准字典选择"><el-option v-for="o in majorClassOpts" :key="o.code" :label="o.name" :value="o.name"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="剂型"><el-select v-model="drugForm.dosform" clearable filterable style="width:100%"><el-option v-for="o in dosformOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规格"><el-input v-model="drugForm.spec"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="生产企业"><el-select v-model="drugForm.manufacturerCode" filterable remote reserve-keyword clearable :remote-method="function(q){ supSearch(\u0027drugMfr\u0027, q) }" :loading="supplierLoading" @change="supChange(drugForm,\u0027manufacturerCode\u0027,\u0027manufacturer\u0027,\u0027drugMfr\u0027)" placeholder="输入企业名称/编码/拼音检索" style="width:100%"><el-option v-for="o in supplierOpts.drugMfr" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="上市持有人"><el-select v-model="drugForm.mktHolderCode" filterable remote reserve-keyword clearable :remote-method="function(q){ supSearch(\u0027drugHold\u0027, q) }" :loading="supplierLoading" @change="supChange(drugForm,\u0027mktHolderCode\u0027,\u0027mktHolder\u0027,\u0027drugHold\u0027)" placeholder="输入企业名称/编码/拼音检索" style="width:100%"><el-option v-for="o in supplierOpts.drugHold" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">三级单位与换算</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="剂量单位"><el-select v-model="drugForm.doseUnit" clearable filterable allow-create style="width:100%"><el-option v-for="o in doseUnitOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="单位含药量"><el-input v-model.number="drugForm.unitDose" type="number" placeholder="如0.25"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="最小/发药单位"><el-select v-model="drugForm.minUnit" clearable filterable allow-create style="width:100%"><el-option v-for="o in packUnitOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="采购/大包装"><el-select v-model="drugForm.packUnit" clearable filterable allow-create style="width:100%"><el-option v-for="o in packUnitOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="包装换算比"><el-input v-model.number="drugForm.packRatio" type="number" placeholder="如24粒/盒"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="发药取整"><el-select v-model="drugForm.roundRule" style="width:100%"><el-option v-for="o in roundRules" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">价格(按最小单位)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="进货价"><el-input v-model.number="drugForm.purchasePrice" type="number" :disabled="drugEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="零售价"><el-input v-model.number="drugForm.retailPrice" type="number" :disabled="drugEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="零差率"><el-switch v-model="drugForm.zeroMargin" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">医保属性</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="甲乙丙类"><el-select v-model="drugForm.chrgitmLv" clearable style="width:100%"><el-option v-for="o in chrgLvOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="自付比例"><el-input v-model.number="drugForm.selfpayProp" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="支付标准"><el-input v-model="drugForm.payStdPrep"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="谈判药"><el-select v-model="drugForm.negoFlag" clearable style="width:100%"><el-option v-for="o in negoFlagOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="门特标识"><el-select v-model="drugForm.msdFlag" clearable style="width:100%"><el-option v-for="o in yesNoOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="限自费标识"><el-select v-model="drugForm.ltdSelfFlag" clearable style="width:100%"><el-option v-for="o in yesNoOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="限定支付范围"><el-input v-model="drugForm.limitScope"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">药事管理分类</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="药品管理类别"><el-select v-model="drugForm.drugClass" clearable style="width:100%"><el-option v-for="o in drugClassOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="抗菌药分级"><el-select v-model="drugForm.abxGrade" clearable style="width:100%"><el-option v-for="o in abxOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="储存条件"><el-select v-model="drugForm.storageCond" clearable style="width:100%"><el-option v-for="o in storageOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="6"><el-form-item label="OTC"><el-switch v-model="drugForm.otcFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="6"><el-form-item label="基本药物"><el-switch v-model="drugForm.essentialFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="6"><el-form-item label="需皮试"><el-switch v-model="drugForm.skinTestFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="6"><el-form-item label="妊娠分级"><el-select v-model="drugForm.pregClass" clearable style="width:100%"><el-option v-for="o in pregOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="单次最大量"><el-input v-model.number="drugForm.maxQtyOnce" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="商品码/条形码"><el-input v-model="drugForm.commodityCode" placeholder="EAN-13等, 三码校验之一"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="电子监管码"><el-input v-model="drugForm.supervisionCode" placeholder="中国药品电子监管码"></el-input></el-form-item></el-col>',
      '        <el-col :span="6"><el-form-item label="强制追溯"><el-switch v-model="drugForm.traceFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="生效日期"><el-date-picker v-model="drugForm.effDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="状态"><el-switch v-model="drugForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="备注"><el-input v-model="drugForm.memo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-alert v-if="drugEditing" type="warning" :closable="false" show-icon title="价格已锁定, 修改请用列表「调零售价/调进货价」(需录调价文号+生效日期)。"></el-alert>',
      '    </div></el-form>',
      '    <template #footer><el-button @click="drugDlg=false">取消</el-button><el-button type="primary" @click="drugSubmit">确定</el-button></template>',
      '  </el-dialog>',

      /* ==== 耗材弹窗 ==== */
      '  <el-dialog v-model="consDlg" :title="consImport?\'导入耗材(补录)\':(consEditing?\'编辑耗材\':\'新增耗材\')" width="780px" top="6vh">',
      '    <el-form :model="consForm" label-width="110px"><div style="max-height:62vh;overflow-y:auto;padding-right:6px;">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="院内耗材码"><el-input v-model="consForm.consCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保耗材码"><el-input v-model="consForm.ybConsCode" placeholder="20位" @change="loadYbInfo(\'cons\', $event, \'consYb\')"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保名称"><el-input :value="consYb.name || \'—\'" readonly></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="耗材名称"><el-input v-model="consForm.name"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="consForm.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="consForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="注册证号"><el-input v-model="consForm.regCertNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="一级分类"><el-input v-model="consForm.cat1"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="二级分类"><el-input v-model="consForm.cat2"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="三级分类"><el-input v-model="consForm.cat3"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规格型号"><el-input v-model="consForm.specModel"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="生产企业"><el-select v-model="consForm.manufacturerCode" filterable remote reserve-keyword clearable :remote-method="function(q){ supSearch(\u0027consMfr\u0027, q) }" :loading="supplierLoading" @change="supChange(consForm,\u0027manufacturerCode\u0027,\u0027manufacturer\u0027,\u0027consMfr\u0027)" placeholder="输入企业名称/编码/拼音检索" style="width:100%"><el-option v-for="o in supplierOpts.consMfr" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="材质"><el-input v-model="consForm.material"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="特征"><el-input v-model="consForm.feature"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="最小单位"><el-select v-model="consForm.minUnit" clearable filterable allow-create style="width:100%"><el-option v-for="o in packUnitOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="采购单位"><el-select v-model="consForm.packUnit" clearable filterable allow-create style="width:100%"><el-option v-for="o in packUnitOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="换算比"><el-input v-model.number="consForm.packRatio" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="进货价"><el-input v-model.number="consForm.purchasePrice" type="number" :disabled="consEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="收费价"><el-input v-model.number="consForm.chargePrice" type="number" :disabled="consEditing"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="单独收费"><el-switch v-model="consForm.chargeFlag" :active-value="1" :inactive-value="0" active-text="是" inactive-text="包含性"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="甲乙丙类"><el-select v-model="consForm.chrgitmLv" clearable style="width:100%"><el-option v-for="o in chrgLvOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="自付比例"><el-input v-model.number="consForm.selfpayProp" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="支付标准"><el-input v-model="consForm.payStd"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="高值耗材"><el-switch v-model="consForm.highValueFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="植入类"><el-switch v-model="consForm.implantFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="无菌"><el-switch v-model="consForm.sterileFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="生效日期"><el-date-picker v-model="consForm.effDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="consForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="备注"><el-input v-model="consForm.memo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-alert v-if="consEditing" type="warning" :closable="false" show-icon title="价格已锁定, 修改请用列表「调价」(需录调价文号+生效日期)。"></el-alert>',
      '    </div></el-form>',
      '    <template #footer><el-button @click="consDlg=false">取消</el-button><el-button type="primary" @click="consSubmit">确定</el-button></template>',
      '  </el-dialog>',

      /* ==== 调价弹窗 ==== */
      '  <el-dialog v-model="adjDlg" title="目录调价(留痕)" width="520px">',
      '    <el-form :model="adjForm" label-width="100px">',
      '      <el-form-item label="目录类型"><el-select v-model="adjForm.catalogType" style="width:100%" :disabled="!!adjForm.catalogId" @change="adjTypeChange"><el-option label="收费项目" value="charge"></el-option><el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option></el-select></el-form-item>',
      '      <el-form-item label="调价项目"><el-input v-model="adjForm.catalogName" readonly></el-input></el-form-item>',
      '      <el-form-item label="调价字段"><el-select v-model="adjForm.priceField" style="width:100%" @change="adjFieldChange"><el-option v-for="o in adjFieldOptions()" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="新价"><el-input v-model.number="adjForm.newPrice" type="number"></el-input></el-form-item>',
      '      <el-form-item label="调价文号"><el-input v-model="adjForm.adjustDocNo" placeholder="必填, 如 X医保发[2024]12号"></el-input></el-form-item>',
      '      <el-form-item label="生效日期"><el-date-picker v-model="adjForm.effDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="必填"></el-date-picker></el-form-item>',
      '      <el-form-item label="调价原因"><el-input v-model="adjForm.reason" type="textarea" :rows="2"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="adjDlg=false">取消</el-button><el-button type="primary" @click="adjSubmit">确定调价</el-button></template>',
      '  </el-dialog>',

      /* ==== 用药字典(用法/频次)编辑弹窗 ==== */
      '  <el-dialog v-model="medDlg" :title="medEditing?\'编辑用药字典\':\'新增用药字典\'" width="520px">',
      '    <el-form :model="medForm" label-width="100px">',
      '      <el-form-item label="字典类型"><el-radio-group v-model="medForm.dictType" :disabled="medEditing"><el-radio label="usage">用法(给药途径)</el-radio><el-radio label="freq">用药频次</el-radio></el-radio-group></el-form-item>',
      '      <el-form-item label="院内编码"><el-input v-model="medForm.code" placeholder="租户内同类型唯一"></el-input></el-form-item>',
      '      <el-form-item label="名称"><el-input v-model="medForm.name" placeholder="如口服 / 每天三次(tid)"></el-input></el-form-item>',
      '      <el-form-item label="拼音码"><el-input v-model="medForm.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item>',
      '      <el-form-item label="自定义码"><el-input v-model="medForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item>',
      '      <el-form-item label="医保值域码"><el-input v-model="medForm.ybCode" placeholder="对应医保标准值编码"></el-input></el-form-item>',
      '      <el-form-item v-if="medForm.dictType===\'freq\'" label="每日次数"><el-input v-model.number="medForm.dailyTimes" type="number" placeholder="驱动发药量换算, 如3; 隔日填0.5"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input v-model.number="medForm.sortNo" type="number"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="medForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="medForm.memo"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="medDlg=false">取消</el-button><el-button type="primary" @click="medSubmit">确定</el-button></template>',
      '  </el-dialog>',

      /* ==== 诊断字典编辑弹窗 ==== */
      '  <el-dialog v-model="diagDlg" :title="diagImport?\'导入诊断字典(确认补录)\':(diagEditing?\'编辑诊断条目\':\'新增诊断条目\')" width="560px" top="8vh">',
      '    <el-form :model="diagForm" label-width="100px">',
      '      <el-form-item label="字典类别"><el-radio-group v-model="diagForm.dictType" :disabled="diagEditing">',
      '        <el-radio v-for="t in diagTypes" :key="t.v" :label="t.v">{{ t.l }}</el-radio>',
      '      </el-radio-group></el-form-item>',
      '      <el-form-item label="编码"><el-input v-model="diagForm.code" placeholder="租户内同类别唯一, 导入时取标准字典编码"></el-input></el-form-item>',
      '      <el-form-item label="名称"><el-input v-model="diagForm.name" placeholder="诊断/术式/症候名"></el-input></el-form-item>',
      '      <el-form-item label="拼音码"><el-input v-model="diagForm.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item>',
      '      <el-form-item label="自定义码"><el-input v-model="diagForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item>',
      '      <el-form-item label="医保码"><el-input v-model="diagForm.ybCode" placeholder="医保版源导入自动=编码; 国标版可人工补录"></el-input></el-form-item>',
      '      <el-form-item label="类目"><el-input v-model="diagForm.category" placeholder="章节/系统类目等(导入自动带入)"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input v-model.number="diagForm.sortNo" type="number"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="diagForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="diagForm.memo"></el-input></el-form-item>',
      '      <el-alert v-if="diagForm.srcType" type="info" :closable="false" show-icon :title="\'来源: \' + diagForm.srcType + (diagForm.srcDoc ? \' · \' + diagForm.srcDoc : \'\')"></el-alert>',
      '    </el-form>',
      '    <template #footer><el-button @click="diagDlg=false">取消</el-button><el-button type="primary" @click="diagSubmit">确定</el-button></template>',
      '  </el-dialog>',

      /* ==== 值域字典条目编辑弹窗 ==== */
      '  <el-dialog v-model="valDlg" :title="valEditing?\'编辑值域条目\':\'新增值域条目\'" width="520px" top="8vh">',
      '    <el-form :model="valForm" label-width="100px">',
      '      <el-form-item label="值域分组"><el-input :model-value="valForm.dictType" :disabled="valEditing" placeholder="标准源键:分组码, 如 cv_code:gend"></el-input></el-form-item>',
      '      <el-form-item label="值域名称"><el-input v-model="valForm.typeName" placeholder="如 性别代码(导入自动带入)"></el-input></el-form-item>',
      '      <el-form-item label="值编码"><el-input v-model="valForm.code" placeholder="租户内同组唯一, 导入取标准值域码"></el-input></el-form-item>',
      '      <el-form-item label="值名称"><el-input v-model="valForm.name"></el-input></el-form-item>',
      '      <el-form-item label="拼音码"><el-input v-model="valForm.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item>',
      '      <el-form-item label="自定义码"><el-input v-model="valForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item>',
      '      <el-form-item label="医保码"><el-input v-model="valForm.ybCode" placeholder="cv_code 源导入自动=编码; 其余源可人工补录"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input v-model.number="valForm.sortNo" type="number"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="valForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="valForm.memo"></el-input></el-form-item>',
      '      <el-alert v-if="valForm.srcType" type="info" :closable="false" show-icon :title="\'来源: \' + valForm.srcType + (valForm.srcDoc ? \' · \' + valForm.srcDoc : \'\')"></el-alert>',
      '    </el-form>',
      '    <template #footer><el-button @click="valDlg=false">取消</el-button><el-button type="primary" @click="valSubmit">确定</el-button></template>',
      '  </el-dialog>',

      /* ==== 用药字典值域导入弹窗 ==== */
      '  <el-dialog v-model="medImpDlg" :title="(medImpType===\'freq\'?\'导入用药频次\':\'导入用法\')+\'(医保/医疗标准值域)\'" width="640px" top="8vh">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" :title="medImpType===\'freq\'?\'勾选频次值域导入; 每日次数由系统按名称自动解析(可导入后编辑)。\':\'勾选用法(给药途径)值域导入, 按医保值域码幂等写入。\'"></el-alert>',
      '    <div class="toolbar">',
      '      <el-select v-model="medImpSrc" style="width:360px" @change="loadMedImpVals"><el-option v-for="s in medImpSrcs" :key="s.v" :label="s.l" :value="s.v"></el-option></el-select>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ medImpVals.length }} 值, 已选 {{ medImpSel.length }}</span>',
      '    </div>',
      '    <el-table :data="medImpVals" v-loading="medImpLoading" border stripe size="small" height="360" @selection-change="onMedImpSel">',
      '      <el-table-column type="selection" width="45"></el-table-column>',
      '      <el-table-column prop="code" label="值域编码" width="120"></el-table-column>',
      '      <el-table-column prop="name" label="名称" min-width="220" show-overflow-tooltip></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="medImpDlg=false">取消</el-button><el-button type="primary" @click="doMedImp">导入选中</el-button></template>',
      '  </el-dialog>',

      /* ==== 标准字典详情弹窗 ==== */
      '  <el-dialog v-model="stdDetailDlg" :title="\'标准字典详情 - \'+stdDetailTitle" width="720px" top="6vh">',
      '    <el-table :data="stdDetailRows" v-loading="stdDetailLoading" border stripe size="small" max-height="60vh">',
      '      <el-table-column prop="label" label="字段" width="150"></el-table-column>',
      '      <el-table-column prop="value" label="内容" min-width="320"></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="stdDetailDlg=false">关闭</el-button></template>',
      '  </el-dialog>',

      '</div>'
    ].join('\n')
  };
})();
