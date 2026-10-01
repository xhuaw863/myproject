/* DRG/DIP 预分组与入组对比(P3-A): 三 tab 视图 - 分组列表 / 同诊断入组差异 / 入组概览。
 * 后端 /api/his/mr/drg: list / comparison / summary。只读展示, 无写操作。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrDrg = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        activeTab: 'list',
        loading: false,
        rows: [], page: 1, size: 20, total: 0,
        filters: { deptId: null, drgCode: '', dipCode: '', startDate: '', endDate: '' },
        depts: [],
        /* comparison */
        compLoading: false, compRows: [], compDiagCode: '', compLimit: 20,
        /* summary */
        sumLoading: false, summary: {}
      };
    },
    computed: {
      dateRange: function () {
        var f = this.filters;
        if (f.startDate && f.endDate) return [f.startDate, f.endDate];
        if (Array.isArray(f.startDate)) return f.startDate;
        return null;
      }
    },
    created: function () {
      this.loadDepts();
      this.loadList();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/tree').then(function (d) {
          var out = [];
          (function walk(list) {
            (list || []).forEach(function (n) {
              out.push({ id: n.id, label: n.deptName || n.deptCode });
              if (n.children && n.children.length) walk(n.children);
            });
          })(d || []);
          vm.depts = out;
        }).catch(function () { });
      },
      /* === 分组列表 === */
      loadList: function () {
        var vm = this; vm.loading = true;
        var q = { page: vm.page, size: vm.size };
        var f = vm.filters;
        if (f.deptId) q.deptId = f.deptId;
        if (f.drgCode) q.drgCode = f.drgCode;
        if (f.dipCode) q.dipCode = f.dipCode;
        if (f.startDate) q.startDate = f.startDate;
        if (f.endDate) q.endDate = f.endDate;
        HIS.get('/api/his/mr/drg/list', q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && Number(d.total)) || 0;
        }).catch(function () { vm.rows = []; }).finally(function () { vm.loading = false; });
      },
      resetList: function () {
        this.filters = { deptId: null, drgCode: '', dipCode: '', startDate: '', endDate: '' };
        this.page = 1;
        this.loadList();
      },
      /* === 同诊断入组差异 === */
      loadComparison: function () {
        var vm = this; vm.compLoading = true;
        var q = { limit: vm.compLimit };
        if (vm.compDiagCode) q.diagCode = vm.compDiagCode;
        HIS.get('/api/his/mr/drg/comparison', q).then(function (d) {
          vm.compRows = d || [];
        }).catch(function () { vm.compRows = []; }).finally(function () { vm.compLoading = false; });
      },
      /* === 入组概览 === */
      loadSummary: function () {
        var vm = this; vm.sumLoading = true;
        var q = {};
        if (vm.filters.startDate) q.startDate = vm.filters.startDate;
        if (vm.filters.endDate) q.endDate = vm.filters.endDate;
        HIS.get('/api/his/mr/drg/summary', q).then(function (d) {
          vm.summary = d || {};
        }).catch(function () { vm.summary = {}; }).finally(function () { vm.sumLoading = false; });
      },
      tabChange: function (tab) {
        if (tab === 'comparison' && !this.compRows.length) this.loadComparison();
        if (tab === 'summary' && !this.summary.totalCataloged) this.loadSummary();
      },
      drgText: function (row) {
        return row.drgGroupCode || row.visitDrgCode || '-';
      }
    },
    template: [
      '<div style="display:flex;flex-direction:column;height:100%;padding:12px;gap:10px;">',
      '  <h3 style="margin:0;">DRG/DIP 预分组与入组对比</h3>',
      '  <el-tabs v-model="activeTab" @tab-change="tabChange">',
      /* Tab 1: 分组列表 */
      '    <el-tab-pane label="分组列表" name="list">',
      '      <div style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px;align-items:center;">',
      '        <el-select v-model="filters.deptId" placeholder="出院科室" clearable filterable size="small" style="width:140px;">',
      '          <el-option v-for="d in depts" :key="idKey(d)" :label="d.label" :value="d.id"></el-option>',
      '        </el-select>',
      '        <el-input v-model="filters.drgCode" placeholder="DRG编码" size="small" style="width:120px;"></el-input>',
      '        <el-input v-model="filters.dipCode" placeholder="DIP编码" size="small" style="width:120px;"></el-input>',
      '        <el-date-picker v-model="filters.startDate" type="date" placeholder="出院起" size="small" style="width:130px;" value-format="YYYY-MM-DD"></el-date-picker>',
      '        <el-date-picker v-model="filters.endDate" type="date" placeholder="出院止" size="small" style="width:130px;" value-format="YYYY-MM-DD"></el-date-picker>',
      '        <el-button type="primary" size="small" @click="loadList()">查询</el-button>',
      '        <el-button size="small" @click="resetList()">重置</el-button>',
      '      </div>',
      '      <el-table :data="rows" border size="small" v-loading="loading" style="width:100%;flex:1;min-height:0;" empty-text="暂无已编目病案分组数据">',
      '        <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column prop="catalogNo" label="病案号" width="110"></el-table-column>',
      '        <el-table-column prop="patientName" label="姓名" width="80"></el-table-column>',
      '        <el-table-column prop="dischargeDeptName" label="出院科室" width="110"></el-table-column>',
      '        <el-table-column prop="mainDiagCode" label="主诊编码" width="100"></el-table-column>',
      '        <el-table-column prop="mainDiagName" label="主诊名称" min-width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="DRG分组" width="110"><template #default="s">{{ drgText(s.row) }}</template></el-table-column>',
      '        <el-table-column prop="dipCode" label="DIP编码" width="100"></el-table-column>',
      '        <el-table-column prop="totalAmount" label="总费用" width="90" align="right"></el-table-column>',
      '        <el-table-column prop="losDays" label="住院日" width="70" align="center"></el-table-column>',
      '      </el-table>',
      '      <el-pagination background layout="total,prev,pager,next,sizes" :total="total" v-model:current-page="page" v-model:page-size="size" :page-sizes="[10,20,50,100]" @current-change="loadList" @size-change="loadList" style="margin-top:8px;justify-content:flex-end;"></el-pagination>',
      '    </el-tab-pane>',
      /* Tab 2: 同诊断入组差异 */
      '    <el-tab-pane label="同诊断入组差异" name="comparison">',
      '      <div style="display:flex;gap:8px;margin-bottom:10px;align-items:center;">',
      '        <el-input v-model="compDiagCode" placeholder="诊断编码(精确)" size="small" style="width:150px;"></el-input>',
      '        <el-input-number v-model="compLimit" :min="5" :max="200" size="small" style="width:100px;"></el-input-number>',
      '        <el-button type="primary" size="small" @click="loadComparison()">查询</el-button>',
      '      </div>',
      '      <el-table :data="compRows" border size="small" v-loading="compLoading" style="width:100%;flex:1;min-height:0;" empty-text="暂无对比数据">',
      '        <el-table-column prop="diagCode" label="诊断编码" width="110"></el-table-column>',
      '        <el-table-column prop="diagName" label="诊断名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="drgGroupCode" label="DRG分组" width="110"></el-table-column>',
      '        <el-table-column prop="dipCode" label="DIP编码" width="100"></el-table-column>',
      '        <el-table-column prop="caseCount" label="例数" width="70" align="center"></el-table-column>',
      '        <el-table-column prop="avgCost" label="例均费用" width="100" align="right"></el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      /* Tab 3: 入组概览 */
      '    <el-tab-pane label="入组概览" name="summary">',
      '      <div v-loading="sumLoading" style="display:flex;flex-wrap:wrap;gap:12px;margin-bottom:14px;">',
      '        <el-card shadow="never" style="width:140px;text-align:center;"><div style="font-size:24px;font-weight:bold;">{{ summary.totalCataloged || 0 }}</div><div>已编目总数</div></el-card>',
      '        <el-card shadow="never" style="width:140px;text-align:center;"><div style="font-size:24px;font-weight:bold;color:#409EFF;">{{ summary.hasDrgGroup || 0 }}</div><div>有DRG分组</div></el-card>',
      '        <el-card shadow="never" style="width:140px;text-align:center;"><div style="font-size:24px;font-weight:bold;color:#67C23A;">{{ summary.hasDip || 0 }}</div><div>有DIP编码</div></el-card>',
      '        <el-card shadow="never" style="width:140px;text-align:center;"><div style="font-size:24px;font-weight:bold;color:#E6A23C;">{{ summary.noGroup || 0 }}</div><div>未入组</div></el-card>',
      '      </div>',
      '      <h4 style="margin:0 0 8px;">DRG TOP 分布</h4>',
      '      <el-table :data="summary.drgDistribution || []" border size="small" style="width:100%;" empty-text="暂无数据">',
      '        <el-table-column prop="drgCode" label="DRG编码" width="130"></el-table-column>',
      '        <el-table-column prop="caseCount" label="例数" width="80" align="center"></el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };
})();
