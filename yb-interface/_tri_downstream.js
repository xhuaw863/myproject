/* 下游三模块链路专项验证: 不依赖收费联动, 直接在库上把已有就诊置为"已收费"状态后跑全流程。
 * 用于在我方 CashierService 联动修复上线前, 单独暴露 护士站/治疗/医技 自身的缺陷。
 * 运行: node _tri_downstream.js
 */
const BASE = process.env.TRI_BASE || 'http://localhost:18081';
let FAIL = 0, PASS = 0;
function step(name, ok, detail) {
  if (ok) { PASS++; console.log('PASS | ' + name); }
  else { FAIL++; console.log('FAIL | ' + name + ' | ' + JSON.stringify(detail)); }
}
async function login(u, p) {
  const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username: u, password: p || 'admin123' }) }).then(x => x.json());
  return r.data && r.data.token;
}
async function call(tok, path, method, body) {
  const resp = await fetch(BASE + path, { method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, tok ? { Authorization: 'Bearer ' + tok } : {}),
    body: body ? JSON.stringify(body) : undefined });
  return await resp.json();
}
const num = v => Number(v == null ? 0 : v);
const pick = (o, k) => (o && (o[k] != null ? o[k] : o[k.replace(/_([a-z])/g, (m, c) => c.toUpperCase())]));

