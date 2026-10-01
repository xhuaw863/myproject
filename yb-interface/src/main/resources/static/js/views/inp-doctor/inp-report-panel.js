/* 住院医生站 - 报告与趋势页签(T1-C): 按患者历史检查/检验/病理报告 + 结果明细 + ECharts 趋势 + 危急值待办计数
 * 直连既有端点(纯前端复用, 镜像门诊 views/doctor/report-panel.js):
 *   GET /api/medtech/reports/patient/{patientId}?reportType= | GET /api/medtech/report/{id} | GET /api/medtech/reports/trend?patientId&itemCode
 *   GET /api/medtech/critical-values/my-pending (顶部危急值待办计数徽标)
 * 注册: HIS.components.InpReportPanel(供 InpDoctorWorkstation 局部注册 'inp-report', 脚本须早于 inp-doctor.js)。样式私有前缀 irp-*。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  function asList(v) { return Array.isArray(v) ? v : ((v && v.records) || []); }

  /* 异常标志(与 his_exam_result_item.abnormal_flag 对齐: 0正常 1偏高 2偏低 3危急高 4危急低) */
  var ABNORMAL_LABELS = { 0: '', 1: '↑偏高', 2: '↓偏低', 3: '↑危急', 4: '↓危急' };
  /* 报告类型 Tab: 全部 / 检验(lab) / 检查(exam) / 病理(pathology) */
  var TYPE_TABS = [
    { key: '', label: '全部' },
    { key: 'lab', label: '检验' },
    { key: 'exam', label: '检查' },
    { key: 'pathology', label: '病理' }
  ];

  var InpReportPanel = {
    name: 'InpReportPanel',
    props: {
      patientId: { type: [String, Number], default: null },
      visitId: { type: [String, Number], default: null }
    },
    data: function () {
      return {
        tabs: TYPE_TABS,
        typeFilter: '',
        reports: [],
        loading: false,
        expandedId: null,
        detail: null,
        detailLoading: false,
        trendVisible: false,
        trendTitle: '',
        trendEmpty: false,
        criticalCount: 0,
        /* T2阶段5-1: 外部影像 PACS 集成(接入骨架/Mock) */
        pacs: null,
        pacsLoading: false,
        pacsVisible: false
      };
    },
    computed: {
      filteredReports: function () {
        var list = this.reports || [];
        if (!this.typeFilter) { return list; }
        return list.filter(function (r) { return String(r.report_type || r.reportType || '') === this.typeFilter; }, this);
      }
    },
    watch: {
      patientId: function () { this.expandedId = null; this.detail = null; this.loadReports(); this.loadCritical(); },
      typeFilter: function () { this.expandedId = null; this.detail = null; }
    },
    methods: {
      pid: function () { return this.patientId != null ? String(this.patientId) : ''; },
      typeText: function (t) {
        return t === 'lab' ? '检验' : (t === 'exam' ? '检查' : (t === 'pathology' ? '病理' : (t || '报告')));
      },
      abnormalText: function (f) { return ABNORMAL_LABELS[Number(f)] || ''; },
      isCritical: function (r) { return Number(r.critical_flag || r.criticalFlag) === 1; },
      /* 阶段2a: 已撤回/作废报告(status=3 或后端附 revoked) */
      isRevoked: function (r) { return r.revoked === true || Number(r.status) === 3; },
      revokeTip: function (r) {
        var t = r.revokeTime || r.revoke_time || '';
        var rs = r.revokeReason || r.revoke_reason || '';
        return '已撤回' + (t ? (' · ' + t) : '') + (rs ? (' · 原因: ' + rs) : '');
      },
      conclusionOf: function (r) { return r.conclusion || r.findings || '暂无结论'; },
      loadCritical: function () {
        var vm = this;
        HIS.get('/api/medtech/critical-values/my-pending').then(function (list) {
          vm.criticalCount = (list || []).length;
        }).catch(function () { /* 静默 */ });
      },
      loadReports: function () {
        var vm = this;
        if (!vm.pid()) { vm.reports = []; return Promise.resolve([]); }
        vm.loading = true;
        var url = '/api/medtech/reports/patient/' + encodeURIComponent(vm.pid())
          + (vm.typeFilter ? '?reportType=' + encodeURIComponent(vm.typeFilter) : '');
        return HIS.get(url).then(function (rows) {
          vm.reports = asList(rows);
        }).catch(function () { vm.reports = []; }).finally(function () { vm.loading = false; });
      },
      onTabChange: function () { this.loadReports(); },
      refresh: function () { this.loadReports(); this.loadCritical(); },
      rowId: function (r) { return r.id != null ? String(r.id) : String(r.reportId); },
      toggleDetail: function (r) {
        var id = this.rowId(r);
        if (this.expandedId === id) { this.expandedId = null; this.detail = null; return; }
        this.expandedId = id;
        this.detail = null;
        this.loadDetail(id);
      },
      loadDetail: function (id) {
        var vm = this;
        vm.detailLoading = true;
        vm.pacs = null;
        HIS.get('/api/medtech/report/' + encodeURIComponent(id)).then(function (d) {
          vm.detail = d || null;
          var rt = d ? (d.reportType || d.report_type) : '';
          if (rt === 'exam') { vm.loadPacs(id); }
        }).catch(function () { vm.detail = null; }).finally(function () { vm.detailLoading = false; });
      },
      /* 载入某报告的外部影像查看器挂接信息(mock 内嵌路由 / dicomweb 外部 URL) */
      loadPacs: function (id) {
        var vm = this;
        vm.pacsLoading = true;
        HIS.get('/api/pacs/report/' + encodeURIComponent(id) + '/viewer').then(function (d) {
          vm.pacs = d || null;
        }).catch(function () { vm.pacs = null; }).finally(function () { vm.pacsLoading = false; });
      },
      openPacs: function () { this.pacsVisible = true; },
      openPacsWindow: function () {
        if (this.pacs && this.pacs.viewerUrl && Number(this.pacs.mock) !== 1) {
          window.open(this.pacs.viewerUrl, '_blank');
        }
      },
      resultItems: function () {
        var d = this.detail || {};
        return d.resultItems || d.result_items || [];
      },
      /* 影像资料(PACS 内嵌查看, 纯前端): 解析 detail.keyImages(可能为 JSON 字符串/数组, 元素可为 url 字符串或 {url,desc} 对象), 逗号分隔兜底 */
      keyImages: function () {
        var d = this.detail || {};
        var raw = d.keyImages != null ? d.keyImages : d.key_images;
        if (raw == null || raw === '') { return []; }
        var arr = null;
        if (Array.isArray(raw)) { arr = raw; }
        else if (typeof raw === 'string') {
          var s = raw.trim();
          if (!s) { return []; }
          if (s.charAt(0) === '[' || s.charAt(0) === '{') {
            try { arr = JSON.parse(s); } catch (e) { arr = null; }
          }
          if (!arr) { arr = s.split(',').map(function (x) { return x.trim(); }).filter(Boolean); }
        }
        if (!Array.isArray(arr)) { return []; }
        var out = [];
        arr.forEach(function (it) {
          if (!it) { return; }
          if (typeof it === 'string') { if (it.trim()) { out.push({ url: it.trim(), desc: '' }); } }
          else if (typeof it === 'object') {
            var u = it.url || it.image || it.path || it.src;
            if (u) { out.push({ url: String(u), desc: it.desc || it.description || it.name || '' }); }
          }
        });
        return out;
      },
      /* 趋势图: 同患者同项目历史序列, ECharts 折线 + 参考范围背景带(沿用门诊实现) */
      openTrend: function (item) {
        var vm = this;
        var code = item.item_code || item.itemCode;
        if (!vm.pid() || !code) { ElementPlus.ElMessage.warning('该项目缺少编码, 无法查看趋势'); return; }
        HIS.get('/api/medtech/reports/trend?patientId=' + encodeURIComponent(vm.pid()) + '&itemCode=' + encodeURIComponent(code))
          .then(function (rows) {
            var list = asList(rows);
            vm.trendTitle = (item.item_name || item.itemName || '') + ' · 历史趋势';
            vm.trendVisible = true;
            vm.$nextTick(function () { vm.renderTrend(list); });
          }).catch(function (e) { HIS.notifyError(e); });
      },
      renderTrend: function (list) {
        var el = this.$refs.trendChart;
        if (!el || !window.echarts) { return; }
        var chart = window.echarts.getInstanceByDom(el) || window.echarts.init(el, 'yb');
        var nums = (list || []).map(function (r) { return Number(r.resultValue); });
        var valid = nums.filter(function (n) { return isFinite(n); });
        this.trendEmpty = !valid.length;
        var x = (list || []).map(function (r) { return r.reportTime || ''; });
        var series = [{
          name: '结果值', type: 'line', smooth: true, symbolSize: 7,
          lineStyle: { width: 2 },
          data: (list || []).map(function (r, i) { return isFinite(nums[i]) ? nums[i] : null; }),
          connectNulls: true
        }];
        var refLow = null, refHigh = null;
        (list || []).some(function (r) {
          var l = Number(r.refLow), h = Number(r.refHigh);
          if (isFinite(l) || isFinite(h)) { refLow = isFinite(l) ? l : null; refHigh = isFinite(h) ? h : null; return true; }
          return false;
        });
        if ((refLow != null || refHigh != null) && valid.length) {
          series[0].markArea = {
            silent: true, itemStyle: { color: 'rgba(103,194,58,0.12)' },
            data: [[{ yAxisStart: refLow != null ? refLow : Math.min.apply(null, valid) }, { yAxisEnd: refHigh != null ? refHigh : Math.max.apply(null, valid) }]]
          };
        }
        chart.setOption({
          title: { text: this.trendTitle, left: 'center', textStyle: { fontSize: 13 } },
          tooltip: { trigger: 'axis' },
          grid: { left: 48, right: 24, top: 42, bottom: 42 },
          xAxis: { type: 'category', data: x, axisLabel: { fontSize: 10, rotate: 30 } },
          yAxis: { type: 'value', name: (list[0] && list[0].resultUnit) || '', scale: true },
          series: series
        }, true);
      },
      closeTrend: function () {
        this.trendVisible = false;
        var el = this.$refs.trendChart;
        if (el && window.echarts) { var c = window.echarts.getInstanceByDom(el); if (c) { c.dispose(); } }
      }
    },
    mounted: function () { this.loadReports(); this.loadCritical(); },
    template: `
      <div class="irp-panel">
        <style>
          .irp-panel{padding:14px 16px;height:100%;box-sizing:border-box;overflow:auto;background:var(--yb-surface-2)}
          .irp-panel .irp-bar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:12px}
          .irp-panel .irp-seg{display:inline-flex;border:1px solid var(--yb-border);border-radius:var(--yb-r-sm);overflow:hidden}
          .irp-panel .irp-seg button{padding:4px 14px;border:none;border-right:1px solid var(--yb-border);background:var(--yb-surface);color:var(--yb-ink-3);font-size:var(--yb-fs-sm);cursor:pointer}
          .irp-panel .irp-seg button:last-child{border-right:none}
          .irp-panel .irp-seg button.is-on{background:var(--yb-brand);color:#fff;font-weight:600}
          .irp-panel .irp-crit{display:inline-flex;align-items:center;gap:6px;margin-left:auto;padding:3px 12px;border-radius:var(--yb-r-pill);background:var(--yb-danger-bg);border:1px solid var(--yb-danger-border);color:var(--yb-danger);font-size:var(--yb-fs-sm);font-weight:600}
          .irp-panel .irp-crit .n{font-size:16px}
          .irp-panel .irp-meta{color:var(--yb-ink-3);font-size:var(--yb-fs-sm)}
          .irp-panel .irp-list{display:flex;flex-direction:column;gap:8px}
          .irp-panel .irp-card{border:1px solid var(--yb-border);border-radius:var(--yb-r-md);background:var(--yb-surface);cursor:pointer;overflow:hidden}
          .irp-panel .irp-card.is-open{border-color:var(--yb-brand)}
          .irp-panel .irp-card.is-revoked{opacity:.72;background:repeating-linear-gradient(45deg,var(--yb-surface),var(--yb-surface) 10px,var(--yb-surface-2) 10px,var(--yb-surface-2) 20px)}
          .irp-panel .irp-card.is-revoked .irp-name,.irp-panel .irp-card.is-revoked .irp-concl{color:var(--yb-ink-4)}
          .irp-panel .irp-head{display:flex;align-items:center;gap:10px;padding:10px 12px}
          .irp-panel .irp-head:hover{background:var(--yb-surface-3)}
          .irp-panel .irp-type{display:inline-flex;align-items:center;justify-content:center;min-width:44px;height:24px;border-radius:var(--yb-r-sm);background:var(--yb-brand);color:#fff;font-size:var(--yb-fs-sm)}
          .irp-panel .irp-type.lab{background:var(--yb-success)}
          .irp-panel .irp-type.pathology{background:var(--yb-info)}
          .irp-panel .irp-name{font-weight:600;color:var(--yb-ink-1)}
          .irp-panel .irp-concl{color:var(--yb-ink-3);font-size:var(--yb-fs-sm);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:52%}
          .irp-panel .irp-time{color:var(--yb-ink-4);font-size:var(--yb-fs-sm);margin-left:auto;white-space:nowrap}
          .irp-panel .irp-detail{padding:10px 12px 12px;border-top:1px dashed var(--yb-border)}
          .irp-panel .irp-sec{margin:6px 0;font-size:var(--yb-fs-base);color:var(--yb-ink-2);white-space:pre-wrap}
          .irp-panel .irp-empty{padding:36px;text-align:center;color:var(--yb-ink-4);font-size:var(--yb-fs-base)}
          .irp-panel .irp-crit-val{color:var(--yb-danger);font-weight:600}
          .irp-panel .irp-imgs{margin-top:10px;padding-top:10px;border-top:1px dashed var(--yb-border)}
          .irp-panel .irp-imgs-title{font-size:var(--yb-fs-sm);color:var(--yb-ink-2);margin-bottom:8px}
          .irp-panel .irp-imgs-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:10px}
          .irp-panel .irp-img-cell{border:1px solid var(--yb-border);border-radius:var(--yb-r-sm);overflow:hidden;background:var(--yb-surface-2)}
          .irp-panel .irp-img-cell .el-image{width:100%;height:110px;display:block;cursor:zoom-in}
          .irp-panel .irp-img-desc{padding:4px 6px;font-size:12px;color:var(--yb-ink-3);line-height:1.3;white-space:normal}
          .irp-panel .irp-img-err{width:100%;height:110px;display:flex;align-items:center;justify-content:center;color:var(--yb-ink-4);font-size:12px;background:var(--yb-surface-3)}
          .irp-panel .irp-imgs-none{padding:10px;color:var(--yb-ink-4);font-size:var(--yb-fs-sm)}
          .irp-panel .irp-pacs{margin-top:10px;padding-top:10px;border-top:1px dashed var(--yb-border)}
          .irp-panel .irp-pacs-title{display:flex;align-items:center;gap:8px;font-size:var(--yb-fs-sm);color:var(--yb-ink-2);margin-bottom:6px}
          .irp-panel .irp-pacs-body{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
          .irp-panel .irp-pacs-meta{color:var(--yb-ink-3);font-size:var(--yb-fs-sm)}
          .irp-panel .irp-pacs-note{font-size:var(--yb-fs-sm);color:var(--yb-warning);background:var(--yb-warning-bg);border:1px solid var(--yb-warning-border);border-radius:var(--yb-r-sm);padding:8px 10px;margin-bottom:10px;line-height:1.5}
        </style>
        <div class="irp-bar">
          <div class="irp-seg">
            <button v-for="t in tabs" :key="t.key" :class="{'is-on': typeFilter===t.key}" @click="typeFilter=t.key; onTabChange()">{{ t.label }}</button>
          </div>
          <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
          <span class="irp-meta">患者ID {{ pid() || '-' }} · 共 {{ filteredReports.length }} 份</span>
          <span class="irp-crit" title="本人待处理危急值(my-pending)"><span class="n">{{ criticalCount }}</span> 危急值待办</span>
        </div>
        <div v-loading="loading" class="irp-list">
          <div v-if="!filteredReports.length && !loading" class="irp-empty">该患者暂无已报告/已审核的检查检验报告</div>
          <div v-for="r in filteredReports" :key="rowId(r)" class="irp-card" :class="{'is-open': expandedId===rowId(r), 'is-revoked': isRevoked(r)}">
            <div class="irp-head" @click="toggleDetail(r)">
              <span class="irp-type" :class="((r.report_type||r.reportType)||'')">{{ typeText(r.report_type||r.reportType) }}</span>
              <span class="irp-name">{{ r.report_no || r.reportNo }}</span>
              <el-tag v-if="isCritical(r)" type="danger" size="small" disable-transitions>危急值</el-tag>
              <el-tag v-if="isRevoked(r)" type="warning" size="small" disable-transitions :title="revokeTip(r)">已撤回</el-tag>
              <span class="irp-concl">{{ conclusionOf(r) }}</span>
              <span class="irp-time">{{ r.report_time || r.reportTime || '' }}</span>
            </div>
            <div v-if="expandedId===rowId(r)" class="irp-detail" v-loading="detailLoading">
              <div class="irp-sec" v-if="detail && detail.findings"><b>所见：</b>{{ detail.findings }}</div>
              <div class="irp-sec" v-if="detail && detail.conclusion"><b>结论：</b>{{ detail.conclusion }}</div>
              <el-table v-if="resultItems().length" :data="resultItems()" border size="small" max-height="240">
                <el-table-column prop="item_name" label="项目" min-width="130"></el-table-column>
                <el-table-column label="结果" width="120"><template #default="s"><b :class="{'irp-crit-val': Number(s.row.abnormal_flag)>=3}">{{ s.row.result_value }}</b> {{ s.row.abnormal_flag ? abnormalText(s.row.abnormal_flag) : '' }}</template></el-table-column>
                <el-table-column prop="result_unit" label="单位" width="80"></el-table-column>
                <el-table-column label="参考范围" width="140"><template #default="s">{{ s.row.ref_range_low }} ~ {{ s.row.ref_range_high }}</template></el-table-column>
                <el-table-column label="操作" width="80" align="center"><template #default="s"><el-button link type="primary" size="small" @click.stop="openTrend(s.row)">趋势</el-button></template></el-table-column>
              </el-table>
              <div v-else-if="detail && !resultItems().length" class="irp-empty" style="padding:14px">无结果明细子项</div>
              <div class="irp-imgs" v-if="detail">
                <div class="irp-imgs-title"><b>影像资料</b></div>
                <div v-if="keyImages().length" class="irp-imgs-grid">
                  <div v-for="(im, i) in keyImages()" :key="i" class="irp-img-cell">
                    <el-image :src="im.url" :preview-src-list="keyImages().map(function(x){return x.url;})" :initial-index="i" fit="cover" lazy preview-teleport>
                      <template #error><div class="irp-img-err">图片加载失败</div></template>
                    </el-image>
                    <div v-if="im.desc" class="irp-img-desc">{{ im.desc }}</div>
                  </div>
                </div>
                <div v-else class="irp-imgs-none">该报告未挂影像资料</div>
              </div>
              <div class="irp-pacs" v-if="detail && ((r.report_type||r.reportType)==='exam')">
                <div class="irp-pacs-title"><b>外部影像 (PACS)</b><el-tag size="mini" type="info" disable-transitions>接入骨架/Mock</el-tag></div>
                <div v-if="pacsLoading" class="irp-pacs-meta">加载中…</div>
                <div v-else-if="pacs && Number(pacs.enabled)===1" class="irp-pacs-body">
                  <span class="irp-pacs-meta">模式：{{ pacs.mode }}{{ pacs.studyUid ? (' · Study：' + pacs.studyUid) : '' }}</span>
                  <el-button size="mini" type="primary" link @click="openPacs">查看外部影像</el-button>
                </div>
                <div v-else class="irp-pacs-meta">未启用 PACS 集成(可在系统参数 pacs.enabled 配置)</div>
              </div>
            </div>
          </div>
        </div>
        <el-dialog v-model="trendVisible" :title="trendTitle" width="640px" append-to-body @close="closeTrend">
          <div v-if="trendEmpty" class="irp-empty">该项目历史结果均为非数值, 无法绘制趋势</div>
          <div v-show="!trendEmpty" ref="trendChart" style="width:100%;height:320px"></div>
          <template #footer><el-button size="small" @click="trendVisible=false">关闭</el-button></template>
        </el-dialog>
        <el-dialog v-model="pacsVisible" title="外部影像查看器 (PACS 集成·骨架)" width="560px" append-to-body>
          <div class="irp-pacs-note">当前为集成抽象层 / Mock 演示, 未真实对接院外厂商 PACS。真实对接时在系统参数配置 pacs.mode=dicomweb 与 pacs.base_url, 由影像设备/网关回填报告 pacs_study_uid 后, 此处将跳转至外部 DICOMweb 查看器。</div>
          <el-descriptions v-if="pacs" :column="1" border size="small">
            <el-descriptions-item label="集成模式">{{ pacs.mode }}{{ Number(pacs.mock)===1 ? ' (Mock)' : '' }}</el-descriptions-item>
            <el-descriptions-item label="Study UID">{{ pacs.studyUid || '-' }}</el-descriptions-item>
            <el-descriptions-item label="查看器 URL">{{ pacs.viewerUrl || '-' }}</el-descriptions-item>
          </el-descriptions>
          <template #footer>
            <el-button size="small" @click="pacsVisible=false">关闭</el-button>
            <el-button v-if="pacs && pacs.viewerUrl && Number(pacs.mock)!==1" size="small" type="primary" @click="openPacsWindow">新窗口打开</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpReportPanel = InpReportPanel;
})();
