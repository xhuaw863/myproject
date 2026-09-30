/* 电子签名板 · 全局单例组件(HIS.SignaturePad, T34)
 * ------------------------------------------------------------------
 * 用法(返回 Promise):
 *   HIS.SignaturePad.open({ actionType: 'order_submit', refType: 'order', refId: 123 })
 *     .then(function (r) { r.signImgUrl; r.signTime; })
 *     .catch(function (e) { if (e === 'cancelled') { /* 用户取消 *\/ } });
 *
 * 数据流:
 *   GET  /api/his/signature/my-sign           预设签名(职工签名图, 可无)
 *   POST /api/his/signature/verify-password   二次密码验证(data=true/false)
 *   POST /api/his/signature/sign              Base64 PNG → 落盘 + 留痕, 返回签名图URL
 *
 * 交互: Canvas 手写(鼠标+触摸) / 使用预设签名 / 清除重签 → 确认提交 → 输入密码 → 完成;
 *       单例 el-dialog(独立 Vue 挂载于 body 末尾, 反复 open 复用同一实例);
 *       样式注入 head, 全部用设计令牌 --yb-*(--yb-primary 不存在, 主色统一 --yb-brand)。
 * 注册: HIS.SignaturePad(须在 app.js 之前加载; 各业务视图运行时调用 open)。
 */
