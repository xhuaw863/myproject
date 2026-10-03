/* 住院医生站 - 临床路径面板: 未入径(模板选择入径) / 已入径(日历时间轴: 逐日任务执行, 跳过/变异登记) / 变异记录
 * 接口: GET /api/his/pathway/instance/current/{visitId} (无活跃路径返回 null)
 *       GET /api/his/pathway/instance/{id}/detail -> {instance, days:[{dayNo, nodes:[{nodeId,nodeName,tasks:[...]}]}], varianceCount}
 *       GET /api/his/pathway/template/{id} (取适用诊断展示) | /template/list?status=1 (入径模板候选)
 *       POST /api/his/pathway/instance/start | /{id}/exec-day (执行当天必做任务转医嘱)
 *       PUT /{id}/advance | /{id}/pause | /{id}/resume | /{id}/complete | /{id}/exit?reason=
 *       PUT /exec/{execId}/skip | /exec/{execId}/variance?reason=  (reason 为 query 参数, 非请求体)
 * 注册: HIS.components.InpPathwayPanel (须在 inp-doctor.js 之前加载); 样式 id: inp-pathway-css, 前缀 ipw-* */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  const EXEC_STATUS = { 1: '待执行', 2: '已执行', 3: '跳过', 4: '变异' };
  const EXEC_STATUS_COLOR = { 1: '#909399', 2: '#67c23a', 3: '#e6a23c', 4: '#f56c6c' };
  const EXEC_STATUS_ICON = { 1: '○', 2: '✓', 3: '—', 4: '!' };
  const INSTANCE_STATUS = { 1: '进行中', 2: '已完成', 3: '已退出', 4: '暂停' };
  const INSTANCE_STATUS_TYPE = { 1: 'success', 2: 'info', 3: 'danger', 4: 'warning' };

  function dateText(value) {
    if (!value) { return '-'; }
    return String(value).replace('T', ' ').substring(0, 10);
  }

  /* ============================================================
   * 样式(注入一次): 复用 theme.css 的 --yb-* 令牌; iw-* 通用类由 inp-doctor.js 提供
   * ============================================================ */
  const CSS = `
.ipw-wrap { height:100%; display:flex; flex-direction:column; overflow:hidden; background:var(--yb-surface); }
.ipw-empty { flex:1; display:flex; flex-direction:column; gap:10px; align-items:center; justify-content:center; color:var(--yb-ink-3); }
.ipw-empty .big { font-size:var(--yb-fs-lg); color:var(--yb-ink-2); font-weight:600; }
.ipw-empty .sub { font-size:var(--yb-fs-sm); color:var(--yb-ink-4); }

/* ===== 顶部信息栏 ===== */
.ipw-banner { flex:none; display:flex; align-items:center; gap:12px 24px; flex-wrap:wrap; padding:12px 16px; background:linear-gradient(180deg, var(--yb-surface), var(--yb-surface-2)); border-bottom:1px solid var(--yb-border); }
.ipw-banner .main { flex:1; min-width:220px; }
.ipw-banner .r1 { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.ipw-banner .r1 .nm { font-size:var(--yb-fs-lg); font-weight:700; color:var(--yb-ink-1); }
.ipw-banner .r2 { margin-top:5px; display:flex; flex-wrap:wrap; gap:5px 16px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.ipw-banner .r2 b { color:var(--yb-ink-2); font-weight:600; }
.ipw-progress { flex:none; display:flex; align-items:center; gap:10px; }
.ipw-progress .pt { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); white-space:nowrap; }
.ipw-progress .pt b { color:var(--yb-brand); font-size:var(--yb-fs-base); }
.ipw-actions { flex:none; margin-left:auto; }

/* ===== 横向日历时间轴 ===== */
.ipw-flow { flex:1; min-height:0; border-bottom:1px solid var(--yb-border); }
.ipw-flow .el-scrollbar__view { height:100%; }
.ipw-flow-inner { display:flex; min-width:max-content; min-height:100%; }
.ipw-day { width:180px; flex:none; display:flex; flex-direction:column; border-right:1px solid var(--yb-divider); }
.ipw-day.is-today { background:var(--yb-info-light); }
.ipw-day-head { position:sticky; top:0; z-index:2; padding:8px 10px; background:var(--yb-surface-2); border-bottom:1px solid var(--yb-divider); }
.ipw-day.is-today .ipw-day-head { background:var(--yb-brand-subtle); }
.ipw-day-head b { font-size:var(--yb-fs-base); color:var(--yb-ink-1); }
.ipw-day.is-today .ipw-day-head b { color:var(--yb-brand); }
.ipw-day-head span { margin-left:6px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.ipw-day-body { flex:1; padding:8px; display:flex; flex-direction:column; gap:6px; }
.ipw-node-name { padding:0 2px; font-size:var(--yb-fs-cap); color:var(--yb-ink-4); }
.ipw-task { padding:6px 8px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); background:var(--yb-surface); cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease); }
.ipw-task:hover { box-shadow:var(--yb-sh-1); }
.ipw-task .row { display:flex; align-items:flex-start; gap:6px; }
.ipw-task .ic { flex:none; width:14px; text-align:center; font-weight:700; line-height:1.45; }
.ipw-task .ct { flex:1; min-width:0; font-size:var(--yb-fs-sm); color:var(--yb-ink-2); line-height:1.45; word-break:break-all; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.ipw-task .mk { flex:none; padding:0 3px; border-radius:3px; font-size:var(--yb-fs-cap); line-height:15px; color:var(--yb-danger); border:1px solid var(--yb-danger-border); background:var(--yb-danger-bg); }
.ipw-task.is-done { border-color:var(--yb-success-border); background:var(--yb-success-bg); }
.ipw-task.is-done .ct { color:var(--yb-success); }
.ipw-task.is-skip { border-color:var(--yb-warning-border); background:var(--yb-warning-bg); }
.ipw-task.is-skip .ct { color:var(--yb-warning); text-decoration:line-through; }
.ipw-task.is-var { border-color:var(--yb-danger-border); background:var(--yb-danger-bg); }
.ipw-task.is-var .ct { color:var(--yb-danger); }
.ipw-task.is-open { box-shadow:var(--yb-sh-2); }
.ipw-task .ops { margin-top:6px; padding-top:5px; border-top:1px dashed var(--yb-divider); display:flex; justify-content:flex-end; gap:2px; }
.ipw-flow-placeholder { padding:22px 0; text-align:center; color:var(--yb-ink-4); font-size:var(--yb-fs-sm); }

/* ===== 底部变异记录 ===== */
.ipw-variance { flex:none; max-height:216px; overflow:auto; padding:10px 16px 14px; }
.ipw-variance .hd { display:flex; align-items:center; gap:6px; font-size:var(--yb-fs-md); font-weight:600; color:var(--yb-ink-1); margin-bottom:8px; }

/* ===== 路径选择对话框 ===== */
.ipw-pick-list { max-height:380px; overflow:auto; }
.ipw-pick-item { padding:10px 12px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); margin-bottom:8px; cursor:pointer; transition:border-color var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease); }
.ipw-pick-item:hover { border-color:var(--yb-brand-border); background:var(--yb-brand-subtle); }
.ipw-pick-item .r1 { display:flex; align-items:center; gap:8px; }
.ipw-pick-item .r1 b { color:var(--yb-ink-1); font-size:var(--yb-fs-md); }
.ipw-pick-item .ver { padding:0 6px; height:18px; line-height:18px; border-radius:var(--yb-r-pill); background:var(--yb-surface-3); color:var(--yb-ink-3); font-size:var(--yb-fs-cap); }
.ipw-pick-item .r2 { margin-top:5px; display:flex; flex-wrap:wrap; gap:5px 16px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
`;

  function ensureStyle() {
    if (document.getElementById('inp-pathway-css')) { return; }
    const style = document.createElement('style');
    style.id = 'inp-pathway-css';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  /* 模板选择对话框搜索防抖(模块级, 面板单实例) */
  let pickTimer = null;

  const InpPathwayPanel = {
    name: 'InpPathwayPanel',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return {
        loading: false,
        current: null,
        detail: null,
        tplDetail: null,
        openTaskId: null,
        executing: false,
        acting: false,
        /* 选择路径对话框 */
        pickDialog: false,
        pickKeyword: '',
        pickLoading: false,
        pickTemplates: [],
        starting: false,
        /* 退出对话框(原因分类字典 pathway_exit_reason + 自由文本备注) */
        exitDialog: false,
        exitReason: '',
        exitType: '',
        exitTypeOptions: [],
        exitLoading: false,
        /* 变异对话框(原因分类字典 pathway_var_reason + 自由文本备注) */
        varianceDialog: false,
        varianceReason: '',
        varianceType: '',
        varianceTypeOptions: [],
        varianceLoading: false,
        varianceTarget: null
      };
    },
    computed: {
      instance() { return this.current && this.current.instance ? this.current.instance : null; },
      statusText() { return this.instance ? (INSTANCE_STATUS[this.instance.status] || '-') : ''; },
      statusType() { return this.instance ? (INSTANCE_STATUS_TYPE[this.instance.status] || '') : ''; },
      canExec() { return !!this.instance && Number(this.instance.status) === 1; },
      canResume() { return !!this.instance && Number(this.instance.status) === 4; },
      currentDay() { return this.instance ? Number(this.instance.currentDay) || 1 : 0; },
      totalDays() { return this.current && this.current.totalDays != null ? Number(this.current.totalDays) : null; },
      dayPercent() {
        const total = this.totalDays;
        if (!total || total <= 0) { return 0; }
        return Math.min(100, Math.round(this.currentDay * 100 / total));
      },
      diseaseName() { return this.tplDetail ? (this.tplDetail.diseaseName || '') : ''; },
      days() { return (this.detail && this.detail.days) || []; },
      varianceRows() {
        const rows = [];
        (this.days || []).forEach(d => {
          (d.nodes || []).forEach(n => {
            (n.tasks || []).forEach(t => {
              if (Number(t.execStatus) === 4) {
                rows.push({
                  dayNo: d.dayNo,
                  orderContent: t.orderContent || '(任务已删除)',
                  varianceTypeName: t.varianceTypeName || '',
                  varianceReason: t.varianceReason || '-',
                  execDate: t.execDate || ''
                });
              }
            });
          });
        });
        return rows;
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler(v) {
          this.reset();
          if (v) { this.load(); }
        }
      }
    },
    methods: {
      dateText,
      statusOf(v) { return EXEC_STATUS[v] || '-'; },
      statusIcon(v) { return EXEC_STATUS_ICON[v] || '○'; },
      statusColor(v) { return EXEC_STATUS_COLOR[v] || '#909399'; },
      instanceStatusText(v) { return INSTANCE_STATUS[v] || '-'; },
      instanceStatusType(v) { return INSTANCE_STATUS_TYPE[v] || ''; },
      taskClass(t) {
        const s = Number(t.execStatus);
        if (s === 2) { return 'is-done'; }
        if (s === 3) { return 'is-skip'; }
        if (s === 4) { return 'is-var'; }
        return 'is-todo';
      },
      /* 第X天对应日期 = 入径日期 + (X-1) 天 */
      dayDate(dayNo) {
        const inst = this.instance;
        if (!inst || !inst.startDate) { return ''; }
        const ts = new Date(String(inst.startDate).replace(' ', 'T')).getTime();
        if (isNaN(ts)) { return ''; }
        const d = new Date(ts + (Number(dayNo) - 1) * 86400000);
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');
        return mm + '-' + dd;
      },
      reset() {
        this.current = null;
        this.detail = null;
        this.tplDetail = null;
        this.openTaskId = null;
      },
      /* ===== 加载: 当前活跃实例 + 执行详情 + 模板诊断 ===== */
      load() {
        const vm = this;
        vm.reset();
        if (!vm.visitId) { return; }
        const vid = vm.visitId;
        vm.loading = true;
        HIS.get('/api/his/pathway/instance/current/' + encodeURIComponent(vid))
          .then(function (data) {
            if (String(vm.visitId) !== String(vid)) { return; }
            vm.current = data || null;
            if (vm.instance && vm.instance.id) { vm.loadDetail(vm.instance); }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      loadDetail(inst) {
        const vm = this;
        const iid = inst.id;
        HIS.get('/api/his/pathway/instance/' + encodeURIComponent(iid) + '/detail')
          .then(function (d) {
            if (!vm.instance || String(vm.instance.id) !== String(iid)) { return; }
            vm.detail = d || null;
          })
          .catch(HIS.notifyError);
        HIS.get('/api/his/pathway/template/' + encodeURIComponent(inst.templateId))
          .then(function (d) {
            if (!vm.instance || String(vm.instance.id) !== String(iid)) { return; }
            vm.tplDetail = (d && d.template) || null;
          })
          .catch(function () { /* 诊断仅展示用, 失败静默 */ });
      },
      /* ===== 入径: 模板选择 ===== */
      openPick() {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.pickKeyword = '';
        this.pickDialog = true;
        this.loadPick(true);
      },
      onPickInput() {
        const vm = this;
        if (pickTimer) { clearTimeout(pickTimer); pickTimer = null; }
        pickTimer = setTimeout(function () { vm.loadPick(true); }, 300);
      },
      loadPick() {
        const vm = this;
        vm.pickLoading = true;
        let url = '/api/his/pathway/template/list?status=1&page=1&size=50';
        const kw = String(vm.pickKeyword || '').trim();
        if (kw) { url += '&keyword=' + encodeURIComponent(kw); }
        HIS.get(url)
          .then(function (data) { vm.pickTemplates = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.pickLoading = false; });
      },
      doStart(t) {
        const vm = this;
        if (vm.starting) { return; }
        ElementPlus.ElMessageBox.confirm('确认为该患者启动临床路径「' + t.pathwayName + '」？启动后将按天生成待执行任务表。', '入径确认', {
          type: 'info', confirmButtonText: '启动入径', cancelButtonText: '取消'
        }).then(function () {
          vm.starting = true;
          return HIS.post('/api/his/pathway/instance/start', {
            inpVisitId: HIS.id(vm.visitId),
            templateId: t.id
          });
        }).then(function () {
          HIS.notifySuccess('已启动临床路径: ' + t.pathwayName);
          vm.pickDialog = false;
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.starting = false; });
      },
      /* ===== 当天任务执行 ===== */
      execDay() {
        const vm = this;
        if (!vm.canExec || vm.executing) { return; }
        const inst = vm.instance;
        vm.executing = true;
        HIS.post('/api/his/pathway/instance/' + encodeURIComponent(inst.id) + '/exec-day', {})
          .then(function (r) {
            HIS.notifySuccess('第' + ((r && r.dayNo) || vm.currentDay) + '天必做任务已执行, 生成医嘱 ' + ((r && r.orderCount) || 0) + ' 条');
            vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.executing = false; });
      },
      /* ===== 状态流转 ===== */
      advance() {
        const vm = this;
        if (!vm.canExec) { return; }
        vm.simplePut('/api/his/pathway/instance/' + encodeURIComponent(vm.instance.id) + '/advance',
          '已推进到下一天(第' + (vm.currentDay + 1) + '天)');
      },
      pause() {
        const vm = this;
        if (!vm.canExec) { return; }
        vm.simplePut('/api/his/pathway/instance/' + encodeURIComponent(vm.instance.id) + '/pause', '路径已暂停');
      },
      resume() {
        const vm = this;
        if (!vm.canResume) { return; }
        vm.simplePut('/api/his/pathway/instance/' + encodeURIComponent(vm.instance.id) + '/resume', '路径已恢复');
      },
      simplePut(url, okMsg) {
        const vm = this;
        if (vm.acting) { return; }
        vm.acting = true;
        HIS.put(url)
          .then(function () {
            HIS.notifySuccess(okMsg);
            vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.acting = false; });
      },
      complete() {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认完成本患者的临床路径？完成后路径流程结束, 患者可再次入径其他路径。', '完成路径确认', {
          type: 'warning', confirmButtonText: '完成', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/pathway/instance/' + encodeURIComponent(vm.instance.id) + '/complete');
        }).then(function () {
          HIS.notifySuccess('临床路径已完成');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 退出路径(原因分类必选口径, 自由文本降为备注) ===== */
      openExit() {
        this.exitReason = '';
        this.exitType = '';
        this.exitDialog = true;
        this.loadTypeOptions('exit');
      },
      /* 原因分类字典懒加载(路径统计质控口径, HIS.stdValues 自带缓存) */
      loadTypeOptions(which) {
        const vm = this;
        if (which === 'exit') {
          if (vm.exitTypeOptions.length) { return; }
          HIS.stdValues('cv_code', 'pathway_exit_reason')
            .then(function (l) { vm.exitTypeOptions = l || []; })
            .catch(function () { /* 字典失败不阻塞登记 */ });
        } else {
          if (vm.varianceTypeOptions.length) { return; }
          HIS.stdValues('cv_code', 'pathway_var_reason')
            .then(function (l) { vm.varianceTypeOptions = l || []; })
            .catch(function () { /* 同上 */ });
        }
      },
      doExit() {
        const vm = this;
        if (vm.exitLoading) { return; }
        vm.exitLoading = true;
        const reason = String(vm.exitReason || '').trim();
        const type = String(vm.exitType || '').trim();
        const qs = [];
        if (reason) { qs.push('reason=' + encodeURIComponent(reason)); }
        if (type) { qs.push('type=' + encodeURIComponent(type)); }
        HIS.put('/api/his/pathway/instance/' + encodeURIComponent(vm.instance.id) + '/exit'
          + (qs.length ? '?' + qs.join('&') : ''))
          .then(function () {
            HIS.notifySuccess('已退出临床路径');
            vm.exitDialog = false;
            vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.exitLoading = false; });
      },
      /* ===== 任务: 点击展开操作 / 跳过 / 变异 ===== */
      toggleTask(t) {
        this.openTaskId = String(this.openTaskId) === String(t.execId) ? null : t.execId;
      },
      skipExec(t) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认跳过任务「' + (t.orderContent || '') + '」？跳过表示该任务本次不执行(不生成医嘱)。', '跳过任务', {
          type: 'warning', confirmButtonText: '跳过', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/pathway/instance/exec/' + encodeURIComponent(t.execId) + '/skip');
        }).then(function () {
          HIS.notifySuccess('任务已跳过');
          vm.openTaskId = null;
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      openVariance(t) {
        this.varianceTarget = t;
        this.varianceReason = '';
        this.varianceType = '';
        this.varianceDialog = true;
        this.loadTypeOptions('variance');
      },
      doVariance() {
        const vm = this;
        if (vm.varianceLoading) { return; }
        const reason = String(vm.varianceReason || '').trim();
        const type = String(vm.varianceType || '').trim();
        if (!type && !reason) { ElementPlus.ElMessage.warning('请选择变异原因分类或填写原因(变异记录将纳入路径质量分析)'); return; }
        vm.varianceLoading = true;
        const qs = [];
        if (reason) { qs.push('reason=' + encodeURIComponent(reason)); }
        if (type) { qs.push('type=' + encodeURIComponent(type)); }
        HIS.put('/api/his/pathway/instance/exec/' + encodeURIComponent(vm.varianceTarget.execId)
          + '/variance?' + qs.join('&'))
          .then(function () {
            HIS.notifySuccess('已登记变异');
            vm.varianceDialog = false;
            vm.openTaskId = null;
            vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.varianceLoading = false; });
      }
    },
    mounted() {
      ensureStyle();
    },
    template: `
      <div class="ipw-wrap" v-loading="loading">
        <!-- 未入径 -->
        <div v-if="!instance && !loading" class="ipw-empty">
          <span class="big">该患者尚未进入临床路径</span>
          <span class="sub">选择与该患者诊断匹配的路径模板入径, 按日执行标准化诊疗任务并可自动转医嘱</span>
          <el-button type="primary" @click="openPick">选择路径</el-button>
        </div>

        <!-- 已入径 -->
        <template v-if="instance">
          <div class="ipw-banner">
            <div class="main">
              <div class="r1">
                <span class="nm">{{ current.templateName || '临床路径' }}</span>
                <el-tag size="small" :type="statusType" disable-transitions>{{ statusText }}</el-tag>
                <span class="iw-tag" v-if="diseaseName">诊断 {{ diseaseName }}</span>
              </div>
              <div class="r2">
                <span>入径日期 <b>{{ dateText(instance.startDate) }}</b></span>
                <span v-if="current.templateCode">编码 <b>{{ current.templateCode }}</b></span>
                <span>任务处理 <b>{{ current.processedTasks || 0 }}/{{ current.totalTasks || 0 }}</b></span>
                <span v-if="detail && detail.varianceCount">变异 <b style="color:var(--yb-danger)">{{ detail.varianceCount }}</b></span>
              </div>
            </div>
            <div class="ipw-progress">
              <el-progress :percentage="dayPercent" :stroke-width="8" :show-text="false" style="width:140px"></el-progress>
              <span class="pt">第 <b>{{ currentDay }}</b> 天 / 共 <b>{{ totalDays != null ? totalDays : '-' }}</b> 天</span>
            </div>
            <div class="ipw-actions">
              <el-button size="small" type="primary" :disabled="!canExec" :loading="executing" @click="execDay">执行当天任务</el-button>
              <el-button size="small" :disabled="!canExec" :loading="acting" @click="advance">推进下一天</el-button>
              <el-button size="small" v-if="!canResume" :disabled="!canExec" @click="pause">暂停</el-button>
              <el-button size="small" type="warning" plain v-else @click="resume">恢复</el-button>
              <el-button size="small" type="danger" plain @click="openExit">退出</el-button>
              <el-button size="small" type="success" plain @click="complete">完成</el-button>
              <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            </div>
          </div>

          <!-- 横向日历时间轴: 每天一列, 当天高亮 -->
          <el-scrollbar class="ipw-flow">
            <div class="ipw-flow-inner" v-if="days.length">
              <div class="ipw-day" v-for="d in days" :key="d.dayNo" :class="{'is-today': Number(d.dayNo) === currentDay}">
                <div class="ipw-day-head">
                  <b>第{{ d.dayNo }}天</b>
                  <span>{{ dayDate(d.dayNo) }}</span>
                </div>
                <div class="ipw-day-body">
                  <template v-for="n in d.nodes" :key="n.nodeId">
                    <div class="ipw-node-name" v-if="n.nodeName">{{ n.nodeName }}</div>
                    <div v-for="t in n.tasks" :key="t.execId" class="ipw-task" :class="[taskClass(t), {'is-open': String(openTaskId) === String(t.execId)}]"
                         @click="toggleTask(t)">
                      <div class="row">
                        <span class="ic" :style="{color: statusColor(t.execStatus)}" :title="statusOf(t.execStatus)">{{ statusIcon(t.execStatus) }}</span>
                        <span class="ct" :title="(t.orderContent || '') + (t.varianceReason ? ' | 变异: ' + t.varianceReason : '')">{{ t.orderContent || '(任务已删除)' }}</span>
                        <span class="mk" v-if="Number(t.isMandatory) === 1" title="必做">必</span>
                      </div>
                      <div class="ops" v-if="String(openTaskId) === String(t.execId) && Number(t.execStatus) === 1" @click.stop>
                        <el-button link type="warning" size="small" @click="skipExec(t)">跳过</el-button>
                        <el-button link type="danger" size="small" @click="openVariance(t)">标记变异</el-button>
                      </div>
                    </div>
                  </template>
                </div>
              </div>
            </div>
            <div v-else-if="!loading" class="ipw-flow-placeholder">路径暂无逐日任务数据</div>
          </el-scrollbar>

          <!-- 底部: 变异记录 -->
          <div class="ipw-variance" v-if="varianceRows.length">
            <div class="hd">变异记录 <span class="iw-count">{{ varianceRows.length }}</span>
              <span class="iw-dim" style="font-weight:400">(点击图上任务可跳过或标记变异)</span>
            </div>
            <el-table :data="varianceRows" size="small" border max-height="160">
              <el-table-column label="天数" width="72" align="center">
                <template #default="s">第{{ s.row.dayNo }}天</template>
              </el-table-column>
              <el-table-column prop="orderContent" label="任务内容" min-width="220" show-overflow-tooltip></el-table-column>
              <el-table-column label="原因分类" width="130" show-overflow-tooltip>
                <template #default="s">{{ s.row.varianceTypeName || '未分类' }}</template>
              </el-table-column>
              <el-table-column prop="varianceReason" label="备注" min-width="160" show-overflow-tooltip></el-table-column>
              <el-table-column label="操作时间" width="110" align="center">
                <template #default="s">{{ s.row.execDate || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>

        <!-- 路径选择对话框 -->
        <el-dialog v-model="pickDialog" title="选择临床路径" width="640px" top="8vh" :close-on-click-modal="false">
          <div style="display:flex;gap:8px;margin-bottom:10px">
            <el-input v-model="pickKeyword" placeholder="按路径名 / 诊断名检索" clearable @input="onPickInput" @keyup.enter="loadPick" style="flex:1"></el-input>
            <el-button @click="loadPick">搜索</el-button>
          </div>
          <div class="ipw-pick-list" v-loading="pickLoading">
            <div class="ipw-pick-item" v-for="t in pickTemplates" :key="t.id" @click="doStart(t)">
              <div class="r1">
                <b>{{ t.pathwayName }}</b>
                <span class="ver">V{{ t.version || 1 }}</span>
                <el-tag size="small" type="success" disable-transitions style="margin-left:auto">启用</el-tag>
              </div>
              <div class="r2">
                <span>适用诊断: {{ t.diseaseName || '-' }}{{ t.diseaseCode ? ' (' + t.diseaseCode + ')' : '' }}</span>
                <span>平均住院日: {{ t.avgLength != null ? t.avgLength + ' 天' : '-' }}</span>
                <span v-if="t.totalCost != null">预估费用: ¥{{ Number(t.totalCost).toFixed(2) }}</span>
              </div>
            </div>
            <div v-if="!pickTemplates.length && !pickLoading" class="iw-empty-line">
              未找到启用的路径模板{{ pickKeyword ? '(关键字: ' + pickKeyword + ')' : '' }}, 请先在「临床路径管理」页维护
            </div>
          </div>
          <template #footer>
            <el-button @click="pickDialog = false">取消</el-button>
          </template>
        </el-dialog>

        <!-- 退出路径对话框(原因分类走 pathway_exit_reason 字典, 供质控柏拉图; 自由文本为备注) -->
        <el-dialog v-model="exitDialog" title="退出临床路径" width="480px" :close-on-click-modal="false">
          <div class="iw-hint" style="margin:0 0 10px">退出将终止本患者的路径流程, 请选择退出原因分类(纳入路径质控分析)。</div>
          <el-select v-model="exitType" clearable filterable placeholder="退出原因分类(建议必选)" style="width:100%;margin-bottom:10px">
            <el-option v-for="o in exitTypeOptions" :key="o.code" :label="o.name" :value="o.code"></el-option>
          </el-select>
          <el-input v-model="exitReason" type="textarea" :rows="3" placeholder="补充说明/备注(选填)"></el-input>
          <template #footer>
            <el-button @click="exitDialog = false">取消</el-button>
            <el-button type="danger" :loading="exitLoading" @click="doExit">确认退出</el-button>
          </template>
        </el-dialog>

        <!-- 变异登记对话框(原因分类走 pathway_var_reason 字典, 供质控柏拉图; 自由文本为备注) -->
        <el-dialog v-model="varianceDialog" title="登记变异" width="480px" :close-on-click-modal="false">
          <div class="iw-hint" style="margin:0 0 10px">任务: {{ varianceTarget && varianceTarget.orderContent || '-' }}</div>
          <el-select v-model="varianceType" clearable filterable placeholder="变异原因分类(建议必选)" style="width:100%;margin-bottom:10px">
            <el-option v-for="o in varianceTypeOptions" :key="o.code" :label="o.name" :value="o.code"></el-option>
          </el-select>
          <el-input v-model="varianceReason" type="textarea" :rows="3" placeholder="补充说明/备注(如: 患者拒绝 / 病情变化调整方案)"></el-input>
          <template #footer>
            <el-button @click="varianceDialog = false">取消</el-button>
            <el-button type="danger" :loading="varianceLoading" @click="doVariance">确认登记</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpPathwayPanel = InpPathwayPanel;
})();
