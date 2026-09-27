/* ============================================================================
 * UI 美化 · 色值归并脚本 (一次性迁移工具, 不入库)
 * 把全站散装的 97 种十六进制色值收敛为 theme.css 中的 --yb-* 令牌。
 *
 * 用法:
 *   node tools/_ui_harmonize.js            仅预览(改动统计 + 被排除的行)
 *   node tools/_ui_harmonize.js --apply    实际写盘
 *
 * 为什么不能无脑全局替换:
 *  1) ECharts 画布调色板(dashboard.js / report-biz.js 的 PALETTE)渲染在 <canvas> 上,
 *     var() 不参与 canvas 取色, 必须留字面量 → 改用 theme.css 里成对维护的 --yb-cx-*。
 *  2) openPrintDoc() 生成的打印文档 / 收据预览运行在独立 window 或内联字符串里,
 *     那里没有本页 CSS 变量作用域, var() 会解析为无色(退化为黑), 打印件直接坏掉。
 *  3) 血标本采血管颜色(TUBE_COLORS)是检验学语义色, 不属界面主题, 不得随品牌走。
 *  4) SVG stroke/fill 属性: 组件内 SVG 曾有状态色切换异常的前科, 保守不动。
 * ========================================================================== */
const fs = require('fs');
const path = require('path');

const R = 'd:/study/ybtest/yb-interface/src/main/resources/static/';
const APPLY = process.argv.includes('--apply');

/* ---------- 逐行排除规则 ---------- */
const EXCLUDE_LINE = [
  /var PALETTE\s*=/,                 // ECharts 调色板数组
  /TUBE_COLORS/,                     // 采血管语义色
  /setOption|axisLine|splitLine|itemStyle|lineStyle|areaStyle/,  // ECharts option 片段
  /openPrintDoc|window\.print|printWin|@page\s*\{|\.print-doc/,  // 打印文档字符串
  /cw-receipt|receipt|收据|票据|rspec|rfoot|ritems|rtotal/,       // 热敏收据视觉语言
  /stroke\s*=|fill\s*=|stop-color|stroke-dasharray/,             // SVG 呈现属性
  /#282c34|#abb2bf/,                 // JSON 报文深色编辑器主题(独立语义)
];

/* ---------- 映射表: 旧字面量 → 令牌(按语义分流, 注释见行内) ---------- */
const MAP = {
  // 文字: 原 #909399 白底仅 3.08 不达 WCAG AA, 提到 ink-2(5.53)
  '#909399': 'var(--yb-ink-2)',
  '#999999': 'var(--yb-ink-2)',
  '#888888': 'var(--yb-ink-2)',
  '#666666': 'var(--yb-ink-2)',
  '#777777': 'var(--yb-ink-3)',
  '#606266': 'var(--yb-ink-2)',
  '#555555': 'var(--yb-ink-2)',
  '#444444': 'var(--yb-ink-1)',
  '#757575': 'var(--yb-ink-2)',
  '#7a8494': 'var(--yb-ink-3)',
  '#8a94a6': 'var(--yb-ink-3)',
  '#8a9aa6': 'var(--yb-ink-3)',
  // 空值占位 '-' / 禁用提示: 第四级文字色, 不与正文混
  '#c0c4cc': 'var(--yb-ink-4)',
  '#9e9e9e': 'var(--yb-ink-4)',
  '#bbbbbb': 'var(--yb-ink-disabled)',
  '#bdbdbd': 'var(--yb-ink-disabled)',
  '#cccccc': 'var(--yb-ink-4)',
  '#a8abb2': 'var(--yb-icon)',
  // 近黑文字: 原实现有 4 个互相竞争的黑色
  '#303133': 'var(--yb-ink-1)',
  '#212121': 'var(--yb-ink-1)',
  '#333333': 'var(--yb-ink-1)',
  '#333':    'var(--yb-ink-1)',
  '#222222': 'var(--yb-ink-1)',
  '#1f2430': 'var(--yb-ink-1)',
  '#111111': 'var(--yb-ink-1)',
  '#000000': 'var(--yb-ink-1)',
  '#12406e': 'var(--yb-brand-strong)',
  // 线框 / 分隔
  '#dcdfe6': 'var(--yb-border-strong)',
  '#e4e7ed': 'var(--yb-border)',
  '#e0e0e0': 'var(--yb-border)',
  '#e3e3e3': 'var(--yb-border)',
  '#ececec': 'var(--yb-border)',
  '#dddddd': 'var(--yb-border)',
  '#ebeef5': 'var(--yb-border-light)',
  '#f2f3f5': 'var(--yb-divider)',
  '#f4f4f5': 'var(--yb-surface-2)',
  // 浅底 / 斑马纹
  '#f5f7fa': 'var(--yb-surface-2)',
  '#f5f5f5': 'var(--yb-surface-2)',
  '#fafafa': 'var(--yb-surface-2)',
  '#f7f7f7': 'var(--yb-surface-2)',
  '#f7fafc': 'var(--yb-surface-2)',
  '#fafbfc': 'var(--yb-surface-2)',
  '#f0f2f5': 'var(--yb-canvas)',
  // 品牌蓝
  '#1a5fb4': 'var(--yb-brand)',
  '#1a5c9e': 'var(--yb-brand)',
  '#1565c0': 'var(--yb-brand)',
  '#0d47a1': 'var(--yb-brand-strong)',
  '#2d7dd2': 'var(--yb-brand-hover)',
  '#61a5e8': 'var(--yb-brand-border)',
  '#409eff': 'var(--yb-link)',
  '#79bbff': 'var(--yb-brand-border)',
  '#a0cfff': 'var(--yb-brand-border)',
  '#ecf5ff': 'var(--yb-brand-subtle)',
  '#d9ecff': 'var(--yb-brand-subtle)',
  '#e3f2fd': 'var(--yb-brand-subtle)',
  '#f5f9ff': 'var(--yb-brand-subtle)',
  '#f0f9eb': 'var(--yb-success-bg)',
  '#e8f5e9': 'var(--yb-success-bg)',
  // 危险
  '#f56c6c': 'var(--yb-danger)',
  '#c45656': 'var(--yb-danger-strong)',
  '#c62828': 'var(--yb-danger-strong)',
  '#cc0000': 'var(--yb-danger)',
  '#fef0f0': 'var(--yb-danger-bg)',
  '#fde2e2': 'var(--yb-danger-border)',
  '#fbd9d9': 'var(--yb-danger-border)',
  '#ffebee': 'var(--yb-danger-bg)',
  '#fbc4c4': 'var(--yb-danger-border)',
  '#8a1f1f': 'var(--yb-danger-strong)',
  // 成功
  '#67c23a': 'var(--yb-success)',
  '#2e7d32': 'var(--yb-success-strong)',
  // 警告 / 金额
  '#e6a23c': 'var(--yb-warning)',
  '#b88230': 'var(--yb-gold)',
  '#f39c12': 'var(--yb-warning)',
  '#e65100': 'var(--yb-warning-strong)',
  '#fdf6ec': 'var(--yb-warning-bg)',
  '#faecd8': 'var(--yb-warning-border)',
  '#fff3e0': 'var(--yb-warning-bg)',
};

/* ---------- 收集待处理文件 ---------- */
const files = ['css/his.css', 'js/app.js', 'js/api.js', 'js/scope.js'];
['js/views', 'js/views/doctor'].forEach(function (d) {
  fs.readdirSync(R + d).filter(function (f) { return f.endsWith('.js'); })
    .forEach(function (f) { files.push(d + '/' + f); });
});

const pat = new RegExp(Object.keys(MAP).map(function (k) {
  return k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?![0-9a-fA-F])';
}).join('|'), 'gi');

