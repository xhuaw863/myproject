/* Task12 药库后端增强冒烟: 默认库自动创建 -> 按库入库 -> 盘点(盘盈+盘亏) -> 确认生成盈亏单据
 * -> 药库启停守卫 -> 按库过滤。运行: node _task12_smoke.js (需应用已在 8080 运行)
 */
const BASE = 'http://localhost:8080';
const ORG = 1;
let TOKEN = null;
const results = [];
let fail = 0;

function step(name, pass, detail) {
  results.push({ name, pass, detail });
  if (!pass) fail++;
  console.log((pass ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + JSON.stringify(detail));
}

async function api(path, method = 'GET', body) {
  const resp = await fetch(BASE + path, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' },
      TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}),
    body: body ? JSON.stringify(body) : undefined
  });
  return await resp.json();
}

(async () => {
  /* 1. 登录(牵头机构 admin) */
  const lr = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username: 'admin', password: 'admin123' })
  }).then(r => r.json());
  TOKEN = lr.data && lr.data.token;
  step('01-登录', !!TOKEN, { code: lr.code });
  if (!TOKEN) { console.log('登录失败'); process.exit(1); }

  /* 2. 药库列表: 首次访问应自动创建默认药库(存量库存回填归属) */
  const wl = await api('/api/his/stock/warehouse-def?orgId=' + ORG);
  const whs = (wl.data || []).filter(w => w.orgId === ORG);
  const defWh = whs.find(w => w.code === 'DEFAULT');
  step('02-默认药库自动创建', !!defWh, { count: whs.length, defWh });
  if (!defWh) { process.exit(1); }
  const WH = defWh.id;

  /* 3. 药品目录分页(机构开展 + WESTERN 过滤 + keyword) */
  const dc = await api('/api/his/stock/drug-catalog?orgId=' + ORG + '&warehouseType=WESTERN&page=1&size=5&keyword=');
  const drugs = (dc.data && dc.data.records) || [];
  step('03-药品目录分页', dc.code === 0 && drugs.length >= 2, { total: dc.data && dc.data.total, first: drugs[0] && { id: drugs[0].id, code: drugs[0].drugCode, name: drugs[0].genericName } });
  const kw = await api('/api/his/stock/drug-catalog?orgId=' + ORG + '&keyword=' + encodeURIComponent((drugs[0] || {}).genericName || 'x'));
  step('03b-keyword检索', kw.code === 0 && ((kw.data && kw.data.total) || 0) >= 1, { total: kw.data && kw.data.total });

  /* 4. 指定药库入库两个药品并确认(新库存行应带 warehouse_id) */
  const d1 = drugs[0], d2 = drugs[1];
  const batchA = 'TASK12A', batchB = 'TASK12B';
  const inReq = {
    orgId: ORG, warehouseId: WH, inType: 1, supplier: '冒烟供应商',
    items: [
      { drugCatalogId: d1.id, drugCode: d1.drugCode, drugName: d1.genericName, spec: d1.spec, batchNo: batchA, qty: 10, costPrice: 5.00, retailPrice: 8.00, expDate: '2027-12-31' },
      { drugCatalogId: d2.id, drugCode: d2.drugCode, drugName: d2.genericName, spec: d2.spec, batchNo: batchB, qty: 8, costPrice: 4.00, retailPrice: 6.50, expDate: '2027-12-31' }
    ]
  };
  const cr = await api('/api/his/stock/in', 'POST', inReq);
  const inId = cr.data && cr.data.id;
  step('04-创建入库单(带药库)', cr.code === 0 && !!inId, { code: cr.code, inNo: cr.data && cr.data.inNo, wh: cr.data && cr.data.warehouseId });
  const cf = await api('/api/his/stock/in/' + inId + '/confirm', 'POST');
  step('04b-确认入库', cf.code === 0, { code: cf.code });

  /* 4c. 按库过滤库存: 两行 TEST 批次都应属默认库 */
  const sp = await api('/api/his/stock/page?orgId=' + ORG + '&warehouseId=' + WH + '&keyword=TASK12&size=50');
  const srows = (sp.data && sp.data.records) || [];
  step('04c-库存按库过滤+关键字', srows.length === 2 && srows.every(r => r.warehouseId === WH),
    { rows: srows.map(r => ({ b: r.batchNo, q: r.qty, w: r.warehouseId })) });

  /* 5. 创建盘点单(快照整库含 TEST 批次) */
  const cc = await api('/api/his/stock/check?orgId=' + ORG + '&warehouseId=' + WH, 'POST');
  const check = cc.data || {};
  step('05-创建盘点单', cc.code === 0 && /^PD\d{12}$/.test(check.checkNo || ''), { code: cc.code, checkNo: check.checkNo, status: check.status, checkBy: check.checkBy });
  const dup = await api('/api/his/stock/check?orgId=' + ORG + '&warehouseId=' + WH, 'POST');
  step('05b-重复盘点被拒', dup.code !== 0, { code: dup.code, msg: dup.msg });

  /* 6. 盘点详情: 快照明细包含两个 TEST 批次 */
  const cd = await api('/api/his/stock/check/' + check.id);
  const items = (cd.data && cd.data.items) || [];
  const itA = items.find(i => i.batchNo === batchA), itB = items.find(i => i.batchNo === batchB);
  step('06-盘点详情快照', !!itA && !!itB && itA.systemQty === 10 && itB.systemQty === 8,
    { items: items.length, itA: itA && { id: itA.id, sys: itA.systemQty, actual: itA.actualQty, diff: itA.diffQty } });

  /* 7. 录入实盘: A 盘盈+2, B 盘亏-1 */
  const ua = await api('/api/his/stock/check/' + check.id + '/item/' + itA.id + '?actualQty=12', 'PUT');
  step('07-录入实盘A(盘盈+2)', ua.code === 0, { code: ua.code });
  const ub = await api('/api/his/stock/check/' + check.id + '/item/' + itB.id + '?actualQty=7', 'PUT');
  step('07b-录入实盘B(盘亏-1)', ub.code === 0, { code: ub.code });
  const cd2 = await api('/api/his/stock/check/' + check.id);
  const itA2 = cd2.data.items.find(i => i.id === itA.id), itB2 = cd2.data.items.find(i => i.id === itB.id);
  step('07c-差异自动计算', Number(itA2.diffQty) === 2 && Number(itB2.diffQty) === -1,
    { diffA: itA2.diffQty, diffB: itB2.diffQty });

  /* 8. 确认盘点: 盘盈10.00(2*5), 盘亏4.00(1*4) */
  const ok = await api('/api/his/stock/check/' + check.id + '/confirm', 'POST');
  step('08-确认盘点', ok.code === 0, { code: ok.code, msg: ok.msg });
  const cd3 = await api('/api/his/stock/check/' + check.id);
  const m = cd3.data.main || {};
  step('08b-主表完成态+金额', m.status === 1 && Number(m.profitAmount) === 10.00 && Number(m.lossAmount) === 4.00,
    { status: m.status, profit: m.profitAmount, loss: m.lossAmount, confirmBy: m.confirmBy, confirmTime: m.confirmTime });

  /* 8c. 确认后不可再录/作废 */
  const ua2 = await api('/api/his/stock/check/' + check.id + '/item/' + itA.id + '?actualQty=99', 'PUT');
  step('08c-确认后录入被拒', ua2.code !== 0, { code: ua2.code, msg: ua2.msg });

  /* 9. 库存核对: A=12, B=7; 出入库流水按库过滤 */
  const sp2 = await api('/api/his/stock/page?orgId=' + ORG + '&warehouseId=' + WH + '&keyword=TASK12&size=50');
  const q = {};
  (sp2.data.records || []).forEach(r => q[r.batchNo] = Number(r.qty));
  step('09-盘盈亏落账', q[batchA] === 12 && q[batchB] === 7, q);
  const fl = await api('/api/his/stock/flow?orgId=' + ORG + '&warehouseId=' + WH + '&size=50');
  const flows = (fl.data && fl.data.records) || [];
  const fIn = flows.find(f => f.billType === 3 && f.flowType === 'IN'), fOut = flows.find(f => f.billType === 3 && f.flowType === 'OUT');
  step('09b-流水含盘盈亏单', !!fIn && !!fOut, { fIn: fIn && { billNo: fIn.billNo, qty: fIn.qty, opTime: fIn.opTime }, fOut: fOut && { billNo: fOut.billNo, qty: fOut.qty } });

  /* 10. 盘点记录分页 */
  const cp = await api('/api/his/stock/check/page?orgId=' + ORG + '&warehouseId=' + WH + '&page=1&size=10');
  step('10-盘点记录分页', cp.code === 0 && ((cp.data && cp.data.total) || 0) >= 1, { total: cp.data && cp.data.total });

  /* 11. 药库维护: 新增 TESTWH -> 空库停用OK -> 默认库有库存停用被拒 */
  const sv = await api('/api/his/stock/warehouse-def', 'POST',
    { orgId: ORG, code: 'TESTWH', name: '冒烟测试库', warehouseType: 'WESTERN', location: '测试位置', manager: '测试员', sortNo: 9 });
  const testWh = sv.data || {};
  step('11-新增药库', sv.code === 0 && !!testWh.id, { id: testWh.id, code: testWh.code, status: testWh.status });
  const dupCode = await api('/api/his/stock/warehouse-def', 'POST', { orgId: ORG, code: 'TESTWH', name: '重复编码' });
  step('11b-编码唯一校验', dupCode.code !== 0, { code: dupCode.code, msg: dupCode.msg });
  const tg1 = await api('/api/his/stock/warehouse-def/' + testWh.id + '/toggle?enabled=false', 'POST');
  step('11c-空库停用', tg1.code === 0, { code: tg1.code });
  const tg2 = await api('/api/his/stock/warehouse-def/' + testWh.id + '/toggle?enabled=true', 'POST');
  step('11d-重新启用', tg2.code === 0, { code: tg2.code });
  const wl2 = await api('/api/his/stock/warehouse-def?orgId=' + ORG + '&includeDisabled=true');
  const stopped = (wl2.data || []).find(w => w.id === testWh.id);
  step('11e-includeDisabled含全部', !!stopped && stopped.status === 1, { status: stopped && stopped.status });
  const tg3 = await api('/api/his/stock/warehouse-def/' + WH + '/toggle?enabled=false', 'POST');
  step('11f-有库存库停用被拒', tg3.code !== 0, { code: tg3.code, msg: tg3.msg });

  /* 清理: 删冒烟测试库(停用态, 已无引用) —— 保留数据不删, 供后续观察 */
  console.log('\n==== SUMMARY: ' + (results.length - fail) + '/' + results.length + ' PASS ====');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('SMOKE_CRASH', e); process.exit(2); });
