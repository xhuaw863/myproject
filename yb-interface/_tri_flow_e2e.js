/* 三模块(护士站/治疗/医技)全流程端到端测试 —— 模拟真实业务发生(含正常与异常路径)
 * 场景A 常规门诊: 挂号→接诊→开单(注射+输液+换药疗程 / 检验血钾 / 检查CT)→完成→收费
 *                 → 自动生成执行单/标本/疗程计划 → 治疗(签到/开始/完成×3次满疗程→医嘱回写)
 *                 → 输液(配液/穿刺/巡视/拔针) → 医技(采集/签收/报告危急值/双签/退回) → 医生站查报告
 * 场景B 过敏患者: 登记青霉素过敏 → 开单含青霉素类 → 输液配液被后端安全守卫拦截
 * 场景C 皮试阳性: 开单(皮试+补液)→收费 → 皮试开始→阳性→自动入过敏档案 → 同患者配液被拦
 * 场景D 守卫与幂等: 已执行医嘱作废被拒; 重复完成/重复拔针/乱序审核/部分退费后收费标志。
 * 运行: node _tri_flow_e2e.js   (BASE 默认 18081 自有实例)
 */
const BASE = process.env.TRI_BASE || 'http://localhost:18081';
let FAIL = 0, PASS = 0;
function step(name, ok, detail) {
  if (ok) { PASS++; console.log('PASS | ' + name); }
  else { FAIL++; console.log('FAIL | ' + name + ' | ' + JSON.stringify(detail)); }
}
async function login(username, password) {
  const r = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantCode: 'H42010000000', username, password: password || 'admin123' })
  }).then(x => x.json());
  return r.data && r.data.token;
}
async function call(tok, path, method, body) {
  const resp = await fetch(BASE + path, {
    method: method || 'GET',
    headers: Object.assign({ 'Content-Type': 'application/json' }, tok ? { Authorization: 'Bearer ' + tok } : {}),
    body: body ? JSON.stringify(body) : undefined
  });
  return await resp.json();
}
const num = v => Number(v == null ? 0 : v);
const pick = (o, k) => (o && (o[k] != null ? o[k] : o[k.replace(/_([a-z])/g, (m, c) => c.toUpperCase())]));
const todayStr = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
const R = { admin: null, nurse: null, therapist: null, tech: null };
const usedSched = {};

/* 建一次真实就诊: 挂号 -> 接诊 -> 返回 {visitId, patientId, patientName} */
async function openVisit(tag) {
  const today = todayStr();
  const sl = await call(R.admin, '/api/his/schedule/list?page=1&size=300');
  const rows = ((sl.data && (sl.data.records || sl.data)) || []).map(s => ({
    id: s.id, workDate: String(pick(s, 'work_date') || '').slice(0, 10),
    status: s.status, left: pick(s, 'left_num')
  })).filter(r => r.workDate === today && r.status === 1 && r.left > 0);
  if (!rows.length) { console.log('无可用号源(' + tag + ')'); return null; }
  let picked = null, patient = null;
  outer:
  for (const s of rows) {
    const pl = await call(R.admin, '/api/his/patient/page?page=1&size=100');
    const patients = (pl.data && (pl.data.records || pl.data)) || [];
    for (const p of patients) {
      const cd = await call(R.admin, '/api/his/registration/checkDuplicate?patientId=' + p.id + '&scheduleId=' + s.id);
      if (cd.data && !cd.data.duplicate) { picked = s; patient = p; break outer; }
    }
  }
  if (!picked) { console.log('无可挂号患者(' + tag + ')'); return null; }
  const reg = await call(R.admin, '/api/his/registration/register?patientId=' + patient.id + '&scheduleId=' + picked.id + '&medType=11&payMethod=cash', 'POST');
  const regId = reg.data && (reg.data.id || reg.data.regId);
  if (!regId) { console.log('挂号失败(' + tag + '): ' + reg.msg); return null; }
  const q = await call(R.admin, '/api/his/visit/queue?page=1&size=100&workDate=' + today + '&visitStatus=1');
  const visits = (q.data && (q.data.records || q.data)) || [];
  const v = visits.find(x => num(x.registrationId) === num(regId)) || visits.find(x => num(x.patientId) === num(patient.id));
  if (!v) { console.log('未取得就诊(' + tag + ')'); return null; }
  await call(R.admin, '/api/his/visit/start?id=' + v.id, 'POST');
  return { visitId: v.id, patientId: num(patient.id), patientName: patient.name || patient.patientName };
}
/* 完成接诊并自费收费(必须显式给支付方式金额) */
async function charge(visitId) {
  const fin = await call(R.admin, '/api/his/visit/finish', 'POST', { visitId, chiefComplaint: '三模块全流程', presentIllness: '无' });
  if (fin.code !== 0) { return { ok: false, at: 'finish', msg: fin.msg }; }
  const bill = await call(R.admin, '/api/his/cashier/bill/' + visitId);
  const total = (bill.data && bill.data.summary && bill.data.summary.totalAmount) || 0;
  const ch = await call(R.admin, '/api/his/cashier/self-pay', 'POST',
    { visitId, orgId: 1, payType: 'self', payments: [{ payMethod: 'CASH', amount: total }] });
  return ch.code === 0 ? { ok: true, billId: ch.data.bill.id, total: total } : { ok: false, at: 'charge', msg: ch.msg };
}
const execsOf = (list, visitId) => ((list) || []).filter(r => num(pick(r, 'visit_id')) === num(visitId));

