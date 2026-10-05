/* ==========================================================================
 * 科室树形选择器(可复用录入组件): HIS.components.DeptTreePicker
 * 形态: 保留可拼音检索的 el-select(扁平候选), 右侧加「科室选择」按钮, 点击弹出
 *       el-dialog 内的科室层级树(大类→科室→窗口/诊室)选择并回填, 二者同源同候选集。
 * 数据契约: props.options 为页面已加载的候选科室扁平数组(元素须含 id/deptName/parentId,
 *           来自 /api/his/dept/list|enabled|outpatient 的 HisDept 均可); 组件客户端按
 *           parentId 组装树(父不在集合内的孤儿提升为根), 不额外发请求。
 * 机构隔离: 树/下拉默认按 effectiveOrg 过滤 options —— orgId prop 显式传入优先(表单机构),
 *           未传取登录机构 HIS.currentOrgId(); orgId=false 关闭过滤(展示全部候选)。
 * 层级语义: deptLevel=1 大类为纯分组, 下拉中排除、树中不可选(单选取节点、多选置 disabled)。
 * 回填: 单选 emit('update:modelValue', id) + emit('change', id); 多选 emit 数组。
 * 注册: HIS.components.DeptTreePicker(须在 app.js 及各接入 views 之前加载; 依赖 api.js)。
 * 无构建坑: template 字符串数组必须 .join('\n'); 模板串内禁用 && (用函数/可选计算规避);
 *           类名单引号须转义 \'dtp-cat\'。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* ===== 样式一次性注入(created+mounted 双调, 沿用 cps 同款防丢函数范式) ===== */
  function ensureStyle() {
    if (document.getElementById('dept-tree-picker-css')) { return; }
    var st = document.createElement('style');
    st.id = 'dept-tree-picker-css';
    st.textContent = [
      '.dtp{display:flex;gap:6px;width:100%;align-items:center;}',
      '.dtp .dtp-select{flex:1;min-width:0;}',
      '.dtp-cat{color:var(--yb-ink-3,#909399);}',
      '.dtp-toolbar{display:flex;gap:8px;align-items:center;margin-bottom:10px;}',
      '.dtp-toolbar .dtp-tip{font-size:12px;color:var(--yb-ink-3,#909399);}',
      '.dtp-body{max-height:52vh;overflow:auto;}',
      '.dtp-empty{padding:24px;text-align:center;color:var(--yb-ink-3,#909399);}'
    ].join('\n');
    document.head.appendChild(st);
  }
  ensureStyle();

  /* ===== 客户端按 parentId 组装森林(浅拷贝加 children/idKey, 不改原对象) ===== */
  function buildTree(flat) {
    var map = {};
    var order = [];
    (flat || []).forEach(function (d) {
      var node = {};
      for (var k in d) { if (Object.prototype.hasOwnProperty.call(d, k)) { node[k] = d[k]; } }
      node.idKey = HIS.idKey(d.id);
      node.children = [];
      map[node.idKey] = node;
      order.push(node.idKey);
    });
    var roots = [];
    order.forEach(function (key) {
      var node = map[key];
      var pk = HIS.idKey(node.parentId);
      if (pk && pk !== key && map[pk]) { map[pk].children.push(node); }
      else { roots.push(node); }
    });
    return roots;
  }

  var DeptTreePicker = {
    name: 'DeptTreePicker',
    mixins: [HIS.kwSelectMixin],
    props: {
      modelValue: { default: null },
      options: { type: Array, default: function () { return []; } },
      /* 可选叶子 id 集合: 传入时树展示 options 全部层级但仅该集合可点选、下拉也只列该集合;
         不传(null)=保持原逻辑(下拉排除 deptLevel=1、树中仅大类不可选)。向后兼容 25 处存量站点。 */
      selectableIds: { type: Array, default: null },
      multiple: { type: Boolean, default: false },
      /* 默认 null=取登录机构; 传值=按该机构过滤(职工/用户管理维护表单机构); false=不过滤 */
      orgId: { default: null },
      placeholder: { type: String, default: '选择科室' },
      btnText: { type: String, default: '科室选择' },
      disabled: { type: Boolean, default: false },
      clearable: { type: Boolean, default: true },
      size: { type: String, default: '' },
      width: { type: String, default: '100%' },
      dialogTitle: { type: String, default: '选择科室' }
    },
    emits: ['update:modelValue', 'change'],
    data: function () {
      return { dlg: false, treeQ: '' };
    },
    computed: {
      effectiveOrg: function () {
        if (this.orgId === false) { return null; }
        if (this.orgId !== null && this.orgId !== undefined && this.orgId !== '') { return HIS.idKey(this.orgId); }
        return HIS.idKey(HIS.currentOrgId());
      },
      /* 按机构过滤后的扁平候选(orgId 缺失的元素保留, 命中或无机构维度的都放行) */
      flat: function () {
        var org = this.effectiveOrg;
        var list = this.options || [];
        if (!org) { return list.slice(); }
        return list.filter(function (d) {
          var oid = HIS.idKey(d.orgId);
          return !oid || oid === org;
        });
      },
      /* 下拉候选: 排除大类(deptLevel=1), 只呈现可落库的真实科室/窗口; 若传 selectableIds 则仅列该集合 */
      selectOptions: function () {
        var vm = this;
        return this.flat.filter(function (d) { return vm.isSelectable(d); });
      },
      /* selectableIds 归一为 key 集合(未传=null 表示走旧逻辑) */
      selKeySet: function () {
        if (!this.selectableIds) { return null; }
        var s = {};
        (this.selectableIds || []).forEach(function (id) { s[HIS.idKey(id)] = true; });
        return s;
      },
      /* 当前已选 id 数组(单选归一为 0/1 个, 多选原样) */
      selectedIds: function () {
        var mv = this.modelValue;
        if (this.multiple) { return Array.isArray(mv) ? mv : []; }
        return (mv === null || mv === undefined || mv === '') ? [] : [mv];
      },
      /* 可管理候选集(下拉叶子)的 key 集合: 用于区分"可在树中增删"与"历史遗留无法管理"的值 */
      selectableKeySet: function () {
        var s = {};
        this.selectOptions.forEach(function (d) { s[HIS.idKey(d.id)] = true; });
        return s;
      },
      /* 回显兜底: 已选但不在可选叶子中的 id(历史大类/跨机构遗留值), 从完整 options 查名,
         保证下拉与多选 tag 显示科室名而非裸 id(修复缺陷2 回显退化) */
      displayExtra: function () {
        var sel = this.selectableKeySet;
        var nameMap = {};
        (this.options || []).forEach(function (d) { nameMap[HIS.idKey(d.id)] = d; });
        var extra = []; var seen = {};
        this.selectedIds.forEach(function (x) {
          var k = HIS.idKey(x);
          if (!k || sel[k] || seen[k]) { return; }
          seen[k] = true;
          var d = nameMap[k];
          extra.push({ id: x, deptName: (d && (d.deptName || d.name)) || ('科室#' + x) });
        });
        return extra;
      },
      selectDisplay: function () {
        return this.displayExtra.length ? this.selectOptions.concat(this.displayExtra) : this.selectOptions;
      },
      treeData: function () { return buildTree(this.flat); },
      treeProps: function () {
        var vm = this;
        return {
          children: 'children',
          label: 'deptName',
          disabled: function (data) { return !vm.isSelectable(data); }
        };
      },
      inner: {
        get: function () { return this.modelValue; },
        set: function (v) { this.$emit('update:modelValue', v); }
      }
    },
    watch: {
      treeQ: function (v) { if (this.$refs.tree) { this.$refs.tree.filter(v); } }
    },
    created: function () { ensureStyle(); },
    mounted: function () { ensureStyle(); },
    methods: {
      /* 节点是否可点选: 传了 selectableIds 则按集合判定; 否则仅大类(deptLevel=1)不可选 */
      isSelectable: function (data) {
        if (this.selKeySet) { return !!(data && this.selKeySet[HIS.idKey(data.id)]); }
        return !!(data && data.deptLevel !== 1);
      },
      /* 非可选项(大类分组/不在可选集合): 置灰且点选无效 */
      isCategory: function (data) { return !this.isSelectable(data); },
      labelOf: function (data) { return data.deptName || data.name || ('科室#' + data.id); },
      filterNode: function (value, data) {
        if (!value) { return true; }
        return HIS.kwMatch(data, value, ['deptName', 'deptCode', 'pyCode', 'abbrCode']);
      },
      openDialog: function () {
        this.treeQ = '';
        this.dlg = true;
        var vm = this;
        this.$nextTick(function () { vm.syncSelection(); });
      },
      syncSelection: function () {
        var tree = this.$refs.tree;
        if (!tree) { return; }
        if (this.multiple) {
          var arr = Array.isArray(this.modelValue) ? this.modelValue.map(function (x) { return HIS.idKey(x); }) : [];
          tree.setCheckedKeys(arr);
        } else {
          tree.setCurrentKey(HIS.idKey(this.modelValue));
        }
      },
      onNodeClick: function (data) {
        if (this.multiple) { return; }
        if (this.isCategory(data)) {
          /* 大类为纯分组不可选: 撤销 el-tree 误打的 is-current 高亮, 恢复回显当前值 */
          var tree = this.$refs.tree;
          if (tree) { tree.setCurrentKey(this.modelValue == null ? null : HIS.idKey(this.modelValue)); }
          return;
        }
        this.$emit('update:modelValue', data.id);
        this.$emit('change', data.id);
        this.dlg = false;
      },
      onChange: function (v) { this.$emit('change', v); },
      confirmMulti: function () {
        var vm = this;
        var tree = this.$refs.tree;
        if (!tree) { return; }
        var lookup = {};
        this.flat.forEach(function (d) { lookup[HIS.idKey(d.id)] = d; });
        var sel = this.selectableKeySet;
        var keys = tree.getCheckedKeys() || [];
        var ids = []; var seen = {};
        keys.forEach(function (k) {
          var d = lookup[k];
          if (d && vm.isSelectable(d) && !seen[k]) { seen[k] = true; ids.push(d.id); }
        });
        /* 保留无法在树中管理的既有值(历史大类/跨机构遗留), 避免静默清空用户既有数据权限(修复缺陷1) */
        var orig = Array.isArray(this.modelValue) ? this.modelValue : [];
        orig.forEach(function (x) {
          var k = HIS.idKey(x);
          if (!k || sel[k] || seen[k]) { return; }
          seen[k] = true;
          ids.push(x);
        });
        this.$emit('update:modelValue', ids);
        this.$emit('change', ids);
        this.dlg = false;
      }
    },
    template: [
      '<div class="dtp" :style="{ width: width }">',
      '  <el-select class="dtp-select" v-model="inner" :multiple="multiple" :clearable="clearable" :disabled="disabled" :size="size" filterable :reserve-keyword="false" :filter-method="kwFilter(\'dtp\')" :placeholder="placeholder" @change="onChange">',
      '    <el-option v-for="d in kwOptions(\'dtp\', selectDisplay, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="d.idKey || d.id" :label="d.deptName" :value="d.id"></el-option>',
      '  </el-select>',
      '  <el-button :size="size" :disabled="disabled" @click="openDialog">{{ btnText }}</el-button>',
      '  <el-dialog v-model="dlg" :title="dialogTitle" width="520px" append-to-body>',
      '    <div class="dtp-toolbar">',
      '      <el-input v-model="treeQ" clearable :placeholder="\'搜索科室名称/编码/拼音/简码\'" style="flex:1"></el-input>',
      '      <span class="dtp-tip">点选科室回填</span>',
      '    </div>',
      '    <div class="dtp-body">',
      '      <el-tree v-if="treeData.length" ref="tree" class="dtp-tree" :data="treeData" :props="treeProps" node-key="idKey" :show-checkbox="multiple" :check-strictly="true" :default-expand-all="true" :expand-on-click-node="false" :highlight-current="!multiple" :filter-node-method="filterNode" @node-click="onNodeClick">',
      '        <template #default="{ data }"><span :class="{ \'dtp-cat\': isCategory(data) }">{{ labelOf(data) }}</span></template>',
      '      </el-tree>',
      '      <div v-else class="dtp-empty">当前机构暂无可选科室</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="dlg=false">取消</el-button>',
      '      <el-button v-if="multiple" type="primary" @click="confirmMulti">确定</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  HIS.components.DeptTreePicker = DeptTreePicker;
})();
