/* 门诊处方审核工作台(P2): 待审/已驳回队列 + 批量通过 + 驳回留因 + 自动审核(合理用药Mock) + 重审 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var RX_TYPES = [
    { v: '西药', t: 'primary' }, { v: '中药', t: 'success' }
  ];
  function rxTag(v) { for (var i = 0; i < RX_TYPES.length; i++) { if (RX_TYPES[i].v === v) { return RX_TYPES[i].t; } } return 'info'; }
  function money(v) { var n = Number(v || 0); return '¥ ' + n.toFixed(2); }

  HIS.views.OutpRxAudit = {
    data: function () {
      return {
        lead: HIS.isLead(), loading: false, tab: 1, list: [], total: 0, page: 1, size: 20,
        depts: [], filterDept: null, keyword: '', stats: { pending: 0, passedToday: 0, rejectedToday: 0 },
        selection: [],
        // 详情弹窗
        dlgVisible: false, detailLoading: false, detail: null,
        // 驳回
        rejectVisible: false, rejectReason: '', rejectCustom: '', rejectTarget: null,
        rejectReasons: []
      };
    },
    created: function () { this.loadDepts(); this.loadReasons(); this.load(); this.loadStats(); },
    methods: {
      loadDepts: function () { var vm = this; HIS.get('/api/his/dept/enabled').then(function (l) { vm.depts = l || []; }).catch(HIS.notifyError); },
      loadReasons: function () { var vm = this; HIS.get('/api/his/pharmacy/rx-audit/reject-reasons').then(function (l) { vm.rejectReasons = l || []; }).catch(HIS.notifyError); },
      loadStats: function () { var vm = this; HIS.get('/api/his/pharmacy/rx-audit/stats').then(function (d) { vm.stats = d || vm.stats; }).catch(HIS.notifyError); },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/pharmacy/rx-audit/pending?auditStatus=' + vm.tab + '&page=' + vm.page + '&size=' + vm.size;
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) { vm.list = (d && d.rows) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      switchTab: function (t) { this.tab = t; this.page = 1; this.selection = []; this.load(); },
      onSearch: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      onSel: function (rows) { this.selection = rows || []; },
      // 批量通过
      batchPass: function () {
        var vm = this;
        if (!vm.selection.length) { ElementPlus.ElMessage.warning('请先勾选要审核的处方'); return; }
        var ids = vm.selection.map(function (r) { return r.id; });
        ElementPlus.ElMessageBox.confirm('确认批量通过所选 ' + ids.length + ' 张处方？', '批量审核', { type: 'warning' }).then(function () {
          return HIS.post('/api/his/pharmacy/rx-audit/batch-audit', { ids: ids });
        }).then(function (d) {
          HIS.notifySuccess('已通过 ' + ((d && d.affected) || 0) + ' 张'); vm.reload();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      // 单张通过
      passOne: function (row) {
        var vm = this;
        HIS.post('/api/his/pharmacy/rx-audit/batch-audit', { ids: [row.id] }).then(function (d) {
          HIS.notifySuccess('已通过 ' + ((d && d.affected) || 0) + ' 张'); vm.reload();
        }).catch(HIS.notifyError);
      },
      // 自动审核
      autoAudit: function (row) {
        var vm = this;
        HIS.post('/api/his/pharmacy/rx-audit/auto-audit', { prescriptionId: row.id }).then(function (d) {
          var lv = (d && d.level) || 'pass';
          if (lv === 'block') { ElementPlus.ElMessage.warning('自动审核拦截, 已置驳回'); }
          else if (lv === 'warn') { HIS.notifySuccess('自动审核通过(含提示)'); }
          else { HIS.notifySuccess('自动审核通过'); }
          vm.reload();
        }).catch(HIS.notifyError);
      },
      // 重审(已驳回→待审)
      reAudit: function (row) {
        var vm = this;
        HIS.post('/api/his/pharmacy/rx-audit/re-audit', { prescriptionId: row.id }).then(function () {
          HIS.notifySuccess('已重审(重新进入待审)'); vm.reload();
        }).catch(HIS.notifyError);
      },
      openDetail: function (row) {
        var vm = this; vm.dlgVisible = true; vm.detailLoading = true; vm.detail = null;
        HIS.get('/api/his/pharmacy/rx-audit/detail?prescriptionId=' + row.id).then(function (d) { vm.detail = d || null; })
          .catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      // 驳回: 行内或详情
      openReject: function (row) {
        this.rejectTarget = row; this.rejectReason = ''; this.rejectCustom = ''; this.rejectVisible = true;
      },
      doReject: function () {
        var vm = this;
        var reason = (vm.rejectCustom || '').trim() || (vm.rejectReason || '').trim();
        if (!reason) { ElementPlus.ElMessage.warning('请选择或填写驳回原因'); return; }
        HIS.post('/api/his/pharmacy/rx-audit/reject', { prescriptionId: vm.rejectTarget.id, reason: reason }).then(function () {
          HIS.notifySuccess('已驳回'); vm.rejectVisible = false; vm.reload();
        }).catch(HIS.notifyError);
      },
      reload: function () { this.load(); this.loadStats(); },
      levelText: function (lv) { return lv === 'block' ? '拦截' : (lv === 'warn' ? '提示' : '通过'); },
      levelTag: function (lv) { return lv === 'block' ? 'danger' : (lv === 'warn' ? 'warning' : 'success'); }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">门诊处方审核 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(审方工作台 · 批量/自动审核 · 驳回留因 · 药师与本机构管理员可操作)</span></div>',
      '  <div style="display:flex;gap:12px;margin-bottom:12px;">',
      '    <div class="stat-mini"><span class="k">待审</span><span class="v" style="color:#e6a23c;">{{ stats.pending }}</span></div>',
      '    <div class="stat-mini"><span class="k">今日通过</span><span class="v" style="color:#67c23a;">{{ stats.passedToday }}</span></div>',
      '    <div class="stat-mini"><span class="k">今日驳回</span><span class="v" style="color:#f56c6c;">{{ stats.rejectedToday }}</span></div>',
      '  </div>',
      '  <el-radio-group v-model="tab" style="margin-bottom:10px;" @change="switchTab">',
      '    <el-radio-button :label="1">待审队列</el-radio-button><el-radio-button :label="3">已驳回(重审)</el-radio-button>',
      '  </el-radio-group>',
      '  <div class="toolbar">',
      '    <el-select v-model="filterDept" placeholder="全部科室" clearable style="width:160px;" @change="onSearch"><el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="患者姓名/处方号" clearable style="width:200px;" @keyup.enter="onSearch"></el-input>',
      '    <el-button type="primary" @click="onSearch">查询</el-button>',
      '    <el-button v-if="tab===1" type="success" :disabled="!selection.length" @click="batchPass">批量通过({{ selection.length }})</el-button>',
      '    <el-button @click="load">刷新</el-button>',
      '    <span style="flex:1;"></span><span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 张</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%" @selection-change="onSel">',
      '    <el-table-column v-if="tab===1" type="selection" width="46"></el-table-column>',
      '    <el-table-column type="index" label="#" width="48" align="center"></el-table-column>',
      '    <el-table-column prop="rxNo" label="处方号" width="150"></el-table-column>',
      '    <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="120"></el-table-column>',
      '    <el-table-column prop="doctorName" label="医师" width="90"></el-table-column>',
      '    <el-table-column label="类型" width="70" align="center"><template #default="s"><el-tag size="small" :type="rxTag(s.row.rxType)">{{ s.row.rxType }}</el-tag></template></el-table-column>',
      '    <el-table-column label="药品数" width="70" align="center"><template #default="s">{{ s.row.itemCount }}</template></el-table-column>',
      '    <el-table-column label="金额" width="100" align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '    <el-table-column prop="prescribeTime" label="开方时间" width="150"></el-table-column>',
      '    <el-table-column v-if="tab===3" prop="rejectReason" label="驳回原因" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="操作" width="260" fixed="right"><template #default="s">',
      '      <el-button link type="primary" size="small" @click="openDetail(s.row)">详情</el-button>',
      '      <template v-if="tab===1">',
      '        <el-button link type="success" size="small" @click="passOne(s.row)">通过</el-button>',
      '        <el-button link size="small" @click="autoAudit(s.row)">自动审核</el-button>',
      '        <el-button link type="danger" size="small" @click="openReject(s.row)">驳回</el-button>',
      '      </template>',
      '      <template v-else><el-button link size="small" @click="reAudit(s.row)">重审</el-button></template>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:10px;" background layout="total, sizes, prev, pager, next" :total="total"',
      '    :page-size="size" :current-page="page" :page-sizes="[20,50,100]" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  <!-- 详情 -->',
      '  <el-dialog v-model="dlgVisible" title="处方审核详情" width="760px">',
      '    <div v-loading="detailLoading">',
      '      <template v-if="detail">',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="处方号">{{ detail.prescription.rxNo }}</el-descriptions-item>',
      '          <el-descriptions-item label="患者">{{ detail.prescription.patientName }}</el-descriptions-item>',
      '          <el-descriptions-item label="科室">{{ detail.prescription.deptName }}</el-descriptions-item>',
      '          <el-descriptions-item label="医师">{{ detail.prescription.doctorName }}</el-descriptions-item>',
      '          <el-descriptions-item label="金额">{{ money(detail.prescription.totalAmount) }}</el-descriptions-item>',
      '          <el-descriptions-item label="开方时间">{{ detail.prescription.prescribeTime }}</el-descriptions-item>',
      '          <el-descriptions-item label="临床诊断" :span="2">{{ detail.prescription.diagName || "-" }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div style="margin:12px 0 6px;font-weight:600;">合理用药自动审查',
      '          <el-tag size="small" :type="levelTag(detail.rational.level)" style="margin-left:8px;">{{ levelText(detail.rational.level) }}</el-tag>',
      '        </div>',
      '        <el-alert v-for="(b,i) in detail.rational.blocks" :key="\'b\'+i" type="error" :closable="false" show-icon :title="b" style="margin-bottom:4px;"></el-alert>',
      '        <el-alert v-for="(w,i) in detail.rational.warns" :key="\'w\'+i" type="warning" :closable="false" show-icon :title="w" style="margin-bottom:4px;"></el-alert>',
      '        <div v-if="!detail.rational.blocks.length && !detail.rational.warns.length" style="color:var(--yb-ink-2);font-size:12px;">未发现用药风险</div>',
      '        <div style="margin:12px 0 6px;font-weight:600;">处方明细</div>',
      '        <el-table :data="detail.items" border size="small" max-height="240">',
      '          <el-table-column prop="drugName" label="药品" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="spec" label="规格" width="120"></el-table-column>',
      '          <el-table-column prop="quantity" label="数量" width="70" align="center"></el-table-column>',
      '          <el-table-column label="用法" width="120"><template #default="s">{{ (s.row.usageName||\'\')+(s.row.frequency?(\' \'+s.row.frequency):\'\') }}</template></el-table-column>',
      '        </el-table>',
      '      </template>',
      '    </div>',
      '    <template #footer><el-button @click="dlgVisible=false">关 闭</el-button></template>',
      '  </el-dialog>',
      '  <!-- 驳回 -->',
      '  <el-dialog v-model="rejectVisible" title="驳回处方" width="460px">',
      '    <el-form label-width="80px">',
      '      <el-form-item label="驳回原因"><el-select v-model="rejectReason" placeholder="选择常见原因" style="width:100%;" clearable><el-option v-for="r in rejectReasons" :key="r" :label="r" :value="r"></el-option></el-select></el-form-item>',
      '      <el-form-item label="补充说明"><el-input type="textarea" :rows="2" v-model="rejectCustom" maxlength="200" placeholder="可留空(以选择原因)/填写具体意见"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="rejectVisible=false">取 消</el-button><el-button type="danger" @click="doReject">确认驳回</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
