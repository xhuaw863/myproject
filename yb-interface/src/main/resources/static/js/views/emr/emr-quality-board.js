/* 病历质控评分看板: HIS.views.EmrQualityBoard —— 复用工作台(dashboard.js)ECharts 风格, 汇总质控评分与科室排名。
 * 后端契约: GET /api/his/emr/quality/report?orgId&startDate&endDate → {totalRecords,evaluatedCount,avgScore,unqualifiedCount,unqualifiedRate,fatalCount,scoreDistribution{},problemDistribution{},deptRanking[]}
 *           GET /api/his/emr/quality/defect-stats?month=yyyy-MM → {month,total,byType{},bySeverity{},byDept[]}
 * 评分语义: 100分起扣, 不合格线60, severity=3 一票否决计 fatal。
 * P5b-4 增强: 甲乙丙等级分布(按评分段折算: 甲≥90/乙80-89/丙<80) + 本月/上月环比(均分/合格率/甲级率)
 *             + 导出质控报告CSV(Blob 下载, 含全院指标与科室排名)。
 * 注册: HIS.views.EmrQualityBoard(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return fmtDate(d); }

  /* 看板局部样式(一次性注入, eqb- 前缀, 其余复用全局 ds-card/chart-card 令牌) */
  (function ensureStyles() {
    if (document.getElementById('emr-quality-board-style')) { return; }
    var st = document.createElement('style');
    st.id = 'emr-quality-board-style';
    st.textContent = [
      '.eqb-grade-row { display:flex; align-items:center; gap:10px; padding:7px 0; }',
      '.eqb-grade-label { flex:none; width:118px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.eqb-grade-track { flex:1; height:14px; background:var(--yb-surface-2,#f7f9fc); border-radius:7px; overflow:hidden; }',
      '.eqb-grade-fill { height:100%; min-width:2px; border-radius:7px; transition:width .4s ease; }',
      '.eqb-grade-num { flex:none; min-width:96px; text-align:right; font-size:12px; color:var(--yb-ink-2,#3d4a5c); font-variant-numeric:tabular-nums; }',
      '.eqb-trend { font-weight:600; font-variant-numeric:tabular-nums; }',
      '.eqb-trend.up { color:var(--yb-success,#3c862d); }',
      '.eqb-trend.down { color:var(--yb-danger,#c74f4f); }',
      '.eqb-trend.flat { color:var(--yb-ink-4,#8994a5); font-weight:400; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  var PALETTE = (HIS.theme && HIS.theme.palette) || ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399'];

  HIS.views.EmrQualityBoard = {
    data: function () {
      return {
        loading: false, report: null,
        dateRange: [daysAgo(29), today()],
        orgId: '',
        /* P5b-4: 本月/上月自然月报告(环比) + 当月缺陷统计(甲级率/导出CSV用) */
        monthReport: null,
        prevMonthReport: null,
        defectStats: null,
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
      },
      /* P5b-4 甲乙丙等级分布: 后端暂无等级评定报表, 按质控评分分数段折算
       * (甲≥90 / 乙80-89 / 丙<80; 与归档四维加权口径同向, 供趋势参考, 页面已标注口径) */
      gradeBars: function () {
        var dist = (this.report && this.report.scoreDistribution) || {};
        var jia = Number(dist['90-100']) || 0;
        var yi = Number(dist['80-89']) || 0;
        var bing = (Number(dist['60-79']) || 0) + (Number(dist['60以下']) || 0);
        var total = jia + yi + bing;
        function pct(v) { return total > 0 ? Math.round(v * 1000 / total) / 10 : 0; }
        return [
          { key: '甲', label: '甲级 ≥90分', count: jia, percent: pct(jia), color: 'var(--yb-success,#3c862d)' },
          { key: '乙', label: '乙级 80-89分', count: yi, percent: pct(yi), color: 'var(--yb-warning,#a26b1b)' },
          { key: '丙', label: '丙级 <80分', count: bing, percent: pct(bing), color: 'var(--yb-danger,#c74f4f)' }
        ];
      },
      /* 甲级率%(甲级份数/已评分; 环比与导出共用; 未评分返回 null) */
      gradeJiaRate: function () {
        var dist = (this.report && this.report.scoreDistribution) || {};
        var evaluated = Number(this.report && this.report.evaluatedCount) || 0;
        if (evaluated <= 0) { return null; }
        return (Number(dist['90-100']) || 0) * 100 / evaluated;
      },
      /* 本月/上月月份标签(环比表头) */
      monthLabels: function () {
        var d = new Date(), p = new Date(); p.setMonth(p.getMonth() - 1);
        return { cur: d.getFullYear() + '-' + pad2(d.getMonth() + 1), prev: p.getFullYear() + '-' + pad2(p.getMonth() + 1) };
      },
      /* 月度环比行: 平均分/合格率/甲级率, 含变化箭头(升绿/降红/平灰) */
      trendRows: function () {
        var cur = this.monthReport || {}, prev = this.prevMonthReport || {};
        function passRate(r) {
          var ev = Number(r.evaluatedCount) || 0;
          if (ev <= 0) { return null; }
          return (ev - (Number(r.unqualifiedCount) || 0)) * 100 / ev;
        }
        function jiaRate(r) {
          var ev = Number(r.evaluatedCount) || 0;
          if (ev <= 0) { return null; }
          return (Number((r.scoreDistribution || {})['90-100']) || 0) * 100 / ev;
        }
        var rows = [
          { name: '平均分', cur: cur.avgScore == null ? null : Number(cur.avgScore), prev: prev.avgScore == null ? null : Number(prev.avgScore), digits: 1, suffix: '' },
          { name: '合格率', cur: passRate(cur), prev: passRate(prev), digits: 1, suffix: '%' },
          { name: '甲级率', cur: jiaRate(cur), prev: jiaRate(prev), digits: 1, suffix: '%' }
        ];
        rows.forEach(function (r) {
          r.curText = r.cur == null ? '—' : r.cur.toFixed(r.digits) + r.suffix;
          r.prevText = r.prev == null ? '—' : r.prev.toFixed(r.digits) + r.suffix;
          r.diff = (r.cur == null || r.prev == null) ? null : Number((r.cur - r.prev).toFixed(1));
          r.diffText = r.diff == null ? '—' : (r.diff > 0 ? '↑' : (r.diff < 0 ? '↓' : '—'));
          r.diffCls = r.diff == null ? 'flat' : (r.diff > 0 ? 'up' : (r.diff < 0 ? 'down' : 'flat'));
        });
        return rows;
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
          vm.loadTrend();
          vm.$nextTick(function () { vm.renderCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* P5b-4: 本月/上月自然月报告 + 当月缺陷统计(并行, 失败降级为空, 不阻断主看板) */
      loadTrend: function () {
        var vm = this;
        var org = String(vm.orgId || '').trim();
        function reportUrl(start, end) {
          return '/api/his/emr/quality/report?startDate=' + start + '&endDate=' + end
            + (org ? ('&orgId=' + encodeURIComponent(org)) : '');
        }
        var now = new Date();
        var curStart = now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-01';
        var prevDate = new Date(now.getFullYear(), now.getMonth() - 1, 1);
        var prevStart = prevDate.getFullYear() + '-' + pad2(prevDate.getMonth() + 1) + '-01';
        var prevEnd = fmtDate(new Date(prevDate.getFullYear(), prevDate.getMonth() + 1, 0));
        var monthParam = now.getFullYear() + '-' + pad2(now.getMonth() + 1);
        global.Promise.all([
          HIS.get(reportUrl(curStart, today())).catch(function () { return null; }),
          HIS.get(reportUrl(prevStart, prevEnd)).catch(function () { return null; }),
          HIS.get('/api/his/emr/quality/defect-stats?month=' + monthParam).catch(function () { return null; })
        ]).then(function (rs) {
          vm.monthReport = rs[0] || null;
          vm.prevMonthReport = rs[1] || null;
          vm.defectStats = rs[2] || null;
        });
      },
      /* 导出质控报告CSV(\ufeff BOM 防 Excel 中文乱码; 含全院指标 + 科室排名 + 当月缺陷数) */
      exportCsv: function () {
        var vm = this;
        var r = vm.report || {};
        var dr = vm.dateRange || [];
        var defByDept = {};
        ((vm.defectStats && vm.defectStats.byDept) || []).forEach(function (x) {
          if (x && x.deptId != null) { defByDept[String(x.deptId)] = x.count; }
        });
        var jr = vm.gradeJiaRate;
        var rows = [
          ['病历质控评分报告'],
          ['导出时间', new Date().toLocaleString()],
          ['统计区间', (dr[0] || '-') + ' ~ ' + (dr[1] || '-')],
          ['机构', String(vm.orgId || '').trim() || '全院(当前租户)'],
          [],
          ['全院指标'],
          ['病历总数', r.totalRecords == null ? '' : r.totalRecords],
          ['已评分', r.evaluatedCount == null ? '' : r.evaluatedCount],
          ['平均分', r.avgScore == null ? '' : Number(r.avgScore).toFixed(1)],
          ['不合格数', r.unqualifiedCount == null ? '' : r.unqualifiedCount],
          ['一票否决', r.fatalCount == null ? '' : r.fatalCount],
          ['甲级率(按评分折算)', jr == null ? '' : jr.toFixed(1) + '%'],
          ['当月缺陷总数', vm.defectStats && vm.defectStats.total != null ? vm.defectStats.total : ''],
          [],
          ['科室排名'],
          ['名次', '科室', '病历数', '已评分', '平均分', '当月缺陷数']
        ];
        (vm.deptRanking || []).forEach(function (d) {
          rows.push([
            d.rank == null ? '' : d.rank,
            d.deptName || '',
            d.recordCount == null ? '' : d.recordCount,
            d.evaluatedCount == null ? '' : d.evaluatedCount,
            d.avgScore == null ? '' : Number(d.avgScore).toFixed(1),
            defByDept[String(d.deptId)] != null ? defByDept[String(d.deptId)] : 0
          ]);
        });
        var csv = '\ufeff' + rows.map(function (row) {
          return row.map(function (cell) {
            var s = cell == null ? '' : String(cell);
            return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
          }).join(',');
        }).join('\r\n');
        var blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = '病历质控报告_' + (dr[0] || '') + '_' + (dr[1] || today()) + '.csv';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
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
      '      <el-button size="small" :disabled="!report" @click="exportCsv">导出质控报告</el-button>',
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
      '      <el-card shadow="never" class="chart-card">',
      '        <template #header><div class="chart-hd"><span class="t">甲乙丙等级分布</span><span style="font-size:11px;color:var(--yb-ink-4,#8994a5);">按质控评分折算: 甲≥90 / 乙80-89 / 丙<80</span></div></template>',
      '        <div class="eqb-grade-row" v-for="g in gradeBars" :key="g.key">',
      '          <span class="eqb-grade-label">{{ g.label }}</span>',
      '          <div class="eqb-grade-track"><div class="eqb-grade-fill" :style="{ width: g.percent + \'%\', background: g.color }"></div></div>',
      '          <span class="eqb-grade-num">{{ g.count }} 份 · {{ g.percent }}%</span>',
      '        </div>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card">',
      '        <template #header><div class="chart-hd"><span class="t">月度环比</span><span style="font-size:11px;color:var(--yb-ink-4,#8994a5);">{{ monthLabels.cur }} vs {{ monthLabels.prev }}(自然月)</span></div></template>',
      '        <el-table :data="trendRows" size="small" border :show-header="true">',
      '          <el-table-column prop="name" label="指标" width="90"></el-table-column>',
      '          <el-table-column label="本月" width="100"><template #default="s"><b>{{ s.row.curText }}</b></template></el-table-column>',
      '          <el-table-column label="上月" width="100"><template #default="s">{{ s.row.prevText }}</template></el-table-column>',
      '          <el-table-column label="环比"><template #default="s"><span class="eqb-trend" :class="s.row.diffCls">{{ s.row.diffText }}<template v-if="s.row.diff != null"> {{ Math.abs(s.row.diff).toFixed(1) }}</template></span></template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
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
