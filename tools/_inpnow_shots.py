# -*- coding: utf-8 -*-
"""住院医生站现状留档截图 (只读): 全景 + 医嘱页签。"""
import os, traceback
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'
SHOT = r'd:\study\ybtest'

def main():
    with sync_playwright() as p:
        b = p.chromium.launch(channel='msedge', headless=True)
        ctx = b.new_context(viewport={'width': 1680, 'height': 950})
        pg = ctx.new_page(); pg.set_default_timeout(15000)
        pg.goto(BASE, wait_until='domcontentloaded')
        pg.wait_for_selector('input[placeholder="账号"]')
        pg.locator('input[placeholder="医院登录码"]').fill('H42010000000')
        pg.locator('input[placeholder="账号"]').fill('admin')
        pg.locator('input[placeholder="密码"]').fill('admin123')
        pg.locator('button:has-text("登 录")').click(); pg.wait_for_timeout(2500)
        pg.evaluate("HIS.go('inp-doctor-ws')")
        pg.wait_for_selector('.iw-patient', timeout=12000)
        pg.locator('.iw-patient').first.click(); pg.wait_for_timeout(2200)
        pg.screenshot(path=os.path.join(SHOT, 'inp_now_overview.png'))
        # 医嘱页签
        pg.locator('.iw-tabs .el-tabs__item', has_text='医嘱').first.click(); pg.wait_for_timeout(2500)
        pg.screenshot(path=os.path.join(SHOT, 'inp_now_order.png'))
        ctx.close(); b.close()
    print('住院站现状截图完成')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
