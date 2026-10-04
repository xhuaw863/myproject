# -*- coding: utf-8 -*-
"""住院医生站 A档视觉移植验收(只读): 表格去色/页签降噪/概览去重复 断言 + 截图。"""
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
        hits404 = []
        pg.on('response', lambda r: hits404.append(r.url) if r.status == 404 else None)
        pg.goto(BASE, wait_until='domcontentloaded')
        pg.wait_for_selector('input[placeholder="账号"]')
        pg.locator('input[placeholder="医院登录码"]').fill('H42010000000')
        pg.locator('input[placeholder="账号"]').fill('admin')
        pg.locator('input[placeholder="密码"]').fill('admin123')
        pg.locator('button:has-text("登 录")').click(); pg.wait_for_timeout(2500)
        pg.evaluate("HIS.go('inp-doctor-ws')")
        pg.wait_for_selector('.iw-patient', timeout=12000)
        pg.locator('.iw-patient').first.click(); pg.wait_for_timeout(2000)

        # --- 概览页签 ---
        pg.locator('.iw-tabs .el-tabs__item', has_text='概览').first.click(); pg.wait_for_timeout(1800)
        chk('A3 床头大卡已移除', pg.locator('.iw-headcard').count() == 0)
        bar = pg.locator('.iw-ov-bar')
        chk('A3 紧凑信息条存在', bar.count() == 1)
        bh = bar.first.bounding_box()['height'] if bar.count() else 999
        chk('A3 信息条单行收缩', bh <= 60, 'h=%d' % bh)
        empty_line = pg.locator('.iw-diaggroups .iw-empty-line').first.text_content() if pg.locator('.iw-diaggroups .iw-empty-line').count() else ''
        chk('A3 空诊断组合一行', '未录入' in empty_line, empty_line.strip())
        chk('A3 有内容诊断组=1', pg.locator('.iw-dg').count() == 1, 'cnt=%d' % pg.locator('.iw-dg').count())
        pg.screenshot(path=os.path.join(SHOT, 'inpa_overview.png'))

        # --- 页签降噪 ---
        fs = pg.evaluate("getComputedStyle(document.querySelector('.iw-tabs .el-tabs__item')).fontSize")
        chk('A2 页签字号13px', fs == '13px', fs)

        # --- 医嘱页签表格去色 ---
        pg.locator('.iw-tabs .el-tabs__item', has_text='医嘱').first.click(); pg.wait_for_timeout(2000)
        m = pg.evaluate("""(() => {
          const th = document.querySelector('.iw-workbench .el-table th.el-table__cell');
          if (!th) return null;
          const cs = getComputedStyle(th);
          const probe = document.createElement('div');
          document.querySelector('.iw-workbench').appendChild(probe);
          probe.style.color = 'var(--yb-surface-2)';
          const want = getComputedStyle(probe).color; probe.remove();
          return { br: cs.borderRightWidth, bg: cs.backgroundColor, want };
        })()""")
        chk('A1 表头无竖分隔线', m is not None and m['br'] == '0px', str(m and m['br']))
        chk('A1 表头底色=surface-2', m is not None and m['bg'] == m['want'], str(m))
        pg.screenshot(path=os.path.join(SHOT, 'inpa_order.png'))

        ctx.close(); b.close()
    real_errs = [e for e in errs if 'favicon' not in e.lower() and '404' not in e]
    chk('零JS错误', not real_errs, '; '.join(real_errs[:3]))
    print('404资源:', hits404)
    print('\n===== 汇总 =====')
    bad = [n for n, ok in results if not ok]
    print('全部通过' if not bad else '未通过: ' + ', '.join(bad))

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
