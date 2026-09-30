/* ============================================================================
 * 住院报表中心(InpReportCenter)
 *
 * 注册: HIS.views.InpReportCenter(独立菜单入口页, 由 app.js 按 comp 名挂载)。
 * 布局: 顶部通用筛选栏(区间/科室/病区/查询/导出Excel/导出CSV) + el-tabs 五页签(数字列点击钻取):
 *   1 床位统计 —— 4 数字卡(总床位/已占用/使用率/空床) + 科室使用率柱状图 + 科室明细表
 *                 GET /api/his/inp/report/bed-stats
 *   2 费用分析 —— 3 数字卡(总费/人均/日均) + 费用构成饼图 + 每日趋势折线 + 科室TOP10表
 *                 GET /api/his/inp/report/fee-analysis
 *   3 科室经营 —— 收入/出入院/手术量/平均住院日/药占比(>30%红色预警)/耗材比(进度条)
 *                 GET /api/his/inp/report/dept-business
 *   4 住院日报 —— 6 数字卡(新入/出院/在院/转入/转出/死亡) + 近30天趋势三线折线
 *                 GET /api/his/inp/report/daily-summary
 *   5 DRG/DIP  —— 病组分布表 + 柱状图; 无分组数据时给友好空态
 *                 GET /api/his/inp/report/drg-analysis
 *
 * 字段口径(对齐后端 InpReportService):
 *   - 比率类(rate/drugRatio/materialRatio/percentage)后端返回 0-1 小数, 前端 ×100 展示;
 *   - 报表接口返回驼峰键; 除零后端回落 0;
 *   - daily-summary 的统计日取筛选区间结束日(date 参数), trend 为截至该日的 30 天;
 *   - fee-analysis.topDepts 仅含 deptId/deptName/totalAmount, 前端并行取 dept-business
 *     合并「药占比」, 「人均」按该科室入院数换算(无入院数显示 '-');
 *   - 病区筛选(wardId)仅作用于床位统计, 其余报表按科室/机构口径(后端如此, 前端原样透传)。
 *
 * 图表约定: echarts.init(el, 'yb') + 色源 HIS.theme(canvas 不识别 CSS 变量);
 *           隐藏页签容器宽度为 0, 故在数据就绪 + $nextTick 后再初始化(带重试);
 *           window resize 统一 resize, 卸载时 dispose。
 * 样式: <style id="inp-report-style"> 一次性注入, 类前缀 ir-*(不动公共 css)。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var T = HIS.theme || {};

  /* ---- 私有样式一次性注入 ---- */
  (function ensureInpReportStyles() {
    if (document.getElementById('inp-report-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-report-style';
    st.textContent = [
      /* 筛选栏 */
      '.ir-filter { display:flex; gap:10px; align-items:center; flex-wrap:wrap; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:12px 16px; box-shadow:var(--yb-sh-1); }',
      '.ir-filter .spacer { flex:1; }',
      /* 数字卡片: 大数字 + 小标签 + 底部装饰色条 */
      '.ir-kpis { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; margin:16px 0; }',
      '.ir-kpis.cols-3 { grid-template-columns:repeat(3,1fr); }',
      '.ir-kpis.cols-6 { grid-template-columns:repeat(6,1fr); }',
      '.ir-kpi { position:relative; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:16px 18px 22px; box-shadow:var(--yb-sh-1); overflow:hidden; }',
      '.ir-kpi .num { font-size:30px; font-weight:700; line-height:1.12; color:var(--yb-brand); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); letter-spacing:-.02em; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.ir-kpis.cols-6 .num { font-size:26px; }',
      '.ir-kpi .lbl { color:var(--yb-ink-3); font-size:13px; margin-top:6px; }',
      '.ir-kpi .ir-kpi-foot { position:absolute; left:0; right:0; bottom:0; height:4px; background:var(--yb-brand); opacity:.92; }',
      '.ir-kpi.is-dim .num { color:var(--yb-ink-4); }',
      '.ir-kpi.is-dim .ir-kpi-foot { background:var(--yb-border-strong); opacity:.7; }',
      /* 图表/表格卡片 */
      '.ir-card { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px; box-shadow:var(--yb-sh-1); }',
      '.ir-card-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); }',
      '.ir-card-title .ir-note { font-weight:400; }',
      '.ir-chart { width:100%; height:320px; }',
      '.ir-chart.sm { height:280px; }',
      '.ir-cols { display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-bottom:16px; }',
      '.ir-cols.wide-right { grid-template-columns:minmax(320px,5fr) minmax(420px,7fr); }',
      '.ir-table-card { margin-bottom:16px; }',
      '.ir-pager { display:flex; justify-content:flex-end; padding-top:10px; }',
      '.ir-note { font-size:12px; color:var(--yb-ink-4); }',
      '.ir-money { font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.ir-rate-warn { color:var(--yb-danger); font-weight:600; }',
      /* 可钻取列(数字列点击下钻) */
      '.ir-drill { cursor:pointer; text-decoration:underline dotted; text-underline-offset:3px; }',
      '.ir-drill:hover { color:var(--yb-link); font-weight:600; }',
      '@media (max-width:1280px) { .ir-kpis.cols-6 { grid-template-columns:repeat(3,1fr); } .ir-cols { grid-template-columns:1fr; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ---- 日期 / 数值工具 ---- */
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function lastNDays(n) {
    var end = new Date();
    var start = new Date();
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
  /* 0-1 小数 → 百分数值(保留1位) */
  function pct100(v) { return Math.round(toNum(v) * 1000) / 10; }
  function pctText(v) { return v == null ? '-' : pct100(v).toFixed(1) + '%'; }
  function mmdd(d) { return String(d == null ? '' : d).slice(5); }

  /* CSV 导出(前端生成: BOM 防乱码 + Blob 下载) */
  function csvDownload(filename, headers, rows) {
    var lines = [headers].concat(rows || []).map(function (r) {
      return r.map(function (v) {
        var s = v == null ? '' : String(v);
        return '"' + s.replace(/"/g, '""') + '"';
      }).join(',');
    });
    var blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    var a = document.createElement('a');
    var u = URL.createObjectURL(blob);
    a.href = u;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 1000);
  }

  /* ========================================================================
   * 住院报表中心组件
   * ====================================================================== */
  HIS.views.InpReportCenter = {
    data: function () {
      return {
        /* 通用筛选: 默认最近30天; '' / null = 全院 / 全部病区 */
        dateRange: lastNDays(30),
        deptId: '',
        wardId: '',
        depts: [],
        wards: [],
        activeTab: 'bed',

        /* Tab1 床位统计 */
        bed: { summary: null, byDept: [], byWard: [] },
        bedLoading: false,
        bedLoaded: false,
        bedPage: 1,
        bedSize: 20,

        /* Tab2 费用分析 */
        fee: { startDate: '', endDate: '', totalAmount: 0, patientCount: 0, avgCostPerPatient: 0, byType: [], dailyTrend: [], topDepts: [] },
        feeBizMap: {},
        feeLoading: false,
        feeLoaded: false,

        /* Tab3 科室经营 */
        biz: { startDate: '', endDate: '', items: [] },
        bizLoading: false,
        bizLoaded: false,
        bizPage: 1,
        bizSize: 20,

        /* Tab4 住院日报 */
        daily: { date: '', today: null, trend: [] },
        dailyLoading: false,
        dailyLoaded: false,

        /* Tab5 DRG/DIP */
        drg: { groupDistribution: [], dipDistribution: [], groupSettleCount: 0, message: null },
        drgMode: 'drg',
        drgLoading: false,
        drgLoaded: false,

        /* Excel导出(xlsx) */
        excelLoading: false,

        /* 报表钻取弹窗 */
        drill: { visible: false, type: '', deptId: null, title: '', dateFrom: '', dateTo: '', page: 1, size: 20, total: 0, records: [], loading: false }
      };
    },

    computed: {
      /* ---- Tab1 ---- */
      bedAll: function () {
        return (this.bed.byDept || []).map(function (r) {
          return {
            deptId: r.deptId,
            deptName: r.deptName,
            total: toNum(r.total),
            occupied: toNum(r.occupied),
            free: toNum(r.total) - toNum(r.occupied),
            rate: toNum(r.rate)
          };
        }).sort(function (a, b) { return b.total - a.total; });
      },
      bedTotal: function () { return this.bedAll.length; },
      bedRows: function () {
        var s = (this.bedPage - 1) * this.bedSize;
        return this.bedAll.slice(s, s + this.bedSize);
      },
      bedKpis: function () {
        var s = this.bed.summary;
        if (!this.bedLoaded || !s) { return []; }
        var rate = toNum(s.occupancyRate);
        return [
          { num: fmtNum(s.totalBeds), lbl: '总床位数', color: T.brand },
          { num: fmtNum(s.occupiedBeds), lbl: '已占用', color: T.warning },
          { num: pctText(rate), lbl: '床位使用率', color: rate >= 0.85 ? T.danger : T.success },
          { num: fmtNum(toNum(s.totalBeds) - toNum(s.occupiedBeds)), lbl: '空床数', color: T.teal }
        ];
      },

      /* ---- Tab2 ---- */
      feeDays: function () {
        var r = this.dateRange;
        if (!r || !r[0] || !r[1]) { return 30; }
        var d = (new Date(r[1]).getTime() - new Date(r[0]).getTime()) / 86400000 + 1;
        return d > 0 ? d : 1;
      },
      feeKpis: function () {
        if (!this.feeLoaded) { return []; }
        var f = this.fee || {};
        return [
          { num: '¥' + fmtMoney(f.totalAmount), lbl: '总费用(区间)', color: T.brand },
          { num: '¥' + fmtMoney(f.avgCostPerPatient), lbl: '人均费用', color: T.teal },
          { num: '¥' + fmtMoney(toNum(f.totalAmount) / this.feeDays), lbl: '日均费用(区间)', color: T.gold }
        ];
      },
      feeTopRows: function () {
        var map = this.feeBizMap || {};
        return (this.fee.topDepts || []).map(function (d, i) {
          var biz = map[d.deptId] || null;
          var admits = biz ? toNum(biz.admitCount) : 0;
          return {
            rank: i + 1,
            deptId: d.deptId,
            deptName: d.deptName,
            totalAmount: toNum(d.totalAmount),
            perCap: admits > 0 ? toNum(d.totalAmount) / admits : null,
            drugRatio: biz ? toNum(biz.drugRatio) : null
          };
        });
      },

      /* ---- Tab3 ---- */
      bizTotal: function () { return (this.biz.items || []).length; },
      bizRows: function () {
        var s = (this.bizPage - 1) * this.bizSize;
        return (this.biz.items || []).slice(s, s + this.bizSize);
      },

      /* ---- Tab4 ---- */
      dailyKpis: function () {
        var t = this.daily.today;
        if (!this.dailyLoaded || !t) { return []; }
        return [
          { num: fmtNum(t.newAdmit), lbl: '新入院', color: T.link },
          { num: fmtNum(t.discharge), lbl: '出院', color: T.success },
          { num: fmtNum(t.inHospital), lbl: '在院', color: T.brand },
          { num: fmtNum(t.transferIn), lbl: '转入', color: T.teal },
          { num: fmtNum(t.transferOut), lbl: '转出', color: T.warning },
          { num: fmtNum(t.death), lbl: '死亡', color: T.danger }
        ];
      },

      /* ---- Tab5 ---- */
      drgHasData: function () {
        return (this.drg.groupDistribution || []).length > 0 || (this.drg.dipDistribution || []).length > 0;
      },
      drgBoth: function () {
        return (this.drg.groupDistribution || []).length > 0 && (this.drg.dipDistribution || []).length > 0;
      },
      drgRows: function () {
        return this.drgMode === 'dip' ? (this.drg.dipDistribution || []) : (this.drg.groupDistribution || []);
      },
      drgEmpty: function () { return this.drgLoaded && !this.drgHasData; },

      /* 当前页签名(导出文件名用) */
      currentTabLabel: function () {
        var m = { bed: '床位统计', fee: '费用分析', biz: '科室经营', daily: '住院日报', drg: 'DRG-DIP分析' };
        return m[this.activeTab] || '报表';
      },

      /* ---- 钻取弹窗 ---- */
      /* 钻取动态列: fee/dept=费用明细10列, bed=在院患者10列 */
      drillColumns: function () {
        if (this.drill.type === 'bed') {
          return [
            { prop: 'inpNo', label: '住院号', minWidth: 110 },
            { prop: 'patientName', label: '患者', minWidth: 90 },
            { prop: 'gender', label: '性别', width: 56 },
            { prop: 'age', label: '年龄', width: 56 },
            { prop: 'deptName', label: '科室', minWidth: 110 },
            { prop: 'wardName', label: '病区', minWidth: 100 },
            { prop: 'bedNo', label: '床号', width: 64 },
            { prop: 'admitDate', label: '入院日期', minWidth: 100 },
            { prop: 'visitStatusText', label: '状态', width: 90 },
            { prop: 'doctorName', label: '主治医师', minWidth: 90 }
          ];
        }
        return [
          { prop: 'chargeDate', label: '费用日期', minWidth: 100 },
          { prop: 'inpNo', label: '住院号', minWidth: 100 },
          { prop: 'patientName', label: '患者', minWidth: 80 },
          { prop: 'deptName', label: '科室', minWidth: 100 },
          { prop: 'feeTypeName', label: '费用类别', width: 80 },
          { prop: 'itemCode', label: '项目编码', minWidth: 100 },
          { prop: 'itemName', label: '项目名称', minWidth: 140 },
          { prop: 'quantity', label: '数量', width: 70, align: 'right' },
          { prop: 'unitPrice', label: '单价(元)', width: 90, align: 'right' },
          { prop: 'amount', label: '金额(元)', width: 100, align: 'right' }
        ];
      },
      /* 钻取副标题(区间/总数) */
      drillSubtitle: function () {
        var d = this.drill;
        if (d.type === 'bed') { return '当前在院患者 · 共 ' + toNum(d.total) + ' 人'; }
        return (d.dateFrom || '…') + ' ~ ' + (d.dateTo || '…') + ' · 共 ' + toNum(d.total) + ' 条';
      }
    },

    methods: {
      /* 模板小工具(方法形式暴露) */
      toNum: toNum,
      fmtNum: fmtNum,
      fmtMoney: fmtMoney,
      pct100: pct100,
      pctText: pctText,
      mmdd: mmdd,
      progPct: function (v) {
        var p = Math.round(toNum(v) * 100);
        return Math.max(0, Math.min(100, p));
      },
      drugColor: function (v) { return toNum(v) > 0.3 ? (T.danger || '#c74f4f') : (T.link || '#2c78c7'); },
      matColor: function () { return T.teal || '#048671'; },

      /* ==================== 基础 ==================== */

      /* 通用筛选参数(区间/科室/病区; '' 与 null 不传) */
      baseParams: function () {
        var p = new URLSearchParams();
        if (this.dateRange && this.dateRange[0]) { p.append('startDate', this.dateRange[0]); }
        if (this.dateRange && this.dateRange[1]) { p.append('endDate', this.dateRange[1]); }
        if (this.deptId !== '' && this.deptId != null) { p.append('deptId', this.deptId); }
        if (this.wardId !== '' && this.wardId != null) { p.append('wardId', this.wardId); }
        return p;
      },

      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.depts = l || []; }).catch(function () { vm.depts = []; });
        HIS.get('/api/his/inp/bed/ward/list').then(function (l) { vm.wards = l || []; }).catch(function () { vm.wards = []; });
      },

      /* 查询: 重置全部页签加载标记, 仅重载当前页签(其余页签切到时再拉) */
      search: function () {
        this.bedLoaded = false;
        this.feeLoaded = false;
        this.bizLoaded = false;
        this.dailyLoaded = false;
        this.drgLoaded = false;
        this.loadActive();
      },

      onTabChange: function (name) {
        if (name) { this.activeTab = name; }
        this.loadActive();
      },

      loadActive: function () {
        var loaders = { bed: 'loadBed', fee: 'loadFee', biz: 'loadBiz', daily: 'loadDaily', drg: 'loadDrg' };
        var flags = { bed: 'bedLoaded', fee: 'feeLoaded', biz: 'bizLoaded', daily: 'dailyLoaded', drg: 'drgLoaded' };
        var tab = this.activeTab;
        if (loaders[tab] && !this[flags[tab]]) { this[loaders[tab]](); }
      },

      /* ==================== Tab1 床位统计 ==================== */

      loadBed: function () {
        var vm = this;
        vm.bedLoading = true;
        HIS.get('/api/his/inp/report/bed-stats?' + vm.baseParams().toString()).then(function (d) {
          vm.bed = {
            summary: (d && d.summary) || null,
            byDept: (d && d.byDept) || [],
            byWard: (d && d.byWard) || []
          };
          vm.bedPage = 1;
          vm.bedLoaded = true;
          vm.$nextTick(function () { vm.renderBedChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.bedLoading = false; });
      },

      renderBedChart: function () {
        var vm = this;
        var rows = vm.bedAll.slice().sort(function (a, b) { return b.rate - a.rate; });
        if (!rows.length) { return; }
        var names = rows.map(function (r) { return r.deptName; });
        var danger = T.danger || '#c74f4f';
        var warning = T.warning || '#a26b1b';
        var link = T.link || '#2c78c7';
        vm.mountChart('bedChart', 'bedChartEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            grid: { left: 8, right: 18, top: 30, bottom: 4, containLabel: true },
            xAxis: T.catAxis
              ? T.catAxis({ type: 'category', data: names, axisLabel: { color: T.ink3, fontSize: 11, interval: 0, rotate: names.length > 8 ? 30 : 0 } })
              : { type: 'category', data: names },
            yAxis: T.valAxis
              ? T.valAxis({ type: 'value', max: 100, axisLabel: { color: T.ink3, fontSize: 11, formatter: '{value}%' } })
              : { type: 'value', max: 100 },
            series: [{
              name: '使用率',
              type: 'bar',
              barMaxWidth: 34,
              data: rows.map(function (r) {
                var v = pct100(r.rate);
                return { value: v, itemStyle: { color: v >= 90 ? danger : v >= 75 ? warning : link, borderRadius: [3, 3, 0, 0] } };
              }),
              label: { show: true, position: 'top', fontSize: 10, color: T.ink3 },
              markLine: {
                silent: true,
                symbol: 'none',
                lineStyle: { color: danger, type: 'dashed' },
                label: { formatter: '警戒 85%', color: danger, fontSize: 10, position: 'insideEndTop' },
                data: [{ yAxis: 85 }]
              }
            }]
          };
        });
      },

      /* ==================== Tab2 费用分析 ==================== */

      loadFee: function () {
        var vm = this;
        vm.feeLoading = true;
        var qs = vm.baseParams().toString();
        var empty = { startDate: '', endDate: '', totalAmount: 0, patientCount: 0, avgCostPerPatient: 0, byType: [], dailyTrend: [], topDepts: [] };
        /* 并行取科室经营(仅用于合并 TOP10 的药占比, 失败不阻断费用分析) */
        Promise.all([
          HIS.get('/api/his/inp/report/fee-analysis?' + qs),
          HIS.get('/api/his/inp/report/dept-business?' + qs).catch(function () { return { items: [] }; })
        ]).then(function (rs) {
          vm.fee = Object.assign(empty, rs[0] || {});
          var map = {};
          ((rs[1] && rs[1].items) || []).forEach(function (it) { map[it.deptId] = it; });
          vm.feeBizMap = map;
          vm.feeLoaded = true;
          vm.$nextTick(function () { vm.renderFeeCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.feeLoading = false; });
      },

      renderFeeCharts: function () {
        var vm = this;
        var link = T.link || '#2c78c7';
        /* 饼图: 费用构成 */
        var byType = (vm.fee.byType || []).filter(function (t2) { return toNum(t2.totalAmount) > 0; });
        var pieData = byType.map(function (t2) {
          return { name: t2.feeTypeName, value: toNum(t2.totalAmount) };
        });
        if (pieData.length) {
          vm.mountChart('feePieChart', 'feePieEl', function () {
            return {
              tooltip: T.tooltip
                ? T.tooltip({ trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' })
                : { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
              legend: T.legend ? T.legend({ bottom: 0, type: 'scroll' }) : { bottom: 0 },
              series: [{
                name: '费用构成',
                type: 'pie',
                radius: ['40%', '66%'],
                center: ['50%', '44%'],
                itemStyle: { borderColor: '#fff', borderWidth: 2 },
                label: { formatter: '{b}\n{d}%', fontSize: 11, color: T.ink2 || '#3d4a5c' },
                labelLine: { length: 8, length2: 8 },
                data: pieData
              }]
            };
          });
        }
        /* 折线图: 每日费用趋势 */
        var trend = vm.fee.dailyTrend || [];
        if (trend.length) {
          vm.mountChart('feeLineChart', 'feeLineEl', function () {
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
              xAxis: T.catAxis
                ? T.catAxis({ type: 'category', boundaryGap: false, data: trend.map(function (r) { return mmdd(r.date); }) })
                : { type: 'category', boundaryGap: false, data: trend.map(function (r) { return mmdd(r.date); }) },
              yAxis: T.valAxis
                ? T.valAxis({ type: 'value' })
                : { type: 'value' },
              series: [{
                name: '每日费用(元)',
                type: 'line',
                smooth: true,
                symbol: 'none',
                data: trend.map(function (r) { return toNum(r.amount); }),
                lineStyle: { color: link, width: 2 },
                itemStyle: { color: link },
                areaStyle: T.grad && T.grad.blue ? T.grad.blue : { color: 'rgba(44,120,199,.12)' }
              }]
            };
          });
        }
      },

      /* ==================== Tab3 科室经营 ==================== */

      loadBiz: function () {
        var vm = this;
        vm.bizLoading = true;
        HIS.get('/api/his/inp/report/dept-business?' + vm.baseParams().toString()).then(function (d) {
          vm.biz = { startDate: (d && d.startDate) || '', endDate: (d && d.endDate) || '', items: (d && d.items) || [] };
          vm.bizPage = 1;
          vm.bizLoaded = true;
        }).catch(HIS.notifyError).finally(function () { vm.bizLoading = false; });
      },

      /* ==================== Tab4 住院日报 ==================== */

      loadDaily: function () {
        var vm = this;
        vm.dailyLoading = true;
        var day = (vm.dateRange && vm.dateRange[1]) || fmtDate(new Date());
        var p = new URLSearchParams();
        p.append('date', day);
        if (vm.deptId !== '' && vm.deptId != null) { p.append('deptId', vm.deptId); }
        HIS.get('/api/his/inp/report/daily-summary?' + p.toString()).then(function (d) {
          vm.daily = {
            date: (d && d.date) || day,
            today: (d && d.today) || null,
            trend: (d && d.trend) || []
          };
          vm.dailyLoaded = true;
          vm.$nextTick(function () { vm.renderDailyChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.dailyLoading = false; });
      },

      renderDailyChart: function () {
        var vm = this;
        var trend = vm.daily.trend || [];
        if (!trend.length) { return; }
        var link = T.link || '#2c78c7';
        var success = T.success || '#3c862d';
        var brand = T.brand || '#1a5c9e';
        vm.mountChart('dailyChart', 'dailyChartEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            legend: T.legend ? T.legend({ top: 0, right: 6 }) : { top: 0, right: 6 },
            grid: { left: 8, right: 18, top: 34, bottom: 4, containLabel: true },
            xAxis: T.catAxis
              ? T.catAxis({ type: 'category', boundaryGap: false, data: trend.map(function (r) { return mmdd(r.date); }) })
              : { type: 'category', boundaryGap: false, data: trend.map(function (r) { return mmdd(r.date); }) },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
            series: [
              { name: '新入院', type: 'line', smooth: true, symbol: 'none', data: trend.map(function (r) { return toNum(r.newAdmit); }), lineStyle: { color: link, width: 2 }, itemStyle: { color: link } },
              { name: '出院', type: 'line', smooth: true, symbol: 'none', data: trend.map(function (r) { return toNum(r.discharge); }), lineStyle: { color: success, width: 2 }, itemStyle: { color: success } },
              { name: '在院', type: 'line', smooth: true, symbol: 'none', data: trend.map(function (r) { return toNum(r.inHospital); }), lineStyle: { color: brand, width: 2 }, itemStyle: { color: brand } }
            ]
          };
        });
      },

      /* ==================== Tab5 DRG/DIP ==================== */

      loadDrg: function () {
        var vm = this;
        vm.drgLoading = true;
        var p = new URLSearchParams();
        if (vm.dateRange && vm.dateRange[0]) { p.append('startDate', vm.dateRange[0]); }
        if (vm.dateRange && vm.dateRange[1]) { p.append('endDate', vm.dateRange[1]); }
        HIS.get('/api/his/inp/report/drg-analysis?' + p.toString()).then(function (d) {
          vm.drg = {
            groupDistribution: (d && d.groupDistribution) || [],
            dipDistribution: (d && d.dipDistribution) || [],
            groupSettleCount: (d && d.groupSettleCount) || 0,
            message: (d && d.message) || null
          };
          /* 默认展示有数据的一侧 */
          if (!vm.drg.groupDistribution.length && vm.drg.dipDistribution.length) { vm.drgMode = 'dip'; }
          vm.drgLoaded = true;
          vm.$nextTick(function () { vm.renderDrgChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.drgLoading = false; });
      },

      renderDrgChart: function () {
        var vm = this;
        var rows = (vm.drgRows || []).slice().sort(function (a, b) { return toNum(b.count) - toNum(a.count); }).slice(0, 12);
        if (!rows.length) { return; }
        var codes = rows.map(function (r) { return r.groupCode || '-'; });
        var counts = rows.map(function (r) { return toNum(r.count); });
        var link = T.link || '#2c78c7';
        vm.mountChart('drgChart', 'drgChartEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
            xAxis: T.catAxis
              ? T.catAxis({ type: 'category', data: codes, axisLabel: { color: T.ink3, fontSize: 11, interval: 0, rotate: codes.length > 6 ? 30 : 0 } })
              : { type: 'category', data: codes },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
            series: [{
              name: '例数',
              type: 'bar',
              barMaxWidth: 34,
              data: counts,
              itemStyle: { color: link, borderRadius: [3, 3, 0, 0] },
              label: { show: true, position: 'top', fontSize: 10, color: T.ink3 }
            }]
          };
        });
      },

      /* ==================== 导出 Excel(CSV) ==================== */

      exportCsv: function () {
        var headers = [];
        var rows = [];
        var tab = this.activeTab;
        if (tab === 'bed') {
          headers = ['科室', '总床数', '占用', '空闲', '使用率(%)'];
          rows = this.bedAll.map(function (r) { return [r.deptName, r.total, r.occupied, r.free, pct100(r.rate)]; });
        } else if (tab === 'fee') {
          headers = ['排名', '科室', '总费用(元)', '人均(元)', '药占比(%)'];
          rows = this.feeTopRows.map(function (r) {
            return [r.rank, r.deptName, fmtMoney(r.totalAmount), r.perCap == null ? '-' : fmtMoney(r.perCap), r.drugRatio == null ? '-' : pct100(r.drugRatio)];
          });
        } else if (tab === 'biz') {
          headers = ['科室', '总收入(元)', '入院数', '出院数', '手术量', '平均住院日(天)', '药占比(%)', '耗材比(%)'];
          rows = (this.biz.items || []).map(function (r) {
            return [r.deptName, fmtMoney(r.totalIncome), toNum(r.admitCount), toNum(r.dischargeCount),
              toNum(r.surgeryCount), fmtNum(r.avgLos, 2), pct100(r.drugRatio), pct100(r.materialRatio)];
          });
        } else if (tab === 'daily') {
          headers = ['日期', '新入院', '出院', '在院'];
          rows = (this.daily.trend || []).map(function (r) {
            return [r.date, toNum(r.newAdmit), toNum(r.discharge), toNum(r.inHospital)];
          });
        } else if (tab === 'drg') {
          headers = [this.drgMode === 'dip' ? 'DIP病组编码' : 'DRG病组编码', '例数', '平均费用(元)'];
          rows = (this.drgRows || []).map(function (r) {
            return [r.groupCode, toNum(r.count), fmtMoney(r.avgCost)];
          });
        }
        if (!rows.length) {
          if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.warning('当前页签暂无数据可导出'); }
          return;
        }
        var r0 = this.dateRange || [];
        var name = '住院报表-' + this.currentTabLabel + '-' + (r0[0] || '') + '_' + (r0[1] || '') + '.csv';
        csvDownload(name, headers, rows);
        HIS.notifySuccess('已导出 ' + name);
      },

      /* ==================== 导出 Excel(xlsx) ==================== */

      /* 后端 EasyExcel 多Sheet导出(认证走 Authorization 头, 用带令牌下载) */
      exportExcel: function () {
        var vm = this;
        var eps = { bed: 'bed-stats', fee: 'fee-analysis', biz: 'dept-business', daily: 'daily-summary', drg: 'drg-analysis' };
        var ep = eps[this.activeTab];
        if (!ep) { return; }
        var r0 = this.dateRange || [];
        vm.excelLoading = true;
        HIS.download('/api/his/inp/report/' + ep + '/export?' + this.baseParams().toString(),
          '住院报表-' + this.currentTabLabel + '-' + (r0[0] || '') + '_' + (r0[1] || '') + '.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError).finally(function () { vm.excelLoading = false; });
      },

      /* ==================== 报表钻取(数字列下钻) ==================== */

      /* 表格单元格点击钻取: bed 表床位数字列→在院患者; fee/biz 表金额列→费用明细 */
      onCellClick: function (row, column) {
        if (!row || !column) { return; }
        var label = column.label || '';
        if (this.activeTab === 'bed' && (label === '总床数' || label === '占用')) {
          this.openDrill('bed', row.deptId, '在院患者 · ' + (row.deptName || ''));
        } else if (this.activeTab === 'fee' && label === '总费用(元)') {
          this.openDrill('fee', row.deptId, '费用明细 · ' + (row.deptName || ''));
        } else if (this.activeTab === 'biz' && label === '总收入(元)') {
          this.openDrill('dept', row.deptId, '科室费用明细 · ' + (row.deptName || ''));
        }
      },

      /* 打开钻取弹窗: fee/dept=期内费用明细(按科室), bed=当前在院患者(快照, 忽略日期) */
      openDrill: function (type, deptId, title) {
        var r0 = this.dateRange || [];
        this.drill = {
          visible: true,
          type: type,
          deptId: deptId != null ? deptId : null,
          title: title || '钻取明细',
          dateFrom: type === 'bed' ? '' : (r0[0] || ''),
          dateTo: type === 'bed' ? '' : (r0[1] || ''),
          page: 1,
          size: 20,
          total: 0,
          records: [],
          loading: false
        };
        this.fetchDrill();
      },

      /* 钻取分页取数 */
      fetchDrill: function () {
        var vm = this;
        var d = vm.drill;
        var p = new URLSearchParams();
        p.append('type', d.type);
        if (d.deptId != null && d.deptId !== '') { p.append('deptId', d.deptId); }
        if (d.dateFrom) { p.append('dateFrom', d.dateFrom); }
        if (d.dateTo) { p.append('dateTo', d.dateTo); }
        p.append('page', d.page);
        p.append('size', d.size);
        d.loading = true;
        HIS.get('/api/his/inp/report/drill-down?' + p.toString()).then(function (res) {
          d.total = toNum(res && res.total);
          d.records = (res && res.records) || [];
        }).catch(HIS.notifyError).finally(function () { d.loading = false; });
      },

      onDrillPage: function (p) { this.drill.page = p; this.fetchDrill(); },
      onDrillSize: function (s) { this.drill.size = s; this.drill.page = 1; this.fetchDrill(); },

      /* ==================== 图表挂载 / 尺寸 ==================== */

      /* 通用挂载: 容器隐藏(切页签瞬间)宽度为 0 时带重试, 就绪后 init 并 setOption */
      mountChart: function (prop, refName, buildOption) {
        var vm = this;
        var tries = 0;
        function tryInit() {
          var dom = vm.$refs[refName];
          if (!dom || !window.echarts) { return; }
          if (!dom.offsetWidth) {
            if (tries++ < 8) { setTimeout(tryInit, 150); }
            return;
          }
          if (vm[prop]) { vm[prop].dispose(); vm[prop] = null; }
          var chart = window.echarts.init(dom, 'yb');
          vm[prop] = chart;
          chart.setOption(buildOption());
        }
        tryInit();
      },

      onResize: function () {
        var vm = this;
        ['bedChart', 'feePieChart', 'feeLineChart', 'dailyChart', 'drgChart'].forEach(function (k) {
          if (vm[k] && vm[k].resize) { vm[k].resize(); }
        });
      },

      /* ==================== 分页 ==================== */

      onBedPage: function (p) { this.bedPage = p; },
      onBedSize: function (s) { this.bedSize = s; this.bedPage = 1; },
      onBizPage: function (p) { this.bizPage = p; },
      onBizSize: function (s) { this.bizSize = s; this.bizPage = 1; },
      bedIdx: function (i) { return (this.bedPage - 1) * this.bedSize + i + 1; },
      bizIdx: function (i) { return (this.bizPage - 1) * this.bizSize + i + 1; }
    },

    mounted: function () {
      window.addEventListener('resize', this.onResize);
      this.loadRefs();
      this.loadActive();
    },

    unmounted: function () {
      window.removeEventListener('resize', this.onResize);
      var vm = this;
      ['bedChart', 'feePieChart', 'feeLineChart', 'dailyChart', 'drgChart'].forEach(function (k) {
        if (vm[k]) { vm[k].dispose(); vm[k] = null; }
      });
    }
    ,

    /* ==================== 模板 ==================== */
    template: [
      '<div class="ir-page">',
      '  <div class="page-title">住院报表中心 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(床位 / 费用 / 科室经营 / 住院日报 / DRG-DIP 五类统计 · 比率均按百分比口径)</span></div>',

      /* ---- 通用筛选栏 ---- */
      '  <div class="ir-filter">',
      '    <span class="ir-note" style="white-space:nowrap;">统计区间</span>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:252px" @change="search"></el-date-picker>',
      '    <el-select v-model="deptId" filterable placeholder="全院" style="width:170px" @change="search">',
      '      <el-option label="全院" value=""></el-option>',
      '      <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="wardId" filterable placeholder="全部病区" style="width:180px" @change="search">',
      '      <el-option label="全部病区" value=""></el-option>',
      '      <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <div class="spacer"></div>',
      '    <el-button type="primary" plain :loading="excelLoading" @click="exportExcel">导出Excel</el-button>',
      '    <el-button @click="exportCsv">导出CSV</el-button>',
      '    <span class="ir-note">病区筛选仅作用于「床位统计」</span>',
      '  </div>',

      '  <el-tabs v-model="activeTab" style="margin-top:14px;" @tab-change="onTabChange">',

      /* ================= Tab1 床位统计 ================= */
      '    <el-tab-pane label="床位统计" name="bed">',
      '      <div v-loading="bedLoading">',
      '        <div v-if="!bedKpis.length" class="ir-kpis">',
      '          <div v-for="i in 4" :key="i" class="ir-kpi is-dim"><div class="num">--</div><div class="lbl">加载中…</div><div class="ir-kpi-foot"></div></div>',
      '        </div>',
      '        <div v-else class="ir-kpis">',
      '          <div v-for="(k, i) in bedKpis" :key="i" class="ir-kpi">',
      '            <div class="num" :style="{ color: k.color }">{{ k.num }}</div>',
      '            <div class="lbl">{{ k.lbl }}</div>',
      '            <div class="ir-kpi-foot" :style="{ background: k.color }"></div>',
      '          </div>',
      '        </div>',
      '        <div class="ir-cols wide-right">',
      '          <div class="ir-card">',
      '            <div class="ir-card-title">各科室床位使用率 <span class="ir-note">(虚线为 85% 警戒线)</span></div>',
      '            <div ref="bedChartEl" class="ir-chart"></div>',
      '          </div>',
      '          <div class="ir-card">',
      '            <div class="ir-card-title">科室床位明细 <span class="ir-note">共 {{ bedTotal }} 个科室</span></div>',
      '            <el-table :data="bedRows" size="small" border style="width:100%;" @cell-click="onCellClick">',
      '              <el-table-column type="index" label="序号" width="56" align="center" :index="bedIdx"></el-table-column>',
      '              <el-table-column prop="deptName" label="科室" min-width="120"></el-table-column>',
      '              <el-table-column prop="total" label="总床数" width="80" align="right"><template #default="s"><span class="ir-drill" title="点击查看在院患者">{{ s.row.total }}</span></template></el-table-column>',
      '              <el-table-column prop="occupied" label="占用" width="80" align="right"><template #default="s"><span class="ir-drill" title="点击查看在院患者">{{ s.row.occupied }}</span></template></el-table-column>',
      '              <el-table-column prop="free" label="空闲" width="80" align="right"></el-table-column>',
      '              <el-table-column label="使用率" width="100" align="right">',
      '                <template #default="s"><span :class="{ \'ir-rate-warn\': pct100(s.row.rate) >= 85 }">{{ pctText(s.row.rate) }}</span></template>',
      '              </el-table-column>',
      '            </el-table>',
      '            <div class="ir-pager">',
      '              <el-pagination background layout="total, sizes, prev, pager, next" :total="bedTotal" :page-size="bedSize" :page-sizes="[10, 20, 50, 100]" :current-page="bedPage" @current-change="onBedPage" @size-change="onBedSize"></el-pagination>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab2 费用分析 ================= */
      '    <el-tab-pane label="费用分析" name="fee">',
      '      <div v-loading="feeLoading">',
      '        <div v-if="!feeKpis.length" class="ir-kpis cols-3">',
      '          <div v-for="i in 3" :key="i" class="ir-kpi is-dim"><div class="num">--</div><div class="lbl">加载中…</div><div class="ir-kpi-foot"></div></div>',
      '        </div>',
      '        <div v-else class="ir-kpis cols-3">',
      '          <div v-for="(k, i) in feeKpis" :key="i" class="ir-kpi">',
      '            <div class="num" :style="{ color: k.color }">{{ k.num }}</div>',
      '            <div class="lbl">{{ k.lbl }}</div>',
      '            <div class="ir-kpi-foot" :style="{ background: k.color }"></div>',
      '          </div>',
      '        </div>',
      '        <div class="ir-cols">',
      '          <div class="ir-card">',
      '            <div class="ir-card-title">费用构成 <span class="ir-note">(按费用类别)</span></div>',
      '            <div ref="feePieEl" class="ir-chart"></div>',
      '          </div>',
      '          <div class="ir-card">',
      '            <div class="ir-card-title">每日费用趋势</div>',
      '            <div ref="feeLineEl" class="ir-chart"></div>',
      '          </div>',
      '        </div>',
      '        <div class="ir-card ir-table-card">',
      '          <div class="ir-card-title">科室费用 TOP10 <span class="ir-note">(人均按入院数换算; 药占比取自科室经营)</span></div>',
      '          <el-table :data="feeTopRows" size="small" border style="width:100%;" @cell-click="onCellClick">',
      '            <el-table-column prop="rank" label="排名" width="64" align="center"></el-table-column>',
      '            <el-table-column prop="deptName" label="科室" min-width="140"></el-table-column>',
      '            <el-table-column label="总费用(元)" width="150" align="right"><template #default="s"><span class="ir-money ir-drill" title="点击钻取费用明细">{{ fmtMoney(s.row.totalAmount) }}</span></template></el-table-column>',
      '            <el-table-column label="人均(元)" width="150" align="right"><template #default="s"><span class="ir-money">{{ s.row.perCap == null ? \'-\' : fmtMoney(s.row.perCap) }}</span></template></el-table-column>',
      '            <el-table-column label="药占比" width="110" align="right">',
      '              <template #default="s"><span :class="{ \'ir-rate-warn\': s.row.drugRatio != null && s.row.drugRatio > 0.3 }">{{ s.row.drugRatio == null ? \'-\' : pctText(s.row.drugRatio) }}</span></template>',
      '            </el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab3 科室经营 ================= */
      '    <el-tab-pane label="科室经营" name="biz">',
      '      <div v-loading="bizLoading">',
      '        <div class="ir-card ir-table-card">',
      '          <div class="ir-card-title">科室经营一览 <span class="ir-note">(药占比 &gt; 30% 红色预警)</span></div>',
      '          <el-table :data="bizRows" size="small" border style="width:100%;" @cell-click="onCellClick">',
      '            <el-table-column type="index" label="序号" width="56" align="center" :index="bizIdx"></el-table-column>',
      '            <el-table-column prop="deptName" label="科室" min-width="120"></el-table-column>',
      '            <el-table-column label="总收入(元)" width="140" align="right"><template #default="s"><span class="ir-money ir-drill" title="点击钻取科室费用明细">{{ fmtMoney(s.row.totalIncome) }}</span></template></el-table-column>',
      '            <el-table-column prop="admitCount" label="入院数" width="80" align="right"></el-table-column>',
      '            <el-table-column prop="dischargeCount" label="出院数" width="80" align="right"></el-table-column>',
      '            <el-table-column prop="surgeryCount" label="手术量" width="80" align="right"></el-table-column>',
      '            <el-table-column label="平均住院日" width="100" align="right"><template #default="s">{{ fmtNum(s.row.avgLos, 2) }}</template></el-table-column>',
      '            <el-table-column label="药占比" min-width="170">',
      '              <template #default="s">',
      '                <el-progress :percentage="progPct(s.row.drugRatio)" :stroke-width="10" :show-text="false" :color="drugColor(s.row.drugRatio)" style="width:92px;display:inline-block;vertical-align:middle;"></el-progress>',
      '                <span style="margin-left:6px;" :class="{ \'ir-rate-warn\': toNum(s.row.drugRatio) > 0.3 }">{{ pctText(s.row.drugRatio) }}</span>',
      '              </template>',
      '            </el-table-column>',
      '            <el-table-column label="耗材比" min-width="170">',
      '              <template #default="s">',
      '                <el-progress :percentage="progPct(s.row.materialRatio)" :stroke-width="10" :show-text="false" :color="matColor()" style="width:92px;display:inline-block;vertical-align:middle;"></el-progress>',
      '                <span style="margin-left:6px;">{{ pctText(s.row.materialRatio) }}</span>',
      '              </template>',
      '            </el-table-column>',
      '          </el-table>',
      '          <div class="ir-pager">',
      '            <el-pagination background layout="total, sizes, prev, pager, next" :total="bizTotal" :page-size="bizSize" :page-sizes="[10, 20, 50, 100]" :current-page="bizPage" @current-change="onBizPage" @size-change="onBizSize"></el-pagination>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab4 住院日报 ================= */
      '    <el-tab-pane label="住院日报" name="daily">',
      '      <div v-loading="dailyLoading">',
      '        <div class="ir-note" style="margin-bottom:8px;">统计日: <b>{{ daily.date || (dateRange && dateRange[1]) || \'-\' }}</b>(取筛选区间结束日), 趋势为截至统计日的近 30 天</div>',
      '        <div v-if="!dailyKpis.length" class="ir-kpis cols-6">',
      '          <div v-for="i in 6" :key="i" class="ir-kpi is-dim"><div class="num">--</div><div class="lbl">加载中…</div><div class="ir-kpi-foot"></div></div>',
      '        </div>',
      '        <div v-else class="ir-kpis cols-6">',
      '          <div v-for="(k, i) in dailyKpis" :key="i" class="ir-kpi">',
      '            <div class="num" :style="{ color: k.color }">{{ k.num }}</div>',
      '            <div class="lbl">{{ k.lbl }}</div>',
      '            <div class="ir-kpi-foot" :style="{ background: k.color }"></div>',
      '          </div>',
      '        </div>',
      '        <div class="ir-card ir-table-card">',
      '          <div class="ir-card-title">近 30 天出入院趋势</div>',
      '          <div ref="dailyChartEl" class="ir-chart"></div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab5 DRG/DIP 分析 ================= */
      '    <el-tab-pane label="DRG/DIP分析" name="drg">',
      '      <div v-loading="drgLoading">',
      '        <div v-if="drgEmpty" class="ir-card" style="text-align:center;padding:36px 20px;">',
      '          <el-empty :description="drg.message || \'暂无DRG/DIP结算数据\'"></el-empty>',
      '          <div class="ir-note">DRG/DIP 分组数据来自住院结算单回写的 drg_group_code / dip_code 字段, 完成分组结算后再来查看</div>',
      '        </div>',
      '        <template v-else>',
      '          <div class="ir-card ir-table-card">',
      '            <div class="ir-card-title">病组分布 <span class="ir-note">共 {{ drg.groupSettleCount }} 例已分组结算</span>',
      '              <el-radio-group v-if="drgBoth" v-model="drgMode" size="small" style="float:right;margin-top:-3px;" @change="renderDrgChart">',
      '                <el-radio-button label="drg">DRG</el-radio-button>',
      '                <el-radio-button label="dip">DIP</el-radio-button>',
      '              </el-radio-group>',
      '            </div>',
      '            <div ref="drgChartEl" class="ir-chart sm"></div>',
      '          </div>',
      '          <div class="ir-card ir-table-card">',
      '            <div class="ir-card-title">{{ drgMode === \'dip\' ? \'DIP 病组分布\' : \'DRG 病组分布\' }}</div>',
      '            <el-table :data="drgRows" size="small" border max-height="420" style="width:100%;">',
      '              <el-table-column label="病组编码" min-width="160"><template #default="s">{{ s.row.groupCode || \'-\' }}</template></el-table-column>',
      '              <el-table-column prop="count" label="例数" width="100" align="right"></el-table-column>',
      '              <el-table-column label="平均费用(元)" width="160" align="right"><template #default="s"><span class="ir-money">{{ fmtMoney(s.row.avgCost) }}</span></template></el-table-column>',
      '            </el-table>',
      '          </div>',
      '        </template>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',

      /* ---- 报表钻取明细弹窗 ---- */
      '  <el-dialog v-model="drill.visible" :title="drill.title" width="72%" top="6vh" append-to-body>',
      '    <div class="ir-note" style="margin-bottom:8px;">{{ drillSubtitle }}</div>',
      '    <el-table :data="drill.records" size="small" border v-loading="drill.loading" max-height="440" style="width:100%;">',
      '      <el-table-column v-for="c in drillColumns" :key="c.prop" :prop="c.prop" :label="c.label" :min-width="c.minWidth" :width="c.width" :align="c.align || \'left\'">',
      '        <template #default="s"><span :class="{ \'ir-money\': c.align === \'right\' }">{{ s.row[c.prop] }}</span></template>',
      '      </el-table-column>',
      '    </el-table>',
      '    <div class="ir-pager">',
      '      <el-pagination background layout="total, sizes, prev, pager, next" :total="drill.total" :page-size="drill.size" :page-sizes="[10, 20, 50, 100]" :current-page="drill.page" @current-change="onDrillPage" @size-change="onDrillSize"></el-pagination>',
      '    </div>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
