/* 门诊医生站诊前预问诊: 医生接诊前对患者基本病情的前置了解, 可引用进病历主诉/现病史 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  var PreConsult = {
    name: 'DwPreConsultDialog',
    inject: ['currentVisit'],
    emits: ['quote-to-record'],
    props: { modelValue: { type: Boolean, default: false } },
    template: `
      <el-dialog v-model="visible" title="诊前预问诊" width="640px" top="8vh" append-to-body @open="onOpen">
        <div v-if="!visitId" class="dw-collapse-empty">请先选择患者</div>
        <template v-else>
          <el-form :model="form" label-width="88px" size="small">
            <el-form-item label="主要症状"><el-input v-model="form.chiefComplaint" type="textarea" :autosize="{minRows:1,maxRows:3}" placeholder="患者自述最主要不适"/></el-form-item>
            <el-form-item label="持续时间"><el-input v-model="form.duration" placeholder="如: 3天"/></el-form-item>
            <el-form-item label="部位"><el-input v-model="form.location" placeholder="如: 右下腹"/></el-form-item>
            <el-form-item label="现病史"><el-input v-model="form.presentIllness" type="textarea" :autosize="{minRows:2,maxRows:5}"/></el-form-item>
            <el-form-item label="患者自述"><el-input v-model="form.selfStatement" type="textarea" :autosize="{minRows:2,maxRows:5}"/></el-form-item>
          </el-form>
          <div class="dim" style="margin-top:6px">录入人: {{ recorder || '-' }}</div>
          <template #footer>
            <el-button size="small" @click="quoteToRecord" :disabled="!loaded">引用到病历</el-button>
            <el-button size="small" type="primary" :loading="saving" @click="doSave">保存预问诊</el-button>
          </template>
        </template>
      </el-dialog>
    `,
    data: function () {
      return { visible: false, form: { chiefComplaint: '', duration: '', location: '', presentIllness: '', selfStatement: '' }, recorder: '', loaded: false, saving: false };
    },
    watch: {
      modelValue: function (v) { this.visible = v; },
      visible: function (v) { if (!v) { this.$emit('update:modelValue', false); } }
    },
    computed: { visitId: function () { var v = this.currentVisit || {}; return v.id || null; } },
    methods: {
      onOpen: function () { this.load(); },
      load: function () {
        var vm = this;
        if (!vm.visitId) { return; }
        HIS.get('/api/his/outp/pre-consult?visitId=' + encodeURIComponent(vm.visitId)).then(function (d) {
          vm.loaded = !!d;
          vm.recorder = d && d.recorder ? d.recorder : '';
          var c = {};
          if (d && d.contentJson) { try { c = JSON.parse(d.contentJson) || {}; } catch (e) { c = {}; } }
          vm.form = {
            chiefComplaint: c.chiefComplaint || '', duration: c.duration || '', location: c.location || '',
            presentIllness: c.presentIllness || '', selfStatement: c.selfStatement || ''
          };
        }).catch(function () { vm.loaded = false; });
      },
      doSave: function () {
        var vm = this;
        vm.saving = true;
        HIS.post('/api/his/outp/pre-consult?visitId=' + encodeURIComponent(vm.visitId), { contentJson: JSON.stringify(vm.form) })
          .then(function () { vm.loaded = true; HIS.notifySuccess('预问诊已保存'); })
          .catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      quoteToRecord: function () {
        this.$emit('quote-to-record', { chiefComplaint: this.form.chiefComplaint, presentIllness: this.joinIllness() });
      },
      joinIllness: function () {
        var f = this.form;
        var seg = [];
        if (f.location) { seg.push('部位：' + f.location); }
        if (f.duration) { seg.push('持续：' + f.duration); }
        if (f.presentIllness) { seg.push(f.presentIllness); }
        if (f.selfStatement) { seg.push('自述：' + f.selfStatement); }
        return seg.join('  ');
      }
    }
  };
  HIS.components = HIS.components || {};
  HIS.components.DwPreConsultDialog = PreConsult;
})();
