/* 门诊医生站 SOAP 病历面板 */
;(function () {
  const EmrPanel = {
    name: 'DwEmrPanel',
    inject: ['currentVisit', 'currentPatient', 'expandedPanel', 'visitHistory', 'diagnoses'],
    emits: ['save-draft', 'request-expand', 'request-collapse'],
    template: `
      <section class="dw-panel" :class="{ 'is-expanded': isExpanded }">
        <header class="dw-panel-header">
          <span>SOAP 门诊病历 <span class="dim">{{ mode === 'first' ? '初诊' : '复诊' }}</span></span>
          <div>
            <el-radio-group v-model="mode" size="small" :disabled="readOnly">
              <el-radio-button label="first">初诊</el-radio-button>
              <el-radio-button label="return">复诊</el-radio-button>
            </el-radio-group>
            <button class="dw-btn-icon dw-expand-btn" :title="isExpanded ? '还原' : '放大'" @click="toggleExpand">{{ isExpanded ? '↙' : '↗' }}</button>
          </div>
        </header>

        <div v-if="!currentVisit" class="dw-empty"><el-empty description="请先选择患者"></el-empty></div>
        <div v-else :style="expandedLayout">
          <aside v-if="isExpanded" style="min-width:0;border-right:1px solid var(--dw-border);padding-right:14px">
            <div class="dw-section">诊疗轨迹 <span class="dw-section-extra">{{ histories.length }} 次</span></div>
            <div class="dw-timeline">
              <div class="dw-timeline-item" v-for="history in histories" :key="history.id">
                <div class="dw-timeline-time">{{ history.workDate || history.visitTime || '-' }}</div>
                <b>{{ history.deptName || '-' }} · {{ history.mainDiagName || '未记录诊断' }}</b>
                <div class="dim">{{ history.chiefComplaint || '无主诉摘要' }}</div>
                <el-button link type="primary" size="small" @click="quoteHistory(history)">引用本次记录</el-button>
              </div>
            </div>
            <div v-if="!histories.length" class="dw-collapse-empty">暂无历史就诊记录</div>
          </aside>

          <main style="min-width:0">
            <div v-if="!isExpanded" class="dw-tpl-bar">
              <span class="dw-tpl-label">病历模板</span>
              <el-select v-model="selectedTemplateId" filterable clearable placeholder="搜索 SOAP 模板" size="small" style="width:230px" @change="applySelectedTemplate">
                <el-option v-for="tpl in templates" :key="tpl.id" :label="tpl.name" :value="tpl.id"></el-option>
              </el-select>
              <el-button size="small" :disabled="readOnly" @click="saveAsTemplate">存为模板</el-button>
            </div>

            <el-form class="dw-emr-form" :model="form" label-position="top" @submit.prevent>
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

              <div class="dw-section">生命体征 <span class="dw-section-extra">Vital Signs</span></div>
              <div style="display:grid;grid-template-columns:repeat(4,minmax(76px,1fr)) auto;gap:8px;align-items:end;margin-bottom:10px">
                <el-form-item label="T(℃)"><el-input v-model="vitals.t" :disabled="readOnly" placeholder="36.5"></el-input></el-form-item>
                <el-form-item label="P(次/分)"><el-input v-model="vitals.p" :disabled="readOnly" placeholder="78"></el-input></el-form-item>
                <el-form-item label="R(次/分)"><el-input v-model="vitals.r" :disabled="readOnly" placeholder="18"></el-input></el-form-item>
                <el-form-item label="BP(mmHg)"><el-input v-model="vitals.bp" :disabled="readOnly" placeholder="120/80"></el-input></el-form-item>
                <el-button style="margin-bottom:8px" :disabled="readOnly" @click="fillNormalVitals">正常值</el-button>
              </div>

              <el-form-item label="主诉" required>
                <el-input v-model="form.chiefComplaint" type="textarea" :rows="rowCount(2)" maxlength="500" show-word-limit :disabled="readOnly" placeholder="主要症状、部位及持续时间"></el-input>
              </el-form-item>

              <template v-if="mode === 'first'">
                <el-form-item label="现病史" required><el-input v-model="form.presentIllness" type="textarea" :rows="rowCount(3)" maxlength="1000" show-word-limit :disabled="readOnly"></el-input></el-form-item>
                <el-form-item label="既往史" required><el-input v-model="form.pastHistory" type="textarea" :rows="rowCount(2)" maxlength="500" show-word-limit :disabled="readOnly"></el-input></el-form-item>
                <div class="dw-form-grid">
                  <el-form-item label="阳性体征" required><el-input v-model="form.positiveSigns" type="textarea" :rows="rowCount(2)" maxlength="500" show-word-limit :disabled="readOnly"></el-input></el-form-item>
                  <el-form-item label="必要的阴性体征" required><el-input v-model="form.negativeSigns" type="textarea" :rows="rowCount(2)" maxlength="500" show-word-limit :disabled="readOnly"></el-input></el-form-item>
                </div>
              </template>
              <template v-else>
                <el-form-item label="病情变化" required><el-input v-model="form.conditionChange" type="textarea" :rows="rowCount(3)" maxlength="1000" show-word-limit :disabled="readOnly"></el-input></el-form-item>
                <el-form-item label="必要的体格检查" required><el-input v-model="form.followupExam" type="textarea" :rows="rowCount(2)" maxlength="500" show-word-limit :disabled="readOnly"></el-input></el-form-item>
              </template>

              <el-form-item label="辅助检查结果"><el-input v-model="form.auxExam" type="textarea" :rows="rowCount(2)" maxlength="1000" show-word-limit :disabled="readOnly"></el-input></el-form-item>
              <el-form-item label="诊断">
                <div style="width:100%;min-height:38px;padding:7px 10px;background:var(--dw-card-muted);border:1px solid var(--dw-border);border-radius:4px">
                  <span v-if="!diagnosisList.length" class="dim">请在诊断面板录入诊断</span>
                  <span v-for="diag in diagnosisList" :key="diag.diagCode" class="dw-tag" :class="diag.maindiagFlag === '1' ? 'dw-tag--info' : ''" style="margin:0 6px 4px 0">{{ diag.maindiagFlag === '1' ? '★ ' : '' }}{{ diag.diagName }}</span>
                </div>
              </el-form-item>
              <el-form-item :label="mode === 'first' ? '治疗意见' : '治疗处理意见'" required>
                <el-input v-model="form.treatmentOpinion" type="textarea" :rows="rowCount(3)" maxlength="1000" show-word-limit :disabled="readOnly"></el-input>
              </el-form-item>
            </el-form>

            <el-tabs v-if="!isExpanded" v-model="activeHistoryTab" class="dw-collapse">
              <el-tab-pane label="历史就诊" name="history">
                <div class="dw-history-item" v-for="history in histories" :key="history.id">
                  <div class="hi-head"><b>{{ history.workDate || '-' }}</b><span class="hi-dept">{{ history.deptName || '-' }}</span><span class="dw-tag dw-tag--info">{{ history.mainDiagName || '未记录诊断' }}</span></div>
                  <div class="hi-line"><span class="k">主诉</span><span>{{ history.chiefComplaint || '-' }}</span></div>
                  <el-collapse><el-collapse-item title="展开详情"><div class="hi-line"><span class="k">处置</span><span>{{ history.treatmentOpinion || '-' }}</span></div></el-collapse-item></el-collapse>
                  <el-button link type="primary" :disabled="readOnly" @click="quoteHistory(history)">引用</el-button>
                </div>
                <div v-if="!histories.length" class="dw-collapse-empty">暂无历史就诊记录</div>
              </el-tab-pane>
              <el-tab-pane label="检验报告" name="reports">
                <div v-for="group in reportGroups" :key="group.type">
                  <div class="dw-section">{{ group.type }}</div>
                  <div class="dw-report-item" v-for="(report, index) in group.items" :key="index">
                    <div class="ri-head"><b>{{ report.itemName || report.name || '-' }}</b><span class="dim">{{ report.reportTime || report.time || '-' }}</span></div>
                    <div :style="isAbnormal(report) ? 'color:var(--dw-danger);font-weight:700' : ''">{{ report.result || report.value || '-' }} {{ report.unit || '' }} <span class="dim">{{ report.referenceRange || report.reference || '' }}</span></div>
                  </div>
                </div>
                <div v-if="!reportGroups.length" class="dw-collapse-empty">暂无检验报告</div>
              </el-tab-pane>
            </el-tabs>

            <div v-if="isExpanded" class="dw-tpl-bar" style="margin-top:14px;padding-top:12px;border-top:1px dashed var(--dw-border)">
              <span class="dw-tpl-label">病历模板</span>
              <el-select v-model="selectedTemplateId" filterable clearable placeholder="搜索 SOAP 模板" style="width:260px" @change="applySelectedTemplate">
                <el-option v-for="tpl in templates" :key="tpl.id" :label="tpl.name" :value="tpl.id"></el-option>
              </el-select>
              <el-button :disabled="readOnly" @click="saveAsTemplate">存为模板</el-button>
            </div>

            <div class="dw-action-group">
              <span class="dim">病历字段按门诊草稿接口保存</span>
              <span style="margin-left:auto"></span>
              <el-button :disabled="readOnly" @click="emitSave(false)">暂存病历</el-button>
              <el-button type="primary" :disabled="readOnly" @click="emitSave(true)">提交病历</el-button>
            </div>
          </main>
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
        arrivalModes: ['步行', '急救车', '轮椅', '担架', '其他'],
        vitals: { t: '', p: '', r: '', bp: '' },
        form: this.emptyForm()
      };
    },
    computed: {
      isExpanded: function () { return this.expandedPanel === 'emr'; },
      expandedLayout: function () {
        return this.isExpanded ? { display: 'grid', gridTemplateColumns: '300px minmax(0,1fr)', gap: '16px', padding: '0 4px' } : { padding: '12px' };
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
      rowCount: function (rows) { return this.isExpanded ? rows * 2 : rows; },
      fillNormalVitals: function () { this.vitals = { t: '36.5', p: '78', r: '18', bp: '120/80' }; },
      toggleExpand: function () { this.$emit(this.isExpanded ? 'request-collapse' : 'request-expand', 'emr'); },
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