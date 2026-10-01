/* 门诊医生站生命体征面板: 就诊内体征列表 + 手工补录 + 血糖/血酮趋势图 + 引用到病历 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  var VitalPanel = {
    name: 'DwVitalPanel',
    inject: ['currentVisit', 'currentPatient'],
    emits: ['quote-to-record'],
    props: { modelValue: { type: Boolean, default: false } },
    template: `
      <el-dialog v-model="visible" title="生命体征" width="820px" top="6vh" append-to-body @open="onOpen">
        <div v-if="!visitId" class="dw-collapse-empty">请先选择患者</div>
        <template v-else>
          <div class="dw-vital-toolbar" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px">
            <el-button size="small" type="primary" @click="startAdd">手工录入体征</el-button>
            <el-button size="small" @click="quoteLatest" :disabled="!list.length">引用最新体征到病历</el-button>
            <span class="dim" style="margin-left:auto">血糖/血酮趋势(近 {{ trendData.length }} 次)</span>
          </div>

          <el-form v-if="adding" :model="form" size="small" inline style="margin-bottom:10px;padding:10px;background:var(--dw-card-muted);border-radius:6px">
            <el-form-item label="收缩压"><el-input v-model="form.systolic" style="width:80px"/></el-form-item>
            <el-form-item label="舒张压"><el-input v-model="form.diastolic" style="width:80px"/></el-form-item>
            <el-form-item label="脉搏"><el-input v-model="form.pulse" style="width:70px"/></el-form-item>
            <el-form-item label="体温"><el-input v-model="form.temperature" style="width:80px"/></el-form-item>
            <el-form-item label="呼吸"><el-input v-model="form.respiration" style="width:70px"/></el-form-item>
            <el-form-item label="血糖"><el-input v-model="form.bloodGlucose" style="width:90px"/></el-form-item>
            <el-form-item label="血酮"><el-input v-model="form.bloodKetone" style="width:90px"/></el-form-item>
            <el-form-item label="身高cm"><el-input v-model="form.height" style="width:80px"/></el-form-item>
            <el-form-item label="体重kg"><el-input v-model="form.weight" style="width:80px"/></el-form-item>
            <el-form-item><el-button size="small" type="primary" :loading="saving" @click="doSave">保存</el-button><el-button size="small" @click="adding=false">取消</el-button></el-form-item>
          </el-form>

          <el-table :data="list" size="small" v-loading="loading" max-height="220" style="margin-bottom:12px">
            <el-table-column prop="measTime" label="测量时间" width="150"/>
            <el-table-column label="血压" width="90"><template #default="s">{{ dash(s.row.systolic, s.row.diastolic) }}</template></el-table-column>
            <el-table-column prop="pulse" label="脉搏" width="60"/>
            <el-table-column prop="temperature" label="体温" width="60"/>
            <el-table-column prop="respiration" label="呼吸" width="60"/>
            <el-table-column prop="bloodGlucose" label="血糖" width="70"/>
            <el-table-column prop="bloodKetone" label="血酮" width="70"/>
            <el-table-column label="身高/体重" width="100"><template #default="s">{{ dash(s.row.height, s.row.weight) }}</template></el-table-column>
            <el-table-column prop="source" label="来源" width="70"/>
          </el-table>

          <div ref="chart" style="width:100%;height:260px"></div>
        </template>
      </el-dialog>
    `,
    data: function () {
      return { visible: false, list: [], trendData: [], loading: false, saving: false, adding: false, form: {} };
    },
    watch: {
      modelValue: function (v) { this.visible = v; },
      visible: function (v) { if (!v) { this.$emit('update:modelValue', false); } }
    },
    computed: {
      visitId: function () { var v = this.currentVisit || {}; return v.id || null; },
      patientId: function () { var v = this.currentVisit || {}; var p = this.currentPatient || {}; return v.patientId || p.patientId || p.id || null; }
    },
    methods: {
      onOpen: function () { this.load(); },
      dash: function (a, b) {
        var hasA = a !== null && a !== undefined && a !== '';
        var hasB = b !== null && b !== undefined && b !== '';
        if (!hasA && !hasB) { return '-'; }
        return (hasA ? a : '') + (hasB ? '/' + b : '');
      },
      load: function () {
        var vm = this;
        if (!vm.visitId) { return; }
        vm.loading = true;
        HIS.get('/api/his/outp/vital?visitId=' + encodeURIComponent(vm.visitId))
          .then(function (d) { vm.list = d || []; })
          .catch(function () { vm.list = []; })
          .finally(function () { vm.loading = false; });
        if (vm.patientId) {
          HIS.get('/api/his/outp/vital/trend?patientId=' + encodeURIComponent(vm.patientId) + '&limit=30')
            .then(function (d) { vm.trendData = d || []; vm.$nextTick(vm.renderChart); })
            .catch(function () { vm.trendData = []; });
        }
      },
      startAdd: function () {
        this.form = { visitId: this.visitId, patientId: this.patientId, systolic: '', diastolic: '', pulse: '', temperature: '', respiration: '', bloodGlucose: '', bloodKetone: '', height: '', weight: '', source: '手工' };
        this.adding = true;
      },
      doSave: function () {
        var vm = this;
        var payload = {};
        Object.keys(vm.form).forEach(function (k) {
          var val = vm.form[k];
          if (val === '' || val === null || val === undefined) { return; }
          payload[k] = val;
        });
        if (!payload.visitId) { payload.visitId = vm.visitId; payload.patientId = vm.patientId; }
        vm.saving = true;
        HIS.post('/api/his/outp/vital', payload).then(function (res) {
          vm.adding = false;
          if (res && res.feverTriggered) { ElementPlus.ElMessage.warning('体温达发热阈值, 已自动登记发热病人'); }
          else { HIS.notifySuccess('体征已保存'); }
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      quoteLatest: function () {
        var r = this.list[0];
        if (!r) { return; }
        var parts = [];
        if (r.systolic != null || r.diastolic != null) { parts.push('BP ' + this.dash(r.systolic, r.diastolic) + 'mmHg'); }
        if (r.pulse != null) { parts.push('P ' + r.pulse + '次/分'); }
        if (r.temperature != null) { parts.push('T ' + r.temperature + '℃'); }
        if (r.respiration != null) { parts.push('R ' + r.respiration + '次/分'); }
        if (r.bloodGlucose != null) { parts.push('血糖 ' + r.bloodGlucose + 'mmol/L'); }
        if (r.bloodKetone != null) { parts.push('血酮 ' + r.bloodKetone + 'mmol/L'); }
        if (r.height != null) { parts.push('身高 ' + r.height + 'cm'); }
        if (r.weight != null) { parts.push('体重 ' + r.weight + 'kg'); }
        this.$emit('quote-to-record', parts.join(';  '));
      },
      renderChart: function () {
        var el = this.$refs.chart;
        if (!el || !window.echarts) { return; }
        var chart = window.echarts.getInstanceByDom(el) || window.echarts.init(el);
        var rows = this.trendData || [];
        var x = rows.map(function (r) { return String(r.measTime || '').slice(5, 16); });
        chart.setOption({
          title: { text: '血糖 / 血酮趋势', left: 'center', textStyle: { fontSize: 13 } },
          tooltip: { trigger: 'axis' },
          legend: { data: ['血糖(mmol/L)', '血酮(mmol/L)'], bottom: 0 },
          grid: { left: 46, right: 24, top: 40, bottom: 44 },
          xAxis: { type: 'category', data: x },
          yAxis: { type: 'value', name: 'mmol/L' },
          series: [
            { name: '血糖(mmol/L)', type: 'line', smooth: true, connectNulls: true, data: rows.map(function (r) { return r.bloodGlucose == null ? null : Number(r.bloodGlucose); }) },
            { name: '血酮(mmol/L)', type: 'line', smooth: true, connectNulls: true, data: rows.map(function (r) { return r.bloodKetone == null ? null : Number(r.bloodKetone); }) }
          ]
        }, true);
      }
    }
  };
  HIS.components = HIS.components || {};
  HIS.components.DwVitalPanel = VitalPanel;
})();
