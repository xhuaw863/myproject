/* 门诊护士站: 待执行医嘱工作台(三查七对·过敏警示·30秒轮询) + 皮试管理(20分钟观察窗倒计时) + 输液管理(配液/穿刺/巡视/拔针) + 过敏档案 + 执行记录查询 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 护士站私有样式一次性注入(无构建架构, 避免改动公共 css 与其他会话冲突) */
  (function ensureNurseStyles() {
    if (document.getElementById('nurse-style')) { return; }
    var st = document.createElement('style');
    st.id = 'nurse-style';
    st.textContent = [
      /* 过敏史患者整行淡红警示 */
      '.el-table__body tr.ns-allergy-row > td.el-table__cell { background-color:var(--yb-danger-bg) !important; }',
      /* 急诊/到窗红色闪烁 */
      '@keyframes nsBlink { 50% { opacity:.2; } }',
      '.ns-blink { animation: nsBlink 1s infinite; font-weight:800; color:var(--yb-danger); }',
      '.ns-emergency { font-weight:800; color:var(--yb-danger); }',
      '.ns-overdue { color:var(--yb-danger); font-weight:700; }',
      /* T45 输液巡视超30分钟: 整行淡红警示 + 巡视列"巡视超时"红字闪烁 */
      '.el-table__body tr.ns-patrol-row > td.el-table__cell { background-color:var(--yb-danger-bg) !important; }',
      '.ns-patrol-due { color:var(--yb-danger); font-weight:800; animation: nsBlink 1s infinite; }',
      '.ns-soft { color:var(--yb-ink-2); font-size:12px; }',
      '.ns-strong { color:var(--yb-ink-1); font-weight:600; }',
      '.ns-check-item { display:block; margin:4px 0; }',
      /* 患者信息横幅(弹窗内) */
      '.ns-banner { background:linear-gradient(135deg,#f0f7ff 0%,#fafcff 100%); border:1px solid var(--yb-brand-subtle); border-radius:8px; padding:10px 14px; margin-bottom:12px; }',
      '.ns-banner .name { font-size:17px; font-weight:700; color:var(--yb-ink-1); margin-right:10px; }',
      '.ns-banner .row { color:var(--yb-ink-2); font-size:13px; margin-top:4px; }',
      /* 过敏警示横幅(弹窗内) */
      '.ns-allergy-alert { background:var(--yb-danger-bg); border:1px solid var(--yb-danger-border); border-radius:8px; padding:8px 14px; margin-bottom:12px; color:var(--yb-danger-strong); font-weight:600; font-size:13px; }',
      /* 时间线小字 */
      '.ns-timeline-time { color:var(--yb-ink-2); font-size:12px; margin-left:8px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 本地受控枚举(非国标字典) ===== */
  var EXEC_TYPES = [
    { v: 'skin_test', l: '皮试', t: 'warning' },
    { v: 'infusion', l: '输液', t: 'primary' },
    { v: 'injection', l: '注射', t: 'success' },
    { v: 'dressing', l: '换药', t: 'info' }
  ];
  var EXEC_STATUS = [
    { v: 0, l: '待执行', t: 'warning' },
    { v: 1, l: '执行中', t: 'primary' },
    { v: 2, l: '已完成', t: 'success' },
    { v: -1, l: '已取消', t: 'info' }
  ];
  /* 皮试结果: 0观察中 1阴性 2阳性 3可疑 */
  var SKIN_RESULTS = [
    { v: 1, l: '阴性', t: 'success' },
    { v: 2, l: '阳性', t: 'danger' },
    { v: 3, l: '可疑', t: 'warning' }
  ];
  var ALLERGEN_TYPES = [
    { v: 'drug', l: '药物', t: 'danger' },
    { v: 'food', l: '食物', t: 'warning' },
    { v: 'contact', l: '接触物', t: 'primary' },
    { v: 'other', l: '其他', t: 'info' }
  ];
  var SEVERITIES = [
    { v: 'mild', l: '轻度', t: 'info' },
    { v: 'moderate', l: '中度', t: 'warning' },
    { v: 'severe', l: '重度', t: 'danger' }
  ];
  var ALLERGY_SOURCES = [
    { v: 'manual', l: '手工登记', t: 'info' },
    { v: 'skin_test', l: '皮试阳性', t: 'danger' },
    { v: 'history', l: '病史采集', t: 'primary' },
    { v: 'other', l: '其他', t: 'info' }
  ];
  /* 三查七对勾选项(开始执行前核对清单, 须全部勾选) */
  var CHECK_ITEMS = [
    '核对患者身份(姓名/就诊号)',
    '核对医嘱与药品名称、规格',
    '核对剂量、浓度与用法',
    '核对皮试结果与过敏史',
    '检查药品外观质量与有效期',
    '确认医嘱已缴费(收费凭据)',
    '已告知患者注意事项'
  ];
  /* 输液巡视患者状态 */
  var PATROL_STATUS = ['正常', '局部疼痛', '穿刺处肿胀(疑似渗液)', '头晕心慌', '寒战发热', '其他不适'];

  function findBy(list, prop, v) {
    for (var i = 0; i < list.length; i++) { if (list[i][prop] === v) { return list[i]; } }
    return null;
  }
  function execTypeLabel(v) { var s = findBy(EXEC_TYPES, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function execTypeTag(v) { var s = findBy(EXEC_TYPES, 'v', v); return s ? s.t : 'info'; }
  function execStatusLabel(v) { var s = findBy(EXEC_STATUS, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function execStatusTag(v) { var s = findBy(EXEC_STATUS, 'v', v); return s ? s.t : 'info'; }
  function skinResultLabel(v) { var s = findBy(SKIN_RESULTS, 'v', v); return s ? s.l : (v === 0 ? '观察中' : (v == null ? '-' : v)); }
  function skinResultTag(v) { var s = findBy(SKIN_RESULTS, 'v', v); return s ? s.t : 'info'; }
  function allergenTypeLabel(v) { var s = findBy(ALLERGEN_TYPES, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function allergenTypeTag(v) { var s = findBy(ALLERGEN_TYPES, 'v', v); return s ? s.t : 'info'; }
  function severityLabel(v) { var s = findBy(SEVERITIES, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  function severityTag(v) { var s = findBy(SEVERITIES, 'v', v); return s ? s.t : 'info'; }
  function sourceLabel(v) { var s = findBy(ALLERGY_SOURCES, 'v', v); return s ? s.l : (v == null ? '-' : v); }
  /* LocalDateTime 序列化为 ISO(含T), 展示转空格并截到秒 */
  function fmtTime(v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; }
  /* 秒数 -> "X小时Y分" / "Y分Z秒"(输液已输时长) */
  function fmtDur(sec) {
    var s = sec == null ? null : Number(sec);
    if (s == null || isNaN(s) || s < 0) { return '-'; }
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var ss = s % 60;
    if (h > 0) { return h + '小时' + m + '分'; }
    if (m > 0) { return m + '分' + ss + '秒'; }
    return ss + '秒';
  }
  /* 秒数 -> "m:ss"(皮试倒计时) */
  function fmtClock(sec) {
    var s = sec == null ? null : Number(sec);
    if (s == null || isNaN(s)) { return '-'; }
    var m = Math.floor(s / 60);
    var ss = s % 60;
    return m + ':' + ('0' + ss).slice(-2);
  }
  function today() {
    var d = new Date();
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var day = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + day;
  }

  /* 共用下拉加载: 科室(启用中, 项目惯例 enabled 扁平列表) / 护士 */
  function loadDeptOpts(vm) {
    HIS.get('/api/his/dept/enabled').then(function (list) { vm.deptDefs = list || []; }).catch(HIS.notifyError);
  }
  function loadNurseOpts(vm) {
    HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('护士') + '&withSubOrgs=false')
      .then(function (list) { vm.nurseOpts = list || []; }).catch(HIS.notifyError);
  }
  /* 弹窗用患者横幅(各组件模板直取字段拼装, 此处仅统一空值兜底) */
  function patientBanner(row) {
    return row || {};
  }

  /* ================= 1. 待执行医嘱工作台(三查七对) =================
   * 已缴费的注射/输液/皮试/换药执行单: 急诊优先先开先做, 30秒自动轮询;
   * 过敏史患者整行淡红警示+图标; 开始执行前须完成三查七对勾选;
   * 页签"执行中"承接注射/换药类的完成/取消闭环(皮试/输液在专门页签完成)。
   */
  HIS.views.NursePending = {
    data: function () {
      return {
        tab: '',
        statusTab: '0',
        loading: false, list: [],
        deptId: null, deptDefs: [], keyword: '',
        /* 三查七对弹窗 */
        checkVisible: false, checkRow: null, checkedItems: [], confirming: false,
        /* 完成执行弹窗 */
        finishVisible: false, finishRow: null, finishResponse: '', finishing: false,
        /* 取消执行弹窗 */
        cancelVisible: false, cancelRow: null, cancelReason: '', cancelling: false,
        checkItems: CHECK_ITEMS,
        execTypeLabel: execTypeLabel, execTypeTag: execTypeTag, fmtTime: fmtTime
      };
    },
    created: function () {
      var vm = this;
      loadDeptOpts(vm);
      vm.load();
      vm.startAutoRefresh();
    },
    /* Vue3 销毁钩子: 清理轮询定时器防泄漏 */
    beforeUnmount: function () { this.stopAutoRefresh(); },
    methods: {
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/nurse/pending?execStatus=' + vm.statusTab;
        if (vm.deptId) { q += '&deptId=' + vm.deptId; }
        if (vm.tab) { q += '&execType=' + encodeURIComponent(vm.tab); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (list) {
          vm.list = list || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.load(); },
      seqNo: function (i) { return i + 1; },
      rowClass: function (opt) {
        return opt && opt.row && opt.row.allergyCount > 0 ? 'ns-allergy-row' : '';
      },
      /* ===== 开始执行(三查七对) ===== */
      openStart: function (row) {
        this.checkRow = row;
        this.checkedItems = [];
        this.checkVisible = true;
      },
      doStart: function () {
        var vm = this;
        if (!vm.checkRow) { return; }
        if (vm.checkedItems.length < CHECK_ITEMS.length) {
          ElementPlus.ElMessage.warning('请先完成三查七对核对清单(全部勾选)');
          return;
        }
        vm.confirming = true;
        HIS.post('/api/nurse/exec/' + vm.checkRow.execId + '/start').then(function (d) {
          var tip = '已开始执行 ' + ((d && d.execNo) || '');
          if (vm.checkRow.execType === 'skin_test') {
            tip += ', 请到皮试管理页签开始皮试观察';
          } else if (vm.checkRow.execType === 'infusion') {
            tip += ', 请到输液管理页签配液穿刺';
          }
          HIS.notifySuccess(tip);
          vm.checkVisible = false;
          vm.load(true);
        }).catch(HIS.notifyError).finally(function () { vm.confirming = false; });
      },
      /* ===== 完成执行 ===== */
      openFinish: function (row) {
        this.finishRow = row;
        this.finishResponse = '';
        this.finishVisible = true;
      },
      doFinish: function () {
        var vm = this;
        if (!vm.finishRow) { return; }
        vm.finishing = true;
        HIS.post('/api/nurse/exec/' + vm.finishRow.execId + '/finish', { response: (vm.finishResponse || '').trim() || null })
          .then(function (d) {
            var tip = '执行完成' + (d && d.endTime ? ' (' + d.endTime + ')' : '');
            if (d && d.orderFinished) { tip += ', 同医嘱单已全部执行完毕'; }
            HIS.notifySuccess(tip);
            vm.finishVisible = false;
            vm.load(true);
          }).catch(HIS.notifyError).finally(function () { vm.finishing = false; });
      },
      /* ===== 取消执行 ===== */
      openCancel: function (row) {
        this.cancelRow = row;
        this.cancelReason = '';
        this.cancelVisible = true;
      },
      doCancel: function () {
        var vm = this;
        if (!vm.cancelRow) { return; }
        if (!(vm.cancelReason || '').trim()) {
          ElementPlus.ElMessage.warning('请填写取消原因');
          return;
        }
        vm.cancelling = true;
        HIS.post('/api/nurse/exec/' + vm.cancelRow.execId + '/cancel', { reason: vm.cancelReason.trim() })
          .then(function () {
            HIS.notifySuccess('已取消执行');
            vm.cancelVisible = false;
            vm.load(true);
          }).catch(HIS.notifyError).finally(function () { vm.cancelling = false; });
      },
      startAutoRefresh: function () {
        var vm = this;
        vm.refreshTimer = setInterval(function () { vm.load(true); }, 30 * 1000);
      },
      stopAutoRefresh: function () {
        if (this.refreshTimer) { clearInterval(this.refreshTimer); this.refreshTimer = null; }
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">待执行医嘱 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(已缴费的注射/输液/皮试/换药 · 急诊优先先开先做 · 列表每30秒自动刷新)</span></div>',
      '  <div class="toolbar">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">科室</span>',
      '    <el-select v-model="deptId" clearable placeholder="全部科室" style="width:170px" @change="search">',
      '      <el-option v-for="d in deptDefs" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-radio-group v-model="statusTab" @change="search">',
      '      <el-radio-button label="0">待执行</el-radio-button>',
      '      <el-radio-button label="1">执行中</el-radio-button>',
      '    </el-radio-group>',
      '    <el-input v-model="keyword" placeholder="患者姓名/执行单号/医嘱号" clearable style="width:230px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 条</span>',
      '  </div>',
      '  <el-tabs v-model="tab" @tab-change="search">',
      '    <el-tab-pane label="全部" name=""></el-tab-pane>',
      '    <el-tab-pane label="皮试" name="skin_test"></el-tab-pane>',
      '    <el-tab-pane label="输液" name="infusion"></el-tab-pane>',
      '    <el-tab-pane label="注射" name="injection"></el-tab-pane>',
      '    <el-tab-pane label="换药" name="dressing"></el-tab-pane>',
      '  </el-tabs>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%" :row-class-name="rowClass">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="185">',
      '      <template #default="s">',
      '        <span class="ns-strong">{{ s.row.patientName || \'-\' }}</span>',
      '        <span class="ns-soft"> {{ s.row.genderName || \'-\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }}</span>',
      '        <el-tooltip v-if="s.row.allergyCount > 0" :content="\'过敏史: \' + (s.row.allergyNames || \'详见过敏档案\')" placement="top">',
      '          <el-tag size="small" type="danger" effect="dark" style="margin-left:4px;">过敏</el-tag>',
      '        </el-tooltip>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column label="医嘱内容" min-width="200" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || \'-\' }}<span v-if="s.row.spec" class="ns-soft"> {{ s.row.spec }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="剂量" width="90" align="center">',
      '      <template #default="s">{{ s.row.quantity != null ? s.row.quantity : \'-\' }}{{ s.row.unit ? \' \' + s.row.unit : \'\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="执行类型" width="80" align="center">',
      '      <template #default="s"><el-tag size="small" :type="execTypeTag(s.row.execType)">{{ execTypeLabel(s.row.execType) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="紧急" width="70" align="center">',
      '      <template #default="s"><span v-if="s.row.medType === \'14\'" class="ns-emergency ns-blink">急诊</span><span v-else class="ns-soft">普通</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="开单医生" width="140">',
      '      <template #default="s">{{ s.row.doctorName || \'-\' }}<span v-if="s.row.orderDeptName" class="ns-soft"> {{ s.row.orderDeptName }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="开单时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.orderTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="执行单号" width="145" align="center">',
      '      <template #default="s">{{ s.row.execNo || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="170" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button v-if="s.row.execStatus === 0" type="primary" size="small" @click="openStart(s.row)">开始执行</el-button>',
      '        <template v-else>',
      '          <el-button type="success" size="small" @click="openFinish(s.row)">完成</el-button>',
      '          <el-button type="info" size="small" plain @click="openCancel(s.row)">取消</el-button>',
      '        </template>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-empty v-if="!loading && !list.length" description="暂无待执行医嘱" :image-size="60"></el-empty>',

      /* 三查七对确认弹窗 */
      '  <el-dialog v-model="checkVisible" title="三查七对确认" width="540px" :close-on-click-modal="false">',
      '    <div v-if="checkRow">',
      '      <div class="ns-banner">',
      '        <span class="name">{{ checkRow.patientName }}</span>',
      '        <span class="ns-soft">{{ checkRow.genderName }} {{ checkRow.age != null ? checkRow.age + \'岁\' : \'\' }}</span>',
      '        <div class="row">医嘱: {{ checkRow.itemName || \'-\' }} {{ checkRow.spec || \'\' }} {{ checkRow.quantity != null ? checkRow.quantity : \'\' }}{{ checkRow.unit || \'\' }}</div>',
      '        <div class="row">执行单: {{ checkRow.execNo }} · 开单: {{ checkRow.doctorName || \'-\' }} {{ checkRow.orderTime || \'\' }}</div>',
      '      </div>',
      '      <div v-if="checkRow.allergyCount > 0" class="ns-allergy-alert">',
      '        ⚠ 该患者有过敏史: {{ checkRow.allergyNames || \'详见过敏档案\' }} — 用药前须再次核对!',
      '      </div>',
      '      <div style="font-weight:600;margin:6px 0 4px;">执行前核对清单(须全部勾选)</div>',
      '      <el-checkbox-group v-model="checkedItems">',
      '        <el-checkbox v-for="(c, i) in checkItems" :key="i" :label="i" class="ns-check-item">{{ c }}</el-checkbox>',
      '      </el-checkbox-group>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="checkVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="confirming" :disabled="checkedItems.length < checkItems.length" @click="doStart">确认执行</el-button>',
      '    </template>',
      '  </el-dialog>',

      /* 完成执行弹窗 */
      '  <el-dialog v-model="finishVisible" title="完成执行" width="460px">',
      '    <div v-if="finishRow" class="ns-banner">',
      '      <span class="name">{{ finishRow.patientName }}</span>',
      '      <div class="row">执行单: {{ finishRow.execNo }} · {{ finishRow.itemName || \'-\' }}</div>',
      '    </div>',
      '    <div style="font-weight:600;margin-bottom:6px;">患者反应</div>',
      '    <el-input type="textarea" v-model="finishResponse" :rows="3" placeholder="如: 无不适 / 局部轻微疼痛等, 可留空"></el-input>',
      '    <template #footer>',
      '      <el-button @click="finishVisible = false">取消</el-button>',
      '      <el-button type="success" :loading="finishing" @click="doFinish">确认完成</el-button>',
      '    </template>',
      '  </el-dialog>',

      /* 取消执行弹窗 */
      '  <el-dialog v-model="cancelVisible" title="取消执行" width="460px">',
      '    <div v-if="cancelRow" class="ns-banner">',
      '      <span class="name">{{ cancelRow.patientName }}</span>',
      '      <div class="row">执行单: {{ cancelRow.execNo }} · {{ cancelRow.itemName || \'-\' }}</div>',
      '    </div>',
      '    <div style="font-weight:600;margin-bottom:6px;">取消原因(必填)</div>',
      '    <el-input type="textarea" v-model="cancelReason" :rows="3" placeholder="如: 患者拒绝 / 医嘱作废 / 药品缺货等"></el-input>',
      '    <template #footer>',
      '      <el-button @click="cancelVisible = false">返回</el-button>',
      '      <el-button type="warning" :loading="cancelling" @click="doCancel">确认取消</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 2. 皮试管理(20分钟观察窗) =================
   * 三Tab: 待皮试(开窗) / 观察中(倒计时进度条, 1秒tick + 30秒列表校准) / 已完成;
   * 阳性结果必填症状描述, 确认后自动写入过敏档案并记录通知医生时间(前端二次确认)。
   */
  HIS.views.NurseSkinTest = {
    data: function () {
      return {
        tab: '0',
        loading: false, list: [], total: 0, page: 1, size: 20,
        /* 开始皮试弹窗 */
        startVisible: false, startRow: null, startDose: '0.1mL', starting: false,
        /* 录入结果弹窗 */
        resultVisible: false, resultRow: null, resultValue: 1, resultDesc: '', saving: false,
        skinResultLabel: skinResultLabel, skinResultTag: skinResultTag, fmtTime: fmtTime
      };
    },
    created: function () { this.load(); this.startTimers(); },
    /* Vue3 销毁钩子: 清理倒计时/轮询定时器防泄漏 */
    beforeUnmount: function () { this.stopTimers(); },
    methods: {
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/nurse/skin-tests?resultStatus=' + vm.tab + '&page=' + vm.page + '&size=' + vm.size;
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 倒计时: 每秒本地自减(观察中行), 每30秒刷新列表校准服务端口径 */
      startTimers: function () {
        var vm = this;
        vm.tickTimer = setInterval(function () {
          if (vm.tab === '1') {
            (vm.list || []).forEach(function (r) {
              if (r.remainingSeconds != null) { r.remainingSeconds = Number(r.remainingSeconds) - 1; }
              if (r.elapsedSeconds != null) { r.elapsedSeconds = Number(r.elapsedSeconds) + 1; }
            });
          }
        }, 1000);
        vm.refreshTimer = setInterval(function () { vm.load(true); }, 30 * 1000);
      },
      stopTimers: function () {
        if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = null; }
        if (this.refreshTimer) { clearInterval(this.refreshTimer); this.refreshTimer = null; }
      },
      progressOf: function (row) {
        var total = 20 * 60;
        var elapsed = row.elapsedSeconds == null ? 0 : Number(row.elapsedSeconds);
        if (elapsed < 0) { elapsed = 0; }
        var pct = Math.round(elapsed / total * 100);
        if (pct < 0) { pct = 0; }
        if (pct > 100) { pct = 100; }
        return pct;
      },
      countdownOf: function (row) {
        var r = row.remainingSeconds == null ? null : Number(row.remainingSeconds);
        if (r == null || isNaN(r)) { return '-'; }
        if (r <= 0) { return '已到观察窗'; }
        return '剩 ' + fmtClock(r);
      },
      /* ===== 开始皮试(开窗) ===== */
      openStart: function (row) {
        this.startRow = row;
        this.startDose = '0.1mL';
        this.startVisible = true;
      },
      doStart: function () {
        var vm = this;
        if (!vm.startRow) { return; }
        if (!(vm.startDose || '').trim()) {
          ElementPlus.ElMessage.warning('请填写皮试剂量(如 0.1mL)');
          return;
        }
        vm.starting = true;
        HIS.post('/api/nurse/skin-test', {
          execId: vm.startRow.execId,
          drugId: vm.startRow.drugId || null,
          drugName: vm.startRow.itemName || vm.startRow.drugName,
          testDose: vm.startDose.trim()
        }).then(function (t) {
          HIS.notifySuccess('皮试已开始, 观察窗20分钟(' + fmtTime(t && t.observeEnd) + ' 到期)');
          vm.startVisible = false;
          vm.tab = '1';
          vm.page = 1;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.starting = false; });
      },
      /* ===== 录入结果 ===== */
      openResult: function (row) {
        this.resultRow = row;
        this.resultValue = 1;
        this.resultDesc = '';
        this.resultVisible = true;
      },
      doResult: function () {
        var vm = this;
        if (!vm.resultRow || !vm.resultValue) { return; }
        if (vm.resultValue === 2 && !(vm.resultDesc || '').trim()) {
          ElementPlus.ElMessage.warning('阳性结果必须填写症状描述');
          return;
        }
        var run = function () {
          vm.saving = true;
          HIS.post('/api/nurse/skin-test/' + vm.resultRow.testId + '/result', {
            result: vm.resultValue,
            resultDesc: (vm.resultDesc || '').trim() || null
          }).then(function (d) {
            var tip = '已录入结果: ' + ((d && d.resultLabel) || '');
            if (d && d.allergyWritten) { tip += ', 已自动写入过敏档案并记录通知医生时间'; }
            if (d && d.allergySkipped) { tip += ', 过敏档案已有同名过敏原, 未重复建档'; }
            if (d && d.orderFinished) { tip += ', 医嘱单已全部执行完毕'; }
            HIS.notifySuccess(tip);
            vm.resultVisible = false;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
        };
        /* 阳性: 先确认"自动写入过敏档案"再提交 */
        if (vm.resultValue === 2) {
          ElementPlus.ElMessageBox.confirm(
            '阳性结果将自动写入该患者的过敏档案(药物·中度)并记录通知医生时间, 请再次核对结果后确认录入。',
            '阳性确认', { type: 'warning', confirmButtonText: '确认录入', cancelButtonText: '再核对' }
          ).then(run).catch(function (e) {
            if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
          });
        } else {
          run();
        }
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">皮试管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(皮内注射 · 观察窗20分钟 · 到窗须录入结果)</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',
      '    <el-tab-pane label="待皮试" name="0"></el-tab-pane>',
      '    <el-tab-pane label="观察中" name="1"></el-tab-pane>',
      '    <el-tab-pane label="已完成" name="2"></el-tab-pane>',
      '  </el-tabs>',
      '  <div class="toolbar">',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条 · 每30秒自动刷新</span>',
      '  </div>',

      /* 待皮试 */
      '  <el-table v-if="tab === \'0\'" :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="185">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span><span class="ns-soft"> {{ s.row.genderName || \'-\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="药品" min-width="200" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || \'-\' }}<span v-if="s.row.spec" class="ns-soft"> {{ s.row.spec }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="开单医生" width="120">',
      '      <template #default="s">{{ s.row.doctorName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="开单时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.createTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="120" align="center" fixed="right">',
      '      <template #default="s"><el-button type="warning" size="small" @click="openStart(s.row)">开始皮试</el-button></template>',
      '    </el-table-column>',
      '  </el-table>',

      /* 观察中 */
      '  <el-table v-if="tab === \'1\'" :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="165">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span><span class="ns-soft"> {{ s.row.genderName || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="药品" min-width="180" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || s.row.drugName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="剂量" width="80" align="center">',
      '      <template #default="s">{{ s.row.testDose || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="开始时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.observeStart || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="观察进度(20分钟)" width="250">',
      '      <template #default="s">',
      '        <el-progress :percentage="progressOf(s.row)" :stroke-width="14" :text-inside="true" :status="s.row.remainingSeconds <= 0 ? \'exception\' : undefined" style="width:170px;display:inline-block;vertical-align:middle;"></el-progress>',
      '        <span :class="(s.row.remainingSeconds != null && s.row.remainingSeconds <= 0) ? \'ns-overdue ns-blink\' : \'ns-soft\'" style="margin-left:6px;">{{ countdownOf(s.row) }}</span>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="120" align="center" fixed="right">',
      '      <template #default="s"><el-button type="primary" size="small" @click="openResult(s.row)">录入结果</el-button></template>',
      '    </el-table-column>',
      '  </el-table>',

      /* 已完成 */
      '  <el-table v-if="tab === \'2\'" :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="145">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="药品" min-width="170" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || s.row.drugName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="开始/结束" width="300" align="center">',
      '      <template #default="s">{{ s.row.observeStart || \'-\' }} ~ {{ s.row.observeEnd || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="结果" width="80" align="center">',
      '      <template #default="s"><el-tag size="small" :type="skinResultTag(s.row.result)">{{ skinResultLabel(s.row.result) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="症状描述" min-width="150" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.resultDesc || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="过敏档案" width="100" align="center">',
      '      <template #default="s"><el-tag v-if="s.row.result === 2" size="small" type="danger">已写入</el-tag><span v-else class="ns-soft">-</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="通知医生" width="150" align="center">',
      '      <template #default="s">{{ s.row.notifyDoctorTime || \'-\' }}</template>',
      '    </el-table-column>',
      '  </el-table>',

      '  <el-pagination v-if="total" style="margin-top:10px;justify-content:flex-end;" layout="total, sizes, prev, pager, next, jumper" :total="total" :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-empty v-if="!loading && !list.length" :description="tab === \'0\' ? \'暂无待皮试医嘱\' : (tab === \'1\' ? \'暂无观察中的皮试\' : \'暂无已完成皮试\')" :image-size="60"></el-empty>',

      /* 开始皮试弹窗 */
      '  <el-dialog v-model="startVisible" title="开始皮试(皮内注射)" width="480px" :close-on-click-modal="false">',
      '    <div v-if="startRow">',
      '      <div class="ns-banner">',
      '        <span class="name">{{ startRow.patientName }}</span>',
      '        <span class="ns-soft">{{ startRow.genderName }} {{ startRow.age != null ? startRow.age + \'岁\' : \'\' }}</span>',
      '        <div class="row">药品: {{ startRow.itemName || \'-\' }} {{ startRow.spec || \'\' }}</div>',
      '        <div class="row">执行单: {{ startRow.execNo }} · 开单: {{ startRow.doctorName || \'-\' }}</div>',
      '      </div>',
      '      <div style="font-weight:600;margin-bottom:6px;">皮试剂量</div>',
      '      <el-input v-model="startDose" style="width:160px" placeholder="0.1mL"></el-input>',
      '      <div class="ns-soft" style="margin-top:8px;">确认后开始计时, 观察窗20分钟, 期间请患者留观勿离开。</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="startVisible = false">取消</el-button>',
      '      <el-button type="warning" :loading="starting" @click="doStart">确认开始皮试</el-button>',
      '    </template>',
      '  </el-dialog>',

      /* 录入结果弹窗 */
      '  <el-dialog v-model="resultVisible" title="录入皮试结果" width="480px" :close-on-click-modal="false">',
      '    <div v-if="resultRow">',
      '      <div class="ns-banner">',
      '        <span class="name">{{ resultRow.patientName }}</span>',
      '        <div class="row">药品: {{ resultRow.itemName || resultRow.drugName || \'-\' }} · 剂量 {{ resultRow.testDose || \'-\' }}</div>',
      '        <div class="row">观察窗: {{ resultRow.observeStart || \'-\' }} ~ {{ resultRow.observeEnd || \'-\' }}</div>',
      '      </div>',
      '      <el-radio-group v-model="resultValue">',
      '        <el-radio :label="1">阴性</el-radio>',
      '        <el-radio :label="2">阳性</el-radio>',
      '        <el-radio :label="3">可疑</el-radio>',
      '      </el-radio-group>',
      '      <div style="font-weight:600;margin:10px 0 6px;">症状描述</div>',
      '      <el-input type="textarea" v-model="resultDesc" :rows="3" placeholder="阳性必填: 局部红肿/皮丘隆起/伪足/痒感等; 阴性/可疑可简述"></el-input>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="resultVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="doResult">提交结果</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 3. 输液管理(配液/穿刺/巡视/拔针) =================
   * 四Tab: 待配液 / 输液中(已输时长实时计算, 1秒tick + 30秒列表校准) / 待拔针(已到预计结束) / 已完成;
   * 预计结束时间 = 穿刺时间 + 容量(ml)*15滴/ml / 滴速 * 60(后端折算, 前端本地按穿刺时间推算更稳);
   * 巡视记录追加式留痕(JSON数组), 拔针联动完成执行单。
   */
  HIS.views.NurseInfusion = {
    data: function () {
      return {
        tab: 'prepare',
        loading: false, list: [], total: 0, page: 1, size: 20,
        /* 配液弹窗(配液即穿刺合并: 溶液+座位+滴速+穿刺部位一步完成) */
        prepVisible: false, prepRow: null, prepSeat: '', prepSolution: '', prepRate: 60, prepSite: '', prepping: false,
        /* 巡视弹窗 */
        patrolVisible: false, patrolRow: null, patrolRate: null, patrolStatus: '正常', patrolNote: '', patrolling: false,
        patrolStatuses: PATROL_STATUS,
        execTypeLabel: execTypeLabel, fmtTime: fmtTime
      };
    },
    created: function () { this.load(); this.startTimers(); },
    /* Vue3 销毁钩子: 清理倒计时/轮询定时器防泄漏 */
    beforeUnmount: function () { this.stopTimers(); },
    methods: {
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/nurse/infusions?stage=' + vm.tab + '&page=' + vm.page + '&size=' + vm.size;
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          /* 列表加载后立即校准巡视超时分钟数(此后每秒 tick 刷新) */
          (vm.list || []).forEach(function (r) { r._patrolMin = vm.patrolMinutes(r); });
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 已输时长实时: 每秒本地自增(输液中/待拔针页签), 每30秒刷新列表校准;
       * 同时刷新巡视超时分钟数(_patrolMin), 驱动超时行标红与"巡视超时"闪烁 */
      startTimers: function () {
        var vm = this;
        vm.tickTimer = setInterval(function () {
          if (vm.tab === 'infusing' || vm.tab === 'ready_remove') {
            (vm.list || []).forEach(function (r) {
              if (r.elapsedSeconds != null) { r.elapsedSeconds = Number(r.elapsedSeconds) + 1; }
              r._patrolMin = vm.patrolMinutes(r);
            });
          }
        }, 1000);
        vm.refreshTimer = setInterval(function () { vm.load(true); }, 30 * 1000);
      },
      stopTimers: function () {
        if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = null; }
        if (this.refreshTimer) { clearInterval(this.refreshTimer); this.refreshTimer = null; }
      },
      /* 预计结束时间: 穿刺时间 + 预计输注秒数(前端本地推算, 不随刷新漂移); 无折算依据时显示后端口径 */
      estEndOf: function (row) {
        if (!row || !row.punctureTime || row.estSeconds == null) { return (row && row.estEndTime) || '-'; }
        var t = new Date(String(row.punctureTime).replace(' ', 'T')).getTime();
        if (isNaN(t)) { return row.estEndTime || '-'; }
        var d = new Date(t + Number(row.estSeconds) * 1000);
        var p = function (n) { return ('0' + n).slice(-2); };
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
      },
      elapsedText: function (row) { return fmtDur(row.elapsedSeconds); },
      patrolList: function (row) {
        try { return JSON.parse((row && row.patrolRecords) || '[]') || []; } catch (e) { return []; }
      },
      /* 距最近一次巡视的分钟数(从未巡视按穿刺时间起算), 无法解析返回 null */
      patrolMinutes: function (row) {
        var arr = this.patrolList(row);
        var base = arr.length ? arr[arr.length - 1].time : (row && row.punctureTime);
        if (!base) { return null; }
        var t = new Date(String(base).replace(' ', 'T')).getTime();
        if (isNaN(t)) { return null; }
        return Math.floor((Date.now() - t) / 60000);
      },
      /* 巡视超时判定: 超30分钟未巡视(口径与后端 InfusionService.PATROL_OVERTIME_MINUTES 一致) */
      isPatrolDue: function (row) {
        var m = row && row._patrolMin != null ? Number(row._patrolMin) : null;
        return m != null && !isNaN(m) && m >= 30;
      },
      /* 超时行整行淡红 */
      rowClass: function (p) {
        return this.isPatrolDue(p && p.row) ? 'ns-patrol-row' : '';
      },
      /* ===== 配液(创建输液记录) ===== */
      openPrep: function (row) {
        this.prepRow = row;
        this.prepSeat = '';
        this.prepSolution = row.itemName || '';
        this.prepRate = 60;
        this.prepSite = '';
        this.prepVisible = true;
      },
      doPrep: function () {
        var vm = this;
        if (!vm.prepRow) { return; }
        if (!(vm.prepSolution || '').trim()) {
          ElementPlus.ElMessage.warning('请填写溶液(液体名称与容量, 如 0.9%氯化钠 250ml)');
          return;
        }
        if (!(vm.prepSite || '').trim()) {
          ElementPlus.ElMessage.warning('请填写穿刺部位(如左手背)');
          return;
        }
        var rate = vm.prepRate == null || vm.prepRate === '' ? null : Number(vm.prepRate);
        if (rate != null && (isNaN(rate) || rate < 1 || rate > 300)) {
          ElementPlus.ElMessage.warning('滴速须在 1-300 滴/分之间');
          return;
        }
        vm.prepping = true;
        // 配液即穿刺(合并): 先建输液记录拿 infusionId, 紧接着记穿刺, 一步进入"输液中"页签
        HIS.post('/api/nurse/infusion', {
          execId: vm.prepRow.execId,
          seatNo: (vm.prepSeat || '').trim() || null,
          solution: vm.prepSolution.trim(),
          dripRate: rate
        }).then(function (rec) {
          return HIS.post('/api/nurse/infusion/' + rec.id + '/puncture', { site: vm.prepSite.trim() });
        }).then(function () {
          HIS.notifySuccess('配液穿刺完成, 已进入输液中');
          vm.prepVisible = false;
          vm.tab = 'infusing';
          vm.page = 1;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.prepping = false; });
      },
      /* ===== 巡视 ===== */
      openPatrol: function (row) {
        this.patrolRow = row;
        this.patrolRate = row.dripRate != null ? row.dripRate : null;
        this.patrolStatus = '正常';
        this.patrolNote = '';
        this.patrolVisible = true;
      },
      doPatrol: function () {
        var vm = this;
        if (!vm.patrolRow) { return; }
        var rate = vm.patrolRate == null || vm.patrolRate === '' ? null : Number(vm.patrolRate);
        var patrol = {
          status: vm.patrolStatus || '正常',
          note: (vm.patrolNote || '').trim() || ''
        };
        if (rate != null) {
          if (isNaN(rate) || rate < 1 || rate > 300) {
            ElementPlus.ElMessage.warning('滴速须在 1-300 滴/分之间');
            return;
          }
          patrol.dripRate = rate;
        }
        vm.patrolling = true;
        HIS.post('/api/nurse/infusion/' + vm.patrolRow.infusionId + '/patrol', {
          patrolJson: JSON.stringify(patrol)
        }).then(function (d) {
          HIS.notifySuccess('已记录巡视(累计' + ((d && d.patrolCount) || 1) + '次)');
          vm.patrolVisible = false;
          vm.load(true);
        }).catch(HIS.notifyError).finally(function () { vm.patrolling = false; });
      },
      /* ===== 拔针 ===== */
      doRemove: function (row) {
        var vm = this;
        var msg = '确认对 ' + (row.patientName || '') + (row.seatNo ? '(' + row.seatNo + '座)' : '') + ' 拔针？拔针后输液执行单将完成, 同医嘱单全完成则医嘱单闭环。';
        ElementPlus.ElMessageBox.confirm(msg, '拔针确认', { type: 'warning', confirmButtonText: '确认拔针', cancelButtonText: '取消' })
          .then(function () {
            return HIS.post('/api/nurse/infusion/' + row.infusionId + '/remove');
          })
          .then(function (d) {
            var tip = '已拔针' + (d && d.removeTime ? ' (' + d.removeTime + ')' : '');
            if (d && d.patrolCount) { tip += ', 全程巡视 ' + d.patrolCount + '次'; }
            if (d && d.orderFinished) { tip += ', 医嘱单已全部执行完毕'; }
            HIS.notifySuccess(tip);
            vm.load(true);
          })
          .catch(function (e) {
            if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
          });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">输液管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(配液 · 穿刺 · 巡视 · 拔针 · 到预计结束自动转"待拔针")</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',
      '    <el-tab-pane label="待配液" name="prepare"></el-tab-pane>',
      '    <el-tab-pane label="输液中" name="infusing"></el-tab-pane>',
      '    <el-tab-pane label="待拔针" name="ready_remove"></el-tab-pane>',
      '    <el-tab-pane label="已完成" name="done"></el-tab-pane>',
      '  </el-tabs>',
      '  <div class="toolbar">',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条 · 每30秒自动刷新</span>',
      '  </div>',

      /* 待配液 */
      '  <el-table v-if="tab === \'prepare\'" :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="185">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span><span class="ns-soft"> {{ s.row.genderName || \'-\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="医嘱(药品)" min-width="220" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || \'-\' }}<span v-if="s.row.spec" class="ns-soft"> {{ s.row.spec }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="开单医生" width="120">',
      '      <template #default="s">{{ s.row.doctorName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="开单时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.createTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="执行单号" width="145" align="center">',
      '      <template #default="s">{{ s.row.execNo || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="120" align="center" fixed="right">',
      '      <template #default="s"><el-button type="primary" size="small" @click="openPrep(s.row)">配液穿刺</el-button></template>',
      '    </el-table-column>',
      '  </el-table>',

      /* 输液中 / 待拔针(同结构, 待拔针突出红色; 巡视超30分钟整行淡红+巡视列红字闪烁) */
      '  <el-table v-if="tab === \'infusing\' || tab === \'ready_remove\'" :data="list" v-loading="loading" border stripe size="small" height="100%" :row-class-name="rowClass">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="150">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span><span class="ns-soft"> {{ s.row.genderName || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="座位" width="70" align="center">',
      '      <template #default="s">{{ s.row.seatNo || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="药品/溶液" min-width="190" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || \'-\' }}<span v-if="s.row.solution" class="ns-soft"> · {{ s.row.solution }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="滴速" width="85" align="center">',
      '      <template #default="s">{{ s.row.dripRate != null ? s.row.dripRate + \'滴/分\' : \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="已输时长" width="105" align="center">',
      '      <template #default="s"><span :class="tab === \'ready_remove\' ? \'ns-overdue\' : \'\'">{{ elapsedText(s.row) }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="预计结束" width="140" align="center">',
      '      <template #default="s">{{ estEndOf(s.row) }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="巡视" width="100" align="center">',
      '      <template #default="s">',
      '        <span :class="{ \'ns-patrol-due\': isPatrolDue(s.row) }">{{ s.row.patrolCount != null ? s.row.patrolCount : 0 }}次</span>',
      '        <div v-if="isPatrolDue(s.row)" class="ns-patrol-due" style="font-size:11px;line-height:1.4;">巡视超时</div>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="170" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button type="primary" size="small" plain @click="openPatrol(s.row)">巡视</el-button>',
      '        <el-button :type="tab === \'ready_remove\' ? \'danger\' : \'success\'" size="small" @click="doRemove(s.row)">{{ tab === \'ready_remove\' ? \'拔针\' : \'拔针\' }}</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',

      /* 已完成(展开看巡视记录) */
      '  <el-table v-if="tab === \'done\'" :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="expand">',
      '      <template #default="s">',
      '        <div style="padding:4px 16px 10px;">',
      '          <div style="font-weight:600;margin-bottom:6px;">巡视记录({{ patrolList(s.row).length }}次)</div>',
      '          <el-table :data="patrolList(s.row)" size="small" border>',
      '            <el-table-column label="巡视时间" width="150" align="center"><template #default="e">{{ e.row.time || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="滴速(滴/分)" width="100" align="center"><template #default="e">{{ e.row.dripRate != null ? e.row.dripRate : \'-\' }}</template></el-table-column>',
      '            <el-table-column label="患者状态" width="140"><template #default="e">{{ e.row.status || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="备注" min-width="150"><template #default="e">{{ e.row.note || \'-\' }}</template></el-table-column>',
      '          </el-table>',
      '          <el-empty v-if="!patrolList(s.row).length" description="无巡视记录" :image-size="40"></el-empty>',
      '          <div class="ns-soft" style="margin-top:8px;">穿刺: {{ s.row.punctureTime || \'-\' }} {{ s.row.punctureSite || \'\' }} {{ s.row.punctureNurseName || \'\' }} · 拔针: {{ s.row.removeTime || \'-\' }} {{ s.row.removeNurseName || \'\' }}</div>',
      '        </div>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="150">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="座位" width="70" align="center">',
      '      <template #default="s">{{ s.row.seatNo || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="药品/溶液" min-width="180" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || \'-\' }}<span v-if="s.row.solution" class="ns-soft"> · {{ s.row.solution }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="穿刺时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.punctureTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="拔针时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.removeTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="巡视" width="60" align="center">',
      '      <template #default="s">{{ s.row.patrolCount != null ? s.row.patrolCount : 0 }}次</template>',
      '    </el-table-column>',
      '    <el-table-column label="状态" width="80" align="center">',
      '      <template #default="s"><el-tag size="small" :type="s.row.execStatus === 2 ? \'success\' : \'info\'">{{ s.row.execStatus === 2 ? \'已完成\' : \'已取消\' }}</el-tag></template>',
      '    </el-table-column>',
      '  </el-table>',

      '  <el-pagination v-if="total" style="margin-top:10px;justify-content:flex-end;" layout="total, sizes, prev, pager, next, jumper" :total="total" :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-empty v-if="!loading && !list.length" description="暂无输液记录" :image-size="60"></el-empty>',

      /* 配液弹窗 */
      '  <el-dialog v-model="prepVisible" title="配液穿刺(创建输液记录并穿刺)" width="500px" :close-on-click-modal="false">',
      '    <div v-if="prepRow">',
      '      <div class="ns-banner">',
      '        <span class="name">{{ prepRow.patientName }}</span>',
      '        <span class="ns-soft">{{ prepRow.genderName }} {{ prepRow.age != null ? prepRow.age + \'岁\' : \'\' }}</span>',
      '        <div class="row">医嘱: {{ prepRow.itemName || \'-\' }} {{ prepRow.spec || \'\' }}</div>',
      '        <div class="row">执行单: {{ prepRow.execNo }} · 开单: {{ prepRow.doctorName || \'-\' }}</div>',
      '      </div>',
      '      <div style="display:flex;gap:10px;margin-bottom:10px;">',
      '        <div><div style="font-weight:600;margin-bottom:6px;">座位号</div>',
      '          <el-input v-model="prepSeat" style="width:120px" placeholder="如 A12"></el-input></div>',
      '        <div style="flex:1;"><div style="font-weight:600;margin-bottom:6px;">滴速(滴/分)</div>',
      '          <el-input-number v-model="prepRate" :min="1" :max="300" style="width:150px"></el-input-number></div>',
      '      </div>',
      '      <div style="font-weight:600;margin-bottom:6px;">溶液(名称与容量, 含容量可折算预计结束)</div>',
      '      <el-input v-model="prepSolution" placeholder="如 0.9%氯化钠注射液 250ml"></el-input>',
      '      <div style="font-weight:600;margin:10px 0 6px;">穿刺部位</div>',
      '      <el-input v-model="prepSite" placeholder="如 左手背"></el-input>',
      '      <div class="ns-soft" style="margin-top:8px;">确认后执行单转为执行中并完成穿刺, 直接进入"输液中"页签巡视观察。</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="prepVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="prepping" @click="doPrep">确认配液穿刺</el-button>',
      '    </template>',
      '  </el-dialog>',

      /* 巡视弹窗 */
      '  <el-dialog v-model="patrolVisible" title="输液巡视" width="480px">',
      '    <div v-if="patrolRow" class="ns-banner">',
      '      <span class="name">{{ patrolRow.patientName }}</span>',
      '      <div class="row">座位: {{ patrolRow.seatNo || \'-\' }} · {{ patrolRow.itemName || \'-\' }} · 已输 {{ elapsedText(patrolRow) }}</div>',
      '    </div>',
      '    <div style="display:flex;gap:10px;margin-bottom:10px;">',
      '      <div><div style="font-weight:600;margin-bottom:6px;">滴速(滴/分)</div>',
      '        <el-input-number v-model="patrolRate" :min="1" :max="300" style="width:150px"></el-input-number></div>',
      '      <div style="flex:1;"><div style="font-weight:600;margin-bottom:6px;">患者状态</div>',
      '        <el-select v-model="patrolStatus" style="width:100%">',
      '          <el-option v-for="st in patrolStatuses" :key="st" :label="st" :value="st"></el-option>',
      '        </el-select></div>',
      '    </div>',
      '    <div style="font-weight:600;margin-bottom:6px;">备注</div>',
      '    <el-input type="textarea" v-model="patrolNote" :rows="3" placeholder="如 穿刺处无渗液, 患者无不适"></el-input>',
      '    <template #footer>',
      '      <el-button @click="patrolVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="patrolling" @click="doPatrol">记录巡视</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 4. 过敏档案 =================
   * 有效过敏记录检索(患者姓名/患者ID/过敏原名称) + 手工登记;
   * 皮试阳性自动写入的记录来源显示"皮试阳性"。
   */
  HIS.views.NurseAllergy = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20, keyword: '',
        /* 新增弹窗 */
        addVisible: false, addSaving: false,
        addForm: { patientId: null, allergenType: 'drug', allergenName: '', severity: 'moderate', source: 'manual' },
        patientKeyword: '', patientOpts: [], patientLoading: false,
        allergenTypes: ALLERGEN_TYPES, severities: SEVERITIES, sources: ALLERGY_SOURCES,
        allergenTypeLabel: allergenTypeLabel, allergenTypeTag: allergenTypeTag,
        severityLabel: severityLabel, severityTag: severityTag,
        sourceLabel: sourceLabel, fmtTime: fmtTime
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/nurse/allergies?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ===== 新增 ===== */
      openAdd: function () {
        this.addForm = { patientId: null, allergenType: 'drug', allergenName: '', severity: 'moderate', source: 'manual' };
        this.patientKeyword = '';
        this.patientOpts = [];
        this.addVisible = true;
      },
      searchPatients: function () {
        var vm = this;
        vm.patientLoading = true;
        var q = '/api/his/patient/page?page=1&size=20';
        if (vm.patientKeyword) { q += '&keyword=' + encodeURIComponent(vm.patientKeyword); }
        HIS.get(q).then(function (d) {
          vm.patientOpts = (d && d.records) || [];
          if (!vm.patientOpts.length) { ElementPlus.ElMessage.info('未找到匹配患者'); }
        }).catch(HIS.notifyError).finally(function () { vm.patientLoading = false; });
      },
      doAdd: function () {
        var vm = this;
        if (!vm.addForm.patientId) { ElementPlus.ElMessage.warning('请先搜索并选择患者'); return; }
        if (!(vm.addForm.allergenName || '').trim()) { ElementPlus.ElMessage.warning('请填写过敏原名称'); return; }
        vm.addSaving = true;
        HIS.post('/api/nurse/allergy', {
          patientId: vm.addForm.patientId,
          allergenType: vm.addForm.allergenType,
          allergenName: vm.addForm.allergenName.trim(),
          severity: vm.addForm.severity,
          source: vm.addForm.source
        }).then(function () {
          HIS.notifySuccess('过敏记录已登记');
          vm.addVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.addSaving = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">过敏档案 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(有效过敏记录 · 皮试阳性自动写入 · 执行前核对警示数据源)</span></div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="患者姓名/患者ID/过敏原名称" clearable style="width:240px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="load()">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button type="danger" plain @click="openAdd">新增过敏记录</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="185">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span><span class="ns-soft"> {{ s.row.patientNo || \'\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="性别/年龄" width="100" align="center">',
      '      <template #default="s">{{ s.row.genderName || \'-\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="过敏原类型" width="100" align="center">',
      '      <template #default="s"><el-tag size="small" :type="allergenTypeTag(s.row.allergenType)">{{ allergenTypeLabel(s.row.allergenType) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="过敏原名称" min-width="160" show-overflow-tooltip>',
      '      <template #default="s"><span class="ns-strong">{{ s.row.allergenName || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="严重程度" width="90" align="center">',
      '      <template #default="s"><el-tag size="small" :type="severityTag(s.row.severity)" effect="dark">{{ severityLabel(s.row.severity) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="来源" width="100" align="center">',
      '      <template #default="s">{{ sourceLabel(s.row.source) }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="记录时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.recordTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="记录人" width="100" align="center">',
      '      <template #default="s">{{ s.row.recordByName || \'-\' }}</template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination v-if="total" style="margin-top:10px;justify-content:flex-end;" layout="total, sizes, prev, pager, next, jumper" :total="total" :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-empty v-if="!loading && !list.length" description="暂无过敏记录" :image-size="60"></el-empty>',

      /* 新增弹窗 */
      '  <el-dialog v-model="addVisible" title="新增过敏记录" width="500px" :close-on-click-modal="false">',
      '    <div style="font-weight:600;margin-bottom:6px;">患者</div>',
      '    <div style="display:flex;gap:8px;margin-bottom:10px;">',
      '      <el-input v-model="patientKeyword" placeholder="患者姓名/证件号/患者号" style="flex:1" @keyup.enter="searchPatients"></el-input>',
      '      <el-button :loading="patientLoading" @click="searchPatients">搜索患者</el-button>',
      '    </div>',
      '    <el-select v-model="addForm.patientId" filterable placeholder="选择患者" style="width:100%;margin-bottom:12px;">',
      '      <el-option v-for="p in patientOpts" :key="p.id" :label="p.name + \'(\' + (p.patientNo || p.id) + \')\'" :value="p.id"></el-option>',
      '    </el-select>',
      '    <div style="display:flex;gap:10px;margin-bottom:10px;">',
      '      <div style="flex:1;"><div style="font-weight:600;margin-bottom:6px;">过敏原类型</div>',
      '        <el-select v-model="addForm.allergenType" style="width:100%">',
      '          <el-option v-for="t in allergenTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '        </el-select></div>',
      '      <div style="flex:1;"><div style="font-weight:600;margin-bottom:6px;">严重程度</div>',
      '        <el-select v-model="addForm.severity" style="width:100%">',
      '          <el-option v-for="sv in severities" :key="sv.v" :label="sv.l" :value="sv.v"></el-option>',
      '        </el-select></div>',
      '    </div>',
      '    <div style="font-weight:600;margin-bottom:6px;">过敏原名称</div>',
      '    <el-input v-model="addForm.allergenName" placeholder="如 青霉素" style="margin-bottom:10px;"></el-input>',
      '    <div style="font-weight:600;margin-bottom:6px;">来源</div>',
      '    <el-select v-model="addForm.source" style="width:100%">',
      '      <el-option v-for="sc in sources" :key="sc.v" :label="sc.l" :value="sc.v"></el-option>',
      '    </el-select>',
      '    <template #footer>',
      '      <el-button @click="addVisible = false">取消</el-button>',
      '      <el-button type="danger" :loading="addSaving" @click="doAdd">登记</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 5. 执行记录查询 =================
   * 全状态执行台账: 日期范围 + 执行类型 + 护士筛选, 行展开完整时间线(创建->开始->完成/取消)。
   */
  HIS.views.NurseExecLog = {
    data: function () {
      return {
        dateRange: [today(), today()],
        execType: '',
        nurseId: null, nurseOpts: [],
        loading: false, list: [], total: 0, page: 1, size: 20,
        execTypes: EXEC_TYPES,
        execTypeLabel: execTypeLabel, execTypeTag: execTypeTag,
        execStatusLabel: execStatusLabel, execStatusTag: execStatusTag,
        fmtTime: fmtTime
      };
    },
    created: function () {
      var vm = this;
      loadNurseOpts(vm);
      vm.load();
    },
    methods: {
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/nurse/exec-log?page=' + vm.page + '&size=' + vm.size;
        if (vm.execType) { q += '&execType=' + encodeURIComponent(vm.execType); }
        if (vm.nurseId) { q += '&nurseId=' + vm.nurseId; }
        if (vm.dateRange && vm.dateRange[0]) { q += '&startDate=' + vm.dateRange[0]; }
        if (vm.dateRange && vm.dateRange[1]) { q += '&endDate=' + vm.dateRange[1]; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      reset: function () {
        this.dateRange = [today(), today()];
        this.execType = '';
        this.nurseId = null;
        this.search();
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 展开行时间线: 创建 -> 开始 -> 完成/取消(含操作人) */
      timelineOf: function (row) {
        var cancelled = row.execStatus === -1;
        return [
          { label: '创建执行单', time: row.createTime, t: 'info', by: row.doctorName ? ('开单医生: ' + row.doctorName) : '' },
          { label: '开始执行', time: row.execTime, t: 'primary', by: row.nurseName ? ('执行护士: ' + row.nurseName) : '未开始' },
          {
            label: cancelled ? '取消执行' : '完成执行',
            time: row.endTime,
            t: cancelled ? 'info' : 'success',
            by: cancelled ? (row.remark || '已取消') : (row.nurseName ? ('执行护士: ' + row.nurseName) : '')
          }
        ];
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">执行记录 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(全状态执行台账 · 点击行首展开完整时间线)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px" @change="search"></el-date-picker>',
      '    <el-select v-model="execType" clearable placeholder="全部类型" style="width:120px" @change="search">',
      '      <el-option v-for="t in execTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '    </el-select>',
      '    <el-select v-model="nurseId" clearable filterable placeholder="全部护士" style="width:150px" @change="search">',
      '      <el-option v-for="n in nurseOpts" :key="n.id" :label="n.staffName" :value="n.id"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="reset">重置</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="expand">',
      '      <template #default="s">',
      '        <div style="padding:6px 24px;">',
      '          <el-timeline>',
      '            <el-timeline-item v-for="(tl, i) in timelineOf(s.row)" :key="i" :timestamp="tl.time || \'未发生\'" placement="top" :type="tl.t">',
      '              <el-tag size="small" :type="tl.t">{{ tl.label }}</el-tag>',
      '              <span v-if="tl.by" class="ns-timeline-time">{{ tl.by }}</span>',
      '            </el-timeline-item>',
      '          </el-timeline>',
      '          <div class="ns-soft" style="margin-top:6px;">患者反应: {{ s.row.patientResponse || \'-\' }} · 备注: {{ s.row.remark || \'-\' }}</div>',
      '        </div>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="执行单号" width="145" align="center">',
      '      <template #default="s">{{ s.row.execNo || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="患者" width="150">',
      '      <template #default="s"><span class="ns-strong">{{ s.row.patientName || \'-\' }}</span><span class="ns-soft"> {{ s.row.genderName || \'\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="类型" width="80" align="center">',
      '      <template #default="s"><el-tag size="small" :type="execTypeTag(s.row.execType)">{{ execTypeLabel(s.row.execType) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="状态" width="80" align="center">',
      '      <template #default="s"><el-tag size="small" :type="execStatusTag(s.row.execStatus)">{{ execStatusLabel(s.row.execStatus) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="医嘱内容" min-width="180" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.itemName || \'-\' }}<span v-if="s.row.spec" class="ns-soft"> {{ s.row.spec }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="执行护士" width="100" align="center">',
      '      <template #default="s">{{ s.row.nurseName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="开始时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.execTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="结束时间" width="150" align="center">',
      '      <template #default="s">{{ s.row.endTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="时长" width="80" align="center">',
      '      <template #default="s">{{ s.row.durationMin != null ? s.row.durationMin + \'分\' : \'-\' }}</template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination v-if="total" style="margin-top:10px;justify-content:flex-end;" layout="total, sizes, prev, pager, next, jumper" :total="total" :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-empty v-if="!loading && !list.length" description="暂无执行记录" :image-size="60"></el-empty>',
      '</div>'
    ].join('\n')
  };
})();
