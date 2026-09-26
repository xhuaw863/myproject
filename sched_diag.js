/* 快速诊断: 排班/模板/医师接口的原始响应 */
const http = require('http');
function api(method, p, body, token) {
  return new Promise((res, rej) => {
    const data = body ? JSON.stringify(body) : null;
    const r = http.request({ host: 'localhost', port: 8080, path: p, method,
      headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}) },
      resp => { let s = ''; resp.setEncoding('utf8'); resp.on('data', c => s += c); resp.on('end', () => res({ status: resp.statusCode, body: s })); });
    r.on('error', rej); if (data) r.write(data); r.end();
  });
}
(async () => {
  const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const token = JSON.parse(lg.body).data.token;
  console.log('--- 1. staff/list 原始响应(前600) ---');
  const st = await api('GET', '/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&page=1&size=5', null, token);
  console.log(st.body.slice(0, 600));
  const stData = JSON.parse(st.body).data;
  const recs = Array.isArray(stData) ? stData : ((stData && stData.records) || []);
  console.log('data is ' + (Array.isArray(stData) ? 'ARRAY len=' + stData.length : 'OBJECT keys=' + Object.keys(stData || {}).join(',')));
  console.log('records.length=' + recs.length + ', recs[0] keys=' + (recs[0] ? Object.keys(recs[0]).join(',') : 'NULL'));
  const s1 = recs[0];
  if (!s1) { console.log('!! staff[0] 为空, 终止'); return; }
  console.log('s1: id=' + s1.id + ' staffName=' + s1.staffName + ' deptId=' + s1.deptId + ' deptName=' + s1.deptName);
  console.log('\n--- 2. POST /api/his/schedule 原始响应 ---');
  const post = await api('POST', '/api/his/schedule', { deptId: s1.deptId, staffId: s1.id, workDate: '2026-09-24', timeType: 'am',
    regLevelCode: '01', regLevelName: '普通号', regFee: 10, totalNum: 30, status: 1, room: 'E2E诊室', stopReason: '' }, token);
  console.log('HTTP ' + post.status + ': ' + post.body.slice(0, 400));
  console.log('\n--- 3. schedule/list 原始响应(前800) ---');
  const lst = await api('GET', '/api/his/schedule/list?from=2026-09-21&to=2026-09-27&page=1&size=50', null, token);
  console.log(lst.body.slice(0, 800));
  const lstData = JSON.parse(lst.body).data;
  console.log('list data is ' + (Array.isArray(lstData) ? 'ARRAY len=' + lstData.length : 'OBJECT keys=' + Object.keys(lstData || {}).join(',')));
  if (Array.isArray(lstData) && lstData.length) console.log('list[0] keys=' + Object.keys(lstData[0]).join(','));
  console.log('\n--- 4. template/list 原始响应(前600) ---');
  const tl = await api('GET', '/api/his/schedule/template/list?page=1&size=10', null, token);
  console.log(tl.body.slice(0, 600));
  const tlData = JSON.parse(tl.body).data;
  console.log('template data is ' + (Array.isArray(tlData) ? 'ARRAY len=' + tlData.length : 'OBJECT keys=' + Object.keys(tlData || {}).join(',')));
  console.log('\n--- 5. week 原始响应(前700) ---');
  const wk = await api('GET', '/api/his/schedule/week?weekStart=2026-09-21', null, token);
  console.log(wk.body.slice(0, 700));
  const wkData = JSON.parse(wk.body).data;
  console.log('week data is ' + (Array.isArray(wkData) ? 'ARRAY len=' + wkData.length : 'OBJECT keys=' + Object.keys(wkData || {}).join(',')));
  console.log('\n--- 6. stats 原始响应(前400) ---');
  const sta = await api('GET', '/api/his/schedule/stats?date=2026-09-21', null, token);
  console.log(sta.body.slice(0, 400));
})().catch(e => { console.error('FATAL:', e.message); process.exit(2); });
