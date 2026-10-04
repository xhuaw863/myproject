/* 医生工作站 - 报告与趋势 Tab(OP-D 14.5): 历史检查/检验报告 + 结果明细 + ECharts 趋势 + 引用病历/打印 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 样式须 createElement 注入: 模板字符串里的 <style> 经 innerHTML 插入不会应用(与住院版同根因); 另统一分段/刷新按钮高度(theme.css 把 el-button--small 压到28px 而 EP radio 默认24px) */
  (function ensureStyles() {
    if (document.getElementById('dw-report-css')) { return; }
    var st = document.createElement('style');
    st.id = 'dw-report-css';
    st.textContent = [
      '.dw-report-panel { padding:4px 2px; }',
      '.dw-report-panel .rp-toolbar { display:flex; align-items:center; gap:10px; margin-bottom:10px; padding:8px 12px; background:var(--dw-card,#fff); border:1px solid var(--dw-border,#dfe4eb); border-radius:6px; }',
      '.dw-report-panel .rp-toolbar .el-radio-button__inner { height:28px; line-height:28px; padding-top:0; padding-bottom:0; }',
      '.dw-report-panel .rp-meta { color:var(--dw-text-hint,#8a94a6); font-size:12px; }',
      '.dw-report-panel .rp-meta b { color:var(--dw-text,#1c2430); font-variant-numeric:tabular-nums; }',
      '.dw-report-panel .rp-refresh { margin-left:auto; }',
      '.dw-report-panel .rp-list { display:flex; flex-direction:column; gap:8px; min-height:160px; }',
      '.dw-report-panel .rp-card { border:1px solid var(--dw-border,#dfe4eb); border-radius:4px; background:var(--dw-card,#fff); cursor:pointer; overflow:hidden; transition:border-color .15s ease; }',
      '.dw-report-panel .rp-card.is-open { border-color:var(--dw-primary,#2b6cb0); }',
      '.dw-report-panel .rp-card-head { display:flex; align-items:center; gap:10px; padding:9px 12px; }',
      '.dw-report-panel .rp-card-head:hover { background:var(--yb-surface-3,#f3f6fa); }',
      '.dw-report-panel .rp-type { display:inline-flex; align-items:center; justify-content:center; min-width:44px; height:24px; padding:0 8px; border-radius:3px; background:var(--dw-primary,#2b6cb0); color:#fff; font-size:12px; flex:none; }',
      '.dw-report-panel .rp-type.lab { background:var(--dw-success,#2f9e44); }',
      '.dw-report-panel .rp-name { font-weight:600; color:var(--dw-text,#1c2430); font-variant-numeric:tabular-nums; }',
      '.dw-report-panel .rp-meta-time { color:var(--dw-text-hint,#8a94a6); font-size:12px; margin-left:auto; white-space:nowrap; font-variant-numeric:tabular-nums; }',
      '.dw-report-panel .rp-concl { color:var(--dw-text-secondary,#4a5568); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:60%; }',
      '.dw-report-panel .rp-detail { padding:6px 12px 10px; border-top:1px dashed var(--dw-border,#dfe4eb); }',
      '.dw-report-panel .rp-detail .sec { margin:6px 0; font-size:13px; color:var(--dw-text,#1c2430); white-space:pre-wrap; }',
      '.dw-report-panel .rp-actions { display:flex; gap:8px; margin-top:8px; }',
      '.dw-report-panel .rp-empty { padding:30px; text-align:center; color:var(--dw-text-hint,#8a94a6); }',
      '.dw-report-panel .dw-critical-text { color:var(--dw-danger,#f56c6c); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  function asList(v) { return Array.isArray(v) ? v : ((v && v.records) || []); }
  function raw(v) { return v && Object.prototype.hasOwnProperty.call(v, 'value') ? v.value : v; }

  /* 异常标志文案(与 his_exam_result_item.abnormal_flag 对齐: 0正常 1偏高 2偏低 3危急高 4危急低) */
  var ABNORMAL_LABELS = { 0: '', 1: '↑偏高', 2: '↓偏低', 3: '↑危急', 4: '↓危急' };
  var ABNORMAL_TONES = { 1: 'warning', 2: 'warning', 3: 'danger', 4: 'danger' };

  var DwReportPanel = {
    name: 'DwReportPanel',
    inject: ['currentVisit', 'currentPatient'],
    emits: ['insert-to-record'],
    data: function () {
      return {
        reports: [],
        loading: false,
        typeFilter: 'all',
        expandedId: null,
        trendVisible: false,
        trendTitle: '',
        trendEmpty: false
      };
    },
    computed: {
      visit: function () { return raw(this.currentVisit) || null; },
      patient: function () { return raw(this.currentPatient) || {}; },
      patientId: function () {
        var v = this.visit || {};
        return this.patient.id || v.patientId || null;
      },
      filteredReports: function () {
        var list = this.reports || [];
        if (!this.typeFilter || this.typeFilter === 'all') { return list; }
        return list.filter(function (r) { return String(r.report_type || r.reportType || '') === this.typeFilter; }, this);
      }
    },
    watch: {
      patientId: function (id) {
        this.expandedId = null;
        if (id) { this.loadReports(); } else { this.reports = []; }
      }
    },
    methods: {
      typeText: function (t) { return t === 'lab' ? '检验' : (t === 'exam' ? '检查' : (t || '报告')); },
      abnormalText: function (f) { return ABNORMAL_LABELS[Number(f)] || ''; },
      abnormalTone: function (f) { return ABNORMAL_TONES[Number(f)] || 'info'; },
      loadReports: function () {
        var vm = this;
        if (!vm.patientId) { vm.reports = []; return Promise.resolve([]); }
        vm.loading = true;
        var url = '/api/medtech/reports/patient/' + encodeURIComponent(vm.patientId)
          + ((vm.typeFilter && vm.typeFilter !== 'all') ? '?reportType=' + encodeURIComponent(vm.typeFilter) : '');
        return HIS.get(url).then(function (rows) {
          vm.reports = asList(rows);
          return vm.reports;
        }).catch(function () { vm.reports = []; }).finally(function () { vm.loading = false; });
      },
      onFilterChange: function () { this.expandedId = null; this.loadReports(); },
      toggleDetail: function (report) {
        var id = report.id || report.reportId;
        this.expandedId = this.expandedId === id ? null : id;
      },
      resultItems: function (report) { return report.resultItems || []; },
      isLab: function (report) { return String(report.report_type || report.reportType || '') === 'lab'; },
      conclusionOf: function (report) { return report.conclusion || report.findings || '暂无结论'; },
      /* 单条结论引用到病历「辅助检查」(经 doctor.js 中继 emr-panel.appendAuxExam) */
      quoteToRecord: function (report) {
        var name = report.itemName || report.report_no || report.reportNo || '报告';
        var date = String(report.report_time || report.reportTime || '').slice(0, 10);
        var text = '【' + this.typeText(report.report_type || report.reportType) + '】' + name + '：' + this.conclusionOf(report) + (date ? '（' + date + '）' : '');
        this.$emit('insert-to-record', { text: text, target: 'auxExam' });
      },
      /* 明细行整体引用(检验报告: 逐结论拼接) */
      quoteAllToRecord: function (report) {
        var items = this.resultItems(report);
        var text;
        if (items.length) {
          var name = report.report_no || report.reportNo || '检验报告';
          text = '【检验】' + name + '：' + items.map(function (i) {
            return i.item_name + ' ' + (i.result_value || '') + (i.result_unit || '') + (ABNORMAL_LABELS[Number(i.abnormal_flag)] ? '(' + ABNORMAL_LABELS[Number(i.abnormal_flag)] + ')' : '');
          }).join('；');
          text += '（' + String(report.report_time || '').slice(0, 10) + '）';
        } else {
          text = '【' + this.typeText(report.report_type) + '】' + (report.report_no || '') + '：' + this.conclusionOf(report);
        }
        this.$emit('insert-to-record', { text: text, target: 'auxExam' });
      },
      /* 趋势图(14.5): 同患者同项目时间序列, ECharts 折线 + 参考范围背景带 */
      openTrend: function (report, item) {
        var vm = this;
        var code = item.item_code || item.itemCode;
        if (!vm.patientId || !code) { ElementPlus.ElMessage.warning('该项目缺少编码, 无法查看趋势'); return; }
        HIS.get('/api/medtech/reports/trend?patientId=' + encodeURIComponent(vm.patientId) + '&itemCode=' + encodeURIComponent(code))
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
        var chart = window.echarts.getInstanceByDom(el) || window.echarts.init(el);
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
      /* 报告打印: 本地组版走统一打印窗 */
      printReport: function (report) {
        var vm = this;
        var patient = vm.patient || {};
        var p = function (v) { return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); };
        var items = vm.resultItems(report);
        var rowsHtml = items.length ? '<table><thead><tr><th>项目</th><th>结果</th><th>单位</th><th>参考范围</th><th>异常</th></tr></thead><tbody>'
          + items.map(function (i) {
            return '<tr><td>' + p(i.item_name) + '</td><td><b>' + p(i.result_value) + '</b></td><td>' + p(i.result_unit) + '</td><td>' + p(i.ref_range_low) + ' ~ ' + p(i.ref_range_high) + '</td><td>' + p(ABNORMAL_LABELS[Number(i.abnormal_flag)]) + '</td></tr>';
          }).join('') + '</tbody></table>' : '';
        var html = '<div class="print-sheet">' +
          '<div class="print-header">' + p(HIS.getHospitalName ? HIS.getHospitalName() : '') + '</div>' +
          '<div class="print-subheader">' + p(vm.typeText(report.report_type)) + '报告单</div>' +
          '<table class="print-meta"><tr><td>姓名：' + p(patient.name || patient.patientName || report.patientName) + '</td><td>性别：' + p(patient.gender_name || patient.genderName || '') + '</td><td>年龄：' + p(patient.age || '') + '</td></tr>' +
          '<tr><td>报告编号：' + p(report.report_no) + '</td><td>报告时间：' + p(report.report_time) + '</td><td>类型：' + p(vm.typeText(report.report_type)) + '</td></tr></table>' +
          '<div class="print-section"><strong>检查/检验所见</strong><div class="print-content" style="min-height:60px">' + String(p(report.findings)).replace(/\r?\n/g, '<br>') + '</div></div>' +
          '<div class="print-section"><strong>结论</strong><div class="print-content" style="min-height:60px">' + String(p(report.conclusion)).replace(/\r?\n/g, '<br>') + '</div></div>' +
          (rowsHtml ? '<div class="print-section"><strong>结果明细</strong>' + rowsHtml + '</div>' : '') +
          '<div class="print-footer"><span>报告医师：' + p(report.report_doctor_name || report.reportDoctorName || '________') + '</span><span>审核医师：' + p(report.review_doctor_name || report.reviewDoctorName || '________') + '</span></div></div>';
        HIS.openPrintWindow('报告单', html);
      }
    },
    template: `
      <div class="dw-report-panel">
        <div class="rp-toolbar">
          <el-radio-group v-model="typeFilter" size="small" @change="onFilterChange">
            <el-radio-button label="all">全部</el-radio-button>
            <el-radio-button label="exam">检查</el-radio-button>
            <el-radio-button label="lab">检验</el-radio-button>
          </el-radio-group>
          <span class="rp-meta">共 <b>{{ filteredReports.length }}</b> 份报告</span>
          <el-button class="rp-refresh" size="small" :loading="loading" @click="loadReports">刷新</el-button>
        </div>
        <div v-loading="loading" class="rp-list">
          <el-empty v-if="!filteredReports.length && !loading" description="该患者暂无已报告/已审核的检查检验报告" :image-size="72"></el-empty>
          <div v-for="r in filteredReports" :key="r.id" class="rp-card" :class="{'is-open': expandedId===(r.id||r.reportId)}">
            <div class="rp-card-head" @click="toggleDetail(r)">
              <span class="rp-type" :class="{lab: (r.report_type||r.reportType)==='lab'}">{{ typeText(r.report_type||r.reportType) }}</span>
              <span class="rp-name">{{ r.report_no || r.reportNo }}</span>
              <el-tag v-if="Number(r.critical_flag||r.criticalFlag)===1" type="danger" size="small">危急值</el-tag>
              <span class="rp-concl">{{ conclusionOf(r) }}</span>
              <span class="rp-meta-time">{{ r.report_time || r.reportTime || '' }}</span>
            </div>
            <div v-if="expandedId===(r.id||r.reportId)" class="rp-detail">
              <div class="sec" v-if="r.findings"><b>所见：</b>{{ r.findings }}</div>
              <div class="sec" v-if="r.conclusion"><b>结论：</b>{{ r.conclusion }}</div>
              <el-table v-if="resultItems(r).length" :data="resultItems(r)" border size="small" max-height="220">
                <el-table-column prop="item_name" label="项目" min-width="120"></el-table-column>
                <el-table-column prop="result_value" label="结果" width="90"><template #default="s"><b :class="{'dw-critical-text': Number(s.row.abnormal_flag)>=3}">{{ s.row.result_value }}</b> {{ s.row.abnormal_flag ? abnormalText(s.row.abnormal_flag) : '' }}</template></el-table-column>
                <el-table-column prop="result_unit" label="单位" width="70"></el-table-column>
                <el-table-column label="参考范围" width="120"><template #default="s">{{ s.row.ref_range_low }} ~ {{ s.row.ref_range_high }}</template></el-table-column>
                <el-table-column label="操作" width="80" align="center"><template #default="s"><el-button link type="primary" size="small" @click.stop="openTrend(r, s.row)">趋势</el-button></template></el-table-column>
              </el-table>
              <div class="rp-actions">
                <el-button size="small" type="primary" plain @click.stop="quoteAllToRecord(r)">引用到病历</el-button>
                <el-button size="small" @click.stop="printReport(r)">打印报告单</el-button>
              </div>
            </div>
          </div>
        </div>
        <el-dialog v-model="trendVisible" :title="trendTitle" width="640px" append-to-body>
          <div v-if="trendEmpty" class="rp-empty">该项目历史结果均为非数值, 无法绘制趋势</div>
          <div v-show="!trendEmpty" ref="trendChart" style="width:100%;height:320px"></div>
          <template #footer><el-button size="small" @click="trendVisible=false">关闭</el-button></template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.DwReportPanel = DwReportPanel;
})();
