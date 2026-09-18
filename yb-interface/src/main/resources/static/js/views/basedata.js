/* 基础数据: 科室 / 职工 / 排班号源 / 收费项目对照 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var DEPT_TYPES = ['临床', '医技', '行政'];
  var STAFF_TYPES = ['医师', '护士', '药师', '技师', '管理'];
  var TIME_TYPES = [{ v: 'am', l: '上午' }, { v: 'pm', l: '下午' }, { v: 'night', l: '晚间' }];
  var ITEM_TYPES = ['药品', '诊疗', '耗材', '其他'];
  var CHRG_TYPES = [{ v: '01', l: '01-药品' }, { v: '02', l: '02-诊疗' }, { v: '03', l: '03-耗材' }];
  var CHRG_LV = [{ v: '01', l: '01-甲类' }, { v: '02', l: '02-乙类' }, { v: '03', l: '03-丙类' }];

  /* 去除审计字段, 避免回填干扰 */
  function clean(f) {
    delete f.createTime; delete f.updateTime; delete f.createBy; delete f.updateBy; delete f.deleted;
    return f;
  }
  function timeLabel(v) {
    for (var i = 0; i < TIME_TYPES.length; i++) { if (TIME_TYPES[i].v === v) { return TIME_TYPES[i].l; } }
    return v || '-';
  }

  /* ================= 科室管理 ================= */
  HIS.views.DeptManage = {
    data: function () {
      return { loading: false, list: [], deptTypes: DEPT_TYPES, dlg: false, editing: false, form: this.empty() };
    },
    created: function () { this.load(); },
    methods: {
      empty: function () {
        return { id: null, deptCode: '', deptName: '', deptType: '临床', deptCaty: '', ybDeptCode: '', phone: '', locDesc: '', sortNo: 0, status: 1, memo: '' };
      },
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/dept/list').then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      add: function () { this.editing = false; this.form = this.empty(); this.dlg = true; },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.dlg = true; },
      submit: function () {
        var vm = this;
        if (!vm.form.deptCode || !vm.form.deptName) { ElementPlus.ElMessage.warning('科室编码与名称必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/dept', vm.form) : HIS.post('/api/his/dept', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        HIS.del('/api/his/dept/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">科室管理</div>',
      '  <div class="toolbar"><el-button type="primary" @click="add">新增科室</el-button><el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 个科室</span></div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="deptCode" label="科室编码" width="100"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室名称" width="140"></el-table-column>',
      '    <el-table-column prop="deptType" label="类型" width="80"></el-table-column>',
      '    <el-table-column prop="deptCaty" label="医保科别" width="100"></el-table-column>',
      '    <el-table-column prop="phone" label="电话" width="130"></el-table-column>',
      '    <el-table-column prop="sortNo" label="排序" width="70"></el-table-column>',
      '    <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" min-width="130"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm title="确认删除该科室？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑科室\':\'新增科室\'" width="520px">',
      '    <el-form :model="form" label-width="100px" size="default">',
      '      <el-form-item label="科室编码"><el-input v-model="form.deptCode"></el-input></el-form-item>',
      '      <el-form-item label="科室名称"><el-input v-model="form.deptName"></el-input></el-form-item>',
      '      <el-form-item label="科室类型"><el-select v-model="form.deptType" style="width:100%"><el-option v-for="t in deptTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item>',
      '      <el-form-item label="医保科别"><el-input v-model="form.deptCaty" placeholder="用于2201 caty"></el-input></el-form-item>',
      '      <el-form-item label="医保科室编码"><el-input v-model="form.ybDeptCode"></el-input></el-form-item>',
      '      <el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item>',
      '      <el-form-item label="位置描述"><el-input v-model="form.locDesc"></el-input></el-form-item>',
      '      <el-form-item label="排序号"><el-input v-model.number="form.sortNo" type="number"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.memo" type="textarea"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 职工管理 ================= */
  HIS.views.StaffManage = {
    data: function () {
      return {
        loading: false, list: [], depts: [], staffTypes: STAFF_TYPES,
        filterDept: null, filterType: '', keyword: '',
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.loadDepts(); this.load(); },
    methods: {
      empty: function () {
        return { id: null, staffNo: '', staffName: '', staffType: '医师', gender: '男', titleName: '', deptId: null, atddrNo: '', diseDorNo: '', idCard: '', phone: '', canRegister: 0, regFee: 0, sortNo: 0, status: 1, memo: '' };
      },
      loadDepts: function () { var vm = this; HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError); },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/staff/list?1=1';
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterType) { q += '&staffType=' + encodeURIComponent(vm.filterType); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return '-'; },
      add: function () { this.editing = false; this.form = this.empty(); this.dlg = true; },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.dlg = true; },
      submit: function () {
        var vm = this;
        if (!vm.form.staffNo || !vm.form.staffName) { ElementPlus.ElMessage.warning('工号与姓名必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/staff', vm.form) : HIS.post('/api/his/staff', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError);
      },
      del: function (row) { var vm = this; HIS.del('/api/his/staff/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError); }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">职工管理</div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterDept" placeholder="全部科室" clearable style="width:150px" @change="load"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '    <el-select v-model="filterType" placeholder="全部类别" clearable style="width:130px" @change="load"><el-option v-for="t in staffTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="工号/姓名" clearable style="width:160px" @keyup.enter="load"></el-input>',
      '    <el-button @click="load">查询</el-button>',
      '    <el-button type="primary" @click="add">新增职工</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 人</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="staffNo" label="工号" width="90"></el-table-column>',
      '    <el-table-column prop="staffName" label="姓名" width="100"></el-table-column>',
      '    <el-table-column prop="staffType" label="类别" width="80"></el-table-column>',
      '    <el-table-column prop="titleName" label="职称" width="110"></el-table-column>',
      '    <el-table-column label="科室" width="110"><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column prop="atddrNo" label="主治医师编码" width="120"></el-table-column>',
      '    <el-table-column label="可挂号" width="80"><template #default="s"><el-tag size="small" :type="s.row.canRegister===1?\'success\':\'info\'">{{ s.row.canRegister===1?"是":"否" }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="regFee" label="挂号费" width="80"></el-table-column>',
      '    <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"在职":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" min-width="130"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm title="确认删除该职工？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑职工\':\'新增职工\'" width="560px">',
      '    <el-form :model="form" label-width="110px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="工号"><el-input v-model="form.staffNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.staffName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="类别"><el-select v-model="form.staffType" style="width:100%"><el-option v-for="t in staffTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="性别"><el-select v-model="form.gender" style="width:100%"><el-option label="男" value="男"></el-option><el-option label="女" value="女"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="职称"><el-input v-model="form.titleName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="所属科室"><el-select v-model="form.deptId" clearable style="width:100%"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="主治医师编码"><el-input v-model="form.atddrNo" placeholder="医保 atddr_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="诊断医师编码"><el-input v-model="form.diseDorNo" placeholder="医保 dise_dor_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证号"><el-input v-model="form.idCard"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="默认挂号费"><el-input v-model.number="form.regFee" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="可挂号"><el-switch v-model="form.canRegister" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="排序号"><el-input v-model.number="form.sortNo" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="在职" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 排班号源 ================= */
  HIS.views.ScheduleManage = {
    data: function () {
      return {
        loading: false, list: [], depts: [], staffs: [], timeTypes: TIME_TYPES,
        filterDept: null, range: [], dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.loadRefs(); this.load(); },
    methods: {
      empty: function () {
        return { id: null, deptId: null, staffId: null, workDate: '', timeType: 'am', regLevelCode: '01', regLevelName: '普通号', regFee: 10, totalNum: 30, leftNum: 30, status: 1 };
      },
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError);
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师')).then(function (d) { vm.staffs = d || []; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/schedule/list?1=1';
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.range && vm.range.length === 2) { q += '&from=' + vm.range[0] + '&to=' + vm.range[1]; }
        HIS.get(q).then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return '-'; },
      staffName: function (id) { for (var i = 0; i < this.staffs.length; i++) { if (this.staffs[i].id === id) { return this.staffs[i].staffName; } } return '-'; },
      onStaffChange: function (id) {
        for (var i = 0; i < this.staffs.length; i++) {
          if (this.staffs[i].id === id) { this.form.deptId = this.staffs[i].deptId; if (this.form.regFee == null) { this.form.regFee = this.staffs[i].regFee; } break; }
        }
      },
      add: function () { this.editing = false; this.form = this.empty(); this.dlg = true; },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.dlg = true; },
      submit: function () {
        var vm = this;
        if (!vm.form.staffId || !vm.form.workDate) { ElementPlus.ElMessage.warning('医师与出诊日期必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/schedule', vm.form) : HIS.post('/api/his/schedule', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError);
      },
      del: function (row) { var vm = this; HIS.del('/api/his/schedule/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError); },
      timeLabel: timeLabel
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">排班号源</div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterDept" placeholder="全部科室" clearable style="width:150px" @change="load"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '    <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px" @change="load"></el-date-picker>',
      '    <el-button @click="load">查询</el-button>',
      '    <el-button type="primary" @click="add">新增排班</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="workDate" label="出诊日期" width="120"></el-table-column>',
      '    <el-table-column label="时段" width="80"><template #default="s">{{ timeLabel(s.row.timeType) }}</template></el-table-column>',
      '    <el-table-column label="科室" width="110"><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column label="医师" width="100"><template #default="s">{{ staffName(s.row.staffId) }}</template></el-table-column>',
      '    <el-table-column prop="regLevelName" label="号别" width="100"></el-table-column>',
      '    <el-table-column prop="regFee" label="挂号费" width="80"></el-table-column>',
      '    <el-table-column label="号源(余/总)" width="110"><template #default="s">{{ s.row.leftNum }} / {{ s.row.totalNum }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"开放":"停诊" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" min-width="130"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm title="确认删除该排班？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑排班\':\'新增排班\'" width="520px">',
      '    <el-form :model="form" label-width="100px">',
      '      <el-form-item label="出诊医师"><el-select v-model="form.staffId" style="width:100%" @change="onStaffChange"><el-option v-for="s in staffs" :key="s.id" :label="s.staffName+\'(\'+s.staffNo+\')\'" :value="s.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="科室"><el-select v-model="form.deptId" style="width:100%"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="出诊日期"><el-date-picker v-model="form.workDate" type="date" value-format="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item>',
      '      <el-form-item label="时段"><el-select v-model="form.timeType" style="width:100%"><el-option v-for="t in timeTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="号别名称"><el-input v-model="form.regLevelName"></el-input></el-form-item>',
      '      <el-form-item label="挂号费"><el-input v-model.number="form.regFee" type="number"></el-input></el-form-item>',
      '      <el-form-item label="总号源"><el-input v-model.number="form.totalNum" type="number"></el-input></el-form-item>',
      '      <el-form-item label="剩余号源"><el-input v-model.number="form.leftNum" type="number"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="开放" inactive-text="停诊"></el-switch></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 收费项目对照 ================= */
  HIS.views.ChargeItemManage = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 15, keyword: '', itemType: '',
        itemTypes: ITEM_TYPES, chrgTypes: CHRG_TYPES, chrgLv: CHRG_LV,
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.load(); },
    methods: {
      empty: function () {
        return { id: null, itemCode: '', itemName: '', itemType: '药品', itemCat: '', spec: '', unit: '', price: 0, medListCodg: '', medinsListCodg: '', medChrgitmType: '01', chrgitmLv: '01', selfpayProp: 0, status: 1, memo: '' };
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/charge-item/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.itemType) { q += '&itemType=' + encodeURIComponent(vm.itemType); }
        HIS.get(q).then(function (d) { vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      add: function () { this.editing = false; this.form = this.empty(); this.dlg = true; },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.dlg = true; },
      submit: function () {
        var vm = this;
        if (!vm.form.itemCode || !vm.form.itemName) { ElementPlus.ElMessage.warning('项目编码与名称必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/charge-item', vm.form) : HIS.post('/api/his/charge-item', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError);
      },
      del: function (row) { var vm = this; HIS.del('/api/his/charge-item/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError); }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">收费项目对照(本院目录 ↔ 医保目录)</div>',
      '  <div class="toolbar">',
      '    <el-select v-model="itemType" placeholder="全部大类" clearable style="width:130px" @change="search"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="名称/编码/医保码" clearable style="width:200px" @keyup.enter="search"></el-input>',
      '    <el-button @click="search">查询</el-button>',
      '    <el-button type="primary" @click="add">新增项目</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 项</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="itemCode" label="院内编码" width="100"></el-table-column>',
      '    <el-table-column prop="itemName" label="项目名称" min-width="150"></el-table-column>',
      '    <el-table-column prop="itemType" label="大类" width="70"></el-table-column>',
      '    <el-table-column prop="spec" label="规格" width="120"></el-table-column>',
      '    <el-table-column prop="unit" label="单位" width="60"></el-table-column>',
      '    <el-table-column prop="price" label="单价" width="80"></el-table-column>',
      '    <el-table-column label="医保目录编码" width="180"><template #default="s">',
      '      <span v-if="s.row.medListCodg">{{ s.row.medListCodg }}</span><el-tag v-else size="small" type="warning">未对照</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column prop="chrgitmLv" label="等级" width="60"></el-table-column>',
      '    <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑/对照</el-button>',
      '      <el-popconfirm title="确认删除？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="prev, pager, next, total" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑收费项目\':\'新增收费项目\'" width="600px">',
      '    <el-form :model="form" label-width="120px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="院内编码"><el-input v-model="form.itemCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="项目名称"><el-input v-model="form.itemName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="项目大类"><el-select v-model="form.itemType" style="width:100%"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="细分类别"><el-input v-model="form.itemCat"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规格"><el-input v-model="form.spec"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="单位"><el-input v-model="form.unit"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="单价"><el-input v-model.number="form.price" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自付比例"><el-input v-model.number="form.selfpayProp" type="number" placeholder="0-1"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="医保目录编码"><el-input v-model="form.medListCodg" placeholder="med_list_codg(可在医保字典页查阅)"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="医保机构编码"><el-input v-model="form.medinsListCodg" placeholder="medins_list_codg"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="收费项目类别"><el-select v-model="form.medChrgitmType" style="width:100%"><el-option v-for="t in chrgTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="收费项目等级"><el-select v-model="form.chrgitmLv" style="width:100%"><el-option v-for="t in chrgLv" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
