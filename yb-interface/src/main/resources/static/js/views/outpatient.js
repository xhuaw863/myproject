/* 门诊挂号台: 患者建档(档案管理) + 门诊挂号工作站(医保2201/2202, 含退号/重挂/凭条) */
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
    { v: 1, l: '已挂号', t: 'primary' },
    { v: 2, l: '已退号', t: 'danger' },
    { v: 3, l: '已就诊', t: 'success' }
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

  /* ================= 换号(挂号工作站今日记录与退号页共用) =================
   * ChangeSlotMixin + CS_DIALOG: 原号摘要 + 目标号源筛选(日期/科室/医师)与选行 -> POST /registration/changeSlot
   * (同一事务内先退原号2202再挂新号2201); 宿主页面需有 depts/staffs 参照数据, 并自行实现 afterChangeSlot(newReg) 刷新。 */
  /* /schedule/list 分页行为下划线列名: 归一化为驼峰供换号目标表渲染 */
  function normScheduleRow(s) {
    return {
      id: s.id,
      deptId: s.deptId != null ? s.deptId : s.dept_id,
      deptName: s.deptName || s.dept_name,
      staffId: s.staffId != null ? s.staffId : s.staff_id,
      staffName: s.staffName || s.staff_name,
      workDate: s.workDate || s.work_date,
      timeType: s.timeType || s.time_type,
      regLevelCode: s.regLevelCode || s.reg_level_code,
      regLevelName: s.regLevelName || s.reg_level_name,
      regFee: s.regFee != null ? s.regFee : s.reg_fee,
      totalNum: s.totalNum != null ? s.totalNum : s.total_num,
      leftNum: s.leftNum != null ? s.leftNum : s.left_num,
      status: s.status
    };
  }
  var ChangeSlotMixin = {
    data: function () {
      return {
        csDlg: false, csRow: null, csSaving: false,
        csDate: '', csDept: null, csStaff: null,
        csSchedules: [], csLoading: false, csSelected: null, csReason: ''
      };
    },
    computed: {
      /* 科室下拉仅科室级节点(deptLevel=2), 与两页主流程口径一致 */
      csDeptOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      csStaffOptions: function () {
        var dep = this.csDept;
        if (!dep) { return this.staffs || []; }
        return (this.staffs || []).filter(function (s) { return s.deptId === dep; });
      },
      /* 原号已收非免收费用 -> 换号后需退费提示(与退号页 needRefundHint 同口径) */
      csNeedRefundHint: function () {
        var pm = this.csRow && this.csRow.payMethod;
        return !!pm && String(pm).trim() !== 'free';
      }
    },
    methods: {
      openChangeSlot: function (row) {
        var vm = this;
        vm.csRow = row; vm.csReason = ''; vm.csSelected = null;
        vm.csDept = row.deptId || null; vm.csStaff = null;
        vm.csDate = row.workDate || today();
        vm.csDlg = true;
        vm.csLoadSchedules();
      },
      /* 科室切换: 清空不属于该科室的已选医师后重查号源 */
      csOnFilterChange: function () {
        var vm = this;
        if (vm.csStaff && vm.csDept) {
          var ok = false;
          (vm.staffs || []).forEach(function (s) { if (s.id === vm.csStaff && s.deptId === vm.csDept) { ok = true; } });
          if (!ok) { vm.csStaff = null; }
        }
        vm.csLoadSchedules();
      },
      csLoadSchedules: function () {
        var vm = this; vm.csLoading = true; vm.csSelected = null;
        var q = '/api/his/schedule/list?1=1&page=1&size=200';
        if (vm.csDept) { q += '&deptId=' + vm.csDept; }
        if (vm.csStaff) { q += '&staffId=' + vm.csStaff; }
        if (vm.csDate) { q += '&from=' + vm.csDate + '&to=' + vm.csDate; }
        HIS.get(q).then(function (d) {
          var all = (d && d.records) ? d.records : (d || []);
          /* 仅开诊号源; 排除原号自身号源(同槽换号无意义, 后端同样有兑底拦截) */
          vm.csSchedules = all.map(normScheduleRow).filter(function (s) {
            return s.status === 1 && (!vm.csRow || !vm.csRow.scheduleId || s.id !== vm.csRow.scheduleId);
          });
        }).catch(HIS.notifyError).finally(function () { vm.csLoading = false; });
      },
      csPickRow: function (row) {
        if (!row) { return; }
        if (row.leftNum <= 0) { ElementPlus.ElMessage.warning('该号源已无余号, 不可选为换号目标'); return; }
        this.csSelected = row;
      },
      csMoney: function (v) { return (Number(v) || 0).toFixed(2); },
      csSubmit: function () {
        var vm = this;
        if (!vm.csRow || !vm.csSelected) { ElementPlus.ElMessage.warning('请先选择目标号源'); return; }
        var s = vm.csSelected;
        var tip = '换号将撤销原号(医保2202)并按目标号源重新挂号(医保2201)。目标: <b>'
          + s.deptName + ' ' + (s.staffName || '-') + ' ' + s.workDate + ' ' + timeLabel(s.timeType)
          + ' ' + (s.regLevelName || '-') + ' ¥' + vm.csMoney(s.regFee) + '</b>';
        ElementPlus.ElMessageBox.confirm(tip, '换号确认 - ' + (vm.csRow.patientName || ''), {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认换号', cancelButtonText: '取消'
        }).then(function () {
          vm.csSaving = true;
          return HIS.post('/api/his/registration/changeSlot?id=' + vm.csRow.id + '&scheduleId=' + s.id
            + '&reason=' + encodeURIComponent(vm.csReason || ''))
            .then(function (d) {
              var nr = (d && d.newReg) || {};
              var msg = '换号成功: 新单号 ' + (nr.regNo || '-') + ', 候诊号 ' + (nr.queueNo || '-');
              if (d && d.needRefund) { msg += '; 原号实收 ¥' + vm.csMoney(d.refundAmount) + ' 请到收费窗口办理退费'; }
              ElementPlus.ElMessage.success(msg);
              vm.csDlg = false;
              if (typeof vm.afterChangeSlot === 'function') { vm.afterChangeSlot(nr); }
            }).catch(HIS.notifyError)
            .finally(function () { vm.csSaving = false; });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      }
    }
  };
  /* 换号对话框模板(作为单个字符串嵌入两页模板数组末尾) */
  var CS_DIALOG = [
    '<el-dialog v-model="csDlg" :title="csRow ? (\'换号 - \' + csRow.patientName) : \'换号\'" width="860px" top="6vh">',
    '  <div v-if="csRow">',
    '    <el-descriptions :column="3" border size="small">',
    '      <el-descriptions-item label="原科室">{{ csRow.deptName || "-" }}</el-descriptions-item>',
    '      <el-descriptions-item label="原医师">{{ csRow.drName || "-" }}</el-descriptions-item>',
    '      <el-descriptions-item label="原出诊">{{ csRow.workDate || "-" }} {{ timeLabel(csRow.timeType) }}</el-descriptions-item>',
    '      <el-descriptions-item label="原号别">{{ csRow.regLevelName || "-" }}</el-descriptions-item>',
    '      <el-descriptions-item label="原挂号费">¥{{ csMoney(csRow.regFee) }}</el-descriptions-item>',
    '      <el-descriptions-item label="原实收">¥{{ csMoney(csRow.actualFee) }}</el-descriptions-item>',
    '    </el-descriptions>',
    '    <el-alert v-if="csNeedRefundHint" type="warning" :closable="false" show-icon title="原号已收费: 换号后请到收费窗口办理原号退费, 新号费用另行收取" style="margin-top:8px;"></el-alert>',
    '    <el-divider content-position="left">选择目标号源</el-divider>',
    '    <div class="toolbar" style="margin-bottom:8px;">',
    '      <el-date-picker v-model="csDate" type="date" value-format="YYYY-MM-DD" placeholder="出诊日期" style="width:150px" @change="csLoadSchedules"></el-date-picker>',
    '      <el-select v-model="csDept" placeholder="全部科室" clearable filterable style="width:160px" @change="csOnFilterChange">',
    '        <el-option v-for="d in csDeptOptions" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
    '      </el-select>',
    '      <el-select v-model="csStaff" placeholder="全部医师" clearable filterable style="width:140px" @change="csLoadSchedules">',
    '        <el-option v-for="s in csStaffOptions" :key="s.id" :label="s.staffName" :value="s.id"></el-option>',
    '      </el-select>',
    '      <el-button type="primary" size="small" :loading="csLoading" @click="csLoadSchedules">查询号源</el-button>',
    '      <span style="color:var(--yb-ink-2);font-size:12px;">单击行选中目标号源(余号为0不可选)</span>',
    '    </div>',
    '    <el-table :data="csSchedules" v-loading="csLoading" border stripe size="small" max-height="280" highlight-current-row @row-click="csPickRow">',
    '      <el-table-column type="index" label="序号" width="56"></el-table-column>',
    '      <el-table-column prop="deptName" label="科室" min-width="110" show-overflow-tooltip></el-table-column>',
    '      <el-table-column prop="staffName" label="医师" width="90"></el-table-column>',
    '      <el-table-column prop="workDate" label="出诊日期" width="100"></el-table-column>',
    '      <el-table-column label="时段" width="70"><template #default="s">{{ timeLabel(s.row.timeType) }}</template></el-table-column>',
    '      <el-table-column prop="regLevelName" label="号别" width="90" show-overflow-tooltip></el-table-column>',
    '      <el-table-column label="挂号费" width="80" align="right"><template #default="s">¥{{ csMoney(s.row.regFee) }}</template></el-table-column>',
    '      <el-table-column label="余号/总数" width="90" align="center"><template #default="s"><span :style="s.row.leftNum <= 0 ? \'color:var(--yb-danger);font-weight:600;\' : \'color:var(--yb-success);\'">{{ s.row.leftNum }}/{{ s.row.totalNum }}</span></template></el-table-column>',
    '    </el-table>',
    '    <div v-if="csSelected" style="margin-top:8px;font-size:13px;color:var(--yb-link);">已选目标: {{ csSelected.deptName }} · {{ csSelected.staffName || \'-\' }} · {{ csSelected.workDate }} {{ timeLabel(csSelected.timeType) }} · {{ csSelected.regLevelName || \'-\' }} ¥{{ csMoney(csSelected.regFee) }}</div>',
    '    <el-input v-model="csReason" maxlength="100" placeholder="换号原因(选填, 将并入退号原因留痕)" style="margin-top:10px;"></el-input>',
    '  </div>',
    '  <template #footer>',
    '    <el-button @click="csDlg=false">取消</el-button>',
    '    <el-button type="primary" :loading="csSaving" :disabled="!csSelected" @click="csSubmit">确认换号</el-button>',
    '  </template>',
    '</el-dialog>'
  ].join('\n');

  /* ================= 档案管理(原患者建档/查询, 2026-09 并入医共体管理) ================= */
  HIS.views.PatientManage = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20, keyword: '',
        gendOpts: [], insutypeOpts: [], certTypeOpts: [],
        gendMap: {}, insutypeMap: {}, certTypeMap: {},
        /* A 身份人口学字典(医保 cv_code 优先, 其余湖北采集规范 hbvalue) */
        idTypeOpts: [], nationOpts: [], natlOpts: [], maritalOpts: [], eduOpts: [], occupOpts: [], relOpts: [],
        idTypeMap: {}, nationMap: {}, natlMap: {}, maritalMap: {}, eduMap: {}, occupMap: {}, relMap: {},
        areaOpts: [], areaLoading: false, orgs: [],
        /* 各地址级联选中路径 [省,市,区县,乡镇]: present/birth/household/mail/emp/contact */
        areaPaths: { present: [], birth: [], household: [], mail: [], emp: [], contact: [] },
        /* 建档弹窗 tab 页签: basic-基本信息 / insu-医保信息 */
        activeTab: 'basic',
        /* el-cascader 懒加载配置(逐级下钻 area_code_2021; checkStrictly 允许选任意一级) */
        cascaderProps: { lazy: true, lazyLoad: this.areaLazyLoad, checkStrictly: true, value: 'value', label: 'label', leaf: 'leaf', expandTrigger: 'hover' },
        dlg: false, editing: false, form: this.empty(),
        /* 修改记录弹窗 */
        logDlg: false, logLoading: false, logPatientName: '', logList: [],
        /* 医保参保信息记录(读卡完整记录, 一人可多条) */
        insuList: [], insuLoading: false
      };
    },
    created: function () { this.loadDicts(); this.loadOrgs(); this.load(); },
    methods: {
      empty: function () {
        return {
          id: null, patientNo: '', psnNo: '', name: '', gender: '1', birthDate: '', age: null,
          idCard: '', phone: '', address: '', insutype: '310', mdtrtCertType: '2', mdtrtCertNo: '',
          insuplcAdmdvs: '', contactName: '', contactPhone: '', status: 1, orgId: null, memo: '',
          /* A 身份人口学(默认: 居民身份证/汉族/中国) */
          certType: '01', nation: '1', nationality: '156', maritalStatus: '', eduLevel: '',
          occupation: '', occupationOther: '',
          /* B 现住址四级级联 + 详细 */
          presentProv: '', presentCity: '', presentCounty: '', presentTown: '', presentDetail: '',
          /* B2 出生地/户籍/通讯/单位/联系人 四级级联(编码) + 详细(沿用原文本列) */
          birthProv: '', birthCity: '', birthCounty: '', birthTown: '', birthDetail: '',
          householdProv: '', householdCity: '', householdCounty: '', householdTown: '', householdAddr: '',
          mailProv: '', mailCity: '', mailCounty: '', mailTown: '',
          empProv: '', empCity: '', empCounty: '', empTown: '',
          employer: '', employerPhone: '', employerAddr: '',
          /* C 联系人与患者关系 */
          contactRelation: '', contactIdCard: '', contactAddr: '',
          contactProv: '', contactCity: '', contactCounty: '', contactTown: ''
        };
      },
      loadDicts: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'gend').then(function (l) { vm.gendOpts = l || []; vm.gendMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('cv_code', 'insutype').then(function (l) { vm.insutypeOpts = l || []; vm.insutypeMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('cv_code', 'mdtrt_cert_type').then(function (l) { vm.certTypeOpts = l || []; vm.certTypeMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        /* A 身份人口学: 证件类别/民族 取医保字典; 国籍/婚姻/文化程度/职业/关系 取湖北采集规范值域 */
        HIS.stdValues('cv_code', 'psn_cert_type').then(function (l) { vm.idTypeOpts = l || []; vm.idTypeMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('cv_code', 'naty').then(function (l) { vm.nationOpts = l || []; vm.nationMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('hbvalue', 'GB/T 2659.1-2022').then(function (l) { vm.natlOpts = l || []; vm.natlMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('hbvalue', 'GB/T 2261.2-2003').then(function (l) { vm.maritalOpts = l || []; vm.maritalMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('hbvalue', 'GB/T 4658-2006').then(function (l) { vm.eduOpts = l || []; vm.eduMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('hbvalue', 'CV02.01.202').then(function (l) { vm.occupOpts = l || []; vm.occupMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('hbvalue', 'GB/T 4761-2008').then(function (l) { vm.relOpts = l || []; vm.relMap = HIS.dictMap(l); }).catch(HIS.notifyError);
      },
      /* 现住址 el-cascader 懒加载: 根节点取省(level=1), 其余按父级编码下钻 */
      areaLazyLoad: function (node, resolve) {
        var lvl = node.level; var val = node.value;
        var pcode = lvl === 0 ? null : val;
        var level = lvl === 0 ? 1 : null;
        HIS.areaChildren(pcode, level).then(function (list) { resolve(list || []); }).catch(function () { resolve([]); });
      },
      /* 级联选择变化: 按前缀回填 <prefix>Prov/City/County/Town 编码 */
      onAreaChange: function (prefix, vals) {
        var f = this.form; var v = vals || [];
        f[prefix + 'Prov'] = v[0] || ''; f[prefix + 'City'] = v[1] || '';
        f[prefix + 'County'] = v[2] || ''; f[prefix + 'Town'] = v[3] || '';
      },
      /* 由行数据重构某地址级联选中路径(仅非空级别) */
      buildAreaPath: function (row, prefix) {
        var p = [];
        if (row[prefix + 'Prov']) { p.push(String(row[prefix + 'Prov'])); }
        if (row[prefix + 'City']) { p.push(String(row[prefix + 'City'])); }
        if (row[prefix + 'County']) { p.push(String(row[prefix + 'County'])); }
        if (row[prefix + 'Town']) { p.push(String(row[prefix + 'Town'])); }
        this.areaPaths[prefix] = p;
      },
      /* 编辑/复用时一次性重构全部地址级联路径 */
      buildAllAreaPaths: function (row) {
        var vm = this;
        ['present', 'birth', 'household', 'mail', 'emp', 'contact'].forEach(function (k) { vm.buildAreaPath(row, k); });
      },
      /* 新增建档时清空全部地址级联路径 */
      resetAreaPaths: function () {
        this.areaPaths = { present: [], birth: [], household: [], mail: [], emp: [], contact: [] };
      },
      areaRemoteSearch: function (q) {
        var vm = this; if (!q) { return; } vm.areaLoading = true;
        HIS.areaSearch(q).then(function (list) {
          vm.areaOpts = (list || []).map(function (a) { return { code: String(a.code), name: a.name }; });
        }).catch(HIS.notifyError).finally(function () { vm.areaLoading = false; });
      },
      setAreaOpt: function (code, name) {
        var vm = this;
        if (!code) { vm.areaOpts = []; return; }
        vm.areaOpts = [{ code: String(code), name: name || String(code) }];
        if (!name) { HIS.areaName(code).then(function (nm) { if (nm) { vm.areaOpts = [{ code: String(code), name: nm }]; } }); }
      },
      loadOrgs: function () { var vm = this; HIS.get('/api/sys/org/tree').then(function (d) { vm.orgs = HIS.flattenOrgs(d || []); }).catch(function () { vm.orgs = []; }); },
      orgName: function (id) { for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } } return '-'; },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/patient/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      add: function () { this.editing = false; this.form = this.empty(); this.areaOpts = []; this.resetAreaPaths(); this.activeTab = 'basic'; this.insuList = []; this.dlg = true; },
      edit: function (row) {
        this.editing = true; this.form = clean(Object.assign(this.empty(), row));
        this.setAreaOpt(row.insuplcAdmdvs, row.insuplcAdmdvsName); this.buildAllAreaPaths(row); this.activeTab = 'basic'; this.loadInsu(row.id); this.dlg = true;
      },
      readCard: function () {
        /* 模拟读取医保电子凭证/社保卡(真实环境调用1101人员信息获取) */
        var f = this.form;
        if (!f.idCard) { ElementPlus.ElMessage.warning('请先录入身份证号再读卡'); return; }
        f.mdtrtCertType = '1';
        f.mdtrtCertNo = f.idCard;
        if (!f.psnNo) { f.psnNo = 'PSN' + f.idCard.slice(-8); }
        HIS.notifySuccess('读卡成功(模拟): 已回填医保凭证信息');
        /* 已建档患者读卡时同步保存 1101 返读的完整参保信息记录 */
        if (this.editing && this.form.id) { this.syncInsu(); }
      },
      calcAge: function () {
        var f = this.form;
        if (f.birthDate) {
          /* birthDate 为 'yyyy-MM-dd HH:mm:ss', 空格转 T 以兼容各浏览器解析 */
          var b = new Date(String(f.birthDate).replace(' ', 'T'));
          if (!isNaN(b.getTime())) {
            var age = new Date().getFullYear() - b.getFullYear();
            if (age >= 0 && age < 150) { f.age = age; }
          }
        }
        if (!f.mdtrtCertNo && f.idCard) { f.mdtrtCertNo = f.idCard; }
      },
      submit: function () {
        var vm = this;
        if (!vm.form.name) { ElementPlus.ElMessage.warning('患者姓名必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/patient', vm.form) : HIS.post('/api/his/patient', vm.form);
        p.then(function (d) {
          HIS.notifySuccess(vm.editing ? '保存成功' : ('建档/复用成功, 患者号: ' + ((d && d.patientNo) || '')));
          vm.dlg = false; vm.load();
        }).catch(HIS.notifyError);
      },
      /* 医共体统一患者主索引: 按身份证查重, 命中即载入复用同一档案 */
      checkDup: function () {
        var vm = this; var f = vm.form;
        if (!f.idCard) { ElementPlus.ElMessage.warning('请先录入身份证号再查重'); return; }
        HIS.get('/api/his/patient/by-idcard?idCard=' + encodeURIComponent(f.idCard))
          .then(function (d) {
            if (d && d.id) {
              vm.editing = true;
              vm.form = clean(Object.assign(vm.empty(), d));
              vm.setAreaOpt(d.insuplcAdmdvs, d.insuplcAdmdvsName);
              vm.buildAllAreaPaths(d);
              ElementPlus.ElMessageBox.alert('医共体内已存在同一自然人档案(患者号 ' + d.patientNo + '), 已载入复用。', '统一患者主索引', { type: 'success' });
            } else {
              ElementPlus.ElMessage.info('未查到已有档案, 可新建建档');
            }
          }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        HIS.del('/api/his/patient/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError);
      },
      /* 查看患者档案修改记录(字段级留痕, 时间倒序) */
      showLogs: function (row) {
        var vm = this;
        vm.logPatientName = row.name || ''; vm.logList = []; vm.logDlg = true; vm.logLoading = true;
        HIS.get('/api/his/patient/' + row.id + '/change-logs')
          .then(function (d) { vm.logList = d || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.logLoading = false; });
      },
      /* 修改记录: 时间格式化(T 转空格) */
      logTime: function (v) { return v ? String(v).replace('T', ' ') : '-'; },
      /* 修改记录: 来源标签色 */
      logSourceTag: function (s) { return s === '建档' ? 'success' : (s === '医保读卡' ? 'warning' : 'primary'); },
      /* 修改记录: 空值占位 */
      logVal: function (v) { return (v === null || v === undefined || v === '') ? '（空）' : v; },
      /* 参保信息记录: 加载该患者读卡完整记录(一人可多条) */
      loadInsu: function (id) {
        var vm = this; vm.insuList = [];
        if (!id) { return; }
        vm.insuLoading = true;
        HIS.get('/api/his/patient/' + id + '/insu').then(function (d) { vm.insuList = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.insuLoading = false; });
      },
      /* 读卡同步: 模拟 1101 人员信息获取返回完整参保信息列表并覆盖保存 */
      syncInsu: function () {
        var vm = this;
        if (!vm.form.id) { ElementPlus.ElMessage.warning('请先保存建档, 再读卡同步参保信息'); return; }
        vm.insuLoading = true;
        HIS.post('/api/his/patient/' + vm.form.id + '/insu/read-sync')
          .then(function (d) { vm.insuList = d || []; HIS.notifySuccess('读卡同步成功: 共 ' + (d ? d.length : 0) + ' 条参保记录'); })
          .catch(HIS.notifyError).finally(function () { vm.insuLoading = false; });
      },
      delInsu: function (row) {
        var vm = this;
        HIS.del('/api/his/patient/insu/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.loadInsu(vm.form.id); }).catch(HIS.notifyError);
      },
      /* 参保日期展示(yyyy-MM-dd) */
      insuDate: function (v) { return v ? String(v).substring(0, 10) : '-'; },
      insutypeLabel: insutypeLabel
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">档案管理</div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="姓名/患者号/身份证/医保号/电话/拼音简码" clearable style="width:280px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="add">新增建档</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 人</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="patientNo" label="患者号" width="170"></el-table-column>',
      '    <el-table-column prop="name" label="姓名" width="90"></el-table-column>',
      '    <el-table-column label="性别" width="55"><template #default="s">{{ s.row.genderName || gendMap[s.row.gender] || s.row.gender }}</template></el-table-column>',
      '    <el-table-column prop="age" label="年龄" width="55"></el-table-column>',
      '    <el-table-column prop="idCard" label="身份证号" width="170"></el-table-column>',
      '    <el-table-column prop="phone" label="电话" width="120"></el-table-column>',
      '    <el-table-column label="险种" width="150"><template #default="s">{{ s.row.insutypeName || insutypeMap[s.row.insutype] || s.row.insutype || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="psnNo" label="医保人员编号" width="140"></el-table-column>',
      '    <el-table-column label="建档机构" min-width="150" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"正常":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="210" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm title="确认删除该患者档案？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '      <el-button link type="info" @click="showLogs(s.row)">修改记录</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑患者档案\':\'新增患者建档\'" width="900px" top="6vh">',
      '    <div style="max-height:62vh;overflow-y:auto;padding-right:6px;">',
      '    <el-form :model="form" label-width="110px" size="default">',
      '      <el-tabs v-model="activeTab">',
      '        <el-tab-pane label="基本信息" name="basic">',
      '      <el-divider content-position="left">基本信息</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.name"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="性别"><el-select v-model="form.gender" style="width:100%"><el-option v-for="o in gendOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="出生时间"><el-date-picker v-model="form.birthDate" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="新生儿可精确到时分秒" style="width:100%" @change="calcAge"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="年龄"><el-input v-model.number="form.age" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证件类别"><el-select v-model="form.certType" style="width:100%" filterable><el-option v-for="o in idTypeOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证号"><el-input v-model="form.idCard" placeholder="新生儿无证件号可填出生8位" @blur="calcAge"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="民族"><el-select v-model="form.nation" style="width:100%" filterable clearable><el-option v-for="o in nationOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="国籍"><el-select v-model="form.nationality" style="width:100%" filterable clearable><el-option v-for="o in natlOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="婚姻状况"><el-select v-model="form.maritalStatus" style="width:100%" filterable clearable><el-option v-for="o in maritalOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="文化程度"><el-select v-model="form.eduLevel" style="width:100%" filterable clearable><el-option v-for="o in eduOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="职业类别"><el-select v-model="form.occupation" style="width:100%" filterable clearable><el-option v-for="o in occupOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="职业其他"><el-input v-model="form.occupationOther" placeholder="职业为其他时填写"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">地址信息(区划均按 area_code_2021 字典级联选择)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="24"><el-form-item label="现住址"><el-cascader v-model="areaPaths.present" :props="cascaderProps" @change="onAreaChange(\'present\', $event)" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="现住址详细"><el-input v-model="form.presentDetail" placeholder="村/街/路/门牌号"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="出生地"><el-cascader v-model="areaPaths.birth" :props="cascaderProps" @change="onAreaChange(\'birth\', $event)" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="出生地详细"><el-input v-model="form.birthDetail" placeholder="村/街/路/门牌号"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="户籍地址"><el-cascader v-model="areaPaths.household" :props="cascaderProps" @change="onAreaChange(\'household\', $event)" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="户籍详细"><el-input v-model="form.householdAddr" placeholder="村/街/路/门牌号"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="通讯地址"><el-cascader v-model="areaPaths.mail" :props="cascaderProps" @change="onAreaChange(\'mail\', $event)" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="通讯详细"><el-input v-model="form.address" placeholder="HEAD_ADDRESS: 本人或联系人通信地址"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="工作单位"><el-input v-model="form.employer"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="单位电话"><el-input v-model="form.employerPhone"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="单位地址"><el-cascader v-model="areaPaths.emp" :props="cascaderProps" @change="onAreaChange(\'emp\', $event)" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="单位详细"><el-input v-model="form.employerAddr" placeholder="村/街/路/门牌号"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">联系人</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="与患者关系"><el-select v-model="form.contactRelation" style="width:100%" filterable clearable><el-option v-for="o in relOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人姓名"><el-input v-model="form.contactName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人电话"><el-input v-model="form.contactPhone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人证件号"><el-input v-model="form.contactIdCard"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="联系人地址"><el-cascader v-model="areaPaths.contact" :props="cascaderProps" @change="onAreaChange(\'contact\', $event)" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="联系人详细"><el-input v-model="form.contactAddr" placeholder="村/街/路/门牌号"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">其他</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="正常" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.memo" type="textarea"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '        </el-tab-pane>',
      '        <el-tab-pane label="医保信息" name="insu">',
      '      <el-divider content-position="left">医保信息(医保接口返读/读卡回填)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="险种类型"><el-select v-model="form.insutype" style="width:100%" filterable><el-option v-for="o in insutypeOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="参保地区划"><el-select v-model="form.insuplcAdmdvs" style="width:100%" filterable remote clearable :remote-method="areaRemoteSearch" :loading="areaLoading" placeholder="输入名称/编码检索"><el-option v-for="a in areaOpts" :key="a.code" :label="a.name + \' (\' + a.code + \')\'" :value="a.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="就诊凭证类型"><el-select v-model="form.mdtrtCertType" style="width:100%"><el-option v-for="o in certTypeOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="就诊凭证编号"><el-input v-model="form.mdtrtCertNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保人员编号"><el-input v-model="form.psnNo" placeholder="psn_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="患者号"><el-input v-model="form.patientNo" placeholder="留空自动生成" :disabled="!editing"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">参保信息记录(【1101】人员基本信息获取输出节点 insuinfo, 一人可多条)</el-divider>',
      '      <div class="toolbar">',
      '        <el-button type="primary" size="small" :loading="insuLoading" @click="syncInsu">读卡同步参保信息</el-button>',
      '        <el-button size="small" @click="loadInsu(form.id)">刷新</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ insuList.length }} 条参保记录; 读卡按来源"医保读卡(1101)"覆盖保存, 重复读卡不产生重复</span>',
      '      </div>',
      '      <el-table :data="insuList" v-loading="insuLoading" border stripe size="small" style="width:100%">',
      '        <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '        <el-table-column prop="insutypeName" label="险种(insutype)" width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="psnTypeName" label="人员类别(psn_type)" width="120" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="参保状态" width="90"><template #default="s"><el-tag size="small" :type="s.row.psnInsuStas===\'1\'?\'success\':\'info\'">{{ s.row.psnInsuStasName || (s.row.psnInsuStas===\'1\'?"参保":"停保") }}</el-tag></template></el-table-column>',
      '        <el-table-column label="个人参保日期" width="110"><template #default="s">{{ insuDate(s.row.psnInsuDate) }}</template></el-table-column>',
      '        <el-table-column label="暂停参保日期" width="110"><template #default="s">{{ s.row.pausInsuDate ? insuDate(s.row.pausInsuDate) : "当前在保" }}</template></el-table-column>',
      '        <el-table-column prop="cvlservFlagName" label="公务员" width="70"></el-table-column>',
      '        <el-table-column prop="insuplcAdmdvsName" label="参保地" width="90" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="balc" label="余额" width="90"></el-table-column>',
      '        <el-table-column prop="empName" label="单位名称(参保单位)" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="src" label="来源" width="110"></el-table-column>',
      '        <el-table-column label="操作" width="60" fixed="right"><template #default="s"><el-button link type="danger" @click="delInsu(s.row)">删除</el-button></template></el-table-column>',
      '      </el-table>',
      '        </el-tab-pane>',
      '      </el-tabs>',
      '    </el-form>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="readCard">读卡(模拟)</el-button>',
      '      <el-button type="warning" plain @click="checkDup">查重/复用</el-button>',
      '      <el-button @click="dlg=false">取消</el-button>',
      '      <el-button type="primary" @click="submit">确定</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="logDlg" :title="\'修改记录 - \'+logPatientName" width="900px" top="6vh">',
      '    <el-table :data="logList" v-loading="logLoading" border stripe size="small" max-height="460">',
      '      <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '      <el-table-column label="修改时间" width="160"><template #default="s">{{ logTime(s.row.createTime) }}</template></el-table-column>',
      '      <el-table-column label="修改人" width="100"><template #default="s">{{ s.row.changeByName || s.row.createBy || \'-\' }}</template></el-table-column>',
      '      <el-table-column label="来源" width="90"><template #default="s"><el-tag size="small" :type="logSourceTag(s.row.source)">{{ s.row.source || \'-\' }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="fieldLabel" label="字段" width="120"></el-table-column>',
      '      <el-table-column label="修改前" min-width="160" show-overflow-tooltip><template #default="s">{{ logVal(s.row.oldValue) }}</template></el-table-column>',
      '      <el-table-column label="修改后" min-width="160" show-overflow-tooltip><template #default="s">{{ logVal(s.row.newValue) }}</template></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="logDlg=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 门诊挂号工作站(三段式) =================
   * 顶部今日统计 + 左栏(患者检索/读卡/快速建档/挂号表单) + 右栏(号源选择/今日挂号记录含退号重挂)
   * 挂号调医保2201, 退号调医保2202; 快捷键 F2/F3/F4/F5/F8/Esc
   */
  HIS.views.RegistrationDesk = {
    mixins: [HIS.kwSelectMixin, ChangeSlotMixin],
    data: function () {
      return {
        /* 顶部统计栏(GET todaySummary, 挂号/退号后刷新) */
        summary: { registered: 0, cancelled: 0, waiting: 0, visited: 0, discountCount: 0, discountTotal: 0, actualTotal: 0 },
        /* 患者快速检索: 防抖300ms, >=2字符触发, 候选下拉最多10条 */
        pKeyword: '', pLoading: false, pCandidates: [], searchDropVisible: false,
        cardReading: false,
        selectedPatient: null, lastVisit: null,
        /* 挂号表单: 医疗类别/费别/挂号类别/挂号减免/支付方式/备注 */
        medType: '11', feeType: 'self', regCaty: 'normal',
        discountType: 'none', discountReason: '', payMethod: 'cash', remark: '',
        submitting: false,
        /* 号源筛选: 科室/医师(联动)/日期(今明后快捷)/时段勾选 */
        depts: [], staffs: [], filterDept: null, filterStaff: null, filterDate: today(),
        timeChecked: ['am', 'pm', 'night'], timeTypes: TIME_TYPES,
        sLoading: false, schedules: [], selectedSchedule: null,
        /* 今日挂号记录(30秒自动刷新) */
        regList: [], regTotal: 0, regPage: 1, regSize: 20, regKeyword: '', regStatus: '',
        regLoading: false, onlyMyWindow: false, regStatusOpts: REG_STATUS,
        /* 快速建档弹窗字典 */
        gendOpts: [], insutypeOpts: [],
        /* 快速建档弹窗(读卡来源字段置灰) */
        buildDlg: false, buildSaving: false, buildForm: this.buildEmpty(),
        buildLocked: { name: false, gender: false, birthDate: false, idCard: false, psnNo: false, insutype: false },
        buildAreaPath: [],
        buildCascaderProps: { lazy: true, lazyLoad: this.buildAreaLazyLoad, checkStrictly: true, value: 'value', label: 'label', leaf: 'leaf', expandTrigger: 'hover' },
        /* 挂号凭条(模拟热敏打印) */
        ticketDlg: false, ticketRow: null
      };
    },
    computed: {
      /* 挂号科室下拉仅列科室级节点(deptLevel=2): 诊室(第3层)不作为科室可选;
         depts 仍保留全量供 deptName(id) 解析历史号源的诊室科室 */
      deptFilterOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      /* 医师下拉联动科室: 选科室后仅列该科室医师 */
      staffOptions: function () {
        var vm = this;
        if (!vm.filterDept) { return vm.staffs || []; }
        return (vm.staffs || []).filter(function (s) { return s.deptId === vm.filterDept; });
      },
      /* 号源客户端二次过滤: 医师 + 时段勾选 */
      filteredSchedules: function () {
        var vm = this;
        return (vm.schedules || []).filter(function (s) {
          if (vm.filterStaff && s.staffId !== vm.filterStaff) { return false; }
          if (vm.timeChecked && vm.timeChecked.length && vm.timeChecked.indexOf(s.timeType) < 0) { return false; }
          return true;
        });
      },
      /* 退号率 = 退号 / (有效挂号 + 退号) */
      cancelRate: function () {
        var s = this.summary || {};
        var base = (Number(s.registered) || 0) + (Number(s.cancelled) || 0);
        return base > 0 ? ((Number(s.cancelled) || 0) * 100 / base).toFixed(1) + '%' : '0.0%';
      },
      /* 支付方式选项随费别动态切换 */
      payMethodOptions: function () {
        if (this.feeType === 'insurance') {
          return [{ v: 'insurance', l: '医保结算' }, { v: 'self_pay', l: '自费结算' }];
        }
        return [{ v: 'cash', l: '现金' }, { v: 'card', l: '银行卡' }, { v: 'wechat', l: '微信' }, { v: 'alipay', l: '支付宝' }, { v: 'free', l: '免收' }];
      },
      patientAge: function () {
        var p = this.selectedPatient || {};
        if (p.age != null) { return Number(p.age) || 0; }
        if (p.birthDate) {
          var b = new Date(String(p.birthDate).replace(' ', 'T'));
          if (!isNaN(b.getTime())) { return new Date().getFullYear() - b.getFullYear(); }
        }
        return null;
      },
      /* 患者卡片左边框: 医保=绿 / 减免=紫 / 自费=橙 */
      patientCardCls: function () {
        if (this.feeType === 'insurance') { return 'reg-patient-card--insurance'; }
        return this.discountType !== 'none' ? 'reg-patient-card--discount' : 'reg-patient-card--self';
      },
      /* 身份证脱敏: 中间8位* */
      maskIdCardC: function () {
        var v = ((this.selectedPatient || {}).idCard) || '';
        if (v.length < 11) { return v || '-'; }
        var mid = Math.min(8, v.length - 10);
        return v.slice(0, v.length - 6 - mid) + new Array(mid + 1).join('*') + v.slice(-6);
      },
      /* 医保号脱敏: 中间4位* */
      maskPsnNoC: function () {
        var v = ((this.selectedPatient || {}).psnNo) || '';
        if (!v) { return '-'; }
        if (v.length < 6) { return v; }
        var h = Math.floor((v.length - 4) / 2);
        return v.slice(0, h) + '****' + v.slice(h + 4);
      },
      /* 费用摘要: 非 none 减免默认全免(与后端 calcDiscount 规则一致) */
      discountAmountC: function () {
        var fee = this.selectedSchedule ? Number(this.selectedSchedule.regFee) || 0 : 0;
        return this.discountType !== 'none' ? fee : 0;
      },
      actualFeeC: function () {
        var fee = this.selectedSchedule ? Number(this.selectedSchedule.regFee) || 0 : 0;
        return Math.max(0, fee - this.discountAmountC);
      }
    },
    watch: {
      /* 费别切换: 支付方式选项联动重置 */
      feeType: function (v) {
        this.payMethod = v === 'insurance' ? 'insurance' : (this.discountType !== 'none' ? 'free' : 'cash');
      },
      /* 减免切换: 全额减免时自费场景自动免收 */
      discountType: function (v) {
        if (v !== 'none' && this.feeType === 'self') { this.payMethod = 'free'; }
      },
      /* 医师/时段过滤使选中号源行消失时清空选择 */
      filteredSchedules: function (arr) {
        var vm = this;
        if (vm.selectedSchedule) {
          var ok = (arr || []).some(function (s) { return s.id === vm.selectedSchedule.id; });
          if (!ok) { vm.selectedSchedule = null; }
        }
      }
    },
    created: function () {
      this.loadRefs();
      this.loadDicts();
      this.loadSchedules();
      this.loadSummary();
      this.loadRegs();
    },
    mounted: function () {
      var vm = this;
      /* 键盘快捷键: F2搜索/F3读医保卡/F4读身份证/F5确认挂号(阻默认刷新)/F8清除患者/Esc关闭弹窗 */
      vm._onKey = function (e) { vm.handleKeydown(e); };
      document.addEventListener('keydown', vm._onKey);
      /* 今日挂号记录每30秒自动刷新(同时刷新统计) */
      vm._timer = setInterval(function () { vm.loadRegs(); vm.loadSummary(); }, 30000);
    },
    beforeUnmount: function () {
      var vm = this;
      if (vm._onKey) { document.removeEventListener('keydown', vm._onKey); vm._onKey = null; }
      if (vm._timer) { clearInterval(vm._timer); vm._timer = null; }
      if (vm._searchTimer) { clearTimeout(vm._searchTimer); vm._searchTimer = null; }
    },
    methods: {
      buildEmpty: function () {
        return {
          id: null, name: '', gender: '1', birthDate: '', idCard: '', phone: '',
          psnNo: '', insutype: '310', feeType: 'self',
          presentProv: '', presentCity: '', presentCounty: '', presentTown: '', presentDetail: '',
          contactName: '', contactPhone: ''
        };
      },
      /* ---------- 基础引用与字典 ---------- */
      loadRefs: function () {
        var vm = this;
        /* 挂号科室仅本机构已开诊的门诊科室(后端硬限定登录机构) */
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        var orgId = HIS.currentOrgId();
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false' + (orgId ? ('&orgId=' + orgId) : '')).then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
      },
      loadDicts: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'gend').then(function (l) { vm.gendOpts = l || []; }).catch(function () { });
        HIS.stdValues('cv_code', 'insutype').then(function (l) { vm.insutypeOpts = l || []; }).catch(function () { });
      },
      /* ---------- 顶部统计 ---------- */
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/his/registration/todaySummary').then(function (d) {
          vm.summary = d || vm.summary;
        }).catch(function () { /* 统计失败不打扰挂号操作 */ });
      },
      /* ---------- 患者快速检索(防抖300ms) ---------- */
      onSearchInput: function () {
        var vm = this;
        vm.searchDropVisible = false;
        if (vm._searchTimer) { clearTimeout(vm._searchTimer); }
        var kw = (vm.pKeyword || '').trim();
        if (kw.length < 2) { vm.pCandidates = []; return; }
        vm._searchTimer = setTimeout(function () { vm.searchCandidates(kw); }, 300);
      },
      searchCandidates: function (kw) {
        var vm = this; vm.pLoading = true;
        HIS.get('/api/his/patient/page?page=1&size=10&keyword=' + encodeURIComponent(kw || vm.pKeyword))
          .then(function (d) { vm.pCandidates = (d && d.records) || []; vm.searchDropVisible = true; })
          .catch(HIS.notifyError).finally(function () { vm.pLoading = false; });
      },
      /* 延迟隐藏候选下拉: 给 mousedown 选号留出触发窗口 */
      hideSearchDrop: function () {
        var vm = this;
        setTimeout(function () { vm.searchDropVisible = false; }, 200);
      },
      pickCandidate: function (row) {
        this.searchDropVisible = false;
        this.applyPatient(row, null);
        HIS.notifySuccess('已选择患者: ' + row.name);
      },
      /* 选中患者: 设费别/年龄减免自动判定/拉取上次就诊 */
      applyPatient: function (row, feeType) {
        var vm = this;
        vm.selectedPatient = row;
        if (feeType) { vm.feeType = feeType; }
        var age = row.age != null ? Number(row.age) : null;
        if (age == null && row.birthDate) {
          var b = new Date(String(row.birthDate).replace(' ', 'T'));
          if (!isNaN(b.getTime())) { age = new Date().getFullYear() - b.getFullYear(); }
        }
        if (age != null && age >= 70) {
          if (vm.discountType !== 'age70free') {
            vm.discountType = 'age70free';
            ElementPlus.ElMessage.success('患者年满70周岁, 已自动选中“70岁以上免挂号费”');
          }
        } else if (vm.discountType === 'age70free') {
          vm.discountType = 'none';
        }
        vm.pCandidates = []; vm.searchDropVisible = false;
        vm.loadLastVisit(row);
      },
      clearPatient: function () {
        var vm = this;
        vm.selectedPatient = null; vm.lastVisit = null;
        vm.discountType = 'none'; vm.discountReason = '';
        vm.pKeyword = ''; vm.pCandidates = []; vm.searchDropVisible = false;
      },
      /* 上次就诊信息: 按患者号检索挂号记录取最近一条(patientId 精确匹配) */
      loadLastVisit: function (row) {
        var vm = this; vm.lastVisit = null;
        if (!row.patientNo) { return; }
        HIS.get('/api/his/registration/page?page=1&size=5&keyword=' + encodeURIComponent(row.patientNo))
          .then(function (d) {
            var rows = (d && d.records) || [];
            for (var i = 0; i < rows.length; i++) {
              if (rows[i].patientId === row.id) { vm.lastVisit = rows[i]; return; }
            }
          }).catch(function () { });
      },
      /* ---------- 读卡(模拟) ---------- */
      /* 取对象多个候选键的首个非空值(兼容医保1101下划线/驼峰命名) */
      pick: function (o) {
        for (var i = 1; i < arguments.length; i++) {
          var k = arguments[i];
          if (o && o[k] != null && o[k] !== '') { return o[k]; }
        }
        return '';
      },
      /* 读医保卡: POST readCard(模拟1101, 返回 baseinfo+insuinfo) -> 按 psnNo/idCard 匹配档案 */
      readInsuCard: function () {
        var vm = this; vm.cardReading = true;
        HIS.post('/api/his/patient/readCard').then(function (d) {
          var base = (d && (d.baseinfo || d.baseInfo || d.base_info)) || d || {};
          var insus = (d && (d.insuinfo || d.insuInfo || d.insu_info)) || [];
          var info = {
            psnNo: vm.pick(base, 'psnNo', 'psn_no'),
            name: vm.pick(base, 'psnName', 'psn_name', 'name'),
            gender: vm.pick(base, 'gend', 'gender'),
            birthDate: String(vm.pick(base, 'brdy', 'birthDate', 'birthday') || '').substring(0, 10),
            idCard: vm.pick(base, 'certno', 'certNo', 'idCard'),
            phone: vm.pick(base, 'phone', 'mobile', 'tel'),
            address: vm.pick(base, 'psnAddr', 'psn_addr', 'address'),
            insutype: insus.length ? vm.pick(insus[0], 'insutype') : ''
          };
          var kw = info.psnNo || info.idCard;
          if (!kw) { throw new Error('读卡返回缺少人员标识(psnNo/idCard)'); }
          return HIS.get('/api/his/patient/page?page=1&size=20&keyword=' + encodeURIComponent(kw)).then(function (pg) {
            var rows = (pg && pg.records) || [];
            var hit = null; var i;
            if (info.psnNo) { for (i = 0; i < rows.length; i++) { if (rows[i].psnNo === info.psnNo) { hit = rows[i]; break; } } }
            if (!hit && info.idCard) { for (i = 0; i < rows.length; i++) { if (rows[i].idCard === info.idCard) { hit = rows[i]; break; } } }
            if (hit) {
              /* 匹配: 自动选中, 费别设为医保(险种随档案), 并同步1101参保记录(覆盖式) */
              vm.applyPatient(hit, 'insurance');
              HIS.notifySuccess('读卡成功: ' + hit.name + ' 已匹配档案 ' + hit.patientNo);
              HIS.post('/api/his/patient/' + hit.id + '/insu/read-sync').catch(function () { });
            } else {
              /* 未匹配: 打开快速建档并自动回填医保返回字段(读卡字段置灰) */
              ElementPlus.ElMessage.info('读卡成功但未匹配到档案, 请完善信息快速建档');
              vm.openBuildDialog(info, 'insurance', true);
            }
          });
        }).catch(function (e) {
          HIS.notifyError(/404/.test(String(e && e.message)) ? '读卡接口未就绪(POST /api/his/patient/readCard)' : e);
        }).finally(function () { vm.cardReading = false; });
      },
      /* 读身份证: 前端模拟读卡器返回(姓名/性别/出生/身份证/地址) -> 按 idCard 匹配档案 */
      readIdCard: function () {
        var vm = this; vm.cardReading = true;
        setTimeout(function () {
          vm.cardReading = false;
          var mock = vm.mockIdCard();
          HIS.get('/api/his/patient/by-idcard?idCard=' + encodeURIComponent(mock.idCard)).then(function (p) {
            if (p && p.id) {
              vm.applyPatient(p, 'self');
              HIS.notifySuccess('读身份证成功: 已匹配患者 ' + p.name);
            } else {
              ElementPlus.ElMessage.info('未找到该身份证对应档案, 请完善信息快速建档');
              vm.openBuildDialog(mock, 'self', true);
            }
          }).catch(HIS.notifyError);
        }, 400);
      },
      /* 模拟身份证读卡器: 随机生成有效身份证号(含校验位) + 人口学信息 */
      mockIdCard: function () {
        var areas = ['420921', '420102', '420111', '420502', '420602', '420702'];
        var surnames = '张王李刘陈杨赵周吴徐孙马朱胡郭何高林罗郑';
        var given = ['伟', '敏', '静', '丽', '强', '磊', '军', '洋', '勇', '艳', '杰', '娟', '涛', '明', '秀英', '桂兰', '国栋', '建军', '志强', '小红'];
        var area = areas[Math.floor(Math.random() * areas.length)];
        var y = 1950 + Math.floor(Math.random() * 60);
        var m = 1 + Math.floor(Math.random() * 12);
        var mm = ('0' + m).slice(-2);
        var dmax = new Date(y, m, 0).getDate();
        var d = 1 + Math.floor(Math.random() * dmax);
        var dd = ('0' + d).slice(-2);
        var seq = 100 + Math.floor(Math.random() * 900); /* 奇数=男 偶数=女 */
        var body = '' + area + y + mm + dd + seq;
        var W = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
        var C = '10X98765432';
        var sum = 0;
        for (var i = 0; i < 17; i++) { sum += Number(body.charAt(i)) * W[i]; }
        return {
          name: surnames.charAt(Math.floor(Math.random() * surnames.length)) + given[Math.floor(Math.random() * given.length)],
          gender: seq % 2 === 1 ? '1' : '2',
          birthDate: y + '-' + mm + '-' + dd,
          idCard: body + C.charAt(sum % 11),
          address: '湖北省孝感市孝昌县花园大道' + (1 + Math.floor(Math.random() * 200)) + '号'
        };
      },
      /* ---------- 快速建档弹窗 ---------- */
      openBuildDialog: function (info, feeType, fromCard) {
        var vm = this;
        info = info || {};
        vm.buildDlg = true;
        vm.buildForm = Object.assign(vm.buildEmpty(), {
          name: info.name || '', gender: info.gender || '1',
          birthDate: String(info.birthDate || '').substring(0, 10),
          idCard: info.idCard || '', phone: info.phone || '',
          psnNo: info.psnNo || '', insutype: info.insutype || '310',
          feeType: feeType || 'self', presentDetail: info.address || ''
        });
        vm.buildAreaPath = [];
        /* 读卡来源字段(姓名/性别/出生/证件号/医保号/险种)置灰不可编辑 */
        vm.buildLocked = { name: !!fromCard, gender: !!fromCard, birthDate: !!fromCard, idCard: !!fromCard, psnNo: !!fromCard, insutype: !!fromCard };
      },
      buildAreaLazyLoad: function (node, resolve) {
        var lvl = node.level; var val = node.value;
        HIS.areaChildren(lvl === 0 ? null : val, lvl === 0 ? 1 : null)
          .then(function (list) { resolve(list || []); }).catch(function () { resolve([]); });
      },
      onBuildAreaChange: function (vals) {
        var f = this.buildForm; var v = vals || [];
        f.presentProv = v[0] || ''; f.presentCity = v[1] || '';
        f.presentCounty = v[2] || ''; f.presentTown = v[3] || '';
      },
      /* 快速建档保存: POST /patient/save; 接口未就绪(404)时降级现有 POST /patient */
      submitBuild: function () {
        var vm = this;
        var f = vm.buildForm;
        if (!f.name) { ElementPlus.ElMessage.warning('患者姓名必填'); return; }
        if (!f.idCard) { ElementPlus.ElMessage.warning('身份证号必填'); return; }
        var payload = {};
        Object.keys(f).forEach(function (k) { payload[k] = f[k]; });
        if (payload.birthDate && payload.birthDate.length === 10) { payload.birthDate += ' 00:00:00'; }
        delete payload.feeType; /* 患者档案无费别列, 费别仅用于挂号表单 */
        var fee = f.feeType || 'self';
        vm.buildSaving = true;
        HIS.post('/api/his/patient/save', payload).then(function (p) { return p; }, function (e) {
          if (/404/.test(String(e && e.message))) { return HIS.post('/api/his/patient', payload); }
          throw e;
        }).then(function (p) {
          vm.buildDlg = false;
          HIS.notifySuccess('建档成功, 患者号: ' + ((p && p.patientNo) || ''));
          vm.applyPatient(p || {}, fee);
        }).catch(HIS.notifyError).finally(function () { vm.buildSaving = false; });
      },
      /* ---------- 号源 ---------- */
      loadSchedules: function () {
        var vm = this; vm.sLoading = true; vm.selectedSchedule = null;
        /* /schedule/list 为分页结构: 显式拉大页容量取全当日号源, 兼容数组/分页两种响应 */
        var q = '/api/his/schedule/list?1=1&page=1&size=200';
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterDate) { q += '&from=' + vm.filterDate + '&to=' + vm.filterDate; }
        HIS.get(q).then(function (d) {
          var all = (d && d.records) ? d.records : (d || []);
          /* /schedule/list 分页行是 JdbcTemplate 下划线列名: 归一化为驼峰供模板/挂号校验使用 */
          vm.schedules = all.map(function (s) {
            return {
              id: s.id,
              deptId: s.deptId != null ? s.deptId : s.dept_id,
              /* 后端已回带科室/医师名: 历史脏数据(非门诊科室排班/跨机构医师)不在本地 refs 时兜底显示, 避免列展示“-” */
              deptName: s.deptName || s.dept_name,
              staffName: s.staffName || s.staff_name,
              staffId: s.staffId != null ? s.staffId : s.staff_id,
              workDate: s.workDate || s.work_date,
              timeType: s.timeType || s.time_type,
              regLevelCode: s.regLevelCode || s.reg_level_code,
              regLevelName: s.regLevelName || s.reg_level_name,
              regFee: s.regFee != null ? s.regFee : s.reg_fee,
              totalNum: s.totalNum != null ? s.totalNum : s.total_num,
              leftNum: s.leftNum != null ? s.leftNum : s.left_num,
              status: s.status,
              room: s.room,
              /* 医保编码(2201挂号实际报送口径): 科室 his_dept.yb_dept_code / 医师 his_staff.atddr_no */
              ybDeptCode: s.ybDeptCode || s.yb_dept_code,
              atddrNo: s.atddrNo || s.atddr_no
            };
          }).filter(function (s) { return s.status === 1; });
        }).catch(HIS.notifyError).finally(function () { vm.sLoading = false; });
      },
      onDeptChange: function () { this.filterStaff = null; this.loadSchedules(); },
      quickDate: function (n) {
        var d = new Date(); d.setDate(d.getDate() + n);
        var m = ('0' + (d.getMonth() + 1)).slice(-2);
        var dd = ('0' + d.getDate()).slice(-2);
        this.filterDate = d.getFullYear() + '-' + m + '-' + dd;
        this.loadSchedules();
      },
      chooseSchedule: function (row) {
        if (!row || row.leftNum <= 0) { return; }
        this.selectedSchedule = row;
      },
      /* 双击行即选号 */
      onRowDblclick: function (row) {
        if (!row || row.leftNum <= 0) { ElementPlus.ElMessage.warning('该号源已无余号, 可点击“加号”追加'); return; }
        this.chooseSchedule(row);
        HIS.notifySuccess('已选号: ' + row.deptName + ' ' + row.staffName + ' ' + row.workDate + ' ' + timeLabel(row.timeType));
      },
      /* 加号: 总号源+1且余号+1(需本机构管理员权限) */
      doAddSlot: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('为 ' + row.deptName + ' ' + row.staffName + ' (' + row.workDate + ' ' + timeLabel(row.timeType) + ') 加一个号源?', '加号确认', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/schedule/addSlot?id=' + row.id); })
          .then(function () { HIS.notifySuccess('加号成功'); vm.loadSchedules(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      sourceRowCls: function (obj) {
        var row = obj && obj.row;
        if (!row) { return ''; }
        if (row.leftNum <= 0) { return 'reg-source-row--full'; }
        if (row.leftNum <= 5) { return 'reg-source-row--warning'; }
        return '';
      },
      timeTagType: function (v) { return v === 'am' ? 'primary' : (v === 'pm' ? 'warning' : 'info'); },
      /* 余号文本着色: 无号置灰/号紧(≤5)橙/充足绿 */
      progressColor: function (row) {
        if (row.leftNum <= 0) { return 'var(--yb-ink-4)'; }
        if (row.leftNum <= 5) { return 'var(--yb-warning)'; }
        return 'var(--yb-success)';
      },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return '-'; },
      staffName: function (id) { for (var i = 0; i < this.staffs.length; i++) { if (this.staffs[i].id === id) { return this.staffs[i].staffName; } } return '-'; },
      /* ---------- 今日挂号记录 ---------- */
      regSearch: function () { this.regPage = 1; this.loadRegs(); },
      loadRegs: function () {
        var vm = this; vm.regLoading = true;
        var q = '/api/his/registration/page?page=' + vm.regPage + '&size=' + vm.regSize + '&from=' + today() + '&to=' + today();
        if (vm.regStatus !== '' && vm.regStatus != null) { q += '&status=' + vm.regStatus; }
        if (vm.regKeyword) { q += '&keyword=' + encodeURIComponent(vm.regKeyword); }
        HIS.get(q).then(function (d) {
          vm.regTotal = (d && d.total) || 0;
          var rows = (d && d.records) || [];
          /* 仅本窗口: 后端暂无 operator 过滤参数, 客户端按当前登录挂号员过滤当前页 */
          if (vm.onlyMyWindow) {
            var u = HIS.getUser() || {};
            var op = u.realName || u.username;
            rows = rows.filter(function (r) { return r.operator === op; });
          }
          vm.regList = rows;
        }).catch(HIS.notifyError).finally(function () { vm.regLoading = false; });
      },
      onRegPage: function (p) { this.regPage = p; this.loadRegs(); },
      onRegSize: function (s) { this.regSize = s; this.onRegPage(1); },
      seqNo: function (i) { return (this.regPage - 1) * this.regSize + i + 1; },
      /* 退号: 二次确认+原因输入, 调医保2202并回滚号源; needRefund 时提示退费 */
      doCancel: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('退号将撤销医保2201挂号并回滚号源', '退号确认 - ' + row.patientName, {
          confirmButtonText: '确认退号', cancelButtonText: '取消',
          inputPlaceholder: '请输入退号原因(如: 患者临时有事)', type: 'warning'
        }).then(function (r) {
          return HIS.post('/api/his/registration/cancel?id=' + row.id + '&reason=' + encodeURIComponent(r.value || ''));
        }).then(function (d) {
          var msg = '退号成功(医保2202已撤销)';
          if (d && d.needRefund) { msg += ', 请到收费台办理退费(￥' + vm.fmtMoney(d.refundAmount) + ')'; }
          HIS.notifySuccess(msg);
          vm.loadRegs(); vm.loadSummary(); vm.loadSchedules();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* 重挂: 仅已退号记录, 复用原挂号信息重走2201链路 */
      doReRegister: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认按原挂号信息重新挂号?', '重新挂号 - ' + row.patientName, { type: 'warning' })
          .then(function () { return HIS.post('/api/his/registration/reRegister?id=' + row.id); })
          .then(function (reg) {
            HIS.notifySuccess('重新挂号成功: ' + reg.regNo);
            vm.showTicket(reg);
            vm.loadRegs(); vm.loadSummary(); vm.loadSchedules();
          }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ---------- 挂号凭条(模拟热敏打印) ---------- */
      showTicket: function (row) {
        this.ticketRow = row; this.ticketDlg = true;
      },
      /* 换号成功回调(ChangeSlotMixin): 弹新号凭条并刷新记录/统计/号源 */
      afterChangeSlot: function (nr) {
        if (nr && nr.id) { this.showTicket(nr); }
        this.loadRegs(); this.loadSummary(); this.loadSchedules();
      },
      doPrint: function () { window.print(); },
      /* ---------- 挂号提交 ---------- */
      submitRegister: function () {
        var vm = this;
        if (!vm.selectedPatient) { ElementPlus.ElMessage.warning('请先选择患者(F3读医保卡 / F4读身份证 / 输入关键字检索)'); return; }
        if (!vm.selectedSchedule) { ElementPlus.ElMessage.warning('请先选择号源'); return; }
        if (vm.selectedSchedule.leftNum <= 0) { ElementPlus.ElMessage.warning('该号源已无余号, 可点击“加号”追加'); return; }
        if (vm.discountType === 'other' && !(vm.discountReason || '').trim()) { ElementPlus.ElMessage.warning('选择“其他减免”时必须填写减免原因'); return; }
        vm.submitting = true;
        /* 重复挂号预检: duplicate 阻断; sameDept 警告可继续(预检失败不阻断, 后端挂号内有兜底校验) */
        HIS.get('/api/his/registration/checkDuplicate?patientId=' + vm.selectedPatient.id + '&scheduleId=' + vm.selectedSchedule.id)
          .then(function (chk) {
            if (chk && chk.duplicate) { throw new Error('该患者已挂此号源, 请勿重复挂号'); }
            if (chk && chk.sameDept) {
              return ElementPlus.ElMessageBox.confirm((chk.sameDeptInfo || '该患者当日已在该科室挂过号') + ', 是否继续挂号?', '同日同科室提示', { type: 'warning', confirmButtonText: '继续挂号', cancelButtonText: '取消' })
                .then(function () { return vm.doRegister(); });
            }
            return vm.doRegister();
          }, function () { return vm.doRegister(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } })
          .finally(function () { vm.submitting = false; });
      },
      doRegister: function () {
        var vm = this;
        var url = '/api/his/registration/register?patientId=' + vm.selectedPatient.id +
          '&scheduleId=' + vm.selectedSchedule.id +
          '&medType=' + vm.medType + '&feeType=' + vm.feeType +
          '&discountType=' + vm.discountType + '&payMethod=' + vm.payMethod;
        if (vm.discountReason) { url += '&discountReason=' + encodeURIComponent(vm.discountReason); }
        if (vm.regCaty) { url += '&regCaty=' + vm.regCaty; }
        if (vm.remark) { url += '&remark=' + encodeURIComponent(vm.remark); }
        return HIS.post(url).then(function (reg) {
          HIS.notifySuccess('挂号成功: ' + reg.regNo + ', 候诊号 ' + (reg.queueNo || '-'));
          if (reg.sameDeptWarning) { ElementPlus.ElMessage.warning(reg.sameDeptInfo || '该患者当日已在该科室挂过号'); }
          vm.ticketRow = reg; vm.ticketDlg = true;
          vm.remark = '';
          vm.clearPatient();
          vm.loadSchedules(); vm.loadRegs(); vm.loadSummary();
        });
      },
      /* ---------- 键盘快捷键 ---------- */
      focusSearch: function () {
        var inp = this.$refs && this.$refs.kwInput;
        if (inp && typeof inp.focus === 'function') { inp.focus(); }
      },
      handleKeydown: function (e) {
        var vm = this;
        var k = e.key;
        if (k === 'F2') { e.preventDefault(); vm.focusSearch(); }
        else if (k === 'F3') { e.preventDefault(); vm.readInsuCard(); }
        else if (k === 'F4') { e.preventDefault(); vm.readIdCard(); }
        else if (k === 'F5') { e.preventDefault(); vm.submitRegister(); }
        else if (k === 'F8') { e.preventDefault(); vm.clearPatient(); }
        else if (k === 'Escape') {
          if (vm.buildDlg) { vm.buildDlg = false; }
          else if (vm.ticketDlg) { vm.ticketDlg = false; }
          else { vm.searchDropVisible = false; }
        }
      },
      /* ---------- 展示格式化 ---------- */
      fmtMoney: function (v) { return (Number(v) || 0).toFixed(2); },
      fmtTime: function (v) { return v ? String(v).replace('T', ' ').substring(11, 16) : '-'; },
      medTypeLabel: function (v) {
        var m = { '11': '普通门诊', '12': '门诊挂号', '92': '门诊慢特病' };
        return m[v] || v || '-';
      },
      feeTypeLabel: function (v) { return v === 'insurance' ? '医保' : (v === 'self' ? '自费' : (v || '-')); },
      payMethodLabel: function (v) {
        var m = { insurance: '医保结算', self_pay: '自费结算', cash: '现金', card: '银行卡', wechat: '微信', alipay: '支付宝', free: '免收' };
        return m[v] || v || '-';
      },
      discountLabel: function (v) {
        var m = { none: '无减免', age70free: '70岁以上免挂号费', military: '军人减免', disabled: '残疾人减免', dibao: '低保减免', other: '其他减免' };
        return m[v] || v || '无减免';
      },
      timeLabel: timeLabel,
      statusLabel: statusLabel,
      statusTag: statusTag
    },
    template: [
      '<div class="reg-workstation">',
      /* 顶部: 今日统计(6卡片, GET todaySummary, 挂号/退号后刷新) */
      '  <div class="reg-summary-bar">',
      '    <div class="reg-summary-card">',
      '      <div class="icon icon--blue">挂</div>',
      '      <div>',
      '        <div class="num">{{ summary.registered }}</div>',
      '        <div class="label">今日挂号</div>',
      '      </div>',
      '    </div>',
      '    <div class="reg-summary-card">',
      '      <div class="icon icon--red">退</div>',
      '      <div>',
      '        <div class="num">{{ summary.cancelled }} <span class="sub">{{ cancelRate }}</span></div>',
      '        <div class="label">今日退号 / 退号率</div>',
      '      </div>',
      '    </div>',
      '    <div class="reg-summary-card">',
      '      <div class="icon icon--orange">候</div>',
      '      <div>',
      '        <div class="num">{{ summary.waiting }}</div>',
      '        <div class="label">候诊中</div>',
      '      </div>',
      '    </div>',
      '    <div class="reg-summary-card">',
      '      <div class="icon icon--green">诊</div>',
      '      <div>',
      '        <div class="num">{{ summary.visited }}</div>',
      '        <div class="label">已就诊</div>',
      '      </div>',
      '    </div>',
      '    <div class="reg-summary-card">',
      '      <div class="icon icon--purple">减</div>',
      '      <div>',
      '        <div class="num">{{ summary.discountCount }}</div>',
      '        <div class="label">减免 ¥{{ fmtMoney(summary.discountTotal) }}</div>',
      '      </div>',
      '    </div>',
      '    <div class="reg-summary-card">',
      '      <div class="icon icon--gold">收</div>',
      '      <div>',
      '        <div class="num">¥{{ fmtMoney(summary.actualTotal) }}</div>',
      '        <div class="label">实收金额</div>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* 主体: 左患者+挂号 / 右号源+记录 */
      '  <div class="reg-body">',
      '    <div class="reg-left">',
      '      <div class="reg-section-title">患者</div>',
      '      <div class="reg-search-box">',
      '        <el-input ref="kwInput" v-model="pKeyword" placeholder="姓名/身份证/患者号/拼音简码" clearable @input="onSearchInput" @blur="hideSearchDrop" @keyup.enter="searchCandidates()">',
      '          <template #append><el-button :loading="pLoading" @click="searchCandidates()">检索</el-button></template>',
      '        </el-input>',
      '        <div v-if="searchDropVisible && pCandidates.length" class="reg-search-drop">',
      '          <div v-for="c in pCandidates" :key="c.id" class="reg-search-item" @mousedown="pickCandidate(c)">',
      '            <span class="nm">{{ c.name }}</span>',
      '            <span class="meta">{{ c.genderName || c.gender }} / {{ c.age||"-" }}岁 / {{ c.patientNo }}</span>',
      '            <div class="meta" v-if="c.psnNo">医保号 {{ c.psnNo }} <span v-if="c.insutypeName">({{ c.insutypeName }})</span></div>',
      '          </div>',
      '        </div>',
      '        <div v-else-if="searchDropVisible" class="reg-search-drop">',
      '          <div class="reg-search-empty">无匹配患者, 可点“快速建档”新建</div>',
      '        </div>',
      '      </div>',
      '      <div>',
      '        <el-button size="small" type="primary" :loading="cardReading" @click="readInsuCard()">读医保卡 F3</el-button>',
      '        <el-button size="small" :loading="cardReading" @click="readIdCard()">读身份证 F4</el-button>',
      '        <el-button size="small" @click="openBuildDialog()">快速建档</el-button>',
      '      </div>',
      '      <div v-if="selectedPatient" :class="\'reg-patient-card \' + patientCardCls">',
      '        <el-button class="clear-btn" link type="info" size="small" @click="clearPatient()">清除 F8</el-button>',
      '        <div class="patient-name">{{ selectedPatient.name }}',
      '          <span v-if="patientAge != null && patientAge >= 70" class="badge-age70">70+老人</span>',
      '        </div>',
      '        <div class="patient-info">',
      '          {{ selectedPatient.genderName || selectedPatient.gender }} / {{ patientAge==null ? "-" : patientAge }}岁 / 患者号 {{ selectedPatient.patientNo || "-" }}',
      '          <br>身份证 {{ maskIdCardC }}',
      '          <br>医保号 {{ maskPsnNoC }} <el-tag v-if="selectedPatient.insutypeName" size="small">{{ selectedPatient.insutypeName }}</el-tag>',
      '        </div>',
      '        <div v-if="lastVisit" class="patient-last-visit">上次就诊: {{ lastVisit.workDate }} {{ lastVisit.deptName }} {{ lastVisit.drName }}</div>',
      '      </div>',
      '      <div v-else class="reg-patient-card" style="border-left-color:var(--yb-border-strong);">',
      '        <div style="color:var(--yb-ink-2);font-size:13px;text-align:center;padding:16px 0;">尚未选择患者<br>F3读医保卡 / F4读身份证 / 输入检索后点选</div>',
      '      </div>',
      '      <div class="reg-section-title">挂号信息</div>',
      '      <el-form class="reg-form" label-position="top" size="small">',
      '        <el-row :gutter="10">',
      '          <el-col :span="12"><el-form-item label="医疗类别"><el-select v-model="medType" style="width:100%"><el-option label="11-普通门诊" value="11"></el-option><el-option label="12-门诊挂号" value="12"></el-option><el-option label="92-门诊慢特病" value="92"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="费别"><el-select v-model="feeType" style="width:100%"><el-option label="医保" value="insurance"></el-option><el-option label="自费" value="self"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="挂号类别"><el-select v-model="regCaty" style="width:100%"><el-option label="普通" value="normal"></el-option><el-option label="急诊" value="emergency"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="挂号减免"><el-select v-model="discountType" style="width:100%"><el-option label="无减免" value="none"></el-option><el-option label="70岁以上免挂号费" value="age70free"></el-option><el-option label="军人减免" value="military"></el-option><el-option label="残疾人减免" value="disabled"></el-option><el-option label="低保减免" value="dibao"></el-option><el-option label="其他减免" value="other"></el-option></el-select></el-form-item></el-col>',
      '          <el-col v-if="discountType===\'other\'" :span="24"><el-form-item label="减免原因(其他减免必填)"><el-input v-model="discountReason" placeholder="请输入其他减免的原因"></el-input></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="支付方式"><el-select v-model="payMethod" style="width:100%"><el-option v-for="o in payMethodOptions" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="备注"><el-input v-model="remark" placeholder="选填"></el-input></el-form-item></el-col>',
      '        </el-row>',
      '      </el-form>',
      '      <div v-if="selectedSchedule" class="reg-fee-summary">',
      '        <div class="fee-src">{{ selectedSchedule.deptName }} · {{ selectedSchedule.staffName }} · {{ selectedSchedule.workDate }} {{ timeLabel(selectedSchedule.timeType) }} · {{ selectedSchedule.regLevelName || "普通" }}</div>',
      '        <div class="fee-row"><span>挂号费</span><span>¥ {{ fmtMoney(selectedSchedule.regFee) }}</span></div>',
      '        <div class="fee-row fee-row--discount"><span>减免({{ discountLabel(discountType) }})</span><span>-¥ {{ fmtMoney(discountAmountC) }}</span></div>',
      '        <div class="fee-row fee-row--total"><span>实收</span><span>¥ {{ fmtMoney(actualFeeC) }}</span></div>',
      '      </div>',
      '      <div v-else class="reg-fee-summary"><div class="fee-src" style="text-align:center;">请在右侧选择号源(双击行快速选号)</div></div>',
      '      <el-button type="primary" size="large" style="width:100%;" :loading="submitting" :disabled="!selectedPatient || !selectedSchedule" @click="submitRegister">确认挂号 F5</el-button>',
      '    </div>',
      '    <div class="reg-right">',
      '      <div class="reg-right-top">',
      '        <div class="reg-section-title">号源选择 <span style="font-weight:normal;color:var(--yb-ink-2);font-size:12px;">(科室联动医师, 双击行选号, 余号=0可加号)</span></div>',
      '        <div class="toolbar" style="margin-bottom:8px;">',
      '          <el-select v-model="filterDept" placeholder="全部科室(可输拼音简码)" clearable filterable style="width:170px" :filter-method="kwFilter(\'rdDept\')" @change="onDeptChange"><el-option v-for="d in kwOptions(\'rdDept\', deptFilterOptions, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '          <el-select v-model="filterStaff" placeholder="全部医师" clearable filterable style="width:130px"><el-option v-for="s in staffOptions" :key="s.id" :label="s.staffName" :value="s.id"></el-option></el-select>',
      '          <el-date-picker v-model="filterDate" type="date" value-format="YYYY-MM-DD" placeholder="出诊日期" style="width:145px" @change="loadSchedules"></el-date-picker>',
      '          <el-button-group>',
      '            <el-button size="small" @click="quickDate(0)">今天</el-button>',
      '            <el-button size="small" @click="quickDate(1)">明天</el-button>',
      '            <el-button size="small" @click="quickDate(2)">后天</el-button>',
      '          </el-button-group>',
      '          <el-checkbox-group v-model="timeChecked" size="small">',
      '            <el-checkbox-button v-for="t in timeTypes" :key="t.v" :label="t.v">{{ t.l }}</el-checkbox-button>',
      '          </el-checkbox-group>',
      '          <el-button size="small" :loading="sLoading" @click="loadSchedules()">刷新</el-button>',
      '        </div>',
      '        <el-table :data="filteredSchedules" v-loading="sLoading" size="small" height="240" border highlight-current-row :row-class-name="sourceRowCls" @row-dblclick="onRowDblclick" @current-change="chooseSchedule">',
      '          <el-table-column type="index" label="#" width="40"></el-table-column>',
      '          <el-table-column label="时段" width="64"><template #default="s"><el-tag size="small" :type="timeTagType(s.row.timeType)" :class="{\'reg-tag--night\': s.row.timeType===\'night\'}">{{ timeLabel(s.row.timeType) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="科室" width="100" show-overflow-tooltip><template #default="s">{{ s.row.deptName || deptName(s.row.deptId) }}</template></el-table-column>',
      '          <el-table-column label="科室医保编码" width="102" show-overflow-tooltip><template #default="s">{{ s.row.ybDeptCode || "—" }}</template></el-table-column>',
      '          <el-table-column label="医师" width="90" show-overflow-tooltip><template #default="s">{{ s.row.staffName || staffName(s.row.staffId) }}</template></el-table-column>',
      '          <el-table-column label="医师医保编码" width="102" show-overflow-tooltip><template #default="s">{{ s.row.atddrNo || "—" }}</template></el-table-column>',
      '          <el-table-column label="号别" width="84" show-overflow-tooltip><template #default="s">{{ s.row.regLevelName || "-" }}</template></el-table-column>',
      '          <el-table-column label="挂号费" width="76" align="right"><template #default="s">¥{{ fmtMoney(s.row.regFee) }}</template></el-table-column>',
      '          <el-table-column label="余号/总号" width="92" align="center"><template #default="s"><span :style="\'font-weight:600;color:\' + progressColor(s.row)">{{ s.row.leftNum }}/{{ s.row.totalNum }}</span></template></el-table-column>',
      '          <el-table-column label="操作" width="64"><template #default="s">',
      '            <el-button v-if="s.row.leftNum<=0" link type="warning" size="small" @click="doAddSlot(s.row)">加号</el-button>',
      '            <span v-else :style="selectedSchedule && selectedSchedule.id===s.row.id ? \'color:var(--yb-success);font-weight:700;\' : \'\'">{{ selectedSchedule && selectedSchedule.id===s.row.id ? "已选" : "" }}</span>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </div>',
      '      <div class="reg-right-bottom">',
      '        <div class="reg-section-title">今日挂号记录 <span style="font-weight:normal;color:var(--yb-ink-2);font-size:12px;">(30秒自动刷新, 共 {{ regTotal }} 条)</span></div>',
      '        <div class="toolbar" style="margin-bottom:8px;">',
      '          <el-input v-model="regKeyword" placeholder="患者/挂号单号/门诊号(支持拼音简码)" clearable style="width:210px" size="small" @keyup.enter="regSearch"></el-input>',
      '          <el-select v-model="regStatus" placeholder="全部状态" clearable size="small" style="width:104px" @change="regSearch"><el-option v-for="s in regStatusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option></el-select>',
      '          <el-checkbox v-model="onlyMyWindow" size="small" @change="regSearch">仅本窗口</el-checkbox>',
      '          <el-button size="small" :loading="regLoading" @click="regSearch">查询</el-button>',
      '        </div>',
      '        <div style="flex:1;overflow:auto;">',
      '          <el-table :data="regList" v-loading="regLoading" size="small" border>',
      '            <el-table-column type="index" label="#" width="40" :index="seqNo"></el-table-column>',
      '            <el-table-column prop="regNo" label="挂号单号" width="148" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="patientName" label="患者" width="76"></el-table-column>',
      '            <el-table-column prop="deptName" label="科室" width="94" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="drName" label="医师" width="76"></el-table-column>',
      '            <el-table-column label="时段" width="58"><template #default="s">{{ timeLabel(s.row.timeType) }}</template></el-table-column>',
      '            <el-table-column prop="queueNo" label="候诊号" width="84"></el-table-column>',
      '            <el-table-column label="实收" width="78" align="right"><template #default="s"><span :style="Number(s.row.actualFee) < Number(s.row.regFee) ? \'color:var(--yb-danger);\' : \'\'">¥{{ fmtMoney(s.row.actualFee) }}</span></template></el-table-column>',
      '            <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '            <el-table-column label="操作" width="150" fixed="right"><template #default="s">',
      '              <el-button v-if="s.row.status===1" link type="danger" size="small" @click="doCancel(s.row)">退号</el-button>',
      '              <el-button v-if="s.row.status===1" link type="warning" size="small" @click="openChangeSlot(s.row)">换号</el-button>',
      '              <el-button v-if="s.row.status===2" link type="warning" size="small" @click="doReRegister(s.row)">重挂</el-button>',
      '              <el-button link type="info" size="small" @click="showTicket(s.row)">凭条</el-button>',
      '            </template></el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <el-pagination style="margin-top:8px;justify-content:flex-end;" background size="small" layout="total, prev, pager, next" :total="regTotal" :page-size="regSize" :current-page="regPage" @current-change="onRegPage"></el-pagination>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* 底部: 快捷键提示条 */
      '  <div class="reg-shortcut-bar">',
      '    <span><kbd>F2</kbd> 搜索患者</span>',
      '    <span><kbd>F3</kbd> 读医保卡</span>',
      '    <span><kbd>F4</kbd> 读身份证</span>',
      '    <span><kbd>F5</kbd> 确认挂号</span>',
      '    <span><kbd>F8</kbd> 清除患者</span>',
      '    <span><kbd>Esc</kbd> 关闭弹窗</span>',
      '    <span style="margin-left:auto;">挂号调医保2201 · 退号调医保2202 · 双击号源行快速选号</span>',
      '  </div>',
      /* 快速建档弹窗(600px, 读卡来源字段置灰) */
      '  <el-dialog v-model="buildDlg" title="快速建档" width="600px">',
      '    <el-form :model="buildForm" label-width="90px" size="small">',
      '      <el-row :gutter="10">',
      '        <el-col :span="12"><el-form-item label="姓名" required><el-input v-model="buildForm.name" :disabled="buildLocked.name"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="性别"><el-select v-model="buildForm.gender" :disabled="buildLocked.gender" style="width:100%"><el-option v-for="o in gendOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="出生日期"><el-date-picker v-model="buildForm.birthDate" type="date" value-format="YYYY-MM-DD" :disabled="buildLocked.birthDate" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证号" required><el-input v-model="buildForm.idCard" :disabled="buildLocked.idCard"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="buildForm.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保人员编号"><el-input v-model="buildForm.psnNo" :disabled="buildLocked.psnNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="险种"><el-select v-model="buildForm.insutype" :disabled="buildLocked.insutype" style="width:100%"><el-option v-for="o in insutypeOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="费别"><el-select v-model="buildForm.feeType" style="width:100%"><el-option label="医保" value="insurance"></el-option><el-option label="自费" value="self"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="现住址"><el-cascader v-model="buildAreaPath" :props="buildCascaderProps" @change="onBuildAreaChange" clearable style="width:100%" placeholder="省/市/区县/乡镇(逐级选择)"></el-cascader></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="详细地址"><el-input v-model="buildForm.presentDetail" placeholder="村/街/路/门牌号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人姓名"><el-input v-model="buildForm.contactName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系人电话"><el-input v-model="buildForm.contactPhone"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="buildDlg=false">取消</el-button>',
      '      <el-button type="primary" :loading="buildSaving" @click="submitBuild()">保存并选中</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* 挂号凭条(480px, 模拟热敏打印) */
      '  <el-dialog v-model="ticketDlg" title="挂号凭条" width="480px">',
      '    <div v-if="ticketRow" class="reg-ticket-preview">',
      '      <div class="ticket-title">{{ ticketRow.deptName }} 门诊挂号凭条</div>',
      '      <div class="ticket-divider"></div>',
      '      <div>患者: {{ ticketRow.patientName }}  单号: {{ ticketRow.regNo }}</div>',
      '      <div>医师: {{ ticketRow.drName }}  日期: {{ ticketRow.workDate }} {{ timeLabel(ticketRow.timeType) }}</div>',
      '      <div>费用: 挂号¥{{ fmtMoney(ticketRow.regFee) }} 减免¥{{ fmtMoney(ticketRow.discountAmount) }} 实收¥{{ fmtMoney(ticketRow.actualFee) }}</div>',
      '      <div class="ticket-queue">{{ ticketRow.queueNo || "-" }}</div>',
      '      <div class="ticket-divider"></div>',
      '      <div class="ticket-footer">请按候诊号顺序就诊 · 医保就诊ID {{ ticketRow.mdtrtId || "-" }}</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="ticketDlg=false">关闭</el-button>',
      '      <el-button type="primary" @click="doPrint">打印</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* 换号对话框(ChangeSlotMixin 共用模板) */
      CS_DIALOG,
      '</div>'
    ].join('\n')
  };

  /* ================= 退号(独立专业退号页) =================
   * 面向跨日/历史退号场景: 查询条件比挂号工作站“今日挂号记录”更丰富(日期范围/科室/医师/状态/关键字)。
   * 数据源: 默认 GET /registration/page(实体驼峰列); 选中科室或医师时 page 不支持该两参数,
   * 改走 GET /registration/statDetail(JdbcTemplate 下划线列), 两种响应统一 normReg 归一化。
   * 退号: 确认对话框展示患者/挂号/费用摘要 -> POST /registration/cancel(医保2202 + 号源回滚 + 退费提示)。 */
  function daysAgo(n) {
    var d = new Date(); d.setDate(d.getDate() - n);
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var dd = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + dd;
  }
  /* 挂号记录行归一化: 兼容 /page 实体驼峰列与 /statDetail 下划线列, 统一输出驼峰 */
  function normReg(r) {
    if (r == null) { return r; }
    function pk(camel, snake) { return r[camel] != null ? r[camel] : r[snake]; }
    return {
      id: r.id,
      regNo: pk('regNo', 'reg_no'),
      patientId: pk('patientId', 'patient_id'),
      patientNo: pk('patientNo', 'patient_no'),
      patientName: pk('patientName', 'patient_name'),
      psnNo: r.psnNo,
      deptId: pk('deptId', 'dept_id'),
      deptName: pk('deptName', 'dept_name'),
      staffId: pk('staffId', 'staff_id'),
      scheduleId: pk('scheduleId', 'schedule_id'),
      drName: pk('drName', 'dr_name'),
      workDate: pk('workDate', 'work_date'),
      timeType: pk('timeType', 'time_type'),
      regLevelCode: pk('regLevelCode', 'reg_level_code'),
      regLevelName: pk('regLevelName', 'reg_level_name'),
      regFee: pk('regFee', 'reg_fee'),
      medType: pk('medType', 'med_type'),
      iptOtpNo: pk('iptOtpNo', 'ipt_otp_no'),
      mdtrtId: pk('mdtrtId', 'mdtrt_id'),
      regTime: pk('regTime', 'reg_time'),
      status: r.status,
      cancelTime: pk('cancelTime', 'cancel_time'),
      cancelReason: pk('cancelReason', 'cancel_reason'),
      operator: r.operator,
      feeType: pk('feeType', 'fee_type'),
      discountType: pk('discountType', 'discount_type'),
      discountReason: pk('discountReason', 'discount_reason'),
      discountAmount: pk('discountAmount', 'discount_amount'),
      actualFee: pk('actualFee', 'actual_fee'),
      payMethod: pk('payMethod', 'pay_method'),
      payDetail: pk('payDetail', 'pay_detail'),
      queueNo: pk('queueNo', 'queue_no')
    };
  }
  /* CSV 单元格: 含引号/逗号/换行时加双引号包裹 */
  function regCsvCell(v) {
    var s = (v === null || v === undefined) ? '' : String(v);
    if (/[",\r\n]/.test(s)) { s = '"' + s.replace(/"/g, '""') + '"'; }
    return s;
  }
  /* 打印/导出取值: 空值统一占位 '-', 布尔转是/否 */
  function printVal(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    if (v === true) { return '是'; }
    if (v === false) { return '否'; }
    return String(v);
  }
  /* html 转义: 防止患者姓名等文本破坏打印文档结构 */
  function printEsc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  /* 打印页列定义(与列表展示同口径, label 用于表头, val 从归一化行取值并格式化) */
  var UNREG_PRINT_COLS = [
    { l: '挂号时间', w: 78, val: function (r) { return printVal(r.regTime ? String(r.regTime).replace('T', ' ').substring(0, 16) : ''); } },
    { l: '挂号单号', w: 120, val: function (r) { return printVal(r.regNo); } },
    { l: '门诊号', w: 88, val: function (r) { return printVal(r.iptOtpNo); } },
    { l: '患者姓名', w: 60, val: function (r) { return printVal(r.patientName); } },
    { l: '患者号', w: 96, val: function (r) { return printVal(r.patientNo); } },
    { l: '科室', w: 80, val: function (r) { return printVal(r.deptName); } },
    { l: '医师', w: 56, val: function (r) { return printVal(r.drName); } },
    { l: '出诊日期', w: 72, val: function (r) { return printVal(r.workDate); } },
    { l: '时段', w: 36, val: function (r) { return printVal(r.timeType ? timeLabel(r.timeType) : ''); } },
    { l: '号别', w: 64, val: function (r) { return printVal(r.regLevelName); } },
    { l: '挂号费', w: 48, val: function (r) { return (Number(r.regFee) || 0).toFixed(2); } },
    { l: '减免金额', w: 52, val: function (r) { return (Number(r.discountAmount) || 0).toFixed(2); } },
    { l: '实收金额', w: 52, val: function (r) { return (Number(r.actualFee) || 0).toFixed(2); } },
    { l: '费别', w: 40, val: function (r) { return printVal(r.feeType === 'insurance' ? '医保' : (r.feeType === 'self' ? '自费' : r.feeType)); } },
    { l: '支付方式', w: 56, val: function (r) { var m = { insurance: '医保结算', self_pay: '自费结算', cash: '现金', card: '银行卡', wechat: '微信', alipay: '支付宝', free: '免收' }; return printVal(m[r.payMethod] || r.payMethod); } },
    { l: '候诊序号', w: 52, val: function (r) { return printVal(r.queueNo); } },
    { l: '医保就诊ID', w: 110, val: function (r) { return printVal(r.mdtrtId); } },
    { l: '状态', w: 48, val: function (r) { return printVal(statusLabel(r.status)); } },
    { l: '退号时间', w: 78, val: function (r) { return printVal(r.cancelTime ? String(r.cancelTime).replace('T', ' ').substring(0, 16) : ''); } },
    { l: '退号原因', w: 96, val: function (r) { return printVal(r.cancelReason); } },
    { l: '操作员', w: 56, val: function (r) { return printVal(r.operator); } }
  ];

  HIS.views.UnregisterDesk = {
    mixins: [ChangeSlotMixin],
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        exporting: false, printing: false,
        /* 默认最近7天 */
        dateRange: [daysAgo(6), today()],
        depts: [], staffs: [], filterDept: null, filterStaff: null,
        statusFilter: '', keyword: '',
        statusOpts: [{ v: '', l: '全部' }].concat(REG_STATUS),
        /* 退号确认对话框 */
        cancelDlg: false, cancelRow: null, cancelReason: '', cancelling: false,
        patientInfo: null, patientLoading: false,
        /* 挂号凭条(复用挂号工作站热敏凭条样式) */
        ticketDlg: false, ticketRow: null
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      /* 科室下拉仅科室级节点(deptLevel=2), 与挂号台口径一致 */
      deptOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      /* 医师下拉联动科室 */
      staffOptions: function () {
        var dep = this.filterDept;
        if (!dep) { return this.staffs || []; }
        return (this.staffs || []).filter(function (s) { return s.deptId === dep; });
      },
      /* 已收费提示: pay_method 非空且非 free 时退号需到收费窗口退费 */
      needRefundHint: function () {
        var pm = this.cancelRow && this.cancelRow.payMethod;
        return !!pm && String(pm).trim() !== 'free';
      }
    },
    created: function () { this.loadRefs(); this.load(); },
    methods: {
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        var orgId = HIS.currentOrgId();
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false' + (orgId ? ('&orgId=' + orgId) : ''))
          .then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
      },
      /* 科室切换: 清空不属于该科室的已选医师, 避免“科室A+医师B”脏组合 */
      onDeptChange: function () {
        var vm = this;
        if (!vm.filterStaff || !vm.filterDept) { return; }
        for (var i = 0; i < vm.staffs.length; i++) {
          if (vm.staffs[i].id === vm.filterStaff) {
            if (vm.staffs[i].deptId !== vm.filterDept) { vm.filterStaff = null; }
            break;
          }
        }
      },
      /* 拼接当前查询条件(不含分页): 未选科室/医师走 /page; 选中后改走 /statDetail(支持 deptId/staffId) */
      buildQuery: function (page, size) {
        var vm = this;
        var q;
        if (vm.filterDept || vm.filterStaff) {
          q = '/api/his/registration/statDetail?page=' + page + '&size=' + size
            + '&from=' + vm.startDate + '&to=' + vm.endDate;
          if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
          if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
        } else {
          q = '/api/his/registration/page?page=' + page + '&size=' + size
            + '&from=' + vm.startDate + '&to=' + vm.endDate;
        }
        if (vm.statusFilter !== '' && vm.statusFilter != null) { q += '&status=' + vm.statusFilter; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        return q;
      },
      /* 分页拉全量(导出/打印全部共用, 与 load 同数据源同口径, 最多50页防失控) */
      fetchAll: function () {
        var vm = this;
        var all = [], page = 1;
        function fetchPage() {
          return HIS.get(vm.buildQuery(page, 200)).then(function (d) {
            var recs = ((d && d.records) || []).map(normReg);
            all = all.concat(recs);
            var total = (d && d.total) || 0;
            if (recs.length >= 200 && all.length < total && page < 50) { page++; return fetchPage(); }
            return all;
          });
        }
        return fetchPage();
      },
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get(vm.buildQuery(vm.page, vm.size)).then(function (d) {
          vm.list = ((d && d.records) || []).map(normReg);
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () {
        if (!this.startDate || !this.endDate) { ElementPlus.ElMessage.warning('请选择日期范围'); return; }
        this.page = 1; this.load();
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      reset: function () {
        var vm = this;
        vm.dateRange = [daysAgo(6), today()];
        vm.filterDept = null; vm.filterStaff = null;
        vm.statusFilter = ''; vm.keyword = '';
        vm.search();
      },
      /* 退号: 打开确认对话框并异步拉取患者摘要(性别/年龄不在挂号记录冗余列中) */
      doCancel: function (row) {
        var vm = this;
        vm.cancelRow = normReg(row); vm.cancelReason = ''; vm.patientInfo = null;
        vm.cancelDlg = true;
        if (row.patientId) {
          vm.patientLoading = true;
          HIS.get('/api/his/patient/' + row.patientId)
            .then(function (p) { vm.patientInfo = p || null; })
            .catch(function () { vm.patientInfo = null; })
            .finally(function () { vm.patientLoading = false; });
        }
      },
      patientGender: function () {
        var p = this.patientInfo || {};
        return p.genderName || (p.gender === '1' ? '男' : (p.gender === '2' ? '女' : '-'));
      },
      patientAge: function () {
        var p = this.patientInfo || {};
        if (p.age != null) { return p.age; }
        if (p.birthDate) {
          var b = new Date(String(p.birthDate).replace(' ', 'T'));
          if (!isNaN(b.getTime())) { return new Date().getFullYear() - b.getFullYear(); }
        }
        return null;
      },
      confirmCancel: function () {
        var vm = this;
        if (vm.cancelling) { return; }
        vm.cancelling = true;
        HIS.post('/api/his/registration/cancel?id=' + vm.cancelRow.id + '&reason=' + encodeURIComponent(vm.cancelReason || ''))
          .then(function (d) {
            var msg = '退号成功';
            if (d && d.needRefund) { msg += ', 请到收费窗口办理退费(¥' + vm.fmtMoney(d.refundAmount) + ')'; }
            ElementPlus.ElMessage.success(msg);
            vm.cancelDlg = false;
            vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.cancelling = false; });
      },
      /* ---------- 挂号凭条(复用挂号工作站样式) ---------- */
      showTicket: function (row) { this.ticketRow = normReg(row); this.ticketDlg = true; },
      doPrint: function () { window.print(); },
      /* 换号成功回调(ChangeSlotMixin): 刷新列表(新单可在此页再次检索) */
      afterChangeSlot: function () { this.load(); },
      /* ---------- 导出 Excel(CSV BOM): 分页拉全量, 与当前查询条件同口径 ---------- */
      doExport: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { ElementPlus.ElMessage.warning('请先选择日期范围'); return; }
        vm.exporting = true;
        vm.fetchAll().then(function (rows) {
          if (!rows || !rows.length) { ElementPlus.ElMessage.warning('当前条件下无数据可导出'); return; }
          var head = UNREG_PRINT_COLS.map(function (c) { return c.l; });
          var lines = [head.join(',')];
          rows.forEach(function (r, i) {
            var cells = [String(i + 1)].concat(UNREG_PRINT_COLS.map(function (c) { return c.val(r); }));
            lines.push(cells.map(regCsvCell).join(','));
          });
          var blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
          var a = document.createElement('a');
          var u = URL.createObjectURL(blob);
          a.href = u;
          a.download = '退号查询_' + vm.startDate + '_' + vm.endDate + '.csv';
          document.body.appendChild(a); a.click();
          setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 1000);
          HIS.notifySuccess('已导出 ' + rows.length + ' 条记录');
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      /* ---------- 打印: 新窗口 A4 横向表格(本页=当前分页数据; 全部=分页拉全量) ---------- */
      printSummary: function () {
        var vm = this;
        var deptName = '-';
        (vm.deptOptions || []).forEach(function (d) { if (d.id === vm.filterDept) { deptName = d.deptName; } });
        var staffName = '-';
        (vm.staffs || []).forEach(function (s) { if (s.id === vm.filterStaff) { staffName = s.staffName; } });
        var statusName = '全部';
        (vm.statusOpts || []).forEach(function (s) { if (s.v === vm.statusFilter) { statusName = s.l; } });
        return '日期: ' + (vm.startDate || '-') + ' ~ ' + (vm.endDate || '-')
          + ' · 科室: ' + printVal(vm.filterDept ? deptName : '')
          + ' · 医师: ' + printVal(vm.filterStaff ? staffName : '')
          + ' · 状态: ' + statusName + ' · 关键字: ' + printVal(vm.keyword);
      },
      doPrintPage: function () { this.printRows(this.list.map(normReg), '本页 ' + this.list.length + ' 条'); },
      doPrintAll: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { ElementPlus.ElMessage.warning('请先选择日期范围'); return; }
        if (vm.printing) { return; }
        vm.printing = true;
        vm.fetchAll().then(function (rows) {
          if (!rows || !rows.length) { ElementPlus.ElMessage.warning('当前条件下无数据可打印'); return; }
          vm.printRows(rows, '全量 ' + rows.length + ' 条');
        }).catch(HIS.notifyError).finally(function () { vm.printing = false; });
      },
      /* 渲染打印文档并打开新窗口(弹窗被拦截时提示; 窗口关闭后恢复按钮状态) */
      printRows: function (rows, scopeLabel) {
        var vm = this;
        var w = window.open('', '_blank');
        if (!w) { ElementPlus.ElMessage.error('打印窗口被浏览器拦截, 请允许弹窗后重试'); return; }
        var head = '<tr><th>序号</th>';
        UNREG_PRINT_COLS.forEach(function (c) { head += '<th>' + printEsc(c.l) + '</th>'; });
        head += '</tr>';
        var body = '';
        rows.forEach(function (r, i) {
          body += '<tr><td>' + (i + 1) + '</td>';
          UNREG_PRINT_COLS.forEach(function (c) { body += '<td>' + printEsc(c.val(r)) + '</td>'; });
          body += '</tr>';
        });
        var now = new Date();
        function pad(n) { return ('0' + n).slice(-2); }
        var printAt = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate())
          + ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes());
        var html = [
          '<!DOCTYPE html><html><head><meta charset="utf-8"><title>退号查询打印</title><style>',
          '@page { size: A4 landscape; margin: 10mm; }',
          'body { font-family: "Microsoft YaHei", sans-serif; color: var(--yb-ink-1); margin: 0; }',
          'h2 { text-align: center; font-size: 17px; margin: 8px 0 4px; }',
          '.meta { text-align: center; font-size: 11px; color: var(--yb-ink-2); margin-bottom: 8px; }',
          'table { width: 100%; border-collapse: collapse; font-size: 10px; }',
          'th, td { border: 1px solid var(--yb-ink-2); padding: 3px 4px; word-break: break-all; }',
          'th { background: var(--yb-surface-2); font-weight: 600; }',
          'tr { page-break-inside: avoid; }',
          '.foot { margin-top: 8px; font-size: 10px; color: var(--yb-ink-2); display: flex; justify-content: space-between; }',
          '</style></head><body>',
          '<h2>退号查询表</h2>',
          '<div class="meta">' + printEsc(vm.printSummary()) + '</div>',
          '<table><thead>' + head + '</thead><tbody>' + body + '</tbody></table>',
          '<div class="foot"><span>打印范围: ' + printEsc(scopeLabel) + '</span><span>打印时间: ' + printAt + '</span></div>',
          '</body></html>'
        ].join('');
        w.document.open();
        w.document.write(html);
        w.document.close();
        w.focus();
        setTimeout(function () { try { w.print(); } catch (e) { /* 用户已关闭窗口 */ } }, 300);
      },
      /* ---------- 展示格式化 ---------- */
      fmtMoney: function (v) { return (Number(v) || 0).toFixed(2); },
      fmtDateTime: function (v) { return v ? String(v).replace('T', ' ').substring(0, 16) : '-'; },
      timeTagType: function (v) { return v === 'am' ? 'primary' : (v === 'pm' ? 'warning' : 'info'); },
      feeTypeLabel: function (v) { return v === 'insurance' ? '医保' : (v === 'self' ? '自费' : (v || '-')); },
      payMethodLabel: function (v) {
        var m = { insurance: '医保结算', self_pay: '自费结算', cash: '现金', card: '银行卡', wechat: '微信', alipay: '支付宝', free: '免收' };
        return m[v] || v || '-';
      },
      timeLabel: timeLabel,
      statusLabel: statusLabel,
      statusTag: statusTag
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">退号 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(跨日/历史退号专用页面; 当天刚挂的号可在挂号工作站“今日挂号记录”中快捷退号)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '    <el-select v-model="filterDept" placeholder="全部科室" clearable filterable style="width:170px" @change="onDeptChange">',
      '      <el-option v-for="d in deptOptions" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="filterStaff" placeholder="全部医师" clearable filterable style="width:140px">',
      '      <el-option v-for="s in staffOptions" :key="s.id" :label="s.staffName" :value="s.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width:110px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="String(s.v)" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="患者姓名/挂号单号/门诊号/患者号/拼音码" clearable style="width:250px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="reset">重置</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '    <el-button :disabled="!list.length" @click="doPrintPage">打印本页</el-button>',
      '    <el-button :loading="printing" @click="doPrintAll">打印全部</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="56" :index="seqNo"></el-table-column>',
      '    <el-table-column label="挂号时间" width="140"><template #default="s">{{ fmtDateTime(s.row.regTime) }}</template></el-table-column>',
      '    <el-table-column prop="regNo" label="挂号单号" width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="iptOtpNo" label="门诊号" width="110" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="80"></el-table-column>',
      '    <el-table-column prop="patientNo" label="患者号" width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="100" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="drName" label="医师" width="80"></el-table-column>',
      '    <el-table-column prop="workDate" label="出诊日期" width="100"></el-table-column>',
      '    <el-table-column label="时段" width="60"><template #default="s"><el-tag size="small" :type="timeTagType(s.row.timeType)" :class="{\'reg-tag--night\': s.row.timeType===\'night\'}">{{ timeLabel(s.row.timeType) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="regLevelName" label="号别" width="90" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="挂号费" width="76" align="right"><template #default="s">¥{{ fmtMoney(s.row.regFee) }}</template></el-table-column>',
      '    <el-table-column label="减免金额" width="80" align="right"><template #default="s"><span :style="Number(s.row.discountAmount) > 0 ? \'color:var(--yb-danger);\' : \'\'">{{ fmtMoney(s.row.discountAmount) }}</span></template></el-table-column>',
      '    <el-table-column label="实收金额" width="82" align="right"><template #default="s"><b>¥{{ fmtMoney(s.row.actualFee) }}</b></template></el-table-column>',
      '    <el-table-column label="费别" width="66"><template #default="s">{{ feeTypeLabel(s.row.feeType) }}</template></el-table-column>',
      '    <el-table-column label="支付方式" width="84"><template #default="s">{{ payMethodLabel(s.row.payMethod) }}</template></el-table-column>',
      '    <el-table-column prop="queueNo" label="候诊序号" width="90"></el-table-column>',
      '    <el-table-column prop="mdtrtId" label="医保就诊ID" width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="状态" width="78" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="退号时间" width="140"><template #default="s">{{ fmtDateTime(s.row.cancelTime) }}</template></el-table-column>',
      '    <el-table-column prop="cancelReason" label="退号原因" width="130" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="操作" width="150" fixed="right"><template #default="s">',
      '      <el-button v-if="s.row.status===1" link type="danger" size="small" @click="doCancel(s.row)">退号</el-button>',
      '      <el-button v-if="s.row.status===1" link type="warning" size="small" @click="openChangeSlot(s.row)">换号</el-button>',
      '      <el-button v-if="s.row.status===1 || s.row.status===3" link type="info" size="small" @click="showTicket(s.row)">凭条</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* 退号确认对话框(500px): 患者摘要 + 挂号摘要 + 费用 + 退号原因 + 已收费退费提示 */
      '  <el-dialog v-model="cancelDlg" title="退号确认" width="500px">',
      '    <div v-if="cancelRow" v-loading="patientLoading">',
      '      <el-descriptions title="患者信息" :column="2" border size="small">',
      '        <el-descriptions-item label="姓名">{{ cancelRow.patientName }}</el-descriptions-item>',
      '        <el-descriptions-item label="性别">{{ patientGender() }}</el-descriptions-item>',
      '        <el-descriptions-item label="年龄">{{ patientAge()==null ? "-" : patientAge() }}</el-descriptions-item>',
      '        <el-descriptions-item label="患者号">{{ cancelRow.patientNo || "-" }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-descriptions title="挂号信息" :column="2" border size="small" style="margin-top:12px;">',
      '        <el-descriptions-item label="科室">{{ cancelRow.deptName || "-" }}</el-descriptions-item>',
      '        <el-descriptions-item label="医师">{{ cancelRow.drName || "-" }}</el-descriptions-item>',
      '        <el-descriptions-item label="出诊日期">{{ cancelRow.workDate || "-" }}</el-descriptions-item>',
      '        <el-descriptions-item label="时段">{{ timeLabel(cancelRow.timeType) }}</el-descriptions-item>',
      '        <el-descriptions-item label="号别" :span="2">{{ cancelRow.regLevelName || "-" }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-descriptions title="费用信息" :column="2" border size="small" style="margin-top:12px;">',
      '        <el-descriptions-item label="挂号费">¥{{ fmtMoney(cancelRow.regFee) }}</el-descriptions-item>',
      '        <el-descriptions-item label="减免金额">¥{{ fmtMoney(cancelRow.discountAmount) }}</el-descriptions-item>',
      '        <el-descriptions-item label="实收金额">¥{{ fmtMoney(cancelRow.actualFee) }}</el-descriptions-item>',
      '        <el-descriptions-item label="支付方式">{{ payMethodLabel(cancelRow.payMethod) }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-form label-width="70px" style="margin-top:14px;">',
      '        <el-form-item label="退号原因">',
      '          <el-input v-model="cancelReason" type="textarea" :rows="2" maxlength="200" placeholder="请输入退号原因（选填）"></el-input>',
      '        </el-form-item>',
      '      </el-form>',
      '      <el-alert v-if="needRefundHint" type="warning" :closable="false" show-icon title="该挂号已收费，退号后需到收费窗口办理退费" style="margin-top:4px;"></el-alert>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="cancelDlg=false">取消</el-button>',
      '      <el-button type="danger" :loading="cancelling" @click="confirmCancel">确认退号</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* 挂号凭条(复用挂号工作站凭条样式) */
      '  <el-dialog v-model="ticketDlg" title="挂号凭条" width="480px">',
      '    <div v-if="ticketRow" class="reg-ticket-preview">',
      '      <div class="ticket-title">{{ ticketRow.deptName }} 门诊挂号凭条</div>',
      '      <div class="ticket-divider"></div>',
      '      <div>患者: {{ ticketRow.patientName }}  单号: {{ ticketRow.regNo }}</div>',
      '      <div>医师: {{ ticketRow.drName }}  日期: {{ ticketRow.workDate }} {{ timeLabel(ticketRow.timeType) }}</div>',
      '      <div>费用: 挂号¥{{ fmtMoney(ticketRow.regFee) }} 减免¥{{ fmtMoney(ticketRow.discountAmount) }} 实收¥{{ fmtMoney(ticketRow.actualFee) }}</div>',
      '      <div class="ticket-queue">{{ ticketRow.queueNo || "-" }}</div>',
      '      <div class="ticket-divider"></div>',
      '      <div class="ticket-footer">请按候诊号顺序就诊 · 医保就诊ID {{ ticketRow.mdtrtId || "-" }}</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="ticketDlg=false">关闭</el-button>',
      '      <el-button type="primary" @click="doPrint">打印</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* 换号对话框(ChangeSlotMixin 共用模板) */
      CS_DIALOG,
      '</div>'
    ].join('\n')
  };
})();

/* ================= 挂号统计(统计卡片 + 6图表 + 明细分页) =================
   独立 IIFE 与上方组件解耦: 仅注册 HIS.views.RegStatistics(菜单 reg_stats/挂号统计)。
   数据源: /api/his/registration/stats(卡片+图表)、/api/his/registration/statDetail(明细分页/导出)、
   /api/his/schedule/list(号源利用率: 有效排班 total/left 汇总)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return fmtDate(d); }
  function money(n) { return (n === null || n === undefined) ? '0.00' : Number(n).toFixed(2); }
  function int(n) { return (n === null || n === undefined) ? '0' : String(Math.round(Number(n))); }
  /* yyyy-MM-dd 解析为本地 Date(避免 new Date('yyyy-MM-dd') 按 UTC 解析产生偏移) */
  function parseDay(s) {
    var p = String(s || '').split('-');
    return new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]));
  }
  /* [from,to] 闭区间逐日列表 yyyy-MM-dd(限 366 天防极端区间) */
  function dayList(from, to) {
    var out = [];
    if (!from || !to) { return out; }
    var d = parseDay(from), end = parseDay(to), i = 0;
    while (d <= end && i < 366) { out.push(fmtDate(d)); d.setDate(d.getDate() + 1); i++; }
    return out;
  }
  function mmdd(s) { return s ? String(s).slice(5) : ''; }
  function timeLabel(v) { return v === 'am' ? '上午' : (v === 'pm' ? '下午' : (v === 'night' ? '晚间' : (v || '-'))); }
  function statusLabel(v) {
    var s = Number(v);
    if (s === 1) { return '已挂号'; }
    if (s === 2) { return '已退号'; }
    if (s === 3) { return '已就诊'; }
    return (v === null || v === undefined) ? '-' : String(v);
  }
  function statusTag(v) {
    var s = Number(v);
    return s === 1 ? 'success' : (s === 3 ? 'warning' : 'info');
  }
  /* 费别标签(兼容编码/中文两种来源, 未知原样展示) */
  function feeTypeLabel(v) {
    if (v === null || v === undefined || v === '') { return '-'; }
    var s = String(v).trim().toLowerCase();
    if (s === 'yb' || s === 'insurance' || s.indexOf('医保') >= 0) { return '医保'; }
    if (s === 'self' || s === 'selfpay' || s.indexOf('自费') >= 0) { return '自费'; }
    return String(v);
  }
  function isYbFee(v) { return feeTypeLabel(v) === '医保'; }
  /* 支付方式标签(现金/银行卡/微信/支付宝/医保/免收) */
  function payLabel(v) {
    if (v === null || v === undefined || v === '') { return '未记录'; }
    var s = String(v).trim().toLowerCase();
    if (s === 'cash' || s.indexOf('现金') >= 0) { return '现金'; }
    if (s === 'bank' || s === 'bankcard' || s === 'card' || s.indexOf('银行') >= 0) { return '银行卡'; }
    if (s === 'wechat' || s === 'wx' || s.indexOf('微信') >= 0) { return '微信'; }
    if (s === 'alipay' || s === 'zfb' || s.indexOf('支付宝') >= 0) { return '支付宝'; }
    if (s === 'insurance' || s === 'yb' || s.indexOf('医保') >= 0) { return '医保'; }
    if (s === 'free' || s.indexOf('免收') >= 0 || s.indexOf('免费') >= 0) { return '免收'; }
    return String(v);
  }
  function discountLabel(v) {
    if (v === null || v === undefined || v === '' || v === 'none') { return '-'; }
    if (v === 'age70free') { return '70岁以上免挂号费'; }
    return String(v);
  }
  function fmtTime(v) { return v ? String(v).replace('T', ' ') : '-'; }

  HIS.views.RegStatistics = {
    data: function () {
      return {
        loading: false, exporting: false,
        dateRange: [daysAgo(6), today()],
        depts: [], staffs: [], filterDept: null, filterStaff: null,
        stats: null,
        slotTotal: null, slotUsed: null,
        chartDept: null, chartTime: null, chartTrend: null, chartStaff: null, chartFee: null, chartPay: null,
        activeCollapse: [],
        dLoading: false, dList: [], dTotal: 0, dPage: 1, dSize: 20
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      /* 科室下拉仅列科室级节点(deptLevel=2), 与挂号台口径一致 */
      deptOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      /* 医师联动科室: 选定科室后仅列该科室医师 */
      staffOptions: function () {
        var dep = this.filterDept;
        if (!dep) { return this.staffs || []; }
        return (this.staffs || []).filter(function (s) { return s.deptId === dep; });
      },
      /* 汇总均由 stats 各分组求和得出(与后端同口径) */
      sumReg: function () { return this.sumBy((this.stats || {}).byDate, 'regCount'); },
      sumCancel: function () { return this.sumBy((this.stats || {}).byDate, 'cancelCount'); },
      sumFee: function () { return this.sumBy((this.stats || {}).byFeeType, 'totalActualFee'); },
      sumDiscount: function () {
        var t = 0;
        ((this.stats || {}).byDiscountType || []).forEach(function (r) {
          if (String(r.discount_type || '') !== 'none') { t += Number(r.totalAmount) || 0; }
        });
        return t;
      },
      cancelRate: function () {
        var all = this.sumReg + this.sumCancel;
        return all ? (this.sumCancel / all * 100).toFixed(1) : '0.0';
      },
      utilization: function () {
        if (!this.slotTotal) { return null; }
        return (this.slotUsed / this.slotTotal * 100).toFixed(1);
      },
      cards: function () {
        return [
          { cls: 'ds-card tone-blue', icon: '📋', title: '挂号总量', color: 'var(--yb-link)', fs: '26px',
            num: int(this.sumReg), sub: '人次(含已就诊, 不含已退号)' },
          { cls: 'ds-card tone-red', icon: '↩', title: '退号量 / 退号率', color: 'var(--yb-danger)', fs: '20px',
            num: int(this.sumCancel) + ' 笔 / ' + this.cancelRate + '%', sub: '退号笔数 / 退号占全部挂号比例' },
          { cls: 'ds-card tone-green', icon: '💰', title: '收费 / 减免汇总', color: 'var(--yb-success)', fs: '20px',
            num: '¥ ' + money(this.sumFee), sub: '其中减免 ¥ ' + money(this.sumDiscount) },
          { cls: 'ds-card tone-orange', icon: '📈', title: '号源利用率', color: 'var(--yb-warning)', fs: '26px',
            num: this.utilization === null ? '—' : (this.utilization + '%'),
            sub: this.slotTotal ? ('已用 ' + int(this.slotUsed) + ' / 总号源 ' + int(this.slotTotal)) : '按有效排班总号源计算' }
        ];
      }
    },
    created: function () {
      this.loadRefs();
      this.loadStats();
      this.loadSlotUtil();
      this.loadDetail();
    },
    mounted: function () {
      var vm = this;
      vm.$nextTick(function () { vm.initCharts(); });
    },
    /* 销毁钩子(Vue3): 移除resize监听 + dispose全部图表实例防内存泄漏 */
    beforeUnmount: function () {
      var vm = this;
      window.removeEventListener('resize', vm._onResize);
      ['chartDept', 'chartTime', 'chartTrend', 'chartStaff', 'chartFee', 'chartPay'].forEach(function (k) {
        if (vm[k]) { vm[k].dispose(); vm[k] = null; }
      });
    },
    methods: {
      /* ===== 通用 ===== */
      sumBy: function (rows, field) {
        var t = 0;
        (rows || []).forEach(function (r) { t += Number(r[field]) || 0; });
        return t;
      },
      money: money, int: int, timeLabel: timeLabel,
      statusLabel: statusLabel, statusTag: statusTag,
      feeTypeLabel: feeTypeLabel, payLabel: payLabel, discountLabel: discountLabel,
      fmtTime: fmtTime,
      /* ===== 参照数据(科室/医师) ===== */
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        var orgId = HIS.currentOrgId();
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false' + (orgId ? ('&orgId=' + orgId) : ''))
          .then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
      },
      /* 科室切换后清空不属于该科室的已选医师, 避免"科室A+医师B"的脏组合 */
      onDeptChange: function () {
        var vm = this;
        if (!vm.filterStaff || !vm.filterDept) { return; }
        for (var i = 0; i < vm.staffs.length; i++) {
          if (vm.staffs[i].id === vm.filterStaff) {
            if (vm.staffs[i].deptId !== vm.filterDept) { vm.filterStaff = null; }
            break;
          }
        }
      },
      /* ===== 查询: 卡片+图表 / 号源利用率 / 明细 同条件刷新 ===== */
      onQuery: function () {
        if (!this.startDate || !this.endDate) { ElementPlus.ElMessage.warning('请选择日期范围'); return; }
        this.dPage = 1;
        this.loadStats();
        this.loadSlotUtil();
        this.loadDetail();
      },
      loadStats: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { return; }
        vm.loading = true;
        var q = '/api/his/registration/stats?from=' + vm.startDate + '&to=' + vm.endDate;
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
        HIS.get(q).then(function (d) {
          vm.stats = d || {};
          vm.renderCharts();
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 号源利用率: 有效排班(status=1)按区间全量分页汇总(total/left -> 已用) */
      loadSlotUtil: function () {
        var vm = this;
        vm.slotTotal = null; vm.slotUsed = null;
        if (!vm.startDate || !vm.endDate) { return; }
        var all = [], page = 1;
        function fetchPage() {
          var q = '/api/his/schedule/list?status=1&page=' + page + '&size=200'
            + '&from=' + vm.startDate + '&to=' + vm.endDate;
          if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
          if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
          return HIS.get(q).then(function (d) {
            var recs = (d && d.records) || [];
            all = all.concat(recs);
            var total = (d && d.total) || 0;
            if (recs.length >= 200 && all.length < total && page < 50) { page++; return fetchPage(); }
            var t = 0, u = 0;
            all.forEach(function (s) {
              var tn = Number(s.totalNum != null ? s.totalNum : s.total_num) || 0;
              var ln = Number(s.leftNum != null ? s.leftNum : s.left_num) || 0;
              t += tn; u += Math.max(tn - ln, 0);
            });
            vm.slotTotal = t; vm.slotUsed = u;
          });
        }
        fetchPage().catch(function () { vm.slotTotal = null; vm.slotUsed = null; });
      },
      /* ===== 明细(可折叠) ===== */
      detailQuery: function (page, size) {
        var vm = this;
        var q = '/api/his/registration/statDetail?page=' + page + '&size=' + size
          + '&from=' + vm.startDate + '&to=' + vm.endDate;
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
        return q;
      },
      loadDetail: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { return; }
        vm.dLoading = true;
        HIS.get(vm.detailQuery(vm.dPage, vm.dSize)).then(function (d) {
          vm.dList = (d && d.records) || [];
          vm.dTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.dLoading = false; });
      },
      onDetailPage: function (p) { this.dPage = p; this.loadDetail(); },
      onDetailSize: function (s) { this.dSize = s; this.dPage = 1; this.loadDetail(); },
      dSeqNo: function (i) { return (this.dPage - 1) * this.dSize + i + 1; },
      /* ===== 导出(CSV): 分页拉全量 statDetail -> 前端生成带BOM的CSV(Excel可直接打开) ===== */
      fetchAllDetail: function () {
        var vm = this;
        var all = [], page = 1;
        function fetchPage() {
          return HIS.get(vm.detailQuery(page, 200)).then(function (d) {
            var recs = (d && d.records) || [];
            all = all.concat(recs);
            var total = (d && d.total) || 0;
            if (recs.length >= 200 && all.length < total && page < 50) { page++; return fetchPage(); }
            return all;
          });
        }
        return fetchPage();
      },
      csvCell: function (v) {
        var s = (v === null || v === undefined) ? '' : String(v);
        if (/[",\r\n]/.test(s)) { s = '"' + s.replace(/"/g, '""') + '"'; }
        return s;
      },
      doExport: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { ElementPlus.ElMessage.warning('请先选择日期范围'); return; }
        vm.exporting = true;
        vm.fetchAllDetail().then(function (rows) {
          if (!rows.length) { ElementPlus.ElMessage.warning('当前条件下无数据可导出'); return; }
          var head = ['挂号时间', '挂号单号', '患者', '科室', '医师', '时段', '号别', '费别', '挂号费', '减免', '实收', '支付方式', '状态'];
          var lines = [head.join(',')];
          rows.forEach(function (r) {
            lines.push([
              fmtTime(r.reg_time), r.reg_no, r.patient_name, r.dept_name, r.dr_name,
              timeLabel(r.time_type), r.reg_level_name, feeTypeLabel(r.fee_type),
              money(r.reg_fee), money(r.discount_amount), money(r.actual_fee),
              payLabel(r.pay_method), statusLabel(r.status)
            ].map(vm.csvCell).join(','));
          });
          var blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
          var a = document.createElement('a');
          var u = URL.createObjectURL(blob);
          a.href = u;
          a.download = '挂号统计明细_' + vm.startDate + '_' + vm.endDate + '.csv';
          document.body.appendChild(a); a.click();
          setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 1000);
          HIS.notifySuccess('已导出 ' + rows.length + ' 条明细');
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      /* ===== 图表初始化/dispose安全 ===== */
      initCharts: function () {
        var vm = this;
        if (!window.echarts) {
          ElementPlus.ElMessage.warning('图表库(echarts)未加载, 仅显示统计卡片与明细');
          return;
        }
        vm.chartDept = echarts.init(vm.$refs.chartDept, 'yb');
        vm.chartTime = echarts.init(vm.$refs.chartTime, 'yb');
        vm.chartTrend = echarts.init(vm.$refs.chartTrend, 'yb');
        vm.chartStaff = echarts.init(vm.$refs.chartStaff, 'yb');
        vm.chartFee = echarts.init(vm.$refs.chartFee, 'yb');
        vm.chartPay = echarts.init(vm.$refs.chartPay, 'yb');
        vm.chartList().forEach(function (c) {
          c.showLoading('default', { text: '加载中...', color: HIS.theme.link, maskColor: 'rgba(255,255,255,.6)' });
        });
        vm.renderCharts();
        vm._onResize = function () {
          vm.chartList().forEach(function (c) { if (c && !c.isDisposed()) { c.resize(); } });
        };
        window.addEventListener('resize', vm._onResize);
      },
      chartList: function () {
        var vm = this;
        return [vm.chartDept, vm.chartTime, vm.chartTrend, vm.chartStaff, vm.chartFee, vm.chartPay];
      },
      /* ===== 六图渲染(stats 变化后整体重绘) ===== */
      renderCharts: function () {
        var vm = this;
        if (!vm.chartDept || !vm.stats) { return; }
        vm.chartList().forEach(function (c) { if (c && !c.isDisposed()) { c.hideLoading(); } });
        var s = vm.stats || {};
        vm.renderDeptChart((s.byDept || []).slice(0, 10));
        vm.renderTimeChart(s.byTimeType || []);
        vm.renderTrendChart(s.byDate || []);
        vm.renderStaffChart((s.byStaff || []).slice(0, 10));
        vm.renderFeeChart(s.byFeeType || [], s.byDiscountType || []);
        vm.renderPayChart(s.byPayMethod || []);
      },
      /* 图1: 科室挂号量 TOP10 横向柱状(反转使最大在顶部) */
      renderDeptChart: function (rows) {
        var vm = this;
        var data = rows.slice().reverse();
        vm.chartDept.setOption({
          tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: '{b}<br/>挂号量: <b>{c}</b> 人次' },
          grid: { left: 10, right: 44, top: 16, bottom: 10, containLabel: true },
          xAxis: { type: 'value', minInterval: 1, axisLabel: { color: HIS.theme.ink3 }, splitLine: { lineStyle: { color: HIS.theme.split } } },
          yAxis: {
            type: 'category',
            data: data.map(function (r) { return r.dept_name || r.deptName || '-'; }),
            axisLabel: { color: HIS.theme.ink3, fontSize: 11, formatter: function (v) { return String(v).length > 7 ? String(v).slice(0, 6) + '…' : v; } },
            axisTick: { show: false }
          },
          series: [{
            name: '挂号量', type: 'bar', barMaxWidth: 16,
            data: data.map(function (r) { return Number(r['count']) || 0; }),
            itemStyle: { color: HIS.theme.link, borderRadius: [0, 4, 4, 0] },
            label: { show: true, position: 'right', color: HIS.theme.link, fontSize: 11 }
          }]
        });
      },
      /* 图2: 时段分布饼图(上午/下午/晚间固定三色) */
      renderTimeChart: function (rows) {
        var vm = this;
        var meta = { am: { n: '上午', c: HIS.theme.link }, pm: { n: '下午', c: HIS.theme.warning }, night: { n: '晚间', c: HIS.theme.purple } };
        var seen = {}, data = [];
        ['am', 'pm', 'night'].forEach(function (k) {
          rows.forEach(function (r) {
            if (r.time_type === k) {
              seen[k] = true;
              data.push({ name: meta[k].n, value: Number(r['count']) || 0, itemStyle: { color: meta[k].c } });
            }
          });
        });
        rows.forEach(function (r) {
          if (!seen[r.time_type]) { data.push({ name: timeLabel(r.time_type), value: Number(r['count']) || 0 }); }
        });
        vm.chartTime.setOption({
          tooltip: { trigger: 'item', formatter: '{b}: {c} 笔 ({d}%)' },
          legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
          series: [{
            name: '时段分布', type: 'pie', radius: ['42%', '66%'], center: ['50%', '44%'],
            itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
            label: { formatter: '{b}\n{c}笔', fontSize: 11 },
            data: data
          }]
        });
      },
      /* 图3: 每日趋势折线(挂号/退号/减免; X轴按区间补零全覆盖) */
      renderTrendChart: function (rows) {
        var vm = this;
        var byDate = {};
        rows.forEach(function (r) { byDate[r.work_date] = r; });
        var days = dayList(vm.startDate, vm.endDate);
        if (!days.length) { days = rows.map(function (r) { return r.work_date; }); }
        var regs = [], cancels = [], discounts = [];
        days.forEach(function (d) {
          var r = byDate[d] || {};
          regs.push(Number(r.regCount) || 0);
          cancels.push(Number(r.cancelCount) || 0);
          discounts.push(Number(r.discountCount) || 0);
        });
        vm.chartTrend.setOption({
          color: [HIS.theme.link, HIS.theme.danger, HIS.theme.purple],
          tooltip: { trigger: 'axis' },
          legend: { top: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
          grid: { left: 40, right: 20, top: 36, bottom: 28 },
          xAxis: { type: 'category', boundaryGap: false, data: days.map(mmdd), axisLabel: { color: HIS.theme.ink3 } },
          yAxis: { type: 'value', minInterval: 1, axisLabel: { color: HIS.theme.ink3 }, splitLine: { lineStyle: { color: HIS.theme.split } } },
          series: [
            { name: '挂号量', type: 'line', smooth: true, symbolSize: 5, data: regs, lineStyle: { width: 2.5 } },
            { name: '退号量', type: 'line', smooth: true, symbolSize: 5, data: cancels, lineStyle: { width: 2.5 } },
            { name: '减免笔数', type: 'line', smooth: true, symbolSize: 5, data: discounts, lineStyle: { width: 2.5, type: 'dashed' } }
          ]
        });
      },
      /* 图4: 医师工作量 TOP10 横向柱状(绿色) */
      renderStaffChart: function (rows) {
        var vm = this;
        var data = rows.slice().reverse();
        vm.chartStaff.setOption({
          tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: '{b}<br/>挂号量: <b>{c}</b> 人次' },
          grid: { left: 10, right: 44, top: 16, bottom: 10, containLabel: true },
          xAxis: { type: 'value', minInterval: 1, axisLabel: { color: HIS.theme.ink3 }, splitLine: { lineStyle: { color: HIS.theme.split } } },
          yAxis: {
            type: 'category',
            data: data.map(function (r) { return r.dr_name || r.staffName || '-'; }),
            axisLabel: { color: HIS.theme.ink3, fontSize: 11 }, axisTick: { show: false }
          },
          series: [{
            name: '挂号量', type: 'bar', barMaxWidth: 16,
            data: data.map(function (r) { return Number(r['count']) || 0; }),
            itemStyle: { color: HIS.theme.success, borderRadius: [0, 4, 4, 0] },
            label: { show: true, position: 'right', color: HIS.theme.success, fontSize: 11 }
          }]
        });
      },
      /* 图5: 收费/减免构成饼图(医保/自费/减免; 医保自费取自 byFeeType 实收金额) */
      renderFeeChart: function (feeRows, discountRows) {
        var vm = this;
        var yb = 0, self = 0, disc = 0;
        feeRows.forEach(function (r) {
          var amt = Number(r.totalActualFee) || 0;
          if (isYbFee(r.fee_type)) { yb += amt; } else { self += amt; }
        });
        discountRows.forEach(function (r) {
          if (String(r.discount_type || '') !== 'none') { disc += Number(r.totalAmount) || 0; }
        });
        vm.chartFee.setOption({
          tooltip: { trigger: 'item', formatter: function (p) { return p.name + ': <b>¥ ' + money(p.value) + '</b> (' + p.percent + '%)'; } },
          legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
          series: [{
            name: '收费/减免构成', type: 'pie', radius: ['42%', '66%'], center: ['50%', '44%'],
            itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
            label: { formatter: function (p) { return p.name + ' ¥' + money(p.value); }, fontSize: 11 },
            data: [
              { name: '医保', value: yb, itemStyle: { color: HIS.theme.link } },
              { name: '自费', value: self, itemStyle: { color: HIS.theme.warning } },
              { name: '减免', value: disc, itemStyle: { color: HIS.theme.success } }
            ]
          }]
        });
      },
      /* 图6: 支付方式占比饼图(现金/银行卡/微信/支付宝/医保/免收; 按笔数) */
      renderPayChart: function (rows) {
        var vm = this;
        var order = ['现金', '银行卡', '微信', '支付宝', '医保', '免收'];
        var agg = {};
        rows.forEach(function (r) {
          var n = payLabel(r.pay_method);
          agg[n] = (agg[n] || 0) + (Number(r['count']) || 0);
        });
        var data = [];
        order.forEach(function (n) { if (agg[n] != null) { data.push({ name: n, value: agg[n] }); delete agg[n]; } });
        Object.keys(agg).forEach(function (n) { data.push({ name: n, value: agg[n] }); });
        vm.chartPay.setOption({
          color: [HIS.theme.link, HIS.theme.warning, HIS.theme.success, '#8e6fff', HIS.theme.danger, HIS.theme.ink3, '#00c9a7', '#ff7b9c'],
          tooltip: { trigger: 'item', formatter: '{b}: {c} 笔 ({d}%)' },
          legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
          series: [{
            name: '支付方式', type: 'pie', radius: ['42%', '66%'], center: ['50%', '44%'],
            itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
            label: { formatter: '{b} {d}%', fontSize: 11 },
            data: data
          }]
        });
      }
    },
    template: [
      '<div>',
      /* A. 查询条件栏 */
      '  <div class="page-card">',
      '    <div class="page-title">挂号统计 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按出诊日期区间统计, 全部数据由挂号记录实时计算)</span></div>',
      '    <div class="toolbar" style="margin-bottom:0;">',
      '      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '      <el-select v-model="filterDept" placeholder="全部科室" clearable filterable style="width:170px" @change="onDeptChange">',
      '        <el-option v-for="d in deptOptions" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '      </el-select>',
      '      <el-select v-model="filterStaff" placeholder="全部医师" clearable filterable style="width:150px">',
      '        <el-option v-for="s in staffOptions" :key="s.id" :label="s.staffName" :value="s.id"></el-option>',
      '      </el-select>',
      '      <el-button type="primary" @click="onQuery">查询</el-button>',
      '      <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '    </div>',
      '  </div>',
      /* B. 统计卡片区(4个) */
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="6" v-for="c in cards" :key="c.title">',
      '      <div :class="c.cls" v-loading="loading">',
      '        <div class="ds-head">',
      '          <div class="ds-ico">{{ c.icon }}</div>',
      '          <div class="ds-title">{{ c.title }}</div>',
      '        </div>',
      '        <div class="ds-num" :style="{ color: c.color, fontSize: c.fs }">{{ c.num }}</div>',
      '        <div style="color:var(--yb-ink-2);font-size:12px;margin-top:6px;">{{ c.sub }}</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      /* C. 图表区(3行2列, 每图300px) */
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="12">',
      '      <el-card shadow="never"><template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">科室挂号量排行 TOP10</span></template>',
      '        <div ref="chartDept" style="height:300px;"></div></el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never"><template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">时段分布(上午/下午/晚间)</span></template>',
      '        <div ref="chartTime" style="height:300px;"></div></el-card>',
      '    </el-col>',
      '  </el-row>',
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="12">',
      '      <el-card shadow="never"><template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">每日趋势(挂号/退号/减免)</span></template>',
      '        <div ref="chartTrend" style="height:300px;"></div></el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never"><template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">医师工作量排行 TOP10</span></template>',
      '        <div ref="chartStaff" style="height:300px;"></div></el-card>',
      '    </el-col>',
      '  </el-row>',
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="12">',
      '      <el-card shadow="never"><template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">收费/减免构成</span></template>',
      '        <div ref="chartFee" style="height:300px;"></div></el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never"><template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">支付方式占比</span></template>',
      '        <div ref="chartPay" style="height:300px;"></div></el-card>',
      '    </el-col>',
      '  </el-row>',
      /* D. 明细列表(可折叠, 默认折叠) */
      '  <div class="page-card">',
      '    <el-collapse v-model="activeCollapse">',
      '      <el-collapse-item name="detail">',
      '        <template #title>',
      '          <span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">挂号明细',
      '            <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(点击展开/收起, 共 {{ dTotal }} 条)</span></span>',
      '        </template>',
      '        <div class="toolbar">',
      '          <el-button type="success" plain size="small" :loading="exporting" @click="doExport">导出Excel</el-button>',
      '          <span style="color:var(--yb-ink-2);font-size:12px;">导出与当前筛选条件一致(全量不分页)</span>',
      '        </div>',
      '        <el-table :data="dList" v-loading="dLoading" border stripe size="small">',
      '          <el-table-column type="index" label="序号" width="60" :index="dSeqNo"></el-table-column>',
      '          <el-table-column label="挂号时间" width="160"><template #default="s">{{ fmtTime(s.row.reg_time) }}</template></el-table-column>',
      '          <el-table-column prop="reg_no" label="挂号单号" width="170" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="patient_name" label="患者" width="90"></el-table-column>',
      '          <el-table-column prop="dept_name" label="科室" width="110"></el-table-column>',
      '          <el-table-column prop="dr_name" label="医师" width="90"></el-table-column>',
      '          <el-table-column label="时段" width="70"><template #default="s">{{ timeLabel(s.row.time_type) }}</template></el-table-column>',
      '          <el-table-column prop="reg_level_name" label="号别" width="100"></el-table-column>',
      '          <el-table-column label="费别" width="70"><template #default="s">{{ feeTypeLabel(s.row.fee_type) }}</template></el-table-column>',
      '          <el-table-column label="挂号费" width="85" align="right" header-align="right"><template #default="s">{{ money(s.row.reg_fee) }}</template></el-table-column>',
      '          <el-table-column label="减免" width="85" align="right" header-align="right"><template #default="s">{{ money(s.row.discount_amount) }}</template></el-table-column>',
      '          <el-table-column label="实收" width="85" align="right" header-align="right"><template #default="s"><b style="color:var(--yb-success);">{{ money(s.row.actual_fee) }}</b></template></el-table-column>',
      '          <el-table-column label="支付方式" width="90"><template #default="s">{{ payLabel(s.row.pay_method) }}</template></el-table-column>',
      '          <el-table-column label="状态" width="84" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        </el-table>',
      '        <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '          :total="dTotal" :page-size="dSize" :page-sizes="[20, 50, 100]" :current-page="dPage"',
      '          @current-change="onDetailPage" @size-change="onDetailSize"></el-pagination>',
      '      </el-collapse-item>',
      '    </el-collapse>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ================= 挂号明细查询(只读) =================
   * 菜单 reg_detail/挂号明细: 查看挂号与退号完整明细数据(不含退号操作, 退号请用挂号工作站或退号页)。
   * 数据源 /statDetail(支持日期/科室/医师/状态/关键字); 状态多选(非全选)/费别/减免类型/支付方式
   * 后端暂不支持组合过滤, 采用前端过滤(浏览为当前页过滤, 导出为全量精确过滤);
   * 顶部汇总卡从 /stats 按日期/科室/医师同条件获取(与挂号统计同口径)。 */
  HIS.views.RegDetailQuery = {
    data: function () {
      return {
        loading: false, exporting: false, statsLoading: false,
        /* 默认最近30天 */
        dateRange: [daysAgo(29), today()],
        depts: [], staffs: [], filterDept: null, filterStaff: null,
        statusSel: [1, 2, 3], statusOpts: [{ v: 1, l: '已挂号' }, { v: 2, l: '已退号' }, { v: 3, l: '已就诊' }],
        feeType: '', discountType: '', payMethod: '', keyword: '',
        stats: null,
        list: [], total: 0, page: 1, size: 20
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      /* 科室下拉仅科室级节点(deptLevel=2), 与挂号台口径一致 */
      deptOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      /* 医师下拉联动科室 */
      staffOptions: function () {
        var dep = this.filterDept;
        if (!dep) { return this.staffs || []; }
        return (this.staffs || []).filter(function (s) { return s.deptId === dep; });
      },
      /* 状态参数: 空/全选->null(后端查全部); 单选->传后端精确过滤; 多选(非全选)->mixed(前端过滤) */
      statusParam: function () {
        var st = this.statusSel || [];
        if (st.length === 0 || st.length >= 3) { return null; }
        if (st.length === 1) { return st[0]; }
        return 'mixed';
      },
      /* 存在后端不支持的客户端过滤条件时提示口径 */
      clientFiltered: function () {
        return this.statusParam === 'mixed' || !!(this.feeType || this.discountType || this.payMethod);
      },
      /* 汇总(与挂号统计同口径): byDate 区分有效挂号/退号, byFeeType 合计实收 */
      sumReg: function () { return this.sumBy((this.stats || {}).byDate, 'regCount'); },
      sumCancel: function () { return this.sumBy((this.stats || {}).byDate, 'cancelCount'); },
      sumFee: function () { return this.sumBy((this.stats || {}).byFeeType, 'totalActualFee'); },
      sumTotal: function () { return this.sumReg + this.sumCancel; }
    },
    created: function () {
      this.loadRefs();
      this.loadStats();
      this.load();
    },
    methods: {
      /* ===== 参照数据(科室/医师) ===== */
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        var orgId = HIS.currentOrgId();
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false' + (orgId ? ('&orgId=' + orgId) : ''))
          .then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
      },
      /* 科室切换后清空不属于该科室的已选医师, 避免“科室A+医师B”脏组合 */
      onDeptChange: function () {
        var vm = this;
        if (!vm.filterStaff || !vm.filterDept) { return; }
        for (var i = 0; i < vm.staffs.length; i++) {
          if (vm.staffs[i].id === vm.filterStaff) {
            if (vm.staffs[i].deptId !== vm.filterDept) { vm.filterStaff = null; }
            break;
          }
        }
      },
      sumBy: function (rows, field) {
        var t = 0;
        (rows || []).forEach(function (r) { t += Number(r[field]) || 0; });
        return t;
      },
      /* ===== 查询 ===== */
      search: function () {
        if (!this.startDate || !this.endDate) { ElementPlus.ElMessage.warning('请选择日期范围'); return; }
        this.page = 1;
        this.loadStats();
        this.load();
      },
      reset: function () {
        var vm = this;
        vm.dateRange = [daysAgo(29), today()];
        vm.filterDept = null; vm.filterStaff = null;
        vm.statusSel = [1, 2, 3];
        vm.feeType = ''; vm.discountType = ''; vm.payMethod = '';
        vm.keyword = '';
        vm.search();
      },
      /* 客户端过滤: 状态(多选非全选)/费别/减免类型/支付方式(后端暂不支持组合条件) */
      applyClientFilters: function (rows) {
        var vm = this;
        var st = vm.statusSel || [];
        var useSt = st.length > 0 && st.length < 3;
        var feeL = vm.feeType === 'insurance' ? '医保' : (vm.feeType === 'self' ? '自费' : '');
        return (rows || []).filter(function (r) {
          if (useSt && st.indexOf(Number(r.status)) < 0) { return false; }
          if (feeL && feeTypeLabel(r.fee_type) !== feeL) { return false; }
          if (vm.discountType && String(r.discount_type || '') !== vm.discountType) { return false; }
          if (vm.payMethod && String(r.pay_method || '') !== vm.payMethod) { return false; }
          return true;
        });
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/registration/statDetail?page=' + vm.page + '&size=' + vm.size
          + '&from=' + vm.startDate + '&to=' + vm.endDate;
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
        if (vm.statusParam != null && vm.statusParam !== 'mixed') { q += '&status=' + vm.statusParam; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = vm.applyClientFilters((d && d.records) || []);
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 汇总卡片: /stats 按日期/科室/医师同条件(费别/减免/支付/关键字等前端条件不参与) */
      loadStats: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { return; }
        vm.statsLoading = true;
        var q = '/api/his/registration/stats?from=' + vm.startDate + '&to=' + vm.endDate;
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
        HIS.get(q).then(function (d) { vm.stats = d || {}; })
          .catch(function () { vm.stats = {}; })
          .finally(function () { vm.statsLoading = false; });
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ===== 导出(CSV BOM): 分页拉全量 statDetail + 前端条件精确过滤 ===== */
      fetchAll: function () {
        var vm = this;
        var all = [], page = 1;
        function fetchPage() {
          var q = '/api/his/registration/statDetail?page=' + page + '&size=200'
            + '&from=' + vm.startDate + '&to=' + vm.endDate;
          if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
          if (vm.filterStaff) { q += '&staffId=' + vm.filterStaff; }
          if (vm.statusParam != null && vm.statusParam !== 'mixed') { q += '&status=' + vm.statusParam; }
          if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
          return HIS.get(q).then(function (d) {
            var recs = (d && d.records) || [];
            all = all.concat(recs);
            var total = (d && d.total) || 0;
            if (recs.length >= 200 && all.length < total && page < 50) { page++; return fetchPage(); }
            return all;
          });
        }
        return fetchPage();
      },
      csvCell: function (v) {
        var s = (v === null || v === undefined) ? '' : String(v);
        if (/[",\r\n]/.test(s)) { s = '"' + s.replace(/"/g, '""') + '"'; }
        return s;
      },
      doExport: function () {
        var vm = this;
        if (!vm.startDate || !vm.endDate) { ElementPlus.ElMessage.warning('请先选择日期范围'); return; }
        vm.exporting = true;
        vm.fetchAll().then(function (rows) {
          rows = vm.applyClientFilters(rows);
          if (!rows.length) { ElementPlus.ElMessage.warning('当前条件下无数据可导出'); return; }
          var head = ['挂号时间', '挂号单号', '门诊号', '候诊序号', '患者姓名', '患者号', '性别', '科室', '医师', '出诊日期', '时段', '号别', '挂号费', '减免类型', '减免金额', '实收金额', '费别', '支付方式', '医保就诊ID', '医疗类别', '状态', '退号时间', '退号原因', '操作员'];
          var lines = [head.join(',')];
          rows.forEach(function (r) {
            lines.push([
              fmtTime(r.reg_time), r.reg_no, r.ipt_otp_no, r.queue_no, r.patient_name, r.patient_no,
              r.gender || '-', r.dept_name, r.dr_name, r.work_date, timeLabel(r.time_type),
              r.reg_level_name, money(r.reg_fee), discountLabel(r.discount_type), money(r.discount_amount),
              money(r.actual_fee), feeTypeLabel(r.fee_type), payLabel(r.pay_method),
              r.mdtrt_id || '-', medTypeLabel(r.med_type), statusLabel(r.status),
              fmtTime(r.cancel_time), r.cancel_reason || '-', r.operator || '-'
            ].map(vm.csvCell).join(','));
          });
          var blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
          var a = document.createElement('a');
          var u = URL.createObjectURL(blob);
          a.href = u;
          a.download = '挂号明细_' + vm.startDate + '_' + vm.endDate + '.csv';
          document.body.appendChild(a); a.click();
          setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 1000);
          HIS.notifySuccess('已导出 ' + rows.length + ' 条明细');
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      /* ===== 金额列表头排序(数值比较, 字符串字典序会错) ===== */
      sortByNum: function (field) {
        return function (a, b) { return (Number(a[field]) || 0) - (Number(b[field]) || 0); };
      },
      /* ===== 展示格式化 ===== */
      money: money, int: int,
      timeLabel: timeLabel,
      statusLabel: statusLabel,
      /* 状态色与退号页统一: 已挂号蓝/已退号红/已就诊绿 */
      statusTag: function (v) {
        var s = Number(v);
        return s === 1 ? 'primary' : (s === 2 ? 'danger' : 'success');
      },
      feeTypeLabel: feeTypeLabel, payLabel: payLabel, discountLabel: discountLabel,
      fmtTime: fmtTime,
      medTypeLabel: function (v) {
        var m = { '11': '普通门诊', '12': '门诊挂号', '92': '门诊慢特病' };
        return m[v] || v || '-';
      }
    },
    template: [
      '<div>',
      /* A. 查询条件栏 */
      '  <div class="page-card">',
      '    <div class="page-title">挂号明细 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(只读查询: 挂号/退号完整明细数据, 不含退号操作)</span></div>',
      '    <div class="toolbar" style="margin-bottom:0;">',
      '      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '      <el-select v-model="filterDept" placeholder="全部科室" clearable filterable style="width:160px" @change="onDeptChange">',
      '        <el-option v-for="d in deptOptions" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '      </el-select>',
      '      <el-select v-model="filterStaff" placeholder="全部医师" clearable filterable style="width:130px">',
      '        <el-option v-for="s in staffOptions" :key="s.id" :label="s.staffName" :value="s.id"></el-option>',
      '      </el-select>',
      '      <el-select v-model="statusSel" multiple collapse-tags collapse-tags-tooltip placeholder="全部状态" style="width:150px">',
      '        <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '      </el-select>',
      '      <el-select v-model="feeType" placeholder="全部费别" clearable style="width:104px">',
      '        <el-option label="医保" value="insurance"></el-option>',
      '        <el-option label="自费" value="self"></el-option>',
      '      </el-select>',
      '      <el-select v-model="discountType" placeholder="全部减免" clearable style="width:120px">',
      '        <el-option label="70岁免费" value="age70free"></el-option>',
      '        <el-option label="军人" value="military"></el-option>',
      '        <el-option label="残疾" value="disabled"></el-option>',
      '        <el-option label="低保" value="dibao"></el-option>',
      '        <el-option label="其他" value="other"></el-option>',
      '      </el-select>',
      '      <el-select v-model="payMethod" placeholder="全部支付" clearable style="width:104px">',
      '        <el-option label="现金" value="cash"></el-option>',
      '        <el-option label="银行卡" value="card"></el-option>',
      '        <el-option label="微信" value="wechat"></el-option>',
      '        <el-option label="支付宝" value="alipay"></el-option>',
      '        <el-option label="医保" value="insurance"></el-option>',
      '        <el-option label="免收" value="free"></el-option>',
      '      </el-select>',
      '      <el-input v-model="keyword" placeholder="患者姓名/挂号单号/门诊号/患者号" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '      <el-button type="primary" @click="search">查询</el-button>',
      '      <el-button @click="reset">重置</el-button>',
      '      <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '    </div>',
      '    <div v-if="clientFiltered" style="margin-top:10px;color:var(--yb-warning);font-size:12px;">',
      '      已按所选状态/费别/减免类型/支付方式在当前页前端过滤(后端暂不支持多维组合条件; 导出为全量精确过滤; 汇总卡片按日期/科室/医师口径)',
      '    </div>',
      '  </div>',
      /* B. 汇总卡片(4个): 总数(蓝)/有效挂号(绿)/退号(红)/合计实收(金) */
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="6">',
      '      <div class="ds-card tone-blue" v-loading="statsLoading">',
      '        <div class="ds-head"><div class="ds-ico">📋</div><div class="ds-title">查询结果总数</div></div>',
      '        <div class="ds-num" style="color:var(--yb-link);">{{ int(sumTotal) }}</div>',
      '        <div style="color:var(--yb-ink-2);font-size:12px;margin-top:6px;">有效挂号 + 退号(按日期/科室/医师)</div>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="6">',
      '      <div class="ds-card tone-green" v-loading="statsLoading">',
      '        <div class="ds-head"><div class="ds-ico">✅</div><div class="ds-title">有效挂号数</div></div>',
      '        <div class="ds-num" style="color:var(--yb-success);">{{ int(sumReg) }}</div>',
      '        <div style="color:var(--yb-ink-2);font-size:12px;margin-top:6px;">状态为已挂号/已就诊</div>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="6">',
      '      <div class="ds-card tone-red" v-loading="statsLoading">',
      '        <div class="ds-head"><div class="ds-ico">↩</div><div class="ds-title">退号数</div></div>',
      '        <div class="ds-num" style="color:var(--yb-danger);">{{ int(sumCancel) }}</div>',
      '        <div style="color:var(--yb-ink-2);font-size:12px;margin-top:6px;">状态为已退号</div>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="6">',
      '      <div class="ds-card tone-gold" v-loading="statsLoading">',
      '        <div class="ds-head"><div class="ds-ico">💰</div><div class="ds-title">合计实收金额</div></div>',
      '        <div class="ds-num" style="color:var(--yb-gold);">¥ {{ money(sumFee) }}</div>',
      '        <div style="color:var(--yb-ink-2);font-size:12px;margin-top:6px;">SUM(actual_fee)</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      /* C. 明细表格(24列, 挂号时间/挂号费/实收金额可排序) */
      '  <div class="page-card">',
      '    <div class="toolbar" style="margin-bottom:10px;">',
      '      <span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">明细列表</span>',
      '      <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ total }} 条 · 挂号时间/挂号费/实收金额可点击表头排序</span>',
      '      <span style="margin-left:auto;"></span>',
      '      <el-button type="success" plain size="small" :loading="exporting" @click="doExport">导出Excel</el-button>',
      '    </div>',
      '    <el-table :data="list" v-loading="loading" border stripe size="small">',
      '      <el-table-column type="index" label="序号" width="56" :index="seqNo"></el-table-column>',
      '      <el-table-column label="挂号时间" width="160" sortable prop="reg_time"><template #default="s">{{ fmtTime(s.row.reg_time) }}</template></el-table-column>',
      '      <el-table-column prop="reg_no" label="挂号单号" width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="ipt_otp_no" label="门诊号" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="queue_no" label="候诊序号" width="92"></el-table-column>',
      '      <el-table-column prop="patient_name" label="患者姓名" width="80"></el-table-column>',
      '      <el-table-column prop="patient_no" label="患者号" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="性别" width="50"><template #default="s">{{ s.row.gender || "-" }}</template></el-table-column>',
      '      <el-table-column prop="dept_name" label="科室" width="100" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="dr_name" label="医师" width="80"></el-table-column>',
      '      <el-table-column prop="work_date" label="出诊日期" width="100"></el-table-column>',
      '      <el-table-column label="时段" width="60"><template #default="s">{{ timeLabel(s.row.time_type) }}</template></el-table-column>',
      '      <el-table-column prop="reg_level_name" label="号别" width="90" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="挂号费" width="80" align="right" sortable :sort-method="sortByNum(\'reg_fee\')"><template #default="s">{{ money(s.row.reg_fee) }}</template></el-table-column>',
      '      <el-table-column label="减免类型" width="110"><template #default="s">{{ discountLabel(s.row.discount_type) }}</template></el-table-column>',
      '      <el-table-column label="减免金额" width="84" align="right"><template #default="s"><span v-if="Number(s.row.discount_amount) > 0" style="color:var(--yb-danger);">{{ money(s.row.discount_amount) }}</span><span v-else>-</span></template></el-table-column>',
      '      <el-table-column label="实收金额" width="84" align="right" sortable :sort-method="sortByNum(\'actual_fee\')"><template #default="s"><b style="color:var(--yb-success);">{{ money(s.row.actual_fee) }}</b></template></el-table-column>',
      '      <el-table-column label="费别" width="66"><template #default="s">{{ feeTypeLabel(s.row.fee_type) }}</template></el-table-column>',
      '      <el-table-column label="支付方式" width="84"><template #default="s">{{ payLabel(s.row.pay_method) }}</template></el-table-column>',
      '      <el-table-column prop="mdtrt_id" label="医保就诊ID" width="145" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="医疗类别" width="90"><template #default="s">{{ medTypeLabel(s.row.med_type) }}</template></el-table-column>',
      '      <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '      <el-table-column label="退号时间" width="160"><template #default="s">{{ fmtTime(s.row.cancel_time) }}</template></el-table-column>',
      '      <el-table-column prop="cancel_reason" label="退号原因" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="operator" label="操作员" width="90"></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '      :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page"',
      '      @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
