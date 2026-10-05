/* RIS 报告审核工作台: 双栏 —— 左待审队列(status=1 已提交) / 右审核详情;
 * 审核通过(1->2 发布并回写医嘱执行状态) · 驳回退回(1->0 回草稿, 退回原因留档)
 * · 撤回作废(1/2->3 留痕) · 转双阅(占位) · 质控面板(占位) · PACS 查看器 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 审核工作台私有样式一次性注入 */
  (function ensureRisReviewStyles() {
    if (document.getElementById('ris-review-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-review-style';
    st.textContent = [
      /* 主区: 左队列 7/24 + 右详情 17/24 */
      '.rv-wrap { flex: 1; min-height: 0; display: flex; gap: 12px; }',
      '.rv-left { width: 29%; min-width: 280px; display: flex; flex-direction: column; min-height: 0; }',
      '.rv-right { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }',
      '.rv-panel { background: var(--yb-surface); border: 1px solid var(--yb-border); border-radius: var(--yb-r-md); display: flex; flex-direction: column; min-height: 0; overflow: hidden; }',
      '.rv-panel-head { padding: 8px 12px 6px; border-bottom: 1px solid var(--yb-border-light); background: var(--yb-surface-2); flex: none; }',
      '.rv-panel-body { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 12px; }',
      /* 待审队列项 */
      '.rv-item { border: 1px solid var(--yb-border); border-radius: var(--yb-r-sm); padding: 8px 10px; margin-bottom: 8px; cursor: pointer; transition: border-color var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease), box-shadow var(--yb-dur) var(--yb-ease); background: var(--yb-surface); }',
      '.rv-item:hover { border-color: var(--yb-link); background: var(--yb-brand-subtle); }',
      '.rv-item.active { border-color: var(--yb-link); background: var(--yb-brand-subtle); box-shadow: 0 0 0 1px var(--yb-link) inset; }',
      '.rv-item.critical { border-left: 3px solid var(--yb-fill-danger); }',
      '.rv-item .no { font-weight: 600; color: var(--yb-ink-1); font-size: var(--yb-fs-base); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; font-variant-numeric: tabular-nums; }',
      '.rv-item .sub { color: var(--yb-ink-2); font-size: var(--yb-fs-sm); margin-top: 3px; display: flex; justify-content: space-between; gap: 6px; font-variant-numeric: tabular-nums; }',
      '.rv-item .meta { color: var(--yb-ink-3); font-size: var(--yb-fs-cap); margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      /* 患者横幅 */
      '.rv-banner { background: linear-gradient(135deg, #f0f7ff 0%, #fafcff 100%); border: 1px solid var(--yb-brand-border); border-radius: var(--yb-r-md); padding: 10px 14px; margin-bottom: 12px; flex: none; }',
      '.rv-banner .name { font-size: 17px; font-weight: 700; color: var(--yb-ink-1); margin-right: 10px; font-variant-numeric: tabular-nums; }',
      '.rv-banner .attr { color: var(--yb-ink-2); font-size: var(--yb-fs-sm); margin-right: 10px; font-variant-numeric: tabular-nums; }',
      '.rv-banner .row2 { margin-top: 4px; color: var(--yb-ink-3); font-size: var(--yb-fs-cap); display: flex; gap: 12px; flex-wrap: wrap; font-variant-numeric: tabular-nums; }',
      /* 检查信息条 */
      '.rv-exambar { display: flex; gap: 14px; flex-wrap: wrap; padding: 6px 10px; background: var(--yb-surface-2); border: 1px solid var(--yb-border-light); border-radius: var(--yb-r-sm); margin-bottom: 12px; font-size: var(--yb-fs-sm); color: var(--yb-ink-2); flex: none; font-variant-numeric: tabular-nums; }',
      '.rv-exambar b { color: var(--yb-ink-1); font-weight: 600; }',
      /* 小节标题 */
      '.rv-sec-title { font-size: var(--yb-fs-sm); font-weight: 700; color: var(--yb-ink-2); margin: 14px 0 8px; padding-left: 8px; border-left: 3px solid var(--yb-brand); line-height: 14px; flex: none; }',
      /* 报告内容只读区块 */
      '.rv-block { border: 1px solid var(--yb-border-light); border-left: 3px solid var(--yb-brand-border); border-radius: var(--yb-r-sm); background: var(--yb-surface-2); padding: 8px 12px; margin-bottom: 8px; }',
      '.rv-block .k { font-size: var(--yb-fs-cap); font-weight: 700; color: var(--yb-ink-3); margin-bottom: 3px; }',
      '.rv-block .v { white-space: pre-wrap; color: var(--yb-ink-1); font-size: var(--yb-fs-base); line-height: var(--yb-lh); }',
      '.rv-block .v.empty { color: var(--yb-ink-4); }',
      /* 质控面板(占位) */
      '.rv-qc { border: 1px dashed var(--yb-ink-4); border-radius: var(--yb-r-sm); padding: 10px 12px; background: var(--yb-surface-2); color: var(--yb-ink-2); font-size: var(--yb-fs-sm); }',
      '.rv-qc .row { display: flex; justify-content: space-between; gap: 8px; padding: 3px 0; font-variant-numeric: tabular-nums; }',
      '.rv-qc .pass { color: var(--yb-success); font-weight: 700; }',
      '.rv-qc .pending { color: var(--yb-ink-3); }',
      /* 审核操作栏 */
      '.rv-ops { display: flex; gap: 10px; align-items: center; padding: 12px 0 2px; border-top: 1px dashed var(--yb-border); margin-top: 14px; flex-wrap: wrap; flex: none; }',
      '.rv-hint { color: var(--yb-ink-3); font-size: var(--yb-fs-sm); }',
      /* 危急值警示条 */
      '.rv-critical-strip { display: flex; align-items: center; gap: 8px; background: var(--yb-danger-bg); border: 1px solid var(--yb-danger-border); border-radius: var(--yb-r-sm); padding: 8px 12px; color: var(--yb-danger-strong); font-size: var(--yb-fs-sm); font-weight: 600; margin-bottom: 10px; flex: none; }',
      '@media (max-width: 1100px) { .rv-left { width: 36%; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 本地受控枚举 ===== */
  var REPORT_STATUS = [
    { v: 0, l: '待书写', t: 'info' },
    { v: 1, l: '已提交', t: 'warning' },
    { v: 2, l: '已审核', t: 'success' },
    { v: 3, l: '已作废', t: 'danger' }
  ];

  function findBy(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i]; } }
    return null;
  }
  function statusLabel(v) { var s = findBy(REPORT_STATUS, v); return s ? s.l : '-'; }
  function statusTag(v) { var s = findBy(REPORT_STATUS, v); return s ? s.t : 'info'; }
  function fmtTime(v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; }

  HIS.views['RisReview'] = {
    name: 'RisReview',
    data: function () {
      return {
        /* 左栏: 待审队列(固定 status=1) */
        keyword: '',
        list: [],
        total: 0,
        page: 1,
        size: 20,
        loading: false,
        /* 右栏: 审核详情 */
        current: null,
        detailLoading: false,
        reviewing: false,
        /* 驳回退回弹窗(reason 随请求上行留档, 退回后回草稿态) */
        rejectVisible: false,
        rejectReason: '',
        rejecting: false,
        /* 撤回作废弹窗(留痕撤回人/时间/原因) */
        voidVisible: false,
        voidReason: '',
        voiding: false,
        /* PACS */
        viewerVisible: false,
        viewerInfo: null
      };
    },
    computed: {
      statusMeta: function () { return REPORT_STATUS; },
      /* 仅已提交态可审核/驳回/作废 */
      canReview: function () { return !!this.current && this.current.status === 1; }
    },
    created: function () { this.loadList(); },
    methods: {
      statusLabel: statusLabel,
      statusTag: statusTag,
      fmtTime: fmtTime,
      /* 待审队列: RIS 走检查报告通道(reportType=exam), 固定 status=1 已提交 */
      loadList: function (keepCurrent) {
        var vm = this;
        vm.loading = true;
        var q = '/api/medtech/reports?reportType=exam&status=1&page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          if (keepCurrent === true && vm.current && vm.current.id) {
            vm.selectReport({ id: vm.current.id }, true);
          }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.loadList(); },
      search: function () { this.page = 1; this.loadList(); },
      /* 选中待审报告: 载详情(含所见/结论/结果明细) */
      selectReport: function (row, silent) {
        var vm = this;
        if (!row || !row.id) { return; }
        if (!silent) { vm.detailLoading = true; }
        HIS.get('/api/medtech/report/' + row.id).then(function (d) {
          vm.current = d;
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      /* 审核通过: 1->2, 报告发布并回写医嘱执行状态 */
      approve: function () {
        var vm = this;
        if (!vm.current) { return; }
        ElementPlus.ElMessageBox.confirm(
          '确认审核通过报告 ' + (vm.current.reportNo || '') + '？通过后报告正式发布并回写医嘱执行状态。',
          '审核通过', { type: 'success', confirmButtonText: '审核通过', cancelButtonText: '取消' }
        ).then(function () {
          vm.reviewing = true;
          return HIS.post('/api/medtech/report/' + vm.current.id + '/review', { approved: true });
        }).then(function () {
          HIS.notifySuccess('报告已审核发布');
          vm.loadList(true);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.reviewing = false; });
      },
      /* 驳回退回: 1->0 回草稿态由书写医生修改后重新提交 */
      openReject: function () {
        if (!this.current) { return; }
        this.rejectReason = '';
        this.rejectVisible = true;
      },
      reject: function () {
        var vm = this;
        if (!vm.current) { return; }
        if (!vm.rejectReason || !vm.rejectReason.trim()) {
          ElementPlus.ElMessage.warning('请填写驳回原因, 退回后书写医生可见');
          return;
        }
        vm.rejecting = true;
        HIS.post('/api/medtech/report/' + vm.current.id + '/review', { approved: false, reason: vm.rejectReason })
          .then(function () {
            vm.rejectVisible = false;
            HIS.notifySuccess('报告已驳回退回, 书写医生可修改后重新提交');
            vm.loadList(true);
          }).catch(HIS.notifyError).finally(function () { vm.rejecting = false; });
      },
      /* 撤回作废: 1/2->3 留痕(撤回人/时间/原因), body={reason} */
      openVoid: function () {
        if (!this.current) { return; }
        this.voidReason = '';
        this.voidVisible = true;
      },
      voidReport: function () {
        var vm = this;
        if (!vm.current) { return; }
        if (!vm.voidReason || !vm.voidReason.trim()) {
          ElementPlus.ElMessage.warning('请填写作废原因(留痕必填)');
          return;
        }
        vm.voiding = true;
        HIS.post('/api/medtech/report/' + vm.current.id + '/void', { reason: vm.voidReason })
          .then(function () {
            vm.voidVisible = false;
            HIS.notifySuccess('报告已撤回作废(已留痕)');
            vm.loadList(true);
          }).catch(HIS.notifyError).finally(function () { vm.voiding = false; });
      },
      /* PACS 查看器: 外部 URL 新窗口; mock 站内路由以内嵌弹窗示意渲染 */
      openViewer: function () {
        var vm = this;
        if (!vm.current) { return; }
        HIS.get('/api/pacs/report/' + vm.current.id + '/viewer').then(function (d) {
          var url = d && (d.viewerUrl || d.url);
          if (!url) {
            ElementPlus.ElMessage.warning('PACS 影像查看未启用或该报告无影像(检验类/未配置)');
            return;
          }
          if (url.charAt(0) === '#') {
            vm.viewerInfo = d;
            vm.viewerVisible = true;
          } else {
            window.open(url, '_blank');
          }
        }).catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">RIS 报告审核工作台 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(审核双签 · 通过即发布回写医嘱 · 驳回退回重写 · 作废全程留痕)</span></div>',
      '  <div class="rv-wrap">',
      /* ---- 左栏: 待审队列 ---- */
      '    <div class="rv-left">',
      '      <div class="rv-panel" style="flex:1;">',
      '        <div class="rv-panel-head">',
      '          <div style="display:flex;align-items:center;gap:8px;">',
      '            <span style="font-size:13px;font-weight:700;color:var(--yb-ink-1);">待审核报告</span>',
      '            <el-tag size="small" type="warning">已提交</el-tag>',
      '            <span style="flex:1;"></span>',
      '            <span style="color:var(--yb-ink-3);font-size:12px;font-variant-numeric:tabular-nums;">共 {{ total }} 条</span>',
      '          </div>',
      '          <el-input v-model="keyword" placeholder="报告单号/患者/患者ID" clearable size="small" style="margin-top:8px;" @keyup.enter="search">',
      '            <template #append><el-button @click="search">查询</el-button></template>',
      '          </el-input>',
      '        </div>',
      '        <div class="rv-panel-body" v-loading="loading">',
      '          <el-empty v-if="!list.length" description="暂无待审核报告" :image-size="60"></el-empty>',
      '          <div v-for="r in list" :key="r.id" class="rv-item" :class="{ active: current && current.id === r.id, critical: r.criticalFlag === 1 }" @click="selectReport(r)">',
      '            <div class="no">',
      '              {{ r.patientName || \'-\' }}',
      '              <el-tag size="small" type="warning" effect="plain">检查</el-tag>',
      '              <el-tag v-if="r.criticalFlag === 1" size="small" type="danger" effect="dark">危急值</el-tag>',
      '            </div>',
      '            <div class="sub"><span>{{ r.reportNo }}</span><span>{{ fmtTime(r.reportTime || r.createTime) }}</span></div>',
      '            <div class="meta">提交人 {{ r.reportDoctorName || \'-\' }}<template v-if="r.diagName"> · {{ r.diagName }}</template></div>',
      '          </div>',
      '        </div>',
      '        <div style="padding:6px 12px;border-top:1px solid var(--yb-border-light);flex:none;">',
      '          <el-pagination small background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '        </div>',
      '      </div>',
      '    </div>',
      /* ---- 右栏: 审核详情 ---- */
      '    <div class="rv-right">',
      '      <div class="rv-panel" style="flex:1;">',
      '        <div class="rv-panel-body" style="overflow-y:auto;" v-loading="detailLoading">',
      '          <el-empty v-if="!current" description="请选择待审核报告" :image-size="80"></el-empty>',
      '          <template v-else>',
      '            <div v-if="current.criticalFlag === 1" class="rv-critical-strip">',
      '              <el-tag size="small" type="danger" effect="dark">危急值</el-tag>',
      '              该报告已命中危急值规则, 审核发布前请确认危急值闭环处置状态。',
      '            </div>',
      '            <div class="rv-banner">',
      '              <span class="name">{{ current.patientName || \'-\' }}</span>',
      '              <span class="attr" v-if="current.genderName">{{ current.genderName }}</span>',
      '              <span class="attr" v-if="current.age != null">{{ current.age }}岁</span>',
      '              <span class="attr" v-if="current.patientNo">病历号 {{ current.patientNo }}</span>',
      '              <el-tag size="small" :type="statusTag(current.status)">{{ statusLabel(current.status) }}</el-tag>',
      '              <div class="row2">',
      '                <span>报告单号 {{ current.reportNo || \'-\' }}</span>',
      '                <span v-if="current.orderNo">医嘱号 {{ current.orderNo }}</span>',
      '                <span v-if="current.diagName">临床诊断 {{ current.diagName }}</span>',
      '                <span v-if="current.drName">开单医生 {{ current.drName }}</span>',
      '              </div>',
      '            </div>',
      '            <div class="rv-exambar">',
      '              <span>申请单号 <b>{{ current.requestNo || \'-\' }}</b></span>',
      '              <span>检查类型 <b>{{ current.examType || current.orderType || \'检查\' }}</b></span>',
      '              <span>检查部位 <b>{{ current.bodyPart || \'-\' }}</b></span>',
      '              <span>书写医生 <b>{{ current.reportDoctorName || \'-\' }}</b></span>',
      '              <span>提交时间 <b>{{ fmtTime(current.reportTime || current.createTime) }}</b></span>',
      '            </div>',
      '            <div class="rv-sec-title">报告内容(只读)</div>',
      '            <div class="rv-block"><div class="k">检查技术</div><div class="v" :class="{ empty: !current.technique }">{{ current.technique || \'未录入\' }}</div></div>',
      '            <div class="rv-block"><div class="k">影像所见</div><div class="v" :class="{ empty: !current.findings }">{{ current.findings || \'未录入\' }}</div></div>',
      '            <div class="rv-block"><div class="k">影像结论</div><div class="v" :class="{ empty: !current.conclusion }">{{ current.conclusion || \'未录入\' }}</div></div>',
      '            <div class="rv-block"><div class="k">印象</div><div class="v" :class="{ empty: !current.impression }">{{ current.impression || \'未录入\' }}</div></div>',
      '            <div class="rv-sec-title">质控结果</div>',
      '            <div class="rv-qc">',
      '              <div class="row"><span>完整性 · 所见/结论要素齐全</span><span :class="(current.findings && current.conclusion) ? \'pass\' : \'pending\'">{{ (current.findings && current.conclusion) ? \'通过\' : \'待检\' }}</span></div>',
      '              <div class="row"><span>时效性 · 提交是否超时</span><span class="pending">规则引擎接入后判定</span></div>',
      '              <div class="row"><span>术语规范 · 结构化数据元校验</span><span class="pending">规则引擎接入后判定</span></div>',
      '              <div class="row"><span>危急值关联</span><span :class="current.criticalFlag === 1 ? \'pending\' : \'pass\'">{{ current.criticalFlag === 1 ? \'已命中, 请核实闭环\' : \'未命中\' }}</span></div>',
      '            </div>',
      '            <div class="rv-sec-title">影像参考</div>',
      '            <el-button size="small" type="primary" plain @click="openViewer">打开 PACS 影像查看器</el-button>',
      '            <div class="rv-ops">',
      '              <el-button v-if="canReview" type="success" :loading="reviewing" @click="approve">审核通过</el-button>',
      '              <el-button v-if="canReview" type="danger" plain @click="openReject">驳回退回</el-button>',
      '              <el-tooltip v-if="canReview" content="功能开发中" placement="top">',
      '                <span><el-button type="warning" plain disabled>转双阅</el-button></span>',
      '              </el-tooltip>',
      '              <el-button v-if="canReview" type="danger" link @click="openVoid">撤回作废</el-button>',
      '              <span v-if="!canReview" class="rv-hint">当前状态「{{ statusLabel(current.status) }}」不可审核(仅已提交可操作)</span>',
      '            </div>',
      '          </template>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      '  <el-dialog v-model="rejectVisible" title="驳回退回" width="480px" :close-on-click-modal="false">',
      '    <div style="margin-bottom:10px;color:var(--yb-ink-2);font-size:13px;">报告退回后回到草稿状态, 书写医生修改后可重新提交送审。</div>',
      '    <el-input type="textarea" v-model="rejectReason" :rows="4" placeholder="驳回原因(必填, 书写医生可见), 如: 结论与所见不符, 请补充病灶测量径线"></el-input>',
      '    <template #footer>',
      '      <el-button @click="rejectVisible = false">取消</el-button>',
      '      <el-button type="danger" :loading="rejecting" @click="reject">确认驳回</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="voidVisible" title="撤回作废" width="480px" :close-on-click-modal="false">',
      '    <div style="margin-bottom:10px;color:var(--yb-ink-2);font-size:13px;">作废后报告置为已作废状态并全程留痕(撤回人/时间/原因), 不可恢复。</div>',
      '    <el-input type="textarea" v-model="voidReason" :rows="4" placeholder="作废原因(必填), 如: 患者信息登记错误, 申请单退回"></el-input>',
      '    <template #footer>',
      '      <el-button @click="voidVisible = false">取消</el-button>',
      '      <el-button type="danger" :loading="voiding" @click="voidReport">确认作废</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="viewerVisible" title="PACS 影像查看器(Mock)" width="640px">',
      '    <div style="border:1px dashed var(--yb-ink-4);border-radius:var(--yb-r-sm);padding:30px 16px;text-align:center;background:var(--yb-surface-2);">',
      '      <div style="font-size:15px;font-weight:700;color:var(--yb-ink-1);margin-bottom:8px;">影像查看器占位(Mock 模式)</div>',
      '      <div style="font-family:var(--yb-font-mono);font-size:12px;word-break:break-all;">StudyUID: {{ viewerInfo && viewerInfo.studyUid }}</div>',
      '      <div style="margin-top:10px;color:var(--yb-ink-3);font-size:12px;">当前为集成抽象层 Mock 演示, 真实对接时配置 pacs.mode=dicomweb 与 pacs.base_url 后直接跳转外部 DICOMweb 查看器。</div>',
      '    </div>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
