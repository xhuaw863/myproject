/* 治疗管理: 待执行工作台(签到/开始/完成/取消) + 治疗计划(疗程) + 治疗设备台账 + 治疗记录查询 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 执行单状态: 0待执行 1执行中 2已完成 3已取消 */
  var EXEC_STATUS = [
    { v: 0, l: '待执行', t: 'info' },
    { v: 1, l: '执行中', t: 'warning' },
    { v: 2, l: '已完成', t: 'success' },
    { v: 3, l: '已取消', t: 'danger' }
  ];
  /* 计划状态: 0执行中 1已完成 2已终止 */
  var PLAN_STATUS = [
    { v: 0, l: '执行中', t: 'primary' },
    { v: 1, l: '已完成', t: 'success' },
    { v: 2, l: '已终止', t: 'danger' }
  ];
  /* 治疗类别: 与后端 classifyCategory 归类一致 */
  var CATEGORIES = [
    { v: 'physiotherapy', l: '理疗', t: 'primary' },
    { v: 'rehab', l: '康复', t: 'success' },
    { v: 'tcm', l: '中医传统', t: 'warning' }
  ];
  /* 设备状态: 1正常 2维修 0停用(仅正常可被治疗单选用) */
  var EQUIP_STATUS = [
    { v: 1, l: '正常', t: 'success' },
    { v: 2, l: '维修', t: 'warning' },
    { v: 0, l: '停用', t: 'info' }
  ];
  var EQUIP_TYPES = ['理疗', '康复', '中医传统'];

  function findIn(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i]; } }
    return null;
  }
  function execLabel(v) { var s = findIn(EXEC_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function execTag(v) { var s = findIn(EXEC_STATUS, v); return s ? s.t : 'info'; }
  function planLabel(v) { var s = findIn(PLAN_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function planTag(v) { var s = findIn(PLAN_STATUS, v); return s ? s.t : 'info'; }
  function catLabel(v) { var s = findIn(CATEGORIES, v); return s ? s.l : (v == null || v === '' ? '-' : v); }
  function catTag(v) { var s = findIn(CATEGORIES, v); return s ? s.t : 'info'; }
  function equipLabel(v) { var s = findIn(EQUIP_STATUS, v); return s ? s.l : (v == null ? '-' : v); }
  function equipTag(v) { var s = findIn(EQUIP_STATUS, v); return s ? s.t : 'info'; }

  function ymd(d) {
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var day = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + day;
  }
  function today() { return ymd(new Date()); }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return ymd(d); }

  /* 后端时间串(yyyy-MM-dd HH:mm:ss 或 ISO 含T)转毫秒; 无效返回0 */
  function parseTs(v) {
    if (!v) { return 0; }
    var t = new Date(String(v).replace(' ', 'T'));
    return isNaN(t.getTime()) ? 0 : t.getTime();
  }
  /* 排队等候分钟数: 已签到按签到时间, 未签到按创建时间 */
  function waitMinutes(row) {
    var ts = parseTs(row.checkin_time || row.create_time);
    if (!ts) { return 0; }
    var m = Math.floor((Date.now() - ts) / 60000);
    return m < 0 ? 0 : m;
  }
  function waitText(row) {
    var m = waitMinutes(row);
    if (m < 1) { return '不足1分钟'; }
    if (m < 60) { return m + ' 分钟'; }
    return Math.floor(m / 60) + ' 小时' + (m % 60) + ' 分';
  }
  function planPercent(row) {
    var t = Number(row && row.total_sessions) || 0;
    var c = Number(row && row.completed_sessions) || 0;
    if (t <= 0) { return 0; }
    var p = Math.round(c * 100 / t);
    return p > 100 ? 100 : p;
  }

  /* ================= 1. 待执行治疗工作台 ================= */
  HIS.views.TreatmentPending = {
    data: function () {
      return {
        loading: false, list: [], selId: null, q: '',
        deptId: null, deptDefs: [],
        equipOpts: [], staffOpts: [],
        startVisible: false, startSaving: false,
        startForm: { execId: null, patientName: '', itemName: '', sessionIndex: 1, therapistId: null, equipCode: null, paramRows: [] },
        finishVisible: false, finishSaving: false,
        finishForm: { execId: null, patientName: '', durationMin: null, params: '', response: '' },
        paramMemo: {},
        refreshTimer: null
      };
    },
    computed: {
      sel: function () {
        var id = this.selId, list = this.list;
        for (var i = 0; i < list.length; i++) { if (list[i].id === id) { return list[i]; } }
        return null;
      },
      filteredQueue: function () {
        var q = (this.q || '').trim();
        if (!q) { return this.list; }
        return this.list.filter(function (r) {
          return (r.patient_name || '').indexOf(q) >= 0 || (r.item_name || '').indexOf(q) >= 0;
        });
      }
    },
    created: function () {
      var vm = this;
      vm.loadDepts();
      vm.loadStaffOpts();
      vm.load().then(function () { vm.startAutoRefresh(); });
    },
    /* Vue3 销毁钩子: 清理自动刷新定时器防泄漏 */
    beforeUnmount: function () { this.stopAutoRefresh(); },
    methods: {
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (list) { vm.deptDefs = list || []; }).catch(HIS.notifyError);
      },
      loadStaffOpts: function () {
        var vm = this;
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('技师') + '&withSubOrgs=false')
          .then(function (list) { vm.staffOpts = list || []; }).catch(HIS.notifyError);
      },
      /* 待执行列表刷新(silent=true 用于自动刷新, 不闪加载态); 返回 Promise 供链式处理 */
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/treatment/pending';
        if (vm.deptId) { q += '?deptId=' + vm.deptId; }
        return HIS.get(q).then(function (list) {
          vm.list = list || [];
          var still = false;
          for (var i = 0; i < vm.list.length; i++) { if (vm.list[i].id === vm.selId) { still = true; break; } }
          if (!still) { vm.selId = null; }
          if (vm.selId === null && vm.list.length) { vm.selId = vm.list[0].id; }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      refreshAll: function () { this.load(); },
      startAutoRefresh: function () {
        var vm = this;
        vm.refreshTimer = setInterval(function () { vm.load(true); }, 15 * 1000);
      },
      stopAutoRefresh: function () {
        if (this.refreshTimer) { clearInterval(this.refreshTimer); this.refreshTimer = null; }
      },
      queueTagLabel: function (p) {
        if (p.exec_status === 1) { return '治疗中'; }
        return p.checked_in ? '已签到' : '待签到';
      },
      queueTagType: function (p) {
        if (p.exec_status === 1) { return 'warning'; }
        return p.checked_in ? 'primary' : 'info';
      },
      waitText: waitText,
      waitOver30: function (p) { return waitMinutes(p) > 30; },
      planPercent: planPercent,
      execLabel: execLabel,
      execTag: execTag,
      catLabel: catLabel,
      catTag: catTag,
      /* 患者签到(排队) */
      doCheckin: function () {
        var vm = this, row = vm.sel;
        if (!row) { return; }
        HIS.post('/api/treatment/exec/' + row.id + '/checkin').then(function (d) {
          HIS.notifySuccess(d && d.already ? '该治疗单已签到过' : '签到成功, 已加入排队');
          return vm.load(true);
        }).catch(HIS.notifyError);
      },
      openStart: function () {
        var vm = this, row = vm.sel;
        if (!row) { return; }
        if (!vm.equipOpts.length) {
          HIS.get('/api/treatment/equipment').then(function (l) {
            vm.equipOpts = (l || []).filter(function (e) { return e.status === 1; });
          }).catch(HIS.notifyError);
        }
        var defTid = null, u = HIS.getUser();
        if (u && u.staffId) {
          for (var i = 0; i < vm.staffOpts.length; i++) {
            if (vm.staffOpts[i].id === u.staffId) { defTid = u.staffId; break; }
          }
        }
        vm.startForm = {
          execId: row.id, patientName: row.patient_name, itemName: row.item_name,
          sessionIndex: row.session_index, therapistId: defTid,
          equipCode: row.equipment_code || null,
          paramRows: [{ k: '部位', v: '' }, { k: '强度', v: '' }, { k: '时间', v: '' }]
        };
        vm.startVisible = true;
      },
      confirmStart: function () {
        var vm = this, f = vm.startForm;
        if (!f.therapistId) { ElementPlus.ElMessage.warning('请选择治疗师'); return; }
        var params = '';
        (f.paramRows || []).forEach(function (p) {
          var k = (p.k || '').trim(), v = (p.v || '').trim();
          if (k && v) { params += (params ? '; ' : '') + k + '=' + v; }
        });
        vm.paramMemo[f.execId] = params;
        vm.startSaving = true;
        HIS.post('/api/treatment/exec/' + f.execId + '/start', { therapistId: f.therapistId, equipCode: f.equipCode || null })
          .then(function () {
            HIS.notifySuccess('已开始治疗');
            vm.startVisible = false;
            return vm.load(true);
          }).catch(HIS.notifyError).finally(function () { vm.startSaving = false; });
      },
      openFinish: function () {
        var row = this.sel;
        if (!row) { return; }
        this.finishForm = {
          execId: row.id, patientName: row.patient_name, durationMin: null,
          params: this.paramMemo[row.id] || '', response: ''
        };
        this.finishVisible = true;
      },
      confirmFinish: function () {
        var vm = this, f = vm.finishForm;
        var pid = vm.sel ? vm.sel.plan_id : null;
        vm.finishSaving = true;
        HIS.post('/api/treatment/exec/' + f.execId + '/finish', {
          durationMin: f.durationMin, params: f.params || null, response: f.response || null
        }).then(function (d) {
          var tip = '治疗完成';
          if (d && d.planFinished) { tip += ', 本疗程已全部完成'; }
          else if (d && d.nextCreated) { tip += ', 已生成下一次执行单'; }
          HIS.notifySuccess(tip);
          vm.finishVisible = false;
          return vm.load(true).then(function () {
            /* 完成后续选同疗程的下一次执行单, 便于连续操作 */
            if (pid != null) {
              vm.selId = null;
              for (var i = 0; i < vm.list.length; i++) { if (vm.list[i].plan_id === pid) { vm.selId = vm.list[i].id; break; } }
            }
          });
        }).catch(HIS.notifyError).finally(function () { vm.finishSaving = false; });
      },
      doCancel: function () {
        var vm = this, row = vm.sel;
        if (!row) { return; }
        ElementPlus.ElMessageBox.prompt(
          '取消治疗单 ' + (row.exec_no || '') + ' (' + (row.patient_name || '') + ' · ' + (row.item_name || '') + ')',
          '取消治疗', {
            confirmButtonText: '确认取消', cancelButtonText: '再想想',
            inputPlaceholder: '请输入取消原因(必填)',
            inputValidator: function (v) { return (v && String(v).trim()) ? true : '取消原因不能为空'; }
          }).then(function (r) {
            return HIS.post('/api/treatment/exec/' + row.id + '/cancel', { reason: r.value });
          }).then(function (d) {
            HIS.notifySuccess('已取消' + (d && d.nextCreated ? ', 已补建下一次执行单' : ''));
            return vm.load(true);
          }).catch(function (e) {
            if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
          });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">待执行治疗工作台 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(仅显示已收费治疗单 · 已签到优先排队 · 每15秒自动刷新)</span></div>',
      '  <div class="toolbar">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">执行科室</span>',
      '    <el-select v-model="deptId" clearable placeholder="全部科室" style="width:170px;" @change="refreshAll">',
      '      <el-option v-for="d in deptDefs" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-input v-model="q" placeholder="按患者姓名/项目名称过滤左侧队列" clearable style="width:240px;"></el-input>',
      '    <el-button type="primary" @click="refreshAll">刷新</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">排队 {{ list.length }} 人 · 每15秒自动刷新</span>',
      '  </div>',
      '  <el-row :gutter="12">',
      '    <el-col :span="7">',
      '      <div v-loading="loading" style="border:1px solid var(--yb-border-light);border-radius:8px;padding:10px;min-height:480px;max-height:660px;overflow:auto;">',
      '        <div style="font-weight:600;color:var(--yb-ink-1);margin-bottom:8px;">签到排队 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(已签到按签到时间优先)</span></div>',
      '        <div v-for="p in filteredQueue" :key="p.id" @click="selId = p.id"',
      '          :style="(selId === p.id ? \'border:1.5px solid var(--yb-link);background:var(--yb-brand-subtle);\' : \'border:1px solid var(--yb-border-light);background:#fff;\') + \';cursor:pointer;border-radius:6px;padding:10px 12px;margin-bottom:8px;\'">',
      '          <div style="display:flex;align-items:center;justify-content:space-between;">',
      '            <span style="font-weight:600;color:var(--yb-ink-1);">{{ p.patient_name || \'-\' }}</span>',
      '            <el-tag size="small" :type="queueTagType(p)">{{ queueTagLabel(p) }}</el-tag>',
      '          </div>',
      '          <div style="font-size:12px;color:var(--yb-ink-2);margin-top:4px;">{{ p.gender || \'-\' }} · {{ p.age != null ? p.age + \'岁\' : \'-\' }} · 第{{ p.session_index }}次</div>',
      '          <div style="font-size:13px;color:var(--yb-ink-2);margin-top:4px;">{{ p.item_name || \'-\' }}</div>',
      '          <div style="font-size:12px;margin-top:4px;">',
      '            <span v-if="p.checked_in" :style="waitOver30(p) ? \'color:var(--yb-danger);font-weight:600;\' : \'color:var(--yb-ink-2);\'">已等候 {{ waitText(p) }}</span>',
      '            <span v-else style="color:var(--yb-ink-2);">未签到 · 开单 {{ p.create_time ? p.create_time.substring(5, 16) : \'-\' }}</span>',
      '          </div>',
      '        </div>',
      '        <el-empty v-if="!filteredQueue.length" description="暂无待执行治疗单" :image-size="70"></el-empty>',
      '      </div>',
      '    </el-col>',
      '    <el-col :span="17">',
      '      <div style="border:1px solid var(--yb-border-light);border-radius:8px;padding:14px;min-height:480px;">',
      '        <div v-if="!sel" style="height:400px;display:flex;align-items:center;justify-content:center;">',
      '          <el-empty description="请从左侧选择患者, 或等待患者签到" :image-size="90"></el-empty>',
      '        </div>',
      '        <div v-else>',
      '          <div style="background:linear-gradient(90deg,var(--yb-brand-subtle),var(--yb-surface-2));border:1px solid var(--yb-brand-subtle);border-radius:6px;padding:12px 16px;margin-bottom:12px;">',
      '            <div style="display:flex;align-items:center;gap:14px;flex-wrap:wrap;">',
      '              <span style="font-size:18px;font-weight:700;color:var(--yb-ink-1);">{{ sel.patient_name || \'-\' }}</span>',
      '              <el-tag size="small" type="info">{{ sel.gender || \'-\' }}</el-tag>',
      '              <span style="color:var(--yb-ink-2);">{{ sel.age != null ? sel.age + \' 岁\' : \'-\' }}</span>',
      '              <span style="color:var(--yb-ink-2);font-size:13px;">病历号 {{ sel.patient_no || \'-\' }}</span>',
      '              <el-tag size="small" :type="execTag(sel.exec_status)">{{ execLabel(sel.exec_status) }}</el-tag>',
      '            </div>',
      '            <div style="margin-top:6px;color:var(--yb-ink-2);font-size:13px;">诊断: {{ sel.diag_name || \'-\' }}　·　医嘱 {{ sel.order_no || \'-\' }}　·　开单医师 {{ sel.doctor_name || \'-\' }}　·　执行科室 {{ sel.exec_dept_name || sel.order_dept_name || \'-\' }}</div>',
      '          </div>',
      '          <el-descriptions :column="3" border size="small">',
      '            <el-descriptions-item label="治疗项目">{{ sel.item_name || \'-\' }}</el-descriptions-item>',
      '            <el-descriptions-item label="类别"><el-tag size="small" :type="catTag(sel.category)">{{ catLabel(sel.category) }}</el-tag></el-descriptions-item>',
      '            <el-descriptions-item label="计划号">{{ sel.plan_no || \'-\' }}</el-descriptions-item>',
      '            <el-descriptions-item label="频次">{{ sel.frequency || \'-\' }}</el-descriptions-item>',
      '            <el-descriptions-item label="本次序次">第 {{ sel.session_index }} 次 / 共 {{ sel.total_sessions }} 次</el-descriptions-item>',
      '            <el-descriptions-item label="设备要求">{{ sel.equipment_code ? (sel.equip_name || sel.equipment_code) : \'不限\' }}</el-descriptions-item>',
      '            <el-descriptions-item label="签到时间">{{ sel.checkin_time || \'未签到\' }}</el-descriptions-item>',
      '            <el-descriptions-item label="执行日期">{{ sel.exec_date || \'-\' }}</el-descriptions-item>',
      '            <el-descriptions-item label="治疗师">{{ sel.therapist_name || \'-\' }}</el-descriptions-item>',
      '          </el-descriptions>',
      '          <div style="margin:14px 0 4px;">',
      '            <div style="font-size:13px;color:var(--yb-ink-2);margin-bottom:4px;">疗程进度 {{ sel.completed_sessions }}/{{ sel.total_sessions }}</div>',
      '            <el-progress :percentage="planPercent(sel)" :stroke-width="14"></el-progress>',
      '          </div>',
      '          <div style="margin-top:16px;display:flex;gap:10px;flex-wrap:wrap;">',
      '            <el-button v-if="!sel.checked_in && sel.exec_status === 0" type="primary" @click="doCheckin">患者签到</el-button>',
      '            <el-button v-if="sel.exec_status === 0" type="success" @click="openStart">开始治疗</el-button>',
      '            <el-button v-if="sel.exec_status === 1" type="warning" @click="openFinish">完成治疗</el-button>',
      '            <el-button v-if="sel.exec_status === 0 || sel.exec_status === 1" type="danger" plain @click="doCancel">取消治疗单</el-button>',
      '            <el-button @click="refreshAll">刷新</el-button>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',
      /* ---- 开始治疗弹窗: 治疗师 + 设备 + 参数设定 ---- */
      '  <el-dialog v-model="startVisible" title="开始治疗" width="640px" top="8vh">',
      '    <div style="font-size:13px;color:var(--yb-ink-2);margin-bottom:10px;">患者 <b>{{ startForm.patientName }}</b> · 项目 <b>{{ startForm.itemName }}</b> · 第 {{ startForm.sessionIndex }} 次治疗</div>',
      '    <el-form label-width="86px">',
      '      <el-form-item label="治疗师">',
      '        <el-select v-model="startForm.therapistId" filterable placeholder="选择治疗师" style="width:280px;">',
      '          <el-option v-for="t in staffOpts" :key="t.id" :label="t.staffName" :value="t.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="治疗设备">',
      '        <el-select v-model="startForm.equipCode" filterable clearable placeholder="不指定设备可不选(仅可选正常设备)" style="width:360px;">',
      '          <el-option v-for="e in equipOpts" :key="e.id" :label="e.equip_name + \' (\' + e.equip_code + \')\'" :value="e.equip_code"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="参数设定">',
      '        <div style="width:100%;">',
      '          <div v-for="(p, i) in startForm.paramRows" :key="i" style="display:flex;gap:8px;margin-bottom:6px;">',
      '            <el-input v-model="p.k" placeholder="参数名(如 强度)" style="width:170px;"></el-input>',
      '            <el-input v-model="p.v" placeholder="参数值(如 2.0)" style="width:210px;"></el-input>',
      '            <el-button text type="danger" @click="startForm.paramRows.splice(i, 1)">删除</el-button>',
      '          </div>',
      '          <el-button size="small" @click="startForm.paramRows.push({ k: \'\', v: \'\' })">+ 添加参数</el-button>',
      '          <span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">开始后可在"完成治疗"时修改确认</span>',
      '        </div>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="startVisible = false">取 消</el-button>',
      '      <el-button type="primary" :loading="startSaving" @click="confirmStart">确认开始</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 完成治疗弹窗: 时长 + 参数 + 患者反应 ---- */
      '  <el-dialog v-model="finishVisible" title="完成治疗" width="560px" top="10vh">',
      '    <el-form label-width="86px">',
      '      <el-form-item label="患者"><span style="font-weight:600;">{{ finishForm.patientName }}</span></el-form-item>',
      '      <el-form-item label="治疗时长">',
      '        <el-input-number v-model="finishForm.durationMin" :min="1" :max="600" placeholder="分钟"></el-input-number>',
      '        <span style="margin-left:8px;color:var(--yb-ink-2);">分钟</span>',
      '      </el-form-item>',
      '      <el-form-item label="治疗参数"><el-input v-model="finishForm.params" type="textarea" :rows="2" placeholder="如 强度=2.0; 部位=腰部"></el-input></el-form-item>',
      '      <el-form-item label="患者反应"><el-input v-model="finishForm.response" type="textarea" :rows="3" placeholder="如 无明显不适, 治疗过程顺利"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="finishVisible = false">取 消</el-button>',
      '      <el-button type="primary" :loading="finishSaving" @click="confirmFinish">确认完成</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 2. 治疗计划(疗程管理) ================= */
  HIS.views.TreatmentPlan = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20, keyword: '', fStatus: null,
        statusOpts: PLAN_STATUS,
        adjustDlg: { visible: false, row: null, newTotal: 1, reason: '', saving: false },
        termDlg: { visible: false, row: null, reason: '', saving: false }
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/treatment/plans?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.fStatus !== null && vm.fStatus !== undefined && vm.fStatus !== '') { q += '&status=' + vm.fStatus; }
        return HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      reset: function () { this.keyword = ''; this.fStatus = null; this.search(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 行展开时懒加载该计划的执行单明细 */
      onExpand: function (row, expandedRows) {
        if (!expandedRows || !expandedRows.length || row._execLoaded) { return; }
        HIS.get('/api/treatment/plan/' + row.id).then(function (d) {
          row._execs = (d && d.execs) || [];
          row._execLoaded = true;
        }).catch(HIS.notifyError);
      },
      planPercent: planPercent,
      planLabel: planLabel,
      planTag: planTag,
      catLabel: catLabel,
      catTag: catTag,
      execLabel: execLabel,
      execTag: execTag,
      openAdjust: function (row) {
        this.adjustDlg = { visible: true, row: row, newTotal: row.total_sessions, reason: '', saving: false };
      },
      confirmAdjust: function () {
        var vm = this, d = vm.adjustDlg, row = d.row;
        if (!d.newTotal || d.newTotal < 1) { ElementPlus.ElMessage.warning('新总次数必须大于0'); return; }
        if (row && row.completed_sessions != null && d.newTotal < row.completed_sessions) {
          ElementPlus.ElMessage.warning('新总次数不能小于已完成次数(' + row.completed_sessions + ')'); return;
        }
        d.saving = true;
        HIS.post('/api/treatment/plan/' + row.id + '/adjust', { newTotal: d.newTotal, reason: d.reason })
          .then(function (r) {
            var tip = '总次数已调整为 ' + (r && r.totalSessions != null ? r.totalSessions : d.newTotal);
            if (r && r.planFinished) { tip += ', 疗程已满自动完成'; }
            else if (r && r.nextCreated) { tip += ', 已补建下一次执行单'; }
            HIS.notifySuccess(tip);
            d.visible = false;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { d.saving = false; });
      },
      openTerm: function (row) {
        this.termDlg = { visible: true, row: row, reason: '', saving: false };
      },
      confirmTerm: function () {
        var vm = this, d = vm.termDlg, row = d.row;
        if (!d.reason || !d.reason.trim()) { ElementPlus.ElMessage.warning('请输入终止原因'); return; }
        d.saving = true;
        HIS.post('/api/treatment/plan/' + row.id + '/terminate', { reason: d.reason.trim() })
          .then(function (r) {
            HIS.notifySuccess('计划已终止' + (r && r.cancelledExecs ? ', 同步取消在途执行单 ' + r.cancelledExecs + ' 张' : ''));
            d.visible = false;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { d.saving = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">治疗计划 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(疗程管理: 进度跟踪 · 调整总次数 · 终止计划)</span></div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="患者姓名/病历号" clearable style="width:220px;" @keyup.enter="search"></el-input>',
      '    <el-select v-model="fStatus" clearable placeholder="全部状态" style="width:140px;" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="reset">重置</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" row-key="id" v-loading="loading" border stripe size="small" height="100%" @expand-change="onExpand">',
      '    <el-table-column type="expand">',
      '      <template #default="s">',
      '        <div style="padding:4px 16px 10px;">',
      '          <el-table :data="s.row._execs || []" size="small" border v-loading="!s.row._execLoaded">',
      '            <el-table-column prop="session_index" label="序次" width="60" align="center"><template #default="e">第{{ e.row.session_index }}次</template></el-table-column>',
      '            <el-table-column prop="exec_no" label="执行单号" width="150"></el-table-column>',
      '            <el-table-column prop="exec_date" label="执行日期" width="105"></el-table-column>',
      '            <el-table-column prop="therapist_name" label="治疗师" width="90"><template #default="e">{{ e.row.therapist_name || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="设备" width="130"><template #default="e">{{ e.row.equip_name || e.row.equipment_code || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="时长" width="70" align="right"><template #default="e">{{ e.row.duration_min != null ? e.row.duration_min + \'分\' : \'-\' }}</template></el-table-column>',
      '            <el-table-column prop="patient_response" label="患者反应" min-width="140" show-overflow-tooltip><template #default="e">{{ e.row.patient_response || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="状态" width="80" align="center"><template #default="e"><el-tag size="small" :type="execTag(e.row.exec_status)">{{ execLabel(e.row.exec_status) }}</el-tag></template></el-table-column>',
      '            <el-table-column label="取消原因" width="140" show-overflow-tooltip><template #default="e">{{ e.row.cancel_reason || \'-\' }}</template></el-table-column>',
      '          </el-table>',
      '          <el-empty v-if="s.row._execLoaded && !(s.row._execs || []).length" description="暂无执行记录" :image-size="40"></el-empty>',
      '        </div>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column label="患者" width="130">',
      '      <template #default="s">{{ s.row.patient_name || \'-\' }}<span v-if="s.row.gender || s.row.age" style="color:var(--yb-ink-2);font-size:12px;"> ({{ s.row.gender || \'-\' }}{{ s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\' }})</span></template>',
      '    </el-table-column>',
      '    <el-table-column prop="item_name" label="治疗项目" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="类别" width="90" align="center"><template #default="s"><el-tag size="small" :type="catTag(s.row.category)">{{ catLabel(s.row.category) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="进度(完成/总次)" width="180">',
      '      <template #default="s">',
      '        <div style="display:flex;align-items:center;gap:6px;">',
      '          <el-progress style="flex:1;" :percentage="planPercent(s.row)" :stroke-width="10"></el-progress>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);white-space:nowrap;">{{ s.row.completed_sessions }}/{{ s.row.total_sessions }}</span>',
      '        </div>',
      '      </template>',
      '    </el-table-column>',
      '    <el-table-column prop="frequency" label="频次" width="80" align="center"><template #default="s">{{ s.row.frequency || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="start_date" label="开始日期" width="105"></el-table-column>',
      '    <el-table-column prop="expire_date" label="有效期至" width="105"><template #default="s">{{ s.row.expire_date || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="doctor_name" label="开单医师" width="90"><template #default="s">{{ s.row.doctor_name || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="planTag(s.row.status)">{{ planLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="140" fixed="right">',
      '      <template #default="s">',
      '        <el-button size="small" :disabled="s.row.status !== 0" @click="openAdjust(s.row)">调整</el-button>',
      '        <el-button size="small" type="danger" plain :disabled="s.row.status !== 0" @click="openTerm(s.row)">终止</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <div class="pager">',
      '    <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      /* ---- 调整总次数弹窗 ---- */
      '  <el-dialog v-model="adjustDlg.visible" title="调整疗程总次数" width="480px">',
      '    <div v-if="adjustDlg.row" style="font-size:13px;color:var(--yb-ink-2);margin-bottom:12px;">',
      '      患者 <b>{{ adjustDlg.row.patient_name }}</b> · 项目 <b>{{ adjustDlg.row.item_name }}</b> · 已完成 {{ adjustDlg.row.completed_sessions }}/{{ adjustDlg.row.total_sessions }} 次',
      '    </div>',
      '    <el-form label-width="86px">',
      '      <el-form-item label="新总次数"><el-input-number v-model="adjustDlg.newTotal" :min="1" :max="99"></el-input-number></el-form-item>',
      '      <el-form-item label="调整原因"><el-input v-model="adjustDlg.reason" type="textarea" :rows="3" placeholder="调整原因(可选, 计入计划备注留痕)"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="adjustDlg.visible = false">取 消</el-button>',
      '      <el-button type="primary" :loading="adjustDlg.saving" @click="confirmAdjust">确认调整</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 终止计划弹窗 ---- */
      '  <el-dialog v-model="termDlg.visible" title="终止治疗计划" width="480px">',
      '    <el-alert type="warning" show-icon :closable="false" title="终止后不可恢复, 该计划在途执行单将同步取消" style="margin-bottom:12px;"></el-alert>',
      '    <div v-if="termDlg.row" style="font-size:13px;color:var(--yb-ink-2);margin-bottom:12px;">',
      '      患者 <b>{{ termDlg.row.patient_name }}</b> · 项目 <b>{{ termDlg.row.item_name }}</b> · 已完成 {{ termDlg.row.completed_sessions }}/{{ termDlg.row.total_sessions }} 次',
      '    </div>',
      '    <el-input v-model="termDlg.reason" type="textarea" :rows="3" placeholder="请输入终止原因(必填)"></el-input>',
      '    <template #footer>',
      '      <el-button @click="termDlg.visible = false">取 消</el-button>',
      '      <el-button type="danger" :loading="termDlg.saving" @click="confirmTerm">确认终止</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 3. 治疗设备台账 ================= */
  HIS.views.TreatmentEquipment = {
    components: { 'dept-tree-picker': HIS.components.DeptTreePicker },
    data: function () {
      return {
        loading: false, list: [], deptDefs: [], deptId: null, keyword: '',
        page: 1, size: 20,
        equipTypes: EQUIP_TYPES, statusOpts: EQUIP_STATUS,
        dlg: { visible: false, isEdit: false, saving: false, form: { id: null, equipCode: '', equipName: '', equipType: null, deptId: null, status: 1 } }
      };
    },
    computed: {
      /* 台账本地分页(数据量小, 一次拉全量, 前端切片) */
      pagedList: function () {
        var start = (this.page - 1) * this.size;
        return this.list.slice(start, start + this.size);
      }
    },
    created: function () {
      this.loadDepts();
      this.load();
    },
    methods: {
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (list) { vm.deptDefs = list || []; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/treatment/equipment';
        var parts = [];
        if (vm.deptId) { parts.push('deptId=' + vm.deptId); }
        if (vm.keyword) { parts.push('keyword=' + encodeURIComponent(vm.keyword)); }
        if (parts.length) { q += '?' + parts.join('&'); }
        return HIS.get(q).then(function (list) {
          vm.list = list || [];
          var maxPage = Math.max(1, Math.ceil(vm.list.length / vm.size));
          if (vm.page > maxPage) { vm.page = maxPage; }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; },
      onSize: function (s) { this.size = s; this.page = 1; },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      equipLabel: equipLabel,
      equipTag: equipTag,
      openAdd: function () {
        this.dlg = { visible: true, isEdit: false, saving: false, form: { id: null, equipCode: '', equipName: '', equipType: null, deptId: this.deptId || null, status: 1 } };
      },
      openEdit: function (row) {
        this.dlg = {
          visible: true, isEdit: true, saving: false,
          form: {
            id: row.id, equipCode: row.equip_code, equipName: row.equip_name,
            equipType: row.equip_type || null, deptId: row.dept_id != null ? row.dept_id : null,
            status: row.status != null ? row.status : 1
          }
        };
      },
      save: function () {
        var vm = this, d = vm.dlg, f = d.form;
        if (!f.equipCode || !f.equipCode.trim()) { ElementPlus.ElMessage.warning('请输入设备编码'); return; }
        if (!f.equipName || !f.equipName.trim()) { ElementPlus.ElMessage.warning('请输入设备名称'); return; }
        var body = {
          equipCode: f.equipCode.trim(), equipName: f.equipName.trim(),
          equipType: f.equipType || null, deptId: f.deptId || null, status: f.status
        };
        d.saving = true;
        var req = d.isEdit ? HIS.put('/api/treatment/equipment/' + f.id, body) : HIS.post('/api/treatment/equipment', body);
        req.then(function () {
          HIS.notifySuccess(d.isEdit ? '设备已更新' : '设备已新增');
          d.visible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { d.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认删除设备 "' + (row.equip_name || '') + '" (' + (row.equip_code || '') + ')？删除后不可恢复。',
          '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.del('/api/treatment/equipment/' + row.id);
        }).then(function () {
          HIS.notifySuccess('设备已删除');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">治疗设备台账 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(机构设备维护 · 仅"正常"设备可被治疗单选用)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="deptId" clearable placeholder="全部科室" style="width:170px;" @change="search">',
      '      <el-option v-for="d in deptDefs" :key="d.id" :label="d.deptName" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="设备编码/名称" clearable style="width:220px;" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="keyword = \'\'; deptId = null; search()">重置</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button type="primary" @click="openAdd">新增设备</el-button>',
      '  </div>',
      '  <el-table :data="pagedList" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="equip_code" label="设备编号" width="130"></el-table-column>',
      '    <el-table-column prop="equip_name" label="设备名称" min-width="170" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="equip_type" label="类别" width="100" align="center"><template #default="s">{{ s.row.equip_type || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="dept_name" label="所属科室" width="150"><template #default="s">{{ s.row.dept_name || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="equipTag(s.row.status)">{{ equipLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="create_time" label="创建时间" width="160"></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right">',
      '      <template #default="s">',
      '        <el-button size="small" @click="openEdit(s.row)">编辑</el-button>',
      '        <el-button size="small" type="danger" plain @click="del(s.row)">删除</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <div class="pager">',
      '    <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="list.length" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      /* ---- 新增/编辑弹窗 ---- */
      '  <el-dialog v-model="dlg.visible" :title="dlg.isEdit ? \'编辑设备\' : \'新增设备\'" width="520px">',
      '    <el-form label-width="86px">',
      '      <el-form-item label="设备编号"><el-input v-model="dlg.form.equipCode" placeholder="机构内唯一, 如 WL-001" style="width:260px;"></el-input></el-form-item>',
      '      <el-form-item label="设备名称"><el-input v-model="dlg.form.equipName" placeholder="如 中频治疗仪" style="width:300px;"></el-input></el-form-item>',
      '      <el-form-item label="设备类别">',
      '        <el-select v-model="dlg.form.equipType" clearable placeholder="选择类别" style="width:200px;">',
      '          <el-option v-for="t in equipTypes" :key="t" :label="t" :value="t"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="所属科室">',
      '        <dept-tree-picker v-model="dlg.form.deptId" :options="deptDefs" width="300px" placeholder="选择科室" />',
      '      </el-form-item>',
      '      <el-form-item label="状态">',
      '        <el-radio-group v-model="dlg.form.status">',
      '          <el-radio :label="1">正常</el-radio>',
      '          <el-radio :label="2">维修</el-radio>',
      '          <el-radio :label="0">停用</el-radio>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dlg.visible = false">取 消</el-button>',
      '      <el-button type="primary" :loading="dlg.saving" @click="save">保 存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 4. 治疗记录查询 ================= */
  HIS.views.TreatmentLog = {
    data: function () {
      return {
        loading: false, list: [], total: 0, page: 1, size: 20,
        dateRange: [daysAgo(6), today()], therapistId: null, category: null,
        cats: CATEGORIES, staffOpts: [],
        detail: { visible: false, row: null }
      };
    },
    created: function () {
      this.loadStaff();
      this.load();
    },
    methods: {
      loadStaff: function () {
        var vm = this;
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('技师') + '&withSubOrgs=false')
          .then(function (list) { vm.staffOpts = list || []; }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/treatment/exec-log?page=' + vm.page + '&size=' + vm.size;
        if (vm.therapistId) { q += '&therapistId=' + vm.therapistId; }
        if (vm.category) { q += '&category=' + vm.category; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&startDate=' + vm.dateRange[0] + '&endDate=' + vm.dateRange[1]; }
        return HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      reset: function () {
        this.dateRange = [daysAgo(6), today()];
        this.therapistId = null; this.category = null;
        this.search();
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      execLabel: execLabel,
      execTag: execTag,
      catLabel: catLabel,
      catTag: catTag,
      openDetail: function (row) { this.detail = { visible: true, row: row }; }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">治疗记录 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(执行明细查询 · 含执行中/已完成/已取消)</span></div>',
      '  <div class="toolbar">',
      '    <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width:260px;"></el-date-picker>',
      '    <el-select v-model="therapistId" clearable filterable placeholder="全部治疗师" style="width:160px;">',
      '      <el-option v-for="t in staffOpts" :key="t.id" :label="t.staffName" :value="t.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="category" clearable placeholder="全部类别" style="width:140px;">',
      '      <el-option v-for="c in cats" :key="c.v" :label="c.l" :value="c.v"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button @click="reset">重置</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="exec_no" label="执行单号" width="150"></el-table-column>',
      '    <el-table-column prop="patient_name" label="患者" width="90"></el-table-column>',
      '    <el-table-column prop="item_name" label="治疗项目" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="类别" width="90" align="center"><template #default="s"><el-tag size="small" :type="catTag(s.row.category)">{{ catLabel(s.row.category) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="序次" width="70" align="center"><template #default="s">第{{ s.row.session_index }}次</template></el-table-column>',
      '    <el-table-column prop="therapist_name" label="治疗师" width="90"><template #default="s">{{ s.row.therapist_name || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="设备" width="130"><template #default="s">{{ s.row.equip_name || s.row.equipment_code || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="exec_date" label="执行日期" width="105"><template #default="s">{{ s.row.exec_date || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="时长" width="70" align="right"><template #default="s">{{ s.row.duration_min != null ? s.row.duration_min + \'分\' : \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="execTag(s.row.exec_status)">{{ execLabel(s.row.exec_status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="patient_response" label="患者反应" width="140" show-overflow-tooltip><template #default="s">{{ s.row.patient_response || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="操作" width="80" fixed="right"><template #default="s"><el-button size="small" @click="openDetail(s.row)">详情</el-button></template></el-table-column>',
      '  </el-table>',
      '  <div class="pager">',
      '    <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      /* ---- 记录详情弹窗 ---- */
      '  <el-dialog v-model="detail.visible" title="治疗执行详情" width="720px" top="6vh">',
      '    <el-descriptions v-if="detail.row" :column="2" border size="small">',
      '      <el-descriptions-item label="执行单号">{{ detail.row.exec_no || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="计划号">{{ detail.row.plan_no || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="医嘱单号">{{ detail.row.order_no || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="患者">{{ (detail.row.patient_name || \'-\') + (detail.row.gender ? \'  \' + detail.row.gender : \'\') + (detail.row.age != null ? \'  \' + detail.row.age + \'岁\' : \'\') }}</el-descriptions-item>',
      '      <el-descriptions-item label="病历号">{{ detail.row.patient_no || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="诊断">{{ detail.row.diag_name || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="治疗项目">{{ detail.row.item_name || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="类别"><el-tag size="small" :type="catTag(detail.row.category)">{{ catLabel(detail.row.category) }}</el-tag></el-descriptions-item>',
      '      <el-descriptions-item label="序次">第 {{ detail.row.session_index }} 次 / 共 {{ detail.row.total_sessions }} 次</el-descriptions-item>',
      '      <el-descriptions-item label="状态"><el-tag size="small" :type="execTag(detail.row.exec_status)">{{ execLabel(detail.row.exec_status) }}</el-tag></el-descriptions-item>',
      '      <el-descriptions-item label="开单医师">{{ detail.row.doctor_name || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="治疗师">{{ detail.row.therapist_name || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="设备">{{ detail.row.equip_name || detail.row.equipment_code || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="执行日期">{{ detail.row.exec_date || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="签到时间">{{ detail.row.checkin_time || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="治疗时长">{{ detail.row.duration_min != null ? detail.row.duration_min + \' 分钟\' : \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="治疗参数">{{ detail.row.parameters || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="患者反应">{{ detail.row.patient_response || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="取消原因">{{ detail.row.cancel_reason || \'-\' }}</el-descriptions-item>',
      '      <el-descriptions-item label="创建时间">{{ detail.row.create_time || \'-\' }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <template #footer>',
      '      <el-button @click="detail.visible = false">关 闭</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
