/* ==========================================================================
 * 中医诊断-证候组合选择器(可复用录入组件): HIS.components.TcmDiagSelector
 * 形态: 已选组合 el-tag 区(可删) + 左栏中医诊断检索 + 右栏证候(推荐/搜索) + 「确认添加」。
 * 交互: 左栏选中诊断 → 右栏自动加载推荐证候(suggest-syndrome); 右栏可切换为关键字搜索
 *       (search-syndrome), 清空关键字恢复推荐; 选中证候后点「确认添加」拼装"诊断名 证候名证"组合。
 * 拼装契约(与后端 TcmDiagAssembleService.joinText 对齐): 诊断名 + 半角空格 + 证候名,
 *       证候名本身以「证」结尾时不重复追加(不存在双"证证")。
 * 数据契约: props.value / modelValue 为已选组合数组(元素 {diagCode, diagName,
 *           syndromeCode, syndromeName, assembledText}); 组件内部按值收敛, 不改原数组。
 * 双通道: 兼容 Vue3 标准 v-model(modelValue/update:modelValue) 与旧式 :value + @input
 *         (任务书契约为后者); 两个事件同时发出, 消费方任选其一即可。
 * 后端: GET /api/emr/tcm/search-diag?keyword=     搜中医诊断(dict_type=tcm, LIMIT 50, 空关键字取前50)
 *       GET /api/emr/tcm/suggest-syndrome?diagCode= 按诊断推荐证候(标准对照表→院内字典→兜底20)
 *       GET /api/emr/tcm/search-syndrome?keyword=   搜中医证候(dict_type=symp)
 * 防抖: 双栏输入均 300ms 防抖; beforeUnmount 清理定时器。
 * 注册: HIS.components.TcmDiagSelector(须在消费方 views 之前加载; 依赖 api.js)。
 * 无构建坑: 模板字符串数组必须 .join('\n'); 模板串内禁用 && 等裸逻辑运算符(用计算属性/函数规避);
 *           模板内类名单引号须转义 \'tcm-sel\'。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  var TCM_API = '/api/emr/tcm';
  var TCM_DEBOUNCE = 300;

  /* ===== 样式一次性注入(created+mounted 双调防丢, 沿用 cps 同款函数范式) ===== */
  function ensureStyle() {
    if (document.getElementById('tcm-diag-selector-css')) { return; }
    var st = document.createElement('style');
    st.id = 'tcm-diag-selector-css';
    st.textContent = [
      '.tcm-sel{border:1px solid var(--yb-border,#dfe4eb);border-radius:6px;padding:8px 10px;background:var(--yb-surface,#fff);}',
      '.tcm-sel-selected{display:flex;align-items:center;flex-wrap:wrap;gap:6px;min-height:26px;margin-bottom:8px;}',
      '.tcm-sel-label{font-size:12px;font-weight:600;color:var(--yb-ink-3,#5a6a7e);flex:none;}',
      '.tcm-sel-tag{max-width:100%;}',
      '.tcm-sel-empty{font-size:12px;color:var(--yb-ink-4,#8994a5);}',
      '.tcm-sel-cols{display:flex;gap:10px;}',
      '.tcm-sel-col{flex:1;min-width:0;}',
      '.tcm-sel-col-title{display:flex;align-items:baseline;gap:6px;font-size:13px;font-weight:600;color:var(--yb-ink-1,#1c2430);margin-bottom:6px;}',
      '.tcm-sel-tip{font-size:12px;font-weight:400;color:var(--yb-ink-4,#8994a5);}',
      '.tcm-sel-table{margin-top:6px;}',
      '.tcm-sel-table .el-table__row{cursor:pointer;}',
      '.tcm-sel-table .tcm-sel-row-on td.el-table__cell{background:var(--yb-brand-bg,#eaf3fb);}',
      '.tcm-sel-foot{display:flex;align-items:center;gap:10px;margin-top:8px;}',
      '.tcm-sel-preview{flex:1;min-width:0;font-size:13px;color:var(--yb-brand,#1a5c9e);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}'
    ].join('\n');
    document.head.appendChild(st);
  }
  ensureStyle();

  /* ===== 工具函数 ===== */
  /* 拼装"诊断名 证候名证"(证候名已带"证"结尾则不重复追加) —— 与后端 joinText 严格一致 */
  function joinAssembled(diagName, syndromeName) {
    var d = String(diagName == null ? '' : diagName).trim();
    var s = String(syndromeName == null ? '' : syndromeName).trim();
    if (!d) { return s; }
    if (!s) { return d; }
    if (s.charAt(s.length - 1) !== '证') { s += '证'; }
    return d + ' ' + s;
  }
  /* 组合唯一键: 诊断码 + 证候码(无码降级证候名) */
  function comboKey(c) {
    if (!c) { return ''; }
    return HIS.idKey(c.diagCode) + '|' + (HIS.idKey(c.syndromeCode) || String(c.syndromeName || '').trim());
  }
  /* 组合行归一(补全 assembledText; 不改原对象) */
  function normalizeCombo(raw) {
    var c = raw || {};
    var diagName = String(c.diagName == null ? '' : c.diagName).trim();
    var syndromeName = String(c.syndromeName == null ? '' : c.syndromeName).trim();
    var text = String(c.assembledText == null ? '' : c.assembledText).trim();
    return {
      diagCode: HIS.id(c.diagCode),
      diagName: diagName,
      syndromeCode: HIS.id(c.syndromeCode),
      syndromeName: syndromeName,
      assembledText: text || joinAssembled(diagName, syndromeName)
    };
  }
  /* 列表深拷贝(元素归一为全新对象, 供 emit 出去, 避免父子共享引用) */
  function cloneList(list) {
    return (list || []).map(function (c) { return normalizeCombo(c); });
  }
  /* 组合列表按值比较(键 + 展示文本) */
  function sameList(a, b) {
    var x = a || [], y = b || [];
    if (x.length !== y.length) { return false; }
    for (var i = 0; i < x.length; i++) {
      if (comboKey(x[i]) !== comboKey(y[i])) { return false; }
      if (String(x[i] && x[i].assembledText) !== String(y[i] && y[i].assembledText)) { return false; }
    }
    return true;
  }
  /* 统一轻提示(EP 缺失时降级 console) */
  function tcmMsg(type, text) {
    var M = window.ElementPlus;
    if (M && M.ElMessage && typeof M.ElMessage[type] === 'function') { M.ElMessage[type](text); return; }
    if (window.console && (type === 'error' || type === 'warning')) { console.warn('[TcmDiagSelector] ' + text); }
  }
  /* 响应列表归一: 兼容纯数组 / {records} / {list} / {rows} 信封 */
  function toRows(data) {
    if (Array.isArray(data)) { return data; }
    if (data && Array.isArray(data.records)) { return data.records; }
    if (data && Array.isArray(data.list)) { return data.list; }
    if (data && Array.isArray(data.rows)) { return data.rows; }
    return [];
  }
  /* 字典行归一: 后端契约 {code, name, pyCode, category} */
  function normRow(r) {
    r = r || {};
    return {
      code: String(r.code == null ? '' : r.code),
      name: String(r.name == null ? '' : r.name),
      pyCode: r.pyCode || '',
      category: r.category || ''
    };
  }

  /* ===== 组件 ===== */
  var TcmDiagSelector = {
    name: 'TcmDiagSelector',
    props: {
      /* Vue3 标准 v-model 通道 */
      modelValue: { type: Array, default: null },
      /* 兼容别名: 任务书契约 value + input(旧式绑定) */
      value: { type: Array, default: null },
      /* 就诊类型: 'outpatient' | 'admission'(预留上下文, 当前仅影响检索占位文案) */
      visitType: { type: String, default: '' }
    },
    emits: ['update:modelValue', 'input'],
    data: function () {
      return {
        combos: [],
        /* 左栏: 中医诊断 */
        diagKeyword: '',
        diagLoading: false,
        diagOptions: [],
        selDiag: null,
        /* 右栏: 证候(推荐/搜索双模式) */
        syndromeKeyword: '',
        syndromeLoading: false,
        syndromeMode: 'suggest',
        syndromeOptions: [],
        selSyndrome: null,
        suggestCache: [],
        _diagTimer: null,
        _synTimer: null
      };
    },
    computed: {
      /* 双通道读值: modelValue 优先, 缺省回落 value */
      innerValue: function () {
        var mv = this.modelValue !== null ? this.modelValue : this.value;
        return Array.isArray(mv) ? mv : [];
      },
      canConfirm: function () { return !!(this.selDiag && this.selSyndrome); },
      previewText: function () {
        if (!this.selDiag) { return ''; }
        var sn = this.selSyndrome ? this.selSyndrome.name : '';
        return sn ? joinAssembled(this.selDiag.name, sn) : this.selDiag.name;
      },
      previewLabel: function () { return '将添加: ' + (this.previewText || '—'); },
      diagPlaceholder: function () {
        if (this.visitType === 'admission') { return '检索住院中医诊断'; }
        if (this.visitType === 'outpatient') { return '检索门诊中医诊断'; }
        return '检索中医诊断';
      },
      syndromeDisabled: function () { return !this.selDiag; },
      syndromePlaceholder: function () {
        if (!this.selDiag) { return '请先选择左侧中医诊断'; }
        return this.syndromeMode === 'suggest' ? '可检索证候关键词, 清空恢复推荐' : '检索证候';
      },
      syndromeTip: function () {
        if (!this.selDiag) { return '（待选择诊断）'; }
        return this.syndromeMode === 'suggest' ? '（推荐）' : '（搜索结果）';
      }
    },
    watch: {
      innerValue: function (nv) {
        var incoming = cloneList(Array.isArray(nv) ? nv : []);
        if (!sameList(this.combos, incoming)) { this.combos = incoming; }
      }
    },
    created: function () { ensureStyle(); },
    mounted: function () {
      ensureStyle();
      this.combos = cloneList(this.innerValue);
      this.searchDiags('');
    },
    beforeUnmount: function () {
      if (this._diagTimer) { clearTimeout(this._diagTimer); this._diagTimer = null; }
      if (this._synTimer) { clearTimeout(this._synTimer); this._synTimer = null; }
    },
    methods: {
      /* ---------- 左栏: 中医诊断 ---------- */
      comboTagKey: function (c, i) { return comboKey(c) + '#' + i; },
      onDiagInput: function () {
        var vm = this;
        if (vm._diagTimer) { clearTimeout(vm._diagTimer); }
        vm._diagTimer = setTimeout(function () {
          vm._diagTimer = null;
          vm.searchDiags(vm.diagKeyword);
        }, TCM_DEBOUNCE);
      },
      onDiagClear: function () { this.searchDiags(''); },
      searchDiags: function (keyword) {
        var vm = this;
        var kw = String(keyword == null ? '' : keyword).trim();
        vm.diagLoading = true;
        return HIS.get(TCM_API + '/search-diag?keyword=' + encodeURIComponent(kw)).then(function (data) {
          vm.diagOptions = toRows(data).map(normRow);
        }).catch(function (e) {
          vm.diagOptions = [];
          tcmMsg('error', '中医诊断检索失败: ' + ((e && e.message) || e));
        }).then(function () { vm.diagLoading = false; });
      },
      /* 选中诊断 → 右栏自动加载推荐证候 */
      pickDiag: function (row) {
        if (!row || !row.code) { return; }
        this.selDiag = row;
        this.selSyndrome = null;
        this.syndromeKeyword = '';
        this.loadSuggest(row.code);
      },
      diagRowClass: function (obj) {
        return (this.selDiag && obj && obj.row && HIS.sameId(this.selDiag.code, obj.row.code)) ? 'tcm-sel-row-on' : '';
      },
      /* ---------- 右栏: 证候推荐/搜索 ---------- */
      loadSuggest: function (diagCode) {
        var vm = this;
        vm.syndromeMode = 'suggest';
        vm.syndromeLoading = true;
        return HIS.get(TCM_API + '/suggest-syndrome?diagCode=' + HIS.idParam(diagCode)).then(function (data) {
          vm.suggestCache = toRows(data).map(normRow);
          vm.syndromeOptions = vm.suggestCache;
        }).catch(function (e) {
          vm.suggestCache = [];
          vm.syndromeOptions = [];
          tcmMsg('error', '推荐证候加载失败: ' + ((e && e.message) || e));
        }).then(function () { vm.syndromeLoading = false; });
      },
      onSyndromeInput: function () {
        var vm = this;
        if (vm._synTimer) { clearTimeout(vm._synTimer); }
        vm._synTimer = setTimeout(function () {
          vm._synTimer = null;
          vm.searchSyndromes(vm.syndromeKeyword);
        }, TCM_DEBOUNCE);
      },
      onSyndromeClear: function () { this.searchSyndromes(''); },
      searchSyndromes: function (keyword) {
        var vm = this;
        var kw = String(keyword == null ? '' : keyword).trim();
        if (!kw) {
          /* 关键字清空: 恢复推荐缓存 */
          vm.syndromeMode = 'suggest';
          vm.syndromeOptions = vm.suggestCache;
          return Promise.resolve();
        }
        vm.syndromeMode = 'search';
        vm.syndromeLoading = true;
        return HIS.get(TCM_API + '/search-syndrome?keyword=' + encodeURIComponent(kw)).then(function (data) {
          vm.syndromeOptions = toRows(data).map(normRow);
        }).catch(function (e) {
          vm.syndromeOptions = [];
          tcmMsg('error', '证候检索失败: ' + ((e && e.message) || e));
        }).then(function () { vm.syndromeLoading = false; });
      },
      pickSyndrome: function (row) {
        if (!row || !row.code) { return; }
        this.selSyndrome = row;
      },
      syndromeRowClass: function (obj) {
        return (this.selSyndrome && obj && obj.row && HIS.sameId(this.selSyndrome.code, obj.row.code)) ? 'tcm-sel-row-on' : '';
      },
      /* ---------- 确认添加 / 删除 ---------- */
      findCombo: function () {
        var key = comboKey({
          diagCode: this.selDiag && this.selDiag.code,
          syndromeCode: this.selSyndrome && this.selSyndrome.code,
          syndromeName: this.selSyndrome && this.selSyndrome.name
        });
        var hit = null;
        this.combos.forEach(function (c) { if (!hit && comboKey(c) === key) { hit = c; } });
        return hit;
      },
      confirmAdd: function () {
        if (!this.selDiag) { tcmMsg('warning', '请先在左栏选择中医诊断'); return; }
        if (!this.selSyndrome) { tcmMsg('warning', '请在右栏选择证候'); return; }
        if (this.findCombo()) { tcmMsg('warning', '该「诊断-证候」组合已在已选列表中'); return; }
        var combo = normalizeCombo({
          diagCode: this.selDiag.code,
          diagName: this.selDiag.name,
          syndromeCode: this.selSyndrome.code,
          syndromeName: this.selSyndrome.name
        });
        this.combos.push(combo);
        this.emitUp();
        tcmMsg('success', '已添加: ' + combo.assembledText);
      },
      removeCombo: function (idx) {
        if (idx < 0 || idx >= this.combos.length) { return; }
        this.combos.splice(idx, 1);
        this.emitUp();
      },
      /* 双通道外发: v-model 与旧式 value/input 消费方均可收到 */
      emitUp: function () {
        var list = cloneList(this.combos);
        this.$emit('update:modelValue', list);
        this.$emit('input', list);
      }
    },
    template: [
      '<div class="tcm-sel">',
      '  <div class="tcm-sel-selected">',
      '    <span class="tcm-sel-label">已选组合</span>',
      '    <el-tag v-for="(c, i) in combos" :key="comboTagKey(c, i)" closable size="small" class="tcm-sel-tag" @close="removeCombo(i)">{{ c.assembledText }}</el-tag>',
      '    <span v-if="!combos.length" class="tcm-sel-empty">尚未选择: 左栏选中医诊断, 右栏选证候, 点「确认添加」生成组合</span>',
      '  </div>',
      '  <div class="tcm-sel-cols">',
      '    <div class="tcm-sel-col">',
      '      <div class="tcm-sel-col-title">中医诊断<span class="tcm-sel-tip">名称 / 拼音 / 编码检索</span></div>',
      '      <el-input v-model="diagKeyword" size="small" clearable :placeholder="diagPlaceholder" @input="onDiagInput" @clear="onDiagClear"></el-input>',
      '      <el-table class="tcm-sel-table" :data="diagOptions" size="small" height="238" v-loading="diagLoading" highlight-current-row :row-class-name="diagRowClass" @row-click="pickDiag">',
      '        <el-table-column prop="name" label="诊断名称" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="code" label="编码" width="108"></el-table-column>',
      '      </el-table>',
      '    </div>',
      '    <div class="tcm-sel-col">',
      '      <div class="tcm-sel-col-title">证候<span class="tcm-sel-tip">{{ syndromeTip }}</span></div>',
      '      <el-input v-model="syndromeKeyword" size="small" clearable :disabled="syndromeDisabled" :placeholder="syndromePlaceholder" @input="onSyndromeInput" @clear="onSyndromeClear"></el-input>',
      '      <el-table class="tcm-sel-table" :data="syndromeOptions" size="small" height="238" v-loading="syndromeLoading" highlight-current-row :row-class-name="syndromeRowClass" @row-click="pickSyndrome">',
      '        <el-table-column prop="name" label="证候名称" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="code" label="编码" width="108"></el-table-column>',
      '      </el-table>',
      '    </div>',
      '  </div>',
      '  <div class="tcm-sel-foot">',
      '    <span class="tcm-sel-preview">{{ previewLabel }}</span>',
      '    <el-button type="primary" size="small" :disabled="!canConfirm" @click="confirmAdd">确认添加</el-button>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  HIS.components.TcmDiagSelector = TcmDiagSelector;
})();
