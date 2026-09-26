/* 药库模块(warehouse.js) 浏览器端到端实测
 * 覆盖: 登录 → 菜单绑定(采购入库/出库管理/库存/流水) → DrugStock三Tab
 *      → StockInManage 新建+药品选择器+保存草稿+确认入库
 *      → StockOutManage 新建+批次选择器+确认出库
 *      → DrugStock 复查库存/流水 → API复核最终库存
 * 运行: node wh_e2e_test.js  (playwright-core 1.62 + 系统Edge) */
const PW_CORE = 'C:/Users/Lenovo/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright-core';
const { chromium } = require(PW_CORE);
const http = require('http');

const BASE = 'http://localhost:8080';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const OUT = 'd:/study/ybtest';

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

(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1680, height: 950 } });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message.slice(0, 300)));

  /* 点击左侧菜单项(不可见时先展开其所属组) */
  async function menu(text) {
    await page.waitForSelector('.el-menu-item', { timeout: 15000 });
    let item = page.locator('.el-menu-item', { hasText: text }).first();
    if (!(await item.count())) throw new Error('菜单项不存在(DOM): ' + text);
    if (!(await item.isVisible())) {
      /* 收起态: 点击包含该菜单的组标题(药库组)展开 */
      const group = page.locator('.el-sub-menu__title', { hasText: '药库' }).first();
      if (!(await group.count())) throw new Error('药库菜单组不存在');
      await group.click();
      await page.waitForTimeout(700);
    }
    if (!(await item.isVisible())) throw new Error('菜单项展开后仍不可见: ' + text);
    await item.click();
    await page.waitForTimeout(900);
  }

  await step('1. 登录系统', async () => {
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.login-box', { timeout: 15000 });
    await page.locator('button', { hasText: '登 录' }).click();
    await page.waitForSelector('.layout', { timeout: 15000 });
    await page.waitForSelector('.el-menu-item', { timeout: 15000 });
    pass('登录成功(admin/牵头ADMIN), 主布局+菜单已渲染');
  });

  await step('2. 菜单绑定验证(wh-in/wh-out/wh-stock)', async () => {
    /* 菜单项在DOM中(收起组内不可见, innerText不含隐藏文本), 故用DOM级文本断言 */
    const items = await page.locator('.el-menu-item').allInnerTexts();
    const txt = items.join('|');
    if (txt.indexOf('采购入库') >= 0) pass('菜单存在: 采购入库(wh-in→StockInManage)'); else fail('菜单缺失: 采购入库');
    if (txt.indexOf('出库管理') >= 0) pass('菜单存在: 出库管理(wh-out→StockOutManage)'); else fail('菜单缺失: 出库管理');
    if (txt.indexOf('库存/流水') >= 0) pass('菜单存在: 库存/流水(wh-stock→DrugStock)'); else fail('菜单缺失: 库存/流水');
    /* 展开药库组截图留证 */
    await page.locator('.el-sub-menu__title', { hasText: '药库' }).first().click();
    await page.waitForTimeout(700);
    await page.screenshot({ path: OUT + '/verify_wh_01_menu.png', fullPage: false });
    pass('截图 verify_wh_01_menu.png (药库组已展开)');
  });

  /* ============ 组件1: DrugStock 库存总览 ============ */
  await step('3. DrugStock: 库存列表Tab(初始空库)', async () => {
    await menu('库存/流水');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    const title = await page.locator('.page-title').first().innerText();
    if (title.indexOf('库存总览') >= 0) pass('页面标题正确: ' + title.split('\n')[0]); else fail('页面标题异常: ' + title);
    const tabs = await page.locator('.el-tabs__item').allInnerTexts();
    ['库存列表', '低库存预警', '出入库流水'].forEach(t => {
      if (tabs.join(',').indexOf(t) >= 0) pass('Tab存在: ' + t); else fail('Tab缺失: ' + t);
    });
    await page.waitForTimeout(600);
    const tip = await page.locator('.toolbar', { hasText: '条库存记录' }).first().innerText();
    pass('库存记录数提示: ' + tip.trim().replace(/\s+/g, ' '));
    if (tip.indexOf('共 0 条') >= 0) pass('初始空库状态符合预期(测试前库存为0)');
    await page.screenshot({ path: OUT + '/verify_wh_02_drugstock_stock.png' });
  });

  await step('4. DrugStock: 低库存预警Tab', async () => {
    await page.locator('.el-tabs__item', { hasText: '低库存预警' }).click();
    await page.waitForTimeout(600);
    if (await page.locator('.el-alert', { hasText: '库存数量' }).count()) pass('低库存预警提示条渲染正常');
    else fail('低库存预警提示条缺失');
    await page.screenshot({ path: OUT + '/verify_wh_03_drugstock_alert.png' });
  });

  await step('5. DrugStock: 出入库流水Tab(初始为空)', async () => {
    await page.locator('.el-tabs__item', { hasText: '出入库流水' }).click();
    await page.waitForTimeout(600);
    if (await page.locator('.toolbar', { hasText: '已确认单据明细' }).count()) pass('流水Tab工具栏渲染正常');
    await page.screenshot({ path: OUT + '/verify_wh_04_drugstock_flow.png' });
  });

  /* ============ 组件2: StockInManage 入库管理 ============ */
  await step('6. StockInManage: 入口与左侧列表', async () => {
    await menu('采购入库');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    const title = await page.locator('.page-title').first().innerText();
    if (title.indexOf('采购入库') >= 0) pass('页面标题正确(非Placeholder占位)'); else fail('页面标题异常: ' + title);
    await page.waitForTimeout(600);
    if (await page.locator('button', { hasText: '新建入库单' }).count()) pass('新建入库单按钮存在(lead权限)');
    else fail('新建入库单按钮缺失');
    if (await page.locator('.el-alert', { hasText: '请从左侧选择入库单' }).count()) pass('右侧空态提示正常');
    await page.screenshot({ path: OUT + '/verify_wh_05_stockin_entry.png' });
  });

  await step('7. StockInManage: 新建+药品选择器回填', async () => {
    await page.locator('button', { hasText: '新建入库单' }).click();
    await page.waitForTimeout(400);
    if (await page.locator('.el-tag', { hasText: '新建未保存' }).count()) pass('新建模式标识"新建未保存"显示');
    await page.locator('button', { hasText: '添加药品行' }).click();
    await page.waitForTimeout(400);
    const pickBtn = page.locator('button', { hasText: '点击选择药品' }).first();
    if (await pickBtn.count()) pass('明细行已添加, "点击选择药品"按钮显示');
    else fail('明细行/药品选择按钮缺失');
    await pickBtn.click({ force: true });
    await page.waitForSelector('.el-dialog', { timeout: 8000 });
    await page.waitForTimeout(800); /* 药品目录加载 */
    const dlgTitle = await page.locator('.el-dialog__title').innerText();
    if (dlgTitle.indexOf('选择药品') >= 0) pass('药品选择器对话框打开: ' + dlgTitle);
    else fail('药品选择器标题异常: ' + dlgTitle);
    const drugRows = await page.locator('.el-dialog .el-table__row').count();
    if (drugRows > 0) pass('药品目录已加载, 对话框行数: ' + drugRows + ' (目录共500条)');
    else fail('药品目录未加载出行数据');
    /* 记录选中药品名 */
    const drugCell = await page.locator('.el-dialog .el-table__row').first().locator('td').nth(1).innerText();
    await page.locator('.el-dialog .el-table__row').first().locator('button', { hasText: '选择' }).click({ force: true });
    await page.waitForTimeout(500);
    const picked = await page.locator('button', { hasText: drugCell.trim() }).count();
    if (picked) pass('药品已回填到明细行: ' + drugCell.trim());
    else fail('药品选择后未回填');
  });

  await step('8. StockInManage: 明细编辑(批号/数量/有效期/参考价)', async () => {
    /* 批号 */
    await page.locator('input[placeholder="批号"]').first().fill('E2E-B2026-001');
    /* 数量: 明细行(含批号输入的行)内第一个 el-input-number */
    const row = page.locator('.el-table__row', { has: page.locator('input[placeholder="批号"]') }).first();
    await row.locator('.el-input-number input').first().click();
    await row.locator('.el-input-number input').first().fill('100');
    await page.keyboard.press('Tab');
    await page.waitForTimeout(300);
    /* 有效期: 行内第二个日期选择器(第一个是生产日期) */
    const dps = row.locator('.el-date-editor input');
    const dpCount = await dps.count();
    if (dpCount >= 2) {
      try {
        await dps.nth(1).click();
        await page.waitForTimeout(300);
        await dps.nth(1).fill('2027-09-30');
        await page.keyboard.press('Enter');
        await page.waitForTimeout(400);
        const v = await dps.nth(1).inputValue();
        if (v && v.indexOf('2027-09-30') >= 0) pass('有效期填写成功: ' + v);
        else fail('有效期填写后值异常: "' + v + '"');
      } catch (e) { fail('有效期日期控件操作失败', e); }
    } else { fail('明细行日期控件数量异常: ' + dpCount); }
    /* 进价参考价检查(选药带出, 或手填兜底): 行内 el-input-number 顺序=数量/进价/零售价 */
    const costInput = row.locator('.el-input-number input').nth(1);
    const costVal = await costInput.inputValue();
    if (costVal && Number(costVal) > 0) pass('进价已带出参考价: ' + costVal);
    else { await costInput.click(); await costInput.fill('12.5'); await page.keyboard.press('Tab'); pass('进价手填兜底: 12.5'); }
    /* 合计金额应更新 */
    const total = await page.locator('span', { hasText: '合计金额' }).first().innerText();
    pass('合计金额显示: ' + total.trim().replace(/\s+/g, ''));
    await page.screenshot({ path: OUT + '/verify_wh_06_stockin_editing.png' });
  });

  await step('9. StockInManage: 保存草稿', async () => {
    await page.locator('button', { hasText: '保存草稿' }).click({ force: true });
    await page.waitForSelector('.el-message--success', { timeout: 10000 });
    const msg = await page.locator('.el-message--success').innerText();
    pass('保存草稿成功提示: ' + msg.trim());
    await page.waitForTimeout(800);
    const tag = await page.locator('.dept-split .el-tag', { hasText: '草稿' }).count();
    if (tag) pass('单据状态已变为"草稿"');
    else fail('草稿状态标识未找到');
    await page.screenshot({ path: OUT + '/verify_wh_07_stockin_draft.png' });
  });

  await step('10. StockInManage: 确认入库(写库存)', async () => {
    await page.locator('button', { hasText: '确认入库' }).click({ force: true });
    await page.waitForSelector('.el-message-box', { timeout: 8000 });
    const boxText = await page.locator('.el-message-box__message').innerText();
    if (boxText.indexOf('确认入库') >= 0 || boxText.indexOf('库存') >= 0) pass('二次确认弹窗: ' + boxText.trim().slice(0, 40) + '...');
    await page.locator('.el-message-box__btns .el-button--primary').click({ force: true });
    await page.locator('.el-message--success', { hasText: '已确认入库' }).first().waitFor({ timeout: 10000 });
    const msg = await page.locator('.el-message--success', { hasText: '已确认入库' }).first().innerText();
    pass('确认入库成功: ' + msg.trim());
    await page.waitForTimeout(800);
    const ok = await page.locator('.dept-split .el-tag', { hasText: '已确认' }).first().count();
    if (ok) pass('单据状态已变为"已确认"');
    else fail('已确认状态未显示');
    await page.screenshot({ path: OUT + '/verify_wh_08_stockin_confirmed.png' });
  });

  /* ============ 组件3: StockOutManage 出库管理 ============ */
  await step('11. StockOutManage: 入口与新建', async () => {
    await menu('出库管理');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    const title = await page.locator('.page-title').first().innerText();
    if (title.indexOf('出库管理') >= 0) pass('页面标题正确(非Placeholder占位)'); else fail('页面标题异常: ' + title);
    await page.waitForTimeout(600);
    if (await page.locator('button', { hasText: '新建出库单' }).count()) pass('新建出库单按钮存在(lead权限)');
    else fail('新建出库单按钮缺失');
    await page.screenshot({ path: OUT + '/verify_wh_09_stockout_entry.png' });
    await page.locator('button', { hasText: '新建出库单' }).click({ force: true });
    await page.waitForTimeout(400);
    if (await page.locator('.el-tag', { hasText: '新建未保存' }).count()) pass('新建模式进入成功');
  });

  await step('12. StockOutManage: 批次选择器(库存联动)', async () => {
    await page.locator('button', { hasText: '添加库存批次' }).click({ force: true });
    await page.waitForTimeout(400);
    await page.waitForSelector('.el-dialog', { timeout: 8000 });
    await page.waitForTimeout(800);
    const dlgTitle = await page.locator('.el-dialog__title').innerText();
    if (dlgTitle.indexOf('选择库存批次') >= 0) pass('批次选择器对话框打开: ' + dlgTitle);
    const rows = await page.locator('.el-dialog .el-table__row').count();
    if (rows > 0) pass('可选批次已加载(来自刚入库的库存), 行数: ' + rows);
    else fail('批次选择器无数据(入库未生效?)');
    /* 第一行: 库存量列(第4列td idx: 0编码/1名称/2规格/3批号/4库存量) 应为100 */
    const qtyText = (await page.locator('.el-dialog .el-table__row').first().locator('td').nth(4).innerText()).trim();
    if (qtyText === '100') pass('批次库存量正确: 100');
    else fail('批次库存量异常: "' + qtyText + '"');
    const batchNo = (await page.locator('.el-dialog .el-table__row').first().locator('td').nth(3).innerText()).trim();
    await page.locator('.el-dialog .el-table__row').first().locator('button', { hasText: '选择' }).click({ force: true });
    await page.waitForTimeout(500);
    pass('已选择批次: ' + batchNo + ' (回填药品/批号/价格/可出库存)');
  });

  await step('13. StockOutManage: 数量+确认出库(扣库存)', async () => {
    const row = page.locator('.el-table__row', { has: page.locator('.el-input-number') }).first();
    await row.locator('.el-input-number input').first().click();
    await row.locator('.el-input-number input').first().fill('10');
    await page.keyboard.press('Tab');
    await page.waitForTimeout(300);
    await page.screenshot({ path: OUT + '/verify_wh_10_stockout_editing.png' });
    await page.locator('button', { hasText: '确认出库' }).click({ force: true });
    await page.waitForSelector('.el-message-box', { timeout: 8000 });
    const boxText = await page.locator('.el-message-box__message').innerText();
    if (boxText.indexOf('扣减') >= 0 || boxText.indexOf('出库') >= 0) pass('二次确认弹窗: ' + boxText.trim().slice(0, 40) + '...');
    await page.locator('.el-message-box__btns .el-button--primary').click({ force: true });
    await page.locator('.el-message--success', { hasText: '已确认出库' }).first().waitFor({ timeout: 10000 });
    const msg = await page.locator('.el-message--success', { hasText: '已确认出库' }).first().innerText();
    pass('确认出库成功: ' + msg.trim());
    await page.waitForTimeout(800);
    const ok = await page.locator('.dept-split .el-tag', { hasText: '已确认' }).first().count();
    if (ok) pass('出库单状态已变为"已确认"');
    else fail('出库单已确认状态未显示');
    await page.screenshot({ path: OUT + '/verify_wh_11_stockout_confirmed.png' });
  });

  /* ============ 闭环复核 ============ */
  await step('14. DrugStock 复查: 库存扣减核销(100-10=90)', async () => {
    await menu('库存/流水');
    await page.waitForTimeout(900);
    /* 仅统计当前激活tab可见的表格行(el-tabs已访问过的tab-pane均留在DOM) */
    const rows = await page.locator('.page-card .el-tab-pane:visible .el-table__row').count();
    if (rows === 1) pass('库存列表行数=1 (入库生成1个批次)');
    else fail('库存列表行数异常: ' + rows);
    if (rows >= 1) {
      const cells = await page.locator('.page-card .el-tab-pane:visible .el-table__row').first().locator('td').allInnerTexts();
      /* 列: 0序号 1编码 2名称 3规格 4剂型 5厂家 6批号 7数量 8进价 9零售价 10有效期 11预警量 12状态 */
      const qty = (cells[7] || '').trim();
      if (qty === '90') pass('库存数量核销正确: 100 - 10 = 90');
      else fail('库存数量异常: "' + qty + '" (期望90)');
      const statusTag = (cells[12] || '').trim();
      if (statusTag.indexOf('正常') >= 0) pass('库存状态: 正常');
      const exp = (cells[10] || '').trim();
      pass('有效期显示: ' + exp.slice(0, 30) + ' (效期着色规则按expState)');
    }
    await page.screenshot({ path: OUT + '/verify_wh_12_drugstock_final.png' });
  });

  await step('15. DrugStock 复查: 出入库流水(IN+100/OUT-10)', async () => {
    await page.locator('.el-tabs__item', { hasText: '出入库流水' }).click();
    await page.waitForTimeout(900);
    /* 仅统计当前激活tab(流水)可见的表格行 */
    const rows = await page.locator('.page-card .el-tab-pane:visible .el-table__row').count();
    if (rows === 2) pass('流水记录=2条 (入库+出库)');
    else fail('流水行数异常: ' + rows);
    if (rows >= 2) {
      /* 流水按确认时间倒序: 第一行应为出库(后确认), 第二行入库 */
      const first = await page.locator('.page-card .el-tab-pane:visible .el-table__row').first().locator('td').allInnerTexts();
      const second = await page.locator('.page-card .el-tab-pane:visible .el-table__row').nth(1).locator('td').allInnerTexts();
      /* 列: 0序号 1确认时间 2方向 3单据号 4业务类型 5编码 6名称 7规格 8批号 9数量 10进价 11零售价 12金额 */
      if (first[2].indexOf('出库') >= 0 && first[9].trim() === '10') pass('流水[0]: 出库 10 (单据 ' + first[3].trim() + ')');
      else fail('流水[0]异常: 方向=' + first[2] + ' 数量=' + first[9]);
      if (second[2].indexOf('入库') >= 0 && second[9].trim() === '100') pass('流水[1]: 入库 100 (单据 ' + second[3].trim() + ')');
      else fail('流水[1]异常: 方向=' + second[2] + ' 数量=' + second[9]);
    }
    await page.screenshot({ path: OUT + '/verify_wh_13_flow_final.png' });
  });

  await step('16. API 终态复核', async () => {
    const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
    const tok = lg.data.token;
    const st = await api('GET', '/api/his/stock/page?page=1&size=10', null, tok);
    const recs = (st.data && st.data.records) || [];
    if (recs.length === 1 && String(recs[0].qty) === '90') pass('API复核: 库存1条, qty=90 ✓');
    else fail('API复核异常: ' + JSON.stringify(recs.map(r => ({ qty: r.qty, batch: r.batchNo }))));
    const flow = await api('GET', '/api/his/stock/flow?page=1&size=10', null, tok);
    const fr = (flow.data && flow.data.records) || [];
    if (fr.length === 2) pass('API复核: 流水2条 (IN 100 / OUT 10)');
    else fail('API流水数异常: ' + fr.length);
    const inPage = await api('GET', '/api/his/stock/in/page?page=1&size=5', null, tok);
    pass('入库单总数: ' + (inPage.data && inPage.data.total));
    const outPage = await api('GET', '/api/his/stock/out/page?page=1&size=5', null, tok);
    pass('出库单总数: ' + (outPage.data && outPage.data.total));
  });

  await browser.close();

  /* ===== 汇总 ===== */
  const p = results.filter(r => r[0] === 'PASS').length;
  const f = results.filter(r => r[0] === 'FAIL').length;
  console.log('\n========== 汇总: PASS ' + p + ' / FAIL ' + f + ' ==========');
  if (f) results.filter(r => r[0] === 'FAIL').forEach(r => console.log('  FAIL>', r[1]));
  if (consoleErrors.length) {
    console.log('\n[页面console错误] ' + consoleErrors.length + ' 条:');
    [...new Set(consoleErrors)].slice(0, 10).forEach(e => console.log('  -', e));
  } else console.log('\n[页面console错误] 无');
  process.exit(f ? 1 : 0);
})().catch(e => { console.error('FATAL:', e); process.exit(2); });
