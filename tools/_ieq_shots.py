# -*- coding: utf-8 -*-
"""住院医生站病历查询页签专业化修复验收(只读): 样式注入/去主键噪音/去斑马纹/等宽字段键 断言 + 截图。"""
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
        pg.locator('.iw-tabs .el-tabs__item', has_text='病历查询').first.click()
        pg.wait_for_selector('.ieq-panel', timeout=10000)
        pg.wait_for_timeout(2500)

        chk('样式注入: #inp-emrquery-css 存在', pg.locator('#inp-emrquery-css').count() == 1)
        bg = pg.evaluate("getComputedStyle(document.querySelector('.ieq-sec')).backgroundColor")
        chk('样式生效: 分区白底卡(曾裸奔)', bg.startswith('rgb(255, 255, 255'), bg)
        panel = pg.locator('.ieq-panel').first.inner_text()
        chk('visitId 主键噪音已移除', 'visitId' not in panel)
        chk('统计行改人话(去旧ID串)', '结构化要素' in panel and '· 科室' not in panel)
        chk('表格已去斑马纹', pg.locator('.ieq-panel .el-table__row--striped').count() == 0)
        vert = pg.evaluate("(() => { const c = document.querySelector('.ieq-panel .el-table .cell'); return c ? getComputedStyle(c.closest('td') || c).borderRightWidth : 'na'; })()")
        chk('表格无竖分隔线(去border)', vert in ('0px', 'na'), str(vert))
        mono = pg.evaluate("(() => { const k = document.querySelector('.ieq-key'); return k ? getComputedStyle(k).fontFamily : ''; })()")
        chk('字段键等宽浅化呈现', 'Consolas' in mono or 'monospace' in mono, mono[:30])
        # 展开/收起跨患者检索(纯前端toggle)
        pg.locator('.ieq-toggle').first.click(); pg.wait_for_timeout(800)
        chk('展开后检索表单出现', pg.locator('.ieq-form').count() >= 1)
        pg.locator('.ieq-toggle').first.click(); pg.wait_for_timeout(800)
        chk('收起后表单隐藏且引导空态恢复', pg.locator('.ieq-form').count() == 0 and pg.locator('.ieq-panel .el-empty').count() >= 1)
        pg.screenshot(path=os.path.join(SHOT, 'ieq_fixed.png'))
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
