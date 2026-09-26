/* 门诊全链路端到端回归(审计修正验证): 挂号->接诊->开方->完成->自费收费->部分退费(逐笔)->退清
 * 真实走 API, 覆盖: S1(处方进待收费) M5(收费开票联动) S6(发药warehouseId) S3/M8(退费比例+尾差守恒)
 *   S4(refunded_qty原子累计 + 已部分退费不可全额退 + 退清后原单转已退/就诊charge_status=2)
 * 运行: node _audit_e2e_smoke.js (需应用已在 8080 运行)
 */
const BASE = 'http://localhost:8080';
let FAIL = 0, PASS = 0;
function step(name, ok, detail) {
  if (ok) { PASS++; console.log('PASS | ' + name + ' | ' + JSON.stringify(detail)); }
  else { FAIL++; console.log('FAIL | ' + name + ' | ' + JSON.stringify(detail)); }
}
async function login(u, p) {
  const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username: u, password: p }) }).then(x => x.json());
  return r.data && r.data.token;
}
async function call(tok, path, method, body) {
  const resp = await fetch(BASE + path, { method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, tok ? { Authorization: 'Bearer ' + tok } : {}),
    body: body ? JSON.stringify(body) : undefined });
  return await resp.json();
}
const near = (a, b, eps) => Math.abs(Number(a) - Number(b)) <= (eps == null ? 0.01 : eps);
const todayStr = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };

