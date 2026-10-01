/* 门诊医生站病历面板 —— 方案 B 收敛: 结构化引擎为唯一编辑面
 * - 新病历一律结构化(选模板→渲染 EmrField→写 structure); SOAP 文本编辑面已下线。
 * - 历史「纯文本病历」(无 structure 但有 SOAP 列)以只读文本回显, 不可在此编辑。
 * - 引用报告/轨迹等辅助动作改写入 structForm(按对齐 fieldKey)。
 * - 完成接诊经 validateForFinish()/buildFinishPayload() 交付 structure+emrTemplateId。 */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  const EmrPanel = {
    name: 'DwEmrPanel',
    inject: ['currentVisit', 'currentPatient', 'visitHistory', 'diagnoses'],
    emits: ['save-draft'],
    /* 结构化模式渲染字段控件; HIS.components 非全局注册, 须局部声明(emr-field.js 先于本文件加载) */
    components: { 'emr-field': (window.HIS.components || {}).EmrField },
    template: `
      <section class="dw-panel dw-emr-panel">
        <header class="dw-panel-header">
          <span>{{ isLegacySoap ? '门诊病历' : '结构化 门诊病历' }} <span class="dim" v-if="isLegacySoap">历史文本(只读)</span></span>
          <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;justify-content:flex-end">
            <template v-if="!isLegacySoap">
              <el-select v-model="selectedEmrTemplateId" size="small" filterable clearable placeholder="结构化模板" style="width:170px" @change="onEmrTemplateChange">
                <el-option v-for="tpl in emrTemplates" :key="tpl.id" :label="tpl.name" :value="tpl.id"></el-option>
              </el-select>
              <el-button size="small" :loading="qualityLoading" @click="evaluateQuality">完整性质控{{ quality && quality.score != null ? ' ' + quality.score + '分' : '' }}</el-button>
              <el-button size="small" @click="openVersions">历史版本</el-button>
              <el-popover placement="bottom-end" :width="380" trigger="click" @show="loadExamReports">
                <template #reference><el-button size="small" :disabled="!patientId || readOnly">引用报告 {{ examReports.length ? examReports.length + '份' : '' }}</el-button></template>
                <div class="dw-trace-pop">
                  <div v-if="reportsLoading" class="dw-collapse-empty">报告加载中…</div>
                  <div class="dw-timeline" v-else-if="examReports.length">
                    <div class="dw-timeline-item" v-for="(rep, idx) in examReports" :key="rep.id || idx">
                      <div class="dw-timeline-time">{{ (rep.reportTime || rep.auditTime || '').slice(0, 10) || '-' }} · {{ rep.reportType === 'lab' ? '检验' : '检查' }}</div>
                      <b>{{ rep.itemNames || rep.orderName || '未命名报告' }}</b>
                      <div class="dim dw-report-conclusion">{{ reportConclusion(rep) || '暂无结论' }}</div>
                      <el-button link type="primary" size="small" :disabled="readOnly || !reportConclusion(rep)" @click="quoteExamReport(rep)">插入辅助检查</el-button>
                    </div>
                  </div>
                  <div v-else class="dw-collapse-empty">暂无已发布报告</div>
                </div>
              </el-popover>
              <el-popover placement="bottom-end" :width="340" trigger="click">
                <template #reference><el-button size="small" :disabled="readOnly">轨迹 {{ histories.length ? histories.length + '次' : '' }}</el-button></template>
                <div class="dw-trace-pop">
                  <div class="dw-timeline" v-if="histories.length">
                    <div class="dw-timeline-item" v-for="history in histories" :key="history.id">
                      <div class="dw-timeline-time">{{ history.workDate || history.visitTime || '-' }}</div>
                      <b>{{ history.deptName || '-' }} · {{ history.mainDiagName || '未记录诊断' }}</b>
                      <div class="dim">{{ history.chiefComplaint || '无主诉摘要' }}</div>
                      <el-button link type="primary" size="small" :disabled="readOnly" @click="quoteHistory(history)">引用本次记录</el-button>
                    </div>
                  </div>
                  <div v-else class="dw-collapse-empty">暂无历史就诊记录</div>
                </div>
              </el-popover>
            </template>
          </div>
        </header>

        <div v-if="!currentVisit" class="dw-empty"><el-empty description="请先选择患者"></el-empty></div>
        <div v-else class="dw-emr-body">
          <!-- 历史「纯文本病历」只读回显 -->
          <div v-if="isLegacySoap" class="dw-emr-legacy">
            <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px;">
              该病历为结构化改造前的历史文本病历，仅作只读展示；如需以结构化病历书写，请在接诊中使用结构化模板新建。
            </el-alert>
            <div class="dw-legacy-row" v-for="row in legacySoap" :key="row.label">
              <span class="lb">{{ row.label }}</span><span class="vl">{{ row.value }}</span>
            </div>
          </div>

          <!-- 结构化引擎编辑面 -->
          <div v-else class="dw-emr-struct">
            <div v-if="!selectedEmrTemplateId" class="dw-collapse-empty dim">请选择结构化病历模板；若无模板，请到「病历模板设计器」新建门诊(scope=2)模板</div>
            <div v-else-if="!emrFields.length" class="dw-collapse-empty dim">模板字段加载中…</div>
            <template v-else>
              <div v-for="(f, i) in emrFields" :key="i" class="dw-emr-struct-row">
                <emr-field :field="f" :model="structForm" :readonly="readOnly"></emr-field>
              </div>
            </template>
          </div>

          <div class="dw-action-group" v-if="!isLegacySoap && !readOnly" style="margin-top:4px;padding-top:8px">
            <el-button size="small" :disabled="readOnly" @click="emitSave(false)">暂存病历(F3)</el-button>
            <el-button size="small" type="primary" :disabled="readOnly" @click="emitSave(true)">提交病历</el-button>
          </div>
        </div>
      </section>
    `,
    data: function () {
      return {
        /* ===== 结构化病历引擎(方案 B 收敛: 唯一编辑面) ===== */
        emrTemplates: [],
        selectedEmrTemplateId: null,
        emrFields: [],
        structForm: {},
        quality: null,
        qualityLoading: false,
        examReports: [],
        reportsLoading: false,
        reportsLoaded: false
      };
    },
    computed: {
      patientId: function () {
        var patient = this.currentPatient || {};
        var visit = this.currentVisit || {};
        return patient.id || visit.patientId || null;
      },
      histories: function () { return Array.isArray(this.visitHistory) ? this.visitHistory : []; },
      diagnosisList: function () { return Array.isArray(this.diagnoses) ? this.diagnoses : []; },
      readOnly: function () { return !this.currentVisit || Number(this.currentVisit.visitStatus) >= 3; },
      /* 有 structure 即结构化病历; 无 structure 但存在 SOAP 文本列 = 历史纯文本病历(只读) */
      legacyHasText: function () {
        var v = this.currentVisit || {};
        return ['chiefComplaint', 'presentIllness', 'physicalExam', 'auxExam', 'treatmentOpinion', 'pastHistory']
          .some(function (k) { return v[k] != null && String(v[k]).trim() !== ''; });
      },
      isLegacySoap: function () {
        /* 仅「已完成且无 structure」的历史纯文本病历才走只读回显; 可编辑就诊一律结构化书写 */
        return !!this.currentVisit && !this.currentVisit.structure && this.readOnly && this.legacyHasText;
      },
      legacySoap: function () {
        var v = this.currentVisit || {};
        var diag = (this.diagnosisList || []).map(function (d) { return d.diagName; }).filter(Boolean).join('、');
        var rows = [
          { label: '就诊时间', value: v.visitTime },
          { label: '主诉', value: v.chiefComplaint },
          { label: '现病史', value: v.presentIllness },
          { label: '既往史', value: v.pastHistory },
          { label: '过敏史', value: v.allergyHistory },
          { label: '体格检查', value: v.physicalExam },
          { label: '辅助检查', value: v.auxExam },
          { label: '诊断', value: diag },
          { label: '处理意见', value: v.treatmentOpinion },
          { label: '随访', value: v.followupNote }
        ];
        return rows.filter(function (r) { return r.value != null && String(r.value).trim() !== ''; });
      }
    },
    watch: {
      currentVisit: {
        immediate: true,
        deep: true,
        handler: function (visit) { this.resetFromVisit(visit); }
      }
    },
    created: function () { this.loadEmrTemplates(); },
    methods: {
      resetFromVisit: function (visit) {
        this.emrFields = []; this.structForm = {};
        this.selectedEmrTemplateId = null; this.quality = null;
        this.examReports = []; this.reportsLoaded = false;
        if (!visit) { return; }
        this.loadStructFromVisit(visit);
      },
      /* 就诊已存结构化病历: 回显模板字段与取值 */
      loadStructFromVisit: function (visit) {
        var vm = this;
        if (!visit || !visit.structure) { return; }
        vm.selectedEmrTemplateId = visit.emrTemplateId != null ? String(visit.emrTemplateId) : null;
        try { vm.structForm = JSON.parse(visit.structure) || {}; } catch (e) { vm.structForm = {}; }
        if (!vm.emrTemplates.length) { vm.loadEmrTemplates(); }
        if (vm.selectedEmrTemplateId) { vm.loadEmrDefs(vm.selectedEmrTemplateId); }
      },
      /* ===== 结构化模板装载 ===== */
      loadEmrTemplates: function () {
        var vm = this;
        HIS.get('/api/his/emr/template/list?scope=2&mine=true').then(function (data) {
          vm.emrTemplates = (data || []).map(function (t) { return { id: String(t.id), name: t.templateName || t.name }; });
        }).catch(function () { vm.emrTemplates = []; });
      },
      onEmrTemplateChange: function (id) {
        this.quality = null;
        if (!id) { this.emrFields = []; this.structForm = {}; return; }
        this.structForm = {};
        this.loadEmrDefs(id);
      },
      loadEmrDefs: function (id) {
        var vm = this;
        HIS.get('/api/his/emr/template/' + id + '/defs').then(function (data) {
          vm.emrFields = Array.isArray(data) ? data : [];
        }).catch(function () { vm.emrFields = []; });
      },
      hasStructField: function (key) {
        return (this.emrFields || []).some(function (f) { return f.fieldKey === key && String(f.type) !== 'section'; });
      },
      validateStructRequired: function () {
        var miss = [];
        var form = this.structForm || {};
        (this.emrFields || []).forEach(function (f) {
          if (String(f.type) === 'section') { return; }
          if (f.required === true || String(f.required) === 'true') {
            var v = form[f.fieldKey];
            var empty = v == null || v === '' || (Array.isArray(v) && !v.length);
            if (empty) { miss.push(f.label || f.fieldKey); }
          }
        });
        if (miss.length) { ElementPlus.ElMessage.warning('请填写必填项：' + miss.join('、')); return false; }
        return true;
      },
      /* ===== 保存/完成交付 ===== */
      emitSave: function (submit) {
        if (this.isLegacySoap) { ElementPlus.ElMessage.warning('历史文本病历为只读, 无法在此编辑保存'); return; }
        if (!this.selectedEmrTemplateId) { ElementPlus.ElMessage.warning('请先选择结构化病历模板'); return; }
        if (!this.validateStructRequired()) { return; }
        this.$emit('save-draft', {
          visitId: this.currentVisit && this.currentVisit.id,
          emrTemplateId: this.selectedEmrTemplateId,
          structure: JSON.stringify(this.structForm || {})
        });
        var vm = this;
        if (HIS.notifySuccess) { HIS.notifySuccess(submit ? '结构化病历已提交保存' : '结构化病历已暂存'); }
        setTimeout(function () { vm.evaluateQuality(true); }, 700);
      },
      /* 供主编排(完成接诊 F4)调用: 历史文本病历返回 true(后端沿用既有列), 结构化须选模板且必填齐备 */
      validateForFinish: function () {
        if (this.isLegacySoap) { return true; }
        if (!this.selectedEmrTemplateId) { ElementPlus.ElMessage.warning('请先选择结构化病历模板并完成书写'); return false; }
        return this.validateStructRequired();
      },
      buildFinishPayload: function () {
        if (this.isLegacySoap) { return {}; }
        return { emrTemplateId: this.selectedEmrTemplateId, structure: JSON.stringify(this.structForm || {}) };
      },
      /* ===== 质控 / 版本 ===== */
      evaluateQuality: function (silent) {
        var vm = this;
        var vid = vm.currentVisit && vm.currentVisit.id;
        if (!vid) { return; }
        vm.qualityLoading = true;
        HIS.post('/api/his/outp/emr/quality?visitId=' + encodeURIComponent(vid), {}).then(function (d) {
          vm.quality = d || null;
          if (!silent && d && d.hasStructure) {
            var miss = (d.missing || []).map(function (m) { return m.label; }).join('、');
            ElementPlus.ElMessage.info('完整性 ' + d.score + ' 分（必填 ' + d.filledRequired + '/' + d.totalRequired + '）' + (miss ? '，缺失：' + miss : ''));
          }
        }).catch(function () { if (!silent) { ElementPlus.ElMessage.warning('质控评分失败(病历可能尚未保存)'); } })
          .finally(function () { vm.qualityLoading = false; });
      },
      openVersions: function () {
        var vm = this;
        var vid = vm.currentVisit && vm.currentVisit.id;
        if (!vid) { return; }
        HIS.get('/api/his/outp/emr/versions?visitId=' + encodeURIComponent(vid)).then(function (list) {
          list = list || [];
          if (!list.length) { ElementPlus.ElMessage.info('暂无历史版本（保存结构化病历后生成）'); return; }
          var txt = list.map(function (v) { return 'v' + v.versionNo + '  ' + (v.operateTime || '') + '  ' + (v.operatorName || '') + '  ' + (v.operateType || ''); }).join('\n');
          ElementPlus.ElMessageBox.alert(txt, '历史版本（共 ' + list.length + ' 个）');
        }).catch(function () { ElementPlus.ElMessage.warning('版本加载失败'); });
      },
      /* ===== 摘要引用(处方/医嘱【插入病历】及报告引用经主编排中继至此, 写入 structForm) ===== */
      appendTreatment: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.isLegacySoap || this.readOnly) { ElementPlus.ElMessage.warning('病历只读, 无法插入摘要'); return; }
        if (!this.hasStructField('treatmentOpinion')) { ElementPlus.ElMessage.warning('当前结构化模板无「治疗意见」字段, 无法插入'); return; }
        var cur = this.structForm.treatmentOpinion || '';
        this.structForm.treatmentOpinion = (cur ? cur.replace(/\s+$/, '') + '\n' : '') + t;
        ElementPlus.ElMessage.success('已插入治疗意见, 请核对后随病历保存(F3)');
      },
      appendAuxExam: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.isLegacySoap || this.readOnly) { ElementPlus.ElMessage.warning('病历只读, 无法插入摘要'); return; }
        if (!this.hasStructField('auxExam')) { ElementPlus.ElMessage.warning('当前结构化模板无「辅助检查」字段, 无法插入'); return; }
        var cur = this.structForm.auxExam || '';
        this.structForm.auxExam = (cur ? cur.replace(/\s+$/, '') + '\n' : '') + t;
        ElementPlus.ElMessage.success('已插入辅助检查, 请核对后随病历保存(F3)');
      },
      /* OP-A 引用体征摘要到「体格检查」 */
      appendVital: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.isLegacySoap || this.readOnly) { ElementPlus.ElMessage.warning('病历只读, 无法引用体征'); return; }
        if (!this.hasStructField('physicalExam')) { ElementPlus.ElMessage.warning('当前结构化模板无「体格检查」字段, 无法引用'); return; }
        var line = '生命体征: ' + t;
        var cur = this.structForm.physicalExam || '';
        if (cur.indexOf(line) >= 0) { ElementPlus.ElMessage.info('体征摘要已存在, 未重复插入'); return; }
        this.structForm.physicalExam = (cur ? cur.replace(/\s+$/, '') + '\n' : '') + line;
        ElementPlus.ElMessage.success('已引用体征到体格检查, 请核对后随病历保存(F3)');
      },
      /* OP-A 引用预问诊到「主诉/现病史」 */
      applyPreConsult: function (payload) {
        payload = payload || {};
        if (this.isLegacySoap || this.readOnly) { ElementPlus.ElMessage.warning('病历只读, 无法引用预问诊'); return; }
        var vm = this, applied = 0;
        if (vm.hasStructField('chiefComplaint') && payload.chiefComplaint) { vm.structForm.chiefComplaint = payload.chiefComplaint; applied++; }
        if (vm.hasStructField('presentIllness') && payload.presentIllness) { vm.structForm.presentIllness = payload.presentIllness; applied++; }
        if (applied) { ElementPlus.ElMessage.success('已引用预问诊 ' + applied + ' 项到当前病历'); }
        else { ElementPlus.ElMessage.warning('当前模板无可对齐的主诉/现病史字段'); }
      },
      /* 引用历史就诊: 按对齐 fieldKey 尽力回填 structForm(仅当前模板存在的字段) */
      quoteHistory: function (history) {
        if (!history) { return; }
        if (this.isLegacySoap || this.readOnly) { ElementPlus.ElMessage.warning('病历只读, 无法引用'); return; }
        var vm = this;
        var map = {
          chiefComplaint: history.chiefComplaint, presentIllness: history.presentIllness,
          pastHistory: history.pastHistory, physicalExam: history.physicalExam,
          auxExam: history.auxExam, treatmentOpinion: history.treatmentOpinion
        };
        var applied = 0;
        Object.keys(map).forEach(function (k) {
          if (vm.hasStructField(k) && map[k] != null && String(map[k]).trim() !== '') { vm.structForm[k] = map[k]; applied++; }
        });
        if (applied) { ElementPlus.ElMessage.success('已引用历史就诊 ' + applied + ' 项到当前结构化病历'); }
        else { ElementPlus.ElMessage.warning('历史就诊无可对齐到当前模板的字段'); }
      },
      /* ===== 医技报告引用(/api/medtech/reports/patient/{id}, popover 首次展开懒加载) ===== */
      loadExamReports: function (force) {
        var vm = this;
        if (vm.reportsLoaded && !force) { return; }
        if (!vm.patientId) { vm.examReports = []; return; }
        vm.reportsLoading = true;
        HIS.get('/api/medtech/reports/patient/' + encodeURIComponent(vm.patientId)).then(function (d) {
          var list = Array.isArray(d) ? d : [];
          vm.examReports = list.filter(function (r) { return Number(r.status) === 2; }).slice(0, 30);
        }).catch(function () { vm.examReports = []; }).finally(function () {
          vm.reportsLoading = false; vm.reportsLoaded = true;
        });
      },
      reportConclusion: function (rep) {
        if (!rep) { return ''; }
        var direct = String(rep.conclusion || rep.examConclusion || rep.resultSummary || '').trim();
        if (direct) { return direct; }
        var findings = String(rep.examFindings || '').trim();
        var items = Array.isArray(rep.resultItems) ? rep.resultItems : [];
        var joined = items.map(function (it) {
          var v = it.resultValue != null ? it.resultValue : (it.value != null ? it.value : '');
          return v === '' ? '' : (it.itemName + ' ' + v + (it.resultUnit || ''));
        }).filter(Boolean).join('；');
        return [findings, joined].filter(Boolean).join(' | ');
      },
      quoteExamReport: function (rep) {
        if (!rep) { return; }
        var type = rep.reportType === 'lab' ? '检验' : '检查';
        var name = rep.itemNames || rep.orderName || rep.reportNo || '未命名项目';
        var date = String(rep.reportTime || rep.auditTime || rep.createTime || '').slice(0, 10);
        var conclusion = this.reportConclusion(rep);
        if (!conclusion) { return; }
        this.appendAuxExam('【' + type + '】' + name + '：' + conclusion + (date ? '（' + date + '）' : ''));
      }
    }
  };

  HIS.components = HIS.components || {};
  HIS.components.DwEmrPanel = EmrPanel;
})();