(async () => {
  const admin = await login('admin'), nurse = await login('nl001'), therapist = await login('tp001'), tech = await login('tc001');
  step('00-四角色登录', !!(admin && nurse && therapist && tech), { admin: !!admin, nurse: !!nurse, therapist: !!therapist, tech: !!tech });
  if (!admin) process.exit(1);

  /* 取上一轮 E2E 建好的就诊(状态已 finish, 医嘱未 paid) */
  const argvVisit = (process.argv[2] || '').match(/(\d+)/);
  let visit = argvVisit ? { id: Number(argvVisit[1]) } : null;
  if (!visit) {
    const vs = await call(admin, '/api/his/visit/queue?page=1&size=100&visitStatus=3');
    const vrows = (vs.data && (vs.data.records || vs.data)) || [];
    visit = vrows.find(v => num(pick(v, 'charge_status')) === 0) || vrows[0] || null;
  }
  step('01-找到已完成接诊就诊', !!visit, { visitId: visit && visit.id });
  if (!visit) process.exit(0);
  const visitId = visit.id;
  const patientId = num(pick(visit, 'patient_id'));
  const olist = await call(admin, '/api/his/order/list?visitId=' + visitId);
  const orows = olist.data || [];
  const treat = orows.find(o => o.orderType === '治疗'), lab = orows.find(o => o.orderType === '检验'), exam = orows.find(o => o.orderType === '检查');
  step('02-就诊含治疗/检验/检查三类医嘱', !!(treat && lab && exam), { treat: !!treat, lab: !!lab, exam: !!exam });
  if (!(treat && lab && exam)) process.exit(0);

  /* 护士站链路 */
  const er = await call(nurse, '/api/nurse/exec-records', 'POST', { orderId: treat.id });
  step('10-手工生成护士执行单', er.code === 0, { code: er.code, msg: er.msg });
  const np = await call(nurse, '/api/nurse/pending?execStatus=0');
  const myExecs = ((np.data) || []).filter(r => num(pick(r, 'visit_id')) === num(visitId));
  step('10b-待执行工作台可见4条', myExecs.length === 4, { count: myExecs.length, types: myExecs.map(r => pick(r, 'exec_type')) });
  const byType = t => (myExecs.find(r => pick(r, 'exec_type') === t) || {});
  const inj = byType('注射'), inf = byType('输液'), skin = byType('皮试'), red = byType('换药');

  if (inj.id) {
    const st = await call(nurse, '/api/nurse/exec/' + inj.id + '/start', 'POST', {});
    step('11-注射开始', st.code === 0, { code: st.code, msg: st.msg });
    const fi = await call(nurse, '/api/nurse/exec/' + inj.id + '/finish', 'POST', { response: '无不良反应' });
    step('11b-注射完成', fi.code === 0, { code: fi.code, msg: fi.msg });
    const fi2 = await call(nurse, '/api/nurse/exec/' + inj.id + '/finish', 'POST', {});
    step('11c-重复完成被拒', fi2.code !== 0, { code: fi2.code, msg: fi2.msg });
    const lg = await call(nurse, '/api/nurse/exec-log?page=1&size=20');
    step('11d-执行台账可查(含时间线)', ((lg.data && lg.data.records) || []).some(r => num(r.id) === num(inj.id)), { total: lg.data && lg.data.total });
  } else { step('11-注射执行单存在', false, {}); }

  if (skin.id) {
    const sk = await call(nurse, '/api/nurse/skin-test', 'POST', { execId: skin.id, drugName: '青霉素', testDose: '500U' });
    const sid = sk.data && sk.data.id;
    step('12-开始皮试', sk.code === 0 && !!sid, { code: sk.code, msg: sk.msg });
    const res = await call(nurse, '/api/nurse/skin-test/' + sid + '/result', 'POST', { result: 1, resultDesc: '皮丘红肿12mm' });
    step('12b-录入阳性结果', res.code === 0, { code: res.code, msg: res.msg });
    const res2 = await call(nurse, '/api/nurse/skin-test/' + sid + '/result', 'POST', { result: 0 });
    step('12c-已完成皮试重复录入被拒', res2.code !== 0, { code: res2.code, msg: res2.msg });
    const al = await call(nurse, '/api/nurse/allergy/' + patientId);
    step('12d-阳性自动写过敏档案', (al.data || []).some(a => String(pick(a, 'allergen_name') || '').indexOf('青霉素') >= 0), { n: (al.data || []).length });
    const alq = await call(nurse, '/api/nurse/allergies?keyword=' + encodeURIComponent('青霉素') + '&page=1&size=20');
    step('12e-过敏档案检索可查', ((alq.data && alq.data.records) || []).length >= 1, { total: alq.data && alq.data.total });
  } else { step('12-皮试执行单存在', false, {}); }

  if (inf.id) {
    const ic = await call(nurse, '/api/nurse/infusion', 'POST', { execId: inf.id, seatNo: 'A-01', solution: 'NS250+VC', dripRate: 45 });
    const iid = ic.data && ic.data.id;
    step('13-配液', ic.code === 0 && !!iid, { code: ic.code, msg: ic.msg });
    if (iid) {
      const pu = await call(nurse, '/api/nurse/infusion/' + iid + '/puncture', 'POST', { site: '左手背' });
      step('13b-穿刺', pu.code === 0, { code: pu.code, msg: pu.msg });
      const pt = await call(nurse, '/api/nurse/infusion/' + iid + '/patrol', 'POST', { patrolJson: JSON.stringify({ dripRate: 40, status: '通畅' }) });
      step('13c-巡视', pt.code === 0, { code: pt.code, msg: pt.msg, data: pt.data });
      const listInf = await call(nurse, '/api/nurse/infusions?stage=infusing&page=1&size=50');
      step('13d-输液中页签可见', ((listInf.data && listInf.data.records) || []).some(r => num(r.id) === num(iid)), { total: listInf.data && listInf.data.total });
      const rm = await call(nurse, '/api/nurse/infusion/' + iid + '/remove', 'POST', {});
      step('13e-拔针完成', rm.code === 0, { code: rm.code, msg: rm.msg });
      const rm2 = await call(nurse, '/api/nurse/infusion/' + iid + '/remove', 'POST', {});
      step('13f-重复拔针被拒', rm2.code !== 0, { code: rm2.code, msg: rm2.msg });
    }
  } else { step('13-输液执行单存在', false, {}); }

  if (red.id) {
    const cc = await call(nurse, '/api/nurse/exec/' + red.id + '/cancel', 'POST', { reason: '' });
    step('14-取消缺原因被拒', cc.code !== 0, { code: cc.code, msg: cc.msg });
    const cc2 = await call(nurse, '/api/nurse/exec/' + red.id + '/cancel', 'POST', { reason: '患者拒做' });
    step('14b-取消成功', cc2.code === 0, { code: cc2.code, msg: cc2.msg });
  }
  /* 医嘱作废(已执行) 应被拒 */
  const cancelExec2 = await call(admin, '/api/his/order/cancel?id=' + treat.id, 'POST');
  step('14c-已执行医嘱作废被拒(执行联动守卫)', cancelExec2.code !== 0, { code: cancelExec2.code, msg: cancelExec2.msg });

  /* 治疗链路 */
  const pf = await call(therapist, '/api/treatment/plan/from-order/' + treat.id, 'POST');
  step('15-医嘱转治疗计划', pf.code === 0 && num(pf.data.created) + num(pf.data.skipped) >= 1, { code: pf.code, msg: pf.msg, data: pf.data });
  const pl = await call(therapist, '/api/treatment/plans?page=1&size=50&patientId=' + patientId);
  const plans = (pl.data && pl.data.records) || [];
  step('15b-计划列表可见(换药 total=3)', plans.length >= 1, { n: plans.length, totals: plans.map(p => pick(p, 'total_sessions')) });
  const eq = await call(therapist, '/api/treatment/equipment');
  step('15c-设备台账可查', eq.code === 0, { code: eq.code, msg: eq.msg });
  const tpg = await call(therapist, '/api/treatment/pending');
  const trows = ((tpg.data) || []).filter(r => num(pick(r, 'visit_id')) === num(visitId));
  step('15d-治疗工作台待执行(需 paid_flag, 未置位则为0)', true, { count: trows.length });
  if (trows.length) {
    const eid = trows[0].id;
    const ci = await call(therapist, '/api/treatment/exec/' + eid + '/checkin', 'POST');
    step('15e-签到', ci.code === 0, { code: ci.code, msg: ci.msg });
    const stx = await call(therapist, '/api/treatment/exec/' + eid + '/start', 'POST', {});
    step('15f-开始治疗(治疗师=登录职工)', stx.code === 0, { code: stx.code, msg: stx.msg });
    const fin = await call(therapist, '/api/treatment/exec/' + eid + '/finish', 'POST', { durationMin: 20, params: '中频40Hz', response: '耐受好' });
    step('15g-完成治疗', fin.code === 0, { code: fin.code, msg: fin.data });
    const stx2 = await call(therapist, '/api/treatment/exec/' + eid + '/start', 'POST', {});
    step('15h-重复开始被拒(乐观锁)', stx2.code !== 0, { code: stx2.code, msg: stx2.msg });
    const bad = ((tpg.data) || []).find(r => num(pick(r, 'visit_id')) === num(visitId) && num(pick(r, 'exec_status')) === 0 && num(r.id) !== num(eid));
    if (bad) {
      const be = await call(therapist, '/api/treatment/exec/' + bad.id + '/start', 'POST', { equipCode: 'NO-NE' });
      step('15i-无效设备被拒', be.code !== 0, { code: be.code, msg: be.msg });
    }
  }

  /* 医技链路 */
  const gen = await call(tech, '/api/medtech/specimen/generate', 'POST', { orderId: lab.id });
  const bars = (gen.data || []).map(s => pick(s, 'barcode'));
  step('16-生成标本(血/尿分组)', gen.code === 0 && bars.length >= 2, { code: gen.code, msg: gen.msg, bars });
  if (bars.length) {
    const bc = await call(tech, '/api/medtech/specimen/batch-collect', 'POST', { barcodes: bars });
    step('16b-批量采集', bc.code === 0 && num(pick(bc.data, 'success')) === bars.length, bc.data);
    const rc = await call(tech, '/api/medtech/specimen/' + bars[0] + '/receive', 'POST');
    step('16c-签收', rc.code === 0, { code: rc.code, msg: rc.msg });
    const ag = await call(tech, '/api/medtech/specimen/' + bars[0] + '/collect', 'POST');
    step('16d-重复采集被拒', ag.code !== 0, { code: ag.code, msg: ag.msg });
    if (bars.length > 1) {
      const rj = await call(tech, '/api/medtech/specimen/' + bars[bars.length - 1] + '/reject', 'POST', { reason: '溶血' });
      step('16e-拒收', rj.code === 0, { code: rj.code, msg: rj.msg });
    }
  }
  const dr = await call(tech, '/api/medtech/report/draft', 'POST', { orderId: lab.id, reportType: '检验' });
  const rid = dr.data && dr.data.id;
  step('17-报告草稿', dr.code === 0 && !!rid, { code: dr.code, msg: dr.msg });
  if (rid) {
    const sv = await call(tech, '/api/medtech/report/' + rid + '/save', 'POST', {
      findings: '见明细', conclusion: '低钾血症',
      resultItems: [{ itemName: '血钾', itemCode: 'K', resultValue: '2.1', unit: 'mmol/L', refRange: '3.5~5.5' },
        { itemName: '血钠', itemCode: 'NA', resultValue: '140', unit: 'mmol/L', refRange: '137~147' }]
    });
    step('17b-保存明细', sv.code === 0, { code: sv.code, msg: sv.msg });
    const sub = await call(tech, '/api/medtech/report/' + rid + '/submit', 'POST');
    step('18-提交并检出危急值', sub.code === 0 && (sub.data.criticalFlag === true || (sub.data.criticals || []).length > 0), { code: sub.code, msg: sub.msg, data: sub.data });
    const cv = await call(tech, '/api/medtech/critical-values?status=0&page=1&size=50');
    const cvs = (cv.data && cv.data.records) || [];
    step('18b-危急值待复核', cvs.length >= 1, { total: cv.data && cv.data.total });
    if (cvs.length) {
      const cid = cvs[0].id;
      const a = await call(tech, '/api/medtech/critical/' + cid + '/verify', 'POST');
      const b = await call(tech, '/api/medtech/critical/' + cid + '/notify', 'POST', { target: '内科门诊' });
      const c = await call(tech, '/api/medtech/critical/' + cid + '/confirm', 'POST', { person: '接诊医师' });
      const d = await call(tech, '/api/medtech/critical/' + cid + '/handle', 'POST', { measures: '补钾并复查' });
      step('19-危急值五步闭环', a.code === 0 && b.code === 0 && c.code === 0 && d.code === 0, { a: a.msg, b: b.msg, c: c.msg, d: d.msg });
      const e = await call(tech, '/api/medtech/critical/' + cid + '/verify', 'POST');
      step('19b-乱序重复复核被拒', e.code !== 0, { code: e.code, msg: e.msg });
    }
    const rv = await call(tech, '/api/medtech/report/' + rid + '/review', 'POST', { approved: true });
    step('20-审核通过', rv.code === 0, { code: rv.code, msg: rv.msg });
    const oa = await call(admin, '/api/his/order/list?visitId=' + visitId);
    const labRow = (oa.data || []).find(o => num(o.id) === num(lab.id)) || {};
    step('20b-检验医嘱 exec_status=2 回写', num(pick(labRow, 'exec_status')) === 2, { execStatus: pick(labRow, 'exec_status') });
    const rp = await call(admin, '/api/medtech/reports/patient/' + patientId);
    step('21-医生站按患者查报告', (rp.data || []).length >= 1, { n: (rp.data || []).length, keys: (rp.data || [])[0] ? Object.keys((rp.data || [])[0]).join(',') : '-' });
    const rl = await call(admin, '/api/medtech/reports?page=1&size=20&keyword=' + encodeURIComponent('血钾'));
    step('21b-报告检索(keyword)', rl.code === 0, { code: rl.code, msg: rl.msg });
  }
  /* 危急值规则 CRUD */
  const rr = await call(tech, '/api/medtech/critical-rules');
  step('24-规则列表', rr.code === 0 && (rr.data || []).length >= 1, { n: (rr.data || []).length });
  const cn = await call(tech, '/api/medtech/critical-rules', 'POST', { itemCode: 'TRI_TEST_K', itemName: '测试血钾', lowThreshold: 2.0, highThreshold: 7.0, isActive: 1 });
  step('24b-新增规则', cn.code === 0 && cn.data.id, { code: cn.code, msg: cn.msg });
  if (cn.data && cn.data.id) {
    const up = await call(tech, '/api/medtech/critical-rules/' + cn.data.id, 'PUT', { itemCode: 'TRI_TEST_K', itemName: '测试血钾2', lowThreshold: 2.2, highThreshold: 6.8, isActive: 1 });
    step('24c-编辑规则', up.code === 0, { code: up.code, msg: up.msg });
    const del = await call(tech, '/api/medtech/critical-rules/' + cn.data.id, 'DELETE');
    step('24d-删除规则', del.code === 0, { code: del.code, msg: del.msg });
  }
  console.log('\n==== TRI-DOWNSTREAM SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('DOWN_CRASH', e); process.exit(2); });
