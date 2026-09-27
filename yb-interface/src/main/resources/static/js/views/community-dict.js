/* 医共体统一字典(L2, 牵头机构维护): 收费项目/药品/耗材三目录 + 调价记录 + 标准字典导入 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var ITEM_TYPES = ['药品', '诊疗', '耗材', '其他'];
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
  /* 标准字典导入源: 类型 -> 可选字典 */
  var IMPORT_DICTS = {
    drug: [{ key: 'drug', label: '湖北医保药品(西药/中成药)' }],
    cons: [{ key: 'consumable', label: '湖北医用耗材(20位)' }],
    charge: [
      { key: 'msi_hb', label: '湖北医疗服务价格项目(2023)' },
      { key: 'msi_nat', label: '全国医疗服务项目技术规范(2023)' },
      { key: 'med_service', label: '湖北医疗服务项目编码库' }
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
        dosformOpts: [], chrgLvOpts: [], drugClassOpts: [], storageOpts: [],
        invClassOpts: [], acctClassOpts: [], mrCostOpts: [], msiCatMap: {}, msiCatOpts: [],
        doseUnitOpts: [], packUnitOpts: [], abxOpts: [],
        itemTypes: ITEM_TYPES, roundRules: ROUND_RULES, priceLv: PRICE_LV,
        /* 收费项目 */
        chg: { loading: false, list: [], total: 0, page: 1, size: 20, keyword: '', itemType: '' },
        chgDlg: false, chgEditing: false, chgImport: false, chgForm: this.emptyCharge(), chgYb: {},
        /* 药品 */
        drug: { loading: false, list: [], total: 0, page: 1, size: 20, keyword: '' },
        drugDlg: false, drugEditing: false, drugImport: false, drugForm: this.emptyDrug(), drugYb: {},
        /* 耗材 */
        cons: { loading: false, list: [], total: 0, page: 1, size: 20, keyword: '' },
        consDlg: false, consEditing: false, consImport: false, consForm: this.emptyCons(), consYb: {},
        /* 调价记录 */
        adj: { loading: false, list: [], total: 0, page: 1, size: 20, catalogType: '' },
        /* 字段级修改记录(价格/医保码之外的全部字段变更) */
        elog: { loading: false, list: [], total: 0, page: 1, size: 20, catalogType: '', keyword: '', range: [] },
        adjDlg: false, adjForm: this.emptyAdjust(),
        /* 标准字典导入 */
        impType: 'drug', impDictKey: 'drug', impDicts: IMPORT_DICTS.drug,
        std: { loading: false, list: [], total: 0, page: 1, size: 20, keyword: '' },
        stdBatchLoading: false,
        stdDetailDlg: false, stdDetailTitle: '', stdDetailRows: [], stdDetailLoading: false,
        /* 用药字典(用法/用药频次) */
        med: {
          usage: { loading: false, list: [], total: 0, page: 1, size: 20, keyword: '' },
          freq: { loading: false, list: [], total: 0, page: 1, size: 20, keyword: '' }
        },
        medDlg: false, medEditing: false, medForm: this.emptyMed('usage'),
        medImpDlg: false, medImpType: 'usage', medImpSrcs: MED_IMPORT_SRCS.usage,
        medImpSrc: 'cv_code:drug_medc_way_code', medImpVals: [], medImpSel: [], medImpLoading: false
      };
    },
    created: function () {
      this.loadDicts();
      this.loadCharge();
    },
    methods: {
      loadDicts: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'dosform').then(function (l) { vm.dosformOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'chrgitm_lv').then(function (l) { vm.chrgLvOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'drug_class').then(function (l) { vm.drugClassOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'storage_cond').then(function (l) { vm.storageOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'dose_unit').then(function (l) { vm.doseUnitOpts = l || []; }).catch(function () {});
        HIS.stdValues('cv_code', 'pack_unit').then(function (l) { vm.packUnitOpts = l || []; }).catch(function () {});
        HIS.stdValues('hbvalue', 'HBCV08.50.029').then(function (l) { vm.abxOpts = l || []; }).catch(function () {});
        /* 费用分类三字典(物价标准·财务归集口径): 票据分类/会计科目/病案首页归并 */
        HIS.get('/api/std-dict/query/invoice_class?page=1&size=100').then(function (d) {
          vm.invClassOpts = ((d && d.records) || []).map(function (o) { return { v: o.name, l: o.name, acct: o.spec }; });
        }).catch(function () {});
        HIS.get('/api/std-dict/query/acct_class?page=1&size=100').then(function (d) {
          vm.acctClassOpts = ((d && d.records) || []).map(function (o) { return { v: o.name, l: o.name, inv: o.spec }; });
        }).catch(function () {});
        HIS.get('/api/std-dict/query/mr_cost_class?page=1&size=100').then(function (d) {
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
        }).catch(function () {});
      },
      onTab: function (name) {
        if (name === 'charge') { this.loadCharge(); }
        else if (name === 'drug') { this.loadDrug(); }
        else if (name === 'cons') { this.loadCons(); }
        else if (name === 'adjust') { this.loadAdjust(); }
        else if (name === 'elog') { this.loadElog(); }
        else if (name === 'import') { this.loadStd(); }
        else if (name === 'usage') { this.loadMed('usage'); }
        else if (name === 'freq') { this.loadMed('freq'); }
      },
      seq: function (state) { return function (i) { return (state.page - 1) * state.size + i + 1; }; },

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
        HIS.get(q).then(function (d) { vm.chg.list = (d && d.records) || []; vm.chg.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.chg.loading = false; });
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
            p.then(function () { HIS.notifySuccess('保存成功'); vm.chgDlg = false; vm.loadCharge(); }).catch(HIS.notifyError);
          });
        });
      },
      chgDel: function (row) { var vm = this; HIS.del('/api/community-dict/charge/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadCharge(); }).catch(HIS.notifyError); },
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
          genericName: '', tradeName: '', majorClass: '', dosform: '', spec: '', manufacturer: '', mktHolder: '',
          chrgitmLv: '', selfpayProp: null, payStdPrep: '', negoFlag: '', msdFlag: '', ltdSelfFlag: '', limitScope: '',
          doseUnit: '', unitDose: null, minUnit: '', packUnit: '', packRatio: null, roundRule: 1,
          purchasePrice: null, retailPrice: null, zeroMargin: 1,
          drugClass: '', abxGrade: '', otcFlag: 0, essentialFlag: 0, pregClass: '', skinTestFlag: 0,
          storageCond: '', maxQtyOnce: null, status: 1, effDate: '', endDate: '', memo: ''
        };
      },
      loadDrug: function () {
        var vm = this; vm.drug.loading = true;
        var q = '/api/community-dict/drug/page?page=' + vm.drug.page + '&size=' + vm.drug.size;
        if (vm.drug.keyword) { q += '&keyword=' + encodeURIComponent(vm.drug.keyword); }
        HIS.get(q).then(function (d) { vm.drug.list = (d && d.records) || []; vm.drug.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.drug.loading = false; });
      },
      drugSearch: function () { this.drug.page = 1; this.loadDrug(); },
      drugPage: function (p) { this.drug.page = p; this.loadDrug(); },
      drugSize: function (s) { this.drug.size = s; this.drug.page = 1; this.loadDrug(); },
      drugAdd: function () { this.drugEditing = false; this.drugImport = false; this.drugOrigYb = ''; this.drugForm = this.emptyDrug(); this.drugYb = {}; this.drugDlg = true; },
      drugEdit: function (row) {
        this.drugEditing = true; this.drugImport = false; this.drugOrigYb = row.ybDrugCode || '';
        this.drugForm = clean(Object.assign(this.emptyDrug(), row)); this.loadYbInfo('drug', row.ybDrugCode, 'drugYb'); this.drugDlg = true;
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
          specModel: '', material: '', feature: '', manufacturer: '',
          minUnit: '', packUnit: '', packRatio: null,
          purchasePrice: null, chargePrice: null, chargeFlag: 1, chrgitmLv: '', selfpayProp: null, payStd: '',
          highValueFlag: 0, implantFlag: 0, sterileFlag: 0, status: 1, effDate: '', endDate: '', memo: ''
        };
      },
      loadCons: function () {
        var vm = this; vm.cons.loading = true;
        var q = '/api/community-dict/cons/page?page=' + vm.cons.page + '&size=' + vm.cons.size;
        if (vm.cons.keyword) { q += '&keyword=' + encodeURIComponent(vm.cons.keyword); }
        HIS.get(q).then(function (d) { vm.cons.list = (d && d.records) || []; vm.cons.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.cons.loading = false; });
      },
      consSearch: function () { this.cons.page = 1; this.loadCons(); },
      consPage: function (p) { this.cons.page = p; this.loadCons(); },
      consSize: function (s) { this.cons.size = s; this.cons.page = 1; this.loadCons(); },
      consAdd: function () { this.consEditing = false; this.consImport = false; this.consOrigYb = ''; this.consForm = this.emptyCons(); this.consYb = {}; this.consDlg = true; },
      consEdit: function (row) { this.consEditing = true; this.consImport = false; this.consOrigYb = row.ybConsCode || ''; this.consForm = clean(Object.assign(this.emptyCons(), row)); this.loadYbInfo('cons', row.ybConsCode, 'consYb'); this.consDlg = true; },
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
        this.std.page = 1; this.std.keyword = ''; this.loadStd();
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
      stdSearch: function () { this.std.page = 1; this.loadStd(); },
      stdPage: function (p) { this.std.page = p; this.loadStd(); },
      stdSize: function (s) { this.std.size = s; this.std.page = 1; this.loadStd(); },
      /* 标准字典全表批量导入收费项目(已存在院内编码自动跳过) */
      stdBatchImport: function () {
        var vm = this;
        var d = (vm.impDicts || []).filter(function (x) { return x.key === vm.impDictKey; })[0];
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
        } else {
          HIS.get('/api/community-dict/std-preview/charge?dictKey=' + encodeURIComponent(vm.impDictKey) + '&stdId=' + row.id).then(function (d) {
            vm.chgEditing = false; vm.chgImport = true; vm.chgForm = Object.assign(vm.emptyCharge(), d || {});
            vm.activeTab = 'charge'; vm.chgDlg = true;
          }).catch(HIS.notifyError);
        }
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医共体统一字典 <span style="font-size:12px;color:#909399;font-weight:normal;">(牵头机构统一维护三目录 · 含分级价格 · 调价留痕)</span></div>',
      '  <el-tabs v-model="activeTab" @tab-change="onTab">',

      /* ---- 收费项目目录 ---- */
      '    <el-tab-pane label="收费项目目录" name="charge">',
      '      <div class="toolbar">',
      '        <el-select v-model="chg.itemType" placeholder="全部大类" clearable style="width:120px" @change="chgSearch"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '        <el-input v-model="chg.keyword" placeholder="名称/编码/拼音简码/医保码" clearable style="width:220px" @keyup.enter="chgSearch"></el-input>',
      '        <el-button @click="chgSearch">查询</el-button>',
      '        <el-button type="primary" @click="chgAdd">新增项目</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ chg.total }} 项</span>',
      '      </div>',
      '      <el-table :data="chg.list" v-loading="chg.loading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="55" :index="seq(chg)"></el-table-column>',
      '        <el-table-column prop="itemCode" label="院内编码" width="110"></el-table-column>',
      '        <el-table-column prop="itemName" label="项目名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="medListCodg" label="医保编码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.medListCodg || "—" }}</template></el-table-column>',
      '        <el-table-column prop="ybName" label="医保名称" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.ybName || "—" }}</template></el-table-column>',
      '        <el-table-column prop="itemType" label="大类" width="70"></el-table-column>',
      '        <el-table-column prop="unit" label="单位" width="60"></el-table-column>',
      '        <el-table-column label="一级价" width="80"><template #default="s">{{ s.row.priceL1 }}</template></el-table-column>',
      '        <el-table-column label="二级价" width="80"><template #default="s">{{ s.row.priceL2 }}</template></el-table-column>',
      '        <el-table-column label="三级价" width="80"><template #default="s">{{ s.row.priceL3 }}</template></el-table-column>',
      '        <el-table-column label="物价分类" min-width="200" show-overflow-tooltip><template #default="s"><span :title="s.row.catCode">{{ catPath(s.row.catCode) }}</span></template></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="180" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="chgEdit(s.row)">编辑</el-button>',
      '          <el-button link type="warning" @click="chgAdjust(s.row)">调价</el-button>',
      '          <el-popconfirm title="确认删除？" @confirm="chgDel(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="chg.total" :page-size="chg.size" :page-sizes="[10,20,50,100]" :current-page="chg.page" @current-change="chgPage" @size-change="chgSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 药品目录 ---- */
      '    <el-tab-pane label="药品目录" name="drug">',
      '      <div class="toolbar">',
      '        <el-input v-model="drug.keyword" placeholder="通用名/商品名/编码/拼音简码" clearable style="width:240px" @keyup.enter="drugSearch"></el-input>',
      '        <el-button @click="drugSearch">查询</el-button>',
      '        <el-button type="primary" @click="drugAdd">新增药品</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ drug.total }} 项</span>',
      '      </div>',
      '      <el-table :data="drug.list" v-loading="drug.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="drug.total" :page-size="drug.size" :page-sizes="[10,20,50,100]" :current-page="drug.page" @current-change="drugPage" @size-change="drugSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 耗材目录 ---- */
      '    <el-tab-pane label="耗材目录" name="cons">',
      '      <div class="toolbar">',
      '        <el-input v-model="cons.keyword" placeholder="名称/编码/拼音简码/注册证号" clearable style="width:240px" @keyup.enter="consSearch"></el-input>',
      '        <el-button @click="consSearch">查询</el-button>',
      '        <el-button type="primary" @click="consAdd">新增耗材</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ cons.total }} 项</span>',
      '      </div>',
      '      <el-table :data="cons.list" v-loading="cons.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="cons.total" :page-size="cons.size" :page-sizes="[10,20,50,100]" :current-page="cons.page" @current-change="consPage" @size-change="consSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 调价记录 ---- */
      '    <el-tab-pane label="调价记录" name="adjust">',
      '      <div class="toolbar">',
      '        <el-select v-model="adj.catalogType" placeholder="全部目录" clearable style="width:130px" @change="adjSearch">',
      '          <el-option label="收费项目" value="charge"></el-option><el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option>',
      '        </el-select>',
      '        <el-button @click="adjSearch">查询</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ adj.total }} 条</span>',
      '      </div>',
      '      <el-table :data="adj.list" v-loading="adj.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="adj.total" :page-size="adj.size" :page-sizes="[10,20,50,100]" :current-page="adj.page" @current-change="adjPage" @size-change="adjSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 修改记录(字段级留痕) ---- */
      '    <el-tab-pane label="修改记录" name="elog">',
      '      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="逐字段记录三目录编辑保存的变更(修改前/后); 价格调整见「调价记录」页签, 医保码变更见「医共体管理→基础数据→医保目录对照」的对照变更记录。"></el-alert>',
      '      <div class="toolbar">',
      '        <el-select v-model="elog.catalogType" placeholder="全部目录" clearable style="width:130px" @change="elogSearch"><el-option label="收费项目" value="charge"></el-option><el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option></el-select>',
      '        <el-date-picker v-model="elog.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:240px" @change="elogSearch"></el-date-picker>',
      '        <el-input v-model="elog.keyword" placeholder="院内码/名称/字段" clearable style="width:200px" @keyup.enter="elogSearch"></el-input>',
      '        <el-button @click="elogSearch">查询</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ elog.total }} 条</span>',
      '      </div>',
      '      <el-table :data="elog.list" v-loading="elog.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="elog.total" :page-size="elog.size" :page-sizes="[10,20,50,100]" :current-page="elog.page" @current-change="elogPage" @size-change="elogSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 标准字典导入 ---- */
      '    <el-tab-pane label="标准字典导入" name="import">',
      '      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="从标准字典(L1)勾选行导入医共体目录(L2): 自动带出编码/名称/规格等, 弹窗补录分级价格/三级单位换算/管理分类后按医保编码幂等写入。"></el-alert>',
      '      <div class="toolbar">',
      '        <el-select v-model="impType" style="width:120px" @change="impTypeChange">',
      '          <el-option label="药品" value="drug"></el-option><el-option label="耗材" value="cons"></el-option><el-option label="收费项目" value="charge"></el-option>',
      '        </el-select>',
      '        <el-select v-model="impDictKey" style="width:280px" @change="stdSearch"><el-option v-for="d in impDicts" :key="d.key" :label="d.label" :value="d.key"></el-option></el-select>',
      '        <el-input v-model="std.keyword" placeholder="编码/名称检索" clearable style="width:200px" @keyup.enter="stdSearch"></el-input>',
      '        <el-button @click="stdSearch">查询</el-button>',
      '        <el-button v-if="impType===\'charge\'" type="warning" :loading="stdBatchLoading" @click="stdBatchImport">批量导入全库</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ std.total }} 条</span>',
      '      </div>',
      '      <el-table :data="std.list" v-loading="std.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="std.total" :page-size="std.size" :page-sizes="[10,20,50,100]" :current-page="std.page" @current-change="stdPage" @size-change="stdSize"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 用法(给药途径) ---- */
      '    <el-tab-pane label="用法(给药途径)" name="usage">',
      '      <div class="toolbar">',
      '        <el-input v-model="med.usage.keyword" placeholder="名称/院内码/拼音简码" clearable style="width:220px" @keyup.enter="medSearch(\'usage\')"></el-input>',
      '        <el-button @click="medSearch(\'usage\')">查询</el-button>',
      '        <el-button type="primary" @click="medAdd(\'usage\')">新增用法</el-button>',
      '        <el-button type="success" @click="openMedImp(\'usage\')">从医保值域导入</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ med.usage.total }} 项</span>',
      '      </div>',
      '      <el-table :data="med.usage.list" v-loading="med.usage.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="med.usage.total" :page-size="med.usage.size" :page-sizes="[10,20,50,100]" :current-page="med.usage.page" @current-change="function(p){medPage(\'usage\',p)}" @size-change="function(s){medSize(\'usage\',s)}"></el-pagination>',
      '    </el-tab-pane>',

      /* ---- 用药频次 ---- */
      '    <el-tab-pane label="用药频次" name="freq">',
      '      <div class="toolbar">',
      '        <el-input v-model="med.freq.keyword" placeholder="名称/院内码/拼音简码" clearable style="width:220px" @keyup.enter="medSearch(\'freq\')"></el-input>',
      '        <el-button @click="medSearch(\'freq\')">查询</el-button>',
      '        <el-button type="primary" @click="medAdd(\'freq\')">新增频次</el-button>',
      '        <el-button type="success" @click="openMedImp(\'freq\')">从医保值域导入</el-button>',
      '        <span style="color:#909399;font-size:13px;">共 {{ med.freq.total }} 项</span>',
      '      </div>',
      '      <el-table :data="med.freq.list" v-loading="med.freq.loading" border stripe size="small">',
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
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="med.freq.total" :page-size="med.freq.size" :page-sizes="[10,20,50,100]" :current-page="med.freq.page" @current-change="function(p){medPage(\'freq\',p)}" @size-change="function(s){medSize(\'freq\',s)}"></el-pagination>',
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
      '        <el-col :span="12"><el-form-item label="医保甲乙分类"><el-input :value="ybLvLabel(chgYb.chrgitmLv) || \'—\'" readonly></el-input></el-form-item></el-col>',
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
      '        <el-col :span="12"><el-form-item label="医保甲乙分类"><el-input :value="ybLvLabel(drugYb.chrgitmLv) || \'—\'" readonly></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="本位码"><el-input v-model="drugForm.drugStdCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="批准文号"><el-input v-model="drugForm.approvalNo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">名称与分类</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="通用名"><el-input v-model="drugForm.genericName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="drugForm.pyCode" disabled placeholder="保存时按通用名自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="drugForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="商品名"><el-input v-model="drugForm.tradeName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="大类"><el-input v-model="drugForm.majorClass"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="剂型"><el-select v-model="drugForm.dosform" clearable filterable style="width:100%"><el-option v-for="o in dosformOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规格"><el-input v-model="drugForm.spec"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="生产企业"><el-input v-model="drugForm.manufacturer"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="上市持有人"><el-input v-model="drugForm.mktHolder"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">三级单位与换算</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="剂量单位"><el-select v-model="drugForm.doseUnit" clearable filterable allow-create style="width:100%"><el-option v-for="o in doseUnitOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="单位含药量"><el-input v-model.number="drugForm.unitDose" type="number" placeholder="如0.25"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="最小/发药单位"><el-input v-model="drugForm.minUnit" placeholder="片/粒/支"></el-input></el-form-item></el-col>',
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
      '        <el-col :span="8"><el-form-item label="谈判药"><el-input v-model="drugForm.negoFlag"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="门特标识"><el-input v-model="drugForm.msdFlag"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="限自费标识"><el-input v-model="drugForm.ltdSelfFlag"></el-input></el-form-item></el-col>',
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
      '        <el-col :span="6"><el-form-item label="妊娠分级"><el-input v-model="drugForm.pregClass" placeholder="A/B/C/D/X"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="单次最大量"><el-input v-model.number="drugForm.maxQtyOnce" type="number"></el-input></el-form-item></el-col>',
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
      '        <el-col :span="12"><el-form-item label="医保甲乙分类"><el-input :value="ybLvLabel(consYb.chrgitmLv) || \'—\'" readonly></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="耗材名称"><el-input v-model="consForm.name"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="consForm.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="consForm.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="注册证号"><el-input v-model="consForm.regCertNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="一级分类"><el-input v-model="consForm.cat1"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="二级分类"><el-input v-model="consForm.cat2"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="三级分类"><el-input v-model="consForm.cat3"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规格型号"><el-input v-model="consForm.specModel"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="生产企业"><el-input v-model="consForm.manufacturer"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="材质"><el-input v-model="consForm.material"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="特征"><el-input v-model="consForm.feature"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="最小单位"><el-input v-model="consForm.minUnit" placeholder="个/套"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="采购单位"><el-input v-model="consForm.packUnit" placeholder="盒/包"></el-input></el-form-item></el-col>',
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

      /* ==== 用药字典值域导入弹窗 ==== */
      '  <el-dialog v-model="medImpDlg" :title="(medImpType===\'freq\'?\'导入用药频次\':\'导入用法\')+\'(医保/医疗标准值域)\'" width="640px" top="8vh">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" :title="medImpType===\'freq\'?\'勾选频次值域导入; 每日次数由系统按名称自动解析(可导入后编辑)。\':\'勾选用法(给药途径)值域导入, 按医保值域码幂等写入。\'"></el-alert>',
      '    <div class="toolbar">',
      '      <el-select v-model="medImpSrc" style="width:360px" @change="loadMedImpVals"><el-option v-for="s in medImpSrcs" :key="s.v" :label="s.l" :value="s.v"></el-option></el-select>',
      '      <span style="color:#909399;font-size:13px;">共 {{ medImpVals.length }} 值, 已选 {{ medImpSel.length }}</span>',
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
