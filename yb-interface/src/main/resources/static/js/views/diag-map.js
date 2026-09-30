/* 医保疾病对照工作台: 医共体统一诊断字典(西医疾病/中医疾病/中医症候/手术编码) -> 医保标准字典
 * 左=院内诊断工作队列(默认仅未对照), 右=按名称打分的标准字典候选; 支持单条确认对照、批量自动对照、清除对照、变更留痕、导出。
 * 参照三目录医保对照(catalog-map.js)实现。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var TYPES = [
    { v: 'west', l: '西医疾病', std: '医保ICD10疾病诊断' },
    { v: 'tcm', l: '中医疾病', std: '中医疾病分类与代码' },
    { v: 'symp', l: '中医症候', std: '中医证候分类与代码' },
    { v: 'oper', l: '手术编码', std: '医保ICD9手术操作' }
  ];
  var MAPPED_FILTERS = [
    { v: '0', l: '仅未对照' },
    { v: '1', l: '仅已对照' },
    { v: '', l: '全部' }
  ];

  HIS.views.DiagMap = {
    data: function () {
      return {
        types: TYPES, mappedFilters: MAPPED_FILTERS,
        dictType: 'west',
        summary: {},
        /* 左: 院内诊断工作队列 */
        mappedFilter: '0', keyword: '',
        page: 1, size: 20, total: 0, loading: false, list: [], selection: [],
        cur: null,
        /* 右: 打分候选 */
        candLoading: false, candList: [], candSel: null, candKeyword: '',
        /* 左右栏扩展: ''=并排, left/right=单栏扩展 */
        panelMode: '',
        /* 批量自动对照 */
        autoDlg: false, autoLoading: false, autoCommitting: false,
        autoThreshold: 0.95, autoPreview: [], autoSel: [], autoStats: {},
        /* 对照变更留痕 */
        logDlg: false, logLoading: false, logList: [], logTotal: 0, logPage: 1, logSize: 20, logOnlyCur: true, logRange: [], logKw: '',
        /* 导出 */
        exporting: false,
        autoSwitched: false
      };
    },
    created: function () { this.loadSummary(); this.loadItems(); },
    computed: {
      cov: function () {
        var s = this.summary[this.dictType] || {};
        var t = s.total || 0; var m = s.mapped || 0;
        return { total: t, mapped: m, unmapped: s.unmapped || 0, pct: t ? Math.round(m * 100 / t) : 0 };
      },
      curLabel: function () {
        for (var i = 0; i < TYPES.length; i++) { if (TYPES[i].v === this.dictType) { return TYPES[i].l; } }
        return '';
      },
      stdLabel: function () {
        for (var i = 0; i < TYPES.length; i++) { if (TYPES[i].v === this.dictType) { return TYPES[i].std; } }
        return '';
      },
      leftSpan: function () { return this.panelMode === 'left' ? 22 : (this.panelMode === 'right' ? 2 : 14); },
      rightSpan: function () { return this.panelMode === 'right' ? 22 : (this.panelMode === 'left' ? 2 : 10); }
    },
    methods: {
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/diag-map/summary').then(function (d) { vm.summary = d || {}; }).catch(function () {});
      },
      onTab: function () {
        this.page = 1; this.keyword = ''; this.cur = null; this.candList = []; this.candSel = null; this.selection = [];
        this.autoSwitched = false;
        this.loadItems();
      },
      loadItems: function () {
        var vm = this; vm.loading = true;
        var q = '/api/diag-map/items?dictType=' + vm.dictType + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.mappedFilter !== '') { q += '&mapped=' + vm.mappedFilter; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
          if (vm.mappedFilter === '0' && vm.total === 0 && !vm.autoSwitched) {
            vm.autoSwitched = true; vm.mappedFilter = ''; vm.page = 1;
            ElementPlus.ElMessage.info('当前筛选下已无未对照数据, 已自动切换为"全部"');
            vm.loadItems();
            return;
          }
          vm.cur = null; vm.candList = []; vm.candSel = null;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.autoSwitched = false; this.loadItems(); },
      onPage: function (p) { this.page = p; this.loadItems(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seq: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSelChange: function (rows) { this.selection = rows || []; },
      stText: function (r) { return (!r || !r.mapped) ? '未对照' : '已对照'; },
      stType: function (r) { return (!r || !r.mapped) ? 'info' : 'success'; },

      /* 选中院内条目 -> 拉取打分候选 */
      pickRow: function (row) { this.cur = row; this.candSel = null; this.candKeyword = ''; this.loadCandidates(); },
      loadCandidates: function () {
        var vm = this;
        if (!vm.cur) { vm.candList = []; return; }
        vm.candLoading = true;
        var q = '/api/diag-map/candidates?dictType=' + vm.dictType + '&itemId=' + vm.cur.id + '&limit=50';
        if (vm.candKeyword) { q += '&keyword=' + encodeURIComponent(vm.candKeyword); }
        HIS.get(q).then(function (d) { vm.candList = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.candLoading = false; });
      },
      searchCand: function () { this.candSel = null; this.loadCandidates(); },
      resetCand: function () { this.candKeyword = ''; this.candSel = null; this.loadCandidates(); },
      chooseCand: function (row) { this.candSel = row; },
      confirmMap: function () {
        var vm = this;
        if (!vm.cur || !vm.candSel) { ElementPlus.ElMessage.warning('请先选择诊断条目与医保候选'); return; }
        var oldCode = vm.cur.ybCode || '';
        var newCode = vm.candSel.code || '';
        if (vm.cur.mapped && oldCode && oldCode === newCode) {
          ElementPlus.ElMessage.info('该条目已对照到相同医保码 ' + newCode + ', 无需重复对照');
          return;
        }
        if (vm.cur.mapped && oldCode) {
          ElementPlus.ElMessageBox.confirm(
            '诊断条目: <b>' + vm.escText(vm.cur.name) + '</b><br/>'
            + '当前对照: ' + vm.escText(oldCode) + (vm.cur.ybName ? ' (' + vm.escText(vm.cur.ybName) + ')' : '') + '<br/>'
            + '变更为: <b style="color:var(--yb-warning);">' + vm.escText(newCode) + '</b>' + (vm.candSel.name ? ' (' + vm.escText(vm.candSel.name) + ')' : '') + '<br/>'
            + '<span style="color:var(--yb-ink-2);font-size:12px;">确认后写入变更记录(CHANGE)。</span>',
            '变更对照确认',
            { type: 'warning', confirmButtonText: '确认变更对照', cancelButtonText: '取消', dangerouslyUseHTMLString: true }
          ).then(function () { vm.doApply(true, '已变更对照'); }).catch(function () {});
          return;
        }
        vm.doApply(false, '已对照');
      },
      doApply: function (force, okText) {
        var vm = this;
        var name = vm.cur.name, code = vm.candSel.code;
        HIS.post('/api/diag-map/apply', {
          catalog: vm.dictType, force: force,
          items: [{ itemId: vm.cur.id, stdId: vm.candSel.stdId }]
        }).then(function () {
          HIS.notifySuccess(okText + ': ' + name + ' ← ' + code);
          vm.candSel = null;
          vm.loadSummary(); vm.loadItems();
        }).catch(HIS.notifyError);
      },
      escText: function (v) {
        return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;')
          .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
      },
      clearMap: function (rows) {
        var vm = this;
        var ids = (rows && rows.length ? rows : (vm.cur ? [vm.cur] : [])).map(function (r) { return r.id; });
        if (!ids.length) { ElementPlus.ElMessage.warning('请选择要清除对照的条目'); return; }
        ElementPlus.ElMessageBox.confirm('确认清除 ' + ids.length + ' 条的医保对照码?', '清除对照', { type: 'warning' })
          .then(function () {
            HIS.post('/api/diag-map/clear', { catalog: vm.dictType, itemIds: ids }).then(function () {
              HIS.notifySuccess('已清除对照'); vm.loadSummary(); vm.loadItems();
            }).catch(HIS.notifyError);
          }).catch(function () {});
      },
      exportRows: function () {
        var vm = this;
        var q = '/api/diag-map/export?dictType=' + vm.dictType;
        if (vm.mappedFilter !== '') { q += '&mapped=' + vm.mappedFilter; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        vm.exporting = true;
        HIS.download(q).then(function (name) {
          HIS.notifySuccess('已导出 ' + vm.total + ' 条: ' + name);
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },

      /* 批量自动对照: dryRun 预览 -> 勾选 -> 提交 */
      openAuto: function () {
        this.autoDlg = true; this.autoPreview = []; this.autoSel = []; this.autoStats = {};
        this.runAuto(true);
      },
      runAuto: function (dryRun) {
        var vm = this; vm.autoLoading = true;
        var itemIds = vm.selection.length ? vm.selection.map(function (r) { return r.id; }) : null;
        HIS.post('/api/diag-map/auto', {
          catalog: vm.dictType, itemIds: itemIds, threshold: vm.autoThreshold, dryRun: dryRun
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
        HIS.post('/api/diag-map/apply', {
          catalog: vm.dictType,
          items: pairs.map(function (p) { return { itemId: p.itemId, stdId: p.stdId }; })
        }).then(function (n) {
          HIS.notifySuccess('批量对照完成: 写入 ' + (n || 0) + ' 条');
          vm.autoDlg = false; vm.loadSummary(); vm.loadItems();
        }).catch(HIS.notifyError).finally(function () { vm.autoCommitting = false; });
      },

      /* 对照变更留痕 */
      openLogDlg: function () {
        var vm = this;
        var d = new Date();
        var daysPrev = new Date(d.getFullYear(), d.getMonth(), 0).getDate();
        var s = new Date(d.getFullYear(), d.getMonth() - 1, Math.min(d.getDate(), daysPrev));
        vm.logRange = [vm.fmtDate(s), vm.fmtDate(d)];
        vm.logKw = '';
        vm.logDlg = true; vm.logPage = 1; vm.logOnlyCur = !!vm.cur; vm.loadLogs();
      },
      onLogOnlyCur: function () { this.logPage = 1; this.loadLogs(); },
      onLogPage: function (p) { this.logPage = p; this.loadLogs(); },
      onLogSize: function (s) { this.logSize = s; this.onLogPage(1); },
      logSeq: function (i) { return (this.logPage - 1) * this.logSize + i + 1; },
      onLogFilter: function () { this.logPage = 1; this.loadLogs(); },
      fmtDate: function (d) { var p = function (n) { return n < 10 ? '0' + n : '' + n; }; return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); },
      togglePanel: function (mode) { this.panelMode = (this.panelMode === mode ? '' : mode); },
      fmtType: function (t) {
        return { MAP: '新增对照', CHANGE: '变更对照', CLEAR: '清除对照' }[t] || t;
      },
      loadLogs: function () {
        var vm = this; vm.logLoading = true;
        var q = '/api/diag-map/logs?dictType=' + vm.dictType + '&page=' + vm.logPage + '&size=' + vm.logSize;
        if (vm.logOnlyCur && vm.cur) { q += '&itemId=' + vm.cur.id; }
        if (vm.logKw) { q += '&kw=' + encodeURIComponent(vm.logKw); }
        if (vm.logRange && vm.logRange.length === 2 && vm.logRange[0] && vm.logRange[1]) { q += '&start=' + vm.logRange[0] + '&end=' + vm.logRange[1]; }
        HIS.get(q).then(function (d) {
          vm.logList = (d && d.records) || []; vm.logTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.logLoading = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医保疾病对照 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(医共体统一诊断字典 → 医保标准字典)</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="从院内诊断字典条目出发补/改医保标准编码: 左栏选诊断条目, 右栏按名称打分给出医保候选; 高置信度可批量自动对照, 其余人工确认。"></el-alert>',

      '  <div class="toolbar">',
      '    <el-radio-group v-model="dictType" @change="onTab">',
      '      <el-radio-button v-for="t in types" :key="t.v" :label="t.v">{{ t.l }}</el-radio-button>',
      '    </el-radio-group>',
      '    <el-progress style="width:220px;" :percentage="cov.pct" :format="function(){return cov.mapped+\'/\'+cov.total;}"></el-progress>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">未对照 {{ cov.unmapped }}</span>',
      '    <el-button @click="loadSummary();loadItems()">刷新</el-button>',
      '  </div>',

      '  <div class="toolbar">',
      '    <el-select v-model="mappedFilter" style="width:120px" @change="search"><el-option v-for="f in mappedFilters" :key="f.v" :label="f.l" :value="f.v"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="名称/院内码/拼音简码/医保码" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="warning" @click="openAuto">批量自动对照</el-button>',
      '    <el-button type="danger" plain :disabled="!selection.length" @click="clearMap(selection)">清除选中对照</el-button>',
      '    <el-button @click="openLogDlg">变更记录</el-button>',
      '    <el-button :loading="exporting" @click="exportRows">导出结果</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条, 已选 {{ selection.length }}</span>',
      '  </div>',

      '  <el-row :gutter="12">',
      '    <el-col :span="leftSpan">',
      '      <div v-if="panelMode!==\'right\'">',
      '      <div style="margin-bottom:6px;color:var(--yb-ink-2);font-size:13px;">院内诊断工作队列<span style="float:right;"><el-button link type="primary" size="small" @click="togglePanel(\'left\')">{{ panelMode===\'left\'?\'还原布局\':\'扩展左栏\' }}</el-button></span></div>',
      '      <el-table :data="list" v-loading="loading" border stripe size="small" height="620" highlight-current-row @current-change="pickRow" @selection-change="onSelChange">',
      '        <el-table-column type="selection" width="42"></el-table-column>',
      '        <el-table-column type="index" label="序号" width="60" :index="seq"></el-table-column>',
      '        <el-table-column prop="code" label="院内码" width="130" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="category" label="类目" min-width="130" show-overflow-tooltip><template #default="s">{{ s.row.category || "—" }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保码" width="130" show-overflow-tooltip><template #default="s">{{ s.row.ybCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="ybName" label="医保名称" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.ybName || "—" }}</template></el-table-column>',
      '        <el-table-column label="状态" width="86"><template #default="s"><el-tag size="small" :type="stType(s.row)">{{ stText(s.row) }}</el-tag></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10,20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '      </div>',
      '      <div v-else style="padding:6px 0;"><el-button size="small" @click="togglePanel(\'\')">‹ 展开左栏</el-button></div>',
      '    </el-col>',

      '    <el-col :span="rightSpan">',
      '      <div v-if="panelMode!==\'left\'" style="border:1px solid var(--yb-border-light);border-radius:4px;padding:10px;">',
      '        <div style="margin-bottom:8px;color:var(--yb-ink-2);font-size:13px;">院内条目: <b>{{ cur ? cur.name : "(未选择)" }}</b> · 对照源: {{ stdLabel }}',
      '          <span style="float:right;"><el-button link type="primary" size="small" @click="togglePanel(\'right\')">{{ panelMode===\'right\'?\'还原布局\':\'扩展右栏\' }}</el-button>',
      '          <el-button type="primary" size="small" :disabled="!cur||!candSel" @click="confirmMap">确认对照</el-button>',
      '          <el-button size="small" :disabled="!cur||!cur.mapped" @click="clearMap([cur])">清除</el-button></span>',
      '        </div>',
      '        <div style="margin-bottom:8px;">',
      '          <el-input v-model="candKeyword" placeholder="检索医保标准字典(名称/编码)" clearable size="small" style="width:220px" @keyup.enter="searchCand" @clear="searchCand"></el-input>',
      '          <el-button size="small" @click="searchCand">检索</el-button>',
      '          <el-button size="small" link @click="resetCand">自动推荐</el-button>',
      '          <span v-if="candKeyword" style="color:var(--yb-ink-2);font-size:12px;">人工检索模式, 可任选一行对照</span>',
      '          <span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">共 {{ candList.length }} 条</span>',
      '        </div>',
      '        <el-table :data="candList" v-loading="candLoading" border stripe size="small" height="560" highlight-current-row @current-change="chooseCand">',
      '          <el-table-column type="index" label="序号" width="60" align="right"></el-table-column>',
      '          <el-table-column prop="code" label="医保编码" width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="类目" min-width="120" show-overflow-tooltip><template #default="s">{{ s.row.spec || "—" }}</template></el-table-column>',
      '          <el-table-column prop="extra" label="亚目" min-width="120" show-overflow-tooltip><template #default="s">{{ s.row.extra || "—" }}</template></el-table-column>',
      '          <el-table-column prop="score" label="置信度" width="80"></el-table-column>',
      '          <el-table-column label="依据" min-width="120" show-overflow-tooltip><template #default="s">{{ (s.row.reasons||[]).join(" / ") }}</template></el-table-column>',
      '        </el-table>',
      '        <div v-if="cur && !candLoading && !candList.length" style="color:var(--yb-ink-2);font-size:13px;margin-top:8px;">无匹配候选, 可在上方输入关键字检索医保标准字典后任选一行对照。</div>',
      '      </div>',
      '      <div v-else style="padding:6px 0;"><el-button size="small" @click="togglePanel(\'\')">展开右栏 ›</el-button></div>',
      '    </el-col>',
      '  </el-row>',

      /* ==== 批量自动对照预览弹窗 ==== */
      '  <el-dialog v-model="autoDlg" :title="\'批量自动对照 - \'+curLabel" width="860px" top="6vh">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"',
      '      title="对未对照条目按名称全同匹配(默认阈值0.95)跑匹配器进入预览; 勾选后提交写入, 未达标条目留人工复核。"></el-alert>',
      '    <div class="toolbar">',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">阈值</span>',
      '      <el-input-number v-model="autoThreshold" :min="0.5" :max="0.99" :step="0.05" :precision="2" style="width:120px"></el-input-number>',
      '      <el-button @click="runAuto(true)" :loading="autoLoading">重新预览</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">达标 {{ autoStats.matched||0 }} · 待复核 {{ autoStats.reviewed||0 }} · 跳过 {{ autoStats.skipped||0 }}</span>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">{{ selection.length? "(仅选中 "+selection.length+" 条)": "(全部未对照)" }}</span>',
      '    </div>',
      '    <el-table :data="autoPreview" v-loading="autoLoading" border stripe size="small" height="380" @selection-change="onAutoSel">',
      '      <el-table-column type="selection" width="42"></el-table-column>',
      '      <el-table-column prop="itemCode" label="院内码" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="itemName" label="院内名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="stdCode" label="医保编码" width="140" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="stdName" label="医保名称" min-width="160" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="score" label="置信度" width="80"></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="autoDlg=false">取消</el-button><el-button type="primary" :loading="autoCommitting" @click="commitAuto">提交对照({{ (autoSel.length||autoPreview.length) }})</el-button></template>',
      '  </el-dialog>',

      /* ==== 对照变更留痕弹窗 ==== */
      '  <el-dialog v-model="logDlg" :title="\'对照变更记录 - \'+curLabel" width="960px" top="6vh">',
      '    <div class="toolbar">',
      '      <el-checkbox v-model="logOnlyCur" :disabled="!cur" @change="onLogOnlyCur">仅当前选中条目{{ cur ? "(" + cur.name + ")" : "" }}</el-checkbox>',
      '      <el-date-picker v-model="logRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:240px" @change="onLogFilter"></el-date-picker>',
      '      <el-input v-model="logKw" placeholder="院内码/院内名/医保码" clearable style="width:200px" @keyup.enter="onLogFilter" @clear="onLogFilter"></el-input>',
      '      <el-button type="primary" size="small" @click="onLogFilter">查询</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">每次新增/变更/清除对照均留痕。</span>',
      '    </div>',
      '    <el-table :data="logList" v-loading="logLoading" border stripe size="small" height="420">',
      '      <el-table-column type="index" label="序号" width="60" :index="logSeq"></el-table-column>',
      '      <el-table-column prop="changeTime" label="变更时间" width="150"></el-table-column>',
      '      <el-table-column label="类型" width="100"><template #default="s"><el-tag size="small" :type="s.row.changeType===\'CLEAR\'?\'danger\':(s.row.changeType===\'CHANGE\'?\'warning\':\'success\')">{{ fmtType(s.row.changeType) }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="itemCode" label="院内码" width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="itemName" label="名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="原医保码" width="130" show-overflow-tooltip><template #default="s">{{ s.row.oldCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="新医保码" width="130" show-overflow-tooltip><template #default="s">{{ s.row.newCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="方式" width="70"><template #default="s">{{ s.row.src === \'auto\' ? \'自动\' : \'人工\' }}</template></el-table-column>',
      '      <el-table-column label="操作人" width="90" show-overflow-tooltip><template #default="s">{{ s.row.operatorName || s.row.operator }}</template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="logTotal" :page-size="logSize" :page-sizes="[10,20,50,100]" :current-page="logPage" @current-change="onLogPage" @size-change="onLogSize"></el-pagination>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
