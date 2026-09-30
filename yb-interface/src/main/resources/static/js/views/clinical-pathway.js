/* 临床路径模板管理: 左侧模板列表(350px, 搜索/状态筛选/分页) + 右侧时间轴节点编辑器(模板→第X天节点→任务 三级定义)
 * 接口: GET/POST /api/his/pathway/template (list?orgId=&keyword=&status=&page=&size= | POST | PUT /{id} | PUT /{id}/status | POST /{id}/copy)
 *       GET /api/his/pathway/template/{id} -> {template, nodes:[{node, tasks}]}
 *       /api/his/pathway/node (GET /list/{templateId} | POST | PUT /{id} | DELETE /{id})
 *       /api/his/pathway/task (POST | PUT /{id} | DELETE /{id})
 * 检索源: /api/community-dict/diag-dict/page?dictType=west&status=1 (ICD-10 诊断)
 *         /api/org-catalog/available/drug|charge|med-dict (本机构可开药品/收费项目/用法频次)
 * 注册: HIS.views.ClinicalPathwayManage (须在 app.js 之前加载); 样式以 <style id="clinical-pathway-css"> 注入, 前缀 cp-* */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  const TASK_TYPE = { 1: '医嘱', 2: '护理', 3: '检查', 4: '检验', 5: '宣教' };
  const ORDER_TYPE = { 1: '长期', 2: '临时' };
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  const PATHWAY_STATUS = { 1: '启用', 0: '停用' };

  function money(value) {
    const n = Number(value);
    return isNaN(n) ? '0.00' : n.toFixed(2);
  }

  function text(v) { return v == null ? '' : String(v); }

  /* ============================================================
   * 样式(注入一次): 复用 theme.css 的 --yb-* 令牌, 不改动既有 CSS 文件
   * ============================================================ */
  const CSS = `
.cp-workbench { display:flex; height:calc(100vh - 88px); min-height:520px; overflow:hidden; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); }

/* ===== 左栏: 模板列表 ===== */
.cp-side { width:350px; flex:none; display:flex; flex-direction:column; border-right:1px solid var(--yb-border); background:var(--yb-surface-2); }
.cp-side-head { flex:none; padding:12px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); }
.cp-side-tools { display:flex; gap:8px; align-items:center; }
.cp-side-tools .grow { flex:1; min-width:0; }
.cp-side-body { flex:1; min-height:0; }
.cp-tpl { padding:10px 12px; border-bottom:1px solid var(--yb-divider); cursor:pointer; background:var(--yb-surface); transition:background var(--yb-dur) var(--yb-ease); }
.cp-tpl:hover { background:var(--yb-surface-3); }
.cp-tpl.is-active { background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }
.cp-tpl .r1 { display:flex; align-items:center; gap:6px; }
.cp-tpl .r1 .nm { flex:1; min-width:0; font-size:var(--yb-fs-md); color:var(--yb-ink-1); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cp-tpl .r2 { margin-top:4px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cp-tpl .r3 { margin-top:4px; display:flex; align-items:center; gap:10px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.cp-tpl .r3 .code { font-family:var(--yb-font-mono); font-size:var(--yb-fs-cap); color:var(--yb-ink-4); }
.cp-tpl .r3 .money { margin-left:auto; color:var(--yb-gold); font-family:var(--yb-font-mono); }
.cp-side-pager { flex:none; display:flex; justify-content:center; padding:6px 0; border-top:1px solid var(--yb-border); background:var(--yb-surface); }

/* ===== 右栏: 详情/编辑器 ===== */
.cp-main { flex:1; min-width:0; display:flex; flex-direction:column; overflow:hidden; background:var(--yb-surface); }
.cp-empty { flex:1; display:flex; flex-direction:column; gap:8px; align-items:center; justify-content:center; color:var(--yb-ink-4); }
.cp-empty .big { font-size:var(--yb-fs-lg); color:var(--yb-ink-3); }
.cp-detail { flex:1; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.cp-head { flex:none; display:flex; gap:16px; padding:12px 16px; border-bottom:1px solid var(--yb-border); background:linear-gradient(180deg, var(--yb-surface), var(--yb-surface-2)); }
.cp-head-main { flex:1; min-width:0; }
.cp-head .line1 { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.cp-head .line1 .nm { font-size:var(--yb-fs-lg); font-weight:700; color:var(--yb-ink-1); }
.cp-head .line2 { margin-top:8px; display:flex; flex-wrap:wrap; gap:6px 18px; font-size:var(--yb-fs-base); color:var(--yb-ink-3); }
.cp-head .line2 b { color:var(--yb-ink-1); font-weight:600; }
.cp-head .line2 .money { font-family:var(--yb-font-mono); color:var(--yb-gold); }
.cp-head .line3 { margin-top:6px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.cp-head-ops { flex:none; display:flex; flex-direction:column; gap:6px; align-items:flex-end; }
.cp-head-ops .row { display:flex; gap:6px; }

/* ===== 时间轴节点编辑器 ===== */
.cp-body { flex:1; min-height:0; overflow:auto; padding:16px 18px 24px; }
.cp-timeline { position:relative; padding-left:26px; }
.cp-timeline::before { content:''; position:absolute; left:8px; top:6px; bottom:6px; width:2px; background:linear-gradient(180deg, var(--yb-brand-border), var(--yb-border-light)); }
.cp-node { position:relative; margin-bottom:12px; }
.cp-node::before { content:''; position:absolute; left:-24px; top:14px; width:10px; height:10px; border-radius:50%; background:var(--yb-surface); border:2px solid var(--yb-brand); box-sizing:content-box; }
.cp-node-head { display:flex; align-items:center; gap:8px; padding:8px 12px; background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm) var(--yb-r-sm) 0 0; }
.cp-node-head .day { flex:none; font-weight:700; color:var(--yb-brand); font-size:var(--yb-fs-base); }
.cp-node-head .nm { color:var(--yb-ink-1); font-weight:600; }
.cp-node-head .desc { color:var(--yb-ink-4); font-size:var(--yb-fs-sm); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:280px; }
.cp-node-head .count { flex:none; padding:0 6px; height:18px; line-height:18px; border-radius:var(--yb-r-pill); background:var(--yb-brand-subtle); color:var(--yb-brand); font-size:var(--yb-fs-cap); font-weight:600; }
.cp-node-head .ops { margin-left:auto; flex:none; }
.cp-node-tasks { border:1px solid var(--yb-border-light); border-top:none; border-radius:0 0 var(--yb-r-sm) var(--yb-r-sm); padding:8px 10px 10px; background:var(--yb-surface); }
.cp-node-add { margin-top:8px; }
.cp-add-day { position:relative; padding-top:2px; }
.cp-add-day::before { content:''; position:absolute; left:-24px; top:14px; width:10px; height:10px; border-radius:50%; background:var(--yb-surface); border:2px dashed var(--yb-border-strong); box-sizing:content-box; }

/* ===== 表单网格 ===== */
.cp-form-grid { display:grid; grid-template-columns:1fr 1fr; gap:0 14px; }
.cp-picked { display:flex; align-items:center; gap:14px; flex-wrap:wrap; padding:7px 10px; border-radius:var(--yb-r-sm); background:var(--yb-brand-subtle); font-size:var(--yb-fs-sm); color:var(--yb-ink-2); }
.cp-picked .money { font-family:var(--yb-font-mono); color:var(--yb-gold); }
.cp-empty-line { padding:14px 0; text-align:center; color:var(--yb-ink-4); font-size:var(--yb-fs-sm); }
.cp-chip { display:inline-flex; align-items:center; padding:0 7px; height:20px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-sm); line-height:1; background:var(--yb-surface-2); color:var(--yb-ink-3); border:1px solid var(--yb-border); white-space:nowrap; }
.cp-dim { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.cp-opt { display:flex; align-items:center; gap:10px; }
.cp-opt .nm { color:var(--yb-ink-1); }
.cp-opt .sub { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
`;

  function ensureStyle() {
    if (document.getElementById('clinical-pathway-css')) { return; }
    const style = document.createElement('style');
    style.id = 'clinical-pathway-css';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  function emptyTplForm() {
    return {
      pathwayCode: '', pathwayName: '', diseaseCode: '', diseaseName: '',
      deptId: null, avgLength: 7, totalCost: null, description: ''
    };
  }

  function emptyTaskForm() {
    return {
      taskType: 1, orderType: 1, orderCategory: 1,
      orderContent: '', spec: '', dosage: '', dosageUnit: '',
      usageCode: null, freqCode: null, quantity: 1, unitPrice: null,
      drugId: null, chargeItemId: null, isMandatory: 1
    };
  }

  /* 关键词搜索防抖计时器(模块级, 本视图单实例) */
  let searchTimer = null;

  const ClinicalPathwayManage = {
    name: 'ClinicalPathwayManage',
    data() {
      return {
        /* 左栏列表 */
        loadingList: false,
        templates: [],
        total: 0,
        page: 1,
        size: 20,
        keyword: '',
        filterStatus: null,
        /* 右侧详情 */
        selectedId: null,
        detailLoading: false,
        detail: null,
        collapsedNodes: {},
        /* 基础数据 */
        depts: [],
        usageOptions: [],
        freqOptions: [],
        dictLoaded: false,
        /* 模板对话框 */
        tplDialog: false,
        tplSaving: false,
        tplEditId: null,
        tplForm: emptyTplForm(),
        diagPickCode: null,
        diagOptions: [],
        diagSearching: false,
        /* 节点对话框 */
        nodeDialog: false,
        nodeSaving: false,
        nodeEditId: null,
        nodeForm: { dayNo: 1, nodeName: '', nodeDesc: '' },
        /* 任务对话框 */
        taskDialog: false,
        taskSaving: false,
        taskEditId: null,
        taskNode: null,
        taskForm: emptyTaskForm(),
        pickedDrug: null,
        pickedCharge: null,
        pickDrugId: null,
        pickChargeId: null,
        drugOptions: [],
        drugSearching: false,
        chargeOptions: [],
        chargeSearching: false,
        contentTouched: false
      };
    },
    computed: {
      currentTemplate() { return this.detail ? this.detail.template : null; },
      nodes() { return (this.detail && this.detail.nodes) || []; },
      maxDay() {
        return this.nodes.reduce((m, n) => Math.max(m, Number(n.dayNo) || 0), 0);
      },
      deptMap() {
        const map = {};
        this.depts.forEach(d => { map[d.id] = d.deptName; });
        return map;
      },
      usageMap() {
        const map = {};
        this.usageOptions.forEach(o => { map[o.code] = o.name; });
        return map;
      },
      freqMap() {
        const map = {};
        this.freqOptions.forEach(o => { map[o.code] = o.name; });
        return map;
      },
      isDrugTask() { return Number(this.taskForm.orderCategory) === 1; },
      taskNodeLabel() {
        return this.taskNode ? ('第' + this.taskNode.dayNo + '天 · ' + (this.taskNode.nodeName || '')) : '';
      }
    },
    created() {
      this.loadDepts();
      this.loadMedDict();
      this.loadTemplates(true);
    },
    methods: {
      text,
      money,
      statusText(v) { return PATHWAY_STATUS[v] == null ? '-' : PATHWAY_STATUS[v]; },
      taskTypeText(v) { return TASK_TYPE[v] || '-'; },
      orderTypeText(v) { return ORDER_TYPE[v] || '-'; },
      categoryText(v) { return ORDER_CATEGORY[v] || '-'; },
      deptName(id) { return this.deptMap[id] || (id == null ? '-' : '#' + id); },
      usageName(code) { return this.usageMap[code] || code || '-'; },
      freqName(code) { return this.freqMap[code] || code || '-'; },
      /* ===== 左栏: 列表 ===== */
      loadTemplates(immediate) {
        const vm = this;
        if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; }
        const run = function () {
          vm.loadingList = true;
          let url = '/api/his/pathway/template/list?page=' + vm.page + '&size=' + vm.size;
          const kw = String(vm.keyword || '').trim();
          if (kw) { url += '&keyword=' + encodeURIComponent(kw); }
          if (vm.filterStatus != null && vm.filterStatus !== '') { url += '&status=' + vm.filterStatus; }
          HIS.get(url)
            .then(function (data) {
              vm.templates = (data && data.records) || [];
              vm.total = Number(data && data.total) || vm.templates.length;
              /* 选中项若仍在列表中, 保持高亮; 首屏无选中且有数据时不自动选中(避免误改) */
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.loadingList = false; });
        };
        if (immediate) { run(); } else { searchTimer = setTimeout(run, 300); }
      },
      onPageChange(p) { this.page = p; this.loadTemplates(true); },
      selectTemplate(t) {
        if (String(this.selectedId) === String(t.id)) { return; }
        this.selectedId = t.id;
        this.collapsedNodes = {};
        this.loadDetail(t.id);
      },
      loadDetail(id) {
        const vm = this;
        const tid = id;
        vm.detailLoading = true;
        vm.detail = null;
        HIS.get('/api/his/pathway/template/' + encodeURIComponent(tid))
          .then(function (data) {
            if (String(vm.selectedId) !== String(tid)) { return; }
            vm.detail = data || null;
          })
          .catch(function (e) {
            if (String(vm.selectedId) === String(tid)) { HIS.notifyError(e); }
          })
          .finally(function () { vm.detailLoading = false; });
      },
      refreshAll() {
        this.loadTemplates(true);
        if (this.selectedId) { this.loadDetail(this.selectedId); }
      },
      loadDepts() {
        const vm = this;
        HIS.get('/api/his/dept/enabled')
          .then(function (list) { vm.depts = list || []; })
          .catch(function () { vm.depts = []; });
      },
      loadMedDict() {
        const vm = this;
        if (vm.dictLoaded) { return; }
        vm.dictLoaded = true;
        HIS.get('/api/org-catalog/available/med-dict?dictType=usage')
          .then(function (list) { vm.usageOptions = list || []; }).catch(function () { vm.usageOptions = []; });
        HIS.get('/api/org-catalog/available/med-dict?dictType=freq')
          .then(function (list) { vm.freqOptions = list || []; }).catch(function () { vm.freqOptions = []; });
      },
      isCollapsed(node) { return !!this.collapsedNodes[node.id]; },
      toggleNode(node) { this.collapsedNodes[node.id] = !this.collapsedNodes[node.id]; },
      /* ===== 模板: 新建/编辑/启停/复制 ===== */
      openTplCreate() {
        this.tplEditId = null;
        this.tplForm = emptyTplForm();
        this.diagPickCode = null;
        this.diagOptions = [];
        this.tplDialog = true;
      },
      openTplEdit() {
        const t = this.currentTemplate;
        if (!t) { return; }
        this.tplEditId = t.id;
        this.tplForm = {
          pathwayCode: t.pathwayCode || '',
          pathwayName: t.pathwayName || '',
          diseaseCode: t.diseaseCode || '',
          diseaseName: t.diseaseName || '',
          deptId: t.deptId,
          avgLength: t.avgLength != null ? t.avgLength : 7,
          totalCost: t.totalCost != null ? Number(t.totalCost) : null,
          description: t.description || ''
        };
        this.diagPickCode = t.diseaseCode || null;
        this.diagOptions = [];
        this.tplDialog = true;
      },
      remoteDiagSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.diagOptions = []; return; }
        vm.diagSearching = true;
        HIS.get('/api/community-dict/diag-dict/page?dictType=west&status=1&page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.diagOptions = (data && data.records) || []; })
          .catch(function () { vm.diagOptions = []; })
          .finally(function () { vm.diagSearching = false; });
      },
      onPickDiag(code) {
        const d = this.diagOptions.find(x => String(x.code) === String(code));
        if (!d) { return; }
        this.tplForm.diseaseCode = d.code || '';
        this.tplForm.diseaseName = d.name || '';
      },
      saveTpl() {
        const vm = this;
        if (vm.tplSaving) { return; }
        const name = String(vm.tplForm.pathwayName || '').trim();
        if (!name) { ElementPlus.ElMessage.warning('请填写路径名称'); return; }
        const f = vm.tplForm;
        const payload = {
          pathwayCode: String(f.pathwayCode || '').trim() || null,
          pathwayName: name,
          diseaseCode: String(f.diseaseCode || '').trim() || null,
          diseaseName: String(f.diseaseName || '').trim() || null,
          deptId: f.deptId != null ? Number(f.deptId) : null,
          avgLength: f.avgLength != null ? Number(f.avgLength) : null,
          totalCost: f.totalCost != null && f.totalCost !== '' ? Number(f.totalCost) : null,
          description: String(f.description || '').trim() || null
        };
        vm.tplSaving = true;
        const req = vm.tplEditId
          ? HIS.put('/api/his/pathway/template/' + encodeURIComponent(vm.tplEditId), payload)
          : HIS.post('/api/his/pathway/template', payload);
        req.then(function (t) {
          HIS.notifySuccess(vm.tplEditId ? '模板已更新' : '模板已创建: ' + (t && t.pathwayCode || name));
          vm.tplDialog = false;
          vm.loadTemplates(true);
          if (!vm.tplEditId && t && t.id) {
            vm.selectedId = t.id;
            vm.collapsedNodes = {};
            vm.loadDetail(t.id);
          } else if (vm.tplEditId) {
            vm.loadDetail(vm.tplEditId);
          }
        }).catch(HIS.notifyError).finally(function () { vm.tplSaving = false; });
      },
      toggleStatus() {
        const vm = this;
        const t = vm.currentTemplate;
        if (!t) { return; }
        const next = Number(t.status) === 1 ? '停用' : '启用';
        ElementPlus.ElMessageBox.confirm('确认' + next + '路径模板「' + t.pathwayName + '」？' + (next === '停用' ? '停用后不可用于新入径, 已在径患者不受影响。' : ''), next + '确认', {
          type: 'warning', confirmButtonText: next, cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/pathway/template/' + encodeURIComponent(t.id) + '/status');
        }).then(function () {
          HIS.notifySuccess('模板已' + next);
          vm.refreshAll();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      copyTemplate() {
        const vm = this;
        const t = vm.currentTemplate;
        if (!t) { return; }
        ElementPlus.ElMessageBox.confirm('将深拷贝「' + t.pathwayName + '」的全部节点与任务并升级版本(编码追加 -vN, 新版本默认启用), 确认复制？', '复制新版本', {
          type: 'info', confirmButtonText: '复制', cancelButtonText: '取消'
        }).then(function () {
          return HIS.post('/api/his/pathway/template/' + encodeURIComponent(t.id) + '/copy', {});
        }).then(function (n) {
          HIS.notifySuccess('已复制为 V' + (n.version || '?') + ' 新版本: ' + (n.pathwayCode || ''));
          vm.selectedId = n.id;
          vm.collapsedNodes = {};
          vm.loadTemplates(true);
          vm.loadDetail(n.id);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 节点: 添加天数/编辑/删除 ===== */
      openNodeCreate() {
        const day = this.maxDay + 1;
        this.nodeEditId = null;
        this.nodeForm = { dayNo: day, nodeName: '第' + day + '天', nodeDesc: '' };
        this.nodeDialog = true;
      },
      openNodeEdit(node) {
        this.nodeEditId = node.id;
        this.nodeForm = {
          dayNo: node.dayNo,
          nodeName: node.nodeName || '',
          nodeDesc: node.nodeDesc || ''
        };
        this.nodeDialog = true;
      },
      saveNode() {
        const vm = this;
        if (vm.nodeSaving) { return; }
        const t = vm.currentTemplate;
        if (!t) { return; }
        const dayNo = Number(vm.nodeForm.dayNo);
        if (!dayNo || dayNo < 1) { ElementPlus.ElMessage.warning('第X天必须为不小于1的整数'); return; }
        const payload = {
          templateId: t.id,
          dayNo: dayNo,
          nodeName: String(vm.nodeForm.nodeName || '').trim() || ('第' + dayNo + '天'),
          nodeDesc: String(vm.nodeForm.nodeDesc || '').trim() || null,
          sortNo: 0
        };
        vm.nodeSaving = true;
        const req = vm.nodeEditId
          ? HIS.put('/api/his/pathway/node/' + encodeURIComponent(vm.nodeEditId), payload)
          : HIS.post('/api/his/pathway/node', payload);
        req.then(function () {
          HIS.notifySuccess(vm.nodeEditId ? '节点已更新' : '已添加第' + dayNo + '天节点');
          vm.nodeDialog = false;
          vm.loadDetail(t.id);
        }).catch(HIS.notifyError).finally(function () { vm.nodeSaving = false; });
      },
      removeNode(node) {
        const vm = this;
        const t = vm.currentTemplate;
        if (!t) { return; }
        ElementPlus.ElMessageBox.confirm('确认删除「第' + node.dayNo + '天 · ' + (node.nodeName || '') + '」节点及其下全部任务？已入径患者的执行记录不受影响。', '删除节点', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/pathway/node/' + encodeURIComponent(node.id));
        }).then(function () {
          HIS.notifySuccess('节点已删除');
          vm.loadDetail(t.id);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* ===== 任务: 添加/编辑/删除 ===== */
      openTaskCreate(node) {
        this.taskEditId = null;
        this.taskNode = node;
        this.taskForm = emptyTaskForm();
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.contentTouched = false;
        this.taskDialog = true;
        this.loadMedDict();
      },
      openTaskEdit(node, task) {
        this.taskEditId = task.id;
        this.taskNode = node;
        this.taskForm = {
          taskType: task.taskType != null ? task.taskType : 1,
          orderType: task.orderType != null ? task.orderType : 1,
          orderCategory: task.orderCategory != null ? task.orderCategory : 1,
          orderContent: task.orderContent || '',
          spec: task.spec || '',
          dosage: task.dosage || '',
          dosageUnit: task.dosageUnit || '',
          usageCode: task.usageCode || null,
          freqCode: task.freqCode || null,
          quantity: task.quantity != null ? Number(task.quantity) : 1,
          unitPrice: task.unitPrice != null ? Number(task.unitPrice) : null,
          drugId: task.drugId || null,
          chargeItemId: task.chargeItemId || null,
          isMandatory: Number(task.isMandatory) === 1 ? 1 : 0
        };
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.contentTouched = true;
        this.taskDialog = true;
        this.loadMedDict();
      },
      remoteDrugSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.drugOptions = []; return; }
        vm.drugSearching = true;
        HIS.get('/api/org-catalog/available/drug?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.drugOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.drugSearching = false; });
      },
      onPickDrug(id) {
        const vm = this;
        vm.pickDrugId = null;
        const drug = vm.drugOptions.find(d => String(d.id) === String(id));
        if (!drug) { return; }
        vm.pickedDrug = drug;
        vm.taskForm.drugId = drug.id;
        vm.taskForm.spec = drug.spec || '';
        if (!vm.taskForm.dosageUnit) { vm.taskForm.dosageUnit = drug.doseUnit || drug.minUnit || ''; }
        if (drug.retailPrice != null) { vm.taskForm.unitPrice = Number(drug.retailPrice); }
        if (!vm.contentTouched) { vm.taskForm.orderContent = vm.composeDrugContent(); }
      },
      remoteChargeSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.chargeOptions = []; return; }
        vm.chargeSearching = true;
        HIS.get('/api/org-catalog/available/charge?page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.chargeOptions = (data && data.records) || []; })
          .catch(HIS.notifyError)
          .finally(function () { vm.chargeSearching = false; });
      },
      onPickCharge(id) {
        const vm = this;
        vm.pickChargeId = null;
        const item = vm.chargeOptions.find(x => String(x.id) === String(id));
        if (!item) { return; }
        vm.pickedCharge = item;
        vm.taskForm.chargeItemId = item.id;
        vm.taskForm.spec = item.spec || '';
        const price = item.execPrice != null ? item.execPrice : item.price;
        if (price != null) { vm.taskForm.unitPrice = Number(price); }
        if (!vm.contentTouched) { vm.taskForm.orderContent = item.itemName || ''; }
      },
      composeDrugContent() {
        const f = this.taskForm;
        const d = this.pickedDrug;
        if (!d) { return f.orderContent || ''; }
        let txt = d.genericName || d.itemName || '';
        if (f.dosage) { txt += ' ' + f.dosage + (f.dosageUnit || ''); }
        const usage = this.usageMap[f.usageCode];
        if (usage) { txt += ' ' + usage; }
        const freq = this.freqMap[f.freqCode];
        if (freq) { txt += ' ' + freq; }
        return txt;
      },
      autoCompose() {
        if (this.isDrugTask) { this.taskForm.orderContent = this.composeDrugContent(); }
        else if (this.pickedCharge) { this.taskForm.orderContent = this.pickedCharge.itemName || ''; }
        this.contentTouched = false;
      },
      onContentInput() { this.contentTouched = true; },
      onCategoryChange() {
        this.pickedDrug = null;
        this.pickedCharge = null;
        this.pickDrugId = null;
        this.pickChargeId = null;
        this.taskForm.drugId = null;
        this.taskForm.chargeItemId = null;
        this.taskForm.spec = '';
        this.taskForm.dosage = '';
        this.taskForm.usageCode = null;
        this.taskForm.freqCode = null;
        this.taskForm.unitPrice = null;
        this.contentTouched = false;
      },
      saveTask() {
        const vm = this;
        if (vm.taskSaving) { return; }
        const node = vm.taskNode;
        const tpl = vm.currentTemplate;
        if (!node || !tpl) { return; }
        const f = vm.taskForm;
        if (vm.isDrugTask && !f.drugId) {
          ElementPlus.ElMessage.warning('药品类任务请先检索并选中药品');
          return;
        }
        let content = String(f.orderContent || '').trim();
        if (!content && vm.isDrugTask) { content = vm.composeDrugContent(); }
        if (!content) { ElementPlus.ElMessage.warning('请填写医嘱内容(或检索选中项目后自动生成)'); return; }
        if (!f.quantity || Number(f.quantity) <= 0) { ElementPlus.ElMessage.warning('数量必须大于 0'); return; }
        const payload = {
          nodeId: node.id,
          templateId: tpl.id,
          taskType: Number(f.taskType) || 1,
          orderType: Number(f.orderType) || 1,
          orderCategory: Number(f.orderCategory) || 1,
          orderContent: content,
          drugId: vm.isDrugTask ? f.drugId : null,
          chargeItemId: vm.isDrugTask ? null : (f.chargeItemId || null),
          spec: f.spec || null,
          dosage: vm.isDrugTask ? (f.dosage || null) : null,
          dosageUnit: vm.isDrugTask ? (f.dosageUnit || null) : null,
          usageCode: vm.isDrugTask ? (f.usageCode || null) : null,
          freqCode: vm.isDrugTask ? (f.freqCode || null) : null,
          quantity: Number(f.quantity) || 1,
          unitPrice: f.unitPrice != null && f.unitPrice !== '' ? Number(f.unitPrice) : null,
          isMandatory: Number(f.isMandatory) === 1 ? 1 : 0,
          sortNo: 0
        };
        vm.taskSaving = true;
        const req = vm.taskEditId
          ? HIS.put('/api/his/pathway/task/' + encodeURIComponent(vm.taskEditId), payload)
          : HIS.post('/api/his/pathway/task', payload);
        req.then(function () {
          HIS.notifySuccess(vm.taskEditId ? '任务已更新' : '任务已添加');
          vm.taskDialog = false;
          vm.loadDetail(tpl.id);
        }).catch(HIS.notifyError).finally(function () { vm.taskSaving = false; });
      },
      removeTask(node, task) {
        const vm = this;
        const tpl = vm.currentTemplate;
        if (!tpl) { return; }
        ElementPlus.ElMessageBox.confirm('确认删除任务「' + (task.orderContent || '') + '」？', '删除任务', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/pathway/task/' + encodeURIComponent(task.id));
        }).then(function () {
          HIS.notifySuccess('任务已删除');
          vm.loadDetail(tpl.id);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      }
    },
    template: `
      <div class="cp-workbench">
        <aside class="cp-side">
          <div class="cp-side-head">
            <div class="cp-side-tools">
              <el-input class="grow" v-model="keyword" placeholder="路径 / 诊断名称" size="small" clearable
                        @input="loadTemplates(false)" @keyup.enter="loadTemplates(true)"></el-input>
              <el-select v-model="filterStatus" placeholder="全部" size="small" clearable style="width:88px" @change="loadTemplates(true)">
                <el-option label="启用" :value="1"></el-option>
                <el-option label="停用" :value="0"></el-option>
              </el-select>
              <el-button size="small" type="primary" @click="openTplCreate">新建</el-button>
            </div>
          </div>
          <el-scrollbar class="cp-side-body" v-loading="loadingList">
            <div v-for="t in templates" :key="t.id" class="cp-tpl"
                 :class="{'is-active': String(selectedId) === String(t.id)}" @click="selectTemplate(t)">
              <div class="r1">
                <b class="nm" :title="t.pathwayName">{{ t.pathwayName }}</b>
                <el-tag size="small" :type="Number(t.status) === 1 ? 'success' : 'info'" disable-transitions>{{ statusText(t.status) }}</el-tag>
              </div>
              <div class="r2" :title="t.diseaseName">诊断: {{ t.diseaseName || '未绑定' }}{{ t.diseaseCode ? ' (' + t.diseaseCode + ')' : '' }}</div>
              <div class="r3">
                <span class="code" :title="t.pathwayCode">{{ t.pathwayCode }}</span>
                <span>V{{ t.version || 1 }}</span>
                <span>住院日 {{ t.avgLength != null ? t.avgLength : '-' }}</span>
                <span class="money" v-if="t.totalCost != null">¥{{ money(t.totalCost) }}</span>
              </div>
            </div>
            <div v-if="!templates.length && !loadingList" class="cp-empty-line">暂无路径模板, 点上方「新建」创建</div>
          </el-scrollbar>
          <div class="cp-side-pager" v-if="total > size">
            <el-pagination small background layout="prev, pager, next" :total="total" :page-size="size"
                           :current-page="page" @current-change="onPageChange"></el-pagination>
          </div>
        </aside>

        <section class="cp-main">
          <div v-if="!selectedId" class="cp-empty">
            <span class="big">临床路径模板管理</span>
            <span>左侧选择模板查看/编辑其逐日节点与任务, 或新建一个病种入径标准</span>
            <span>模板定义: 适用诊断 + 平均住院日 + 第1~N天节点 + 每天任务(自动转医嘱)</span>
          </div>
          <div v-else class="cp-detail" v-loading="detailLoading">
            <template v-if="currentTemplate">
              <div class="cp-head">
                <div class="cp-head-main">
                  <div class="line1">
                    <span class="nm">{{ currentTemplate.pathwayName }}</span>
                    <el-tag size="small" :type="Number(currentTemplate.status) === 1 ? 'success' : 'info'" disable-transitions>{{ statusText(currentTemplate.status) }}</el-tag>
                    <span class="cp-chip">V{{ currentTemplate.version || 1 }}</span>
                  </div>
                  <div class="line2">
                    <span>编码 <b>{{ currentTemplate.pathwayCode || '-' }}</b></span>
                    <span>诊断 <b>{{ currentTemplate.diseaseName || '-' }}</b>{{ currentTemplate.diseaseCode ? ' (' + currentTemplate.diseaseCode + ')' : '' }}</span>
                    <span>科室 <b>{{ deptName(currentTemplate.deptId) }}</b></span>
                    <span>平均住院日 <b>{{ currentTemplate.avgLength != null ? currentTemplate.avgLength : '-' }}</b> 天</span>
                    <span>预估费用 <b class="money">¥{{ money(currentTemplate.totalCost) }}</b></span>
                    <span>节点 <b>{{ nodes.length }}</b> 天</span>
                  </div>
                  <div class="line3" v-if="currentTemplate.description">说明: {{ currentTemplate.description }}</div>
                </div>
                <div class="cp-head-ops">
                  <div class="row">
                    <el-button size="small" @click="openTplEdit">编辑信息</el-button>
                    <el-button size="small" :type="Number(currentTemplate.status) === 1 ? 'warning' : 'success'" plain @click="toggleStatus">{{ Number(currentTemplate.status) === 1 ? '停用' : '启用' }}</el-button>
                    <el-button size="small" @click="copyTemplate">复制新版本</el-button>
                  </div>
                  <span class="cp-dim">节点任务修改立即生效; 已入径患者按旧快照执行</span>
                </div>
              </div>

              <div class="cp-body">
                <div class="cp-timeline">
                  <div class="cp-node" v-for="n in nodes" :key="n.id">
                    <div class="cp-node-head">
                      <span class="day">第{{ n.dayNo }}天</span>
                      <span class="nm">{{ n.nodeName }}</span>
                      <span class="desc" v-if="n.nodeDesc" :title="n.nodeDesc">{{ n.nodeDesc }}</span>
                      <span class="count">任务 {{ (n.tasks || []).length }}</span>
                      <span class="ops">
                        <el-button link size="small" @click="toggleNode(n)">{{ isCollapsed(n) ? '展开' : '收起' }}</el-button>
                        <el-button link type="primary" size="small" @click="openNodeEdit(n)">编辑</el-button>
                        <el-button link type="danger" size="small" @click="removeNode(n)">删除</el-button>
                      </span>
                    </div>
                    <div class="cp-node-tasks" v-show="!isCollapsed(n)">
                      <el-table :data="n.tasks || []" size="small" border>
                        <el-table-column label="任务类型" width="80" align="center">
                          <template #default="s"><el-tag size="small" effect="plain" disable-transitions>{{ taskTypeText(s.row.taskType) }}</el-tag></template>
                        </el-table-column>
                        <el-table-column prop="orderContent" label="内容" min-width="200" show-overflow-tooltip></el-table-column>
                        <el-table-column label="分类" width="108">
                          <template #default="s">{{ categoryText(s.row.orderCategory) }}<span style="color:var(--yb-ink-4)"> · {{ orderTypeText(s.row.orderType) }}</span></template>
                        </el-table-column>
                        <el-table-column label="用法" width="88" show-overflow-tooltip>
                          <template #default="s">{{ s.row.usageCode ? usageName(s.row.usageCode) : '-' }}</template>
                        </el-table-column>
                        <el-table-column label="频次" width="88" show-overflow-tooltip>
                          <template #default="s">{{ s.row.freqCode ? freqName(s.row.freqCode) : '-' }}</template>
                        </el-table-column>
                        <el-table-column label="必做" width="66" align="center">
                          <template #default="s">
                            <el-tag size="small" :type="Number(s.row.isMandatory) === 1 ? 'danger' : 'info'" effect="plain" disable-transitions>{{ Number(s.row.isMandatory) === 1 ? '必做' : '可选' }}</el-tag>
                          </template>
                        </el-table-column>
                        <el-table-column label="操作" width="104" align="center" fixed="right">
                          <template #default="s">
                            <el-button link type="primary" size="small" @click="openTaskEdit(n, s.row)">编辑</el-button>
                            <el-button link type="danger" size="small" @click="removeTask(n, s.row)">删除</el-button>
                          </template>
                        </el-table-column>
                        <template #empty><div class="cp-empty-line">该天暂无任务, 点下方「添加任务」配置</div></template>
                      </el-table>
                      <el-button class="cp-node-add" size="small" type="primary" plain @click="openTaskCreate(n)">+ 添加任务</el-button>
                    </div>
                  </div>

                  <div class="cp-add-day">
                    <el-button size="small" type="primary" plain @click="openNodeCreate">+ 添加天数</el-button>
                    <span class="cp-dim" style="margin-left:8px">当前已配置 {{ nodes.length }} 天, 新节点默认追加为第{{ maxDay + 1 }}天</span>
                  </div>
                </div>
              </div>
            </template>
            <div v-else-if="!detailLoading" class="cp-empty">
              <span>模板加载失败或已删除, 请从左侧重新选择</span>
            </div>
          </div>
        </section>

        <!-- 模板基本信息对话框 -->
        <el-dialog v-model="tplDialog" :title="tplEditId ? '编辑路径模板' : '新建路径模板'" width="640px" top="6vh" :close-on-click-modal="false">
          <el-form label-width="92px" size="small">
            <div class="cp-form-grid">
              <el-form-item label="路径编码"><el-input v-model="tplForm.pathwayCode" placeholder="留空自动生成(如 PATH001)"></el-input></el-form-item>
              <el-form-item label="路径名称" required><el-input v-model="tplForm.pathwayName" placeholder="如: 社区获得性肺炎(成人)"></el-input></el-form-item>
            </div>
            <el-form-item label="诊断检索">
              <el-select v-model="diagPickCode" filterable remote reserve-keyword clearable style="width:100%"
                         :remote-method="remoteDiagSearch" :loading="diagSearching"
                         placeholder="ICD-10 检索: 编码 / 名称 / 拼音简码, 选中回填诊断编码与名称" @change="onPickDiag">
                <el-option v-for="d in diagOptions" :key="d.id || d.code" :label="(d.code || '') + ' ' + (d.name || '')" :value="d.code">
                  <div class="cp-opt"><span class="nm">{{ d.name }}</span><span class="sub">{{ d.code }}</span></div>
                </el-option>
              </el-select>
            </el-form-item>
            <div class="cp-form-grid">
              <el-form-item label="诊断编码"><el-input v-model="tplForm.diseaseCode" placeholder="ICD-10 编码"></el-input></el-form-item>
              <el-form-item label="诊断名称"><el-input v-model="tplForm.diseaseName" placeholder="可检索回填或手填"></el-input></el-form-item>
              <el-form-item label="适用科室">
                <el-select v-model="tplForm.deptId" filterable clearable placeholder="选择科室" style="width:100%">
                  <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="平均住院日"><el-input-number v-model="tplForm.avgLength" :min="1" :max="365" controls-position="right" style="width:130px"></el-input-number></el-form-item>
              <el-form-item label="预估费用">
                <el-input-number v-model="tplForm.totalCost" :min="0" :precision="2" :step="100" controls-position="right" style="width:170px"></el-input-number>
                <span class="cp-dim" style="margin-left:6px">元</span>
              </el-form-item>
            </div>
            <el-form-item label="路径描述"><el-input v-model="tplForm.description" type="textarea" :rows="3" placeholder="路径适用条件 / 入径标准 / 变异口径等说明(选填)"></el-input></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="tplDialog = false">取消</el-button>
            <el-button type="primary" :loading="tplSaving" @click="saveTpl">保存</el-button>
          </template>
        </el-dialog>

        <!-- 节点对话框 -->
        <el-dialog v-model="nodeDialog" :title="nodeEditId ? '编辑节点' : '添加天数'" width="480px" :close-on-click-modal="false">
          <el-form label-width="86px" size="small">
            <el-form-item label="第X天" required>
              <el-input-number v-model="nodeForm.dayNo" :min="1" :max="365" controls-position="right" style="width:130px"></el-input-number>
              <span class="cp-dim" style="margin-left:8px">同一天可添加多个节点(如"上午/下午")</span>
            </el-form-item>
            <el-form-item label="节点名称"><el-input v-model="nodeForm.nodeName" placeholder="默认: 第X天, 如: 入院首日 / 术前日"></el-input></el-form-item>
            <el-form-item label="节点描述"><el-input v-model="nodeForm.nodeDesc" type="textarea" :rows="2" placeholder="该天诊疗重点说明(选填)"></el-input></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="nodeDialog = false">取消</el-button>
            <el-button type="primary" :loading="nodeSaving" @click="saveNode">保存</el-button>
          </template>
        </el-dialog>

        <!-- 任务对话框 -->
        <el-dialog v-model="taskDialog" :title="taskEditId ? '编辑任务' : '添加任务'" width="720px" top="5vh" :close-on-click-modal="false">
          <el-form label-width="86px" size="small">
            <div class="cp-form-grid">
              <el-form-item label="所属节点"><span style="color:var(--yb-brand);font-weight:600">{{ taskNodeLabel }}</span></el-form-item>
              <el-form-item label="任务类型">
                <el-select v-model="taskForm.taskType" style="width:100%">
                  <el-option v-for="(label, key) in {1:'医嘱',2:'护理',3:'检查',4:'检验',5:'宣教'}" :key="key" :label="label" :value="Number(key)"></el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="医嘱类型">
                <el-radio-group v-model="taskForm.orderType">
                  <el-radio-button :label="1">长期</el-radio-button>
                  <el-radio-button :label="2">临时</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="医嘱分类">
                <el-radio-group v-model="taskForm.orderCategory" @change="onCategoryChange">
                  <el-radio-button v-for="(label, key) in {1:'药品',2:'检查',3:'检验',4:'治疗',5:'护理',6:'膳食',7:'其他'}" :key="key" :label="Number(key)">{{ label }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </div>

            <el-form-item v-if="isDrugTask" label="药品检索">
              <el-select class="grow" style="width:100%" v-model="pickDrugId" filterable remote reserve-keyword clearable
                         :remote-method="remoteDrugSearch" :loading="drugSearching"
                         placeholder="通用名 / 编码 / 拼音简码, 选中带出规格与单价" @change="onPickDrug">
                <el-option v-for="d in drugOptions" :key="d.id" :label="(d.genericName || '') + ' ' + (d.spec || '')" :value="d.id">
                  <div class="cp-opt">
                    <span class="nm">{{ d.genericName }}</span>
                    <span class="sub">{{ d.spec }}</span>
                    <span class="sub" style="font-family:var(--yb-font-mono);color:var(--yb-gold)">¥{{ money(d.retailPrice) }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
            <el-form-item v-else label="项目检索">
              <el-select class="grow" style="width:100%" v-model="pickChargeId" filterable remote reserve-keyword clearable
                         :remote-method="remoteChargeSearch" :loading="chargeSearching"
                         placeholder="收费项目名称 / 编码, 选中带出执行价(也可留空填纯文字任务)" @change="onPickCharge">
                <el-option v-for="c in chargeOptions" :key="c.id" :label="(c.itemName || '') + ' ' + (c.spec || '')" :value="c.id">
                  <div class="cp-opt">
                    <span class="nm">{{ c.itemName }}</span>
                    <span class="sub">{{ c.itemCode }}</span>
                    <span class="sub" style="font-family:var(--yb-font-mono);color:var(--yb-gold)">¥{{ money(c.execPrice != null ? c.execPrice : c.price) }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>

            <div class="cp-picked" v-if="isDrugTask && pickedDrug" style="margin:0 0 14px">
              <span>规格 <b>{{ pickedDrug.spec || '-' }}</b></span>
              <span>零售价 <b class="money">{{ money(pickedDrug.retailPrice) }}</b> 元/{{ pickedDrug.minUnit || '单位' }}</span>
              <span>剂型 {{ pickedDrug.dosformName || pickedDrug.dosform || '-' }}</span>
            </div>
            <div class="cp-picked" v-if="!isDrugTask && pickedCharge" style="margin:0 0 14px">
              <span>编码 <b>{{ pickedCharge.itemCode || '-' }}</b></span>
              <span>执行价 <b class="money">{{ money(pickedCharge.execPrice != null ? pickedCharge.execPrice : pickedCharge.price) }}</b> 元/{{ pickedCharge.unit || '次' }}</span>
            </div>

            <div class="cp-form-grid" v-if="isDrugTask">
              <el-form-item label="规格"><el-input v-model="taskForm.spec" placeholder="选中药品自动带出"></el-input></el-form-item>
              <el-form-item label="剂量">
                <el-input v-model="taskForm.dosage" style="width:110px" placeholder="如 0.5"></el-input>
                <el-input v-model="taskForm.dosageUnit" style="width:96px;margin-left:8px" placeholder="单位"></el-input>
              </el-form-item>
              <el-form-item label="用法">
                <el-select v-model="taskForm.usageCode" clearable filterable style="width:100%" placeholder="用法" @change="!contentTouched && autoCompose()">
                  <el-option v-for="u in usageOptions" :key="u.code" :label="u.name" :value="u.code"></el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="频次">
                <el-select v-model="taskForm.freqCode" clearable filterable style="width:100%" placeholder="频次" @change="!contentTouched && autoCompose()">
                  <el-option v-for="f in freqOptions" :key="f.code" :label="f.name" :value="f.code"></el-option>
                </el-select>
              </el-form-item>
            </div>

            <el-form-item label="医嘱内容">
              <el-input v-model="taskForm.orderContent" placeholder="自动拼接(药品: 名称+剂量+用法+频次)或手动输入" @input="onContentInput">
                <template #append><el-button @click="autoCompose">自动生成</el-button></template>
              </el-input>
            </el-form-item>
            <div class="cp-form-grid">
              <el-form-item label="数量"><el-input-number v-model="taskForm.quantity" :min="0.01" :step="1" :precision="2" controls-position="right" style="width:150px"></el-input-number></el-form-item>
              <el-form-item label="是否必做">
                <el-switch v-model="taskForm.isMandatory" :active-value="1" :inactive-value="0" active-text="必做" inactive-text="可选"></el-switch>
                <span class="cp-dim" style="margin-left:8px">必做任务由「执行当天任务」自动转医嘱; 可选任务人工处理</span>
              </el-form-item>
            </div>
          </el-form>
          <template #footer>
            <el-button @click="taskDialog = false">取消</el-button>
            <el-button type="primary" :loading="taskSaving" @click="saveTask">保存</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.views.ClinicalPathwayManage = ClinicalPathwayManage;
})();
