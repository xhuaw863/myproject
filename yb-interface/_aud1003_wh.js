/* 全面审计 - 药房药库闭环流程测试(2026-10-03, API层)
 * 链路: 药库/目录 -> 采购入库(草稿不动库存->确认+100) -> 出库(FIFO批次-10) -> 超出库守卫
 *       -> 盘点(快照->录实盘80->确认差异盈亏联动库存) -> 调价(药库域改批次价->复核->反向调回)
 *       -> 出入库流水核对 -> 药房待发药队列探针。
 * 运行: node _aud1003_wh.js  (需 8080 在跑)
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
const dstr = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');

(async () => {
  const login = await call(null, '/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const tk = login.data && login.data.token;
  step('00-登录 admin', !!tk, { code: login.code });
  if (!tk) { console.log(L.join('\n')); process.exitCode = 1; return; }

  // 01 药库(取第一个启用西药/综合库)
  const whs = await call(tk, '/api/his/stock/warehouse-def');
  const wh = (whs.data || []).find(w => (w.status === 1 || w.enabled === 1) && String(w.warehouseType || 'WESTERN') !== 'TCM') || (whs.data || [])[0];
  step('01-启用药库存在', whs.code === 0 && !!wh, { id: wh && wh.id, name: wh && (wh.warehouseName || wh.name), type: wh && wh.warehouseType });
  if (!wh) { console.log(L.join('\n')); process.exitCode = 1; return; }
  const WH = wh.id;

  // 02 药库可选药品目录(有零售价)
  const dc = await call(tk, '/api/his/stock/drug-catalog?page=1&size=50');
  const drug = ((dc.data && dc.data.records) || []).find(r => r.retailPrice != null && Number(r.retailPrice) > 0 && (r.status === 1 || r.status == null));
  step('02-药库目录可选药品', !!drug, { id: drug && drug.id, name: drug && drug.genericName, retail: drug && drug.retailPrice });
  if (!drug) { console.log(L.join('\n')); process.exitCode = 1; return; }

  const BATCH = 'AUD1003-' + Date.now();
  const findBatch = async () => {
    const sp = await call(tk, '/api/his/stock/page?warehouseId=' + WH + '&keyword=' + BATCH + '&page=1&size=10');
    const recs = (sp.data && sp.data.records) || [];
    return recs.find(r => String(r.batchNo) === String(BATCH)) || null;
  };
  const qtyOf = (r) => Number(r && (r.qty != null ? r.qty : r.stockQty) || -1);

  // 03 采购入库草稿(创建后库存应无该批次)
  const qBefore = qtyOf(await findBatch());
  const inReq = await call(tk, '/api/his/stock/in', 'POST', {
    warehouseId: WH, inType: 1, supplier: '审计供应商', supplierContact: '027-00000000', remark: '全面审计入库',
    purchaseMode: 1,
    items: [{ drugCatalogId: drug.id, drugCode: drug.drugCode, drugName: drug.genericName, batchNo: BATCH, spec: drug.spec, qty: 100, costPrice: 10.00, retailPrice: 12.00, prodDate: '2026-01-10', expDate: '2027-12-31' }]
  });
  const inId = inReq.data && inReq.data.id;
  const qDraft = qtyOf(await findBatch());
  step('03-入库草稿不改库存', inReq.code === 0 && !!inId && qDraft === -1 && qBefore === -1, { code: inReq.code, inId, stockAtDraft: qDraft });

  // 04 确认入库 -> +100
  const inCf = await call(tk, '/api/his/stock/in/' + inId + '/confirm', 'POST');
  const b1 = await findBatch();
  step('04-确认入库库存+100', inCf.code === 0 && qtyOf(b1) === 100, { code: inCf.code, qty: qtyOf(b1) });
  if (!b1) { console.log(L.join('\n')); process.exitCode = 1; return; }
  const stockRowId = b1.id;

  // 05 出库(报损)10 -> 批次90
  const outReq = await call(tk, '/api/his/stock/out', 'POST', {
    warehouseId: WH, outType: 2, remark: '审计报损出库',
    items: [{ drugStockId: stockRowId, drugCatalogId: drug.id, batchNo: BATCH, qty: 10 }]
  });
  const outId = outReq.data && outReq.data.id;
  let outCf = { code: -1 };
  if (outId) outCf = await call(tk, '/api/his/stock/out/' + outId + '/confirm', 'POST');
  const b2 = await findBatch();
  step('05-确认出库批次扣减至90', outReq.code === 0 && outCf.code === 0 && qtyOf(b2) === 90, { code: outReq.code, cf: outCf.code, msg: outCf.msg, qty: qtyOf(b2) });

  // 06 超库存出库守卫(5000>90 应被拒)
  const overOut = await call(tk, '/api/his/stock/out', 'POST', {
    warehouseId: WH, outType: 2, remark: '审计超出库', items: [{ drugStockId: stockRowId, drugCatalogId: drug.id, batchNo: BATCH, qty: 5000 }]
  });
  const overId = overOut.data && overOut.data.id;
  const overCf = overId ? await call(tk, '/api/his/stock/out/' + overId + '/confirm', 'POST') : overOut;
  const overBlocked = overOut.code !== 0 || (overId && overCf.code !== 0);
  if (overId && overCf.code !== 0) await call(tk, '/api/his/stock/out/' + overId, 'DELETE'); // 清理被拒草稿
  const b2b = await findBatch();
  step('06-超库存出库被拒且库存不变', overBlocked && qtyOf(b2b) === 90, { createCode: overOut.code, confirmCode: overCf.code, msg: overCf.msg || overOut.msg, qty: qtyOf(b2b) });

  // 07 盘点: 先清理本库历史遗留"进行中"盘点单(数据卫生) -> 快照 -> 实盘80 -> 确认盈亏联动
  const pgOld = await call(tk, '/api/his/stock/check/page?warehouseId=' + WH + '&page=1&size=50');
  const stale = ((pgOld.data && pgOld.data.records) || []).filter(r => Number(r.status) === 0); // 0=进行中(1已完成/9已作废)
  let voided = 0;
  for (const s of stale) { const vd = await call(tk, '/api/his/stock/check/' + s.id, 'DELETE'); if (vd.code === 0) voided++; }
  const ck = await call(tk, '/api/his/stock/check?warehouseId=' + WH, 'POST');
  const ckId = ck.data && ck.data.id;
  const ckd = await call(tk, '/api/his/stock/check/' + ckId);
  const itemsMap = ckd.data && (ckd.data.items || ckd.data.details) || [];
  const myItem = itemsMap.find(it => String(it.batchNo) === String(BATCH));
  let ckSet = null, ckCf = null, qtyAfterCk = -1;
  if (myItem) {
    ckSet = await call(tk, '/api/his/stock/check/' + ckId + '/item/' + myItem.id + '?actualQty=80', 'PUT');
    ckCf = await call(tk, '/api/his/stock/check/' + ckId + '/confirm', 'POST');
    qtyAfterCk = qtyOf(await findBatch());
  }
  step('07-盘点实盘80确认后库存校正', ck.code === 0 && !!myItem && ckSet && ckSet.code === 0 && ckCf && ckCf.code === 0 && qtyAfterCk === 80,
    { staleVoided: voided + '/' + stale.length, ckId: ckId, found: !!myItem, setCode: ckSet && ckSet.code, cfCode: ckCf && ckCf.code, qty: qtyAfterCk });

  // 08 调价(药库域): 批次零售 12->13 生效, 复核后反向调回 12
  const origRetail = Number((await findBatch()).retailPrice || 0);
  const pa1 = await call(tk, '/api/his/price-adjust', 'POST', {
    priceDomain: 'WAREHOUSE', targetWarehouseId: WH, scope: 'DRUG',
    effectiveDate: dstr(new Date()), reason: '审计调价链路验证',
    items: [{ drugCatalogId: drug.id, newRetail: 13 }]
  });
  const pa1Id = pa1.data && pa1.data.id;
  const pa1Cf = pa1Id ? await call(tk, '/api/his/price-adjust/' + pa1Id + '/confirm', 'POST') : pa1;
  const b3 = await findBatch();
  step('08a-调价单确认生效(批次价12->13)', pa1.code === 0 && pa1Cf.code === 0 && Number(b3.retailPrice) === 13,
    { code: pa1.code, cfCode: pa1Cf.code, msg: pa1Cf.msg, retail: b3 && b3.retailPrice, orig: origRetail });
  // 反向调回(保持 demo 数据原貌)
  const pa2 = await call(tk, '/api/his/price-adjust', 'POST', {
    priceDomain: 'WAREHOUSE', targetWarehouseId: WH, scope: 'DRUG',
    effectiveDate: dstr(new Date()), reason: '审计调价复原',
    items: [{ drugCatalogId: drug.id, newRetail: 12 }]
  });
  const pa2Id = pa2.data && pa2.data.id;
  const pa2Cf = pa2Id ? await call(tk, '/api/his/price-adjust/' + pa2Id + '/confirm', 'POST') : pa2;
  const b4 = await findBatch();
  step('08b-反向调价复原(批次价回12)', pa2.code === 0 && pa2Cf.code === 0 && Number(b4.retailPrice) === 12,
    { code: pa2.code, cfCode: pa2Cf.code, retail: b4 && b4.retailPrice });

  // 09 出入库流水覆盖: IN100 / OUT10 / 盘亏(均按 flowType 方向字段)
  const flow = await call(tk, '/api/his/stock/flow?warehouseId=' + WH + '&drugCatalogId=' + drug.id + '&page=1&size=50');
  const fr = ((flow.data && flow.data.records) || []).filter(r => String(r.batchNo) === String(BATCH));
  const inF = fr.filter(r => r.flowType === 'IN'), outF = fr.filter(r => r.flowType === 'OUT');
  step('09-流水含本批次入/出且方向正确', fr.length >= 3 && inF.length >= 1 && outF.length >= 2,
    { batchFlowRows: fr.length, in: inF.map(r => r.qty), out: outF.map(r => r.qty + ':' + r.billType) });

  // 10 药房待发药队列探针(只读, 记录不判分)
  const todo = await call(tk, '/api/his/pharmacy/todo?page=1&size=5');
  step('10-药房待发药队列可读', todo.code === 0, { code: todo.code, total: todo.data && todo.data.total });

  console.log(L.join('\n'));
  console.log('\n==== 药房药库闭环 SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
})().catch(e => { console.log(L.join('\n')); console.error('WH_FLOW_CRASH', e && e.message); process.exitCode = 2; });
