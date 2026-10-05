/* ============================================================================
 * RIS 检查申请查询(RisRequest) — 全院检查申请单列表 + 状态流转台
 * 后端契约(com.yb.hi.controller.ris.RisRequestController, /api/ris/request):
 *   GET  /page          分页(keyword/sourceType/examType/status/isUrgent/dateFrom/dateTo/page/size)
 *   GET  /{id}          详情四段: request(实体) / patient / device / schedule
 *   PUT  /{id}/status   状态顺序推进(body {status:N}, 仅 N=cur+1 合法, 乐观锁)
 *   PUT  /{id}/cancel   取消(body {reason}, 仅 0待预约/1已预约; 已预约联动释放排程占位)
 *   GET  /by-dept|/by-device   登记台/技师台视角(本页不消费, 由工作台页使用)
 * 状态机: 0待预约 → 1已预约 → 2已登记 → 3检查中 → 4已完成 → 5已报告 → 6已审核; 0/1 → 7已取消
 *   0→1 由排程预约(/api/ris/schedule/book)完成, 本页不提供 0→1 按钮(防止绕过号源占位)。
 * 注册: HIS.views.RisRequest(菜单 ris-request → RisRequest)  |  样式前缀 rr-
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量映射 ===== */
  var STATUS_MAP = { 0: '待预约', 1: '已预约', 2: '已登记', 3: '检查中', 4: '已完成', 5: '已报告', 6: '已审核', 7: '已取消' };
  /* EP el-tag 近似类型(详情弹窗徽标); 列表状态用 rr-st-N 自定义八色 chip(EP 仅五类无法表达八态区分) */
  var STATUS_TYPE = { 0: 'info', 1: '', 2: 'warning', 3: '', 4: 'success', 5: '', 6: 'success', 7: 'danger' };
  var SOURCE_MAP = { 1: '门诊', 2: '住院', 3: '急诊', 4: '体检' };
  var EXAM_TYPE_MAP = { XRAY: 'X线', CT: 'CT', MRI: 'MRI', US: '超声', DSA: 'DSA', ENDO: '内镜' };
  var STATUS_OPTS = [0, 1, 2, 3, 4, 5, 6, 7];
  var SOURCE_OPTS = optsOf(SOURCE_MAP);
  var EXAM_TYPE_OPTS = optsOfStr(EXAM_TYPE_MAP);
  /* 状态推进表: 当前态 → {to 目标态, label 动作名}; 无 0 键(0→1 须走排程预约占位) */
  var ADVANCE = {
    1: { to: 2, label: '登记' },
    2: { to: 3, label: '开始检查' },
    3: { to: 4, label: '完成检查' },
    4: { to: 5, label: '提交报告' },
    5: { to: 6, label: '审核通过' }
  };

  /* ===== 工具 ===== */
  function optsOf(map) {
    return Object.keys(map).map(function (k) { return { value: Number(k), label: map[k] }; });
  }
  function optsOfStr(map) {
    return Object.keys(map).map(function (k) { return { value: k, label: map[k] }; });
  }
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function confirmBox(msg, title) {
    return ElementPlus.ElMessageBox.confirm(msg, title || '操作确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' });
  }
  function isCancel(e) { return e === 'cancel' || e === 'close'; }
  /* 取消原因: 允许留空(后端缺省"医生站取消"), 留痕供审计 */
  function promptCancelReason(requestNo) {
    return ElementPlus.ElMessageBox.prompt(
      '将取消申请单 ' + orDash(requestNo) + '，已预约的将联动释放号源占位。请填写取消原因(可留空):',
      '取消申请单', {
        inputType: 'textarea',
        inputPlaceholder: '如: 患者拒绝检查 / 重复开单 / 病情变化',
        confirmButtonText: '确认取消', cancelButtonText: '返回', type: 'warning'
      });
  }

  /* ===== 样式一次性注入(前缀 rr-, 复用 --yb-* 令牌; 八态 chip 色 + 行状态脊) ===== */
  (function ensureRisRequestStyles() {
    if (document.getElementById('ris-request-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-request-style';
    st.textContent = [
      /* 行状态脊: 首单元格左内描边(与状态 chip 同色系) */
      '.rr tr.rr-rs-0 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-ink-disabled); }',
      '.rr tr.rr-rs-1 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-brand); }',
      '.rr tr.rr-rs-2 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-fill-warning); }',
      '.rr tr.rr-rs-3 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-fill-info); }',
      '.rr tr.rr-rs-4 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-fill-success); }',
      '.rr tr.rr-rs-5 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-fill-purple); }',
      '.rr tr.rr-rs-6 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-fill-teal); }',
      '.rr tr.rr-rs-7 > td.el-table__cell:first-child { box-shadow: inset 3px 0 0 var(--yb-fill-danger); }',
      '.rr tr.rr-urgent td.el-table__cell { background: rgba(217, 96, 95, .05); }',
      '.rr .el-table__row { cursor: pointer; }',
      /* 状态 chip: 八态八色(EP tag 五类不足以区分 1/3 蓝系与 4/5/6 绿紫深绿) */
      '.rr-st { display: inline-block; padding: 0 8px; border-radius: var(--yb-r-pill); font-size: 12px; line-height: 18px; border: 1px solid transparent; white-space: nowrap; }',
      '.rr-st-0 { background: var(--yb-surface-3); border-color: var(--yb-border); color: var(--yb-ink-3); }',
      '.rr-st-1 { background: var(--yb-brand-subtle); border-color: var(--yb-brand-border); color: var(--yb-brand); }',
      '.rr-st-2 { background: var(--yb-warning-bg); border-color: var(--yb-warning-border); color: var(--yb-warning); }',
      '.rr-st-3 { background: var(--yb-info-light); border-color: #cfe0f4; color: var(--yb-info); }',
      '.rr-st-4 { background: var(--yb-success-bg); border-color: var(--yb-success-border); color: var(--yb-success); }',
      '.rr-st-5 { background: #f6eefa; border-color: #e3cdee; color: var(--yb-fill-purple); }',
      '.rr-st-6 { background: var(--yb-fill-teal); border-color: var(--yb-fill-teal); color: #fff; }',
      '.rr-st-7 { background: var(--yb-danger-bg); border-color: var(--yb-danger-border); color: var(--yb-danger); }',
      /* 优先级 chip: 1-2 红 / 3-4 琥珀 / 其余中性 */
      '.rr-pri { display: inline-block; min-width: 22px; padding: 0 6px; border-radius: var(--yb-r-sm); font-size: 12px; line-height: 18px; background: var(--yb-surface-3); color: var(--yb-ink-2); font-variant-numeric: tabular-nums; }',
      '.rr-pri-hi { background: var(--yb-danger-bg); color: var(--yb-danger); font-weight: 600; }',
      '.rr-pri-mid { background: var(--yb-warning-bg); color: var(--yb-warning); }',
      '.rr-dim { color: var(--yb-ink-4); }',
      /* 详情弹窗分段标题 */
      '.rr-sec { margin: 14px 0 8px; padding-left: 9px; border-left: 3px solid var(--yb-brand); font-size: 13px; font-weight: 600; color: var(--yb-ink-1); line-height: 1; }',
      '.rr-sec:first-child { margin-top: 0; }',
      '.rr-detail .el-descriptions__label { color: var(--yb-ink-3); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ========================================================================
   * RisRequest 检查申请查询
   * ====================================================================== */
  HIS.views.RisRequest = {
    name: 'RisRequest',
    template: [
      '<div class="page-card cd-fill rr">',
      '  <div class="page-title">检查申请查询<span class="rr-dim" style="font-size:12px;font-weight:400;margin-left:10px">申请 - 预约 - 检查 - 报告 全流程追踪</span></div>',
      '  <div class="toolbar" style="margin-bottom:8px">',
      '    <el-radio-group v-model="filters.status" size="small" @change="onSearch">',
      '      <el-radio-button label="">全部</el-radio-button>',
      '      <el-radio-button v-for="s in statusOpts" :key="s" :label="s">{{ stLabel(s) }}</el-radio-button>',
      '    </el-radio-group>',
      '    <span class="rr-dim" style="margin-left:auto;font-size:12px">共 {{ total }} 条</span>',
      '  </div>',
      '  <div class="toolbar">',
      '    <el-input v-model="filters.keyword" clearable placeholder="申请单号 / 患者姓名 / 检查项目" size="small" style="width:220px" @keyup.enter="onSearch"></el-input>',
      '    <el-select v-model="filters.sourceType" clearable placeholder="来源" size="small" style="width:104px" @change="onSearch">',
      '      <el-option v-for="o in sourceOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-select v-model="filters.examType" clearable placeholder="检查类型" size="small" style="width:112px" @change="onSearch">',
      '      <el-option v-for="o in examTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="filters.dateRange" type="daterange" size="small" value-format="YYYY-MM-DD"',
      '      range-separator="至" start-placeholder="申请日期起" end-placeholder="止" style="width:236px"></el-date-picker>',
      '    <el-checkbox v-model="filters.urgent" @change="onSearch">仅急诊</el-checkbox>',
      '    <el-button type="primary" size="small" @click="onSearch">查询</el-button>',
      '    <el-button size="small" @click="resetFilters">重置</el-button>',
      '  </div>',
      '  <el-table :data="rows" border size="small" v-loading="loading" height="100%" :row-class-name="rowCls" @row-click="openDetail">',
      '    <el-table-column label="序号" width="52" align="center"><template #default="s">{{ seqNo(s.$index) }}</template></el-table-column>',
      '    <el-table-column label="申请单号" width="150"><template #default="s"><el-button link type="primary" @click.stop="openDetail(s.row)">{{ s.row.requestNo }}</el-button></template></el-table-column>',
      '    <el-table-column label="患者姓名" width="90" show-overflow-tooltip><template #default="s">{{ orDash(s.row.patientName) }}</template></el-table-column>',
      '    <el-table-column label="来源" width="64" align="center"><template #default="s">{{ srcLabel(s.row.sourceType) }}</template></el-table-column>',
      '    <el-table-column label="检查类型" width="80" align="center"><template #default="s">{{ examLabel(s.row.examType) }}</template></el-table-column>',
      '    <el-table-column label="检查部位" min-width="150" show-overflow-tooltip><template #default="s">{{ orDash(s.row.bodyPart) }}</template></el-table-column>',
      '    <el-table-column label="申请科室" width="108" show-overflow-tooltip><template #default="s">{{ orDash(s.row.applyDeptName) }}</template></el-table-column>',
      '    <el-table-column label="执行科室" width="108" show-overflow-tooltip><template #default="s">{{ orDash(s.row.targetDeptName) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="84" align="center"><template #default="s"><span class="rr-st" :class="\'rr-st-\' + (s.row.status == null ? 0 : s.row.status)">{{ stLabel(s.row.status) }}</span></template></el-table-column>',
      '    <el-table-column label="优先级" width="70" align="center"><template #default="s"><span class="rr-pri" :class="priCls(s.row.priority)" :title="priTitle(s.row.priority)">{{ orDash(s.row.priority) }}</span></template></el-table-column>',
      '    <el-table-column label="急诊" width="64" align="center"><template #default="s"><el-tag v-if="s.row.isUrgent === 1" type="danger" size="small" effect="dark">急诊</el-tag><span v-else class="rr-dim">-</span></template></el-table-column>',
      '    <el-table-column label="申请时间" width="150"><template #default="s"><span class="rr-dim">{{ orDash(s.row.applyTime) }}</span></template></el-table-column>',
      '    <el-table-column label="操作" width="170" fixed="right" class-name="op-nowrap">',
      '      <template #default="s">',
      '        <el-button link type="primary" @click.stop="openDetail(s.row)">详情</el-button>',
      '        <el-button v-if="s.row.status <= 1" link type="danger" @click.stop="doCancel(s.row)">取消</el-button>',
      '        <el-button v-if="advanceOf(s.row.status)" link type="success" @click.stop="doAdvance(s.row)">{{ advanceOf(s.row.status).label }}</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page"',
      '    @current-change="onPage" @size-change="onSize"></el-pagination>',
      /* ===== 详情弹窗(四段: 基本信息/患者/临床/设备排程) ===== */
      '  <el-dialog v-model="detailVisible" title="检查申请详情" width="860px" top="4vh" custom-class="form-dialog-scroll" append-to-body>',
      '    <div v-loading="detailLoading" class="rr-detail">',
      '      <template v-if="req">',
      '        <el-alert v-if="req.status === 7 && req.cancelReason" type="error" :closable="false" show-icon style="margin-bottom:12px" :title="\'取消原因: \' + req.cancelReason"></el-alert>',
      '        <div class="rr-sec">基本信息</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="申请单号">{{ orDash(req.requestNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="状态"><el-tag size="small" :type="stTag(req.status)">{{ stLabel(req.status) }}</el-tag></el-descriptions-item>',
      '          <el-descriptions-item label="来源">{{ srcLabel(req.sourceType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="检查类型">{{ examLabel(req.examType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="检查部位" :span="2">{{ orDash(req.bodyPart) }}</el-descriptions-item>',
      '          <el-descriptions-item label="收费项目" :span="2">{{ orDash(req.chargeItemName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="是否急诊"><el-tag v-if="req.isUrgent === 1" type="danger" size="small" effect="dark">急诊</el-tag><span v-else>否</span></el-descriptions-item>',
      '          <el-descriptions-item label="优先级">{{ orDash(req.priority) }}<span class="rr-dim" style="margin-left:4px">(1最高-9最低)</span></el-descriptions-item>',
      '          <el-descriptions-item label="缴费状态">{{ req.paidFlag === 1 ? "已缴费" : (req.paidFlag === 0 ? "未缴费" : "-") }}</el-descriptions-item>',
      '          <el-descriptions-item label="检查费用">{{ req.examCharge != null ? "￥" + req.examCharge : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请科室">{{ orDash(req.applyDeptName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="执行科室">{{ orDash(req.targetDeptName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请医生">{{ orDash(req.applyDoctorName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请时间">{{ orDash(req.applyTime) }}</el-descriptions-item>',
      '          <el-descriptions-item label="预约检查时间" :span="2">{{ orDash(req.scheduledTime) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="rr-sec">患者信息</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="患者姓名">{{ orDash(pt.name) }}</el-descriptions-item>',
      '          <el-descriptions-item label="性别">{{ orDash(pt.genderName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="年龄">{{ orDash(pt.age) }}</el-descriptions-item>',
      '          <el-descriptions-item label="患者编号">{{ orDash(pt.patientNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="联系电话" :span="2">{{ orDash(pt.phone) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="rr-sec">临床信息</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="临床诊断" :span="2">{{ orDash(req.clinicalDiagnosis) }}</el-descriptions-item>',
      '          <el-descriptions-item label="检查目的" :span="2">{{ orDash(req.examPurpose) }}</el-descriptions-item>',
      '          <el-descriptions-item label="简要病史" :span="2">{{ orDash(req.clinicalHistory) }}</el-descriptions-item>',
      '          <el-descriptions-item label="造影方式">{{ orDash(req.contrastMode) }}</el-descriptions-item>',
      '          <el-descriptions-item label="过敏史(造影剂)">{{ orDash(req.allergyInfo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="是否妊娠">{{ req.pregnantFlag === 1 ? "是" : (req.pregnantFlag === 0 ? "否" : "-") }}</el-descriptions-item>',
      '          <el-descriptions-item label="是否隔离">{{ req.isIsolation === 1 ? "是" : "否" }}</el-descriptions-item>',
      '          <el-descriptions-item label="备注" :span="2">{{ orDash(req.notes) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="rr-sec">设备与排程</div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="分配设备">{{ device ? (device.deviceName + "（" + orDash(device.deviceCode) + "）") : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="Modality/机房">{{ device ? (orDash(device.modality) + " / " + orDash(device.roomNo)) : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="排程日期">{{ sched ? orDash(sched.scheduleDate) : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="排程时段">{{ sched ? orDash(sched.timeSlot) : "-" }}</el-descriptions-item>',
      '          <el-descriptions-item label="时段占用" :span="2">{{ sched ? ((sched.bookedCount || 0) + " / " + (sched.maxPatients || 0)) : "-" }}</el-descriptions-item>',
      '        </el-descriptions>',
      '      </template>',
      '      <el-empty v-else-if="!detailLoading" description="暂无详情数据"></el-empty>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="detailVisible = false">关闭</el-button>',
      '      <el-button v-if="req && req.status <= 1" type="danger" plain @click="doCancel(req)">取消申请</el-button>',
      '      <el-button v-if="req && advanceOf(req.status)" type="primary" :loading="acting" @click="doAdvance(req)">{{ advanceOf(req.status).label }}</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        statusOpts: STATUS_OPTS,
        sourceOpts: SOURCE_OPTS,
        examTypeOpts: EXAM_TYPE_OPTS,
        filters: { keyword: '', status: '', sourceType: null, examType: null, dateRange: [], urgent: false },
        rows: [],
        loading: false,
        page: 1,
        size: 20,
        total: 0,
        detailVisible: false,
        detailLoading: false,
        detailId: null,
        detail: null,
        acting: false
      };
    },
    computed: {
      req: function () { return (this.detail && this.detail.request) || null; },
      pt: function () { return (this.detail && this.detail.patient) || {}; },
      device: function () { return (this.detail && this.detail.device) || null; },
      sched: function () { return (this.detail && this.detail.schedule) || null; }
    },
    created: function () {
      this.load();
    },
    methods: {
      /* ===== 列表 ===== */
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/ris/request/page?page=' + vm.page + '&size=' + vm.size;
        var f = vm.filters;
        if (f.keyword && f.keyword.trim()) { q += '&keyword=' + encodeURIComponent(f.keyword.trim()); }
        /* 状态页签: '' 表示全部(注意 0=待预约 是有效筛选值, 须与空串严格区分) */
        if (f.status !== '' && f.status !== null && f.status !== undefined) { q += '&status=' + f.status; }
        if (f.sourceType !== null && f.sourceType !== undefined) { q += '&sourceType=' + f.sourceType; }
        if (f.examType) { q += '&examType=' + encodeURIComponent(f.examType); }
        if (f.dateRange && f.dateRange.length === 2) {
          q += '&dateFrom=' + f.dateRange[0] + '&dateTo=' + f.dateRange[1];
        }
        if (f.urgent) { q += '&isUrgent=1'; }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onSearch: function () { this.page = 1; this.load(); },
      resetFilters: function () {
        this.filters = { keyword: '', status: '', sourceType: null, examType: null, dateRange: [], urgent: false };
        this.page = 1;
        this.load();
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      refreshAll: function () {
        this.load();
        if (this.detailVisible && this.detailId) { this.loadDetail(); }
      },
      /* ===== 展示映射 ===== */
      stLabel: function (v) { var t = STATUS_MAP[v]; return t === undefined ? orDash(v) : t; },
      stTag: function (v) { var t = STATUS_TYPE[v]; return t === undefined ? 'info' : t; },
      srcLabel: function (v) { return SOURCE_MAP[v] || orDash(v); },
      examLabel: function (v) { return EXAM_TYPE_MAP[v] || orDash(v); },
      advanceOf: function (status) { return ADVANCE[status] || null; },
      rowCls: function (o) {
        var r = o.row || {};
        return 'rr-rs-' + (r.status == null ? 0 : r.status) + (r.isUrgent === 1 ? ' rr-urgent' : '');
      },
      priCls: function (p) {
        if (p === null || p === undefined) { return ''; }
        return p <= 2 ? 'rr-pri-hi' : (p <= 4 ? 'rr-pri-mid' : '');
      },
      priTitle: function (p) {
        return (p === null || p === undefined) ? '未设置优先级' : ('优先级 ' + p + ' (1最高-9最低)');
      },
      orDash: orDash,
      /* ===== 详情 ===== */
      openDetail: function (row) {
        if (!row || row.id == null) { return; }
        this.detailId = row.id;
        this.detail = null;
        this.detailVisible = true;
        this.loadDetail();
      },
      loadDetail: function () {
        var vm = this;
        if (!vm.detailId) { return; }
        vm.detailLoading = true;
        HIS.get('/api/ris/request/' + HIS.idParam(vm.detailId))
          .then(function (d) { vm.detail = d || null; })
          .catch(HIS.notifyError)
          .finally(function () { vm.detailLoading = false; });
      },
      /* ===== 状态流转 ===== */
      doAdvance: function (row) {
        var vm = this;
        var adv = ADVANCE[row.status];
        if (!adv) { return; }
        confirmBox('将申请单 ' + orDash(row.requestNo) + ' 推进为「' + STATUS_MAP[adv.to] + '」？', '状态推进')
          .then(function () {
            vm.acting = true;
            return HIS.put('/api/ris/request/' + HIS.idParam(row.id) + '/status', { status: adv.to });
          })
          .then(function () {
            HIS.notifySuccess('已推进为「' + STATUS_MAP[adv.to] + '」');
            vm.refreshAll();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } })
          .finally(function () { vm.acting = false; });
      },
      doCancel: function (row) {
        var vm = this;
        promptCancelReason(row.requestNo)
          .then(function (r) {
            vm.acting = true;
            var reason = (r && r.value) ? r.value.trim() : '';
            return HIS.put('/api/ris/request/' + HIS.idParam(row.id) + '/cancel', { reason: reason });
          })
          .then(function () {
            HIS.notifySuccess('申请单已取消');
            vm.refreshAll();
          })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } })
          .finally(function () { vm.acting = false; });
      }
    }
  };
})();
