/* 换号(changeSlot)端到端冒烟: 登录 -> 找 status=1 挂号 -> 找可用目标号源 -> 换号 -> 校验原单已退/新单已挂 */
(async function () {
  const BASE = 'http://localhost:8080';
  let tok = '';
  async function api(method, path, body) {
    const opt = { method, headers: {} };
    if (tok) { opt.headers['Authorization'] = 'Bearer ' + tok; }
    if (body) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    const r = await fetch(BASE + path, opt);
    return r.json();
  }
  function ok(name, cond, extra) {
    console.log((cond ? 'PASS' : 'FAIL') + ' | ' + name + (extra ? ' | ' + extra : ''));
    if (!cond) { process.exitCode = 1; }
  }
  // 1. 登录
  const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  if (!lg.data || !lg.data.token) { console.log('FAIL | 登录 ' + JSON.stringify(lg).slice(0, 200)); process.exitCode = 1; return; }
  tok = lg.data.token;
  console.log('PASS | 登录');
  // 2. 取一条今日 status=1 挂号
  const today = new Date().toISOString().slice(0, 10);
  const pg = await api('GET', '/api/his/registration/page?page=1&size=20&from=' + today + '&to=' + today + '&status=1');
  const rows = (pg.data && pg.data.records) || [];
  ok('存在已挂号记录', rows.length > 0, 'count=' + rows.length);
  if (!rows.length) { return; }
  const reg = rows[0];
  // 3. 找一个非原号源且有余号的开诊排班(近7天)
  const d7 = new Date(Date.now() + 6 * 86400000).toISOString().slice(0, 10);
  const sc = await api('GET', '/api/his/schedule/list?page=1&size=200&status=1&from=' + today + '&to=' + d7);
  const recs = (sc.data && sc.data.records) || sc.data || [];
  const target = recs.filter(function (s) { return (s.left_num != null ? s.left_num : s.leftNum) > 0 && String(s.id) !== String(reg.scheduleId); })[0];
  ok('存在可用目标号源', !!target, target ? ('scheduleId=' + target.id) : '');
  if (!target) { return; }
  // 4. 同号源拦截: 传原 scheduleId 应被拒
  const same = await api('POST', '/api/his/registration/changeSlot?id=' + reg.id + '&scheduleId=' + reg.scheduleId + '&reason=同号源测试');
  ok('同号源换号被拦截', same.code !== 0, 'msg=' + same.msg);
  // 5. 正常换号
  const cs = await api('POST', '/api/his/registration/changeSlot?id=' + reg.id + '&scheduleId=' + target.id + '&reason=冒烟换号');
  ok('换号成功', cs.code === 0 && cs.data && cs.data.newReg && cs.data.newReg.regNo,
    cs.code === 0 ? ('新单=' + cs.data.newReg.regNo + ' 候诊号=' + cs.data.newReg.queueNo + ' needRefund=' + cs.data.needRefund + ' 原单=' + cs.data.oldRegNo) : ('msg=' + cs.msg));
  if (cs.code !== 0) { return; }
  const nr = cs.data.newReg;
  ok('新单换号来源标记', nr.changeFromRegNo === cs.data.oldRegNo, 'changeFrom=' + nr.changeFromRegNo);
  // 6. 原单状态=2 且退号原因含换号; 新单状态=1
  const chk = await api('GET', '/api/his/registration/page?page=1&size=50&from=' + today + '&to=' + d7);
  const all = (chk.data && chk.data.records) || [];
  const oldRow = all.filter(function (r) { return r.id === reg.id; })[0];
  const newRow = all.filter(function (r) { return r.id === nr.id; })[0];
  ok('原单已退号(status=2)', oldRow && oldRow.status === 2, oldRow ? ('cancelReason=' + oldRow.cancelReason) : '未找到');
  ok('原单退号原因含换号', oldRow && /换号/.test(oldRow.cancelReason || ''));
  ok('新单已挂号(status=1)', newRow && newRow.status === 1, newRow ? ('regNo=' + newRow.regNo + ' scheduleId=' + newRow.scheduleId) : '未找到');
  ok('新单号源=目标号源', newRow && String(newRow.scheduleId) === String(target.id));
  // 7. 对已退的原单再次换号应被拒
  const again = await api('POST', '/api/his/registration/changeSlot?id=' + reg.id + '&scheduleId=' + target.id + '&reason=重复测试');
  ok('已退号记录换号被拦截', again.code !== 0, 'msg=' + again.msg);
})();
