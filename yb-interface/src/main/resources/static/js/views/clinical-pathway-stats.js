/* ============================================================================
 * 临床路径统计质控(ClinicalPathwayStats) — 对标专业路径系统质控模块六维分析
 * 后端契约(/api/his/pathway/stats, 机构隔离 scopeOrgId, 全只读):
 *   GET /overview   驾驶舱: inPathwayCount/todayEnterCount/todayTaskCount/overdueTaskCount/
 *                   varianceMonthCount/exitMonthCount/monthRates{overall}/activePatients[]/
 *                   recentVariances[]/recentExits[]
 *   GET /rates      四大率: from/to/overall{denominator,entered,enterRate,completeRate,exitRate,
 *                   caseVarianceRate,itemVarianceRate,...}/byDept[]/byDoctor[]/byDisease[]/
 *                   doctorEnterRank[]/deptCompleteRank[] (行含 dimName)
 *   GET /trend      月度趋势: months[]{ym,enterCount,completedCount,exitedCount,completeRate,exitRate,caseVarianceRate}
 *   GET /variance-pareto  变异柏拉图: items[]{code,name,cnt,rate,cumulativeRate}/topDiseases[]{pathwayName,varianceCount}
 *   GET /exit-pareto      退出柏拉图: items[]{code,name,cnt,rate,cumulativeRate}
 *   GET /efficiency 效率费用: pathwayFinished/nonPathwaySameDisease{caseCount,avgLos,avgCost}/
 *                   templateStandard{stdLos,stdCost}/diff{...}/standardCompliance{totalCnt,complianceRate}
 *   GET /coverage   病种覆盖: enabledTemplateCount/coveredDeptCount/usedTemplateCount/diseases[]
 *                   {pathwayName,diseaseName,deptName,enteredCount,denominator,coverageRate,varianceExecCount}
 *   GET /detail     病例明细: {total,page,size,records[]{inpNo,patientName,deptName,doctorName,pathwayName,
 *                   startDate,endDate,status,currentDay,varianceCount,losDays,totalCost,dischargeDate}}
 *   GET /export     九Sheet xlsx(HIS.download 直接落盘)
 * 口径: 率值后端 0-1 小数(前端 ×100); 出院窗口 from/to=yyyy-MM-dd, 缺省近90天; 趋势缺省近12个月。
 * 注册: HIS.views.ClinicalPathwayStats(菜单 pathway-stats, comp 同名)
 * 图表: echarts.init(el,'yb') + 隐藏页签宽度0故 mountChart 带重试; resize 统一; 卸载 dispose。
 * 视觉: 沿用 clinical-pathway.js 的 --yb-* 令牌族, 类前缀 cps-*。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var T = HIS.theme || {};

  /* ===== 常量映射 ===== */
  var INSTANCE_STATUS = { 1: '进行中', 2: '已完成', 3: '已退出', 4: '暂停' };
  var INSTANCE_STATUS_TYPE = { 1: 'success', 2: 'info', 3: 'danger', 4: 'warning' };

  /* ===== 工具 ===== */
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function lastNDays(n) {
    var end = new Date(); var start = new Date();
    start.setDate(start.getDate() - (n - 1));
    return [fmtDate(start), fmtDate(end)];
  }
  function toNum(v) { var n = Number(v); return isFinite(n) ? n : 0; }
  function fmtNum(v, digits) {
    return toNum(v).toLocaleString('zh-CN', digits == null ? {} : { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }
  function fmtMoney(v) {
    return toNum(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function pct100(v) { return Math.round(toNum(v) * 1000) / 10; }
  function pctText(v) { return v == null ? '-' : pct100(v).toFixed(1) + '%'; }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }

  /* ===== 图表键(统一 resize/dispose) ===== */
  var CHART_KEYS = ['trendChart', 'varParetoChart', 'exitParetoChart', 'varDiseaseChart',
    'effLosChart', 'effCostChart', 'rateBarChart', 'rankChart', 'coverageChart'];

  /* ---- 私有样式一次性注入(类前缀 cps-*, 沿用 cp/sr 视觉基因) ---- */
  (function ensureStyles() {
    if (document.getElementById('clinical-pathway-stats-css')) { return; }
    var st = document.createElement('style');
    st.id = 'clinical-pathway-stats-css';
    st.textContent = [
      '.cps-root { min-height:calc(100vh - 88px); display:flex; flex-direction:column; }',
      '.cps-caption { flex:none; display:flex; align-items:baseline; gap:10px; margin-bottom:10px; }',
      '.cps-caption .t { font-size:var(--yb-fs-xl); font-weight:700; letter-spacing:var(--yb-tracking-tight); color:var(--yb-ink-1); border-left:3px solid var(--yb-brand); padding-left:11px; }',
      '.cps-caption .hint { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }',
      '.cps-filter { display:flex; gap:10px; align-items:center; flex-wrap:wrap; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:12px 16px; box-shadow:var(--yb-sh-1); }',
      '.cps-filter .spacer { flex:1; }',
      '.cps-kpis { display:grid; grid-template-columns:repeat(6,1fr); gap:12px; margin:14px 0; }',
      '.cps-kpis.cols-4 { grid-template-columns:repeat(4,1fr); }',
      '.cps-kpi { position:relative; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px 20px; box-shadow:var(--yb-sh-1); overflow:hidden; animation:cps-in .3s var(--yb-ease) both; }',
      '.cps-kpi::before { content:""; position:absolute; left:0; top:0; bottom:0; width:3px; background:var(--yb-brand); opacity:.9; }',
      '.cps-kpi .num { font-size:26px; font-weight:700; line-height:1.12; color:var(--yb-brand); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); letter-spacing:-.02em; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.cps-kpi .lbl { color:var(--yb-ink-3); font-size:12px; margin-top:5px; }',
      '.cps-kpi.is-warn::before { background:var(--yb-warning); }',
      '.cps-kpi.is-warn .num { color:var(--yb-warning); }',
      '.cps-kpi.is-danger::before { background:var(--yb-danger); }',
      '.cps-kpi.is-danger .num { color:var(--yb-danger); }',
      '.cps-kpi.is-ok::before { background:var(--yb-success); }',
      '.cps-kpi.is-ok .num { color:var(--yb-success); }',
      '@keyframes cps-in { from { opacity:0; transform:translateY(6px); } to { opacity:1; transform:none; } }',
      '@media (prefers-reduced-motion: reduce) { .cps-kpi { animation:none; } }',
      '.cps-card { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px; box-shadow:var(--yb-sh-1); margin-bottom:14px; }',
      '.cps-card-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); display:flex; align-items:center; gap:8px; }',
      '.cps-chart { width:100%; height:300px; }',
      '.cps-cols { display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-bottom:14px; }',
      '.cps-cols > .cps-card { margin-bottom:0; }',
      '.cps-note { font-size:12px; color:var(--yb-ink-4); font-weight:400; }',
      '.cps-money { font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.cps-rate-warn { color:var(--yb-danger); font-weight:600; }',
      '.cps-daybar { display:inline-block; vertical-align:middle; width:90px; margin-right:8px; }',
      '@media (max-width:1280px) { .cps-kpis { grid-template-columns:repeat(3,1fr); } .cps-cols { grid-template-columns:1fr; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  function ensureStyle() {
    if (document.getElementById('clinical-pathway-stats-css')) { return; }
    /* 兜底: 极端情况下历史丢函数, created/mounted 双调 */
    (function () {
      var st = document.createElement('style');
      st.id = 'clinical-pathway-stats-css';
      st.textContent = '.cps-root { min-height:calc(100vh - 88px); }';
      document.head.appendChild(st);
    })();
  }

  /* ========================================================================
   * ClinicalPathwayStats 路径统计质控(六页签)
   * ====================================================================== */
  HIS.views.ClinicalPathwayStats = {
    name: 'ClinicalPathwayStats',
    data: function () {
      return {
        dateRange: lastNDays(90),
        trendRange: lastNDays(365),
        deptId: '',
        doctorId: '',
        templateId: '',
        statusFilter: '',
        depts: [],
        doctors: [],
        templates: [],
        activeTab: 'dashboard',

        dash: null, dashLoading: false, dashLoaded: false, dashTimer: null,

        rates: null, ratesLoading: false, ratesLoaded: false, rateDim: 'dept',
        trend: null, trendLoading: false, trendLoaded: false,

        varP: null, varPLoading: false, varPLoaded: false,
        exitP: null, exitPLoading: false, exitPLoaded: false,

        eff: null, effLoading: false, effLoaded: false,
        cov: null, covLoading: false, covLoaded: false,

        detail: { records: [], total: 0 }, detailPage: 1, detailSize: 20,
        detailLoading: false, detailLoaded: false,
        exporting: false,

        /* 图表实例(句柄存 data 以便模板引用与生命周期管理) */
        trendChart: null, varParetoChart: null, exitParetoChart: null, varDiseaseChart: null,
        effLosChart: null, effCostChart: null, rateBarChart: null, rankChart: null, coverageChart: null
      };
    },
    computed: {
      rateDimRows: function () {
        if (!this.rates) { return []; }
        var map = { dept: this.rates.byDept, doctor: this.rates.byDoctor, disease: this.rates.byDisease };
        return map[this.rateDim] || [];
      },
      rateOverall: function () { return this.rates ? (this.rates.overall || {}) : {}; },
      monthOverall: function () {
        var mr = this.dash && this.dash.monthRates ? this.dash.monthRates.overall : null;
        return mr || {};
      },
      dashKpis: function () {
        var d = this.dash || {};
        return [
          { num: fmtNum(d.inPathwayCount), lbl: '在径患者(进行中/暂停)', cls: '' },
          { num: fmtNum(d.todayEnterCount), lbl: '今日入径', cls: '' },
          { num: fmtNum(d.todayTaskCount), lbl: '今日应执行任务', cls: '' },
          { num: fmtNum(d.overdueTaskCount), lbl: '逾期未处理任务', cls: toNum(d.overdueTaskCount) > 0 ? 'is-warn' : 'is-ok' },
          { num: fmtNum(d.varianceMonthCount), lbl: '本月变异记录', cls: toNum(d.varianceMonthCount) > 0 ? 'is-danger' : '' },
          { num: fmtNum(d.exitMonthCount), lbl: '本月退出例数', cls: '' }
        ];
      },
      monthRateKpis: function () {
        var m = this.monthOverall;
        return [
          { num: pctText(m.enterRate), lbl: '本月入径率', cls: '' },
          { num: pctText(m.completeRate), lbl: '本月完成率', cls: 'is-ok' },
          { num: pctText(m.exitRate), lbl: '本月退出率', cls: toNum(m.exitRate) > 0.1 ? 'is-danger' : '' },
          { num: pctText(m.caseVarianceRate), lbl: '本月病例变异率', cls: toNum(m.caseVarianceRate) > 0.2 ? 'is-warn' : '' }
        ];
      },
      effKpis: function () {
        var e = this.eff || {};
        var f = e.pathwayFinished || {}; var nf = e.nonPathwaySameDisease || {};
        var std = e.templateStandard || {}; var c = e.standardCompliance || {};
        return [
          { num: fmtNum(f.avgLos, 1), lbl: '入径完成组 平均住院日(天)', cls: '' },
          { num: fmtNum(nf.avgLos, 1), lbl: '非入径同病种 平均住院日(天)', cls: '' },
          { num: fmtNum(std.stdLos, 1), lbl: '模板标准 住院日(加权)', cls: '' },
          { num: '¥' + fmtMoney(f.avgCost), lbl: '入径完成组 例均费用', cls: '' },
          { num: '¥' + fmtMoney(nf.avgCost), lbl: '非入径同病种 例均费用', cls: '' },
          { num: pctText(c.complianceRate), lbl: '标准达标符合率(住院日且费用)', cls: toNum(c.complianceRate) < 0.7 ? 'is-warn' : 'is-ok' }
        ];
      },
      covKpis: function () {
        var c = this.cov || {};
        return [
          { num: fmtNum(c.enabledTemplateCount), lbl: '启用路径模板', cls: '' },
          { num: fmtNum(c.coveredDeptCount), lbl: '模板覆盖科室', cls: '' },
          { num: fmtNum(c.usedTemplateCount), lbl: '期内实际使用模板', cls: '' }
        ];
      }
    },
    watch: {
      rateDim: function () { var vm = this; vm.$nextTick(function () { vm.renderRateCharts(); }); }
    },

    methods: {
      toNum: toNum, fmtNum: fmtNum, fmtMoney: fmtMoney, pct100: pct100, pctText: pctText, orDash: orDash,
      statusText: function (v) { return INSTANCE_STATUS[toNum(v)] || '-'; },
      statusType: function (v) { return INSTANCE_STATUS_TYPE[toNum(v)] || ''; },

      /* ===== 参数与引用 ===== */
      baseParams: function (range) {
        var p = new URLSearchParams();
        var r = range || this.dateRange;
        if (r && r[0]) { p.append('from', r[0]); }
        if (r && r[1]) { p.append('to', r[1]); }
        if (this.deptId !== '' && this.deptId != null) { p.append('deptId', this.deptId); }
        if (this.doctorId !== '' && this.doctorId != null) { p.append('doctorId', this.doctorId); }
        if (this.templateId !== '' && this.templateId != null) { p.append('templateId', this.templateId); }
        return p;
      },
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.depts = l || []; }).catch(function () { vm.depts = []; });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false')
          .then(function (l) { vm.doctors = l || []; }).catch(function () { vm.doctors = []; });
        HIS.get('/api/his/pathway/template/list?status=1&page=1&size=200')
          .then(function (d) { vm.templates = (d && d.records) || []; }).catch(function () { vm.templates = []; });
      },
      search: function () {
        var t = this.activeTab;
        this.ratesLoaded = false; this.trendLoaded = false; this.varPLoaded = false;
        this.exitPLoaded = false; this.effLoaded = false; this.covLoaded = false; this.detailLoaded = false;
        this.dashLoaded = false;
        if (t === 'dashboard') { this.loadDash(); } else { this.loadActive(true); }
      },
      onTabChange: function (name) {
        if (name) { this.activeTab = name; }
        this.loadActive(false);
      },
      loadActive: function (force) {
        var loaders = { dashboard: 'loadDash', rates: 'loadRates', variance: 'loadVariance',
          efficiency: 'loadEfficiency', coverage: 'loadCoverage', detail: 'loadDetail' };
        var flags = { dashboard: 'dashLoaded', rates: 'ratesLoaded', variance: 'varPLoaded',
          efficiency: 'effLoaded', coverage: 'covLoaded', detail: 'detailLoaded' };
        var tab = this.activeTab;
        if (loaders[tab] && (force || !this[flags[tab]])) {
          if (tab === 'rates') { this.loadRates(); } else { this[loaders[tab]](); }
        }
      },

      /* ===== Tab1 管理驾驶舱(30s 静默自刷新) ===== */
      loadDash: function () {
        var vm = this; vm.dashLoading = !vm.dashLoaded;
        HIS.get('/api/his/pathway/stats/overview').then(function (d) {
          vm.dash = d || {};
          vm.dashLoaded = true;
        }).catch(HIS.notifyError).finally(function () { vm.dashLoading = false; });
      },
      startDashTimer: function () {
        var vm = this;
        vm.stopDashTimer();
        vm.dashTimer = setInterval(function () {
          var el = document.querySelector('.cps-root');
          if (!el) { vm.stopDashTimer(); return; }
          if (vm.activeTab === 'dashboard' && !document.hidden) {
            HIS.get('/api/his/pathway/stats/overview').then(function (d) { vm.dash = d || {}; }).catch(function () { /* 静默 */ });
          }
        }, 30000);
      },
      stopDashTimer: function () { if (this.dashTimer) { clearInterval(this.dashTimer); this.dashTimer = null; } },

      /* ===== Tab2 质控指标(四大率+趋势+排行) ===== */
      loadRates: function () {
        var vm = this; vm.ratesLoading = true;
        var qs = vm.baseParams().toString();
        HIS.get('/api/his/pathway/stats/rates?' + qs).then(function (d) {
          vm.rates = d || {};
          vm.ratesLoaded = true;
          vm.$nextTick(function () { vm.renderRateCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.ratesLoading = false; });
        if (!vm.trendLoaded) { vm.loadTrend(); }
      },
      loadTrend: function () {
        var vm = this; vm.trendLoading = true;
        HIS.get('/api/his/pathway/stats/trend?' + vm.baseParams(vm.trendRange).toString()).then(function (d) {
          vm.trend = d || {};
          vm.trendLoaded = true;
          vm.$nextTick(function () { vm.renderTrendChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.trendLoading = false; });
      },
      renderTrendChart: function () {
        var vm = this;
        var months = (vm.trend && vm.trend.months) || [];
        if (!months.length) { return; }
        vm.mountChart('trendChart', 'trendEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            legend: T.legend ? T.legend({ top: 0 }) : { top: 0 },
            grid: { left: 8, right: 42, top: 34, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: months.map(function (r) { return r.ym; }) })
              : { type: 'category', data: months.map(function (r) { return r.ym; }) },
            yAxis: [
              T.valAxis ? T.valAxis({ type: 'value', minInterval: 1, name: '例数' }) : { type: 'value', minInterval: 1 },
              { type: 'value', name: '率(%)', max: 100, axisLabel: { formatter: '{value}%' } }
            ],
            series: [
              { name: '入径例数', type: 'bar', barMaxWidth: 26, itemStyle: { color: T.brand || '#1a5c9e', borderRadius: [3, 3, 0, 0] },
                data: months.map(function (r) { return toNum(r.enterCount); }) },
              { name: '完成率', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 6,
                lineStyle: { color: T.success || '#3fa35a', width: 2 }, itemStyle: { color: T.success || '#3fa35a' },
                data: months.map(function (r) { return pct100(r.completeRate); }) },
              { name: '病例变异率', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 6,
                lineStyle: { color: T.danger || '#d64541', width: 2 }, itemStyle: { color: T.danger || '#d64541' },
                data: months.map(function (r) { return pct100(r.caseVarianceRate); }) }
            ]
          };
        });
      },
      renderRateCharts: function () {
        var vm = this;
        var rows = (vm.rateDimRows || []).filter(function (r) { return toNum(r.denominator) > 0 || toNum(r.instanceCount) > 0; });
        if (rows.length) {
          vm.mountChart('rateBarChart', 'rateBarEl', function () {
            var names = rows.map(function (r) { return r.dimName || '-'; });
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              legend: T.legend ? T.legend({ top: 0 }) : { top: 0 },
              grid: { left: 8, right: 18, top: 34, bottom: 4, containLabel: true },
              xAxis: T.catAxis ? T.catAxis({ type: 'category', data: names, axisLabel: { interval: 0, rotate: names.length > 6 ? 28 : 0 } })
                : { type: 'category', data: names },
              yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
              series: [
                { name: '入径率', type: 'bar', barMaxWidth: 18, itemStyle: { color: T.brand || '#1a5c9e', borderRadius: [3, 3, 0, 0] }, data: rows.map(function (r) { return pct100(r.enterRate); }) },
                { name: '完成率', type: 'bar', barMaxWidth: 18, itemStyle: { color: T.success || '#3fa35a', borderRadius: [3, 3, 0, 0] }, data: rows.map(function (r) { return pct100(r.completeRate); }) },
                { name: '退出率', type: 'bar', barMaxWidth: 18, itemStyle: { color: T.warning || '#e6a23c', borderRadius: [3, 3, 0, 0] }, data: rows.map(function (r) { return pct100(r.exitRate); }) },
                { name: '病例变异率', type: 'bar', barMaxWidth: 18, itemStyle: { color: T.danger || '#d64541', borderRadius: [3, 3, 0, 0] }, data: rows.map(function (r) { return pct100(r.caseVarianceRate); }) }
              ]
            };
          });
        }
        var rankSrc = vm.rateDim === 'doctor' ? (vm.rates && vm.rates.doctorEnterRank)
          : vm.rateDim === 'dept' ? (vm.rates && vm.rates.deptCompleteRank) : [];
        var rankKey = vm.rateDim === 'doctor' ? 'enterRate' : 'completeRate';
        var rankName = vm.rateDim === 'doctor' ? '医生入径率排行' : '科室完成率排行';
        if (rankSrc && rankSrc.length) {
          vm.mountChart('rankChart', 'rankEl', function () {
            var rows2 = rankSrc.slice(0, 12).slice().reverse();
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              grid: { left: 8, right: 34, top: 8, bottom: 4, containLabel: true },
              xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
              yAxis: T.catAxis ? T.catAxis({ type: 'category', data: rows2.map(function (r) { return r.dimName || '-'; }) })
                : { type: 'category', data: rows2.map(function (r) { return r.dimName || '-'; }) },
              series: [{ name: rankName, type: 'bar', barMaxWidth: 16,
                itemStyle: { color: T.link || '#2c78c7', borderRadius: [0, 3, 3, 0] },
                label: { show: true, position: 'right', fontSize: 10, color: T.ink3, formatter: function (p) { return p.value + '%'; } },
                data: rows2.map(function (r) { return pct100(r[rankKey]); }) }]
            };
          });
        }
      },

      /* ===== Tab3 变异与退出分析 ===== */
      loadVariance: function () {
        var vm = this;
        var qs = vm.baseParams().toString();
        if (!vm.varPLoaded) {
          vm.varPLoading = true;
          HIS.get('/api/his/pathway/stats/variance-pareto?' + qs).then(function (d) {
            vm.varP = d || {};
            vm.varPLoaded = true;
            vm.$nextTick(function () { vm.renderVarCharts(); });
          }).catch(HIS.notifyError).finally(function () { vm.varPLoading = false; });
        }
        if (!vm.exitPLoaded) {
          vm.exitPLoading = true;
          HIS.get('/api/his/pathway/stats/exit-pareto?' + qs).then(function (d) {
            vm.exitP = d || {};
            vm.exitPLoaded = true;
            vm.$nextTick(function () { vm.renderExitChart(); });
          }).catch(HIS.notifyError).finally(function () { vm.exitPLoading = false; });
        }
      },
      renderVarCharts: function () {
        var vm = this;
        var items = (vm.varP && vm.varP.items) || [];
        if (items.length) {
          vm.mountChart('varParetoChart', 'varParetoEl', function () {
            return paretoOption(items, T);
          });
        }
        var top = (vm.varP && vm.varP.topDiseases) || [];
        if (top.length) {
          vm.mountChart('varDiseaseChart', 'varDiseaseEl', function () {
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              grid: { left: 8, right: 30, top: 8, bottom: 4, containLabel: true },
              xAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
              yAxis: T.catAxis ? T.catAxis({ type: 'category', data: top.slice().reverse().map(function (r) { return r.pathwayName; }) })
                : { type: 'category', data: top.slice().reverse().map(function (r) { return r.pathwayName; }) },
              series: [{ name: '变异条数', type: 'bar', barMaxWidth: 16,
                itemStyle: { color: T.danger || '#d64541', borderRadius: [0, 3, 3, 0] },
                label: { show: true, position: 'right', fontSize: 10, color: T.ink3 },
                data: top.slice().reverse().map(function (r) { return toNum(r.varianceCount); }) }]
            };
          });
        }
      },
      renderExitChart: function () {
        var vm = this;
        var items = (vm.exitP && vm.exitP.items) || [];
        if (!items.length) { return; }
        vm.mountChart('exitParetoChart', 'exitParetoEl', function () {
          return paretoOption(items, T);
        });
      },

      /* ===== Tab4 效率与费用 ===== */
      loadEfficiency: function () {
        var vm = this; vm.effLoading = true;
        HIS.get('/api/his/pathway/stats/efficiency?' + vm.baseParams().toString()).then(function (d) {
          vm.eff = d || {};
          vm.effLoaded = true;
          vm.$nextTick(function () { vm.renderEffCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.effLoading = false; });
      },
      renderEffCharts: function () {
        var vm = this;
        var e = vm.eff || {};
        var groups = [
          { name: '入径完成组', f: e.pathwayFinished || {} },
          { name: '非入径同病种', f: e.nonPathwaySameDisease || {} },
          { name: '模板标准(加权)', f: { avgLos: (e.templateStandard || {}).stdLos, avgCost: (e.templateStandard || {}).stdCost } }
        ];
        vm.mountChart('effLosChart', 'effLosEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: groups.map(function (g) { return g.name; }) })
              : { type: 'category', data: groups.map(function (g) { return g.name; }) },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', name: '天' }) : { type: 'value' },
            series: [{ name: '平均住院日', type: 'bar', barMaxWidth: 44,
              itemStyle: { color: T.brand || '#1a5c9e', borderRadius: [3, 3, 0, 0] },
              label: { show: true, position: 'top', fontSize: 10, color: T.ink3 },
              data: groups.map(function (g) { return toNum(g.f.avgLos); }) }]
          };
        });
        vm.mountChart('effCostChart', 'effCostEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: groups.map(function (g) { return g.name; }) })
              : { type: 'category', data: groups.map(function (g) { return g.name; }) },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', name: '元' }) : { type: 'value' },
            series: [{ name: '例均费用', type: 'bar', barMaxWidth: 44,
              itemStyle: { color: T.gold || '#a26b1b', borderRadius: [3, 3, 0, 0] },
              label: { show: true, position: 'top', fontSize: 10, color: T.ink3 },
              data: groups.map(function (g) { return toNum(g.f.avgCost); }) }]
          };
        });
      },

      /* ===== Tab5 病种覆盖 ===== */
      loadCoverage: function () {
        var vm = this; vm.covLoading = true;
        HIS.get('/api/his/pathway/stats/coverage?' + vm.baseParams().toString()).then(function (d) {
          vm.cov = d || {};
          vm.covLoaded = true;
          vm.$nextTick(function () { vm.renderCoverageChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.covLoading = false; });
      },
      renderCoverageChart: function () {
        var vm = this;
        var rows = ((vm.cov && vm.cov.diseases) || []).filter(function (r) { return toNum(r.denominator) > 0; })
          .slice(0, 15);
        if (!rows.length) { return; }
        vm.mountChart('coverageChart', 'coverageEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis', formatter: function (ps) {
              var p = ps[0]; var r = rows[p.dataIndex];
              return r.pathwayName + '<br/>入径 ' + toNum(r.enteredCount) + ' / 出院 ' + toNum(r.denominator)
                + ' 例<br/>推广度 ' + pct100(r.coverageRate) + '%';
            } }) : { trigger: 'axis' },
            grid: { left: 8, right: 34, top: 8, bottom: 4, containLabel: true },
            xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
            yAxis: T.catAxis ? T.catAxis({ type: 'category', data: rows.slice().reverse().map(function (r) { return r.pathwayName; }) })
              : { type: 'category', data: rows.slice().reverse().map(function (r) { return r.pathwayName; }) },
            series: [{ name: '入径占其出院比', type: 'bar', barMaxWidth: 16,
              itemStyle: { color: T.teal || '#048671', borderRadius: [0, 3, 3, 0] },
              label: { show: true, position: 'right', fontSize: 10, color: T.ink3, formatter: function (p) { return p.value + '%'; } },
              data: rows.slice().reverse().map(function (r) { return pct100(r.coverageRate); }) }]
          };
        });
      },

      /* ===== Tab6 病例明细 ===== */
      loadDetail: function () {
        var vm = this; vm.detailLoading = true;
        var p = vm.baseParams();
        p.append('page', vm.detailPage); p.append('size', vm.detailSize);
        if (vm.statusFilter !== '' && vm.statusFilter != null) { p.append('status', vm.statusFilter); }
        HIS.get('/api/his/pathway/stats/detail?' + p.toString()).then(function (d) {
          d = d || {};
          vm.detail = { records: d.records || [], total: toNum(d.total) };
          vm.detailLoaded = true;
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      onDetailPage: function (page) { this.detailPage = page; this.loadDetail(); },

      /* ===== 导出(HIS.download 带 token 落盘) ===== */
      doExport: function () {
        var vm = this;
        if (vm.exporting) { return; }
        vm.exporting = true;
        var name = '临床路径统计质控_' + ((vm.dateRange && vm.dateRange[1]) || fmtDate(new Date())) + '.xlsx';
        HIS.download('/api/his/pathway/stats/export?' + vm.baseParams().toString(), name)
          .then(function () { HIS.notifySuccess('导出成功'); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },

      /* ===== 图表通用: echarts.init 'yb' + 隐藏页签宽度0重试 ===== */
      mountChart: function (prop, refName, buildOption) {
        var vm = this; var tries = 0;
        function tryInit() {
          var dom = vm.$refs[refName];
          if (!dom || !window.echarts) { return; }
          if (!dom.offsetWidth) { if (tries++ < 8) { setTimeout(tryInit, 150); } return; }
          if (vm[prop]) { vm[prop].dispose(); vm[prop] = null; }
          var chart = window.echarts.init(dom, 'yb');
          vm[prop] = chart;
          chart.setOption(buildOption());
        }
        tryInit();
      },
      onResize: function () {
        var vm = this;
        CHART_KEYS.forEach(function (k) { if (vm[k] && vm[k].resize) { vm[k].resize(); } });
      }
    },

    created: function () { ensureStyle(); },
    mounted: function () {
      ensureStyle();
      window.addEventListener('resize', this.onResize);
      this.loadRefs();
      this.loadDash();
      this.startDashTimer();
    },
    unmounted: function () {
      window.removeEventListener('resize', this.onResize);
      this.stopDashTimer();
      var vm = this;
      CHART_KEYS.forEach(function (k) { if (vm[k]) { vm[k].dispose(); vm[k] = null; } });
    },

    /* ==================== 模板 ==================== */
    template: [
      '<div class="cps-root">',
      '  <div class="cps-caption">',
      '    <span class="t">路径统计质控</span>',
      '    <span class="hint">四大率 / 变异退出柏拉图 / 效率费用对比 / 病种覆盖 / 实时监控 · 口径遵循《临床路径管理办法》病种质控习惯</span>',
      '  </div>',

      /* ---- 通用筛选栏 ---- */
      '  <div class="cps-filter">',
      '    <span class="cps-note" style="white-space:nowrap;">出院统计区间</span>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:236px" @change="search"></el-date-picker>',
      '    <el-select v-model="deptId" filterable clearable placeholder="全部科室" style="width:150px" @change="search">',
      '      <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="doctorId" filterable clearable placeholder="全部医生" style="width:150px" @change="search">',
      '      <el-option v-for="s in doctors" :key="s.id" :label="s.staffName" :value="s.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="templateId" filterable clearable placeholder="全部病种路径" style="width:190px" @change="search">',
      '      <el-option v-for="t in templates" :key="t.id" :label="t.pathwayName" :value="t.id"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <div class="spacer"></div>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '  </div>',

      '  <el-tabs v-model="activeTab" style="margin-top:12px;" @tab-change="onTabChange">',

      /* ==================== Tab1 管理驾驶舱 ==================== */
      '    <el-tab-pane label="管理驾驶舱" name="dashboard">',
      '      <div v-loading="dashLoading">',
      '        <div class="cps-kpis">',
      '          <div class="cps-kpi" v-for="(k, i) in dashKpis" :key="i" :class="k.cls">',
      '            <div class="num">{{ k.num }}</div><div class="lbl">{{ k.lbl }}</div>',
      '          </div>',
      '        </div>',
      '        <div class="cps-kpis cols-4" v-if="dash">',
      '          <div class="cps-kpi" v-for="(k, i) in monthRateKpis" :key="i" :class="k.cls">',
      '            <div class="num">{{ k.num }}</div><div class="lbl">{{ k.lbl }}</div>',
      '          </div>',
      '        </div>',
      '        <div class="cps-card">',
      '          <div class="cps-card-title">在径患者一览 <span class="cps-note">进行中/暂停, 实时(30秒自动刷新) · 共 {{ (dash && dash.activePatients) ? dash.activePatients.length : 0 }} 人</span></div>',
      '          <el-table :data="(dash && dash.activePatients) || []" size="small" max-height="330" empty-text="当前没有在径患者">',
      '            <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '            <el-table-column prop="inpNo" label="住院号" width="120"></el-table-column>',
      '            <el-table-column prop="deptName" label="科室" width="110"></el-table-column>',
      '            <el-table-column prop="doctorName" label="主管医生" width="95"></el-table-column>',
      '            <el-table-column prop="pathwayName" label="路径" min-width="150" show-overflow-tooltip></el-table-column>',
      '            <el-table-column label="进度" width="200">',
      '              <template #default="scope">',
      '                <el-progress class="cps-daybar" :percentage="scope.row.dayPercent || 0" :stroke-width="8" :show-text="false"></el-progress>',
      '                <span class="cps-note">第{{ scope.row.currentDay }}/{{ scope.row.totalDays || "-" }}天</span>',
      '              </template>',
      '            </el-table-column>',
      '            <el-table-column label="变异" width="70" align="center">',
      '              <template #default="scope"><span :class="{\'cps-rate-warn\': toNum(scope.row.varianceCount) > 0}">{{ scope.row.varianceCount }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column label="状态" width="80" align="center">',
      '              <template #default="scope"><el-tag size="small" :type="statusType(scope.row.status)" disable-transitions>{{ statusText(scope.row.status) }}</el-tag></template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="cps-cols">',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">近30天变异记录 <span class="cps-note">质控待复核 · 最多50条</span></div>',
      '            <el-table :data="(dash && dash.recentVariances) || []" size="small" max-height="260" empty-text="近30天无变异">',
      '              <el-table-column prop="execDate" label="日期" width="96"></el-table-column>',
      '              <el-table-column prop="patientName" label="患者" width="86"></el-table-column>',
      '              <el-table-column prop="pathwayName" label="路径" min-width="120" show-overflow-tooltip></el-table-column>',
      '              <el-table-column label="原因分类" width="130"><template #default="scope">{{ orDash(scope.row.varianceTypeName) }}</template></el-table-column>',
      '              <el-table-column prop="varianceReason" label="备注" min-width="110" show-overflow-tooltip></el-table-column>',
      '            </el-table>',
      '          </div>',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">近30天退出记录 <span class="cps-note">最多50条</span></div>',
      '            <el-table :data="(dash && dash.recentExits) || []" size="small" max-height="260" empty-text="近30天无退出">',
      '              <el-table-column prop="exitDate" label="日期" width="96"></el-table-column>',
      '              <el-table-column prop="patientName" label="患者" width="86"></el-table-column>',
      '              <el-table-column prop="pathwayName" label="路径" min-width="120" show-overflow-tooltip></el-table-column>',
      '              <el-table-column label="退出分类" width="130"><template #default="scope">{{ orDash(scope.row.exitTypeName) }}</template></el-table-column>',
      '              <el-table-column prop="exitReason" label="备注" min-width="110" show-overflow-tooltip></el-table-column>',
      '            </el-table>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab2 质控指标 ==================== */
      '    <el-tab-pane label="质控指标" name="rates">',
      '      <div v-loading="ratesLoading || trendLoading">',
      '        <div class="cps-kpis cols-4" v-if="rates">',
      '          <div class="cps-kpi"><div class="num">{{ fmtNum(rateOverall.denominator) }}</div><div class="lbl">目标病种出院病例(入径率分母)</div></div>',
      '          <div class="cps-kpi is-ok"><div class="num">{{ pctText(rateOverall.enterRate) }}</div><div class="lbl">入径率(入径 {{ fmtNum(rateOverall.entered) }} 例)</div></div>',
      '          <div class="cps-kpi"><div class="num">{{ pctText(rateOverall.completeRate) }}</div><div class="lbl">完成率({{ fmtNum(rateOverall.completedCount) }}/{{ fmtNum(rateOverall.instanceCount) }})</div></div>',
      '          <div class="cps-kpi" :class="toNum(rateOverall.exitRate) > 0.1 ? \'is-danger\' : \'\'"><div class="num">{{ pctText(rateOverall.exitRate) }}</div><div class="lbl">退出率({{ fmtNum(rateOverall.exitedCount) }} 例)</div></div>',
      '        </div>',
      '        <div class="cps-kpis cols-4" v-if="rates">',
      '          <div class="cps-kpi" :class="toNum(rateOverall.caseVarianceRate) > 0.2 ? \'is-warn\' : \'\'"><div class="num">{{ pctText(rateOverall.caseVarianceRate) }}</div><div class="lbl">病例变异率</div></div>',
      '          <div class="cps-kpi"><div class="num">{{ pctText(rateOverall.itemVarianceRate) }}</div><div class="lbl">项次变异率({{ fmtNum(rateOverall.execVariance) }}/{{ fmtNum(rateOverall.execTotal) }} 项)</div></div>',
      '          <div class="cps-kpi"><div class="num">{{ fmtNum(rateOverall.instanceCount) }}</div><div class="lbl">入径实例数(窗口内启动)</div></div>',
      '          <div class="cps-kpi"><div class="num">{{ orDash(rates.from) }} ~ {{ orDash(rates.to) }}</div><div class="lbl">统计窗口(出院口径)</div></div>',
      '        </div>',
      '        <div class="cps-card">',
      '          <div class="cps-card-title">四大率分维分析',
      '            <el-radio-group v-model="rateDim" size="small" style="margin-left:12px;">',
      '              <el-radio-button label="dept">科室</el-radio-button>',
      '              <el-radio-button label="doctor">主管医生</el-radio-button>',
      '              <el-radio-button label="disease">病种(模板)</el-radio-button>',
      '            </el-radio-group>',
      '          </div>',
      '          <div ref="rateBarEl" class="cps-chart"></div>',
      '          <el-table :data="rateDimRows" size="small" max-height="320" style="margin-top:8px;" empty-text="窗口内无目标病例">',
      '            <el-table-column prop="dimName" label="维度" min-width="130"></el-table-column>',
      '            <el-table-column prop="denominator" label="出院目标" width="86" align="center"></el-table-column>',
      '            <el-table-column prop="entered" label="入径" width="70" align="center"></el-table-column>',
      '            <el-table-column label="入径率" width="86" align="center"><template #default="s">{{ pctText(s.row.enterRate) }}</template></el-table-column>',
      '            <el-table-column label="完成率" width="86" align="center"><template #default="s">{{ pctText(s.row.completeRate) }}</template></el-table-column>',
      '            <el-table-column label="退出率" width="86" align="center"><template #default="s">{{ pctText(s.row.exitRate) }}</template></el-table-column>',
      '            <el-table-column label="病例变异率" width="96" align="center"><template #default="s"><span :class="{\'cps-rate-warn\': toNum(s.row.caseVarianceRate) > 0.2}">{{ pctText(s.row.caseVarianceRate) }}</span></template></el-table-column>',
      '            <el-table-column label="项次变异率" width="96" align="center"><template #default="s">{{ pctText(s.row.itemVarianceRate) }}</template></el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div class="cps-cols">',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">月度趋势 <span class="cps-note">入径例数 / 完成率 / 变异率(区间可独立设置)</span>',
      '              <el-date-picker v-model="trendRange" type="daterange" value-format="YYYY-MM-DD" size="small" range-separator="至" start-placeholder="趋势起" end-placeholder="趋势止" style="width:220px;margin-left:auto;" @change="trendLoaded = true; loadTrend()"></el-date-picker>',
      '            </div>',
      '            <div ref="trendEl" class="cps-chart"></div>',
      '          </div>',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">{{ rateDim === "doctor" ? "医生入径率排行" : "科室完成率排行" }} <span class="cps-note">Top 12</span></div>',
      '            <div ref="rankEl" class="cps-chart"></div>',
      '            <div v-if="!(rateDim === \'doctor\' ? rates && rates.doctorEnterRank : rates && rates.deptCompleteRank) || (rateDim === \'doctor\' ? !(rates && rates.doctorEnterRank.length) : !(rates && rates.deptCompleteRank.length))" class="cps-note" style="text-align:center;padding:30px 0;">暂无排行数据</div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab3 变异与退出分析 ==================== */
      '    <el-tab-pane label="变异与退出分析" name="variance">',
      '      <div v-loading="varPLoading || exitPLoading">',
      '        <div class="cps-cols">',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">变异原因分类柏拉图 <span class="cps-note">累计占比80%即关键少数原因</span></div>',
      '            <div ref="varParetoEl" class="cps-chart"></div>',
      '            <div v-if="varPLoaded && varP && !varP.items.length" class="cps-note" style="text-align:center;padding:20px 0;">窗口内无变异记录</div>',
      '          </div>',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">退出原因分类柏拉图</div>',
      '            <div ref="exitParetoEl" class="cps-chart"></div>',
      '            <div v-if="exitPLoaded && exitP && !exitP.items.length" class="cps-note" style="text-align:center;padding:20px 0;">窗口内无退出记录</div>',
      '          </div>',
      '        </div>',
      '        <div class="cps-card">',
      '          <div class="cps-card-title">高频变异病种 Top10 <span class="cps-note">按变异记录条数</span></div>',
      '          <div ref="varDiseaseEl" class="cps-chart"></div>',
      '          <div v-if="varPLoaded && varP && !varP.topDiseases.length" class="cps-note" style="text-align:center;padding:20px 0;">窗口内无变异病种数据</div>',
      '        </div>',
      '        <div class="cps-cols" v-if="varP && varP.items && varP.items.length">',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">变异原因构成明细</div>',
      '            <el-table :data="varP.items" size="small" max-height="280">',
      '              <el-table-column prop="name" label="原因分类" min-width="140"></el-table-column>',
      '              <el-table-column prop="cnt" label="条数" width="80" align="center"></el-table-column>',
      '              <el-table-column label="占比" width="90" align="center"><template #default="s">{{ pctText(s.row.rate) }}</template></el-table-column>',
      '              <el-table-column label="累计" width="90" align="center"><template #default="s">{{ pctText(s.row.cumulativeRate) }}</template></el-table-column>',
      '            </el-table>',
      '          </div>',
      '          <div class="cps-card" v-if="exitP && exitP.items">',
      '            <div class="cps-card-title">退出原因构成明细</div>',
      '            <el-table :data="exitP.items" size="small" max-height="280" empty-text="无退出记录">',
      '              <el-table-column prop="name" label="原因分类" min-width="140"></el-table-column>',
      '              <el-table-column prop="cnt" label="例数" width="80" align="center"></el-table-column>',
      '              <el-table-column label="占比" width="90" align="center"><template #default="s">{{ pctText(s.row.rate) }}</template></el-table-column>',
      '              <el-table-column label="累计" width="90" align="center"><template #default="s">{{ pctText(s.row.cumulativeRate) }}</template></el-table-column>',
      '            </el-table>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab4 效率与费用 ==================== */
      '    <el-tab-pane label="效率与费用" name="efficiency">',
      '      <div v-loading="effLoading">',
      '        <div class="cps-kpis" v-if="effLoaded">',
      '          <div class="cps-kpi" v-for="(k, i) in effKpis" :key="i" :class="k.cls">',
      '            <div class="num">{{ k.num }}</div><div class="lbl">{{ k.lbl }}</div>',
      '          </div>',
      '        </div>',
      '        <div class="cps-cols">',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">平均住院日三口径对比 <span class="cps-note">入径完成 / 非入径同病种出院 / 模板标准</span></div>',
      '            <div ref="effLosEl" class="cps-chart"></div>',
      '          </div>',
      '          <div class="cps-card">',
      '            <div class="cps-card-title">例均住院费用三口径对比</div>',
      '            <div ref="effCostEl" class="cps-chart"></div>',
      '          </div>',
      '        </div>',
      '        <div class="cps-card" v-if="eff">',
      '          <div class="cps-card-title">差值分析 <span class="cps-note">负值表示入径组更优</span></div>',
      '          <el-descriptions :column="4" border size="small">',
      '            <el-descriptions-item label="住院日 完成组-标准">{{ fmtNum((eff.diff || {}).losVsStandard, 1) }} 天</el-descriptions-item>',
      '            <el-descriptions-item label="费用 完成组-标准">¥{{ fmtMoney((eff.diff || {}).costVsStandard) }}</el-descriptions-item>',
      '            <el-descriptions-item label="住院日 完成组-非入径">{{ fmtNum((eff.diff || {}).losVsNonPathway, 1) }} 天</el-descriptions-item>',
      '            <el-descriptions-item label="费用 完成组-非入径">¥{{ fmtMoney((eff.diff || {}).costVsNonPathway) }}</el-descriptions-item>',
      '            <el-descriptions-item label="完成组例数">{{ fmtNum((eff.pathwayFinished || {}).caseCount) }}</el-descriptions-item>',
      '            <el-descriptions-item label="非入径对照组例数">{{ fmtNum((eff.nonPathwaySameDisease || {}).caseCount) }}</el-descriptions-item>',
      '            <el-descriptions-item label="达标符合率">{{ pctText((eff.standardCompliance || {}).complianceRate) }}</el-descriptions-item>',
      '            <el-descriptions-item label="口径说明">标准=入径实例所用模板 avg_length/total_cost 加权</el-descriptions-item>',
      '          </el-descriptions>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab5 病种覆盖 ==================== */
      '    <el-tab-pane label="病种覆盖" name="coverage">',
      '      <div v-loading="covLoading">',
      '        <div class="cps-kpis cols-4" v-if="covLoaded">',
      '          <div class="cps-kpi" v-for="(k, i) in covKpis" :key="i">',
      '            <div class="num">{{ k.num }}</div><div class="lbl">{{ k.lbl }}</div>',
      '          </div>',
      '          <div class="cps-kpi"><div class="num">{{ fmtNum((cov.diseases || []).length) }}</div><div class="lbl">已发布病种(启用模板)</div></div>',
      '        </div>',
      '        <div class="cps-card">',
      '          <div class="cps-card-title">各病种推广度 <span class="cps-note">入径例数 / 该类目出院例数(推广度), Top15</span></div>',
      '          <div ref="coverageEl" class="cps-chart"></div>',
      '          <div v-if="covLoaded && cov && !cov.diseases.length" class="cps-note" style="text-align:center;padding:20px 0;">暂无启用模板</div>',
      '        </div>',
      '        <div class="cps-card">',
      '          <div class="cps-card-title">病种覆盖明细</div>',
      '          <el-table :data="(cov && cov.diseases) || []" size="small" max-height="400" empty-text="暂无启用模板">',
      '            <el-table-column prop="pathwayName" label="路径名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="diseaseName" label="适用病种" min-width="130" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="diseaseCode" label="ICD-10" width="100"></el-table-column>',
      '            <el-table-column prop="deptName" label="适用科室" width="110"></el-table-column>',
      '            <el-table-column prop="enteredCount" label="期内入径" width="86" align="center"></el-table-column>',
      '            <el-table-column prop="denominator" label="同类目出院" width="96" align="center"></el-table-column>',
      '            <el-table-column label="推广度" width="90" align="center"><template #default="s">{{ pctText(s.row.coverageRate) }}</template></el-table-column>',
      '            <el-table-column label="变异条数" width="86" align="center"><template #default="s"><span :class="{\'cps-rate-warn\': toNum(s.row.varianceExecCount) > 0}">{{ s.row.varianceExecCount }}</span></template></el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab6 病例明细 ==================== */
      '    <el-tab-pane label="病例明细" name="detail">',
      '      <div class="cps-filter" style="margin-bottom:12px;">',
      '        <span class="cps-note" style="white-space:nowrap;">入径状态</span>',
      '        <el-select v-model="statusFilter" placeholder="全部" style="width:120px" @change="detailPage = 1; loadDetail()">',
      '          <el-option label="全部" value=""></el-option>',
      '          <el-option label="进行中" :value="1"></el-option>',
      '          <el-option label="已完成" :value="2"></el-option>',
      '          <el-option label="已退出" :value="3"></el-option>',
      '          <el-option label="暂停" :value="4"></el-option>',
      '        </el-select>',
      '        <div class="spacer"></div>',
      '        <span class="cps-note">共 {{ fmtNum(detail.total) }} 例入径病例</span>',
      '      </div>',
      '      <div class="cps-card" v-loading="detailLoading">',
      '        <el-table :data="detail.records" size="small" max-height="520" empty-text="窗口内无入径病例">',
      '          <el-table-column prop="inpNo" label="住院号" width="120"></el-table-column>',
      '          <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '          <el-table-column prop="deptName" label="科室" width="110"></el-table-column>',
      '          <el-table-column prop="doctorName" label="主管医生" width="95"></el-table-column>',
      '          <el-table-column prop="pathwayName" label="路径" min-width="150" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="statusType(s.row.status)" disable-transitions>{{ statusText(s.row.status) }}</el-tag></template></el-table-column>',
      '          <el-table-column prop="startDate" label="入径日期" width="100"></el-table-column>',
      '          <el-table-column label="出径日期" width="100"><template #default="s">{{ orDash(s.row.endDate) }}</template></el-table-column>',
      '          <el-table-column prop="currentDay" label="天数" width="60" align="center"></el-table-column>',
      '          <el-table-column label="变异项" width="72" align="center"><template #default="s"><span :class="{\'cps-rate-warn\': toNum(s.row.varianceCount) > 0}">{{ s.row.varianceCount }}</span></template></el-table-column>',
      '          <el-table-column label="实际住院日" width="96" align="center"><template #default="s">{{ orDash(s.row.losDays) }}</template></el-table-column>',
      '          <el-table-column label="实际费用" width="110" align="right"><template #default="s"><span class="cps-money">{{ s.row.totalCost == null ? "-" : fmtMoney(s.row.totalCost) }}</span></template></el-table-column>',
      '        </el-table>',
      '        <el-pagination style="margin-top:10px; justify-content:flex-end;" background layout="prev, pager, next, sizes, total"',
      '          :total="detail.total" v-model:current-page="detailPage" v-model:page-size="detailSize"',
      '          :page-sizes="[20, 50, 100]" @current-change="onDetailPage" @size-change="detailPage = 1; loadDetail()"></el-pagination>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };

  /* ===== 柏拉图配置(变异/退出共用): 柱=例数, 折线=累计占比 ===== */
  function paretoOption(items, TH) {
    var names = items.map(function (r) { return r.name || '未分类'; });
    var cnts = items.map(function (r) { return toNum(r.cnt); });
    var cums = items.map(function (r) { return pct100(r.cumulativeRate); });
    return {
      tooltip: TH.tooltip ? TH.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
      legend: TH.legend ? TH.legend({ top: 0 }) : { top: 0 },
      grid: { left: 8, right: 42, top: 34, bottom: 4, containLabel: true },
      xAxis: TH.catAxis ? TH.catAxis({ type: 'category', data: names, axisLabel: { interval: 0, rotate: names.length > 5 ? 30 : 0, fontSize: 10 } })
        : { type: 'category', data: names },
      yAxis: [
        TH.valAxis ? TH.valAxis({ type: 'value', minInterval: 1, name: '例数' }) : { type: 'value', minInterval: 1 },
        { type: 'value', name: '累计(%)', max: 100, axisLabel: { formatter: '{value}%' } }
      ],
      series: [
        { name: '例数', type: 'bar', barMaxWidth: 30, itemStyle: { color: TH.brand || '#1a5c9e', borderRadius: [3, 3, 0, 0] },
          label: { show: true, position: 'top', fontSize: 10, color: TH.ink3 }, data: cnts },
        { name: '累计占比', type: 'line', yAxisIndex: 1, smooth: false, symbol: 'circle', symbolSize: 6,
          lineStyle: { color: TH.danger || '#d64541', width: 2 }, itemStyle: { color: TH.danger || '#d64541' },
          data: cums,
          markLine: { silent: true, symbol: 'none', label: { formatter: '80%', fontSize: 10 },
            lineStyle: { color: '#e6a23c', type: 'dashed' }, data: [{ yAxis: 80 }] } }
      ]
    };
  }
})();
