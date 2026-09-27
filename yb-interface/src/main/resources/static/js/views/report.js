/* 查询报表: 结算记录查询(门诊收费单+住院医保结算) + 门诊日结(汇总预览/执行/记录) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 共用工具 ================= */
  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) {
    var d = new Date();
    d.setDate(d.getDate() - n);
    return fmtDate(d);
  }
  function money(n) { return (n === null || n === undefined) ? '0.00' : Number(n).toFixed(2); }
  function num(n) { return (n === null || n === undefined) ? '0' : String(n); }

  /* ================= 结算记录查询 ================= */
  HIS.views.SettleRecords = {
    data: function () {
      return {
        loading: false, exporting: false,
        list: [], total: 0, page: 1, size: 20,
        /* type: ''全部 / outpatient门诊(本地收费单) / inpatient住院(医保结算留存) */
        type: '',
        dateRange: [daysAgo(29), today()],
        keyword: ''
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; }
    },
    created: function () { this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/report/settle-records?page=' + vm.page + '&size=' + vm.size;
        if (vm.type) { q += '&type=' + vm.type; }
        if (vm.startDate) { q += '&startDate=' + vm.startDate; }
        if (vm.endDate) { q += '&endDate=' + vm.endDate; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      /* 就诊类型: 本地收费单=门诊, 医保住院结算留存=住院 */
      bizLabel: function (row) { return row.bizType === 'inpatient' ? '住院' : '门诊'; },
      bizTag: function (row) { return row.bizType === 'inpatient' ? 'warning' : 'primary'; },
      /* 状态: 门诊单 0待收费/1已收费/2已退费; 住院结算 1已结算/0已撤销 */
      statusLabel: function (row) {
        var s = Number(row.status);
        if (row.bizType === 'inpatient') { return s === 1 ? '已结算' : '已撤销'; }
        if (s === 0) { return '待收费'; }
        if (s === 1) { return '已收费'; }
        if (s === 2) { return '已退费'; }
        return s;
      },
      statusTag: function (row) {
        var s = Number(row.status);
        if (row.bizType === 'inpatient') { return s === 1 ? 'success' : 'info'; }
        if (s === 0) { return 'info'; }
        if (s === 1) { return 'success'; }
        return 'danger';
      },
      money: money,
      /* 导出与列表同区间口径(导出接口仅按日期, 不区分类型/关键词) */
      doExport: function () {
        var vm = this; vm.exporting = true;
        var q = '';
        if (vm.startDate) { q += '?startDate=' + vm.startDate; }
        if (vm.endDate) { q += (q ? '&' : '?') + 'endDate=' + vm.endDate; }
        HIS.download('/api/his/report/export/settle' + q, '结算记录.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">结算记录 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(门诊收费单 + 住院医保结算留存, 金额单位: 元)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="type" style="width:110px" @change="search">',
      '      <el-option label="全部类型" value=""></el-option>',
      '      <el-option label="门诊" value="outpatient"></el-option>',
      '      <el-option label="住院" value="inpatient"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-input v-model="keyword" placeholder="患者姓名 / 收费单号" clearable style="width:210px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 笔</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="billNo" label="收费单号" min-width="170" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="90"></el-table-column>',
      '    <el-table-column label="就诊类型" width="84" align="center"><template #default="s">',
      '      <el-tag size="small" :type="bizTag(s.row)" effect="light">{{ bizLabel(s.row) }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column label="总金额" prop="totalAmount" width="110" align="right" header-align="right"><template #default="s">',
      '      <b style="color:var(--yb-ink-1);">{{ money(s.row.totalAmount) }}</b>',
      '    </template></el-table-column>',
      '    <el-table-column label="基金支付" prop="fundPay" width="100" align="right" header-align="right"><template #default="s">{{ money(s.row.fundPay) }}</template></el-table-column>',
      '    <el-table-column label="个账支付" prop="acctPay" width="100" align="right" header-align="right"><template #default="s">{{ money(s.row.acctPay) }}</template></el-table-column>',
      '    <el-table-column label="自付金额" prop="selfPay" width="100" align="right" header-align="right"><template #default="s">{{ money(s.row.selfPay) }}</template></el-table-column>',
      '    <el-table-column prop="chargeBy" label="收费员" width="80"><template #default="s">{{ s.row.chargeBy || "-" }}</template></el-table-column>',
      '    <el-table-column prop="chargeTime" label="收费时间" width="160"></el-table-column>',
      '    <el-table-column label="状态" width="84" align="center"><template #default="s">',
      '      <el-tag size="small" :type="statusTag(s.row)">{{ statusLabel(s.row) }}</el-tag>',
      '    </template></el-table-column>',
      '    <el-table-column prop="deptName" label="科室" width="110"><template #default="s">{{ s.row.deptName || "-" }}</template></el-table-column>',
      '    <el-table-column prop="drName" label="医生" width="80"><template #default="s">{{ s.row.drName || "-" }}</template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page"',
      '    @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };

  /* ================= 门诊日结 ================= */
  HIS.views.DailySettle = {
    data: function () {
      return {
        /* 日结操作区 */
        settleDate: today(),
        previewLoading: false,
        preview: null,          /* 当日汇总预览(与后端日结SQL同口径) */
        truncated: false,       /* 当日单据超过500笔时预览不完整提示 */
        submitting: false,
        /* 日结记录列表 */
        loading: false, list: [], total: 0, page: 1, size: 20,
        dateRange: [daysAgo(29), today()]
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      /* 预览口径提示: 净额 = 收费 - 退费 */
      netCash: function () { return this.money((this.preview && this.preview.cashTotal) || 0); }
    },
    created: function () {
      this.loadPreview();
      this.load();
    },
    methods: {
      /* ---- 日结预览: 拉当日收费单(含退费单), 前端按日结SQL口径聚合 ---- */
      loadPreview: function () {
        var vm = this;
        if (!vm.settleDate) { vm.preview = null; return; }
        vm.previewLoading = true;
        HIS.get('/api/his/cashier/bills?startDate=' + vm.settleDate + '&endDate=' + vm.settleDate + '&page=1&size=500')
          .then(function (d) {
            var recs = (d && d.records) || [];
            vm.truncated = (d && d.total || 0) > recs.length;
            var agg = { totalCount: 0, totalAmount: 0, refundCount: 0, refundAmount: 0, cashTotal: 0, fundTotal: 0, acctTotal: 0 };
            /* 口径对齐后端日结: status>=1(已收费/已退费), billType=1收费/billType=2退费(冲减为负) */
            recs.forEach(function (r) {
              if ((Number(r.status) || 0) < 1) { return; }
              var amt = Number(r.totalAmount) || 0;
              var cash = Number(r.cashPay) || 0;
              var fund = Number(r.fundPay) || 0;
              var acct = Number(r.acctPay) || 0;
              if (Number(r.billType) === 2) {
                agg.refundCount++; agg.refundAmount += amt;
                agg.cashTotal -= cash; agg.fundTotal -= fund; agg.acctTotal -= acct;
              } else {
                agg.totalCount++; agg.totalAmount += amt;
                agg.cashTotal += cash; agg.fundTotal += fund; agg.acctTotal += acct;
              }
            });
            vm.preview = agg;
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.previewLoading = false; });
      },
      /* ---- 执行日结: 二次确认后提交(后端幂等, 已日结直接返回) ---- */
      doSettle: function () {
        var vm = this;
        if (!vm.settleDate) { ElementPlus.ElMessage.warning('请先选择日结日期'); return; }
        var p = vm.preview;
        var msg = '将对 ' + vm.settleDate + ' 执行门诊日结:<br/>'
          + '收费 ' + num(p && p.totalCount) + ' 笔 / ¥' + money(p && p.totalAmount)
          + ', 退费 ' + num(p && p.refundCount) + ' 笔 / ¥' + money(p && p.refundAmount)
          + '<br/><b style="color:var(--yb-warning);">日结后当日单据不可再退费</b>, 确认执行?';
        ElementPlus.ElMessageBox.confirm(msg, '日结确认', {
          type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '执行日结', cancelButtonText: '再想想'
        }).then(function () {
          vm.submitting = true;
          HIS.post('/api/his/cashier/daily-settle?date=' + vm.settleDate, null)
            .then(function (s) {
              HIS.notifySuccess('日结完成: ' + (s && s.settleDate || vm.settleDate) + ', 操作人 ' + (s && s.operator || '-'));
              vm.load();
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.submitting = false; });
        }).catch(function () { /* 取消 */ });
      },
      /* ---- 日结记录列表 ---- */
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/report/daily-settles?page=' + vm.page + '&size=' + vm.size;
        if (vm.startDate) { q += '&startDate=' + vm.startDate; }
        if (vm.endDate) { q += '&endDate=' + vm.endDate; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.onPage(1); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      statusLabel: function (v) { return Number(v) === 1 ? '已日结' : '待日结'; },
      statusTag: function (v) { return Number(v) === 1 ? 'success' : 'info'; },
      money: money,
      num: num
    },
    template: [
      '<div>',
      /* ---- 上半部分: 日结操作区 ---- */
      '  <el-card shadow="never" class="settle-card" style="margin-bottom:14px;">',
      '    <template #header>',
      '      <div style="display:flex;align-items:center;justify-content:space-between;">',
      '        <div><span style="font-size:15px;font-weight:600;color:var(--yb-ink-1);">门诊日结</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:10px;">按收费单汇总当日交易(收费/退费冲减), 日结后当日不可再退费</span></div>',
      '        <span style="font-size:12px;color:var(--yb-ink-2);">日结日期: <b style="color:var(--yb-link);">{{ settleDate || "-" }}</b></span>',
      '      </div>',
      '    </template>',
      '    <div class="toolbar" style="margin-bottom:12px;">',
      '      <el-date-picker v-model="settleDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日结日期" style="width:170px" @change="loadPreview"></el-date-picker>',
      '      <el-button :loading="previewLoading" @click="loadPreview">刷新预览</el-button>',
      '      <el-button type="primary" :loading="submitting" @click="doSettle">执行日结</el-button>',
      '    </div>',
      '    <el-alert v-if="truncated" type="warning" :closable="false" show-icon style="margin-bottom:10px;"',
      '      title="当日单据超过500笔, 预览汇总仅基于前500笔, 实际日结以后端全量为准"></el-alert>',
      '    <el-descriptions :column="4" border size="small" class="settle-desc">',
      '      <el-descriptions-item label="收费笔数"><b class="sd-plain">{{ num(preview && preview.totalCount) }}</b> 笔</el-descriptions-item>',
      '      <el-descriptions-item label="总金额"><b class="sd-money">¥ {{ money(preview && preview.totalAmount) }}</b></el-descriptions-item>',
      '      <el-descriptions-item label="退费笔数"><b class="sd-plain">{{ num(preview && preview.refundCount) }}</b> 笔</el-descriptions-item>',
      '      <el-descriptions-item label="退费金额"><b class="sd-money">¥ {{ money(preview && preview.refundAmount) }}</b></el-descriptions-item>',
      '      <el-descriptions-item label="现金合计"><b class="sd-money">¥ {{ money(preview && preview.cashTotal) }}</b></el-descriptions-item>',
      '      <el-descriptions-item label="基金合计"><b class="sd-money">¥ {{ money(preview && preview.fundTotal) }}</b></el-descriptions-item>',
      '      <el-descriptions-item label="个账合计"><b class="sd-money">¥ {{ money(preview && preview.acctTotal) }}</b></el-descriptions-item>',
      '      <el-descriptions-item label="净收入(收费-退费)"><b class="sd-money">¥ {{ money(preview ? (preview.totalAmount - preview.refundAmount) : 0) }}</b></el-descriptions-item>',
      '    </el-descriptions>',
      '  </el-card>',
      /* ---- 下半部分: 日结记录列表 ---- */
      '  <div class="page-card">',
      '    <div class="page-title">日结记录 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按结算日期降序, 金额单位: 元)</span></div>',
      '    <div class="toolbar">',
      '      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '        start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '      <el-button type="primary" @click="search">查询</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ total }} 条</span>',
      '    </div>',
      '    <el-table :data="list" v-loading="loading" border stripe size="small">',
      '      <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '      <el-table-column prop="settleDate" label="日期" width="105"></el-table-column>',
      '      <el-table-column label="收费笔数" prop="totalCount" width="90" align="right" header-align="right"></el-table-column>',
      '      <el-table-column label="总金额" prop="totalAmount" width="110" align="right" header-align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '      <el-table-column label="退费笔数" prop="refundCount" width="90" align="right" header-align="right"></el-table-column>',
      '      <el-table-column label="退费金额" prop="refundAmount" width="110" align="right" header-align="right"><template #default="s">{{ money(s.row.refundAmount) }}</template></el-table-column>',
      '      <el-table-column label="现金合计" prop="cashTotal" width="110" align="right" header-align="right"><template #default="s">{{ money(s.row.cashTotal) }}</template></el-table-column>',
      '      <el-table-column label="基金合计" prop="fundTotal" width="110" align="right" header-align="right"><template #default="s">{{ money(s.row.fundTotal) }}</template></el-table-column>',
      '      <el-table-column label="个账合计" prop="acctTotal" width="110" align="right" header-align="right"><template #default="s">{{ money(s.row.acctTotal) }}</template></el-table-column>',
      '      <el-table-column label="状态" width="84" align="center"><template #default="s">',
      '        <el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag>',
      '      </template></el-table-column>',
      '      <el-table-column prop="operator" label="操作人" width="90"></el-table-column>',
      '      <el-table-column prop="settleTime" label="日结时间" width="160"></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '      :total="total" :page-size="size" :page-sizes="[20, 50, 100]" :current-page="page"',
      '      @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
