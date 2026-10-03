/* 医生工作站打印工具：处方笺、申请单、门诊病历与医疗证明 */
;(function () {
  var HIS = (window.HIS = window.HIS || {});

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function value(obj, names, fallback) {
    obj = obj || {};
    for (var i = 0; i < names.length; i++) {
      if (obj[names[i]] !== null && obj[names[i]] !== undefined && obj[names[i]] !== '') { return obj[names[i]]; }
    }
    return fallback == null ? '' : fallback;
  }
  function gender(v) {
    if (v === '1' || v === 1 || v === '男') { return '男'; }
    if (v === '2' || v === 2 || v === '女') { return '女'; }
    return v || '';
  }
  function dateText(v) { return v ? String(v).replace('T', ' ').slice(0, 16) : ''; }
  function money(v) { var n = Number(v); return isFinite(n) ? n.toFixed(2) : '0.00'; }
  function diagText(list) {
    return (list || []).map(function (d, index) {
      var name = value(d, ['diagName', 'name'], '');
      return (String(d.maindiagFlag) === '1' || (index === 0 && !list.some(function (x) { return String(x.maindiagFlag) === '1'; })) ? '[主] ' : '') + name;
    }).filter(Boolean).join('；');
  }
  function nl2br(v) { return esc(v).replace(/\r?\n/g, '<br>'); }

  function getHospitalName() {
    var user = typeof HIS.getUser === 'function' ? (HIS.getUser() || {}) : {};
    return value(HIS.config, ['hospitalName'], '') || value(user, ['tenantName', 'orgName'], '某某医院');
  }

  function openPrintWindow(title, htmlContent) {
    var win = window.open('', '_blank', 'width=900,height=700,noopener=no');
    if (!win) {
      if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.warning('浏览器阻止了打印窗口，请允许本站弹出窗口'); }
      return null;
    }
    var doc = win.document;
    doc.title = String(title || '打印');
    var meta = doc.createElement('meta'); meta.setAttribute('charset', 'UTF-8'); doc.head.appendChild(meta);
    var style = doc.createElement('style');
    style.textContent = '@page{size:A4;margin:14mm 16mm}*{box-sizing:border-box}body{font-family:"宋体",SimSun,serif;margin:20px;color:#111;font-size:13px;line-height:1.65}' +
      '.print-sheet{max-width:760px;margin:0 auto}.print-header{text-align:center;font-size:20px;font-weight:bold;letter-spacing:2px;margin-bottom:4px}' +
      '.print-subheader{text-align:center;font-size:17px;font-weight:bold;letter-spacing:8px;margin-bottom:12px}.print-meta{border-top:2px solid #222;border-bottom:1px solid #555}' +
      'table{width:100%;border-collapse:collapse}td,th{padding:5px 8px;border-bottom:1px solid #ddd;font-size:12px;text-align:left;vertical-align:top}' +
      'th{font-weight:bold;background:var(--yb-surface-2)}.print-section{margin:12px 0}.print-rx-group{margin:10px 0;padding:8px 10px;border-left:3px solid var(--yb-brand)}' +
      '.print-rx-line{display:flex;justify-content:space-between;gap:16px}.print-usage{padding-left:24px;color:var(--yb-ink-1);font-size:12px}.print-slash{text-align:right;font-size:18px;margin:8px 0}' +
      '.print-footer{margin-top:20px;padding-top:10px;border-top:1px solid #555;display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.print-content{min-height:200px;padding:18px 8px;font-size:15px;line-height:2}' +
      '.record-row{margin:9px 0;white-space:pre-wrap}.record-label{font-weight:bold}.print-note{color:#555;font-size:11px;margin-top:12px}@media print{body{margin:0}.no-print{display:none!important}}';
    doc.head.appendChild(style);
    while (doc.body.firstChild) { doc.body.removeChild(doc.body.firstChild); }
    var parsed = new DOMParser().parseFromString('<body>' + htmlContent + '</body>', 'text/html');
    Array.prototype.slice.call(parsed.body.childNodes).forEach(function (node) { doc.body.appendChild(doc.importNode(node, true)); });
    win.focus();
    setTimeout(function () { try { win.print(); } catch (e) { /* 用户仍可使用浏览器打印 */ } }, 300);
    return win;
  }

  HIS.getHospitalName = getHospitalName;
  HIS.openPrintWindow = openPrintWindow;

  /* OP-D 14.7 集中连打: 多单据聚合到同一打印窗口, 逐张分页(避免连发 window.open 被拦截) */
  HIS.printSheets = function (sheets, title) {
    sheets = sheets || [];
    if (!sheets.length) {
      if (window.ElementPlus && ElementPlus.ElMessage) { ElementPlus.ElMessage.warning('没有可打印的单据'); }
      return null;
    }
    var html = sheets.map(function (s, i) {
      return (i ? '<div style="page-break-after:always"></div>' : '') + (s.html || '');
    }).join('');
    return openPrintWindow(title || ('集中打印(' + sheets.length + ' 份)'), html);
  };

  /* 处方笺：按 groupNo 分组，呈现前记、正文 Rp 和后记。 */
  function rxHtml(data) {
    data = data || {};
    var rx = data.prescription || data.rx || {};
    var patient = data.patient || {};
    var postscript = data.postscript || {};
    var groups = {};
    (data.items || data.body || []).forEach(function (item) {
      var key = value(item, ['groupNo'], '1');
      if (!groups[key]) { groups[key] = []; }
      groups[key].push(item);
    });
    var itemsHtml = '';
    Object.keys(groups).sort(function (a, b) { return Number(a) - Number(b); }).forEach(function (groupNo) {
      itemsHtml += '<div class="print-rx-group"><strong>Rp.' + esc(groupNo) + '</strong>';
      groups[groupNo].forEach(function (item) {
        var itemName = value(item, ['itemName', 'genericName', 'drugName'], '');
        var qty = value(item, ['quantity'], '');
        itemsHtml += '<div class="print-rx-line"><span>' + esc(itemName) + '　' + esc(item.spec || '') + '</span><span>×' + esc(qty) + esc(item.unit || '') + '</span></div>';
        itemsHtml += '<div class="print-usage">用法：' + esc(value(item, ['usageMethod', 'administration'], '')) +
          '　频次：' + esc(item.frequency || '') + '　' + esc(item.days || '') + '天　剂量：' +
          esc(item.dosage || '') + esc(item.dosageUnit || '') + '</div>';
      });
      itemsHtml += '</div>';
    });
    var hospital = data.hospitalName || value(data.preface, ['hospitalName'], '') || getHospitalName();
    var html = '<div class="print-sheet">' +
      '<div class="print-header">' + esc(hospital) + '</div><div class="print-subheader">处 方 笺</div>' +
      '<table class="print-meta"><tr><td>姓名：' + esc(value(patient, ['name', 'patientName'], value(rx, ['patientName'], ''))) + '</td><td>性别：' + esc(gender(patient.gender)) + '</td><td>年龄：' + esc(patient.age || '') + '</td></tr>' +
      '<tr><td>门诊号：' + esc(value(patient, ['outpatientNo', 'iptOtpNo', 'regNo'], value(rx, ['iptOtpNo'], ''))) + '</td><td>科别：' + esc(value(patient, ['deptName'], value(rx, ['deptName'], value(postscript, ['deptName'], '')))) + '</td><td>日期：' + esc(dateText(value(rx, ['createTime'], value(postscript, ['createTime'], '')))) + '</td></tr>' +
      '<tr><td colspan="3">临床诊断：' + esc(diagText(data.diagnoses || [])) + '</td></tr><tr><td colspan="3">费别：' + esc(value(patient, ['insuranceType', 'insutypeName'], '自费')) + '</td></tr></table>' +
      '<div class="print-section"><strong>Rp</strong>' + itemsHtml + '<div class="print-slash">／</div></div>' +
      '<div class="print-footer"><span>金额：￥' + money(value(rx, ['totalAmount'], value(postscript, ['totalAmount'], 0))) + '</span><span>医师签名：' + esc(value(postscript, ['doctorName'], value(rx, ['drName'], '________'))) + '</span><span>审核药师：________</span></div></div>';
    return html;
  }
  HIS.printRx = function (data) { return openPrintWindow('处方笺', rxHtml(data || {})); };

  /* 检查、检验申请单。结构化字段优先取明细，兼容主表字段。 */
  function orderHtml(data) {
    data = data || {};
    var order = data.order || data;
    var patient = data.patient || {};
    var isLab = order.orderType === '检验';
    var title = isLab ? '检验申请单' : (order.orderType === '治疗' ? '治疗单' : '检查申请单');
    var orderItems = data.items || order.items || [];
    var firstItem = orderItems[0] || {};
    var rows = '<table><thead><tr><th>项目</th><th>数量</th>' + (isLab ? '<th>标本</th><th>条件</th>' : '<th>部位</th><th>方法</th>') + '<th>紧急</th></tr></thead><tbody>';
    orderItems.forEach(function (item) {
      rows += '<tr><td>' + esc(item.itemName) + '</td><td>' + esc(item.quantity) + '</td>' +
        (isLab ? '<td>' + esc(value(item, ['specimenType'], value(order, ['specimenType'], ''))) + '</td><td>' + esc(value(item, ['specimenCondition'], value(order, ['specimenCondition'], ''))) + '</td>' :
          '<td>' + esc(value(item, ['examPart', 'treatmentPart'], value(order, ['examPart'], ''))) + '</td><td>' + esc(value(item, ['examMethod'], item.spec || '')) + '</td>') +
        '<td>' + esc(value(item, ['urgency'], value(order, ['urgency'], '常规'))) + '</td></tr>';
    });
    rows += '</tbody></table>';
    var diagnosis = diagText(data.diagnoses || []) || order.diagName || '';
    var detail = '';
    if (isLab) {
      detail = '<div class="print-note">采集部位：' + esc(value(firstItem, ['collectionSite'], value(order, ['collectionSite'], ''))) + '　送检目的：' + esc(value(firstItem, ['inspectionPurpose'], value(order, ['inspectionPurpose'], ''))) + '　用药情况：' + esc(value(firstItem, ['medicationInfo'], value(order, ['medicationInfo'], ''))) + '</div>';
    } else {
      detail = '<div class="print-note">检查目的：' + esc(value(firstItem, ['examPurpose'], value(order, ['examPurpose'], ''))) + '　造影方式：' + esc(value(firstItem, ['contrastMode'], value(order, ['contrastMode'], ''))) + '<br>简要病史：' + esc(value(firstItem, ['briefHistory'], value(order, ['briefHistory'], ''))) + '<br>注意事项：' + esc(value(firstItem, ['examNotes'], value(order, ['examNotes'], '无'))) + '</div>';
    }
    var html = '<div class="print-sheet"><div class="print-header">' + esc(data.hospitalName || getHospitalName()) + '</div><div class="print-subheader">' + esc(title) + '</div>' +
      '<table class="print-meta"><tr><td>姓名：' + esc(value(patient, ['name', 'patientName'], order.patientName || '')) + '</td><td>性别：' + esc(gender(patient.gender)) + '</td><td>年龄：' + esc(patient.age || '') + '</td></tr>' +
      '<tr><td>门诊号：' + esc(value(patient, ['outpatientNo', 'iptOtpNo', 'regNo'], '')) + '</td><td>科别：' + esc(value(patient, ['deptName'], order.deptName || '')) + '</td><td>单据号：' + esc(order.orderNo || '') + '</td></tr>' +
      '<tr><td colspan="3">临床诊断：' + esc(diagnosis) + '</td></tr></table><div class="print-section">' + rows + detail + '</div>' +
      '<div class="print-footer"><span>申请医师：' + esc(order.drName || '________') + '</span><span>日期：' + esc(dateText(order.createTime) || new Date().toLocaleDateString()) + '</span></div></div>';
    return html;
  }
  HIS.printOrder = function (data) { data = data || {}; return openPrintWindow(data.order && data.order.orderType === '检验' ? '检验申请单' : '检查申请单', orderHtml(data)); };

  /* ===== P3-4 Tiptap 病历打印 ===== */
  /* 正文样式: 与 emr-editor 打印预览(PP_CSS)同语义, 限定 .emr-print-body 作用域避免污染打印窗口全局样式 */
  var TIPTAP_PRINT_CSS =
    '.emr-print-body p{margin:.5em 0;line-height:1.9}' +
    '.emr-print-body h1,.emr-print-body h2,.emr-print-body h3,.emr-print-body h4{margin:.8em 0 .4em}' +
    '.emr-print-body table{border-collapse:collapse;width:100%;margin:8px 0}' +
    '.emr-print-body td,.emr-print-body th{border:1px solid #000;padding:4px 8px}' +
    '.emr-print-body th{background:#f2f2f2}' +
    '.emr-print-body .emr-pp-sec-title{font-weight:bold;margin:12px 0 4px;font-size:15px}' +
    '.emr-print-body .emr-pp-field{border-bottom:1px solid #000;padding:0 6px}' +
    '.emr-print-body .emr-pp-macro{font-weight:600}' +
    '.emr-print-body .emr-pp-fragment{border:1px dashed #999;padding:6px 10px;margin:6px 0;color:#555}' +
    '.emr-print-body .emr-pp-drawing{text-align:center;margin:8px 0}.emr-print-body .emr-pp-drawing svg{max-width:100%;height:auto}' +
    '.emr-print-body .emr-pp-pagebreak{page-break-after:always;border-top:1px dashed #999;margin:24px 0 8px}' +
    '.emr-print-body ul,.emr-print-body ol{padding-left:1.6em}';

  /* Tiptap JSON → 打印 HTML: 优先编辑器引擎渲染(renderToHtml 前向兼容; 当前为 EmrPrintPreview.generateHTML),
   * 引擎缺失时纯文本兜底; 引擎抛错/解析失败返回空串, 由调用方回退 SOAP 行渲染 */
  function renderTiptapRecordHtml(content) {
    var json = content;
    if (typeof json === 'string') {
      try { json = JSON.parse(json); } catch (e) { return '<div class="record-row">' + nl2br(json) + '</div>'; }
    }
    if (!json || typeof json !== 'object') { return ''; }
    var editor = window.HIS && HIS.EmrEditor;
    try {
      if (editor && typeof editor.renderToHtml === 'function') {
        return editor.renderToHtml(json) || '';
      }
      if (editor && editor.EmrPrintPreview && typeof editor.EmrPrintPreview.generateHTML === 'function') {
        return editor.EmrPrintPreview.generateHTML(json, {}) || '';
      }
    } catch (e) {
      console.warn('Tiptap print render failed, falling back to SOAP', e);
      return '';
    }
    return plainTiptapHtml(json);
  }

  /* 引擎渲染器缺失时的极简兜底: 提取章节标题/段落/字段纯文本, 保证打印不空白 */
  function plainTiptapHtml(json) {
    function inlineText(nodes) {
      var s = '';
      (nodes || []).forEach(function (n) {
        if (!n) { return; }
        var a = n.attrs || {};
        if (n.type === 'text') { s += n.text || ''; return; }
        if (n.type === 'emrField') { s += (a.fieldName ? a.fieldName + '：' : '') + (a.value == null ? '' : a.value); return; }
        if (n.type === 'emrMacro') { s += a.resolvedValue || ('【' + (a.macroCode || '宏变量') + '】'); return; }
        if (n.type === 'hardBreak') { s += ' '; return; }
        s += inlineText(n.content);
      });
      return s;
    }
    var html = '';
    (function walk(nodes) {
      (nodes || []).forEach(function (n) {
        if (!n) { return; }
        var a = n.attrs || {};
        if (n.type === 'emrSection') {
          if (a.title || a.sectionKey) { html += '<div class="record-row"><span class="record-label">' + esc(a.title || a.sectionKey) + '</span></div>'; }
          walk(n.content);
          return;
        }
        if (n.type === 'paragraph' || n.type === 'heading') {
          var text = inlineText(n.content).trim();
          if (text) { html += '<div class="record-row">' + nl2br(text) + '</div>'; }
          return;
        }
        if (n.content) { walk(n.content); }
      });
    })(json.content || []);
    return html;
  }

  /* 门诊病历：仅打印当前有效病历内容，不展示任何修改痕迹。 */
  function recordHtml(data) {
    data = data || {};
    var visit = data.visit || {};
    var soap = data.soap || data.soapContent || visit;
    var patient = data.patient || {};
    /* P3-4 Tiptap 病历(emrFormat=1): 正文用 Tiptap JSON 渲染(打印页头/页脚版式复用); 失败回退既有 SOAP 行渲染 */
    var emrFormat = data.emrFormat != null ? data.emrFormat : visit.emrFormat;
    var tiptapContent = data.content != null ? data.content : visit.content;
    var tiptapHtml = '';
    if (Number(emrFormat) === 1 && tiptapContent) { tiptapHtml = renderTiptapRecordHtml(tiptapContent); }
    var rows;
    if (tiptapHtml) {
      rows = '<div class="emr-print-body">' + tiptapHtml + '</div>';
    } else {
      rows = [
        ['主诉', value(soap, ['chiefComplaint'], '')], ['现病史', value(soap, ['presentIllness'], '')],
        ['既往史', value(soap, ['pastHistory'], '')], ['体格检查', value(soap, ['physicalExam'], '')],
        ['辅助检查', value(soap, ['auxiliaryExam', 'auxExam'], '')], ['诊断', diagText(data.diagnoses || [])],
        ['治疗意见', value(soap, ['treatment', 'treatmentOpinion'], '')]
      ].map(function (row) { return '<div class="record-row"><span class="record-label">' + esc(row[0]) + '：</span>' + nl2br(row[1]) + '</div>'; }).join('');
    }
    var html = (tiptapHtml ? '<style>' + TIPTAP_PRINT_CSS + '</style>' : '') +
      '<div class="print-sheet"><div class="print-header">' + esc(data.hospitalName || getHospitalName()) + '</div><div class="print-subheader">门 诊 病 历</div>' +
      '<table class="print-meta"><tr><td>姓名：' + esc(value(patient, ['name', 'patientName'], visit.patientName || '')) + '</td><td>性别：' + esc(gender(value(patient, ['gender'], visit.gender))) + '</td><td>年龄：' + esc(value(patient, ['age'], visit.age || '')) + '</td></tr>' +
      '<tr><td>门诊号：' + esc(value(patient, ['outpatientNo', 'iptOtpNo', 'regNo'], visit.iptOtpNo || visit.regNo || '')) + '</td><td>科别：' + esc(value(patient, ['deptName'], visit.deptName || '')) + '</td><td>就诊时间：' + esc(dateText(value(visit, ['visitTime', 'startTime', 'createTime'], ''))) + '</td></tr>' +
      '<tr><td colspan="3">过敏史：' + esc(value(patient, ['allergyHistory'], soap.allergyHistory || '无')) + '</td></tr></table><div class="print-section">' + rows + '</div>' +
      '<div class="print-footer"><span>医师签名：' + esc(visit.drName || '________') + '</span><span>日期：' + new Date().toLocaleDateString() + '</span></div></div>';
    return html;
  }
  HIS.printRecord = function (data) { return openPrintWindow('门诊病历', recordHtml(data || {})); };

  /* 住院证(规范版式): 前记(患者身份/医保/住址) + 病情摘要(就诊SOAP) + 入院决定 + 后记(签名盖章/入院须知) */
  function admissionHtml(data) {
    data = data || {};
    var cert = data.cert || data.admission || {};
    var patient = data.patient || {};
    var visit = data.visit || {};
    var soap = data.soap || visit;
    var certNo = 'ZY' + String(cert.applyTime || dateText(new Date().toISOString())).replace(/[-: T]/g, '').slice(0, 8) + '-' + String(cert.id || '');
    var urgency = Number(cert.urgency) === 3 ? 'Ⅲ级 危急' : (Number(cert.urgency) === 2 ? 'Ⅱ级 急' : 'Ⅳ级 普通');
    var row = function (label, v) {
      return '<tr><td class="ac-label">' + esc(label) + '</td><td colspan="3" class="ac-text">' + (nl2br(v) || '<span class="ac-empty">未记录</span>') + '</td></tr>';
    };
    var cell = function (label, v) {
      return '<td class="ac-label">' + esc(label) + '</td><td>' + (esc(v) || '<span class="ac-empty">—</span>') + '</td>';
    };
    var ageV = value(patient, ['age'], visit.age || '');
    var addr = value(patient, ['presentDetail'], '') || value(patient, ['address'], '') || value(patient, ['householdAddr'], '');
    if (!addr) {
      addr = [value(patient, ['presentProvName'], ''), value(patient, ['presentCityName'], ''), value(patient, ['presentCountyName'], ''), value(patient, ['presentTownName'], '')].filter(Boolean).join('');
    }
    var allergy = value(soap, ['allergyHistory'], '');
    var html = '<style>' +
      '.ac-table{width:100%;border-collapse:collapse;margin:6px 0 10px}.ac-table td{border:1px solid #333;padding:6px 8px;font-size:12px;line-height:1.5;text-align:left;background:transparent}' +
      '.ac-table .ac-label{background:#f3f4f6;font-weight:bold;white-space:nowrap;width:76px}.ac-table .ac-text{min-height:20px}' +
      '.ac-empty{color:#999}.ac-title{display:flex;align-items:baseline;justify-content:space-between;border-bottom:2px solid #222;padding-bottom:2px;margin:10px 0 4px}' +
      '.ac-diag td{font-size:14px;font-weight:bold}' +
      '.ac-notice{margin-top:10px;padding:8px 10px;border:1px dashed #666;font-size:11px;color:#333;line-height:1.8}' +
      '.ac-sign{display:flex;justify-content:flex-end;gap:36px;margin-top:14px;font-size:13px}' +
      '.ac-stamp{display:inline-block;border:1px solid #bbb;color:#999;padding:10px 16px;border-radius:50%;font-size:11px;transform:rotate(-8deg);margin-top:8px}' +
      '</style>' +
      '<div class="print-sheet" style="max-width:800px">' +
      '<div class="print-header">' + esc(data.hospitalName || getHospitalName()) + '</div>' +
      '<div class="print-subheader" style="letter-spacing:12px">住 院 证</div>' +
      '<div class="ac-title"><span style="font-size:12px;color:#555">编号：' + esc(certNo) + '</span><span style="font-size:12px">开证日期：' + esc(dateText(cert.applyTime).slice(0, 10) || new Date().toLocaleDateString()) + '</span></div>' +
      '<table class="ac-table"><tr>' + cell('姓名', value(patient, ['name'], cert.patientName)) + cell('性别', value(patient, ['genderName'], gender(patient.gender))) + cell('年龄', ageV ? ageV + '岁' : '') + cell('费别', value(patient, ['insutypeName', 'insutype'], '自费')) + '</tr>' +
      '<tr>' + cell('门诊号', value(visit, ['iptOtpNo'], value(patient, ['patientNo'], ''))) + cell('医保个人号', value(patient, ['psnNo'], '')) + cell('身份证号', value(patient, ['idCard'], '')) + cell('联系电话', value(patient, ['phone'], '')) + '</tr>' +
      '<tr><td class="ac-label">现住址</td><td colspan="3">' + (esc(addr) || '<span class="ac-empty">—</span>') + '</td></tr></table>' +
      '<div class="ac-title"><b style="font-size:14px">病情摘要</b></div>' +
      '<table class="ac-table">' +
      row('主诉', value(soap, ['chiefComplaint'], '')) +
      row('现病史', value(soap, ['presentIllness'], cert.conditionSummary)) +
      row('既往史', value(soap, ['pastHistory'], '') + (allergy ? '　过敏史：' + allergy : '')) +
      row('体格检查', value(soap, ['physicalExam'], '')) +
      row('辅助检查', value(soap, ['auxExam', 'auxiliaryExam'], '')) +
      '</table>' +
      '<div class="ac-title"><b style="font-size:14px">入院决定</b></div>' +
      '<table class="ac-table">' +
      '<tr class="ac-diag"><td class="ac-label">入院诊断</td><td colspan="3">' + esc(cert.admitDiagnosis || '') + '</td></tr>' +
      row('入院目的', value(cert, ['admitPurpose'], '进一步诊断治疗')) +
      '<tr><td class="ac-label">拟收科室</td><td style="font-weight:bold">' + esc(cert.admitDeptName || '') + '</td><td class="ac-label">紧急程度</td><td>' + esc(urgency) + '</td></tr>' +
      '</table>' +
      '<div class="ac-sign"><span>开证医师（签名）：' + esc(cert.applyDrName || '________') + '</span><span>科主任（签名）：________</span></div>' +
      '<div style="text-align:right"><span class="ac-stamp">（诊断专用章）</span></div>' +
      '<div class="ac-notice"><b>入院须知：</b><br>1. 请持本证、医保卡/身份证及门诊病历资料，到住院处办理入院登记（持证可自动预填入院信息）；<br>' +
      '2. 本证自开具之日起 7 日内有效，逾期需重新评估；危急患者请直接联系拟收科室做好接诊准备；<br>' +
      '3. 入院请携带生活必需品及既往检查报告；医保患者请确认参保状态正常；<br>4. 本证由接诊医师填写并签名，涂改无效，加盖医院章后生效。</div>' +
      '<div class="print-note" style="margin-top:8px">' + esc(visit.deptName || '') + ' · ' + esc(cert.applyDrName || '') + '　打印时间：' + esc(dateText(new Date().toISOString())) + '</div>' +
      '</div>';
    return html;
  }
  HIS.printAdmissionCert = function (data) { return openPrintWindow('住院证', admissionHtml(data || {})); };

  /* 住院证、诊断证明等证明类单据。 */
  function certHtml(data) {
    data = data || {};
    var cert = data.cert || data.admission || data;
    var patient = data.patient || {};
    var type = data.certTypeName || (cert.admitDiagnosis != null ? '住院证' : ({ 1: '诊断证明书', 2: '病假证明', 3: '转诊证明' }[cert.certType] || data.certType || '诊断证明书'));
    if (type === '住院证') { return admissionHtml(data); }
    var content = cert.certContent || cert.content || '';
    var html = '<div class="print-sheet"><div class="print-header">' + esc(data.hospitalName || getHospitalName()) + '</div><div class="print-subheader">' + esc(type) + '</div>' +
      '<table class="print-meta"><tr><td>姓名：' + esc(value(patient, ['name', 'patientName'], cert.patientName || '')) + '</td><td>性别：' + esc(gender(patient.gender)) + '</td><td>年龄：' + esc(patient.age || '') + '</td></tr>' +
      '<tr><td colspan="3">诊断：' + esc(cert.diagnosis || cert.admitDiagnosis || '') + '</td></tr></table><div class="print-content">' + nl2br(content) + '</div>' +
      '<div class="print-footer"><span>医师签名：' + esc(cert.issueDrName || cert.applyDrName || '________') + '</span><span>日期：' + esc(dateText(cert.issueTime || cert.applyTime) || new Date().toLocaleDateString()) + '</span><span>（盖章）</span></div></div>';
    return html;
  }
  HIS.printCert = function (data) {
    data = data || {};
    var cert = data.cert || data.admission || data;
    var type = data.certTypeName || (cert.admitDiagnosis != null ? '住院证' : ({ 1: '诊断证明书', 2: '病假证明', 3: '转诊证明' }[cert.certType] || data.certType || '诊断证明书'));
    return openPrintWindow(type, certHtml(data));
  };

  /* OP-D 14.7: 单据 HTML 构建器对外暴露, 供打印中心一键全打聚合复用 */
  HIS.buildDocHtml = { rx: rxHtml, order: orderHtml, record: recordHtml, cert: certHtml, admission: admissionHtml };
})();
