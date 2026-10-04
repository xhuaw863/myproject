# -*- coding: utf-8 -*-
"""病区护士站 PatientBanner 接入患者360抽屉 验收截图 (Playwright + Edge headless, 8080, 纯只读)。"""
import os, traceback
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'
SHOT = r'd:\study\ybtest'
FAILS, PERR = [], []

def chk(name, cond, extra=''):
    print('[%s] %s %s' % ('PASS' if cond else 'FAIL', name, extra))
    if not cond: FAILS.append(name)

def main():
    with sync_playwright() as p:
        b = p.chromium.launch(channel='msedge', headless=True)
        ctx = b.new_context(viewport={'width': 1680, 'height': 950})
        pg = ctx.new_page(); pg.set_default_timeout(15000)
        pg.on('pageerror', lambda e: PERR.append(str(e)))
        pg.goto(BASE, wait_until='domcontentloaded')
        pg.wait_for_selector('input[placeholder="账号"]')
        pg.locator('input[placeholder="医院登录码"]').fill('H42010000000')
        pg.locator('input[placeholder="账号"]').fill('admin')
        pg.locator('input[placeholder="密码"]').fill('admin123')
        pg.locator('button:has-text("登 录")').click(); pg.wait_for_timeout(2500)
        pg.evaluate("HIS.go('inp-nurse-ws')")
        pg.wait_for_timeout(3500)
        # 无患者则从病区下拉选第一个病区
        if pg.locator('.inp-patient').count() == 0:
            pg.locator('.toolbar .el-select, .inp-side .el-select').first.click()
            pg.wait_for_timeout(700)
            pg.locator('.el-select-dropdown__item:visible').first.click()
            pg.wait_for_timeout(2500)
        n = pg.locator('.inp-patient').count()
        chk('患者列表有在区患者', n >= 1, 'n=%d' % n)
        if n == 0:
            pg.screenshot(path=os.path.join(SHOT, 'nurse_p360_debug.png'))
            raise SystemExit('无患者可选, 需人工确认病区数据')
        pg.locator('.inp-patient').first.click(); pg.wait_for_timeout(2000)
        chk('护士站横幅渲染', pg.locator('.inp-banner').count() >= 1)
        pname = pg.locator('.inp-banner .nm').first.inner_text()
        cursor = pg.evaluate("getComputedStyle(document.querySelector('.inp-banner .nm')).cursor")
        chk('姓名可点(cursor=pointer)', cursor == 'pointer', cursor)

        pg.locator('.inp-banner .nm').first.click(); pg.wait_for_timeout(2200)
        drawer = pg.locator('.el-drawer:visible')
        chk('360抽屉打开', drawer.count() >= 1)
        dtext = drawer.first.inner_text() if drawer.count() else ''
        chk('抽屉头部姓名一致', pname in dtext, pname)
        chk('页签齐(概览/时间线/报告)', ('概览' in dtext) and ('时间线' in dtext) and ('报告' in dtext))
        chk('概览统计卡渲染', pg.locator('.p360-stat b').count() >= 3)
        pg.screenshot(path=os.path.join(SHOT, 'nurse_p360.png'))
        pg.keyboard.press('Escape'); pg.wait_for_timeout(500)

        ctx.close(); b.close()

    if PERR:
        print('--- JS异常 ---'); [print('  *', e[:160]) for e in PERR[:8]]
    chk('无未捕获JS错误', len(PERR) == 0)
    if FAILS:
        print('!!!!!! 失败 %d 项:' % len(FAILS)); [print('   -', f) for f in FAILS]
        raise SystemExit(1)
    print('护士站360接入验收全部通过 OK')

if __name__ == '__main__':
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
