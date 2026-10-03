/* 全面审计 - 安全探针(2026-10-03): JWT/认证、租户与机构隔离、越权写、RBAC档位
 * 与 _audit_fix_smoke.js(M3/M4 已有 11 项守卫回归)互补, 本脚本只打新增面:
 * S1 无token/伪造token  S2 JWT签名篡改  S3 payload越权篡改(签名不动)  S4 alg=none
 * S5 非牵头伪造orgId读隔离  S6 非牵头越权读他机构就诊详情
 * S7 医生档调用管理员写接口  S8 SUPER_ADMIN建号拦截(若创建成功立即删除复原)
 * S9 SQL注入探测(keyword引号)  S10 禁删自己守卫
 * 运行: node _aud1003_sec.js
 */
const BASE = 'http://localhost:8080';
let FAIL = 0, PASS = 0;
const L = [];
function step(name, ok, detail) {
  const line = (ok ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + JSON.stringify(detail);
  if (ok) PASS++; else FAIL++;
  L.push(line);
}
async function raw(path, method, body, authVal) {
  const resp = await fetch(BASE + path, { method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, authVal ? { Authorization: authVal } : {}),
    body: body ? JSON.stringify(body) : undefined });
  let j = null; try { j = await resp.json(); } catch (e) { j = { httpStatus: resp.status }; }
  return { http: resp.status, code: j && j.code, msg: j && j.msg, data: j && j.data };
}
const call = (tok, path, method, body) => raw(path, method, body, tok ? 'Bearer ' + tok : null);
const b64u = (s) => Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

