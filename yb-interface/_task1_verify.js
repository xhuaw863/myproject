/* Task #1 后端增强端到端验证:
 * 挂号(自动age70free/支付方式/候诊序号/重复拦截/同日同科室提示) + 退号needRefund + reRegister
 * + todaySummary + stats + statDetail + checkDuplicate + schedule/addSlot
 * 运行: node _task1_verify.js  (应用需已启动在 8080) */
const http = require('http');

const results = [];
const pass = m => { results.push(['PASS', m]); console.log('  [PASS]', m); };
const fail = (m, e) => { const d = e ? ' :: ' + (e.message || e).toString().split('\n')[0] : ''; results.push(['FAIL', m + d]); console.log('  [FAIL]', m + d); };
const step = async (name, fn) => { console.log('\n==== ' + name + ' ===='); try { await fn(); } catch (e) { fail(name, e); } };

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
  let tok, baseSummary;
  const regIds = {}; // A=09-25单(115), B=09-26 116单, C=09-26 170单, D=重挂单
  const QN = /^[A-Z0-9]{1,2}-\d{4}$/; // 科室简码(≤2) + 4位流水

  await step('0. 登录', async () => {
    const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
    if (lg.code === 0 && lg.data && lg.data.token) { tok = lg.data.token; pass('登录成功'); }
    else throw new Error('登录失败: ' + JSON.stringify(lg).slice(0, 200));
  });

  await step('1. todaySummary 基线', async () => {
    const s = await api('GET', '/api/his/registration/todaySummary', null, tok);
    if (s.code !== 0) throw new Error('接口异常: ' + JSON.stringify(s).slice(0, 200));
    baseSummary = s.data;
    pass('todaySummary 返回: ' + JSON.stringify(s.data));
  });

  await step('2. checkDuplicate 基线(患者19/排班115)', async () => {
    const d = await api('GET', '/api/his/registration/checkDuplicate?patientId=19&scheduleId=115', null, tok);
    if (d.code !== 0) throw new Error('接口异常: ' + JSON.stringify(d).slice(0, 200));
    if (d.data.duplicate === false && d.data.sameDept === false) pass('基线无重复: ' + JSON.stringify(d.data));
    else fail('基线异常', JSON.stringify(d.data));
  });

  await step('3. 挂号A: 患者19(73岁)+排班115(09-25 dept2 am) 自动age70free', async () => {
    const r = await api('POST', '/api/his/registration/register?patientId=19&scheduleId=115&medType=11&payMethod=free&feeType=self', null, tok);
    if (r.code !== 0) throw new Error('挂号失败: ' + JSON.stringify(r).slice(0, 220));
    const d = r.data;
    regIds.A = d.id;
    if (d.discountType === 'age70free' && Number(d.discountAmount) === 10 && Number(d.actualFee) === 0) pass('自动减免: discountType=age70free, discount=10, actualFee=0');
    else fail('减免异常', JSON.stringify({ dt: d.discountType, da: d.discountAmount, af: d.actualFee }));
    if (QN.test(d.queueNo || '')) pass('候诊序号: ' + d.queueNo);
    else fail('候诊序号格式异常: ' + d.queueNo);
    if (d.queueNo === 'WK-0001') pass('科室简码取自 his_dept.py_code(WKMZ→WK) 且当日首号=0001');
    else fail('简码/序号不符合预期: ' + d.queueNo);
    if (d.sameDeptWarning === false) pass('sameDeptWarning=false(首挂)');
    else fail('首挂即提示同日同科室', JSON.stringify(d.sameDeptWarning));
    if (d.payMethod === 'free' && d.feeType === 'self') pass('支付方式/费别落库: free / self');
    else fail('支付/费别异常', JSON.stringify({ pay: d.payMethod, fee: d.feeType }));
  });

  await step('4. 重复挂号拦截(患者19/排班115 二次)', async () => {
    const r = await api('POST', '/api/his/registration/register?patientId=19&scheduleId=115&medType=11', null, tok);
    if (r.code !== 0 && (r.msg || '').indexOf('该患者已挂此号源') >= 0) pass('正确拦截: ' + r.msg);
    else fail('未按预期拦截', JSON.stringify(r).slice(0, 200));
  });

  await step('5. checkDuplicate 复检(患者19/排班115) → duplicate=true', async () => {
    const d = await api('GET', '/api/his/registration/checkDuplicate?patientId=19&scheduleId=115', null, tok);
    if (d.data.duplicate === true) pass('duplicate=true, sameDept=' + d.data.sameDept + ', info=' + d.data.sameDeptInfo);
    else fail('复检异常', JSON.stringify(d.data));
  });

  await step('6. 挂号B: 患者19+排班116(09-26 dept2 am, discountType=none, cash)', async () => {
    const r = await api('POST', '/api/his/registration/register?patientId=19&scheduleId=116&discountType=none&payMethod=cash&feeType=insurance', null, tok);
    if (r.code !== 0) throw new Error('挂号失败: ' + JSON.stringify(r).slice(0, 220));
    regIds.B = r.data.id;
    if (r.data.discountType === 'none' && Number(r.data.actualFee) === 10) pass('显式 none: actualFee=10, queueNo=' + r.data.queueNo);
    else fail('none 减免异常', JSON.stringify({ dt: r.data.discountType, af: r.data.actualFee }));
    if (r.data.sameDeptWarning === false) pass('sameDeptWarning=false(09-26 首挂 dept2)');
    else fail('异常提示', JSON.stringify(r.data.sameDeptWarning));
  });

  await step('7. 挂号C: 患者19+排班170(09-26 dept2 pm) → sameDeptWarning=true', async () => {
    const r = await api('POST', '/api/his/registration/register?patientId=19&scheduleId=170&discountType=none&payMethod=cash', null, tok);
    if (r.code !== 0) throw new Error('挂号失败: ' + JSON.stringify(r).slice(0, 220));
    regIds.C = r.data.id;
    if (r.data.sameDeptWarning === true && (r.data.sameDeptInfo || '').indexOf('外科门诊') >= 0) pass('同日同科室提示: ' + r.data.sameDeptInfo);
    else fail('未提示同日同科室', JSON.stringify({ w: r.data.sameDeptWarning, i: r.data.sameDeptInfo }));
    if (r.data.queueNo === 'WK-0001') pass('pm 时段独立流水: ' + r.data.queueNo);
    else fail('pm 序号异常: ' + r.data.queueNo);
  });

  await step('8. 退号B(cash) → needRefund=true', async () => {
    const r = await api('POST', '/api/his/registration/cancel?id=' + regIds.B + '&reason=' + encodeURIComponent('任务1验证退号'), null, tok);
    if (r.code !== 0) throw new Error('退号失败: ' + JSON.stringify(r).slice(0, 220));
    if (r.data.needRefund === true && Number(r.data.refundAmount) === 10) pass('needRefund=true, refundAmount=10, payMethod=' + r.data.payMethod);
    else fail('退款提示异常', JSON.stringify(r.data));
  });

  await step('9. 退号A(free) → needRefund=false', async () => {
    const r = await api('POST', '/api/his/registration/cancel?id=' + regIds.A + '&reason=' + encodeURIComponent('任务1验证free不退款'), null, tok);
    if (r.code !== 0) throw new Error('退号失败: ' + JSON.stringify(r).slice(0, 220));
    if (r.data.needRefund === false) pass('free 支付不提示退款');
    else fail('free 误报退款', JSON.stringify(r.data));
  });

  await step('10. reRegister 退号B → 重挂成功且序号递增', async () => {
    const r = await api('POST', '/api/his/registration/reRegister?id=' + regIds.B, null, tok);
    if (r.code !== 0) throw new Error('重挂失败: ' + JSON.stringify(r).slice(0, 220));
    regIds.D = r.data.id;
    if (r.data.id !== regIds.B && r.data.status === 1) pass('重挂新记录 id=' + r.data.id + ', regNo=' + r.data.regNo);
    else fail('重挂异常', JSON.stringify({ id: r.data.id, st: r.data.status }));
    if (r.data.queueNo === 'WK-0002') pass('同科室同时段序号递增: ' + r.data.queueNo);
    else fail('递增异常: ' + r.data.queueNo);
    if (r.data.payMethod === 'cash') pass('支付方式沿用原单: cash');
    else fail('支付方式丢失', JSON.stringify(r.data.payMethod));
  });

  await step('11. stats(09-26~09-26) 全维度', async () => {
    const r = await api('GET', '/api/his/registration/stats?from=2026-09-26&to=2026-09-26&deptId=2', null, tok);
    if (r.code !== 0) throw new Error('接口异常: ' + JSON.stringify(r).slice(0, 220));
    const d = r.data;
    const keys = ['byDept', 'byTimeType', 'byDate', 'byStaff', 'byFeeType', 'byPayMethod', 'byDiscountType'];
    const missing = keys.filter(k => !(d[k] instanceof Array));
    if (missing.length === 0) pass('7 个维度键齐全: ' + keys.join(','));
    else fail('缺失维度: ' + missing.join(','));
    const deptRow = (d.byDept || [])[0] || {};
    if (Number(deptRow.dept_id) === 2 && Number(deptRow.count) >= 3) pass('byDept[0]: dept=外科门诊, count=' + deptRow.count + ', totalFee=' + deptRow.totalFee + ', discount=' + deptRow.discountAmount);
    else fail('byDept[0] 异常', JSON.stringify(deptRow));
    const tt = (d.byTimeType || []).map(x => x.time_type + ':' + x.count).join(',');
    if (tt.indexOf('am:') >= 0 && tt.indexOf('pm:') >= 0) pass('byTimeType: ' + tt);
    else fail('byTimeType 异常', tt);
    const dayRow = (d.byDate || [])[0] || {};
    if (dayRow.regCount !== undefined && dayRow.cancelCount !== undefined && dayRow.discountCount !== undefined) pass('byDate[0]: ' + JSON.stringify(dayRow));
    else fail('byDate 异常', JSON.stringify(dayRow));
    const pay = (d.byPayMethod || []).map(x => x.pay_method + ':' + x.count + ':' + x.totalActualFee).join(',');
    if (pay.indexOf('cash:') >= 0) pass('byPayMethod: ' + pay);
    else fail('byPayMethod 异常', pay);
  });

  await step('12. statDetail(09-26, deptId=2) 分页明细', async () => {
    const r = await api('GET', '/api/his/registration/statDetail?from=2026-09-26&to=2026-09-26&deptId=2&page=1&size=10', null, tok);
    if (r.code !== 0) throw new Error('接口异常: ' + JSON.stringify(r).slice(0, 220));
    const recs = (r.data && r.data.records) || [];
    const m = recs.filter(x => Number(x.patient_id) === 19);
    if (recs.length >= 3 && m.length >= 3) pass('total=' + r.data.total + ', 患者19记录=' + m.length + ', 含 queueNo=' + m.map(x => x.queue_no).join('/'));
    else fail('明细异常', 'total=' + (r.data && r.data.total) + ' rows=' + recs.length + ' m19=' + m.length);
    const stFilter = await api('GET', '/api/his/registration/statDetail?from=2026-09-26&to=2026-09-26&deptId=2&status=2&page=1&size=10', null, tok);
    const stRecs = (stFilter.data && stFilter.data.records) || [];
    if (stRecs.every(x => Number(x.status) === 2)) pass('status=2 筛选生效: ' + stRecs.length + ' 条');
    else fail('状态筛选未生效', JSON.stringify(stRecs.map(x => x.status)));
    const kw = await api('GET', '/api/his/registration/statDetail?from=2026-09-26&to=2026-09-26&keyword=' + encodeURIComponent('钱婷婷') + '&page=1&size=10', null, tok);
    const kwRecs = (kw.data && kw.data.records) || [];
    if (kwRecs.length >= 3 && kwRecs.every(x => x.patient_name === '钱婷婷')) pass('关键字筛选(钱婷婷): ' + kwRecs.length + ' 条');
    else fail('关键字筛选异常', JSON.stringify(kwRecs.length));
  });

  await step('13. todaySummary 终态(挂号A已被退: cancelled+1, discount+1, registered不变)', async () => {
    const s = await api('GET', '/api/his/registration/todaySummary', null, tok);
    if (s.code !== 0) throw new Error('接口异常');
    const after = s.data;
    if (Number(after.registered) === Number(baseSummary.registered)
      && Number(after.cancelled) === Number(baseSummary.cancelled) + 1
      && Number(after.discountCount) === Number(baseSummary.discountCount) + 1) {
      pass('registered 持平=' + after.registered + ', cancelled ' + baseSummary.cancelled + '→' + after.cancelled + ', discountCount ' + baseSummary.discountCount + '→' + after.discountCount);
    } else fail('终态异常', 'before=' + JSON.stringify(baseSummary) + ' after=' + JSON.stringify(after));
  });

  await step('14. schedule/addSlot(id=129) 号源+1', async () => {
    const r = await api('POST', '/api/his/schedule/addSlot?id=129', null, tok);
    if (r.code !== 0) throw new Error('加号失败: ' + JSON.stringify(r).slice(0, 220));
    const d = r.data;
    if (Number(d.totalNum) === 31 && Number(d.leftNum) === 31) pass('加号成功: totalNum=31, leftNum=31');
    else fail('号源异常', JSON.stringify({ t: d.totalNum, l: d.leftNum }));
  });

  console.log('\n================ 汇总 ================');
  const ok = results.filter(x => x[0] === 'PASS').length;
  const ng = results.filter(x => x[0] === 'FAIL').length;
  console.log('PASS=' + ok + '  FAIL=' + ng);
  results.filter(x => x[0] === 'FAIL').forEach(x => console.log('  FAIL:', x[1]));
})().catch(e => { console.error('脚本异常:', e); process.exit(1); });