;(function () {
  const HIS = (window.HIS = window.HIS || {});

  /* ---------- 私有样式(一次性注入 head, 无构建组件不依赖外部样式文件) ---------- */
  (function ensureStyles() {
    if (document.getElementById('sigpad-style')) { return; }
    const st = document.createElement('style');
    st.id = 'sigpad-style';
    st.textContent = [
      '.sigpad-dialog .el-dialog__header { padding:14px 16px 6px; margin-right:0; }',
      '.sigpad-dialog .el-dialog__body { padding:6px 16px 14px; }',
      '.sigpad-head { display:flex; align-items:baseline; justify-content:space-between; gap:10px; }',
      '.sigpad-head .sp-title { font-size:15px; font-weight:600; color:var(--yb-ink-1); }',
      '.sigpad-head .sp-sub { font-size:12px; color:var(--yb-ink-3); font-variant-numeric:tabular-nums; }',
      /* 画板: 固定 300x150(内部 2x 分辨率导出高清 PNG), 白底手写 */
      '.sigpad-stage { position:relative; width:300px; margin:6px auto 0; border:1px dashed var(--yb-border-strong); border-radius:var(--yb-r-md); background:var(--yb-surface); overflow:hidden; }',
      '.sigpad-stage.is-preset .sigpad-canvas { pointer-events:none; }',
      '.sigpad-canvas { display:block; width:300px; height:150px; cursor:crosshair; touch-action:none; }',
      '.sigpad-preset-img { position:absolute; inset:0; width:100%; height:100%; object-fit:contain; background:var(--yb-surface); }',
      '.sigpad-hint { position:absolute; left:0; right:0; top:50%; transform:translateY(-50%); text-align:center; color:var(--yb-ink-4); font-size:12px; pointer-events:none; user-select:none; }',
      '.sigpad-actions { display:flex; align-items:center; gap:8px; margin-top:12px; }',
      '.sigpad-spacer { flex:1; }',
      /* 密码确认步: 签名预览 + 提示 + 密码框 */
      '.sigpad-confirm-tip { font-size:12px; color:var(--yb-ink-2); margin:12px 0 6px; line-height:1.6; }',
      '.sigpad-sign-preview { border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); background:#fff; padding:6px; display:flex; justify-content:center; }',
      '.sigpad-sign-preview img { max-height:110px; max-width:100%; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* 当前时间 yyyy-MM-dd HH:mm:ss(签章时间戳, 展示用) */
  function fmtNow() {
    const d = new Date();
    const p = (n) => (n < 10 ? '0' : '') + n;
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
      ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  const SignaturePadDialog = {
    name: 'SignaturePadDialog',
    data() {
      return {
        visible: false,
        step: 1,              /* 1=手写/选签 2=密码确认 */
        canvasOk: false,      /* 画布是否有笔迹 */
        presetUrl: null,      /* 预设签名(职工签名图), 无则 null */
        usePreset: false,     /* 本次选用预设签名(代替手写) */
        signImg: '',          /* 已定稿的签名图(Base64 或预设URL) */
        password: '',
        submitting: false,
        ctx: { actionType: '', refType: null, refId: null },
        _resolve: null,
        _reject: null,
        _settled: false,
        _bound: false,
        _drawing: false,
        _last: null
      };
    },
    methods: {
      /* ---------- 对外入口: 打开签名板并返回 Promise ---------- */
      open(opts) {
        const vm = this;
        /* 上一会话未决(连点/并发)先按取消清算, 避免 Promise 悬挂 */
        if (vm._reject) { vm.settle(false, 'cancelled'); }
        const o = opts || {};
        vm.ctx = { actionType: o.actionType || 'sign', refType: o.refType || null, refId: o.refId != null ? o.refId : null };
        vm.step = 1;
        vm.password = '';
        vm.usePreset = false;
        vm.signImg = '';
        vm.canvasOk = false;
        vm.presetUrl = null;
        vm.submitting = false;
        vm._settled = false;
        vm.visible = true;
        /* 预设签名拉取(失败静默降级: 无预设不阻塞手写路径) */
        HIS.get('/api/his/signature/my-sign').then((url) => { vm.presetUrl = url || null; })
          .catch(() => { vm.presetUrl = null; });
        return new Promise((resolve, reject) => { vm._resolve = resolve; vm._reject = reject; });
      },

      /* ---------- 会话结算: resolve(成功) / reject('cancelled')(取消), 只生效一次 ---------- */
      settle(ok, payload) {
        const vm = this;
        if (vm._settled) { return; }
        vm._settled = true;
        const resolve = vm._resolve, reject = vm._reject;
        vm._resolve = null; vm._reject = null;
        vm.visible = false;
        if (ok) { if (resolve) { resolve(payload); } } else { if (reject) { reject(payload); } }
      },

      /* ---------- 画布 ---------- */
      onOpened() {
        const vm = this;
        vm.bindCanvas();
      },
      onClosed() {
        /* 容错: 异常路径(如路由级关闭)未结算时按取消处理 */
        if (!this._settled) { this.settle(false, 'cancelled'); }
      },
      bindCanvas() {
        const vm = this;
        const c = vm.$refs.pad;
        if (!c) { return; }
        if (!vm._bound) {
          vm._bound = true;
          /* 坐标换算: 触摸/鼠标 → 画布内部像素(固定 2x 内部分辨率) */
          const pos = function (e) {
            const r = c.getBoundingClientRect();
            const pt = (e.touches && e.touches[0]) ? e.touches[0] : e;
            return { x: (pt.clientX - r.left) * (c.width / r.width), y: (pt.clientY - r.top) * (c.height / r.height) };
          };
          const down = function (e) {
            e.preventDefault();
            vm._drawing = true;
            vm._last = pos(e);
          };
          const move = function (e) {
            if (!vm._drawing) { return; }
            e.preventDefault();
            const p = pos(e);
            const ctx = c.getContext('2d');
            ctx.beginPath();
            ctx.moveTo(vm._last.x, vm._last.y);
            ctx.lineTo(p.x, p.y);
            ctx.stroke();
            vm._last = p;
            if (!vm.canvasOk) { vm.canvasOk = true; }
          };
          const up = function () { vm._drawing = false; };
          /* 鼠标 + 触摸双通道(touch 事件 passive:false 以允许 preventDefault 防页面滚动) */
          c.addEventListener('mousedown', down);
          c.addEventListener('mousemove', move);
          c.addEventListener('mouseup', up);
          c.addEventListener('mouseleave', up);
          c.addEventListener('touchstart', down, { passive: false });
          c.addEventListener('touchmove', move, { passive: false });
          c.addEventListener('touchend', up);
          c.addEventListener('touchcancel', up);
        }
        vm.paintWhite();
      },
      /* 白底 + 笔触设置(白底导出 PNG, 深色背景下不变透明); 线色跟随设计令牌 */
      paintWhite() {
        const c = this.$refs.pad;
        if (!c) { return; }
        const ctx = c.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, c.width, c.height);
        const ink = (getComputedStyle(document.documentElement).getPropertyValue('--yb-ink-1') || '').trim();
        ctx.strokeStyle = ink || '#1c2430';
        ctx.lineWidth = 4;          /* 2px 视觉线宽 × 2x 内部分辨率 */
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
      },
      /* 清除重签: 恢复手写模式并清空画布 */
      clearPad() {
        this.usePreset = false;
        this.canvasOk = false;
        this.paintWhite();
      },
      usePresetSign() {
        if (!this.presetUrl) { return; }
        this.usePreset = true;
      },

      /* ---------- 提交流程: 定稿签名图 → 密码步骤 → 密码验证 → 落盘 ---------- */
      toPassword() {
        const vm = this;
        if (vm.usePreset && vm.presetUrl) {
          vm.signImg = vm.presetUrl;
        } else if (vm.canvasOk && vm.$refs.pad) {
          vm.signImg = vm.$refs.pad.toDataURL('image/png');
        } else {
          ElementPlus.ElMessage.warning('请先手写签名, 或使用预设签名');
          return;
        }
        vm.password = '';
        vm.step = 2;
        vm.$nextTick(() => {
          const i = vm.$refs.pwdInput;
          if (i && i.focus) { i.focus(); }
        });
      },
      submit() {
        const vm = this;
        if (vm.submitting) { return; }
        if (!vm.password) { ElementPlus.ElMessage.warning('请输入登录密码'); return; }
        vm.submitting = true;
        HIS.post('/api/his/signature/verify-password', { password: vm.password })
          .then((ok) => {
            if (ok !== true) { throw new Error('密码错误, 请重新输入'); }
            return HIS.post('/api/his/signature/sign', {
              actionType: vm.ctx.actionType,
              refType: vm.ctx.refType,
              refId: vm.ctx.refId,
              signImg: vm.signImg
            });
          })
          .then((url) => {
            vm.submitting = false;
            vm.settle(true, { signImgUrl: url, signTime: fmtNow() });
          })
          .catch((e) => {
            vm.submitting = false;
            HIS.notifyError(e);
          });
      },
      onCancel() {
        this.settle(false, 'cancelled');
      }
    },
    template: `
      <el-dialog v-model="visible" width="430px" append-to-body class="sigpad-dialog"
                 :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false"
                 @opened="onOpened" @closed="onClosed">
        <template #header>
          <div class="sigpad-head">
            <span class="sp-title">电子签名</span>
            <span class="sp-sub">{{ ctx.actionType }}</span>
          </div>
        </template>
        <div v-if="step === 1">
          <div class="sigpad-stage" :class="{ 'is-preset': usePreset }">
            <canvas ref="pad" width="600" height="300" class="sigpad-canvas"></canvas>
            <img v-if="usePreset && presetUrl" :src="presetUrl" class="sigpad-preset-img" alt="预设签名"/>
            <span v-if="!canvasOk && !usePreset" class="sigpad-hint">请在此处手写签名</span>
          </div>
          <div class="sigpad-actions">
            <el-button size="small" @click="clearPad">清除重签</el-button>
            <el-button size="small" :disabled="!presetUrl" :type="usePreset ? 'primary' : 'default'"
                       :title="presetUrl ? '使用职工管理中上传的预设签名' : '未配置预设签名(可在职工管理上传)'"
                       @click="usePresetSign">{{ usePreset ? '已用预设签名' : '使用预设签名' }}</el-button>
            <span class="sigpad-spacer"></span>
            <el-button size="small" @click="onCancel">取消</el-button>
            <el-button size="small" type="primary" @click="toPassword">确认提交</el-button>
          </div>
        </div>
        <div v-else>
          <div class="sigpad-sign-preview"><img :src="signImg" alt="签名预览"/></div>
          <p class="sigpad-confirm-tip">请输入您的登录密码, 完成电子签名并留痕(密码仅用于短信验证, 不做存储)。</p>
          <el-input ref="pwdInput" v-model="password" type="password" show-password
                    placeholder="登录密码" @keyup.enter="submit"></el-input>
          <div class="sigpad-actions">
            <el-button size="small" @click="step = 1">返回重签</el-button>
            <span class="sigpad-spacer"></span>
            <el-button size="small" @click="onCancel">取消</el-button>
            <el-button size="small" type="primary" :loading="submitting" @click="submit">确认签名</el-button>
          </div>
        </div>
      </el-dialog>
    `
  };

  /* ---------- 全局单例: 独立 Vue 应用挂载到 body 末尾的宿主机(反复 open 复用) ---------- */
  let _vm = null;
  function ensureVm() {
    if (_vm) { return _vm; }
    const host = document.createElement('div');
    host.id = 'sigpad-host';
    document.body.appendChild(host);
    const app = Vue.createApp(SignaturePadDialog);
    /* 全局 ElementPlus 插件(带中文 locale), 使宿主应用内的 el-* 组件可用 */
    app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
    _vm = app.mount(host);
    return _vm;
  }

  HIS.SignaturePad = {
    /**
     * 打开签名板(全局单例)。
     * @param {{actionType:string, refType?:string, refId?:number}} opts 签名场景与关联单据
     * @returns {Promise<{signImgUrl:string, signTime:string}>} 取消时 reject('cancelled')
     */
    open(opts) {
      return ensureVm().open(opts);
    }
  };
})();
