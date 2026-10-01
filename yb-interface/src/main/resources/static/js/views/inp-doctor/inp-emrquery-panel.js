/* 住院医生站 - 病历综合查询页签(T1-E): 当前就诊病案首页要素透视 + 跨患者字段级检索(住院维度收窄)
 * 直连既有端点(纯前端复用, 镜像 views/emr/emr-element-search.js):
 *   GET /api/his/emr/element/pivot?scope=1&visitId=(单份病历透视) | GET /api/his/emr/element/search(多维检索)
 * 注册: HIS.components.InpEmrqueryPanel(供 InpDoctorWorkstation 局部注册 'inp-emrquery', 脚本须早于 inp-doctor.js)。样式私有前缀 ieq-*。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

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

  var InpEmrqueryPanel = {
    name: 'InpEmrqueryPanel',
    props: { visitId: { type: [String, Number], default: null } },
    data: function () {
      return {
        /* 当前就诊透视 */
        pvLoading: false, pv: null, pvEmptyHint: '',
        /* 跨患者字段检索 */
        showAdv: false, searching: false, rows: [], total: 0, page: 1, size: 20,
        f: { scope: 1, fieldKey: '', valueText: '', patientId: '', visitId: '', recordId: '', deptId: '', doctorId: '' },
        rowPvVisible: false, rowPvLoading: false, rowPv: null, rowPvTitle: ''
      };
    },
    watch: {
      visitId: function () { this.pivotCurrent(); }
    },
    methods: {
      fmtDateTime: fmtDateTime,
      dispVal: dispVal,
      scopeName: function (s) { return s === 2 ? '门诊' : (s === 1 ? '住院' : '-'); },
      pivotEntries: function (p) {
        var fields = (p && p.fields) || {};
        return Object.keys(fields).map(function (k) { return { key: k, label: fields[k].label, value: fields[k].value }; });
      },
      /* 当前就诊透视: 住院 scope=1, 以 visitId 定位(后端 recordId 为空即走 byVisit) */
      pivotCurrent: function () {
        var vm = this;
        vm.pv = null; vm.pvEmptyHint = '';
        if (!vm.visitId) { vm.pvEmptyHint = '未选择患者, 无法预置当前就诊透视'; return; }
        vm.pvLoading = true;
        HIS.get('/api/his/emr/element/pivot?scope=1&visitId=' + encodeURIComponent(vm.visitId))
          .then(function (d) {
            vm.pv = d || {};
            if (!vm.pivotEntries(vm.pv).length) { vm.pvEmptyHint = '该就诊暂无结构化病历要素(未保存/未提交抽取)'; }
          })
          .catch(function () { vm.pvEmptyHint = '透视取数失败'; })
          .finally(function () { vm.pvLoading = false; });
      },
      buildQuery: function () {
        var f = this.f, parts = [];
        function add(k, v) { if (v !== null && v !== undefined && String(v).trim() !== '') { parts.push(k + '=' + encodeURIComponent(String(v).trim())); } }
        add('scope', f.scope); add('fieldKey', f.fieldKey); add('valueText', f.valueText);
        add('patientId', f.patientId); add('visitId', f.visitId); add('recordId', f.recordId);
        add('deptId', f.deptId); add('doctorId', f.doctorId);
        add('page', this.page); add('size', this.size);
        return parts.length ? ('&' + parts.join('&')) : '';
      },
      search: function (resetPage) {
        var vm = this;
        if (resetPage !== false) { vm.page = 1; }
        vm.searching = true;
        HIS.get('/api/his/emr/element/search?' + vm.buildQuery())
          .then(function (d) { vm.rows = (d && d.records) || []; vm.total = (d && d.total) || 0; })
          .catch(HIS.notifyError).finally(function () { vm.searching = false; });
      },
      resetSearch: function () {
        this.f = { scope: 1, fieldKey: '', valueText: '', patientId: '', visitId: '', recordId: '', deptId: '', doctorId: '' };
        this.rows = []; this.total = 0; this.page = 1;
      },
      onPage: function (p) { this.page = p; this.search(false); },
      onSize: function (s) { this.size = s; this.page = 1; this.search(false); },
      openRowPivot: function (row) {
        var vm = this;
        vm.rowPv = null; vm.rowPvVisible = true; vm.rowPvLoading = true;
        vm.rowPvTitle = vm.scopeName(row.scope) + '病历透视 · ' + (row.recordId ? ('记录#' + row.recordId) : ('就诊#' + row.visitId));
        var url = '/api/his/emr/element/pivot?scope=' + row.scope
          + (row.recordId != null ? ('&recordId=' + encodeURIComponent(row.recordId)) : ('&visitId=' + encodeURIComponent(row.visitId)));
        HIS.get(url).then(function (d) { vm.rowPv = d || {}; })
          .catch(HIS.notifyError).finally(function () { vm.rowPvLoading = false; });
      }
    },
    mounted: function () { this.pivotCurrent(); },
    template: `
      <div class="ieq-panel">
        <style>
          .ieq-panel{padding:14px 16px;height:100%;box-sizing:border-box;overflow:auto;background:var(--yb-surface-2)}
          .ieq-panel .ieq-sec{background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:var(--yb-r-md);padding:12px 14px;margin-bottom:14px}
          .ieq-panel .ieq-hd{display:flex;align-items:center;gap:8px;font-size:var(--yb-fs-md);font-weight:600;color:var(--yb-ink-1);margin-bottom:10px}
          .ieq-panel .ieq-form{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
          .ieq-panel .ieq-empty{padding:20px;text-align:center;color:var(--yb-ink-4);font-size:var(--yb-fs-sm)}
          .ieq-panel .ieq-toggle{color:var(--yb-brand);cursor:pointer;font-size:var(--yb-fs-sm);user-select:none}
        </style>

        <div class="ieq-sec">
          <div class="ieq-hd">
            当前就诊 · 病案首页要素透视
            <span class="ieq-dim" style="margin-left:auto;color:var(--yb-ink-3);font-size:var(--yb-fs-sm)">visitId {{ visitId || '-' }}</span>
            <el-button link type="primary" size="small" @click="pivotCurrent">重新透视</el-button>
          </div>
          <div v-loading="pvLoading">
            <div v-if="pv && pivotEntries(pv).length" style="margin-bottom:8px;color:var(--yb-ink-3);font-size:var(--yb-fs-sm)">
              要素数 {{ pv.elementCount }} · 患者 {{ (pv.meta && pv.meta.patientId) || '-' }} · 科室 {{ (pv.meta && pv.meta.deptId) || '-' }} · 医生 {{ (pv.meta && pv.meta.doctorId) || '-' }}
            </div>
            <el-table v-if="pv && pivotEntries(pv).length" :data="pivotEntries(pv)" size="small" border stripe max-height="360">
              <el-table-column prop="label" label="字段" width="180"></el-table-column>
              <el-table-column prop="key" label="字段键" width="180" show-overflow-tooltip></el-table-column>
              <el-table-column prop="value" label="值" min-width="240" show-overflow-tooltip></el-table-column>
            </el-table>
            <div v-else class="ieq-empty">{{ pvEmptyHint || '暂无透视数据' }}</div>
          </div>
        </div>

        <div class="ieq-sec">
          <div class="ieq-hd">
            病历数据元综合检索
            <span class="ieq-toggle" style="margin-left:auto" @click="showAdv=!showAdv">{{ showAdv ? '收起' : '展开跨患者检索' }}</span>
          </div>
          <div v-if="!showAdv" class="ieq-empty">点击「展开跨患者检索」按字段/术语/患者/科室/医生多维检索结构化病历要素</div>
          <div v-else>
            <div class="ieq-form" style="margin-bottom:8px">
              <el-select v-model="f.scope" placeholder="范围" clearable size="small" style="width:100px">
                <el-option label="住院" :value="1"></el-option><el-option label="门诊" :value="2"></el-option>
              </el-select>
              <el-input v-model="f.fieldKey" placeholder="字段键 如 chiefComplaint" size="small" style="width:180px"></el-input>
              <el-input v-model="f.valueText" placeholder="字段值(模糊)" size="small" style="width:180px"></el-input>
              <el-button type="primary" size="small" :loading="searching" @click="search()">检索</el-button>
              <el-button size="small" @click="resetSearch">重置</el-button>
            </div>
            <div class="ieq-form">
              <el-input v-model="f.patientId" placeholder="患者ID" size="small" style="width:130px"></el-input>
              <el-input v-model="f.visitId" placeholder="就诊ID" size="small" style="width:130px"></el-input>
              <el-input v-model="f.deptId" placeholder="科室ID" size="small" style="width:120px"></el-input>
              <el-input v-model="f.doctorId" placeholder="医生ID" size="small" style="width:120px"></el-input>
            </div>
            <el-table :data="rows" v-loading="searching" size="small" border stripe max-height="360" style="margin-top:10px" empty-text="输入条件后点击检索">
              <el-table-column label="范围" width="70"><template #default="s">{{ scopeName(s.row.scope) }}</template></el-table-column>
              <el-table-column label="字段" width="150" show-overflow-tooltip><template #default="s">{{ s.row.fieldLabel || s.row.fieldKey }}</template></el-table-column>
              <el-table-column prop="fieldKey" label="字段键" width="150" show-overflow-tooltip></el-table-column>
              <el-table-column label="值" min-width="200" show-overflow-tooltip><template #default="s">{{ dispVal(s.row) }}</template></el-table-column>
              <el-table-column prop="visitId" label="就诊" width="130" show-overflow-tooltip></el-table-column>
              <el-table-column prop="recordId" label="记录" width="130" show-overflow-tooltip></el-table-column>
              <el-table-column label="创建时间" width="150"><template #default="s">{{ fmtDateTime(s.row.createTime) }}</template></el-table-column>
              <el-table-column label="操作" width="80" fixed="right"><template #default="s"><el-button link type="primary" size="small" @click="openRowPivot(s.row)">透视</el-button></template></el-table-column>
            </el-table>
            <el-pagination style="margin-top:10px;justify-content:flex-end" background layout="total, sizes, prev, pager, next"
              :total="total" :page-size="size" :page-sizes="[10,20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>
          </div>
        </div>

        <el-drawer v-model="rowPvVisible" :title="rowPvTitle" size="46%" append-to-body>
          <div v-loading="rowPvLoading" style="padding:0 4px">
            <el-descriptions v-if="rowPv && rowPv.meta && rowPv.meta.patientId" :column="2" border size="small" style="margin-bottom:12px">
              <el-descriptions-item label="患者ID">{{ rowPv.meta.patientId }}</el-descriptions-item>
              <el-descriptions-item label="范围">{{ scopeName(rowPv.meta.scope) }}</el-descriptions-item>
              <el-descriptions-item label="要素数">{{ rowPv.elementCount }}</el-descriptions-item>
              <el-descriptions-item label="记录类型">{{ rowPv.meta.recordType != null ? rowPv.meta.recordType : '-' }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="pivotEntries(rowPv)" size="small" border stripe max-height="520" empty-text="无透视字段">
              <el-table-column prop="label" label="字段" width="160"></el-table-column>
              <el-table-column prop="value" label="值" min-width="240" show-overflow-tooltip></el-table-column>
            </el-table>
          </div>
        </el-drawer>
      </div>
    `
  };

  HIS.components.InpEmrqueryPanel = InpEmrqueryPanel;
})();
