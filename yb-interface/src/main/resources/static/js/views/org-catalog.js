/* 机构目录选用(L3): 本机构从医共体目录勾选能开展的项目, 只能启停, 不能改内容与价格 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var TABS = [
    { name: 'charge', label: '收费项目' },
    { name: 'drug', label: '药品' },
    { name: 'cons', label: '耗材' },
    { name: 'usage', label: '用法' },
    { name: 'freq', label: '用药频次' }
  ];

  HIS.views.OrgCatalog = {
    data: function () {
      return {
        activeTab: 'charge', tabs: TABS, lead: false,
        loading: false, list: [], total: 0, page: 1, size: 20, keyword: '',
        selection: [],
        detDlg: false, detTitle: '', detRows: [], detLoading: false
      };
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/org-catalog/selection?catalogType=' + vm.activeTab
          + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          vm.lead = !!(d && d.lead);
          vm.selection = [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () { this.page = 1; this.keyword = ''; this.load(); },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      onSelChange: function (rows) { this.selection = rows || []; },
      /* 查看目录项完整内容(L2 全字段): 拉取带中文标签的字段列表 */
      showDetail: function (row) {
        var vm = this;
        vm.detTitle = (row.code || '') + ' ' + (row.name || '');
        vm.detRows = []; vm.detLoading = true; vm.detDlg = true;
        HIS.get('/api/org-catalog/detail?catalogType=' + vm.activeTab + '&catalogId=' + row.id)
          .then(function (d) { vm.detRows = d || []; })
          .catch(HIS.notifyError).finally(function () { vm.detLoading = false; });
      },
      /* 单条启停 */
      toggle: function (row, enabled) {
        var vm = this;
        HIS.post('/api/org-catalog/save', { catalogType: vm.activeTab, catalogId: row.id, enabled: enabled })
          .then(function () { HIS.notifySuccess(enabled === 1 ? '已开展' : '已停用'); vm.load(); })
          .catch(HIS.notifyError);
      },
      /* 批量启停 */
      batch: function (enabled) {
        var vm = this;
        if (!vm.selection.length) { ElementPlus.ElMessage.warning('请先勾选项目'); return; }
        var ids = vm.selection.map(function (r) { return r.id; });
        HIS.post('/api/org-catalog/save', { catalogType: vm.activeTab, ids: ids, enabled: enabled })
          .then(function () { HIS.notifySuccess('批量操作成功'); vm.load(); })
          .catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">机构目录选用 <span style="font-size:12px;color:#909399;font-weight:normal;">(勾选本院能开展的项目 · 只能启停, 价格由牵头机构统一定义)</span></div>',
      '  <el-alert v-if="lead" type="success" :closable="false" show-icon style="margin-bottom:10px;" title="本机构为牵头机构, 默认全量开展医共体目录, 无需勾选。"></el-alert>',
      '  <el-tabs v-model="activeTab" @tab-change="onTab">',
      '    <el-tab-pane v-for="t in tabs" :key="t.name" :label="t.label" :name="t.name"></el-tab-pane>',
      '  </el-tabs>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="名称/编码检索" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button @click="search">查询</el-button>',
      '    <el-button type="success" :disabled="lead" @click="batch(1)">批量开展</el-button>',
      '    <el-button type="info" :disabled="lead" @click="batch(0)">批量停用</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 项</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" @selection-change="onSelChange">',
      '    <el-table-column type="selection" width="45" :selectable="function(){ return !lead; }"></el-table-column>',
      '    <el-table-column type="index" label="序号" width="55" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="code" label="编码" width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="spec" :label="activeTab===\'freq\'?\'每日次数\':\'规格\'" min-width="130" show-overflow-tooltip></el-table-column>',
      '    <el-table-column v-if="activeTab!==\'usage\' && activeTab!==\'freq\'" prop="unit" label="单位" width="70"></el-table-column>',
      '    <el-table-column v-if="activeTab!==\'usage\' && activeTab!==\'freq\'" prop="priceText" label="价格(只读)" width="100"></el-table-column>',
      '    <el-table-column label="开展状态" width="90"><template #default="s"><el-tag size="small" :type="s.row.enabled===1?\'success\':\'info\'">{{ s.row.enabled===1?"已开展":"未开展" }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="160" fixed="right"><template #default="s">',
      '      <el-button link type="info" @click="showDetail(s.row)">详情</el-button>',
      '      <el-button v-if="!lead && s.row.enabled!==1" link type="success" @click="toggle(s.row,1)">开展</el-button>',
      '      <el-button v-if="!lead && s.row.enabled===1" link type="warning" @click="toggle(s.row,0)">停用</el-button>',
      '      <span v-if="lead" style="color:#909399;font-size:12px;">全量</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',

      /* ==== 目录项详情弹窗 ==== */
      '  <el-dialog v-model="detDlg" :title="\'目录详情 - \'+detTitle" width="720px" top="6vh">',
      '    <el-table :data="detRows" v-loading="detLoading" border stripe size="small" max-height="60vh">',
      '      <el-table-column prop="label" label="字段" width="150"></el-table-column>',
      '      <el-table-column prop="value" label="内容" min-width="320"></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="detDlg=false">关闭</el-button></template>',
      '  </el-dialog>',

      '</div>'
    ].join('\n')
  };
})();
