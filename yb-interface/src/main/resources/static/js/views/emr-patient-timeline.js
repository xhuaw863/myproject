/* 患者全景时间线: HIS.views['emr-patient-timeline'] —— 患者维度360°视图。
 * 左栏: 患者概览(基本档案/就诊统计/末次就诊/活动诊断/在院状态); 右栏: 门诊+住院就诊事件统一倒序时间线(日期分组)。
 * 后端契约(/api/his/emr/timeline, EmrTimelineController/EmrTimelineService, Task#19 已实现):
 *   GET  /patient/{patientId}?startDate&endDate&types   时间轴条目(倒序, 最近100条; types=outpatient,inpatient 逗号分隔)
 *   GET  /visit-summary/{visitId}?scope=1|2             就诊摘要(1住院 2门诊): SOAP/诊断/医嘱/病历文书/重要提示
 *   GET  /patient-overview/{patientId}                  患者总览: 就诊量/末次就诊/有效诊断/当前在院态
 * 患者检索复用既有档案接口 GET /api/his/patient/page?keyword=(HisPatientController, 姓名/档案号/证件号模糊)。
 * 语义要点: 只读聚合页, 无写操作; ID 全链路字符串(HIS.id/idParam/sameId);
 *   概览计数( Long→String 序列化)需 Number 归一后展示; 时间字段 ISO 带 T, 统一 replace('T',' ') 截断展示。
 * 注册: HIS.views['emr-patient-timeline'](与动态菜单 comp 同值; 须在 app.js 之前加载, index.html 引入)。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 事件类型: 门诊(品牌蓝) / 住院(绿) —— 点色与标签色双通道同源 */
  var TYPE_LABEL = { outpatient: '门诊', inpatient: '住院' };

  function text(v) { return v == null ? '' : String(v); }
  function dash(v) { return (v == null || v === '') ? '-' : v; }

  /* 组件私有样式: 左概览右时间线分栏 + 垂直时间轴(左轨圆点+连线) + 详情对话框(不动全局 his.css) */
  (function injectCss() {
    var st = document.getElementById('emr-patient-timeline-css');
    if (!st) { st = document.createElement('style'); st.id = 'emr-patient-timeline-css'; document.head.appendChild(st); }
    st.textContent = [
      '.ptl-tools { flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }',
      '.ptl-tools .el-button + .el-button { margin-left: 0; }',
      '.ptl-tools .el-radio-group { margin-right: 2px; }',
      '.ptl-count { font-size: 12px; color: var(--yb-ink-3); align-self: center; margin-left: auto; white-space: nowrap; }',
      '.ptl-split { flex: 1; min-height: 0; display: flex; gap: 12px; }',
      /* ===== 左栏: 患者概览 ===== */
      '.ptl-left { flex: none; width: 292px; overflow-y: auto; border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); background: var(--yb-surface,#fff); padding: 12px 14px; }',
      '.ptl-left-empty { height: 100%; min-height: 180px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--yb-ink-4,#8994a5); font-size: 13px; text-align: center; line-height: 1.8; padding: 0 12px; }',
      '.ptl-pv { padding-bottom: 10px; border-bottom: 1px solid var(--yb-divider,#f0f3f7); }',
      '.ptl-pv-name { font-size: 16px; font-weight: 600; color: var(--yb-ink-1,#1c2430); display: flex; align-items: baseline; gap: 8px; }',
      '.ptl-pv-sub { font-size: 12px; font-weight: normal; color: var(--yb-ink-3,#5a6a7e); }',
      '.ptl-pv-meta { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); margin-top: 4px; line-height: 1.6; }',
      '.ptl-sec { padding: 10px 0 8px; border-bottom: 1px solid var(--yb-divider,#f0f3f7); }',
      '.ptl-sec:last-child { border-bottom: none; padding-bottom: 2px; }',
      '.ptl-sec-t { font-size: 12px; font-weight: 600; color: var(--yb-ink-2,#3d4a5c); margin-bottom: 6px; }',
      '.ptl-sec-empty { font-size: 12px; color: var(--yb-ink-4,#8994a5); }',
      '.ptl-stat { display: flex; }',
      '.ptl-stat > div { flex: 1; text-align: center; }',
      '.ptl-stat b { display: block; font-size: 22px; font-weight: 600; color: var(--yb-brand-strong,#0f3a66); font-variant-numeric: tabular-nums; line-height: 1.25; }',
      '.ptl-stat span { font-size: 11px; color: var(--yb-ink-3,#5a6a7e); }',
      '.ptl-stat .ptl-o b { color: var(--yb-brand,#1a5c9e); }',
      '.ptl-stat .ptl-i b { color: var(--yb-success,#3c862d); }',
      '.ptl-last { font-size: 12px; color: var(--yb-ink-2,#3d4a5c); line-height: 1.7; }',
      '.ptl-last-sub { color: var(--yb-ink-3,#5a6a7e); }',
      '.ptl-last-sum { color: var(--yb-ink-3,#5a6a7e); margin-top: 2px; }',
      '.ptl-diags { display: flex; flex-wrap: wrap; gap: 6px; }',
      '.ptl-inp { font-size: 12px; color: var(--yb-ink-2,#3d4a5c); line-height: 1.7; }',
      '.ptl-inp-sub { color: var(--yb-ink-3,#5a6a7e); }',
      /* ===== 右栏: 时间线 ===== */
      '.ptl-right { flex: 1; min-width: 0; overflow-y: auto; border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); background: var(--yb-surface,#fff); padding: 6px 16px 18px; }',
      '.ptl-empty { height: 100%; min-height: 180px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--yb-ink-4,#8994a5); font-size: 13px; text-align: center; line-height: 1.8; padding: 0 16px; }',
      '.ptl-day-chip { display: flex; align-items: center; gap: 10px; margin: 14px 0 8px; color: var(--yb-ink-2,#3d4a5c); font-size: 12px; }',
      '.ptl-day-chip b { font-variant-numeric: tabular-nums; font-weight: 600; }',
      '.ptl-day-chip i { font-style: normal; color: var(--yb-ink-4,#8994a5); }',
      '.ptl-day-chip::after { content: \'\'; flex: 1; height: 1px; background: var(--yb-border-light,#ebeff4); }',
      '.ptl-item { display: flex; }',
      '.ptl-rail { flex: none; width: 16px; position: relative; }',
      '.ptl-rail::before { content: \'\'; position: absolute; left: 7px; top: 0; bottom: 0; width: 2px; background: var(--yb-border-light,#ebeff4); }',
      '.ptl-item:last-child .ptl-rail::before { bottom: auto; height: 18px; }',
      '.ptl-dot { position: absolute; left: 2px; top: 12px; width: 12px; height: 12px; border-radius: 50%; border: 2px solid #fff; box-shadow: 0 0 0 1px var(--yb-border,#dfe4eb); }',
      '.ptl-outpatient .ptl-dot { background: var(--yb-brand,#1a5c9e); }',
      '.ptl-inpatient .ptl-dot { background: var(--yb-success,#3c862d); }',
      '.ptl-card { flex: 1; min-width: 0; border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); background: var(--yb-surface,#fff); padding: 8px 12px 5px; margin: 0 0 10px 8px; transition: box-shadow .15s ease, border-color .15s ease; }',
      '.ptl-card:hover { border-color: var(--yb-brand-border,#bacee2); box-shadow: var(--yb-sh-2,0 2px 8px rgba(16,24,40,.07)); }',
      '.ptl-it-hd { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }',
      '.ptl-dept { font-weight: 600; font-size: 13px; color: var(--yb-ink-1,#1c2430); }',
      '.ptl-doc { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); }',
      '.ptl-time { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); font-variant-numeric: tabular-nums; }',
      '.ptl-inpno { font-size: 11px; color: var(--yb-ink-4,#8994a5); font-family: var(--yb-font-mono,monospace); }',
      '.ptl-grow { flex: 1; }',
      '.ptl-it-body { margin-top: 5px; }',
      '.ptl-line { font-size: 12px; color: var(--yb-ink-2,#3d4a5c); line-height: 1.6; display: flex; gap: 6px; }',
      '.ptl-line label { flex: none; color: var(--yb-ink-4,#8994a5); }',
      '.ptl-line span { flex: 1; min-width: 0; word-break: break-all; }',
      '.ptl-meta { margin-top: 3px; font-size: 11px; color: var(--yb-ink-4,#8994a5); }',
      '.ptl-it-ft { display: flex; justify-content: flex-end; margin-top: 2px; }',
      '.ptl-more { text-align: center; margin-top: 6px; }',
      '.ptl-end { text-align: center; color: var(--yb-ink-4,#8994a5); font-size: 11px; margin-top: 10px; }',
      /* ===== 患者检索下拉选项 ===== */
      '.ptl-opt { display: flex; align-items: center; gap: 10px; }',
      '.ptl-opt-name { color: var(--yb-ink-1,#1c2430); font-weight: 500; }',
      '.ptl-opt-meta { color: var(--yb-ink-4,#8994a5); font-size: 12px; }',
      /* ===== 详情对话框 ===== */
      '.ptl-dl-hd { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }',
      '.ptl-dl-title { font-size: 15px; font-weight: 600; color: var(--yb-ink-1,#1c2430); }',
      '.ptl-dl-sub { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); }',
      '.ptl-dl-patient { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); background: var(--yb-surface-2,#f7f9fc); border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-sm,4px); padding: 5px 10px; margin-bottom: 12px; }',
      '.ptl-dl-patient b { color: var(--yb-ink-1,#1c2430); font-weight: 600; }',
      '.ptl-dl-alerts { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }',
      '.ptl-soap { border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); overflow: hidden; }',
      '.ptl-soap-r { display: flex; gap: 10px; padding: 7px 12px; border-bottom: 1px dashed var(--yb-divider,#f0f3f7); font-size: 12px; line-height: 1.65; }',
      '.ptl-soap-r:last-child { border-bottom: none; }',
      '.ptl-soap-r label { flex: none; width: 64px; color: var(--yb-ink-3,#5a6a7e); }',
      '.ptl-soap-r div { flex: 1; color: var(--yb-ink-1,#1c2430); white-space: pre-wrap; word-break: break-all; }',
      '.ptl-diag { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px dashed var(--yb-divider,#f0f3f7); font-size: 12px; }',
      '.ptl-diag:last-child { border-bottom: none; }',
      '.ptl-diag-name { color: var(--yb-ink-1,#1c2430); font-weight: 500; }',
      '.ptl-diag-code { color: var(--yb-ink-4,#8994a5); font-size: 11px; font-family: var(--yb-font-mono,monospace); }',
      '.ptl-diag-list { border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); }',
      '.ptl-empty-inline { color: var(--yb-ink-4,#8994a5); font-size: 12px; text-align: center; padding: 18px 0; }'
    ].join('\n');
  })();

  HIS.views['emr-patient-timeline'] = {
    name: 'EmrPatientTimeline',
    data: function () {
      return {
        /* 患者检索(el-select remote) */
        patientId: null, patientOptions: [], patientSearching: false,
        /* 概览(左栏): overview.patient 覆盖选中档案行作权威展示源 */
        patient: null, overview: null, overviewLoading: false,
        /* 时间线(右栏) */
        items: [], timelineLoading: false, timelineLoaded: false,
        typeFilter: 'all', dateRange: null, visibleCount: 20,
        /* 就诊详情对话框 */
        detailDlg: false, detailLoading: false, detail: null, detailCtx: null, detailTab: 'record'
      };
    },
    computed: {
      /* 按日期(倒序截断至 visibleCount)分组: [{date, week, items}] */
      timeGroups: function () {
        var shown = Math.min((this.items || []).length, this.visibleCount);
        var groups = [], idx = {}, order = [];
        for (var i = 0; i < shown; i++) {
          var it = this.items[i];
          var d = text(it.time).substring(0, 10) || '未知日期';
          if (!idx[d]) { idx[d] = []; order.push(d); }
          idx[d].push(it);
        }
        for (var k = 0; k < order.length; k++) {
          groups.push({ date: order[k], week: this.dayWeek(order[k]), items: idx[order[k]] });
        }
        return groups;
      },
      hasMore: function () { return (this.items || []).length > this.visibleCount; },
      hiddenCount: function () { return (this.items || []).length - this.visibleCount; },
      /* 门诊 SOAP 摘要行(空字段过滤): [label, value] */
      soapRows: function () {
        var d = this.detail;
        if (!d || d.type !== 'outpatient') { return []; }
        var defs = [
          ['主诉', d.chiefComplaint], ['现病史', d.presentIllness], ['既往史', d.pastHistory],
          ['过敏史', d.allergyHistory], ['体格检查', d.physicalExam], ['生命体征', d.vitals],
          ['辅助检查', d.auxExam], ['处理意见', d.treatmentOpinion]
        ];
        return defs.filter(function (x) { return text(x[1]).trim() !== ''; });
      },
      /* 详情对话框患者行: 摘要接口 patient 优先, 回退概览档案 */
      dlPatient: function () {
        var p = (this.detail && this.detail.patient) || this.patient || {};
        var segs = [];
        if (p.name) { segs.push(String(p.name)); }
        if (p.gender) { segs.push(String(p.gender)); }
        if (p.age != null && p.age !== '') { segs.push(p.age + '岁'); }
        if (p.patientNo) { segs.push('档案号 ' + p.patientNo); }
        return segs.join(' · ');
      },
      /* 概览就诊计数(Long→String 下发, Number 归一) */
      statTotal: function () { return this.overview ? Number(this.overview.totalVisits || 0) : null; },
      statOutp: function () { return this.overview ? Number(this.overview.outpatientCount || 0) : null; },
      statInp: function () { return this.overview ? Number(this.overview.inpatientCount || 0) : null; }
    },
    methods: {
      /* ===== 公共助手 ===== */
      dash: dash,
      typeLabel: function (t) { return TYPE_LABEL[t] || dash(t); },
      typeTag: function (t) { return t === 'inpatient' ? 'success' : 'primary'; },
      genderText: function (g) { return (g === '1' || g === 1) ? '男' : ((g === '2' || g === 2) ? '女' : dash(g)); },
      fmtDT: function (v) { return (v == null || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 16); },
      fmtMoney: function (v) {
        if (v == null || v === '') { return '-'; }
        var n = Number(v);
        return isNaN(n) ? '-' : '¥' + n.toFixed(2);
      },
      dayWeek: function (d) {
        var t = Date.parse(text(d));
        if (isNaN(t)) { return ''; }
        return '周' + ['日', '一', '二', '三', '四', '五', '六'][new Date(t).getDay()];
      },
      itemStatus: function (it) { return (it && it.details && it.details.visitStatusText) || ''; },
      /* 时间线卡片正文/元信息 */
      outpMeta: function (it) {
        var d = (it && it.details) || {}, parts = [];
        if (d.orderCount != null) { parts.push('医嘱 ' + d.orderCount + ' 条'); }
        return parts.join(' · ');
      },
      inpMeta: function (it) {
        var d = (it && it.details) || {}, parts = [];
        if (d.inDays != null) { parts.push('住院 ' + d.inDays + ' 天'); }
        if (d.recordCount != null) { parts.push('文书 ' + d.recordCount + ' 份'); }
        if (d.orderCount != null) { parts.push('医嘱 ' + d.orderCount + ' 条'); }
        return parts.join(' · ');
      },
      inpRecords: function (it) {
        var list = (it && it.details && it.details.latestRecords) || [];
        return list.slice(0, 3).join('、');
      },
      patientLabel: function (p) {
        if (!p) { return ''; }
        var s = text(p.name);
        var g = this.genderText(p.gender);
        if (g && g !== '-') { s += '/' + g; }
        if (p.age != null && p.age !== '') { s += '/' + p.age + '岁'; }
        if (p.patientNo) { s += ' (' + p.patientNo + ')'; }
        return s;
      },
      /* ===== 患者检索(remote) ===== */
      searchPatients: function (q) {
        var vm = this;
        var kw = text(q).trim();
        if (!kw) { vm.patientOptions = []; return; }
        vm.patientSearching = true;
        HIS.get('/api/his/patient/page?page=1&size=20&keyword=' + encodeURIComponent(kw)).then(function (d) {
          vm.patientOptions = (d && d.records) || [];
          if (!vm.patientOptions.length) { ElementPlus.ElMessage.warning('未找到匹配的患者档案'); }
        }).catch(HIS.notifyError).finally(function () { vm.patientSearching = false; });
      },
      onPickPatient: function (val) {
        var vm = this;
        if (!val) { vm.resetPatient(); return; }
        vm.patientId = HIS.id(val);
        /* 选中即以档案行作概览占位, 载入后被 overview.patient 覆盖 */
        var hit = vm.patientOptions.filter(function (p) { return HIS.sameId(p.id, val); })[0];
        vm.patient = hit ? {
          name: hit.name, patientNo: hit.patientNo, gender: hit.gender, age: hit.age, phone: hit.phone
        } : null;
        vm.overview = null;
        vm.loadOverview();
        vm.loadTimeline();
      },
      resetPatient: function () {
        this.patientId = null; this.patient = null; this.overview = null;
        this.items = []; this.timelineLoaded = false; this.visibleCount = 20;
      },
      /* ===== 概览(左栏) ===== */
      loadOverview: function () {
        var vm = this;
        if (!vm.patientId) { vm.overview = null; return; }
        vm.overviewLoading = true;
        HIS.get('/api/his/emr/timeline/patient-overview/' + HIS.idParam(vm.patientId)).then(function (d) {
          vm.overview = d || null;
          if (d && d.patient) { vm.patient = d.patient; }
        }).catch(function (e) { vm.overview = null; HIS.notifyError(e); })
          .finally(function () { vm.overviewLoading = false; });
      },
      /* ===== 时间线(右栏) ===== */
      loadTimeline: function () {
        var vm = this;
        if (!vm.patientId) { vm.items = []; vm.timelineLoaded = false; return; }
        vm.timelineLoading = true;
        var q = '/api/his/emr/timeline/patient/' + HIS.idParam(vm.patientId);
        var p = [];
        if (vm.dateRange && vm.dateRange[0]) { p.push('startDate=' + encodeURIComponent(vm.dateRange[0])); }
        if (vm.dateRange && vm.dateRange[1]) { p.push('endDate=' + encodeURIComponent(vm.dateRange[1])); }
        if (vm.typeFilter && vm.typeFilter !== 'all') { p.push('types=' + encodeURIComponent(vm.typeFilter)); }
        if (p.length) { q += '?' + p.join('&'); }
        HIS.get(q).then(function (list) {
          vm.items = list || [];
          vm.timelineLoaded = true;
          vm.visibleCount = 20;
        }).catch(function (e) {
          vm.items = []; vm.timelineLoaded = true; HIS.notifyError(e);
        }).finally(function () { vm.timelineLoading = false; });
      },
      onQuery: function () {
        if (!this.patientId) { ElementPlus.ElMessage.warning('请先检索并选择患者'); return; }
        this.loadTimeline();
      },
      onReset: function () {
        this.typeFilter = 'all'; this.dateRange = null;
        if (this.patientId) { this.loadTimeline(); }
      },
      loadMore: function () { this.visibleCount += 20; },
      /* ===== 就诊详情对话框 ===== */
      openDetail: function (it) {
        var vm = this;
        if (!it || it.visitId == null || it.visitId === '') { return; }
        var scope = it.type === 'inpatient' ? 1 : 2;
        vm.detailCtx = {
          type: it.type, scope: scope, visitId: HIS.id(it.visitId),
          deptName: it.deptName, doctorName: it.doctorName,
          time: vm.fmtDT(it.time), statusText: vm.itemStatus(it)
        };
        vm.detailDlg = true; vm.detailLoading = true; vm.detail = null; vm.detailTab = 'record';
        HIS.get('/api/his/emr/timeline/visit-summary/' + HIS.idParam(it.visitId) + '?scope=' + scope)
          .then(function (d) { vm.detail = d || null; })
          .catch(function (e) { vm.detail = null; HIS.notifyError(e); })
          .finally(function () { vm.detailLoading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">患者全景时间线 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(门诊+住院就诊史统一时间轴 · 只读 · 最多展示最近100条)</span></div>',
      /* ===== 检索与筛选 ===== */
      '  <div class="toolbar ptl-tools">',
      '    <el-select v-model="patientId" size="small" filterable remote reserve-keyword clearable',
      '      :remote-method="searchPatients" :loading="patientSearching"',
      '      placeholder="输入患者姓名 / 档案号 / 证件号检索" style="width:250px" @change="onPickPatient">',
      '      <el-option v-for="p in patientOptions" :key="p.id" :label="patientLabel(p)" :value="p.id">',
      '        <div class="ptl-opt">',
      '          <span class="ptl-opt-name">{{ p.name }}</span>',
      '          <span class="ptl-opt-meta">{{ genderText(p.gender) }} · {{ dash(p.age) }}岁 · {{ dash(p.patientNo) }}</span>',
      '        </div>',
      '      </el-option>',
      '    </el-select>',
      '    <el-radio-group v-model="typeFilter" size="small" @change="onQuery">',
      '      <el-radio-button value="all">全部</el-radio-button>',
      '      <el-radio-button value="outpatient">门诊</el-radio-button>',
      '      <el-radio-button value="inpatient">住院</el-radio-button>',
      '    </el-radio-group>',
      '    <el-date-picker v-model="dateRange" size="small" type="daterange" value-format="YYYY-MM-DD"',
      '      range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px"></el-date-picker>',
      '    <el-button size="small" type="primary" :disabled="!patientId" @click="onQuery">查询</el-button>',
      '    <el-button size="small" :disabled="!patientId" @click="onReset">重置</el-button>',
      '    <span class="ptl-count" v-if="patientId && timelineLoaded">已加载 {{ items.length }} 条就诊事件</span>',
      '  </div>',
      '  <div class="ptl-split">',
      /* ===== 左栏: 患者概览 ===== */
      '    <div class="ptl-left" v-loading="overviewLoading">',
      '      <template v-if="patient">',
      '        <div class="ptl-pv">',
      '          <div class="ptl-pv-name">{{ patient.name }}<span class="ptl-pv-sub" v-if="patient.gender || patient.age != null">{{ genderText(patient.gender) }} · {{ dash(patient.age) }}岁</span></div>',
      '          <div class="ptl-pv-meta">档案号 {{ dash(patient.patientNo) }}</div>',
      '          <div class="ptl-pv-meta" v-if="patient.phone">联系电话 {{ patient.phone }}</div>',
      '        </div>',
      '        <div class="ptl-sec">',
      '          <div class="ptl-sec-t">就诊统计</div>',
      '          <div class="ptl-stat">',
      '            <div><b>{{ statTotal == null ? \'-\' : statTotal }}</b><span>总就诊</span></div>',
      '            <div class="ptl-o"><b>{{ statOutp == null ? \'-\' : statOutp }}</b><span>门诊</span></div>',
      '            <div class="ptl-i"><b>{{ statInp == null ? \'-\' : statInp }}</b><span>住院</span></div>',
      '          </div>',
      '        </div>',
      '        <div class="ptl-sec">',
      '          <div class="ptl-sec-t">末次就诊</div>',
      '          <div class="ptl-last" v-if="overview && overview.lastVisit">',
      '            <div><el-tag size="small" :type="typeTag(overview.lastVisit.type)">{{ typeLabel(overview.lastVisit.type) }}</el-tag> {{ fmtDT(overview.lastVisit.time) }}</div>',
      '            <div class="ptl-last-sub">{{ dash(overview.lastVisit.deptName) }}<span v-if="overview.lastVisit.doctorName"> · {{ overview.lastVisit.doctorName }}</span></div>',
      '            <div class="ptl-last-sum" v-if="overview.lastVisit.summary">{{ overview.lastVisit.summary }}</div>',
      '          </div>',
      '          <div class="ptl-sec-empty" v-else>暂无就诊记录</div>',
      '        </div>',
      '        <div class="ptl-sec">',
      '          <div class="ptl-sec-t">活动诊断<span style="font-weight:normal;color:var(--yb-ink-4);margin-left:6px;">(末次就诊有效诊断)</span></div>',
      '          <div class="ptl-diags" v-if="overview && (overview.activeDiagnoses || []).length">',
      '            <el-tag v-for="(d, i) in overview.activeDiagnoses" :key="i" size="small"',
      '              :type="d.isMain ? \'danger\' : \'info\'" :effect="d.isMain ? \'dark\' : \'plain\'">{{ d.diagName || \'-\' }}</el-tag>',
      '          </div>',
      '          <div class="ptl-sec-empty" v-else>暂无有效诊断</div>',
      '        </div>',
      '        <div class="ptl-sec">',
      '          <div class="ptl-sec-t">当前状态</div>',
      '          <div class="ptl-inp" v-if="overview && overview.currentInpatient">',
      '            <div><el-tag size="small" type="success">在院中</el-tag><span v-if="overview.currentInpatient.inpNo" class="ptl-inpno" style="margin-left:6px;">{{ overview.currentInpatient.inpNo }}</span></div>',
      '            <div class="ptl-inp-sub">{{ dash(overview.currentInpatient.deptName) }}<span v-if="overview.currentInpatient.doctorName"> · {{ overview.currentInpatient.doctorName }}</span></div>',
      '            <div class="ptl-inp-sub" v-if="overview.currentInpatient.inDays != null">已住院 {{ overview.currentInpatient.inDays }} 天 · 押金余额 {{ fmtMoney(overview.currentInpatient.depositBalance) }}</div>',
      '          </div>',
      '          <div v-else><el-tag size="small" type="info" effect="plain">未在院</el-tag></div>',
      '        </div>',
      '      </template>',
      '      <div class="ptl-left-empty" v-else>',
      '        <div>请先在上方检索并选择患者</div>',
      '        <div style="font-size:12px;">支持姓名 / 档案号 / 证件号模糊检索, 选中后展示其门诊与住院就诊全景</div>',
      '      </div>',
      '    </div>',
      /* ===== 右栏: 时间线 ===== */
      '    <div class="ptl-right" v-loading="timelineLoading">',
      '      <template v-if="patientId">',
      '        <template v-if="timeGroups.length">',
      '          <div v-for="g in timeGroups" :key="g.date">',
      '            <div class="ptl-day-chip"><b>{{ g.date }}</b><i v-if="g.week">{{ g.week }}</i></div>',
      '            <div class="ptl-item" v-for="it in g.items" :key="it.id" :class="\'ptl-\' + it.type">',
      '              <div class="ptl-rail"><span class="ptl-dot"></span></div>',
      '              <div class="ptl-card">',
      '                <div class="ptl-it-hd">',
      '                  <el-tag size="small" :type="typeTag(it.type)">{{ typeLabel(it.type) }}</el-tag>',
      '                  <span class="ptl-dept">{{ dash(it.deptName) }}</span>',
      '                  <span class="ptl-doc" v-if="it.doctorName">{{ it.doctorName }}</span>',
      '                  <span class="ptl-time">{{ fmtDT(it.time) }}</span>',
      '                  <span class="ptl-inpno" v-if="it.type === \'inpatient\' && it.details && it.details.inpNo">{{ it.details.inpNo }}</span>',
      '                  <span class="ptl-grow"></span>',
      '                  <el-tag v-if="itemStatus(it)" size="small" type="info" effect="plain">{{ itemStatus(it) }}</el-tag>',
      '                </div>',
      '                <div class="ptl-it-body">',
      '                  <div class="ptl-line" v-if="it.type === \'outpatient\'"><label>主诉</label><span>{{ (it.details && it.details.chiefComplaint) || \'-\' }}</span></div>',
      '                  <div class="ptl-line" v-if="it.type === \'outpatient\'"><label>诊断</label><span>{{ (it.details && it.details.diagnosisText) || \'-\' }}</span></div>',
      '                  <div class="ptl-line" v-if="it.type === \'inpatient\'"><label>入院诊断</label><span>{{ (it.details && it.details.admitDiag) || \'-\' }}</span></div>',
      '                  <div class="ptl-line" v-if="it.type === \'inpatient\'"><label>诊断</label><span>{{ (it.details && it.details.diagnosisText) || \'-\' }}</span></div>',
      '                  <div class="ptl-meta" v-if="it.type === \'outpatient\' && outpMeta(it)">{{ outpMeta(it) }}</div>',
      '                  <div class="ptl-meta" v-if="it.type === \'inpatient\' && inpMeta(it)">{{ inpMeta(it) }}<span v-if="inpRecords(it)"> · 最近文书: {{ inpRecords(it) }}</span></div>',
      '                </div>',
      '                <div class="ptl-it-ft"><el-button link size="small" type="primary" @click="openDetail(it)">查看详情</el-button></div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '          <div class="ptl-more" v-if="hasMore"><el-button size="small" @click="loadMore">加载更多(余 {{ hiddenCount }} 条)</el-button></div>',
      '          <div class="ptl-end" v-else>— 已展示全部 {{ items.length }} 条就诊事件 —</div>',
      '        </template>',
      '        <div class="ptl-empty" v-else-if="!timelineLoading">',
      '          <div>当前筛选条件下无就诊事件</div>',
      '          <div style="font-size:12px;">可切换类型为「全部」或清空日期区间后重新查询</div>',
      '        </div>',
      '      </template>',
      '      <div class="ptl-empty" v-else>',
      '        <div>请先在上方检索并选择患者</div>',
      '        <div style="font-size:12px;">时间线将按时间倒序展示其门诊就诊与住院入院事件, 点击「查看详情」可查看就诊摘要</div>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ===== 就诊详情对话框 ===== */
      '  <el-dialog v-model="detailDlg" width="880px" append-to-body :close-on-click-modal="false">',
      '    <template #header>',
      '      <div class="ptl-dl-hd">',
      '        <el-tag size="small" :type="detailCtx ? typeTag(detailCtx.type) : \'info\'">{{ detailCtx ? typeLabel(detailCtx.type) + \'就诊摘要\' : \'就诊摘要\' }}</el-tag>',
      '        <span class="ptl-dl-title">{{ detailCtx ? dash(detailCtx.deptName) : \'-\' }}</span>',
      '        <span class="ptl-dl-sub" v-if="detailCtx">{{ dash(detailCtx.doctorName) }} · {{ detailCtx.time }}</span>',
      '        <el-tag v-if="detailCtx && detailCtx.statusText" size="small" type="info" effect="plain">{{ detailCtx.statusText }}</el-tag>',
      '      </div>',
      '    </template>',
      '    <div v-loading="detailLoading" style="min-height:220px;">',
      '      <template v-if="detail">',
      '        <div class="ptl-dl-patient" v-if="dlPatient">患者: <b>{{ dlPatient }}</b></div>',
      '        <div class="ptl-dl-alerts" v-if="(detail.importantNotes || []).length">',
      '          <el-alert v-for="(n, i) in detail.importantNotes" :key="i" type="warning" :title="n" :closable="false" show-icon></el-alert>',
      '        </div>',
      '        <el-tabs v-model="detailTab">',
      /* ----- 诊疗记录 ----- */
      '          <el-tab-pane label="诊疗记录" name="record">',
      '            <template v-if="detail.type === \'outpatient\'">',
      '              <div class="ptl-soap" v-if="soapRows.length">',
      '                <div class="ptl-soap-r" v-for="(r, i) in soapRows" :key="i"><label>{{ r[0] }}</label><div>{{ r[1] }}</div></div>',
      '              </div>',
      '              <div class="ptl-empty-inline" v-else>本次就诊未记录 SOAP 病历内容</div>',
      '            </template>',
      '            <template v-else>',
      '              <el-descriptions :column="2" border size="small">',
      '                <el-descriptions-item label="住院号">{{ dash(detail.inpNo) }}</el-descriptions-item>',
      '                <el-descriptions-item label="病情等级">{{ dash(detail.conditionLevelText) }}</el-descriptions-item>',
      '                <el-descriptions-item label="主治医师">{{ dash(detail.doctorName) }}</el-descriptions-item>',
      '                <el-descriptions-item label="责任护士">{{ dash(detail.nurseName) }}</el-descriptions-item>',
      '                <el-descriptions-item label="入院时间">{{ fmtDT(detail.admitDate) }}</el-descriptions-item>',
      '                <el-descriptions-item label="出院时间">{{ fmtDT(detail.dischargeDate) }}</el-descriptions-item>',
      '                <el-descriptions-item label="住院天数">{{ detail.inDays != null ? detail.inDays + \' 天\' : \'-\' }}</el-descriptions-item>',
      '                <el-descriptions-item label="费用 / 押金余额">{{ fmtMoney(detail.totalCost) }} / {{ fmtMoney(detail.depositBalance) }}</el-descriptions-item>',
      '                <el-descriptions-item label="入院诊断" :span="2">{{ dash(detail.admitDiag) }}</el-descriptions-item>',
      '                <el-descriptions-item label="主诉" :span="2">{{ dash(detail.chiefComplaint) }}</el-descriptions-item>',
      '              </el-descriptions>',
      '            </template>',
      '          </el-tab-pane>',
      /* ----- 诊断 ----- */
      '          <el-tab-pane :label="\'诊断(\' + ((detail.diagnoses || []).length) + \')\'" name="diag">',
      '            <div class="ptl-diag-list" v-if="(detail.diagnoses || []).length">',
      '              <div class="ptl-diag" v-for="(d, i) in detail.diagnoses" :key="i">',
      '                <el-tag v-if="d.isMain" size="small" type="danger" effect="dark">主诊断</el-tag>',
      '                <el-tag v-else size="small" type="info" effect="plain">{{ i + 1 }}</el-tag>',
      '                <span class="ptl-diag-name">{{ dash(d.diagName) }}</span>',
      '                <span class="ptl-diag-code" v-if="d.diagCode">{{ d.diagCode }}</span>',
      '                <el-tag v-if="d.diagTypeText" size="small" effect="plain">{{ d.diagTypeText }}</el-tag>',
      '              </div>',
      '            </div>',
      '            <div class="ptl-empty-inline" v-else>无诊断记录</div>',
      '          </el-tab-pane>',
      /* ----- 医嘱 ----- */
      '          <el-tab-pane :label="\'医嘱(\' + (detail.type === \'outpatient\' ? (detail.orderCount || 0) : (detail.orderTotal || 0)) + \')\'" name="order">',
      '            <el-table v-if="detail.type === \'outpatient\'" :data="detail.orders || []" border size="small" max-height="340">',
      '              <el-table-column type="index" label="序号" width="56" align="center"></el-table-column>',
      '              <el-table-column prop="orderNo" label="单号" width="160" show-overflow-tooltip><template #default="s">{{ dash(s.row.orderNo) }}</template></el-table-column>',
      '              <el-table-column prop="orderType" label="类型" width="90"><template #default="s">{{ dash(s.row.orderType) }}</template></el-table-column>',
      '              <el-table-column prop="diagName" label="关联诊断" min-width="130" show-overflow-tooltip><template #default="s">{{ dash(s.row.diagName) }}</template></el-table-column>',
      '              <el-table-column label="状态" width="90"><template #default="s">{{ dash(s.row.statusText) }}</template></el-table-column>',
      '              <el-table-column label="执行" width="80"><template #default="s">{{ dash(s.row.execStatusText) }}</template></el-table-column>',
      '              <el-table-column label="金额" width="100" align="right"><template #default="s">{{ fmtMoney(s.row.totalAmount) }}</template></el-table-column>',
      '            </el-table>',
      '            <el-table v-else :data="detail.orders || []" border size="small" max-height="360">',
      '              <el-table-column type="index" label="序号" width="56" align="center"></el-table-column>',
      '              <el-table-column prop="orderTypeText" label="类型" width="70"><template #default="s">{{ dash(s.row.orderTypeText) }}</template></el-table-column>',
      '              <el-table-column prop="orderCategoryText" label="类别" width="80"><template #default="s">{{ dash(s.row.orderCategoryText) }}</template></el-table-column>',
      '              <el-table-column prop="orderContent" label="内容" min-width="200" show-overflow-tooltip><template #default="s">{{ dash(s.row.orderContent) }}</template></el-table-column>',
      '              <el-table-column label="规格 / 剂量" width="130" show-overflow-tooltip><template #default="s">{{ dash(s.row.spec) }}<span v-if="s.row.dosage"> · {{ s.row.dosage }}</span></template></el-table-column>',
      '              <el-table-column label="开始时间" width="140"><template #default="s">{{ fmtDT(s.row.startTime) }}</template></el-table-column>',
      '              <el-table-column label="停止时间" width="140"><template #default="s">{{ fmtDT(s.row.stopTime) }}</template></el-table-column>',
      '              <el-table-column label="状态" width="90"><template #default="s">{{ dash(s.row.orderStatusText) }}</template></el-table-column>',
      '            </el-table>',
      '            <div class="ptl-empty-inline" v-if="!(detail.orders || []).length">无医嘱记录</div>',
      '          </el-tab-pane>',
      /* ----- 病历文书(仅住院) ----- */
      '          <el-tab-pane v-if="detail.type === \'inpatient\'" :label="\'病历文书(\' + (detail.recordCount || 0) + \')\'" name="record-list">',
      '            <el-table :data="detail.records || []" border size="small" max-height="360">',
      '              <el-table-column type="index" label="序号" width="56" align="center"></el-table-column>',
      '              <el-table-column prop="recordTypeText" label="类型" width="100"><template #default="s">{{ dash(s.row.recordTypeText) }}</template></el-table-column>',
      '              <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip><template #default="s">{{ dash(s.row.title) }}</template></el-table-column>',
      '              <el-table-column label="书写时间" width="150"><template #default="s">{{ fmtDT(s.row.recordTime) }}</template></el-table-column>',
      '              <el-table-column label="状态" width="90"><template #default="s">{{ dash(s.row.statusText) }}</template></el-table-column>',
      '              <el-table-column label="医师" width="100"><template #default="s">{{ dash(s.row.doctorName) }}</template></el-table-column>',
      '            </el-table>',
      '            <div class="ptl-empty-inline" v-if="!(detail.records || []).length">无病历文书</div>',
      '          </el-tab-pane>',
      '        </el-tabs>',
      '      </template>',
      '      <div class="ptl-empty-inline" v-else-if="!detailLoading">摘要为空或加载失败, 可关闭后重试</div>',
      '    </div>',
      '    <template #footer><el-button size="small" @click="detailDlg = false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
