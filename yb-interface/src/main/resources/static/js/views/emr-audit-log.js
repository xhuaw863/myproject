/* 病历审计日志查询页(病历P2): 操作审计全量分页检索, 仅牵头机构管理员可用。
 * 接口: GET /api/his/emr/audit/page?startTime=&endTime=&action=&operatorId=&page=&size=
 * 注册: HIS.views.EmrAuditLog (app.js 按 comp 名挂载); 样式前缀 eal-; 无构建 IIFE。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var ACTION_OPTIONS = [
    { label: '全部', value: '' },
    { label: '创建', value: 'CREATE' },
    { label: '修改', value: 'UPDATE' },
    { label: '查看', value: 'VIEW' },
    { label: '打印', value: 'PRINT' },
    { label: '签名', value: 'SIGN' },
    { label: '删除', value: 'DELETE' },
    { label: '提交', value: 'SUBMIT' },
    { label: '审核', value: 'AUDIT' }
  ];

  var ACTION_MAP = {};
  ACTION_OPTIONS.forEach(function (o) { if (o.value) { ACTION_MAP[o.value] = o.label; } });

  var SCOPE_MAP = { 1: '住院', 2: '门诊' };

  /* ===== 样式注入 ===== */
  if (typeof HIS.injectStyle === 'function') {
    HIS.injectStyle('emr-audit-log-style', [
      '.eal-root { display:flex; flex-direction:column; height:100%; padding:14px 16px; box-sizing:border-box; overflow:hidden; }',
      '.eal-filter { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-bottom:12px; }',
      '.eal-table { flex:1; min-height:0; overflow:hidden; }',
      '.eal-pager { flex:none; display:flex; justify-content:flex-end; padding:8px 0 0; }'
    ].join('\n'));
  } else {
    (function () {
      if (document.getElementById('emr-audit-log-style')) { return; }
      var st = document.createElement('style');
      st.id = 'emr-audit-log-style';
      st.textContent = [
        '.eal-root { display:flex; flex-direction:column; height:100%; padding:14px 16px; box-sizing:border-box; overflow:hidden; }',
        '.eal-filter { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-bottom:12px; }',
        '.eal-table { flex:1; min-height:0; overflow:hidden; }',
        '.eal-pager { flex:none; display:flex; justify-content:flex-end; padding:8px 0 0; }'
      ].join('\n');
      document.head.appendChild(st);
    })();
  }

  HIS.views.EmrAuditLog = {
    name: 'EmrAuditLog',
    data: function () {
      return {
        actionOptions: ACTION_OPTIONS,
        loading: false,
        dateRange: [],
        action: '',
        operatorId: '',
        rows: [],
        total: 0,
        page: 1,
        size: 20
      };
    },
    mounted: function () {
      this.query();
    },
    methods: {
      query: function () {
        var vm = this;
        vm.loading = true;
        var params = 'page=' + vm.page + '&size=' + vm.size;
        if (vm.dateRange && vm.dateRange.length === 2) {
          if (vm.dateRange[0]) { params += '&startTime=' + encodeURIComponent(vm.formatDate(vm.dateRange[0])); }
          if (vm.dateRange[1]) { params += '&endTime=' + encodeURIComponent(vm.formatDate(vm.dateRange[1])); }
        }
        if (vm.action) { params += '&action=' + encodeURIComponent(vm.action); }
        var opId = String(vm.operatorId || '').trim();
        if (opId) { params += '&operatorId=' + encodeURIComponent(opId); }
        HIS.get('/api/his/emr/audit/page?' + params)
          .then(function (data) {
            vm.rows = (data && data.records) || [];
            vm.total = Number(data && data.total) || 0;
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      reset: function () {
        this.dateRange = [];
        this.action = '';
        this.operatorId = '';
        this.page = 1;
        this.query();
      },
      handlePageChange: function (p) { this.page = p; this.query(); },
      handleSizeChange: function (s) { this.size = s; this.page = 1; this.query(); },
      formatDate: function (d) {
        if (!d) { return ''; }
        var dt = d instanceof Date ? d : new Date(d);
        var m = dt.getMonth() + 1;
        var day = dt.getDate();
        return dt.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
      },
      actionLabel: function (v) { return ACTION_MAP[v] || v || '-'; },
      scopeLabel: function (v) { return SCOPE_MAP[v] || String(v || '-'); },
      fmtTime: function (v) {
        if (!v) { return '-'; }
        return String(v).replace('T', ' ').substring(0, 19);
      },
      fmtId: function (v) { return v != null ? String(v) : '-'; },
      indexMethod: function (idx) { return (this.page - 1) * this.size + idx + 1; }
    },
    template: [
      '<div class="eal-root">',
      '  <div class="eal-filter">',
      '    <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width:280px;" size="default"></el-date-picker>',
      '    <el-select v-model="action" placeholder="操作类型" clearable style="width:120px;" size="default">',
      '      <el-option v-for="o in actionOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-input v-model="operatorId" placeholder="操作人ID" clearable style="width:140px;" size="default"></el-input>',
      '    <el-button type="primary" size="default" @click="page=1;query()">查询</el-button>',
      '    <el-button size="default" @click="reset">重置</el-button>',
      '  </div>',
      '  <div class="eal-table">',
      '    <el-table :data="rows" v-loading="loading" border stripe height="100%" style="width:100%;">',
      '      <el-table-column label="序号" width="60" align="center" :index="indexMethod" type="index"></el-table-column>',
      '      <el-table-column label="操作时间" width="170" prop="createTime" :formatter="(r)=>fmtTime(r.createTime)"></el-table-column>',
      '      <el-table-column label="操作类型" width="90" prop="action" :formatter="(r)=>actionLabel(r.action)"></el-table-column>',
      '      <el-table-column label="操作人" width="110">',
      '        <template #default="{row}">{{ row.operatorName || fmtId(row.operatorId) }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="病历ID" width="200" prop="recordId" :formatter="(r)=>fmtId(r.recordId)"></el-table-column>',
      '      <el-table-column label="范围" width="80" align="center" prop="scope" :formatter="(r)=>scopeLabel(r.scope)"></el-table-column>',
      '      <el-table-column label="IP地址" width="140" prop="ipAddress"></el-table-column>',
      '      <el-table-column label="详情" min-width="200" prop="detail" show-overflow-tooltip></el-table-column>',
      '    </el-table>',
      '  </div>',
      '  <div class="eal-pager">',
      '    <el-pagination layout="total, sizes, prev, pager, next, jumper" :total="total" v-model:current-page="page" v-model:page-size="size" :page-sizes="[10,20,50,100]" @current-change="handlePageChange" @size-change="handleSizeChange"></el-pagination>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
