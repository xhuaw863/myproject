/* 药库管理系统升级 批次D: 药品养护(手动/自动/模板) + 养护模板 + 库房月结 + 账簿查询(收发存/财务/保管员)
 * 后端: /api/warehouse/maintenance/*, /api/warehouse/month-end/*, /api/warehouse/report/*
 * 读: 后端 scopeOrgId 机构隔离; 写: requireLeadWrite 仅牵头机构管理员, 前端按 lead 隐藏写按钮并以后端403兜底。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 枚举/辅助 ===== */
  var MNT_TYPE = [{ v: 1, l: '手动', t: 'info' }, { v: 2, l: '自动', t: 'primary' }, { v: 3, l: '模板', t: 'warning' }];
  var MNT_STATUS = [{ v: 0, l: '草稿', t: 'info' }, { v: 1, l: '已完成', t: 'success' }];
  var MNT_RESULT = [{ v: 1, l: '合格', t: 'success' }, { v: 2, l: '异常', t: 'danger' }];
  var ACCT_STD = [{ v: 1, l: '进价', t: 'primary' }, { v: 3, l: '零售价', t: 'warning' }];
  var ME_STATUS = [{ v: 1, l: '已月结', t: 'success' }, { v: 0, l: '进行中', t: 'warning' }, { v: -1, l: '已取消', t: 'info' }];
  function stLabel(list, v) { for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].l; } } return (v === null || v === undefined) ? '-' : v; }
  function stTag(list, v) { for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i].t; } } return 'info'; }
  function mtLabel(v) { return stLabel(MNT_TYPE, v); } function mtTag(v) { return stTag(MNT_TYPE, v); }
  function mnStatusLabel(v) { return stLabel(MNT_STATUS, v); } function mnStatusTag(v) { return stTag(MNT_STATUS, v); }
  function rsLabel(v) { return stLabel(MNT_RESULT, v); } function rsTag(v) { return stTag(MNT_RESULT, v); }
  function stdLabel(v) { return stLabel(ACCT_STD, v); }
  function meStatusLabel(v) { return stLabel(ME_STATUS, v); } function meStatusTag(v) { return stTag(ME_STATUS, v); }
  function money(v) { if (v === null || v === undefined || v === '') { return '-'; } var n = Number(v); return isNaN(n) ? v : n.toFixed(2); }
  function qty(v) { if (v === null || v === undefined || v === '') { return '-'; } return String(Number(v)); }
  function dt(v) { return v ? String(v).replace('T', ' ').substring(0, 16) : '-'; }
  function loadWarehouses(vm) { return HIS.get('/api/his/stock/warehouse-def').then(function (d) { vm.warehouses = d || []; }).catch(HIS.notifyError); }

  /* ==================== 药品养护模板 ==================== */
  HIS.views.MaintenanceTemplate = {
    data: function () {
      return { lead: HIS.isLead(), warehouses: [], loading: false, list: [], dlg: false, saving: false, isNew: false, form: {} };
    },
    created: function () { this.load(); loadWarehouses(this); },
    methods: {
      mtLabel: mtLabel, money: money, whName: function (id) { if (!id) { return '全部'; } for (var i = 0; i < this.warehouses.length; i++) { if (this.warehouses[i].id === id) { return this.warehouses[i].name; } } return '库#' + id; },
      load: function () { var vm = this; vm.loading = true; HIS.get('/api/warehouse/maintenance/template/list').then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; }); },
      openCreate: function () { this.form = { templateName: '', warehouseId: null, dosform: '', storageCond: '', drugKeyword: '', defaultMeasure: '外观检查/包装完整性/效期核查', status: 1, remark: '' }; this.isNew = true; this.dlg = true; },
      openEdit: function (row) { this.form = JSON.parse(JSON.stringify(row)); this.isNew = false; this.dlg = true; },
      save: function () { var vm = this; if (!vm.form.templateName) { ElementPlus.ElMessage.warning('请填写模板名称'); return; } vm.saving = true; HIS.post('/api/warehouse/maintenance/template', vm.form).then(function () { HIS.notifySuccess('已保存'); vm.dlg = false; vm.load(); }).catch(HIS.notifyError).finally(function () { vm.saving = false; }); },
      del: function (row) { var vm = this; ElementPlus.ElMessageBox.confirm('删除模板 ' + row.templateName + '?', '删除', { type: 'warning' }).then(function () { HIS.del('/api/warehouse/maintenance/template/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); }).catch(HIS.notifyError); }).catch(function () { }); }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">养护模板 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(可复用筛选条件+默认养护措施, 供"按模板建单")</span></div>',
      '  <div class="toolbar"><el-button v-if="lead" type="primary" @click="openCreate">新建模板</el-button><el-button @click="load">刷新</el-button></div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column prop="templateName" label="模板名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="适用药库" width="120"><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column prop="dosform" label="剂型" width="100"><template #default="s">{{ s.row.dosform || "-" }}</template></el-table-column>',
      '    <el-table-column prop="drugKeyword" label="关键字" width="120"><template #default="s">{{ s.row.drugKeyword || "-" }}</template></el-table-column>',
      '    <el-table-column prop="defaultMeasure" label="默认养护措施" min-width="180" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="lead" label="操作" width="120"><template #default="s"><el-button link type="primary" @click="openEdit(s.row)">编辑</el-button><el-button link type="danger" @click="del(s.row)">删除</el-button></template></el-table-column>',
      '  </el-table>',
      '  <el-dialog v-model="dlg" :title="isNew?\'新建养护模板\':\'编辑养护模板\'" width="560px">',
      '    <el-form :model="form" label-width="100px" size="small">',
      '      <el-form-item label="模板名称" required><el-input v-model="form.templateName"></el-input></el-form-item>',
      '      <el-form-item label="适用药库"><el-select v-model="form.warehouseId" clearable placeholder="空=全部" style="width:100%;"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="剂型"><el-input v-model="form.dosform" placeholder="如 片剂/注射剂"></el-input></el-form-item>',
      '      <el-form-item label="储存条件"><el-input v-model="form.storageCond" placeholder="如 阴凉/冷藏"></el-input></el-form-item>',
      '      <el-form-item label="药品关键字"><el-input v-model="form.drugKeyword" placeholder="名称/编码"></el-input></el-form-item>',
      '      <el-form-item label="默认措施"><el-input type="textarea" v-model="form.defaultMeasure" :rows="2"></el-input></el-form-item>',
      '      <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0"></el-switch></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="form.remark"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 药品养护(手动/自动/模板) ==================== */
  HIS.views.DrugMaintenance = {
    data: function () {
      return {
        lead: HIS.isLead(), warehouses: [], templates: [],
        loading: false, list: [], total: 0, page: 1, size: 20, filterStatus: null,
        createDlg: false, saving: false, mode: 1,
        form: { warehouseId: null, mntDate: '', templateId: null, dosform: '', drugKeyword: '', remark: '' },
        viewDlg: false, viewLoading: false, editing: false, view: { main: {}, items: [] },
        drugDlg: false, drugKeyword: '', drugList: [], drugLoading: false, drugTotal: 0, drugPage: 1, drugSize: 20, pendingIdx: null
      };
    },
    created: function () { this.load(); loadWarehouses(this); this.loadTemplates(); },
    methods: {
      mtLabel: mtLabel, mtTag: mtTag, mnStatusLabel: mnStatusLabel, mnStatusTag: mnStatusTag, rsLabel: rsLabel, rsTag: rsTag, money: money, qty: qty, dt: dt,
      whName: function (id) { if (!id) { return '-'; } for (var i = 0; i < this.warehouses.length; i++) { if (this.warehouses[i].id === id) { return this.warehouses[i].name; } } return '库#' + id; },
      loadTemplates: function () { var vm = this; HIS.get('/api/warehouse/maintenance/template/list').then(function (d) { vm.templates = d || []; }).catch(HIS.notifyError); },
      load: function () {
        var vm = this; vm.loading = true;
        var url = '/api/warehouse/maintenance/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.filterStatus !== null && vm.filterStatus !== '') { url += '&status=' + vm.filterStatus; }
        HIS.get(url).then(function (d) { vm.list = (d && d.records) || []; vm.total = Number((d && d.total) || 0); }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      openCreate: function () { this.mode = 1; this.form = { warehouseId: null, mntDate: '', templateId: null, dosform: '', drugKeyword: '', remark: '' }; this.view = { main: {}, items: [this.emptyItem()] }; this.editing = true; this.viewDlg = true; },
      switchMode: function (m) { this.mode = m; if (m === 1) { this.view = { main: {}, items: [this.emptyItem()] }; } else { this.view = { main: {}, items: [] }; } },
      emptyItem: function () { return { drugCatalogId: null, drugCode: '', drugName: '', spec: '', batchNo: '', manufacturer: '', dosform: '', qty: null, expDate: '', measure: '', result: 1, conclusion: '' }; },
      createByMode: function () {
        var vm = this;
        var payload = { mntType: vm.mode, mntDate: vm.form.mntDate || null, warehouseId: vm.form.warehouseId || null, remark: vm.form.remark || null };
        if (vm.mode === 1) { payload.items = vm.view.items; }
        else if (vm.mode === 2) { payload.dosform = vm.form.dosform || null; payload.drugKeyword = vm.form.drugKeyword || null; }
        else { if (!vm.form.templateId) { ElementPlus.ElMessage.warning('请选择模板'); return; } payload.templateId = vm.form.templateId; }
        vm.saving = true;
        HIS.post('/api/warehouse/maintenance/create', payload).then(function (m) { HIS.notifySuccess('养护单已建: ' + m.mntNo); vm.viewDlg = false; vm.load(); }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      openView: function (row, edit) {
        var vm = this; vm.viewLoading = true; vm.viewDlg = true; vm.editing = !!edit && row.status === 0; vm.view = { main: {}, items: [] };
        HIS.get('/api/warehouse/maintenance/' + row.id).then(function (d) { vm.view = d || { main: {}, items: [] }; }).catch(HIS.notifyError).finally(function () { vm.viewLoading = false; });
      },
      addItem: function () { this.view.items.push(this.emptyItem()); },
      removeItem: function (i) { this.view.items.splice(i, 1); },
      openDrugDlg: function (idx) { this.pendingIdx = idx; this.drugKeyword = ''; this.drugPage = 1; this.drugList = []; this.drugDlg = true; this.loadDrugs(); },
      loadDrugs: function () {
        var vm = this; vm.drugLoading = true;
        var q = '/api/his/stock/drug-catalog?page=' + vm.drugPage + '&size=' + vm.drugSize;
        if (vm.drugKeyword) { q += '&keyword=' + encodeURIComponent(vm.drugKeyword); }
        HIS.get(q).then(function (d) { vm.drugList = (d && d.records) || []; vm.drugTotal = Number((d && d.total) || 0); }).catch(HIS.notifyError).finally(function () { vm.drugLoading = false; });
      },
      searchDrugs: function () { this.drugPage = 1; this.loadDrugs(); },
      onDrugPage: function (p) { this.drugPage = p; this.loadDrugs(); },
      pickDrug: function (d) { var it = this.view.items[this.pendingIdx]; if (it) { it.drugCatalogId = d.id; it.drugCode = d.drugCode; it.drugName = d.genericName || d.tradeName || d.drugCode; it.spec = d.spec || ''; it.manufacturer = d.manufacturer || ''; it.dosform = d.dosform || it.dosform || ''; } this.drugDlg = false; },
      saveItems: function () {
        var vm = this; vm.saving = true;
        HIS.put('/api/warehouse/maintenance/' + vm.view.main.id + '/items', vm.view.items).then(function () { HIS.notifySuccess('明细已保存'); vm.viewDlg = false; vm.load(); }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      complete: function () {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('整体结论', '完成养护', { inputValue: '养护合格', inputType: 'textarea' }).then(function (res) {
          vm.saving = true;
          HIS.post('/api/warehouse/maintenance/' + vm.view.main.id + '/complete?conclusion=' + encodeURIComponent(res.value || '')).then(function () { HIS.notifySuccess('养护已完成'); vm.viewDlg = false; vm.load(); }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
        }).catch(function () { });
      },
      completeRow: function (row) { var vm = this; ElementPlus.ElMessageBox.prompt('整体结论', '完成养护 ' + row.mntNo, { inputValue: '养护合格', inputType: 'textarea' }).then(function (res) { HIS.post('/api/warehouse/maintenance/' + row.id + '/complete?conclusion=' + encodeURIComponent(res.value || '')).then(function () { HIS.notifySuccess('已完成'); vm.load(); }).catch(HIS.notifyError); }).catch(function () { }); },
      del: function (row) { var vm = this; ElementPlus.ElMessageBox.confirm('作废草稿养护单 ' + row.mntNo + '?', '作废', { type: 'warning' }).then(function () { HIS.del('/api/warehouse/maintenance/' + row.id).then(function () { HIS.notifySuccess('已作废'); vm.load(); }).catch(HIS.notifyError); }).catch(function () { }); }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">药品养护 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(手动逐行 / 自动按在库筛选 / 引入模板 · 明细养护结果 · 完成汇总异常)</span></div>',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="openCreate">新建养护单</el-button>',
      '    <el-select v-model="filterStatus" clearable placeholder="全部状态" style="width:130px;" @change="search"><el-option label="草稿" :value="0"></el-option><el-option label="已完成" :value="1"></el-option></el-select>',
      '    <el-button @click="search">查询</el-button><el-button @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column prop="mntNo" label="养护单号" width="160"></el-table-column>',
      '    <el-table-column label="药库" width="110"><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column prop="mntDate" label="养护日期" width="110"></el-table-column>',
      '    <el-table-column label="方式" width="80" align="center"><template #default="s"><el-tag size="small" :type="mtTag(s.row.mntType)">{{ mtLabel(s.row.mntType) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="品种" width="70" align="right"><template #default="s">{{ s.row.drugCount }}</template></el-table-column>',
      '    <el-table-column label="异常" width="70" align="right"><template #default="s"><span :style="s.row.abnormalCount>0?\'color:var(--yb-danger)\':\'\'">{{ s.row.abnormalCount }}</span></template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="mnStatusTag(s.row.status)">{{ mnStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="mntBy" label="养护人" width="90"></el-table-column>',
      '    <el-table-column label="操作" min-width="200"><template #default="s">',
      '      <el-button link type="primary" @click="openView(s.row,false)">查看</el-button>',
      '      <template v-if="lead && s.row.status===0">',
      '        <el-button link type="primary" @click="openView(s.row,true)">编辑明细</el-button>',
      '        <el-button link type="success" @click="completeRow(s.row)">完成</el-button>',
      '        <el-button link @click="del(s.row)">作废</el-button>',
      '      </template>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="p=>{page=p;load()}" style="margin-top:8px;"></el-pagination>',
      /* 建单/明细 对话框 */
      '  <el-dialog v-model="viewDlg" :title="view.main.mntNo ? (editing?\'编辑养护明细\':\'养护单详情\') : \'新建养护单\'" width="980px" top="6vh">',
      '    <div v-loading="viewLoading">',
      '      <template v-if="!view.main.mntNo">',
      '        <div class="toolbar" style="margin-bottom:8px;">',
      '          <el-radio-group v-model="mode" @change="switchMode"><el-radio-button :value="1">手动</el-radio-button><el-radio-button :value="2">自动(在库筛选)</el-radio-button><el-radio-button :value="3">引入模板</el-radio-button></el-radio-group>',
      '        </div>',
      '        <el-form :model="form" label-width="90px" size="small" inline>',
      '          <el-form-item label="药库"><el-select v-model="form.warehouseId" clearable placeholder="选择药库" style="width:160px;"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="养护日期"><el-date-picker v-model="form.mntDate" type="date" value-format="YYYY-MM-DD" style="width:150px;"></el-date-picker></el-form-item>',
      '          <el-form-item v-if="mode===2" label="剂型"><el-input v-model="form.dosform" style="width:120px;"></el-input></el-form-item>',
      '          <el-form-item v-if="mode===2" label="关键字"><el-input v-model="form.drugKeyword" style="width:140px;"></el-input></el-form-item>',
      '          <el-form-item v-if="mode===3" label="模板" required><el-select v-model="form.templateId" placeholder="选择模板" style="width:200px;"><el-option v-for="t in templates" :key="t.id" :label="t.templateName" :value="t.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="备注"><el-input v-model="form.remark" style="width:160px;"></el-input></el-form-item>',
      '        </el-form>',
      '        <div v-if="mode!==1" style="color:var(--yb-ink-2);font-size:12px;margin-bottom:6px;">{{ mode===2?"自动: 按药库/剂型/关键字从在库有余量批次自动生成养护行, 建单后再编辑结果":"模板: 引入所选模板的筛选条件与默认养护措施自动生行" }}</div>',
      '        <template v-if="mode===1">',
      '          <div class="toolbar"><el-button size="small" type="primary" @click="addItem">添加明细行</el-button></div>',
      '          <el-table :data="view.items" border size="small" max-height="300">',
      '            <el-table-column label="药品" min-width="160"><template #default="s"><el-input :model-value="s.row.drugName" readonly placeholder="点击选药" @click="openDrugDlg(s.$index)"><template #append><el-button @click="openDrugDlg(s.$index)">选</el-button></template></el-input></template></el-table-column>',
      '            <el-table-column label="批号" width="130"><template #default="s"><el-input v-model="s.row.batchNo" size="small"></el-input></template></el-table-column>',
      '            <el-table-column label="数量" width="90"><template #default="s"><el-input-number v-model="s.row.qty" :min="0" :controls="false" size="small" style="width:100%;"></el-input-number></template></el-table-column>',
      '            <el-table-column label="养护措施" min-width="150"><template #default="s"><el-input v-model="s.row.measure" size="small"></el-input></template></el-table-column>',
      '            <el-table-column label="结果" width="100"><template #default="s"><el-select v-model="s.row.result" size="small" style="width:100%;"><el-option label="合格" :value="1"></el-option><el-option label="异常" :value="2"></el-option></el-select></template></el-table-column>',
      '            <el-table-column label="操作" width="55"><template #default="s"><el-button link type="danger" @click="removeItem(s.$index)">删</el-button></template></el-table-column>',
      '          </el-table>',
      '        </template>',
      '        <template v-if="mode!==1"><div style="color:var(--yb-ink-2);font-size:13px;">点击下方"建单"后在生成的草稿中编辑每行养护结果。</div></template>',
      '        <template #footer><el-button @click="viewDlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="createByMode">建单</el-button></template>',
      '      </template>',
      '      <template v-else>',
      '        <div style="margin-bottom:8px;">{{ view.main.mntNo }} · 药库 {{ whName(view.main.warehouseId) }} · {{ view.main.mntDate }} · 方式 <el-tag size="small" :type="mtTag(view.main.mntType)">{{ mtLabel(view.main.mntType) }}</el-tag> · 状态 <el-tag size="small" :type="mnStatusTag(view.main.status)">{{ mnStatusLabel(view.main.status) }}</el-tag></div>',
      '        <div class="toolbar" v-if="editing"><el-button size="small" type="primary" @click="addItem">添加行</el-button></div>',
      '        <el-table :data="view.items" border size="small" max-height="360">',
      '          <el-table-column prop="drugCode" label="编码" width="110"></el-table-column>',
      '          <el-table-column prop="drugName" label="药品" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="batchNo" label="批号" width="120"></el-table-column>',
      '          <el-table-column label="数量" width="80" align="right"><template #default="s">{{ qty(s.row.qty) }}</template></el-table-column>',
      '          <el-table-column prop="expDate" label="有效期" width="110"></el-table-column>',
      '          <el-table-column label="养护措施" min-width="150"><template #default="s"><el-input v-if="editing" v-model="s.row.measure" size="small"></el-input><span v-else>{{ s.row.measure || "-" }}</span></template></el-table-column>',
      '          <el-table-column label="结果" width="110"><template #default="s"><el-select v-if="editing" v-model="s.row.result" size="small" style="width:100%;"><el-option label="合格" :value="1"></el-option><el-option label="异常" :value="2"></el-option></el-select><el-tag v-else size="small" :type="rsTag(s.row.result)">{{ rsLabel(s.row.result) }}</el-tag></template></el-table-column>',
      '          <el-table-column v-if="editing" label="操作" width="55"><template #default="s"><el-button link type="danger" @click="removeItem(s.$index)">删</el-button></template></el-table-column>',
      '        </el-table>',
      '        <template #footer>',
      '          <el-button @click="viewDlg=false">关闭</el-button>',
      '          <template v-if="editing && lead"><el-button :loading="saving" @click="saveItems">保存明细</el-button><el-button type="success" :loading="saving" @click="complete">完成养护</el-button></template>',
      '        </template>',
      '      </template>',
      '    </div>',
      '  </el-dialog>',
      /* 选药对话框 */
      '  <el-dialog v-model="drugDlg" title="选择药品" width="720px">',
      '    <div class="toolbar"><el-input v-model="drugKeyword" placeholder="名称/编码" clearable style="width:220px;" @keyup.enter="searchDrugs"></el-input><el-button type="primary" @click="searchDrugs">查询</el-button></div>',
      '    <el-table :data="drugList" v-loading="drugLoading" border size="small" max-height="360" @row-click="pickDrug">',
      '      <el-table-column prop="drugCode" label="编码" width="120"></el-table-column>',
      '      <el-table-column label="名称" min-width="160"><template #default="s">{{ s.row.genericName || s.row.tradeName }}</template></el-table-column>',
      '      <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="manufacturer" label="厂家" width="140" show-overflow-tooltip></el-table-column>',
      '    </el-table>',
      '    <el-pagination background layout="total, prev, pager, next" :total="drugTotal" :page-size="drugSize" :current-page="drugPage" @current-change="onDrugPage" style="margin-top:8px;"></el-pagination>',
      '    <template #footer><el-button @click="drugDlg=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ==================== 库房月结 ==================== */
  HIS.views.MonthEnd = {
    data: function () {
      return {
        lead: HIS.isLead(), warehouses: [], loading: false, list: [], total: 0, page: 1, size: 20,
        running: false, form: { warehouseId: null, periodStart: '', periodEnd: '', acctStandard: 1, remark: '' }
      };
    },
    created: function () { this.load(); loadWarehouses(this); },
    methods: {
      stdLabel: stdLabel, meStatusLabel: meStatusLabel, meStatusTag: meStatusTag, money: money, qty: qty, dt: dt,
      whName: function (id) { if (!id) { return '全院'; } for (var i = 0; i < this.warehouses.length; i++) { if (this.warehouses[i].id === id) { return this.warehouses[i].name; } } return '库#' + id; },
      load: function () { var vm = this; vm.loading = true; HIS.get('/api/warehouse/month-end/page?page=' + vm.page + '&size=' + vm.size).then(function (d) { vm.list = (d && d.records) || []; vm.total = Number((d && d.total) || 0); }).catch(HIS.notifyError).finally(function () { vm.loading = false; }); },
      run: function () {
        var vm = this;
        if (!vm.form.periodStart || !vm.form.periodEnd) { ElementPlus.ElMessage.warning('请填写本期起止日期'); return; }
        ElementPlus.ElMessageBox.confirm('确认对 ' + vm.whName(vm.form.warehouseId) + ' 执行月结 ' + vm.form.periodStart + ' ~ ' + vm.form.periodEnd + '? 本期若有未确认单据将被拦截。', '库房月结', { type: 'warning' }).then(function () {
          vm.running = true;
          HIS.post('/api/warehouse/month-end/run', vm.form).then(function (m) { HIS.notifySuccess('月结完成: 期末 ' + money(m.closingAmount)); vm.load(); }).catch(HIS.notifyError).finally(function () { vm.running = false; });
        }).catch(function () { });
      },
      unmonthEnd: function (row) { var vm = this; ElementPlus.ElMessageBox.confirm('取消月结 ' + row.periodStart + ' ~ ' + row.periodEnd + '? (仅最后一个月结期可取消)', '取消月结', { type: 'warning' }).then(function () { HIS.post('/api/warehouse/month-end/' + row.id + '/unmonth-end').then(function () { HIS.notifySuccess('已取消'); vm.load(); }).catch(HIS.notifyError); }).catch(function () { }); }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">库房月结 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(未确认单据拦截 · 起始衔接上次月结 · 财务账+实物账双轨)</span></div>',
      '  <el-form v-if="lead" :model="form" label-width="82px" size="small" inline style="margin-bottom:8px;">',
      '    <el-form-item label="药库"><el-select v-model="form.warehouseId" clearable placeholder="全院" style="width:150px;"><el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id"></el-option></el-select></el-form-item>',
      '    <el-form-item label="起始"><el-date-picker v-model="form.periodStart" type="date" value-format="YYYY-MM-DD" style="width:140px;"></el-date-picker></el-form-item>',
      '    <el-form-item label="终止"><el-date-picker v-model="form.periodEnd" type="date" value-format="YYYY-MM-DD" style="width:140px;"></el-date-picker></el-form-item>',
      '    <el-form-item label="记账标准"><el-select v-model="form.acctStandard" style="width:110px;"><el-option label="进价" :value="1"></el-option><el-option label="零售价" :value="3"></el-option></el-select></el-form-item>',
      '    <el-form-item label="备注"><el-input v-model="form.remark" style="width:140px;"></el-input></el-form-item>',
      '    <el-form-item><el-button type="primary" :loading="running" @click="run">执行月结</el-button></el-form-item>',
      '  </el-form>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column label="药库" width="100"><template #default="s">{{ whName(s.row.warehouseId) }}</template></el-table-column>',
      '    <el-table-column label="期间" width="200"><template #default="s">{{ s.row.periodStart }} ~ {{ s.row.periodEnd }}</template></el-table-column>',
      '    <el-table-column label="标准" width="70" align="center"><template #default="s">{{ stdLabel(s.row.acctStandard) }}</template></el-table-column>',
      '    <el-table-column label="期初额" width="100" align="right"><template #default="s">{{ money(s.row.openingAmount) }}</template></el-table-column>',
      '    <el-table-column label="收入额" width="100" align="right"><template #default="s">{{ money(s.row.incomeAmount) }}</template></el-table-column>',
      '    <el-table-column label="支出额" width="100" align="right"><template #default="s">{{ money(s.row.expenseAmount) }}</template></el-table-column>',
      '    <el-table-column label="期末额" width="100" align="right"><template #default="s"><b>{{ money(s.row.closingAmount) }}</b></template></el-table-column>',
      '    <el-table-column label="期末量" width="90" align="right"><template #default="s">{{ qty(s.row.closingQty) }}</template></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="meStatusTag(s.row.status)">{{ meStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="90"><template #default="s"><el-button v-if="lead && s.row.status===1" link type="danger" @click="unmonthEnd(s.row)">取消月结</el-button><span v-else>-</span></template></el-table-column>',
      '  </el-table>',
      '  <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="p=>{page=p;load()}" style="margin-top:8px;"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* 账簿查询(StockBookRpt) 已下线(2026-10): 收发存/财务/实物账 + 保管员台账 均已整合进 pharmacy.js 的进销存台账(DrugLedger)财务账簿页签 · 菜单 stock-book 已退役 */
})();
