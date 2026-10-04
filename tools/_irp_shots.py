# -*- coding: utf-8 -*-
"""住院医生站报告页签专业化修复验收(只读): 样式注入/EP分段/空态/工具条 断言 + 截图。"""
import os, traceback
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'
SHOT = r'd:\study\ybtest'
results = []

def chk(name, ok, detail=''):
    results.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, detail)

def main():
    errs = []
    with sync_playwright() as p:
        b = p.chromium.launch(channel='msedge', headless=True)
        ctx = b.new_context(viewport={'width': 1680, 'height': 950})
        pg = ctx.new_page(); pg.set_default_timeout(15000)
        pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.on('console', lambda m: errs.append(m.text) if m.type == 'error' else None)
        pg.goto(BASE, wait_until='domcontentloaded')
        pg.wait_for_selector('input[placeholder="账号"]')
        pg.locator('input[placeholder="医院登录码"]').fill('H42010000000')
        pg.locator('input[placeholder="账号"]').fill('admin')
        pg.locator('input[placeholder="密码"]').fill('admin123')
        pg.locator('button:has-text("登 录")').click(); pg.wait_for_timeout(2500)
        pg.evaluate("HIS.go('inp-doctor-ws')")
        pg.wait_for_selector('.iw-patient', timeout=12000)
        pg.wait_for_timeout(2000)
        pg.locator('.iw-patient').first.click(); pg.wait_for_timeout(1500)
        pg.locator('.iw-tabs .el-tabs__item', has_text='报告').first.click()
        pg.wait_for_selector('.irp-panel', timeout=10000)
        pg.wait_for_timeout(2500)

        # 1. 样式注入修复: style#inp-report-css 存在且 irp-bar 有 computed 样式(白底卡片)
        chk('样式注入: #inp-report-css 存在', pg.locator('#inp-report-css').count() == 1)
        bg = pg.evaluate("getComputedStyle(document.querySelector('.irp-bar')).backgroundColor")
        chk('样式生效: 工具条白底(曾裸奔)', bg == 'rgb(255, 255, 255)', bg)
        # 2. EP 分段控件替代原生按钮
        chk('分段为 el-radio-button', pg.locator('.irp-bar .el-radio-button').count() == 4)
        chk('原生 irp-seg 按钮已移除', pg.locator('.irp-seg').count() == 0)
        on = pg.evaluate("!!document.querySelector('.irp-bar .el-radio-button.is-active')")
        chk('默认选中"全部"', on)
        # 3. meta 文案不再暴露患者ID
        meta = pg.locator('.irp-meta').first.inner_text()
        chk('meta 去掉患者ID噪音', '患者ID' not in meta, meta)
        # 4. 空态用 el-empty 居中
        chk('空态为 el-empty', pg.locator('.irp-list .el-empty').count() == 1)
        # 5. 点"检验"分段可过滤(只读, 无数据也应切换选中态)
        pg.locator('.irp-bar .el-radio-button', has_text='检验').first.click(); pg.wait_for_timeout(1500)
        on2 = pg.evaluate("document.querySelector('.irp-bar .el-radio-button.is-active .el-radio-button__inner').innerText.trim()")
        chk('分段切换生效', on2 == '检验', on2)
        pg.screenshot(path=os.path.join(SHOT, 'irp_fixed.png'))
        chk('零JS错误', not [e for e in errs if 'favicon' not in e.lower() and '404' not in e], '; '.join(errs[:2]))
        ctx.close(); b.close()
    print('\n===== 汇总 =====')
    bad = [n for n, ok in results if not ok]
    print('全部通过' if not bad else '未通过: ' + ', '.join(bad))

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
