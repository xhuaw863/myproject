/* PDA扫码工作台(T48 P3 UI模拟): 模拟手持终端扫码腕带 → 患者识别 → 待执行医嘱床旁执行。
 * 无构建架构: 组件注册到 HIS.views['pda-simulation'](与 RbacInitializer 菜单 comp 同值)。
 * 数据流:
 *   扫码/输入   GET /api/his/inp/patients?keyword=&visitStatus=2 (机构隔离, 免管床关系, keyword 匹配住院号/姓名/身份证/拼音)
 *   患者详情    GET /api/his/inp/visit/{id} (诊断列表) + GET /api/his/inp/allergy/list?visitId= (过敏横幅)
 *   待执行医嘱  GET /api/his/inp/order-exec/plan?wardId=&page&size (按病区整日计划, 前端按 inpVisitId 过滤 execStatus=1)
 *   确认执行    POST /api/his/inp/order-exec/execute body [execId,...] (需双人核对时返回 {needDoubleCheck:true}, PDA 端拦截提示)
 * 条形码: 纯 JS 实现 Code128B 编码(107 模式表), 渲染为内联 SVG; 非法字符回落等宽竖线模拟。
 * 视觉: 临床工业终端风 —— 深色扫码头 + 红色激光扫描线动画 + 等宽数字 + 腕带仿真预览。
 */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== Code128B 编码表(0-102 数据/起始, 103-105 START A/B/C, 106 STOP;
   * 每模式 6 位数字(STOP 7 位), 交替表示条/空模块宽) ===== */
  const CODE128_PATTERNS = [
    '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212',
    '221213', '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221',
    '223211', '221132', '221231', '213212', '223112', '312131', '311222', '321122', '321221',
    '312212', '322112', '322211', '212123', '212321', '232121', '111323', '131123', '131321',
    '112313', '132113', '132311', '211313', '231113', '231311', '112133', '112331', '132131',
    '113123', '113321', '133121', '313121', '211331', '231131', '213113', '213311', '213131',
    '311123', '311321', '331121', '312113', '312311', '332111', '314111', '221411', '431111',
    '111224', '111422', '121124', '121421', '141122', '141221', '112214', '112412', '122114',
    '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111', '111242',
    '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
    '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311',
    '113141', '114131', '311141', '411131',
    '211412', '211214', '211232',
    '2331112'
  ];

  /** Code128B 值序列: START B(104) + (ASCII-32)×n + 校验((104+Σvi×i)%103) + STOP(106)。 */
  function code128Values(text) {
    const s = String(text || '');
    const vals = [104];
    let sum = 104;
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c < 32 || c > 126) { return null; }
      const v = c - 32;
      vals.push(v);
      sum += v * (i + 1);
    }
    vals.push(sum % 103);
    vals.push(106);
    return vals;
  }

  /** 渲染 Code128 SVG(条为黑 rect, 空为透明); 非法字符返回空串由调用方回落。 */
  function code128Svg(text, height, module) {
    const vals = code128Values(text);
    if (!vals || !vals.length) { return ''; }
    const m = module || 2;
    const h = height || 44;
    const segs = vals.map(v => CODE128_PATTERNS[v].split('').map(Number));
    let total = 0;
    segs.forEach(p => p.forEach(w => { total += w; }));
    let x = 0;
    let rects = '';
    segs.forEach(p => {
      p.forEach((w, i) => {
        const px = w * m;
        if (i % 2 === 0) { rects += '<rect x="' + x + '" y="0" width="' + px + '" height="' + h + '"></rect>'; }
        x += px;
      });
    });
    return '<svg class="pda-bc-svg" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + (total * m) + ' ' + h +
      '" width="100%" height="' + h + '" preserveAspectRatio="none" shape-rendering="crispEdges" fill="currentColor" aria-label="Code128 ' + String(text).replace(/"/g, '&quot;') + '">' + rects + '</svg>';
  }

  /** 兜底条形码(住院号含 Code B 不可编码字符时): 等宽字体竖线交替的装饰性模拟。 */
  function fallbackBars(text) {
    const s = String(text || '');
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      out += (c % 2 === 0) ? '&#124;&#124;&nbsp;' : '&#124;&nbsp;&nbsp;';
    }
    return out;
  }

  /* ===== 常量(与后端 InpOrderExecService 枚举一致) ===== */
  const ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  const CATEGORY_TAG = { 1: 'primary', 2: 'info', 3: 'warning', 4: 'success', 5: '', 6: 'info', 7: 'info' };
  const EXEC_STATUS = { 1: '待执行', 2: '已执行', 3: '未执行' };
  const EXEC_STATUS_TAG = { 1: 'warning', 2: 'success', 3: 'danger' };

  /* ===== 私有样式一次性注入(不触碰公共 css, 与其他会话并行开发互不干扰) ===== */
  (function ensurePdaStyles() {
    if (document.getElementById('pda-simulation-style')) { return; }
    const st = document.createElement('style');
    st.id = 'pda-simulation-style';
    st.textContent = [
      /* 页面骨架: 扫码头(工业深色) + 主体两栏 */
      '.pda-wrap { height:100%; display:flex; flex-direction:column; min-height:0; background:var(--yb-canvas); }',
      /* ---- 扫码头: 深色渐变 + 终端等宽字标 + 大号扫码框 ---- */
      '.pda-scanbar { flex:none; padding:16px 20px 14px; background:var(--yb-header-grad); position:relative; overflow:hidden; }',
      '.pda-scanbar .hd { display:flex; align-items:center; gap:10px; margin-bottom:12px; }',
      '.pda-scanbar .hd .led { width:9px; height:9px; border-radius:50%; background:#5ee08a; box-shadow:0 0 8px rgba(94,224,138,.9); animation:pda-led 2.4s ease-in-out infinite; }',
      '@keyframes pda-led { 0%,100% { opacity:1; } 50% { opacity:.25; } }',
      '.pda-scanbar .hd .tt { color:var(--yb-header-ink); font-size:var(--yb-fs-lg); font-weight:700; letter-spacing:.02em; }',
      '.pda-scanbar .hd .sub { color:var(--yb-header-ink-2); font-size:var(--yb-fs-sm); font-family:var(--yb-font-mono); }',
      '.pda-scanbar .hd .spacer { flex:1; }',
      '.pda-scanbar .hd .who { color:var(--yb-header-ink-2); font-size:var(--yb-fs-cap); background:var(--yb-header-chip); padding:2px 10px; border-radius:var(--yb-r-pill); }',
      '.pda-scanrow { display:flex; gap:10px; max-width:760px; }',
      '.pda-scanrow .el-input { flex:1; }',
      '.pda-scanrow .el-input .el-input__wrapper { background:rgba(255,255,255,.96); box-shadow:0 0 0 1px rgba(255,255,255,.35), var(--yb-sh-2); }',
      '.pda-scanrow .el-input .el-input__inner { font-family:var(--yb-font-mono); font-size:16px; letter-spacing:.06em; height:22px; }',
      '.pda-scanrow .el-input.is-focus .el-input__wrapper { box-shadow:0 0 0 2px #fff; }',
      '.pda-scanbtn { flex:none; font-weight:700; letter-spacing:.15em; }',
      /* 激光扫描线: 扫码瞬间红色激光横扫输入框(工业扫码枪意象) */
      '.pda-laser { position:absolute; left:0; right:0; top:50%; height:2px; pointer-events:none;',
      ' background:linear-gradient(90deg, transparent, #ff4444 18%, #ff5c5c 50%, #ff4444 82%, transparent);',
      ' filter:drop-shadow(0 0 6px rgba(255,68,68,.8)); animation:pda-laser-sweep .55s linear forwards; z-index:5; }',
      '@keyframes pda-laser-sweep { 0% { transform:translateX(-100%); opacity:0; } 12% { opacity:1; } 88% { opacity:1; } 100% { transform:translateX(100%); opacity:0; } }',
      /* 扫码成功绿闪(嘀—— 识别成功反馈) */
      '.pda-beep { position:absolute; inset:0; pointer-events:none; z-index:6;',
      ' background:radial-gradient(ellipse at center, rgba(94,224,138,.22), transparent 65%);',
      ' animation:pda-beep-flash .5s ease-out forwards; }',
      '@keyframes pda-beep-flash { 0% { opacity:1; } 100% { opacity:0; } }',
      /* 演示区: 最近入院患者"迷你腕带", 点击=模拟扫该患者腕带 */
      '.pda-demo { margin-top:12px; display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.pda-demo .lbl { color:var(--yb-header-ink-2); font-size:var(--yb-fs-cap); flex:none; }',
      '.pda-demo-card { display:flex; align-items:center; gap:8px; padding:4px 10px; cursor:pointer;',
      ' background:var(--yb-header-chip); border:1px solid rgba(255,255,255,.18); border-radius:6px; color:var(--yb-header-ink);',
      ' font-size:var(--yb-fs-sm); transition:background var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }',
      '.pda-demo-card:hover { background:var(--yb-header-hover); transform:translateY(-1px); }',
      '.pda-demo-card .bd { font-family:var(--yb-font-mono); font-weight:700; color:#ffd98a; font-variant-numeric:tabular-nums; }',
      '.pda-demo-card .nm { font-weight:600; }',
      /* ---- 主体两栏: 左患者+腕带 / 右医嘱 ---- */
      '.pda-main { flex:1; min-height:0; display:flex; gap:14px; padding:14px 16px; overflow:auto; }',
      '.pda-left { width:380px; flex:none; display:flex; flex-direction:column; gap:12px; }',
      '.pda-card { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); }',
      '.pda-card .cap { display:flex; align-items:center; gap:8px; padding:10px 14px; border-bottom:1px solid var(--yb-divider);',
      ' color:var(--yb-ink-2); font-weight:700; font-size:var(--yb-fs-base); }',
      '.pda-card .cap .dot { width:8px; height:8px; border-radius:2px; background:var(--yb-brand); }',
      '.pda-card .bd { padding:12px 14px; }',
      /* 患者字段网格 */
      '.pda-grid { display:grid; grid-template-columns:1fr 1fr; gap:9px 14px; }',
      '.pda-grid .f { min-width:0; }',
      '.pda-grid .f .k { color:var(--yb-ink-3); font-size:var(--yb-fs-cap); margin-bottom:2px; }',
      '.pda-grid .f .v { color:var(--yb-ink-1); font-size:var(--yb-fs-md); font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.pda-grid .f .v.mono { font-family:var(--yb-font-mono); letter-spacing:.05em; font-variant-numeric:tabular-nums; }',
      '.pda-grid .f.wide { grid-column:1 / -1; }',
      '.pda-allergy { margin-top:10px; padding:6px 10px; border-radius:var(--yb-r-sm); font-size:var(--yb-fs-sm); font-weight:600;',
      ' background:var(--yb-danger-bg); border:1px solid var(--yb-danger-border); color:var(--yb-danger-strong); }',
      '.pda-allergy.is-safe { background:var(--yb-success-bg); border-color:var(--yb-success-border); color:var(--yb-success-strong); }',
      /* ---- 腕带仿真预览: 白底黑码, 25mm 高比例横带 ---- */
      '.pda-band { display:flex; align-items:center; gap:0; background:#fff; border:1px solid #111;',
      ' border-radius:3px; overflow:hidden; min-height:74px; }',
      '.pda-band.is-print { box-shadow:var(--yb-sh-1); }',
      '.pda-band .warn { flex:none; background:#000; color:#fff; font-weight:700; font-size:11px;',
      ' padding:6px 8px; align-self:stretch; display:flex; align-items:center; justify-content:center; text-align:center; line-height:1.5; }',
      '.pda-band .info { flex:1; min-width:0; padding:8px 12px; }',
      '.pda-band .info .nm { font-size:19px; font-weight:800; color:#000; letter-spacing:1px; line-height:1.2; }',
      '.pda-band .info .meta { font-size:11px; color:#000; margin-top:3px; line-height:1.6; font-family:var(--yb-font-mono); }',
      '.pda-band .bc { flex:0 0 150px; padding:6px 10px 4px; text-align:center; color:#000; }',
      '.pda-band .bc .pda-bc-svg { display:block; }',
      '.pda-band .bc .no { font-family:var(--yb-font-mono); font-size:11px; font-weight:700; letter-spacing:2px; margin-top:2px; color:#000; font-variant-numeric:tabular-nums; }',
      '.pda-band .bc .fb { font-family:var(--yb-font-mono); font-size:20px; font-weight:700; letter-spacing:1px; line-height:1; overflow:hidden; white-space:nowrap; }',
      '.pda-band-cap { display:flex; align-items:center; gap:8px; }',
      '.pda-band-cap .hint { margin-left:auto; color:var(--yb-ink-4); font-size:var(--yb-fs-cap); font-weight:400; }',
      /* ---- 医嘱面板 ---- */
      '.pda-orders { flex:1; min-width:0; display:flex; flex-direction:column; background:var(--yb-surface);',
      ' border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); box-shadow:var(--yb-sh-1); overflow:hidden; }',
      '.pda-orders .cap { flex:none; display:flex; align-items:center; gap:10px; padding:10px 14px; border-bottom:1px solid var(--yb-divider); }',
      '.pda-orders .cap .t { color:var(--yb-ink-2); font-weight:700; font-size:var(--yb-fs-base); }',
      '.pda-orders .cap .cnt { color:var(--yb-brand-strong); font-variant-numeric:tabular-nums; }',
      '.pda-orders .cap .grow { flex:1; }',
      '.pda-orders .scroll { flex:1; min-height:0; overflow:auto; }',
      '.pda-orders .foot { flex:none; display:flex; align-items:center; gap:10px; padding:10px 14px; border-top:1px solid var(--yb-divider); background:var(--yb-surface-2); }',
      '.pda-orders .foot .tip { color:var(--yb-ink-4); font-size:var(--yb-fs-cap); }',
      '.pda-orders .foot .grow { flex:1; }',
      '.pda-flag { margin-left:6px; }',
      '.pda-flag.is-danger { color:var(--yb-danger-strong); font-weight:700; }',
      '.pda-flag.is-warn { color:var(--yb-warning-strong); font-weight:700; }',
      /* 超时候补视觉: 计划时间已过仍待执行的行, 时间列红色加粗 */
      '.pda-orders .is-overdue { color:var(--yb-danger-strong); font-weight:700; }',
      /* ---- 空态引导 ---- */
      '.pda-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:14px; color:var(--yb-ink-3); }',
      '.pda-empty .big { font-size:44px; opacity:.5; }',
      '.pda-empty .t1 { font-size:var(--yb-fs-lg); font-weight:700; color:var(--yb-ink-2); }',
      '.pda-empty .t2 { font-size:var(--yb-fs-sm); max-width:420px; text-align:center; line-height:1.8; }',
      '.pda-empty .t2 b { color:var(--yb-brand-strong); }',
      /* 候选患者列表(多命中) */
      '.pda-cand { margin-top:12px; background:var(--yb-surface); border:1px solid var(--yb-brand-border); border-radius:var(--yb-r-md); overflow:hidden; }',
      '.pda-cand .cap { padding:8px 14px; background:var(--yb-brand-subtle); color:var(--yb-brand-strong); font-weight:700; font-size:var(--yb-fs-sm); border-bottom:1px solid var(--yb-brand-border); }',
      '.pda-cand-item { display:flex; align-items:center; gap:12px; padding:9px 14px; cursor:pointer; border-bottom:1px solid var(--yb-divider);',
      ' transition:background var(--yb-dur) var(--yb-ease); }',
      '.pda-cand-item:last-child { border-bottom:none; }',
      '.pda-cand-item:hover { background:var(--yb-brand-subtle); }',
      '.pda-cand-item .nm { font-weight:700; color:var(--yb-ink-1); min-width:64px; }',
      '.pda-cand-item .no { font-family:var(--yb-font-mono); color:var(--yb-brand-strong); font-weight:700; letter-spacing:.05em; font-variant-numeric:tabular-nums; }',
      '.pda-cand-item .meta { margin-left:auto; color:var(--yb-ink-3); font-size:var(--yb-fs-sm); }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 工具 ===== */
  function fmtTime(v) {
    if (v == null || v === '') { return '-'; }
    return String(v).replace('T', ' ').substring(0, 16);
  }
  function isOverdue(planTime) {
    if (!planTime) { return false; }
    const d = new Date(String(planTime).replace(' ', 'T'));
    return !isNaN(d.getTime()) && d.getTime() < Date.now();
  }

  /* ===== 组件 ===== */
  HIS.views['pda-simulation'] = {
    name: 'PdaSimulation',
    data() {
      return {
        /* 扫码输入与动效 */
        barcodeInput: '',
        scanning: false,
        beeped: false,
        /* 患者上下文(patients API 行, 下划线字段) */
        patient: null,
        detail: null,
        allergies: [],
        /* 演示腕带(最近入院在院患者) */
        demoRows: [],
        demoLoading: false,
        /* 候选列表(扫码多命中) */
        candidates: [],
        /* 待执行医嘱(plan API 行, 驼峰字段) */
        orders: [],
        loading: false,
        sel: [],
        executing: false,
        /* 常量映射供模板取用 */
        catMap: ORDER_CATEGORY,
        catTag: CATEGORY_TAG,
        stMap: EXEC_STATUS,
        stTag: EXEC_STATUS_TAG,
        /* 登录用户快照(非响应式即可, 组件重建时刷新) */
        HISuser: (typeof HIS !== 'undefined' && HIS.getUser) ? (HIS.getUser() || {}) : {}
      };
    },
    computed: {
      diagText() {
        const list = (this.detail && this.detail.diagnoses) || [];
        const names = list.map(d => d.diagName || d.diagnosisName || '').filter(Boolean);
        if (names.length) { return names.slice(0, 3).join('；') + (names.length > 3 ? ' 等' : ''); }
        return (this.patient && this.patient.admit_diag) || '-';
      },
      maskedPhone() {
        const p = String((this.patient && this.patient.phone) || '');
        if (!p) { return '-'; }
        if (p.length >= 7) { return p.substring(0, 3) + '****' + p.substring(p.length - 4); }
        return p.replace(/./g, '*');
      },
      allergyText() {
        return (this.allergies || []).map(a => a.allergenName).filter(Boolean).join('、');
      },
      pendingCount() {
        return this.orders.filter(o => Number(o.execStatus) === 1).length;
      },
      inpNo() {
        return this.patient ? String(this.patient.inp_no || '') : '';
      },
      /* 腕带条形码 SVG(Code128B), 非法字符回落装饰竖线 */
      bandBarcode() {
        if (!this.inpNo) { return ''; }
        const svg = code128Svg(this.inpNo, 44, 2);
        if (svg) { return svg; }
        return '<div class="fb">' + fallbackBars(this.inpNo) + '</div>';
      }
    },
    mounted() {
      /* 扫码框自动聚焦(扫码枪即扫即用) */
      this.$nextTick(() => {
        const el = this.$refs.scanInput;
        if (el && el.focus) { el.focus(); }
      });
      this.loadDemoRows();
    },
    methods: {
      fmtTime,
      isOverdue,
      genderText(g) {
        const v = String(g || '');
        if (v === '1' || v === '男') { return '男'; }
        if (v === '2' || v === '女') { return '女'; }
        return v || '-';
      },
      /* ---- 扫码入口(回车=扫码枪扳机) ---- */
      onScan() {
        const kw = String(this.barcodeInput || '').trim();
        if (!kw) {
          ElementPlus.ElMessage.warning('请扫描腕带条码或输入住院号/姓名');
          return;
        }
        this.laser();
        this.loading = true;
        HIS.get('/api/his/inp/patients?keyword=' + encodeURIComponent(kw) + '&visitStatus=2&page=1&size=20')
          .then(d => {
            const rows = (d && d.records) || [];
            if (!rows.length) {
              ElementPlus.ElMessage.error('未找到匹配的在院患者(关键词: ' + kw + ')');
              return;
            }
            this.beep();
            if (rows.length === 1) {
              this.candidates = [];
              this.pickPatient(rows[0]);
            } else {
              /* 多命中(如姓氏): 列出候选由人工点选 */
              this.candidates = rows;
              ElementPlus.ElMessage.info('匹配到 ' + rows.length + ' 位在院患者, 请点选确认');
            }
          })
          .catch(HIS.notifyError)
          .finally(() => { this.loading = false; });
      },
      /* 激光扫描线动画 */
      laser() {
        this.scanning = true;
        setTimeout(() => { this.scanning = false; }, 600);
      },
      /* 识别成功绿闪 */
      beep() {
        this.beeped = true;
        setTimeout(() => { this.beeped = false; }, 520);
      },
      /* ---- 演示腕带: 最近入院在院患者, 点击=模拟扫其腕带 ---- */
      loadDemoRows() {
        const vm = this;
        vm.demoLoading = true;
        HIS.get('/api/his/inp/patients?visitStatus=2&page=1&size=6')
          .then(d => { vm.demoRows = (d && d.records) || []; })
          .catch(() => { vm.demoRows = []; })
          .finally(() => { vm.demoLoading = false; });
      },
      demoScan(row) {
        this.barcodeInput = String(row.inp_no || '');
        this.candidates = [];
        this.pickPatient(row);
      },
      /* ---- 锁定患者: 拉详情 + 过敏 + 该患者当日待执行医嘱 ---- */
      pickPatient(row) {
        const vm = this;
        vm.patient = row;
        vm.detail = null;
        vm.allergies = [];
        vm.orders = [];
        vm.sel = [];
        const vid = HIS.id(row.id);
        HIS.get('/api/his/inp/visit/' + HIS.idParam(vid))
          .then(d => { vm.detail = d || {}; })
          .catch(() => { });
        HIS.get('/api/his/inp/allergy/list?visitId=' + HIS.idParam(vid))
          .then(list => { vm.allergies = list || []; })
          .catch(() => { });
        vm.loadOrders();
      },
      clearPatient() {
        this.patient = null;
        this.detail = null;
        this.allergies = [];
        this.orders = [];
        this.sel = [];
        this.candidates = [];
        this.barcodeInput = '';
        this.$nextTick(() => {
          const el = this.$refs.scanInput;
          if (el && el.focus) { el.focus(); }
        });
      },
      /* ---- 待执行医嘱: 按病区取当日执行计划, 前端过滤该患者 ---- */
      loadOrders() {
        const vm = this;
        if (!vm.patient) { return; }
        const wardId = vm.patient.ward_id;
        if (wardId == null) {
          vm.orders = [];
          ElementPlus.ElMessage.warning('该患者未分配病区, 无法查询执行计划');
          return;
        }
        vm.loading = true;
        HIS.get('/api/his/inp/order-exec/plan?wardId=' + HIS.idParam(wardId) + '&page=1&size=200')
          .then(d => {
            const rows = (d && d.records) || [];
            vm.orders = rows.filter(r => HIS.sameId(r.inpVisitId, vm.patient.id));
            vm.sel = [];
          })
          .catch(HIS.notifyError)
          .finally(() => { vm.loading = false; });
      },
      onSelChange(rows) { this.sel = rows || []; },
      /* ---- 确认执行: POST /execute, 双人核对场景 PDA 端拦截 ---- */
      confirmExec() {
        const vm = this;
        const rows = vm.sel.filter(r => Number(r.execStatus) === 1);
        if (!rows.length) {
          ElementPlus.ElMessage.warning('请勾选待执行的医嘱计划');
          return;
        }
        const hasHigh = rows.some(r => Number(r.highAlertFlag) === 1 || Number(r.doubleCheckFlag) === 1);
        ElementPlus.ElMessageBox.confirm(
          '确认对 ' + (vm.patient && vm.patient.patient_name) + ' 执行所选 ' + rows.length + ' 项医嘱计划？执行后将记录当前登录人为执行护士。',
          '床旁执行确认',
          { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
        ).then(() => {
          /* 19位雪花ID以字符串承载: 执行ID数组不得数字化 */
          const ids = rows.map(r => HIS.id(r.id));
          vm.executing = true;
          return HIS.post('/api/his/inp/order-exec/execute', ids).then(d => {
            if (d && d.needDoubleCheck) {
              ElementPlus.ElMessageBox.alert(
                '选中医嘱含高警示/需双人核对药品(' + (d.orderIds || []).length + ' 条), PDA 单人操作端不支持双人核对, 请在护士工作站完成执行。',
                '需双人核对', { type: 'warning', confirmButtonText: '知道了' }
              );
              return;
            }
            const done = d && d.executed != null ? d.executed : ids.length;
            const skip = d && d.skipped ? d.skipped.length : 0;
            HIS.notifySuccess('已执行 ' + done + ' 项医嘱计划' + (skip ? '(跳过 ' + skip + ' 项已变更项)' : ''));
            vm.loadOrders();
          });
        }).catch(e => {
          if (e && e !== 'cancel' && e !== 'close') { HIS.notifyError(e); }
        }).finally(() => { vm.executing = false; });
        /* 高警示提示前置于确认文案(hasHigh 仅做视觉提醒, 实际拦截由后端 needDoubleCheck 决定) */
        if (hasHigh) {
          ElementPlus.ElMessage.warning('所选计划含高警示/双人核对医嘱, 提交后可能被要求双人核对');
        }
      }
    },
    template: `
      <div class="pda-wrap">
        <!-- 扫码头: 工业终端意象(呼吸LED + 等宽终端字标 + 大号扫码框) -->
        <div class="pda-scanbar">
          <div class="laser" v-if="scanning"></div>
          <div class="beep" v-if="beeped"></div>
          <div class="hd">
            <span class="led"></span>
            <span class="tt">PDA 扫码工作台</span>
            <span class="sub">WRISTBAND · CODE128 · BEDSIDE</span>
            <span class="spacer"></span>
            <span class="who">{{ (HISuser && (HISuser.realName || HISuser.username)) || '-' }}</span>
          </div>
          <div class="pda-scanrow">
            <el-input ref="scanInput" v-model="barcodeInput" size="large" clearable
                      placeholder="扫描腕带条码, 或输入住院号 / 姓名 / 身份证后回车"
                      @keyup.enter="onScan" @clear="clearPatient">
            </el-input>
            <el-button class="pda-scanbtn" size="large" type="danger" :loading="loading" @click="onScan">扫 码</el-button>
          </div>
          <!-- 演示腕带: 点击=模拟扫该患者腕带(测试便利) -->
          <div class="pda-demo" v-if="demoRows.length">
            <span class="lbl">模拟腕带(点击即扫):</span>
            <span class="pda-demo-card" v-for="r in demoRows" :key="r.id" @click="demoScan(r)">
              <span class="nm">{{ r.patient_name }}</span>
              <span class="bd">{{ r.inp_no }}</span>
            </span>
          </div>
        </div>

        <!-- 候选患者(扫码多命中人工点选) -->
        <div style="padding:0 16px;" v-if="candidates.length">
          <div class="pda-cand">
            <div class="cap">匹配到 {{ candidates.length }} 位在院患者, 请点选目标患者</div>
            <div class="pda-cand-item" v-for="r in candidates" :key="r.id" @click="demoScan(r)">
              <span class="nm">{{ r.patient_name }}</span>
              <span class="no">{{ r.inp_no }}</span>
              <span class="meta">{{ genderText(r.gender_name || r.gender) }} · {{ r.age }}岁 · {{ r.ward_name || '-' }} {{ r.bed_no ? r.bed_no + '床' : '' }}</span>
            </div>
          </div>
        </div>

        <!-- 主体 -->
        <div class="pda-main" v-if="patient">
          <!-- 左栏: 患者信息 + 腕带仿真预览 -->
          <div class="pda-left">
            <div class="pda-card">
              <div class="cap">
                <span class="dot"></span>患者识别结果
                <span style="margin-left:auto;">
                  <el-button size="small" text type="primary" @click="clearPatient">解除绑定</el-button>
                </span>
              </div>
              <div class="bd">
                <div class="pda-grid">
                  <div class="f"><div class="k">姓名</div><div class="v">{{ patient.patient_name || '-' }}</div></div>
                  <div class="f"><div class="k">性别 / 年龄</div><div class="v">{{ genderText(patient.gender_name || patient.gender) }} · {{ patient.age || '-' }}岁</div></div>
                  <div class="f"><div class="k">住院号</div><div class="v mono">{{ patient.inp_no || '-' }}</div></div>
                  <div class="f"><div class="k">床位</div><div class="v">{{ (patient.ward_name || '-') + ' · ' + (patient.bed_no ? patient.bed_no + '床' : '未分配') }}</div></div>
                  <div class="f wide"><div class="k">入院诊断</div><div class="v" :title="diagText" style="white-space:normal;">{{ diagText }}</div></div>
                  <div class="f"><div class="k">入院时间</div><div class="v mono" style="font-size:12px;">{{ fmtTime(patient.admit_date) }}</div></div>
                  <div class="f"><div class="k">联系电话</div><div class="v mono" style="font-size:12px;">{{ maskedPhone }}</div></div>
                </div>
                <div class="pda-allergy" :class="{ 'is-safe': !allergyText }">
                  {{ allergyText ? ('⚠ 过敏: ' + allergyText) : '未登记药物过敏' }}
                </div>
              </div>
            </div>

            <!-- 腕带仿真预览: Code128 条形码 + 患者要素(与打印腕带同构) -->
            <div class="pda-card">
              <div class="cap pda-band-cap">
                <span class="dot"></span>腕带条码预览
                <span class="hint">CODE128-B</span>
              </div>
              <div class="bd" style="padding:12px;">
                <div class="pda-band is-print">
                  <div class="warn" v-if="allergyText">药物<br/>过敏</div>
                  <div class="info">
                    <div class="nm">{{ patient.patient_name || '-' }}</div>
                    <div class="meta">NO.{{ patient.inp_no || '-' }}</div>
                    <div class="meta">{{ patient.bed_no ? (patient.bed_no + '床') : '未分床' }}</div>
                  </div>
                  <div class="bc" v-html="bandBarcode">
                  </div>
                </div>
                <div style="margin-top:8px;color:var(--yb-ink-4);font-size:11px;line-height:1.6;">
                  扫码枪读取本条码即回填上方输入框; 实际部署时 PDA 摄像头解码腕带 Code128 后自动触发同一链路。
                </div>
              </div>
            </div>
          </div>

          <!-- 右栏: 待执行医嘱 -->
          <div class="pda-orders">
            <div class="cap">
              <span class="t">今日待执行医嘱</span>
              <span class="cnt">{{ pendingCount }}</span>
              <span style="color:var(--yb-ink-4);font-size:12px;">项</span>
              <span class="grow"></span>
              <el-button size="small" :loading="loading" @click="loadOrders">刷新</el-button>
            </div>
            <div class="scroll" v-loading="loading">
              <el-table :data="orders" size="small" border :row-key="r => r.id"
                        @selection-change="onSelChange">
                <el-table-column type="selection" width="40" :selectable="r => Number(r.execStatus) === 1"></el-table-column>
                <el-table-column label="计划时间" width="92">
                  <template #default="s">
                    <span :class="{ 'is-overdue': isOverdue(s.row.planTime) && Number(s.row.execStatus) === 1 }">{{ s.row.planTime || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="医嘱内容" min-width="220" show-overflow-tooltip>
                  <template #default="s">
                    <span style="font-weight:600;color:var(--yb-ink-1);">{{ s.row.orderContent || '-' }}</span>
                    <span v-if="s.row.spec" style="color:var(--yb-ink-3);margin-left:6px;">{{ s.row.spec }}</span>
                    <span v-if="s.row.dosage" class="pda-flag">{{ s.row.dosage }}{{ s.row.dosageUnit || '' }}</span>
                    <span v-if="Number(s.row.highAlertFlag) === 1" class="pda-flag is-danger">[高警示]</span>
                    <span v-else-if="Number(s.row.doubleCheckFlag) === 1" class="pda-flag is-warn">[双核对]</span>
                    <span v-if="Number(s.row.skinTestFlag) === 1" class="pda-flag is-warn">[需皮试]</span>
                  </template>
                </el-table-column>
                <el-table-column label="类别" width="70" align="center">
                  <template #default="s">
                    <el-tag size="small" :type="catTag[s.row.orderCategory] || 'info'" disable-transitions>{{ catMap[s.row.orderCategory] || '-' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="频次" width="76" align="center">
                  <template #default="s">{{ s.row.freqCode || '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="80" align="center">
                  <template #default="s">
                    <el-tag size="small" :type="stTag[s.row.execStatus] || 'info'" disable-transitions>{{ stMap[s.row.execStatus] || '-' }}</el-tag>
                  </template>
                </el-table-column>
                <template #empty>
                  <div style="padding:24px 0;color:var(--yb-ink-4);font-size:13px;">今日暂无该患者的执行计划</div>
                </template>
              </el-table>
            </div>
            <div class="foot">
              <span class="tip">已选 {{ sel.length }} 项 · 执行签名人为当前登录职工</span>
              <span class="grow"></span>
              <el-button type="primary" :loading="executing" :disabled="!sel.length" @click="confirmExec">
                确认执行({{ sel.length }})
              </el-button>
            </div>
          </div>
        </div>

        <!-- 空态引导 -->
        <div class="pda-empty" v-else>
          <div class="big">⌗</div>
          <div class="t1">等待扫码…</div>
          <div class="t2">
            在上方输入框<b>扫描腕带条码</b>(回车触发, 等同扫码枪扳机), 或点击深色栏中的<b>模拟腕带</b>快捷体验。<br/>
            识别患者后展示腕带 Code128 条码预览与当日待执行医嘱, 勾选后即可床旁执行。
          </div>
        </div>
      </div>
    `
  };
})();
