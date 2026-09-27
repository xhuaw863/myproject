/* 诊断: 找出被误写进 ECharts 画布配置里的 var() —— canvas 渲染器不解析 CSS 变量 */
const fs = require('fs');
const R = 'd:/study/ybtest/yb-interface/src/main/resources/static/';
let files = ['js/app.js', 'js/api.js', 'js/scope.js'];
['js/views', 'js/views/doctor'].forEach(d => fs.readdirSync(R + d).filter(f => f.endsWith('.js')).forEach(f => files.push(d + '/' + f)));

// ECharts option 特征: 这些上下文里的颜色最终交给 canvas, var() 无效
const CTX = /axisLabel|nameTextStyle|splitLine|axisLine|axisTick|itemStyle|lineStyle|areaStyle|textStyle|label:\s*\{|color:\s*\[|showLoading|borderColor:\s*'var|PALETTE|title:\s*\{|legend:\s*\{|dataBackground|areaColor|emphasis|pointer|detail:\s*\{|formatter/.test;
const isCtx = l => /axisLabel|nameTextStyle|splitLine|axisLine|axisTick|itemStyle|lineStyle|areaStyle|textStyle|label:|color:\s*\[|showLoading|PALETTE|legend:|title:|dataBackground|areaColor|emphasis|pointer|detail:/.test(l);

let total = 0;
const report = {};
for (const f of files) {
  const lines = fs.readFileSync(R + f, 'utf8').split('\n');
  lines.forEach((l, i) => {
    if (!/var\(--yb-/.test(l)) return;
    if (!isCtx(l)) return;
    const n = (l.match(/var\(--yb-[a-z0-9-]+\)/g) || []).length;
    total += n;
    (report[f] = report[f] || []).push({ ln: i + 1, n: n, code: l.trim().slice(0, 150) });
  });
}
console.log('疑似误入画布配置的 var() 合计 = ' + total);
Object.entries(report).forEach(([f, arr]) => {
  console.log('\n--- ' + f + '  (' + arr.length + ' 行 / ' + arr.reduce((s, a) => s + a.n, 0) + ' 处) ---');
  arr.slice(0, 8).forEach(a => console.log('  L' + a.ln + ' x' + a.n + '  ' + a.code));
  if (arr.length > 8) console.log('  ... 另有 ' + (arr.length - 8) + ' 行');
});
