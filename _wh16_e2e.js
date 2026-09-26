/* Task #16 前端药库视图改造 E2E 验证
 * 覆盖:
 *  1) DrugStock:        药库下拉过滤(库存/预警/流水三Tab联动 warehouseId 参数) + 药库列
 *  2) StockInManage:    药库过滤下拉 + 建单必选目标药库守卫 + 选药联动(中药库仅可选中药)
 *  3) StockOutManage:   药库过滤下拉 + 建单必选来源药库守卫 + 批次选择锁定该库
 *  4) WarehouseDef:     列表(含停用) + 新增校验 + 新增/编辑 + 启停(MessageBox二次确认)
 *  5) DrugCatalogView:  只读分页 + 类型过滤(warehouseType=WESTERN) + 关键字
 *  6) StockCheck:       新建盘点 → 逐行录实盘(PUT保存) → 作废 → 再建 → 确认 → 只读查看
 * 运行: node _wh16_e2e.js  (playwright-core 1.62 + 系统Edge, 依赖后端已启动 8080) */
const PW_CORE = 'C:/Users/Lenovo/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright-core';
const { chromium } = require(PW_CORE);
const http = require('http');

/* 孤立等待器超时不崩溃进程 */
process.on('unhandledRejection', e => { console.log('  [warn] unhandledRejection:', String((e && e.message) || e).split('\n')[0]); });

const BASE = 'http://localhost:8080';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const OUT = 'd:/study/ybtest';

