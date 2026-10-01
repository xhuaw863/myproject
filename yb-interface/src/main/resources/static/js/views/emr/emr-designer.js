/* 病历结构化模板可视化设计器: HIS.views.EmrTemplateDesigner —— 全院统一(住院/门诊)结构化病历模板的自建与维护。
 * 左控件库(点击/拖拽加入画布) + 中画布(拖拽排序、选中编辑、预览渲染) + 右属性面板(标签/必填/类型专属/值域绑定/默认宏)。
 * 产出的 rawFields 原样落 his_emr_template.fields(含 dictRef/subFields/section 等新属性), 供病历面板 EmrField 动态渲染。
 * 后端契约: GET /api/his/emr/template/list | /{id}/defs | POST /api/his/emr/template | PUT /{id} | DELETE /{id};
 *          归属层级 personal/dept/global 由服务端按登录用户判权(前端仅按 medical-template 同款规则展示/禁用)。
 * 注册: HIS.views.EmrTemplateDesigner (须在 app.js 之前加载; 依赖 emr-field.js 的 HIS.components.EmrField)。 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  const FIELD_TYPES = [
    { t: 'text', n: '单行文本' }, { t: 'textarea', n: '多行文本' }, { t: 'number', n: '数字' },
    { t: 'date', n: '日期' }, { t: 'datetime', n: '日期时间' }, { t: 'select', n: '下拉单选' },
    { t: 'multiselect', n: '下拉多选' }, { t: 'radio', n: '单选框组' }, { t: 'checkbox', n: '复选框组' },
    { t: 'diagnosis', n: '诊断检索' }, { t: 'catalog', n: '项目检索' }, { t: 'vitals', n: '生命体征' },
    { t: 'table', n: '子表格' }, { t: 'signature', n: '电子签名' }, { t: 'section', n: '分节标题' }
  ];
  const TYPE_NAME = {}; FIELD_TYPES.forEach(function (x) { TYPE_NAME[x.t] = x.n; });
  const CHOICE = { select: 1, multiselect: 1, radio: 1, checkbox: 1 };
  const DICT_SOURCE = [
    { v: '', l: '无(手工选项)' }, { v: 'diag', l: '医保/临床诊断' },
    { v: 'charge', l: '医疗服务项目' }, { v: 'drug', l: '药品目录' }
  ];
  const CATEGORIES = [
    { v: 1, l: '入院记录' }, { v: 2, l: '首次病程' }, { v: 3, l: '日常病程' }, { v: 4, l: '上级查房' },
    { v: 5, l: '手术记录' }, { v: 6, l: '术后病程' }, { v: 7, l: '出院小结' }, { v: 8, l: '死亡记录' }, { v: 9, l: '病危通知' }
  ];

  function text(v) { return v == null ? '' : String(v); }
  function splitOpts(s) {
    return String(s || '').split(/[,，;；]/).map(function (x) { return x.trim(); }).filter(Boolean);
  }

  (function injectCss() {
    let st = document.getElementById('emr-designer-css');
    if (!st) { st = document.createElement('style'); st.id = 'emr-designer-css'; document.head.appendChild(st); }
    st.textContent = [
      '.emr-dsn { display:flex; flex-direction:column; height:100%; }',
      '.emr-dsn-body { flex:1; min-height:0; }',
      '.emr-dsn-cols { display:flex; gap:12px; height:60vh; min-height:420px; }',
      '.emr-dsn-palette { flex:none; width:150px; overflow:auto; border:1px solid var(--yb-divider,#eee); border-radius:6px; padding:8px; }',
      '.emr-dsn-palette h4 { margin:2px 0 8px; font-size:13px; color:var(--yb-ink-2); }',
      '.emr-dsn-pal-item { padding:6px 8px; margin:4px 0; border:1px dashed var(--yb-divider,#ddd); border-radius:4px; cursor:pointer; font-size:13px; background:var(--yb-surface,#fff); }',
      '.emr-dsn-pal-item:hover { border-color:var(--yb-brand,#2a6ebb); color:var(--yb-brand,#2a6ebb); }',
      '.emr-dsn-canvas { flex:1; min-width:0; overflow:auto; border:1px solid var(--yb-divider,#eee); border-radius:6px; padding:8px 12px; background:var(--yb-bg,#fafbfc); }',
      '.emr-dsn-field { position:relative; padding:8px 34px 8px 10px; margin:6px 0; border:1px solid transparent; border-radius:6px; background:var(--yb-surface,#fff); cursor:pointer; }',
      '.emr-dsn-field:hover { border-color:var(--yb-divider,#e2e6ea); }',
      '.emr-dsn-field.active { border-color:var(--yb-brand,#2a6ebb); box-shadow:0 0 0 1px var(--yb-brand,#2a6ebb) inset; }',
      '.emr-dsn-field.dragover { border-top:2px solid var(--yb-brand,#2a6ebb); }',
      '.emr-dsn-fops { position:absolute; right:6px; top:6px; display:flex; flex-direction:column; gap:2px; }',
      '.emr-dsn-fops button { border:none; background:transparent; color:var(--yb-ink-3); cursor:pointer; font-size:12px; line-height:1.2; }',
      '.emr-dsn-fops button:hover { color:var(--yb-brand,#2a6ebb); }',
      '.emr-dsn-prop { flex:none; width:260px; overflow:auto; border:1px solid var(--yb-divider,#eee); border-radius:6px; padding:10px; }',
      '.emr-dsn-prop h4 { margin:0 0 10px; font-size:13px; color:var(--yb-ink-2); }',
      '.emr-dsn-prop .emr-dsn-sub-row { display:flex; gap:4px; margin:3px 0; }',
      '.emr-dsn-empty { color:var(--yb-ink-3); text-align:center; padding:40px 0; font-size:13px; }',
      '.emr-dsn-meta { display:grid; grid-template-columns:1fr 1fr 1fr; gap:0 16px; margin-bottom:8px; }',
      '.emr-dsn-tag { font-size:11px; color:var(--yb-ink-3); margin-left:6px; }'
    ].join('');
  })();

  HIS.views.EmrTemplateDesigner = {
    name: 'EmrTemplateDesigner',
    components: { 'emr-field': (HIS.components || {}).EmrField },
    data: function () {
      return {
        loading: false,
        rows: [],
        depts: [],
        filters: { scope: 1, keyword: '' },
        fieldTypes: FIELD_TYPES,
        categories: CATEGORIES,
        dictSources: DICT_SOURCE,
        /* 设计器 */
        dsnVisible: false,
        dsnSaving: false,
        editId: null,
        meta: this.emptyMeta(),
        fields: [],
        selectedIdx: -1,
        dragIdx: -1,
        dragOverIdx: -1
      };
    },
    computed: {
      user: function () { return typeof HIS.getUser === 'function' ? (HIS.getUser() || {}) : {}; },
      isAdmin: function () {
        var u = this.user;
        return HIS.hasRole('ADMIN') || HIS.hasRole('SUPER_ADMIN') || HIS.hasRole('ORG_ADMIN')
          || text(u.role) === 'ADMIN' || text(u.role) === 'SUPER_ADMIN';
      },
      filteredRows: function () {
        var kw = text(this.filters.keyword).trim();
        if (!kw) { return this.rows; }
        return this.rows.filter(function (r) {
          return text(r.templateName).indexOf(kw) >= 0 || text(r.templateCode).indexOf(kw) >= 0;
        });
      },
      selected: function () { return this.selectedIdx >= 0 ? (this.fields[this.selectedIdx] || null) : null; }
    },
    created: function () { this.loadDepts(); this.load(); },
    methods: {
      emptyMeta: function () {
        return { templateCode: '', templateName: '', scope: 1, templateCategory: null, recordType: null, ownerScope: 'global', deptId: null, status: 1 };
      },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(function () { vm.depts = []; });
      },
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/emr/template/list?scope=' + vm.filters.scope;
        HIS.get(q).then(function (d) { vm.rows = Array.isArray(d) ? d : []; })
          .catch(function (e) { vm.rows = []; HIS.notifyError(e); })
          .finally(function () { vm.loading = false; });
      },
      onScopeChange: function () { this.load(); },
      /* ===== 归属层级(与 medical-template 同规则) ===== */
      scopeOf: function (row) {
        if (row.staffId != null && row.staffId !== '') { return 'personal'; }
        if (row.deptId != null && Number(row.deptId) !== 0) { return 'dept'; }
        return 'global';
      },
      scopeLabel: function (row) { return { personal: '个人', dept: '科室', global: '全院' }[this.scopeOf(row)]; },
      canEditRow: function (row) {
        var sc = this.scopeOf(row);
        if (sc === 'global') { return this.isAdmin && HIS.isLead && HIS.isLead(); }
        if (sc === 'personal') { return this.isAdmin || HIS.sameId(row.staffId, this.user.staffId); }
        return this.isAdmin || HIS.sameId(row.deptId, this.user.deptId);
      },
      deptName: function (id) {
        var d = this.depts.find(function (x) { return HIS.sameId(x.id, id); });
        return d ? d.deptName : (id == null || Number(id) === 0 ? '全院' : '#' + id);
      },
      catLabel: function (v) { var c = this.categories.find(function (x) { return Number(x.v) === Number(v); }); return c ? c.l : (v == null ? '-' : v); },
      scopeText: function (v) { return Number(v) === 2 ? '门诊' : '住院'; },

      /* ===== 新建/编辑: 装载设计器 ===== */
      openCreate: function () {
        this.editId = null;
        this.meta = this.emptyMeta();
        this.meta.scope = this.filters.scope;
        this.meta.ownerScope = (this.isAdmin && HIS.isLead && HIS.isLead()) ? 'global' : (this.user.deptId != null ? 'dept' : 'personal');
        this.fields = [];
        this.selectedIdx = -1;
        this.dsnVisible = true;
      },
      openEdit: function (row) {
        if (!this.canEditRow(row)) { ElementPlus.ElMessage.warning('无权限维护该范围模板'); return; }
        var vm = this;
        vm.editId = row.id;
        vm.meta = {
          templateCode: row.templateCode, templateName: row.templateName,
          scope: row.scope != null ? Number(row.scope) : 1,
          templateCategory: row.templateCategory != null ? Number(row.templateCategory) : null,
          recordType: row.recordType != null ? Number(row.recordType) : null,
          ownerScope: vm.scopeOf(row), deptId: row.deptId != null ? row.deptId : null,
          status: row.status != null ? Number(row.status) : 1
        };
        HIS.get('/api/his/emr/template/' + HIS.idParam(row.id) + '/defs').then(function (arr) {
          vm.fields = vm.parseDefs(arr);
          vm.selectedIdx = vm.fields.length ? 0 : -1;
          vm.dsnVisible = true;
        }).catch(function (e) { vm.fields = []; vm.dsnVisible = true; HIS.notifyError(e); });
      },
      parseDefs: function (arr) {
        if (!Array.isArray(arr)) { return []; }
        return arr.map(function (f) {
          var opts = Array.isArray(f.options) ? f.options : splitOpts(f.options);
          return {
            fieldKey: text(f.fieldKey), label: text(f.label) || text(f.fieldKey),
            type: text(f.type) || 'text', required: f.required === true || String(f.required) === 'true',
            maxLength: f.maxLength != null ? Number(f.maxLength) : null,
            placeholder: text(f.placeholder), _optText: opts.join(','),
            dictSource: (f.dictRef && f.dictRef.source) || '', defaultMacro: text(f.defaultMacro),
            subFields: Array.isArray(f.subFields) ? f.subFields.map(function (s) { return { fieldKey: text(s.fieldKey), label: text(s.label) }; }) : []
          };
        });
      },
      /* ===== 画布: 增删改排序 ===== */
      nextKey: function () {
        var n = 1;
        while (this.fields.some(function (f) { return f.fieldKey === ('f' + n); })) { n++; }
        return 'f' + n;
      },
      newField: function (type) {
        return {
          fieldKey: this.nextKey(), label: TYPE_NAME[type] || '字段', type: type, required: false,
          maxLength: null, placeholder: '', _optText: (type === 'radio' || type === 'checkbox') ? '选项A,选项B' : '',
          dictSource: '', defaultMacro: '', subFields: []
        };
      },
      addField: function (type) {
        this.fields.push(this.newField(type));
        this.selectedIdx = this.fields.length - 1;
      },
      removeField: function (idx) {
        this.fields.splice(idx, 1);
        if (this.selectedIdx >= this.fields.length) { this.selectedIdx = this.fields.length - 1; }
      },
      moveField: function (idx, dir) {
        var j = idx + dir;
        if (j < 0 || j >= this.fields.length) { return; }
        var tmp = this.fields[idx]; this.fields[idx] = this.fields[j]; this.fields[j] = tmp;
        if (this.selectedIdx === idx) { this.selectedIdx = j; } else if (this.selectedIdx === j) { this.selectedIdx = idx; }
      },
      selectField: function (idx) { this.selectedIdx = idx; },
      /* 拖拽排序 */
      onDragStart: function (idx) { this.dragIdx = idx; },
      onDragOver: function (idx, e) { this.dragOverIdx = idx; if (e && e.preventDefault) { e.preventDefault(); } },
      onDrop: function (idx) {
        var from = this.dragIdx;
        if (from < 0 || from === idx) { this.dragOverIdx = -1; return; }
        var moved = this.fields.splice(from, 1)[0];
        this.fields.splice(idx, 0, moved);
        this.selectedIdx = idx; this.dragIdx = -1; this.dragOverIdx = -1;
      },
      onDragEnd: function () { this.dragIdx = -1; this.dragOverIdx = -1; },
      /* 属性面板: 子表列 */
      addSubField: function () { if (this.selected) { this.selected.subFields.push({ fieldKey: 'c' + (this.selected.subFields.length + 1), label: '列' }); } },
      delSubField: function (i) { if (this.selected) { this.selected.subFields.splice(i, 1); } },
      isChoice: function (t) { return !!CHOICE[t]; },

      /* ===== 保存 ===== */
      buildRaw: function () {
        return this.fields.map(function (f) {
          var o = { fieldKey: text(f.fieldKey).trim(), label: text(f.label).trim(), type: f.type, required: !!f.required };
          if (f.placeholder) { o.placeholder = text(f.placeholder); }
          if (f.maxLength != null && Number(f.maxLength) > 0) { o.maxLength = Number(f.maxLength); }
          if (CHOICE[f.type]) {
            if (f.dictSource) { o.dictRef = { source: f.dictSource }; }
            else { var opts = splitOpts(f._optText); if (opts.length) { o.options = opts; } }
          }
          if (f.type === 'diagnosis') { o.dictRef = { source: 'diag' }; }
          if (f.type === 'catalog') { o.dictRef = { source: 'charge' }; }
          if (f.type === 'table' && Array.isArray(f.subFields) && f.subFields.length) {
            o.subFields = f.subFields.map(function (s) { return { fieldKey: text(s.fieldKey).trim(), label: text(s.label).trim() }; });
          }
          if (f.defaultMacro) { o.defaultMacro = text(f.defaultMacro).trim(); }
          return o;
        });
      },
      validate: function () {
        if (!text(this.meta.templateName).trim()) { ElementPlus.ElMessage.warning('请填写模板名称'); return false; }
        if (!this.editId && !text(this.meta.templateCode).trim()) { ElementPlus.ElMessage.warning('请填写模板编码'); return false; }
        if (!this.fields.length) { ElementPlus.ElMessage.warning('至少拖入一个字段'); return false; }
        var seen = {};
        for (var i = 0; i < this.fields.length; i++) {
          var k = text(this.fields[i].fieldKey).trim();
          if (!k) { ElementPlus.ElMessage.warning('第 ' + (i + 1) + ' 个字段编码不能为空'); return false; }
          if (seen[k]) { ElementPlus.ElMessage.warning('字段编码重复: ' + k); return false; }
          seen[k] = 1;
        }
        return true;
      },
      save: function () {
        var vm = this;
        if (!vm.validate()) { return; }
        if (vm.dsnSaving) { return; }
        var body = {
          templateCode: text(vm.meta.templateCode).trim(), templateName: text(vm.meta.templateName).trim(),
          scope: Number(vm.meta.scope), templateCategory: vm.meta.templateCategory, recordType: vm.meta.recordType,
          ownerScope: vm.meta.ownerScope, deptId: vm.meta.deptId, status: Number(vm.meta.status),
          rawFields: vm.buildRaw()
        };
        vm.dsnSaving = true;
        var p = vm.editId
          ? HIS.put('/api/his/emr/template/' + HIS.idParam(vm.editId), body)
          : HIS.post('/api/his/emr/template', body);
        p.then(function () {
          ElementPlus.ElMessage.success(vm.editId ? '模板已更新(版本自增)' : '模板已创建');
          vm.dsnVisible = false; vm.load();
        }).catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.dsnSaving = false; });
      },
      toggleStatus: function (row) {
        var vm = this;
        if (!vm.canEditRow(row)) { ElementPlus.ElMessage.warning('无权限'); return; }
        HIS.put('/api/his/emr/template/' + HIS.idParam(row.id), { status: Number(row.status) === 1 ? 0 : 1 })
          .then(function () { vm.load(); }).catch(HIS.notifyError);
      },
      removeRow: function (row) {
        var vm = this;
        if (!vm.canEditRow(row)) { ElementPlus.ElMessage.warning('无权限'); return; }
        ElementPlus.ElMessageBox.confirm('确认删除模板「' + row.templateName + '」? 已引用该模板的历史病历不受影响。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/his/emr/template/' + HIS.idParam(row.id)); })
          .then(function () { vm.load(); }).catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      },
      previewModel: function (f) {
        var m = {}; m[f.fieldKey] = (f.type === 'checkbox' || f.type === 'multiselect' || f.type === 'table') ? [] : '';
        return m;
      }
    },
    template: [
      '<div class="page-card emr-dsn">',
      '  <div class="page-title">病历模板设计器 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal">(结构化病历模板可视化自建, 全院统一引擎)</span></div>',
      '  <div class="toolbar">',
      '    <el-radio-group v-model="filters.scope" size="small" @change="onScopeChange">',
      '      <el-radio-button :label="1">住院</el-radio-button><el-radio-button :label="2">门诊</el-radio-button>',
      '    </el-radio-group>',
      '    <el-input v-model="filters.keyword" size="small" placeholder="名称/编码" clearable style="width:180px;margin-left:8px"></el-input>',
      '    <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>',
      '    <el-button size="small" type="primary" @click="openCreate">新建模板</el-button>',
      '  </div>',
      '  <el-table :data="filteredRows" height="100%" border size="small" v-loading="loading" class="emr-dsn-body">',
      '    <el-table-column type="index" label="#" width="48" align="center"></el-table-column>',
      '    <el-table-column prop="templateCode" label="编码" width="150"></el-table-column>',
      '    <el-table-column prop="templateName" label="模板名称" min-width="150"></el-table-column>',
      '    <el-table-column label="类别" width="110"><template #default="s">{{ catLabel(s.row.templateCategory) }}</template></el-table-column>',
      '    <el-table-column label="适用" width="70"><template #default="s">{{ scopeText(s.row.scope) }}</template></el-table-column>',
      '    <el-table-column label="归属" width="90"><template #default="s">{{ scopeLabel(s.row) }}<span class="emr-dsn-tag" v-if="scopeLabel(s.row)===\'科室\'">{{ deptName(s.row.deptId) }}</span></template></el-table-column>',
      '    <el-table-column prop="version" label="版本" width="60" align="center"></el-table-column>',
      '    <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag size="small" :type="Number(s.row.status)===1?\'success\':\'info\'">{{ Number(s.row.status)===1?\'启用\':\'停用\' }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="200" align="center"><template #default="s">',
      '      <el-button link type="primary" size="small" @click="openEdit(s.row)">设计</el-button>',
      '      <el-button link size="small" @click="openEdit(s.row)" :disabled="!canEditRow(s.row)">编辑</el-button>',
      '      <el-button link size="small" @click="toggleStatus(s.row)" :disabled="!canEditRow(s.row)">{{ Number(s.row.status)===1?\'停用\':\'启用\' }}</el-button>',
      '      <el-button link type="danger" size="small" @click="removeRow(s.row)" :disabled="!canEditRow(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',

      /* ===== 设计器对话框 ===== */
      '  <el-dialog v-model="dsnVisible" :title="editId?\'设计病历模板(编辑即版本自增)\':\'新建病历模板\'" width="1120px" top="6vh" append-to-body :close-on-click-modal="false">',
      '    <div class="emr-dsn-meta">',
      '      <el-form label-width="72px" size="small">',
      '        <el-form-item label="名称"><el-input v-model="meta.templateName" placeholder="模板名称"></el-input></el-form-item>',
      '        <el-form-item label="编码"><el-input v-model="meta.templateCode" :disabled="!!editId" placeholder="如 EMR_CUSTOM_01"></el-input></el-form-item>',
      '      </el-form>',
      '      <el-form label-width="72px" size="small">',
      '        <el-form-item label="适用"><el-radio-group v-model="meta.scope"><el-radio-button :label="1">住院</el-radio-button><el-radio-button :label="2">门诊</el-radio-button></el-radio-group></el-form-item>',
      '        <el-form-item label="类别"><el-select v-model="meta.templateCategory" clearable placeholder="文书类别"><el-option v-for="c in categories" :key="c.v" :label="c.l" :value="c.v"></el-option></el-select></el-form-item>',
      '      </el-form>',
      '      <el-form label-width="72px" size="small">',
      '        <el-form-item label="归属">',
      '          <el-select v-model="meta.ownerScope" :disabled="!!editId"><el-option label="个人" value="personal"></el-option><el-option label="科室" value="dept"></el-option><el-option label="全院" value="global" :disabled="!(isAdmin)"></el-option></el-select>',
      '        </el-form-item>',
      '        <el-form-item label="状态"><el-switch v-model="meta.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '      </el-form>',
      '    </div>',
      '    <div class="emr-dsn-cols">',
      /* 控件库 */
      '      <div class="emr-dsn-palette">',
      '        <h4>控件库</h4>',
      '        <div class="emr-dsn-pal-item" v-for="ft in fieldTypes" :key="ft.t" draggable="true" @dragstart="addField(ft.t)" @click="addField(ft.t)">{{ ft.n }}</div>',
      '      </div>',
      /* 画布 */
      '      <div class="emr-dsn-canvas" @dragover.prevent @drop="onDragEnd">',
      '        <div class="emr-dsn-empty" v-if="!fields.length">从左侧点击或拖入控件开始设计模板</div>',
      '        <div class="emr-dsn-field" :class="{active: selectedIdx===i, dragover: dragOverIdx===i}" v-for="(f,i) in fields" :key="i" draggable="true"',
      '             @dragstart="onDragStart(i)" @dragover="onDragOver(i,$event)" @drop.stop="onDrop(i)" @dragend="onDragEnd" @click="selectField(i)">',
      '          <div class="emr-dsn-fops">',
      '            <button title="上移" @click.stop="moveField(i,-1)">▲</button>',
      '            <button title="下移" @click.stop="moveField(i,1)">▼</button>',
      '            <button title="删除" @click.stop="removeField(i)">✕</button>',
      '          </div>',
      '          <emr-field :field="f" :model="previewModel(f)" :readonly="true"></emr-field>',
      '        </div>',
      '      </div>',
      /* 属性面板 */
      '      <div class="emr-dsn-prop">',
      '        <h4>属性</h4>',
      '        <div class="emr-dsn-empty" v-if="!selected">选中画布中的字段进行配置</div>',
      '        <el-form v-else label-position="top" size="small">',
      '          <el-form-item label="字段标签"><el-input v-model="selected.label"></el-input></el-form-item>',
      '          <el-form-item label="字段编码"><el-input v-model="selected.fieldKey" placeholder="英文/拼音唯一键"></el-input></el-form-item>',
      '          <el-form-item label="控件类型"><el-select v-model="selected.type" style="width:100%"><el-option v-for="ft in fieldTypes" :key="ft.t" :label="ft.n" :value="ft.t"></el-option></el-select></el-form-item>',
      '          <el-form-item label="必填"><el-switch v-model="selected.required"></el-switch></el-form-item>',
      '          <template v-if="selected.type!==\'section\' && selected.type!==\'signature\' && selected.type!==\'vitals\'">',
      '            <el-form-item label="占位提示"><el-input v-model="selected.placeholder"></el-input></el-form-item>',
      '          </template>',
      '          <el-form-item v-if="[\'text\',\'textarea\'].indexOf(selected.type)>=0" label="最大长度"><el-input-number v-model="selected.maxLength" :min="0" controls-position="right" style="width:120px"></el-input-number></el-form-item>',
      '          <template v-if="isChoice(selected.type)">',
      '            <el-form-item label="值域来源"><el-select v-model="selected.dictSource" style="width:100%" placeholder="无则手工选项"><el-option v-for="ds in dictSources" :key="ds.v" :label="ds.l" :value="ds.v"></el-option></el-select></el-form-item>',
      '            <el-form-item v-if="!selected.dictSource" label="选项(逗号分隔)"><el-input v-model="selected._optText" type="textarea" :rows="3" placeholder="选项A,选项B"></el-input></el-form-item>',
      '          </template>',
      '          <template v-if="selected.type===\'table\'">',
      '            <el-form-item label="子表列">',
      '              <div style="width:100%">',
      '                <div class="emr-dsn-sub-row" v-for="(sf,si) in selected.subFields" :key="si">',
      '                  <el-input v-model="sf.label" placeholder="列名" style="flex:1"></el-input>',
      '                  <el-input v-model="sf.fieldKey" placeholder="编码" style="width:78px"></el-input>',
      '                  <el-button size="small" @click="delSubField(si)">删</el-button>',
      '                </div>',
      '                <el-button size="small" @click="addSubField" style="margin-top:4px">+ 加列</el-button>',
      '              </div>',
      '            </el-form-item>',
      '          </template>',
      '          <el-form-item label="默认宏(可选)"><el-input v-model="selected.defaultMacro" placeholder="宏编码, 建档时自动取值"></el-input></el-form-item>',
      '        </el-form>',
      '      </div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button size="small" @click="dsnVisible=false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="dsnSaving" @click="save">保存模板</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('')
  };
})();
