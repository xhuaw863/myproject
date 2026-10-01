/* 病历数据元检索/上报: HIS.views.EmrElementSearch —— 基于 his_emr_element 的字段级检索 + 单份病历透视 + CSV 导出。
 * 后端契约: GET /api/his/emr/element/search(分页要素) | GET /api/his/emr/element/pivot?scope&visitId&recordId(病案首页/上报透视)
 * 数据由病历保存/提交时 EmrElementService 抽取。导出走前端 Blob(无服务端文件端点)。
 * 注册: HIS.views.EmrElementSearch(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDateTime(d) {
    if (!d) { return ''; }
    if (typeof d === 'string') { return d.replace('T', ' ').slice(0, 19); }
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
      + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }
  function dispVal(row) {
    if (row.valueText != null && row.valueText !== '') { return row.valueText; }
    if (row.valueNum != null) { return String(row.valueNum); }
    if (row.valueDate) { return fmtDateTime(row.valueDate); }
    if (row.termCode) { return row.termCode; }
    return '';
  }

  HIS.views.EmrElementSearch = {
    data: function () {
      return {
        loading: false, rows: [], total: 0, page: 1, size: 20,
        f: { scope: null, fieldKey: '', valueText: '', patientId: '', visitId: '', recordId: '',
          deptId: '', doctorId: '', valueNumMin: '', valueNumMax: '', valueDateStart: '', valueDateEnd: '' },
        showAdv: false,
        pivotVisible: false, pivotLoading: false, pivot: null, pivotTitle: ''
      };
    },
    created: function () { /* 不自动全量拉取, 由用户点检索 */ },
    methods: {
      buildQuery: function (extraSize) {
        var f = this.f, parts = [];
        function add(k, v) { if (v !== null && v !== undefined && String(v).trim() !== '') { parts.push(k + '=' + encodeURIComponent(String(v).trim())); } }
        add('scope', f.scope); add('fieldKey', f.fieldKey); add('valueText', f.valueText);
        add('patientId', f.patientId); add('visitId', f.visitId); add('recordId', f.recordId);
        add('deptId', f.deptId); add('doctorId', f.doctorId);
        add('valueNumMin', f.valueNumMin); add('valueNumMax', f.valueNumMax);
        add('valueDateStart', f.valueDateStart); add('valueDateEnd', f.valueDateEnd);
        add('page', this.page); add('size', extraSize || this.size);
        return parts.length ? ('&' + parts.join('&')) : '';
      },
      search: function (resetPage) {
        var vm = this;
        if (resetPage !== false) { vm.page = 1; }
        vm.loading = true;
        HIS.get('/api/his/emr/element/search?' + vm.buildQuery()).then(function (d) {
          vm.rows = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      reset: function () {
        this.f = { scope: null, fieldKey: '', valueText: '', patientId: '', visitId: '', recordId: '',
          deptId: '', doctorId: '', valueNumMin: '', valueNumMax: '', valueDateStart: '', valueDateEnd: '' };
        this.rows = []; this.total = 0; this.page = 1;
      },
      onPage: function (p) { this.page = p; this.search(false); },
      onSize: function (s) { this.size = s; this.page = 1; this.search(false); },
      scopeName: function (s) { return s === 2 ? '门诊' : (s === 1 ? '住院' : '-'); },
      openPivot: function (row) {
        var vm = this;
        vm.pivot = null; vm.pivotVisible = true; vm.pivotLoading = true;
        vm.pivotTitle = vm.scopeName(row.scope) + '病历透视 · ' + (row.recordId ? ('记录#' + row.recordId) : ('就诊#' + row.visitId));
        var url = '/api/his/emr/element/pivot?scope=' + row.scope
          + (row.recordId != null ? ('&recordId=' + encodeURIComponent(row.recordId)) : ('&visitId=' + encodeURIComponent(row.visitId)));
        HIS.get(url).then(function (d) { vm.pivot = d || {}; })
          .catch(HIS.notifyError).finally(function () { vm.pivotLoading = false; });
      },
      pivotEntries: function () {
        var p = (this.pivot && this.pivot.fields) || {};
        return Object.keys(p).map(function (k) { return { key: k, label: p[k].label, value: p[k].value }; });
      },
      /* CSV 导出当前页(要素扁平): 引号转义 + Blob 下载 */
      exportCsv: function () {
        var vm = this;
        if (!vm.rows.length) { ElementPlus.ElMessage.warning('暂无可导出的检索结果'); return; }
        var head = ['范围', '字段键', '字段名', '值', '术语码', '字典源', '患者ID', '就诊ID', '记录ID', '科室ID', '医生ID', '记录类型', '创建时间'];
        var lines = [head.join(',')];
        vm.rows.forEach(function (r) {
          var cells = [vm.scopeName(r.scope), r.fieldKey, r.fieldLabel, dispVal(r), r.termCode, r.dictSource,
            r.patientId, r.visitId, r.recordId, r.deptId, r.doctorId, r.recordType, fmtDateTime(r.createTime)];
          lines.push(cells.map(vm.csvCell).join(','));
        });
        vm.download('病历要素检索_' + Date.now() + '.csv', '\ufeff' + lines.join('\r\n'));
      },
      csvCell: function (v) {
        var s = v == null ? '' : String(v);
        if (/[",\r\n]/.test(s)) { s = '"' + s.replace(/"/g, '""') + '"'; }
        return s;
      },
      download: function (name, content) {
        try {
          var blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
          var url = URL.createObjectURL(blob);
          var a = document.createElement('a');
          a.href = url; a.download = name; document.body.appendChild(a); a.click();
          document.body.removeChild(a); setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        } catch (e) { HIS.notifyError(e); }
      }
    },
    template: [
      '<div>',
      '  <div class="page-card">',
      '    <div style="font-size:16px;font-weight:600;color:var(--yb-ink-1);">病历数据元检索 / 上报</div>',
      '    <div style="color:var(--yb-ink-2);font-size:13px;margin-top:6px;">',
      '      结构化病历按字段抽取为可检索要素 · 支持字段值/数值区间/日期区间/患者/就诊/科室/医生多维筛选 · 透视生成病案首页/上报数据集',
      '    </div>',
      '  </div>',
      '  <el-card shadow="never" style="margin-top:14px;">',
      '    <el-form :inline="true" size="small" @submit.prevent>',
      '      <el-form-item label="范围">',
      '        <el-select v-model="f.scope" placeholder="全部" clearable style="width:100px;"><el-option label="住院" :value="1"></el-option><el-option label="门诊" :value="2"></el-option></el-select>',
      '      </el-form-item>',
      '      <el-form-item label="字段键"><el-input v-model="f.fieldKey" placeholder="如 chiefComplaint" style="width:160px;"></el-input></el-form-item>',
      '      <el-form-item label="字段值(模糊)"><el-input v-model="f.valueText" placeholder="文本包含" style="width:180px;"></el-input></el-form-item>',
      '      <el-form-item>',
      '        <el-button type="primary" :loading="loading" @click="search()">检索</el-button>',
      '        <el-button @click="reset">重置</el-button>',
      '        <el-button link type="primary" @click="showAdv=!showAdv">{{ showAdv ? "收起" : "更多筛选" }}</el-button>',
      '        <el-button :disabled="!rows.length" @click="exportCsv">导出CSV</el-button>',
      '      </el-form-item>',
      '    </el-form>',
      '    <el-form v-if="showAdv" :inline="true" size="small" @submit.prevent style="margin-top:4px;">',
      '      <el-form-item label="患者ID"><el-input v-model="f.patientId" style="width:130px;"></el-input></el-form-item>',
      '      <el-form-item label="就诊ID"><el-input v-model="f.visitId" style="width:130px;"></el-input></el-form-item>',
      '      <el-form-item label="记录ID"><el-input v-model="f.recordId" style="width:130px;"></el-input></el-form-item>',
      '      <el-form-item label="科室ID"><el-input v-model="f.deptId" style="width:110px;"></el-input></el-form-item>',
      '      <el-form-item label="医生ID"><el-input v-model="f.doctorId" style="width:110px;"></el-input></el-form-item>',
      '      <el-form-item label="数值≥"><el-input v-model="f.valueNumMin" style="width:100px;"></el-input></el-form-item>',
      '      <el-form-item label="数值≤"><el-input v-model="f.valueNumMax" style="width:100px;"></el-input></el-form-item>',
      '      <el-form-item label="值日期起"><el-date-picker v-model="f.valueDateStart" type="date" value-format="YYYY-MM-DD" style="width:150px;"></el-date-picker></el-form-item>',
      '      <el-form-item label="值日期止"><el-date-picker v-model="f.valueDateEnd" type="date" value-format="YYYY-MM-DD" style="width:150px;"></el-date-picker></el-form-item>',
      '      <el-form-item><el-button type="primary" :loading="loading" @click="search()">检索</el-button></el-form-item>',
      '    </el-form>',
      '    <el-table :data="rows" v-loading="loading" size="small" border stripe max-height="540" empty-text="输入条件后点击检索">',
      '      <el-table-column label="范围" width="70"><template #default="s">{{ scopeName(s.row.scope) }}</template></el-table-column>',
      '      <el-table-column prop="fieldLabel" label="字段" width="140" show-overflow-tooltip><template #default="s">{{ s.row.fieldLabel || s.row.fieldKey }}</template></el-table-column>',
      '      <el-table-column prop="fieldKey" label="字段键" width="150" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="值" min-width="200" show-overflow-tooltip><template #default="s">{{ dispVal(s.row) }}</template></el-table-column>',
      '      <el-table-column prop="termCode" label="术语码" width="100" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="visitId" label="就诊" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="recordId" label="记录" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="创建时间" width="150"><template #default="s">{{ fmtDateTime(s.row.createTime) }}</template></el-table-column>',
      '      <el-table-column label="操作" width="80" fixed="right"><template #default="s"><el-button link type="primary" size="small" @click="openPivot(s.row)">透视</el-button></template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10,20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </el-card>',
      /* ---- 透视抽屉 ---- */
      '  <el-drawer v-model="pivotVisible" :title="pivotTitle" size="46%">',
      '    <div v-loading="pivotLoading" style="padding:0 4px;">',
      '      <el-descriptions v-if="pivot && pivot.meta && pivot.meta.patientId" :column="2" border size="small" style="margin-bottom:12px;">',
      '        <el-descriptions-item label="患者ID">{{ pivot.meta.patientId }}</el-descriptions-item>',
      '        <el-descriptions-item label="范围">{{ pivot.meta.scope===2?"门诊":"住院" }}</el-descriptions-item>',
      '        <el-descriptions-item label="科室ID">{{ pivot.meta.deptId || "-" }}</el-descriptions-item>',
      '        <el-descriptions-item label="医生ID">{{ pivot.meta.doctorId || "-" }}</el-descriptions-item>',
      '        <el-descriptions-item label="记录类型">{{ pivot.meta.recordType != null ? pivot.meta.recordType : "-" }}</el-descriptions-item>',
      '        <el-descriptions-item label="要素数">{{ pivot.elementCount }}</el-descriptions-item>',
      '      </el-descriptions>',
      '      <el-table :data="pivotEntries()" size="small" border stripe max-height="520" empty-text="无透视字段">',
      '        <el-table-column prop="label" label="字段" width="160"></el-table-column>',
      '        <el-table-column prop="value" label="值" min-width="240" show-overflow-tooltip></el-table-column>',
      '      </el-table>',
      '    </div>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };
})();
