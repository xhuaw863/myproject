/* 医保上报中心(M5): 上传管线状态机(his_upload_status)监控台。
 * 按业务类型/状态筛选逐单状态, 失败待补(status=2)可手动重传(复位退避后立即补传一次);
 * 定时扫描(UploadStatusSweeper)自动补传纯上传类: VISIT(2203)/INP_REG(2401入院)/INP_DISCH(2402出院), 指数退避 1/5/15/60min, 满 6 次转人工。
 * 住院 INP_SETL(2304结算)/INP_FEE(2301明细) 为资金补偿跟踪行, 归 CompTaskSweeper 收敛, 只读展示不可重传。
 * 写操作(手动重传)ADMIN 角色鉴权(BizRoleInterceptor)。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var BIZ_TYPES = [
    { v: '', l: '全部业务' },
    { v: 'VISIT', l: '就诊上传(2203)' },
    { v: 'REG', l: '挂号(2201)' },
    { v: 'RX', l: '处方' },
    { v: 'FEE', l: '费用明细(2204)' },
    { v: 'SETL', l: '结算(2207/2208)' },
    { v: 'CANCEL', l: '撤销(2202)' },
    { v: 'INP_REG', l: '住院入院登记(2401)' },
    { v: 'INP_DISCH', l: '住院出院办理(2402)' },
    { v: 'INP_FEE', l: '住院费用明细(2301)' },
    { v: 'INP_SETL', l: '住院结算跟踪(2304)' }
  ];
  /* 本监控台可手动重传的纯上传类型(其余为补偿/业务跟踪行) */
  var RETRYABLE = ['VISIT', 'INP_REG', 'INP_DISCH'];
  var STATUSES = [
    { v: null, l: '全部状态' },
    { v: 0, l: '待传' },
    { v: 1, l: '已传' },
    { v: 2, l: '失败待补' },
    { v: 3, l: '已撤销' }
  ];

  HIS.views.UploadCenter = {
    data: function () {
      return {
        bizTypes: BIZ_TYPES, statuses: STATUSES,
        bizType: '', status: null,
        loading: false, list: [], total: 0, page: 1, size: 20,
        stats: { pending: 0, uploaded: 0, failed: 0, revoked: 0 },
        retrying: 0,
        /* 自动刷新(30s) */
        timer: null
      };
    },
    created: function () {
      this.load();
      var vm = this;
      this.timer = setInterval(function () { vm.load(); }, 30000);
    },
    beforeDestroy: function () {
      if (this.timer) { clearInterval(this.timer); this.timer = null; }
    },
    methods: {
      bizText: function (v) {
        for (var i = 0; i < BIZ_TYPES.length; i++) { if (BIZ_TYPES[i].v === v) { return BIZ_TYPES[i].l; } }
        return v || '—';
      },
      statusText: function (v) {
        if (v === 0) { return '待传'; }
        if (v === 1) { return '已传'; }
        if (v === 2) { return '失败待补'; }
        if (v === 3) { return '已撤销'; }
        return '—';
      },
      statusTag: function (v) {
        if (v === 1) { return 'success'; }
        if (v === 2) { return 'danger'; }
        if (v === 3) { return 'info'; }
        return 'warning';
      },
      canRetry: function (row) {
        return row.status === 2 && RETRYABLE.indexOf(row.bizType) >= 0;
      },
      load: function () {
        var vm = this;
        this.loading = true;
        var params = { page: this.page, size: this.size };
        if (this.bizType) { params.bizType = this.bizType; }
        if (this.status !== null && this.status !== '') { params.status = this.status; }
        HIS.get('/api/yb/upload-status/list', params)
          .then(function (res) {
            var p = res && res.data ? res.data : {};
            vm.list = p.records || [];
            vm.total = p.total || 0;
            vm.loading = false;
            vm.loadStats();
          })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 状态统计: 全量(不带状态筛选)按状态计数 */
      loadStats: function () {
        var vm = this;
        HIS.get('/api/yb/upload-status/list', { page: 1, size: 500 })
          .then(function (res) {
            var rows = (res && res.data && res.data.records) || [];
            var s = { pending: 0, uploaded: 0, failed: 0, revoked: 0 };
            rows.forEach(function (r) {
              if (r.status === 1) { s.uploaded++; }
              else if (r.status === 2) { s.failed++; }
              else if (r.status === 3) { s.revoked++; }
              else { s.pending++; }
            });
            vm.stats = s;
          })
          .catch(function () { /* 统计失败不影响列表 */ });
      },
      onFilter: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      retry: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认立即重传该记录(' + vm.bizText(row.bizType) + ')? 失败将按退避策略继续自动重试。', '手动重传', { type: 'warning' })
          .then(function () {
            vm.retrying = row.id;
            HIS.post('/api/yb/upload-status/' + row.id + '/retry', {})
              .then(function (res) {
                vm.retrying = 0;
                var d = res && res.data ? res.data : {};
                if (d && d.success) {
                  HIS.notifySuccess('重传成功, 报文ID: ' + (d.msgid || '—'));
                } else {
                  ElementPlus.ElMessage.error('重传失败: ' + (d && d.err ? d.err : '未知原因'));
                }
                vm.load();
              })
              .catch(HIS.notifyError).finally(function () { vm.retrying = 0; });
          })
          .catch(function () { /* 取消 */ });
      }
    },
    template:
      '<div style="padding:16px">' +
      '  <el-row :gutter="12" style="margin-bottom:12px">' +
      '    <el-col :span="6"><div class="ub-card" style="padding:12px;text-align:center;background:#f0f9eb">待传 <b>{{ stats.pending }}</b></div></el-col>' +
      '    <el-col :span="6"><div class="ub-card" style="padding:12px;text-align:center;background:#ecf5ff">已传 <b>{{ stats.uploaded }}</b></div></el-col>' +
      '    <el-col :span="6"><div class="ub-card" style="padding:12px;text-align:center;background:#fef0f0">失败待补 <b style="color:#f56c6c">{{ stats.failed }}</b></div></el-col>' +
      '    <el-col :span="6"><div class="ub-card" style="padding:12px;text-align:center;background:#f4f4f5">已撤销 <b>{{ stats.revoked }}</b></div></el-col>' +
      '  </el-row>' +
      '  <div class="ub-card" style="padding:12px">' +
      '    <div style="margin-bottom:10px">' +
      '      <span style="margin-right:8px;color:#909399;font-size:13px">医保上传管线状态: 0待传 / 1已传 / 2失败待补 / 3已撤销; 失败自动重试指数退避(1/5/15/60分钟), 满 6 次转人工。' +
      '        VISIT 就诊上传(2203)未成功时收费入口强制补传。</span>' +
      '    </div>' +
      '    <div style="margin-bottom:10px">' +
      '      <el-select v-model="bizType" placeholder="业务类型" size="small" style="width:210px;margin-right:8px" @change="onFilter">' +
      '        <el-option v-for="b in bizTypes" :key="b.v" :label="b.l" :value="b.v"></el-option>' +
      '      </el-select>' +
      '      <el-select v-model="status" placeholder="状态" size="small" style="width:140px;margin-right:8px" @change="onFilter">' +
      '        <el-option v-for="s in statuses" :key="String(s.v)" :label="s.l" :value="s.v"></el-option>' +
      '      </el-select>' +
      '      <el-button size="small" @click="load" :loading="loading">刷新</el-button>' +
      '    </div>' +
      '    <el-table :data="list" v-loading="loading" size="small" border stripe max-height="calc(100vh - 320px)">' +
      '      <el-table-column prop="id" label="ID" width="70"></el-table-column>' +
      '      <el-table-column label="业务" width="150"><template #default="s">{{ bizText(s.row.bizType) }}</template></el-table-column>' +
      '      <el-table-column prop="bizId" label="业务ID" width="110"></el-table-column>' +
      '      <el-table-column prop="mdtrtId" label="医保就诊ID" width="150" show-overflow-tooltip></el-table-column>' +
      '      <el-table-column label="状态" width="100"><template #default="s"><el-tag :type="statusTag(s.row.status)" size="small">{{ statusText(s.row.status) }}</el-tag></template></el-table-column>' +
      '      <el-table-column prop="retryCount" label="重试" width="60"></el-table-column>' +
      '      <el-table-column prop="nextRetry" label="下次重试" width="150"></el-table-column>' +
      '      <el-table-column prop="lastErr" label="失败原因" min-width="200" show-overflow-tooltip></el-table-column>' +
      '      <el-table-column prop="msgid" label="报文ID" width="160" show-overflow-tooltip></el-table-column>' +
      '      <el-table-column prop="updateTime" label="更新时间" width="150"></el-table-column>' +
      '      <el-table-column label="操作" width="110" fixed="right">' +
      '        <template #default="s">' +
      '          <el-button v-if="canRetry(s.row)" type="primary" size="small" :loading="retrying === s.row.id" @click="retry(s.row)">手动重传</el-button>' +
      '          <span v-else-if="s.row.status === 2" style="color:#909399;font-size:12px">补偿任务收敛</span>' +
      '        </template>' +
      '      </el-table-column>' +
      '    </el-table>' +
      '    <div style="margin-top:10px;text-align:right">' +
      '      <el-pagination layout="total, sizes, prev, pager, next" :total="total" :current-page="page" :page-size="size"' +
      '        :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize"></el-pagination>' +
      '    </div>' +
      '  </div>' +
      '</div>'
  };
})();
