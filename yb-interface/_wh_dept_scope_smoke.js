/* 四期「药房/药库与科室一一对应 + 科室授权 + 当前库房范围」端到端冒烟(真实走 API, BASE=18081 自有实例)
 * 覆盖:
 *  A. 归属科室绑定与一一对应: 药房/药库保存带 deptId; 同科室重复绑定同类库房被 400; 编辑再存幂等不报错。
 *  B. 授权过滤与越权守卫(非牵头成员机构药师): warehouse-def 列表仅含授权科室库; 越权 warehouseId 读取(库存page)403; 授权放行。
 * 运行: node _wh_dept_scope_smoke.js   (需先以 18081 端口启动本实例, 见 README/mvn spring-boot:run)
 * 临时验证脚本: 只建自增测试数据(唯一 code/账号), 不做破坏性清理。 */
const BASE = process.env.WH_BASE || 'http://localhost:18081';
const TENANT = process.env.WH_TENANT || 'H42010000000';
const PWD = 'smoke123';
let FAIL = 0, PASS = 0, SKIP = 0;
function step(name, ok, detail) {
  if (ok === 'skip') { SKIP++; console.log('SKIP | ' + name + (detail ? ' | ' + JSON.stringify(detail) : '')); return; }
  if (ok) { PASS++; console.log('PASS | ' + name); }
  else { FAIL++; console.log('FAIL | ' + name + ' | ' + JSON.stringify(detail)); }
}
async function raw(path, method, tok, body) {
  const resp = await fetch(BASE + path, {
    method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, tok ? { Authorization: 'Bearer ' + tok } : {}),
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await resp.text();
  let json = null; try { json = JSON.parse(text); } catch (e) { /* 非 JSON(如 403 纯文本/HTML) */ }
  return { status: resp.status, json: json, text: text };
}
async function call(tok, path, method, body) { const r = await raw(path, method, tok, body); return r.json || { code: -1, msg: r.text }; }
async function login(username, password) {
  const r = await call(null, '/api/auth/login', 'POST', { tenantCode: TENANT, username: username, password: password });
  return r && r.data && r.data.token;
}
const num = v => Number(v == null ? 0 : v);
const code = v => (v && v.code);
const is403 = r => r.status === 403 || num(code(r)) === 403 || (r.json ? false : String(r.text).indexOf('403') >= 0);
// 403 判定(经 R 包装时 code=403; 若框架直接返回 HTTP 403 则 status)
function biz403(j) { return num(code(j)) === 403 || (j && /无权操作/.test(j.msg || '')); }

(async () => {
  const T = await login('admin', 'admin123');
  step('00-admin登录', !!T, { ok: !!T });
  if (!T) process.exit(1);

  const uniq = 'WHDEPT' + Date.now().toString().slice(-8);

  /* ===== 预备: 取机构(牵头 + 成员) ===== */
  const orgs = (await call(T, '/api/sys/org/list')).data || [];
  const leadOrg = (orgs.find(o => num(o.isLead) === 1) || {});
  const leadOrgId = leadOrg.id;
  const memberOrg = (orgs.find(o => num(o.isLead) !== 1) || {});
  const memberOrgId = memberOrg.id;
  step('01-存在牵头机构', !!leadOrgId, { leadOrgId });

  /* 建科室helper: POST /api/his/dept(requireLeadWrite, admin 通过); create 返回 Void, 需回查 enabled 取 id */
  async function mkDept(orgId, name, cat) {
    const dc = uniq + '-' + name;
    const cr = await call(T, '/api/his/dept', 'POST', {
      orgId: orgId, parentId: 0, deptCode: dc, deptName: name + uniq, deptType: '医技',
      deptCategory: cat || '医技科室', deptLevel: 1, status: 1
    });
    if (num(code(cr)) !== 0) { return { err: cr }; }
    const list = (await call(T, '/api/his/dept/enabled?orgId=' + orgId)).data || [];
    const hit = list.find(d => d.deptCode === dc) || {};
    return { id: hit.id, code: dc };
  }
  /* 建药库/药房 */
  function mkWarehouse(orgId, dCode, dName, deptId) {
    return call(T, '/api/his/stock/warehouse-def', 'POST',
      { orgId: orgId, code: dCode, name: dName, warehouseType: 'MIXED', status: 1, sortNo: 0, deptId: deptId });
  }
  function mkPharmacy(orgId, dCode, dName, deptId) {
    return call(T, '/api/his/pharmacy/pharmacy-def', 'POST',
      { orgId: orgId, code: dCode, name: dName, pharmacyType: 'OUTPATIENT', status: 1, sortNo: 0, deptId: deptId });
  }

  /* ===== A. 归属科室绑定 + 一一对应校验(牵头机构内) ===== */
  const dA = await mkDept(leadOrgId, '药库A科', '医技科室');
  const dB = await mkDept(leadOrgId, '药库B科', '医技科室');
  step('A0-建两个测试科室', !!dA.id && !!dB.id, { dA: dA.id || dA.err, dB: dB.id || dB.err });

  const wA = uniq + '-WA';
  const rwA = await mkWarehouse(leadOrgId, wA, '授权药库A' + uniq, dA.id);
  step('A1-药库绑定科室A成功', num(code(rwA)) === 0 && !!(rwA.data && rwA.data.id), { code: code(rwA), msg: rwA.msg });
  const whAId = rwA.data && rwA.data.id;

  const rwA2 = await mkWarehouse(leadOrgId, uniq + '-WA2', '重复绑A' + uniq, dA.id);
  step('A2-同科室再绑第二药库被拒(一一对应)', num(code(rwA2)) !== 0 && /一一对应|已绑定/.test(rwA2.msg || ''), { code: code(rwA2), msg: rwA2.msg });

  const rwB = await mkWarehouse(leadOrgId, uniq + '-WB', '其它药库B' + uniq, dB.id);
  step('A3-另一科室B绑定药库成功', num(code(rwB)) === 0, { code: code(rwB), msg: rwB.msg });
  const whBId = rwB.data && rwB.data.id;

  // 编辑幂等: 把 A 库改存(同 dept A)不报"重复绑定"(排除自身)
  const reSave = await call(T, '/api/his/stock/warehouse-def', 'POST',
    { id: whAId, orgId: leadOrgId, code: wA, name: '授权药库A改' + uniq, warehouseType: 'MIXED', status: 1, sortNo: 0, deptId: dA.id });
  step('A4-编辑重存同科室不自我碰撞(幂等)', num(code(reSave)) === 0, { code: code(reSave), msg: reSave.msg });

  // 药房一一对应
  const dp1 = await mkDept(leadOrgId, '药房科P', '医技科室');
  const rp1 = await mkPharmacy(leadOrgId, uniq + '-P1', '测试药房1' + uniq, dp1.id);
  step('A5-药房绑定科室成功', num(code(rp1)) === 0, { code: code(rp1), msg: rp1.msg });
  const rp2 = await mkPharmacy(leadOrgId, uniq + '-P2', '测试药房2' + uniq, dp1.id);
  step('A6-同科室再绑第二药房被拒', num(code(rp2)) !== 0 && /一一对应|已绑定/.test(rp2.msg || ''), { code: code(rp2), msg: rp2.msg });

  // 管理员(牵头, 不受限)列表可见全部
  const adminWh = (await call(T, '/api/his/stock/warehouse-def?orgId=' + leadOrgId)).data || [];
  step('A7-牵头管理员warehouse-def可见授权库A', adminWh.some(w => num(w.id) === num(whAId)), { count: adminWh.length });

  /* ===== B. 非牵头成员机构药师: 授权过滤 + 越权403 ===== */
  if (!memberOrgId) {
    step('B0-成员机构药师越权', 'skip', '库内无 is_lead=0 成员机构, 跳过越权过滤用例');
  } else {
    const mA = await mkDept(memberOrgId, '成员授权科A', '医技科室');
    const mB = await mkDept(memberOrgId, '成员非授权科B', '医技科室');
    // 成员库房由牵头 admin 代建(写守卫仅牵头可写), 归属成员机构
    const mwA = await mkWarehouse(memberOrgId, uniq + '-MA', '成员授权库A' + uniq, mA.id);
    const mwB = await mkWarehouse(memberOrgId, uniq + '-MB', '成员越权库B' + uniq, mB.id);
    const mwAId = mwA.data && mwA.data.id, mwBId = mwB.data && mwB.data.id;
    step('B1-成员机构建两药库(各绑科室)', num(code(mwA)) === 0 && num(code(mwB)) === 0, { a: code(mwA), b: code(mwB) });

    // 建药师用户: 归属成员机构, 授权科室= mA(仅)
    const user = 'phm' + Date.now().toString().slice(-8);
    const cu = await call(T, '/api/sys/user', 'POST', {
      username: user, password: PWD, realName: '范围药师' + uniq, role: 'PHARMACIST',
      orgId: memberOrgId, deptId: mA.id, deptScope: String(mA.id), status: 1
    });
    step('B2-创建授权药师(仅科室A)', num(code(cu)) === 0, { code: code(cu), msg: cu.msg });

    const UT = await login(user, PWD);
    step('B3-药师登录成功', !!UT, { ok: !!UT });
    if (UT) {
      const uWh = (await call(UT, '/api/his/stock/warehouse-def')).data || [];
      const ids = uWh.map(w => num(w.id));
      step('B4-药师warehouse-def含授权库A', ids.indexOf(num(mwAId)) >= 0, { ids: ids });
      step('B5-药师warehouse-def不含越权库B', ids.indexOf(num(mwBId)) < 0, { ids: ids });

      const okRead = await call(UT, '/api/his/stock/page?warehouseId=' + mwAId + '&page=1&size=1');
      step('B6-药师读取授权库A库存放行', num(code(okRead)) === 0, { code: code(okRead), msg: okRead.msg });
      const noRead = await raw('/api/his/stock/page?warehouseId=' + mwBId + '&page=1&size=1', 'GET', UT);
      step('B7-药师读取越权库B库存被拒403', biz403(noRead.json) || is403(noRead), { status: noRead.status, code: code(noRead.json), msg: noRead.json && noRead.json.msg });

      // 越权写: 对库B新建盘点(即便被 requireLeadWrite 先拦也不应放行→非0)
      const noChk = await call(UT, '/api/his/stock/check?warehouseId=' + mwBId, 'POST', {});
      step('B8-药师对越权库B发起盘点被拒(非0)', num(code(noChk)) !== 0, { code: code(noChk), msg: noChk.msg });
    }
  }

  console.log('\n==== WH-DEPT-SCOPE SUMMARY: ' + PASS + ' PASS / ' + FAIL + ' FAIL / ' + SKIP + ' SKIP ====');
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('WH_DEPT_CRASH', e); process.exit(2); });
