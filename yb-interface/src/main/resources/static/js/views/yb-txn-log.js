/* 医保接口日志查询页: 分页检索出站交易日志 his_yb_txn_log, 供机构管理员按交易编号/状态/患者/报文ID回溯请求响应原文。
 * 接口: GET /api/yb/txn-log/page?startTime=&endTime=&orgId=&infno=&status=&msgid=&mdtrtId=&psnNo=&page=&size=
 * 注册: HIS.views.YbTxnLog (app.js 按 comp 名挂载); 菜单 yb-txn-log 挂 医保字典(yb-dict) 目录; 样式前缀 yt-; 无构建 IIFE。
 * 可见性: 仅机构管理员(ADMIN/ORG_ADMIN/SUPER_ADMIN)可查询; 牵头机构可跨机构过滤, 成员机构后端强制本机构。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 交易编号 → 名称(常用医保接口, 未收录者原样显示编号) */
  var INFNO_MAP = {
    '2101': '人员信息获取', '2102': '人员待遇检查', '2103': '起付线计算', '2104': '人员备案查询',
    '2201': '门诊挂号', '2202': '门诊挂号撤销', '2203': '门诊就诊信息上传', '2204': '门诊费用明细上传',
    '2205': '门诊费用明细撤销', '2206': '门诊预结算', '2207': '门诊结算', '2208': '门诊结算撤销',
    '2301': '住院登记', '2302': '住院登记撤销', '2303': '住院留院观察', '2304': '住院费用明细上传',
    '2305': '出院办理', '2306': '出院撤销', '2401': '入院办理', '2402': '住院占用床位信息',
    '2404': '真实追溯码上报', '2501': '医疗目录下载', '2502': '诊断目录下载', '2503': '科室信息上传',
    '2504': '医执人员信息上传', '2505': '医疗机构信息上传', '2506': '病历明细上传',
    '2601': '交易冲正', '3201': '就医对账', '3301': '目录对照', '3501': '中药饮片追溯',
    '3502': '自制剂追溯', '3503': '库表类追溯', '3504': '耗材追溯', '3505': '西药中成药追溯',
    '9001': '字典下载', '9101': '文件上传'
  };
  var INFNO_OPTIONS = Object.keys(INFNO_MAP).filter(function (k) { return INFNO_MAP[k]; }).sort()
    .map(function (k) { return { value: k, label: k + '-' + INFNO_MAP[k] }; });

  var STATUS_OPTIONS = [
    { label: '处理中', value: 'PENDING' },
    { label: '成功', value: 'SUCCESS' },
    { label: '失败', value: 'FAIL' },
    { label: '结果未知', value: 'UNKNOWN' },
    { label: '已冲正', value: 'REVERSED' }
  ];
  var STATUS_TAG = { PENDING: 'info', SUCCESS: 'success', FAIL: 'danger', UNKNOWN: 'warning', REVERSED: '' };
  var STATUS_LABEL = {};
  STATUS_OPTIONS.forEach(function (o) { STATUS_LABEL[o.value] = o.label; });

  /* ===== 样式注入 ===== */
  var STYLE = [
    '.yt-root { display:flex; flex-direction:column; height:100%; padding:14px 16px; box-sizing:border-box; overflow:hidden; }',
    '.yt-filter { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-bottom:12px; }',
    '.yt-table { flex:1; min-height:0; overflow:hidden; }',
    '.yt-pager { flex:none; display:flex; justify-content:flex-end; padding:8px 0 0; }',
    '.yt-msg { font-family: Consolas, Menlo, monospace; font-size:12px; line-height:1.5; white-space:pre-wrap; word-break:break-all; background:var(--yb-surface-1); border:1px solid var(--yb-border-light); border-radius:6px; padding:10px 12px; max-height:38vh; overflow:auto; margin:0; }',
    '.yt-msg-cap { font-size:13px; font-weight:600; color:var(--yb-ink-1); margin:10px 0 6px; }'
  ].join('\n');
  if (typeof HIS.injectStyle === 'function') {
    HIS.injectStyle('yb-txn-log-style', STYLE);
  } else if (!document.getElementById('yb-txn-log-style')) {
    var st = document.createElement('style');
    st.id = 'yb-txn-log-style';
    st.textContent = STYLE;
    document.head.appendChild(st);
  }

  HIS.views.YbTxnLog = {
    name: 'YbTxnLog',
    data: function () {
      return {
        infnoOptions: INFNO_OPTIONS,
        statusOptions: STATUS_OPTIONS,
        orgOptions: [],
        loading: false,
        dateRange: [],
        orgId: '',
        infno: '',
        status: '',
        msgid: '',
        mdtrtId: '',
        psnNo: '',
        rows: [],
        total: 0,
        page: 1,
        size: 20,
        msgVisible: false,
        msgRow: null
      };
    },
    mounted: function () {
      this.query();
      this.loadOrgs();
    },
    methods: {
      loadOrgs: function () {
        var vm = this;
        HIS.get('/api/sys/org/tree').then(function (data) {
          vm.orgOptions = (typeof HIS.flattenOrgs === 'function') ? HIS.flattenOrgs(data || []) : [];
        }).catch(function () { /* 机构下拉失败不阻断主查询 */ });
      },
      query: function () {
        var vm = this;
        vm.loading = true;
        var params = 'page=' + vm.page + '&size=' + vm.size;
        if (vm.dateRange && vm.dateRange.length === 2) {
          if (vm.dateRange[0]) { params += '&startTime=' + encodeURIComponent(vm.dateRange[0]); }
          if (vm.dateRange[1]) { params += '&endTime=' + encodeURIComponent(vm.dateRange[1]); }
        }
        if (vm.orgId) { params += '&orgId=' + encodeURIComponent(vm.orgId); }
        if (vm.infno) { params += '&infno=' + encodeURIComponent(vm.infno); }
        if (vm.status) { params += '&status=' + encodeURIComponent(vm.status); }
        var mid = String(vm.msgid || '').trim();
        if (mid) { params += '&msgid=' + encodeURIComponent(mid); }
        var md = String(vm.mdtrtId || '').trim();
        if (md) { params += '&mdtrtId=' + encodeURIComponent(md); }
        var pn = String(vm.psnNo || '').trim();
        if (pn) { params += '&psnNo=' + encodeURIComponent(pn); }
        HIS.get('/api/yb/txn-log/page?' + params)
          .then(function (data) {
            vm.rows = (data && data.records) || [];
            vm.total = Number(data && data.total) || 0;
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      reset: function () {
        this.dateRange = []; this.orgId = ''; this.infno = ''; this.status = '';
        this.msgid = ''; this.mdtrtId = ''; this.psnNo = '';
        this.page = 1; this.query();
      },
      handlePageChange: function (p) { this.page = p; this.query(); },
      handleSizeChange: function (s) { this.size = s; this.page = 1; this.query(); },
      openMsg: function (row) { this.msgRow = row; this.msgVisible = true; },
      infnoLabel: function (v) { return v ? (INFNO_MAP[v] || '') : ''; },
      statusLabel: function (v) { return STATUS_LABEL[v] || v || '-'; },
      statusTag: function (v) { return STATUS_TAG[v] == null ? 'info' : STATUS_TAG[v]; },
      maskSensitive: function (node) {
        var self = this;
        var sensitive = function (key) {
          var n = String(key || '').toLowerCase().replace(/[_\-\s]/g, '');
          return n.indexOf('idcard') >= 0 || n === 'idno' || n.indexOf('certno') >= 0
            || n.indexOf('mdtrtcertno') >= 0 || n.indexOf('psnno') >= 0 || n.indexOf('phone') >= 0
            || n.indexOf('mobile') >= 0 || n.indexOf('password') >= 0 || n.indexOf('passwd') >= 0
            || n.indexOf('secret') >= 0 || n.indexOf('token') >= 0 || n.indexOf('bankaccount') >= 0;
        };
        var mask = function (v) {
          var s = String(v == null ? '' : v);
          if (!s) { return s; }
          if (s.length <= 4) { return '****'; }
          return s.slice(0, 3) + '********' + s.slice(-4);
        };
        if (Array.isArray(node)) {
          return node.map(function (item) { return self.maskSensitive(item); });
        }
        if (node && typeof node === 'object') {
          var out = {};
          Object.keys(node).forEach(function (k) {
            var v = node[k];
            out[k] = sensitive(k) ? mask(v) : self.maskSensitive(v);
          });
          return out;
        }
        return node;
      },
      fmtJson: function (s) {
        if (s == null || s === '') { return '(空)'; }
        try { return JSON.stringify(this.maskSensitive(JSON.parse(s)), null, 2); } catch (e) { return String(s); }
      },
      fmtTime: function (v) { return v ? String(v).replace('T', ' ').substring(0, 19) : '-'; },
      orDash: function (v) { return (v == null || v === '') ? '-' : v; },
      indexMethod: function (idx) { return (this.page - 1) * this.size + idx + 1; }
    },
    template: [
      '<div class="yt-root">',
      '  <div class="yt-filter">',
      '    <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width:240px;" size="default"></el-date-picker>',
      '    <el-select v-model="infno" placeholder="交易编号" clearable filterable style="width:180px;" size="default">',
      '      <el-option v-for="o in infnoOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-select v-model="status" placeholder="状态" clearable style="width:120px;" size="default">',
      '      <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '    </el-select>',
      '    <el-select v-model="orgId" placeholder="机构(牵头可选全部)" clearable filterable style="width:200px;" size="default">',
      '      <el-option v-for="o in orgOptions" :key="o.id" :label="o.label" :value="o.id"></el-option>',
      '    </el-select>',
      '    <el-input v-model="msgid" placeholder="报文ID(msgid)" clearable style="width:200px;" size="default" @keyup.enter="page=1;query()"></el-input>',
      '    <el-input v-model="mdtrtId" placeholder="就诊ID" clearable style="width:150px;" size="default" @keyup.enter="page=1;query()"></el-input>',
      '    <el-input v-model="psnNo" placeholder="人员编号" clearable style="width:150px;" size="default" @keyup.enter="page=1;query()"></el-input>',
      '    <el-button type="primary" size="default" @click="page=1;query()">查询</el-button>',
      '    <el-button size="default" @click="reset">重置</el-button>',
      '  </div>',
      '  <div class="yt-table">',
      '    <el-table :data="rows" v-loading="loading" border stripe height="100%" style="width:100%;">',
      '      <el-table-column label="序号" width="60" align="center" type="index" :index="indexMethod"></el-table-column>',
      '      <el-table-column label="交易时间" width="160" :formatter="(r)=>fmtTime(r.createTime)"></el-table-column>',
      '      <el-table-column label="交易编号" width="150" show-overflow-tooltip>',
      '        <template #default="{row}">{{ row.infno }}<span v-if="infnoLabel(row.infno)" style="color:var(--yb-ink-2);"> {{ infnoLabel(row.infno) }}</span></template>',
      '      </el-table-column>',
      '      <el-table-column label="状态" width="90" align="center">',
      '        <template #default="{row}"><el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag></template>',
      '      </el-table-column>',
      '      <el-table-column label="人员编号" width="130" show-overflow-tooltip :formatter="(r)=>orDash(r.psnNo)"></el-table-column>',
      '      <el-table-column label="就诊ID" width="150" show-overflow-tooltip :formatter="(r)=>orDash(r.mdtrtId)"></el-table-column>',
      '      <el-table-column label="结算ID" width="150" show-overflow-tooltip :formatter="(r)=>orDash(r.setlId)"></el-table-column>',
      '      <el-table-column label="报文ID" width="200" show-overflow-tooltip :formatter="(r)=>orDash(r.msgid)"></el-table-column>',
      '      <el-table-column label="错误信息" min-width="160" show-overflow-tooltip :formatter="(r)=>orDash(r.errMsg)"></el-table-column>',
      '      <el-table-column label="操作" width="90" align="center" fixed="right">',
      '        <template #default="{row}"><el-button link type="primary" size="small" @click="openMsg(row)">查看报文</el-button></template>',
      '      </el-table-column>',
      '    </el-table>',
      '  </div>',
      '  <div class="yt-pager">',
      '    <el-pagination layout="total, sizes, prev, pager, next, jumper" :total="total" v-model:current-page="page" v-model:page-size="size" :page-sizes="[10,20,50,100]" @current-change="handlePageChange" @size-change="handleSizeChange"></el-pagination>',
      '  </div>',
      /* ===== 报文详情对话框 ===== */
      '  <el-dialog v-model="msgVisible" title="医保交易报文详情" width="900px" top="6vh">',
      '    <div v-if="msgRow">',
      '      <div style="display:flex;flex-wrap:wrap;gap:16px;font-size:13px;color:var(--yb-ink-2);margin-bottom:4px;">',
      '        <span>交易编号: <b>{{ msgRow.infno }} {{ infnoLabel(msgRow.infno) }}</b></span>',
      '        <span>状态: <b>{{ statusLabel(msgRow.status) }}</b></span>',
      '        <span>时间: <b>{{ fmtTime(msgRow.createTime) }}</b></span>',
      '        <span>报文ID: <b>{{ orDash(msgRow.msgid) }}</b></span>',
      '        <span v-if="msgRow.errMsg" style="color:var(--yb-danger);">错误: <b>{{ msgRow.errMsg }}</b></span>',
      '      </div>',
      '      <div class="yt-msg-cap">请求报文 (input)</div>',
      '      <pre class="yt-msg">{{ fmtJson(msgRow.inputJson) }}</pre>',
      '      <div class="yt-msg-cap">响应报文 (output)</div>',
      '      <pre class="yt-msg">{{ fmtJson(msgRow.outputJson) }}</pre>',
      '    </div>',
      '    <template #footer><el-button type="primary" @click="msgVisible=false">关闭</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
