# -*- coding: utf-8 -*-
"""门诊医生站同根因三面板(order/documents/tooth-chart)样式注入修复验收(只读)。"""
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
        pg.evaluate("HIS.go('doctor-ws')")
        pg.wait_for_selector('.dw-qi', timeout=12000)
        pg.wait_for_timeout(1500)
        pg.locator('.dw-qi-name').first.click(); pg.wait_for_timeout(2500)

        # 1) 三个注入 style 标签存在(JS 加载即注入, 与组件挂载无关)
        for sid in ('dw-order-panel-css', 'dw-documents-panel-css', 'dw-tooth-chart-css'):
            chk('注入: #' + sid, pg.locator('#' + sid).count() == 1)
        css_order = pg.evaluate("document.getElementById('dw-order-panel-css').textContent")
        chk('危急值全屏警报规则含 --yb 兜底(曾随死代码从未生效)',
            'dw-critical-value-dialog' in css_order and 'var(--yb-danger-strong)' in css_order)
        css_tooth = pg.evaluate("document.getElementById('dw-tooth-chart-css').textContent")
        chk('牙位图规则完整', 'tc-cell' in css_tooth and 'tc-quad' in css_tooth)

        # 2) 诊疗工作台内医嘱面板: 样式真实生效(computed)
        if pg.locator('.dw-order-panel').count():
            disp = pg.evaluate("getComputedStyle(document.querySelector('.dw-order-toolbar')).display")
            chk('order-panel 生效: toolbar flex(曾裸奔)', disp == 'flex', disp)
            chip = pg.locator('.dw-order-chip').count()
            if chip:
                r = pg.evaluate("(() => { const c = getComputedStyle(document.querySelector('.dw-order-chip')); return [c.borderRadius, c.fontSize]; })()")
                chk('order-panel 生效: 助手 chip 圆角胶囊', r[0] == '12px', str(r))
        else:
            chk('order-panel 在诊疗工作台可见', False, 'DOM 未找到 .dw-order-panel')

        # 3) 处置与历史页签: documents-panel 文书卡网格生效
        pg.locator('.dw-tabnav-item', has_text='处置与历史').first.click(); pg.wait_for_timeout(1500)
        if pg.locator('.dw-documents-panel').count():
            g = pg.evaluate("getComputedStyle(document.querySelector('.dw-doc-grid')).display")
            chk('documents-panel 生效: 文书卡两列网格', g == 'grid', g)
            m = pg.evaluate("(() => { const e = document.querySelector('.dw-doc-mark'); return e ? [getComputedStyle(e).width, getComputedStyle(e).backgroundColor] : null; })()")
            chk('documents-panel 生效: 色块角标 34px', m and m[0] == '34px', str(m))
            pg.screenshot(path=os.path.join(SHOT, 'dwsweep_docs.png'))
        else:
            chk('documents-panel 在处置与历史可见', False, 'DOM 未找到 .dw-documents-panel')
        pg.locator('.dw-tabnav-item', has_text='诊疗工作台').first.click(); pg.wait_for_timeout(1200)
        pg.screenshot(path=os.path.join(SHOT, 'dwsweep_clinic.png'))

        real = [e for e in errs if 'favicon' not in e.lower() and '404' not in e]
        chk('零JS错误', not real, '; '.join(real[:2]))
        ctx.close(); b.close()
    print('\n===== 汇总 =====')
    bad = [n for n, ok in results if not ok]
    print('全部通过' if not bad else '未通过: ' + ', '.join(bad))

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
