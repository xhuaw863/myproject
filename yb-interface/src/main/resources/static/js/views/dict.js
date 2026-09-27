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
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="time" label="时间" width="110"></el-table-column>',
      '    <el-table-column prop="label" label="字典" min-width="200"></el-table-column>',
      '    <el-table-column label="结果" min-width="200"><template #default="s">',
      '      <el-tag size="small" :type="s.row.ok?\'success\':\'danger\'">{{ s.row.ok?"成功":"失败" }}</el-tag>',
      '      <span style="margin-left:8px;color:var(--yb-ink-2);">{{ s.row.msg }}</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <div v-if="!results.length" style="color:var(--yb-ink-2);font-size:13px;margin-top:8px;">尚未执行下载。</div>',
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
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 类字典</span></div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="dictType" label="字典类型" width="180"></el-table-column>',
      '    <el-table-column prop="dictName" label="字典名称" min-width="160"></el-table-column>',
      '    <el-table-column prop="infno" label="交易号" width="90"></el-table-column>',
      '    <el-table-column prop="maxVer" label="本地版本" width="140"></el-table-column>',
      '    <el-table-column prop="lastDldTime" label="最近下载" min-width="170"></el-table-column>',
      '  </el-table>',
      '  <div v-if="!loading && !list.length" style="color:var(--yb-ink-2);font-size:13px;margin-top:8px;">本院尚未下载任何字典, 请先到“字典下载”页执行。</div>',
      '</div>'
    ].join('\n')
  };

})();
