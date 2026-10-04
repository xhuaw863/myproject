# -*- coding: utf-8 -*-
"""病区护士站 A档视觉移植验收(只读): 表格去竖线/页签降噪/病人信息头卡去重复 断言 + 截图。"""
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
        pg.evaluate("HIS.go('inp-nurse-ws')")
        pg.wait_for_selector('.inp-tabs .el-tabs__item', timeout=12000)
        pg.wait_for_timeout(2000)
        if pg.locator('.inp-patient').count():
            pg.locator('.inp-patient').first.click(); pg.wait_for_timeout(2000)

        # A2 页签降噪
        fs = pg.evaluate("getComputedStyle(document.querySelector('.inp-tabs > .el-tabs__header .el-tabs__item')).fontSize")
        chk('A2 页签字号13px', fs == '13px', fs)

        # A1 医嘱执行表格去竖线
        pg.locator('.inp-tabs .el-tabs__item', has_text='医嘱执行').first.click(); pg.wait_for_timeout(2500)
        m = pg.evaluate("""(() => {
          const th = document.querySelector('.inp-tabs .el-table--border th.el-table__cell');
          if (!th) return null;
          return getComputedStyle(th).borderRightWidth;
        })()""")
        chk('A1 执行表头无竖分隔线', m == '0px', str(m))
        pg.screenshot(path=os.path.join(SHOT, 'nurse_a_exec.png'))

        # A3 病人信息头卡去重复
        pg.locator('.inp-tabs .el-tabs__item', has_text='病人信息').first.click(); pg.wait_for_timeout(2500)
        chk('A3 床号块已移除', pg.locator('.np360-hd .bed').count() == 0)
        chk('A3 过敏史重复tag已移除', pg.locator('.np360-hd .el-tag').count() == 0)
        hd = pg.locator('.np360-hd')
        h = hd.first.bounding_box()['height'] if hd.count() else 999
        chk('A3 头卡压成细条', hd.count() == 1 and h <= 44, 'h=%d' % h)
        chk('A3 费用入口保留', pg.locator('.np360-hd button:has-text("前往费用管理")').count() == 1)
        pg.screenshot(path=os.path.join(SHOT, 'nurse_a_patient.png'))

        real_errs = [e for e in errs if 'favicon' not in e.lower() and '404' not in e]
        chk('零JS错误', not real_errs, '; '.join(real_errs[:3]))
        ctx.close(); b.close()
    print('\n===== 汇总 =====')
    bad_n = [n for n, ok in results if not ok]
    print('全部通过' if not bad_n else '未通过: ' + ', '.join(bad_n))

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
