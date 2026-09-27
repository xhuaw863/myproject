/* P1-P5 二级库存专业化 后端链路冒烟(真实走 API): 请领全链 / 调拨全链 / 调价预览生效 / 台账恒等式 / 追溯码采集绑定报送
 * 运行: node _p_chain_smoke.js (需应用在 8080)。临时验证脚本, 验证后删除。
 */
const BASE = process.env.CHAIN_BASE || 'http://localhost:8080';
let FAIL = 0, PASS = 0;
function step(name, ok, detail) {
  if (ok) { PASS++; console.log('PASS | ' + name); }
  else { FAIL++; console.log('FAIL | ' + name + ' | ' + JSON.stringify(detail)); }
}
async function login() {
  const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username: 'admin', password: 'admin123' }) }).then(x => x.json());
  return r.data && r.data.token;
}
async function call(tok, path, method, body) {
  const resp = await fetch(BASE + path, { method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, tok ? { Authorization: 'Bearer ' + tok } : {}),
    body: body ? JSON.stringify(body) : undefined });
  return await resp.json();
}
const num = v => Number(v == null ? 0 : v);
const near = (a, b, eps) => Math.abs(num(a) - num(b)) <= (eps == null ? 0.01 : eps);

(async () => {
  const T = await login();
  step('00-login', !!T, { ok: !!T });
  if (!T) process.exit(1);

  // 源数据: 药房(含库存位) + 药库 + 药库有库存的药品
  const ph = await call(T, '/api/his/pharmacy/pharmacy-def');
  const pharmacies = (ph.data || []).filter(p => p.stockLocationId);
  step('01-药房库存位已迁移', pharmacies.length > 0, ph.data && ph.data.length);
  const pharmacy = pharmacies[0];
  const wh = await call(T, '/api/his/stock/warehouse-def');
  const warehouse = (wh.data || []).filter(w => !w.kind || w.kind === 'WAREHOUSE')[0];
  step('02-药库存在', !!warehouse && !!pharmacy, { wh: warehouse && warehouse.id, ph: pharmacy && pharmacy.id });
  const stk = await call(T, `/api/his/stock/page?warehouseId=${warehouse.id}&page=1&size=50`);
  const recs = (stk.data && (stk.data.records || stk.data)) || [];
  const drugRec = recs.find(r => num(r.qty) > 2);
  step('03-药库有库存药品', !!drugRec, { count: recs.length });
  if (!drugRec) { console.log('药库无可用库存, 终止'); process.exit(1); }
  const drugId = drugRec.drugCatalogId, batch = drugRec.batchNo;

  // 药房库存位初始量
  const phLoc = pharmacy.stockLocationId;
  const beforePh = await call(T, `/api/his/requisition/available?warehouseId=${phLoc}&drugCatalogId=${drugId}`);
  const phBefore = num(beforePh.data);

  /* ============ P1 请领: 发起 -> 审核发货 -> 收货 ============ */
  const rq = await call(T, '/api/his/requisition', 'POST', {
    pharmacyId: pharmacy.id, toWarehouseId: warehouse.id, submit: true,
    items: [{ drugCatalogId: drugId, qtyApply: 1, batchNo: batch }]
  });
  const reqId = rq.data && rq.data.id;
  step('P1-发起请领', rq.code === 0 && !!reqId, { code: rq.code, msg: rq.msg });
  const ap = await call(T, `/api/his/requisition/${reqId}/approve?approved=true`, 'POST');
  step('P1-审核发货(out_type4)', ap.code === 0 && ap.data && ap.data.status === 2, { code: ap.code, msg: ap.msg, status: ap.data && ap.data.status });
  const rv = await call(T, `/api/his/requisition/${reqId}/receive`, 'POST');
  step('P1-确认收货(in_type4)', rv.code === 0 && rv.data && rv.data.status === 3, { code: rv.code, msg: rv.msg, status: rv.data && rv.data.status });
  const afterPh = await call(T, `/api/his/requisition/available?warehouseId=${phLoc}&drugCatalogId=${drugId}`);
  step('P1-药房库存+1(守恒)', near(num(afterPh.data), phBefore + 1), { before: phBefore, after: afterPh.data });

  /* ============ P2 调拨: 药库 -> 药房库存位, 指定批次 ============ */
  const tf = await call(T, '/api/his/transfer', 'POST', {
    fromLocationId: warehouse.id, toLocationId: phLoc,
    items: [{ drugCatalogId: drugId, batchNo: batch, qty: 1 }]
  });
  const tfId = tf.data && tf.data.id;
  step('P2-建调拨单', tf.code === 0 && !!tfId, { code: tf.code, msg: tf.msg });
  const ship = await call(T, `/api/his/transfer/${tfId}/ship`, 'POST');
  step('P2-调出(ship)', ship.code === 0 && ship.data && ship.data.status === 2, { code: ship.code, msg: ship.msg });
  const recv = await call(T, `/api/his/transfer/${tfId}/receive`, 'POST');
  step('P2-调入(receive)', recv.code === 0 && recv.data && recv.data.status === 3, { code: recv.code, msg: recv.msg });
  const afterTf = await call(T, `/api/his/requisition/available?warehouseId=${phLoc}&drugCatalogId=${drugId}`);
  step('P2-药房库存再+1(守恒)', near(num(afterTf.data), num(afterPh.data) + 1), { afterReq: afterPh.data, afterTf: afterTf.data });

  /* ============ P3 调价: 预览 -> 建单 -> 生效 -> 校验目录/库存零售价 ============ */
  const oldRetail = num(drugRec.retailPrice);
  const newRetail = (oldRetail + 1.23).toFixed(2);
  const pv = await call(T, '/api/his/price-adjust/preview', 'POST', { items: [{ drugCatalogId: drugId, newRetail: Number(newRetail) }] });
  step('P3-预览影响', pv.code === 0 && pv.data && pv.data.rows && pv.data.rows.length === 1, { code: pv.code, rows: pv.data && pv.data.rows && pv.data.rows.length });
  const pa = await call(T, '/api/his/price-adjust', 'POST', { effectiveDate: '2026-09-28', reason: '链式冒烟调价', items: [{ drugCatalogId: drugId, newRetail: Number(newRetail) }] });
  const paId = pa.data && pa.data.id;
  step('P3-建调价单', pa.code === 0 && !!paId, { code: pa.code, msg: pa.msg });
  const pc = await call(T, `/api/his/price-adjust/${paId}/confirm`, 'POST');
  step('P3-生效', pc.code === 0 && pc.data && pc.data.status === 1, { code: pc.code, msg: pc.msg });
  const stk2 = await call(T, `/api/his/stock/page?warehouseId=${warehouse.id}&page=1&size=50`);
  const recs2 = (stk2.data && (stk2.data.records || stk2.data)) || [];
  const upd = recs2.find(r => r.drugCatalogId === drugId);
  step('P3-在库零售价同步为新价', !!upd && near(upd.retailPrice, newRetail), { got: upd && upd.retailPrice, want: newRetail });

  /* ============ P4 台账: 恒等式 期初+入-出=期末 ============ */
  const lg = await call(T, `/api/his/stock/ledger?warehouseId=${warehouse.id}&startDate=2020-01-01&endDate=2099-12-31`);
  const rows = lg.data && lg.data.rows || [];
  let identOk = lg.code === 0 && rows.length > 0;
  for (const r of rows) {
    if (!near(num(r.openingQty) + num(r.inQty) - num(r.outQty), num(r.closingQty), 0.001)) { identOk = false; break; }
  }
  step('P4-台账恒等式(逐行 期初+入-出=期末)', identOk, { code: lg.code, rows: rows.length });

  /* ============ P5 追溯码: 采集 -> 绑定 -> 报送 -> 统计 ============ */
  const col = await call(T, '/api/his/trace/collect', 'POST', { locationId: warehouse.id, drugCatalogId: drugId, batchNo: batch, autoGenerateQty: 3 });
  step('P5-采集3码', col.code === 0 && num(col.data.collected) === 3, { code: col.code, msg: col.msg, data: col.data });
  const tp = await call(T, `/api/his/trace/page?locationId=${warehouse.id}&drugCatalogId=${drugId}&status=0&page=1&size=10`);
  const codes = ((tp.data && tp.data.records) || []).map(x => x.traceCode).slice(0, 2);
  const bind = await call(T, '/api/his/trace/bind', 'POST', { traceCodes: codes, dispenseId: 1, patientId: 1, visitId: 1, requiredPackQty: 2 });
  step('P5-绑定发药(2码)', bind.code === 0 && num(bind.data.bound) === 2, { code: bind.code, msg: bind.msg, data: bind.data });
  const up = await call(T, '/api/his/trace/upload?locationId=' + warehouse.id, 'POST');
  step('P5-Mock报送(已发药未报=2)', up.code === 0 && num(up.data.uploaded) === 2, { code: up.code, data: up.data });
  const st = await call(T, '/api/his/trace/statistics?locationId=' + warehouse.id);
  step('P5-统计可读', st.code === 0 && st.data && num(st.data.uploaded) >= 2, { code: st.code, data: st.data });

  console.log('\n==== P-CHAIN SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('CHAIN_CRASH', e); process.exit(2); });
