/* 简易HIS - 医保接口对接验证台 前端逻辑 */
'use strict';

const state = {
  opMdtrtId: '', opSetlId: '', opChrgBchno: '',
  ipMdtrtId: '', ipSetlId: '',
  opFee: [], ipFee: []
};

const $ = id => document.getElementById(id);
const val = id => $(id).value.trim();
/* HTML转义, 防止插值数据被解析为标签 */
const esc = s => String(s === null || s === undefined ? '' : s)
  .replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const now = () => new Date().toLocaleTimeString('zh-CN', { hour12: false });
const nowFull = () => {
  const d = new Date(), p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};

/* ---------- 通用请求 ---------- */
async function api(url, method, body) {
  const headers = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('yb_his_token');
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const opt = { method: method || 'GET', headers };
  if (body !== undefined) opt.body = JSON.stringify(body);
  const resp = await fetch(url, opt);
  const data = await resp.json();
  if (data && data.code === 401) {
    alert('未登录或登录已过期, 请先在 HIS 主系统登录');
    throw new Error('unauthorized');
  }
  return data;
}

function showResult(id, resp, okText) {
  const el = $(id);
  if (resp.infcode === '0') {
    el.className = 'result ok';
    el.textContent = '✓ ' + (okText || '交易成功') + (resp.errMsg ? ' | ' + resp.errMsg : '');
  } else {
    el.className = 'result err';
    el.textContent = '✗ 交易失败: ' + (resp.errMsg || resp.infcode);
  }
}

function logCall(infno, name, resp) {
  const item = document.createElement('div');
  item.className = 'log-item';
  const ok = resp.infcode === '0';
  item.innerHTML =
    `<div class="row"><span class="t">${now()}</span><span class="code ${ok ? 'ok' : 'err'}">[${ok ? '成功' : '失败'}]</span>` +
    `<b>${esc(infno)} ${esc(name)}</b><span class="t">${esc(resp.errMsg)}</span></div>` +
    `<pre>===== 请求报文 =====\n${esc(fmt(resp.requestJson))}\n\n===== 响应报文 =====\n${esc(fmt(resp.rawJson))}</pre>`;
  item.querySelector('.row').onclick = () => item.classList.toggle('open');
  $('log-body').prepend(item);
}
function fmt(json) {
  if (!json) return '(无)';
  try { return JSON.stringify(JSON.parse(json), null, 2); } catch (e) { return json; }
}
function clearLog() { $('log-body').innerHTML = ''; }

function outputObj(resp) {
  try { return JSON.parse(resp.output || '{}'); } catch (e) { return {}; }
}

/* ---------- 页签切换 ---------- */
document.querySelectorAll('nav button').forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll('nav button').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-page').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    $(btn.dataset.tab).classList.add('active');
  };
});

/* ---------- 初始化 ---------- */
(async function init() {
  const info = await api('/api/system/info');
  const badge = $('badge-mode');
  badge.textContent = info.mockEnabled ? '模拟平台模式' : '真实平台模式';
  badge.className = 'badge ' + (info.mockEnabled ? 'mock' : 'real');
  $('badge-fix').textContent = info.fixmedinsCode + ' ' + info.fixmedinsName;
  // 预生成院内流水号
  $('op-iptotpno').value = 'OTP' + Date.now();
  $('op-bchno').value = 'B' + Date.now();
  $('ip-iptno').value = 'IPT' + Date.now();
  addOpFeeRow(); addIpFeeRow();
  loadDictVersions();
})();

/* ================= 字典管理 ================= */
async function dlDict(type) {
  const resp = await api('/api/dict/' + type);
  showResult('dict-result', resp, '字典下载完成');
  logCall('13xx', '字典下载[' + type + ']', resp);
  loadDictVersions();
}
async function dlDictAll() {
  const resp = await api('/api/dict/all');
  const el = $('dict-result');
  el.className = 'result ok';
  el.textContent = '✓ 全部字典下载任务已执行, 详见报文日志';
  Object.entries(resp).forEach(([k, v]) => logCall(k, '字典下载', v));
  loadDictVersions();
}
async function loadDictVersions() {
  const list = await api('/api/query/dict-versions');
  const html = list.map(r => `<tr><td>${esc(r.dictType)}</td><td>${esc(r.dictName)}</td><td>${esc(r.infno)}</td><td>${esc(r.maxVer)}</td><td>${esc(r.lastDldTime) || '-'}</td></tr>`).join('');
  document.querySelectorAll('#dict-ver-table tbody, #dict-ver-table2 tbody').forEach(tb => tb.innerHTML = html);
}

