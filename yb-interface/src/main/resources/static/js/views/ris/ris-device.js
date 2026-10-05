/* RIS 影像设备管理(RisDevice) — his_imaging_device 台账维护。
 * 后端 /api/ris/admin(机构隔离: 非牵头锁定本机构, 牵头可带 orgId 查成员机构; 写仅本机构管理员):
 *   GET  /devices          分页(关键字/设备类型/状态)
 *   POST /device           新增(编号空则服务端生成, 同租户防重)
 *   PUT  /device/{id}      更新(编号不允许变更)
 *   DEL  /device/{id}      逻辑删除
 * 注册: HIS.views.RisDevice; 科室名经 /api/his/dept/list 本地映射; 类前缀 rd-。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 设备类型: DR/CT/MRI/US/DSA/ENDO; DICOM Modality: CR/CT/MR/US/XA/ES */
  var DEVICE_TYPES = [
    { v: 'DR', l: 'DR 数字X线' }, { v: 'CT', l: 'CT' }, { v: 'MRI', l: 'MRI' },
    { v: 'US', l: '超声 US' }, { v: 'DSA', l: 'DSA' }, { v: 'ENDO', l: '内镜 ENDO' }
  ];
  var DEVICE_STATUS = [
    { v: 1, l: '正常', t: 'success' }, { v: 2, l: '维修中', t: 'warning' }, { v: 3, l: '停用', t: 'info' }
  ];
  var MODALITIES = [
    { v: 'CR', l: 'CR (Computed Radiography)' }, { v: 'CT', l: 'CT (Computed Tomography)' },
    { v: 'MR', l: 'MR (Magnetic Resonance)' }, { v: 'US', l: 'US (Ultrasound)' },
    { v: 'XA', l: 'XA (X-Ray Angiography)' }, { v: 'ES', l: 'ES (Endoscopy)' }
  ];

  function typeLabel(v) {
    for (var i = 0; i < DEVICE_TYPES.length; i++) { if (DEVICE_TYPES[i].v === v) { return DEVICE_TYPES[i].l; } }
    return v == null ? '-' : String(v);
  }
  function statusMeta(v) {
    for (var i = 0; i < DEVICE_STATUS.length; i++) {
      if (DEVICE_STATUS[i].v === Number(v)) { return DEVICE_STATUS[i]; }
    }
    return { v: v, l: v == null ? '-' : String(v), t: 'info' };
  }

  (function ensureRdStyles() {
    if (document.getElementById('ris-device-style')) { return; }
    var st = document.createElement('style');
    st.id = 'ris-device-style';
    st.textContent = [
      '.rd-dlg-sec { margin: 2px 0 12px; padding: 7px 0 6px; border-bottom: 1px dashed var(--yb-border);',
      '  font-size: 12px; font-weight: 600; color: var(--yb-ink-2); letter-spacing: .04em; }',
      '.rd-dlg-sec .rd-sec-dot { display: inline-block; width: 7px; height: 7px; border-radius: 2px;',
      '  background: var(--yb-brand); margin-right: 7px; vertical-align: 1px; }',
      '.rd-code { font-family: var(--yb-font-mono); font-size: 12px; color: var(--yb-ink-2); }',
      '.rd-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 5px; vertical-align: 1px; }',
      '.rd-dot.ok { background: var(--yb-success); }',
      '.rd-dot.warn { background: var(--yb-warning); }',
      '.rd-dot.off { background: var(--yb-ink-4); }',
      '.rd-tip { color: var(--yb-ink-4); font-size: 12px; line-height: 1.5; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  HIS.views.RisDevice = {
    name: 'RisDevice',
    data: function () {
      return {
        keyword: '', deviceType: null, status: null,
        page: 1, size: 20, total: 0, list: [], loading: false,
        depts: [],
        typeOpts: DEVICE_TYPES, statusOpts: DEVICE_STATUS, modalityOpts: MODALITIES,
        dlg: { visible: false, saving: false, editing: false, form: {} }
      };
    },
    computed: {
      deptMap: function () {
        var m = {};
        (this.depts || []).forEach(function (d) { m[String(d.id)] = d.deptName; });
        return m;
      }
    },
    created: function () {
      this.loadDepts();
      this.fetch();
    },
    methods: {
      typeLabel: typeLabel,
      statusMeta: statusMeta,
      statusTag: function (v) { return statusMeta(v).t; },
      statusText: function (v) { return statusMeta(v).l; },
      statusDot: function (v) {
        var n = Number(v);
        return n === 1 ? 'ok' : (n === 2 ? 'warn' : 'off');
      },
      deptName: function (id) {
        return id == null ? '-' : (this.deptMap[String(id)] || ('科室#' + id));
      },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/list').then(function (l) { vm.depts = l || []; }).catch(function () { vm.depts = []; });
      },
      search: function () { this.page = 1; this.fetch(); },
      resetFilter: function () {
        this.keyword = ''; this.deviceType = null; this.status = null;
        this.page = 1; this.fetch();
      },
      fetch: function () {
        var vm = this; vm.loading = true;
        var q = '/api/ris/admin/devices?page=' + vm.page + '&size=' + vm.size;
        if (vm.keyword) { q += '&keyword=' + encodeURIComponent(vm.keyword.trim()); }
        if (vm.deviceType) { q += '&deviceType=' + encodeURIComponent(vm.deviceType); }
        if (vm.status != null) { q += '&status=' + vm.status; }
        HIS.get(q).then(function (d) {
          vm.list = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.fetch(); },
      onSize: function (s) { this.size = s; this.page = 1; this.fetch(); },
      emptyForm: function () {
        return {
          deviceCode: '', deviceName: '', deviceType: 'DR', modality: null, deptId: null,
          roomNo: '', aeTitle: '', ipAddress: '', port: null, manufacturer: '', model: '',
          serialNo: '', installDate: null, maxDailySlots: 40, status: 1, doseTracking: 0
        };
      },
      openAdd: function () {
        this.dlg = { visible: true, saving: false, editing: false, form: this.emptyForm() };
      },
      openEdit: function (row) {
        this.dlg = {
          visible: true, saving: false, editing: true, editingId: String(row.id),
          form: {
            deviceCode: String(row.deviceCode || ''), deviceName: row.deviceName || '',
            deviceType: row.deviceType || 'DR', modality: row.modality || null,
            deptId: row.deptId == null ? null : String(row.deptId),
            roomNo: row.roomNo || '', aeTitle: row.aeTitle || '',
            ipAddress: row.ipAddress || '', port: row.port == null ? null : Number(row.port),
            manufacturer: row.manufacturer || '', model: row.model || '',
            serialNo: row.serialNo || '',
            installDate: row.installDate ? String(row.installDate).slice(0, 10) : null,
            maxDailySlots: row.maxDailySlots == null ? null : Number(row.maxDailySlots),
            status: Number(row.status) || 1, doseTracking: Number(row.doseTracking) || 0
          }
        };
      },
      submit: function () {
        var vm = this;
        var f = vm.dlg.form;
        if (!f.deviceName || !String(f.deviceName).trim()) { ElementPlus.ElMessage.warning('请填写设备名称'); return; }
        if (!f.deviceType) { ElementPlus.ElMessage.warning('请选择设备类型'); return; }
        var body = {
          deviceName: String(f.deviceName).trim(),
          deviceType: f.deviceType,
          modality: f.modality || null,
          deptId: f.deptId || null,
          roomNo: f.roomNo || null,
          aeTitle: f.aeTitle || null,
          ipAddress: f.ipAddress || null,
          port: f.port == null ? null : Number(f.port),
          manufacturer: f.manufacturer || null,
          model: f.model || null,
          serialNo: f.serialNo || null,
          installDate: f.installDate || null,
          maxDailySlots: f.maxDailySlots == null ? null : Number(f.maxDailySlots),
          status: Number(f.status) || 1,
          doseTracking: Number(f.doseTracking) || 0
        };
        vm.dlg.saving = true;
        var p = vm.dlg.editing
          ? HIS.put('/api/ris/admin/device/' + encodeURIComponent(vm.dlg.editingId), body)
          : HIS.post('/api/ris/admin/device', body);
        p.then(function () {
          HIS.notifySuccess(vm.dlg.editing ? '设备已更新' : '设备已新增');
          vm.dlg.visible = false;
          vm.fetch();
        }).catch(HIS.notifyError).finally(function () { vm.dlg.saving = false; });
      },
      del: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm(
          '确认删除设备「' + row.deviceName + '(' + row.deviceCode + ')」? 逻辑删除, 已有排程与执行记录保留审计。',
          '删除设备', { type: 'warning' }
        ).then(function () {
          return HIS.del('/api/ris/admin/device/' + encodeURIComponent(row.id));
        }).then(function () {
          HIS.notifySuccess('已删除');
          vm.fetch();
        }).catch(function (e) { if (e !== 'cancel' && e && e.message) { HIS.notifyError(e); } });
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">影像设备管理',
      '    <span style="font-size:12px;color:var(--yb-ink-3);font-weight:normal;">(his_imaging_device · 排程/Worklist 设备真源, modality+AE Title 供 DICOM 对接)</span>',
      '  </div>',
      '  <div class="toolbar">',
      '    <el-input v-model="keyword" placeholder="编号/名称/AE Title/厂商/型号/机房号检索" clearable style="width:270px" @keyup.enter="search"></el-input>',
      '    <el-select v-model="deviceType" placeholder="设备类型" clearable style="width:150px" @change="search">',
      '      <el-option v-for="t in typeOpts" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '    </el-select>',
      '    <el-select v-model="status" placeholder="状态" clearable style="width:120px" @change="search">',
      '      <el-option v-for="s in statusOpts" :key="s.v" :label="s.l" :value="s.v"></el-option>',
      '    </el-select>',
      '    <el-button type="primary" @click="search">检索</el-button>',
      '    <el-button @click="resetFilter">重置</el-button>',
      '    <span style="flex:1;"></span>',
      '    <el-button type="success" @click="openAdd">新增设备</el-button>',
      '    <span class="rd-tip">共 {{ total }} 台</span>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small" height="100%">',
      '    <el-table-column type="index" label="序号" width="56" :index="seqNo"></el-table-column>',
      '    <el-table-column prop="deviceCode" label="设备编号" width="150"><template #default="s"><span class="rd-code">{{ s.row.deviceCode }}</span></template></el-table-column>',
      '    <el-table-column prop="deviceName" label="设备名称" min-width="170" show-overflow-tooltip></el-table-column>',
      '    <el-table-column label="设备类型" width="120"><template #default="s"><el-tag size="small" effect="plain">{{ typeLabel(s.row.deviceType) }}</el-tag></template></el-table-column>',
      '    <el-table-column prop="modality" label="Modality" width="94"><template #default="s"><span class="rd-code">{{ s.row.modality || \'-\' }}</span></template></el-table-column>',
      '    <el-table-column label="所属科室" width="130" show-overflow-tooltip><template #default="s">{{ deptName(s.row.deptId) }}</template></el-table-column>',
      '    <el-table-column prop="roomNo" label="机房号" width="86"><template #default="s">{{ s.row.roomNo || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="aeTitle" label="AE Title" width="130" show-overflow-tooltip><template #default="s"><span class="rd-code">{{ s.row.aeTitle || \'-\' }}</span></template></el-table-column>',
      '    <el-table-column prop="manufacturer" label="厂商" width="110" show-overflow-tooltip><template #default="s">{{ s.row.manufacturer || \'-\' }}</template></el-table-column>',
      '    <el-table-column prop="model" label="型号" width="110" show-overflow-tooltip><template #default="s">{{ s.row.model || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="96">',
      '      <template #default="s"><el-tag size="small" :type="statusTag(s.row.status)"><span class="rd-dot" :class="statusDot(s.row.status)"></span>{{ statusText(s.row.status) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column prop="maxDailySlots" label="日上限" width="76" align="center"><template #default="s">{{ s.row.maxDailySlots == null ? \'-\' : s.row.maxDailySlots }}</template></el-table-column>',
      '    <el-table-column label="操作" width="120" fixed="right"><template #default="s">',
      '      <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>',
      '      <el-button link type="danger" @click="del(s.row)">删除</el-button>',
      '    </template></el-table-column>',
      '  </el-table>',
      '  <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '    :total="total" :page-size="size" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',

      '  <el-dialog v-model="dlg.visible" :title="dlg.editing ? \'编辑影像设备\' : \'新增影像设备\'" width="640px" append-to-body top="6vh">',
      '    <el-form :model="dlg.form" label-width="108px" size="small">',
      '      <div class="rd-dlg-sec"><span class="rd-sec-dot"></span>基础信息</div>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="设备编号" required>',
      '          <el-input v-model="dlg.form.deviceCode" :disabled="dlg.editing" placeholder="留空自动生成"></el-input>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="设备名称" required>',
      '          <el-input v-model="dlg.form.deviceName" placeholder="如 联影 uCT 760"></el-input>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="设备类型" required>',
      '          <el-select v-model="dlg.form.deviceType" style="width:100%">',
      '            <el-option v-for="t in typeOpts" :key="t.v" :label="t.l" :value="t.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="所属科室">',
      '          <el-select v-model="dlg.form.deptId" filterable clearable placeholder="选择科室" style="width:100%">',
      '            <el-option v-for="d in depts" :key="String(d.id)" :label="d.deptName" :value="String(d.id)"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="厂商"><el-input v-model="dlg.form.manufacturer" placeholder="如 联影/西门子"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="型号"><el-input v-model="dlg.form.model"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="设备序列号"><el-input v-model="dlg.form.serialNo"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="安装日期">',
      '          <el-date-picker v-model="dlg.form.installDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%"></el-date-picker>',
      '        </el-form-item></el-col>',
      '      </el-row>',

      '      <div class="rd-dlg-sec"><span class="rd-sec-dot"></span>DICOM 对接</div>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="Modality">',
      '          <el-select v-model="dlg.form.modality" filterable clearable placeholder="DICOM 模态" style="width:100%">',
      '            <el-option v-for="m in modalityOpts" :key="m.v" :label="m.l" :value="m.v"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="AE Title"><el-input v-model="dlg.form.aeTitle" placeholder="如 CT_AETITLE"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="设备 IP"><el-input v-model="dlg.form.ipAddress" placeholder="如 192.168.1.60"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="DICOM 端口">',
      '          <el-input-number v-model="dlg.form.port" :min="0" :max="65535" controls-position="right" style="width:100%"></el-input-number>',
      '        </el-form-item></el-col>',
      '      </el-row>',

      '      <div class="rd-dlg-sec"><span class="rd-sec-dot"></span>排程与状态</div>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="每日最大量">',
      '          <el-input-number v-model="dlg.form.maxDailySlots" :min="1" :max="999" controls-position="right" style="width:100%"></el-input-number>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="机房号"><el-input v-model="dlg.form.roomNo" placeholder="如 CT-1"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="12">',
      '        <el-col :span="12"><el-form-item label="状态">',
      '          <el-radio-group v-model="dlg.form.status">',
      '            <el-radio v-for="s in statusOpts" :key="s.v" :value="s.v">{{ s.l }}</el-radio>',
      '          </el-radio-group>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="剂量追踪">',
      '          <el-switch v-model="dlg.form.doseTracking" :active-value="1" :inactive-value="0"></el-switch>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button size="small" @click="dlg.visible=false">取消</el-button>',
      '      <el-button size="small" type="primary" :loading="dlg.saving" @click="submit">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };
})();
