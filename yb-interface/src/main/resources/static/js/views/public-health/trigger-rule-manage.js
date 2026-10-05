/* 公共卫生管理-报卡触发规则维护(全局表 his_disease_report_trigger_rule)。
 * 把"哪个诊断触发哪类报卡"从后端硬编码升格为可维护数据: 新增/编辑/删除/行内启停, 保存诊断的触发判定即时生效(服务端每次判定查表)。
 * 后端 /api/his/disease-report/trigger-rules: GET 全量(含停用) / POST 新增 / PUT /{id} 修改 / PUT /{id}/toggle?enabled= 启停 / DELETE /{id};
 * 全部受 requireAdminOrSuper 守卫(仅 ADMIN/SUPER_ADMIN); 菜单 public-health/report-trigger-rule 走 all_menus 自动可见。
 * 判定口径: 启用规则按 priority 升序首个命中即定大类(种子: 肿瘤10 < 传染病20 < 精障30 < 慢病40); 表清空 = 全局不自动弹卡。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var CAT_TEXT = { 1: '法定传染病', 2: '严重精神障碍', 3: '恶性肿瘤', 4: '高血压', 5: '糖尿病', 9: '其他' };
  var MATCH_TEXT = { prefix: '诊断码前缀', exact: '诊断码精确', class: '诊断类别' };

  HIS.views.TriggerRuleManage = {
    name: 'TriggerRuleManage',
    data: function () {
      return {
        loading: false,
        rows: [],
        filterCat: null,
        filterEnabled: null,
        keyword: '',
        pageNo: 1,
        pageSize: 20,
        catOpts: Object.keys(CAT_TEXT).map(function (k) { return { value: Number(k), label: CAT_TEXT[k] }; }),
        matchOpts: Object.keys(MATCH_TEXT).map(function (k) { return { value: k, label: MATCH_TEXT[k] }; }),
        /* 新增/编辑弹窗 */
        dlg: { visible: false, saving: false, editing: false, form: {} }
      };
    },
    computed: {
      filtered: function () {
        var vm = this;
        var kw = (vm.keyword || '').trim().toLowerCase();
        return vm.rows.filter(function (r) {
          if (vm.filterCat != null && Number(r.reportCategory) !== vm.filterCat) { return false; }
          if (vm.filterEnabled != null && Number(r.enabled) !== vm.filterEnabled) { return false; }
          if (kw) {
            var hay = (String(r.codePattern || '') + ' ' + String(r.remark || '')).toLowerCase();
            if (hay.indexOf(kw) < 0) { return false; }
          }
          return true;
        });
      },
      paged: function () {
        var start = (this.pageNo - 1) * this.pageSize;
        return this.filtered.slice(start, start + this.pageSize);
      }
    },
    created: function () { this.load(); },
    methods: {
      catText: function (c) { return CAT_TEXT[c] || (c == null ? '-' : String(c)); },
      matchText: function (m) { return MATCH_TEXT[m] || m || '-'; },
      seqNo: function (i) { return (this.pageNo - 1) * this.pageSize + i + 1; },
      fmt: function (v) { return (v || '').toString().replace('T', ' ').slice(0, 16); },
      load: function () {
        var vm = this;
        vm.loading = true;
        HIS.get('/api/his/disease-report/trigger-rules').then(function (d) {
          vm.rows = d || [];
        }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); }).finally(function () { vm.loading = false; });
      },
      search: function () { this.pageNo = 1; },
      resetFilter: function () { this.filterCat = null; this.filterEnabled = null; this.keyword = ''; this.pageNo = 1; },
      onPage: function (p) { this.pageNo = p; },
      onSize: function (s) { this.pageSize = s; this.pageNo = 1; },
      /* ================= 新增/编辑 ================= */
      openAdd: function () {
        this.dlg = { visible: true, saving: false, editing: false, form: { reportCategory: 1, matchType: 'prefix', codePattern: '', priority: 20, enabled: 1, remark: '' } };
      },
      openEdit: function (row) {
        this.dlg = { visible: true, saving: false, editing: true, form: Object.assign({}, row) };
      },
      saveDlg: function () {
        var vm = this;
        var f = vm.dlg.form;
        if (!f.codePattern || !String(f.codePattern).trim()) { ElementPlus.ElMessage.warning('匹配模式不能为空'); return; }
        vm.dlg.saving = true;
        var body = { reportCategory: Number(f.reportCategory), matchType: f.matchType, codePattern: String(f.codePattern).trim(), priority: Number(f.priority) || 100, enabled: Number(f.enabled), remark: f.remark || null };
        var p = vm.dlg.editing
          ? HIS.put('/api/his/disease-report/trigger-rules/' + encodeURIComponent(f.id), body)
          : HIS.post('/api/his/disease-report/trigger-rules', body);
        p.then(function () {
          HIS.notifySuccess && HIS.notifySuccess(vm.dlg.editing ? '规则已更新, 触发判定即时生效' : '规则已新增');
          vm.dlg.visible = false;
          vm.load();
        }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); }).finally(function () { vm.dlg.saving = false; });
      },
      /* ================= 启停/删除 ================= */
      toggleEnable: function (row, val) {
        var vm = this;
        HIS.put('/api/his/disease-report/trigger-rules/' + encodeURIComponent(row.id) + '/toggle?enabled=' + (val ? 1 : 0), {}).then(function () {
          row.enabled = val ? 1 : 0;
          HIS.notifySuccess && HIS.notifySuccess(val ? '已启用: 该规则参与报卡触发' : '已停用: 命中该模式的诊断不再弹卡');
        }).catch(function (e) { HIS.notifyError && HIS.notifyError(e); row.enabled = !val; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除规则「' + vm.catText(row.reportCategory) + ' · ' + row.codePattern + '」? 删除后命中该模式的诊断不再自动弹卡。', '删除触发规则', { type: 'warning' }).then(function () {
          return HIS.del('/api/his/disease-report/trigger-rules/' + encodeURIComponent(row.id));
        }).then(function () {
          HIS.notifySuccess && HIS.notifySuccess('已删除');
          vm.load();
        }).catch(function (e) { if (e && e.message) { HIS.notifyError && HIS.notifyError(e); } });
      }
    },
    template: `
      <div class="dw-panel" style="padding:12px">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
          <h3 style="margin:0">报卡触发规则维护 <span class="dim">(共 {{ rows.length }} 条, 启用规则按优先级小者先判, 首个命中即定报卡大类)</span></h3>
          <div>
            <el-button size="small" @click="load">刷新</el-button>
            <el-button size="small" type="primary" @click="openAdd">新增规则</el-button>
          </div>
        </div>

        <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px">
          <el-select v-model="filterCat" placeholder="报卡大类" clearable size="small" style="width:140px">
            <el-option v-for="c in catOpts" :key="c.value" :label="c.label" :value="c.value"></el-option>
          </el-select>
          <el-select v-model="filterEnabled" placeholder="启停状态" clearable size="small" style="width:110px">
            <el-option :value="1" label="启用"></el-option>
            <el-option :value="0" label="停用"></el-option>
          </el-select>
          <el-input v-model="keyword" size="small" style="width:220px" placeholder="编码模式/备注关键字" clearable @keyup.enter="search"></el-input>
          <el-button size="small" type="primary" @click="search">查询</el-button>
          <el-button size="small" @click="resetFilter">重置</el-button>
        </div>

        <el-table :data="paged" size="small" border v-loading="loading" max-height="560" style="width:100%">
          <el-table-column label="#" width="52"><template #default="s">{{ seqNo(s.$index) }}</template></el-table-column>
          <el-table-column label="报卡大类" width="120"><template #default="s"><el-tag size="small">{{ catText(s.row.reportCategory) }}</el-tag></template></el-table-column>
          <el-table-column label="匹配方式" width="110"><template #default="s">{{ matchText(s.row.matchType) }}</template></el-table-column>
          <el-table-column prop="codePattern" label="编码/模式" width="130" show-overflow-tooltip></el-table-column>
          <el-table-column prop="priority" label="优先级" width="80" sortable></el-table-column>
          <el-table-column label="启用" width="80">
            <template #default="s"><el-switch :model-value="Number(s.row.enabled) === 1" @change="toggleEnable(s.row, $event)"></el-switch></template>
          </el-table-column>
          <el-table-column prop="remark" label="备注(病种/依据)" min-width="240" show-overflow-tooltip></el-table-column>
          <el-table-column label="更新" width="150"><template #default="s">{{ fmt(s.row.updateTime) }}<span class="dim" v-if="s.row.updateBy"> · {{ s.row.updateBy }}</span></template></el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="s">
              <el-button size="small" link type="primary" @click="openEdit(s.row)">编辑</el-button>
              <el-button size="small" link type="danger" @click="del(s.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div style="display:flex;justify-content:flex-end;margin-top:12px">
          <el-pagination background layout="total, prev, pager, next, sizes" :total="filtered.length"
            v-model:current-page="pageNo" v-model:page-size="pageSize" :page-sizes="[20,50,100]"
            @current-change="onPage" @size-change="onSize"></el-pagination>
        </div>

        <el-dialog v-model="dlg.visible" :title="dlg.editing ? '编辑触发规则' : '新增触发规则'" width="480px" append-to-body>
          <el-form label-width="92px" size="small">
            <el-form-item label="报卡大类" required>
              <el-select v-model="dlg.form.reportCategory" style="width:100%">
                <el-option v-for="c in catOpts" :key="c.value" :label="c.label" :value="c.value"></el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="匹配方式" required>
              <el-select v-model="dlg.form.matchType" style="width:100%">
                <el-option v-for="m in matchOpts" :key="m.value" :label="m.label" :value="m.value"></el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="编码/模式" required>
              <el-input v-model="dlg.form.codePattern" placeholder="如 A01(前缀) / I10(精确) / tumor(诊断类别)"></el-input>
            </el-form-item>
            <el-form-item label="优先级">
              <el-input-number v-model="dlg.form.priority" :min="1" :max="999" controls-position="right"></el-input-number>
              <span class="dim" style="margin-left:8px">小者先判, 首个命中即定大类</span>
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="dlg.form.enabled" :active-value="1" :inactive-value="0"></el-switch>
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="dlg.form.remark" type="textarea" :rows="2" placeholder="病种名/法规依据"></el-input>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="dlg.visible=false">取消</el-button>
            <el-button size="small" type="primary" :loading="dlg.saving" @click="saveDlg">保存</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };
})();
