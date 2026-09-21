/* 三目录医保对照工作台: 医疗机构目录(药品/耗材/医疗服务项目) -> 标准字典医保目录
 * 左=院内工作队列(默认仅未对照), 右=打分候选; 支持单条确认对照、批量自动对照(dryRun 预览后提交)、清除对照。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var CATALOGS = [
    { v: 'drug', l: '药品目录' },
    { v: 'cons', l: '耗材目录' },
    { v: 'charge', l: '医疗服务项目' }
  ];
  var ITEM_TYPES = ['诊疗', '药品', '耗材', '其他'];
  var MAPPED_FILTERS = [
    { v: '0', l: '仅未对照' },
    { v: '1', l: '仅已对照' },
    { v: '', l: '全部' }
  ];

  HIS.views.CatalogMap = {
    data: function () {
      return {
        catalogs: CATALOGS, itemTypes: ITEM_TYPES, mappedFilters: MAPPED_FILTERS,
        catalog: 'drug',
        summary: {},
        /* 左: 院内工作队列 */
        mappedFilter: '0', keyword: '', itemType: '诊疗',
        page: 1, size: 20, total: 0, loading: false, list: [], selection: [],
        cur: null,
        /* 右: 打分候选 */
        candLoading: false, candList: [], candSel: null, candKeyword: '',
        /* 批量自动对照 */
        autoDlg: false, autoLoading: false, autoCommitting: false,
        autoThreshold: 0.95, autoPreview: [], autoSel: [], autoStats: {},
        /* 对照变更留痕 */
        logDlg: false, logLoading: false, logList: [], logTotal: 0, logPage: 1, logSize: 20, logOnlyCur: true
      };
    },
    created: function () { this.loadSummary(); this.loadItems(); },
    computed: {
      cov: function () {
        var s = this.summary[this.catalog] || {};
        var t = s.total || 0; var m = s.mapped || 0;
        return { total: t, mapped: m, unmapped: s.unmapped || 0, pct: t ? Math.round(m * 100 / t) : 0 };
      },
      curLabel: function () {
        for (var i = 0; i < CATALOGS.length; i++) { if (CATALOGS[i].v === this.catalog) { return CATALOGS[i].l; } }
        return '';
      }
    },
    methods: {
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/catalog-map/summary').then(function (d) { vm.summary = d || {}; }).catch(function () {});
      },
      onTab: function () {
        this.page = 1; this.keyword = ''; this.cur = null; this.candList = []; this.candSel = null; this.selection = [];
        this.loadItems();
      },
      loadItems: function () {
        var vm = this; vm.loading = true;
        var q = '/api/catalog-map/items?catalog=' + vm.catalog + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.mappedFilter !== '') { q += '&mapped=' + vm.mappedFilter; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.catalog === 'charge' && vm.itemType) { q += '&itemType=' + encodeURIComponent(vm.itemType); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
          vm.cur = null; vm.candList = []; vm.candSel = null;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadItems(); },
      onPage: function (p) { this.page = p; this.loadItems(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seq: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSelChange: function (rows) { this.selection = rows || []; },

      /* 选中院内条目 -> 拉取打分候选 */
      pickRow: function (row) {
        this.cur = row; this.candSel = null; this.candKeyword = ''; this.loadCandidates();
      },
      loadCandidates: function () {
        var vm = this;
        if (!vm.cur) { vm.candList = []; return; }
        vm.candLoading = true;
        var q = '/api/catalog-map/candidates?catalog=' + vm.catalog + '&itemId=' + vm.cur.id + '&limit=50';
        if (vm.candKeyword) { q += '&keyword=' + encodeURIComponent(vm.candKeyword); }
        HIS.get(q)
          .then(function (d) { vm.candList = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.candLoading = false; });
      },
      searchCand: function () { this.candSel = null; this.loadCandidates(); },
      resetCand: function () { this.candKeyword = ''; this.candSel = null; this.loadCandidates(); },
      chooseCand: function (row) { this.candSel = row; },
      confirmMap: function () {
        var vm = this;
        if (!vm.cur || !vm.candSel) { ElementPlus.ElMessage.warning('请先选择院内条目与医保候选'); return; }
        HIS.post('/api/catalog-map/apply', {
          catalog: vm.catalog,
          items: [{ itemId: vm.cur.id, stdId: vm.candSel.stdId }]
        }).then(function () {
          HIS.notifySuccess('已对照: ' + vm.cur.name + ' ← ' + vm.candSel.code);
          vm.loadSummary(); vm.loadItems();
        }).catch(HIS.notifyError);
      },
      clearMap: function (rows) {
        var vm = this;
        var ids = (rows && rows.length ? rows : (vm.cur ? [vm.cur] : [])).map(function (r) { return r.id; });
        if (!ids.length) { ElementPlus.ElMessage.warning('请选择要清除对照的条目'); return; }
        ElementPlus.ElMessageBox.confirm('确认清除 ' + ids.length + ' 条的医保对照码?', '清除对照', { type: 'warning' })
          .then(function () {
            HIS.post('/api/catalog-map/clear', { catalog: vm.catalog, itemIds: ids }).then(function () {
              HIS.notifySuccess('已清除对照'); vm.loadSummary(); vm.loadItems();
            }).catch(HIS.notifyError);
          }).catch(function () {});
      },

      /* 批量自动对照: dryRun 预览 -> 勾选 -> 提交 */
      openAuto: function () {
        this.autoDlg = true; this.autoPreview = []; this.autoSel = []; this.autoStats = {};
        this.runAuto(true);
      },
      runAuto: function (dryRun) {
        var vm = this; vm.autoLoading = true;
        var itemIds = vm.selection.length ? vm.selection.map(function (r) { return r.id; }) : null;
        HIS.post('/api/catalog-map/auto', {
          catalog: vm.catalog, itemIds: itemIds, threshold: vm.autoThreshold, dryRun: dryRun
        }).then(function (d) {
          vm.autoPreview = (d && d.preview) || []; vm.autoSel = [];
          vm.autoStats = { matched: d && d.matched, reviewed: d && d.reviewed, skipped: d && d.skipped, threshold: d && d.threshold };
        }).catch(HIS.notifyError).finally(function () { vm.autoLoading = false; });
      },
      onAutoSel: function (rows) { this.autoSel = rows || []; },
      commitAuto: function () {
        var vm = this;
        var pairs = (vm.autoSel.length ? vm.autoSel : vm.autoPreview);
        if (!pairs.length) { ElementPlus.ElMessage.warning('没有可提交的对照配对'); return; }
        vm.autoCommitting = true;
        HIS.post('/api/catalog-map/apply', {
          catalog: vm.catalog,
          items: pairs.map(function (p) { return { itemId: p.itemId, stdId: p.stdId }; })
        }).then(function (n) {
          HIS.notifySuccess('批量对照完成: 写入 ' + (n || 0) + ' 条');
          vm.autoDlg = false; vm.loadSummary(); vm.loadItems();
        }).catch(HIS.notifyError).finally(function () { vm.autoCommitting = false; });
      },

      /* 对照变更留痕: 新增/变更/清除均记一行, 可按时间点回溯生效医保码 */
      openLogDlg: function () {
        this.logDlg = true; this.logPage = 1; this.logOnlyCur = !!this.cur; this.loadLogs();
      },
      onLogOnlyCur: function () { this.logPage = 1; this.loadLogs(); },
      onLogPage: function (p) { this.logPage = p; this.loadLogs(); },
      loadLogs: function () {
        var vm = this; vm.logLoading = true;
        var q = '/api/catalog-map/logs?catalog=' + vm.catalog + '&page=' + vm.logPage + '&size=' + vm.logSize;
        if (vm.logOnlyCur && vm.cur) { q += '&itemId=' + vm.cur.id; }
        HIS.get(q).then(function (d) {
          vm.logList = (d && d.records) || []; vm.logTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.logLoading = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">三目录医保对照 <span style="font-size:12px;color:#909399;font-weight:normal;">(医疗机构目录 → 标准字典医保目录)</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="从院内已有条目出发补/改医保标准编码: 左栏选院内条目, 右栏按名称/规格/厂家打分给出医保候选; 高置信度可批量自动对照, 其余人工确认。"></el-alert>',

      '  <div class="toolbar">',
      '    <el-radio-group v-model="catalog" @change="onTab">',
      '      <el-radio-button v-for="c in catalogs" :key="c.v" :label="c.v">{{ c.l }}</el-radio-button>',
      '    </el-radio-group>',
      '    <el-progress style="width:220px;" :percentage="cov.pct" :format="function(){return cov.mapped+\'/\'+cov.total;}"></el-progress>',
      '    <span style="color:#909399;font-size:13px;">未对照 {{ cov.unmapped }}</span>',
      '    <el-button @click="loadSummary();loadItems()">刷新</el-button>',
      '  </div>',

      '  <div class="toolbar">',
      '    <el-select v-model="mappedFilter" style="width:110px" @change="search"><el-option v-for="f in mappedFilters" :key="f.v" :label="f.l" :value="f.v"></el-option></el-select>',
      '    <el-select v-if="catalog===\'charge\'" v-model="itemType" style="width:100px" @change="search"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="名称/院内码/医保码" clearable style="width:200px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="warning" @click="openAuto">批量自动对照</el-button>',
      '    <el-button type="danger" plain :disabled="!selection.length" @click="clearMap(selection)">清除选中对照</el-button>',
      '    <el-button @click="openLogDlg">变更记录</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条, 已选 {{ selection.length }}</span>',
      '  </div>',

      '  <el-row :gutter="12">',
      '    <el-col :span="14">',
      '      <el-table :data="list" v-loading="loading" border stripe size="small" height="620" highlight-current-row @current-change="pickRow" @selection-change="onSelChange">',
      '        <el-table-column type="selection" width="42"></el-table-column>',
      '        <el-table-column type="index" label="#" width="50" :index="seq"></el-table-column>',
      '        <el-table-column prop="code" label="院内码" width="100" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="price" label="单价" width="80"></el-table-column>',
      '        <el-table-column :label="catalog===\'charge\'?\'单位\':\'厂家\'" min-width="110" show-overflow-tooltip><template #default="s">{{ catalog===\'charge\'? s.row.unit : s.row.manufacturer }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="prevYbCode" label="变更前码" width="140" show-overflow-tooltip><template #default="s">{{ s.row.prevYbCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="mapEffTime" label="对照生效时间" width="150" show-overflow-tooltip><template #default="s">{{ s.row.mapEffTime || "—" }}</template></el-table-column>',
      '        <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.mapped?\'success\':\'info\'">{{ s.row.mapped?"已对照":"未对照" }}</el-tag></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </el-col>',

      '    <el-col :span="10">',
      '      <div style="border:1px solid #ebeef5;border-radius:4px;padding:10px;">',
      '        <div style="margin-bottom:8px;color:#606266;font-size:13px;">院内条目: <b>{{ cur ? cur.name : "(未选择)" }}</b>',
      '          <span style="float:right;"><el-button type="primary" size="small" :disabled="!cur||!candSel" @click="confirmMap">确认对照</el-button>',
      '          <el-button size="small" :disabled="!cur||!cur.mapped" @click="clearMap([cur])">清除</el-button></span>',
      '        </div>',
      '        <div style="margin-bottom:8px;">',
      '          <el-input v-model="candKeyword" placeholder="检索医保目录(名称/编码)" clearable size="small" style="width:190px" @keyup.enter="searchCand" @clear="searchCand"></el-input>',
      '          <el-button size="small" @click="searchCand">检索</el-button>',
      '          <el-button size="small" link @click="resetCand">自动推荐</el-button>',
      '          <span v-if="candKeyword" style="color:#909399;font-size:12px;">人工检索模式, 可任选一行对照</span>',
      '        </div>',
      '        <el-table :data="candList" v-loading="candLoading" border stripe size="small" height="560" highlight-current-row @current-change="chooseCand">',
      '          <el-table-column prop="code" label="医保编码" width="150" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格/单位" width="90" show-overflow-tooltip></el-table-column>',
      '          <el-table-column v-if="catalog!==\'charge\'" prop="extra" label="厂家/企业" min-width="120" show-overflow-tooltip></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="地方码" width="170" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).loc_item_code }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="项目内涵" min-width="180" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).item_connotation }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="除外内容" min-width="140" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).item_excluded }}</template></el-table-column>',
      '          <el-table-column v-if="catalog!==\'drug\'" label="支付标准" width="80"><template #default="s">{{ (s.row.row||{}).pay_std }}</template></el-table-column>',
      '          <el-table-column prop="score" label="置信度" width="70"></el-table-column>',
      '          <el-table-column label="依据" min-width="120" show-overflow-tooltip><template #default="s">{{ (s.row.reasons||[]).join(" / ") }}</template></el-table-column>',
      '        </el-table>',
      '        <div v-if="cur && !candLoading && !candList.length" style="color:#909399;font-size:13px;margin-top:8px;">无匹配候选, 可在上方输入关键字检索医保目录后任选一行对照。</div>',
      '      </div>',
      '    </el-col>',
      '  </el-row>',

      /* ==== 批量自动对照预览弹窗 ==== */
      '  <el-dialog v-model="autoDlg" :title="\'批量自动对照 - \'+curLabel" width="860px" top="6vh">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"',
      '      title="对未对照条目跑匹配器, 仅名称+规格全同(默认阈值0.95)进入预览; 勾选后提交写入, 未达标条目留人工复核。"></el-alert>',
      '    <div class="toolbar">',
      '      <span style="color:#606266;font-size:13px;">阈值</span>',
      '      <el-input-number v-model="autoThreshold" :min="0.5" :max="0.99" :step="0.05" :precision="2" style="width:120px"></el-input-number>',
      '      <el-button @click="runAuto(true)" :loading="autoLoading">重新预览</el-button>',
      '      <span style="color:#909399;font-size:13px;">达标 {{ autoStats.matched||0 }} · 待复核 {{ autoStats.reviewed||0 }} · 跳过 {{ autoStats.skipped||0 }}</span>',
      '      <span style="color:#909399;font-size:13px;">{{ selection.length? "(仅选中 "+selection.length+" 条)": "(全部未对照)" }}</span>',
      '    </div>',
      '    <el-table :data="autoPreview" v-loading="autoLoading" border stripe size="small" height="380" @selection-change="onAutoSel">',
      '      <el-table-column type="selection" width="42"></el-table-column>',
      '      <el-table-column prop="itemCode" label="院内码" width="100" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="itemName" label="院内名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="stdCode" label="医保编码" width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="stdName" label="医保名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="score" label="置信度" width="80"></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="autoDlg=false">取消</el-button><el-button type="primary" :loading="autoCommitting" @click="commitAuto">提交对照({{ (autoSel.length||autoPreview.length) }})</el-button></template>',
      '  </el-dialog>',

      /* ==== 对照变更留痕弹窗 ==== */
      '  <el-dialog v-model="logDlg" :title="\'对照变更记录 - \'+curLabel" width="980px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-checkbox v-model="logOnlyCur" :disabled="!cur" @change="onLogOnlyCur">仅当前选中条目{{ cur ? "(" + cur.name + ")" : "" }}</el-checkbox>',
      '      <span style="color:#909399;font-size:13px;">每次新增/变更/清除对照均留痕; 变更时间之前用原医保码、之后用新医保码, 可回溯任意时点生效码。</span>',
      '    </div>',
      '    <el-table :data="logList" v-loading="logLoading" border stripe size="small" height="420">',
      '      <el-table-column prop="changeTime" label="变更时间" width="150"></el-table-column>',
      '      <el-table-column label="类型" width="90"><template #default="s"><el-tag size="small" :type="s.row.changeType===\'CLEAR\'?\'danger\':(s.row.changeType===\'CHANGE\'?\'warning\':\'success\')">{{ s.row.changeType }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="itemCode" label="院内码" width="100" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="原医保码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.oldCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="新医保码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.newCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="方式" width="70"><template #default="s">{{ s.row.src === \'auto\' ? \'自动\' : \'人工\' }}</template></el-table-column>',
      '      <el-table-column label="操作人" width="90" show-overflow-tooltip><template #default="s">{{ s.row.operatorName || s.row.operator }}</template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, prev, pager, next" :total="logTotal" :page-size="logSize" :current-page="logPage" @current-change="onLogPage"></el-pagination>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
