/* 排班号源(ScheduleManage) E2E 验证 —— 前端完全重写(Task #15)
 * 覆盖: 登录 → 排班号源菜单 → 周视图(矩阵表头/统计条/卡片/空槽/today高亮)
 *      → 周导航(上一周/下一周/本周) → 卡片点击编辑 → 空槽快捷新增
 *      → 列表视图(列/序号/状态tag/分页) → 批量停诊(执行+API复核)
 *      → 模板抽屉(列表/新增模板对话框/批量创建对话框)
 *      → 按模板生成(执行+API复核) → 复制上周(执行+API复核) → 停诊卡片样式
 * 前置: 应用运行于 localhost:8080; 测试数据以 room='E2E诊室' 标记, 结束自动清理
 * 运行: node sched_e2e_test.js  (playwright-core + 系统Edge headless)
 * 注意: EP el-dialog 关闭后 DOM 残留, 所有对话框定位必须限定 :visible */
const PW_CORE = 'C:/Users/Lenovo/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright-core';
const { chromium } = require(PW_CORE);
const http = require('http');

const BASE = 'http://localhost:8080';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const OUT = 'd:/study/ybtest';
const MARK = 'E2E诊室';

const results = [];
const pass = m => { results.push(['PASS', m]); console.log('  [PASS]', m); };
const fail = (m, e) => { const d = e ? ' :: ' + (e.message || e).toString().split('\n')[0] : ''; results.push(['FAIL', m + d]); console.log('  [FAIL]', m + d); };
const step = async (name, fn) => { console.log('\n==== ' + name + ' ===='); try { await fn(); } catch (e) { fail(name, e); } };

function api(method, p, body, token) {
  return new Promise((res, rej) => {
    const data = body ? JSON.stringify(body) : null;
    const r = http.request({ host: 'localhost', port: 8080, path: p, method,
      headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}) },
      resp => { let s = ''; resp.setEncoding('utf8'); resp.on('data', c => s += c); resp.on('end', () => { try { res(JSON.parse(s)); } catch (e) { rej(new Error('bad json: ' + s.slice(0, 120))); } }); });
    r.on('error', rej); if (data) r.write(data); r.end();
  });
}

/* Node 侧日期工具(yyyy-MM-dd) */
const fmt = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
const addD = (s, n) => { const p = s.split('-').map(Number); return fmt(new Date(p[0], p[1] - 1, p[2] + n)); };
const monday = d => { const m = new Date(d.getFullYear(), d.getMonth(), d.getDate()); m.setDate(m.getDate() - ((m.getDay() + 6) % 7)); return m; };

