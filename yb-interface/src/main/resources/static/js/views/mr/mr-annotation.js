/* 病案批注与反馈(病案统计科侧 P1-C): 围绕某份病案的沟通线程(批注/反馈/询问/答复 + 回复串联)。
 * 后端 /api/his/mr/annotation: list/{visitId}(GET) / my-todo(GET) / (POST创建·回复) / {id}/resolve(PUT) / {id}(DELETE)。
 * 铁律: 不回写临床首页, 仅编目侧记录; 写权限本机构管理员(后端 requireSelfOrgWrite 兜底 403), 前端按角色隐藏写按钮。
 * 雪花 ID 全链路以字符串承载(HIS.id / HIS.idKey / HIS.sameId)。运行时编译模板仅可访问组件作用域,
 * 故文件级 HIS 助手须以 methods 暴露给模板(idKey)。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  HIS.views.MrAnnotation = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        tab: 'todo',
        /* 我的待处理 */
        todos: [],
        todoLoading: false,
        /* 病案批注: 选取病案(快速检索) + 线程 */
        pickKw: '',
        pickRows: [],
        pickLoading: false,
        picked: null,
        thread: [],
        threadLoading: false,
        /* 撰写/回复 */
        compose: { visible: false, saving: false, parentId: null, annType: 'feedback', content: '', toStaffName: '' },
        types: [
          { value: 'feedback', label: '反馈' },
          { value: 'ask', label: '询问' },
          { value: 'reply', label: '答复' },
          { value: 'other', label: '其他' }
        ]
      };
    },
    computed: {
      canWrite: function () {
        return HIS.hasRole('ADMIN') || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      },
      /* 按 parentId 组装顶层线程(顶层时间升序, 回复挂在其下) */
      tree: function () {
        var tops = [], map = {};
        (this.thread || []).forEach(function (a) { map[a.id] = { node: a, children: [] }; });
        (this.thread || []).forEach(function (a) {
          if (a.parentId != null && map[a.parentId]) {
            map[a.parentId].children.push(map[a.id]);
          } else {
            tops.push(map[a.id]);
          }
        });
        return tops;
      }
    },
    created: function () {
      this.loadTodo();
    },
    methods: {
      idKey: function (v) { return HIS.idKey(v); },
      fmt: function (v) { return (v || '').toString().replace('T', ' ').slice(0, 16); },
      typeLabel: function (t) {
        for (var i = 0; i < this.types.length; i++) { if (this.types[i].value === t) { return this.types[i].label; } }
        return t || '批注';
      },
      /* ================= 我的待处理 ================= */
      loadTodo: function () {
        var vm = this;
        vm.todoLoading = true;
        HIS.get('/api/his/mr/annotation/my-todo').then(function (d) {
          vm.todos = d || [];
        }).catch(function () { vm.todos = []; }).finally(function () { vm.todoLoading = false; });
      },
      gotoVisit: function (visitId) {
        this.tab = 'record';
        this.viewThread(visitId);
      },
      /* ================= 病案选取(快速检索) ================= */
      doPick: function () {
        var vm = this;
        vm.pickLoading = true;
        var q = '/api/his/mr/search/quick?page=1&size=20';
        if (vm.pickKw) { q += '&keyword=' + encodeURIComponent(vm.pickKw); }
        HIS.get(q).then(function (d) {
          vm.pickRows = (d && d.records) || [];
        }).catch(function () { vm.pickRows = []; }).finally(function () { vm.pickLoading = false; });
      },
      choose: function (row) {
        this.picked = row;
        this.viewThread(row.visitId);
      },
      viewThread: function (visitId) {
        var vm = this;
        if (visitId == null) { return; }
        vm.threadLoading = true;
        HIS.get('/api/his/mr/annotation/list/' + HIS.idParam(visitId)).then(function (d) {
          vm.thread = d || [];
        }).catch(function () { vm.thread = []; }).finally(function () { vm.threadLoading = false; });
      },
      /* ================= 撰写/回复 ================= */
      openNew: function () {
        if (!this.picked) { ElementPlus.ElMessage.warning('请先选择一份病案'); return; }
        this.compose = { visible: true, saving: false, parentId: null, annType: 'feedback', content: '', toStaffName: '' };
      },
      openReply: function (node) {
        this.compose = { visible: true, saving: false, parentId: node.id, annType: 'reply', content: '', toStaffName: node.fromStaffName || '' };
      },
      doSave: function () {
        var vm = this;
        if (!vm.picked) { return; }
        if (!vm.compose.content) { ElementPlus.ElMessage.warning('请填写批注内容'); return; }
        var body = {
          visitId: HIS.id(vm.picked.visitId),
          annType: vm.compose.annType,
          content: vm.compose.content
        };
        if (vm.compose.parentId != null) { body.parentId = HIS.id(vm.compose.parentId); }
        if (!vm.compose.parentId && vm.compose.toStaffName) { body.toStaffName = vm.compose.toStaffName; }
        vm.compose.saving = true;
        HIS.post('/api/his/mr/annotation', body).then(function () {
          HIS.notifySuccess('批注已保存');
          vm.compose.visible = false;
          vm.viewThread(vm.picked.visitId);
        }).catch(HIS.notifyError).finally(function () { vm.compose.saving = false; });
      },
      doResolve: function (node, val) {
        var vm = this;
        HIS.put('/api/his/mr/annotation/' + HIS.id(node.id) + '/resolve', { resolved: val }).then(function () {
          HIS.notifySuccess(val === 1 ? '已标记处理' : '已取消处理');
          if (vm.picked) { vm.viewThread(vm.picked.visitId); }
          vm.loadTodo();
        }).catch(HIS.notifyError);
      },
      doDelete: function (node) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('删除该批注将连同其回复一并删除, 是否继续?', '提示', { type: 'warning' })
          .then(function () {
            return HIS.del('/api/his/mr/annotation/' + HIS.id(node.id));
          }).then(function () {
            HIS.notifySuccess('批注已删除');
            if (vm.picked) { vm.viewThread(vm.picked.visitId); }
            vm.loadTodo();
          }).catch(function (e) { if (e !== 'cancel' && e) { HIS.notifyError(e); } });
      },
      /* P3-C 推送至临床 */
      doPush: function (node) {
        var vm = this;
        if (!node.toStaffId) { ElementPlus.ElMessage.warning('该批注无接收人, 无法推送'); return; }
        ElementPlus.ElMessageBox.confirm('确认推送该批注至临床医生站通知中心?', '推送确认', { type: 'info' })
          .then(function () {
            return HIS.post('/api/his/mr/annotation/' + HIS.id(node.id) + '/push', {});
          }).then(function (d) {
            HIS.notifySuccess('已推送, 通知ID: ' + (d && d.notificationId || ''));
          }).catch(function (e) { if (e !== 'cancel' && e) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">病案批注与反馈 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">编目员与责任编码员围绕病案的沟通线程 · 不回写临床首页</span></div>',
      '  <el-tabs v-model="tab" style="flex:1;min-height:0;display:flex;flex-direction:column;">',
      /* ---- 我的待处理 ---- */
      '    <el-tab-pane label="我的待处理" name="todo">',
      '      <div class="toolbar" style="margin-bottom:10px;">',
      '        <el-button size="small" @click="loadTodo" :loading="todoLoading">刷新</el-button>',
      '        <span style="font-size:12px;color:var(--yb-ink-2);">接收人为本人的未处理批注</span>',
      '      </div>',
      '      <el-table :data="todos" border size="small" height="100%" v-loading="todoLoading" style="width:100%;flex:1;min-height:0;" empty-text="暂无待处理批注">',
      '        <el-table-column label="类型" width="80" align="center"><template #default="s"><el-tag size="small" effect="plain">{{ typeLabel(s.row.annType) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="fromStaffName" label="发起人" width="110"></el-table-column>',
      '        <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="时间" width="150"><template #default="s">{{ fmt(s.row.createTime) }}</template></el-table-column>',
      '        <el-table-column label="操作" width="140" align="center" fixed="right">',
      '          <template #default="s"><el-button type="primary" link size="small" @click="gotoVisit(s.row.visitId)">查看病案</el-button></template>',
      '        </el-table-column>',
      '      </el-table>',
      '    </el-tab-pane>',
      /* ---- 病案批注 ---- */
      '    <el-tab-pane label="病案批注" name="record">',
      '      <div style="display:flex;gap:12px;align-items:stretch;flex:1;min-height:0;">',
      '        <div style="width:320px;flex:none;display:flex;flex-direction:column;border:1px solid var(--yb-border);border-radius:4px;padding:10px;">',
      '          <div style="display:flex;gap:6px;margin-bottom:8px;flex:none;">',
      '            <el-input v-model="pickKw" size="small" placeholder="患者姓名/住院号" clearable style="flex:1;" @keyup.enter="doPick"></el-input>',
      '            <el-button size="small" type="primary" :loading="pickLoading" @click="doPick">检索</el-button>',
      '          </div>',
      '          <el-table :data="pickRows" border size="small" height="100%" style="width:100%;flex:1;min-height:0;" empty-text="输入关键字检索病案" @row-click="choose">',
      '            <el-table-column prop="patientName" label="患者" width="90"></el-table-column>',
      '            <el-table-column prop="inpNo" label="住院号" width="110"></el-table-column>',
      '            <el-table-column prop="mainDiagName" label="主要诊断" min-width="120" show-overflow-tooltip></el-table-column>',
      '          </el-table>',
      '        </div>',
      '        <div style="flex:1;min-width:0;display:flex;flex-direction:column;" v-loading="threadLoading">',
      '          <div class="toolbar" style="margin-bottom:10px;flex:none;">',
      '            <span v-if="picked" style="font-weight:600;">{{ picked.patientName }} · {{ picked.inpNo }}<span v-if="picked.mainDiagName" style="color:var(--yb-ink-2);font-weight:normal;">　{{ picked.mainDiagName }}</span></span>',
      '            <span v-else style="color:var(--yb-ink-2);">请在左侧选择一份病案</span>',
      '            <span style="flex:1"></span>',
      '            <el-button v-if="canWrite && picked" size="small" type="success" @click="openNew">新增批注</el-button>',
      '          </div>',
      '          <div style="flex:1;min-height:0;overflow:auto;">',
      '            <el-empty v-if="!picked" description="选择病案后查看其批注线程"></el-empty>',
      '            <div v-else-if="!tree.length" style="color:var(--yb-ink-2);padding:16px;">该病案暂无批注</div>',
      '            <div v-else>',
      '              <div v-for="t in tree" :key="idKey(t.node.id)" class="ann-top" style="border:1px solid var(--yb-border);border-radius:4px;padding:10px;margin-bottom:10px;">',
      '                <div style="display:flex;align-items:center;gap:8px;">',
      '                  <el-tag size="small" effect="plain">{{ typeLabel(t.node.annType) }}</el-tag>',
      '                  <span style="font-weight:600;">{{ t.node.fromStaffName }}</span>',
      '                  <span style="font-size:12px;color:var(--yb-ink-2);">{{ fmt(t.node.createTime) }}</span>',
      '                  <el-tag v-if="t.node.resolved===1" size="small" type="success">已处理</el-tag>',
      '                  <el-tag v-else size="small" type="warning">未处理</el-tag>',
      '                  <span style="flex:1"></span>',
      '                  <template v-if="canWrite">',
      '                    <el-button link size="small" @click="openReply(t.node)">回复</el-button>',
      '                    <el-button v-if="t.node.resolved!==1" link size="small" type="success" @click="doResolve(t.node,1)">标记处理</el-button>',
      '                    <el-button v-else link size="small" @click="doResolve(t.node,0)">取消处理</el-button>',
      '                    <el-button v-if="t.node.toStaffId && canWrite" link size="small" type="warning" @click="doPush(t.node)">推送临床</el-button>',
      '                    <el-button link size="small" type="danger" @click="doDelete(t.node)">删除</el-button>',
      '                  </template>',
      '                </div>',
      '                <div style="margin-top:6px;white-space:pre-wrap;">{{ t.node.content }}</div>',
      '                <div v-if="t.children.length" style="margin-top:8px;border-left:2px solid var(--yb-border);padding-left:10px;">',
      '                  <div v-for="c in t.children" :key="idKey(c.node.id)" style="padding:6px 0;border-bottom:1px dashed var(--yb-border);">',
      '                    <div style="display:flex;align-items:center;gap:8px;">',
      '                      <el-tag size="small" type="info" effect="plain">{{ typeLabel(c.node.annType) }}</el-tag>',
      '                      <span style="font-weight:600;">{{ c.node.fromStaffName }}</span>',
      '                      <span style="font-size:12px;color:var(--yb-ink-2);">{{ fmt(c.node.createTime) }}</span>',
      '                      <span style="flex:1"></span>',
      '                      <el-button v-if="canWrite" link size="small" type="danger" @click="doDelete(c.node)">删除</el-button>',
      '                    </div>',
      '                    <div style="margin-top:4px;white-space:pre-wrap;">{{ c.node.content }}</div>',
      '                  </div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      '</div>',
      /* ---- 撰写/回复弹窗 ---- */
      '<el-dialog v-model="compose.visible" :title="compose.parentId ? \'回复批注\' : \'新增批注\'" width="520px" destroy-on-close>',
      '  <el-form label-width="80px" size="default">',
      '    <el-form-item label="类型">',
      '      <el-select v-model="compose.annType" style="width:160px;">',
      '        <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value"></el-option>',
      '      </el-select>',
      '    </el-form-item>',
      '    <el-form-item v-if="!compose.parentId" label="接收人">',
      '      <el-input v-model="compose.toStaffName" placeholder="选填, 指定处理人姓名" style="width:220px;"></el-input>',
      '    </el-form-item>',
      '    <el-form-item v-if="compose.parentId" label="回复给"><span>{{ compose.toStaffName }}</span></el-form-item>',
      '    <el-form-item label="内容" required>',
      '      <el-input v-model="compose.content" type="textarea" :rows="4" placeholder="请填写批注/反馈内容"></el-input>',
      '    </el-form-item>',
      '  </el-form>',
      '  <template #footer>',
      '    <el-button @click="compose.visible=false">取消</el-button>',
      '    <el-button type="primary" :loading="compose.saving" @click="doSave">保存</el-button>',
      '  </template>',
      '</el-dialog>'
    ].join('\n')
  };
})();
