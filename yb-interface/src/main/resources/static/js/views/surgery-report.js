/* ============================================================================
 * 手麻P2: 手术统计报表中心(SurgeryReport) — 规范 2.2.2.3.7 手术麻醉统计
 * 后端契约(/api/his/surgery-report, 机构隔离 scopeOrgId 牵头可跨机构汇总):
 *   GET  /volume      手术量: total/byLevel/byDept/bySurgeon/byDate/applyFunnel
 *   GET  /anesthesia  麻醉:   byType/byAsa/avgAnesthesiaMin
 *   GET  /duration    时长:   avgOperateMin/avgIncisionMin/timedCount/byLevel
 *   GET  /fee         费用:   byCategory/totalAmount/avgPerSurgery/topByName
 *   GET  /quality     质量:   byIncision/total/cancelled/cancelRate/reopList
 *   GET  /dashboard   KPI:    todayCount/inOperation/pendingSchedule/completeRate/cancelRate/level34Ratio/range30Count
 *   POST /{type}/snapshot     按类型重算后落 his_inp_report_snapshot(report_type 6-11)
 * 查询(SurgeryReportQueryDTO): startDate/endDate/deptId/surgeonId/moduleType(1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗)
 * 口径: 比率后端 0-1 小数(前端 ×100); 分组行 {grp,cnt,name}; 费用 {grp,amt,cnt,name}; 时长 {grp,avg_min,cnt,name}。
 * 注册: HIS.views.SurgeryReport(菜单 surgery-report, comp 同名)
 * 图表: echarts.init(el,'yb') + 隐藏页签宽度 0 故 $nextTick 后带重试初始化; resize 统一; 卸载 dispose。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var T = HIS.theme || {};

  /* ===== 常量映射 ===== */
  var MODULE = { 1: '手术室', 2: 'DSA', 3: '产科分娩', 4: '内镜', 5: '麻醉治疗' };
  var APPLY_STATUS = { 1: '待复核', 2: '待安排', 3: '已退回', 4: '已安排', 5: '已完成', 6: '已作废' };

  function optsOf(map) {
    return Object.keys(map).map(function (k) { return { value: Number(k), label: map[k] }; });
  }
  var MODULE_OPTS = optsOf(MODULE);
  /* 手麻P4b: 手术间资源类型(利用率报表) */
  var DEVICE_ROOM_TYPE = { 0: '未登记', 1: '手术间', 2: 'DSA机房', 3: '内镜室', 4: '产房' };

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
  function mmdd(d) { return String(d == null ? '' : d).slice(5); }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }

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
    a.href = u; a.download = filename;
    document.body.appendChild(a); a.click();
    setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 1000);
  }

  /* ---- 私有样式一次性注入(类前缀 sr-*, 复用 ir-* 视觉基因但独立) ---- */
  (function ensureStyles() {
    if (document.getElementById('surgery-report-style')) { return; }
    var st = document.createElement('style');
    st.id = 'surgery-report-style';
    st.textContent = [
      '.sr-filter { display:flex; gap:10px; align-items:center; flex-wrap:wrap; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:12px 16px; box-shadow:var(--yb-sh-1); }',
      '.sr-filter .spacer { flex:1; }',
      '.sr-kpis { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; margin:16px 0; }',
      '.sr-kpis.cols-3 { grid-template-columns:repeat(3,1fr); }',
      '.sr-kpis.cols-6 { grid-template-columns:repeat(6,1fr); }',
      '.sr-kpi { position:relative; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:16px 18px 22px; box-shadow:var(--yb-sh-1); overflow:hidden; }',
      '.sr-kpi .num { font-size:30px; font-weight:700; line-height:1.12; color:var(--yb-brand); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); letter-spacing:-.02em; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.sr-kpis.cols-6 .num { font-size:26px; }',
      '.sr-kpi .lbl { color:var(--yb-ink-3); font-size:13px; margin-top:6px; }',
      '.sr-kpi .sr-kpi-foot { position:absolute; left:0; right:0; bottom:0; height:4px; background:var(--yb-brand); opacity:.92; }',
      '.sr-kpi.is-dim .num { color:var(--yb-ink-4); }',
      '.sr-kpi.is-dim .sr-kpi-foot { background:var(--yb-border-strong); opacity:.7; }',
      '.sr-card { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px; box-shadow:var(--yb-sh-1); }',
      '.sr-card-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); }',
      '.sr-card-title .sr-note { font-weight:400; }',
      '.sr-chart { width:100%; height:320px; }',
      '.sr-chart.sm { height:280px; }',
      '.sr-cols { display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-bottom:16px; }',
      '.sr-table-card { margin-bottom:16px; }',
      '.sr-note { font-size:12px; color:var(--yb-ink-4); }',
      '.sr-money { font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); }',
      '.sr-rate-warn { color:var(--yb-danger); font-weight:600; }',
      /* 手麻UI轻触统一: KPI 卡片品牌脊 + 级联入场(与手术监护中控台同源) */
      '.sr-kpi::before { content:""; position:absolute; left:0; top:0; bottom:0; width:3px; background:var(--yb-brand); opacity:.9; }',
      '.sr-kpi.is-dim::before { background:var(--yb-border-strong); opacity:.6; }',
      '.sr-kpi { animation:sr-in .3s var(--yb-ease) both; }',
      '@keyframes sr-in { from { opacity:0; transform:translateY(6px); } to { opacity:1; transform:none; } }',
      '@media (prefers-reduced-motion: reduce) { .sr-kpi { animation:none; } }',
      '@media (max-width:1280px) { .sr-kpis.cols-6 { grid-template-columns:repeat(3,1fr); } .sr-cols { grid-template-columns:1fr; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ========================================================================
   * SurgeryReport 手术统计报表中心
   * ====================================================================== */
  HIS.views.SurgeryReport = {
    data: function () {
      return {
        dateRange: lastNDays(30),
        deptId: '',
        surgeonId: '',
        moduleType: '',
        depts: [],
        doctors: [],
        moduleOpts: MODULE_OPTS,
        activeTab: 'volume',
        snapLoading: false,

        volume: { total: 0, byLevel: [], byDept: [], bySurgeon: [], byDate: [], applyFunnel: [] },
        volumeLoaded: false, volumeLoading: false,

        anesthesia: { byType: [], byAsa: [], avgAnesthesiaMin: 0 },
        anesthesiaLoaded: false, anesthesiaLoading: false,

        duration: { avgOperateMin: 0, avgIncisionMin: 0, timedCount: 0, byLevel: [] },
        durationLoaded: false, durationLoading: false,

        fee: { byCategory: [], totalAmount: 0, avgPerSurgery: 0, topByName: [] },
        feeLoaded: false, feeLoading: false,

        quality: { byIncision: [], total: 0, cancelled: 0, cancelRate: 0, reopList: [] },
        qualityLoaded: false, qualityLoading: false,

        dashboard: { today: '', todayCount: 0, inOperation: 0, pendingSchedule: 0, completeRate: 0, cancelRate: 0, level34Ratio: 0, range30Count: 0 },
        dashboardLoaded: false, dashboardLoading: false,

        device: { byRoom: [], byRoomType: [], dayCount: 0, availMinPerDay: 0, startDate: '', endDate: '' },
        deviceLoaded: false, deviceLoading: false
      };
    },

    computed: {
      volKpis: function () {
        if (!this.volumeLoaded) { return []; }
        var v = this.volume;
        return [
          { num: fmtNum(v.total), lbl: '手术总量(区间)', color: T.brand },
          { num: (v.byDept || []).length, lbl: '涉及科室数', color: T.teal },
          { num: (v.bySurgeon || []).length, lbl: '参与术者数', color: T.link },
          { num: this.level34Count, lbl: '三四级手术', color: T.gold }
        ];
      },
      level34Count: function () {
        var c = 0;
        (this.volume.byLevel || []).forEach(function (r) { if (toNum(r.grp) >= 3) { c += toNum(r.cnt); } });
        return c;
      },
      aneKpis: function () {
        if (!this.anesthesiaLoaded) { return []; }
        var a = this.anesthesia;
        return [
          { num: fmtNum(a.avgAnesthesiaMin, 1) + ' 分', lbl: '平均麻醉时长(诱导→拔管)', color: T.brand },
          { num: fmtNum(sumCnt(a.byType)), lbl: '麻醉记录例数', color: T.teal },
          { num: fmtNum(sumCnt(a.byAsa)), lbl: 'ASA 已分级', color: T.link }
        ];
      },
      durKpis: function () {
        if (!this.durationLoaded) { return []; }
        var d = this.duration;
        return [
          { num: fmtNum(d.avgOperateMin, 1) + ' 分', lbl: '平均台内时长', color: T.brand },
          { num: fmtNum(d.avgIncisionMin, 1) + ' 分', lbl: '平均切口时长', color: T.teal },
          { num: fmtNum(d.timedCount), lbl: '计时手术例数', color: T.link }
        ];
      },
      feeKpis: function () {
        if (!this.feeLoaded) { return []; }
        var f = this.fee;
        return [
          { num: '¥' + fmtMoney(f.totalAmount), lbl: '手术总费用(区间)', color: T.brand },
          { num: '¥' + fmtMoney(f.avgPerSurgery), lbl: '例均费用', color: T.gold },
          { num: (f.byCategory || []).length, lbl: '费用类别数', color: T.teal }
        ];
      },
      qualityKpis: function () {
        if (!this.qualityLoaded) { return []; }
        var q = this.quality;
        return [
          { num: fmtNum(q.total), lbl: '手术总量(区间)', color: T.brand },
          { num: fmtNum(q.cancelled), lbl: '取消台次', color: T.warning },
          { num: pctText(q.cancelRate), lbl: '取消率', color: pct100(q.cancelRate) > 5 ? T.danger : T.success },
          { num: (q.reopList || []).length, lbl: '非计划二次手术', color: T.danger }
        ];
      },
      dashKpis: function () {
        if (!this.dashboardLoaded) { return []; }
        var d = this.dashboard;
        return [
          { num: fmtNum(d.todayCount), lbl: '今日台次', color: T.brand },
          { num: fmtNum(d.inOperation), lbl: '在术', color: T.danger },
          { num: fmtNum(d.pendingSchedule), lbl: '待安排', color: T.warning },
          { num: pctText(d.completeRate), lbl: '完成率(近30天)', color: T.success },
          { num: pctText(d.cancelRate), lbl: '取消率(近30天)', color: pct100(d.cancelRate) > 5 ? T.danger : T.link },
          { num: pctText(d.level34Ratio), lbl: '三四级占比(近30天)', color: T.gold }
        ];
      },
      deviceKpis: function () {
        if (!this.deviceLoaded) { return []; }
        var dv = this.device;
        var cases = 0, occ = 0, rooms = 0;
        (dv.byRoom || []).forEach(function (r) { cases += toNum(r.cases); occ += toNum(r.occupiedMin); });
        (dv.byRoomType || []).forEach(function (r) { rooms += toNum(r.roomCount); });
        var avail = toNum(dv.availMinPerDay) * toNum(dv.dayCount) * (rooms > 0 ? rooms : 1);
        return [
          { num: fmtNum(cases), lbl: '开机台次(区间内有起止)', color: T.brand },
          { num: fmtNum(occ) + ' 分', lbl: '占用时长合计', color: T.teal },
          { num: fmtNum((dv.byRoom || []).length), lbl: '在用手术间/机房', color: T.link },
          { num: avail > 0 ? pctText(occ / avail) : '—', lbl: '整体利用率', color: T.gold }
        ];
      },
      currentTabLabel: function () {
        var m = { volume: '手术量', anesthesia: '麻醉分布', duration: '手术时长', fee: '费用统计', quality: '质量指标', dashboard: '工作台KPI', device: '设备利用率' };
        return m[this.activeTab] || '报表';
      }
    },

    methods: {
      toNum: toNum, fmtNum: fmtNum, fmtMoney: fmtMoney, pct100: pct100, pctText: pctText,
      mmdd: mmdd, orDash: orDash,
      applyStatusLabel: function (st) { return APPLY_STATUS[toNum(st)] || orDash(st); },

      /* 通用筛选参数(区间/科室/术者/模块; '' 与 null 不传) */
      baseParams: function () {
        var p = new URLSearchParams();
        if (this.dateRange && this.dateRange[0]) { p.append('startDate', this.dateRange[0]); }
        if (this.dateRange && this.dateRange[1]) { p.append('endDate', this.dateRange[1]); }
        if (this.deptId !== '' && this.deptId != null) { p.append('deptId', this.deptId); }
        if (this.surgeonId !== '' && this.surgeonId != null) { p.append('surgeonId', this.surgeonId); }
        if (this.moduleType !== '' && this.moduleType != null) { p.append('moduleType', this.moduleType); }
        return p;
      },

      loadRefs: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.depts = l || []; }).catch(function () { vm.depts = []; });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false')
          .then(function (l) { vm.doctors = l || []; }).catch(function () { vm.doctors = []; });
      },

      search: function () {
        this.volumeLoaded = false; this.anesthesiaLoaded = false; this.durationLoaded = false;
        this.feeLoaded = false; this.qualityLoaded = false; this.dashboardLoaded = false;
        this.deviceLoaded = false;
        this.loadActive();
      },

      onTabChange: function (name) { if (name) { this.activeTab = name; } this.loadActive(); },

      loadActive: function () {
        var loaders = { volume: 'loadVolume', anesthesia: 'loadAnesthesia', duration: 'loadDuration', fee: 'loadFee', quality: 'loadQuality', dashboard: 'loadDashboard', device: 'loadDevice' };
        var flags = { volume: 'volumeLoaded', anesthesia: 'anesthesiaLoaded', duration: 'durationLoaded', fee: 'feeLoaded', quality: 'qualityLoaded', dashboard: 'dashboardLoaded', device: 'deviceLoaded' };
        var tab = this.activeTab;
        if (loaders[tab] && !this[flags[tab]]) { this[loaders[tab]](); }
      },

      /* ==================== Tab1 手术量 ==================== */
      loadVolume: function () {
        var vm = this; vm.volumeLoading = true;
        HIS.get('/api/his/surgery-report/volume?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          vm.volume = {
            total: toNum(d.total), byLevel: d.byLevel || [], byDept: d.byDept || [],
            bySurgeon: d.bySurgeon || [], byDate: d.byDate || [], applyFunnel: d.applyFunnel || []
          };
          vm.volumeLoaded = true;
          vm.$nextTick(function () { vm.renderVolumeCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.volumeLoading = false; });
      },
      renderVolumeCharts: function () {
        var vm = this;
        var lvl = (vm.volume.byLevel || []).slice().sort(function (a, b) { return toNum(a.grp) - toNum(b.grp); });
        if (lvl.length) {
          vm.mountChart('volLevelChart', 'volLevelEl', function () {
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
              xAxis: T.catAxis ? T.catAxis({ type: 'category', data: lvl.map(function (r) { return r.name || ('级别' + r.grp); }) })
                : { type: 'category', data: lvl.map(function (r) { return r.name || ('级别' + r.grp); }) },
              yAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
              series: [{ name: '手术量', type: 'bar', barMaxWidth: 40, itemStyle: { color: T.link || '#2c78c7', borderRadius: [3, 3, 0, 0] },
                label: { show: true, position: 'top', fontSize: 10, color: T.ink3 }, data: lvl.map(function (r) { return toNum(r.cnt); }) }]
            };
          });
        }
        var dt = vm.volume.byDate || [];
        if (dt.length) {
          vm.mountChart('volDateChart', 'volDateEl', function () {
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
              xAxis: T.catAxis ? T.catAxis({ type: 'category', boundaryGap: false, data: dt.map(function (r) { return mmdd(r.d); }) })
                : { type: 'category', boundaryGap: false, data: dt.map(function (r) { return mmdd(r.d); }) },
              yAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
              series: [{ name: '每日台次', type: 'line', smooth: true, symbol: 'none', data: dt.map(function (r) { return toNum(r.cnt); }),
                lineStyle: { color: T.brand || '#1a5c9e', width: 2 }, itemStyle: { color: T.brand || '#1a5c9e' },
                areaStyle: T.grad && T.grad.blue ? T.grad.blue : { color: 'rgba(44,120,199,.12)' } }]
            };
          });
        }
      },

      /* ==================== Tab2 麻醉分布 ==================== */
      loadAnesthesia: function () {
        var vm = this; vm.anesthesiaLoading = true;
        HIS.get('/api/his/surgery-report/anesthesia?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          vm.anesthesia = { byType: d.byType || [], byAsa: d.byAsa || [], avgAnesthesiaMin: toNum(d.avgAnesthesiaMin) };
          vm.anesthesiaLoaded = true;
          vm.$nextTick(function () { vm.renderAneCharts(); });
        }).catch(HIS.notifyError).finally(function () { vm.anesthesiaLoading = false; });
      },
      renderAneCharts: function () {
        var vm = this;
        var bt = (vm.anesthesia.byType || []).filter(function (r) { return toNum(r.cnt) > 0; });
        if (bt.length) {
          vm.mountChart('aneTypeChart', 'aneTypeEl', function () {
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'item', formatter: '{b}: {c} ({d}%)' }) : { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
              legend: T.legend ? T.legend({ bottom: 0, type: 'scroll' }) : { bottom: 0 },
              series: [{ name: '麻醉类型', type: 'pie', radius: ['40%', '66%'], center: ['50%', '44%'],
                itemStyle: { borderColor: '#fff', borderWidth: 2 }, label: { formatter: '{b}\n{d}%', fontSize: 11 },
                data: bt.map(function (r) { return { name: r.name || ('类型' + r.grp), value: toNum(r.cnt) }; }) }]
            };
          });
        }
        var asa = (vm.anesthesia.byAsa || []).slice().sort(function (a, b) { return toNum(a.grp) - toNum(b.grp); });
        if (asa.length) {
          vm.mountChart('aneAsaChart', 'aneAsaEl', function () {
            return {
              tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
              grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
              xAxis: T.catAxis ? T.catAxis({ type: 'category', data: asa.map(function (r) { return 'ASA ' + orDash(r.name); }) })
                : { type: 'category', data: asa.map(function (r) { return 'ASA ' + orDash(r.name); }) },
              yAxis: T.valAxis ? T.valAxis({ type: 'value', minInterval: 1 }) : { type: 'value', minInterval: 1 },
              series: [{ name: '例数', type: 'bar', barMaxWidth: 40, itemStyle: { color: T.teal || '#048671', borderRadius: [3, 3, 0, 0] },
                label: { show: true, position: 'top', fontSize: 10, color: T.ink3 }, data: asa.map(function (r) { return toNum(r.cnt); }) }]
            };
          });
        }
      },

      /* ==================== Tab3 手术时长 ==================== */
      loadDuration: function () {
        var vm = this; vm.durationLoading = true;
        HIS.get('/api/his/surgery-report/duration?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          vm.duration = { avgOperateMin: toNum(d.avgOperateMin), avgIncisionMin: toNum(d.avgIncisionMin), timedCount: toNum(d.timedCount), byLevel: d.byLevel || [] };
          vm.durationLoaded = true;
          vm.$nextTick(function () { vm.renderDurationChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.durationLoading = false; });
      },
      renderDurationChart: function () {
        var vm = this;
        var bl = (vm.duration.byLevel || []).slice().sort(function (a, b) { return toNum(a.grp) - toNum(b.grp); });
        if (!bl.length) { return; }
        vm.mountChart('durLevelChart', 'durLevelEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'axis' }) : { trigger: 'axis' },
            grid: { left: 8, right: 18, top: 26, bottom: 4, containLabel: true },
            xAxis: T.catAxis ? T.catAxis({ type: 'category', data: bl.map(function (r) { return r.name || ('级别' + r.grp); }) })
              : { type: 'category', data: bl.map(function (r) { return r.name || ('级别' + r.grp); }) },
            yAxis: T.valAxis ? T.valAxis({ type: 'value', name: '分钟' }) : { type: 'value' },
            series: [{ name: '平均台内时长', type: 'bar', barMaxWidth: 40, itemStyle: { color: T.gold || '#a26b1b', borderRadius: [3, 3, 0, 0] },
              label: { show: true, position: 'top', fontSize: 10, color: T.ink3, formatter: function (p) { return Math.round(p.value); } },
              data: bl.map(function (r) { return Math.round(toNum(r.avg_min) * 10) / 10; }) }]
          };
        });
      },

      /* ==================== Tab4 费用统计 ==================== */
      loadFee: function () {
        var vm = this; vm.feeLoading = true;
        HIS.get('/api/his/surgery-report/fee?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          vm.fee = { byCategory: d.byCategory || [], totalAmount: toNum(d.totalAmount), avgPerSurgery: toNum(d.avgPerSurgery), topByName: d.topByName || [] };
          vm.feeLoaded = true;
          vm.$nextTick(function () { vm.renderFeeChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.feeLoading = false; });
      },
      renderFeeChart: function () {
        var vm = this;
        var bc = (vm.fee.byCategory || []).filter(function (r) { return toNum(r.amt) > 0; });
        if (!bc.length) { return; }
        vm.mountChart('feeCatChart', 'feeCatEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' }) : { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
            legend: T.legend ? T.legend({ bottom: 0, type: 'scroll' }) : { bottom: 0 },
            series: [{ name: '费用构成', type: 'pie', radius: ['40%', '66%'], center: ['50%', '44%'],
              itemStyle: { borderColor: '#fff', borderWidth: 2 }, label: { formatter: '{b}\n{d}%', fontSize: 11 },
              data: bc.map(function (r) { return { name: r.name || ('类别' + r.grp), value: Math.round(toNum(r.amt) * 100) / 100 }; }) }]
          };
        });
      },

      /* ==================== Tab5 质量指标 ==================== */
      loadQuality: function () {
        var vm = this; vm.qualityLoading = true;
        HIS.get('/api/his/surgery-report/quality?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          vm.quality = { byIncision: d.byIncision || [], total: toNum(d.total), cancelled: toNum(d.cancelled), cancelRate: toNum(d.cancelRate), reopList: d.reopList || [] };
          vm.qualityLoaded = true;
          vm.$nextTick(function () { vm.renderQualityChart(); });
        }).catch(HIS.notifyError).finally(function () { vm.qualityLoading = false; });
      },
      renderQualityChart: function () {
        var vm = this;
        var inc = (vm.quality.byIncision || []).filter(function (r) { return toNum(r.cnt) > 0; });
        if (!inc.length) { return; }
        vm.mountChart('qIncChart', 'qIncEl', function () {
          return {
            tooltip: T.tooltip ? T.tooltip({ trigger: 'item', formatter: '{b}: {c} ({d}%)' }) : { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
            legend: T.legend ? T.legend({ bottom: 0 }) : { bottom: 0 },
            series: [{ name: '切口类型', type: 'pie', radius: ['40%', '66%'], center: ['50%', '44%'],
              itemStyle: { borderColor: '#fff', borderWidth: 2 }, label: { formatter: '{b}\n{d}%', fontSize: 11 },
              data: inc.map(function (r) { return { name: r.name || ('切口' + r.grp), value: toNum(r.cnt) }; }) }]
          };
        });
      },

      /* ==================== Tab6 工作台KPI ==================== */
      loadDashboard: function () {
        var vm = this; vm.dashboardLoading = true;
        HIS.get('/api/his/surgery-report/dashboard?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          vm.dashboard = {
            today: d.today || '', todayCount: toNum(d.todayCount), inOperation: toNum(d.inOperation),
            pendingSchedule: toNum(d.pendingSchedule), completeRate: toNum(d.completeRate), cancelRate: toNum(d.cancelRate),
            level34Ratio: toNum(d.level34Ratio), range30Count: toNum(d.range30Count)
          };
          vm.dashboardLoaded = true;
        }).catch(HIS.notifyError).finally(function () { vm.dashboardLoading = false; });
      },

      /* ==================== Tab7 设备利用率(P4b) ==================== */
      loadDevice: function () {
        var vm = this; vm.deviceLoading = true;
        HIS.get('/api/his/surgery-report/device-utilization?' + vm.baseParams().toString()).then(function (d) {
          d = d || {};
          var byRoom = (d.byRoom || []).map(function (r) {
            return { room: r.room, roomName: r.room_name || '', roomType: toNum(r.room_type), cases: toNum(r.cases),
              activeCases: toNum(r.active_cases), occupiedMin: toNum(r.occupiedMin), rate: toNum(r.utilizationRate) };
          });
          var byType = (d.byRoomType || []).map(function (r) {
            return { rt: toNum(r.rt), name: r.name || ('类型' + r.rt), roomCount: toNum(r.roomCount), cases: toNum(r.cases),
              activeCases: toNum(r.active_cases), occupiedMin: toNum(r.occupiedMin), rate: toNum(r.utilizationRate) };
          });
          vm.device = { byRoom: byRoom, byRoomType: byType, dayCount: toNum(d.dayCount),
            availMinPerDay: toNum(d.availMinPerDay), startDate: d.startDate || '', endDate: d.endDate || '' };
          vm.deviceLoaded = true;
        }).catch(HIS.notifyError).finally(function () { vm.deviceLoading = false; });
      },
      deviceRoomTypeLabel: function (rt) { return DEVICE_ROOM_TYPE[rt] || orDash(rt); },

      /* ==================== 快照 / 导出 ==================== */
      saveSnapshot: function () {
        var vm = this;
        var tab = vm.activeTab;
        vm.snapLoading = true;
        HIS.post('/api/his/surgery-report/' + tab + '/snapshot?' + vm.baseParams().toString(), {}).then(function () {
          HIS.notifySuccess('已保存「' + vm.currentTabLabel + '」快照');
        }).catch(HIS.notifyError).finally(function () { vm.snapLoading = false; });
      },

      exportCsv: function () {
        var tab = this.activeTab, headers = [], rows = [];
        if (tab === 'volume') {
          headers = ['类别', '名称', '数量'];
          rows = (this.volume.byLevel || []).map(function (r) { return ['手术级别', r.name || r.grp, toNum(r.cnt)]; })
            .concat((this.volume.byDept || []).map(function (r) { return ['科室', r.name, toNum(r.cnt)]; }))
            .concat((this.volume.bySurgeon || []).map(function (r) { return ['术者', r.name, toNum(r.cnt)]; }))
            .concat((this.volume.applyFunnel || []).map(function (r) { return ['申请状态', APPLY_STATUS[toNum(r.st)] || r.st, toNum(r.cnt)]; }));
        } else if (tab === 'anesthesia') {
          headers = ['类别', '名称', '数量'];
          rows = (this.anesthesia.byType || []).map(function (r) { return ['麻醉类型', r.name || r.grp, toNum(r.cnt)]; })
            .concat((this.anesthesia.byAsa || []).map(function (r) { return ['ASA', orDash(r.name), toNum(r.cnt)]; }));
        } else if (tab === 'duration') {
          headers = ['手术级别', '平均台内时长(分)', '例数'];
          rows = (this.duration.byLevel || []).map(function (r) { return [r.name || ('级别' + r.grp), fmtNum(r.avg_min, 1), toNum(r.cnt)]; });
        } else if (tab === 'fee') {
          headers = ['费用类别', '金额(元)', '笔数'];
          rows = (this.fee.byCategory || []).map(function (r) { return [r.name || ('类别' + r.grp), fmtMoney(r.amt), toNum(r.cnt)]; });
        } else if (tab === 'quality') {
          headers = ['切口类型', '数量'];
          rows = (this.quality.byIncision || []).map(function (r) { return [r.name || ('切口' + r.grp), toNum(r.cnt)]; });
        } else if (tab === 'dashboard') {
          headers = ['指标', '数值'];
          var d = this.dashboard;
          rows = [['今日台次', d.todayCount], ['在术', d.inOperation], ['待安排', d.pendingSchedule],
            ['完成率(%)', pct100(d.completeRate)], ['取消率(%)', pct100(d.cancelRate)], ['三四级占比(%)', pct100(d.level34Ratio)], ['近30天台次', d.range30Count]];
        }
        if (!rows.length) {
          if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.warning('当前页签暂无数据可导出'); }
          return;
        }
        var r0 = this.dateRange || [];
        var name = '手术统计报表-' + this.currentTabLabel + '-' + (r0[0] || '') + '_' + (r0[1] || '') + '.csv';
        csvDownload(name, headers, rows);
        HIS.notifySuccess('已导出 ' + name);
      },

      /* ==================== 图表挂载 / 尺寸 ==================== */
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
      this.loadActive();
    },

    unmounted: function () {
      window.removeEventListener('resize', this.onResize);
      var vm = this;
      CHART_KEYS.forEach(function (k) { if (vm[k]) { vm[k].dispose(); vm[k] = null; } });
    },

    /* ==================== 模板 ==================== */
    template: [
      '<div class="sr-page">',
      '  <div class="page-title">手术统计报表 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(手术量 / 麻醉 / 时长 / 费用 / 质量 / 工作台KPI 六类只读统计 · 比率均按百分比口径)</span></div>',

      /* ---- 通用筛选栏 ---- */
      '  <div class="sr-filter">',
      '    <span class="sr-note" style="white-space:nowrap;">统计区间</span>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:252px" @change="search"></el-date-picker>',
      '    <el-select v-model="deptId" filterable placeholder="全院" style="width:160px" @change="search">',
      '      <el-option label="全院" value=""></el-option>',
      '      <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="surgeonId" filterable placeholder="全部术者" style="width:160px" @change="search">',
      '      <el-option label="全部术者" value=""></el-option>',
      '      <el-option v-for="s in doctors" :key="s.id" :label="s.staffName" :value="s.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="moduleType" placeholder="全部模块" style="width:140px" @change="search">',
      '      <el-option label="全部模块" value=""></el-option>',
      '      <el-option v-for="m in moduleOpts" :key="m.value" :label="m.label" :value="m.value"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <div class="spacer"></div>',
      '    <el-button type="primary" plain :loading="snapLoading" @click="saveSnapshot">存快照</el-button>',
      '    <el-button @click="exportCsv">导出CSV</el-button>',
      '  </div>',

      '  <el-tabs v-model="activeTab" style="margin-top:14px;" @tab-change="onTabChange">',

      /* ================= Tab1 手术量 ================= */
      '    <el-tab-pane label="手术量" name="volume">',
      '      <div v-loading="volumeLoading">',
      kpiBlock('volKpis', 4),
      '        <div class="sr-cols">',
      '          <div class="sr-card"><div class="sr-card-title">按手术级别 <span class="sr-note">(一~四级)</span></div><div ref="volLevelEl" class="sr-chart"></div></div>',
      '          <div class="sr-card"><div class="sr-card-title">每日手术台次</div><div ref="volDateEl" class="sr-chart"></div></div>',
      '        </div>',
      '        <div class="sr-cols">',
      '          <div class="sr-card"><div class="sr-card-title">科室分布 <span class="sr-note">共 {{ volume.byDept.length }} 个科室</span></div>',
      '            <el-table :data="volume.byDept" size="small" border max-height="300" style="width:100%;">',
      '              <el-table-column type="index" label="#" width="48" align="center"></el-table-column>',
      '              <el-table-column prop="name" label="科室" min-width="120"></el-table-column>',
      '              <el-table-column prop="cnt" label="手术量" width="90" align="right"></el-table-column>',
      '            </el-table></div>',
      '          <div class="sr-card"><div class="sr-card-title">术者分布 <span class="sr-note">TOP20</span></div>',
      '            <el-table :data="volume.bySurgeon" size="small" border max-height="300" style="width:100%;">',
      '              <el-table-column type="index" label="#" width="48" align="center"></el-table-column>',
      '              <el-table-column prop="name" label="主刀医师" min-width="120"></el-table-column>',
      '              <el-table-column prop="cnt" label="手术量" width="90" align="right"></el-table-column>',
      '            </el-table></div>',
      '        </div>',
      '        <div class="sr-card sr-table-card"><div class="sr-card-title">申请漏斗 <span class="sr-note">(his_surgery_apply 按状态计数)</span></div>',
      '          <el-table :data="volume.applyFunnel" size="small" border style="width:100%;">',
      '            <el-table-column label="状态" min-width="120"><template #default="s">{{ applyStatusLabel(s.row.st) }}</template></el-table-column>',
      '            <el-table-column prop="cnt" label="数量" width="100" align="right"></el-table-column>',
      '          </el-table></div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab2 麻醉分布 ================= */
      '    <el-tab-pane label="麻醉分布" name="anesthesia">',
      '      <div v-loading="anesthesiaLoading">',
      kpiBlock('aneKpis', 3),
      '        <div class="sr-cols">',
      '          <div class="sr-card"><div class="sr-card-title">麻醉类型分布</div><div ref="aneTypeEl" class="sr-chart"></div></div>',
      '          <div class="sr-card"><div class="sr-card-title">ASA 分级分布</div><div ref="aneAsaEl" class="sr-chart"></div></div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab3 手术时长 ================= */
      '    <el-tab-pane label="手术时长" name="duration">',
      '      <div v-loading="durationLoading">',
      kpiBlock('durKpis', 3),
      '        <div class="sr-card sr-table-card"><div class="sr-card-title">按级别平均台内时长 <span class="sr-note">(开始→结束, 分钟)</span></div><div ref="durLevelEl" class="sr-chart"></div></div>',
      '        <div class="sr-card sr-table-card">',
      '          <el-table :data="duration.byLevel" size="small" border style="width:100%;">',
      '            <el-table-column label="手术级别" min-width="120"><template #default="s">{{ s.row.name || (\'级别\' + s.row.grp) }}</template></el-table-column>',
      '            <el-table-column label="平均台内时长(分)" width="150" align="right"><template #default="s">{{ fmtNum(s.row.avg_min, 1) }}</template></el-table-column>',
      '            <el-table-column prop="cnt" label="例数" width="90" align="right"></el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab4 费用统计 ================= */
      '    <el-tab-pane label="费用统计" name="fee">',
      '      <div v-loading="feeLoading">',
      kpiBlock('feeKpis', 3),
      '        <div class="sr-cols">',
      '          <div class="sr-card"><div class="sr-card-title">费用构成 <span class="sr-note">(按费用类别)</span></div><div ref="feeCatEl" class="sr-chart"></div></div>',
      '          <div class="sr-card"><div class="sr-card-title">术式 TOP10 <span class="sr-note">(按台次)</span></div>',
      '            <el-table :data="fee.topByName" size="small" border max-height="320" style="width:100%;">',
      '              <el-table-column type="index" label="#" width="48" align="center"></el-table-column>',
      '              <el-table-column prop="name" label="术式" min-width="140"></el-table-column>',
      '              <el-table-column prop="cnt" label="台次" width="70" align="right"></el-table-column>',
      '              <el-table-column label="金额(元)" width="120" align="right"><template #default="s"><span class="sr-money">{{ fmtMoney(s.row.amt) }}</span></template></el-table-column>',
      '            </el-table></div>',
      '        </div>',
      '        <div class="sr-card sr-table-card"><div class="sr-card-title">费用类别明细</div>',
      '          <el-table :data="fee.byCategory" size="small" border style="width:100%;">',
      '            <el-table-column label="费用类别" min-width="120"><template #default="s">{{ s.row.name || (\'类别\' + s.row.grp) }}</template></el-table-column>',
      '            <el-table-column label="金额(元)" width="150" align="right"><template #default="s"><span class="sr-money">{{ fmtMoney(s.row.amt) }}</span></template></el-table-column>',
      '            <el-table-column prop="cnt" label="笔数" width="90" align="right"></el-table-column>',
      '          </el-table></div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab5 质量指标 ================= */
      '    <el-tab-pane label="质量指标" name="quality">',
      '      <div v-loading="qualityLoading">',
      kpiBlock('qualityKpis', 4),
      '        <div class="sr-cols">',
      '          <div class="sr-card"><div class="sr-card-title">切口类型分布</div><div ref="qIncEl" class="sr-chart"></div></div>',
      '          <div class="sr-card"><div class="sr-card-title">非计划二次手术 <span class="sr-note">(同就诊同术式重复)</span></div>',
      '            <el-table :data="quality.reopList" size="small" border max-height="320" style="width:100%;">',
      '              <el-table-column type="index" label="#" width="48" align="center"></el-table-column>',
      '              <el-table-column prop="name" label="术式" min-width="140"></el-table-column>',
      '              <el-table-column prop="cnt" label="次数" width="80" align="right"></el-table-column>',
      '            </el-table></div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab6 工作台KPI ================= */
      '    <el-tab-pane label="工作台KPI" name="dashboard">',
      '      <div v-loading="dashboardLoading">',
      '        <div class="sr-note" style="margin-bottom:8px;">当日快照(不受区间约束): <b>{{ dashboard.today || \'-\' }}</b> · 完成率/取消率/三四级占比为近 30 天口径(共 {{ dashboard.range30Count }} 台)</div>',
      kpiBlock('dashKpis', 6),
      '      </div>',
      '    </el-tab-pane>',

      /* ================= Tab7 设备利用率(P4b) ================= */
      '    <el-tab-pane label="设备利用率" name="device">',
      '      <div v-loading="deviceLoading">',
      kpiBlock('deviceKpis', 4),
      '        <div class="sr-note" style="margin-bottom:8px;">区间 {{ device.startDate || \'-\' }} ~ {{ device.endDate || \'-\' }}（{{ device.dayCount }} 天）；可用机时按每日 {{ device.availMinPerDay }} 分钟估算（诚实口径：无设备开机日志，占用以手术实际 start~end 计）</div>',
      '        <div class="sr-cols">',
      '          <div class="sr-card"><div class="sr-card-title">按资源类型汇总</div>',
      '            <el-table :data="device.byRoomType" size="small" border style="width:100%;">',
      '              <el-table-column prop="name" label="类型" min-width="90"></el-table-column>',
      '              <el-table-column prop="roomCount" label="可用房数" width="84" align="right"></el-table-column>',
      '              <el-table-column prop="cases" label="台次" width="64" align="right"></el-table-column>',
      '              <el-table-column label="占用(分)" width="82" align="right"><template #default="s">{{ fmtNum(s.row.occupiedMin) }}</template></el-table-column>',
      '              <el-table-column label="利用率" min-width="120"><template #default="s"><el-progress :percentage="Math.round(s.row.rate*100)" :stroke-width="10"/></template></el-table-column>',
      '            </el-table></div>',
      '          <div class="sr-card"><div class="sr-card-title">按手术间/机房明细</div>',
      '            <el-table :data="device.byRoom" size="small" border max-height="360" style="width:100%;">',
      '              <el-table-column prop="room" label="手术间" min-width="90"></el-table-column>',
      '              <el-table-column label="类型" width="90" align="center"><template #default="s">{{ deviceRoomTypeLabel(s.row.roomType) }}</template></el-table-column>',
      '              <el-table-column prop="cases" label="台次" width="56" align="right"></el-table-column>',
      '              <el-table-column label="占用(分)" width="82" align="right"><template #default="s">{{ fmtNum(s.row.occupiedMin) }}</template></el-table-column>',
      '              <el-table-column label="利用率" min-width="120"><template #default="s"><el-progress :percentage="Math.round(s.row.rate*100)" :stroke-width="10"/></template></el-table-column>',
      '            </el-table></div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };

  /* KPI 卡片区(加载占位 + 实际值), cols 用于栅格类名 */
  function kpiBlock(computedName, cols) {
    var gridCls = 'sr-kpis' + (cols === 3 ? ' cols-3' : cols === 6 ? ' cols-6' : '');
    return [
      '        <div v-if="!' + computedName + '.length" class="' + gridCls + '">',
      '          <div v-for="i in ' + cols + '" :key="i" class="sr-kpi is-dim"><div class="num">--</div><div class="lbl">加载中…</div><div class="sr-kpi-foot"></div></div>',
      '        </div>',
      '        <div v-else class="' + gridCls + '">',
      '          <div v-for="(k, i) in ' + computedName + '" :key="i" class="sr-kpi">',
      '            <div class="num" :style="{ color: k.color }">{{ k.num }}</div>',
      '            <div class="lbl">{{ k.lbl }}</div>',
      '            <div class="sr-kpi-foot" :style="{ background: k.color }"></div>',
      '          </div>',
      '        </div>'
    ].join('\n');
  }

  /* 分组求和(麻醉/示例数合计) */
  function sumCnt(arr) {
    var s = 0; (arr || []).forEach(function (r) { s += toNum(r.cnt); }); return s;
  }

  var CHART_KEYS = ['volLevelChart', 'volDateChart', 'aneTypeChart', 'aneAsaChart', 'durLevelChart', 'feeCatChart', 'qIncChart'];
})();