/* ================= 费用明细编辑 ================= */
function feeRowHtml(prefix, row, idx) {
  return `<tr data-idx="${idx}">
    <td>${esc(row.feedetlSn)}</td>
    <td><input value="${esc(row.medListCodg)}" onchange="updFee('${prefix}',${idx},'medListCodg',this.value)"></td>
    <td><input value="${esc(row.medinsListCodg)}" onchange="updFee('${prefix}',${idx},'medinsListCodg',this.value)"></td>
    <td><input type="number" value="${esc(row.cnt)}" onchange="updFee('${prefix}',${idx},'cnt',this.value)"></td>
    <td><input type="number" value="${esc(row.pric)}" onchange="updFee('${prefix}',${idx},'pric',this.value)"></td>
    <td>${(row.cnt * row.pric).toFixed(2)}</td>
    <td><button class="act danger" onclick="delFee('${prefix}',${idx})">删</button></td></tr>`;
}
function renderFee(prefix) {
  const list = prefix === 'op' ? state.opFee : state.ipFee;
  $(prefix + '-fee-body').innerHTML = list.map((r, i) => feeRowHtml(prefix, r, i)).join('');
  const total = list.reduce((s, r) => s + r.cnt * r.pric, 0);
  $(prefix + '-fee-total').textContent = total.toFixed(2);
  $(prefix + '-medfee').value = total.toFixed(2);
}
function newFeeRow(prefix, medcodgId) {
  const list = prefix === 'op' ? state.opFee : state.ipFee;
  return {
    feedetlSn: 'F' + Date.now() + list.length,
    medListCodg: val(medcodgId), medinsListCodg: 'HIS' + String(list.length + 1).padStart(4, '0'),
    cnt: parseFloat(val(prefix + '-new-cnt')) || 1, pric: parseFloat(val(prefix + '-new-pric')) || 0
  };
}
function addOpFeeRow() { state.opFee.push(newFeeRow('op', 'op-new-medcodg')); renderFee('op'); }
function addIpFeeRow() { state.ipFee.push(newFeeRow('ip', 'ip-new-medcodg')); renderFee('ip'); }
function delFee(prefix, idx) {
  const list = prefix === 'op' ? state.opFee : state.ipFee;
  list.splice(idx, 1); renderFee(prefix);
}
function updFee(prefix, idx, field, v) {
  const list = prefix === 'op' ? state.opFee : state.ipFee;
  list[idx][field] = (field === 'cnt' || field === 'pric') ? parseFloat(v) || 0 : v;
  renderFee(prefix);
}

