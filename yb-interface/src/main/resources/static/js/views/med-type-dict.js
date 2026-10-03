/* 医疗类别维护字典(医共体级模板; 2026-10 医疗类别维护字典)。
   单表: 从医保字典 cv_code:med_type 整组导入, 每条维护「门诊使用/住院使用」两个独立启停开关与「开放机构级别」(县/乡/村)。
   码值来源为医保权威值域, 只导入不手工造码(无新增按钮); code 不可改。写守卫在 Service(requireLeadOrg, 仅牵头机构维护)。
   open_levels 为 orgLevel token 逗号集(1县/2乡/3村); 消费端按登录机构级别 + 场景开关收窄下拉。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  var BASE = '/api/his/med-type';
  /* 机构级别(orgLevel): 1县/2乡/3村 */
  var LEVELS = [{ v: '1', l: '县级' }, { v: '2', l: '乡级' }, { v: '3', l: '村级' }];

  function levelsToArr(csv) {
    var s = String(csv == null ? '' : csv).trim();
    if (!s) { return ['1', '2', '3']; }
    var a = [];
    s.split(',').forEach(function (t) { t = t.trim(); if (t === '1' || t === '2' || t === '3') { a.push(t); } });
    return a.length ? a : ['1', '2', '3'];
  }
  function arrToLevels(a) {
    a = a || [];
    var set = {};
    a.forEach(function (t) { if (t === '1' || t === '2' || t === '3') { set[t] = 1; } });
    var out = [];
    ['1', '2', '3'].forEach(function (t) { if (set[t]) { out.push(t); } });
    return out.length ? out.join(',') : '1,2,3';
  }
  function numOrNull(v) {
    if (v === null || v === undefined || v === '') { return null; }
    var n = Number(v); return isNaN(n) ? null : n;
  }
  function flagOf(v) { return (v === 1 || v === '1' || v === true) ? 1 : 0; }

  function newForm() {
    return { code: '', name: '', otpUseFlag: 0, iptUseFlag: 0, levelArr: ['1', '2', '3'], sortNo: 0, status: 1, memo: '' };
  }
  function formFromRow(d) {
    d = d || {};
    var f = newForm();
    f.code = d.code || ''; f.name = d.name || '';
    f.otpUseFlag = flagOf(d.otpUseFlag); f.iptUseFlag = flagOf(d.iptUseFlag);
    f.levelArr = levelsToArr(d.openLevels);
    f.sortNo = d.sortNo == null ? 0 : Number(d.sortNo);
    f.status = d.status == null ? 1 : Number(d.status);
    f.memo = d.memo || '';
    return f;
  }

  HIS.views.MedTypeDict = {
    data: function () {
      return {
        keyword: '', fOtp: null, fIpt: null, fLevel: '', fStatus: null,
        page: 1, size: 20, loading: false, list: [], total: 0,
        importing: false,
        dlg: false, editing: false, saving: false, editId: null,
        form: newForm(),
        levelOpts: LEVELS
      };
    },
    computed: {
      levelMap: function () { var m = {}; LEVELS.forEach(function (x) { m[x.v] = x.l; }); return m; },
      ph: function () { return '编码/名称/拼音简码检索'; }
    },
    created: function () { this.fetch(); },
    methods: {
      seq: function (i) { return (this.page - 1) * this.size + i + 1; },
      search: function () { this.page = 1; this.fetch(); },
      onPage: function (p) { this.page = p; this.fetch(); },
      onSize: function (s) { this.size = s; this.page = 1; this.fetch(); },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = BASE + '/page?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword); }
        if (vm.fOtp !== null && vm.fOtp !== '') { q += '&otp=' + vm.fOtp; }
        if (vm.fIpt !== null && vm.fIpt !== '') { q += '&ipt=' + vm.fIpt; }
        if (vm.fLevel) { q += '&level=' + vm.fLevel; }
        if (vm.fStatus !== null && vm.fStatus !== '') { q += '&status=' + vm.fStatus; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || []; vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      doImport: function () {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '从医保字典 med_type 值域整组导入医疗类别。已存在的编码只刷新名称/溯源, 不覆盖已维护的门诊/住院启停与开放级别。确认导入?',
          '导入确认', { type: 'warning' })
          .then(function () {
            vm.importing = true;
            return HIS.post(BASE + '/import', {});
          })
          .then(function (r) {
            r = r || {};
            HIS.notifySuccess('导入完成: 标准码 ' + (r.total || 0) + ' 项, 新增 ' + (r.inserted || 0) + ' 项, 已存在保留 ' + (r.skipped || 0) + ' 项');
            vm.page = 1; vm.fetch();
            if (HIS._medTypeCache) { HIS._medTypeCache.OTP = null; HIS._medTypeCache.IPT = null; }
          })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } })
          .finally(function () { vm.importing = false; });
      },
      openEdit: function (row) {
        this.form = formFromRow(row); this.editing = true; this.editId = row.id; this.dlg = true;
      },
      save: function () {
        var vm = this; var f = vm.form;
        if (!f.name || !String(f.name).trim()) { ElementPlus.ElMessage.warning('名称必填'); return; }
        if (!f.levelArr || !f.levelArr.length) { ElementPlus.ElMessage.warning('至少保留一个开放级别'); return; }
        var body = {
          name: String(f.name).trim(),
          otpUseFlag: Number(f.otpUseFlag), iptUseFlag: Number(f.iptUseFlag),
          openLevels: arrToLevels(f.levelArr),
          sortNo: numOrNull(f.sortNo) == null ? 0 : Number(f.sortNo),
          status: Number(f.status), memo: f.memo || null
        };
        vm.saving = true;
        HIS.put(BASE + '/update?id=' + vm.editId, body).then(function () {
          HIS.notifySuccess('修改成功');
          vm.dlg = false; vm.fetch();
          if (HIS._medTypeCache) { HIS._medTypeCache.OTP = null; HIS._medTypeCache.IPT = null; }
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认删除医疗类别【' + row.name + '(' + row.code + ')】? 删除的是本医共体的启用配置, 医保标准码集仍在, 可再次整组导入恢复。',
          '删除确认', { type: 'warning' })
          .then(function () { return HIS.del(BASE + '/delete?id=' + row.id); })
          .then(function () {
            HIS.notifySuccess('已删除'); vm.fetch();
            if (HIS._medTypeCache) { HIS._medTypeCache.OTP = null; HIS._medTypeCache.IPT = null; }
          })
          .catch(function (e) { if (e !== 'cancel') { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">医疗类别 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(医共体级模板 · 医保 med_type 导入 · 门诊/住院分设启停 · 按机构级别开放 · 编码不可改)</span></div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" :placeholder="ph" clearable style="width:220px" @keyup.enter="search"></el-input>',
      '    <el-select v-model="fOtp" placeholder="门诊使用" clearable style="width:120px"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-select v-model="fIpt" placeholder="住院使用" clearable style="width:120px"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-select v-model="fLevel" placeholder="开放级别" clearable style="width:120px"><el-option v-for="l in levelOpts" :key="l.v" :label="l.l" :value="l.v"></el-option></el-select>',
      '    <el-select v-model="fStatus" placeholder="状态" clearable style="width:110px"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button type="warning" :loading="importing" @click="doImport">从医保字典导入</el-button>',
      '    <span style="color:var(--yb-ink-2);font-size:13px;margin-left:auto;">共 {{ total }} 项</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="60" :index="seq"></el-table-column>',
      '    <el-table-column prop="code" label="编码" width="90" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '    <el-table-column prop="pyCode" label="拼音码" width="100"><template #default="s">{{ s.row.pyCode || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="门诊用" width="80" align="center"><template #default="s"><el-tag v-if="s.row.otpUseFlag===1" type="success" size="small">用</el-tag><span v-else>—</span></template></el-table-column>',
      '    <el-table-column label="住院用" width="80" align="center"><template #default="s"><el-tag v-if="s.row.iptUseFlag===1" type="primary" size="small">用</el-tag><span v-else>—</span></template></el-table-column>',
      '    <el-table-column label="开放级别" width="150"><template #default="s"><el-tag v-for="t in String(s.row.openLevels||\'\').split(\',\').filter(Boolean)" :key="t" size="small" style="margin-right:4px;">{{ levelMap[t] || t }}</el-tag></template></el-table-column>',
      '    <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag :type="s.row.status===0 ? \'info\' : \'success\'" size="small">{{ s.row.status===0 ? \'停用\' : \'启用\' }}</el-tag></template></el-table-column>',
      '    <el-table-column label="操作" width="120" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :page-sizes="[10,20,50,100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',

      /* ================= 编辑弹窗(无新增, 守医保权威) ================= */
      '  <el-dialog v-model="dlg" :title="\'编辑医疗类别 #\' + editId" width="640px" top="8vh" :close-on-click-modal="false">',
      '    <el-form label-position="top" size="small">',
      '      <el-row :gutter="16">',
      '        <el-col :span="8"><el-form-item label="编码(医保权威, 不可改)"><el-input v-model="form.code" disabled></el-input></el-form-item></el-col>',
      '        <el-col :span="16"><el-form-item label="名称"><el-input v-model="form.name" placeholder="可修正展示名称"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="门诊使用"><el-switch v-model="form.otpUseFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="住院使用"><el-switch v-model="form.iptUseFlag" :active-value="1" :inactive-value="0"></el-switch></el-form-item></el-col>',
      '        <el-col :span="24"><el-form-item label="开放机构级别"><el-checkbox-group v-model="form.levelArr"><el-checkbox v-for="l in levelOpts" :key="l.v" :label="l.v">{{ l.l }}</el-checkbox></el-checkbox-group></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="状态"><el-select v-model="form.status" style="width:100%"><el-option label="启用" :value="1"></el-option><el-option label="停用" :value="0"></el-option></el-select></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="排序号"><el-input v-model.number="form.sortNo" type="number"></el-input></el-form-item></el-col>',
      '        <el-col :span="8"><el-form-item label="备注"><el-input v-model="form.memo"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <div style="font-size:12px;color:var(--yb-ink-2);">说明: 关闭"状态"则门诊/住院下拉均不展示该项; 门诊/住院两开关独立控制场景可选。未勾选任何开放级别将回落全级别。</div>',
      '    </el-form>',
      '    <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
