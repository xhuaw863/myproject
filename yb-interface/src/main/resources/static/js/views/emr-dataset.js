/* 病历数据集管理: HIS.views['emr-dataset'] —— 病历数据集("章节→小节→数据元"三级结构)维护页。
 * 左栏: 数据集分页列表(检索/新增/编辑/克隆/删除); 右栏: 选中数据集的章节树编辑(章节/小节/数据元增删改与排序)。
 * 后端契约(/api/his/emr/dataset, EmrDatasetController/EmrDatasetService, P1a-2 已实现):
 *   GET  /list?keyword=&scope=&page=&size=   分页列表(按 scope→id 升序; scope: 0全部 1住院 2门诊 3护理)
 *   GET  /{id}                               详情: {dataset, chapters:[{chapterKey,chapterName,elements,sections:[{sectionKey,sectionName,elements}]}]}
 *   POST /save                               新建(id空)/更新数据集 {id?, code, name, scope, description, status}
 *   DELETE /{id}                             删除数据集(级联逻辑删除其数据元)
 *   POST /element/save                       新建(id空)/更新数据元(update 语义: 字段为 null 不改, '' 清空可空字段)
 *   DELETE /element/{id}                     删除单个数据元
 *   POST /element/reorder                    [{id, sortNo}] 批量变更排序号
 *   POST /{id}/clone                         {newCode?, newName?} 深拷贝(留空自动 源编码_COPY / 原名(副本))
 * 语义要点: 章节/小节无独立实体, 均从数据元的 chapterKey/sectionKey 归组派生 —— 故"新增章节/小节"以本地草稿承载,
 *   其下首个数据元保存后自动落库(loadTree 后 prune 掉已实体化草稿); "编辑/删除章节/小节"按组批量改写/删除其下数据元。
 *   写操作服务端 guard.requireLeadOrg(仅牵头机构管理员), 前端按 HIS.isLead() 预禁用按钮; 读为租户级共享主数据(不按机构过滤)。
 * 注册: HIS.views['emr-dataset'](与后续动态菜单 comp 同值; 须在 app.js 之前加载, index.html 引入)。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 适用范围(DDL 口径: 0全部 1住院 2门诊 3护理) */
  var SCOPE_OPTS = [
    { v: 0, l: '全部(通用)', t: 'info' },
    { v: 1, l: '住院', t: 'primary' },
    { v: 2, l: '门诊', t: 'success' },
    { v: 3, l: '护理', t: 'warning' }
  ];
  /* 数据元类型(实体口径: text/number/date/datetime/select/multiselect/checkbox/dict/textarea) */
  var FIELD_TYPES = [
    { t: 'text', n: '文本' }, { t: 'textarea', n: '多行文本' }, { t: 'number', n: '数字' },
    { t: 'date', n: '日期' }, { t: 'datetime', n: '日期时间' },
    { t: 'select', n: '下拉单选' }, { t: 'multiselect', n: '下拉多选' },
    { t: 'checkbox', n: '复选框组' }, { t: 'dict', n: '字典项' }
  ];
  var DICT_TYPES = { select: 1, multiselect: 1, checkbox: 1, dict: 1 };
  var LID = 0;
  function nextLid() { return 'lid' + (++LID); }
  function text(v) { return v == null ? '' : String(v); }

  /* 组件私有样式: 左右分栏 + 章节/小节/数据元树(不动全局 his.css) */
  (function injectCss() {
    var st = document.getElementById('emr-dataset-css');
    if (!st) { st = document.createElement('style'); st.id = 'emr-dataset-css'; document.head.appendChild(st); }
    st.textContent = [
      '.eds-split { flex:1; min-height:0; display:flex; gap:12px; }',
      '.eds-left { flex:none; width:36%; min-width:350px; display:flex; flex-direction:column; min-height:0; }',
      '.eds-left .eds-tools { flex:none; flex-wrap:wrap; gap:8px; margin-bottom:8px; }',
      '.eds-left .eds-tools .el-button+.el-button { margin-left:0; }',
      '.eds-left .eds-table { flex:1; min-height:0; }',
      '.eds-left .eds-pager { flex:none; display:flex; justify-content:flex-end; margin-top:10px; }',
      '.eds-left .el-table .eds-row-on > td { background:var(--yb-brand-subtle,#eaf1f8) !important; }',
      '.eds-right { flex:1; min-width:0; display:flex; flex-direction:column; min-height:0; border:1px solid var(--yb-border,#dfe4eb); border-radius:var(--yb-r-md,8px); padding:10px 12px; }',
      '.eds-head { flex:none; padding-bottom:8px; border-bottom:1px solid var(--yb-divider,#f0f3f7); margin-bottom:8px; }',
      '.eds-head-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.eds-head-sub { color:var(--yb-ink-3,#5a6a7e); font-size:12px; margin-top:6px; }',
      '.eds-ds-name { font-size:15px; font-weight:600; color:var(--yb-ink-1,#1c2430); }',
      '.eds-key { font-size:12px; color:var(--yb-ink-3,#5a6a7e); background:var(--yb-surface-2,#f7f9fc); border:1px solid var(--yb-border,#dfe4eb); border-radius:4px; padding:0 6px; line-height:18px; }',
      '.eds-grow { flex:1; }',
      '.eds-tree { flex:1; min-height:0; overflow:auto; }',
      '.eds-empty { color:var(--yb-ink-4,#8994a5); text-align:center; padding:40px 0; font-size:13px; }',
      '.eds-empty-main { height:100%; display:flex; align-items:center; justify-content:center; }',
      '.eds-empty-inline { color:var(--yb-ink-4,#8994a5); font-size:12px; padding:6px 10px; }',
      '.eds-ch { border:1px solid var(--yb-border,#dfe4eb); border-radius:var(--yb-r-md,8px); margin-bottom:10px; overflow:hidden; }',
      '.eds-ch-hd { display:flex; align-items:center; gap:8px; padding:6px 10px; background:var(--yb-surface-2,#f7f9fc); cursor:pointer; }',
      '.eds-ch-hd:hover { background:var(--yb-surface-3,#eef2f7); }',
      '.eds-caret { color:var(--yb-ink-3,#5a6a7e); width:14px; text-align:center; }',
      '.eds-ch-name { font-weight:600; color:var(--yb-ink-1,#1c2430); font-size:13px; }',
      '.eds-ch-body { padding:6px 10px 8px 26px; }',
      '.eds-sec { border-left:3px solid var(--yb-brand-border,#bacee2); margin:6px 0; border-radius:0 6px 6px 0; background:var(--yb-surface,#fff); }',
      '.eds-sec-hd { display:flex; align-items:center; gap:8px; padding:5px 8px; background:var(--yb-brand-subtle,#eaf1f8); border-radius:0 6px 0 0; cursor:pointer; }',
      '.eds-sec-name { font-weight:600; color:var(--yb-ink-2,#3d4a5c); font-size:13px; }',
      '.eds-sec-body { padding:4px 8px 6px 20px; }',
      '.eds-el { display:flex; align-items:center; gap:8px; padding:4px 8px; border-bottom:1px dashed var(--yb-divider,#f0f3f7); cursor:grab; }',
      '.eds-el:hover { background:var(--yb-surface-2,#f7f9fc); }',
      '.eds-el:last-child { border-bottom:none; }',
      '.eds-el-name { color:var(--yb-ink-1,#1c2430); font-size:13px; }',
      '.eds-dict { font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.eds-sort { font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.eds-ops { display:flex; align-items:center; gap:2px; }',
      '.eds-ops .el-button+.el-button { margin-left:2px; }',
      '.eds-dlg-ctx { font-size:12px; color:var(--yb-ink-2,#3d4a5c); margin-bottom:10px; }'
    ].join('\n');
  })();

  HIS.views['emr-dataset'] = {
    name: 'EmrDatasetManage',
    data: function () {
      return {
        isLead: false,
        scopeOpts: SCOPE_OPTS,
        fieldTypes: FIELD_TYPES,
        /* 左栏: 数据集列表 */
        keyword: '', scopeFilter: null, page: 1, size: 20,
        loading: false, list: [], total: 0, selectedId: null,
        /* 右栏: 结构树 */
        treeLoading: false, dataset: null, chapters: [],
        draftChapters: [], draftSections: [], collapsedMap: {},
        /* 拖拽排序(同一容器内): 源容器key + 行索引 */
        dragCtx: { arrKey: null, index: -1 },
        /* 数据集表单 */
        dsDlg: false, dsSaving: false, dsForm: this.emptyDs(),
        /* 克隆 */
        cloneDlg: false, cloneSaving: false, cloneSrc: null, cloneForm: { newCode: '', newName: '' },
        /* 章节 */
        chDlg: false, chSaving: false, chForm: this.emptyCh(),
        /* 小节 */
        secDlg: false, secSaving: false, secForm: this.emptySec(),
        /* 数据元 */
        elDlg: false, elSaving: false, elForm: this.emptyEl()
      };
    },
    computed: {
      /* 服务端章节树 + 本地草稿章节/小节 合并为渲染视图模型(草稿不落库, 首个数据元保存后实体化) */
      renderChapters: function () {
        var out = [];
        var byChapKey = {};
        (this.chapters || []).forEach(function (c) {
          var ch = {
            isDraft: false, lid: null, key: 'c:' + text(c.chapterKey),
            chapterKey: c.chapterKey, chapterName: c.chapterName,
            elements: c.elements || [],
            sections: (c.sections || []).map(function (s) {
              return {
                isDraft: false, lid: null,
                key: 'c:' + text(c.chapterKey) + '/' + (text(s.sectionKey) || ('#' + text(s.sectionName))),
                sectionKey: s.sectionKey, sectionName: s.sectionName, elements: s.elements || []
              };
            })
          };
          out.push(ch);
          byChapKey[text(c.chapterKey)] = ch;
        });
        (this.draftChapters || []).forEach(function (d) {
          var ch = {
            isDraft: true, lid: d.lid, key: 'dc:' + d.lid,
            chapterKey: d.chapterKey, chapterName: d.chapterName, elements: [], sections: []
          };
          out.push(ch);
          byChapKey[text(d.chapterKey)] = ch;
        });
        (this.draftSections || []).forEach(function (d) {
          var chap = byChapKey[text(d.chapterKey)];
          if (!chap) { return; }
          chap.sections.push({
            isDraft: true, lid: d.lid, key: 'ds:' + d.lid,
            sectionKey: d.sectionKey, sectionName: d.sectionName, elements: []
          });
        });
        return out;
      },
      elCount: function () {
        var n = 0;
        this.renderChapters.forEach(function (c) {
          n += (c.elements || []).length;
          (c.sections || []).forEach(function (s) { n += (s.elements || []).length; });
        });
        return n;
      }
    },
    created: function () {
      this.isLead = typeof HIS.isLead === 'function' ? !!HIS.isLead() : false;
      this.fetch();
    },
    methods: {
      /* ===== 表单工厂 ===== */
      emptyDs: function () { return { id: null, code: '', name: '', scope: 0, description: '', status: 1 }; },
      emptyCh: function () { return { lid: null, mode: 'new', oldKey: '', oldName: '', chapterKey: '', chapterName: '' }; },
      emptySec: function () {
        return { lid: null, lidParent: null, mode: 'new', chapterKey: '', chapterName: '', oldSectionKey: '', oldSectionName: '', sectionKey: '', sectionName: '' };
      },
      emptyEl: function () {
        return {
          id: null, chapterKey: '', chapterName: '', sectionKey: '', sectionName: '',
          fieldKey: '', fieldName: '', fieldType: 'text', dictSource: '',
          required: 0, readonly: 0, noCopy: 0, sortNo: null
        };
      },
      /* ===== 公共助手 ===== */
      scopeLabel: function (v) {
        var o = SCOPE_OPTS.filter(function (x) { return Number(x.v) === Number(v); })[0];
        return o ? o.l : (v == null ? '-' : v);
      },
      scopeTag: function (v) {
        var o = SCOPE_OPTS.filter(function (x) { return Number(x.v) === Number(v); })[0];
        return o ? o.t : 'info';
      },
      typeName: function (t) {
        var o = FIELD_TYPES.filter(function (x) { return x.t === t; })[0];
        return o ? o.n : (t == null ? '-' : t);
      },
      isDictType: function (t) { return !!DICT_TYPES[t]; },
      requireLead: function () {
        if (this.isLead) { return true; }
        ElementPlus.ElMessage.warning('仅牵头机构管理员可维护病历数据集');
        return false;
      },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      rowCls: function (s) { return HIS.sameId(s.row.id, this.selectedId) ? 'eds-row-on' : ''; },
      /* ===== 左栏: 列表查询 ===== */
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/emr/dataset/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.scopeFilter != null && vm.scopeFilter !== '') { q += '&scope=' + vm.scopeFilter; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          /* 无选中时自动选中首行, 右侧直接展示结构 */
          if (!vm.selectedId && vm.list.length) { vm.selectRow(vm.list[0]); }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
      onSize: function (s) { this.size = s; this.page = 1; this.fetch(); },
      onSearch: function () { this.page = 1; this.fetch(); },
      selectRow: function (row) {
        var vm = this;
        if (!row) { return; }
        if (HIS.sameId(vm.selectedId, row.id) && vm.dataset) { return; }
        vm.selectedId = HIS.id(row.id);
        vm.loadTree();
      },
      /* ===== 右栏: 结构树 ===== */
      loadTree: function () {
        var vm = this;
        if (!vm.selectedId) {
          vm.dataset = null; vm.chapters = [];
          vm.draftChapters = []; vm.draftSections = []; vm.collapsedMap = {};
          return;
        }
        vm.treeLoading = true;
        HIS.get('/api/his/emr/dataset/' + HIS.idParam(vm.selectedId)).then(function (d) {
          vm.dataset = (d && d.dataset) || null;
          vm.chapters = (d && d.chapters) || [];
          vm.pruneDrafts();
        }).catch(function (e) {
          vm.dataset = null; vm.chapters = [];
          HIS.notifyError(e);
        }).finally(function () { vm.treeLoading = false; });
      },
      /* 已实体化(服务端已存在同键章节/小节)的草稿移除, 避免与落库数据重复展示 */
      pruneDrafts: function () {
        var vm = this;
        var chapByKey = {}, chapKeys = {}, draftChapKeys = {};
        (vm.chapters || []).forEach(function (c) { chapByKey[text(c.chapterKey)] = c; chapKeys[text(c.chapterKey)] = 1; });
        vm.draftChapters = vm.draftChapters.filter(function (d) { return !chapKeys[text(d.chapterKey)]; });
        vm.draftChapters.forEach(function (d) { draftChapKeys[text(d.chapterKey)] = 1; });
        vm.draftSections = vm.draftSections.filter(function (d) {
          var ck = text(d.chapterKey);
          var chap = chapByKey[ck];
          var materialized = chap && (chap.sections || []).some(function (s) {
            return d.sectionKey ? text(d.sectionKey) === text(s.sectionKey)
              : text(d.sectionName) === text(s.sectionName);
          });
          if (materialized) { return false; }
          return !!(chap || draftChapKeys[ck]);
        });
      },
      chCount: function (c) {
        var n = (c.elements || []).length;
        (c.sections || []).forEach(function (s) { n += (s.elements || []).length; });
        return n;
      },
      isCollapsed: function (key) { return !!this.collapsedMap[key]; },
      toggleNode: function (key) { this.collapsedMap[key] = !this.collapsedMap[key]; },
      expandAll: function () { this.collapsedMap = {}; },
      collapseAll: function () {
        var m = {};
        this.renderChapters.forEach(function (c) {
          m[c.key] = true;
          (c.sections || []).forEach(function (s) { m[s.key] = true; });
        });
        this.collapsedMap = m;
      },
      /* 章节/小节内数据元收集(服务端在库对象; 用于批量改写/删除) */
      elementsOfChapter: function (chapterKey) {
        var out = [];
        this.renderChapters.forEach(function (c) {
          if (text(c.chapterKey) !== text(chapterKey)) { return; }
          (c.elements || []).forEach(function (e) { out.push(e); });
          (c.sections || []).forEach(function (s) { (s.elements || []).forEach(function (e) { out.push(e); }); });
        });
        return out;
      },
      elementsOfSection: function (chapterKey, sectionKey, sectionName) {
        var out = [];
        this.renderChapters.forEach(function (c) {
          if (text(c.chapterKey) !== text(chapterKey)) { return; }
          (c.sections || []).forEach(function (s) {
            var hit = sectionKey ? text(s.sectionKey) === text(sectionKey)
              : text(s.sectionName) === text(sectionName);
            if (hit) { (s.elements || []).forEach(function (e) { out.push(e); }); }
          });
        });
        return out;
      },
      chapterDup: function (key, exceptOldKey) {
        return this.renderChapters.some(function (c) {
          if (exceptOldKey != null && text(c.chapterKey) === text(exceptOldKey)) { return false; }
          return text(c.chapterKey) === text(key);
        });
      },
      sectionDup: function (chapterKey, key, name, selfKey, selfName) {
        var hit = false;
        this.renderChapters.forEach(function (c) {
          if (text(c.chapterKey) !== text(chapterKey)) { return; }
          (c.sections || []).forEach(function (s) {
            if (selfKey != null || selfName != null) {
              var isSelf = selfKey ? text(s.sectionKey) === text(selfKey)
                : text(s.sectionName) === text(selfName);
              if (isSelf) { return; }
            }
            var same = key ? text(s.sectionKey) === key : text(s.sectionName) === text(name);
            if (same) { hit = true; }
          });
        });
        return hit;
      },
      renameDraftByKey: function (oldKey, newKey, newName) {
        var ok = text(oldKey), nk = text(newKey);
        this.draftChapters.forEach(function (d) {
          if (text(d.chapterKey) === ok) { d.chapterKey = nk; d.chapterName = newName; }
        });
        this.draftSections.forEach(function (d) {
          if (text(d.chapterKey) === ok) { d.chapterKey = nk; d.chapterName = newName; }
        });
      },
      /* ===== 数据集 CRUD ===== */
      openDsCreate: function () {
        if (!this.requireLead()) { return; }
        this.dsForm = this.emptyDs();
        this.dsDlg = true;
      },
      openDsEdit: function () {
        if (!this.requireLead()) { return; }
        var d = this.dataset;
        if (!d) { ElementPlus.ElMessage.warning('请先选择数据集'); return; }
        this.dsForm = {
          id: d.id, code: text(d.code), name: text(d.name),
          scope: d.scope != null ? Number(d.scope) : 0,
          description: text(d.description),
          status: d.status != null ? Number(d.status) : 1
        };
        this.dsDlg = true;
      },
      submitDs: function () {
        var vm = this, f = vm.dsForm;
        if (!text(f.code).trim()) { ElementPlus.ElMessage.warning('请填写数据集编码'); return; }
        if (!text(f.name).trim()) { ElementPlus.ElMessage.warning('请填写数据集名称'); return; }
        var body = {
          id: f.id || null,
          code: text(f.code).trim(), name: text(f.name).trim(),
          scope: f.scope == null ? 0 : Number(f.scope),
          description: text(f.description).trim(),
          status: f.status == null ? 1 : Number(f.status)
        };
        vm.dsSaving = true;
        HIS.post('/api/his/emr/dataset/save', body).then(function (d) {
          HIS.notifySuccess(f.id ? '数据集已保存' : '数据集已新增');
          vm.dsDlg = false;
          if (f.id) {
            vm.fetch();
            if (HIS.sameId(vm.selectedId, f.id)) { vm.loadTree(); }
          } else {
            var newId = HIS.id(d && d.id);
            vm.page = 1; vm.selectedId = newId;
            vm.fetch(); vm.loadTree();
          }
        }).catch(HIS.notifyError).finally(function () { vm.dsSaving = false; });
      },
      removeDs: function () {
        var vm = this;
        if (!vm.requireLead()) { return; }
        var d = vm.dataset || vm.list.filter(function (r) { return HIS.sameId(r.id, vm.selectedId); })[0];
        if (!d) { ElementPlus.ElMessage.warning('请先选择数据集'); return; }
        ElementPlus.ElMessageBox.confirm('确认删除数据集「' + d.name + '(' + d.code + ')」? 其下全部数据元将被级联删除且不可恢复。', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/his/emr/dataset/' + HIS.idParam(d.id)); })
          .then(function () {
            HIS.notifySuccess('已删除');
            vm.selectedId = null; vm.dataset = null; vm.chapters = [];
            vm.draftChapters = []; vm.draftSections = []; vm.collapsedMap = {};
            vm.fetch();
          })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close' && e && e.message) { HIS.notifyError(e); } });
      },
      /* ===== 克隆 ===== */
      openClone: function () {
        if (!this.requireLead()) { return; }
        var d = this.dataset;
        if (!d) { ElementPlus.ElMessage.warning('请先选择数据集'); return; }
        this.cloneSrc = { id: d.id, code: text(d.code), name: text(d.name) };
        this.cloneForm = { newCode: '', newName: '' };
        this.cloneDlg = true;
      },
      submitClone: function () {
        var vm = this, s = vm.cloneSrc;
        if (!s) { return; }
        var body = {};
        if (text(vm.cloneForm.newCode).trim()) { body.newCode = text(vm.cloneForm.newCode).trim(); }
        if (text(vm.cloneForm.newName).trim()) { body.newName = text(vm.cloneForm.newName).trim(); }
        vm.cloneSaving = true;
        HIS.post('/api/his/emr/dataset/' + HIS.idParam(s.id) + '/clone', body).then(function (d) {
          HIS.notifySuccess('已克隆为「' + ((d && d.name) || '副本') + '」');
          vm.cloneDlg = false;
          vm.page = 1; vm.selectedId = HIS.id(d && d.id);
          vm.fetch(); vm.loadTree();
        }).catch(HIS.notifyError).finally(function () { vm.cloneSaving = false; });
      },
      /* ===== 章节 ===== */
      openAddChapter: function () {
        if (!this.requireLead()) { return; }
        if (!this.selectedId) { ElementPlus.ElMessage.warning('请先选择数据集'); return; }
        this.chForm = this.emptyCh();
        this.chDlg = true;
      },
      openEditChapter: function (c) {
        if (!this.requireLead()) { return; }
        var f = this.emptyCh();
        f.mode = c.isDraft ? 'draft' : 'edit';
        f.lid = c.lid || null;
        f.oldKey = text(c.chapterKey);
        f.oldName = text(c.chapterName);
        f.chapterKey = text(c.chapterKey);
        f.chapterName = text(c.chapterName);
        this.chForm = f;
        this.chDlg = true;
      },
      submitChapter: function () {
        var vm = this, f = vm.chForm;
        var key = text(f.chapterKey).trim(), name = text(f.chapterName).trim();
        if (!key) { ElementPlus.ElMessage.warning('请填写章节编码'); return; }
        if (!name) { ElementPlus.ElMessage.warning('请填写章节名称'); return; }
        var proceed = function () { vm.applyChapter(key, name); };
        var dup = vm.chapterDup(key, f.mode === 'new' ? null : f.oldKey);
        if (dup) {
          ElementPlus.ElMessageBox.confirm('已存在章节编码「' + key + '」, 继续保存将并入该章节。是否继续?', '提示', { type: 'warning' })
            .then(proceed).catch(function () { });
        } else { proceed(); }
      },
      applyChapter: function (key, name) {
        var vm = this, f = vm.chForm;
        if (f.mode === 'new') {
          vm.draftChapters.push({ lid: nextLid(), chapterKey: key, chapterName: name });
          vm.chDlg = false;
          return;
        }
        if (f.mode === 'draft') {
          vm.draftChapters.forEach(function (d) {
            if (d.lid === f.lid) { d.chapterKey = key; d.chapterName = name; }
          });
          vm.draftSections.forEach(function (d) {
            if (d.lidParent === f.lid) { d.chapterKey = key; d.chapterName = name; }
          });
          vm.chDlg = false;
          return;
        }
        /* 服务端章节: 批量改写其下全部数据元的 chapterKey/chapterName(重键=并入/改名) */
        if (key === text(f.oldKey) && name === text(f.oldName)) { vm.chDlg = false; return; }
        var els = vm.elementsOfChapter(f.oldKey);
        if (!els.length) { vm.chDlg = false; return; }
        vm.chSaving = true;
        Promise.all(els.map(function (e) {
          return HIS.post('/api/his/emr/dataset/element/save', { id: e.id, chapterKey: key, chapterName: name });
        })).then(function () {
          vm.renameDraftByKey(f.oldKey, key, name);
          HIS.notifySuccess('章节已更新(同步 ' + els.length + ' 个数据元)');
          vm.chDlg = false; vm.loadTree();
        }).catch(HIS.notifyError).finally(function () { vm.chSaving = false; });
      },
      removeChapter: function (c) {
        var vm = this;
        if (!vm.requireLead()) { return; }
        if (c.isDraft) {
          ElementPlus.ElMessageBox.confirm('确认移除草稿章节「' + c.chapterName + '」?', '提示', { type: 'warning' })
            .then(function () {
              vm.draftChapters = vm.draftChapters.filter(function (d) { return d.lid !== c.lid; });
              vm.draftSections = vm.draftSections.filter(function (d) { return d.lidParent !== c.lid; });
            }).catch(function () { });
          return;
        }
        var els = vm.elementsOfChapter(c.chapterKey);
        ElementPlus.ElMessageBox.confirm('确认删除章节「' + c.chapterName + '」及其下 ' + els.length + ' 个数据元? 删除后不可恢复。', '删除确认', { type: 'warning' })
          .then(function () {
            return Promise.all(els.map(function (e) { return HIS.del('/api/his/emr/dataset/element/' + HIS.idParam(e.id)); }));
          })
          .then(function () { HIS.notifySuccess('章节已删除'); vm.loadTree(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close' && e && e.message) { HIS.notifyError(e); } });
      },
      /* ===== 小节 ===== */
      openAddSection: function (c) {
        if (!this.requireLead()) { return; }
        var f = this.emptySec();
        f.lidParent = c.isDraft ? c.lid : null;
        f.chapterKey = text(c.chapterKey);
        f.chapterName = text(c.chapterName);
        this.secForm = f;
        this.secDlg = true;
      },
      openEditSection: function (c, s) {
        if (!this.requireLead()) { return; }
        var f = this.emptySec();
        f.mode = s.isDraft ? 'draft' : 'edit';
        f.lid = s.lid || null;
        f.chapterKey = text(c.chapterKey);
        f.chapterName = text(c.chapterName);
        f.oldSectionKey = text(s.sectionKey);
        f.oldSectionName = text(s.sectionName);
        f.sectionKey = text(s.sectionKey);
        f.sectionName = text(s.sectionName);
        this.secForm = f;
        this.secDlg = true;
      },
      submitSection: function () {
        var vm = this, f = vm.secForm;
        var key = text(f.sectionKey).trim(), name = text(f.sectionName).trim();
        if (!name) { ElementPlus.ElMessage.warning('请填写小节名称'); return; }
        if (f.mode === 'draft') {
          vm.draftSections.forEach(function (d) {
            if (d.lid === f.lid) { d.sectionKey = key; d.sectionName = name; }
          });
          vm.secDlg = false;
          return;
        }
        if (f.mode === 'new') {
          if (vm.sectionDup(f.chapterKey, key, name, null, null)) {
            ElementPlus.ElMessage.warning('该章节下已存在同编码/同名小节, 请直接在其下新增数据元');
            return;
          }
          vm.draftSections.push({
            lid: nextLid(), lidParent: f.lidParent || null,
            chapterKey: text(f.chapterKey), chapterName: text(f.chapterName),
            sectionKey: key, sectionName: name
          });
          vm.secDlg = false;
          return;
        }
        /* 服务端小节: 批量改写其下数据元的 sectionKey/sectionName(空键=仅按名称归组) */
        if (key === text(f.oldSectionKey) && name === text(f.oldSectionName)) { vm.secDlg = false; return; }
        var els = vm.elementsOfSection(f.chapterKey, f.oldSectionKey, f.oldSectionName);
        if (!els.length) { vm.secDlg = false; return; }
        var proceed = function () {
          vm.secSaving = true;
          Promise.all(els.map(function (e) {
            return HIS.post('/api/his/emr/dataset/element/save', { id: e.id, sectionKey: key, sectionName: name });
          })).then(function () {
            HIS.notifySuccess('小节已更新(同步 ' + els.length + ' 个数据元)');
            vm.secDlg = false; vm.loadTree();
          }).catch(HIS.notifyError).finally(function () { vm.secSaving = false; });
        };
        if (vm.sectionDup(f.chapterKey, key, name, f.oldSectionKey, f.oldSectionName)) {
          ElementPlus.ElMessageBox.confirm('该章节下已存在小节「' + (key || name) + '」, 继续保存将并入该小节。是否继续?', '提示', { type: 'warning' })
            .then(proceed).catch(function () { });
        } else { proceed(); }
      },
      removeSection: function (c, s) {
        var vm = this;
        if (!vm.requireLead()) { return; }
        if (s.isDraft) {
          ElementPlus.ElMessageBox.confirm('确认移除草稿小节「' + s.sectionName + '」?', '提示', { type: 'warning' })
            .then(function () { vm.draftSections = vm.draftSections.filter(function (d) { return d.lid !== s.lid; }); })
            .catch(function () { });
          return;
        }
        var els = vm.elementsOfSection(c.chapterKey, s.sectionKey, s.sectionName);
        ElementPlus.ElMessageBox.confirm('确认删除小节「' + s.sectionName + '」及其下 ' + els.length + ' 个数据元? 删除后不可恢复。', '删除确认', { type: 'warning' })
          .then(function () {
            return Promise.all(els.map(function (e) { return HIS.del('/api/his/emr/dataset/element/' + HIS.idParam(e.id)); }));
          })
          .then(function () { HIS.notifySuccess('小节已删除'); vm.loadTree(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close' && e && e.message) { HIS.notifyError(e); } });
      },
      /* ===== 数据元 ===== */
      openAddElement: function (c, s) {
        if (!this.requireLead()) { return; }
        if (!this.selectedId) { ElementPlus.ElMessage.warning('请先选择数据集'); return; }
        var f = this.emptyEl();
        if (c) { f.chapterKey = text(c.chapterKey); f.chapterName = text(c.chapterName); }
        if (s) { f.sectionKey = text(s.sectionKey); f.sectionName = text(s.sectionName); }
        this.elForm = f;
        this.elDlg = true;
      },
      openEditElement: function (e) {
        if (!this.requireLead()) { return; }
        var f = this.emptyEl();
        f.id = e.id;
        f.chapterKey = text(e.chapterKey); f.chapterName = text(e.chapterName);
        f.sectionKey = text(e.sectionKey); f.sectionName = text(e.sectionName);
        f.fieldKey = text(e.fieldKey); f.fieldName = text(e.fieldName);
        f.fieldType = text(e.fieldType) || 'text';
        f.dictSource = text(e.dictSource);
        f.required = Number(e.required) === 1 ? 1 : 0;
        f.readonly = Number(e.readonly) === 1 ? 1 : 0;
        f.noCopy = Number(e.noCopy) === 1 ? 1 : 0;
        f.sortNo = e.sortNo != null ? Number(e.sortNo) : null;
        this.elForm = f;
        this.elDlg = true;
      },
      submitElement: function () {
        var vm = this, f = vm.elForm;
        if (!text(f.chapterKey).trim()) { ElementPlus.ElMessage.warning('请填写章节编码'); return; }
        if (!text(f.chapterName).trim()) { ElementPlus.ElMessage.warning('请填写章节名称'); return; }
        var sk = text(f.sectionKey).trim(), sn = text(f.sectionName).trim();
        if ((sk || sn) && !sn) { ElementPlus.ElMessage.warning('使用小节时须填写小节名称(编码可留空)'); return; }
        if (!text(f.fieldKey).trim()) { ElementPlus.ElMessage.warning('请填写数据元编码'); return; }
        if (!text(f.fieldName).trim()) { ElementPlus.ElMessage.warning('请填写数据元名称'); return; }
        var body = {
          id: f.id || null,
          datasetId: vm.selectedId,
          chapterKey: text(f.chapterKey).trim(), chapterName: text(f.chapterName).trim(),
          /* 空串=清空(null 语义为"不改"): 后端仅当 sectionKey/sectionName 两者都空才归为章节直属,
           * 故清空小节时两字段均送空串, 避免 sectionName 残留旧值致数据元仍挂在旧小节下 */
          sectionKey: sk, sectionName: sn,
          fieldKey: text(f.fieldKey).trim(), fieldName: text(f.fieldName).trim(),
          fieldType: text(f.fieldType) || 'text',
          dictSource: vm.isDictType(f.fieldType) ? text(f.dictSource).trim() : '',
          required: Number(f.required) ? 1 : 0,
          readonly: Number(f.readonly) ? 1 : 0,
          noCopy: Number(f.noCopy) ? 1 : 0,
          sortNo: f.sortNo == null || f.sortNo === '' ? null : Number(f.sortNo)
        };
        vm.elSaving = true;
        HIS.post('/api/his/emr/dataset/element/save', body).then(function () {
          HIS.notifySuccess(f.id ? '数据元已保存' : '数据元已新增');
          vm.elDlg = false; vm.loadTree();
        }).catch(HIS.notifyError).finally(function () { vm.elSaving = false; });
      },
      removeElement: function (e) {
        var vm = this;
        if (!vm.requireLead()) { return; }
        ElementPlus.ElMessageBox.confirm('确认删除数据元「' + e.fieldName + '(' + e.fieldKey + ')」?', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/his/emr/dataset/element/' + HIS.idParam(e.id)); })
          .then(function () { HIS.notifySuccess('已删除'); vm.loadTree(); })
          .catch(function (x) { if (x !== 'cancel' && x !== 'close' && x && x.message) { HIS.notifyError(x); } });
      },
      /* 上移/下移(仅限同一容器内相邻交换): 交换后按可视顺序全量重排并提交 */
      moveEl: function (arr, i, dir) {
        var j = i + dir;
        if (j < 0 || j >= arr.length) { return; }
        if (!this.requireLead()) { return; }
        var tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        this.reorderAll();
      },
      /* 拖拽(同一容器内): 记录源行; 落到目标行时按目标位插入(跨容器/自身位置释放不生效) */
      dragStart: function (arrKey, i) { this.dragCtx = { arrKey: arrKey, index: i }; },
      dropOn: function (arrKey, i) {
        var vm = this, d = vm.dragCtx;
        vm.dragCtx = { arrKey: null, index: -1 };
        if (!d.arrKey || d.arrKey !== arrKey || d.index < 0 || d.index === i) { return; }
        if (!vm.requireLead()) { return; }
        var arr = vm.containerArr(arrKey);
        if (!arr || d.index >= arr.length) { return; }
        arr.splice(i, 0, arr.splice(d.index, 1)[0]);
        vm.reorderAll();
      },
      /* 容器 key → 元素数组: 章节直属 c.key / 小节 s.key(与 renderChapters 的 key 同构) */
      containerArr: function (arrKey) {
        var found = null;
        this.renderChapters.forEach(function (c) {
          if (found) { return; }
          if (c.key === arrKey) { found = c.elements; return; }
          (c.sections || []).forEach(function (s) { if (!found && s.key === arrKey) { found = s.elements; } });
        });
        return found;
      },
      /* 按可视 DFS 顺序全量重排 sortNo=(序号+1)*10 并提交(章节/小节首现序随之稳定) */
      reorderAll: function () {
        var vm = this;
        var flat = [];
        vm.renderChapters.forEach(function (c) {
          (c.elements || []).forEach(function (e) { flat.push(e); });
          (c.sections || []).forEach(function (s) { (s.elements || []).forEach(function (e) { flat.push(e); }); });
        });
        if (!flat.length) { return; }
        var items = flat.map(function (e, idx) { return { id: e.id, sortNo: (idx + 1) * 10 }; });
        HIS.post('/api/his/emr/dataset/element/reorder', items)
          .then(function () { vm.loadTree(); })
          .catch(function (e) { HIS.notifyError(e); vm.loadTree(); });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">病历数据集管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(章节→小节→数据元 三级结构 · 全医共体共享)</span> <el-tag size="small" effect="plain" :type="isLead?\'success\':\'info\'" style="margin-left:6px;">{{ isLead ? \'牵头机构 · 可维护\' : \'非牵头 · 只读\' }}</el-tag></div>',
      '  <div class="eds-split">',
      /* ===== 左栏: 数据集列表 ===== */
      '    <div class="eds-left">',
      '      <div class="toolbar eds-tools">',
      '        <el-input v-model="keyword" size="small" placeholder="名称/编码检索" clearable style="width:150px" @keyup.enter="onSearch"></el-input>',
      '        <el-select v-model="scopeFilter" size="small" placeholder="适用范围" clearable style="width:110px" @change="onSearch">',
      '          <el-option v-for="o in scopeOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '        <el-button size="small" type="primary" @click="onSearch">检索</el-button>',
      '      </div>',
      '      <div class="toolbar eds-tools">',
      '        <el-button size="small" type="primary" :disabled="!isLead" @click="openDsCreate">新增</el-button>',
      '        <el-button size="small" :disabled="!isLead || !dataset" @click="openDsEdit">编辑</el-button>',
      '        <el-button size="small" :disabled="!isLead || !dataset" @click="openClone">克隆</el-button>',
      '        <el-button size="small" type="danger" plain :disabled="!isLead || !dataset" @click="removeDs">删除</el-button>',
      '      </div>',
      '      <div class="eds-table">',
      '        <el-table :data="list" v-loading="loading" border stripe size="small" height="100%" highlight-current-row :row-class-name="rowCls" @row-click="selectRow">',
      '          <el-table-column type="index" label="序号" width="56" align="center" :index="seqNo"></el-table-column>',
      '          <el-table-column prop="code" label="编码" width="110" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="范围" width="90" align="center"><template #default="s"><el-tag size="small" effect="plain" :type="scopeTag(s.row.scope)">{{ scopeLabel(s.row.scope) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag size="small" :type="Number(s.row.status)===1?\'success\':\'info\'">{{ Number(s.row.status)===1?\'启用\':\'停用\' }}</el-tag></template></el-table-column>',
      '        </el-table>',
      '      </div>',
      '      <el-pagination class="eds-pager" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </div>',
      /* ===== 右栏: 结构树编辑 ===== */
      '    <div class="eds-right" v-loading="treeLoading">',
      '      <div class="eds-head" v-if="dataset">',
      '        <div class="eds-head-row">',
      '          <span class="eds-ds-name">{{ dataset.name }}</span>',
      '          <span class="eds-key">{{ dataset.code }}</span>',
      '          <el-tag size="small" effect="plain" :type="scopeTag(dataset.scope)">{{ scopeLabel(dataset.scope) }}</el-tag>',
      '          <el-tag size="small" :type="Number(dataset.status)===1?\'success\':\'info\'">{{ Number(dataset.status)===1?\'启用\':\'停用\' }}</el-tag>',
      '          <span class="eds-grow"></span>',
      '          <el-button size="small" @click="expandAll">全部展开</el-button>',
      '          <el-button size="small" @click="collapseAll">全部收起</el-button>',
      '          <el-button size="small" @click="loadTree">刷新</el-button>',
      '          <el-button size="small" type="primary" :disabled="!isLead" @click="openAddChapter">+ 新增章节</el-button>',
      '        </div>',
      '        <div class="eds-head-sub" v-if="dataset.description">{{ dataset.description }}</div>',
      '        <div class="eds-head-sub">共 {{ renderChapters.length }} 章节 · {{ elCount }} 个数据元<i style="color:var(--yb-ink-4);font-style:normal;margin-left:8px;">(章节/小节随首个数据元落库, 编辑/删除章节与小节将同步其下全部数据元; 数据元行可拖拽同层排序)</i></div>',
      '      </div>',
      '      <div class="eds-tree" v-if="dataset">',
      '        <div class="eds-empty" v-if="!renderChapters.length">该数据集尚未定义结构 — 点击「+ 新增章节」开始</div>',
      '        <div class="eds-ch" v-for="c in renderChapters" :key="c.key">',
      '          <div class="eds-ch-hd" @click="toggleNode(c.key)">',
      '            <span class="eds-caret">{{ isCollapsed(c.key) ? \'▸\' : \'▾\' }}</span>',
      '            <span class="eds-ch-name">{{ c.chapterName }}</span>',
      '            <span class="eds-key">{{ c.chapterKey }}</span>',
      '            <el-tag size="small" type="info" effect="plain">{{ chCount(c) }}</el-tag>',
      '            <el-tag size="small" type="warning" effect="plain" v-if="c.isDraft">草稿 · 保存首个数据元后生效</el-tag>',
      '            <span class="eds-grow"></span>',
      '            <span class="eds-ops" @click.stop>',
      '              <el-button link size="small" type="primary" :disabled="!isLead" @click="openAddSection(c)">+ 小节</el-button>',
      '              <el-button link size="small" type="primary" :disabled="!isLead" @click="openAddElement(c)">+ 数据元</el-button>',
      '              <el-button link size="small" :disabled="!isLead" @click="openEditChapter(c)">编辑</el-button>',
      '              <el-button link size="small" type="danger" :disabled="!isLead" @click="removeChapter(c)">删除</el-button>',
      '            </span>',
      '          </div>',
      '          <div class="eds-ch-body" v-show="!isCollapsed(c.key)">',
      '            <div class="eds-el" v-for="(e, i) in c.elements" :key="e.id" :draggable="isLead" @dragstart="dragStart(c.key, i)" @dragover.prevent @drop="dropOn(c.key, i)">',
      '              <span class="eds-el-name">{{ e.fieldName }}</span>',
      '              <span class="eds-key">{{ e.fieldKey }}</span>',
      '              <el-tag size="small" type="info" effect="plain">{{ typeName(e.fieldType) }}</el-tag>',
      '              <el-tag size="small" type="danger" effect="plain" v-if="Number(e.required)===1">必填</el-tag>',
      '              <el-tag size="small" type="warning" effect="plain" v-if="Number(e.readonly)===1">只读</el-tag>',
      '              <el-tag size="small" type="info" effect="plain" v-if="Number(e.noCopy)===1">防复制</el-tag>',
      '              <span class="eds-dict" v-if="e.dictSource">字典 {{ e.dictSource }}</span>',
      '              <span class="eds-sort" v-if="e.sortNo != null">#{{ e.sortNo }}</span>',
      '              <span class="eds-grow"></span>',
      '              <span class="eds-ops">',
      '                <el-button link size="small" :disabled="!isLead || i===0" title="上移" @click="moveEl(c.elements, i, -1)">▲</el-button>',
      '                <el-button link size="small" :disabled="!isLead || i===c.elements.length-1" title="下移" @click="moveEl(c.elements, i, 1)">▼</el-button>',
      '                <el-button link size="small" type="primary" :disabled="!isLead" @click="openEditElement(e)">编辑</el-button>',
      '                <el-button link size="small" type="danger" :disabled="!isLead" @click="removeElement(e)">删除</el-button>',
      '              </span>',
      '            </div>',
      '            <div class="eds-sec" v-for="s in c.sections" :key="s.key">',
      '              <div class="eds-sec-hd" @click="toggleNode(s.key)">',
      '                <span class="eds-caret">{{ isCollapsed(s.key) ? \'▸\' : \'▾\' }}</span>',
      '                <span class="eds-sec-name">{{ s.sectionName }}</span>',
      '                <span class="eds-key">{{ s.sectionKey || \'-\' }}</span>',
      '                <el-tag size="small" type="info" effect="plain">{{ (s.elements || []).length }}</el-tag>',
      '                <el-tag size="small" type="warning" effect="plain" v-if="s.isDraft">草稿</el-tag>',
      '                <span class="eds-grow"></span>',
      '                <span class="eds-ops" @click.stop>',
      '                  <el-button link size="small" type="primary" :disabled="!isLead" @click="openAddElement(c, s)">+ 数据元</el-button>',
      '                  <el-button link size="small" :disabled="!isLead" @click="openEditSection(c, s)">编辑</el-button>',
      '                  <el-button link size="small" type="danger" :disabled="!isLead" @click="removeSection(c, s)">删除</el-button>',
      '                </span>',
      '              </div>',
      '              <div class="eds-sec-body" v-show="!isCollapsed(s.key)">',
      '                <div class="eds-empty-inline" v-if="!(s.elements || []).length">小节下暂无数据元</div>',
      '                <div class="eds-el" v-for="(e, i) in s.elements" :key="e.id" :draggable="isLead" @dragstart="dragStart(s.key, i)" @dragover.prevent @drop="dropOn(s.key, i)">',
      '                  <span class="eds-el-name">{{ e.fieldName }}</span>',
      '                  <span class="eds-key">{{ e.fieldKey }}</span>',
      '                  <el-tag size="small" type="info" effect="plain">{{ typeName(e.fieldType) }}</el-tag>',
      '                  <el-tag size="small" type="danger" effect="plain" v-if="Number(e.required)===1">必填</el-tag>',
      '                  <el-tag size="small" type="warning" effect="plain" v-if="Number(e.readonly)===1">只读</el-tag>',
      '                  <el-tag size="small" type="info" effect="plain" v-if="Number(e.noCopy)===1">防复制</el-tag>',
      '                  <span class="eds-dict" v-if="e.dictSource">字典 {{ e.dictSource }}</span>',
      '                  <span class="eds-sort" v-if="e.sortNo != null">#{{ e.sortNo }}</span>',
      '                  <span class="eds-grow"></span>',
      '                  <span class="eds-ops">',
      '                    <el-button link size="small" :disabled="!isLead || i===0" title="上移" @click="moveEl(s.elements, i, -1)">▲</el-button>',
      '                    <el-button link size="small" :disabled="!isLead || i===s.elements.length-1" title="下移" @click="moveEl(s.elements, i, 1)">▼</el-button>',
      '                    <el-button link size="small" type="primary" :disabled="!isLead" @click="openEditElement(e)">编辑</el-button>',
      '                    <el-button link size="small" type="danger" :disabled="!isLead" @click="removeElement(e)">删除</el-button>',
      '                  </span>',
      '                </div>',
      '              </div>',
      '            </div>',
      '            <div class="eds-empty-inline" v-if="!c.elements.length && !c.sections.length">章节下暂无内容 — 点「+ 数据元」或「+ 小节」开始</div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '      <div class="eds-empty eds-empty-main" v-else>请从左侧列表选择数据集, 右侧将展示其章节结构</div>',
      '    </div>',
      '  </div>',
      /* ===== 数据集 新增/编辑 ===== */
      '  <el-dialog v-model="dsDlg" :title="dsForm.id?\'编辑数据集\':\'新增数据集\'" width="520px" append-to-body :close-on-click-modal="false">',
      '    <el-form label-width="90px" size="small">',
      '      <el-form-item label="数据集编码"><el-input v-model="dsForm.code" :disabled="!!dsForm.id" placeholder="如 DS_ADMISSION(租户内唯一)"></el-input></el-form-item>',
      '      <el-form-item label="数据集名称"><el-input v-model="dsForm.name" placeholder="如 入院记录数据集"></el-input></el-form-item>',
      '      <el-form-item label="适用范围"><el-select v-model="dsForm.scope" style="width:100%"><el-option v-for="o in scopeOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></el-form-item>',
      '      <el-form-item label="描述"><el-input v-model="dsForm.description" type="textarea" :rows="2" placeholder="数据集用途说明(可选)"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="dsForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="dsDlg=false">取消</el-button><el-button size="small" type="primary" :loading="dsSaving" @click="submitDs">确定</el-button></template>',
      '  </el-dialog>',
      /* ===== 克隆 ===== */
      '  <el-dialog v-model="cloneDlg" title="克隆数据集" width="520px" append-to-body :close-on-click-modal="false">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;" :title="cloneSrc ? (\'源数据集: 「\' + cloneSrc.name + \'」(\' + cloneSrc.code + \') — 将深拷贝其全部章节/小节/数据元\') : \'\'"></el-alert>',
      '    <el-form label-width="90px" size="small">',
      '      <el-form-item label="新编码"><el-input v-model="cloneForm.newCode" placeholder="留空自动生成 源编码_COPY"></el-input></el-form-item>',
      '      <el-form-item label="新名称"><el-input v-model="cloneForm.newName" placeholder="留空=源名称(副本)"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="cloneDlg=false">取消</el-button><el-button size="small" type="primary" :loading="cloneSaving" @click="submitClone">确定克隆</el-button></template>',
      '  </el-dialog>',
      /* ===== 章节 新增/编辑 ===== */
      '  <el-dialog v-model="chDlg" :title="chForm.mode===\'new\'?\'新增章节\':\'编辑章节\'" width="480px" append-to-body :close-on-click-modal="false">',
      '    <el-alert v-if="chForm.mode===\'new\'" type="info" :closable="false" show-icon style="margin-bottom:12px;" title="章节随其首个数据元一并落库: 保存后请在该章节下新增数据元。"></el-alert>',
      '    <el-alert v-else-if="chForm.mode===\'edit\'" type="warning" :closable="false" show-icon style="margin-bottom:12px;" title="修改章节编码/名称将同步应用到该章节下全部数据元。"></el-alert>',
      '    <el-form label-width="90px" size="small">',
      '      <el-form-item label="章节编码"><el-input v-model="chForm.chapterKey" placeholder="如 CHIEF_COMPLAINT"></el-input></el-form-item>',
      '      <el-form-item label="章节名称"><el-input v-model="chForm.chapterName" placeholder="如 主诉"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="chDlg=false">取消</el-button><el-button size="small" type="primary" :loading="chSaving" @click="submitChapter">确定</el-button></template>',
      '  </el-dialog>',
      /* ===== 小节 新增/编辑 ===== */
      '  <el-dialog v-model="secDlg" :title="secForm.mode===\'new\'?\'新增小节\':\'编辑小节\'" width="480px" append-to-body :close-on-click-modal="false">',
      '    <div class="eds-dlg-ctx">所属章节: {{ secForm.chapterName }} <span class="eds-key">{{ secForm.chapterKey }}</span></div>',
      '    <el-alert v-if="secForm.mode===\'new\'" type="info" :closable="false" show-icon style="margin-bottom:12px;" title="小节随其首个数据元一并落库, 小节编码可留空(按名称归组)。"></el-alert>',
      '    <el-alert v-else-if="secForm.mode===\'edit\'" type="warning" :closable="false" show-icon style="margin-bottom:12px;" title="修改将同步应用到该小节下全部数据元。"></el-alert>',
      '    <el-form label-width="90px" size="small">',
      '      <el-form-item label="小节编码"><el-input v-model="secForm.sectionKey" placeholder="可选, 如 S1"></el-input></el-form-item>',
      '      <el-form-item label="小节名称"><el-input v-model="secForm.sectionName" placeholder="如 基本信息"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="secDlg=false">取消</el-button><el-button size="small" type="primary" :loading="secSaving" @click="submitSection">确定</el-button></template>',
      '  </el-dialog>',
      /* ===== 数据元 新增/编辑 ===== */
      '  <el-dialog v-model="elDlg" :title="elForm.id?\'编辑数据元\':\'新增数据元\'" width="640px" append-to-body :close-on-click-modal="false">',
      '    <el-form label-width="90px" size="small">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="章节编码"><el-input v-model="elForm.chapterKey" placeholder="章节key"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="章节名称"><el-input v-model="elForm.chapterName" placeholder="章节名称"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="小节编码"><el-input v-model="elForm.sectionKey" placeholder="可留空(直接挂章节)"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="小节名称"><el-input v-model="elForm.sectionName" placeholder="可留空(直接挂章节)"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">数据元定义</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="数据元编码"><el-input v-model="elForm.fieldKey" placeholder="英文/拼音唯一键(同数据集内唯一)"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="数据元名称"><el-input v-model="elForm.fieldName" placeholder="如 主诉"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="8"><el-form-item label="字段类型"><el-select v-model="elForm.fieldType" style="width:100%"><el-option v-for="ft in fieldTypes" :key="ft.t" :label="ft.n" :value="ft.t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="排序号"><el-input-number v-model="elForm.sortNo" :min="0" :step="10" controls-position="right" placeholder="留空=自动追加" style="width:100%"></el-input-number></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="必填"><el-switch v-model="elForm.required" :active-value="1" :inactive-value="0" active-text="是" inactive-text="否"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="字典来源" v-if="isDictType(elForm.fieldType)"><el-input v-model="elForm.dictSource" placeholder="字典/值域编码, 如 std_gender、diag、charge、drug"></el-input></el-form-item>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="只读"><el-switch v-model="elForm.readonly" :active-value="1" :inactive-value="0" active-text="是" inactive-text="否"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="防复制"><el-switch v-model="elForm.noCopy" :active-value="1" :inactive-value="0" active-text="是" inactive-text="否"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer><el-button size="small" @click="elDlg=false">取消</el-button><el-button size="small" type="primary" :loading="elSaving" @click="submitElement">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
