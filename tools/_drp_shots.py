# -*- coding: utf-8 -*-
"""门诊医生站报告页签专业化修复验收(只读): 样式注入/等高/工具条/空态 断言 + 截图。"""
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
        pg.locator('.dw-qi-name', has_text='朱志强').first.click(); pg.wait_for_timeout(2000)
        # 切到"报告"页签
        pg.locator('.dw-tabnav-item', has_text='报告').first.click()
        pg.wait_for_selector('.dw-report-panel', timeout=10000)
        pg.wait_for_timeout(2500)

        chk('样式注入: #dw-report-css 存在', pg.locator('#dw-report-css').count() == 1)
        bg = pg.evaluate("getComputedStyle(document.querySelector('.rp-toolbar')).backgroundColor")
        chk('工具条白底卡片(曾裸奔)', bg.startswith('rgb(255, 255, 255'), bg)
        # 用户核心诉求: 刷新与分段按钮等高
        h = pg.evaluate("""(() => {
          const rb = document.querySelector('.rp-toolbar .el-radio-button__inner').getBoundingClientRect().height;
          const bt = document.querySelector('.rp-toolbar .el-button').getBoundingClientRect().height;
          return [Math.round(rb), Math.round(bt)];
        })()""")
        chk('分段与刷新等高(28px)', h[0] == h[1] and h[0] == 28, str(h))
        meta = pg.locator('.rp-meta').first.inner_text()
        chk('meta 去掉患者ID噪音', '患者ID' not in meta, meta)
        chk('空态为 el-empty', pg.locator('.rp-list .el-empty').count() == 1)
        # 分段切换
        pg.locator('.rp-toolbar .el-radio-button', has_text='检验').first.click(); pg.wait_for_timeout(1500)
        on = pg.evaluate("document.querySelector('.rp-toolbar .el-radio-button.is-active .el-radio-button__inner').innerText.trim()")
        chk('分段切换生效', on == '检验', on)
        pg.locator('.dw-report-panel').screenshot(path=os.path.join(SHOT, 'drp_fixed.png'))
        pg.screenshot(path=os.path.join(SHOT, 'drp_fixed_full.png'))
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
