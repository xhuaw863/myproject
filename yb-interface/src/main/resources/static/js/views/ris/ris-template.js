/* RIS 报告模板管理(RisTemplate) — his_ris_report_template + his_ris_report_element。
 * 后端 /api/ris/admin:
 *   GET  /templates              模板列表(模态/科室类型筛选, 启用模板 + org_id=0 全局种子并集)
 *   GET  /template/{id}          模板详情 {template, elements}(四段模板 + 挂载数据元)
 *   POST /template               新增(数据元随模板整体保存)
 *   PUT  /template/{id}          更新(编码不可变更, 数据元非空时整体替换)
 *   DEL  /template/{id}          逻辑删除(挂数据元一并软删)
 * 注册: HIS.views.RisTemplate; 左列表右详情布局; 类前缀 rt-。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var MODALITIES = [
    { v: 'ALL', l: '全部模态 ALL' }, { v: 'CT', l: 'CT' }, { v: 'MR', l: 'MR' },
    { v: 'DR', l: 'DR' }, { v: 'US', l: 'US' }, { v: 'ES', l: 'ES' }
  ];
  var DEPT_TYPES = [
    { v: 'RADIOLOGY', l: '放射 RADIOLOGY' }, { v: 'ULTRASOUND', l: '超声 ULTRASOUND' }, { v: 'ENDOSCOPY', l: '内镜 ENDOSCOPY' }
  ];
  var LEVELS = [
    { v: 1, l: '全院', t: 'primary' }, { v: 2, l: '科室', t: 'success' }, { v: 3, l: '个人', t: 'warning' }
  ];
  var ELEMENT_TYPES = [
    { v: 'TEXT', l: '文本' }, { v: 'NUMBER', l: '数值' }, { v: 'SELECT', l: '下拉' },
    { v: 'MULTISELECT', l: '多选' }, { v: 'RADIO', l: '单选' }, { v: 'MEASUREMENT', l: '测量' }
  ];
  var SECTIONS = [
    { v: 'FINDINGS', l: '所见段' }, { v: 'CONCLUSION', l: '结论段' }, { v: 'TECHNIQUE', l: '技术段' }
  ];
  var TPL_SECTIONS = [
    { key: 'findingsTemplate', label: '所见模板' }, { key: 'conclusionTemplate', label: '结论模板' },
    { key: 'impressionTemplate', label: '印象模板' }, { key: 'techniqueTemplate', label: '检查技术' }
  ];

  function labelOf(list, v, dft) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].l; } }
    return (v == null || v === '') ? (dft || '-') : String(v);
  }
  function levelMeta(v) {
    for (var i = 0; i < LEVELS.length; i++) { if (LEVELS[i].v === Number(v)) { return LEVELS[i]; } }
    return { v: v, l: v == null ? '-' : String(v), t: 'info' };
  }
  function typeTag(v) {
    for (var i = 0; i < ELEMENT_TYPES.length; i++) { if (ELEMENT_TYPES[i].v === v) { return ELEMENT_TYPES[i].l; } }
    return v == null ? '-' : String(v);
  }
  function sectionLabel(v) { return labelOf(SECTIONS, v); }
  /* 四段模板内容为结构化 JSON/文本: 可解析则美化展示 */
  function prettyTpl(s) {
    if (s == null || String(s).trim() === '') { return ''; }
    try {
      var o = JSON.parse(s);
      return (o && typeof o === 'object') ? JSON.stringify(o, null, 2) : String(s);
    } catch (e) { return String(s); }
  }

  (function ensureRtStyles() {
    if (document.getElementById('ris-template-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-template-style';
    st.textContent = [
      '.rt-main { display: flex; gap: 14px; flex: 1; min-height: 0; }',
      '.rt-left { width: 47%; display: flex; flex-direction: column; min-width: 0; }',
      '.rt-tbl { flex: 1; min-height: 0; }',
      '.rt-right { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow-y: auto; }',
      '.rt-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }',
      '.rt-head .rt-head-name { font-size: 17px; font-weight: 700; color: var(--yb-ink-1); }',
      '.rt-code { font-family: var(--yb-font-mono); font-size: 12px; color: var(--yb-ink-3); }',
      '.rt-sec { margin: 14px 0 8px; padding: 7px 0 6px; border-bottom: 1px dashed var(--yb-border);',
      '  font-size: 12px; font-weight: 600; color: var(--yb-ink-2); letter-spacing: .04em;',
      '  display: flex; align-items: center; }',
      '.rt-sec .rt-sec-dot { display: inline-block; width: 7px; height: 7px; border-radius: 2px;',
      '  background: var(--yb-brand); margin-right: 7px; }',
      '.rt-sec .rt-sec-ext { margin-left: auto; font-weight: 400; }',
      '.rt-tpl-text { margin: 0 0 4px; padding: 10px 12px; background: var(--yb-canvas);',
      '  border: 1px solid var(--yb-border-light); border-radius: 8px; font-family: var(--yb-font-mono);',
      '  font-size: 12px; line-height: 1.6; color: var(--yb-ink-2); white-space: pre-wrap; word-break: break-word; }',
      '.rt-el-card { border: 1px dashed var(--yb-border); border-radius: 8px; padding: 10px 12px 4px;',
      '  margin-bottom: 8px; background: var(--yb-canvas); position: relative; }',
      '.rt-el-grid { display: grid; grid-template-columns: repeat(12, 1fr); gap: 6px 8px; }',
      '.rt-el-grid .rt-span3 { grid-column: span 3; }',
      '.rt-el-grid .rt-span4 { grid-column: span 4; }',
      '.rt-el-grid .rt-span6 { grid-column: span 6; }',
      '.rt-el-grid .rt-span8 { grid-column: span 8; }',
      '.rt-el-grid .rt-span2 { grid-column: span 2; }',
      '.rt-el-del { position: absolute; top: 6px; right: 8px; }',
      '.rt-tip { color: var(--yb-ink-4); font-size: 12px; line-height: 1.6; }',
      '.rt-dlg .el-dialog__body { max-height: 64vh; overflow-y: auto; padding-top: 6px; }',
      '.rt-empty { color: var(--yb-ink-4); font-weight: 400; margin-left: 8px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  HIS.views.RisTemplate = {
    name: 'RisTemplate',
    data: function () {
      return {
        modality: null, deptType: null,
        list: [], loading: false,
        current: null, detailLoading: false,
        depts: [], doctors: [],
        modalityOpts: MODALITIES, deptTypeOpts: DEPT_TYPES, levelOpts: LEVELS,
        elementTypeOpts: ELEMENT_TYPES, sectionOpts: SECTIONS, secDefs: TPL_SECTIONS,
        dlg: { visible: false, saving: false, editing: false, editingId: null, form: {}, els: [] }
      };
    },
    created: function () {
      this.loadDepts();
      this.loadDoctors();
      this.fetch();
    },
    methods: {
      levelLabel: function (v) { return levelMeta(v).l; },
      levelTag: function (v) { return levelMeta(v).t; },
      modalityLabel: function (v) { return labelOf(MODALITIES, v); },
      deptTypeLabel: function (v) { return labelOf(DEPT_TYPES, v); },
      typeLabel: typeTag,
      sectionLabel: sectionLabel,
      prettyText: prettyTpl,
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.depts = l || []; }).catch(function () { vm.depts = []; });
      },
      loadDoctors: function () {
        var vm = this;
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&withSubOrgs=false')
          .then(function (l) { vm.doctors = l || []; }).catch(function () { vm.doctors = []; });
      },
      search: function () { this.fetch(); },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/ris/admin/templates';
        var parts = [];
        if (vm.modality) { parts.push('modality=' + encodeURIComponent(vm.modality)); }
        if (vm.deptType) { parts.push('deptType=' + encodeURIComponent(vm.deptType)); }
        if (parts.length) { q += '?' + parts.join('&'); }
        HIS.get(q).then(function (l) {
          vm.list = l || [];
          /* 筛选后当前选中项可能已不在列表, 同步失效 */
          if (vm.current) {
            var still = vm.list.some(function (t) { return HIS.sameId(t.id, vm.current.id); });
            if (!still) { vm.current = null; }
          }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      selectRow: function (row) {
        var vm = this; vm.detailLoading = true;
        HIS.get('/api/ris/admin/template/' + encodeURIComponent(HIS.id(row && row.id)))
          .then(function (d) {
            vm.current = {
              id: HIS.id(row && row.id), template: d && d.template, elements: (d && d.elements) || []
            };
          }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      tpl: function () { return (this.current && this.current.template) || {}; },
      emptyForm: function () {
        return {
          templateCode: '', templateName: '', modality: 'ALL', bodyPart: '',
          deptType: 'RADIOLOGY', templateLevel: 1, ownerDeptId: null, ownerStaffId: null,
          findingsTemplate: '', conclusionTemplate: '', impressionTemplate: '', techniqueTemplate: '',
          normalFlag: 0, sortOrder: 0, status: 1
        };
      },
      emptyElement: function () {
        return {
          id: null, elementCode: '', elementName: '', elementType: 'TEXT', section: 'FINDINGS',
          valueUnit: '', valueOptions: '', defaultValue: '', normalRange: '', sortOrder: 0, required: 1
        };
      },
      openAdd: function () {
        this.dlg = {
          visible: true, saving: false, editing: false, editingId: null,
          form: this.emptyForm(), els: [this.emptyElement()]
        };
      },
      openEdit: function (row) {
        var vm = this;
        HIS.get('/api/ris/admin/template/' + encodeURIComponent(HIS.id(row && row.id))).then(function (d) {
          var t = (d && d.template) || {};
          var els = (d && d.elements) || [];
          vm.dlg = {
            visible: true, saving: false, editing: true, editingId: HIS.id(row && row.id),
            form: {
              templateCode: String(t.templateCode || ''), templateName: t.templateName || '',
              modality: t.modality || 'ALL', bodyPart: t.bodyPart || '',
              deptType: t.deptType || 'RADIOLOGY', templateLevel: Number(t.templateLevel) || 1,
              ownerDeptId: t.ownerDeptId == null ? null : String(t.ownerDeptId),
              ownerStaffId: t.ownerStaffId == null ? null : String(t.ownerStaffId),
              findingsTemplate: t.findingsTemplate || '', conclusionTemplate: t.conclusionTemplate || '',
              impressionTemplate: t.impressionTemplate || '', techniqueTemplate: t.techniqueTemplate || '',
              normalFlag: Number(t.normalFlag) || 0, sortOrder: Number(t.sortOrder) || 0,
              status: Number(t.status) || 1
            },
            els: els.map(function (e) {
              return {
                id: e.id == null ? null : String(e.id),
                elementCode: e.elementCode || '', elementName: e.elementName || '',
                elementType: e.elementType || 'TEXT', section: e.section || 'FINDINGS',
                valueUnit: e.valueUnit || '', valueOptions: e.valueOptions || '',
                defaultValue: e.defaultValue || '', normalRange: e.normalRange || '',
                sortOrder: Number(e.sortOrder) || 0, required: Number(e.required) || 0
              };
            })
          };
        }).catch(HIS.notifyError);
      },
      addEl: function () { this.dlg.els.push(this.emptyElement()); },
      delEl: function (i) { this.dlg.els.splice(i, 1); },
      submit: function () {
        var vm = this;
        var f = vm.dlg.form;
        if (!f.templateName || !String(f.templateName).trim()) { ElementPlus.ElMessage.warning('请填写模板名称'); return; }
        if (!f.modality) { ElementPlus.ElMessage.warning('请选择适用模态'); return; }
        if (!f.deptType) { ElementPlus.ElMessage.warning('请选择适用科室类型'); return; }
        if (Number(f.templateLevel) === 2 && !f.ownerDeptId) { ElementPlus.ElMessage.warning('科室级模板须选择归属科室'); return; }
        if (Number(f.templateLevel) === 3 && !f.ownerStaffId) { ElementPlus.ElMessage.warning('个人级模板须选择归属医生'); return; }
        for (var i = 0; i < vm.dlg.els.length; i++) {
          var e = vm.dlg.els[i];
          if (!String(e.elementCode || '').trim() || !String(e.elementName || '').trim()) {
            ElementPlus.ElMessage.warning('数据元第 ' + (i + 1) + ' 行的编码与名称为必填');
            return;
          }
          var vo = String(e.valueOptions || '').trim();
          if (vo) {
            try {
              var parsed = JSON.parse(vo);
              if (!Array.isArray(parsed)) { throw new Error('not array'); }
            } catch (err) {
              ElementPlus.ElMessage.warning('数据元第 ' + (i + 1) + ' 行候选值须为 JSON 数组, 如 ["正常","增大"]');
              return;
            }
          }
        }
        var body = {
          templateName: String(f.templateName).trim(),
          modality: f.modality, deptType: f.deptType,
          bodyPart: f.bodyPart || null,
          templateLevel: Number(f.templateLevel) || 1,
          ownerDeptId: Number(f.templateLevel) === 2 ? (f.ownerDeptId || null) : null,
          ownerStaffId: Number(f.templateLevel) === 3 ? (f.ownerStaffId || null) : null,
          findingsTemplate: f.findingsTemplate || null,
          conclusionTemplate: f.conclusionTemplate || null,
          impressionTemplate: f.impressionTemplate || null,
          techniqueTemplate: f.techniqueTemplate || null,
          normalFlag: Number(f.normalFlag) || 0,
          sortOrder: Number(f.sortOrder) || 0,
          status: Number(f.status) || 1,
          elements: vm.dlg.els.map(function (e) {
            return {
              id: e.id || null,
              elementCode: String(e.elementCode || '').trim(),
              elementName: String(e.elementName || '').trim(),
              elementType: e.elementType || 'TEXT',
              section: e.section || 'FINDINGS',
              valueUnit: e.valueUnit || null,
              valueOptions: e.valueOptions || null,
              defaultValue: e.defaultValue || null,
              normalRange: e.normalRange || null,
              sortOrder: Number(e.sortOrder) || 0,
              required: Number(e.required) || 0
            };
          })
        };
        vm.dlg.saving = true;
        var p = vm.dlg.editing
          ? HIS.put('/api/ris/admin/template/' + encodeURIComponent(vm.dlg.editingId), body)
          : HIS.post('/api/ris/admin/template', body);
        p.then(function () {
          HIS.notifySuccess(vm.dlg.editing ? '模板已更新' : '模板已新增');
          vm.dlg.visible = false;
          vm.fetch();
          /* 刚编辑的即选中刷新详情 */
          if (vm.dlg.editing && vm.current && HIS.sameId(vm.current.id, vm.dlg.editingId)) {
            vm.selectRow({ id: vm.dlg.editingId });
          }
        }).catch(HIS.notifyError).finally(function () { vm.dlg.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认删除模板「' + row.templateName + '(' + row.templateCode + ')」? 逻辑删除, 挂载的结构化数据元一并软删。',
          '删除模板', { type: 'warning' }
        ).then(function () {
          return HIS.del('/api/ris/admin/template/' + encodeURIComponent(HIS.id(row && row.id)));
        }).then(function () {
          HIS.notifySuccess('已删除');
          if (vm.current && HIS.sameId(vm.current.id, HIS.id(row && row.id))) { vm.current = null; }
          vm.fetch();
        }).catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">报告模板管理',
      '    <span style="font-size:12px;color:var(--yb-ink-3);font-weight:normal;">(his_ris_report_template + element · 所见/结论/印象/技术四段 + FINDINGS/CONCLUSION/TECHNIQUE 结构化数据元)</span>',
      '  </div>',
      '  <div class="rt-main">',
      '    <div class="rt-left">',
      '      <div class="toolbar">',
      '        <el-select v-model="modality" placeholder="适用模态" clearable style="width:150px" @change="search">',
      '          <el-option v-for="m in modalityOpts" :key="m.v" :label="m.l" :value="m.v"></el-option>',
      '        </el-select>',
      '        <el-select v-model="deptType" placeholder="科室类型" clearable style="width:170px" @change="search">',
      '          <el-option v-for="d in deptTypeOpts" :key="d.v" :label="d.l" :value="d.v"></el-option>',
      '        </el-select>',
      '        <el-button type="primary" @click="fetch">刷新</el-button>',
      '        <span style="flex:1;"></span>',
      '        <el-button type="success" @click="openAdd">新增模板</el-button>',
      '        <span class="rt-tip">共 {{ list.length }} 个</span>',
      '      </div>',
      '      <div class="rt-tbl">',
      '        <el-table :data="list" v-loading="loading" border stripe size="small" height="100%" highlight-current-row @row-click="selectRow" style="cursor:pointer;">',
      '          <el-table-column prop="templateCode" label="模板编码" width="140"><template #default="s"><span class="rt-code">{{ s.row.templateCode }}</span></template></el-table-column>',
      '          <el-table-column prop="templateName" label="模板名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="适用模态" width="84" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ modalityLabel(s.row.modality) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="适用部位" width="110" show-overflow-tooltip><template #default="s">{{ s.row.bodyPart || \'通用\' }}</template></el-table-column>',
      '          <el-table-column label="科室类型" width="120" show-overflow-tooltip><template #default="s">{{ deptTypeLabel(s.row.deptType) }}</template></el-table-column>',
      '          <el-table-column label="级别" width="76" align="center"><template #default="s"><el-tag size="small" :type="levelTag(s.row.templateLevel)">{{ levelLabel(s.row.templateLevel) }}</el-tag></template></el-table-column>',
      '          <el-table-column prop="useCount" label="使用次数" width="84" align="center"><template #default="s">{{ s.row.useCount == null ? 0 : s.row.useCount }}</template></el-table-column>',
      '          <el-table-column label="状态" width="72" align="center"><template #default="s">',
      '            <el-tag size="small" :type="Number(s.row.status)===1 ? \'success\' : \'info\'">{{ Number(s.row.status)===1 ? \'启用\' : \'停用\' }}</el-tag>',
      '          </template></el-table-column>',
      '          <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '            <el-button link type="primary" @click.stop="selectRow(s.row)">详情</el-button>',
      '            <el-button link type="primary" @click.stop="openEdit(s.row)">编辑</el-button>',
      '            <el-button link type="danger" @click.stop="del(s.row)">删除</el-button>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </div>',
      '    </div>',

      '    <div class="rt-right" v-loading="detailLoading">',
      '      <div v-if="!current" class="placeholder">',
      '        <div class="big">&#128196;</div>',
      '        在左侧选择模板查看详情: 四段模板内容 + 结构化数据元定义',
      '      </div>',
      '      <template v-else>',
      '        <div class="rt-head">',
      '          <span class="rt-head-name">{{ tpl().templateName }}</span>',
      '          <span class="rt-code">{{ tpl().templateCode }}</span>',
      '          <el-tag size="small" :type="levelTag(tpl().templateLevel)">{{ levelLabel(tpl().templateLevel) }}</el-tag>',
      '          <el-tag size="small" effect="plain">{{ modalityLabel(tpl().modality) }}</el-tag>',
      '          <el-tag size="small" effect="plain" type="info">{{ deptTypeLabel(tpl().deptType) }}</el-tag>',
      '          <el-tag v-if="Number(tpl().normalFlag)===1" size="small" type="success">正常模板</el-tag>',
      '          <el-tag size="small" :type="Number(tpl().status)===1 ? \'success\' : \'info\'">{{ Number(tpl().status)===1 ? \'启用\' : \'停用\' }}</el-tag>',
      '        </div>',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="适用部位">{{ tpl().bodyPart || \'通用\' }}</el-descriptions-item>',
      '          <el-descriptions-item label="使用次数">{{ tpl().useCount == null ? 0 : tpl().useCount }}</el-descriptions-item>',
      '          <el-descriptions-item label="排序">{{ tpl().sortOrder == null ? 0 : tpl().sortOrder }}</el-descriptions-item>',
      '          <el-descriptions-item label="归属">{{ levelLabel(tpl().templateLevel) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div class="rt-sec" v-for="sec in secDefs" :key="sec.key"><span class="rt-sec-dot"></span>{{ sec.label }}',
      '          <span v-if="!prettyText(tpl()[sec.key])" class="rt-empty">未设置</span>',
      '        </div>',
      '        <pre v-if="prettyText(tpl()[sec.key])" class="rt-tpl-text">{{ prettyText(tpl()[sec.key]) }}</pre>',
      '        <div class="rt-sec"><span class="rt-sec-dot"></span>结构化数据元',
      '          <span class="rt-sec-ext">共 {{ current.elements.length }} 项</span>',
      '        </div>',
      '        <el-table :data="current.elements" border stripe size="small" max-height="360">',
      '          <el-table-column type="index" label="#" width="46"></el-table-column>',
      '          <el-table-column prop="elementCode" label="数据元编码" width="130"><template #default="s"><span class="rt-code">{{ s.row.elementCode }}</span></template></el-table-column>',
      '          <el-table-column prop="elementName" label="名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="类型" width="86" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ typeLabel(s.row.elementType) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="所属段" width="96" align="center"><template #default="s">{{ sectionLabel(s.row.section) }}</template></el-table-column>',
      '          <el-table-column prop="valueUnit" label="单位" width="70" align="center"><template #default="s">{{ s.row.valueUnit || \'-\' }}</template></el-table-column>',
      '          <el-table-column prop="normalRange" label="正常范围" width="100" show-overflow-tooltip><template #default="s">{{ s.row.normalRange || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="必填" width="66" align="center"><template #default="s">',
      '            <el-tag v-if="Number(s.row.required)===1" size="small" type="danger">必填</el-tag>',
      '            <span v-else class="rt-empty">选填</span>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </template>',
      '    </div>',
      '  </div>',

      '  <el-dialog v-model="dlg.visible" :title="dlg.editing ? \'编辑报告模板\' : \'新增报告模板\'" width="760px" top="5vh" class="rt-dlg" append-to-body>',
      '    <el-form :model="dlg.form" label-width="92px" size="small">',
      '      <div class="rt-sec"><span class="rt-sec-dot"></span>基础信息</div>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="模板编码" required>',
      '          <el-input v-model="dlg.form.templateCode" :disabled="dlg.editing" placeholder="留空自动生成"></el-input>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="模板名称" required>',
      '          <el-input v-model="dlg.form.templateName" placeholder="如 胸部CT平扫正常模板"></el-input>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="适用模态" required>',
      '          <el-select v-model="dlg.form.modality" style="width:100%">',
      '            <el-option v-for="m in modalityOpts" :key="m.v" :label="m.l" :value="m.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="科室类型" required>',
      '          <el-select v-model="dlg.form.deptType" style="width:100%">',
      '            <el-option v-for="d in deptTypeOpts" :key="d.v" :label="d.l" :value="d.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="适用部位">',
      '          <el-input v-model="dlg.form.bodyPart" placeholder="空=通用"></el-input>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="级别" required>',
      '          <el-select v-model="dlg.form.templateLevel" style="width:100%">',
      '            <el-option v-for="l in levelOpts" :key="l.v" :label="l.l" :value="l.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col v-if="Number(dlg.form.templateLevel)===2" :span="8"><el-form-item label="归属科室" required>',
      '          <el-select v-model="dlg.form.ownerDeptId" filterable clearable placeholder="选择科室" style="width:100%">',
      '            <el-option v-for="d in depts" :key="String(d.id)" :label="d.deptName" :value="String(d.id)"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col v-if="Number(dlg.form.templateLevel)===3" :span="8"><el-form-item label="归属医生" required>',
      '          <el-select v-model="dlg.form.ownerStaffId" filterable clearable placeholder="选择医生" style="width:100%">',
      '            <el-option v-for="d in doctors" :key="String(d.id)" :label="d.staffName" :value="String(d.id)"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="4"><el-form-item label="排序" label-width="56px">',
      '          <el-input-number v-model="dlg.form.sortOrder" :min="0" :max="999" controls-position="right" style="width:100%"></el-input-number>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="状态">',
      '          <el-switch v-model="dlg.form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="正常模板">',
      '          <el-switch v-model="dlg.form.normalFlag" :active-value="1" :inactive-value="0"></el-switch>',
      '          <span class="rt-tip">&nbsp;正常模板供一键引用正常所见/结论</span>',
      '        </el-form-item></el-col>',
      '      </el-row>',

      '      <div class="rt-sec"><span class="rt-sec-dot"></span>四段模板内容<span class="rt-sec-ext">所见 / 结论 / 印象 / 检查技术</span></div>',
      '      <el-form-item label="所见模板" label-width="92px">',
      '        <el-input v-model="dlg.form.findingsTemplate" type="textarea" :rows="4" placeholder="结构化 JSON 或文本, 如 影像所见:双肺纹理清晰..."></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="结论模板" label-width="92px">',
      '        <el-input v-model="dlg.form.conclusionTemplate" type="textarea" :rows="3" placeholder="结构化 JSON 或文本"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="印象模板" label-width="92px">',
      '        <el-input v-model="dlg.form.impressionTemplate" type="textarea" :rows="2" placeholder="结构化 JSON 或文本"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="检查技术" label-width="92px">',
      '        <el-input v-model="dlg.form.techniqueTemplate" type="textarea" :rows="2" placeholder="如 患者仰卧位, 层厚5mm, 平扫"></el-input>',
      '      </el-form-item>',

      '      <div class="rt-sec"><span class="rt-sec-dot"></span>结构化数据元定义',
      '        <span class="rt-sec-ext"><el-button size="small" type="primary" plain @click="addEl">+ 添加数据元</el-button></span>',
      '      </div>',
      '      <div class="rt-tip" style="margin:4px 0 10px;">随模板整体保存(非空时整体替换); 候选值须为 JSON 数组, 如 ["正常","增大","缩小"]。</div>',
      '      <div v-if="!dlg.els.length" class="rt-tip" style="text-align:center;padding:18px 0;">暂无数据元, 点击右上角「添加数据元」</div>',
      '      <div v-for="(e, i) in dlg.els" :key="i" class="rt-el-card">',
      '        <el-button class="rt-el-del" link type="danger" size="small" @click="delEl(i)">删除</el-button>',
      '        <div class="rt-el-grid">',
      '          <div class="rt-span3"><el-input v-model="e.elementCode" size="small" placeholder="数据元编码*"><template #prepend>#{{ i + 1 }}</template></el-input></div>',
      '          <div class="rt-span4"><el-input v-model="e.elementName" size="small" placeholder="数据元名称*"></el-input></div>',
      '          <div class="rt-span3"><el-select v-model="e.elementType" size="small">',
      '            <el-option v-for="t in elementTypeOpts" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '          </el-select></div>',
      '          <div class="rt-span2"><el-input-number v-model="e.sortOrder" size="small" :min="0" :max="999" controls-position="right" style="width:100%"></el-input-number></div>',
      '          <div class="rt-span3"><el-select v-model="e.section" size="small">',
      '            <el-option v-for="sc in sectionOpts" :key="sc.v" :label="sc.l" :value="sc.v"></el-option>',
      '          </el-select></div>',
      '          <div class="rt-span3"><el-input v-model="e.valueUnit" size="small" placeholder="单位"></el-input></div>',
      '          <div class="rt-span3" style="display:flex;align-items:center;">',
      '            <span class="rt-tip">必填</span>&nbsp;<el-switch v-model="e.required" :active-value="1" :inactive-value="0" size="small"></el-switch>',
      '          </div>',
      '          <div class="rt-span3"><el-input v-model="e.normalRange" size="small" placeholder="正常范围"></el-input></div>',
      '          <div class="rt-span6"><el-input v-model="e.valueOptions" size="small" placeholder=\'候选值 JSON 数组, 如 ["正常","增大"]\'></el-input></div>',
      '          <div class="rt-span3"><el-input v-model="e.defaultValue" size="small" placeholder="默认值"></el-input></div>',
      '        </div>',
      '      </div>',
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
