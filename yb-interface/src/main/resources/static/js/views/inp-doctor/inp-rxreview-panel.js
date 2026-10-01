/* 住院医生站 - 处方点评页签(T2 阶段3): 待点评池(就诊下未点评药品医嘱) → 发起点评(冻结快照) → 点评提交(结论/问题类型/评分/意见留痕)。
 * 端点: GET /api/his/inp/rx-review/pool?inpVisitId= | GET /api/his/inp/rx-review/list?inpVisitId=&status=
 *       POST /api/his/inp/rx-review/initiate {orderId} | POST /api/his/inp/rx-review/{id}/review {result,problemType,score,comment}
 *       DELETE /api/his/inp/rx-review/{id}
 * 注册: HIS.components.InpRxReviewPanel(供 InpDoctorWorkstation 局部注册 'inp-rxreview', 脚本须早于 inp-doctor.js)。样式私有前缀 irr-*。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* 点评结论: 1合理 2不规范 3不合理 */
  var RESULT_LABELS = { 1: '合理', 2: '不规范', 3: '不合理' };
  var RESULT_TAG = { 1: 'success', 2: 'warning', 3: 'danger' };
  /* 常见问题类型(处方点评口径) */
  var PROBLEM_TYPES = [
    { code: 'INDICATION', name: '适应证不适宜' },
    { code: 'DRUG_CHOICE', name: '遴选药品不适宜' },
    { code: 'DOSAGE', name: '剂量/用法不适宜' },
    { code: 'COMBINATION', name: '联合用药不适宜' },
    { code: 'DUPLICATION', name: '重复给药' },
    { code: 'INTERACTION', name: '相互作用/不良相互作用' },
    { code: 'CONTRAINDICATION', name: '禁忌用药' },
    { code: 'ROUTE', name: '给药途径/溶媒不适宜' },
    { code: 'OTHER', name: '其他不规范' }
  ];

  function injectCss() {
    if (document.getElementById('inp-rxreview-css')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-rxreview-css';
    st.textContent = [
      '.irr-panel{padding:14px 16px;height:100%;box-sizing:border-box;overflow:auto;background:var(--yb-surface-2)}',
      '.irr-bar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:12px}',
      '.irr-bar .irr-hint{color:var(--yb-ink-4);font-size:var(--yb-fs-sm)}',
      '.irr-card{background:var(--yb-surface);border:1px solid var(--yb-border);border-radius:var(--yb-r-md);padding:10px 12px;margin-bottom:10px}',
      '.irr-card-hd{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:6px}',
      '.irr-card-title{font-weight:600;color:var(--yb-ink)}',
      '.irr-meta{display:flex;flex-wrap:wrap;gap:6px 18px;font-size:var(--yb-fs-sm);color:var(--yb-ink-3)}',
      '.irr-meta .k{color:var(--yb-ink-4)}',
      '.irr-empty{padding:26px;text-align:center;color:var(--yb-ink-4);font-size:var(--yb-fs-base)}',
      '.irr-res{display:inline-flex;align-items:center;gap:6px}',
      '.irr-res .score{font-variant-numeric:tabular-nums;font-weight:600}',
      '.irr-snapshot{margin:8px 0;padding:8px 10px;background:var(--yb-surface-2);border-radius:var(--yb-r-sm);font-size:var(--yb-fs-sm);color:var(--yb-ink-2)}',
      '.irr-snapshot .row{display:flex;gap:16px;flex-wrap:wrap}',
      '.irr-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}',
      '.irr-form-grid .full{grid-column:1 / -1}',
      '@media (max-width:900px){.irr-form-grid{grid-template-columns:1fr}}'
    ].join('\n');
    document.head.appendChild(st);
  }
  injectCss();

  var InpRxReviewPanel = {
    name: 'InpRxReviewPanel',
    props: {
      patientId: { type: [String, Number], default: null },
      visitId: { type: [String, Number], default: null }
    },
    data: function () {
      return {
        seg: 'pool',
        pool: [],
        reviews: [],
        loading: false,
        poolLoading: false,
        submitting: false,
        dialog: { visible: false, mode: 'review', record: null, form: { result: 1, problemType: '', score: null, comment: '' } },
        RESULT_LABELS: RESULT_LABELS,
        RESULT_TAG: RESULT_TAG,
        PROBLEM_TYPES: PROBLEM_TYPES
      };
    },
    watch: {
      visitId: function () { this.reload(); }
    },
    methods: {
      vid: function () { return this.visitId != null ? String(this.visitId) : ''; },
      resultText: function (r) { return RESULT_LABELS[Number(r)] || '—'; },
      resultTag: function (r) { return RESULT_TAG[Number(r)] || 'info'; },
      problemName: function (code) {
        if (!code) { return ''; }
        var hit = PROBLEM_TYPES.filter(function (p) { return p.code === code; })[0];
        return hit ? hit.name : code;
      },
      snap: function (record) {
        if (!record || !record.rxItemSnapshot) { return {}; }
        try { return JSON.parse(record.rxItemSnapshot); } catch (e) { return {}; }
      },
      usageText: function (s) {
        if (!s) { return ''; }
        var parts = [];
        if (s.dosage) { parts.push('剂量 ' + s.dosage + (s.dosageUnit || '')); }
        if (s.usageCode) { parts.push('用法 ' + s.usageCode); }
        if (s.freqCode) { parts.push('频次 ' + s.freqCode); }
        if (s.spec) { parts.push('规格 ' + s.spec); }
        return parts.join(' · ');
      },
      autoList: function (record) {
        if (!record || !record.autoFindings) { return []; }
        try { var a = JSON.parse(record.autoFindings); return Array.isArray(a) ? a : []; } catch (e) { return []; }
      },
      hasAuto: function (record) { return !!(record && Number(record.autoEvaluated) === 1); },
      adoptSuggestion: function () {
        var r = this.dialog.record;
        if (!r) { return; }
        var list = this.autoList(r);
        if (r.autoResult != null) { this.dialog.form.result = Number(r.autoResult); }
        if (r.autoProblemType) { this.dialog.form.problemType = r.autoProblemType; }
        if (r.autoScore != null) { this.dialog.form.score = Number(r.autoScore); }
        if (!this.dialog.form.comment) {
          this.dialog.form.comment = list.map(function (x) { return '【' + x.name + '】' + (x.detail || ''); }).join('；');
        }
        ElementPlus.ElMessage.success('已采纳引擎建议, 请核对后提交');
      },
      reload: function () {
        if (this.seg === 'pool') { this.loadPool(); } else { this.loadReviews(); }
      },
      onSeg: function () { this.reload(); },
      loadPool: function () {
        var vm = this;
        if (!vm.vid()) { vm.pool = []; return; }
        vm.poolLoading = true;
        HIS.get('/api/his/inp/rx-review/pool?inpVisitId=' + HIS.idParam(vm.visitId))
          .then(function (d) { vm.pool = d || []; })
          .catch(function (e) { vm.pool = []; HIS.notifyError(e); })
          .finally(function () { vm.poolLoading = false; });
      },
      loadReviews: function () {
        var vm = this;
        if (!vm.vid()) { vm.reviews = []; return; }
        vm.loading = true;
        HIS.get('/api/his/inp/rx-review/list?inpVisitId=' + HIS.idParam(vm.visitId) + '&page=1&size=100')
          .then(function (d) { vm.reviews = (d && d.records) || []; })
          .catch(function (e) { vm.reviews = []; HIS.notifyError(e); })
          .finally(function () { vm.loading = false; });
      },
      initiate: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '将医嘱「' + (row.orderContent || row.drugName || '') + '」纳入处方点评?',
          '发起点评', { type: 'info', confirmButtonText: '发起', cancelButtonText: '取消' }
        ).then(function () {
          return HIS.post('/api/his/inp/rx-review/initiate', { orderId: HIS.id(row.orderId) });
        }).then(function () {
          HIS.notifySuccess('已发起点评, 请到「点评记录」填写结论');
          vm.loadPool();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      },
      openReview: function (record) {
        this.dialog = {
          visible: true, mode: 'review', record: record,
          form: {
            result: record.result || 1,
            problemType: record.problemType || '',
            score: record.score != null ? record.score : null,
            comment: record.comment || ''
          }
        };
      },
      submitReview: function () {
        var vm = this;
        var f = vm.dialog.form;
        if ((f.result === 2 || f.result === 3) && !f.problemType) {
          ElementPlus.ElMessage.warning('不规范/不合理结论必须选择问题类型'); return;
        }
        vm.submitting = true;
        HIS.post('/api/his/inp/rx-review/' + HIS.idParam(vm.dialog.record.id) + '/review',
          { result: f.result, problemType: f.problemType, score: f.score, comment: f.comment })
          .then(function () {
            HIS.notifySuccess('点评已提交留痕');
            vm.dialog.visible = false;
            vm.loadReviews();
          })
          .catch(function (e) { HIS.notifyError(e); })
          .finally(function () { vm.submitting = false; });
      },
      removeReview: function (record) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('删除该点评单?', '确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
          .then(function () { return HIS.del('/api/his/inp/rx-review/' + HIS.idParam(record.id)); })
          .then(function () { HIS.notifySuccess('已删除'); vm.loadReviews(); })
          .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
      }
    },
    mounted: function () { this.reload(); },
    template: `
      <div class="irr-panel">
        <div class="irr-bar">
          <el-radio-group v-model="seg" size="small" @change="onSeg">
            <el-radio-button label="pool">待点评池</el-radio-button>
            <el-radio-button label="reviewed">点评记录</el-radio-button>
          </el-radio-group>
          <el-button size="small" text @click="reload">刷新</el-button>
          <span class="irr-hint">处方点评: 对住院药品医嘱进行人工合理性点评并留痕(合理/不规范/不合理 + 问题类型 + 评分)</span>
        </div>

        <!-- 待点评池 -->
        <div v-if="seg === 'pool'" v-loading="poolLoading">
          <el-table :data="pool" size="small" border empty-text="该就诊暂无待点评的药品医嘱">
            <el-table-column prop="orderContent" label="医嘱内容" min-width="180" show-overflow-tooltip />
            <el-table-column prop="drugName" label="药品" min-width="140" show-overflow-tooltip />
            <el-table-column label="要素" min-width="160">
              <template #default="{ row }">
                <span class="irr-meta"><span>{{ [row.spec, (row.dosage ? row.dosage + (row.dosageUnit||'') : ''), row.usageCode, row.freqCode].filter(Boolean).join(' / ') || '—' }}</span></span>
              </template>
            </el-table-column>
            <el-table-column prop="doctorName" label="开嘱医生" width="100" />
            <el-table-column label="抗菌分级" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.abxGradeName" size="small" type="info">{{ row.abxGradeName }}</el-tag>
                <span v-else class="irr-meta">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="96" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="initiate(row)">发起点评</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 点评记录 -->
        <div v-else v-loading="loading">
          <div v-if="!reviews.length" class="irr-empty">暂无点评记录, 请到「待点评池」发起点评</div>
          <div v-for="rec in reviews" :key="rec.id" class="irr-card">
            <div class="irr-card-hd">
              <span class="irr-card-title">{{ snap(rec).orderContent || '医嘱点评' }}</span>
              <span>
                <el-tag v-if="Number(rec.status) === 1" size="small" type="info">待点评</el-tag>
                <el-tag v-else :type="resultTag(rec.result)" size="small" class="irr-res">{{ resultText(rec.result) }}<span v-if="rec.score != null" class="score">{{ rec.score }}分</span></el-tag>
                <el-button v-if="Number(rec.status) === 1" size="small" type="primary" link @click="openReview(rec)">去点评</el-button>
                <el-button v-else size="small" link @click="openReview(rec)">查看/修改</el-button>
                <el-button size="small" link type="danger" @click="removeReview(rec)">删除</el-button>
              </span>
            </div>
            <div class="irr-snapshot"><div class="row">
              <span>{{ (snap(rec).dosage ? '剂量 ' + snap(rec).dosage + (snap(rec).dosageUnit||'') : '') }}</span>
              <span>{{ snap(rec).usageCode ? ('用法 ' + snap(rec).usageCode) : '' }}</span>
              <span>{{ snap(rec).freqCode ? ('频次 ' + snap(rec).freqCode) : '' }}</span>
              <span>{{ snap(rec).spec ? ('规格 ' + snap(rec).spec) : '' }}</span>
            </div></div>
            <div class="irr-meta">
              <span v-if="Number(rec.status) === 2">
                <span class="k">问题类型:</span>{{ problemName(rec.problemType) || '—' }}
                <span class="k" style="margin-left:16px">点评意见:</span>{{ rec.comment || '—' }}
                <span class="k" style="margin-left:16px">点评时间:</span>{{ rec.reviewTime || '—' }}
              </span>
              <span v-else><span class="k">尚未点评</span><template v-if="hasAuto(rec)"> · <span class="k">引擎建议:</span><el-tag :type="resultTag(rec.autoResult)" size="small" style="margin-left:4px">{{ resultText(rec.autoResult) }}</el-tag><span v-if="autoList(rec).length" class="irr-meta" style="margin-left:6px">({{ autoList(rec).length }}项提示)</span></template></span>
            </div>
          </div>
        </div>

        <!-- 点评对话框 -->
        <el-dialog v-model="dialog.visible" :title="Number(dialog.record && dialog.record.status) === 1 ? '处方点评' : '点评详情/修改'" width="560px" append-to-body>
          <div v-if="dialog.record">
            <div class="irr-snapshot">
              <div style="font-weight:600;margin-bottom:4px">{{ snap(dialog.record).orderContent || '医嘱' }}</div>
              <div class="row">{{ usageText(snap(dialog.record)) }}</div>
            </div>
            <div v-if="hasAuto(dialog.record)" style="margin:6px 0 10px;padding:8px 10px;border:1px dashed var(--yb-border);border-radius:6px;background:var(--yb-surface-2)">
              <div style="display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:4px">
                <span class="irr-meta"><span class="k">规则引擎建议:</span>
                  <el-tag :type="resultTag(dialog.record.autoResult)" size="small">{{ resultText(dialog.record.autoResult) }}</el-tag>
                  <span v-if="dialog.record.autoScore != null" class="score" style="margin-left:6px">{{ dialog.record.autoScore }}分</span>
                </span>
                <el-button size="small" type="primary" link @click="adoptSuggestion">采纳建议</el-button>
              </div>
              <ul style="margin:0;padding-left:18px;font-size:var(--yb-fs-sm);color:var(--yb-ink-2)">
                <li v-for="(f,fi) in autoList(dialog.record)" :key="fi">{{ f.name }}<span v-if="f.detail"> — {{ f.detail }}</span></li>
              </ul>
            </div>
            <div class="irr-form-grid">
              <div class="full">
                <div class="irr-meta"><span class="k">点评结论</span></div>
                <el-radio-group v-model="dialog.form.result">
                  <el-radio :label="1">合理</el-radio>
                  <el-radio :label="2">不规范</el-radio>
                  <el-radio :label="3">不合理</el-radio>
                </el-radio-group>
              </div>
              <div>
                <div class="irr-meta"><span class="k">问题类型</span></div>
                <el-select v-model="dialog.form.problemType" placeholder="选择问题类型" clearable style="width:100%" :disabled="dialog.form.result === 1">
                  <el-option v-for="p in PROBLEM_TYPES" :key="p.code" :label="p.name" :value="p.code" />
                </el-select>
              </div>
              <div>
                <div class="irr-meta"><span class="k">评分(0-100)</span></div>
                <el-input-number v-model="dialog.form.score" :min="0" :max="100" controls-position="right" style="width:100%" />
              </div>
              <div class="full">
                <div class="irr-meta"><span class="k">点评意见</span></div>
                <el-input v-model="dialog.form.comment" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="填写点评理由/建议" />
              </div>
            </div>
          </div>
          <template #footer>
            <el-button size="small" @click="dialog.visible=false">取消</el-button>
            <el-button size="small" type="primary" :loading="submitting" @click="submitReview">提交点评</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpRxReviewPanel = InpRxReviewPanel;
})();
