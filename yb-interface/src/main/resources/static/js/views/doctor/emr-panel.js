/* 门诊医生站 SOAP 病历面板 */
;(function () {
  const EmrPanel = {
    name: 'DwEmrPanel',
    inject: ['currentVisit', 'currentPatient', 'visitHistory', 'diagnoses'],
    emits: ['save-draft'],
    template: `
      <section class="dw-panel dw-emr-panel">
        <header class="dw-panel-header">
          <span>SOAP 门诊病历 <span class="dim">{{ mode === 'first' ? '初诊' : '复诊' }}</span></span>
          <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;justify-content:flex-end">
            <el-radio-group v-model="mode" size="small" :disabled="readOnly">
              <el-radio-button label="first">初诊</el-radio-button>
              <el-radio-button label="return">复诊</el-radio-button>
            </el-radio-group>
            <el-select v-model="selectedTemplateId" size="small" filterable clearable placeholder="病历模板" style="width:130px" @change="applySelectedTemplate">
              <el-option v-for="tpl in templates" :key="tpl.id" :label="tpl.name" :value="tpl.id"></el-option>
            </el-select>
            <el-button size="small" :disabled="readOnly" @click="saveAsTemplate">存模板</el-button>
            <el-popover placement="bottom-end" :width="380" trigger="click" @show="loadExamReports">
              <template #reference><el-button size="small" :disabled="!patientId">引用报告 {{ examReports.length ? examReports.length + '份' : '' }}</el-button></template>
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
              <template #reference><el-button size="small">轨迹 {{ histories.length ? histories.length + '次' : '' }}</el-button></template>
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
          </div>
        </header>

        <div v-if="!currentVisit" class="dw-empty"><el-empty description="请先选择患者"></el-empty></div>
        <div v-else class="dw-emr-body">
          <el-form class="dw-emr-form" :model="form" label-position="left" label-width="78px" @submit.prevent>
            <div class="dw-form-grid">
              <el-form-item label="就诊时间" required>
                <el-date-picker v-model="form.visitTime" :type="emergency ? 'datetime' : 'date'" :value-format="emergency ? 'YYYY-MM-DD HH:mm' : 'YYYY-MM-DD'" :format="emergency ? 'YYYY-MM-DD HH:mm' : 'YYYY-MM-DD'" :disabled="readOnly" style="width:100%"></el-date-picker>
              </el-form-item>
              <el-form-item label="科别"><el-input :model-value="currentVisit.deptName || '-'" disabled></el-input></el-form-item>
            </div>

            <div v-if="emergency" class="dw-form-grid">
              <el-form-item label="来院方式" required>
                <el-select v-model="form.arrivalMode" :disabled="readOnly" placeholder="请选择来院方式" style="width:100%">
                  <el-option v-for="item in arrivalModes" :key="item" :label="item" :value="item"></el-option>
                </el-select>
              </el-form-item>
              <div></div>
            </div>

            <div class="dw-vitals-line">
              <span class="lb">T</span><el-input v-model="vitals.t" size="small" :disabled="readOnly" placeholder="36.5"></el-input>
              <span class="lb">P</span><el-input v-model="vitals.p" size="small" :disabled="readOnly" placeholder="78"></el-input>
              <span class="lb">R</span><el-input v-model="vitals.r" size="small" :disabled="readOnly" placeholder="18"></el-input>
              <span class="lb">BP</span><el-input v-model="vitals.bp" size="small" :disabled="readOnly" placeholder="120/80"></el-input>
              <el-button size="small" :disabled="readOnly" @click="fillNormalVitals">正常值</el-button>
            </div>

            <el-form-item label="主诉" required>
              <el-input v-model="form.chiefComplaint" type="textarea" :autosize="taSize(1)" maxlength="500" :disabled="readOnly" placeholder="主要症状、部位及持续时间"></el-input>
            </el-form-item>

            <template v-if="mode === 'first'">
              <el-form-item label="现病史" required><el-input v-model="form.presentIllness" type="textarea" :autosize="taSize(2)" maxlength="1000" :disabled="readOnly"></el-input></el-form-item>
              <div class="dw-form-grid">
                <el-form-item label="既往史" required><el-input v-model="form.pastHistory" type="textarea" :autosize="taSize(1)" maxlength="500" :disabled="readOnly"></el-input></el-form-item>
                <el-form-item label="阳性体征" required><el-input v-model="form.positiveSigns" type="textarea" :autosize="taSize(1)" maxlength="500" :disabled="readOnly"></el-input></el-form-item>
              </div>
              <el-form-item label="阴性体征" required><el-input v-model="form.negativeSigns" type="textarea" :autosize="taSize(1)" maxlength="500" :disabled="readOnly"></el-input></el-form-item>
            </template>
            <template v-else>
              <el-form-item label="病情变化" required><el-input v-model="form.conditionChange" type="textarea" :autosize="taSize(2)" maxlength="1000" :disabled="readOnly"></el-input></el-form-item>
              <el-form-item label="体格检查" required><el-input v-model="form.followupExam" type="textarea" :autosize="taSize(1)" maxlength="500" :disabled="readOnly"></el-input></el-form-item>
            </template>

            <div class="dw-form-grid">
              <el-form-item label="辅助检查"><el-input v-model="form.auxExam" type="textarea" :autosize="taSize(1)" maxlength="1000" :disabled="readOnly"></el-input></el-form-item>
              <el-form-item label="诊断">
                <div class="dw-diag-chips">
                  <span v-if="!diagnosisList.length" class="dim">下方诊断区录入</span>
                  <span v-for="diag in diagnosisList" :key="diag.diagCode" class="dw-tag" :class="diag.maindiagFlag === '1' ? 'dw-tag--info' : ''">{{ diag.maindiagFlag === '1' ? '★' : '' }}{{ diag.diagName }}</span>
                </div>
              </el-form-item>
            </div>
            <el-form-item :label="mode === 'first' ? '治疗意见' : '处理意见'" required>
              <el-input v-model="form.treatmentOpinion" type="textarea" :autosize="taSize(2)" maxlength="1000" :disabled="readOnly"></el-input>
            </el-form-item>
          </el-form>

          <div class="dw-action-group" style="margin-top:4px;padding-top:8px">
            <el-button size="small" :disabled="readOnly" @click="emitSave(false)">暂存病历(F3)</el-button>
            <el-button size="small" type="primary" :disabled="readOnly" @click="emitSave(true)">提交病历</el-button>
          </div>
        </div>
      </section>
    `,
    data: function () {
      return {
        mode: 'first',
        modeVisitId: null,
        selectedTemplateId: null,
        templates: [],
        activeHistoryTab: 'history',
        examReports: [],
        reportsLoading: false,
        reportsLoaded: false,
        arrivalModes: ['步行', '急救车', '轮椅', '担架', '其他'],
        vitals: { t: '', p: '', r: '', bp: '' },
        form: this.emptyForm()
      };
    },
    computed: {
      isExpanded: function () { return true; },
      patientId: function () {
        var patient = this.currentPatient || {};
        var visit = this.currentVisit || {};
        return patient.id || visit.patientId || null;
      },
      histories: function () { return Array.isArray(this.visitHistory) ? this.visitHistory : []; },
      diagnosisList: function () { return Array.isArray(this.diagnoses) ? this.diagnoses : []; },
      emergency: function () { return !!this.currentVisit && String(this.currentVisit.medType || '') === '13'; },
      readOnly: function () { return !this.currentVisit || Number(this.currentVisit.visitStatus) >= 3; },
      reportGroups: function () {
        var groups = {};
        this.histories.forEach(function (history) {
          var reports = history.labReports || history.reports || history.reportItems || [];
          if (!Array.isArray(reports)) { reports = []; }
          reports.forEach(function (report) {
            var type = report.type || report.reportType || report.category || '其他检验';
            (groups[type] = groups[type] || []).push(report);
          });
        });
        return Object.keys(groups).map(function (type) { return { type: type, items: groups[type] }; });
      },
      soapData: function () {
        return {
          visitId: this.currentVisit && this.currentVisit.id,
          chiefComplaint: this.form.chiefComplaint,
          presentIllness: this.mode === 'first' ? this.form.presentIllness : this.form.conditionChange,
          pastHistory: this.mode === 'first' ? this.form.pastHistory : '',
          allergyHistory: (this.currentVisit && this.currentVisit.allergyHistory) || (this.currentPatient && this.currentPatient.allergyHistory) || '',
          physicalExam: this.buildPhysicalExam(),
          auxExam: this.form.auxExam,
          treatmentOpinion: this.form.treatmentOpinion
        };
      }
    },
    watch: {
      currentVisit: {
        immediate: true,
        deep: true,
        handler: function (visit) { this.resetFromVisit(visit); }
      },
      visitHistory: {
        immediate: true,
        handler: function () { this.applyAutoMode(); }
      }
    },
    created: function () { this.loadTemplates(); },
    methods: {
      emptyForm: function () {
        return { visitTime: '', arrivalMode: '', chiefComplaint: '', presentIllness: '', pastHistory: '', positiveSigns: '', negativeSigns: '', conditionChange: '', followupExam: '', auxExam: '', treatmentOpinion: '' };
      },
      nowText: function (withTime) {
        var d = new Date();
        var date = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
        return withTime ? date + ' ' + ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) : date;
      },
      resetFromVisit: function (visit) {
        this.form = this.emptyForm();
        this.vitals = { t: '', p: '', r: '', bp: '' };
        this.modeVisitId = null;
        this.examReports = []; this.reportsLoaded = false;
        if (!visit) { return; }
        var withTime = String(visit.medType || '') === '13';
        this.form.visitTime = visit.visitTime
          ? String(visit.visitTime).slice(0, withTime ? 16 : 10).replace('T', ' ')
          : this.nowText(withTime);
        this.form.chiefComplaint = visit.chiefComplaint || '';
        this.form.presentIllness = visit.presentIllness || '';
        this.form.pastHistory = visit.pastHistory || '';
        this.form.auxExam = visit.auxExam || '';
        this.form.treatmentOpinion = visit.treatmentOpinion || '';
        this.parsePhysicalExam(visit.physicalExam || '');
        this.applyAutoMode();
      },
      applyAutoMode: function () {
        if (!this.currentVisit || this.modeVisitId === this.currentVisit.id) { return; }
        this.mode = this.histories.length ? 'return' : 'first';
        this.modeVisitId = this.currentVisit.id;
      },
      parsePhysicalExam: function (text) {
        var value = String(text || '');
        var vital = value.match(/T[:：]\s*([\d.]+).*?P[:：]\s*(\d+).*?R[:：]\s*(\d+).*?BP[:：]\s*([\d/]+)/i);
        if (vital) { this.vitals = { t: vital[1], p: vital[2], r: vital[3], bp: vital[4] }; }
        this.form.positiveSigns = value.replace(/^T[:：].*?(\n|$)/i, '').replace(/^阳性体征[:：]\s*/i, '');
        this.form.followupExam = this.form.positiveSigns;
      },
      buildPhysicalExam: function () {
        var vital = ['T:' + this.vitals.t, 'P:' + this.vitals.p, 'R:' + this.vitals.r, 'BP:' + this.vitals.bp].filter(function (part) { return !/:$/.test(part); }).join(' ');
        var body = this.mode === 'first'
          ? ['阳性体征：' + this.form.positiveSigns, '必要的阴性体征：' + this.form.negativeSigns].join('\n')
          : this.form.followupExam;
        return [vital, body].filter(Boolean).join('\n');
      },
      rowCount: function (rows) { return rows * 2; },
      taSize: function (min) { return { minRows: min, maxRows: min + 4 }; },
      fillNormalVitals: function () { this.vitals = { t: '36.5', p: '78', r: '18', bp: '120/80' }; },
      loadTemplates: function () {
        var vm = this;
        window.HIS.get('/api/his/template/list?type=soap').then(function (data) { vm.templates = data || []; }).catch(function () { vm.templates = []; });
      },
      templateById: function (id) {
        return this.templates.find(function (tpl) { return tpl.id === id; });
      },
      parseTemplate: function (tpl) {
        if (!tpl) { return {}; }
        if (typeof tpl.content === 'object') { return tpl.content || {}; }
        try { return JSON.parse(tpl.content || '{}'); } catch (e) { return {}; }
      },
      macroValue: function (text) {
        var patient = this.currentPatient || {};
        var visit = this.currentVisit || {};
        var age = patient.age != null ? patient.age : visit.age;
        var values = {
          '{姓名}': patient.name || visit.patientName || '',
          '{性别}': patient.genderName || (patient.gender === '1' || visit.gender === '1' ? '男' : (patient.gender === '2' || visit.gender === '2' ? '女' : '')),
          '{年龄}': age == null ? '' : age + '岁',
          '{科室}': visit.deptName || ''
        };
        var result = String(text == null ? '' : text);
        Object.keys(values).forEach(function (key) { result = result.split(key).join(values[key]); });
        return result;
      },
      applySelectedTemplate: function (id) {
        var data = this.parseTemplate(this.templateById(id));
        var vm = this;
        Object.keys(this.form).forEach(function (key) {
          if (data[key] !== undefined && data[key] !== null) { vm.form[key] = vm.macroValue(data[key]); }
        });
        if (data.vitals) { this.vitals = Object.assign({}, this.vitals, data.vitals); }
        if (id && window.HIS.notifySuccess) { window.HIS.notifySuccess('已应用病历模板'); }
      },
      saveAsTemplate: function () {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('请输入模板名称', '存为 SOAP 模板', { inputPattern: /\S+/, inputErrorMessage: '模板名称不能为空' })
          .then(function (result) {
            return window.HIS.post('/api/his/template/create', {
              templateType: 'soap', name: result.value.trim(), content: JSON.stringify(Object.assign({}, vm.form, { vitals: vm.vitals })), sortOrder: 0, status: 1
            });
          })
          .then(function () { window.HIS.notifySuccess('病历模板已保存'); vm.loadTemplates(); })
          .catch(function () {});
      },
      quoteHistory: function (history) {
        if (!history) { return; }
        this.form.chiefComplaint = history.chiefComplaint || this.form.chiefComplaint;
        this.form.presentIllness = history.presentIllness || history.chiefComplaint || this.form.presentIllness;
        this.form.conditionChange = history.presentIllness || history.chiefComplaint || this.form.conditionChange;
        this.form.pastHistory = history.pastHistory || this.form.pastHistory;
        this.form.positiveSigns = history.physicalExam || this.form.positiveSigns;
        this.form.followupExam = history.physicalExam || this.form.followupExam;
        this.form.auxExam = history.auxExam || this.form.auxExam;
        this.form.treatmentOpinion = history.treatmentOpinion || this.form.treatmentOpinion;
      },
      /* ===== 摘要引用追加(处方/医嘱【插入病历】及报告引用经主编排中继至此) ===== */
      appendTreatment: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        this.form.treatmentOpinion = (this.form.treatmentOpinion ? this.form.treatmentOpinion.replace(/\s+$/, '') + '\n' : '') + t;
        ElementPlus.ElMessage.success('已插入治疗意见, 请核对后随病历保存(F3)');
      },
      appendAuxExam: function (text) {
        var t = String(text || '').trim();
        if (!t) { return; }
        this.form.auxExam = (this.form.auxExam ? this.form.auxExam.replace(/\s+$/, '') + '\n' : '') + t;
        ElementPlus.ElMessage.success('已插入辅助检查, 请核对后随病历保存(F3)');
      },
      /* ===== 医技报告引用(/api/medtech/reports/patient/{id}, popover 首次展开懒加载) ===== */
      loadExamReports: function (force) {
        var vm = this;
        if (vm.reportsLoaded && !force) { return; }
        if (!vm.patientId) { vm.examReports = []; return; }
        vm.reportsLoading = true;
        window.HIS.get('/api/medtech/reports/patient/' + encodeURIComponent(vm.patientId)).then(function (d) {
          var list = Array.isArray(d) ? d : [];
          /* 仅已发布(status=2)报告可引用, 口径同医技报告状态字典 */
          vm.examReports = list.filter(function (r) { return Number(r.status) === 2; }).slice(0, 30);
        }).catch(function () { vm.examReports = []; }).finally(function () {
          vm.reportsLoading = false; vm.reportsLoaded = true;
        });
      },
      /* 报告结论提取: 检验类取 resultItems 项目值拼接, 检查类取所见/结论字段 */
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
      },
      isAbnormal: function (report) {
        return report.abnormal === true || report.abnormalFlag === '1' || /^(H|L|↑|↓|异常)$/i.test(String(report.flag || report.resultFlag || ''));
      },
      validateRequired: function () {
        var required = this.mode === 'first'
          ? ['chiefComplaint', 'presentIllness', 'pastHistory', 'positiveSigns', 'negativeSigns', 'treatmentOpinion']
          : ['chiefComplaint', 'conditionChange', 'followupExam', 'treatmentOpinion'];
        if (this.emergency) { required.push('arrivalMode'); }
        for (var i = 0; i < required.length; i++) {
          if (!String(this.form[required[i]] || '').trim()) { ElementPlus.ElMessage.warning('请完整填写红星必填项'); return false; }
        }
        return true;
      },
      emitSave: function (submit) {
        if (!this.validateRequired()) { return; }
        this.$emit('save-draft', Object.assign({}, this.soapData));
        if (submit && window.HIS.notifySuccess) { window.HIS.notifySuccess('病历内容已提交保存'); }
      }
    }
  };

  window.HIS = window.HIS || {};
  window.HIS.components = window.HIS.components || {};
  window.HIS.components.DwEmrPanel = EmrPanel;
})();