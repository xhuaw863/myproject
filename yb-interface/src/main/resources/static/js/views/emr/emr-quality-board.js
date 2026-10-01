/* 病历质控评分看板: HIS.views.EmrQualityBoard —— 复用工作台(dashboard.js)ECharts 风格, 汇总质控评分与科室排名。
 * 后端契约: GET /api/his/emr/quality/report?orgId&startDate&endDate → {totalRecords,evaluatedCount,avgScore,unqualifiedCount,unqualifiedRate,fatalCount,scoreDistribution{},problemDistribution{},deptRanking[]}
 * 评分语义: 100分起扣, 不合格线60, severity=3 一票否决计 fatal。注册: HIS.views.EmrQualityBoard(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return fmtDate(d); }

  var PALETTE = (HIS.theme && HIS.theme.palette) || ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399'];

  HIS.views.EmrQualityBoard = {
    data: function () {
      return {
        loading: false, report: null,
        dateRange: [daysAgo(29), today()],
        orgId: '',
        scoreChart: null, problemChart: null, _onResize: null
      };
    },
    computed: {
      kpis: function () {
        var r = this.report || {};
        return [
          { icon: '📚', tone: 'blue', title: '病历总数', num: r.totalRecords == null ? '—' : r.totalRecords, sub: '份' },
          { icon: '✅', tone: 'green', title: '已评分', num: r.evaluatedCount == null ? '—' : r.evaluatedCount, sub: '份' },
          { icon: '⭐', tone: 'orange', title: '平均分', num: r.avgScore == null ? '—' : Number(r.avgScore).toFixed(1), sub: '分' },
          { icon: '⚠️', tone: 'red', title: '不合格', num: r.unqualifiedCount == null ? '—' : r.unqualifiedCount, sub: (r.unqualifiedRate == null ? '—' : Number(r.unqualifiedRate).toFixed(1) + '%') },
          { icon: '⛔', tone: 'red', title: '一票否决', num: r.fatalCount == null ? '—' : r.fatalCount, sub: '份' }
        ];
      },
      deptRanking: function () { return (this.report && this.report.deptRanking) || []; },
      scoreRows: function () {
        var d = (this.report && this.report.scoreDistribution) || {};
        return Object.keys(d).map(function (k) { return { name: k, value: d[k] }; });
      },
      problemRows: function () {
        var d = (this.report && this.report.problemDistribution) || {};
        return Object.keys(d).map(function (k) { return { name: k, value: d[k] }; });
      }
    },
    created: function () { this.load(); },
    mounted: function () {
      var vm = this;
      vm.$nextTick(function () { vm.initCharts(); });
    },
    beforeUnmount: function () {
      var vm = this;
      window.removeEventListener('resize', vm._onResize);
      if (vm.scoreChart) { vm.scoreChart.dispose(); vm.scoreChart = null; }
      if (vm.problemChart) { vm.problemChart.dispose(); vm.problemChart = null; }
    },
    methods: {
      initCharts: function () {
        var vm = this;
        if (!window.echarts) { ElementPlus.ElMessage.warning('图表库(echarts)未加载, 仅显示卡片与排名'); return; }
        vm.scoreChart = echarts.init(vm.$refs.scoreChart, 'yb');
        vm.problemChart = echarts.init(vm.$refs.problemChart, 'yb');
        vm._onResize = function () { vm.scoreChart && vm.scoreChart.resize(); vm.problemChart && vm.problemChart.resize(); };
        window.addEventListener('resize', vm._onResize);
        vm.renderCharts();
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        var dr = vm.dateRange || [];
        var url = '/api/his/emr/quality/report?'
          + (dr[0] ? ('startDate=' + dr[0] + '&') : '')
          + (dr[1] ? ('endDate=' + dr[1] + '&') : '')
          + (String(vm.orgId || '').trim() ? ('orgId=' + encodeURIComponent(String(vm.orgId).trim())) : '');
        HIS.get(url).then(function (d) {
          vm.report = d || {};
          vm.$nextTick(function () { vm.renderCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      renderCharts: function () {
        var vm = this;
        if (vm.scoreChart) {
          var sr = vm.scoreRows;
          vm.scoreChart.setOption({
            color: PALETTE,
            tooltip: { trigger: 'item', formatter: function (p) { return p.name + '<br/>' + p.value + ' 份 (' + p.percent + '%)'; } },
            legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
            series: [{
              name: '分数段', type: 'pie', radius: ['42%', '66%'], center: ['50%', '44%'],
              itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
              label: { show: true, formatter: '{b}\n{c}' },
              data: sr.filter(function (x) { return x.value > 0; })
            }]
          });
        }
        if (vm.problemChart) {
          var pr = vm.problemRows;
          vm.problemChart.setOption({
            color: [HIS.theme.danger],
            tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
            grid: { left: 10, right: 24, top: 24, bottom: 10, containLabel: true },
            xAxis: { type: 'value', minInterval: 1, axisLabel: { color: HIS.theme.ink3 }, splitLine: { lineStyle: { color: HIS.theme.split } } },
            yAxis: { type: 'category', data: pr.map(function (x) { return x.name; }), axisLabel: { color: HIS.theme.ink3 }, axisTick: { show: false } },
            series: [{ name: '问题数', type: 'bar', barMaxWidth: 18, data: pr.map(function (x) { return x.value; }), itemStyle: { borderRadius: [0, 4, 4, 0] }, label: { show: true, position: 'right', color: HIS.theme.danger, fontSize: 11 } }]
          });
        }
      }
    },
    template: [
      '<div>',
      '  <div class="page-card" style="display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;">',
      '    <div>',
      '      <div style="font-size:16px;font-weight:600;color:var(--yb-ink-1);">病历质控评分看板</div>',
      '      <div style="color:var(--yb-ink-2);font-size:13px;margin-top:6px;">住院病历四类规则评分汇总 · 分数段/问题类型分布 · 科室质控排名(100分起扣, 不合格线60)</div>',
      '    </div>',
      '    <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">',
      '      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" size="small" start-placeholder="开始" end-placeholder="结束" style="width:240px;"></el-date-picker>',
      '      <el-input v-model="orgId" placeholder="机构ID(牵头可选)" size="small" style="width:150px;"></el-input>',
      '      <el-button size="small" type="primary" :loading="loading" @click="load">查询</el-button>',
      '    </div>',
      '  </div>',
      '  <el-row :gutter="16" style="margin-top:14px;" v-loading="loading">',
      '    <el-col :span="4" v-for="c in kpis" :key="c.title">',
      '      <div class="ds-card" :class="\'tone-\' + c.tone">',
      '        <div class="ds-head"><div class="ds-ico">{{ c.icon }}</div><div class="ds-title">{{ c.title }}<span class="ds-sub">{{ c.sub }}</span></div></div>',
      '        <div class="ds-num">{{ c.num }}</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      '  <el-row :gutter="16" style="margin-top:14px;">',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card"><template #header><div class="chart-hd"><span class="t">分数段分布</span></div></template><div ref="scoreChart" class="chart-box"></div></el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card"><template #header><div class="chart-hd"><span class="t">问题类型分布</span></div></template><div ref="problemChart" class="chart-box"></div></el-card>',
      '    </el-col>',
      '  </el-row>',
      '  <el-card shadow="never" style="margin-top:14px;">',
      '    <template #header><div class="chart-hd"><span class="t">科室质控排名</span></div></template>',
      '    <el-table :data="deptRanking" size="small" border stripe max-height="440" empty-text="区间内无病历数据">',
      '      <el-table-column prop="rank" label="名次" width="70"></el-table-column>',
      '      <el-table-column prop="deptName" label="科室" min-width="140"></el-table-column>',
      '      <el-table-column prop="recordCount" label="病历数" width="100"></el-table-column>',
      '      <el-table-column prop="evaluatedCount" label="已评分" width="100"></el-table-column>',
      '      <el-table-column label="平均分" width="120">',
      '        <template #default="s"><b :style="{color: s.row.avgScore!=null && Number(s.row.avgScore)<60 ? \'var(--yb-danger)\' : \'var(--yb-success)\'}">{{ s.row.avgScore == null ? "—" : Number(s.row.avgScore).toFixed(1) }}</b></template>',
      '      </el-table-column>',
      '    </el-table>',
      '  </el-card>',
      '</div>'
    ].join('\n')
  };
})();
