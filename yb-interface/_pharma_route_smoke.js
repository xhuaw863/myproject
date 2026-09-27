/* 三期「发药药房路由与药房维度定价」端到端冒烟(真实走 API, BASE=18081 自有实例)
 * 覆盖: 科室默认解析 → 开方绑房+服务端按房重算价(前端故意传错价被覆盖) → 按房取数软提示(effPrice/stockQty)
 *       → 发药价差预览+落账(price_diff) → 人为绑无库存房造不足 → shortage/transfer-options → transfer 改派
 *       → 改派后发药成功(回退处方药房) + transfer_from 留痕核对 → 未收费处方拒绝改派守卫 → 药房定价页/回落。
 * 临时验证脚本, 仅只读+建测试数据, 收尾清空自设覆盖价。运行: node _pharma_route_smoke.js
 */
const BASE = process.env.PHARMA_BASE || 'http://localhost:18081';
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
const near = (a, b, eps) => Math.abs(num(a) - num(b)) <= (eps == null ? 0.011 : eps);
// JdbcTemplate 行可能 snake_case 或 camelCase, 两取其一
const pick = (o, k) => (o && (o[k] != null ? o[k] : o[k.replace(/_([a-z])/g, (m, c) => c.toUpperCase())]));

// 固定测试夹具(见 _probe 实际数据)
const ORG = 1;
const VISIT_CHARGED = 5;   // 内科门诊 charge_status=1
const VISIT_UNCHARGED = 28; // 外科门诊 charge_status=0
const PH_A = 1;  // 默认门诊药房 stockLocationId=7 (drug5 有库存)
const PH_B = 4;  // 急诊药房 stockLocationId=10 (无库存, 造不足)
const PH_TCM = 2; // 中药房
const DRUG = 5;  // 盐酸雷尼替丁注射液 catalog retail=11.35, loc7 批次零售同为11.35
const KWM = encodeURIComponent('雷尼替丁'); // availableDrug/pricePage keyword 匹配名称(非id), 结果按 id DESC 故需精确词

