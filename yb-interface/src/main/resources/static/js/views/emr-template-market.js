/* 病历组件与模板市场(高级版): 分类浏览/搜索 + 上架我的母件 + 克隆为独立副本 + 引用反查分析下钻。
 * 后端契约(/api/his/emr/market, EmrMarketController/EmrComponentMarketService):
 *   GET  /list?compType=&category=&keyword=        市场目录(仅上架行, 按下载量倒序)
 *   POST /publish  {compType,refId,title,category,scopeLevel,shareScope,summary}  上架母件(幂等重上架)
 *   PUT  /{id}/offline                             下架(登记人本人或管理员)
 *   POST /{id}/clone?targetScope=                  克隆为独立副本(0全院 1科室 2个人, 默认2; 0/1需牵头机构)
 *   GET  /referenceAnalysis?refType=&refKey=       引用反查: 某片段/图示/数据元/宏被哪些模板引用
 * 上架母件数据源: 片段 /api/his/emr/fragment/list, 图示 /api/his/emr/drawing-template/list, 模板 /api/his/emr/template/listByScope。
 * 注册: HIS.views.EmrTemplateMarket(菜单 comp 同值; 须在 app.js 之前加载, index.html 引入)。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var COMP_TYPES = [
    { v: '', l: '全部类型' },
    { v: 'fragment', l: '片段' },
    { v: 'drawing', l: '医学图示' },
    { v: 'template', l: '整模板' },
    { v: 'elementSet', l: '数据元集合' }
  ];
  var COMP_LABEL = { fragment: '片段', drawing: '图示', template: '模板', elementSet: '数据元集' };
  var SCOPE_LABEL = { 0: '全院', 1: '科室', 2: '个人' };
  /* 引用反查可选类型 */
  var REF_TYPES = [{ v: 'fragment', l: '片段' }, { v: 'drawing', l: '图示' }, { v: 'element', l: '数据元' }, { v: 'macro', l: '宏' }];
  var CLONE_SCOPES = [{ v: 2, l: '个人模板' }, { v: 1, l: '科室模板' }, { v: 0, l: '全院模板' }];

  function text(v) { return v == null ? '' : String(v); }

  /* ===== 样式注入 ===== */
  (function () {
    if (document.getElementById('emr-tpl-market-style')) { return; }
    var st = document.createElement('style');
    st.id = 'emr-tpl-market-style';
    st.textContent = [
      '.etm-shell { display:flex; flex-direction:column; height:100%; padding:12px 14px; box-sizing:border-box; overflow:hidden; }',
      '.etm-hd { flex:none; display:flex; align-items:center; gap:10px; margin-bottom:10px; flex-wrap:wrap; }',
      '.etm-hd .page-title { margin-right:6px; }',
      '.etm-grid { flex:1; min-height:0; overflow:auto; display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:12px; align-content:start; padding-bottom:8px; }',
      '.etm-card { border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; padding:12px 14px; background:#fff; display:flex; flex-direction:column; gap:8px; transition:box-shadow .15s,border-color .15s; }',
      '.etm-card:hover { box-shadow:0 3px 12px rgba(26,92,158,.12); border-color:var(--yb-brand,#1a5c9e); }',
      '.etm-card-hd { display:flex; align-items:center; gap:8px; }',
      '.etm-card-title { font-size:14px; font-weight:600; color:var(--yb-ink-1,#1c2430); flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.etm-card-sum { font-size:12px; color:var(--yb-ink-2,#3d4a5c); line-height:1.7; min-height:34px; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }',
      '.etm-card-meta { font-size:11px; color:var(--yb-ink-4,#8994a5); display:flex; gap:10px; flex-wrap:wrap; }',
      '.etm-card-acts { display:flex; gap:6px; margin-top:2px; }',
      '.etm-card-acts .el-button { flex:1; margin-left:0; }',
      '.etm-empty { grid-column:1/-1; color:var(--yb-ink-4,#8994a5); font-size:13px; padding:40px; text-align:center; }',
      '.etm-pub-row { display:flex; align-items:center; gap:8px; margin-bottom:12px; }',
      '.etm-ref-tpl { padding:6px 0; border-bottom:1px dashed var(--yb-divider,#f0f3f7); font-size:13px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  HIS.views.EmrTemplateMarket = {
    name: 'EmrTemplateMarket',
    data: function () {
      return {
        compTypes: COMP_TYPES, refTypes: REF_TYPES, cloneScopes: CLONE_SCOPES,
        isAdmin: false, isLead: false,
        loading: false, rows: [],
        filterType: '', keyword: '',
        /* 上架弹窗 */
        pubDlg: false, pubBusy: false,
        pubForm: { compType: 'fragment', refId: '', title: '', category: '', shareScope: 0, summary: '' },
        pubSrcLoading: false, pubSrcList: [],
        /* 克隆弹窗 */
        cloneDlg: false, cloneBusy: false, cloneTarget: null, cloneScope: 2,
        /* 引用分析弹窗 */
        refDlg: false, refBusy: false, refForm: { refType: 'fragment', refKey: '' }, refRows: []
      };
    },
    created: function () {
      this.isAdmin = !!(HIS.hasRole && (HIS.hasRole('ADMIN') || HIS.hasRole('SUPER_ADMIN')));
      this.isLead = typeof HIS.isLead === 'function' ? !!HIS.isLead() : false;
      this.fetch();
    },
    computed: {
      /* 上架可选具体类型(排除"全部类型"空值项) */
      pubCompTypes: function () { return COMP_TYPES.filter(function (x) { return x.v; }); }
    },
    methods: {
      compLabel: function (v) { return COMP_LABEL[v] || v || '-'; },
      scopeLabel: function (v) { return SCOPE_LABEL[Number(v)] || '-'; },

      fetch: function () {
        var vm = this;
        vm.loading = true;
        var q = '?compType=' + encodeURIComponent(text(vm.filterType)) + '&keyword=' + encodeURIComponent(text(vm.keyword).trim());
        HIS.get('/api/his/emr/market/list' + q).then(function (d) {
          vm.rows = Array.isArray(d) ? d : [];
        }).catch(function (e) { vm.rows = []; HIS.notifyError(e); })
          .finally(function () { vm.loading = false; });
      },

      /* ===== 上架 ===== */
      openPublish: function () {
        this.pubForm = { compType: 'fragment', refId: '', title: '', category: '', shareScope: 0, summary: '' };
        this.pubDlg = true;
        this.loadSources();
      },
      onPubTypeChange: function () { this.pubForm.refId = ''; this.pubForm.title = ''; this.loadSources(); },
      loadSources: function () {
        var vm = this, ct = vm.pubForm.compType;
        vm.pubSrcList = [];
        if (ct === 'elementSet') { return; }   /* 数据元集合无独立母件源, 手填 refId */
        vm.pubSrcLoading = true;
        var url = ct === 'fragment' ? '/api/his/emr/fragment/list?page=1&size=200'
          : ct === 'drawing' ? '/api/his/emr/drawing-template/list'
            : '/api/his/emr/template/listByScope?scopeLevel=2';
        HIS.get(url).then(function (d) {
          var arr = (d && d.records) || (Array.isArray(d) ? d : []);
          vm.pubSrcList = arr.map(function (x) {
            return { id: HIS.id(x.id), label: text(x.title || x.templateName || x.name || x.code) || text(x.id), raw: x };
          });
        }).catch(function () { vm.pubSrcList = []; }).finally(function () { vm.pubSrcLoading = false; });
      },
      onPubRefPick: function (id) {
        var hit = this.pubSrcList.filter(function (o) { return o.id === id; })[0];
        if (hit) { this.pubForm.title = hit.label; }
      },
      doPublish: function () {
        var vm = this, f = vm.pubForm;
        if (!f.compType || !text(f.refId).trim() || !text(f.title).trim()) {
          ElementPlus.ElMessage.warning('组件类型、母件与标题为必填'); return;
        }
        vm.pubBusy = true;
        HIS.post('/api/his/emr/market/publish', {
          compType: f.compType, refId: f.refId, title: text(f.title).trim(),
          category: text(f.category).trim() || null, shareScope: Number(f.shareScope) || 0, summary: text(f.summary).trim() || null
        }).then(function () {
          ElementPlus.ElMessage.success('已上架到市场');
          vm.pubDlg = false; vm.fetch();
        }).catch(HIS.notifyError).finally(function () { vm.pubBusy = false; });
      },

      /* ===== 下架 ===== */
      offline: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认下架「' + text(row.title) + '」? 下架后市场不再展示, 母件不受影响。', '下架确认', { type: 'warning' })
          .then(function () { return HIS.put('/api/his/emr/market/' + HIS.idParam(row.id) + '/offline'); })
          .then(function () { ElementPlus.ElMessage.success('已下架'); vm.fetch(); })
          .catch(function (e) { if (e && e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },

      /* ===== 克隆 ===== */
      openClone: function (row) {
        this.cloneTarget = row;
        this.cloneScope = this.isLead ? 2 : 2;
        this.cloneDlg = true;
      },
      doClone: function () {
        var vm = this, row = vm.cloneTarget;
        if (!row) { return; }
        if (Number(vm.cloneScope) !== 2 && !vm.isLead) {
          ElementPlus.ElMessage.warning('克隆为科室/全院组件需牵头机构权限'); return;
        }
        vm.cloneBusy = true;
        HIS.post('/api/his/emr/market/' + HIS.idParam(row.id) + '/clone?targetScope=' + Number(vm.cloneScope)).then(function () {
          ElementPlus.ElMessage.success('已克隆为独立副本(母件不变)');
          vm.cloneDlg = false; vm.fetch();
        }).catch(HIS.notifyError).finally(function () { vm.cloneBusy = false; });
      },

      /* ===== 引用分析 ===== */
      openRef: function () { this.refRows = []; this.refDlg = true; },
      doRefAnalysis: function () {
        var vm = this, f = vm.refForm;
        if (!text(f.refKey).trim()) { ElementPlus.ElMessage.warning('请填写引用标识(fieldKey/fragmentId/drawingCode/macroCode)'); return; }
        vm.refBusy = true;
        HIS.get('/api/his/emr/market/referenceAnalysis?refType=' + encodeURIComponent(f.refType) + '&refKey=' + encodeURIComponent(text(f.refKey).trim()))
          .then(function (d) { vm.refRows = Array.isArray(d) ? d : []; })
          .catch(function (e) { vm.refRows = []; HIS.notifyError(e); })
          .finally(function () { vm.refBusy = false; });
      }
    },
    template: [
      '<div class="etm-shell">',
      '  <div class="etm-hd">',
      '    <span class="page-title">组件与模板市场</span>',
      '    <el-select v-model="filterType" size="small" style="width:130px" @change="fetch">',
      '      <el-option v-for="t in compTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" size="small" placeholder="搜索标题/简介…" style="width:200px" clearable @keyup.enter="fetch" @clear="fetch"></el-input>',
      '    <el-button size="small" @click="fetch">搜索</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button size="small" @click="openRef">引用分析</el-button>',
      '    <el-button size="small" type="primary" @click="openPublish">上架组件</el-button>',
      '  </div>',
      /* ===== 卡片栅格 ===== */
      '  <div class="etm-grid" v-loading="loading">',
      '    <div v-if="!rows.length && !loading" class="etm-empty">市场暂无上架组件</div>',
      '    <div v-for="r in rows" :key="r.id" class="etm-card">',
      '      <div class="etm-card-hd">',
      '        <el-tag size="small" effect="dark">{{ compLabel(r.compType) }}</el-tag>',
      '        <span class="etm-card-title">{{ r.title }}</span>',
      '      </div>',
      '      <div class="etm-card-sum">{{ r.summary || \'（暂无简介）\' }}</div>',
      '      <div class="etm-card-meta">',
      '        <span v-if="r.category">分类: {{ r.category }}</span>',
      '        <span>来源: {{ scopeLabel(r.scopeLevel) }}</span>',
      '        <span v-if="Number(r.shareScope)===1">医共体跨机构</span>',
      '        <span>克隆: {{ r.downloadCount || 0 }}</span>',
      '      </div>',
      '      <div class="etm-card-acts">',
      '        <el-button size="small" type="primary" plain @click="openClone(r)">克隆为副本</el-button>',
      '        <el-button v-if="isAdmin" size="small" @click="offline(r)">下架</el-button>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ===== 上架弹窗 ===== */
      '  <el-dialog v-model="pubDlg" title="上架组件到市场" width="520px" :close-on-click-modal="false">',
      '    <el-form label-width="80px" size="small">',
      '      <el-form-item label="组件类型">',
      '        <el-select v-model="pubForm.compType" style="width:160px" @change="onPubTypeChange">',
      '          <el-option v-for="t in pubCompTypes" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="母件" v-if="pubForm.compType!==\'elementSet\'">',
      '        <el-select v-model="pubForm.refId" filterable :loading="pubSrcLoading" placeholder="选择要上架的母件" style="width:100%" @change="onPubRefPick">',
      '          <el-option v-for="o in pubSrcList" :key="o.id" :label="o.label" :value="o.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="母件ID" v-else>',
      '        <el-input v-model="pubForm.refId" placeholder="数据元集合标识"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="标题"><el-input v-model="pubForm.title" placeholder="市场展示标题"></el-input></el-form-item>',
      '      <el-form-item label="分类"><el-input v-model="pubForm.category" placeholder="如: 入院记录 / 外科"></el-input></el-form-item>',
      '      <el-form-item label="共享范围">',
      '        <el-radio-group v-model="pubForm.shareScope"><el-radio-button :value="0">全院</el-radio-button><el-radio-button :value="1">医共体跨机构</el-radio-button></el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="简介"><el-input v-model="pubForm.summary" type="textarea" :rows="2" placeholder="用途与亮点"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="pubDlg=false">取消</el-button><el-button type="primary" :loading="pubBusy" @click="doPublish">确认上架</el-button></template>',
      '  </el-dialog>',
      /* ===== 克隆弹窗 ===== */
      '  <el-dialog v-model="cloneDlg" title="克隆为独立副本" width="420px">',
      '    <div style="font-size:13px;color:var(--yb-ink-2);margin-bottom:12px;">将「{{ cloneTarget ? cloneTarget.title : \'\' }}」克隆为独立副本，母件不受影响，克隆后归属您所选层级。</div>',
      '    <el-radio-group v-model="cloneScope">',
      '      <el-radio-button v-for="s in cloneScopes" :key="s.v" :value="s.v" :disabled="s.v!==2 && !isLead">{{ s.l }}</el-radio-button>',
      '    </el-radio-group>',
      '    <div v-if="!isLead" style="font-size:12px;color:var(--yb-ink-4);margin-top:8px;">非牵头机构仅可克隆为个人副本</div>',
      '    <template #footer><el-button @click="cloneDlg=false">取消</el-button><el-button type="primary" :loading="cloneBusy" @click="doClone">确认克隆</el-button></template>',
      '  </el-dialog>',
      /* ===== 引用分析弹窗 ===== */
      '  <el-dialog v-model="refDlg" title="引用反查分析" width="520px">',
      '    <div class="etm-pub-row">',
      '      <el-select v-model="refForm.refType" style="width:120px"><el-option v-for="t in refTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '      <el-input v-model="refForm.refKey" placeholder="引用标识(fieldKey/fragmentId/drawingCode/macroCode)" style="flex:1" @keyup.enter="doRefAnalysis"></el-input>',
      '      <el-button type="primary" :loading="refBusy" @click="doRefAnalysis">查询</el-button>',
      '    </div>',
      '    <div style="max-height:320px;overflow:auto;">',
      '      <div v-if="!refRows.length && !refBusy" style="font-size:13px;color:var(--yb-ink-4);padding:16px;text-align:center;">暂无引用该组件的模板</div>',
      '      <div v-for="t in refRows" :key="t.id" class="etm-ref-tpl">{{ t.templateName }} <span style="color:var(--yb-ink-4);font-size:12px;">{{ t.templateCode }} · {{ scopeLabel(t.scopeLevel) }}</span></div>',
      '    </div>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
