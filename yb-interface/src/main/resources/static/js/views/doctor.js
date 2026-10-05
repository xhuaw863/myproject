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
        critAlertedIds: [],
        /* U2 诊疗进度与脏标记: emrSaved=病历已保存(完成/暂存成功), dirtyEmr=本次会话有未保存修改 */
        emrSaved: false,
        dirtyEmr: false,
        /* OP-B 法定报卡主动弹出: 保存诊断(完成接诊)后按触发字典命中的应报卡清单, 非阻断提醒 */
        reportTips: [],
        reportTipVisible: false
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
      },
      /* U2 诊疗进度 chips: 病历/诊断/处方/医嘱 四环节状态一目了然, 点击定位 */
      progChips: function () {
        var vm = this;
        if (!vm.currentVisit) { return []; }
        var finished = Number(vm.currentVisit.visitStatus) === 3;
        var items = [
          { key: 'emr', label: '病历', ok: !!vm.emrSaved },
          { key: 'diag', label: '诊断', ok: vm.diagnoses.length > 0, count: vm.diagnoses.length, hard: true },
          { key: 'rx', label: '处方', ok: vm.rxCount > 0, count: vm.rxCount },
          { key: 'order', label: '医嘱', ok: vm.orderCount > 0, count: vm.orderCount }
        ];
        return items.map(function (it) {
          return {
            key: it.key,
            label: it.label + (it.count ? ' ' + it.count : ''),
            cls: (it.ok || finished) ? 'is-done' : (it.hard ? 'is-danger' : 'is-miss'),
            tip: ((it.ok || finished) ? '已完成: ' : '待处理: ') + it.label
          };
        });
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
        /* 切换前对当前接诊中病历尽力自动暂存, 避免丢失未保存书写; U2: 有脏标记时明确告知而非静默 */
        var emr = vm.$refs.emrPanel;
        if (vm.canEdit && emr && typeof emr.emitSave === 'function') {
          var name = (vm.currentVisit && vm.currentVisit.patientName) || '当前患者';
          if (vm.dirtyEmr) { ElementPlus.ElMessage.info('「' + name + '」病历有未保存修改, 切换前自动暂存…'); }
          try { emr.emitSave(false); } catch (e) {
            if (vm.dirtyEmr) { ElementPlus.ElMessage.warning('「' + name + '」病历自动暂存失败, 可切回该患者按 F3 手动暂存'); }
          }
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
        this.emrSaved = false;
        this.dirtyEmr = false;
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
          vm.emrSaved = true;
          vm.dirtyEmr = false;
          HIS.notifySuccess('病历草稿已暂存(F3)');
        }).catch(function (e) { vm.dirtyEmr = true; HIS.notifyError(e); }).finally(function () { vm.submitting = false; });
      },
      finishVisit: function () {
        var vm = this;
        if (!vm.currentVisit) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.canEdit || vm.submitting) { return; }
        /* U2 F4 预检: 汇总缺项清单; 硬缺项阻断+定位闪烁, 软缺项确认后仍可完成 */
        var issues = vm.collectFinishIssues();
        var hard = issues.filter(function (i) { return i.hard; });
        if (hard.length) {
          ElementPlus.ElMessageBox.alert(
            hard.map(function (i) { return '· ' + i.label; }).join('\n'),
            '完成接诊前存在必填缺项',
            { type: 'warning', confirmButtonText: '定位处理' }
          ).catch(function () { /* alert 无取消路径, 防未处理 rejection */ }).finally(function () { vm.locateIssue(hard[0].key); });
          return;
        }
        var openDisposition = function () {
          /* Tiptap 病历等模式的必检兜底: 书写器 validateForFinish 自带弹错与阻断 */
          var emr = vm.$refs.emrPanel;
          if (emr && typeof emr.validateForFinish === 'function' && !emr.validateForFinish()) { return; }
          /* OP-A 诊后去向: 完成前弹出去向选择(离院/转科/转留观/转院) */
          vm.dispositionForm = { disposition: 1, dispositionDeptId: null, dispositionNote: '' };
          if (!vm.dispositionDepts.length) { vm.loadDispositionDepts(); }
          vm.dispositionVisible = true;
        };
        var soft = issues.filter(function (i) { return !i.hard; });
        if (soft.length) {
          ElementPlus.ElMessageBox.confirm(
            soft.map(function (i) { return '· ' + i.label; }).join('\n') + '\n\n确认仍要完成接诊？',
            '存在非必填缺项',
            { type: 'info', confirmButtonText: '忽略并完成', cancelButtonText: '返回补充' }
          ).then(openDisposition).catch(function () { /* 返回补充: 停留当前 */ });
          return;
        }
        openDisposition();
      },
      /* F4 预检缺项收集: 诊断必选(硬) + 病历结构必存项(硬, 由 emr-panel 提供) + 处方/医嘱全空(软) */
      collectFinishIssues: function () {
        var vm = this;
        var issues = [];
        if (!vm.diagnoses.length) { issues.push({ key: 'diag', label: '未录入诊断', hard: true }); }
        var emr = vm.$refs.emrPanel;
        if (emr && typeof emr.collectFinishIssues === 'function') {
          (emr.collectFinishIssues() || []).forEach(function (miss) {
            issues.push({ key: 'emr', label: '病历：' + miss, hard: true });
          });
        }
        if (!vm.rxCount && !vm.orderCount) { issues.push({ key: 'rx', label: '未开立任何处方与医嘱（纯咨询可忽略）', hard: false }); }
        return issues;
      },
      /* 缺项定位: 切回诊疗页签 → 展开目标面板 → 滚动 + 闪烁一次(.dw-flash) */
      locateIssue: function (key) {
        var vm = this;
        var refMap = { diag: 'diagPanel', emr: 'emrPanel', rx: 'rxPanel', order: 'orderPanel' };
        var selMap = { diag: '.dw-diagnosis-panel', emr: '.dw-emr-panel', rx: '.dw-prescription-panel', order: '.dw-order-panel' };
        if (!selMap[key]) { return; }
        vm.switchTab('clinic');
        var panel = vm.$refs[refMap[key]];
        if (panel && typeof panel.revealForLocate === 'function') { panel.revealForLocate(); }
        vm.$nextTick(function () {
          var el = vm.$el && vm.$el.querySelector(selMap[key]);
          if (!el) { return; }
          if (typeof el.scrollIntoView === 'function') { el.scrollIntoView({ block: 'nearest' }); }
          el.classList.remove('dw-flash');
          void el.offsetWidth;
          el.classList.add('dw-flash');
          window.setTimeout(function () { el.classList.remove('dw-flash'); }, 1800);
        });
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
          /* OP-B 法定报卡主动弹出: 诊断已落库, 按触发字典检查应报卡(非阻断) */
          vm.checkReportTrigger(visitIdOf(vm.currentVisit));
        }).catch(function (error) {
          if (error && error.message) { HIS.notifyError(error); }
        }).finally(function () { vm.submitting = false; });
      },
      /* OP-B 保存诊断后触发判定: 命中法定应报卡则弹非阻断提醒条, 已报/已暂不报的后端自动过滤 */
      checkReportTrigger: function (visitId) {
        var vm = this;
        if (!visitId) { return; }
        HIS.get('/api/his/disease-report/check-trigger?visitId=' + encodeURIComponent(visitId)).then(function (list) {
          var hits = Array.isArray(list) ? list : [];
          if (!hits.length) { return; }
          vm.reportTips = hits;
          vm.reportTipVisible = true;
        }).catch(function () { /* 触发判定为辅助提醒, 失败静默不打断接诊 */ });
      },
      /* 立即报卡: 委托诊断面板按类别开卡(预填患者区+诊断) */
      reportNow: function (tip) {
        var vm = this;
        var panel = vm.$refs.diagPanel;
        if (!panel || typeof panel.openReport !== 'function') {
          HIS.notifyError && HIS.notifyError('报卡面板未就绪');
          return;
        }
        panel.openReport({ diagCode: tip.diagCode, diagName: tip.diagName }, tip.reportCategory);
        vm.reportTipVisible = false;
      },
      /* 暂不报卡: 落 skip 留痕(计入漏报监控), 该卡从提醒清单移除 */
      skipReport: function (tip) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('请填写暂不报卡原因(将计入漏报监控)', '暂不报卡', {
          inputValue: '信息待核实', confirmButtonText: '确定', cancelButtonText: '取消'
        }).then(function (r) {
          return HIS.post('/api/his/disease-report/skip', {
            visitId: visitIdOf(vm.currentVisit), patientId: patientIdOf(vm.currentVisit),
            diagCode: tip.diagCode, diagName: tip.diagName,
            reportCategory: tip.reportCategory, skipReason: (r && r.value) || '暂不报卡'
          });
        }).then(function () {
          vm.reportTips = vm.reportTips.filter(function (t) { return !(t.diagCode === tip.diagCode && t.reportCategory === tip.reportCategory); });
          if (!vm.reportTips.length) { vm.reportTipVisible = false; }
          HIS.notifySuccess && HIS.notifySuccess('已留痕(暂不报卡)');
        }).catch(function () {});
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
          /* U2 进度初始化: 详情已带回病历正文(含旧模式结构化字段)则视为已保存 */
          var rec = (data && (data.soap || data.record)) || {};
          vm.emrSaved = Number(mergedVisit.visitStatus) === 3 ||
            !!(rec.chiefComplaint || rec.presentIllness || rec.physicalExam || rec.content || mergedVisit.content);
          vm.dirtyEmr = false;
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
          <div class="dw-header-left">
            <button class="dw-btn-icon" :title="leftCollapsed ? '展开候诊栏' : '折叠候诊栏'" @click="leftCollapsed=!leftCollapsed">{{ leftCollapsed ? '≫' : '≪' }}</button>
            <span class="dw-header-title">门诊医生工作站</span>
          </div>
          <div class="dw-header-right dw-actions">
            <el-button size="small" @click="openPreConsult" :disabled="!currentVisit">预问诊</el-button>
            <el-button size="small" @click="openVital" :disabled="!currentVisit">生命体征</el-button>
            <el-button size="small" @click="startVisit" :disabled="!canStart">F2 接诊</el-button>
            <el-button size="small" @click="saveDraft" :loading="submitting" :disabled="!canEdit">F3 暂存</el-button>
            <el-button size="small" type="primary" @click="finishVisit" :loading="submitting" :disabled="!canEdit">F4 完成</el-button>
            <el-button size="small" @click="refreshQueue">F8 刷新</el-button>
            <span class="dw-clock">{{ currentTime }}</span>
            <el-popover placement="bottom-end" :width="320" trigger="click" popper-class="dw-help-pop">
              <template #reference><button class="dw-help-btn" title="快捷键帮助">?</button></template>
              <div class="dw-help-row"><kbd>F2 / F3 / F4</kbd><span>接诊 / 暂存病历 / 完成接诊(预检缺项)</span></div>
              <div class="dw-help-row"><kbd>F5 / F6</kbd><span>焦点到药品检索 / 焦点到医嘱检索</span></div>
              <div class="dw-help-row"><kbd>F8</kbd><span>刷新候诊队列</span></div>
              <div class="dw-help-row"><kbd>Ctrl+1~4</kbd><span>切换 诊疗工作台 / 医嘱总览 / 处置与历史 / 报告</span></div>
              <div class="dw-help-row"><kbd>Enter</kbd><span>检索选中即入行, 随后可连续录入剂量</span></div>
              <div class="dw-help-row"><kbd>进度条</kbd><span>顶栏右侧 病历/诊断/处方/医嘱 chips, 点击自动定位缺项</span></div>
            </el-popover>
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
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='clinic'}" role="tab" :aria-selected="activeTab==='clinic'" @click="switchTab('clinic')">诊疗工作台</button>
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='overview'}" role="tab" :aria-selected="activeTab==='overview'" @click="switchTab('overview')">医嘱总览<span class="dw-tab-count" v-if="rxCount || orderCount">{{ rxCount + orderCount }}</span></button>
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='docs'}" role="tab" :aria-selected="activeTab==='docs'" @click="switchTab('docs')">处置与历史</button>
                <button class="dw-tabnav-item" :class="{'is-active': activeTab==='report'}" role="tab" :aria-selected="activeTab==='report'" @click="switchTab('report')">报告<span class="dw-tab-count" v-if="pendingCriticals.length">⚠{{ pendingCriticals.length }}</span></button>
                <div class="dw-prog-chips" v-if="progChips.length">
                  <span v-for="c in progChips" :key="c.key" class="dw-prog-chip" :class="c.cls" :title="c.tip" @click="locateIssue(c.key)">{{ c.label }}</span>
                </div>
              </nav>
            </div>

            <div class="dw-tab-body dw-clinic" v-show="activeTab==='clinic'">
              <div class="dw-col dw-col-left">
                <dw-diagnosis-panel ref="diagPanel" @update-diagnoses="onUpdateDiagnoses" @apply-template="onApplyDiagTemplate"></dw-diagnosis-panel>
                <dw-emr-panel ref="emrPanel" @save-draft="onSaveDraft" @emr-dirty="dirtyEmr = true"></dw-emr-panel>
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

        <!-- OP-B 法定报卡主动弹出提醒(非阻断): 列出应报卡, 立即报卡开对应卡 / 暂不报卡留痕 -->
        <el-dialog v-model="reportTipVisible" title="法定报告卡提醒" width="560px" append-to-body>
          <div class="dim" style="margin-bottom:10px">本次接诊的诊断命中国家法定应报病种, 依据《传染病防治法》等需填报报告卡。可立即报卡, 或暂不报卡(将留痕计入漏报监控)。</div>
          <el-table :data="reportTips" size="small" border max-height="320px">
            <el-table-column type="index" label="#" width="48"></el-table-column>
            <el-table-column prop="diagCode" label="诊断编码" width="120"></el-table-column>
            <el-table-column prop="diagName" label="诊断名称" min-width="140"></el-table-column>
            <el-table-column prop="cardLabel" label="应报卡" min-width="150"></el-table-column>
            <el-table-column label="操作" width="180">
              <template #default="s">
                <el-button size="small" type="primary" @click="reportNow(s.row)">立即报卡</el-button>
                <el-button size="small" @click="skipReport(s.row)">暂不报卡</el-button>
              </template>
            </el-table-column>
          </el-table>
          <template #footer>
            <el-button size="small" @click="reportTipVisible=false">稍后处理</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };
})();
