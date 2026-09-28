# -*- coding: utf-8 -*-
"""诊断10: 验证 fetch 回调是否触发展开回滚 (永不resolve/立即resolve 对照) + 网络时序。"""
from playwright.sync_api import sync_playwright
import time

BASE = 'http://localhost:8080/'


def run_round(p, tag, patch_script=None, second_click=False):
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.set_default_timeout(15000)
    events = []
    page.on('request', lambda r: events.append(('REQ', round(time.time() * 1000), r.method, r.url)))
    page.on('response', lambda r: events.append(('RESP', round(time.time() * 1000), r.status, r.url)))
    page.on('pageerror', lambda e: events.append(('PAGEERR', round(time.time() * 1000), str(e)[:140])))

    def on_console(m):
        if m.type in ('error', 'warning'):
            events.append(('CONSOLE-' + m.type, round(time.time() * 1000), m.text[:140]))

    page.on('console', on_console)

    page.goto(BASE, wait_until='domcontentloaded')
    page.wait_for_selector('input[placeholder="账号"]')
    page.locator('button:has-text("登 录")').click()
    page.wait_for_selector('text=治疗管理', timeout=15000)
    page.get_by_text('治疗管理', exact=True).first.click()
    page.get_by_text('治疗计划', exact=True).first.wait_for()
    page.get_by_text('治疗计划', exact=True).first.click()
    page.wait_for_selector('text=共 4 条', timeout=10000)
    page.wait_for_timeout(600)

    page.evaluate('''() => {
      window.__gets = [];
      const og = HIS.get;
      HIS.get = function (url) {
        window.__gets.push([Math.round(performance.now()), String(url)]);
        return og.call(HIS, url);
      };
    }''')

    if patch_script:
        page.evaluate(patch_script)

    page.evaluate('''() => {
      const outer = document.querySelector('.page-card .el-table');
      const tb = outer.querySelector(':scope > .el-table__inner-wrapper .el-table__body tbody')
             || outer.querySelector('tbody');
      tb.setAttribute('data-outer-tbody', '1');
      window.__rec = [];
      const T = () => Math.round(performance.now());
      const mo = new MutationObserver(function (list) {
        list.forEach(function (m) {
          const t = m.target;
          if (m.type === 'childList' && t.tagName === 'TBODY') {
            window.__rec.push(['tbody:' + (t.getAttribute('data-outer-tbody') ? 'OUTER' : 'inner'),
              T(), '+' + m.addedNodes.length + ' -' + m.removedNodes.length]);
          }
          if (m.type === 'attributes' && m.attributeName === 'class'
              && String(t.className).indexOf('expand-icon') >= 0) {
            window.__rec.push(['icon-class', T(),
              t.getAttribute('data-probe-tag') ? 'again' : (t.setAttribute('data-probe-tag', '1'), 'first')]);
          }
        });
      });
      mo.observe(document.body, {childList: true, subtree: true, attributes: true,
                                 attributeFilter: ['class']});
    }''')

    box = page.locator('.page-card .el-table__expand-icon').first.bounding_box()
    cx = box['x'] + box['width'] / 2
    cy = box['y'] + 2

    def do_click(label):
        page.evaluate('window.__rec = []')
        t0 = round(time.time() * 1000)
        page.mouse.click(cx, cy)
        page.wait_for_timeout(700)
        r = page.evaluate('''() => ({rec: window.__rec.slice(),
          cells: document.querySelectorAll('.page-card .el-table__expanded-cell').length})''')
        print('[%s] %s t0=%d -> expandedCells=%s' % (tag, label, t0, r['cells']))
        for x in r['rec']:
            print('    %6s %-14s %s' % (x[1], x[0], x[2]))
        return t0

    t0 = do_click('click1')
    if second_click:
        do_click('click2')

    print('[%s] HIS.get probe:' % tag)
    for g in page.evaluate('window.__gets'):
        print('    %6s %s' % (g[0], g[1]))
    print('[%s] events (last 5000ms):' % tag)
    for e in events:
        if e[1] > t0 - 5000:
            print('    ' + ' '.join(str(v) for v in e))
    print('-' * 60)
    browser.close()


with sync_playwright() as p:
    run_round(p, 'P1-normal', None, second_click=True)
    run_round(p, 'P2-hang', '''() => {
      const og = HIS.get;
      HIS.get = function (url) {
        if (String(url).indexOf('/api/treatment/plan/') === 0) { return new Promise(function () {}); }
        return og.call(HIS, url);
      };
    }''')
    run_round(p, 'P3-immediate', '''() => {
      const og = HIS.get;
      HIS.get = function (url) {
        if (String(url).indexOf('/api/treatment/plan/') === 0) { return Promise.resolve({ execs: [] }); }
        return og.call(HIS, url);
      };
    }''')
print('diag10 done')
