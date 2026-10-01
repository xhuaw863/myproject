/* 门诊医生站患者上下文信息条: 单行弹性布局, 常驻 sticky 区, 滚动/切 Tab 时关键临床信息不丢 */
;(function () {
  const PatientBanner = {
    name: 'DwPatientBanner',
    inject: ['currentVisit', 'currentPatient', 'feeSummary', 'visitDuration'],
    template: `
      <section v-if="currentVisit" class="dw-banner" :class="bannerClass" aria-label="当前患者信息">
        <strong class="dw-banner-main">{{ patientName }}</strong>
        <span class="dw-banner-meta">{{ genderLabel(patient.gender || currentVisit.gender) }} · <b :style="ageStyle">{{ ageLabel }}</b></span>
        <span class="dw-banner-meta" v-if="cfg.iptNo" :title="'门诊号 ' + (currentVisit.iptOtpNo || currentVisit.patientNo || '-') + ' · 挂号号 ' + (currentVisit.regNo || '-')">门诊号 {{ currentVisit.iptOtpNo || currentVisit.patientNo || '-' }}</span>
        <span v-if="cfg.insurance" class="dw-tag" :class="insured ? 'dw-tag--success' : ''">{{ insuranceLabel }}</span>
        <span v-if="emergency" class="dw-tag dw-tag--danger">急诊</span>
        <span class="dw-tag" :class="statusClass">{{ statusLabel }}</span>
        <span class="dw-banner-meta" v-if="cfg.deptDoctor">{{ currentVisit.deptName || '-' }} · {{ currentVisit.drName || '-' }} · {{ medTypeLabel }}</span>
        <span class="dw-banner-allergy" :class="{ 'dw-allergy-alert': hasAllergy }" :title="allergyText">
          <span class="warning-icon" aria-hidden="true">⚠</span>
          <span>过敏：</span>
          <strong>{{ allergyText }}</strong>
          <span v-if="!allergyHistory" class="dw-tag dw-tag--danger">必填</span>
        </span>
        <span v-if="lastVisit && cfg.lastVisit" class="dw-banner-meta" :title="lastVisit.mainDiagName || '未记录'" style="max-width:260px;overflow:hidden;text-overflow:ellipsis">上次: {{ lastVisit.workDate || '-' }} {{ lastVisit.deptName || '' }}</span>
        <span class="dw-banner-right">
          <span class="dim">接诊计时 {{ visitDuration }}</span>
          <span v-if="cfg.fee" class="dw-fee-badge">本次费用 ￥{{ feeTotal }}</span>
          <el-popover placement="bottom-end" :width="220" trigger="click">
            <template #reference><button class="dw-banner-gear" title="自定义患者信息栏" aria-label="自定义">⚙</button></template>
            <div style="padding:2px">
              <div style="font-weight:600;margin-bottom:6px">信息栏显示字段</div>
              <el-checkbox v-model="cfg.iptNo" size="small">门诊号</el-checkbox>
              <el-checkbox v-model="cfg.insurance" size="small">医保/自费</el-checkbox>
              <el-checkbox v-model="cfg.deptDoctor" size="small">科室·医生·类别</el-checkbox>
              <el-checkbox v-model="cfg.lastVisit" size="small">上次就诊</el-checkbox>
              <el-checkbox v-model="cfg.fee" size="small">本次费用</el-checkbox>
              <div style="margin-top:8px;text-align:right"><el-button size="small" type="primary" @click="savePref">保存偏好</el-button></div>
            </div>
          </el-popover>
        </span>
      </section>
      <section v-else class="dw-banner dw-banner--selfpay" aria-label="未选择患者">
        <span style="padding:4px 0;color:var(--dw-text-hint)">请从左侧候诊队列选择患者</span>
      </section>
    `,
    data: function () {
      return { history: [], historyLoadingFor: null, cfg: { iptNo: true, insurance: true, deptDoctor: true, lastVisit: true, fee: true } };
    },
    mounted: function () { this.loadPref(); },
    computed: {
      feeTotal: function () {
        var fee = this.feeSummary && this.feeSummary.value ? this.feeSummary.value : (this.feeSummary || {});
        return (Number(fee.totalFee != null ? fee.totalFee : fee.total) || 0).toFixed(2);
      },
      patient: function () { return this.currentPatient || {}; },
      patientName: function () {
        return this.patient.name || (this.currentVisit && this.currentVisit.patientName) || '-';
      },
      patientId: function () {
        return this.currentVisit ? this.currentVisit.patientId : null;
      },
      birthDate: function () {
        return this.patient.birthDate || (this.currentVisit && this.currentVisit.birthDate) || '';
      },
      ageInfo: function () {
        var birth = this.parseDate(this.birthDate);
        if (!birth) {
          var fallback = Number(this.patient.age != null ? this.patient.age : this.currentVisit.age);
          return { text: isNaN(fallback) ? '-' : fallback + '岁', years: isNaN(fallback) ? null : fallback };
        }
        var now = new Date();
        var days = Math.max(0, Math.floor((now.getTime() - birth.getTime()) / 86400000));
        var years = now.getFullYear() - birth.getFullYear();
        var months = now.getMonth() - birth.getMonth();
        if (now.getDate() < birth.getDate()) { months--; }
        if (months < 0) { years--; months += 12; }
        if (years < 1) {
          return { text: days < 31 ? days + '日龄' : Math.max(1, Math.floor(days / 30.4375)) + '月龄', years: 0 };
        }
        return { text: years <= 6 ? years + '岁' + months + '月' : years + '岁', years: years };
      },
      ageLabel: function () { return this.ageInfo.text; },
      ageStyle: function () {
        if (this.ageInfo.years !== null && this.ageInfo.years < 14) { return { color: 'var(--dw-primary)' }; }
        if (this.ageInfo.years !== null && this.ageInfo.years >= 65) { return { color: 'var(--dw-warning)' }; }
        return {};
      },
      emergency: function () {
        return String(this.currentVisit.medType || '') === '13' || this.currentVisit.emergencyFlag === 1 || this.currentVisit.emergencyFlag === '1';
      },
      insured: function () {
        return !!(this.currentVisit.psnNo || this.currentVisit.insutype || this.currentVisit.mdtrtId || this.patient.psnNo);
      },
      bannerClass: function () {
        if (this.emergency) { return 'dw-banner--emergency'; }
        return this.insured ? 'dw-banner--insured' : 'dw-banner--selfpay';
      },
      insuranceLabel: function () {
        if (!this.insured) { return '自费'; }
        var type = String(this.currentVisit.insutype || this.patient.insutype || '');
        var names = { '310': '职工医保', '390': '居民医保', '340': '工伤保险', '391': '城乡居民' };
        return this.patient.insutypeName || names[type] || '医保';
      },
      statusLabel: function () {
        return ({ 1: '候诊', 2: '接诊中', 3: '已完成', 4: '已取消' })[Number(this.currentVisit.visitStatus)] || '未知';
      },
      statusClass: function () {
        return ({ 1: 'dw-tag--info', 2: 'dw-tag--warning', 3: 'dw-tag--success', 4: '' })[Number(this.currentVisit.visitStatus)] || '';
      },
      medTypeLabel: function () {
        var value = String(this.currentVisit.medType || '11');
        return ({ '11': '普通门诊', '13': '急诊', '14': '门诊慢特病', '1102': '门诊统筹' })[value] || value;
      },
      allergyHistory: function () {
        return String(this.currentVisit.allergyHistory || this.patient.allergyHistory || '').trim();
      },
      hasAllergy: function () {
        var value = this.allergyHistory;
        return !!value && !/^(无|否|未发现|无.*过敏)/.test(value);
      },
      allergyText: function () { return this.allergyHistory || '未填写过敏史'; },
      lastVisit: function () { return this.history.length ? this.history[0] : null; }
    },
    watch: {
      patientId: {
        immediate: true,
        handler: function () { this.loadHistory(); }
      }
    },
    methods: {
      parseDate: function (value) {
        if (!value) { return null; }
        var date = new Date(String(value).replace(' ', 'T'));
        return isNaN(date.getTime()) ? null : date;
      },
      genderLabel: function (value) {
        if (value === '1' || value === '男') { return '男'; }
        if (value === '2' || value === '女') { return '女'; }
        return value || '-';
      },
      loadHistory: function () {
        var vm = this;
        var id = vm.patientId;
        vm.history = [];
        if (!id || vm.historyLoadingFor === id) { return; }
        vm.historyLoadingFor = id;
        window.HIS.get('/api/his/visit/history?patientId=' + encodeURIComponent(id) + '&limit=1')
          .then(function (data) { if (vm.patientId === id) { vm.history = data || []; } })
          .catch(function () { if (vm.patientId === id) { vm.history = []; } })
          .finally(function () { if (vm.historyLoadingFor === id) { vm.historyLoadingFor = null; } });
      },
      loadPref: function () {
        var vm = this;
        if (!window.HIS || !window.HIS.get) { return; }
        window.HIS.get('/api/his/outp/pref?scene=dw_banner').then(function (d) {
          if (d && d.configJson) {
            try {
              var c = JSON.parse(d.configJson) || {};
              Object.keys(vm.cfg).forEach(function (k) { if (c[k] != null) { vm.cfg[k] = !!c[k]; } });
            } catch (e) { /* 配置损坏回落默认 */ }
          }
        }).catch(function () { /* 未登录/无配置保持默认 */ });
      },
      savePref: function () {
        var vm = this;
        window.HIS.put('/api/his/outp/pref?scene=dw_banner', { configJson: JSON.stringify(vm.cfg) })
          .then(function () { window.HIS.notifySuccess('患者信息栏偏好已保存'); })
          .catch(window.HIS.notifyError);
      }
    }
  };

  window.HIS = window.HIS || {};
  window.HIS.components = window.HIS.components || {};
  window.HIS.components.DwPatientBanner = PatientBanner;
})();