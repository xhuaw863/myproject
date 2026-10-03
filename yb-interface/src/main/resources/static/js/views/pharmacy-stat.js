/* ============================================================================
 * 药房统计查询增强(2026-11 药房整合): 药品消耗分析 / 医保合规分析 / 处方与退药质量 三报表视图。
 * 后端 PharmacyStatController(/api/his/pharmacy/stat/*), 全部只读聚合 + EasyExcel 导出。
 * 发药工作量总览沿用 report-biz.js 的 PharmacyReport(药房统计), 本文件补齐另三维。
 * 口径与金额单位(元)同后端; 区间留空后端回退最近30天; 机构隔离由后端 guard.scopeOrgId 强制。
 * 视图 HIS.views 注册键须与 RbacInitializer 菜单 comp 同值: PharmacyUsageStat/PharmacyYbStat/PharmacyQualityStat。
 * ========================================================================== */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ---------- 共用工具 ---------- */
  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return fmtDate(d); }
  function money(n) { return (n === null || n === undefined) ? '0.00' : Number(n).toFixed(2); }
  function num(n) { return (n === null || n === undefined) ? '0' : String(n); }
  function pctNum(p) { var v = parseFloat(p); return isNaN(v) ? 0 : v; }
  /* abc 着色: A=成功 B=警告 C=危险 */
  function abcType(c) { return c === 'A' ? 'success' : (c === 'B' ? 'warning' : 'info'); }

  /* 药房下拉 mixin(三视图共用): 当前机构启用药房 */
  var pharmacyDefMixin = {
    methods: {
      loadPharmacyDefs: function () {
        var vm = this;
        HIS.get('/api/his/pharmacy/pharmacy-def').then(function (d) {
          vm.pharmacyDefs = d || [];
        }).catch(function () { vm.pharmacyDefs = []; });
      }
    }
  };

  function buildQuery(vm) {
    var p = [];
    if (vm.pharmacyId) { p.push('pharmacyId=' + vm.pharmacyId); }
    if (vm.dateRange && vm.dateRange[0]) { p.push('startDate=' + vm.dateRange[0]); }
    if (vm.dateRange && vm.dateRange[1]) { p.push('endDate=' + vm.dateRange[1]); }
    return p.length ? '?' + p.join('&') : '';
  }

  /* ================= 药品消耗分析 ================= */
  HIS.views.PharmacyUsageStat = {
    mixins: [pharmacyDefMixin],
    data: function () {
      return {
        loading: false, exporting: false,
        pharmacyDefs: [], pharmacyId: '',
        dateRange: [daysAgo(29), today()],
        dimType: 'major_class',
        dims: [
          { value: 'generic', label: '通用名' },
          { value: 'major_class', label: '大类' },
          { value: 'manufacturer', label: '厂家' },
          { value: 'dept', label: '科室' }
        ],
        summary: {}, dimRows: [], detailRows: []
      };
    },
    created: function () { this.loadPharmacyDefs(); this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        var q = buildQuery(vm);
        var qDim = q + (q ? '&' : '?') + 'dimType=' + vm.dimType + '&limit=50';
        var qDetail = q + (q ? '&' : '?') + 'topN=50';
        Promise.all([
          HIS.get('/api/his/pharmacy/stat/usage/summary' + q),
          HIS.get('/api/his/pharmacy/stat/usage/dim' + qDim),
          HIS.get('/api/his/pharmacy/stat/usage/detail' + qDetail)
        ]).then(function (rs) {
          vm.summary = rs[0] || {};
          vm.dimRows = rs[1] || [];
          vm.detailRows = rs[2] || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.load(); },
      doExport: function () {
        var vm = this; vm.exporting = true;
        var q = buildQuery(vm) + (vm.querySep() ? '&' : '?') + 'dimType=' + vm.dimType;
        HIS.download('/api/his/pharmacy/stat/usage/export' + q, '药品消耗分析.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      querySep: function () { return buildQuery(this).length > 0; },
      money: money, num: num, pctNum: pctNum, abcType: abcType
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">药品消耗分析 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(门诊+住院发药口径, 金额单位: 元; ABC 按累计金额 80/95 划分)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable filterable style="width:160px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-select v-model="dimType" style="width:120px" @change="search">',
      '      <el-option v-for="d in dims" :key="d.value" :label="d.label" :value="d.value"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '  </div>',
      '  <div class="stat-grid" v-loading="loading">',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-gold);">¥ {{ money(summary.totalAmount) }}</div><div class="lbl">消耗总金额</div></div>',
      '    <div class="stat-card"><div class="num">{{ num(summary.totalQty) }}</div><div class="lbl">净发放数量</div></div>',
      '    <div class="stat-card"><div class="num">{{ num(summary.drugCount) }}</div><div class="lbl">消耗品种数</div></div>',
      '    <div class="stat-card"><div class="num">¥ {{ money(summary.outpAmount) }}</div><div class="lbl">门诊消耗</div></div>',
      '    <div class="stat-card"><div class="num">¥ {{ money(summary.inpAmount) }}</div><div class="lbl">住院消耗</div></div>',
      '    <div class="stat-card"><div class="num" :style="summary.growthRate &amp;&amp; summary.growthRate.indexOf(\'-\')===0 ? \'color:var(--yb-success)\' : \'color:var(--yb-danger)\'">{{ summary.growthRate || \'0.0%\' }}</div><div class="lbl">环比上一周期</div></div>',
      '  </div>',
      '  <el-row :gutter="16">',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">维度消耗排行(ABC)</span></template>',
      '        <el-table :data="dimRows" v-loading="loading" border stripe size="small" max-height="440" empty-text="暂无数据">',
      '          <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="qty" label="净数量" width="90" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="120" align="right" header-align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '          <el-table-column prop="pct" label="占比" width="80" align="right" header-align="right"></el-table-column>',
      '          <el-table-column prop="cumPct" label="累计" width="80" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="ABC" width="70" align="center"><template #default="s">',
      '            <el-tag :type="abcType(s.row.abcClass)" size="small">{{ s.row.abcClass }}</el-tag>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">Top 消耗明细(通用名·大类·厂家)</span></template>',
      '        <el-table :data="detailRows" v-loading="loading" border stripe size="small" max-height="440" empty-text="暂无数据">',
      '          <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '          <el-table-column prop="genericName" label="通用名" min-width="130" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="majorClass" label="大类" width="90"></el-table-column>',
      '          <el-table-column prop="manufacturer" label="厂家" min-width="120" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="qty" label="净数量" width="80" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="110" align="right" header-align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      '</div>'
    ].join('\n')
  };

  /* ================= 医保合规分析 ================= */
  HIS.views.PharmacyYbStat = {
    mixins: [pharmacyDefMixin],
    data: function () {
      return {
        loading: false, exporting: false,
        pharmacyDefs: [], pharmacyId: '',
        dateRange: [daysAgo(29), today()],
        s: {}, abx: [], lv: []
      };
    },
    created: function () { this.loadPharmacyDefs(); this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/stat/yb/summary' + buildQuery(vm)).then(function (d) {
          vm.s = d || {};
          vm.abx = d.abxDist || [];
          vm.lv = d.chrgitmLvDist || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.load(); },
      doExport: function () {
        var vm = this; vm.exporting = true;
        HIS.download('/api/his/pharmacy/stat/yb/export' + buildQuery(vm), '医保合规分析.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      money: money, num: num, pctNum: pctNum
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">医保合规分析 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按消耗金额加权, 门诊+住院合并口径; 集采占比因缺稳定批次-供应商链路本期未纳入)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable filterable style="width:160px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '  </div>',
      '  <div class="stat-grid" v-loading="loading">',
      '    <div class="stat-card"><div class="num">{{ s.essentialRatio || \'0.0%\' }}</div><div class="lbl">基本药物占比</div></div>',
      '    <div class="stat-card"><div class="num">{{ s.ybMatchRatio || \'0.0%\' }}</div><div class="lbl">医保目录对账率</div></div>',
      '    <div class="stat-card"><div class="num">{{ s.zeroMarginRatio || \'0.0%\' }}</div><div class="lbl">零差率药品占比</div></div>',
      '    <div class="stat-card"><div class="num">{{ s.avgSelfpayProp || \'0.0%\' }}</div><div class="lbl">平均自付比例</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-warning);">{{ s.drugCostRatio || \'0.0%\' }}</div><div class="lbl">药占比(药品/医药总费)</div></div>',
      '    <div class="stat-card"><div class="num">¥ {{ money(s.avgPerVisit) }}</div><div class="lbl">门诊次均费用</div></div>',
      '  </div>',
      '  <el-row :gutter="16">',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">抗菌药物供应分级分布</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">非限制/限制/特殊使用级</span></template>',
      '        <el-table :data="abx" v-loading="loading" border stripe size="small" max-height="360" empty-text="暂无抗菌药物数据">',
      '          <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '          <el-table-column prop="grade" label="分级" min-width="120"></el-table-column>',
      '          <el-table-column prop="qty" label="净数量" width="90" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="120" align="right" header-align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '          <el-table-column label="占比" min-width="150"><template #default="s">',
      '            <el-progress :percentage="pctNum(s.row.pct)" :stroke-width="12" text-inside></el-progress>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="12">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">医保甲乙丙结构分布</span></template>',
      '        <el-table :data="lv" v-loading="loading" border stripe size="small" max-height="360" empty-text="暂无数据">',
      '          <el-table-column type="index" label="序号" width="55"></el-table-column>',
      '          <el-table-column prop="level" label="类别" min-width="100"></el-table-column>',
      '          <el-table-column prop="qty" label="净数量" width="90" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="120" align="right" header-align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '          <el-table-column label="占比" min-width="150"><template #default="s">',
      '            <el-progress :percentage="pctNum(s.row.pct)" :stroke-width="12" text-inside></el-progress>',
      '          </template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      '</div>'
    ].join('\n')
  };

  /* ================= 处方与退药质量 ================= */
  HIS.views.PharmacyQualityStat = {
    mixins: [pharmacyDefMixin],
    data: function () {
      return {
        loading: false, exporting: false,
        pharmacyDefs: [], pharmacyId: '',
        dateRange: [daysAgo(29), today()],
        s: {}, rejectReasons: [], returnReasons: [], rxReview: []
      };
    },
    created: function () { this.loadPharmacyDefs(); this.load(); },
    methods: {
      load: function () {
        var vm = this; vm.loading = true;
        HIS.get('/api/his/pharmacy/stat/quality/summary' + buildQuery(vm)).then(function (d) {
          vm.s = d || {};
          vm.rejectReasons = d.rejectReasons || [];
          vm.returnReasons = d.returnReasons || [];
          vm.rxReview = d.rxReview || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.load(); },
      doExport: function () {
        var vm = this; vm.exporting = true;
        HIS.download('/api/his/pharmacy/stat/quality/export' + buildQuery(vm), '处方与退药质量.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      money: money, num: num, pctNum: pctNum
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">处方与退药质量 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(审方通过率按处方开立时间; 退药按已审批退药, 金额单位: 元)</span></div>',
      '  <div class="toolbar">',
      '    <el-select v-model="pharmacyId" placeholder="全部药房" clearable filterable style="width:160px" @change="search">',
      '      <el-option v-for="p in pharmacyDefs" :key="p.id" :label="p.name" :value="p.id"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '    <el-button type="primary" @click="search">查询</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="doExport">导出Excel</el-button>',
      '  </div>',
      '  <div class="stat-grid" v-loading="loading">',
      '    <div class="stat-card"><div class="num">{{ num(s.rxTotal) }}</div><div class="lbl">处方总数</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-success);">{{ num(s.auditPass) }}</div><div class="lbl">审方通过</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-danger);">{{ num(s.auditReject) }}</div><div class="lbl">审方驳回</div></div>',
      '    <div class="stat-card"><div class="num">{{ s.passRate || \'0.0%\' }}</div><div class="lbl">审方通过率</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-warning);">{{ num(s.returnCount) }}</div><div class="lbl">退药单数</div></div>',
      '    <div class="stat-card"><div class="num" style="color:var(--yb-danger);">{{ s.returnRate || \'0.0%\' }}</div><div class="lbl">退药率(退药/发药)</div></div>',
      '  </div>',
      '  <el-row :gutter="16">',
      '    <el-col :span="8">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">审方驳回原因</span></template>',
      '        <el-table :data="rejectReasons" v-loading="loading" border stripe size="small" max-height="360" empty-text="暂无驳回记录">',
      '          <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="cnt" label="次数" width="80" align="right" header-align="right"></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="8">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">退药原因分布</span></template>',
      '        <el-table :data="returnReasons" v-loading="loading" border stripe size="small" max-height="360" empty-text="暂无退药记录">',
      '          <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="cnt" label="次数" width="60" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="100" align="right" header-align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '    <el-col :span="8">',
      '      <el-card shadow="never">',
      '        <template #header><span style="font-weight:600;font-size:14px;color:var(--yb-ink-1);">处方点评抽样 Top</span>',
      '          <span style="font-size:12px;color:var(--yb-ink-2);margin-left:8px;">按金额降序</span></template>',
      '        <el-table :data="rxReview" v-loading="loading" border stripe size="small" max-height="360" empty-text="暂无处方">',
      '          <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '          <el-table-column prop="deptName" label="科室" width="90" show-overflow-tooltip></el-table-column>',
      '          <el-table-column prop="itemCount" label="项数" width="60" align="right" header-align="right"></el-table-column>',
      '          <el-table-column label="金额" width="90" align="right" header-align="right"><template #default="s">{{ money(s.row.totalAmount) }}</template></el-table-column>',
      '        </el-table>',
      '      </el-card>',
      '    </el-col>',
      '  </el-row>',
      '</div>'
    ].join('\n')
  };
})();
