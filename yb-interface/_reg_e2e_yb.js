/* 医保挂号业务端到端冒烟: 2201挂号 -> 重复拦截 -> 2202退号 -> 号源回滚 -> 重新挂号
 * 运行: node _reg_e2e_yb.js   (需应用已在 8080 运行)
 */
const BASE = 'http://localhost:8080';
let TOKEN = null;
const results = [];

function step(name, pass, detail) {
  results.push({ name, pass, detail });
  console.log((pass ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + JSON.stringify(detail));
}

async function api(path, method = 'GET') {
  const resp = await fetch(BASE + path, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' }, TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {})
  });
  return await resp.json();
}

(async () => {
  /* 1. 登录 */
  const lr = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username: 'admin', password: 'admin123' })
  }).then(r => r.json());
  TOKEN = lr.data && lr.data.token;
  step('01-登录', !!TOKEN, { code: lr.code });
  if (!TOKEN) { console.log('登录失败, 终止'); process.exit(1); }

  /* 2. 找今日有剩余号且医保编码齐全的号源 */
  const today = new Date().toISOString().slice(0, 10);
  const sl = await api('/api/his/schedule/list?page=1&size=200');
  const rows = (sl.data && sl.data.records ? sl.data.records : []).map(s => ({
    id: s.id, deptName: s.deptName || s.dept_name, ybDeptCode: s.ybDeptCode || s.yb_dept_code,
    staffName: s.staffName || s.staff_name, atddrNo: s.atddrNo || s.atddr_no,
    workDate: String(s.workDate || s.work_date), timeType: s.timeType || s.time_type,
    leftNum: s.leftNum != null ? s.leftNum : s.left_num, totalNum: s.totalNum != null ? s.totalNum : s.total_num,
    status: s.status
  }));
  const cand = rows.filter(r => r.workDate === today && r.status === 1 && r.leftNum > 0 && r.ybDeptCode && r.atddrNo);
  step('02-可用号源(医保编码齐全)', cand.length > 0, { today, count: cand.length, first: cand[0] });
  if (!cand.length) { console.log(JSON.stringify(results, null, 2)); return; }
  const sched = cand[0];

  /* 3. 选患者: 逐个试, 找今日未挂过该号源的 */
  const pl = await api('/api/his/patient/page?page=1&size=30');
  const patients = (pl.data && (pl.data.records || pl.data)) || [];
  let patient = null;
  for (const p of patients) {
    const cd = await api('/api/his/registration/checkDuplicate?patientId=' + p.id + '&scheduleId=' + sched.id);
    if (cd.data && !cd.data.duplicate) { patient = p; break; }
  }
  step('03-可用患者', !!patient, { id: patient && patient.id, name: patient && (patient.name || patient.patientName), psnNo: patient && patient.psnNo });

  /* 4. 挂号(2201) */
  const before = sched.leftNum;
  const reg = await api('/api/his/registration/register?patientId=' + patient.id + '&scheduleId=' + sched.id + '&medType=11&discountType=none&payMethod=free', 'POST');
  const r = reg.data || {};
  step('04-挂号成功(2201)', reg.code === 0 && !!r.regNo, { code: reg.code, msg: reg.msg, regNo: r.regNo, mdtrtId: r.mdtrtId, queueNo: r.queueNo, deptCode: r.deptCode, atddrNo: r.atddrNo, actualFee: r.actualFee });

  /* 5. 号源扣减校验 */
  const sl2 = await api('/api/his/schedule/list?page=1&size=200');
  const s2 = (sl2.data.records || []).map(x => ({ id: x.id, left: x.leftNum != null ? x.leftNum : x.left_num })).find(x => x.id === sched.id);
  step('05-号源扣减1', s2 && s2.left === before - 1, { before, after: s2 && s2.left });

  /* 6. 重复挂号拦截 */
  const dup = await api('/api/his/registration/register?patientId=' + patient.id + '&scheduleId=' + sched.id + '&medType=11&discountType=none&payMethod=free', 'POST');
  step('06-重复挂号被拦截', dup.code !== 0, { code: dup.code, msg: dup.msg });

  /* 7. 退号(2202) */
  const cancel = await api('/api/his/registration/cancel?id=' + r.id + '&reason=e2e冒烟退号', 'POST');
  step('07-退号成功(2202)', cancel.code === 0, { code: cancel.code, msg: cancel.msg, data: cancel.data });

  /* 8. 号源回滚 */
  const sl3 = await api('/api/his/schedule/list?page=1&size=200');
  const s3 = (sl3.data.records || []).map(x => ({ id: x.id, left: x.leftNum != null ? x.leftNum : x.left_num })).find(x => x.id === sched.id);
  step('08-退号后号源回滚', s3 && s3.left === before, { expect: before, actual: s3 && s3.left });

  /* 9. 已退号记录重新挂号(reRegister 复用2201, 生成新记录) */
  const re = await api('/api/his/registration/reRegister?id=' + r.id, 'POST');
  step('09-重新挂号(新记录)', re.code === 0 && re.data && re.data.status === 1, { code: re.code, msg: re.msg, oldId: r.id, newId: re.data && re.data.id, newMdtrtId: re.data && re.data.mdtrtId, status: re.data && re.data.status });

  /* 10. 收尾: 退掉新记录, 恢复现场 */
  const newId = re.data && re.data.id;
  const cancel2 = await api('/api/his/registration/cancel?id=' + newId + '&reason=e2e冒烟清理', 'POST');
  step('10-收尾退号(恢复现场)', cancel2.code === 0, { code: cancel2.code, msg: cancel2.msg });

  console.log('\n===== 汇总: ' + results.filter(x => x.pass).length + '/' + results.length + ' PASS =====');
})().catch(e => { console.error('E2E异常:', e); process.exit(1); });
