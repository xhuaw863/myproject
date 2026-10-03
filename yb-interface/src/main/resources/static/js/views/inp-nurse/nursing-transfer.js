/* ==================================================================
 * nursing-transfer.js — 护理转运交接单组件(住院护士站 P4c-3)
 * ------------------------------------------------------------------
 * 定位: 患者离开病区做转科/手术/血透/介入/内镜检查时的标准化交接:
 *       按转运类型加载核查清单(意识/瞳孔/生命体征/皮肤/管道/药物/病历…),
 *       交出方逐项核对并签名交接, 接收方到场确认; 全程留痕。
 * 版式: 顶栏(新建交接单 + 类型筛选 + 刷新) + 交接单卡片列表
 *       + 表单对话框(新建/编辑/查看三态合一; 核查清单 checkbox+值录入)。
 * 状态流: 0草稿(可编辑) → 1已交接(交出方签名) → 2已确认(接收方确认)。
 * 依赖(index.html 先于本文件加载 api.js; 无构建、无 ES module):
 *   - HIS.request(HIS.get/post/put): R{code,msg,data} 信封, code=0 成功
 *   - HIS.id/idKey/idParam/sameId: 19位雪花ID全链路字符串化治理
 * 后端契约(P4c-1 交接接口, 表 his_nursing_transfer):
 *   - GET  /api/his/inp/nursing/transfer/list?inpVisitId=            就诊交接单列表
 *   - GET  /api/his/inp/nursing/transfer/checklist-template?transferType=  核查清单模板
 *           (返回 [{item,checked:false}]; 失败回落本文件内置模板, 不阻断开单)
 *   - POST /api/his/inp/nursing/transfer                             新建草稿(创建即落库)
 *           body: {inpVisitId, patientId, transferType, checklist(JSON串),
 *                  fromDeptId, toDeptId, note}; 服务端无更新端点, 清单在新建时定稿
 *   - PUT  /api/his/inp/nursing/transfer/{id}/handover?receiverName= 交接签名(0→1, 接收人姓名必填)
 *   - PUT  /api/his/inp/nursing/transfer/{id}/confirm                接收确认(1→2)
 *   枚举: transferType=dept_transfer/surgery/hemodialysis/intervention/endoscopy;
 *         checklist=[{key,label,checked,value}] JSON 串;
 *         目标科室下拉复用 GET /api/his/dept/tree(失败时仅手填或留空)。
 * 注册: HIS.components.InpNursingTransfer + 全局标签 dw-inp-nursing-transfer 双保险。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 nt- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('inp-nursing-transfer-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-nursing-transfer-style';
    st.textContent = [
      '.nt-root { flex:1 1 auto; display:flex; flex-direction:column; min-height:420px; min-width:0; font-size:13px; color:var(--yb-ink-1,#1c2430); background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:hidden; }',
      /* ---- 顶栏 ---- */
      '.nt-bar { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:8px 10px; padding:8px 12px; border-bottom:1px solid var(--yb-border,#dfe4eb); }',
      '.nt-icon { display:inline-flex; width:13px; height:13px; vertical-align:-2px; }',
      '.nt-icon svg { width:100%; height:100%; }',
      '.nt-spacer { flex:1 1 auto; }',
      '.nt-loading { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      '.nt-sel-type { width:140px; }',
      /* ---- 列表 ---- */
      '.nt-list { flex:1; min-height:0; overflow-y:auto; padding:10px 12px; display:flex; flex-direction:column; gap:8px; }',
      '.nt-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; padding:30px 12px; color:var(--yb-ink-4,#8994a5); font-size:13px; }',
      '.nt-card { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; padding:9px 11px; display:flex; flex-direction:column; gap:6px; }',
      '.nt-card.st-draft { border-left:3px solid var(--yb-border-strong,#ccd4de); }',
      '.nt-card.st-handover { border-left:3px solid #e6a23c; }',
      '.nt-card.st-confirmed { border-left:3px solid #3c9d3c; }',
      '.nt-card-head { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.nt-card-title { font-size:13px; font-weight:600; }',
      '.nt-card-head .el-tag { margin-left:auto; }',
      '.nt-card-line { display:flex; flex-wrap:wrap; gap:4px 14px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.nt-card-note { font-size:12px; color:var(--yb-ink-4,#8994a5); line-height:1.6; word-break:break-all; }',
      '.nt-card-actions { display:flex; gap:6px; }',
      '.nt-card-actions .el-button + .el-button { margin-left:0; }',
      /* ---- 状态步骤条 ---- */
      '.nt-steps { display:flex; align-items:center; gap:0; font-size:11px; color:var(--yb-ink-4,#8994a5); }',
      '.nt-step { display:flex; align-items:center; gap:4px; }',
      '.nt-step-dot { width:8px; height:8px; border-radius:50%; background:var(--yb-border-strong,#ccd4de); }',
      '.nt-step.is-done .nt-step-dot { background:#3c9d3c; }',
      '.nt-step.is-cur .nt-step-dot { background:var(--yb-brand,#1a5c9e); box-shadow:0 0 0 3px rgba(26,92,158,.18); }',
      '.nt-step.is-done, .nt-step.is-cur { color:var(--yb-ink-2,#42506a); }',
      '.nt-step-line { width:22px; height:1px; background:var(--yb-border-strong,#ccd4de); margin:0 6px; }',
      /* ---- 对话框 ---- */
      '.nt-dlg-title2 { margin:-6px 0 10px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.nt-checklist { border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; overflow:hidden; }',
      '.nt-check-item { display:flex; align-items:center; gap:8px; padding:6px 10px; border-bottom:1px solid var(--yb-border-light,#ebeff4); }',
      '.nt-check-item:last-child { border-bottom:none; }',
      '.nt-check-item:nth-child(odd) { background:var(--yb-surface-2,#f7f9fc); }',
      '.nt-check-label { flex:none; width:92px; font-size:12px; color:var(--yb-ink-1,#1c2430); }',
      '.nt-check-input { flex:1; }',
      '.nt-check-tip { padding:6px 10px; font-size:12px; color:#B88230; background:rgba(230,162,60,.10); border-top:1px solid rgba(230,162,60,.3); }',
      '.nt-view-flag { display:inline-flex; align-items:center; gap:4px; font-size:12px; color:var(--yb-ink-4,#8994a5); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  var TRANSFER_TYPES = [
    { value: 'dept_transfer', label: '转科交接' },
    { value: 'surgery', label: '手术交接' },
    { value: 'hemodialysis', label: '血透交接' },
    { value: 'intervention', label: '介入交接' },
    { value: 'endoscopy', label: '内镜交接' }
  ];
  var TYPE_MAP = {};
  TRANSFER_TYPES.forEach(function (t) { TYPE_MAP[t.value] = t.label; });
  var STATUS_LABELS = { 0: '草稿', 1: '已交接', 2: '已确认' };
  var STATUS_TAGS = { 0: 'info', 1: 'warning', 2: 'success' };

  /* 内置核查清单模板(后端 checklist-template 不可用时的开箱兜底) */
  var DEFAULT_CHECKLISTS = {
    dept_transfer: [
      { key: 'consciousness', label: '意识状态', hint: '如: 清醒' },
      { key: 'pupil', label: '瞳孔', hint: '如: 等大等圆, 对光反射灵敏' },
      { key: 'vital_sign', label: '生命体征', hint: 'T36.5 P78 R18 BP120/80' },
      { key: 'skin', label: '皮肤', hint: '如: 完整, 无压红' },
      { key: 'pipe', label: '管道', hint: '如: 留置尿管1根, 通畅' },
      { key: 'drug', label: '药物/输液', hint: '如: 0.9%NS 500ml 静滴中' },
      { key: 'record', label: '病历资料', hint: '如: 齐全' },
      { key: 'belongings', label: '随身物品', hint: '如: 无 / 手机1部' }
    ],
    surgery: [
      { key: 'prep', label: '术前准备', hint: '备皮/禁食禁饮情况' },
      { key: 'wristband', label: '腕带核对', hint: '姓名/住院号/血型' },
      { key: 'allergy', label: '过敏史', hint: '无 / 具体药敏' },
      { key: 'skin', label: '皮肤完整性', hint: '如: 完整' },
      { key: 'record', label: '病历/影像资料', hint: '如: 随行' },
      { key: 'drug', label: '术前用药', hint: '如: 无' },
      { key: 'denture', label: '义齿/饰品', hint: '如: 已取下' }
    ],
    hemodialysis: [
      { key: 'access', label: '血管通路', hint: '内瘘 / 导管' },
      { key: 'dry_weight', label: '干体重', hint: 'kg' },
      { key: 'bp', label: '血压', hint: 'mmHg' },
      { key: 'drug', label: '抗凝用药', hint: '' },
      { key: 'record', label: '病历资料', hint: '如: 齐全' }
    ],
    intervention: [
      { key: 'prep', label: '术前准备', hint: '' },
      { key: 'allergy', label: '过敏史', hint: '碘造影剂等' },
      { key: 'access_site', label: '穿刺部位', hint: '如: 右侧股动脉' },
      { key: 'consent', label: '知情同意', hint: '如: 已签' }
    ],
    endoscopy: [
      { key: 'fasting', label: '禁食情况', hint: '如: 禁食8h' },
      { key: 'bowel_prep', label: '肠道准备', hint: '如: 已完成' },
      { key: 'anesthesia', label: '麻醉评估', hint: '如: 通过' },
      { key: 'consent', label: '知情同意', hint: '如: 已签' }
    ]
  };

  /* 内联 SVG 图标 */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    plus: svg('<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    transfer: svg('<polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/>'),
    clipboard: svg('<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>')
  };

  /* ================= 工具 ================= */
  function shortTime(v) {
    var s = String(v == null ? '' : v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }
  function toast(type, text) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessage) { EP.ElMessage[type](text); }
    else { try { console.log('[nt:' + type + '] ' + text); } catch (e) { /* noop */ } }
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
  function promptBox(text, title, placeholder) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessageBox && EP.ElMessageBox.prompt) {
      return EP.ElMessageBox.prompt(text, title || '输入', {
        confirmButtonText: '确认', cancelButtonText: '取消',
        inputPlaceholder: placeholder || '',
        inputValidator: function (v) { return String(v == null ? '' : v).trim() ? true : '请输入内容'; }
      });
    }
    return global.Promise ? global.Promise.reject(new Error('cancel')) : null;
  }
  function parseLoose(v) {
    if (v == null || typeof v === 'object') { return v; }
    try { return JSON.parse(v); } catch (e) { return null; }
  }

  /* ================= 组件 ================= */
  var InpNursingTransfer = {
    name: 'InpNursingTransfer',
    props: {
      inpVisitId: { type: [String, Number], default: null },
      patientId: { type: [String, Number], default: null }
    },
    data: function () {
      return {
        icons: ICONS,
        typeOptions: TRANSFER_TYPES,
        transfers: [],
        loading: false,
        loadFailed: false,
        saving: false,
        filterType: '',
        depts: [],
        deptRaw: [],
        /* 表单对话框 */
        dlgVisible: false,
        viewOnly: false,
        form: this.freshForm()
      };
    },
    computed: {
      hasVisit: function () {
        return this.inpVisitId != null && String(this.inpVisitId).length > 0;
      },
      filteredTransfers: function () {
        var t = this.filterType;
        if (!t) { return this.transfers || []; }
        return (this.transfers || []).filter(function (r) { return r.transferType === t; });
      },
      deptMap: function () {
        var map = {};
        (this.deptRaw || []).forEach(function (d) { map[HIS.idKey(d.id)] = d.deptName; });
        return map;
      },
      uncheckedCount: function () {
        return (this.form.items || []).filter(function (it) { return !it.checked; }).length;
      },
      dialogTitle: function () {
        return this.viewOnly ? '查看交接单' : '新建交接单';
      },
      /* 表单内类型是否为转科(高亮目标科室提示) */
      needTargetDept: function () {
        return ['dept_transfer', 'surgery', 'hemodialysis', 'intervention', 'endoscopy'].indexOf(this.form.transferType) >= 0;
      }
    },
    watch: {
      inpVisitId: function () { this.resetForVisit(); }
    },
    mounted: function () {
      this.loadDepts();
      this.loadTransfers();
    },
    methods: {
      freshForm: function () {
        return {
          transferType: 'dept_transfer',
          toDeptId: null,
          items: [],
          note: ''
        };
      },
      /* ---------- 数据加载 ---------- */
      resetForVisit: function () {
        this.transfers = [];
        this.loadFailed = false;
        this.loadTransfers();
      },
      loadTransfers: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.transfers = []; return; }
        vm.loading = true;
        vm.loadFailed = false;
        HIS.get('/api/his/inp/nursing/transfer/list?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .then(function (rows) {
            vm.transfers = (Array.isArray(rows) ? rows : []).map(function (r) {
              return Object.assign({}, r, { id: HIS.id(r.id) });
            });
          })
          .catch(function () {
            vm.transfers = [];
            vm.loadFailed = true;
          })
          .finally(function () { vm.loading = false; });
      },
      /* 目标科室下拉(dept/tree 展平; 失败静默, 仅影响下拉可选项) */
      loadDepts: function () {
        var vm = this;
        if (!HIS.get) { return; }
        HIS.get('/api/his/dept/tree').then(function (d) {
          var out = [], raw = [];
          (function walk(list, depth) {
            (list || []).forEach(function (n) {
              var pad = '';
              for (var i = 0; i < depth; i++) { pad += '　'; }
              out.push({ id: n.id, label: pad + (n.deptName || ('科室' + n.id)) });
              raw.push({ id: n.id, deptName: n.deptName });
              if (n.children && n.children.length) { walk(n.children, depth + 1); }
            });
          })(d || [], 0);
          vm.depts = out;
          vm.deptRaw = raw;
        }).catch(function () { vm.depts = []; vm.deptRaw = []; });
      },
      /* ---------- 展示辅助 ---------- */
      typeLabel: function (v) { return TYPE_MAP[v] || '交接单'; },
      statusLabel: function (v) { return STATUS_LABELS[Number(v) || 0] || '-'; },
      statusTag: function (v) { return STATUS_TAGS[Number(v) || 0] || 'info'; },
      statusCls: function (v) { return 'st-' + ['draft', 'handover', 'confirmed'][Number(v) || 0]; },
      deptName: function (id) {
        var k = HIS.idKey(id);
        return k ? (this.deptMap[k] || '') : '';
      },
      shortTime: shortTime,
      /* 交接流向文案: 转出科室 → 转入科室(名称未知时省略) */
      routeText: function (t) {
        var from = this.deptName(t.fromDeptId);
        var to = this.deptName(t.toDeptId);
        if (from && to) { return from + ' → ' + to; }
        if (to) { return '转入 ' + to; }
        if (from) { return from + ' 转出'; }
        return '';
      },
      noteSummaryOf: function (t) {
        var list = parseLoose(t.checklist);
        if (!Array.isArray(list) || !list.length) { return ''; }
        var checked = list.filter(function (it) { return it && it.checked; }).length;
        return '核查项 ' + checked + '/' + list.length + ' 项';
      },
      /* 步骤条状态: 0=草稿 1=已交接 2=已确认 */
      stepCls: function (cur, idx) {
        if (idx < cur) { return 'is-done'; }
        if (idx === cur) { return 'is-cur'; }
        return '';
      },

      /* ---------- 核查清单 ---------- */
      /* 归一化服务端/内置模板 → [{key,label,hint,checked:false,value:''}] */
      normalizeTemplate: function (list) {
        var out = [];
        (Array.isArray(list) ? list : []).forEach(function (it, i) {
          if (it == null) { return; }
          var o = (typeof it === 'string') ? { label: it } : it;
          out.push({
            key: String(o.key || ('item_' + i)),
            label: String(o.label || o.item || o.name || ('核查项' + (i + 1))),
            hint: String(o.hint || o.placeholder || ''),
            checked: !!o.checked,
            value: String(o.value || '')
          });
        });
        return out;
      },
      loadTemplate: function (type, keepValues) {
        var vm = this;
        var prev = {};
        if (keepValues) {
          (vm.form.items || []).forEach(function (it) { prev[it.label] = it; });
        }
        var apply = function (list, fromServer) {
          var items = vm.normalizeTemplate(list);
          if (!items.length) {
            items = vm.normalizeTemplate(DEFAULT_CHECKLISTS[type] || DEFAULT_CHECKLISTS.dept_transfer);
            fromServer = false;
          }
          /* 类型切换保留已填项(按 key 对齐) */
          items.forEach(function (it) {
            var old = prev[it.label];
            if (old) { it.value = old.value || ''; it.checked = !!old.checked; }
          });
          vm.form.items = items;
        };
        if (HIS.get) {
          HIS.get('/api/his/inp/nursing/transfer/checklist-template?transferType=' + encodeURIComponent(type))
            .then(function (list) {
              apply(list, true);
            })
            .catch(function () {
              apply(DEFAULT_CHECKLISTS[type] || DEFAULT_CHECKLISTS.dept_transfer, false);
            });
        } else {
          apply(DEFAULT_CHECKLISTS[type] || DEFAULT_CHECKLISTS.dept_transfer, false);
        }
      },
      onTypeChange: function () {
        this.loadTemplate(this.form.transferType, true);
      },
      /* 清单勾选状态样式(全部核对完成提示) */
      checkTip: function () {
        var n = this.uncheckedCount;
        return n > 0 ? ('还有 ' + n + ' 项未核对, 完成全部核对后方可交接') : '全部核查项已核对, 可以交接';
      },

      /* ---------- 新建/查看 ---------- */
      openNew: function () {
        var vm = this;
        if (!vm.hasVisit) { toast('warning', '请先在护士站选择患者'); return; }
        vm.viewOnly = false;
        vm.form = vm.freshForm();
        vm.form.items = vm.normalizeTemplate(DEFAULT_CHECKLISTS.dept_transfer);
        vm.dlgVisible = true;
        vm.loadTemplate('dept_transfer', false);
      },
      openView: function (t) {
        var vm = this;
        vm.viewOnly = true;
        vm.form = {
          transferType: t.transferType || 'dept_transfer',
          toDeptId: HIS.idKey(t.toDeptId) || null,
          items: vm.normalizeTemplate(parseLoose(t.checklist) || DEFAULT_CHECKLISTS[t.transferType] || []),
          note: t.note || ''
        };
        vm.dlgVisible = true;
      },
      buildChecklist: function () {
        return JSON.stringify((this.form.items || []).map(function (it) {
          return { key: it.key, label: it.label, checked: !!it.checked, value: String(it.value || '') };
        }));
      },
      /* ---------- 保存/流转 ---------- */
      saveDraft: function () {
        var vm = this;
        if (!vm.form.transferType) { toast('warning', '请选择交接类型'); return; }
        var payload = {
          inpVisitId: HIS.id(vm.inpVisitId),
          patientId: HIS.id(vm.patientId),
          transferType: vm.form.transferType,
          checklist: vm.buildChecklist(),
          toDeptId: vm.form.toDeptId ? HIS.id(vm.form.toDeptId) : null,
          note: String(vm.form.note || '').trim() || null
        };
        vm.saving = true;
        HIS.post('/api/his/inp/nursing/transfer', payload).then(function () {
          vm.saving = false;
          vm.dlgVisible = false;
          toast('success', '交接单草稿已保存');
          vm.loadTransfers();
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      },
      /* 交出方签名交接: 服务端要求填接收人姓名, 清单须全部核对完成 */
      doHandover: function (t) {
        var vm = this;
        var unchecked = 0;
        var list = parseLoose(t.checklist) || [];
        list.forEach(function (it) { if (it && !it.checked) { unchecked += 1; } });
        if (!list.length) { toast('warning', '交接单无核查项, 内容异常'); return; }
        if (unchecked > 0) { toast('warning', '还有 ' + unchecked + ' 项未核对, 无法交接'); return; }
        promptBox('「' + (TYPE_MAP[t.transferType] || '交接单') + '」核查完成, 请填写接收人姓名并确认交接签名', '交接签名', '接收人姓名').then(function (r) {
          var receiverName = String((r && r.value) || '').trim();
          vm.saving = true;
          HIS.put('/api/his/inp/nursing/transfer/' + HIS.idParam(t.id) + '/handover?receiverName=' + encodeURIComponent(receiverName)).then(function () {
            vm.saving = false;
            toast('success', '交接签名已提交, 等待接收方确认');
            vm.loadTransfers();
          }).catch(function (e) {
            vm.saving = false;
            HIS.notifyError(e);
          });
        }).catch(function () { /* 取消 */ });
      },
      doConfirm: function (t) {
        var vm = this;
        confirmBox('确认接收该患者并完成交接单确认?').then(function () {
          vm.saving = true;
          HIS.put('/api/his/inp/nursing/transfer/' + HIS.idParam(t.id) + '/confirm', {}).then(function () {
            vm.saving = false;
            toast('success', '交接单已确认');
            vm.loadTransfers();
          }).catch(function (e) {
            vm.saving = false;
            HIS.notifyError(e);
          });
        }).catch(function () { /* 取消 */ });
      },
      /* 对话框保存按钮: 查看态不显示; 新建态存草稿 */
      onDlgSave: function () { this.saveDraft(); }
    },

    /* ================= 模板 ================= */
    template: [
      '<div class="nt-root">',
      /* ---- 顶栏 ---- */
      '  <div class="nt-bar">',
      '    <el-button type="primary" size="small" @click="openNew"><span class="nt-icon" v-html="icons.plus"></span> 新建交接单</el-button>',
      '    <el-select v-model="filterType" class="nt-sel-type" size="small" clearable placeholder="全部类型">',
      '      <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value"></el-option>',
      '    </el-select>',
      '    <span class="nt-spacer"></span>',
      '    <span v-if="loading" class="nt-loading">加载中…</span>',
      '    <el-button size="small" text @click="loadTransfers" title="刷新"><span class="nt-icon" v-html="icons.refresh"></span></el-button>',
      '  </div>',
      /* ---- 列表 ---- */
      '  <div class="nt-list">',
      '    <div v-if="!hasVisit" class="nt-empty">',
      '      <span style="width:40px;height:40px;opacity:.5" v-html="icons.user"></span>',
      '      <div>请先在护士站患者列表选择患者</div>',
      '    </div>',
      '    <div v-else-if="loadFailed" class="nt-empty">',
      '      <div>交接单服务暂不可用</div>',
      '      <el-button size="small" @click="loadTransfers">重试</el-button>',
      '    </div>',
      '    <div v-else-if="!filteredTransfers.length && !loading" class="nt-empty">',
      '      <span style="width:36px;height:36px;opacity:.5" v-html="icons.transfer"></span>',
      '      <div>暂无交接单, 点击「新建交接单」开始</div>',
      '    </div>',
      '    <div v-for="t in filteredTransfers" :key="t.id" class="nt-card" :class="statusCls(t.status)">',
      '      <div class="nt-card-head">',
      '        <el-tag size="small" disable-transitions>{{ typeLabel(t.transferType) }}</el-tag>',
      '        <span class="nt-card-title">{{ shortTime(t.createTime) || "—" }}</span>',
      '        <el-tag size="small" :type="statusTag(t.status)" disable-transitions>{{ statusLabel(t.status) }}</el-tag>',
      '      </div>',
      '      <div class="nt-steps">',
      '        <span class="nt-step" :class="stepCls(Number(t.status) || 0, 0)"><span class="nt-step-dot"></span>草稿</span>',
      '        <span class="nt-step-line"></span>',
      '        <span class="nt-step" :class="stepCls(Number(t.status) || 0, 1)"><span class="nt-step-dot"></span>已交接</span>',
      '        <span class="nt-step-line"></span>',
      '        <span class="nt-step" :class="stepCls(Number(t.status) || 0, 2)"><span class="nt-step-dot"></span>已确认</span>',
      '      </div>',
      '      <div class="nt-card-line">',
      '        <span v-if="routeText(t)">{{ routeText(t) }}</span>',
      '        <span>{{ t.senderName || "待交出" }} → {{ t.receiverName || "待接收" }}</span>',
      '        <span v-if="t.handoverTime">交接 {{ shortTime(t.handoverTime) }}</span>',
      '      </div>',
      '      <div v-if="noteSummaryOf(t) || t.note" class="nt-card-note">{{ noteSummaryOf(t) }}{{ noteSummaryOf(t) && t.note ? " · " : "" }}{{ t.note || "" }}</div>',
      '      <div class="nt-card-actions">',
      '        <template v-if="Number(t.status) === 0">',
      '          <el-button size="small" @click="openView(t)">查看</el-button>',
      '          <el-button size="small" type="primary" @click="doHandover(t)">交接</el-button>',
      '        </template>',
      '        <template v-else-if="Number(t.status) === 1">',
      '          <el-button size="small" @click="openView(t)">查看</el-button>',
      '          <el-button size="small" type="success" @click="doConfirm(t)">确认接收</el-button>',
      '        </template>',
      '        <template v-else>',
      '          <el-button size="small" @click="openView(t)">查看</el-button>',
      '        </template>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ---- 表单对话框(新建/查看) ---- */
      '  <el-dialog v-model="dlgVisible" :title="dialogTitle" width="620px" append-to-body :close-on-click-modal="false">',
      '    <div class="nt-dlg-title2">',
      '      <span class="nt-view-flag" v-if="viewOnly">只读查看</span>',
      '    </div>',
      '    <el-form label-width="86px">',
      '      <el-form-item label="交接类型" required>',
      '        <el-select v-model="form.transferType" style="width:100%" :disabled="viewOnly" @change="onTypeChange">',
      '          <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="目标科室">',
      '        <el-select v-model="form.toDeptId" style="width:100%" clearable filterable placeholder="选择转入科室(可选)" :disabled="viewOnly">',
      '          <el-option v-for="d in depts" :key="d.id" :label="d.label" :value="d.id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="核查清单">',
      '        <div style="width:100%">',
      '          <div class="nt-checklist">',
      '            <div v-for="it in form.items" :key="it.key" class="nt-check-item">',
      '              <el-checkbox v-model="it.checked" :disabled="viewOnly"></el-checkbox>',
      '              <span class="nt-check-label">{{ it.label }}</span>',
      '              <el-input v-model="it.value" class="nt-check-input" size="small" :disabled="viewOnly" :placeholder="it.hint || \'核对情况\'"></el-input>',
      '            </div>',
      '            <div v-if="!form.items.length" class="nt-check-tip">核查清单加载中…</div>',
      '          </div>',
      '          <div v-if="!viewOnly && form.items.length" class="nt-check-tip">{{ checkTip() }}</div>',
      '        </div>',
      '      </el-form-item>',
      '      <el-form-item label="备注">',
      '        <el-input v-model="form.note" type="textarea" :rows="2" maxlength="500" :disabled="viewOnly" placeholder="交接补充说明(可选)"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dlgVisible = false">{{ viewOnly ? "关闭" : "取消" }}</el-button>',
      '      <el-button v-if="!viewOnly" type="primary" :loading="saving" @click="onDlgSave">保存草稿</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.InpNursingTransfer = InpNursingTransfer;
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-nursing-transfer', InpNursingTransfer); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
