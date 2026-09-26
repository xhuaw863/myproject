/* 医生工作站 - 检查/检验/治疗申请单面板 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  var SPECIMEN_TYPES = ['全血', '血清', '血浆', '尿液', '粪便', '分泌物', '痰液', '体液', '骨髓', '其他'];
  var SPECIMEN_CONDITIONS = ['空腹', '随机', '餐后2小时', '晨尿', '24小时尿', '中段尿', '清晨', '定时', '其他'];
  var URGENCY_LAB = ['常规', '急诊', '危急'];
  var URGENCY_EXAM = ['常规', '急诊'];
  var EXAM_NOTES = ['药物过敏史', '碘过敏', '妊娠或可能妊娠', '金属植入物', '心脏起搏器', '幽闭恐惧症', '肾功能不全'];
  var EXEC_DEPTS = ['放射科', '超声科', 'CT室', 'MRI室', '心电图室', '内镜室'];
  var TREAT_DEPTS = ['治疗室', '康复医学科', '理疗科', '针灸科', '输液室', '换药室'];
  var MUTUAL_REASONS = ['病情变化', '结果变化快', '重大措施前', '急诊', '鉴定', '其他'];
  var CRITICAL_VALUES = {
    '血糖': { low: 2.8, high: 22.2, unit: 'mmol/L' },
    '血钾': { low: 2.5, high: 6.5, unit: 'mmol/L' },
    '血钠': { low: 120, high: 160, unit: 'mmol/L' },
    '血红蛋白': { low: 40, high: 200, unit: 'g/L' },
    '白细胞': { low: 1.0, high: 30.0, unit: '×10⁹/L' },
    '血小板': { low: 30, high: 1000, unit: '×10⁹/L' },
    '肌酐': { high: 530, unit: 'μmol/L' },
    '血钙': { low: 1.5, high: 3.5, unit: 'mmol/L' }
  };
  var COMMON_PACKAGES = [
    { name: '入院常规', items: ['血常规', '尿常规', '肝功能', '肾功能', '血糖', '心电图', '胸部X线'] },
    { name: '术前检查', items: ['血常规', '凝血功能', '肝功能', '肾功能', '血糖', '血型', '传染病筛查', '心电图', '胸片'] },
    { name: '糖尿病随访', items: ['空腹血糖', '糖化血红蛋白', '肝功能', '肾功能', '尿常规', '尿微量白蛋白'] },
    { name: '高血压随访', items: ['血常规', '肝功能', '肾功能', '血脂', '血糖', '心电图'] }
  ];

  function raw(v) { return v && Object.prototype.hasOwnProperty.call(v, 'value') ? v.value : v; }
  function money(v) { var n = Number(v); return isFinite(n) ? n.toFixed(2) : '0.00'; }
  function textDate(v) { return v ? String(v).slice(0, 10) : ''; }
  function makeBucket() { return { keyword: '', results: [], loading: false, items: [] }; }
  function labForm() { return { specimenType: '', specimenCondition: '', collectionSite: '', urgency: '常规', medicationInfo: '', inspectionPurpose: '' }; }
  function examForm() { return { examPart: '', examPurpose: '', briefHistory: '', contrastMode: '', urgency: '常规', examNotes: [], execDept: '' }; }
  function treatmentForm() { return { treatmentPart: '', treatmentTimes: 1, execDept: '' }; }

  var DwOrderPanel = {
    name: 'DwOrderPanel',
    inject: ['currentVisit', 'currentPatient', 'expandedPanel', 'diagnoses', 'visitHistory'],
    emits: ['order-saved', 'request-expand', 'request-collapse', 'print-order'],
    data: function () {
      return {
        orderType: '检查',
        orderTypes: ['检查', '检验', '治疗'],
        buckets: { '检查': makeBucket(), '检验': makeBucket(), '治疗': makeBucket() },
        forms: { '检查': examForm(), '检验': labForm(), '治疗': treatmentForm() },
        specimenTypes: SPECIMEN_TYPES,
        specimenConditions: SPECIMEN_CONDITIONS,
        urgencyLab: URGENCY_LAB,
        urgencyExam: URGENCY_EXAM,
        examNotes: EXAM_NOTES,
        execDepts: EXEC_DEPTS,
        treatDepts: TREAT_DEPTS,
        commonPackages: COMMON_PACKAGES.slice(),
        backendPackages: [],
        packageLoading: false,
        orders: [],
        reports: [],
        loadingOrders: false,
        saving: false,
        reportVisible: false,
        reportDetail: null,
        mutualVisible: false,
        mutualHit: null,
        mutualItem: null,
        mutualReason: '',
        mutualResolver: null
      };
    },
    computed: {
      visit: function () { return raw(this.currentVisit) || null; },
      patient: function () { return raw(this.currentPatient) || {}; },
      diagList: function () { return raw(this.diagnoses) || []; },
      historyList: function () { return raw(this.visitHistory) || []; },
      visitId: function () { return this.visit && (this.visit.id || this.visit.visitId); },
      patientId: function () { return (this.patient && this.patient.id) || (this.visit && this.visit.patientId); },
      isExpanded: function () { var v = raw(this.expandedPanel); return v === 'order' || v === 'orders'; },
      canEdit: function () { return !!this.visit && Number(this.visit.visitStatus) === 2; },
      activeBucket: function () { return this.buckets[this.orderType]; },
      activeForm: function () { return this.forms[this.orderType]; },
      diagnosisText: function () {
        return this.diagList.map(function (d) { return d.diagName || d.name; }).filter(Boolean).join('；');
      },
      doctorName: function () {
        var u = typeof HIS.getUser === 'function' ? (HIS.getUser() || {}) : {};
        return (this.visit && (this.visit.drName || this.visit.doctorName)) || u.realName || u.username || '';
      },
      orderTotal: function () {
        return this.activeBucket.items.reduce(function (sum, item) {
          return sum + (Number(item.price) || 0) * (Number(item.quantity) || 0);
        }, 0);
      },
      visibleOrders: function () {
        var type = this.orderType;
        return this.orders.filter(function (o) { return o.orderType === type; });
      },
      allPackages: function () {
        var names = {};
        return this.commonPackages.concat(this.backendPackages).filter(function (p) {
          if (!p || !p.name || names[p.name]) { return false; }
          names[p.name] = true; return true;
        });
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler: function (id, oldId) {
          if (id === oldId && oldId !== undefined) { return; }
          this.resetDrafts();
          this.orders = []; this.reports = [];
          if (id) { this.loadOrders(); this.loadReports(); }
        }
      }
    },
    created: function () { this.loadPackages(); },
    beforeUnmount: function () {
      if (this.mutualResolver) { this.mutualResolver(false); this.mutualResolver = null; }
    },
    methods: {
      money: money,
      resetDrafts: function () {
        this.buckets = { '检查': makeBucket(), '检验': makeBucket(), '治疗': makeBucket() };
        this.forms = { '检查': examForm(), '检验': labForm(), '治疗': treatmentForm() };
      },
      switchType: function (type) { this.orderType = type; },
      toggleExpand: function () { this.$emit(this.isExpanded ? 'request-collapse' : 'request-expand', 'order'); },
      loadOrders: function () {
        var vm = this;
        if (!vm.visitId) { return Promise.resolve([]); }
        vm.loadingOrders = true;
        return HIS.get('/api/his/order/list?visitId=' + encodeURIComponent(vm.visitId)).then(function (d) {
          vm.orders = Array.isArray(d) ? d : ((d && d.records) || []);
          return vm.orders;
        }).catch(function (e) {
          vm.orders = []; if (HIS.notifyError) { HIS.notifyError(e); }
          return [];
        }).finally(function () { vm.loadingOrders = false; });
      },
      loadReports: function () {
        var vm = this;
        if (!vm.patientId) { return Promise.resolve([]); }
        return HIS.get('/api/his/order/reports?patientId=' + encodeURIComponent(vm.patientId)).then(function (d) {
          vm.reports = Array.isArray(d) ? d : [];
          return vm.reports;
        }).catch(function () { vm.reports = []; return []; });
      },
      loadPackages: function () {
        var vm = this;
        HIS.get('/api/his/template/list?type=order_set').then(function (list) {
          vm.backendPackages = (list || []).map(function (tpl) {
            var content = tpl.content;
            try { content = typeof content === 'string' ? JSON.parse(content) : content; } catch (e) { content = []; }
            var items = Array.isArray(content) ? content : ((content && content.items) || []);
            items = items.map(function (x) { return typeof x === 'string' ? x : (x.itemName || x.name || x.keyword); }).filter(Boolean);
            return { name: tpl.name, items: items };
          }).filter(function (p) { return p.items.length; });
        }).catch(function () { vm.backendPackages = []; });
      },
      catalogSearch: function (keyword) {
        var q = '/api/org-catalog/available/charge?page=1&size=20&itemType=' + encodeURIComponent('诊疗');
        if (keyword) { q += '&keyword=' + encodeURIComponent(keyword); }
        return HIS.get(q).then(function (d) { return (d && d.records) || (Array.isArray(d) ? d : []); });
      },
      searchOd: function () {
        var vm = this; var bucket = vm.activeBucket;
        bucket.loading = true;
        vm.catalogSearch(bucket.keyword).then(function (rows) { bucket.results = rows; })
          .catch(function (e) { bucket.results = []; if (HIS.notifyError) { HIS.notifyError(e); } })
          .finally(function () { bucket.loading = false; });
      },
      mapCatalogItem: function (it) {
        var px = it.execPrice != null ? it.execPrice : it.price;
        return {
          itemId: it.id, itemCode: it.itemCode, itemName: it.itemName,
          spec: it.spec || '', unit: it.unit || '', price: Number(px) || 0,
          quantity: 1, medListCodg: it.medListCodg || '', execDept: ''
        };
      },
      addOd: function (it) {
        var vm = this;
        vm.addCatalogItem(it).catch(function (e) { if (HIS.notifyError) { HIS.notifyError(e); } });
      },
      addCatalogItem: function (it) {
        var vm = this; var mapped = vm.mapCatalogItem(it);
        if (vm.activeBucket.items.some(function (x) { return x.itemId === mapped.itemId; })) {
          ElementPlus.ElMessage.info('该项目已在暂存列表中'); return Promise.resolve(false);
        }
        return vm.requestMutualDecision(mapped).then(function (decision) {
          if (!decision) { return false; }
          if (decision.reason) {
            mapped.mutualRecognition = 1;
            mapped.mutualRecognitionReason = decision.reason;
            mapped.mutualRecognitionReportDate = decision.hit.date;
          }
          vm.activeBucket.items.push(mapped);
          return true;
        });
      },
      normalizeName: function (v) { return String(v || '').replace(/[\s（）()\-]/g, '').replace(/检查|检验|测定|试验/g, ''); },
      historyReports: function () {
        var out = this.reports.slice();
        this.historyList.forEach(function (h) {
          var lists = [h.reports, h.reportList, h.orders, h.orderItems, h.items];
          lists.forEach(function (list) {
            (Array.isArray(list) ? list : []).forEach(function (r) { out.push(r); });
          });
        });
        return out;
      },
      checkMutualRecognition: function (item) {
        var vm = this; var target = vm.normalizeName(item.itemName); var now = Date.now();
        var reports = vm.historyReports();
        for (var i = 0; i < reports.length; i++) {
          var r = reports[i] || {}; var order = r.order || r;
          var date = order.reportTime || order.executeTime || order.createTime || r.reportTime || r.date;
          var time = date ? new Date(String(date).replace(' ', 'T')).getTime() : NaN;
          if (!isFinite(time) || now - time > 30 * 86400000 || time > now + 86400000) { continue; }
          var items = r.items || order.items || [r];
          for (var j = 0; j < items.length; j++) {
            var ri = items[j] || {}; var name = vm.normalizeName(ri.itemName || ri.name || order.itemName);
            if (name && target && (name.indexOf(target) >= 0 || target.indexOf(name) >= 0)) {
              return {
                date: textDate(date),
                summary: ri.resultSummary || ri.resultText || ri.result || order.resultSummary || '已有可调阅结果',
                hr: ri.hrFlag || ri.mutualRecognitionFlag || order.hrFlag || 'HR'
              };
            }
          }
        }
        return null;
      },
      requestMutualDecision: function (item) {
        var vm = this; var hit = vm.checkMutualRecognition(item);
        if (!hit || vm.orderType === '治疗') { return Promise.resolve({ reason: '', hit: null }); }
        return new Promise(function (resolve) {
          vm.mutualItem = item; vm.mutualHit = hit; vm.mutualReason = '';
          vm.mutualResolver = resolve; vm.mutualVisible = true;
        });
      },
      finishMutual: function (accepted) {
        if (accepted && !this.mutualReason) { ElementPlus.ElMessage.warning('请选择仍需开单理由'); return; }
        var resolve = this.mutualResolver;
        var result = accepted ? { reason: this.mutualReason, hit: this.mutualHit } : false;
        this.mutualResolver = null; this.mutualVisible = false;
        this.mutualItem = null; this.mutualHit = null;
        if (resolve) { resolve(result); }
      },
      onMutualClosed: function () {
        if (this.mutualResolver) { var resolve = this.mutualResolver; this.mutualResolver = null; resolve(false); }
      },
      addPackage: function (pkg) {
        var vm = this; var names = (pkg && pkg.items) || [];
        if (!names.length || vm.packageLoading) { return; }
        vm.packageLoading = true;
        var added = 0; var skipped = [];
        names.reduce(function (chain, name) {
          return chain.then(function () {
            return vm.catalogSearch(name).then(function (rows) {
              var target = vm.normalizeName(name);
              var found = rows.find(function (r) { return vm.normalizeName(r.itemName) === target; }) || rows[0];
              if (!found) { skipped.push(name); return; }
              return vm.addCatalogItem(found).then(function (ok) { if (ok) { added++; } });
            }).catch(function () { skipped.push(name); });
          });
        }, Promise.resolve()).then(function () {
          var msg = '套餐“' + pkg.name + '”已添加 ' + added + ' 项';
          if (skipped.length) { msg += '，未匹配：' + skipped.join('、'); }
          ElementPlus.ElMessage[skipped.length ? 'warning' : 'success'](msg);
        }).finally(function () { vm.packageLoading = false; });
      },
      removeItem: function (index) { this.activeBucket.items.splice(index, 1); },
      lineAmount: function (it) { return money((Number(it.price) || 0) * (Number(it.quantity) || 0)); },
      validateOrder: function () {
        if (!this.visitId) { return '请先选择患者'; }
        if (!this.canEdit) { return '仅接诊中的就诊可开立申请单'; }
        if (!this.activeBucket.items.length) { return '请先添加项目'; }
        var f = this.activeForm;
        if (this.orderType === '检验' && (!f.specimenType || !f.specimenCondition || !f.urgency)) { return '请完整填写标本类型、标本条件和紧急程度'; }
        if (this.orderType === '检查' && (!f.examPart || !f.examPurpose || !f.briefHistory || !f.contrastMode || !f.urgency || !f.execDept)) { return '请完整填写检查部位、检查目的、简要病史、造影方式、紧急程度和执行科室'; }
        if (this.orderType === '治疗' && (!f.treatmentPart || !f.treatmentTimes || !f.execDept)) { return '请完整填写治疗部位、次数和执行科室'; }
        return '';
      },
      payloadItems: function () {
        var vm = this; var f = vm.activeForm;
        return vm.activeBucket.items.map(function (base) {
          var item = Object.assign({}, base);
          item.clinicalDiagnosis = vm.diagnosisText;
          item.applyDoctor = vm.doctorName;
          if (vm.orderType === '检验') {
            Object.assign(item, {
              specimenType: f.specimenType, specimenCondition: f.specimenCondition,
              collectionSite: f.collectionSite, urgency: f.urgency,
              medicationInfo: f.medicationInfo, inspectionPurpose: f.inspectionPurpose
            });
          } else if (vm.orderType === '检查') {
            Object.assign(item, {
              examPart: f.examPart, examPurpose: f.examPurpose, briefHistory: f.briefHistory,
              contrastMode: f.contrastMode, urgency: f.urgency,
              examNotes: (f.examNotes || []).join(','), execDept: f.execDept,
              examMethod: item.spec || item.itemName
            });
          } else {
            Object.assign(item, {
              treatmentPart: f.treatmentPart, treatmentTimes: f.treatmentTimes,
              quantity: Number(f.treatmentTimes) || 1, execDept: f.execDept
            });
          }
          return item;
        });
      },
      saveOd: function () {
        var vm = this; var error = vm.validateOrder();
        if (error) { ElementPlus.ElMessage.warning(error); return; }
        vm.saving = true;
        HIS.post('/api/his/order/create', {
          visitId: vm.visitId, orderType: vm.orderType, items: vm.payloadItems()
        }).then(function (order) {
          ElementPlus.ElMessage.success('单据已开立：' + (order.orderNo || '成功') + '，金额￥' + money(order.totalAmount));
          vm.buckets[vm.orderType] = makeBucket();
          vm.forms[vm.orderType] = vm.orderType === '检验' ? labForm() : (vm.orderType === '检查' ? examForm() : treatmentForm());
          vm.loadOrders(); vm.$emit('order-saved', order);
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      cancelOrder: function (order) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废单据“' + order.orderNo + '”？仅未收费单据可作废。', '作废确认', { type: 'warning' }).then(function () {
          return HIS.post('/api/his/order/cancel?id=' + encodeURIComponent(order.id));
        }).then(function (saved) {
          ElementPlus.ElMessage.success('单据已作废'); vm.loadOrders(); vm.$emit('order-saved', saved);
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close' && HIS.notifyError) { HIS.notifyError(e); } });
      },
      printOrder: function (order) { this.$emit('print-order', order.id); },
      orderStatus: function (o) {
        if (Number(o.status) < 0 || Number(o.status) === 3) { return '已作废'; }
        if (o.reportStatus === 1 || o.reportTime) { return '已报告'; }
        if (Number(o.status) >= 2 || o.executeTime) { return '已执行'; }
        if (o.chargeStatus === 1 || o.chargeTime) { return '已收费'; }
        return '已开';
      },
      orderProgress: function (o) {
        var steps = ['已开'];
        if (o.chargeStatus === 1 || o.chargeTime || Number(o.status) >= 2) { steps.push('已收费'); }
        if (Number(o.status) >= 2 || o.executeTime) { steps.push('已执行'); }
        if (o.reportStatus === 1 || o.reportTime) { steps.push('已报告'); }
        return steps.join(' → ');
      },
      showReport: function (row) {
        this.reportDetail = row; this.reportVisible = true; this.checkCriticalReport(row);
      },
      reportItems: function (row) { return (row && row.items) || []; },
      reportItemsLine: function (row) {
        return this.reportItems(row).map(function (item) { return item.itemName || item.name || '-'; }).join('、');
      },
      reportValue: function (item) { return item.resultValue != null ? item.resultValue : (item.value != null ? item.value : item.result); },
      checkCriticalReport: function (report) {
        var vm = this; var hits = [];
        vm.reportItems(report).forEach(function (item) {
          var name = String(item.itemName || ''); var value = parseFloat(vm.reportValue(item));
          if (!isFinite(value)) { return; }
          Object.keys(CRITICAL_VALUES).forEach(function (key) {
            var rule = CRITICAL_VALUES[key];
            if (name.indexOf(key) >= 0 && ((rule.low != null && value < rule.low) || (rule.high != null && value > rule.high))) {
              hits.push(name + '：' + value + ' ' + rule.unit + '（危急范围：' + (rule.low == null ? '-∞' : rule.low) + '～' + (rule.high == null ? '+∞' : rule.high) + '）');
            }
          });
        });
        if (!hits.length) { return; }
        this.$nextTick(function () {
          ElementPlus.ElMessageBox.alert('发现危急值：\n\n' + hits.join('\n') + '\n\n请立即按危急值流程处置并记录。', '危急值警报', {
            confirmButtonText: '已知悉', type: 'error', customClass: 'dw-critical-value-dialog',
            dangerouslyUseHTMLString: false, showClose: false, closeOnClickModal: false, closeOnPressEscape: false
          });
        });
      }
    },
    template: `
      <div class="dw-panel dw-order-panel" :class="{'is-expanded':isExpanded}">
        <style>
          .dw-order-panel .dw-order-toolbar{display:flex;align-items:center;gap:8px;padding:8px 10px}.dw-order-panel .dw-order-search{flex:1;min-width:140px}.dw-order-panel .dw-order-empty{padding:18px;text-align:center;color:var(--dw-text-hint);font-size:12px}.dw-order-panel .dw-order-split{display:grid;grid-template-columns:35% 65%;min-height:440px}.dw-order-panel .dw-order-catalog{padding:10px;border-right:1px solid var(--dw-border)}.dw-order-panel .dw-order-form{padding:10px 14px}.dw-order-panel .dw-package-row{display:flex;gap:5px;flex-wrap:wrap;margin:8px 0}.dw-order-panel .dw-form-readonly{padding:6px 9px;background:var(--dw-card-muted);border:1px solid var(--dw-border);border-radius:4px;color:var(--dw-text-secondary);min-height:30px}.dw-order-panel .dw-order-cards{padding:0 10px 8px}.dw-order-panel .dw-order-history{padding:10px 14px;border-top:1px solid var(--dw-border)}.dw-order-panel .dw-progress{color:var(--dw-primary);font-size:11px;white-space:nowrap}.dw-order-panel .dw-mutual-alert{padding:12px;border-left:4px solid var(--dw-warning);background:var(--dw-warning-light);line-height:1.8}.dw-order-panel .dw-report-result{font-weight:700}.dw-critical-value-dialog{width:100vw!important;max-width:none!important;height:100vh;margin:0!important;border:8px solid var(--dw-danger)!important;border-radius:0!important;background:var(--dw-danger-light)!important;display:flex;flex-direction:column;justify-content:center}.dw-critical-value-dialog .el-message-box__title,.dw-critical-value-dialog .el-message-box__message{color:var(--dw-danger)!important;font-size:22px;font-weight:700}.dw-critical-value-dialog .el-message-box__content{max-width:760px;margin:0 auto;white-space:pre-line}.dw-critical-value-dialog .el-message-box__btns{justify-content:center}.dw-critical-value-dialog .el-button{font-size:18px;padding:18px 42px}
        </style>
        <div class="dw-panel-header">
          <span>检查 · 检验 · 治疗申请</span>
          <button class="dw-btn-icon dw-expand-btn" type="button" :title="isExpanded?'收起':'放大'" @click="toggleExpand">{{ isExpanded ? '↙' : '↗' }}</button>
        </div>
        <template v-if="visit">
          <div class="dw-order-toolbar">
            <el-radio-group v-model="orderType" size="small" @change="switchType"><el-radio-button v-for="t in orderTypes" :key="t" :label="t">{{ t }}</el-radio-button></el-radio-group>
            <el-input v-model="activeBucket.keyword" class="dw-order-search" size="small" clearable placeholder="项目名称/编码/拼音简码" @keyup.enter="searchOd"><template #append><el-button :loading="activeBucket.loading" @click="searchOd">检索</el-button></template></el-input>
          </div>
          <div v-if="isExpanded" class="dw-order-split">
            <div class="dw-order-catalog">
              <div class="dw-section" style="margin-top:0">项目与套餐</div>
              <div class="dw-package-row"><el-button v-for="p in allPackages" :key="p.name" size="small" plain :loading="packageLoading" @click="addPackage(p)">{{ p.name }}</el-button></div>
              <el-table :data="activeBucket.results" v-loading="activeBucket.loading" border size="small" height="250">
                <el-table-column type="index" label="序号" width="48"></el-table-column><el-table-column prop="itemName" label="项目" show-overflow-tooltip></el-table-column><el-table-column label="价格" width="72" align="right"><template #default="s">￥{{ money(s.row.execPrice!=null?s.row.execPrice:s.row.price) }}</template></el-table-column><el-table-column label="" width="48"><template #default="s"><el-button link type="primary" :disabled="!canEdit" @click="addOd(s.row)">加</el-button></template></el-table-column>
              </el-table>
              <div class="dw-section">互认提醒<span class="dw-section-extra">30天内 HR</span></div>
              <div class="dim">添加项目时自动检索患者近期同类结果；继续开单须记录医学理由。</div>
            </div>
            <div class="dw-order-form">
              <div class="dw-section" style="margin-top:0">{{ orderType }}申请单<span class="dw-section-extra">结构化录入</span></div>
              <el-form :model="activeForm" label-width="92px" size="small">
                <div class="dw-form-grid"><el-form-item label="临床诊断"><div class="dw-form-readonly" style="width:100%">{{ diagnosisText || '尚未录入诊断' }}</div></el-form-item><el-form-item label="申请医师"><div class="dw-form-readonly" style="width:100%">{{ doctorName || '-' }}</div></el-form-item></div>
                <template v-if="orderType==='检验'">
                  <div class="dw-form-grid"><el-form-item label="标本类型" required><el-select v-model="activeForm.specimenType" style="width:100%"><el-option v-for="x in specimenTypes" :key="x" :label="x" :value="x"></el-option></el-select></el-form-item><el-form-item label="标本条件" required><el-select v-model="activeForm.specimenCondition" style="width:100%"><el-option v-for="x in specimenConditions" :key="x" :label="x" :value="x"></el-option></el-select></el-form-item></div>
                  <div class="dw-form-grid"><el-form-item label="采集部位"><el-input v-model="activeForm.collectionSite" placeholder="按需填写"></el-input></el-form-item><el-form-item label="紧急程度" required><el-select v-model="activeForm.urgency" style="width:100%"><el-option v-for="x in urgencyLab" :key="x" :label="x" :value="x"></el-option></el-select></el-form-item></div>
                  <el-form-item label="用药情况"><el-input v-model="activeForm.medicationInfo" placeholder="选填"></el-input></el-form-item><el-form-item label="送检目的"><el-input v-model="activeForm.inspectionPurpose" placeholder="选填"></el-input></el-form-item>
                </template>
                <template v-else-if="orderType==='检查'">
                  <div class="dw-form-grid"><el-form-item label="检查部位" required><el-input v-model="activeForm.examPart"></el-input></el-form-item><el-form-item label="执行科室" required><el-select v-model="activeForm.execDept" style="width:100%"><el-option v-for="x in execDepts" :key="x" :label="x" :value="x"></el-option></el-select></el-form-item></div>
                  <el-form-item label="检查目的" required><el-input v-model="activeForm.examPurpose" placeholder="如：排除XX、评估XX"></el-input></el-form-item><el-form-item label="简要病史" required><el-input v-model="activeForm.briefHistory" type="textarea" :rows="3"></el-input></el-form-item>
                  <div class="dw-form-grid"><el-form-item label="造影方式" required><el-radio-group v-model="activeForm.contrastMode"><el-radio label="平扫">平扫</el-radio><el-radio label="增强">增强</el-radio></el-radio-group></el-form-item><el-form-item label="紧急程度" required><el-select v-model="activeForm.urgency" style="width:100%"><el-option v-for="x in urgencyExam" :key="x" :label="x" :value="x"></el-option></el-select></el-form-item></div>
                  <el-form-item label="注意事项"><el-checkbox-group v-model="activeForm.examNotes"><el-checkbox v-for="x in examNotes" :key="x" :label="x">{{ x }}</el-checkbox></el-checkbox-group></el-form-item>
                </template>
                <template v-else>
                  <div class="dw-form-grid"><el-form-item label="治疗部位" required><el-input v-model="activeForm.treatmentPart"></el-input></el-form-item><el-form-item label="治疗次数" required><el-input-number v-model="activeForm.treatmentTimes" :min="1" :max="999" style="width:100%"></el-input-number></el-form-item></div><el-form-item label="执行科室" required><el-select v-model="activeForm.execDept" style="width:100%"><el-option v-for="x in treatDepts" :key="x" :label="x" :value="x"></el-option></el-select></el-form-item>
                </template>
              </el-form>
              <div class="dw-section">暂存项目</div>
              <el-table :data="activeBucket.items" border size="small" max-height="220"><el-table-column type="index" label="序号" width="48"></el-table-column><el-table-column prop="itemName" label="项目" show-overflow-tooltip></el-table-column><el-table-column label="数量" width="112"><template #default="s"><el-input-number v-model="s.row.quantity" :min="1" size="small" controls-position="right" style="width:96px"></el-input-number></template></el-table-column><el-table-column label="金额" width="82" align="right"><template #default="s">￥{{ lineAmount(s.row) }}</template></el-table-column><el-table-column label="" width="48"><template #default="s"><el-button link type="danger" @click="removeItem(s.$index)">删</el-button></template></el-table-column></el-table>
            </div>
          </div>
          <div v-else class="dw-order-cards">
            <div v-if="!activeBucket.items.length" class="dw-order-empty">检索并添加{{ orderType }}项目，放大后填写完整申请信息</div>
            <div class="dw-od-item" v-for="(it,index) in activeBucket.items" :key="it.itemId+'-'+index"><div class="row1"><span class="nm">{{ it.itemName }}</span><span>×{{ it.quantity }}</span><span class="amt">￥{{ lineAmount(it) }}</span><el-button link type="danger" @click="removeItem(index)">删</el-button></div></div>
          </div>
          <div class="dw-foot-bar" v-if="activeBucket.items.length"><span>共 {{ activeBucket.items.length }} 项 · 合计 <b>￥{{ money(orderTotal) }}</b></span><el-button type="primary" size="small" :loading="saving" :disabled="!canEdit" @click="saveOd">开立{{ orderType }}单</el-button></div>
          <div class="dw-order-history" v-loading="loadingOrders">
            <div class="dw-section" style="margin-top:0">已开立单据 <el-button link size="small" @click="reportVisible=true">查看报告</el-button></div>
            <div v-if="!visibleOrders.length" class="dim">暂无{{ orderType }}单</div>
            <div class="dw-done-list"><div class="dw-done-item" v-for="o in visibleOrders" :key="o.id"><span class="no">{{ o.orderNo }}</span><span class="amt">￥{{ money(o.totalAmount) }}</span><span class="dw-progress">{{ orderProgress(o) }}</span><el-button link type="primary" @click="printOrder(o)">打印</el-button><el-button v-if="Number(o.status)===1" link type="danger" @click="cancelOrder(o)">作废</el-button></div></div>
          </div>
        </template>
        <div v-else class="dw-order-empty"><el-empty description="请选择接诊患者"></el-empty></div>

        <el-dialog v-model="mutualVisible" title="检查检验结果互认提醒" width="560px" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" @closed="onMutualClosed">
          <div class="dw-mutual-alert">该患者30天内已有同类检查结果（<b>{{ mutualHit&&mutualHit.date }}</b> {{ mutualHit&&mutualHit.summary }} <el-tag type="warning" size="small">{{ mutualHit&&mutualHit.hr }}</el-tag>），是否仍需开单？</div>
          <div class="dw-section">仍需开单理由</div><el-radio-group v-model="mutualReason"><el-radio v-for="x in ['病情变化','结果变化快','重大措施前','急诊','鉴定','其他']" :key="x" :label="x">{{ x }}</el-radio></el-radio-group>
          <template #footer><el-button @click="finishMutual(false)">取消开单</el-button><el-button type="warning" @click="finishMutual(true)">仍需开单</el-button></template>
        </el-dialog>

        <el-dialog v-model="reportVisible" title="检验检查报告" width="820px" top="5vh">
          <el-table :data="reports" border size="small" max-height="240" highlight-current-row @row-click="showReport"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column label="类型" width="70"><template #default="s">{{ s.row.order&&s.row.order.orderType }}</template></el-table-column><el-table-column label="单据号" width="190"><template #default="s">{{ s.row.order&&s.row.order.orderNo }}</template></el-table-column><el-table-column label="日期" width="130"><template #default="s">{{ textDate((s.row.order||{}).reportTime||(s.row.order||{}).createTime) }}</template></el-table-column><el-table-column label="项目"><template #default="s">{{ reportItemsLine(s.row) }}</template></el-table-column></el-table>
          <template v-if="reportDetail"><div class="dw-section">报告结果</div><el-table :data="reportItems(reportDetail)" border size="small" max-height="300"><el-table-column prop="itemName" label="项目"></el-table-column><el-table-column label="结果"><template #default="s"><span class="dw-report-result">{{ reportValue(s.row) == null ? '-' : reportValue(s.row) }}</span></template></el-table-column><el-table-column prop="unit" label="单位" width="100"></el-table-column><el-table-column prop="referenceRange" label="参考范围" width="150"></el-table-column></el-table></template>
          <template #footer><el-button @click="reportVisible=false">关闭</el-button></template>
        </el-dialog>
      </div>
    `
  };

  DwOrderPanel.methods.textDate = textDate;
  HIS.components.DwOrderPanel = DwOrderPanel;
})();
