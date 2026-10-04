# -*- coding: utf-8 -*-
"""U3 视觉批次验收截图与断言 (Playwright + Edge headless, 端口8080, 纯只读)。

断言: 队列两行卡片 / 表头中性化 / banner单行nowrap / 组号标签中性化。
截图: 全景 / 队列特写 / banner特写 / 处方面板特写。
"""
import os, traceback
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'
SHOT = r'd:\study\ybtest'
FAILS, PERR = [], []

def chk(name, cond, extra=''):
    print('[%s] %s %s' % ('PASS' if cond else 'FAIL', name, extra))
    if not cond: FAILS.append(name)

def main():
    with sync_playwright() as p:
        b = p.chromium.launch(channel='msedge', headless=True)
        ctx = b.new_context(viewport={'width': 1680, 'height': 950})
        pg = ctx.new_page(); pg.set_default_timeout(15000)
        pg.on('pageerror', lambda e: PERR.append(str(e)))
        pg.goto(BASE, wait_until='domcontentloaded')
        pg.wait_for_selector('input[placeholder="账号"]')
        pg.locator('input[placeholder="医院登录码"]').fill('H42010000000')
        pg.locator('input[placeholder="账号"]').fill('admin')
        pg.locator('input[placeholder="密码"]').fill('admin123')
        pg.locator('button:has-text("登 录")').click(); pg.wait_for_timeout(2500)
        pg.evaluate("HIS.go('doctor-ws')")
        pg.wait_for_selector('.dw-qi', timeout=10000)
        pg.locator('.dw-qi-name', has_text='朱志强').first.click(); pg.wait_for_timeout(1800)

        # ---- U3-3 队列两行卡片 ----
        r1 = pg.locator('.dw-qi-row1').count(); r2 = pg.locator('.dw-qi-row2').count()
        chk('队列两行结构(row1/row2各>=5)', r1 >= 5 and r2 >= 5, 'r1=%d r2=%d' % (r1, r2))
        muted = pg.locator('.dw-qi .dw-tag--muted').count()
        chk('自费标签走class(无inline style)', muted >= 1 and pg.locator('.dw-qi span[style]').count() == 0, 'muted=%d' % muted)
        qi_h = pg.evaluate("document.querySelector('.dw-qi').getBoundingClientRect().height")
        chk('卡片高度>=40', qi_h >= 40, 'h=%.0f' % qi_h)
        consult_bg = pg.evaluate("""(() => { const el = document.querySelector('.dw-qi.is-consulting');
          const probe = document.createElement('div'); probe.style.background = 'var(--dw-warning-light)';
          document.body.appendChild(probe); const yellow = getComputedStyle(probe).backgroundColor; probe.remove();
          return [getComputedStyle(el).backgroundColor, yellow]; })()""")
        chk('接诊中行去整行黄底', consult_bg[0] != consult_bg[1], str(consult_bg))

        # ---- U3-1 表头中性化(扫描样式规则声明, 避开空表无th场景) ----
        hdr = pg.evaluate("""(() => { let hit = 'not-found'; for (const s of document.styleSheets) { let rs; try { rs = s.cssRules; } catch (e) { continue; }
          for (const r of rs) { if (r.style && r.style.getPropertyValue && r.style.getPropertyValue('--el-table-header-bg-color')) hit = r.style.getPropertyValue('--el-table-header-bg-color'); } }
          return hit; })()""")
        chk('表头变量已改中性card-muted', 'card-muted' in hdr, hdr)
        grp = pg.evaluate("""(() => { const g = document.querySelector('.dw-rx-grp'); if (!g) return 'no-el';
          const s = getComputedStyle(g); return [s.backgroundColor, s.color].join('|'); })()""")
        chk('组号小方块中性描边', grp == 'no-el' or 'rgb(255, 255, 255)' not in grp, grp)

        # ---- U3-4 banner 单行省略 ----
        bn = pg.evaluate("""(() => { const el = document.querySelector('.dw-banner'); const s = getComputedStyle(el);
          return [s.flexWrap, el.getBoundingClientRect().height, el.scrollWidth, el.clientWidth]; })()""")
        chk('banner单行nowrap', bn[0] == 'nowrap' and bn[1] < 50, 'wrap=%s h=%.0f' % (bn[0], bn[1]))
        chk('banner无横向溢出', bn[2] <= bn[3] + 1, 'sw=%d cw=%d' % (bn[2], bn[3]))

        # ---- 截图 ----
        pg.screenshot(path=os.path.join(SHOT, 'u3_ws_main.png'))
        pg.locator('.dw-left').screenshot(path=os.path.join(SHOT, 'u3_queue.png'))
        pg.locator('.dw-banner').screenshot(path=os.path.join(SHOT, 'u3_banner.png'))
        pg.locator('.dw-prescription-panel').screenshot(path=os.path.join(SHOT, 'u3_rx_panel.png'))

        # ---- 窄视口省略验证 (1280 仍单行, 中段收缩) ----
        pg.set_viewport_size({'width': 1280, 'height': 900}); pg.wait_for_timeout(800)
        bn2 = pg.evaluate("""(() => { const el = document.querySelector('.dw-banner');
          return [el.getBoundingClientRect().height, el.scrollWidth, el.clientWidth]; })()""")
        chk('1280宽banner仍单行', bn2[0] < 50 and bn2[1] <= bn2[2] + 1, 'h=%.0f sw=%d cw=%d' % (bn2[0], bn2[1], bn2[2]))
        pg.locator('.dw-banner').screenshot(path=os.path.join(SHOT, 'u3_banner_1280.png'))
        pg.set_viewport_size({'width': 1680, 'height': 950}); pg.wait_for_timeout(500)

        ctx.close(); b.close()

    if PERR:
        print('--- JS异常 ---'); [print('  *', e[:160]) for e in PERR[:8]]
    chk('无未捕获JS错误', len(PERR) == 0)
    if FAILS:
        print('!!!!!! 失败 %d 项:' % len(FAILS)); [print('   -', f) for f in FAILS]
        raise SystemExit(1)
    print('U3 验收断言与截图全部通过 OK')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc(); raise SystemExit(2)
