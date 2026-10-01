/* 结构化病历字段动态渲染器(可复用): HIS.components.EmrField —— 依据模板 fields JSON 的单个字段定义渲染对应控件。
 * 由住院病历面板(inp-record-panel.js)、病历模板设计器(emr-designer.js)、门诊病历面板共用, 确保"设计即所见"。
 * 支持控件: text/textarea/number/date/datetime/select/multiselect/radio/checkbox/diagnosis/catalog/vitals/table/signature/section。
 * 值域绑定(dictRef): 诊断 community-dict/diag-dict、收费/药品 org-catalog/available/{charge,drug}, 渲染期解析候选(懒加载)。
 * 数据契约: props.model 为父级结构化数据对象(structForm), 组件按 field.fieldKey 读写其属性(不重赋 model 本身)。
 * 注册: HIS.components.EmrField (须在 inp-record-panel.js / emr-designer.js / 各 views 之前加载; 依赖 api.js 的 HIS.get)。 */
;(function () {
  'use strict';
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 值域候选缓存(模块级): key=source+':'+kw, value=[{code,name}] —— 同页多字段共享, 避免重复请求 */
  const dictCache = {};

  /* 按 dictRef.source 解析候选: 返回 Promise<Array<{code,name}>>; 未知源返回空数组(回落本地 options) */
  function loadDict(source, keyword) {
    const src = String(source || '').toLowerCase();
    const kw = String(keyword || '').trim();
    const ck = src + ':' + kw;
    if (dictCache[ck]) { return Promise.resolve(dictCache[ck]); }
    let url = null;
    if (src === 'diag') {
      url = '/api/community-dict/diag-dict/page?page=1&size=30' + (kw ? '&keyword=' + encodeURIComponent(kw) : '');
    } else if (src === 'charge') {
      url = '/api/org-catalog/available/charge?page=1&size=30' + (kw ? '&keyword=' + encodeURIComponent(kw) : '');
    } else if (src === 'drug') {
      url = '/api/org-catalog/available/drug?page=1&size=30' + (kw ? '&keyword=' + encodeURIComponent(kw) : '');
    }
    if (!url) { return Promise.resolve([]); }
    return HIS.get(url).then(function (d) {
      const recs = (d && d.records) || [];
      const opts = recs.map(function (r) {
        return { code: r.diagCode || r.itemCode || r.drugCode || r.code || r.id,
          name: r.diagName || r.itemName || r.drugName || r.name || '' };
      }).filter(function (o) { return o.name; });
      dictCache[ck] = opts;
      return opts;
    }).catch(function () { return []; });
  }
  HIS.emrLoadDict = loadDict;

  /* 规范化选项: field.options 可能是数组(rawFields)或逗号分隔字符串(旧 fields) */
  function normOptions(field) {
    const o = field && field.options;
    if (!o) { return []; }
    if (Array.isArray(o)) {
      return o.map(function (x) { return typeof x === 'object' ? x : { label: String(x), value: String(x) }; });
    }
    return String(o).split(/[,，;；]/).map(function (s) { return s.trim(); }).filter(Boolean)
      .map(function (s) { return { label: s, value: s }; });
  }

  function isMultiType(t) { return t === 'checkbox' || t === 'multiselect'; }

  const EmrField = {
    name: 'EmrField',
    props: {
      field: { type: Object, required: true },
      model: { type: Object, required: true },
      readonly: { type: Boolean, default: false }
    },
    data: function () {
      return { remoteOpts: [], remoteLoading: false, vitals: { t: '', p: '', r: '', bp: '' } };
    },
    computed: {
      type: function () { return String(this.field.type || 'text'); },
      isSection: function () { return this.type === 'section'; },
      key: function () { return this.field.fieldKey; },
      label: function () { return this.field.label || this.field.fieldKey; },
      required: function () { return this.field.required === true || String(this.field.required) === 'true'; },
      placeholder: function () { return this.field.placeholder || ''; },
      dictSource: function () {
        const ref = this.field.dictRef;
        if (ref && ref.source) { return String(ref.source); }
        if (this.type === 'diagnosis') { return 'diag'; }
        if (this.type === 'catalog') { return 'charge'; }
        return null;
      },
      opts: function () { return normOptions(this.field); },
      value: {
        get: function () { return this.model[this.key]; },
        set: function (v) { this.model[this.key] = v; }
      }
    },
    watch: {
      field: { immediate: true, handler: function () { this.ensureShape(); } }
    },
    created: function () {
      this.ensureShape();
      if (this.type === 'vitals') { this.parseVitals(this.model[this.key]); }
      if (this.dictSource) { this.fetchRemote(''); }
    },
    methods: {
      ensureShape: function () {
        if (isMultiType(this.type)) {
          if (!Array.isArray(this.model[this.key])) { this.$set ? this.$set(this.model, this.key, []) : (this.model[this.key] = []); }
        } else if (this.type === 'table') {
          if (!Array.isArray(this.model[this.key])) { this.model[this.key] = []; }
        } else if (this.model[this.key] === undefined) {
          this.model[this.key] = '';
        }
      },
      fieldMax: function () { const n = Number(this.field.maxLength); return n > 0 ? n : undefined; },
      /* ===== 值域远程候选 ===== */
      fetchRemote: function (kw) {
        const vm = this;
        if (!vm.dictSource) { return; }
        vm.remoteLoading = true;
        loadDict(vm.dictSource, kw).then(function (list) { vm.remoteOpts = list || []; })
          .finally(function () { vm.remoteLoading = false; });
      },
      onRemoteSearch: function (kw) { this.fetchRemote(kw); },
      selectOpts: function () { return this.dictSource ? this.remoteOpts.map(function (o) { return { label: o.name, value: o.name }; }) : this.opts; },
      /* ===== 生命体征复合控件: 存为 "T:x P:x R:x BP:x" ===== */
      parseVitals: function (s) {
        const v = { t: '', p: '', r: '', bp: '' };
        const str = String(s || '');
        const m = str.match(/T[:：]\s*([\d.]+)/i); if (m) { v.t = m[1]; }
        m = str.match(/P[:：]\s*(\d+)/i); if (m) { v.p = m[1]; }
        m = str.match(/R[:：]\s*(\d+)/i); if (m) { v.r = m[1]; }
        m = str.match(/BP[:：]\s*([\d/]+)/i); if (m) { v.bp = m[1]; }
        this.vitals = v;
      },
      emitVitals: function () {
        const v = this.vitals;
        const parts = [];
        if (v.t) { parts.push('T:' + v.t); }
        if (v.p) { parts.push('P:' + v.p); }
        if (v.r) { parts.push('R:' + v.r); }
        if (v.bp) { parts.push('BP:' + v.bp); }
        this.model[this.key] = parts.join(' ');
      },
      fillNormalVitals: function () { this.vitals = { t: '36.5', p: '78', r: '18', bp: '120/80' }; this.emitVitals(); },
      /* ===== 子表(table) ===== */
      subFields: function () { return Array.isArray(this.field.subFields) ? this.field.subFields : []; },
      addRow: function () {
        const row = {};
        this.subFields().forEach(function (sf) { row[sf.fieldKey] = ''; });
        this.model[this.key].push(row);
      },
      delRow: function (idx) { this.model[this.key].splice(idx, 1); },
      /* ===== 电子签名: 唤起全局签名板, 存 base64 dataURL ===== */
      signName: function () { const u = (HIS.getUser && HIS.getUser()) || {}; return u.realName || u.username || ''; },
      doSign: function () {
        const vm = this;
        if (!window.HIS || !HIS.SignaturePad || typeof HIS.SignaturePad.open !== 'function') {
          ElementPlus.ElMessage.warning('签名板未就绪'); return;
        }
        HIS.SignaturePad.open(function (dataUrl) {
          if (dataUrl) { vm.model[vm.key] = dataUrl; }
        });
      }
    },
    template: [
      '<div class="emr-field" :class="{\'emr-field--section\': isSection}">',
      '  <template v-if="isSection"><div class="emr-field-section-title">{{ label }}</div></template>',
      '  <template v-else>',
      '    <span class="emr-field-lb"><span v-if="required" class="emr-field-req">*</span>{{ label }}</span>',
      '    <div class="emr-field-ctl">',
      /* 文本/数字/日期 */
      '      <el-input v-if="type===\'text\'" v-model="value" size="small" :disabled="readonly" :maxlength="fieldMax()" :placeholder="placeholder" style="width:240px"></el-input>',
      '      <el-input v-else-if="type===\'textarea\'" v-model="value" type="textarea" :rows="3" :autosize="{minRows:2,maxRows:8}" :disabled="readonly" :maxlength="fieldMax()" :placeholder="placeholder" class="emr-grow"></el-input>',
      '      <el-input-number v-else-if="type===\'number\'" v-model="value" :disabled="readonly" controls-position="right" size="small" style="width:160px"></el-input-number>',
      '      <el-date-picker v-else-if="type===\'date\'" v-model="value" type="date" value-format="YYYY-MM-DD" :disabled="readonly" size="small" style="width:200px"></el-date-picker>',
      '      <el-date-picker v-else-if="type===\'datetime\'" v-model="value" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" :disabled="readonly" size="small" style="width:220px"></el-date-picker>',
      /* 下拉/多选 */
      '      <el-select v-else-if="type===\'select\'" v-model="value" clearable :disabled="readonly" filterable size="small" style="width:240px" :placeholder="placeholder||\'请选择\'">',
      '        <el-option v-for="(o,i) in selectOpts()" :key="i" :label="o.label" :value="o.value"></el-option>',
      '      </el-select>',
      '      <el-select v-else-if="type===\'multiselect\'" v-model="value" multiple clearable :disabled="readonly" size="small" style="width:300px" :placeholder="placeholder||\'可多选\'">',
      '        <el-option v-for="(o,i) in selectOpts()" :key="i" :label="o.label" :value="o.value"></el-option>',
      '      </el-select>',
      /* 单选/多选(平铺) */
      '      <el-radio-group v-else-if="type===\'radio\'" v-model="value" :disabled="readonly">',
      '        <el-radio v-for="(o,i) in opts" :key="i" :label="o.value">{{ o.label }}</el-radio>',
      '      </el-radio-group>',
      '      <el-checkbox-group v-else-if="type===\'checkbox\'" v-model="value" :disabled="readonly">',
      '        <el-checkbox v-for="(o,i) in opts" :key="i" :label="o.value">{{ o.label }}</el-checkbox>',
      '      </el-checkbox-group>',
      /* 诊断/项目: 远程检索 */
      '      <el-select v-else-if="type===\'diagnosis\'||type===\'catalog\'" v-model="value" filterable remote clearable reserve-keyword :remote-method="onRemoteSearch" :loading="remoteLoading" :disabled="readonly" size="small" style="width:300px" :placeholder="placeholder||\'输入检索\'">',
      '        <el-option v-for="(o,i) in remoteOpts" :key="i" :label="o.name" :value="o.name"></el-option>',
      '      </el-select>',
      /* 生命体征 */
      '      <div v-else-if="type===\'vitals\'" class="emr-vitals">',
      '        <span class="emr-vt-lb">T</span><el-input v-model="vitals.t" size="small" :disabled="readonly" style="width:64px" @input="emitVitals"></el-input>',
      '        <span class="emr-vt-lb">P</span><el-input v-model="vitals.p" size="small" :disabled="readonly" style="width:56px" @input="emitVitals"></el-input>',
      '        <span class="emr-vt-lb">R</span><el-input v-model="vitals.r" size="small" :disabled="readonly" style="width:56px" @input="emitVitals"></el-input>',
      '        <span class="emr-vt-lb">BP</span><el-input v-model="vitals.bp" size="small" :disabled="readonly" style="width:88px" @input="emitVitals"></el-input>',
      '        <el-button size="small" :disabled="readonly" @click="fillNormalVitals">正常值</el-button>',
      '      </div>',
      /* 子表 */
      '      <div v-else-if="type===\'table\'" class="emr-table-wrap">',
      '        <el-table :data="value" size="small" border style="width:100%">',
      '          <el-table-column v-for="(sf,ci) in subFields()" :key="ci" :label="sf.label||sf.fieldKey" min-width="120">',
      '            <template #default="s"><el-input v-model="s.row[sf.fieldKey]" size="small" :disabled="readonly"></el-input></template>',
      '          </el-table-column>',
      '          <el-table-column v-if="!readonly" label="操作" width="60" align="center">',
      '            <template #default="s"><el-button link type="danger" size="small" @click="delRow(s.$index)">删</el-button></template>',
      '          </el-table-column>',
      '        </el-table>',
      '        <el-button v-if="!readonly" size="small" @click="addRow" style="margin-top:4px">+ 加行</el-button>',
      '      </div>',
      /* 电子签名 */
      '      <div v-else-if="type===\'signature\'" class="emr-sign">',
      '        <span class="emr-sign-name">{{ signName() }}</span>',
      '        <img v-if="value" :value="value" :src="value" class="emr-sign-img" alt="签名">',
      '        <el-button v-if="!readonly" size="small" @click="doSign">签名</el-button>',
      '      </div>',
      /* 兜底文本 */
      '      <el-input v-else v-model="value" size="small" :disabled="readonly" :maxlength="fieldMax()" :placeholder="placeholder" style="width:240px"></el-input>',
      '    </div>',
      '  </template>',
      '</div>'
    ].join(''),
    components: {}
  };

  HIS.components.EmrField = EmrField;

  /* 注入样式(全局单点): 字段行标签+控件栅格; 供病历面板/设计器/门诊共用 */
  (function injectCss() {
    let st = document.getElementById('emr-field-css');
    if (!st) { st = document.createElement('style'); st.id = 'emr-field-css'; document.head.appendChild(st); }
    st.textContent = [
      '.emr-field { display:flex; align-items:flex-start; gap:8px; margin:6px 0; }',
      '.emr-field-lb { flex:none; width:96px; text-align:right; color:var(--yb-ink-2); font-size:var(--yb-fs-sm); padding-top:5px; line-height:1.4; }',
      '.emr-field-req { color:var(--yb-danger, #f56c6c); margin-right:2px; }',
      '.emr-field-ctl { flex:1; min-width:0; display:flex; flex-wrap:wrap; align-items:flex-start; gap:6px; }',
      '.emr-grow { flex:1; min-width:260px; }',
      '.emr-field--section { display:block; margin:12px 0 6px; }',
      '.emr-field-section-title { font-weight:600; color:var(--yb-ink-1); border-left:3px solid var(--yb-brand,#2a6ebb); padding-left:8px; }',
      '.emr-vitals { display:flex; align-items:center; gap:4px; flex-wrap:wrap; }',
      '.emr-vt-lb { color:var(--yb-ink-3); font-size:12px; }',
      '.emr-sign { display:flex; align-items:center; gap:8px; }',
      '.emr-sign-name { color:var(--yb-ink-1); font-weight:600; }',
      '.emr-sign-img { height:34px; border:1px solid var(--yb-divider,#eee); }'
    ].join('');
  })();
})();