/* ================= 门诊业务 ================= */
async function opRegister() {
  const body = {
    psnNo: val('op-psnno'), insutype: val('op-insutype'), begntime: nowFull(),
    mdtrtCertType: val('op-certtype'), mdtrtCertNo: val('op-certno'),
    iptOtpNo: val('op-iptotpno'), deptCode: val('op-deptcode'), deptName: val('op-deptname'),
    caty: val('op-caty'), medType: val('op-medtype')
  };
  const resp = await api('/api/outpatient/register', 'POST', body);
  showResult('op-reg-result', resp, '挂号成功');
  logCall('2201', '门诊挂号', resp);
  if (resp.infcode === '0') {
    const out = outputObj(resp);
    state.opMdtrtId = (out.data || {}).mdtrt_id || '';
    $('op-mdtrtid').value = state.opMdtrtId;
  }
}
async function opVisitInfo() {
  const body = {
    mdtrtinfo: {
      mdtrtId: state.opMdtrtId, psnNo: val('op-psnno'), medType: val('op-medtype'),
      begntime: nowFull(), mainCondDscr: '咳嗽、发热两天'
    },
    diseinfo: [{
      diagType: '1', diagSrtNo: 1, diagCode: val('op-diagcode'), diagName: val('op-diagname'),
      diagDept: val('op-deptname'), diseDorNo: 'DOC001', diseDorName: '张医生',
      diagTime: nowFull(), valiFlag: '1', maindiagFlag: '1'
    }]
  };
  const resp = await api('/api/outpatient/visit-info', 'POST', body);
  showResult('op-visit-result', resp, '就诊信息上传成功');
  logCall('2203', '门诊就诊信息上传', resp);
}
async function opFeeUpload() {
  const body = state.opFee.map(r => ({
    feedetlSn: r.feedetlSn, mdtrtId: state.opMdtrtId, psnNo: val('op-psnno'),
    chrgBchno: val('op-bchno'), feeOcurTime: nowFull(), medListCodg: r.medListCodg,
    medinsListCodg: r.medinsListCodg, detItemFeeSumamt: (r.cnt * r.pric).toFixed(2),
    cnt: r.cnt, pric: r.pric, rxCircFlag: '0', bilgDeptCodg: val('op-deptcode'),
    bilgDeptName: val('op-deptname'), bilgDrCodg: 'DOC001', bilgDrName: '张医生', hospApprFlag: '1'
  }));
  const resp = await api('/api/outpatient/fee-detail', 'POST', body);
  showResult('op-fee-result', resp, '费用明细上传成功');
  logCall('2204', '门诊费用明细上传', resp);
}
function opSetlBody(infno) {
  return {
    psnNo: val('op-psnno'), mdtrtCertType: val('op-certtype'), mdtrtCertNo: val('op-certno'),
    medType: val('op-medtype'), medfeeSumamt: val('op-medfee'), psnSetlway: val('op-setlway'),
    mdtrtId: state.opMdtrtId, chrgBchno: val('op-bchno'), acctUsedFlag: val('op-acctflag'),
    insutype: val('op-insutype'), invono: val('op-invono')
  };
}
async function opPreSetl() {
  const resp = await api('/api/outpatient/pre-settlement', 'POST', opSetlBody('2206'));
  showResult('op-setl-result', resp, '预结算成功');
  logCall('2206', '门诊预结算', resp);
}
async function opSetl() {
  const resp = await api('/api/outpatient/settlement', 'POST', opSetlBody('2207'));
  showResult('op-setl-result', resp, '结算成功, 结算记录已留存');
  logCall('2207', '门诊结算', resp);
  if (resp.infcode === '0') {
    state.opSetlId = (outputObj(resp).setlinfo || {}).setl_id || '';
    loadSetlRecords();
  }
}
async function opCancel() {
  const resp = await api('/api/outpatient/settlement-cancel', 'POST',
    { setlId: state.opSetlId, mdtrtId: state.opMdtrtId, psnNo: val('op-psnno') });
  showResult('op-setl-result', resp, '结算撤销成功');
  logCall('2208', '门诊结算撤销', resp);
  loadSetlRecords();
}

