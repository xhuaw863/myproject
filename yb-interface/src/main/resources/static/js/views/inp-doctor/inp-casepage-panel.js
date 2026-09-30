/* 住院医生站 - 病案首页面板: 聚合生成 → 草稿编辑 → 提交(医师签名) → 质控审核(评分+签名) → 打印
 * 接口: POST /api/his/inp/case-page/generate/{visitId} (聚合生成, 幂等)
 *       GET  /api/his/inp/case-page/{visitId} (查询, exists=false 未生成)
 *       PUT  /api/his/inp/case-page/{visitId} (保存草稿, 白名单字段)
 *       POST /{visitId}/submit {doctorSignImg} (草稿→已提交)
 *       POST /{visitId}/audit  {score, qcSignImg} (已提交→已审核)
 *       GET  /{visitId}/print (打印数据, 前端组装 HTML)
 * 状态机: 1草稿(可编辑) → 2已提交(质控可操作) → 3已审核(全部只读)
 * 布局按《住院病案首页》标准六区: 患者信息/入院信息/出院信息/手术操作/费用汇总/签名与质控
 * 签名: HIS.SignaturePad.open({actionType, refType:'case_front_page', refId}) 返回签名图URL
 * 注册: HIS.components.InpCasePagePanel (须在 inp-doctor.js 之前加载) */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  const STATUS_TEXT = { 1: '草稿', 2: '已提交', 3: '已审核' };
  const STATUS_TAG = { 1: 'info', 2: 'warning', 3: 'success' };
  /* 手术切口类型: 1清洁 2清洁污染 3污染 4感染(his_surgery.incision_type) */
  const INCISION_TYPES = { 1: '清洁', 2: '清洁污染', 3: '污染', 4: '感染' };
  /* 愈合等级(病案首页手工填写): 甲/乙/丙级 */
  const HEAL_LEVELS = { '甲': '甲级', '乙': '乙级', '丙': '丙级' };

  function dateText(v) {
    if (v == null || v === '') { return '—'; }
    return String(v).replace('T', ' ').substring(0, 10);
  }

  function timeText(v) {
    if (v == null || v === '') { return '—'; }
    const s = String(v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }

  function moneyText(v) {
    const n = Number(v);
    return isNaN(n) ? '0.00' : n.toFixed(2);
  }

  function nz(v) { return v == null ? '' : v; }

  function num(v) {
    const n = Number(v);
    return isNaN(n) ? 0 : n;
  }

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* ============================================================
   * 样式(注入一次): 模拟纸质病案首页 —— 表格边框线 + 标题灰底 + 签名虚线框
   * ============================================================ */
  const CSS = `
.cp-panel { display:flex; flex-direction:column; }
.cp-scroll { flex:1; min-height:0; overflow:auto; padding:12px; background:var(--yb-surface-3); }
.cp-sheet { max-width:1080px; margin:0 auto; background:var(--yb-surface); border:1px solid var(--yb-border-strong); box-shadow:var(--yb-sh-1); padding:18px 22px 24px; }
.cp-sheet-title { text-align:center; margin-bottom:6px; }
.cp-sheet-title h1 { margin:0; font-size:20px; font-weight:700; letter-spacing:8px; color:var(--yb-ink-1); }
.cp-sheet-meta { display:flex; justify-content:space-between; align-items:center; gap:10px; margin-top:8px; font-size:var(--yb-fs-sm); color:var(--yb-ink-3); flex-wrap:wrap; }
.cp-sheet-meta b { color:var(--yb-ink-1); font-family:var(--yb-font-mono); }
.cp-sec { margin-top:12px; }
.cp-sec-hd { background:var(--yb-surface-2); border:1px solid var(--yb-border-strong); border-bottom:none; padding:5px 10px; font-weight:600; font-size:var(--yb-fs-base); color:var(--yb-ink-1); }
.cp-tb { width:100%; border-collapse:collapse; table-layout:fixed; }
.cp-tb td { border:1px solid var(--yb-border-strong); padding:5px 8px; font-size:var(--yb-fs-base); color:var(--yb-ink-1); vertical-align:middle; word-break:break-all; }
.cp-tb td.lb { width:76px; background:var(--yb-surface-2); color:var(--yb-ink-3); font-size:var(--yb-fs-sm); text-align:center; }
.cp-tb td.lb2 { width:52px; background:var(--yb-surface-2); color:var(--yb-ink-3); font-size:var(--yb-fs-cap); text-align:center; }
.cp-tb td.in { padding:2px 4px; }
.cp-tb .money { font-family:var(--yb-font-mono); font-variant-numeric:tabular-nums; }
.cp-tb .money.danger { color:var(--yb-danger); }
.cp-diag-line { display:flex; align-items:center; gap:8px; padding:2px 0; }
.cp-diag-line .code { flex:none; width:72px; font-family:var(--yb-font-mono); font-size:var(--yb-fs-sm); color:var(--yb-ink-3); }
.cp-diag-line .name { color:var(--yb-ink-1); }
.cp-edit-line { display:flex; align-items:center; gap:6px; padding:2px 0; }
.cp-op-wrap { border:1px solid var(--yb-border-strong); border-top:none; }
.cp-op-actions { padding:6px 0 0; }
.cp-sign-row { display:grid; grid-template-columns:repeat(3, 1fr); gap:12px; margin-top:12px; }
@media (max-width:1100px) { .cp-sign-row { grid-template-columns:1fr; } }
.cp-sign-cell { border:1px dashed var(--yb-border-strong); border-radius:var(--yb-r-sm); padding:10px; background:var(--yb-surface); }
.cp-sign-lb { font-size:var(--yb-fs-sm); color:var(--yb-ink-3); margin-bottom:8px; }
.cp-sign-box { min-height:52px; display:flex; align-items:center; justify-content:center; border-bottom:1px dashed var(--yb-divider); margin-bottom:8px; }
.cp-sign-box img { max-height:48px; max-width:100%; }
.cp-sign-cell .iw-dim { display:block; margin-top:6px; }
.cp-qc-row { margin-top:12px; display:flex; align-items:center; gap:10px; flex-wrap:wrap; padding:10px 12px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); background:var(--yb-surface-2); }
.cp-qc-row .lb { color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }
.cp-qc-score { font-family:var(--yb-font-mono); font-size:var(--yb-fs-lg); font-weight:600; color:var(--yb-gold); }
.cp-empty { flex:1; display:flex; align-items:center; justify-content:center; }
.cp-empty-card { display:flex; flex-direction:column; align-items:center; gap:10px; padding:36px 52px; border:1px dashed var(--yb-border-strong); border-radius:var(--yb-r-md); background:var(--yb-surface); }
.cp-empty-title { font-size:var(--yb-fs-lg); font-weight:600; color:var(--yb-ink-1); }
.cp-fee-input { width:130px; }
`;

  function ensureStyle() {
    if (document.getElementById('inp-casepage-css')) { return; }
    const st = document.createElement('style');
    st.id = 'inp-casepage-css';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  function emptyForm() {
    return {
      admissionDiagCode: '', admissionDiagName: '',
      dischargeMainDiagCode: '', dischargeMainDiagName: '',
      pathologyDiag: '', injuryPoisonCode: '', bloodType: '', rh: '',
      totalCost: 0, drugCost: 0, examCost: 0, treatmentCost: 0, bedCost: 0,
      nursingCost: 0, materialCost: 0, otherCost: 0, selfPay: 0, insurancePay: 0,
      autopsy: 0
    };
  }

  const InpCasePagePanel = {
    name: 'InpCasePagePanel',
    props: {
      visitId: { type: [String, Number], default: null },
      visitStatus: { type: [String, Number], default: null }
    },
    data() {
      return {
        loading: false,
        generating: false,
        saving: false,
        submitting: false,
        auditing: false,
        printing: false,
        exists: false,
        status: null,
        base: {},
        form: emptyForm(),
        otherDiags: [],
        opRecords: [],
        score: null,
        incisionTypes: INCISION_TYPES,
        healLevels: HEAL_LEVELS
      };
    },
    computed: {
      statusText() { return this.status == null ? '—' : (STATUS_TEXT[this.status] || '—'); },
      statusTag() { return STATUS_TAG[this.status] || 'info'; },
      canEdit() { return this.exists && this.status === 1; },
      canSubmit() { return this.exists && this.status === 1; },
      canAudit() { return this.exists && this.status === 2; },
      ageText() {
        const vm = this;
        if (vm.base.age != null && vm.base.age !== '') { return vm.base.age + '岁'; }
        const bd = vm.base.birthDate;
        if (!bd) { return '—'; }
        const ts = new Date(String(bd).replace(' ', 'T')).getTime();
        if (isNaN(ts)) { return '—'; }
        return Math.max(0, Math.floor((Date.now() - ts) / 31557600000)) + '岁';
      },
      wardBedText() {
        const vm = this;
        const parts = [];
        if (vm.base.wardName) { parts.push(vm.base.wardName); }
        if (vm.base.bedNo) { parts.push(vm.base.bedNo + '床'); }
        return parts.length ? parts.join(' ') : '—';
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler() { this.load(); }
      }
    },
    mounted() { ensureStyle(); },
    methods: {
      dateText,
      timeText,
      moneyText,
      disp(v) { return v == null || v === '' ? '—' : v; },
      dispDate(v) { return dateText(v); },
      dispTime(v) { return timeText(v); },
      genderText(v) {
        if (v === '1' || v === '男') { return '男'; }
        if (v === '2' || v === '女') { return '女'; }
        return v || '—';
      },
      incisionText(v) {
        if (v == null || v === '') { return '—'; }
        return INCISION_TYPES[v] || String(v);
      },
      healText(v) {
        if (v == null || v === '') { return '—'; }
        return HEAL_LEVELS[v] || String(v);
      },
      load() {
        const vm = this;
        vm.otherDiags = [];
        vm.opRecords = [];
        vm.score = null;
        vm.form = emptyForm();
        if (!vm.visitId) {
          vm.exists = false;
          vm.status = null;
          vm.base = {};
          vm.loading = false;
          return Promise.resolve();
        }
        const id = vm.visitId;
        vm.loading = true;
        return HIS.get('/api/his/inp/case-page/' + encodeURIComponent(id))
          .then(function (d) {
            if (String(vm.visitId) !== String(id)) { return; }
            vm.applyData(d || {});
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      applyData(d) {
        const vm = this;
        vm.base = d;
        vm.exists = !!d.exists;
        vm.status = d.exists ? Number(d.status || 1) : null;
        vm.form = {
          admissionDiagCode: nz(d.admissionDiagCode),
          admissionDiagName: nz(d.admissionDiagName),
          dischargeMainDiagCode: nz(d.dischargeMainDiagCode),
          dischargeMainDiagName: nz(d.dischargeMainDiagName),
          pathologyDiag: nz(d.pathologyDiag),
          injuryPoisonCode: nz(d.injuryPoisonCode),
          bloodType: nz(d.bloodType),
          rh: nz(d.rh),
          totalCost: num(d.totalCost), drugCost: num(d.drugCost), examCost: num(d.examCost),
          treatmentCost: num(d.treatmentCost), bedCost: num(d.bedCost), nursingCost: num(d.nursingCost),
          materialCost: num(d.materialCost), otherCost: num(d.otherCost),
          selfPay: num(d.selfPay), insurancePay: num(d.insurancePay),
          autopsy: num(d.autopsy)
        };
        vm.otherDiags = Array.isArray(d.dischargeOtherDiags)
          ? d.dischargeOtherDiags.map(function (x) { return { code: nz(x && x.code), name: nz(x && x.name) }; })
          : [];
        vm.opRecords = Array.isArray(d.operationRecords)
          ? d.operationRecords.map(function (r) {
              return {
                name: nz(r && r.name), code: nz(r && r.code), date: nz(r && r.date),
                surgeon: nz(r && r.surgeon),
                anesthesia: nz(r && r.anesthesia),
                incisionType: r && r.incisionType != null ? r.incisionType : '',
                healLevel: nz(r && r.healLevel)
              };
            })
          : [];
        vm.score = d.qualityScore != null ? Number(d.qualityScore) : null;
      },
      generate() {
        const vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先从左侧选择患者'); return; }
        if (vm.generating) { return; }
        const run = function () {
          vm.generating = true;
          HIS.post('/api/his/inp/case-page/generate/' + encodeURIComponent(vm.visitId))
            .then(function (d) {
              vm.applyData(d || {});
              HIS.notifySuccess('病案首页已生成');
            })
            .catch(HIS.notifyError)
            .finally(function () { vm.generating = false; });
        };
        if (vm.exists && vm.status === 1) {
          ElementPlus.ElMessageBox.confirm('重新生成将按当前诊断/手术/费用明细覆盖草稿内容(手工修改将丢失), 确认继续？',
            '重新生成病案首页', { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' })
            .then(run)
            .catch(function (e) { if (e !== 'cancel' && e !== 'close') { HIS.notifyError(e); } });
        } else {
          run();
        }
      },
      buildPayload() {
        const vm = this;
        return Object.assign({}, vm.form, {
          dischargeOtherDiags: vm.otherDiags.filter(function (d) {
            return (d.code && String(d.code).trim()) || (d.name && String(d.name).trim());
          }),
          operationRecords: vm.opRecords.filter(function (r) {
            return r.name && String(r.name).trim();
          })
        });
      },
      save() {
        const vm = this;
        if (!vm.canEdit) { ElementPlus.ElMessage.warning('仅草稿状态可编辑保存'); return; }
        if (vm.saving) { return; }
        vm.saving = true;
        HIS.put('/api/his/inp/case-page/' + encodeURIComponent(vm.visitId), vm.buildPayload())
          .then(function () {
            HIS.notifySuccess('病案首页已保存');
            return vm.load();
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.saving = false; });
      },
      submit() {
        const vm = this;
        if (!vm.exists) { ElementPlus.ElMessage.warning('请先生成病案首页'); return; }
        if (vm.status !== 1) { ElementPlus.ElMessage.warning('仅草稿状态可提交'); return; }
        if (!HIS.SignaturePad) { ElementPlus.ElMessage.warning('签名组件未加载'); return; }
        if (vm.submitting) { return; }
        vm.submitting = true;
        /* 先保存当前编辑(含手术/其他诊断), 再电子签名提交 */
        HIS.put('/api/his/inp/case-page/' + encodeURIComponent(vm.visitId), vm.buildPayload())
          .then(function () {
            return HIS.SignaturePad.open({
              actionType: 'case_page_submit', refType: 'case_front_page', refId: HIS.id(vm.visitId)
            });
          })
          .then(function (r) {
            const url = r && r.signImgUrl;
            if (!url) { throw new Error('未获取到签名图, 请重新签名'); }
            return HIS.post('/api/his/inp/case-page/' + encodeURIComponent(vm.visitId) + '/submit',
              { doctorSignImg: url });
          })
          .then(function () {
            HIS.notifySuccess('病案首页已提交, 待质控审核');
            return vm.load();
          })
          .catch(function (e) {
            if (e === 'cancelled') { return; }
            HIS.notifyError(e);
          })
          .finally(function () { vm.submitting = false; });
      },
      audit() {
        const vm = this;
        if (!vm.exists) { ElementPlus.ElMessage.warning('请先生成病案首页'); return; }
        if (vm.status !== 2) { ElementPlus.ElMessage.warning('仅已提交状态可质控审核'); return; }
        const score = Number(vm.score);
        if (vm.score == null || vm.score === '' || isNaN(score) || score < 0 || score > 100) {
          ElementPlus.ElMessage.warning('请先填写 0-100 的质控评分');
          return;
        }
        if (!HIS.SignaturePad) { ElementPlus.ElMessage.warning('签名组件未加载'); return; }
        if (vm.auditing) { return; }
        vm.auditing = true;
        HIS.SignaturePad.open({
          actionType: 'case_page_audit', refType: 'case_front_page', refId: HIS.id(vm.visitId)
        })
          .then(function (r) {
            const url = r && r.signImgUrl;
            if (!url) { throw new Error('未获取到签名图, 请重新签名'); }
            return HIS.post('/api/his/inp/case-page/' + encodeURIComponent(vm.visitId) + '/audit',
              { score: score, qcSignImg: url });
          })
          .then(function () {
            HIS.notifySuccess('质控审核完成');
            return vm.load();
          })
          .catch(function (e) {
            if (e === 'cancelled') { return; }
            HIS.notifyError(e);
          })
          .finally(function () { vm.auditing = false; });
      },
      print() {
        const vm = this;
        if (!vm.exists) { ElementPlus.ElMessage.warning('请先生成病案首页'); return; }
        if (vm.printing) { return; }
        vm.printing = true;
        HIS.get('/api/his/inp/case-page/' + encodeURIComponent(vm.visitId) + '/print')
          .then(function (d) {
            const html = vm.buildPrintHtml(d || {});
            if (typeof HIS.printHtmlFrame === 'function') { HIS.printHtmlFrame(html, '住院病案首页'); }
            else { ElementPlus.ElMessage.warning('打印组件不可用'); }
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.printing = false; });
      },
      addOtherDiag() { this.otherDiags.push({ code: '', name: '' }); },
      addOpRecord() {
        this.opRecords.push({ name: '', code: '', date: '', surgeon: '', anesthesia: '', incisionType: '', healLevel: '' });
      },
      /* 打印 HTML: 纸质病案首页样式(宋体/黑边框/签名区), 签名图转绝对路径供 iframe 加载 */
      buildPrintHtml(d) {
        const vm = this;
        const abs = function (url) {
          if (!url) { return ''; }
          if (/^https?:/i.test(url)) { return url; }
          return location.origin + (url.charAt(0) === '/' ? '' : '/') + url;
        };
        const td = function (lb, val, colspan) {
          return '<td class="lb">' + esc(lb) + '</td><td class="vl"' + (colspan ? ' colspan="' + colspan + '"' : '') + '>'
            + esc(val == null || val === '' ? '—' : val) + '</td>';
        };
        const ops = Array.isArray(d.operationRecords) ? d.operationRecords : [];
        let opRows = '';
        if (ops.length) {
          ops.forEach(function (r, i) {
            opRows += '<tr><td>' + (i + 1) + '</td><td>' + esc(r.name) + '</td><td>' + esc(r.date)
              + '</td><td>' + esc(r.surgeon) + '</td><td>' + esc(r.anesthesia)
              + '</td><td>' + esc(r.incisionType == null || r.incisionType === '' ? '—' : (INCISION_TYPES[r.incisionType] || r.incisionType))
              + '</td><td>' + esc(r.healLevel ? (HEAL_LEVELS[r.healLevel] || r.healLevel) : '—') + '</td></tr>';
          });
        } else {
          opRows = '<tr><td colspan="7" style="text-align:center">无手术操作记录</td></tr>';
        }
        const others = Array.isArray(d.dischargeOtherDiags) ? d.dischargeOtherDiags : [];
        const otherText = others.length
          ? others.map(function (x) { return (x.code ? x.code + ' ' : '') + (x.name || ''); }).join('; ')
          : '—';
        const signImg = function (url) {
          return url ? '<img src="' + esc(abs(url)) + '"/>' : '<span class="ph">待签署</span>';
        };
        return '<!DOCTYPE html><html><head><meta charset="utf-8"><title>住院病案首页</title><style>'
          + 'body{font-family:SimSun,"Songti SC",serif;color:#000;padding:24px;font-size:13px}'
          + 'h1{font-size:20px;text-align:center;letter-spacing:8px;margin:0 0 4px}'
          + '.meta{display:flex;justify-content:space-between;font-size:12px;color:#333;margin-bottom:8px}'
          + '.sec{margin-top:10px}'
          + '.sec h2{font-size:14px;background:#f0f0f0;border:1px solid #000;border-bottom:none;margin:0;padding:4px 8px}'
          + 'table{width:100%;border-collapse:collapse;table-layout:fixed}'
          + 'td{border:1px solid #000;padding:4px 6px;vertical-align:middle;word-break:break-all}'
          + 'td.lb{width:76px;background:#f7f7f7;text-align:center;font-size:12px}'
          + '.op td{text-align:center;font-size:12px}'
          + '.money{text-align:right;font-family:Consolas,monospace}'
          + '.sign{display:flex;gap:20px;margin-top:14px}'
          + '.sign .cell{flex:1;border:1px dashed #000;padding:8px;min-height:64px}'
          + '.sign .lb2{font-size:12px;color:#333;margin-bottom:6px}'
          + '.sign img{max-height:44px}'
          + '.ph{color:#999;font-size:12px}'
          + '</style></head><body>'
          + '<h1>住院病案首页</h1>'
          + '<div class="meta"><span>住院号: ' + esc(d.inpNo) + '</span>'
          + '<span>打印时间: ' + esc(timeText(d.printTime)) + '</span></div>'
          + '<div class="sec"><h2>一、患者基本信息</h2><table><tr>'
          + td('姓名', d.patientName) + td('性别', d.genderName || vm.genderText(d.gender)) + td('出生日期', dateText(d.birthDate)) + td('年龄', vm.ageText2(d))
          + '</tr><tr>' + td('婚姻状况', d.maritalStatus) + td('职业', d.occupation) + td('身份证号', d.idCard, 2)
          + '</tr><tr>' + td('联系人', d.contactName) + td('关系', d.contactRelation) + td('联系电话', d.contactPhone, 2)
          + '</tr><tr>' + td('户籍地址', d.householdAddr, 2) + td('现住址', d.presentAddr, 2)
          + '</tr><tr>' + td('过敏史', d.allergyDrugs, 4)
          + '</tr></table></div>'
          + '<div class="sec"><h2>二、入院信息</h2><table><tr>'
          + td('住院号', d.inpNo) + td('入院日期', timeText(d.admissionDate)) + td('入院科室', d.deptName) + td('病房/床号', vm.wardBed2(d))
          + '</tr><tr>' + td('入院诊断编码', d.admissionDiagCode) + td('入院诊断名称', d.admissionDiagName, 2)
          + td('血型/Rh', (d.bloodType || '—') + ' / ' + (d.rh || '—'))
          + '</tr></table></div>'
          + '<div class="sec"><h2>三、出院信息</h2><table><tr>'
          + td('出院日期', d.dischargeDate ? dateText(d.dischargeDate) : '—') + td('出院科室', d.deptName) + td('住院天数', d.losDays) + td('主治医师', d.doctorName)
          + '</tr><tr>' + td('主诊断编码', d.dischargeMainDiagCode) + td('主诊断名称', d.dischargeMainDiagName, 2)
          + td('病理诊断', d.pathologyDiag)
          + '</tr><tr>' + td('其他诊断', otherText, 4)
          + '</tr><tr>' + td('损伤/中毒', d.injuryPoisonCode, 4)
          + '</tr></table></div>'
          + '<div class="sec"><h2>四、手术操作</h2><table class="op"><tr><td>#</td><td>手术名称</td><td>日期</td><td>术者</td><td>麻醉方式</td><td>切口等级</td><td>愈合等级</td></tr>'
          + opRows + '</table></div>'
          + '<div class="sec"><h2>五、费用汇总</h2><table><tr>'
          + td('药品费', moneyText(d.drugCost)) + td('总费用', moneyText(d.totalCost))
          + '</tr><tr>' + td('检查费', moneyText(d.examCost)) + td('自付金额', moneyText(d.selfPay))
          + '</tr><tr>' + td('治疗费', moneyText(d.treatmentCost)) + td('医保支付', moneyText(d.insurancePay))
          + '</tr><tr>' + td('床位费', moneyText(d.bedCost)) + td('护理费', moneyText(d.nursingCost))
          + '</tr><tr>' + td('材料费', moneyText(d.materialCost)) + td('其他费用', moneyText(d.otherCost))
          + '</tr></table></div>'
          + '<div class="sec"><h2>六、签名与质控</h2><table><tr>'
          + td('主治医师', d.doctorName) + td('质控评分', d.qualityScore != null && d.qualityScore !== '' ? d.qualityScore : '—')
          + td('质控医师', d.qcDoctorName || '—') + td('质控时间', d.qcTime ? timeText(d.qcTime) : '—')
          + '</tr></table>'
          + '<div class="sign">'
          + '<div class="cell"><div class="lb2">主治医师签名</div>' + signImg(d.doctorSignImg) + '</div>'
          + '<div class="cell"><div class="lb2">护士签名</div>' + signImg(d.nurseSignImg) + '</div>'
          + '<div class="cell"><div class="lb2">质控医师签名</div>' + signImg(d.qcSignImg) + '</div>'
          + '</div></div>'
          + '</body></html>';
      },
      /* 打印页脚使用的年龄/床号纯文本(不依赖组件响应式状态) */
      ageText2(d) {
        if (d.age != null && d.age !== '') { return d.age + '岁'; }
        const bd = d.birthDate;
        if (!bd) { return '—'; }
        const ts = new Date(String(bd).replace(' ', 'T')).getTime();
        if (isNaN(ts)) { return '—'; }
        return Math.max(0, Math.floor((Date.now() - ts) / 31557600000)) + '岁';
      },
      wardBed2(d) {
        const parts = [];
        if (d.wardName) { parts.push(d.wardName); }
        if (d.bedNo) { parts.push(d.bedNo + '床'); }
        return parts.length ? parts.join(' ') : '—';
      }
    },
    template: `
      <div class="iw-panel-body iw-panel-body--flush cp-panel" v-loading="loading">
        <div class="iw-toolbar">
          <span class="iw-toolbar-info">
            病案首页
            <el-tag v-if="exists" size="small" :type="statusTag" disable-transitions>{{ statusText }}</el-tag>
            <span v-else class="iw-dim">尚未生成</span>
          </span>
          <span class="iw-toolbar-right">
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button size="small" type="primary" plain :loading="generating" :disabled="!visitId" @click="generate">生成</el-button>
            <el-button size="small" :loading="saving" :disabled="!canEdit" @click="save">保存</el-button>
            <el-button size="small" type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">提交</el-button>
            <el-button size="small" :loading="printing" :disabled="!exists" @click="print">打印</el-button>
          </span>
        </div>

        <div v-if="!visitId" class="iw-placeholder" style="flex:1">
          <span>请从左侧选择患者</span>
          <span class="iw-dim">病案首页在患者出院办理中或已出院后可生成</span>
        </div>

        <div v-else-if="!exists" class="cp-empty">
          <div class="cp-empty-card">
            <div class="cp-empty-title">病案首页尚未生成</div>
            <div class="iw-dim">点击「生成」按出院诊断 / 手术记录 / 费用明细自动聚合, 生成后可手工修订</div>
            <el-button type="primary" :loading="generating" @click="generate">立即生成</el-button>
          </div>
        </div>

        <div v-else class="cp-scroll">
          <div class="cp-sheet">
            <div class="cp-sheet-title">
              <h1>住 院 病 案 首 页</h1>
              <div class="cp-sheet-meta">
                <span>住院号: <b>{{ disp(base.inpNo) }}</b></span>
                <span>姓名: <b>{{ disp(base.patientName) }}</b></span>
                <span>住院状态: {{ disp(base.visitStatus) }}</span>
                <span>状态: {{ statusText }}</span>
              </div>
            </div>

            <!-- 第一区 患者基本信息 -->
            <section class="cp-sec">
              <div class="cp-sec-hd">一、患者基本信息</div>
              <table class="cp-tb">
                <tr>
                  <td class="lb">姓名</td><td>{{ disp(base.patientName) }}</td>
                  <td class="lb">性别</td><td>{{ base.genderName || genderText(base.gender) }}</td>
                  <td class="lb">出生日期</td><td>{{ dispDate(base.birthDate) }}</td>
                  <td class="lb">年龄</td><td>{{ ageText }}</td>
                </tr>
                <tr>
                  <td class="lb">婚姻状况</td><td>{{ disp(base.maritalStatus) }}</td>
                  <td class="lb">职业</td><td>{{ disp(base.occupation) }}</td>
                  <td class="lb">身份证号</td><td colspan="3">{{ disp(base.idCard) }}</td>
                </tr>
                <tr>
                  <td class="lb">联系人</td><td>{{ disp(base.contactName) }}</td>
                  <td class="lb">关系</td><td>{{ disp(base.contactRelation) }}</td>
                  <td class="lb">联系电话</td><td colspan="3">{{ disp(base.contactPhone) }}</td>
                </tr>
                <tr>
                  <td class="lb">户籍地址</td><td colspan="3">{{ disp(base.householdAddr) }}</td>
                  <td class="lb">现住址</td><td colspan="3">{{ disp(base.presentAddr) }}</td>
                </tr>
                <tr>
                  <td class="lb">过敏史</td>
                  <td colspan="7">{{ base.allergyDrugs ? base.allergyDrugs.split(',').join('、') : '—' }}</td>
                </tr>
              </table>
            </section>

            <!-- 第二区 入院信息 -->
            <section class="cp-sec">
              <div class="cp-sec-hd">二、入院信息</div>
              <table class="cp-tb">
                <tr>
                  <td class="lb">住院号</td><td>{{ disp(base.inpNo) }}</td>
                  <td class="lb">入院日期</td><td>{{ dispTime(base.admissionDate) }}</td>
                  <td class="lb">入院科室</td><td>{{ disp(base.deptName) }}</td>
                  <td class="lb">病房/床号</td><td>{{ wardBedText }}</td>
                </tr>
                <tr>
                  <td class="lb">入院诊断</td>
                  <td class="lb2">编码</td>
                  <td class="in" colspan="2">
                    <el-input v-if="canEdit" v-model="form.admissionDiagCode" size="small" placeholder="ICD编码"></el-input>
                    <span v-else>{{ disp(form.admissionDiagCode) }}</span>
                  </td>
                  <td class="lb2">名称</td>
                  <td class="in" colspan="3">
                    <el-input v-if="canEdit" v-model="form.admissionDiagName" size="small" placeholder="入院诊断名称"></el-input>
                    <span v-else>{{ disp(form.admissionDiagName) }}</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">血型</td>
                  <td class="in" colspan="2">
                    <el-input v-if="canEdit" v-model="form.bloodType" size="small" placeholder="如 O 型"></el-input>
                    <span v-else>{{ disp(form.bloodType) }}</span>
                  </td>
                  <td class="lb2">Rh</td>
                  <td class="in" colspan="4">
                    <el-input v-if="canEdit" v-model="form.rh" size="small" placeholder="阳性 / 阴性"></el-input>
                    <span v-else>{{ disp(form.rh) }}</span>
                  </td>
                </tr>
              </table>
            </section>

            <!-- 第三区 出院信息 -->
            <section class="cp-sec">
              <div class="cp-sec-hd">三、出院信息</div>
              <table class="cp-tb">
                <tr>
                  <td class="lb">出院日期</td><td>{{ base.dischargeDate ? dispDate(base.dischargeDate) : '—' }}</td>
                  <td class="lb">出院科室</td><td>{{ disp(base.deptName) }}</td>
                  <td class="lb">住院天数</td><td>{{ base.losDays != null ? base.losDays + ' 天' : '—' }}</td>
                  <td class="lb">主治医师</td><td>{{ disp(base.doctorName) }}</td>
                </tr>
                <tr>
                  <td class="lb">出院主诊断</td>
                  <td class="lb2">编码</td>
                  <td class="in" colspan="2">
                    <el-input v-if="canEdit" v-model="form.dischargeMainDiagCode" size="small" placeholder="ICD编码"></el-input>
                    <span v-else>{{ disp(form.dischargeMainDiagCode) }}</span>
                  </td>
                  <td class="lb2">名称</td>
                  <td class="in" colspan="3">
                    <el-input v-if="canEdit" v-model="form.dischargeMainDiagName" size="small" placeholder="出院主要诊断名称"></el-input>
                    <span v-else>{{ disp(form.dischargeMainDiagName) }}</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">其他诊断</td>
                  <td colspan="7">
                    <template v-if="canEdit">
                      <div class="cp-edit-line" v-for="(d, i) in otherDiags" :key="'od-' + i">
                        <el-input v-model="d.code" size="small" style="width:130px" placeholder="编码"></el-input>
                        <el-input v-model="d.name" size="small" class="iw-grow" placeholder="诊断名称"></el-input>
                        <el-button link type="danger" size="small" @click="otherDiags.splice(i, 1)">删除</el-button>
                      </div>
                      <el-button link type="primary" size="small" @click="addOtherDiag">+ 添加其他诊断</el-button>
                    </template>
                    <template v-else>
                      <div class="cp-diag-line" v-for="(d, i) in otherDiags" :key="'odv-' + i">
                        <span class="code">{{ d.code || '—' }}</span><span class="name">{{ d.name }}</span>
                      </div>
                      <span v-if="!otherDiags.length" class="iw-dim">无其他诊断</span>
                    </template>
                  </td>
                </tr>
                <tr>
                  <td class="lb">病理诊断</td>
                  <td class="in" colspan="7">
                    <el-input v-if="canEdit" v-model="form.pathologyDiag" size="small" placeholder="病理诊断(选填)"></el-input>
                    <span v-else>{{ disp(form.pathologyDiag) }}</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">损伤/中毒</td>
                  <td class="in" colspan="7">
                    <el-input v-if="canEdit" v-model="form.injuryPoisonCode" size="small" placeholder="损伤中毒外部原因编码(选填)"></el-input>
                    <span v-else>{{ disp(form.injuryPoisonCode) }}</span>
                  </td>
                </tr>
              </table>
            </section>

            <!-- 第四区 手术操作 -->
            <section class="cp-sec">
              <div class="cp-sec-hd">四、手术操作</div>
              <div class="cp-op-wrap">
                <el-table :data="opRecords" size="small" border>
                  <el-table-column type="index" label="#" width="44" align="center"></el-table-column>
                  <el-table-column label="手术名称" min-width="180">
                    <template #default="s">
                      <el-input v-if="canEdit" v-model="s.row.name" size="small" placeholder="手术名称"></el-input>
                      <span v-else>{{ disp(s.row.name) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="日期" width="128">
                    <template #default="s">
                      <el-input v-if="canEdit" v-model="s.row.date" size="small" placeholder="yyyy-MM-dd"></el-input>
                      <span v-else>{{ disp(s.row.date) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="术者" width="110">
                    <template #default="s">
                      <el-input v-if="canEdit" v-model="s.row.surgeon" size="small"></el-input>
                      <span v-else>{{ disp(s.row.surgeon) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="麻醉方式" width="132">
                    <template #default="s">
                      <el-input v-if="canEdit" v-model="s.row.anesthesia" size="small"></el-input>
                      <span v-else>{{ disp(s.row.anesthesia) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="切口等级" width="118">
                    <template #default="s">
                      <el-select v-if="canEdit" v-model="s.row.incisionType" size="small" clearable placeholder="选择">
                        <el-option v-for="(l, k) in incisionTypes" :key="'it-' + k" :label="l" :value="Number(k)"></el-option>
                      </el-select>
                      <span v-else>{{ incisionText(s.row.incisionType) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="愈合等级" width="118">
                    <template #default="s">
                      <el-select v-if="canEdit" v-model="s.row.healLevel" size="small" clearable placeholder="选择">
                        <el-option v-for="(l, k) in healLevels" :key="'hl-' + k" :label="l" :value="k"></el-option>
                      </el-select>
                      <span v-else>{{ healText(s.row.healLevel) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="canEdit" label="操作" width="64" align="center">
                    <template #default="s">
                      <el-button link type="danger" size="small" @click="opRecords.splice(s.$index, 1)">删除</el-button>
                    </template>
                  </el-table-column>
                  <template #empty><div class="iw-empty-line">无手术操作记录</div></template>
                </el-table>
              </div>
              <div v-if="canEdit" class="cp-op-actions">
                <el-button size="small" plain @click="addOpRecord">+ 添加手术</el-button>
              </div>
            </section>

            <!-- 第五区 费用汇总 -->
            <section class="cp-sec">
              <div class="cp-sec-hd">五、费用汇总</div>
              <table class="cp-tb">
                <tr>
                  <td class="lb">药品费</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.drugCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.drugCost) }}</span>
                  </td>
                  <td class="lb">总费用</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.totalCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else><b>{{ moneyText(form.totalCost) }}</b></span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">检查费</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.examCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.examCost) }}</span>
                  </td>
                  <td class="lb">自付金额</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.selfPay" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.selfPay) }}</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">治疗费</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.treatmentCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.treatmentCost) }}</span>
                  </td>
                  <td class="lb">医保支付</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.insurancePay" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.insurancePay) }}</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">床位费</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.bedCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.bedCost) }}</span>
                  </td>
                  <td class="lb">护理费</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.nursingCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.nursingCost) }}</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb">材料费</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.materialCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.materialCost) }}</span>
                  </td>
                  <td class="lb">其他费用</td>
                  <td class="money">
                    <el-input-number v-if="canEdit" v-model="form.otherCost" size="small" :min="0" :precision="2" :controls="false" class="cp-fee-input"></el-input-number>
                    <span v-else>{{ moneyText(form.otherCost) }}</span>
                  </td>
                </tr>
              </table>
            </section>

            <!-- 第六区 签名与质控 -->
            <section class="cp-sec">
              <div class="cp-sec-hd">六、签名与质控</div>
              <div class="cp-sign-row">
                <div class="cp-sign-cell">
                  <div class="cp-sign-lb">主治医师签名</div>
                  <div class="cp-sign-box">
                    <img v-if="base.doctorSignImg" :src="base.doctorSignImg" alt="主治医师签名"/>
                    <span v-else class="iw-dim">待签署</span>
                  </div>
                  <span class="iw-dim">{{ disp(base.doctorName) }}</span>
                  <el-button v-if="canSubmit" size="small" type="primary" plain :loading="submitting" @click="submit">签名并提交</el-button>
                </div>
                <div class="cp-sign-cell">
                  <div class="cp-sign-lb">护士签名</div>
                  <div class="cp-sign-box">
                    <img v-if="base.nurseSignImg" :src="base.nurseSignImg" alt="护士签名"/>
                    <span v-else class="iw-dim">待护士站签署</span>
                  </div>
                  <span class="iw-dim">{{ disp(base.nurseName) }}</span>
                </div>
                <div class="cp-sign-cell">
                  <div class="cp-sign-lb">质控医师签名</div>
                  <div class="cp-sign-box">
                    <img v-if="base.qcSignImg" :src="base.qcSignImg" alt="质控医师签名"/>
                    <span v-else class="iw-dim">待质控签署</span>
                  </div>
                  <span class="iw-dim">{{ base.qcDoctorName ? base.qcDoctorName : '质控审核后自动记录' }}</span>
                </div>
              </div>
              <div class="cp-qc-row">
                <span class="lb">质控评分</span>
                <el-input-number v-if="canAudit" v-model="score" size="small" :min="0" :max="100" :precision="0" style="width:130px"></el-input-number>
                <span v-else class="cp-qc-score">{{ base.qualityScore != null && base.qualityScore !== '' ? base.qualityScore : '—' }}</span>
                <span class="iw-dim" v-if="canAudit">0-100 分</span>
                <span class="iw-dim" v-if="base.qcTime">质控时间: {{ dispTime(base.qcTime) }}</span>
                <el-button v-if="canAudit" size="small" type="primary" :loading="auditing" @click="audit">质控签名并审核</el-button>
                <span v-if="!canAudit && !base.qcTime && status === 1" class="iw-dim">提交后进入质控环节</span>
              </div>
            </section>
          </div>
        </div>
      </div>
    `
  };

  HIS.components.InpCasePagePanel = InpCasePagePanel;
})();