(async () => {
  const WK1 = fmt(monday(new Date()));          /* 本周一 */
  const WK7 = addD(WK1, 6);                     /* 本周日 */
  const LW1 = addD(WK1, -7);                    /* 上周一 */
  console.log('本周: ' + WK1 + ' ~ ' + WK7 + ' | 上周: ' + LW1 + ' ~ ' + addD(LW1, 6));

  /* ===== 0. API 数据准备 ===== */
  let token = '', s1 = null, s2 = null, deptNameOf = () => '';
  await step('0. API数据准备(登录/清理/造数)', async () => {
    const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
    if (!lg.data || !lg.data.token) throw new Error('API登录失败: ' + JSON.stringify(lg).slice(0, 150));
    token = lg.data.token;
    pass('API登录成功(牵头ADMIN, lead=' + (lg.data.leadOrg || '') + ')');
    /* 清理历史 E2E 数据(全年范围) */
    let del = 0;
    const lst = await api('GET', '/api/his/schedule/list?from=2026-01-01&to=2026-12-31&page=1&size=200', null, token);
    for (const r of (lst.data && lst.data.records) || []) {
      if (r.room === MARK) { await api('DELETE', '/api/his/schedule/' + r.id, null, token); del++; }
    }
    const tl = await api('GET', '/api/his/schedule/template/list?page=1&size=200', null, token);
    for (const r of (tl.data && tl.data.records) || []) {
      if (r.room === MARK) { await api('DELETE', '/api/his/schedule/template/' + r.id, null, token); del++; }
    }
    pass('清理历史E2E标记数据: ' + del + ' 条');
    /* 全量医师(不限科室)取前两位, 科室取各自归属 */
    const depts = await api('GET', '/api/his/dept/enabled', null, token);
    const deptList = depts.data || [];
    deptNameOf = id => { const d = deptList.find(x => x.id === id); return d ? d.deptName : ('科室#' + id); };
    const st = await api('GET', '/api/his/staff/list?staffType=' + encodeURIComponent('医师') + '&page=1&size=200', null, token);
    const recs = (st.data && st.data.records) || st.data || [];
    s1 = recs[0]; s2 = recs[1];
    if (!s1 || !s2) throw new Error('系统医师不足2人(records=' + recs.length + '), 无法测试');
    pass('测试基础: 医师1=' + s1.staffName + '(' + s1.staffNo + '@' + (s1.deptName || deptNameOf(s1.deptId)) + ')'
      + ' 医师2=' + s2.staffName + '(' + s2.staffNo + '@' + (s2.deptName || deptNameOf(s2.deptId)) + ')');
    /* 造排班: 本周3条(开放2+停诊1) + 上周1条(复制验证), 科室随医师归属 */
    const mk = (staff, date, tt, code, name, fee, num, status, reason) =>
      api('POST', '/api/his/schedule', { deptId: staff.deptId, staffId: staff.id, workDate: date, timeType: tt,
        regLevelCode: code, regLevelName: name, regFee: fee, totalNum: num, status: status, room: MARK, stopReason: reason || '' }, token);
    await mk(s1, WK1, 'am', '01', '普通号', 10, 30, 1, '');
    await mk(s1, addD(WK1, 2), 'pm', '02', '副主任医师号', 20, 20, 1, '');
    await mk(s2, addD(WK1, 1), 'am', '06', '急诊号', 10, 10, 0, 'E2E测试停诊');
    await mk(s1, addD(LW1, 1), 'pm', '01', '普通号', 10, 30, 1, '');
    pass('已创建排班: 本周3条(开放2+停诊1) + 上周1条(复制上周验证用)');
    /* 造模板: 医师1 周五am 普通号(按模板生成验证用) */
    const tp = await api('POST', '/api/his/schedule/template', { staffId: s1.id, deptId: s1.deptId, deptName: s1.deptName || deptNameOf(s1.deptId),
      weekday: 5, timeType: 'am', regLevelCode: '01', regLevelName: '普通号', regFee: 10, totalNum: 25, room: MARK, status: 1 }, token);
    pass('已创建模板' + (tp.data && tp.data.id ? '#' + tp.data.id : '') + '(医师1 周五am 普通号25号源)');
    /* 造数自检 */
    const chk = await api('GET', '/api/his/schedule/list?from=' + LW1 + '&to=' + WK7 + '&page=1&size=100', null, token);
    const n = ((chk.data && chk.data.records) || []).filter(r => r.room === MARK).length;
    if (n === 4) pass('造数自检: 标记排班=4条 ✓');
    else fail('造数自检异常: 标记排班=' + n + ' 条(期望4)');
  });

  /* ===== 浏览器 ===== */
  const browser = await chromium.launch({ executablePath: EDGE, headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1680, height: 950 } });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message.slice(0, 300)));

  /* 点击左侧菜单(不可见时先展开"门诊挂号台"组) */
  async function menu(text) {
    await page.waitForSelector('.el-menu-item', { timeout: 15000 });
    const item = page.locator('.el-menu-item', { hasText: text }).first();
    if (!(await item.count())) throw new Error('菜单项不存在(DOM): ' + text);
    if (!(await item.isVisible())) {
      const group = page.locator('.el-sub-menu__title', { hasText: '门诊挂号台' }).first();
      if (!(await group.count())) throw new Error('门诊挂号台菜单组不存在');
      await group.click();
      await page.waitForTimeout(700);
    }
    if (!(await item.isVisible())) throw new Error('菜单项展开后仍不可见: ' + text);
    await item.click();
    await page.waitForTimeout(1000);
  }
  const weekLabel = async () => (await page.locator('.sched-week-label').first().innerText()).trim();
  const visDialog = () => page.locator('.el-dialog:visible');

  await step('1. 登录系统', async () => {
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.login-box', { timeout: 15000 });
    await page.locator('button', { hasText: '登 录' }).click();
    await page.waitForSelector('.layout', { timeout: 15000 });
    await page.waitForSelector('.el-menu-item', { timeout: 15000 });
    pass('登录成功(admin/牵头ADMIN), 主布局+菜单已渲染');
  });

  await step('2. 排班号源: 周视图渲染', async () => {
    await menu('排班号源');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    const title = await page.locator('.page-title').first().innerText();
    if (title.indexOf('排班号源') >= 0) pass('页面标题正确: ' + title.split('\n')[0]); else fail('页面标题异常: ' + title);
    /* 视图切换按钮 */
    const radios = (await page.locator('.el-radio-button').allInnerTexts()).join(',');
    if (radios.indexOf('周视图') >= 0 && radios.indexOf('列表视图') >= 0) pass('视图切换: ' + radios);
    else fail('视图切换按钮缺失: ' + radios);
    /* 周导航 */
    for (const b of ['上一周', '下一周', '本周']) {
      if (await page.locator('button:visible', { hasText: b }).count()) pass('周导航按钮存在: ' + b);
      else fail('周导航按钮缺失: ' + b);
    }
    const lb = await weekLabel();
    if (lb.indexOf(WK1) === 0 && lb.indexOf(WK7) > 0) pass('周标签正确: ' + lb + ' (本周一~周日)');
    else fail('周标签异常: "' + lb + '" 期望 ' + WK1 + ' ~ ' + WK7);
    /* 等待周数据加载 */
    await page.waitForSelector('.schedule-card', { timeout: 10000 });
    await page.waitForTimeout(500);
    /* 表头: 7天+医师列(整页文本断言, EP2.3 fixed列为sticky单表格实现不克隆DOM) */
    const pageTxt = await page.locator('.page-card').innerText();
    const missHdr = ['医师 / 科室', '周一', '周二', '周三', '周四', '周五', '周六', '周日'].filter(h => pageTxt.indexOf(h) < 0);
    if (!missHdr.length) pass('周矩阵表头完整(医师/科室 + 周一~周日)');
    else fail('周矩阵表头缺失: ' + missHdr.join(','));
    /* today 列高亮(今日必在本周) */
    if (await page.locator('.sched-col-hd.today').count()) pass('今日列高亮: .sched-col-hd.today 存在');
    else fail('今日列高亮缺失');
    /* 医师行与卡片 */
    const staffs = await page.locator('.sched-staff').allInnerTexts();
    const hasBoth = staffs.indexOf(s1.staffName) >= 0 && staffs.indexOf(s2.staffName) >= 0;
    if (hasBoth) pass('周矩阵含两位测试医师行 (' + staffs.join(',') + ')');
    else fail('周矩阵医师行异常: ' + staffs.join(','));
    const cards = await page.locator('.schedule-card').count();
    const stops = await page.locator('.schedule-card.is-stop').count();
    if (cards >= 3) pass('排班卡片≥3张 (医师1:2张开放 + 医师2:1张停诊), 实际=' + cards);
    else fail('排班卡片数不足: ' + cards);
    if (stops >= 1) pass('停诊卡片样式 is-stop≥1 (医师2周二am, 灰底删除线)');
    else fail('停诊卡片样式缺失');
    /* 卡片内容: 号别+余量+诊室 */
    const cardTxt = (await page.locator('.schedule-card').first().innerText()).replace(/\s+/g, ' ').trim();
    if (cardTxt.indexOf('普通号') >= 0 && cardTxt.indexOf('30') >= 0 && cardTxt.indexOf(MARK) >= 0)
      pass('卡片内容完整(时段/号别/余量/诊室): ' + cardTxt);
    else fail('卡片内容异常: ' + cardTxt);
    /* 空槽快捷新增 */
    const empties = await page.locator('.schedule-empty').count();
    if (empties > 0) pass('空槽快捷新增入口存在: ' + empties + ' 处(+排班)');
    else fail('空槽快捷新增入口缺失');
    /* 号源统计条 */
    await page.waitForTimeout(300);
    const chips = await page.locator('.sched-stat-chip').count();
    if (chips >= 1) {
      const chip = (await page.locator('.sched-stat-chip').first().innerText()).replace(/\s+/g, ' ').trim();
      pass('号源统计条: ' + chips + ' 科室 -> ' + chip);
    } else fail('号源统计条缺失(本周应有数据)');
    /* lead 操作按钮 */
    for (const b of ['按模板排班', '复制上周', '管理模板']) {
      if (await page.locator('button:visible', { hasText: b }).count()) pass('牵头操作按钮存在: ' + b);
      else fail('牵头操作按钮缺失: ' + b);
    }
    await page.screenshot({ path: OUT + '/verify_sched_01_week.png', fullPage: false });
  });

  await step('3. 周导航(上一周/下一周/本周)', async () => {
    await page.locator('button:visible', { hasText: '上一周' }).click();
    await page.waitForTimeout(900);
    let lb = await weekLabel();
    if (lb.indexOf(LW1) === 0) pass('上一周: ' + lb);
    else fail('上一周标签异常: "' + lb + '" 期望起点 ' + LW1);
    await page.locator('button:visible', { hasText: '下一周' }).click();
    await page.waitForTimeout(900);
    lb = await weekLabel();
    if (lb.indexOf(WK1) === 0) pass('下一周(回到本周): ' + lb);
    else fail('下一周标签异常: "' + lb + '" 期望起点 ' + WK1);
    await page.locator('button:visible', { hasText: '下一周' }).click();
    await page.waitForTimeout(900);
    lb = await weekLabel();
    if (lb.indexOf(addD(WK1, 7)) === 0) pass('再下一周: ' + lb);
    else fail('再下一周标签异常: "' + lb + '" 期望起点 ' + addD(WK1, 7));
    await page.locator('button:visible', { hasText: '本周' }).click();
    await page.waitForTimeout(900);
    lb = await weekLabel();
    if (lb.indexOf(WK1) === 0) pass('回本周: ' + lb);
    else fail('回本周标签异常: "' + lb + '"');
    await page.waitForSelector('.schedule-card', { timeout: 10000 });
    await page.screenshot({ path: OUT + '/verify_sched_02_week_nav.png' });
  });

  await step('4. 周视图卡片点击→编辑排班', async () => {
    await page.locator('.schedule-card:visible').first().click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 8000 });
    const dt = await visDialog().locator('.el-dialog__title').innerText();
    if (dt.indexOf('编辑排班') >= 0) pass('编辑排班对话框打开: ' + dt); else fail('对话框标题异常: ' + dt);
    await page.waitForTimeout(400);
    const body = await visDialog().innerText();
    if (body.indexOf('已挂号数') >= 0) pass('编辑模式显示"已挂号数"');
    else fail('编辑模式缺少"已挂号数"提示');
    if (body.indexOf('出诊医师') >= 0 && body.indexOf('时段') >= 0 && body.indexOf('号别') >= 0 && body.indexOf('总号源') >= 0 && body.indexOf('诊室') >= 0)
      pass('表单字段完整(医师/时段/号别/总号源/诊室)');
    else fail('表单字段缺失');
    pass('对话框内容节选: ' + body.replace(/\s+/g, ' ').slice(0, 90) + '...');
    await page.screenshot({ path: OUT + '/verify_sched_03_edit_dialog.png' });
    await visDialog().locator('.el-dialog__footer button', { hasText: '取消' }).click();
    await page.waitForTimeout(500);
  });

  await step('5. 空槽点击→快捷新增(自动带医师/日期)', async () => {
    const empties = page.locator('.schedule-empty');
    const n = await empties.count();
    if (!n) { fail('无空槽可测'); return; }
    /* 由空槽所在列索引推导期望日期(第0列为固定医师列, 列i对应weekDays[i-1]) */
    const colIdx = await empties.first().evaluate(el => el.closest('td').cellIndex);
    const expDate = addD(WK1, colIdx - 1);
    const expStaff = (await empties.first().locator('xpath=ancestor::tr').locator('.sched-staff').innerText()).trim();
    await empties.first().click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 8000 });
    const dt = await visDialog().locator('.el-dialog__title').innerText();
    if (dt.indexOf('新增排班') >= 0) pass('新增排班对话框打开: ' + dt); else fail('对话框标题异常: ' + dt);
    await page.waitForTimeout(400);
    /* 日期应自动填为该列日期 */
    const dateVal = await visDialog().locator('.el-date-editor input').first().inputValue();
    if (dateVal === expDate) pass('出诊日期自动带入该列: ' + dateVal);
    else fail('日期未自动带入: "' + dateVal + '" 期望 ' + expDate);
    /* 医师应自动带入该行医师 */
    const staffTxt = await visDialog().locator('.el-select input').first().inputValue().catch(() => '');
    if (staffTxt.indexOf(expStaff) >= 0) pass('出诊医师自动带入: ' + staffTxt + ' (期望含 ' + expStaff + ')');
    else fail('医师未自动带入: "' + staffTxt + '" 期望含 ' + expStaff);
    await page.screenshot({ path: OUT + '/verify_sched_04_addat_dialog.png' });
    await visDialog().locator('.el-dialog__footer button', { hasText: '取消' }).click();
    await page.waitForTimeout(500);
  });

  await step('6. 列表视图: 列渲染+分页', async () => {
    await page.locator('.el-radio-button', { hasText: '列表视图' }).click();
    await page.waitForSelector('.el-table__row', { timeout: 10000 });
    await page.waitForTimeout(800);
    if (await page.locator('button:visible', { hasText: '新增排班' }).count()) pass('列表视图专属"新增排班"按钮出现');
    else fail('新增排班按钮缺失');
    /* 列头(整页文本断言) */
    const pageTxt = await page.locator('.page-card').innerText();
    const missCol = ['出诊日期', '星期', '时段', '科室', '医师', '工号', '号别', '挂号费', '号源(余/总)', '诊室', '状态', '停诊原因', '操作'].filter(h => pageTxt.indexOf(h) < 0);
    if (!missCol.length) pass('列表列头完整(13列)');
    else fail('列表列缺失: ' + missCol.join(','));
    /* 本周3条E2E数据 */
    const rows = await page.locator('.el-table__row').count();
    if (rows >= 3) pass('列表行数≥3 (本周E2E数据), 实际=' + rows);
    else fail('列表行数不足: ' + rows + ' (期望≥3)');
    /* 停诊行: tag+原因 */
    const stopRow = page.locator('.el-table__row', { hasText: 'E2E测试停诊' });
    if (await stopRow.count()) {
      const tag = await stopRow.first().locator('.el-tag').innerText();
      if (tag.indexOf('停诊') >= 0) pass('停诊行: tag=' + tag + ', 原因列显示');
      else fail('停诊行tag异常: ' + tag);
    } else fail('停诊行(E2E测试停诊)未找到');
    /* 开放行 tag */
    const openRow = page.locator('.el-table__row', { hasText: WK1 }).first();
    if (await openRow.count()) {
      const tag = await openRow.locator('.el-tag').innerText();
      if (tag.indexOf('开放') >= 0) pass('开放行: tag=' + tag + ' (' + WK1 + ' 医师1周一am)');
      else fail('开放行tag异常: ' + tag);
    }
    /* 序号列 */
    const seq = (await page.locator('.el-table__row').first().locator('td').first().innerText()).trim();
    if (seq === '1') pass('序号列起始=1');
    else fail('序号列异常: ' + seq);
    /* 分页 */
    if (await page.locator('.el-pagination').count()) {
      const pg = (await page.locator('.el-pagination').innerText()).replace(/\s+/g, ' ').trim();
      pass('分页组件: ' + pg);
    } else fail('分页组件缺失');
    await page.screenshot({ path: OUT + '/verify_sched_05_list.png' });
  });

  await step('7. 批量停诊(勾选→执行→API复核)', async () => {
    /* 勾选医师1周一am(开放)行 */
    const row = page.locator('.el-table__row', { hasText: WK1 }).first();
    if (!(await row.count())) { fail('未找到' + WK1 + '行(医师1周一am)'); return; }
    await row.locator('.el-checkbox').click();
    await page.waitForTimeout(400);
    if (await page.locator('.sched-batchbar').count()) {
      const t = (await page.locator('.sched-batchbar').innerText()).replace(/\s+/g, ' ').trim();
      pass('批量操作条出现: ' + t);
    } else fail('批量操作条未出现');
    await page.locator('.sched-batchbar button', { hasText: '批量停诊' }).click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 8000 });
    const dt = await visDialog().locator('.el-dialog__title').innerText();
    if (dt.indexOf('批量停诊') >= 0) pass('批量停诊对话框打开'); else fail('对话框标题异常: ' + dt);
    const body = await visDialog().innerText();
    if (body.indexOf('1 条') >= 0) pass('选中条数提示正确: 1 条');
    else fail('选中条数提示异常: ' + body.replace(/\s+/g, ' ').slice(0, 80));
    /* 停诊原因为空时拦截 */
    await visDialog().locator('.el-dialog__footer button', { hasText: '确认停诊' }).click();
    await page.waitForTimeout(400);
    if (await page.locator('.el-message--warning', { hasText: '停诊原因' }).count()) pass('空原因拦截: warning提示');
    else fail('空原因未拦截');
    /* 填写原因并执行 */
    await visDialog().locator('textarea').fill('E2E批量停诊验证');
    await visDialog().locator('.el-dialog__footer button', { hasText: '确认停诊' }).click();
    await page.locator('.el-message--success', { timeout: 10000 }).first().waitFor();
    const msg = await page.locator('.el-message--success').first().innerText();
    pass('批量停诊执行: ' + msg.trim());
    await page.waitForTimeout(900);
    /* 行 tag 应变为停诊 */
    const tag = await page.locator('.el-table__row', { hasText: WK1 }).first().locator('.el-tag').innerText();
    if (tag.indexOf('停诊') >= 0) pass('列表行状态已变停诊(tag=' + tag + ')');
    else fail('列表行状态未变: ' + tag);
    await page.screenshot({ path: OUT + '/verify_sched_06_batchstop.png' });
    /* API 复核 */
    const lst = await api('GET', '/api/his/schedule/list?from=' + WK1 + '&to=' + WK7 + '&page=1&size=100', null, token);
    const recs = (lst.data && lst.data.records) || [];
    const hit = recs.find(r => r.staff_id === s1.id && r.work_date === WK1 && r.time_type === 'am');
    if (hit && hit.status === 0 && hit.stop_reason === 'E2E批量停诊验证')
      pass('API复核: 医师1周一am已停诊, 原因="' + hit.stop_reason + '"');
    else fail('API复核失败: ' + JSON.stringify(hit).slice(0, 150));
  });

  await step('8. 模板管理抽屉(列表+新增+批量创建)', async () => {
    await page.locator('button:visible', { hasText: '管理模板' }).click();
    await page.waitForSelector('.el-drawer', { timeout: 8000 });
    const dh = await page.locator('.el-drawer__header').innerText();
    if (dh.indexOf('排班模板管理') >= 0) pass('模板抽屉打开: ' + dh.trim()); else fail('抽屉标题异常: ' + dh);
    await page.waitForTimeout(800);
    for (const b of ['新增模板', '批量创建']) {
      if (await page.locator('.el-drawer button', { hasText: b }).count()) pass('抽屉按钮存在: ' + b);
      else fail('抽屉按钮缺失: ' + b);
    }
    /* 模板列表含 E2E 模板 */
    const tplRow = page.locator('.el-drawer .el-table__row', { hasText: MARK });
    if (await tplRow.count()) {
      const t = (await tplRow.first().innerText()).replace(/\s+/g, ' ').trim();
      pass('模板列表含E2E模板: ' + t);
    } else fail('模板列表未见E2E模板(room=' + MARK + ')');
    await page.screenshot({ path: OUT + '/verify_sched_07_drawer.png' });
    /* 8a 新增模板对话框(嵌套, append-to-body) */
    await page.locator('.el-drawer button', { hasText: '新增模板' }).click();
    await page.waitForTimeout(600);
    const t1 = await visDialog().locator('.el-dialog__title').innerText().catch(() => '');
    if (t1.indexOf('新增模板') >= 0) {
      pass('新增模板对话框打开(append-to-body嵌套)');
      const fb = await visDialog().innerText();
      if (fb.indexOf('出诊医师') >= 0 && fb.indexOf('星期') >= 0 && fb.indexOf('时段') >= 0 && fb.indexOf('号源数') >= 0)
        pass('模板表单字段完整(医师/星期/时段/号源数)');
      else fail('模板表单字段缺失');
      await page.screenshot({ path: OUT + '/verify_sched_08_tpl_form.png' });
      await visDialog().locator('.el-dialog__footer button', { hasText: '取消' }).click();
      await page.waitForTimeout(600);
    } else fail('新增模板对话框未打开: "' + t1 + '"');
    /* 8b 批量创建对话框: 科室→医师多选→时段规律→添加时段 */
    await page.locator('.el-drawer button', { hasText: '批量创建' }).click();
    await page.waitForTimeout(600);
    const t2 = await visDialog().locator('.el-dialog__title').innerText().catch(() => '');
    if (t2.indexOf('批量创建排班模板') >= 0) {
      pass('批量创建对话框打开');
      const bb = await visDialog().innerText();
      if (bb.indexOf('医师(多选)') >= 0 && bb.indexOf('排班规律') >= 0) pass('批量创建结构完整(科室/医师多选/排班规律)');
      else fail('批量创建结构缺失: ' + bb.replace(/\s+/g, ' ').slice(0, 80));
      const before = await visDialog().locator('.el-table__row').count();
      await visDialog().locator('button', { hasText: '添加时段' }).click();
      await page.waitForTimeout(400);
      const after = await visDialog().locator('.el-table__row').count();
      if (after === before + 1) pass('+添加时段生效: ' + before + ' → ' + after + ' 行');
      else fail('添加时段无效: ' + before + ' → ' + after);
      await page.screenshot({ path: OUT + '/verify_sched_09_batch_tpl.png' });
      await visDialog().locator('.el-dialog__footer button', { hasText: '取消' }).click();
      await page.waitForTimeout(600);
    } else fail('批量创建对话框未打开: "' + t2 + '"');
    /* 关闭抽屉 */
    await page.locator('.el-drawer__close-btn').click();
    await page.waitForTimeout(700);
    if (!(await page.locator('.el-drawer:visible').count())) pass('抽屉已关闭');
    else fail('抽屉未关闭');
  });

  await step('9. 按模板生成排班(执行+API复核)', async () => {
    await page.locator('button:visible', { hasText: '按模板排班' }).click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 8000 });
    const dt = await visDialog().locator('.el-dialog__title').innerText();
    if (dt.indexOf('按模板生成排班') >= 0) pass('按模板生成对话框打开'); else fail('对话框标题异常: ' + dt);
    await page.waitForTimeout(800);
    /* 勾选 E2E 模板行 */
    const tplRow = visDialog().locator('.el-table__row', { hasText: MARK });
    if (!(await tplRow.count())) { fail('生成对话框无E2E模板行'); return; }
    await tplRow.first().locator('.el-checkbox').click();
    await page.waitForTimeout(400);
    const genBtn = visDialog().locator('.el-dialog__footer button', { hasText: '生成(' });
    const btnTxt = await genBtn.innerText();
    if (btnTxt.indexOf('1个模板') >= 0) pass('已选中1个模板: ' + btnTxt.trim());
    else fail('选中模板计数异常: ' + btnTxt);
    await page.screenshot({ path: OUT + '/verify_sched_10_generate.png' });
    /* 执行生成(MessageBox确认) */
    await genBtn.click();
    await page.waitForSelector('.el-message-box', { timeout: 8000 });
    const boxTxt = await page.locator('.el-message-box__message').innerText();
    if (boxTxt.indexOf('生成') >= 0) pass('二次确认: ' + boxTxt.trim().slice(0, 60) + '...');
    else fail('确认文案异常: ' + boxTxt);
    await page.locator('.el-message-box__btns .el-button--primary').click();
    await page.locator('.el-message--success', { timeout: 10000 }).first().waitFor();
    const msg = await page.locator('.el-message--success').first().innerText();
    pass('生成结果: ' + msg.trim());
    await page.waitForTimeout(600);
    /* API 复核: 本周五(医师1, am, room=MARK)新生成 */
    const lst = await api('GET', '/api/his/schedule/list?from=' + WK1 + '&to=' + WK7 + '&page=1&size=100', null, token);
    const recs = (lst.data && lst.data.records) || [];
    const fri = addD(WK1, 4);
    const hit = recs.find(r => r.staff_id === s1.id && r.work_date === fri && r.time_type === 'am' && r.room === MARK);
    if (hit) pass('API复核: 周五am排班已生成(id=' + hit.id + ', 号别=' + hit.reg_level_name + ', 总' + hit.total_num + ')');
    else fail('API复核失败: 周五(' + fri + ')am未生成');
  });

  await step('10. 复制上周(执行+API复核)', async () => {
    /* 防御: 关闭可能残留的浮层 */
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    /* 切回周视图(复制上周按钮仅周视图显示) */
    await page.locator('.el-radio-button', { hasText: '周视图' }).click();
    await page.waitForTimeout(900);
    await page.waitForSelector('.schedule-card', { timeout: 10000 });
    await page.locator('button:visible', { hasText: '复制上周' }).click();
    await page.waitForSelector('.el-message-box', { timeout: 8000 });
    const boxTxt = await page.locator('.el-message-box__message').innerText();
    if (boxTxt.indexOf(LW1) >= 0 && boxTxt.indexOf(WK1) >= 0) pass('复制确认文案含上周/本周: ' + boxTxt.trim().slice(0, 60) + '...');
    else fail('复制确认文案异常: ' + boxTxt);
    await page.locator('.el-message-box__btns .el-button--primary').click();
    await page.locator('.el-message--success', { timeout: 10000 }).first().waitFor();
    const msg = await page.locator('.el-message--success').first().innerText();
    pass('复制结果: ' + msg.trim());
    await page.waitForTimeout(900);
    /* API 复核: 本周二pm(医师1)新复制 */
    const lst = await api('GET', '/api/his/schedule/list?from=' + WK1 + '&to=' + WK7 + '&page=1&size=100', null, token);
    const recs = (lst.data && lst.data.records) || [];
    const tue = addD(WK1, 1);
    const hit = recs.find(r => r.staff_id === s1.id && r.work_date === tue && r.time_type === 'pm' && r.room === MARK);
    if (hit) pass('API复核: 本周二pm已复制(id=' + hit.id + ', 余' + hit.left_num + '/' + hit.total_num + ')');
    else fail('API复核失败: 周二pm未复制');
    /* 周视图最终态: is-stop卡片≥2(医师1周一am已停诊+医师2周二am停诊) */
    const stops = await page.locator('.schedule-card.is-stop').count();
    if (stops >= 2) pass('周视图停诊卡片 is-stop=' + stops + ' (批量停诊结果已反映)');
    else fail('周视图停诊卡片数异常: ' + stops);
    await page.screenshot({ path: OUT + '/verify_sched_11_week_final.png', fullPage: false });
  });

  await browser.close();

  /* ===== 11. console 错误汇总 ===== */
  console.log('\n==== 11. 页面console错误检查 ====');
  if (consoleErrors.length) {
    const uniq = [...new Set(consoleErrors)];
    const fatal = uniq.filter(e => e.indexOf('Uncaught') >= 0 || e.indexOf('TypeError') >= 0 || e.indexOf('SyntaxError') >= 0 || e.indexOf('pageerror') >= 0);
    if (fatal.length) { fatal.slice(0, 5).forEach(e => fail('console错误: ' + e)); }
    else { pass('console无致命错误(' + uniq.length + '条非致命, 如资源404):'); uniq.slice(0, 5).forEach(e => console.log('    -', e)); }
  } else pass('页面console零错误');

  /* ===== 12. 清理E2E数据 ===== */
  await step('12. 清理E2E标记数据', async () => {
    let del = 0;
    const lst = await api('GET', '/api/his/schedule/list?from=2026-01-01&to=2026-12-31&page=1&size=200', null, token);
    for (const r of (lst.data && lst.data.records) || []) {
      if (r.room === MARK) { await api('DELETE', '/api/his/schedule/' + r.id, null, token); del++; }
    }
    const tl = await api('GET', '/api/his/schedule/template/list?page=1&size=200', null, token);
    for (const r of (tl.data && tl.data.records) || []) {
      if (r.room === MARK) { await api('DELETE', '/api/his/schedule/template/' + r.id, null, token); del++; }
    }
    pass('已清理E2E标记数据: ' + del + ' 条(排班+模板)');
  });

  /* ===== 汇总 ===== */
  const p = results.filter(r => r[0] === 'PASS').length;
  const f = results.filter(r => r[0] === 'FAIL').length;
  console.log('\n========== 汇总: PASS ' + p + ' / FAIL ' + f + ' ==========');
  if (f) results.filter(r => r[0] === 'FAIL').forEach(r => console.log('  FAIL>', r[1]));
  process.exit(f ? 1 : 0);
})().catch(e => { console.error('FATAL:', e); process.exit(2); });