/* ================= 住院业务 ================= */
async function ipAdmission() {
  const body = {
    mdtrtinfo: {
      psnNo: val('ip-psnno'), insutype: val('ip-insutype'), begntime: nowFull(),
      mdtrtCertType: '02', mdtrtCertNo: '420106198505054321', medType: val('ip-medtype'),
      iptNo: val('ip-iptno'), atddrNo: val('ip-drcode'), chfpdrName: val('ip-drname'),
      admDiagDscr: val('ip-diagname'), admDeptCodg: val('ip-deptcodg'), admDeptName: val('ip-deptname'),
      admBed: val('ip-bed'), dscgMaindiagCode: val('ip-diagcode'), dscgMaindiagName: val('ip-diagname'),
      insuplcAdmdvs: '420100', mdtrtareaAdmvs: '420100'
    },
    diseinfo: [{
      psnNo: val('ip-psnno'), diagType: '1', maindiagFlag: '1', diagSrtNo: 1,
      diagCode: val('ip-diagcode'), diagName: val('ip-diagname'), diagDept: val('ip-deptname'),
      diseDorNo: val('ip-drcode'), diseDorName: val('ip-drname'), diagTime: nowFull()
    }]
  };
  const resp = await api('/api/inpatient/admission', 'POST', body);
  showResult('ip-adm-result', resp, '入院登记成功');
  logCall('2401', '入院办理', resp);
  if (resp.infcode === '0') {
    const out = outputObj(resp);
    state.ipMdtrtId = (out.result || {}).mdtrt_id || '';
    $('ip-mdtrtid').value = state.ipMdtrtId;
  }
}
async function ipFeeUpload() {
  const body = state.ipFee.map(r => ({
    feedetlSn: r.feedetlSn, mdtrtId: state.ipMdtrtId, psnNo: val('ip-psnno'),
    medType: val('ip-medtype'), feeOcurTime: nowFull(), medListCodg: r.medListCodg,
    medinsListCodg: r.medinsListCodg, detItemFeeSumamt: (r.cnt * r.pric).toFixed(2),
    cnt: r.cnt, pric: r.pric, bilgDeptCodg: val('ip-deptcodg'), bilgDeptName: val('ip-deptname'),
    bilgDrCodg: val('ip-drcode'), bilgDrName: val('ip-drname'), hospApprFlag: '1'
  }));
  const resp = await api('/api/inpatient/fee-detail', 'POST', body);
  showResult('ip-fee-result', resp, '住院费用明细上传成功');
  logCall('2301', '住院费用明细上传', resp);
}
function ipSetlBody() {
  return {
    psnNo: val('ip-psnno'), mdtrtCertType: '02', mdtrtCertNo: '420106198505054321',
    medType: val('ip-medtype'), medfeeSumamt: val('ip-medfee'), psnSetlway: val('ip-setlway'),
    mdtrtId: state.ipMdtrtId, acctUsedFlag: val('ip-acctflag'),
    insutype: val('ip-insutype'), invono: val('ip-invono')
  };
}
async function ipPreSetl() {
  const resp = await api('/api/inpatient/pre-settlement', 'POST', ipSetlBody());
  showResult('ip-setl-result', resp, '住院预结算成功');
  logCall('2303', '住院预结算', resp);
}
async function ipSetl() {
  const resp = await api('/api/inpatient/settlement', 'POST', ipSetlBody());
  showResult('ip-setl-result', resp, '住院结算成功, 结算记录已留存');
  logCall('2304', '住院结算', resp);
  if (resp.infcode === '0') {
    state.ipSetlId = (outputObj(resp).setlinfo || {}).setl_id || '';
    loadSetlRecords();
  }
}
async function ipCancel() {
  const resp = await api('/api/inpatient/settlement-cancel', 'POST',
    { setlId: state.ipSetlId, mdtrtId: state.ipMdtrtId, psnNo: val('ip-psnno') });
  showResult('ip-setl-result', resp, '住院结算撤销成功');
  logCall('2305', '住院结算撤销', resp);
  loadSetlRecords();
}
async function ipDischarge() {
  const body = {
    dscginfo: {
      mdtrtId: state.ipMdtrtId, psnNo: val('ip-psnno'), insutype: val('ip-insutype'),
      endtime: nowFull(), dscgDeptCodg: val('ip-dscgdept'), dscgDeptName: val('ip-dscgdeptname'),
      dscgWay: val('ip-dscgway'), copFlag: '0'
    },
    diseinfo: [{
      mdtrtId: state.ipMdtrtId, psnNo: val('ip-psnno'), diagType: '1', maindiagFlag: '1',
      diagSrtNo: 1, diagCode: val('ip-diagcode'), diagName: val('ip-diagname'),
      diagDept: val('ip-dscgdeptname'), diseDorNo: val('ip-drcode'),
      diseDorName: val('ip-drname'), diagTime: nowFull()
    }]
  };
  const resp = await api('/api/inpatient/discharge', 'POST', body);
  showResult('ip-dscg-result', resp, '出院登记成功');
  logCall('2402', '出院办理', resp);
}

/* ================= 数据验证 ================= */
async function loadSetlRecords() {
  const list = await api('/api/query/setl-records');
  $('setl-table').querySelector('tbody').innerHTML = list.map(r =>
    `<tr><td>${esc(r.id)}</td><td>${esc(r.setlId) || '-'}</td><td>${esc(r.mdtrtId) || '-'}</td><td>${esc(r.psnName) || '-'}(${esc(r.psnNo) || '-'})</td>` +
    `<td>${esc(r.bizType)}</td><td>${esc(r.infno)}</td><td>${esc(r.medfeeSumamt)}</td><td>${esc(r.fundPaySumamt)}</td>` +
    `<td>${esc(r.psnCashPay)}</td><td>${r.status === '1' ? '已结算' : '已撤销'}</td><td>${esc(r.setlTime) || '-'}</td></tr>`).join('')
    || '<tr><td colspan="11">暂无记录</td></tr>';
}
