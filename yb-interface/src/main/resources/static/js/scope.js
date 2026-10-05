/* 全局"当前库房/当前药房"上下文(四期: 药房/药库与科室一一对应 + 科室授权):
 * - HIS.scope: 模块级 Vue.reactive 单例, 跨视图共享; 列表来自后端按授权科室过滤的 warehouse-def / pharmacy-def;
 * - 当前 whId/phId 持久于 localStorage, 切换页面(导航重挂载)与重新登录后保持; 缓存 id 不在授权集合内则回退列表首项;
 * - whScopeMixin/phScopeMixin: 以计算属性别名(warehouseDefs/warehouseId、pharmacyDefs/pharmacyId)接管视图原有的
 *   本地过滤态, 使"锁定单库房/单药房 + 全局持久切换"接入无需逐处改写查询方法。
 * 依赖: 在 /js/api.js 之后、各 views 之前加载(Vue 已由 index.html 顶部引入)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  var LS_WH = 'his.whScope.whId';
  var LS_PH = 'his.whScope.phId';
  var WH_TYPE_TEXT = { WESTERN: '西药库', TCM: '中药库', MIXED: '综合库' };
  var PHARM_TYPE_TEXT = { OUTPATIENT: '门诊药房', INPATIENT: '住院药房', TCM: '中药房' };

  /* 共享响应式上下文(单例) */
  HIS.scope = Vue.reactive({
    warehouses: [],   /* 当前用户可操作的药库(kind=WAREHOUSE, 已按授权科室过滤) */
    pharmacies: [],   /* 当前用户可操作的药房(已按授权科室过滤) */
    whId: null,       /* 当前药库 id */
    phId: null,       /* 当前药房 id */
    whLoaded: false,
    phLoaded: false
  });

  /* 校正当前选择: 缓存 id 命中列表则保留, 否则回退首项(空列表返回 null) */
  function pickCurrent(list, cached) {
    var cid = Number(cached);
    if (!list || !list.length) { return null; }
    for (var i = 0; i < list.length; i++) { if (list[i].id === cid) { return cid; } }
    return list[0].id;
  }

  HIS.loadWarehouses = function (force) {
    if (HIS.scope.whLoaded && !force) { return Promise.resolve(HIS.scope.warehouses); }
    return HIS.get('/api/his/stock/warehouse-def').then(function (list) {
      list = list || [];
      HIS.scope.warehouses = list;
      HIS.scope.whLoaded = true;
      HIS.scope.whId = pickCurrent(list, localStorage.getItem(LS_WH));
      if (HIS.scope.whId !== null) { localStorage.setItem(LS_WH, HIS.scope.whId); }
      return list;
    }).catch(HIS.notifyError);
  };
  HIS.setWh = function (id) {
    HIS.scope.whId = (id === '' || id === undefined) ? null : id;
    if (HIS.scope.whId !== null) { localStorage.setItem(LS_WH, HIS.scope.whId); }
  };

  HIS.loadPharmacies = function (force) {
    if (HIS.scope.phLoaded && !force) { return Promise.resolve(HIS.scope.pharmacies); }
    return HIS.get('/api/his/pharmacy/pharmacy-def').then(function (list) {
      list = list || [];
      HIS.scope.pharmacies = list;
      HIS.scope.phLoaded = true;
      HIS.scope.phId = pickCurrent(list, localStorage.getItem(LS_PH));
      if (HIS.scope.phId !== null) { localStorage.setItem(LS_PH, HIS.scope.phId); }
      return list;
    }).catch(HIS.notifyError);
  };
  HIS.setPh = function (id) {
    HIS.scope.phId = (id === '' || id === undefined) ? null : id;
    if (HIS.scope.phId !== null) { localStorage.setItem(LS_PH, HIS.scope.phId); }
  };

  function findIn(list, id) {
    for (var i = 0; i < (list || []).length; i++) { if (list[i].id === id) { return list[i]; } }
    return null;
  }

  /* 药库作用域混入: 视图声明 mixins:[HIS.whScopeMixin] 并移除本地 warehouseId/warehouseDefs 与 loadWarehouseDefs */
  HIS.whScopeMixin = {
    data: function () { return { scope: HIS.scope }; },
    computed: {
      warehouseDefs: function () { return HIS.scope.warehouses; },
      warehouseId: {
        get: function () { return HIS.scope.whId; },
        set: function (v) { HIS.setWh(v); }
      },
      currentWarehouse: function () { return findIn(HIS.scope.warehouses, HIS.scope.whId); },
      /* 顶栏展示文本: 名称(类型) */
      currentWhLabel: function () {
        var w = this.currentWarehouse;
        if (!w) { return ''; }
        return w.name + (w.warehouseType ? '（' + (WH_TYPE_TEXT[w.warehouseType] || w.warehouseType) + '）' : '');
      },
      whSingle: function () { return HIS.scope.warehouses.length <= 1; }
    },
    methods: {
      /* 供 created 链式: 授权药库加载 + 当前库校正完成后 resolve */
      loadWarehouseDefs: function () { return HIS.loadWarehouses(); },
      onWhSwitch: function (id) { HIS.setWh(id); }
    }
  };

  /* 药房作用域混入(药房站): 视图移除本地 pharmacyId/pharmacyDefs 与对模块函数 loadPharmacyOpts 的调用 */
  HIS.phScopeMixin = {
    data: function () { return { scope: HIS.scope }; },
    computed: {
      pharmacyDefs: function () { return HIS.scope.pharmacies; },
      pharmacyId: {
        get: function () { return HIS.scope.phId; },
        set: function (v) { HIS.setPh(v); }
      },
      currentPharmacy: function () { return findIn(HIS.scope.pharmacies, HIS.scope.phId); },
      currentPhLabel: function () {
        var p = this.currentPharmacy;
        return p ? p.name : '';
      },
      phSingle: function () { return HIS.scope.pharmacies.length <= 1; }
    },
    methods: {
      loadPharmacyOpts: function () { return HIS.loadPharmacies(); },
      onPhSwitch: function (id) { HIS.setPh(id); }
    }
  };

  /* ================= 统一"库房上下文条"组件(全局注册 <scope-bar>) =================
   * mode='warehouse' 读 HIS.scope.warehouses/whId; mode='pharmacy' 读 pharmacies/phId。
   * 目标: 一眼看清"当前哪个库 + 归属哪个科室 + 类型"(左色条按类型区分), 切换走显式确认弹窗防误操作。
   * 切换后 emit('switched', id) 由宿主视图重查当前页数据。 */
  HIS.components = HIS.components || {};
  HIS.components.ScopeBar = {
    props: { mode: { type: String, default: 'warehouse' } },
    data: function () {
      return { scope: HIS.scope, switchDlg: false, tempId: null };
    },
    computed: {
      isWh: function () { return this.mode !== 'pharmacy'; },
      kindLabel: function () { return this.isWh ? '药库' : '药房'; },
      list: function () { return this.isWh ? this.scope.warehouses : this.scope.pharmacies; },
      curId: function () { return this.isWh ? this.scope.whId : this.scope.phId; },
      cur: function () { return findIn(this.list, this.curId); },
      single: function () { return this.list.length <= 1; },
      empty: function () { return this.list.length === 0; },
      hasCur: function () { return !!this.cur; },
      canSwitch: function () { return !this.single && !!this.cur; },
      showLock: function () { return this.single && !!this.cur; },
      /* 根容器色条修饰类: 按当前库类型着色, 无库时空态 */
      rootClass: function () { return this.cur ? this.typeClass(this.cur) : 'is-empty'; }
    },
    methods: {
      typeLabel: function (it) {
        if (!it) { return ''; }
        if (this.isWh) { return WH_TYPE_TEXT[it.warehouseType] || '综合库'; }
        return PHARM_TYPE_TEXT[it.pharmacyType] || '药房';
      },
      typeClass: function (it) {
        if (!it) { return 'is-mixed'; }
        if (this.isWh) {
          if (it.warehouseType === 'WESTERN') { return 'is-western'; }
          if (it.warehouseType === 'TCM') { return 'is-tcm'; }
          return 'is-mixed';
        }
        return it.pharmacyType === 'TCM' ? 'is-tcm' : 'is-pharmacy';
      },
      deptText: function (it) { return (it && it.deptName) ? it.deptName : '未绑定科室'; },
      openSwitch: function () { this.tempId = this.curId; this.switchDlg = true; },
      confirmSwitch: function () {
        if (this.tempId === null || this.tempId === this.curId) { this.switchDlg = false; return; }
        var it = findIn(this.list, this.tempId);
        var nm = it ? it.name : '';
        var dp = it && it.deptName ? '（科室 ' + it.deptName + '）' : '';
        if (this.isWh) { HIS.setWh(this.tempId); } else { HIS.setPh(this.tempId); }
        this.switchDlg = false;
        ElementPlus.ElMessage.success('已切换到 ' + nm + dp);
        this.$emit('switched', this.tempId);
      }
    },
    template: [
      '<div class="scope-bar" :class="rootClass">',
      '  <div class="scope-bar__main">',
      '    <span class="scope-bar__kind">当前{{ kindLabel }}</span>',
      '    <span class="scope-bar__name" v-if="hasCur">{{ cur.name }}</span>',
      '    <span class="scope-bar__name is-empty-text" v-else>无可操作{{ kindLabel }}</span>',
      '    <span class="scope-bar__badge" v-if="hasCur">{{ typeLabel(cur) }}</span>',
      '    <span class="scope-bar__dept" v-if="hasCur">归属科室：<b>{{ deptText(cur) }}</b></span>',
      '  </div>',
      '  <div class="scope-bar__act">',
      '    <el-button v-if="canSwitch" size="small" @click="openSwitch">⇄ 切换{{ kindLabel }}</el-button>',
      '    <el-tag v-if="showLock" size="small" type="info">唯一授权{{ kindLabel }}</el-tag>',
      '    <span class="scope-bar__hint" v-if="hasCur">本页所有操作仅作用于当前{{ kindLabel }}</span>',
      '  </div>',
      '  <div class="scope-bar__empty" v-if="empty">当前账号无可操作的{{ kindLabel }}：请先在「{{ isWh ? \'药库管理\' : \'药房管理\' }}」为库房绑定归属科室，并在「职工管理」维护本人任职/授权科室。</div>',
      '  <el-dialog v-model="switchDlg" :title="\'切换\' + kindLabel" width="460px" append-to-body>',
      '    <div class="scope-switch__tip">切换会同步改变所有{{ kindLabel }}页面的当前上下文，请选中后点“确认切换”。</div>',
      '    <el-radio-group v-model="tempId" class="scope-switch__list">',
      '      <label class="scope-switch__item" :class="{\'is-cur\': it.id===curId}" v-for="it in list" :key="it.id">',
      '        <el-radio :label="it.id">',
      '          <span class="scope-switch__dot" :class="typeClass(it)"></span>',
      '          <span class="scope-switch__nm">{{ it.name }}</span>',
      '          <span class="scope-switch__meta">{{ typeLabel(it) }} · {{ deptText(it) }}</span>',
      '          <el-tag v-if="it.id===curId" size="mini" type="success">当前</el-tag>',
      '        </el-radio>',
      '      </label>',
      '    </el-radio-group>',
      '    <template #footer>',
      '      <el-button @click="switchDlg=false">取消</el-button>',
      '      <el-button type="primary" :disabled="tempId===null || tempId===curId" @click="confirmSwitch">确认切换</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
