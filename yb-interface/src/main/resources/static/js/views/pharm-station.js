/* ============================================================================
 * 住院药师审核工作站(HIS.views['pharm-station'], T35)
 *
 * 职责: 药品类住院医嘱的开立后审方 —— 待审队列 → 药师签名批量通过 / 驳回留原因;
 *       护士审核前由后端强制校验药审通过(InpOrderExecService.batchAudit 前置拦截)。
 *
 * 接口(后端 InpPharmController, /api/his/inp/pharm):
 *   GET  /queue?deptId=&keyword=&page=&size=   待审队列(JOIN 患者/床位/药品目录)
 *   POST /audit   { orderIds: [...] }          批量通过(前端先完成电子签名)
 *   POST /reject  { orderId, reason }          驳回(原因落 pharm_reject_reason)
 *   GET  /stats                                  待审/今日已审/今日驳回(30s 轮询)
 *   GET  /reject-history?visitId=               该就诊驳回历史
 *
 * 布局: 顶部统计条(3 数字卡带左边框色条) + 左 6/右 4 主区:
 *   左 —— 科室/关键词筛选 + 多选待审列表(床号/患者/药品/剂量用法/开立医生) + 分页 + 底部批量工具栏;
 *   右 —— 当前医嘱详情: 患者信息 / 合理用药检查(接口不可用时优雅降级) / 高警示标记 / 驳回历史。
 *
 * 交互: 批量通过与单条通过均先走 HIS.SignaturePad(actionType='pharm_audit') 电子签名,
 *       签名取消('cancelled')静默返回; 驳回用 ElMessageBox.prompt 必填原因。
 * 样式: <style id="pharm-station-style"> 一次性注入, 类前缀 pws-*, 全部走 --yb-* 设计令牌。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var STATS_REFRESH_MS = 30000;

  /* 高警示药品关键词粗判(与后端 InpOrderService 合理用药审查同口径) */
  var HIGH_ALERT_KEYS = ['高警示', '麻醉', '精神', '毒性', '放射'];

  /* ---- 私有样式一次性注入 ---- */
  (function ensureStyles() {
    if (document.getElementById('pharm-station-style')) { return; }
    var st = document.createElement('style');
    st.id = 'pharm-station-style';
    st.textContent = [
      '.pws-page { display:flex; flex-direction:column; height:100%; gap:12px; }',
      /* ---- 统计条: 数字卡 + 左边框色条(对齐医生站 dashboard 风格) ---- */
      '.pws-stats { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)) auto; gap:12px; align-items:stretch; }',
      '.pws-stat { position:relative; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-left:4px solid var(--yb-brand); border-radius:var(--yb-r-md); padding:12px 18px 12px 16px; box-shadow:var(--yb-sh-1); display:flex; flex-direction:column; justify-content:center; gap:2px; overflow:hidden; }',
      '.pws-stat .v { font-size:var(--yb-fs-2xl); font-weight:700; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; font-family:var(--yb-font-mono); line-height:var(--yb-lh-tight); letter-spacing:-.02em; }',
      '.pws-stat .l { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }',
      '.pws-stat.is-brand .v { color:var(--yb-brand); }',
      '.pws-stat.is-success { border-left-color:var(--yb-success); }',
      '.pws-stat.is-success .v { color:var(--yb-success); }',
      '.pws-stat.is-danger { border-left-color:var(--yb-danger); }',
      '.pws-stat.is-danger .v { color:var(--yb-danger); }',
      '.pws-stat .pws-stat-sub { font-size:var(--yb-fs-cap); color:var(--yb-ink-4); }',
      '.pws-stats-meta { display:flex; flex-direction:column; justify-content:center; align-items:flex-end; gap:4px; color:var(--yb-ink-4); font-size:var(--yb-fs-cap); white-space:nowrap; }',
      /* ---- 主区: 左 6 右 4 ---- */
      '.pws-main { flex:1; display:grid; grid-template-columns:minmax(0,6fr) minmax(320px,4fr); gap:12px; min-height:0; }',
      '.pws-left { display:flex; flex-direction:column; gap:10px; min-height:0; background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); padding:12px; }',
      '.pws-filter { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.pws-filter .spacer { flex:1; }',
      '.pws-count { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }',
      '.pws-count b { color:var(--yb-brand); font-variant-numeric:tabular-nums; }',
      '.pws-table-wrap { flex:1; min-height:0; }',
      /* 高警示闪烁标记(对齐医生站 iw-high-alert 风格) */
      '@keyframes pwsBlink { 50% { opacity:.15; } }',
      '.pws-high-alert { color:var(--yb-danger); font-weight:700; cursor:help; animation:pwsBlink .9s step-start infinite; }',
      '.pws-sub { color:var(--yb-ink-4); font-size:var(--yb-fs-cap); margin-left:4px; }',
      '.pws-pager { display:flex; justify-content:flex-end; }',
      /* ---- 底部批量工具栏 ---- */
      '.pws-actionbar { display:flex; align-items:center; gap:10px; padding-top:10px; border-top:1px solid var(--yb-divider); }',
      '.pws-actionbar .sel-tip { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }',
      '.pws-actionbar .sel-tip b { color:var(--yb-brand); font-variant-numeric:tabular-nums; }',
      '.pws-actionbar .spacer { flex:1; }',
      /* ---- 右侧详情 ---- */
      '.pws-right { display:flex; flex-direction:column; gap:10px; min-height:0; overflow:auto; }',
      '.pws-card { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); padding:12px 14px; }',
      '.pws-card-title { font-size:var(--yb-fs-base); font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); }',
      '.pws-kv { display:grid; grid-template-columns:64px 1fr; row-gap:6px; column-gap:8px; font-size:var(--yb-fs-sm); }',
      '.pws-kv .k { color:var(--yb-ink-3); }',
      '.pws-kv .v { color:var(--yb-ink-1); word-break:break-all; }',
      '.pws-empty { flex:1; display:flex; align-items:center; justify-content:center; color:var(--yb-ink-4); font-size:var(--yb-fs-base); background:var(--yb-surface); border:1px dashed var(--yb-border-strong); border-radius:var(--yb-r-md); min-height:200px; }',
      '.pws-warn-item { padding:6px 10px; margin-bottom:6px; background:var(--yb-warning-bg); border:1px solid var(--yb-warning-border); border-radius:var(--yb-r-sm); color:var(--yb-warning-strong); font-size:var(--yb-fs-sm); }',
      '.pws-warn-item.danger { background:var(--yb-danger-bg); border-color:var(--yb-danger-border); color:var(--yb-danger-strong); }',
      '.pws-warn-item.ok { background:var(--yb-success-bg); border-color:var(--yb-success-border); color:var(--yb-success-strong); }',
      '.pws-dim { color:var(--yb-ink-4); font-size:var(--yb-fs-sm); line-height:1.6; }',
      '.pws-rj-item { padding:8px 10px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); margin-bottom:8px; background:var(--yb-surface-2); }',
      '.pws-rj-item .nm { font-weight:600; color:var(--yb-ink-1); font-size:var(--yb-fs-sm); }',
      '.pws-rj-item .rs { color:var(--yb-danger-strong); font-size:var(--yb-fs-sm); margin-top:4px; }',
      '.pws-rj-item .tm { color:var(--yb-ink-4); font-size:var(--yb-fs-cap); margin-top:2px; }',
      '@media (max-width:1280px) { .pws-main { grid-template-columns:1fr; } .pws-right { max-height:44%; } .pws-stats { grid-template-columns:repeat(3,1fr); } .pws-stats-meta { display:none; } }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ---- 工具函数 ---- */
  function fmtTime(v) {
    if (!v) { return '-'; }
    var s = String(v).replace('T', ' ');
    return s.length >= 16 ? s.substring(5, 16) : s;
  }
  function genderText(v) {
    if (v === 1 || v === '1' || v === '男' || v === 'M') { return '男'; }
    if (v === 2 || v === '2' || v === '女' || v === 'F') { return '女'; }
    return v != null && v !== '' ? String(v) : '-';
  }
  function isHighAlert(drugClass) {
    if (!drugClass) { return false; }
    var s = String(drugClass);
    for (var i = 0; i < HIGH_ALERT_KEYS.length; i++) {
      if (s.indexOf(HIGH_ALERT_KEYS[i]) >= 0) { return true; }
    }
    return false;
  }
  function clockNow() {
    var d = new Date();
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  /* ============================================================================
   * 药师审核工作站组件
   * ========================================================================== */
  HIS.views['pharm-station'] = {
    name: 'PharmStation',
    data: function () {
      return {
        /* 统计(30s 轮询) */
        stats: { pending: 0, auditedToday: 0, rejectedToday: 0 },
        statsLoading: false,
        lastStatsTime: '',
        /* 待审队列 */
        loading: false,
        rows: [],
        total: 0,
        page: 1,
        size: 20,
        deptId: null,
        deptOptions: [],
        keyword: '',
        /* 用法/频次字典 */
        usageOptions: [],
        freqOptions: [],
        dictLoaded: false,
        /* 选中状态 */
        selection: [],
        currentRow: null,
        /* 右侧详情: 合理用药检查(接口不可用时优雅降级) + 驳回历史 */
        checkLoading: false,
        checkResult: null,
        checkUnavailable: false,
        historyLoading: false,
        rejectHistory: [],
        /* 操作中 */
        auditing: false
      };
    },
    computed: {
      usageMap: function () {
        var m = {};
        (this.usageOptions || []).forEach(function (o) { m[o.code] = o.name; });
        return m;
      },
      freqMap: function () {
        var m = {};
        (this.freqOptions || []).forEach(function (o) { m[o.code] = o.name; });
        return m;
      },
      currentHighAlert: function () {
        var r = this.currentRow;
        return !!(r && isHighAlert(r.drugClass));
      }
    },
    methods: {
      timeText: fmtTime,
      genderText: genderText,
      /* 药品名: 目录通用名优先, 回落医嘱内容 */
      drugNameOf: function (row) {
        return (row && (row.drugName || row.orderContent)) || '-';
      },
      specOf: function (row) {
        return (row && (row.drugSpec || row.spec)) || '-';
      },
      dosageOf: function (row) {
        if (!row || !row.dosage) { return '-'; }
        return row.dosage + (row.dosageUnit || '');
      },
      usageText: function (code) { return this.usageMap[code] || code || '-'; },
      freqText: function (code) { return this.freqMap[code] || code || '-'; },
      /* 高警示药品粗判(与后端合理用药审查同口径关键词) */
      isHighAlertRow: function (row) { return !!(row && isHighAlert(row.drugClass)); },

      /* ===== 数据加载 ===== */
      loadStats: function () {
        var vm = this;
        vm.statsLoading = true;
        HIS.get('/api/his/inp/pharm/stats')
          .then(function (d) {
            vm.stats = {
              pending: Number(d && d.pending) || 0,
              auditedToday: Number(d && d.auditedToday) || 0,
              rejectedToday: Number(d && d.rejectedToday) || 0
            };
            vm.lastStatsTime = clockNow();
          })
          .catch(function () { /* 统计失败不打扰, 下轮再试 */ })
          .finally(function () { vm.statsLoading = false; });
      },
      loadQueue: function () {
        var vm = this;
        vm.loading = true;
        var url = '/api/his/inp/pharm/queue?page=' + vm.page + '&size=' + vm.size;
        if (vm.deptId) { url += '&deptId=' + HIS.idParam(vm.deptId); }
        if (vm.keyword) { url += '&keyword=' + encodeURIComponent(vm.keyword.trim()); }
        HIS.get(url)
          .then(function (d) {
            vm.rows = (d && d.rows) || [];
            vm.total = Number(d && d.total) || 0;
            /* 数据刷新后清空多选, 当前详情行若已不在队列则重置 */
            vm.selection = [];
            if (vm.currentRow) {
              var still = vm.rows.filter(function (r) { return HIS.sameId(r.id, vm.currentRow.id); });
              if (!still.length) { vm.currentRow = null; }
            }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled')
          .then(function (list) { vm.deptOptions = list || []; })
          .catch(function () { vm.deptOptions = []; });
      },
      loadMedDict: function () {
        var vm = this;
        if (vm.dictLoaded) { return; }
        vm.dictLoaded = true;
        HIS.get('/api/org-catalog/available/med-dict?dictType=usage')
          .then(function (list) { vm.usageOptions = list || []; })
          .catch(function () { vm.usageOptions = []; });
        HIS.get('/api/org-catalog/available/med-dict?dictType=freq')
          .then(function (list) { vm.freqOptions = list || []; })
          .catch(function () { vm.freqOptions = []; });
      },
      /* 合理用药检查(开嘱前预检同源): 接口暂未开放时降级提示, 不阻断审方 */
      loadCheck: function (row) {
        var vm = this;
        vm.checkLoading = true;
        vm.checkResult = null;
        vm.checkUnavailable = false;
        var url = '/api/his/inp/order/rational-check?visitId=' + HIS.idParam(row.inpVisitId)
          + '&drugId=' + HIS.idParam(row.drugId);
        if (row.dosage) { url += '&dosage=' + encodeURIComponent(row.dosage); }
        HIS.get(url)
          .then(function (d) { vm.checkResult = d || null; })
          .catch(function () { vm.checkUnavailable = true; })
          .finally(function () { vm.checkLoading = false; });
      },
      /* 该就诊的药审驳回历史 */
      loadHistory: function (row) {
        var vm = this;
        vm.historyLoading = true;
        vm.rejectHistory = [];
        HIS.get('/api/his/inp/pharm/reject-history?visitId=' + HIS.idParam(row.inpVisitId))
          .then(function (list) { vm.rejectHistory = list || []; })
          .catch(function () { vm.rejectHistory = []; })
          .finally(function () { vm.historyLoading = false; });
      },

      /* ===== 事件 ===== */
      onSearch: function () {
        this.page = 1;
        this.loadQueue();
      },
      onReset: function () {
        var vm = this;
        vm.deptId = null;
        vm.keyword = '';
        vm.page = 1;
        vm.loadQueue();
      },
      onPageChange: function (p) {
        this.page = p;
        this.loadQueue();
      },
      onSelectionChange: function (sel) {
        this.selection = sel || [];
      },
      onCurrentChange: function (row) {
        var vm = this;
        vm.currentRow = row || null;
        if (row) {
          vm.loadCheck(row);
          vm.loadHistory(row);
        } else {
          vm.checkResult = null;
          vm.checkUnavailable = false;
          vm.rejectHistory = [];
        }
      },
      refreshAll: function () {
        this.loadQueue();
        this.loadStats();
      },

      /* ===== 审核动作(均先电子签名, actionType=pharm_audit 留痕) ===== */
      /* 批量通过: 多选集合(单条通过复用同一链路) */
      passOrders: function (rows) {
        var vm = this;
        var list = (rows || []).filter(function (r) { return r && r.id != null; });
        if (!list.length) {
          ElementPlus.ElMessage.warning('请先勾选要审核的医嘱');
          return;
        }
        if (vm.auditing) { return; }
        /* 19位雪花ID以字符串承载: 不得 Number 化, 后端 InpPharmController.parseId 兼容字符串 */
        var ids = list.map(function (r) { return HIS.id(r.id); });
        HIS.SignaturePad.open({ actionType: 'pharm_audit', refType: 'inp_order', refId: ids[0] })
          .then(function () {
            vm.auditing = true;
            return HIS.post('/api/his/inp/pharm/audit', { orderIds: ids });
          })
          .then(function (n) {
            HIS.notifySuccess('药师审核通过 ' + (Number(n) || 0) + ' 条医嘱');
            vm.refreshAll();
          })
          .catch(function (e) {
            /* 用户取消签名静默返回, 其余报错提示 */
            if (e !== 'cancelled') { HIS.notifyError(e); }
          })
          .finally(function () { vm.auditing = false; });
      },
      batchPass: function () { this.passOrders(this.selection); },

      /* 驳回: 单条, 原因必填(落 pharm_reject_reason, 医生站悬停可见) */
      doReject: function (row) {
        var vm = this;
        if (!row || row.id == null) {
          ElementPlus.ElMessage.warning('请先点击选择要驳回的医嘱行');
          return;
        }
        var name = vm.drugNameOf(row);
        ElementPlus.ElMessageBox.prompt('驳回后医生站将展示原因, 医生可修改后重新开立。', '驳回医嘱「' + name + '」', {
          confirmButtonText: '确认驳回',
          cancelButtonText: '取消',
          type: 'warning',
          inputType: 'textarea',
          inputPlaceholder: '请输入驳回原因, 如: 剂量与患者肾功能不符 / 与在用药品存在配伍禁忌',
          inputValidator: function (v) {
            return (v && String(v).trim()) ? true : '驳回原因不能为空';
          }
        }).then(function (r) {
          return HIS.post('/api/his/inp/pharm/reject', {
            orderId: HIS.id(row.id),
            reason: String(r.value || '').trim()
          });
        }).then(function () {
          HIS.notifySuccess('医嘱已驳回, 原因已留痕');
          vm.refreshAll();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      }
    },
    mounted: function () {
      var vm = this;
      vm.loadDepts();
      vm.loadMedDict();
      vm.loadStats();
      vm.loadQueue();
      /* 统计 30s 轮询: 组件销毁时清除 */
      vm._statsTimer = setInterval(function () { vm.loadStats(); }, STATS_REFRESH_MS);
    },
    beforeUnmount: function () {
      if (this._statsTimer) {
        clearInterval(this._statsTimer);
        this._statsTimer = null;
      }
    },
    template: `
      <div class="pws-page">
        <!-- 顶部统计条 -->
        <div class="pws-stats">
          <div class="pws-stat is-brand" v-loading="statsLoading">
            <span class="v">{{ stats.pending }}</span>
            <span class="l">待审医嘱</span>
            <span class="pws-stat-sub">药品类医嘱等待审方</span>
          </div>
          <div class="pws-stat is-success">
            <span class="v">{{ stats.auditedToday }}</span>
            <span class="l">今日已审</span>
            <span class="pws-stat-sub">审核通过(含签名留痕)</span>
          </div>
          <div class="pws-stat is-danger">
            <span class="v">{{ stats.rejectedToday }}</span>
            <span class="l">今日驳回</span>
            <span class="pws-stat-sub">已留驳回原因待医生处理</span>
          </div>
          <div class="pws-stats-meta">
            <span>每 30 秒自动刷新</span>
            <span v-if="lastStatsTime">最近刷新 {{ lastStatsTime }}</span>
          </div>
        </div>

        <div class="pws-main">
          <!-- 左: 待审队列 -->
          <div class="pws-left">
            <div class="pws-filter">
              <el-select v-model="deptId" size="small" clearable filterable placeholder="全部科室(就诊科室)"
                         style="width:190px" @change="onSearch">
                <el-option v-for="d in deptOptions" :key="d.id" :label="d.deptName" :value="d.id"></el-option>
              </el-select>
              <el-input v-model="keyword" size="small" clearable placeholder="药品名称 / 医嘱内容"
                        style="width:230px" @keyup.enter="onSearch" @clear="onSearch"></el-input>
              <el-button size="small" type="primary" @click="onSearch">搜索</el-button>
              <el-button size="small" @click="onReset">重置</el-button>
              <span class="spacer"></span>
              <span class="pws-count">共 <b>{{ total }}</b> 条待审</span>
            </div>

            <div class="pws-table-wrap" v-loading="loading">
              <el-table :data="rows" height="100%" size="small" border
                        highlight-current-row :row-key="row => row.id"
                        @selection-change="onSelectionChange" @current-change="onCurrentChange">
                <el-table-column type="selection" width="42" align="center"></el-table-column>
                <el-table-column label="床号" width="62" align="center">
                  <template #default="s">{{ s.row.bedNo || '-' }}</template>
                </el-table-column>
                <el-table-column label="患者" width="118">
                  <template #default="s">
                    <span>{{ s.row.patientName || '-' }}</span>
                    <span class="pws-sub">{{ genderText(s.row.gender) }}/{{ s.row.age != null ? s.row.age : '-' }}岁</span>
                  </template>
                </el-table-column>
                <el-table-column prop="inpNo" label="住院号" width="104" show-overflow-tooltip>
                  <template #default="s">{{ s.row.inpNo || '-' }}</template>
                </el-table-column>
                <el-table-column label="药品名称" min-width="170" show-overflow-tooltip>
                  <template #default="s">
                    <span :title="s.row.orderContent">{{ drugNameOf(s.row) }}</span>
                    <span class="pws-high-alert" v-if="isHighAlertRow(s.row)"
                          title="高警示药品: 调配/执行需双人核对">⚠</span>
                  </template>
                </el-table-column>
                <el-table-column label="规格" width="110" show-overflow-tooltip>
                  <template #default="s">{{ specOf(s.row) }}</template>
                </el-table-column>
                <el-table-column label="剂量" width="76">
                  <template #default="s">{{ dosageOf(s.row) }}</template>
                </el-table-column>
                <el-table-column label="用法" width="70" show-overflow-tooltip>
                  <template #default="s">{{ s.row.usageCode ? usageText(s.row.usageCode) : '-' }}</template>
                </el-table-column>
                <el-table-column label="频次" width="70" show-overflow-tooltip>
                  <template #default="s">{{ s.row.freqCode ? freqText(s.row.freqCode) : '-' }}</template>
                </el-table-column>
                <el-table-column label="开立医生" width="82" show-overflow-tooltip>
                  <template #default="s">{{ s.row.doctorName || '-' }}</template>
                </el-table-column>
                <el-table-column label="开立时间" width="98">
                  <template #default="s"><span :title="s.row.createTime">{{ timeText(s.row.createTime) }}</span></template>
                </el-table-column>
                <el-table-column label="操作" width="106" fixed="right" align="center">
                  <template #default="s">
                    <el-button link type="primary" size="small" :disabled="auditing" @click.stop="passOrders([s.row])">通过</el-button>
                    <el-button link type="danger" size="small" @click.stop="doReject(s.row)">驳回</el-button>
                  </template>
                </el-table-column>
                <template #empty><div class="pws-dim" style="padding:24px 0">暂无待审药品医嘱</div></template>
              </el-table>
            </div>

            <div class="pws-pager" v-if="total > size">
              <el-pagination small background layout="total, prev, pager, next" :total="total" :page-size="size"
                             :current-page="page" @current-change="onPageChange"></el-pagination>
            </div>

            <!-- 底部批量工具栏 -->
            <div class="pws-actionbar">
              <span class="sel-tip">已选 <b>{{ selection.length }}</b> 条</span>
              <el-button type="primary" size="small" :disabled="!selection.length" :loading="auditing"
                         @click="batchPass">批量通过(电子签名)</el-button>
              <el-button type="danger" size="small" plain :disabled="!currentRow"
                         :title="currentRow ? ('驳回当前选中医嘱: ' + drugNameOf(currentRow)) : '请先点击选中一条医嘱'"
                         @click="doReject(currentRow)">驳回当前</el-button>
              <span class="spacer"></span>
              <el-button size="small" :loading="loading" @click="refreshAll">刷新</el-button>
            </div>
          </div>

          <!-- 右: 医嘱详情 -->
          <div class="pws-right">
            <template v-if="currentRow">
              <!-- 患者信息 -->
              <div class="pws-card">
                <div class="pws-card-title">患者信息</div>
                <div class="pws-kv">
                  <span class="k">姓名</span><span class="v">{{ currentRow.patientName || '-' }}（{{ genderText(currentRow.gender) }} / {{ currentRow.age != null ? currentRow.age : '-' }}岁）</span>
                  <span class="k">床号</span><span class="v">{{ currentRow.bedNo || '-' }}<span v-if="currentRow.roomNo">（{{ currentRow.roomNo }}房）</span></span>
                  <span class="k">住院号</span><span class="v">{{ currentRow.inpNo || '-' }}</span>
                  <span class="k">科室</span><span class="v">{{ currentRow.deptName || '-' }}</span>
                  <span class="k">入院诊断</span><span class="v">{{ currentRow.admitDiag || '-' }}</span>
                </div>
              </div>

              <!-- 医嘱信息 -->
              <div class="pws-card">
                <div class="pws-card-title">医嘱信息</div>
                <div class="pws-kv">
                  <span class="k">药品</span><span class="v">{{ drugNameOf(currentRow) }}</span>
                  <span class="k">规格</span><span class="v">{{ specOf(currentRow) }}</span>
                  <span class="k">剂量</span><span class="v">{{ dosageOf(currentRow) }}</span>
                  <span class="k">用法/频次</span><span class="v">{{ currentRow.usageCode ? usageText(currentRow.usageCode) : '-' }} / {{ currentRow.freqCode ? freqText(currentRow.freqCode) : '-' }}</span>
                  <span class="k">数量</span><span class="v">{{ currentRow.quantity != null ? currentRow.quantity : '-' }}</span>
                  <span class="k">开立医生</span><span class="v">{{ currentRow.doctorName || '-' }}</span>
                  <span class="k">开立时间</span><span class="v">{{ currentRow.createTime || timeText(currentRow.createTime) }}</span>
                </div>
                <div v-if="currentHighAlert" class="pws-warn-item danger" style="margin-top:8px">⚠ 高警示药品: 审方通过后调配/执行环节仍需双人核对</div>
              </div>

              <!-- 合理用药检查 -->
              <div class="pws-card">
                <div class="pws-card-title">合理用药检查</div>
                <div v-if="checkLoading" class="pws-dim">审查中...</div>
                <template v-else-if="checkResult">
                  <div v-if="checkResult.passed && !(checkResult.warnings && checkResult.warnings.length)"
                       class="pws-warn-item ok">审查通过: 未见过敏冲突与重复用药</div>
                  <div v-for="(w, i) in (checkResult.warnings || [])" :key="i" class="pws-warn-item danger">⚠ {{ w }}</div>
                  <div v-if="checkResult.highAlert || checkResult.doubleCheck" class="pws-warn-item">此药品需双人核对: 调配/执行环节请由两名药师/护士核对签字</div>
                </template>
                <div v-else-if="checkUnavailable" class="pws-dim">
                  合理用药审查接口暂不可用(未部署或已下线)。审方时可结合患者过敏史与用药史人工判断。
                </div>
                <div v-else class="pws-dim">暂无审查结果</div>
              </div>

              <!-- 驳回历史(该就诊) -->
              <div class="pws-card">
                <div class="pws-card-title">该就诊驳回历史
                  <span class="pws-sub" style="font-weight:400" v-if="rejectHistory.length">{{ rejectHistory.length }} 条</span>
                </div>
                <div v-if="historyLoading" class="pws-dim">加载中...</div>
                <template v-else-if="rejectHistory.length">
                  <div v-for="h in rejectHistory" :key="h.id" class="pws-rj-item">
                    <div class="nm">{{ h.drugName || h.orderContent || '-' }}</div>
                    <div class="rs">驳回原因: {{ h.rejectReason || '-' }}</div>
                    <div class="tm">{{ h.pharmAuditTime || '' }}<span v-if="h.pharmacistName"> · 审方药师: {{ h.pharmacistName }}</span></div>
                  </div>
                </template>
                <div v-else class="pws-dim">该就诊暂无被驳回的药品医嘱</div>
              </div>
            </template>
            <div v-else class="pws-empty">
              点击左侧医嘱行查看患者信息、合理用药检查与驳回历史
            </div>
          </div>
        </div>
      </div>
    `
  };
})();
