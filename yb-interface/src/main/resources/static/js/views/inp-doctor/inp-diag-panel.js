/* 住院医生站 - 诊断面板: 入院/补充/术后/出院 四分组诊断 CRUD
 * 接口: GET /api/his/inp/diagnosis/{visitId} (Map<"1".."4",List>) | POST /api/his/inp/diagnosis
 *       PUT /api/his/inp/diagnosis/{id} | DELETE /api/his/inp/diagnosis/{id}
 * 检索源: /api/community-dict/diag-dict/page?dictType=west&status=1 (ICD-10 诊断字典; 选中回填编码/名称, 编码名称仍可手改)
 * 注册: HIS.components.InpDiagPanel (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* ---------- ICD校验提示样式(一次性注入 head, 无构建组件不依赖外部样式文件) ---------- */
  (function ensureIcdStyles() {
    if (document.getElementById('inp-diag-icd-style')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-diag-icd-style';
    st.textContent = [
      /* 编码校验警告: 橙色文本(设计令牌 --yb-warning), 仅提醒不阻断保存 */
      '.iw-icd-warn { color:var(--yb-warning); font-size:12px; line-height:1.6; }',
      /* 相近标准编码建议: 行内链接按钮, 点击回填编码与名称 */
      '.iw-icd-sugs { display:flex; flex-wrap:wrap; align-items:center; gap:2px 10px; margin-top:2px; }',
      '.iw-icd-sugs .el-button { height:auto; padding:0; font-size:12px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  const DIAG_TYPE_DEFS = [
    { type: 1, label: '入院诊断' },
    { type: 2, label: '补充诊断' },
    { type: 3, label: '术后诊断' },
    { type: 4, label: '出院诊断' }
  ];

  function emptyForm(visitId) {
    return {
      id: null,
      inpVisitId: HIS.id(visitId) || null,
      diagType: 1,
      diagCode: '',
      diagName: '',
      isMain: 0,
      toothPosition: ''
    };
  }

  const InpDiagPanel = {
    name: 'InpDiagPanel',
    props: { visitId: { type: [String, Number], default: null } },
    components: { 'dw-tooth-chart': HIS.components.DwToothChart },
    data() {
      return {
        loading: false,
        groups: DIAG_TYPE_DEFS.map(g => ({ type: g.type, label: g.label, items: [] })),
        dialogVisible: false,
        saving: false,
        form: emptyForm(null),
        pickCode: null,
        options: [],
        searching: false,
        /* ICD校验(失焦触发, 不阻断保存): 警告文本 + 相近标准编码建议 */
        icdWarning: '',
        icdSuggestions: [],
        /* 口腔牙位图对话框(复用 HIS.components.DwToothChart) */
        toothVisible: false,
        toothTemp: ''
      };
    },
    computed: {
      totalCount() { return this.groups.reduce((sum, g) => sum + g.items.length, 0); },
      isEdit() { return !!this.form.id; }
    },
    watch: {
      visitId: { immediate: true, handler() { this.load(); } }
    },
    methods: {
      load() {
        const vm = this;
        vm.groups.forEach(g => { g.items = []; });
        if (!vm.visitId) { return; }
        const id = vm.visitId;
        vm.loading = true;
        HIS.get('/api/his/inp/diagnosis/' + encodeURIComponent(id))
          .then(function (data) {
            if (String(vm.visitId) !== String(id)) { return; }
            const map = data || {};
            vm.groups.forEach(function (g) {
              g.items = map[String(g.type)] || [];
            });
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      openCreate(type) {
        if (!this.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        this.form = emptyForm(this.visitId);
        this.form.diagType = Number(type) || 1;
        this.pickCode = null;
        this.options = [];
        this.clearIcdHint();
        this.dialogVisible = true;
      },
      openEdit(row, type) {
        this.form = {
          id: row.id,
          inpVisitId: row.inpVisitId || HIS.id(this.visitId) || null,
          diagType: Number(row.diagType || type) || 1,
          diagCode: row.diagCode || '',
          diagName: row.diagName || '',
          isMain: Number(row.isMain) === 1 ? 1 : 0,
          toothPosition: row.toothPosition || ''
        };
        this.pickCode = row.diagCode || null;
        this.options = [];
        this.clearIcdHint();
        this.dialogVisible = true;
      },
      remoteSearch(query) {
        const vm = this;
        const kw = String(query || '').trim();
        if (!kw) { vm.options = []; return; }
        vm.searching = true;
        HIS.get('/api/community-dict/diag-dict/page?dictType=west&status=1&page=1&size=30&keyword=' + encodeURIComponent(kw))
          .then(function (data) { vm.options = (data && data.records) || []; })
          .catch(function () { vm.options = []; })
          .finally(function () { vm.searching = false; });
      },
      onPick(code) {
        const d = this.options.find(x => String(x.code) === String(code));
        if (!d) { return; }
        this.form.diagCode = d.code || '';
        this.form.diagName = d.name || '';
        this.clearIcdHint();
      },
      /* 清除编码校验警告与建议(选中检索项/切换弹窗时) */
      clearIcdHint() {
        this.icdWarning = '';
        this.icdSuggestions = [];
      },
      /* 编码失焦校验(不阻断保存): 失败时橙色警告 + 相近标准编码建议 */
      checkIcd() {
        const vm = this;
        const code = String(vm.form.diagCode || '').trim();
        vm.clearIcdHint();
        if (!code) { return; }
        HIS.get('/api/his/inp/diagnosis/validate-icd?code=' + encodeURIComponent(code))
          .then(function (d) {
            d = d || {};
            if (!d.valid) {
              vm.icdWarning = d.warning || '编码校验未通过, 建议从检索结果选择';
              vm.icdSuggestions = d.suggestions || [];
            }
          })
          .catch(function () { /* 校验失败静默: 不打扰录入(后端保存本身不阻断) */ });
      },
      /* 点击建议: 回填编码与名称并清除警告(仍可人工修改) */
      applyIcdSuggestion(s) {
        if (!s) { return; }
        this.form.diagCode = s.code || '';
        if (s.name) { this.form.diagName = s.name; }
        this.pickCode = s.code || null;
        this.clearIcdHint();
      },
      /* 口腔牙位图: 打开对话框暂存, 确写回表单 */
      openTooth() {
        this.toothTemp = this.form.toothPosition || '';
        this.toothVisible = true;
      },
      applyTooth() {
        this.form.toothPosition = this.toothTemp || '';
        this.toothVisible = false;
      },
      save() {
        const vm = this;
        if (vm.saving) { return; }
        const name = String(vm.form.diagName || '').trim();
        if (!name) { ElementPlus.ElMessage.warning('请填写诊断名称'); return; }
        const edit = !!vm.form.id;
        const payload = {
          inpVisitId: HIS.id(vm.form.inpVisitId || vm.visitId) || null,
          diagType: Number(vm.form.diagType) || 1,
          diagCode: String(vm.form.diagCode || '').trim() || null,
          diagName: name,
          isMain: Number(vm.form.isMain) === 1 ? 1 : 0,
          toothPosition: String(vm.form.toothPosition || '').trim() || null
        };
        vm.saving = true;
        const req = edit
          ? HIS.put('/api/his/inp/diagnosis/' + encodeURIComponent(vm.form.id), payload)
          : HIS.post('/api/his/inp/diagnosis', payload);
        req.then(function () {
          HIS.notifySuccess(edit ? '诊断已更新' : '诊断已新增');
          vm.dialogVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      doRemove(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除诊断「' + row.diagName + '」？', '删除诊断', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/inp/diagnosis/' + encodeURIComponent(row.id));
        }).then(function () {
          HIS.notifySuccess('诊断已删除');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      }
    },
    template: `
      <div class="iw-panel-body" v-loading="loading">
        <div class="iw-toolbar">
          <span class="iw-dim">共 <b>{{ totalCount }}</b> 条诊断; 同类型下「主诊断」仅一条, 由服务端置位</span>
          <span class="iw-toolbar-right">
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </span>
        </div>

        <div class="iw-two-col iw-two-col--gap">
          <section class="iw-card iw-diag-sec" v-for="g in groups" :key="g.type">
            <div class="iw-sect-title">
              {{ g.label }}
              <span class="iw-count" v-if="g.items.length">{{ g.items.length }}</span>
              <span class="iw-toolbar-right">
                <el-button size="small" type="primary" plain @click="openCreate(g.type)">+ 新增</el-button>
              </span>
            </div>
            <div class="iw-diag-lines" v-if="g.items.length">
              <div class="iw-diag-line" v-for="(d, i) in g.items" :key="d.id || i">
                <span class="code" :title="d.diagCode">{{ d.diagCode || '—' }}</span>
                <span class="name" :title="d.diagName">{{ d.diagName }}</span>
                <span class="iw-tag iw-tag--danger" v-if="Number(d.isMain) === 1">主诊断</span>
                <span class="iw-tag iw-tag--plain" v-if="d.toothPosition" :title="'牙位 ' + d.toothPosition">牙 {{ d.toothPosition }}</span>
                <span class="iw-tag iw-tag--plain" v-if="d.ybCode" :title="'医保编码 ' + d.ybCode">医保</span>
                <span class="ops">
                  <el-button link type="primary" size="small" @click="openEdit(d, g.type)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="doRemove(d)">删除</el-button>
                </span>
              </div>
            </div>
            <div v-else class="iw-empty-line">暂无{{ g.label }}</div>
          </section>
        </div>

        <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑诊断' : '新增诊断'" width="600px" :close-on-click-modal="false">
          <div class="iw-form">
            <div class="iw-form-row">
              <span class="lb">诊断类型</span>
              <el-radio-group v-model="form.diagType" size="small">
                <el-radio-button v-for="g in groups" :key="g.type" :label="g.type">{{ g.label }}</el-radio-button>
              </el-radio-group>
            </div>
            <div class="iw-form-row">
              <span class="lb">诊断检索</span>
              <el-select class="iw-grow" v-model="pickCode" size="small" filterable remote reserve-keyword clearable
                         :remote-method="remoteSearch" :loading="searching"
                         placeholder="输入编码 / 名称 / 拼音简码检索 ICD-10, 选中自动回填编码与名称" @change="onPick">
                <el-option v-for="d in options" :key="d.id || d.code" :label="(d.code || '') + ' ' + (d.name || '')" :value="d.code">
                  <div class="iw-opt">
                    <span class="nm">{{ d.name }}</span>
                    <span class="sub">{{ d.code }}</span>
                    <span class="sub" v-if="d.category">{{ d.category }}</span>
                  </div>
                </el-option>
              </el-select>
            </div>
            <div class="iw-form-row">
              <span class="lb">诊断编码</span>
              <el-input v-model="form.diagCode" size="small" style="width:150px" placeholder="ICD-10 编码" @blur="checkIcd"></el-input>
              <span class="lb">诊断名称</span>
              <el-input v-model="form.diagName" class="iw-grow" size="small" placeholder="可检索回填, 也可直接输入自定义名称"></el-input>
            </div>
            <div class="iw-form-row" v-if="icdWarning">
              <span class="lb"></span>
              <div class="iw-grow">
                <div class="iw-icd-warn">{{ icdWarning }}</div>
                <div class="iw-icd-sugs" v-if="icdSuggestions.length">
                  <span class="iw-dim">相近标准编码:</span>
                  <el-button v-for="s in icdSuggestions" :key="s.code" link type="primary" size="small"
                             :title="'点击回填 ' + s.code + ' ' + (s.name || '')" @click="applyIcdSuggestion(s)">
                    {{ s.code }}{{ s.name ? ' ' + s.name : '' }}
                  </el-button>
                </div>
              </div>
            </div>
            <div class="iw-form-row">
              <span class="lb">主诊断</span>
              <el-switch v-model="form.isMain" :active-value="1" :inactive-value="0"></el-switch>
              <span class="iw-dim">标记为主诊断后, 同类型下其他诊断将被置为非主诊断</span>
            </div>
            <div class="iw-form-row">
              <span class="lb">牙位</span>
              <el-input v-model="form.toothPosition" class="iw-grow" size="small" placeholder="口腔诊断选填, 如 16,26 (FDI 编码)"></el-input>
              <el-button size="small" @click="openTooth">选择牙位</el-button>
            </div>
          </div>
          <template #footer>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="toothVisible" title="选择牙位(FDI 编码)" width="560px" append-to-body :close-on-click-modal="false">
          <dw-tooth-chart v-model="toothTemp"></dw-tooth-chart>
          <template #footer>
            <el-button @click="toothVisible = false">取消</el-button>
            <el-button type="primary" @click="applyTooth">确定</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpDiagPanel = InpDiagPanel;
})();
