/* 医生站: 候诊列表 + 接诊工作台(病历/诊断/处方/检查单 + 医保2203上传) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var VISIT_STATUS = [
    { v: 1, l: '候诊', t: 'info' },
    { v: 2, l: '接诊中', t: 'warning' },
    { v: 3, l: '已完成', t: 'success' },
    { v: 4, l: '已取消', t: 'info' }
  ];
  function statusLabel(v) {
    for (var i = 0; i < VISIT_STATUS.length; i++) { if (VISIT_STATUS[i].v === v) { return VISIT_STATUS[i].l; } }
    return v;
  }
  function statusTag(v) {
    for (var i = 0; i < VISIT_STATUS.length; i++) { if (VISIT_STATUS[i].v === v) { return VISIT_STATUS[i].t; } }
    return 'info';
  }
  function today() {
    var d = new Date();
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var day = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + day;
  }
  function money(n) { return (n === null || n === undefined) ? '0.00' : Number(n).toFixed(2); }

  /* ================= 候诊列表 ================= */
  HIS.views.DoctorQueue = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        workDate: today(), visitStatus: null, deptId: null, keyword: '',
        depts: [], visitStatusOpts: VISIT_STATUS
      };
    },
    created: function () { this.loadDepts(); this.load(); },
    methods: {
      loadDepts: function () {
        var vm = this;
        /* 候诊列表科室筛选: 仅本机构开诊门诊科室(号源只出自这些科室) */
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/visit/queue?page=' + vm.page + '&size=' + vm.size;
        if (vm.workDate) { q += '&workDate=' + vm.workDate; }
        if (vm.visitStatus !== null && vm.visitStatus !== '') { q += '&visitStatus=' + vm.visitStatus; }
        if (vm.deptId) { q += '&deptId=' + vm.deptId; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      receive: function (row) {
        var vm = this;
        var go = function () { HIS.pendingVisitId = row.id; HIS.go('doctor-work'); };
        if (row.visitStatus === 1) {
          HIS.post('/api/his/visit/start?id=' + row.id).then(function () {
            HIS.notifySuccess('已接诊: ' + row.patientName); go();
          }).catch(HIS.notifyError);
        } else {
          go();
        }
      },
      statusLabel: statusLabel,
      statusTag: statusTag
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">候诊列表 <span style="font-size:12px;color:#909399;font-weight:normal;">(挂号后自动进入候诊, 接诊后进入工作台)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="workDate" type="date" value-format="YYYY-MM-DD" placeholder="就诊日期" style="width:160px" @change="search"></el-date-picker>',
      '    <el-select v-model="visitStatus" placeholder="全部状态" clearable style="width:130px" @change="search"><el-option v-for="s in visitStatusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option></el-select>',
      '    <el-select v-model="deptId" placeholder="全部科室" clearable style="width:160px" @change="search"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="患者/挂号单号/患者号" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 人</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="regNo" label="挂号单号" width="170"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '    <el-table-column prop="gender" label="性别" width="55"></el-table-column>',
      '    <el-table-column prop="age" label="年龄" width="55"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="120"></el-table-column>',
      '    <el-table-column prop="drName" label="医师" width="90"></el-table-column>',
      '    <el-table-column prop="iptOtpNo" label="门诊号" width="170"></el-table-column>',
      '    <el-table-column prop="mdtrtId" label="医保就诊ID" width="120"></el-table-column>',
      '    <el-table-column label="状态" width="90"><template #default="s"><el-tag size="small" :type="statusTag(s.row.visitStatus)">{{ statusLabel(s.row.visitStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="110" fixed="right"><template #default="s">',
      '      <el-button link type="primary" :disabled="s.row.visitStatus>=3" @click="receive(s.row)">{{ s.row.visitStatus===1?"接诊":"查看" }}</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* ================= 接诊工作台 ================= */
  HIS.views.DoctorWork = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        queueList: [], currentVisitId: null, visit: null, submitting: false,
        activeTab: 'record',
        form: { chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', treatmentOpinion: '' },
        diagnoses: [], prescriptions: [], orders: [],
        diag: { keyword: '', results: [], loading: false },
        rx: { keyword: '', results: [], loading: false, rxType: '西药', items: [] },
        od: { keyword: '', results: [], loading: false, orderType: '检查', items: [] },
        /* 本机构启用的用法/频次选项(L3) */
        usageOpts: [], freqOpts: []
      };
    },
    created: function () {
      this.loadQueue();
      this.loadMedOpts();
      if (HIS.pendingVisitId) { this.selectVisit(HIS.pendingVisitId); HIS.pendingVisitId = null; }
    },
    methods: {
      loadMedOpts: function () {
        var vm = this;
        HIS.get('/api/org-catalog/available/med-dict?dictType=usage').then(function (d) { vm.usageOpts = d || []; }).catch(function () {});
        HIS.get('/api/org-catalog/available/med-dict?dictType=freq').then(function (d) { vm.freqOpts = d || []; }).catch(function () {});
      },
      loadQueue: function () {
        var vm = this;
        HIS.get('/api/his/visit/queue?page=1&size=50&workDate=' + today()).then(function (d) {
          var recs = (d && d.records) || [];
          vm.queueList = recs.filter(function (r) { return r.visitStatus < 3; });
        }).catch(HIS.notifyError);
      },
      selectVisit: function (id) {
        var vm = this; vm.currentVisitId = id;
        HIS.get('/api/his/visit/detail?id=' + id).then(function (d) {
          vm.visit = d.visit || null;
          vm.diagnoses = d.diagnoses || [];
          vm.prescriptions = d.prescriptions || [];
          vm.orders = d.orders || [];
          var v = vm.visit || {};
          vm.form = {
            chiefComplaint: v.chiefComplaint || '', presentIllness: v.presentIllness || '',
            pastHistory: v.pastHistory || '', physicalExam: v.physicalExam || '',
            treatmentOpinion: v.treatmentOpinion || ''
          };
          if (d.record && d.record.subjective && !vm.form.chiefComplaint) { /* 已完成就诊仅回显 */ }
          vm.rx.items = []; vm.od.items = [];
        }).catch(HIS.notifyError);
      },
      startVisit: function () {
        var vm = this;
        if (!vm.currentVisitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        HIS.post('/api/his/visit/start?id=' + vm.currentVisitId).then(function () {
          HIS.notifySuccess('已开始接诊'); vm.selectVisit(vm.currentVisitId); vm.loadQueue();
        }).catch(HIS.notifyError);
      },
      /* ---- 诊断 ---- */
      searchDiag: function () {
        var vm = this; vm.diag.loading = true;
        var q = '/api/dict/query/catalog?type=disease&page=1&size=20';
        if (vm.diag.keyword) { q += '&keyword=' + encodeURIComponent(vm.diag.keyword); }
        HIS.get(q).then(function (d) { vm.diag.results = (d && d.records) || []; })
          .catch(HIS.notifyError).finally(function () { vm.diag.loading = false; });
      },
      addDiag: function (item) {
        for (var i = 0; i < this.diagnoses.length; i++) { if (this.diagnoses[i].diagCode === item.code) { ElementPlus.ElMessage.warning('该诊断已添加'); return; } }
        this.diagnoses.push({ diagCode: item.code, diagName: item.name, maindiagFlag: this.diagnoses.length === 0 ? '1' : '0', diagSrtNo: this.diagnoses.length + 1 });
      },
      removeDiag: function (i) { this.diagnoses.splice(i, 1); },
      setMain: function (i) { for (var k = 0; k < this.diagnoses.length; k++) { this.diagnoses[k].maindiagFlag = (k === i ? '1' : '0'); } },
      /* ---- 处方(取数走本机构启用的医共体药品目录 L3) ---- */
      searchRx: function () {
        var vm = this; vm.rx.loading = true;
        var q = '/api/org-catalog/available/drug?page=1&size=20';
        if (vm.rx.keyword) { q += '&keyword=' + encodeURIComponent(vm.rx.keyword); }
        HIS.get(q).then(function (d) { vm.rx.results = (d && d.records) || []; })
          .catch(HIS.notifyError).finally(function () { vm.rx.loading = false; });
      },
      addRx: function (it) {
        this.rx.items.push({
          drugId: it.id, itemId: it.id, itemCode: it.drugCode, itemName: it.genericName,
          spec: it.spec, unit: it.minUnit, price: it.retailPrice, quantity: 1,
          dosage: '', dosageUnit: it.doseUnit || '', usageMethod: '', frequency: '',
          administration: '', groupNo: '', days: 3, medListCodg: it.ybDrugCode,
          unitDose: it.unitDose, packRatio: it.packRatio, roundRule: it.roundRule
        });
      },
      /* 频次->每日次数: 优先取本机构启用频次字典的 daily_times, 无则按名称解析 */
      freqTimes: function (f) {
        for (var i = 0; i < this.freqOpts.length; i++) {
          if (this.freqOpts[i].name === f && this.freqOpts[i].dailyTimes != null) {
            return Number(this.freqOpts[i].dailyTimes);
          }
        }
        var s = String(f || '').toLowerCase();
        if (s.indexOf('qid') >= 0 || s.indexOf('q6h') >= 0) { return 4; }
        if (s.indexOf('tid') >= 0 || s.indexOf('q8h') >= 0) { return 3; }
        if (s.indexOf('bid') >= 0 || s.indexOf('q12h') >= 0) { return 2; }
        if (s.indexOf('qd') >= 0 || s.indexOf('q24h') >= 0) { return 1; }
        return 1;
      },
      /* 按 round_rule 取整: 1向上 2向下 3四舍五入 */
      roundByRule: function (v, rule) {
        var n = Number(v) || 0;
        if (rule === 2) { return Math.floor(n); }
        if (rule === 3) { return Math.round(n); }
        return Math.ceil(n);
      },
      /* 剂量->发药数量换算: 每次=剂量/单位含药量(按规则取整), 总量=每次×每日次数×天数 */
      calcQty: function (row) {
        var perDose = Number(row.dosage) || 0;
        var unitDose = Number(row.unitDose) || 0;
        var times = this.freqTimes(row.frequency);
        var days = Number(row.days) || 0;
        if (perDose <= 0 || unitDose <= 0 || days <= 0) {
          ElementPlus.ElMessage.warning('请先填写单次剂量, 且该药品需已配置单位含药量'); return;
        }
        var per = this.roundByRule(perDose / unitDose, Number(row.roundRule) || 1);
        row.quantity = Math.ceil(per * times * days);
      },
      removeRxItem: function (i) { this.rx.items.splice(i, 1); },
      lineAmount: function (it) { return money((Number(it.price) || 0) * (Number(it.quantity) || 0)); },
      rxTotal: function () { var s = 0; for (var i = 0; i < this.rx.items.length; i++) { s += (Number(this.rx.items[i].price) || 0) * (Number(this.rx.items[i].quantity) || 0); } return money(s); },
      saveRx: function () {
        var vm = this;
        if (!vm.currentVisitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.rx.items.length) { ElementPlus.ElMessage.warning('请添加处方明细'); return; }
        HIS.post('/api/his/prescription/create', { visitId: vm.currentVisitId, rxType: vm.rx.rxType, items: vm.rx.items })
          .then(function (p) { HIS.notifySuccess('处方已开立: ' + p.rxNo + '  金额￥' + money(p.totalAmount)); vm.rx.items = []; vm.selectVisit(vm.currentVisitId); })
          .catch(HIS.notifyError);
      },
      /* ---- 检查单(取数走本机构启用的收费项目 L3, 附执行价) ---- */
      searchOd: function () {
        var vm = this; vm.od.loading = true;
        var q = '/api/org-catalog/available/charge?page=1&size=20&itemType=' + encodeURIComponent('诊疗');
        if (vm.od.keyword) { q += '&keyword=' + encodeURIComponent(vm.od.keyword); }
        HIS.get(q).then(function (d) { vm.od.results = (d && d.records) || []; })
          .catch(HIS.notifyError).finally(function () { vm.od.loading = false; });
      },
      addOd: function (it) {
        var px = (it.execPrice != null ? it.execPrice : it.price);
        this.od.items.push({ itemId: it.id, itemCode: it.itemCode, itemName: it.itemName, spec: it.spec, unit: it.unit, price: px, quantity: 1, medListCodg: it.medListCodg, execDept: '' });
      },
      removeOdItem: function (i) { this.od.items.splice(i, 1); },
      odTotal: function () { var s = 0; for (var i = 0; i < this.od.items.length; i++) { s += (Number(this.od.items[i].price) || 0) * (Number(this.od.items[i].quantity) || 0); } return money(s); },
      saveOd: function () {
        var vm = this;
        if (!vm.currentVisitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.od.items.length) { ElementPlus.ElMessage.warning('请添加单据明细'); return; }
        HIS.post('/api/his/order/create', { visitId: vm.currentVisitId, orderType: vm.od.orderType, items: vm.od.items })
          .then(function (o) { HIS.notifySuccess('单据已开立: ' + o.orderNo + '  金额￥' + money(o.totalAmount)); vm.od.items = []; vm.selectVisit(vm.currentVisitId); })
          .catch(HIS.notifyError);
      },
      /* ---- 完成接诊 ---- */
      finish: function () {
        var vm = this;
        if (!vm.currentVisitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.diagnoses.length) { ElementPlus.ElMessage.warning('请至少录入一条诊断'); return; }
        vm.submitting = true;
        var payload = {
          visitId: vm.currentVisitId,
          chiefComplaint: vm.form.chiefComplaint, presentIllness: vm.form.presentIllness,
          pastHistory: vm.form.pastHistory, physicalExam: vm.form.physicalExam,
          treatmentOpinion: vm.form.treatmentOpinion,
          diagnoses: vm.diagnoses, uploadYb: true
        };
        HIS.post('/api/his/visit/finish', payload).then(function () {
          HIS.notifySuccess('接诊完成, 已进入待收费(医保2203已上传)');
          vm.selectVisit(vm.currentVisitId); vm.loadQueue();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      statusLabel: statusLabel,
      statusTag: statusTag,
      money: money
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">接诊工作台 <span style="font-size:12px;color:#909399;font-weight:normal;">(病历/诊断/处方/检查单, 完成后上传医保2203并进入待收费)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="currentVisitId" placeholder="选择候诊/接诊中患者" style="width:340px" @change="selectVisit">',
      '      <el-option v-for="q in queueList" :key="q.id" :label="q.patientName+\' | \'+q.deptName+\' | \'+q.regNo" :value="q.id"></el-option>',
      '    </el-select>',
      '    <el-button @click="loadQueue">刷新队列</el-button>',
      '    <el-tag v-if="visit" :type="statusTag(visit.visitStatus)" size="large">{{ statusLabel(visit.visitStatus) }}</el-tag>',
      '    <span v-if="visit" style="color:#606266;font-size:13px;">{{ visit.patientName }} {{ visit.gender }} {{ visit.age||"-" }}岁 | 门诊号 {{ visit.iptOtpNo }} | 医保 {{ visit.mdtrtId||"-" }}</span>',
      '  </div>',
      '  <el-alert v-if="!visit" type="info" :closable="false" show-icon title="请从上方选择一位候诊患者开始接诊" style="margin-bottom:12px;"></el-alert>',
      '  <div v-else>',
      '    <el-tabs v-model="activeTab" type="border-card">',
      /* ---- 病历 ---- */
      '      <el-tab-pane label="病历" name="record">',
      '        <el-form :model="form" label-width="90px" size="default">',
      '          <el-form-item label="主诉"><el-input v-model="form.chiefComplaint" type="textarea" :rows="2"></el-input></el-form-item>',
      '          <el-form-item label="现病史"><el-input v-model="form.presentIllness" type="textarea" :rows="3"></el-input></el-form-item>',
      '          <el-form-item label="既往史"><el-input v-model="form.pastHistory" type="textarea" :rows="2"></el-input></el-form-item>',
      '          <el-form-item label="体格检查"><el-input v-model="form.physicalExam" type="textarea" :rows="2"></el-input></el-form-item>',
      '          <el-form-item label="处理意见"><el-input v-model="form.treatmentOpinion" type="textarea" :rows="3"></el-input></el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      /* ---- 诊断 ---- */
      '      <el-tab-pane label="诊断" name="diag">',
      '        <div class="toolbar">',
      '          <el-input v-model="diag.keyword" placeholder="疾病名称/编码/拼音简码(医保疾病目录)" clearable style="width:300px" @keyup.enter="searchDiag"></el-input>',
      '          <el-button type="primary" @click="searchDiag">检索目录</el-button>',
      '        </div>',
      '        <el-table :data="diag.results" v-loading="diag.loading" border size="small" height="180" style="margin-bottom:12px;">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="code" label="诊断编码" width="160"></el-table-column>',
      '          <el-table-column prop="name" label="诊断名称"></el-table-column>',
      '          <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" @click="addDiag(s.row)">添加</el-button></template></el-table-column>',
      '        </el-table>',
      '        <el-table :data="diagnoses" border size="small">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="diagCode" label="编码" width="160"></el-table-column>',
      '          <el-table-column prop="diagName" label="诊断名称"></el-table-column>',
      '          <el-table-column label="主诊断" width="90"><template #default="s"><el-radio :model-value="s.row.maindiagFlag" label="1" @change="setMain(s.$index)"><span></span></el-radio></template></el-table-column>',
      '          <el-table-column label="操作" width="70"><template #default="s"><el-button link type="danger" @click="removeDiag(s.$index)">移除</el-button></template></el-table-column>',
      '        </el-table>',
      '      </el-tab-pane>',
      /* ---- 处方 ---- */
      '      <el-tab-pane label="处方" name="rx">',
      '        <div class="toolbar">',
      '          <el-input v-model="rx.keyword" placeholder="药品名称/编码/拼音简码" clearable style="width:260px" @keyup.enter="searchRx"></el-input>',
      '          <el-button type="primary" @click="searchRx">检索药品</el-button>',
      '          <el-select v-model="rx.rxType" style="width:110px"><el-option label="西药" value="西药"></el-option><el-option label="中药" value="中药"></el-option></el-select>',
      '        </div>',
      '        <el-table :data="rx.results" v-loading="rx.loading" border size="small" height="170" style="margin-bottom:12px;">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="genericName" label="药品名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="minUnit" label="发药单位" width="80"></el-table-column>',
      '          <el-table-column prop="retailPrice" label="零售价" width="90"></el-table-column>',
      '          <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" @click="addRx(s.row)">添加</el-button></template></el-table-column>',
      '        </el-table>',
      '        <el-table :data="rx.items" border size="small">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="itemName" label="药品" min-width="150"></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="110"></el-table-column>',
      '          <el-table-column label="数量" width="110"><template #default="s"><el-input-number v-model="s.row.quantity" :min="1" size="small" controls-position="right" style="width:95px"></el-input-number></template></el-table-column>',
      '          <el-table-column label="剂量" width="150"><template #default="s"><div style="display:flex;gap:4px;"><el-input v-model="s.row.dosage" size="small" placeholder="如0.5"></el-input><el-button link type="primary" size="small" @click="calcQty(s.row)">算量</el-button></div></template></el-table-column>',
      '          <el-table-column label="用法" width="140"><template #default="s"><el-select v-model="s.row.usageMethod" size="small" placeholder="选择用法" clearable filterable style="width:125px" :filter-method="kwFilter(\'usage\'+s.$index)"><el-option v-for="o in kwOptions(\'usage\'+s.$index, usageOpts, [\'name\',\'code\',\'pyCode\',\'abbrCode\'])" :key="o.id" :label="o.name" :value="o.name"></el-option></el-select></template></el-table-column>',
      '          <el-table-column label="频次" width="140"><template #default="s"><el-select v-model="s.row.frequency" size="small" placeholder="选择频次" clearable filterable style="width:125px" :filter-method="kwFilter(\'freq\'+s.$index)"><el-option v-for="o in kwOptions(\'freq\'+s.$index, freqOpts, [\'name\',\'code\',\'pyCode\',\'abbrCode\'])" :key="o.id" :label="o.name" :value="o.name"></el-option></el-select></template></el-table-column>',
      '          <el-table-column label="天数" width="90"><template #default="s"><el-input-number v-model="s.row.days" :min="1" size="small" controls-position="right" style="width:75px"></el-input-number></template></el-table-column>',
      '          <el-table-column label="金额" width="90"><template #default="s">{{ lineAmount(s.row) }}</template></el-table-column>',
      '          <el-table-column label="操作" width="60"><template #default="s"><el-button link type="danger" @click="removeRxItem(s.$index)">删</el-button></template></el-table-column>',
      '        </el-table>',
      '        <div class="toolbar" style="justify-content:flex-end;">',
      '          <span style="color:#606266;">合计 ￥{{ rxTotal() }}</span>',
      '          <el-button type="primary" @click="saveRx">开立处方</el-button>',
      '        </div>',
      '        <el-divider content-position="left">已开处方</el-divider>',
      '        <el-table :data="prescriptions" border size="small">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="rxNo" label="处方号" width="180"></el-table-column>',
      '          <el-table-column prop="rxType" label="类型" width="80"></el-table-column>',
      '          <el-table-column prop="diagName" label="临床诊断"></el-table-column>',
      '          <el-table-column label="金额" width="100"><template #default="s">￥{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '          <el-table-column label="状态" width="90"><template #default="s"><el-tag size="small">{{ s.row.status===1?"已开":(s.row.status===2?"已发药":"已退药") }}</el-tag></template></el-table-column>',
      '        </el-table>',
      '      </el-tab-pane>',
      /* ---- 检查单 ---- */
      '      <el-tab-pane label="检查单" name="od">',
      '        <div class="toolbar">',
      '          <el-input v-model="od.keyword" placeholder="诊疗项目名称/编码/拼音简码" clearable style="width:260px" @keyup.enter="searchOd"></el-input>',
      '          <el-button type="primary" @click="searchOd">检索项目</el-button>',
      '          <el-select v-model="od.orderType" style="width:110px"><el-option label="检查" value="检查"></el-option><el-option label="检验" value="检验"></el-option><el-option label="治疗" value="治疗"></el-option></el-select>',
      '        </div>',
      '        <el-table :data="od.results" v-loading="od.loading" border size="small" height="170" style="margin-bottom:12px;">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="itemName" label="项目名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="执行价" width="90"><template #default="s">{{ (s.row.execPrice!=null?s.row.execPrice:s.row.price) }}</template></el-table-column>',
      '          <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" @click="addOd(s.row)">添加</el-button></template></el-table-column>',
      '        </el-table>',
      '        <el-table :data="od.items" border size="small">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="itemName" label="项目" min-width="160"></el-table-column>',
      '          <el-table-column label="数量" width="120"><template #default="s"><el-input-number v-model="s.row.quantity" :min="1" size="small" controls-position="right" style="width:100px"></el-input-number></template></el-table-column>',
      '          <el-table-column label="执行科室" width="140"><template #default="s"><el-input v-model="s.row.execDept" size="small"></el-input></template></el-table-column>',
      '          <el-table-column label="金额" width="90"><template #default="s">{{ lineAmount(s.row) }}</template></el-table-column>',
      '          <el-table-column label="操作" width="60"><template #default="s"><el-button link type="danger" @click="removeOdItem(s.$index)">删</el-button></template></el-table-column>',
      '        </el-table>',
      '        <div class="toolbar" style="justify-content:flex-end;">',
      '          <span style="color:#606266;">合计 ￥{{ odTotal() }}</span>',
      '          <el-button type="primary" @click="saveOd">开立单据</el-button>',
      '        </div>',
      '        <el-divider content-position="left">已开单据</el-divider>',
      '        <el-table :data="orders" border size="small">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="orderNo" label="单据号" width="180"></el-table-column>',
      '          <el-table-column prop="orderType" label="类型" width="80"></el-table-column>',
      '          <el-table-column prop="diagName" label="临床诊断"></el-table-column>',
      '          <el-table-column label="金额" width="100"><template #default="s">￥{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '          <el-table-column label="状态" width="90"><template #default="s"><el-tag size="small">{{ s.row.status===1?"已开":(s.row.status===2?"已执行":"已退") }}</el-tag></template></el-table-column>',
      '        </el-table>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '    <div class="toolbar" style="justify-content:flex-end;margin-top:14px;">',
      '      <el-button v-if="visit.visitStatus===1" type="warning" @click="startVisit">开始接诊</el-button>',
      '      <el-button type="primary" :loading="submitting" :disabled="visit.visitStatus>=3" @click="finish">完成接诊(上传2203)</el-button>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
