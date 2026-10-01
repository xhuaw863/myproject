/* 病案检索查询(病案统计科侧 P1-D): 快速检索 / 复合检索(患者·诊断·手术·就诊属性四条件块并行) / 诊断手术检索 / 出院人数对比。
 * 后端 /api/his/mr/search: quick(GET) / advanced(POST) / diag-oper(GET) / discharge-compare(GET)。
 * 均为读接口(机构隔离 scopeOrgId); 铁律: 只在编目侧检索, 不回写临床首页。
 * 雪花 ID 全链路字符串承载; 运行时模板仅访问组件作用域, 文件级助手须以 methods 暴露(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var DIAG_TYPES = [
    { value: 'outp', label: '门急诊' }, { value: 'adm', label: '入院' },
    { value: 'dmain', label: '出院主诊' }, { value: 'dother', label: '出院次诊' },
    { value: 'path', label: '病理' }, { value: 'injure', label: '损伤中毒外因' }, { value: 'infect', label: '院内感染' }
  ];

  HIS.views.MrSearch = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        tab: 'quick',
        depts: [],
        deptsLoading: false,
        diagTypes: DIAG_TYPES,
        /* 共享结果(quick/advanced/diagOper) */
        rows: [],
        page: 1,
        size: 20,
        total: 0,
        loading: false,
        lastMode: 'quick',
        /* 快速 */
        q: { keyword: '', deptId: null, status: null },
        statusOpts: [
          { value: 1, label: '待编目' }, { value: 2, label: '编目中' }, { value: 3, label: '已编目' }
        ],
        /* 复合 */
        adv: {
          logic: 'ALL',
          patient: { name: '', inpNo: '', gender: '', ageMin: null, ageMax: null },
          diag: { code: '', name: '', type: '', main: null },
          oper: { code: '', name: '', main: null },
          attr: { dischargeDeptId: null, catalogStatus: null, admitFrom: '', admitTo: '', dischargeFrom: '', dischargeTo: '', qualityMin: null, qualityMax: null }
        },
        /* 诊断手术 */
        dq: { code: '', name: '', scope: 'both' },
        /* 出院人数对比 */
        cmp: { from: '', to: '', compareFrom: '', compareTo: '', rows: [], summary: {}, currentRange: [], previousRange: [], loading: false }
      };
    },
    created: function () {
      this.loadDepts();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      fmtDay: function (v) { return (v || '').toString().slice(0, 10); },
      catStatusText: function (s) { return { 1: '待编目', 2: '编目中', 3: '已编目' }[s] || '-'; },
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
      /* 分页时按最近一次检索模式重载 */
      reload: function () {
        if (this.lastMode === 'quick') { this.runQuick(true); }
        else if (this.lastMode === 'advanced') { this.runAdvanced(true); }
        else if (this.lastMode === 'diagOper') { this.runDiagOper(true); }
      },
      onPage: function (p) { this.page = p; this.reload(); },
      onSize: function (s) { this.size = s; this.page = 1; this.reload(); },
      applyRows: function (d) {
        this.rows = (d && d.records) || [];
        this.total = (d && d.total) || 0;
      },
      /* ================= 快速检索 ================= */
      runQuick: function (keepPage) {
        var vm = this;
        if (!keepPage) { vm.page = 1; }
        vm.lastMode = 'quick';
        vm.loading = true;
        var q = '/api/his/mr/search/quick?page=' + vm.page + '&size=' + vm.size;
        if (vm.q.keyword) { q += '&keyword=' + encodeURIComponent(vm.q.keyword); }
        if (vm.q.deptId != null) { q += '&deptId=' + HIS.idParam(vm.q.deptId); }
        if (vm.q.status != null) { q += '&status=' + vm.q.status; }
        HIS.get(q).then(function (d) { vm.applyRows(d); }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* ================= 复合检索 ================= */
      runAdvanced: function (keepPage) {
        var vm = this;
        if (!keepPage) { vm.page = 1; }
        vm.lastMode = 'advanced';
        vm.loading = true;
        var body = {
          logic: vm.adv.logic, page: vm.page, size: vm.size,
          patient: vm.adv.patient, diag: vm.adv.diag, oper: vm.adv.oper, attr: vm.adv.attr
        };
        HIS.post('/api/his/mr/search/advanced', body).then(function (d) { vm.applyRows(d); })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      resetAdv: function () {
        this.adv = {
          logic: 'ALL',
          patient: { name: '', inpNo: '', gender: '', ageMin: null, ageMax: null },
          diag: { code: '', name: '', type: '', main: null },
          oper: { code: '', name: '', main: null },
          attr: { dischargeDeptId: null, catalogStatus: null, admitFrom: '', admitTo: '', dischargeFrom: '', dischargeTo: '', qualityMin: null, qualityMax: null }
        };
      },
      /* ================= 诊断手术检索 ================= */
      runDiagOper: function (keepPage) {
        var vm = this;
        if (!vm.dq.code && !vm.dq.name) { ElementPlus.ElMessage.warning('请输入诊断/手术编码或名称'); return; }
        if (!keepPage) { vm.page = 1; }
        vm.lastMode = 'diagOper';
        vm.loading = true;
        var q = '/api/his/mr/search/diag-oper?page=' + vm.page + '&size=' + vm.size + '&scope=' + encodeURIComponent(vm.dq.scope);
        if (vm.dq.code) { q += '&code=' + encodeURIComponent(vm.dq.code); }
        if (vm.dq.name) { q += '&name=' + encodeURIComponent(vm.dq.name); }
        HIS.get(q).then(function (d) { vm.applyRows(d); }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* ================= 出院人数对比 ================= */
      runCompare: function () {
        var vm = this;
        if (!vm.cmp.from || !vm.cmp.to) { ElementPlus.ElMessage.warning('请选择当期起止日期'); return; }
        vm.cmp.loading = true;
        var q = '/api/his/mr/search/discharge-compare?from=' + encodeURIComponent(vm.cmp.from) + '&to=' + encodeURIComponent(vm.cmp.to);
        if (vm.cmp.compareFrom) { q += '&compareFrom=' + encodeURIComponent(vm.cmp.compareFrom); }
        if (vm.cmp.compareTo) { q += '&compareTo=' + encodeURIComponent(vm.cmp.compareTo); }
        HIS.get(q).then(function (d) {
          vm.cmp.rows = (d && d.rows) || [];
          vm.cmp.summary = (d && d.summary) || {};
          vm.cmp.currentRange = (d && d.currentRange) || [];
          vm.cmp.previousRange = (d && d.previousRange) || [];
        }).catch(HIS.notifyError).finally(function () { vm.cmp.loading = false; });
      },
      signStyle: function (n) { return { color: n > 0 ? '#e6424a' : (n < 0 ? '#1aad19' : ''), fontWeight: '600' }; },
      signText: function (n) { return n > 0 ? ('+' + n) : ('' + n); }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">病案检索查询 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">快速 / 复合(四条件并行) / 诊断手术 / 出院人数对比</span></div>',
      '  <el-tabs v-model="tab" style="flex:1;min-height:0;display:flex;flex-direction:column;">',
      /* ---- 快速检索 ---- */
      '    <el-tab-pane label="快速检索" name="quick">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-input v-model="q.keyword" size="small" placeholder="患者 / 住院号 / 编目号 / 主要诊断" clearable style="width:260px;" @keyup.enter="runQuick()"></el-input>',
      '        <el-select v-model="q.deptId" size="small" filterable clearable placeholder="出院科室" style="width:180px" :loading="deptsLoading" :filter-method="kwFilter(\'qdept\')">',
      '          <el-option v-for="d in kwOptions(\'qdept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '        </el-select>',
      '        <el-select v-model="q.status" size="small" clearable placeholder="编目状态" style="width:130px">',
      '          <el-option v-for="s in statusOpts" :key="s.value" :label="s.label" :value="s.value"></el-option>',
      '        </el-select>',
      '        <el-button size="small" type="primary" @click="runQuick()">检索</el-button>',
      '      </div>',
      '    </el-tab-pane>',
      /* ---- 复合检索 ---- */
      '    <el-tab-pane label="复合检索" name="advanced">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <span style="font-size:13px;">条件组合</span>',
      '        <el-radio-group v-model="adv.logic" size="small">',
      '          <el-radio-button label="ALL">全部满足(AND)</el-radio-button>',
      '          <el-radio-button label="ANY">任一满足(OR)</el-radio-button>',
      '        </el-radio-group>',
      '        <el-button size="small" type="primary" @click="runAdvanced()">检索</el-button>',
      '        <el-button size="small" @click="resetAdv">重置</el-button>',
      '      </div>',
      '      <div style="display:flex;flex-wrap:wrap;gap:12px;">',
      '        <fieldset style="border:1px solid var(--yb-border);border-radius:4px;padding:8px 12px;min-width:240px;">',
      '          <legend style="font-size:13px;color:var(--yb-ink-2);">患者</legend>',
      '          <div style="display:flex;flex-wrap:wrap;gap:6px;">',
      '            <el-input v-model="adv.patient.name" size="small" placeholder="姓名" style="width:110px;"></el-input>',
      '            <el-input v-model="adv.patient.inpNo" size="small" placeholder="住院号" style="width:110px;"></el-input>',
      '            <el-select v-model="adv.patient.gender" size="small" clearable placeholder="性别" style="width:80px;"><el-option label="男" value="男"></el-option><el-option label="女" value="女"></el-option></el-select>',
      '            <el-input v-model.number="adv.patient.ageMin" size="small" placeholder="年龄≥" style="width:80px;"></el-input>',
      '            <el-input v-model.number="adv.patient.ageMax" size="small" placeholder="年龄≤" style="width:80px;"></el-input>',
      '          </div>',
      '        </fieldset>',
      '        <fieldset style="border:1px solid var(--yb-border);border-radius:4px;padding:8px 12px;min-width:240px;">',
      '          <legend style="font-size:13px;color:var(--yb-ink-2);">诊断(EXISTS)</legend>',
      '          <div style="display:flex;flex-wrap:wrap;gap:6px;">',
      '            <el-input v-model="adv.diag.code" size="small" placeholder="诊断编码" style="width:110px;"></el-input>',
      '            <el-input v-model="adv.diag.name" size="small" placeholder="诊断名称" style="width:120px;"></el-input>',
      '            <el-select v-model="adv.diag.type" size="small" clearable placeholder="类型" style="width:120px;"><el-option v-for="t in diagTypes" :key="t.value" :label="t.label" :value="t.value"></el-option></el-select>',
      '            <el-select v-model="adv.diag.main" size="small" clearable placeholder="主诊" style="width:90px;"><el-option label="是" :value="1"></el-option><el-option label="否" :value="0"></el-option></el-select>',
      '          </div>',
      '        </fieldset>',
      '        <fieldset style="border:1px solid var(--yb-border);border-radius:4px;padding:8px 12px;min-width:220px;">',
      '          <legend style="font-size:13px;color:var(--yb-ink-2);">手术(EXISTS)</legend>',
      '          <div style="display:flex;flex-wrap:wrap;gap:6px;">',
      '            <el-input v-model="adv.oper.code" size="small" placeholder="手术编码" style="width:110px;"></el-input>',
      '            <el-input v-model="adv.oper.name" size="small" placeholder="手术名称" style="width:120px;"></el-input>',
      '            <el-select v-model="adv.oper.main" size="small" clearable placeholder="主手术" style="width:100px;"><el-option label="是" :value="1"></el-option><el-option label="否" :value="0"></el-option></el-select>',
      '          </div>',
      '        </fieldset>',
      '        <fieldset style="border:1px solid var(--yb-border);border-radius:4px;padding:8px 12px;min-width:320px;">',
      '          <legend style="font-size:13px;color:var(--yb-ink-2);">就诊属性</legend>',
      '          <div style="display:flex;flex-wrap:wrap;gap:6px;align-items:center;">',
      '            <el-select v-model="adv.attr.dischargeDeptId" size="small" filterable clearable placeholder="出院科室" style="width:160px" :loading="deptsLoading" :filter-method="kwFilter(\'adept\')">',
      '              <el-option v-for="d in kwOptions(\'adept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '            </el-select>',
      '            <el-select v-model="adv.attr.catalogStatus" size="small" clearable placeholder="编目状态" style="width:120px;"><el-option v-for="s in statusOpts" :key="s.value" :label="s.label" :value="s.value"></el-option></el-select>',
      '            <span style="font-size:12px;">出院</span>',
      '            <el-date-picker v-model="adv.attr.dischargeFrom" type="date" size="small" placeholder="起" value-format="YYYY-MM-DD" style="width:130px;"></el-date-picker>',
      '            <el-date-picker v-model="adv.attr.dischargeTo" type="date" size="small" placeholder="止" value-format="YYYY-MM-DD" style="width:130px;"></el-date-picker>',
      '            <el-input v-model.number="adv.attr.qualityMin" size="small" placeholder="评分≥" style="width:80px;"></el-input>',
      '            <el-input v-model.number="adv.attr.qualityMax" size="small" placeholder="评分≤" style="width:80px;"></el-input>',
      '          </div>',
      '        </fieldset>',
      '      </div>',
      '    </el-tab-pane>',
      /* ---- 诊断手术检索 ---- */
      '    <el-tab-pane label="诊断手术检索" name="diagOper">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-input v-model="dq.code" size="small" placeholder="编码(临床/医保)" clearable style="width:160px;" @keyup.enter="runDiagOper()"></el-input>',
      '        <el-input v-model="dq.name" size="small" placeholder="名称(临床/医保)" clearable style="width:180px;" @keyup.enter="runDiagOper()"></el-input>',
      '        <el-select v-model="dq.scope" size="small" style="width:140px;"><el-option label="诊断+手术" value="both"></el-option><el-option label="仅诊断" value="diag"></el-option><el-option label="仅手术" value="oper"></el-option></el-select>',
      '        <el-button size="small" type="primary" @click="runDiagOper()">检索</el-button>',
      '      </div>',
      '    </el-tab-pane>',
      /* ---- 出院人数对比 ---- */
      '    <el-tab-pane label="出院人数对比" name="compare">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <span style="font-size:13px;">当期</span>',
      '        <el-date-picker v-model="cmp.from" type="date" size="small" placeholder="起" value-format="YYYY-MM-DD" style="width:130px;"></el-date-picker>',
      '        <el-date-picker v-model="cmp.to" type="date" size="small" placeholder="止" value-format="YYYY-MM-DD" style="width:130px;"></el-date-picker>',
      '        <span style="font-size:13px;margin-left:8px;">对比期(选填,缺省自动取前一等长区间)</span>',
      '        <el-date-picker v-model="cmp.compareFrom" type="date" size="small" placeholder="对比起" value-format="YYYY-MM-DD" style="width:130px;"></el-date-picker>',
      '        <el-date-picker v-model="cmp.compareTo" type="date" size="small" placeholder="对比止" value-format="YYYY-MM-DD" style="width:130px;"></el-date-picker>',
      '        <el-button size="small" type="primary" :loading="cmp.loading" @click="runCompare()">对比</el-button>',
      '      </div>',
      '      <div v-if="cmp.summary.current != null" style="margin-bottom:10px;padding:8px 10px;background:var(--yb-fill-2,#f5f7fa);border-radius:4px;font-size:13px;">',
      '        当期 {{ cmp.summary.current }} · 对比期 {{ cmp.summary.previous }} · 增减 <b :style="signStyle(cmp.summary.diff)">{{ signText(cmp.summary.diff) }}</b>',
      '        <span v-if="cmp.summary.rate != null"> · 变化率 {{ cmp.summary.rate }}%</span>',
      '      </div>',
      '      <el-table v-if="cmp.rows.length" :data="cmp.rows" border size="small" max-height="420px" empty-text="暂无对比数据">',
      '        <el-table-column prop="deptName" label="出院科室" min-width="160"></el-table-column>',
      '        <el-table-column prop="current" label="当期" width="90" align="center"></el-table-column>',
      '        <el-table-column prop="previous" label="对比期" width="90" align="center"></el-table-column>',
      '        <el-table-column label="增减" width="90" align="center"><template #default="s"><span :style="signStyle(s.row.diff)">{{ signText(s.row.diff) }}</span></template></el-table-column>',
      '      </el-table>',
      '      <el-empty v-else description="选择当期日期后点击对比"></el-empty>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      /* ---- 结果表(quick/advanced/diagOper 共用) ---- */
      '  <div v-if="tab !== \'compare\'" style="display:flex;flex-direction:column;flex:1;min-height:0;" v-loading="loading">',
      '    <el-table :data="rows" :key="lastMode" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无检索结果">',
      '      <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '      <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '      <el-table-column prop="genderName" label="性别" width="55" align="center"></el-table-column>',
      '      <el-table-column prop="age" label="年龄" width="55" align="center"></el-table-column>',
      '      <el-table-column prop="inpNo" label="住院号" width="110"></el-table-column>',
      '      <el-table-column prop="dischargeDeptName" label="出院科室" width="120"></el-table-column>',
      '      <el-table-column label="出院日期" width="105"><template #default="s">{{ fmtDay(s.row.dischargeDate) }}</template></el-table-column>',
      '      <el-table-column label="主要诊断" min-width="160"><template #default="s">{{ (s.row.mainDiagCode ? s.row.mainDiagCode + \' \' : \'\') + (s.row.mainDiagName || \'\') }}</template></el-table-column>',
      '      <el-table-column v-if="lastMode === \'diagOper\'" label="命中诊断" width="130"><template #default="s">{{ s.row.hitDiag || \'-\' }}</template></el-table-column>',
      '      <el-table-column v-if="lastMode === \'diagOper\'" label="命中手术" width="130"><template #default="s">{{ s.row.hitOper || \'-\' }}</template></el-table-column>',
      '      <el-table-column label="编目状态" width="90" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ catStatusText(s.row.catalogStatus) }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="catalogerName" label="编目员" width="90"></el-table-column>',
      '      <el-table-column prop="qualityScore" label="评分" width="60" align="center"></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '      :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]"',
      '      @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
