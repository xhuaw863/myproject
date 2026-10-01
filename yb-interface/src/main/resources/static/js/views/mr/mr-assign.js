/* 病案分配(病案统计科侧): 待分配池(已出院未编目)查询 + 工作量看板 + 分配(偏好/随机均分) + 释放/重分配。
 * 后端 /api/his/mr/assign: pending(分页) / assign(POST) / release(POST) / workload(GET) / catalogers(GET)。
 * 写权限: 本机构管理员(后端 requireSelfOrgWrite 兜底 403); 前端按角色隐藏写按钮。
 * 雪花 ID 全链路以字符串承载(HIS.id / HIS.sameId / HIS.idKey)。运行时编译模板仅可访问组件作用域,
 * 故文件级 HIS 助手须以 methods 暴露给模板(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrAssign = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 20,
        total: 0,
        keyword: '',
        deptId: null,
        depts: [],
        deptsLoading: false,
        selected: [],
        /* 编目员剩余工作量 */
        workload: [],
        workloadLoading: false,
        /* 编目员候选池 */
        catalogerPool: [],
        catalogerLoading: false,
        /* 分配弹窗 */
        assign: { visible: false, saving: false, mode: 2, picked: [], prips: {} },
        /* 释放弹窗 */
        rel: { visible: false, saving: false, row: null, reason: '' }
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      }
    },
    created: function () {
      this.loadDepts();
      this.loadPending();
      this.loadWorkload();
    },
    methods: {
      /* 文件级助手暴露给模板 */
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* ================= 取数 ================= */
      loadDepts: function () {
        var vm = this;
        vm.deptsLoading = true;
        HIS.get('/api/his/dept/tree').then(function (d) {
          var out = [];
          (function walk(list, depth) {
            (list || []).forEach(function (n) {
              var pad = '';
              for (var i = 0; i < depth; i++) { pad += '　'; }
              out.push({ id: n.id, label: pad + (n.deptName || ('科室' + n.id)), deptName: n.deptName, deptCode: n.deptCode });
              if (n.children && n.children.length) { walk(n.children, depth + 1); }
            });
          })(d || [], 0);
          vm.depts = out;
        }).catch(function () { vm.depts = []; }).finally(function () { vm.deptsLoading = false; });
      },
      loadPending: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/mr/assign/pending?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.deptId != null) { q += '&deptId=' + HIS.idParam(vm.deptId); }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadWorkload: function () {
        var vm = this;
        vm.workloadLoading = true;
        HIS.get('/api/his/mr/assign/workload').then(function (d) {
          vm.workload = d || [];
        }).catch(function () { vm.workload = []; }).finally(function () { vm.workloadLoading = false; });
      },
      loadCatalogers: function (kw) {
        var vm = this;
        vm.catalogerLoading = true;
        HIS.get('/api/his/mr/assign/catalogers?keyword=' + encodeURIComponent(kw || '')).then(function (d) {
          vm.catalogerPool = d || [];
        }).catch(function () { vm.catalogerPool = []; }).finally(function () { vm.catalogerLoading = false; });
      },
      search: function () { this.page = 1; this.loadPending(); },
      reset: function () { this.keyword = ''; this.deptId = null; this.page = 1; this.loadPending(); },
      onPage: function (p) { this.page = p; this.loadPending(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadPending(); },
      onSelChange: function (rows) { this.selected = rows || []; },

      /* ================= 分配 ================= */
      openAssign: function () {
        var vm = this;
        if (!vm.selected.length) { ElementPlus.ElMessage.warning('请先勾选待分配的病案'); return; }
        vm.assign = { visible: true, saving: false, mode: 2, picked: [], prips: {} };
        vm.loadCatalogers('');
      },
      onPickCatalogers: function (ids) {
        var vm = this;
        /* 保留已录入的优先级, 新增项默认序号+1 */
        var next = {};
        (ids || []).forEach(function (id, i) {
          next[HIS.idKey(id)] = vm.assign.prips[HIS.idKey(id)] != null ? vm.assign.prips[HIS.idKey(id)] : (i + 1);
        });
        vm.assign.prips = next;
        vm.assign.picked = ids || [];
      },
      catalogerName: function (id) {
        for (var i = 0; i < this.catalogerPool.length; i++) {
          if (HIS.sameId(this.catalogerPool[i].catalogerId, id)) { return this.catalogerPool[i].catalogerName; }
        }
        return '';
      },
      doAssign: function () {
        var vm = this;
        if (!vm.assign.picked || !vm.assign.picked.length) { ElementPlus.ElMessage.warning('请选择至少一名编目员'); return; }
        var catalogers = vm.assign.picked.map(function (id) {
          return {
            catalogerId: HIS.id(id),
            catalogerName: vm.catalogerName(id),
            priority: vm.assign.prips[HIS.idKey(id)] || 0
          };
        });
        var body = { visitIds: vm.selected.map(function (r) { return HIS.id(r.visitId); }), mode: vm.assign.mode, catalogers: catalogers };
        vm.assign.saving = true;
        HIS.post('/api/his/mr/assign/assign', body).then(function (res) {
          var n = res && res.assigned != null ? res.assigned : vm.selected.length;
          HIS.notifySuccess('已分配 ' + n + ' 份病案(快照已生成)');
          vm.assign.visible = false;
          vm.selected = [];
          vm.loadPending();
          vm.loadWorkload();
        }).catch(HIS.notifyError).finally(function () { vm.assign.saving = false; });
      },

      /* ================= 释放 ================= */
      openRelease: function (row) {
        this.rel = { visible: true, saving: false, row: row, reason: '' };
      },
      doRelease: function () {
        var vm = this;
        vm.rel.saving = true;
        HIS.post('/api/his/mr/assign/release', { visitId: HIS.id(vm.rel.row.visitId), reason: vm.rel.reason }).then(function () {
          HIS.notifySuccess('已释放该病案分配');
          vm.rel.visible = false;
          vm.loadPending();
          vm.loadWorkload();
        }).catch(HIS.notifyError).finally(function () { vm.rel.saving = false; });
      },

      catStatusText: function (s) { return { 1: '未编目', 2: '编目中', 3: '已编目' }[s] || '-'; }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">病案分配 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">将已出院待编目患者按偏好或随机均分派给编目员 · 分配即生成编目快照</span></div>',
      '  <div class="ma-split" style="display:flex;gap:12px;align-items:stretch;flex:1;min-height:0;">',
      /* ---- 左: 待分配池 ---- */
      '    <div style="flex:1;min-width:0;display:flex;flex-direction:column;">',
      '      <div class="toolbar" style="margin-bottom:10px;flex:none;">',
      '        <el-input v-model="keyword" size="small" placeholder="患者姓名 / 住院号" clearable style="width:200px;" @keyup.enter="search"></el-input>',
      '        <el-select v-model="deptId" size="small" filterable clearable placeholder="出院科室" style="width:200px" :loading="deptsLoading"',
      '          :filter-method="kwFilter(\'dept\')">',
      '          <el-option v-for="d in kwOptions(\'dept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '        </el-select>',
      '        <el-button size="small" type="primary" @click="search">查询</el-button>',
      '        <el-button size="small" @click="reset">重置</el-button>',
      '        <span style="flex:1"></span>',
      '        <el-button v-if="canWrite" size="small" type="success" :disabled="!selected.length" @click="openAssign">分配 ({{ selected.length }})</el-button>',
      '      </div>',
      '      <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" @selection-change="onSelChange" empty-text="暂无待分配病案">',
      '        <el-table-column type="selection" width="42"></el-table-column>',
      '        <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '        <el-table-column prop="genderName" label="性别" width="55" align="center"></el-table-column>',
      '        <el-table-column prop="age" label="年龄" width="55" align="center"></el-table-column>',
      '        <el-table-column prop="inpNo" label="住院号" width="120"></el-table-column>',
      '        <el-table-column prop="deptName" label="出院科室" min-width="120"></el-table-column>',
      '        <el-table-column label="出院日期" width="110"><template #default="s">{{ (s.row.dischargeDate||\'\').slice(0,10) }}</template></el-table-column>',
      '        <el-table-column label="编目状态" width="90" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ catStatusText(s.row.catalogStatus) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="80" align="center" fixed="right">',
      '          <template #default="s"><el-button v-if="canWrite && s.row.catalogId" type="warning" link size="small" @click="openRelease(s.row)">释放</el-button></template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '        :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]"',
      '        @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </div>',
      /* ---- 右: 工作量看板 ---- */
      '    <div style="width:280px;flex:none;border:1px solid var(--yb-border);border-radius:4px;padding:10px;display:flex;flex-direction:column;" v-loading="workloadLoading">',
      '      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;flex:none;">',
      '        <span style="font-weight:600;">编目员剩余工作量</span>',
      '        <el-button size="small" link @click="loadWorkload">刷新</el-button>',
      '      </div>',
      '      <el-table :data="workload" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无分配记录">',
      '        <el-table-column prop="catalogerName" label="编目员" min-width="80"></el-table-column>',
      '        <el-table-column prop="pending" label="待办" width="60" align="center"></el-table-column>',
      '        <el-table-column prop="finished" label="已定稿" width="70" align="center"></el-table-column>',
      '      </el-table>',
      '    </div>',
      '  </div>',
      '</div>',
      /* ---- 分配弹窗 ---- */
      '<el-dialog v-model="assign.visible" title="病案分配" width="560px" destroy-on-close>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;"',
      '    :title="\'已选 \' + selected.length + \' 份待编目病案, 分配后将自动从临床首页生成编目快照。\'"></el-alert>',
      '  <el-form label-width="96px" size="default">',
      '    <el-form-item label="分配方式">',
      '      <el-radio-group v-model="assign.mode">',
      '        <el-radio-button :label="2">随机均分</el-radio-button>',
      '        <el-radio-button :label="1">偏好(指定单人)</el-radio-button>',
      '      </el-radio-group>',
      '    </el-form-item>',
      '    <el-form-item label="编目员" required>',
      '      <el-select v-model="assign.picked" multiple filterable :loading="catalogerLoading" style="width:100%;"',
      '        placeholder="检索并选择编目员(职工)" :filter-method="kwFilter(\'cat\')" @change="onPickCatalogers">',
      '        <el-option v-for="c in kwOptions(\'cat\', catalogerPool, [\'catalogerName\',\'deptName\'])" :key="idKey(c.catalogerId)"',
      '          :label="c.catalogerName + (c.deptName ? \' · \' + c.deptName : \'\')" :value="c.catalogerId"></el-option>',
      '      </el-select>',
      '    </el-form-item>',
      '    <el-form-item v-if="assign.picked.length" label="优先级">',
      '      <div style="display:flex;flex-wrap:wrap;gap:8px;">',
      '        <div v-for="id in assign.picked" :key="idKey(id)" style="display:flex;align-items:center;gap:4px;">',
      '          <el-tag size="small">{{ catalogerName(id) }}</el-tag>',
      '          <el-input-number v-model="assign.prips[idKey(id)]" :min="0" :max="99" size="small" controls-position="right" style="width:88px;"></el-input-number>',
      '        </div>',
      '      </div>',
      '      <div style="font-size:12px;color:var(--yb-ink-2);margin-top:4px;">随机均分时优先级用于轮询顺序; 偏好方式仅取第一名编目员。</div>',
      '    </el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="assign.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="assign.saving" @click="doAssign">确认分配</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 释放弹窗 ---- */
      '<el-dialog v-model="rel.visible" title="释放分配" width="420px" destroy-on-close>',
      '  <el-form label-width="80px" size="default">',
      '    <el-form-item label="患者"><span>{{ rel.row ? rel.row.patientName : \'\' }}</span></el-form-item>',
      '    <el-form-item label="释放原因"><el-input v-model="rel.reason" type="textarea" :rows="2" placeholder="选填"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="rel.visible=false">取消</el-button>',
      '    <el-button type="warning" :loading="rel.saving" @click="doRelease">确认释放</el-button>',
      '  </template>',
      '</el-dialog>'
    ].join('\n')
  };
})();
