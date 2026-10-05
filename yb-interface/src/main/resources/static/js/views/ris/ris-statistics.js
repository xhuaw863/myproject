/* ============================================================================
 * RIS 统计分析(RisStatistics) — 工作量/阳性率/报告时效/设备利用率/收入 五类报表
 * 后端契约(/api/ris/admin/stats, 机构隔离 scopeOrgId, 全只读, 日期缺省近30天):
 *   GET /workload  {byDoctor[], byDept[], byDevice[]}(行含 *_name/exam_cnt/report_cnt)
 *                  参数 deptId/doctorId/startDate/endDate/groupBy(回显)
 *   GET /positive-rate  {byExamType[], byBodyPart[]}(行含 dim_label/total/positive/
 *                  positive_rate; 0-100 百分数; 参数 deptType/startDate/endDate)
 *   GET /report-time  List(行含 category normal/urgent/critical, total, avg/min/max_minutes,
 *                  within_2h_rate 0-100)
 *   GET /device-utilization  List(行含 device_name/modality/check_cnt/total_minutes/
 *                  avg_minutes/active_days/utilization_pct 0-100)
 *   GET /revenue   {byDept[], byExamType[]}(行含 *_name/exam_type + order_cnt/total_charge)
 * 口径: 工作量/收入按申请日期, 阳性率按报告日期, 时效/设备按执行时间; 已取消单不计收入。
 * 注册: HIS.views.RisStatistics; 图表 echarts.init(el,'yb') + 隐藏页签宽度0故 mountChart
 * 带重试; resize 统一; 卸载 dispose; 类前缀 rst-。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var T = HIS.theme || {};

  /* ===== 常量映射 ===== */
  var WL_GROUPS = {
    dept: { label: '按科室', key: 'byDept', name: 'dept_name', col: '执行科室' },
    doctor: { label: '按医生', key: 'byDoctor', name: 'doctor_name', col: '申请医生' },
    device: { label: '按设备', key: 'byDevice', name: 'device_name', col: '设备' }
  };
  var TIME_CATS = [
    { v: 'normal', l: '普通', t: 'primary' },
    { v: 'urgent', l: '急诊', t: 'warning' },
    { v: 'critical', l: '危急值', t: 'danger' }
  ];
  var DEPT_TYPES = [
    { v: 'RADIOLOGY', l: '放射 RADIOLOGY' }, { v: 'ULTRASOUND', l: '超声 ULTRASOUND' }, { v: 'ENDOSCOPY', l: '内镜 ENDOSCOPY' }
  ];

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
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  /* 后端率值已是 0-100 百分数, 直接格式化 */
  function pctText(v) { return v == null ? '-' : toNum(v).toFixed(1) + '%'; }
  function fmtMinutes(v) {
    var n = toNum(v);
    if (v == null) { return '-'; }
    var h = Math.floor(n / 60); var m = Math.round(n % 60);
    if (h > 0) { return h + '时' + (m > 0 ? m + '分' : ''); }
    return m + '分';
  }

  /* ===== 图表键(统一 resize/dispose) ===== */
  var CHART_KEYS = ['workloadChart', 'posPieChart', 'posBarChart', 'partChart', 'timeChart', 'utilChart', 'revenueChart'];

  /* ---- 私有样式一次性注入(类前缀 rst-*) ---- */
  (function ensureStyles() {
    if (document.getElementById('ris-statistics-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-statistics-style';
    st.textContent = [
      '.rst-root { min-height:calc(100vh - 88px); display:flex; flex-direction:column; }',
      '.rst-caption { flex:none; display:flex; align-items:baseline; gap:10px; margin-bottom:10px; }',
      '.rst-caption .t { font-size:var(--yb-fs-xl); font-weight:700; letter-spacing:var(--yb-tracking-tight); color:var(--yb-ink-1); border-left:3px solid var(--yb-brand); padding-left:11px; }',
      '.rst-caption .hint { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }',
      '.rst-filter { display:flex; gap:10px; align-items:center; flex-wrap:wrap; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:12px 16px; box-shadow:var(--yb-sh-1); }',
      '.rst-filter .spacer { flex:1; }',
      '.rst-kpis { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin:14px 0; }',
      '.rst-kpi { position:relative; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px 20px; box-shadow:var(--yb-sh-1); overflow:hidden; }',
      '.rst-kpi::before { content:""; position:absolute; left:0; top:0; bottom:0; width:3px; background:var(--yb-brand); opacity:.9; }',
      '.rst-kpi .num { font-size:24px; font-weight:700; line-height:1.15; color:var(--yb-brand); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); letter-spacing:-.02em; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.rst-kpi .lbl { color:var(--yb-ink-3); font-size:12px; margin-top:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.rst-kpi.is-gold::before { background:var(--yb-gold); }',
      '.rst-kpi.is-gold .num { color:var(--yb-gold); }',
      '.rst-kpi.is-ok::before { background:var(--yb-success); }',
      '.rst-kpi.is-ok .num { color:var(--yb-success); }',
      '.rst-card { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px; box-shadow:var(--yb-sh-1); margin-bottom:14px; }',
      '.rst-card-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.rst-chart { width:100%; height:300px; }',
      '.rst-cols { display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-bottom:14px; }',
      '.rst-cols > .rst-card { margin-bottom:0; }',
      '.rst-note { font-size:12px; color:var(--yb-ink-4); font-weight:400; }',
      '.rst-money { font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.rst-code { font-family:var(--yb-font-mono); font-size:12px; color:var(--yb-ink-2); }',
      '@media (max-width:1280px) { .rst-cols { grid-template-columns:1fr; } .rst-kpis { grid-template-columns:repeat(2,1fr); } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ============================================================================
   * RisStatistics 五页签统计
   * ========================================================================== */
  HIS.views.RisStatistics = {
    name: 'RisStatistics',
    data: function () {
      return {
        dateRange: lastNDays(30),
        activeTab: 'workload',
        depts: [], doctors: [],
        deptTypeOpts: DEPT_TYPES,

        /* Tab1 工作量 */
        groupBy: 'dept', wlDeptId: '', wlDoctorId: '',
        workload: null, workloadLoading: false, workloadLoaded: false,

        /* Tab2 阳性率 */
        posDeptType: null, pos: null, posLoading: false, posLoaded: false,

        /* Tab3 报告时效 */
        times: [], timeLoading: false, timeLoaded: false,

        /* Tab4 设备利用率 */
        utils: [], utilLoading: false, utilLoaded: false,

        /* Tab5 收入 */
        revenue: null, revLoading: false, revLoaded: false, revDim: 'byDept',

        /* 图表实例(句柄存 data 以便模板引用与生命周期管理) */
        workloadChart: null, posPieChart: null, posBarChart: null, partChart: null,
        timeChart: null, utilChart: null, revenueChart: null
      };
    },
    computed: {
      wlMeta: function () { return WL_GROUPS[this.groupBy] || WL_GROUPS.dept; },
      wlRows: function () {
        if (!this.workload) { return []; }
        return this.workload[this.wlMeta.key] || [];
      },
      timeRows: function () {
        var map = {};
        (this.times || []).forEach(function (r) { map[r.category] = r; });
        return TIME_CATS.map(function (c) {
          return map[c.v] || { category: c.v, total: 0, avg_minutes: null, min_minutes: null, max_minutes: null, within_2h_rate: null };
        });
      },
      revRows: function () {
        if (!this.revenue) { return []; }
        return this.revDim === 'byExamType' ? (this.revenue.byExamType || []) : (this.revenue.byDept || []);
      },
      revNameKey: function () { return this.revDim === 'byExamType' ? 'exam_type' : 'dept_name'; },
      revTotal: function () {
        return ((this.revenue && this.revenue.byDept) || []).reduce(function (s, r) { return s + toNum(r.total_charge); }, 0);
      },
      revOrders: function () {
        return ((this.revenue && this.revenue.byDept) || []).reduce(function (s, r) { return s + toNum(r.order_cnt); }, 0);
      },
      revKpis: function () {
        var vm = this;
        var byDept = (vm.revenue && vm.revenue.byDept) || [];
        var top = byDept.length ? byDept[0] : null;
        return [
          { num: '¥' + fmtMoney(vm.revTotal), lbl: '检查总收入(已取消单不计)', cls: 'is-gold' },
          { num: fmtNum(vm.revOrders), lbl: '申请单量', cls: '' },
          { num: '¥' + fmtMoney(vm.revOrders ? vm.revTotal / vm.revOrders : 0), lbl: '平均单笔检查费用', cls: '' },
          { num: top ? (top[vm.revNameKeyFor('byDept')] || '-') : '-', lbl: top ? ('Top科室 ¥' + fmtMoney(top.total_charge)) : 'Top科室', cls: 'is-ok' }
        ];
      }
    },
    watch: {
      groupBy: function () { this.renderWorkloadChart(); },
      revDim: function () { this.renderRevenueChart(); }
    },

    methods: {
      toNum: toNum, fmtNum: fmtNum, fmtMoney: fmtMoney, orDash: orDash,
      pctText: pctText, fmtMinutes: fmtMinutes,
      timeCatLabel: function (v) {
        for (var i = 0; i < TIME_CATS.length; i++) { if (TIME_CATS[i].v === v) { return TIME_CATS[i].l; } }
        return orDash(v);
      },
      timeCatTag: function (v) {
        for (var i = 0; i < TIME_CATS.length; i++) { if (TIME_CATS[i].v === v) { return TIME_CATS[i].t; } }
        return 'info';
      },
      revNameKeyFor: function (dim) { return dim === 'byExamType' ? 'exam_type' : 'dept_name'; },
      revRowName: function (r) { return orDash(r && (r.exam_type != null ? r.exam_type : r.dept_name)); },

      /* ===== 参数与引用 ===== */
      rangeParams: function () {
        var p = new URLSearchParams();
        var r = this.dateRange;
        if (r && r[0]) { p.append('startDate', r[0]); }
        if (r && r[1]) { p.append('endDate', r[1]); }
        return p;
      },
      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.depts = l || []; }).catch(function () { vm.depts = []; });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false')
          .then(function (l) { vm.doctors = l || []; }).catch(function () { vm.doctors = []; });
      },
      search: function () {
        this.workloadLoaded = false; this.posLoaded = false; this.timeLoaded = false;
        this.utilLoaded = false; this.revLoaded = false;
        this.loadActive(true);
      },
      onTabChange: function (name) {
        if (name) { this.activeTab = name; }
        this.loadActive(false);
      },
      loadActive: function (force) {
        var loaders = { workload: 'loadWorkload', positive: 'loadPos', time: 'loadTimes',
          util: 'loadUtils', revenue: 'loadRevenue' };
        var flags = { workload: 'workloadLoaded', positive: 'posLoaded', time: 'timeLoaded',
          util: 'utilLoaded', revenue: 'revLoaded' };
        var tab = this.activeTab;
        if (loaders[tab] && (force || !this[flags[tab]])) { this[loaders[tab]](); }
      },

      /* ===== Tab1 工作量统计 ===== */
      loadWorkload: function () {
        var vm = this; vm.workloadLoading = true;
        var p = vm.rangeParams();
        if (vm.wlDeptId !== '' && vm.wlDeptId != null) { p.append('deptId', vm.wlDeptId); }
        if (vm.wlDoctorId !== '' && vm.wlDoctorId != null) { p.append('doctorId', vm.wlDoctorId); }
        p.append('groupBy', vm.groupBy);
        HIS.get('/api/ris/admin/stats/workload?' + p.toString()).then(function (d) {
          vm.workload = d || {};
          vm.workloadLoaded = true;
          vm.$nextTick(function () { vm.renderWorkloadChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.workloadLoading = false; });
      },
      renderWorkloadChart: function () {
        var vm = this;
        var rows = vm.wlRows;
        if (!rows.length) { return; }
        var nameKey = vm.wlMeta.name;
        vm.mountChart('workloadChart', 'workloadEl', function () {
          var names = rows.map(function (r) { return orDash(r[nameKey]); });
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            legend: T.legend ? T.legend({ top: 0 }) : { top: 0 },
            grid: { left: 8, right: 18, top: 34, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: names,
              axisLabel: { interval: 0, rotate: names.length > 6 ? 28 : 0, fontSize: 10 } })
              : { type: 'category', data: names },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
            series: [
              { name: '检查量(申请单)', type: 'bar', barMaxWidth: 26,
                itemStyle: { color: T.brand || '#1a5c9e', borderRadius: [3, 3, 0, 0] },
                data: rows.map(function (r) { return toNum(r.exam_cnt); }) },
              { name: '报告量', type: 'bar', barMaxWidth: 26,
                itemStyle: { color: T.teal || '#048671', borderRadius: [3, 3, 0, 0] },
                data: rows.map(function (r) { return toNum(r.report_cnt); }) }
            ]
          };
        });
      },

      /* ===== Tab2 阳性率统计 ===== */
      loadPos: function () {
        var vm = this; vm.posLoading = true;
        var p = vm.rangeParams();
        if (vm.posDeptType) { p.append('deptType', vm.posDeptType); }
        HIS.get('/api/ris/admin/stats/positive-rate?' + p.toString()).then(function (d) {
          vm.pos = d || {};
          vm.posLoaded = true;
          vm.$nextTick(function () { vm.renderPosCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.posLoading = false; });
      },
      renderPosCharts: function () {
        var vm = this;
        var byType = (vm.pos && vm.pos.byExamType) || [];
        if (byType.length) {
          /* 检查类型构成 donut(中心总检查量) */
          vm.mountChart('posPieChart', 'posPieEl', function () {
            var total = byType.reduce(function (s, r) { return s + toNum(r.total); }, 0);
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'item', formatter: '{b}: {c} 例 ({d}%)' }) : { trigger: 'item' },
              legend: T.legend ? T.legend({ bottom: 0, type: 'scroll' }) : { bottom: 0 },
              series: [{
                name: '检查量', type: 'pie', radius: ['40%', '66%'], center: ['50%', '44%'],
                itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
                label: { color: T.ink3, fontSize: 11, formatter: '{b}\n{d}%' },
                data: byType.map(function (r) { return { name: orDash(r.dim_label), value: toNum(r.total) }; })
              }],
              graphic: [{ type: 'text', left: 'center', top: '40%',
                style: { text: fmtNum(total) + ' 例', fill: T.ink2 || '#3d4a5c', fontSize: 18, fontWeight: 700 } }]
            };
          });
          /* 阳性数柱 + 阳性率折线(双 y 轴) */
          vm.mountChart('posBarChart', 'posBarEl', function () {
            var names = byType.map(function (r) { return orDash(r.dim_label); });
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              legend: T.legend ? T.legend({ top: 0 }) : { top: 0 },
              grid: { left: 8, right: 44, top: 34, bottom: 4, containLabel: true },
              xAxis: T.catAxis ? T.catAxis({ type: 'category', data: names,
                axisLabel: { interval: 0, rotate: names.length > 5 ? 24 : 0, fontSize: 10 } })
                : { type: 'category', data: names },
              yAxis: [
                T.valAxis ? T.valAxis({ type: 'value', minInterval: 1, name: '例' }) : { type: 'value', minInterval: 1 },
                { type: 'value', name: '阳性率(%)', max: 100, axisLabel: { formatter: '{value}%' } }
              ],
              series: [
                { name: '阳性数', type: 'bar', barMaxWidth: 26,
                  itemStyle: { color: T.danger || '#c74f4f', borderRadius: [3, 3, 0, 0] },
                  data: byType.map(function (r) { return toNum(r.positive); }) },
                { name: '阳性率', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 6,
                  lineStyle: { color: T.warning || '#a26b1b', width: 2 }, itemStyle: { color: T.warning || '#a26b1b' },
                  data: byType.map(function (r) { return r.positive_rate == null ? null : toNum(r.positive_rate); }) }
              ]
            };
          });
        }
        /* 各部位阳性率 Top15 横向柱 */
        var byPart = ((vm.pos && vm.pos.byBodyPart) || []).slice(0, 15);
        if (byPart.length) {
          vm.mountChart('partChart', 'partEl', function () {
            var rows = byPart.slice().reverse();
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis',
                formatter: function (ps) {
                  var p = ps[0]; var r = rows[p.dataIndex];
                  return orDash(r.dim_label) + '<br/>检查 ' + fmtNum(r.total) + ' 例 · 阳性 ' + fmtNum(r.positive)
                    + ' 例<br/>阳性率 ' + pctText(r.positive_rate);
                } }) : { trigger: 'axis' },
              grid: { left: 8, right: 48, top: 8, bottom: 4, containLabel: true },
              xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
              yAxis: T.catAxis ? T.catAxis({ type: 'category',
                data: rows.map(function (r) { return orDash(r.dim_label); }) })
                : { type: 'category', data: rows.map(function (r) { return orDash(r.dim_label); }) },
              series: [{ name: '阳性率', type: 'bar', barMaxWidth: 16,
                itemStyle: { color: T.purple || '#9b59b6', borderRadius: [0, 3, 3, 0] },
                label: { show: true, position: 'right', fontSize: 10, color: T.ink3 || '#5a6a7e', formatter: function (p) { return p.value + '%'; } },
                data: rows.map(function (r) { return r.positive_rate == null ? 0 : toNum(r.positive_rate); }) }]
            };
          });
        }
      },

      /* ===== Tab3 报告时效 ===== */
      loadTimes: function () {
        var vm = this; vm.timeLoading = true;
        var p = vm.rangeParams();
        HIS.get('/api/ris/admin/stats/report-time?' + p.toString()).then(function (d) {
          vm.times = d || [];
          vm.timeLoaded = true;
          vm.$nextTick(function () { vm.renderTimeChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.timeLoading = false; });
      },
      renderTimeChart: function () {
        var vm = this;
        var rows = vm.timeRows;
        if (!vm.times.length) { return; }
        vm.mountChart('timeChart', 'timeEl', function () {
          var labels = rows.map(function (r) { return vm.timeCatLabel(r.category); });
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis',
              formatter: function (ps) {
                var i = ps[0].dataIndex; var r = rows[i];
                return vm.timeCatLabel(r.category) + ' · ' + fmtNum(r.total) + ' 份报告<br/>'
                  + '平均 ' + fmtMinutes(r.avg_minutes) + ' (最短 ' + fmtMinutes(r.min_minutes)
                  + ' / 最长 ' + fmtMinutes(r.max_minutes) + ')<br/>2小时达标率 ' + pctText(r.within_2h_rate);
              } }) : { trigger: 'axis' },
            legend: T.legend ? T.legend({ top: 0 }) : { top: 0 },
            grid: { left: 8, right: 46, top: 34, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: labels }) : { type: 'category', data: labels },
            yAxis: [
              T.valAxis ? T.valAxis({ type: 'value', name: '分钟' }) : { type: 'value', name: '分钟' },
              { type: 'value', name: '达标率(%)', max: 100, axisLabel: { formatter: '{value}%' } }
            ],
            series: [
              { name: '最短时长', type: 'bar', barMaxWidth: 20,
                itemStyle: { color: T.teal || '#048671', borderRadius: [3, 3, 0, 0] },
                data: rows.map(function (r) { return r.min_minutes == null ? null : toNum(r.min_minutes); }) },
              { name: '平均时长', type: 'bar', barMaxWidth: 20,
                itemStyle: { color: T.brand || '#1a5c9e', borderRadius: [3, 3, 0, 0] },
                data: rows.map(function (r) { return r.avg_minutes == null ? null : toNum(r.avg_minutes); }) },
              { name: '最长时长', type: 'bar', barMaxWidth: 20,
                itemStyle: { color: T.danger || '#c74f4f', borderRadius: [3, 3, 0, 0] },
                data: rows.map(function (r) { return r.max_minutes == null ? null : toNum(r.max_minutes); }) },
              { name: '2小时达标率', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 7,
                lineStyle: { color: T.success || '#3c862d', width: 2 }, itemStyle: { color: T.success || '#3c862d' },
                data: rows.map(function (r) { return r.within_2h_rate == null ? null : toNum(r.within_2h_rate); }) }
            ]
          };
        });
      },

      /* ===== Tab4 设备利用率 ===== */
      loadUtils: function () {
        var vm = this; vm.utilLoading = true;
        var p = vm.rangeParams();
        HIS.get('/api/ris/admin/stats/device-utilization?' + p.toString()).then(function (d) {
          vm.utils = d || [];
          vm.utilLoaded = true;
          vm.$nextTick(function () { vm.renderUtilChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.utilLoading = false; });
      },
      renderUtilChart: function () {
        var vm = this;
        var rows = (vm.utils || []).slice(0, 15);
        if (!rows.length) { return; }
        vm.mountChart('utilChart', 'utilEl', function () {
          var r = rows.slice().reverse();
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis',
              formatter: function (ps) {
                var i = ps[0].dataIndex; var row = r[i];
                return orDash(row.device_name) + (row.modality ? ' (' + row.modality + ')' : '') + '<br/>'
                  + '检查 ' + fmtNum(row.check_cnt) + ' 次 · 占用 ' + fmtMinutes(row.total_minutes) + '<br/>'
                  + '平均单次 ' + fmtMinutes(row.avg_minutes) + ' · 活跃 ' + fmtNum(row.active_days) + ' 天<br/>'
                  + '开机利用率 ' + pctText(row.utilization_pct);
              } }) : { trigger: 'axis' },
            grid: { left: 8, right: 52, top: 8, bottom: 4, containLabel: true },
            xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
            yAxis: T.catAxis ? T.catAxis({ type: 'category',
              data: r.map(function (x) { return orDash(x.device_name); }) })
              : { type: 'category', data: r.map(function (x) { return orDash(x.device_name); }) },
            series: [{ name: '开机利用率', type: 'bar', barMaxWidth: 16,
              itemStyle: { color: T.brand || '#1a5c9e', borderRadius: [0, 3, 3, 0] },
              label: { show: true, position: 'right', fontSize: 10, color: T.ink3 || '#5a6a7e', formatter: function (p) { return p.value + '%'; } },
              data: r.map(function (x) { return x.utilization_pct == null ? 0 : toNum(x.utilization_pct); }) }]
          };
        });
      },

      /* ===== Tab5 收入统计 ===== */
      loadRevenue: function () {
        var vm = this; vm.revLoading = true;
        var p = vm.rangeParams();
        HIS.get('/api/ris/admin/stats/revenue?' + p.toString()).then(function (d) {
          vm.revenue = d || {};
          vm.revLoaded = true;
          vm.$nextTick(function () { vm.renderRevenueChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.revLoading = false; });
      },
      renderRevenueChart: function () {
        var vm = this;
        var rows = vm.revRows;
        if (!rows.length) { return; }
        var nameKey = vm.revNameKey;
        vm.mountChart('revenueChart', 'revenueEl', function () {
          var names = rows.map(function (r) { return orDash(r[nameKey]); });
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis',
              formatter: function (ps) {
                var p = ps[0]; var r = rows[p.dataIndex];
                return p.name + '<br/>申请单 ' + fmtNum(r.order_cnt) + ' 笔<br/>检查收入 ¥' + fmtMoney(r.total_charge);
              } }) : { trigger: 'axis' },
            grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: names,
              axisLabel: { interval: 0, rotate: names.length > 6 ? 24 : 0, fontSize: 10 } })
              : { type: 'category', data: names },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', name: '元' }) : { type: 'value', name: '元' },
            series: [{ name: '检查收入', type: 'bar', barMaxWidth: 32,
              itemStyle: { color: T.gold || '#9c6d25', borderRadius: [3, 3, 0, 0] },
              label: { show: true, position: 'top', fontSize: 10, color: T.ink3 || '#5a6a7e' },
              data: rows.map(function (r) { return toNum(r.total_charge); }) }]
          };
        });
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

    mounted: function () {
      window.addEventListener('resize', this.onResize);
      this.loadRefs();
      this.loadWorkload();
    },
    unmounted: function () {
      window.removeEventListener('resize', this.onResize);
      var vm = this;
      CHART_KEYS.forEach(function (k) { if (vm[k]) { vm[k].dispose(); vm[k] = null; } });
    },

    /* ==================== 模板 ==================== */
    template: [
      '<div class="rst-root">',
      '  <div class="rst-caption">',
      '    <span class="t">RIS 统计分析</span>',
      '    <span class="hint">工作量 / 阳性率 / 报告时效 / 设备利用率 / 收入 · 牵头机构可看医共体全部, 日期缺省近30天</span>',
      '  </div>',

      /* ---- 通用筛选栏 ---- */
      '  <div class="rst-filter">',
      '    <span class="rst-note" style="white-space:nowrap;">统计区间</span>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:236px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <div class="spacer"></div>',
      '    <span class="rst-note">口径: 工作量/收入按申请日期, 阳性率按报告日期, 时效/设备按执行时间</span>',
      '  </div>',

      '  <el-tabs v-model="activeTab" style="margin-top:12px;" @tab-change="onTabChange">',

      /* ==================== Tab1 工作量统计 ==================== */
      '    <el-tab-pane label="工作量统计" name="workload">',
      '      <div v-loading="workloadLoading">',
      '        <div class="rst-filter" style="margin-bottom:12px;">',
      '          <el-radio-group v-model="groupBy" size="small">',
      '            <el-radio-button label="dept">按科室</el-radio-button>',
      '            <el-radio-button label="doctor">按医生</el-radio-button>',
      '            <el-radio-button label="device">按设备</el-radio-button>',
      '          </el-radio-group>',
      '          <el-select v-model="wlDeptId" filterable clearable placeholder="全部执行科室" style="width:160px" @change="loadWorkload">',
      '            <el-option v-for="d in depts" :key="String(d.id)" :label="d.deptName" :value="String(d.id)"></el-option>',
      '          </el-select>',
      '          <el-select v-model="wlDoctorId" filterable clearable placeholder="全部申请医生" style="width:160px" @change="loadWorkload">',
      '            <el-option v-for="s in doctors" :key="String(s.id)" :label="s.staffName" :value="String(s.id)"></el-option>',
      '          </el-select>',
      '          <div class="spacer"></div>',
      '          <span class="rst-note">检查量=申请单数 · 报告量=关联报告数(含未审核草稿)</span>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">工作量分布 <span class="rst-note">{{ wlMeta.label }}(检查量 / 报告量)</span></div>',
      '          <div ref="workloadEl" class="rst-chart"></div>',
      '          <div v-if="workloadLoaded && !wlRows.length" class="rst-note" style="text-align:center;padding:24px 0;">区间内无申请单</div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">明细 <span class="rst-note">共 {{ wlRows.length }} 项</span></div>',
      '          <el-table :data="wlRows" size="small" max-height="380" empty-text="区间内无申请单">',
      '            <el-table-column type="index" label="#" width="52"></el-table-column>',
      '            <el-table-column :label="wlMeta.col" min-width="160" show-overflow-tooltip>',
      '              <template #default="s">{{ orDash(s.row[wlMeta.name]) }}</template>',
      '            </el-table-column>',
      '            <el-table-column prop="modality" label="Modality" width="100" align="center" v-if="groupBy===\'device\'">',
      '              <template #default="s"><span class="rst-code">{{ orDash(s.row.modality) }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column prop="exam_cnt" label="检查量(申请单)" width="130" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.exam_cnt) }}</template>',
      '            </el-table-column>',
      '            <el-table-column prop="report_cnt" label="报告量" width="100" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.report_cnt) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="报告覆盖" width="110" align="center">',
      '              <template #default="s">{{ toNum(s.row.exam_cnt) ? pctText(toNum(s.row.report_cnt) * 100 / toNum(s.row.exam_cnt)) : \'-\' }}</template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab2 阳性率统计 ==================== */
      '    <el-tab-pane label="阳性率统计" name="positive">',
      '      <div v-loading="posLoading">',
      '        <div class="rst-filter" style="margin-bottom:12px;">',
      '          <span class="rst-note" style="white-space:nowrap;">科室类型</span>',
      '          <el-select v-model="posDeptType" clearable placeholder="全部" style="width:170px" @change="loadPos">',
      '            <el-option v-for="d in deptTypeOpts" :key="d.v" :label="d.l" :value="d.v"></el-option>',
      '          </el-select>',
      '          <div class="spacer"></div>',
      '          <span class="rst-note">口径: 已判定阳性/阴性的报告(positive_flag>=0), 阳性=positive_flag=1</span>',
      '        </div>',
      '        <div class="rst-cols">',
      '          <div class="rst-card">',
      '            <div class="rst-card-title">检查类型构成 <span class="rst-note">期内已判定报告</span></div>',
      '            <div ref="posPieEl" class="rst-chart"></div>',
      '            <div v-if="posLoaded && !(pos && pos.byExamType && pos.byExamType.length)" class="rst-note" style="text-align:center;padding:24px 0;">区间内无已判定报告</div>',
      '          </div>',
      '          <div class="rst-card">',
      '            <div class="rst-card-title">各检查类型阳性率 <span class="rst-note">阳性数 / 阳性率走势</span></div>',
      '            <div ref="posBarEl" class="rst-chart"></div>',
      '          </div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">各部位阳性率 <span class="rst-note">Top 15</span></div>',
      '          <div ref="partEl" class="rst-chart"></div>',
      '          <div v-if="posLoaded && !(pos && pos.byBodyPart && pos.byBodyPart.length)" class="rst-note" style="text-align:center;padding:24px 0;">区间内无部位记录</div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">检查类型明细</div>',
      '          <el-table :data="(pos && pos.byExamType) || []" size="small" max-height="320" empty-text="区间内无已判定报告">',
      '            <el-table-column type="index" label="#" width="52"></el-table-column>',
      '            <el-table-column prop="dim_label" label="检查类型" min-width="140" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="total" label="检查量" width="110" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.total) }}</template>',
      '            </el-table-column>',
      '            <el-table-column prop="positive" label="阳性数" width="100" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.positive) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="阳性率" min-width="200">',
      '              <template #default="s">',
      '                <el-progress :percentage="toNum(s.row.positive_rate)" :stroke-width="8" style="width:150px;display:inline-block;vertical-align:middle;"></el-progress>',
      '                <span class="rst-note">&nbsp;{{ pctText(s.row.positive_rate) }}</span>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab3 报告时效 ==================== */
      '    <el-tab-pane label="报告时效" name="time">',
      '      <div v-loading="timeLoading">',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">检查完成 → 报告审核 时长与达标率 <span class="rst-note">普通 / 急诊 / 危急值 · 仅已审核报告, 2小时达标率=审核时长≤120分钟占比</span></div>',
      '          <div ref="timeEl" class="rst-chart"></div>',
      '          <div v-if="timeLoaded && !times.length" class="rst-note" style="text-align:center;padding:24px 0;">区间内无已审核报告</div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">分类明细</div>',
      '          <el-table :data="timeRows" size="small" empty-text="区间内无已审核报告">',
      '            <el-table-column label="类别" width="110" align="center">',
      '              <template #default="s"><el-tag size="small" :type="timeCatTag(s.row.category)">{{ timeCatLabel(s.row.category) }}</el-tag></template>',
      '            </el-table-column>',
      '            <el-table-column prop="total" label="报告量" width="100" align="center">',
      '              <template #default="s">{{ fmtNum(s.row.total) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="平均时长" width="120" align="center">',
      '              <template #default="s"><b>{{ fmtMinutes(s.row.avg_minutes) }}</b></template>',
      '            </el-table-column>',
      '            <el-table-column label="最短" width="100" align="center">',
      '              <template #default="s">{{ fmtMinutes(s.row.min_minutes) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="最长" width="100" align="center">',
      '              <template #default="s">{{ fmtMinutes(s.row.max_minutes) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="2小时达标率" min-width="220">',
      '              <template #default="s">',
      '                <el-progress :percentage="toNum(s.row.within_2h_rate)" :stroke-width="8" style="width:150px;display:inline-block;vertical-align:middle;"></el-progress>',
      '                <span class="rst-note">&nbsp;{{ pctText(s.row.within_2h_rate) }}</span>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab4 设备利用率 ==================== */
      '    <el-tab-pane label="设备利用率" name="util">',
      '      <div v-loading="utilLoading">',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">设备开机利用率 <span class="rst-note">检查占用时长 ÷ (活跃天数 × 8小时标准工时) · 按检查量排序 Top 15</span></div>',
      '          <div ref="utilEl" class="rst-chart"></div>',
      '          <div v-if="utilLoaded && !utils.length" class="rst-note" style="text-align:center;padding:24px 0;">区间内无设备执行记录</div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">设备明细 <span class="rst-note">共 {{ utils.length }} 台</span></div>',
      '          <el-table :data="utils" size="small" max-height="420" empty-text="区间内无设备执行记录">',
      '            <el-table-column type="index" label="#" width="52"></el-table-column>',
      '            <el-table-column prop="device_name" label="设备" min-width="160" show-overflow-tooltip></el-table-column>',
      '            <el-table-column prop="modality" label="Modality" width="100" align="center">',
      '              <template #default="s"><span class="rst-code">{{ orDash(s.row.modality) }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column prop="check_cnt" label="检查量" width="90" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.check_cnt) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="占用时长" width="110" align="center">',
      '              <template #default="s">{{ fmtMinutes(s.row.total_minutes) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="平均单次" width="100" align="center">',
      '              <template #default="s">{{ fmtMinutes(s.row.avg_minutes) }}</template>',
      '            </el-table-column>',
      '            <el-table-column prop="active_days" label="活跃天数" width="90" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.active_days) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="开机利用率" min-width="220">',
      '              <template #default="s">',
      '                <el-progress :percentage="Math.min(100, toNum(s.row.utilization_pct))" :stroke-width="8" style="width:150px;display:inline-block;vertical-align:middle;"></el-progress>',
      '                <span class="rst-note">&nbsp;{{ pctText(s.row.utilization_pct) }}</span>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ==================== Tab5 收入统计 ==================== */
      '    <el-tab-pane label="收入统计" name="revenue">',
      '      <div v-loading="revLoading">',
      '        <div class="rst-kpis" v-if="revLoaded">',
      '          <div class="rst-kpi" v-for="(k, i) in revKpis" :key="i" :class="k.cls">',
      '            <div class="num">{{ k.num }}</div><div class="lbl">{{ k.lbl }}</div>',
      '          </div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">检查收入分布',
      '            <el-radio-group v-model="revDim" size="small" style="margin-left:12px;">',
      '              <el-radio-button label="byDept">按科室</el-radio-button>',
      '              <el-radio-button label="byExamType">按检查类型</el-radio-button>',
      '            </el-radio-group>',
      '          </div>',
      '          <div ref="revenueEl" class="rst-chart"></div>',
      '          <div v-if="revLoaded && !revRows.length" class="rst-note" style="text-align:center;padding:24px 0;">区间内无有效申请单</div>',
      '        </div>',
      '        <div class="rst-card">',
      '          <div class="rst-card-title">明细 <span class="rst-note">已取消申请单不计收入</span></div>',
      '          <el-table :data="revRows" size="small" max-height="380" empty-text="区间内无有效申请单">',
      '            <el-table-column type="index" label="#" width="52"></el-table-column>',
      '            <el-table-column :label="revDim===\'byExamType\' ? \'检查类型\' : \'执行科室\'" min-width="150" show-overflow-tooltip>',
      '              <template #default="s">{{ revRowName(s.row) }}</template>',
      '            </el-table-column>',
      '            <el-table-column prop="order_cnt" label="申请单量" width="110" align="center" sortable>',
      '              <template #default="s">{{ fmtNum(s.row.order_cnt) }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="检查收入" width="140" align="right" sortable :sort-by="\'total_charge\'">',
      '              <template #default="s"><span class="rst-money">¥{{ fmtMoney(s.row.total_charge) }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column label="占比" width="120" align="center">',
      '              <template #default="s">{{ revTotal ? pctText(toNum(s.row.total_charge) * 100 / revTotal) : \'-\' }}</template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };
})();
