/* ==================================================================
 * nursing-consent.js — 护理告知书/同意书组件(住院护士站 P4c-3)
 * ------------------------------------------------------------------
 * 定位: 入院告知/手术知情同意/麻醉同意/输血同意/特殊用药/侵入性操作/
 *       跌倒风险告知等文书的告知-签署-撤回闭环, 含患者与家属双轨
 *       手写签名(Canvas 手写板, 落库 canvas.toDataURL('image/png') base64)。
 * 版式: 顶栏(新建告知书 + 刷新) + 告知书卡片列表(名称/状态/签名时间/动作)
 *       + 表单对话框(新建/编辑待签) + 签署对话框(告知内容 + 手写签名板)
 *       + 查看对话框(内容 + 签名图像回显)。
 * 状态流: 0待签 → 1已签 → 2已撤回(待签/已签均可撤回)。
 * 签名板交互: mouse/touch 双通道; 每板独立[清除][确认]; 确认后锁定并
 *   导出 base64; 重写内容自动解除确认(需重新确认)。
 * 依赖(index.html 先于本文件加载 api.js; 无构建、无 ES module):
 *   - HIS.request(HIS.get/post/put): R{code,msg,data} 信封, code=0 成功
 *   - HIS.id/idKey/idParam: 19位雪花ID全链路字符串化治理
 * 后端契约(P4c-1 告知书接口, 表 his_nursing_consent):
 *   - GET  /api/his/inp/nursing/consent/list?inpVisitId=    就诊告知书列表
 *   - POST /api/his/inp/nursing/consent                     新建(待签, 创建即定稿)
 *           body: {inpVisitId, patientId, consentType, consentName, content, witnessName}
 *   - PUT  /api/his/inp/nursing/consent/{id}/sign           签署(0→1)
 *           body: {patientSigBase64, familySigBase64, signerName, signerRelation}
 *           (家属签名存在时 signerName 必填; 单张签名图片限200KB)
 *   - PUT  /api/his/inp/nursing/consent/{id}/revoke         撤回(待签/已签均可→2)
 *   - POST /api/emr/ca-sign/patient-sign/{signatureId}      P8b-2: 行数据携带 signatureId(从
 *           病历签名链路关联来的)时, 患者/家属签名图同步落 his_emr_signature; 独立告知书
 *           无 signatureId 仅存本表字段; 同步失败静默(try-catch), 不影响告知书签署主流程。
 *   枚举: consentType=admission/surgery/anesthesia/blood/special_drug/
 *         invasive/fall_risk/other; status 0待签 1已签 2已撤回。
 * 注册: HIS.components.InpNursingConsent + 全局标签 dw-inp-nursing-consent 双保险。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 nc- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('inp-nursing-consent-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-nursing-consent-style';
    st.textContent = [
      '.nc-root { flex:1 1 auto; display:flex; flex-direction:column; min-height:420px; min-width:0; font-size:13px; color:var(--yb-ink-1,#1c2430); background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:hidden; }',
      /* ---- 顶栏 ---- */
      '.nc-bar { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:8px 10px; padding:8px 12px; border-bottom:1px solid var(--yb-border,#dfe4eb); }',
      '.nc-icon { display:inline-flex; width:13px; height:13px; vertical-align:-2px; }',
      '.nc-icon svg { width:100%; height:100%; }',
      '.nc-spacer { flex:1 1 auto; }',
      '.nc-loading { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      /* ---- 列表 ---- */
      '.nc-list { flex:1; min-height:0; overflow-y:auto; padding:10px 12px; display:flex; flex-direction:column; gap:8px; }',
      '.nc-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; padding:30px 12px; color:var(--yb-ink-4,#8994a5); font-size:13px; }',
      '.nc-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:9px 11px; display:flex; flex-direction:column; gap:6px; }',
      '.nc-card.st-pending { border-left:3px solid #e6a23c; }',
      '.nc-card.st-signed { border-left:3px solid #3c9d3c; }',
      '.nc-card.st-revoked { border-left:3px solid var(--yb-border-strong,#ccd4de); opacity:.82; }',
      '.nc-card-head { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.nc-card-title { font-size:13px; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.nc-card-head .el-tag { margin-left:auto; }',
      '.nc-card-line { display:flex; flex-wrap:wrap; gap:4px 14px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.nc-card-actions { display:flex; gap:6px; }',
      '.nc-card-actions .el-button + .el-button { margin-left:0; }',
      /* ---- 签名板 ---- */
      '.nc-sign-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }',
      '.nc-sign-col { display:flex; flex-direction:column; gap:6px; min-width:0; }',
      '.nc-sign-col-title { font-size:12px; font-weight:600; color:var(--yb-ink-1,#1c2430); display:flex; align-items:center; gap:6px; }',
      '.nc-sign-ok { color:#3c9d3c; font-size:12px; font-weight:400; }',
      '.nc-pad-wrap { position:relative; border:1px dashed var(--yb-border-strong,#ccd4de); border-radius:6px; background:#fff; overflow:hidden; }',
      '.nc-pad-wrap.is-confirmed { border:1.5px solid #3c9d3c; }',
      '.nc-pad { display:block; width:100%; height:128px; touch-action:none; cursor:crosshair; }',
      '.nc-pad-hint { position:absolute; left:10px; top:10px; font-size:12px; color:#c2c8d2; pointer-events:none; }',
      '.nc-pad-bar { display:flex; gap:6px; }',
      '.nc-pad-bar .el-button + .el-button { margin-left:0; }',
      '.nc-sign-img { max-width:100%; border:1px solid var(--yb-border-light,#ebeff4); border-radius:4px; background:#fff; }',
      '.nc-sign-content { width:100%; white-space:pre-line; font-size:13px; line-height:1.8; color:var(--yb-ink-1,#1c2430); border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; background:var(--yb-surface-2,#f7f9fc); padding:8px 10px; max-height:150px; overflow-y:auto; }',
      /* ---- 查看对话框 ---- */
      '.nc-view-meta { display:flex; flex-wrap:wrap; gap:4px 16px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); margin-bottom:8px; }',
      '.nc-view-content { white-space:pre-line; font-size:13px; line-height:1.85; color:var(--yb-ink-1,#1c2430); border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; background:var(--yb-surface-2,#f7f9fc); padding:10px 12px; max-height:240px; overflow-y:auto; }',
      '.nc-view-signs { display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-top:12px; }',
      '.nc-view-sign-item { font-size:12px; color:var(--yb-ink-3,#5a6a7e); display:flex; flex-direction:column; gap:4px; }',
      '.nc-empty-sign { font-size:12px; color:var(--yb-ink-4,#8994a5); padding:20px 0; text-align:center; border:1px dashed var(--yb-border-light,#ebeff4); border-radius:6px; }',
      /* ---- 窄屏回落 ---- */
      '@media (max-width: 900px) { .nc-sign-grid, .nc-view-signs { grid-template-columns:1fr; } }',
      /* ---- 滚动条 ---- */
      '.nc-list::-webkit-scrollbar, .nc-view-content::-webkit-scrollbar { width:6px; }',
      '.nc-list::-webkit-scrollbar-thumb, .nc-view-content::-webkit-scrollbar-thumb { background:var(--yb-border-strong,#ccd4de); border-radius:3px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  /* 告知书类型 + 默认告知内容(模板文本供参考, 实际以医院规范文书为准, 创建后可编辑) */
  var CONSENT_TYPES = [
    { value: 'admission', label: '入院告知书', content: '一、您入住本病区后，医务人员将为您提供诊疗与护理服务，请遵守医院规章制度。\n二、住院期间请勿擅自离院，因擅自离院造成的后果由您本人承担。\n三、请妥善保管随身物品，贵重物品建议由家属带回。\n四、病区内禁止吸烟、使用明火及私接电器。\n五、请配合身份核对(腕带)、用药核对等安全措施。\n六、您有权了解病情、参与诊疗决策并可随时提出疑问。' },
    { value: 'surgery', label: '手术知情同意书', content: '一、医师已向本人(患者/家属)说明拟行手术的目的、方式及预期效果。\n二、已知晓手术可能出现的风险：出血、感染、麻醉意外、邻近器官损伤等，以及个体差异导致的其他不可预知情况。\n三、已知晓替代治疗方案及不进行手术可能产生的后果。\n四、同意术中根据实际情况调整术式并同意必要的输血。\n五、本同意书经本人充分理解后自愿签署。' },
    { value: 'anesthesia', label: '麻醉知情同意书', content: '一、麻醉医师已说明拟采用的麻醉方式(全身麻醉/椎管内麻醉/区域阻滞等)及过程。\n二、已知晓麻醉可能风险：过敏反应、呼吸循环抑制、神经损伤、恶心呕吐等。\n三、已知晓个体差异可能导致其他不可预知情况，同意麻醉期间必要的监测与处理。' },
    { value: 'blood', label: '输血知情同意书', content: '一、医师已说明输血的必要性、血液成分种类及数量。\n二、已知晓输血风险：发热反应、过敏反应、溶血反应、感染传播(乙肝/丙肝/HIV等)等。\n三、同意使用经检测合格的血液制品，同意输血过程中必要的监测。' },
    { value: 'special_drug', label: '特殊用药同意书', content: '一、医师已说明拟使用药品(如自费药/超说明书用药)的名称、用途及用法用量。\n二、已知晓该药可能的不良反应及注意事项。\n三、同意在用药期间接受监测，如出现异常将及时处理。' },
    { value: 'invasive', label: '侵入性操作同意书', content: '一、医师已说明拟行操作(如穿刺/置管/引流等)的目的、步骤及预期效果。\n二、已知晓操作风险：出血、感染、气胸、损伤邻近组织等。\n三、同意操作中根据情况调整方案。' },
    { value: 'fall_risk', label: '跌倒风险告知书', content: '一、经评估您(患者)存在跌倒/坠床风险，特此告知。\n二、请卧床时拉起床档，起床时先坐起片刻再站立，穿防滑鞋。\n三、请勿自行下床，尤其夜间；呼叫器置于随手可及处，需要帮助请按铃。\n四、地面湿滑时请勿走动，家属陪护请加强看护。\n五、如您不配合防护措施，跌倒风险将由您自行承担。' },
    { value: 'other', label: '其他告知书', content: '' }
  ];
  var TYPE_MAP = {};
  CONSENT_TYPES.forEach(function (t) { TYPE_MAP[t.value] = t; });
  var STATUS_LABELS = { 0: '待签', 1: '已签', 2: '已撤回' };
  var STATUS_TAGS = { 0: 'warning', 1: 'success', 2: 'info' };
  var RELATIONS = ['本人', '配偶', '子女', '父母', '兄弟姐妹', '祖父母', '其他'];

  /* 内联 SVG 图标 */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    plus: svg('<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    edit: svg('<path d="M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>'),
    file: svg('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>'),
    pen: svg('<path d="M12 19l7-7 3 3-7 7-3-3z"/><path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/><path d="M2 2l7.586 7.586"/><circle cx="11" cy="11" r="2"/>')
  };

  /* ================= 工具 ================= */
  function shortTime(v) {
    var s = String(v == null ? '' : v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }
  function toast(type, text) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessage) { EP.ElMessage[type](text); }
    else { try { console.log('[nc:' + type + '] ' + text); } catch (e) { /* noop */ } }
  }
  function confirmBox(text, title) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessageBox) {
      return EP.ElMessageBox.confirm(text, title || '确认', {
        type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消'
      });
    }
    return global.Promise ? global.Promise.reject(new Error('cancel')) : null;
  }

  /* ================= 组件 ================= */
  var InpNursingConsent = {
    name: 'InpNursingConsent',
    props: {
      inpVisitId: { type: [String, Number], default: null },
      patientId: { type: [String, Number], default: null }
    },
    data: function () {
      return {
        icons: ICONS,
        typeOptions: CONSENT_TYPES,
        relations: RELATIONS,
        consents: [],
        loading: false,
        loadFailed: false,
        saving: false,
        /* 表单对话框(新建) */
        formVisible: false,
        form: { consentType: 'admission', consentName: '入院告知书', content: '', witnessName: '' },
        /* 签署对话框 */
        signVisible: false,
        signTarget: null,
        signContent: '',
        signForm: { signerName: '', signerRelation: '本人' },
        padConfirmed: { patient: false, family: false },
        padImages: { patient: '', family: '' },
        /* 查看对话框 */
        viewVisible: false,
        viewTarget: null
      };
    },
    computed: {
      hasVisit: function () {
        return this.inpVisitId != null && String(this.inpVisitId).length > 0;
      }
    },
    watch: {
      inpVisitId: function () { this.resetForVisit(); }
    },
    mounted: function () {
      this.loadConsents();
    },
    methods: {
      /* ---------- 数据加载 ---------- */
      resetForVisit: function () {
        this.consents = [];
        this.loadFailed = false;
        this.loadConsents();
      },
      loadConsents: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.consents = []; return; }
        vm.loading = true;
        vm.loadFailed = false;
        HIS.get('/api/his/inp/nursing/consent/list?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .then(function (rows) {
            vm.consents = (Array.isArray(rows) ? rows : []).map(function (r) {
              return Object.assign({}, r, { id: HIS.id(r.id) });
            });
          })
          .catch(function () {
            vm.consents = [];
            vm.loadFailed = true;
          })
          .finally(function () { vm.loading = false; });
      },
      afterChanged: function (msg) {
        toast('success', msg);
        this.loadConsents();
      },

      /* ---------- 展示辅助 ---------- */
      typeLabel: function (v) {
        var t = TYPE_MAP[v];
        return t ? t.label : (v || '告知书');
      },
      statusLabel: function (v) { return STATUS_LABELS[Number(v) || 0] || '-'; },
      statusTag: function (v) { return STATUS_TAGS[Number(v) || 0] || 'info'; },
      statusCls: function (v) { return 'st-' + ['pending', 'signed', 'revoked'][Number(v) || 0]; },
      shortTime: shortTime,
      hasAnySign: function (r) {
        return !!(r && (r.patientSignatureBase64 || r.familySignatureBase64));
      },

      /* ---------- 新建(创建即定稿, 无更新端点) ---------- */
      openNew: function () {
        var vm = this;
        if (!vm.hasVisit) { toast('warning', '请先在护士站选择患者'); return; }
        var t = TYPE_MAP.admission;
        vm.form = { consentType: 'admission', consentName: t.label, content: t.content, witnessName: '' };
        vm.formVisible = true;
      },
      onTypeChange: function (val) {
        var t = TYPE_MAP[val];
        if (!t) { return; }
        /* 名称与内容未被手动改过时随类型联动 */
        var cur = String(this.form.consentName || '').trim();
        var isAuto = !cur || Object.keys(TYPE_MAP).some(function (k) { return TYPE_MAP[k].label === cur; });
        if (isAuto) { this.form.consentName = t.label; }
        if (!this.form.content || this.form.content === this._lastTemplateContent) {
          this.form.content = t.content;
        }
        this._lastTemplateContent = t.content;
      },
      submitForm: function () {
        var vm = this;
        if (!String(vm.form.consentName || '').trim()) { toast('warning', '请填写告知书名称'); return; }
        if (!String(vm.form.content || '').trim()) { toast('warning', '请填写告知内容'); return; }
        var payload = {
          inpVisitId: HIS.id(vm.inpVisitId),
          patientId: HIS.id(vm.patientId),
          consentType: vm.form.consentType,
          consentName: String(vm.form.consentName).trim(),
          content: vm.form.content,
          witnessName: String(vm.form.witnessName || '').trim() || null
        };
        vm.saving = true;
        HIS.post('/api/his/inp/nursing/consent', payload).then(function () {
          vm.saving = false;
          vm.formVisible = false;
          vm.afterChanged('告知书已创建(待签)');
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      },

      /* ---------- 签署(Canvas 签名板) ---------- */
      openSign: function (r) {
        var vm = this;
        vm.signTarget = r;
        vm.signContent = r.content || '';
        vm.signForm = {
          signerName: r.signerName || '',
          signerRelation: r.signerRelation || '本人'
        };
        vm.padConfirmed = { patient: false, family: false };
        vm.padImages = { patient: '', family: '' };
        vm._pads = {};
        vm.signVisible = true;
      },
      /* 对话框展开动画结束后初始化两块签名板(此时 canvas 已获得实际布局宽度) */
      onSignOpened: function () {
        this.initPad('patient');
        this.initPad('family');
      },
      initPad: function (name) {
        var canvas = this.$refs['pad_' + name];
        if (!canvas) { return; }
        this._pads = this._pads || {};
        var dpr = global.devicePixelRatio || 1;
        var rect = canvas.getBoundingClientRect();
        var w = Math.max(200, Math.round(rect.width || 320));
        var h = Math.max(100, Math.round(rect.height || 128));
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
        var ctx = canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);   /* 逻辑坐标统一为 CSS 像素 */
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, w, h);
        ctx.lineWidth = 2.4;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.strokeStyle = '#1c2430';
        this._pads[name] = { canvas: canvas, ctx: ctx, w: w, h: h, drawing: false, dirty: false, last: null };
      },
      padPos: function (name, e) {
        var pad = this._pads && this._pads[name];
        var rect = pad.canvas.getBoundingClientRect();
        var t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e;
        return {
          x: (t.clientX - rect.left) * (pad.w / (rect.width || pad.w)),
          y: (t.clientY - rect.top) * (pad.h / (rect.height || pad.h))
        };
      },
      padDown: function (name, e) {
        var pad = this._pads && this._pads[name];
        if (!pad || this.padConfirmed[name]) { return; }
        pad.drawing = true;
        var p = this.padPos(name, e);
        pad.last = p;
        pad.ctx.beginPath();
        pad.ctx.moveTo(p.x, p.y);
        pad.ctx.lineTo(p.x + 0.01, p.y);
        pad.ctx.stroke();
        pad.dirty = true;
      },
      padMove: function (name, e) {
        var pad = this._pads && this._pads[name];
        if (!pad || !pad.drawing || this.padConfirmed[name]) { return; }
        var p = this.padPos(name, e);
        if (pad.last) {
          pad.ctx.beginPath();
          pad.ctx.moveTo(pad.last.x, pad.last.y);
          pad.ctx.lineTo(p.x, p.y);
          pad.ctx.stroke();
        }
        pad.last = p;
        pad.dirty = true;
      },
      padUp: function (name) {
        var pad = this._pads && this._pads[name];
        if (pad) { pad.drawing = false; pad.last = null; }
      },
      clearPad: function (name) {
        var pad = this._pads && this._pads[name];
        if (!pad) { return; }
        pad.ctx.save();
        pad.ctx.setTransform(1, 0, 0, 1, 0, 0);
        pad.ctx.fillStyle = '#fff';
        pad.ctx.fillRect(0, 0, pad.canvas.width, pad.canvas.height);
        pad.ctx.restore();
        pad.dirty = false;
        this.padConfirmed[name] = false;
        this.padImages[name] = '';
      },
      confirmPad: function (name) {
        var pad = this._pads && this._pads[name];
        if (!pad) { return; }
        if (!pad.dirty) { toast('warning', '请先在签名板上手写签名'); return; }
        try {
          this.padImages[name] = pad.canvas.toDataURL('image/png');
          this.padConfirmed[name] = true;
        } catch (e) {
          toast('error', '签名导出失败');
        }
      },
      hasSign: function (name) {
        var r = this.signTarget;
        if (!r) { return false; }
        return !!(name === 'patient' ? r.patientSignatureBase64 : r.familySignatureBase64);
      },
      submitSign: function () {
        var vm = this;
        var r = vm.signTarget;
        if (!r) { return; }
        if (!vm.padConfirmed.patient && !vm.padConfirmed.family) {
          toast('warning', '请至少完成一位签名人的手写签名并点击「确认」');
          return;
        }
        if (vm.padConfirmed.family && !String(vm.signForm.signerName || '').trim()) {
          toast('warning', '家属代签时请填写签名人姓名'); return;
        }
        var signPayload = {
          patientSigBase64: vm.padConfirmed.patient ? vm.padImages.patient : null,
          familySigBase64: vm.padConfirmed.family ? vm.padImages.family : null,
          signerName: String(vm.signForm.signerName || '').trim() || null,
          signerRelation: vm.signForm.signerRelation || null
        };
        vm.saving = true;
        HIS.put('/api/his/inp/nursing/consent/' + HIS.idParam(r.id) + '/sign', signPayload).then(function () {
          vm.saving = false;
          vm.signVisible = false;
          vm.afterChanged('告知书已签署');
          /* P8b-2: 行数据携带关联 signatureId(如从病历签名链路来的)时, 患者/家属签名图同步落
           * his_emr_signature(patient_sign_image/family_sign_image); 独立告知书无 signatureId
           * → 跳过; 同步失败静默(try-catch), 不影响告知书签署主流程。 */
          var sigId = r.signatureId != null ? r.signatureId : (r.caSignatureId != null ? r.caSignatureId : null);
          if (sigId != null && HIS.post) {
            try {
              HIS.post('/api/emr/ca-sign/patient-sign/' + HIS.idParam(sigId), {
                patientImage: signPayload.patientSigBase64 || null,
                familyImage: signPayload.familySigBase64 || null
              }).catch(function () { /* 同步失败不影响主流程 */ });
            } catch (e2) { /* noop */ }
          }
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      },

      /* ---------- 查看/撤回 ---------- */
      openView: function (r) {
        this.viewTarget = r;
        this.viewVisible = true;
      },
      doRevoke: function (r) {
        var vm = this;
        confirmBox('撤回后该告知书将标记为「已撤回」, 确认撤回「' + (r.consentName || '') + '」?').then(function () {
          vm.saving = true;
          HIS.put('/api/his/inp/nursing/consent/' + HIS.idParam(r.id) + '/revoke', {}).then(function () {
            vm.saving = false;
            vm.afterChanged('告知书已撤回');
          }).catch(function (e) {
            vm.saving = false;
            HIS.notifyError(e);
          });
        }).catch(function () { /* 取消 */ });
      }
    },

    /* ================= 模板 ================= */
    template: [
      '<div class="nc-root">',
      /* ---- 顶栏 ---- */
      '  <div class="nc-bar">',
      '    <el-button type="primary" size="small" @click="openNew"><span class="nc-icon" v-html="icons.plus"></span> 新建告知书</el-button>',
      '    <span class="nc-spacer"></span>',
      '    <span v-if="loading" class="nc-loading">加载中…</span>',
      '    <el-button size="small" text @click="loadConsents" title="刷新"><span class="nc-icon" v-html="icons.refresh"></span></el-button>',
      '  </div>',
      /* ---- 列表 ---- */
      '  <div class="nc-list">',
      '    <div v-if="!hasVisit" class="nc-empty">',
      '      <span style="width:40px;height:40px;opacity:.5" v-html="icons.user"></span>',
      '      <div>请先在护士站患者列表选择患者</div>',
      '    </div>',
      '    <div v-else-if="loadFailed" class="nc-empty">',
      '      <div>告知书服务暂不可用</div>',
      '      <el-button size="small" @click="loadConsents">重试</el-button>',
      '    </div>',
      '    <div v-else-if="!consents.length && !loading" class="nc-empty">',
      '      <span style="width:36px;height:36px;opacity:.5" v-html="icons.file"></span>',
      '      <div>暂无告知书, 点击「新建告知书」开始</div>',
      '    </div>',
      '    <div v-for="r in consents" :key="r.id" class="nc-card" :class="statusCls(r.status)">',
      '      <div class="nc-card-head">',
      '        <span class="nc-card-title" :title="r.consentName">{{ r.consentName || typeLabel(r.consentType) }}</span>',
      '        <el-tag size="small" :type="statusTag(r.status)" disable-transitions>{{ statusLabel(r.status) }}</el-tag>',
      '      </div>',
      '      <div class="nc-card-line">',
      '        <span>{{ typeLabel(r.consentType) }}</span>',
      '        <span v-if="r.createTime">创建 {{ shortTime(r.createTime) }}</span>',
      '        <span v-if="Number(r.status) === 1 && r.signTime">签署 {{ shortTime(r.signTime) }}</span>',
      '        <span v-if="r.signerName">签名人 {{ r.signerName }}<template v-if="r.signerRelation">({{ r.signerRelation }})</template></span>',
      '      </div>',
      '      <div class="nc-card-actions">',
      '        <template v-if="Number(r.status) === 0">',
      '          <el-button size="small" @click="openView(r)">查看</el-button>',
      '          <el-button size="small" type="primary" @click="openSign(r)">签署</el-button>',
      '          <el-button size="small" type="danger" plain @click="doRevoke(r)">撤回</el-button>',
      '        </template>',
      '        <template v-else-if="Number(r.status) === 1">',
      '          <el-button size="small" @click="openView(r)">查看</el-button>',
      '          <el-button size="small" type="danger" plain @click="doRevoke(r)">撤回</el-button>',
      '        </template>',
      '        <template v-else>',
      '          <el-button size="small" @click="openView(r)">查看</el-button>',
      '        </template>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ---- 表单对话框(新建/编辑待签) ---- */
      '  <el-dialog v-model="formVisible" title="新建告知书" width="620px" append-to-body :close-on-click-modal="false">',
      '    <el-form label-width="86px">',
      '      <el-form-item label="告知类型" required>',
      '        <el-select v-model="form.consentType" style="width:100%" @change="onTypeChange">',
      '          <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="告知书名称" required>',
      '        <el-input v-model="form.consentName" maxlength="200"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="告知内容" required>',
      '        <el-input v-model="form.content" type="textarea" :rows="9" placeholder="告知内容(选择类型后自动套用参考模板, 可编辑)"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="见证人">',
      '        <el-input v-model="form.witnessName" maxlength="50" placeholder="见证人姓名(可选)"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="formVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitForm">创建(待签)</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 签署对话框 ---- */
      '  <el-dialog v-model="signVisible" title="告知书签署" width="720px" append-to-body :close-on-click-modal="false" @opened="onSignOpened">',
      '    <el-form label-width="72px">',
      '      <el-form-item label="告知内容">',
      '        <div class="nc-sign-content">{{ signContent || "(无内容)" }}</div>',
      '      </el-form-item>',
      '      <el-form-item label="签名人">',
      '        <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;width:100%">',
      '          <el-input v-model="signForm.signerName" style="width:180px" maxlength="50" placeholder="签名人姓名(家属代签必填)"></el-input>',
      '          <el-select v-model="signForm.signerRelation" style="width:130px">',
      '            <el-option v-for="rel in relations" :key="rel" :label="rel" :value="rel"></el-option>',
      '          </el-select>',
      '        </div>',
      '      </el-form-item>',
      '      <el-form-item label="手写签名">',
      '        <div class="nc-sign-grid" style="width:100%">',
      '          <div class="nc-sign-col">',
      '            <div class="nc-sign-col-title">患者签名',
      '              <span v-if="padConfirmed.patient" class="nc-sign-ok">✓ 已确认</span>',
      '            </div>',
      '            <div class="nc-pad-wrap" :class="{ \'is-confirmed\': padConfirmed.patient }">',
      '              <canvas ref="pad_patient" class="nc-pad"',
      '                @mousedown="padDown(\'patient\', $event)" @mousemove="padMove(\'patient\', $event)" @mouseup="padUp(\'patient\')" @mouseleave="padUp(\'patient\')"',
      '                @touchstart.prevent="padDown(\'patient\', $event)" @touchmove.prevent="padMove(\'patient\', $event)" @touchend="padUp(\'patient\')"></canvas>',
      '              <div class="nc-pad-hint">在此手写签名</div>',
      '            </div>',
      '            <div class="nc-pad-bar">',
      '              <el-button size="small" @click="clearPad(\'patient\')">清除</el-button>',
      '              <el-button size="small" type="primary" plain :disabled="padConfirmed.patient" @click="confirmPad(\'patient\')">确认</el-button>',
      '            </div>',
      '          </div>',
      '          <div class="nc-sign-col">',
      '            <div class="nc-sign-col-title">家属签名',
      '              <span v-if="padConfirmed.family" class="nc-sign-ok">✓ 已确认</span>',
      '            </div>',
      '            <div class="nc-pad-wrap" :class="{ \'is-confirmed\': padConfirmed.family }">',
      '              <canvas ref="pad_family" class="nc-pad"',
      '                @mousedown="padDown(\'family\', $event)" @mousemove="padMove(\'family\', $event)" @mouseup="padUp(\'family\')" @mouseleave="padUp(\'family\')"',
      '                @touchstart.prevent="padDown(\'family\', $event)" @touchmove.prevent="padMove(\'family\', $event)" @touchend="padUp(\'family\')"></canvas>',
      '              <div class="nc-pad-hint">在此手写签名</div>',
      '            </div>',
      '            <div class="nc-pad-bar">',
      '              <el-button size="small" @click="clearPad(\'family\')">清除</el-button>',
      '              <el-button size="small" type="primary" plain :disabled="padConfirmed.family" @click="confirmPad(\'family\')">确认</el-button>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="signVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitSign">确认签署</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 查看对话框 ---- */
      '  <el-dialog v-model="viewVisible" title="查看告知书" width="660px" append-to-body>',
      '    <template v-if="viewTarget">',
      '      <div class="nc-view-meta">',
      '        <span><b>{{ viewTarget.consentName || typeLabel(viewTarget.consentType) }}</b></span>',
      '        <el-tag size="small" :type="statusTag(viewTarget.status)" disable-transitions>{{ statusLabel(viewTarget.status) }}</el-tag>',
      '        <span v-if="Number(viewTarget.status) === 1 && viewTarget.signTime">签署时间 {{ shortTime(viewTarget.signTime) }}</span>',
      '        <span v-if="viewTarget.signerName">签名人 {{ viewTarget.signerName }}<template v-if="viewTarget.signerRelation">({{ viewTarget.signerRelation }})</template></span>',
      '        <span v-if="viewTarget.witnessName">见证人 {{ viewTarget.witnessName }}</span>',
      '      </div>',
      '      <div class="nc-view-content">{{ viewTarget.content || "(无内容)" }}</div>',
      '      <div class="nc-view-signs">',
      '        <div class="nc-view-sign-item">',
      '          <span>患者签名</span>',
      '          <img v-if="viewTarget.patientSignatureBase64" class="nc-sign-img" :src="viewTarget.patientSignatureBase64" alt="患者签名">',
      '          <div v-else class="nc-empty-sign">未采集</div>',
      '        </div>',
      '        <div class="nc-view-sign-item">',
      '          <span>家属签名</span>',
      '          <img v-if="viewTarget.familySignatureBase64" class="nc-sign-img" :src="viewTarget.familySignatureBase64" alt="家属签名">',
      '          <div v-else class="nc-empty-sign">未采集</div>',
      '        </div>',
      '      </div>',
      '    </template>',
      '    <template #footer>',
      '      <el-button @click="viewVisible = false">关闭</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.InpNursingConsent = InpNursingConsent;
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-nursing-consent', InpNursingConsent); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
