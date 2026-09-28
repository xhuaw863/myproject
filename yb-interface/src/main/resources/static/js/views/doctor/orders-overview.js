/* 门诊医生站医嘱总览: 处方+检查/检验/治疗单统一展示, 类别过滤、作废可见、打印/作废复用主编排通道。 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  var CATEGORIES = ['西药', '中成药', '中药饮片', '检查', '检验', '治疗'];

  function money(v) { var n = Number(v); return isFinite(n) ? n.toFixed(2) : '0.00'; }
  function raw(v) { return v && Object.prototype.hasOwnProperty.call(v, 'value') ? v.value : v; }
  function text(v) { return v == null ? '' : String(v); }

  /* 处方类别推断: 按处方类型与药品名关键词(中药饮片处方整单归饮片; 其余按"丸/散/颗粒/口服液"等中成药特征) */
  function rxCategory(rx, items) {
    if (text(rx.rxType).indexOf('中药') >= 0 || text(rx.rxType).indexOf('饮片') >= 0) { return '中药饮片'; }
    var herbCount = 0, patentCount = 0;
    (items || []).forEach(function (it) {
      var nm = text(it.itemName) + text(it.spec);
      if (/饮片|药材/.test(nm)) { herbCount++; return; }
      if (/丸$|散$|膏$|丹$|口服液|颗粒|胶囊剂?[（(]?中成药|片[（(]?中成药/.test(text(it.itemName))) { patentCount++; }
    });
    var all = (items || []).length;
    if (all && herbCount === all) { return '中药饮片'; }
    if (all && patentCount === all) { return '中成药'; }
    return '西药';
  }

  /* 处方状态: -1作废 / 发药进度 / 单据已开 */
  function rxStatusLabel(rx) {
    var st = Number(rx.status);
    if (st === -1) { return '已作废'; }
    var ds = Number(rx.dispenseStatus || 0);
    if (ds === 1) { return '已发药'; }
    if (ds === 2) { return '已退药'; }
    return Number(rx.chargeStatus) === 1 || Number(rx.paidFlag) === 1 ? '已收费' : '待收费';
  }

  /* 医嘱单状态: 口径同 order-panel.orderStatus(作废/执行/报告/收费) */
  function orderStatusLabel(o) {
    var ex = Number(o.execStatus || 0), paid = Number(o.paidFlag || 0);
    if (Number(o.status) < 0 || Number(o.status) === 3) { return '已作废'; }
    if (ex === 3) { return '已取消'; }
    if (o.reportStatus === 1 || o.reportTime) { return '已报告'; }
    if (ex === 2) { return '已执行'; }
    if (ex === 1) { return '执行中'; }
    if (paid === 1 || o.chargeStatus === 1 || o.chargeTime) { return '已收费'; }
    return '待收费';
  }

  var DwOrdersOverview = {
    name: 'DwOrdersOverview',
    inject: ['currentVisit'],
    emits: ['print-rx', 'print-order'],
    template: `
      <section class="dw-panel dw-overview-panel">
        <header class="dw-panel-header">
          <span>医嘱总览 <span class="dim">{{ rows.length }} 单 / {{ itemCount }} 项</span></span>
          <div style="display:flex;align-items:center;gap:8px;margin-left:auto">
            <el-button size="small" text :loading="loading" @click="refresh">刷新</el-button>
          </div>
        </header>
        <div v-if="!visitId" class="dw-slim-empty">未选择患者, 医嘱总览暂不可用</div>
        <div v-else>
          <div class="dw-ov-filter">
            <button class="dw-diag-tab" :class="{'is-active': filterCat===''}" @click="filterCat=''">全部</button>
            <button class="dw-diag-tab" v-for="c in categories" :key="c" :class="{'is-active': filterCat===c}" @click="filterCat=c">{{ c }}<span class="dim" v-if="catCount(c)"> ({{ catCount(c) }})</span></button>
            <el-checkbox v-model="showVoid" size="small" style="margin-left:auto">显示已作废</el-checkbox>
          </div>
          <el-table :data="visibleRows" border size="small" v-loading="loading" row-key="_uid" :max-height="560">
            <el-table-column label="时间" width="150"><template #default="s"><span class="dim">{{ s.row.time || '-' }}</span></template></el-table-column>
            <el-table-column label="类别" width="88"><template #default="s"><el-tag size="small" effect="plain" :type="catTagType(s.row.category)">{{ s.row.category }}</el-tag></template></el-table-column>
            <el-table-column label="内容摘要" min-width="300" show-overflow-tooltip><template #default="s"><b>{{ s.row.no }}</b> · {{ s.row.summary }}</template></el-table-column>
            <el-table-column label="用法/标本部位" width="160" show-overflow-tooltip><template #default="s">{{ s.row.extra || '-' }}</template></el-table-column>
            <el-table-column label="项数" width="60" align="center"><template #default="s">{{ s.row.itemCount }}</template></el-table-column>
            <el-table-column label="金额" width="86" align="right"><template #default="s"><span class="amt">¥{{ money(s.row.amount) }}</span></template></el-table-column>
            <el-table-column label="状态" width="86" align="center"><template #default="s"><el-tag size="small" :type="statusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="110" align="center"><template #default="s">
              <el-button link type="primary" size="small" @click="onPrint(s.row)">打印</el-button>
              <el-button link type="danger" size="small" :disabled="!s.row.canCancel" @click="onCancel(s.row)">作废</el-button>
            </template></el-table-column>
            <template #empty><div class="dw-collapse-empty">{{ loading ? '加载中…' : '本次就诊暂无处方与医嘱单据' }}</div></template>
          </el-table>
        </div>
      </section>
    `,
    data: function () {
      return {
        categories: CATEGORIES,
        filterCat: '',
        showVoid: false,
        loading: false,
        rows: [],
        uidSeq: 0
      };
    },
    computed: {
      visit: function () { return raw(this.currentVisit) || null; },
      visitId: function () { return this.visit && (this.visit.id || this.visit.visitId); },
      visibleRows: function () {
        var vm = this;
        return this.rows.filter(function (row) {
          if (vm.filterCat && row.category !== vm.filterCat) { return false; }
          if (!vm.showVoid && row.status === '已作废') { return false; }
          return true;
        });
      },
      itemCount: function () {
        return this.visibleRows.reduce(function (sum, row) { return sum + (Number(row.itemCount) || 0); }, 0);
      }
    },
    watch: {
      visitId: {
        immediate: true,
        handler: function (id) { this.rows = []; if (id) { this.refresh(); } }
      }
    },
    methods: {
      money: money,
      catCount: function (cat) {
        var vm = this;
        return this.rows.filter(function (row) {
          return row.category === cat && (vm.showVoid || row.status !== '已作废');
        }).length;
      },
      catTagType: function (cat) {
        return cat === '检查' || cat === '检验' ? 'warning' : (cat === '治疗' ? 'success' : '');
      },
      statusType: function (status) {
        if (status === '已作废' || status === '已取消' || status === '已退药') { return 'danger'; }
        if (status === '已发药' || status === '已报告' || status === '已执行') { return 'success'; }
        if (status === '已收费' || status === '执行中') { return 'warning'; }
        return 'info';
      },
      refresh: function () {
        var vm = this;
        if (!vm.visitId) { vm.rows = []; return Promise.resolve([]); }
        vm.loading = true;
        return Promise.all([
          HIS.get('/api/his/prescription/list?visitId=' + encodeURIComponent(vm.visitId)).catch(function () { return []; }),
          HIS.get('/api/his/order/list?visitId=' + encodeURIComponent(vm.visitId)).catch(function () { return []; })
        ]).then(function (values) {
          var rxs = Array.isArray(values[0]) ? values[0] : ((values[0] && values[0].records) || []);
          var orders = Array.isArray(values[1]) ? values[1] : ((values[1] && values[1].records) || []);
          /* list 接口不带明细, 逐单补拉 items 汇总摘要(就诊级单据量小, 并行拉取) */
          function wrap(target, key, promise) {
            return promise.then(function (d) {
              var out = {};
              out[key] = target;
              out.items = Array.isArray(d) ? d : [];
              return out;
            }).catch(function () {
              var out = {};
              out[key] = target;
              out.items = [];
              return out;
            });
          }
          var tasks = [];
          rxs.forEach(function (rx) {
            tasks.push(wrap(rx, 'rx', HIS.get('/api/his/prescription/items?prescriptionId=' + encodeURIComponent(rx.id))));
          });
          orders.forEach(function (order) {
            tasks.push(wrap(order, 'order', HIS.get('/api/his/order/items?orderId=' + encodeURIComponent(order.id))));
          });
          return Promise.all(tasks);
        }).then(function (detailed) {
          var rows = [];
          detailed.forEach(function (entry) {
            if (entry.rx) { rows.push(vm.buildRxRow(entry.rx, entry.items)); }
            else { rows.push(vm.buildOrderRow(entry.order, entry.items)); }
          });
          rows.sort(function (a, b) { return String(a.timeSort).localeCompare(String(b.timeSort)); });
          vm.rows = rows;
          return rows;
        }).catch(function (e) {
          vm.rows = []; if (HIS.notifyError) { HIS.notifyError(e); } return [];
        }).finally(function () { vm.loading = false; });
      },
      nextUid: function () { return 'ov-' + (++this.uidSeq); },
      buildRxRow: function (rx, items) {
        var names = items.map(function (it) { return text(it.itemName); }).filter(Boolean);
        var usage = items.map(function (it) {
          return [it.usageMethod, it.frequency].filter(Boolean).join(' ');
        }).filter(Boolean);
        var one = items[0] || {};
        return {
          _uid: this.nextUid(), kind: 'rx', id: rx.id, timeSort: rx.createTime || rx.rxTime || '',
          time: text(rx.createTime || rx.rxTime).slice(0, 16), no: rx.rxNo || ('#' + rx.id),
          category: rxCategory(rx, items),
          summary: names.length ? names.slice(0, 4).join('、') + (names.length > 4 ? ' 等' + names.length + '种' : '') : text(rx.diagName) || '无明细',
          extra: usage.length ? usage.slice(0, 2).join(' / ') : text(one.administration || ''),
          itemCount: items.length, amount: rx.totalAmount,
          status: rxStatusLabel(rx), canCancel: Number(rx.status) === 1
        };
      },
      buildOrderRow: function (order, items) {
        var names = items.map(function (it) { return text(it.itemName); }).filter(Boolean);
        var one = items[0] || {};
        var extra = '';
        if (order.orderType === '检验') { extra = [one.specimenType, one.collectionSite].filter(Boolean).join(' · '); }
        else if (order.orderType === '检查') { extra = [one.examPart, one.examMethod].filter(Boolean).join(' · '); }
        else if (order.orderType === '治疗') { extra = one.treatmentTimes ? one.treatmentTimes + '次' : (one.treatmentPart || ''); }
        return {
          _uid: this.nextUid(), kind: 'order', id: order.id, timeSort: order.createTime || '',
          time: text(order.createTime).slice(0, 16), no: order.orderNo || ('#' + order.id),
          category: ['检查', '检验', '治疗'].indexOf(order.orderType) >= 0 ? order.orderType : '治疗',
          summary: names.length ? names.slice(0, 4).join('、') + (names.length > 4 ? ' 等' + names.length + '项' : '') : text(order.diagName) || '无明细',
          extra: extra,
          itemCount: items.length, amount: order.totalAmount,
          status: orderStatusLabel(order), canCancel: Number(order.status) === 1
        };
      },
      onPrint: function (row) {
        if (row.kind === 'rx') { this.$emit('print-rx', row.id); }
        else { this.$emit('print-order', row.id); }
      },
      onCancel: function (row) {
        var vm = this;
        var label = row.kind === 'rx' ? '处方' : '单据';
        ElementPlus.ElMessageBox.confirm('确认作废' + label + '“' + row.no + '”？仅未收费' + label + '可作废。', '作废确认', { type: 'warning' }).then(function () {
          var base = row.kind === 'rx' ? '/api/his/prescription/cancel' : '/api/his/order/cancel';
          return HIS.post(base + '?id=' + encodeURIComponent(row.id));
        }).then(function () {
          ElementPlus.ElMessage.success(label + '已作废');
          return vm.refresh();
        }).catch(function (e) { if (e !== 'cancel' && e !== 'close' && HIS.notifyError) { HIS.notifyError(e); } });
      }
    }
  };

  HIS.components.DwOrdersOverview = DwOrdersOverview;
})();
