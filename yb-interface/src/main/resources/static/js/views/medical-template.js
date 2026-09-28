/* 病历模板管理: 六页签维护 soap/fragment/rx_set/order_set/diag_personal/diag_dept 医疗模板
 * 权限口径: 后端不判 owner, 前端按 个人级=仅本人staffId、科室级=仅本科室deptId、全院=ADMIN/SUPER_ADMIN 控制编辑删除;
 * create 显式传 staffId/deptId 定作用域(与医生站面板存模板口径一致) */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var TABS = [
    { key: 'soap', label: '病历模板' },
    { key: 'fragment', label: '病历片段' },
    { key: 'rx_set', label: '处方套' },
    { key: 'order_set', label: '医嘱套' },
    { key: 'diag_personal', label: '个人常用诊断' },
    { key: 'diag_dept', label: '科室诊断' }
  ];
  var RX_TYPE_LABELS = [
    { value: 'NORMAL', label: '普通处方' }, { value: 'EMERGENCY', label: '急诊处方' },
    { value: 'PEDIATRIC', label: '儿科处方' }, { value: 'TCM_HERB', label: '中药饮片处方' },
    { value: 'NARCOTIC', label: '麻醉药品处方' }, { value: 'PSYCHO1', label: '精一处方' }, { value: 'PSYCHO2', label: '精二处方' }
  ];
  var DIAG_CLASSES = [
    { v: 'west', l: '西医诊断' }, { v: 'tcm', l: '中医诊断' }, { v: 'symp', l: '中医症候' },
    { v: 'oper', l: '手术代码' }, { v: 'tumor', l: '肿瘤代码' }
  ];

  function text(v) { return v == null ? '' : String(v); }
  function parseJson(v, fallback) {
    if (v && typeof v === 'object') { return v; }
    try { return JSON.parse(text(v) || '{}'); } catch (e) { return fallback; }
  }

  HIS.views.MedicalTemplateManage = {
    name: 'MedicalTemplateManage',
    data: function () {
      return {
        tabs: TABS,
        activeTab: 'soap',
        keyword: '',
        loading: false,
        rows: [],
        depts: [],
        staffs: [],
        /* 编辑对话框 */
        dialogVisible: false,
        saving: false,
        editId: null,
        form: this.emptyForm(),
        /* 诊断目录检索(仅 diag 页签) */
        diagSearchCode: null,
        diagResults: []
      };
    },
    computed: {
      user: function () { return typeof HIS.getUser === 'function' ? (HIS.getUser() || {}) : {}; },
      isAdmin: function () { var r = text(this.user.role); return r === 'ADMIN' || r === 'SUPER_ADMIN'; },
      tabLabel: function () {
        var t = TABS.find(function (x) { return x.key === this.activeTab; }, this);
        return t ? t.label : '';
      },
      rxTypeLabels: function () { return RX_TYPE_LABELS; },
      diagClasses: function () { return DIAG_CLASSES; }
    },
    created: function () {
      this.loadDepts();
      this.loadStaffs();
      this.load();
    },
    methods: {
      emptyForm: function () {
        return {
          scope: 'personal', deptId: null, sortOrder: 0, status: 1, name: '',
          /* soap 结构化字段 */
          soap: { chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', auxExam: '', diagnosis: '', treatmentOpinion: '' },
          /* fragment / 诊断名称 */
          contentText: '',
          /* rx_set */
          rxType: 'NORMAL', rxItems: [],
          /* order_set */
          orderItems: []
        };
      },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(function () { vm.depts = []; });
      },
      loadStaffs: function () {
        var vm = this;
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false').then(function (d) { vm.staffs = d || []; }).catch(function () { vm.staffs = []; });
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/template/list?type=' + encodeURIComponent(vm.activeTab);
        if (vm.keyword.trim()) { q += '&keyword=' + encodeURIComponent(vm.keyword.trim()); }
        HIS.get(q).then(function (d) {
          vm.rows = (Array.isArray(d) ? d : []).slice().sort(function (a, b) {
            var sa = Number(a.sortOrder || 0) - Number(b.sortOrder || 0);
            return sa !== 0 ? sa : String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN');
          });
        }).catch(function (e) { vm.rows = []; HIS.notifyError(e); })
          .finally(function () { vm.loading = false; });
      },
      switchTab: function (tab) { this.activeTab = tab; this.keyword = ''; this.load(); },
      /* ===== 作用域与判权 ===== */
      scopeOf: function (row) {
        if (row.staffId != null) { return 'personal'; }
        return row.deptId != null ? 'dept' : 'global';
      },
      scopeLabel: function (row) {
        return { personal: '个人', dept: '科室', global: '全院' }[this.scopeOf(row)];
      },
      scopeTagType: function (row) {
        return { personal: 'info', dept: 'warning', global: 'success' }[this.scopeOf(row)];
      },
      canEditRow: function (row) {
        var scope = this.scopeOf(row);
        if (scope === 'global') { return this.isAdmin; }
        if (scope === 'personal') { return this.isAdmin || (this.user.staffId != null && String(row.staffId) === String(this.user.staffId)); }
        return this.isAdmin || (this.user.deptId != null && String(row.deptId) === String(this.user.deptId));
      },
      deptName: function (id) {
        var d = this.depts.find(function (x) { return String(x.id) === String(id); });
        return d ? d.deptName : (id == null ? '' : '#' + id);
      },
      staffName: function (id) {
        var s = this.staffs.find(function (x) { return String(x.id) === String(id); });
        return s ? s.staffName : (id == null ? '' : '#' + id);
      },
      /* ===== 编辑对话框: 按类型差异化装载 ===== */
      openCreate: function () {
        this.editId = null;
        this.form = this.emptyForm();
        this.form.scope = this.activeTab === 'diag_dept' || this.activeTab === 'order_set' ? 'dept' : 'personal';
        if (this.form.scope === 'dept' && this.user.deptId != null) { this.form.deptId = this.user.deptId; }
        this.diagSearchCode = null; this.diagResults = [];
        this.dialogVisible = true;
      },
      openEdit: function (row) {
        if (!this.canEditRow(row)) { ElementPlus.ElMessage.warning('无权限编辑该范围模板'); return; }
        this.editId = row.id;
        var f = this.emptyForm();
        f.name = row.name;
        f.sortOrder = row.sortOrder || 0;
        f.status = row.status == null ? 1 : row.status;
        f.scope = this.scopeOf(row);
        f.deptId = row.deptId;
        var content = parseJson(row.content, {});
        var type = this.activeTab;
        if (type === 'soap') {
          var soapKeys = Object.keys(f.soap);
          soapKeys.forEach(function (k) { f.soap[k] = content[k] != null ? text(content[k]) : ''; });
          f.name = row.name;
        } else if (type === 'fragment') {
          f.name = row.name;
          f.contentText = typeof content === 'string' ? content : text(content.text || row.content);
        } else if (type === 'rx_set') {
          f.rxType = content.rxType || 'NORMAL';
          f.rxItems = (Array.isArray(content.items) ? content.items : []).map(function (it) {
            return { itemName: text(it.itemName || it.name), spec: text(it.spec), dosage: text(it.dosage), usageMethod: text(it.usageMethod), frequency: text(it.frequency), days: it.days == null ? '' : it.days, quantity: it.quantity == null ? 1 : it.quantity };
          });
        } else if (type === 'order_set') {
          f.orderItems = (Array.isArray(content) ? content : (Array.isArray(content.items) ? content.items : [])).map(function (x) {
            return typeof x === 'string' ? { itemName: x } : { itemName: text(x.itemName || x.name || x.keyword) };
          }).filter(function (x) { return x.itemName; });
        } else if (type === 'diag_personal' || type === 'diag_dept') {
          f.contentText = row.name;
          f.diagCode = content.code;
          f.diagCategory = content.category || 'west';
        }
        this.form = f;
        this.diagSearchCode = null; this.diagResults = [];
        this.dialogVisible = true;
      },
      removeRow: function (row) {
        var vm = this;
        if (!vm.canEditRow(row)) { ElementPlus.ElMessage.warning('无权限删除该范围模板'); return; }
        ElementPlus.ElMessageBox.confirm('确认删除模板“' + row.name + '”？', '删除确认', { type: 'warning' }).then(function () {
          return HIS.del('/api/his/template/' + encodeURIComponent(row.id));
        }).then(function () {
          ElementPlus.ElMessage.success('模板已删除'); vm.load();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      /* ===== 条目行编辑(处方套/医嘱套) ===== */
      addItem: function () {
        if (this.activeTab === 'rx_set') { this.form.rxItems.push({ itemName: '', spec: '', dosage: '', usageMethod: '', frequency: '', days: '', quantity: 1 }); }
        else { this.form.orderItems.push({ itemName: '' }); }
      },
      removeItem: function (list, idx) { this.form[list].splice(idx, 1); },
      /* ===== 诊断目录检索选择(diag 页签, 五类统一诊断字典) ===== */
      remoteSearchDiag: function (query) {
        var vm = this;
        var kw = String(query || '').trim();
        if (!kw) { vm.diagResults = []; return; }
        var cls = vm.form.diagCategory || 'west';
        HIS.get('/api/community-dict/diag-dict/page?status=1&dictType=' + cls + '&page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (d) { vm.diagResults = (d && d.records) || []; })
          .catch(function () { vm.diagResults = []; });
      },
      onPickDiag: function (code) {
        var vm = this;
        if (!code) { return; }
        var row = vm.diagResults.find(function (r) { return (r.diagCode || r.code) === code; });
        if (row) {
          vm.form.name = row.diagName || row.name;
          vm.form.contentText = vm.form.name;
          vm.form.diagCode = row.diagCode || row.code;
          vm.form.diagCategory = row.dictType || row.diagCategory || vm.form.diagCategory;
        }
        vm.diagSearchCode = null;
      },
      /* ===== 保存: 按类型组 content, create 显式传作用域 ===== */
      buildContent: function () {
        var f = this.form;
        if (this.activeTab === 'soap') { return JSON.stringify(f.soap); }
        if (this.activeTab === 'fragment') { return JSON.stringify({ text: f.contentText }); }
        if (this.activeTab === 'rx_set') {
          return JSON.stringify({ rxType: f.rxType, items: f.rxItems.filter(function (it) { return text(it.itemName).trim(); }) });
        }
        if (this.activeTab === 'order_set') {
          return JSON.stringify(f.orderItems.filter(function (it) { return text(it.itemName).trim(); }).map(function (it) { return it.itemName.trim(); }));
        }
        if (this.activeTab === 'diag_personal' || this.activeTab === 'diag_dept') {
          return JSON.stringify({ code: f.diagCode || f.contentText, name: f.contentText, category: f.diagCategory || 'west' });
        }
        return JSON.stringify({ text: f.contentText });
      },
      validateForm: function () {
        var f = this.form;
        if (!text(f.name).trim() && this.activeTab !== 'fragment' && this.activeTab !== 'diag_personal' && this.activeTab !== 'diag_dept') { return '请填写模板名称'; }
        if (this.activeTab === 'fragment' && (!text(f.name).trim() || !text(f.contentText).trim())) { return '请填写片段名称与正文'; }
        if (this.activeTab === 'rx_set' && !f.rxItems.some(function (it) { return text(it.itemName).trim(); })) { return '处方套至少需要一个药品条目'; }
        if (this.activeTab === 'order_set' && !f.orderItems.some(function (it) { return text(it.itemName).trim(); })) { return '医嘱套至少需要一个项目条目'; }
        if ((this.activeTab === 'diag_personal' || this.activeTab === 'diag_dept') && !text(f.contentText).trim()) { return '请检索选择或填写诊断名称'; }
        if (f.scope === 'dept' && !f.deptId) { return '科室级模板必须选择科室'; }
        if (f.scope === 'global' && !this.isAdmin) { return '仅管理员可维护全院模板'; }
        return '';
      },
      save: function () {
        var vm = this;
        var err = vm.validateForm();
        if (err) { ElementPlus.ElMessage.warning(err); return; }
        var name = (vm.activeTab === 'diag_personal' || vm.activeTab === 'diag_dept')
          ? text(vm.form.contentText).trim()
          : text(vm.form.name).trim();
        var payload = {
          templateType: vm.activeTab, name: name, content: vm.buildContent(),
          sortOrder: Number(vm.form.sortOrder) || 0, status: Number(vm.form.status)
        };
        if (vm.form.scope === 'personal') { payload.staffId = vm.user.staffId || null; payload.deptId = null; }
        else if (vm.form.scope === 'dept') { payload.staffId = null; payload.deptId = vm.form.deptId; }
        else { payload.staffId = null; payload.deptId = null; }
        if (vm.form.scope === 'personal' && !payload.staffId) { ElementPlus.ElMessage.warning('当前账号未关联职工, 无法保存个人级模板(可改科室级)'); return; }
        vm.saving = true;
        var promise = vm.editId
          ? HIS.put('/api/his/template/' + encodeURIComponent(vm.editId), payload)
          : HIS.post('/api/his/template/create', payload);
        promise.then(function () {
          ElementPlus.ElMessage.success(vm.editId ? '模板已更新' : '模板已创建');
          vm.dialogVisible = false; vm.load();
        }).catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.saving = false; });
      }
    },
    template: `
      <div class="medical-template-wrap">
        <div class="page-card">
          <div class="page-title">病历模板管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(个人仅本人、科室仅本科室、全院仅管理员可维护)</span></div>
          <div class="toolbar">
            <el-radio-group v-model="activeTab" @change="switchTab">
              <el-radio-button v-for="t in tabs" :key="t.key" :label="t.key">{{ t.label }}</el-radio-button>
            </el-radio-group>
            <el-input v-model="keyword" placeholder="按名称检索" clearable style="width:180px" @keyup.enter="load"></el-input>
            <el-button @click="load">查询</el-button>
            <el-button type="primary" @click="openCreate">＋ 新建{{ tabLabel }}</el-button>
            <span style="margin-left:auto;color:var(--yb-ink-2);font-size:13px;">共 {{ rows.length }} 条</span>
          </div>
          <el-table :data="rows" v-loading="loading" border stripe size="small">
            <el-table-column type="index" label="序号" width="56"></el-table-column>
            <el-table-column label="范围" width="72" align="center">
              <template #default="s"><el-tag size="small" :type="scopeTagType(s.row)">{{ scopeLabel(s.row) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="归属" width="150" show-overflow-tooltip>
              <template #default="s">
                <span v-if="s.row.staffId != null">{{ staffName(s.row.staffId) }}</span>
                <span v-else-if="s.row.deptId != null">{{ deptName(s.row.deptId) }}</span>
                <span v-else class="dim">全院</span>
              </template>
            </el-table-column>
            <el-table-column prop="sortOrder" label="排序" width="66" align="center"></el-table-column>
            <el-table-column label="状态" width="70" align="center">
              <template #default="s"><el-tag size="small" :type="Number(s.row.status)===1 ? 'success' : 'info'">{{ Number(s.row.status)===1 ? '启用' : '停用' }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="150"><template #default="s">{{ text(s.row.createTime).slice(0,16) || '-' }}</template></el-table-column>
            <el-table-column label="操作" width="130" align="center">
              <template #default="s">
                <el-button link type="primary" size="small" :disabled="!canEditRow(s.row)" @click="openEdit(s.row)">编辑</el-button>
                <el-button link type="danger" size="small" :disabled="!canEditRow(s.row)" @click="removeRow(s.row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><div style="padding:24px 0;color:var(--yb-ink-4);">暂无{{ tabLabel }}, 点右上「新建」添加</div></template>
          </el-table>
        </div>

        <el-dialog v-model="dialogVisible" :title="(editId ? '编辑' : '新建') + tabLabel" width="720px" top="6vh">
          <el-form label-width="86px" size="small">
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 14px">
              <el-form-item label="作用范围">
                <el-radio-group v-model="form.scope" :disabled="editId !== null">
                  <el-radio label="personal">个人</el-radio>
                  <el-radio label="dept">科室</el-radio>
                  <el-radio label="global" :disabled="!isAdmin">全院</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="科室" v-if="form.scope==='dept'">
                <el-select v-model="form.deptId" filterable placeholder="选择科室" style="width:100%">
                  <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="排序号"><el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" style="width:110px"></el-input-number></el-form-item>
              <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>
            </div>

            <!-- 病历模板(soap): 结构化表单 -->
            <template v-if="activeTab==='soap'">
              <el-form-item label="模板名称"><el-input v-model="form.name" placeholder="例如: 高血压复诊模板"></el-input></el-form-item>
              <el-form-item label="主诉"><el-input v-model="form.soap.chiefComplaint" type="textarea" :autosize="{minRows:1,maxRows:3}"></el-input></el-form-item>
              <el-form-item label="现病史"><el-input v-model="form.soap.presentIllness" type="textarea" :autosize="{minRows:2,maxRows:4}"></el-input></el-form-item>
              <el-form-item label="既往史"><el-input v-model="form.soap.pastHistory" type="textarea" :autosize="{minRows:1,maxRows:3}"></el-input></el-form-item>
              <el-form-item label="体格检查"><el-input v-model="form.soap.physicalExam" type="textarea" :autosize="{minRows:1,maxRows:3}"></el-input></el-form-item>
              <el-form-item label="辅助检查"><el-input v-model="form.soap.auxExam" type="textarea" :autosize="{minRows:1,maxRows:3}"></el-input></el-form-item>
              <el-form-item label="诊断"><el-input v-model="form.soap.diagnosis"></el-input></el-form-item>
              <el-form-item label="治疗意见"><el-input v-model="form.soap.treatmentOpinion" type="textarea" :autosize="{minRows:2,maxRows:4}"></el-input></el-form-item>
            </template>

            <!-- 病历片段: 名称+纯文本正文 -->
            <template v-else-if="activeTab==='fragment'">
              <el-form-item label="片段名称"><el-input v-model="form.name" placeholder="例如: 糖尿病教育"></el-input></el-form-item>
              <el-form-item label="片段内容"><el-input v-model="form.contentText" type="textarea" :rows="5" placeholder="片段正文(插入病历用)"></el-input></el-form-item>
            </template>

            <!-- 处方套: 类型 + 条目行编辑 -->
            <template v-else-if="activeTab==='rx_set'">
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 14px">
                <el-form-item label="套名称"><el-input v-model="form.name" placeholder="例如: 上呼吸道感染常用方"></el-input></el-form-item>
                <el-form-item label="处方类型"><el-select v-model="form.rxType" style="width:100%"><el-option v-for="o in rxTypeLabels" :key="o.value" :label="o.label" :value="o.value"></el-option></el-select></el-form-item>
              </div>
              <el-table :data="form.rxItems" border size="small">
                <el-table-column label="药品" min-width="130"><template #default="s"><el-input v-model="s.row.itemName" size="small" placeholder="通用名"></el-input></template></el-table-column>
                <el-table-column label="规格" width="100"><template #default="s"><el-input v-model="s.row.spec" size="small"></el-input></template></el-table-column>
                <el-table-column label="单次剂量" width="90"><template #default="s"><el-input v-model="s.row.dosage" size="small"></el-input></template></el-table-column>
                <el-table-column label="用法" width="100"><template #default="s"><el-input v-model="s.row.usageMethod" size="small" placeholder="口服"></el-input></template></el-table-column>
                <el-table-column label="频次" width="90"><template #default="s"><el-input v-model="s.row.frequency" size="small" placeholder="TID"></el-input></template></el-table-column>
                <el-table-column label="天数" width="72"><template #default="s"><el-input v-model="s.row.days" size="small"></el-input></template></el-table-column>
                <el-table-column label="数量" width="72"><template #default="s"><el-input v-model="s.row.quantity" size="small"></el-input></template></el-table-column>
                <el-table-column label="" width="46" align="center"><template #default="s"><el-button link type="danger" size="small" @click="removeItem('rxItems', s.$index)">删</el-button></template></el-table-column>
                <template #empty><div class="dim" style="padding:12px 0">无条目, 点下方「＋添加条目」</div></template>
              </el-table>
              <el-button size="small" plain style="margin-top:8px" @click="addItem">＋ 添加条目</el-button>
            </template>

            <!-- 医嘱套: 项目名行编辑 -->
            <template v-else-if="activeTab==='order_set'">
              <el-form-item label="套名称"><el-input v-model="form.name" placeholder="例如: 入院常规"></el-input></el-form-item>
              <el-table :data="form.orderItems" border size="small">
                <el-table-column label="项目" show-overflow-tooltip><template #default="s"><el-input v-model="s.row.itemName" size="small" placeholder="诊疗项目名称(与目录名一致方可自动匹配)"></el-input></template></el-table-column>
                <el-table-column label="" width="46" align="center"><template #default="s"><el-button link type="danger" size="small" @click="removeItem('orderItems', s.$index)">删</el-button></template></el-table-column>
                <template #empty><div class="dim" style="padding:12px 0">无条目, 点下方「＋添加条目」</div></template>
              </el-table>
              <el-button size="small" plain style="margin-top:8px" @click="addItem">＋ 添加条目</el-button>
            </template>

            <!-- 个人常用诊断 / 科室诊断: 目录检索选择器 -->
            <template v-else>
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 14px">
                <el-form-item label="诊断类别">
                  <el-select v-model="form.diagCategory" style="width:100%"><el-option v-for="c in diagClasses" :key="c.v" :label="c.l" :value="c.v"></el-option></el-select>
                </el-form-item>
                <el-form-item label="检索选择">
                  <el-select v-model="diagSearchCode" filterable remote reserve-keyword clearable :remote-method="remoteSearchDiag" :disabled="!diagResults" style="width:100%" placeholder="检索诊断: 名称/编码/拼音, 选中自动带入" @change="onPickDiag">
                    <el-option v-for="r in diagResults" :key="r.code || r.diagCode" :label="r.name || r.diagName" :value="r.diagCode || r.code"></el-option>
                  </el-select>
                </el-form-item>
              </div>
              <el-form-item label="诊断名称"><el-input v-model="form.contentText" placeholder="诊断名称(可直接手填, 保存即沉淀)"></el-input></el-form-item>
              <el-form-item label="诊断编码"><el-input v-model="form.diagCode" placeholder="ICD 编码(选填)"></el-input></el-form-item>
            </template>
          </el-form>
          <template #footer>
            <el-button @click="dialogVisible=false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  /* text 挂到原型方法供模板内使用 */
  HIS.views.MedicalTemplateManage.methods.text = text;
})();
