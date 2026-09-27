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
  var WH_TYPE_TEXT = { WESTERN: '西药', TCM: '中药', MIXED: '综合' };

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
})();
