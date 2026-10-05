/* 公共卫生管理-报卡集中审核工作台(归口部门统一审核 + 统计查询)。
 * 后端 /api/his/disease-report: page(GET 分页) / stats(GET 统计卡片) / detail(GET 明细回显)
 *   / {id}/audit(POST 审核通过 1→2) / {id}/return(POST 退回→-1 需原因) / export(GET 按国标导出CSV文本)。
 * page/stats/export 均受后端 requireAdminOrSuper 守卫(仅 ADMIN/SUPER_ADMIN); 菜单走 all_menus 自动可见。
 * 两叶子共用本工厂: ReportAuditInf(cats=[1] 传染病) / ReportAuditChronic(cats=[2,3,4,5] 慢病·精障/肿瘤/高血压/糖尿病)。
 * 雪花 ID 全链路字符串承载; 运行时模板仅访问组件作用域。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var CAT_TEXT = { 1: '法定传染病', 2: '严重精神障碍', 3: '恶性肿瘤', 4: '高血压', 5: '糖尿病', 9: '其他' };
  var STATUS_TEXT = { 0: '待报', 1: '已报', 2: '已审核', '-1': '退回' };
  function statusTag(s) {
    if (s === 2) { return 'success'; }
    if (s === 1) { return 'warning'; }
    if (s === -1) { return 'danger'; }
    return 'info';
  }

  /* 工厂: 按报卡大类集合参数化生成审核工作台组件 */
  function buildAuditView(cats, title, defaultExportCat) {
    var catParam = cats.join(',');
    return {
      name: 'ReportAuditView',
      data: function () {
        return {
          title: title,
          cats: cats,
          loading: false,
          rows: [],
          pageNo: 1,
          pageSize: 20,
          total: 0,
          status: null,
          reporter: '',
          keyword: '',
          dateRange: [],
          statusOpts: [
            { value: 0, label: '待报' },
            { value: 1, label: '已报' },
            { value: 2, label: '已审核' },
            { value: -1, label: '退回' }
          ],
          stats: { pending: 0, reported: 0, audited: 0, returned: 0, total: 0, monthNew: 0, late: 0, missed: 0 },
          /* 详情抽屉 */
          drawer: { visible: false, loading: false, row: null, detail: {}, form: {} },
          /* 退回复核 */
          ret: { visible: false, saving: false, row: null, reason: '' },
          /* 导出类别(慢病含多大类, 需选择) */
          exportCat: defaultExportCat,
          exportCats: cats.map(function (c) { return { value: c, label: CAT_TEXT[c] || ('类别' + c) }; })
        };
      },
      computed: {
        from: function () { return this.dateRange && this.dateRange[0] ? String(this.dateRange[0]).slice(0, 10) : ''; },
        to: function () { return this.dateRange && this.dateRange[1] ? String(this.dateRange[1]).slice(0, 10) : ''; }
      },
      created: function () { this.loadStats(); this.loadList(); },
      methods: {
        catText: function (c) { return CAT_TEXT[c] || (c == null ? '-' : String(c)); },
        statusText: function (s) { return STATUS_TEXT[s] != null ? STATUS_TEXT[s] : '-'; },
        statusTag: statusTag,
        seqNo: function (i) { return (this.pageNo - 1) * this.pageSize + i + 1; },
        fmt: function (v) { return (v || '').toString().replace('T', ' ').slice(0, 16); },
        fmtDay: function (v) { return (v || '').toString().slice(0, 10); },
        /* ================= 取数 ================= */
        rangeParams: function () {
          var vm = this;
          var q = '';
          if (vm.from) { q += '&from=' + encodeURIComponent(vm.from); }
          if (vm.to) { q += '&to=' + encodeURIComponent(vm.to); }
          return q;
        },
        loadStats: function () {
          var vm = this;
          HIS.get('/api/his/disease-report/stats?cats=' + encodeURIComponent(catParam) + vm.rangeParams()).then(function (d) {
            vm.stats = d || vm.stats;
          }).catch(function () {});
        },
        loadList: function () {
          var vm = this;
          vm.loading = true;
          var q = '/api/his/disease-report/page?cats=' + encodeURIComponent(catParam)
            + '&pageNo=' + vm.pageNo + '&pageSize=' + vm.pageSize
            + (vm.status != null ? '&status=' + vm.status : '')
            + (vm.reporter ? '&reporter=' + encodeURIComponent(vm.reporter) : '')
            + (vm.keyword ? '&keyword=' + encodeURIComponent(vm.keyword) : '')
            + vm.rangeParams();
          HIS.get(q).then(function (d) {
            vm.rows = (d && d.records) || [];
            vm.total = (d && Number(d.total)) || 0;
          }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); }).finally(function () { vm.loading = false; });
        },
        search: function () { this.pageNo = 1; this.loadList(); this.loadStats(); },
        resetFilter: function () {
          var vm = this;
          vm.status = null; vm.reporter = ''; vm.keyword = ''; vm.dateRange = [];
          vm.pageNo = 1; vm.loadList(); vm.loadStats();
        },
        onPage: function (p) { this.pageNo = p; this.loadList(); },
        /* ================= 详情回显 ================= */
        openDetail: function (row) {
          var vm = this;
          vm.drawer = { visible: true, loading: true, row: row, detail: {}, form: {} };
          HIS.get('/api/his/disease-report/detail?reportId=' + encodeURIComponent(row.id)).then(function (d) {
            d = d || {};
            vm.drawer.detail = d;
            var fd = {};
            try { fd = d.formData ? JSON.parse(d.formData) : {}; } catch (e) { fd = {}; }
            vm.drawer.form = fd;
          }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); }).finally(function () { vm.drawer.loading = false; });
        },
        /* ================= 审核动作 ================= */
        approve: function (row) {
          var vm = this;
          ElementPlus.ElMessageBox.confirm('确认审核通过卡片「' + (row.cardNo || '') + '」?', '审核通过', { type: 'success' }).then(function () {
            return HIS.post('/api/his/disease-report/' + encodeURIComponent(row.id) + '/audit', {});
          }).then(function () {
            HIS.notifySuccess && HIS.notifySuccess('已审核通过');
            vm.loadList(); vm.loadStats();
          }).catch(function (e) { if (e && e.message) { HIS.notifyError && HIS.notifyError(e); } });
        },
        openReturn: function (row) { this.ret = { visible: true, saving: false, row: row, reason: '' }; },
        confirmReturn: function () {
          var vm = this;
          if (!vm.ret.row) { return; }
          if (!vm.ret.reason || !vm.ret.reason.trim()) { ElementPlus.ElMessage.warning('退回必须填写原因'); return; }
          vm.ret.saving = true;
          HIS.post('/api/his/disease-report/' + encodeURIComponent(vm.ret.row.id) + '/return?reason=' + encodeURIComponent(vm.ret.reason.trim()), {}).then(function () {
            HIS.notifySuccess && HIS.notifySuccess('已退回');
            vm.ret.visible = false; vm.loadList(); vm.loadStats();
          }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); }).finally(function () { vm.ret.saving = false; });
        },
        /* ================= 按国标导出 ================= */
        doExport: function () {
          var vm = this;
          if (!vm.exportCat) { ElementPlus.ElMessage.warning('请选择导出类别'); return; }
          var q = '/api/his/disease-report/export?cat=' + encodeURIComponent(vm.exportCat) + vm.rangeParams();
          HIS.get(q).then(function (text) {
            var csv = text == null ? '' : String(text);
            var blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
            var name = vm.catText(vm.exportCat) + '报告卡_' + (vm.from || '全部') + '.csv';
            var a = document.createElement('a');
            var u = URL.createObjectURL(blob);
            a.href = u; a.download = name;
            document.body.appendChild(a); a.click();
            setTimeout(function () { URL.revokeObjectURL(u); a.parentNode && a.parentNode.removeChild(a); }, 800);
          }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); });
        }
      },
      template: `
        <div class="dw-panel" style="padding:12px">
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
            <h3 style="margin:0">{{ title }}</h3>
            <el-button size="small" @click="search">刷新</el-button>
          </div>

          <!-- 统计卡片 -->
          <el-row :gutter="10" style="margin-bottom:12px">
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">待报</div><div style="font-size:22px;font-weight:600">{{ stats.pending }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">已报待审</div><div style="font-size:22px;font-weight:600;color:#e6a23c">{{ stats.reported }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">已审核</div><div style="font-size:22px;font-weight:600;color:#67c23a">{{ stats.audited }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">退回</div><div style="font-size:22px;font-weight:600;color:#f56c6c">{{ stats.returned }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">本月新增</div><div style="font-size:22px;font-weight:600">{{ stats.monthNew }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">迟报</div><div style="font-size:22px;font-weight:600;color:#e6a23c">{{ stats.late }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">漏报(暂不报)</div><div style="font-size:22px;font-weight:600;color:#f56c6c">{{ stats.missed }}</div></el-card></el-col>
            <el-col :span="3"><el-card shadow="never" body-style="padding:12px;text-align:center"><div class="dim">全部</div><div style="font-size:22px;font-weight:600">{{ stats.total }}</div></el-card></el-col>
          </el-row>

          <!-- 筛选栏 -->
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px">
            <el-select v-model="status" placeholder="状态" clearable size="small" style="width:120px">
              <el-option v-for="s in statusOpts" :key="s.value" :label="s.label" :value="s.value"></el-option>
            </el-select>
            <el-input v-model="keyword" size="small" style="width:180px" placeholder="患者姓名/证件/卡片编号" clearable @keyup.enter="search"></el-input>
            <el-input v-model="reporter" size="small" style="width:130px" placeholder="报告医生" clearable @keyup.enter="search"></el-input>
            <el-date-picker v-model="dateRange" type="daterange" size="small" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="报告起" end-placeholder="报告止" style="width:240px"></el-date-picker>
            <el-button size="small" type="primary" @click="search">查询</el-button>
            <el-button size="small" @click="resetFilter">重置</el-button>
            <span style="flex:1"></span>
            <el-select v-model="exportCat" size="small" style="width:130px" placeholder="导出类别">
              <el-option v-for="c in exportCats" :key="c.value" :label="c.label" :value="c.value"></el-option>
            </el-select>
            <el-button size="small" type="success" @click="doExport">按国标导出CSV</el-button>
          </div>

          <!-- 分页表 -->
          <el-table :data="rows" size="small" border v-loading="loading" max-height="520" style="width:100%">
            <el-table-column label="#" width="52" :formatter="(r,i)=>seqNo(i)"></el-table-column>
            <el-table-column prop="cardNo" label="卡片编号" width="150" show-overflow-tooltip></el-table-column>
            <el-table-column label="类别" width="110"><template #default="s">{{ catText(s.row.reportCategory) }}</template></el-table-column>
            <el-table-column prop="patientName" label="患者姓名" width="100" show-overflow-tooltip></el-table-column>
            <el-table-column prop="patientIdcard" label="证件号" width="150" show-overflow-tooltip></el-table-column>
            <el-table-column prop="diagName" label="疾病/诊断" min-width="150" show-overflow-tooltip></el-table-column>
            <el-table-column prop="reporter" label="报告医生" width="100" show-overflow-tooltip></el-table-column>
            <el-table-column label="报告日期" width="150"><template #default="s">{{ fmt(s.row.reportTime) }}</template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="s"><el-tag :type="statusTag(s.row.reportStatus)" size="small">{{ statusText(s.row.reportStatus) }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="s">
                <el-button size="small" link type="primary" @click="openDetail(s.row)">详情</el-button>
                <el-button v-if="s.row.reportStatus===1" size="small" link type="success" @click="approve(s.row)">审核通过</el-button>
                <el-button v-if="s.row.reportStatus===1" size="small" link type="danger" @click="openReturn(s.row)">退回</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div style="display:flex;justify-content:flex-end;margin-top:12px">
            <el-pagination background layout="total, prev, pager, next, sizes" :total="total"
              v-model:current-page="pageNo" v-model:page-size="pageSize" :page-sizes="[20,50,100]"
              @current-change="loadList" @size-change="search"></el-pagination>
          </div>

          <!-- 详情抽屉(只读回显 form_data) -->
          <el-drawer v-model="drawer.visible" title="报卡详情" size="560px" :with-header="true">
            <div v-loading="drawer.loading" style="padding:0 4px">
              <el-descriptions v-if="drawer.row" :column="1" border size="small" style="margin-bottom:12px">
                <el-descriptions-item label="卡片编号">{{ drawer.row.cardNo || '-' }}</el-descriptions-item>
                <el-descriptions-item label="报卡类别">{{ catText(drawer.row.reportCategory) }}</el-descriptions-item>
                <el-descriptions-item label="状态"><el-tag :type="statusTag(drawer.row.reportStatus)" size="small">{{ statusText(drawer.row.reportStatus) }}</el-tag></el-descriptions-item>
                <el-descriptions-item label="疾病/诊断">{{ drawer.row.diagName || '-' }}</el-descriptions-item>
                <el-descriptions-item label="退卡原因" v-if="drawer.row.returnReason">{{ drawer.row.returnReason }}</el-descriptions-item>
              </el-descriptions>
              <el-divider content-position="left">患者信息</el-divider>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="姓名">{{ drawer.form.name || '-' }}</el-descriptions-item>
                <el-descriptions-item label="性别">{{ drawer.form.genderName || drawer.form.gender || '-' }}</el-descriptions-item>
                <el-descriptions-item label="出生日期">{{ drawer.form.birthDate || '-' }}</el-descriptions-item>
                <el-descriptions-item label="证件号">{{ drawer.form.idCard || '-' }}</el-descriptions-item>
                <el-descriptions-item label="联系电话">{{ drawer.form.phone || '-' }}</el-descriptions-item>
                <el-descriptions-item label="职业">{{ drawer.form.occupationName || '-' }}</el-descriptions-item>
                <el-descriptions-item label="现住址" :span="2">{{ drawer.form.presentDetail || '-' }}</el-descriptions-item>
              </el-descriptions>
              <el-divider content-position="left">发病/报卡信息</el-divider>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="疾病名称">{{ drawer.form.diseaseName || '-' }}</el-descriptions-item>
                <el-descriptions-item label="疾病编码">{{ drawer.form.diseaseCode || drawer.form.icdCode || '-' }}</el-descriptions-item>
                <el-descriptions-item label="病例分类" v-if="drawer.form.caseType">{{ drawer.form.caseType }}</el-descriptions-item>
                <el-descriptions-item label="危险等级" v-if="drawer.form.riskLevel!=null">{{ drawer.form.riskLevel }}</el-descriptions-item>
                <el-descriptions-item label="肿瘤部位" v-if="drawer.form.tumorTopoName">{{ drawer.form.tumorTopoName }}</el-descriptions-item>
                <el-descriptions-item label="临床分期" v-if="drawer.form.stage">{{ drawer.form.stage }}</el-descriptions-item>
                <el-descriptions-item label="发病日期">{{ fmt(drawer.row.onsetDate) }}</el-descriptions-item>
                <el-descriptions-item label="诊断时间">{{ fmt(drawer.row.diagTime) }}</el-descriptions-item>
              </el-descriptions>
            </div>
          </el-drawer>

          <!-- 退回复核 -->
          <el-dialog v-model="ret.visible" title="退回报卡" width="440px" append-to-body>
            <div class="dim" style="margin-bottom:8px">退回卡片「{{ ret.row && ret.row.cardNo }}」, 请填写退回原因(将落库并提示医生重报)。</div>
            <el-input v-model="ret.reason" type="textarea" :rows="3" placeholder="退回原因(必填)"></el-input>
            <template #footer>
              <el-button size="small" @click="ret.visible=false">取消</el-button>
              <el-button size="small" type="danger" :loading="ret.saving" @click="confirmReturn">确认退回</el-button>
            </template>
          </el-dialog>
        </div>
      `
    };
  }

  /* 传染病报卡审核: 大类=1; 导出默认类别 1 */
  HIS.views.ReportAuditInf = buildAuditView([1], '传染病报卡审核', 1);
  /* 慢病报卡审核: 严重精神障碍(2)/恶性肿瘤(3)/高血压(4)/糖尿病(5); 导出默认 2 */
  HIS.views.ReportAuditChronic = buildAuditView([2, 3, 4, 5], '慢病报卡审核', 2);
})();
