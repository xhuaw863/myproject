/* 手工测试门诊医生站预置: 新挂 N 个(默认20)不同患者的号, 产出候诊队列(visit_status=1)。
 * 走真实 register API(2201->扣号源->建 his_visit 候诊), 轮转分布到今日多个可挂号源(不同科室/医师),
 * 便于医生站各工作站都有排队患者。跳过已有有效挂号的患者, 保证 N 个互不重复。
 * 用法: node _seed_20_regs.js [baseUrl=http://localhost:8080] [count=20] */
const BASE = process.argv[2] || 'http://localhost:8080';
const COUNT = Number(process.argv[3] || 20);
const todayStr = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };

async function api(path, tok, method) {
  const opt = { method: method || 'GET', headers: {} };
  if (tok) opt.headers['Authorization'] = 'Bearer ' + tok;
  const r = await fetch(BASE + path, opt);
  return await r.json();
}

(async () => {
  const today = todayStr();
  const login = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username: 'admin', password: 'admin123' })
  }).then(r => r.json());
  if (login.code !== 0) { console.log('登录失败: ' + JSON.stringify(login).slice(0, 200)); process.exit(1); }
  const tok = login.data.token;
  console.log('登录 OK, 基准日期=' + today);

  /* 1. 今日可挂号源: status=1 且 left>0 且医保科室/医师编码齐全(否则 register 守卫拒挂) */
  const sl = await api('/api/his/schedule/list?page=1&size=300&from=' + today + '&to=' + today, tok);
  const raw = (sl.data && (sl.data.records || sl.data)) || [];
  const pick = (s, k) => s[k] != null ? s[k] : s[k.replace(/([A-Z])/g, '_$1').toLowerCase()];
  let slots = raw.map(s => ({
    id: s.id,
    deptName: pick(s, 'deptName') || pick(s, 'dept_name'),
    staffName: pick(s, 'staffName') || pick(s, 'staff_name'),
    timeType: pick(s, 'timeType') || pick(s, 'time_type'),
    left: pick(s, 'leftNum') != null ? pick(s, 'leftNum') : pick(s, 'left_num'),
    yb: pick(s, 'ybDeptCode') || pick(s, 'yb_dept_code'),
    atddr: pick(s, 'atddrNo') || pick(s, 'atddr_no'),
    status: s.status
  })).filter(s => s.status === 1 && s.left > 0 && s.yb && s.atddr);
  console.log('今日可挂号源 ' + slots.length + ' 个:');
  slots.forEach(s => console.log('   sched=' + s.id + ' ' + s.deptName + '/' + s.staffName + '(' + s.timeType + ') 余' + s.left));
  if (!slots.length) { console.log('无可用号源, 请先在[排班号源]为今日排班'); process.exit(1); }
  const totalCap = slots.reduce((a, s) => a + (s.left || 0), 0);
  if (totalCap < COUNT) console.log('警告: 可挂容量 ' + totalCap + ' < 需 ' + COUNT + ', 将尽量挂满');

  /* 2. 候选患者: 拉两页(200/页), 排除已有任意有效挂号(status=1)者, 保证新号互不重复 */
  const pl = await api('/api/his/patient/page?page=1&size=200', tok);
  const p2 = await api('/api/his/patient/page?page=2&size=200', tok);
  const pats = ((pl.data && (pl.data.records || pl.data)) || []).concat((p2.data && (p2.data.records || p2.data)) || []);
  console.log('候选患者池 ' + pats.length + ' 人');

  const usedPids = new Set();
  let ok = 0, si = 0, pi = 0, exhausted = 0;
  while (ok < COUNT && exhausted < slots.length && pi < pats.length) {
    if (si >= slots.length) { si = 0; }
    const s = slots[si];
    if ((s.left || 0) <= 0) { si++; exhausted++; continue; }
    exhausted = 0;
    const p = pats[pi++];
    if (!p || usedPids.has(p.id)) { continue; }
    const cd = await api('/api/his/registration/checkDuplicate?patientId=' + p.id + '&scheduleId=' + s.id, tok);
    if (cd.data && cd.data.duplicate) { continue; }
    const pay = ok % 3 === 2 ? 'insurance' : 'cash';
    const r = await api('/api/his/registration/register?patientId=' + p.id + '&scheduleId=' + s.id +
      '&medType=11&payMethod=' + pay + '&feeType=' + (pay === 'insurance' ? 'insurance' : 'self'), tok, 'POST');
    if (r.code === 0) {
      ok++; usedPids.add(p.id); s.left--;
      console.log('#' + ok + ' ' + p.name + ' -> ' + s.deptName + '/' + s.staffName + '(' + s.timeType + ') 候诊号=' + r.data.queueNo + ' 单号=' + r.data.regNo);
    } else if ((r.msg || '').indexOf('号源已用完') >= 0) {
      s.left = 0; // 该号源已空, 轮转下一个
    } else {
      console.log('  跳过(患者' + p.name + '/' + s.deptName + '): ' + (r.msg || '').slice(0, 60));
    }
  }
  console.log('==== 新挂完成: 成功 ' + ok + ' / 目标 ' + COUNT + ' ====');

  /* 3. 终检: 今日候诊队列(visit_status=1) 按科室/医师统计 */
  const q = await api('/api/his/visit/queue?page=1&size=200&workDate=' + today + '&visitStatus=1', tok);
  const rows = (q.data && (q.data.records || q.data)) || [];
  const byDoc = {};
  rows.forEach(v => { const k = (v.deptName || v.dept_name) + '/' + (v.drName || v.dr_name); byDoc[k] = (byDoc[k] || 0) + 1; });
  console.log('终检: 今日候诊中(visit_status=1)=' + rows.length + ' 人');
  Object.keys(byDoc).forEach(k => console.log('   ' + k + ': ' + byDoc[k] + ' 人'));
})().catch(e => { console.error('异常:', e); process.exit(1); });
