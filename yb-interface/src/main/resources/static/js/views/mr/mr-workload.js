/* 病案工作量统计(病案统计科侧 P2, 收尾 P1 遗留 E): 按统计期/科室/责任人登记门诊·住院病区·医技·其他项工作量, 并做逻辑审核(合理性复核)。
 * 后端 /api/his/mr/workload: list(GET) / POST新增 / PUT{id}编辑 / DEL{id} / PUT{id}/audit单条 / POST batch-audit / GET summary概览。
 * 铁律: 独立于编目/临床首页; 写权限本机构管理员(后端 requireSelfOrgWrite 兜底 403), 前端按角色隐藏写按钮。
 * 雪花 ID 全链路字符串承载; 运行时模板仅访问组件作用域, 文件级助手须以 methods 暴露(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrWorkload = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 20,
        total: 0,
        period: '',
        deptId: null,
        category: null,
        auditStatus: null,
        keyword: '',
        depts: [],
        deptsLoading: false,
        picked: [],
        catOpts: [
          { value: 'outp', label: '门诊' },
          { value: 'inp', label: '住院病区' },
          { value: 'tech', label: '医技' },
          { value: 'other', label: '其他项' }
        ],
        auditOpts: [
          { value: 1, label: '待审核' },
          { value: 2, label: '已通过' },
          { value: 3, label: '已驳回' }
        ],
        /* 新增/编辑弹窗 */
        form: { visible: false, saving: false, id: null, period: '', deptId: null, staffName: '', category: 'outp', itemCode: '', itemName: '', qty: null, amount: null, remark: '' },
        /* 单条审核弹窗 */
        audit: { visible: false, saving: false, row: null, approved: true, opinion: '' },
        /* 概览弹窗 */
        summary: { visible: false, loading: false, period: '', rows: [] }
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
      catText: function (c) { var m = { outp: '门诊', inp: '住院病区', tech: '医技', other: '其他项' }; return m[c] || c || '-'; },
      auditText: function (s) { return { 1: '待审核', 2: '已通过', 3: '已驳回' }[s] || '-'; },
      auditTag: function (s) { return s === 2 ? 'success' : (s === 3 ? 'danger' : 'warning'); },
      loadDepts: function () {
        var vm = this;
        vm.deptsLoading = true;
        HIS.get('/api/his/dept/tree').then(function (d) {
          var out = [];
          (function walk(list, depth) {
            (list || []).forEach(function (n) {
              var pad = '';
              for (var i = 0; i < depth; i++) { pad += '　'; }
              out.push({ id: n.id, label: pad + (n.deptName || ('科室' + n.id)), deptName: n.deptName, deptCode: n.deptCode });
              if (n.children && n.children.length) { walk(n.children, depth + 1); }
            });
          })(d || [], 0);
          vm.depts = out;
        }).catch(function () { vm.depts = []; }).finally(function () { vm.deptsLoading = false; });
      },
      loadList: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/mr/workload/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.period) { q += '&period=' + encodeURIComponent(vm.period); }
        if (vm.deptId != null) { q += '&deptId=' + HIS.idParam(vm.deptId); }
        if (vm.category) { q += '&category=' + encodeURIComponent(vm.category); }
        if (vm.auditStatus != null) { q += '&auditStatus=' + vm.auditStatus; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadList(); },
      reset: function () { this.period = ''; this.deptId = null; this.category = null; this.auditStatus = null; this.keyword = ''; this.page = 1; this.loadList(); },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },
      onSel: function (rows) { this.picked = rows || []; },

      /* 新增/编辑 */
      openCreate: function () {
        this.form = { visible: true, saving: false, id: null, period: this.period || '', deptId: null, staffName: '', category: 'outp', itemCode: '', itemName: '', qty: null, amount: null, remark: '' };
      },
      openEdit: function (row) {
        this.form = {
          visible: true, saving: false, id: row.id, period: row.period, deptId: row.deptId, staffName: row.staffName,
          category: row.category, itemCode: row.itemCode, itemName: row.itemName, qty: row.qty, amount: row.amount, remark: row.remark
        };
      },
      doSave: function () {
        var vm = this;
        if (!vm.form.period) { ElementPlus.ElMessage.warning('请选择统计期'); return; }
        if (!vm.form.category) { ElementPlus.ElMessage.warning('请选择工作量类别'); return; }
        var body = {
          period: vm.form.period, deptId: vm.form.deptId, staffName: vm.form.staffName, category: vm.form.category,
          itemCode: vm.form.itemCode, itemName: vm.form.itemName, qty: vm.form.qty, amount: vm.form.amount, remark: vm.form.remark
        };
        vm.form.saving = true;
        var p = vm.form.id ? HIS.put('/api/his/mr/workload/' + HIS.id(vm.form.id), body) : HIS.post('/api/his/mr/workload', body);
        p.then(function () {
          HIS.notifySuccess(vm.form.id ? '已保存' : '已登记');
          vm.form.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.form.saving = false; });
      },
      doDelete: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除该工作量记录?', '提示', { type: 'warning' }).then(function () {
          HIS.del('/api/his/mr/workload/' + HIS.id(row.id)).then(function () {
            HIS.notifySuccess('已删除');
            vm.loadList();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },

      /* 单条审核 */
      openAudit: function (row) {
        this.audit = { visible: true, saving: false, row: row, approved: true, opinion: '' };
      },
      doAudit: function () {
        var vm = this;
        vm.audit.saving = true;
        HIS.put('/api/his/mr/workload/' + HIS.id(vm.audit.row.id) + '/audit', { approved: vm.audit.approved, opinion: vm.audit.opinion }).then(function () {
          HIS.notifySuccess(vm.audit.approved ? '已通过' : '已驳回');
          vm.audit.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.audit.saving = false; });
      },
      /* 批量审核 */
      batchAudit: function (approved) {
        var vm = this;
        var ids = vm.picked.map(function (r) { return HIS.id(r.id); });
        if (!ids.length) { ElementPlus.ElMessage.warning('请勾选待审核记录'); return; }
        HIS.post('/api/his/mr/workload/batch-audit', { ids: ids, approved: approved, opinion: '' }).then(function (res) {
          HIS.notifySuccess('批量审核完成: 处理 ' + (res && res.done || 0) + ' 条, 跳过 ' + (res && res.skipped || 0) + ' 条');
          vm.loadList();
        }).catch(HIS.notifyError);
      },

      /* 概览 */
      openSummary: function () {
        this.summary = { visible: true, loading: false, period: this.period, rows: [] };
        this.loadSummary();
      },
      loadSummary: function () {
        var vm = this;
        vm.summary.loading = true;
        var q = '/api/his/mr/workload/summary' + (vm.summary.period ? '?period=' + encodeURIComponent(vm.summary.period) : '');
        HIS.get(q).then(function (d) { vm.summary.rows = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.summary.loading = false; });
      },
      num: function (v) { return v == null ? '-' : v; }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">工作量统计 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">门诊 / 住院病区 / 医技 / 其他项登记 · 逻辑审核(合理性复核)</span></div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <el-date-picker v-model="period" type="month" size="small" placeholder="统计期" value-format="YYYY-MM" style="width:140px;"></el-date-picker>',
      '    <el-select v-model="deptId" size="small" filterable clearable placeholder="科室" style="width:170px" :loading="deptsLoading" :filter-method="kwFilter(\'dept\')">',
      '      <el-option v-for="d in kwOptions(\'dept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="category" size="small" clearable placeholder="类别" style="width:120px">',
      '      <el-option v-for="c in catOpts" :key="c.value" :label="c.label" :value="c.value"></el-option>',
      '    </el-select>',
      '    <el-select v-model="auditStatus" size="small" clearable placeholder="审核状态" style="width:120px">',
      '      <el-option v-for="a in auditOpts" :key="a.value" :label="a.label" :value="a.value"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" size="small" placeholder="项目/责任人/科室" clearable style="width:160px;" @keyup.enter="search"></el-input>',
      '    <el-button size="small" type="primary" @click="search">查询</el-button>',
      '    <el-button size="small" @click="reset">重置</el-button>',
      '    <span style="flex:1"></span>',
      '    <el-button size="small" @click="openSummary">概览汇总</el-button>',
      '    <template v-if="canWrite">',
      '      <el-button size="small" type="primary" @click="openCreate">新增登记</el-button>',
      '      <el-button size="small" type="success" @click="batchAudit(true)">批量通过</el-button>',
      '      <el-button size="small" type="warning" @click="batchAudit(false)">批量驳回</el-button>',
      '    </template>',
      '  </div>',
      '  <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" @selection-change="onSel" empty-text="暂无工作量记录">',
      '    <el-table-column v-if="canWrite" type="selection" width="42"></el-table-column>',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '    <el-table-column prop="period" label="统计期" width="90" align="center"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" min-width="120"></el-table-column>',
      '    <el-table-column prop="staffName" label="责任人" width="100"></el-table-column>',
      '    <el-table-column label="类别" width="100"><template #default="s">{{ catText(s.row.category) }}</template></el-table-column>',
      '    <el-table-column prop="itemName" label="项目" min-width="140"></el-table-column>',
      '    <el-table-column prop="qty" label="数量" width="90" align="right"></el-table-column>',
      '    <el-table-column prop="amount" label="金额" width="110" align="right"></el-table-column>',
      '    <el-table-column label="审核" width="90" align="center"><template #default="s"><el-tag size="small" :type="auditTag(s.row.auditStatus)">{{ auditText(s.row.auditStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="170" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button v-if="canWrite && s.row.auditStatus!==2" type="success" link size="small" @click="openAudit(s.row)">审核</el-button>',
      '        <el-button v-if="canWrite" type="primary" link size="small" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button v-if="canWrite" type="danger" link size="small" @click="doDelete(s.row)">删除</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]"',
      '    @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>',
      /* ---- 新增/编辑弹窗 ---- */
      '<el-dialog v-model="form.visible" :title="form.id ? \'编辑工作量\' : \'新增工作量登记\'" width="520px" destroy-on-close>',
      '  <el-form label-width="90px" size="default">',
      '    <el-form-item label="统计期" required><el-date-picker v-model="form.period" type="month" placeholder="yyyy-MM" value-format="YYYY-MM" style="width:100%;"></el-date-picker></el-form-item>',
      '    <el-form-item label="科室"><el-select v-model="form.deptId" filterable clearable placeholder="选择科室" style="width:100%" :loading="deptsLoading" :filter-method="kwFilter(\'fdept\')"><el-option v-for="d in kwOptions(\'fdept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option></el-select></el-form-item>',
      '    <el-form-item label="责任人"><el-input v-model="form.staffName" placeholder="责任编码员姓名"></el-input></el-form-item>',
      '    <el-form-item label="类别" required><el-select v-model="form.category" style="width:100%"><el-option v-for="c in catOpts" :key="c.value" :label="c.label" :value="c.value"></el-option></el-select></el-form-item>',
      '    <el-form-item label="项目编码"><el-input v-model="form.itemCode" placeholder="选填"></el-input></el-form-item>',
      '    <el-form-item label="项目名称"><el-input v-model="form.itemName" placeholder="如 编目病案/借阅处理"></el-input></el-form-item>',
      '    <el-form-item label="数量"><el-input v-model.number="form.qty" placeholder="0"></el-input></el-form-item>',
      '    <el-form-item label="金额"><el-input v-model.number="form.amount" placeholder="选填"></el-input></el-form-item>',
      '    <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="form.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="form.saving" @click="doSave">保存</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 单条审核弹窗 ---- */
      '<el-dialog v-model="audit.visible" title="逻辑审核" width="440px" destroy-on-close>',
      '  <div style="margin-bottom:10px;font-size:13px;color:var(--yb-ink-2);">{{ audit.row ? (audit.row.period + \' · \' + audit.row.deptName + \' · \' + audit.row.itemName) : \'\' }}</div>',
      '  <el-form label-width="80px" size="default">',
      '    <el-form-item label="结论"><el-radio-group v-model="audit.approved"><el-radio :label="true">通过</el-radio><el-radio :label="false">驳回</el-radio></el-radio-group></el-form-item>',
      '    <el-form-item label="意见"><el-input v-model="audit.opinion" type="textarea" :rows="2" placeholder="驳回原因/审核说明"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="audit.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="audit.saving" @click="doAudit">确认</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 概览弹窗 ---- */
      '<el-dialog v-model="summary.visible" title="工作量概览汇总" width="720px" destroy-on-close>',
      '  <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;">',
      '    <span style="font-size:13px;">统计期</span>',
      '    <el-date-picker v-model="summary.period" type="month" size="small" placeholder="全部" value-format="YYYY-MM" style="width:140px;"></el-date-picker>',
      '    <el-button size="small" type="primary" :loading="summary.loading" @click="loadSummary">统计</el-button>',
      '  </div>',
      '  <el-table :data="summary.rows" border size="small" max-height="420px" v-loading="summary.loading" empty-text="暂无数据">',
      '    <el-table-column prop="period" label="统计期" width="90" align="center"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" min-width="130"></el-table-column>',
      '    <el-table-column label="类别" width="100"><template #default="s">{{ catText(s.row.category) }}</template></el-table-column>',
      '    <el-table-column prop="totalQty" label="数量合计" width="100" align="right"></el-table-column>',
      '    <el-table-column prop="totalAmount" label="金额合计" width="120" align="right"></el-table-column>',
      '    <el-table-column prop="pendingCount" label="待审" width="70" align="center"></el-table-column>',
      '    <el-table-column prop="passedCount" label="通过" width="70" align="center"></el-table-column>',
      '    <el-table-column prop="rejectedCount" label="驳回" width="70" align="center"></el-table-column>',
      '  </el-table>',
      '</el-dialog>'
    ].join('\n')
  };
})();
