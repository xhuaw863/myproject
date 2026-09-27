/* 业务统计报表: 药库统计 / 药房统计 / 收费统计(对接后端 ReportController 业务统计端点)
 * 口径(与后端 ReportService 一致, 金额单位: 元): 入/出库仅已确认单(status=1, 确认时间口径);
 * 发药仅已发药单(status=2, 发药时间口径); 退药仅已审批(status=1, 审批时间口径);
 * 收费/退费按收费单(bill_type 1/2)收费时间; 发票按创建时间; 区间留空时后端回退最近30天。
 * 视图: WarehouseReport(概况卡片+入出库按类型汇总+导出) / PharmacyReport(卡片+按日趋势折线+明细) /
 * ChargeReport(三Tab: 支付方式饼图+明细 / 退费卡片 / 发票卡片) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 共用工具(同 report.js) ================= */
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
  function num(n) { return (n === null || n === undefined) ? '0' : String(n); }
  /* 图表金额轴刻度: >=1万 显示 "X.X万" */
  function amtLabel(v) { return Number(v) >= 10000 ? (Number(v) / 10000).toFixed(1) + '万' : v; }
  /* 占比文案: 金额/总额, 总额为0返回 0.0% */
  function percentText(amount, total) {
    var t = Number(total) || 0;
    if (!t) { return '0.0%'; }
    return ((Number(amount) || 0) / t * 100).toFixed(1) + '%';
  }

  /* Element Plus 风格图表调色板(同 dashboard.js) */
  var PALETTE = HIS.theme.palette;

  /* 统计表合计行通用: cnt 列求和, amount 列求和(入/出库统计、按日发药明细共用) */
  function sumSummary(param) {
    var cnt = 0, amount = 0;
    (param.data || []).forEach(function (r) {
      cnt += Number(r.cnt) || 0;
      amount += Number(r.amount) || 0;
    });
    return (param.columns || []).map(function (col, idx) {
      if (idx === 0) { return '合计'; }
      if (col.property === 'cnt') { return String(cnt); }
      if (col.property === 'amount') { return money(amount); }
      return '';
    });
  }

  /* ================= 药库统计 ================= */
  HIS.views.WarehouseReport = {
    data: function () {
      return {
        loading: false, exporting: false,
        warehouseDefs: [],                  /* 药库下拉(启用) */
        warehouseId: '',                    /* ''=全部药库 */
        dateRange: [daysAgo(29), today()],  /* 默认最近30天 */
        summary: {},                        /* 库存概况: drugCount/totalValue/lowStockCount/nearExpCount */
        inflows: [], outflows: []           /* 已确认入库/出库单按类型汇总 */
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; }
    },
    created: function () {
      this.loadWarehouseDefs();
      this.load();
    },
    methods: {
      /* ---- 药库下拉: 当前机构启用药库(后端读隔离) ---- */
      loadWarehouseDefs: function () {
        var vm = this;
        HIS.get('/api/his/stock/warehouse-def').then(function (d) {
          vm.warehouseDefs = d || [];
        }).catch(function () { vm.warehouseDefs = []; });
      },
      /* ---- 查询参数: warehouseId(空=全部) + 日期区间 ---- */
      query: function () {
        var vm = this, p = [];
        if (vm.warehouseId) { p.push('warehouseId=' + vm.warehouseId); }
        if (vm.startDate) { p.push('startDate=' + vm.startDate); }
        if (vm.endDate) { p.push('endDate=' + vm.endDate); }
        return p.length ? '?' + p.join('&') : '';
      },
      /* ---- 概况 + 入出库统计并行取数 ---- */
      load: function () {
        var vm = this; vm.loading = true;
        var q = vm.query();
        Promise.all([
          HIS.get('/api/his/report/warehouse-stats' + q),
          HIS.get('/api/his/report/warehouse-flow' + q)
        ]).then(function (rs) {
          vm.summary = rs[0] || {};
          var flow = rs[1] || {};
          vm.inflows = flow.inflows || [];
          vm.outflows = flow.outflows || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.load(); },
      /* ---- 导出Excel: 双Sheet(库存概况+入出库统计), 与页面同区间/药库口径 ---- */
      doExport: function () {
        var vm = this; vm.exporting = true;
        HIS.download('/api/his/report/export/warehouse' + vm.query(), '药库统计.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },
      money: money, num: num, sumSummary: sumSummary
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药库统计 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(库存概况 + 已确认入出库单汇总, 金额单位: 元)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="warehouseId" placeholder="全部药库" clearable filterable style="width:180px" @change="search">',
      '      <el-option v-for="w in warehouseDefs" :key="w.id" :label="w.name" :value="w.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '  </div>',
      '  <div class="stat-grid" v-loading="loading">',
      '    <div class="stat-card"><div class="num">{{ num(summary.drugCount) }}</div><div class="lbl">在库品种数</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-gold);">¥ {{ money(summary.totalValue) }}</div><div class="lbl">库存总金额(零售价)</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-warning);">{{ num(summary.lowStockCount) }}</div><div class="lbl">低库存批次(≤预警线且有余量)</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-danger);">{{ num(summary.nearExpCount) }}</div><div class="lbl">近效期批次(30天内到期)</div></div>',
      '  </div>',
      '  <el-row :gutter="16">',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">入库统计</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">已确认入库单(确认时间口径)</span></template>',
      '        <el-table :data="inflows" v-loading="loading" border stripe size="small" show-summary :summary-method="sumSummary" empty-text="暂无入库数据">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="typeName" label="类型" min-width="100"></el-table-column>',
      '          <el-table-column prop="cnt" label="笔数" width="80" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="120" align="right" header-align="right"><template #default="s">',
      '            <b style="color:var(--yb-ink-1);">{{ money(s.row.amount) }}</b>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">出库统计</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">已确认出库单(确认时间口径)</span></template>',
      '        <el-table :data="outflows" v-loading="loading" border stripe size="small" show-summary :summary-method="sumSummary" empty-text="暂无出库数据">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column prop="typeName" label="类型" min-width="100"></el-table-column>',
      '          <el-table-column prop="cnt" label="笔数" width="80" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="120" align="right" header-align="right"><template #default="s">',
      '            <b style="color:var(--yb-ink-1);">{{ money(s.row.amount) }}</b>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      '</div>'
    ].join('\n')
  };

  /* ================= 药房统计 ================= */
  HIS.views.PharmacyReport = {
    data: function () {
      return {
        loading: false, exporting: false,
        pharmacyDefs: [],                   /* 药房下拉(启用) */
        pharmacyId: '',                     /* ''=全部药房 */
        dateRange: [daysAgo(29), today()],  /* 默认最近30天 */
        dailyStats: [],                     /* 按日发药: [{dt,cnt,amount}] */
        ret: {},                            /* 退药: {returnCount,returnAmount,dispenseCount,returnRate} */
        chart: null                         /* ECharts实例 */
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      /* 概况卡片: 发药笔数/金额 = 按日明细求和 */
      totalCnt: function () {
        return (this.dailyStats || []).reduce(function (s, r) { return s + (Number(r.cnt) || 0); }, 0);
      },
      totalAmount: function () {
        return (this.dailyStats || []).reduce(function (s, r) { return s + (Number(r.amount) || 0); }, 0);
      }
    },
    created: function () { this.loadPharmacyDefs(); },
    mounted: function () {
      var vm = this;
      /* 图表容器已渲染(首屏可见), 先init再取数; $nextTick 保证 DOM 尺寸可用 */
      vm.$nextTick(function () {
        if (window.echarts && vm.$refs.pharmacyChart) {
          vm.chart = echarts.init(vm.$refs.pharmacyChart, 'yb');
          vm.chart.showLoading('default', { text: '加载中...', color: HIS.theme.link, maskColor: 'rgba(255,255,255,.6)' });
        } else if (!window.echarts) {
          ElementPlus.ElMessage.warning('图表库(echarts)未加载, 仅显示统计卡片与明细');
        }
        vm.load();
        vm._onResize = function () { if (vm.chart && !vm.chart.isDisposed()) { vm.chart.resize(); } };
        window.addEventListener('resize', vm._onResize);
      });
    },
    /* Vue3 销毁钩子: 清理监听与图表实例防内存泄漏 */
    beforeUnmount: function () {
      window.removeEventListener('resize', this._onResize);
      if (this.chart) { this.chart.dispose(); this.chart = null; }
    },
    methods: {
      /* ---- 药房下拉: 当前机构启用药房(后端读隔离) ---- */
      loadPharmacyDefs: function () {
        var vm = this;
        HIS.get('/api/his/pharmacy/pharmacy-def').then(function (d) {
          vm.pharmacyDefs = d || [];
        }).catch(function () { vm.pharmacyDefs = []; });
      },
      /* ---- 查询参数: pharmacyId(空=全部) + 日期区间 ---- */
      query: function () {
        var vm = this, p = [];
        if (vm.pharmacyId) { p.push('pharmacyId=' + vm.pharmacyId); }
        if (vm.startDate) { p.push('startDate=' + vm.startDate); }
        if (vm.endDate) { p.push('endDate=' + vm.endDate); }
        return p.length ? '?' + p.join('&') : '';
      },
      /* ---- 按日发药 + 退药统计并行取数 ---- */
      load: function () {
        var vm = this; vm.loading = true;
        var q = vm.query();
        Promise.all([
          HIS.get('/api/his/report/pharmacy-stats' + q),
          HIS.get('/api/his/report/pharmacy-return' + q)
        ]).then(function (rs) {
          vm.dailyStats = rs[0] || [];
          vm.ret = rs[1] || {};
          vm.renderChart();
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.load(); },
      /* ---- 双轴折线: 左轴发药笔数, 右轴发药金额 ---- */
      renderChart: function () {
        var vm = this;
        if (!vm.chart || vm.chart.isDisposed()) { return; }
        vm.chart.hideLoading();
        var rows = vm.dailyStats || [];
        if (!rows.length) {
          vm.chart.clear();
          vm.chart.setOption({
            title: { text: '暂无发药数据', left: 'center', top: 'middle', textStyle: { color: HIS.theme.ink3, fontSize: 13, fontWeight: 'normal' } }
          });
          return;
        }
        vm.chart.setOption({
          color: [HIS.theme.link, HIS.theme.warning],
          tooltip: {
            trigger: 'axis',
            formatter: function (ps) {
              var html = ps[0].axisValue;
              ps.forEach(function (p) {
                var val = p.seriesName === '发药金额'
                  ? '¥ ' + money(p.value)
                  : (Number(p.value) || 0) + ' 笔';
                html += '<br/>' + p.marker + p.seriesName + ': <b>' + val + '</b>';
              });
              return html;
            }
          },
          legend: { top: 0, icon: 'roundRect', itemWidth: 12, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
          grid: { left: 64, right: 68, top: 36, bottom: 30 },
          xAxis: {
            type: 'category', boundaryGap: false,
            data: rows.map(function (d) { return d.dt; }),
            axisLabel: { color: HIS.theme.ink3 }
          },
          yAxis: [
            {
              type: 'value', name: '笔数', minInterval: 1,
              nameTextStyle: { color: HIS.theme.ink3 }, axisLabel: { color: HIS.theme.ink3 },
              splitLine: { lineStyle: { color: HIS.theme.split } }
            },
            {
              type: 'value', name: '金额(元)',
              nameTextStyle: { color: HIS.theme.ink3 },
              axisLabel: { color: HIS.theme.ink3, formatter: amtLabel },
              splitLine: { show: false }
            }
          ],
          series: [
            {
              name: '发药笔数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6, showSymbol: false,
              data: rows.map(function (d) { return Number(d.cnt) || 0; }),
              lineStyle: { width: 2.5 }
            },
            {
              name: '发药金额', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 6, showSymbol: false,
              data: rows.map(function (d) { return Number(d.amount) || 0; }),
              lineStyle: { width: 2.5, type: 'dashed' }
            }
          ]
        }, true);
      },
      /* ---- 导出Excel: 双Sheet(发药统计+退药统计), 与页面同区间/药房口径 ---- */
      doExport: function () {
        var vm = this; vm.exporting = true;
        HIS.download('/api/his/report/export/pharmacy' + vm.query(), '药房统计.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },
      money: money, num: num, sumSummary: sumSummary
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药房统计 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(发药/退药统计, 金额单位: 元)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable filterable style="width:180px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '  </div>',
      '  <div class="stat-grid" v-loading="loading">',
      '    <div class="stat-card"><div class="num">{{ num(totalCnt) }}</div><div class="lbl">发药笔数</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-gold);">¥ {{ money(totalAmount) }}</div><div class="lbl">发药金额</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-warning);">{{ num(ret.returnCount) }}</div><div class="lbl">退药单数(已审批)</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-danger);">{{ ret.returnRate || \'0.0%\' }}</div><div class="lbl">退药率(退药数/发药数)</div></div>',
      '  </div>',
      '  <el-card shadow="never" style="margin-bottom:14px;">',
      '    <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">按日发药趋势</span>',
      '      <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">蓝=发药笔数(左轴) 橙=发药金额(右轴)</span></template>',
      '    <div ref="pharmacyChart" style="width:100%;height:300px;"></div>',
      '  </el-card>',
      '  <el-card shadow="never">',
      '    <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">按日发药明细</span>',
      '      <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">退药金额合计 ¥ {{ money(ret.returnAmount) }} · 退药率 {{ ret.returnRate || \'0.0%\' }}</span></template>',
      '    <el-table :data="dailyStats" v-loading="loading" border stripe size="small" show-summary :summary-method="sumSummary" empty-text="暂无发药数据">',
      '      <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '      <el-table-column prop="dt" label="日期" width="130"></el-table-column>',
      '      <el-table-column prop="cnt" label="发药笔数" width="110" align="right" header-align="right"></el-table-column>',
      '      <el-table-column label="发药金额" min-width="130" align="right" header-align="right"><template #default="s">',
      '        <b style="color:var(--yb-ink-1);">{{ money(s.row.amount) }}</b>',
      '      </template></el-table-column>',
      '    </el-table>',
      '  </el-card>',
      '</div>'
    ].join('\n')
  };

  /* ================= 收费统计 ================= */
  HIS.views.ChargeReport = {
    data: function () {
      return {
        loading: false, exporting: false,
        /* 三个Tab: paymethod支付方式 / refund退费统计 / invoice发票统计 */
        activeTab: 'paymethod',
        dateRange: [daysAgo(29), today()],  /* 默认最近30天 */
        payList: [],                        /* 支付方式构成: [{payMethod,payMethodName,cnt,amount}] */
        refund: {},                         /* 退费: {refundCount,refundAmount,partialRefundCount,fullRefundCount} */
        invoice: {},                        /* 发票: {normalCount,voidCount,redCount,totalCount} */
        payChart: null                      /* ECharts实例(饼图) */
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      /* 支付方式金额合计(占比列分母) */
      payAmountSum: function () {
        return (this.payList || []).reduce(function (s, r) { return s + (Number(r.amount) || 0); }, 0);
      }
    },
    mounted: function () {
      var vm = this;
      /* 首屏Tab(支付方式)可见, 先init再取数; $nextTick 保证 DOM 尺寸可用 */
      vm.$nextTick(function () {
        if (window.echarts && vm.$refs.payChart) {
          vm.payChart = echarts.init(vm.$refs.payChart, 'yb');
          vm.payChart.showLoading('default', { text: '加载中...', color: HIS.theme.link, maskColor: 'rgba(255,255,255,.6)' });
        } else if (!window.echarts) {
          ElementPlus.ElMessage.warning('图表库(echarts)未加载, 仅显示表格与统计卡片');
        }
        vm.loadPay();
        vm._onResize = function () { if (vm.payChart && !vm.payChart.isDisposed()) { vm.payChart.resize(); } };
        window.addEventListener('resize', vm._onResize);
      });
    },
    /* Vue3 销毁钩子: 清理监听与图表实例防内存泄漏 */
    beforeUnmount: function () {
      window.removeEventListener('resize', this._onResize);
      if (this.payChart) { this.payChart.dispose(); this.payChart = null; }
    },
    methods: {
      /* ---- 查询参数: 日期区间(三Tab共用) ---- */
      query: function () {
        var vm = this, p = [];
        if (vm.startDate) { p.push('startDate=' + vm.startDate); }
        if (vm.endDate) { p.push('endDate=' + vm.endDate); }
        return p.length ? '?' + p.join('&') : '';
      },
      /* ---- 支付方式: 饼图 + 明细表 ---- */
      loadPay: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/report/charge-paymethod' + vm.query()).then(function (list) {
          vm.payList = list || [];
          vm.renderPayChart();
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadRefund: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/report/charge-refund' + vm.query()).then(function (d) {
          vm.refund = d || {};
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadInvoice: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/report/invoice-stats' + vm.query()).then(function (d) {
          vm.invoice = d || {};
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* ---- 支付方式饼图(按金额, 环形) ---- */
      renderPayChart: function () {
        var vm = this;
        if (!vm.payChart || vm.payChart.isDisposed()) { return; }
        vm.payChart.hideLoading();
        var rows = vm.payList || [];
        if (!rows.length) {
          vm.payChart.clear();
          vm.payChart.setOption({
            title: { text: '暂无支付数据', left: 'center', top: 'middle', textStyle: { color: HIS.theme.ink3, fontSize: 13, fontWeight: 'normal' } }
          });
          return;
        }
        vm.payChart.setOption({
          color: PALETTE,
          tooltip: {
            trigger: 'item',
            formatter: function (p) { return p.name + '<br/>金额: <b>¥ ' + money(p.value) + '</b> (' + p.percent + '%)'; }
          },
          legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: HIS.theme.ink3, fontSize: 11 } },
          series: [{
            name: '支付方式', type: 'pie', radius: ['40%', '66%'], center: ['50%', '44%'],
            avoidLabelOverlap: true,
            label: { formatter: '{b}\n{d}%', color: HIS.theme.ink3, fontSize: 11 },
            data: rows.map(function (d) {
              return { name: d.payMethodName || d.payMethod || '-', value: Number(d.amount) || 0 };
            })
          }]
        }, true);
      },
      /* ---- 查询按钮: 刷新当前Tab数据 ---- */
      search: function () { this.reloadCurrent(); },
      reloadCurrent: function () {
        var vm = this;
        if (vm.activeTab === 'paymethod') { vm.loadPay(); }
        else if (vm.activeTab === 'refund') { vm.loadRefund(); }
        else { vm.loadInvoice(); }
      },
      /* ---- Tab切换: 拉取该Tab数据; 切回支付方式时容器从隐藏恢复, 需resize重绘 ---- */
      onTabChange: function () {
        var vm = this;
        vm.reloadCurrent();
        if (vm.activeTab === 'paymethod') {
          vm.$nextTick(function () {
            if (vm.payChart && !vm.payChart.isDisposed()) {
              vm.payChart.resize();
              vm.renderPayChart();
            }
          });
        }
      },
      /* ---- 导出Excel: 三Sheet(支付方式构成+退费统计+发票统计), 与页面同区间口径 ---- */
      doExport: function () {
        var vm = this; vm.exporting = true;
        HIS.download('/api/his/report/export/charge-stats' + vm.query(), '收费统计.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },
      money: money, num: num, percentText: percentText
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">收费统计 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(支付方式 / 退费 / 发票, 金额单位: 元)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '    <span style="margin-left:auto;color:var(--yb-ink-2);font-size:13px;">统计区间: {{ startDate || \'-\' }} 至 {{ endDate || \'-\' }}</span>',
      '  </div>',
      '  <div style="margin-bottom:12px;">',
      '    <el-radio-group v-model="activeTab" @change="onTabChange">',
      '      <el-radio-button label="paymethod">支付方式</el-radio-button>',
      '      <el-radio-button label="refund">退费统计</el-radio-button>',
      '      <el-radio-button label="invoice">发票统计</el-radio-button>',
      '    </el-radio-group>',
      '  </div>',
      /* 支付方式Tab: 饼图 + 明细表 */
      '  <el-row v-show="activeTab===\'paymethod\'" :gutter="16">',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">支付方式构成</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">按金额占比</span></template>',
      '        <div ref="payChart" style="width:100%;height:320px;"></div>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">支付方式明细</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">按金额降序, 共计 ¥ {{ money(payAmountSum) }}</span></template>',
      '        <el-table :data="payList" v-loading="loading" border stripe size="small" empty-text="暂无支付数据">',
      '          <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '          <el-table-column label="支付方式" min-width="110"><template #default="s">{{ s.row.payMethodName || s.row.payMethod || \'-\' }}</template></el-table-column>',
      '          <el-table-column prop="cnt" label="笔数" width="80" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="120" align="right" header-align="right"><template #default="s">',
      '            <b style="color:var(--yb-ink-1);">{{ money(s.row.amount) }}</b>',
      '          </template></el-table-column>',
      '          <el-table-column label="占比" width="90" align="right" header-align="right"><template #default="s">',
      '            {{ percentText(s.row.amount, payAmountSum) }}',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      /* 退费统计Tab: 汇总卡片 */
      '  <div v-show="activeTab===\'refund\'">',
      '    <div class="stat-grid" v-loading="loading">',
      '      <div class="stat-card"><div class="num">{{ num(refund.refundCount) }}</div><div class="lbl">退费笔数</div></div>',
      '      <div class="stat-card"><div class="num" style="color:var(--yb-danger);">¥ {{ money(refund.refundAmount) }}</div><div class="lbl">退费金额</div></div>',
      '      <div class="stat-card"><div class="num">{{ num(refund.fullRefundCount) }}</div><div class="lbl">全额退费(未关联原单)</div></div>',
      '      <div class="stat-card"><div class="num" style="color:var(--yb-warning);">{{ num(refund.partialRefundCount) }}</div><div class="lbl">部分退费(关联原单)</div></div>',
      '    </div>',
      '    <div style="color:var(--yb-ink-2);font-size:12px;">口径: 按退费单(收费时间)统计; 关联原单的差额退款记为部分退费, 未关联原单的整单退款记为全额退费</div>',
      '  </div>',
      /* 发票统计Tab: 汇总卡片 */
      '  <div v-show="activeTab===\'invoice\'">',
      '    <div class="stat-grid" v-loading="loading">',
      '      <div class="stat-card"><div class="num" style="color:var(--yb-success);">{{ num(invoice.normalCount) }}</div><div class="lbl">正常发票(已开具)</div></div>',
      '      <div class="stat-card"><div class="num" style="color:var(--yb-warning);">{{ num(invoice.voidCount) }}</div><div class="lbl">已作废</div></div>',
      '      <div class="stat-card"><div class="num" style="color:var(--yb-danger);">{{ num(invoice.redCount) }}</div><div class="lbl">已红冲</div></div>',
      '      <div class="stat-card"><div class="num">{{ num(invoice.totalCount) }}</div><div class="lbl">发票总计</div></div>',
      '    </div>',
      '    <div style="color:var(--yb-ink-2);font-size:12px;">口径: 按发票创建时间统计; 正常=类型NORMAL且状态已开具, 作废/红冲按发票状态统计</div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
