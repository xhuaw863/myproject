/* ============================================================================
 * HIS.theme —— 画布(ECharts)专用色源
 *
 * 为什么需要它: ECharts 默认用 canvas 渲染器, 颜色最终交给 zrender 解析字符串,
 * 它不认识 CSS 变量 —— 写 color: 'var(--yb-link)' 不会报错, 只会静默丢色。
 * 而界面其余部分(DOM/CSS)应当用 var(--yb-*) 以便统一调色。
 * 故此处保留一份与 theme.css 中 --yb-cx-* 成对维护的字面量, 全站图表只从这里取色。
 *
 * 加载顺序: 必须在各 views 之前(app.js / echarts vendor 之后)。
 * ========================================================================== */
(function () {
  var HIS = (window.HIS = window.HIS || {});

  HIS.theme = {
    /* 序列色: 与 --yb-cx-* 令牌同值, 改 theme.css 必须同步这里 */
    brand:   '#1a5c9e',
    link:    '#2c78c7',
    success: '#3c862d',
    warning: '#a26b1b',
    danger:  '#c74f4f',
    purple:  '#9b59b6',
    teal:    '#048671',
    gold:    '#9c6d25',

    /* 文字 / 线 (取自 theme.css 中性标尺) */
    ink1:    '#1c2430',
    ink2:    '#3d4a5c',
    ink3:    '#5a6a7e',
    ink4:    '#8994a5',
    axis:    '#c6cfda',   /* 坐标轴线 */
    split:   '#eef1f6',   /* 网格分隔线 */

    /* 定性调色板: 主色 → 语义色 → 扩展色, 前 4 色承担绝大多数图表 */
    palette: ['#2c78c7', '#3c862d', '#a26b1b', '#c74f4f',
              '#048671', '#9b59b6', '#1a5c9e', '#bd8128',
              '#7f8b9c', '#2b74bd'],

    /* 连续渐变(直方图/密度)用同一色相, 不引入第二个色相抢注意力 */
    grad: {
      blue:  { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
               colorStops: [{ offset: 0, color: 'rgba(44,120,199,.28)' }, { offset: 1, color: 'rgba(44,120,199,.02)' }] },
      green: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
               colorStops: [{ offset: 0, color: 'rgba(60,134,45,.26)' }, { offset: 1, color: 'rgba(60,134,45,.02)' }] },
      amber: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
               colorStops: [{ offset: 0, color: 'rgba(162,107,27,.26)' }, { offset: 1, color: 'rgba(162,107,27,.02)' }] }
    },

    /* 通用碎片: 坐标轴/图例/提示框的公共设定, 各图 setOption 时 merge 使用 */
    axisLabel: function (extra) { return Object.assign({ color: this.ink3, fontSize: 11 }, extra || {}); },
    catAxis: function (extra) {
      return Object.assign({
        axisLine: { lineStyle: { color: this.axis } },
        axisTick: { show: false },
        axisLabel: { color: this.ink3, fontSize: 11 }
      }, extra || {});
    },
    valAxis: function (extra) {
      return Object.assign({
        nameTextStyle: { color: this.ink3 },
        axisLabel: { color: this.ink3, fontSize: 11 },
        splitLine: { lineStyle: { color: this.split, type: 'dashed' } }
      }, extra || {});
    },
    legend: function (extra) {
      return Object.assign({
        icon: 'circle', itemWidth: 8, itemHeight: 8, itemGap: 14,
        textStyle: { color: this.ink3, fontSize: 11 }
      }, extra || {});
    },
    tooltip: function (extra) {
      return Object.assign({
        backgroundColor: 'rgba(28,36,48,.92)', borderWidth: 0, padding: [7, 10],
        textStyle: { color: '#fff', fontSize: 12 },
        extraCssText: 'border-radius:6px;box-shadow:0 8px 24px rgba(16,24,40,.24);'
      }, extra || {});
    },
    loading: function () {
      return { text: '加载中...', color: this.link, maskColor: 'rgba(255,255,255,.72)',
               spinnerRadius: 9, lineWidth: 2, textColor: this.ink4 };
    }
  };

  /* 注册全局 ECharts 主题 'yb': 兜底那些 option 里没显式写到的默认色
   * (如 categoryAxis 轴线原本落到 ECharts 内置 #6E7079), 让全站画布只有一种中性标尺。
   * 视图里 echarts.init(dom) 需改为 echarts.init(dom, 'yb') 才生效。 */
  var T = HIS.theme;
  if (window.echarts && window.echarts.registerTheme) {
    window.echarts.registerTheme('yb', {
      color: T.palette.slice(),
      backgroundColor: 'transparent',
      textStyle: { fontFamily: '-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif' },
      title: { textStyle: { color: T.ink1, fontSize: 14, fontWeight: 600 }, subtextStyle: { color: T.ink4 } },
      categoryAxis: {
        axisLine: { lineStyle: { color: T.axis } }, axisTick: { show: false },
        axisLabel: { color: T.ink3 }, splitLine: { show: false }
      },
      valueAxis: {
        axisLine: { show: false }, axisTick: { show: false },
        axisLabel: { color: T.ink3 }, nameTextStyle: { color: T.ink3 },
        splitLine: { lineStyle: { color: T.split, type: 'dashed' } }
      },
      timeAxis: { axisLine: { lineStyle: { color: T.axis } }, axisLabel: { color: T.ink3 } },
      legend: { textStyle: { color: T.ink3 }, inactiveColor: T.ink4 },
      tooltip: { backgroundColor: 'rgba(28,36,48,.92)', borderWidth: 0,
                 textStyle: { color: '#fff' }, axisPointer: { lineStyle: { color: T.axis }, crossStyle: { color: T.axis } } }
    });
  }
})();
