/* 病案收回管理(病案统计科侧 P1-A): 出院病案从临床科室回收至病案室并上架的流转追踪。
 * 后端 /api/his/mr/recall: list(GET) / enroll(POST批量建单) / scan(POST扫码回收) / {id}/shelf(PUT上架)
 *   / refresh-overdue(POST逾期刷新) / rate(GET回收率报表)。建单来源取自待分配池 /api/his/mr/assign/pending。
 * 铁律: 不回写临床首页; 写权限本机构管理员(后端 requireSelfOrgWrite 兜底 403), 前端按角色隐藏写按钮。
 * 雪花 ID 全链路字符串承载; 运行时模板仅访问组件作用域, 文件级助手须以 methods 暴露(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrRecall = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 20,
        total: 0,
        status: null,
        deptId: null,
        keyword: '',
        depts: [],
        deptsLoading: false,
        statusOpts: [
          { value: 1, label: '待收回' },
          { value: 2, label: '已收回' },
          { value: 3, label: '逾期' }
        ],
        /* 批量建单(来源=待分配池) */
        enroll: { visible: false, loading: false, saving: false, rows: [], picked: [] },
        /* 扫码回收 */
        scan: { visible: false, saving: false, barcode: '', shelfLocation: '', remark: '' },
        /* 上架 */
        shelf: { visible: false, saving: false, row: null, location: '' },
        /* 回收率报表 */
        rate: { visible: false, loading: false, from: '', to: '', rows: [], summary: {} }
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      }
    },
    created: function () {
      this.loadDepts();
      this.loadList();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      fmt: function (v) { return (v || '').toString().replace('T', ' ').slice(0, 16); },
      fmtDay: function (v) { return (v || '').toString().slice(0, 10); },
      statusText: function (s) { return { 1: '待收回', 2: '已收回', 3: '逾期' }[s] || '-'; },
      statusTag: function (s) { return s === 2 ? 'success' : (s === 3 ? 'danger' : 'warning'); },
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
      loadList: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/mr/recall/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.status != null) { q += '&status=' + vm.status; }
        if (vm.deptId != null) { q += '&deptId=' + HIS.idParam(vm.deptId); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadList(); },
      reset: function () { this.keyword = ''; this.deptId = null; this.status = null; this.page = 1; this.loadList(); },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },

      /* ================= 批量建单 ================= */
      openEnroll: function () {
        var vm = this;
        vm.enroll = { visible: true, loading: true, saving: false, rows: [], picked: [] };
        HIS.get('/api/his/mr/assign/pending?page=1&size=200').then(function (d) {
          vm.enroll.rows = (d && d.records) || [];
        }).catch(function () { vm.enroll.rows = []; }).finally(function () { vm.enroll.loading = false; });
      },
      onEnrollSel: function (rows) { this.enroll.picked = rows || []; },
      doEnroll: function () {
        var vm = this;
        if (!vm.enroll.picked.length) { ElementPlus.ElMessage.warning('请勾选需登记的病案'); return; }
        var ids = vm.enroll.picked.map(function (r) { return HIS.id(r.visitId); });
        vm.enroll.saving = true;
        HIS.post('/api/his/mr/recall/enroll', { visitIds: ids }).then(function (res) {
          HIS.notifySuccess('建单完成: 新建 ' + (res && res.created != null ? res.created : ids.length) + ' 份, 跳过 ' + (res && res.skipped || 0) + ' 份');
          vm.enroll.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.enroll.saving = false; });
      },

      /* ================= 扫码回收 ================= */
      openScan: function () {
        this.scan = { visible: true, saving: false, barcode: '', shelfLocation: '', remark: '' };
      },
      doScan: function () {
        var vm = this;
        if (!vm.scan.barcode) { ElementPlus.ElMessage.warning('请扫描或录入病案条码'); return; }
        var body = { barcode: vm.scan.barcode };
        if (vm.scan.shelfLocation) { body.shelfLocation = vm.scan.shelfLocation; }
        if (vm.scan.remark) { body.remark = vm.scan.remark; }
        vm.scan.saving = true;
        HIS.post('/api/his/mr/recall/scan', body).then(function () {
          HIS.notifySuccess('回收登记成功');
          vm.scan.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.scan.saving = false; });
      },
      recallRow: function (row) {
        var vm = this;
        HIS.post('/api/his/mr/recall/scan', { visitId: HIS.id(row.visitId) }).then(function () {
          HIS.notifySuccess('已登记回收');
          vm.loadList();
        }).catch(HIS.notifyError);
      },

      /* ================= 上架 ================= */
      openShelf: function (row) {
        this.shelf = { visible: true, saving: false, row: row, location: row.shelfLocation || '' };
      },
      doShelf: function () {
        var vm = this;
        if (!vm.shelf.location) { ElementPlus.ElMessage.warning('请填写库位/架号'); return; }
        vm.shelf.saving = true;
        HIS.put('/api/his/mr/recall/' + HIS.id(vm.shelf.row.id) + '/shelf', { shelfLocation: vm.shelf.location }).then(function () {
          HIS.notifySuccess('已上架');
          vm.shelf.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.shelf.saving = false; });
      },

      /* ================= 逾期刷新 ================= */
      refreshOverdue: function () {
        var vm = this;
        HIS.post('/api/his/mr/recall/refresh-overdue', {}).then(function (n) {
          HIS.notifySuccess('逾期刷新: 标记 ' + (n || 0) + ' 份超期未收回');
          vm.loadList();
        }).catch(HIS.notifyError);
      },

      /* ================= 回收率报表 ================= */
      openRate: function () {
        this.rate = { visible: true, loading: false, from: this.rate.from, to: this.rate.to, rows: this.rate.rows, summary: this.rate.summary };
        if (this.rate.rows.length) { return; }
        this.loadRate();
      },
      loadRate: function () {
        var vm = this;
        vm.rate.loading = true;
        var q = '/api/his/mr/recall/rate';
        var sep = '?';
        if (vm.rate.from) { q += sep + 'from=' + encodeURIComponent(vm.rate.from); sep = '&'; }
        if (vm.rate.to) { q += sep + 'to=' + encodeURIComponent(vm.rate.to); }
        HIS.get(q).then(function (d) {
          vm.rate.rows = (d && d.rows) || [];
          vm.rate.summary = (d && d.summary) || {};
        }).catch(HIS.notifyError).finally(function () { vm.rate.loading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">病案收回管理 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">出院病案从临床科室回收至病案室并上架 · 条码登记 · 逾期与回收率</span></div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <el-input v-model="keyword" size="small" placeholder="患者姓名 / 条码 / 住院号" clearable style="width:200px;" @keyup.enter="search"></el-input>',
      '    <el-select v-model="deptId" size="small" filterable clearable placeholder="出院科室" style="width:180px" :loading="deptsLoading"',
      '      :filter-method="kwFilter(\'dept\')">',
      '      <el-option v-for="d in kwOptions(\'dept\', depts, [\'label\',\'deptName\',\'deptCode\'])" :key="idKey(d.id)" :label="d.label" :value="d.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="status" size="small" clearable placeholder="收回状态" style="width:130px">',
      '      <el-option v-for="s in statusOpts" :key="s.value" :label="s.label" :value="s.value"></el-option>',
      '    </el-select>',
      '    <el-button size="small" type="primary" @click="search">查询</el-button>',
      '    <el-button size="small" @click="reset">重置</el-button>',
      '    <span style="flex:1"></span>',
      '    <el-button size="small" @click="openRate">回收率报表</el-button>',
      '    <template v-if="canWrite">',
      '      <el-button size="small" @click="openEnroll">批量建单</el-button>',
      '      <el-button size="small" type="primary" @click="openScan">扫码回收</el-button>',
      '      <el-button size="small" type="warning" @click="refreshOverdue">逾期刷新</el-button>',
      '    </template>',
      '  </div>',
      '  <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无收回记录(可点批量建单登记)">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="100"></el-table-column>',
      '    <el-table-column prop="inpNo" label="住院号" width="110"></el-table-column>',
      '    <el-table-column prop="barcode" label="病案条码" width="130"></el-table-column>',
      '    <el-table-column prop="deptName" label="出院科室" min-width="120"></el-table-column>',
      '    <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.recallStatus)">{{ statusText(s.row.recallStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="应回收" width="100"><template #default="s">{{ fmtDay(s.row.dueDate) }}</template></el-table-column>',
      '    <el-table-column label="实际回收" width="150"><template #default="s">{{ fmt(s.row.recallTime) }}</template></el-table-column>',
      '    <el-table-column prop="recallUserName" label="回收人" width="90"></el-table-column>',
      '    <el-table-column label="上架" width="120"><template #default="s"><el-tag v-if="s.row.shelfFlag===1" size="small" type="success">{{ s.row.shelfLocation || \'已上架\' }}</el-tag><span v-else>-</span></template></el-table-column>',
      '    <el-table-column label="操作" width="120" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button v-if="canWrite && s.row.recallStatus!==2" type="primary" link size="small" @click="recallRow(s.row)">回收</el-button>',
      '        <el-button v-if="canWrite && s.row.recallStatus===2 && s.row.shelfFlag!==1" type="success" link size="small" @click="openShelf(s.row)">上架</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]"',
      '    @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>',
      /* ---- 批量建单弹窗 ---- */
      '<el-dialog v-model="enroll.visible" title="批量建单(收回登记)" width="720px" destroy-on-close>',
      '  <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px;" title="从已出院待编目池勾选病案生成待收回记录, 应回收日期默认出院后2天, 条码取住院号。"></el-alert>',
      '  <el-table :data="enroll.rows" border size="small" height="360px" v-loading="enroll.loading" @selection-change="onEnrollSel" empty-text="暂无待登记病案">',
      '    <el-table-column type="selection" width="42"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者" width="100"></el-table-column>',
      '    <el-table-column prop="inpNo" label="住院号" width="120"></el-table-column>',
      '    <el-table-column prop="deptName" label="出院科室" min-width="120"></el-table-column>',
      '    <el-table-column label="出院日期" width="110"><template #default="s">{{ fmtDay(s.row.dischargeDate) }}</template></el-table-column>',
      '  </el-table>',
      '  <template #footer>',
      '    <span style="float:left;color:var(--yb-ink-2);font-size:12px;">已选 {{ enroll.picked.length }} 份</span>',
      '    <el-button @click="enroll.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="enroll.saving" @click="doEnroll">确认建单</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 扫码回收弹窗 ---- */
      '<el-dialog v-model="scan.visible" title="扫码回收登记" width="460px" destroy-on-close>',
      '  <el-form label-width="90px" size="default">',
      '    <el-form-item label="病案条码" required><el-input v-model="scan.barcode" placeholder="扫描或录入条码" @keyup.enter="doScan"></el-input></el-form-item>',
      '    <el-form-item label="上架库位"><el-input v-model="scan.shelfLocation" placeholder="选填, 录入即同时上架"></el-input></el-form-item>',
      '    <el-form-item label="备注"><el-input v-model="scan.remark" type="textarea" :rows="2" placeholder="选填"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="scan.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="scan.saving" @click="doScan">确认回收</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 上架弹窗 ---- */
      '<el-dialog v-model="shelf.visible" title="病案上架" width="420px" destroy-on-close>',
      '  <el-form label-width="90px" size="default">',
      '    <el-form-item label="患者"><span>{{ shelf.row ? shelf.row.patientName : \'\' }}</span></el-form-item>',
      '    <el-form-item label="库位/架号" required><el-input v-model="shelf.location" placeholder="如 A区-03架-2层"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="shelf.visible=false">取消</el-button>',
      '    <el-button type="success" :loading="shelf.saving" @click="doShelf">确认上架</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 回收率报表弹窗 ---- */
      '<el-dialog v-model="rate.visible" title="病案回收率报表" width="640px" destroy-on-close>',
      '  <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;">',
      '    <span style="font-size:13px;">应回收日期</span>',
      '    <el-date-picker v-model="rate.from" type="date" size="small" placeholder="开始" value-format="YYYY-MM-DD" style="width:140px;"></el-date-picker>',
      '    <span>至</span>',
      '    <el-date-picker v-model="rate.to" type="date" size="small" placeholder="结束" value-format="YYYY-MM-DD" style="width:140px;"></el-date-picker>',
      '    <el-button size="small" type="primary" :loading="rate.loading" @click="loadRate">统计</el-button>',
      '  </div>',
      '  <div style="margin-bottom:10px;padding:8px 10px;background:var(--yb-fill-2, #f5f7fa);border-radius:4px;font-size:13px;" v-loading="rate.loading">',
      '    合计 {{ rate.summary.total || 0 }} 份 · 已收回 {{ rate.summary.recalled || 0 }} · 待收回 {{ rate.summary.pending || 0 }} · 逾期 {{ rate.summary.overdue || 0 }} · <b>回收率 {{ rate.summary.rate || 0 }}%</b>',
      '  </div>',
      '  <el-table :data="rate.rows" border size="small" max-height="320px" empty-text="暂无数据">',
      '    <el-table-column prop="deptName" label="出院科室" min-width="140"></el-table-column>',
      '    <el-table-column prop="total" label="总数" width="80" align="center"></el-table-column>',
      '    <el-table-column prop="recalled" label="已收回" width="80" align="center"></el-table-column>',
      '    <el-table-column prop="pending" label="待收回" width="80" align="center"></el-table-column>',
      '    <el-table-column prop="overdue" label="逾期" width="70" align="center"></el-table-column>',
      '  </el-table>',
      '</el-dialog>'
    ].join('\n')
  };
})();
