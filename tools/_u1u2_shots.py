# -*- coding: utf-8 -*-
"""U1+U2 门诊医生工作站验收截图留档 (Playwright + Edge headless, 端口8080, 纯只读)。

场景: A 工作台全景(双栏) / B 已开立处方摘要展开 / C 患者360抽屉 / D 快捷键帮助。
严禁点击任何写入提交类按钮; 仅导航、只读展开、弹层查看。
"""
import os, traceback
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'
SHOT = r'd:\study\ybtest'
FAILS, PERR = [], []

def chk(name, cond, extra=''):
    print('[%s] %s %s' % ('PASS' if cond else 'FAIL', name, extra))
    if not cond: FAILS.append(name)

def login(page):
    page.goto(BASE, wait_until='domcontentloaded')
    page.wait_for_selector('input[placeholder="账号"]')
    page.locator('input[placeholder="医院登录码"]').fill('H42010000000')
    page.locator('input[placeholder="账号"]').fill('admin')
    page.locator('input[placeholder="密码"]').fill('admin123')
    page.locator('button:has-text("登 录")').click()
    page.wait_for_timeout(2500)

def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        ctx = browser.new_context(viewport={'width': 1680, 'height': 950})
        pg = ctx.new_page(); pg.set_default_timeout(15000)
        pg.on('pageerror', lambda e: PERR.append(str(e)))

        login(pg)
        chk('登录成功', pg.locator('text=工作台').count() >= 1)
        pg.evaluate("HIS.go('doctor-ws')")
        pg.wait_for_selector('.dw-qi-name', timeout=10000)
        # 选中接诊中患者 朱志强
        pg.locator('.dw-qi-name', has_text='朱志强').first.click()
        pg.wait_for_timeout(1800)
        chk('患者横幅=朱志强', pg.locator('.dw-banner-main', has_text='朱志强').count() >= 1)
        chk('本次费用已同步', '41.58' in pg.locator('.dw-banner-region, [class*=banner]').first.inner_text())

        # ---------- A 全景(双栏) ----------
        cols = pg.evaluate("getComputedStyle(document.querySelector('.dw-clinic')).gridTemplateColumns")
        chk('A双栏grid生效', len(cols.split(' ')) == 2, cols)
        pg.screenshot(path=os.path.join(SHOT, 'u1u2_ws_main.png'), full_page=False)

        # ---------- B 已开立处方摘要展开(只读toggle) ----------
        summ = pg.locator('.dw-prescription-panel .dw-done-summary').first
        chk('B摘要默认收起含1张计数', '已开立处方' in summ.inner_text() and '▸' in summ.inner_text(), summ.inner_text().replace('\n', ' ')[:60])
        summ.click(position={'x': 60, 'y': 8})  # 点摘要文字区, 避开任何按钮
        pg.wait_for_timeout(800)
        body = pg.locator('.dw-prescription-panel').first.inner_text()
        chk('B展开后处方明细可见', '阿卡波糖' in body)
        pg.screenshot(path=os.path.join(SHOT, 'u1u2_rx_expanded.png'))
        summ.click(position={'x': 60, 'y': 8})  # 收回, 恢复原状
        pg.wait_for_timeout(400)

        # ---------- C 患者360抽屉 ----------
        pg.locator('.dw-p360-trigger').first.click()
        pg.wait_for_timeout(1800)
        drawer = pg.locator('.el-drawer:visible')
        chk('C360抽屉打开', drawer.count() >= 1)
        dtext = drawer.first.inner_text() if drawer.count() else ''
        chk('C360页签齐(概览/时间线/报告)', ('概览' in dtext) and ('时间线' in dtext) and ('报告' in dtext))
        pg.screenshot(path=os.path.join(SHOT, 'u1u2_p360.png'))
        pg.keyboard.press('Escape'); pg.wait_for_timeout(500)

        # ---------- D 快捷键帮助 ----------
        pg.locator('.dw-help-btn').first.click()
        pg.wait_for_timeout(600)
        rows = pg.locator('.dw-help-pop .dw-help-row').count()
        vis = pg.evaluate("(() => { const p = document.querySelector('.dw-help-pop'); return p && getComputedStyle(p).display !== 'none'; })()")
        chk('D帮助气泡可见且6行', bool(vis) and rows == 6, 'rows=%d' % rows)
        pg.screenshot(path=os.path.join(SHOT, 'u1u2_help.png'))

        ctx.close(); browser.close()

    if PERR:
        print('--- JS异常 ---'); [print('  *', e[:160]) for e in PERR[:8]]
    chk('无未捕获JS错误', len(PERR) == 0)
    if FAILS:
        print('!!!!!! 失败 %d 项:' % len(FAILS)); [print('   -', f) for f in FAILS]
        raise SystemExit(1)
    print('U1+U2 截图留档全部通过 OK')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
