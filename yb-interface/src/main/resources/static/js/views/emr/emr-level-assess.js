/* 病历等级自评(P7b-3): HIS.views.EmrLevelAssess —— 顶部等级徽章 + 中部八维度SVG雷达图 + 下部维度明细卡片。
 * 后端契约(P7b-2 EmrLevelAssessController, /api/emr/level-assess):
 *   GET /assess  快速评估 → {totalScore, maxScore, level, levelText, assessTime}
 *   GET /detail  详细评估 → 8维度[{name, score, maxScore, metric, detail, suggestion}]
 * 兼容兜底: ①API前缀双探(/api/emr/level-assess → /api/his/emr/level-assess, 端点缺失自动切换缓存);
 *   ②detail 载荷数组直取/对象多键名(dimensions/dims/items/list)归一, 维度字段多键名兼容;
 *   ③汇总三级回退: /assess 结果 → /detail 对象顶层字段 → 维度分值累计;
 *   ④端点未上线(404/405/非JSON)静默空态占位, 不弹错误风暴。
 * 等级色: 5绿/4蓝/3黄/2橙/1红/0灰; 雷达图纯SVG实现(不依赖 ECharts):
 *   外圈满分轮廓(浅灰) + 内圈实际得分(半透明蓝) + 同心网格 + 角标签(维度名+得分)。
 * 注册: HIS.views.EmrLevelAssess(须在 app.js 之前加载)。 */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ================= 常量/工具 ================= */
  var API_CANDIDATES = ['/api/emr/level-assess', '/api/his/emr/level-assess'];
  var DEFAULT_MAX = 80;   /* 8维度 × 10分 */

  function pad2(n) { return ('0' + n).slice(-2); }
  function parseT(v) {
    if (!v) { return null; }
    var d = new Date(String(v).replace('T', ' ').replace(/-/g, '/'));
    return isNaN(d.getTime()) ? null : d;
  }
  function fmtDT(v) {
    if (v == null || v === '') { return '—'; }
    var d = parseT(v);
    if (!d) { return String(v).replace('T', ' ').substring(0, 16); }
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  /* 多候选取第一个有效数值 */
  function pickNum() {
    for (var i = 0; i < arguments.length; i++) {
      var v = arguments[i];
      if (v != null && v !== '' && !isNaN(Number(v))) { return Number(v); }
    }
    return null;
  }
  /* 多候选取第一个非空字符串 */
  function pickStr() {
    for (var i = 0; i < arguments.length; i++) {
      var v = arguments[i];
      if (v != null && String(v) !== '') { return String(v); }
    }
    return null;
  }
  function pickArr(o, keys) {
    if (!o) { return null; }
    for (var i = 0; i < keys.length; i++) { if (Array.isArray(o[keys[i]])) { return o[keys[i]]; } }
    return null;
  }
  /* 端点缺失判定: HTTP 404/405、响应非JSON/网络层失败(供前缀双探与空态降级) */
  function isEndpointMissing(e) {
    var m = String((e && e.message) || e || '');
    return /(^|\D)(404|405)(\D|$)/.test(m) || /响应解析失败/.test(m) || /Failed to fetch/i.test(m) || /NetworkError/i.test(m);
  }

  /* ================= API 前缀双探(首个 GET 端点缺失时切备用前缀并缓存) ================= */
  var apiIdx = 0;
  function apiPath(p) { return API_CANDIDATES[apiIdx] + p; }
  function getA(path) {
    return HIS.get(apiPath(path)).catch(function (e) {
      if (apiIdx === 0 && isEndpointMissing(e)) {
        apiIdx = 1;
        return HIS.get(apiPath(path));
      }
      throw e;
    });
  }

  /* 维度行多键名归一 */
  function normDim(x, i) {
    var score = pickNum(x.score, x.value, x.points);
    var maxScore = pickNum(x.maxScore, x.max, x.fullScore, x.total);
    return {
      name: pickStr(x.name, x.dimensionName, x.dimName, x.title) || ('维度' + (i + 1)),
      score: score == null ? 0 : score,
      maxScore: maxScore == null ? 10 : maxScore,
      metric: pickStr(x.metric, x.metricDesc, x.metricText),
      detail: pickStr(x.detail, x.detailText, x.desc, x.description),
      suggestion: pickStr(x.suggestion, x.suggestionText, x.advice, x.improve)
    };
  }

  /* SVG 文本转义(维度名等后端字段注入前防注入) */
  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* 八维度雷达图(纯SVG): n 维度均布圆周(角度 = i*2π/n - π/2),
   * 每维度半径 = (score/maxScore)*r; 满分轮廓浅灰 + 实际得分半透明蓝 + 角标签。 */
  function radarSvg(dims) {
    var n = dims.length;
    if (!n) { return ''; }
    var cx = 220, cy = 188, r = 104;
    function pt(i, ratio) {
      var a = (i * Math.PI * 2 / n) - Math.PI / 2;
      return [cx + r * ratio * Math.cos(a), cy + r * ratio * Math.sin(a)];
    }
    function poly(ratio) {
      var ps = [];
      for (var i = 0; i < n; i++) { var p = pt(i, ratio); ps.push(p[0].toFixed(1) + ',' + p[1].toFixed(1)); }
      return ps.join(' ');
    }
    var s = [];
    s.push('<svg viewBox="0 0 440 384" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="病历等级自评八维度雷达图">');
    /* 满分轮廓(浅灰) + 同心网格(25/50/75%) */
    s.push('<polygon points="' + poly(1) + '" fill="#f7f9fc" stroke="#d8dfe8" stroke-width="1.5"/>');
    [0.25, 0.5, 0.75].forEach(function (k) {
      s.push('<polygon points="' + poly(k) + '" fill="none" stroke="#e3e8ef" stroke-width="1"/>');
    });
    /* 轴线(中心到各角) */
    for (var i = 0; i < n; i++) {
      var p = pt(i, 1);
      s.push('<line x1="' + cx + '" y1="' + cy + '" x2="' + p[0].toFixed(1) + '" y2="' + p[1].toFixed(1) + '" stroke="#eef2f7" stroke-width="1"/>');
    }
    /* 实际得分多边形(半透明蓝) + 顶点圆点 */
    var sp = [], dots = [];
    for (var j = 0; j < n; j++) {
      var d = dims[j];
      var ratio = d.maxScore > 0 ? Math.max(0, Math.min(1, d.score / d.maxScore)) : 0;
      var q = pt(j, ratio);
      sp.push(q[0].toFixed(1) + ',' + q[1].toFixed(1));
      dots.push(q);
    }
    s.push('<polygon points="' + sp.join(' ') + '" fill="rgba(59,130,246,.20)" stroke="#3b82f6" stroke-width="2" stroke-linejoin="round"/>');
    dots.forEach(function (q) {
      s.push('<circle cx="' + q[0].toFixed(1) + '" cy="' + q[1].toFixed(1) + '" r="3" fill="#3b82f6" stroke="#fff" stroke-width="1"/>');
    });
    /* 角标签: 维度名(粗) + 得分/满分; 锚点按方位 start/middle/end */
    for (var t = 0; t < n; t++) {
      var dd = dims[t], a = (t * Math.PI * 2 / n) - Math.PI / 2;
      var cos = Math.cos(a), sin = Math.sin(a);
      var lx = cx + (r + 14) * cos, ly = cy + (r + 14) * sin;
      var anchor = cos > 0.35 ? 'start' : (cos < -0.35 ? 'end' : 'middle');
      var nameY = ly + (sin < -0.35 ? -4 : (sin > 0.35 ? 14 : 4));
      s.push('<text x="' + lx.toFixed(1) + '" y="' + nameY.toFixed(1) + '" text-anchor="' + anchor + '" font-size="12" font-weight="600" fill="#3d4a5c">' + esc(dd.name) + '</text>');
      s.push('<text x="' + lx.toFixed(1) + '" y="' + (nameY + 14).toFixed(1) + '" text-anchor="' + anchor + '" font-size="11" fill="#5a6a7e">' + dd.score + '/' + dd.maxScore + '</text>');
    }
    s.push('</svg>');
    return s.join('');
  }

  /* ================= 私有样式(ela- 前缀一次性注入; 高度链复用 him.css .cd-fill 规范) ================= */
  function ensureLevelAssessStyles() {
    if (document.getElementById('ela-styles')) { return; }
    var css = [
      /* 顶部等级展示区: 在 .cd-fill flex 列中固定高度不参与拉伸 */
      '.cd-fill > .ela-hero { flex: none; }',
      '.ela-hero { display: flex; align-items: center; gap: 26px; flex-wrap: wrap; background: var(--yb-surface, #fff); border: 1px solid var(--yb-border, #e3e8ef); border-radius: 12px; padding: 16px 22px; margin-bottom: 12px; }',
      '.ela-badge { flex: none; width: 118px; height: 118px; border-radius: 16px; display: flex; flex-direction: column; align-items: center; justify-content: center; border: 1.5px solid transparent; }',
      '.ela-badge-lv { font-size: 34px; font-weight: 800; line-height: 1.15; letter-spacing: 2px; }',
      '.ela-badge-cap { font-size: 12px; margin-top: 4px; opacity: .75; }',
      /* 等级色: 5绿/4蓝/3黄/2橙/1红/0灰 */
      '.ela-badge.lv5 { background: #e9f5e4; color: var(--yb-success, #3c862d); border-color: #c5e2b8; }',
      '.ela-badge.lv4 { background: #e7f0fd; color: #2563eb; border-color: #bcd7fb; }',
      '.ela-badge.lv3 { background: #fdf6ec; color: var(--yb-warning-strong, #a26b1b); border-color: #f2dcae; }',
      '.ela-badge.lv2 { background: #fdf0e6; color: #c26a1c; border-color: #f4d5b8; }',
      '.ela-badge.lv1 { background: #fdf0ef; color: var(--yb-danger, #c74f4f); border-color: #f0c5c1; }',
      '.ela-badge.lv0 { background: #f0f2f5; color: var(--yb-ink-4, #8994a5); border-color: #dfe4ea; }',
      '.ela-meta { flex: 1; min-width: 220px; }',
      '.ela-meta-score { font-size: 14px; color: var(--yb-ink-2, #3d4a5c); }',
      '.ela-meta-score b { font-size: 30px; font-weight: 700; color: var(--yb-ink-1, #1f2d3d); margin: 0 2px; font-variant-numeric: tabular-nums; }',
      '.ela-meta-time { font-size: 12px; color: var(--yb-ink-3, #5a6a7e); margin-top: 8px; }',
      /* 中部滚动区 + 雷达卡 */
      '.ela-scroll { flex: 1; min-height: 0; overflow: auto; padding: 2px 2px 24px; }',
      '.ela-radar-card { background: var(--yb-surface, #fff); border: 1px solid var(--yb-border, #e3e8ef); border-radius: 12px; padding: 14px 16px 8px; margin-bottom: 12px; }',
      '.ela-radar-t { font-size: 13px; font-weight: 600; color: var(--yb-ink-1, #1f2d3d); margin-bottom: 2px; }',
      '.ela-radar-box { max-width: 500px; margin: 0 auto; }',
      '.ela-radar-box svg { width: 100%; height: auto; display: block; }',
      '.ela-legend { display: flex; justify-content: center; gap: 18px; font-size: 11px; color: var(--yb-ink-4, #8994a5); padding: 2px 0 4px; }',
      '.ela-legend .sw { display: inline-block; width: 12px; height: 12px; border-radius: 3px; margin-right: 5px; vertical-align: -2px; }',
      /* 维度明细卡(宽屏 4列×2行, 窄屏 2列×4行) */
      '.ela-dims { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }',
      '@media (max-width: 1500px) { .ela-dims { grid-template-columns: repeat(2, 1fr); } }',
      '.ela-dim { background: var(--yb-surface, #fff); border: 1px solid var(--yb-border, #e3e8ef); border-radius: 10px; padding: 12px 14px; }',
      '.ela-dim-hd { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }',
      '.ela-dim-name { font-size: 13px; font-weight: 600; color: var(--yb-ink-1, #1f2d3d); }',
      '.ela-dim-score { font-size: 13px; font-weight: 700; color: var(--yb-ink-2, #3d4a5c); font-variant-numeric: tabular-nums; }',
      '.ela-dim-metric { font-size: 11px; color: var(--yb-ink-4, #8994a5); margin-top: 6px; line-height: 1.6; }',
      '.ela-dim-detail { font-size: 12px; color: var(--yb-ink-2, #3d4a5c); margin-top: 6px; line-height: 1.7; }',
      '.ela-dim-sug { margin-top: 8px; background: #fdf6ec; border-left: 3px solid var(--yb-warning, #e6a23c); border-radius: 0 6px 6px 0; padding: 6px 9px; font-size: 12px; color: #8a6d3b; line-height: 1.7; }'
    ].join('\n');
    var el = document.createElement('style');
    el.id = 'ela-styles';
    el.textContent = css;
    document.head.appendChild(el);
  }
  ensureLevelAssessStyles();

  /* ================= 组件 ================= */
  HIS.views.EmrLevelAssess = {
    name: 'EmrLevelAssess',
    data: function () {
      return {
        loading: false,
        assessMissing: false,   /* /assess 端点未上线标记(空态提示) */
        detailMissing: false,   /* /detail 端点未上线标记 */
        summary: {},            /* /assess 汇总(一级来源) */
        detailSum: {},          /* /detail 对象顶层汇总字段(二级回退) */
        dims: []                /* 归一后的维度明细 */
      };
    },
    computed: {
      /* 汇总视图: /assess → /detail 对象顶层 → 维度分值累计 三级回退 */
      sv: function () {
        var a = this.summary || {};
        var b = this.detailSum || {};
        var total = pickNum(a.totalScore, a.total, b.totalScore, b.total);
        var max = pickNum(a.maxScore, a.max, b.maxScore, b.max);
        var i, sum = 0;
        if (total == null && this.dims.length) {
          for (i = 0; i < this.dims.length; i++) { sum += Number(this.dims[i].score) || 0; }
          total = sum;
        }
        if (max == null) {
          if (this.dims.length) {
            sum = 0;
            for (i = 0; i < this.dims.length; i++) { sum += Number(this.dims[i].maxScore) || 0; }
            max = sum;
          } else { max = DEFAULT_MAX; }
        }
        var level = pickNum(a.level, b.level);
        var levelText = pickStr(a.levelText, a.levelName, b.levelText, b.levelName) || (level != null ? (level + '级') : null);
        var time = pickStr(a.assessTime, a.assessAt, b.assessTime, b.assessAt);
        return {
          total: total,
          max: max,
          levelText: levelText || '—',
          time: time ? fmtDT(time) : '—',
          tone: level == null ? 'lv0' : ('lv' + Math.max(0, Math.min(5, Math.round(level))))
        };
      },
      /* 雷达图 SVG 字符串(v-html 注入, 内容本地生成已转义) */
      radarSvg: function () { return radarSvg(this.dims); }
    },
    created: function () { this.loadAll(); },
    methods: {
      /* 重新评估: 重新拉取 /assess + /detail */
      reassess: function () { this.loadAll(); },
      loadAll: function () {
        var vm = this;
        vm.loading = true;
        return Promise.all([vm.fetchAssess(), vm.fetchDetail()])
          .finally(function () { vm.loading = false; });
      },
      fetchAssess: function () {
        var vm = this;
        return getA('/assess').then(function (d) {
          vm.assessMissing = false;
          if (d && typeof d === 'object' && !Array.isArray(d)) { vm.summary = d; }
        }).catch(function (e) {
          vm.summary = {};
          if (isEndpointMissing(e)) { vm.assessMissing = true; }  /* 端点未上线静默降级 */
          else { HIS.notifyError(e); }
        });
      },
      fetchDetail: function () {
        var vm = this;
        return getA('/detail').then(function (d) {
          vm.detailMissing = false;
          vm.applyDetail(d);
        }).catch(function (e) {
          vm.dims = [];
          vm.detailSum = {};
          if (isEndpointMissing(e)) { vm.detailMissing = true; }
          else { HIS.notifyError(e); }
        });
      },
      /* detail 载荷: 数组直取; 对象取 dimensions/dims/items/list 数组 + 顶层汇总字段留作回退 */
      applyDetail: function (d) {
        var vm = this;
        var arr = null;
        if (Array.isArray(d)) {
          arr = d;
          vm.detailSum = {};
        } else if (d && typeof d === 'object') {
          arr = pickArr(d, ['dimensions', 'dims', 'items', 'list', 'rows']);
          vm.detailSum = d;
        }
        vm.dims = (arr || []).map(normDim);
      },
      /* 维度得分率(进度条) */
      dimPct: function (d) {
        if (!d || !d.maxScore) { return 0; }
        return Math.round(Math.max(0, Math.min(1, d.score / d.maxScore)) * 100);
      },
      /* 维度进度条色: ≥80绿 / ≥60黄 / <60红 */
      dimColor: function (d) {
        var p = this.dimPct(d);
        return p >= 80 ? 'var(--yb-success, #3c862d)' : (p >= 60 ? 'var(--yb-warning, #e6a23c)' : 'var(--yb-danger, #c74f4f)');
      }
    },
    template: [
      '<div class="page-card cd-fill">',
      '  <div class="page-title">病历等级自评 <span style="font-size:12px;color:var(--yb-ink-2);font-weight:normal;">八维度自评雷达 · 维度明细 · 改进建议</span></div>',
      /* ===== 顶部等级展示区 ===== */
      '  <div class="ela-hero">',
      '    <div class="ela-badge" :class="sv.tone">',
      '      <div class="ela-badge-lv">{{ sv.levelText }}</div>',
      '      <div class="ela-badge-cap">当前评级</div>',
      '    </div>',
      '    <div class="ela-meta">',
      '      <div class="ela-meta-score">总分 <b>{{ sv.total == null ? \'—\' : sv.total }}</b> / {{ sv.max == null ? \'—\' : sv.max }}</div>',
      '      <div class="ela-meta-time">评估时间: {{ sv.time }}</div>',
      '    </div>',
      '    <el-button type="primary" :loading="loading" @click="reassess">重新评估</el-button>',
      '  </div>',
      '  <div class="ela-scroll" v-loading="loading">',
      /* ===== 中部雷达图(纯SVG) ===== */
      '    <div class="ela-radar-card">',
      '      <div class="ela-radar-t">八维度雷达图</div>',
      '      <div class="ela-radar-box" v-html="radarSvg"></div>',
      '      <div class="ela-legend">',
      '        <span><span class="sw" style="background:#f7f9fc;border:1px solid #d8dfe8;"></span>满分轮廓</span>',
      '        <span><span class="sw" style="background:rgba(59,130,246,.25);border:1px solid #3b82f6;"></span>实际得分</span>',
      '      </div>',
      '    </div>',
      /* ===== 空态占位(端点未上线/暂无数据) ===== */
      '    <div v-if="!dims.length" class="ela-radar-card" style="padding:26px;">',
      '      <el-empty :image-size="80" :description="detailMissing ? \'评估服务暂未上线(等级评估接口未部署, 后端 P7b-2 交付后可见)\' : \'暂无评估维度数据, 点击右上角「重新评估」\'"></el-empty>',
      '    </div>',
      /* ===== 下部维度明细卡片(grid 4×2) ===== */
      '    <div class="ela-dims" v-if="dims.length">',
      '      <div class="ela-dim" v-for="(d, i) in dims" :key="i">',
      '        <div class="ela-dim-hd"><span class="ela-dim-name">{{ d.name }}</span><span class="ela-dim-score">{{ d.score }}/{{ d.maxScore }}</span></div>',
      '        <el-progress :percentage="dimPct(d)" :stroke-width="8" :color="dimColor(d)" style="margin-top:6px;"></el-progress>',
      '        <div class="ela-dim-metric" v-if="d.metric">{{ d.metric }}</div>',
      '        <div class="ela-dim-detail" v-if="d.detail">{{ d.detail }}</div>',
      '        <div class="ela-dim-sug" v-if="d.suggestion"><b>改进建议</b>: {{ d.suggestion }}</div>',
      '      </div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
