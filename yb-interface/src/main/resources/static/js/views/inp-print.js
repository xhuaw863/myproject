/* ============================================================================
 * 住院打印中心(InpPrintCenter) + 全局打印辅助 HIS.print
 *
 * HIS.print(挂到 window.HIS, 供任意组件/页面调用):
 *   preview(html, title, opts)           通用打印预览(el-dialog + iframe srcdoc);
 *                                        opts.autoPrint=true 打开后自动调起打印
 *   dailyBill(visitId, date)             住院每日费用清单  GET /api/his/inp/print/render/daily-bill
 *   settlement(settleId)                 住院结算单        GET .../render/settlement
 *   orders(visitId, orderType)           医嘱单(1长期/2临时) GET .../render/orders
 *   nursing(visitId, startDate, endDate) 护理记录单        GET .../render/nursing
 *   emr(recordId)                        病历              GET .../render/emr
 *   wristband(visitId)                   患者腕带          GET .../render/wristband
 *   tempChart(visitId, startDate, endDate, patient)
 *                                        体温单(前端自渲染: 后端未暴露体温单渲染端点, 取
 *                                        GET /api/his/inp/nursing/temperature/{visitId} 数据生成)
 *   render(kind, params)                 按类型渲染 HTML(不弹预览, 供内嵌预览/批量复用)
 *   batchDailyBill(date, wardId, opts)   批量日清单: POST /api/his/inp/daily-bill/batch-generate
 *                                        批量生成 → 按病区取在院患者逐个渲染 → 合并单文档
 *                                        (每患者一页 page-break-before) → 一次打印;
 *                                        opts.inline=true 时不弹窗, 返回 { html } 供页面内嵌
 *   markDailyBillPrinted(visitId, date)  静默标记日清单已打印(GET 单据取 id → PUT /{id}/printed)
 *   exportPdf(type, params, fileName)    单据PDF导出(带令牌下载)  GET /api/his/inp/print/pdf/{type}
 *                                        type=daily-bill|settlement|orders|nursing|emr|wristband|temp-chart
 *
 * 实现要点:
 *   - 预览对话框由首次调用时动态挂载的独立 Vue 应用承载(#inp-print-host, 与主应用解耦);
 *   - iframe 用 srcdoc 渲染完整 HTML 文档(后端渲染结果即完整文档), 打印调 contentWindow.print();
 *   - 批量合并用 DOMParser: 提取各文档 <style> 去重 + body 拼接 + 页间强制分页。
 *
 * 注册: HIS.views.InpPrintCenter(独立菜单入口, 也可作为工具嵌入其他页面)。
 * 样式: <style id="inp-print-style"> 一次性注入, 类前缀 ipr-*(不动公共 css)。
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ---- 私有样式一次性注入 ---- */
  (function ensureInpPrintStyles() {
    if (document.getElementById('inp-print-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-print-style';
    st.textContent = [
      /* 预览对话框 */
      '.ipr-dialog .el-dialog__body { padding:10px 16px; }',
      '.ipr-dialog-body { height:76vh; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); overflow:hidden; background:#fff; }',
      '.ipr-dialog-body iframe { width:100%; height:100%; border:0; background:#fff; }',
      /* 管理页三栏 */
      '.ipr-cols { display:grid; grid-template-columns:196px 340px 1fr; gap:16px; align-items:start; margin-bottom:16px; }',
      '@media (max-width:1360px) { .ipr-cols { grid-template-columns:196px 1fr; } .ipr-preview-col { grid-column:1 / -1; } }',
      '.ipr-col { background:var(--yb-surface); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:14px 16px; box-shadow:var(--yb-sh-1); }',
      '.ipr-card-title { font-size:14px; font-weight:600; color:var(--yb-ink-1); margin:0 0 10px; padding-left:8px; border-left:3px solid var(--yb-brand); }',
      '.ipr-type { display:flex; align-items:center; gap:8px; padding:9px 10px; border-radius:var(--yb-r-sm); cursor:pointer; color:var(--yb-ink-2); font-size:13px; border:1px solid transparent; margin-bottom:2px; }',
      '.ipr-type:hover { background:var(--yb-surface-2); }',
      '.ipr-type.is-active { background:var(--yb-brand-subtle); border-color:var(--yb-brand-border); color:var(--yb-link); font-weight:600; }',
      '.ipr-type .dot { width:6px; height:6px; border-radius:50%; background:var(--yb-border-strong); flex:none; }',
      '.ipr-type.is-active .dot { background:var(--yb-brand); }',
      '.ipr-hint { font-size:12px; color:var(--yb-ink-4); line-height:1.7; }',
      '.ipr-patient-info { margin-top:8px; font-size:12px; color:var(--yb-ink-2); background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); padding:6px 10px; }',
      '.ipr-preview { height:540px; border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); overflow:hidden; background:#f5f6f8; }',
      '.ipr-preview iframe { width:100%; height:100%; border:0; background:#fff; }',
      '.ipr-pv-empty { height:100%; display:flex; align-items:center; justify-content:center; color:var(--yb-ink-4); text-align:center; font-size:13px; line-height:1.9; }',
      '.ipr-preview-foot { display:flex; justify-content:flex-end; gap:8px; margin-top:10px; }',
      '.ipr-batch-row { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }',
      '.ipr-actions { margin-top:4px; display:flex; gap:8px; flex-wrap:wrap; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ---- 工具 ---- */
  /* 查询串构造(空值不传) */
  function qs(obj) {
    var p = new URLSearchParams();
    Object.keys(obj || {}).forEach(function (k) {
      var v = obj[k];
      if (v !== undefined && v !== null && v !== '') { p.append(k, v); }
    });
    var s = p.toString();
    return s ? ('?' + s) : '';
  }
  /* HTML 文本转义(拼接自渲染文档用) */
  function esc(t) {
    return String(t == null ? '' : t)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function lastNDays(n) {
    var end = new Date();
    var start = new Date();
    start.setDate(start.getDate() - (n - 1));
    return [fmtDate(start), fmtDate(end)];
  }
  function inRange(day, s, e) {
    if (!day) { return false; }
    var d = String(day).slice(0, 10);
    if (s && d < s) { return false; }
    if (e && d > e) { return false; }
    return true;
  }

  /* 合并多个渲染文档为单文档: <style> 文本去重 + body 拼接 + 页间强制分页 */
  function mergePrintDocs(htmlList, title) {
    if (htmlList.length === 1) { return htmlList[0]; }
    var styleMap = {};
    var styleList = [];
    var sections = [];
    htmlList.forEach(function (html) {
      var doc = null;
      try { doc = new DOMParser().parseFromString(html, 'text/html'); } catch (e) { doc = null; }
      if (!doc || !doc.body) { return; }
      Array.prototype.forEach.call(doc.querySelectorAll('style'), function (st) {
        var css = st.textContent || '';
        if (css && !styleMap[css]) { styleMap[css] = true; styleList.push(css); }
      });
      sections.push('<section class="ipr-print-page">' + doc.body.innerHTML + '</section>');
    });
    return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>' + esc(title)
      + '</title><style>\n' + styleList.join('\n')
      + '\n.ipr-print-page + .ipr-print-page { page-break-before: always; }'
      + '\n</style></head><body>\n' + sections.join('\n') + '\n</body></html>';
  }

  /* 体温单前端渲染(后端未暴露打印渲染端点): 患者信息头 + 生命体征记录表 */
  function buildTempChartHtml(rows, patient, startDate, endDate) {
    var p = patient || {};
    var list = (rows || []).filter(function (r) {
      return inRange(r.time || r.recordTime, startDate, endDate);
    });
    var css = [
      '* { margin:0; padding:0; box-sizing:border-box; }',
      "body { font-family:'SimSun','Microsoft YaHei',serif; color:#000; font-size:11pt; background:#fff; }",
      '@page { size: A4 landscape; margin:8mm; }',
      '.doc { width:277mm; margin:0 auto; }',
      ".doc-title { text-align:center; font-size:16pt; font-weight:bold; letter-spacing:4px; font-family:'SimHei','Microsoft YaHei',sans-serif; }",
      '.doc-sub { font-size:10pt; margin:6px 0 10px; text-align:center; }',
      'table { width:100%; border-collapse:collapse; }',
      'th, td { border:0.4mm solid #000; padding:3px 5px; text-align:center; font-size:10pt; }',
      'th { background:#f2f2f2; font-weight:bold; }',
      '.empty { text-align:center; padding:20px; color:#666; }',
      '@media print { body { background:none; } th { -webkit-print-color-adjust:exact; print-color-adjust:exact; } }'
    ].join('\n');
    var subBits = [
      p.ward_name || p.wardName || '',
      p.bed_no ? (p.bed_no + '床') : '',
      p.patient_name ? ('姓名：' + p.patient_name) : '',
      p.gender_name || p.gender || '',
      (p.age != null && p.age !== '') ? (p.age + '岁') : '',
      p.inp_no ? ('住院号：' + p.inp_no) : '',
      (startDate || endDate) ? ('区间：' + (startDate || '…') + ' ~ ' + (endDate || '…')) : ''
    ].filter(function (s) { return !!s; });
    var body = '';
    if (list.length) {
      body = '<table><thead><tr>'
        + '<th style="width:22%;">记录时间</th><th>体温(°C)</th><th>脉搏(次/分)</th><th>呼吸(次/分)</th><th>血压(mmHg)</th>'
        + '</tr></thead><tbody>'
        + list.map(function (r) {
          var bp = (r.systolicBp != null || r.diastolicBp != null)
            ? ((r.systolicBp == null ? '' : r.systolicBp) + '/' + (r.diastolicBp == null ? '' : r.diastolicBp))
            : '';
          return '<tr><td>' + esc(r.time || r.recordTime) + '</td><td>' + esc(r.temperature)
            + '</td><td>' + esc(r.pulse) + '</td><td>' + esc(r.respiration) + '</td><td>' + esc(bp) + '</td></tr>';
        }).join('')
        + '</tbody></table>';
    } else {
      body = '<div class="empty">所选区间内暂无体温单记录</div>';
    }
    return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>体温单</title><style>\n'
      + css + '\n</style></head><body><div class="doc">'
      + '<div class="doc-title">体　温　单</div>'
      + '<div class="doc-sub">' + esc(subBits.join('　')) + '</div>'
      + body
      + '</div></body></html>';
  }

  /* ==================== 打印预览宿主(懒挂载独立 Vue 应用) ==================== */
  var printHost = null;

  function ensurePrintHost() {
    if (printHost) { return printHost; }
    var el = document.createElement('div');
    el.id = 'inp-print-host';
    document.body.appendChild(el);
    /* 共享状态: 外部(HIS.print.*)只改状态, 渲染由宿主应用承载 */
    var state = Vue.reactive({ visible: false, title: '', html: '', autoPrint: false });
    var app = Vue.createApp({
      data: function () { return { s: state }; },
      methods: {
        doPrint: function () {
          var f = this.$refs.frame;
          if (!f || !f.contentWindow) { return; }
          try { f.contentWindow.focus(); f.contentWindow.print(); } catch (e) { HIS.notifyError(e); }
        },
        /* 新窗口打开: Blob URL 承载完整文档(避免 document.write 注入) */
        openWindow: function () {
          var url = URL.createObjectURL(new Blob([state.html || ''], { type: 'text/html;charset=utf-8' }));
          var w = window.open(url, '_blank');
          if (!w) {
            URL.revokeObjectURL(url);
            HIS.notifyError(new Error('浏览器拦截了新窗口, 请允许弹出窗口后重试'));
            return;
          }
          setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
        },
        onFrameLoad: function () {
          if (!state.autoPrint) { return; }
          state.autoPrint = false;
          var vm = this;
          setTimeout(function () { vm.doPrint(); }, 200);
        },
        close: function () { state.visible = false; }
      },
      template: [
        '<el-dialog v-model="s.visible" :title="s.title || \'打印预览\'" width="80%" top="4vh" append-to-body destroy-on-close :close-on-click-modal="false" class="ipr-dialog">',
        '  <div class="ipr-dialog-body">',
        '    <iframe ref="frame" :srcdoc="s.html" @load="onFrameLoad"></iframe>',
        '  </div>',
        '  <template #footer>',
        '    <el-button @click="openWindow">新窗口打开</el-button>',
        '    <el-button @click="close">关闭</el-button>',
        '    <el-button type="primary" @click="doPrint">打印</el-button>',
        '  </template>',
        '</el-dialog>'
      ].join('\n')
    });
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    app.mount(el);
    printHost = { state: state };
    return printHost;
  }

  /* ==================== HIS.print 全局打印辅助 ==================== */
  HIS.print = {
    /* 通用打印预览: html 为完整文档字符串 */
    preview: function (html, title, opts) {
      var host = ensurePrintHost();
      var s = host.state;
      s.title = title || '打印预览';
      s.html = html || '';
      s.autoPrint = !!(opts && opts.autoPrint);
      s.visible = true;
      return host;
    },

    /* 住院每日费用清单 */
    dailyBill: function (visitId, date) {
      var vm = this;
      return HIS.get('/api/his/inp/print/render/daily-bill' + qs({ visitId: visitId, date: date }))
        .then(function (html) { vm.preview(html, '住院每日费用清单'); return html; });
    },

    /* 住院结算单 */
    settlement: function (settleId) {
      var vm = this;
      return HIS.get('/api/his/inp/print/render/settlement' + qs({ settleId: settleId }))
        .then(function (html) { vm.preview(html, '住院结算单'); return html; });
    },

    /* 医嘱单: orderType 1长期 2临时 */
    orders: function (visitId, orderType) {
      var vm = this;
      var type = orderType === 2 ? 2 : 1;
      return HIS.get('/api/his/inp/print/render/orders' + qs({ visitId: visitId, orderType: type }))
        .then(function (html) { vm.preview(html, type === 1 ? '长期医嘱单' : '临时医嘱单'); return html; });
    },

    /* 护理记录单(日期区间缺省时后端取最近7天) */
    nursing: function (visitId, startDate, endDate) {
      var vm = this;
      return HIS.get('/api/his/inp/print/render/nursing' + qs({ visitId: visitId, startDate: startDate, endDate: endDate }))
        .then(function (html) { vm.preview(html, '护理记录单'); return html; });
    },

    /* 病历 */
    emr: function (recordId) {
      var vm = this;
      return HIS.get('/api/his/inp/print/render/emr' + qs({ recordId: recordId }))
        .then(function (html) { vm.preview(html, '病历'); return html; });
    },

    /* 患者腕带 */
    wristband: function (visitId) {
      var vm = this;
      return HIS.get('/api/his/inp/print/render/wristband' + qs({ visitId: visitId }))
        .then(function (html) { vm.preview(html, '患者腕带'); return html; });
    },

    /* 体温单(前端自渲染: 取护理体温记录生成打印文档) */
    tempChart: function (visitId, startDate, endDate, patient) {
      var vm = this;
      return HIS.get('/api/his/inp/nursing/temperature/' + HIS.idParam(visitId)).then(function (rows) {
        var html = buildTempChartHtml(rows || [], patient, startDate, endDate);
        vm.preview(html, '体温单');
        return html;
      });
    },

    /* 按类型渲染 HTML(不弹预览): 供页面内嵌预览与批量复用 */
    render: function (kind, params) {
      var urls = {
        daily: '/api/his/inp/print/render/daily-bill',
        settle: '/api/his/inp/print/render/settlement',
        orders: '/api/his/inp/print/render/orders',
        nursing: '/api/his/inp/print/render/nursing',
        emr: '/api/his/inp/print/render/emr',
        wristband: '/api/his/inp/print/render/wristband'
      };
      if (!urls[kind]) { return Promise.reject(new Error('未知打印类型: ' + kind)); }
      return HIS.get(urls[kind] + qs(params));
    },

    /* 批量日清单: 批量生成 → 病区在院患者逐个渲染 → 合并单文档(每患者一页);
     * opts.inline=true 时不弹预览, 调用方拿 html 内嵌展示 */
    batchDailyBill: function (date, wardId, opts) {
      var vm = this;
      var title = '住院每日费用清单 · 批量';
      return HIS.post('/api/his/inp/daily-bill/batch-generate' + qs({ date: date })).then(function (sum) {
        var p = { page: 1, size: 300, visitStatus: 2 };
        if (wardId !== '' && wardId != null) { p.wardId = wardId; }
        return HIS.get('/api/his/inp/patients' + qs(p)).then(function (d) {
          return { sum: sum || {}, patients: (d && d.records) || [] };
        });
      }).then(function (rs) {
        var list = rs.patients;
        if (!list.length) { throw new Error('所选范围内没有在院患者, 无法批量打印日清单'); }
        var docs = [];
        var chain = Promise.resolve();
        /* 串行渲染(避免瞬时并发压后端), 单患者失败跳过不阻断整批 */
        list.forEach(function (pt) {
          chain = chain.then(function () {
            return HIS.get('/api/his/inp/print/render/daily-bill' + qs({ visitId: pt.id, date: date }))
              .then(function (html) { docs.push(html); })
              .catch(function () { });
          });
        });
        return chain.then(function () {
          if (!docs.length) { throw new Error('批量渲染失败: 无可用日清单'); }
          var merged = mergePrintDocs(docs, title + '(' + docs.length + '人)');
          if (!(opts && opts.inline)) { vm.preview(merged, title + '(' + docs.length + '人)'); }
          return { generated: rs.sum, total: list.length, printed: docs.length, html: merged };
        });
      });
    },

    /* 静默标记日清单已打印(取当日单据 id → PUT printed; 失败不打扰) */
    markDailyBillPrinted: function (visitId, date) {
      return HIS.get('/api/his/inp/daily-bill' + qs({ visitId: visitId, date: date })).then(function (bill) {
        if (bill && bill.id) { return HIS.put('/api/his/inp/daily-bill/' + HIS.idParam(bill.id) + '/printed', {}); }
      }).catch(function () { });
    },

    /* 单据PDF导出(带令牌下载, 后端 OpenPDF 渲染):
     * type=daily-bill|settlement|orders|nursing|emr|wristband|temp-chart */
    exportPdf: function (type, params, fileName) {
      return HIS.download('/api/his/inp/print/pdf/' + type + qs(params),
        fileName || ('住院单据_' + today() + '.pdf'));
    }
  };


  /* ==================== 住院打印中心(独立管理页) ==================== */

  /* 病历类型 / 状态字典(对齐后端 InpPrintService.MED_RECORD_TYPES) */
  var RECORD_TYPE_NAMES = {
    1: '入院记录', 2: '首次病程', 3: '日常病程', 4: '查房记录', 5: '术前小结',
    6: '手术记录', 7: '术后病程', 8: '出院小结', 9: '死亡记录'
  };
  var RECORD_STATUS_NAMES = { 1: '草稿', 2: '已提交', 3: '已审核' };

  /* ------------------------------------------------------------------------
   * 布局: 左(打印类型 7 项) + 中(参数) + 右(iframe 实时预览) + 底部(批量日清单)。
   * 渲染一律走 HIS.print.render / buildTempChartHtml, 右栏内嵌预览(不弹对话框);
   * 「预览并打印」靠 iframe @load 事件衔接(内容未变时直接打印, 见 previewCurrent)。
   * --------------------------------------------------------------------- */
  HIS.views.InpPrintCenter = {
    mixins: [HIS.kwSelectMixin || {}],

    data: function () {
      return {
        /* 打印类型 */
        types: [
          { key: 'daily', label: '每日费用清单' },
          { key: 'settle', label: '住院结算单' },
          { key: 'orders', label: '医嘱单' },
          { key: 'nursing', label: '护理记录单' },
          { key: 'temp', label: '体温单' },
          { key: 'emr', label: '病历' },
          { key: 'wristband', label: '患者腕带' }
        ],
        type: 'daily',

        /* 患者选择(病区 + 关键字 → 在院患者) */
        wards: [],
        wardFilter: '',
        patientKw: '',
        patients: [],
        patientLoading: false,
        visitId: null,

        /* 各类型参数 */
        billDate: today(),
        settleId: null,
        orderType: 1,
        nursingRange: lastNDays(7),
        tempRange: lastNDays(7),
        records: [],
        recordLoading: false,
        recordId: null,

        /* 预览 */
        previewHtml: '',
        previewTitle: '',
        previewLoading: false,
        pendingPrint: false,

        /* 批量日清单 */
        batchWardId: '',
        batchDate: today(),
        batchLoading: false,

        /* PDF导出 */
        pdfLoading: false
      };
    },

    computed: {
      /* 结算单按结算ID定位, 其余类型均需先选患者 */
      requiresPatient: function () { return this.type !== 'settle'; },
      currentTypeLabel: function () {
        for (var i = 0; i < this.types.length; i++) {
          if (this.types[i].key === this.type) { return this.types[i].label; }
        }
        return '';
      },
      currentPatient: function () {
        var id = this.visitId;
        if (id == null) { return null; }
        var list = this.patients || [];
        for (var i = 0; i < list.length; i++) {
          if (HIS.sameId(list[i].id, id)) { return list[i]; }
        }
        return null;
      },
      canPreview: function () {
        if (this.type === 'settle') { return !!this.settleId; }
        if (this.type === 'emr') { return !!(this.visitId && this.recordId); }
        return !!this.visitId;
      }
    },

    methods: {
      /* 患者下拉展示: 住院号 · 姓名（病区 床号）(记录为蛇形键, 兼容驼峰) */
      patientLabel: function (p) {
        if (!p) { return ''; }
        var name = p.patient_name || p.patientName || '';
        var no = p.inp_no || p.inpNo || '';
        var ward = p.ward_name || p.wardName || '';
        var bed = p.bed_no || p.bedNo || '';
        var s = no + (name ? (' · ' + name) : '');
        if (ward) { s += '（' + ward + (bed ? (' ' + bed + '床') : '') + '）'; }
        else if (bed) { s += '（' + bed + '床）'; }
        return s;
      },
      recordLabel: function (r) {
        if (!r) { return ''; }
        var t = RECORD_TYPE_NAMES[r.recordType] || ('类型' + r.recordType);
        var st = RECORD_STATUS_NAMES[r.status] || '';
        var time = String(r.recordTime == null ? '' : r.recordTime).replace('T', ' ').slice(0, 16);
        var s = t + (r.title ? (' · ' + r.title) : '');
        if (st) { s += '（' + st + '）'; }
        if (time) { s += ' ' + time; }
        return s;
      },
      switchType: function (key) {
        this.type = key;
        this.previewHtml = '';
        this.previewTitle = '';
        this.pendingPrint = false;
        if (key === 'emr' && this.visitId) { this.loadRecords(); }
      },
      loadWards: function () {
        var vm = this;
        HIS.get('/api/his/inp/bed/ward/list').then(function (rows) {
          var list = rows || [];
          var enabled = list.filter(function (w) { return w.status === 1; });
          vm.wards = enabled.length ? enabled : list;
        }).catch(HIS.notifyError);
      },
      loadPatients: function () {
        var vm = this;
        vm.patientLoading = true;
        var p = { page: 1, size: 300, visitStatus: 2 };
        if (vm.wardFilter !== '' && vm.wardFilter != null) { p.wardId = vm.wardFilter; }
        if (vm.patientKw) { p.keyword = vm.patientKw; }
        HIS.get('/api/his/inp/patients' + qs(p)).then(function (d) {
          var list = (d && d.records) || [];
          vm.patients = list;
          /* 当前所选患者不在新列表时清空(防串号) */
          if (vm.visitId != null) {
            var still = false;
            for (var i = 0; i < list.length; i++) {
              if (HIS.sameId(list[i].id, vm.visitId)) { still = true; break; }
            }
            if (!still) { vm.visitId = null; vm.records = []; vm.recordId = null; }
          }
        }).catch(HIS.notifyError).finally(function () { vm.patientLoading = false; });
      },
      onPatientChange: function () {
        this.records = [];
        this.recordId = null;
        if (this.type === 'emr' && this.visitId) { this.loadRecords(); }
      },
      loadRecords: function () {
        var vm = this;
        if (!vm.visitId) { vm.records = []; return; }
        vm.recordLoading = true;
        HIS.get('/api/his/inp/record/list/' + HIS.idParam(vm.visitId)).then(function (rows) {
          vm.records = rows || [];
        }).catch(HIS.notifyError).finally(function () { vm.recordLoading = false; });
      },
      setPreview: function (html, title) {
        this.previewHtml = html || '';
        this.previewTitle = title || '预览';
      },
      /* iframe 加载完成 → 若在「预览并打印」流程中则自动调起打印 */
      onFrameLoad: function () {
        if (!this.pendingPrint) { return; }
        this.pendingPrint = false;
        var vm = this;
        setTimeout(function () { vm.printPreview(); }, 100);
      },
      /* 按当前类型渲染 HTML(预览与批量共用口径) */
      renderCurrent: function () {
        var vm = this;
        var type = this.type;
        var nr = this.nursingRange || [];
        var tr = this.tempRange || [];
        if (type === 'daily') {
          return HIS.print.render('daily', { visitId: this.visitId, date: this.billDate });
        }
        if (type === 'settle') {
          return HIS.print.render('settle', { settleId: this.settleId });
        }
        if (type === 'orders') {
          return HIS.print.render('orders', { visitId: this.visitId, orderType: this.orderType });
        }
        if (type === 'nursing') {
          return HIS.print.render('nursing', { visitId: this.visitId, startDate: nr[0], endDate: nr[1] });
        }
        if (type === 'temp') {
          return HIS.get('/api/his/inp/nursing/temperature/' + HIS.idParam(this.visitId)).then(function (rows) {
            return buildTempChartHtml(rows || [], vm.currentPatient, tr[0], tr[1]);
          });
        }
        if (type === 'emr') {
          return HIS.print.render('emr', { recordId: this.recordId });
        }
        if (type === 'wristband') {
          return HIS.print.render('wristband', { visitId: this.visitId });
        }
        return Promise.reject(new Error('未知打印类型: ' + type));
      },
      /* 预览: 渲染到右栏; 内容未变(iframe 不重建)且有打印意图时直接打印 */
      previewCurrent: function () {
        if (!this.canPreview) { return Promise.resolve(null); }
        var vm = this;
        vm.previewLoading = true;
        return this.renderCurrent().then(function (html) {
          var title = vm.currentTypeLabel;
          var pt = vm.currentPatient;
          if (pt) { title += ' · ' + (pt.patient_name || pt.patientName || ''); }
          var changed = (vm.previewHtml !== html);
          vm.setPreview(html, title);
          if (vm.pendingPrint && !changed) {
            vm.pendingPrint = false;
            setTimeout(function () { vm.printPreview(); }, 50);
          }
          return html;
        }).catch(function (e) { HIS.notifyError(e); return null; })
          .finally(function () { vm.previewLoading = false; });
      },
      previewAndPrint: function () {
        if (!this.canPreview) { return; }
        var vm = this;
        this.pendingPrint = true;
        this.previewCurrent().then(function (html) {
          if (!html) { vm.pendingPrint = false; }
        });
      },
      printPreview: function () {
        var f = this.$refs.pvFrame;
        if (!f || !f.contentWindow) { return; }
        try { f.contentWindow.focus(); f.contentWindow.print(); } catch (e) { HIS.notifyError(e); }
      },
      /* 新窗口打开: Blob URL 承载完整文档 */
      openPreviewWindow: function () {
        if (!this.previewHtml) { return; }
        var url = URL.createObjectURL(new Blob([this.previewHtml], { type: 'text/html;charset=utf-8' }));
        var w = window.open(url, '_blank');
        if (!w) {
          URL.revokeObjectURL(url);
          HIS.notifyError(new Error('浏览器拦截了新窗口, 请允许弹出窗口后重试'));
          return;
        }
        setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
      },
      /* 批量日清单: 病区在院患者批量生成 → 合并单文档(每患者一页) → 右栏预览 */
      batchPrint: function () {
        var vm = this;
        vm.batchLoading = true;
        HIS.print.batchDailyBill(this.batchDate, this.batchWardId, { inline: true }).then(function (r) {
          vm.setPreview(r.html, '住院每日费用清单 · 批量(' + r.printed + '人)');
          HIS.notifySuccess('已合并 ' + r.printed + '/' + r.total + ' 名患者的日清单, 可在右栏直接打印');
        }).catch(HIS.notifyError).finally(function () { vm.batchLoading = false; });
      },

      /* 当前类型的PDF导出地址(与 renderCurrent 同参数口径, 走后端 OpenPDF) */
      pdfExportUrl: function () {
        var nr = this.nursingRange || [];
        var tr = this.tempRange || [];
        var type = this.type;
        if (type === 'daily') { return '/api/his/inp/print/pdf/daily-bill' + qs({ visitId: this.visitId, date: this.billDate }); }
        if (type === 'settle') { return '/api/his/inp/print/pdf/settlement' + qs({ settleId: this.settleId }); }
        if (type === 'orders') { return '/api/his/inp/print/pdf/orders' + qs({ visitId: this.visitId, orderType: this.orderType }); }
        if (type === 'nursing') { return '/api/his/inp/print/pdf/nursing' + qs({ visitId: this.visitId, startDate: nr[0], endDate: nr[1] }); }
        if (type === 'temp') { return '/api/his/inp/print/pdf/temp-chart' + qs({ visitId: this.visitId, startDate: tr[0], endDate: tr[1] }); }
        if (type === 'emr') { return '/api/his/inp/print/pdf/emr' + qs({ recordId: this.recordId }); }
        if (type === 'wristband') { return '/api/his/inp/print/pdf/wristband' + qs({ visitId: this.visitId }); }
        return '';
      },
      /* 导出当前单据PDF(认证走 Authorization 头, 不能 window.open, 用带令牌下载) */
      exportPdfCurrent: function () {
        if (!this.canPreview) { return; }
        var vm = this;
        var url = this.pdfExportUrl();
        if (!url) { HIS.notifyError(new Error('当前类型暂不支持PDF导出')); return; }
        vm.pdfLoading = true;
        HIS.download(url, vm.currentTypeLabel + '.pdf').then(function (name) {
          HIS.notifySuccess('已导出: ' + name);
        }).catch(HIS.notifyError).finally(function () { vm.pdfLoading = false; });
      }
    },

    mounted: function () {
      this.loadWards();
      this.loadPatients();
    },

    template: [
      '<div>',
      '  <div class="page-title">打印中心 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">(住院单据统一打印入口 · 实时预览 · 批量日清单)</span></div>',

      /* ---- 三栏主区 ---- */
      '  <div class="ipr-cols">',

      /* 左栏: 打印类型 */
      '    <div class="ipr-col">',
      '      <div class="ipr-card-title">打印类型</div>',
      '      <div v-for="t in types" :key="t.key" class="ipr-type" :class="{ \'is-active\': type === t.key }" @click="switchType(t.key)">',
      '        <span class="dot"></span><span>{{ t.label }}</span>',
      '      </div>',
      '    </div>',

      /* 中栏: 参数 */
      '    <div class="ipr-col">',
      '      <div class="ipr-card-title">{{ currentTypeLabel }} · 参数</div>',
      '      <el-form label-width="80px" label-position="left" size="default">',

      /* 患者选择(结算单除外, 按结算ID定位) */
      '        <template v-if="requiresPatient">',
      '          <el-form-item label="病区">',
      '            <el-select v-model="wardFilter" clearable placeholder="全部病区" style="width:100%" @change="loadPatients">',
      '              <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="关键字">',
      '            <el-input v-model="patientKw" placeholder="姓名 / 住院号" clearable @keyup.enter="loadPatients" @clear="loadPatients"></el-input>',
      '          </el-form-item>',
      '          <el-form-item label="患者">',
      '            <el-select v-model="visitId" filterable :loading="patientLoading" placeholder="搜索选择在院患者" style="width:100%" :filter-method="kwFilter(\'pvPat\')" @change="onPatientChange">',
      '              <el-option v-for="p in kwOptions(\'pvPat\', patients, [\'patient_name\', \'patientName\', \'inp_no\', \'inpNo\', \'ward_name\', \'wardName\', \'bed_no\', \'bedNo\'])" :key="p.id" :label="patientLabel(p)" :value="p.id"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <div v-if="currentPatient" class="ipr-patient-info">{{ patientLabel(currentPatient) }}<span v-if="currentPatient.admit_date"> · 入院 {{ String(currentPatient.admit_date).slice(0, 10) }}</span></div>',
      '        </template>',

      /* 各类型参数 */
      '        <el-form-item v-if="type === \'daily\'" label="清单日期">',
      '          <el-date-picker v-model="billDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:100%"></el-date-picker>',
      '        </el-form-item>',
      '        <el-form-item v-if="type === \'settle\'" label="结算ID">',
      '          <el-input v-model="settleId" placeholder="粘贴住院结算记录ID(19位数字)" clearable style="width:100%"></el-input>',
      '        </el-form-item>',
      '        <div v-if="type === \'settle\'" class="ipr-hint">结算单以结算记录ID定位(住院结算完成后由结算模块提供)。</div>',
      '        <el-form-item v-if="type === \'orders\'" label="医嘱类型">',
      '          <el-radio-group v-model="orderType">',
      '            <el-radio-button :label="1">长期医嘱</el-radio-button>',
      '            <el-radio-button :label="2">临时医嘱</el-radio-button>',
      '          </el-radio-group>',
      '        </el-form-item>',
      '        <el-form-item v-if="type === \'nursing\'" label="日期范围">',
      '          <el-date-picker v-model="nursingRange" type="daterange" value-format="YYYY-MM-DD" :clearable="false" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:100%"></el-date-picker>',
      '        </el-form-item>',
      '        <el-form-item v-if="type === \'temp\'" label="日期范围">',
      '          <el-date-picker v-model="tempRange" type="daterange" value-format="YYYY-MM-DD" :clearable="false" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:100%"></el-date-picker>',
      '        </el-form-item>',
      '        <div v-if="type === \'temp\'" class="ipr-hint">体温单由前端取护理体温记录渲染, 区间内无记录时提示为空。</div>',
      '        <el-form-item v-if="type === \'emr\'" label="病历">',
      '          <el-select v-model="recordId" filterable :loading="recordLoading" placeholder="选择病历" style="width:100%" no-data-text="该患者暂无病历">',
      '            <el-option v-for="r in records" :key="r.id" :label="recordLabel(r)" :value="r.id"></el-option>',
      '          </el-select>',
      '        </el-form-item>',
      '        <div v-if="type === \'wristband\'" class="ipr-hint">腕带含患者标识(姓名 / 住院号 / 病区床号), 建议配腕带打印机使用。</div>',

      '      </el-form>',
      '      <div class="ipr-actions">',
      '        <el-button type="primary" :loading="previewLoading" :disabled="!canPreview" @click="previewCurrent">预览</el-button>',
      '        <el-button :disabled="!canPreview" @click="previewAndPrint">预览并打印</el-button>',
      '        <el-button type="primary" plain size="small" :loading="pdfLoading" :disabled="!canPreview" @click="exportPdfCurrent">导出PDF</el-button>',
      '      </div>',
      '    </div>',

      /* 右栏: 实时预览 */
      '    <div class="ipr-col ipr-preview-col">',
      '      <div class="ipr-card-title">{{ previewTitle || \'预览\' }}</div>',
      '      <div class="ipr-preview">',
      '        <iframe v-if="previewHtml" ref="pvFrame" :srcdoc="previewHtml" @load="onFrameLoad"></iframe>',
      '        <div v-else class="ipr-pv-empty">选择左侧打印类型并设置参数,<br/>点击「预览」在此查看打印效果</div>',
      '      </div>',
      '      <div class="ipr-preview-foot">',
      '        <el-button size="small" :disabled="!previewHtml" @click="openPreviewWindow">新窗口打开</el-button>',
      '        <el-button size="small" type="primary" :disabled="!previewHtml" @click="printPreview">打印</el-button>',
      '      </div>',
      '    </div>',
      '  </div>',

      /* ---- 底部: 批量打印日清单 ---- */
      '  <div class="ipr-col">',
      '    <div class="ipr-card-title">批量打印日清单</div>',
      '    <div class="ipr-batch-row">',
      '      <el-select v-model="batchWardId" clearable placeholder="全部病区" style="width:220px">',
      '        <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>',
      '      </el-select>',
      '      <el-date-picker v-model="batchDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:170px"></el-date-picker>',
      '      <el-button type="primary" :loading="batchLoading" @click="batchPrint">批量生成并预览</el-button>',
      '      <span class="ipr-hint">对所选病区在院患者批量生成日清单, 每患者一页合并展示, 一次打印全部。</span>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

})();
