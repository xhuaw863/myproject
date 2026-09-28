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
    { v: '2', l: '对照失效' },
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
        /* 左右栏扩展状态: ''=并排, 'left'=左栏扩展(收缩右栏), 'right'=右栏扩展(收缩左栏) */
        panelMode: '',
        /* 对照变更留痕 */
        logDlg: false, logLoading: false, logList: [], logTotal: 0, logPage: 1, logSize: 20, logOnlyCur: true, logRange: [], logKw: '',
        /* 3301/3302 上报队列(M4) */
        upDlg: false, upLoading: false, upRunning: false, upRetrying: false,
        upCatalog: '', upStatus: null, upList: [], upTotal: 0, upPage: 1, upSize: 20,
        /* 导出对照结果 */
        exporting: false,
        /* 已自动将"仅未对照"切为"全部"(避免空列表), 每次查询/切目录重置 */
        autoSwitched: false
      };
    },
    created: function () { this.loadSummary(); this.loadItems(); },
    computed: {
      cov: function () {
        var s = this.summary[this.catalog] || {};
        var t = s.total || 0; var m = s.mapped || 0;
        return { total: t, mapped: m, unmapped: s.unmapped || 0, invalid: s.invalid || 0, pct: t ? Math.round(m * 100 / t) : 0 };
      },
      curLabel: function () {
        for (var i = 0; i < CATALOGS.length; i++) { if (CATALOGS[i].v === this.catalog) { return CATALOGS[i].l; } }
        return '';
      },
      leftSpan: function () { return this.panelMode === 'left' ? 22 : (this.panelMode === 'right' ? 2 : 14); },
      rightSpan: function () { return this.panelMode === 'right' ? 22 : (this.panelMode === 'left' ? 2 : 10); }
    },
    methods: {
      /* 左栏甲乙丙列显示: 药品/耗材为院内回填名称或编码1~4直接透传; 服务项目自身存01/02/03码映射为甲/乙/丙; 空显示— */
      lvText: function (v) {
        if (!v) return "—";
        var m = { '01': '甲', '02': '乙', '03': '丙' };
        return m[v] || v;
      },
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/catalog-map/summary').then(function (d) { vm.summary = d || {}; }).catch(function () {});
      },
      onTab: function () {
        this.page = 1; this.keyword = ''; this.cur = null; this.candList = []; this.candSel = null; this.selection = [];
        this.autoSwitched = false;
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
          /* 无未对照数据时自动切"全部", 避免打开页面就是空列表 */
          if (vm.mappedFilter === '0' && vm.total === 0 && !vm.autoSwitched) {
            vm.autoSwitched = true; vm.mappedFilter = ''; vm.page = 1;
            ElementPlus.ElMessage.info('当前筛选下已无未对照数据, 已自动切换为"全部"');
            vm.loadItems();
            return;
          }
          /* 生效时间归一为 yyyy-MM-dd HH:mm:ss, 供日期时间控件回显 */
          for (var i = 0; i < vm.list.length; i++) {
            if (vm.list[i].mapEffTime) { vm.list[i].mapEffTime = String(vm.list[i].mapEffTime).replace('T', ' '); }
          }
          vm.cur = null; vm.candList = []; vm.candSel = null;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.autoSwitched = false; this.loadItems(); },
      onPage: function (p) { this.page = p; this.loadItems(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seq: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSelChange: function (rows) { this.selection = rows || []; },
      /* 对照状态标签: 已对照但医保码已作废/过期/删除 => 失效, 需重新对照 */
      stText: function (r) {
        if (!r || !r.mapped) { return '未对照'; }
        return r.ybValid === false ? (r.ybInvalidReason || '失效') : '已对照';
      },
      stType: function (r) {
        if (!r || !r.mapped) { return 'info'; }
        return r.ybValid === false ? 'danger' : 'success';
      },
      stTitle: function (r) {
        if (!r || !r.mapped) { return '尚未对照医保码'; }
        if (r.ybValid === false) {
          return '医保码 ' + (r.ybCode || '') + ' 在标准字典中' + (r.ybInvalidReason || '已失效') + ', 需重新对照';
        }
        return '医保码 ' + (r.ybCode || '') + ' 在标准字典中有效';
      },
      /* 失效行整行淡红底, 便于扫视待重新对照的条目 */
      rowCls: function (o) {
        return (o && o.row && o.row.mapped && o.row.ybValid === false) ? 'row-yb-invalid' : '';
      },
      /* 快捷筛选对照失效条目 */
      filterInvalid: function () { this.mappedFilter = '2'; this.search(); },

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
        var oldCode = vm.cur.ybCode || '';
        var newCode = vm.candSel.code || '';
        /* 已对照到同一医保码: 无需重复操作; 但若该码已失效, 应提示另选有效编码 */
        if (vm.cur.mapped && oldCode && oldCode === newCode) {
          if (vm.cur.ybValid === false) {
            ElementPlus.ElMessage.warning('医保码 ' + newCode + ' 在标准字典中'
              + (vm.cur.ybInvalidReason || '已失效') + ', 请另选有效的医保码重新对照');
          } else {
            ElementPlus.ElMessage.info('该条目已对照到相同医保码 ' + newCode + ', 无需重复对照');
          }
          return;
        }
        /* 已对照且目标码不同 => 变更对照, 二次确认后才写入, 避免误操作 */
        if (vm.cur.mapped && oldCode) {
          ElementPlus.ElMessageBox.confirm(
            '院内条目: <b>' + vm.escText(vm.cur.name) + '</b><br/>'
            + '当前对照: ' + vm.escText(oldCode) + (vm.cur.ybName ? ' (' + vm.escText(vm.cur.ybName) + ')' : '') + '<br/>'
            + '变更为: <b style="color:var(--yb-warning);">' + vm.escText(newCode) + '</b>' + (vm.candSel.name ? ' (' + vm.escText(vm.candSel.name) + ')' : '') + '<br/>'
            + (vm.cur.ybValid === false
              ? '<span style="color:var(--yb-danger);font-size:12px;">当前医保码在标准字典中' + vm.escText(vm.cur.ybInvalidReason || '已失效') + ', 应重新对照到有效的医保项目。</span><br/>' : '')
            + '<span style="color:var(--yb-ink-2);font-size:12px;">确认后原医保码记入“变更前码”, 生效时间更新为当前时刻, 并写入变更记录(CHANGE)。</span>',
            '变更对照确认',
            { type: 'warning', confirmButtonText: '确认变更对照', cancelButtonText: '取消', dangerouslyUseHTMLString: true }
          ).then(function () { vm.doApply(true, '已变更对照'); }).catch(function () {});
          return;
        }
        vm.doApply(false, '已对照');
      },
      /* 写入对照: force=true 表示已确认变更对照(已对照条目改码必须带) */
      doApply: function (force, okText) {
        var vm = this;
        var name = vm.cur.name, code = vm.candSel.code;
        HIS.post('/api/catalog-map/apply', {
          catalog: vm.catalog, force: force,
          items: [{ itemId: vm.cur.id, stdId: vm.candSel.stdId }]
        }).then(function () {
          HIS.notifySuccess(okText + ': ' + name + ' ← ' + code);
          vm.candSel = null;
          vm.loadSummary(); vm.loadItems();
        }).catch(HIS.notifyError);
      },
      /* 导出对照结果(xlsx): 沿用当前筛选条件, 导出全部匹配行(非仅本页) */
      exportRows: function () {
        var vm = this;
        var q = '/api/catalog-map/export?catalog=' + vm.catalog;
        if (vm.mappedFilter !== '') { q += '&mapped=' + vm.mappedFilter; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.catalog === 'charge' && vm.itemType) { q += '&itemType=' + encodeURIComponent(vm.itemType); }
        vm.exporting = true;
        HIS.download(q).then(function (name) {
          HIS.notifySuccess('已导出 ' + vm.total + ' 条: ' + name);
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
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
        var vm = this;
        /* 默认时间段 = 最近一个月(上月同日, 按上月天数自动收口) */
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
      /* 单栏扩展/还原: 扩展一侧时另一侧收缩为窄条 */
      togglePanel: function (mode) { this.panelMode = (this.panelMode === mode ? '' : mode); },
      fmtType: function (t) {
        return { MAP: '新增对照', CHANGE: '变更对照', CLEAR: '清除对照', EFF: '调生效时间' }[t] || t;
      },
      /* 修改对照生效时间(留痕 EFF); 清空则置空生效时间 */
      saveEff: function (row) {
        var vm = this;
        HIS.post('/api/catalog-map/eff-time', { catalog: vm.catalog, itemId: row.id, effTime: row.mapEffTime || '' })
          .then(function () { HIS.notifySuccess('生效时间已更新: ' + row.name); })
          .catch(function (e) { HIS.notifyError(e); vm.loadItems(); });
      },
      loadLogs: function () {
        var vm = this; vm.logLoading = true;
        var q = '/api/catalog-map/logs?catalog=' + vm.catalog + '&page=' + vm.logPage + '&size=' + vm.logSize;
        if (vm.logOnlyCur && vm.cur) { q += '&itemId=' + vm.cur.id; }
        if (vm.logKw) { q += '&kw=' + encodeURIComponent(vm.logKw); }
        if (vm.logRange && vm.logRange.length === 2 && vm.logRange[0] && vm.logRange[1]) { q += '&start=' + vm.logRange[0] + '&end=' + vm.logRange[1]; }
        HIS.get(q).then(function (d) {
          vm.logList = (d && d.records) || []; vm.logTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.logLoading = false; });
      },

      /* ==== 3301/3302 上报队列(M4): 对照变更自动入队, 手动触发先撤(3302)后传(3301), 每批≤100条 ==== */
      openUpDlg: function () { this.upDlg = true; this.upPage = 1; this.loadQueue(); },
      onUpFilter: function () { this.upPage = 1; this.loadQueue(); },
      onUpPage: function (p) { this.upPage = p; this.loadQueue(); },
      onUpSize: function (s) { this.upSize = s; this.onUpPage(1); },
      upSeq: function (i) { return (this.upPage - 1) * this.upSize + i + 1; },
      upCatalogText: function (t) { return { drug: '药品', cons: '耗材', charge: '医疗服务项目' }[t] || t; },
      upActionText: function (t) {
        return { MAP: '新增对照→3301', CHANGE: '变更对照→先撤后传', CLEAR: '清除对照→3302' }[t] || t;
      },
      loadQueue: function () {
        var vm = this; vm.upLoading = true;
        var q = '/api/yb/catalog-upload/queue?page=' + vm.upPage + '&size=' + vm.upSize;
        if (vm.upCatalog) { q += '&catalog=' + vm.upCatalog; }
        if (vm.upStatus !== null && vm.upStatus !== undefined && vm.upStatus !== '') { q += '&status=' + vm.upStatus; }
        HIS.get(q).then(function (d) {
          vm.upList = (d && d.records) || []; vm.upTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.upLoading = false; });
      },
      runUp: function () {
        var vm = this; vm.upRunning = true;
        HIS.post('/api/yb/catalog-upload/run', {}).then(function (d) {
          HIS.notifySuccess('上报完成: 撤销 ' + (d.revoked || 0) + ' 条 / 上传 ' + (d.uploaded || 0) + ' 条 / 失败 ' + (d.failed || 0) + ' 条');
          vm.loadQueue();
        }).catch(HIS.notifyError).finally(function () { vm.upRunning = false; });
      },
      retryUp: function () {
        var vm = this; vm.upRetrying = true;
        HIS.post('/api/yb/catalog-upload/retry', {}).then(function (msg) {
          HIS.notifySuccess(msg); vm.loadQueue();
        }).catch(HIS.notifyError).finally(function () { vm.upRetrying = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医保目录对照 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(医疗机构目录 → 标准字典医保目录)</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="从院内已有条目出发补/改医保标准编码: 左栏选院内条目, 右栏按名称/规格/厂家打分给出医保候选; 高置信度可批量自动对照, 其余人工确认。"></el-alert>',
      '  <el-alert v-if="mappedFilter===\'2\'" type="warning" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="对照失效: 下列条目已对照的医保码在标准字典中已作废/已过期或已被删除, 需重新对照到新的医保项目(可直接选新码变更对照, 或先清除再对照)。"></el-alert>',

      '  <div class="toolbar">',
      '    <el-radio-group v-model="catalog" @change="onTab">',
      '      <el-radio-button v-for="c in catalogs" :key="c.v" :label="c.v">{{ c.l }}</el-radio-button>',
      '    </el-radio-group>',
      '    <el-progress style="width:220px;" :percentage="cov.pct" :format="function(){return cov.mapped+\'/\'+cov.total;}"></el-progress>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">未对照 {{ cov.unmapped }}</span>',
      '    <span v-if="cov.invalid" title="已对照的医保码在标准字典中已作废/过期/删除, 点击筛出后重新对照" style="color:var(--yb-danger);font-size:13px;cursor:pointer;text-decoration:underline;" @click="filterInvalid">对照失效 {{ cov.invalid }}</span>',
      '    <span v-else style="color:var(--yb-success);font-size:13px;">对照失效 0</span>',
      '    <el-button @click="loadSummary();loadItems()">刷新</el-button>',
      '  </div>',

      '  <div class="toolbar">',
      '    <el-select v-model="mappedFilter" style="width:120px" @change="search"><el-option v-for="f in mappedFilters" :key="f.v" :label="f.l" :value="f.v"></el-option></el-select>',
      '    <el-select v-if="catalog===\'charge\'" v-model="itemType" style="width:100px" @change="search"><el-option v-for="t in itemTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="名称/院内码/拼音简码/医保码" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="warning" @click="openAuto">批量自动对照</el-button>',
      '    <el-button type="danger" plain :disabled="!selection.length" @click="clearMap(selection)">清除选中对照</el-button>',
      '    <el-button @click="openLogDlg">变更记录</el-button>',
      '    <el-button type="primary" plain @click="openUpDlg">3301/3302 上报队列</el-button>',
      '    <el-button :loading="exporting" @click="exportRows">导出结果</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条, 已选 {{ selection.length }}</span>',
      '  </div>',

      '  <el-row :gutter="12">',
      '    <el-col :span="leftSpan">',
      '      <div v-if="panelMode!==\'right\'">',
      '      <div style="margin-bottom:6px;color:var(--yb-ink-2);font-size:13px;">院内工作队列<span style="float:right;"><el-button link type="primary" size="small" @click="togglePanel(\'left\')">{{ panelMode===\'left\'?\'还原布局\':\'扩展左栏\' }}</el-button></span></div>',
      '      <el-table :data="list" v-loading="loading" border stripe size="small" height="620" highlight-current-row :row-class-name="rowCls" @current-change="pickRow" @selection-change="onSelChange">',
      '        <el-table-column type="selection" width="42"></el-table-column>',
      '        <el-table-column type="index" label="序号" width="60" :index="seq"></el-table-column>',
      '        <el-table-column prop="code" label="院内码" width="100" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column v-if="catalog!==\'charge\'" prop="spec" label="规格" min-width="110" show-overflow-tooltip><template #default="s">{{ s.row.spec || "—" }}</template></el-table-column>',
      '        <el-table-column prop="price" label="单价" width="80"></el-table-column>',
      '        <el-table-column :label="catalog===\'charge\'?\'单位\':\'厂家\'" :min-width="catalog===\'charge\'?64:110" show-overflow-tooltip><template #default="s">{{ catalog===\'charge\'? s.row.unit : s.row.manufacturer }}</template></el-table-column>',
      '        <el-table-column prop="chrgitmLv" label="甲乙丙" width="80" show-overflow-tooltip><template #default="s">{{ lvText(s.row.chrgitmLv) }}</template></el-table-column>',
      '        <el-table-column prop="ybCode" label="医保码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybCode || "—" }}</template></el-table-column>',
      '        <el-table-column prop="ybName" label="医保名称" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.ybName || "—" }}</template></el-table-column>',
      '        <el-table-column prop="prevYbCode" label="变更前码" width="140" show-overflow-tooltip><template #default="s">{{ s.row.prevYbCode || "—" }}</template></el-table-column>',
      '        <el-table-column label="对照生效时间" width="175"><template #default="s"><el-date-picker v-model="s.row.mapEffTime" type="datetime" size="small" placeholder="生效时间" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm:ss" :disabled="!s.row.mapped" style="width:158px" @change="saveEff(s.row)"></el-date-picker></template></el-table-column>',
      '        <el-table-column label="状态" width="86"><template #default="s"><el-tag size="small" :type="stType(s.row)" :title="stTitle(s.row)">{{ stText(s.row) }}</el-tag></template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10,20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '      </div>',
      '      <div v-else style="padding:6px 0;"><el-button size="small" @click="togglePanel(\'\')">‹ 展开左栏</el-button></div>',
      '    </el-col>',

      '    <el-col :span="rightSpan">',
      '      <div v-if="panelMode!==\'left\'" style="border:1px solid var(--yb-border-light);border-radius:4px;padding:10px;">',
      '        <div style="margin-bottom:8px;color:var(--yb-ink-2);font-size:13px;">院内条目: <b>{{ cur ? cur.name : "(未选择)" }}</b>',
      '          <span style="float:right;"><el-button link type="primary" size="small" @click="togglePanel(\'right\')">{{ panelMode===\'right\'?\'还原布局\':\'扩展右栏\' }}</el-button>',
      '          <el-button type="primary" size="small" :disabled="!cur||!candSel" @click="confirmMap">确认对照</el-button>',
      '          <el-button size="small" :disabled="!cur||!cur.mapped" @click="clearMap([cur])">清除</el-button></span>',
      '        </div>',
      '        <div style="margin-bottom:8px;">',
      '          <el-input v-model="candKeyword" placeholder="检索医保目录(名称/编码/拼音简码)" clearable size="small" style="width:210px" @keyup.enter="searchCand" @clear="searchCand"></el-input>',
      '          <el-button size="small" @click="searchCand">检索</el-button>',
      '          <el-button size="small" link @click="resetCand">自动推荐</el-button>',
      '          <span v-if="candKeyword" style="color:var(--yb-ink-2);font-size:12px;">人工检索模式, 可任选一行对照</span>',
      '          <span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">共 {{ candList.length }} 条</span>',
      '        </div>',
      '        <el-table :data="candList" v-loading="candLoading" border stripe size="small" height="560" highlight-current-row @current-change="chooseCand">',
      '          <el-table-column type="index" label="序号" width="60" align="right"></el-table-column>',
      '          <el-table-column prop="code" label="医保编码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.code }}<el-tag v-if="s.row.valid===false" type="danger" size="small" style="margin-left:4px;">{{ s.row.invalidReason || "已作废" }}</el-tag></template></el-table-column>',
      '          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="商品名" min-width="120" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).trade_name || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="一级分类" min-width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).cat1 || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="二级分类" min-width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).cat2 || "—" }}</template></el-table-column>',
      '          <el-table-column prop="spec" :label="catalog===\'charge\'?\'单位\':(catalog===\'cons\'?\'三级分类\':\'规格\')" width="110" show-overflow-tooltip><template #default="s">{{ s.row.spec || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="剂型" width="90" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).act_dosform || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="材质" min-width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).material || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="特征" min-width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).feature || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog!==\'charge\'" prop="extra" label="厂家/企业" min-width="120" show-overflow-tooltip></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="甲乙类" width="70"><template #default="s">{{ (s.row.row||{}).chrgitm_lv || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="最小包装单位" width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).min_pack_unit || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="批准文号" width="130" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).approval_no || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="上市许可持有人" min-width="130" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).mkt_holder || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="医保通用名" min-width="130" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).hi_drug_name || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="注册剂型" width="90" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).reg_dosform || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="医保剂型" width="90" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).hi_dosform || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="注册规格" min-width="120" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).reg_spec || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="包装材质" min-width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).pack_material || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="最小制剂单位" width="100" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).min_prep_unit || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="本位码" width="120" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).drug_std_code || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'drug\'" label="上市状态" width="90" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).market_status || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="注册证号" min-width="140" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).reg_cert_no || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="耗材类型" width="90" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).cons_type || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'cons\'" label="政策标识" width="80"><template #default="s">{{ (s.row.row||{}).policy_flag || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="地方码" width="170" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).loc_item_code }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="项目内涵" min-width="180" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).item_connotation }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="除外内容" min-width="140" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).item_excluded }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="政策标识" width="80"><template #default="s">{{ (s.row.row||{}).policy_flag || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="国家项目名称" min-width="150" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).nat_item_name || "—" }}</template></el-table-column>',
      '          <el-table-column v-if="catalog===\'charge\'" label="项目说明" min-width="160" show-overflow-tooltip><template #default="s">{{ (s.row.row||{}).item_explain || "—" }}</template></el-table-column>',
      '          <el-table-column label="支付标准" width="80"><template #default="s">{{ (s.row.row||{}).pay_std || (s.row.row||{}).pay_std_prep || "—" }}</template></el-table-column>',
      '          <el-table-column prop="score" label="置信度" width="70"></el-table-column>',
      '          <el-table-column label="依据" min-width="120" show-overflow-tooltip><template #default="s">{{ (s.row.reasons||[]).join(" / ") }}</template></el-table-column>',
      '        </el-table>',
      '        <div v-if="cur && !candLoading && !candList.length" style="color:var(--yb-ink-2);font-size:13px;margin-top:8px;">无匹配候选, 可在上方输入关键字检索医保目录后任选一行对照。</div>',
      '      </div>',
      '      <div v-else style="padding:6px 0;"><el-button size="small" @click="togglePanel(\'\')">展开右栏 ›</el-button></div>',
      '    </el-col>',
      '  </el-row>',

      /* ==== 批量自动对照预览弹窗 ==== */
      '  <el-dialog v-model="autoDlg" :title="\'批量自动对照 - \'+curLabel" width="860px" top="6vh">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"',
      '      title="对未对照条目跑匹配器, 仅名称+规格全同(默认阈值0.95)进入预览; 勾选后提交写入, 未达标条目留人工复核。"></el-alert>',
      '    <div class="toolbar">',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">阈值</span>',
      '      <el-input-number v-model="autoThreshold" :min="0.5" :max="0.99" :step="0.05" :precision="2" style="width:120px"></el-input-number>',
      '      <el-button @click="runAuto(true)" :loading="autoLoading">重新预览</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">达标 {{ autoStats.matched||0 }} · 待复核 {{ autoStats.reviewed||0 }} · 跳过 {{ autoStats.skipped||0 }}</span>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">{{ selection.length? "(仅选中 "+selection.length+" 条)": "(全部未对照)" }}</span>',
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
      '      <el-date-picker v-model="logRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:240px" @change="onLogFilter"></el-date-picker>',
      '      <el-input v-model="logKw" placeholder="院内码/院内名/医保码/医保名称" clearable style="width:210px" @keyup.enter="onLogFilter" @clear="onLogFilter"></el-input>',
      '      <el-button type="primary" size="small" @click="onLogFilter">查询</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">每次新增/变更/清除对照均留痕; 变更时间之前用原医保码、之后用新医保码, 可回溯任意时点生效码。</span>',
      '    </div>',
      '    <el-table :data="logList" v-loading="logLoading" border stripe size="small" height="420">',
      '      <el-table-column type="index" label="序号" width="60" :index="logSeq"></el-table-column>',
      '      <el-table-column prop="changeTime" label="变更时间" width="150"></el-table-column>',
      '      <el-table-column label="类型" width="100"><template #default="s"><el-tag size="small" :type="s.row.changeType===\'CLEAR\'?\'danger\':(s.row.changeType===\'CHANGE\'?\'warning\':(s.row.changeType===\'EFF\'?\'info\':\'success\'))">{{ fmtType(s.row.changeType) }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="itemCode" label="院内码" width="100" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="原医保码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.oldCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="新医保码" width="150" show-overflow-tooltip><template #default="s">{{ s.row.newCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="方式" width="70"><template #default="s">{{ s.row.src === \'auto\' ? \'自动\' : \'人工\' }}</template></el-table-column>',
      '      <el-table-column label="操作人" width="90" show-overflow-tooltip><template #default="s">{{ s.row.operatorName || s.row.operator }}</template></el-table-column>',
      '      <el-table-column prop="memo" label="备注" min-width="140" show-overflow-tooltip><template #default="s">{{ s.row.memo || "—" }}</template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="logTotal" :page-size="logSize" :page-sizes="[10,20,50,100]" :current-page="logPage" @current-change="onLogPage" @size-change="onLogSize"></el-pagination>',
      '  </el-dialog>',

      /* ==== 3301/3302 上报队列弹窗(M4) ==== */
      '  <el-dialog v-model="upDlg" title="医保目录对照上报队列(3301/3302)" width="1050px" top="6vh">',
      '    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"',
      '      title="对照变更自动入队: 新增对照→3301 上传; 清除对照→3302 撤销; 变更对照→先 3302 撤销原码后 3301 上传新码。每批 ≤100 条; list_type 未配置(application.yml yb.list-type-*)时对应目录类型拒绝上报。"></el-alert>',
      '    <div class="toolbar">',
      '      <el-select v-model="upCatalog" placeholder="目录类型" clearable style="width:140px" @change="onUpFilter">',
      '        <el-option v-for="c in catalogs" :key="c.v" :label="c.l" :value="c.v"></el-option>',
      '      </el-select>',
      '      <el-select v-model="upStatus" placeholder="状态" clearable style="width:120px" @change="onUpFilter">',
      '        <el-option label="待传" :value="0"></el-option>',
      '        <el-option label="已传" :value="1"></el-option>',
      '        <el-option label="失败" :value="2"></el-option>',
      '      </el-select>',
      '      <el-button type="primary" :loading="upRunning" @click="runUp">立即上报(先撤后传)</el-button>',
      '      <el-button :loading="upRetrying" @click="retryUp">失败重传(复位待传)</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ upTotal }} 条</span>',
      '    </div>',
      '    <el-table :data="upList" v-loading="upLoading" border stripe size="small" height="420">',
      '      <el-table-column type="index" label="序号" width="60" :index="upSeq"></el-table-column>',
      '      <el-table-column label="目录" width="110"><template #default="s">{{ upCatalogText(s.row.catalogType) }}</template></el-table-column>',
      '      <el-table-column prop="itemCode" label="院内码" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="itemName" label="名称" min-width="130" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="动作" width="150"><template #default="s">{{ upActionText(s.row.action) }}</template></el-table-column>',
      '      <el-table-column label="原码→新码" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.oldCode || "—" }} → {{ s.row.newCode || "—" }}</template></el-table-column>',
      '      <el-table-column label="状态" width="80"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':(s.row.status===2?\'danger\':\'info\')">{{ s.row.status===1?\'已传\':(s.row.status===2?\'失败\':\'待传\') }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="batchNo" label="批次号" width="130" show-overflow-tooltip><template #default="s">{{ s.row.batchNo || "—" }}</template></el-table-column>',
      '      <el-table-column prop="uploadTime" label="上传时间" width="150"><template #default="s">{{ s.row.uploadTime || "—" }}</template></el-table-column>',
      '      <el-table-column prop="lastErr" label="失败原因" min-width="180" show-overflow-tooltip><template #default="s">{{ s.row.lastErr || "—" }}</template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="upTotal" :page-size="upSize" :page-sizes="[10,20,50,100]" :current-page="upPage" @current-change="onUpPage" @size-change="onUpSize"></el-pagination>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
