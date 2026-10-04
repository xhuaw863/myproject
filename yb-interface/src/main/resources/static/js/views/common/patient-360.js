/* 患者360°速览抽屉(三站共用: 门诊医生站/住院医生站/病区护士站): 不打断工作台的轻量患者全景。
 * 注册: HIS.components.Patient360Drawer; 依赖后端契约与「患者全景时间线」页同源(EmrTimelineController):
 *   GET /api/his/emr/timeline/patient-overview/{patientId}   概览(就诊统计/末次就诊/活动诊断/在院状态)
 *   GET /api/his/emr/timeline/patient/{patientId}            门诊+住院就诊事件时间线(倒序)
 *   GET /api/his/emr/timeline/visit-summary/{visitId}?scope= 单次浏览摘要(1住院 2门诊, 行内展开)
 *   GET /api/medtech/reports/patient/{patientId}             医技报告(仅展示已发布 status=2)
 * 用法: <patient-360-drawer v-model="visible" :patient-id="pid" :patient="pobj"></patient-360-drawer>
 * 定位: 只读速览; 需要完整筛选/详情对话框时经底部按钮跳转 emr-patient-timeline 全页。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  function text(v) { return v == null ? '' : String(v); }
  function dash(v) { return (v == null || v === '') ? '-' : v; }

  /* 组件私有样式(挂 --yb-* 令牌, 不动全局 his.css, 模式同 emr-patient-timeline) */
  (function injectCss() {
    var id = 'patient-360-drawer-css';
    var st = document.getElementById(id);
    if (!st) { st = document.createElement('style'); st.id = id; document.head.appendChild(st); }
    st.textContent = [
      '.p360-hd { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }',
      '.p360-hd-name { font-size: 16px; font-weight: 700; color: var(--yb-ink-1,#1c2430); }',
      '.p360-hd-meta { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); }',
      '.p360-body { padding: 0 4px; }',
      /* 概览 */
      '.p360-stat { display: flex; margin: 4px 0 12px; }',
      '.p360-stat > div { flex: 1; text-align: center; border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); background: var(--yb-surface,#fff); padding: 8px 4px; margin-right: 8px; }',
      '.p360-stat > div:last-child { margin-right: 0; }',
      '.p360-stat b { display: block; font-size: 20px; font-weight: 600; color: var(--yb-brand,#1a5c9e); font-variant-numeric: tabular-nums; line-height: 1.3; }',
      '.p360-stat span { font-size: 11px; color: var(--yb-ink-3,#5a6a7e); }',
      '.p360-stat .inp b { color: var(--yb-success,#3c862d); }',
      '.p360-sec { margin-bottom: 12px; }',
      '.p360-sec-t { font-size: 12px; font-weight: 600; color: var(--yb-ink-2,#3d4a5c); margin-bottom: 6px; }',
      '.p360-sec-empty { font-size: 12px; color: var(--yb-ink-4,#8994a5); }',
      '.p360-line { font-size: 12px; color: var(--yb-ink-2,#3d4a5c); line-height: 1.8; }',
      '.p360-line .k { color: var(--yb-ink-4,#8994a5); margin-right: 6px; }',
      '.p360-diags { display: flex; flex-wrap: wrap; gap: 6px; }',
      '.p360-allergy { padding: 6px 10px; border-radius: var(--yb-r-sm,4px); font-size: 12px; font-weight: 600; color: var(--yb-danger,#c2363b); background: var(--yb-danger-light,#fdecec); }',
      /* 时间线 */
      '.p360-tl-item { border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); padding: 8px 10px; margin-bottom: 8px; background: var(--yb-surface,#fff); }',
      '.p360-tl-hd { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }',
      '.p360-tl-hd .dept { font-weight: 600; font-size: 13px; color: var(--yb-ink-1,#1c2430); }',
      '.p360-tl-hd .time { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); font-variant-numeric: tabular-nums; }',
      '.p360-tl-hd .grow { flex: 1; }',
      '.p360-tl-hd .caret { color: var(--yb-ink-4,#8994a5); font-size: 11px; transition: transform .15s; }',
      '.p360-tl-item.is-open .caret { transform: rotate(90deg); }',
      '.p360-tl-brief { margin-top: 4px; font-size: 12px; color: var(--yb-ink-3,#5a6a7e); line-height: 1.6; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }',
      '.p360-tl-sum { margin-top: 8px; border-top: 1px dashed var(--yb-divider,#f0f3f7); padding-top: 6px; }',
      '.p360-tl-sum .row { display: flex; gap: 8px; font-size: 12px; line-height: 1.7; padding: 2px 0; }',
      '.p360-tl-sum .row label { flex: none; width: 60px; color: var(--yb-ink-4,#8994a5); }',
      '.p360-tl-sum .row div { flex: 1; min-width: 0; color: var(--yb-ink-1,#1c2430); white-space: pre-wrap; word-break: break-all; }',
      /* 报告 */
      '.p360-rp { border: 1px solid var(--yb-border,#dfe4eb); border-radius: var(--yb-r-md,8px); padding: 8px 10px; margin-bottom: 8px; background: var(--yb-surface,#fff); }',
      '.p360-rp-hd { display: flex; align-items: center; gap: 8px; }',
      '.p360-rp-hd b { font-size: 13px; color: var(--yb-ink-1,#1c2430); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      '.p360-rp-hd .time { font-size: 12px; color: var(--yb-ink-3,#5a6a7e); font-variant-numeric: tabular-nums; }',
      '.p360-rp-c { margin-top: 4px; font-size: 12px; color: var(--yb-ink-2,#3d4a5c); line-height: 1.6; }',
      '.p360-foot { display: flex; align-items: center; gap: 8px; }',
      '.p360-empty { padding: 28px 0; text-align: center; color: var(--yb-ink-4,#8994a5); font-size: 12px; line-height: 1.8; }'
    ].join('\n');
  })();

  var TYPE_LABEL = { outpatient: '门诊', inpatient: '住院' };

  var Patient360Drawer = {
    name: 'Patient360Drawer',
    props: {
      modelValue: { type: Boolean, default: false },
      patientId: { default: null },
      /* 宿主站点的患者展示信息: {name, gender, age, patientNo, allergyHistory...} */
      patient: { type: Object, default: function () { return {}; } }
    },
    emits: ['update:modelValue'],
    template: `
      <el-drawer :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
                 :size="560" :with-header="true" append-to-body class="p360-drawer">
        <template #header>
          <div class="p360-hd">
            <span class="p360-hd-name">{{ headName }}</span>
            <span class="p360-hd-meta">{{ headMeta }}</span>
          </div>
        </template>
        <div class="p360-body" v-if="patientId">
          <el-tabs v-model="activeTab">
            <el-tab-pane label="概览" name="overview">
              <div v-loading="overviewLoading" style="min-height:120px">
                <div class="p360-allergy" v-if="allergyText" style="margin-bottom:12px">⚠ 过敏史：{{ allergyText }}</div>
                <div class="p360-stat">
                  <div><b>{{ stat('totalVisits') }}</b><span>总就诊</span></div>
                  <div><b>{{ stat('outpatientCount') }}</b><span>门诊</span></div>
                  <div class="inp"><b>{{ stat('inpatientCount') }}</b><span>住院</span></div>
                </div>
                <div class="p360-sec">
                  <div class="p360-sec-t">末次就诊</div>
                  <template v-if="overview && overview.lastVisit">
                    <div class="p360-line"><span class="k">{{ typeLabel(overview.lastVisit.type) }}</span>{{ fmtDT(overview.lastVisit.time) }} · {{ dash(overview.lastVisit.deptName) }}<span v-if="overview.lastVisit.doctorName"> · {{ overview.lastVisit.doctorName }}</span></div>
                    <div class="p360-line" v-if="overview.lastVisit.summary" style="color:var(--yb-ink-3)">{{ overview.lastVisit.summary }}</div>
                  </template>
                  <div class="p360-sec-empty" v-else>暂无就诊记录</div>
                </div>
                <div class="p360-sec">
                  <div class="p360-sec-t">活动诊断 <span style="font-weight:normal;color:var(--yb-ink-4)">(末次就诊有效诊断)</span></div>
                  <div class="p360-diags" v-if="overview && (overview.activeDiagnoses || []).length">
                    <el-tag v-for="(d, i) in overview.activeDiagnoses" :key="i" size="small"
                            :type="d.isMain ? 'danger' : 'info'" :effect="d.isMain ? 'dark' : 'plain'">{{ d.diagName || '-' }}</el-tag>
                  </div>
                  <div class="p360-sec-empty" v-else>暂无有效诊断</div>
                </div>
                <div class="p360-sec">
                  <div class="p360-sec-t">当前状态</div>
                  <template v-if="overview && overview.currentInpatient">
                    <div class="p360-line"><el-tag size="small" type="success">在院中</el-tag>
                      <span style="margin-left:8px">{{ dash(overview.currentInpatient.deptName) }}<span v-if="overview.currentInpatient.doctorName"> · {{ overview.currentInpatient.doctorName }}</span></span></div>
                    <div class="p360-line" v-if="overview.currentInpatient.inDays != null" style="color:var(--yb-ink-3)">已住院 {{ overview.currentInpatient.inDays }} 天 · 押金余额 {{ fmtMoney(overview.currentInpatient.depositBalance) }}</div>
                  </template>
                  <div v-else><el-tag size="small" type="info" effect="plain">未在院</el-tag></div>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="就诊时间线" name="timeline">
              <div v-loading="tlLoading" style="min-height:120px">
                <template v-if="tlItems.length">
                  <div class="p360-tl-item" v-for="it in tlItems" :key="it.id" :class="{'is-open': openedKey === itemKey(it)}">
                    <div class="p360-tl-hd" @click="toggleItem(it)">
                      <el-tag size="small" :type="it.type === 'inpatient' ? 'success' : 'primary'">{{ typeLabel(it.type) }}</el-tag>
                      <span class="dept">{{ dash(it.deptName) }}</span>
                      <span class="time">{{ fmtDT(it.time) }}</span>
                      <span class="grow"></span>
                      <span class="caret">▶</span>
                    </div>
                    <div class="p360-tl-brief" v-if="openedKey !== itemKey(it)">{{ itemBrief(it) }}</div>
                    <div class="p360-tl-sum" v-else v-loading="sumLoading">
                      <template v-if="summary">
                        <div class="row" v-for="(r, i) in summaryRows" :key="i"><label>{{ r[0] }}</label><div>{{ r[1] }}</div></div>
                        <div class="row" v-if="!summaryRows.length"><label>摘要</label><div>本次就诊无可展示摘要内容</div></div>
                      </template>
                    </div>
                  </div>
                  <div class="p360-sec-empty" style="text-align:center;padding:6px 0">已展示最近 {{ tlItems.length }} 条就诊事件（服务端上限100条）</div>
                </template>
                <div class="p360-empty" v-else-if="!tlLoading">该患者暂无门诊/住院就诊事件</div>
              </div>
            </el-tab-pane>
            <el-tab-pane :label="'报告' + (reports.length ? ' ' + reports.length : '')" name="reports">
              <div v-loading="rpLoading" style="min-height:120px">
                <template v-if="reports.length">
                  <div class="p360-rp" v-for="(rep, i) in reports" :key="rep.id || i">
                    <div class="p360-rp-hd">
                      <el-tag size="small" effect="plain">{{ rep.reportType === 'lab' ? '检验' : '检查' }}</el-tag>
                      <b :title="rep.itemNames || rep.orderName || '未命名报告'">{{ rep.itemNames || rep.orderName || '未命名报告' }}</b>
                      <span class="time">{{ fmtDT(rep.reportTime || rep.auditTime) }}</span>
                    </div>
                    <div class="p360-rp-c">{{ repConclusion(rep) || '暂无结论' }}</div>
                  </div>
                </template>
                <div class="p360-empty" v-else-if="!rpLoading">暂无已发布报告</div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
        <div class="p360-empty" v-else>未选择患者</div>
        <template #footer>
          <div class="p360-foot">
            <span style="font-size:11px;color:var(--yb-ink-4)">数据为只读速览, 最多展示最近就诊事件</span>
            <el-button size="small" style="margin-left:auto" @click="openFullTimeline">打开完整时间线</el-button>
            <el-button size="small" type="primary" @click="$emit('update:modelValue', false)">关闭</el-button>
          </div>
        </template>
      </el-drawer>
    `,
    data: function () {
      return {
        activeTab: 'overview',
        overview: null, overviewLoading: false, overviewLoadedFor: null,
        tlItems: [], tlLoading: false, tlLoadedFor: null,
        openedKey: '', summary: null, sumLoading: false,
        reports: [], rpLoading: false, rpLoadedFor: null
      };
    },
    computed: {
      pid: function () { return this.patientId != null ? HIS.id(this.patientId) : null; },
      headName: function () { return (this.patient && this.patient.name) || (this.overview && this.overview.patient && this.overview.patient.name) || '患者'; },
      headMeta: function () {
        var p = (this.overview && this.overview.patient) || this.patient || {};
        var segs = [];
        var g = (p.gender === '1' || p.gender === 1) ? '男' : ((p.gender === '2' || p.gender === 2) ? '女' : '');
        if (g) { segs.push(g); }
        if (p.age != null && p.age !== '') { segs.push(p.age + '岁'); }
        if (p.patientNo) { segs.push('档案号 ' + p.patientNo); }
        return segs.join(' · ');
      },
      allergyText: function () {
        var v = text((this.overview && this.overview.patient && this.overview.patient.allergyHistory) || (this.patient && this.patient.allergyHistory)).trim();
        if (!v || /^(无|否|未发现)/.test(v)) { return ''; }
        return v;
      },
      /* 展开行的摘要行(门诊 SOAP 优先, 住院取关键字段; 空值过滤) */
      summaryRows: function () {
        var d = this.summary;
        if (!d) { return []; }
        var rows = [];
        function push(label, value) { if (value != null && text(value).trim() !== '') { rows.push([label, text(value)]); } }
        if ((d.diagnoses || []).length) {
          push('诊断', d.diagnoses.map(function (x) { return (x.isMain ? '★' : '') + text(x.diagName); }).filter(Boolean).join('、'));
        }
        if (d.type === 'outpatient') {
          push('主诉', d.chiefComplaint); push('现病史', d.presentIllness); push('体格检查', d.physicalExam);
          push('辅助检查', d.auxExam); push('处理意见', d.treatmentOpinion);
          if (d.orders) { push('医嘱单', (d.orders || []).length + ' 条'); }
        } else {
          push('入院诊断', d.admitDiag); push('主治医师', d.doctorName);
          push('住院天数', d.inDays != null ? d.inDays + ' 天' : '');
          push('费用', this.fmtMoney(d.totalCost));
        }
        return rows;
      }
    },
    watch: {
      modelValue: function (open) { if (open && this.pid) { this.ensureLoad(); } },
      pid: function () {
        /* 切换患者: 已展开摘要复位, 各页签按新患者重取(ensureLoad 由打开动作或即时触发) */
        this.openedKey = ''; this.summary = null;
        if (this.modelValue && this.pid) { this.ensureLoad(); }
      }
    },
    methods: {
      dash: dash,
      typeLabel: function (t) { return TYPE_LABEL[t] || dash(t); },
      fmtDT: function (v) { return (v == null || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 16); },
      fmtMoney: function (v) { if (v == null || v === '') { return '-'; } var n = Number(v); return isNaN(n) ? '-' : '¥' + n.toFixed(2); },
      stat: function (key) { return this.overview ? Number(this.overview[key] || 0) : '-'; },
      itemKey: function (it) { return text(it.type) + '-' + text(it.visitId); },
      itemBrief: function (it) {
        var d = it.details || {};
        if (it.type === 'outpatient') { return [d.chiefComplaint, d.diagnosisText].filter(Boolean).join(' | ') || '-'; }
        return [d.admitDiag, d.diagnosisText].filter(Boolean).join(' | ') || '-';
      },
      /* 首开懒加载: 概览/时间线/报告各按 patientId 记忆已加载, 避免重复请求 */
      ensureLoad: function () {
        if (this.overviewLoadedFor !== this.pid) { this.loadOverview(); }
        if (this.tlLoadedFor !== this.pid) { this.loadTimeline(); }
        if (this.rpLoadedFor !== this.pid) { this.loadReports(); }
      },
      loadOverview: function () {
        var vm = this;
        if (!vm.pid) { return; }
        var id = vm.pid;
        vm.overviewLoading = true;
        HIS.get('/api/his/emr/timeline/patient-overview/' + HIS.idParam(id)).then(function (d) {
          if (vm.pid === id) { vm.overview = d || null; vm.overviewLoadedFor = id; }
        }).catch(function () { if (vm.pid === id) { vm.overview = null; } })
          .finally(function () { if (vm.pid === id) { vm.overviewLoading = false; } });
      },
      loadTimeline: function () {
        var vm = this;
        if (!vm.pid) { return; }
        var id = vm.pid;
        vm.tlLoading = true;
        HIS.get('/api/his/emr/timeline/patient/' + HIS.idParam(id)).then(function (list) {
          if (vm.pid === id) { vm.tlItems = list || []; vm.tlLoadedFor = id; }
        }).catch(function () { if (vm.pid === id) { vm.tlItems = []; } })
          .finally(function () { if (vm.pid === id) { vm.tlLoading = false; } });
      },
      loadReports: function () {
        var vm = this;
        if (!vm.pid) { return; }
        var id = vm.pid;
        vm.rpLoading = true;
        HIS.get('/api/medtech/reports/patient/' + HIS.idParam(id)).then(function (list) {
          if (vm.pid !== id) { return; }
          vm.reports = (Array.isArray(list) ? list : []).filter(function (r) { return Number(r.status) === 2; }).slice(0, 30);
          vm.rpLoadedFor = id;
        }).catch(function () { if (vm.pid === id) { vm.reports = []; } })
          .finally(function () { if (vm.pid === id) { vm.rpLoading = false; } });
      },
      repConclusion: function (rep) {
        if (!rep) { return ''; }
        var direct = text(rep.conclusion || rep.examConclusion || rep.resultSummary).trim();
        if (direct) { return direct; }
        var findings = text(rep.examFindings).trim();
        var items = Array.isArray(rep.resultItems) ? rep.resultItems : [];
        var joined = items.map(function (it) {
          var v = it.resultValue != null ? it.resultValue : (it.value != null ? it.value : '');
          return v === '' ? '' : (text(it.itemName) + ' ' + v + text(it.resultUnit || ''));
        }).filter(Boolean).join('；');
        return [findings, joined].filter(Boolean).join(' | ');
      },
      toggleItem: function (it) {
        var vm = this;
        var key = vm.itemKey(it);
        if (vm.openedKey === key) { vm.openedKey = ''; vm.summary = null; return; }
        vm.openedKey = key; vm.summary = null;
        if (it.visitId == null || it.visitId === '') { return; }
        var scope = it.type === 'inpatient' ? 1 : 2;
        vm.sumLoading = true;
        HIS.get('/api/his/emr/timeline/visit-summary/' + HIS.idParam(it.visitId) + '?scope=' + scope)
          .then(function (d) { if (vm.openedKey === key) { vm.summary = d || null; } })
          .catch(function () {})
          .finally(function () { if (vm.openedKey === key) { vm.sumLoading = false; } });
      },
      openFullTimeline: function () {
        this.$emit('update:modelValue', false);
        if (typeof HIS.go === 'function') { HIS.go('emr-patient-timeline'); }
      }
    }
  };

  HIS.components.Patient360Drawer = Patient360Drawer;
})();
