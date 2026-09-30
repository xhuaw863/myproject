/* 住院医嘱模板/套餐维护页: 列表(筛选/分页/操作) + 编辑对话框(基本信息 + items 可编辑表格)。
 * 接口: GET /api/his/inp/order-template/list | GET /{id} | POST | PUT /{id} | DELETE /{id} | PUT /{id}/usage
 *       入参 OrderTemplateDTO{templateName,templateType(1个人2科室3全院),scopeType(1单条2套餐),deptId,doctorId,items[],diseaseCode,status}
 *       items 元素 OrderTemplateItemDTO{orderType,orderCategory,orderContent,chargeItemId,drugId,spec,dosage,dosageUnit,usageCode,freqCode,quantity,unitPrice}
 * 检索源(复用开嘱手开): /api/org-catalog/available/drug|charge|med-dict | /api/his/dept/enabled | /api/community-dict/diag-dict/page
 * 前置校验对齐后端定价: category=1(药品)须选中药品(drugId); 至少1项; 内容必填。
 * 注册: HIS.views.InpOrderTemplateManage (须在 app.js 之前加载); 样式 <style id="order-template-css"> 注入, 前缀 ot-* */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  const ORDER_TYPE = { 1: '长期', 2: '临时' };
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  const TEMPLATE_TYPE = { 1: '个人', 2: '科室', 3: '全院' };
  const SCOPE_TYPE = { 1: '单条', 2: '套餐' };
  const TYPE_OPTS = [{ v: 1, l: '长期' }, { v: 2, l: '临时' }];
  const CAT_OPTS = Object.keys(ORDER_CATEGORY).map(k => ({ v: Number(k), l: ORDER_CATEGORY[k] }));

  function money(v) { const n = Number(v); return isNaN(n) ? '' : n.toFixed(2); }

  /* 瞬态字段(编辑器内用, 保存前剥离): _pickId 选中药/项目id, _opts 检索结果, _searching 检索中 */
  function blankItem() {
    return {
      orderType: 1, orderCategory: 1, orderContent: '', drugId: null, chargeItemId: null,
      spec: '', dosage: '', dosageUnit: '', usageCode: null, freqCode: null, quantity: 1, unitPrice: null,
      _pickId: null, _opts: [], _searching: false
    };
  }
  function hydrate(it) {
    const b = blankItem();
    Object.keys(b).forEach(k => { if (k.charAt(0) !== '_' && it[k] != null) { b[k] = it[k]; } });
    b.orderType = Number(b.orderType) || 1;
    b.orderCategory = Number(b.orderCategory) || 1;
    b.quantity = b.quantity != null ? Number(b.quantity) : 1;
    b.unitPrice = b.unitPrice != null ? Number(b.unitPrice) : null;
    b._pickId = b.orderCategory === 1 ? b.drugId : b.chargeItemId;
    return b;
  }
  function parseItems(raw) {
    if (!raw) { return []; }
    try { const a = typeof raw === 'string' ? JSON.parse(raw) : raw; return Array.isArray(a) ? a : []; }
    catch (e) { return []; }
  }

  const CSS = `
.ot-wrap { display:flex; flex-direction:column; height:calc(100vh - 88px); padding:12px; box-sizing:border-box; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); overflow:hidden; }
.ot-toolbar { flex:none; display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:10px; }
.ot-toolbar .grow { flex:1; }
.ot-body { flex:1; min-height:0; }
.ot-pager { flex:none; display:flex; justify-content:flex-end; padding-top:10px; }
.ot-form-grid { display:grid; grid-template-columns:1fr 1fr; gap:0 18px; }
.ot-items-head { display:flex; align-items:center; gap:10px; margin:6px 0 8px; padding-top:10px; border-top:1px solid var(--yb-divider); font-weight:600; color:var(--yb-ink-1); font-size:var(--yb-fs-md); }
.ot-items { width:100%; }
.ot-cell-sub { font-size:11px; color:var(--yb-ink-3); }
.ot-price { font-family:var(--yb-font-mono); color:var(--yb-gold); }
.ot-pv { display:flex; flex-direction:column; gap:4px; max-height:260px; overflow:auto; }
.ot-pv .row { font-size:12px; color:var(--yb-ink-2); border-bottom:1px dashed var(--yb-divider); padding-bottom:3px; }
.ot-pv .row b { color:var(--yb-ink-1); }
`;
  (function injectCss() {
    let st = document.getElementById('order-template-css');
    if (!st) { st = document.createElement('style'); st.id = 'order-template-css'; document.head.appendChild(st); }
    st.textContent = CSS;
  })();

  HIS.views.InpOrderTemplateManage = {
    name: 'InpOrderTemplateManage',
    data: function () {
      return {
        loading: false, rows: [], total: 0, page: 1, size: 20,
        flt: { keyword: '', templateType: null, scopeType: null, status: null },
        depts: [], usageOptions: [], freqOptions: [], dictLoaded: false,
        dlg: { visible: false, saving: false, edit: false, rowId: null, model: { items: [blankItem()] } },
        diagOptions: [], diagSearching: false,
        typeOpts: TYPE_OPTS, catOpts: CAT_OPTS
      };
    },
    computed: {
      usageMap: function () { const m = {}; this.usageOptions.forEach(o => { m[o.code] = o.name; }); return m; },
      freqMap: function () { const m = {}; this.freqOptions.forEach(o => { m[o.code] = o.name; }); return m; },
      deptMap: function () { const m = {}; this.depts.forEach(d => { m[d.id] = d.deptName; }); return m; }
    },
    created: function () { this.loadDepts(); this.loadDicts(); this.load(); },
    methods: {
      /* ===== 文案/工具 ===== */
      typeText: function (t) { return TEMPLATE_TYPE[t] || '-'; },
      scopeText: function (t) { return SCOPE_TYPE[t] || '-'; },
      orderTypeText: function (t) { return ORDER_TYPE[t] || '-'; },
      catText: function (t) { return ORDER_CATEGORY[t] || '-'; },
      deptName: function (id) { return id == null ? '-' : (this.deptMap[id] || ('#' + id)); },
      itemCount: function (items) { return parseItems(items).length; },
      itemRows: function (items) {
        return parseItems(items).map(it => {
          const seg = [ORDER_CATEGORY[it.orderCategory] || '-', it.orderContent || ''];
          if (Number(it.orderCategory) === 1) {
            const d = [it.dosage ? it.dosage + (it.dosageUnit || '') : '', this.usageMap[it.usageCode] || '', this.freqMap[it.freqCode] || ''].filter(Boolean).join(' ');
            if (d) { seg.push(d); }
          }
          return { head: (ORDER_TYPE[it.orderType] || '') + ' · ' + seg[0], body: seg.slice(1).join('  ') };
        });
      },
      fmtTime: function (v) { return v ? String(v).replace('T', ' ').substring(0, 16) : '-'; },
      indexMethod: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ===== 基础数据 ===== */
      loadDepts: function () {
        const vm = this;
        HIS.get('/api/his/dept/enabled').then(l => { vm.depts = l || []; }).catch(() => { vm.depts = []; });
      },
      loadDicts: function () {
        const vm = this;
        if (vm.dictLoaded) { return; }
        vm.dictLoaded = true;
        HIS.get('/api/org-catalog/available/med-dict?dictType=usage').then(l => { vm.usageOptions = l || []; }).catch(() => {});
        HIS.get('/api/org-catalog/available/med-dict?dictType=freq').then(l => { vm.freqOptions = l || []; }).catch(() => {});
      },
      /* ===== 列表 ===== */
      load: function () {
        const vm = this;
        vm.loading = true;
        const p = new URLSearchParams();
        p.set('page', vm.page); p.set('size', vm.size);
        if (vm.flt.keyword) { p.set('keyword', vm.flt.keyword); }
        if (vm.flt.templateType) { p.set('templateType', vm.flt.templateType); }
        if (vm.flt.scopeType) { p.set('scopeType', vm.flt.scopeType); }
        if (vm.flt.status != null) { p.set('status', vm.flt.status); }
        HIS.get('/api/his/inp/order-template/list?' + p.toString())
          .then(d => { vm.rows = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(() => { vm.loading = false; });
      },
      onSearch: function () { this.page = 1; this.load(); },
      reset: function () { this.flt = { keyword: '', templateType: null, scopeType: null, status: null }; this.onSearch(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      /* ===== 编辑对话框 ===== */
      openCreate: function (scopeType) {
        this.dlg.edit = false; this.dlg.rowId = null;
        this.dlg.model = {
          templateName: '', templateType: 1, scopeType: scopeType || 1, deptId: null,
          diseaseCode: '', status: 1, items: [blankItem()]
        };
        this.diagOptions = [];
        this.loadDicts();
        this.dlg.visible = true;
      },
      openEdit: function (row) {
        const vm = this;
        HIS.get('/api/his/inp/order-template/' + HIS.idParam(row.id)).then(t => {
          if (!t) { HIS.notifyError({ message: '模板不存在' }); return; }
          const items = parseItems(t.items).map(hydrate);
          vm.dlg.edit = true; vm.dlg.rowId = t.id;
          vm.dlg.model = {
            templateName: t.templateName || '', templateType: Number(t.templateType) || 1,
            scopeType: Number(t.scopeType) || 1, deptId: t.deptId || null,
            diseaseCode: t.diseaseCode || '', status: Number(t.status) != null ? Number(t.status) : 1,
            items: items.length ? items : [blankItem()]
          };
          vm.diagOptions = t.diseaseCode ? [{ code: t.diseaseCode, name: t.diseaseCode }] : [];
          vm.loadDicts();
          vm.dlg.visible = true;
        }).catch(HIS.notifyError);
      },
      addItem: function () { this.dlg.model.items.push(blankItem()); },
      removeItem: function (i) {
        const its = this.dlg.model.items;
        if (its.length <= 1) { ElementPlus.ElMessage.warning('至少保留 1 条医嘱项'); return; }
        its.splice(i, 1);
      },
      onCatChange: function (row) {
        row.drugId = null; row.chargeItemId = null; row.spec = ''; row.unitPrice = null;
        row.dosage = ''; row.dosageUnit = ''; row.usageCode = null; row.freqCode = null;
        row._pickId = null; row._opts = [];
      },
      /* 目录检索: category=1 走药品, 其余走收费项目; 归一化为 {id,name,spec,price} */
      searchCatalog: function (row, query) {
        const kw = String(query || '').trim();
        if (!kw) { row._opts = []; return; }
        row._searching = true;
        const isDrug = Number(row.orderCategory) === 1;
        const url = isDrug
          ? '/api/org-catalog/available/drug?page=1&size=30&keyword=' + encodeURIComponent(kw)
          : '/api/org-catalog/available/charge?page=1&size=30&keyword=' + encodeURIComponent(kw);
        HIS.get(url).then(data => {
          row._opts = ((data && data.records) || []).map(x => ({
            id: x.id,
            name: isDrug ? (x.genericName || x.itemName || '') : (x.itemName || x.genericName || ''),
            spec: x.spec || '',
            price: isDrug ? x.retailPrice : (x.execPrice != null ? x.execPrice : x.price)
          }));
        }).catch(() => { row._opts = []; }).finally(() => { row._searching = false; });
      },
      onPickCatalog: function (row, id) {
        const o = (row._opts || []).find(x => HIS.sameId(x.id, id));
        if (!o) { return; }
        if (Number(row.orderCategory) === 1) { row.drugId = o.id; row.chargeItemId = null; }
        else { row.chargeItemId = o.id; row.drugId = null; }
        row.spec = o.spec || '';
        row.unitPrice = o.price != null ? Number(o.price) : null;
        if (!String(row.orderContent || '').trim()) { row.orderContent = o.name || ''; }
      },
      /* 适用病种: ICD-10 诊断检索 */
      remoteDiagSearch: function (query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.diagOptions = []; return; }
        vm.diagSearching = true;
        HIS.get('/api/community-dict/diag-dict/page?dictType=west&status=1&page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(d => { vm.diagOptions = (d && d.records) || []; })
          .catch(() => { vm.diagOptions = []; })
          .finally(() => { vm.diagSearching = false; });
      },
      /* ===== 校验 + 保存 ===== */
      validate: function () {
        const m = this.dlg.model;
        if (!String(m.templateName || '').trim()) { ElementPlus.ElMessage.warning('请填写模板名称'); return false; }
        if (Number(m.templateType) === 2 && !m.deptId) { ElementPlus.ElMessage.warning('科室级模板请选择适用科室'); return false; }
        const items = m.items || [];
        if (!items.length) { ElementPlus.ElMessage.warning('至少添加 1 条医嘱项'); return false; }
        for (let i = 0; i < items.length; i++) {
          const it = items[i];
          if (!String(it.orderContent || '').trim()) { ElementPlus.ElMessage.warning('第 ' + (i + 1) + ' 项医嘱内容不能为空'); return false; }
          if (Number(it.orderCategory) === 1 && !it.drugId) { ElementPlus.ElMessage.warning('第 ' + (i + 1) + ' 项为药品, 请先检索并选中药品'); return false; }
          if (it.quantity != null && Number(it.quantity) < 0) { ElementPlus.ElMessage.warning('第 ' + (i + 1) + ' 项数量不能为负'); return false; }
        }
        return true;
      },
      buildPayload: function () {
        const m = this.dlg.model;
        return {
          templateName: String(m.templateName).trim(),
          templateType: Number(m.templateType),
          scopeType: Number(m.scopeType),
          deptId: Number(m.templateType) === 2 ? m.deptId : null,
          diseaseCode: m.diseaseCode || null,
          status: Number(m.status),
          items: (m.items || []).map(it => ({
            orderType: Number(it.orderType), orderCategory: Number(it.orderCategory),
            orderContent: String(it.orderContent).trim(),
            drugId: Number(it.orderCategory) === 1 ? it.drugId : null,
            chargeItemId: Number(it.orderCategory) === 1 ? null : (it.chargeItemId || null),
            spec: it.spec || null, dosage: it.dosage || null, dosageUnit: it.dosageUnit || null,
            usageCode: it.usageCode || null, freqCode: it.freqCode || null,
            quantity: it.quantity != null ? Number(it.quantity) : null,
            unitPrice: it.unitPrice != null ? Number(it.unitPrice) : null
          }))
        };
      },
      save: function () {
        const vm = this;
        if (vm.dlg.saving) { return; }
        if (!vm.validate()) { return; }
        vm.dlg.saving = true;
        const payload = vm.buildPayload();
        const req = vm.dlg.edit
          ? HIS.put('/api/his/inp/order-template/' + HIS.idParam(vm.dlg.rowId), payload)
          : HIS.post('/api/his/inp/order-template', payload);
        req.then(() => {
          HIS.notifySuccess(vm.dlg.edit ? '模板已保存' : '模板已创建');
          vm.dlg.visible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(() => { vm.dlg.saving = false; });
      },
      toggleStatus: function (row) {
        const vm = this;
        const next = Number(row.status) === 1 ? 0 : 1;
        HIS.put('/api/his/inp/order-template/' + HIS.idParam(row.id), { status: next }).then(() => {
          HIS.notifySuccess(next === 1 ? '已启用' : '已停用'); vm.load();
        }).catch(HIS.notifyError);
      },
      del: function (row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除模板「' + row.templateName + '」？删除为逻辑删除，不影响历史医嘱溯源。', '删除确认', { type: 'warning' })
          .then(() => HIS.del('/api/his/inp/order-template/' + HIS.idParam(row.id)))
          .then(() => { HIS.notifySuccess('已删除'); vm.load(); })
          .catch(e => { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      }
    },
    template: `
<div class="ot-wrap">
  <div class="ot-toolbar">
    <el-input v-model="flt.keyword" placeholder="模板名称" clearable style="width:180px" @keyup.enter="onSearch" @clear="onSearch"></el-input>
    <el-select v-model="flt.templateType" placeholder="级别" clearable style="width:110px" @change="onSearch">
      <el-option label="个人" :value="1"></el-option><el-option label="科室" :value="2"></el-option><el-option label="全院" :value="3"></el-option>
    </el-select>
    <el-select v-model="flt.scopeType" placeholder="类型" clearable style="width:110px" @change="onSearch">
      <el-option label="单条" :value="1"></el-option><el-option label="套餐" :value="2"></el-option>
    </el-select>
    <el-select v-model="flt.status" placeholder="状态" clearable style="width:110px" @change="onSearch">
      <el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option>
    </el-select>
    <el-button size="small" @click="onSearch">查询</el-button>
    <el-button size="small" @click="reset">重置</el-button>
    <span class="grow"></span>
    <el-button type="primary" size="small" @click="openCreate(1)">+ 新建模板</el-button>
    <el-button type="success" size="small" @click="openCreate(2)">+ 新建套餐</el-button>
  </div>
  <div class="ot-body">
    <el-table :data="rows" v-loading="loading" size="small" border height="100%">
      <el-table-column type="index" label="#" width="48" :index="indexMethod"></el-table-column>
      <el-table-column prop="templateName" label="名称" min-width="160" show-overflow-tooltip></el-table-column>
      <el-table-column label="级别" width="72"><template #default="{row}">{{ typeText(row.templateType) }}</template></el-table-column>
      <el-table-column label="类型" width="72"><template #default="{row}">{{ scopeText(row.scopeType) }}</template></el-table-column>
      <el-table-column label="适用科室" width="120" show-overflow-tooltip><template #default="{row}">{{ deptName(row.deptId) }}</template></el-table-column>
      <el-table-column label="医嘱项" width="80" align="center">
        <template #default="{row}">
          <el-popover placement="right" :width="320" trigger="hover" :disabled="!itemCount(row.items)">
            <template #reference><el-tag size="small" type="info" style="cursor:pointer">{{ itemCount(row.items) }} 项</el-tag></template>
            <div class="ot-pv"><div class="row" v-for="(pv,i) in itemRows(row.items)" :key="i"><b>{{ i+1 }}. {{ pv.head }}</b><div>{{ pv.body }}</div></div></div>
          </el-popover>
        </template>
      </el-table-column>
      <el-table-column label="适用病种" width="120" show-overflow-tooltip><template #default="{row}">{{ row.diseaseCode || '-' }}</template></el-table-column>
      <el-table-column prop="usageCount" label="使用" width="60" align="center"></el-table-column>
      <el-table-column label="状态" width="72"><template #default="{row}"><el-tag :type="Number(row.status)===1?'success':'info'" size="small">{{ Number(row.status)===1?'启用':'停用' }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间" width="150"><template #default="{row}">{{ fmtTime(row.createTime) }}</template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{row}">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" @click="toggleStatus(row)">{{ Number(row.status)===1?'停用':'启用' }}</el-button>
          <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
  <div class="ot-pager">
    <el-pagination background layout="total, sizes, prev, pager, next" :total="total"
      :current-page="page" :page-size="size" :page-sizes="[20,50,100]" @current-change="onPage" @size-change="onSize"></el-pagination>
  </div>

  <el-dialog v-model="dlg.visible" :title="dlg.edit ? '编辑模板/套餐' : (dlg.model.scopeType===2 ? '新建医嘱套餐' : '新建医嘱模板')" width="980px" top="6vh" :close-on-click-modal="false">
    <el-form label-width="80px" size="small">
      <div class="ot-form-grid">
        <el-form-item label="名称" required><el-input v-model="dlg.model.templateName" maxlength="60" placeholder="模板 / 套餐名称"></el-input></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="dlg.model.scopeType"><el-radio-button :label="1">单条</el-radio-button><el-radio-button :label="2">套餐</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item label="级别">
          <el-radio-group v-model="dlg.model.templateType"><el-radio-button :label="1">个人</el-radio-button><el-radio-button :label="2">科室</el-radio-button><el-radio-button :label="3">全院</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item label="适用科室" v-if="Number(dlg.model.templateType)===2">
          <el-select v-model="dlg.model.deptId" filterable placeholder="选择科室" style="width:100%"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>
        </el-form-item>
        <el-form-item label="适用病种">
          <el-select v-model="dlg.model.diseaseCode" filterable remote reserve-keyword clearable :remote-method="remoteDiagSearch" :loading="diagSearching" placeholder="ICD-10 诊断检索(可空)" style="width:100%">
            <el-option v-for="d in diagOptions" :key="d.code" :label="(d.code + ' ' + (d.name || ''))" :value="d.code"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="dlg.model.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>
        </el-form-item>
      </div>
    </el-form>
    <div class="ot-items-head">医嘱项 ({{ dlg.model.items.length }})<el-button size="small" type="primary" link @click="addItem">+ 添加项</el-button></div>
    <el-table :data="dlg.model.items" size="small" border max-height="360">
      <el-table-column type="index" label="#" width="40"></el-table-column>
      <el-table-column label="类型" width="96">
        <template #default="{row}"><el-select v-model="row.orderType" size="small" style="width:100%"><el-option v-for="o in typeOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></template>
      </el-table-column>
      <el-table-column label="分类" width="96">
        <template #default="{row}"><el-select v-model="row.orderCategory" size="small" style="width:100%" @change="onCatChange(row)"><el-option v-for="o in catOpts" :key="o.v" :label="o.l" :value="o.v"></el-option></el-select></template>
      </el-table-column>
      <el-table-column label="目录检索" width="180">
        <template #default="{row}">
          <el-select v-model="row._pickId" size="small" filterable remote reserve-keyword clearable :remote-method="(q)=>searchCatalog(row,q)" :loading="row._searching" :placeholder="row.orderCategory===1?'检索药品':'检索收费项目(可空)'" style="width:100%" @change="(v)=>onPickCatalog(row,v)">
            <el-option v-for="o in row._opts" :key="o.id" :label="o.name" :value="o.id">
              <div>{{ o.name }}<span class="ot-cell-sub" style="margin-left:8px">{{ o.spec }}<span v-if="o.price!=null" class="ot-price"> ￥{{ money(o.price) }}</span></span></div>
            </el-option>
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="医嘱内容" min-width="180">
        <template #default="{row}"><el-input v-model="row.orderContent" size="small" placeholder="内容(检索后自动带出, 可改)"></el-input></template>
      </el-table-column>
      <el-table-column label="剂量" width="150">
        <template #default="{row}">
          <div v-if="row.orderCategory===1" style="display:flex;gap:4px;">
            <el-input v-model="row.dosage" size="small" placeholder="剂量" style="width:70px"></el-input>
            <el-input v-model="row.dosageUnit" size="small" placeholder="单位" style="width:64px"></el-input>
          </div>
          <span v-else class="ot-cell-sub">—</span>
        </template>
      </el-table-column>
      <el-table-column label="用法" width="104">
        <template #default="{row}"><el-select v-model="row.usageCode" size="small" clearable filterable placeholder="用法" style="width:100%"><el-option v-for="u in usageOptions" :key="u.code" :label="u.name" :value="u.code"></el-option></el-select></template>
      </el-table-column>
      <el-table-column label="频次" width="104">
        <template #default="{row}"><el-select v-model="row.freqCode" size="small" clearable filterable placeholder="频次" style="width:100%"><el-option v-for="f in freqOptions" :key="f.code" :label="f.name" :value="f.code"></el-option></el-select></template>
      </el-table-column>
      <el-table-column label="数量" width="92">
        <template #default="{row}"><el-input-number v-model="row.quantity" size="small" :min="0" :controls="false" style="width:100%"></el-input-number></template>
      </el-table-column>
      <el-table-column label="单价" width="80">
        <template #default="{row}"><span class="ot-price">{{ row.unitPrice!=null ? money(row.unitPrice) : '—' }}</span></template>
      </el-table-column>
      <el-table-column label="" width="46" fixed="right">
        <template #default="{$index}"><el-button link type="danger" size="small" @click="removeItem($index)">删</el-button></template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="dlg.visible=false">取消</el-button>
      <el-button type="primary" :loading="dlg.saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</div>
`
  };
})();
