/* 基础数据: 科室 / 职工 / 排班号源 (收费项目对照已下线, 对照请用「三目录医保对照」) */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var DEPT_TYPES = ['临床', '医技', '行政'];
  var DEPT_CATEGORIES = ['门诊科室', '住院科室', '病区护理', '医技科室', '行政后勤'];
  var DEPT_LEVELS = [{ v: 1, l: '大类' }, { v: 2, l: '科室' }, { v: 3, l: '窗口/诊室' }];
  var STAFF_TYPES = ['医师', '护士', '药师', '技师', '管理'];
  /* 兜底时段常量: 班次字典(his_shift_dict, L1)未加载/接口异常时使用, 正常取数源为 HIS.shiftDict 缓存 */
  var TIME_TYPES = [{ v: 'am', l: '上午' }, { v: 'pm', l: '下午' }, { v: 'night', l: '晚间' }];

  /* 去除审计字段, 避免回填干扰 */
  function clean(f) {
    delete f.createTime; delete f.updateTime; delete f.createBy; delete f.updateBy; delete f.deleted;
    return f;
  }
  function timeLabel(v) {
    /* 优先班次字典缓存(含自定义班次), 未命中回落存量三值 */
    var n = (window.HIS && HIS.shiftLabel) ? HIS.shiftLabel(v) : null;
    if (n) { return n; }
    for (var i = 0; i < TIME_TYPES.length; i++) { if (TIME_TYPES[i].v === v) { return TIME_TYPES[i].l; } }
    return v || '-';
  }
  /* 科室大类多选: 逗号分隔串 <-> 数组互转; joinCat 按五大类固定顺序归一(首个=主大类, 供授权分组与展示) */
  function splitCat(s) { return s ? String(s).split(',').map(function (x) { return x.trim(); }).filter(Boolean) : []; }
  function joinCat(arr) {
    var a = (Array.isArray(arr) ? arr : String(arr || '').split(',')).map(function (x) { return x.trim(); }).filter(Boolean);
    a.sort(function (x, y) { return DEPT_CATEGORIES.indexOf(x) - DEPT_CATEGORIES.indexOf(y); });
    return a.join(',');
  }

  /* ================= 科室管理(层级树: 大类→科室→窗口/诊室) ================= */
  HIS.views.DeptManage = {
    mixins: [HIS.kwSelectMixin],
    data: function () {
      return {
        loading: false, tree: [], nodeMap: {}, allDepts: null,
        deptTypes: DEPT_TYPES, deptCategories: DEPT_CATEGORIES, deptLevels: DEPT_LEVELS,
        orgs: [], filterOrg: null, catyOpts: [], catyMap: {},
        /* 查询条件(前端过滤已加载的机构科室树): 名称/编码关键字 + 大类 + 状态 */
        keyword: '', filterCategory: '', filterStatus: null,
        /* 选机构时是否级联显示下级机构科室(默认不勾选=仅选中机构本身; 仅牵头机构生效) */
        withSubOrgs: false,
        exporting: false,
        /* 右侧明细显示模式: 默认由租户参数 system.list_default_paged 控制, 用户手动切换后以本地偏好为准 */
        paged: HIS.pagedDefault('deptPaged2'),
        page: 1, size: 20,
        /* 左栏机构列表收缩态(localStorage 持久化, 与主侧栏收展同策略) */
        orgsCollapsed: (function () { try { return localStorage.getItem('his.deptOrgsCollapsed') === '1'; } catch (e) { return false; } })(),
        /* 左栏机构子树折叠态: 机构id→true=已折叠(隐藏其下级机构) */
        orgFolded: {},
        /* 左栏机构树过滤关键字(名称/编码/拼音命中, 连同祖先保留) */
        orgKw: '',
        /* 右栏科室表列设置: 低频列默认隐藏(拼音码/自定义码/医保科别), localStorage 持久化 */
        colDefs: [
          { key: 'code', label: '编码' }, { key: 'pinyin', label: '拼音码' }, { key: 'abbr', label: '自定义码' },
          { key: 'level', label: '层级' }, { key: 'cat', label: '所属大类' }, { key: 'org', label: '所属机构' },
          { key: 'type', label: '类型' }, { key: 'caty', label: '医保科别' }, { key: 'status', label: '状态' }, { key: 'clinic', label: '门诊开诊' }
        ],
        colOff: (function () {
          try { return JSON.parse(localStorage.getItem('his.deptCols') || '{"pinyin":1,"abbr":1,"caty":1}'); } catch (e) { return { pinyin: 1, abbr: 1, caty: 1 }; }
        })(),
        lead: HIS.isLead(),
        /* 三期: 科室默认发药药房下拉选项(按当前表单机构加载启用药房) */
        pharmacyOpts: [],
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () {
      var vm = this;
      /* 非牵头: 锁定本机构(后端亦强制), 只读 */
      if (!vm.lead) { vm.filterOrg = (HIS.getUser() || {}).orgId || null; }
      vm.loadCaty(); vm.load(); vm.loadDeptCounts();
      /* 首屏默认收缩到二级(显示二级医疗机构), 与"收缩"按钮同口径(#1) */
      vm.loadOrgs().then(function () {
        if (!Object.keys(vm.orgFolded).length) { vm.collapseOrgToLevel(2); }
      });
    },
    computed: {
      /* 上级科室下拉: 扁平化缩进展示层级; 编辑时排除自身及子树防环 */
      parentOptions: function () {
        var vm = this;
        var out = [{ id: 0, label: '（顶级：科室大类）' }];
        var excludeId = vm.editing && vm.form.id ? vm.form.id : null;
        /* 已选所属机构时上级候选仅限该机构子树, 防跨机构父子(#2) */
        var orgFilter = vm.form.orgId || null;
        (function walk(nodes, depth) {
          (nodes || []).forEach(function (n) {
            if (excludeId && n.id === excludeId) { return; }
            if (orgFilter && n.orgId !== orgFilter) { return; }
            var pad = '';
            for (var i = 0; i < depth; i++) { pad += '　'; }
            out.push({ id: n.id, label: pad + (depth > 0 ? '└ ' : '') + n.deptName, pyCode: n.pyCode, abbrCode: n.abbrCode });
            walk(n.children, depth + 1);
          });
        })(vm.tree, 0);
        return out;
      },
      /* 分页模式按摊平后的明细行切片(DFS 序, 与树展开顺序一致); 全量模式直接返过滤树 */
      pagedTree: function () {
        if (!this.paged) { return this.filteredTree; }
        return this.flatRows.slice((this.page - 1) * this.size, this.page * this.size);
      },
      /* 过滤树 DFS 摊平(去 children, 渲染为平面行); 层级信息由「层级/所属大类」列体现 */
      flatRows: function () {
        var out = [];
        (function walk(nodes) {
          (nodes || []).forEach(function (n) {
            var c = Object.assign({}, n);
            delete c.children;
            out.push(c);
            walk(n.children);
          });
        })(this.filteredTree);
        return out;
      },
      /* 查询过滤: 命中节点保留其全部子级(上下文), 未命中但有命中后代的节点仅保留命中分支 */
      filteredTree: function () {
        var vm = this;
        var kw = (vm.keyword || '').trim().toLowerCase();
        var cat = vm.filterCategory || '';
        var st = (vm.filterStatus === '' || vm.filterStatus == null) ? null : vm.filterStatus;
        if (!kw && !cat && st === null) { return vm.tree; }
        function match(n) {
          if (kw && !HIS.kwMatch(n, kw, ['deptName', 'deptCode', 'pyCode', 'abbrCode'])) { return false; }
          if (cat && !vm.catHas(n.deptCategory, cat)) { return false; }
          if (st !== null && n.status !== st) { return false; }
          return true;
        }
        function walk(nodes) {
          var out = [];
          (nodes || []).forEach(function (n) {
            var self = match(n);
            var kids = walk(n.children);
            if (self || kids.length) {
              var c = Object.assign({}, n);
              c.children = self ? (n.children || []) : kids;
              out.push(c);
            }
          });
          return out;
        }
        return walk(vm.tree);
      },
      /* 过滤后科室总数(含各级节点) */
      filteredCount: function () {
        var n = 0;
        (function walk(nodes) { (nodes || []).forEach(function (x) { n++; walk(x.children); }); })(this.filteredTree);
        return n;
      },
      /* 左栏机构树是否处于过滤态(强制展平 + caret 置灰) */
      searching: function () { return !!String(this.orgKw || '').trim(); },
      /* 机构父链索引(共享缓存): 前序展平数组中向前最近更小 orgLevel 即父机构; 角标累计与搜索保留父链两处复用(#5) */
      orgParentIdx: function () {
        var orgs = this.orgs; var par = {};
        for (var i = 0; i < orgs.length; i++) {
          for (var j = i - 1; j >= 0; j--) { if ((orgs[j].orgLevel || 1) < (orgs[i].orgLevel || 1)) { par[orgs[i].id] = orgs[j].id; break; } }
        }
        return par;
      },
      /* 右栏计数文案: 树计数含停用, 与左栏"在用"角标口径不同, 明示避免误解(#4) */
      countHint: function () {
        var s = '共 ' + this.filteredCount + ' 个科室';
        return (this.filterStatus === '' || this.filterStatus == null) ? s + '（含停用 · 左栏角标为在用数）' : s;
      },
      /* 机构节点在用科室数角标(含下级机构累计); allDepts 未就绪时返回 null 不显示 */
      orgCountMap: function () {
        var vm = this;
        if (!vm.allDepts) { return null; }
        var direct = {}; var total = 0;
        vm.allDepts.forEach(function (d) { direct[d.orgId] = (direct[d.orgId] || 0) + 1; total++; });
        var orgs = vm.orgs; var sub = {}; var par = vm.orgParentIdx;
        orgs.forEach(function (o) { sub[o.id] = direct[o.id] || 0; });
        /* 逆序累加: 子机构科室数并入父机构 */
        for (var k = orgs.length - 1; k >= 0; k--) { var pid = par[orgs[k].id]; if (pid != null && sub[pid] != null) { sub[pid] += sub[orgs[k].id]; } }
        var m = { all: total };
        orgs.forEach(function (o) { m['org-' + o.id] = sub[o.id] || 0; });
        return m;
      },
      /* 左栏机构列表: 过滤态忽略折叠强制展平并保留命中祖先链+选中项; 否则按 orgFolded 跳过子树 */
      visibleOrgs: function () {
        var vm = this; var out = [];
        var kw = String(vm.orgKw || '').trim();
        if (kw) {
          var byId = {};
          vm.orgs.forEach(function (o) { byId[o.id] = o; });
          var par = vm.orgParentIdx;
          var keep = {};
          var markUp = function (o) { var c = o; while (c && !keep[c.id]) { keep[c.id] = 1; c = par[c.id] ? byId[par[c.id]] : null; } };
          vm.orgs.forEach(function (o) { if (HIS.kwMatch(o, kw, ['label', 'orgCode', 'pyCode'])) { markUp(o); } });
          /* 作用域可见性: 已选机构及其祖先始终保留 */
          if (vm.filterOrg && byId[vm.filterOrg]) { markUp(byId[vm.filterOrg]); }
          vm.orgs.forEach(function (o) { if (keep[o.id]) { out.push(o); } });
          return out;
        }
        var skipping = null;
        vm.orgs.forEach(function (o) {
          if (skipping !== null) {
            if ((o.depth || 0) > skipping) { return; }
            skipping = null;
          }
          out.push(o);
          if (o.hasKids && vm.orgFolded[o.id]) { skipping = o.depth || 0; }
        });
        return out;
      }
    },
    methods: {
      empty: function () {
        return { id: null, orgId: null, parentId: 0, deptCategory: [], deptLevel: 1, deptCode: '', deptName: '', pyCode: '', abbrCode: '', deptType: '临床', deptCaty: '', phone: '', locDesc: '', sortNo: 0, status: 1, openClinic: 1, defPharmacyWest: null, defPharmacyTcm: null, memo: '' };
      },
      /* 三期: 按表单机构加载启用药房(无机构则清空); 默认药房为机构级配置, 需先选定所属机构 */
      loadPharmacies: function (orgId) {
        var vm = this;
        if (!orgId) { vm.pharmacyOpts = []; return; }
        HIS.get('/api/his/pharmacy/pharmacy-def?orgId=' + encodeURIComponent(orgId))
          .then(function (list) { vm.pharmacyOpts = list || []; })
          .catch(function () { vm.pharmacyOpts = []; });
      },
      /* 所属机构变更: 重拉药房选项并清空已选默认药房(避免跨机构残留); 上级不属于新机构时重置为顶级(#2) */
      onDeptOrgChange: function () {
        this.form.defPharmacyWest = null; this.form.defPharmacyTcm = null;
        var pid = this.form.parentId;
        if (pid && pid !== 0 && (!this.nodeMap[pid] || this.nodeMap[pid].orgId !== this.form.orgId)) {
          this.form.parentId = 0; this.form.deptLevel = 1;
          ElementPlus.ElMessage.info('上级科室不属于所选机构, 已重置为顶级(大类)');
        }
        this.loadPharmacies(this.form.orgId);
      },
      loadOrgs: function () {
        var vm = this;
        return HIS.get('/api/sys/org/tree').then(function (d) {
          vm.orgs = HIS.flattenOrgs(d || []);
          /* 非牵头: 左栏仅列本机构(后端读隔离亦强制) */
          if (!vm.lead) { vm.orgs = vm.orgs.filter(function (o) { return o.id === vm.filterOrg; }); }
        }).catch(function () { vm.orgs = []; });
      },
      /* 左栏机构角标数据源: 一次性拉全院在用科室(status=1)按 orgId 统计(后端按登录机构作用域隔离) */
      loadDeptCounts: function () {
        var vm = this;
        return HIS.get('/api/his/dept/enabled').then(function (d) { vm.allDepts = d || []; }).catch(function () { vm.allDepts = []; });
      },
      /* 左栏点选机构: 右侧科室树按机构过滤(null=全部机构) */
      selectOrg: function (id) {
        if (this.filterOrg === id) { return; }
        this.filterOrg = id; this.load();
      },
      /* 左栏收缩/展开切换并持久化 */
      toggleOrgs: function () {
        this.orgsCollapsed = !this.orgsCollapsed;
        try { localStorage.setItem('his.deptOrgsCollapsed', this.orgsCollapsed ? '1' : '0'); } catch (e) { }
      },
      /* 折叠/展开机构下级子树(仅显隐, 不触发筛选) */
      toggleOrgFold: function (o) { this.orgFolded[o.id] = !this.orgFolded[o.id]; },
      /* 机构树标签高亮: 转义 HTML 后将命中子串包入 <mark>(大小写不敏感) */
      hl: function (text) {
        var t = String(text == null ? '' : text);
        var esc = function (s) { return s.replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); };
        var kw = String(this.orgKw || '').trim();
        if (!kw) { return esc(t); }
        var lower = t.toLowerCase(); var k = kw.toLowerCase(); var out = ''; var i = 0;
        for (;;) {
          var idx = lower.indexOf(k, i);
          if (idx < 0) { out += esc(t.slice(i)); break; }
          out += esc(t.slice(i, idx)) + '<mark class="kw-hit">' + esc(t.slice(idx, idx + kw.length)) + '</mark>';
          i = idx + kw.length;
        }
        return out;
      },
      /* 左栏机构悬停提示: 名称+编码+拼音, 命中编码/拼音而无可见高亮时可解释(#7) */
      orgTitle: function (o) {
        var parts = [String(o.label || '').trim()];
        if (o.orgCode) { parts.push('编码 ' + o.orgCode); }
        if (o.pyCode) { parts.push('拼音 ' + o.pyCode); }
        return parts.join(' / ');
      },
      /* 机构树批量展开/收缩/到层级(搜索态强制展平, 先清关键字使操作即时可见) */
      expandAllOrg: function () { this.orgKw = ''; this.orgFolded = {}; },
      /* "收缩": 收缩到二级机构(保留顶级与二级可见, 折叠二级及更深的子树) */
      collapseAllOrg: function () {
        this.orgKw = ''; var f = {}; this.orgs.forEach(function (o) { if (o.hasKids && (o.depth || 0) >= 1) { f[o.id] = true; } }); this.orgFolded = f;
      },
      collapseOrgToLevel: function (n) {
        this.orgKw = '';
        if (!n) { this.orgFolded = {}; return; }
        var cap = n - 1; var f = {};
        this.orgs.forEach(function (o) { if (o.hasKids && (o.depth || 0) >= cap) { f[o.id] = true; } });
        this.orgFolded = f;
      },
      /* 列设置: 勾选=显示(colOff 置0), 取消=隐藏(置1); 持久化 */
      colShow: function (key) { return !this.colOff[key]; },
      onColToggle: function (key, shown) { this.colOff[key] = shown ? 0 : 1; this.onColChange(); },
      onColChange: function () { try { localStorage.setItem('his.deptCols', JSON.stringify(this.colOff)); } catch (e) { } },
      loadCaty: function () { var vm = this; HIS.stdValues('cv_code', 'caty').then(function (l) { vm.catyOpts = l || []; vm.catyMap = HIS.dictMap(l); }).catch(HIS.notifyError); },
      orgName: function (id) { for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } } return '-'; },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/dept/tree?1=1';
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg + '&withSubOrgs=' + vm.withSubOrgs; }
        HIS.get(q).then(function (d) { vm.tree = d || []; vm.rebuildMap(); vm.page = 1; }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 分页/全量模式切换(持久化): 超阈弹确认软提示, 用户确认后仍可全量; 阈值读租户参数 */
      onPagedToggle: function () {
        var vm = this;
        var threshold = (HIS.params && HIS.params.listFullThreshold) || 2000;
        if (!this.paged && this.flatRows.length > threshold) {
          ElementPlus.ElMessageBox.confirm(
            '当前共 ' + this.flatRows.length + ' 行，全量显示可能卡顿数秒，是否继续？',
            '提示', { confirmButtonText: '继续全量', cancelButtonText: '保持分页', type: 'warning' }
          ).then(function () {
            vm.page = 1;
            try { localStorage.setItem('his.deptPaged2', '0'); } catch (e) { }
          }).catch(function () {
            vm.paged = true;
            try { localStorage.setItem('his.deptPaged2', '1'); } catch (e) { }
          });
          return;
        }
        this.page = 1;
        try { localStorage.setItem('his.deptPaged2', this.paged ? '1' : '0'); } catch (e) { }
      },
      /* 序号列: 分页模式跨页连续编号 */
      seqNo: function (i) { return this.paged ? (this.page - 1) * this.size + i + 1 : i + 1; },
      onPage: function (p) { this.page = p; },
      onSize: function (s) { this.size = s; this.page = 1; },
      /* 查询条件变化: 回第 1 页(过滤为计算属性即时生效) */
      onQueryChange: function () { this.page = 1; },
      /* 导出(xlsx): 沿用当前机构/含下级机构筛选, 层级树摊平导出全部匹配行 */
      exportRows: function () {
        var vm = this;
        var q = '/api/his/dept/export?1=1';
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg + '&withSubOrgs=' + vm.withSubOrgs; }
        if (vm.keyword && vm.keyword.trim()) { q += '&keyword=' + encodeURIComponent(vm.keyword.trim()); }
        if (vm.filterCategory) { q += '&deptCategory=' + encodeURIComponent(vm.filterCategory); }
        if (vm.filterStatus !== '' && vm.filterStatus != null) { q += '&status=' + vm.filterStatus; }
        vm.exporting = true;
        HIS.download(q).then(function (name) {
          HIS.notifySuccess('已导出: ' + name);
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      rebuildMap: function () {
        var m = {};
        (function walk(nodes) { (nodes || []).forEach(function (n) { m[n.id] = n; walk(n.children); }); })(this.tree);
        this.nodeMap = m;
      },
      levelLabel: function (lv) { for (var i = 0; i < DEPT_LEVELS.length; i++) { if (DEPT_LEVELS[i].v === lv) { return DEPT_LEVELS[i].l; } } return lv || '-'; },
      levelTagType: function (lv) { return lv === 1 ? 'danger' : (lv === 2 ? '' : (lv === 3 ? 'success' : 'info')); },
      /* 大类包含判定(行/存储为逗号串): 供表格开诊标签与树过滤复用 */
      catHas: function (stored, cat) { return (',' + (stored || '') + ',').indexOf(',' + cat + ',') >= 0; },
      catFmt: function (stored) { var a = splitCat(stored); return a.length ? a.join('、') : '-'; },
      /* 科室类型由大类主标签(首个)派生: 医技科室→医技, 行政后勤→行政, 其余→临床; 与后端 deriveDeptType 同口径 */
      typeFromCat: function (stored) { var p = splitCat(stored)[0]; if (!p) { return ''; } if (p.indexOf('医技') >= 0) { return '医技'; } if (p.indexOf('行政') >= 0) { return '行政'; } return '临床'; },
      add: function () {
        this.editing = false; this.form = this.empty();
        /* 已选中机构时默认带入所属机构 */
        if (this.filterOrg) { this.form.orgId = this.filterOrg; }
        this.loadPharmacies(this.form.orgId);
        this.dlg = true;
      },
      /* 快捷新增下级: 继承上级机构/大类, 层级+1(封顶3) */
      addChild: function (row) {
        this.editing = false;
        var f = this.empty();
        f.parentId = row.id; f.orgId = row.orgId; f.deptCategory = splitCat(row.deptCategory);
        f.deptLevel = (row.deptLevel || 1) + 1; if (f.deptLevel > 3) { f.deptLevel = 3; }
        this.form = f; this.loadPharmacies(f.orgId); this.dlg = true;
      },
      edit: function (row) {
        this.editing = true; this.form = clean(Object.assign(this.empty(), row));
        delete this.form.children;
        this.form.deptCategory = splitCat(row.deptCategory);
        if (this.form.parentId == null) { this.form.parentId = 0; }
        if (this.form.openClinic == null) { this.form.openClinic = 1; }
        this.loadPharmacies(this.form.orgId);
        this.dlg = true;
      },
      /* 选上级时自动带出大类与层级(顶级=1大类) */
      onParentChange: function (pid) {
        if (!pid || pid === 0) { this.form.deptLevel = 1; return; }
        var p = this.nodeMap[pid];
        if (p) {
          if (p.deptCategory) { this.form.deptCategory = splitCat(p.deptCategory); }
          this.form.deptLevel = (p.deptLevel || 1) + 1; if (this.form.deptLevel > 3) { this.form.deptLevel = 3; }
          if (!this.form.orgId && p.orgId) { this.form.orgId = p.orgId; }
        }
      },
      /* 保存前基础校验: 编码/名称必填; 编码/电话若填做格式约束(不伤历史宽松值) */
      validateForm: function () {
        var f = this.form;
        if (!String(f.deptCode || '').trim()) { return '科室编码必填'; }
        if (!String(f.deptName || '').trim()) { return '科室名称必填'; }
        if (f.deptCode && !/^[0-9A-Za-z_.\-]+$/.test(String(f.deptCode).trim())) { return '科室编码仅允许字母数字及 _ . - ';
        }
        var ph = String(f.phone || '').trim();
        if (ph && !/^[0-9+\-() ]{6,20}$/.test(ph)) { return '联系电话格式不正确'; }
        return null;
      },
      submit: function () {
        var vm = this;
        var err = vm.validateForm();
        if (err) { ElementPlus.ElMessage.warning(err); return; }
        var payload = Object.assign({}, vm.form); payload.deptCategory = joinCat(vm.form.deptCategory);
        var p = vm.editing ? HIS.put('/api/his/dept', payload) : HIS.post('/api/his/dept', payload);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); vm.loadDeptCounts(); }).catch(HIS.notifyError);
      },
      del: function (row) {
        var vm = this;
        if (row.children && row.children.length) { ElementPlus.ElMessage.warning('该科室存在下级, 请先删除或移动下级科室'); return; }
        HIS.del('/api/his/dept/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); vm.loadDeptCounts(); }).catch(HIS.notifyError);
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">科室管理</div>',
      '  <el-alert v-if="!lead" type="warning" :closable="false" show-icon style="margin-bottom:10px;" title="非牵头机构: 仅展示本机构科室, 只读不可维护。"></el-alert>',
      '  <div class="dept-split">',
      '    <div class="dept-orgs" :class="{collapsed: orgsCollapsed}">',
      '      <div class="dept-orgs-hd">',
      '        <span v-show="!orgsCollapsed">机构列表</span>',
      '        <span v-show="orgsCollapsed" class="dept-orgs-vt">机构列表</span>',
      '        <el-button link size="small" class="dept-orgs-tg" :title="orgsCollapsed?\'展开机构列表\':\'收缩机构列表\'" @click="toggleOrgs">{{ orgsCollapsed ? "\u00bb" : "\u00ab" }}</el-button>',
      '      </div>',
      '      <div v-show="!orgsCollapsed" style="padding:6px 8px 0;">',
      '        <el-input v-model="orgKw" size="small" clearable placeholder="过滤机构名/编码"></el-input>',
      '        <div class="dept-tree-tools">',
      '          <span class="dept-tree-tools-lb">层级</span>',
      '          <el-button link size="small" class="dept-tree-btn" title="展开全部层级" @click="expandAllOrg">展开</el-button>',
      '          <el-dropdown trigger="click" class="dept-tree-drop" @command="collapseOrgToLevel">',
      '            <el-button link size="small" class="dept-tree-btn" title="展开/收缩到指定层级">到层级\u25be</el-button>',
      '            <template #dropdown>',
      '              <el-dropdown-menu>',
      '                <el-dropdown-item :command="1">一级（顶级机构）</el-dropdown-item>',
      '                <el-dropdown-item :command="2">二级（卫生院/社区）</el-dropdown-item>',
      '                <el-dropdown-item :command="3">三级（卫生室）</el-dropdown-item>',
      '              </el-dropdown-menu>',
      '            </template>',
      '          </el-dropdown>',
      '          <el-button link size="small" class="dept-tree-btn" title="收缩到二级(显示二级医疗机构)" @click="collapseAllOrg">收缩</el-button>',
      '        </div>',
      '      </div>',
      '      <el-scrollbar v-show="!orgsCollapsed">',
      '        <div v-if="lead" class="dept-org-item" :class="{active: filterOrg===null}" @click="selectOrg(null)"><span class="tree-caret"></span><span>全部机构</span><span v-if="orgCountMap" class="tree-cnt" title="在用科室数(不含停用)">{{ orgCountMap.all }}</span></div>',
      '        <div v-for="o in visibleOrgs" :key="o.id" class="dept-org-item" :title="orgTitle(o)" :class="{active: filterOrg===o.id}" @click="selectOrg(o.id)"><span v-if="o.hasKids" class="tree-caret" :class="{\'is-inert\': searching}" :title="searching?\'过滤态自动展开全部层级, 清空关键字后可折叠\':\'折叠/展开\'" @click.stop="searching ? null : toggleOrgFold(o)">{{ (searching || !orgFolded[o.id]) ? \'▾\' : \'▸\' }}</span><span v-else class="tree-caret"></span><span v-html="hl(o.label)"></span><span v-if="orgCountMap && orgCountMap[\'org-\'+o.id]" class="tree-cnt" title="在用科室数(含下级机构, 不含停用)">{{ orgCountMap[\'org-\'+o.id] }}</span></div>',
      '        <div v-if="orgKw && !visibleOrgs.length" class="dept-tree-empty">无匹配机构</div>',
      '      </el-scrollbar>',
      '    </div>',
      '    <div class="dept-main">',
      '  <div class="toolbar">',
      '    <el-button v-if="lead" type="primary" @click="add">新增大类/科室</el-button><el-button @click="load">刷新</el-button>',
      '    <el-input v-model="keyword" placeholder="名称/编码/拼音简码" clearable style="width:170px" @input="onQueryChange" @clear="onQueryChange"></el-input>',
      '    <el-select v-model="filterCategory" placeholder="全部大类" clearable style="width:130px" @change="onQueryChange"><el-option v-for="c in deptCategories" :key="c" :label="c" :value="c"></el-option></el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:100px" @change="onQueryChange"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-checkbox v-if="lead" v-model="withSubOrgs" @change="load" title="勾选后选中机构时级联显示下级机构的科室; 默认仅显示选中机构本身的科室" style="margin-left:4px;">含下级机构</el-checkbox>',
      '    <el-button :loading="exporting" @click="exportRows">导出</el-button>',
      '    <el-popover placement="bottom" :width="180" trigger="click">',
      '      <template #reference><el-button link type="primary" size="small" style="margin-left:6px;">列设置</el-button></template>',
      '      <div style="max-height:260px;overflow:auto;">',
      '        <el-checkbox v-for="c in colDefs" :key="c.key" :model-value="colShow(c.key)" @change="onColToggle(c.key, $event)" style="display:block;margin:2px 0;">{{ c.label }}</el-checkbox>',
      '      </div>',
      '    </el-popover>',
      '    <el-radio-group v-model="paged" size="small" @change="onPagedToggle" title="右侧明细显示模式: 全量=树形展示所有行; 分页=按明细行分页平铺展示" style="margin-left:6px;">',
      '      <el-radio-button :label="false">全量</el-radio-button>',
      '      <el-radio-button :label="true">分页</el-radio-button>',
      '    </el-radio-group>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">点击左侧机构查看其科室 · {{ countHint }}</span></div>',
      '  <div class="table-box">',
      '  <el-table :data="pagedTree" v-loading="loading" border row-key="id" :tree-props="{children:\'children\'}" default-expand-all size="small" height="100%">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="60"></el-table-column>',
      '    <el-table-column prop="deptName" label="科室名称" min-width="200"></el-table-column>',
      '    <el-table-column v-if="colShow(\'code\')" prop="deptCode" label="编码" width="90"></el-table-column>',
      '    <el-table-column v-if="colShow(\'pinyin\')" prop="pyCode" label="拼音码" width="90"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'abbr\')" prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'level\') || paged" label="层级" width="90"><template #default="s"><el-tag size="small" :type="levelTagType(s.row.deptLevel)">{{ levelLabel(s.row.deptLevel) }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="colShow(\'cat\')" label="所属大类" width="150" show-overflow-tooltip><template #default="s">{{ catFmt(s.row.deptCategory) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'org\')" label="所属机构" min-width="140" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'type\')" label="类型" width="70"><template #default="s">{{ typeFromCat(s.row.deptCategory) || s.row.deptType || \'-\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'caty\')" label="医保科别" width="130" show-overflow-tooltip><template #default="s">{{ s.row.deptCatyName || catyMap[s.row.deptCaty] || s.row.deptCaty || \'-\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'status\')" label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="colShow(\'clinic\')" label="门诊开诊" width="90" title="仅开诊的门诊科室参与排班与挂号"><template #default="s"><el-tag v-if="catHas(s.row.deptCategory,\'门诊科室\')" size="small" :type="s.row.openClinic===0?\'info\':\'success\'">{{ s.row.openClinic===0?"未开诊":"开诊" }}</el-tag><span v-else style="color:var(--yb-ink-4);">-</span></template></el-table-column>',
      '    <el-table-column label="操作" min-width="190" fixed="right"><template #default="s">',
      '      <el-button v-if="lead" link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-button v-if="lead" link type="success" @click="addChild(s.row)">新增下级</el-button>',
      '      <el-popconfirm v-if="lead" title="确认删除该科室？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '      <span v-if="!lead" style="color:var(--yb-ink-2);font-size:12px;">只读</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  </div>',
      '  <div v-if="paged" class="pager">',
      '    <el-pagination background layout="total, sizes, prev, pager, next" :total="flatRows.length" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      '    </div>',
      '  </div>',
      /* 长表单弹窗双栏化(2026-10-03): 18 字段单列直排超屏需滚到底才见按钮, 改双栏+加宽+上移, 与编辑职工弹窗同范式 */
      '  <el-dialog v-model="dlg" custom-class="form-dialog-scroll" :title="editing?\'编辑科室\':\'新增科室\'" width="680px" top="6vh">',
      '    <el-form :model="form" label-width="92px" size="default">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="上级科室"><el-select v-model="form.parentId" filterable style="width:100%" placeholder="上级科室(拼音简码可搜)" :filter-method="kwFilter(\'dParent\')" @change="onParentChange"><el-option v-for="o in kwOptions(\'dParent\', parentOptions, [\'label\',\'pyCode\',\'abbrCode\'])" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="科室大类"><el-select v-model="form.deptCategory" multiple clearable style="width:100%" placeholder="多选:门诊/住院/护理/医技/行政"><el-option v-for="c in deptCategories" :key="c" :label="c" :value="c"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="层级"><el-select v-model="form.deptLevel" style="width:100%" :disabled="form.parentId !== 0 && form.parentId != null" :title="form.parentId !== 0 && form.parentId != null ? \'已选上级, 层级由上级自动推导(#3)\' : \'\'"><el-option v-for="l in deptLevels" :key="l.v" :label="l.v+\'-\'+l.l" :value="l.v"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="所属机构"><el-select v-model="form.orgId" clearable filterable style="width:100%" placeholder="医共体内机构(可空; 拼音可搜)" :filter-method="kwFilter(\'dOrg\')" @change="onDeptOrgChange"><el-option v-for="o in kwOptions(\'dOrg\', orgs, [\'label\',\'orgCode\',\'pyCode\'])" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="科室编码"><el-input v-model="form.deptCode"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="科室名称"><el-input v-model="form.deptName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="form.pyCode" disabled placeholder="保存时按名称自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="form.abbrCode" maxlength="64" placeholder="选填, 人工简码纠正多音字"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="科室类型"><span style="color:var(--yb-ink-2);">{{ typeFromCat(form.deptCategory) || form.deptType || \'-\' }}</span><span style="color:var(--yb-ink-4);font-size:12px;margin-left:8px;">由主大类派生</span></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保科别"><el-select v-model="form.deptCaty" filterable clearable style="width:100%" placeholder="院内科室→医保科室映射(2201)" :title="\'2201 dept_code+caty 均取此值\'"><el-option v-for="o in catyOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="位置描述"><el-input v-model="form.locDesc"></el-input></el-form-item></el-col>',
      /* 三期: 科室默认发药药房(区分西药/中药渠道, 需先选定所属机构; 可空=医生开方时手工选择) */
      '        <el-col :span="12"><el-form-item label="默认药房(西)"><el-select v-model="form.defPharmacyWest" clearable filterable style="width:100%" :disabled="!form.orgId" placeholder="西药/中成药(可空)"><el-option v-for="p in pharmacyOpts" :key="p.id" :label="p.name" :value="p.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="默认药房(中)"><el-select v-model="form.defPharmacyTcm" clearable filterable style="width:100%" :disabled="!form.orgId" placeholder="中药饮片(可空)"><el-option v-for="p in pharmacyOpts" :key="p.id" :label="p.name" :value="p.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="排序号"><el-input v-model.number="form.sortNo" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item v-if="form.deptCategory && form.deptCategory.indexOf(\'门诊科室\')>=0" label="门诊开诊" title="仅开诊的门诊科室会出现在排班/挂号科室下拉"><el-switch v-model="form.openClinic" :active-value="1" :inactive-value="0" active-text="开诊" inactive-text="未开诊"></el-switch><span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">未开诊的门诊科室不参与排班与挂号</span></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.memo" type="textarea" :rows="1"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 职工管理 ================= */
  HIS.views.StaffManage = {
    components: { 'dept-tree-picker': HIS.components.DeptTreePicker },
    data: function () {
      return {
        loading: false, list: [], depts: [], orgs: [], staffTypes: STAFF_TYPES,
        gendOpts: [], gendMap: {}, titleOpts: [], titleMap: {},
        pracCateOpts: [], pracCateMap: {},
        abxOpts: [], abxMap: {},
        surgeryOpts: [], surgeryMap: {},
        activeTab: 'basic',
        filterOrg: null, filterDept: null, filterType: '', filterStatus: null, keyword: '',
        /* 快速查询两项(2026-10-03): 可挂号 1/0; 处方权限快捷码 rx/narcotic/psych1/psych2/abx/abx1-3(与后端 applyRxAuth 同口径) */
        filterReg: null, filterRx: '',
        /* 选上级科室时是否级联显示下级科室人员(默认开, 关=仅精确匹配选中科室) */
        withKids: true,
        /* 选上级机构时是否级联显示下级机构人员(默认开, 关=仅精确匹配选中机构; 仅牵头机构生效) */
        withSubOrgs: true,
        exporting: false,
        /* 服务端筛选结果全集(不参与分页): 供左栏节点人数统计; 无统计时置 null 不显示角标 */
        allRows: null,
        /* 左栏树过滤关键字(按机构/科室名, 命中节点连同其父级链保留显示) */
        treeKw: '',
        /* 右侧明细显示模式: 默认由租户参数 system.list_default_paged 控制, 用户手动切换后以本地偏好为准 */
        paged: HIS.pagedDefault('staffPaged2'),
        page: 1, size: 20,
        /* 左栏机构/科室树收缩态(localStorage 持久化, 与科室管理同策略) */
        orgsCollapsed: (function () { try { return localStorage.getItem('his.staffOrgsCollapsed') === '1'; } catch (e) { return false; } })(),
        /* 左栏分支折叠态: key(org-Id/dept-Id)→true=已折叠 */
        folded: {},
        /* 列设置: 低频列默认隐藏(pinyin拼音码/abbr自定义码/atddr主治医师编码/fee挂号费/sort排序), localStorage 持久化 */
        colDefs: [
          { key: 'pinyin', label: '拼音码' }, { key: 'abbr', label: '自定义码' }, { key: 'cat', label: '类别' },
          { key: 'dept', label: '科室' }, { key: 'org', label: '归属机构' }, { key: 'atddr', label: '主治医师编码' },
          { key: 'reg', label: '可挂号' }, { key: 'fee', label: '挂号费' }, { key: 'status', label: '状态' }, { key: 'rx', label: '处方权限' }
        ],
        colOff: (function () {
          try { return JSON.parse(localStorage.getItem('his.staffCols') || '{"abbr":1,"atddr":1,"fee":1}'); } catch (e) { return { abbr: 1, atddr: 1, fee: 1 }; }
        })(),
        lead: HIS.isLead(),
        /* 任职与授权(编辑态懒加载): 任职行 [{orgId,deptId,isPrimary}], 临调授权行 [StaffDeptGrant] */
        empRows: [], grantRows: [], empLoaded: false, empSaving: false,
        grantForm: { orgId: null, deptId: null, validFrom: null, validTo: null, remark: '' },
        /* 处方权限·按级授权明细(T2阶段5-3, 编辑态懒加载) */
        rxAuthRows: [], rxAuthLoaded: false, rxAuthSaving: false, rxAuthForm: this.emptyRxAuth(),
        dlg: false, editing: false, form: this.empty()
      };
    },
    created: function () {
      var vm = this;
      /* 非牵头: 锁定本机构(后端亦强制), 只读 */
      if (!vm.lead) { vm.filterOrg = (HIS.getUser() || {}).orgId || null; }
      vm.loadDicts(); vm.load(); vm.loadCounts();
      /* 首屏默认折叠到科室层: 待机构+科室树就绪后一次性设定, 数百节点不再全展开(用户已手动展开则不覆盖) */
      Promise.all([vm.loadDepts(), vm.loadOrgs()]).then(function () {
        if (!Object.keys(vm.folded).length) { vm.collapseToLevel(3); }
      });
    },
    computed: {
      /* 表单科室下拉按所选机构联动: 选定机构时只列该机构下科室 */
      formDepts: function () {
        var f = this.form;
        if (!f.orgId) { return this.depts; }
        return this.depts.filter(function (d) { return d.orgId === f.orgId; });
      },
      /* 科室索引(仅随 depts 变化重算): 按机构分组 + 按(机构:父科室)分组子节点 + id 直查,
         将 leftNodes/branchList 的 O(机构×科室^2) 重复 filter/some 降为近似 O(科室) */
      deptIndex: function () {
        var byId = {}; var byOrg = {}; var childrenOf = {};
        this.depts.forEach(function (d) {
          byId[d.id] = d;
          (byOrg[d.orgId] = byOrg[d.orgId] || []).push(d);
          if (d.parentId) { var k = d.orgId + ':' + d.parentId; (childrenOf[k] = childrenOf[k] || []).push(d); }
        });
        return { byId: byId, byOrg: byOrg, childrenOf: childrenOf };
      },
      /* 左栏组合层级: 全部机构 → 机构(按 org_level 缩进) → 其下科室树(按 dept_level 缩进);
         有子级的节点带 caret 可折叠(folded 记录), 点节点本身仍为筛选 */
      leftNodes: function () {
        var vm = this; var idx = vm.deptIndex;
        /* 搜索态强制展平整棵树(命中分支不因手动折叠而被隐藏); 非搜索态尊重 folded */
        var ignoreFold = !!String(vm.treeKw || '').trim();
        var isFold = function (k) { return !ignoreFold && vm.folded[k]; };
        var rootsOf = function (mine) {
          return mine.filter(function (d) { var p = d.parentId ? idx.byId[d.parentId] : null; return !p || p.orgId !== d.orgId; });
        };
        var out = [{ type: 'all', id: null, label: '全部机构', pad: 0, kids: false, key: 'all' }];
        vm.orgs.forEach(function (o) {
          var base = (o.orgLevel || 1) - 1;
          var mine = idx.byOrg[o.id] || [];
          var orgKey = 'org-' + o.id;
          out.push({ type: 'org', id: o.id, label: String(o.label).trim(), code: o.orgCode, pyCode: o.pyCode, pad: base, kids: mine.length > 0, key: orgKey });
          if (!mine.length || isFold(orgKey)) { return; }
          (function walk(parentId, depth) {
            var list = parentId === 0 ? rootsOf(mine) : (idx.childrenOf[o.id + ':' + parentId] || []);
            list.forEach(function (d) {
              var dk = 'dept-' + d.id;
              var kids = idx.childrenOf[o.id + ':' + d.id] || [];
              out.push({ type: 'dept', id: d.id, orgId: o.id, parentId: d.parentId, label: d.deptName, code: d.deptCode, pyCode: d.pyCode, abbr: d.abbrCode, pad: base + 1 + depth, kids: kids.length > 0, key: dk });
              if (kids.length && !isFold(dk)) { walk(d.id, depth + 1); }
            });
          })(0, 0);
        });
        return out;
      },
      /* 左栏树(应用过滤关键字): 命中节点(名称/编码/拼音码/自定义码)保留, 并连同父级机构/科室链一起显示;
         已选中的作用域节点(机构/科室)始终保留不被过滤掉 */
      filterTree: function () {
        var vm = this;
        var kw = String(vm.treeKw || '').trim().toLowerCase();
        var nodes = vm.leftNodes;
        if (!kw) { return nodes; }
        var keep = {};
        var byKey = {};
        nodes.forEach(function (n) { byKey[n.key] = n; });
        /* 标记节点及其父级链(已标记则其祖先必已标记, 提前停止) */
        var markChain = function (n) {
          while (n && !keep[n.key]) {
            keep[n.key] = 1;
            n = n.type === 'dept' ? (n.parentId ? byKey['dept-' + n.parentId] : byKey['org-' + n.orgId]) : null;
          }
        };
        var hit = function (v) { return v && String(v).toLowerCase().indexOf(kw) >= 0; };
        nodes.forEach(function (n) {
          if (hit(n.label) || hit(n.code) || hit(n.pyCode) || hit(n.abbr)) { markChain(n); }
        });
        /* 作用域可见性: 仅当已选定机构/科室时保留其选中节点(未选定时"全部机构"不强制保留, 以便零命中空态生效) */
        if (vm.filterOrg || vm.filterDept) {
          nodes.forEach(function (n) { if (vm.nodeActive(n)) { markChain(n); } });
        }
        return nodes.filter(function (n) { return keep[n.key]; });
      },
      /* 筛选条件回显: 非"全部机构"时显示当前作用域(机构 ▸ 科室), 点 × 一键清除 */
      chipLabel: function () {
        if (!this.filterOrg && !this.filterDept) { return ''; }
        var s = this.orgName(this.filterOrg);
        if (this.filterDept) { s += ' ▸ ' + this.deptName(this.filterDept); }
        return s;
      },
      /* 是否处于树过滤态(决定 leftNodes 强制展平与 caret 是否可点) */
      searching: function () { return !!String(this.treeKw || '').trim(); },
      /* 分页模式按当前列表切片; 全量模式直接返完整列表 */
      pagedList: function () {
        if (!this.paged) { return this.list; }
        return this.list.slice((this.page - 1) * this.size, this.page * this.size);
      },
      /* 左栏节点人数角标索引: 基于全集一次性统计(机构含下级机构累计, 科室含子科室累计); 未拉全集时返回 null 不显示 */
      nodeCountMap: function () {
        var vm = this;
        if (!vm.allRows) { return null; }
        var m = { all: vm.allRows.length };
        var deptById = vm.deptIndex.byId;
        /* 机构父级索引: flattenOrgs 为前序展平, 向前最近的更小 orgLevel 即父机构 */
        var orgParent = {};
        for (var i = 0; i < vm.orgs.length; i++) {
          for (var j = i - 1; j >= 0; j--) {
            if ((vm.orgs[j].orgLevel || 1) < (vm.orgs[i].orgLevel || 1)) { orgParent[vm.orgs[i].id] = vm.orgs[j].id; break; }
          }
        }
        vm.allRows.forEach(function (s) {
          var d = deptById[s.deptId];
          /* 科室链逐级累计(含子科室); 链顶科室的 orgId 即机构节点 */
          var orgId = 0; var seen = {};
          /* 环/自引用守卫: 科室父链遇重复 id 立即停止, 避免脏数据导致整页死循环卡死 */
          while (d && !seen[d.id]) {
            seen[d.id] = 1;
            m['dept-' + d.id] = (m['dept-' + d.id] || 0) + 1;
            if (!d.parentId && d.orgId) { orgId = d.orgId; }
            d = d.parentId ? deptById[d.parentId] : null;
          }
          if (!orgId) { orgId = s.orgId; }
          /* 机构沿父链上卷, 上级机构含下级机构累计 */
          while (orgId) {
            m['org-' + orgId] = (m['org-' + orgId] || 0) + 1;
            orgId = orgParent[orgId];
          }
        });
        return m;
      }
    },
    methods: {
      loadDicts: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'gend').then(function (l) { vm.gendOpts = l || []; vm.gendMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('wst364', 'CV08.30.005').then(function (l) { vm.titleOpts = l || []; vm.titleMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        HIS.stdValues('whvalue', 'CT98.00.024').then(function (l) { vm.pracCateOpts = l || []; vm.pracCateMap = HIS.dictMap(l); }).catch(HIS.notifyError);
        /* 抗菌药物处方权级别字典(hbvalue:HBCV08.50.029), 排除"非抗菌药物"(0) */
        HIS.stdValues('hbvalue', 'HBCV08.50.029').then(function (l) {
          var arr = (l || []).filter(function (o) { return String(o.code) !== '0'; });
          vm.abxOpts = arr; vm.abxMap = HIS.dictMap(arr);
        }).catch(HIS.notifyError);
        /* 手术级别权限字典(cv_code:oprn_lv_code: 1一级/2二级/3三级/4四级/5其他), 排除"其他"(5) */
        HIS.stdValues('cv_code', 'oprn_lv_code').then(function (l) {
          var arr = (l || []).filter(function (o) { return String(o.code) !== '5'; });
          vm.surgeryOpts = arr; vm.surgeryMap = HIS.dictMap(arr);
        }).catch(HIS.notifyError);
      },
      empty: function () {
        return { id: null, staffNo: '', staffName: '', pyCode: '', abbrCode: '', staffType: '医师', gender: '1', titleCode: '', titleName: '', deptId: null, orgId: null, atddrNo: '', diseDorNo: '', idCard: '', birthDate: '', medInsurCode: '', pracCate: '', drQualCertNo: '', pracCertNo: '', qualIntro: '', phone: '', canRegister: 0, regFee: 0, sortNo: 0, status: 1, memo: '', avatarUrl: '', signImgUrl: '', rxRight: 0, narcoticRight: 0, psych1Right: 0, psych2Right: 0, antibioticLevel: '', surgeryLevel: '', rxAuthOrg: '', rxAuthNo: '', rxAuthDate: '', rxValidUntil: '' };
      },
      loadDepts: function () { var vm = this; return HIS.get('/api/his/dept/enabled').then(function (d) { vm.depts = d || []; }).catch(HIS.notifyError); },
      onOrgChange: function () {
        var vm = this;
        if (vm.form.deptId && vm.formDepts.every(function (d) { return d.id !== vm.form.deptId; })) { vm.form.deptId = null; }
      },
      loadOrgs: function () {
        var vm = this;
        return HIS.get('/api/sys/org/tree').then(function (d) {
          vm.orgs = HIS.flattenOrgs(d || []);
          /* 非牵头: 左栏仅列本机构(后端读隔离亦强制) */
          if (!vm.lead) { vm.orgs = vm.orgs.filter(function (o) { return o.id === vm.filterOrg; }); }
        }).catch(function () { vm.orgs = []; });
      },
      /* 左栏点选: 机构节点=按机构筛, 科室节点=按科室筛(连带机构) */
      selectNode: function (n) {
        if (n.type === 'dept') { this.filterOrg = n.orgId; this.filterDept = n.id; }
        else { this.filterOrg = n.id; this.filterDept = null; }
        this.load();
      },
      nodeActive: function (n) {
        if (n.type === 'dept') { return this.filterDept === n.id; }
        if (n.type === 'org') { return this.filterOrg === n.id && !this.filterDept; }
        return !this.filterOrg && !this.filterDept;
      },
      /* 树节点标签高亮: 先转义 HTML 再将命中子串包入 <mark>(大小写不敏感) */
      hl: function (text) {
        var t = String(text == null ? '' : text);
        var esc = function (s) { return s.replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); };
        var kw = String(this.treeKw || '').trim();
        if (!kw) { return esc(t); }
        var lower = t.toLowerCase(); var k = kw.toLowerCase(); var out = ''; var i = 0;
        for (;;) {
          var idx = lower.indexOf(k, i);
          if (idx < 0) { out += esc(t.slice(i)); break; }
          out += esc(t.slice(i, idx)) + '<mark class="kw-hit">' + esc(t.slice(idx, idx + kw.length)) + '</mark>';
          i = idx + kw.length;
        }
        return out;
      },
      /* 左栏收缩/展开切换并持久化 */
      toggleOrgs: function () {
        this.orgsCollapsed = !this.orgsCollapsed;
        try { localStorage.setItem('his.staffOrgsCollapsed', this.orgsCollapsed ? '1' : '0'); } catch (e) { }
      },
      /* 折叠/展开左栏分支(仅显隐子级, 不触发筛选) */
      toggleFold: function (n) { this.folded[n.key] = !this.folded[n.key]; },
      /* 收集所有"有子级"分支节点及其显示层级 pad(与 leftNodes 展平口径一致, 忽略 folded 全展开), 供按层级批量收展 */
      branchList: function () {
        var vm = this; var idx = vm.deptIndex; var arr = [];
        var rootsOf = function (mine) {
          return mine.filter(function (d) { var p = d.parentId ? idx.byId[d.parentId] : null; return !p || p.orgId !== d.orgId; });
        };
        vm.orgs.forEach(function (o) {
          var base = (o.orgLevel || 1) - 1;
          var mine = idx.byOrg[o.id] || [];
          if (mine.length) { arr.push({ key: 'org-' + o.id, pad: base }); }
          (function walk(parentId, depth) {
            var list = parentId === 0 ? rootsOf(mine) : (idx.childrenOf[o.id + ':' + parentId] || []);
            list.forEach(function (d) {
              var kids = idx.childrenOf[o.id + ':' + d.id] || [];
              if (kids.length) { arr.push({ key: 'dept-' + d.id, pad: base + 1 + depth }); walk(d.id, depth + 1); }
            });
          })(0, 0);
        });
        return arr;
      },
      /* 批量展开/收缩: 搜索态会强制展平, 故先清空关键字确保操作即时可见 */
      expandAllTree: function () { this.treeKw = ''; this.folded = {}; },
      /* 全部收缩: 仅显示"全部机构"+顶级机构行(折叠所有分支) */
      collapseAllTree: function () {
        this.treeKw = '';
        var f = {}; this.branchList().forEach(function (b) { f[b.key] = true; }); this.folded = f;
      },
      /* 展开到第 n 级(1-based 显示层级=pad+1): 折叠 pad>=n-1 的分支即隐藏更深层; n=0/空=全部展开 */
      collapseToLevel: function (n) {
        this.treeKw = '';
        if (!n) { this.folded = {}; return; }
        var cap = n - 1; var f = {};
        this.branchList().forEach(function (b) { if (b.pad >= cap) { f[b.key] = true; } });
        this.folded = f;
      },
      load: function () {
        var vm = this; vm.loading = true;
        var q = '/api/his/staff/list?1=1';
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg + '&withSubOrgs=' + vm.withSubOrgs; }
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept + '&withChildren=' + vm.withKids; }
        if (vm.filterType) { q += '&staffType=' + encodeURIComponent(vm.filterType); }
        if (vm.filterStatus !== '' && vm.filterStatus != null) { q += '&status=' + vm.filterStatus; }
        if (vm.filterReg !== '' && vm.filterReg != null) { q += '&canRegister=' + vm.filterReg; }
        if (vm.filterRx) { q += '&rxAuth=' + encodeURIComponent(vm.filterRx); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        HIS.get(q).then(function (d) {
          vm.list = d || []; vm.page = 1;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* 左栏人数角标全集来源: 独立于右表筛选, 拉一次不带任何筛选的列表(后端按登录机构作用域隔离);
         新增/编辑/删除后需重调, 保证角标与"全部机构"总数随数据变化刷新 */
      loadCounts: function () {
        var vm = this;
        HIS.get('/api/his/staff/list?1=1').then(function (d) { vm.allRows = d || []; }).catch(function () { });
      },
      /* 一键清除左栏筛选回到"全部机构" */
      chipClear: function () { this.filterOrg = null; this.filterDept = null; this.load(); },
      /* 列设置: 显隐开关(持久化) */
      colShow: function (key) { return !this.colOff[key]; },
      /* 复选框代"显示"语义: 勾选=显示(colOff 置0), 取消=隐藏(colOff 置1) */
      onColToggle: function (key, shown) { this.colOff[key] = shown ? 0 : 1; this.onColChange(); },
      onColChange: function () {
        try { localStorage.setItem('his.staffCols', JSON.stringify(this.colOff)); } catch (e) { }
      },
      /* 类别标签配色: 医师/药师/护士/技师区分显示, 未知类别回退灰 */
      typeTag: function (t) {
        return { '医师': 'primary', '药师': 'success', '护士': 'warning', '技师': 'info' }[t] || 'info';
      },
      /* 分页/全量模式切换(持久化): 超阈弹确认软提示, 用户确认后仍可全量; 阈值读租户参数 */
      onPagedToggle: function () {
        var vm = this;
        var threshold = (HIS.params && HIS.params.listFullThreshold) || 2000;
        if (!this.paged && this.list.length > threshold) {
          ElementPlus.ElMessageBox.confirm(
            '当前共 ' + this.list.length + ' 行，全量显示可能卡顿数秒，是否继续？',
            '提示', { confirmButtonText: '继续全量', cancelButtonText: '保持分页', type: 'warning' }
          ).then(function () {
            vm.page = 1;
            try { localStorage.setItem('his.staffPaged2', '0'); } catch (e) { }
          }).catch(function () {
            vm.paged = true;
            try { localStorage.setItem('his.staffPaged2', '1'); } catch (e) { }
          });
          return;
        }
        this.page = 1;
        try { localStorage.setItem('his.staffPaged2', this.paged ? '1' : '0'); } catch (e) { }
      },
      onPage: function (p) { this.page = p; },
      onSize: function (s) { this.size = s; this.page = 1; },
      /* 序号列: 分页模式跨页连续编号 */
      seqNo: function (i) { return this.paged ? (this.page - 1) * this.size + i + 1 : i + 1; },
      /* 导出(xlsx): 沿用当前机构/科室/类别/关键字及两个级联开关筛选, 导出全部匹配行 */
      exportRows: function () {
        var vm = this;
        var q = '/api/his/staff/export?1=1';
        if (vm.filterOrg) { q += '&orgId=' + vm.filterOrg + '&withSubOrgs=' + vm.withSubOrgs; }
        if (vm.filterDept) { q += '&deptId=' + vm.filterDept + '&withChildren=' + vm.withKids; }
        if (vm.filterType) { q += '&staffType=' + encodeURIComponent(vm.filterType); }
        if (vm.filterStatus !== '' && vm.filterStatus != null) { q += '&status=' + vm.filterStatus; }
        if (vm.filterReg !== '' && vm.filterReg != null) { q += '&canRegister=' + vm.filterReg; }
        if (vm.filterRx) { q += '&rxAuth=' + encodeURIComponent(vm.filterRx); }
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        vm.exporting = true;
        HIS.download(q).then(function (name) {
          HIS.notifySuccess('已导出 ' + vm.list.length + ' 条: ' + name);
        }).catch(HIS.notifyError).finally(function () { vm.exporting = false; });
      },
      deptName: function (id) { for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } } return '-'; },
      orgName: function (id) { for (var i = 0; i < this.orgs.length; i++) { if (this.orgs[i].id === id) { return String(this.orgs[i].label).trim(); } } return '-'; },
      add: function () {
        this.editing = false; this.form = this.empty(); this.activeTab = 'basic';
        this.empRows = []; this.grantRows = []; this.empLoaded = false;
        this.rxAuthRows = []; this.rxAuthLoaded = false;
        /* 左栏已选中机构/科室时默认带入 */
        if (this.filterOrg) { this.form.orgId = this.filterOrg; }
        if (this.filterDept) { this.form.deptId = this.filterDept; }
        this.dlg = true;
      },
      edit: function (row) { this.editing = true; this.form = clean(Object.assign(this.empty(), row)); this.activeTab = 'basic'; this.empRows = []; this.grantRows = []; this.empLoaded = false; this.rxAuthRows = []; this.rxAuthLoaded = false; this.dlg = true; },
      /* 保存前基础格式校验: 仅拦截明确的格式错误, 对选填/历史宽松值不误伤 */
      validateForm: function () {
        var f = this.form;
        if (!String(f.staffNo || '').trim()) { return '工号必填'; }
        if (!String(f.staffName || '').trim()) { return '姓名必填'; }
        var idc = String(f.idCard || '').trim();
        if (idc && !/^\d{17}[0-9Xx]$/.test(idc)) { return '身份证号格式不正确(应为18位)'; }
        var ph = String(f.phone || '').trim();
        if (ph && !/^[0-9+\-() ]{6,20}$/.test(ph)) { return '联系电话格式不正确'; }
        if (f.regFee !== '' && f.regFee != null && (isNaN(Number(f.regFee)) || Number(f.regFee) < 0)) { return '挂号费须为不小于0的数字'; }
        if (f.birthDate && String(f.birthDate) > new Date().toISOString().slice(0, 10)) { return '出生日期不能晚于今天'; }
        return null;
      },
      submit: function () {
        var vm = this;
        var err = vm.validateForm();
        if (err) { ElementPlus.ElMessage.warning(err); return; }
        var p = vm.editing ? HIS.put('/api/his/staff', vm.form) : HIS.post('/api/his/staff', vm.form);
        p.then(function () { HIS.notifySuccess('保存成功'); vm.dlg = false; vm.load(); vm.loadCounts(); }).catch(HIS.notifyError);
      },
      del: function (row) { var vm = this; HIS.del('/api/his/staff/' + row.id).then(function () { HIS.notifySuccess('已删除'); vm.load(); vm.loadCounts(); }).catch(HIS.notifyError); },
      /* ===== 处方权限·按级授权明细(T2阶段5-3): 抗菌各分级/麻醉/精一/精二 分别授权与独立有效期 ===== */
      emptyRxAuth: function () { return { authKind: 'abx', authCode: '', validFrom: null, validUntil: null, authOrg: '', authNo: '', memo: '' }; },
      onRxKindChange: function () { this.rxAuthForm.authCode = (this.rxAuthForm.authKind === 'abx') ? '' : '1'; },
      loadRxAuth: function () {
        var vm = this;
        HIS.get('/api/his/staff/' + vm.form.id + '/rx-auth').then(function (d) { vm.rxAuthRows = d || []; }).catch(HIS.notifyError);
        vm.rxAuthLoaded = true;
      },
      addRxAuth: function () {
        var vm = this, f = vm.rxAuthForm;
        if (!f.authCode) { ElementPlus.ElMessage.warning('请选择权限级别/填写编码'); return; }
        if (!f.validUntil) { ElementPlus.ElMessage.warning('请填写有效期至'); return; }
        vm.rxAuthSaving = true;
        HIS.post('/api/his/staff/' + vm.form.id + '/rx-auth', { authKind: f.authKind, authCode: f.authCode, validFrom: f.validFrom, validUntil: f.validUntil, authOrg: f.authOrg, authNo: f.authNo, memo: f.memo })
          .then(function () { HIS.notifySuccess('已保存按级授权'); vm.rxAuthForm = vm.emptyRxAuth(); vm.loadRxAuth(); })
          .catch(HIS.notifyError).finally(function () { vm.rxAuthSaving = false; });
      },
      removeRxAuth: function (row) {
        var vm = this;
        HIS.del('/api/his/staff/rx-auth/' + row.id).then(function () { HIS.notifySuccess('已撤销'); vm.loadRxAuth(); }).catch(HIS.notifyError);
      },
      rxAuthKindText: function (row) { var m = { abx: '抗菌分级', narcotic: '麻醉药品', psych1: '第一类精神', psych2: '第二类精神' }; return m[row.authKind] || row.authKind; },
      rxAuthLevelText: function (row) { return row.authName || row.authCode; },
      rxAuthFromText: function (row) { return row.validFrom || '立即'; },
      rxAuthUntilText: function (row) { return row.validUntil || '长期'; },
      rxAuthStatusText: function (row) { return row.status === 1 ? '有效' : '注销'; },
      /* ===== 任职与授权(仅编辑现有职工时可维护) ===== */
      /* 切换到"任职与授权"Tab 时懒加载一次 */
      onTabChange: function (name) {
        if (name === 'employ' && this.editing && this.form.id && !this.empLoaded) { this.loadEmploy(); }
        if (name === 'rxauth' && this.editing && this.form.id && !this.rxAuthLoaded) { this.loadRxAuth(); }
      },
      loadEmploy: function () {
        var vm = this;
        if (!vm.form.id) { return; }
        HIS.get('/api/his/staff-employment/' + vm.form.id + '/employments').then(function (d) {
          vm.empRows = (d || []).map(function (e) { return { orgId: e.orgId, deptId: e.deptId, isPrimary: e.isPrimary === 1 }; });
        }).catch(HIS.notifyError);
        HIS.get('/api/his/staff-employment/' + vm.form.id + '/grants').then(function (d) {
          vm.grantRows = d || [];
        }).catch(HIS.notifyError);
        vm.empLoaded = true;
      },
      /* 按机构联动过滤可选科室 */
      empDeptsFor: function (orgId) {
        if (!orgId) { return this.depts; }
        return this.depts.filter(function (d) { return d.orgId === orgId; });
      },
      addEmploy: function () { this.empRows.push({ orgId: null, deptId: null, isPrimary: false }); },
      removeEmploy: function (i) { this.empRows.splice(i, 1); },
      /* 单选当前主选行下标(无标记时 -1, 保存时自动补首条) */
      rowPrimaryIdx: function (row) { var i = this.empRows.indexOf(row); return row && row.isPrimary ? i : -1; },
      setPrimary: function (i) {
        for (var k = 0; k < this.empRows.length; k++) { this.empRows[k].isPrimary = (k === i); }
      },
      saveEmploy: function () {
        var vm = this;
        var rows = vm.empRows.filter(function (r) { return r.orgId && r.deptId; });
        if (!rows.length) { ElementPlus.ElMessage.warning('至少维护一条有效任职(机构+科室)'); return; }
        /* 后端以首个有效行为主任职: 前端把选中标记行排到最前; 实体 isPrimary 为 Integer 列, 不随布尔传参 */
        var body = rows.slice().sort(function (a, b) { return (b.isPrimary ? 1 : 0) - (a.isPrimary ? 1 : 0); })
          .map(function (r) { return { orgId: r.orgId, deptId: r.deptId }; });
        vm.empSaving = true;
        HIS.post('/api/his/staff-employment/' + vm.form.id + '/employments', body).then(function () {
          HIS.notifySuccess('任职已保存'); vm.load(); vm.loadCounts(); vm.loadEmploy();
        }).catch(HIS.notifyError).finally(function () { vm.empSaving = false; });
      },
      addGrantRow: function () {
        var vm = this; var g = vm.grantForm;
        if (!g.orgId || !g.deptId) { ElementPlus.ElMessage.warning('请选择机构与科室'); return; }
        HIS.post('/api/his/staff-employment/' + vm.form.id + '/grants', {
          orgId: g.orgId, deptId: g.deptId, validFrom: g.validFrom || null, validTo: g.validTo || null, remark: g.remark || null
        }).then(function () {
          HIS.notifySuccess('临调授权已新增'); vm.grantForm = { orgId: null, deptId: null, validFrom: null, validTo: null, remark: '' };
          vm.loadEmploy(); vm.empLoaded = true;
        }).catch(HIS.notifyError);
      },
      removeGrantRow: function (row) {
        var vm = this;
        HIS.del('/api/his/staff-employment/grants/' + row.id).then(function () { HIS.notifySuccess('已撤销授权'); vm.loadEmploy(); }).catch(HIS.notifyError);
      },
      /* 头像上传(本地文件服务 /api/file/upload, biz=staff_avatar) */
      uploadAvatar: function (opt) {
        var vm = this;
        HIS.upload(opt.file, 'staff_avatar').then(function (d) { vm.form.avatarUrl = d.url; HIS.notifySuccess('头像已上传'); }).catch(HIS.notifyError);
      },
      /* 签名图片上传(用于电子处方/医学文档签名, biz=staff_sign) */
      uploadSign: function (opt) {
        var vm = this;
        HIS.upload(opt.file, 'staff_sign').then(function (d) { vm.form.signImgUrl = d.url; HIS.notifySuccess('签名图片已上传'); }).catch(HIS.notifyError);
      },
      /* 总处方权关闭时联动清空专项权(前端镜像服务端守护, 避免无效录入) */
      onRxRightChange: function (v) {
        if (v === 0) {
          this.form.narcoticRight = 0; this.form.psych1Right = 0; this.form.psych2Right = 0; this.form.antibioticLevel = '';
        }
      },
      /* 专项权(麻醉/精一/精二/抗菌)开启时自动具备总处方权 */
      ensureRxRight: function () {
        var f = this.form;
        if (f.narcoticRight === 1 || f.psych1Right === 1 || f.psych2Right === 1 || f.antibioticLevel) { f.rxRight = 1; }
      },
      /* 按职称建议抗菌药物处方权级别(《抗菌药物临床应用管理办法》分级授权): 高级→三级(特殊使用) 中级→二级(限制) 初级→一级(非限制); 可手工覆盖 */
      suggestAbxLevel: function () {
        var tc = String(this.form.titleCode || '');
        var lv = '';
        if (tc === '1' || tc === '2') { lv = '13'; }
        else if (tc === '3') { lv = '12'; }
        else if (tc === '4') { lv = '11'; }
        if (!lv) { ElementPlus.ElMessage.warning('请先选择职称(初级及以上方可授予抗菌药物处方权)'); return; }
        this.form.antibioticLevel = lv; this.form.rxRight = 1;
        ElementPlus.ElMessage.success('已按职称建议抗菌级别: ' + (this.abxMap[lv] || lv) + '(可手工调整)');
      },
      /* 按职称建议手术级别权限(《医疗技术临床应用管理办法》手术分级授权): 正高→四级 副高→三级 中级→二级 初级→一级; 可手工覆盖 */
      suggestSurgeryLevel: function () {
        var tc = String(this.form.titleCode || '');
        var lv = '';
        if (tc === '1') { lv = '4'; }
        else if (tc === '2') { lv = '3'; }
        else if (tc === '3') { lv = '2'; }
        else if (tc === '4') { lv = '1'; }
        if (!lv) { ElementPlus.ElMessage.warning('请先选择职称(初级及以上方可授予手术级别权限)'); return; }
        this.form.surgeryLevel = lv;
        ElementPlus.ElMessage.success('已按职称建议手术级别: ' + (this.surgeryMap[lv] || lv) + '级(可手工调整)');
      },
      /* 列表页处方权限标签 */
      rxTags: function (row) {
        var a = [];
        if (row.rxRight === 1) { a.push({ t: '处方', c: 'success' }); }
        if (row.narcoticRight === 1) { a.push({ t: '麻醉', c: 'danger' }); }
        if (row.psych1Right === 1) { a.push({ t: '精一', c: 'danger' }); }
        if (row.psych2Right === 1) { a.push({ t: '精二', c: 'warning' }); }
        if (row.antibioticLevel) { a.push({ t: '抗菌' + (this.abxMap[row.antibioticLevel] || row.antibioticLevel), c: 'primary' }); }
        if (row.surgeryLevel) { a.push({ t: '手术' + (this.surgeryMap[row.surgeryLevel] || row.surgeryLevel) + '级', c: 'success' }); }
        return a;
      }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">职工管理</div>',
      '  <el-alert v-if="!lead" type="warning" :closable="false" show-icon style="margin-bottom:10px;" title="非牵头机构: 仅展示本机构职工, 只读不可维护。"></el-alert>',
      '  <div class="dept-split">',
      '    <div class="dept-orgs" :class="{collapsed: orgsCollapsed}">',
      '      <div class="dept-orgs-hd">',
      '        <span v-show="!orgsCollapsed">机构 / 科室</span>',
      '        <span v-show="orgsCollapsed" class="dept-orgs-vt">机构科室</span>',
      '        <el-button link size="small" class="dept-orgs-tg" :title="orgsCollapsed?\'展开机构科室树\':\'收缩机构科室树\'" @click="toggleOrgs">{{ orgsCollapsed ? "\u00bb" : "\u00ab" }}</el-button>',
      '      </div>',
      '      <div v-show="!orgsCollapsed" style="padding:6px 8px 0;">',
      '        <el-input v-model="treeKw" size="small" clearable placeholder="过滤机构/科室名"></el-input>',
      '        <div class="dept-tree-tools">',
      '          <span class="dept-tree-tools-lb">层级</span>',
      '          <el-button link size="small" class="dept-tree-btn" title="展开全部层级" @click="expandAllTree">展开</el-button>',
      '          <el-dropdown trigger="click" class="dept-tree-drop" @command="collapseToLevel">',
      '            <el-button link size="small" class="dept-tree-btn" title="展开/收缩到指定层级">到层级\u25be</el-button>',
      '            <template #dropdown>',
      '              <el-dropdown-menu>',
      '                <el-dropdown-item :command="1">一级（仅机构）</el-dropdown-item>',
      '                <el-dropdown-item :command="2">二级（大类）</el-dropdown-item>',
      '                <el-dropdown-item :command="3">三级（科室）</el-dropdown-item>',
      '                <el-dropdown-item :command="4">四级（诊室/窗口）</el-dropdown-item>',
      '                <el-dropdown-item :command="5">五级</el-dropdown-item>',
      '              </el-dropdown-menu>',
      '            </template>',
      '          </el-dropdown>',
      '          <el-button link size="small" class="dept-tree-btn" title="全部收缩(仅显示机构)" @click="collapseAllTree">收缩</el-button>',
      '        </div>',
      '      </div>',
      '      <el-scrollbar v-show="!orgsCollapsed">',
      '        <div v-for="n in filterTree" :key="n.type + \'-\' + n.id" class="dept-org-item" :class="{active: nodeActive(n), \'is-head\': n.type !== \'dept\'}" :style="{paddingLeft: (8 + n.pad * 14) + \'px\'}" @click="selectNode(n)"><span v-if="n.kids" class="tree-caret" :class="{\'is-inert\': searching}" :title="searching?\'过滤态自动展开全部层级, 清空关键字后可折叠\':\'折叠/展开\'" @click.stop="searching ? null : toggleFold(n)">{{ (searching || !folded[n.key]) ? \'▾\' : \'▸\' }}</span><span v-else class="tree-caret"></span><span v-html="hl(n.label)"></span><span v-if="nodeCountMap && nodeCountMap[n.key]" class="tree-cnt">{{ nodeCountMap[n.key] }}</span></div>',
      '        <div v-if="treeKw && !filterTree.length" class="dept-tree-empty">无匹配节点</div>',
      '      </el-scrollbar>',
      '    </div>',
      '    <div class="dept-main">',
      '  <div class="toolbar">',
      '    <el-select v-model="filterType" placeholder="全部类别" clearable style="width:130px" @change="load"><el-option v-for="t in staffTypes" :key="t" :label="t" :value="t"></el-option></el-select>',
      '    <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:100px" @change="load"><el-option label="在职" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-select v-model="filterReg" placeholder="可挂号" clearable style="width:110px" @change="load"><el-option label="可挂号" :value="1"></el-option><el-option label="不可挂号" :value="0"></el-option></el-select>',
      '    <el-select v-model="filterRx" placeholder="处方权限" clearable style="width:130px" @change="load"><el-option label="有处方权" value="rx"></el-option><el-option label="麻醉" value="narcotic"></el-option><el-option label="精一" value="psych1"></el-option><el-option label="精二" value="psych2"></el-option><el-option label="抗菌药物(任意级)" value="abx"></el-option><el-option label="抗菌一级" value="abx1"></el-option><el-option label="抗菌二级" value="abx2"></el-option><el-option label="抗菌三级" value="abx3"></el-option></el-select>',
      '    <el-input v-model="keyword" placeholder="工号/姓名/拼音简码, 回车查询" clearable style="width:190px" @keyup.enter="load" @clear="load"></el-input>',
      '    <el-checkbox v-if="lead" v-model="withSubOrgs" :disabled="!filterOrg" @change="load" title="选上级机构时级联显示下级机构人员; 取消勾选仅显示选中机构本身的人员(左侧选中机构后激活)" style="margin-left:4px;">含下级机构</el-checkbox>',
      '    <el-checkbox v-model="withKids" :disabled="!filterDept" @change="load" title="选上级科室时级联显示下级科室人员; 取消勾选仅显示选中科室本身的人员(左侧选中科室后激活)" style="margin-left:4px;">含下级科室</el-checkbox>',
      '    <el-button @click="load">查询</el-button>',
      '    <el-button :loading="exporting" @click="exportRows">导出</el-button>',
      '    <el-button v-if="lead" type="primary" @click="add">新增职工</el-button>',
      '    <span v-if="chipLabel" class="filter-chip" title="当前列表作用域, 点 × 回到全部机构">筛选: {{ chipLabel }}<b class="filter-chip-x" @click="chipClear">×</b></span>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;">共 {{ list.length }} 人</span>',
      '    <el-popover placement="bottom-end" :width="280" trigger="click">',
      '      <template #reference><el-button style="margin-left:auto;">列设置</el-button></template>',
      '      <div style="font-size:12px;color:var(--yb-ink-2);margin-bottom:6px;">勾选需要展示的列(选择跨会话保存)</div>',
      '      <el-checkbox v-for="c in colDefs" :key="c.key" :model-value="!colOff[c.key]" @change="onColToggle(c.key, $event)" style="width:120px;margin-right:0;">{{ c.label }}</el-checkbox>',
      '    </el-popover>',
      '  </div>',
      '  <div class="table-box">',
      '  <el-table :data="pagedList" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" :index="seqNo" label="序号" width="60" fixed="left"></el-table-column>',
      '    <el-table-column prop="staffNo" label="工号" width="110" sortable></el-table-column>',
      '    <el-table-column prop="staffName" label="姓名" width="100" sortable></el-table-column>',
      '    <el-table-column v-if="colShow(\'pinyin\')" prop="pyCode" label="拼音码" width="80"><template #default="s">{{ s.row.pyCode || \'—\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'abbr\')" prop="abbrCode" label="自定义码" width="90"><template #default="s">{{ s.row.abbrCode || \'—\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'cat\')" prop="staffType" label="类别" width="86" sortable><template #default="s"><el-tag size="small" :type="typeTag(s.row.staffType)">{{ s.row.staffType }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="titleName" label="职称" width="110" sortable></el-table-column>',
      '    <el-table-column v-if="colShow(\'dept\')" label="科室" width="110" show-overflow-tooltip><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'org\')" label="归属机构" min-width="150" show-overflow-tooltip><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'atddr\')" prop="atddrNo" label="主治医师编码" width="120" show-overflow-tooltip><template #default="s">{{ s.row.atddrNo || \'—\' }}</template></el-table-column>',
      '    <el-table-column v-if="colShow(\'reg\')" label="可挂号" width="80"><template #default="s"><el-tag size="small" :type="s.row.canRegister===1?\'success\':\'info\'">{{ s.row.canRegister===1?"是":"否" }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="colShow(\'fee\')" prop="regFee" label="挂号费" width="80"></el-table-column>',
      '    <el-table-column v-if="colShow(\'status\')" label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"在职":"停用" }}</el-tag></template></el-table-column>',
      '    <el-table-column v-if="colShow(\'rx\')" label="处方权限" min-width="200"><template #default="s"><template v-if="rxTags(s.row).length"><el-tag v-for="(g,i) in rxTags(s.row)" :key="i" size="small" :type="g.c" style="margin-right:4px;">{{ g.t }}</el-tag></template><span v-else style="color:var(--yb-ink-4);">—</span></template></el-table-column>',
      '    <el-table-column label="操作" width="130" fixed="right"><template #default="s">',
      '      <el-button v-if="lead" link type="primary" @click="edit(s.row)">编辑</el-button>',
      '      <el-popconfirm v-if="lead" title="确认删除该职工？" @confirm="del(s.row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>',
      '      <span v-if="!lead" style="color:var(--yb-ink-2);font-size:12px;">只读</span>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  </div>',
      '  <div class="pager">',
      '    <el-radio-group v-model="paged" size="small" @change="onPagedToggle" title="明细显示模式: 全量=一次性展示所有行; 分页=按页展示" style="margin-right:auto;">',
      '      <el-radio-button :label="false">全量</el-radio-button>',
      '      <el-radio-button :label="true">分页</el-radio-button>',
      '    </el-radio-group>',
      '    <span v-if="!paged" style="color:var(--yb-ink-2);font-size:13px;">全量展示 {{ list.length }} 条</span>',
      '    <el-pagination v-else background layout="total, sizes, prev, pager, next, jumper" :total="list.length" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '  </div>',
      '    </div>',
      '  </div>',
      '  <el-dialog v-model="dlg" :title="editing?\'编辑职工\':\'新增职工\'" width="760px" top="6vh">',
      '    <el-tabs v-model="activeTab" @tab-change="onTabChange">',
      '      <el-tab-pane label="基本信息" name="basic">',
      '        <el-form :model="form" label-width="100px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="工号"><el-input v-model="form.staffNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.staffName"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拼音码"><el-input v-model="form.pyCode" disabled placeholder="保存时按姓名自动生成"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="自定义码"><el-input v-model="form.abbrCode" maxlength="64" placeholder="选填, 人工简码"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="类别"><el-select v-model="form.staffType" style="width:100%"><el-option v-for="t in staffTypes" :key="t" :label="t" :value="t"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="性别"><el-select v-model="form.gender" style="width:100%"><el-option v-for="o in gendOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="职称"><el-select v-model="form.titleCode" style="width:100%" clearable placeholder="专业技术职务类别(CV08.30.005)"><el-option v-for="o in titleOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="所属科室"><dept-tree-picker v-model="form.deptId" :options="formDepts" :org-id="form.orgId" placeholder="选择所属科室" /></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="归属机构"><el-select v-model="form.orgId" clearable filterable style="width:100%" placeholder="医共体内共享(可选)" @change="onOrgChange"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="身份证号"><el-input v-model="form.idCard"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="头像"><el-upload :show-file-list="false" :http-request="uploadAvatar" accept="image/*"><el-button size="small">上传头像</el-button></el-upload><img v-if="form.avatarUrl" :src="form.avatarUrl" style="height:64px;margin-top:6px;border:1px solid var(--yb-border-light);border-radius:4px;"/></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="签名图片"><el-upload :show-file-list="false" :http-request="uploadSign" accept="image/*"><el-button size="small">上传签名</el-button></el-upload><img v-if="form.signImgUrl" :src="form.signImgUrl" style="height:64px;margin-top:6px;border:1px solid var(--yb-border-light);border-radius:4px;background:#fff;"/></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="默认挂号费"><el-input v-model.number="form.regFee" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="可挂号"><el-switch v-model="form.canRegister" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="排序号"><el-input v-model.number="form.sortNo" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="在职" inactive-text="停用"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="医疗权限" name="medical">',
      '        <el-form :model="form" label-width="100px">',
      '          <el-alert v-if="form.staffType!==\'医师\'" title="处方权/手术分级权限仅对医师类别适用" type="info" :closable="false" show-icon></el-alert>',
      '          <template v-else>',
      '      <el-divider content-position="left">处方权限(药事管理强管控)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="处方权"><el-switch v-model="form.rxRight" :active-value="1" :inactive-value="0" active-text="具备" inactive-text="无" @change="onRxRightChange"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="抗菌药物级别"><el-select v-model="form.antibioticLevel" clearable style="width:100%" placeholder="分级授权(HBCV08.50.029)" @change="ensureRxRight"><el-option v-for="o in abxOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label=" "><el-button size="small" @click="suggestAbxLevel">按职称建议抗菌级别</el-button><span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">高级→三级(特殊使用) 中级→二级(限制) 初级→一级(非限制)</span></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="麻醉药品"><el-switch v-model="form.narcoticRight" :active-value="1" :inactive-value="0" @change="ensureRxRight"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="第一类精神"><el-switch v-model="form.psych1Right" :active-value="1" :inactive-value="0" @change="ensureRxRight"></el-switch></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="第二类精神"><el-switch v-model="form.psych2Right" :active-value="1" :inactive-value="0" @change="ensureRxRight"></el-switch></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">手术权限(手术分级授权)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="手术级别"><el-select v-model="form.surgeryLevel" clearable style="width:100%" placeholder="可主刀最高手术级别(oprn_lv_code)"><el-option v-for="o in surgeryOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label=" "><el-button size="small" @click="suggestSurgeryLevel">按职称建议手术级别</el-button></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label=" "><span style="color:var(--yb-ink-2);font-size:12px;">正高→四级 副高→三级 中级→二级 初级→一级(《医疗技术临床应用管理办法》手术分级授权, 可手工调整)</span></el-form-item></el-col>',
      '      </el-row>',
      '      <el-divider content-position="left">授权留痕(可追溯/定期复训失效)</el-divider>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="授权机构"><el-input v-model="form.rxAuthOrg" placeholder="医务科/授权部门"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="授权文号"><el-input v-model="form.rxAuthNo" placeholder="授权文件编号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="授权日期"><el-date-picker v-model="form.rxAuthDate" type="date" value-format="YYYY-MM-DD" placeholder="YYYY-MM-DD" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="有效期至"><el-date-picker v-model="form.rxValidUntil" type="date" value-format="YYYY-MM-DD" placeholder="到期需复训考核" style="width:100%"></el-date-picker></el-form-item></el-col>',
      '      </el-row>',
      '          </template>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="从业资质" name="insur">',
      '        <el-form :model="form" label-width="110px">',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="主治医师编码"><el-input v-model="form.atddrNo" placeholder="医保 atddr_no"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医保业务编码"><el-input v-model="form.medInsurCode" placeholder="国家医保业务编码(医师/药师/护士)"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="执业类别"><el-select v-model="form.pracCate" style="width:100%" clearable filterable placeholder="医师执业类别(CT98.00.024)"><el-option v-for="o in pracCateOpts" :key="o.code" :label="o.name + \' (\' + o.code + \')\'" :value="o.code"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="医护资格证号"><el-input v-model="form.drQualCertNo" placeholder="医师/护士资格证号"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="执业证书编码"><el-input v-model="form.pracCertNo" placeholder="医师执业证书编码"></el-input></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="资质介绍"><el-input v-model="form.qualIntro" type="textarea" :autosize="{ minRows: 4, maxRows: 14 }" maxlength="1000" show-word-limit placeholder="专业特长/学术任职/从业经历等资质说明(选填)"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane v-if="editing" label="按级授权" name="rxauth">',
      '        <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px;" title="抗菌各分级/麻醉/精一/精二 可分别授权并各自设定有效期; 到期扫描优先取本明细, 无明细时回落医疗权限Tab的有效期至。"></el-alert>',
      '        <el-table :data="rxAuthRows" border size="small" style="margin-bottom:8px;">',
      '          <el-table-column label="类别" width="110"><template #default="s">{{ rxAuthKindText(s.row) }}</template></el-table-column>',
      '          <el-table-column label="级别/名称" min-width="120"><template #default="s">{{ rxAuthLevelText(s.row) }}</template></el-table-column>',
      '          <el-table-column label="生效" width="110"><template #default="s">{{ rxAuthFromText(s.row) }}</template></el-table-column>',
      '          <el-table-column label="有效期至" width="120"><template #default="s">{{ rxAuthUntilText(s.row) }}</template></el-table-column>',
      '          <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ rxAuthStatusText(s.row) }}</el-tag></template></el-table-column>',
      '          <el-table-column prop="authNo" label="文号" min-width="90" show-overflow-tooltip><template #default="s">{{ s.row.authNo || \'—\' }}</template></el-table-column>',
      '          <el-table-column label="操作" width="80" align="center"><template #default="s"><el-popconfirm title="确认撤销该授权？" @confirm="removeRxAuth(s.row)"><template #reference><el-button link type="danger" size="small">撤销</el-button></template></el-popconfirm></template></el-table-column>',
      '        </el-table>',
      '        <el-form :inline="true" size="small">',
      '          <el-form-item label="类别"><el-select v-model="rxAuthForm.authKind" style="width:130px;" @change="onRxKindChange"><el-option label="抗菌分级" value="abx"></el-option><el-option label="麻醉药品" value="narcotic"></el-option><el-option label="第一类精神" value="psych1"></el-option><el-option label="第二类精神" value="psych2"></el-option></el-select></el-form-item>',
      '          <el-form-item label="级别"><el-select v-if="rxAuthForm.authKind === \'abx\'" v-model="rxAuthForm.authCode" style="width:150px;" placeholder="分级授权"><el-option v-for="o in abxOpts" :key="o.code" :label="o.name" :value="o.code"></el-option></el-select><el-input v-else v-model="rxAuthForm.authCode" style="width:120px;" placeholder="专项编码"></el-input></el-form-item>',
      '          <el-form-item label="生效"><el-date-picker v-model="rxAuthForm.validFrom" type="date" value-format="YYYY-MM-DD" style="width:130px;" placeholder="默认立即"></el-date-picker></el-form-item>',
      '          <el-form-item label="有效期至"><el-date-picker v-model="rxAuthForm.validUntil" type="date" value-format="YYYY-MM-DD" style="width:140px;" placeholder="必填"></el-date-picker></el-form-item>',
      '          <el-form-item label="文号"><el-input v-model="rxAuthForm.authNo" style="width:120px;" placeholder="选填"></el-input></el-form-item>',
      '          <el-form-item><el-button type="primary" :loading="rxAuthSaving" @click="addRxAuth">新增授权</el-button></el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      '      <el-tab-pane v-if="editing" label="任职与授权" name="employ">',
      '        <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px;" title="任职为事实(多点执业/兼科室), 首条为主任职并回写基本信息Tab的归属机构/科室; 临调授权适用于替班/临时调配, 带生效期限。"></el-alert>',
      '        <el-table :data="empRows" border size="small" style="margin-bottom:8px;">',
      '          <el-table-column label="机构" min-width="180"><template #default="s"><el-select v-model="s.row.orgId" filterable size="small" style="width:100%;" placeholder="选择机构" @change="s.row.deptId=null"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></template></el-table-column>',
      '          <el-table-column label="科室" min-width="160"><template #default="s"><el-select v-model="s.row.deptId" filterable size="small" style="width:100%;" placeholder="选择科室"><el-option v-for="d in empDeptsFor(s.row.orgId)" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select></template></el-table-column>',
      '          <el-table-column label="主任职" width="80" align="center"><template #default="s"><el-radio :model-value="rowPrimaryIdx(s.row)" :label="empRows.indexOf(s.row)" @change="setPrimary(empRows.indexOf(s.row))"><span></span></el-radio></template></el-table-column>',
      '          <el-table-column label="操作" width="70" align="center"><template #default="s"><el-button link type="danger" size="small" @click="removeEmploy(empRows.indexOf(s.row))">移除</el-button></template></el-table-column>',
      '        </el-table>',
      '        <div style="margin-bottom:12px;"><el-button size="small" @click="addEmploy">+ 新增任职</el-button><el-button size="small" type="primary" :loading="empSaving" @click="saveEmploy">保存任职</el-button></div>',
      '        <el-divider content-position="left">临调科室授权(替班/临时调配)</el-divider>',
      '        <el-table :data="grantRows" border size="small" style="margin-bottom:8px;">',
      '          <el-table-column label="机构" min-width="150"><template #default="s">{{ orgName(s.row.orgId) }}</template></el-table-column>',
      '          <el-table-column label="科室" min-width="120"><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '          <el-table-column label="生效" width="110"><template #default="s">{{ s.row.validFrom || \'立即\' }}</template></el-table-column>',
      '          <el-table-column label="失效" width="110"><template #default="s">{{ s.row.validTo || \'长期\' }}</template></el-table-column>',
      '          <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip><template #default="s">{{ s.row.remark || \'—\' }}</template></el-table-column>',
      '          <el-table-column label="操作" width="70" align="center"><template #default="s"><el-button link type="danger" size="small" @click="removeGrantRow(s.row)">撤销</el-button></template></el-table-column>',
      '        </el-table>',
      '        <el-form :inline="true" size="small">',
      '          <el-form-item label="机构"><el-select v-model="grantForm.orgId" filterable style="width:170px;" placeholder="机构" @change="grantForm.deptId=null"><el-option v-for="o in orgs" :key="o.id" :label="o.label" :value="o.id"></el-option></el-select></el-form-item>',
      '          <el-form-item label="科室"><dept-tree-picker v-model="grantForm.deptId" :options="empDeptsFor(grantForm.orgId)" :org-id="grantForm.orgId" size="small" width="300px" placeholder="科室" /></el-form-item>',
      '          <el-form-item label="生效"><el-date-picker v-model="grantForm.validFrom" type="date" value-format="YYYY-MM-DD" style="width:130px;" placeholder="默认立即"></el-date-picker></el-form-item>',
      '          <el-form-item label="失效"><el-date-picker v-model="grantForm.validTo" type="date" value-format="YYYY-MM-DD" style="width:130px;" placeholder="默认长期"></el-date-picker></el-form-item>',
      '          <el-form-item label="事由"><el-input v-model="grantForm.remark" style="width:130px;" placeholder="替班等"></el-input></el-form-item>',
      '          <el-form-item><el-button @click="addGrantRow">新增授权</el-button></el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 排班号源(周视图 + 列表视图 + 模板管理 + 批量操作) ================= */
  /* 号别字典: 优先标准值域 cv_code:reg_level(仅名称), 默认费用按本地映射; 无字典时回退本地定义 */
  var REG_LEVEL_DEFAULTS = [
    { code: '01', name: '普通号', fee: 10 },
    { code: '02', name: '副主任医师号', fee: 20 },
    { code: '03', name: '主任医师号', fee: 30 },
    { code: '04', name: '专家号', fee: 50 },
    { code: '05', name: '特需号', fee: 100 },
    { code: '06', name: '急诊号', fee: 10 }
  ];
  var REG_LEVEL_FEE = { '01': 10, '02': 20, '03': 30, '04': 50, '05': 100, '06': 10 };
  var WEEK_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
  var WEEK_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

  HIS.views.ScheduleManage = {
    mixins: [HIS.kwSelectMixin],
    components: { 'dept-tree-picker': HIS.components.DeptTreePicker },
    data: function () {
      return {
        /* 视图模式: week=周视图矩阵 / list=列表视图 */
        viewMode: 'week', lead: HIS.isLead(), canMaintain: HIS.canMaintainSelfOrg(),
        /* 筛选: 科室联动医师(周视图按医师分行, 医师筛选仅列表视图生效) */
        filterDeptId: null, filterStaffId: null,
        depts: [], staffs: [], allStaffs: [],
        /* 周视图: weekStart 为周一日期字符串(yyyy-MM-dd, 避免响应式 Date 内置方法陷阱) */
        weekStart: '', weekData: [], weekStats: [], weekLoading: false, weekDays: [],
        /* 班次字典(his_shift_dict): 周视图列/表单时段选项, created 拉取后覆盖兜底三值 */
        shiftList: TIME_TYPES.map(function (t) { return { v: t.v, l: t.l, st: '', et: '' }; }),
        /* 列表视图 */
        listStatus: null, dateRange: null,
        listData: [], listTotal: 0, listPage: 1, listSize: 20, listLoading: false,
        selectedRows: [],
        /* 号别字典 */
        regLevels: REG_LEVEL_DEFAULTS.map(function (r) { return { code: r.code, name: r.name, fee: r.fee }; }),
        /* 新增/编辑排班 */
        formVisible: false, editing: false, form: this.empty(), formConflict: '', saving: false,
        /* 按模板生成 */
        generateVisible: false, templateList: [], selectedTemplates: [], genDateRange: null, generating: false,
        /* 批量停诊 */
        batchStopVisible: false, stopReason: '', batchStopping: false,
        /* 模板管理抽屉 */
        drawerVisible: false, tplList: [], tplTotal: 0, tplPage: 1, tplSize: 20, tplLoading: false, tplFilterDeptId: null,
        tplFormVisible: false, tplEditing: false, tplForm: this.emptyTpl(), tplSaving: false,
        /* 批量创建模板 */
        batchTplVisible: false, batchTplSaving: false,
        batchTplForm: { deptId: null, deptName: '', staffIds: [], slots: [] }
      };
    },
    created: function () {
      var vm = this;
      var ws = this.fmt(this.getMonday(new Date()));
      this.weekStart = ws;
      this.updateWeekDays();
      this.dateRange = [ws, this.addDays(ws, 6)];
      HIS.shiftDict(function (l) { vm.shiftList = l; });
      this.loadDepts();
      this.loadStaffs();
      this.loadAllStaffs();
      this.loadRegLevels();
      this.loadWeek();
    },
    computed: {
      /* 时段码有序列表(字典驱动): 周视图列循环与表单选项同源 */
      shiftCodes: function () { return this.shiftList.map(function (t) { return t.v; }); },
      /* 选项文本: 有起止时间时附带, 如 上午(08:00-12:00) */
      shiftOptLabel: function () {
        return function (t) { return t.st ? t.l + '(' + t.st + (t.et ? '-' + t.et : '') + ')' : t.l; };
      },
      weekLabel: function () {
        return this.weekStart ? (this.weekStart + ' ~ ' + this.addDays(this.weekStart, 6)) : '';
      },
      /* 批量创建模板: 按所选科室过滤医师(不选=全部医师) */
      batchStaffOptions: function () {
        if (!this.batchTplForm.deptId) { return this.allStaffs; }
        var did = this.batchTplForm.deptId;
        return this.allStaffs.filter(function (s) { return s.deptId === did; });
      },
      /* 挂号科室候选(筛选/模板下拉): 仅科室级(第2层); 诊室(第3层)不作挂号科室参与筛选, 只在「诊室」字段联动选择 */
      regDeptOptions: function () {
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
      },
      /* 排班表单挂号科室下拉: 仅本科室级(第2层); 诊室(第3层)是科室子级, 在「诊室」字段联动选择; 编辑旧数据时若当前科室不在列表则补一项避免丢失 */
      formDeptOptions: function () {
        return this.deptLevel2Options(this.form.deptId, this.form.deptName);
      },
      /* 模板表单科室下拉: 同上 */
      tplDeptOptions: function () {
        return this.deptLevel2Options(this.tplForm.deptId, this.tplForm.deptName);
      },
      /* 诊室下拉选项: 所选挂号科室(deptId)下的子级诊室(deptLevel=3) */
      formRoomOptions: function () {
        return this.roomOptionsOf(this.form.deptId);
      },
      tplRoomOptions: function () {
        return this.roomOptionsOf(this.tplForm.deptId);
      },
      batchRoomOptions: function () {
        return this.roomOptionsOf(this.batchTplForm.deptId);
      },
      /* 批量模板科室下拉: 同样仅列科室级(第2层), 诊室走槽位行诊室列 */
      batchDeptOptions: function () {
        return this.deptLevel2Options(this.batchTplForm.deptId, this.batchTplForm.deptName);
      }
    },
    methods: {
      empty: function () {
        return { id: null, deptId: null, deptName: '', staffId: null, staffName: '', workDate: '', timeType: 'am', regLevelCode: '01', regLevelName: '普通号', regFee: 10, totalNum: 30, usedNum: 0, status: 1, room: '', stopReason: '' };
      },
      emptyTpl: function () {
        return { id: null, staffId: null, staffName: '', deptId: null, deptName: '', weekday: 1, timeType: 'am', regLevelCode: '01', regLevelName: '普通号', regFee: 10, totalNum: 30, room: '', status: 1 };
      },
      emptySlot: function () {
        return { weekday: 1, timeType: 'am', regLevelCode: '01', regLevelName: '普通号', regFee: 10, totalNum: 30, room: '' };
      },
      /* ===== 日期工具(统一 yyyy-MM-dd 字符串, 内部运算用局部 Date) ===== */
      fmt: function (d) {
        return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
      },
      parseDate: function (s) {
        var p = String(s || '').split('-');
        return new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]));
      },
      addDays: function (s, n) {
        var d = this.parseDate(s); d.setDate(d.getDate() + n); return this.fmt(d);
      },
      getMonday: function (d) {
        var m = new Date(d.getFullYear(), d.getMonth(), d.getDate());
        m.setDate(m.getDate() - ((m.getDay() + 6) % 7));
        return m;
      },
      updateWeekDays: function () {
        var out = [], today = this.fmt(new Date());
        for (var i = 0; i < 7; i++) {
          var full = this.addDays(this.weekStart, i);
          var d = this.parseDate(full);
          out.push({ key: WEEK_KEYS[i], label: WEEK_LABELS[i], date: (d.getMonth() + 1) + '/' + d.getDate(), full: full, isToday: full === today });
        }
        this.weekDays = out;
      },
      weekdayOf: function (ds) {
        if (!ds) { return ''; }
        return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][this.parseDate(ds).getDay()];
      },
      /* ===== 字典与基础数据 ===== */
      /* 挂号科室候选: 开诊门诊列表中仅保留科室级(第2层) */
      deptLevel2Options: function (deptId, deptName) {
        var l2 = (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 2; });
        return this.withCurrentDept(l2, deptId, deptName);
      },
      /* 科室下挂诊室候选(第3层且 parentId 指向该科室) */
      roomOptionsOf: function (deptId) {
        if (!deptId) { return []; }
        return (this.depts || []).filter(function (d) { return Number(d.deptLevel) === 3 && d.parentId === deptId; });
      },
      withCurrentDept: function (list, deptId, deptName) {
        var arr = (list || []).slice();
        if (deptId && !arr.some(function (d) { return d.id === deptId; })) {
          arr.unshift({ id: deptId, deptName: deptName || ('科室#' + deptId) });
        }
        return arr;
      },
      loadDepts: function () {
        var vm = this;
        /* 仅本机构“开诊”的门诊科室(挂号科室): 后端硬限定登录机构, 不传 orgId */
        HIS.get('/api/his/dept/outpatient').then(function (d) { vm.depts = d || []; }).catch(function () { vm.depts = []; });
      },
      loadStaffs: function () {
        var vm = this;
        var orgId = HIS.currentOrgId();
        /* 出诊医师仅限当前登录机构本级: withSubOrgs=false 防牵头机构级联带出成员机构医师;
           canRegister=1 仅限可挂号医生方可排班出诊(2026-10-03, 与科室侧只列开诊门诊科室同口径) */
        var q = '/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&canRegister=1&withSubOrgs=false';
        if (orgId) { q += '&orgId=' + orgId; }
        if (vm.filterDeptId) { q += '&deptId=' + vm.filterDeptId; }
        HIS.get(q).then(function (d) {
          vm.staffs = (d && d.records) ? d.records : (d || []);
        }).catch(function () { vm.staffs = []; });
      },
      loadAllStaffs: function () {
        var vm = this;
        var orgId = HIS.currentOrgId();
        /* 同上: 批量模板医师候选亦仅限可挂号医生 */
        var q = '/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&canRegister=1&withSubOrgs=false';
        if (orgId) { q += '&orgId=' + orgId; }
        HIS.get(q).then(function (d) {
          vm.allStaffs = (d && d.records) ? d.records : (d || []);
        }).catch(function () { vm.allStaffs = []; });
      },
      loadRegLevels: function () {
        var vm = this;
        HIS.stdValues('cv_code', 'reg_level').then(function (l) {
          var arr = l || [];
          if (arr.length) {
            vm.regLevels = arr.map(function (o) {
              return { code: o.code, name: o.name, fee: REG_LEVEL_FEE[String(o.code)] != null ? REG_LEVEL_FEE[String(o.code)] : 10 };
            });
          }
        }).catch(function () { /* 保留本地定义 */ });
      },
      deptName: function (id) {
        if (!id) { return ''; }
        for (var i = 0; i < this.depts.length; i++) { if (this.depts[i].id === id) { return this.depts[i].deptName; } }
        return '';
      },
      /* ===== 视图切换与筛选联动 ===== */
      onViewModeChange: function () {
        if (this.viewMode === 'list') { this.listPage = 1; this.loadList(); }
        else { this.loadWeek(); }
      },
      onDeptChange: function () {
        this.filterStaffId = null;
        this.loadStaffs();
        if (this.viewMode === 'week') { this.loadWeek(); }
        else { this.listPage = 1; this.loadList(); }
      },
      /* ===== 周视图 ===== */
      loadWeek: function () {
        var vm = this;
        if (!vm.weekStart) { return; }
        vm.weekLoading = true;
        var url = '/api/his/schedule/week?weekStart=' + vm.weekStart;
        if (vm.filterDeptId) { url += '&deptId=' + vm.filterDeptId; }
        HIS.get(url).then(function (data) { vm.weekData = data || []; })
          .catch(HIS.notifyError).finally(function () { vm.weekLoading = false; });
        /* 号源统计(以周起始日为统计日期, 后端仅支持单日) */
        var su = '/api/his/schedule/stats?date=' + vm.weekStart;
        if (vm.filterDeptId) { su += '&deptId=' + vm.filterDeptId; }
        HIS.get(su).then(function (data) { vm.weekStats = data || []; }).catch(function () { vm.weekStats = []; });
      },
      prevWeek: function () { this.weekStart = this.addDays(this.weekStart, -7); this.updateWeekDays(); this.loadWeek(); },
      nextWeek: function () { this.weekStart = this.addDays(this.weekStart, 7); this.updateWeekDays(); this.loadWeek(); },
      thisWeek: function () { this.weekStart = this.fmt(this.getMonday(new Date())); this.updateWeekDays(); this.loadWeek(); },
      /* 今天列浅绿高亮, 周末列淡灰区分(第0列为固定医师列) */
      weekCellStyle: function (opt) {
        var wd = this.weekDays[opt.columnIndex - 1];
        if (!wd) { return {}; }
        if (wd.isToday) { return { background: 'var(--yb-success-bg)' }; }
        if (opt.columnIndex >= 6) { return { background: 'var(--yb-surface-2)' }; }
        return {};
      },
      /* 单元格槽位列表: 同医师同日同时段可多班次(跨科室), 后端返回数组; 兼容旧单值 */
      slotOf: function (row, wdKey, tt) {
        var v = (row.slots || {})[wdKey + '_' + tt];
        if (!v) { return []; }
        return Array.isArray(v) ? v : [v];
      },
      slotClass: function (slot) {
        return { 'is-stop': slot.status === 0, 'is-full': slot.status === 1 && Number(slot.leftNum) <= 0 };
      },
      hasAnySlot: function (row, wdKey) {
        var vm = this;
        for (var i = 0; i < vm.shiftCodes.length; i++) {
          if (vm.slotOf(row, wdKey, vm.shiftCodes[i]).length) { return true; }
        }
        return false;
      },
      /* ===== 列表视图 ===== */
      loadList: function () {
        var vm = this;
        vm.listLoading = true;
        var q = '/api/his/schedule/list?page=' + vm.listPage + '&size=' + vm.listSize;
        if (vm.filterDeptId) { q += '&deptId=' + vm.filterDeptId; }
        if (vm.filterStaffId) { q += '&staffId=' + vm.filterStaffId; }
        if (vm.listStatus !== null && vm.listStatus !== '') { q += '&status=' + vm.listStatus; }
        if (vm.dateRange && vm.dateRange.length === 2) { q += '&from=' + vm.dateRange[0] + '&to=' + vm.dateRange[1]; }
        HIS.get(q).then(function (d) {
          vm.listData = (d && d.records) || [];
          vm.listTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.listLoading = false; });
      },
      searchList: function () { this.listPage = 1; this.loadList(); },
      onSelectionChange: function (rows) { this.selectedRows = rows || []; },
      onListPage: function (p) { this.listPage = p; this.loadList(); },
      onListSize: function (s) { this.listSize = s; this.listPage = 1; this.loadList(); },
      seqNo: function (i) { return (this.listPage - 1) * this.listSize + i + 1; },
      /* ===== 新增/编辑排班 ===== */
      openAdd: function () {
        this.editing = false;
        this.form = this.empty();
        this.form.workDate = this.fmt(new Date());
        if (this.filterDeptId) { this.form.deptId = this.filterDeptId; this.form.deptName = this.deptName(this.filterDeptId); }
        this.formConflict = '';
        this.formVisible = true;
      },
      /* 周视图空白槽位快捷新增: 自动带医师/科室/日期(行首科室不在本科室可排列表时不预填) */
      openAddAt: function (row, wd) {
        this.editing = false;
        this.form = this.empty();
        this.form.staffId = row.staffId; this.form.staffName = row.staffName;
        if (row.deptId && (this.depts || []).some(function (d) { return d.id === row.deptId; })) {
          this.form.deptId = row.deptId; this.form.deptName = row.deptName;
        }
        this.form.workDate = wd.full;
        this.formConflict = '';
        this.formVisible = true;
      },
      /* 周视图卡片编辑(slot 为 camelCase; 多科室混排时科室以 slot 为准, 行级兑底) */
      openEdit: function (slot, row) {
        this.editing = true;
        this.form = Object.assign(this.empty(), {
          id: slot.id, deptId: slot.deptId != null ? slot.deptId : row.deptId, deptName: slot.deptName || row.deptName,
          staffId: row.staffId, staffName: row.staffName,
          workDate: slot.workDate, timeType: slot.timeType,
          regLevelCode: slot.regLevelCode, regLevelName: slot.regLevelName, regFee: slot.regFee,
          totalNum: slot.totalNum, usedNum: (slot.totalNum != null && slot.leftNum != null) ? (Number(slot.totalNum) - Number(slot.leftNum)) : 0,
          status: slot.status, room: slot.room || '', stopReason: slot.stopReason || ''
        });
        this.formConflict = '';
        this.formVisible = true;
      },
      /* 列表行编辑(下划线字段转表单) */
      openEditFromList: function (row) {
        this.editing = true;
        this.form = Object.assign(this.empty(), {
          id: row.id, deptId: row.dept_id, deptName: row.dept_name, staffId: row.staff_id, staffName: row.staff_name,
          workDate: row.work_date, timeType: row.time_type,
          regLevelCode: row.reg_level_code, regLevelName: row.reg_level_name, regFee: row.reg_fee,
          totalNum: row.total_num, usedNum: (row.total_num != null && row.left_num != null) ? (Number(row.total_num) - Number(row.left_num)) : 0,
          status: row.status, room: row.room || '', stopReason: row.stop_reason || ''
        });
        this.formConflict = '';
        this.formVisible = true;
      },
      /* 选医师: 仅带出姓名与默认挂号费; 出诊科室不再强制等于医师行政所属科室
       * (医师可跨科室出诊, 院外专家挂本机构任一开诊门诊科室); 若尚未选科室且医师科室在开诊列表则预填 */
      onFormStaffChange: function (id) {
        var vm = this, s = null;
        for (var i = 0; i < vm.staffs.length; i++) { if (vm.staffs[i].id === id) { s = vm.staffs[i]; break; } }
        if (s) {
          vm.form.staffName = s.staffName;
          if (s.regFee != null && Number(s.regFee) >= 0) { vm.form.regFee = Number(s.regFee); }
          if (!vm.form.deptId && s.deptId) {
            var inList = vm.depts.some(function (d) { return d.id === s.deptId && Number(d.deptLevel) === 2; });
            if (inList) { vm.form.deptId = s.deptId; vm.form.deptName = s.deptName || vm.deptName(s.deptId); }
          }
        }
        vm.checkConflict();
      },
      /* 选排班(出诊/挂号)科室: 换科室后清空已选诊室(诊室须属该科室子级); 科室也是冲突键, 重新检测 */
      onFormDeptChange: function (id) {
        this.form.deptName = id ? this.deptName(id) : '';
        this.form.room = '';
        this.checkConflict();
      },
      /* 选号别自动带出名称与默认费用 */
      onLevelPick: function (slot) {
        for (var i = 0; i < this.regLevels.length; i++) {
          if (this.regLevels[i].code === slot.regLevelCode) {
            slot.regLevelName = this.regLevels[i].name;
            if (this.regLevels[i].fee != null) { slot.regFee = this.regLevels[i].fee; }
            break;
          }
        }
      },
      /* 冲突实时提示(同医师+同日+同时段+同科室才算冲突, 跨科室可排多班次; 带请求序号防乱序覆盖) */
      checkConflict: function () {
        var vm = this;
        vm.formConflict = '';
        if (!vm.formVisible || !vm.form.staffId || !vm.form.workDate || !vm.form.timeType) { return; }
        var seq = (vm._conflictSeq = (vm._conflictSeq || 0) + 1);
        var q = '/api/his/schedule/list?staffId=' + vm.form.staffId + '&from=' + vm.form.workDate + '&to=' + vm.form.workDate + '&page=1&size=50';
        HIS.get(q).then(function (d) {
          if (seq !== vm._conflictSeq) { return; }
          var recs = (d && d.records) || [];
          for (var i = 0; i < recs.length; i++) {
            if (String(recs[i].time_type) === String(vm.form.timeType) && recs[i].id !== vm.form.id
              && Number(recs[i].dept_id) === Number(vm.form.deptId)) {
              vm.formConflict = '该医师在此日期的' + vm.ttLabel(vm.form.timeType) + '时段于该科室已有排班: ' + (recs[i].reg_level_name || '') + ' 余' + recs[i].left_num + '/' + recs[i].total_num + (recs[i].status === 0 ? '(已停诊)' : '') + '; 换挂号科室可继续加排班次';
              return;
            }
          }
        }).catch(function () { });
      },
      doSave: function () {
        var vm = this;
        if (!vm.form.staffId) { ElementPlus.ElMessage.warning('请选择出诊医师'); return; }
        if (!vm.form.deptId) { ElementPlus.ElMessage.warning('请选择挂号科室(本机构开诊的门诊科室)'); return; }
        if (!vm.form.workDate) { ElementPlus.ElMessage.warning('请选择出诊日期'); return; }
        if (vm.form.totalNum == null || Number(vm.form.totalNum) < 0) { ElementPlus.ElMessage.warning('总号源数不能为负'); return; }
        if (vm.editing && Number(vm.form.totalNum) < Number(vm.form.usedNum || 0)) { ElementPlus.ElMessage.warning('总号源不得低于已挂号数(' + vm.form.usedNum + ')'); return; }
        if (vm.form.status === 0 && !(vm.form.stopReason || '').trim()) { ElementPlus.ElMessage.warning('停诊时请填写停诊原因'); return; }
        if (vm.formConflict) { ElementPlus.ElMessage.warning(vm.formConflict); return; }
        var payload = {
          id: vm.form.id, deptId: vm.form.deptId, staffId: vm.form.staffId,
          workDate: vm.form.workDate, timeType: vm.form.timeType,
          regLevelCode: vm.form.regLevelCode, regLevelName: vm.form.regLevelName,
          regFee: vm.form.regFee, totalNum: vm.form.totalNum,
          status: vm.form.status, room: vm.form.room || '', stopReason: (vm.form.stopReason || '').trim()
        };
        vm.saving = true;
        var p = vm.editing ? HIS.put('/api/his/schedule', payload) : HIS.post('/api/his/schedule', payload);
        p.then(function () {
          HIS.notifySuccess('保存成功');
          vm.formVisible = false;
          vm.reloadCurrent();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      doDelete: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除 ' + (row.staff_name || '') + ' ' + row.work_date + ' ' + vm.ttLabel(row.time_type) + ' 的排班? 仅无人挂号时可删除。', '删除确认', { type: 'warning' }).then(function () {
          HIS.del('/api/his/schedule/' + row.id).then(function () {
            HIS.notifySuccess('已删除');
            vm.loadList();
          }).catch(HIS.notifyError);
        }).catch(function () { /* 取消 */ });
      },
      reloadCurrent: function () {
        if (this.viewMode === 'week') { this.loadWeek(); } else { this.loadList(); }
      },
      /* ===== 复制上周 ===== */
      doCopyWeek: function () {
        var vm = this;
        var source = vm.addDays(vm.weekStart, -7), target = vm.weekStart;
        ElementPlus.ElMessageBox.confirm('将上周(' + source + ' 起)的排班复制到本周(' + target + ' 起)? 已存在的排班自动跳过, 新排班恢复开放且余号重置。', '复制上周排班', { type: 'info' }).then(function () {
          var body = { sourceWeekStart: source, targetWeekStart: target };
          if (vm.filterDeptId) { body.deptId = vm.filterDeptId; }
          return HIS.post('/api/his/schedule/copy-week', body);
        }).then(function (d) {
          HIS.notifySuccess('复制完成: 新建 ' + (d.created || 0) + ' 条, 跳过 ' + (d.skipped || 0) + ' 条(共 ' + (d.total || 0) + ' 条)');
          vm.loadWeek();
        }).catch(function (e) { if (e && e.message) { HIS.notifyError(e); } });
      },
      /* ===== 按模板生成 ===== */
      openGenerate: function () {
        var vm = this;
        vm.selectedTemplates = [];
        vm.genDateRange = [vm.weekStart, vm.addDays(vm.weekStart, 6)];
        vm.generateVisible = true;
        HIS.get('/api/his/schedule/template/list?page=1&size=200').then(function (d) {
          vm.templateList = (d && d.records) || [];
        }).catch(HIS.notifyError);
      },
      onGenSelection: function (rows) { this.selectedTemplates = rows || []; },
      doGenerate: function () {
        var vm = this;
        if (!vm.selectedTemplates.length) { ElementPlus.ElMessage.warning('请勾选排班模板'); return; }
        if (!vm.genDateRange || vm.genDateRange.length !== 2) { ElementPlus.ElMessage.warning('请选择生成日期范围'); return; }
        var msg = '将按 ' + vm.selectedTemplates.length + ' 个模板在 ' + vm.genDateRange[0] + ' ~ ' + vm.genDateRange[1] + ' 内按星期规律生成排班(已存在自动跳过), 确认执行?';
        ElementPlus.ElMessageBox.confirm(msg, '按模板生成排班', { type: 'info' }).then(function () {
          vm.generating = true;
          HIS.post('/api/his/schedule/generate', {
            templateIds: vm.selectedTemplates.map(function (t) { return t.id; }),
            startDate: vm.genDateRange[0], endDate: vm.genDateRange[1]
          }).then(function (d) {
            HIS.notifySuccess('生成完成: 新建 ' + (d.created || 0) + ' 条, 跳过 ' + (d.skipped || 0) + ' 条(共 ' + (d.total || 0) + ' 条)');
            vm.generateVisible = false;
            vm.loadWeek();
          }).catch(HIS.notifyError).finally(function () { vm.generating = false; });
        }).catch(function () { /* 取消 */ });
      },
      /* ===== 批量停诊 ===== */
      openBatchStop: function () {
        this.stopReason = '';
        this.batchStopVisible = true;
      },
      doBatchStop: function () {
        var vm = this;
        if (!(vm.stopReason || '').trim()) { ElementPlus.ElMessage.warning('请填写停诊原因'); return; }
        var ids = vm.selectedRows.filter(function (r) { return r.status === 1; }).map(function (r) { return r.id; });
        if (!ids.length) { ElementPlus.ElMessage.warning('选中排班中没有开放状态的记录'); return; }
        vm.batchStopping = true;
        HIS.post('/api/his/schedule/batch-stop', { ids: ids, reason: vm.stopReason.trim() }).then(function (n) {
          HIS.notifySuccess('已停诊 ' + n + ' 条排班');
          vm.batchStopVisible = false;
          vm.selectedRows = [];
          vm.loadList();
        }).catch(HIS.notifyError).finally(function () { vm.batchStopping = false; });
      },
      /* ===== 模板管理 ===== */
      openDrawer: function () {
        this.drawerVisible = true;
        this.tplPage = 1;
        this.loadTemplates();
      },
      loadTemplates: function () {
        var vm = this;
        vm.tplLoading = true;
        var q = '/api/his/schedule/template/list?page=' + vm.tplPage + '&size=' + vm.tplSize;
        if (vm.tplFilterDeptId) { q += '&deptId=' + vm.tplFilterDeptId; }
        HIS.get(q).then(function (d) {
          vm.tplList = (d && d.records) || [];
          vm.tplTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.tplLoading = false; });
      },
      onTplFilterChange: function () { this.tplPage = 1; this.loadTemplates(); },
      onTplPage: function (p) { this.tplPage = p; this.loadTemplates(); },
      onTplSize: function (s) { this.tplSize = s; this.tplPage = 1; this.loadTemplates(); },
      tplSeqNo: function (i) { return (this.tplPage - 1) * this.tplSize + i + 1; },
      addTpl: function () {
        this.tplEditing = false;
        this.tplForm = this.emptyTpl();
        this.tplFormVisible = true;
      },
      editTpl: function (row) {
        this.tplEditing = true;
        this.tplForm = clean(Object.assign(this.emptyTpl(), row));
        this.tplFormVisible = true;
      },
      onTplStaffChange: function (id) {
        var vm = this, s = null;
        for (var i = 0; i < vm.allStaffs.length; i++) { if (vm.allStaffs[i].id === id) { s = vm.allStaffs[i]; break; } }
        if (s) {
          vm.tplForm.staffName = s.staffName;
          /* 不强制覆盖已选科室; 仅当未选且医师科室为开诊门诊科室时预填 */
          if (!vm.tplForm.deptId && s.deptId && vm.depts.some(function (d) { return d.id === s.deptId && Number(d.deptLevel) === 2; })) {
            vm.tplForm.deptId = s.deptId;
            vm.tplForm.deptName = s.deptName || vm.deptName(s.deptId);
          }
        }
      },
      onTplDeptChange: function (id) {
        this.tplForm.deptName = id ? this.deptName(id) : '';
        this.tplForm.room = '';
      },
      saveTpl: function () {
        var vm = this;
        if (!vm.tplForm.staffId) { ElementPlus.ElMessage.warning('请选择出诊医师'); return; }
        if (!vm.tplForm.deptId) { ElementPlus.ElMessage.warning('请选择挂号科室(本机构开诊的门诊科室)'); return; }
        if (!vm.tplForm.totalNum || Number(vm.tplForm.totalNum) < 1) { ElementPlus.ElMessage.warning('号源数至少为 1'); return; }
        vm.tplSaving = true;
        var p = vm.tplEditing ? HIS.put('/api/his/schedule/template', vm.tplForm) : HIS.post('/api/his/schedule/template', vm.tplForm);
        p.then(function () {
          HIS.notifySuccess('模板已保存');
          vm.tplFormVisible = false;
          vm.loadTemplates();
        }).catch(HIS.notifyError).finally(function () { vm.tplSaving = false; });
      },
      delTpl: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除模板「' + (row.templateName || row.staffName || row.id) + '」? 不影响已生成的历史排班。', '删除确认', { type: 'warning' }).then(function () {
          HIS.del('/api/his/schedule/template/' + row.id).then(function () {
            HIS.notifySuccess('已删除');
            vm.loadTemplates();
          }).catch(HIS.notifyError);
        }).catch(function () { /* 取消 */ });
      },
      /* ===== 批量创建模板(医师×时段规律) ===== */
      openBatchTpl: function () {
        this.batchTplForm = { deptId: null, deptName: '', staffIds: [], slots: [this.emptySlot()] };
        this.batchTplVisible = true;
      },
      onBatchDeptChange: function (id) {
        this.batchTplForm.deptName = id ? this.deptName(id) : '';
        this.batchTplForm.staffIds = [];
        /* 换科室后各槽位已选诊室失效(诊室须属该科室子级), 一并清空 */
        (this.batchTplForm.slots || []).forEach(function (sl) { if (sl) { sl.room = ''; } });
      },
      addSlot: function () { this.batchTplForm.slots.push(this.emptySlot()); },
      removeSlot: function (i) {
        if (this.batchTplForm.slots.length <= 1) { ElementPlus.ElMessage.warning('至少保留一个时段'); return; }
        this.batchTplForm.slots.splice(i, 1);
      },
      doBatchTpl: function () {
        var vm = this;
        if (!vm.batchTplForm.staffIds.length) { ElementPlus.ElMessage.warning('请勾选医师'); return; }
        var msg = '将为 ' + vm.batchTplForm.staffIds.length + ' 位医师 × ' + vm.batchTplForm.slots.length + ' 个时段创建排班模板(同医师+星期+时段已存在的自动跳过), 确认执行?';
        ElementPlus.ElMessageBox.confirm(msg, '批量创建模板', { type: 'info' }).then(function () {
          vm.batchTplSaving = true;
          return HIS.post('/api/his/schedule/template/batch', {
            deptId: vm.batchTplForm.deptId || null,
            deptName: vm.batchTplForm.deptName || '',
            staffIds: vm.batchTplForm.staffIds,
            slots: vm.batchTplForm.slots
          });
        }).then(function (d) {
          HIS.notifySuccess('批量创建完成: 新增 ' + (d.created || 0) + ' 条, 跳过 ' + (d.skipped || 0) + ' 条');
          vm.batchTplVisible = false;
          vm.loadTemplates();
        }).catch(function (e) { if (e && e.message) { HIS.notifyError(e); } })
          .finally(function () { vm.batchTplSaving = false; });
      },
      /* ===== 通用 ===== */
      ttLabel: function (tt) { return timeLabel(tt); },
      weekdayLabel: function (wd) { return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][wd] || ''; },
      usageClass: function (r) {
        var n = Number(r) || 0;
        return n > 80 ? 'r-high' : (n > 50 ? 'r-mid' : 'r-low');
      },
      canStopRow: function (row) { return row.status === 1; },
      canSelectTpl: function (row) { return row.status === 1; }
    },
    template: [
      '<div class="page-card">',
      '  <div class="page-title">排班号源 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(周视图排班矩阵 · 号源余量 · 模板化批量排班)</span></div>',
      '  <el-alert v-if="!canMaintain" type="info" :closable="false" show-icon style="margin-bottom:10px;" title="排班号源为本机构业务过程, 仅显示当前登录机构的开诊门诊科室; 仅本机构管理员可维护。"></el-alert>',
      '  <el-alert v-else type="success" :closable="false" show-icon style="margin-bottom:10px;" title="仅展示与维护当前登录机构的号源; 挂号科室限于本机构已开诊的门诊科室, 出诊医师可跨科室/为院外专家(挂本机构虚拟职工)。"></el-alert>',
      '  <div class="toolbar">',
      '    <el-radio-group v-model="viewMode" size="small" @change="onViewModeChange">',
      '      <el-radio-button label="week">周视图</el-radio-button>',
      '      <el-radio-button label="list">列表视图</el-radio-button>',
      '    </el-radio-group>',
      '    <el-select v-model="filterDeptId" placeholder="科室/拼音简码" clearable filterable size="small" style="width:150px" :filter-method="kwFilter(\'fDept\')" @change="onDeptChange"><el-option v-for="d in kwOptions(\'fDept\', regDeptOptions, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '    <el-select v-model="filterStaffId" placeholder="医师/拼音简码" clearable filterable size="small" :disabled="viewMode===\'week\'" :filter-method="kwFilter(\'fStaff\')" style="width:170px" title="周视图按医师分行展示, 医师筛选仅在列表视图生效"><el-option v-for="s in kwOptions(\'fStaff\', staffs, [\'staffName\',\'staffNo\',\'pyCode\',\'abbrCode\'])" :key="s.id" :label="s.staffName+\'(\'+s.staffNo+\')\'" :value="s.id"></el-option></el-select>',
      '    <template v-if="viewMode===\'week\'">',
      '      <el-button size="small" @click="prevWeek">&lt; 上一周</el-button>',
      '      <span class="sched-week-label">{{ weekLabel }}</span>',
      '      <el-button size="small" @click="nextWeek">下一周 &gt;</el-button>',
      '      <el-button size="small" type="info" plain @click="thisWeek">本周</el-button>',
      '    </template>',
      '    <template v-else>',
      '      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" size="small" style="width:250px"></el-date-picker>',
      '      <el-select v-model="listStatus" placeholder="全部状态" clearable size="small" style="width:105px"><el-option label="开放" :value="1"></el-option><el-option label="停诊" :value="0"></el-option></el-select>',
      '      <el-button type="primary" size="small" @click="searchList">查询</el-button>',
      '    </template>',
      '    <span style="flex:1"></span>',
      '    <template v-if="canMaintain">',
      '      <el-button type="success" size="small" @click="openGenerate">按模板排班</el-button>',
      '      <el-button v-if="viewMode===\'week\'" size="small" @click="doCopyWeek">复制上周</el-button>',
      '      <el-button v-if="viewMode===\'list\'" type="primary" plain size="small" @click="openAdd">新增排班</el-button>',
      '      <el-button size="small" @click="openDrawer">管理模板</el-button>',
      '    </template>',
      '  </div>',
      /* ---- 周视图 ---- */
      '  <div v-if="viewMode===\'week\'" v-loading="weekLoading">',
      '    <div v-if="weekStats.length" class="sched-stats">',
      '      <span class="sched-stats-cap">号源统计({{ weekDays.length ? weekDays[0].date : \'\' }}):</span>',
      '      <div v-for="st in weekStats" :key="st.dept_id" class="sched-stat-chip">',
      '        <b>{{ st.dept_name }}</b>',
      '        <span>总{{ st.total_num }} · 已挂{{ st.used_num }} · 余{{ st.left_num }}</span>',
      '        <span class="rate" :class="usageClass(st.usage_rate)">{{ st.usage_rate }}%</span>',
      '      </div>',
      '    </div>',
      '    <el-table :data="weekData" border size="small" style="width:100%" :cell-style="weekCellStyle" empty-text="当前筛选条件下无排班数据">',
      '      <el-table-column label="医师 / 科室" width="150" fixed>',
      '        <template #default="s">',
      '          <div class="sched-staff">{{ s.row.staffName }}</div>',
      '          <div class="sched-staff-sub">{{ s.row.staffNo }} · {{ s.row.deptName }}</div>',
      '        </template>',
      '      </el-table-column>',
      '      <el-table-column v-for="wd in weekDays" :key="wd.key" min-width="128" align="center">',
      '        <template #header>',
      '          <div class="sched-col-hd" :class="{today: wd.isToday}">',
      '            <span>{{ wd.label }}</span>',
      '            <span class="sched-col-date">{{ wd.date }}</span>',
      '          </div>',
      '        </template>',
      '        <template #default="s">',
      '          <template v-for="tt in shiftCodes" :key="tt">',
      '            <div v-for="slot in slotOf(s.row, wd.key, tt)" :key="slot.id" class="schedule-card" :class="slotClass(slot)" @click="openEdit(slot, s.row)">',
      '              <div class="sc-time">{{ ttLabel(tt) }}</div>',
      '              <div class="sc-level">{{ slot.regLevelName }}<span v-if="slot.deptName && slot.deptName !== s.row.deptName" style="color:var(--yb-ink-2);"> · {{ slot.deptName }}</span></div>',
      '              <div class="sc-num">余 {{ slot.leftNum }}/{{ slot.totalNum }}</div>',
      '              <div v-if="slot.room" class="sc-room">{{ slot.room }}</div>',
      '            </div>',
      '          </template>',
      '          <div v-if="canMaintain" class="schedule-empty" :class="{\'is-ghost\':hasAnySlot(s.row, wd.key)}" @click="openAddAt(s.row, wd)"><span>+ 排班</span></div>',
      '        </template>',
      '      </el-table-column>',
      '    </el-table>',
      '  </div>',
      /* ---- 列表视图 ---- */
      '  <div v-if="viewMode===\'list\'">',
      '    <div v-if="canMaintain && selectedRows.length" class="sched-batchbar">',
      '      <el-button type="warning" size="small" @click="openBatchStop">批量停诊({{ selectedRows.length }}条)</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:12px;">仅开放状态的排班参与停诊</span>',
      '    </div>',
      '    <el-table :data="listData" border stripe size="small" v-loading="listLoading" max-height="calc(100vh - 320px)" @selection-change="onSelectionChange">',
      '      <el-table-column v-if="canMaintain" type="selection" width="42" :selectable="canStopRow"></el-table-column>',
      '      <el-table-column type="index" label="序号" width="60" :index="seqNo"></el-table-column>',
      '      <el-table-column prop="work_date" label="出诊日期" width="105"></el-table-column>',
      '      <el-table-column label="星期" width="65"><template #default="s">{{ weekdayLabel(s.row.weekday) }}</template></el-table-column>',
      '      <el-table-column label="时段" width="65"><template #default="s">{{ ttLabel(s.row.time_type) }}</template></el-table-column>',
      '      <el-table-column prop="dept_name" label="科室" width="120" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="staff_name" label="医师" width="90"></el-table-column>',
      '      <el-table-column prop="staff_no" label="工号" width="85"></el-table-column>',
      '      <el-table-column prop="reg_level_name" label="号别" width="100"></el-table-column>',
      '      <el-table-column prop="reg_fee" label="挂号费" width="75" align="right"></el-table-column>',
      '      <el-table-column label="号源(余/总)" width="95" align="center"><template #default="s"><span :style="{color: s.row.left_num<=0?\'var(--yb-danger)\':\'\'}">{{ s.row.left_num }}</span>/{{ s.row.total_num }}</template></el-table-column>',
      '      <el-table-column prop="room" label="诊室" width="75"></el-table-column>',
      '      <el-table-column label="状态" width="70"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"开放":"停诊" }}</el-tag></template></el-table-column>',
      '      <el-table-column label="停诊原因" min-width="120" show-overflow-tooltip><template #default="s"><span v-if="s.row.status===0">{{ s.row.stop_reason || \'-\' }}</span><span v-else style="color:var(--yb-ink-4);">-</span></template></el-table-column>',
      '      <el-table-column v-if="canMaintain" label="操作" width="120" fixed="right"><template #default="s">',
      '        <el-button link size="small" @click="openEditFromList(s.row)">编辑</el-button>',
      '        <el-button link size="small" type="danger" @click="doDelete(s.row)">删除</el-button>',
      '      </template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="listTotal" :page-size="listSize" :current-page="listPage" :page-sizes="[20, 50, 100]" @current-change="onListPage" @size-change="onListSize"></el-pagination>',
      '  </div>',
      /* ---- 新增/编辑排班 ---- */
      '  <el-dialog v-model="formVisible" :title="editing?\'编辑排班\':\'新增排班\'" width="560px">',
      '    <el-form :model="form" label-width="100px">',
      '      <el-form-item label="出诊医师">',
      '        <el-select v-model="form.staffId" filterable style="width:100%" placeholder="选择医师(可输拼音简码)" :filter-method="kwFilter(\'formStaff\')" @change="onFormStaffChange"><el-option v-for="s in kwOptions(\'formStaff\', staffs, [\'staffName\',\'staffNo\',\'pyCode\',\'abbrCode\'])" :key="s.id" :label="s.staffName+\'(\'+s.staffNo+\')\'" :value="s.id"></el-option></el-select>',
      '      </el-form-item>',
      '      <el-form-item label="挂号科室" title="本机构已开诊的门诊科室; 可与出诊医师行政所属科室不同(支持跨科室/院外专家出诊)">',
      '        <dept-tree-picker v-model="form.deptId" :options="formDeptOptions" placeholder="选择本机构开诊门诊科室(可输拼音简码)" @change="onFormDeptChange" />',
      '      </el-form-item>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="出诊日期">',
      '          <el-date-picker v-model="form.workDate" type="date" value-format="YYYY-MM-DD" style="width:100%" @change="checkConflict"></el-date-picker>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="星期"><span style="line-height:32px;color:var(--yb-ink-2);">{{ weekdayOf(form.workDate) || \'请选择日期\' }}</span></el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="时段">',
      '        <el-radio-group v-model="form.timeType" @change="checkConflict">',
      '          <el-radio v-for="t in shiftList" :key="t.v" :label="t.v">{{ t.l }}</el-radio>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="号别">',
      '          <el-select v-model="form.regLevelCode" style="width:100%" @change="onLevelPick(form)"><el-option v-for="r in regLevels" :key="r.code" :label="r.name" :value="r.code"></el-option></el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="挂号费(元)">',
      '          <el-input-number v-model="form.regFee" :min="0" :precision="2" controls-position="right" style="width:100%"></el-input-number>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="总号源">',
      '          <el-input-number v-model="form.totalNum" :min="editing?(form.usedNum||0):0" :max="999" controls-position="right" style="width:100%"></el-input-number>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="诊室" title="诊室为挂号科室的子级, 先选挂号科室后联动带出其下诊室; 可不选">',
      '          <el-select v-model="form.room" clearable filterable allow-create default-first-option style="width:100%" :disabled="!form.deptId" placeholder="选择本科室诊室(可留空, 可输拼音简码)" :filter-method="kwFilter(\'formRoom\')"><el-option v-for="r in kwOptions(\'formRoom\', formRoomOptions, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="r.id" :label="r.deptName" :value="r.deptName"></el-option></el-select>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item v-if="editing" label="已挂号数"><span style="color:var(--yb-ink-2);">{{ form.usedNum }} 人(总号源不得低于已挂号数)</span></el-form-item>',
      '      <el-form-item label="状态">',
      '        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="开放" inactive-text="停诊"></el-switch>',
      '      </el-form-item>',
      '      <el-form-item v-if="form.status===0" label="停诊原因">',
      '        <el-input v-model="form.stopReason" maxlength="100" placeholder="必填, 如: 医师外出进修"></el-input>',
      '      </el-form-item>',
      '      <el-alert v-if="formConflict" :title="formConflict" type="error" :closable="false" show-icon></el-alert>',
      '    </el-form>',
      '    <template #footer><el-button @click="formVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="doSave">确定</el-button></template>',
      '  </el-dialog>',
      /* ---- 按模板生成 ---- */
      '  <el-dialog v-model="generateVisible" title="按模板生成排班" width="850px" top="6vh">',
      '    <el-alert type="info" :closable="false" show-icon title="按模板的星期规律在日期范围内生成排班, 已存在的排班自动跳过; 停用模板不可选。" style="margin-bottom:10px;"></el-alert>',
      '    <el-table :data="templateList" border size="small" height="280" @selection-change="onGenSelection">',
      '      <el-table-column type="selection" width="42" :selectable="canSelectTpl"></el-table-column>',
      '      <el-table-column type="index" label="#" width="45"></el-table-column>',
      '      <el-table-column prop="deptName" label="科室" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="staffName" label="医师" width="85"></el-table-column>',
      '      <el-table-column label="星期" width="60"><template #default="s">{{ weekdayLabel(s.row.weekday) }}</template></el-table-column>',
      '      <el-table-column label="时段" width="60"><template #default="s">{{ ttLabel(s.row.timeType) }}</template></el-table-column>',
      '      <el-table-column prop="regLevelName" label="号别" width="95"></el-table-column>',
      '      <el-table-column prop="totalNum" label="号源" width="60"></el-table-column>',
      '      <el-table-column prop="room" label="诊室" width="80"></el-table-column>',
      '      <el-table-column label="状态" width="65"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '    </el-table>',
      '    <el-form label-width="90px" style="margin-top:12px;">',
      '      <el-form-item label="生成范围">',
      '        <el-date-picker v-model="genDateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:100%"></el-date-picker>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="generateVisible=false">取消</el-button><el-button type="primary" :loading="generating" @click="doGenerate">生成({{ selectedTemplates.length }}个模板)</el-button></template>',
      '  </el-dialog>',
      /* ---- 批量停诊 ---- */
      '  <el-dialog v-model="batchStopVisible" title="批量停诊" width="460px">',
      '    <el-form label-width="80px">',
      '      <el-form-item label="选中排班"><span>{{ selectedRows.length }} 条(仅统计开放状态)</span></el-form-item>',
      '      <el-form-item label="停诊原因">',
      '        <el-input v-model="stopReason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="必填, 如: 医师临时外出/设备维护"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="batchStopVisible=false">取消</el-button><el-button type="warning" :loading="batchStopping" @click="doBatchStop">确认停诊</el-button></template>',
      '  </el-dialog>',
      /* ---- 模板管理抽屉 ---- */
      '  <el-drawer v-model="drawerVisible" title="排班模板管理" size="880px">',
      '    <div class="toolbar">',
      '      <el-select v-model="tplFilterDeptId" placeholder="科室/拼音简码" clearable filterable size="small" style="width:150px" :filter-method="kwFilter(\'tDept\')" @change="onTplFilterChange"><el-option v-for="d in kwOptions(\'tDept\', regDeptOptions, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="d.id" :label="d.deptName" :value="d.id"></el-option></el-select>',
      '      <el-button type="primary" size="small" @click="addTpl">新增模板</el-button>',
      '      <el-button type="success" size="small" @click="openBatchTpl">批量创建</el-button>',
      '      <span style="color:var(--yb-ink-2);font-size:12px;">共 {{ tplTotal }} 条 · 同一医师同星期同时段仅一条模板</span>',
      '    </div>',
      '    <el-table :data="tplList" border size="small" v-loading="tplLoading">',
      '      <el-table-column type="index" label="序号" width="55" :index="tplSeqNo"></el-table-column>',
      '      <el-table-column prop="deptName" label="科室" width="110" show-overflow-tooltip></el-table-column>',
      '      <el-table-column prop="staffName" label="医师" width="85"></el-table-column>',
      '      <el-table-column label="星期" width="60"><template #default="s">{{ weekdayLabel(s.row.weekday) }}</template></el-table-column>',
      '      <el-table-column label="时段" width="60"><template #default="s">{{ ttLabel(s.row.timeType) }}</template></el-table-column>',
      '      <el-table-column prop="regLevelName" label="号别" width="95"></el-table-column>',
      '      <el-table-column prop="regFee" label="挂号费" width="70" align="right"></el-table-column>',
      '      <el-table-column prop="totalNum" label="号源" width="60"></el-table-column>',
      '      <el-table-column prop="room" label="诊室" width="80" show-overflow-tooltip></el-table-column>',
      '      <el-table-column label="状态" width="65"><template #default="s"><el-tag size="small" :type="s.row.status===1?\'success\':\'info\'">{{ s.row.status===1?"启用":"停用" }}</el-tag></template></el-table-column>',
      '      <el-table-column label="操作" width="110" fixed="right"><template #default="s">',
      '        <el-button link size="small" @click="editTpl(s.row)">编辑</el-button>',
      '        <el-button link size="small" type="danger" @click="delTpl(s.row)">删除</el-button>',
      '      </template></el-table-column>',
      '    </el-table>',
      '    <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="tplTotal" :page-size="tplSize" :current-page="tplPage" :page-sizes="[20, 50]" @current-change="onTplPage" @size-change="onTplSize"></el-pagination>',
      /* 模板新增/编辑(嵌套在抽屉内, append-to-body 保证层级) */
      '    <el-dialog v-model="tplFormVisible" :title="tplEditing?\'编辑模板\':\'新增模板\'" width="540px" append-to-body>',
      '      <el-form :model="tplForm" label-width="100px">',
      '        <el-form-item label="出诊医师">',
      '          <el-select v-model="tplForm.staffId" filterable style="width:100%" placeholder="选择医师(可输拼音简码)" :filter-method="kwFilter(\'tplStaff\')" @change="onTplStaffChange"><el-option v-for="s in kwOptions(\'tplStaff\', allStaffs, [\'staffName\',\'staffNo\',\'pyCode\',\'abbrCode\'])" :key="s.id" :label="s.staffName+\'(\'+s.staffNo+\')\'" :value="s.id"></el-option></el-select>',
      '        </el-form-item>',
      '        <el-form-item label="挂号科室" title="本机构已开诊的门诊科室; 模板生成排班时按此科室落库">',
      '          <dept-tree-picker v-model="tplForm.deptId" :options="tplDeptOptions" placeholder="选择本机构开诊门诊科室(可输拼音简码)" @change="onTplDeptChange" />',
      '        </el-form-item>',
      '        <el-row :gutter="12">',
      '          <el-col :span="12"><el-form-item label="星期">',
      '            <el-select v-model="tplForm.weekday" style="width:100%"><el-option v-for="i in 7" :key="i" :label="weekdayLabel(i)" :value="i"></el-option></el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="时段">',
      '            <el-select v-model="tplForm.timeType" style="width:100%"><el-option v-for="t in shiftList" :key="t.v" :label="shiftOptLabel(t)" :value="t.v"></el-option></el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-row :gutter="12">',
      '          <el-col :span="12"><el-form-item label="号别">',
      '            <el-select v-model="tplForm.regLevelCode" style="width:100%" @change="onLevelPick(tplForm)"><el-option v-for="r in regLevels" :key="r.code" :label="r.name" :value="r.code"></el-option></el-select>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="挂号费(元)">',
      '            <el-input-number v-model="tplForm.regFee" :min="0" :precision="2" controls-position="right" style="width:100%"></el-input-number>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-row :gutter="12">',
      '          <el-col :span="12"><el-form-item label="号源数">',
      '            <el-input-number v-model="tplForm.totalNum" :min="1" :max="999" controls-position="right" style="width:100%"></el-input-number>',
      '          </el-form-item></el-col>',
      '          <el-col :span="12"><el-form-item label="诊室" title="诊室为挂号科室的子级, 随挂号科室联动; 可不选">',
      '            <el-select v-model="tplForm.room" clearable filterable allow-create default-first-option style="width:100%" :disabled="!tplForm.deptId" placeholder="选择本科室诊室(可留空, 可输拼音简码)" :filter-method="kwFilter(\'tplRoom\')"><el-option v-for="r in kwOptions(\'tplRoom\', tplRoomOptions, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="r.id" :label="r.deptName" :value="r.deptName"></el-option></el-select>',
      '          </el-form-item></el-col>',
      '        </el-row>',
      '        <el-form-item label="状态">',
      '          <el-switch v-model="tplForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用"></el-switch>',
      '        </el-form-item>',
      '      </el-form>',
      '      <template #footer><el-button @click="tplFormVisible=false">取消</el-button><el-button type="primary" :loading="tplSaving" @click="saveTpl">确定</el-button></template>',
      '    </el-dialog>',
      /* 批量创建模板: 选科室 → 勾选医师 → 设置时段规律 → 一键生成 */
      '    <el-dialog v-model="batchTplVisible" title="批量创建排班模板" width="880px" top="5vh" append-to-body>',
      '      <el-form label-width="90px">',
      '        <el-form-item label="科室">',
      '          <dept-tree-picker v-model="batchTplForm.deptId" :options="batchDeptOptions" placeholder="选择本科室级挂号科室(不选则逐医师回退其行政科室)" @change="onBatchDeptChange" />',
      '        </el-form-item>',
      '        <el-form-item label="医师(多选)">',
      '          <el-select v-model="batchTplForm.staffIds" multiple filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="8" style="width:100%" placeholder="勾选需生成排班模板的医师(可输拼音简码)" :filter-method="kwFilter(\'bStaff\')"><el-option v-for="s in kwOptions(\'bStaff\', batchStaffOptions, [\'staffName\',\'staffNo\',\'pyCode\',\'abbrCode\'])" :key="s.id" :label="s.staffName+\'(\'+s.staffNo+\')\'" :value="s.id"></el-option></el-select>',
      '        </el-form-item>',
      '      </el-form>',
      '      <el-divider content-position="left">排班规律(每周固定时段, 选中医师共用: {{ batchTplForm.staffIds.length }} 人 × {{ batchTplForm.slots.length }} 时段)</el-divider>',
      '      <el-table :data="batchTplForm.slots" border size="small">',
      '        <el-table-column type="index" label="#" width="42"></el-table-column>',
      '        <el-table-column label="星期" width="112">',
      '          <template #default="s"><el-select v-model="s.row.weekday" size="small" style="width:90px;"><el-option v-for="i in 7" :key="i" :label="weekdayLabel(i)" :value="i"></el-option></el-select></template>',
      '        </el-table-column>',
      '        <el-table-column label="时段" width="108">',
      '          <template #default="s"><el-select v-model="s.row.timeType" size="small" style="width:130px;"><el-option v-for="t in shiftList" :key="t.v" :label="shiftOptLabel(t)" :value="t.v"></el-option></el-select></template>',
      '        </el-table-column>',
      '        <el-table-column label="号别" width="142">',
      '          <template #default="s"><el-select v-model="s.row.regLevelCode" size="small" @change="onLevelPick(s.row)"><el-option v-for="r in regLevels" :key="r.code" :label="r.name" :value="r.code"></el-option></el-select></template>',
      '        </el-table-column>',
      '        <el-table-column label="挂号费" width="106" align="center">',
      '          <template #default="s"><el-input-number v-model="s.row.regFee" :min="0" :precision="2" size="small" controls-position="right" style="width:92px;"></el-input-number></template>',
      '        </el-table-column>',
      '        <el-table-column label="号源数" width="106" align="center">',
      '          <template #default="s"><el-input-number v-model="s.row.totalNum" :min="1" size="small" controls-position="right" style="width:92px;"></el-input-number></template>',
      '        </el-table-column>',
      '        <el-table-column label="诊室" min-width="130">',
      '          <template #default="s"><el-select v-model="s.row.room" size="small" clearable filterable allow-create default-first-option style="width:100%" :disabled="!batchTplForm.deptId" placeholder="随科室联动" :filter-method="kwFilter(\'slotRoom\'+s.$index)"><el-option v-for="r in kwOptions(\'slotRoom\'+s.$index, batchRoomOptions, [\'deptName\',\'deptCode\',\'pyCode\',\'abbrCode\'])" :key="r.id" :label="r.deptName" :value="r.deptName"></el-option></el-select></template>',
      '        </el-table-column>',
      '        <el-table-column label="操作" width="60" align="center">',
      '          <template #default="s"><el-button link type="danger" size="small" @click="removeSlot(s.$index)">移除</el-button></template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <div style="margin-top:8px;display:flex;align-items:center;gap:10px;">',
      '        <el-button size="small" @click="addSlot">+ 添加时段</el-button>',
      '        <span style="color:var(--yb-ink-2);font-size:12px;">已存在(同医师+星期+时段)的模板自动跳过</span>',
      '      </div>',
      '      <template #footer><el-button @click="batchTplVisible=false">取消</el-button><el-button type="primary" :loading="batchTplSaving" @click="doBatchTpl">确认创建</el-button></template>',
      '    </el-dialog>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };

})();
