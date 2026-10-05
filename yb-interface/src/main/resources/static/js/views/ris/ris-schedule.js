/* ============================================================================
 * RIS 检查排程管理(ris-schedule) — 双组件一文件:
 *   1) RisSchedule          设备号源簿: 单设备+单日期 号源时段网格(生成号源/预约/取消预约)
 *   2) RisScheduleTemplate  排程周模板维护: 周几 x 时段段 CRUD(供 生成号源 展开为日排程)
 * 后端契约(com.yb.hi.controller.ris.RisScheduleController, /api/ris/schedule):
 *   POST /generate               {deviceId, date} → {deviceId, created}
 *                                幂等: 同设备同日同时段已存在跳过; 当日无启用模板则报错提示
 *   POST /book                   {scheduleId, requestId} 占位乐观锁(占满自动置已满; 申请单联动置已预约回填时间/设备/技师)
 *   PUT  /{id}/cancel-booking    {requestId} 余量回退/已满恢复可约, 申请单回退待预约并清空分配
 *   GET  /by-device?deviceId&startDate&endDate  时段列表(附 remaining/requestId/technicianName, requestId 为雪花字符串)
 *   GET  /template?deviceId      周模板列表   |  POST /template 新增/更新  |  PUT /template/{id}  |  DELETE /template/{id}
 *   (GET /slots 可约时段查询、POST /slot 手工加号: 供技师工作台/后续页面, 本页不消费)
 * 时段状态: 1可预约 / 2已满 / 3停诊 / 4临时加号; 仅状态 1/4 且有余量可被预约。
 * 设备数据源: 影像设备台账(his_imaging_device)管理页(ris-device)尚未交付, 当前以演示设备清单占位;
 *   deviceId 全链路字符串透传(雪花ID防精度), 接入设备接口后仅替换下拉数据源即可, 其余逻辑不变。
 * 注册: HIS.views.RisSchedule(菜单 ris-schedule) / HIS.views.RisScheduleTemplate(菜单 ris-schedule-template)
 * 样式前缀 rs-
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量 ===== */
  /* 演示设备清单(占位): his_imaging_device 台账就绪后替换为远程加载 */
  var DEMO_DEVICES = [
    { id: '1', deviceName: 'DR-1 数字化X线机', deviceType: 'DR', modality: 'CR', roomNo: '放射1室' },
    { id: '2', deviceName: 'CT-1 64排螺旋CT', deviceType: 'CT', modality: 'CT', roomNo: 'CT室' },
    { id: '3', deviceName: 'MR-1 1.5T磁共振', deviceType: 'MRI', modality: 'MR', roomNo: '磁共振室' },
    { id: '4', deviceName: 'US-1 彩色多普勒超声', deviceType: 'US', modality: 'US', roomNo: '超声1室' }
  ];
  var DAY_OF_WEEK = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };
  /* 申请单状态(时段详情弹窗展示关联申请单用) */
  var REQ_STATUS_MAP = { 0: '待预约', 1: '已预约', 2: '已登记', 3: '检查中', 4: '已完成', 5: '已报告', 6: '已审核', 7: '已取消' };
  var EXAM_TYPE_MAP = { XRAY: 'X线', CT: 'CT', MRI: 'MRI', US: '超声', DSA: 'DSA', ENDO: '内镜' };

  /* ===== 工具 ===== */
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function padTime(v) { return (v === null || v === undefined || v === '') ? '' : String(v).slice(0, 5); }
  function todayStr() {
    var d = new Date();
    var m = d.getMonth() + 1;
    var day = d.getDate();
    return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
  }
  function confirmBox(msg, title) {
    return ElementPlus.ElMessageBox.confirm(msg, title || '操作确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' });
  }
  function isCancel(e) { return e === 'cancel' || e === 'close'; }
  function devNameOf(id) {
    var k = HIS.idKey(id);
    for (var i = 0; i < DEMO_DEVICES.length; i++) {
      if (DEMO_DEVICES[i].id === k) { return DEMO_DEVICES[i].deviceName; }
    }
    return k ? ('未知设备(' + k + ')') : '-';
  }
  function blankTpl(deviceId) {
    return {
      id: null, deviceId: deviceId || DEMO_DEVICES[0].id, dayOfWeek: 1,
      slotStart: '08:00', slotEnd: '12:00', slotDuration: 30, maxPatients: 2,
      technicianId: null, enabled: 1
    };
  }

  /* ===== 样式一次性注入(前缀 rs-) ===== */
  (function ensureRisScheduleStyles() {
    if (document.getElementById('ris-schedule-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-schedule-style';
    st.textContent = [
      /* 统计条 */
      '.rs .rs-stats { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:10px; }',
      '.rs-stat { display:flex; align-items:baseline; gap:5px; padding:3px 12px; border:1px solid var(--yb-border-light); background:var(--yb-surface); border-radius:var(--yb-r-sm); font-size:12px; color:var(--yb-ink-3); box-shadow:var(--yb-sh-1); }',
      '.rs-stat .n { font-size:16px; font-weight:700; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; }',
      '.rs-stat .n.ok { color:var(--yb-success); }',
      '.rs-stat .n.warn { color:var(--yb-warning); }',
      '.rs-stat .n.stop { color:var(--yb-danger); }',
      /* 号源网格 */
      '.rs-board { flex:1; min-height:0; overflow:auto; }',
      '.rs-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(176px, 1fr)); gap:10px; padding:2px; }',
      '.rs-slot { display:flex; flex-direction:column; gap:5px; min-height:92px; padding:8px 10px; border:1px solid var(--yb-border); border-left-width:3px; border-radius:var(--yb-r-sm); background:var(--yb-surface); cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease); }',
      '.rs-slot:hover { box-shadow:var(--yb-sh-2); }',
      '.rs-slot.is-open { background:var(--yb-success-bg); border-color:var(--yb-success-border); border-left-color:var(--yb-fill-success); }',
      '.rs-slot.is-extra { background:var(--yb-warning-bg); border-color:var(--yb-warning-border); border-left-color:var(--yb-fill-warning); }',
      '.rs-slot.is-full { background:var(--yb-surface-2); border-left-color:var(--yb-ink-disabled); }',
      '.rs-slot.is-stop { background:var(--yb-danger-bg); border-color:var(--yb-danger-border); border-left-color:var(--yb-fill-danger); }',
      '.rs-slot-time { font-size:13px; font-weight:700; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; }',
      '.rs-slot.is-full .rs-slot-time { color:var(--yb-ink-2); }',
      '.rs-slot-row { display:flex; align-items:center; justify-content:space-between; gap:6px; }',
      '.rs-slot-cap { font-size:11px; color:var(--yb-ink-3); font-variant-numeric:tabular-nums; }',
      /* 状态胶囊(卡片内/详情弹窗共用) */
      '.rs-pill { display:inline-block; padding:0 7px; border-radius:var(--yb-r-pill); font-size:11px; line-height:17px; border:1px solid transparent; white-space:nowrap; }',
      '.rs-pill.is-open { background:#fff; border-color:var(--yb-success-border); color:var(--yb-success); }',
      '.rs-pill.is-extra { background:#fff; border-color:var(--yb-warning-border); color:var(--yb-warning); }',
      '.rs-pill.is-full { background:var(--yb-surface-3); border-color:var(--yb-border); color:var(--yb-ink-3); }',
      '.rs-pill.is-stop { background:#fff; border-color:var(--yb-danger-border); color:var(--yb-danger); }',
      /* 占位进度条 */
      '.rs-slot-bar { height:4px; border-radius:2px; background:rgba(28, 36, 48, .08); overflow:hidden; }',
      '.rs-slot-bar i { display:block; height:100%; border-radius:2px; background:var(--yb-fill-success); }',
      '.rs-slot.is-extra .rs-slot-bar i { background:var(--yb-fill-warning); }',
      '.rs-slot.is-full .rs-slot-bar i { background:var(--yb-ink-disabled); }',
      '.rs-slot.is-stop .rs-slot-bar i { background:var(--yb-fill-danger); }',
      '.rs-slot-foot { display:flex; align-items:center; justify-content:space-between; gap:6px; min-height:16px; margin-top:auto; }',
      '.rs-slot-pat { font-size:12px; font-weight:600; color:var(--yb-brand); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.rs-slot-pat.is-empty { font-weight:400; color:var(--yb-ink-4); }',
      '.rs-slot-tech { font-size:11px; color:var(--yb-ink-3); white-space:nowrap; }',
      /* 图例 */
      '.rs-legend { display:inline-flex; align-items:center; gap:4px; margin-left:auto; font-size:12px; color:var(--yb-ink-3); }',
      '.rs-dot { display:inline-block; width:10px; height:10px; border-radius:3px; margin-left:10px; }',
      '.rs-dot.is-open { background:var(--yb-fill-success); }',
      '.rs-dot.is-full { background:var(--yb-ink-disabled); }',
      '.rs-dot.is-stop { background:var(--yb-fill-danger); }',
      '.rs-dot.is-extra { background:var(--yb-fill-warning); }',
      /* 杂项 */
      '.rs-hint { font-size:12px; color:var(--yb-ink-3); }',
      '.rs-mono { font-family:var(--yb-font-mono); font-size:12px; }',
      '.rs-book-sel { margin-top:10px; padding:7px 10px; border-radius:var(--yb-r-sm); background:var(--yb-brand-subtle); border:1px solid var(--yb-brand-border); font-size:12px; color:var(--yb-brand-strong); }',
      '.rs-sec { margin:14px 0 8px; padding-left:9px; border-left:3px solid var(--yb-brand); font-size:13px; font-weight:600; color:var(--yb-ink-1); line-height:1; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ========================================================================
   * RisSchedule 排程管理主视图(设备号源簿)
   * ====================================================================== */
  HIS.views.RisSchedule = {
    name: 'RisSchedule',
    template: [
      '<div class="page-card cd-fill rs">',
      '  <div class="page-title">检查排程管理<span class="rs-hint" style="margin-left:10px;font-weight:400">设备号源簿: 可约时段点击预约 / 已约时段点击查看</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="deviceId" size="small" style="width:236px" @change="loadSlots">',
      '      <el-option v-for="d in devices" :key="d.id" :label="devOptionLabel(d)" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" size="small" style="width:150px" @change="loadSlots"></el-date-picker>',
      '    <el-button type="primary" size="small" :loading="genLoading" @click="genSlots">生成号源</el-button>',
      '    <el-button size="small" :loading="loading" @click="loadSlots">刷新</el-button>',
      '    <span class="rs-legend">',
      '      <i class="rs-dot is-open"></i>可约',
      '      <i class="rs-dot is-full"></i>已满',
      '      <i class="rs-dot is-stop"></i>停诊',
      '      <i class="rs-dot is-extra"></i>加号',
      '    </span>',
      '  </div>',
      '  <div class="rs-stats">',
      '    <div class="rs-stat"><span class="n">{{ statTotal }}</span>时段</div>',
      '    <div class="rs-stat"><span class="n ok">{{ statOpen }}</span>可约</div>',
      '    <div class="rs-stat"><span class="n">{{ statBooked }}</span>已约人次</div>',
      '    <div class="rs-stat"><span class="n">{{ statRemain }}</span>剩余号源</div>',
      '    <div class="rs-stat"><span class="n warn">{{ statExtra }}</span>加号</div>',
      '    <div class="rs-stat"><span class="n stop">{{ statStop }}</span>停诊</div>',
      '  </div>',
      '  <div class="rs-board" v-loading="loading">',
      '    <div v-if="slots.length" class="rs-grid">',
      '      <div v-for="sl in slots" :key="sl.id" class="rs-slot" :class="slotClass(sl)" @click="onSlotClick(sl)">',
      '        <div class="rs-slot-time">{{ sl.timeSlot }}</div>',
      '        <div class="rs-slot-row">',
      '          <span class="rs-pill" :class="slotClass(sl)">{{ slotLabel(sl) }}</span>',
      '          <span class="rs-slot-cap">余 {{ orDash(sl.remaining) }}/{{ orDash(sl.maxPatients) }}</span>',
      '        </div>',
      '        <div class="rs-slot-bar"><i :style="barStyle(sl)"></i></div>',
      '        <div class="rs-slot-foot">',
      '          <span v-if="patName(sl)" class="rs-slot-pat">{{ patName(sl) }}</span>',
      '          <span v-else class="rs-slot-pat is-empty">—</span>',
      '          <span v-if="sl.technicianName" class="rs-slot-tech">{{ sl.technicianName }}</span>',
      '        </div>',
      '      </div>',
      '    </div>',
      '    <el-empty v-else-if="!loading" description="当日暂无号源，点击「生成号源」按周模板批量生成"></el-empty>',
      '  </div>',
      /* ===== 预约弹窗(待预约申请单检索+选中) ===== */
      '  <el-dialog v-model="bookVisible" :title="bookTitle" width="700px" top="6vh" append-to-body @closed="onBookClosed">',
      '    <div class="toolbar" style="margin-bottom:10px;padding:8px 12px">',
      '      <el-input v-model="pendKw" clearable placeholder="申请单号 / 患者姓名 / 检查项目" size="small" style="width:240px" @keyup.enter="loadPending"></el-input>',
      '      <el-button type="primary" size="small" @click="loadPending">查询</el-button>',
      '      <span class="rs-hint">仅列出待预约申请单(最多50条, 急诊优先)</span>',
      '    </div>',
      '    <el-table :data="pendRows" v-loading="pendLoading" border size="small" highlight-current-row max-height="320" @current-change="pendSelChange">',
      '      <el-table-column label="申请单号" width="146"><template #default="s"><span class="rs-mono">{{ s.row.requestNo }}</span></template></el-table-column>',
      '      <el-table-column label="患者" width="90" show-overflow-tooltip><template #default="s">{{ orDash(s.row.patientName) }}</template></el-table-column>',
      '      <el-table-column label="类型" width="70" align="center"><template #default="s">{{ examLabel(s.row.examType) }}</template></el-table-column>',
      '      <el-table-column label="检查部位" min-width="130" show-overflow-tooltip><template #default="s">{{ orDash(s.row.bodyPart) }}</template></el-table-column>',
      '      <el-table-column label="申请科室" width="110" show-overflow-tooltip><template #default="s">{{ orDash(s.row.applyDeptName) }}</template></el-table-column>',
      '      <el-table-column label="急诊" width="58" align="center"><template #default="s"><el-tag v-if="s.row.isUrgent === 1" type="danger" size="small" effect="dark">急</el-tag><span v-else>-</span></template></el-table-column>',
      '    </el-table>',
      '    <div v-if="bookSel" class="rs-book-sel">已选择: <b>{{ bookSel.requestNo }}</b> — {{ orDash(bookSel.patientName) }}（{{ examLabel(bookSel.examType) }} {{ orDash(bookSel.bodyPart) }}）</div>',
      '    <div v-else class="rs-hint" style="margin-top:10px">单击列表行选中申请单后点击「确认预约」</div>',
      '    <template #footer>',
      '      <el-button @click="bookVisible = false">取消</el-button>',
      '      <el-button type="primary" :disabled="!bookSel" :loading="booking" @click="submitBook">确认预约</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ===== 时段详情弹窗(查看占用/取消预约/继续预约) ===== */
      '  <el-dialog v-model="slotVisible" title="号源时段详情" width="600px" top="8vh" append-to-body>',
      '    <el-descriptions v-if="curSlot" :column="2" border size="small">',
      '      <el-descriptions-item label="设备" :span="2">{{ devName(curSlot.deviceId) }}</el-descriptions-item>',
      '      <el-descriptions-item label="日期">{{ orDash(curSlot.scheduleDate) }}</el-descriptions-item>',
      '      <el-descriptions-item label="状态"><span class="rs-pill" :class="slotClass(curSlot)">{{ slotLabel(curSlot) }}</span></el-descriptions-item>',
      '      <el-descriptions-item label="时段">{{ orDash(curSlot.timeSlot) }}</el-descriptions-item>',
      '      <el-descriptions-item label="时长">{{ orDash(curSlot.slotDuration) }} 分钟</el-descriptions-item>',
      '      <el-descriptions-item label="容量占用">{{ (curSlot.bookedCount || 0) }} / {{ curSlot.maxPatients || 0 }}</el-descriptions-item>',
      '      <el-descriptions-item label="剩余号源">余 {{ orDash(curSlot.remaining) }}</el-descriptions-item>',
      '      <el-descriptions-item label="值班技师" :span="2">{{ orDash(curSlot.technicianName) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <div v-loading="slotReqLoading">',
      '      <template v-if="slotReq && slotReq.request">',
      '        <div class="rs-sec">关联申请单</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="申请单号">{{ orDash(slotReq.request.requestNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请单状态">{{ stLabel(slotReq.request.status) }}</el-descriptions-item>',
      '          <el-descriptions-item label="患者">{{ patientNameOf(slotReq) }}</el-descriptions-item>',
      '          <el-descriptions-item label="检查类型">{{ examLabel(slotReq.request.examType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="检查部位" :span="2">{{ orDash(slotReq.request.bodyPart) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '      </template>',
      '      <div v-else-if="curSlot && !curSlot.requestId" class="rs-hint" style="margin-top:10px">该时段暂无预约占位</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="slotVisible = false">关闭</el-button>',
      '      <el-button v-if="curSlot && curSlot.requestId" type="danger" plain :loading="unbooking" @click="cancelBooking">取消预约</el-button>',
      '      <el-button v-if="curSlot && canBookMore(curSlot)" type="primary" @click="goBook">继续预约</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        devices: DEMO_DEVICES,
        deviceId: DEMO_DEVICES[0].id,
        date: todayStr(),
        slots: [],
        loading: false,
        genLoading: false,
        patInfo: {},
        bookVisible: false,
        bookSlot: null,
        pendRows: [],
        pendLoading: false,
        pendKw: '',
        bookSel: null,
        booking: false,
        slotVisible: false,
        curSlot: null,
        slotReq: null,
        slotReqLoading: false,
        unbooking: false
      };
    },
    computed: {
      statTotal: function () { return this.slots.length; },
      statOpen: function () {
        return this.slots.filter(function (s) { return s.status !== 3 && (Number(s.remaining) || 0) > 0; }).length;
      },
      statBooked: function () {
        return this.slots.reduce(function (a, s) { return a + (Number(s.bookedCount) || 0); }, 0);
      },
      statRemain: function () {
        return this.slots.reduce(function (a, s) {
          return a + (s.status === 3 ? 0 : Math.max(Number(s.remaining) || 0, 0));
        }, 0);
      },
      statExtra: function () { return this.slots.filter(function (s) { return s.status === 4; }).length; },
      statStop: function () { return this.slots.filter(function (s) { return s.status === 3; }).length; },
      bookTitle: function () {
        if (!this.bookSlot) { return '预约号源'; }
        return '预约号源 — ' + this.bookSlot.timeSlot + '（' + this.devName(this.bookSlot.deviceId) + ' ' + this.date + '）';
      }
    },
    created: function () {
      this.loadSlots();
    },
    methods: {
      /* ===== 展示 ===== */
      orDash: orDash,
      examLabel: function (v) { return EXAM_TYPE_MAP[v] || orDash(v); },
      stLabel: function (v) { var t = REQ_STATUS_MAP[v]; return t === undefined ? orDash(v) : t; },
      devName: devNameOf,
      devOptionLabel: function (d) { return d.deviceName + '（' + d.roomNo + '）'; },
      slotClass: function (s) {
        if (s.status === 3) { return 'is-stop'; }
        if ((Number(s.remaining) || 0) <= 0) { return 'is-full'; }
        return s.status === 4 ? 'is-extra' : 'is-open';
      },
      slotLabel: function (s) {
        if (s.status === 3) { return '停诊'; }
        if ((Number(s.remaining) || 0) <= 0) { return '已满'; }
        return s.status === 4 ? '加号可约' : '可预约';
      },
      barStyle: function (s) {
        var max = Number(s.maxPatients) || 0;
        var bk = Number(s.bookedCount) || 0;
        var p = max > 0 ? Math.round(bk / max * 100) : 0;
        if (bk > 0 && p < 6) { p = 6; }
        return { width: p + '%' };
      },
      patName: function (s) {
        var k = HIS.idKey(s.requestId);
        if (!k) { return ''; }
        var info = this.patInfo[k];
        return (info && info.name) ? info.name : '已预约';
      },
      patientNameOf: function (d) {
        return (d && d.patient && d.patient.name) ? d.patient.name : '-';
      },
      canBookMore: function (s) {
        return s.status !== 3 && (Number(s.remaining) || 0) > 0;
      },
      /* ===== 号源加载 ===== */
      loadSlots: function () {
        var vm = this;
        if (!vm.deviceId || !vm.date) { vm.slots = []; return; }
        vm.loading = true;
        var q = '/api/ris/schedule/by-device?deviceId=' + HIS.idParam(vm.deviceId)
          + '&startDate=' + vm.date + '&endDate=' + vm.date;
        HIS.get(q).then(function (d) {
          vm.slots = d || [];
          vm.hydrate();
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 已约时段患者名水合: 收集缺失 requestId → 申请单详情取患者姓名(失败静默, 不影响号源操作) */
      hydrate: function () {
        var vm = this;
        var ids = [];
        vm.slots.forEach(function (s) {
          var k = HIS.idKey(s.requestId);
          var info = k ? vm.patInfo[k] : null;
          if (k && (!info || !info.name) && ids.indexOf(k) < 0) { ids.push(k); }
        });
        ids.forEach(function (id) {
          HIS.get('/api/ris/request/' + HIS.idParam(id)).then(function (d) {
            vm.patInfo[id] = {
              name: (d && d.patient && d.patient.name) || '',
              no: (d && d.request && d.request.requestNo) || ''
            };
          }).catch(function () { /* 静默 */ });
        });
      },
      /* ===== 生成号源 ===== */
      genSlots: function () {
        var vm = this;
        if (!vm.deviceId || !vm.date) { HIS.notifyError('请先选择设备与日期'); return; }
        confirmBox('按周模板为「' + vm.devName(vm.deviceId) + '」生成 ' + vm.date + ' 的号源时段？已存在的时段自动跳过。', '生成号源')
          .then(function () {
            vm.genLoading = true;
            return HIS.post('/api/ris/schedule/generate', { deviceId: vm.deviceId, date: vm.date });
          })
          .then(function (d) {
            var n = (d && Number(d.created)) || 0;
            HIS.notifySuccess(n > 0 ? '已生成 ' + n + ' 个号源时段' : '该日期号源已全部存在, 未新增');
            vm.loadSlots();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } })
          .finally(function () { vm.genLoading = false; });
      },
      /* ===== 时段点击: 停诊提示 / 有占位看详情 / 空档直接预约 ===== */
      onSlotClick: function (s) {
        if (s.status === 3) { ElementPlus.ElMessage.info('该时段已停诊'); return; }
        var booked = (Number(s.bookedCount) || 0) > 0 || !!HIS.idKey(s.requestId);
        if (booked) { this.openSlotDetail(s); return; }
        this.openBook(s);
      },
      openSlotDetail: function (s) {
        var vm = this;
        vm.curSlot = s;
        vm.slotReq = null;
        vm.slotVisible = true;
        if (s.requestId) {
          vm.slotReqLoading = true;
          HIS.get('/api/ris/request/' + HIS.idParam(s.requestId)).then(function (d) {
            vm.slotReq = d || null;
            var rq = d && d.request;
            if (rq) {
              vm.patInfo[HIS.idKey(s.requestId)] = {
                name: (d.patient || {}).name || '',
                no: rq.requestNo || ''
              };
            }
          }).catch(HIS.notifyError).finally(function () { vm.slotReqLoading = false; });
        }
      },
      /* ===== 预约 ===== */
      openBook: function (s) {
        var vm = this;
        vm.bookSlot = s;
        vm.bookSel = null;
        vm.pendKw = '';
        vm.bookVisible = true;
        vm.loadPending();
      },
      goBook: function () {
        this.slotVisible = false;
        this.openBook(this.curSlot);
      },
      loadPending: function () {
        var vm = this;
        vm.pendLoading = true;
        var q = '/api/ris/request/page?status=0&page=1&size=50';
        var kw = (vm.pendKw || '').trim();
        if (kw) { q += '&keyword=' + encodeURIComponent(kw); }
        HIS.get(q).then(function (d) {
          var rows = (d && d.records) || [];
          /* 急诊优先(稳定排序: 组内保持接口的新单在前) */
          rows.sort(function (a, b) {
            return (b.isUrgent === 1 ? 1 : 0) - (a.isUrgent === 1 ? 1 : 0);
          });
          vm.pendRows = rows;
          vm.bookSel = null;
        }).catch(HIS.notifyError).finally(function () { vm.pendLoading = false; });
      },
      pendSelChange: function (r) { this.bookSel = r || null; },
      onBookClosed: function () {
        this.bookSel = null;
        this.pendRows = [];
      },
      submitBook: function () {
        var vm = this;
        if (!vm.bookSlot || !vm.bookSel) { return; }
        vm.booking = true;
        HIS.post('/api/ris/schedule/book', { scheduleId: vm.bookSlot.id, requestId: vm.bookSel.id })
          .then(function () {
            HIS.notifySuccess('预约成功: ' + orDash(vm.bookSel.patientName) + ' → ' + vm.bookSlot.timeSlot);
            vm.bookVisible = false;
            vm.loadSlots();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.booking = false; });
      },
      /* ===== 取消预约(释放占位, 申请单回退待预约) ===== */
      cancelBooking: function () {
        var vm = this;
        confirmBox('取消该时段的预约占位？申请单将回退为「待预约」并清空分配。', '取消预约')
          .then(function () {
            vm.unbooking = true;
            return HIS.put('/api/ris/schedule/' + HIS.idParam(vm.curSlot.id) + '/cancel-booking',
              { requestId: vm.curSlot.requestId });
          })
          .then(function () {
            HIS.notifySuccess('预约已取消');
            vm.slotVisible = false;
            vm.loadSlots();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } })
          .finally(function () { vm.unbooking = false; });
      }
    }
  };

  /* ========================================================================
   * RisScheduleTemplate 排程周模板维护(周几 x 时段段 CRUD)
   * ====================================================================== */
  HIS.views.RisScheduleTemplate = {
    name: 'RisScheduleTemplate',
    template: [
      '<div class="page-card cd-fill rs">',
      '  <div class="page-title">排程周模板<span class="rs-hint" style="margin-left:10px;font-weight:400">定义设备每周排班段, 供「生成号源」展开为具体日期排程</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="deviceFilter" clearable placeholder="全部设备" size="small" style="width:236px" @change="loadTpls">',
      '      <el-option v-for="d in devices" :key="d.id" :label="d.deviceName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-button size="small" :loading="loading" @click="loadTpls">刷新</el-button>',
      '    <el-button type="primary" size="small" style="margin-left:auto" @click="openCreate">新增模板</el-button>',
      '  </div>',
      '  <el-table :data="tpls" v-loading="loading" border size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="52" align="center"></el-table-column>',
      '    <el-table-column label="设备" min-width="180" show-overflow-tooltip><template #default="s">{{ devName(s.row.deviceId) }}</template></el-table-column>',
      '    <el-table-column label="周几" width="80" align="center"><template #default="s">{{ dayLabel(s.row.dayOfWeek) }}</template></el-table-column>',
      '    <el-table-column label="开始时间" width="92" align="center"><template #default="s">{{ padTime(s.row.slotStart) || "-" }}</template></el-table-column>',
      '    <el-table-column label="结束时间" width="92" align="center"><template #default="s">{{ padTime(s.row.slotEnd) || "-" }}</template></el-table-column>',
      '    <el-table-column label="时长(分钟)" width="92" align="center"><template #default="s">{{ orDash(s.row.slotDuration) }}</template></el-table-column>',
      '    <el-table-column label="最大人数" width="84" align="center"><template #default="s">{{ orDash(s.row.maxPatients) }}</template></el-table-column>',
      '    <el-table-column label="默认技师" width="110" show-overflow-tooltip><template #default="s">{{ staffName(s.row.technicianId) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="82" align="center"><template #default="s"><el-tag size="small" :type="s.row.enabled === 1 ? \'success\' : \'info\'">{{ s.row.enabled === 1 ? "启用" : "停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="120" fixed="right" class-name="op-nowrap">',
      '      <template #default="s">',
      '        <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button link type="danger" @click="doDelete(s.row)">删除</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      /* ===== 新增/编辑弹窗 ===== */
      '  <el-dialog v-model="dlgVisible" :title="form.id ? \'编辑周模板\' : \'新增周模板\'" width="560px" append-to-body :close-on-click-modal="false">',
      '    <el-form :model="form" label-width="110px">',
      '      <el-form-item label="设备" required>',
      '        <el-select v-model="form.deviceId" placeholder="选择设备" style="width:100%">',
      '          <el-option v-for="d in devices" :key="d.id" :label="d.deviceName" :value="d.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="周几" required>',
      '        <el-select v-model="form.dayOfWeek" style="width:100%">',
      '          <el-option v-for="n in 7" :key="n" :label="dayLabel(n)" :value="n"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="时段起止" required>',
      '        <el-time-select v-model="form.slotStart" start="00:00" step="00:15" end="23:45" placeholder="开始" style="width:150px"></el-time-select>',
      '        <span style="margin:0 8px;color:var(--yb-ink-3)">至</span>',
      '        <el-time-select v-model="form.slotEnd" start="00:00" step="00:15" end="23:45" placeholder="结束" style="width:150px"></el-time-select>',
      '      </el-form-item>',
      '      <el-form-item label="时段时长(分钟)">',
      '        <el-input-number v-model="form.slotDuration" :min="5" :max="240" :step="5" controls-position="right" style="width:150px"></el-input-number>',
      '        <span class="rs-hint" style="margin-left:8px">不足5分钟按15分钟执行</span>',
      '      </el-form-item>',
      '      <el-form-item label="最大检查人数">',
      '        <el-input-number v-model="form.maxPatients" :min="1" :max="99" controls-position="right" style="width:150px"></el-input-number>',
      '      </el-form-item>',
      '      <el-form-item label="默认技师">',
      '        <el-select v-model="form.technicianId" clearable filterable placeholder="选填" style="width:100%">',
      '          <el-option v-for="st in staffOpts" :key="st.id" :label="st.staffName" :value="st.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="是否启用">',
      '        <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"></el-switch>',
      '        <span class="rs-hint" style="margin-left:8px">停用后生成号源时不再展开该模板段</span>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dlgVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        devices: DEMO_DEVICES,
        deviceFilter: null,
        tpls: [],
        loading: false,
        dlgVisible: false,
        saving: false,
        staffOpts: [],
        staffLoaded: false,
        form: blankTpl(null)
      };
    },
    created: function () {
      this.loadTpls();
      this.loadStaff();
    },
    methods: {
      /* ===== 展示 ===== */
      orDash: orDash,
      padTime: padTime,
      devName: devNameOf,
      dayLabel: function (v) { return DAY_OF_WEEK[v] || orDash(v); },
      staffName: function (id) {
        var k = HIS.idKey(id);
        if (!k) { return '-'; }
        for (var i = 0; i < this.staffOpts.length; i++) {
          if (HIS.sameId(this.staffOpts[i].id, k)) { return this.staffOpts[i].staffName || '-'; }
        }
        return '-';
      },
      /* ===== 数据 ===== */
      loadTpls: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/ris/schedule/template';
        if (vm.deviceFilter) { q += '?deviceId=' + HIS.idParam(vm.deviceFilter); }
        HIS.get(q).then(function (d) {
          vm.tpls = d || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 技师下拉(默认技师): 非关键路径, 失败静默不阻断模板维护 */
      loadStaff: function () {
        var vm = this;
        if (vm.staffLoaded) { return; }
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('技师') + '&withSubOrgs=false')
          .then(function (d) { vm.staffOpts = d || []; })
          .catch(function () { /* 静默 */ })
          .finally(function () { vm.staffLoaded = true; });
      },
      /* ===== 编辑 ===== */
      openCreate: function () {
        this.form = blankTpl(this.deviceFilter || DEMO_DEVICES[0].id);
        this.dlgVisible = true;
      },
      openEdit: function (row) {
        this.form = {
          id: row.id,
          deviceId: row.deviceId,
          dayOfWeek: row.dayOfWeek,
          slotStart: padTime(row.slotStart),
          slotEnd: padTime(row.slotEnd),
          slotDuration: row.slotDuration || 30,
          maxPatients: row.maxPatients || 2,
          technicianId: row.technicianId || null,
          enabled: row.enabled === 0 ? 0 : 1
        };
        this.dlgVisible = true;
      },
      submit: function () {
        var vm = this;
        var f = vm.form;
        if (!f.deviceId) { HIS.notifyError('请选择设备'); return; }
        if (!f.dayOfWeek) { HIS.notifyError('请选择周几'); return; }
        if (!f.slotStart || !f.slotEnd) { HIS.notifyError('请选择时段起止时间'); return; }
        if (f.slotEnd <= f.slotStart) { HIS.notifyError('时段结束时间必须晚于开始时间'); return; }
        var isEdit = !!f.id;
        var body = {
          deviceId: f.deviceId,
          dayOfWeek: f.dayOfWeek,
          slotStart: f.slotStart,
          slotEnd: f.slotEnd,
          slotDuration: f.slotDuration || 30,
          maxPatients: f.maxPatients || 2,
          technicianId: f.technicianId,
          enabled: f.enabled
        };
        vm.saving = true;
        var p = isEdit
          ? HIS.put('/api/ris/schedule/template/' + HIS.idParam(f.id), body)
          : HIS.post('/api/ris/schedule/template', body);
        p.then(function () {
          HIS.notifySuccess(isEdit ? '周模板已更新' : '周模板已新增');
          vm.dlgVisible = false;
          vm.loadTpls();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      doDelete: function (row) {
        var vm = this;
        confirmBox('删除该条周模板？(逻辑删除, 不影响已生成的排程)', '删除周模板')
          .then(function () { return HIS.del('/api/ris/schedule/template/' + HIS.idParam(row.id)); })
          .then(function () {
            HIS.notifySuccess('周模板已删除');
            vm.loadTpls();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      }
    }
  };
})();
