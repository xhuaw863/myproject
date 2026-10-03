/* 医保对账台(M3): 3201 对总账/3202 对明细账手动触发 + 对账任务与差异明细查询 + 差异处置。
 * 默认对 T-1: 总账不平自动走 明细TXT→9101上传→3202 → 表201 差异落本表 his_recon_diff。
 * 差异处置三选一: 已核对(handle status=1, 线下核实留痕) / 已平账(status=2) / 2601 冲正(reverse, 撤平台多记/幽灵结算)。
 * 2601 结果三分: 受理→原交易置 REVERSED+本地补冲销行+差异待复核(须 force 重对该日复核平账);
 *   拒绝→400 可见原因(非白名单/无 msgid 凭据/已冲正); UNKNOWN→本地零变更, 勿重复冲正, 以对账复核为准。
 * 写操作守卫: BizRoleInterceptor /api/yb/recon/ 前缀 = ADMIN/ORG_ADMIN/SUPER_ADMIN 直通 + CASHIER。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 规范 5.2.7.1 可冲正交易白名单(与 ReconService.REVERSABLE_OINFNO 同口径) */
  var OINFNO_OPTS = ['2207', '2208', '2102', '2103', '2304', '2305', '2401', '2304A', '2102A'];
  var DIFF_STATUS = [
    { v: 0, l: '待处理' },
    { v: 1, l: '已核对' },
    { v: 2, l: '已平账' }
  ];
  /* 本地时区 yyyy-MM-dd(避免 toISOString 的 UTC 偏移) */
  function ymd(ms) {
    var d = new Date(ms); var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
  }

  HIS.views.ReconConsole = {
    data: function () {
      return {
        tab: 'tasks',
        oinfnoOpts: OINFNO_OPTS, diffStatusOpts: DIFF_STATUS,
        canWrite: HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN') || HIS.hasRole('CASHIER'),
        /* 手动对账 */
        runDate: ymd(Date.now() - 86400000), runForce: false, running: false,
        /* 任务列表 */
        tLoading: false, tasks: [], tTotal: 0, tPage: 1, tSize: 20,
        fDate: '', fType: '',
        /* 差异列表 */
        dLoading: false, diffs: [], dTotal: 0, dPage: 1, dSize: 20, fStatus: null,
        /* 处置对话框 */
        hdDlg: false, hdRow: null, hdStatus: 1, hdMemo: '', hdSaving: false,
        /* 冲正对话框 */
        rvDlg: false, rvRow: null, rvOinfno: '2207', rvMemo: '', rvBusy: false
      };
    },
    created: function () { this.loadTasks(); this.loadDiffs(); },
    methods: {
      money: function (v) { return (v === null || v === undefined || v === '') ? '—' : Number(v).toFixed(2); },
      dash: function (v) { return v ? v : '—'; },
      /* ---- 手动触发对账 ---- */
      runRecon: function () {
        var vm = this;
        var tip = '将对 ' + (vm.runDate || 'T-1') + ' 执行 3201 对总账(不平自动跟进 3202 明细账)。';
        if (vm.runForce) { tip += ' 已勾选强制重跑: 该日历史任务/差异将作废留痕。'; }
        ElementPlus.ElMessageBox.confirm(tip, '触发对账', { type: 'warning' })
          .then(function () {
            vm.running = true;
            HIS.post('/api/yb/recon/run?date=' + encodeURIComponent(vm.runDate) + '&force=' + (vm.runForce ? 'true' : 'false'), {})
              .then(function () {
                HIS.notifySuccess('对账已执行, 请刷新查看任务与差异');
                vm.loadTasks(); vm.loadDiffs();
              })
              .catch(HIS.notifyError)
              .finally(function () { vm.running = false; });
          })
          .catch(function () { /* 取消 */ });
      },
      /* ---- 任务列表 ---- */
      loadTasks: function () {
        var vm = this; vm.tLoading = true;
        var q = '/api/yb/recon/tasks?page=' + this.tPage + '&size=' + this.tSize;
        if (this.fDate) { q += '&date=' + encodeURIComponent(this.fDate); }
        if (this.fType) { q += '&type=' + encodeURIComponent(this.fType); }
        HIS.get(q)
          .then(function (p) {
            p = p || {};
            vm.tasks = p.records || []; vm.tTotal = p.total || 0;
          })
          .catch(HIS.notifyError).finally(function () { vm.tLoading = false; });
      },
      onTFilter: function () { this.tPage = 1; this.loadTasks(); },
      onTPage: function (p) { this.tPage = p; this.loadTasks(); },
      onTSize: function (s) { this.tSize = s; this.tPage = 1; this.loadTasks(); },
      resultTag: function (r) { return r === '1' ? 'success' : (r === '2' ? 'danger' : (r === '9' ? 'warning' : 'info')); },
      resultText: function (r) { return r === '1' ? '平' : (r === '2' ? '不平' : (r === '9' ? '失败' : '—')); },
      typeText: function (t) { return t === 'TOTAL' ? '总账(3201)' : (t === 'DETAIL' ? '明细账(3202)' : (t || '—')); },
      gotoDiffs: function (row) { this.fDate = row.stmtDate || ''; this.tab = 'diffs'; this.fStatus = null; this.onDFilter(); },
      /* 任务表序号跨页连续 */
      tIndex: function (i) { return (this.tPage - 1) * this.tSize + i + 1; },
      dIndex: function (i) { return (this.dPage - 1) * this.dSize + i + 1; },
      /* ---- 差异列表 ---- */
      loadDiffs: function () {
        var vm = this; vm.dLoading = true;
        var q = '/api/yb/recon/diffs?page=' + this.dPage + '&size=' + this.dSize;
        if (this.fStatus !== null && this.fStatus !== '') { q += '&status=' + this.fStatus; }
        HIS.get(q)
          .then(function (p) {
            p = p || {};
            vm.diffs = p.records || []; vm.dTotal = p.total || 0;
          })
          .catch(HIS.notifyError).finally(function () { vm.dLoading = false; });
      },
      onDFilter: function () { this.dPage = 1; this.loadDiffs(); },
      onDPage: function (p) { this.dPage = p; this.loadDiffs(); },
      onDSize: function (s) { this.dSize = s; this.dPage = 1; this.loadDiffs(); },
      diffStatusTag: function (v) { return v === 2 ? 'success' : (v === 1 ? 'warning' : 'danger'); },
      diffStatusText: function (v) { return v === 2 ? '已平账' : (v === 1 ? '已核对' : '待处理'); },
      /* ---- 人工处置(已核对/已平账, 仅留痕不动平台侧) ---- */
      openHandle: function (row) { this.hdRow = row; this.hdStatus = 1; this.hdMemo = ''; this.hdDlg = true; },
      saveHandle: function () {
        var vm = this; vm.hdSaving = true;
        HIS.post('/api/yb/recon/diff/' + vm.hdRow.id + '/handle', { status: vm.hdStatus, handleMemo: vm.hdMemo })
          .then(function () { HIS.notifySuccess('已处置'); vm.hdDlg = false; vm.loadDiffs(); })
          .catch(HIS.notifyError).finally(function () { vm.hdSaving = false; });
      },
      /* ---- 2601 冲正(人工确认, 撤平台侧多记/幽灵结算) ---- */
      canReverse: function (row) { return this.canWrite && row.msgid && row.status !== 2; },
      openReverse: function (row) { this.rvRow = row; this.rvOinfno = '2207'; this.rvMemo = ''; this.rvDlg = true; },
      doReverse: function () {
        var vm = this; vm.rvBusy = true;
        HIS.post('/api/yb/recon/diff/' + vm.rvRow.id + '/reverse', { oinfno: vm.rvOinfno, memo: vm.rvMemo })
          .then(function (d) {
            d = d || {};
            if (d.unknown) {
              ElementPlus.ElMessage.warning('冲正结果未知(平台回执超时): 本地未做任何变更, 请勿重复冲正, 以次日对账复核为准。');
            } else if (d.reversed) {
              HIS.notifySuccess(d.msg || ('2601 冲正已受理, 请及时对该日 force 重对复核'));
            } else {
              ElementPlus.ElMessage.error(d.msg || '冲正被平台拒绝, 差异保留待线下处理');
            }
            vm.rvDlg = false; vm.loadDiffs(); vm.loadTasks();
          })
          .catch(HIS.notifyError).finally(function () { vm.rvBusy = false; });
      },
      /* 差异行带对账日: 复核重对(强制重跑该日) */
      recheck: function (row) {
        var vm = this;
        this.runDate = row.stmtDate || this.runDate;
        this.runForce = true;
        this.runRecon();
      }
    },
    template:
      '<div style="padding:16px">' +
      '  <div class="ub-card" style="padding:12px;margin-bottom:12px">' +
      '    <span style="margin-right:14px;color:#303133;font-weight:600">医保对账台(3201/3202)</span>' +
      '    <span style="margin-right:8px;color:#909399;font-size:13px">对账日期</span>' +
      '    <el-date-picker v-model="runDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:150px;margin-right:10px"></el-date-picker>' +
      '    <el-checkbox v-model="runForce" style="margin-right:10px">强制重跑(作废该日历史任务/差异)</el-checkbox>' +
      '    <el-button type="primary" size="small" :disabled="!canWrite" :loading="running" @click="runRecon">触发对账</el-button>' +
      '    <span v-if="!canWrite" style="margin-left:10px;color:#e6a23c;font-size:12px">只读查阅(需收费员/管理员角色)</span>' +
      '  </div>' +
      '  <div class="ub-card" style="padding:12px">' +
      '    <el-tabs v-model="tab">' +
      '      <el-tab-pane label="对账任务" name="tasks">' +
      '        <div style="margin-bottom:10px">' +
      '          <span style="margin-right:6px;color:#909399;font-size:13px">对账日</span>' +
      '          <el-date-picker v-model="fDate" type="date" value-format="YYYY-MM-DD" size="small" clearable style="width:150px;margin-right:8px"></el-date-picker>' +
      '          <el-select v-model="fType" placeholder="类型" size="small" clearable style="width:150px;margin-right:8px" @change="onTFilter">' +
      '            <el-option label="总账(3201)" value="TOTAL"></el-option>' +
      '            <el-option label="明细账(3202)" value="DETAIL"></el-option>' +
      '          </el-select>' +
      '          <el-button size="small" @click="onTFilter" :loading="tLoading">查询</el-button>' +
      '        </div>' +
      '        <el-table :data="tasks" v-loading="tLoading" size="small" border stripe max-height="calc(100vh - 360px)">' +
      '          <el-table-column type="index" label="#" width="55" fixed :index="tIndex"></el-table-column>' +
      '          <el-table-column prop="stmtDate" label="对账日" width="105" fixed></el-table-column>' +
      '          <el-table-column prop="insutype" label="险种" width="80"></el-table-column>' +
      '          <el-table-column label="类型" width="110"><template #default="s">{{ typeText(s.row.reconType) }}</template></el-table-column>' +
      '          <el-table-column label="结果" width="80"><template #default="s"><el-tag :type="resultTag(s.row.result)" size="small">{{ resultText(s.row.result) }}</el-tag></template></el-table-column>' +
      '          <el-table-column label="医疗费(院内/平台)" width="170"><template #default="s">{{ money(s.row.medfeeLocal) }} / {{ money(s.row.medfeeRemote) }}</template></el-table-column>' +
      '          <el-table-column label="基金(院内/平台)" width="160"><template #default="s">{{ money(s.row.fundLocal) }} / {{ money(s.row.fundRemote) }}</template></el-table-column>' +
      '          <el-table-column label="个账(院内/平台)" width="150"><template #default="s">{{ money(s.row.acctLocal) }} / {{ money(s.row.acctRemote) }}</template></el-table-column>' +
      '          <el-table-column label="笔数(院内/平台)" width="120"><template #default="s">{{ dash(s.row.cntLocal) }} / {{ dash(s.row.cntRemote) }}</template></el-table-column>' +
      '          <el-table-column prop="stmtRslt" label="回执" width="110" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column prop="fileQuryNo" label="文件查询号" width="170" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column prop="reconTime" label="执行时间" width="150"></el-table-column>' +
      '          <el-table-column prop="memo" label="备注" min-width="140" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column label="操作" width="90" fixed="right">' +
      '            <template #default="s"><el-button v-if="s.row.result===\'2\'" link type="primary" size="small" @click="gotoDiffs(s.row)">查看差异</el-button></template>' +
      '          </el-table-column>' +
      '        </el-table>' +
      '        <div style="margin-top:10px;text-align:right">' +
      '          <el-pagination layout="total, sizes, prev, pager, next" :total="tTotal" :current-page="tPage" :page-size="tSize"' +
      '            :page-sizes="[10, 20, 50]" @current-change="onTPage" @size-change="onTSize"></el-pagination>' +
      '        </div>' +
      '      </el-tab-pane>' +
      '      <el-tab-pane label="差异明细" name="diffs">' +
      '        <div style="margin-bottom:10px">' +
      '          <el-select v-model="fStatus" placeholder="处理状态" size="small" clearable style="width:140px;margin-right:8px" @change="onDFilter">' +
      '            <el-option v-for="o in diffStatusOpts" :key="o.v" :label="o.l" :value="o.v"></el-option>' +
      '          </el-select>' +
      '          <el-button size="small" @click="onDFilter" :loading="dLoading">查询</el-button>' +
      '          <span style="margin-left:12px;color:#909399;font-size:12px">差异=3202 表201 核对不一致行; 冲正仅适用于平台侧多记/幽灵结算(有原交易 msgid 凭据)</span>' +
      '        </div>' +
      '        <el-table :data="diffs" v-loading="dLoading" size="small" border stripe max-height="calc(100vh - 400px)">' +
      '          <el-table-column type="index" label="#" width="55" fixed :index="dIndex"></el-table-column>' +
      '          <el-table-column prop="stmtDate" label="对账日" width="105" fixed></el-table-column>' +
      '          <el-table-column prop="psnNo" label="人员编号" width="130" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column prop="setlId" label="结算ID" width="150" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column prop="mdtrtId" label="就诊ID" width="150" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column prop="msgid" label="原报文ID" width="160" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column prop="stmtRslt" label="核对结果" width="90"></el-table-column>' +
      '          <el-table-column prop="refdSetlFlag" label="退费标志" width="80"></el-table-column>' +
      '          <el-table-column label="平台金额(费/基金/个账)" width="190"><template #default="s">{{ money(s.row.medfeeSumamt) }} / {{ money(s.row.fundPaySumamt) }} / {{ money(s.row.acctPay) }}</template></el-table-column>' +
      '          <el-table-column prop="memo" label="平台说明" min-width="160" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column label="状态" width="90"><template #default="s"><el-tag :type="diffStatusTag(s.row.status)" size="small">{{ diffStatusText(s.row.status) }}</el-tag></template></el-table-column>' +
      '          <el-table-column prop="handleMemo" label="处置记录" width="180" show-overflow-tooltip></el-table-column>' +
      '          <el-table-column label="操作" width="190" fixed="right">' +
      '            <template #default="s">' +
      '              <el-button v-if="canWrite && s.row.status !== 2" link type="primary" size="small" @click="openHandle(s.row)">处置</el-button>' +
      '              <el-button v-if="canReverse(s.row)" link type="danger" size="small" @click="openReverse(s.row)">2601冲正</el-button>' +
      '              <el-button v-if="canWrite && s.row.status !== 2" link size="small" @click="recheck(s.row)">重对复核</el-button>' +
      '            </template>' +
      '          </el-table-column>' +
      '        </el-table>' +
      '        <div style="margin-top:10px;text-align:right">' +
      '          <el-pagination layout="total, sizes, prev, pager, next" :total="dTotal" :current-page="dPage" :page-size="dSize"' +
      '            :page-sizes="[10, 20, 50]" @current-change="onDPage" @size-change="onDSize"></el-pagination>' +
      '        </div>' +
      '      </el-tab-pane>' +
      '    </el-tabs>' +
      '  </div>' +
      /* 处置对话框 */
      '  <el-dialog v-model="hdDlg" title="差异人工处置" width="520px">' +
      '    <div style="color:#909399;font-size:12px;margin-bottom:10px">仅院内留痕处置(不动平台侧): 已核对=线下核实原因待平账; 已平账=确认差异已解决。</div>' +
      '    <el-form label-width="80px">' +
      '      <el-form-item label="处置"><el-radio-group v-model="hdStatus"><el-radio :value="1">已核对</el-radio><el-radio :value="2">已平账</el-radio></el-radio-group></el-form-item>' +
      '      <el-form-item label="备注"><el-input v-model="hdMemo" type="textarea" :rows="2" placeholder="核实情况与处置说明"></el-input></el-form-item>' +
      '    </el-form>' +
      '    <template #footer><el-button @click="hdDlg=false">取消</el-button><el-button type="primary" :loading="hdSaving" @click="saveHandle">保存</el-button></template>' +
      '  </el-dialog>' +
      /* 冲正对话框 */
      '  <el-dialog v-model="rvDlg" title="2601 冲正确认(人工)" width="560px">' +
      '    <div style="margin-bottom:10px;color:#606266;font-size:13px;line-height:1.7">' +
      '      人员编号: <b>{{ rvRow && rvRow.psnNo }}</b>　原报文ID: <b>{{ rvRow && rvRow.msgid }}</b><br>' +
      '      冲正将撤销平台侧该笔结算(适用于机构多记/幽灵结算); 受理后本地自动补冲销行并使 3201 净额归零,' +
      '      <b style="color:#e6a23c">仍需对该日「强制重跑」复核平账</b>。结果未知时严禁重复冲正。' +
      '    </div>' +
      '    <el-form label-width="90px">' +
      '      <el-form-item label="原交易"><el-select v-model="rvOinfno" size="small" style="width:140px"><el-option v-for="o in oinfnoOpts" :key="o" :label="o" :value="o"></el-option></el-select></el-form-item>' +
      '      <el-form-item label="备注"><el-input v-model="rvMemo" type="textarea" :rows="2" placeholder="冲正原因(留痕到差异处置记录)"></el-input></el-form-item>' +
      '    </el-form>' +
      '    <template #footer><el-button @click="rvDlg=false">取消</el-button><el-button type="danger" :loading="rvBusy" @click="doReverse">确认冲正</el-button></template>' +
      '  </el-dialog>' +
      '</div>'
  };
})();
