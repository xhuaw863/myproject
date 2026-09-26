/* 门诊医生工作站主编排器：共享就诊状态、三栏布局、事件中继与快捷键。 */
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
      'dw-documents-panel': HIS.components.DwDocumentsPanel
    },
    data: function () {
      return {
        currentVisit: null,
        currentPatient: null,
        visitHistory: [],
        diagnoses: [],
        expandedPanel: null,
        leftCollapsed: false,
        feeSummary: null,
        currentTime: '',
        visitTimer: 0,
        timerInterval: null,
        submitting: false
      };
    },
    provide: function () {
      var vm = this;
      return {
        currentVisit: Vue.computed(function () { return vm.currentVisit; }),
        currentPatient: Vue.computed(function () { return vm.currentPatient; }),
        visitHistory: Vue.computed(function () { return vm.visitHistory; }),
        diagnoses: Vue.computed(function () { return vm.diagnoses; }),
        expandedPanel: Vue.computed(function () {
          return vm.expandedPanel === 'diag' ? 'diagnosis' : vm.expandedPanel;
        }),
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
      vm.timerInterval = window.setInterval(function () {
        vm.updateClock();
        if (vm.canEdit) { vm.visitTimer += 1; }
      }, 1000);

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
        if (event.key === 'Escape') { this.expandedPanel = null; return; }
        if (!editing && event.ctrlKey && event.key === '1') { event.preventDefault(); this.expandedPanel = 'emr'; }
        if (!editing && event.ctrlKey && event.key === '2') { event.preventDefault(); this.expandedPanel = 'diag'; }
        if (!editing && event.ctrlKey && event.key === '3') { event.preventDefault(); this.expandedPanel = 'rx'; }
        if (!editing && event.ctrlKey && event.key === '4') { event.preventDefault(); this.expandedPanel = 'order'; }
      },
      onSelectVisit: function (visit) {
        if (!visitIdOf(visit)) { return; }
        this.currentVisit = visit;
        this.currentPatient = visit;
        this.visitHistory = [];
        this.diagnoses = [];
        this.feeSummary = null;
        this.expandedPanel = null;
        this.visitTimer = 0;
        this.loadPatientDetail(visit);
        this.loadVisitHistory(visit);
        this.loadFeeSummary(visit);
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
        if (!vm.canEdit || vm.submitting) { return; }
        vm.submitting = true;
        HIS.post('/api/his/visit/save-draft', Object.assign({}, soapData, {
          visitId: visitIdOf(vm.currentVisit)
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
        if (emr && typeof emr.validateRequired === 'function' && !emr.validateRequired()) { return; }
        var soap = emr && emr.soapData ? emr.soapData : {};
        var payload = Object.assign({}, soap, {
          visitId: visitIdOf(vm.currentVisit),
          diagnoses: vm.diagnoses,
          uploadYb: true
        });
        ElementPlus.ElMessageBox.confirm('完成接诊将保存病历与诊断、上传医保2203就诊信息，并转入待收费。是否继续？', '完成接诊确认', {
          type: 'warning', confirmButtonText: '完成接诊', cancelButtonText: '取消'
        }).then(function () {
          vm.submitting = true;
          return HIS.post('/api/his/visit/finish', payload).then(function (saved) {
            vm.currentVisit = saved || Object.assign({}, vm.currentVisit, { visitStatus: 3 });
            HIS.notifySuccess('接诊完成，已进入待收费');
            vm.loadPatientDetail(vm.currentVisit);
            vm.loadFeeSummary(vm.currentVisit);
            vm.refreshQueue();
          });
        }).catch(function (error) {
          if (error !== 'cancel' && error !== 'close' && error && error.message) { HIS.notifyError(error); }
        }).finally(function () { vm.submitting = false; });
      },
      onUpdateDiagnoses: function (diagList) {
        this.diagnoses = Array.isArray(diagList) ? diagList : [];
      },
      onRxSaved: function () {
        this.loadFeeSummary(this.currentVisit);
        this.refreshDocuments();
      },
      onOrderSaved: function () {
        this.loadFeeSummary(this.currentVisit);
        this.refreshDocuments();
      },
      onRequestExpand: function (panelName) {
        var names = { diagnosis: 'diag', prescription: 'rx', orders: 'order' };
        this.expandedPanel = names[panelName] || panelName;
      },
      onRequestCollapse: function () {
        this.expandedPanel = null;
      },
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
          HIS.printRecord({ visit: data.visit, soap: data.record || data.visit, diagnoses: data.diagnoses || vm.diagnoses, patient: vm.currentPatient });
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
      focusPanelSearch: function (panelName) {
        var vm = this;
        if (!vm.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        vm.expandedPanel = panelName;
        vm.$nextTick(function () {
          var panel = panelName === 'rx' ? vm.$refs.rxPanel : vm.$refs.orderPanel;
          if (!panel) { return; }
          if (panelName === 'rx') {
            var control = panel.$refs.rxSearchExpanded || panel.$refs.rxSearch;
            if (control && typeof control.focus === 'function') { control.focus(); return; }
          }
          var input = panel.$el && panel.$el.querySelector('.dw-order-search input');
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
          <div class="dw-patient-summary">
            <template v-if="currentVisit">
              <span class="name">{{ currentVisit.patientName || '-' }}</span>
              <span class="dim">{{ currentVisit.deptName || '-' }} · {{ currentVisit.drName || '-' }}</span>
              <span class="dim">接诊计时 {{ visitDuration }}</span>
            </template>
            <span v-else class="dim">请从候诊队列选择患者</span>
          </div>
          <span v-if="currentVisit" class="dw-fee-badge">本次费用 ￥{{ feeTotal.toFixed(2) }}</span>
          <div class="dw-header-right dw-actions">
            <el-button size="small" @click="startVisit" :disabled="!canStart">F2 接诊</el-button>
            <el-button size="small" @click="saveDraft" :loading="submitting" :disabled="!canEdit">F3 暂存</el-button>
            <el-button size="small" type="primary" @click="finishVisit" :loading="submitting" :disabled="!canEdit">F4 完成</el-button>
            <el-button size="small" @click="refreshQueue">F8 刷新</el-button>
          </div>
        </div>

        <div class="dw-body">
          <dw-queue-panel ref="queuePanel" :class="{'is-collapsed': leftCollapsed}" @select-visit="onSelectVisit" @start-visit="onStartVisit"></dw-queue-panel>

          <div class="dw-center">
            <dw-patient-banner></dw-patient-banner>
            <dw-emr-panel ref="emrPanel" @save-draft="onSaveDraft" @request-expand="onRequestExpand" @request-collapse="onRequestCollapse"></dw-emr-panel>
            <dw-diagnosis-panel @update-diagnoses="onUpdateDiagnoses" @request-expand="onRequestExpand" @request-collapse="onRequestCollapse"></dw-diagnosis-panel>
          </div>

          <div class="dw-right">
            <dw-prescription-panel ref="rxPanel" @rx-saved="onRxSaved" @request-expand="onRequestExpand" @request-collapse="onRequestCollapse" @print-rx="onPrintRx"></dw-prescription-panel>
            <dw-order-panel ref="orderPanel" @order-saved="onOrderSaved" @request-expand="onRequestExpand" @request-collapse="onRequestCollapse" @print-order="onPrintOrder"></dw-order-panel>
            <dw-documents-panel ref="documentsPanel" @print="onPrint" @fee-updated="onFeeUpdated"></dw-documents-panel>
          </div>
        </div>

        <div class="dw-footer">
          <span>F2接诊 F3暂存 F4完成 F5处方 F6检查 F8刷新 | Ctrl+1病历 Ctrl+2诊断 Ctrl+3处方 Ctrl+4检查 Esc还原</span>
          <span class="dw-footer-time" style="margin-left:auto;font-variant-numeric:tabular-nums">{{ currentTime }}</span>
        </div>
      </div>
    `
  };
})();
