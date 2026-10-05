/* RIS 质控管理(RisQc) — 质控规则 CRUD + 质控检查记录只读留痕。
 * 后端 /api/ris/admin:
 *   GET  /qc-rules             规则列表(登录机构 + org_id=0 全局种子并集, 含停用)
 *   POST /qc-rule              新增(编码空则服务端生成)
 *   PUT  /qc-rule/{id}         更新(编码不可变更, 非空字段覆盖)
 *   PUT  /qc-rule/{id}/toggle?enabled=0|1   规则启停
 *   GET  /qc-records           检查记录分页(报告关键字/规则/日期区间/通过与否)
 * 注册: HIS.views.RisQc; 双页签(规则/记录)布局; 类前缀 rq-。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var RULE_TYPES = [
    { v: 'COMPLETENESS', l: '完整性', t: 'primary' },
    { v: 'TIMELINESS', l: '时效性', t: 'warning' },
    { v: 'CONSISTENCY', l: '一致性', t: 'success' },
    { v: 'TERMINOLOGY', l: '术语规范', t: 'info' }
  ];
  var CHECK_POINTS = [
    { v: 'ON_SAVE', l: '保存时' }, { v: 'ON_SUBMIT', l: '提交时' },
    { v: 'ON_REVIEW', l: '审核时' }, { v: 'BATCH', l: '批量任务' }
  ];
  var SEVERITIES = [
    { v: 1, l: '警告', t: 'warning' }, { v: 2, l: '阻断', t: 'danger' }
  ];
  var DEPT_TYPES = [
    { v: 'RADIOLOGY', l: '放射 RADIOLOGY' }, { v: 'ULTRASOUND', l: '超声 ULTRASOUND' }, { v: 'ENDOSCOPY', l: '内镜 ENDOSCOPY' }
  ];

  function metaOf(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v || list[i].v === Number(v)) { return list[i]; } }
    return { v: v, l: (v == null || v === '') ? '-' : String(v), t: 'info' };
  }
  function labelOf(list, v) { return metaOf(list, v).l; }

  (function ensureRqStyles() {
    if (document.getElementById('ris-qc-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-qc-style';
    st.textContent = [
      '.rq-code { font-family: var(--yb-font-mono); font-size: 12px; color: var(--yb-ink-2); }',
      '.rq-tip { color: var(--yb-ink-4); font-size: 12px; line-height: 1.6; }',
      '.rq-msg { color: var(--yb-ink-3); font-size: 12px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  HIS.views.RisQc = {
    name: 'RisQc',
    data: function () {
      return {
        tab: 'rules',
        /* 规则页签 */
        rules: [], rulesLoading: false,
        ruleTypeOpts: RULE_TYPES, checkPointOpts: CHECK_POINTS,
        severityOpts: SEVERITIES, deptTypeOpts: DEPT_TYPES,
        dlg: { visible: false, saving: false, editing: false, editingId: null, form: {} },
        /* 记录页签 */
        recLoaded: false, recs: [], recLoading: false,
        recTotal: 0, recPage: 1, recSize: 20,
        recKeyword: '', recRuleId: null, recPassed: null, recRange: null,
        passedOpts: [{ v: 1, l: '通过' }, { v: 0, l: '未通过' }]
      };
    },
    watch: {
      tab: function (v) {
        if (v === 'records' && !this.recLoaded) { this.fetchRecords(); }
      }
    },
    created: function () { this.fetchRules(); },
    methods: {
      typeMeta: function (v) { return metaOf(RULE_TYPES, v); },
      typeLabel: function (v) { return labelOf(RULE_TYPES, v); },
      cpLabel: function (v) { return labelOf(CHECK_POINTS, v); },
      sevLabel: function (v) { return labelOf(SEVERITIES, v); },
      sevTag: function (v) { return metaOf(SEVERITIES, v).t; },
      deptLabel: function (v) { return (v == null || v === '') ? '通用' : labelOf(DEPT_TYPES, v); },
      seqNo: function (i) { return (this.recPage - 1) * this.recSize + i + 1; },

      /* ===== 质控规则 ===== */
      fetchRules: function () {
        var vm = this; vm.rulesLoading = true;
        HIS.get('/api/ris/admin/qc-rules').then(function (l) {
          vm.rules = l || [];
        }).catch(HIS.notifyError).finally(function () { vm.rulesLoading = false; });
      },
      emptyForm: function () {
        return {
          ruleCode: '', ruleName: '', ruleType: 'COMPLETENESS', deptType: '',
          checkPoint: 'ON_SAVE', ruleExpression: '', severity: 1,
          scoreDeduction: 2, message: '', enabled: 1
        };
      },
      openAdd: function () {
        this.dlg = { visible: true, saving: false, editing: false, editingId: null, form: this.emptyForm() };
      },
      openEdit: function (row) {
        this.dlg = {
          visible: true, saving: false, editing: true, editingId: HIS.id(row && row.id),
          form: {
            ruleCode: String(row.ruleCode || ''), ruleName: row.ruleName || '',
            ruleType: row.ruleType || 'COMPLETENESS', deptType: row.deptType || '',
            checkPoint: row.checkPoint || 'ON_SAVE', ruleExpression: row.ruleExpression || '',
            severity: Number(row.severity) || 1,
            scoreDeduction: row.scoreDeduction == null ? 2 : Number(row.scoreDeduction),
            message: row.message || '', enabled: Number(row.enabled) || 0
          }
        };
      },
      submit: function () {
        var vm = this;
        var f = vm.dlg.form;
        if (!f.ruleName || !String(f.ruleName).trim()) { ElementPlus.ElMessage.warning('请填写规则名称'); return; }
        if (!f.ruleType) { ElementPlus.ElMessage.warning('请选择规则类型'); return; }
        if (!f.checkPoint) { ElementPlus.ElMessage.warning('请选择检查时点'); return; }
        var expr = String(f.ruleExpression || '').trim();
        if (!expr) { ElementPlus.ElMessage.warning('请填写规则表达式'); return; }
        try { JSON.parse(expr); } catch (e) {
          ElementPlus.ElMessage.warning('规则表达式须为合法 JSON, 如 {"field":"conclusion","minLen":10}');
          return;
        }
        var body = {
          ruleName: String(f.ruleName).trim(),
          ruleType: f.ruleType,
          deptType: f.deptType || null,
          checkPoint: f.checkPoint,
          ruleExpression: expr,
          severity: Number(f.severity) || 1,
          scoreDeduction: f.scoreDeduction == null ? 0 : Number(f.scoreDeduction),
          message: f.message || null,
          enabled: Number(f.enabled) || 0
        };
        vm.dlg.saving = true;
        var p = vm.dlg.editing
          ? HIS.put('/api/ris/admin/qc-rule/' + encodeURIComponent(vm.dlg.editingId), body)
          : HIS.post('/api/ris/admin/qc-rule', body);
        p.then(function () {
          HIS.notifySuccess(vm.dlg.editing ? '规则已更新' : '规则已新增');
          vm.dlg.visible = false;
          vm.fetchRules();
        }).catch(HIS.notifyError).finally(function () { vm.dlg.saving = false; });
      },
      /* 启停: :model-value 绑定行数据, 失败不改行值即自动回显 */
      toggleEnable: function (row, val) {
        var vm = this;
        HIS.put('/api/ris/admin/qc-rule/' + HIS.idParam(row && row.id) + '/toggle?enabled=' + (val ? 1 : 0))
          .then(function (d) {
            row.enabled = (d && d.enabled != null) ? Number(d.enabled) : (val ? 1 : 0);
            HIS.notifySuccess(val ? '规则已启用' : '规则已停用');
          })
          .catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认删除规则「' + row.ruleName + '(' + row.ruleCode + ')」? 删除后报告书写/审核环节不再执行该检查。',
          '删除规则', { type: 'warning' }
        ).then(function () {
          return HIS.del('/api/ris/admin/qc-rule/' + HIS.idParam(row && row.id));
        }).then(function () {
          HIS.notifySuccess('已删除');
          vm.fetchRules();
        }).catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      },

      /* ===== 质控记录 ===== */
      searchRecords: function () { this.recPage = 1; this.fetchRecords(); },
      fetchRecords: function () {
        var vm = this; vm.recLoading = true; vm.recLoaded = true;
        var q = '/api/ris/admin/qc-records?page=' + vm.recPage + '&size=' + vm.recSize;
        if (vm.recKeyword) { q += '&keyword=' + encodeURIComponent(vm.recKeyword.trim()); }
        if (vm.recRuleId) { q += '&ruleId=' + HIS.idParam(vm.recRuleId); }
        if (vm.recPassed != null) { q += '&passed=' + vm.recPassed; }
        if (vm.recRange && vm.recRange.length === 2) {
          q += '&startDate=' + vm.recRange[0] + '&endDate=' + vm.recRange[1];
        }
        HIS.get(q).then(function (d) {
          vm.recs = (d && d.records) || [];
          vm.recTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.recLoading = false; });
      },
      resetRecFilter: function () {
        this.recKeyword = ''; this.recRuleId = null;
        this.recPassed = null; this.recRange = null;
        this.searchRecords();
      },
      onRecPage: function (p) { this.recPage = p; this.fetchRecords(); },
      onRecSize: function (s) { this.recSize = s; this.recPage = 1; this.fetchRecords(); }
    },
    template: [
      '<div class="page-card cd-fill cd-tabs">',
      '  <div class="page-title">RIS 质控管理',
      '    <span style="font-size:12px;color:var(--yb-ink-3);font-weight:normal;">(his_ris_qc_rule + his_ris_qc_record · 完整性/时效性/一致性/术语四类规则, 保存/提交/审核/批量四检查时点)</span>',
      '  </div>',
      '  <el-tabs v-model="tab">',
      '    <el-tab-pane label="质控规则" name="rules">',
      '      <div class="toolbar">',
      '        <el-button type="primary" @click="fetchRules">刷新</el-button>',
      '        <span style="flex:1;"></span>',
      '        <el-button type="success" @click="openAdd">新增规则</el-button>',
      '        <span class="rq-tip">共 {{ rules.length }} 条</span>',
      '      </div>',
      '      <el-table :data="rules" v-loading="rulesLoading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="#" width="48"></el-table-column>',
      '        <el-table-column prop="ruleCode" label="规则编码" width="140"><template #default="s"><span class="rq-code">{{ s.row.ruleCode }}</span></template></el-table-column>',
      '        <el-table-column prop="ruleName" label="规则名称" min-width="170" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="类型" width="96" align="center"><template #default="s">',
      '          <el-tag size="small" :type="typeMeta(s.row.ruleType).t">{{ typeLabel(s.row.ruleType) }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column label="科室类型" width="120" show-overflow-tooltip><template #default="s">{{ deptLabel(s.row.deptType) }}</template></el-table-column>',
      '        <el-table-column label="检查时点" width="90" align="center"><template #default="s">{{ cpLabel(s.row.checkPoint) }}</template></el-table-column>',
      '        <el-table-column label="严重级别" width="86" align="center"><template #default="s">',
      '          <el-tag size="small" :type="sevTag(s.row.severity)">{{ sevLabel(s.row.severity) }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column prop="scoreDeduction" label="扣分" width="64" align="center"><template #default="s">{{ s.row.scoreDeduction == null ? 0 : s.row.scoreDeduction }}</template></el-table-column>',
      '        <el-table-column prop="message" label="提示消息" min-width="170" show-overflow-tooltip><template #default="s"><span class="rq-msg">{{ s.row.message || \'-\' }}</span></template></el-table-column>',
      '        <el-table-column label="状态" width="86" align="center"><template #default="s">',
      '          <el-switch :model-value="Number(s.row.enabled)===1" @change="toggleEnable(s.row, $event)"></el-switch>',
      '        </template></el-table-column>',
      '        <el-table-column label="操作" width="110" fixed="right"><template #default="s">',
      '          <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '          <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '        </template></el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',

      '    <el-tab-pane :label="\'质控记录\' + (recLoaded ? \'(\' + recTotal + \')\' : \'\')" name="records">',
      '      <div class="toolbar">',
      '        <el-input v-model="recKeyword" placeholder="申请单号/患者姓名/报告ID" clearable style="width:220px" @keyup.enter="searchRecords"></el-input>',
      '        <el-select v-model="recRuleId" placeholder="规则" clearable filterable style="width:200px" @change="searchRecords">',
      '          <el-option v-for="r in rules" :key="HIS.idKey(r.id)" :label="r.ruleName" :value="HIS.id(r.id)"></el-option>',
      '        </el-select>',
      '        <el-select v-model="recPassed" placeholder="结果" clearable style="width:110px" @change="searchRecords">',
      '          <el-option v-for="p in passedOpts" :key="p.v" :label="p.l" :value="p.v"></el-option>',
      '        </el-select>',
      '        <el-date-picker v-model="recRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '          start-placeholder="检查开始" end-placeholder="检查结束" style="width:240px" @change="searchRecords"></el-date-picker>',
      '        <el-button type="primary" @click="searchRecords">检索</el-button>',
      '        <el-button @click="resetRecFilter">重置</el-button>',
      '        <span class="rq-tip">共 {{ recTotal }} 条</span>',
      '      </div>',
      '      <el-table :data="recs" v-loading="recLoading" border stripe size="small" height="100%">',
      '        <el-table-column type="index" label="#" width="48" :index="seqNo"></el-table-column>',
      '        <el-table-column prop="checkTime" label="检查时间" width="150"><template #default="s"><span class="rq-code">{{ s.row.checkTime || \'-\' }}</span></template></el-table-column>',
      '        <el-table-column prop="requestNo" label="申请单号" width="140" show-overflow-tooltip><template #default="s"><span class="rq-code">{{ s.row.requestNo || \'-\' }}</span></template></el-table-column>',
      '        <el-table-column prop="patientName" label="患者姓名" width="90" show-overflow-tooltip><template #default="s">{{ s.row.patientName || \'-\' }}</template></el-table-column>',
      '        <el-table-column prop="reportId" label="报告ID" width="150" show-overflow-tooltip><template #default="s"><span class="rq-code">{{ HIS.idKey(s.row.reportId) || \'-\' }}</span></template></el-table-column>',
      '        <el-table-column prop="ruleCode" label="规则编码" width="130" show-overflow-tooltip><template #default="s"><span class="rq-code">{{ s.row.ruleCode || \'-\' }}</span></template></el-table-column>',
      '        <el-table-column prop="ruleName" label="规则名称" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.ruleName || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="类型" width="90" align="center"><template #default="s">{{ typeLabel(s.row.ruleType) }}</template></el-table-column>',
      '        <el-table-column label="严重级别" width="86" align="center"><template #default="s">',
      '          <el-tag size="small" :type="sevTag(s.row.severity)">{{ sevLabel(s.row.severity) }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column prop="scoreDeduction" label="扣分" width="62" align="center"><template #default="s">{{ s.row.scoreDeduction == null ? 0 : s.row.scoreDeduction }}</template></el-table-column>',
      '        <el-table-column label="结果" width="80" align="center"><template #default="s">',
      '          <el-tag size="small" :type="Number(s.row.passed)===1 ? \'success\' : \'danger\'">{{ Number(s.row.passed)===1 ? \'通过\' : \'未通过\' }}</el-tag>',
      '        </template></el-table-column>',
      '        <el-table-column prop="detail" label="检查明细" min-width="200" show-overflow-tooltip></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '        :total="recTotal" :page-size="recSize" :page-sizes="[10, 20, 50, 100]" :current-page="recPage" @current-change="onRecPage" @size-change="onRecSize"></el-pagination>',
      '    </el-tab-pane>',
      '  </el-tabs>',

      '  <el-dialog v-model="dlg.visible" :title="dlg.editing ? \'编辑质控规则\' : \'新增质控规则\'" width="600px" top="7vh">',
      '    <el-form :model="dlg.form" label-width="92px" size="small">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="规则编码" required>',
      '          <el-input v-model="dlg.form.ruleCode" :disabled="dlg.editing" placeholder="留空自动生成"></el-input>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规则名称" required>',
      '          <el-input v-model="dlg.form.ruleName" placeholder="如 报告结论必填"></el-input>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="规则类型" required>',
      '          <el-select v-model="dlg.form.ruleType" style="width:100%">',
      '            <el-option v-for="t in ruleTypeOpts" :key="t.v" :label="t.l + \' \' + t.v" :value="t.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="科室类型">',
      '          <el-select v-model="dlg.form.deptType" clearable placeholder="空=通用" style="width:100%">',
      '            <el-option v-for="d in deptTypeOpts" :key="d.v" :label="d.l" :value="d.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="检查时点" required>',
      '          <el-select v-model="dlg.form.checkPoint" style="width:100%">',
      '            <el-option v-for="c in checkPointOpts" :key="c.v" :label="c.l + \'(\' + c.v + \')\'" :value="c.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="严重级别" required>',
      '          <el-radio-group v-model="dlg.form.severity">',
      '            <el-radio v-for="s in severityOpts" :key="s.v" :value="s.v">{{ s.l }}</el-radio>',
      '          </el-radio-group>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="扣分">',
      '          <el-input-number v-model="dlg.form.scoreDeduction" :min="0" :max="100" controls-position="right" style="width:100%"></el-input-number>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="是否启用">',
      '          <el-switch v-model="dlg.form.enabled" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="提示消息">',
      '        <el-input v-model="dlg.form.message" placeholder="检查未通过时展示给医师的提示, 如 结论不能为空"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="规则表达式" required>',
      '        <el-input v-model="dlg.form.ruleExpression" type="textarea" :rows="4"',
      '          placeholder=\'JSON 表达式, 如 {"field":"conclusion","minLen":10}\'></el-input>',
      '        <div class="rq-tip">合法性由引擎按类型解释: 完整性(必填字段列表)/时效性(时限阈值)/一致性(交叉校验)/术语(敏感词库)。</div>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlg.visible=false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="dlg.saving" @click="submit">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
