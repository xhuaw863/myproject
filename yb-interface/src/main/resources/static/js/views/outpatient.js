/* 门诊挂号台: 患者建档/查询 + 门诊挂号(2201) + 退号(2202) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var INSUTYPES = [
    { v: '310', l: '310-职工医保' },
    { v: '390', l: '390-居民医保' },
    { v: '340', l: '340-工伤保险' },
    { v: '391', l: '391-城乡居民' }
  ];
  var CERT_TYPES = [
    { v: '01', l: '01-电子凭证' },
    { v: '02', l: '02-身份证' },
    { v: '03', l: '03-社保卡' }
  ];
  var TIME_TYPES = [{ v: 'am', l: '上午' }, { v: 'pm', l: '下午' }, { v: 'night', l: '晚间' }];
  var REG_STATUS = [
    { v: 1, l: '已挂号', t: 'success' },
    { v: 2, l: '已退号', t: 'info' },
    { v: 3, l: '已就诊', t: 'warning' }
  ];

  function clean(f) {
    delete f.createTime; delete f.updateTime; delete f.createBy; delete f.updateBy; delete f.deleted;
    return f;
  }
  function timeLabel(v) {
    for (var i = 0; i < TIME_TYPES.length; i++) { if (TIME_TYPES[i].v === v) { return TIME_TYPES[i].l; } }
    return v || '-';
  }
  function statusLabel(v) {
    for (var i = 0; i < REG_STATUS.length; i++) { if (REG_STATUS[i].v === v) { return REG_STATUS[i].l; } }
    return v;
  }
  function statusTag(v) {
    for (var i = 0; i < REG_STATUS.length; i++) { if (REG_STATUS[i].v === v) { return REG_STATUS[i].t; } }
    return 'info';
  }
  function insutypeLabel(v) {
    for (var i = 0; i < INSUTYPES.length; i++) { if (INSUTYPES[i].v === v) { return INSUTYPES[i].l; } }
    return v || '-';
  }
  function today() {
    var d = new Date();
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var day = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + day;
  }

  /* ================= 患者建档 / 查询 ================= */
  HIS.views.PatientManage = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 15, keyword: '',
        insutypes: INSUTYPES, certTypes: CERT_TYPES,
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.load(); },
    methods: {
      empty: function () {
        return {
          id: null, patientNo: '', psnNo: '', name: '', gender: '男', birthDate: '', age: null,
          idCard: '', phone: '', address: '', insutype: '310', mdtrtCertType: '02', mdtrtCertNo: '',
          insuplcAdmdvs: '', contactName: '', contactPhone: '', status: 1, memo: ''
        };
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/patient/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      add: function () { this.editing = false; this.form = this.empty(); this.dlg = true; },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.dlg = true; },
      readCard: function () {
        /* 模拟读取医保电子凭证/社保卡(真实环境调用1101人员信息获取) */
        var f = this.form;
        if (!f.idCard) { ElementPlus.ElMessage.warning('请先录入身份证号再读卡'); return; }
        f.mdtrtCertType = '01';
        f.mdtrtCertNo = f.idCard;
        if (!f.psnNo) { f.psnNo = 'PSN' + f.idCard.slice(-8); }
        HIS.notifySuccess('读卡成功(模拟): 已回填医保凭证信息');
      },
      calcAge: function () {
        var f = this.form;
        if (f.birthDate) {
          var b = new Date(f.birthDate);
          var age = new Date().getFullYear() - b.getFullYear();
          if (age >= 0 && age < 150) { f.age = age; }
        }
        if (!f.mdtrtCertNo && f.idCard) { f.mdtrtCertNo = f.idCard; }
      },
      submit: function () {
        var vm = this;
        if (!vm.form.name) { ElementPlus.ElMessage.warning('患者姓名必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/patient', vm.form) : HIS.post('/api/his/patient', vm.form);
        p.then(function (d) {
          HIS.notifySuccess(vm.editing ? '保存成功' : ('建档成功, 患者号: ' + ((d && d.patientNo) || '')));
          vm.dlg = false; vm.load();
        }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        HIS.del('/api/his/patient/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError);
      },
      insutypeLabel: insutypeLabel
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">患者建档 / 查询</div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="姓名/患者号/身份证/医保号/电话" clearable style="width:280px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="add">新增建档</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 人</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="patientNo" label="患者号" width="170"></el-table-column>',
      '    <el-table-column prop="name" label="姓名" width="90"></el-table-column>',
      '    <el-table-column prop="gender" label="性别" width="55"></el-table-column>',
      '    <el-table-column prop="age" label="年龄" width="55"></el-table-column>',
      '    <el-table-column prop="idCard" label="身份证号" width="170"></el-table-column>',
      '    <el-table-column prop="phone" label="电话" width="120"></el-table-column>',
      '    <el-table-column label="险种" width="120"><template #default="s">{{ insutypeLabel(s.row.insutype) }}</template></el-table-column>',
      '    <el-table-column prop="psnNo" label="医保人员编号" width="140"></el-table-column>',
      '    <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"正常":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="120" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm title="确认删除该患者档案？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="prev, pager, next, total" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑患者档案\':\'新增患者建档\'" width="680px">',
      '    <el-form :model="form" label-width="110px" size="default">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.name"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="性别"><el-select v-model="form.gender" style="width:100%"><el-option label="男" value="男"></el-option><el-option label="女" value="女"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" style="width:100%" @change="calcAge"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="年龄"><el-input v-model.number="form.age" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证号"><el-input v-model="form.idCard" @blur="calcAge"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="住址"><el-input v-model="form.address"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="险种类型"><el-select v-model="form.insutype" style="width:100%"><el-option v-for="t in insutypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="参保地区划"><el-input v-model="form.insuplcAdmdvs" placeholder="如 420100"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="就诊凭证类型"><el-select v-model="form.mdtrtCertType" style="width:100%"><el-option v-for="t in certTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="就诊凭证编号"><el-input v-model="form.mdtrtCertNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保人员编号"><el-input v-model="form.psnNo" placeholder="psn_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="患者号"><el-input v-model="form.patientNo" placeholder="留空自动生成" :disabled="!editing"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人电话"><el-input v-model="form.contactPhone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="正常" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.memo" type="textarea"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="readCard">读卡(模拟)</el-button>',
      '      <el-button @click="dlg=false">取消</el-button>',
      '      <el-button type="primary" @click="submit">确定</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 门诊挂号台 ================= */
  HIS.views.RegistrationDesk = {
    data: function () {
      return {
        /* 患者选择 */
        pKeyword: '', pLoading: false, patients: [], selectedPatient: null,
        /* 号源选择 */
        depts: [], staffs: [], filterDept: null, filterDate: today(),
        sLoading: false, schedules: [], selectedSchedule: null,
        medType: '11', submitting: false, lastReg: null,
        timeTypes: TIME_TYPES
      };
    },
    created: function () { this.loadRefs(); },
    methods: {
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师')).then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
      },
      searchPatients: function () {
        var vm = this; vm.pLoading = true;
        var q = '/api/his/patient/page?page=1&size=20';
        if (vm.pKeyword) { q += '&keyword=' + encodeURIComponent(vm.pKeyword); }
        HIS.get(q).then(function (d) { vm.patients = (d && d.records) || []; }).catch(HIS.notifyError).finally(function () { vm.pLoading = false; });
      },
      selectPatient: function (row) {
        this.selectedPatient = row;
        HIS.notifySuccess('已选择患者: ' + row.name);
      },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return '-'; },
      staffName: function (id) { for (var i = 0; i < this.staffs.length; i++) { if (this.staffs[i].id === id) { return this.staffs[i].staffName; } } return '-'; },
      loadSchedules: function () {
        var vm = this; vm.sLoading = true; vm.selectedSchedule = null;
        var q = '/api/his/schedule/list?1=1';
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterDate) { q += '&from=' + vm.filterDate + '&to=' + vm.filterDate; }
        HIS.get(q).then(function (d) {
          var all = d || [];
          vm.schedules = all.filter(function (s) { return s.status === 1; });
        }).catch(HIS.notifyError).finally(function () { vm.sLoading = false; });
      },
      chooseSchedule: function (row) { this.selectedSchedule = row; },
      doRegister: function () {
        var vm = this;
        if (!vm.selectedPatient) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.selectedSchedule) { ElementPlus.ElMessage.warning('请选择一个号源'); return; }
        if (vm.selectedSchedule.leftNum <= 0) { ElementPlus.ElMessage.warning('该号源已无余号'); return; }
        vm.submitting = true;
        var url = '/api/his/registration/register?patientId=' + vm.selectedPatient.id +
          '&scheduleId=' + vm.selectedSchedule.id + '&medType=' + vm.medType;
        HIS.post(url).then(function (reg) {
          vm.lastReg = reg;
          HIS.notifySuccess('挂号成功! 挂号单号 ' + reg.regNo);
          vm.loadSchedules();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      timeLabel: timeLabel
    },
    template: [
      '<div>',
      '  <div class="page-card">',
      '    <div class="page-title">门诊挂号台 <span style="font-size:12px;color:#909399;font-weight:normal;">(挂号时调用医保2201, 回填就诊ID mdtrt_id)</span></div>',
      '    <el-alert v-if="lastReg" type="success" :closable="true" show-icon style="margin-bottom:12px;"',
      '      :title="\'挂号成功: \'+lastReg.patientName+\'  \'+lastReg.deptName+\' \'+lastReg.drName+\'  挂号费￥\'+lastReg.regFee"',
      '      :description="\'挂号单号 \'+lastReg.regNo+\'  |  门诊号 \'+lastReg.iptOtpNo+\'  |  医保就诊ID \'+(lastReg.mdtrtId||\'-\')"></el-alert>',
      /* ---- 第一步: 选择患者 ---- */
      '    <el-divider content-position="left">第一步 · 选择患者</el-divider>',
      '    <div v-if="selectedPatient" style="margin-bottom:10px;">',
      '      <el-tag type="success" size="large" closable @close="selectedPatient=null">',
      '        {{ selectedPatient.name }} | {{ selectedPatient.gender }} | {{ selectedPatient.age||"-" }}岁 | 患者号 {{ selectedPatient.patientNo }} | 医保 {{ selectedPatient.psnNo||"无" }}',
      '      </el-tag>',
      '    </div>',
      '    <div class="toolbar">',
      '      <el-input v-model="pKeyword" placeholder="姓名/身份证/患者号/电话" clearable style="width:280px" @keyup.enter="searchPatients"></el-input>',
      '      <el-button type="primary" @click="searchPatients">检索患者</el-button>',
      '      <span style="color:#909399;font-size:13px;">未建档请先到“患者建档”页新增</span>',
      '    </div>',
      '    <el-table :data="patients" v-loading="pLoading" border stripe size="small" height="200" highlight-current-row @current-change="selectPatient">',
      '      <el-table-column prop="patientNo" label="患者号" width="170"></el-table-column>',
      '      <el-table-column prop="name" label="姓名" width="90"></el-table-column>',
      '      <el-table-column prop="gender" label="性别" width="55"></el-table-column>',
      '      <el-table-column prop="age" label="年龄" width="55"></el-table-column>',
      '      <el-table-column prop="idCard" label="身份证号" width="170"></el-table-column>',
      '      <el-table-column prop="psnNo" label="医保编号" width="130"></el-table-column>',
      '      <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" @click="selectPatient(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      /* ---- 第二步: 选择号源 ---- */
      '    <el-divider content-position="left">第二步 · 选择号源</el-divider>',
      '    <div class="toolbar">',
      '      <el-select v-model="filterDept" placeholder="全部科室" clearable style="width:160px" @change="loadSchedules"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '      <el-date-picker v-model="filterDate" type="date" value-format="YYYY-MM-DD" placeholder="出诊日期" style="width:160px" @change="loadSchedules"></el-date-picker>',
      '      <el-button @click="loadSchedules">查询号源</el-button>',
      '      <el-select v-model="medType" style="width:140px"><el-option label="11-普通门诊" value="11"></el-option><el-option label="92-门诊慢特病" value="92"></el-option></el-select>',
      '      <el-button type="primary" :loading="submitting" @click="doRegister">确认挂号</el-button>',
      '    </div>',
      '    <el-table :data="schedules" v-loading="sLoading" border stripe size="small" height="240" highlight-current-row @current-change="chooseSchedule">',
      '      <el-table-column label="科室" width="120"><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '      <el-table-column label="医师" width="100"><template #default="s">{{ staffName(s.row.staffId) }}</template></el-table-column>',
      '      <el-table-column prop="workDate" label="出诊日期" width="120"></el-table-column>',
      '      <el-table-column label="时段" width="70"><template #default="s">{{ timeLabel(s.row.timeType) }}</template></el-table-column>',
      '      <el-table-column prop="regLevelName" label="号别" width="100"></el-table-column>',
      '      <el-table-column prop="regFee" label="挂号费" width="80"></el-table-column>',
      '      <el-table-column label="余号/总号" width="100"><template #default="s">',
      '        <el-tag size="small" :type="s.row.leftNum>0?\'success\':\'danger\'">{{ s.row.leftNum }} / {{ s.row.totalNum }}</el-tag>',
      '      </template></el-table-column>',
      '      <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" :disabled="s.row.leftNum<=0" @click="chooseSchedule(s.row)">选号</el-button></template></el-table-column>',
      '    </el-table>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ================= 退号 ================= */
  HIS.views.UnregisterDesk = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 15,
        range: [], status: 1, keyword: '', regStatus: REG_STATUS
      };
    },
    created: function () {
      this.range = [today(), today()];
      this.load();
    },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/registration/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.range && vm.range.length === 2) { q += '&from=' + vm.range[0] + '&to=' + vm.range[1]; }
        if (vm.status !== null && vm.status !== '') { q += '&status=' + vm.status; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      doCancel: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('请输入退号原因(可留空)', '退号确认 - ' + row.patientName, {
          confirmButtonText: '确认退号', cancelButtonText: '取消', inputPlaceholder: '如: 患者临时有事'
        }).then(function (r) {
          var reason = r.value || '';
          return HIS.post('/api/his/registration/cancel?id=' + row.id + '&reason=' + encodeURIComponent(reason));
        }).then(function () {
          HIS.notifySuccess('退号成功(医保2202已撤销)');
          vm.load();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      timeLabel: timeLabel,
      statusLabel: statusLabel,
      statusTag: statusTag
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">退号 <span style="font-size:12px;color:#909399;font-weight:normal;">(退号调用医保2202撤销, 并回滚号源)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px" @change="search"></el-date-picker>',
      '    <el-select v-model="status" placeholder="全部状态" clearable style="width:130px" @change="search"><el-option v-for="s in regStatus" :key="s.v" :label="s.l" :value="s.v"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="患者/挂号单号/门诊号" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="regNo" label="挂号单号" width="170"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="110"></el-table-column>',
      '    <el-table-column prop="drName" label="医师" width="90"></el-table-column>',
      '    <el-table-column prop="workDate" label="出诊日期" width="110"></el-table-column>',
      '    <el-table-column label="时段" width="70"><template #default="s">{{ timeLabel(s.row.timeType) }}</template></el-table-column>',
      '    <el-table-column prop="regFee" label="挂号费" width="75"></el-table-column>',
      '    <el-table-column prop="iptOtpNo" label="门诊号" width="170"></el-table-column>',
      '    <el-table-column prop="mdtrtId" label="医保就诊ID" width="120"></el-table-column>',
      '    <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="80" fixed="right"><template #default="s">',
      '      <el-button link type="danger" :disabled="s.row.status!==1" @click="doCancel(s.row)">退号</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="prev, pager, next, total" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '</div>'
    ].join('\n')
  };
})();
