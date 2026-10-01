/* 质控前后对比(P3-B): 输入就诊ID或选择已编目病案, 展示编目前后质控校验差异。
 * 后端 /api/his/mr/quality-diff/{visitId} GET + /api/his/mr/quality-diff/batch POST。只读计算。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrQualityDiff = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        activeTab: 'single',
        loading: false,
        visitId: '',
        result: null,
        /* batch */
        batchLoading: false, batchRows: [], batchVisitIds: '',
        /* catalog list for selection */
        catalogs: [], catLoading: false
      };
    },
    created: function () {
      this.loadCatalogs();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      loadCatalogs: function () {
        var vm = this; vm.catLoading = true;
        HIS.get('/api/his/mr/catalog/list', { page: 1, size: 100, catalogStatus: 3 }).then(function (d) {
          vm.catalogs = (d && d.records) || [];
        }).catch(function () { vm.catalogs = []; }).finally(function () { vm.catLoading = false; });
      },
      /* 单份对比 */
      doDiff: function () {
        var vm = this;
        var vid = String(vm.visitId || '').trim();
        if (!vid) { ElementPlus.ElMessage.warning('请输入或选择就诊ID'); return; }
        vm.loading = true; vm.result = null;
        HIS.get('/api/his/mr/quality-diff/' + vid).then(function (d) {
          vm.result = d;
        }).catch(function (e) {
          ElementPlus.ElMessage.error(e && e.msg ? e.msg : '对比失败');
        }).finally(function () { vm.loading = false; });
      },
      selectCatalog: function (row) {
        this.visitId = String(row.visitId);
        this.doDiff();
      },
      /* 批量对比 */
      doBatchDiff: function () {
        var vm = this;
        var ids = (vm.batchVisitIds || '').split(/[,;\s\n]+/).filter(Boolean).map(Number).filter(function (n) { return n > 0; });
        if (!ids.length) { ElementPlus.ElMessage.warning('请输入就诊ID列表'); return; }
        vm.batchLoading = true;
        HIS.post('/api/his/mr/quality-diff/batch', { visitIds: ids }).then(function (d) {
          vm.batchRows = d || [];
        }).catch(function () { vm.batchRows = []; }).finally(function () { vm.batchLoading = false; });
      },
      tagType: function (count) { return count > 0 ? 'danger' : 'success'; }
    },
    template: [
      '<div style="display:flex;flex-direction:column;height:100%;padding:12px;gap:10px;">',
      '  <h3 style="margin:0;">质控前后对比</h3>',
      '  <el-tabs v-model="activeTab">',
      /* Tab 1: 单份对比 */
      '    <el-tab-pane label="单份对比" name="single">',
      '      <div style="display:flex;gap:8px;margin-bottom:10px;align-items:center;">',
      '        <el-input v-model="visitId" placeholder="就诊ID" size="small" style="width:200px;"></el-input>',
      '        <el-button type="primary" size="small" @click="doDiff()" :loading="loading">对比</el-button>',
      '      </div>',
      /* 选择已编目病案 */
      '      <div style="margin-bottom:10px;"><span style="font-size:12px;color:#909399;">或从已编目病案中选择:</span></div>',
      '      <el-table :data="catalogs" border size="small" v-loading="catLoading" max-height="180" style="width:100%;margin-bottom:12px;" empty-text="暂无已编目病案" @row-click="selectCatalog">',
      '        <el-table-column prop="catalogNo" label="病案号" width="110"></el-table-column>',
      '        <el-table-column prop="patientName" label="姓名" width="80"></el-table-column>',
      '        <el-table-column prop="visitId" label="就诊ID" width="140"></el-table-column>',
      '        <el-table-column prop="mainDiagName" label="主诊断" min-width="130" show-overflow-tooltip></el-table-column>',
      '      </el-table>',
      /* 对比结果 */
      '      <div v-if="result" v-loading="loading">',
      '        <el-alert :title="result.summary" type="info" :closable="false" show-icon style="margin-bottom:10px;"></el-alert>',
      '        <div style="display:flex;gap:12px;flex-wrap:wrap;">',
      /* 新增错误 */
      '          <div style="flex:1;min-width:280px;">',
      '            <h4 style="margin:0 0 6px;color:#F56C6C;">新增({{ (result.added||[]).length }})</h4>',
      '            <el-table :data="result.added" border size="small" max-height="200" empty-text="无新增">',
      '              <el-table-column prop="ruleCategory" label="类别" width="70"></el-table-column>',
      '              <el-table-column prop="ruleCode" label="规则" width="140"></el-table-column>',
      '              <el-table-column prop="errorMsg" label="描述" min-width="150" show-overflow-tooltip></el-table-column>',
      '            </el-table>',
      '          </div>',
      /* 消除错误 */
      '          <div style="flex:1;min-width:280px;">',
      '            <h4 style="margin:0 0 6px;color:#67C23A;">已消除({{ (result.resolved||[]).length }})</h4>',
      '            <el-table :data="result.resolved" border size="small" max-height="200" empty-text="无消除">',
      '              <el-table-column prop="ruleCategory" label="类别" width="70"></el-table-column>',
      '              <el-table-column prop="ruleCode" label="规则" width="140"></el-table-column>',
      '              <el-table-column prop="errorMsg" label="描述" min-width="150" show-overflow-tooltip></el-table-column>',
      '            </el-table>',
      '          </div>',
      '        </div>',
      /* 仍存在 */
      '        <div style="margin-top:10px;">',
      '          <h4 style="margin:0 0 6px;color:#E6A23C;">仍存在({{ (result.unchanged||[]).length }})</h4>',
      '          <el-table :data="result.unchanged" border size="small" max-height="160" empty-text="无">',
      '            <el-table-column prop="ruleCategory" label="类别" width="70"></el-table-column>',
      '            <el-table-column prop="ruleCode" label="规则" width="140"></el-table-column>',
      '            <el-table-column prop="fieldKey" label="定位字段" width="130"></el-table-column>',
      '            <el-table-column prop="errorMsg" label="描述" min-width="180" show-overflow-tooltip></el-table-column>',
      '          </el-table>',
      '        </div>',
      '      </div>',
      '      <div v-else-if="!loading" style="color:#909399;text-align:center;padding:40px;">输入就诊ID或点击上方表格行查看质控前后对比</div>',
      '    </el-tab-pane>',
      /* Tab 2: 批量对比 */
      '    <el-tab-pane label="批量对比" name="batch">',
      '      <div style="margin-bottom:10px;">',
      '        <el-input v-model="batchVisitIds" type="textarea" :rows="3" placeholder="输入多个就诊ID(逗号或换行分隔)" size="small" style="max-width:500px;"></el-input>',
      '      </div>',
      '      <el-button type="primary" size="small" @click="doBatchDiff()" :loading="batchLoading">批量对比</el-button>',
      '      <el-table :data="batchRows" border size="small" v-loading="batchLoading" style="width:100%;margin-top:12px;" empty-text="暂无数据">',
      '        <el-table-column prop="visitId" label="就诊ID" width="140"></el-table-column>',
      '        <el-table-column prop="patientName" label="姓名" width="80"></el-table-column>',
      '        <el-table-column prop="beforeCount" label="编目前" width="80" align="center"></el-table-column>',
      '        <el-table-column prop="afterCount" label="编目后" width="80" align="center"></el-table-column>',
      '        <el-table-column label="新增" width="70" align="center"><template #default="s"><el-tag size="small" :type="tagType(s.row.addedCount)">{{ s.row.addedCount }}</el-tag></template></el-table-column>',
      '        <el-table-column label="消除" width="70" align="center"><template #default="s"><el-tag size="small" type="success">{{ s.row.resolvedCount }}</el-tag></template></el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>'
    ].join('\n')
  };
})();
