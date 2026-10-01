/* 医生工作日志: 按医师汇总工作量(接诊/完成/处方/检查单量与金额) + 接诊明细分页 + 导出Excel/打印
 * 口径(与后端 ReportService 一致): 就诊排除已取消(visit_status=4); 处方/检查单仅统计有效单(status>0); 金额单位: 元 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 共用工具(同 report.js) ================= */
  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) {
    var d = new Date();
    d.setDate(d.getDate() - n);
    return fmtDate(d);
  }
  function money(n) { return (n === null || n === undefined) ? '0.00' : Number(n).toFixed(2); }

  /* ================= 医生工作日志 ================= */
  HIS.views.DoctorWorklog = {
    data: function () {
      return {
        loading: false, exporting: false,
        /* summary 汇总统计 | detail 接诊明细 */
        activeTab: 'summary',
        dateRange: [daysAgo(6), today()],   /* 默认最近7天 */
        deptId: '',                         /* 科室过滤(下拉取自启用科室, 后端读隔离) */
        staffId: '',                        /* 医生过滤(下拉取自汇总结果提取) */
        keyword: '',                        /* 患者姓名, 仅明细Tab */
        /* 汇总数据(不分页, 按接诊数降序) */
        summaryList: [],
        /* 明细数据(分页) */
        detailList: [], detailTotal: 0, detailPage: 1, detailSize: 20,
        /* 下拉选项 */
        deptOptions: [],
        staffOptions: []
      };
    },
    computed: {
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; }
    },
    created: function () {
      this.loadDepts();
      this.loadSummary();
    },
    methods: {
      /* ---- 科室下拉: 启用科室(后端 scopeOrgId 读隔离, 非牵头机构强制本院, 牵头可见全医共体) ---- */
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (d) {
          vm.deptOptions = (d || []).map(function (x) { return { id: x.id, name: x.deptName }; });
        }).catch(function () { vm.deptOptions = []; });
      },
      /* ---- 医生下拉: 从汇总结果提取并累计去重(历史见过的医生保持可选, 避免选定科室过滤后选项收窄无法换选) ---- */
      mergeStaffOptions: function (rows) {
        var vm = this;
        var seen = {};
        (vm.staffOptions || []).forEach(function (s) { seen[s.id] = true; });
        (rows || []).forEach(function (r) {
          if (r.staffId == null || seen[r.staffId]) { return; }
          seen[r.staffId] = true;
          vm.staffOptions.push({ id: r.staffId, name: r.drName });
        });
        vm.staffOptions.sort(function (a, b) { return String(a.name).localeCompare(String(b.name), 'zh-Hans-CN'); });
      },
      /* ---- 汇总统计: 按医师聚合(接诊数降序, 完成率后端返回"XX.X%") ---- */
      loadSummary: function () {
        var vm = this; vm.loading = true;
        var p = [];
        if (vm.startDate) { p.push('startDate=' + vm.startDate); }
        if (vm.endDate) { p.push('endDate=' + vm.endDate); }
        if (vm.staffId) { p.push('staffId=' + vm.staffId); }
        if (vm.deptId) { p.push('deptId=' + vm.deptId); }
        HIS.get('/api/his/report/doctor-worklog' + (p.length ? '?' + p.join('&') : ''))
          .then(function (list) {
            vm.summaryList = list || [];
            vm.mergeStaffOptions(vm.summaryList);
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      /* ---- 接诊明细: 分页(keyword 匹配患者姓名) ---- */
      loadDetail: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/report/doctor-worklog-detail?page=' + vm.detailPage + '&size=' + vm.detailSize;
        if (vm.startDate) { q += '&startDate=' + vm.startDate; }
        if (vm.endDate) { q += '&endDate=' + vm.endDate; }
        if (vm.staffId) { q += '&staffId=' + vm.staffId; }
        if (vm.deptId) { q += '&deptId=' + vm.deptId; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.detailList = (d && d.records) || [];
          vm.detailTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () {
        if (this.activeTab === 'summary') { this.loadSummary(); }
        else { this.detailPage = 1; this.loadDetail(); }
      },
      reset: function () {
        this.dateRange = [daysAgo(6), today()];
        this.deptId = ''; this.staffId = ''; this.keyword = '';
        this.search();
      },
      onTabChange: function (tab) {
        if (tab === 'summary') { this.loadSummary(); }
        else { this.loadDetail(); }
      },
      onPage: function (p) { this.detailPage = p; this.loadDetail(); },
      onSize: function (s) { this.detailSize = s; this.detailPage = 1; this.loadDetail(); },
      /* ---- 导出Excel(xlsx 双Sheet: 工作量汇总+接诊明细, 与列表同区间/过滤口径, 明细全量上限10000行) ---- */
      exportExcel: function () {
        var vm = this; vm.exporting = true;
        var p = [];
        if (vm.startDate) { p.push('startDate=' + vm.startDate); }
        if (vm.endDate) { p.push('endDate=' + vm.endDate); }
        if (vm.staffId) { p.push('staffId=' + vm.staffId); }
        if (vm.deptId) { p.push('deptId=' + vm.deptId); }
        HIS.download('/api/his/report/export/doctor-worklog' + (p.length ? '?' + p.join('&') : ''), '医生工作日志.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出: ' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },
      print: function () { window.print(); },
      /* ---- 汇总合计行: 数量/金额列求和, 完成率按总完成数/总接诊数重算 ---- */
      summaryMethod: function (param) {
        var agg = { visitCount: 0, finishCount: 0, rxCount: 0, rxAmount: 0, orderCount: 0, orderAmount: 0 };
        (param.data || []).forEach(function (r) {
          agg.visitCount += Number(r.visitCount) || 0;
          agg.finishCount += Number(r.finishCount) || 0;
          agg.rxCount += Number(r.rxCount) || 0;
          agg.rxAmount += Number(r.rxAmount) || 0;
          agg.orderCount += Number(r.orderCount) || 0;
          agg.orderAmount += Number(r.orderAmount) || 0;
        });
        var rate = agg.visitCount > 0 ? (agg.finishCount / agg.visitCount * 100).toFixed(1) + '%' : '0.0%';
        return (param.columns || []).map(function (col, idx) {
          if (idx === 0) { return '合计'; }
          switch (col.property) {
            case 'visitCount': case 'finishCount': case 'rxCount': case 'orderCount':
              return String(agg[col.property]);
            case 'rxAmount': case 'orderAmount':
              return money(agg[col.property]);
            case 'finishRate':
              return rate;
            default:
              return '';
          }
        });
      },
      /* ---- 明细格式化 ---- */
      money: money,
      genderLabel: function (v) {
        var s = (v === null || v === undefined) ? '' : String(v);
        if (s === '1') { return '男'; }
        if (s === '2') { return '女'; }
        return s || '-';
      },
      statusLabel: function (v) {
        var n = Number(v);
        if (n === 1) { return '候诊'; }
        if (n === 2) { return '接诊中'; }
        if (n === 3) { return '已完成'; }
        return '-';
      },
      statusTag: function (v) {
        var n = Number(v);
        if (n === 2) { return 'warning'; }
        if (n === 3) { return 'success'; }
        return 'info';
      }
    },
    template: [
      '<div class="doctor-worklog-wrap cd-fill">',
      '  <div class="page-card cd-grow">',
      '    <div class="page-title">医生工作日志 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(按医师汇总接诊/完成/处方/检查工作量, 金额单位: 元)</span></div>',
      /* 打印标题(仅打印时显示, 屏显时隐藏) */
      '    <div class="doctor-worklog-print-title">医生工作日志</div>',
      '    <div class="doctor-worklog-print-sub">统计区间: {{ startDate || \'-\' }} 至 {{ endDate || \'-\' }} · {{ activeTab===\'summary\' ? (\'工作量汇总, 共 \' + summaryList.length + \' 名医生\') : (\'接诊明细, 共 \' + detailTotal + \' 条记录\') }}</div>',
      /* 查询栏(打印时隐藏) */
      '    <div class="toolbar doctor-worklog-toolbar">',
      '      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '        start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" @change="search"></el-date-picker>',
      '      <el-select v-model="deptId" placeholder="全部科室" clearable filterable style="width:170px" @change="search">',
      '        <el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id"></el-option>',
      '      </el-select>',
      '      <el-select v-model="staffId" placeholder="全部医生" clearable filterable style="width:150px" @change="search">',
      '        <el-option v-for="s in staffOptions" :key="s.id" :label="s.name" :value="s.id"></el-option>',
      '      </el-select>',
      '      <el-input v-show="activeTab===\'detail\'" v-model="keyword" placeholder="患者姓名" clearable style="width:170px" @keyup.enter="search"></el-input>',
      '      <el-button type="primary" @click="search">查询</el-button>',
      '      <el-button @click="reset">重置</el-button>',
      '      <el-button @click="print">打印</el-button>',
      '      <el-button type="success" plain :loading="exporting" @click="exportExcel">导出Excel</el-button>',
      '      <span style="margin-left:auto;color:var(--yb-ink-2);font-size:13px;">{{ activeTab===\'summary\' ? (\'共 \' + summaryList.length + \' 名医生\') : (\'共 \' + detailTotal + \' 条记录\') }}</span>',
      '    </div>',
      /* Tab切换(打印时隐藏) */
      '    <div class="doctor-worklog-tabs" style="margin-bottom:12px;">',
      '      <el-radio-group v-model="activeTab" @change="onTabChange">',
      '        <el-radio-button label="summary">汇总统计</el-radio-button>',
      '        <el-radio-button label="detail">接诊明细</el-radio-button>',
      '      </el-radio-group>',
      '    </div>',
      /* 汇总统计表(带合计行) */
      '    <el-table v-show="activeTab===\'summary\'" :data="summaryList" v-loading="loading" border stripe size="small" height="100%"',
      '      show-summary :summary-method="summaryMethod">',
      '      <el-table-column type="index" label="序号" width="60"></el-table-column>',
      '      <el-table-column prop="drName" label="医生" min-width="90"></el-table-column>',
      '      <el-table-column prop="deptName" label="科室" min-width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="visitCount" label="接诊数" width="80" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="finishCount" label="完成数" width="80" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="finishRate" label="完成率" width="80" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="rxCount" label="处方数" width="80" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="rxAmount" label="处方金额" width="100" align="right" header-align="right"><template #default="s">',
      '        <b style="color:var(--yb-ink-1);">{{ money(s.row.rxAmount) }}</b>',
      '      </template></el-table-column>',
      '      <el-table-column prop="orderCount" label="检查单数" width="84" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="orderAmount" label="检查金额" width="100" align="right" header-align="right"><template #default="s">',
      '        <b style="color:var(--yb-ink-1);">{{ money(s.row.orderAmount) }}</b>',
      '      </template></el-table-column>',
      '    </el-table>',
      /* 接诊明细表(分页, 序号跨页连续) */
      '    <el-table v-show="activeTab===\'detail\'" :data="detailList" v-loading="loading" border stripe size="small" height="100%">',
      '      <el-table-column type="index" label="序号" width="60" :index="(detailPage-1)*detailSize+1"></el-table-column>',
      '      <el-table-column prop="workDate" label="日期" width="100"></el-table-column>',
      '      <el-table-column prop="patientName" label="患者" width="80"></el-table-column>',
      '      <el-table-column label="性别" width="50" align="center"><template #default="s">{{ genderLabel(s.row.gender) }}</template></el-table-column>',
      '      <el-table-column prop="age" label="年龄" width="50" align="center"></el-table-column>',
      '      <el-table-column prop="chiefComplaint" label="主诉" min-width="160" show-overflow-tooltip><template #default="s">{{ s.row.chiefComplaint || \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="drName" label="医生" width="80"></el-table-column>',
      '      <el-table-column prop="deptName" label="科室" width="100" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="状态" width="80" align="center"><template #default="s">',
      '        <el-tag size="small" :type="statusTag(s.row.visitStatus)">{{ statusLabel(s.row.visitStatus) }}</el-tag>',
      '      </template></el-table-column>',
      '      <el-table-column prop="visitTime" label="接诊时间" width="70" align="center"><template #default="s">{{ s.row.visitTime || \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="finishTime" label="完成时间" width="70" align="center"><template #default="s">{{ s.row.finishTime || \'-\' }}</template></el-table-column>',
      '      <el-table-column prop="rxCount" label="处方数" width="60" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="rxAmount" label="处方金额" width="90" align="right" header-align="right"><template #default="s">{{ money(s.row.rxAmount) }}</template></el-table-column>',
      '      <el-table-column prop="orderCount" label="检查数" width="60" align="right" header-align="right"></el-table-column>',
      '      <el-table-column prop="orderAmount" label="检查金额" width="90" align="right" header-align="right"><template #default="s">{{ money(s.row.orderAmount) }}</template></el-table-column>',
      '    </el-table>',
      '    <el-pagination v-show="activeTab===\'detail\'" style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '      :total="detailTotal" :page-size="detailSize" :page-sizes="[20, 50, 100]" :current-page="detailPage"',
      '      @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      /* 打印样式: his.css 全局打印规则隐藏了 body 全部元素(visibility), 此处显式恢复本组件可见;
       * 工具栏/Tab切换/分页/屏显标题打印时隐藏, 换为居中打印标题; 仅打印当前Tab的表格(v-show 隐藏的不打印) */
      '  <style>',
      '    .doctor-worklog-print-title, .doctor-worklog-print-sub { display: none; }',
      '    @media print {',
      '      body * { visibility: hidden; }',
      '      .doctor-worklog-wrap, .doctor-worklog-wrap * { visibility: visible !important; }',
      '      .doctor-worklog-wrap { position: absolute; left: 0; top: 0; width: 100%; }',
      '      .doctor-worklog-wrap .page-title,',
      '      .doctor-worklog-toolbar,',
      '      .doctor-worklog-tabs,',
      '      .doctor-worklog-wrap .el-radio-group,',
      '      .doctor-worklog-wrap .el-pagination { display: none !important; }',
      '      .doctor-worklog-print-title { display: block !important; text-align: center; font-size: 18px; font-weight: bold; margin-bottom: 6px; }',
      '      .doctor-worklog-print-sub { display: block !important; text-align: center; font-size: 12px; color: var(--yb-ink-2); font-weight: normal; margin-bottom: 16px; }',
      '    }',
      '  </style>',
      '</div>'
    ].join('\n')
  };
})();
