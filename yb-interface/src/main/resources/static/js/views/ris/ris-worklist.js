/* RIS 技师检查工作台: 今日任务板(Worklist+执行记录两源合并) · 到检签到 → 开始检查 → 完成检查(剂量/图像/StudyUID 登记)
 * · DICOM Worklist 查询预览 · 30 秒静默自动刷新(对齐危急值工作台模式) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 技师工作台私有样式一次性注入(无构建架构, 避免改动公共 css 与其他会话冲突) */
  (function ensureRisWorklistStyles() {
    if (document.getElementById('ris-worklist-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-worklist-style';
    st.textContent = [
      /* 主区: 左任务板 1/3 + 右详情 2/3, 撑满剩余视口高 */
      '.rw-wrap { flex: 1; min-height: 0; display: flex; gap: 12px; }',
      '.rw-left { width: 34%; min-width: 300px; display: flex; flex-direction: column; min-height: 0; }',
      '.rw-right { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }',
      '.rw-panel { background: var(--yb-surface); border: 1px solid var(--yb-border); border-radius: var(--yb-r-md); display: flex; flex-direction: column; min-height: 0; overflow: hidden; }',
      '.rw-panel-head { padding: 10px 12px 8px; border-bottom: 1px solid var(--yb-border-light); background: var(--yb-surface-2); flex: none; }',
      '.rw-panel-body { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 12px; }',
      /* 任务卡: 左状态脊条 + 急诊红脊; hover 上浮, active 品牌浅底+内描边 */
      '.rw-task { position: relative; border: 1px solid var(--yb-border); border-left: 3px solid var(--yb-border-strong); border-radius: var(--yb-r-sm); padding: 8px 10px 8px 12px; margin-bottom: 8px; cursor: pointer; transition: border-color var(--yb-dur) var(--yb-ease), box-shadow var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); background: var(--yb-surface); }',
      '.rw-task:hover { border-color: var(--yb-link); transform: translateY(-1px); box-shadow: var(--yb-sh-2); }',
      '.rw-task.active { border-color: var(--yb-link); background: var(--yb-brand-subtle); box-shadow: 0 0 0 1px var(--yb-link) inset; }',
      '.rw-task.urgent { border-left-color: var(--yb-fill-danger); }',
      '.rw-task.s0 { border-left-color: var(--yb-ink-4); }',
      '.rw-task.s1 { border-left-color: var(--yb-fill-info); }',
      '.rw-task.s2 { border-left-color: var(--yb-fill-success); }',
      '.rw-task.s3 { border-left-color: var(--yb-fill-danger); }',
      '.rw-task .no { font-weight: 600; color: var(--yb-ink-1); font-size: var(--yb-fs-base); display: flex; align-items: center; flex-wrap: wrap; gap: 4px; font-variant-numeric: tabular-nums; }',
      '.rw-task .sub { color: var(--yb-ink-2); font-size: var(--yb-fs-sm); margin-top: 3px; display: flex; justify-content: space-between; gap: 8px; }',
      '.rw-task .meta { color: var(--yb-ink-3); font-size: var(--yb-fs-cap); margin-top: 3px; display: flex; gap: 10px; flex-wrap: wrap; font-variant-numeric: tabular-nums; }',
      '.rw-urgent-badge { display: inline-block; background: var(--yb-fill-danger); color: #fff; font-size: var(--yb-fs-cap); font-weight: 700; border-radius: var(--yb-r-sm); padding: 0 4px; line-height: 16px; }',
      '.rw-unpaid { color: var(--yb-warning); font-weight: 700; }',
      /* 状态点(卡片头部小圆点) */
      '.rw-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 5px; vertical-align: middle; border: 1px solid rgba(0,0,0,.12); }',
      '.rw-dot.s0 { background: var(--yb-ink-4); }',
      '.rw-dot.s1 { background: var(--yb-fill-info); }',
      '.rw-dot.s2 { background: var(--yb-fill-success); }',
      '.rw-dot.s3 { background: var(--yb-fill-danger); }',
      /* 自动刷新呼吸指示 */
      '@keyframes rwPulse { 0%,100% { opacity: 1; transform: scale(1); } 50% { opacity: .35; transform: scale(.72); } }',
      '.rw-live-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--yb-fill-success); margin-right: 5px; animation: rwPulse 2.4s var(--yb-ease) infinite; vertical-align: middle; }',
      '.rw-live { color: var(--yb-ink-3); font-size: var(--yb-fs-cap); font-variant-numeric: tabular-nums; }',
      /* 患者横幅 */
      '.rw-banner { background: linear-gradient(135deg, #f0f7ff 0%, #fafcff 100%); border: 1px solid var(--yb-brand-border); border-radius: var(--yb-r-md); padding: 10px 14px; margin-bottom: 12px; flex: none; }',
      '.rw-banner .name { font-size: 17px; font-weight: 700; color: var(--yb-ink-1); margin-right: 10px; font-variant-numeric: tabular-nums; }',
      '.rw-banner .attr { color: var(--yb-ink-2); font-size: var(--yb-fs-sm); margin-right: 10px; font-variant-numeric: tabular-nums; }',
      /* 信息网格: label 上 value 下两列卡 */
      '.rw-kv-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px 12px; }',
      '.rw-kv { min-width: 0; }',
      '.rw-kv .k { color: var(--yb-ink-3); font-size: var(--yb-fs-cap); margin-bottom: 2px; }',
      '.rw-kv .v { color: var(--yb-ink-1); font-size: var(--yb-fs-base); font-weight: 600; word-break: break-all; font-variant-numeric: tabular-nums; }',
      '.rw-sec-title { font-size: var(--yb-fs-sm); font-weight: 700; color: var(--yb-ink-2); margin: 14px 0 8px; padding-left: 8px; border-left: 3px solid var(--yb-brand); line-height: 14px; flex: none; }',
      /* 操作栏 */
      '.rw-ops { display: flex; gap: 10px; align-items: center; padding: 12px 0 2px; border-top: 1px dashed var(--yb-border); margin-top: 14px; flex-wrap: wrap; flex: none; }',
      '.rw-hint { color: var(--yb-ink-3); font-size: var(--yb-fs-sm); }',
      '.rw-guide { text-align: center; padding: 40px 16px; color: var(--yb-ink-2); font-size: var(--yb-fs-md); }',
      '.rw-guide .t { font-size: 15px; font-weight: 700; color: var(--yb-ink-1); margin-bottom: 6px; }',
      '.rw-empty-dot { display: inline-block; width: 44px; height: 44px; line-height: 44px; border-radius: 50%; background: var(--yb-brand-subtle); color: var(--yb-brand); font-size: 20px; font-weight: 700; margin-bottom: 12px; }',
      /* 完成检查摘要条 */
      '.rw-done-strip { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 4px; flex: none; }',
      '.rw-done-strip .el-tag { font-variant-numeric: tabular-nums; }',
      '@media (max-width: 1100px) { .rw-left { width: 42%; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 本地受控枚举(两源行状态归一: 0待检/未开始, 1检查中, 2已完成, 3中断|取消) ===== */
  var TASK_STATUS = [
    { v: 0, l: '待检', t: 'info' },
    { v: 1, l: '检查中', t: 'primary' },
    { v: 2, l: '已完成', t: 'success' },
    { v: 3, l: '已中断', t: 'danger' }
  ];
  var WL_STATUS = { 0: '待检查', 1: '检查中', 2: '已完成', 3: '已取消' };

  function findBy(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i]; } }
    return null;
  }
  function statusLabel(v) { var s = findBy(TASK_STATUS, v); return s ? s.l : '-'; }
  function statusTag(v) { var s = findBy(TASK_STATUS, v); return s ? s.t : 'info'; }
  /* LocalDateTime 序列化为 ISO(含T), 展示转空格并截到秒 */
  function fmtTime(v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; }
  /* yyyyMMdd 出生年月估算年龄(展示用, 权威年龄以档案为准) */
  function ageFromBirth(v) {
    if (!v) { return null; }
    var s = String(v).replace(/[^0-9]/g, '');
    if (s.length !== 8) { return null; }
    var y = parseInt(s.substring(0, 4), 10);
    var m = parseInt(s.substring(4, 6), 10);
    var d = parseInt(s.substring(6, 8), 10);
    if (!y || !m || !d) { return null; }
    var now = new Date();
    var age = now.getFullYear() - y;
    var mm = now.getMonth() + 1 - m;
    if (mm < 0 || (mm === 0 && now.getDate() < d)) { age--; }
    return age >= 0 && age < 150 ? age : null;
  }
  /* DICOM 性别口径(M/F/O)转中文 */
  function genderLabel(v) {
    if (v === 'M' || v === '1' || v === '男') { return '男'; }
    if (v === 'F' || v === '2' || v === '女') { return '女'; }
    return v ? String(v) : '';
  }
  /* yyyyMMdd(或含分隔)日期展示 */
  function fmtDay(v) {
    if (!v) { return '-'; }
    var s = String(v);
    if (s.length === 8 && /^\d+$/.test(s)) { return s.substring(0, 4) + '-' + s.substring(4, 6) + '-' + s.substring(6, 8); }
    return String(v).replace('T', ' ').substring(0, 10);
  }

  HIS.views['RisWorklist'] = {
    name: 'RisWorklist',
    data: function () {
      return {
        /* 设备筛选(今日任务接口 deviceId 必填; 选项聚合自排程周模板, 支持手输设备ID兜底) */
        deviceId: null,
        deviceOptions: [],
        deviceLoading: false,
        lastAeTitle: '',
        /* 任务列表与状态页签 */
        tasks: [],
        tab: 'all',
        loading: false,
        lastRefresh: '',
        current: null,
        acting: false,
        /* 完成检查弹窗(RisExecutionDTO: 图像/序列/剂量/造影剂/StudyUID) */
        doneVisible: false,
        doneForm: { imageCount: null, seriesCount: null, studyUid: '', doseDlp: null, doseCtdi: null, doseDap: null, contrastAgent: '', contrastDose: '', notes: '' },
        /* DICOM Worklist 查询预览(供 MWL SCP 联调核对) */
        wlVisible: false,
        wlAeTitle: '',
        wlDate: '',
        wlList: [],
        wlLoading: false
      };
    },
    computed: {
      statusMeta: function () { return TASK_STATUS; },
      /* 状态页签过滤: 全部 / 待检(0) / 检查中(1) / 已完成(2); 中断(3)归入全部 */
      filteredTasks: function () {
        var vm = this;
        if (vm.tab === 'all') { return vm.tasks; }
        var want = parseInt(vm.tab, 10);
        return (vm.tasks || []).filter(function (t) { return vm.effStatus(t) === want; });
      },
      tabCounts: function () {
        var vm = this;
        var c = { all: (vm.tasks || []).length, 0: 0, 1: 0, 2: 0 };
        (vm.tasks || []).forEach(function (t) {
          var s = vm.effStatus(t);
          if (c[s] != null) { c[s]++; }
        });
        return c;
      },
      /* 当前任务归一状态(操作按钮分档) */
      curStatus: function () { return this.current ? this.effStatus(this.current) : null; },
      curAge: function () {
        if (!this.current) { return null; }
        return this.current.age != null ? this.current.age : ageFromBirth(this.current.birthDate);
      },
      isUrgentCur: function () { return !!this.current && this.current.isUrgent === 1; }
    },
    created: function () {
      this.wlDate = this.todayStr();
      this.loadDevices();
      this.load();
    },
    mounted: function () {
      var vm = this;
      if (vm.timer) { clearInterval(vm.timer); }
      vm.timer = setInterval(function () { vm.load(true); }, 30000);
    },
    beforeUnmount: function () {
      if (this.timer) { clearInterval(this.timer); this.timer = null; }
    },
    methods: {
      statusLabel: statusLabel,
      statusTag: statusTag,
      fmtTime: fmtTime,
      fmtDay: fmtDay,
      genderLabel: genderLabel,
      todayStr: function () {
        var d = new Date();
        var p = function (n) { return n < 10 ? '0' + n : '' + n; };
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
      },
      /* 两源行状态归一: 有执行记录以执行状态为准(0未开始/1检查中/2已完成/3中断), 否则取 Worklist 状态 */
      effStatus: function (t) {
        if (!t) { return 0; }
        if (t.executionId != null) { return t.executionStatus == null ? 0 : t.executionStatus; }
        return t.status == null ? 0 : t.status;
      },
      /* 设备下拉选项: 聚合排程周模板中的设备ID去重(后端暂无设备清单端点, allow-create 兜底手输) */
      loadDevices: function () {
        var vm = this;
        vm.deviceLoading = true;
        HIS.get('/api/ris/schedule/template').then(function (d) {
          var seen = {};
          var opts = [];
          ((d && d.records) || d || []).forEach(function (r) {
            var id = r && (r.deviceId != null ? r.deviceId : r.device_id);
            if (id != null && !seen[id]) {
              seen[id] = true;
              opts.push({ value: id, label: '设备 #' + id });
            }
          });
          vm.deviceOptions = opts;
          if (!vm.deviceId && opts.length) { vm.deviceId = opts[0].value; vm.load(); }
        }).catch(function () { /* 设备选项拉取失败不阻断, 允许手动输入设备ID */ })
          .finally(function () { vm.deviceLoading = false; });
      },
      /* 今日任务: silent=true 为定时静默刷新(不显示 loading, 不弹错) */
      load: function (silent) {
        var vm = this;
        if (!vm.deviceId) { return; }
        if (silent !== true) { vm.loading = true; }
        HIS.get('/api/ris/workstation/today-tasks?deviceId=' + vm.deviceId).then(function (d) {
          var rows = (d && d.records) || d || [];
          /* 静默刷新保持当前选中卡(按 requestId 匹配回填最新行) */
          if (vm.current && vm.current.requestId != null) {
            var cur = vm.current;
            for (var i = 0; i < rows.length; i++) {
              if (rows[i] && rows[i].requestId === cur.requestId) { vm.current = rows[i]; break; }
            }
          }
          vm.tasks = rows;
          var n = new Date();
          var p = function (x) { return x < 10 ? '0' + x : '' + x; };
          vm.lastRefresh = p(n.getHours()) + ':' + p(n.getMinutes()) + ':' + p(n.getSeconds());
          /* 记住最近出现的 AE Title 供 Worklist 查询预填 */
          for (var j = 0; j < rows.length; j++) {
            if (rows[j] && rows[j].deviceAeTitle) { vm.lastAeTitle = rows[j].deviceAeTitle; break; }
          }
        }).catch(function (e) { if (silent !== true) { HIS.notifyError(e); } })
          .finally(function () { if (silent !== true) { vm.loading = false; } });
      },
      onDevice: function () {
        this.current = null;
        this.load();
      },
      onTab: function () { /* 纯前端过滤, 无需请求 */ },
      selectTask: function (t) { this.current = t; },
      /* 到检签到: 幂等(已有未完成执行记录直接返回), 申请单 -> 已登记 */
      checkIn: function () {
        var vm = this;
        if (!vm.current || vm.current.requestId == null) { return; }
        vm.acting = true;
        HIS.post('/api/ris/workstation/check-in/' + vm.current.requestId).then(function () {
          HIS.notifySuccess('到检签到成功, 可开始检查');
          vm.load(true);
        }).catch(HIS.notifyError).finally(function () { vm.acting = false; });
      },
      /* 开始检查: 执行 0/3 -> 1检查中(设备取当前筛选设备, 技师缺省当前登录职工) */
      startExam: function () {
        var vm = this;
        if (!vm.current || vm.current.executionId == null) { return; }
        vm.acting = true;
        HIS.post('/api/ris/workstation/start-exam/' + vm.current.executionId, { deviceId: vm.deviceId })
          .then(function () {
            HIS.notifySuccess('检查已开始');
            vm.load(true);
          }).catch(HIS.notifyError).finally(function () { vm.acting = false; });
      },
      /* 完成检查弹窗 */
      openDone: function () {
        var vm = this;
        if (!vm.current) { return; }
        vm.doneForm = {
          imageCount: vm.current.imageCount != null ? vm.current.imageCount : null,
          seriesCount: vm.current.seriesCount != null ? vm.current.seriesCount : null,
          studyUid: vm.current.studyUid || '',
          doseDlp: null, doseCtdi: null, doseDap: null,
          contrastAgent: '', contrastDose: '', notes: ''
        };
        vm.doneVisible = true;
      },
      /* 完成检查: 执行 -> 2已完成, 申请单 -> 4, Worklist -> 2 + StudyUID 回填 */
      completeExam: function () {
        var vm = this;
        if (!vm.current || vm.current.executionId == null) { return; }
        if (vm.doneForm.imageCount == null || vm.doneForm.imageCount < 0) {
          ElementPlus.ElMessage.warning('请填写图像数量');
          return;
        }
        vm.acting = true;
        HIS.post('/api/ris/workstation/complete-exam/' + vm.current.executionId, {
          imageCount: vm.doneForm.imageCount,
          seriesCount: vm.doneForm.seriesCount,
          studyUid: vm.doneForm.studyUid,
          doseDlp: vm.doneForm.doseDlp,
          doseCtdi: vm.doneForm.doseCtdi,
          doseDap: vm.doneForm.doseDap,
          contrastAgent: vm.doneForm.contrastAgent,
          contrastDose: vm.doneForm.contrastDose,
          notes: vm.doneForm.notes
        }).then(function () {
          vm.doneVisible = false;
          HIS.notifySuccess('检查已完成' + (vm.doneForm.studyUid ? ', StudyUID 已回填' : ' (未登记 StudyUID, 不影响完成)'));
          vm.load(true);
        }).catch(HIS.notifyError).finally(function () { vm.acting = false; });
      },
      /* DICOM Worklist 查询预览(MWL SCP 同口径: AE Title + 日期, 仅待检查条目) */
      openWl: function () {
        this.wlAeTitle = this.lastAeTitle || '';
        if (!this.wlDate) { this.wlDate = this.todayStr(); }
        this.wlList = [];
        this.wlVisible = true;
      },
      queryWl: function () {
        var vm = this;
        if (!vm.wlAeTitle) { ElementPlus.ElMessage.warning('请输入设备 AE Title'); return; }
        vm.wlLoading = true;
        var q = '/api/ris/workstation/worklist?aeTitle=' + encodeURIComponent(vm.wlAeTitle) + '&date=' + encodeURIComponent(vm.wlDate || '');
        HIS.get(q).then(function (d) {
          vm.wlList = (d && d.records) || d || [];
          if (!vm.wlList.length) { ElementPlus.ElMessage.info('该设备当日无待检查 Worklist 条目'); }
        }).catch(HIS.notifyError).finally(function () { vm.wlLoading = false; });
      },
      wlStatusLabel: function (s) { return WL_STATUS[s] != null ? WL_STATUS[s] : (s == null ? '-' : s); }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">技师检查工作台 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(Worklist 与执行记录合并任务板 · 到检签到 → 开始检查 → 完成登记 · 30秒自动刷新)</span></div>',
      '  <div class="toolbar">',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">检查设备</span>',
      '    <el-select v-model="deviceId" filterable allow-create default-first-option :loading="deviceLoading" size="small" style="width:220px" placeholder="选择或输入设备ID" @change="onDevice">',
      '      <el-option v-for="o in deviceOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-button size="small" @click="load()">刷新</el-button>',
      '    <el-button size="small" @click="openWl">Worklist 查询</el-button>',
      '    <span class="rw-live" style="margin-left:2px;"><span class="rw-live-dot"></span>30s 自动刷新<span v-if="lastRefresh"> · 最后 {{ lastRefresh }}</span></span>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">今日任务 {{ tabCounts.all }} 条</span>',
      '  </div>',
      '  <div class="rw-wrap">',
      '    <div class="rw-left">',
      '      <div class="rw-panel" style="flex:1;">',
      '        <div class="rw-panel-head">',
      '          <el-tabs v-model="tab" @tab-change="onTab" stretch>',
      '            <el-tab-pane :label="\'全部(\' + tabCounts.all + \')\'" name="all"></el-tab-pane>',
      '            <el-tab-pane :label="\'待检(\' + tabCounts[0] + \')\'" name="0"></el-tab-pane>',
      '            <el-tab-pane :label="\'检查中(\' + tabCounts[1] + \')\'" name="1"></el-tab-pane>',
      '            <el-tab-pane :label="\'已完成(\' + tabCounts[2] + \')\'" name="2"></el-tab-pane>',
      '          </el-tabs>',
      '        </div>',
      '        <div class="rw-panel-body" v-loading="loading">',
      '          <div v-if="!deviceId" class="rw-guide">',
      '            <div class="rw-empty-dot">器</div>',
      '            <div class="t">请先选择检查设备</div>',
      '            <div>今日任务按设备聚合(Worklist + 执行记录), 支持直接输入设备ID</div>',
      '          </div>',
      '          <el-empty v-else-if="!filteredTasks.length" description="暂无检查任务" :image-size="60"></el-empty>',
      '          <div v-for="t in filteredTasks" :key="(t.worklistId != null ? \'wl\' : \'ex\') + (t.worklistId != null ? t.worklistId : t.requestId)" class="rw-task" :class="{ active: current && current.requestId != null && current.requestId === t.requestId, urgent: t.isUrgent === 1, s0: effStatus(t)===0, s1: effStatus(t)===1, s2: effStatus(t)===2, s3: effStatus(t)===3 }" @click="selectTask(t)">',
      '            <div class="no">',
      '              <span class="rw-dot" :class="\'s\' + effStatus(t)"></span>{{ t.patientName || \'-\' }}',
      '              <el-tag size="small" :type="statusTag(effStatus(t))" effect="light">{{ statusLabel(effStatus(t)) }}</el-tag>',
      '              <el-tag v-if="t.isUrgent === 1" size="small" type="danger" effect="dark">急</el-tag>',
      '              <el-tag v-if="t.modality" size="small" type="info" effect="plain">{{ t.modality }}</el-tag>',
      '              <span v-if="t.paidFlag != null && t.paidFlag !== 1" class="rw-unpaid">未缴费</span>',
      '            </div>',
      '            <div class="sub"><span>{{ t.procedureDesc || t.examItemName || t.bodyPart || \'检查项目\' }}</span><span>{{ t.accessionNo || \'\' }}</span></div>',
      '            <div class="meta">',
      '              <span>预约 {{ fmtTime(t.scheduledAt) }}</span>',
      '              <span v-if="t.checkInTime">到检 {{ fmtTime(t.checkInTime) }}</span>',
      '              <span v-if="t.examStartTime">开始 {{ fmtTime(t.examStartTime) }}</span>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '    <div class="rw-right">',
      '      <div class="rw-panel" style="flex:1;">',
      '        <div class="rw-panel-body" style="overflow-y:auto;">',
      '          <el-empty v-if="!current" description="请选择检查任务" :image-size="80"></el-empty>',
      '          <template v-else>',
      '            <div class="rw-banner">',
      '              <span class="name">{{ current.patientName || \'-\' }}</span>',
      '              <span class="attr" v-if="genderLabel(current.gender)">{{ genderLabel(current.gender) }}</span>',
      '              <span class="attr" v-if="curAge != null">{{ curAge }}岁</span>',
      '              <span class="attr" v-if="current.patientIdNo">病历号 {{ current.patientIdNo }}</span>',
      '              <el-tag size="small" :type="statusTag(curStatus)">{{ statusLabel(curStatus) }}</el-tag>',
      '              <el-tag v-if="isUrgentCur" size="small" type="danger" effect="dark">急诊</el-tag>',
      '              <el-tag v-if="current.paidFlag != null && current.paidFlag !== 1" size="small" type="warning">未缴费</el-tag>',
      '            </div>',
      '            <div class="rw-sec-title">检查信息</div>',
      '            <div class="rw-kv-grid">',
      '              <div class="rw-kv"><div class="k">申请单号</div><div class="v">{{ current.requestNo || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">检查号(Accession)</div><div class="v">{{ current.accessionNo || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">检查项目</div><div class="v">{{ current.procedureDesc || current.examItemName || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">检查部位</div><div class="v">{{ current.bodyPart || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">设备类型(Modality)</div><div class="v">{{ current.modality || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">预约时间</div><div class="v">{{ fmtTime(current.scheduledAt) }}</div></div>',
      '              <div class="rw-kv"><div class="k">设备 AE Title</div><div class="v">{{ current.deviceAeTitle || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">申请科室</div><div class="v">{{ current.requestingDept || \'-\' }}</div></div>',
      '              <div class="rw-kv"><div class="k">申请医生</div><div class="v">{{ current.referringPhysician || \'-\' }}</div></div>',
      '            </div>',
      '            <template v-if="curStatus === 2">',
      '              <div class="rw-sec-title">检查结果摘要</div>',
      '              <div class="rw-done-strip">',
      '                <el-tag type="success">完成时间 {{ fmtTime(current.examEndTime) }}</el-tag>',
      '                <el-tag type="info">图像 {{ current.imageCount != null ? current.imageCount : \'-\' }} 张</el-tag>',
      '                <el-tag type="info">序列 {{ current.seriesCount != null ? current.seriesCount : \'-\' }}</el-tag>',
      '                <el-tag v-if="current.technicianName">技师 {{ current.technicianName }}</el-tag>',
      '                <el-tag v-if="current.studyUid" type="warning">StudyUID {{ current.studyUid }}</el-tag>',
      '              </div>',
      '            </template>',
      '            <div class="rw-ops">',
      '              <el-button v-if="curStatus === 0 && current.executionId == null" type="primary" :loading="acting" @click="checkIn">到检签到</el-button>',
      '              <el-button v-if="curStatus === 0 && current.executionId != null" type="primary" :loading="acting" @click="startExam">开始检查</el-button>',
      '              <el-button v-if="curStatus === 3 && current.executionId != null" type="warning" :loading="acting" @click="startExam">重新开始检查</el-button>',
      '              <el-button v-if="curStatus === 1" type="success" @click="openDone">完成检查</el-button>',
      '              <span v-if="curStatus === 2" class="rw-hint">检查已完成, 待报告书写</span>',
      '              <span v-if="curStatus === 0 && current.executionId != null" class="rw-hint">已到检 {{ fmtTime(current.checkInTime) }}, 可开始检查</span>',
      '            </div>',
      '          </template>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      '  <el-dialog v-model="doneVisible" title="完成检查登记" width="560px" :close-on-click-modal="false">',
      '    <el-form label-width="128px" size="default">',
      '      <div style="margin:0 0 12px 4px;color:var(--yb-ink-2);font-size:12px;">{{ current ? (current.patientName || \'-\') + \' · \' + (current.procedureDesc || current.examItemName || \'-\') : \'\' }}</div>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="图像数量"><el-input-number v-model="doneForm.imageCount" :min="0" :max="9999" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="序列数量"><el-input-number v-model="doneForm.seriesCount" :min="0" :max="999" style="width:100%"></el-input-number></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="Study UID"><el-input v-model="doneForm.studyUid" placeholder="DICOM Study Instance UID(回填 Worklist/PACS)"></el-input></el-form-item>',
      '      <el-divider content-position="left">剂量登记(选填)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="DLP" label-width="60px"><el-input-number v-model="doneForm.doseDlp" :min="0" :precision="2" :controls="false" placeholder="mGy·cm" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="CTDIvol" label-width="64px"><el-input-number v-model="doneForm.doseCtdi" :min="0" :precision="2" :controls="false" placeholder="mGy" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="DAP" label-width="52px"><el-input-number v-model="doneForm.doseDap" :min="0" :precision="2" :controls="false" placeholder="Gy·cm²" style="width:100%"></el-input-number></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">造影剂(选填)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="造影剂名称"><el-input v-model="doneForm.contrastAgent" placeholder="如: 碘海醇 350"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="造影剂剂量"><el-input v-model="doneForm.contrastDose" placeholder="如: 60ml"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="检查备注"><el-input type="textarea" v-model="doneForm.notes" :rows="2" placeholder="体位/配合情况/复查建议等"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="doneVisible = false">取消</el-button>',
      '      <el-button type="success" :loading="acting" @click="completeExam">完成检查</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="wlVisible" title="DICOM Worklist 查询(MWL SCP 同口径)" width="720px">',
      '    <div class="toolbar" style="margin-bottom:10px;">',
      '      <el-input v-model="wlAeTitle" size="small" style="width:200px" placeholder="设备 AE Title"></el-input>',
      '      <el-date-picker v-model="wlDate" type="date" size="small" value-format="YYYY-MM-DD" style="width:150px"></el-date-picker>',
      '      <el-button size="small" type="primary" :loading="wlLoading" @click="queryWl">查询</el-button>',
      '      <span style="color:var(--yb-ink-3);font-size:12px;">仅返回状态=待检查 的条目, 与影像设备 Worklist 拉取口径一致</span>',
      '    </div>',
      '    <el-table :data="wlList" size="small" border height="320">',
      '      <el-table-column prop="accessionNo" label="Accession" min-width="150"></el-table-column>',
      '      <el-table-column prop="patientName" label="患者" min-width="90"></el-table-column>',
      '      <el-table-column prop="modality" label="Modality" width="80"></el-table-column>',
      '      <el-table-column prop="bodyPart" label="部位" min-width="90"></el-table-column>',
      '      <el-table-column label="预约" width="150"><template #default="s">{{ (s.row.scheduledDate || \'\') + \' \' + (s.row.scheduledTime || \'\') }}</template></el-table-column>',
      '      <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" type="info">{{ wlStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    </el-table>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
