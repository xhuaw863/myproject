/* 收尾 u5: 把图表 option 里遗留的旧调色板字面量归一到 HIS.theme.* 语义色。
 * '#fff'(甜甜圈描边) 与装饰性分类色(#8e6fff/#00c9a7/#ff7b9c) 保留不动。
 * 只替换字符串字面量形式, 按文件+行区间限定, 不碰 DOM 段。 */
const fs = require('fs');
const R = 'd:/study/ybtest/yb-interface/src/main/resources/static/js/views/';
const APPLY = process.argv.includes('--apply');
// 旧字面量 -> HIS.theme 表达式 (两边都带引号确保只命中字符串)
const MAP = {
  "'#909399'": 'HIS.theme.ink3',
  '"#909399"': 'HIS.theme.ink3',
  "'#ebeef5'": 'HIS.theme.split',
  '"#ebeef5"': 'HIS.theme.split',
  "'#409eff'": 'HIS.theme.link',
  '"#409eff"': 'HIS.theme.link',
  "'#67c23a'": 'HIS.theme.success',
  '"#67c23a"': 'HIS.theme.success',
  "'#e6a23c'": 'HIS.theme.warning',
  '"#e6a23c"': 'HIS.theme.warning',
  "'#9b59b6'": 'HIS.theme.purple',
  '"#9b59b6"': 'HIS.theme.purple',
};
const SCOPE = { 'dashboard.js': 100, 'report-biz.js': 150, 'outpatient.js': 2100 };
let total = 0;
Object.entries(SCOPE).forEach(([f, from]) => {
  const lines = fs.readFileSync(R + f, 'utf8').split('\n');
  const log = [];
  const out = lines.map((l, i) => {
    if (i + 1 < from) return l;
    let after = l;
    Object.entries(MAP).forEach(([k, v]) => { after = after.split(k).join(v); });
    if (after !== l) { total++; log.push('  L' + (i + 1) + '  ' + after.trim().slice(0, 120)); }
    return after;
  });
  if (log.length) { console.log('\n=== ' + f + ' (' + log.length + ' 行) ===\n' + log.join('\n')); if (APPLY) fs.writeFileSync(R + f, out.join('\n'), 'utf8'); }
});
console.log('\n' + (APPLY ? '【已写盘】' : '【预览】') + '语义色归一行数 = ' + total);
