/* 门诊医生站病历面板 —— 方案 B 收敛 + P3-4 Tiptap 适配层
 * - 当前模板带 Tiptap 文档(document)时, 整面板交由门诊病历书写器(dw-outp-emr-writer), 下述接口方法全部代理转发;
 *   对外接口(emitSave/validateForFinish/buildFinishPayload/appendXxx/quoteXxx/resetFromVisit)保持不变, doctor.js 无感切换。
 * - 无 document 的旧模板回退既有结构化表单(选模板→渲染 EmrField→写 structure); SOAP 文本编辑面保持下线。
 * - 历史「纯文本病历」(无 structure 但有 SOAP 列)以只读文本回显, 不可在此编辑。
 * - 完成接诊经 validateForFinish()/buildFinishPayload() 交付; Tiptap 模式为 content+structure 双轨。 */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  const EmrPanel = {
    name: 'DwEmrPanel',
    inject: ['currentVisit', 'currentPatient', 'visitHistory', 'diagnoses'],
    emits: ['save-draft', 'submit', 'emr-dirty'],
    /* 结构化模式渲染字段控件; Tiptap 模式渲染书写器(两者均先于本文件加载, 须局部声明) */
    components: {
      'emr-field': (window.HIS.components || {}).EmrField,
      'dw-outp-emr-writer': (window.HIS.components || {}).OutpEmrWriter
    },
    template: `
      <section class="dw-panel dw-emr-panel" :class="{ 'is-folded': folded }">
        <!-- U1 折叠态(两种模式统一): 单行细条; 父页进度 chips 定位时自动展开 -->
        <div v-if="folded" class="dw-slim-empty">病历面板已折叠 <a href="javascript:void(0)" @click="toggleFold">展开</a></div>
        <template v-else>
        <!-- Tiptap 模式: 悬浮折叠把手(书写器自带 header, 不侵入其内部) -->
        <button v-if="useTiptap" class="dw-collapse-btn dw-emr-fold-float" title="折叠病历面板" @click="toggleFold">▾</button>
        <!-- P3-4: 当前模板带 Tiptap 文档时整面板切换为书写器; 事件经本组件中继, 对外接口不变 -->
        <dw-outp-emr-writer
          v-if="useTiptap"
          ref="tiptapWriter"
          @save-draft="$emit('save-draft', $event)"
          @submit="$emit('submit', $event)"
        ></dw-outp-emr-writer>

        <!-- 旧表单模式: 无 Tiptap 文档的模板走原有结构化编辑面 -->
        <template v-else>
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
            <button class="dw-collapse-btn" style="margin-left:6px" title="折叠病历面板" @click="toggleFold">▾</button>
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
            <div v-if="!selectedEmrTemplateId" class="dw-collapse-empty dim">请选择结构化病历模板；若无模板，请到「模板设计器(结构化)」或「病历模板设计器(字段画布)」新建门诊(scope=2)模板</div>
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
        </template>
        </template>
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
        reportsLoaded: false,
        /* U1: 折叠记忆(localStorage) */
        folded: window.localStorage.getItem('dw.emr.folded') === '1'
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
      },
      /* P3-4: 当前生效模板(面板选择优先, 否则取就诊记录模板); 用于判定是否走 Tiptap 书写器 */
      currentTemplate: function () {
        var id = this.selectedEmrTemplateId;
        if (!id) {
          var v = this.currentVisit || {};
          id = v.emrTemplateId != null ? String(v.emrTemplateId) : null;
        }
        if (!id) { return null; }
        var hit = null;
        (this.emrTemplates || []).forEach(function (t) { if (String(t.id) === String(id)) { hit = t; } });
        return hit;
      },
      useTiptap: function () {
        var tpl = this.currentTemplate;
        return !!(tpl && tpl.document);
      }
    },
    watch: {
      currentVisit: {
        immediate: true,
        deep: true,
        handler: function (visit) {
          var vm = this;
          /* 装载期(含模板字段回填)不报脏 */
          vm._emrHydrating = true;
          vm.resetFromVisit(visit);
          Vue.nextTick(function () { vm._emrHydrating = false; });
        }
      },
      /* U2 脏标记: 结构化表单有改动即上报主编排(Tiptap 模式由书写器自行管理, 未覆盖); 误报代价仅为切换时多一次自动暂存提示 */
      structForm: {
        deep: true,
        handler: function () {
          if (!this._emrHydrating) { this.$emit('emr-dirty'); }
        }
      }
    },
    created: function () { this.loadEmrTemplates(); },
    methods: {
      /* ===== U1/U2: 折叠记忆与定位 / F4 预检缺项清单(无副作用, 供父页汇总) ===== */
      toggleFold: function () {
        this.folded = !this.folded;
        try { window.localStorage.setItem('dw.emr.folded', this.folded ? '1' : '0'); } catch (e) { /* 隐私模式忽略 */ }
      },
      revealForLocate: function () { this.folded = false; },
      collectFinishIssues: function () {
        /* Tiptap 模式返回空清单: 必检链由书写器 validateForFinish 在 doctor.finishVisit 内兜底 */
        if (this.useTiptap || this.isLegacySoap) { return []; }
        var out = [];
        if (!this.selectedEmrTemplateId) { return ['未选择结构化病历模板']; }
        var form = this.structForm || {};
        (this.emrFields || []).forEach(function (f) {
          if (String(f.type) === 'section') { return; }
          if (f.required === true || String(f.required) === 'true') {
            var v = form[f.fieldKey];
            var empty = v == null || v === '' || (Array.isArray(v) && !v.length);
            if (empty) { out.push(f.label || f.fieldKey); }
          }
        });
        if (out.length) { out = ['必填项未填写：' + out.join('、')]; }
        return out;
      },
      /* ===== P3-4 Tiptap 适配: 书写器句柄与模板同步 ===== */
      tiptapWriter: function () {
        return this.$refs.tiptapWriter || null;
      },
      /* 将模板选择同步给书写器: 书写器模板列表可能尚未加载, 经其 pendingRestore + autoSelectTemplate
       * 既有消费链等待列表就绪后自动应用(其 loadTemplates().then 内会再消费一次) */
      syncTemplateToWriter: function (templateId, structure) {
        var writer = this.tiptapWriter();
        var tplId = templateId != null ? String(templateId) : null;
        if (!writer || !tplId) { return; }
        var visible = (writer.templates || []).some(function (t) { return String(t.id) === String(tplId); });
        if (visible) {
          if (String(writer.selectedTemplateId || '') !== tplId) {
            writer.pendingRestore = { templateId: tplId, structure: structure || '' };
            writer.autoSelectTemplate();
          }
        } else if (!writer.templatesLoading) {
          writer.pendingRestore = { templateId: tplId, structure: structure || '' };
          writer.loadTemplates();
        } else {
          writer.pendingRestore = { templateId: tplId, structure: structure || '' };
        }
      },
      resetFromVisit: function (visit) {
        this.emrFields = []; this.structForm = {};
        this.selectedEmrTemplateId = null; this.quality = null;
        this.examReports = []; this.reportsLoaded = false;
        if (!visit) { return; }
        this.loadStructFromVisit(visit);
        /* Tiptap 模式: 已挂载书写器同步复位(书写器自身 watch 亦会复位, 经其 _loadedVisitKey 幂等收敛) */
        var writer = this.tiptapWriter();
        if (this.useTiptap && writer) { writer.resetFromVisit(visit); }
      },
      /* 就诊已存结构化病历: 回显模板字段与取值(Tiptap 病历 emrFormat=1 亦须选中模板以驱动书写器) */
      loadStructFromVisit: function (visit) {
        var vm = this;
        var tiptapDoc = !!visit && visit.emrFormat != null && Number(visit.emrFormat) === 1;
        if (!visit || (!visit.structure && !tiptapDoc)) { return; }
        vm.selectedEmrTemplateId = visit.emrTemplateId != null ? String(visit.emrTemplateId) : null;
        if (visit.structure) {
          try { vm.structForm = JSON.parse(visit.structure) || {}; } catch (e) { vm.structForm = {}; }
        }
        if (!vm.emrTemplates.length) { vm.loadEmrTemplates(); }
        if (vm.selectedEmrTemplateId) { vm.loadEmrDefs(vm.selectedEmrTemplateId); }
      },
      /* ===== 结构化模板装载 ===== */
      loadEmrTemplates: function () {
        var vm = this;
        HIS.get('/api/his/emr/template/list?scope=2&mine=true').then(function (data) {
          /* document(Tiptap JSON) 决定该模板走书写器还是旧表单, 须保留 */
          vm.emrTemplates = (data || []).map(function (t) {
            return { id: String(t.id), name: t.templateName || t.name, document: t.document || null };
          });
        }).catch(function () { vm.emrTemplates = []; });
      },
      onEmrTemplateChange: function (id) {
        this.quality = null;
        if (!id) { this.emrFields = []; this.structForm = {}; return; }
        this.structForm = {};
        this.loadEmrDefs(id);
        /* 若新模板带 Tiptap 文档: 本次更新后即切换为书写器, 待其挂载后同步模板选择 */
        var vm = this;
        this.$nextTick(function () {
          if (vm.useTiptap) { vm.syncTemplateToWriter(id, ''); }
        });
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
        /* Tiptap 模式: 委托书写器(其自带只读/必填守卫与提示), 不回退表单逻辑 */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.emitSave(submit); }
          else { ElementPlus.ElMessage.warning('病历书写器加载中, 请稍后重试'); }
          return;
        }
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
        /* Tiptap 模式: 委托书写器(未就绪时保守拦截, 不回退表单数据) */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { return writer.validateForFinish(); }
          ElementPlus.ElMessage.warning('病历书写器加载中, 请稍后重试');
          return false;
        }
        if (this.isLegacySoap) { return true; }
        if (!this.selectedEmrTemplateId) { ElementPlus.ElMessage.warning('请先选择结构化病历模板并完成书写'); return false; }
        return this.validateStructRequired();
      },
      buildFinishPayload: function () {
        /* Tiptap 模式: 委托书写器(返回 content+structure 双轨; 未就绪返回 {} 由 validateForFinish 先行拦截) */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          return writer ? writer.buildFinishPayload() : {};
        }
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
        /* Tiptap 模式: 委托书写器(自带可插入性校验与提示) */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.appendTreatment(text); }
          return;
        }
        var t = String(text || '').trim();
        if (!t) { return; }
        if (this.isLegacySoap || this.readOnly) { ElementPlus.ElMessage.warning('病历只读, 无法插入摘要'); return; }
        if (!this.hasStructField('treatmentOpinion')) { ElementPlus.ElMessage.warning('当前结构化模板无「治疗意见」字段, 无法插入'); return; }
        var cur = this.structForm.treatmentOpinion || '';
        this.structForm.treatmentOpinion = (cur ? cur.replace(/\s+$/, '') + '\n' : '') + t;
        ElementPlus.ElMessage.success('已插入治疗意见, 请核对后随病历保存(F3)');
      },
      appendAuxExam: function (text) {
        /* Tiptap 模式: 委托书写器 */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.appendAuxExam(text); }
          return;
        }
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
        /* Tiptap 模式: 委托书写器 */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.appendVital(text); }
          return;
        }
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
        /* Tiptap 模式: 委托书写器 */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.applyPreConsult(payload); }
          return;
        }
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
        /* Tiptap 模式: 委托书写器 */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.quoteHistory(history); }
          return;
        }
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
        /* Tiptap 模式: 委托书写器 */
        if (this.useTiptap) {
          var writer = this.tiptapWriter();
          if (writer) { writer.quoteExamReport(rep); }
          return;
        }
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
