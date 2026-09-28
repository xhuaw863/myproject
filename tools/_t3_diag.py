# -*- coding: utf-8 -*-
"""诊断9: 二分拦截定位展开处理层 + 区分内外表格的 tbody mutation。"""
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.set_default_timeout(15000)

    page.goto(BASE, wait_until='domcontentloaded')
    page.wait_for_selector('input[placeholder="账号"]')
    page.locator('button:has-text("登 录")').click()
    page.wait_for_selector('text=治疗管理', timeout=15000)
    page.get_by_text('治疗管理', exact=True).first.click()
    page.get_by_text('治疗计划', exact=True).first.wait_for()
    page.get_by_text('治疗计划', exact=True).first.click()
    page.wait_for_selector('text=共 4 条', timeout=10000)
    page.wait_for_timeout(500)

    # 标记外层表格的 tbody/结构, 区分内外层
    page.evaluate('''() => {
      const outer = document.querySelector('.page-card .el-table');
      window.__outerTable = outer;
      outer.setAttribute('data-outer', '1');
      const tb = outer.querySelector(':scope > .el-table__inner-wrapper .el-table__body tbody')
             || outer.querySelector('tbody');
      window.__outerTbody = tb;
      tb.setAttribute('data-outer-tbody', '1');
      window.__rec = [];
      const T = () => Math.round(performance.now());
      window.__T2 = T;
      const mo = new MutationObserver(function (list) {
        list.forEach(function (m) {
          const t = m.target;
          if (m.type === 'childList' && t.tagName === 'TBODY') {
            const outerFlag = t.getAttribute('data-outer-tbody') ? 'OUTER' : 'inner';
            window.__rec.push(['tbody:' + outerFlag, T(),
              '+' + m.addedNodes.length + ' -' + m.removedNodes.length]);
          }
          if (m.type === 'attributes' && m.attributeName === 'class'
              && String(t.className).indexOf('expand-icon') >= 0) {
            window.__rec.push(['icon-class', T(),
              t.getAttribute('data-probe-tag') || (t.setAttribute('data-probe-tag', '1'), 'first')]);
          }
        });
      });
      mo.observe(document.body, {childList: true, subtree: true, attributes: true,
                                 attributeFilter: ['class']});
    }''')

    box = page.locator('.page-table-outer .el-table__expand-icon, .page-card .el-table__expand-icon').first.bounding_box()
    cx = box['x'] + box['width'] / 2
    cy = box['y'] + 2

    def click_and_report(tag):
        page.evaluate('window.__rec = []')
        page.mouse.click(cx, cy)
        page.wait_for_timeout(900)
        r = page.evaluate('''() => ({
          rec: window.__rec,
          cells: document.querySelectorAll('.page-card .el-table__expanded-cell').length
        })''')
        print('[%s] final outer expandedCells=%s' % (tag, r['cells']))
        for x in r['rec']:
            print('    %6d %-16s %s' % (x[1], x[0], x[2]))

    # Round 1: window 捕获拦截(阻止一切后续 click 处理者)
    page.evaluate('''() => {
      window.__blk = function (e) { window.__blockedW = (window.__blockedW || 0) + 1;
                                   e.stopImmediatePropagation(); };
      window.addEventListener('click', window.__blk, true);
      window.__rec = [];
    }''')
    page.mouse.click(cx, cy)
    page.wait_for_timeout(700)
    print('[R1 window-blocked] blocked=%s cells=%s'
          % (page.evaluate('window.__blockedW || 0'),
             page.evaluate("document.querySelectorAll('.page-card .el-table__expanded-cell').length")))
    page.evaluate("window.removeEventListener('click', window.__blk, true)")

    # Round 2: 对照(不拦截)
    click_and_report('R2 control')

    # Round 3: 收起(再点一次)
    click_and_report('R3 toggle-off')
    browser.close()
print('diag9 done')
