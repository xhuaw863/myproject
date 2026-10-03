/* 全面审计 - 住院业务闭环流程测试(2026-10-03, HTTP层)
 * 链路: 空床 -> 入院登记 -> 长期医嘱(开嘱即待审) -> 临时医嘱(开立即记账) -> 护士审核 -> 执行计划->批量执行
 *       -> 预交金缴纳 -> 出院申请(2->3) -> 预结算(自费仅院内试算) -> 正式结算(扣预交金, 3->4, 释放床)
 *       -> 金额/余额守恒 -> 出院后开嘱守卫 -> 结算历史。
 * 运行: node _aud1003_inp.js  (需 8080 在跑; 患者/病区用既有 demo 数据, 结算走自费链不涉医保 mock)
 */
const BASE = 'http://localhost:8080';
let FAIL = 0, PASS = 0;
const L = [];
function step(name, ok, detail) {
  const line = (ok ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + JSON.stringify(detail);
  if (ok) PASS++; else FAIL++;
  L.push(line);
}
async function call(tok, path, method, body) {
  const resp = await fetch(BASE + path, { method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, tok ? { Authorization: 'Bearer ' + tok } : {}),
    body: body ? JSON.stringify(body) : undefined });
  try { return await resp.json(); } catch (e) { return { code: -1, httpStatus: resp.status }; }
}
const near = (a, b, eps) => Math.abs(Number(a) - Number(b)) <= (eps == null ? 0.01 : eps);

