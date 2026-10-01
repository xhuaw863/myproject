/* 病案统计报表(病案统计科侧 P2): 常用总览/出院科室分布/主要诊断TOP/手术操作TOP/住院日分布/质量评分分布 六类多维汇总, 支持 Excel 导出。
 * 后端 /api/his/mr/report: types(GET) / data(GET type,from,to,limit) / export(GET 同参数, xlsx 流)。
 * 铁律: 只读编目侧快照聚合(his_mr_catalog/diag/oper), 不回写临床首页; 机构 scopeOrgId 后端强制隔离。
 * 数据字段(键名)与后端 MrReportService.columnsOf 严格一一对应; bucket "N|xxx" 前缀前端剥序显示。
 * 运行时模板仅访问组件作用域; 文件级助手须以 methods 暴露(idKey/bucketText)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrReport = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        exporting: false,
        tab: 'overview',
        rows: [],
        dateRange: [],
        limit: 20,
        tabs: [
          { name: 'overview', label: '常用总览' },
          { name: 'byDept', label: '出院科室分布' },
          { name: 'diagTop', label: '主要诊断 TOP', top: true },
          { name: 'operTop', label: '手术操作 TOP', top: true },
          { name: 'losDist', label: '住院日分布' },
          { name: 'qualityDist', label: '质量评分分布' }
        ]
      };
    },
    computed: {
      cur: function () {
        var t = this.tab;
        var hit = null;
        this.tabs.forEach(function (x) { if (x.name === t) { hit = x; } });
        return hit || this.tabs[0];
      },
      isTop: function () { return !!this.cur.top; },
      /* 概览: 单行 KPI, 用横向卡片显示 */
      isOverview: function () { return this.tab === 'overview'; },
      /* KPI 卡片列表(与后端 overview 字段一一对应) */
      kpiCells: function () {
        return [
          { key: 'catalogTotal', label: '编目病案总数' },
          { key: 'pendingCatalog', label: '待编目' },
          { key: 'cataloging', label: '编目中' },
          { key: 'cataloged', label: '已编目' },
          { key: 'audited', label: '已审核' },
          { key: 'confirmed', label: '已确认' },
          { key: 'locked', label: '已锁定' },
          { key: 'tcmCount', label: '中医病案' },
          { key: 'westCount', label: '西医病案' },
          { key: 'avgLos', label: '平均住院日' },
          { key: 'avgQuality', label: '平均质量评分' }
        ];
      }
    },
    created: function () {
      this.loadData();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return i + 1; },
      bucketText: function (v) {
        var s = (v == null ? '' : String(v));
        var bar = s.indexOf('|');
        return bar >= 0 ? s.substring(bar + 1) : s;
      },
      num: function (v) {
        if (v == null || v === '') { return '-'; }
        return v;
      },
      onTab: function () { this.loadData(); },
      buildQuery: function () {
        var p = ['type=' + encodeURIComponent(this.tab)];
        if (this.dateRange && this.dateRange.length === 2) {
          p.push('from=' + encodeURIComponent(this.dateRange[0]));
          p.push('to=' + encodeURIComponent(this.dateRange[1]));
        }
        if (this.isTop) { p.push('limit=' + (this.limit || 20)); }
        return p.join('&');
      },
      loadData: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/his/mr/report/data?' + vm.buildQuery()).then(function (d) {
          vm.rows = d || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      doExport: function () {
        var vm = this;
        vm.exporting = true;
        HIS.download('/api/his/mr/report/export?' + vm.buildQuery(), vm.cur.label + '.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },
      /* 概览 KPI 卡片(取首行, 后端只返回 1 行) */
      kpi: function () { return (this.rows && this.rows[0]) || {}; }
    },
    template: [
        '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">病案统计报表 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">编目侧多维汇总 · Excel 导出 · 只读</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab" style="flex:1;min-height:0;display:flex;flex-direction:column;">',
      '    <el-tab-pane v-for="t in tabs" :key="t.name" :label="t.label" :name="t.name">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-date-picker v-model="dateRange" size="small" type="daterange" range-separator="至" start-placeholder="出院起" end-placeholder="出院止" value-format="YYYY-MM-DD" style="width:280px;"></el-date-picker>',
      '        <el-input-number v-if="isTop" v-model="limit" size="small" :min="5" :max="100" :step="5" controls-position="right" style="width:120px;" placeholder="TOP N"></el-input-number>',
      '        <span v-if="isTop" style="font-size:12px;color:var(--yb-ink-2);">TOP N</span>',
      '        <el-button size="small" type="primary" @click="loadData">查询</el-button>',
      '        <span style="flex:1"></span>',
      '        <el-button size="small" :loading="exporting" @click="doExport">导出 Excel</el-button>',
      '      </div>',
      /* ---- 概览: KPI 卡片(inline 样式, 避免 style 多根陷阱) ---- */
      '      <div v-if="t.name===\'overview\'" style="flex:1;min-height:0;overflow:auto;">',
      '        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px;">',
      '          <div v-for="k in kpiCells" :key="k.key" style="border:1px solid var(--yb-line,#e5e7eb);border-radius:6px;padding:14px 16px;background:var(--yb-bg-1,#fafafa);">',
      '            <div style="font-size:12px;color:var(--yb-ink-2,#6b7280);">{{ k.label }}</div>',
      '            <div style="font-size:22px;font-weight:600;margin-top:6px;color:var(--yb-ink,#111827);">{{ num(kpi()[k.key]) }}</div>',
      '          </div>',
      '        </div>',
      '      </div>',
      /* ---- 科室分布 ---- */
      '      <el-table v-if="t.name===\'byDept\'" :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无数据">',
      '        <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column prop="deptName" label="出院科室" min-width="140"></el-table-column>',
      '        <el-table-column prop="deptCode" label="科室编码" width="120"></el-table-column>',
      '        <el-table-column prop="caseCount" label="病案数" width="90" align="right"></el-table-column>',
      '        <el-table-column prop="cataloged" label="已编目" width="90" align="right"></el-table-column>',
      '        <el-table-column prop="audited" label="已审核" width="90" align="right"></el-table-column>',
      '        <el-table-column prop="locked" label="已锁定" width="90" align="right"></el-table-column>',
      '        <el-table-column prop="avgLos" label="平均住院日" width="110" align="right"></el-table-column>',
      '        <el-table-column prop="avgQuality" label="平均质量评分" width="120" align="right"></el-table-column>',
      '      </el-table>',
      /* ---- 诊断 TOP ---- */
      '      <el-table v-if="t.name===\'diagTop\'" :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无数据(需已编目病案有主要诊断)">',
      '        <el-table-column type="index" :index="seqNo" label="排名" width="60" align="center"></el-table-column>',
      '        <el-table-column prop="diagCode" label="诊断编码" width="140"></el-table-column>',
      '        <el-table-column prop="diagName" label="诊断名称" min-width="240"></el-table-column>',
      '        <el-table-column prop="caseCount" label="例数" width="90" align="right"></el-table-column>',
      '      </el-table>',
      /* ---- 手术 TOP ---- */
      '      <el-table v-if="t.name===\'operTop\'" :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无数据(需手术明细有国临版编码)">',
      '        <el-table-column type="index" :index="seqNo" label="排名" width="60" align="center"></el-table-column>',
      '        <el-table-column prop="operCode" label="手术编码" width="140"></el-table-column>',
      '        <el-table-column prop="operName" label="手术名称" min-width="240"></el-table-column>',
      '        <el-table-column prop="caseCount" label="例数" width="90" align="right"></el-table-column>',
      '        <el-table-column prop="mainCount" label="主手术例数" width="110" align="right"></el-table-column>',
      '      </el-table>',
      /* ---- 住院日分布 ---- */
      '      <el-table v-if="t.name===\'losDist\'" :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无数据">',
      '        <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column label="住院日区间" min-width="160"><template #default="s">{{ bucketText(s.row.bucket) }}</template></el-table-column>',
      '        <el-table-column prop="caseCount" label="例数" width="120" align="right"></el-table-column>',
      '      </el-table>',
      /* ---- 质量分布 ---- */
      '      <el-table v-if="t.name===\'qualityDist\'" :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无数据(需有质量评分)">',
      '        <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column label="质量等级" min-width="180"><template #default="s">{{ bucketText(s.row.bucket) }}</template></el-table-column>',
      '        <el-table-column prop="caseCount" label="例数" width="120" align="right"></el-table-column>',
      '        <el-table-column prop="avgScore" label="区间均分" width="120" align="right"></el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };
})();