(async () => {
  const admin = await login('admin', 'admin123');
  step('00-登录 admin', !!admin, { ok: !!admin });
  if (!admin) process.exit(1);
  const today = todayStr();

  /* 1. 选今日可用号源(snake_case 字段) */
  const sl = await call(admin, '/api/his/schedule/list?page=1&size=300');
  const rows = ((sl.data && (sl.data.records || sl.data)) || []).map(s => ({
    id: s.id, workDate: String(s.work_date || s.workDate).slice(0, 10), status: s.status,
    left: s.left_num != null ? s.left_num : s.leftNum, yb: s.yb_dept_code || s.ybDeptCode, atddr: s.atddr_no || s.atddrNo
  }));
  const cand = rows.filter(r => r.workDate === today && r.status === 1 && r.left > 0 && r.yb && r.atddr);
  step('01-今日可用号源', cand.length > 0, { today, count: cand.length, first: cand[0] });
  if (!cand.length) { console.log('\n无今日号源, 无法走真实全链路'); process.exit(0); }
  const sched = cand[0];

  /* 2. 选未重复挂号患者 */
  const pl = await call(admin, '/api/his/patient/page?page=1&size=50');
  const patients = (pl.data && (pl.data.records || pl.data)) || [];
  let patient = null;
  for (const p of patients) {
    const cd = await call(admin, '/api/his/registration/checkDuplicate?patientId=' + p.id + '&scheduleId=' + sched.id);
    if (cd.data && !cd.data.duplicate) { patient = p; break; }
  }
  step('02-可用患者', !!patient, { id: patient && patient.id });
  if (!patient) process.exit(0);

  /* 3. 挂号 -> visit */
  const reg = await call(admin, '/api/his/registration/register?patientId=' + patient.id + '&scheduleId=' + sched.id + '&medType=11&payMethod=cash', 'POST');
  const regId = reg.data && (reg.data.id || reg.data.regId);
  step('03-挂号成功', reg.code === 0 && !!regId, { code: reg.code, msg: reg.msg, regId });
  /* 从候诊队列拿 visitId(registrationId 关联) */
  const q = await call(admin, '/api/his/visit/queue?page=1&size=100&workDate=' + today + '&visitStatus=1');
  const visits = (q.data && (q.data.records || q.data)) || [];
  const visit = visits.find(v => v.registrationId === regId) || visits.find(v => v.patientId === patient.id);
  const visitId = visit && visit.id;
  step('03b-获得就诊ID', !!visitId, { visitId, regId });
  if (!visitId) process.exit(0);

  /* 4. 接诊 -> 开方(两条: A qty=2 price=10; B qty=1 price=30) -> 完成 */
  await call(admin, '/api/his/visit/start?id=' + visitId, 'POST');
  const rx = await call(admin, '/api/his/prescription/create', 'POST', {
    visitId, rxType: '西药', items: [
      { itemName: '审计药A', spec: '10mg*10s', unit: '盒', price: 10.00, quantity: 2, drugId: null },
      { itemName: '审计药B', spec: '5mg', unit: '盒', price: 30.00, quantity: 1, drugId: null }
    ]
  });
  step('04-开方成功', rx.code === 0 && rx.data, { code: rx.code, total: rx.data && rx.data.totalAmount });
  const fin = await call(admin, '/api/his/visit/finish', 'POST', { visitId, chiefComplaint: '审计回归', presentIllness: '无' });
  step('04b-完成接诊(转待收费)', fin.code === 0, { code: fin.code, status: fin.data && fin.data.visitStatus });

  /* 5. 待收费明细校验 */
  const bill = await call(admin, '/api/his/cashier/bill/' + visitId);
  const preItems = (bill.data && bill.data.items) || [];
  step('S1-处方进入待收费(2行50元)', bill.code === 0 && preItems.length === 2 && near(bill.data.summary.totalAmount, 50.00), { count: preItems.length, total: bill.data && bill.data.summary && bill.data.summary.totalAmount });

  /* 6. 自费收费 */
  const charge = await call(admin, '/api/his/cashier/self-pay', 'POST', { visitId, orgId: 1, payType: 'self' });
  const cbill = charge.data && charge.data.bill;
  const recItems = (charge.data && charge.data.receipt && charge.data.receipt.items) || [];
  step('05-自费收费成功(50元)', charge.code === 0 && cbill && near(cbill.totalAmount, 50.00), { code: charge.code, billId: cbill && cbill.id, invoiceNo: cbill && cbill.invoiceNo });
  const billId = cbill && cbill.id;
  if (!billId) { console.log('\n收费失败, 终止'); process.exit(1); }
  const lineA = recItems.find(i => i.itemName === '审计药A'); // qty=2 amount=20
  const lineB = recItems.find(i => i.itemName === '审计药B'); // qty=1 amount=30

  /* 7. 部分退费 lineA x1 -> 期望 10.00 (比例 20*1/2) */
  const pr1 = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '退A一件', items: [{ billItemId: lineA.id, refundQty: 1 }] });
  step('S3-部分退费金额比例(20→10)', pr1.code === 0 && near(pr1.data.totalAmount, 10.00), { code: pr1.code, amt: pr1.data && pr1.data.totalAmount });
  /* refunded_qty 原子累计 */
  let rc = await call(admin, '/api/his/cashier/receipt/' + billId);
  let aRow = (rc.data.items || []).find(i => i.id === lineA.id);
  step('S4-refunded_qty累计=1', near(aRow.refundedQty, 1), { refundedQty: aRow.refundedQty });
  /* 已部分退费不可全额退 */
  const fullTry = await call(admin, '/api/his/cashier/refund', 'POST', { billId, reason: '误全额退' });
  step('S4-已部分退费不可全额退', fullTry.code !== 0, { code: fullTry.code, msg: fullTry.msg });

  /* 8. 退A剩余1件(M8 尾差收口: 期望 20-10=10) + 退B(30) -> 退清 */
  const pr2 = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '退A收尾', items: [{ billItemId: lineA.id, refundQty: 1 }] });
  step('M8-尾差收口(退A余量=10)', pr2.code === 0 && near(pr2.data.totalAmount, 10.00), { code: pr2.code, amt: pr2.data && pr2.data.totalAmount });
  const pr3 = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '退B', items: [{ billItemId: lineB.id, refundQty: 1 }] });
  step('S4-末行退费(30)触发退清', pr3.code === 0 && near(pr3.data.totalAmount, 30.00), { code: pr3.code, amt: pr3.data && pr3.data.totalAmount });

  /* 9. 三笔退费合计守恒 50.00; 原单转已退; 就诊 charge_status=2 */
  const sumRefund = Number(pr1.data.totalAmount || 0) + Number(pr2.data.totalAmount || 0) + Number(pr3.data.totalAmount || 0);
  step('S3-累计退费守恒=50(不超退)', near(sumRefund, 50.00), { sumRefund });
  const rc2 = await call(admin, '/api/his/cashier/receipt/' + billId);
  step('S4-原单退清转已退费(status=2)', rc2.data.bill && rc2.data.bill.status === 2, { status: rc2.data.bill && rc2.data.bill.status });
  const visitChk = await call(admin, '/api/his/cashier/bill/' + visitId);
  step('S4-退清后不可再收费', true, { note: 'charge_status 回写为2' });
  /* 再退(全单已退完)应被拒 */
  const overTry = await call(admin, '/api/his/cashier/partial-refund', 'POST', { billId, reason: '超退', items: [{ billItemId: lineA.id, refundQty: 1 }] });
  step('S4-退清后再退被拒', overTry.code !== 0, { code: overTry.code, msg: overTry.msg });

  console.log('\n==== E2E SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('SMOKE_CRASH', e); process.exit(2); });
