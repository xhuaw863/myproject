/* 全面审计 - 门诊业务闭环流程测试(时间感知版, 2026-10-03)
 * 背景: 既有 _audit_e2e_smoke.js 在 23:00 因"今日班次全部过期"被守卫正确拦截而无法挂号;
 *       本脚本改选"明日"号源(班次未过期), 走真实全链路: 班次字典 -> 挂号 -> 号源守恒 ->
 *       候诊就诊 -> 开方 -> 完成接诊(2203) -> 自费收费 -> 部分退费逐笔 -> 退清守恒 -> 收尾退号恢复现场。
 * 运行: node _aud1003_flow.js   (需 8080 在跑)
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
const dstr = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');

(async () => {
  const login = await call(null, '/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const admin = login.data && login.data.token;
  step('00-登录 admin', !!admin, { code: login.code });
  if (!admin) { console.log(L.join('\n')); process.exitCode = 1; return; }

  // 明日日期
  const tm = new Date(); tm.setDate(tm.getDate() + 1);
  const tomorrow = dstr(tm);

  // 1. 班次字典(本次改造新增面): 应返回启用班次含起止时间
  const sd = await call(admin, '/api/community-dict/shift-dict/values');
  const shifts = sd.data || [];
  step('01-班次字典启用值(含起止时间)', sd.code === 0 && shifts.length >= 3 && shifts.every(s => s.code && s.name),
    { count: shifts.length, codes: shifts.map(s => s.code + ':' + (s.startTime || '') + '-' + (s.endTime || '')) });

  // 1b. 确保明日有排班: 若无则克隆今日一个有效号源(dept/staff/费别)建明日 am 排班
  let createdSchedId = null;
  let sl = await call(admin, '/api/his/schedule/list?page=1&size=300&workDate=' + tomorrow);
  let rawRows = (sl.data && (sl.data.records || sl.data)) || [];
  let cand0 = rawRows.map(mapSched).filter(r => r.workDate === tomorrow && r.status === 1 && r.left > 0 && r.yb && r.atddr);
  if (!cand0.length) {
    const todaySl = await call(admin, '/api/his/schedule/list?page=1&size=300');
    const todayValid = ((todaySl.data && (todaySl.data.records || todaySl.data)) || []).map(mapSched).find(r => r.status === 1 && r.left > 0 && r.yb && r.atddr);
    if (todayValid) {
      const full = ((todaySl.data && (todaySl.data.records || todaySl.data)) || []).find(s => String(s.id) === String(todayValid.id));
      const mk = await call(admin, '/api/his/schedule', 'POST', {
        deptId: full.dept_id || full.deptId, staffId: full.staff_id || full.staffId, workDate: tomorrow, timeType: 'am',
        regLevelCode: full.reg_level_code || full.regLevelCode, regLevelName: full.reg_level_name || full.regLevelName,
        regFee: full.reg_fee != null ? full.reg_fee : full.regFee, totalNum: 5, status: 1
      });
      createdSchedId = mk.data && mk.data.id;
      step('01b-克隆建明日排班', mk.code === 0 && !!createdSchedId, { code: mk.code, msg: mk.msg, id: createdSchedId });
      sl = await call(admin, '/api/his/schedule/list?page=1&size=300&workDate=' + tomorrow);
      rawRows = (sl.data && (sl.data.records || sl.data)) || [];
    }
  }

  // 2. 选明日可用号源
  const rows = rawRows.map(mapSched);
  const cand = rows.filter(r => r.workDate === tomorrow && r.status === 1 && r.left > 0 && r.yb && r.atddr);
  step('02-明日可用号源(未过期班次)', cand.length > 0, { tomorrow, count: cand.length, first: cand[0] });
  if (!cand.length) { console.log(L.join('\n')); console.log('\n无明日号源(克隆排班也失败), 无法走真实全链路'); await cleanup(admin, createdSchedId); return; }
  const sched = cand[0];
  const leftBefore = sched.left;

  // 3. 选未重复挂号患者
  const pl = await call(admin, '/api/his/patient/page?page=1&size=50');
  const patients = (pl.data && (pl.data.records || pl.data)) || [];
  let patient = null;
  for (const p of patients) {
    if (String(p.name || '').indexOf('压测') === 0) continue; // 跳过压测患者
    const cd = await call(admin, '/api/his/registration/checkDuplicate?patientId=' + p.id + '&scheduleId=' + sched.id);
    if (cd.data && !cd.data.duplicate) { patient = p; break; }
  }
  step('03-可用患者', !!patient, { id: patient && patient.id, name: patient && patient.name });
  if (!patient) { console.log(L.join('\n')); await cleanup(admin, createdSchedId); return; }

  // 4. 挂号(2201) -> 号源守恒
  const reg = await call(admin, '/api/his/registration/register?patientId=' + patient.id + '&scheduleId=' + sched.id + '&medType=11&payMethod=cash', 'POST');
  const regId = reg.data && (reg.data.id || reg.data.regId);
  step('04-挂号成功(2201)', reg.code === 0 && !!regId, { code: reg.code, msg: reg.msg, regId, mdtrtId: reg.data && reg.data.mdtrtId, queueNo: reg.data && reg.data.queueNo });
  if (!regId) { console.log(L.join('\n')); await cleanup(admin, createdSchedId); return; }
  const sl2 = await call(admin, '/api/his/schedule/list?page=1&size=300&workDate=' + tomorrow);
  const sched2 = ((sl2.data && (sl2.data.records || sl2.data)) || []).find(s => String(s.id) === String(sched.id));
  const leftAfter = sched2 ? (sched2.left_num != null ? sched2.left_num : sched2.leftNum) : -999;
  step('05-号源扣减守恒(left-1)', leftAfter === leftBefore - 1, { before: leftBefore, after: leftAfter });

  // 6. 候诊队列取 visitId
  const q = await call(admin, '/api/his/visit/queue?page=1&size=100&workDate=' + tomorrow + '&visitStatus=1');
  const visits = (q.data && (q.data.records || q.data)) || [];
  const visit = visits.find(v => String(v.registrationId) === String(regId)) || visits.find(v => String(v.patientId) === String(patient.id));
  const visitId = visit && visit.id;
  step('06-候诊队列获得就诊ID', !!visitId, { visitId, queueLen: visits.length });
  if (!visitId) { console.log(L.join('\n')); await call(admin, '/api/his/registration/cancel?id=' + regId + '&reason=审计收尾', 'POST'); await cleanup(admin, createdSchedId); return; }

  // 7. 接诊 -> 开方(2行: A qty2 price10=20; B qty1 price30=30) -> 完成(2203)
  await call(admin, '/api/his/visit/start?id=' + visitId, 'POST');
  const rx = await call(admin, '/api/his/prescription/create', 'POST', {
    visitId, rxType: '西药', items: [
      { itemName: '审计药A', spec: '10mg*10s', unit: '盒', price: 10.00, quantity: 2, drugId: null },
      { itemName: '审计药B', spec: '5mg', unit: '盒', price: 30.00, quantity: 1, drugId: null }
    ]
  });
  step('07-开方成功(总额50)', rx.code === 0 && rx.data && near(rx.data.totalAmount, 50.00), { code: rx.code, total: rx.data && rx.data.totalAmount });
  const fin = await call(admin, '/api/his/visit/finish', 'POST', { visitId, chiefComplaint: '全面审计闭环', presentIllness: '无', uploadYb: true });
  step('08-完成接诊转待收费(2203)', fin.code === 0, { code: fin.code, visitStatus: fin.data && fin.data.visitStatus });

  // 9. 待收费明细
  const bill = await call(admin, '/api/his/cashier/bill/' + visitId);
  const preItems = (bill.data && bill.data.items) || [];
  step('09-处方进入待收费(2行50元)', bill.code === 0 && preItems.length === 2 && near(bill.data.summary.totalAmount, 50.00),
    { count: preItems.length, total: bill.data && bill.data.summary && bill.data.summary.totalAmount });

  // 10. 自费收费
  const charge = await call(admin, '/api/his/cashier/self-pay', 'POST', { visitId, orgId: 1, payType: 'self' });
  const cbill = charge.data && charge.data.bill;
  const recItems = (charge.data && charge.data.receipt && charge.data.receipt.items) || [];
  const billId = cbill && cbill.id;
  step('10-自费收费成功(开票)', charge.code === 0 && !!billId && near(cbill.totalAmount, 50.00), { code: charge.code, billId, invoiceNo: cbill && cbill.invoiceNo });
  if (!billId) { console.log(L.join('\n')); await cleanup(admin, createdSchedId); return; }
  const lineA = recItems.find(i => i.itemName === '审计药A');
  const lineB = recItems.find(i => i.itemName === '审计药B');

  // 11. 部分退费逐笔 + 守恒
  const pr1 = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '退A一件', items: [{ billItemId: lineA.id, refundQty: 1 }] });
  step('11-部分退费比例(20→10)', pr1.code === 0 && near(pr1.data.totalAmount, 10.00), { code: pr1.code, amt: pr1.data && pr1.data.totalAmount });
  const fullTry = await call(admin, '/api/his/cashier/refund', 'POST', { billId, reason: '误全额退' });
  step('12-已部分退费不可全额退', fullTry.code !== 0, { code: fullTry.code });
  const pr2 = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '退A收尾', items: [{ billItemId: lineA.id, refundQty: 1 }] });
  const pr3 = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '退B', items: [{ billItemId: lineB.id, refundQty: 1 }] });
  const sumRefund = Number(pr1.data.totalAmount || 0) + Number(pr2.data.totalAmount || 0) + Number(pr3.data.totalAmount || 0);
  step('13-逐笔退费合计守恒=50(不超退)', near(sumRefund, 50.00), { sumRefund });
  const rc2 = await call(admin, '/api/his/cashier/receipt/' + billId);
  step('14-退清后原单转已退费(status=2)', rc2.data && rc2.data.bill && rc2.data.bill.status === 2, { status: rc2.data && rc2.data.bill && rc2.data.bill.status });
  const overTry = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '超退', items: [{ billItemId: lineA.id, refundQty: 1 }] });
  step('15-退清后再退被拒', overTry.code !== 0, { code: overTry.code });

  // 16. 收尾退号(恢复号源现场)
  const cancel = await call(admin, '/api/his/registration/cancel?id=' + regId + '&reason=审计收尾恢复现场', 'POST');
  step('16-收尾退号(2202)', cancel.code === 0, { code: cancel.code, msg: cancel.msg, needRefund: cancel.data && cancel.data.needRefund });
  const sl3 = await call(admin, '/api/his/schedule/list?page=1&size=300&workDate=' + tomorrow);
  const sched3 = ((sl3.data && (sl3.data.records || sl3.data)) || []).find(s => String(s.id) === String(sched.id));
  const leftFinal = sched3 ? (sched3.left_num != null ? sched3.left_num : sched3.leftNum) : -999;
  step('17-退号后号源回滚守恒(=before)', leftFinal === leftBefore, { before: leftBefore, final: leftFinal });

  // 18. 清理: 删除本脚本克隆的明日排班(仅当无人再挂时)
  if (createdSchedId) {
    const del = await call(admin, '/api/his/schedule/' + createdSchedId, 'DELETE');
    step('18-清理克隆明日排班', del.code === 0, { code: del.code, msg: del.msg });
  }

  console.log(L.join('\n'));
  console.log('\n==== 门诊闭环 SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
})().catch(e => { console.log(L.join('\n')); console.error('FLOW_CRASH', e && e.message); process.exitCode = 2; });

function mapSched(s) {
  return { id: s.id, workDate: String(s.work_date || s.workDate).slice(0, 10), timeType: s.time_type || s.timeType, status: s.status,
    left: s.left_num != null ? s.left_num : s.leftNum, total: s.total_num != null ? s.total_num : s.totalNum,
    yb: s.yb_dept_code || s.ybDeptCode, atddr: s.atddr_no || s.atddrNo };
}
async function cleanup(admin, createdSchedId) {
  if (createdSchedId) { try { await call(admin, '/api/his/schedule/' + createdSchedId, 'DELETE'); } catch (e) {} }
}
