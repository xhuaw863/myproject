/* 门诊医生工作站主编排器：共享就诊状态、左队列+双栏独立滚动 Tab 布局、事件中继与快捷键。 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};
  HIS.components = HIS.components || {};

  function visitIdOf(visit) {
    return visit && (visit.id || visit.visitId);
  }

  function patientIdOf(visit) {
    return visit && visit.patientId;
  }

  function asList(value) {
    return Array.isArray(value) ? value : ((value && value.records) || []);
  }

  HIS.views.DoctorWorkstation = {
    name: 'DoctorWorkstation',
    components: {
      'dw-queue-panel': HIS.components.DwQueuePanel,
      'dw-patient-banner': HIS.components.DwPatientBanner,
      'dw-emr-panel': HIS.components.DwEmrPanel,
      'dw-diagnosis-panel': HIS.components.DwDiagnosisPanel,
      'dw-prescription-panel': HIS.components.DwPrescriptionPanel,
      'dw-order-panel': HIS.components.DwOrderPanel,
      'dw-documents-panel': HIS.components.DwDocumentsPanel,
      'dw-orders-overview': HIS.components.DwOrdersOverview,
      'dw-vital-panel': HIS.components.DwVitalPanel,
      'dw-pre-consult-dialog': HIS.components.DwPreConsultDialog,
      'dw-report-panel': HIS.components.DwReportPanel,
      'dept-tree-picker': HIS.components.DeptTreePicker
    },
    data: function () {
      return {
        currentVisit: null,
        currentPatient: null,
        visitHistory: [],
        diagnoses: [],
        activeTab: 'clinic',
        rxCount: 0,
        orderCount: 0,
        leftCollapsed: false,
        feeSummary: null,
        currentTime: '',
        visitTimer: 0,
        timerInterval: null,
        submitting: false,
        /* OP-A 多病人并行接诊 + 体征/预问诊/诊后去向 */
        openVisits: [],
        vitalVisible: false,
        preConsultVisible: false,
        dispositionVisible: false,
        dispositionDepts: [],
        dispositionForm: { disposition: 1, dispositionDeptId: null, dispositionNote: '' },
        /* OP-D 危急值轮询待办(14.6) */
        pendingCriticals: [],
        critAlertedIds: []
      };
    },
    provide: function () {
      var vm = this;
      return {
        currentVisit: Vue.computed(function () { return vm.currentVisit; }),
        currentPatient: Vue.computed(function () { return vm.currentPatient; }),
        visitHistory: Vue.computed(function () { return vm.visitHistory; }),
        diagnoses: Vue.computed(function () { return vm.diagnoses; }),
        visitDuration: Vue.computed(function () { return vm.visitDuration; }),
        feeSummary: Vue.computed(function () { return vm.feeSummary; })
      };
    },
    computed: {
      canStart: function () {
        return !!this.currentVisit && Number(this.currentVisit.visitStatus) === 1;
      },
      canEdit: function () {
        return !!this.currentVisit && Number(this.currentVisit.visitStatus) === 2;
      },
      feeTotal: function () {
        var fee = this.feeSummary || {};
        return Number(fee.totalFee != null ? fee.totalFee : fee.total) || 0;
      },
      visitDuration: function () {
        var seconds = Math.max(0, Number(this.visitTimer) || 0);
        var hours = Math.floor(seconds / 3600);
        var minutes = Math.floor((seconds % 3600) / 60);
        var remain = seconds % 60;
        return [hours, minutes, remain].map(function (value) {
          return ('0' + value).slice(-2);
        }).join(':');
      }
    },
    mounted: function () {
      var vm = this;
      vm._keyHandler = function (event) { vm.handleShortcut(event); };
      document.addEventListener('keydown', vm._keyHandler);
      vm.updateClock();
      vm._critTick = 0;
      vm.timerInterval = window.setInterval(function () {
        vm.updateClock();
        if (vm.canEdit) { vm.visitTimer += 1; }
        /* OP-D 危急值轮询: 每 60s 拉一次本人待接收项 */
        vm._critTick += 1;
        if (vm._critTick >= 60) { vm._critTick = 0; vm.checkCriticals(); }
      }, 1000);
      vm.checkCriticals();

      if (HIS.pendingVisitId) {
        var pendingId = HIS.pendingVisitId;
        HIS.pendingVisitId = null;
        vm.onSelectVisit({ id: pendingId });
      }
    },
    beforeUnmount: function () {
      document.removeEventListener('keydown', this._keyHandler);
      if (this.timerInterval) { window.clearInterval(this.timerInterval); }
    },
    methods: {
      updateClock: function () {
        this.currentTime = new Date().toLocaleTimeString('zh-CN', { hour12: false });
      },
      handleShortcut: function (event) {
        var target = event.target || {};
        var tag = String(target.tagName || '').toLowerCase();
        var editing = tag === 'input' || tag === 'textarea' || target.isContentEditable;
        if (event.key === 'F2') { event.preventDefault(); this.startVisit(); return; }
        if (event.key === 'F3') { event.preventDefault(); this.saveDraft(); return; }
        if (event.key === 'F4') { event.preventDefault(); this.finishVisit(); return; }
        if (event.key === 'F5') { event.preventDefault(); this.focusPanelSearch('rx'); return; }
        if (event.key === 'F6') { event.preventDefault(); this.focusPanelSearch('order'); return; }
        if (event.key === 'F8') { event.preventDefault(); this.refreshQueue(); return; }
        if (!editing && event.ctrlKey && event.key === '1') { event.preventDefault(); this.switchTab('clinic'); }
        if (!editing && event.ctrlKey && event.key === '2') { event.preventDefault(); this.switchTab('docs'); }
        if (!editing && event.ctrlKey && event.key === '3') { event.preventDefault(); this.switchTab('overview'); }
        if (!editing && event.ctrlKey && event.key === '4') { event.preventDefault(); this.switchTab('report'); }
      },
      switchTab: function (tab) {
        this.activeTab = tab;
        /* dw-main 不再整体滚动: 复位当前可见页签内各滚动容器(栏内/整页) */
        var root = this.$el;
        if (!root) { return; }
        var bodies = root.querySelectorAll('.dw-tab-body');
        for (var i = 0; i < bodies.length; i++) {
          if (bodies[i].style.display === 'none') { continue; }
          bodies[i].scrollTop = 0;
          var cols = bodies[i].querySelectorAll('.dw-col');
          for (var j = 0; j < cols.length; j++) { cols[j].scrollTop = 0; }
        }
      },
      onSelectVisit: function (visit) {
        if (!visitIdOf(visit)) { return; }
        this.pinOpenVisit(visit);
        this.currentVisit = visit;
        this.currentPatient = visit;
        this.visitHistory = [];
        this.diagnoses = [];
        this.feeSummary = null;
        this.rxCount = 0;
        this.orderCount = 0;
        this.activeTab = 'clinic';
        this.visitTimer = 0;
        this.loadPatientDetail(visit);
        this.loadVisitHistory(visit);
        this.loadFeeSummary(visit);
      },
      /* ===== OP-A 多病人并行接诊 ===== */
      pinOpenVisit: function (visit) {
        var id = String(visitIdOf(visit));
        if (!id || id === 'undefined') { return; }
        var exists = this.openVisits.some(function (v) { return String(v.id) === id; });
        if (!exists) {
          this.openVisits.push({ id: visitIdOf(visit), patientName: visit.patientName || '-', visitStatus: visit.visitStatus });
        }
      },
      isActiveVisit: function (visit) {
        return !!this.currentVisit && String(this.currentVisit.id) === String(visit.id);
      },
      switchActiveVisit: function (visit) {
        var vm = this;
        if (vm.isActiveVisit(visit)) { return; }
        /* 切换前对当前接诊中病历尽力自动暂存, 避免丢失未保存书写 */
        var emr = vm.$refs.emrPanel;
        if (vm.canEdit && emr && typeof emr.emitSave === 'function') {
          try { emr.emitSave(false); } catch (e) { /* 静默: 未选模板等情况不阻断切换 */ }
        }
        vm.onSelectVisit({ id: visit.id, patientName: visit.patientName, visitStatus: visit.visitStatus });
      },
      closeOpenVisit: function (visit) {
        var vm = this;
        var id = String(visit.id);
        var remaining = vm.openVisits.filter(function (v) { return String(v.id) !== id; });
        vm.openVisits = remaining;
        if (vm.currentVisit && String(vm.currentVisit.id) === id) {
          var next = remaining.length ? remaining[remaining.length - 1] : null;
          if (next) { vm.switchActiveVisit(next); } else { vm.clearActiveVisit(); }
        }
      },
      clearActiveVisit: function () {
        this.currentVisit = null;
        this.currentPatient = null;
        this.visitHistory = [];
        this.diagnoses = [];
        this.feeSummary = null;
        this.rxCount = 0;
        this.orderCount = 0;
      },
      /* ===== OP-A 体征/预问诊引用中继到病历面板 ===== */
      openVital: function () {
        if (!this.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        this.vitalVisible = true;
      },
      openPreConsult: function () {
        if (!this.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        this.preConsultVisible = true;
      },
      onQuoteVital: function (text) {
        var emr = this.$refs.emrPanel;
        if (emr && typeof emr.appendVital === 'function') { emr.appendVital(text); }
      },
      onQuotePreConsult: function (payload) {
        var emr = this.$refs.emrPanel;
        if (emr && typeof emr.applyPreConsult === 'function') { emr.applyPreConsult(payload); }
      },
      onStartVisit: function (visit) {
        var vm = this;
        var selected = visit || vm.currentVisit;
        var id = visitIdOf(selected);
        if (!id) { ElementPlus.ElMessage.warning('请先从左侧队列选择患者'); return; }
        if (Number(selected.visitStatus) >= 2) { ElementPlus.ElMessage.warning('该患者已在接诊中或已完成'); return; }
        HIS.post('/api/his/visit/start?id=' + encodeURIComponent(id)).then(function (saved) {
          vm.currentVisit = saved || Object.assign({}, selected, { visitStatus: 2 });
          vm.currentPatient = vm.currentVisit;
          vm.visitTimer = 0;
          HIS.notifySuccess('已开始接诊：' + (vm.currentVisit.patientName || ''));
          vm.loadPatientDetail(vm.currentVisit);
          vm.refreshQueue();
        }).catch(HIS.notifyError);
      },
      startVisit: function () {
        this.onStartVisit(this.currentVisit);
      },
      saveDraft: function () {
        if (!this.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!this.canEdit) { ElementPlus.ElMessage.warning('仅接诊中的患者可暂存病历'); return; }
        var panel = this.$refs.emrPanel;
        if (panel && typeof panel.emitSave === 'function') { panel.emitSave(false); }
      },
      onSaveDraft: function (soapData) {
        var vm = this;
        soapData = soapData || {};
        if (!vm.canEdit || vm.submitting) { return; }
        vm.submitting = true;
        HIS.post('/api/his/visit/save-draft', Object.assign({}, soapData, {
          visitId: visitIdOf(vm.currentVisit),
          /* P3-4: Tiptap 模式随草稿保存 content(Tiptap JSON 串, 服务端加密落库); 旧模式为 undefined 将被序列化忽略 */
          content: soapData.content
        })).then(function () {
          HIS.notifySuccess('病历草稿已暂存(F3)');
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      finishVisit: function () {
        var vm = this;
        if (!vm.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.canEdit || vm.submitting) { return; }
        if (!vm.diagnoses.length) { ElementPlus.ElMessage.warning('请至少录入一条诊断'); return; }
        var emr = vm.$refs.emrPanel;
        if (emr && typeof emr.validateForFinish === 'function' && !emr.validateForFinish()) { return; }
        /* OP-A 诊后去向: 完成前弹出去向选择(离院/转科/转留观/转院) */
        vm.dispositionForm = { disposition: 1, dispositionDeptId: null, dispositionNote: '' };
        if (!vm.dispositionDepts.length) { vm.loadDispositionDepts(); }
        vm.dispositionVisible = true;
      },
      loadDispositionDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (list) {
          vm.dispositionDepts = list || [];
        }).catch(function () { vm.dispositionDepts = []; });
      },
      doSubmitFinish: function () {
        var vm = this;
        if (!vm.canEdit || vm.submitting) { return; }
        var emr = vm.$refs.emrPanel;
        var emrPayload = (emr && typeof emr.buildFinishPayload === 'function') ? emr.buildFinishPayload() : {};
        var payload = Object.assign({}, emrPayload, {
          visitId: visitIdOf(vm.currentVisit),
          diagnoses: vm.diagnoses,
          uploadYb: true,
          /* P3-4: Tiptap 模式随完成接诊提交 content(Tiptap JSON 串); 旧模式为 undefined 将被序列化忽略 */
          content: emrPayload ? emrPayload.content : undefined,
          disposition: vm.dispositionForm.disposition,
          dispositionDeptId: vm.dispositionForm.dispositionDeptId,
          dispositionNote: vm.dispositionForm.dispositionNote
        });
        vm.dispositionVisible = false;
        vm.submitting = true;
        HIS.post('/api/his/visit/finish', payload).then(function (saved) {
          vm.currentVisit = saved || Object.assign({}, vm.currentVisit, { visitStatus: 3 });
          HIS.notifySuccess('接诊完成，已进入待收费');
          vm.loadPatientDetail(vm.currentVisit);
          vm.loadFeeSummary(vm.currentVisit);
          vm.refreshQueue();
        }).catch(function (error) {
          if (error && error.message) { HIS.notifyError(error); }
        }).finally(function () { vm.submitting = false; });
      },
      onUpdateDiagnoses: function (diagList) {
        this.diagnoses = Array.isArray(diagList) ? diagList : [];
      },
      /* OP-B 诊断→模板调入中继: 诊断面板命中关联模板并经确认后, 按类型路由到处方/医嘱面板批量入行 */
      onApplyDiagTemplate: function (tpl) {
        tpl = tpl || {};
        if (tpl.templateType === 'order_set') {
          var op = this.$refs.orderPanel;
          if (op && typeof op.applyDiagTemplate === 'function') { op.applyDiagTemplate(tpl); }
          else { ElementPlus.ElMessage.warning('医嘱面板不可用, 无法调入模板'); }
        } else {
          var rp = this.$refs.rxPanel;
          if (rp && typeof rp.applyDiagTemplate === 'function') { rp.applyDiagTemplate(tpl); }
          else { ElementPlus.ElMessage.warning('处方面板不可用, 无法调入模板'); }
        }
      },
      onRxSaved: function () {
        this.loadFeeSummary(this.currentVisit);
        this.refreshDocuments();
        this.refreshOrdersOverview();
      },
      onOrderSaved: function () {
        this.loadFeeSummary(this.currentVisit);
        this.refreshDocuments();
        this.refreshOrdersOverview();
      },
      refreshOrdersOverview: function () {
        var ov = this.$refs.ordersOverview;
        if (ov && typeof ov.refresh === 'function') { ov.refresh(); }
      },
      /* 病历摘要引用中继: 处方/医嘱面板【插入病历】发射 insert-to-record, 转调病历面板追加方法 */
      onInsertToRecord: function (payload) {
        payload = payload || {};
        var emr = this.$refs.emrPanel;
        if (!emr) { ElementPlus.ElMessage.warning('请先打开诊疗工作台页签'); return; }
        if (payload.target === 'auxExam') {
          if (typeof emr.appendAuxExam === 'function') { emr.appendAuxExam(payload.text); return; }
        } else if (typeof emr.appendTreatment === 'function') {
          emr.appendTreatment(payload.text); return;
        }
        ElementPlus.ElMessage.warning('病历面板不可用, 无法插入摘要');
      },
      /* 弹窗跳转/快捷入口: 面板名映射到 Tab */
      onRequestExpand: function (panelName) {
        this.switchTab(panelName === 'documents' ? 'docs' : 'clinic');
      },
      onRxCount: function (n) { this.rxCount = Number(n) || 0; },
      onOrderCount: function (n) { this.orderCount = Number(n) || 0; },
      onFeeUpdated: function (summary) {
        this.feeSummary = summary || null;
      },
      onPrintRx: function (rxId) {
        if (!rxId) { return; }
        HIS.get('/api/his/prescription/print-data?id=' + encodeURIComponent(rxId))
          .then(function (data) { HIS.printRx(data); }).catch(HIS.notifyError);
      },
      onPrintOrder: function (orderId) {
        var vm = this;
        if (!orderId) { return; }
        HIS.get('/api/his/order/print-data?id=' + encodeURIComponent(orderId))
          .then(function (data) { HIS.printOrder(data); })
          .catch(function () { return vm.printOrderFallback(orderId); })
          .catch(HIS.notifyError);
      },
      printOrderFallback: function (orderId) {
        var vm = this;
        var visitId = visitIdOf(vm.currentVisit);
        return Promise.all([
          HIS.get('/api/his/order/list?visitId=' + encodeURIComponent(visitId)),
          HIS.get('/api/his/order/items?orderId=' + encodeURIComponent(orderId))
        ]).then(function (values) {
          var order = asList(values[0]).find(function (item) { return String(item.id) === String(orderId); });
          HIS.printOrder({ order: order || { id: orderId }, items: asList(values[1]), patient: vm.currentPatient, diagnoses: vm.diagnoses });
        });
      },
      onPrint: function (payload) {
        payload = payload || {};
        if (payload.type === 'rx') { this.onPrintRx(payload.id); return; }
        if (payload.type === 'order') { this.onPrintOrder(payload.id); return; }
        if (payload.type === 'record') { this.printRecord(payload.id); return; }
        if (payload.type === 'cert' || payload.type === 'admission') { this.printCertificate(payload.type, payload.id); }
      },
      printRecord: function (visitId) {
        var vm = this;
        var id = visitId || visitIdOf(vm.currentVisit);
        if (!id) { return; }
        HIS.get('/api/his/visit/detail?id=' + encodeURIComponent(id)).then(function (data) {
          data = data || {};
          var visit = data.visit || {};
          /* P3-4: Tiptap 病历打印须透传 emrFormat/content(detail 接口已解密, 挂在 visit 上) */
          HIS.printRecord({
            visit: visit,
            soap: data.soap || data.record || visit,
            diagnoses: data.diagnoses || vm.diagnoses,
            patient: vm.currentPatient,
            emrFormat: visit.emrFormat,
            content: visit.content
          });
        }).catch(HIS.notifyError);
      },
      printCertificate: function (type, id) {
        var vm = this;
        var base = type === 'admission' ? 'admission-cert' : 'medical-cert';
        HIS.get('/api/his/' + base + '/print-data?id=' + encodeURIComponent(id)).then(function (data) {
          HIS.printCert(data);
        }).catch(function () {
          return HIS.get('/api/his/' + base + '/list?visitId=' + encodeURIComponent(visitIdOf(vm.currentVisit))).then(function (rows) {
            var documentData = asList(rows).find(function (item) { return String(item.id) === String(id); }) || {};
            HIS.printCert({ cert: documentData, admission: documentData, patient: vm.currentPatient });
          });
        }).catch(HIS.notifyError);
      },
      loadPatientDetail: function (visit) {
        var vm = this;
        var id = visitIdOf(visit);
        if (!id) { return Promise.resolve(null); }
        var serial = (vm._detailSerial || 0) + 1;
        vm._detailSerial = serial;
        return HIS.get('/api/his/visit/detail?id=' + encodeURIComponent(id)).then(function (data) {
          if (serial !== vm._detailSerial || String(visitIdOf(vm.currentVisit)) !== String(id)) { return null; }
          var baseVisit = (data && data.visit) || visit;
          var mergedVisit = Object.assign({}, baseVisit, (data && data.record) || {});
          mergedVisit.id = visitIdOf(baseVisit);
          vm.currentVisit = mergedVisit;
          vm.currentPatient = Object.assign({}, baseVisit, (data && data.patient) || {});
          vm.diagnoses = (data && data.diagnoses) || [];
          vm.resetVisitTimer(mergedVisit);
          if (!vm.visitHistory.length) { vm.loadVisitHistory(mergedVisit); }
          return data;
        }).catch(HIS.notifyError);
      },
      loadVisitHistory: function (visit) {
        var vm = this;
        var patientId = patientIdOf(visit);
        if (!patientId) { vm.visitHistory = []; return Promise.resolve([]); }
        return HIS.get('/api/his/visit/history?patientId=' + encodeURIComponent(patientId) + '&limit=5').then(function (rows) {
          if (String(patientIdOf(vm.currentVisit)) === String(patientId)) { vm.visitHistory = rows || []; }
          return rows || [];
        }).catch(function () { vm.visitHistory = []; return []; });
      },
      loadFeeSummary: function (visit) {
        var vm = this;
        var id = visitIdOf(visit);
        if (!id) { vm.feeSummary = null; return Promise.resolve(null); }
        return HIS.get('/api/his/visit/fee-summary?visitId=' + encodeURIComponent(id)).then(function (summary) {
          if (String(visitIdOf(vm.currentVisit)) === String(id)) { vm.feeSummary = summary || null; }
          return summary;
        }).catch(function () { vm.feeSummary = null; return null; });
      },
      resetVisitTimer: function (visit) {
        var started = visit && (visit.startTime || visit.visitTime);
        var timestamp = started ? new Date(String(started).replace(' ', 'T')).getTime() : NaN;
        this.visitTimer = Number(visit && visit.visitStatus) === 2 && !isNaN(timestamp)
          ? Math.max(0, Math.floor((Date.now() - timestamp) / 1000)) : 0;
      },
      refreshQueue: function () {
        var queue = this.$refs.queuePanel;
        if (queue && typeof queue.loadQueue === 'function') { queue.loadQueue(); }
      },
      refreshDocuments: function () {
        var documents = this.$refs.documentsPanel;
        if (documents && typeof documents.refreshAll === 'function') { documents.refreshAll(); }
      },
      /* ===== OP-D 危急值弹窗待办(14.6): 开单医生=本人的已复核/已通知未接收记录 ===== */
      checkCriticals: function () {
        var vm = this;
        return HIS.get('/api/medtech/critical-values/my-pending').then(function (rows) {
          var pending = rows || [];
          vm.pendingCriticals = pending;
          var hit = null;
          for (var i = 0; i < pending.length; i++) {
            if (vm.critAlertedIds.indexOf(String(pending[i].id)) < 0) { hit = pending[i]; break; }
          }
          if (hit) { vm.alertCritical(hit); }
          return pending;
        }).catch(function () { /* 静默: 轮询失败不打断接诊 */ });
      },
      alertCritical: function (c) {
        var vm = this;
        /* 强提醒不自动消退: 仅确认接收/已知晓后记录已弹, 否则下轮轮询继续提醒(危急值必处置) */
        var statusTip = Number(c.status) === 2 ? '已通知临床, 请确认接收' : '待医技通知, 请先行知晓准备';
        ElementPlus.ElMessageBox.confirm(
          '患者 ' + (c.patientName || '-') + '(' + (c.genderName || '') + ' ' + (c.age != null ? c.age + '岁' : '-') + ') 项目「' + (c.itemName || '') + '」结果 ' + (c.resultValue || '') + ' 达危急值(' + (c.discoverTime || '') + ' 发现, ' + statusTip + ')。报告单: ' + (c.reportNo || '-'),
          '危急值提醒',
          { type: 'warning', confirmButtonText: Number(c.status) === 2 ? '确认接收' : '已知晓', cancelButtonText: '稍后处理', distinguishCancelAndClose: true }
        ).then(function () {
          vm.critAlertedIds.push(String(c.id));
          if (Number(c.status) !== 2) { return; }
          return vm.confirmCritical(c);
        }).catch(function () { /* 稍后/关闭: 不记已弹, 下轮继续提醒 */ });
      },
      confirmCritical: function (c) {
        var vm = this;
        var user = (typeof HIS.getUser === 'function' && HIS.getUser()) || {};
        var person = user.staffName || user.realName || user.username || '值班医生';
        return HIS.post('/api/medtech/critical/' + encodeURIComponent(c.id) + '/confirm', { person: person }).then(function () {
          ElementPlus.ElMessage.success('危急值已接收, 请及时补录处置措施');
          return vm.checkCriticals();
        }).catch(function (e) { HIS.notifyError(e); });
      },
      focusPanelSearch: function (panelName) {
        var vm = this;
        if (!vm.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        vm.switchTab('clinic');
        vm.$nextTick(function () {
          var panel = panelName === 'rx' ? vm.$refs.rxPanel : vm.$refs.orderPanel;
          if (!panel) { return; }
          if (panelName === 'rx') {
            var control = panel.$refs.rxPick || panel.$refs.rxSearchExpanded || panel.$refs.rxSearch;
            if (control && typeof control.focus === 'function') { control.focus(); return; }
          }
          var input = panel.$el && panel.$el.querySelector('.dw-order-pick input');
          if (input) { input.focus(); }
        });
      }
    },
    template: `
      <div class="dw-wrap">
        <div class="dw-header">
          <div class="dw-header-left" style="display:flex;align-items:center;gap:10px;min-width:210px">
            <button class="dw-btn-icon" style="color:var(--dw-header-text)" :title="leftCollapsed ? '展开候诊栏' : '折叠候诊栏'" @click="leftCollapsed=!leftCollapsed">{{ leftCollapsed ? '≫' : '≪' }}</button>
            <span class="dw-header-title" style="font-size:16px;font-weight:700;letter-spacing:.08em">门诊医生工作站</span>
          </div>
          <div class="dw-header-right dw-actions">
            <el-button size="small" @click="openPreConsult" :disabled="!currentVisit">预问诊</el-button>
            <el-button size="small" @click="openVital" :disabled="!currentVisit">生命体征</el-button>
            <el-button size="small" @click="startVisit" :disabled="!canStart">F2 接诊</el-button>
            <el-button size="small" @click="saveDraft" :loading="submitting" :disabled="!canEdit">F3 暂存</el-button>
            <el-button size="small" type="primary" @click="finishVisit" :loading="submitting" :disabled="!canEdit">F4 完成</el-button>
            <el-button size="small" @click="refreshQueue">F8 刷新</el-button>
          </div>
        </div>

        <div class="dw-body">
          <dw-queue-panel ref="queuePanel" :class="{'is-collapsed': leftCollapsed}" @select-visit="onSelectVisit" @start-visit="onStartVisit"></dw-queue-panel>

          <div class="dw-main" ref="dwMain">
            <div class="dw-contextbar">
              <dw-patient-banner></dw-patient-banner>
              <div class="dw-openvisits" v-if="openVisits.length > 1">
                <span class="dw-openvisits-label">已打开</span>
                <span v-for="ov in openVisits" :key="ov.id" class="dw-ov-chip" :class="{'is-active': isActiveVisit(ov)}" @click="switchActiveVisit(ov)">
                  {{ ov.patientName }}<i class="dw-ov-close" @click.stop="closeOpenVisit(ov)">×</i>
                </span>
              </div>
              <nav class="dw-tabnav" role="tablist">
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='clinic'}" role="tab" :aria-selected="activeTab==='clinic'" @click="switchTab('clinic')">诊疗工作台<span class="dw-tab-count" v-if="diagnoses.length || rxCount || orderCount">诊{{ diagnoses.length }} 方{{ rxCount }} 嘱{{ orderCount }}</span></button>
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='overview'}" role="tab" :aria-selected="activeTab==='overview'" @click="switchTab('overview')">医嘱总览<span class="dw-tab-count" v-if="rxCount || orderCount">{{ rxCount + orderCount }}</span></button>
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='docs'}" role="tab" :aria-selected="activeTab==='docs'" @click="switchTab('docs')">处置与历史</button>
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='report'}" role="tab" :aria-selected="activeTab==='report'" @click="switchTab('report')">报告<span class="dw-tab-count" v-if="pendingCriticals.length">⚠{{ pendingCriticals.length }}</span></button>
              </nav>
            </div>

            <div class="dw-tab-body dw-clinic" v-show="activeTab==='clinic'">
              <div class="dw-col dw-col-left">
                <dw-diagnosis-panel @update-diagnoses="onUpdateDiagnoses" @apply-template="onApplyDiagTemplate"></dw-diagnosis-panel>
                <dw-emr-panel ref="emrPanel" @save-draft="onSaveDraft"></dw-emr-panel>
              </div>
              <div class="dw-col dw-col-right">
                <dw-prescription-panel ref="rxPanel" @rx-saved="onRxSaved" @count-update="onRxCount" @print-rx="onPrintRx" @insert-to-record="onInsertToRecord"></dw-prescription-panel>
                <dw-order-panel ref="orderPanel" @order-saved="onOrderSaved" @count-update="onOrderCount" @print-order="onPrintOrder" @insert-to-record="onInsertToRecord"></dw-order-panel>
              </div>
            </div>

            <div class="dw-tab-body" v-if="activeTab==='overview'">
              <dw-orders-overview ref="ordersOverview" @print-rx="onPrintRx" @print-order="onPrintOrder"></dw-orders-overview>
            </div>

            <div class="dw-tab-body dw-tab-narrow" v-show="activeTab==='docs'">
              <dw-documents-panel ref="documentsPanel" @print="onPrint" @fee-updated="onFeeUpdated"></dw-documents-panel>
            </div>

            <div class="dw-tab-body dw-tab-narrow" v-if="activeTab==='report'">
              <dw-report-panel @insert-to-record="onInsertToRecord"></dw-report-panel>
            </div>
          </div>
        </div>

        <div class="dw-footer">
          <span>F2接诊 F3暂存 F4完成 F5药品 F6医嘱 F8刷新 | Ctrl+1诊疗 Ctrl+2处置 Ctrl+3总览 Ctrl+4报告</span>
          <span class="dw-footer-time" style="margin-left:auto;font-variant-numeric:tabular-nums">{{ currentTime }}</span>
        </div>

        <dw-vital-panel v-model="vitalVisible" @quote-to-record="onQuoteVital"></dw-vital-panel>
        <dw-pre-consult-dialog v-model="preConsultVisible" @quote-to-record="onQuotePreConsult"></dw-pre-consult-dialog>
        <el-dialog v-model="dispositionVisible" title="诊后去向 / 完成接诊" width="460px" append-to-body>
          <el-form :model="dispositionForm" label-width="88px" size="small">
            <el-form-item label="去向">
              <el-radio-group v-model="dispositionForm.disposition">
                <el-radio :label="1">离院</el-radio><el-radio :label="2">转科</el-radio><el-radio :label="3">转留观</el-radio><el-radio :label="4">转院</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="转科科室" v-if="dispositionForm.disposition===2">
              <dept-tree-picker v-model="dispositionForm.dispositionDeptId" :options="dispositionDepts" size="small" placeholder="选择目标科室" />
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="dispositionForm.dispositionNote" type="textarea" :autosize="{minRows:2,maxRows:3}"/></el-form-item>
            <div class="dim">确认后将保存病历与诊断、上传医保2203就诊信息，并转入待收费。</div>
          </el-form>
          <template #footer>
            <el-button size="small" @click="dispositionVisible=false">取消</el-button>
            <el-button size="small" type="primary" :loading="submitting" @click="doSubmitFinish">确认完成</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };
})();
