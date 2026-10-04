# -*- coding: utf-8 -*-
"""病区护士站现状留档截图(只读): 入出转 + 医嘱执行 + 病人信息页签。"""
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
        pg.evaluate("HIS.go('inp-nurse-ws')")
        pg.wait_for_selector('.inp-tabs .el-tabs__item', timeout=12000)
        pg.wait_for_timeout(2500)
        pg.screenshot(path=os.path.join(SHOT, 'nurse_now_flow.png'))
        # 选一个患者让横幅与页签数据出来
        if pg.locator('.inp-patient').count():
            pg.locator('.inp-patient').first.click(); pg.wait_for_timeout(2000)
        # 医嘱执行页签
        pg.locator('.inp-tabs .el-tabs__item', has_text='医嘱执行').first.click(); pg.wait_for_timeout(2500)
        pg.screenshot(path=os.path.join(SHOT, 'nurse_now_exec.png'))
        # 医嘱审核页签
        pg.locator('.inp-tabs .el-tabs__item', has_text='医嘱审核').first.click(); pg.wait_for_timeout(2500)
        pg.screenshot(path=os.path.join(SHOT, 'nurse_now_audit.png'))
        # 病人信息页签
        pg.locator('.inp-tabs .el-tabs__item', has_text='病人信息').first.click(); pg.wait_for_timeout(2500)
        pg.screenshot(path=os.path.join(SHOT, 'nurse_now_patient.png'))
        ctx.close(); b.close()
    print('护士站现状截图完成')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
