/* 系统维护字典(病案统计科侧 P2, 收尾 P1 遗留 F): 病案基础/卫统基础/病区/医疗小组/节假日五类通用码表 CRUD。
 * 后端 /api/his/mr/dict: types(GET) / list(GET type,keyword,validFlag) / options(GET) / POST{type} / PUT{id} / DEL{id}。
 * 员工/科室复用既有主数据(基础数据域维护), 不在此重复; 写权限本机构管理员(后端 requireSelfOrgWrite 兜底)。
 * 运行时模板仅访问组件作用域; 文件级助手须以 methods 暴露(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrMaintain = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false,
        tab: 'case_base',
        rows: [],
        keyword: '',
        validFlag: null,
        tabs: [
          { name: 'case_base', label: '病案基础', codeHint: '编码', nameHint: '名称', ext1: '说明/分类', ext2: '备注属性' },
          { name: 'wt_base', label: '卫统基础', codeHint: '卫统代码', nameHint: '名称', ext1: '取值口径', ext2: '' },
          { name: 'ward', label: '病区', codeHint: '病区编码', nameHint: '病区名称', ext1: '所属科室', ext2: '' },
          { name: 'med_team', label: '医疗小组', codeHint: '小组编码', nameHint: '小组名称', ext1: '组长', ext2: '所属科室' },
          { name: 'holiday', label: '节假日', codeHint: '日期(yyyy-MM-dd)', nameHint: '名称', ext1: '', ext2: '类型(法定/调休)' }
        ],
        form: { visible: false, saving: false, id: null, code: '', name: '', parentCode: '', ext1: '', ext2: '', sortNo: 0, validFlag: 1, remark: '' }
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      },
      meta: function () {
        var t = this.tab;
        var hit = null;
        this.tabs.forEach(function (x) { if (x.name === t) { hit = x; } });
        return hit || this.tabs[0];
      }
    },
    created: function () {
      this.loadList();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      seqNo: function (i) { return i + 1; },
      onTab: function () { this.keyword = ''; this.loadList(); },
      loadList: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/mr/dict/list?type=' + encodeURIComponent(vm.tab) + '&page=1&size=500';
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.validFlag != null) { q += '&validFlag=' + vm.validFlag; }
        HIS.get(q).then(function (d) { vm.rows = (d && d.records) || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      search: function () { this.loadList(); },
      openCreate: function () {
        this.form = { visible: true, saving: false, id: null, code: '', name: '', parentCode: '', ext1: '', ext2: '', sortNo: 0, validFlag: 1, remark: '' };
      },
      openEdit: function (row) {
        this.form = {
          visible: true, saving: false, id: row.id, code: row.code, name: row.name, parentCode: row.parentCode,
          ext1: row.ext1, ext2: row.ext2, sortNo: row.sortNo, validFlag: row.validFlag, remark: row.remark
        };
      },
      doSave: function () {
        var vm = this;
        if (!vm.form.code || !vm.form.name) { ElementPlus.ElMessage.warning('编码与名称必填'); return; }
        vm.form.saving = true;
        var p = vm.form.id ? HIS.put('/api/his/mr/dict/' + HIS.id(vm.form.id), vm.form)
          : HIS.post('/api/his/mr/dict/' + encodeURIComponent(vm.tab), vm.form);
        p.then(function () {
          HIS.notifySuccess(vm.form.id ? '已保存' : '已新增');
          vm.form.visible = false;
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.form.saving = false; });
      },
      doDelete: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除「' + row.name + '」?', '提示', { type: 'warning' }).then(function () {
          HIS.del('/api/his/mr/dict/' + HIS.id(row.id)).then(function () {
            HIS.notifySuccess('已删除');
            vm.loadList();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      }
    },
    template: [
      '<div class="page-card cd-fill" v-loading="loading">',
      '  <div class="page-title">系统维护字典 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">病案基础 / 卫统基础 / 病区 / 医疗小组 / 节假日(员工·科室走基础数据)</span></div>',
      '  <el-tabs v-model="tab" @tab-change="onTab" style="flex:1;min-height:0;display:flex;flex-direction:column;">',
      '    <el-tab-pane v-for="t in tabs" :key="t.name" :label="t.label" :name="t.name">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-input v-model="keyword" size="small" placeholder="编码 / 名称" clearable style="width:200px;" @keyup.enter="search"></el-input>',
      '        <el-select v-model="validFlag" size="small" clearable placeholder="状态" style="width:110px"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '        <el-button size="small" type="primary" @click="search">查询</el-button>',
      '        <span style="flex:1"></span>',
      '        <el-button v-if="canWrite" size="small" type="primary" @click="openCreate">新增</el-button>',
      '      </div>',
      '      <el-table :data="rows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="暂无数据(可点新增维护)">',
      '        <el-table-column type="index" :index="seqNo" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column prop="code" label="编码" width="150"></el-table-column>',
      '        <el-table-column prop="name" label="名称" min-width="150"></el-table-column>',
      '        <el-table-column v-if="t.ext1" prop="ext1" :label="t.ext1" min-width="130"></el-table-column>',
      '        <el-table-column v-if="t.ext2" prop="ext2" :label="t.ext2" min-width="130"></el-table-column>',
      '        <el-table-column prop="sortNo" label="排序" width="70" align="center"></el-table-column>',
      '        <el-table-column label="状态" width="80" align="center"><template #default="s"><el-tag size="small" :type="s.row.validFlag===1?\'success\':\'info\'">{{ s.row.validFlag===1?\'启用\':\'停用\' }}</el-tag></template></el-table-column>',
      '        <el-table-column v-if="canWrite" label="操作" width="120" align="center" fixed="right">',
      '          <template #default="s">',
      '            <el-button type="primary" link size="small" @click="openEdit(s.row)">编辑</el-button>',
      '            <el-button type="danger" link size="small" @click="doDelete(s.row)">删除</el-button>',
      '          </template>',
      '        </el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>',
      /* ---- 新增/编辑弹窗 ---- */
      '<el-dialog v-model="form.visible" :title="(form.id?\'编辑\':\'新增\') + \' · \' + meta.label" width="480px" destroy-on-close>',
      '  <el-form label-width="100px" size="default">',
      '    <el-form-item label="编码" required><el-input v-model="form.code" :placeholder="meta.codeHint"></el-input></el-form-item>',
      '    <el-form-item label="名称" required><el-input v-model="form.name" :placeholder="meta.nameHint"></el-input></el-form-item>',
      '    <el-form-item v-if="meta.ext1" :label="meta.ext1"><el-input v-model="form.ext1"></el-input></el-form-item>',
      '    <el-form-item v-if="meta.ext2" :label="meta.ext2"><el-input v-model="form.ext2"></el-input></el-form-item>',
      '    <el-form-item label="上级编码"><el-input v-model="form.parentCode" placeholder="选填(层级)"></el-input></el-form-item>',
      '    <el-form-item label="排序"><el-input v-model.number="form.sortNo" placeholder="0"></el-input></el-form-item>',
      '    <el-form-item label="状态"><el-radio-group v-model="form.validFlag"><el-radio :label="1">启用</el-radio><el-radio :label="0">停用</el-radio></el-radio-group></el-form-item>',
      '    <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2"></el-input></el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="form.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="form.saving" @click="doSave">保存</el-button>',
      '  </template>',
      '</el-dialog>'
    ].join('\n')
  };
})();
