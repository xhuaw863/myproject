/* 危急值管理(住院, T37): 处理闭环(待通知→已通知→已确认→已处置→已关闭) + 规则管理 + 模拟检验上报。
 * 注册键: HIS.views['critical-value'](app.js 菜单 comp 与通知中心/看板跳转 HIS.go('critical-value') 据此定位)。
 * 后端接口: /api/his/inp/critical-value(check / records / notify / confirm / handle / close / rules / unhandled-count)。
 * 签名口径: 医生确认与处置前调用 HIS.SignaturePad.open({actionType, refType:'critical_value', refId})。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 私有样式一次性注入(无构建架构, 不触碰公共 css 与其他会话) */
  (function ensureCvStyles() {
    if (document.getElementById('cv-style')) { return; }
    var st = document.createElement('style');
    st.id = 'cv-style';
    st.textContent = [
      '.cv-value { color:var(--yb-danger); font-weight:700; font-variant-numeric:tabular-nums; }',
      '@keyframes cvBlink { 50% { opacity:.2; } }',
      '.cv-blink { animation: cvBlink 1.1s ease-in-out infinite; }',
      '.el-table__body tr.cv-critical-row > td.el-table__cell { background-color:var(--yb-danger-bg); }',
      '.cv-sim { border:1px dashed var(--yb-danger-border); background:var(--yb-danger-bg); border-radius:var(--yb-r-md,8px); padding:10px 12px; }',
      '.cv-sim .cv-sim-title { font-size:13px; font-weight:600; color:var(--yb-danger-strong); }',
      '.cv-sim-ok { border:1px solid var(--yb-danger-border); background:var(--yb-surface); border-radius:var(--yb-r-sm,6px); padding:8px 10px; margin-top:10px; font-size:13px; line-height:1.7; }',
      '.cv-sim-ok .tagline { font-weight:700; color:var(--yb-danger-strong); margin-right:8px; }',
      '.cv-sim-miss { color:var(--yb-ink-3); }',
      '.cv-flow-line { font-size:12px; color:var(--yb-ink-3); line-height:1.7; }',
      '.cv-flow-line b { color:var(--yb-ink-1); font-weight:600; }',
      '.cv-handle-measures { color:var(--yb-success-strong); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:210px; display:inline-block; vertical-align:bottom; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 状态/级别/性别口径(与后端 his_critical_value_record.status / rule.alert_level / rule.gender 对齐) ===== */
  var CV_STATUS = { 1: '待通知', 2: '已通知待确认', 3: '已确认待处置', 4: '已处置', 5: '已关闭' };
  var CV_STATUS_TAG = { 1: 'danger', 2: 'warning', 3: 'primary', 4: 'success', 5: 'info' };
  var CV_LEVEL = { 1: '危急', 2: '异常' };
  var CV_LEVEL_TAG = { 1: 'danger', 2: 'warning' };
  var CV_GENDER = { 0: '通用', 1: '男', 2: '女' };
  var CV_STATUS_TABS = [
    { v: 'all', l: '全部' },
    { v: 1, l: '待通知' },
    { v: 2, l: '已通知待确认' },
    { v: 3, l: '已确认待处置' },
    { v: 4, l: '已处置' },
    { v: 5, l: '已关闭' }
  ];

  /* 签名门: 打开签名板; 组件缺失时降级放行(仅提示), 取消则 reject('cancelled') 由调用方忽略 */
  function signGate(actionType, refId) {
    var sp = HIS.SignaturePad;
    if (!sp || typeof sp.open !== 'function') {
      ElementPlus.ElMessage.warning('签名组件未加载, 本次操作将不附电子签名');
      return Promise.resolve(null);
    }
    return sp.open({ actionType: actionType, refType: 'critical_value', refId: refId });
  }

  function isCancel(e) { return e === 'cancel' || e === 'close' || e === 'cancelled'; }

  /* ========================================================================
   * 危急值管理主组件
   * ====================================================================== */
  HIS.views['critical-value'] = {
    name: 'CriticalValueManage',
    data: function () {
      return {
        mainTab: 'handle',
        /* 处理 Tab */
        statusTab: 'all', statusTabs: CV_STATUS_TABS,
        loading: false, rows: [], total: 0, page: 1, size: 20, timer: null,
        cvStatus: CV_STATUS, cvStatusTag: CV_STATUS_TAG, cvLevel: CV_LEVEL, cvLevelTag: CV_LEVEL_TAG,
        /* 处置弹窗 */
        handleVisible: false, handleRow: null, handleMeasures: '', handling: false,
        /* 模拟上报(开发测试) */
        simOpen: [], simLoading: false, simResult: null,
        sim: { visitId: null, itemCode: '', itemName: '', resultValue: '', unit: '' },
        visits: [], visitsLoading: false,
        /* 规则 Tab */
        rulesLoading: false, rules: [],
        ruleVisible: false, ruleSaving: false,
        ruleForm: null
      };
    },
    computed: {
      /* 当前筛选说明(空态与提示用) */
      statusFilterLabel: function () {
        for (var i = 0; i < this.statusTabs.length; i++) {
          if (this.statusTabs[i].v === this.statusTab) { return this.statusTabs[i].l; }
        }
        return '全部';
      }
    },
    created: function () {
      this.ruleForm = this.blankRule();
      this.load();
      this.loadRules();
    },
    mounted: function () {
      var vm = this;
      if (vm.timer) { clearInterval(vm.timer); }
      vm.timer = setInterval(function () {
        if (vm.mainTab === 'handle') { vm.load(true); }
      }, 30000);
    },
    beforeUnmount: function () {
      if (this.timer) { clearInterval(this.timer); this.timer = null; }
    },
    methods: {
      /* ---------- 处理记录 ---------- */
      load: function (silent) {
        var vm = this;
        if (silent !== true) { vm.loading = true; }
        var q = '/api/his/inp/critical-value/records?page=' + vm.page + '&size=' + vm.size;
        if (vm.statusTab !== 'all') { q += '&status=' + vm.statusTab; }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.rows) || [];
          vm.total = (d && d.total) || 0;
        }).catch(function (e) { if (silent !== true) { HIS.notifyError(e); } })
          .finally(function () { if (silent !== true) { vm.loading = false; } });
      },
      onStatusTab: function (name) {
        if (name != null) { this.statusTab = name; }
        this.page = 1;
        this.load();
      },
      onMainTab: function (name) {
        if (name === 'rules') { this.loadRules(); }
        if (name === 'handle') { this.load(true); }
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      rowClass: function (o) {
        var r = o.row;
        return (r.alertLevel === 1 && r.status < 4) ? 'cv-critical-row' : '';
      },
      valueClass: function (row) {
        return { 'cv-blink': row.status < 4 && row.alertLevel === 1 };
      },

      /* 标记已通知(1→2) */
      doNotify: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认已电话/口头通知主管医生该危急值? 将记录通知时间。', '标记已通知', {
          type: 'warning', confirmButtonText: '确认已通知', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/inp/critical-value/' + row.id + '/notify');
        }).then(function () {
          HIS.notifySuccess('已标记通知');
          vm.load();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },

      /* 医生确认(2→3): 电子签名 → 回写确认医生与时间 */
      doConfirm: function (row) {
        var vm = this;
        signGate('critical_confirm', row.id).then(function () {
          return HIS.post('/api/his/inp/critical-value/' + row.id + '/confirm');
        }).then(function () {
          HIS.notifySuccess('医生确认完成');
          vm.load();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },

      /* 处置(3→4): 输入处置措施 → 电子签名 → 提交 */
      openHandle: function (row) {
        this.handleRow = row;
        this.handleMeasures = '';
        this.handleVisible = true;
      },
      submitHandle: function () {
        var vm = this;
        if (!vm.handleMeasures || !vm.handleMeasures.trim()) {
          ElementPlus.ElMessage.warning('请输入处置措施');
          return;
        }
        vm.handling = true;
        signGate('critical_handle', vm.handleRow.id).then(function () {
          return HIS.post('/api/his/inp/critical-value/' + vm.handleRow.id + '/handle',
            { measures: vm.handleMeasures.trim() });
        }).then(function () {
          HIS.notifySuccess('处置措施已记录');
          vm.handleVisible = false;
          vm.load();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } })
          .finally(function () { vm.handling = false; });
      },

      /* 关闭(4→5) */
      doClose: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认关闭该危急值闭环? 关闭后不可再流转。', '关闭危急值', {
          type: 'warning', confirmButtonText: '确认关闭', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/inp/critical-value/' + row.id + '/close');
        }).then(function () {
          HIS.notifySuccess('已关闭');
          vm.load();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },

      /* ---------- 模拟上报(开发测试) ---------- */
      onSimToggle: function (names) {
        var opened = (names || []).indexOf('sim') >= 0;
        if (opened && !this.visits.length) { this.loadVisits(); }
      },
      loadVisits: function () {
        var vm = this;
        vm.visitsLoading = true;
        HIS.get('/api/his/inp/patients?visitStatus=2&page=1&size=300').then(function (d) {
          vm.visits = (d && d.records) || [];
        }).catch(HIS.notifyError).finally(function () { vm.visitsLoading = false; });
      },
      simVisitLabel: function (v) {
        return (v.inp_no || v.id) + ' · ' + (v.patient_name || '-') + (v.bed_no ? ' · ' + v.bed_no + '床' : '');
      },
      onSimItem: function (code) {
        /* 选中规则项时自动回填名称与单位; allow-create 手输编码时仅回填编码 */
        for (var i = 0; i < this.rules.length; i++) {
          if (this.rules[i].itemCode === code) {
            this.sim.itemName = this.rules[i].itemName || '';
            this.sim.unit = this.rules[i].unit || '';
            return;
          }
        }
        this.sim.itemName = '';
      },
      simSubmit: function () {
        var vm = this;
        if (!vm.sim.visitId) { ElementPlus.ElMessage.warning('请选择就诊患者'); return; }
        if (!vm.sim.itemCode) { ElementPlus.ElMessage.warning('请选择或输入检验项目编码'); return; }
        if (vm.sim.resultValue === '' || vm.sim.resultValue == null) {
          ElementPlus.ElMessage.warning('请输入检验结果值'); return;
        }
        vm.simLoading = true;
        vm.simResult = null;
        HIS.post('/api/his/inp/critical-value/check', {
          visitId: vm.sim.visitId,
          itemCode: vm.sim.itemCode,
          itemName: vm.sim.itemName,
          resultValue: String(vm.sim.resultValue),
          unit: vm.sim.unit
        }).then(function (d) {
          vm.simResult = d || { alert: false, message: '无返回' };
          if (d && d.alert) { vm.load(true); }
        }).catch(HIS.notifyError).finally(function () { vm.simLoading = false; });
      },

      /* ---------- 规则管理 ---------- */
      blankRule: function () {
        return {
          id: null, itemCode: '', itemName: '', unit: '',
          criticalLow: null, criticalHigh: null, gender: 0,
          ageMin: null, ageMax: null, alertLevel: 1, enabled: 1
        };
      },
      loadRules: function () {
        var vm = this;
        vm.rulesLoading = true;
        HIS.get('/api/his/inp/critical-value/rules').then(function (list) {
          vm.rules = list || [];
        }).catch(HIS.notifyError).finally(function () { vm.rulesLoading = false; });
      },
      openCreate: function () {
        this.ruleForm = this.blankRule();
        this.ruleVisible = true;
      },
      openEdit: function (row) {
        this.ruleForm = {
          id: row.id, itemCode: row.itemCode, itemName: row.itemName, unit: row.unit,
          criticalLow: row.criticalLow, criticalHigh: row.criticalHigh,
          gender: row.gender == null ? 0 : row.gender,
          ageMin: row.ageMin, ageMax: row.ageMax,
          alertLevel: row.alertLevel == null ? 1 : row.alertLevel,
          enabled: row.enabled == null ? 1 : row.enabled
        };
        this.ruleVisible = true;
      },
      saveRule: function () {
        var vm = this;
        var f = vm.ruleForm;
        if (!f.itemCode || !f.itemCode.trim()) { ElementPlus.ElMessage.warning('请输入项目编码'); return; }
        if (!f.itemName || !f.itemName.trim()) { ElementPlus.ElMessage.warning('请输入项目名称'); return; }
        if (f.criticalLow == null && f.criticalHigh == null) {
          ElementPlus.ElMessage.warning('危急值下限与上限至少填写一项'); return;
        }
        if (f.criticalLow != null && f.criticalHigh != null && Number(f.criticalLow) >= Number(f.criticalHigh)) {
          ElementPlus.ElMessage.warning('危急值下限必须小于上限'); return;
        }
        if (f.ageMin != null && f.ageMax != null && Number(f.ageMin) > Number(f.ageMax)) {
          ElementPlus.ElMessage.warning('年龄下限不能大于年龄上限'); return;
        }
        var body = {
          id: f.id, itemCode: f.itemCode.trim(), itemName: f.itemName.trim(),
          unit: f.unit ? f.unit.trim() : null,
          criticalLow: f.criticalLow, criticalHigh: f.criticalHigh,
          gender: f.gender, ageMin: f.ageMin, ageMax: f.ageMax,
          alertLevel: f.alertLevel, enabled: f.enabled
        };
        vm.ruleSaving = true;
        HIS.post('/api/his/inp/critical-value/rules', body).then(function () {
          HIS.notifySuccess(f.id ? '规则已更新' : '规则已新增');
          vm.ruleVisible = false;
          vm.loadRules();
        }).catch(HIS.notifyError).finally(function () { vm.ruleSaving = false; });
      },
      /* 规则展示辅助: 性别文本 / 年龄范围文本 */
      cvGenderText: function (g) {
        return CV_GENDER[g == null ? 0 : g] || '通用';
      },
      ageRangeText: function (row) {
        var lo = row.ageMin, hi = row.ageMax;
        if (lo == null && hi == null) { return '不限'; }
        return (lo == null ? '0' : lo) + ' ~ ' + (hi == null ? '不限' : hi) + ' 岁';
      },
      removeRule: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除规则「' + (row.itemName || row.itemCode) + '」? 删除后该规则不再参与告警判定。',
          '删除规则', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.del('/api/his/inp/critical-value/rules/' + row.id);
        }).then(function () {
          HIS.notifySuccess('规则已删除');
          vm.loadRules();
        }).catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">危急值管理 <span style="font-size:12px;color:var(--yb-ink-3);font-weight:normal;">(检验结果阈值告警 · 通知→确认→处置→关闭全流程留痕; 30秒自动刷新)</span></div>',
      '  <el-tabs v-model="mainTab" @tab-change="onMainTab">',
      '    <el-tab-pane label="危急值处理" name="handle">',

      /* ---------- 模拟上报(开发测试, 折叠) ---------- */
      '      <el-collapse v-model="simOpen" @change="onSimToggle" style="margin-bottom:12px;">',
      '        <el-collapse-item name="sim">',
      '          <template #title><span class="cv-sim-title">⚠ 模拟检验上报(开发测试)</span><span style="margin-left:8px;color:var(--yb-ink-3);font-size:12px;">选择在院患者 → 输入项目与结果值 → 命中规则即生成告警并推送医生/护士</span></template>',
      '          <div class="cv-sim">',
      '            <div class="toolbar" style="margin:0;flex-wrap:wrap;row-gap:8px;">',
      '              <el-select v-model="sim.visitId" filterable :loading="visitsLoading" placeholder="选择在院患者(住院号/姓名/床号)" style="width:270px;">',
      '                <el-option v-for="v in visits" :key="v.id" :label="simVisitLabel(v)" :value="v.id"></el-option>',
      '              </el-select>',
      '              <el-select v-model="sim.itemCode" filterable allow-create default-first-option placeholder="检验项目(选择规则或输入编码)" style="width:250px;" @change="onSimItem">',
      '                <el-option v-for="r in rules" :key="r.itemCode" :label="(r.itemName || r.itemCode) + \' (\' + r.itemCode + \')\'" :value="r.itemCode"></el-option>',
      '              </el-select>',
      '              <el-input v-model="sim.resultValue" placeholder="结果值, 如 7.8" style="width:130px;"></el-input>',
      '              <el-input v-model="sim.unit" placeholder="单位(可自动回填)" style="width:130px;"></el-input>',
      '              <el-button type="danger" :loading="simLoading" @click="simSubmit">上报</el-button>',
      '            </div>',
      '            <div v-if="simResult" class="cv-sim-ok">',
      '              <template v-if="simResult.alert">',
      '                <span class="tagline">⚠ 命中危急值!</span>',
      '                <el-tag type="danger" size="small" effect="dark">{{ cvLevel[simResult.alertLevel] || \'危急\' }}</el-tag>',
      '                <span style="margin-left:8px;">记录 #{{ simResult.recordId }}</span>',
      '                <div style="margin-top:4px;">{{ simResult.message }}</div>',
      '                <div style="color:var(--yb-ink-3);font-size:12px;margin-top:2px;">已推送站内通知(预警), 并出现在下方处理列表「待通知」。</div>',
      '              </template>',
      '              <template v-else>',
      '                <span class="cv-sim-miss">✓ 未触发告警: {{ simResult.message }}</span>',
      '              </template>',
      '            </div>',
      '          </div>',
      '        </el-collapse-item>',
      '      </el-collapse>',

      /* ---------- 状态子Tab ---------- */
      '      <el-tabs v-model="statusTab" type="card" @tab-change="onStatusTab">',
      '        <el-tab-pane v-for="t in statusTabs" :key="t.v" :label="t.l" :name="t.v">',
      '          <div class="toolbar" style="margin-top:2px;">',
      '            <span style="color:var(--yb-ink-3);font-size:13px;">共 {{ total }} 条 · 当前「{{ statusFilterLabel }}」</span>',
      '            <span style="flex:1;"></span>',
      '            <el-button size="small" @click="load()">刷新</el-button>',
      '          </div>',
      '          <el-table :data="rows" border size="small" v-loading="loading" :row-class-name="rowClass">',
      '            <el-table-column type="index" label="序号" width="55" :index="seqNo"></el-table-column>',
      '            <el-table-column label="患者" min-width="110"><template #default="s">{{ s.row.patientName || \'-\' }}<div style="color:var(--yb-ink-3);font-size:12px;">{{ (s.row.gender || \'\') + (s.row.age != null ? \' \' + s.row.age + \'岁\' : \'\') }}</div></template></el-table-column>',
      '            <el-table-column label="床号" width="75" align="center"><template #default="s">{{ s.row.bedNo || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="检验项目" min-width="140"><template #default="s"><div style="font-weight:600;">{{ s.row.itemName || \'-\' }}</div><div style="color:var(--yb-ink-3);font-size:12px;">{{ s.row.itemCode || \'\' }}</div></template></el-table-column>',
      '            <el-table-column label="结果值" width="110" align="center"><template #default="s"><span class="cv-value" :class="valueClass(s.row)">{{ s.row.resultValue }}</span><div style="color:var(--yb-ink-3);font-size:12px;">{{ s.row.unit || \'\' }}</div></template></el-table-column>',
      '            <el-table-column label="参考范围" width="130"><template #default="s"><span style="font-family:Consolas,monospace;font-size:12px;">{{ s.row.refRange || \'-\' }}</span></template></el-table-column>',
      '            <el-table-column label="告警级别" width="90" align="center"><template #default="s"><el-tag size="small" :type="cvLevelTag[s.row.alertLevel] || \'info\'" :effect="s.row.alertLevel === 1 ? \'dark\' : \'plain\'">{{ cvLevel[s.row.alertLevel] || s.row.alertLevel }}</el-tag></template></el-table-column>',
      '            <el-table-column label="报告时间" width="150"><template #default="s">{{ s.row.reportTime || \'-\' }}</template></el-table-column>',
      '            <el-table-column label="流转记录" min-width="180"><template #default="s">',
      '              <div v-if="s.row.notifyDoctorTime" class="cv-flow-line">通知时间 <b>{{ s.row.notifyDoctorTime }}</b></div>',
      '              <div v-if="s.row.confirmTime" class="cv-flow-line">确认医生 <b>{{ s.row.confirmDoctorName || \'-\' }}</b> {{ s.row.confirmTime }}</div>',
      '              <div v-if="s.row.handleMeasures" class="cv-flow-line">处置 <span class="cv-handle-measures" :title="s.row.handleMeasures">{{ s.row.handleMeasures }}</span> {{ s.row.handleTime || \'\' }}</div>',
      '              <span v-if="!s.row.notifyDoctorTime && !s.row.confirmTime && !s.row.handleMeasures" style="color:var(--yb-ink-4);font-size:12px;">-</span>',
      '            </template></el-table-column>',
      '            <el-table-column label="状态" width="110" align="center"><template #default="s"><el-tag size="small" :type="cvStatusTag[s.row.status] || \'info\'" :effect="s.row.status < 4 ? \'dark\' : \'plain\'">{{ cvStatus[s.row.status] || s.row.status }}</el-tag></template></el-table-column>',
      '            <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '              <el-button v-if="s.row.status===1" link type="warning" size="small" @click="doNotify(s.row)">标记已通知</el-button>',
      '              <el-button v-else-if="s.row.status===2" link type="primary" size="small" @click="doConfirm(s.row)">医生确认</el-button>',
      '              <el-button v-else-if="s.row.status===3" link type="danger" size="small" @click="openHandle(s.row)">处置</el-button>',
      '              <el-button v-else-if="s.row.status===4" link type="success" size="small" @click="doClose(s.row)">关闭</el-button>',
      '              <span v-else style="color:var(--yb-success);font-size:12px;">已闭环</span>',
      '            </template></el-table-column>',
      '          </el-table>',
      '          <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '        </el-tab-pane>',
      '      </el-tabs>',
      '    </el-tab-pane>',

      /* ---------- 规则管理 ---------- */
      '    <el-tab-pane label="规则管理" name="rules">',
      '      <div class="toolbar">',
      '        <span style="color:var(--yb-ink-3);font-size:13px;">阈值判定: 结果值 &lt; 下限 或 &gt; 上限 即触发告警; 单侧留空表示该侧不检查。</span>',
      '        <span style="flex:1;"></span>',
      '        <el-button size="small" @click="loadRules">刷新</el-button>',
      '        <el-button type="primary" size="small" @click="openCreate">新增规则</el-button>',
      '      </div>',
      '      <el-table :data="rules" border size="small" v-loading="rulesLoading">',
      '        <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '        <el-table-column prop="itemCode" label="项目编码" width="110"></el-table-column>',
      '        <el-table-column prop="itemName" label="项目名称" min-width="150"></el-table-column>',
      '        <el-table-column prop="unit" label="单位" width="90"></el-table-column>',
      '        <el-table-column label="下限(低于告警)" width="120" align="right"><template #default="s"><span v-if="s.row.criticalLow != null" style="color:var(--yb-link);font-weight:600;">{{ s.row.criticalLow }}</span><span v-else style="color:var(--yb-ink-4);">不限</span></template></el-table-column>',
      '        <el-table-column label="上限(高于告警)" width="120" align="right"><template #default="s"><span v-if="s.row.criticalHigh != null" style="color:var(--yb-danger);font-weight:600;">{{ s.row.criticalHigh }}</span><span v-else style="color:var(--yb-ink-4);">不限</span></template></el-table-column>',
      '        <el-table-column label="性别" width="70" align="center"><template #default="s"><el-tag size="small" :type="s.row.gender ? \'warning\' : \'info\'">{{ cvGenderText(s.row.gender) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="年龄范围" width="110" align="center"><template #default="s">{{ ageRangeText(s.row) }}</template></el-table-column>',
      '        <el-table-column label="告警级别" width="90" align="center"><template #default="s"><el-tag size="small" :type="cvLevelTag[s.row.alertLevel] || \'info\'">{{ cvLevel[s.row.alertLevel] || s.row.alertLevel }}</el-tag></template></el-table-column>',
      '        <el-table-column label="启用" width="80" align="center"><template #default="s"><el-tag size="small" :type="s.row.enabled === 1 ? \'success\' : \'info\'">{{ s.row.enabled === 1 ? \'已启用\' : \'停用\' }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="120" align="center"><template #default="s">',
      '          <el-button link type="primary" size="small" @click="openEdit(s.row)">编辑</el-button>',
      '          <el-button link type="danger" size="small" @click="removeRule(s.row)">删除</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!rulesLoading && !rules.length" description="暂无危急值规则, 未配置规则时上报结果不会触发告警"></el-empty>',
      '    </el-tab-pane>',
      '  </el-tabs>',

      /* ---------- 处置弹窗 ---------- */
      '  <el-dialog v-model="handleVisible" title="记录处置措施" width="520px">',
      '    <el-alert v-if="handleRow" type="error" :closable="false" show-icon',
      '      :title="(handleRow.patientName || \'-\') + \' · \' + (handleRow.itemName || \'-\') + \' = \' + (handleRow.resultValue || \'-\') + (handleRow.unit || \'\') + \' (参考: \' + (handleRow.refRange || \'-\') + \')\'" style="margin-bottom:12px;"></el-alert>',
      '    <el-form label-width="80px">',
      '      <el-form-item label="处置措施" required>',
      '        <el-input v-model="handleMeasures" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="如: 立即复查血钾、静脉补钾, 已报告值班医师并记录病程..."></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <div style="color:var(--yb-ink-3);font-size:12px;margin-left:80px;">提交前需完成电子签名(处置医师)。</div>',
      '    <template #footer><el-button @click="handleVisible=false">取 消</el-button><el-button type="danger" :loading="handling" @click="submitHandle">签名并提交</el-button></template>',
      '  </el-dialog>',

      /* ---------- 规则编辑弹窗 ---------- */
      '  <el-dialog v-model="ruleVisible" :title="ruleForm.id ? \'编辑危急值规则\' : \'新增危急值规则\'" width="520px">',
      '    <el-form label-width="110px">',
      '      <el-form-item label="项目编码" required><el-input v-model="ruleForm.itemCode" maxlength="50" placeholder="与检验结果上报的项目编码一致, 如 K"></el-input></el-form-item>',
      '      <el-form-item label="项目名称" required><el-input v-model="ruleForm.itemName" maxlength="100" placeholder="如 血钾"></el-input></el-form-item>',
      '      <el-form-item label="单位"><el-input v-model="ruleForm.unit" maxlength="30" placeholder="如 mmol/L"></el-input></el-form-item>',
      '      <el-form-item label="危急值下限"><el-input-number v-model="ruleForm.criticalLow" :controls="false" style="width:100%;" placeholder="结果值低于该值即告警(留空=不检查)"></el-input-number></el-form-item>',
      '      <el-form-item label="危急值上限"><el-input-number v-model="ruleForm.criticalHigh" :controls="false" style="width:100%;" placeholder="结果值高于该值即告警(留空=不检查)"></el-input-number></el-form-item>',
      '      <el-form-item label="适用性别">',
      '        <el-radio-group v-model="ruleForm.gender">',
      '          <el-radio :label="0">通用</el-radio><el-radio :label="1">男</el-radio><el-radio :label="2">女</el-radio>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="年龄范围">',
      '        <el-input-number v-model="ruleForm.ageMin" :controls="false" :min="0" placeholder="下限" style="width:120px;"></el-input-number>',
      '        <span style="margin:0 8px;color:var(--yb-ink-3);">~</span>',
      '        <el-input-number v-model="ruleForm.ageMax" :controls="false" :min="0" placeholder="上限" style="width:120px;"></el-input-number>',
      '        <span style="margin-left:8px;color:var(--yb-ink-3);font-size:12px;">留空不限</span>',
      '      </el-form-item>',
      '      <el-form-item label="告警级别">',
      '        <el-radio-group v-model="ruleForm.alertLevel"><el-radio :label="1">危急(红)</el-radio><el-radio :label="2">异常(橙)</el-radio></el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="启用状态"><el-switch v-model="ruleForm.enabled" :active-value="1" :inactive-value="0"></el-switch><span style="margin-left:8px;color:var(--yb-ink-3);font-size:12px;">停用后该规则不参与告警判定</span></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="ruleVisible=false">取 消</el-button><el-button type="primary" :loading="ruleSaving" @click="saveRule">保 存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
