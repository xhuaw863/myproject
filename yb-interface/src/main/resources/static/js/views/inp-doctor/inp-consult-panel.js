/* 住院医生站 - 会诊管理面板: 会诊申请(普通/急/MDT) + 状态流转(受理/完成/拒绝/取消) + 意见留痕
 * 接口: GET /api/his/inp/consultation/list?visitId&status&page&size (分页)
 *       POST /api/his/inp/consultation {inpVisitId,consultType,targetDeptId,targetDoctorId,applyReason,urgencyLevel}
 *       PUT /{id}/accept | PUT /{id}/complete {opinion} | PUT /{id}/reject {reason} | PUT /{id}/cancel
 * 状态机: 1已申请(可受理/拒绝/取消) → 2已受理(可完成) → 3已完成; 1→4已拒绝 / 1→5已取消
 * 参考数据: /api/his/dept/list · /api/his/staff/list?staffType=医师(按科室前端筛选)
 * 注册: HIS.components.InpConsultPanel (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  const CONSULT_TYPE = { 1: '普通会诊', 2: '急会诊', 3: 'MDT会诊' };
  const CONSULT_TYPE_TAG = { 1: 'info', 2: 'warning', 3: '' };
  const CONSULT_STATUS = { 1: '已申请', 2: '已受理', 3: '已完成', 4: '已拒绝', 5: '已取消' };
  const CONSULT_STATUS_TAG = { 1: '', 2: 'warning', 3: 'success', 4: 'danger', 5: 'info' };
  const URGENCY = { 1: '普通', 2: '紧急', 3: '特急' };
  const URGENCY_TAG = { 1: 'info', 2: 'warning', 3: 'danger' };

  /* 内联 SVG 图标(项目未引入图标库, 统一 24 视框 / currentColor 染色), 经 v-html 渲染 */
  const ICONS = {
    check: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="m8.5 12.5 2.5 2.5 5-5"/></svg>',
    close: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="m9 9 6 6M15 9l-6 6"/></svg>',
    ban: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M5.7 5.7l12.6 12.6"/></svg>',
    edit: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>',
    /* T48 远程视频会诊: 摄像机(发起视频, consultType=3 MDT/远程会诊专属) */
    video: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="6.5" width="13" height="11" rx="2"/><path d="m15.5 10.5 4.6-2.8a.65.65 0 0 1 1 .55v7.5a.65.65 0 0 1-1 .55l-4.6-2.8"/></svg>'
  };

  function timeText(value) {
    if (!value) { return '-'; }
    const s = String(value).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }

  /* T48 远程视频会诊占位样式(面板私有, 一次性注入不碰公共 css) */
  (function ensureConsultVideoStyles() {
    if (document.getElementById('inp-consult-video-style')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-consult-video-style';
    st.textContent = [
      /* 布局: 左视频窗口 300x200 + 右信息面板 */
      '.cv-layout { display:flex; gap:16px; align-items:stretch; }',
      '.cv-stage { position:relative; width:300px; height:200px; flex:none; border-radius:10px; overflow:hidden;',
      ' background:linear-gradient(160deg, #2c3444 0%, #1c2430 60%, #232c3a 100%);',
      ' box-shadow:inset 0 0 0 1px rgba(255,255,255,.08), 0 6px 18px rgba(28,36,48,.35); }',
      /* REC 徽标 + 会诊时长计时 */
      '.cv-rec { position:absolute; top:10px; left:12px; z-index:3; display:flex; align-items:center; gap:6px;',
      ' color:#ff8f8f; font-size:12px; font-weight:700; font-family:var(--yb-font-mono, Consolas, monospace);',
      ' letter-spacing:.08em; text-shadow:0 1px 2px rgba(0,0,0,.6); }',
      '.cv-rec .dot { width:8px; height:8px; border-radius:50%; background:#ff5c5c; animation:cv-blink 1.3s ease-in-out infinite; }',
      '@keyframes cv-blink { 0%,100% { opacity:1; } 50% { opacity:.2; } }',
      '.cv-rec .tm { color:#fff; }',
      /* 视频扫描线(实时画面意象) */
      '.cv-scan { position:absolute; left:0; right:0; top:0; height:2px; z-index:2;',
      ' background:linear-gradient(90deg, transparent, rgba(255,255,255,.22) 30%, rgba(255,255,255,.22) 70%, transparent);',
      ' animation:cv-scan 4.5s linear infinite; }',
      '@keyframes cv-scan { 0% { top:-2px; } 100% { top:100%; } }',
      /* 中心: 摄像头图标 + 演示模式文案 */
      '.cv-center { position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; color:#aab6c8; }',
      '.cv-center .cam { opacity:.85; }',
      '.cv-center .cam svg { width:56px; height:56px; stroke-width:1.3; }',
      '.cv-center .t1 { color:#e7edf5; font-size:14px; font-weight:700; letter-spacing:.12em; }',
      '.cv-center .t2 { font-size:11px; opacity:.7; font-family:var(--yb-font-mono, Consolas, monospace); letter-spacing:.06em; }',
      /* 右侧会诊信息面板 */
      '.cv-info { flex:1; min-width:0; display:flex; flex-direction:column; }',
      '.cv-info .cap { color:var(--yb-ink-2); font-weight:700; font-size:13px; padding-bottom:8px; border-bottom:1px solid var(--yb-divider); margin-bottom:10px; }',
      '.cv-row { display:flex; align-items:baseline; gap:10px; padding:6px 0; border-bottom:1px dashed var(--yb-divider); }',
      '.cv-row:last-child { border-bottom:none; }',
      '.cv-row .k { flex:none; width:72px; color:var(--yb-ink-3); font-size:12px; }',
      '.cv-row .v { color:var(--yb-ink-1); font-weight:600; font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      /* SDK 提示条 */
      '.cv-tip { margin-top:14px; padding:8px 12px; border-radius:6px; font-size:12px; line-height:1.7;',
      ' background:var(--yb-warning-bg); border:1px solid var(--yb-warning-border); color:var(--yb-warning-strong); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  const InpConsultPanel = {
    name: 'InpConsultPanel',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 10,
        total: 0,
        statusFilter: null,
        statuses: CONSULT_STATUS,
        /* 参考数据(科室/医师) */
        depts: [],
        doctors: [],
        /* 申请对话框 */
        applyVisible: false,
        applying: false,
        form: { consultType: 1, targetDeptId: null, targetDoctorId: null, applyReason: '', urgencyLevel: 1 },
        /* T48 远程视频会诊占位(consultType=3): 会诊行 + 患者摘要 + 时长计时 */
        videoVisible: false,
        videoRow: null,
        videoPatient: { name: '-', bedNo: '-' },
        videoSeconds: 0,
        videoTimer: null,
        /* 操作图标 */
        icons: ICONS
      };
    },
    computed: {
      deptMap() {
        const m = {};
        (this.depts || []).forEach(function (d) { m[HIS.idKey(d.id)] = d.deptName; });
        return m;
      },
      staffMap() {
        const m = {};
        (this.doctors || []).forEach(function (s) { m[HIS.idKey(s.id)] = s.staffName; });
        return m;
      },
      /* 目标医生: 按所选科室前端筛选 */
      deptDoctors() {
        const vm = this;
        if (vm.form.targetDeptId == null) { return []; }
        return (vm.doctors || []).filter(function (d) { return HIS.sameId(d.deptId, vm.form.targetDeptId); });
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler() {
          this.page = 1;
          this.rows = [];
          this.total = 0;
          this.load();
        }
      }
    },
    mounted() {
      this.loadRefs();
    },
    beforeUnmount() {
      /* 视频演示计时器随面板销毁一并清理, 防泄漏 */
      this.stopVideoTimer();
    },
    methods: {
      timeText,
      typeText(v) { return CONSULT_TYPE[v] || '-'; },
      typeTag(v) { const t = CONSULT_TYPE_TAG[v]; return t === undefined ? 'info' : t; },
      statusText(v) { return CONSULT_STATUS[v] || '-'; },
      statusTag(v) { const t = CONSULT_STATUS_TAG[v]; return t === undefined ? 'info' : t; },
      urgencyText(v) { return URGENCY[v] || '-'; },
      urgencyTag(v) { return URGENCY_TAG[v] || 'info'; },
      deptText(id) { return id == null ? '-' : (this.deptMap[HIS.idKey(id)] || String(id)); },
      doctorText(id) { return id == null ? '-' : (this.staffMap[HIS.idKey(id)] || String(id)); },
      doctorLabel(d) { return d.staffName + (d.titleName ? '（' + d.titleName + '）' : ''); },
      loadRefs() {
        const vm = this;
        HIS.get('/api/his/dept/list')
          .then(function (list) { vm.depts = list || []; })
          .catch(function () { });
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师'))
          .then(function (list) { vm.doctors = list || []; })
          .catch(function () { });
      },
      load() {
        const vm = this;
        if (!vm.visitId) { vm.rows = []; vm.total = 0; return Promise.resolve(); }
        vm.loading = true;
        let url = '/api/his/inp/consultation/list?visitId=' + HIS.idParam(vm.visitId) +
                  '&page=' + vm.page + '&size=' + vm.size;
        if (vm.statusFilter != null) { url += '&status=' + vm.statusFilter; }
        return HIS.get(url)
          .then(function (data) {
            vm.rows = (data && data.records) || [];
            vm.total = (data && Number(data.total)) || 0;
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      onFilterChange() { this.page = 1; this.load(); },
      onPage(p) { this.page = p; this.load(); },
      openApply() {
        const vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        vm.form = { consultType: 1, targetDeptId: null, targetDoctorId: null, applyReason: '', urgencyLevel: 1 };
        vm.applyVisible = true;
        if (!vm.depts.length || !vm.doctors.length) { vm.loadRefs(); }
      },
      submitApply() {
        const vm = this;
        if (!vm.form.targetDeptId) { ElementPlus.ElMessage.warning('请选择目标科室'); return; }
        if (!String(vm.form.applyReason || '').trim()) { ElementPlus.ElMessage.warning('请填写申请原因'); return; }
        vm.applying = true;
        HIS.post('/api/his/inp/consultation', {
          inpVisitId: HIS.id(vm.visitId),
          consultType: vm.form.consultType,
          targetDeptId: HIS.id(vm.form.targetDeptId),
          targetDoctorId: HIS.id(vm.form.targetDoctorId),
          applyReason: String(vm.form.applyReason).trim(),
          urgencyLevel: vm.form.urgencyLevel
        }).then(function () {
          HIS.notifySuccess('会诊申请已提交');
          vm.applyVisible = false;
          vm.page = 1;
          vm.load();
        }).catch(HIS.notifyError)
          .finally(function () { vm.applying = false; });
      },
      accept(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认受理「' + vm.typeText(row.consultType) + '」？受理后请及时完成并填写会诊意见。', '受理确认', {
          type: 'info', confirmButtonText: '受理', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/consultation/' + HIS.idParam(row.id) + '/accept');
        }).then(function () {
          HIS.notifySuccess('会诊已受理');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      reject(row) {
        const vm = this;
        ElementPlus.ElMessageBox.prompt('请输入拒绝原因(将反馈给申请医师)', '拒绝会诊', {
          type: 'warning', inputType: 'textarea', confirmButtonText: '确认拒绝', cancelButtonText: '取消',
          inputPlaceholder: '如: 当前患者病情不属于本科诊疗范围',
          inputValidator: function (v) { return !!(v && String(v).trim()) || '拒绝原因不能为空'; }
        }).then(function (r) {
          return HIS.put('/api/his/inp/consultation/' + HIS.idParam(row.id) + '/reject', { reason: String(r.value).trim() });
        }).then(function () {
          HIS.notifySuccess('已拒绝该会诊申请');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      cancel(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认取消该会诊申请？仅申请人本人可取消。', '取消确认', {
          type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回'
        }).then(function () {
          return HIS.put('/api/his/inp/consultation/' + HIS.idParam(row.id) + '/cancel');
        }).then(function () {
          HIS.notifySuccess('会诊申请已取消');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      complete(row) {
        const vm = this;
        ElementPlus.ElMessageBox.prompt('请填写会诊意见(结论/建议), 完成后不可再修改。', '完成会诊', {
          type: 'success', inputType: 'textarea', confirmButtonText: '提交会诊意见', cancelButtonText: '取消',
          inputPlaceholder: '如: 考虑社区获得性肺炎, 建议……',
          inputValidator: function (v) { return !!(v && String(v).trim()) || '会诊意见不能为空'; }
        }).then(function (r) {
          return HIS.put('/api/his/inp/consultation/' + HIS.idParam(row.id) + '/complete', { opinion: String(r.value).trim() });
        }).then(function () {
          HIS.notifySuccess('会诊已完成, 意见已存档');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ================= T48 远程视频会诊占位(consultType=3 MDT/远程) ================= */
      /* 会诊时长 mm:ss */
      fmtDuration(sec) {
        const m = Math.floor(sec / 60), s = sec % 60;
        return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
      },
      /* 发起视频: 打开演示模式对话框 + 拉患者摘要(姓名/床号) + 启动时长计时 */
      openVideo(row) {
        const vm = this;
        vm.videoRow = row;
        vm.videoPatient = { name: '-', bedNo: '-' };
        vm.videoSeconds = 0;
        vm.videoVisible = true;
        vm.stopVideoTimer();
        vm.videoTimer = setInterval(function () { vm.videoSeconds += 1; }, 1000);
        /* 患者摘要: 就诊详情(与医生站患者横幅同源) */
        if (vm.visitId) {
          HIS.get('/api/his/inp/visit/' + HIS.idParam(vm.visitId))
            .then(function (d) {
              const p = (d && d.patient) || {};
              const b = (d && d.bed) || {};
              vm.videoPatient = {
                name: p.name || '-',
                bedNo: b.bedNo ? (b.bedNo + '床') : '未分床'
              };
            })
            .catch(function () { /* 详情不可用时保持 '-' 占位 */ });
        }
      },
      stopVideoTimer() {
        if (this.videoTimer) { clearInterval(this.videoTimer); this.videoTimer = null; }
      },
      /* 结束会诊: 关闭演示窗 -> 已受理(status=2)时引导填写完成意见(复用 complete 链路) */
      closeVideo() {
        const vm = this;
        const row = vm.videoRow;
        vm.videoVisible = false;
        vm.stopVideoTimer();
        if (row && Number(row.status) === 2) {
          vm.complete(row);
        }
      },
      /* 对话框被动关闭(X/遮罩/Esc): 仅停计时, 不触发完成意见引导 */
      onVideoClosed() {
        this.stopVideoTimer();
      }
    },
    template: `
      <div class="iw-panel-body iw-panel-body--flush">
        <div class="iw-toolbar">
          <el-select v-model="statusFilter" placeholder="全部状态" size="small" clearable style="width:130px" @change="onFilterChange">
            <el-option v-for="(label, key) in statuses" :key="key" :label="label" :value="Number(key)"></el-option>
          </el-select>
          <span class="iw-toolbar-info">共 <b>{{ total }}</b> 条会诊</span>
          <span class="iw-toolbar-right">
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button size="small" type="primary" :disabled="!visitId" @click="openApply">申请会诊</el-button>
          </span>
        </div>

        <div class="iw-table-scroll" v-loading="loading">
          <el-table :data="rows" height="100%" size="small" border :row-key="row => row.id">
            <el-table-column type="expand">
              <template #default="s">
                <div style="padding:6px 14px;font-size:13px;color:var(--yb-ink-2);line-height:1.9">
                  <div>申请科室 <b>{{ deptText(s.row.applyDeptId) }}</b> · 申请医师 <b>{{ doctorText(s.row.applyDoctorId) }}</b> · 申请时间 {{ timeText(s.row.applyTime) }}</div>
                  <div>目标科室 <b>{{ deptText(s.row.targetDeptId) }}</b> · 目标医师 <b>{{ doctorText(s.row.targetDoctorId) }}</b></div>
                  <div>申请理由: {{ s.row.applyReason || '-' }}</div>
                  <div v-if="s.row.consultOpinion">意见 / 回执: {{ s.row.consultOpinion }}</div>
                  <div>受理时间 {{ s.row.responseTime ? timeText(s.row.responseTime) : '-' }} · 完成时间 {{ s.row.consultTime ? timeText(s.row.consultTime) : '-' }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="会诊类型" width="104" align="center">
              <template #default="s">
                <el-tag size="small" :type="typeTag(s.row.consultType)" disable-transitions>{{ typeText(s.row.consultType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="紧急" width="72" align="center">
              <template #default="s">
                <el-tag size="small" :type="urgencyTag(s.row.urgencyLevel)" disable-transitions>{{ urgencyText(s.row.urgencyLevel) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="目标科室" min-width="130" show-overflow-tooltip>
              <template #default="s">{{ deptText(s.row.targetDeptId) }}</template>
            </el-table-column>
            <el-table-column label="目标医生" width="110" show-overflow-tooltip>
              <template #default="s">{{ doctorText(s.row.targetDoctorId) }}</template>
            </el-table-column>
            <el-table-column label="申请时间" width="140">
              <template #default="s">{{ timeText(s.row.applyTime) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="86" align="center">
              <template #default="s">
                <el-tag size="small" :type="statusTag(s.row.status)" disable-transitions>{{ statusText(s.row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="136" fixed="right" align="center">
              <template #default="s">
                <span class="iw-ops" v-if="Number(s.row.status) === 1">
                  <el-tooltip v-if="Number(s.row.consultType) === 3" content="发起远程视频" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-primary" @click="openVideo(s.row)" v-html="icons.video"></button>
                  </el-tooltip>
                  <el-tooltip content="受理" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-success" @click="accept(s.row)" v-html="icons.check"></button>
                  </el-tooltip>
                  <el-tooltip content="拒绝" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-danger" @click="reject(s.row)" v-html="icons.close"></button>
                  </el-tooltip>
                  <el-tooltip content="取消申请" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn" @click="cancel(s.row)" v-html="icons.ban"></button>
                  </el-tooltip>
                </span>
                <span class="iw-ops" v-else-if="Number(s.row.status) === 2">
                  <el-tooltip v-if="Number(s.row.consultType) === 3" content="发起远程视频" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-primary" @click="openVideo(s.row)" v-html="icons.video"></button>
                  </el-tooltip>
                  <el-tooltip content="填写意见并完成" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-success" @click="complete(s.row)" v-html="icons.edit"></button>
                  </el-tooltip>
                </span>
                <span class="iw-ops" v-else-if="Number(s.row.consultType) === 3 && Number(s.row.status) === 3">
                  <el-tooltip content="查看会诊回执" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn" disabled v-html="icons.video"></button>
                  </el-tooltip>
                </span>
                <span v-else class="iw-dim">-</span>
              </template>
            </el-table-column>
            <template #empty><div class="iw-empty-line">暂无会诊记录, 点击右上「申请会诊」发起</div></template>
          </el-table>
        </div>

        <div class="iw-pager" v-if="total > size">
          <el-pagination small background layout="prev, pager, next" :total="total" :page-size="size"
                         :current-page="page" @current-change="onPage"></el-pagination>
        </div>

        <!-- T48 远程视频会诊演示对话框(consultType=3): 左模拟视频窗 + 右会诊信息 -->
        <el-dialog v-model="videoVisible" title="远程视频会诊" width="700px" :close-on-click-modal="false" @close="onVideoClosed">
          <div class="cv-layout" v-if="videoRow">
            <div class="cv-stage">
              <div class="cv-scan"></div>
              <div class="cv-rec"><span class="dot"></span>REC <span class="tm">{{ fmtDuration(videoSeconds) }}</span></div>
              <div class="cv-center">
                <span class="cam" v-html="icons.video"></span>
                <div class="t1">视频会诊演示模式</div>
                <div class="t2">LOCAL PREVIEW · NO A/V STREAM</div>
              </div>
            </div>
            <div class="cv-info">
              <div class="cap">会诊信息</div>
              <div class="cv-row"><span class="k">患者姓名</span><span class="v">{{ videoPatient.name }}</span></div>
              <div class="cv-row"><span class="k">床号</span><span class="v">{{ videoPatient.bedNo }}</span></div>
              <div class="cv-row"><span class="k">会诊类型</span><span class="v">{{ typeText(videoRow.consultType) }}</span></div>
              <div class="cv-row"><span class="k">申请科室</span><span class="v">{{ deptText(videoRow.applyDeptId) }}</span></div>
              <div class="cv-row"><span class="k">会诊医生</span><span class="v">{{ doctorText(videoRow.targetDoctorId) }}</span></div>
              <div class="cv-row"><span class="k">紧急程度</span><span class="v">{{ urgencyText(videoRow.urgencyLevel) }}</span></div>
              <div class="cv-row"><span class="k">当前状态</span><span class="v"><el-tag size="small" :type="statusTag(videoRow.status)" disable-transitions>{{ statusText(videoRow.status) }}</el-tag></span></div>
            </div>
          </div>
          <div class="cv-tip">演示占位：实际部署需集成第三方音视频 SDK（声网 / 腾讯云），本窗口仅为交互流程示意。</div>
          <template #footer>
            <el-button @click="videoVisible = false">暂离</el-button>
            <el-button type="danger" @click="closeVideo">结束会诊</el-button>
          </template>
        </el-dialog>

        <!-- 申请会诊对话框 -->
        <el-dialog v-model="applyVisible" title="申请会诊" width="560px" :close-on-click-modal="false">
          <div class="iw-form">
            <div class="iw-form-row">
              <span class="lb">会诊类型</span>
              <el-radio-group v-model="form.consultType" size="small">
                <el-radio-button :label="1">普通会诊</el-radio-button>
                <el-radio-button :label="2">急会诊</el-radio-button>
                <el-radio-button :label="3">MDT会诊</el-radio-button>
              </el-radio-group>
            </div>
            <div class="iw-form-row">
              <span class="lb">目标科室</span>
              <el-select v-model="form.targetDeptId" size="small" filterable clearable style="width:240px"
                         placeholder="选择受邀科室" @change="form.targetDoctorId = null">
                <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>
              </el-select>
            </div>
            <div class="iw-form-row">
              <span class="lb">目标医生</span>
              <el-select v-model="form.targetDoctorId" size="small" filterable clearable style="width:240px"
                         :placeholder="form.targetDeptId == null ? '请先选择科室' : '按科室筛选(可留空)'" :disabled="form.targetDeptId == null">
                <el-option v-for="d in deptDoctors" :key="d.id" :label="doctorLabel(d)" :value="d.id"></el-option>
              </el-select>
            </div>
            <div class="iw-form-row">
              <span class="lb">紧急程度</span>
              <el-radio-group v-model="form.urgencyLevel" size="small">
                <el-radio-button :label="1">普通</el-radio-button>
                <el-radio-button :label="2">紧急</el-radio-button>
                <el-radio-button :label="3">特急</el-radio-button>
              </el-radio-group>
            </div>
            <div class="iw-form-row" style="align-items:flex-start">
              <span class="lb" style="padding-top:6px">申请原因</span>
              <el-input v-model="form.applyReason" type="textarea" :rows="4" maxlength="500" show-word-limit
                        class="iw-grow" placeholder="患者病情摘要与会诊目的"></el-input>
            </div>
          </div>
          <template #footer>
            <el-button @click="applyVisible = false">取消</el-button>
            <el-button type="primary" :loading="applying" @click="submitApply">提交申请</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpConsultPanel = InpConsultPanel;
})();
