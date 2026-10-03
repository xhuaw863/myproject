/* 安全探针预侦察: 租户列表/账号矩阵/角色档位 */
const J = async (p, m, b, t) => {
  const r = await fetch('http://localhost:8080' + p, { method: m || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, t ? { Authorization: 'Bearer ' + t } : {}),
    body: b ? JSON.stringify(b) : undefined });
  try { return await r.json(); } catch (e) { return { httpStatus: r.status }; }
};
const lg = await J('/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
const t = lg.data.token;
const tn = await J('/api/sys/tenant/page?page=1&size=10', null, null, t);
console.log('tenant resp:', JSON.stringify(tn).slice(0, 500));
const role = await J('/api/sys/role/list', null, null, t);
console.log('roles:', JSON.stringify(role && role.data).slice(0, 400));
for (const u of ['D002', 'XCRH0006', 'XCRH0001', 'nurse01', 'hyadmin']) {
  const r2 = await J('/api/auth/login', 'POST', { tenantCode: 'H42010000000', username: u, password: 'admin123' });
  console.log(u, '=> code', r2.code, r2.data ? ('role=' + r2.data.role + ' orgId=' + r2.data.orgId + ' lead=' + r2.data.leadOrg + ' staff=' + r2.data.staffId + ' home=' + r2.data.homeOrgId + ' allowed=' + JSON.stringify(r2.data.allowedOrgs)) : r2.msg);
}
