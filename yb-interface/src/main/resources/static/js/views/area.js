/* 行政区划管理: area_code_2021(国家统计局2021版 5级区划) 显示/级联下钻/检索/增删改 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var LEVELS = [
    { v: 1, l: '省级', t: 'danger' },
    { v: 2, l: '市级', t: 'warning' },
    { v: 3, l: '县级', t: 'success' },
    { v: 4, l: '乡镇/街道', t: 'primary' },
    { v: 5, l: '村/社区', t: 'info' }
  ];
  function levelLabel(v) {
    for (var i = 0; i < LEVELS.length; i++) { if (LEVELS[i].v === Number(v)) { return LEVELS[i].l; } }
    return v == null ? '-' : String(v);
  }
  function levelTag(v) {
    for (var i = 0; i < LEVELS.length; i++) { if (LEVELS[i].v === Number(v)) { return LEVELS[i].t; } }
    return 'info';
  }

  HIS.views.AreaManage = {
    data: function () {
      return {
        keyword: '', level: null, pcode: null, page: 1, size: 20,
        loading: false, list: [], total: 0,
        crumbs: [], stats: [], levelOpts: LEVELS,
        dlg: false, editing: false, saving: false, form: this.empty()
      };
    },
    created: function () { this.loadStats(); this.fetch(); },
    methods: {
      empty: function () { return { code: '', name: '', level: 5, pcode: '' }; },
      levelLabel: levelLabel,
      levelTag: levelTag,
      loadStats: function () {
        var vm = this;
        HIS.get('/api/his/area/stats').then(function (d) { vm.stats = d || []; }).catch(function () {});
      },
      search: function () { this.page = 1; this.crumbs = []; this.fetch(); },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/area/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.level) { q += '&level=' + vm.level; }
        if (vm.pcode) { q += '&pcode=' + vm.pcode; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
            onSize: function (s) { this.size = s; this.onPage(1); },
            seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 下钻到某节点的下级 */
      drill: function (row) {
        var vm = this;
        vm.pcode = row.code; vm.keyword = ''; vm.level = null; vm.page = 1;
        HIS.get('/api/his/area/path?code=' + row.code).then(function (d) {
          vm.crumbs = d || []; vm.fetch();
        }).catch(function () { vm.crumbs = []; vm.fetch(); });
      },
      /* 面包屑: 跳到某祖先的下级 */
      gotoCrumb: function (c) {
        var vm = this;
        vm.pcode = c.code; vm.keyword = ''; vm.level = null; vm.page = 1;
        HIS.get('/api/his/area/path?code=' + c.code).then(function (d) {
          vm.crumbs = d || []; vm.fetch();
        }).catch(function () { vm.crumbs = []; vm.fetch(); });
      },
      /* 回到根(全部): 同时清空检索词与级别过滤 */
      resetRoot: function () {
        this.pcode = null; this.crumbs = []; this.keyword = ''; this.level = null; this.page = 1; this.fetch();
      },
      openCreate: function () {
        this.editing = false;
        var f = this.empty();
        /* 下钻状态下, 新增默认挂到当前父级并推断级别 */
        if (this.pcode && this.crumbs.length) {
          f.pcode = String(this.pcode);
          var cur = this.crumbs[this.crumbs.length - 1];
          f.level = Math.min(5, (Number(cur.level) || 0) + 1);
        }
        this.form = f; this.dlg = true;
      },
      openEdit: function (row) {
        this.editing = true;
        this.form = {
          code: String(row.code), name: row.name, level: Number(row.level),
          pcode: (row.pcode == null ? '' : String(row.pcode))
        };
        this.dlg = true;
      },
      submit: function () {
        var vm = this;
        var f = vm.form;
        if (!f.code || !String(f.code).trim()) { ElementPlus.ElMessage.warning('请填写区划代码'); return; }
        if (!f.name || !f.name.trim()) { ElementPlus.ElMessage.warning('请填写名称'); return; }
        var body = {
          code: Number(String(f.code).trim()),
          name: f.name.trim(),
          level: Number(f.level),
          pcode: (f.pcode === '' || f.pcode == null) ? 0 : Number(String(f.pcode).trim())
        };
        if (!body.code || isNaN(body.code)) { ElementPlus.ElMessage.warning('区划代码必须为数字'); return; }
        vm.saving = true;
        var req = vm.editing ? HIS.put('/api/his/area', body) : HIS.post('/api/his/area', body);
        req.then(function () {
          HIS.notifySuccess(vm.editing ? '已保存' : '已新增');
          vm.dlg = false; vm.fetch(); vm.loadStats();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除区划「' + row.name + '(' + row.code + ')」?', '删除确认', { type: 'warning' })
          .then(function () { return HIS.del('/api/his/area/' + row.code); })
          .then(function () { HIS.notifySuccess('已删除'); vm.fetch(); vm.loadStats(); })
          .catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">行政区划管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(国家统计局2021版 area_code_2021 · 全局共享)</span></div>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    title="5级行政区划(省/市/县/镇/村)。可点“查看下级”逐级下钻, 或按名称/代码检索; 支持新增、修改、删除(有下级的节点需先删下级)。"></el-alert>',
      '  <div class="toolbar" style="flex-wrap:wrap;gap:8px;">',
      '    <el-tag v-for="s in stats" :key="s.level" size="small" type="info" effect="plain">{{ levelLabel(s.level) }}：{{ s.count }}</el-tag>',
      '  </div>',
      '  <div class="toolbar">',
      '    <el-select v-model="level" placeholder="级别" clearable style="width:130px" @change="search">',
      '      <el-option v-for="o in levelOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '    </el-select>',
      '    <el-input v-model="keyword" placeholder="名称/区划代码/拼音简码检索" clearable style="width:240px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button @click="resetRoot">回到全部</el-button>',
      '    <el-button type="success" @click="openCreate">新增区划</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '  </div>',
      '  <el-breadcrumb separator="/" style="margin:6px 0 10px;">',
      '    <el-breadcrumb-item><a href="javascript:;" @click="resetRoot">全部</a></el-breadcrumb-item>',
      '    <el-breadcrumb-item v-for="c in crumbs" :key="c.code"><a href="javascript:;" @click="gotoCrumb(c)">{{ c.name }}</a></el-breadcrumb-item>',
      '  </el-breadcrumb>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="code" label="区划代码" width="160"></el-table-column>',
      '    <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="级别" width="120"><template #default="s"><el-tag size="small" :type="levelTag(s.row.level)">{{ levelLabel(s.row.level) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="pcode" label="父级代码" width="160"></el-table-column>',
      '    <el-table-column label="操作" width="220" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="drill(s.row)">查看下级</el-button>',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <el-dialog v-model="dlg" :title="editing?\'修改行政区划\':\'新增行政区划\'" width="480px">',
      '    <el-form :model="form" label-width="96px">',
      '      <el-form-item label="区划代码"><el-input v-model="form.code" :disabled="editing" placeholder="如 110101001001(数字)"></el-input></el-form-item>',
      '      <el-form-item label="名称"><el-input v-model="form.name" placeholder="如 东城区"></el-input></el-form-item>',
      '      <el-form-item label="级别">',
      '        <el-select v-model="form.level" style="width:100%">',
      '          <el-option v-for="o in levelOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="父级代码"><el-input v-model="form.pcode" placeholder="顶级填 0 或留空"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
