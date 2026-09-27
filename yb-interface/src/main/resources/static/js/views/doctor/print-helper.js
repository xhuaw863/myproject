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

  /* 处方笺：按 groupNo 分组，呈现前记、正文 Rp 和后记。 */
  HIS.printRx = function (data) {
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
    return openPrintWindow('处方笺', html);
  };

  /* 检查、检验申请单。结构化字段优先取明细，兼容主表字段。 */
  HIS.printOrder = function (data) {
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
    return openPrintWindow(title, html);
  };

  /* 门诊病历：仅打印当前有效病历内容，不展示任何修改痕迹。 */
  HIS.printRecord = function (data) {
    data = data || {};
    var visit = data.visit || {};
    var soap = data.soap || data.soapContent || visit;
    var patient = data.patient || {};
    var rows = [
      ['主诉', value(soap, ['chiefComplaint'], '')], ['现病史', value(soap, ['presentIllness'], '')],
      ['既往史', value(soap, ['pastHistory'], '')], ['体格检查', value(soap, ['physicalExam'], '')],
      ['辅助检查', value(soap, ['auxiliaryExam', 'auxExam'], '')], ['诊断', diagText(data.diagnoses || [])],
      ['治疗意见', value(soap, ['treatment', 'treatmentOpinion'], '')]
    ].map(function (row) { return '<div class="record-row"><span class="record-label">' + esc(row[0]) + '：</span>' + nl2br(row[1]) + '</div>'; }).join('');
    var html = '<div class="print-sheet"><div class="print-header">' + esc(data.hospitalName || getHospitalName()) + '</div><div class="print-subheader">门 诊 病 历</div>' +
      '<table class="print-meta"><tr><td>姓名：' + esc(value(patient, ['name', 'patientName'], visit.patientName || '')) + '</td><td>性别：' + esc(gender(value(patient, ['gender'], visit.gender))) + '</td><td>年龄：' + esc(value(patient, ['age'], visit.age || '')) + '</td></tr>' +
      '<tr><td>门诊号：' + esc(value(patient, ['outpatientNo', 'iptOtpNo', 'regNo'], visit.iptOtpNo || visit.regNo || '')) + '</td><td>科别：' + esc(value(patient, ['deptName'], visit.deptName || '')) + '</td><td>就诊时间：' + esc(dateText(value(visit, ['visitTime', 'startTime', 'createTime'], ''))) + '</td></tr>' +
      '<tr><td colspan="3">过敏史：' + esc(value(patient, ['allergyHistory'], soap.allergyHistory || '无')) + '</td></tr></table><div class="print-section">' + rows + '</div>' +
      '<div class="print-footer"><span>医师签名：' + esc(visit.drName || '________') + '</span><span>日期：' + new Date().toLocaleDateString() + '</span></div></div>';
    return openPrintWindow('门诊病历', html);
  };

  /* 住院证、诊断证明等证明类单据。 */
  HIS.printCert = function (data) {
    data = data || {};
    var cert = data.cert || data.admission || data;
    var patient = data.patient || {};
    var type = data.certTypeName || (cert.admitDiagnosis != null ? '住院证' : ({ 1: '诊断证明书', 2: '病假证明', 3: '转诊证明' }[cert.certType] || data.certType || '诊断证明书'));
    var content = cert.certContent || cert.content || '';
    if (type === '住院证') {
      content = '入院诊断：' + (cert.admitDiagnosis || '') + '\n病情摘要：' + (cert.conditionSummary || '') + '\n入院目的：' + (cert.admitPurpose || '') + '\n拟收科室：' + (cert.admitDeptName || '');
    }
    var html = '<div class="print-sheet"><div class="print-header">' + esc(data.hospitalName || getHospitalName()) + '</div><div class="print-subheader">' + esc(type) + '</div>' +
      '<table class="print-meta"><tr><td>姓名：' + esc(value(patient, ['name', 'patientName'], cert.patientName || '')) + '</td><td>性别：' + esc(gender(patient.gender)) + '</td><td>年龄：' + esc(patient.age || '') + '</td></tr>' +
      '<tr><td colspan="3">诊断：' + esc(cert.diagnosis || cert.admitDiagnosis || '') + '</td></tr></table><div class="print-content">' + nl2br(content) + '</div>' +
      '<div class="print-footer"><span>医师签名：' + esc(cert.issueDrName || cert.applyDrName || '________') + '</span><span>日期：' + esc(dateText(cert.issueTime || cert.applyTime) || new Date().toLocaleDateString()) + '</span><span>（盖章）</span></div></div>';
    return openPrintWindow(type, html);
  };
})();