(async () => {
  const T = await login();
  step('00-login', !!T, { ok: !!T });
  if (!T) process.exit(1);

  /* ===== A. 科室默认药房解析(建测试科室: 西药→A, 中药→TCM) ===== */
  const uniq = 'PHROUTE' + Date.now().toString().slice(-7);
  const deptReq = await call(T, '/api/his/dept', 'POST', {
    orgId: ORG, parentId: 0, deptCode: uniq, deptName: '路由测试科' + uniq, deptType: '临床',
    deptCategory: '门诊', deptLevel: 1, status: 1, openClinic: 1, defPharmacyWest: PH_A, defPharmacyTcm: PH_TCM
  });
  step('A1-建默认药房科室(b2校验通过)', deptReq.code === 0, { code: deptReq.code, msg: deptReq.msg });
  const enabled = await call(T, '/api/his/dept/enabled?orgId=' + ORG);
  const newDept = ((enabled.data || []).find(d => d.deptCode === uniq) || {});
  const deptId = newDept.id;
  step('A2-科室已落库并回填默认药房', !!deptId && num(newDept.defPharmacyWest) === PH_A, { deptId, west: newDept.defPharmacyWest });

  const rdWest = await call(T, '/api/his/pharmacy/resolve-default?deptId=' + deptId + '&rxType=' + encodeURIComponent('西药'));
  step('A3-resolve-default(西药)→A', rdWest.code === 0 && num(rdWest.data.pharmacyId) === PH_A, rdWest.data);
  const rdTcm = await call(T, '/api/his/pharmacy/resolve-default?deptId=' + deptId + '&rxType=' + encodeURIComponent('中药'));
  step('A4-resolve-default(中药)→TCM房', rdTcm.code === 0 && num(rdTcm.data.pharmacyId) === PH_TCM, rdTcm.data);
  const rdNone = await call(T, '/api/his/pharmacy/resolve-default?deptId=1&rxType=' + encodeURIComponent('西药'));
  step('A5-未配置科室回落 null', rdNone.code === 0 && rdNone.data.pharmacyId == null, rdNone.data);

  /* ===== B. 药房维度定价: 设覆盖价 → 按房取数 effPrice/stockQty 软提示 =====
   * availableDrug 仅返回本机构"开展"目录药(与 dispense 用的 DRUG=5 不同), 取其中唯一有A房库存的 500。
   */
  const OVERRIDE = 22.70;      // drug5@A: 驱动 C 段服务端重算价 + F 段定价页
  const AVAIL_DRUG = 500;      // 开展目录内含A房库存(stockQty>0)的药品
  const OVR_AVAIL = 50.50;     // drug500@A 覆盖价: 驱动 B 段 effPrice 软提示
  const saveP = await call(T, '/api/his/pharmacy/price/save', 'POST', { orgId: ORG, pharmacyId: PH_A, drugCatalogId: DRUG, retailPrice: OVERRIDE });
  step('B1-保存覆盖价 drug5@A', saveP.code === 0, { code: saveP.code, msg: saveP.msg });
  const saveP2 = await call(T, '/api/his/pharmacy/price/save', 'POST', { orgId: ORG, pharmacyId: PH_A, drugCatalogId: AVAIL_DRUG, retailPrice: OVR_AVAIL });
  step('B1b-保存覆盖价 500@A', saveP2.code === 0, { code: saveP2.code, msg: saveP2.msg });
  const availA = await call(T, '/api/org-catalog/available/drug?pharmacyId=' + PH_A + '&page=1&size=50');
  const rowsA = (availA.data && (availA.data.records || availA.data)) || [];
  const dr = rowsA.find(r => num(r.id) === AVAIL_DRUG) || {};
  step('B2-按房取数附 effPrice=覆盖价', near(dr.effPrice, OVR_AVAIL), { found: !!dr.id, effPrice: dr.effPrice });
  step('B3-按房取数附 stockQty>0(软提示)', num(dr.stockQty) > 0, { stockQty: dr.stockQty });
  const availNo = await call(T, '/api/org-catalog/available/drug?page=1&size=50');
  const drNo = (((availNo.data && (availNo.data.records || availNo.data)) || []).find(r => num(r.id) === AVAIL_DRUG) || {});
  step('B4-不传药房时无 effPrice(向后兼容)', drNo.effPrice == null, { found: !!drNo.id, effPrice: drNo.effPrice });

  /* ===== C. 开方绑房 + 服务端重算价(前端故意传错价 999) + 发药价差落账 ===== */
  const QTY = 2;
  const rx1 = await call(T, '/api/his/prescription/create', 'POST', {
    visitId: VISIT_CHARGED, rxType: '西药', pharmacyId: PH_A,
    items: [{ drugId: DRUG, itemCode: 'D5', itemName: '盐酸雷尼替丁注射液', spec: '50ml', unit: '支', price: 999, quantity: QTY }]
  });
  const rx1Id = rx1.data && rx1.data.id;
  const wantTotal = (OVERRIDE * QTY).toFixed(2); // 45.40
  step('C1-开方成功绑药房A', rx1.code === 0 && !!rx1Id && num(rx1.data.pharmacyId) === PH_A, { code: rx1.code, msg: rx1.msg, ph: rx1.data && rx1.data.pharmacyId });
  step('C2-服务端按房重算价(覆盖前端999)', near(rx1.data && rx1.data.totalAmount, wantTotal), { total: rx1.data && rx1.data.totalAmount, want: wantTotal });

  const pv = await call(T, '/api/his/pharmacy/dispense-preview?prescriptionId=' + rx1Id);
  step('C3-价差预览 hasPriceDiff=true', pv.code === 0 && pv.data.hasPriceDiff === true, pv.data);
  step('C4-预览计费=覆盖价×量', near(pv.data.billingAmount, wantTotal), { billing: pv.data && pv.data.billingAmount });
  step('C5-预览实发≈批次零售×量(11.35)', near(pv.data.estStockAmount, (11.35 * QTY).toFixed(2)), { est: pv.data && pv.data.estStockAmount });

  const disp1 = await call(T, '/api/his/pharmacy/dispense', 'POST', { prescriptionId: rx1Id, orgId: ORG, pharmacyId: PH_A, checkBy: 'route-smoke' });
  step('C6-发药成功', disp1.code === 0 && disp1.data && num(disp1.data.status) === 2, { code: disp1.code, msg: disp1.msg });
  const d1 = disp1.data || {};
  step('C7-发药记录价差落账 priceDiff=实发-计费', near(d1.priceDiff, num(d1.stockAmount) - num(d1.totalAmount)) && near(d1.totalAmount, wantTotal),
    { totalAmount: d1.totalAmount, stockAmount: d1.stockAmount, priceDiff: d1.priceDiff });
  step('C8-价差非0(房内批次价差)', near(num(d1.priceDiff), 0, 0.005) === false, { priceDiff: d1.priceDiff });

  /* ===== D. 人为绑无库存房造不足 → shortage → transfer-options → transfer → 改派后发药成功 ===== */
  const rx2 = await call(T, '/api/his/prescription/create', 'POST', {
    visitId: VISIT_CHARGED, rxType: '西药', pharmacyId: PH_B,
    items: [{ drugId: DRUG, itemCode: 'D5', itemName: '盐酸雷尼替丁注射液', spec: '50ml', unit: '支', price: 11.35, quantity: 10 }]
  });
  const rx2Id = rx2.data && rx2.data.id;
  step('D1-开方绑无库存房B成功(软提示不拦截)', rx2.code === 0 && !!rx2Id, { code: rx2.code, msg: rx2.msg });

  const sh = await call(T, '/api/his/pharmacy/shortage?prescriptionId=' + rx2Id + '&pharmacyId=' + PH_B);
  step('D2-shortage 诊断 allSufficient=false', sh.code === 0 && sh.data.allSufficient === false, sh.data);
  const shLine = ((sh.data && sh.data.lines) || [])[0] || {};
  step('D3-缺口明细 need>avail', num(shLine.needQty) === 10 && num(shLine.availQty) < 10, shLine);

  // 发药在B应失败(库存不足逐药清单)
  const dispFail = await call(T, '/api/his/pharmacy/dispense', 'POST', { prescriptionId: rx2Id, orgId: ORG, pharmacyId: PH_B });
  step('D4-B房发药被拒(库存不足)', dispFail.code !== 0 && String(dispFail.msg || '').indexOf('不足') >= 0, { code: dispFail.code, msg: dispFail.msg });

  const opts = await call(T, '/api/his/pharmacy/transfer-options?prescriptionId=' + rx2Id);
  const optList = (opts.data || []);
  const optA = optList.find(o => num(o.pharmacyId) === PH_A) || {};
  const optCur = optList.find(o => o.current === true) || {};
  step('D5-transfer-options 含当前房B(不满足)', opts.code === 0 && num(optCur.pharmacyId) === PH_B && optCur.allSufficient === false, { cur: optCur });
  step('D6-transfer-options 含A(全满足)', optA.allSufficient === true && optA.current === false, optA);

  const tr = await call(T, '/api/his/pharmacy/transfer', 'POST', { prescriptionId: rx2Id, toPharmacyId: PH_A });
  step('D7-改派 B→A 成功', tr.code === 0 && num(tr.data.toPharmacyId) === PH_A && num(tr.data.fromPharmacyId) === PH_B, { code: tr.code, msg: tr.msg, data: tr.data });

  // 改派后不带 pharmacyId 发药(回退处方绑定药房A)
  const disp2 = await call(T, '/api/his/pharmacy/dispense', 'POST', { prescriptionId: rx2Id, orgId: ORG, checkBy: 'route-smoke' });
  step('D8-改派后发药成功(回退处方药房A)', disp2.code === 0 && disp2.data && num(disp2.data.status) === 2 && num(disp2.data.pharmacyId) === PH_A,
    { code: disp2.code, msg: disp2.msg, ph: disp2.data && disp2.data.pharmacyId });
  step('D9-发药记录 transfer_from 留痕=B', num(disp2.data && disp2.data.transferFromPharmacyId) === PH_B, { from: disp2.data && disp2.data.transferFromPharmacyId });

  /* ===== E. 未收费处方拒绝改派守卫 ===== */
  const rx3 = await call(T, '/api/his/prescription/create', 'POST', {
    visitId: VISIT_UNCHARGED, rxType: '西药', pharmacyId: PH_B,
    items: [{ drugId: DRUG, itemCode: 'D5', itemName: '盐酸雷尼替丁注射液', spec: '50ml', unit: '支', price: 11.35, quantity: 1 }]
  });
  const rx3Id = rx3.data && rx3.data.id;
  const trGuard = await call(T, '/api/his/pharmacy/transfer', 'POST', { prescriptionId: rx3Id, toPharmacyId: PH_A });
  step('E1-未收费处方改派被拒', trGuard.code !== 0 && String(trGuard.msg || '').indexOf('已收费') >= 0, { code: trGuard.code, msg: trGuard.msg });

  /* ===== F. 药房定价分页(PharmacyPriceManage) + 清空回落目录价 ===== */
  const pg = await call(T, '/api/his/pharmacy/price/page?pharmacyId=' + PH_A + '&keyword=' + KWM + '&page=1&size=20');
  const pgRows = (pg.data && (pg.data.records || pg.data.rows || pg.data)) || [];
  const pgDr = (Array.isArray(pgRows) ? pgRows : []).find(r => num(r.id) === DRUG) || {};
  step('F1-定价页含药品行', pg.code === 0 && Object.keys(pgDr).length > 0, { code: pg.code, keys: Object.keys(pgDr) });
  step('F2-定价页 override=22.70 eff=22.70', near(pick(pgDr, 'override_price'), OVERRIDE) && near(pick(pgDr, 'eff_price'), OVERRIDE),
    { ov: pick(pgDr, 'override_price'), eff: pick(pgDr, 'eff_price') });

  const clr = await call(T, '/api/his/pharmacy/price/clear', 'POST', { pharmacyId: PH_A, drugCatalogId: DRUG });
  step('F3-清空覆盖价', clr.code === 0, { code: clr.code, msg: clr.msg });
  const pg2 = await call(T, '/api/his/pharmacy/price/page?pharmacyId=' + PH_A + '&keyword=' + KWM + '&page=1&size=20');
  const drAfter = (((pg2.data && pg2.data.records) || []).find(r => num(r.id) === DRUG) || {});
  step('F4-清空后回落目录价(11.35)', near(drAfter.eff_price, 11.35) && drAfter.override_price == null, { eff: drAfter.eff_price, ov: drAfter.override_price });
  // 收尾: 清空自设的开展药覆盖价, 恢复现场
  await call(T, '/api/his/pharmacy/price/clear', 'POST', { pharmacyId: PH_A, drugCatalogId: AVAIL_DRUG });

  console.log('\n==== PHARMA-ROUTE SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('PHARMA_CRASH', e); process.exit(2); });
