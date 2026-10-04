/* 工作台(运营Dashboard: 概览统计卡片 + ECharts图表) + 通用占位页 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) {
    var d = new Date();
    d.setDate(d.getDate() - n);
    return fmtDate(d);
  }
  function money(n) { return (n === null || n === undefined) ? '0.00' : Number(n).toFixed(2); }

  /* Element Plus 风格图表调色板 */
  var PALETTE = HIS.theme.palette;

  /* ================= 工作台(Dashboard) ================= */
  HIS.views.Dashboard = {
    data: function () {
      return {
        user: HIS.getUser() || {},
        overview: null,          /* {today:{...}, yesterday:{...}, month:{...}} */
        overviewLoading: false,
        chartReady: false,       /* ECharts 实例化完成(脚本缺失时保持false并提示) */
        revenueGranularity: 'day',
        /* 危急值待确认(2026-09 集成: /api/medtech/critical-values?status=2 已通知待接收, 徽章+跳转) */
        criticalTodo: 0,
        criticalLoading: false,
        /* 图表实例(非响应式, 不入data避免Vue代理开销) */
        revenueChart: null, deptChart: null, visitChart: null, drugChart: null,
        refreshTimer: null
      };
    },
    computed: {
      roleName: function () { return HIS.roleLabel(this.user.role); },
      todayStr: function () { return today(); },
      /* 统计卡片: 数值 + 环比(对昨日) + 本月累计, 显示文本在computed内拼好(模板不访问Math等全局) */
      cards: function () {
        var o = this.overview || {};
        var t = o.today || {}, y = o.yesterday || {}, m = o.month || {};
        return [
          {
            icon: '📋', tone: 'blue', title: '今日挂号',
            num: (t.regCount === null || t.regCount === undefined) ? '—' : t.regCount,
            sub: '人次', monthText: '本月 ' + (m.regCount == null ? '—' : m.regCount),
            ratio: this.calcRatio(t.regCount, y.regCount)
          },
          {
            icon: '🩺', tone: 'green', title: '今日就诊',
            num: (t.visitCount === null || t.visitCount === undefined) ? '—' : t.visitCount,
            sub: '人次', monthText: '本月 ' + (m.visitCount == null ? '—' : m.visitCount),
            ratio: this.calcRatio(t.visitCount, y.visitCount)
          },
          {
            icon: '💰', tone: 'orange', title: '今日收费',
            num: (t.chargeAmount == null) ? '¥ —' : '¥ ' + money(t.chargeAmount),
            sub: (t.chargeCount == null ? '—' : t.chargeCount) + ' 笔',
            monthText: '本月 ¥ ' + money(m.chargeAmount),
            ratio: this.calcRatio(t.chargeAmount, y.chargeAmount)
          },
          {
            icon: '💊', tone: 'red', title: '今日发药',
            num: (t.dispenseCount === null || t.dispenseCount === undefined) ? '—' : t.dispenseCount,
            sub: '笔', monthText: '本月 ' + (m.dispenseCount == null ? '—' : m.dispenseCount),
            ratio: this.calcRatio(t.dispenseCount, y.dispenseCount)
          }
        ];
      }
    },
    created: function () { this.loadDashboard(); this.loadCriticalTodo(); },
    mounted: function () {
      var vm = this;
      vm.$nextTick(function () { vm.initCharts(); });
      /* 定时刷新(5分钟): 概览 + 图表数据 */
      vm.refreshTimer = setInterval(function () {
        vm.loadDashboard();
        vm.loadCriticalTodo();
        vm.loadRevenue(); vm.loadDeptRevenue(); vm.loadVisits(); vm.loadDrugUsage();
      }, 5 * 60 * 1000);
    },
    /* Vue3 销毁钩子(beforeDestroy为Vue2名称, 在Vue3中被忽略): 清理定时器/监听/图表实例防内存泄漏 */
    beforeUnmount: function () {
      var vm = this;
      if (vm.refreshTimer) { clearInterval(vm.refreshTimer); vm.refreshTimer = null; }
      window.removeEventListener('resize', vm._onResize);
      ['revenueChart', 'deptChart', 'visitChart', 'drugChart'].forEach(function (k) {
        if (vm[k]) { vm[k].dispose(); vm[k] = null; }
      });
    },
    methods: {
      /* ===== 初始化图表实例 + 首轮取数 ===== */
      initCharts: function () {
        var vm = this;
        if (!window.echarts) {
          ElementPlus.ElMessage.warning('图表库(echarts)未加载, 仅显示统计卡片');
          return;
        }
        vm.revenueChart = echarts.init(vm.$refs.revenueChart, 'yb');
        vm.deptChart = echarts.init(vm.$refs.deptChart, 'yb');
        vm.visitChart = echarts.init(vm.$refs.visitChart, 'yb');
        vm.drugChart = echarts.init(vm.$refs.drugChart, 'yb');
        vm.chartReady = true;
        /* 空态骨架: 数据到达前显示loading动画 */
        [vm.revenueChart, vm.deptChart, vm.visitChart, vm.drugChart].forEach(function (c) {
          c.showLoading('default', { text: '加载中...', color: HIS.theme.link, maskColor: 'rgba(255,255,255,.6)' });
        });
        vm.loadRevenue(); vm.loadDeptRevenue(); vm.loadVisits(); vm.loadDrugUsage();
        /* 窗口resize自适应 */
        vm._onResize = function () {
          vm.revenueChart && vm.revenueChart.resize();
          vm.deptChart && vm.deptChart.resize();
          vm.visitChart && vm.visitChart.resize();
          vm.drugChart && vm.drugChart.resize();
        };
        window.addEventListener('resize', vm._onResize);
      },
      /* ===== 概览统计卡片 ===== */
      loadDashboard: function () {
        var vm = this; vm.overviewLoading = true;
        HIS.get('/api/his/report/dashboard').then(function (data) {
          vm.overview = data;
        }).catch(HIS.notifyError).finally(function () { vm.overviewLoading = false; });
      },
      /* 环比: 昨日无数据(null/0)返回null显示"--"; 返回Number(toFixed(1)) */
      calcRatio: function (todayVal, yesterdayVal) {
        var t = Number(todayVal) || 0;
        var y = Number(yesterdayVal);
        if (yesterdayVal === null || yesterdayVal === undefined || !y) { return null; }
        return Number(((t - y) / y * 100).toFixed(1));
      },
      ratioText: function (r) {
        if (r === null || r === undefined) { return '--'; }
        if (r > 0) { return '↑ ' + r + '%'; }
        if (r < 0) { return '↓ ' + Math.abs(r) + '%'; }
        return '--';
      },
      ratioColor: function (r) {
        if (r === null || r === undefined || r === 0) { return HIS.theme.ink3; }
        return r > 0 ? HIS.theme.success : HIS.theme.danger;
      },
      /* ===== 危急值待确认(2026-09 集成: 医技危急值闭环 status=2 已通知待接收) =====
       * 接口不可用(无机构/无权限)时静默置 0, 不阻断工作台; 定时随概览一并刷新 */
      loadCriticalTodo: function () {
        var vm = this;
        vm.criticalLoading = true;
        HIS.get('/api/medtech/critical-values?status=2&page=1&size=1').then(function (d) {
          var n = d && d.total;
          vm.criticalTodo = (n === null || n === undefined) ? 0 : Number(n) || 0;
        }).catch(function () { vm.criticalTodo = 0; })
          .finally(function () { vm.criticalLoading = false; });
      },
      /* 跳转危急值管理页(菜单 key=medtech-critical, HIS.go 由主布局注册) */
      goCritical: function () {
        if (typeof HIS.go === 'function') { HIS.go('medtech-critical'); }
      },
      /* ===== 收入趋势(折线, 近30天, 日/周/月粒度) ===== */
      loadRevenue: function () {
        var vm = this;
        if (!vm.revenueChart) { return; }
        HIS.get('/api/his/report/revenue?granularity=' + vm.revenueGranularity).then(function (data) {
          if (!vm.revenueChart || vm.revenueChart.isDisposed()) { return; } /* 视图已卸载(切菜单)时图表实例置 null, 异步回调节流防护 */
          var rows = data || [];
          vm.revenueChart.hideLoading();
          vm.revenueChart.setOption({
            color: [HIS.theme.link],
            tooltip: {
              trigger: 'axis',
              formatter: function (ps) {
                var p = ps[0];
                return p.name + '<br/>收入: <b>¥ ' + money(p.value) + '</b><br/>笔数: ' + (rows[p.dataIndex] ? (rows[p.dataIndex].count || 0) : 0);
              }
            },
            grid: { left: 64, right: 20, top: 40, bottom: 32 },
            xAxis: { type: 'category', boundaryGap: false, data: rows.map(function (d) { return d.date; }), axisLabel: { color: HIS.theme.ink3 } },
            yAxis: { type: 'value', name: '金额(元)', nameTextStyle: { color: HIS.theme.ink3 }, axisLabel: { color: HIS.theme.ink3, formatter: function (v) { return v >= 10000 ? (v / 10000) + '万' : v; } }, splitLine: { lineStyle: { color: HIS.theme.split } } },
            series: [{
              name: '收入', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6, showSymbol: false,
              data: rows.map(function (d) { return d.amount; }),
              lineStyle: { width: 2.5 },
              areaStyle: {
                color: {
                  type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
                  colorStops: [
                    { offset: 0, color: 'rgba(64,158,255,.28)' },
                    { offset: 1, color: 'rgba(64,158,255,.02)' }
                  ]
                }
              }
            }]
          });
        }).catch(HIS.notifyError);
      },
      /* ===== 科室收入占比(饼图, 近30天) ===== */
      loadDeptRevenue: function () {
        var vm = this;
        if (!vm.deptChart) { return; }
        HIS.get('/api/his/report/dept-revenue').then(function (data) {
          if (!vm.deptChart || vm.deptChart.isDisposed()) { return; }
          var rows = data || [];
          vm.deptChart.hideLoading();
          /* 科室过多时仅展示前8, 其余并入"其他" */
          var show = rows.slice(0, 8).map(function (d) { return { name: d.deptName, value: Number(d.amount) || 0 }; });
          if (rows.length > 8) {
            var rest = 0;
            rows.slice(8).forEach(function (d) { rest += Number(d.amount) || 0; });
            show.push({ name: '其他(' + (rows.length - 8) + '科)', value: rest });
          }
          vm.deptChart.setOption({
            color: PALETTE,
            tooltip: {
              trigger: 'item',
              formatter: function (p) { return p.name + '<br/>金额: <b>¥ ' + money(p.value) + '</b> (' + p.percent + '%)'; }
            },
            legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
            series: [{
              name: '科室收入', type: 'pie', radius: ['42%', '66%'], center: ['50%', '44%'],
              avoidLabelOverlap: true,
              itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
              label: { show: false },
              emphasis: {
                label: { show: true, fontSize: 13, fontWeight: 600, formatter: '{b}\n{d}%' },
                itemStyle: { shadowBlur: 12, shadowColor: 'rgba(0,0,0,.18)' }
              },
              data: show
            }]
          });
        }).catch(HIS.notifyError);
      },
      /* ===== 就诊量趋势(柱状, 近7天, 无数据日期补0) ===== */
      loadVisits: function () {
        var vm = this;
        if (!vm.visitChart) { return; }
        var start = daysAgo(6), end = today();
        HIS.get('/api/his/report/visits?granularity=day&startDate=' + start + '&endDate=' + end).then(function (data) {
          if (!vm.visitChart || vm.visitChart.isDisposed()) { return; }
          var byDate = {};
          (data || []).forEach(function (d) { byDate[d.date] = d.count; });
          var days = [], counts = [];
          for (var i = 6; i >= 0; i--) {
            var d = daysAgo(i);
            days.push(d.slice(5));           /* MM-DD 更紧凑 */
            counts.push(byDate[d] || 0);
          }
          vm.visitChart.hideLoading();
          vm.visitChart.setOption({
            color: [HIS.theme.success],
            tooltip: { trigger: 'axis', formatter: function (ps) { var p = ps[0]; return p.name + '<br/>就诊量: <b>' + p.value + '</b> 人次'; } },
            grid: { left: 44, right: 20, top: 40, bottom: 32 },
            xAxis: { type: 'category', data: days, axisLabel: { color: HIS.theme.ink3 } },
            yAxis: { type: 'value', name: '人次', nameTextStyle: { color: HIS.theme.ink3 }, minInterval: 1, axisLabel: { color: HIS.theme.ink3 }, splitLine: { lineStyle: { color: HIS.theme.split } } },
            series: [{
              name: '就诊量', type: 'bar', barMaxWidth: 26,
              data: counts,
              itemStyle: { borderRadius: [4, 4, 0, 0] },
              label: { show: true, position: 'top', color: HIS.theme.success, fontSize: 11 }
            }]
          });
        }).catch(HIS.notifyError);
      },
      /* ===== 药品使用TOP10(横向条形, 近30天, 金额降序最大在顶) ===== */
      loadDrugUsage: function () {
        var vm = this;
        if (!vm.drugChart) { return; }
        HIS.get('/api/his/report/drug-usage?topN=10').then(function (data) {
          if (!vm.drugChart || vm.drugChart.isDisposed()) { return; }
          var rows = (data || []).slice().reverse();   /* yAxis自下而上, 反转后最大在顶部 */
          vm.drugChart.hideLoading();
          vm.drugChart.setOption({
            color: [HIS.theme.warning],
            tooltip: {
              trigger: 'axis', axisPointer: { type: 'shadow' },
              formatter: function (ps) {
                var p = ps[0];
                var r = rows[p.dataIndex] || {};
                return (r.drugName || p.name) + '<br/>金额: <b>¥ ' + money(p.value) + '</b><br/>数量: ' + (r.qty || 0);
              }
            },
            grid: { left: 10, right: 56, top: 16, bottom: 10, containLabel: true },
            xAxis: { type: 'value', axisLabel: { color: HIS.theme.ink3, formatter: function (v) { return v >= 10000 ? (v / 10000) + '万' : v; } }, splitLine: { lineStyle: { color: HIS.theme.split } } },
            yAxis: {
              type: 'category', data: rows.map(function (d) { return d.drugName; }),
              axisLabel: {
                color: HIS.theme.ink3, fontSize: 11,
                formatter: function (s) { return s.length > 6 ? s.slice(0, 5) + '…' : s; }
              },
              axisTick: { show: false }
            },
            series: [{
              name: '药品金额', type: 'bar', barMaxWidth: 14,
              data: rows.map(function (d) { return d.amount; }),
              itemStyle: { borderRadius: [0, 4, 4, 0] },
              label: { show: true, position: 'right', color: HIS.theme.warning, fontSize: 11, formatter: function (p) { var v = Number(p.value) || 0; return v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(v); } }
            }]
          });
        }).catch(HIS.notifyError);
      },
      money: money
    },
    template: [
      '<div>',
      /* ---- 欢迎条 ---- */
      '  <div class="page-card" style="display:flex;align-items:center;justify-content:space-between;">',
      '    <div>',
      '      <div style="font-size:16px;color:var(--yb-ink-1);font-weight:600;">{{ user.realName || user.username }}，欢迎回来</div>',
      '      <div style="color:var(--yb-ink-2);font-size:13px;margin-top:6px;">',
      '        {{ user.tenantName || "-" }} · {{ user.orgName || "-" }} · {{ roleName }} · 数据每5分钟自动刷新',
      '        <el-tag v-if="overviewLoading" size="small" type="info" style="margin-left:8px;">刷新中...</el-tag>',
      '      </div>',
      '    </div>',
      '    <div style="text-align:right;color:var(--yb-ink-2);font-size:12px;line-height:1.8;">',
      '      <div v-loading="criticalLoading" @click="goCritical" style="cursor:pointer;margin-bottom:4px;" title="已通知待接收的危急值记录">',
      '        <el-badge :value="criticalTodo" :max="99" type="danger" :hidden="criticalTodo <= 0">',
      '          <span :class="criticalTodo > 0 ? \'ds-critical-tag-on\' : \'ds-critical-tag-off\'">危急值待确认</span>',
      '        </el-badge>',
      '        <span :style="{ marginLeft: \'6px\', fontWeight: \'700\', color: criticalTodo > 0 ? \'var(--yb-danger)\' : \'var(--yb-ink-2)\' }">{{ criticalTodo }} 项已通知待接收</span>',
      '        <span style="color:var(--yb-brand);">前往处理 →</span>',
      '      </div>',
      '      <div>今日: <b style="color:var(--yb-link);">{{ todayStr }}</b></div>',
      '    </div>',
      '  </div>',
      /* ---- 第一行: 四个统计卡片 ---- */
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="6" v-for="c in cards" :key="c.title">',
      '      <div class="ds-card" :class="\'tone-\' + c.tone" v-loading="overviewLoading">',
      '        <div class="ds-head">',
      '          <div class="ds-ico">{{ c.icon }}</div>',
      '          <div class="ds-title">{{ c.title }}<span class="ds-sub">{{ c.sub }}</span></div>',
      '        </div>',
      '        <div class="ds-num">{{ c.num }}</div>',
      '        <div class="ds-foot">',
      '          <span>较昨日 <b :style="{color: ratioColor(c.ratio)}">{{ ratioText(c.ratio) }}</b></span>',
      '          <span>{{ c.monthText }}</span>',
      '        </div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      /* ---- 第二行: 收入趋势 + 科室收入占比 ---- */
      '  <el-row :gutter="16" style="margin-bottom:14px;">',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card">',
      '        <template #header>',
      '          <div class="chart-hd">',
      '            <span class="t">收入趋势（近30天）</span>',
      '            <el-radio-group v-model="revenueGranularity" size="small" @change="loadRevenue">',
      '              <el-radio-button label="day">日</el-radio-button>',
      '              <el-radio-button label="week">周</el-radio-button>',
      '              <el-radio-button label="month">月</el-radio-button>',
      '            </el-radio-group>',
      '          </div>',
      '        </template>',
      '        <div ref="revenueChart" class="chart-box"></div>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card">',
      '        <template #header><div class="chart-hd"><span class="t">科室收入占比（近30天）</span></div></template>',
      '        <div ref="deptChart" class="chart-box"></div>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      /* ---- 第三行: 就诊量趋势 + 药品使用TOP10 ---- */
      '  <el-row :gutter="16">',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card">',
      '        <template #header><div class="chart-hd"><span class="t">就诊量趋势（近7天）</span></div></template>',
      '        <div ref="visitChart" class="chart-box"></div>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never" class="chart-card">',
      '        <template #header><div class="chart-hd"><span class="t">药品使用 TOP10（近30天 · 按金额）</span></div></template>',
      '        <div ref="drugChart" class="chart-box"></div>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      '</div>'
    ].join('\n')
  };

  /* ================= 通用占位页(建设中模块) ================= */
  HIS.views.Placeholder = {
    props: {
      title: { type: String, default: '功能模块' },
      phase: { type: String, default: 'P1' }
    },
    template: [
      '<div class="page-card">',
      '  <div class="placeholder">',
      '    <div class="big">🚧</div>',
      '    <h3 style="color:var(--yb-ink-1);">{{ title }}</h3>',
      '    <p style="margin-top:10px;color:var(--yb-ink-2);">该模块建设中，计划在 <b style="color:var(--yb-brand);">{{ phase }}</b> 阶段交付。</p>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