let grand = 0, grandSkip = 0;
const skippedSamples = [];
const changed = [];

files.forEach(function (f) {
  const abs = R + f;
  const src = fs.readFileSync(abs, 'utf8');
  const lines = src.split('\n');
  let n = 0, sk = 0;
  const out = lines.map(function (l) {
    const hits = (l.match(pat) || []).length;
    if (!hits) return l;
    if (EXCLUDE_LINE.some(function (p) { return p.test(l); })) { sk += hits; return l; }
    n += hits;
    return l.replace(pat, function (m) { return MAP[m.toLowerCase()] || MAP[m] || m; });
  });
  grand += n; grandSkip += sk;
  if (sk) out.forEach(function (l, i) {
    if (skippedSamples.length < 14 && (lines[i].match(pat) || []).length &&
        EXCLUDE_LINE.some(function (p) { return p.test(l); })) {
      skippedSamples.push('  ' + f + ':' + (i + 1) + '  ' + l.trim().slice(0, 118));
    }
  });
  if (n) {
    changed.push('  ' + f.padEnd(30) + String(n).padStart(4) + ' 处 (留字面量 ' + sk + ')');
    if (APPLY) fs.writeFileSync(abs, out.join('\n'), 'utf8');
  }
});

console.log((APPLY ? '【已写盘】' : '【预览, 未写盘】') + ' 替换 ' + grand + ' 处, 保留字面量 ' + grandSkip + ' 处, 涉及 ' + changed.length + ' 个文件');
console.log(changed.join('\n'));
console.log('\n=== 被排除(保留字面量)样例 ===');
console.log(skippedSamples.join('\n'));
