# -*- coding: utf-8 -*-
"""补验 rxType 语义徽标分支: 切急诊处方 → 断言 header「急」徽标 + 左色条 class (纯前端草稿态, 不落库)。"""
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
        pg.evaluate("HIS.go('doctor-ws')")
        pg.wait_for_selector('.dw-qi-name', timeout=10000)
        pg.locator('.dw-qi-name', has_text='朱志强').first.click(); pg.wait_for_timeout(1500)

        # 处方类型下拉 → 急诊处方
        pg.locator('.dw-rx-hd-left .el-select').first.click()
        pg.wait_for_timeout(600)
        pg.locator('.el-select-dropdown__item:visible', has_text='急诊处方').first.click()
        pg.wait_for_timeout(600)
        flag = pg.locator('.dw-rx-type-flag').first.inner_text() if pg.locator('.dw-rx-type-flag').count() else '(无徽标)'
        print('[%s] 急诊徽标=急 -> %s' % ('PASS' if flag == '急' else 'FAIL', flag))
        panel_cls = pg.evaluate("document.querySelector('.dw-prescription-panel').className")
        print('   面板class:', panel_cls)
        pg.locator('.dw-prescription-panel').first.screenshot(path=os.path.join(SHOT, 'u1u2_rx_flag.png'))

        # 再切回普通处方: 断言无徽标(NORMAL 设计为不显示)
        pg.locator('.dw-rx-hd-left .el-select').first.click(); pg.wait_for_timeout(500)
        pg.locator('.el-select-dropdown__item:visible', has_text='普通处方').first.click(); pg.wait_for_timeout(500)
        n = pg.locator('.dw-rx-type-flag').count()
        print('[%s] 普通处方无徽标 count=%d' % ('PASS' if n == 0 else 'FAIL', n))
        ctx.close(); b.close()
    print('rxType 徽标分支补验完成')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
