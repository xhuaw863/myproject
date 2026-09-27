/* 医技管理: 标本管理(采集/签收/拒收) + 报告书写(草稿/提交/审核双签) + 危急值闭环 + 危急值规则 + 报告查询 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 医技模块私有样式一次性注入(无构建架构, 避免改动公共 css 与其他会话冲突) */
  (function ensureMedtechStyles() {
    if (document.getElementById('medtech-style')) { return; }
    var st = document.createElement('style');
    st.id = 'medtech-style';
    st.textContent = [
      '.mt-report-item { border:1px solid #e4e7ed; border-radius:6px; padding:8px 10px; margin-bottom:8px; cursor:pointer; transition:all .15s; }',
      '.mt-report-item:hover { border-color:#409eff; background:#f5f9ff; }',
      '.mt-report-item.active { border-color:#409eff; background:#ecf5ff; box-shadow:0 0 0 1px #409eff inset; }',
      '.mt-report-item .no { font-weight:600; color:#303133; font-size:13px; }',
      '.mt-report-item .sub { color:#909399; font-size:12px; margin-top:3px; display:flex; justify-content:space-between; }',
      '@keyframes mtBlink { 50% { opacity:.2; } }',
      '.mt-blink { animation: mtBlink 1s infinite; font-weight:800; color:#f56c6c; }',
      '.mt-flag-high { color:#f56c6c; font-weight:700; }',
      '.mt-flag-low { color:#409eff; font-weight:700; }',
      '.el-table__body tr.mt-warning-row > td.el-table__cell { background-color:#fdf6ec; }',
      '.mt-tube-dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:4px; vertical-align:middle; border:1px solid rgba(0,0,0,.15); }',
      '.mt-banner { background:linear-gradient(135deg,#f0f7ff 0%,#fafcff 100%); border:1px solid #d9ecff; border-radius:8px; padding:10px 14px; margin-bottom:12px; }',
      '.mt-banner .name { font-size:17px; font-weight:700; color:#303133; margin-right:10px; }',
      '.mt-placeholder-img { border:1px dashed #c0c4cc; border-radius:6px; color:#909399; text-align:center; padding:28px 0; font-size:13px; background:#fafafa; }',
      '.mt-steps { padding:2px 0; }',
      '.mt-steps .el-step__icon { width:16px; height:16px; }',
      '.mt-steps .el-step__icon-inner { font-size:9px; }',
      '.mt-steps .el-step__title { font-size:11px; line-height:15px; margin-top:1px; }',
      '.mt-steps .el-step.is-horizontal .el-step__line { top:7px; }',
      '.mt-steps .el-step__head.is-process .el-step__icon { border-color:#f56c6c; color:#f56c6c; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 本地受控枚举(非国标字典) ===== */
  var SPEC_TYPES = [
    { v: 'blood', l: '血液', t: 'danger' },
    { v: 'urine', l: '尿液', t: 'warning' },
    { v: 'stool', l: '粪便', t: 'warning' },
    { v: 'sputum', l: '痰液', t: 'info' },
    { v: 'other', l: '其他', t: 'info' }
  ];
  var TUBE_COLORS = { '红': '#f56c6c', '紫': '#b37feb', '蓝': '#409eff', '黑': '#303133', '绿': '#67c23a', '灰': '#909399', '黄': '#e6a23c' };
  var SPECIMEN_STATUS = [
    { v: 0, l: '待采集', t: 'warning' },
    { v: 1, l: '已采集', t: 'primary' },
    { v: 2, l: '运送中', t: 'primary' },
    { v: 3, l: '已签收', t: 'success' },
    { v: -1, l: '已拒收', t: 'danger' }
  ];
  var REPORT_TYPES = [
    { v: 'lab', l: '检验', t: 'primary' },
    { v: 'exam', l: '检查', t: 'warning' }
  ];
  var REPORT_STATUS = [
    { v: 0, l: '待写', t: 'info' },
    { v: 1, l: '待审', t: 'warning' },
    { v: 2, l: '已发布', t: 'success' },
    { v: 3, l: '已作废', t: 'danger' }
  ];
  var CRITICAL_STATUS = ['已发现', '已复核', '已通知', '已接收', '已处置'];
  var CRITICAL_STATUS_TAG = ['danger', 'warning', 'warning', 'primary', 'success'];
  var REJECT_REASONS = ['溶血', '标本量不足', '标识不清', '容器错误', '项目不符', '其他'];
  var PATIENT_TYPES = [
    { v: 'adult', l: '成人', t: 'warning' },
    { v: 'child', l: '儿童', t: 'success' }
  ];
  function findBy(list, prop, v) {
    for (var i = 0; i < list.length; i++) { if (list[i][prop] === v) { return list[i]; } }
    return null;
  }
  function specLabel(v) { var s = findBy(SPEC_TYPES, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function specTag(v) { var s = findBy(SPEC_TYPES, 'v', v); return s ? s.t : 'info'; }
  function specStatusLabel(v) { var s = findBy(SPECIMEN_STATUS, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function specStatusTag(v) { var s = findBy(SPECIMEN_STATUS, 'v', v); return s ? s.t : 'info'; }
  function reportTypeLabel(v) { var s = findBy(REPORT_TYPES, 'v', v); return s ? s.l : (v == null || v === '' ? '-' : v); }
  function reportTypeTag(v) { var s = findBy(REPORT_TYPES, 'v', v); return s ? s.t : 'info'; }
  function reportStatusLabel(v) { var s = findBy(REPORT_STATUS, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function reportStatusTag(v) { var s = findBy(REPORT_STATUS, 'v', v); return s ? s.t : 'info'; }
  function patientTypeLabel(v) {
    var s = findBy(PATIENT_TYPES, 'v', v);
    return s ? s.l : '通用';
  }
  function patientTypeTag(v) {
    var s = findBy(PATIENT_TYPES, 'v', v);
    return s ? s.t : 'info';
  }
  /* LocalDateTime 序列化为 ISO(含T), 展示转空格并截到秒 */
  function fmtTime(v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; }
  /* 时间字符串转时间戳(兼容 'yyyy-MM-dd HH:mm:ss' 与 ISO) */
  function ts(v) {
    if (!v) { return 0; }
    var t = new Date(String(v).replace(' ', 'T')).getTime();
    return isNaN(t) ? 0 : t;
  }
  /* 前端异常标志即时计算(与后端 calcAbnormal 同口径: 0正常 1偏高 2偏低; 危急由提交时后端升级) */
  function calcAbnormal(value, low, high) {
    var v = parseFloat(value);
    if (value === '' || value == null || isNaN(v)) { return 0; }
    var lo = low === '' || low == null ? null : parseFloat(low);
    var hi = high === '' || high == null ? null : parseFloat(high);
    if (hi != null && !isNaN(hi) && v > hi) { return 1; }
    if (lo != null && !isNaN(lo) && v < lo) { return 2; }
    return 0;
  }
  /* 异常标志展示: 文字与配色(1/3 偏高红, 2/4 偏低蓝; 3/4 危急值加粗闪烁) */
  function flagText(f) { return ({ 1: '↑偏高', 2: '↓偏低', 3: '↑↑危急', 4: '↓↓危急' })[f] || '正常'; }
  function flagClass(f) {
    if (f === 1 || f === 3) { return 'mt-flag-high' + (f === 3 ? ' mt-blink' : ''); }
    if (f === 2 || f === 4) { return 'mt-flag-low' + (f === 4 ? ' mt-blink' : ''); }
    return '';
  }

  /* ================= 1. 标本管理工作台(采集/签收/拒收) =================
   * 三Tab: 待采集(status=0) / 运送中(status=1,2) / 已签收(status=3); 采集与签收均为后端乐观锁。
   */
  HIS.views.MedtechSpecimen = {
    data: function () {
      return {
        tab: '0',
        loading: false, list: [], total: 0, page: 1, size: 20,
        selection: [], collecting: false,
        /* 拒收弹窗 */
        rejectVisible: false, rejectRow: null, rejectReason: '', rejectNote: '', rejecting: false,
        /* 生成标本弹窗(从检验类医嘱生成) */
        genVisible: false, genOrderId: null, generating: false,
        specTypeLabel: specLabel, specTypeTag: specTag, specStatusLabel: specStatusLabel, specStatusTag: specStatusTag,
        tubeColors: TUBE_COLORS, rejectReasons: REJECT_REASONS, fmtTime: fmtTime
      };
    },
    computed: {
      statusParam: function () {
        if (this.tab === '12') { return '1,2'; }
        return this.tab;
      }
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/medtech/specimens?page=' + vm.page + '&size=' + vm.size;
        if (vm.statusParam !== '') { q += '&status=' + vm.statusParam; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () { this.page = 1; this.selection = []; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSelection: function (rows) { this.selection = rows; },
      /* 单条采集(乐观锁 0->1) */
      doCollect: function (row) {
        var vm = this;
        vm.collecting = true;
        HIS.post('/api/medtech/specimen/' + encodeURIComponent(row.barcode) + '/collect')
          .then(function () { HIS.notifySuccess('标本 ' + row.barcode + ' 已采集'); vm.load(); })
          .catch(HIS.notifyError)
          .finally(function () { vm.collecting = false; });
      },
      /* 批量采集(部分成功保留, 失败条码提示) */
      doBatchCollect: function () {
        var vm = this;
        if (!vm.selection.length) { ElementPlus.ElMessage.warning('请先勾选待采集标本'); return; }
        ElementPlus.ElMessageBox.confirm('确认批量采集选中的 ' + vm.selection.length + ' 份标本？', '批量采集',
          { type: 'info', confirmButtonText: '确认采集', cancelButtonText: '取消' }
        ).then(function () {
          vm.collecting = true;
          return HIS.post('/api/medtech/specimen/batch-collect', {
            barcodes: vm.selection.map(function (r) { return r.barcode; })
          });
        }).then(function (d) {
          var msg = '批量采集完成: 成功 ' + ((d && d.success) || 0) + ' 条';
          if (d && d.failed && d.failed.length) { msg += ', 失败 ' + d.failed.length + ' 条(' + d.failed.join('、') + ')'; }
          HIS.notifySuccess(msg);
          vm.selection = [];
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.collecting = false; });
      },
      /* 签收(乐观锁 1->3) */
      doReceive: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认签收标本 ' + row.barcode + '？签收后将进入检验环节。', '标本签收',
          { type: 'info', confirmButtonText: '确认签收', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.post('/api/medtech/specimen/' + encodeURIComponent(row.barcode) + '/receive');
        }).then(function () { HIS.notifySuccess('标本已签收'); vm.load(); })
          .catch(function (e) {
            if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
          });
      },
      openReject: function (row) {
        this.rejectRow = row;
        this.rejectReason = '';
        this.rejectNote = '';
        this.rejectVisible = true;
      },
      submitReject: function () {
        var vm = this;
        if (!vm.rejectReason) { ElementPlus.ElMessage.warning('请选择拒收原因'); return; }
        var reason = vm.rejectReason;
        if (vm.rejectReason === '其他' && !vm.rejectNote) {
          ElementPlus.ElMessage.warning('选择"其他"时请填写备注说明'); return;
        }
        if (vm.rejectNote) { reason += '; 备注: ' + vm.rejectNote; }
        vm.rejecting = true;
        HIS.post('/api/medtech/specimen/' + encodeURIComponent(vm.rejectRow.barcode) + '/reject', { reason: reason })
          .then(function () { HIS.notifySuccess('标本已拒收'); vm.rejectVisible = false; vm.load(); })
          .catch(HIS.notifyError)
          .finally(function () { vm.rejecting = false; });
      },
      openGenerate: function () { this.genOrderId = null; this.genVisible = true; },
      submitGenerate: function () {
        var vm = this;
        if (!vm.genOrderId) { ElementPlus.ElMessage.warning('请输入检验类医嘱ID'); return; }
        vm.generating = true;
        HIS.post('/api/medtech/specimen/generate', { orderId: vm.genOrderId })
          .then(function (list) {
            HIS.notifySuccess('已生成 ' + ((list && list.length) || 0) + ' 份标本');
            vm.genVisible = false;
            vm.tab = '0'; vm.page = 1;
            vm.load();
          }).catch(HIS.notifyError)
          .finally(function () { vm.generating = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">标本管理 <span style="font-size:12px;color:#909399;font-weight:normal;">(检验标本全流程: 生成 · 采集 · 运送 · 签收/拒收)</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',
      '    <el-tab-pane label="待采集" name="0"></el-tab-pane>',
      '    <el-tab-pane label="运送中" name="12"></el-tab-pane>',
      '    <el-tab-pane label="已签收" name="3"></el-tab-pane>',
      '  </el-tabs>',
      '  <div class="toolbar">',
      '    <el-button type="primary" :disabled="!selection.length" :loading="collecting" @click="doBatchCollect">批量采集({{ selection.length }})</el-button>',
      '    <el-button @click="openGenerate">生成标本</el-button>',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 份标本</span>',
      '  </div>',
      '  <el-table :data="list" border size="small" v-loading="loading" @selection-change="onSelection">',
      '    <el-table-column v-if="tab===\'0\'" type="selection" width="42"></el-table-column>',
      '    <el-table-column type="index" label="序号" width="55" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="barcode" label="条码号" width="150"><template #default="s"><b style="font-family:Consolas,monospace;">{{ s.row.barcode }}</b></template></el-table-column>',
      '    <el-table-column label="患者" min-width="130"><template #default="s">{{ s.row.patientName || \'-\' }}<span style="color:#909399;font-size:12px;" v-if="s.row.genderName || s.row.age"> ({{ s.row.genderName || \'\' }}{{ s.row.age != null ? s.row.age + \'岁\' : \'\' }})</span></template></el-table-column>',
      '    <el-table-column label="标本类型" width="90" align="center"><template #default="s"><el-tag size="small" :type="specTypeTag(s.row.specimenType)">{{ specTypeLabel(s.row.specimenType) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="采血管" width="90" align="center"><template #default="s">',
      '      <span v-if="s.row.tubeColor"><span class="mt-tube-dot" :style="{background: tubeColors[s.row.tubeColor] || \'#ccc\'}"></span>{{ s.row.tubeColor }}管</span>',
      '      <span v-else style="color:#909399;">免管</span>',
      '    </template></el-table-column>',
      '    <el-table-column label="关联医嘱" width="150"><template #default="s">{{ s.row.orderNo || (\'医嘱#\' + (s.row.orderId || \'-\')) }}<div style="color:#909399;font-size:12px;">{{ s.row.orderType || \'\' }}</div></template></el-table-column>',
      '    <el-table-column label="采集" width="130"><template #default="s"><div>{{ s.row.collectNurseName || \'-\' }}</div><div style="color:#909399;font-size:12px;">{{ fmtTime(s.row.collectTime) }}</div></template></el-table-column>',
      '    <el-table-column label="签收" width="130"><template #default="s"><div>{{ s.row.receiveTechName || \'-\' }}</div><div style="color:#909399;font-size:12px;">{{ fmtTime(s.row.receiveTime) }}</div></template></el-table-column>',
      '    <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="specStatusTag(s.row.status)">{{ specStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="拒收原因" min-width="120" show-overflow-tooltip><template #default="s"><span v-if="s.row.rejectReason" style="color:#f56c6c;">{{ s.row.rejectReason }}</span><span v-else style="color:#c0c4cc;">-</span></template></el-table-column>',
      '    <el-table-column label="操作" width="140" fixed="right"><template #default="s">',
      '      <el-button v-if="s.row.status===0" link type="primary" size="small" @click="doCollect(s.row)">采集</el-button>',
      '      <template v-if="s.row.status===1">',
      '        <el-button link type="success" size="small" @click="doReceive(s.row)">签收</el-button>',
      '        <el-button link type="danger" size="small" @click="openReject(s.row)">拒收</el-button>',
      '      </template>',
      '      <span v-if="s.row.status!==0 && s.row.status!==1" style="color:#c0c4cc;font-size:12px;">-</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="rejectVisible" title="标本拒收" width="460px">',
      '    <el-alert v-if="rejectRow" type="warning" :closable="false" show-icon :title="\'拒收标本: \' + rejectRow.barcode + \'(\' + (rejectRow.patientName || \'-\') + \')\'" style="margin-bottom:12px;"></el-alert>',
      '    <el-form label-width="90px">',
      '      <el-form-item label="拒收原因" required>',
      '        <el-select v-model="rejectReason" placeholder="选择拒收原因" style="width:100%">',
      '          <el-option v-for="r in rejectReasons" :key="r" :label="r" :value="r"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="rejectNote" type="textarea" :rows="3" maxlength="150" show-word-limit placeholder="补充说明(选\'其他\'时必填)"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="rejectVisible=false">取 消</el-button><el-button type="danger" :loading="rejecting" @click="submitReject">确认拒收</el-button></template>',
      '  </el-dialog>',
      '  <el-dialog v-model="genVisible" title="生成标本(检验类医嘱)" width="430px">',
      '    <el-form label-width="90px">',
      '      <el-form-item label="医嘱ID" required><el-input v-model="genOrderId" placeholder="输入检验类医嘱单ID(数字)"></el-input></el-form-item>',
      '      <div style="margin:-6px 0 6px 90px;color:#909399;font-size:12px;">按明细项目推断标本类型与采血管, 同类型同管色合管一份; 已生成过则直接返回已有标本</div>',
      '    </el-form>',
      '    <template #footer><el-button @click="genVisible=false">取 消</el-button><el-button type="primary" :loading="generating" @click="submitGenerate">生成</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 2. 报告工作站(左右分栏: 左侧列表 / 右侧书写与审核) =================
   * 报告状态: 0待写(可编辑保存/提交) -> 1待审(可通过/退回) -> 2已发布(只读); 退回 1->0 可继续编辑。
   * 检验类(lab)持结果明细可编辑表格; 其他类型所见/结论文本 + 关键图像预留。
   */
  HIS.views.MedtechReport = {
    data: function () {
      return {
        tab: '0', keyword: '',
        loading: false, list: [], total: 0, page: 1, size: 20,
        current: null, detailLoading: false,
        editItems: [], findings: '', conclusion: '',
        saving: false, submitting: false, reviewing: false,
        /* 危急值弹窗 */
        criticalVisible: false, criticalList: [],
        /* 新建报告弹窗 */
        draftVisible: false, draftOrderId: null, draftType: 'lab', creatingDraft: false,
        reportTypes: REPORT_TYPES, fmtTime: fmtTime, reportTypeLabel: reportTypeLabel, reportTypeTag: reportTypeTag,
        reportStatusLabel: reportStatusLabel, reportStatusTag: reportStatusTag,
        flagText: flagText, flagClass: flagClass, calcAbnormal: calcAbnormal
      };
    },
    computed: {
      isLab: function () { return !!this.current && this.current.reportType === 'lab'; },
      canEdit: function () { return !!this.current && this.current.status === 0; },
      canReview: function () { return !!this.current && this.current.status === 1; }
    },
    created: function () { this.loadList(); },
    methods: {
      loadList: function (keepCurrent) {
        var vm = this;
        vm.loading = true;
        var q = '/api/medtech/reports?page=' + vm.page + '&size=' + vm.size + '&status=' + vm.tab;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          if (keepCurrent !== true) {
            /* 切Tab后原选中可能不在列表, 保持右侧不变但刷新其状态 */
            if (vm.current && vm.current.id) { vm.selectReport({ id: vm.current.id }, true); }
          }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () { this.page = 1; this.current = null; this.editItems = []; this.findings = ''; this.conclusion = ''; this.loadList(); },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },
      search: function () { this.page = 1; this.loadList(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 选中报告: 加载详情并填充编辑区(行数组深拷贝, 避免直接改动服务端对象) */
      selectReport: function (row, silent) {
        var vm = this;
        if (!row || !row.id) { return; }
        if (!silent) { vm.detailLoading = true; }
        HIS.get('/api/medtech/report/' + row.id).then(function (d) {
          vm.current = d;
          vm.findings = d.findings || '';
          vm.conclusion = d.conclusion || '';
          vm.editItems = ((d && d.resultItems) || []).map(function (it) {
            return {
              itemCode: it.itemCode, itemName: it.itemName, resultValue: it.resultValue, resultUnit: it.resultUnit,
              refRangeLow: it.refRangeLow, refRangeHigh: it.refRangeHigh,
              abnormalFlag: it.abnormalFlag, remark: it.remark
            };
          });
          if (vm.isLab && vm.editItems.length === 0 && vm.current.status === 0) { vm.addItem(); }
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      addItem: function () {
        this.editItems.push({ itemCode: '', itemName: '', resultValue: '', resultUnit: '', refRangeLow: null, refRangeHigh: null, abnormalFlag: 0, remark: '' });
      },
      removeItem: function (i) { this.editItems.splice(i, 1); },
      /* 行内异常标志即时预览(保存时由后端复算为准) */
      rowFlag: function (row) { return calcAbnormal(row.resultValue, row.refRangeLow, row.refRangeHigh); },
      /* 保存草稿: 校验至少一行有效结果项 */
      saveDraft: function () {
        var vm = this;
        if (!vm.current) { return; }
        if (vm.isLab) {
          var valid = vm.editItems.filter(function (it) { return it.itemName || it.itemCode; });
          if (!valid.length) { ElementPlus.ElMessage.warning('请至少填写一条检验结果项(项目名称或编码)'); return; }
        }
        vm.saving = true;
        HIS.post('/api/medtech/report/' + vm.current.id + '/save', {
          findings: vm.findings, conclusion: vm.conclusion, resultItems: vm.isLab ? vm.editItems : null
        }).then(function (r) {
          HIS.notifySuccess('草稿已保存');
          vm.selectReport({ id: vm.current.id }, true);
          vm.loadList(true);
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* 提交审核: 返回 criticalFlag=1 时弹红色危急值警告框 */
      submitReview: function () {
        var vm = this;
        if (!vm.current) { return; }
        ElementPlus.ElMessageBox.confirm('确认提交报告 ' + (vm.current.reportNo || '') + ' 送审？提交后将自动检测危急值。', '提交审核',
          { type: 'warning', confirmButtonText: '提交送审', cancelButtonText: '取消' }
        ).then(function () {
          vm.submitting = true;
          return HIS.post('/api/medtech/report/' + vm.current.id + '/submit');
        }).then(function (d) {
          if (d && d.criticalFlag === 1 && d.criticals && d.criticals.length) {
            vm.criticalList = d.criticals;
            vm.criticalVisible = true;
          } else {
            HIS.notifySuccess('报告已提交送审' + (d && d.criticalFlag === 1 ? ' (命中危急值, 请到危急值管理处理)' : ''));
          }
          vm.selectReport({ id: vm.current.id }, true);
          vm.loadList(true);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.submitting = false; });
      },
      /* 审核: 通过(1->2, 回写医嘱执行状态) / 退回(1->0) */
      review: function (approved) {
        var vm = this;
        if (!vm.current) { return; }
        var msg = approved
          ? '确认审核通过报告 ' + (vm.current.reportNo || '') + '？通过后报告发布并回写医嘱执行状态。'
          : '确认退回报告 ' + (vm.current.reportNo || '') + '？退回后报告回到草稿状态可继续修改。';
        ElementPlus.ElMessageBox.confirm(msg, approved ? '审核通过' : '退回重写',
          { type: approved ? 'success' : 'warning', confirmButtonText: approved ? '审核通过' : '退回', cancelButtonText: '取消' }
        ).then(function () {
          vm.reviewing = true;
          return HIS.post('/api/medtech/report/' + vm.current.id + '/review', { approved: approved });
        }).then(function () {
          HIS.notifySuccess(approved ? '报告已审核发布' : '报告已退回重写');
          vm.selectReport({ id: vm.current.id }, true);
          vm.loadList(true);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.reviewing = false; });
      },
      openDraft: function () { this.draftOrderId = null; this.draftType = 'lab'; this.draftVisible = true; },
      submitDraft: function () {
        var vm = this;
        if (!vm.draftOrderId) { ElementPlus.ElMessage.warning('请输入医嘱ID'); return; }
        vm.creatingDraft = true;
        HIS.post('/api/medtech/report/draft', { orderId: vm.draftOrderId, reportType: vm.draftType })
          .then(function (r) {
            vm.draftVisible = false;
            HIS.notifySuccess(r && r.status !== 0 ? '该医嘱已存在报告(单号 ' + r.reportNo + '), 已打开' : '报告草稿已创建: ' + (r && r.reportNo));
            vm.tab = String(r && r.status != null ? r.status : 0);
            if (vm.tab === '2') { vm.tab = '2'; }
            vm.loadList();
            vm.selectReport({ id: r.id });
          }).catch(HIS.notifyError)
          .finally(function () { vm.creatingDraft = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">报告工作站 <span style="font-size:12px;color:#909399;font-weight:normal;">(检查/检验报告书写 · 报告-审核双签 · 提交自动检测危急值)</span></div>',
      '  <el-row :gutter="12">',
      '    <el-col :span="8">',
      '      <div style="border-right:1px solid #ebeef5;padding-right:10px;">',
      '        <el-tabs v-model="tab" @tab-change="onTab" stretch>',
      '          <el-tab-pane label="待写" name="0"></el-tab-pane>',
      '          <el-tab-pane label="待审" name="1"></el-tab-pane>',
      '          <el-tab-pane label="已发布" name="2"></el-tab-pane>',
      '        </el-tabs>',
      '        <div class="toolbar" style="margin-bottom:8px;">',
      '          <el-input v-model="keyword" placeholder="报告单号/患者/患者ID" clearable size="small" style="width:170px" @keyup.enter="search"></el-input>',
      '          <el-button size="small" @click="search">查询</el-button>',
      '          <el-button size="small" type="primary" @click="openDraft">新建报告</el-button>',
      '        </div>',
      '        <div v-loading="loading" style="min-height:200px;max-height:560px;overflow-y:auto;">',
      '          <el-empty v-if="!list.length" description="暂无报告" :image-size="60"></el-empty>',
      '          <div v-for="r in list" :key="r.id" class="mt-report-item" :class="{active: current && current.id===r.id}" @click="selectReport(r)">',
      '            <div class="no">{{ r.reportNo }} <el-tag size="small" :type="reportTypeTag(r.reportType)" style="margin-left:4px;">{{ reportTypeLabel(r.reportType) }}</el-tag>',
      '              <el-tag v-if="r.criticalFlag===1" size="small" type="danger" effect="dark" style="margin-left:4px;">危急值</el-tag>',
      '            </div>',
      '            <div class="sub"><span>{{ r.patientName || \'-\' }} <span v-if="r.genderName || r.age">({{ r.genderName || \'\' }}{{ r.age != null ? r.age + \'岁\' : \'\' }})</span></span><span>{{ fmtTime(r.reportTime || r.createTime) }}</span></div>',
      '          </div>',
      '        </div>',
      '        <el-pagination small background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="16">',
      '      <div v-loading="detailLoading" style="min-height:420px;padding-left:4px;">',
      '        <el-empty v-if="!current" description="请选择左侧报告开始书写/查看" :image-size="90"></el-empty>',
      '        <template v-else>',
      '          <div class="mt-banner">',
      '            <div>',
      '              <span class="name">{{ current.patientName || \'-\' }}</span>',
      '              <span style="color:#606266;font-size:13px;" v-if="current.genderName || current.age">{{ current.genderName || \'\' }}{{ current.age != null ? \' · \' + current.age + \'岁\' : \'\' }}</span>',
      '              <el-tag size="small" :type="reportTypeTag(current.reportType)" style="margin-left:10px;">{{ reportTypeLabel(current.reportType) }}</el-tag>',
      '              <el-tag size="small" :type="reportStatusTag(current.status)" style="margin-left:4px;">{{ reportStatusLabel(current.status) }}</el-tag>',
      '              <el-tag v-if="current.criticalFlag===1" size="small" type="danger" effect="dark" style="margin-left:4px;">危急值</el-tag>',
      '            </div>',
      '            <div style="color:#606266;font-size:12px;margin-top:6px;">',
      '              报告单号: {{ current.reportNo }} · 就诊卡号: {{ current.patientNo || \'-\' }} · 医嘱单: {{ current.orderNo || (\'#\' + (current.orderId || \'-\')) }}',
      '              <span v-if="current.diagName"> · 诊断: {{ current.diagName }}</span>',
      '              <span v-if="current.reportDoctorName"> · 报告医师: {{ current.reportDoctorName }}</span>',
      '              <span v-if="current.reviewDoctorName"> · 审核医师: {{ current.reviewDoctorName }}</span>',
      '            </div>',
      '          </div>',
      '          <template v-if="canEdit">',
      '            <template v-if="isLab">',
      '              <div class="toolbar" style="margin-bottom:6px;">',
      '                <span style="font-weight:600;color:#303133;">检验结果明细</span>',
      '                <el-button size="small" @click="addItem">+ 新增子项</el-button>',
      '                <span style="flex:1;"></span>',
      '                <span style="color:#909399;font-size:12px;">异常标志实时预览, 提交时按危急值规则自动升级</span>',
      '              </div>',
      '              <el-table :data="editItems" border size="small" max-height="360">',
      '                <el-table-column type="index" label="#" width="42"></el-table-column>',
      '                <el-table-column label="项目编码" width="110"><template #default="s"><el-input v-model="s.row.itemCode" size="small" placeholder="如 K"></el-input></template></el-table-column>',
      '                <el-table-column label="项目名称" width="140"><template #default="s"><el-input v-model="s.row.itemName" size="small" placeholder="如 血钾"></el-input></template></el-table-column>',
      '                <el-table-column label="结果值" width="110"><template #default="s"><el-input v-model="s.row.resultValue" size="small" placeholder="数值"></el-input></template></el-table-column>',
      '                <el-table-column label="单位" width="80"><template #default="s"><el-input v-model="s.row.resultUnit" size="small" placeholder="mmol/L"></el-input></template></el-table-column>',
      '                <el-table-column label="参考下限" width="95"><template #default="s"><el-input v-model="s.row.refRangeLow" size="small"></el-input></template></el-table-column>',
      '                <el-table-column label="参考上限" width="95"><template #default="s"><el-input v-model="s.row.refRangeHigh" size="small"></el-input></template></el-table-column>',
      '                <el-table-column label="标志" width="82" align="center"><template #default="s"><span :class="flagClass(rowFlag(s.row))">{{ flagText(rowFlag(s.row)) }}</span></template></el-table-column>',
      '                <el-table-column label="备注" min-width="100"><template #default="s"><el-input v-model="s.row.remark" size="small"></el-input></template></el-table-column>',
      '                <el-table-column label="操作" width="60" align="center"><template #default="s"><el-button link type="danger" size="small" @click="removeItem(s.$index)">删除</el-button></template></el-table-column>',
      '              </el-table>',
      '            </template>',
      '            <template v-else>',
      '              <div style="font-weight:600;color:#303133;margin:8px 0 6px;">检查所见</div>',
      '              <el-input v-model="findings" type="textarea" :rows="7" placeholder="影像/检查所见(如: 双肺纹理清晰, 未见实质性病变...)"></el-input>',
      '            </template>',
      '            <div style="font-weight:600;color:#303133;margin:10px 0 6px;">报告结论</div>',
      '            <el-input v-model="conclusion" type="textarea" :rows="4" placeholder="诊断结论/检验意见"></el-input>',
      '            <div v-if="!isLab" style="font-weight:600;color:#303133;margin:10px 0 6px;">关键图像</div>',
      '            <div v-if="!isLab" class="mt-placeholder-img">影像存储对接中 · 关键图像预留位</div>',
      '          </template>',
      '          <template v-else>',
      '            <template v-if="isLab">',
      '              <div style="font-weight:600;color:#303133;margin:8px 0 6px;">检验结果明细(只读)</div>',
      '              <el-table :data="current.resultItems || []" border size="small" max-height="300">',
      '                <el-table-column type="index" label="#" width="42"></el-table-column>',
      '                <el-table-column prop="itemCode" label="项目编码" width="110"></el-table-column>',
      '                <el-table-column prop="itemName" label="项目名称" min-width="130"></el-table-column>',
      '                <el-table-column label="结果值" width="110"><template #default="s"><span :class="flagClass(s.row.abnormalFlag)">{{ s.row.resultValue }}</span></template></el-table-column>',
      '                <el-table-column prop="resultUnit" label="单位" width="80"></el-table-column>',
      '                <el-table-column prop="refRangeLow" label="参考下限" width="90"></el-table-column>',
      '                <el-table-column prop="refRangeHigh" label="参考上限" width="90"></el-table-column>',
      '                <el-table-column label="标志" width="82" align="center"><template #default="s"><span :class="flagClass(s.row.abnormalFlag)">{{ flagText(s.row.abnormalFlag) }}</span></template></el-table-column>',
      '                <el-table-column prop="remark" label="备注" min-width="90"></el-table-column>',
      '              </el-table>',
      '            </template>',
      '            <template v-else>',
      '              <el-descriptions :column="1" border size="small" style="margin-top:4px;">',
      '                <el-descriptions-item label="检查所见">{{ current.findings || \'-\' }}</el-descriptions-item>',
      '              </el-descriptions>',
      '            </template>',
      '            <el-descriptions :column="1" border size="small" style="margin-top:10px;">',
      '              <el-descriptions-item label="报告结论">{{ current.conclusion || \'-\' }}</el-descriptions-item>',
      '            </el-descriptions>',
      '            <div style="color:#909399;font-size:12px;margin-top:8px;">报告时间: {{ fmtTime(current.reportTime) }} · 审核时间: {{ fmtTime(current.reviewTime) }}</div>',
      '          </template>',
      '          <div class="toolbar" style="margin-top:14px;justify-content:flex-end;">',
      '            <template v-if="canEdit">',
      '              <el-button :loading="saving" @click="saveDraft">保存草稿</el-button>',
      '              <el-button type="primary" :loading="submitting" @click="submitReview">提交审核</el-button>',
      '            </template>',
      '            <template v-if="canReview">',
      '              <el-button type="success" :loading="reviewing" @click="review(true)">审核通过</el-button>',
      '              <el-button type="warning" :loading="reviewing" @click="review(false)">退回重写</el-button>',
      '            </template>',
      '            <span v-if="current.status===2" style="color:#67c23a;font-size:13px;">该报告已审核发布, 如需修改请线下联系检验科</span>',
      '          </div>',
      '        </template>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      '  <el-dialog v-model="criticalVisible" title="危急值警告" width="620px">',
      '    <el-alert type="error" :closable="false" show-icon title="该报告检出危急值! 请立即按危急值制度复核并电话通知临床。" style="margin-bottom:12px;"></el-alert>',
      '    <el-table :data="criticalList" border size="small">',
      '      <el-table-column type="index" label="#" width="42"></el-table-column>',
      '      <el-table-column prop="itemCode" label="项目编码" width="110"></el-table-column>',
      '      <el-table-column prop="itemName" label="项目名称" min-width="130"></el-table-column>',
      '      <el-table-column label="结果值" width="100"><template #default="s"><span class="mt-blink">{{ s.row.resultValue }}</span></template></el-table-column>',
      '      <el-table-column prop="discoverTime" label="发现时间" width="160"><template #default="s">{{ fmtTime(s.row.discoverTime) }}</template></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button type="danger" @click="criticalVisible=false">知悉, 前往危急值管理</el-button></template>',
      '  </el-dialog>',
      '  <el-dialog v-model="draftVisible" title="新建报告(选择一个检验/检查医嘱)" width="430px">',
      '    <el-form label-width="90px">',
      '      <el-form-item label="医嘱ID" required><el-input v-model="draftOrderId" placeholder="医嘱单ID(数字)"></el-input></el-form-item>',
      '      <el-form-item label="报告类型">',
      '        <el-radio-group v-model="draftType">',
      '          <el-radio label="lab">检验</el-radio>',
      '          <el-radio label="exam">检查(影像)</el-radio>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <div style="margin:-6px 0 6px 90px;color:#909399;font-size:12px;">同一医嘱已有报告时将直接打开已有报告</div>',
      '    </el-form>',
      '    <template #footer><el-button @click="draftVisible=false">取 消</el-button><el-button type="primary" :loading="creatingDraft" @click="submitDraft">创建草稿</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 3. 危急值闭环管理(发现→复核→通知→接收→处置) =================
   * 超时预警: 已发现超30分钟未复核 / 已通知超30分钟未被接收, 行标橙色并附提示;
   * 30秒自动静默刷新列表(同时刷新超时着色); 流转全部走后端乐观锁, 冲突时提示刷新重试。
   */
  HIS.views.MedtechCritical = {
    data: function () {
      return {
        status: '', loading: false, list: [], total: 0, page: 1, size: 20, timer: null,
        statusOptions: [
          { v: '', l: '全部' }, { v: 0, l: '已发现' }, { v: 1, l: '已复核' },
          { v: 2, l: '已通知' }, { v: 3, l: '已接收' }, { v: 4, l: '已处置' }
        ],
        criticalStatus: CRITICAL_STATUS, criticalStatusTag: CRITICAL_STATUS_TAG,
        fmtTime: fmtTime,
        /* 流转操作弹窗(复核/通知/接收/处置 四合一) */
        actVisible: false, actRow: null, act: '', actInput: '', acting: false,
        /* 闭环详情 */
        detailVisible: false, detailRow: null
      };
    },
    computed: {
      actMeta: function () {
        var m = {
          verify: { title: '危急值复核', label: '', inputType: '', placeholder: '', tip: '确认该结果已人工核对无误, 复核后须在30分钟内电话通知临床并留痕。', btn: '确认复核', btnType: 'primary' },
          notify: { title: '电话通知临床', label: '被通知人', inputType: 'input', placeholder: '如: 心内科 张医生 / 护士站', tip: '通知对象须为临床医师或护士, 通知时间与对象将全程留痕。', btn: '确认已通知', btnType: 'warning' },
          confirm: { title: '临床接收回执', label: '接收人', inputType: 'input', placeholder: '如: 李医生', tip: '由临床接收方确认已收到该危急值通知。', btn: '确认接收', btnType: 'primary' },
          handle: { title: '记录处置措施', label: '处置措施', inputType: 'textarea', placeholder: '如: 立即复查血钾、静脉补钾, 已报告值班医师并记录病程...', tip: '记录临床处置经过, 提交后危急值闭环完成。', btn: '完成闭环', btnType: 'success' }
        };
        return m[this.act] || m.verify;
      },
      timeoutCount: function () {
        var vm = this;
        return (vm.list || []).filter(function (r) { return !!vm.rowWarn(r); }).length;
      }
    },
    created: function () { this.load(); },
    mounted: function () {
      var vm = this;
      if (vm.timer) { clearInterval(vm.timer); }
      vm.timer = setInterval(function () { vm.load(true); }, 30000);
    },
    beforeUnmount: function () {
      if (this.timer) { clearInterval(this.timer); this.timer = null; }
    },
    methods: {
      /* silent=true 为定时静默刷新(不显示 loading, 不弹错) */
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/medtech/critical-values?page=' + vm.page + '&size=' + vm.size;
        if (vm.status !== '') { q += '&status=' + vm.status; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(function (e) { if (silent !== true) { HIS.notifyError(e); } })
          .finally(function () { if (silent !== true) { vm.loading = false; } });
      },
      onStatus: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 超时判定: 待复核超30min(基于发现时间) / 已通知超30min未被接收(基于通知时间) */
      rowWarn: function (row) {
        var now = Date.now(), TH = 30 * 60 * 1000;
        if (row.status === 0 && row.discoverTime && now - ts(row.discoverTime) > TH) { return '发现已超30分钟未复核'; }
        if (row.status === 2 && row.notifyTime && now - ts(row.notifyTime) > TH) { return '通知已超30分钟未被临床接收'; }
        return '';
      },
      rowClass: function (o) { return this.rowWarn(o.row) ? 'mt-warning-row' : ''; },
      /* 步骤条: active=status+1(已处置全完成=5) */
      stepActive: function (row) { var s = row.status; return s >= 4 ? 5 : s + 1; },
      openAct: function (row, act) {
        this.actRow = row;
        this.act = act;
        this.actInput = '';
        this.actVisible = true;
      },
      submitAct: function () {
        var vm = this;
        var needInput = vm.act === 'notify' ? '被通知人' : (vm.act === 'confirm' ? '接收人' : (vm.act === 'handle' ? '处置措施' : ''));
        if (needInput && !vm.actInput) { ElementPlus.ElMessage.warning('请输入' + needInput); return; }
        var id = vm.actRow.id;
        var req;
        if (vm.act === 'verify') { req = HIS.post('/api/medtech/critical/' + id + '/verify'); }
        else if (vm.act === 'notify') { req = HIS.post('/api/medtech/critical/' + id + '/notify', { target: vm.actInput }); }
        else if (vm.act === 'confirm') { req = HIS.post('/api/medtech/critical/' + id + '/confirm', { person: vm.actInput }); }
        else { req = HIS.post('/api/medtech/critical/' + id + '/handle', { measures: vm.actInput }); }
        vm.acting = true;
        req.then(function () {
          HIS.notifySuccess('操作成功');
          vm.actVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.acting = false; });
      },
      openDetail: function (row) { this.detailRow = row; this.detailVisible = true; }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">危急值闭环管理 <span style="font-size:12px;color:#909399;font-weight:normal;">(发现 · 复核 · 通知 · 接收 · 处置 全流程留痕, 30秒自动刷新; 橙色行 = 超时预警)</span></div>',
      '  <div class="toolbar">',
      '    <span style="color:#606266;font-size:13px;">状态</span>',
      '    <el-select v-model="status" size="small" style="width:130px" @change="onStatus">',
      '      <el-option v-for="o in statusOptions" :key="o.l" :label="o.l" :value="o.v"></el-option>',
      '    </el-select>',
      '    <el-button size="small" @click="load()">刷新</el-button>',
      '    <el-tag v-if="timeoutCount" type="warning" size="small" style="margin-left:4px;">超时预警 {{ timeoutCount }} 条</el-tag>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" border size="small" v-loading="loading" :row-class-name="rowClass">',
      '    <el-table-column type="index" label="序号" width="55" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" min-width="110"><template #default="s">{{ s.row.patientName || \'-\' }}<div style="color:#909399;font-size:12px;">{{ (s.row.genderName || \'\') + (s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\') }}</div></template></el-table-column>',
      '    <el-table-column label="项目" min-width="130"><template #default="s"><div style="font-weight:600;">{{ s.row.itemName || \'-\' }}</div><div style="color:#909399;font-size:12px;">{{ s.row.itemCode || \'\' }}</div></template></el-table-column>',
      '    <el-table-column label="结果值" width="95" align="center"><template #default="s"><span class="mt-flag-high" :class="{ \'mt-blink\': s.row.status < 4 }">{{ s.row.resultValue }}</span></template></el-table-column>',
      '    <el-table-column label="报告单号" width="130"><template #default="s"><span style="font-family:Consolas,monospace;font-size:12px;">{{ s.row.reportNo || \'-\' }}</span></template></el-table-column>',
      '    <el-table-column label="发现时间" width="150"><template #default="s">',
      '      <el-tooltip v-if="rowWarn(s.row)" :content="rowWarn(s.row)" placement="top"><span style="color:#e6a23c;font-weight:600;cursor:help;">{{ fmtTime(s.row.discoverTime) }}</span></el-tooltip>',
      '      <span v-else>{{ fmtTime(s.row.discoverTime) }}</span>',
      '    </template></el-table-column>',
      '    <el-table-column label="流程状态" min-width="230"><template #default="s">',
      '      <el-steps class="mt-steps" :active="stepActive(s.row)" align-center>',
      '        <el-step title="发现"></el-step><el-step title="复核"></el-step><el-step title="通知"></el-step><el-step title="接收"></el-step><el-step title="处置"></el-step>',
      '      </el-steps>',
      '    </template></el-table-column>',
      '    <el-table-column label="流转记录" min-width="160"><template #default="s">',
      '      <div v-if="s.row.verifyTime" style="font-size:12px;color:#606266;">复核 {{ s.row.verifyTechName || \'-\' }} {{ fmtTime(s.row.verifyTime) }}</div>',
      '      <div v-if="s.row.notifyTarget" style="font-size:12px;color:#606266;">通知 {{ s.row.notifyTarget }} {{ fmtTime(s.row.notifyTime) }}</div>',
      '      <div v-if="s.row.receivePerson" style="font-size:12px;color:#606266;">接收 {{ s.row.receivePerson }} {{ fmtTime(s.row.receiveTime) }}</div>',
      '      <div v-if="s.row.handleMeasures" style="font-size:12px;color:#67c23a;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" :title="s.row.handleMeasures">处置 {{ s.row.handleMeasures }}</div>',
      '      <span v-if="!s.row.verifyTime && !s.row.notifyTarget" style="color:#c0c4cc;font-size:12px;">-</span>',
      '    </template></el-table-column>',
      '    <el-table-column label="状态" width="85" align="center"><template #default="s"><el-tag size="small" :type="criticalStatusTag[s.row.status] || \'info\'">{{ criticalStatus[s.row.status] || s.row.status }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="140" fixed="right"><template #default="s">',
      '      <el-button v-if="s.row.status===0" link type="danger" size="small" @click="openAct(s.row, \'verify\')">复核</el-button>',
      '      <el-button v-else-if="s.row.status===1" link type="warning" size="small" @click="openAct(s.row, \'notify\')">通知</el-button>',
      '      <el-button v-else-if="s.row.status===2" link type="primary" size="small" @click="openAct(s.row, \'confirm\')">确认接收</el-button>',
      '      <el-button v-else-if="s.row.status===3" link type="success" size="small" @click="openAct(s.row, \'handle\')">记录处置</el-button>',
      '      <span v-else style="color:#67c23a;font-size:12px;">已闭环</span>',
      '      <el-button link size="small" @click="openDetail(s.row)">详情</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="actVisible" :title="actMeta.title" width="480px">',
      '    <el-alert v-if="actMeta.tip" type="warning" :closable="false" show-icon :title="actMeta.tip" style="margin-bottom:12px;"></el-alert>',
      '    <el-alert v-if="actRow" type="error" :closable="false" show-icon :title="\'(报告 \' + (actRow.reportNo || \'-\') + \') \' + (actRow.patientName || \'-\') + \' · \' + (actRow.itemName || \'-\') + \' = \' + (actRow.resultValue || \'-\')" style="margin-bottom:12px;"></el-alert>',
      '    <el-form v-if="actMeta.inputType" label-width="80px">',
      '      <el-form-item :label="actMeta.label" required>',
      '        <el-input v-if="actMeta.inputType===\'input\'" v-model="actInput" :placeholder="actMeta.placeholder" maxlength="50"></el-input>',
      '        <el-input v-else v-model="actInput" type="textarea" :rows="3" :placeholder="actMeta.placeholder" maxlength="200" show-word-limit></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="actVisible=false">取 消</el-button><el-button :type="actMeta.btnType" :loading="acting" @click="submitAct">{{ actMeta.btn }}</el-button></template>',
      '  </el-dialog>',
      '  <el-dialog v-model="detailVisible" title="危急值闭环详情" width="580px">',
      '    <el-descriptions v-if="detailRow" :column="2" border size="small">',
      '      <el-descriptions-item label="患者">{{ detailRow.patientName || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="项目">{{ (detailRow.itemName || \'-\') + \' \' + (detailRow.itemCode || \'\') }}</el-descriptions-item>',
      '      <el-descriptions-item label="结果值"><span class="mt-flag-high">{{ detailRow.resultValue }}</span></el-descriptions-item>',
      '      <el-descriptions-item label="报告单号">{{ detailRow.reportNo || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="发现时间">{{ fmtTime(detailRow.discoverTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="复核技师">{{ detailRow.verifyTechName || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="复核时间">{{ fmtTime(detailRow.verifyTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="通知对象">{{ detailRow.notifyTarget || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="通知时间">{{ fmtTime(detailRow.notifyTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="接收人">{{ detailRow.receivePerson || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="接收时间">{{ fmtTime(detailRow.receiveTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="处置时间">{{ fmtTime(detailRow.handleTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="处置措施" :span="2">{{ detailRow.handleMeasures || \'-\' }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <template #footer><el-button type="primary" @click="detailVisible=false">关 闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 4. 危急值规则配置(阈值维护 + 一键初始化常用规则) =================
   * 规则维度: 项目编码+患者类型(通用/成人/儿童); 命中时具体类型优先, 回落通用; 删除为物理删。
   */
  var DEFAULT_CRITICAL_RULES = [
    { itemCode: 'K', itemName: '血钾', lowThreshold: 2.8, highThreshold: 6.0, patientType: null, isActive: 1 },
    { itemCode: 'GLU', itemName: '血糖', lowThreshold: 2.5, highThreshold: 22.2, patientType: null, isActive: 1 },
    { itemCode: 'PLT', itemName: '血小板', lowThreshold: 30, highThreshold: 1000, patientType: null, isActive: 1 },
    { itemCode: 'WBC', itemName: '白细胞', lowThreshold: 1.5, highThreshold: 30, patientType: null, isActive: 1 },
    { itemCode: 'HGB', itemName: '血红蛋白', lowThreshold: 50, highThreshold: 200, patientType: null, isActive: 1 },
    { itemCode: 'PT', itemName: '凝血酶原时间', lowThreshold: null, highThreshold: 30, patientType: null, isActive: 1 }
  ];
  HIS.views.MedtechCriticalRule = {
    data: function () {
      return {
        loading: false, list: [], seeding: false,
        dialogVisible: false, editingId: null, saving: false,
        form: { itemCode: '', itemName: '', lowThreshold: null, highThreshold: null, patientType: '', isActive: true },
        patientTypeLabel: patientTypeLabel, patientTypeTag: patientTypeTag
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/medtech/critical-rules').then(function (d) {
          vm.list = d || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      seqNo: function (i) { return i + 1; },
      openCreate: function () {
        this.editingId = null;
        this.form = { itemCode: '', itemName: '', lowThreshold: null, highThreshold: null, patientType: '', isActive: true };
        this.dialogVisible = true;
      },
      openEdit: function (row) {
        this.editingId = row.id;
        this.form = {
          itemCode: row.itemCode, itemName: row.itemName,
          lowThreshold: row.lowThreshold, highThreshold: row.highThreshold,
          patientType: row.patientType || '', isActive: row.isActive === 1
        };
        this.dialogVisible = true;
      },
      saveRule: function () {
        var vm = this, f = vm.form;
        if (!f.itemCode) { ElementPlus.ElMessage.warning('请输入项目编码'); return; }
        var lo = (f.lowThreshold === '' || f.lowThreshold == null) ? null : f.lowThreshold;
        var hi = (f.highThreshold === '' || f.highThreshold == null) ? null : f.highThreshold;
        if (lo == null && hi == null) { ElementPlus.ElMessage.warning('危急值下限与上限至少填写一项'); return; }
        if (lo != null && hi != null && lo > hi) { ElementPlus.ElMessage.warning('危急值下限不能高于上限'); return; }
        var payload = {
          itemCode: f.itemCode, itemName: f.itemName, lowThreshold: lo, highThreshold: hi,
          patientType: f.patientType || null, isActive: f.isActive ? 1 : 0
        };
        vm.saving = true;
        var req = vm.editingId
          ? HIS.put('/api/medtech/critical-rules/' + vm.editingId, payload)
          : HIS.post('/api/medtech/critical-rules', payload);
        req.then(function () {
          HIS.notifySuccess(vm.editingId ? '规则已更新' : '规则已新增');
          vm.dialogVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* 启用/停用开关(v-model 已先行更新 row.isActive, 失败回读恢复) */
      onToggle: function (row) {
        var vm = this;
        HIS.put('/api/medtech/critical-rules/' + row.id, {
          itemCode: row.itemCode, itemName: row.itemName,
          lowThreshold: row.lowThreshold, highThreshold: row.highThreshold,
          patientType: row.patientType || null, isActive: row.isActive
        }).then(function () {
          HIS.notifySuccess(row.isActive === 1 ? '规则已启用' : '规则已停用');
        }).catch(function (e) { HIS.notifyError(e); vm.load(); });
      },
      removeRule: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除规则『' + (row.itemName || row.itemCode) + '』？删除后该项目将不再触发自动危急值检测。', '删除规则',
          { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.del('/api/medtech/critical-rules/' + row.id);
        }).then(function () {
          HIS.notifySuccess('规则已删除');
          vm.load();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* 一键初始化6条常用规则(逐条串行, 失败(如已存在)不阻断) */
      seedDefaults: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('将初始化 6 条常用危急值规则(血钾/血糖/血小板/白细胞/血红蛋白/PT, 适用全部患者类型), 之后可继续编辑。', '一键初始化常用规则',
          { type: 'info', confirmButtonText: '开始初始化', cancelButtonText: '取消' }
        ).then(function () {
          vm.seeding = true;
          var ok = 0, fail = 0;
          var seq = Promise.resolve();
          DEFAULT_CRITICAL_RULES.forEach(function (r) {
            seq = seq.then(function () {
              return HIS.post('/api/medtech/critical-rules', r)
                .then(function () { ok++; })
                .catch(function () { fail++; });
            });
          });
          return seq.then(function () {
            HIS.notifySuccess('初始化完成: 成功 ' + ok + ' 条' + (fail ? ', 失败 ' + fail + ' 条(可能已存在)' : ''));
            vm.load();
          });
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } })
          .finally(function () { vm.seeding = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">危急值规则配置 <span style="font-size:12px;color:#909399;font-weight:normal;">(阈值命中即报危急值; 患者类型精确匹配优先, 无则回落通用规则)</span></div>',
      '  <div class="toolbar">',
      '    <el-button type="primary" @click="openCreate">新增规则</el-button>',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 条</span>',
      '  </div>',
      '  <el-table v-if="list.length || loading" :data="list" border size="small" v-loading="loading">',
      '    <el-table-column type="index" label="序号" width="55" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="itemCode" label="项目编码" width="110"></el-table-column>',
      '    <el-table-column prop="itemName" label="项目名称" min-width="140"></el-table-column>',
      '    <el-table-column label="危急值下限" width="120" align="right"><template #default="s"><span style="color:#409eff;font-weight:600;">{{ s.row.lowThreshold != null ? s.row.lowThreshold : \'-\' }}</span></template></el-table-column>',
      '    <el-table-column label="危急值上限" width="120" align="right"><template #default="s"><span style="color:#f56c6c;font-weight:600;">{{ s.row.highThreshold != null ? s.row.highThreshold : \'-\' }}</span></template></el-table-column>',
      '    <el-table-column label="患者类型" width="100" align="center"><template #default="s"><el-tag size="small" :type="s.row.patientType ? patientTypeTag(s.row.patientType) : \'info\'">{{ s.row.patientType ? patientTypeLabel(s.row.patientType) : \'通用\' }}</el-tag></template></el-table-column>',
      '    <el-table-column label="启用" width="80" align="center"><template #default="s"><el-switch v-model="s.row.isActive" :active-value="1" :inactive-value="0" @change="onToggle(s.row)"></el-switch></template></el-table-column>',
      '    <el-table-column label="操作" width="120" align="center"><template #default="s">',
      '      <el-button link type="primary" size="small" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" size="small" @click="removeRule(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-empty v-if="!loading && !list.length" description="暂无危急值规则, 未配置时报告提交不会触发危急值检测">',
      '    <el-button type="primary" :loading="seeding" @click="seedDefaults">一键初始化常用规则</el-button>',
      '    <el-button @click="openCreate">手动新增</el-button>',
      '  </el-empty>',
      '  <el-dialog v-model="dialogVisible" :title="editingId ? \'编辑危急值规则\' : \'新增危急值规则\'" width="480px">',
      '    <el-form label-width="100px">',
      '      <el-form-item label="项目编码" required><el-input v-model="form.itemCode" placeholder="如 K, 需与结果明细项目编码一致" maxlength="30"></el-input></el-form-item>',
      '      <el-form-item label="项目名称"><el-input v-model="form.itemName" placeholder="如 血钾(留空默认同编码)" maxlength="50"></el-input></el-form-item>',
      '      <el-form-item label="危急值下限"><el-input-number v-model="form.lowThreshold" :controls="false" style="width:100%" placeholder="低于该值报危急(留空不限)"></el-input-number></el-form-item>',
      '      <el-form-item label="危急值上限"><el-input-number v-model="form.highThreshold" :controls="false" style="width:100%" placeholder="高于该值报危急(留空不限)"></el-input-number></el-form-item>',
      '      <el-form-item label="患者类型">',
      '        <el-radio-group v-model="form.patientType">',
      '          <el-radio label="">通用</el-radio>',
      '          <el-radio label="adult">成人</el-radio>',
      '          <el-radio label="child">儿童</el-radio>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="启用状态"><el-switch v-model="form.isActive"></el-switch><span style="margin-left:8px;color:#909399;font-size:12px;">停用后该规则不参与检测</span></el-form-item>',
      '      <div style="margin:-6px 0 6px 100px;color:#909399;font-size:12px;">上下限至少填写一项; 通用规则对所有患者生效, 具体类型规则命中时优先于通用。</div>',
      '    </el-form>',
      '    <template #footer><el-button @click="dialogVisible=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveRule">保 存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 5. 报告查询(全状态检索 + 详情查看) ================= */
  HIS.views.MedtechReportQuery = {
    data: function () {
      return {
        keyword: '', reportType: '', dateRange: null,
        loading: false, list: [], total: 0, page: 1, size: 20,
        detailVisible: false, detail: null, detailLoading: false,
        reportTypes: REPORT_TYPES, fmtTime: fmtTime,
        reportTypeLabel: reportTypeLabel, reportTypeTag: reportTypeTag,
        reportStatusLabel: reportStatusLabel, reportStatusTag: reportStatusTag,
        flagText: flagText, flagClass: flagClass
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/medtech/reports?page=' + vm.page + '&size=' + vm.size;
        if (vm.reportType) { q += '&reportType=' + vm.reportType; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&from=' + vm.dateRange[0] + '&to=' + vm.dateRange[1]; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      reset: function () {
        this.keyword = ''; this.reportType = ''; this.dateRange = null;
        this.page = 1; this.load();
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      openDetail: function (row) {
        var vm = this;
        if (!row || !row.id) { return; }
        vm.detailVisible = true;
        vm.detailLoading = true;
        vm.detail = null;
        HIS.get('/api/medtech/report/' + row.id).then(function (d) {
          vm.detail = d;
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">报告查询 <span style="font-size:12px;color:#909399;font-weight:normal;">(全状态检索历史报告 · 危急值标注 · 点击行查看详情)</span></div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="患者姓名 / 患者ID / 报告单号" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-select v-model="reportType" placeholder="报告类型" clearable style="width:130px">',
      '      <el-option v-for="t in reportTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="报告日期起" end-placeholder="报告日期止" style="width:260px"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="reset">重置</el-button>',
      '  </div>',
      '  <el-table :data="list" border size="small" v-loading="loading" style="cursor:pointer;" @row-click="openDetail">',
      '    <el-table-column type="index" label="序号" width="55" :index="seqNo"></el-table-column>',
      '    <el-table-column label="报告编号" width="150"><template #default="s"><b style="font-family:Consolas,monospace;">{{ s.row.reportNo }}</b></template></el-table-column>',
      '    <el-table-column label="患者" min-width="130"><template #default="s">{{ s.row.patientName || \'-\' }}<span style="color:#909399;font-size:12px;" v-if="s.row.genderName || s.row.age"> ({{ s.row.genderName || \'\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }})</span></template></el-table-column>',
      '    <el-table-column label="类型" width="80" align="center"><template #default="s"><el-tag size="small" :type="reportTypeTag(s.row.reportType)">{{ reportTypeLabel(s.row.reportType) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="报告日期" width="150"><template #default="s">{{ fmtTime(s.row.reportTime || s.row.createTime) }}</template></el-table-column>',
      '    <el-table-column label="审核日期" width="150"><template #default="s">{{ fmtTime(s.row.reviewTime) }}</template></el-table-column>',
      '    <el-table-column label="报告医师" width="100"><template #default="s">{{ s.row.reportDoctorName || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="85" align="center"><template #default="s"><el-tag size="small" :type="reportStatusTag(s.row.status)">{{ reportStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="危急值" width="95" align="center"><template #default="s"><el-tag v-if="s.row.criticalFlag===1" type="danger" effect="dark" size="small">⚠ 危急</el-tag><span v-else style="color:#c0c4cc;">-</span></template></el-table-column>',
      '    <el-table-column label="操作" width="70" align="center"><template #default="s"><el-button link type="primary" size="small" @click.stop="openDetail(s.row)">详情</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="detailVisible" title="报告详情" width="760px" top="6vh">',
      '    <div v-loading="detailLoading" style="min-height:200px;">',
      '      <template v-if="detail">',
      '        <div class="mt-banner">',
      '          <div>',
      '            <span class="name">{{ detail.patientName || \'-\' }}</span>',
      '            <span style="color:#606266;font-size:13px;" v-if="detail.genderName || detail.age">{{ detail.genderName || \'\' }}{{ detail.age != null ? \' · \' + detail.age + \'岁\' : \'\' }}</span>',
      '            <el-tag size="small" :type="reportTypeTag(detail.reportType)" style="margin-left:10px;">{{ reportTypeLabel(detail.reportType) }}</el-tag>',
      '            <el-tag size="small" :type="reportStatusTag(detail.status)" style="margin-left:4px;">{{ reportStatusLabel(detail.status) }}</el-tag>',
      '            <el-tag v-if="detail.criticalFlag===1" size="small" type="danger" effect="dark" style="margin-left:4px;">⚠ 含危急值</el-tag>',
      '          </div>',
      '          <div style="color:#606266;font-size:12px;margin-top:6px;">',
      '            报告单号: {{ detail.reportNo }} · 就诊卡号: {{ detail.patientNo || \'-\' }} · 医嘱单: {{ detail.orderNo || (\'#\' + (detail.orderId || \'-\')) }}',
      '            <span v-if="detail.diagName"> · 诊断: {{ detail.diagName }}</span>',
      '            <span v-if="detail.reportDoctorName"> · 报告医师: {{ detail.reportDoctorName }}</span>',
      '            <span v-if="detail.reviewDoctorName"> · 审核医师: {{ detail.reviewDoctorName }}</span>',
      '          </div>',
      '          <div style="color:#909399;font-size:12px;margin-top:4px;">报告时间: {{ fmtTime(detail.reportTime) }} · 审核时间: {{ fmtTime(detail.reviewTime) }}</div>',
      '        </div>',
      '        <el-alert v-if="detail.criticalFlag===1" type="error" :closable="false" show-icon title="该报告含危急值, 请到危急值闭环管理核查处理记录" style="margin-bottom:12px;"></el-alert>',
      '        <template v-if="detail.reportType===\'lab\'">',
      '          <div style="font-weight:600;color:#303133;margin:2px 0 6px;">检验结果明细</div>',
      '          <el-table :data="detail.resultItems || []" border size="small" max-height="320">',
      '            <el-table-column type="index" label="#" width="42"></el-table-column>',
      '            <el-table-column prop="itemCode" label="项目编码" width="100"></el-table-column>',
      '            <el-table-column prop="itemName" label="项目名称" min-width="130"></el-table-column>',
      '            <el-table-column label="结果值" width="110"><template #default="s"><span :class="flagClass(s.row.abnormalFlag)">{{ s.row.resultValue }}</span></template></el-table-column>',
      '            <el-table-column prop="resultUnit" label="单位" width="80"></el-table-column>',
      '            <el-table-column label="参考范围" width="130"><template #default="s">{{ (s.row.refRangeLow != null ? s.row.refRangeLow : \'\') + \' ~ \' + (s.row.refRangeHigh != null ? s.row.refRangeHigh : \'\') }}</template></el-table-column>',
      '            <el-table-column label="标志" width="82" align="center"><template #default="s"><span :class="flagClass(s.row.abnormalFlag)">{{ flagText(s.row.abnormalFlag) }}</span></template></el-table-column>',
      '          </el-table>',
      '        </template>',
      '        <template v-else>',
      '          <div style="font-weight:600;color:#303133;margin:2px 0 6px;">检查所见</div>',
      '          <el-descriptions :column="1" border size="small"><el-descriptions-item label="所见">{{ detail.findings || \'-\' }}</el-descriptions-item></el-descriptions>',
      '        </template>',
      '        <div style="font-weight:600;color:#303133;margin:10px 0 6px;">报告结论</div>',
      '        <el-descriptions :column="1" border size="small"><el-descriptions-item label="结论">{{ detail.conclusion || \'-\' }}</el-descriptions-item></el-descriptions>',
      '      </template>',
      '      <el-empty v-else-if="!detailLoading" description="报告不存在或已删除" :image-size="80"></el-empty>',
      '    </div>',
      '    <template #footer><el-button type="primary" @click="detailVisible=false">关 闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
