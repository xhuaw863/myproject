/* 医保字典: 下载 / 版本状态 / 目录对照 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var DL_TYPES = [
    { path: 'drug', label: '1301 西药中成药目录' },
    { path: 'tcm', label: '1302 中药饮片目录' },
    { path: 'preparation', label: '1303 医疗机构制剂目录' },
    { path: 'med-service', label: '1305 医疗服务项目目录' },
    { path: 'consumable', label: '1306 医用耗材目录' },
    { path: 'disease', label: '1307 疾病与诊断目录' }
  ];

  /* ================= 字典下载 ================= */
  HIS.views.DictDownload = {
    data: function () {
      return { busy: false, busyLabel: '', types: DL_TYPES, results: [] };
    },
    methods: {
      dl: function (t) {
        var vm = this; vm.busy = true; vm.busyLabel = t.label;
        HIS.raw('GET', '/api/dict/' + t.path).then(function (r) {
          vm.push(t.label, r);
        }).catch(HIS.notifyError).finally(function () { vm.busy = false; vm.busyLabel = ''; });
      },
      dlAll: function () {
        var vm = this; vm.busy = true; vm.busyLabel = '全部字典';
        HIS.raw('GET', '/api/dict/all').then(function (map) {
          Object.keys(map || {}).forEach(function (k) { vm.push(k, map[k]); });
          HIS.notifySuccess('全部字典下载任务已执行');
        }).catch(HIS.notifyError).finally(function () { vm.busy = false; vm.busyLabel = ''; });
      },
      push: function (label, r) {
        var ok = r && r.infcode === '0';
        this.results.unshift({
          time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          label: label, ok: ok,
          msg: ok ? '下载成功' : ('失败: ' + ((r && (r.errMsg || r.infcode)) || '未知错误'))
        });
        if (this.results.length > 30) { this.results.pop(); }
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医保基础字典下载</div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px;"',
      '    title="按当前登录医院(租户)下载并入库, 各医院字典相互隔离。首次下载为全量, 之后按本地版本号增量。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-button v-for="t in types" :key="t.path" :loading="busy && busyLabel===t.label" @click="dl(t)">{{ t.label }}</el-button>',
      '    <el-button type="warning" :loading="busy && busyLabel===\'全部字典\'" @click="dlAll">下载全部</el-button>',
      '  </div>',
      '  <el-table :data="results" size="small" border stripe style="margin-top:8px;">',
      '    <el-table-column prop="time" label="时间" width="110"></el-table-column>',
      '    <el-table-column prop="label" label="字典" min-width="200"></el-table-column>',
      '    <el-table-column label="结果" min-width="200"><template #default="s">',
      '      <el-tag size="small" :type="s.row.ok?\'success\':\'danger\'">{{ s.row.ok?"成功":"失败" }}</el-tag>',
      '      <span style="margin-left:8px;color:#606266;">{{ s.row.msg }}</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <div v-if="!results.length" style="color:#909399;font-size:13px;margin-top:8px;">尚未执行下载。</div>',
      '</div>'
    ].join('\n')
  };

  /* ================= 字典版本状态 ================= */
  HIS.views.DictVersion = {
    data: function () { return { loading: false, list: [] }; },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/dict/query/versions').then(function (d) { vm.list = d || []; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">字典版本状态(本院)</div>',
      '  <div class="toolbar"><el-button @click="load">刷新</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ list.length }} 类字典</span></div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="dictType" label="字典类型" width="180"></el-table-column>',
      '    <el-table-column prop="dictName" label="字典名称" min-width="160"></el-table-column>',
      '    <el-table-column prop="infno" label="交易号" width="90"></el-table-column>',
      '    <el-table-column prop="maxVer" label="本地版本" width="140"></el-table-column>',
      '    <el-table-column prop="lastDldTime" label="最近下载" min-width="170"></el-table-column>',
      '  </el-table>',
      '  <div v-if="!loading && !list.length" style="color:#909399;font-size:13px;margin-top:8px;">本院尚未下载任何字典, 请先到“字典下载”页执行。</div>',
      '</div>'
    ].join('\n')
  };

  /* ================= 目录对照 =================
   * 左: 浏览医保目录 -> 右: 选择本院收费项目建立对照(写入 med_list_codg)
   */
  var MAP_TYPES = [
    { v: 'drug', l: '西药中成药(药品)', chrg: '01' },
    { v: 'med_service', l: '医疗服务项目(诊疗)', chrg: '02' },
    { v: 'consumable', l: '医用耗材', chrg: '03' },
    { v: 'disease', l: '疾病与诊断', chrg: '' }
  ];

  HIS.views.DictMap = {
    data: function () {
      return {
        mapTypes: MAP_TYPES, dictType: 'drug', keyword: '', page: 1, size: 10,
        loading: false, dictList: [], total: 0,
        mapDlg: false, curDict: null,
        itemKeyword: '', itemList: [], itemLoading: false, selectedItem: null
      };
    },
    created: function () { this.search(); },
    methods: {
      curChrg: function () {
        for (var i = 0; i < MAP_TYPES.length; i++) { if (MAP_TYPES[i].v === this.dictType) { return MAP_TYPES[i].chrg; } }
        return '';
      },
      search: function () {
        var vm = this; vm.loading = true; vm.page = 1;
        vm.fetch();
      },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/dict/query/catalog?type=' + vm.dictType + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.dictList = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
      onTypeChange: function () { this.keyword = ''; this.search(); },
      openMap: function (row) {
        this.curDict = row; this.selectedItem = null; this.itemKeyword = ''; this.itemList = [];
        this.mapDlg = true;
      },
      searchItems: function () {
        var vm = this; vm.itemLoading = true;
        var q = '/api/his/charge-item/page?page=1&size=20';
        if (vm.itemKeyword) { q += '&keyword=' + encodeURIComponent(vm.itemKeyword); }
        HIS.get(q).then(function (d) { vm.itemList = (d && d.records) || []; }).catch(HIS.notifyError).finally(function () { vm.itemLoading = false; });
      },
      chooseItem: function (row) { this.selectedItem = row; },
      confirmMap: function () {
        var vm = this;
        if (!vm.selectedItem) { ElementPlus.ElMessage.warning('请选择一个本院收费项目'); return; }
        var body = { id: vm.selectedItem.id, medListCodg: vm.curDict.code };
        var chrg = vm.curChrg();
        if (chrg) { body.medChrgitmType = chrg; }
        HIS.put('/api/his/charge-item', body).then(function () {
          HIS.notifySuccess('已对照: ' + vm.selectedItem.itemName + ' ← ' + vm.curDict.code);
          vm.mapDlg = false;
        }).catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医保目录 → 本院收费项目 对照</div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px;"',
      '    title="浏览已下载的医保目录, 选择条目后对照到本院收费项目(写入医保目录编码 med_list_codg), 供后续费用上传使用。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-select v-model="dictType" style="width:200px" @change="onTypeChange"><el-option v-for="t in mapTypes" :key="t.v" :label="t.l" :value="t.v"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="名称/编码检索" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索目录</el-button>',
      '    <span style="color:#909399;font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-table :data="dictList" v-loading="loading" border stripe size="small">',
      '    <el-table-column prop="code" label="医保目录编码" width="200"></el-table-column>',
      '    <el-table-column prop="name" label="名称" min-width="200"></el-table-column>',
      '    <el-table-column prop="spec" label="规格/单位" width="150"></el-table-column>',
      '    <el-table-column prop="extra" label="附加" min-width="140"></el-table-column>',
      '    <el-table-column label="操作" width="100" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openMap(s.row)">对照</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="prev, pager, next, total" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '  <el-dialog v-model="mapDlg" title="对照到本院收费项目" width="640px">',
      '    <div style="margin-bottom:10px;color:#606266;">医保条目: <b>{{ curDict && curDict.code }}</b> {{ curDict && curDict.name }}</div>',
      '    <div class="toolbar">',
      '      <el-input v-model="itemKeyword" placeholder="搜索本院收费项目" clearable style="width:240px" @keyup.enter="searchItems"></el-input>',
      '      <el-button type="primary" @click="searchItems">搜索</el-button>',
      '    </div>',
      '    <el-table :data="itemList" v-loading="itemLoading" border size="small" height="280" highlight-current-row @current-change="chooseItem">',
      '      <el-table-column prop="itemCode" label="编码" width="100"></el-table-column>',
      '      <el-table-column prop="itemName" label="名称" min-width="150"></el-table-column>',
      '      <el-table-column prop="itemType" label="大类" width="70"></el-table-column>',
      '      <el-table-column prop="price" label="单价" width="80"></el-table-column>',
      '      <el-table-column label="现对照" width="150"><template #default="s">{{ s.row.medListCodg || "—" }}</template></el-table-column>',
      '    </el-table>',
      '    <template #footer><el-button @click="mapDlg=false">取消</el-button><el-button type="primary" @click="confirmMap">确认对照</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
