/* 门诊医生站处方面板：处方分类、用药分组、合理用药审查、模板及处方生命周期。 */
;(function () {
  const RX_TYPES = {
    NORMAL: { label: '普通处方', cssClass: 'rx-normal', maxDrugs: 5, maxDays: 7 },
    EMERGENCY: { label: '急诊处方', cssClass: 'rx-emergency', maxDrugs: 5, maxDays: 3 },
    PEDIATRIC: { label: '儿科处方', cssClass: 'rx-pediatric', maxDrugs: 5, maxDays: 7 },
    NARCOTIC: { label: '麻醉药品处方', cssClass: 'rx-narcotic', maxDrugs: 99, maxDays: 3 },
    PSYCHO1: { label: '精一处方', cssClass: 'rx-narcotic', maxDrugs: 99, maxDays: 3 },
    PSYCHO2: { label: '精二处方', cssClass: 'rx-normal', maxDrugs: 99, maxDays: 7 },
    TCM_HERB: { label: '中药饮片处方', cssClass: 'rx-normal', maxDrugs: 99, maxDays: 99 }
  };

  const USAGE_METHODS = [
    { value: '口服', label: '口服 po' },
    { value: '静脉注射', label: '静脉注射 iv' },
    { value: '静脉滴注', label: '静脉滴注 ivgtt' },
    { value: '肌肉注射', label: '肌肉注射 im' },
    { value: '皮下注射', label: '皮下注射 sc' },
    { value: '外用', label: '外用' },
    { value: '含服', label: '含服' },
    { value: '舌下含化', label: '舌下含化' },
    { value: '雾化吸入', label: '雾化吸入' },
    { value: '直肠给药', label: '直肠给药' },
    { value: '阴道给药', label: '阴道给药' },
    { value: '滴眼', label: '滴眼' },
    { value: '滴耳', label: '滴耳' },
    { value: '滴鼻', label: '滴鼻' }
  ];

  const FREQUENCIES = [
    { value: 'QD', label: '每日一次(QD)', times: 1 },
    { value: 'BID', label: '每日两次(BID)', times: 2 },
    { value: 'TID', label: '每日三次(TID)', times: 3 },
    { value: 'QID', label: '每日四次(QID)', times: 4 },
    { value: 'Q8H', label: '每8小时一次(Q8H)', times: 3 },
    { value: 'Q12H', label: '每12小时一次(Q12H)', times: 2 },
    { value: 'QOD', label: '隔日一次(QOD)', times: 0.5 },
    { value: 'QW', label: '每周一次(QW)', times: 1 / 7 },
    { value: 'PRN', label: '必要时(PRN)', times: 1 },
    { value: 'ST', label: '立即(ST)', times: 1 },
    { value: 'QN', label: '每晚一次(QN)', times: 1 },
    { value: 'Bid-AC', label: '饭前两次', times: 2 },
    { value: 'Tid-AC', label: '饭前三次', times: 3 }
  ];

  const CONTRAINDICATIONS = [
    ['头孢', '酒精'], ['头孢', '含乙醇'], ['甲氨蝶呤', '非甾体'],
    ['地高辛', '钙剂'], ['华法林', '阿司匹林'], ['卡托普利', '螺内酯'],
    ['氨基糖苷', '呋塞米'], ['红霉素', '特非那定'], ['利福平', '异烟肼'],
    ['磺胺', '甲氧苄啶']
  ];

  function money(value) {
    return Number(value || 0).toFixed(2);
  }

  function text(value) {
    return value === null || value === undefined ? '' : String(value);
  }

  function unwrap(value) {
    return value && Object.prototype.hasOwnProperty.call(value, 'value') ? value.value : value;
  }

  const PrescriptionPanel = {
    name: 'DwPrescriptionPanel',
    inject: ['currentVisit', 'currentPatient', 'diagnoses'],
    emits: ['rx-saved', 'count-update', 'print-rx', 'insert-to-record'],
    template: `
      <section class="dw-panel dw-prescription-panel" :class="[rxTypeConfig.cssClass, { 'is-folded': folded }]">
        <header class="dw-panel-header" style="gap:8px;flex-wrap:wrap">
          <div style="display:flex;align-items:center;gap:7px">
            <strong>处方</strong>
            <el-select v-model="rxType" size="small" style="width:132px" @change="onManualTypeChange">
              <el-option v-for="option in rxTypeOptions" :key="option.value" :label="option.label" :value="option.value"></el-option>
            </el-select>
            <span v-if="rxItems.length" class="dim">{{ rxItems.length }}种 / {{ groupedItems.length }}组</span>
          </div>
          <div style="display:flex;align-items:center;gap:6px;margin-left:auto">
            <el-select v-model="selectedPharmacyId" size="small" clearable filterable placeholder="发药药房" style="width:118px" @change="onPharmacyChange">
              <el-option v-for="ph in pharmacies" :key="ph.id" :label="ph.name" :value="ph.id"></el-option>
            </el-select>
            <el-select v-model="selectedTemplateId" size="small" clearable filterable placeholder="常用处方" style="width:124px" @change="applyTemplate">
              <el-option v-for="tpl in templates" :key="tpl.id" :label="tpl.name" :value="tpl.id"></el-option>
            </el-select>
            <el-button link size="small" :disabled="!rxItems.length" @click="saveAsTemplate">存为常用</el-button>
            <button class="dw-collapse-btn" :title="folded ? '展开处方面板' : '折叠处方面板'" @click="folded=!folded">{{ folded ? '▸' : '▾' }}</button>
          </div>
        </header>

        <div v-if="!visitId" class="dw-slim-empty">未选择患者, 处方面板暂不可用</div>

        <div v-else-if="!folded" class="dw-rx-editor dw-panel-body">
          <div class="dw-rx-pickrow">
            <el-select class="dw-rx-pick" ref="rxPick" v-model="pickDrugId" size="small" filterable remote reserve-keyword clearable :remote-method="remoteSearchDrug" :loading="searching" :disabled="!canEdit" placeholder="检索药品：通用名 / 编码 / 拼音简码，选中即加入当前处方组" @change="onPickDrug">
              <el-option v-for="d in searchResults" :key="d.id" :label="d.genericName || d.itemName" :value="d.id">
                <div class="dw-rx-opt"><span class="nm">{{ d.genericName || d.itemName }}</span><span class="spec">{{ d.spec }}</span><span class="price">¥{{ unitPriceOf(d) }}</span><span class="stock">库存 {{ stockText(d) }}</span><span v-if="!d.medListCodg && !d.ybDrugCode" class="dw-tag self">自费</span></div>
              </el-option>
            </el-select>
            <span class="dw-rx-curgrp" title="当前活跃分组，新选药品将并入此组">Rp.{{ currentGroupNo }}</span>
            <el-button size="small" plain :disabled="!canEdit" @click="newGroup">＋新组</el-button>
          </div>

          <el-table v-if="rxItems.length" class="dw-rx-table" :data="rxItems" border size="small" row-key="_key" :max-height="340">
            <el-table-column label="组" width="40" align="center"><template #default="s"><span class="dw-rx-grp" :class="grpClassOf(s.row)">{{ s.row.groupNo }}</span></template></el-table-column>
            <el-table-column label="药品" min-width="140">
              <template #default="s"><div class="dw-rx-nm" :title="s.row.itemName + ' ' + s.row.spec">{{ s.row.itemName }}<span class="spec">{{ s.row.spec }}</span><span v-if="!s.row.medListCodg" class="dw-tag self">自费</span><span v-if="s.row._warningLevel" class="dw-tag dw-tag--warning" :title="warningTitle(s.row)">审查</span></div></template>
            </el-table-column>
            <el-table-column label="剂量" width="88"><template #default="s"><el-input v-model="s.row.dosage" size="small" :disabled="!canEdit" @change="calcQty(s.row)"></el-input></template></el-table-column>
            <el-table-column label="用法" width="104"><template #default="s"><el-select v-model="s.row.usageMethod" size="small" placeholder="用法" :disabled="!canEdit" @change="onGroupFieldChange(s.row,'usageMethod')"><el-option v-for="option in usageMethods" :key="option.value" :label="option.label" :value="option.value"></el-option></el-select></template></el-table-column>
            <el-table-column label="频次" width="104"><template #default="s"><el-select v-model="s.row.frequency" size="small" placeholder="频次" :disabled="!canEdit" @change="frequencyChanged(s.row)"><el-option v-for="option in frequencies" :key="option.value" :label="option.label" :value="option.value"></el-option></el-select></template></el-table-column>
            <el-table-column label="天数" width="70"><template #default="s"><el-input-number v-model="s.row.days" :min="1" :max="typeConfigForItem(s.row).maxDays" size="small" controls-position="right" :disabled="!canEdit" @change="calcQty(s.row)" style="width:62px"></el-input-number></template></el-table-column>
            <el-table-column label="发药量" width="80"><template #default="s"><el-input-number v-model="s.row.quantity" :min="1" size="small" controls-position="right" :disabled="!canEdit" style="width:72px"></el-input-number></template></el-table-column>
            <el-table-column label="金额" width="66" align="right"><template #default="s"><span class="amt">¥{{ lineAmount(s.row) }}</span></template></el-table-column>
            <el-table-column label="" width="40" align="center"><template #default="s"><el-button link type="danger" size="small" :disabled="!canEdit" @click="removeItem(s.row)">删</el-button></template></el-table-column>
          </el-table>
          <div v-if="!rxItems.length" class="dw-collapse-empty">检索并选中药品即加入处方；同一给药可用“＋新组”分开</div>

          <div class="dw-foot-bar">
            <span>{{ rxItems.length }}种 · {{ groupedItems.length }}组 · 合计 <b>¥{{ rxTotal }}</b><span v-if="hasMixedHerb" class="dw-tag dw-tag--warning" style="margin-left:8px">含中药饮片将自动拆方</span></span>
            <div style="display:flex;gap:6px">
              <el-button size="small" :disabled="!canEdit || !rxItems.length" title="将处方组摘要追加到病历治疗意见" @click="insertToRecord">插入病历</el-button>
              <el-button type="primary" size="small" :loading="saving" :disabled="!canEdit || !rxItems.length" @click="savePrescription">审核并开立处方</el-button>
            </div>
          </div>

          <div class="dw-rx-done">
            <div class="dw-section">已开立处方</div>
            <prescription-list :rows="prescriptions" :expanded="true" @cancel="cancelPrescription" @print="printPrescription"></prescription-list>
            <div class="dw-rx-allergy" :class="allergyHistory ? 'has' : ''"><b>过敏史</b>　{{ allergyHistory || '未记录（开方前请主动核实）' }}</div>
          </div>
        </div>
      </section>
    `,
    data: function () {
      return {
        rxTypes: RX_TYPES,
        rxType: 'NORMAL',
        rxItems: [],
        currentGroupNo: 1,
        usageMethods: USAGE_METHODS,
        frequencies: FREQUENCIES,
        keyword: '',
        pickDrugId: null,
        searchResults: [],
        searching: false,
        saving: false,
        templates: [],
        selectedTemplateId: null,
        prescriptions: [],
        itemSequence: 0,
        loadedVisitId: null,
        folded: false,
        pharmacies: [],
        selectedPharmacyId: null,
        pharmacyAutoResolved: false
      };
    },
    computed: {
      visit: function () { return unwrap(this.currentVisit) || null; },
      patient: function () { return unwrap(this.currentPatient) || this.visit || {}; },
      visitId: function () { return this.visit && (this.visit.id || this.visit.visitId); },
      canEdit: function () { return !!this.visitId && (!this.visit || Number(this.visit.visitStatus || 2) < 3); },
      isExpanded: function () { return true; },
      rxTypeOptions: function () {
        return Object.keys(RX_TYPES).map(function (key) { return { value: key, label: RX_TYPES[key].label }; });
      },
      rxTypeConfig: function () { return RX_TYPES[this.rxType] || RX_TYPES.NORMAL; },
      allergyHistory: function () { return text(this.patient.allergyHistory || (this.visit && this.visit.allergyHistory)); },
      groupedItems: function () {
        var groups = [];
        var index = {};
        (this.rxItems || []).forEach(function (item) {
          var no = Number(item.groupNo) || 1;
          if (!index[no]) {
            index[no] = { groupNo: no, items: [] };
            groups.push(index[no]);
          }
          index[no].items.push(item);
        });
        return groups;
      },
      rxTotal: function () {
        return money(this.rxItems.reduce(function (sum, item) {
          return sum + Number(item.price || 0) * Number(item.quantity || 0);
        }, 0));
      },
      hasMixedHerb: function () {
        var vm = this;
        var herb = this.rxItems.some(function (item) { return vm.isHerbItem(item); });
        var other = this.rxItems.some(function (item) { return !vm.isHerbItem(item); });
        return herb && other;
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler: function (id) {
          if (id === this.loadedVisitId) { return; }
          this.loadedVisitId = id;
          this.rxItems = [];
          this.currentGroupNo = 1;
          this.searchResults = [];
          this.setDefaultRxType();
          this.initPharmacyScope();
          if (id) { this.loadPrescriptions(); }
          else { this.prescriptions = []; }
        }
      }
    },
    created: function () { this.loadTemplates(); },
    methods: {
      money: money,
      suggestedRxType: function () {
        var visit = this.visit || {};
        var patient = this.patient || {};
        if (text(visit.medType) === '13') { return 'EMERGENCY'; }
        if (Number(patient.age) > 0 && Number(patient.age) < 14) { return 'PEDIATRIC'; }
        return 'NORMAL';
      },
      setDefaultRxType: function () { this.rxType = this.suggestedRxType(); },
      /* ===== 三期: 发药药房选择(科室×渠道默认预载 + 手选改房重取价/库存) ===== */
      loadPharmacies: function () {
        var vm = this;
        return window.HIS.get('/api/his/pharmacy/pharmacy-def').then(function (rows) {
          vm.pharmacies = rows || [];
        }).catch(function () { vm.pharmacies = []; });
      },
      initPharmacyScope: function () {
        var vm = this;
        vm.selectedPharmacyId = null;
        vm.pharmacyAutoResolved = false;
        if (!vm.visitId) { return Promise.resolve(); }
        return vm.loadPharmacies().then(function () { vm.autoResolvePharmacy(); });
      },
      autoResolvePharmacy: function () {
        var vm = this;
        var visit = vm.visit || {};
        if (!visit.deptId) { return; }
        window.HIS.get('/api/his/pharmacy/resolve-default?deptId=' + encodeURIComponent(visit.deptId)
          + '&rxType=' + encodeURIComponent(vm.currentRxTypeLabel())).then(function (data) {
          if (data && data.pharmacyId && !vm.selectedPharmacyId) {
            vm.selectedPharmacyId = data.pharmacyId;
            vm.pharmacyAutoResolved = true;
            vm.searchDrugs();
          }
        }).catch(function () {});
      },
      currentRxTypeLabel: function () { return (RX_TYPES[this.rxType] || RX_TYPES.NORMAL).label; },
      onPharmacyChange: function () {
        var vm = this;
        vm.pharmacyAutoResolved = false;
        vm.searchDrugs();
        // 已加明细按新药房生效价重算(服务端开方时仍会重算兑底, 此处仅预览对齐)
        window.HIS.get('/api/org-catalog/available/drug?page=1&size=200&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId || ''))
          .then(function (data) {
            var map = {};
            ((data && data.records) || []).forEach(function (row) { map[row.id] = row.effPrice != null ? row.effPrice : row.retailPrice; });
            var changed = 0;
            vm.rxItems.forEach(function (item) {
              if (item.drugId && map[item.drugId] != null && Number(map[item.drugId]) !== Number(item.price)) {
                item.price = Number(map[item.drugId]); changed++;
              }
            });
            if (changed) { ElementPlus.ElMessage.info('药房变更,' + changed + '种药品价格已刷新'); }
          }).catch(function () {});
      },
      unitPriceOf: function (drug) { return money(drug.effPrice != null ? drug.effPrice : drug.retailPrice); },
      onManualTypeChange: function () {
        if (this.rxType !== 'TCM_HERB') { this.autoResolvePharmacy(); }
        var cfg = this.rxTypeConfig;
        this.rxItems.forEach(function (item) {
          if (!item._detectedType || item._detectedType === 'NORMAL') { item._rxType = this.rxType; }
          if (Number(item.days) > cfg.maxDays && !this.isHerbItem(item)) { item.days = cfg.maxDays; }
        }, this);
      },
      typeTagClass: function (type) {
        return type === 'NARCOTIC' || type === 'PSYCHO1' ? 'dw-tag--danger' : (type === 'EMERGENCY' ? 'dw-tag--warning' : 'dw-tag--info');
      },
      detectRxType: function (drug) {
        var source = [drug.majorClass, drug.drugClass, drug.drugClassName, drug.srcType, drug.itemName, drug.genericName].map(text).join('|');
        if (/中药饮片|饮片|草药/.test(source)) { return 'TCM_HERB'; }
        if (/麻醉|麻药/.test(source)) { return 'NARCOTIC'; }
        if (/第一类精神|精一|精神一类/.test(source)) { return 'PSYCHO1'; }
        if (/第二类精神|精二|精神二类/.test(source)) { return 'PSYCHO2'; }
        return 'NORMAL';
      },
      isHerbItem: function (item) {
        return !!item && (item._detectedType === 'TCM_HERB' || (!item._detectedType && this.detectRxType(item) === 'TCM_HERB'));
      },
      typeConfigForItem: function (item) { return RX_TYPES[(item && item._rxType) || this.rxType] || this.rxTypeConfig; },
      normalizeDrug: function (drug) {
        var type = drug.rxCategory || drug._rxType || this.detectRxType(drug);
        var maxDays = (RX_TYPES[type] || this.rxTypeConfig).maxDays;
        var item = {
          drugId: drug.drugId || drug.id,
          itemId: drug.itemId || drug.id,
          itemCode: drug.itemCode || drug.drugCode,
          itemName: drug.itemName || drug.genericName,
          spec: drug.spec || '',
          unit: drug.unit || drug.minUnit || '',
          price: drug.price != null ? drug.price : (drug.effPrice != null ? drug.effPrice : drug.retailPrice),
          quantity: Number(drug.quantity) || 1,
          dosage: drug.dosage != null ? drug.dosage : '',
          dosageUnit: drug.dosageUnit || drug.doseUnit || '',
          usageMethod: drug.usageMethod || '',
          frequency: drug.frequency || '',
          administration: drug.administration || '',
          groupNo: Number(drug.groupNo) || this.currentGroupNo,
          days: Number(drug.days) || Math.min(3, maxDays),
          medListCodg: drug.medListCodg || drug.ybDrugCode || '',
          unitDose: drug.unitDose,
          packRatio: drug.packRatio,
          roundRule: drug.roundRule,
          manufacturer: drug.manufacturer || '',
          majorClass: drug.majorClass || '',
          drugClass: drug.drugClass || '',
          drugClassName: drug.drugClassName || '',
          _rxType: type === 'NORMAL' ? (this.rxType === 'TCM_HERB' ? this.suggestedRxType() : this.rxType) : type,
          _detectedType: type,
          _key: 'rx-item-' + (++this.itemSequence),
          _warnings: [],
          _warningLevel: '',
          _safetyReason: ''
        };
        return item;
      },
      searchDrugs: function () {
        var vm = this;
        vm.searching = true;
        var url = '/api/org-catalog/available/drug?page=1&size=' + (vm.isExpanded ? 100 : 20);
        if (text(vm.keyword).trim()) { url += '&keyword=' + encodeURIComponent(text(vm.keyword).trim()); }
        if (vm.selectedPharmacyId) { url += '&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId); }
        window.HIS.get(url).then(function (data) {
          vm.searchResults = (data && data.records) || [];
        }).catch(window.HIS.notifyError).finally(function () { vm.searching = false; });
      },
      /* 行式处方编辑器: 顶部 type-ahead 检索, 选中即并入当前活跃组 */
      remoteSearchDrug: function (query) {
        var vm = this;
        var kw = text(query).trim();
        if (!kw) { vm.searchResults = []; return; }
        vm.searching = true;
        var url = '/api/org-catalog/available/drug?page=1&size=30&keyword=' + encodeURIComponent(kw);
        if (vm.selectedPharmacyId) { url += '&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId); }
        window.HIS.get(url).then(function (data) {
          vm.searchResults = (data && data.records) || [];
        }).catch(window.HIS.notifyError).finally(function () { vm.searching = false; });
      },
      onPickDrug: function (id) {
        var vm = this;
        if (!id) { return; }
        var drug = vm.searchResults.find(function (d) { return String(d.id) === String(id); });
        vm.pickDrugId = null;
        if (drug) { vm.addDrug(drug); }
      },
      grpClassOf: function (item) {
        if (this.isHerbItem(item)) { return 'dw-rx-grp--tcm'; }
        if (item._rxType === 'NARCOTIC' || item._rxType === 'PSYCHO1') { return 'dw-rx-grp--nar'; }
        if (item.usageMethod === '外用') { return 'dw-rx-grp--ext'; }
        return '';
      },
      checkDrugSafety: function (drug) {
        var warnings = [];
        var allergies = this.allergyHistory.toLowerCase();
        var drugName = text(drug.itemName);
        if (allergies && drugName && allergies.indexOf(drugName.substring(0, 2).toLowerCase()) >= 0) {
          warnings.push({ level: 'fatal', msg: '患者对“' + this.allergyHistory + '”过敏，该药品可能引发过敏反应！' });
        }
        var duplicate = this.rxItems.find(function (item) { return item.itemCode === drug.itemCode; });
        if (duplicate) { warnings.push({ level: 'serious', msg: '药品“' + drug.itemName + '”已在处方中，请勿重复添加' }); }
        var cfg = RX_TYPES[drug._rxType === 'NORMAL' ? this.rxType : drug._rxType] || RX_TYPES.NORMAL;
        var unique = new Set(this.rxItems.filter(function (item) {
          return this.isHerbItem(item) === this.isHerbItem(drug);
        }, this).map(function (item) { return item.itemCode; }));
        if (!unique.has(drug.itemCode) && unique.size >= cfg.maxDrugs) {
          warnings.push({ level: 'serious', msg: cfg.label + '每张不得超过' + cfg.maxDrugs + '种药品' });
        }
        if (Number(drug.days) > cfg.maxDays) {
          warnings.push({ level: 'serious', msg: cfg.label + '用量不得超过' + cfg.maxDays + '天（当前' + drug.days + '天）' });
        }
        if (drug.unitDose && drug.dosage && Number(drug.dosage) > Number(drug.unitDose) * 2) {
          warnings.push({ level: 'warning', msg: '单次剂量' + drug.dosage + drug.dosageUnit + '超过常规剂量2倍，请确认' });
        }
        for (var i = 0; i < CONTRAINDICATIONS.length; i++) {
          var a = CONTRAINDICATIONS[i][0];
          var b = CONTRAINDICATIONS[i][1];
          var names = this.rxItems.map(function (item) { return text(item.itemName); });
          if ((drugName.indexOf(a) >= 0 && names.some(function (name) { return name.indexOf(b) >= 0; })) ||
              (drugName.indexOf(b) >= 0 && names.some(function (name) { return name.indexOf(a) >= 0; }))) {
            warnings.push({ level: 'warning', msg: '配伍提示：“' + drug.itemName + '”与现有药品可能存在相互作用' });
          }
        }
        if (!drug.medListCodg) { warnings.push({ level: 'info', msg: '“' + drug.itemName + '”非医保目录药品（自费）' }); }
        var stock = Number(drug.stockQty != null ? drug.stockQty : -1);
        if (stock >= 0 && stock < Number(drug.quantity || 1)) {
          warnings.push({ level: 'warning', msg: '所选药房库存不足（可用' + drug.stockQty + '，本次需' + (drug.quantity || 1) + '），发药环节可能需改派药房' });
        }
        if (this.rxItems.length && this.rxItems.some(function (item) { return this.isHerbItem(item) !== this.isHerbItem(drug); }, this)) {
          warnings.push({ level: 'info', msg: '中药饮片与西药/中成药将自动拆分为两张独立处方' });
        }
        return warnings;
      },
      handleSafetyWarnings: function (drug, warnings) {
        var fatal = warnings.filter(function (warning) { return warning.level === 'fatal'; });
        var serious = warnings.filter(function (warning) { return warning.level === 'serious'; });
        var caution = warnings.filter(function (warning) { return warning.level === 'warning'; });
        var info = warnings.filter(function (warning) { return warning.level === 'info'; });
        if (fatal.length) {
          return ElementPlus.ElMessageBox.alert(fatal.map(function (w) { return w.msg; }).join('\n'), '致命用药风险', {
            type: 'error', confirmButtonText: '停止添加'
          }).then(function () { return false; });
        }
        var proceed = Promise.resolve({ value: '' });
        if (serious.length) {
          proceed = ElementPlus.ElMessageBox.prompt(serious.map(function (w) { return w.msg; }).join('\n') + '\n如仍需添加，请填写处方理由：', '严重用药警告', {
            type: 'warning', confirmButtonText: '确认继续', cancelButtonText: '取消添加',
            inputPlaceholder: '请输入临床用药理由',
            inputValidator: function (value) { return text(value).trim().length >= 2 ? true : '临床用药理由至少2个字'; }
          });
        }
        return proceed.then(function (result) {
          drug._safetyReason = text(result && result.value).trim();
          if (caution.length) { ElementPlus.ElMessage.warning(caution.map(function (w) { return w.msg; }).join('；')); }
          if (info.length) { ElementPlus.ElMessage.info(info.map(function (w) { return w.msg; }).join('；')); }
          drug._warnings = warnings;
          drug._warningLevel = serious.length ? 'serious' : (caution.length ? 'warning' : '');
          return true;
        }).catch(function () { return false; });
      },
      addDrug: function (source, preferredGroupNo) {
        if (!this.canEdit) { ElementPlus.ElMessage.warning('当前就诊不可开立处方'); return Promise.resolve(false); }
        var vm = this;
        var item = vm.normalizeDrug(source || {});
        if (!item.itemCode || !item.itemName) { ElementPlus.ElMessage.error('药品通用名或院内编码缺失，不能开立'); return Promise.resolve(false); }
        var mixedCategory = vm.rxItems.some(function (row) { return vm.isHerbItem(row) !== vm.isHerbItem(item); });
        if (mixedCategory) {
          item.groupNo = vm.nextGroupNo();
        } else if (preferredGroupNo != null) {
          item.groupNo = Number(preferredGroupNo) || 1;
        } else {
          item.groupNo = vm.currentGroupNo;
        }
        var warnings = vm.checkDrugSafety(item);
        return vm.handleSafetyWarnings(item, warnings).then(function (allowed) {
          if (!allowed) { return false; }
          vm.rxItems.push(item);
          vm.resequenceGroups();
          vm.currentGroupNo = Number(item.groupNo) || 1;
          if (item._detectedType !== 'NORMAL' && !vm.hasMixedHerb) {
            vm.rxType = item._rxType;
            vm.rxItems.forEach(function (row) { if (row._detectedType === 'NORMAL') { row._rxType = vm.rxType; } });
          }
          vm.calcQty(item, true);
          return true;
        });
      },
      nextGroupNo: function () {
        return this.rxItems.reduce(function (max, item) { return Math.max(max, Number(item.groupNo) || 0); }, 0) + 1;
      },
      newGroup: function () { this.currentGroupNo = this.nextGroupNo(); },
      removeItem: function (item) {
        var index = this.rxItems.indexOf(item);
        if (index >= 0) { this.rxItems.splice(index, 1); }
        this.resequenceGroups();
      },
      splitItem: function (item) {
        item.groupNo = this.nextGroupNo();
        this.resequenceGroups();
        this.currentGroupNo = Number(item.groupNo);
      },
      mergeGroup: function (groupNo) {
        var vm = this;
        var targets = vm.groupedItems.map(function (group) { return group.groupNo; }).filter(function (no) { return no !== groupNo; });
        if (!targets.length) { ElementPlus.ElMessage.info('当前没有可合并的其他分组'); return; }
        ElementPlus.ElMessageBox.prompt('可选目标组：' + targets.map(function (no) { return 'Rp.' + no; }).join('、'), '合并 Rp.' + groupNo, {
          type: 'info', inputValue: text(targets[0]), inputPattern: /^\d+$/, inputErrorMessage: '请输入有效组号'
        }).then(function (result) {
          var target = Number(result.value);
          if (targets.indexOf(target) < 0) { ElementPlus.ElMessage.warning('目标组不存在'); return; }
          var sourceItem = vm.rxItems.find(function (item) { return Number(item.groupNo) === Number(groupNo); });
          var targetItem = vm.rxItems.find(function (item) { return Number(item.groupNo) === target; });
          if (sourceItem && targetItem && vm.isHerbItem(sourceItem) !== vm.isHerbItem(targetItem)) {
            ElementPlus.ElMessage.error('中药饮片不能与西药/中成药合并为同一组');
            return;
          }
          vm.rxItems.forEach(function (item) { if (Number(item.groupNo) === Number(groupNo)) { item.groupNo = target; } });
          vm.resequenceGroups();
          vm.currentGroupNo = sourceItem ? Number(sourceItem.groupNo) : 1;
        }).catch(function () {});
      },
      resequenceGroups: function () {
        var map = {};
        var sequence = 0;
        this.rxItems.forEach(function (item) {
          var old = text(item.groupNo || 1);
          if (!map[old]) { map[old] = ++sequence; }
          item.groupNo = map[old];
        });
        this.currentGroupNo = this.rxItems.length ? Math.min(this.currentGroupNo, sequence) || 1 : 1;
      },
      onGroupFieldChange: function (item, field) {
        var vm = this;
        var others = vm.rxItems.filter(function (row) { return row !== item && Number(row.groupNo) === Number(item.groupNo); });
        if (!others.length) { return; }
        var label = field === 'usageMethod' ? '用法' : '频次';
        ElementPlus.ElMessageBox.confirm('是否将' + label + '“' + item[field] + '”同步到本组其他药品？', '同组医嘱同步', {
          type: 'info', confirmButtonText: '同步', cancelButtonText: '仅修改本项'
        }).then(function () {
          others.forEach(function (row) { row[field] = item[field]; if (field === 'frequency') { vm.calcQty(row, true); } });
        }).catch(function () {});
      },
      frequencyChanged: function (item) { this.calcQty(item, true); this.onGroupFieldChange(item, 'frequency'); },
      frequencyTimes: function (value) {
        var found = FREQUENCIES.find(function (option) { return option.value === value; });
        return found ? found.times : 1;
      },
      roundByRule: function (value, rule) {
        if (Number(rule) === 2) { return Math.floor(value); }
        if (Number(rule) === 3) { return Math.round(value); }
        return Math.ceil(value);
      },
      calcQty: function (item, silent) {
        var dosage = Number(item.dosage);
        var unitDose = Number(item.unitDose);
        var days = Number(item.days);
        if (!(dosage > 0 && unitDose > 0 && days > 0)) {
          if (!silent) { ElementPlus.ElMessage.warning('请填写单次剂量，且药品需配置单位含药量'); }
          return;
        }
        var perDose = this.roundByRule(dosage / unitDose, Number(item.roundRule) || 1);
        item.quantity = Math.max(1, Math.ceil(perDose * this.frequencyTimes(item.frequency) * days));
      },
      groupUsage: function (group) { return (group.items[0] && group.items[0].usageMethod) || '未选用法'; },
      groupFrequency: function (group) { return (group.items[0] && group.items[0].frequency) || '未选频次'; },
      groupDays: function (group) { return group.items[0] && group.items[0].days ? group.items[0].days + '天' : ''; },
      groupTypeLabel: function (group) {
        var type = group.items[0] && group.items[0]._rxType;
        return (RX_TYPES[type] || this.rxTypeConfig).label;
      },
      groupClass: function (group) {
        var first = group.items[0] || {};
        if (this.isHerbItem(first)) { return 'dw-rx-group--tcm'; }
        if (first._rxType === 'NARCOTIC' || first._rxType === 'PSYCHO1') { return 'dw-rx-group--narcotic'; }
        return first.usageMethod === '外用' ? 'dw-rx-group--external' : '';
      },
      safetyRowStyle: function (item) {
        if (item._warningLevel === 'serious') { return { borderColor: 'var(--dw-danger)', background: 'var(--dw-danger-light)' }; }
        if (item._warningLevel === 'warning') { return { borderColor: 'var(--dw-warning)', background: 'var(--dw-warning-light)' }; }
        return null;
      },
      warningTitle: function (item) { return (item._warnings || []).map(function (warning) { return warning.msg; }).join('；'); },
      lineAmount: function (item) { return money(Number(item.price || 0) * Number(item.quantity || 0)); },
      stockText: function (drug) {
        var value = drug.stockQty != null ? drug.stockQty : (drug.qty != null ? drug.qty : drug.availableQty);
        return value === null || value === undefined ? '-' : value;
      },
      validateItems: function () {
        if (!this.rxItems.length) { ElementPlus.ElMessage.warning('请添加处方明细'); return false; }
        for (var i = 0; i < this.rxItems.length; i++) {
          var item = this.rxItems[i];
          if (!item.dosage || !item.usageMethod || !item.frequency || !(Number(item.days) > 0) || !(Number(item.quantity) > 0)) {
            ElementPlus.ElMessage.warning('请完整填写“' + item.itemName + '”的剂量、用法、频次、天数和发药量');
            return false;
          }
          if (!USAGE_METHODS.some(function (option) { return option.value === item.usageMethod; }) ||
              !FREQUENCIES.some(function (option) { return option.value === item.frequency; })) {
            ElementPlus.ElMessage.error('用法和频次必须从结构化选项中选择');
            return false;
          }
          var cfg = this.typeConfigForItem(item);
          if (Number(item.days) > cfg.maxDays) { ElementPlus.ElMessage.error(cfg.label + '用量不得超过' + cfg.maxDays + '天'); return false; }
        }
        return true;
      },
      apiItem: function (item) {
        return {
          drugId: item.drugId, itemId: item.itemId, itemCode: item.itemCode, itemName: item.itemName,
          spec: item.spec, unit: item.unit, price: item.price, quantity: item.quantity,
          dosage: text(item.dosage), dosageUnit: item.dosageUnit, usageMethod: item.usageMethod,
          frequency: item.frequency, administration: item.administration, groupNo: text(item.groupNo),
          days: item.days, medListCodg: item.medListCodg, unitDose: item.unitDose,
          packRatio: item.packRatio, roundRule: item.roundRule
        };
      },
      templateItem: function (item) {
        var result = this.apiItem(item);
        result.rxCategory = item._rxType;
        result.manufacturer = item.manufacturer;
        return result;
      },
      prescriptionBatches: function () {
        var vm = this;
        var herbs = vm.rxItems.filter(function (item) { return vm.isHerbItem(item); });
        var others = vm.rxItems.filter(function (item) { return !vm.isHerbItem(item); });
        var batches = [];
        if (others.length) {
          var otherType = vm.rxType === 'TCM_HERB' ? (others[0]._rxType || vm.suggestedRxType()) : vm.rxType;
          batches.push({ rxType: (RX_TYPES[otherType] || RX_TYPES.NORMAL).label, items: others.map(vm.apiItem) });
        }
        if (herbs.length) { batches.push({ rxType: RX_TYPES.TCM_HERB.label, items: herbs.map(vm.apiItem) }); }
        return batches;
      },
      savePrescription: function () {
        if (!this.canEdit || !this.validateItems()) { return; }
        var vm = this;
        var batches = vm.prescriptionBatches();
        vm.saving = true;
        /* C7: 拆方批量开立一次请求原子提交(服务端单事务), 任一批失败整体回滚, 重试不产生重复处方 */
        window.HIS.post('/api/his/prescription/create-batch', {
          visitId: vm.visitId, pharmacyId: vm.selectedPharmacyId || null, batches: batches
        }).then(function (created) {
          var list = created || [];
          ElementPlus.ElMessage.success('已开立' + list.length + '张处方，合计 ¥' + money(list.reduce(function (sum, rx) { return sum + Number(rx.totalAmount || 0); }, 0)));
          vm.rxItems = [];
          vm.currentGroupNo = 1;
          vm.keyword = '';
          vm.searchResults = [];
          vm.loadPrescriptions();
          vm.$emit('rx-saved', list);
        }).catch(window.HIS.notifyError).finally(function () { vm.saving = false; });
      },
      loadPrescriptions: function () {
        var vm = this;
        if (!vm.visitId) { vm.prescriptions = []; return; }
        window.HIS.get('/api/his/prescription/list?visitId=' + encodeURIComponent(vm.visitId)).then(function (rows) {
          vm.prescriptions = rows || [];
          vm.$emit('count-update', vm.prescriptions.length);
        }).catch(function () { vm.prescriptions = []; vm.$emit('count-update', 0); });
      },
      cancelPrescription: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.prompt('作废后不能恢复，请填写作废原因：', '作废处方 ' + row.rxNo, {
          type: 'warning', inputPlaceholder: '请输入作废原因',
          inputValidator: function (value) { return text(value).trim().length >= 2 ? true : '作废原因至少2个字'; }
        }).then(function () {
          return window.HIS.post('/api/his/prescription/cancel?id=' + encodeURIComponent(row.id));
        }).then(function () {
          ElementPlus.ElMessage.success('处方已作废');
          vm.loadPrescriptions();
          vm.$emit('rx-saved');
        }).catch(function (error) { if (error !== 'cancel' && error !== 'close' && error && error.message) { window.HIS.notifyError(error); } });
      },
      printPrescription: function (row) { this.$emit('print-rx', row.id); },
      loadTemplates: function () {
        var vm = this;
        window.HIS.get('/api/his/template/list?type=rx_set').then(function (rows) { vm.templates = rows || []; }).catch(function () { vm.templates = []; });
      },
      parseTemplate: function (template) {
        try {
          var body = typeof template.content === 'string' ? JSON.parse(template.content) : template.content;
          return Array.isArray(body) ? { items: body } : (body || { items: [] });
        } catch (error) {
          ElementPlus.ElMessage.error('常用处方内容格式错误');
          return null;
        }
      },
      applyTemplate: function (id) {
        if (!id) { return; }
        var vm = this;
        var template = vm.templates.find(function (row) { return row.id === id; });
        var body = template && vm.parseTemplate(template);
        if (!body || !Array.isArray(body.items) || !body.items.length) { ElementPlus.ElMessage.warning('该模板没有药品明细'); return; }
        var apply = function () {
          vm.rxItems = [];
          vm.currentGroupNo = 1;
          if (body.rxType && RX_TYPES[body.rxType]) { vm.rxType = body.rxType; }
          return body.items.reduce(function (chain, source) {
            return chain.then(function () { return vm.addDrug(source, source.groupNo); });
          }, Promise.resolve()).then(function () {
            vm.resequenceGroups();
            ElementPlus.ElMessage.success('已套用常用处方：' + template.name);
          });
        };
        if (vm.rxItems.length) {
          ElementPlus.ElMessageBox.confirm('套用模板将替换当前处方草稿，是否继续？', '套用常用处方', { type: 'warning' }).then(apply).catch(function () {});
        } else { apply(); }
      },
      /* 插入病历: 按 Rp 组生成处方摘要追加到病历治疗意见(不自动双写, 经主编排中继) */
      insertToRecord: function () {
        var vm = this;
        if (!vm.rxItems.length) { ElementPlus.ElMessage.warning('当前没有可插入的处方草稿'); return; }
        var lines = vm.groupedItems.map(function (grp) {
          var parts = grp.items.map(function (it) {
            var seg = [text(it.itemName), text(it.spec)];
            if (text(it.dosage)) { seg.push('每次' + it.dosage); }
            if (text(it.usageMethod) || text(it.frequency)) { seg.push((it.usageMethod || '') + (it.frequency ? ' ' + it.frequency : '')); }
            if (text(it.days)) { seg.push(it.days + '天'); }
            seg.push((it.quantity || '') + (it.unit || '支/盒'));
            return seg.filter(function (x) { return String(x).trim(); }).join(' ');
          });
          return 'Rp.' + grp.groupNo + ' ' + parts.join('；');
        });
        vm.$emit('insert-to-record', { target: 'treatment', text: '处方摘要：' + lines.join('；') });
      },
      saveAsTemplate: function () {
        var vm = this;
        if (!vm.rxItems.length) { ElementPlus.ElMessage.warning('当前没有可保存的处方草稿'); return; }
        ElementPlus.ElMessageBox.prompt('请输入常用处方名称', '存为常用处方', {
          inputPlaceholder: '例如：上呼吸道感染常用方',
          inputValidator: function (value) { return text(value).trim() ? true : '请输入模板名称'; }
        }).then(function (result) {
          var visit = vm.visit || {};
          return window.HIS.post('/api/his/template/create', {
            templateType: 'rx_set', name: text(result.value).trim(),
            deptId: visit.deptId || null, staffId: visit.staffId || visit.drId || null,
            content: JSON.stringify({ rxType: vm.rxType, items: vm.rxItems.map(function (item) { return vm.templateItem(item); }) }),
            sortOrder: 0, status: 1
          });
        }).then(function () {
          ElementPlus.ElMessage.success('已保存为常用处方');
          vm.loadTemplates();
        }).catch(function (error) { if (error && error.message) { window.HIS.notifyError(error); } });
      }
    },
    components: {
      PrescriptionList: {
        props: { rows: { type: Array, default: function () { return []; } }, expanded: Boolean },
        emits: ['cancel', 'print'],
        template: `
          <div class="dw-done-list" v-if="rows.length">
            <div class="dw-done-item" v-for="row in rows" :key="row.id">
              <span aria-hidden="true">▸</span>
              <span class="no">{{ row.rxNo }}</span>
              <el-tag size="small" effect="plain">{{ row.rxType }}</el-tag>
              <span class="amt">¥{{ money(row.totalAmount) }}</span>
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              <template v-if="expanded">
                <el-button link type="danger" size="small" :disabled="Number(row.status)!==1" @click="$emit('cancel',row)">作废</el-button>
                <el-button link type="primary" size="small" @click="$emit('print',row)">打印</el-button>
              </template>
            </div>
          </div>
        `,
        methods: {
          money: money,
          statusLabel: function (status) {
            return Number(status) === -1 ? '已作废' : (Number(status) === 1 ? '已开' : (Number(status) === 2 ? '已发药' : (Number(status) === 3 ? '已退药' : '未知')));
          },
          statusType: function (status) {
            return Number(status) === 2 ? 'success' : ((Number(status) === 3 || Number(status) === -1) ? 'danger' : 'info');
          }
        }
      }
    }
  };

  window.HIS = window.HIS || {};
  window.HIS.components = window.HIS.components || {};
  window.HIS.components.DwPrescriptionPanel = PrescriptionPanel;
})();
