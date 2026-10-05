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
  
  /* OP-C 草药/中成药专业化(需求2.2.2.3.14.3): 煎法/炮制/治法固定候选 + 单味倍数基础量 */
  const DECOCTIONS = ['先煎', '后下', '包煎', '另煎', '冲服', '研末', '煎汤代水'];
  const PROCESSES = ['生用', '炒', '麸炒', '蜜炙', '酒炙', '盐炙', '醋炙', '煅', '蒸', '煮'];
  const THERAPIES = ['解表', '泻下', '和解', '温里', '清热', '消导', '补益', '固涩'];

  /* P6 中药饮片处方专属版式: 每日剂次/煎服法候选 + 十八反/十九畏经典配伍禁忌(关键词可含"/"同义多写) */
  const TCM_DAILY_OPTIONS = [
    { value: '日一剂', label: '日一剂' },
    { value: '日二剂分早晚', label: '日二剂(分早晚服)' },
    { value: '日三剂', label: '日三剂' },
    { value: '频服', label: '不拘时频服' }
  ];
  const TCM_DECOCT_METHODS = ['水煎', '代煎', '配方颗粒'];
  /* 十八反: 甘草反海藻/大戟/芫花/甘遂; 乌头(川乌/草乌/附子)反贝母/瓜蒌/半夏/白蔹/白及; 藜芦反人参/沙参/丹参/玄参/苦参/细辛/芍药 */
  const TCM_EIGHTEEN_INCOMPATIBLE = [
    ['甘草', '海藻'], ['甘草', '大戟'], ['甘草', '芫花'], ['甘草', '甘遂'],
    ['川乌/草乌/附子/乌头', '贝母/浙贝/川贝'], ['川乌/草乌/附子/乌头', '瓜蒌/天花粉'], ['川乌/草乌/附子/乌头', '半夏/法夏/姜夏'],
    ['川乌/草乌/附子/乌头', '白蔹'], ['川乌/草乌/附子/乌头', '白及'],
    ['藜芦', '人参/党参/太子参'], ['藜芦', '沙参/丹参/玄参/苦参'], ['藜芦', '细辛'], ['藜芦', '芍药/赤芍/白芍']
  ];
  /* 十九畏: 硫黄畏朴硝; 水银畏砒霜; 狼毒畏密陀僧; 巴豆畏牵牛; 丁香畏郁金; 川乌/草乌畏犀角; 牙硝畏三棱; 官桂畏石脂; 人参畏五灵脂 */
  const TCM_NINETEEN_FEAR = [
    ['硫黄', '朴硝/芒硝'], ['水银', '砒霜'], ['狼毒', '密陀僧'], ['巴豆', '牵牛/黑丑/白丑'],
    ['丁香', '郁金'], ['川乌/草乌/附子', '犀角/水牛角'], ['牙硝/芒硝', '三棱'],
    ['官桂/肉桂/桂枝', '石脂/赤石脂'], ['人参', '五灵脂']
  ];

  function herbHit(name, key) {
    var n = text(name);
    if (!n) { return false; }
    var parts = String(key).split('/');
    for (var i = 0; i < parts.length; i++) { if (parts[i] && n.indexOf(parts[i]) >= 0) { return true; } }
    return false;
  }

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
      <section class="dw-panel dw-prescription-panel" :class="[rxTypeConfig.cssClass, { 'is-folded': folded, 'is-maximized': maximized }]">
        <header class="dw-panel-header">
          <div class="dw-rx-hd-left">
            <strong>处方</strong>
            <el-select v-model="rxType" size="small" style="width:132px" @change="onManualTypeChange">
              <el-option v-for="option in rxTypeOptions" :key="option.value" :label="option.label" :value="option.value"></el-option>
            </el-select>
            <span v-if="rxTypeFlag" class="dw-rx-type-flag" :class="'dw-rx-type-flag--' + rxTypeFlag.key" :title="rxTypeConfig.label">{{ rxTypeFlag.label }}</span>
            <span v-if="rxItems.length" class="dim">{{ rxItems.length }}种 / {{ groupedItems.length }}组</span>
          </div>
          <div class="dw-rx-hd-right">
            <el-select v-model="selectedPharmacyId" size="small" clearable filterable placeholder="发药药房" style="width:118px" @change="onPharmacyChange">
              <el-option v-for="ph in pharmacies" :key="ph.id" :label="ph.name" :value="ph.id"></el-option>
            </el-select>
            <el-select v-model="selectedTemplateId" size="small" clearable filterable placeholder="选择处方组套" style="width:124px" @change="applyTemplate">
              <el-option v-for="tpl in templates" :key="tpl.id" :label="tpl.name" :value="tpl.id"></el-option>
            </el-select>
            <el-button link size="small" :disabled="!rxItems.length" @click="saveAsTemplate">存为组套</el-button>
            <el-button link type="primary" size="small" title="维护个人/科室处方组套" @click="openRxSetManage">组套管理</el-button>
            <button class="dw-panel-max-btn" :title="maximized ? '退出最大化(Esc)' : '最大化处方面板'" @click="toggleMaximize" :aria-label="maximized ? '退出最大化' : '最大化处方面板'">
              <svg viewBox="0 0 24 24" aria-hidden="true"><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>
            </button>
            <button class="dw-collapse-btn" :title="folded ? '展开处方面板' : '折叠处方面板'" @click="toggleFold">{{ folded ? '▸' : '▾' }}</button>
          </div>
        </header>

        <div v-if="!visitId" class="dw-slim-empty">未选择患者, 处方面板暂不可用</div>

        <div v-else-if="!folded" class="dw-rx-editor dw-panel-body">
          <div class="dw-rx-pickrow">
            <el-select class="dw-rx-pick" ref="rxPick" v-model="pickDrugId" size="small" filterable remote reserve-keyword clearable popper-class="dw-rx-pick-popper" :remote-method="remoteSearchDrug" :loading="searching" :disabled="!canEdit" placeholder="检索药品：通用名 / 编码 / 拼音简码；空输入即出精选候选" @change="onPickDrug" @visible-change="onPickVisible">
              <!-- 固定表头: sticky 于下拉滚动容器顶部, 列宽与 .dw-rx-opt1 同栅格保证逐列对齐; 仅有候选时显示 -->
              <div class="dw-rx-head" v-if="displayResults.length">
                <span class="h-idx">#</span>
                <span class="h-nm">药品名称</span>
                <span class="h-spec">规格</span>
                <span class="h-lv">医保</span>
                <span class="h-sp">自付</span>
                <span class="h-price">价格</span>
                <span class="h-stock">可用量</span>
              </div>
              <el-option-group v-for="g in renderGroups" :key="g.label" :label="g.label">
                <el-option v-for="d in g.items" :key="d.id" :label="d.genericName || d.itemName" :value="d.id">
                  <div class="dw-rx-opt1" :title="pickTitle(d)">
                    <span class="c-idx">{{ seqOf(d) }}</span>
                    <span class="c-nm"><b>{{ d.genericName || d.itemName }}</b><span v-if="d.tradeName" class="trade">· {{ d.tradeName }}</span></span>
                    <span class="c-spec">{{ d.spec || '—' }}</span>
                    <span class="c-lv"><span class="dw-rx-lv" :class="lvClass(d)">{{ lvText(d) }}</span></span>
                    <span class="c-sp">{{ selfpayText(d) ? '自付' + selfpayText(d) : '' }}</span>
                    <span class="c-price">¥{{ unitPriceOf(d) }}</span>
                    <span class="c-stock">可用 {{ availText(d) }}</span>
                  </div>
                </el-option>
              </el-option-group>
              <!-- 分页条: 末尾一个 disabled 选项(点击不选中也不关闭下拉), 内嵌真实翻页按钮 + mousedown.prevent 保持面板开启 -->
              <el-option v-if="pickTotalPages > 1" :value="'__pager__'" :disabled="true" class="dw-rx-pager">
                <div class="dw-rx-pager-bar" @mousedown.prevent>
                  <button type="button" class="dw-rx-pager-btn" :disabled="effPage <= 1" @click.stop.prevent="prevPage">‹ 上一页</button>
                  <span class="dw-rx-pager-info">第 {{ effPage }} / {{ pickTotalPages }} 页 · 共 {{ displayResults.length }} 条</span>
                  <button type="button" class="dw-rx-pager-btn" :disabled="effPage >= pickTotalPages" @click.stop.prevent="nextPage">下一页 ›</button>
                </div>
              </el-option>
            </el-select>
            <span class="dw-rx-curgrp" title="当前活跃分组，新选药品将并入此组">Rp.{{ currentGroupNo }}</span>
            <el-button size="small" plain :disabled="!canEdit" @click="newGroup">＋新组</el-button>
            <el-button size="small" plain :disabled="!canEdit || rxItems.length<2" title="按拆方规则(险种/门慢/特药/药房等维度)预览并应用多处方拆分" @click="applySplitSuggest">按规则拆方</el-button>
          </div>

          <div class="dw-rx-filter">
            <span class="dw-rx-filter-lbl">筛选</span>
            <button class="dw-rx-fchip" :class="{ 'is-on': fInpharmacy }" title="仅显示本院发药药房在售(有生效价)" @click="fInpharmacy = !fInpharmacy">仅看本院</button>
            <button class="dw-rx-fchip" :class="{ 'is-on': fStock }" title="仅显示当前药房有可用库存" @click="fStock = !fStock">仅看在药</button>
            <button class="dw-rx-fchip" :class="{ 'is-on': fYb }" title="仅显示已对照医保目录" @click="fYb = !fYb">医保内</button>
          </div>

          <div v-if="assistantChips.length" class="dw-rx-assistant">
            <span class="dw-rx-assistant-lbl">常用助手</span>
            <button v-for="f in assistantChips" :key="f.kind+'-'+f.code" class="dw-rx-chip" :title="(f.count||0)+'次'" :disabled="!canEdit" @click="useFrequent(f)">{{ f.name }}</button>
          </div>
          <div v-if="chronicList.length" class="dw-rx-chronic">
            <span class="dw-rx-chronic-lbl">门慢/门特</span>
            <button v-for="c in chronicList" :key="c.id" class="dw-rx-chip dw-rx-chip--chronic" @click="useChronic(c)">{{ c.diseName }}</button>
          </div>

          <!-- P6 中药饮片处方专属处方头: 治法/剂数/每日剂次/煎服法/全局脚注/忌口 -->
          <div v-if="rxType==='TCM_HERB'" class="dw-tcm-head">
            <div class="dw-tcm-hrow">
              <label class="dw-tcm-fl"><i>治法</i>
                <el-select v-model="tcmTherapy" size="small" clearable filterable placeholder="选治法" style="width:112px" :disabled="!canEdit">
                  <el-option v-for="t in therapies" :key="t" :label="t" :value="t"></el-option>
                </el-select>
              </label>
              <label class="dw-tcm-fl"><i>剂数</i>
                <el-input-number v-model="tcmDoseCount" :min="1" :max="99" size="small" controls-position="right" :disabled="!canEdit" style="width:92px" @change="recalcTcmAllQty"></el-input-number><span class="dw-tcm-unit">付</span>
              </label>
              <label class="dw-tcm-fl"><i>每日剂次</i>
                <el-select v-model="tcmDailyTimes" size="small" :disabled="!canEdit" style="width:132px">
                  <el-option v-for="o in tcmDailyOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>
                </el-select>
              </label>
              <label class="dw-tcm-fl"><i>煎服法</i>
                <el-select v-model="tcmDecoctMethod" size="small" filterable allow-create default-first-option :disabled="!canEdit" style="width:112px">
                  <el-option v-for="o in tcmDecoctMethods" :key="o" :label="o" :value="o"></el-option>
                </el-select>
              </label>
            </div>
            <div class="dw-tcm-hrow">
              <label class="dw-tcm-fl dw-tcm-fl--wide"><i>全局脚注</i>
                <el-input v-model="tcmGlobalFootnote" size="small" clearable :disabled="!canEdit" placeholder="全方通用特殊要求，如先煎、后下、忌铁器"></el-input>
              </label>
              <label class="dw-tcm-fl dw-tcm-fl--wide"><i>忌口</i>
                <el-input v-model="tcmTaboo" size="small" clearable :disabled="!canEdit" placeholder="如：忌生冷、辛辣、油腻、鱼腥"></el-input>
              </label>
              <span class="dw-tcm-hint">{{ rxItems.length }}味 · 每味发药量 = 剂量(g) × {{ tcmDoseCount }}付</span>
            </div>
          </div>

          <div v-if="rxItems.length" class="dw-rx-groups" :style="{ maxHeight: rxTableMaxHeight }">
            <div v-for="group in groupedItems" :key="group.groupNo" class="dw-rx-band" :class="groupClass(group)">
              <div class="dw-rx-band-hd">
                <span class="dw-rx-band-no">Rp.{{ group.groupNo }}</span>
                <span class="dw-rx-band-type">{{ groupTypeLabel(group) }}</span>
                <span v-if="!isHerbGroup(group)" class="dw-rx-band-field"><i>用法</i><el-select :model-value="group.items[0].usageMethod" size="small" placeholder="用法" :disabled="!canEdit" style="width:100px" @change="onGroupUsageChange(group, $event)"><el-option v-for="option in usageMethods" :key="option.value" :label="option.label" :value="option.value"></el-option></el-select></span>
                <span v-if="!isHerbGroup(group)" class="dw-rx-band-field"><i>频次</i><el-select :model-value="group.items[0].frequency" size="small" placeholder="频次" :disabled="!canEdit" style="width:100px" @change="onGroupFreqChange(group, $event)"><el-option v-for="option in frequencies" :key="option.value" :label="option.label" :value="option.value"></el-option></el-select></span>
                <span v-if="!isHerbGroup(group)" class="dw-rx-band-field"><i>天数</i><el-input-number :model-value="group.items[0].days" :min="1" :max="rxTypeConfig.maxDays" size="small" controls-position="right" :disabled="!canEdit" style="width:84px" @change="onGroupDaysChange(group, $event)"></el-input-number></span>
                <span v-if="isHerbGroup(group)" class="dw-rx-band-herb"><i>煎服</i>{{ tcmDecoctMethod }} · {{ tcmDailyTimes }} · 共{{ tcmDoseCount }}付</span>
                <span class="dw-rx-band-sum">本组合计 <b>¥{{ groupTotal(group) }}</b></span>
                <span class="dw-rx-band-ops"><el-button link size="small" :disabled="!canEdit || groupedItems.length<2" title="把本组药品并入其他组" @click="mergeGroup(group.groupNo)">合并组</el-button></span>
              </div>
              <el-table class="dw-rx-table" :data="group.items" border size="small" row-key="_key">
                <el-table-column label="药品" min-width="160">
                  <template #default="s"><div class="dw-rx-nm" :title="itemTitle(s.row)">{{ s.row.itemName }}<span class="spec">{{ s.row.spec }}</span><span class="dw-rx-lv" :class="lvClass(s.row)">{{ lvText(s.row) }}</span><span v-if="s.row._warningLevel" class="dw-tag dw-tag--warning" :title="warningTitle(s.row)">审查</span></div></template>
                </el-table-column>
                <el-table-column label="剂量" width="88"><template #default="s"><el-input v-model="s.row.dosage" size="small" :disabled="!canEdit" @change="calcQty(s.row)" @keyup.enter="backToPick" placeholder="剂量"></el-input></template></el-table-column>
                <el-table-column v-if="rxType==='TCM_HERB' || hasMixedHerb" label="中药专化" width="186"><template #default="s"><div v-if="isHerbItem(s.row)" class="dw-rx-herb"><el-select v-model="s.row.decoction" size="small" placeholder="煎法" :disabled="!canEdit" style="width:82px"><el-option v-for="o in decoctions" :key="o" :label="o" :value="o"></el-option></el-select><el-select v-model="s.row.processing" size="small" placeholder="炮制" :disabled="!canEdit" style="width:82px"><el-option v-for="o in processes" :key="o" :label="o" :value="o"></el-option></el-select><span v-if="Number(s.row.multipleBase)>0" class="dw-tag dw-tag--info" :title="'单味剂量须为 '+s.row.multipleBase+' 的整数倍'">×{{ s.row.multipleBase }}</span></div><span v-else class="dim">—</span></template></el-table-column>
                <el-table-column label="发药量" width="80"><template #default="s"><el-input-number v-model="s.row.quantity" :min="1" size="small" controls-position="right" :disabled="!canEdit" style="width:72px" @keyup.enter="backToPick"></el-input-number></template></el-table-column>
                <el-table-column label="金额" width="66" align="right"><template #default="s"><span class="amt">¥{{ lineAmount(s.row) }}</span></template></el-table-column>
                <el-table-column label="" width="64" align="center"><template #default="s"><el-button link type="primary" size="small" :disabled="!canEdit" title="拆出为独立组" @click="splitItem(s.row)">拆</el-button><el-button link type="danger" size="small" :disabled="!canEdit" @click="removeItem(s.row)">删</el-button></template></el-table-column>
              </el-table>
            </div>
          </div>
          <div v-if="!rxItems.length" class="dw-collapse-empty">检索并选中药品即加入处方；同一给药可用“＋新组”分开，组内用法/频次/天数共用</div>

          <div class="dw-foot-bar">
            <span>{{ rxItems.length }}种 · {{ groupedItems.length }}组 · 合计 <b>¥{{ rxTotal }}</b><span v-if="hasMixedHerb" class="dw-tag dw-tag--warning" style="margin-left:8px">含中药饮片将自动拆方</span></span>
            <div style="display:flex;gap:6px">
              <el-button size="small" :disabled="!canEdit || !rxItems.length" title="将处方组摘要追加到病历治疗意见" @click="insertToRecord">插入病历</el-button>
              <el-button type="primary" size="small" :loading="saving" :disabled="!canEdit || !rxItems.length" @click="savePrescription">审核并开立处方</el-button>
            </div>
          </div>

          <div class="dw-rx-done">
            <div class="dw-done-summary" :title="doneExpanded ? '收起已开立处方' : '展开查看/打印/作废'" @click="doneExpanded=!doneExpanded">
              <span>{{ doneExpanded ? '▾' : '▸' }}</span>
              <span>已开立处方 <b>{{ prescriptions.length }}</b> 张<span v-if="prescriptions.length"> · 合计 ¥{{ rxDoneTotal }}</span></span>
              <span class="dim" style="margin-left:auto">{{ doneExpanded ? '收起' : '展开查看 / 打印 / 作废' }}</span>
            </div>
            <prescription-list v-show="doneExpanded" :rows="prescriptions" :expanded="true" @cancel="cancelPrescription" @print="printPrescription"></prescription-list>
            <div class="dw-rx-allergy" :class="allergyHistory ? 'has' : ''"><b>过敏史</b>　{{ allergyHistory || '未记录（开方前请主动核实）' }}</div>
          </div>
        </div>

        <!-- 处方组套管理就地嵌入弹窗: 关闭即回到打开它的处方面板(不整页跳转, 患者上下文与在编处方不丢)。
             内容为医疗模板管理页(自动定位处方组套页签), 其内部编辑弹窗由 Element Plus teleport 挂 body 不受影响 -->
        <el-dialog v-model="rxSetManageVisible" title="处方组套管理（关闭后回到医生站处方界面）" width="76%" top="5vh"
          append-to-body destroy-on-close class="dw-rxset-manage-dialog" @closed="loadTemplates">
          <component v-if="rxSetManageVisible && medicalTemplateComp" :is="medicalTemplateComp"></component>
        </el-dialog>
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
        folded: window.localStorage.getItem('dw.rx.folded') === '1',
        maximized: false,
        /* 组套管理嵌入弹窗: 可见态 + 懒引用的医疗模板管理组件(本面板先于 medical-template.js 加载) */
        rxSetManageVisible: false,
        medicalTemplateComp: null,
        doneExpanded: false,
        assistant: { personalFrequent: [], deptFrequent: [] },
        chronicList: [],
        splitPlan: null,
        decoctions: DECOCTIONS,
        processes: PROCESSES,
        therapies: THERAPIES,
        /* P6 中药饮片处方专属处方头(治法/剂数/每日剂次/煎服法/全局脚注/忌口) */
        tcmTherapy: '',
        tcmDoseCount: 1,
        tcmDailyTimes: '日一剂',
        tcmDecoctMethod: '水煎',
        tcmGlobalFootnote: '',
        tcmTaboo: '',
        tcmDailyOptions: TCM_DAILY_OPTIONS,
        tcmDecoctMethods: TCM_DECOCT_METHODS,
        pharmacies: [],
        selectedPharmacyId: null,
        pharmacyAutoResolved: false,
        /* P2 智能选药: 候选来源模式 + 检索过滤 chip */
        searchMode: 'keyword',
        /* 候选下拉客户端分页(跨页连续序号) */
        pickPage: 1,
        pickPageSize: 12,
        fStock: false,
        fYb: false,
        fInpharmacy: false
      };
    },
    computed: {
      visit: function () { return unwrap(this.currentVisit) || null; },
      patient: function () { return unwrap(this.currentPatient) || this.visit || {}; },
      visitId: function () { return this.visit && (this.visit.id || this.visit.visitId); },
      canEdit: function () { return !!this.visitId && (!this.visit || Number(this.visit.visitStatus || 2) < 3); },
      isExpanded: function () { return true; },
      /* U1: 处方表高度自适应视口(有界 max-height, 保留固定表头内滚口径) */
      rxTableMaxHeight: function () { return 'calc(100vh - 430px)'; },
      /* U1 降噪: 非普通处方在 header 出示短徽标(配合面板左侧色条) */
      rxTypeFlag: function () {
        var map = {
          EMERGENCY: { key: 'emergency', label: '急' },
          PEDIATRIC: { key: 'pediatric', label: '儿' },
          NARCOTIC: { key: 'narcotic', label: '麻' },
          PSYCHO1: { key: 'narcotic', label: '精一' }
        };
        return map[this.rxType] || null;
      },
      rxDoneTotal: function () {
        return money((this.prescriptions || []).reduce(function (sum, rx) { return sum + Number(rx.totalAmount || 0); }, 0));
      },
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
      },
      assistantChips: function () {
        var a = this.assistant || {};
        var seen = {};
        var out = [];
        (a.personalFrequent || []).concat(a.deptFrequent || []).forEach(function (f) {
          var uk = f.kind + '|' + f.code;
          if (!f.code || seen[uk]) { return; }
          seen[uk] = 1;
          out.push(f);
        });
        return out.slice(0, 12);
      },
      /* P2 过滤 chip: 客户端筛选当前候选 */
      displayResults: function () {
        var vm = this;
        return (this.searchResults || []).filter(function (d) {
          /* P6 中药饮片处方: 药味检索限 majorClass=中药饮片(后端无 majorClass 参数, 前端按大类识别过滤) */
          if (vm.rxType === 'TCM_HERB' && vm.detectRxType(d) !== 'TCM_HERB') { return false; }
          if (vm.fYb && !(d.ybDrugCode || d.medListCodg)) { return false; }
          if (vm.fStock && !(Number(vm.availText(d)) > 0)) { return false; }
          if (vm.fInpharmacy && d.effPrice == null) { return false; }
          return true;
        });
      },
      /* 候选下拉客户端分页: 总页数 / 有效页(过滤致页数变小时自动钳制) / 当前页切片 */
      pickTotalPages: function () {
        var size = this.pickPageSize || 12;
        return Math.max(1, Math.ceil((this.displayResults || []).length / size));
      },
      effPage: function () {
        return Math.min(Math.max(1, this.pickPage), this.pickTotalPages);
      },
      pagedResults: function () {
        var list = this.displayResults || [];
        var size = this.pickPageSize || 12;
        var start = (this.effPage - 1) * size;
        return list.slice(start, start + size);
      },
      /* P2 候选分区呈现: 关键词模式单组“匹配结果”, 精选模式按药品大类分组(均基于当前页) */
      renderGroups: function () {
        var items = this.pagedResults || [];
        if (!items.length) { return []; }
        var total = (this.displayResults || []).length;
        if (this.searchMode !== 'curated') {
          return [{ label: '匹配结果（共 ' + total + '）· 第 ' + this.effPage + '/' + this.pickTotalPages + ' 页', items: items }];
        }
        var map = {};
        var order = [];
        items.forEach(function (d) {
          var k = d.majorClass || '其他';
          if (!map[k]) { map[k] = { label: k, items: [] }; order.push(k); }
          map[k].items.push(d);
        });
        return order.map(function (k) { return map[k]; });
      }
    },
    watch: {
      /* 候选结果集变化(新检索/切药房/精选)即回到第 1 页 */
      searchResults: function () { this.pickPage = 1; },
      visitId: {
        immediate: true,
        handler: function (id) {
          if (id === this.loadedVisitId) { return; }
          this.loadedVisitId = id;
          this.rxItems = [];
          this.currentGroupNo = 1;
          this.searchResults = [];
          this.searchMode = 'keyword';
          this.resetTcmHead();
          this.setDefaultRxType();
          this.initPharmacyScope();
          if (id) { this.loadPrescriptions(); this.loadAssistant(); this.loadChronic(); }
          else { this.prescriptions = []; this.assistant = { personalFrequent: [], deptFrequent: [] }; this.chronicList = []; }
          this.splitPlan = null;
        }
      }
    },
    created: function () { this.loadTemplates(); },
    mounted: function () { window.addEventListener('keydown', this.onPanelKeydown); },
    beforeUnmount: function () { window.removeEventListener('keydown', this.onPanelKeydown); },
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
        // 已加明细按新药房生效价/可用量重算(服务端开方时仍会重算兜底, 此处仅预览对齐)
        window.HIS.get('/api/org-catalog/available/drug?page=1&size=200&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId || ''))
          .then(function (data) {
            var map = {};
            ((data && data.records) || []).forEach(function (row) { map[row.id] = row; });
            var changed = 0;
            vm.rxItems.forEach(function (item) {
              var row = item.drugId ? map[item.drugId] : null;
              if (!row) { return; }
              var np = row.effPrice != null ? row.effPrice : row.retailPrice;
              if (np != null && Number(np) !== Number(item.price)) { item.price = Number(np); changed++; }
              item.stockQty = row.stockQty;
              item.stockAvail = row.stockAvail;
              item.reservedQty = row.reservedQty;
              vm.refreshStockWarning(item);
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
        if (this.rxType === 'TCM_HERB') { this.recalcTcmAllQty(); }
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
          decoction: drug.decoction || '',
          processing: drug.processing || '',
          therapy: drug.therapy || '',
          herbForm: drug.herbForm || (type === 'TCM_HERB' ? '饮片' : ''),
          multipleBase: drug.multipleBase != null ? Number(drug.multipleBase) : 0,
          abxGrade: drug.abxGrade || '',
          manufacturer: drug.manufacturer || '',
          majorClass: drug.majorClass || '',
          chrgitmLv: drug.chrgitmLv || '',
          chrgitmLvName: drug.chrgitmLvName || '',
          selfpayProp: drug.selfpayProp,
          ybName: drug.ybName || '',
          ybDrugCode: drug.ybDrugCode || '',
          dosformName: drug.dosformName || '',
          tradeName: drug.tradeName || '',
          stockQty: drug.stockQty,
          stockAvail: drug.stockAvail,
          reservedQty: drug.reservedQty,
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
        if (!kw) { vm.loadCuratedCandidates(); return; }
        vm.searchMode = 'keyword';
        vm.searching = true;
        var url = '/api/org-catalog/available/drug?page=1&size=50&keyword=' + encodeURIComponent(kw);
        if (vm.selectedPharmacyId) { url += '&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId); }
        window.HIS.get(url).then(function (data) {
          vm.searchResults = (data && data.records) || [];
        }).catch(window.HIS.notifyError).finally(function () { vm.searching = false; });
      },
      /* P2 空输入聚焦即出精选候选: 按当前诊断推荐(常用助手已在 chips 区呈现) */
      loadCuratedCandidates: function () {
        var vm = this;
        var visit = vm.visit || {};
        var diagList = unwrap(vm.diagnoses) || [];
        var diagCodes = diagList.map(function (d) { return d.diagCode || d.code; }).filter(Boolean).join(',');
        vm.searchMode = 'curated';
        if (!diagCodes) { vm.searchResults = []; return; }
        vm.searching = true;
        var url = '/api/his/prescription/drug-suggest?limit=24&deptId=' + encodeURIComponent(visit.deptId || '') + '&diagCodes=' + encodeURIComponent(diagCodes);
        if (vm.selectedPharmacyId) { url += '&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId); }
        window.HIS.get(url).then(function (rows) {
          vm.searchResults = rows || [];
        }).catch(function () { vm.searchResults = []; }).finally(function () { vm.searching = false; });
      },
      onPickVisible: function (visible) {
        if (visible && !(this.searchResults || []).length) { this.loadCuratedCandidates(); }
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
        if (this.rxItems.length && this.rxItems.some(function (item) { return this.isHerbItem(item) !== this.isHerbItem(drug); }, this)) {
          warnings.push({ level: 'info', msg: '中药饮片与西药/中成药将自动拆分为两张独立处方' });
        }
        /* P6 中药饮片配伍审查: 十八反/十九畏经典禁忌 → "用药不适宜"级告警 */
        if (this.isHerbItem(drug)) {
          this.checkTcmIncompatibility(drug).forEach(function (w) { warnings.push(w); });
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
          return vm.checkPrescribeAuth(item).then(function (authOk) {
            if (!authOk) { return false; }
          vm.rxItems.push(item);
          vm.resequenceGroups();
          vm.currentGroupNo = Number(item.groupNo) || 1;
          if (item._detectedType !== 'NORMAL' && !vm.hasMixedHerb) {
            vm.rxType = item._rxType;
            vm.rxItems.forEach(function (row) { if (row._detectedType === 'NORMAL') { row._rxType = vm.rxType; } });
          }
          vm.calcQty(item, true);
          /* U2 键盘流: 入行后自动聚焦新行剂量框; 延时避开 el-select 选中后同步回焦检索框的抢焦点 */
          vm.$nextTick(function () { window.setTimeout(vm.focusNewRowDosage, 220); });
          return true;
          });
        });
      },
      nextGroupNo: function () {
        return this.rxItems.reduce(function (max, item) { return Math.max(max, Number(item.groupNo) || 0); }, 0) + 1;
      },
      /* ===== U1/U2: 折叠记忆与定位 / 键盘流焦点 ===== */
      toggleFold: function () {
        this.folded = !this.folded;
        if (this.folded) { this.maximized = false; }
        try { window.localStorage.setItem('dw.rx.folded', this.folded ? '1' : '0'); } catch (e) { /* 隐私模式忽略 */ }
      },
      toggleMaximize: function () {
        if (this.folded) {
          this.folded = false;
          try { window.localStorage.setItem('dw.rx.folded', '0'); } catch (e) { /* 隐私模式忽略 */ }
        }
        this.maximized = !this.maximized;
      },
      onPanelKeydown: function (ev) {
        if (ev && ev.key === 'Escape' && this.maximized) { this.maximized = false; }
      },
      /* F4 预检定位时由父页调用: 确保面板展开 */
      revealForLocate: function () { this.folded = false; },
      /* 新行剂量框聚焦: 表格首行无输入列, 取末行首个可编辑 input */
      focusNewRowDosage: function () {
        var root = this.$el;
        if (!root) { return; }
        var rows = root.querySelectorAll('.dw-rx-table .el-table__body tr.el-table__row');
        var row = rows[rows.length - 1];
        if (!row) { return; }
        var input = row.querySelector('td .el-input__inner');
        if (input && !input.disabled) { input.focus(); if (input.select) { input.select(); } }
      },
      /* 剂量/发药量框回车: 回到顶部药品检索框, 形成 检索→剂量→回车检索 闭环 */
      backToPick: function () {
        var pick = this.$refs.rxPick;
        if (pick && typeof pick.focus === 'function') { pick.focus(); }
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
        /* P6 中药饮片: 发药量 = 单味剂量(g) × 剂数(付), 不走频次×天数 */
        if (this.isHerbItem(item)) {
          var herbDose = Number(item.dosage);
          var _fu = Number(this.tcmDoseCount) || 1;
          if (herbDose > 0) {
            item.dosageUnit = item.dosageUnit || 'g';
            item.quantity = Math.max(1, Math.round(herbDose * _fu));
          }
          this.refreshStockWarning(item);
          return;
        }
        var dosage = Number(item.dosage);
        var unitDose = Number(item.unitDose);
        var days = Number(item.days);
        if (!(dosage > 0 && unitDose > 0 && days > 0)) {
          if (!silent) { ElementPlus.ElMessage.warning('请填写单次剂量，且药品需配置单位含药量'); }
          this.refreshStockWarning(item);
          return;
        }
        var perDose = this.roundByRule(dosage / unitDose, Number(item.roundRule) || 1);
        item.quantity = Math.max(1, Math.ceil(perDose * this.frequencyTimes(item.frequency) * days));
        /* P3 软预占: 发药量变更后即时重评可用量告警 */
        this.refreshStockWarning(item);
      },
      /* P3 软预占告警: 以虚拟可用量(stockAvail=在库-他单已开未发占用)为准, 非致命行内提示 */
      refreshStockWarning: function (item) {
        if (!item) { return; }
        var need = Number(item.quantity || 1);
        var avail = item.stockAvail != null ? Number(item.stockAvail) : null;
        item._warnings = (item._warnings || []).filter(function (w) { return w.kind !== 'stock'; });
        if (avail != null && avail >= 0 && avail < need) {
          item._warnings.push({
            level: 'warning', kind: 'stock',
            msg: '该药房在库' + (item.stockQty != null ? item.stockQty : '—') + '、已被他单占用' + (item.reservedQty != null ? item.reservedQty : 0) + '、本次可用' + avail + '（需' + need + '），发药环节可能需改派药房'
          });
        }
        var level = '';
        item._warnings.forEach(function (w) {
          if (w.level === 'serious') { level = 'serious'; }
          else if (w.level === 'warning' && level !== 'serious') { level = 'warning'; }
        });
        item._warningLevel = level;
      },
      groupUsage: function (group) { return (group.items[0] && group.items[0].usageMethod) || '未选用法'; },
      groupFrequency: function (group) { return (group.items[0] && group.items[0].frequency) || '未选频次'; },
      groupDays: function (group) { return group.items[0] && group.items[0].days ? group.items[0].days + '天' : ''; },
      /* P4 组带上移的组级编辑: 改一个即同步本组全部成员(组内用法/频次/天数共用) */
      onGroupUsageChange: function (group, val) {
        (group.items || []).forEach(function (it) { it.usageMethod = val; });
      },
      onGroupFreqChange: function (group, val) {
        var vm = this;
        (group.items || []).forEach(function (it) { it.frequency = val; vm.calcQty(it, true); });
      },
      onGroupDaysChange: function (group, val) {
        var vm = this;
        (group.items || []).forEach(function (it) { it.days = Number(val) || 1; vm.calcQty(it, true); });
      },
      groupTotal: function (group) {
        return money((group.items || []).reduce(function (s, it) { return s + Number(it.price || 0) * Number(it.quantity || 0); }, 0));
      },
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
      /* ===== P6 中药饮片处方专属能力 ===== */
      isHerbGroup: function (group) {
        var vm = this;
        return !!(group && group.items && group.items.length && group.items.every(function (it) { return vm.isHerbItem(it); }));
      },
      resetTcmHead: function () {
        this.tcmTherapy = '';
        this.tcmDoseCount = 1;
        this.tcmDailyTimes = '日一剂';
        this.tcmDecoctMethod = '水煎';
        this.tcmGlobalFootnote = '';
        this.tcmTaboo = '';
      },
      applyTcmHead: function (t) {
        if (!t) { return; }
        if (t.therapy != null) { this.tcmTherapy = t.therapy; }
        if (Number(t.doseCount) > 0) { this.tcmDoseCount = Number(t.doseCount); }
        if (t.dailyTimes) { this.tcmDailyTimes = t.dailyTimes; }
        if (t.decoctMethod) { this.tcmDecoctMethod = t.decoctMethod; }
        if (t.globalFootnote != null) { this.tcmGlobalFootnote = t.globalFootnote; }
        if (t.taboo != null) { this.tcmTaboo = t.taboo; }
        this.recalcTcmAllQty();
      },
      collectTcmHead: function () {
        return {
          therapy: this.tcmTherapy, doseCount: this.tcmDoseCount, dailyTimes: this.tcmDailyTimes,
          decoctMethod: this.tcmDecoctMethod, globalFootnote: this.tcmGlobalFootnote, taboo: this.tcmTaboo
        };
      },
      /* 剂数/切换处方头时重算全方所有药味发药量 */
      recalcTcmAllQty: function () {
        var vm = this;
        vm.rxItems.forEach(function (item) { if (vm.isHerbItem(item)) { vm.calcQty(item, true); } });
      },
      /* 十八反/十九畏配伍禁忌扫描(待加药味 vs 已在方药味) */
      checkTcmIncompatibility: function (drug) {
        var vm = this;
        var out = [];
        var drugName = text(drug.itemName);
        var existing = vm.rxItems.filter(function (it) { return vm.isHerbItem(it); }).map(function (it) { return text(it.itemName); });
        if (!drugName || !existing.length) { return out; }
        function scan(pairs, listName, verb) {
          pairs.forEach(function (pair) {
            var a = pair[0];
            var b = pair[1];
            var hit = (herbHit(drugName, a) && existing.some(function (n) { return herbHit(n, b); }))
              || (herbHit(drugName, b) && existing.some(function (n) { return herbHit(n, a); }));
            if (hit) {
              out.push({ level: 'warning', kind: 'tcm-contra', msg: listName + '配伍禁忌（' + verb + '）：“' + drug.itemName + '”与处方内现有药味属经典' + listName + '禁忌，属用药不适宜，请复核或调整配伍' });
            }
          });
        }
        scan(TCM_EIGHTEEN_INCOMPATIBLE, '十八反', '反');
        scan(TCM_NINETEEN_FEAR, '十九畏', '畏');
        return out;
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
      /* ===== P1 医保目录富展示: 甲乙丙/自费徽标 + 自付比例 + 可用量 + 副行/编码带 ===== */
      lvCode: function (d) {
        if (!d) { return 'none'; }
        if (!d.ybDrugCode && !d.medListCodg) { return 'self'; }
        var lv = text(d.chrgitmLv);
        var nm = text(d.chrgitmLvName) || lv;
        if (lv === '1' || /^甲/.test(nm)) { return 'a'; }
        if (lv === '2' || /^乙/.test(nm)) { return 'b'; }
        if (lv === '3' || lv === '4' || /^丙/.test(nm)) { return 'c'; }
        return 'none';
      },
      lvText: function (d) {
        var code = this.lvCode(d);
        if (code === 'self') { return '自费'; }
        if (code === 'none') { return text(d && d.chrgitmLvName) || '未分类'; }
        return code === 'a' ? '甲' : (code === 'b' ? '乙' : '丙');
      },
      lvClass: function (d) { return 'dw-lv--' + this.lvCode(d); },
      selfpayText: function (d) {
        if (!d || d.selfpayProp == null || d.selfpayProp === '') { return ''; }
        var v = Number(d.selfpayProp);
        if (isNaN(v) || v <= 0) { return ''; }
        var pct = v <= 1 ? v * 100 : v;
        return (Math.round(pct * 10) / 10) + '%';
      },
      availText: function (d) {
        if (!d) { return '-'; }
        var v = d.stockAvail != null ? d.stockAvail : (d.stockQty != null ? d.stockQty : (d.qty != null ? d.qty : d.availableQty));
        return v == null ? '-' : v;
      },
      subLine: function (d) {
        return [text(d.spec), text(d.dosformName), text(d.manufacturer)].filter(function (x) { return x; }).join(' / ');
      },
      itemTitle: function (row) {
        var t = text(row.itemName) + ' ' + text(row.spec);
        if (row.itemCode) { t += '｜院内码 ' + row.itemCode; }
        if (row.medListCodg) { t += '｜医保码 ' + row.medListCodg + (row.ybName ? '（' + row.ybName + '）' : ''); }
        else { t += '｜未对照医保（自费）'; }
        return t;
      },
      /* 检索候选单行化: 规格/剂型/厂家 + 院内码/医保码/医保名 收进 hover 提示, 保持行内简洁 */
      pickTitle: function (d) {
        var parts = [this.subLine(d)];
        parts.push('院内码 ' + (text(d.drugCode) || '—'));
        parts.push('医保码 ' + (text(d.ybDrugCode || d.medListCodg) || '未对照'));
        if (text(d.ybName)) { parts.push(d.ybName); }
        return parts.filter(function (x) { return x; }).join('　|　');
      },
      /* 候选行全局序号(跨页连续): 按过滤后完整列表 displayResults 定位 */
      seqOf: function (d) {
        var idx = (this.displayResults || []).indexOf(d);
        return idx >= 0 ? idx + 1 : '';
      },
      prevPage: function () { this.pickPage = Math.max(1, this.effPage - 1); },
      nextPage: function () { this.pickPage = Math.min(this.pickTotalPages, this.effPage + 1); },
      validateItems: function () {
        if (!this.rxItems.length) { ElementPlus.ElMessage.warning('请添加处方明细'); return false; }
        for (var i = 0; i < this.rxItems.length; i++) {
          var item = this.rxItems[i];
          /* P6 中药饮片: 仅校验单味剂量与剂数(发药量由 剂量×剂数 导出), 不要求西药用法/频次/天数 */
          if (this.isHerbItem(item)) {
            if (!(Number(item.dosage) > 0) || !(Number(item.quantity) > 0)) { ElementPlus.ElMessage.warning('请填写中药"' + item.itemName + '"的剂量(克)'); return false; }
            if (!(Number(this.tcmDoseCount) > 0)) { ElementPlus.ElMessage.warning('请填写中药方剂数(付)'); return false; }
            continue;
          }
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
          packRatio: item.packRatio, roundRule: item.roundRule,
          decoction: item.decoction, processing: item.processing, therapy: item.therapy,
          herbForm: item.herbForm, multipleBase: item.multipleBase
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
        if (vm.splitPlan && vm.splitPlan.length > 1) {
          return vm.splitPlan.map(function (grp) {
            var items = (grp.indexes || []).map(function (i) { return vm.rxItems[i]; }).filter(Boolean);
            if (!items.length) { return null; }
            var herbAll = items.every(function (it) { return vm.isHerbItem(it); });
            var label = herbAll ? RX_TYPES.TCM_HERB.label : (RX_TYPES[(items[0]._rxType) || vm.rxType] || RX_TYPES.NORMAL).label;
            return { rxType: label, splitName: grp.ruleName || '', items: items.map(vm.apiItem) };
          }).filter(Boolean);
        }
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
        /* U2 开立前审查聚合: 行内残留告警一次性确认, 致命过敏直接阻断 */
        vm.preSaveSafetyReview().then(function (allowed) {
          if (allowed) { vm.doSavePrescription(); }
        });
      },
      preSaveSafetyReview: function () {
        var vm = this;
        var allergies = vm.allergyHistory.toLowerCase();
        var fatalNames = vm.rxItems.filter(function (item) {
          var nm = text(item.itemName);
          return allergies && nm && allergies.indexOf(nm.substring(0, 2).toLowerCase()) >= 0;
        }).map(function (item) { return item.itemName; });
        if (fatalNames.length) {
          ElementPlus.ElMessage.error('与过敏史禁忌：' + fatalNames.join('、') + '，已阻止开立');
          return Promise.resolve(false);
        }
        var flagged = vm.rxItems.filter(function (item) { return item._warningLevel === 'serious' || item._warningLevel === 'warning'; });
        if (!flagged.length) { return Promise.resolve(true); }
        var lines = flagged.map(function (item) {
          var msgs = (item._warnings || []).filter(function (w) { return w.level === 'serious' || w.level === 'warning'; }).map(function (w) { return w.msg; });
          return '· ' + item.itemName + '：' + (msgs.join('；') || '存在用药警告');
        });
        return ElementPlus.ElMessageBox.confirm(
          lines.join('\n') + '\n\n处方已复核，确认开立？',
          '开立前用药审查（' + flagged.length + ' 项提示）',
          { type: 'warning', confirmButtonText: '已复核，继续开立', cancelButtonText: '返回调整' }
        ).then(function () { return true; }).catch(function () { return false; });
      },
      doSavePrescription: function () {
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
          vm.splitPlan = null;
          vm.resetTcmHead();
          vm.loadPrescriptions();
          vm.loadAssistant();
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
      /* ===== OP-C(C2) 医嘱处方专业化前端接线 ===== */
      loadAssistant: function () {
        var vm = this;
        var visit = vm.visit || {};
        var staffId = visit.staffId || visit.drId || '';
        var deptId = visit.deptId || '';
        window.HIS.get('/api/his/order/assistant?staffId=' + encodeURIComponent(staffId) + '&deptId=' + encodeURIComponent(deptId) + '&limit=12')
          .then(function (data) { vm.assistant = data || { personalFrequent: [], deptFrequent: [] }; })
          .catch(function () { vm.assistant = { personalFrequent: [], deptFrequent: [] }; });
      },
      useFrequent: function (f) {
        var vm = this;
        if (!vm.canEdit) { return; }
        window.HIS.get('/api/org-catalog/available/drug?page=1&size=10&keyword=' + encodeURIComponent(f.name || '') + (vm.selectedPharmacyId ? '&pharmacyId=' + encodeURIComponent(vm.selectedPharmacyId) : ''))
          .then(function (data) {
            var recs = (data && data.records) || [];
            var hit = recs.find(function (d) { return String(d.itemCode || d.drugCode || '') === String(f.code); }) || recs[0];
            if (hit) { vm.addDrug(hit); } else { ElementPlus.ElMessage.warning('未检索到该常用项目, 请手动检索'); }
          }).catch(window.HIS.notifyError);
      },
      loadChronic: function () {
        var vm = this;
        var pid = vm.patient && (vm.patient.id || vm.patient.patientId);
        if (!pid) { vm.chronicList = []; return; }
        window.HIS.get('/api/his/chronic-disease/list?patientId=' + encodeURIComponent(pid))
          .then(function (rows) { vm.chronicList = rows || []; }).catch(function () { vm.chronicList = []; });
      },
      useChronic: function (c) {
        this.$emit('insert-to-record', { target: 'treatment', text: '门慢/门特备案：' + text(c.diseName) + (c.diseCode ? '（' + c.diseCode + '）' : '') + (c.validUntil ? '，有效期至' + c.validUntil : '') });
      },
      checkPrescribeAuth: function (item) {
        var vm = this;
        var type = '';
        if (item._rxType === 'NARCOTIC') { type = 'narcotic'; } else if (item._rxType === 'PSYCHO1') { type = 'psych1'; } else if (item._rxType === 'PSYCHO2') { type = 'psych2'; } else if (item.abxGrade) { type = 'abx'; }
        if (!type) { return Promise.resolve(true); }
        var labels = { narcotic: '麻醉药品', psych1: '第一类精神药品', psych2: '第二类精神药品', abx: '抗菌药物' };
        var visit = vm.visit || {};
        var staffId = visit.staffId || visit.drId || '';
        return window.HIS.get('/api/his/staff/prescribe-auth?staffId=' + encodeURIComponent(staffId) + '&type=' + type)
          .then(function (data) {
            if (data && data.found && data.typeAllowed === false) {
              ElementPlus.ElMessage.error('医师[' + (data.staffName || staffId) + ']无' + labels[type] + '处方权限' + (data.expired ? '(处方权已过期)' : '') + ', 不能开立"' + item.itemName + '"');
              return false;
            }
            return true;
          }).catch(function () { return true; });
      },
      applySplitSuggest: function () {
        var vm = this;
        if (!vm.rxItems.length) { ElementPlus.ElMessage.warning('请先添加药品'); return; }
        var visit = vm.visit || {};
        var items = vm.rxItems.map(function (it) {
          return { usage: it.usageMethod || '', insutype: text(visit.insutype), chronicDise: '', specialDrug: it.specialDrug || '', pharmacy: text(vm.selectedPharmacyId) };
        });
        window.HIS.post('/api/his/rx-split-rule/suggest', { deptId: visit.deptId || null, items: items }).then(function (groups) {
          groups = groups || [];
          if (groups.length < 2) { ElementPlus.ElMessage.info('未配置匹配的拆方规则, 将沿用中西药自动拆方'); vm.splitPlan = null; return; }
          var desc = groups.map(function (g, i) { return '第' + (i + 1) + '张[' + (g.ruleName || '默认组') + ']: ' + (g.indexes || []).map(function (idx) { return text(vm.rxItems[idx] && vm.rxItems[idx].itemName); }).join('、'); }).join('\n');
          ElementPlus.ElMessageBox.confirm(desc + '\n\n确认按规则拆分为 ' + groups.length + ' 张处方？', '拆方建议', { type: 'info', confirmButtonText: '应用拆分', cancelButtonText: '保持自动' })
            .then(function () { vm.splitPlan = groups; ElementPlus.ElMessage.success('已按规则拆分, 开立时将原子提交 ' + groups.length + ' 张处方'); })
            .catch(function () {});
        }).catch(window.HIS.notifyError);
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
      openRxSetManage: function () {
        try { window.sessionStorage.setItem('yb.medical-template.activeTab', 'rx_set'); } catch (e) { /* 隐私模式仍可弹窗, 但使用默认页签 */ }
        // 懒引用: medical-template.js 在本面板之后加载, 打开时才从 HIS.views 取组件定义
        var comp = window.HIS && HIS.views ? HIS.views.MedicalTemplateManage : null;
        if (!comp) { ElementPlus.ElMessage.warning('医疗模板管理组件未加载'); return; }
        this.medicalTemplateComp = comp;
        this.rxSetManageVisible = true;
      },
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
          if (body.tcm) { vm.applyTcmHead(body.tcm); }
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
      /* OP-B 诊断→模板调入: 按传入模板 content(items) 追加到当前处方草稿(不替换), 供主编排中继调用 */
      applyDiagTemplate: function (tpl) {
        var vm = this;
        if (!tpl) { return; }
        var parsed;
        try { parsed = typeof tpl.content === 'string' ? JSON.parse(tpl.content) : tpl.content; } catch (e) { ElementPlus.ElMessage.error('模板内容格式错误'); return; }
        var items = Array.isArray(parsed) ? parsed : ((parsed && parsed.items) || []);
        if (!items.length) { ElementPlus.ElMessage.warning('该模板没有药品明细'); return; }
        if (parsed && parsed.rxType && RX_TYPES[parsed.rxType]) { vm.rxType = parsed.rxType; }
        if (parsed && parsed.tcm) { vm.applyTcmHead(parsed.tcm); }
        return items.reduce(function (chain, source) {
          return chain.then(function () { return vm.addDrug(source, source.groupNo); });
        }, Promise.resolve()).then(function () {
          vm.resequenceGroups();
          ElementPlus.ElMessage.success('已调入处方模板：' + (tpl.templateName || tpl.name || ''));
        }).catch(function (e) { if (window.HIS.notifyError) { window.HIS.notifyError(e); } });
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
        var tcmNote = '';
        if (vm.rxType === 'TCM_HERB') {
          var head = [];
          if (text(vm.tcmTherapy)) { head.push('治法' + vm.tcmTherapy); }
          head.push(vm.tcmDoseCount + '付');
          if (text(vm.tcmDailyTimes)) { head.push(vm.tcmDailyTimes); }
          if (text(vm.tcmDecoctMethod)) { head.push(vm.tcmDecoctMethod); }
          if (text(vm.tcmGlobalFootnote)) { head.push('脚注:' + vm.tcmGlobalFootnote); }
          if (text(vm.tcmTaboo)) { head.push('忌口:' + vm.tcmTaboo); }
          tcmNote = '（中药饮片方 ' + head.join('，') + '）';
        }
        vm.$emit('insert-to-record', { target: 'treatment', text: '处方摘要：' + tcmNote + lines.join('；') });
      },
      saveAsTemplate: function () {
        var vm = this;
        if (!vm.rxItems.length) { ElementPlus.ElMessage.warning('当前没有可保存的处方草稿'); return; }
        ElementPlus.ElMessageBox.prompt('请输入处方组套名称', '存为处方组套', {
          inputPlaceholder: '例如：上呼吸道感染常用方',
          inputValidator: function (value) { return text(value).trim() ? true : '请输入模板名称'; }
        }).then(function (result) {
          var visit = vm.visit || {};
          return window.HIS.post('/api/his/template/create', {
            templateType: 'rx_set', name: text(result.value).trim(),
            deptId: visit.deptId || null, staffId: visit.staffId || visit.drId || null,
            content: JSON.stringify({ rxType: vm.rxType, tcm: vm.collectTcmHead(), items: vm.rxItems.map(function (item) { return vm.templateItem(item); }) }),
            sortOrder: 0, status: 1
          });
        }).then(function () {
          ElementPlus.ElMessage.success('已保存为处方组套');
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
