/* 医生工作站 - 费用汇总、医疗文书与打印中心 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  var CERT_TYPES = [{ v: 1, l: '诊断证明' }, { v: 2, l: '病假条' }, { v: 3, l: '转诊证明' }];
  /* OP-D 14.11 门诊知情同意书五类 */
  var CONSENT_TYPES = [{ v: 1, l: '特殊检查' }, { v: 2, l: '特殊治疗' }, { v: 3, l: '输血' }, { v: 4, l: '自费' }, { v: 5, l: '病危' }];
  var DOC_CARDS = [
    { key: 'admit', label: '住院证', mark: '住', tone: 'blue' },
    { key: 'consult', label: '会诊申请', mark: '会', tone: 'orange' },
    { key: 'cert', label: '诊断证明', mark: '证', tone: 'green' },
    { key: 'consent', label: '知情同意', mark: '同', tone: 'red' },
    { key: 'followup', label: '复诊预约', mark: '复', tone: 'gray' }
  ];

  function raw(v) { return v && Object.prototype.hasOwnProperty.call(v, 'value') ? v.value : v; }
  function money(v) { var n = Number(v); return isFinite(n) ? n.toFixed(2) : '0.00'; }
  function asList(v) { return Array.isArray(v) ? v : ((v && v.records) || []); }
  function blankFee() { return { drug: 0, exam: 0, lab: 0, treatment: 0, total: 0, fund: 0, self: 0 }; }

  var DwDocumentsPanel = {
    name: 'DwDocumentsPanel',
    inject: ['currentVisit', 'currentPatient', 'diagnoses'],
    emits: ['print', 'fee-updated'],
    data: function () {
      return {
        fee: blankFee(), feeLoading: false,
        cards: DOC_CARDS,
        depts: [], orders: [], prescriptions: [],
        admitList: [], consultList: [], certList: [],
        admitVisible: false, consultVisible: false, certVisible: false, followupVisible: false, printVisible: false,
        admitSaving: false, consultSaving: false, certSaving: false, followupSaving: false,
        admitForm: { admitDeptId: null, admitDeptName: '', admitDiagnosis: '', conditionSummary: '', admitPurpose: '', urgency: 1, preFlag: 0 },
        consultForm: { consultDeptId: null, consultDeptName: '', consultPurpose: '', conditionSummary: '', urgency: 1, expectedTime: '' },
        certForm: { certType: 1, diagnosis: '', certContent: '', sickLeaveDays: null, remark: '' },
        followupForm: { followupDate: '', followupNote: '' },
        certTypes: CERT_TYPES,
        /* OP-D 14.11 知情同意书 + 14.8 诊间业务(代办/转诊/绿通/犬伤) */
        consentList: [], consentVisible: false, consentSaving: false,
        consentForm: { consentType: 1, title: '', content: '' },
        consentTypes: CONSENT_TYPES,
        bizVisible: false, bizTab: 'agent', bizSaving: false, printingAll: false,
        agentList: [], referralList: [], greenList: [], dogbiteList: [],
        agentForm: { agentName: '', relation: '', agentIdCard: '', agentPhone: '', reason: '' },
        referralForm: { direction: 1, toHospital: '', toDept: '', reason: '', contactPhone: '' },
        greenForm: { creditLimit: 500, reason: '' },
        dogbiteForm: { exposeTime: '', animalType: '犬', woundGrade: 2, woundParts: '', woundHandling: '', vaccinePlan: '五针法', vaccineNextDate: '', immunoglobulin: 0, dogInfo: '' }
      };
    },
    computed: {
      visit: function () { return raw(this.currentVisit) || null; },
      patient: function () { return raw(this.currentPatient) || {}; },
      diagList: function () { return raw(this.diagnoses) || []; },
      visitId: function () { return this.visit && (this.visit.id || this.visit.visitId); },
      /* 患者维度ID必须取 visit.patientId(currentPatient 由 visit 合并而来, 其 .id 是就诊ID) */
      patientId: function () { return this.visit && this.visit.patientId; },
      canEdit: function () { return !!this.visit && Number(this.visit.visitStatus) === 2; },
      mainDiagnosis: function () {
        var main = this.diagList.find(function (d) { return String(d.maindiagFlag) === '1'; }) || this.diagList[0];
        return main ? (main.diagName || main.name || '') : '';
      },
      deptOpts: function () { return this.depts.filter(function (d) { return Number(d.deptLevel) === 2 || d.deptLevel == null; }); },
      /* 拟收科室候选: 与住院登记页同口径(大类含"住院"的科室级), 无匹配时回退全部科室级; 修复药剂科等医技/行政科室可选问题 */
      inpDeptOpts: function () {
        var all = this.depts || [];
        var hit = all.filter(function (d) { return (d.deptCategory || '').indexOf('住院') >= 0 && d.deptLevel !== 1; });
        if (hit.length) { return hit; }
        return all.filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      /* 入院诊断候选: 本次就诊已录诊断(去重), 支持字典外手工补录 */
      diagOpts: function () {
        var seen = {};
        return this.diagList.map(function (d) { return d.diagName || d.name || ''; })
          .filter(function (n) { if (!n || seen[n]) { return false; } seen[n] = 1; return true; });
      },
      followupCount: function () { return this.visit && this.visit.followupDate ? 1 : 0; },
      printableOrders: function () { return this.orders.filter(function (o) { return o.orderType !== '治疗'; }); },
      activeAdmits: function () { return this.admitList.filter(function (x) { return Number(x.status) !== 3; }); },
      feeItems: function () {
        return [
          { key: 'drug', label: '药品费', amount: this.fee.drug, color: 'var(--dw-primary)' },
          { key: 'exam', label: '检查费', amount: this.fee.exam, color: 'var(--dw-warning)' },
          { key: 'lab', label: '检验费', amount: this.fee.lab, color: 'var(--dw-success)' },
          { key: 'treatment', label: '治疗费', amount: this.fee.treatment, color: 'var(--dw-text-hint)' }
        ];
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler: function (id, oldId) {
          if (id === oldId && oldId !== undefined) { return; }
          this.clearLoaded();
          if (id) { this.refreshAll(); }
        }
      }
    },
    created: function () { this.loadDepts(); },
    methods: {
      money: money,
      /* 性别容错翻译: 兼容码值(1/2)与文本(男/女) */
      genderText: function (g) { return Number(g) === 1 || g === '男' ? '男' : (Number(g) === 2 || g === '女' ? '女' : '未知'); },
      todayStr: function () { var d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); },
      clearLoaded: function () {
        this.fee = blankFee(); this.orders = []; this.prescriptions = [];
        this.admitList = []; this.consultList = []; this.certList = []; this.consentList = [];
      },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (d) { vm.depts = asList(d); }).catch(function () { vm.depts = []; });
      },
      refreshAll: function () {
        var vm = this;
        return Promise.all([vm.loadFee(), vm.loadDocuments(), vm.loadPrintables()]);
      },
      loadFee: function () {
        var vm = this;
        if (!vm.visitId) { vm.fee = blankFee(); return Promise.resolve(vm.fee); }
        vm.feeLoading = true;
        return Promise.all([
          HIS.get('/api/his/visit/fee-summary?visitId=' + encodeURIComponent(vm.visitId)),
          HIS.get('/api/his/order/list?visitId=' + encodeURIComponent(vm.visitId))
        ]).then(function (values) {
          var summary = values[0] || {}; var rows = asList(values[1]);
          var byType = { '检查': 0, '检验': 0, '治疗': 0 };
          rows.forEach(function (o) {
            if (Number(o.status) >= 0 && byType[o.orderType] != null) { byType[o.orderType] += Number(o.totalAmount) || 0; }
          });
          var orderFallback = Number(summary.orderTotal) || 0;
          var splitTotal = byType['检查'] + byType['检验'] + byType['治疗'];
          if (!splitTotal && orderFallback) { byType['检查'] = orderFallback; }
          var drug = Number(summary.drugTotal != null ? summary.drugTotal : summary.rxTotal) || 0;
          var exam = Number(summary.examTotal != null ? summary.examTotal : byType['检查']) || 0;
          var lab = Number(summary.labTotal != null ? summary.labTotal : byType['检验']) || 0;
          var treatment = Number(summary.treatmentTotal != null ? summary.treatmentTotal : byType['治疗']) || 0;
          var total = Number(summary.totalFee);
          if (!isFinite(total)) { total = drug + exam + lab + treatment; }
          vm.fee = { drug: drug, exam: exam, lab: lab, treatment: treatment, total: total, fund: total * 0.7, self: total * 0.3 };
          vm.$emit('fee-updated', Object.assign({}, vm.fee));
          return vm.fee;
        }).catch(function (e) {
          vm.fee = blankFee(); if (HIS.notifyError) { HIS.notifyError(e); }
          return vm.fee;
        }).finally(function () { vm.feeLoading = false; });
      },
      loadDocuments: function () {
        var vm = this;
        if (!vm.visitId) { return Promise.resolve(); }
        return Promise.all([
          HIS.get('/api/his/admission-cert/list?visitId=' + encodeURIComponent(vm.visitId)),
          HIS.get('/api/his/consult/list?visitId=' + encodeURIComponent(vm.visitId)),
          HIS.get('/api/his/medical-cert/list?visitId=' + encodeURIComponent(vm.visitId)),
          HIS.get('/api/his/outp-biz/consent/list?visitId=' + encodeURIComponent(vm.visitId))
        ]).then(function (values) {
          vm.admitList = asList(values[0]); vm.consultList = asList(values[1]); vm.certList = asList(values[2]); vm.consentList = asList(values[3]);
        }).catch(function (e) { if (HIS.notifyError) { HIS.notifyError(e); } });
      },
      loadPrintables: function () {
        var vm = this;
        if (!vm.visitId) { return Promise.resolve(); }
        return Promise.all([
          HIS.get('/api/his/prescription/list?visitId=' + encodeURIComponent(vm.visitId)),
          HIS.get('/api/his/order/list?visitId=' + encodeURIComponent(vm.visitId))
        ]).then(function (values) { vm.prescriptions = asList(values[0]); vm.orders = asList(values[1]); })
          .catch(function (e) { if (HIS.notifyError) { HIS.notifyError(e); } });
      },
      docCount: function (key) {
        if (key === 'admit') { return this.admitList.filter(function (x) { return Number(x.status) !== 3; }).length; }
        if (key === 'consult') { return this.consultList.length; }
        if (key === 'cert') { return this.certList.length; }
        if (key === 'consent') { return this.consentList.filter(function (x) { return Number(x.status) !== 3; }).length; }
        return this.followupCount;
      },
      openDocument: function (key) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (key === 'admit') { this.openAdmit(); }
        else if (key === 'consult') { this.openConsult(); }
        else if (key === 'cert') { this.openCert(); }
        else if (key === 'consent') { this.openConsent(); }
        else { this.openFollowup(); }
      },
      openAdmit: function () {
        this.admitForm = { admitDeptId: null, admitDeptName: '', admitDiagnosis: this.mainDiagnosis, conditionSummary: this.visit.presentIllness || this.visit.chiefComplaint || '', admitPurpose: '', urgency: 1, preFlag: 0 };
        this.admitVisible = true;
      },
      openConsult: function () {
        this.consultForm = { consultDeptId: null, consultDeptName: '', consultPurpose: '', conditionSummary: this.visit.presentIllness || this.visit.chiefComplaint || '', urgency: 1, expectedTime: '' };
        this.consultVisible = true;
      },
      openCert: function () {
        this.certForm = { certType: 1, diagnosis: this.mainDiagnosis, certContent: '', sickLeaveDays: null, remark: '' };
        this.certVisible = true;
      },
      openFollowup: function () {
        this.followupForm = { followupDate: this.visit.followupDate ? String(this.visit.followupDate).slice(0, 10) : '', followupNote: this.visit.followupNote || '' };
        this.followupVisible = true;
      },
      deptNameOf: function (id) {
        var d = this.depts.find(function (x) { return x.id === id; }); return d ? d.deptName : '';
      },
      onAdmitDeptChange: function (id) { this.admitForm.admitDeptName = this.deptNameOf(id); },
      onConsultDeptChange: function (id) { this.consultForm.consultDeptName = this.deptNameOf(id); },
      submitAdmit: function () {
        var vm = this; var f = vm.admitForm;
        if (!f.admitDeptId || !f.admitDiagnosis) { ElementPlus.ElMessage.warning('拟收科室和入院诊断必填'); return; }
        var pending = vm.admitList.filter(function (x) { return Number(x.status) === 1; });
        var pre = pending.length
          ? ElementPlus.ElMessageBox.confirm('本次就诊已有 ' + pending.length + ' 张未入院的住院证, 继续开具将重复发证(持证入院登记时会自动核销)。确认继续?', '重复开证提醒', { type: 'warning', confirmButtonText: '继续开具', cancelButtonText: '取消' })
          : Promise.resolve();
        vm.admitSaving = true;
        pre.then(function () {
          return HIS.post('/api/his/admission-cert/create', Object.assign({ visitId: vm.visitId }, f));
        }).then(function (cert) {
          ElementPlus.ElMessage.success('住院证已开具'); vm.admitVisible = false;
          return vm.loadDocuments().then(function () {
            /* 商业习惯: 开证后立即交付纸质凭证, 提示打印(可跳过, 打印中心随时可补打);
             * 必须等开证 dialog 关闭动画结束后再弹, 同 tick 弹会被 dialog 销毁链连带吞掉(实测) */
            if (cert && cert.id) {
              setTimeout(function () {
                ElementPlus.ElMessageBox.confirm('是否立即打印住院证(患者持证办理入院)?', '开具成功', { type: 'success', confirmButtonText: '打印住院证', cancelButtonText: '暂不打印', distinguishCancelAndClose: true })
                  .then(function () { vm.emitPrint('admission', cert.id); })
                  .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
              }, 450);
            }
          });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } }).finally(function () { vm.admitSaving = false; });
      },
      /* 住院证状态码翻译(1已开具 2已入院 3已作废) */
      admitStatusText: function (s) { return Number(s) === 2 ? '已入院' : (Number(s) === 3 ? '已作废' : '待入院'); },
      admitStatusTone: function (s) { return Number(s) === 2 ? 'success' : (Number(s) === 3 ? 'info' : 'warning'); },
      /* 作废住院证: 仅待入院(1)可作废; 已入院由住院登记消费不可作废 */
      cancelAdmit: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认作废住院证「' + (row.admitDeptName || '') + ' · ' + (row.admitDiagnosis || '') + '」? 作废后患者不能持证入院。', '作废确认', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/admission-cert/cancel?id=' + encodeURIComponent(row.id)); })
          .then(function () { HIS.notifySuccess('住院证已作废'); return vm.loadDocuments(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      submitConsult: function () {
        var vm = this; var f = vm.consultForm;
        if (!f.consultDeptId || !f.consultPurpose) { ElementPlus.ElMessage.warning('会诊科室和会诊目的必填'); return; }
        vm.consultSaving = true;
        HIS.post('/api/his/consult/create', Object.assign({ visitId: vm.visitId }, f)).then(function () {
          ElementPlus.ElMessage.success('会诊申请已发起'); vm.consultVisible = false; return vm.loadDocuments();
        }).catch(HIS.notifyError).finally(function () { vm.consultSaving = false; });
      },
      submitCert: function () {
        var vm = this; var f = vm.certForm;
        if (!f.certContent) { ElementPlus.ElMessage.warning('证明内容必填'); return; }
        vm.certSaving = true;
        HIS.post('/api/his/medical-cert/create', Object.assign({ visitId: vm.visitId }, f)).then(function (cert) {
          var pending = cert && Number(cert.auditStatus) === 1;
          ElementPlus.ElMessage.success(pending ? '证明已开具, 已提交科室审核(通过后仍可打印)' : '证明已开具'); vm.certVisible = false; return vm.loadDocuments();
        }).catch(HIS.notifyError).finally(function () { vm.certSaving = false; });
      },
      draftBody: function () {
        var v = this.visit || {};
        return {
          visitId: this.visitId,
          chiefComplaint: v.chiefComplaint || '', presentIllness: v.presentIllness || '',
          pastHistory: v.pastHistory || '', allergyHistory: v.allergyHistory || '',
          physicalExam: v.physicalExam || '', auxExam: v.auxExam || v.auxiliaryExam || '',
          treatmentOpinion: v.treatmentOpinion || '', diseTypeCode: v.diseTypeCode || '',
          birctrlType: v.birctrlType || '', birctrlMatnDate: v.birctrlMatnDate || '',
          followupDate: this.followupForm.followupDate, followupNote: this.followupForm.followupNote
        };
      },
      pastDate: function (d) {
        if (!d) { return false; } var day = new Date(d); var now = new Date(); now.setHours(0, 0, 0, 0); return day.getTime() < now.getTime();
      },
      submitFollowup: function () {
        var vm = this;
        if (!vm.followupForm.followupDate) { ElementPlus.ElMessage.warning('请选择复诊日期'); return; }
        vm.followupSaving = true;
        HIS.post('/api/his/visit/save-draft', vm.draftBody()).then(function () {
          ElementPlus.ElMessage.success('复诊预约已保存');
          vm.visit.followupDate = vm.followupForm.followupDate; vm.visit.followupNote = vm.followupForm.followupNote;
          vm.followupVisible = false;
        }).catch(HIS.notifyError).finally(function () { vm.followupSaving = false; });
      },
      certTypeLabel: function (v) { var c = CERT_TYPES.find(function (x) { return x.v === Number(v); }); return c ? c.l : '诊断证明'; },
      /* OP-B 诊断证明审核流: 状态码翻译(0无须 1待审 2通过 3驳回) + 审核动作(通过/驳回带原因) */
      certAuditText: function (s) { return ({ 1: '待审核', 2: '已通过', 3: '已驳回' })[Number(s)] || '无须'; },
      certAuditTone: function (s) { return ({ 1: 'warning', 2: 'success', 3: 'danger' })[Number(s)] || 'info'; },
      auditCert: function (row, pass) {
        var vm = this;
        var doAudit = function (remark) {
          return HIS.post('/api/his/medical-cert/audit?id=' + encodeURIComponent(row.id) + '&pass=' + (pass ? 'true' : 'false') + '&remark=' + encodeURIComponent(remark || '')).then(function () {
            HIS.notifySuccess(pass ? '证明已审核通过, 可打印' : '证明已驳回');
            return vm.loadDocuments();
          });
        };
        if (pass) { doAudit('').catch(function (e) { if (HIS.notifyError) { HIS.notifyError(e); } }); return; }
        ElementPlus.ElMessageBox.prompt('请输入驳回原因', '驳回诊断证明', { inputPlaceholder: '如: 诊断与证明内容不符', closeOnClickModal: false })
          .then(function (r) { return doAudit(r.value); })
          .catch(function () {});
      },
      barWidth: function (amount) { return this.fee.total > 0 ? Math.max(0, Number(amount) * 100 / this.fee.total).toFixed(2) + '%' : '0%'; },
      emitPrint: function (type, id) { this.$emit('print', { type: type, id: id }); },
      openPrintCenter: function () { if (!this.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; } this.loadPrintables(); this.printVisible = true; },
      /* ===== OP-D 14.9 会诊状态翻译 ===== */
      consultStatusText: function (s) { return ({ 1: '已申请', 2: '已接受', 3: '已完成', 4: '已拒绝' })[Number(s)] || '待处理'; },
      consultStatusTone: function (s) { return ({ 1: 'warning', 2: 'primary', 3: 'success', 4: 'danger' })[Number(s)] || 'info'; },
      /* ===== OP-D 14.11 知情同意书 ===== */
      consentTypeLabel: function (v) { var c = CONSENT_TYPES.find(function (x) { return x.v === Number(v); }); return c ? c.l : '知情同意'; },
      consentStatusText: function (s) { return ({ 1: '待签', 2: '已签', 3: '已撤销' })[Number(s)] || '待签'; },
      consentStatusTone: function (s) { return ({ 1: 'warning', 2: 'success', 3: 'info' })[Number(s)] || 'info'; },
      openConsent: function () {
        this.consentForm = { consentType: 1, title: '', content: '' };
        this.consentVisible = true;
      },
      submitConsent: function () {
        var vm = this; var f = vm.consentForm;
        if (!f.content) { ElementPlus.ElMessage.warning('告知事项内容必填'); return; }
        vm.consentSaving = true;
        HIS.post('/api/his/outp-biz/consent/create', Object.assign({ visitId: vm.visitId }, f, { title: f.title || (vm.consentTypeLabel(f.consentType) + '知情同意书') })).then(function () {
          ElementPlus.ElMessage.success('同意书已开具(待患者签署)'); vm.consentVisible = false; return vm.loadDocuments();
        }).catch(HIS.notifyError).finally(function () { vm.consentSaving = false; });
      },
      signConsent: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('请输入患者/家属签署姓名', '同意书签署', { inputPlaceholder: '如: 张三(本人)', closeOnClickModal: false })
          .then(function (r) {
            var name = String(r.value || '').trim();
            var m = name.match(/^[^（(]*[（(](本人|配偶|父母|子女|其他)[）)]$/);
            return HIS.post('/api/his/outp-biz/consent/' + encodeURIComponent(row.id) + '/sign', {
              patientSignName: name.replace(/[（(].*$/, '') || name, relation: m ? m[1] : '本人'
            });
          }).then(function () {
            HIS.notifySuccess('同意书签署完成'); return vm.loadDocuments();
          }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      cancelConsent: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认撤销该知情同意书? 撤销后需重新开具。', '撤销确认', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/outp-biz/consent/' + encodeURIComponent(row.id) + '/cancel'); })
          .then(function () { HIS.notifySuccess('同意书已撤销'); return vm.loadDocuments(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ===== OP-D 14.8 诊间业务(代办/转诊/绿通/犬伤) ===== */
      openBiz: function () {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        this.bizVisible = true;
        this.loadBizAll();
      },
      loadBizAll: function () {
        var vm = this;
        if (!vm.visitId) { return Promise.resolve(); }
        return Promise.all([
          HIS.get('/api/his/outp-biz/agent/list?visitId=' + encodeURIComponent(vm.visitId)).then(function (d) { vm.agentList = asList(d); }).catch(function () { vm.agentList = []; }),
          HIS.get('/api/his/outp-biz/referral/list?patientId=' + encodeURIComponent(vm.patientId)).then(function (d) { vm.referralList = asList(d); }).catch(function () { vm.referralList = []; }),
          HIS.get('/api/his/outp-biz/green/list?patientId=' + encodeURIComponent(vm.patientId)).then(function (d) { vm.greenList = asList(d); }).catch(function () { vm.greenList = []; }),
          HIS.get('/api/his/outp-biz/dogbite/list?visitId=' + encodeURIComponent(vm.visitId)).then(function (d) { vm.dogbiteList = asList(d); }).catch(function () { vm.dogbiteList = []; })
        ]);
      },
      submitAgent: function () {
        var vm = this; var f = vm.agentForm;
        if (!f.agentName || !f.relation) { ElementPlus.ElMessage.warning('代办人姓名与关系必填'); return; }
        vm.bizSaving = true;
        HIS.post('/api/his/outp-biz/agent/create', Object.assign({ visitId: vm.visitId }, f)).then(function () {
          ElementPlus.ElMessage.success('代办登记已保存'); vm.agentForm = { agentName: '', relation: '', agentIdCard: '', agentPhone: '', reason: '' }; return vm.loadBizAll();
        }).catch(HIS.notifyError).finally(function () { vm.bizSaving = false; });
      },
      submitReferral: function () {
        var vm = this; var f = vm.referralForm;
        if (!f.toHospital) { ElementPlus.ElMessage.warning('目标医院必填'); return; }
        vm.bizSaving = true;
        HIS.post('/api/his/outp-biz/referral/create', Object.assign({ visitId: vm.visitId, patientId: vm.patientId }, f)).then(function () {
          ElementPlus.ElMessage.success('转诊申请已发起'); vm.referralForm = { direction: 1, toHospital: '', toDept: '', reason: '', contactPhone: '' }; return vm.loadBizAll();
        }).catch(HIS.notifyError).finally(function () { vm.bizSaving = false; });
      },
      referralStatusText: function (s) { return ({ 1: '已申请', 2: '已接收', 3: '已完成', 4: '已取消' })[Number(s)] || '已申请'; },
      referralAdvance: function (row, next) {
        var vm = this;
        HIS.post('/api/his/outp-biz/referral/' + encodeURIComponent(row.id) + '/status?status=' + encodeURIComponent(next)).then(function () {
          HIS.notifySuccess('转诊状态已更新'); return vm.loadBizAll();
        }).catch(HIS.notifyError);
      },
      submitGreen: function () {
        var vm = this; var f = vm.greenForm;
        if (!f.creditLimit || Number(f.creditLimit) <= 0) { ElementPlus.ElMessage.warning('信用额度需大于0'); return; }
        vm.bizSaving = true;
        HIS.post('/api/his/outp-biz/green/create', { patientId: vm.patientId, patientName: vm.visit && vm.visit.patientName, visitId: vm.visitId, creditLimit: Number(f.creditLimit), reason: f.reason }).then(function () {
          ElementPlus.ElMessage.success('绿通额度已开通'); vm.greenForm = { creditLimit: 500, reason: '' }; return vm.loadBizAll();
        }).catch(HIS.notifyError).finally(function () { vm.bizSaving = false; });
      },
      revokeGreen: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认关闭该绿通额度?', '关闭确认', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/outp-biz/green/' + encodeURIComponent(row.id) + '/revoke'); })
          .then(function () { HIS.notifySuccess('额度已关闭'); return vm.loadBizAll(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      submitDogbite: function () {
        var vm = this; var f = vm.dogbiteForm;
        if (!f.woundParts) { ElementPlus.ElMessage.warning('暴露部位必填'); return; }
        vm.bizSaving = true;
        HIS.post('/api/his/outp-biz/dogbite/create', Object.assign({ visitId: vm.visitId }, f)).then(function () {
          ElementPlus.ElMessage.success('犬伤登记已保存'); vm.dogbiteForm = { exposeTime: '', animalType: '犬', woundGrade: 2, woundParts: '', woundHandling: '', vaccinePlan: '五针法', vaccineNextDate: '', immunoglobulin: 0, dogInfo: '' }; return vm.loadBizAll();
        }).catch(HIS.notifyError).finally(function () { vm.bizSaving = false; });
      },
      dogbiteGradeText: function (g) { return ({ 1: 'Ⅰ级', 2: 'Ⅱ级', 3: 'Ⅲ级' })[Number(g)] || '-'; },
      /* ===== OP-D 14.7 一键全打: 聚合本就诊全部单据到同一打印窗口逐张分页 ===== */
      printAllDocs: function () {
        var vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!HIS.buildDocHtml || !HIS.printSheets) { ElementPlus.ElMessage.warning('打印组件未加载'); return; }
        vm.printingAll = true;
        var tasks = [];
        tasks.push(HIS.get('/api/his/visit/detail?id=' + encodeURIComponent(vm.visitId)).then(function (d) {
          return HIS.buildDocHtml.record({ visit: d.visit, soap: d.soap || d.record || d.visit, diagnoses: d.diagnoses || vm.diagList, patient: d.patient || vm.patient });
        }));
        vm.prescriptions.forEach(function (rx) {
          tasks.push(HIS.get('/api/his/prescription/print-data?id=' + encodeURIComponent(rx.id)).then(function (d) { return HIS.buildDocHtml.rx(d); }));
        });
        vm.printableOrders.forEach(function (o) {
          tasks.push(HIS.get('/api/his/order/print-data?id=' + encodeURIComponent(o.id)).then(function (d) { return HIS.buildDocHtml.order(d); }));
        });
        vm.activeAdmits.forEach(function (a) {
          tasks.push(HIS.get('/api/his/admission-cert/print-data?id=' + encodeURIComponent(a.id)).then(function (d) { return HIS.buildDocHtml.cert(d); }));
        });
        vm.certList.forEach(function (c) {
          tasks.push(HIS.get('/api/his/medical-cert/print-data?id=' + encodeURIComponent(c.id)).then(function (d) { return HIS.buildDocHtml.cert(d); }));
        });
        Promise.allSettled(tasks).then(function (results) {
          var sheets = [];
          results.forEach(function (r) { if (r.status === 'fulfilled' && r.value) { sheets.push({ html: r.value }); } });
          if (!sheets.length) { ElementPlus.ElMessage.warning('没有可打印的单据'); return; }
          HIS.printSheets(sheets);
          var failed = results.length - sheets.length;
          if (failed > 0) { ElementPlus.ElMessage.info(failed + ' 张单据取数失败已跳过'); }
        }).finally(function () { vm.printingAll = false; });
      }
    },
    template: `
      <div class="dw-documents-panel">
        <style>
          .dw-documents-panel{margin-top:12px}.dw-documents-panel .dw-doc-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.dw-documents-panel .dw-doc-grid .el-badge{width:100%}.dw-documents-panel .dw-doc-card{position:relative;display:flex;align-items:center;gap:10px;width:100%;padding:10px;border:1px solid var(--dw-border);border-radius:4px;background:var(--dw-card);cursor:pointer;text-align:left}.dw-documents-panel .dw-doc-card:hover{border-color:var(--dw-primary);background:var(--dw-primary-light)}.dw-documents-panel .dw-doc-mark{display:flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:3px;background:var(--dw-primary);color:#fff;font-size:16px;font-weight:700}.dw-documents-panel .tone-orange .dw-doc-mark{background:var(--dw-warning)}.dw-documents-panel .tone-green .dw-doc-mark{background:var(--dw-success)}.dw-documents-panel .tone-gray .dw-doc-mark{background:var(--dw-text-secondary)}.dw-documents-panel .dw-doc-name{font-weight:600;color:var(--dw-text)}.dw-documents-panel .dw-doc-hint{font-size:11px;color:var(--dw-text-hint)}.dw-documents-panel .dw-fee-legend{display:grid;grid-template-columns:1fr 1fr;gap:3px 16px;margin-top:6px}.dw-documents-panel .dw-fee-legend span{display:flex;justify-content:space-between;color:var(--dw-text-secondary);font-size:12px}.dw-documents-panel .dw-insu-estimate{display:flex;justify-content:space-between;margin-top:7px;padding-top:7px;border-top:1px dashed var(--dw-border);color:var(--dw-text-hint);font-size:11px}.dw-documents-panel .dw-print-row{display:flex;align-items:center;gap:8px;padding:8px 4px;border-bottom:1px solid var(--dw-border)}.dw-documents-panel .dw-print-row .name{flex:1}.dw-documents-panel .dw-print-group{margin:10px 0;color:var(--dw-primary-dark);font-weight:700}.dw-documents-panel .dw-cert-patient{display:flex;align-items:center;gap:14px;margin:-6px 0 12px;padding:8px 12px;border:1px solid var(--dw-border);border-left:3px solid var(--dw-primary);border-radius:4px;background:var(--dw-card);font-size:12px;color:var(--dw-text-secondary)}.dw-documents-panel .dw-cert-patient b{font-size:14px;color:var(--dw-text)}.dw-documents-panel .dw-cert-patient .no{margin-left:auto;font-family:monospace}.dw-documents-panel .dw-cert-sign{font-size:13px;color:var(--dw-text)}.dw-documents-panel .tone-red .dw-doc-mark{background:var(--el-color-danger,#f56c6c)}.dw-documents-panel .dw-biz-form{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}
        </style>
        <div class="dw-fee-summary" v-loading="feeLoading">
          <div class="fs-row total"><span>本次费用合计</span><b>￥{{ money(fee.total) }}</b></div>
          <div class="dw-fee-chart" aria-label="费用构成"><span v-for="x in feeItems" :key="x.key" class="fs-bar" :style="{width:barWidth(x.amount),background:x.color}" :title="x.label+' ￥'+money(x.amount)"></span></div>
          <div class="dw-fee-legend"><span v-for="x in feeItems" :key="x.key"><i>{{ x.label }}</i><b>￥{{ money(x.amount) }}</b></span></div>
          <div class="dw-insu-estimate"><span>医保预估统筹（70%） ￥{{ money(fee.fund) }}</span><span>个人（30%） ￥{{ money(fee.self) }}</span></div>
        </div>
        <div class="dw-section">医疗文书<span class="dw-section-extra">DOCUMENTS</span></div>
        <div class="dw-doc-grid">
          <el-badge v-for="card in cards" :key="card.key" :value="docCount(card.key)" :hidden="!docCount(card.key)"><button type="button" class="dw-doc-card" :class="'tone-'+card.tone" :disabled="!canEdit" @click="openDocument(card.key)"><span class="dw-doc-mark">{{ card.mark }}</span><span><span class="dw-doc-name">{{ card.label }}</span><br><span class="dw-doc-hint">{{ docCount(card.key) ? '已开具 '+docCount(card.key)+' 份' : '点击开具' }}</span></span></button></el-badge>
        </div>
        <div class="dw-action-group" style="display:flex;gap:8px"><el-button type="primary" plain style="flex:1" :disabled="!visitId" @click="openPrintCenter">打印中心</el-button><el-button plain style="flex:1" :disabled="!canEdit" @click="openBiz">诊间业务</el-button></div>

        <el-dialog v-model="admitVisible" title="开具住院证" width="660px">
          <div class="dw-cert-patient"><b>{{ (visit&&visit.patientName) || patient.name || '-' }}</b><span>{{ genderText(visit&&visit.gender) }} · {{ (visit&&visit.age) != null ? visit.age + '岁' : '年龄-' }}</span><span>门诊: {{ (visit&&visit.deptName) || '-' }} · {{ (visit&&visit.drName) || '-' }}</span><span class="no">门诊号 {{ (visit&&visit.iptOtpNo) || '-' }}</span></div>
          <el-form :model="admitForm" label-width="92px">
            <el-form-item label="拟收科室" required><el-select v-model="admitForm.admitDeptId" filterable placeholder="仅可选住院科室(与入院登记页同口径)" style="width:100%" @change="onAdmitDeptChange"><el-option v-for="d in inpDeptOpts" :key="d.id" :label="d.deptName + (d.deptCategory ? ' · ' + d.deptCategory : '')" :value="d.id"></el-option></el-select></el-form-item>
            <el-form-item label="入院诊断" required><el-select v-model="admitForm.admitDiagnosis" filterable allow-create default-first-option placeholder="下拉选本次就诊诊断, 也可直接输入后回车" style="width:100%"><el-option v-for="(n, i) in diagOpts" :key="i" :label="n" :value="n"></el-option></el-select><div v-if="!diagOpts.length" class="dim" style="font-size:11px;line-height:16px">本次就诊尚未录入诊断, 建议先在诊断页签开诊断后再开证(此处也可直接输入)</div></el-form-item>
            <el-form-item label="病情摘要"><el-input v-model="admitForm.conditionSummary" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="已自动带入现病史/主诉, 可补充查体与辅检结论"></el-input></el-form-item>
            <el-form-item label="入院目的"><el-input v-model="admitForm.admitPurpose" maxlength="100" placeholder="如: 进一步检查治疗 / 手术治疗"></el-input></el-form-item>
            <el-form-item label="紧急程度"><el-radio-group v-model="admitForm.urgency"><el-radio-button :label="1">普通</el-radio-button><el-radio-button :label="2">急</el-radio-button><el-radio-button :label="3">危急</el-radio-button></el-radio-group><el-checkbox v-model="admitForm.preFlag" :true-label="1" :false-label="0" style="margin-left:14px">预开卡锁床(暂无空床先开证)</el-checkbox></el-form-item>
            <el-form-item label="开证医师"><span class="dw-cert-sign">{{ (visit&&visit.drName) || '-' }}　{{ todayStr() }}　<span class="dim">(随证打印, 持证入院登记时自动预填)</span></span></el-form-item>
          </el-form>
          <el-table v-if="admitList.length" :data="admitList" border size="small" max-height="160"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column prop="admitDeptName" label="拟收科室" width="110"></el-table-column><el-table-column prop="admitDiagnosis" label="入院诊断" show-overflow-tooltip></el-table-column><el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag :type="admitStatusTone(s.row.status)" size="small">{{ admitStatusText(s.row.status) }}</el-tag></template></el-table-column><el-table-column label="操作" width="64" align="center"><template #default="s"><el-button v-if="Number(s.row.status)===1" link type="danger" size="small" @click="cancelAdmit(s.row)">作废</el-button><span v-else class="dim">—</span></template></el-table-column></el-table>
          <template #footer><el-button @click="admitVisible=false">关闭</el-button><el-button type="primary" :loading="admitSaving" @click="submitAdmit">开具</el-button></template>
        </el-dialog>

        <el-dialog v-model="consultVisible" title="会诊申请" width="620px">
          <el-form :model="consultForm" label-width="92px"><el-form-item label="会诊科室" required><el-select v-model="consultForm.consultDeptId" filterable style="width:100%" @change="onConsultDeptChange"><el-option v-for="d in deptOpts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item><el-form-item label="会诊目的" required><el-input v-model="consultForm.consultPurpose" maxlength="200"></el-input></el-form-item><el-form-item label="病情摘要"><el-input v-model="consultForm.conditionSummary" type="textarea" :rows="3" maxlength="500"></el-input></el-form-item><el-form-item label="紧急程度"><el-radio-group v-model="consultForm.urgency"><el-radio-button :label="1">普通</el-radio-button><el-radio-button :label="2">急</el-radio-button><el-radio-button :label="3">紧急</el-radio-button></el-radio-group></el-form-item><el-form-item label="期望时间"><el-date-picker v-model="consultForm.expectedTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%"></el-date-picker></el-form-item></el-form>
          <el-table v-if="consultList.length" :data="consultList" border size="small" max-height="160"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column prop="consultDeptName" label="会诊科室" width="120"></el-table-column><el-table-column prop="consultPurpose" label="会诊目的" show-overflow-tooltip></el-table-column><el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag :type="consultStatusTone(s.row.status)" size="small">{{ consultStatusText(s.row.status) }}</el-tag></template></el-table-column></el-table>
          <template #footer><el-button @click="consultVisible=false">关闭</el-button><el-button type="primary" :loading="consultSaving" @click="submitConsult">发起</el-button></template>
        </el-dialog>

        <el-dialog v-model="certVisible" title="诊断证明" width="620px">
          <el-form :model="certForm" label-width="92px"><el-form-item label="证明类型"><el-radio-group v-model="certForm.certType"><el-radio-button v-for="c in certTypes" :key="c.v" :label="c.v">{{ c.l }}</el-radio-button></el-radio-group></el-form-item><el-form-item label="诊断"><el-input v-model="certForm.diagnosis" maxlength="300"></el-input></el-form-item><el-form-item label="证明内容" required><el-input v-model="certForm.certContent" type="textarea" :rows="4" maxlength="500"></el-input></el-form-item><el-form-item label="病假天数"><el-input-number v-model="certForm.sickLeaveDays" :min="1" :max="365"></el-input-number></el-form-item><el-form-item label="备注"><el-input v-model="certForm.remark" maxlength="200"></el-input></el-form-item></el-form>
          <el-table v-if="certList.length" :data="certList" border size="small" max-height="160"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column label="类型" width="90"><template #default="s">{{ certTypeLabel(s.row.certType) }}</template></el-table-column><el-table-column prop="diagnosis" label="诊断" show-overflow-tooltip></el-table-column><el-table-column label="审核" width="82" align="center"><template #default="s"><el-tag v-if="Number(s.row.auditStatus)>0" :type="certAuditTone(s.row.auditStatus)" size="small">{{ certAuditText(s.row.auditStatus) }}</el-tag><span v-else class="dim">无须</span></template></el-table-column><el-table-column label="操作" width="110" align="center"><template #default="s"><el-button v-if="Number(s.row.auditStatus)===1" link type="success" size="small" @click="auditCert(s.row, true)">通过</el-button><el-button v-if="Number(s.row.auditStatus)===1" link type="danger" size="small" @click="auditCert(s.row, false)">驳回</el-button><el-button link type="primary" size="small" :disabled="Number(s.row.auditStatus)===1||Number(s.row.auditStatus)===3" @click="emitPrint('cert',s.row.id)">打印</el-button></template></el-table-column></el-table>
          <template #footer><el-button @click="certVisible=false">关闭</el-button><el-button type="primary" :loading="certSaving" @click="submitCert">开具</el-button></template>
        </el-dialog>

        <el-dialog v-model="followupVisible" title="复诊预约" width="500px"><el-form :model="followupForm" label-width="92px"><el-form-item label="复诊日期" required><el-date-picker v-model="followupForm.followupDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="pastDate"></el-date-picker></el-form-item><el-form-item label="复诊备注"><el-input v-model="followupForm.followupNote" type="textarea" :rows="3" maxlength="200"></el-input></el-form-item></el-form><template #footer><el-button @click="followupVisible=false">取消</el-button><el-button type="primary" :loading="followupSaving" @click="submitFollowup">保存</el-button></template></el-dialog>

        <el-dialog v-model="consentVisible" title="知情同意书" width="640px">
          <div class="dw-cert-patient"><b>{{ (visit&&visit.patientName) || '-' }}</b><span>{{ genderText(visit&&visit.gender) }} · {{ (visit&&visit.age) != null ? visit.age + '岁' : '年龄-' }}</span><span class="no">门诊号 {{ (visit&&visit.iptOtpNo) || '-' }}</span></div>
          <el-form :model="consentForm" label-width="92px">
            <el-form-item label="同意书类型"><el-radio-group v-model="consentForm.consentType"><el-radio-button v-for="c in consentTypes" :key="c.v" :label="c.v">{{ c.l }}</el-radio-button></el-radio-group></el-form-item>
            <el-form-item label="标题"><el-input v-model="consentForm.title" maxlength="100" :placeholder="consentTypeLabel(consentForm.consentType) + '知情同意书(留空自动生成)'"></el-input></el-form-item>
            <el-form-item label="告知事项" required><el-input v-model="consentForm.content" type="textarea" :rows="5" maxlength="2000" placeholder="风险/替代方案/注意事项等告知正文, 患者签署前请逐条告知"></el-input></el-form-item>
          </el-form>
          <el-table v-if="consentList.length" :data="consentList" border size="small" max-height="170"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column prop="title" label="标题" show-overflow-tooltip></el-table-column><el-table-column label="状态" width="76" align="center"><template #default="s"><el-tag :type="consentStatusTone(s.row.status)" size="small">{{ consentStatusText(s.row.status) }}</el-tag></template></el-table-column><el-table-column label="签署" width="110" show-overflow-tooltip><template #default="s">{{ s.row.patientSignName ? s.row.patientSignName + '(' + (s.row.relation||'本人') + ')' : '待签' }}</template></el-table-column><el-table-column label="操作" width="110" align="center"><template #default="s"><el-button v-if="Number(s.row.status)===1" link type="success" size="small" @click="signConsent(s.row)">签署</el-button><el-button v-if="Number(s.row.status)!==3" link type="danger" size="small" @click="cancelConsent(s.row)">撤销</el-button></template></el-table-column></el-table>
          <template #footer><el-button @click="consentVisible=false">关闭</el-button><el-button type="primary" :loading="consentSaving" @click="submitConsent">开具</el-button></template>
        </el-dialog>

        <el-dialog v-model="bizVisible" title="诊间业务登记" width="720px" top="5vh">
          <el-tabs v-model="bizTab">
            <el-tab-pane label="代办登记" name="agent">
              <el-form :model="agentForm" label-width="88px" size="small"><div class="dw-biz-form"><el-form-item label="代办人" required><el-input v-model="agentForm.agentName" maxlength="50"></el-input></el-form-item><el-form-item label="与患者关系" required><el-select v-model="agentForm.relation" style="width:100%"><el-option v-for="r in ['配偶','父母','子女','兄弟姐妹','监护人','其他']" :key="r" :label="r" :value="r"></el-option></el-select></el-form-item><el-form-item label="身份证号"><el-input v-model="agentForm.agentIdCard" maxlength="18"></el-input></el-form-item><el-form-item label="联系电话"><el-input v-model="agentForm.agentPhone" maxlength="20"></el-input></el-form-item></div><el-form-item label="代办事由"><el-input v-model="agentForm.reason" maxlength="200"></el-input></el-form-item></el-form>
              <el-button size="small" type="primary" :loading="bizSaving" @click="submitAgent">登记</el-button>
              <el-table v-if="agentList.length" :data="agentList" border size="small" max-height="150" style="margin-top:8px"><el-table-column prop="agentName" label="代办人" width="90"></el-table-column><el-table-column prop="relation" label="关系" width="80"></el-table-column><el-table-column prop="reason" label="事由" show-overflow-tooltip></el-table-column><el-table-column prop="createTime" label="时间" width="150"></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane label="转诊" name="referral">
              <el-form :model="referralForm" label-width="88px" size="small"><div class="dw-biz-form"><el-form-item label="方向"><el-radio-group v-model="referralForm.direction"><el-radio-button :label="1">上转/转出</el-radio-button><el-radio-button :label="2">下转/接收</el-radio-button></el-radio-group></el-form-item><el-form-item label="目标医院" required><el-input v-model="referralForm.toHospital" maxlength="100"></el-input></el-form-item><el-form-item label="目标科室"><el-input v-model="referralForm.toDept" maxlength="50"></el-input></el-form-item><el-form-item label="联系电话"><el-input v-model="referralForm.contactPhone" maxlength="20"></el-input></el-form-item></div><el-form-item label="转诊原因"><el-input v-model="referralForm.reason" type="textarea" :rows="2" maxlength="500"></el-input></el-form-item></el-form>
              <el-button size="small" type="primary" :loading="bizSaving" @click="submitReferral">发起</el-button>
              <el-table v-if="referralList.length" :data="referralList" border size="small" max-height="150" style="margin-top:8px"><el-table-column prop="toHospital" label="目标医院" show-overflow-tooltip></el-table-column><el-table-column label="方向" width="80"><template #default="s">{{ Number(s.row.direction)===2?'下转':'上转' }}</template></el-table-column><el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small">{{ referralStatusText(s.row.status) }}</el-tag></template></el-table-column><el-table-column label="操作" width="120" align="center"><template #default="s"><el-button v-if="Number(s.row.status)===1" link type="primary" size="small" @click="referralAdvance(s.row,2)">接收</el-button><el-button v-if="Number(s.row.status)<=2" link type="success" size="small" @click="referralAdvance(s.row,3)">完成</el-button><el-button v-if="Number(s.row.status)<=2" link type="danger" size="small" @click="referralAdvance(s.row,4)">取消</el-button></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane label="绿通额度" name="green">
              <el-form :model="greenForm" label-width="88px" size="small"><div class="dw-biz-form"><el-form-item label="信用额度(元)" required><el-input-number v-model="greenForm.creditLimit" :min="1" :max="50000" controls-position="right" style="width:130px"></el-input-number></el-form-item><el-form-item label="开通原因"><el-input v-model="greenForm.reason" maxlength="200" placeholder="急危重症/证件缺失等"></el-input></el-form-item></div></el-form>
              <el-button size="small" type="primary" :loading="bizSaving" @click="submitGreen">开通</el-button>
              <el-table v-if="greenList.length" :data="greenList" border size="small" max-height="150" style="margin-top:8px"><el-table-column prop="creditLimit" label="额度" width="90"></el-table-column><el-table-column prop="usedAmount" label="已用" width="90"></el-table-column><el-table-column prop="reason" label="原因" show-overflow-tooltip></el-table-column><el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag :type="Number(s.row.status)===1?'success':'info'" size="small">{{ Number(s.row.status)===1?'启用':'关闭' }}</el-tag></template></el-table-column><el-table-column label="操作" width="80" align="center"><template #default="s"><el-button v-if="Number(s.row.status)===1" link type="danger" size="small" @click="revokeGreen(s.row)">关闭</el-button></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane label="犬伤登记" name="dogbite">
              <el-form :model="dogbiteForm" label-width="88px" size="small"><div class="dw-biz-form"><el-form-item label="暴露时间"><el-date-picker v-model="dogbiteForm.exposeTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%"></el-date-picker></el-form-item><el-form-item label="致伤动物"><el-select v-model="dogbiteForm.animalType" style="width:100%"><el-option v-for="a in ['犬','猫','其他']" :key="a" :label="a" :value="a"></el-option></el-select></el-form-item><el-form-item label="伤口分级"><el-radio-group v-model="dogbiteForm.woundGrade"><el-radio-button :label="1">Ⅰ级</el-radio-button><el-radio-button :label="2">Ⅱ级</el-radio-button><el-radio-button :label="3">Ⅲ级</el-radio-button></el-radio-group></el-form-item><el-form-item label="暴露部位" required><el-input v-model="dogbiteForm.woundParts" maxlength="100"></el-input></el-form-item><el-form-item label="免疫程序"><el-select v-model="dogbiteForm.vaccinePlan" style="width:100%"><el-option label="五针法" value="五针法"></el-option><el-option label="四针法(2-1-1)" value="四针法(2-1-1)"></el-option></el-select></el-form-item><el-form-item label="下次接种"><el-date-picker v-model="dogbiteForm.vaccineNextDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item><el-form-item label="免疫球蛋白"><el-switch v-model="dogbiteForm.immunoglobulin" :active-value="1" :inactive-value="0"></el-switch></el-form-item><el-form-item label="动物情况"><el-input v-model="dogbiteForm.dogInfo" maxlength="200"></el-input></el-form-item></div><el-form-item label="伤口处置"><el-input v-model="dogbiteForm.woundHandling" type="textarea" :rows="2" maxlength="300"></el-input></el-form-item></el-form>
              <el-button size="small" type="primary" :loading="bizSaving" @click="submitDogbite">登记</el-button>
              <el-table v-if="dogbiteList.length" :data="dogbiteList" border size="small" max-height="150" style="margin-top:8px"><el-table-column label="分级" width="70"><template #default="s">{{ dogbiteGradeText(s.row.woundGrade) }}</template></el-table-column><el-table-column prop="woundParts" label="部位" show-overflow-tooltip></el-table-column><el-table-column prop="vaccinePlan" label="免疫程序" width="110"></el-table-column><el-table-column prop="vaccineNextDate" label="下次接种" width="110"></el-table-column></el-table>
            </el-tab-pane>
          </el-tabs>
          <template #footer><el-button @click="bizVisible=false">关闭</el-button></template>
        </el-dialog>

        <el-dialog v-model="printVisible" title="打印中心" width="680px" top="5vh">
          <div class="dw-print-group">病历</div><div class="dw-print-row"><span class="name">门诊病历 · {{ visit&&visit.patientName }}</span><el-button size="small" @click="emitPrint('record',visitId)">打印</el-button></div>
          <div class="dw-print-group">处方笺</div><div v-if="!prescriptions.length" class="dim">暂无可打印处方</div><div class="dw-print-row" v-for="p in prescriptions" :key="'rx'+p.id"><span class="name">{{ p.rxNo }} · {{ p.rxType }} · ￥{{ money(p.totalAmount) }}</span><el-button size="small" @click="emitPrint('rx',p.id)">打印</el-button></div>
          <div class="dw-print-group">检查 / 检验申请单</div><div v-if="!printableOrders.length" class="dim">暂无可打印申请单</div><div class="dw-print-row" v-for="o in printableOrders" :key="'od'+o.id"><span class="name">{{ o.orderNo }} · {{ o.orderType }}申请单 · ￥{{ money(o.totalAmount) }}</span><el-button size="small" @click="emitPrint('order',o.id)">打印</el-button></div>
          <div class="dw-print-group">证明</div><div class="dw-print-row" v-for="a in activeAdmits" :key="'ad'+a.id"><span class="name">住院证 · {{ a.admitDeptName }} · {{ a.applyTime||'' }}</span><el-button size="small" @click="emitPrint('admission',a.id)">打印</el-button></div><div class="dw-print-row" v-for="c in certList" :key="'ct'+c.id"><span class="name">{{ certTypeLabel(c.certType) }} · {{ c.issueTime||'' }}</span><el-button size="small" @click="emitPrint('cert',c.id)">打印</el-button></div>
          <template #footer><el-button @click="printVisible=false">关闭</el-button><el-button type="primary" :loading="printingAll" @click="printAllDocs">一键全打(病历+全部单据)</el-button></template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.DwDocumentsPanel = DwDocumentsPanel;
})();
