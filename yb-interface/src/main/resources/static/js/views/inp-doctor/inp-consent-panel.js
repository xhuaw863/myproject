/* 住院医生站 - 知情同意书面板: 七类同意书(手术/麻醉/输血/特殊检查/特殊治疗/自费/病危) 创建→签署→撤销闭环
 * 接口: GET /api/his/inp/consent/list?visitId&page&size (分页, 创建时间倒序)
 *       POST /api/his/inp/consent {inpVisitId,consentType,title,content,doctorId} (创建待签)
 *       PUT /{id}/sign (待签→已签, 记录患者/医师签署时间) | PUT /{id}/revoke (已签→已撤销)
 * 状态机: 1待签 → 2已签 → 3已撤销
 * 打印: 后端暂无同意书渲染模板, 前端组装 HTML(HIS.printHtmlFrame) 打印签署留痕
 * 注册: HIS.components.InpConsentPanel (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  const CONSENT_TYPE = { 1: '手术', 2: '麻醉', 3: '输血', 4: '特殊检查', 5: '特殊治疗', 6: '自费', 7: '病危' };
  const CONSENT_TYPE_FULL = {
    1: '手术同意书', 2: '麻醉同意书', 3: '输血治疗同意书', 4: '特殊检查同意书',
    5: '特殊治疗同意书', 6: '自费项目知情同意书', 7: '病危告知同意书'
  };
  const CONSENT_TYPE_TAG = { 1: '', 2: 'warning', 3: 'danger', 4: 'info', 5: 'info', 6: 'success', 7: 'danger' };
  const CONSENT_STATUS = { 1: '待签', 2: '已签', 3: '已撤销' };
  const CONSENT_STATUS_TAG = { 1: 'warning', 2: 'success', 3: 'info' };

  /* 内联 SVG 图标(项目未引入图标库, 统一 24 视框 / currentColor 染色), 经 v-html 渲染 */
  const ICONS = {
    check: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="m8.5 12.5 2.5 2.5 5-5"/></svg>',
    close: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="m9 9 6 6M15 9l-6 6"/></svg>',
    printer: '<svg class="iw-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9V3h12v6"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="7" rx="1"/></svg>'
  };

  function timeText(value) {
    if (!value) { return '-'; }
    const s = String(value).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* 内容摘要: JSON 美化 / 纯文本原样 */
  function contentText(v) {
    if (v == null || v === '') { return '(内容待填写)'; }
    const s = String(v);
    if (s.charAt(0) === '{' || s.charAt(0) === '[') {
      try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s; }
    }
    return s;
  }

  const InpConsentPanel = {
    name: 'InpConsentPanel',
    props: { visitId: { type: [String, Number], default: null } },
    data() {
      return {
        loading: false,
        rows: [],
        page: 1,
        size: 10,
        total: 0,
        doctors: [],
        types: CONSENT_TYPE,
        /* 新建对话框 */
        createVisible: false,
        creating: false,
        form: { consentType: 1, title: '', content: '', doctorId: null },
        /* 操作图标 */
        icons: ICONS
      };
    },
    computed: {
      staffMap() {
        const m = {};
        (this.doctors || []).forEach(function (s) { m[s.id] = s.staffName; });
        return m;
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler() {
          this.page = 1;
          this.rows = [];
          this.total = 0;
          this.load();
        }
      }
    },
    mounted() {
      this.loadDoctors();
    },
    methods: {
      timeText,
      contentText,
      typeText(v) { return CONSENT_TYPE[v] || '-'; },
      typeFull(v) { return CONSENT_TYPE_FULL[v] || '知情同意书'; },
      typeTag(v) { const t = CONSENT_TYPE_TAG[v]; return t === undefined ? 'info' : t; },
      statusText(v) { return CONSENT_STATUS[v] || '-'; },
      statusTag(v) { const t = CONSENT_STATUS_TAG[v]; return t === undefined ? 'info' : t; },
      doctorText(id) { return id == null ? '-' : (this.staffMap[id] || String(id)); },
      doctorLabel(d) { return d.staffName + (d.titleName ? '（' + d.titleName + '）' : ''); },
      loadDoctors() {
        const vm = this;
        HIS.get('/api/his/staff/list?staffType=' + encodeURIComponent('医师'))
          .then(function (list) { vm.doctors = list || []; })
          .catch(function () { });
      },
      load() {
        const vm = this;
        if (!vm.visitId) { vm.rows = []; vm.total = 0; return Promise.resolve(); }
        vm.loading = true;
        return HIS.get('/api/his/inp/consent/list?visitId=' + encodeURIComponent(vm.visitId) +
                       '&page=' + vm.page + '&size=' + vm.size)
          .then(function (data) {
            vm.rows = (data && data.records) || [];
            vm.total = (data && Number(data.total)) || 0;
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      onPage(p) { this.page = p; this.load(); },
      openCreate() {
        const vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        vm.form = { consentType: 1, title: CONSENT_TYPE_FULL[1], content: '', doctorId: null };
        vm.createVisible = true;
        if (!vm.doctors.length) { vm.loadDoctors(); }
      },
      onTypeChange(v) {
        /* 标题跟随类型默认值(用户可改) */
        const cur = String(this.form.title || '');
        const isDefault = !cur || Object.keys(CONSENT_TYPE_FULL).some(k => cur === CONSENT_TYPE_FULL[k]);
        if (isDefault) { this.form.title = CONSENT_TYPE_FULL[v] || '知情同意书'; }
      },
      submitCreate() {
        const vm = this;
        if (!vm.form.consentType) { ElementPlus.ElMessage.warning('请选择同意书类型'); return; }
        if (!String(vm.form.title || '').trim()) { ElementPlus.ElMessage.warning('请填写同意书标题'); return; }
        vm.creating = true;
        HIS.post('/api/his/inp/consent', {
          inpVisitId: HIS.id(vm.visitId),
          consentType: vm.form.consentType,
          title: String(vm.form.title).trim(),
          content: String(vm.form.content || ''),
          doctorId: vm.form.doctorId
        }).then(function () {
          HIS.notifySuccess('同意书已创建, 待患者/家属签署');
          vm.createVisible = false;
          vm.page = 1;
          vm.load();
        }).catch(HIS.notifyError)
          .finally(function () { vm.creating = false; });
      },
      sign(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认「' + (row.title || vm.typeFull(row.consentType)) + '」已完成谈话并签署？签署后将记录双方签字时间。', '签署确认', {
          type: 'warning', confirmButtonText: '确认签署', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/consent/' + encodeURIComponent(row.id) + '/sign');
        }).then(function () {
          HIS.notifySuccess('同意书已签署');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      revoke(row) {
        const vm = this;
        ElementPlus.ElMessageBox.confirm('确认撤销「' + (row.title || '') + '」？撤销后保留签署留痕, 不可恢复。', '撤销确认', {
          type: 'error', confirmButtonText: '确认撤销', cancelButtonText: '返回'
        }).then(function () {
          return HIS.put('/api/his/inp/consent/' + encodeURIComponent(row.id) + '/revoke');
        }).then(function () {
          HIS.notifySuccess('同意书已撤销');
          vm.load();
        }).catch(function (e) {
          if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        });
      },
      /* 打印: 后端暂无渲染模板, 前端组装签署留痕 HTML */
      printConsent(row) {
        const vm = this;
        const title = row.title || vm.typeFull(row.consentType);
        const html = '<html><head><meta charset="utf-8"><title>' + esc(title) + '</title>' +
          '<style>body{font-family:SimSun,"Songti SC",serif;padding:32px;font-size:14px;line-height:1.8;color:#000}' +
          'h1{font-size:20px;text-align:center;margin-bottom:6px}' +
          '.meta{color:#555;font-size:12px;text-align:center;margin-bottom:16px}' +
          '.body{white-space:pre-wrap;border:1px solid #bbb;padding:14px;min-height:220px}' +
          '.sign{margin-top:48px;display:flex;justify-content:space-between;font-size:14px}</style></head><body>' +
          '<h1>' + esc(title) + '</h1>' +
          '<div class="meta">类型: ' + esc(vm.typeText(row.consentType)) + ' | 谈话医师: ' + esc(vm.doctorText(row.doctorId)) +
          ' | 患者签字: ' + esc(vm.timeText(row.patientSignTime)) + ' | 医师签字: ' + esc(vm.timeText(row.doctorSignTime)) + '</div>' +
          '<div class="body">' + esc(vm.contentText(row.content)) + '</div>' +
          '<div class="sign"><span>患者/家属签名: ________________</span><span>谈话医师签名: ________________</span><span>日期: ____________</span></div>' +
          '</body></html>';
        if (typeof HIS.printHtmlFrame === 'function') { HIS.printHtmlFrame(html, title); }
        else if (typeof HIS.openPrintWindow === 'function') { HIS.openPrintWindow(title, html); }
        else { ElementPlus.ElMessage.warning('打印组件不可用'); }
      }
    },
    template: `
      <div class="iw-panel-body iw-panel-body--flush">
        <div class="iw-toolbar">
          <span class="iw-toolbar-info">共 <b>{{ total }}</b> 份同意书</span>
          <span class="iw-toolbar-right">
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button size="small" type="primary" :disabled="!visitId" @click="openCreate">新建同意书</el-button>
          </span>
        </div>

        <div class="iw-table-scroll" v-loading="loading">
          <el-table :data="rows" height="100%" size="small" border :row-key="row => row.id">
            <el-table-column type="expand">
              <template #default="s">
                <div style="padding:8px 14px;font-size:13px;color:var(--yb-ink-2)">
                  <div style="margin-bottom:6px">
                    谈话医师 <b>{{ doctorText(s.row.doctorId) }}</b>
                    · 患者签字 {{ timeText(s.row.patientSignTime) }}
                    · 医师签字 {{ timeText(s.row.doctorSignTime) }}
                    <template v-if="s.row.witnessSignTime"> · 见证人签字 {{ timeText(s.row.witnessSignTime) }}</template>
                  </div>
                  <pre style="white-space:pre-wrap;font-family:inherit;margin:0;padding:10px;border:1px dashed var(--yb-border);border-radius:4px;background:var(--yb-surface-2)">{{ contentText(s.row.content) }}</pre>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="92" align="center">
              <template #default="s">
                <el-tag size="small" :type="typeTag(s.row.consentType)" disable-transitions>{{ typeText(s.row.consentType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="标题" min-width="200" show-overflow-tooltip>
              <template #default="s">{{ s.row.title || typeFull(s.row.consentType) }}</template>
            </el-table-column>
            <el-table-column label="谈话医师" width="104" show-overflow-tooltip>
              <template #default="s">{{ doctorText(s.row.doctorId) }}</template>
            </el-table-column>
            <el-table-column label="签署时间" width="140">
              <template #default="s">{{ timeText(s.row.patientSignTime) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="82" align="center">
              <template #default="s">
                <el-tag size="small" :type="statusTag(s.row.status)" disable-transitions>{{ statusText(s.row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="84" fixed="right" align="center">
              <template #default="s">
                <span class="iw-ops">
                  <el-tooltip v-if="Number(s.row.status) === 1" content="签署" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-success" @click="sign(s.row)" v-html="icons.check"></button>
                  </el-tooltip>
                  <el-tooltip v-if="Number(s.row.status) === 2" content="撤销" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn is-danger" @click="revoke(s.row)" v-html="icons.close"></button>
                  </el-tooltip>
                  <el-tooltip content="打印" placement="top" :show-after="200">
                    <button type="button" class="iw-icobtn" @click="printConsent(s.row)" v-html="icons.printer"></button>
                  </el-tooltip>
                </span>
              </template>
            </el-table-column>
            <template #empty><div class="iw-empty-line">暂无同意书, 点击右上「新建同意书」创建</div></template>
          </el-table>
        </div>

        <div class="iw-pager" v-if="total > size">
          <el-pagination small background layout="prev, pager, next" :total="total" :page-size="size"
                         :current-page="page" @current-change="onPage"></el-pagination>
        </div>

        <!-- 新建同意书对话框 -->
        <el-dialog v-model="createVisible" title="新建知情同意书" width="620px" :close-on-click-modal="false">
          <div class="iw-form">
            <div class="iw-form-row">
              <span class="lb">类型</span>
              <el-select v-model="form.consentType" size="small" style="width:200px" @change="onTypeChange">
                <el-option v-for="(label, key) in types" :key="key" :label="label + '同意书'" :value="Number(key)"></el-option>
              </el-select>
            </div>
            <div class="iw-form-row">
              <span class="lb">标题</span>
              <el-input v-model="form.title" size="small" class="iw-grow" maxlength="100" placeholder="同意书标题"></el-input>
            </div>
            <div class="iw-form-row">
              <span class="lb">谈话医师</span>
              <el-select v-model="form.doctorId" size="small" filterable clearable style="width:220px" placeholder="选择谈话医师">
                <el-option v-for="d in doctors" :key="d.id" :label="doctorLabel(d)" :value="d.id"></el-option>
              </el-select>
            </div>
            <div class="iw-form-row" style="align-items:flex-start">
              <span class="lb" style="padding-top:6px">内容</span>
              <el-input v-model="form.content" type="textarea" :rows="7" maxlength="4000" class="iw-grow"
                        placeholder="同意书正文(纯文本或JSON, 打印时按签署留痕输出)"></el-input>
            </div>
          </div>
          <template #footer>
            <el-button @click="createVisible = false">取消</el-button>
            <el-button type="primary" :loading="creating" @click="submitCreate">创建(待签)</el-button>
          </template>
        </el-dialog>
      </div>
    `
  };

  HIS.components.InpConsentPanel = InpConsentPanel;
})();
