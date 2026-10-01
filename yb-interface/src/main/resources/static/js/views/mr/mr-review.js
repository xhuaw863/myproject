/* 首页质量审核与确认锁定(病案室质控侧): 审核队列 + 批量审核(错误归类汇总) + 单份错误查看 + 确认/锁定/解锁。
 * 后端 /api/his/mr/review: queue(分页) / batch-audit(POST {visitIds}) / errors/{visitId}(GET)
 *   / {visitId}/confirm(POST {opinion}) / {visitId}/lock(POST) / {visitId}/unlock(POST {reason})。
 * 状态机: audit_status 1未审核→2已审核(批量审核无强制错自动置2)→3已确认; lock_status 锁定禁改。
 * 写权限: 本机构管理员(后端 requireSelfOrgWrite 兜底 403)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrReview = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        rows: [], page: 1, size: 20, total: 0,
        auditStatus: null, catalogStatus: null,
        selected: [],
        /* 批量审核结果 */
        batch: { visible: false, running: false, data: null },
        /* 单份错误抽屉 */
        errDrawer: { visible: false, loading: false, row: null, list: [] },
        /* 解锁原因 */
        unlock: { visible: false, saving: false, row: null, reason: '' }
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      }
    },
    created: function () { this.loadQueue(); },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      loadQueue: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/mr/review/queue?page=' + vm.page + '&size=' + vm.size;
        if (vm.auditStatus != null) { q += '&auditStatus=' + vm.auditStatus; }
        if (vm.catalogStatus != null) { q += '&catalogStatus=' + vm.catalogStatus; }
        HIS.get(q).then(function (d) { vm.rows = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadQueue(); },
      reset: function () { this.auditStatus = null; this.catalogStatus = null; this.page = 1; this.loadQueue(); },
      onPage: function (p) { this.page = p; this.loadQueue(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadQueue(); },
      onSelChange: function (rows) { this.selected = rows || []; },

      /* ================= 批量审核 ================= */
      batchAudit: function () {
        var vm = this;
        if (!vm.selected.length) { ElementPlus.ElMessage.warning('请先勾选需审核的病案'); return; }
        vm.batch = { visible: true, running: true, data: null };
        HIS.post('/api/his/mr/review/batch-audit', { visitIds: vm.selected.map(function (r) { return HIS.id(r.visitId); }) })
          .then(function (res) { vm.batch.data = res; vm.loadQueue(); })
          .catch(HIS.notifyError).finally(function () { vm.batch.running = false; });
      },

      /* ================= 单份错误 ================= */
      openErrors: function (row) {
        var vm = this;
        vm.errDrawer = { visible: true, loading: true, row: row, list: [] };
        HIS.get('/api/his/mr/review/errors/' + HIS.idParam(row.visitId)).then(function (list) {
          vm.errDrawer.list = list || [];
        }).catch(HIS.notifyError).finally(function () { vm.errDrawer.loading = false; });
      },

      /* ================= 确认 / 锁定 / 解锁 ================= */
      confirm: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('填写审核意见(选填)后确认, 存在未修复强制错误将被拦截。', '审核确认', {
          inputPlaceholder: '审核意见', confirmButtonText: '确认', cancelButtonText: '取消'
        }).then(function (r) {
          return HIS.post('/api/his/mr/review/' + HIS.idParam(row.visitId) + '/confirm', { opinion: r.value || '' });
        }).then(function () { HIS.notifySuccess('已确认'); vm.loadQueue(); })
          .catch(function (e) { if (e && e.message) { HIS.notifyError(e); } });
      },
      lock: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('锁定后该病案首页将禁止任何修改, 如需改动须由质控人员解锁。确认锁定?', '确认锁定', { type: 'warning' })
          .then(function () { return HIS.post('/api/his/mr/review/' + HIS.idParam(row.visitId) + '/lock', {}); })
          .then(function () { HIS.notifySuccess('已锁定'); vm.loadQueue(); })
          .catch(function (e) { if (e && e.message) { HIS.notifyError(e); } });
      },
      openUnlock: function (row) { this.unlock = { visible: true, saving: false, row: row, reason: '' }; },
      doUnlock: function () {
        var vm = this;
        if (!vm.unlock.reason || !vm.unlock.reason.trim()) { ElementPlus.ElMessage.warning('解锁必须填写原因'); return; }
        vm.unlock.saving = true;
        HIS.post('/api/his/mr/review/' + HIS.idParam(vm.unlock.row.visitId) + '/unlock', { reason: vm.unlock.reason.trim() })
          .then(function () { HIS.notifySuccess('已解锁, 退回已审核态'); vm.unlock.visible = false; vm.loadQueue(); })
          .catch(HIS.notifyError).finally(function () { vm.unlock.saving = false; });
      },

      /* 展示辅助 */
      catStatusText: function (s) { return { 1: '待编目', 2: '编目中', 3: '已编目' }[Number(s)] || '-'; },
      auditStatusText: function (s) { return { 1: '未审核', 2: '已审核', 3: '已确认' }[Number(s)] || '-'; },
      auditTagType: function (s) { return { 1: 'info', 2: 'warning', 3: 'success' }[Number(s)] || 'info'; }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">质量审核与确认锁定 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">批量审核重跑质控规则并错误归类 · 二级确认 · 确认后可锁定 · 解锁须录原因</span></div>',
      '  <div class="toolbar" style="margin-bottom:10px;flex:none;">',
      '    <el-select v-model="auditStatus" size="small" clearable placeholder="审核状态" style="width:120px;">',
      '      <el-option label="未审核" :value="1"></el-option><el-option label="已审核" :value="2"></el-option><el-option label="已确认" :value="3"></el-option>',
      '    </el-select>',
      '    <el-select v-model="catalogStatus" size="small" clearable placeholder="编目状态" style="width:120px;">',
      '      <el-option label="已编目" :value="3"></el-option><el-option label="编目中" :value="2"></el-option>',
      '    </el-select>',
      '    <el-button size="small" type="primary" @click="search">查询</el-button>',
      '    <el-button size="small" @click="reset">重置</el-button>',
      '    <span style="flex:1"></span>',
      '    <el-button v-if="canWrite" size="small" type="success" :disabled="!selected.length" @click="batchAudit">批量审核 ({{ selected.length }})</el-button>',
      '  </div>',
      '  <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" @selection-change="onSelChange" empty-text="暂无待审核病案">',
      '    <el-table-column type="selection" width="42"></el-table-column>',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '    <el-table-column prop="catalogNo" label="病案号" width="120"></el-table-column>',
      '    <el-table-column prop="patientName" label="姓名" width="90"></el-table-column>',
      '    <el-table-column prop="dischargeDeptName" label="出院科室" min-width="110"></el-table-column>',
      '    <el-table-column prop="mainDiagName" label="主要诊断" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="编目" width="80" align="center"><template #default="s">{{ catStatusText(s.row.catalogStatus) }}</template></el-table-column>',
      '    <el-table-column label="审核" width="80" align="center"><template #default="s"><el-tag size="small" :type="auditTagType(s.row.auditStatus)">{{ auditStatusText(s.row.auditStatus) }}</el-tag></template></el-table-column>',
      '    <el-table-column label="错误" width="70" align="center"><template #default="s"><el-badge :value="s.row.errCount || 0" :hidden="!Number(s.row.errCount)" type="danger"><el-button link size="small" @click="openErrors(s.row)">查看</el-button></el-badge></template></el-table-column>',
      '    <el-table-column label="锁定" width="60" align="center"><template #default="s"><el-tag v-if="Number(s.row.lockStatus)===1" size="small" type="danger">锁</el-tag><span v-else>-</span></template></el-table-column>',
      '    <el-table-column prop="catalogerName" label="编目员" width="90"></el-table-column>',
      '    <el-table-column label="操作" width="200" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button v-if="canWrite && Number(s.row.auditStatus)<3 && Number(s.row.lockStatus)!==1" type="primary" link size="small" @click="confirm(s.row)">确认</el-button>',
      '        <el-button v-if="canWrite && Number(s.row.auditStatus)===3 && Number(s.row.lockStatus)!==1" type="warning" link size="small" @click="lock(s.row)">锁定</el-button>',
      '        <el-button v-if="canWrite && Number(s.row.lockStatus)===1" type="danger" link size="small" @click="openUnlock(s.row)">解锁</el-button>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:8px;flex:none;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>',
      /* ---- 批量审核结果 ---- */
      '<el-dialog v-model="batch.visible" title="批量审核结果" width="640px" destroy-on-close>',
      '  <div v-loading="batch.running" style="min-height:120px;">',
      '    <template v-if="batch.data">',
      '      <el-row :gutter="12" style="margin-bottom:12px;">',
      '        <el-col :span="6"><el-statistic title="患者总数" :value="batch.data.patientCount"></el-statistic></el-col>',
      '        <el-col :span="6"><el-statistic title="错误总数" :value="batch.data.totalErrors"></el-statistic></el-col>',
      '        <el-col :span="6"><el-statistic title="强制错误" :value="batch.data.forceErrors"></el-statistic></el-col>',
      '        <el-col :span="6"><el-statistic title="非强制" :value="batch.data.softErrors"></el-statistic></el-col>',
      '      </el-row>',
      '      <div style="font-weight:600;margin-bottom:6px;">按规则归类</div>',
      '      <el-table :data="batch.data.byRule" border size="small" max-height="180" empty-text="无">',
      '        <el-table-column prop="key" label="规则编码" min-width="140"></el-table-column>',
      '        <el-table-column prop="count" label="数量" width="90" align="center"></el-table-column>',
      '      </el-table>',
      '      <div style="font-weight:600;margin:10px 0 6px;">逐份明细</div>',
      '      <el-table :data="batch.data.details" border size="small" max-height="200">',
      '        <el-table-column type="index" label="#" width="45" align="center"></el-table-column>',
      '        <el-table-column prop="patientName" label="患者" min-width="100"></el-table-column>',
      '        <el-table-column prop="errorCount" label="错误数" width="90" align="center"></el-table-column>',
      '      </el-table>',
      '      <div style="font-size:12px;color:var(--yb-ink-2);margin-top:8px;">批次号: {{ batch.data.batchNo }} · 无强制错误且已定稿的病案已自动置为"已审核"。</div>',
      '    </template>',
      '  </div>',
      '  <template #footer><el-button type="primary" @click="batch.visible=false">关闭</el-button></template>',
      '</el-dialog>',
      /* ---- 单份错误抽屉 ---- */
      '<el-drawer v-model="errDrawer.visible" :title="errDrawer.row ? (\'错误项 - \' + (errDrawer.row.patientName||\'\')) : \'错误项\'" size="440px">',
      '  <div v-loading="errDrawer.loading">',
      '    <div v-if="!errDrawer.list.length" style="color:var(--yb-ink-2);">暂无错误项。</div>',
      '    <div v-for="(e,i) in errDrawer.list" :key="i" style="padding:8px 0;border-bottom:1px solid var(--yb-border);">',
      '      <el-tag size="small" :type="e.ruleCategory===\'强制\'?\'danger\':\'warning\'">{{ e.ruleCategory }}</el-tag>',
      '      <span style="margin-left:6px;font-size:12px;color:var(--yb-ink-2);">{{ e.ruleCode }}</span>',
      '      <div style="margin-top:4px;">{{ e.errorMsg }}</div>',
      '      <div style="margin-top:2px;font-size:12px;color:var(--yb-ink-2);">定位: {{ e.fieldKey }}</div>',
      '    </div>',
      '  </div>',
      '</el-drawer>',
      /* ---- 解锁原因 ---- */
      '<el-dialog v-model="unlock.visible" title="解锁病案" width="420px" destroy-on-close>',
      '  <el-form label-width="80px" size="default">',
      '    <el-form-item label="患者"><span>{{ unlock.row ? unlock.row.patientName : \'\' }}</span></el-form-item>',
      '    <el-form-item label="解锁原因" required><el-input v-model="unlock.reason" type="textarea" :rows="3" placeholder="必填, 说明解锁修改的事由"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="unlock.visible=false">取消</el-button>',
      '    <el-button type="danger" :loading="unlock.saving" @click="doUnlock">确认解锁</el-button>',
      '  </template>',
      '</el-dialog>'
    ].join('\n')
  };
})();
