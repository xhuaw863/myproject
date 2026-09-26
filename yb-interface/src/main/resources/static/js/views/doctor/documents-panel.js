/* 医生工作站 - 费用汇总、医疗文书与打印中心 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  var CERT_TYPES = [{ v: 1, l: '诊断证明' }, { v: 2, l: '病假条' }, { v: 3, l: '转诊证明' }];
  var DOC_CARDS = [
    { key: 'admit', label: '住院证', mark: '住', tone: 'blue' },
    { key: 'consult', label: '会诊申请', mark: '会', tone: 'orange' },
    { key: 'cert', label: '诊断证明', mark: '证', tone: 'green' },
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
        admitForm: { admitDeptId: null, admitDeptName: '', admitDiagnosis: '', conditionSummary: '', admitPurpose: '', urgency: 1 },
        consultForm: { consultDeptId: null, consultDeptName: '', consultPurpose: '', conditionSummary: '', urgency: 1, expectedTime: '' },
        certForm: { certType: 1, diagnosis: '', certContent: '', sickLeaveDays: null, remark: '' },
        followupForm: { followupDate: '', followupNote: '' },
        certTypes: CERT_TYPES
      };
    },
    computed: {
      visit: function () { return raw(this.currentVisit) || null; },
      patient: function () { return raw(this.currentPatient) || {}; },
      diagList: function () { return raw(this.diagnoses) || []; },
      visitId: function () { return this.visit && (this.visit.id || this.visit.visitId); },
      canEdit: function () { return !!this.visit && Number(this.visit.visitStatus) === 2; },
      mainDiagnosis: function () {
        var main = this.diagList.find(function (d) { return String(d.maindiagFlag) === '1'; }) || this.diagList[0];
        return main ? (main.diagName || main.name || '') : '';
      },
      deptOpts: function () { return this.depts.filter(function (d) { return Number(d.deptLevel) === 2 || d.deptLevel == null; }); },
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
      clearLoaded: function () {
        this.fee = blankFee(); this.orders = []; this.prescriptions = [];
        this.admitList = []; this.consultList = []; this.certList = [];
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
          HIS.get('/api/his/medical-cert/list?visitId=' + encodeURIComponent(vm.visitId))
        ]).then(function (values) {
          vm.admitList = asList(values[0]); vm.consultList = asList(values[1]); vm.certList = asList(values[2]);
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
        return this.followupCount;
      },
      openDocument: function (key) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (key === 'admit') { this.openAdmit(); }
        else if (key === 'consult') { this.openConsult(); }
        else if (key === 'cert') { this.openCert(); }
        else { this.openFollowup(); }
      },
      openAdmit: function () {
        this.admitForm = { admitDeptId: null, admitDeptName: '', admitDiagnosis: this.mainDiagnosis, conditionSummary: this.visit.presentIllness || this.visit.chiefComplaint || '', admitPurpose: '', urgency: 1 };
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
        vm.admitSaving = true;
        HIS.post('/api/his/admission-cert/create', Object.assign({ visitId: vm.visitId }, f)).then(function () {
          ElementPlus.ElMessage.success('住院证已开具'); vm.admitVisible = false; return vm.loadDocuments();
        }).catch(HIS.notifyError).finally(function () { vm.admitSaving = false; });
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
        HIS.post('/api/his/medical-cert/create', Object.assign({ visitId: vm.visitId }, f)).then(function () {
          ElementPlus.ElMessage.success('证明已开具'); vm.certVisible = false; return vm.loadDocuments();
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
      barWidth: function (amount) { return this.fee.total > 0 ? Math.max(0, Number(amount) * 100 / this.fee.total).toFixed(2) + '%' : '0%'; },
      emitPrint: function (type, id) { this.$emit('print', { type: type, id: id }); },
      openPrintCenter: function () { if (!this.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; } this.loadPrintables(); this.printVisible = true; }
    },
    template: `
      <div class="dw-documents-panel">
        <style>
          .dw-documents-panel{margin-top:12px}.dw-documents-panel .dw-doc-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.dw-documents-panel .dw-doc-grid .el-badge{width:100%}.dw-documents-panel .dw-doc-card{position:relative;display:flex;align-items:center;gap:10px;width:100%;padding:10px;border:1px solid var(--dw-border);border-radius:4px;background:var(--dw-card);cursor:pointer;text-align:left}.dw-documents-panel .dw-doc-card:hover{border-color:var(--dw-primary);background:var(--dw-primary-light)}.dw-documents-panel .dw-doc-mark{display:flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:3px;background:var(--dw-primary);color:#fff;font-size:16px;font-weight:700}.dw-documents-panel .tone-orange .dw-doc-mark{background:var(--dw-warning)}.dw-documents-panel .tone-green .dw-doc-mark{background:var(--dw-success)}.dw-documents-panel .tone-gray .dw-doc-mark{background:var(--dw-text-secondary)}.dw-documents-panel .dw-doc-name{font-weight:600;color:var(--dw-text)}.dw-documents-panel .dw-doc-hint{font-size:11px;color:var(--dw-text-hint)}.dw-documents-panel .dw-fee-legend{display:grid;grid-template-columns:1fr 1fr;gap:3px 16px;margin-top:6px}.dw-documents-panel .dw-fee-legend span{display:flex;justify-content:space-between;color:var(--dw-text-secondary);font-size:12px}.dw-documents-panel .dw-insu-estimate{display:flex;justify-content:space-between;margin-top:7px;padding-top:7px;border-top:1px dashed var(--dw-border);color:var(--dw-text-hint);font-size:11px}.dw-documents-panel .dw-print-row{display:flex;align-items:center;gap:8px;padding:8px 4px;border-bottom:1px solid var(--dw-border)}.dw-documents-panel .dw-print-row .name{flex:1}.dw-documents-panel .dw-print-group{margin:10px 0;color:var(--dw-primary-dark);font-weight:700}
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
        <div class="dw-action-group"><el-button type="primary" plain style="width:100%" :disabled="!visitId" @click="openPrintCenter">打印中心</el-button></div>

        <el-dialog v-model="admitVisible" title="开具住院证" width="620px">
          <el-form :model="admitForm" label-width="92px"><el-form-item label="拟收科室" required><el-select v-model="admitForm.admitDeptId" filterable style="width:100%" @change="onAdmitDeptChange"><el-option v-for="d in deptOpts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item><el-form-item label="入院诊断" required><el-input v-model="admitForm.admitDiagnosis" maxlength="200"></el-input></el-form-item><el-form-item label="病情摘要"><el-input v-model="admitForm.conditionSummary" type="textarea" :rows="3" maxlength="500"></el-input></el-form-item><el-form-item label="入院目的"><el-input v-model="admitForm.admitPurpose" maxlength="100"></el-input></el-form-item><el-form-item label="紧急程度"><el-radio-group v-model="admitForm.urgency"><el-radio-button :label="1">普通</el-radio-button><el-radio-button :label="2">急</el-radio-button><el-radio-button :label="3">危急</el-radio-button></el-radio-group></el-form-item></el-form>
          <el-table v-if="admitList.length" :data="admitList" border size="small" max-height="160"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column prop="admitDeptName" label="拟收科室" width="120"></el-table-column><el-table-column prop="admitDiagnosis" label="入院诊断" show-overflow-tooltip></el-table-column><el-table-column label="状态" width="75"><template #default="s">{{ Number(s.row.status)===3?'已作废':'有效' }}</template></el-table-column></el-table>
          <template #footer><el-button @click="admitVisible=false">关闭</el-button><el-button type="primary" :loading="admitSaving" @click="submitAdmit">开具</el-button></template>
        </el-dialog>

        <el-dialog v-model="consultVisible" title="会诊申请" width="620px">
          <el-form :model="consultForm" label-width="92px"><el-form-item label="会诊科室" required><el-select v-model="consultForm.consultDeptId" filterable style="width:100%" @change="onConsultDeptChange"><el-option v-for="d in deptOpts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item><el-form-item label="会诊目的" required><el-input v-model="consultForm.consultPurpose" maxlength="200"></el-input></el-form-item><el-form-item label="病情摘要"><el-input v-model="consultForm.conditionSummary" type="textarea" :rows="3" maxlength="500"></el-input></el-form-item><el-form-item label="紧急程度"><el-radio-group v-model="consultForm.urgency"><el-radio-button :label="1">普通</el-radio-button><el-radio-button :label="2">急</el-radio-button><el-radio-button :label="3">紧急</el-radio-button></el-radio-group></el-form-item><el-form-item label="期望时间"><el-date-picker v-model="consultForm.expectedTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%"></el-date-picker></el-form-item></el-form>
          <el-table v-if="consultList.length" :data="consultList" border size="small" max-height="160"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column prop="consultDeptName" label="会诊科室" width="120"></el-table-column><el-table-column prop="consultPurpose" label="会诊目的" show-overflow-tooltip></el-table-column><el-table-column prop="status" label="状态" width="65"></el-table-column></el-table>
          <template #footer><el-button @click="consultVisible=false">关闭</el-button><el-button type="primary" :loading="consultSaving" @click="submitConsult">发起</el-button></template>
        </el-dialog>

        <el-dialog v-model="certVisible" title="诊断证明" width="620px">
          <el-form :model="certForm" label-width="92px"><el-form-item label="证明类型"><el-radio-group v-model="certForm.certType"><el-radio-button v-for="c in certTypes" :key="c.v" :label="c.v">{{ c.l }}</el-radio-button></el-radio-group></el-form-item><el-form-item label="诊断"><el-input v-model="certForm.diagnosis" maxlength="300"></el-input></el-form-item><el-form-item label="证明内容" required><el-input v-model="certForm.certContent" type="textarea" :rows="4" maxlength="500"></el-input></el-form-item><el-form-item label="病假天数"><el-input-number v-model="certForm.sickLeaveDays" :min="1" :max="365"></el-input-number></el-form-item><el-form-item label="备注"><el-input v-model="certForm.remark" maxlength="200"></el-input></el-form-item></el-form>
          <el-table v-if="certList.length" :data="certList" border size="small" max-height="160"><el-table-column type="index" label="序号" width="50"></el-table-column><el-table-column label="类型" width="90"><template #default="s">{{ certTypeLabel(s.row.certType) }}</template></el-table-column><el-table-column prop="diagnosis" label="诊断" show-overflow-tooltip></el-table-column><el-table-column label="打印" width="60"><template #default="s"><el-button link type="primary" @click="emitPrint('cert',s.row.id)">打印</el-button></template></el-table-column></el-table>
          <template #footer><el-button @click="certVisible=false">关闭</el-button><el-button type="primary" :loading="certSaving" @click="submitCert">开具</el-button></template>
        </el-dialog>

        <el-dialog v-model="followupVisible" title="复诊预约" width="500px"><el-form :model="followupForm" label-width="92px"><el-form-item label="复诊日期" required><el-date-picker v-model="followupForm.followupDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="pastDate"></el-date-picker></el-form-item><el-form-item label="复诊备注"><el-input v-model="followupForm.followupNote" type="textarea" :rows="3" maxlength="200"></el-input></el-form-item></el-form><template #footer><el-button @click="followupVisible=false">取消</el-button><el-button type="primary" :loading="followupSaving" @click="submitFollowup">保存</el-button></template></el-dialog>

        <el-dialog v-model="printVisible" title="打印中心" width="680px" top="5vh">
          <div class="dw-print-group">病历</div><div class="dw-print-row"><span class="name">门诊病历 · {{ visit&&visit.patientName }}</span><el-button size="small" @click="emitPrint('record',visitId)">打印</el-button></div>
          <div class="dw-print-group">处方笺</div><div v-if="!prescriptions.length" class="dim">暂无可打印处方</div><div class="dw-print-row" v-for="p in prescriptions" :key="'rx'+p.id"><span class="name">{{ p.rxNo }} · {{ p.rxType }} · ￥{{ money(p.totalAmount) }}</span><el-button size="small" @click="emitPrint('rx',p.id)">打印</el-button></div>
          <div class="dw-print-group">检查 / 检验申请单</div><div v-if="!printableOrders.length" class="dim">暂无可打印申请单</div><div class="dw-print-row" v-for="o in printableOrders" :key="'od'+o.id"><span class="name">{{ o.orderNo }} · {{ o.orderType }}申请单 · ￥{{ money(o.totalAmount) }}</span><el-button size="small" @click="emitPrint('order',o.id)">打印</el-button></div>
          <div class="dw-print-group">证明</div><div class="dw-print-row" v-for="a in activeAdmits" :key="'ad'+a.id"><span class="name">住院证 · {{ a.admitDeptName }} · {{ a.applyTime||'' }}</span><el-button size="small" @click="emitPrint('admission',a.id)">打印</el-button></div><div class="dw-print-row" v-for="c in certList" :key="'ct'+c.id"><span class="name">{{ certTypeLabel(c.certType) }} · {{ c.issueTime||'' }}</span><el-button size="small" @click="emitPrint('cert',c.id)">打印</el-button></div>
          <template #footer><el-button @click="printVisible=false">关闭</el-button></template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.DwDocumentsPanel = DwDocumentsPanel;
})();