(async () => {
  const login = await call(null, '/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const tk = login.data && login.data.token;
  const doctorId = login.data && login.data.staffId;
  step('00-登录 admin(关联职工可开嘱)', !!tk && !!doctorId, { code: login.code, staffId: doctorId });
  if (!tk) { console.log(L.join('\n')); process.exitCode = 1; return; }

  // 01 空床(内科病区 ward=1)
  const WARD = 1, DEPT = 1042;
  const bl = await call(tk, '/api/his/inp/bed/list?wardId=' + WARD);
  const free = ((bl.data) || []).filter(b => b.status === 0);
  step('01-病区空床可用', bl.code === 0 && free.length > 0, { ward: WARD, free: free.length, first: free[0] && free[0].bedNo });
  if (!free.length) { console.log(L.join('\n')); process.exitCode = 1; return; }
  const bedId = free[0].id;

  // 02 入院登记(逐个患者试, 避开"重复在院"拦截; 自费不传 medType/psnNo)
  const pl = await call(tk, '/api/his/patient/page?page=1&size=60');
  const pats = (pl.data && (pl.data.records || pl.data)) || [];
  let visit = null, usedPatient = null, lastErr = null;
  for (const p of pats) {
    if (String(p.name || '').indexOf('压测') === 0) continue;
    const ad = await call(tk, '/api/his/inp/admit', 'POST', {
      patientId: p.id, deptId: DEPT, wardId: WARD, bedId, doctorId,
      admitDiag: '全面审计住院闭环-入院测试诊断'
    });
    if (ad.code === 0 && ad.data && ad.data.id) { visit = ad.data; usedPatient = p; break; }
    lastErr = { code: ad.code, msg: ad.msg };
  }
  step('02-入院登记成功(占床转在院)', !!visit && visit.visitStatus === 2,
    { visitId: visit && visit.id, inpNo: visit && visit.inpNo, patient: usedPatient && usedPatient.name, status: visit && visit.visitStatus, lastErr: visit ? undefined : lastErr });
  if (!visit) { console.log(L.join('\n')); process.exitCode = 1; return; }
  const visitId = visit.id;
  // 床位应被占用
  const bl2 = await call(tk, '/api/his/inp/bed/list?wardId=' + WARD);
  const bedNow = (bl2.data || []).find(b => String(b.id) === String(bedId));
  step('02b-入院后床位转占用', bedNow && bedNow.status === 1, { bedStatus: bedNow && bedNow.status, inpVisitId: bedNow && bedNow.inpVisitId });

  // 03 长期医嘱(治疗类, 无目录: 服务端按 unitPrice 兜底)
  const lo = await call(tk, '/api/his/inp/order', 'POST', {
    inpVisitId: visitId, orderType: 1, orderCategory: 5, orderContent: '审计-长期二级护理',
    freqCode: 'qd', quantity: 1, unitPrice: 30.00
  });
  const longOrderId = lo.data && lo.data.id;
  step('03-开立长期医嘱(待审核)', lo.code === 0 && !!longOrderId, { code: lo.code, msg: lo.msg, id: longOrderId, status: lo.data && lo.data.orderStatus, unitPrice: lo.data && lo.data.unitPrice });

  // 04 临时医嘱(开立即记账 20 元)
  const to = await call(tk, '/api/his/inp/order', 'POST', {
    inpVisitId: visitId, orderType: 2, orderCategory: 4, orderContent: '审计-临时雾化吸入',
    quantity: 1, unitPrice: 20.00
  });
  const tmpOrderId = to.data && to.data.id;
  const cl1 = await call(tk, '/api/his/inp/settle/charge/list?inpVisitId=' + visitId + '&page=1&size=50');
  const chg1 = (cl1.data && cl1.data.records) || [];
  step('04-临时医嘱开立即记账(20元)', to.code === 0 && chg1.length >= 1 && near(chg1.reduce((s, c) => s + Number(c.amount), 0), 20.00),
    { code: to.code, detailCount: chg1.length, sum: chg1.reduce((s, c) => s + Number(c.amount), 0) });

  // 05 护士批量审核(1->2)
  const au = await call(tk, '/api/his/inp/order-exec/audit', 'POST', [longOrderId, tmpOrderId].filter(Boolean));
  step('05-护士审核医嘱(1->2)', au.code === 0 && au.data && Number(au.data.audited || au.data.count || 0) >= 1, { code: au.code, msg: au.msg, data: au.data });

  // 06 执行计划 -> 批量执行(长期医嘱按频次生成当天计划)
  const pn = await call(tk, '/api/his/inp/order-exec/plan?wardId=' + WARD + '&page=1&size=100');
  const plans = (pn.data && pn.data.records) || [];
  const myPlan = plans.filter(r => String(r.inpVisitId) === String(visitId) && Number(r.execStatus) === 1);
  step('06a-长期医嘱生成执行计划', myPlan.length >= 1, { planCount: myPlan.length, first: myPlan[0] && { execId: myPlan[0].id, planTime: myPlan[0].planTime } });
  let execOk = null;
  if (myPlan.length) {
    execOk = await call(tk, '/api/his/inp/order-exec/execute', 'POST', myPlan.map(r => r.id));
    step('06b-批量执行(1->2)', execOk.code === 0, { code: execOk.code, msg: execOk.msg, data: execOk.data });
  } else step('06b-批量执行(1->2)', false, { skipped: '无计划' });

  // 06c S-4: 长期医嘱逐日记账 —— 手动触发定时任务同款回填, 活动长期医嘱应生成当日费用明细(此例 qd×30=30元)
  const pc = await call(tk, '/api/his/inp/order-exec/post-daily-charge', 'POST');
  const cl2 = await call(tk, '/api/his/inp/settle/charge/list?inpVisitId=' + visitId + '&page=1&size=50');
  const chg2 = (cl2.data && cl2.data.records) || [];
  const longChg = chg2.find(c => String(c.orderId) === String(longOrderId));
  const sum2 = chg2.reduce((s, c) => s + Number(c.amount), 0);
  step('06c-S4长期医嘱逐日记账(当日30元, 与临时合计50)', pc.code === 0 && !!longChg && near(Number(longChg.amount), 30.00) && near(sum2, 50.00),
    { code: pc.code, msg: pc.msg, posted: pc.data && pc.data.posted, longAmount: longChg && Number(longChg.amount), sum: sum2 });

  // 07 预交金缴纳 100 -> 余额
  const dp = await call(tk, '/api/his/inp/deposit', 'POST', { inpVisitId: visitId, amount: 100.00, payType: 'cash', direction: 1, remark: '审计闭环预交' });
  const bal = await call(tk, '/api/his/inp/deposit/balance/' + visitId);
  step('07-预交金缴纳并余额一致', dp.code === 0 && near(bal.data, 100.00), { code: dp.code, balance: bal.data });

  // 08 出院申请(2->3)
  const da = await call(tk, '/api/his/inp/visit/' + visitId + '/discharge-apply', 'PUT');
  step('08-出院申请转出院办理中(3)', da.code === 0 && da.data && da.data.visitStatus === 3, { code: da.code, msg: da.msg, visitStatus: da.data && da.data.visitStatus });

  // 09 预结算(自费仅院内试算, 不走2303)
  const ps = await call(tk, '/api/his/inp/settle/pre?visitId=' + visitId, 'POST');
  step('09-预结算院内试算', ps.code === 0, { code: ps.code, msg: ps.msg, ybFlag: ps.data && ps.data.ybFlag, total: ps.data && (ps.data.totalAmount || (ps.data.summary && ps.data.summary.totalAmount)) });

  // 10 正式结算(出院): 汇总->扣预交金->自费终态->释放床
  const se = await call(tk, '/api/his/inp/settle', 'POST', { inpVisitId: visitId, settleType: 1 });
  const st = se.data && se.data.settle;
  step('10-正式出院结算成功', se.code === 0 && !!st, { code: se.code, msg: se.msg, settleId: st && st.id, total: st && st.totalAmount, depositDeduct: st && st.depositDeduct, cashPay: se.data && se.data.cashPay, refund: se.data && se.data.refundAmount });

  // 10b 金额守恒: deduct+cash=total(预交抵扣+现缴=结算额); refund=预交余额-deduct(多缴退还)
  if (st) {
    const balPaid = 100; // 步骤07已缴预交金
    const eq1 = near(Number(st.totalAmount), Number(st.depositDeduct || 0) + Number(se.data.cashPay || 0));
    const eq2 = near(Number(se.data.refundAmount || 0), balPaid - Number(st.depositDeduct || 0));
    step('10b-结算守恒(抵扣+现缴=总额; 退还=余额-抵扣)', eq1 && eq2,
      { total: Number(st.totalAmount), deduct: Number(st.depositDeduct || 0), cash: Number(se.data.cashPay || 0), refund: Number(se.data.refundAmount || 0), balPaid });
  } else step('10b-结算守恒(抵扣+现缴=总额; 退还=余额-抵扣)', false, { skipped: '无结算单' });

  // 11 结算后状态: visit=4 + 床位释放 + 余额结清
  const vd = await call(tk, '/api/his/inp/visit/' + visitId);
  const vdd = vd.data && (vd.data.visit || vd.data);
  const bl3 = await call(tk, '/api/his/inp/bed/list?wardId=' + WARD);
  const bedAfter = (bl3.data || []).find(b => String(b.id) === String(bedId));
  const bal2 = await call(tk, '/api/his/inp/deposit/balance/' + visitId);
  step('11-出院后就诊转已出院(4)', vd.code === 0 && vdd && Number(vdd.visitStatus) === 4, { visitStatus: vdd && vdd.visitStatus, dischargeDate: vdd && vdd.dischargeDate });
  step('11b-出院后床位释放(status=0)', bedAfter && bedAfter.status === 0, { bedStatus: bedAfter && bedAfter.status });
  step('11c-出院后预交金余额清退', near(bal2.data, 0), { balanceAfterSettle: bal2.data });

  // 12 守卫: 出院后不可开嘱
  const guardOrder = await call(tk, '/api/his/inp/order', 'POST', { inpVisitId: visitId, orderType: 2, orderCategory: 4, orderContent: '审计-出院后开嘱应被拒', quantity: 1, unitPrice: 1 });
  step('12-出院后开立医嘱被拒', guardOrder.code !== 0, { code: guardOrder.code, msg: guardOrder.msg });

  // 13 结算历史
  const sh = await call(tk, '/api/his/inp/settle/settlements/' + visitId);
  step('13-结算历史含出院结算单', sh.code === 0 && Array.isArray(sh.data) && sh.data.length >= 1, { count: sh.data && sh.data.length, types: (sh.data || []).map(x => x.settleType + ':' + x.ybStatus) });

  console.log(L.join('\n'));
  console.log('\n==== 住院闭环 SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
})().catch(e => { console.log(L.join('\n')); console.error('INP_FLOW_CRASH', e && e.message); process.exitCode = 2; });
