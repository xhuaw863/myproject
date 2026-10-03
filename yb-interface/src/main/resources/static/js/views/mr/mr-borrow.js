/* 病案借阅管理(病案统计科侧 P1-B): 病案实体借出/归还登记与借阅单查看。
 * 后端 /api/his/mr/borrow: list(GET) / lend(POST借出) / {id}/return(PUT归还) / refresh-overdue(POST逾期刷新) / slip/{id}(GET借阅单)。
 * 借出选病案复用快速检索 /api/his/mr/search/quick; 借阅人科室复用 /api/his/dept/tree(后端按 deptId 反查名称)。
 * 铁律: 不回写临床首页; 写权限本机构管理员(后端 requireSelfOrgWrite 兜底 403), 前端按角色隐藏写按钮。
 * 雪花 ID 全链路字符串承载; 运行时模板仅访问组件作用域, 文件级助手须以 methods 暴露(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrBorrow = {
    mixins: [HIS.kwSelectMixin],
    components: { 'dept-tree-picker': HIS.components.DeptTreePicker },
    data: function () {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 20,
        total: 0,
        status: null,
        deptId: null,
        keyword: '',
        depts: [],
        deptRaw: [],
        deptsLoading: false,
        statusOpts: [
          { value: 1, label: '借出' },
          { value: 2, label: '已归还' },
          { value: 3, label: '逾期' }
        ],
        /* 借出登记 */
        lend: {
          visible: false, saving: false, kw: '', pickRows: [], pickLoading: false,
          visitId: null, patientLabel: '', borrowerName: '', borrowerDeptId: null, purpose: '', expectReturnDate: ''
        },
        /* 借阅单 */
        slip: { visible: false, loading: false, data: {} }
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      }
    },
    created: function () {
      this.loadDepts();
      this.loadList();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      fmt: function (v) { return (v || '').toString().replace('T', ' ').slice(0, 16); },
      fmtDay: function (v) { return (v || '').toString().slice(0, 10); },
      statusText: function (s) { return { 1: '借出', 2: '已归还', 3: '逾期' }[s] || '-'; },
      statusTag: function (s) { return s === 2 ? 'success' : (s === 3 ? 'danger' : 'warning'); },
      /* ================= 取数 ================= */
      loadDepts: function () {
        var vm = this;
        vm.deptsLoading = true;
        HIS.get('/api/his/dept/tree').then(function (d) {
          var out = []; var raw = [];
          (function walk(list, depth) {
            (list || []).forEach(function (n) {
              var pad = '';
              for (var i = 0; i < depth; i++) { pad += '　'; }
              out.push({ id: n.id, label: pad + (n.deptName || ('科室' + n.id)), deptName: n.deptName, deptCode: n.deptCode });
              raw.push({ id: n.id, parentId: n.parentId, orgId: n.orgId, deptLevel: n.deptLevel, deptName: n.deptName, deptCode: n.deptCode, pyCode: n.pyCode, abbrCode: n.abbrCode });
              if (n.children && n.children.length) { walk(n.children, depth + 1); }
            });
          })(d || [], 0);
          vm.depts = out; vm.deptRaw = raw;
        }).catch(function () { vm.depts = []; vm.deptRaw = []; }).finally(function () { vm.deptsLoading = false; });
      },
      loadList: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/mr/borrow/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.status != null) { q += '&status=' + vm.status; }
        if (vm.deptId != null) { q += '&borrowerDeptId=' + HIS.idParam(vm.deptId); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadList(); },
      reset: function () { this.keyword = ''; this.deptId = null; this.status = null; this.page = 1; this.loadList(); },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },

      /* ================= 借出登记 ================= */
      openLend: function () {
        this.lend = {
          visible: true, saving: false, kw: '', pickRows: [], pickLoading: false,
          visitId: null, patientLabel: '', borrowerName: '', borrowerDeptId: null, purpose: '', expectReturnDate: ''
        };
      },
      doLendPick: function () {
        var vm = this;
        if (!vm.lend.kw) { ElementPlus.ElMessage.warning('请输入患者姓名或住院号'); return; }
        vm.lend.pickLoading = true;
        HIS.get('/api/his/mr/search/quick?page=1&size=20&keyword=' + encodeURIComponent(vm.lend.kw)).then(function (d) {
          vm.lend.pickRows = (d && d.records) || [];
        }).catch(function () { vm.lend.pickRows = []; }).finally(function () { vm.lend.pickLoading = false; });
      },
      chooseVisit: function (row) {
        this.lend.visitId = row.visitId;
        this.lend.patientLabel = (row.patientName || '') + ' · ' + (row.inpNo || '') + (row.mainDiagName ? ' · ' + row.mainDiagName : '');
      },
      doLend: function () {
        var vm = this;
        if (!vm.lend.visitId) { ElementPlus.ElMessage.warning('请先检索并选择要借出的病案'); return; }
        if (!vm.lend.borrowerName) { ElementPlus.ElMessage.warning('请填写借阅人姓名'); return; }
        var body = {
          visitId: HIS.id(vm.lend.visitId),
          borrowerName: vm.lend.borrowerName,
          purpose: vm.lend.purpose
        };
        if (vm.lend.borrowerDeptId != null) { body.borrowerDeptId = HIS.id(vm.lend.borrowerDeptId); }
        if (vm.lend.expectReturnDate) { body.expectReturnDate = vm.lend.expectReturnDate; }
        vm.lend.saving = true;
        HIS.post('/api/his/mr/borrow/lend', body).then(function (res) {
          HIS.notifySuccess('借出成功, 借阅单号 ' + ((res && res.borrowNo) || ''));
          vm.lend.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.lend.saving = false; });
      },

      /* ================= 归还 ================= */
      giveBack: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认归还「' + (row.patientName || '') + '」的病案?', '提示', { type: 'warning' })
          .then(function () {
            return HIS.put('/api/his/mr/borrow/' + HIS.id(row.id) + '/return', {});
          }).then(function () {
            HIS.notifySuccess('已登记归还');
            vm.loadList();
          }).catch(function (e) { if (e !== 'cancel' && e) { HIS.notifyError(e); } });
      },

      /* ================= 逾期刷新 ================= */
      refreshOverdue: function () {
        var vm = this;
        HIS.post('/api/his/mr/borrow/refresh-overdue', {}).then(function (n) {
          HIS.notifySuccess('逾期刷新: 标记 ' + (n || 0) + ' 份超期未还');
          vm.loadList();
        }).catch(HIS.notifyError);
      },

      /* ================= 借阅单 ================= */
      viewSlip: function (row) {
        var vm = this;
        vm.slip = { visible: true, loading: true, data: {} };
        HIS.get('/api/his/mr/borrow/slip/' + HIS.id(row.id)).then(function (d) {
          vm.slip.data = d || {};
        }).catch(HIS.notifyError).finally(function () { vm.slip.loading = false; });
      },
      /* 借出弹窗内选中行高亮(模板无法访问全局 HIS, 经方法暴露) */
      lendRowClass: function (o) {
        return HIS.sameId(o.row.visitId, this.lend.visitId) ? 'sel-row' : '';
      },
      doPrint: function () {
        window.print();
      }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">病案借阅管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">病案实体借出/归还登记 · 借阅单 · 逾期跟踪</span></div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <el-input v-model="keyword" size="small" placeholder="患者 / 住院号 / 借阅单号 / 借阅人" clearable style="width:220px;" @keyup.enter="search"></el-input>',
      '    <el-select v-model="deptId" size="small" filterable clearable placeholder="借阅人科室" style="width:180px" :loading="deptsLoading"',
      '      :filter-method="kwFilter(\'dept\')">',
      '      <el-option v-for="d in kwOptions(\'dept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="status" size="small" clearable placeholder="借阅状态" style="width:130px">',
      '      <el-option v-for="s in statusOpts" :key="s.value" :label="s.label" :value="s.value"></el-option>',
      '    </el-select>',
      '    <el-button size="small" type="primary" @click="search">查询</el-button>',
      '    <el-button size="small" @click="reset">重置</el-button>',
      '    <span style="flex:1"></span>',
      '    <template v-if="canWrite">',
      '      <el-button size="small" type="primary" @click="openLend">借出登记</el-button>',
      '      <el-button size="small" type="warning" @click="refreshOverdue">逾期刷新</el-button>',
      '    </template>',
      '  </div>',
      '  <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无借阅记录">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '    <el-table-column prop="borrowNo" label="借阅单号" width="140"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '    <el-table-column prop="inpNo" label="住院号" width="110"></el-table-column>',
      '    <el-table-column prop="borrowerName" label="借阅人" width="90"></el-table-column>',
      '    <el-table-column prop="borrowerDeptName" label="借阅科室" width="120"></el-table-column>',
      '    <el-table-column prop="purpose" label="事由" min-width="120" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.borrowStatus)">{{ statusText(s.row.borrowStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="借出" width="100"><template #default="s">{{ fmtDay(s.row.borrowTime) }}</template></el-table-column>',
      '    <el-table-column label="应还" width="100"><template #default="s">{{ fmtDay(s.row.expectReturnDate) }}</template></el-table-column>',
      '    <el-table-column label="实还" width="100"><template #default="s">{{ fmtDay(s.row.actualReturnTime) }}</template></el-table-column>',
      '    <el-table-column label="操作" width="120" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button type="info" link size="small" @click="viewSlip(s.row)">借阅单</el-button>',
      '        <el-button v-if="canWrite && s.row.borrowStatus!==2" type="success" link size="small" @click="giveBack(s.row)">归还</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]"',
      '    @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>',
      /* ---- 借出登记弹窗 ---- */
      '<el-dialog v-model="lend.visible" title="病案借出登记" width="720px" destroy-on-close>',
      '  <div style="display:flex;gap:6px;margin-bottom:8px;">',
      '    <el-input v-model="lend.kw" size="small" placeholder="患者姓名 / 住院号 检索病案" clearable style="flex:1;" @keyup.enter="doLendPick"></el-input>',
      '    <el-button size="small" type="primary" :loading="lend.pickLoading" @click="doLendPick">检索</el-button>',
      '  </div>',
      '  <el-table :data="lend.pickRows" border size="small" height="180px" @row-click="chooseVisit" empty-text="检索后点击选择病案"',
      '    :row-class-name="lendRowClass">',
      '    <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '    <el-table-column prop="inpNo" label="住院号" width="110"></el-table-column>',
      '    <el-table-column prop="dischargeDeptName" label="出院科室" width="120"></el-table-column>',
      '    <el-table-column prop="mainDiagName" label="主要诊断" min-width="140" show-overflow-tooltip></el-table-column>',
      '  </el-table>',
      '  <div v-if="lend.patientLabel" style="margin:6px 0;font-size:13px;color:var(--yb-ink-2);">已选: {{ lend.patientLabel }}</div>',
      '  <el-form label-width="90px" size="default" style="margin-top:8px;">',
      '    <el-form-item label="借阅人" required><el-input v-model="lend.borrowerName" placeholder="借阅人姓名" style="width:220px;"></el-input></el-form-item>',
      '    <el-form-item label="借阅科室">',
      '      <dept-tree-picker v-model="lend.borrowerDeptId" :options="deptRaw" width="260px" placeholder="借阅人科室" />',
      '    </el-form-item>',
      '    <el-form-item label="应还日期"><el-date-picker v-model="lend.expectReturnDate" type="date" placeholder="默认借出后7天" value-format="YYYY-MM-DD" style="width:220px;"></el-date-picker></el-form-item>',
      '    <el-form-item label="借阅事由"><el-input v-model="lend.purpose" type="textarea" :rows="2" placeholder="选填"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="lend.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="lend.saving" @click="doLend">确认借出</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 借阅单弹窗 ---- */
      '<el-dialog v-model="slip.visible" title="借阅单" width="560px" destroy-on-close>',
      '  <div v-loading="slip.loading" style="padding:8px 12px;">',
      '    <div style="text-align:center;font-size:18px;font-weight:600;margin-bottom:4px;">病 案 借 阅 单</div>',
      '    <div style="text-align:center;color:var(--yb-ink-2);font-size:13px;margin-bottom:12px;">单号: {{ slip.data.borrowNo }}</div>',
      '    <el-descriptions :column="2" border size="small">',
      '      <el-descriptions-item label="患者姓名">{{ slip.data.patientName }}</el-descriptions-item>',
      '      <el-descriptions-item label="住院号">{{ slip.data.inpNo }}</el-descriptions-item>',
      '      <el-descriptions-item label="性别">{{ slip.data.genderName }}</el-descriptions-item>',
      '      <el-descriptions-item label="年龄">{{ slip.data.age }}</el-descriptions-item>',
      '      <el-descriptions-item label="出院科室">{{ slip.data.deptName }}</el-descriptions-item>',
      '      <el-descriptions-item label="出院日期">{{ fmtDay(slip.data.dischargeDate) }}</el-descriptions-item>',
      '      <el-descriptions-item label="借阅人">{{ slip.data.borrowerName }}</el-descriptions-item>',
      '      <el-descriptions-item label="借阅科室">{{ slip.data.borrowerDeptName }}</el-descriptions-item>',
      '      <el-descriptions-item label="借出时间">{{ fmt(slip.data.borrowTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="应还日期">{{ fmtDay(slip.data.expectReturnDate) }}</el-descriptions-item>',
      '      <el-descriptions-item label="实际归还">{{ fmt(slip.data.actualReturnTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="状态">{{ slip.data.statusText }}</el-descriptions-item>',
      '      <el-descriptions-item label="借阅事由" :span="2">{{ slip.data.purpose }}</el-descriptions-item>',
      '      <el-descriptions-item label="经办人" :span="2">{{ slip.data.operatorName }}</el-descriptions-item>',
      '    </el-descriptions>',
      '  </div>',
      '  <template #footer>',
      '    <el-button @click="slip.visible=false">关闭</el-button>',
      '    <el-button type="primary" @click="doPrint">打印</el-button>',
      '  </template>',
      '</el-dialog>'
    ].join('\n')
  };
})();
