# -*- coding: utf-8 -*-
"""住院医生站 B档流程件验收(只读): 进度chips/出院预检/医嘱键盘流 断言 + 截图。禁止任何写库操作。"""
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
        pg.locator('.iw-patient').first.click(); pg.wait_for_timeout(2600)

        # --- B1 进度 chips ---
        chips = pg.locator('.iw-pb .iw-prog-chip')
        chk('B1 chips数量=5(4件套+预检)', chips.count() == 5, 'cnt=%d' % chips.count())
        diag_chip = pg.locator('.iw-pb .iw-prog-chip', has_text='诊断').first
        chk('B1 诊断chip已完成态', 'is-done' in (diag_chip.get_attribute('class') or ''))
        order_chip = pg.locator('.iw-pb .iw-prog-chip', has_text='医嘱').first
        chk('B1 医嘱chip未完成态', 'is-done' not in (order_chip.get_attribute('class') or ''))
        pg.screenshot(path=os.path.join(SHOT, 'inpb_chips.png'))
        # chip 点击跳转页签
        order_chip.click(); pg.wait_for_timeout(1200)
        act = pg.evaluate("document.querySelector('.iw-tabs .el-tabs__item.is-active').textContent.trim()")
        chk('B1 chip点击跳转医嘱页签', '医嘱' in act, act)

        # --- B3 医嘱开立弹窗键盘流(只进不提交) ---
        pg.locator('button:has-text("开立医嘱")').first.click(); pg.wait_for_timeout(1500)
        chk('B3 开立医嘱弹窗打开', pg.locator('.el-dialog:visible', has_text='开立医嘱').count() >= 1)
        dosage = pg.locator('.el-dialog input[placeholder="如 0.5"]')
        chk('B3 剂量框存在', dosage.count() == 1)
        dosage.first.click(); dosage.first.type('0.5'); pg.wait_for_timeout(200)
        pg.keyboard.press('Enter'); pg.wait_for_timeout(350)
        ph = pg.evaluate("document.activeElement && document.activeElement.placeholder")
        chk('B3 Enter跳单位框', ph == '单位', 'active=%s' % ph)
        pg.keyboard.press('Enter'); pg.wait_for_timeout(350)
        ph2 = pg.evaluate("document.activeElement && document.activeElement.placeholder")
        chk('B3 再Enter跳用法框', ph2 == '用法', 'active=%s' % ph2)
        pg.screenshot(path=os.path.join(SHOT, 'inpb_kbd.png'))
        pg.locator('.el-dialog:visible button:has-text("取消")').last.click(); pg.wait_for_timeout(800)
        chk('B3 弹窗已取消关闭', pg.locator('.el-dialog:visible', has_text='药品检索').count() == 0)

        # --- B2 出院预检 ---
        pg.locator('.iw-pb .iw-prog-chip--go').click(); pg.wait_for_timeout(1000)
        dlg = pg.locator('.el-dialog:visible', has_text='出院预检')
        chk('B2 预检弹窗打开', dlg.count() >= 1)
        rows = pg.locator('.el-dialog:visible .iw-pc-row')
        chk('B2 预检4行', rows.count() == 4, 'cnt=%d' % rows.count())
        bad = pg.locator('.el-dialog:visible .iw-pc-row .st.bad')
        chk('B2 缺项红叉存在(出院诊断未录)', bad.count() >= 1, 'bad=%d' % bad.count())
        pg.screenshot(path=os.path.join(SHOT, 'inpb_precheck.png'))
        # 缺项"去处理"跳转
        pg.locator('.el-dialog:visible .iw-pc-row', has_text='出院诊断').locator('button:has-text("去处理")').click()
        pg.wait_for_timeout(1200)
        act2 = pg.evaluate("document.querySelector('.iw-tabs .el-tabs__item.is-active').textContent.trim()")
        chk('B2 去处理跳转诊断页签且弹窗关闭', '诊断' in act2 and pg.locator('.el-dialog:visible', has_text='出院预检').count() == 0, act2)

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
