/* 病历质控规则维护: HIS.views.EmrQualityRuleManage —— his_emr_quality_rule 四类规则(完整性/时限性/逻辑性/规范性)可视化增删改。
 * 后端契约: GET /api/his/emr/quality/rules | POST /api/his/emr/quality/rule | PUT /api/his/emr/quality/rule/{id} | DELETE /{id}
 *          (写操作服务端 guard.requireLeadOrg 鉴权, 前端按 HIS.isLead() 启用/禁用按钮)。
 * rule_config 构造器: 按 cfgType 动态子表单(required/maxLength/deadline/pattern/logic/term), 保存时拼 JSON 字符串落 ruleConfig。
 * 评分语义: severity=1 警告(不扣分)/2 按 deductScore 扣分/3 一票否决(0分)。注册: HIS.views.EmrQualityRuleManage(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var RECORD_TYPES = [
    { v: 1, l: '1-入院记录' }, { v: 2, l: '2-首次病程' }, { v: 3, l: '3-日常病程' }, { v: 4, l: '4-上级查房' },
    { v: 5, l: '5-术前小结' }, { v: 6, l: '6-手术记录' }, { v: 7, l: '7-术后病程' }, { v: 8, l: '8-出院小结' }, { v: 9, l: '9-死亡记录' }
  ];
  var RULE_TYPES = [{ v: 1, l: '完整性' }, { v: 2, l: '时限性' }, { v: 3, l: '逻辑性' }, { v: 4, l: '规范性' }];
  var SEVERITIES = [{ v: 1, l: '警告(不扣分)' }, { v: 2, l: '扣分' }, { v: 3, l: '一票否决(0分)' }];
  var CFG_TYPES = [
    { v: 'required', l: '必填(完整性)' }, { v: 'maxLength', l: '长度上限(规范)' }, { v: 'deadline', l: '完成时限(时限)' },
    { v: 'pattern', l: '正则格式(规范)' }, { v: 'logic', l: '字段间比较(逻辑)' }, { v: 'term', l: '值域命中(规范)' }
  ];
  var LOGIC_OPS = [
    { v: 'eq', l: '等于' }, { v: 'ne', l: '不等于' }, { v: 'contains', l: '包含' },
    { v: 'gt', l: '大于' }, { v: 'ge', l: '大于等于' }, { v: 'lt', l: '小于' }, { v: 'le', l: '小于等于' },
    { v: 'before', l: '时间早于' }, { v: 'after', l: '时间晚于' }
  ];

  function emptyForm() {
    return {
      id: null, ruleCode: '', ruleName: '', recordType: null, ruleType: 1, deductScore: 5, severity: 2,
      status: 1, description: '',
      cfgType: 'required', cfgField: '', cfgMax: 200, cfgHours: 24, cfgDays: null, cfgFromField: '',
      cfgRegex: '', cfgOp: 'before', cfgLeft: '', cfgRight: '', cfgUseConst: false, cfgConst: '', cfgValuesText: ''
    };
  }
  function text(v) { return v == null ? '' : String(v); }

  HIS.views.EmrQualityRuleManage = {
    data: function () {
      return {
        loading: false, list: [], filterRuleType: null, filterStatus: null,
        recordTypes: RECORD_TYPES, ruleTypes: RULE_TYPES, severities: SEVERITIES,
        cfgTypes: CFG_TYPES, logicOps: LOGIC_OPS,
        dlgVisible: false, dlgTitle: '新建规则', saving: false, form: emptyForm(),
        isLead: false
      };
    },
    computed: {
      ruleTypeName: function () {
        return function (t) { var x = RULE_TYPES.filter(function (r) { return r.v === t; })[0]; return x ? x.l : '-'; };
      },
      severityName: function () {
        return function (s) { var x = SEVERITIES.filter(function (r) { return r.v === s; })[0]; return x ? x.l : '-'; };
      },
      severityTag: function () {
        return function (s) { return s === 3 ? 'danger' : (s === 1 ? 'warning' : 'info'); };
      }
    },
    created: function () {
      this.isLead = typeof HIS.isLead === 'function' ? !!HIS.isLead() : false;
      this.load();
    },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/his/emr/quality/rules?'
          + (vm.filterRuleType != null ? '&ruleType=' + vm.filterRuleType : '')
          + (vm.filterStatus != null ? '&status=' + vm.filterStatus : '');
        HIS.get(url).then(function (d) { vm.list = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      openCreate: function () {
        if (!this.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护质控规则'); return; }
        this.form = emptyForm(); this.dlgTitle = '新建规则'; this.dlgVisible = true;
      },
      openEdit: function (row) {
        if (!this.isLead) { ElementPlus.ElMessage.warning('仅牵头机构管理员可维护质控规则'); return; }
        var f = emptyForm();
        f.id = row.id; f.ruleCode = row.ruleCode; f.ruleName = row.ruleName;
        f.recordType = row.recordType; f.ruleType = row.ruleType; f.deductScore = row.deductScore;
        f.severity = row.severity; f.status = row.status; f.description = row.description;
        try {
          var cfg = JSON.parse(row.ruleConfig || '{}');
          f.cfgType = cfg.type || 'required';
          f.cfgField = cfg.field || '';
          f.cfgMax = cfg.max != null ? cfg.max : 200;
          f.cfgHours = cfg.hours != null ? cfg.hours : 24;
          f.cfgDays = cfg.days != null ? cfg.days : null;
          f.cfgFromField = cfg.fromField || '';
          f.cfgRegex = cfg.regex || '';
          f.cfgOp = cfg.op || 'before';
          f.cfgLeft = cfg.left || '';
          f.cfgRight = cfg.right || '';
          f.cfgUseConst = cfg.rightValue != null;
          f.cfgConst = cfg.rightValue != null ? String(cfg.rightValue) : '';
          f.cfgValuesText = Array.isArray(cfg.values) ? cfg.values.join(',') : '';
        } catch (e) { /* 保留默认 cfgType */ }
        this.form = f; this.dlgTitle = '编辑规则 #' + HIS.idKey(row.id); this.dlgVisible = true;
      },
      buildConfig: function () {
        var f = this.form, o = {};
        o.type = f.cfgType;
        if (f.cfgType === 'required') { o.field = f.cfgField; }
        else if (f.cfgType === 'maxLength') { o.field = f.cfgField; o.max = Number(f.cfgMax); }
        else if (f.cfgType === 'deadline') {
          if (f.cfgHours != null && f.cfgHours !== '') { o.hours = Number(f.cfgHours); }
          if (f.cfgDays != null && f.cfgDays !== '') { o.days = Number(f.cfgDays); }
          if (f.cfgFromField) { o.fromField = f.cfgFromField; }
        } else if (f.cfgType === 'pattern') { o.field = f.cfgField; o.regex = f.cfgRegex; }
        else if (f.cfgType === 'logic') {
          o.op = f.cfgOp; o.left = f.cfgLeft;
          if (f.cfgUseConst) { o.rightValue = f.cfgConst; } else { o.right = f.cfgRight; }
        } else if (f.cfgType === 'term') {
          o.field = f.cfgField;
          o.values = String(f.cfgValuesText || '').split(/[,，;；]/).map(function (x) { return x.trim(); }).filter(Boolean);
        }
        return JSON.stringify(o);
      },
      validate: function () {
        var f = this.form;
        if (!text(f.ruleCode).trim()) { ElementPlus.ElMessage.warning('规则编码不能为空'); return false; }
        if (!text(f.ruleName).trim()) { ElementPlus.ElMessage.warning('规则名称不能为空'); return false; }
        if (!text(f.ruleType)) { ElementPlus.ElMessage.warning('规则类型不能为空'); return false; }
        if ((f.cfgType === 'required' || f.cfgType === 'maxLength' || f.cfgType === 'pattern' || f.cfgType === 'term')
          && !text(f.cfgField).trim()) { ElementPlus.ElMessage.warning('请填写检查字段'); return false; }
        if (f.cfgType === 'pattern' && !text(f.cfgRegex).trim()) { ElementPlus.ElMessage.warning('请填写正则表达式'); return false; }
        if (f.cfgType === 'logic' && !text(f.cfgLeft).trim()) { ElementPlus.ElMessage.warning('逻辑比较需填写左字段'); return false; }
        return true;
      },
      save: function () {
        var vm = this;
        if (!vm.validate()) { return; }
        var f = vm.form;
        var body = {
          ruleCode: text(f.ruleCode).trim(), ruleName: text(f.ruleName).trim(),
          recordType: f.recordType, ruleType: Number(f.ruleType), ruleConfig: vm.buildConfig(),
          deductScore: f.deductScore == null || f.deductScore === '' ? null : Number(f.deductScore),
          severity: Number(f.severity), description: text(f.description), status: Number(f.status)
        };
        vm.saving = true;
        var req = f.id ? HIS.put('/api/his/emr/quality/rule/' + HIS.idParam(f.id), body)
          : HIS.post('/api/his/emr/quality/rule', body);
        req.then(function () {
          HIS.notifySuccess(f.id ? '规则已更新' : '规则已新建');
          vm.dlgVisible = false; vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      remove: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除规则「' + (row.ruleName || '') + '」？', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/his/emr/quality/rule/' + HIS.idParam(row.id)); })
          .then(function () { HIS.notifySuccess('已删除'); vm.load(); })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div>',
      '  <div class="page-card" style="display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;">',
      '    <div>',
      '      <div style="font-size:16px;font-weight:600;color:var(--yb-ink-1);">病历质控规则维护</div>',
      '      <div style="color:var(--yb-ink-2);font-size:13px;margin-top:6px;">',
      '        四类规则逐条检查评分(100分起扣) · 逻辑性/规范性基于结构化字段 · ',
      '        <el-tag size="small" :type="isLead ? \'success\' : \'info\'">{{ isLead ? "牵头机构·可维护" : "非牵头·只读" }}</el-tag>',
      '      </div>',
      '    </div>',
      '    <div style="display:flex;align-items:center;gap:10px;">',
      '      <el-select v-model="filterRuleType" placeholder="规则类型" clearable size="small" style="width:130px;" @change="load">',
      '        <el-option v-for="t in ruleTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '      </el-select>',
      '      <el-select v-model="filterStatus" placeholder="状态" clearable size="small" style="width:110px;" @change="load">',
      '        <el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option>',
      '      </el-select>',
      '      <el-button size="small" @click="load">刷新</el-button>',
      '      <el-button size="small" type="primary" :disabled="!isLead" @click="openCreate">+ 新建规则</el-button>',
      '    </div>',
      '  </div>',
      '  <el-card shadow="never" style="margin-top:14px;">',
      '    <el-table :data="list" v-loading="loading" size="small" border stripe max-height="560">',
      '      <el-table-column prop="ruleCode" label="编码" width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="ruleName" label="规则名称" min-width="180" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="类型" width="90"><template #default="s">{{ ruleTypeName(s.row.ruleType) }}</template></el-table-column>',
      '      <el-table-column prop="recordType" label="记录" width="70"></el-table-column>',
      '      <el-table-column label="扣分" width="70"><template #default="s">{{ s.row.deductScore }}</template></el-table-column>',
      '      <el-table-column label="严重度" width="120"><template #default="s"><el-tag size="small" :type="severityTag(s.row.severity)">{{ severityName(s.row.severity) }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="ruleConfig" label="检查条件" min-width="220" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="120" fixed="right">',
      '        <template #default="s">',
      '          <el-button link type="primary" size="small" :disabled="!isLead" @click="openEdit(s.row)">编辑</el-button>',
      '          <el-button link type="danger" size="small" :disabled="!isLead" @click="remove(s.row)">删除</el-button>',
      '        </template>',
      '      </el-table-column>',
      '    </el-table>',
      '    <div style="margin-top:10px;color:var(--yb-ink-2);font-size:12px;">共 {{ list.length }} 条规则</div>',
      '  </el-card>',
      /* ---- 规则编辑弹窗 ---- */
      '  <el-dialog v-model="dlgVisible" :title="dlgTitle" width="640px" top="6vh">',
      '    <el-form label-width="96px" size="small">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="规则编码"><el-input v-model="form.ruleCode" :disabled="!!form.id" placeholder="如 ADMIT_CHIEF_REQ"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="规则名称"><el-input v-model="form.ruleName" placeholder="简明名称"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="规则类型"><el-select v-model="form.ruleType" style="width:100%;"><el-option v-for="t in ruleTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="记录类型"><el-select v-model="form.recordType" clearable placeholder="空=通用" style="width:100%;"><el-option v-for="t in recordTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="严重程度"><el-select v-model="form.severity" style="width:100%;"><el-option v-for="t in severities" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="扣分分值"><el-input-number v-model="form.deductScore" :min="0" :max="100" :step="1" controls-position="right" style="width:100%;"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="状态"><el-select v-model="form.status" style="width:100%;"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="规则说明"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="展示给医生的扣分说明"></el-input></el-form-item>',
      /* 检查条件构造器 */
      '      <el-divider content-position="left">检查条件(rule_config)</el-divider>',
      '      <el-form-item label="条件类型"><el-select v-model="form.cfgType" style="width:220px;"><el-option v-for="t in cfgTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item>',
      '      <template v-if="form.cfgType===\'required\' || form.cfgType===\'term\' || form.cfgType===\'pattern\'">',
      '        <el-form-item label="检查字段"><el-input v-model="form.cfgField" placeholder="structure fieldKey 或经典字段(如 chiefComplaint)"></el-input></el-form-item>',
      '      </template>',
      '      <template v-if="form.cfgType===\'maxLength\'">',
      '        <el-form-item label="检查字段"><el-input v-model="form.cfgField"></el-input></el-form-item>',
      '        <el-form-item label="最大长度"><el-input-number v-model="form.cfgMax" :min="1" controls-position="right"></el-input-number></el-form-item>',
      '      </template>',
      '      <template v-if="form.cfgType===\'pattern\'">',
      '        <el-form-item label="正则表达式"><el-input v-model="form.cfgRegex" placeholder="如 ^[0-9]{11}$"></el-input></el-form-item>',
      '      </template>',
      '      <template v-if="form.cfgType===\'term\'">',
      '        <el-form-item label="允许值域"><el-input v-model="form.cfgValuesText" placeholder="逗号分隔, 如 男,女,未知"></el-input></el-form-item>',
      '      </template>',
      '      <template v-if="form.cfgType===\'deadline\'">',
      '        <el-form-item label="小时限制"><el-input-number v-model="form.cfgHours" :min="0" controls-position="right" placeholder="留空不限"></el-input-number></el-form-item>',
      '        <el-form-item label="天数限制"><el-input-number v-model="form.cfgDays" :min="0" controls-position="right" placeholder="留空不限"></el-input-number></el-form-item>',
      '        <el-form-item label="基准字段"><el-input v-model="form.cfgFromField" placeholder="如 admitDate / structure 日期字段(空=记录自带截止)"></el-input></el-form-item>',
      '      </template>',
      '      <template v-if="form.cfgType===\'logic\'">',
      '        <el-form-item label="比较运算"><el-select v-model="form.cfgOp" style="width:160px;"><el-option v-for="t in logicOps" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select></el-form-item>',
      '        <el-form-item label="左字段"><el-input v-model="form.cfgLeft" placeholder="字段键"></el-input></el-form-item>',
      '        <el-form-item label="右对象"><el-checkbox v-model="form.cfgUseConst">使用常量值</el-checkbox></el-form-item>',
      '        <el-form-item v-if="!form.cfgUseConst" label="右字段"><el-input v-model="form.cfgRight" placeholder="另一字段键"></el-input></el-form-item>',
      '        <el-form-item v-else label="常量值"><el-input v-model="form.cfgConst" placeholder="比较常量"></el-input></el-form-item>',
      '      </template>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlgVisible=false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
