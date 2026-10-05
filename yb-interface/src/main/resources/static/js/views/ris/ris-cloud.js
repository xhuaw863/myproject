/* RIS 医保影像云管理(RisCloud) — his_ris_cloud_index 上报台账。
 * 后端 /api/ris/admin:
 *   GET  /cloud/list        分页(上传状态/关键字/上传时间区间), 附机构范围汇总卡
 *                          {records, total, page, size, summary{pendingCount, uploadedToday, failedCount}}
 *   POST /cloud/retry/{id}  重试上传失败索引: 仅失败态可重试, 重置回待上传队列
 *                          (回执/上传时间/云端索引ID 清空, 上传管线接续上报)
 * 上传状态: 0待上传 / 1已上传 / 2上传失败。
 * 注册: HIS.views.RisCloud; 类前缀 rc-。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var UPLOAD_STATUS = [
    { v: 0, l: '待上传', t: 'warning' },
    { v: 1, l: '已上传', t: 'success' },
    { v: 2, l: '上传失败', t: 'danger' }
  ];

  function statusMeta(v) {
    for (var i = 0; i < UPLOAD_STATUS.length; i++) {
      if (UPLOAD_STATUS[i].v === Number(v)) { return UPLOAD_STATUS[i]; }
    }
    return { v: v, l: v == null ? '-' : String(v), t: 'info' };
  }

  (function ensureRcStyles() {
    if (document.getElementById('ris-cloud-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-cloud-style';
    st.textContent = [
      '.rc-code { font-family: var(--yb-font-mono); font-size: 12px; color: var(--yb-ink-2); }',
      '.rc-tip { color: var(--yb-ink-4); font-size: 12px; line-height: 1.6; }',
      '.rc-stat { flex: none; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  HIS.views.RisCloud = {
    name: 'RisCloud',
    data: function () {
      return {
        uploadStatus: null, keyword: '', uploadRange: null,
        page: 1, size: 20, total: 0, list: [], loading: false,
        summary: { pendingCount: 0, uploadedToday: 0, failedCount: 0 },
        statusOpts: UPLOAD_STATUS
      };
    },
    created: function () { this.fetch(); },
    methods: {
      statusLabel: function (v) { return statusMeta(v).l; },
      statusTag: function (v) { return statusMeta(v).t; },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      search: function () { this.page = 1; this.fetch(); },
      resetFilter: function () {
        this.uploadStatus = null; this.keyword = ''; this.uploadRange = null;
        this.page = 1; this.fetch();
      },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/ris/admin/cloud/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.uploadStatus != null) { q += '&uploadStatus=' + vm.uploadStatus; }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword.trim()); }
        if (vm.uploadRange && vm.uploadRange.length === 2) {
          q += '&startDate=' + vm.uploadRange[0] + '&endDate=' + vm.uploadRange[1];
        }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          vm.summary = (d && d.summary) || { pendingCount: 0, uploadedToday: 0, failedCount: 0 };
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
      onSize: function (s) { this.size = s; this.page = 1; this.fetch(); },
      /* 重试: 仅失败态展示按钮, 重置回待上传队列由管线接续上报 */
      retry: function (row) {
        var vm = this;
        var name = row.requestNo || row.patientName || HIS.idKey(row.studyUid) || HIS.idKey(row.reportId);
        ElementPlus.ElMessageBox.confirm(
          '确认重试「' + name + '」的影像云上报? 将重置回待上传队列, 清空历史回执后由上传管线接续上报。',
          '重试上传', { type: 'warning' }
        ).then(function () {
          return HIS.post('/api/ris/admin/cloud/retry/' + encodeURIComponent(HIS.id(row && row.id)));
        }).then(function () {
          HIS.notifySuccess('已重置为待上传');
          vm.fetch();
        }).catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">医保影像云管理',
      '    <span style="font-size:12px;color:var(--yb-ink-3);font-weight:normal;">(his_ris_cloud_index · 医保影像云上报索引, 报告审核后自动入队上报)</span>',
      '  </div>',
      '  <div class="stat-grid rc-stat">',
      '    <div class="stat-card"><div class="num">{{ summary.pendingCount }}</div><div class="lbl">待上传(机构范围)</div></div>',
      '    <div class="stat-card"><div class="num">{{ summary.uploadedToday }}</div><div class="lbl">今日已上传</div></div>',
      '    <div class="stat-card"><div class="num">{{ summary.failedCount }}</div><div class="lbl">上传失败(可重试)</div></div>',
      '    <div class="stat-card"><div class="num">{{ total }}</div><div class="lbl">当前筛选结果</div></div>',
      '  </div>',
      '  <div class="toolbar">',
      '    <el-select v-model="uploadStatus" placeholder="上传状态" clearable style="width:130px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-date-picker v-model="uploadRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"',
      '      start-placeholder="上传开始" end-placeholder="上传结束" style="width:230px" @change="search"></el-date-picker>',
      '    <el-input v-model="keyword" placeholder="申请单号/患者/StudyUID/医保检查编码/云端索引ID/报告ID" clearable style="width:330px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button @click="resetFilter">重置</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span class="rc-tip">日期筛选按上传时间; 待上传记录无上传时间, 选日期时不出现在结果中</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="56" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="reportId" label="报告ID" width="150" show-overflow-tooltip>',
      '      <template #default="s"><span class="rc-code">{{ HIS.idKey(s.row.reportId) || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column prop="requestNo" label="申请单号" width="140" show-overflow-tooltip>',
      '      <template #default="s"><span class="rc-code">{{ s.row.requestNo || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column prop="patientName" label="患者姓名" width="90" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.patientName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column prop="studyUid" label="Study UID" width="210" show-overflow-tooltip>',
      '      <template #default="s"><span class="rc-code">{{ s.row.studyUid || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column prop="ybExamCode" label="医保检查编码" width="120" show-overflow-tooltip>',
      '      <template #default="s"><span class="rc-code">{{ s.row.ybExamCode || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="上传状态" width="96" align="center">',
      '      <template #default="s"><el-tag size="small" :type="statusTag(s.row.uploadStatus)">{{ statusLabel(s.row.uploadStatus) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column prop="uploadTime" label="上传时间" width="150">',
      '      <template #default="s"><span class="rc-code">{{ s.row.uploadTime || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column prop="cloudIndexId" label="云端索引ID" width="150" show-overflow-tooltip>',
      '      <template #default="s"><span class="rc-code">{{ s.row.cloudIndexId || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column prop="uploadResponse" label="上报回执(失败原因)" min-width="170" show-overflow-tooltip>',
      '      <template #default="s"><span class="rc-tip">{{ s.row.uploadResponse || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="80" fixed="right">',
      '      <template #default="s">',
      '        <el-button v-if="Number(s.row.uploadStatus)===2" link type="warning" @click="retry(s.row)">重试</el-button>',
      '        <span v-else class="rc-tip">-</span>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>'
    ].join('\n')
  };
})();
