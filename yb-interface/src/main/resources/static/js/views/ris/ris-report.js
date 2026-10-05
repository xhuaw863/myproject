/* RIS 报告书写工作台: 三栏工作站 —— 左报告队列 / 中报告编辑(检查技术·所见·结论·印象·阳性标识,
 * Ctrl+S 保存草稿, Ctrl+Enter 提交审核) / 右参考面板(PACS 影像 + 患者历史报告);
 * 报告走医技报告通道(reportType=exam), 提交自动检测危急值。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 报告书写工作台私有样式一次性注入 */
  (function ensureRisReportStyles() {
    if (document.getElementById('ris-report-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-report-style';
    st.textContent = [
      /* 三栏主区 */
      '.rpt-wrap { flex: 1; min-height: 0; display: flex; gap: 12px; }',
      '.rpt-col { display: flex; flex-direction: column; min-height: 0; min-width: 0; }',
      '.rpt-col-l { width: 21%; min-width: 240px; }',
      '.rpt-col-c { flex: 1; }',
      '.rpt-col-r { width: 28%; min-width: 260px; }',
      '.rpt-panel { background: var(--yb-surface); border: 1px solid var(--yb-border); border-radius: var(--yb-r-md); display: flex; flex-direction: column; min-height: 0; overflow: hidden; }',
      '.rpt-panel-head { padding: 8px 12px 6px; border-bottom: 1px solid var(--yb-border-light); background: var(--yb-surface-2); flex: none; }',
      '.rpt-panel-title { font-size: var(--yb-fs-sm); font-weight: 700; color: var(--yb-ink-2); padding: 10px 12px 8px; border-bottom: 1px solid var(--yb-border-light); background: var(--yb-surface-2); flex: none; }',
      '.rpt-panel-body { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 12px; }',
      /* 队列项 */
      '.rpt-item { border: 1px solid var(--yb-border); border-radius: var(--yb-r-sm); padding: 8px 10px; margin-bottom: 8px; cursor: pointer; transition: border-color var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease), box-shadow var(--yb-dur) var(--yb-ease); background: var(--yb-surface); }',
      '.rpt-item:hover { border-color: var(--yb-link); background: var(--yb-brand-subtle); }',
      '.rpt-item.active { border-color: var(--yb-link); background: var(--yb-brand-subtle); box-shadow: 0 0 0 1px var(--yb-link) inset; }',
      '.rpt-item.critical { border-left: 3px solid var(--yb-fill-danger); }',
      '.rpt-item .no { font-weight: 600; color: var(--yb-ink-1); font-size: var(--yb-fs-base); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; font-variant-numeric: tabular-nums; }',
      '.rpt-item .sub { color: var(--yb-ink-2); font-size: var(--yb-fs-sm); margin-top: 3px; display: flex; justify-content: space-between; gap: 6px; font-variant-numeric: tabular-nums; }',
      /* 患者横幅(紧凑) */
      '.rpt-banner { background: linear-gradient(135deg, #f0f7ff 0%, #fafcff 100%); border: 1px solid var(--yb-brand-border); border-radius: var(--yb-r-md); padding: 8px 12px; margin-bottom: 10px; flex: none; }',
      '.rpt-banner .name { font-size: 16px; font-weight: 700; color: var(--yb-ink-1); margin-right: 8px; font-variant-numeric: tabular-nums; }',
      '.rpt-banner .attr { color: var(--yb-ink-2); font-size: var(--yb-fs-sm); margin-right: 8px; font-variant-numeric: tabular-nums; }',
      '.rpt-banner .row2 { margin-top: 4px; color: var(--yb-ink-3); font-size: var(--yb-fs-cap); display: flex; gap: 12px; flex-wrap: wrap; font-variant-numeric: tabular-nums; }',
      /* 检查信息条 */
      '.rpt-exambar { display: flex; gap: 14px; flex-wrap: wrap; padding: 6px 10px; background: var(--yb-surface-2); border: 1px solid var(--yb-border-light); border-radius: var(--yb-r-sm); margin-bottom: 10px; font-size: var(--yb-fs-sm); color: var(--yb-ink-2); flex: none; font-variant-numeric: tabular-nums; }',
      '.rpt-exambar b { color: var(--yb-ink-1); font-weight: 600; }',
      /* 表单区 */
      '.rpt-form-label { font-size: var(--yb-fs-sm); font-weight: 700; color: var(--yb-ink-2); margin: 10px 0 4px; display: flex; align-items: center; gap: 6px; }',
      '.rpt-form-label .req { color: var(--yb-danger); }',
      '.rpt-form-label .kbd { font-family: var(--yb-font-mono); font-size: 11px; color: var(--yb-ink-3); background: var(--yb-surface-3); border: 1px solid var(--yb-border); border-radius: 3px; padding: 0 4px; line-height: 15px; }',
      /* 底部操作栏 */
      '.rpt-ops { display: flex; gap: 10px; align-items: center; padding-top: 12px; border-top: 1px dashed var(--yb-border); margin-top: 14px; flex-wrap: wrap; flex: none; }',
      '.rpt-hint { color: var(--yb-ink-3); font-size: var(--yb-fs-sm); }',
      /* 右栏 PACS / 历史 */
      '.rpt-pacs-box { border: 1px dashed var(--yb-ink-4); border-radius: var(--yb-r-sm); padding: 14px 10px; text-align: center; color: var(--yb-ink-2); font-size: var(--yb-fs-sm); background: var(--yb-surface-2); }',
      '.rpt-study { display: flex; gap: 8px; align-items: center; border: 1px solid var(--yb-border); border-radius: var(--yb-r-sm); padding: 6px 8px; margin-bottom: 6px; font-size: var(--yb-fs-sm); color: var(--yb-ink-2); font-variant-numeric: tabular-nums; }',
      '.rpt-study .uid { font-family: var(--yb-font-mono); font-size: 11px; color: var(--yb-ink-3); word-break: break-all; }',
      '.rpt-hist-item { border: 1px solid var(--yb-border); border-radius: var(--yb-r-sm); padding: 7px 9px; margin-bottom: 6px; cursor: pointer; transition: border-color var(--yb-dur) var(--yb-ease), background var(--yb-dur) var(--yb-ease); }',
      '.rpt-hist-item:hover { border-color: var(--yb-link); background: var(--yb-brand-subtle); }',
      '.rpt-hist-item.active { border-color: var(--yb-link); box-shadow: 0 0 0 1px var(--yb-link) inset; background: var(--yb-brand-subtle); }',
      '.rpt-hist-item .t { display: flex; justify-content: space-between; gap: 6px; color: var(--yb-ink-1); font-size: var(--yb-fs-sm); font-weight: 600; font-variant-numeric: tabular-nums; }',
      '.rpt-hist-item .d { color: var(--yb-ink-3); font-size: var(--yb-fs-cap); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      /* 历史报告只读查看框 */
      '.rpt-readonly { border: 1px solid var(--yb-border-light); border-radius: var(--yb-r-sm); background: var(--yb-surface-2); padding: 10px 12px; margin-top: 8px; }',
      '.rpt-readonly h5 { margin: 0 0 6px; font-size: var(--yb-fs-sm); color: var(--yb-ink-1); }',
      '.rpt-readonly p { margin: 0 0 8px; white-space: pre-wrap; color: var(--yb-ink-2); font-size: var(--yb-fs-sm); line-height: var(--yb-lh); }',
      '@media (max-width: 1200px) { .rpt-col-l { width: 26%; } .rpt-col-r { width: 34%; } }'
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
  /* 演示报告模板(占位快捷填充, 结构化模板库接入后替换为后端模板接口) */
  var DEMO_TEMPLATES = [
    {
      key: 'ct-chest',
      label: '胸部CT平扫(演示)',
      technique: '检查技术: 患者仰卧位, 行胸部螺旋CT平扫, 层厚5mm, 螺距1.0, 肺窗/纵隔窗重组观察。',
      findings: '影像所见:\n胸廓对称, 气管及主支气管通畅。两肺纹理清晰, 走行自然, 未见明显实变影、结节影及磨玻璃影; 两肺门不大, 纵隔居中, 纵隔内未见肿大淋巴结。心脏大小、形态如常, 心包不厚。两侧胸腔未见积液, 胸膜无增厚。所示骨质结构未见明显破坏。',
      conclusion: '影像结论:\n1. 两肺CT平扫未见明显实质性病变;\n2. 纵隔、胸腔及心影未见异常。',
      impression: '本次胸部CT平扫未见明显异常, 建议结合临床随诊复查。'
    },
    {
      key: 'mri-head',
      label: '头颅MRI平扫(演示)',
      technique: '检查技术: 头颅MRI平扫, 序列包括 T1WI、T2WI、DWI、FLAIR, 层厚5mm, 层间距1mm。',
      findings: '影像所见:\n两侧大脑半球对称, 脑灰白质分界清楚, 两侧基底节区、丘脑及脑干形态信号未见异常, 未见明确梗死灶及占位性病变。脑室系统对称, 无扩大, 中线结构居中。脑沟、脑裂、脑池未见增宽。小脑、脑干形态如常。颅骨骨质信号未见异常。',
      conclusion: '影像结论:\n1. 头颅MRI平扫未见明显异常信号;\n2. 建议必要时增强MRI进一步检查。',
      impression: '头颅MRI平扫目前未见明显异常。'
    },
    {
      key: 'us-abd',
      label: '腹部超声(演示)',
      technique: '检查技术: 患者空腹, 取仰卧位, 行腹部脏器二维超声扫查并存图。',
      findings: '影像所见:\n肝脏大小、形态正常, 包膜光滑, 实质回声均匀, 肝内管道结构清晰, 未见明显占位。胆囊大小正常, 壁光滑, 不厚, 腔内未见明显异常回声。胰腺大小形态如常, 主胰管不扩张。脾脏厚度正常, 实质回声均匀。双肾大小正常, 集合系统无分离, 未见明显结石及积液。',
      conclusion: '影像结论:\n肝、胆、胰、脾、双肾超声检查未见明显异常。',
      impression: '腹部超声目前未见明显异常。'
    },
    {
      key: 'dr-chest',
      label: '胸部DR正位(演示)',
      technique: '检查技术: 站立后前位胸部DR摄影, 深吸气末曝光, 包括双侧肺野及肋膈角。',
      findings: '影像所见:\n两侧胸廓对称, 两肺野透亮度正常, 肺纹理走行自然, 未见明显实变及占位征象。两肺门不大。心影大小、形态如常。两侧膈面光滑, 肋膈角锐利。',
      conclusion: '影像结论:\n两肺、心影及膈肌未见明显异常。',
      impression: '胸部DR未见明显异常。'
    }
  ];
  /* 阳性标识: -1未判定/0阴性/1阳性(医保4501报告阳性标志) */
  var POSITIVE_OPTS = [
    { v: -1, l: '未判定' },
    { v: 0, l: '阴性' },
    { v: 1, l: '阳性' }
  ];

  function findBy(list, v) {
    for (var i = 0; i < list.length; i++) { if (list[i].v === v) { return list[i]; } }
    return null;
  }
  function statusLabel(v) { var s = findBy(REPORT_STATUS, v); return s ? s.l : '-'; }
  function statusTag(v) { var s = findBy(REPORT_STATUS, v); return s ? s.t : 'info'; }
  function fmtTime(v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; }

  HIS.views['RisReport'] = {
    name: 'RisReport',
    data: function () {
      return {
        /* 左栏: 报告队列 */
        tab: '0',
        keyword: '',
        list: [],
        total: 0,
        page: 1,
        size: 20,
        loading: false,
        /* 中栏: 当前报告与编辑区 */
        current: null,
        detailLoading: false,
        technique: '',
        findings: '',
        conclusion: '',
        impression: '',
        positiveFlag: -1,
        saving: false,
        submitting: false,
        /* 右栏: PACS 与历史报告 */
        studies: [],
        studiesLoading: false,
        historyList: [],
        historyLoading: false,
        historyCurrent: null,
        viewerVisible: false,
        viewerInfo: null,
        /* 新建报告草稿 */
        draftVisible: false,
        draftOrderId: null,
        creatingDraft: false
      };
    },
    computed: {
      statusMeta: function () { return REPORT_STATUS; },
      templates: function () { return DEMO_TEMPLATES; },
      positiveOpts: function () { return POSITIVE_OPTS; },
      /* 仅草稿态可编辑 */
      canEdit: function () { return !!this.current && this.current.status === 0; }
    },
    created: function () { this.loadList(); },
    mounted: function () {
      var vm = this;
      vm._keyHandler = function (ev) {
        if (!(ev.ctrlKey || ev.metaKey)) { return; }
        var k = String(ev.key || '').toLowerCase();
        if (k === 's') {
          ev.preventDefault();
          if (vm.canEdit) { vm.saveDraft(); }
        } else if (k === 'enter') {
          ev.preventDefault();
          if (vm.canEdit) { vm.submitReport(); }
        }
      };
      window.addEventListener('keydown', vm._keyHandler);
    },
    beforeUnmount: function () {
      if (this._keyHandler) {
        window.removeEventListener('keydown', this._keyHandler);
        this._keyHandler = null;
      }
    },
    methods: {
      statusLabel: statusLabel,
      statusTag: statusTag,
      fmtTime: fmtTime,
      /* 报告队列: RIS 走检查报告通道(reportType=exam, 库内小写口径) */
      loadList: function (keepCurrent) {
        var vm = this;
        vm.loading = true;
        var q = '/api/medtech/reports?reportType=exam&page=' + vm.page + '&size=' + vm.size + '&status=' + vm.tab;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          if (keepCurrent === true && vm.current && vm.current.id) {
            vm.selectReport({ id: vm.current.id }, true);
          }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onTab: function () {
        this.page = 1;
        this.current = null;
        this.resetEditor();
        this.loadSidePanels();
        this.loadList();
      },
      onPage: function (p) { this.page = p; this.loadList(); },
      search: function () { this.page = 1; this.loadList(); },
      resetEditor: function () {
        this.technique = '';
        this.findings = '';
        this.conclusion = '';
        this.impression = '';
        this.positiveFlag = -1;
        this.historyCurrent = null;
      },
      loadSidePanels: function () {
        this.studies = [];
        this.historyList = [];
      },
      /* 选中报告: 载详情填编辑区 + 右栏联动(PACS Study / 患者历史报告) */
      selectReport: function (row, silent) {
        var vm = this;
        if (!row || !row.id) { return; }
        if (!silent) { vm.detailLoading = true; }
        HIS.get('/api/medtech/report/' + row.id).then(function (d) {
          vm.current = d;
          vm.technique = (d && d.technique) || '';
          vm.findings = (d && d.findings) || '';
          vm.conclusion = (d && d.conclusion) || '';
          vm.impression = (d && d.impression) || '';
          vm.positiveFlag = d && d.positiveFlag != null ? d.positiveFlag : -1;
          vm.historyCurrent = null;
          vm.loadStudies(row.id);
          if (d && d.patientId != null) { vm.loadHistory(d.patientId); } else { vm.historyList = []; }
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      /* PACS Study 列表(集成抽象层/Mock, 未启用返回空) */
      loadStudies: function (reportId) {
        var vm = this;
        vm.studiesLoading = true;
        vm.studies = [];
        HIS.get('/api/pacs/report/' + reportId + '/studies').then(function (d) {
          vm.studies = (d && d.records) || d || [];
        }).catch(function () { vm.studies = []; })
          .finally(function () { vm.studiesLoading = false; });
      },
      /* 患者历史报告(排除作废, 供对照参考) */
      loadHistory: function (patientId) {
        var vm = this;
        vm.historyLoading = true;
        vm.historyList = [];
        HIS.get('/api/medtech/reports/patient/' + patientId + '?reportType=exam').then(function (d) {
          vm.historyList = (d && d.records) || d || [];
        }).catch(function () { vm.historyList = []; })
          .finally(function () { vm.historyLoading = false; });
      },
      selectHistory: function (r) { this.historyCurrent = this.historyCurrent && this.historyCurrent.id === r.id ? null : r; },
      /* 打开 PACS 查看器: 外部 URL 新窗口; mock 站内路由以内嵌弹窗示意渲染 */
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
      },
      /* 演示模板快捷填充(整段覆盖, 二次确认防误覆盖已录内容) */
      applyTemplate: function (key) {
        var vm = this;
        var tpl = null;
        for (var i = 0; i < DEMO_TEMPLATES.length; i++) {
          if (DEMO_TEMPLATES[i].key === key) { tpl = DEMO_TEMPLATES[i]; break; }
        }
        if (!tpl) { return; }
        var doApply = function () {
          vm.technique = tpl.technique;
          vm.findings = tpl.findings;
          vm.conclusion = tpl.conclusion;
          vm.impression = tpl.impression;
          HIS.notifySuccess('已套用演示模板: ' + tpl.label);
        };
        if (vm.findings || vm.conclusion) {
          ElementPlus.ElMessageBox.confirm('套用模板将覆盖当前已录入的检查技术与所见/结论, 是否继续？', '套用报告模板',
            { type: 'warning', confirmButtonText: '覆盖套用', cancelButtonText: '取消' }
          ).then(doApply).catch(function () { });
        } else {
          doApply();
        }
      },
      /* 保存草稿: 后端持久化 findings/conclusion/resultItems; technique/impression/positiveFlag 随请求上行(前向兼容) */
      saveDraft: function () {
        var vm = this;
        if (!vm.current) { return; }
        if (!vm.findings || !String(vm.findings).trim()) {
          ElementPlus.ElMessage.warning('请填写影像所见后再保存');
          return;
        }
        vm.saving = true;
        HIS.post('/api/medtech/report/' + vm.current.id + '/save', {
          findings: vm.findings,
          conclusion: vm.conclusion,
          resultItems: null,
          technique: vm.technique,
          impression: vm.impression,
          positiveFlag: vm.positiveFlag
        }).then(function () {
          HIS.notifySuccess('草稿已保存 (Ctrl+S)');
          vm.selectReport({ id: vm.current.id }, true);
          vm.loadList(true);
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      /* 提交审核: 返回 criticalFlag=1 时弹危急值提示 */
      submitReport: function () {
        var vm = this;
        if (!vm.current) { return; }
        if (!vm.findings || !String(vm.findings).trim()) {
          ElementPlus.ElMessage.warning('请填写影像所见后再提交');
          return;
        }
        ElementPlus.ElMessageBox.confirm('确认提交报告 ' + (vm.current.reportNo || '') + ' 送审？提交后将自动检测危急值。', '提交审核 (Ctrl+Enter)',
          { type: 'warning', confirmButtonText: '提交送审', cancelButtonText: '取消' }
        ).then(function () {
          vm.submitting = true;
          return HIS.post('/api/medtech/report/' + vm.current.id + '/submit');
        }).then(function (d) {
          if (d && d.criticalFlag === 1) {
            var lines = (d.criticals || []).map(function (c) {
              return '· ' + (c.itemName || c.itemCode || '项目') + ': ' + (c.resultValue != null ? c.resultValue : '')
                + (c.resultUnit || '') + (c.refRange ? ' (参考 ' + c.refRange + ')' : '');
            });
            ElementPlus.ElMessageBox.alert(
              '该报告命中危急值规则, 请立即到「危急值闭环管理」处理并在30分钟内电话通知临床:<br/><br/>'
              + (lines.length ? lines.join('<br/>') : '· 命中危急值规则(明细见危急值管理)'),
              '危急值警告', { type: 'error', dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
            );
          } else {
            HIS.notifySuccess('报告已提交送审');
          }
          vm.selectReport({ id: vm.current.id }, true);
          vm.loadList(true);
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(function () { vm.submitting = false; });
      },
      /* 新建报告草稿(按医嘱ID建检查报告, 幂等: 已有报告直接返回) */
      openDraft: function () { this.draftOrderId = null; this.draftVisible = true; },
      submitDraft: function () {
        var vm = this;
        if (!vm.draftOrderId) { ElementPlus.ElMessage.warning('请输入医嘱ID'); return; }
        vm.creatingDraft = true;
        HIS.post('/api/medtech/report/draft', { orderId: vm.draftOrderId, reportType: 'exam' })
          .then(function (r) {
            vm.draftVisible = false;
            HIS.notifySuccess(r && r.status !== 0 ? '该医嘱已存在报告(单号 ' + r.reportNo + '), 已打开' : '报告草稿已创建: ' + (r && r.reportNo));
            vm.tab = String(r && r.status != null ? r.status : 0);
            vm.page = 1;
            vm.loadList();
            if (r && r.id) { vm.selectReport({ id: r.id }); }
          }).catch(HIS.notifyError)
          .finally(function () { vm.creatingDraft = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">RIS 报告书写工作台 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(检查技术 · 影像所见 · 结论 · 印象 · 阳性标识 · 报告-审核双签 · Ctrl+S 保存 / Ctrl+Enter 提交)</span></div>',
      '  <div class="rpt-wrap">',
      /* ---- 左栏: 报告队列 ---- */
      '    <div class="rpt-col rpt-col-l">',
      '      <div class="rpt-panel" style="flex:1;">',
      '        <div class="rpt-panel-head">',
      '          <el-tabs v-model="tab" @tab-change="onTab" stretch>',
      '            <el-tab-pane label="待书写" name="0"></el-tab-pane>',
      '            <el-tab-pane label="已提交" name="1"></el-tab-pane>',
      '            <el-tab-pane label="已审核" name="2"></el-tab-pane>',
      '          </el-tabs>',
      '        </div>',
      '        <div style="padding:8px 12px 0;flex:none;">',
      '          <el-input v-model="keyword" placeholder="报告单号/患者/患者ID" clearable size="small" @keyup.enter="search">',
      '            <template #append><el-button @click="search">查询</el-button></template>',
      '          </el-input>',
      '        </div>',
      '        <div class="rpt-panel-body" v-loading="loading">',
      '          <div style="display:flex;justify-content:flex-end;margin-bottom:6px;"><el-button size="small" type="primary" plain @click="openDraft">新建报告</el-button></div>',
      '          <el-empty v-if="!list.length" description="暂无报告" :image-size="60"></el-empty>',
      '          <div v-for="r in list" :key="r.id" class="rpt-item" :class="{ active: current && current.id === r.id, critical: r.criticalFlag === 1 }" @click="selectReport(r)">',
      '            <div class="no">{{ r.patientName || \'-\' }}<el-tag size="small" type="warning" effect="plain">检查</el-tag><el-tag v-if="r.criticalFlag === 1" size="small" type="danger" effect="dark">危急值</el-tag></div>',
      '            <div class="sub"><span>{{ r.reportNo }}</span><span>{{ fmtTime(r.reportTime || r.createTime) }}</span></div>',
      '          </div>',
      '        </div>',
      '        <div style="padding:6px 12px;border-top:1px solid var(--yb-border-light);flex:none;">',
      '          <el-pagination small background layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '        </div>',
      '      </div>',
      '    </div>',
      /* ---- 中栏: 报告编辑器 ---- */
      '    <div class="rpt-col rpt-col-c">',
      '      <div class="rpt-panel" style="flex:1;">',
      '        <div class="rpt-panel-body" style="overflow-y:auto;" v-loading="detailLoading">',
      '          <el-empty v-if="!current" description="请选择待报告项目" :image-size="80"></el-empty>',
      '          <template v-else>',
      '            <div class="rpt-banner">',
      '              <span class="name">{{ current.patientName || \'-\' }}</span>',
      '              <span class="attr" v-if="current.genderName">{{ current.genderName }}</span>',
      '              <span class="attr" v-if="current.age != null">{{ current.age }}岁</span>',
      '              <span class="attr" v-if="current.patientNo">病历号 {{ current.patientNo }}</span>',
      '              <el-tag size="small" :type="statusTag(current.status)">{{ statusLabel(current.status) }}</el-tag>',
      '              <el-tag v-if="current.criticalFlag === 1" size="small" type="danger" effect="dark">危急值</el-tag>',
      '              <div class="row2">',
      '                <span>报告单号 {{ current.reportNo || \'-\' }}</span>',
      '                <span v-if="current.orderNo">医嘱号 {{ current.orderNo }}</span>',
      '                <span v-if="current.diagName">临床诊断 {{ current.diagName }}</span>',
      '                <span v-if="current.drName">开单医生 {{ current.drName }}</span>',
      '              </div>',
      '            </div>',
      '            <div class="rpt-exambar">',
      '              <span>申请单号 <b>{{ current.requestNo || \'-\' }}</b></span>',
      '              <span>检查类型 <b>{{ current.examType || current.orderType || \'检查\' }}</b></span>',
      '              <span>检查部位 <b>{{ current.bodyPart || \'-\' }}</b></span>',
      '              <span>Modality <b>{{ current.modality || \'-\' }}</b></span>',
      '              <span>报告时间 <b>{{ fmtTime(current.reportTime || current.createTime) }}</b></span>',
      '            </div>',
      '            <div v-if="canEdit" style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex:none;">',
      '              <el-dropdown @command="applyTemplate">',
      '                <el-button size="small" plain>常用模板<i class="el-icon--right">▾</i></el-button>',
      '                <template #dropdown>',
      '                  <el-dropdown-menu>',
      '                    <el-dropdown-item v-for="t in templates" :key="t.key" :command="t.key">{{ t.label }}</el-dropdown-item>',
      '                  </el-dropdown-menu>',
      '                </template>',
      '              </el-dropdown>',
      '              <span class="rpt-hint">演示模板快捷填充, 结构化模板库接入后启用</span>',
      '            </div>',
      '            <div class="rpt-form-label">检查技术</div>',
      '            <el-input type="textarea" v-model="technique" :rows="2" :disabled="!canEdit" placeholder="体位/序列/层厚/曝光参数等检查技术描述"></el-input>',
      '            <div class="rpt-form-label">影像所见 <span class="req">*</span></div>',
      '            <el-input type="textarea" v-model="findings" :rows="6" :disabled="!canEdit" placeholder="逐项描述影像阳性与阴性所见"></el-input>',
      '            <div class="rpt-form-label">影像结论</div>',
      '            <el-input type="textarea" v-model="conclusion" :rows="4" :disabled="!canEdit" placeholder="分条列出影像诊断结论"></el-input>',
      '            <div class="rpt-form-label">印象</div>',
      '            <el-input type="textarea" v-model="impression" :rows="3" :disabled="!canEdit" placeholder="综合印象与建议(报告尾段)"></el-input>',
      '            <div class="rpt-form-label">阳性标识 <span class="kbd">医保4501</span></div>',
      '            <el-radio-group v-model="positiveFlag" :disabled="!canEdit">',
      '              <el-radio v-for="o in positiveOpts" :key="o.v" :label="o.v">{{ o.l }}</el-radio>',
      '            </el-radio-group>',
      '            <div class="rpt-ops">',
      '              <el-button v-if="canEdit" :loading="saving" @click="saveDraft">保存草稿 <span style="font-family:var(--yb-font-mono);font-size:11px;color:var(--yb-ink-3);">Ctrl+S</span></el-button>',
      '              <el-button v-if="canEdit" type="primary" :loading="submitting" @click="submitReport">提交审核 <span style="font-family:var(--yb-font-mono);font-size:11px;opacity:.8;">Ctrl+Enter</span></el-button>',
      '              <el-tooltip content="即将推出" placement="top">',
      '                <span><el-button disabled>AI 辅助</el-button></span>',
      '              </el-tooltip>',
      '              <span v-if="!canEdit" class="rpt-hint">当前状态「{{ statusLabel(current.status) }}」不可编辑, 仅草稿可书写</span>',
      '            </div>',
      '          </template>',
      '        </div>',
      '      </div>',
      '    </div>',
      /* ---- 右栏: 参考面板(PACS + 历史报告) ---- */
      '    <div class="rpt-col rpt-col-r">',
      '      <div class="rpt-panel" style="flex:1;">',
      '        <div class="rpt-panel-title">PACS 影像</div>',
      '        <div class="rpt-panel-body" style="flex:none;max-height:38%;overflow-y:auto;" v-loading="studiesLoading">',
      '          <template v-if="current">',
      '            <el-button size="small" type="primary" plain style="width:100%;margin-bottom:8px;" @click="openViewer">打开影像查看器</el-button>',
      '            <div v-if="!studies.length" class="rpt-pacs-box">该报告暂无关联 Study<br/>(未启用 PACS 或检查尚未完成)</div>',
      '            <div v-for="(s, i) in studies" :key="i" class="rpt-study">',
      '              <el-tag size="small" type="info" effect="plain">{{ s.modality || \'OT\' }}</el-tag>',
      '              <div style="flex:1;min-width:0;">',
      '                <div class="uid">{{ s.studyUid }}</div>',
      '                <div style="font-size:11px;color:var(--yb-ink-3);">Series {{ s.seriesCount || 1 }} · {{ s.studyDescription || \'\' }}</div>',
      '              </div>',
      '              <el-tag v-if="s.mock === 1" size="small" type="warning" effect="plain">Mock</el-tag>',
      '            </div>',
      '          </template>',
      '          <div v-else class="rpt-pacs-box">选择报告后显示关联影像</div>',
      '        </div>',
      '        <div class="rpt-panel-title">患者历史报告 <span v-if="historyList.length" style="font-weight:normal;color:var(--yb-ink-3);">({{ historyList.length }})</span></div>',
      '        <div class="rpt-panel-body" style="flex:1;min-height:0;" v-loading="historyLoading">',
      '          <el-empty v-if="current && !historyList.length" description="暂无历史报告" :image-size="50"></el-empty>',
      '          <div v-if="!current" class="rpt-pacs-box">选择报告后显示该患者历史检查报告</div>',
      '          <div v-for="h in historyList" :key="h.id" class="rpt-hist-item" :class="{ active: historyCurrent && historyCurrent.id === h.id }" @click="selectHistory(h)">',
      '            <div class="t"><span>{{ h.reportNo }}</span><span>{{ fmtTime(h.reportTime || h.createTime) }}</span></div>',
      '            <div class="d">{{ h.diagName || (h.reportType === \'exam\' ? \'影像检查\' : \'-\') }} · {{ h.reportDoctorName || \'\' }}</div>',
      '          </div>',
      '          <div v-if="historyCurrent" class="rpt-readonly">',
      '            <h5>{{ historyCurrent.reportNo }} <el-tag size="small" :type="statusTag(historyCurrent.status)">{{ statusLabel(historyCurrent.status) }}</el-tag></h5>',
      '            <p><b>所见:</b> {{ historyCurrent.findings || \'-\' }}</p>',
      '            <p><b>结论:</b> {{ historyCurrent.conclusion || \'-\' }}</p>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      '  <el-dialog v-model="draftVisible" title="新建检查报告草稿" width="420px" :close-on-click-modal="false">',
      '    <el-form label-width="80px">',
      '      <el-form-item label="医嘱ID"><el-input v-model="draftOrderId" placeholder="关联检查医嘱ID(his_order.id)"></el-input></el-form-item>',
      '      <div style="color:var(--yb-ink-3);font-size:12px;margin-left:4px;">幂等: 该医嘱已有报告时直接打开原报告</div>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="draftVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="creatingDraft" @click="submitDraft">创建草稿</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="viewerVisible" title="PACS 影像查看器(Mock)" width="640px">',
      '    <div class="rpt-pacs-box" style="padding:30px 16px;">',
      '      <div style="font-size:15px;font-weight:700;color:var(--yb-ink-1);margin-bottom:8px;">影像查看器占位(Mock 模式)</div>',
      '      <div style="font-family:var(--yb-font-mono);font-size:12px;word-break:break-all;">StudyUID: {{ viewerInfo && viewerInfo.studyUid }}</div>',
      '      <div style="margin-top:10px;color:var(--yb-ink-3);font-size:12px;">当前为集成抽象层 Mock 演示, 未真实对接院外 PACS。真实对接时配置 pacs.mode=dicomweb 与 pacs.base_url, 将直接跳转外部 DICOMweb 查看器。</div>',
      '    </div>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
