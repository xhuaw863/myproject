/* 病历模板发布审批工作台(高级版): 待审模板列表 + 两版对比预览 + 通过/驳回(意见) + 审批流水留痕。
 * 后端契约(/api/his/emr/template, EmrTemplateController/EmrTemplateApprovalService):
 *   GET  /pendingReviews           待审模板列表(publish_status=1, 审批工作台数据源, 仅管理员)
 *   GET  /{id}                     模板详情(含 document 当前待审载荷)
 *   GET  /{id}/versions            模板版本快照列表(取对比两版: 当前待审态 vs 上一已发布态)
 *   GET  /version/{vid}            单版本详情(含 document 四载荷)
 *   POST /{id}/approve?opinion=    审核通过(待审 → 已发布并启用)
 *   POST /{id}/reject?opinion=     审核驳回(待审 → 已驳回, 意见必填)
 *   GET  /{id}/approvalHistory     某模板审批流水(提交/审核留痕)
 * 权限口径: 审批仅 ADMIN/SUPER_ADMIN(全院模板由牵头机构管理员放行); 非管理员进入给出只读提示。
 * 注册: HIS.views.EmrTemplateApproval(菜单 comp 同值; 须在 app.js 之前加载, index.html 引入)。
 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var PUB_LABEL = { 0: '草稿', 1: '待审', 2: '已驳回', 3: '已发布' };
  var PUB_TAG = { 0: 'info', 1: 'warning', 2: 'danger', 3: 'success' };
  var SCOPE_LABEL = { 0: '全院', 1: '科室', 2: '个人' };
  var ACTION_LABEL = { pass: '通过', reject: '驳回', submit: '提交', retract: '撤回' };

  function text(v) { return v == null ? '' : String(v); }
  function safeParse(s) { try { var v = typeof s === 'string' ? JSON.parse(s) : s; return v || null; } catch (e) { return null; } }

  /* ===== 样式注入 ===== */
  (function () {
    if (document.getElementById('emr-tpl-approval-style')) { return; }
    var st = document.createElement('style');
    st.id = 'emr-tpl-approval-style';
    st.textContent = [
      '.eta-shell { display:flex; flex-direction:column; height:100%; padding:12px 14px; box-sizing:border-box; overflow:hidden; }',
      '.eta-hd { flex:none; display:flex; align-items:center; gap:10px; margin-bottom:10px; }',
      '.eta-hd .page-title { margin:0; }',
      '.eta-body { flex:1; min-height:0; display:flex; gap:12px; }',
      '.eta-left { flex:none; width:320px; min-width:320px; display:flex; flex-direction:column; border:1px solid var(--yb-border,#dfe4eb); border-radius:4px; overflow:hidden; }',
      '.eta-list { flex:1; overflow:auto; }',
      '.eta-item { padding:10px 12px; border-bottom:1px solid var(--yb-divider,#f0f3f7); cursor:pointer; }',
      '.eta-item:hover { background:var(--yb-surface-2,#f7f9fc); }',
      '.eta-item--active { background:var(--yb-brand-soft,#eaf2fb); border-left:3px solid var(--yb-brand,#1a5c9e); }',
      '.eta-item-name { font-size:13px; font-weight:600; color:var(--yb-ink-1,#1c2430); display:flex; align-items:center; gap:6px; }',
      '.eta-item-meta { font-size:12px; color:var(--yb-ink-4,#8994a5); margin-top:4px; display:flex; gap:8px; flex-wrap:wrap; }',
      '.eta-right { flex:1; min-width:0; display:flex; flex-direction:column; border:1px solid var(--yb-border,#dfe4eb); border-radius:4px; padding:10px 12px; overflow:auto; background:#fff; }',
      '.eta-acts { flex:none; display:flex; align-items:center; gap:8px; margin-bottom:10px; flex-wrap:wrap; }',
      '.eta-diff { flex:none; min-height:0; margin-bottom:10px; }',
      '.eta-empty { color:var(--yb-ink-4,#8994a5); font-size:13px; padding:24px; text-align:center; }',
      '.eta-hist { margin-top:8px; }',
      '.eta-hist-row { display:flex; gap:10px; font-size:12px; padding:6px 0; border-bottom:1px dashed var(--yb-divider,#f0f3f7); }',
      '.eta-hist-act { font-weight:600; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  HIS.views.EmrTemplateApproval = {
    name: 'EmrTemplateApproval',
    data: function () {
      return {
        isAdmin: false,
        loading: false,
        pending: [],
        currentId: '',
        current: null,
        detailLoading: false,
        verA: null, verB: null,       /* 两版快照: verB=较新(待审态), verA=较早(参照) */
        diffOk: false,
        busy: false,
        histLoading: false,
        history: []
      };
    },
    computed: {
      curName: function () { return this.current ? text(this.current.templateName) : ''; }
    },
    created: function () {
      this.isAdmin = !!(HIS.hasRole && (HIS.hasRole('ADMIN') || HIS.hasRole('SUPER_ADMIN')));
      this.fetchPending();
    },
    beforeUnmount: function () { this.unmountDiff(); },
    methods: {
      pubLabel: function (v) { var n = Number(v); return PUB_LABEL[n] || (v == null ? '已发布' : v); },
      pubTag: function (v) { var n = Number(v); return PUB_TAG[n] || 'success'; },
      scopeLabel: function (v) { return SCOPE_LABEL[Number(v)] || '-'; },
      fmtTime: function (t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-'; },

      fetchPending: function () {
        var vm = this;
        if (!vm.isAdmin) { return; }
        vm.loading = true;
        HIS.get('/api/his/emr/template/pendingReviews').then(function (d) {
          vm.pending = Array.isArray(d) ? d : [];
        }).catch(function (e) { vm.pending = []; HIS.notifyError(e); })
          .finally(function () { vm.loading = false; });
      },
      pick: function (t) {
        if (!t) { return; }
        var id = HIS.id(t.id);
        if (id === this.currentId) { return; }
        this.unmountDiff();
        this.currentId = id;
        this.loadDetail(t);
      },
      loadDetail: function (t) {
        var vm = this;
        vm.detailLoading = true;
        vm.diffOk = false; vm.verA = null; vm.verB = null; vm.history = [];
        HIS.get('/api/his/emr/template/' + HIS.idParam(t.id)).then(function (d) {
          vm.current = d || t;
          return HIS.get('/api/his/emr/template/' + HIS.idParam(t.id) + '/versions');
        }).then(function (vs) {
          var list = Array.isArray(vs) ? vs : [];
          /* 待审态取最新一版, 参照版取次新; 不足两版时用当前 document 兜底为新版 */
          if (list.length >= 2) {
            vm.verB = list[0]; vm.verA = list[1];
          } else if (list.length === 1) {
            vm.verB = list[0]; vm.verA = null;
          } else {
            vm.verB = null; vm.verA = null;
          }
          vm.diffOk = true;
          vm.$nextTick(function () { vm.mountDiff(); });
        }).catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.detailLoading = false; });
        vm.fetchHistory(t.id);
      },
      /* 取快照文档: 有版本快照用快照, 否则回落当前模板 document */
      diffDocOf: function (v) {
        if (v && v.document) { return safeParse(v.document); }
        if (!v && this.current) { return safeParse(this.current.document); }
        return null;
      },
      mountDiff: function () {
        this.unmountDiff();
        var host = this.$refs.diffHost;
        if (!host || !this.diffOk || !HIS.EmrEditor || !HIS.EmrEditor.EmrDiffViewer) { return; }
        var newDoc = this.verB ? safeParse(this.verB.document) : safeParse((this.current || {}).document);
        var oldDoc = this.verA ? safeParse(this.verA.document) : null;
        var newMeta = this.verB ? ('v' + this.verB.versionNo + ' · ' + text(this.verB.operatorName) + ' · ' + text(this.verB.changeSummary)) : '当前待审态';
        var oldMeta = this.verA ? ('v' + this.verA.versionNo + ' · ' + text(this.verA.operatorName) + ' · ' + text(this.verA.changeSummary)) : '无历史参照(首次提交)';
        host.innerHTML = '';
        this._diff = HIS.EmrEditor.EmrDiffViewer.mount(host, {
          oldDoc: oldDoc, newDoc: newDoc,
          oldLabel: '参照版本', newLabel: '待审版本',
          oldMeta: oldMeta, newMeta: newMeta, canRollback: false, onRollback: null
        });
      },
      unmountDiff: function () {
        var m = this._diff; this._diff = null;
        if (m) { try { m.app.unmount(); } catch (e) { /* noop */ } }
      },

      approve: function () {
        var vm = this, t = vm.current;
        if (!t) { return; }
        ElementPlus.ElMessageBox.prompt('审核意见(可留空)', '通过审核', {
          confirmButtonText: '确认通过', cancelButtonText: '取消', inputPlaceholder: '如: 版式与内容符合全院规范, 准予发布'
        }).then(function (r) {
          vm.busy = true;
          var op = encodeURIComponent(text(r && r.value));
          return HIS.post('/api/his/emr/template/' + HIS.idParam(t.id) + '/approve?opinion=' + op);
        }).then(function () {
          ElementPlus.ElMessage.success('已通过, 模板转入已发布态');
          vm.currentId = ''; vm.current = null; vm.unmountDiff();
          vm.fetchPending();
        }).catch(function (e) { if (e && e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } })
          .finally(function () { vm.busy = false; });
      },
      reject: function () {
        var vm = this, t = vm.current;
        if (!t) { return; }
        ElementPlus.ElMessageBox.prompt('驳回意见(必填)', '驳回审核', {
          confirmButtonText: '确认驳回', cancelButtonText: '取消',
          inputValidator: function (v) { return text(v).trim() ? true : '驳回必须填写意见'; }
        }).then(function (r) {
          vm.busy = true;
          var op = encodeURIComponent(text(r && r.value).trim());
          return HIS.post('/api/his/emr/template/' + HIS.idParam(t.id) + '/reject?opinion=' + op);
        }).then(function () {
          ElementPlus.ElMessage.success('已驳回, 模板转入已驳回态');
          vm.currentId = ''; vm.current = null; vm.unmountDiff();
          vm.fetchPending();
        }).catch(function (e) { if (e && e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } })
          .finally(function () { vm.busy = false; });
      },

      fetchHistory: function (templateId) {
        var vm = this;
        vm.histLoading = true;
        HIS.get('/api/his/emr/template/' + HIS.idParam(templateId) + '/approvalHistory').then(function (d) {
          vm.history = Array.isArray(d) ? d : [];
        }).catch(function () { vm.history = []; }).finally(function () { vm.histLoading = false; });
      },
      /* 模板内联展示工具 */
      HISID: function (id) { return HIS.id(id); },
      ACTION: function (a) { return ACTION_LABEL[a] || a || '-'; }
    },
    template: [
      '<div class="eta-shell">',
      '  <div class="eta-hd">',
      '    <span class="page-title">模板审批工作台</span>',
      '    <el-tag size="small" :type="isAdmin?\'primary\':\'info\'" effect="plain">{{ isAdmin ? \'审核人\' : \'仅管理员可审核\' }}</el-tag>',
      '    <el-button size="small" @click="fetchPending" :disabled="!isAdmin">刷新待审</el-button>',
      '  </div>',
      '  <el-alert v-if="!isAdmin" class="eta-legacy-alert" type="info" :closable="false" show-icon title="当前账号无审核权限: 发布审批由 ADMIN/SUPER_ADMIN 处理, 您可在病历模板设计器中查看本人/本科室模板的审批流水。"/>',
      '  <div class="eta-body">',
      /* ===== 左栏: 待审列表 ===== */
      '    <div class="eta-left">',
      '      <div class="eta-list" v-loading="loading">',
      '        <div v-if="!pending.length && !loading" class="eta-empty">暂无待审模板</div>',
      '        <div v-for="t in pending" :key="t.id" class="eta-item" :class="{\'eta-item--active\': currentId===HISID(t.id)}" @click="pick(t)">',
      '          <div class="eta-item-name">{{ t.templateName }} <el-tag size="small" :type="pubTag(t.publishStatus)" effect="light">{{ pubLabel(t.publishStatus) }}</el-tag></div>',
      '          <div class="eta-item-meta"><span>{{ scopeLabel(t.scopeLevel) }}</span><span>v{{ t.version }}</span><span>{{ t.templateCode }}</span></div>',
      '        </div>',
      '      </div>',
      '    </div>',
      /* ===== 右栏: diff + 操作 + 流水 ===== */
      '    <div class="eta-right" v-loading="detailLoading">',
      '      <div v-if="!current" class="eta-empty">从左侧选择一条待审模板查看变更对比</div>',
      '      <template v-else>',
      '        <div class="eta-acts">',
      '          <b style="font-size:14px;">{{ curName }}</b>',
      '          <el-tag size="small" effect="plain">{{ scopeLabel(current.scopeLevel) }} · v{{ current.version }}</el-tag>',
      '          <span style="flex:1;"></span>',
      '          <template v-if="isAdmin">',
      '            <el-button type="success" size="small" :disabled="busy" @click="approve">通过发布</el-button>',
      '            <el-button type="danger" size="small" :disabled="busy" @click="reject">驳回</el-button>',
      '          </template>',
      '        </div>',
      '        <div class="eta-diff" v-if="diffOk"><div ref="diffHost"></div></div>',
      '        <div class="eta-hist">',
      '          <div style="font-size:13px;font-weight:600;margin-bottom:6px;">审批流水</div>',
      '          <div v-if="!history.length && !histLoading" style="font-size:12px;color:var(--yb-ink-4);">暂无流水记录</div>',
      '          <div v-for="h in history" :key="h.id" class="eta-hist-row">',
      '            <span class="eta-hist-act">{{ ACTION(h.reviewAction) }}</span>',
      '            <span>{{ h.reviewUserName || h.submitUserName || \'-\' }}</span>',
      '            <span>{{ fmtTime(h.reviewTime || h.submitTime) }}</span>',
      '            <span style="flex:1;color:var(--yb-ink-2);">{{ h.reviewOpinion || \'\' }}</span>',
      '          </div>',
      '        </div>',
      '      </template>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
