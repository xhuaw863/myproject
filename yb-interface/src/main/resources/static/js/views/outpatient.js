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
      '  <div class="page-title">患者建档 / 查询</div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="姓名/患者号/身份证/医保号/电话" clearable style="width:280px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="add">新增建档</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 人</span>',
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
      '        <span style="color:#909399;font-size:12px;">共 {{ insuList.length }} 条参保记录; 读卡按来源"医保读卡(1101)"覆盖保存, 重复读卡不产生重复</span>',
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
    computed: {
      /* 挂号科室下拉仅列科室级节点(deptLevel=2): 诊室(第3层)不作为科室可选;
         depts 仍保留全量供 deptName(id) 解析历史号源的诊室科室 */
      deptFilterOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      }
    },
    methods: {
      loadRefs: function () {
        var vm = this;
        /* 挂号科室仅本机构已开诊的门诊科室(后端硬限定登录机构) */
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        var orgId = HIS.currentOrgId();
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false' + (orgId ? ('&orgId=' + orgId) : '')).then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
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
              /* 后端已回带科室/医师名: 历史脏数据(非门诊科室排班/跨机构医师)不在本地 refs 时兜底显示, 避免列展示"-" */
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
              room: s.room
            };
          }).filter(function (s) { return s.status === 1; });
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
      '        {{ selectedPatient.name }} | {{ selectedPatient.genderName || selectedPatient.gender }} | {{ selectedPatient.age||"-" }}岁 | 患者号 {{ selectedPatient.patientNo }} | 医保 {{ selectedPatient.psnNo||"无" }}',
      '      </el-tag>',
      '    </div>',
      '    <div class="toolbar">',
      '      <el-input v-model="pKeyword" placeholder="姓名/身份证/患者号/电话" clearable style="width:280px" @keyup.enter="searchPatients"></el-input>',
      '      <el-button type="primary" @click="searchPatients">检索患者</el-button>',
      '      <span style="color:#909399;font-size:13px;">未建档请先到“患者建档”页新增</span>',
      '    </div>',
      '    <el-table :data="patients" v-loading="pLoading" border stripe size="small" height="200" highlight-current-row @current-change="selectPatient">',
      '      <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '      <el-table-column prop="patientNo" label="患者号" width="170"></el-table-column>',
      '      <el-table-column prop="name" label="姓名" width="90"></el-table-column>',
      '      <el-table-column label="性别" width="55"><template #default="s">{{ s.row.genderName || s.row.gender }}</template></el-table-column>',
      '      <el-table-column prop="age" label="年龄" width="55"></el-table-column>',
      '      <el-table-column prop="idCard" label="身份证号" width="170"></el-table-column>',
      '      <el-table-column prop="psnNo" label="医保编号" width="130"></el-table-column>',
      '      <el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" @click="selectPatient(s.row)">选择</el-button></template></el-table-column>',
      '    </el-table>',
      /* ---- 第二步: 选择号源 ---- */
      '    <el-divider content-position="left">第二步 · 选择号源</el-divider>',
      '    <div class="toolbar">',
      '      <el-select v-model="filterDept" placeholder="全部科室" clearable style="width:160px" @change="loadSchedules"><el-option v-for="d in deptFilterOptions" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '      <el-date-picker v-model="filterDate" type="date" value-format="YYYY-MM-DD" placeholder="出诊日期" style="width:160px" @change="loadSchedules"></el-date-picker>',
      '      <el-button @click="loadSchedules">查询号源</el-button>',
      '      <el-select v-model="medType" style="width:140px"><el-option label="11-普通门诊" value="11"></el-option><el-option label="92-门诊慢特病" value="92"></el-option></el-select>',
      '      <el-button type="primary" :loading="submitting" @click="doRegister">确认挂号</el-button>',
      '    </div>',
      '    <el-table :data="schedules" v-loading="sLoading" border stripe size="small" height="240" highlight-current-row @current-change="chooseSchedule">',
      '      <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '      <el-table-column label="科室" width="120"><template #default="s">{{ s.row.deptName || deptName(s.row.deptId) }}</template></el-table-column>',
      '      <el-table-column label="医师" width="100"><template #default="s">{{ s.row.staffName || staffName(s.row.staffId) }}</template></el-table-column>',
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
        loading: false, list: [], total: 0, page: 1, size: 20,
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
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
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
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
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
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };
})();
