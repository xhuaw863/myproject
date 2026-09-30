/* 住院医生站 - 医嘱面板: 长期/临时医嘱列表 + 开立对话框(type-ahead 药品/项目检索 + 成组清单) + 停止/作废
 * 接口: GET /api/his/inp/order/list | POST /api/his/inp/order | POST /api/his/inp/order/batch
 *       PUT /api/his/inp/order/{id}/stop | PUT /api/his/inp/order/{id}/cancel
 *       POST /api/his/inp/order/renew/{orderId}(T42续开: 仅已停止长期医嘱) | GET /api/his/inp/order/copy-data/{orderId}(副本预填)
 *       GET /api/his/inp/order-template/list | POST /api/his/inp/order/from-template(模板/套餐开嘱)
 *       POST /api/his/inp/order/rational-check(合理用药审查: 配伍/过敏/剂量 + 高警示/双人核对)
 * 检索源: /api/org-catalog/available/drug(本机构可开药品) /available/charge(收费项目) /available/med-dict(用法/频次)
 * 注册: HIS.components.InpOrderPanel (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 面板私有样式(高警示闪烁/审查提示条), 一次性注入 */
  (function ensureStyles() {
    if (document.getElementById('inp-order-extra-style')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-order-extra-style';
    st.textContent = [
      '@keyframes iwHighBlink { 50% { opacity:0.15; } }',
      '.iw-high-alert { color:var(--yb-danger); font-weight:700; font-size:16px; margin-left:4px; cursor:help; animation:iwHighBlink 0.9s step-start infinite; }',
      '.iw-warn-item { padding:6px 10px; margin-bottom:6px; background:var(--yb-warning-bg); border:1px solid var(--yb-warning-border); border-radius:var(--yb-r-sm); color:var(--yb-warning-strong); font-size:13px; }',
      '.iw-warn-item.danger { background:var(--yb-danger-bg); border-color:var(--yb-danger-border); color:var(--yb-danger-strong); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  const ORDER_TYPE = { 1: '长期', 2: '临时' };
  const ORDER_STATUS = { 1: '新开', 2: '已审核', 3: '执行中', 4: '已完成', 5: '已停止', 6: '已作废' };
  const ORDER_STATUS_TYPE = { 1: 'info', 2: 'warning', 3: 'success', 4: 'success', 5: 'danger', 6: 'info' };
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };

  /* 内联 SVG 图标(项目未引入图标库, 统一 24 视框 / currentColor 染色), 经 v-html 渲染 */
  const ICONS = {
    pause: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M9 5v14M15 5v14"/></svg>',
    ban: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M5.7 5.7l12.6 12.6"/></svg>',
    copy: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
    renew: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>',
    exec: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>',
    more: '<svg class="iw-ico-more" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>'
  };

  function money(value) {
    const n = Number(value);
    return isNaN(n) ? '0.00' : n.toFixed(2);
  }

  function shortTime(value) {
    if (!value) { return '-'; }
    const s = String(value).replace('T', ' ');
    return s.length >= 16 ? s.substring(5, 16) : s;
  }

  function emptyForm() {
    return {
      orderType: 1,
      orderCategory: 1,
      orderContent: '',
      drugId: null,
      chargeItemId: null,
      spec: '',
      dosage: '',
      dosageUnit: '',
      usageCode: null,
      freqCode: null,
      quantity: 1
    };
  }

  const InpOrderPanel = {
    name: 'InpOrderPanel',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return {
        loading: false,
        orders: [],
        total: 0,
        page: 1,
        size: 50,
        filterType: 0,
        filterStatus: null,
        dialogVisible: false,
        form: emptyForm(),
        pickedDrug: null,
        pickedCharge: null,
        pickDrugId: null,
        pickChargeId: null,
        drugOptions: [],
        drugSearching: false,
        chargeOptions: [],
        chargeSearching: false,
        usageOptions: [],
        freqOptions: [],
        dictLoaded: false,
        pendingItems: [],
        submitting: false,
        statuses: ORDER_STATUS,
        categories: ORDER_CATEGORY,
        contentTouched: false,
        /* 模板医嘱/套餐对话框 */
        tplVisible: false, tplMode: 1,
        tplRows: [], tplTotal: 0, tplPage: 1, tplSize: 10, tplLoading: false, tplApplying: false,
        tplKeyword: '', tplTypeFilter: null,
        /* 合理用药审查 */
        checkVisible: false, checkLoading: false, checkResult: null, overrideAccepted: false,
        /* 医保预审提示(T42): 开立/续开回执的无医保编码预警, 列表上方告警条 3 秒自动消失 */
        insTip: null,
        /* 操作图标 / 行右键菜单 / 执行详情 */
        icons: ICONS,
        ctx: { visible: false, x: 0, y: 0, row: null },
        execVisible: false, execRow: null
      };
    },
    computed: {
      isDrug() { return Number(this.form.orderCategory) === 1; },
      usageMap() {
        const map = {};
        this.usageOptions.forEach(o => { map[o.code] = o.name; });
        return map;
      },
      freqMap() {
        const map = {};
        this.freqOptions.forEach(o => { map[o.code] = o.name; });
        return map;
      },
      pendingTotal() {
        return this.pendingItems.reduce((sum, it) => sum + (Number(it.quantity) || 0) * (Number(it._price) || 0), 0);
      },
      drugUnitPrice() { return this.pickedDrug ? Number(this.pickedDrug.retailPrice) || 0 : 0; },
      /* 行右键菜单防溢出定位 */
      ctxStyle() {
        const x = Math.min(this.ctx.x, Math.max(window.innerWidth - 184, 0));
        const y = Math.min(this.ctx.y, Math.max(window.innerHeight - 184, 0));
        return { left: x + 'px', top: y + 'px' };
      }
    },
    watch: {
      visitId: { immediate: true, handler() { this.resetList(); } }
    },
    methods: {
      money,
      orderTypeText(v) { return ORDER_TYPE[v] || '-'; },
      statusText(v) { return ORDER_STATUS[v] || '-'; },
      statusTag(v) { return ORDER_STATUS_TYPE[v] || ''; },
      categoryText(v) { return ORDER_CATEGORY[v] || '-'; },
      usageText(code) { return this.usageMap[code] || code || '-'; },
      freqText(code) { return this.freqMap[code] || code || '-'; },
      timeText: shortTime,
      resetList() {
        this.page = 1;
        this.filterType = 0;
        this.filterStatus = null;
        this.orders = [];
        this.total = 0;
        if (this.visitId) { this.load(); }
      },
      load() {
        const vm = this;
        if (!vm.visitId) { return; }
        vm.loading = true;
        let url = '/api/his/inp/order/list?inpVisitId=' + HIS.idParam(vm.visitId)
          + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.filterType) { url += '&orderType=' + vm.filterType; }
        if (vm.filterStatus) { url += '&orderStatus=' + vm.filterStatus; }
        HIS.get(url)
          .then(function (data) {
            const list = (data && data.records) || [];
            vm.decorate(list);
            vm.orders = list;
            vm.total = Number(data && data.total) || list.length;
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      /* 成组标注: 同 groupNo 相邻行归属同一组(后端按时间倒序, 组内天然相邻), 组首行显示组序号 */
      decorate(list) {
        let seq = 0;
        const seen = {};
        let prevGroup = null;
        (list || []).forEach(function (row) {
          if (row.groupNo) {
            if (seen[row.groupNo] == null) { seen[row.groupNo] = ++seq; }
            row._groupLabel = '组' + seen[row.groupNo];
            row._groupFirst = row.groupNo !== prevGroup;
          } else {
            row._groupLabel = '';
            row._groupFirst = false;
          }
          prevGroup = row.groupNo;
        });
      },
      rowClass({ row }) {
        const cls = [Number(row.orderType) === 1 ? 'iw-order--long' : 'iw-order--temp'];
        if (row._groupFirst) { cls.push('iw-order--grp-first'); }
        else if (row.groupNo) { cls.push('iw-order--grp-child'); }
        return cls.join(' ');
      },
      onPageChange(p) { this.page = p; this.load(); },
      /* 过滤条件变化: 重置到第一页再查 */
      onFilterChange() { this.page = 1; this.load(); },
      /* ===== 开立对话框 ===== */
      openDialog() {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.form = emptyForm();
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pendingItems = [];
        this.checkResult = null;
        this.overrideAccepted = false;
        this.dialogVisible = true;
        this.loadMedDict();
      },
      loadMedDict() {
        const vm = this;
        if (vm.dictLoaded) { return; }
        vm.dictLoaded = true;
        HIS.get('/api/org-catalog/available/med-dict?dictType=usage')
          .then(function (list) { vm.usageOptions = list || []; }).catch(function () { vm.usageOptions = []; });
        HIS.get('/api/org-catalog/available/med-dict?dictType=freq')
          .then(function (list) { vm.freqOptions = list || []; }).catch(function () { vm.freqOptions = []; });
      },
      remoteDrugSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.drugOptions = []; return; }
        vm.drugSearching = true;
        HIS.get('/api/org-catalog/available/drug?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.drugOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.drugSearching = false; });
      },
      onPickDrug(id) {
        const vm = this;
        vm.pickDrugId = null;
        const drug = vm.drugOptions.find(d => HIS.sameId(d.id, id));
        if (!drug) { return; }
        vm.pickedDrug = drug;
        vm.form.drugId = drug.id;
        vm.form.spec = drug.spec || '';
        if (!vm.form.dosageUnit) { vm.form.dosageUnit = drug.doseUnit || drug.minUnit || ''; }
        if (!vm.contentTouched) { vm.form.orderContent = vm.composeDrugContent(); }
        /* 选药即触发合理用药审查(配伍/过敏/重复用药/剂量) */
        vm.checkResult = null;
        vm.overrideAccepted = false;
        vm.rationalCheck();
      },
      remoteChargeSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.chargeOptions = []; return; }
        vm.chargeSearching = true;
        HIS.get('/api/org-catalog/available/charge?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.chargeOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.chargeSearching = false; });
      },
      onPickCharge(id) {
        const vm = this;
        vm.pickChargeId = null;
        const item = vm.chargeOptions.find(x => HIS.sameId(x.id, id));
        if (!item) { return; }
        vm.pickedCharge = item;
        vm.form.chargeItemId = item.id;
        vm.form.spec = item.spec || '';
        if (!vm.contentTouched) { vm.form.orderContent = item.itemName || ''; }
      },
      composeDrugContent() {
        const d = this.pickedDrug;
        if (!d) { return this.form.orderContent || ''; }
        let text = d.genericName || d.itemName || '';
        if (this.form.dosage) { text += ' ' + this.form.dosage + (this.form.dosageUnit || ''); }
        const usage = this.usageMap[this.form.usageCode];
        if (usage) { text += ' ' + usage; }
        const freq = this.freqMap[this.form.freqCode];
        if (freq) { text += ' ' + freq; }
        return text;
      },
      autoCompose() {
        if (this.isDrug) { this.form.orderContent = this.composeDrugContent(); }
        else if (this.pickedCharge) { this.form.orderContent = this.pickedCharge.itemName || ''; }
        this.contentTouched = false;
      },
      onContentInput() { this.contentTouched = true; },
      onCategoryChange() {
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.form.drugId = null;
        this.form.chargeItemId = null;
        this.form.spec = '';
        this.form.dosage = '';
        this.form.usageCode = null;
        this.form.freqCode = null;
        this.contentTouched = false;
      },
      validateForm() {
        if (this.isDrug && !this.form.drugId) {
          ElementPlus.ElMessage.warning('药品类医嘱请先检索并选中药品');
          return false;
        }
        if (this.isDrug && this.checkResult && !this.checkResult.passed && !this.overrideAccepted) {
          ElementPlus.ElMessage.warning('该药品合理用药审查未通过, 请先确认「知情开具」');
          this.checkVisible = true;
          return false;
        }
        if (!this.isDrug && this.form.drugId) { this.form.drugId = null; }
        const content = String(this.form.orderContent || '').trim();
        if (!content && this.isDrug) { this.form.orderContent = this.composeDrugContent(); }
        if (!String(this.form.orderContent || '').trim()) {
          ElementPlus.ElMessage.warning('请填写医嘱内容(或检索选中项目后自动生成)');
          return false;
        }
        if (!this.form.quantity || Number(this.form.quantity) <= 0) {
          ElementPlus.ElMessage.warning('数量必须大于 0');
          return false;
        }
        return true;
      },
      addToPending() {
        if (!this.validateForm()) { return; }
        const f = this.form;
        const item = {
          orderType: Number(f.orderType),
          orderCategory: Number(f.orderCategory),
          orderContent: String(f.orderContent).trim(),
          drugId: this.isDrug ? f.drugId : null,
          chargeItemId: this.isDrug ? null : f.chargeItemId,
          spec: f.spec || null,
          dosage: this.isDrug ? (f.dosage || null) : null,
          dosageUnit: this.isDrug ? (f.dosageUnit || null) : null,
          usageCode: this.isDrug ? (f.usageCode || null) : null,
          freqCode: this.isDrug ? (f.freqCode || null) : null,
          quantity: Number(f.quantity) || 1,
          _typeText: ORDER_TYPE[f.orderType] || '-',
          _categoryText: ORDER_CATEGORY[f.orderCategory] || '-',
          _price: this.isDrug
            ? Number(this.pickedDrug && this.pickedDrug.retailPrice) || 0
            : Number(this.pickedCharge && (this.pickedCharge.execPrice != null ? this.pickedCharge.execPrice : this.pickedCharge.price)) || 0,
          _doseText: this.isDrug
            ? [f.dosage ? f.dosage + (f.dosageUnit || '') : '', this.usageMap[f.usageCode] || '', this.freqMap[f.freqCode] || ''].filter(Boolean).join(' ')
            : ''
        };
        this.pendingItems.push(item);
        /* 保留类型/分类, 清空具体条目字段以便连续录入 */
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.form.drugId = null;
        this.form.chargeItemId = null;
        this.form.orderContent = '';
        this.form.spec = '';
        this.form.dosage = '';
        this.form.usageCode = null;
        this.form.freqCode = null;
        this.form.quantity = 1;
        this.contentTouched = false;
      },
      removePending(index) { this.pendingItems.splice(index, 1); },
      buildDto(item) {
        return {
          inpVisitId: HIS.id(this.visitId),
          orderType: item.orderType,
          orderCategory: item.orderCategory,
          orderContent: item.orderContent,
          drugId: HIS.id(item.drugId) || null,
          chargeItemId: HIS.id(item.chargeItemId) || null,
          spec: item.spec || null,
          dosage: item.dosage || null,
          dosageUnit: item.dosageUnit || null,
          usageCode: item.usageCode || null,
          freqCode: item.freqCode || null,
          quantity: item.quantity
        };
      },
      submitOrders() {
        const vm = this;
        if (vm.submitting) { return; }
        if (!vm.pendingItems.length) {
          if (!vm.validateForm()) { return; }
          const f = vm.form;
          const single = {
            orderType: Number(f.orderType),
            orderCategory: Number(f.orderCategory),
            orderContent: String(f.orderContent).trim(),
            drugId: vm.isDrug ? f.drugId : null,
            chargeItemId: vm.isDrug ? null : f.chargeItemId,
            spec: f.spec || null,
            dosage: vm.isDrug ? (f.dosage || null) : null,
            dosageUnit: vm.isDrug ? (f.dosageUnit || null) : null,
            usageCode: vm.isDrug ? (f.usageCode || null) : null,
            freqCode: vm.isDrug ? (f.freqCode || null) : null,
            quantity: Number(f.quantity) || 1
          };
          vm.submitting = true;
          HIS.post('/api/his/inp/order', vm.buildDto(single)).then(function (order) {
            HIS.notifySuccess('医嘱已开立: ' + ((order && order.orderContent) || '成功')
              + (order && order.insuranceCategory ? '(' + order.insuranceCategory + ')' : ''));
            /* 医保预审提示(T42, 非阻断): 无医保编码项目将全额自费 */
            if (order && order.insuranceWarning) { vm.showInsuranceTip(order.insuranceWarning); }
            vm.dialogVisible = false;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
          return;
        }
        const items = vm.pendingItems.map(it => vm.buildDto(it));
        vm.submitting = true;
        HIS.post('/api/his/inp/order/batch', items).then(function (list) {
          HIS.notifySuccess('成组医嘱已开立 ' + ((list && list.length) || items.length) + ' 条');
          vm.showBatchInsuranceTip(list);
          vm.dialogVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      /* ===== 停止 / 作废 ===== */
      canStop(row) { return Number(row.orderStatus) === 2 || Number(row.orderStatus) === 3; },
      canCancel(row) { return Number(row.orderStatus) === 1; },
      /* ===== 续开(T42) / 医保预审提示 ===== */
      canRenew(row) { return Number(row.orderStatus) === 5 && Number(row.orderType) === 1; },
      doRenew(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认续开医嘱「' + row.orderContent + '」？将按原医嘱要素(药品/剂量/用法/频次)重新开立长期医嘱, 新医嘱自原停嘱次日开始。',
          '续开医嘱确认', { type: 'info', confirmButtonText: '续开', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.post('/api/his/inp/order/renew/' + HIS.idParam(row.id));
        }).then(function (order) {
          HIS.notifySuccess('续开成功');
          if (order && order.insuranceWarning) { vm.showInsuranceTip(order.insuranceWarning); }
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* 医保预审提示条: 列表上方橙色告警, 3 秒自动消失或手动关闭 */
      showInsuranceTip(msg) {
        const vm = this;
        vm.insTip = msg;
        if (vm._insTimer) { clearTimeout(vm._insTimer); }
        vm._insTimer = setTimeout(function () { vm.insTip = null; vm._insTimer = null; }, 3000);
      },
      clearInsTip() {
        this.insTip = null;
        if (this._insTimer) { clearTimeout(this._insTimer); this._insTimer = null; }
      },
      /* 批量回执中的医保预警汇总(单条展示原文, 多条按计数) */
      showBatchInsuranceTip(list) {
        const warns = (list || []).filter(function (o) { return o && o.insuranceWarning; });
        if (!warns.length) { return; }
        this.showInsuranceTip(warns.length === 1
          ? warns[0].insuranceWarning
          : '其中 ' + warns.length + ' 条医嘱项目无医保编码, 将全额自费');
      },
      doStop(row) {
        const vm = this;
        const groupTip = row.groupNo ? '该医嘱属于成组医嘱(' + row._groupLabel + '), 同组在执行/已审核成员将一并停止。' : '';
        ElementPlus.ElMessageBox.confirm('确认停止医嘱「' + row.orderContent + '」？' + groupTip, '停止医嘱确认', {
          type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/order/' + HIS.idParam(row.id) + '/stop');
        }).then(function () {
          HIS.notifySuccess('医嘱已停止');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      doCancel(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废医嘱「' + row.orderContent + '」？作废后已记账的临时医嘱费用将同步冲销。', '作废医嘱确认', {
          type: 'warning', confirmButtonText: '作废', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/order/' + HIS.idParam(row.id) + '/cancel');
        }).then(function () {
          HIS.notifySuccess('医嘱已作废');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 模板医嘱 / 医嘱套餐 ===== */
      openTemplates(mode) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.tplMode = mode;
        this.tplKeyword = '';
        this.tplTypeFilter = null;
        this.tplPage = 1;
        this.tplVisible = true;
        this.loadTemplates();
      },
      loadTemplates(page) {
        const vm = this;
        if (page) { vm.tplPage = page; }
        vm.tplLoading = true;
        let url = '/api/his/inp/order-template/list?page=' + vm.tplPage + '&size=' + vm.tplSize
          + '&scopeType=' + (vm.tplMode === 2 ? 2 : 1)
          + '&status=1'; // 仅启用模板可被引用(后端开嘱仍会兜底拦截停用模板)
        if (vm.tplKeyword) { url += '&keyword=' + encodeURIComponent(vm.tplKeyword); }
        if (vm.tplTypeFilter) { url += '&templateType=' + vm.tplTypeFilter; }
        HIS.get(url).then(function (d) {
          vm.tplRows = (d && d.records) || [];
          vm.tplTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.tplLoading = false; });
      },
      tplTypeText(t) { return t === 1 ? '个人' : (t === 2 ? '科室' : (t === 3 ? '全院' : '-')); },
      tplItemCount(items) {
        if (!items) { return 0; }
        try {
          const arr = typeof items === 'string' ? JSON.parse(items) : items;
          return Array.isArray(arr) ? arr.length : 0;
        } catch (e) { return 0; }
      },
      applyTemplate(row) {
        const vm = this;
        if (vm.tplApplying) { return; }
        ElementPlus.ElMessageBox.confirm('确认按模板「' + row.templateName + '」为当前患者开立医嘱? 开立后可在列表中调整/停止。', vm.tplMode === 2 ? '套餐开嘱确认' : '模板开嘱确认', {
          type: 'info', confirmButtonText: '开立', cancelButtonText: '取消'
        }).then(function () {
          vm.tplApplying = true;
          return HIS.post('/api/his/inp/order/from-template', { visitId: HIS.id(vm.visitId), templateId: HIS.id(row.id) });
        }).then(function (list) {
          HIS.notifySuccess('已开立 ' + ((list && list.length) || 0) + ' 条医嘱');
          vm.showBatchInsuranceTip(list);
          vm.tplVisible = false;
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.tplApplying = false; });
      },
      /* ===== 合理用药审查 ===== */
      rationalCheck() {
        const vm = this;
        if (!vm.visitId || !vm.form.drugId) { return; }
        vm.checkLoading = true;
        HIS.post('/api/his/inp/order/rational-check', {
          visitId: HIS.id(vm.visitId), drugId: HIS.id(vm.form.drugId), dosage: vm.form.dosage || null
        }).then(function (d) {
          vm.checkResult = d || null;
          const warn = d && d.warnings && d.warnings.length;
          if (d && (!d.passed || warn || d.highAlert || d.doubleCheck)) { vm.checkVisible = true; }
        }).catch(function (e) {
          /* 审查服务异常不阻断开嘱(本地留痕即可) */
          console.warn('[合理用药审查]', (e && e.message) || e);
          vm.checkResult = null;
        }).finally(function () { vm.checkLoading = false; });
      },
      /* 知情开具: 审查未通过时人工覆盖(责任自负) */
      overrideOk() {
        this.overrideAccepted = true;
        this.checkVisible = false;
        ElementPlus.ElMessage.warning('已标记「知情开具」, 请在提交前再次核对');
      },
      /* 取消选药(从审查对话框退出) */
      cancelPick() {
        this.pickedDrug = null;
        this.form.drugId = null;
        this.checkResult = null;
        this.overrideAccepted = false;
        this.checkVisible = false;
      },
      /* ===== 复制开立: 预填开立对话框(内容视为已编辑, 字典检索不覆盖) ===== */
      copyOrder(row) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.form = {
          orderType: Number(row.orderType) || 1,
          orderCategory: Number(row.orderCategory) || 1,
          orderContent: row.orderContent || '',
          drugId: row.drugId || null,
          chargeItemId: row.chargeItemId || null,
          spec: row.spec || '',
          dosage: row.dosage || '',
          dosageUnit: row.dosageUnit || '',
          usageCode: row.usageCode || null,
          freqCode: row.freqCode || null,
          quantity: row.quantity != null ? Number(row.quantity) : 1
        };
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.pendingItems = [];
        this.checkResult = null;
        this.overrideAccepted = false;
        this.contentTouched = true;
        this.dialogVisible = true;
        this.loadMedDict();
        /* 复制药品医嘱: 重新走合理用药审查 */
        if (this.form.orderCategory === 1 && this.form.drugId) { this.rationalCheck(); }
      },
      /* 执行详情: 医嘱级状态与时间痕迹(执行明细由护士站执行环节产生) */
      showExec(row) {
        this.execRow = row;
        this.execVisible = true;
      },
      onMoreCmd(row, cmd) {
        if (cmd === 'copy') { this.copyOrder(row); }
        else if (cmd === 'renew') { if (this.canRenew(row)) { this.doRenew(row); } }
        else if (cmd === 'exec') { this.showExec(row); }
      },
      /* ---- 行右键上下文菜单 ---- */
      onRowCtx(row, column, event) {
        if (event) {
          event.preventDefault();
          event.stopPropagation();
        }
        this.ctx = { visible: true, x: event ? event.clientX : 0, y: event ? event.clientY : 0, row: row };
      },
      closeCtx() {
        if (this.ctx.visible) { this.ctx.visible = false; }
      },
      ctxCmd(cmd) {
        const row = this.ctx.row;
        this.closeCtx();
        if (!row) { return; }
        if (cmd === 'copy') { this.copyOrder(row); }
        else if (cmd === 'renew') { if (this.canRenew(row)) { this.doRenew(row); } }
        else if (cmd === 'exec') { this.showExec(row); }
        else if (cmd === 'stop') { if (this.canStop(row)) { this.doStop(row); } }
        else if (cmd === 'cancel') { if (this.canCancel(row)) { this.doCancel(row); } }
      }
    },
    mounted() {
      /* 点击任意处 / 在别处右键 自动关闭行右键菜单 */
      this._onDocClick = this.closeCtx.bind(this);
      this._onDocCtxMenu = this.closeCtx.bind(this);
      document.addEventListener('click', this._onDocClick);
      document.addEventListener('contextmenu', this._onDocCtxMenu);
    },
    beforeUnmount() {
      if (this._onDocClick) { document.removeEventListener('click', this._onDocClick); this._onDocClick = null; }
      if (this._onDocCtxMenu) { document.removeEventListener('contextmenu', this._onDocCtxMenu); this._onDocCtxMenu = null; }
      if (this._insTimer) { clearTimeout(this._insTimer); this._insTimer = null; }
    },
    template: `
      <div class="iw-panel-body iw-panel-body--flush">
        <div class="iw-toolbar">
          <el-radio-group v-model="filterType" size="small" @change="onFilterChange">
            <el-radio-button :label="0">全部</el-radio-button>
            <el-radio-button :label="1">长期</el-radio-button>
            <el-radio-button :label="2">临时</el-radio-button>
          </el-radio-group>
          <el-select v-model="filterStatus" placeholder="全部状态" size="small" clearable style="width:130px" @change="onFilterChange">
            <el-option v-for="(label, key) in statuses" :key="key" :label="label" :value="Number(key)"></el-option>
          </el-select>
          <span class="iw-toolbar-info">共 <b>{{ total }}</b> 条</span>
          <span class="iw-toolbar-right">
            <el-button size="small" @click="openTemplates(1)">模板医嘱</el-button>
            <el-button size="small" @click="openTemplates(2)">医嘱套餐</el-button>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button size="small" type="primary" @click="openDialog">开立医嘱</el-button>
          </span>
        </div>

        <!-- 医保预审提示(T42, 非阻断): 开立/续开回执的无医保编码预警, 3秒自动消失 -->
        <el-alert v-if="insTip" type="warning" :title="insTip" show-icon closable style="margin:0 0 6px" @close="clearInsTip"></el-alert>

        <div class="iw-table-scroll" v-loading="loading">
          <el-table :data="orders" height="100%" size="small" border :row-class-name="rowClass" :row-key="row => row.id"
                    @row-contextmenu="onRowCtx">
            <el-table-column label="组" width="50" align="center">
              <template #default="s">
                <span v-if="s.row._groupFirst" class="iw-grp">{{ s.row._groupLabel }}</span>
                <span v-else-if="s.row.groupNo" class="iw-grp-child">↳</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="62" align="center">
              <template #default="s">
                <span class="iw-tag" :class="Number(s.row.orderType) === 1 ? 'iw-tag--long' : 'iw-tag--temp'">{{ orderTypeText(s.row.orderType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="62" align="center">
              <template #default="s">{{ categoryText(s.row.orderCategory) }}</template>
            </el-table-column>
            <el-table-column prop="orderContent" label="医嘱内容" min-width="230" show-overflow-tooltip></el-table-column>
            <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip>
              <template #default="s">{{ s.row.spec || '-' }}</template>
            </el-table-column>
            <el-table-column label="剂量" width="86">
              <template #default="s">{{ s.row.dosage ? (s.row.dosage + (s.row.dosageUnit || '')) : '-' }}</template>
            </el-table-column>
            <el-table-column label="用法" width="80" show-overflow-tooltip>
              <template #default="s">{{ s.row.usageCode ? usageText(s.row.usageCode) : '-' }}</template>
            </el-table-column>
            <el-table-column label="频次" width="86" show-overflow-tooltip>
              <template #default="s">{{ s.row.freqCode ? freqText(s.row.freqCode) : '-' }}</template>
            </el-table-column>
            <el-table-column label="数量" width="62" align="right">
              <template #default="s">{{ s.row.quantity != null ? s.row.quantity : '-' }}</template>
            </el-table-column>
            <el-table-column label="单价" width="82" align="right">
              <template #default="s"><span class="iw-money">{{ s.row.unitPrice != null ? money(s.row.unitPrice) : '-' }}</span></template>
            </el-table-column>
            <el-table-column label="开始时间" width="118">
              <template #default="s"><span :title="s.row.startTime">{{ timeText(s.row.startTime) }}</span></template>
            </el-table-column>
            <el-table-column label="停止时间" width="118">
              <template #default="s">{{ s.row.stopTime ? timeText(s.row.stopTime) : '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="76" align="center">
              <template #default="s">
                <el-tag size="small" :type="statusTag(s.row.orderStatus)" disable-transitions>{{ statusText(s.row.orderStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="药审" width="64" align="center">
              <template #default="s">
                <el-tooltip v-if="Number(s.row.pharmAuditStatus) === 3"
                            :content="'药审驳回: ' + (s.row.pharmRejectReason || '未填写原因') + ' — 可右键复制开立修改后重新提交'"
                            placement="top" :show-after="200">
                  <el-tag size="small" type="danger" disable-transitions>驳回</el-tag>
                </el-tooltip>
                <el-tag v-else-if="Number(s.row.pharmAuditStatus) === 1" size="small" type="warning" disable-transitions>待审</el-tag>
                <el-tag v-else-if="Number(s.row.pharmAuditStatus) === 2" size="small" type="success" disable-transitions>通过</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="134" fixed="right" align="center">
              <template #default="s">
                <span class="iw-ops">
                  <el-tooltip v-if="canRenew(s.row)" content="续开医嘱(按原医嘱要素重新开立长期医嘱)" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-success" @click.stop="doRenew(s.row)" v-html="icons.renew"></button>
                  </el-tooltip>
                  <el-tooltip content="停止" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-danger" :disabled="!canStop(s.row)" @click.stop="doStop(s.row)" v-html="icons.pause"></button>
                  </el-tooltip>
                  <el-tooltip content="作废" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-danger" :disabled="!canCancel(s.row)" @click.stop="doCancel(s.row)" v-html="icons.ban"></button>
                  </el-tooltip>
                  <el-tooltip content="更多操作" placement="top" :show-after="200">
                    <el-dropdown trigger="click" @command="cmd => onMoreCmd(s.row, cmd)">
                      <button type="button" class="iw-icobtn" v-html="icons.more"></button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="copy">复制开立</el-dropdown-item>
                          <el-dropdown-item v-if="canRenew(s.row)" command="renew" divided>续开医嘱</el-dropdown-item>
                          <el-dropdown-item command="exec">查看执行详情</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </el-tooltip>
                </span>
              </template>
            </el-table-column>
            <template #empty><div class="iw-empty-line">暂无医嘱, 点击右上「开立医嘱」开始录入</div></template>
          </el-table>
        </div>

        <div class="iw-pager" v-if="total > size">
          <el-pagination small background layout="prev, pager, next" :total="total" :page-size="size"
                         :current-page="page" @current-change="onPageChange"></el-pagination>
        </div>

        <!-- 开立医嘱对话框 -->
        <el-dialog v-model="dialogVisible" title="开立医嘱" width="780px" top="5vh" :close-on-click-modal="false">
          <div class="iw-form">
            <div class="iw-form-row">
              <span class="lb">医嘱类型</span>
              <el-radio-group v-model="form.orderType" size="small">
                <el-radio-button :label="1">长期医嘱</el-radio-button>
                <el-radio-button :label="2">临时医嘱</el-radio-button>
              </el-radio-group>
              <span class="iw-dim" style="margin-left:8px">{{ Number(form.orderType) === 2 ? '临时医嘱开立即记账计入住院费用' : '长期医嘱由执行环节逐日生成费用' }}</span>
            </div>
            <div class="iw-form-row">
              <span class="lb">医嘱分类</span>
              <el-radio-group v-model="form.orderCategory" size="small" @change="onCategoryChange">
                <el-radio-button v-for="(label, key) in categories" :key="key" :label="Number(key)">{{ label }}</el-radio-button>
              </el-radio-group>
            </div>

            <div class="iw-form-row" v-if="isDrug">
              <span class="lb">药品检索</span>
              <el-select class="iw-grow" v-model="pickDrugId" size="small" filterable remote reserve-keyword clearable
                         :remote-method="remoteDrugSearch" :loading="drugSearching" placeholder="通用名 / 编码 / 拼音简码, 选中即带出规格与单价" @change="onPickDrug">
                <el-option v-for="d in drugOptions" :key="d.id" :label="(d.genericName || '') + ' ' + (d.spec || '')" :value="d.id">
                  <div class="iw-opt">
                    <span class="nm">{{ d.genericName }}</span>
                    <span class="sub">{{ d.spec }}</span>
                    <span class="price">¥{{ money(d.retailPrice) }}</span>
                    <span class="iw-tag iw-tag--plain" v-if="!d.ybDrugCode">自费</span>
                  </div>
                </el-option>
              </el-select>
            </div>
            <div class="iw-form-row" v-else>
              <span class="lb">项目检索</span>
              <el-select class="iw-grow" v-model="pickChargeId" size="small" filterable remote reserve-keyword clearable
                         :remote-method="remoteChargeSearch" :loading="chargeSearching" placeholder="收费项目名称 / 编码, 选中带出执行价(也可留空开纯文字医嘱)" @change="onPickCharge">
                <el-option v-for="c in chargeOptions" :key="c.id" :label="(c.itemName || '') + ' ' + (c.spec || '')" :value="c.id">
                  <div class="iw-opt">
                    <span class="nm">{{ c.itemName }}</span>
                    <span class="sub">{{ c.itemCode }}</span>
                    <span class="sub">{{ c.spec }}</span>
                    <span class="price">¥{{ money(c.execPrice != null ? c.execPrice : c.price) }}</span>
                  </div>
                </el-option>
              </el-select>
            </div>

            <div class="iw-picked" v-if="isDrug && pickedDrug">
              <span>规格 <b>{{ pickedDrug.spec || '-' }}</b></span>
              <span>零售价 <b class="iw-money">{{ money(pickedDrug.retailPrice) }}</b> 元/{{ pickedDrug.minUnit || '单位' }}</span>
              <span>剂型 {{ pickedDrug.dosformName || pickedDrug.dosform || '-' }}</span>
              <span class="iw-tag iw-tag--plain" v-if="pickedDrug.chrgitmLvName">{{ pickedDrug.chrgitmLvName }}</span>
              <span class="iw-tag iw-tag--plain" v-else-if="!pickedDrug.ybDrugCode">自费</span>
              <span v-if="checkLoading" class="iw-dim">合理用药审查中...</span>
              <span v-if="checkResult && checkResult.highAlert" class="iw-high-alert" title="高警示药品: 开立/调配/执行需严格核对">⚠</span>
              <span v-if="checkResult && checkResult.doubleCheck" class="iw-tag iw-tag--plain" style="color:var(--yb-warning-strong);border-color:var(--yb-warning-border)" title="需双人核对">双人核对</span>
            </div>
            <div class="iw-picked" v-if="!isDrug && pickedCharge">
              <span>编码 <b>{{ pickedCharge.itemCode || '-' }}</b></span>
              <span>执行价 <b class="iw-money">{{ money(pickedCharge.execPrice != null ? pickedCharge.execPrice : pickedCharge.price) }}</b> 元/{{ pickedCharge.unit || '次' }}</span>
              <span v-if="pickedCharge.itemContent" class="clip">{{ pickedCharge.itemContent }}</span>
            </div>

            <div class="iw-form-row" v-if="isDrug">
              <span class="lb">剂量</span>
              <el-input v-model="form.dosage" size="small" style="width:110px" placeholder="如 0.5"></el-input>
              <el-input v-model="form.dosageUnit" size="small" style="width:90px" placeholder="单位"></el-input>
              <span class="lb">用法</span>
              <el-select v-model="form.usageCode" size="small" clearable filterable style="width:130px" placeholder="用法">
                <el-option v-for="u in usageOptions" :key="u.code" :label="u.name" :value="u.code"></el-option>
              </el-select>
              <span class="lb">频次</span>
              <el-select v-model="form.freqCode" size="small" clearable filterable style="width:130px" placeholder="频次">
                <el-option v-for="f in freqOptions" :key="f.code" :label="f.name" :value="f.code"></el-option>
              </el-select>
            </div>

            <div class="iw-form-row">
              <span class="lb">医嘱内容</span>
              <el-input class="iw-grow" v-model="form.orderContent" size="small" placeholder="自动生成或手动输入" @input="onContentInput">
                <template #append><el-button @click="autoCompose">自动生成</el-button></template>
              </el-input>
            </div>
            <div class="iw-form-row">
              <span class="lb">数量</span>
              <el-input-number v-model="form.quantity" size="small" :min="0.01" :step="1" :precision="2" style="width:130px"></el-input-number>
              <span class="iw-dim">药品类数量按最小发药单位计; 单价由服务端按本机构目录定价</span>
            </div>
          </div>

          <div class="iw-sect-title" style="margin-top:12px">
            医嘱清单
            <span class="iw-count" v-if="pendingItems.length">{{ pendingItems.length }}</span>
            <span class="iw-dim" style="font-weight:400;margin-left:8px">多条一并提交将自动成组(同组联动停止/作废)</span>
            <span class="iw-toolbar-right" v-if="pendingItems.length">预估金额 <b class="iw-money" style="margin-left:4px">{{ money(pendingTotal) }}</b> 元</span>
          </div>
          <el-table v-if="pendingItems.length" :data="pendingItems" size="small" border max-height="200">
            <el-table-column label="类型" width="58" align="center">
              <template #default="s">{{ s.row._typeText }}</template>
            </el-table-column>
            <el-table-column label="分类" width="58" align="center">
              <template #default="s">{{ s.row._categoryText }}</template>
            </el-table-column>
            <el-table-column prop="orderContent" label="内容" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="剂量用法" width="150" show-overflow-tooltip>
              <template #default="s">{{ s.row._doseText || '-' }}</template>
            </el-table-column>
            <el-table-column label="数量" width="60" align="right">
              <template #default="s">{{ s.row.quantity }}</template>
            </el-table-column>
            <el-table-column label="单价" width="76" align="right">
              <template #default="s">{{ s.row._price ? money(s.row._price) : '服务端定价' }}</template>
            </el-table-column>
            <el-table-column label="" width="52" align="center">
              <template #default="s"><el-button link type="danger" size="small" @click="removePending(s.$index)">删</el-button></template>
            </el-table-column>
          </el-table>
          <div v-else class="iw-empty-line" style="border:1px dashed var(--yb-border);border-radius:4px;padding:10px;text-align:center">
            暂未添加医嘱条目, 填写上方信息后点击「加入清单」; 或直接点击「提交医嘱」开立单条
          </div>

          <template #footer>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button @click="addToPending">加入清单</el-button>
            <el-button type="primary" :loading="submitting" @click="submitOrders">
              {{ pendingItems.length ? ('提交医嘱(' + pendingItems.length + '条)') : '提交医嘱' }}
            </el-button>
          </template>
        </el-dialog>

        <!-- 模板医嘱 / 医嘱套餐 -->
        <el-dialog v-model="tplVisible" :title="tplMode === 2 ? '医嘱套餐' : '模板医嘱'" width="720px" top="6vh">
          <div class="iw-toolbar" style="margin-bottom:8px">
            <el-input v-model="tplKeyword" size="small" placeholder="模板名称 / 病种编码" clearable style="width:230px"
                      @keyup.enter="loadTemplates(1)" @clear="loadTemplates(1)"></el-input>
            <el-select v-model="tplTypeFilter" size="small" placeholder="全部范围" clearable style="width:120px" @change="loadTemplates(1)">
              <el-option :value="1" label="个人"></el-option>
              <el-option :value="2" label="科室"></el-option>
              <el-option :value="3" label="全院"></el-option>
            </el-select>
            <el-button size="small" type="primary" @click="loadTemplates(1)">搜索</el-button>
            <span class="iw-toolbar-info">共 <b>{{ tplTotal }}</b> 个</span>
          </div>
          <el-table :data="tplRows" v-loading="tplLoading" size="small" border max-height="380">
            <el-table-column prop="templateName" label="模板名称" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="范围" width="76" align="center">
              <template #default="s">
                <el-tag size="small" :type="s.row.templateType === 1 ? 'success' : (s.row.templateType === 2 ? 'warning' : 'info')" disable-transitions>{{ tplTypeText(s.row.templateType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="条目数" width="70" align="center">
              <template #default="s">{{ tplItemCount(s.row.items) }}</template>
            </el-table-column>
            <el-table-column label="病种" width="96">
              <template #default="s">{{ s.row.diseaseCode || '-' }}</template>
            </el-table-column>
            <el-table-column label="使用次数" width="80" align="center">
              <template #default="s">{{ s.row.usageCount || 0 }}</template>
            </el-table-column>
            <el-table-column label="操作" width="96" align="center">
              <template #default="s">
                <el-button link type="primary" size="small" :disabled="tplApplying" @click="applyTemplate(s.row)">应用开嘱</el-button>
              </template>
            </el-table-column>
            <template #empty><div class="iw-empty-line">暂无模板</div></template>
          </el-table>
          <div class="iw-pager" v-if="tplTotal > tplSize">
            <el-pagination small background layout="prev, pager, next" :total="tplTotal" :page-size="tplSize"
                           :current-page="tplPage" @current-change="loadTemplates"></el-pagination>
          </div>
        </el-dialog>

        <!-- 合理用药审查结果 -->
        <el-dialog v-model="checkVisible" title="合理用药审查" width="560px">
          <template v-if="checkResult">
            <el-alert v-if="checkResult.passed && !(checkResult.warnings && checkResult.warnings.length)"
                      type="success" :closable="false" title="审查通过"></el-alert>
            <div v-if="checkResult.warnings && checkResult.warnings.length">
              <div class="iw-sect-title" style="margin-bottom:6px">风险提示</div>
              <div v-for="(w, i) in checkResult.warnings" :key="i" class="iw-warn-item danger">⚠ {{ w }}</div>
            </div>
            <div v-if="checkResult.highAlert" class="iw-warn-item danger">⚠ 高警示药品: 开立/调配/执行需严格双人核对</div>
            <div v-if="checkResult.doubleCheck" class="iw-warn-item">此药品需双人核对: 执行环节请由两名护士核对签字</div>
            <div v-if="!checkResult.passed" style="color:var(--yb-danger);font-size:13px;margin-top:8px">
              审查未通过(过敏冲突或重复用药)。如确需开立, 请点击「知情开具」, 开立人承担相应责任。
            </div>
          </template>
          <template #footer>
            <el-button @click="cancelPick">取消选药</el-button>
            <el-button v-if="checkResult && !checkResult.passed" type="warning" @click="overrideOk">知情开具</el-button>
            <el-button v-else type="primary" @click="checkVisible = false">继续开立</el-button>
          </template>
        </el-dialog>

        <!-- 医嘱行右键菜单 -->
        <div class="iw-ctx" v-if="ctx.visible" :style="ctxStyle" @contextmenu.prevent>
          <div class="iw-ctx-item" @click="ctxCmd('copy')"><span v-html="icons.copy"></span>复制开立</div>
          <div class="iw-ctx-item" v-if="canRenew(ctx.row)" @click="ctxCmd('renew')"><span v-html="icons.renew"></span>续开医嘱</div>
          <div class="iw-ctx-item" @click="ctxCmd('exec')"><span v-html="icons.exec"></span>查看执行详情</div>
          <div class="iw-ctx-sep"></div>
          <div class="iw-ctx-item" :class="{ 'is-disabled': !canStop(ctx.row) }" @click="ctxCmd('stop')">停止医嘱</div>
          <div class="iw-ctx-item" :class="{ 'is-disabled': !canCancel(ctx.row) }" @click="ctxCmd('cancel')">作废医嘱</div>
        </div>

        <!-- 执行详情 -->
        <el-dialog v-model="execVisible" title="医嘱执行详情" width="580px">
          <template v-if="execRow">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="医嘱内容" :span="2">{{ execRow.orderContent }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ orderTypeText(execRow.orderType) }}</el-descriptions-item>
              <el-descriptions-item label="分类">{{ categoryText(execRow.orderCategory) }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag size="small" :type="statusTag(execRow.orderStatus)" disable-transitions>{{ statusText(execRow.orderStatus) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="数量">{{ execRow.quantity != null ? execRow.quantity : '-' }}</el-descriptions-item>
              <el-descriptions-item label="剂量">{{ execRow.dosage ? (execRow.dosage + (execRow.dosageUnit || '')) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="用法 / 频次">{{ [execRow.usageCode ? usageText(execRow.usageCode) : '', execRow.freqCode ? freqText(execRow.freqCode) : ''].filter(Boolean).join(' / ') || '-' }}</el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ timeText(execRow.startTime) }}</el-descriptions-item>
              <el-descriptions-item label="停止时间">{{ execRow.stopTime ? timeText(execRow.stopTime) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="成组医嘱" :span="2">{{ execRow.groupNo ? ((execRow._groupLabel || '成组') + ' (' + execRow.groupNo + ')') : '非成组医嘱' }}</el-descriptions-item>
            </el-descriptions>
            <div class="iw-hint">执行明细(执行时间 / 执行人 / 双人核对)由护士站执行环节产生, 完整记录见护士站「医嘱执行」。</div>
          </template>
          <template #footer>
            <el-button type="primary" @click="execVisible = false">关闭</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpOrderPanel = InpOrderPanel;
})();