const results = [];
const pass = m => { results.push(['PASS', m]); console.log('  [PASS]', m); };
const fail = (m, e) => { const d = e ? ' :: ' + (e.message || e).toString().split('\n')[0] : ''; results.push(['FAIL', m + d]); console.log('  [FAIL]', m + d); };
const step = async (name, fn) => {
  console.log('\n==== ' + name + ' ====');
  try { await fn(); }
  catch (e) {
    fail(name, e);
    try {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
      await page.evaluate(() => { document.querySelectorAll('.el-overlay').forEach(o => o.remove()); });
      await page.waitForTimeout(200);
    } catch (_) { /* 清理尽力而为 */ }
  }
};

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
  /* ===== 0. API 前置: 登录 + 现状(药库/库存/盘点) + 遗留清理 ===== */
  console.log('==== 0. API 前置检查 ====');
  const lg = await api('POST', '/api/auth/login', { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' });
  const tok = lg.data.token;
  const defsAllResp = await api('GET', '/api/his/stock/warehouse-def?includeDisabled=true', null, tok);
  const defsAll = defsAllResp.data || [];
  const defs = defsAll.filter(d => d.status === 1);
  console.log('  药库定义:', defsAll.map(d => d.id + ':' + d.name + '(' + d.warehouseType + ',' + (d.status === 1 ? '启用' : '停用') + ')').join(' | '));
  if (defs.length >= 1) pass('药库定义存在且含启用库: ' + defs.length + ' 启用 / ' + defsAll.length + ' 总数'); else fail('无启用药库定义');

  const stockAllResp = await api('GET', '/api/his/stock/page?page=1&size=200', null, tok);
  const stockTotal = Number(stockAllResp.data.total || 0);
  const stockRecs = stockAllResp.data.records || [];
  if (stockTotal > 0) pass('库存数据就绪: ' + stockTotal + ' 行(有量批次)'); else fail('库存为空, 盘点用例将失去意义');
  const stockedWhId = stockRecs.length ? stockRecs[0].warehouseId : (defs[0] && defs[0].id);
  const stockedWh = defs.find(d => d.id === stockedWhId) || defs[0];
  const filterWh = defs.find(d => d.id !== stockedWh.id) || stockedWh;
  console.log('  有量库存药库: ' + stockedWh.id + ':' + stockedWh.name + ' | 过滤测试药库: ' + filterWh.id + ':' + filterWh.name);

  /* 前置清理: 目标药库遗留"进行中"盘点单(历史测试数据) → 作废, 保证新建盘点可成功 */
  const ckPre = await api('GET', '/api/his/stock/check/page?warehouseId=' + stockedWh.id + '&page=1&size=100', null, tok);
  const inProg = (ckPre.data.records || []).filter(r => r.status === 0);
  for (const c of inProg) {
    await api('DELETE', '/api/his/stock/check/' + c.id, null, tok);
    console.log('  [前置清理] 作废遗留进行中盘点单: ' + c.checkNo);
  }
  const e2eWhExisting = defsAll.find(d => d.code === 'E2E-WH16');

  const browser = await chromium.launch({ executablePath: EDGE, headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1680, height: 950 } });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', e => pageErrors.push('pageerror: ' + e.message.slice(0, 300)));

  const w = ms => page.waitForTimeout(ms);
  const waitResp = part => page.waitForResponse(r => r.url().includes(part) && r.status() === 200, { timeout: 15000 })
    .then(r => ({ url: () => r.url() }))
    .catch(() => ({ url: () => 'TIMEOUT:' + part }));
  const toast = (type, text) => page.locator('.el-message--' + type, { hasText: text }).last();
  const vDlg = sub => page.locator('.el-dialog:visible', { hasText: sub }).first();
  const pickSelect = async (sel, optionText) => {
    await sel.click();
    const opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: optionText }).first();
    await opt.waitFor({ timeout: 8000 });
    await opt.click();
    await w(400);
  };
  const popConfirm = async () => {
    const btn = page.locator('.el-popconfirm__action .el-button--primary:visible').last();
    await btn.waitFor({ timeout: 8000 });
    await w(250);
    await btn.click();
  };
  const mountView = async name => {
    const r = await page.evaluate(n => {
      try {
        const old = document.getElementById('e2e-host');
        if (old) { old.remove(); }
        const host = document.createElement('div');
        host.id = 'e2e-host';
        host.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:1500;background:#f5f7fa;overflow:auto;';
        document.body.appendChild(host);
        const app = Vue.createApp(window.HIS.views[n]);
        app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
        app.mount(host);
        return true;
      } catch (e) { return String((e && e.message) || e); }
    }, name);
    if (r !== true) throw new Error('挂载失败 ' + name + ': ' + r);
    await page.waitForSelector('#e2e-host .page-title', { timeout: 10000 });
    await w(700);
  };
  async function menu(text) {
    await page.waitForSelector('.el-menu-item', { timeout: 15000 });
    const item = page.locator('.el-menu-item', { hasText: text }).first();
    if (!(await item.count())) throw new Error('菜单项不存在: ' + text);
    if (!(await item.isVisible())) {
      await page.locator('.el-sub-menu__title', { hasText: '药库' }).first().click();
      await w(700);
    }
    await item.click();
    await w(900);
  }

  /* ============ 1. 登录 + 组件注册 ============ */
  await step('1. 登录 + 六组件注册检查', async () => {
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.login-box', { timeout: 15000 });
    await page.locator('button', { hasText: '登 录' }).click();
    await page.waitForSelector('.layout', { timeout: 15000 });
    await page.waitForSelector('.el-menu-item', { timeout: 15000 });
    pass('登录成功(admin/牵头ADMIN)');
    const names = await page.evaluate(() => Object.keys(window.HIS.views).filter(k => ['DrugStock', 'StockInManage', 'StockOutManage', 'WarehouseDef', 'DrugCatalogView', 'StockCheck'].indexOf(k) >= 0));
    if (names.length === 6) pass('HIS.views 六组件注册齐全: ' + names.join(',')); else fail('组件注册缺失: ' + names.join(','));
    await page.screenshot({ path: OUT + '/verify_wh16_01_login.png' });
  });

  /* ============ 2. DrugStock 药库过滤 ============ */
  await step('2. DrugStock: 药库下拉三Tab联动 + 药库列', async () => {
    await menu('库存/流水');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    await w(500);
    const selCount = await page.locator('.page-card > .toolbar .el-select').count();
    if (selCount >= 1) pass('工具栏药库下拉已渲染(库存总览顶部)'); else fail('药库下拉缺失');
    const heads = await page.locator('.el-tab-pane:visible th').allInnerTexts();
    if (heads.join(',').indexOf('药库') >= 0) pass('库存列表存在"药库"列'); else fail('"药库"列缺失: ' + heads.join(','));

    /* 过滤到 filterWh(预期0行或有独立行), 校验请求参数 + 行数与API一致 */
    const respP = waitResp('/api/his/stock/page');
    await pickSelect(page.locator('.page-card > .toolbar .el-select').first(), filterWh.name);
    const req1 = await respP;
    if (req1.url().indexOf('warehouseId=' + filterWh.id) >= 0) pass('库存列表请求携带 warehouseId=' + filterWh.id);
    else fail('库存列表请求未带 warehouseId: ' + req1.url());
    await w(400);
    const apiStock = await api('GET', '/api/his/stock/page?page=1&size=20&warehouseId=' + filterWh.id, null, tok);
    const expRows = Math.min(Number(apiStock.data.total || 0), 20);
    const rows1 = await page.locator('.el-tab-pane:visible .el-table__row').count();
    if (rows1 === expRows) pass('过滤后行数=' + rows1 + ' 与API一致(该库 total=' + apiStock.data.total + ')');
    else fail('过滤行数不一致: UI=' + rows1 + ' API=' + expRows);
    await page.screenshot({ path: OUT + '/verify_wh16_02_drugstock_wh_filter.png' });

    /* 预警Tab联动 */
    const respA = waitResp('/api/his/stock/alert');
    await page.locator('.el-tabs__item', { hasText: '低库存预警' }).click();
    const reqA = await respA;
    if (reqA.url().indexOf('warehouseId=' + filterWh.id) >= 0) pass('低库存预警请求携带 warehouseId');
    else fail('预警请求未带 warehouseId: ' + reqA.url());

    /* 流水Tab联动 */
    const respF = waitResp('/api/his/stock/flow');
    await page.locator('.el-tabs__item', { hasText: '出入库流水' }).click();
    const reqF = await respF;
    if (reqF.url().indexOf('warehouseId=' + filterWh.id) >= 0) pass('出入库流水请求携带 warehouseId');
    else fail('流水请求未带 warehouseId: ' + reqF.url());
    await page.screenshot({ path: OUT + '/verify_wh16_03_drugstock_alert_flow.png' });
  });

  /* ============ 3. StockInManage ============ */
  await step('3. StockInManage: 药库过滤 + 建单触发守卫 + 选药联动', async () => {
    await menu('采购入库');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    await w(500);
    const whSel = page.locator('.toolbar', { hasText: '新建入库单' }).locator('.el-select').first();
    if (await whSel.count()) pass('左侧工具栏药库下拉已渲染'); else fail('药库下拉缺失');
    const heads = await page.locator('.dept-split .el-table').first().locator('th').allInnerTexts();
    if (heads.join(',').indexOf('药库') >= 0) pass('入库单列表存在"药库"列'); else fail('"药库"列缺失');

    /* 新建(未过滤药库→prePick为空): 未选目标药库时添加药品行 → 守卫警告 */
    await page.locator('button', { hasText: '新建入库单' }).click();
    await w(500);
    if (await page.locator('.el-form-item', { hasText: '目标药库' }).count()) pass('表单"目标药库"必选项已渲染'); else fail('"目标药库"项缺失');
    await page.locator('button', { hasText: '添加药品行' }).click();
    await w(600);
    if (await toast('warning', '请先选择目标药库').count()) pass('未选药库时选药被拦截: 请先选择目标药库');
    else fail('缺少未选药库守卫警告');
    await page.screenshot({ path: OUT + '/verify_wh16_04_stockin_guard.png' });

    /* 选择目标药库(西药库) → 选药器联动 warehouseType=WESTERN */
    const formWhSel = page.locator('.el-form-item', { hasText: '目标药库' }).locator('.el-select').first();
    await pickSelect(formWhSel, filterWh.name);
    const respD = waitResp('/api/his/stock/drug-catalog');
    await page.locator('.dept-split .el-table__row').last().locator('button', { hasText: '点击选择药品' }).click();
    const reqD = await respD;
    if (reqD.url().indexOf('warehouseType=WESTERN') >= 0) pass('选药请求携带 warehouseType=WESTERN(按目标药库联动)');
    else fail('选药请求未带 warehouseType: ' + reqD.url());
    await vDlg('选择药品').waitFor({ timeout: 8000 });
    const hint = await vDlg('选择药品').locator('span', { hasText: '西药库' }).first().innerText();
    pass('选药弹窗范围提示: ' + hint.trim());
    /* 选择第一条药品 → 回填明细行 */
    await vDlg('选择药品').locator('.el-table__row').first().locator('button', { hasText: '选择' }).click();
    await w(600);
    const drugBtnTxt = await page.locator('.dept-split .el-table__row').last().locator('button').first().innerText();
    if (drugBtnTxt.indexOf('点击选择药品') < 0) pass('药品已回填明细行: ' + drugBtnTxt.trim().slice(0, 20));
    else fail('药品未回填');
    /* 保存草稿校验: 缺批号被拦(不落库) */
    await page.locator('button', { hasText: '保存草稿' }).click();
    await w(600);
    if (await toast('warning', '第1行请填写批次号').count()) pass('保存校验生效: 第1行请填写批次号');
    else fail('保存校验未触发');
    await page.screenshot({ path: OUT + '/verify_wh16_05_stockin_drugscope.png' });

    /* 药库过滤下拉 + 请求参数 */
    const respI = waitResp('/api/his/stock/in/page');
    await pickSelect(whSel, filterWh.name);
    const reqI = await respI;
    if (reqI.url().indexOf('warehouseId=' + filterWh.id) >= 0) pass('入库单分页请求携带 warehouseId=' + filterWh.id);
    else fail('入库单请求未带 warehouseId: ' + reqI.url());
  });

  /* ============ 4. StockOutManage ============ */
  await step('4. StockOutManage: 药库过滤 + 来源药库守卫 + 批次锁定该库', async () => {
    await menu('出库管理');
    await page.waitForSelector('.page-title', { timeout: 10000 });
    await w(500);
    const whSel = page.locator('.toolbar', { hasText: '新建出库单' }).locator('.el-select').first();
    if (await whSel.count()) pass('左侧工具栏药库下拉已渲染'); else fail('药库下拉缺失');

    /* 新建(未过滤药库→prePick为空): 未选来源药库时添加批次 → 守卫警告 */
    await page.locator('button', { hasText: '新建出库单' }).click();
    await w(500);
    if (await page.locator('.el-form-item', { hasText: '来源药库' }).count()) pass('表单"来源药库"必选项已渲染'); else fail('"来源药库"项缺失');
    await page.locator('button', { hasText: '添加库存批次' }).click();
    await w(600);
    if (await toast('warning', '请先选择来源药库').count()) pass('未选药库时选批次被拦截: 请先选择来源药库');
    else fail('缺少未选药库守卫警告');

    /* 选择来源药库(有量库存库) → 批次选择器携带 warehouseId */
    const formWhSel = page.locator('.el-form-item', { hasText: '来源药库' }).locator('.el-select').first();
    await pickSelect(formWhSel, stockedWh.name);
    const respB = waitResp('/api/his/stock/page');
    await page.locator('.dept-split .el-table__row').last().locator('button', { hasText: '点击选择库存批次' }).click();
    const reqB = await respB;
    if (reqB.url().indexOf('warehouseId=' + stockedWh.id) >= 0) pass('批次选择请求锁定来源药库 warehouseId=' + stockedWh.id);
    else fail('批次请求未带 warehouseId: ' + reqB.url());
    await vDlg('选择库存批次').waitFor({ timeout: 8000 });
    if (await vDlg('选择库存批次').locator('span', { hasText: '仅显示该药库' }).count()) pass('批次弹窗提示: 仅显示该药库未停用且有库存的批次');
    else fail('批次弹窗提示缺失');
    const batchRows = await vDlg('选择库存批次').locator('.el-table__row').count();
    if (batchRows > 0) pass('批次列表非空: ' + batchRows + ' 行');
    else fail('批次列表为空(该库应有库存)');
    await vDlg('选择库存批次').locator('.el-table__row').first().locator('button', { hasText: '选择' }).click();
    await w(600);
    const outDrugTxt = await page.locator('.dept-split .el-table__row').last().locator('button').first().innerText();
    if (outDrugTxt.indexOf('点击选择库存批次') < 0) pass('批次已回填出库明细: ' + outDrugTxt.trim().slice(0, 20));
    else fail('批次未回填');
    await page.locator('button', { hasText: '保存草稿' }).click();
    await w(600);
    if (await toast('warning', '第1行数量必须大于0').count()) pass('出库保存校验生效(数量>0)');
    else fail('出库保存校验未触发');
    await page.screenshot({ path: OUT + '/verify_wh16_06_stockout_batch.png' });

    /* 药库过滤下拉 + 请求参数 */
    const respO = waitResp('/api/his/stock/out/page');
    await pickSelect(whSel, filterWh.name);
    const reqO = await respO;
    if (reqO.url().indexOf('warehouseId=' + filterWh.id) >= 0) pass('出库单分页请求携带 warehouseId=' + filterWh.id);
    else fail('出库单请求未带 warehouseId: ' + reqO.url());
  });

  /* ============ 5. DrugCatalogView ============ */
  const dcvTotalResp = await api('GET', '/api/his/stock/drug-catalog?page=1&size=20', null, tok);
  const dcvTotalAll = Number(dcvTotalResp.data.total || 0);
  await step('5. DrugCatalogView: 只读分页 + 类型过滤 + 关键字', async () => {
    await mountView('DrugCatalogView');
    const title = await page.locator('#e2e-host .page-title').innerText();
    if (title.indexOf('药品目录') >= 0) pass('页面标题: ' + title.split('\n')[0].trim().slice(0, 30));
    else fail('标题异常: ' + title);
    const heads = await page.locator('#e2e-host th').allInnerTexts();
    ['药品编码', '通用名', '剂型', '零售价', '医保等级'].forEach(c => {
      if (heads.join(',').indexOf(c) >= 0) pass('列存在: ' + c); else fail('列缺失: ' + c);
    });
    const rows = await page.locator('#e2e-host .el-table__row').count();
    if (rows > 0) pass('目录数据渲染: ' + rows + ' 行 (全量 total=' + dcvTotalAll + ')');
    else fail('目录无数据');

    const respW = waitResp('/api/his/stock/drug-catalog');
    await pickSelect(page.locator('#e2e-host .toolbar .el-select').first(), '西药');
    const reqW = await respW;
    if (reqW.url().indexOf('warehouseType=WESTERN') >= 0) pass('类型过滤请求携带 warehouseType=WESTERN');
    else fail('类型过滤未带参数: ' + reqW.url());
    await w(500);
    const westTxt = await page.locator('#e2e-host .toolbar span', { hasText: '共' }).last().innerText();
    pass('西药过滤结果: ' + westTxt.trim());
    await page.screenshot({ path: OUT + '/verify_wh16_07_catalog_western.png' });

    const respK = waitResp('/api/his/stock/drug-catalog');
    await page.locator('#e2e-host input[placeholder*="通用名"]').fill('阿');
    await page.locator('#e2e-host button', { hasText: '查询' }).click();
    const reqK = await respK;
    if (reqK.url().indexOf('keyword=') >= 0) pass('关键字查询请求携带 keyword');
    else fail('关键字查询未带参数: ' + reqK.url());
    await w(400);
    await page.screenshot({ path: OUT + '/verify_wh16_08_catalog_keyword.png' });
  });

  /* ============ 6. StockCheck 盘点全流程 ============ */
  let checkNo1 = '', checkNo2 = '';
  await step('6. StockCheck: 新建→录实盘(逐行PUT)→作废', async () => {
    await mountView('StockCheck');
    const title = await page.locator('#e2e-host .page-title').innerText();
    if (title.indexOf('盘点管理') >= 0) pass('页面标题: 盘点管理');
    else fail('标题异常: ' + title);
    if (await page.locator('#e2e-host button', { hasText: '新建盘点' }).count()) pass('新建盘点按钮存在(lead)');
    else fail('新建盘点按钮缺失');

    await page.locator('#e2e-host button', { hasText: '新建盘点' }).click();
    await vDlg('新建盘点').waitFor({ timeout: 8000 });
    await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '创建盘点' }).click();
    await w(500);
    if (await toast('warning', '请选择要盘点的药库').count()) pass('新建盘点未选药库被拦截');
    else fail('新建盘点缺守卫');
    await pickSelect(page.locator('.el-dialog:visible .el-select').first(), stockedWh.name);
    const respC = waitResp('/api/his/stock/check');
    await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '创建盘点' }).click();
    await respC;
    const t1 = await toast('success', '盘点单已创建');
    await t1.waitFor({ timeout: 10000 });
    const t1txt = await t1.innerText();
    checkNo1 = (t1txt.match(/PD\d+/) || [''])[0];
    if (checkNo1) pass('盘点单已创建: ' + checkNo1); else fail('未获取盘点单号: ' + t1txt);

    /* 录入弹窗自动打开, 明细行数 = 该库有量库存数 */
    await vDlg('盘点录入').waitFor({ timeout: 10000 });
    await w(800);
    const stockWhResp = await api('GET', '/api/his/stock/page?page=1&size=200&warehouseId=' + stockedWh.id, null, tok);
    const expItems = (stockWhResp.data.records || []).filter(r => r.status === 1 && Number(r.qty) > 0).length;
    const itemRows = await vDlg('盘点录入').locator('.el-table__row').count();
    if (itemRows === expItems) pass('盘点明细行数=' + itemRows + ' 与该库有量批次一致');
    else fail('明细行数不一致: UI=' + itemRows + ' 期望=' + expItems);

    /* 第一行: 实盘=系统+3 → 失焦自动PUT保存 → 差异+3 */
    const row1 = vDlg('盘点录入').locator('.el-table__row').first();
    const tds = await row1.locator('td').allInnerTexts();
    const sys = Number(String(tds[4]).replace(/[^\d.\-]/g, ''));
    const target = sys + 3;
    const respU = waitResp('/item/');
    await row1.locator('.el-input-number input').click();
    await row1.locator('.el-input-number input').fill(String(target));
    await page.keyboard.press('Tab');
    const reqU = await respU;
    if (reqU.url().indexOf('actualQty=' + target) >= 0) pass('逐行失焦保存PUT成功: actualQty=' + target + ' (系统数量=' + sys + ')');
    else fail('PUT参数异常: ' + reqU.url());
    await vDlg('盘点录入').locator('span', { hasText: '已保存:' }).first().waitFor({ timeout: 8000 });
    const tipTxt = await vDlg('盘点录入').locator('span', { hasText: '已保存:' }).first().innerText();
    pass('保存回执: ' + tipTxt.trim());
    const tds2 = await vDlg('盘点录入').locator('.el-table__row').first().locator('td').allInnerTexts();
    if (String(tds2[6]).indexOf('+3') >= 0) pass('差异列回算正确: ' + String(tds2[6]).trim());
    else fail('差异列异常: "' + tds2[6] + '"');
    await page.screenshot({ path: OUT + '/verify_wh16_09_check_entry.png' });
    await vDlg('盘点录入').locator('.el-dialog__footer button', { hasText: '关闭' }).click();
    await w(600);

    /* 作废(进行中) — popconfirm 二次确认 */
    const rowC1 = page.locator('#e2e-host .el-table__row', { hasText: checkNo1 }).first();
    await rowC1.locator('button', { hasText: '作废' }).click();
    await popConfirm();
    const tv = toast('success', '盘点单已作废');
    await tv.waitFor({ timeout: 10000 });
    pass('作废成功: ' + (await tv.innerText()).trim());
    await w(700);
    const rowC1b = page.locator('#e2e-host .el-table__row', { hasText: checkNo1 }).first();
    const tag1 = await rowC1b.locator('.el-tag').first().innerText();
    if (tag1.indexOf('已作废') >= 0) pass('列表状态=已作废'); else fail('状态异常: ' + tag1);
    await page.screenshot({ path: OUT + '/verify_wh16_10_check_voided.png' });
  });

  await step('7. StockCheck: 再建→确认(零差异不动物流)→只读查看', async () => {
    await page.locator('#e2e-host button', { hasText: '新建盘点' }).click();
    await vDlg('新建盘点').waitFor({ timeout: 8000 });
    await pickSelect(page.locator('.el-dialog:visible .el-select').first(), stockedWh.name);
    const respC2 = waitResp('/api/his/stock/check');
    await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '创建盘点' }).click();
    await respC2;
    const t2 = await toast('success', '盘点单已创建');
    await t2.waitFor({ timeout: 10000 });
    const t2txt = await t2.innerText();
    checkNo2 = (t2txt.match(/PD\d+/) || [''])[0];
    if (checkNo2) pass('第二张盘点单已创建: ' + checkNo2); else fail('未获取盘点单号: ' + t2txt);
    await vDlg('盘点录入').waitFor({ timeout: 10000 });
    await w(600);
    await page.screenshot({ path: OUT + '/verify_wh16_11_check_entry2.png' });
    await vDlg('盘点录入').locator('.el-dialog__footer button', { hasText: '关闭' }).click();
    await w(600);

    /* 确认盘点(未录实盘 → 零差异, 不生成盘盈亏单据, 库存不变) */
    const rowC2 = page.locator('#e2e-host .el-table__row', { hasText: checkNo2 }).first();
    await rowC2.locator('button', { hasText: '确认盘点' }).click();
    await popConfirm();
    const tc = toast('success', '盘点已确认');
    await tc.waitFor({ timeout: 10000 });
    pass('确认成功: ' + (await tc.innerText()).trim());
    await w(700);
    const rowC2b = page.locator('#e2e-host .el-table__row', { hasText: checkNo2 }).first();
    const tag2 = await rowC2b.locator('.el-tag').first().innerText();
    if (tag2.indexOf('已完成') >= 0) pass('列表状态=已完成'); else fail('状态异常: ' + tag2);
    await page.screenshot({ path: OUT + '/verify_wh16_12_check_confirmed.png' });

    /* 已作废单只读查看 */
    const rowV = page.locator('#e2e-host .el-table__row', { hasText: checkNo1 }).first();
    await rowV.locator('button', { hasText: '查看详情' }).click();
    await vDlg('盘点详情').waitFor({ timeout: 10000 });
    await w(600);
    const editableCnt = await vDlg('盘点详情').locator('.el-input-number').count();
    if (editableCnt === 0) pass('只读查看: 无实盘录入控件');
    else fail('只读弹窗出现可编辑控件: ' + editableCnt);
    if (!(await vDlg('盘点详情').locator('.el-dialog__footer button', { hasText: '保存' }).count())) pass('只读查看: 无保存按钮(仅关闭)');
    else fail('只读弹窗出现保存按钮');
    await page.screenshot({ path: OUT + '/verify_wh16_13_check_view.png' });
    await vDlg('盘点详情').locator('.el-dialog__footer button', { hasText: '关闭' }).click();
    await w(400);
  });

  /* ============ 7. WarehouseDef ============ */
  await step('8. WarehouseDef: 列表(含停用) + 新增校验 + 新增/编辑 + 启停', async () => {
    await mountView('WarehouseDef');
    const title = await page.locator('#e2e-host .page-title').innerText();
    if (title.indexOf('药库管理') >= 0) pass('页面标题: 药库管理');
    else fail('标题异常: ' + title);
    const rowCnt = await page.locator('#e2e-host .el-table__row').count();
    if (rowCnt === defsAll.length) pass('列表行数=' + rowCnt + '(含停用, includeDisabled 生效)');
    else fail('列表行数=' + rowCnt + ' 期望=' + defsAll.length);
    const typeTags = await page.locator('#e2e-host .el-table__row .el-tag').allInnerTexts();
    if (typeTags.every(t => ['西药', '中药', '综合'].indexOf(t.trim()) >= 0)) pass('类型标签映射正确: ' + typeTags.map(t => t.trim()).join(','));
    else fail('类型标签异常: ' + typeTags.join(','));

    /* 新增: 空编码校验 → 取消 */
    await page.locator('#e2e-host button', { hasText: '新增药库' }).click();
    await vDlg('新增药库').waitFor({ timeout: 8000 });
    await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '保存' }).click();
    await w(500);
    if (await toast('warning', '请填写药库编码').count()) pass('新增校验: 空编码被拦截');
    else fail('空编码未被拦截');

    if (!e2eWhExisting) {
      await page.locator('.el-dialog:visible .el-form-item', { hasText: '编码' }).locator('input').fill('E2E-WH16');
      await page.locator('.el-dialog:visible .el-form-item', { hasText: '名称' }).locator('input').fill('E2E中药验收库');
      await pickSelect(page.locator('.el-dialog:visible .el-form-item', { hasText: '类型' }).locator('.el-select').first(), '中药');
      await page.locator('.el-dialog:visible .el-form-item', { hasText: '位置' }).locator('input').fill('E2E测试区');
      await page.locator('.el-dialog:visible .el-form-item', { hasText: '负责人' }).locator('input').fill('E2E验收员');
      await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '保存' }).click();
      const tn = toast('success', '药库已新增');
      await tn.waitFor({ timeout: 10000 });
      pass('新增药库成功: ' + (await tn.innerText()).trim());
    } else {
      await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '取消' }).click();
      pass('复用上轮遗留记录 E2E-WH16(跳过新增)');
    }
    await w(700);
    const newRow = page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).first();
    if (await newRow.count()) pass('列表出现 E2E-WH16 行');
    else fail('新增行未出现');
    const newTag = await newRow.locator('.el-tag').first().innerText();
    if (newTag.trim() === '中药') pass('新增行类型标签=中药(TCM映射)');
    else fail('新增行类型标签异常: ' + newTag);
    await page.screenshot({ path: OUT + '/verify_wh16_14_warehouse_new.png' });

    /* 编辑: 改负责人 */
    await newRow.locator('button', { hasText: '编辑' }).click();
    await vDlg('编辑药库').waitFor({ timeout: 8000 });
    await page.locator('.el-dialog:visible .el-form-item', { hasText: '负责人' }).locator('input').fill('E2E验收员2号');
    await page.locator('.el-dialog:visible .el-dialog__footer button', { hasText: '保存' }).click();
    const te = toast('success', '药库已更新');
    await te.waitFor({ timeout: 10000 });
    pass('编辑保存成功: ' + (await te.innerText()).trim());
    await w(700);
    const editedRow = page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).first();
    if ((await editedRow.innerText()).indexOf('E2E验收员2号') >= 0) pass('编辑回显: 负责人=E2E验收员2号');
    else fail('编辑未回显');

    /* 启停: 终态=停用 */
    const isOn = async () => ((await page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).first().locator('.el-switch').getAttribute('class')) || '').indexOf('is-checked') >= 0;
    const doDisable = async () => {
      await page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).first().locator('.el-switch').click();
      await page.locator('.el-message-box', { hasText: '停用药库' }).waitFor({ timeout: 8000 });
      await page.locator('.el-message-box__btns .el-button--primary').click();
      const tt = toast('success', '已停用');
      await tt.waitFor({ timeout: 10000 });
      await w(700);
    };
    const doEnable = async () => {
      await page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).first().locator('.el-switch').click();
      const tt = toast('success', '已启用');
      await tt.waitFor({ timeout: 10000 });
      await w(700);
    };
    if (await isOn()) {
      await doDisable();
      pass('停用成功(MessageBox二次确认 + 后端守卫通过)');
    } else {
      await doEnable();
      pass('启用成功(上轮为停用态)');
      await doDisable();
      pass('停用成功');
    }
    let finalRowTxt = await page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).first().innerText();
    if (finalRowTxt.indexOf('停用') >= 0) pass('终态=停用');
    else fail('终态异常: ' + finalRowTxt.replace(/\s+/g, ' ').slice(0, 80));
    /* includeDisabled: 刷新后停用行仍在 */
    await page.locator('#e2e-host button', { hasText: '刷新' }).click();
    await w(900);
    if (await page.locator('#e2e-host .el-table__row', { hasText: 'E2E-WH16' }).count()) pass('刷新后停用行仍可见(?includeDisabled=true)');
    else fail('停用行丢失(includeDisabled 失效)');
    await page.screenshot({ path: OUT + '/verify_wh16_15_warehouse_disabled.png' });
  });

  /* ============ 8. 终态 API 复核 ============ */
  await step('9. API 终态复核', async () => {
    const st2 = await api('GET', '/api/his/stock/page?page=1&size=200', null, tok);
    if (Number(st2.data.total) === stockTotal) pass('库存总数未变: ' + stockTotal + ' 行(盘点确认零差异未动物流)');
    else fail('库存数变化: ' + stockTotal + ' -> ' + st2.data.total);

    const ck = await api('GET', '/api/his/stock/check/page?page=1&size=100', null, tok);
    const recs = ck.data.records || [];
    const r1 = recs.find(r => r.checkNo === checkNo1);
    const r2 = recs.find(r => r.checkNo === checkNo2);
    if (r1 && r1.status === 2) pass('API复核: ' + checkNo1 + ' status=2(已作废)');
    else fail('API复核失败: ' + checkNo1 + ' -> ' + JSON.stringify(r1 && { status: r1.status }));
    if (r2 && r2.status === 1) pass('API复核: ' + checkNo2 + ' status=1(已完成), 盘盈=' + r2.profitAmount + ' 盘亏=' + r2.lossAmount);
    else fail('API复核失败: ' + checkNo2 + ' -> ' + JSON.stringify(r2 && { status: r2.status }));

    const defs2 = await api('GET', '/api/his/stock/warehouse-def?includeDisabled=true', null, tok);
    const we = (defs2.data || []).find(d => d.code === 'E2E-WH16');
    if (we && we.status === 0) pass('API复核: E2E-WH16 已停用(status=0), 不影响其他用例下拉');
    else fail('API复核: E2E-WH16 状态异常: ' + JSON.stringify(we && { status: we.status }));

    /* 过滤器一致性: 以有量库反查 in/out/stock */
    const inAll = await api('GET', '/api/his/stock/in/page?page=1&size=200', null, tok);
    const inCnt = (inAll.data.records || []).filter(r => r.warehouseId === stockedWh.id).length;
    const inWh = await api('GET', '/api/his/stock/in/page?page=1&size=200&warehouseId=' + stockedWh.id, null, tok);
    if (Number(inWh.data.total) === inCnt) pass('API复核: in/page warehouseId过滤一致(' + inCnt + '单)');
    else fail('in/page过滤不一致: ' + inWh.data.total + ' vs ' + inCnt);
    const outAll = await api('GET', '/api/his/stock/out/page?page=1&size=200', null, tok);
    const outCnt = (outAll.data.records || []).filter(r => r.warehouseId === stockedWh.id).length;
    const outWh = await api('GET', '/api/his/stock/out/page?page=1&size=200&warehouseId=' + stockedWh.id, null, tok);
    if (Number(outWh.data.total) === outCnt) pass('API复核: out/page warehouseId过滤一致(' + outCnt + '单)');
    else fail('out/page过滤不一致: ' + outWh.data.total + ' vs ' + outCnt);
  });

  await browser.close();

  /* ===== 汇总 ===== */
  const p = results.filter(r => r[0] === 'PASS').length;
  const f = results.filter(r => r[0] === 'FAIL').length;
  console.log('\n========== 汇总: PASS ' + p + ' / FAIL ' + f + ' ==========');
  if (f) results.filter(r => r[0] === 'FAIL').forEach(r => console.log('  FAIL>', r[1]));
  if (pageErrors.length) { console.log('\n[页面JS异常] ' + pageErrors.length + ' 条:'); [...new Set(pageErrors)].forEach(e => console.log('  -', e)); }
  else console.log('\n[页面JS异常] 无');
  if (consoleErrors.length) {
    console.log('[页面console错误] ' + consoleErrors.length + ' 条(前10):');
    [...new Set(consoleErrors)].slice(0, 10).forEach(e => console.log('  -', e));
  } else console.log('[页面console错误] 无');
  process.exit((f || pageErrors.length) ? 1 : 0);
})().catch(e => { console.error('FATAL:', e); process.exit(2); });