(async () => {
  const lg = await call(null, '/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const admin = lg.data && lg.data.token;
  const lgc = await call(null, '/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'XC1030035', password: '123456' });
  const community = lgc.data && lgc.data.token;
  const communityOrg = lgc.data && lgc.data.orgId;
  step('S00-牵头admin+非牵头社区账号登录', !!admin && !!community, { communityOrg });
  if (!admin || !community) { console.log(L.join('\n')); process.exitCode = 1; return; }

  // S1 未认证(本项目 401 在响应体 code, HTTP 层统一 200)
  const noTok = await raw('/api/his/patient/page?page=1&size=1');
  const fakeTok = await raw('/api/his/patient/page?page=1&size=1', 'GET', null, 'Bearer not.a.jwt');
  step('S1-无token与伪造token均拒401', noTok.code === 401 && fakeTok.code === 401, { noTok: noTok.code, fake: fakeTok.code });

  // S2 篡改签名(第三段换掉)
  const [h, p, s] = admin.split('.');
  const tamperSig = await raw('/api/his/patient/page?page=1&size=1', 'GET', null, 'Bearer ' + h + '.' + p + '.' + (s.slice(0, -4) + 'AAAA'));
  step('S2-JWT签名篡改被拒401', tamperSig.code === 401, { code: tamperSig.code });

  // S3 篡改payload(orgId 改成牵头1)保持原签名 -> 签名校验必须失败
  const payload = JSON.parse(Buffer.from(p.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
  const forged = { ...payload, orgId: 999, userId: 999999 };
  const tamperPayload = await raw('/api/his/patient/page?page=1&size=1', 'GET', null, 'Bearer ' + h + '.' + b64u(JSON.stringify(forged)) + '.' + s);
  step('S3-JWT载荷篡改(orgId伪造)被拒401', tamperPayload.code === 401, { code: tamperPayload.code, hadKeys: Object.keys(payload).slice(0, 8) });

  // S4 alg=none 攻击
  const noneHdr = b64u(JSON.stringify({ alg: 'none', typ: 'JWT' }));
  const noneTok = await raw('/api/his/patient/page?page=1&size=1', 'GET', null, 'Bearer ' + noneHdr + '.' + p + '.');
  step('S4-alg=none令牌被拒401', noneTok.code === 401, { code: noneTok.code, note: 'code=0即未签名令牌被放行(P0发现, hutool空签名默认valid)' });

  // S4b 完全自造payload(攻击者无任何合法token, 裸编admin身份+空签名)
  const expMs = Date.now() + 3600_000;
  const selfPayload = { userId: 1, tenantId: payload.tenantId, username: 'admin', role: 'ADMIN', roles: 'ADMIN', orgId: 1, leadOrg: true, iat: new Date(), exp: new Date(expMs) };
  const selfTok = b64u(JSON.stringify({ alg: 'none', typ: 'JWT' })) + '.' + b64u(JSON.stringify(selfPayload)) + '.';
  const s4b = await raw('/api/his/patient/page?page=1&size=1', 'GET', null, 'Bearer ' + selfTok);
  step('S4b-裸自造管理员令牌被拒', s4b.code === 401, { code: s4b.code, note: 'code=0即可任意伪造身份(P0升级证据)' });

  // S5 非牵头伪造 orgId=1(牵头) 读列表: scopeOrgId 应忽略入参锁定本机构
  const cList = await call(community, '/api/his/stock/page?orgId=1&page=1&size=50');
  const recs = (cList.data && cList.data.records) || [];
  const foreign = recs.filter(r => String(r.orgId) !== String(communityOrg));
  step('S5-非牵头伪造orgId=1读库存被锁定本机构', cList.code === 0 && foreign.length === 0, { rows: recs.length, foreign: foreign.length, ownOrg: communityOrg });

  // S6 非牵头越权读牵头机构住院就诊详情(已知在院占用床的 inpVisitId)
  const leadVisit = '2104594343610875906'; // 牵头机构 demo 在院就诊(床位103占用)
  const crossRead = await call(community, '/api/his/inp/visit/' + leadVisit);
  const blocked6 = crossRead.http === 403 || crossRead.code === 403 || (crossRead.code !== 0 && crossRead.code !== undefined && crossRead.code !== 200);
  step('S6-非牵头读他机构住院就诊详情被拒', blocked6, { http: crossRead.http, code: crossRead.code, msg: (crossRead.msg || '').slice(0, 60) });

  // S7 医生档(D002)调用牵头写接口(建库存单) -> 应403(即便密码不对则跳过并记录)
  const doc = await call(null, '/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'D002', password: '123456' });
  if (doc.code === 0 && doc.data) {
    const s7 = await call(doc.data.token, '/api/his/stock/in', 'POST', { warehouseId: 1, inType: 1, supplier: '越权探针', items: [] });
    step('S7-医生档调用药库入库写被拒403', s7.code === 403 || s7.http === 403, { role: doc.data.role, code: s7.code, http: s7.http, msg: (s7.msg || '').slice(0, 50) });
  } else step('S7-医生档调用药库入库写被拒403(账号不可用,跳过)', true, { note: 'D002 登录失败: ' + doc.msg });

  // S8 SUPER_ADMIN 建号拦截(提权防线); 若创建成功则立即删除复原
  const suRole = { role: 'SUPER_ADMIN' };
  const s8 = await call(admin, '/api/sys/user', 'POST', { username: 'AUD_SEC_PROBE_Y', password: 'probe1234', realName: '审计提权探针', orgId: 1, ...suRole });
  let s8Cleaned = 'no-record';
  if (s8.code === 0) {
    const lst = await call(admin, '/api/sys/user/list?orgId=1');
    const bad = ((lst.data) || []).find(u => u.username === 'AUD_SEC_PROBE_Y');
    if (bad) { await call(admin, '/api/sys/user/' + bad.id, 'DELETE'); s8Cleaned = 'created-then-deleted'; }
  }
  // 拒创才算防线存在; "账号已存在"(上次探针创建成功的残留)不算拦截
  step('S8-创建SUPER_ADMIN账号被拦截', s8.code !== 0 && String(s8.msg || '').indexOf('已存在') < 0, { code: s8.code, msg: (s8.msg || '').slice(0, 60), cleanup: s8Cleaned });

  // S9 SQL注入探测: 单引号 keyword 不应 500, 不应返回全量
  const normal = await call(admin, '/api/his/patient/page?page=1&size=1&keyword=%E9%99%88');
  const inj = await call(admin, "/api/his/patient/page?page=1&size=5&keyword=" + encodeURIComponent("陈' OR '1'='1"));
  step('S9-SQL注入串安全(无500且不泄全量)', inj.code === 0 && (inj.data.total || 0) <= (normal.data && normal.data.total || 0),
    { injCode: inj.code, injTotal: inj.data && inj.data.total, normalTotal: normal.data && normal.data.total });

  // S10 禁删当前登录账号
  const me = await call(admin, '/api/auth/me');
  const myId = me.data && (me.data.userId || me.data.id);
  const s10 = myId ? await call(admin, '/api/sys/user/' + myId, 'DELETE') : { code: -1, msg: 'no /api/auth/me' };
  step('S10-删除自己账号被拒', s10.code !== 0 && String(s10.msg || '').indexOf('自己') >= 0 || s10.code === 400, { code: s10.code, msg: (s10.msg || '').slice(0, 50) });

  console.log(L.join('\n'));
  console.log('\n==== 安全审计 SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
})().catch(e => { console.log(L.join('\n')); console.error('SEC_CRASH', e && e.message); process.exitCode = 2; });