(async () => {
  R.admin = await login('admin');
  R.nurse = await login('nl001');
  R.therapist = await login('tp001');
  R.tech = await login('tc001');
  step('00-四角色登录(管理员/护士/治疗师/医技)', !!(R.admin && R.nurse && R.therapist && R.tech),
    { admin: !!R.admin, nurse: !!R.nurse, therapist: !!R.therapist, tech: !!R.tech });
  if (!R.admin) process.exit(1);

  /* ================= 场景A: 常规门诊全链路 ================= */
  const A = await openVisit('A');
  step('A01-挂号并进入接诊', !!A, { A });
  if (!A) { console.log('\n无法建立真实就诊夹具, 终止'); process.exit(1); }

  const ordTreat = await call(R.admin, '/api/his/order/create', 'POST', {
    visitId: A.visitId, orderType: '治疗',
    items: [
      { itemCode: 'A-ING', itemName: '维生素C 肌内注射', spec: '0.2g', unit: '支', price: 6, quantity: 1, execDept: '门诊注射室' },
      { itemCode: 'A-INF', itemName: '0.9%氯化钠注射液 静脉输液', spec: '250ml', unit: '组', price: 25, quantity: 1, execDept: '门诊输液室' },
      { itemCode: 'A-RED', itemName: '腹部换药', spec: '', unit: '次', price: 30, quantity: 3, execDept: '门诊换药室' }
    ]
  });
  const treatOrderId = ordTreat.data && ordTreat.data.id;
  step('A02-开治疗医嘱(注射/输液/换药×3)', ordTreat.code === 0 && !!treatOrderId, { code: ordTreat.code, msg: ordTreat.msg });
  const ordLab = await call(R.admin, '/api/his/order/create', 'POST', {
    visitId: A.visitId, orderType: '检验',
    items: [{ itemCode: 'A-K', itemName: '血钾测定', spec: '', unit: '次', price: 20, quantity: 1, specimenType: '全血' }]
  });
  const labOrderId = ordLab.data && ordLab.data.id;
  step('A02b-开检验医嘱(血钾)', ordLab.code === 0 && !!labOrderId, { code: ordLab.code, msg: ordLab.msg });
  const ordExam = await call(R.admin, '/api/his/order/create', 'POST', {
    visitId: A.visitId, orderType: '检查',
    items: [{ itemCode: 'A-CT', itemName: '胸部CT平扫', spec: '', unit: '次', price: 220, quantity: 1, execDept: '放射科' }]
  });
  const examOrderId = ordExam.data && ordExam.data.id;
  step('A02c-开检查医嘱(CT)', ordExam.code === 0 && !!examOrderId, { code: ordExam.code, msg: ordExam.msg });

  const ca = await charge(A.visitId);
  step('A03-完成接诊并自费收费', ca.ok, ca);
  if (!ca.ok) { console.log('\n场景A收费失败, 终止: ' + JSON.stringify(ca)); process.exit(1); }

  /* A04: 收费 → 医嘱 paid_flag + 三类下游单据自动生成(本次核心修复的验收点) */
  const olist = await call(R.admin, '/api/his/order/list?visitId=' + A.visitId);
  const treatRow = (olist.data || []).find(o => num(o.id) === num(treatOrderId)) || {};
  step('A04-收费后医嘱 paid_flag=1(下游可见前提)', num(pick(treatRow, 'paid_flag')) === 1, { paidFlag: pick(treatRow, 'paid_flag') });

  const np = await call(R.nurse, '/api/nurse/pending?execStatus=0');
  const myExecs = execsOf(np.data, A.visitId);
  step('A05-护士站待执行自动生成2条(单次注射/输液; 换药×3归治疗站疗程)', myExecs.length === 2,
    { count: myExecs.length, types: myExecs.map(r => pick(r, 'exec_type')) });
  const byType = t => (myExecs.find(r => pick(r, 'exec_type') === t) || {});
  const inj = byType('injection'), inf = byType('infusion');
  // 护士工作台返回列别名为 execId(非 id), 统一取值助手
  const execIdOf = r => pick(r, 'execId') || pick(r, 'id');

  const sp = await call(R.tech, '/api/medtech/specimens?status=0&page=1&size=100');
  const mySpec = ((sp.data && sp.data.records) || []).filter(s => num(pick(s, 'order_id')) === num(labOrderId));
  step('A06-收费后自动生成标本', mySpec.length >= 1, { count: mySpec.length });

  const plans = await call(R.therapist, '/api/treatment/plans?page=1&size=50&patientId=' + A.patientId);
  const myPlans = ((plans.data && plans.data.records) || []).filter(p => num(pick(p, 'visit_id')) === num(A.visitId));
  const huanYao = myPlans.find(p => String(pick(p, 'item_name') || '').indexOf('换药') >= 0) || {};
  step('A07-自动生成疗程计划且总次数取开单次数(换药=3)', num(pick(huanYao, 'total_sessions')) === 3,
    { total: pick(huanYao, 'total_sessions'), freq: pick(huanYao, 'frequency'), plans: myPlans.length });
  // 双轨去重验证: 单次注射/输液不进疗程计划, 患者名下应仅换药一条计划
  step('A07b-单次注射/输液不生成治疗计划(去重)', myPlans.length === 1 && String(pick(huanYao, 'item_name') || '').indexOf('换药') >= 0,
    { plans: myPlans.map(p => pick(p, 'item_name')) });

  /* A08: 护士执行 —— 注射闭环 */
  const injId = execIdOf(inj);
  if (injId) {
    const st = await call(R.nurse, '/api/nurse/exec/' + injId + '/start', 'POST', {});
    step('A08-注射开始(三查七对后)', st.code === 0, { code: st.code, msg: st.msg });
    const fi = await call(R.nurse, '/api/nurse/exec/' + injId + '/finish', 'POST', { response: '无不良反应' });
    step('A08b-注射完成', fi.code === 0, { code: fi.code, msg: fi.msg });
    const dup = await call(R.nurse, '/api/nurse/exec/' + injId + '/finish', 'POST', {});
    step('A08c-重复完成被拒(乐观锁)', dup.code !== 0, { code: dup.code, msg: dup.msg });
    const lg = await call(R.nurse, '/api/nurse/exec-log?page=1&size=30');
    const lgRow = ((lg.data && lg.data.records) || []).find(r => num(execIdOf(r)) === num(injId)) || {};
    step('A08d-执行台账留痕含操作人与时间线', !!(pick(lgRow, 'nurse_name') || pick(lgRow, 'start_time') || pick(lgRow, 'startTime')),
      { keys: Object.keys(lgRow).join(',') });
  } else { step('A08-注射执行单存在', false, {}); }

  /* A09: 输液闭环 */
  const infExecId = execIdOf(inf);
  if (infExecId) {
    const ic = await call(R.nurse, '/api/nurse/infusion', 'POST', { execId: infExecId, seatNo: 'A-01', solution: '0.9%氯化钠250ml+维生素C2.0g', dripRate: 45 });
    const infId = ic.data && ic.data.id;
    step('A09-配液(座位/溶液/滴速)', ic.code === 0 && !!infId, { code: ic.code, msg: ic.msg });
    if (infId) {
      const pu = await call(R.nurse, '/api/nurse/infusion/' + infId + '/puncture', 'POST', { site: '左手背静脉' });
      step('A09b-穿刺', pu.code === 0, { code: pu.code, msg: pu.msg });
      const pt = await call(R.nurse, '/api/nurse/infusion/' + infId + '/patrol', 'POST', { patrolJson: JSON.stringify({ dripRate: 42, status: '通畅', note: '巡视无异常' }) });
      step('A09c-巡视记录追加', pt.code === 0, { code: pt.code, msg: pt.msg });
      const stageInf = await call(R.nurse, '/api/nurse/infusions?stage=infusing&page=1&size=50');
      step('A09d-输液中页签可见', ((stageInf.data && stageInf.data.records) || []).some(r => num(pick(r, 'infusionId')) === num(infId)), { total: stageInf.data && stageInf.data.total });
      const rm = await call(R.nurse, '/api/nurse/infusion/' + infId + '/remove', 'POST', {});
      step('A09e-拔针(联动执行单完成)', rm.code === 0, { code: rm.code, msg: rm.msg });
      const rm2 = await call(R.nurse, '/api/nurse/infusion/' + infId + '/remove', 'POST', {});
      step('A09f-重复拔针被拒', rm2.code !== 0, { code: rm2.code, msg: rm2.msg });
    }
  } else { step('A09-输液执行单存在', false, {}); }

  /* A10: 治疗疗程 —— 换药3次逐次完成, 满疗程回写医嘱 */
  let guard = 0;
  let doneRounds = 0;
  while (guard++ < 6) {
    const tp = await call(R.therapist, '/api/treatment/pending');
    const row = execsOf(tp.data, A.visitId).find(r => num(pick(r, 'exec_status')) === 0);
    if (!row) break;
    if (doneRounds === 0) {
      const ci = await call(R.therapist, '/api/treatment/exec/' + row.id + '/checkin', 'POST');
      step('A10-患者签到排队', ci.code === 0, { code: ci.code, msg: ci.msg });
      const ci2 = await call(R.therapist, '/api/treatment/exec/' + row.id + '/checkin', 'POST');
      step('A10b-重复签到幂等', ci2.code === 0, { code: ci2.code, msg: ci2.msg });
    }
    const bad = doneRounds === 1
      ? await call(R.therapist, '/api/treatment/exec/' + row.id + '/start', 'POST', { equipCode: 'NO-SUCH-EQ' })
      : { code: -1 };
    if (doneRounds === 1) { step('A10c-不存在的设备开始治疗被拒', bad.code !== 0, { code: bad.code, msg: bad.msg }); }
    const st = await call(R.therapist, '/api/treatment/exec/' + row.id + '/start', 'POST', {});
    if (doneRounds === 0) { step('A10d-开始治疗(治疗师取登录职工)', st.code === 0, { code: st.code, msg: st.msg }); }
    const fi = await call(R.therapist, '/api/treatment/exec/' + row.id + '/finish', 'POST', { durationMin: 20, params: '换药常规', response: '耐受良好' });
    if (doneRounds === 0) { step('A10e-完成第1次治疗', fi.code === 0, { code: fi.code, msg: fi.msg }); }
    if (fi.code !== 0) break;
    doneRounds++;
    if (doneRounds > 0 && fi.data && fi.data.nextCreated === false && fi.data.planFinished === true) break;
  }
  step('A11-疗程自动补建并逐次执行(完成3次)', doneRounds === 3, { rounds: doneRounds });
  const planAfter = await call(R.therapist, '/api/treatment/plans?page=1&size=50&patientId=' + A.patientId);
  const hy = ((planAfter.data && planAfter.data.records) || []).filter(p => num(pick(p, 'visit_id')) === num(A.visitId)).find(p => String(pick(p, 'item_name') || '').indexOf('换药') >= 0) || {};
  step('A11b-计划满疗程后状态=已完成', num(pick(hy, 'status')) === 1 && num(pick(hy, 'completed_sessions')) === 3,
    { status: pick(hy, 'status'), completed: pick(hy, 'completed_sessions') });
  const oAfter = await call(R.admin, '/api/his/order/list?visitId=' + A.visitId);
  const treatAfter = (oAfter.data || []).find(o => num(o.id) === num(treatOrderId)) || {};
  step('A11c-治疗医嘱执行状态回写(exec_status=2)', num(pick(treatAfter, 'exec_status')) === 2,
    { execStatus: pick(treatAfter, 'exec_status') });

  /* A12: 医技 —— 标本采集/签收 -> 报告危急值 -> 双签 */
  if (mySpec.length) {
    const barcodes = mySpec.map(s => pick(s, 'barcode'));
    const bc = await call(R.tech, '/api/medtech/specimen/batch-collect', 'POST', { barcodes });
    step('A12-批量采集标本', bc.code === 0 && num(pick(bc.data, 'success')) === barcodes.length, bc.data);
    const ag = await call(R.tech, '/api/medtech/specimen/' + barcodes[0] + '/collect', 'POST');
    step('A12b-重复采集被拒(乐观锁)', ag.code !== 0, { code: ag.code, msg: ag.msg });
    const rc = await call(R.tech, '/api/medtech/specimen/' + barcodes[0] + '/receive', 'POST');
    step('A12c-检验科签收', rc.code === 0, { code: rc.code, msg: rc.msg });

    const dr = await call(R.tech, '/api/medtech/report/draft', 'POST', { orderId: labOrderId, reportType: 'lab' });
    const rid = dr.data && dr.data.id;
    step('A13-创建检验报告草稿', dr.code === 0 && !!rid, { code: dr.code, msg: dr.msg });
    const unsaved = await call(R.tech, '/api/medtech/report/' + rid + '/review', 'POST', { approved: true });
    step('A13b-草稿未经提交直接审核被拒(双签顺序)', unsaved.code !== 0, { code: unsaved.code, msg: unsaved.msg });
    const sv = await call(R.tech, '/api/medtech/report/' + rid + '/save', 'POST', {
      findings: '电解质各项见明细', conclusion: '低钾血症',
      resultItems: [{ itemName: '血钾', itemCode: 'K', resultValue: '2.10', resultUnit: 'mmol/L', refRangeLow: 3.5, refRangeHigh: 5.5 }]
    });
    step('A13c-保存结果明细', sv.code === 0, { code: sv.code, msg: sv.msg });
    const sub = await call(R.tech, '/api/medtech/report/' + rid + '/submit', 'POST');
    step('A14-提交审核并自动检出危急值(血钾2.10)', sub.code === 0 && (num(sub.data && sub.data.criticalFlag) === 1 || ((sub.data && sub.data.criticals) || []).length > 0),
      { code: sub.code, msg: sub.msg, data: sub.data });
    const cvs = await call(R.tech, '/api/medtech/critical-values?status=0&page=1&size=50');
    const cvList = (cvs.data && cvs.data.records) || [];
    step('A14b-危急值进入待复核队列', cvList.length >= 1, { total: cvs.data && cvs.data.total });
    if (cvList.length) {
      const cid = cvList[0].id;
      const early = await call(R.tech, '/api/medtech/critical/' + cid + '/handle', 'POST', { measures: '越级处置' });
      step('A14c-跳过复核直接处置被拒(五步顺序)', early.code !== 0, { code: early.code, msg: early.msg });
      const a = await call(R.tech, '/api/medtech/critical/' + cid + '/verify', 'POST');
      const b = await call(R.tech, '/api/medtech/critical/' + cid + '/notify', 'POST', { target: '内科门诊-接诊医师' });
      const c = await call(R.tech, '/api/medtech/critical/' + cid + '/confirm', 'POST', { person: '接诊医师' });
      const d = await call(R.tech, '/api/medtech/critical/' + cid + '/handle', 'POST', { measures: '口服补钾，2小时后复查' });
      step('A15-危急值五步闭环(发现→复核→通知→接收→处置)', a.code === 0 && b.code === 0 && c.code === 0 && d.code === 0,
        { a: a.msg, b: b.msg, c: c.msg, d: d.msg });
      const done = await call(R.tech, '/api/medtech/critical-values?status=4&page=1&size=50');
      step('A15b-已处置页签可检索到该条', ((done.data && done.data.records) || []).some(r => num(r.id) === num(cid)), { total: done.data && done.data.total });
    }
    const back = await call(R.tech, '/api/medtech/report/' + rid + '/review', 'POST', { approved: false });
    step('A16-审核退回(1->0 可修改)', back.code === 0, { code: back.code, msg: back.msg });
    const sv2 = await call(R.tech, '/api/medtech/report/' + rid + '/save', 'POST', { findings: '修正单位', conclusion: '低钾血症(已纠正)', resultItems: [{ itemName: '血钾', itemCode: 'K', resultValue: '2.10', resultUnit: 'mmol/L', refRangeLow: 3.5, refRangeHigh: 5.5 }] });
    const sub2 = await call(R.tech, '/api/medtech/report/' + rid + '/submit', 'POST');
    const ok = await call(R.tech, '/api/medtech/report/' + rid + '/review', 'POST', { approved: true });
    step('A16b-退回后重新提交并审核通过', sv2.code === 0 && sub2.code === 0 && ok.code === 0, { s: sv2.msg, u: sub2.msg, r: ok.msg });
    const oLab = (await call(R.admin, '/api/his/order/list?visitId=' + A.visitId)).data || [];
    const labRow = oLab.find(o => num(o.id) === num(labOrderId)) || {};
    step('A16c-检验医嘱执行状态回写=2', num(pick(labRow, 'exec_status')) === 2, { execStatus: pick(labRow, 'exec_status') });
    const doc = await call(R.admin, '/api/medtech/reports/patient/' + A.patientId);
    step('A17-医生站按患者可查历史报告', (doc.data || []).length >= 1, { n: (doc.data || []).length });
  } else { step('A12-标本链路', false, { spec: 0 }); }

  /* A18: 检查报告(无标本路径) */
  const ed = await call(R.tech, '/api/medtech/report/draft', 'POST', { orderId: examOrderId, reportType: 'exam' });
  if (ed.code === 0 && ed.data && ed.data.id) {
    await call(R.tech, '/api/medtech/report/' + ed.data.id + '/save', 'POST', { findings: '两肺纹理清晰,未见实质病变', conclusion: '胸部CT未见异常' });
    const s2 = await call(R.tech, '/api/medtech/report/' + ed.data.id + '/submit', 'POST');
    const r2 = await call(R.tech, '/api/medtech/report/' + ed.data.id + '/review', 'POST', { approved: true });
    step('A18-检查报告 书写→提交→审核 通过', s2.code === 0 && r2.code === 0, { s: s2.msg, r: r2.msg });
  } else { step('A18-检查报告草稿', false, { code: ed.code, msg: ed.msg }); }

  /* A19: 守卫 —— 已执行医嘱不可作废 */
  const cancelExec = await call(R.admin, '/api/his/order/cancel?id=' + treatOrderId, 'POST');
  step('A19-已执行医嘱作废被拒(执行痕迹守卫)', cancelExec.code !== 0, { code: cancelExec.code, msg: cancelExec.msg });

  /* ================= 场景B: 过敏患者安全拦截 ================= */
  const B = await openVisit('B');
  step('B01-第二患者挂号接诊', !!B, { B });
  if (B) {
    const addAl = await call(R.nurse, '/api/nurse/allergy', 'POST',
      { patientId: B.patientId, allergenType: 'drug', allergenName: '青霉素', severity: 'severe', source: 'manual', note: '既往休克史' });
    step('B02-登记过敏档案(青霉素)', addAl.code === 0 || String(addAl.msg || '').indexOf('已登记') >= 0, { code: addAl.code, msg: addAl.msg });
    const dupAl = await call(R.nurse, '/api/nurse/allergy', 'POST',
      { patientId: B.patientId, allergenType: 'drug', allergenName: '青霉素', severity: 'severe', source: 'manual' });
    step('B02b-同过敏原重复登记被拒', dupAl.code !== 0, { code: dupAl.code, msg: dupAl.msg });
    const ob = await call(R.admin, '/api/his/order/create', 'POST', {
      visitId: B.visitId, orderType: '治疗',
      items: [{ itemCode: 'B-INF', itemName: '青霉素钠 静脉输液', spec: '80万U', unit: '支', price: 12, quantity: 1, execDept: '门诊输液室' }]
    });
    step('B03-过敏患者仍可开单(拦截在执行环节, 医生决策)', ob.code === 0, { code: ob.code, msg: ob.msg });
    const cb = await charge(B.visitId);
    step('B03b-收费成功', cb.ok, cb);
    const npB = await call(R.nurse, '/api/nurse/pending?execStatus=0');
    const infB = execsOf(npB.data, B.visitId).find(r => pick(r, 'exec_type') === 'infusion') || {};
    const infBId = execIdOf(infB);
    step('B04-过敏患者生成输液执行单', !!infBId, { found: !!infBId });
    if (infBId) {
      const block = await call(R.nurse, '/api/nurse/infusion', 'POST', { execId: infBId, seatNo: 'B-02', solution: '青霉素钠80万U+NS250ml', dripRate: 40 });
      step('B05-配液被后端过敏守卫拦截(用药安全)', block.code !== 0 && String(block.msg || '').indexOf('过敏') >= 0,
        { code: block.code, msg: block.msg });
      const cancel = await call(R.nurse, '/api/nurse/exec/' + infBId + '/cancel', 'POST', { reason: '皮试/过敏史禁用, 已联系医生换药' });
      step('B05b-护士取消该执行单(闭环到换药决策)', cancel.code === 0, { code: cancel.code, msg: cancel.msg });
    }
    const alList = await call(R.nurse, '/api/nurse/allergies?keyword=' + encodeURIComponent('青霉素') + '&page=1&size=30');
    step('B06-过敏档案检索可见该患者', ((alList.data && alList.data.records) || []).length >= 1, { total: alList.data && alList.data.total });
  }

  /* ================= 场景C: 皮试阳性 → 自动入档 → 后续用药受限 ================= */
  const C = await openVisit('C');
  step('C01-第三患者挂号接诊', !!C, { C });
  if (C) {
    const oc = await call(R.admin, '/api/his/order/create', 'POST', {
      visitId: C.visitId, orderType: '治疗',
      items: [
        { itemCode: 'C-SKIN', itemName: '青霉素皮试', spec: '500U', unit: '次', price: 8, quantity: 1, execDept: '门诊注射室' },
        { itemCode: 'C-INF', itemName: '0.9%氯化钠注射液 静脉输液', spec: '250ml', unit: '组', price: 20, quantity: 1, execDept: '门诊输液室' }
      ]
    });
    step('C02-开单(皮试+补液)', oc.code === 0, { code: oc.code, msg: oc.msg });
    const cc = await charge(C.visitId);
    step('C02b-收费成功', cc.ok, cc);
    const npC = await call(R.nurse, '/api/nurse/pending?execStatus=0');
    const skinC = execsOf(npC.data, C.visitId).find(r => pick(r, 'exec_type') === 'skin_test') || {};
    const infC = execsOf(npC.data, C.visitId).find(r => pick(r, 'exec_type') === 'infusion') || {};
    const skinCId = execIdOf(skinC), infCId = execIdOf(infC);
    step('C03-皮试执行单生成', !!skinCId, { found: !!skinCId, types: execsOf(npC.data, C.visitId).map(r => pick(r, 'exec_type')) });
    if (skinCId) {
      const sk = await call(R.nurse, '/api/nurse/skin-test', 'POST', { execId: skinCId, drugName: '青霉素', testDose: '500U' });
      const sid = sk.data && sk.data.id;
      step('C04-开始皮试(20分钟观察窗)', sk.code === 0 && !!sid, { code: sk.code, msg: sk.msg });
      const obs = await call(R.nurse, '/api/nurse/skin-tests?resultStatus=1&page=1&size=30');
      step('C04b-观察中页签可见(含剩余秒数)', ((obs.data && obs.data.records) || []).some(r => num(pick(r, 'testId')) === num(sid)),
        { total: obs.data && obs.data.total });
      const badResult = await call(R.nurse, '/api/nurse/skin-test/' + sid + '/result', 'POST', { result: 2 });
      step('C05-阳性未填症状描述被拒', badResult.code !== 0, { code: badResult.code, msg: badResult.msg });
      const pos = await call(R.nurse, '/api/nurse/skin-test/' + sid + '/result', 'POST', { result: 2, resultDesc: '皮丘红肿直径12mm，伪足(+)，痒' });
      step('C05b-录入阳性结果', pos.code === 0, { code: pos.code, msg: pos.msg });
      const al = await call(R.nurse, '/api/nurse/allergy/' + C.patientId);
      step('C06-阳性自动写入过敏档案', (al.data || []).some(a => String(pick(a, 'allergen_name') || '').indexOf('青霉素') >= 0
        && String(pick(a, 'source') || '').indexOf('skin') >= 0), { n: (al.data || []).length });
      const dup = await call(R.nurse, '/api/nurse/skin-test/' + sid + '/result', 'POST', { result: 1, resultDesc: '改判' });
      step('C06b-已录入皮试重复改判被拒', dup.code !== 0, { code: dup.code, msg: dup.msg });
      if (infCId) {
        const block = await call(R.nurse, '/api/nurse/infusion', 'POST', { execId: infCId, seatNo: 'C-03', solution: 'NS250ml+VC', dripRate: 40 });
        step('C07-阳性后同患者配液被拦(过敏档案生效)', block.code !== 0 && String(block.msg || '').indexOf('过敏') >= 0,
          { code: block.code, msg: block.msg });
      } else { step('C07-同患者输液执行单存在', false, {}); }
      /* 医生站开单时应能查到该过敏(前端拦截依赖此接口) */
      const forDoctor = await call(R.admin, '/api/nurse/allergy/' + C.patientId);
      step('C07b-医生站开单前可读到过敏(接口对医生可用)', (forDoctor.data || []).length >= 1, { n: (forDoctor.data || []).length, code: forDoctor.code });
    }
  }

  /* ================= 场景D: 未执行就诊 收费→退费 → paid_flag 回滚(用独立新就诊, 已执行单留痕不回滚) ================= */
  const D = await openVisit('D');
  step('D00-第四患者挂号接诊(未发生执行)', !!D, { D });
  if (D) {
    const od = await call(R.admin, '/api/his/order/create', 'POST', {
      visitId: D.visitId, orderType: '治疗',
      items: [{ itemCode: 'D-INJ', itemName: '甲氧氯普胺注射液 肌内注射', spec: '1ml', unit: '支', price: 5, quantity: 1, execDept: '门诊注射室' }]
    });
    const cd = await charge(D.visitId);
    step('D01-收费成功', cd.ok, cd);
    const oD = (await call(R.admin, '/api/his/order/list?visitId=' + D.visitId)).data || [];
    step('D01b-收费后医嘱可执行(paid_flag=1)', oD.length > 0 && oD.every(o => num(pick(o, 'paid_flag')) === 1),
      { flags: oD.map(o => pick(o, 'paid_flag')) });
    const re = await call(R.admin, '/api/his/cashier/self-pay', 'POST', { visitId: D.visitId, orgId: 1, payType: 'self', payments: [] });
    step('D01c-同一就诊重复收费被拒', re.code !== 0, { code: re.code, msg: re.msg });
    const npD0 = await call(R.nurse, '/api/nurse/pending?execStatus=0');
    step('D02-退费前护士站待执行含该就诊', execsOf(npD0.data, D.visitId).length >= 1, { n: execsOf(npD0.data, D.visitId).length });
    const rf = await call(R.admin, '/api/his/cashier/refund', 'POST', { billId: cd.billId, reason: '全流程测试-整单退' });
    step('D02b-整单退费', rf.code === 0, { code: rf.code, msg: rf.msg });
    if (rf.code === 0) {
      const oAfter2 = await call(R.admin, '/api/his/order/list?visitId=' + D.visitId);
      const paidBack = (oAfter2.data || []).every(o => num(pick(o, 'paid_flag')) === 0);
      step('D03-退费后医嘱 paid_flag 复位(不再可执行)', paidBack,
        { flags: (oAfter2.data || []).map(o => pick(o, 'paid_flag')) });
      const npD = await call(R.nurse, '/api/nurse/pending?execStatus=0');
      step('D04-退费后护士站待执行不再含该就诊', execsOf(npD.data, D.visitId).length === 0, { left: execsOf(npD.data, D.visitId).length });
    }
  }

  console.log('\n==== TRI-FLOW E2E SUMMARY: ' + PASS + '/' + (PASS + FAIL) + ' PASS ====');
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('TRI_CRASH', e); process.exit(2); });
