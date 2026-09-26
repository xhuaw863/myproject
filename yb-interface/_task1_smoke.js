/* Task #1 重构后冒烟: calcDiscount 抽取后的 register/cancel/reRegister 回归
 * 用患者40(龚华 79岁) + 排班169(09-26 dept2 am) — 无历史记录的干净组合
 * 运行: node _task1_smoke.js */
const http = require('http');

const results = [];
const pass = m => { results.push(['PASS', m]); console.log('  [PASS]', m); };
const fail = (m, e) => { const d = e ? ' :: ' + (e.message || e).toString().split('\n')[0] : ''; results.push(['FAIL', m + d]); console.log('  [FAIL]', m + d); };

function api(method, p, body, token) {
  return new Promise((res, rej) => {
    const data = body ? JSON.stringify(body) : null;
    const r = http.request({ host: 'localhost', port: 8080, path: p, method,
      headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}) },
      resp => { let s = ''; resp.setEncoding('utf8'); resp.on('data', c => s += c); resp.on('end', () => { try { res(JSON.parse(s)); } catch (e) { rej(new Error('bad json: ' + s.slice(0, 160))); } }); });
    r.on('error', rej); if (data) r.write(data); r.end();
  });
}

(async () => {
  const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const tok = lg.data.token;
  if (!tok) throw new Error('登录失败');
  pass('登录成功');

  // 1. 挂号: 79岁患者自动 age70free, 09-26 dept2 am 已有 0001/0002 → 新号应为 0003
  const r1 = await api('POST', '/api/his/registration/register?patientId=40&scheduleId=169&payMethod=free', null, tok);
  if (r1.code !== 0) throw new Error('挂号失败: ' + JSON.stringify(r1).slice(0, 220));
  const d1 = r1.data;
  if (d1.discountType === 'age70free' && Number(d1.discountAmount) === 10 && Number(d1.actualFee) === 0) pass('calcDiscount: 自动 age70free 全免(actualFee=0)');
  else fail('减免异常', JSON.stringify({ dt: d1.discountType, af: d1.actualFee }));
  if (d1.queueNo === 'WK-0003') pass('候诊序号递增正确: ' + d1.queueNo);
  else fail('序号异常: ' + d1.queueNo);

  // 2. 退号 free → needRefund=false
  const r2 = await api('POST', '/api/his/registration/cancel?id=' + d1.id + '&reason=' + encodeURIComponent('冒烟测试'), null, tok);
  if (r2.code === 0 && r2.data.needRefund === false) pass('退号: free 不提示退款');
  else fail('退号异常', JSON.stringify(r2).slice(0, 200));

  // 3. 重挂: 沿用 age70free, 序号 0004
  const r3 = await api('POST', '/api/his/registration/reRegister?id=' + d1.id, null, tok);
  if (r3.code !== 0) throw new Error('重挂失败: ' + JSON.stringify(r3).slice(0, 220));
  const d3 = r3.data;
  if (d3.discountType === 'age70free' && Number(d3.actualFee) === 0 && d3.queueNo === 'WK-0004') pass('重挂: age70free 沿用, 序号=' + d3.queueNo);
  else fail('重挂异常', JSON.stringify({ dt: d3.discountType, af: d3.actualFee, qn: d3.queueNo }));

  // 4. 清理: 退掉重挂单
  const r4 = await api('POST', '/api/his/registration/cancel?id=' + d3.id + '&reason=' + encodeURIComponent('冒烟清理'), null, tok);
  if (r4.code === 0) pass('冒烟清理完成(重挂单已退)');
  else fail('清理失败', JSON.stringify(r4).slice(0, 200));

  const ok = results.filter(x => x[0] === 'PASS').length;
  const ng = results.filter(x => x[0] === 'FAIL').length;
  console.log('\nSMOKE: PASS=' + ok + '  FAIL=' + ng);
  if (ng > 0) process.exit(1);
})().catch(e => { console.error('脚本异常:', e); process.exit(1); });
