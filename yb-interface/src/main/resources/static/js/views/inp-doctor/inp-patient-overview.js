/* 住院医生站 - 患者概览面板: 床头卡信息 + 过敏警示 + 联系人/关切 + 诊断分组 + 医嘱统计 + 费用汇总
 * 数据源: GET /api/his/inp/doctor/patient/{visitId}/summary (visit/patient/bed/ward/diagnoses/orderStats/feeSummary)
 *       GET /api/his/inp/allergy/list?visitId=xx (就诊过敏史, 红色醒目警示卡)
 * 同文件附带注册「费用」页签面板 inp-charge-overview(共用 summary 接口, 只展开费用部分)。
 * 注册: HIS.components.InpPatientOverview / HIS.components.InpChargeOverview (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 面板私有样式(过敏警示卡/信息栅格), 一次性注入 */
  (function ensureStyles() {
    if (document.getElementById('inp-overview-extra-style')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-overview-extra-style';
    st.textContent = [
      '.iw-allergy-card { margin-top:12px; background:var(--yb-danger); color:#fff; border-radius:var(--yb-r-md); padding:12px 16px; }',
      '.iw-allergy-card .t { font-weight:700; margin-bottom:8px; font-size:var(--yb-fs-md); }',
      '.iw-allergy-list { display:flex; flex-wrap:wrap; gap:6px 10px; font-size:var(--yb-fs-base); }',
      '.iw-allergy-item { background:rgba(255,255,255,.18); border-radius:var(--yb-r-pill); padding:2px 10px; }',
      '.iw-allergy-none { margin-top:12px; background:var(--yb-success-bg); color:var(--yb-success-strong); border:1px solid var(--yb-success-border); border-radius:var(--yb-r-md); padding:10px 16px; font-weight:600; font-size:var(--yb-fs-base); }',
      '.iw-info-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(150px, 1fr)); gap:8px 18px; font-size:var(--yb-fs-base); color:var(--yb-ink-2); }',
      '.iw-info-grid b { color:var(--yb-ink-1); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  const FEE_TYPE = { 1: '西药', 2: '中药', 3: '检查', 4: '检验', 5: '治疗', 6: '护理', 7: '材料', 8: '床位', 9: '其他' };
  const DIAG_TYPE_DEFS = [
    { type: 1, label: '入院诊断' },
    { type: 2, label: '补充诊断' },
    { type: 3, label: '术后诊断' },
    { type: 4, label: '出院诊断' }
  ];
  const ORDER_STATUS = { 1: '新开', 2: '已审核', 3: '执行中', 4: '已完成', 5: '已停止', 6: '已作废' };
  /* 过敏/护理/病情/入院来源(与住院登记模块枚举一致) */
  const ALLERGY_TYPES = { 1: '药物', 2: '食物', 3: '环境', 4: '其他' };
  const ALLERGY_SEVERITY = { 1: '轻', 2: '中', 3: '重' };
  const NURSING_LEVELS = { 1: '特级护理', 2: '一级护理', 3: '二级护理', 4: '三级护理' };
  const NURSING_LEVEL_TAG = { 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' };
  const CONDITION_LEVELS = { 1: '危', 2: '重', 3: '一般' };
  const CONDITION_LEVEL_TAG = { 1: 'danger', 2: 'warning', 3: 'success' };
  const ADMIT_SOURCES = { 1: '门诊', 2: '急诊', 3: '转诊', 4: '其他' };

  function money(value) {
    const n = Number(value);
    return isNaN(n) ? '0.00' : n.toFixed(2);
  }

  function dateTimeText(value) {
    if (!value) { return '-'; }
    const s = String(value).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }

  function dateText(value) {
    if (!value) { return '-'; }
    return String(value).replace('T', ' ').substring(0, 10);
  }

  function admitDays(admitDate) {
    if (!admitDate) { return '-'; }
    const ts = new Date(String(admitDate).replace(' ', 'T')).getTime();
    if (isNaN(ts)) { return '-'; }
    return Math.max(1, Math.floor((Date.now() - ts) / 86400000) + 1);
  }

  function genderText(value) {
    if (value === '1' || value === '男') { return '男'; }
    if (value === '2' || value === '女') { return '女'; }
    return value || '-';
  }

  /* ================= 患者概览 ================= */
  const InpPatientOverview = {
    name: 'InpPatientOverview',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return { loading: false, summary: null, allergies: [] };
    },
    computed: {
      visit() { return (this.summary && this.summary.visit) || {}; },
      patient() { return (this.summary && this.summary.patient) || {}; },
      ward() { return (this.summary && this.summary.ward) || {}; },
      bed() { return (this.summary && this.summary.bed) || {}; },
      patientName() { return this.patient.name || this.visit.patientName || '-'; },
      gender() { return genderText(this.patient.gender); },
      age() {
        const birth = this.patient.birthDate;
        if (birth) {
          const b = new Date(String(birth).replace(' ', 'T')).getTime();
          if (!isNaN(b)) {
            const years = Math.floor((Date.now() - b) / 31557600000);
            return years > 0 ? years + '岁' : Math.max(1, Math.floor((Date.now() - b) / 2592000000)) + '月';
          }
        }
        return this.patient.age != null ? this.patient.age + '岁' : '-';
      },
      bedLabel() { return this.bed.bedNo || '未分床'; },
      wardName() { return this.ward.wardName || '-'; },
      admittedDays() { return admitDays(this.visit.admitDate); },
      insured() { return !!(this.visit.psnNo || this.visit.insutype || this.visit.mdtrtId); },
      statusText() { return ({ 1: '待入院', 2: '在院', 3: '出院办理中', 4: '已出院', 5: '已取消' })[Number(this.visit.visitStatus)] || '-'; },
      statusTagType() { return ({ 1: 'info', 2: 'success', 3: 'warning', 4: '', 5: 'info' })[Number(this.visit.visitStatus)] || ''; },
      diagGroups() {
        const dict = (this.summary && this.summary.diagnoses) || {};
        return DIAG_TYPE_DEFS.map(g => ({ type: g.type, label: g.label, items: dict[String(g.type)] || [] }));
      },
      diagCount() {
        return this.diagGroups.reduce((sum, g) => sum + g.items.length, 0);
      },
      stats() { return (this.summary && this.summary.orderStats) || {}; },
      statusChips() {
        const by = this.stats.byStatus || {};
        return Object.keys(ORDER_STATUS).map(k => ({
          label: ORDER_STATUS[k], value: Number(by[k] || 0), key: k
        }));
      },
      fee() { return (this.summary && this.summary.feeSummary) || {}; },
      feeRows() {
        const by = this.fee.byFeeType || {};
        return Object.keys(by).map(k => ({ type: k, label: FEE_TYPE[k] || ('类别' + k), amount: Number(by[k]) || 0 }))
          .sort((a, b) => b.amount - a.amount);
      },
      feeTotal() { return Number(this.fee.totalCost != null ? this.fee.totalCost : this.visit.totalCost) || 0; }
    },
    watch: {
      visitId: { immediate: true, handler() { this.load(); } }
    },
    methods: {
      load() {
        const vm = this;
        if (!vm.visitId) { vm.summary = null; vm.allergies = []; return; }
        const id = vm.visitId;
        vm.loading = true;
        HIS.get('/api/his/inp/doctor/patient/' + encodeURIComponent(id) + '/summary')
          .then(function (data) { if (String(vm.visitId) === String(id)) { vm.summary = data || null; } })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
        /* 过敏史并行加载(失败不阻断概览) */
        HIS.get('/api/his/inp/allergy/list?visitId=' + encodeURIComponent(id))
          .then(function (list) { if (String(vm.visitId) === String(id)) { vm.allergies = list || []; } })
          .catch(function () { vm.allergies = []; });
      },
      allergyTypeLabel(v) { return ALLERGY_TYPES[v] || '-'; },
      allergySeverityLabel(v) { return ALLERGY_SEVERITY[v] || '-'; },
      nursingLevelLabel(v) { return v == null ? '-' : (NURSING_LEVELS[v] || String(v)); },
      nursingLevelTag(v) { return NURSING_LEVEL_TAG[v] || 'info'; },
      conditionLevelLabel(v) { return v == null ? '-' : (CONDITION_LEVELS[v] || String(v)); },
      conditionLevelTag(v) { return CONDITION_LEVEL_TAG[v] || 'info'; },
      admitSourceLabel(v) { return v == null ? '-' : (ADMIT_SOURCES[v] || String(v)); },
      money,
      dateText,
      dateTimeText,
      admitDays,
      genderText,
      refresh() { this.load(); }
    },
    template: `
      <div class="iw-panel-body" v-loading="loading">
        <!-- 床头卡: 床位块 + 关键身份信息 -->
        <section class="iw-headcard">
          <div class="iw-headcard-bed">
            <span class="no">{{ bedLabel }}</span>
            <span class="lb">床位</span>
          </div>
          <div class="iw-headcard-main">
            <div class="iw-headcard-line1">
              <strong class="nm">{{ patientName }}</strong>
              <span class="meta">{{ gender }} · {{ age }}</span>
              <span class="iw-tag" :class="insured ? 'iw-tag--success' : 'iw-tag--plain'">{{ insured ? '医保' : '自费' }}</span>
              <span class="iw-tag" :class="Number(visit.visitStatus) === 2 ? 'iw-tag--success' : 'iw-tag--plain'">{{ statusText }}</span>
              <el-tag v-if="visit.nursingLevel != null" size="small" :type="nursingLevelTag(visit.nursingLevel)" disable-transitions>{{ nursingLevelLabel(visit.nursingLevel) }}</el-tag>
              <el-tag v-if="visit.conditionLevel != null" size="small" :type="conditionLevelTag(visit.conditionLevel)" disable-transitions>病情:{{ conditionLevelLabel(visit.conditionLevel) }}</el-tag>
              <el-tag v-if="Number(visit.isQuarantine) === 1" size="small" type="danger" effect="plain" disable-transitions>隔离</el-tag>
            </div>
            <div class="iw-headcard-line2">
              <span>住院号 <b>{{ visit.inpNo || '-' }}</b></span>
              <span>病区 {{ wardName }}</span>
              <span>入院 {{ dateText(visit.admitDate) }}</span>
              <span>在院 <b class="iw-em">{{ admittedDays }}</b> 天</span>
              <span v-if="visit.admitDiag">入院诊断 {{ visit.admitDiag }}</span>
            </div>
          </div>
          <div class="iw-headcard-right">
            <span class="lb">累计费用</span>
            <span class="iw-money">{{ money(feeTotal) }}</span>
          </div>
        </section>

        <!-- 过敏史醒目警示: 红色=有过敏记录 / 绿色=无已知过敏 -->
        <section class="iw-allergy-card" v-if="allergies.length">
          <div class="t">⚠ 过敏史警示</div>
          <div class="iw-allergy-list">
            <span class="iw-allergy-item" v-for="a in allergies" :key="a.id">
              {{ allergyTypeLabel(a.allergyType) }}过敏 · {{ a.allergenName }}（{{ allergySeverityLabel(a.severity) }}<template v-if="a.reactionDesc">：{{ a.reactionDesc }}</template>）
            </span>
          </div>
        </section>
        <section class="iw-allergy-none" v-else>✓ 无已知过敏</section>

        <!-- 联系人/担保人 | 护理·病情·饮食 -->
        <div class="iw-two-col">
          <section class="iw-card">
            <div class="iw-sect-title">联系人 / 担保人</div>
            <div class="iw-info-grid">
              <span>联系人 <b>{{ visit.contactName || '-' }}</b></span>
              <span>关系 <b>{{ visit.contactRelation || '-' }}</b></span>
              <span>电话 <b>{{ visit.contactPhone || '-' }}</b></span>
              <span>担保人 <b>{{ visit.guarantorName || '-' }}</b></span>
              <span>担保电话 <b>{{ visit.guarantorPhone || '-' }}</b></span>
              <span>证件号 <b>{{ visit.guarantorIdNo || '-' }}</b></span>
            </div>
          </section>
          <section class="iw-card">
            <div class="iw-sect-title">护理 / 病情 / 饮食</div>
            <div class="iw-info-grid">
              <span>护理等级 <el-tag size="small" :type="nursingLevelTag(visit.nursingLevel)" disable-transitions>{{ nursingLevelLabel(visit.nursingLevel) }}</el-tag></span>
              <span>病情等级 <el-tag size="small" :type="conditionLevelTag(visit.conditionLevel)" disable-transitions>{{ conditionLevelLabel(visit.conditionLevel) }}</el-tag></span>
              <span>饮食 <b>{{ visit.dietType || '-' }}</b></span>
              <span>血型 <b>{{ visit.bloodType || '-' }}</b></span>
              <span>入院来源 <b>{{ admitSourceLabel(visit.admitSource) }}</b></span>
              <span>预计出院 <b>{{ dateText(visit.expectedDischargeDate) }}</b></span>
            </div>
          </section>
        </div>

        <!-- 第二行: 诊断信息 | 医嘱统计 -->
        <div class="iw-two-col">
          <section class="iw-card">
            <div class="iw-sect-title">诊断信息 <span class="iw-count">{{ diagCount }}</span></div>
            <div class="iw-diaggroups">
              <div class="iw-dg" v-for="g in diagGroups" :key="g.type">
                <div class="iw-dg-head">{{ g.label }}<span class="iw-count" v-if="g.items.length">{{ g.items.length }}</span></div>
                <div class="iw-dg-list" v-if="g.items.length">
                  <div class="iw-dg-item" v-for="d in g.items" :key="d.id">
                    <span class="code">{{ d.diagCode || '-' }}</span>
                    <span class="name">{{ d.diagName }}</span>
                    <span class="iw-tag iw-tag--danger" v-if="Number(d.isMain) === 1">主</span>
                  </div>
                </div>
                <div class="iw-empty-line" v-else>暂无{{ g.label }}</div>
              </div>
            </div>
          </section>

          <section class="iw-card">
            <div class="iw-sect-title">医嘱统计</div>
            <div class="iw-stat-grid">
              <div class="iw-stat"><span class="v">{{ stats.total || 0 }}</span><span class="l">合计</span></div>
              <div class="iw-stat"><span class="v">{{ stats.longTerm || 0 }}</span><span class="l">长期</span></div>
              <div class="iw-stat"><span class="v">{{ stats.temp || 0 }}</span><span class="l">临时</span></div>
              <div class="iw-stat iw-stat--hl"><span class="v">{{ stats.active || 0 }}</span><span class="l">在执行</span></div>
            </div>
            <div class="iw-chiprow">
              <span class="iw-chip" v-for="c in statusChips" :key="c.key" :class="{'is-zero': !c.value}">{{ c.label }} {{ c.value }}</span>
            </div>
            <div class="iw-empty-line" v-if="stats.groupCount">成组医嘱 {{ stats.groupCount }} 组</div>
          </section>
        </div>

        <!-- 第三行: 费用汇总 -->
        <section class="iw-card">
          <div class="iw-sect-title">费用汇总</div>
          <div class="iw-charge-sum">
            <span class="item">总费用 <b class="iw-money">{{ money(feeTotal) }}</b></span>
            <span class="item">预交金余额 <b :class="Number(fee.depositBalance) < 0 ? 'iw-money iw-money--danger' : 'iw-money'">{{ money(fee.depositBalance) }}</b></span>
            <span class="item">有效明细 <b>{{ fee.chargeDetailCount || 0 }}</b> 条</span>
            <span class="item">已退费 <b>{{ fee.refundedCount || 0 }}</b> 条</span>
          </div>
          <table class="iw-table" v-if="feeRows.length">
            <thead><tr><th style="width:160px">费用类别</th><th style="width:180px">金额(元)</th><th>占比</th></tr></thead>
            <tbody>
              <tr v-for="r in feeRows" :key="r.type">
                <td>{{ r.label }}</td>
                <td class="iw-money">{{ money(r.amount) }}</td>
                <td>
                  <div class="iw-bar"><div class="iw-bar-inner" :style="{width: feeTotal ? Math.min(100, (r.amount / feeTotal * 100)).toFixed(1) + '%' : '0%'}"></div></div>
                </td>
              </tr>
              <tr class="iw-table-total"><td>合计</td><td class="iw-money">{{ money(feeTotal) }}</td><td></td></tr>
            </tbody>
          </table>
          <div class="iw-empty-line" v-else>暂无费用明细</div>
        </section>
      </div>
    `
  };

  /* ================= 费用页签面板 ================= */
  const InpChargeOverview = {
    name: 'InpChargeOverview',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return { loading: false, fee: {}, visit: {} };
    },
    computed: {
      rows() {
        const by = this.fee.byFeeType || {};
        return Object.keys(by).map(k => ({ type: k, label: FEE_TYPE[k] || ('类别' + k), amount: Number(by[k]) || 0 }))
          .sort((a, b) => b.amount - a.amount);
      },
      total() { return Number(this.fee.totalCost != null ? this.fee.totalCost : this.visit.totalCost) || 0; },
      balance() { return Number(this.fee.depositBalance != null ? this.fee.depositBalance : this.visit.depositBalance) || 0; }
    },
    watch: {
      visitId: { immediate: true, handler() { this.load(); } }
    },
    methods: {
      load() {
        const vm = this;
        if (!vm.visitId) { return; }
        vm.loading = true;
        HIS.get('/api/his/inp/doctor/patient/' + encodeURIComponent(vm.visitId) + '/summary')
          .then(function (data) {
            vm.visit = (data && data.visit) || {};
            vm.fee = (data && data.feeSummary) || {};
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      money,
      dateText,
      refresh() { this.load(); }
    },
    template: `
      <div class="iw-panel-body" v-loading="loading">
        <div class="iw-charge-cards">
          <div class="iw-stat iw-stat--big"><span class="v iw-money">{{ money(total) }}</span><span class="l">累计总费用</span></div>
          <div class="iw-stat iw-stat--big" :class="{'iw-stat--danger': balance < 0}"><span class="v">{{ money(balance) }}</span><span class="l">预交金余额</span></div>
          <div class="iw-stat iw-stat--big"><span class="v">{{ fee.chargeDetailCount || 0 }}</span><span class="l">有效费用明细</span></div>
          <div class="iw-stat iw-stat--big"><span class="v">{{ fee.refundedCount || 0 }}</span><span class="l">已退费记录</span></div>
        </div>

        <section class="iw-card">
          <div class="iw-sect-title">费用类别明细
            <el-button link size="small" style="margin-left:auto" @click="refresh">刷新</el-button>
          </div>
          <table class="iw-table iw-table--wide" v-if="rows.length">
            <thead><tr><th style="width:180px">费用类别</th><th style="width:220px">金额(元)</th><th>占比</th></tr></thead>
            <tbody>
              <tr v-for="r in rows" :key="r.type">
                <td>{{ r.label }}</td>
                <td class="iw-money">{{ money(r.amount) }}</td>
                <td>
                  <div class="iw-bar"><div class="iw-bar-inner" :style="{width: total ? Math.min(100, (r.amount / total * 100)).toFixed(1) + '%' : '0%'}"></div></div>
                  <span class="iw-pct">{{ total ? (r.amount / total * 100).toFixed(1) : '0.0' }}%</span>
                </td>
              </tr>
              <tr class="iw-table-total"><td>合计</td><td class="iw-money">{{ money(total) }}</td><td></td></tr>
            </tbody>
          </table>
          <div class="iw-empty-line" v-else>暂无费用明细(临时医嘱开立即记账, 长期医嘱由执行环节逐日生成)</div>
        </section>
        <div class="iw-hint">费用口径: 明细状态正常(1)计入; 作废医嘱的记账明细已冲销(状态2)不再计入。</div>
      </div>
    `
  };

  HIS.components.InpPatientOverview = InpPatientOverview;
  HIS.components.InpChargeOverview = InpChargeOverview;
})();
