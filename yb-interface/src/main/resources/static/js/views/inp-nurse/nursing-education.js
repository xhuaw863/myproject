/* ==================================================================
 * nursing-education.js — 护理健康宣教组件(住院护士站 P4c-3)
 * ------------------------------------------------------------------
 * 定位: 病区护士对患者开展分类健康宣教(入院/疾病/用药/饮食/运动/
 *       出院等)并评价掌握效果的记录闭环; 右侧提供知识库分类浏览。
 * 版式: 顶栏(新增宣教记录 + 记录数 + 刷新) + 主体双栏
 *       (左 宣教记录卡片列表[分类/时间/方式/评价] /
 *        右 知识库分类浏览[点分类看要点说明 + 一键以此分类新增])
 *       + 新增对话框 + 查看对话框。
 * 依赖(index.html 先于本文件加载 api.js; 无构建、无 ES module):
 *   - HIS.request(HIS.get/post/del): R{code,msg,data} 信封, code=0 成功
 *   - HIS.id/idParam: 19位雪花ID全链路字符串化治理
 * 后端契约(P4c-1 宣教接口, 表 his_nursing_education):
 *   - GET    /api/his/inp/nursing/education/list?inpVisitId=   就诊宣教记录
 *   - POST   /api/his/inp/nursing/education                    新增记录
 *            body: {inpVisitId, patientId, knowledgeCategory, title, content,
 *                   educationMethod, evaluationResult, educationTime}
 *            (宣教人=当前登录职工由后端回填; 标题≤100字 内容≤5000字)
 *   - DELETE /api/his/inp/nursing/education/{id}               删除记录(逻辑删)
 *   - GET    /api/his/inp/nursing/education/knowledge-categories  知识分类库
 *            (返回 [{code,name,description,items[]}]; 失败回落本文件内置分类说明)
 *   枚举: knowledgeCategory=admission/disease/medication/diet/exercise/
 *         discharge/other; educationMethod=verbal/written/video/demo;
 *         evaluationResult=understood/partially/not_understood。
 *   载入失败优雅降级(显示"服务暂不可用"+重试), 不阻断页面其余功能。
 * 注册: HIS.components.InpNursingEducation + 全局标签 dw-inp-nursing-education 双保险。
 * ================================================================== */
