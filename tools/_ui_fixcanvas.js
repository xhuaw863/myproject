/* ============================================================================
 * 修复: 把被误写进 ECharts 画布配置的 var(--yb-*) 换成 HIS.theme.* 字面量色源。
 * DOM 内联样式里的 var() 是合法的, 故按"文件 + 行区间"限定, 只动图表代码。
 *   dashboard.js   : 100-398 (整个图表区)
 *   report-biz.js  : 150-末尾 (图表区; 前面是 DOM 统计卡)
 *   outpatient.js  : 2100-末尾 (图表区; 1949 附近的 ds-card color 是 DOM 用法, 保留)
 * 另: 两处 PALETTE 数组(dashboard.js:19 / report-biz.js:34)整体换成 HIS.theme.palette。
 * ========================================================================== */
const fs = require('fs');
const R = 'd:/study/ybtest/yb-interface/src/main/resources/static/';
const APPLY = process.argv.includes('--apply');

const TOKEN = {
  'var(--yb-link)': 'HIS.theme.link',
  'var(--yb-brand)': 'HIS.theme.brand',
  'var(--yb-success)': 'HIS.theme.success',
  'var(--yb-warning)': 'HIS.theme.warning',
  'var(--yb-gold)': 'HIS.theme.gold',
  'var(--yb-danger)': 'HIS.theme.danger',
  'var(--yb-purple)': 'HIS.theme.purple',
  'var(--yb-ink-1)': 'HIS.theme.ink1',
  'var(--yb-ink-2)': 'HIS.theme.ink3',   /* 轴文字归到三级灰, 比正文轻一档 */
  'var(--yb-ink-3)': 'HIS.theme.ink3',
  'var(--yb-ink-4)': 'HIS.theme.ink4',
  'var(--yb-border-light)': 'HIS.theme.split',
  'var(--yb-border)': 'HIS.theme.axis',
};
const SCOPE = { 'js/views/dashboard.js': 100, 'js/views/report-biz.js': 150, 'js/views/outpatient.js': 2100 };
const QUOTED = new RegExp(["'var\\(--yb-[a-z0-9-]+\\)'", '"var\\(--yb-[a-z0-9-]+\\)"'].join('|'), 'g');

let total = 0;
Object.keys(SCOPE).forEach(function (f) {
  const from = SCOPE[f];
  const lines = fs.readFileSync(R + f, 'utf8').split('\n');
  const log = [];
  const out = lines.map(function (l, i) {
    if (i + 1 < from) return l;
    if (!QUOTED.test(l)) { QUOTED.lastIndex = 0; return l; }
    QUOTED.lastIndex = 0;
    const before = l;
    const after = l.replace(QUOTED, function (m) {
      const inner = m.slice(1, -1);
      if (!TOKEN[inner]) { console.log('  !! 未知令牌 ' + inner + ' @ ' + f + ':' + (i + 1)); return m; }
      return TOKEN[inner];
    });
    if (after !== before) { total++; log.push('  L' + (i + 1) + '  ' + after.trim().slice(0, 132)); }
    return after;
  });
  if (log.length) {
    console.log('\n=== ' + f + ' (' + log.length + ' 行) ===');
    console.log(log.join('\n'));
    if (APPLY) fs.writeFileSync(R + f, out.join('\n'), 'utf8');
  }
});

/* PALETTE 数组统一换成共享调色板 */
[['js/views/dashboard.js', /var PALETTE = \[[^\]]*\];/], ['js/views/report-biz.js', /var PALETTE = \[[^\]]*\];/]]
  .forEach(function ([f, re]) {
    let t = fs.readFileSync(R + f, 'utf8');
    const m = t.match(re);
    if (!m) { console.log('\n!! ' + f + ' 未找到 PALETTE'); return; }
    console.log('\n=== ' + f + ' PALETTE ===\n  旧: ' + m[0].slice(0, 120) + '\n  新: var PALETTE = HIS.theme.palette;');
    if (APPLY) fs.writeFileSync(R + f, t.replace(re, 'var PALETTE = HIS.theme.palette;'), 'utf8');
  });

console.log('\n' + (APPLY ? '【已写盘】' : '【预览】') + '画布 var() 修复行数 = ' + total);
