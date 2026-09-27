// 生成全量色值归并映射 + 标注不宜 token 化的位置(画布/打印/业务语义)
const fs = require('fs');
const R = 'd:/study/ybtest/yb-interface/src/main/resources/static/';
let files = ['css/his.css', 'js/app.js', 'js/api.js', 'js/scope.js'];
['js/views', 'js/views/doctor'].forEach(d => fs.readdirSync(R + d).filter(f => f.endsWith('.js')).forEach(f => files.push(d + '/' + f)));

// 需要保留字面量的场景关键字
const KEEP = [
  /PALETTE\s*=/, /TUBE_COLORS/, /echarts/i, /setOption/,
  /@media print|th, td \{|window\.print|printWin|收据|票据/,
  /stroke=|fill=|stop-color/,             // SVG 属性内 var() 可用但风险高
  /#282c34|#abb2bf/,                       // JSON 代码块深色主题, 独立语义
];

const cnt = {};
for (const f of files) {
  const lines = fs.readFileSync(R + f, 'utf8').split('\n');
  lines.forEach((l, i) => {
    for (const m of l.match(/#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b/g) || []) {
      const k = m.length === 4 ? ('#' + m[1] + m[1] + m[2] + m[2] + m[3] + m[3]) : m.toLowerCase();
      (cnt[k] = cnt[k] || { n: 0, keep: 0, samples: [] }).n++;
      if (KEEP.some(p => p.test(l))) cnt[k].keep++;
      if (cnt[k].samples.length < 3 && KEEP.some(p => p.test(l))) cnt[k].samples.push(f + ':' + (i + 1) + ' ' + l.trim().slice(0, 95));
    }
  });
}
const list = Object.entries(cnt).sort((a, b) => b[1].n - a[1].n);
console.log('色值数=' + list.length + '  总引用=' + list.reduce((s, e) => s + e[1].n, 0)
  + '  需保留字面量=' + list.reduce((s, e) => s + e[1].keep, 0));
console.log('\n=== 全量清单 (色值 / 次数 / 其中须留字面量) ===');
list.forEach(([c, v]) => {
  console.log('  ' + c + ' x' + String(v.n).padStart(3) + (v.keep ? '  keep=' + v.keep : ''));
  v.samples.forEach(s => console.log('        ! ' + s));
});