;(function (global) {
  'use strict';

  var HIS = (global.HIS = global.HIS || {});
  HIS.components = HIS.components || {};

  /* ================= 样式(一次性注入, 全部 ne- 前缀) ================= */
  (function ensureStyles() {
    if (document.getElementById('inp-nursing-education-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-nursing-education-style';
    st.textContent = [
      '.ne-root { flex:1 1 auto; display:flex; flex-direction:column; min-height:480px; min-width:0; font-size:13px; color:var(--yb-ink-1,#1c2430); background:var(--yb-surface,#fff); border:1px solid var(--yb-border,#dfe4eb); border-radius:6px; overflow:hidden; }',
      /* ---- 顶栏 ---- */
      '.ne-bar { flex:none; display:flex; flex-wrap:wrap; align-items:center; gap:8px 10px; padding:8px 12px; border-bottom:1px solid var(--yb-border,#dfe4eb); }',
      '.ne-icon { display:inline-flex; width:13px; height:13px; vertical-align:-2px; }',
      '.ne-icon svg { width:100%; height:100%; }',
      '.ne-spacer { flex:1 1 auto; }',
      '.ne-loading { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      '.ne-count { font-size:12px; color:var(--yb-ink-4,#8994a5); }',
      /* ---- 主体双栏 ---- */
      '.ne-body { flex:1; display:flex; min-height:0; }',
      '.ne-left { flex:1; min-width:0; display:flex; flex-direction:column; min-height:0; }',
      '.ne-right { width:280px; flex:none; display:flex; flex-direction:column; min-height:0; border-left:1px solid var(--yb-border,#dfe4eb); background:var(--yb-surface-2,#f7f9fc); }',
      '.ne-sec-title { flex:none; padding:8px 12px 0; font-size:12px; font-weight:600; color:var(--yb-ink-2,#42506a); }',
      /* ---- 左侧记录列表 ---- */
      '.ne-list { flex:1; min-height:0; overflow-y:auto; padding:8px 12px 10px; display:flex; flex-direction:column; gap:8px; }',
      '.ne-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; padding:30px 12px; color:var(--yb-ink-4,#8994a5); font-size:13px; }',
      '.ne-card { border:1px solid var(--yb-border-light,#ebeff4); border-left-width:3px; border-radius:6px; padding:9px 11px; display:flex; flex-direction:column; gap:6px; }',
      '.ne-card.ev-ok { border-left-color:#3c9d3c; }',
      '.ne-card.ev-part { border-left-color:#e6a23c; }',
      '.ne-card.ev-bad { border-left-color:#e0493a; }',
      '.ne-card-head { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.ne-card-title { font-size:13px; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.ne-card-head .el-tag { margin-left:auto; }',
      '.ne-card-line { display:flex; flex-wrap:wrap; gap:4px 14px; font-size:12px; color:var(--yb-ink-3,#5a6a7e); }',
      '.ne-card-content { font-size:12px; color:var(--yb-ink-3,#5a6a7e); line-height:1.65; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }',
      '.ne-card-actions { display:flex; gap:6px; }',
      '.ne-card-actions .el-button + .el-button { margin-left:0; }',
      /* ---- 右侧知识库浏览 ---- */
      '.ne-kb { flex:1; min-height:0; overflow-y:auto; padding:8px 10px 10px; display:flex; flex-direction:column; gap:6px; }',
      '.ne-kb-item { display:flex; align-items:center; gap:8px; padding:7px 10px; border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; background:#fff; font-size:12.5px; cursor:pointer; transition:border-color .15s, background .15s; }',
      '.ne-kb-item:hover { border-color:var(--yb-brand,#1a5c9e); }',
      '.ne-kb-item.is-active { border-color:var(--yb-brand,#1a5c9e); background:rgba(26,92,158,.08); }',
      '.ne-kb-ico { display:inline-flex; flex:none; width:14px; height:14px; color:#c58f2c; }',
      '.ne-kb-ico svg { width:100%; height:100%; }',
      '.ne-kb-count { margin-left:auto; flex:none; font-size:11px; color:var(--yb-brand,#1a5c9e); background:rgba(26,92,158,.1); border-radius:8px; padding:0 6px; line-height:15px; }',
      '.ne-kb-detail { display:flex; flex-direction:column; gap:7px; padding:9px 10px; border:1px dashed var(--yb-border-strong,#ccd4de); border-radius:6px; background:#fff; font-size:12px; line-height:1.7; color:var(--yb-ink-3,#5a6a7e); }',
      '.ne-kb-detail b { font-size:12.5px; color:var(--yb-ink-1,#1c2430); }',
      '.ne-kb-detail .el-button { align-self:flex-start; }',
      '.ne-kb-items { display:flex; flex-wrap:wrap; gap:4px; }',
      '.ne-kb-item-tag { font-size:11px; color:var(--yb-ink-3,#5a6a7e); background:var(--yb-surface-2,#f7f9fc); border:1px solid var(--yb-border-light,#ebeff4); border-radius:4px; padding:1px 6px; }',
      /* ---- 查看对话框 ---- */
      '.ne-view-meta { display:flex; flex-wrap:wrap; gap:4px 16px; align-items:center; font-size:12px; color:var(--yb-ink-3,#5a6a7e); margin-bottom:8px; }',
      '.ne-view-meta.is-sub { margin-top:-2px; }',
      '.ne-view-content { white-space:pre-line; font-size:13px; line-height:1.85; color:var(--yb-ink-1,#1c2430); border:1px solid var(--yb-border-light,#ebeff4); border-radius:6px; background:var(--yb-surface-2,#f7f9fc); padding:10px 12px; max-height:300px; overflow-y:auto; }',
      /* ---- 窄屏回落 ---- */
      '@media (max-width: 1080px) { .ne-body { flex-direction:column; } .ne-right { width:auto; max-height:280px; border-left:none; border-top:1px solid var(--yb-border,#dfe4eb); } }',
      /* ---- 滚动条 ---- */
      '.ne-list::-webkit-scrollbar, .ne-kb::-webkit-scrollbar, .ne-view-content::-webkit-scrollbar { width:6px; }',
      '.ne-list::-webkit-scrollbar-thumb, .ne-kb::-webkit-scrollbar-thumb, .ne-view-content::-webkit-scrollbar-thumb { background:var(--yb-border-strong,#ccd4de); border-radius:3px; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ================= 常量 ================= */
  /* 知识库分类(与后端 knowledge_category 枚举一致; desc 用于右侧浏览说明) */
  var KNOWLEDGE_CATEGORIES = [
    { value: 'admission', label: '入院宣教', desc: '介绍病区环境、规章制度、责任医护、探视与作息安排，以及防跌倒、防压疮、财产安全等注意事项。' },
    { value: 'disease', label: '疾病知识', desc: '讲解疾病病因、临床表现、诊疗方案、并发症预防与自我监测要点，帮助患者正确认识病情、配合治疗。' },
    { value: 'medication', label: '用药指导', desc: '说明药物名称、作用、用法用量、服用时间(餐前/餐后/特殊时段)、常见不良反应及储存注意事项。' },
    { value: 'diet', label: '饮食指导', desc: '明确饮食类型(普食/流质/半流质/糖尿病饮食等)、宜忌食物、进食方式与营养搭配建议。' },
    { value: 'exercise', label: '活动指导', desc: '指导活动量、体位摆放、功能锻炼方法与康复训练时机，注意防跌倒、循序渐进。' },
    { value: 'discharge', label: '出院指导', desc: '交代出院后用药、复诊时间、饮食活动、伤口/管道护理、异常情况处理及紧急联系方式。' },
    { value: 'other', label: '其他', desc: '其他个性化健康宣教内容，如检查前后配合要点、心理疏导等。' }
  ];
  var CATEGORY_MAP = {};
  KNOWLEDGE_CATEGORIES.forEach(function (c) { CATEGORY_MAP[c.value] = c; });
  var METHODS = [
    { value: 'verbal', label: '口头讲解' },
    { value: 'written', label: '书面资料' },
    { value: 'video', label: '视频播放' },
    { value: 'demo', label: '操作演示' }
  ];
  var METHOD_MAP = {};
  METHODS.forEach(function (m) { METHOD_MAP[m.value] = m; });
  var EVALUATIONS = [
    { value: 'understood', label: '掌握', tag: 'success' },
    { value: 'partially', label: '部分掌握', tag: 'warning' },
    { value: 'not_understood', label: '未掌握', tag: 'danger' }
  ];
  var EVAL_MAP = {};
  EVALUATIONS.forEach(function (e) { EVAL_MAP[e.value] = e; });

  /* 内联 SVG 图标 */
  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }
  var ICONS = {
    plus: svg('<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>'),
    refresh: svg('<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>'),
    folder: svg('<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>'),
    file: svg('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>'),
    user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>')
  };

  /* ================= 工具 ================= */
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function nowStr() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
      + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }
  function shortTime(v) {
    var s = String(v == null ? '' : v).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  }
  function toast(type, text) {
    var EP = global.ElementPlus;
    if (EP && EP.ElMessage) { EP.ElMessage[type](text); }
    else { try { console.log('[ne:' + type + '] ' + text); } catch (e) { /* noop */ } }
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
  var InpNursingEducation = {
    name: 'InpNursingEducation',
    props: {
      inpVisitId: { type: [String, Number], default: null },
      patientId: { type: [String, Number], default: null }
    },
    data: function () {
      return {
        icons: ICONS,
        /* 左侧浏览: 6 大分类(不含 other); 对话框下拉: 全部 7 项 */
        kbCategories: KNOWLEDGE_CATEGORIES.filter(function (c) { return c.value !== 'other'; }),
        allCategories: KNOWLEDGE_CATEGORIES,
        methodOptions: METHODS,
        evalOptions: EVALUATIONS,
        records: [],
        loading: false,
        loadFailed: false,
        saving: false,
        activeCat: 'admission',
        kbItems: {},
        formVisible: false,
        form: { knowledgeCategory: 'admission', title: '', content: '', educationMethod: 'verbal', evaluationResult: 'understood', educationTime: '' },
        viewVisible: false,
        viewTarget: null
      };
    },
    computed: {
      hasVisit: function () {
        return this.inpVisitId != null && String(this.inpVisitId).length > 0;
      },
      activeCatInfo: function () {
        return CATEGORY_MAP[this.activeCat] || null;
      }
    },
    watch: {
      inpVisitId: function () { this.resetForVisit(); }
    },
    mounted: function () {
      this.loadKb();
      this.loadRecords();
    },
    methods: {
      /* ---------- 数据加载 ---------- */
      resetForVisit: function () {
        this.records = [];
        this.loadFailed = false;
        this.loadRecords();
      },
      loadRecords: function () {
        var vm = this;
        if (!vm.hasVisit || !HIS.get) { vm.records = []; return; }
        vm.loading = true;
        vm.loadFailed = false;
        HIS.get('/api/his/inp/nursing/education/list?inpVisitId=' + HIS.idParam(vm.inpVisitId))
          .then(function (rows) {
            var list = (Array.isArray(rows) ? rows : []).map(function (r) {
              return Object.assign({}, r, { id: HIS.id(r.id) });
            });
            /* 宣教时间倒序(缺失回落创建时间) */
            list.sort(function (a, b) {
              var ta = String(a.educationTime || a.createTime || '').replace('T', ' ');
              var tb = String(b.educationTime || b.createTime || '').replace('T', ' ');
              return ta < tb ? 1 : (ta > tb ? -1 : 0);
            });
            vm.records = list;
          })
          .catch(function () {
            vm.records = [];
            vm.loadFailed = true;
          })
          .finally(function () { vm.loading = false; });
      },
      /* 知识分类库(服务端补充分类条目提示; 失败静默回落内置说明) */
      loadKb: function () {
        var vm = this;
        if (!HIS.get) { return; }
        HIS.get('/api/his/inp/nursing/education/knowledge-categories').then(function (list) {
          (Array.isArray(list) ? list : []).forEach(function (c) {
            if (c && c.code && Array.isArray(c.items) && c.items.length) {
              vm.kbItems[c.code] = c.items;
            }
          });
        }).catch(function () { /* 静默回落内置 */ });
      },
      afterChanged: function (msg) {
        toast('success', msg);
        this.loadRecords();
      },

      /* ---------- 展示辅助 ---------- */
      catLabel: function (v) {
        var c = CATEGORY_MAP[v];
        return c ? c.label : (v || '宣教');
      },
      methodLabel: function (v) {
        var m = METHOD_MAP[v];
        return m ? m.label : (v || '-');
      },
      evalLabel: function (v) {
        var e = EVAL_MAP[v];
        return e ? e.label : '未评价';
      },
      evalTag: function (v) {
        var e = EVAL_MAP[v];
        return e ? e.tag : 'info';
      },
      evalCls: function (v) {
        if (v === 'understood') { return 'ev-ok'; }
        if (v === 'partially') { return 'ev-part'; }
        if (v === 'not_understood') { return 'ev-bad'; }
        return '';
      },
      recordCount: function (catValue) {
        return this.records.filter(function (r) { return r.knowledgeCategory === catValue; }).length;
      },
      shortTime: shortTime,

      /* ---------- 知识库浏览 ---------- */
      selectCat: function (v) { this.activeCat = v; },
      openNewFor: function (catValue) { this.openNew(catValue); },

      /* ---------- 新增 ---------- */
      openNew: function (catValue) {
        var vm = this;
        if (!vm.hasVisit) { toast('warning', '请先在护士站选择患者'); return; }
        var cat = CATEGORY_MAP[catValue] ? catValue : (vm.activeCat || 'admission');
        vm.form = {
          knowledgeCategory: cat,
          title: '',
          content: '',
          educationMethod: 'verbal',
          evaluationResult: 'understood',
          educationTime: nowStr()
        };
        vm.formVisible = true;
      },
      submitForm: function () {
        var vm = this;
        if (!String(vm.form.title || '').trim()) { toast('warning', '请填写宣教标题'); return; }
        if (!String(vm.form.content || '').trim()) { toast('warning', '请填写宣教内容'); return; }
        var payload = {
          inpVisitId: HIS.id(vm.inpVisitId),
          patientId: HIS.id(vm.patientId),
          knowledgeCategory: vm.form.knowledgeCategory,
          title: String(vm.form.title).trim(),
          content: vm.form.content,
          educationMethod: vm.form.educationMethod,
          evaluationResult: vm.form.evaluationResult,
          educationTime: vm.form.educationTime || nowStr()
        };
        vm.saving = true;
        HIS.post('/api/his/inp/nursing/education', payload).then(function () {
          vm.saving = false;
          vm.formVisible = false;
          vm.afterChanged('宣教记录已保存');
        }).catch(function (e) {
          vm.saving = false;
          HIS.notifyError(e);
        });
      },

      /* ---------- 查看/删除 ---------- */
      openView: function (r) {
        this.viewTarget = r;
        this.viewVisible = true;
      },
      doDelete: function (r) {
        var vm = this;
        confirmBox('删除后不可恢复, 确认删除「' + (r.title || vm.catLabel(r.knowledgeCategory)) + '」?').then(function () {
          vm.saving = true;
          HIS.del('/api/his/inp/nursing/education/' + HIS.idParam(r.id)).then(function () {
            vm.saving = false;
            vm.afterChanged('宣教记录已删除');
          }).catch(function (e) {
            vm.saving = false;
            HIS.notifyError(e);
          });
        }).catch(function () { /* 取消 */ });
      }
    },

    /* ================= 模板 ================= */
    template: [
      '<div class="ne-root">',
      /* ---- 顶栏 ---- */
      '  <div class="ne-bar">',
      '    <el-button type="primary" size="small" @click="openNew()"><span class="ne-icon" v-html="icons.plus"></span> 新增宣教记录</el-button>',
      '    <span class="ne-spacer"></span>',
      '    <span v-if="records.length" class="ne-count">共 {{ records.length }} 条</span>',
      '    <span v-if="loading" class="ne-loading">加载中…</span>',
      '    <el-button size="small" text @click="loadRecords" title="刷新"><span class="ne-icon" v-html="icons.refresh"></span></el-button>',
      '  </div>',
      /* ---- 主体双栏 ---- */
      '  <div class="ne-body">',
      /* 左侧: 宣教记录列表 */
      '    <div class="ne-left">',
      '      <div class="ne-sec-title">宣教记录</div>',
      '      <div class="ne-list">',
      '        <div v-if="!hasVisit" class="ne-empty">',
      '          <span style="width:40px;height:40px;opacity:.5" v-html="icons.user"></span>',
      '          <div>请先在护士站患者列表选择患者</div>',
      '        </div>',
      '        <div v-else-if="loadFailed" class="ne-empty">',
      '          <div>宣教服务暂不可用</div>',
      '          <el-button size="small" @click="loadRecords">重试</el-button>',
      '        </div>',
      '        <div v-else-if="!records.length && !loading" class="ne-empty">',
      '          <span style="width:36px;height:36px;opacity:.5" v-html="icons.file"></span>',
      '          <div>暂无宣教记录, 点击「新增宣教记录」开始</div>',
      '        </div>',
      '        <div v-for="r in records" :key="r.id" class="ne-card" :class="evalCls(r.evaluationResult)">',
      '          <div class="ne-card-head">',
      '            <span class="ne-card-title" :title="r.title">{{ r.title || catLabel(r.knowledgeCategory) }}</span>',
      '            <el-tag size="small" :type="evalTag(r.evaluationResult)" disable-transitions>{{ evalLabel(r.evaluationResult) }}</el-tag>',
      '          </div>',
      '          <div class="ne-card-line">',
      '            <span>{{ catLabel(r.knowledgeCategory) }}</span>',
      '            <span v-if="r.educationTime">{{ shortTime(r.educationTime) }}</span>',
      '            <span v-if="r.educationMethod">{{ methodLabel(r.educationMethod) }}</span>',
      '            <span v-if="r.educatorName">宣教人 {{ r.educatorName }}</span>',
      '          </div>',
      '          <div v-if="r.content" class="ne-card-content">{{ r.content }}</div>',
      '          <div class="ne-card-actions">',
      '            <el-button size="small" @click="openView(r)">查看</el-button>',
      '            <el-button size="small" type="danger" plain @click="doDelete(r)">删除</el-button>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      /* 右侧: 知识库分类浏览 */
      '    <div class="ne-right">',
      '      <div class="ne-sec-title">知识库浏览</div>',
      '      <div class="ne-kb">',
      '        <div v-for="c in kbCategories" :key="c.value" class="ne-kb-item" :class="{ \'is-active\': activeCat === c.value }" @click="selectCat(c.value)">',
      '          <span class="ne-kb-ico" v-html="icons.folder"></span>',
      '          <span>{{ c.label }}</span>',
      '          <span v-if="recordCount(c.value)" class="ne-kb-count">{{ recordCount(c.value) }}</span>',
      '        </div>',
      '        <div v-if="activeCatInfo" class="ne-kb-detail">',
      '          <b>{{ activeCatInfo.label }}</b>',
      '          <span>{{ activeCatInfo.desc }}</span>',
      '          <div v-if="kbItems[activeCatInfo.value] && kbItems[activeCatInfo.value].length" class="ne-kb-items">',
      '            <span v-for="(tag, i) in kbItems[activeCatInfo.value]" :key="i" class="ne-kb-item-tag">{{ tag }}</span>',
      '          </div>',
      '          <el-button size="small" type="primary" plain @click="openNewFor(activeCatInfo.value)">以此分类新增</el-button>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      /* ---- 新增对话框 ---- */
      '  <el-dialog v-model="formVisible" title="新增宣教记录" width="600px" append-to-body :close-on-click-modal="false">',
      '    <el-form label-width="86px">',
      '      <el-form-item label="知识分类" required>',
      '        <el-select v-model="form.knowledgeCategory" style="width:100%">',
      '          <el-option v-for="c in allCategories" :key="c.value" :label="c.label" :value="c.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="宣教标题" required>',
      '        <el-input v-model="form.title" maxlength="100" placeholder="如: 高血压患者的饮食注意事项"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="宣教内容" required>',
      '        <el-input v-model="form.content" type="textarea" :rows="5" maxlength="5000" placeholder="宣教要点内容"></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="宣教方式">',
      '        <el-radio-group v-model="form.educationMethod">',
      '          <el-radio-button v-for="m in methodOptions" :key="m.value" :label="m.value">{{ m.label }}</el-radio-button>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item label="效果评价">',
      '        <el-select v-model="form.evaluationResult" style="width:100%">',
      '          <el-option v-for="ev in evalOptions" :key="ev.value" :label="ev.label" :value="ev.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="宣教时间">',
      '        <el-date-picker v-model="form.educationTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="宣教时间" style="width:100%"></el-date-picker>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="formVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ---- 查看对话框 ---- */
      '  <el-dialog v-model="viewVisible" title="查看宣教记录" width="620px" append-to-body>',
      '    <template v-if="viewTarget">',
      '      <div class="ne-view-meta">',
      '        <span><b>{{ viewTarget.title || catLabel(viewTarget.knowledgeCategory) }}</b></span>',
      '        <el-tag size="small" :type="evalTag(viewTarget.evaluationResult)" disable-transitions>{{ evalLabel(viewTarget.evaluationResult) }}</el-tag>',
      '      </div>',
      '      <div class="ne-view-meta is-sub">',
      '        <span>分类 {{ catLabel(viewTarget.knowledgeCategory) }}</span>',
      '        <span v-if="viewTarget.educationMethod">方式 {{ methodLabel(viewTarget.educationMethod) }}</span>',
      '        <span v-if="viewTarget.educationTime">时间 {{ shortTime(viewTarget.educationTime) }}</span>',
      '        <span v-if="viewTarget.educatorName">宣教人 {{ viewTarget.educatorName }}</span>',
      '      </div>',
      '      <div class="ne-view-content">{{ viewTarget.content || "(无内容)" }}</div>',
      '    </template>',
      '    <template #footer>',
      '      <el-button @click="viewVisible = false">关闭</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 注册 ================= */
  HIS.components.InpNursingEducation = InpNursingEducation;
  function registerTag() {
    if (!(HIS.app && typeof HIS.app.component === 'function')) { return false; }
    try { HIS.app.component('dw-inp-nursing-education', InpNursingEducation); } catch (e) { /* 重复注册等场景忽略 */ }
    return true;
  }
  if (!registerTag()) {
    global.document.addEventListener('DOMContentLoaded', function () { registerTag(); });
  }
})(window);
