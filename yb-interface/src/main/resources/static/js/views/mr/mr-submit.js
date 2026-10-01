/* 病案上报三段闭环(病案统计科侧 P2): 卫统4表(wt) / HQMS绩效(hqms) / 医保结算清单(med_list) 建批次→审核→转换→上报。
 * 后端 /api/his/mr/submit: types(GET) / list(GET reportType,status) / batch(POST body{reportType,from,to})
 *   / batch/{id}/audit|convert|submit(PUT 严格前进,跳档 409) / batch/{id}(GET 详情含明细) / batch/{id}(DEL 未上报方可删)。
 * 铁律: 数据源为编目侧快照(catalog_status=3 AND audit_status>=2), 不回写临床首页; 写权限本机构管理员(后端 requireSelfOrgWrite 兜底 403)。
 * 状态: 1待审核 / 2已审核待转换 / 3已转换待上报 / 4已上报 / 5失败。雪花 ID 全链路字符串承载; 文件级助手须以 methods 暴露(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrSubmit = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 20,
        total: 0,
        reportType: null,
        status: null,
        typeOpts: [
          { value: 'wt', label: '卫统4表' },
          { value: 'hqms', label: 'HQMS绩效' },
          { value: 'med_list', label: '医保结算清单' }
        ],
        statusOpts: [
          { value: 1, label: '待审核' },
          { value: 2, label: '已审核待转换' },
          { value: 3, label: '已转换待上报' },
          { value: 4, label: '已上报' },
          { value: 5, label: '失败' }
        ],
        /* 建批次 */
        create: { visible: false, saving: false, reportType: 'wt', from: '', to: '' },
        /* 批次详情 */
        detail: { visible: false, loading: false, batch: {}, items: [] }
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      }
    },
    created: function () {
      this.loadList();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      fmt: function (v) { return (v || '').toString().replace('T', ' ').slice(0, 16); },
      fmtDay: function (v) { return (v || '').toString().slice(0, 10); },
      typeText: function (t) {
        return { wt: '卫统4表', hqms: 'HQMS绩效', med_list: '医保结算清单' }[t] || t || '-';
      },
      statusText: function (s) {
        return { 1: '待审核', 2: '已审核待转换', 3: '已转换待上报', 4: '已上报', 5: '失败' }[s] || '-';
      },
      statusTag: function (s) {
        if (s === 4) { return 'success'; }
        if (s === 5) { return 'danger'; }
        if (s === 1) { return 'warning'; }
        return 'info';
      },
      checkStatusText: function (c) { return c === 1 ? '通过' : (c === 2 ? '拦截' : '-'); },
      checkStatusTag: function (c) { return c === 1 ? 'success' : (c === 2 ? 'danger' : 'info'); },
      yesNo: function (v) { return v === 1 ? '是' : '否'; },
      /* ================= 列表 ================= */
      loadList: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/mr/submit/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.reportType) { q += '&reportType=' + encodeURIComponent(vm.reportType); }
        if (vm.status != null) { q += '&status=' + vm.status; }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.page = 1; this.loadList(); },
      reset: function () { this.reportType = null; this.status = null; this.page = 1; this.loadList(); },
      onPage: function (p) { this.page = p; this.loadList(); },
      onSize: function (s) { this.size = s; this.page = 1; this.loadList(); },

      /* ================= 建批次 ================= */
      openCreate: function () {
        this.create = { visible: true, saving: false, reportType: 'wt', from: '', to: '' };
      },
      doCreate: function () {
        var vm = this;
        if (!vm.create.reportType) { ElementPlus.ElMessage.warning('请选择上报类型'); return; }
        if (!vm.create.from || !vm.create.to) { ElementPlus.ElMessage.warning('统计起止日期必填'); return; }
        vm.create.saving = true;
        HIS.post('/api/his/mr/submit/batch', {
          reportType: vm.create.reportType, from: vm.create.from, to: vm.create.to
        }).then(function (res) {
          HIS.notifySuccess('批次已建: ' + (res && res.batchNo || '') + ' · 快照 ' + (res && res.totalCount || 0) + ' 条');
          vm.create.visible = false;
          vm.page = 1;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.create.saving = false; });
      },

      /* ================= 三段推进 ================= */
      doAudit: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认对批次「' + row.batchNo + '」执行数据审核? 将逐条校验主要诊断等合规性。',
          '第一段 · 数据审核', { type: 'warning' }
        ).then(function () {
          HIS.put('/api/his/mr/submit/batch/' + HIS.id(row.id) + '/audit', {}).then(function (r) {
            HIS.notifySuccess('审核完成: 通过 ' + (r && r.ok || 0) + ' / 拦截 ' + (r && r.err || 0));
            vm.loadList();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      doConvert: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认对批次「' + row.batchNo + '」执行数据转换? 仅审核通过项参与。',
          '第二段 · 数据转换', { type: 'warning' }
        ).then(function () {
          HIS.put('/api/his/mr/submit/batch/' + HIS.id(row.id) + '/convert', {}).then(function (r) {
            HIS.notifySuccess('转换完成: 已转换 ' + (r && r.converted || 0) + ' 条');
            vm.loadList();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      doSubmit: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认对批次「' + row.batchNo + '」执行上报? 上报后批次不可删除。',
          '第三段 · 上报', { type: 'warning' }
        ).then(function () {
          HIS.put('/api/his/mr/submit/batch/' + HIS.id(row.id) + '/submit', {}).then(function (r) {
            HIS.notifySuccess('上报完成: 回执 ' + (r && r.reported || 0) + ' 条, 文件 ' + (r && r.fileRef || ''));
            vm.loadList();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      doDelete: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除批次「' + row.batchNo + '」? 未上报方可删。', '提示', { type: 'warning' })
          .then(function () {
            HIS.del('/api/his/mr/submit/batch/' + HIS.id(row.id)).then(function () {
              HIS.notifySuccess('已删除');
              vm.loadList();
            }).catch(HIS.notifyError);
          }).catch(function () {});
      },

      /* ================= 批次详情 ================= */
      openDetail: function (row) {
        var vm = this;
        vm.detail = { visible: true, loading: true, batch: {}, items: [] };
        HIS.get('/api/his/mr/submit/batch/' + HIS.id(row.id)).then(function (d) {
          vm.detail.batch = (d && d.batch) || {};
          vm.detail.items = (d && d.items) || [];
        }).catch(HIS.notifyError).finally(function () { vm.detail.loading = false; });
      }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">病案上报三段闭环 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">卫统4表 / HQMS绩效 / 医保结算清单 · 建批次 → 数据审核 → 数据转换 → 上报</span></div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <el-select v-model="reportType" size="small" clearable placeholder="上报类型" style="width:160px">',
      '      <el-option v-for="t in typeOpts" :key="t.value" :label="t.label" :value="t.value"></el-option>',
      '    </el-select>',
      '    <el-select v-model="status" size="small" clearable placeholder="批次状态" style="width:170px">',
      '      <el-option v-for="s in statusOpts" :key="s.value" :label="s.label" :value="s.value"></el-option>',
      '    </el-select>',
      '    <el-button size="small" type="primary" @click="search">查询</el-button>',
      '    <el-button size="small" @click="reset">重置</el-button>',
      '    <span style="flex:1"></span>',
      '    <el-button v-if="canWrite" size="small" type="primary" @click="openCreate">建批次</el-button>',
      '  </div>',
      '  <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无上报批次(可点建批次快照已编目已审核病案)">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '    <el-table-column prop="batchNo" label="批次号" width="180"></el-table-column>',
      '    <el-table-column label="上报类型" width="130"><template #default="s">{{ typeText(s.row.reportType) }}</template></el-table-column>',
      '    <el-table-column label="统计区间" width="200"><template #default="s">{{ fmtDay(s.row.periodFrom) }} ~ {{ fmtDay(s.row.periodTo) }}</template></el-table-column>',
      '    <el-table-column prop="totalCount" label="总数" width="70" align="right"></el-table-column>',
      '    <el-table-column prop="okCount" label="通过" width="70" align="right"></el-table-column>',
      '    <el-table-column prop="errCount" label="拦截" width="70" align="right"></el-table-column>',
      '    <el-table-column label="状态" width="130" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusText(s.row.status) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="reviewerName" label="审核人" width="100"></el-table-column>',
      '    <el-table-column prop="converterName" label="转换人" width="100"></el-table-column>',
      '    <el-table-column prop="submitterName" label="上报人" width="100"></el-table-column>',
      '    <el-table-column prop="fileRef" label="回执文件" min-width="160"></el-table-column>',
      '    <el-table-column label="操作" width="260" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-button type="primary" link size="small" @click="openDetail(s.row)">明细</el-button>',
      '        <template v-if="canWrite">',
      '          <el-button v-if="s.row.status===1" type="warning" link size="small" @click="doAudit(s.row)">数据审核</el-button>',
      '          <el-button v-if="s.row.status===2" type="warning" link size="small" @click="doConvert(s.row)">数据转换</el-button>',
      '          <el-button v-if="s.row.status===3" type="success" link size="small" @click="doSubmit(s.row)">上报</el-button>',
      '          <el-button v-if="s.row.status!==4" type="danger" link size="small" @click="doDelete(s.row)">删除</el-button>',
      '        </template>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:10px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next, jumper"',
      '    :total="total" :current-page="page" :page-size="size" :page-sizes="[10,20,50,100]"',
      '    @current-change="onPage" @size-change="onSize"></el-pagination>',
      '</div>',
      /* ---- 建批次弹窗 ---- */
      '<el-dialog v-model="create.visible" title="建上报批次" width="460px" destroy-on-close>',
      '  <el-form label-width="100px" size="default">',
      '    <el-form-item label="上报类型" required>',
      '      <el-radio-group v-model="create.reportType">',
      '        <el-radio label="wt">卫统4表</el-radio>',
      '        <el-radio label="hqms">HQMS绩效</el-radio>',
      '        <el-radio label="med_list">医保结算清单</el-radio>',
      '      </el-radio-group>',
      '    </el-form-item>',
      '    <el-form-item label="出院起" required><el-date-picker v-model="create.from" type="date" value-format="YYYY-MM-DD" placeholder="yyyy-MM-dd" style="width:100%;"></el-date-picker></el-form-item>',
      '    <el-form-item label="出院止" required><el-date-picker v-model="create.to" type="date" value-format="YYYY-MM-DD" placeholder="yyyy-MM-dd" style="width:100%;"></el-date-picker></el-form-item>',
      '    <el-form-item><span style="font-size:12px;color:var(--yb-ink-2);">将快照该出院区间内 catalog_status=3 且 audit_status≥2 的病案为上报明细, 建单后进入待审核。</span></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="create.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="create.saving" @click="doCreate">建批次</el-button>',
      '  </template>',
      '</el-dialog>',
      /* ---- 批次详情弹窗 ---- */
      '<el-dialog v-model="detail.visible" title="批次明细" width="960px" destroy-on-close>',
      '  <div v-loading="detail.loading">',
      '    <el-descriptions v-if="detail.batch && detail.batch.id" :column="3" border size="small" style="margin-bottom:12px;">',
      '      <el-descriptions-item label="批次号">{{ detail.batch.batchNo }}</el-descriptions-item>',
      '      <el-descriptions-item label="上报类型">{{ typeText(detail.batch.reportType) }}</el-descriptions-item>',
      '      <el-descriptions-item label="状态"><el-tag size="small" :type="statusTag(detail.batch.status)">{{ statusText(detail.batch.status) }}</el-tag></el-descriptions-item>',
      '      <el-descriptions-item label="统计区间">{{ fmtDay(detail.batch.periodFrom) }} ~ {{ fmtDay(detail.batch.periodTo) }}</el-descriptions-item>',
      '      <el-descriptions-item label="总数/通过/拦截">{{ detail.batch.totalCount }} / {{ detail.batch.okCount }} / {{ detail.batch.errCount }}</el-descriptions-item>',
      '      <el-descriptions-item label="回执文件">{{ detail.batch.fileRef || "-" }}</el-descriptions-item>',
      '      <el-descriptions-item label="审核">{{ detail.batch.reviewerName || "-" }} {{ fmt(detail.batch.reviewTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="转换">{{ detail.batch.converterName || "-" }} {{ fmt(detail.batch.convertTime) }}</el-descriptions-item>',
      '      <el-descriptions-item label="上报">{{ detail.batch.submitterName || "-" }} {{ fmt(detail.batch.submitTime) }}</el-descriptions-item>',
      '    </el-descriptions>',
      '    <el-table :data="detail.items" border size="small" max-height="420" style="width:100%;" empty-text="暂无明细">',
      '      <el-table-column type="index" label="#" width="45" align="center"></el-table-column>',
      '      <el-table-column prop="patientName" label="患者" width="100"></el-table-column>',
      '      <el-table-column prop="inpNo" label="住院号" width="120"></el-table-column>',
      '      <el-table-column prop="mainDiagCode" label="主要诊断编码" width="140"></el-table-column>',
      '      <el-table-column label="审核" width="80" align="center"><template #default="s"><el-tag size="small" :type="checkStatusTag(s.row.checkStatus)">{{ checkStatusText(s.row.checkStatus) }}</el-tag></template></el-table-column>',
      '      <el-table-column prop="checkMsg" label="审核意见" min-width="140"></el-table-column>',
      '      <el-table-column label="已转换" width="80" align="center"><template #default="s">{{ yesNo(s.row.converted) }}</template></el-table-column>',
      '      <el-table-column label="已上报" width="80" align="center"><template #default="s">{{ yesNo(s.row.reported) }}</template></el-table-column>',
      '      <el-table-column prop="reportMsg" label="上报回执" min-width="160"></el-table-column>',
      '    </el-table>',
      '  </div>',
      '  <template #footer><el-button @click="detail.visible=false">关闭</el-button></template>',
      '</el-dialog>'
    ].join('\n')
  };
})();
