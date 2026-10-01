/* 门诊医生站 OP-B: 口腔牙位图(FDI 两位数编码, 可交互选择拼进诊断 tooth_position) */
;(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* FDI 象限: 右上1区(11-18) 左上2区(21-28) 左下3区(31-38) 右下4区(41-48); 上排18..11|21..28, 下排48..41|31..38 */
  var UPPER_LEFT = [18, 17, 16, 15, 14, 13, 12, 11];
  var UPPER_RIGHT = [21, 22, 23, 24, 25, 26, 27, 28];
  var LOWER_LEFT = [48, 47, 46, 45, 44, 43, 42, 41];
  var LOWER_RIGHT = [31, 32, 33, 34, 35, 36, 37, 38];

  var ToothChart = {
    name: 'DwToothChart',
    props: {
      /* 已选牙位逗号分隔串(如 "16,26") */
      modelValue: { type: String, default: '' }
    },
    emits: ['update:modelValue'],
    template: `
      <div class="dw-tooth-chart">
        <style>
          .dw-tooth-chart{--tc-cell:34px}
          .dw-tooth-chart .tc-row{display:flex;align-items:center;justify-content:center;gap:3px;margin-bottom:4px}
          .dw-tooth-chart .tc-quad{display:flex;gap:3px;padding:0 8px;border-right:2px solid var(--dw-border)}
          .dw-tooth-chart .tc-quad:last-child{border-right:none}
          .dw-tooth-chart .tc-line{font-size:11px;color:var(--dw-text-hint);text-align:center;margin:2px 0 6px}
          .dw-tooth-chart .tc-cell{width:var(--tc-cell);height:var(--tc-cell);border:1px solid var(--dw-border);border-radius:4px;background:var(--dw-card);color:var(--dw-text-secondary);font-size:12px;cursor:pointer;display:flex;align-items:center;justify-content:center;padding:0}
          .dw-tooth-chart .tc-cell:hover{border-color:var(--dw-primary)}
          .dw-tooth-chart .tc-cell.is-on{background:var(--dw-primary);border-color:var(--dw-primary);color:#fff;font-weight:700}
          .dw-tooth-chart .tc-foot{display:flex;align-items:center;justify-content:space-between;margin-top:8px;font-size:12px;color:var(--dw-text-secondary)}
        </style>
        <div class="tc-line">上颌 · 面对患者(左=患者右)</div>
        <div class="tc-row">
          <div class="tc-quad"><button type="button" v-for="t in upperLeft" :key="'u'+t" class="tc-cell" :class="{'is-on': isOn(t)}" @click="toggle(t)">{{ t }}</button></div>
          <div class="tc-quad"><button type="button" v-for="t in upperRight" :key="'u2'+t" class="tc-cell" :class="{'is-on': isOn(t)}" @click="toggle(t)">{{ t }}</button></div>
        </div>
        <div class="tc-line">下颌</div>
        <div class="tc-row">
          <div class="tc-quad"><button type="button" v-for="t in lowerLeft" :key="'l'+t" class="tc-cell" :class="{'is-on': isOn(t)}" @click="toggle(t)">{{ t }}</button></div>
          <div class="tc-quad"><button type="button" v-for="t in lowerRight" :key="'l2'+t" class="tc-cell" :class="{'is-on': isOn(t)}" @click="toggle(t)">{{ t }}</button></div>
        </div>
        <div class="tc-foot">
          <span>已选 {{ selectedList.length }} 颗<span v-if="selectedList.length">：{{ selectedList.join(',') }}</span></span>
          <button type="button" class="tc-cell" style="width:auto;padding:0 10px;height:26px" @click="clearAll">清空</button>
        </div>
      </div>
    `,
    data: function () {
      return { upperLeft: UPPER_LEFT, upperRight: UPPER_RIGHT, lowerLeft: LOWER_LEFT, lowerRight: LOWER_RIGHT };
    },
    computed: {
      selectedList: function () {
        var s = String(this.modelValue || '').trim();
        if (!s) { return []; }
        return s.split(/[,，\s]+/).filter(Boolean);
      }
    },
    methods: {
      isOn: function (t) { return this.selectedList.indexOf(String(t)) >= 0; },
      toggle: function (t) {
        var list = this.selectedList.slice();
        var key = String(t);
        var idx = list.indexOf(key);
        if (idx >= 0) { list.splice(idx, 1); } else { list.push(t); }
        list.sort(function (a, b) { return Number(a) - Number(b); });
        this.$emit('update:modelValue', list.join(','));
      },
      clearAll: function () { this.$emit('update:modelValue', ''); }
    }
  };

  HIS.components.DwToothChart = ToothChart;
})();
