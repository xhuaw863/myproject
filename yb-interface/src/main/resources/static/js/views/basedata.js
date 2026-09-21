/* 基础数据: 科室 / 职工 / 排班号源 / 收费项目对照 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var DEPT_TYPES = ['临床', '医技', '行政'];
  var DEPT_CATEGORIES = ['门诊科室', '住院科室', '病区护理', '医技科室', '行政后勤'];
  var DEPT_LEVELS = [{ v: 1, l: '大类' }, { v: 2, l: '科室' }, { v: 3, l: '窗口/诊室' }];
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

  /* ================= 科室管理(层级树: 大类→科室→窗口/诊室) ================= */
  HIS.views.DeptManage = {
    data: function () {
      return {
        loading: false, tree: [], nodeMap: {},
        deptTypes: DEPT_TYPES, deptCategories: DEPT_CATEGORIES, deptLevels: DEPT_LEVELS,
        orgs: [], filterOrg: null, catyOpts: [], catyMap: {},
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.loadOrgs(); this.loadCaty(); this.load(); },
    computed: {
      /* 上级科室下拉: 扁平化缩进展示层级; 编辑时排除自身及子树防环 */
      parentOptions: function () {
        var vm = this;
        var out = [{ id: 0, label: '（顶级：科室大类）' }];
        var excludeId = vm.editing && vm.form.id ? vm.form.id : null;
        (function walk(nodes, depth) {
          (nodes || []).forEach(function (n) {
            if (excludeId && n.id === excludeId) { return; }
            var pad = '';
            for (var i = 0; i < depth; i++) { pad += '　'; }
            out.push({ id: n.id, label: pad + (depth > 0 ? '└ ' : '') + n.deptName });
            walk(n.children, depth + 1);
          });
        })(vm.tree, 0);
        return out;
      }
    },
    methods: {
      empty: function () {
        return { id: null, orgId: null, parentId: 0, deptCategory: '', deptLevel: 1, deptCode: '', deptName: '', deptType: '临床', deptCaty: '', ybDeptCode: '', phone: '', locDesc: '', sortNo: 0, status: 1, memo: '' };
      },
      loadOrgs: function () { var vm = this; HIS.get('/api/sys/org/tree').then(function (d) { vm.orgs = HIS.flattenOrgs(d || []); }).catch(function () { vm.orgs = []; }); },
      loadCaty: function () { var vm = this; HIS.stdValues('cv_code', 'caty').then(function (l) { vm.catyOpts = l || []; vm.catyMap = HIS.dictMap(l); }).catch(HIS.notifyError); },
      orgName: function (id) { for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } } return '-'; },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/dept/tree?1=1';
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg; }
        HIS.get(q).then(function (d) { vm.tree = d || []; vm.rebuildMap(); }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      rebuildMap: function () {
        var m = {};
        (function walk(nodes) { (nodes || []).forEach(function (n) { m[n.id] = n; walk(n.children); }); })(this.tree);
        this.nodeMap = m;
      },
      levelLabel: function (lv) { for (var i = 0; i < DEPT_LEVELS.length; i++) { if (DEPT_LEVELS[i].v === lv) { return DEPT_LEVELS[i].l; } } return lv || '-'; },
      levelTagType: function (lv) { return lv === 1 ? 'danger' : (lv === 2 ? '' : (lv === 3 ? 'success' : 'info')); },
      add: function () { this.editing = false; this.form = this.empty(); this.dlg = true; },
      /* 快捷新增下级: 继承上级机构/大类, 层级+1(封顶3) */
      addChild: function (row) {
        this.editing = false;
        var f = this.empty();
        f.parentId = row.id; f.orgId = row.orgId; f.deptCategory = row.deptCategory || '';
        f.deptLevel = (row.deptLevel || 1) + 1; if (f.deptLevel > 3) { f.deptLevel = 3; }
        this.form = f; this.dlg = true;
      },
      edit: function (row) {
        this.editing = true; this.form = clean(Object.assign(this.empty(), row));
        delete this.form.children;
        if (this.form.parentId == null) { this.form.parentId = 0; }
        this.dlg = true;
      },
      /* 选上级时自动带出大类与层级(顶级=1大类) */
      onParentChange: function (pid) {
        if (!pid || pid === 0) { this.form.deptLevel = 1; return; }
        var p = this.nodeMap[pid];
        if (p) {
          if (p.deptCategory) { this.form.deptCategory = p.deptCategory; }
          this.form.deptLevel = (p.deptLevel || 1) + 1; if (this.form.deptLevel > 3) { this.form.deptLevel = 3; }
          if (!this.form.orgId && p.orgId) { this.form.orgId = p.orgId; }
        }
      },
      submit: function () {
        var vm = this;
        if (!vm.form.deptCode || !vm.form.deptName) { ElementPlus.ElMessage.warning('科室编码与名称必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/dept', vm.form) : HIS.post('/api/his/dept', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        if (row.children && row.children.length) { ElementPlus.ElMessage.warning('该科室存在下级, 请先删除或移动下级科室'); return; }
        HIS.del('/api/his/dept/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">科室管理</div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterOrg" placeholder="全部机构" clearable filterable style="width:200px" @change="load"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select>',
      '    <el-button type="primary" @click="add">新增大类/科室</el-button><el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">支持 大类→科室→窗口/诊室 三级层级维护</span></div>',
      '  <el-table :data="tree" v-loading="loading" border row-key="id" :tree-props="{children:\'children\'}" default-expand-all size="small">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室名称" min-width="200"></el-table-column>',
      '    <el-table-column prop="deptCode" label="编码" width="90"></el-table-column>',
      '    <el-table-column label="层级" width="90"><template #default="s"><el-tag size="small" :type="levelTagType(s.row.deptLevel)">{{ levelLabel(s.row.deptLevel) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="deptCategory" label="所属大类" width="110"></el-table-column>',
      '    <el-table-column label="所属机构" min-width="140" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column prop="deptType" label="类型" width="70"></el-table-column>',
      '    <el-table-column label="医保科别" width="130" show-overflow-tooltip><template #default="s">{{ s.row.deptCatyName || catyMap[s.row.deptCaty] || s.row.deptCaty || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" min-width="190" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-button link type="success" @click="addChild(s.row)">新增下级</el-button>',
      '      <el-popconfirm title="确认删除该科室？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑科室\':\'新增科室\'" width="560px">',
      '    <el-form :model="form" label-width="100px" size="default">',
      '      <el-form-item label="上级科室"><el-select v-model="form.parentId" style="width:100%" @change="onParentChange"><el-option v-for="o in parentOptions" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="科室大类"><el-select v-model="form.deptCategory" clearable style="width:100%" placeholder="门诊/住院/病区护理/医技/行政后勤"><el-option v-for="c in deptCategories" :key="c" :label="c" :value="c"></el-option></el-select></el-form-item>',
      '      <el-form-item label="层级"><el-select v-model="form.deptLevel" style="width:100%"><el-option v-for="l in deptLevels" :key="l.v" :label="l.v+\'-\'+l.l" :value="l.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="所属机构"><el-select v-model="form.orgId" clearable filterable style="width:100%" placeholder="医共体内机构(可选)"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="科室编码"><el-input v-model="form.deptCode"></el-input></el-form-item>',
      '      <el-form-item label="科室名称"><el-input v-model="form.deptName"></el-input></el-form-item>',
      '      <el-form-item label="科室类型"><el-select v-model="form.deptType" style="width:100%"><el-option v-for="t in deptTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item>',
      '      <el-form-item label="医保科别"><el-select v-model="form.deptCaty" filterable clearable style="width:100%" placeholder="选择医保字典 caty(2201/2203必填)"><el-option v-for="o in catyOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item>',
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
        loading: false, list: [], depts: [], orgs: [], staffTypes: STAFF_TYPES,
        gendOpts: [], gendMap: {}, titleOpts: [], titleMap: {},
        pracCateOpts: [], pracCateMap: {},
        abxOpts: [], abxMap: {},
        surgeryOpts: [], surgeryMap: {},
        activeTab: 'basic',
        filterOrg: null, filterDept: null, filterType: '', keyword: '',
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () { this.loadDicts(); this.loadDepts(); this.loadOrgs(); this.load(); },
    computed: {
      /* 表单科室下拉按所选机构联动: 选定机构时只列该机构下科室 */
      formDepts: function () {
        var f = this.form;
        if (!f.orgId) { return this.depts; }
        return this.depts.filter(function (d) { return d.orgId === f.orgId; });
      },
      /* 工具栏科室筛选按所选机构联动 */
      filterDepts: function () {
        var oid = this.filterOrg;
        if (!oid) { return this.depts; }
        return this.depts.filter(function (d) { return d.orgId === oid; });
      }
    },
    methods: {
      loadDicts: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'gend').then(function (l) { vm.gendOpts = l || []; vm.gendMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('wst364', 'CV08.30.005').then(function (l) { vm.titleOpts = l || []; vm.titleMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('whvalue', 'CT98.00.024').then(function (l) { vm.pracCateOpts = l || []; vm.pracCateMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        /* 抗菌药物处方权级别字典(hbvalue:HBCV08.50.029), 排除"非抗菌药物"(0) */
        HIS.stdValues('hbvalue', 'HBCV08.50.029').then(function (l) {
          var arr = (l || []).filter(function (o) { return String(o.code) !== '0'; });
          vm.abxOpts = arr; vm.abxMap = HIS.dictMap(arr);
        }).catch(HIS.notifyError);
        /* 手术级别权限字典(cv_code:oprn_lv_code: 1一级/2二级/3三级/4四级/5其他), 排除"其他"(5) */
        HIS.stdValues('cv_code', 'oprn_lv_code').then(function (l) {
          var arr = (l || []).filter(function (o) { return String(o.code) !== '5'; });
          vm.surgeryOpts = arr; vm.surgeryMap = HIS.dictMap(arr);
        }).catch(HIS.notifyError);
      },
      empty: function () {
        return { id: null, staffNo: '', staffName: '', staffType: '医师', gender: '1', titleCode: '', titleName: '', deptId: null, orgId: null, atddrNo: '', diseDorNo: '', idCard: '', birthDate: '', medInsurCode: '', pracCate: '', drQualCertNo: '', pracCertNo: '', phone: '', canRegister: 0, regFee: 0, sortNo: 0, status: 1, memo: '', avatarUrl: '', signImgUrl: '', rxRight: 0, narcoticRight: 0, psych1Right: 0, psych2Right: 0, antibioticLevel: '', surgeryLevel: '', rxAuthOrg: '', rxAuthNo: '', rxAuthDate: '', rxValidUntil: '' };
      },
      loadDepts: function () { var vm = this; HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError); },
      onOrgChange: function () {
        var vm = this;
        if (vm.form.deptId && vm.formDepts.every(function (d) { return d.id !== vm.form.deptId; })) { vm.form.deptId = null; }
      },
      loadOrgs: function () { var vm = this; HIS.get('/api/sys/org/tree').then(function (d) { vm.orgs = HIS.flattenOrgs(d || []); }).catch(function () { vm.orgs = []; }); },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/staff/list?1=1';
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg; }
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.filterType) { q += '&staffType=' + encodeURIComponent(vm.filterType); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return '-'; },
      orgName: function (id) { for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } } return '-'; },
      add: function () { this.editing = false; this.form = this.empty(); this.activeTab = 'basic'; this.dlg = true; },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.activeTab = 'basic'; this.dlg = true; },
      submit: function () {
        var vm = this;
        if (!vm.form.staffNo || !vm.form.staffName) { ElementPlus.ElMessage.warning('工号与姓名必填'); return; }
        var p = vm.editing ? HIS.put('/api/his/staff', vm.form) : HIS.post('/api/his/staff', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError);
      },
      del: function (row) { var vm = this; HIS.del('/api/his/staff/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError); },
      /* 头像上传(本地文件服务 /api/file/upload, biz=staff_avatar) */
      uploadAvatar: function (opt) {
        var vm = this;
        HIS.upload(opt.file, 'staff_avatar').then(function (d) { vm.form.avatarUrl = d.url; HIS.notifySuccess('头像已上传'); }).catch(HIS.notifyError);
      },
      /* 签名图片上传(用于电子处方/医学文档签名, biz=staff_sign) */
      uploadSign: function (opt) {
        var vm = this;
        HIS.upload(opt.file, 'staff_sign').then(function (d) { vm.form.signImgUrl = d.url; HIS.notifySuccess('签名图片已上传'); }).catch(HIS.notifyError);
      },
      /* 总处方权关闭时联动清空专项权(前端镜像服务端守护, 避免无效录入) */
      onRxRightChange: function (v) {
        if (v === 0) {
          this.form.narcoticRight = 0; this.form.psych1Right = 0; this.form.psych2Right = 0; this.form.antibioticLevel = '';
        }
      },
      /* 专项权(麻醉/精一/精二/抗菌)开启时自动具备总处方权 */
      ensureRxRight: function () {
        var f = this.form;
        if (f.narcoticRight === 1 || f.psych1Right === 1 || f.psych2Right === 1 || f.antibioticLevel) { f.rxRight = 1; }
      },
      /* 按职称建议抗菌药物处方权级别(《抗菌药物临床应用管理办法》分级授权): 高级→三级(特殊使用) 中级→二级(限制) 初级→一级(非限制); 可手工覆盖 */
      suggestAbxLevel: function () {
        var tc = String(this.form.titleCode || '');
        var lv = '';
        if (tc === '1' || tc === '2') { lv = '13'; }
        else if (tc === '3') { lv = '12'; }
        else if (tc === '4') { lv = '11'; }
        if (!lv) { ElementPlus.ElMessage.warning('请先选择职称(初级及以上方可授予抗菌药物处方权)'); return; }
        this.form.antibioticLevel = lv; this.form.rxRight = 1;
        ElementPlus.ElMessage.success('已按职称建议抗菌级别: ' + (this.abxMap[lv] || lv) + '(可手工调整)');
      },
      /* 按职称建议手术级别权限(《医疗技术临床应用管理办法》手术分级授权): 正高→四级 副高→三级 中级→二级 初级→一级; 可手工覆盖 */
      suggestSurgeryLevel: function () {
        var tc = String(this.form.titleCode || '');
        var lv = '';
        if (tc === '1') { lv = '4'; }
        else if (tc === '2') { lv = '3'; }
        else if (tc === '3') { lv = '2'; }
        else if (tc === '4') { lv = '1'; }
        if (!lv) { ElementPlus.ElMessage.warning('请先选择职称(初级及以上方可授予手术级别权限)'); return; }
        this.form.surgeryLevel = lv;
        ElementPlus.ElMessage.success('已按职称建议手术级别: ' + (this.surgeryMap[lv] || lv) + '级(可手工调整)');
      },
      /* 列表页处方权限标签 */
      rxTags: function (row) {
        var a = [];
        if (row.rxRight === 1) { a.push({ t: '处方', c: 'success' }); }
        if (row.narcoticRight === 1) { a.push({ t: '麻醉', c: 'danger' }); }
        if (row.psych1Right === 1) { a.push({ t: '精一', c: 'danger' }); }
        if (row.psych2Right === 1) { a.push({ t: '精二', c: 'warning' }); }
        if (row.antibioticLevel) { a.push({ t: '抗菌' + (this.abxMap[row.antibioticLevel] || row.antibioticLevel), c: 'primary' }); }
        if (row.surgeryLevel) { a.push({ t: '手术' + (this.surgeryMap[row.surgeryLevel] || row.surgeryLevel) + '级', c: 'success' }); }
        return a;
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">职工管理</div>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterOrg" placeholder="全部机构" clearable filterable style="width:200px" @change="filterDept=null; load()"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select>',
      '    <el-select v-model="filterDept" placeholder="全部科室" clearable style="width:150px" @change="load"><el-option v-for="d in filterDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '    <el-select v-model="filterType" placeholder="全部类别" clearable style="width:130px" @change="load"><el-option v-for="t in staffTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="工号/姓名" clearable style="width:160px" @keyup.enter="load"></el-input>',
      '    <el-button @click="load">查询</el-button>',
      '    <el-button type="primary" @click="add">新增职工</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 人</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="staffNo" label="工号" width="90"></el-table-column>',
      '    <el-table-column prop="staffName" label="姓名" width="100"></el-table-column>',
      '    <el-table-column prop="staffType" label="类别" width="80"></el-table-column>',
      '    <el-table-column prop="titleName" label="职称" width="110"></el-table-column>',
      '    <el-table-column label="科室" width="110"><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column label="归属机构" min-width="150" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column prop="atddrNo" label="主治医师编码" width="120"></el-table-column>',
      '    <el-table-column label="可挂号" width="80"><template #default="s"><el-tag size="small" :type="s.row.canRegister===1?\'success\':\'info\'">{{ s.row.canRegister===1?"是":"否" }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="regFee" label="挂号费" width="80"></el-table-column>',
      '    <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"在职":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="处方权限" min-width="200"><template #default="s"><template v-if="rxTags(s.row).length"><el-tag v-for="(g,i) in rxTags(s.row)" :key="i" size="small" :type="g.c" style="margin-right:4px;">{{ g.t }}</el-tag></template><span v-else style="color:#c0c4cc;">-</span></template></el-table-column>',
      '    <el-table-column label="操作" min-width="130"><template #default="s">',
      '      <el-button link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm title="确认删除该职工？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑职工\':\'新增职工\'" width="760px" top="6vh">',
      '    <el-tabs v-model="activeTab">',
      '      <el-tab-pane label="基本信息" name="basic">',
      '        <el-form :model="form" label-width="100px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="工号"><el-input v-model="form.staffNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.staffName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="类别"><el-select v-model="form.staffType" style="width:100%"><el-option v-for="t in staffTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="性别"><el-select v-model="form.gender" style="width:100%"><el-option v-for="o in gendOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="职称"><el-select v-model="form.titleCode" style="width:100%" clearable placeholder="专业技术职务类别(CV08.30.005)"><el-option v-for="o in titleOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="所属科室"><el-select v-model="form.deptId" clearable style="width:100%"><el-option v-for="d in formDepts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="归属机构"><el-select v-model="form.orgId" clearable filterable style="width:100%" placeholder="医共体内共享(可选)" @change="onOrgChange"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证号"><el-input v-model="form.idCard"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="头像"><el-upload :show-file-list="false" :http-request="uploadAvatar" accept="image/*"><el-button size="small">上传头像</el-button></el-upload><img v-if="form.avatarUrl" :src="form.avatarUrl" style="height:64px;margin-top:6px;border:1px solid #ebeef5;border-radius:4px;"/></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="签名图片"><el-upload :show-file-list="false" :http-request="uploadSign" accept="image/*"><el-button size="small">上传签名</el-button></el-upload><img v-if="form.signImgUrl" :src="form.signImgUrl" style="height:64px;margin-top:6px;border:1px solid #ebeef5;border-radius:4px;background:#fff;"/></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="默认挂号费"><el-input v-model.number="form.regFee" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="可挂号"><el-switch v-model="form.canRegister" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="排序号"><el-input v-model.number="form.sortNo" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="在职" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="医疗权限" name="medical">',
      '        <el-form :model="form" label-width="100px">',
      '          <el-alert v-if="form.staffType!==\'医师\'" title="处方权/手术分级权限仅对医师类别适用" type="info" :closable="false" show-icon></el-alert>',
      '          <template v-else>',
      '      <el-divider content-position="left">处方权限(药事管理强管控)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="处方权"><el-switch v-model="form.rxRight" :active-value="1" :inactive-value="0" active-text="具备" inactive-text="无" @change="onRxRightChange"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="抗菌药物级别"><el-select v-model="form.antibioticLevel" clearable style="width:100%" placeholder="分级授权(HBCV08.50.029)" @change="ensureRxRight"><el-option v-for="o in abxOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label=" "><el-button size="small" @click="suggestAbxLevel">按职称建议抗菌级别</el-button><span style="color:#909399;font-size:12px;margin-left:8px;">高级→三级(特殊使用) 中级→二级(限制) 初级→一级(非限制)</span></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="麻醉药品"><el-switch v-model="form.narcoticRight" :active-value="1" :inactive-value="0" @change="ensureRxRight"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="第一类精神"><el-switch v-model="form.psych1Right" :active-value="1" :inactive-value="0" @change="ensureRxRight"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="第二类精神"><el-switch v-model="form.psych2Right" :active-value="1" :inactive-value="0" @change="ensureRxRight"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">手术权限(手术分级授权)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="手术级别"><el-select v-model="form.surgeryLevel" clearable style="width:100%" placeholder="可主刀最高手术级别(oprn_lv_code)"><el-option v-for="o in surgeryOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label=" "><el-button size="small" @click="suggestSurgeryLevel">按职称建议手术级别</el-button></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label=" "><span style="color:#909399;font-size:12px;">正高→四级 副高→三级 中级→二级 初级→一级(《医疗技术临床应用管理办法》手术分级授权, 可手工调整)</span></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">授权留痕(可追溯/定期复训失效)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="授权机构"><el-input v-model="form.rxAuthOrg" placeholder="医务科/授权部门"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="授权文号"><el-input v-model="form.rxAuthNo" placeholder="授权文件编号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="授权日期"><el-date-picker v-model="form.rxAuthDate" type="date" value-format="YYYY-MM-DD" placeholder="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="有效期至"><el-date-picker v-model="form.rxValidUntil" type="date" value-format="YYYY-MM-DD" placeholder="到期需复训考核" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '      </el-row>',
      '          </template>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="医保信息" name="insur">',
      '        <el-form :model="form" label-width="110px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="主治医师编码"><el-input v-model="form.atddrNo" placeholder="医保 atddr_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="诊断医师编码"><el-input v-model="form.diseDorNo" placeholder="医保 dise_dor_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保业务编码"><el-input v-model="form.medInsurCode" placeholder="国家医保业务编码(医师/药师/护士)"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="执业类别"><el-select v-model="form.pracCate" style="width:100%" clearable filterable placeholder="医师执业类别(CT98.00.024)"><el-option v-for="o in pracCateOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医师资格证号"><el-input v-model="form.drQualCertNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="执业证书编码"><el-input v-model="form.pracCertNo" placeholder="医师执业证书编码"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '        </el-form>',
      '      </el-tab-pane>',
      '    </el-tabs>',
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
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
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
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
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
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
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
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
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
