/* 全局通知中心 · 顶栏铃铛(站内消息入口)
 * 数据源: GET /api/his/inp/notification/unread-count  {total, byType:{1..6}}
 *         GET /api/his/inp/notification/list?type=&page=&size=  (IPage, 未读在前)
 *         PUT /api/his/inp/notification/{id}/read | PUT /api/his/inp/notification/read-all
 * 交互: 60s 轮询未读数(增量为正时弹 ElNotification 提醒); 点击条目标已读并按 refType 跳业务;
 *       分类页签带未读小徽标; 全部已读一键清角标。
 * 标色: TYPE_ALERT=4 按 refType 区分 sub-type —— critical_value 红色(危急值), fee_alert 橙色(费用预警)。
 * 注册: HIS.components.NotificationBell (须在 app.js 之前加载; app.js 顶栏引用)。
 * 说明: 项目未引入 @element-plus/icons-vue 图标包, 铃铛/类型图标一律线上手绘内联 SVG
 *       (与 app.js 图钉同一约束); 面板私有样式一次性注入 head(不走 his.css/theme.css, 属禁改文件)。
 */
;(function () {
  const HIS = (window.HIS = window.HIS || {});
  HIS.components = HIS.components || {};

  /* ---------- 面板私有样式(一次性注入, 无构建组件不能依赖外部样式文件) ---------- */
  (function ensureStyles() {
    if (document.getElementById('notif-bell-style')) { return; }
    const st = document.createElement('style');
    st.id = 'notif-bell-style';
    st.textContent = [
      /* 触发器: 顶栏深色底上的白色铃铛, hover 给 pill 底色(与顶栏 .user/.hosp 交互语言一致) */
      '.notif-bell .nb-trigger { display:flex; align-items:center; justify-content:center; width:32px; height:32px; border-radius:var(--yb-r-pill); color:var(--yb-header-ink); cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }',
      '.notif-bell .nb-trigger:hover { background:var(--yb-header-hover); }',
      '.notif-bell .nb-badge { line-height:0; }',
      '.notif-bell .el-badge__content { font-size:10px; height:16px; line-height:14px; padding:0 4px; }',
      /* 面板 */
      '.nb-panel { max-height:420px; display:flex; flex-direction:column; }',
      '.nb-head { display:flex; align-items:baseline; justify-content:space-between; padding:0 0 10px; border-bottom:1px solid var(--yb-border); font-weight:600; font-size:15px; color:var(--yb-ink-1); }',
      '.nb-head-sub { font-size:12px; font-weight:400; color:var(--yb-ink-3); font-variant-numeric:tabular-nums; }',
      /* 分类页签: 7 个页签收窄内边距以适配 380px 弹层; 未读小徽标(实底红, 与铃铛角标同语言) */
      '.nb-tabs .el-tabs__header { margin:0 0 2px; }',
      '.nb-tabs .el-tabs__nav-wrap::after { height:1px; }',
      '.nb-tabs .el-tabs__item { height:34px; line-height:34px; padding:0 8px; font-size:13px; }',
      '.nb-tab-badge { display:inline-block; min-width:14px; height:14px; line-height:14px; padding:0 4px; margin-left:3px; border-radius:var(--yb-r-pill); background:var(--yb-danger); color:#fff; font-size:10px; font-weight:600; text-align:center; vertical-align:middle; font-variant-numeric:tabular-nums; }',
      /* 列表: 未读条目浅品牌底 + 加粗标题 + 右侧圆点, 三通道表意 */
      '.nb-list { flex:1; overflow-y:auto; max-height:300px; }',
      '.nb-empty { text-align:center; padding:40px 0; color:var(--yb-ink-3); font-size:13px; }',
      '.nb-item { padding:10px 12px; border-bottom:1px solid var(--yb-border-light); cursor:pointer; display:flex; align-items:flex-start; gap:8px; transition:background var(--yb-dur) var(--yb-ease); }',
      '.nb-item:hover { background:var(--yb-surface-2); }',
      '.nb-item.is-unread { background:var(--el-color-primary-light-9); }',
      '.nb-item.is-unread:hover { background:var(--el-color-primary-light-8); }',
      /* 危急值通知(TYPE_ALERT=4 且 refType≠fee_alert): 红底 + 左侧红边强提示(覆盖未读蓝底, 保持红色语义) */
      '.nb-item--critical, .nb-item--critical.is-unread { background:var(--yb-danger-bg); border-left:3px solid var(--yb-danger); }',
      '.nb-item--critical:hover, .nb-item--critical.is-unread:hover { background:var(--yb-danger-border); }',
      /* 费用预警通知(refType=fee_alert): 橙底 + 左侧橙边(与危急值红色区分, 同属 TYPE_ALERT=4) */
      '.nb-item--fee-alert, .nb-item--fee-alert.is-unread { background:var(--yb-warning-bg); border-left:3px solid var(--yb-warning); }',
      '.nb-item--fee-alert:hover, .nb-item--fee-alert.is-unread:hover { background:var(--yb-warning-border); }',
      '.nb-item-ico { flex:none; margin-top:2px; }',
      '.nb-item-main { flex:1; min-width:0; }',
      '.nb-item-title { font-size:13px; line-height:1.4; color:var(--yb-ink-1); }',
      '.nb-item.is-unread .nb-item-title { font-weight:600; }',
      '.nb-item-desc { font-size:12px; color:var(--yb-ink-3); margin-top:2px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.nb-item-time { font-size:12px; color:var(--yb-ink-4); margin-top:2px; font-variant-numeric:tabular-nums; }',
      '.nb-dot { width:6px; height:6px; border-radius:50%; background:var(--yb-brand); margin-top:6px; flex-shrink:0; }',
      '.nb-foot { padding:8px 0 0; border-top:1px solid var(--yb-border); display:flex; justify-content:space-between; align-items:center; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ---------- 通知类型: 1医嘱 2会诊 3病历 4预警 5评估 6系统(与后端 InpNotificationService 常量对应) ----------
   * icon 为 16x16 viewBox 内联 SVG path(手绘, fill-rule=evenodd); color 全部走设计令牌,
   * 评估紫用填充级 --yb-fill-purple(文字级紫不在令牌表中, 保持"全站只此一处调色")。 */
  const NOTIFY_TYPES = [
    { value: 1, label: '医嘱', color: 'var(--yb-brand)',
      icon: 'M3 2.9h10a1 1 0 0 1 0 2H3a1 1 0 0 1 0-2zM3 7h10a1 1 0 0 1 0 2H3a1 1 0 0 1 0-2zM3 11.1h6.4a1 1 0 0 1 0 2H3a1 1 0 0 1 0-2z' },
    { value: 2, label: '会诊', color: 'var(--yb-success)',
      icon: 'M8 1.6a6.4 6.4 0 1 0 0 12.8A6.4 6.4 0 0 0 8 1.6zM5.5 6.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2zM8 6.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2zM10.5 6.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2z' },
    { value: 3, label: '病历', color: 'var(--yb-warning)',
      icon: 'M4 1.5h5.4l3.6 3.6v9.4H4zM9.4 1.9l3 3h-3zM6 7.4h4v1.1H6zM6 9.9h4v1.1H6z' },
    { value: 4, label: '预警', color: 'var(--yb-danger)',
      icon: 'M8 1.7 15.3 14.3H.7zM7.25 6h1.5v5h-1.5zM7.2 12h1.6v1.6h-1.6z' },
    { value: 5, label: '评估', color: 'var(--yb-fill-purple)',
      icon: 'M8 1.7a6.3 6.3 0 1 0 0 12.6A6.3 6.3 0 0 0 8 1.7zM8 3.9a4.1 4.1 0 1 0 0 8.2A4.1 4.1 0 0 0 8 3.9zM8 6.7a1.3 1.3 0 1 0 0 2.6 1.3 1.3 0 0 0 0-2.6z' },
    { value: 6, label: '系统', color: 'var(--yb-ink-3)',
      icon: 'M8 1.3l5.8 3.35v6.7L8 14.7l-5.8-3.35v-6.7zM8 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z' }
  ];
  function typeOf(v) {
    for (let i = 0; i < NOTIFY_TYPES.length; i++) { if (NOTIFY_TYPES[i].value === v) { return NOTIFY_TYPES[i]; } }
    return null;
  }

  /* ---------- 业务关联跳转表: refType(后端业务类型) -> 菜单 key ----------
   * 无映射(含当前 refType 为空的存量通知)或不带 key 时仅关闭面板不跳转; HIS.go 由 AppLayout 挂载后提供。
   * fee_alert 费用预警跳转费用清单页(待审核页签即费用预警复核入口)。 */
  const REF_ROUTES = {
    ORDER: 'inp-order-manage',
    CONSULT: 'inp-doctor-ws',
    RECORD: 'inp-med-record',
    ALERT: 'inp-doctor-ws',
    ASSESS: 'inp-nurse-ws',
    CRITICAL_VALUE: 'critical-value',
    FEE_ALERT: 'inp-charge-list'
  };

  function pad2(v) { return (v < 10 ? '0' : '') + v; }

  const NotificationBell = {
    name: 'NotificationBell',
    data() {
      return {
        totalUnread: 0,
        unreadByType: {},
        notifications: [],
        activeTab: 'all',
        popVisible: false,
        pollTimer: null,
        /* 首次计数基线标记: 首载只建基线不弹"新通知", 之后才比较增量 */
        countLoaded: false
      };
    },
    computed: {
      types() { return NOTIFY_TYPES; }
    },
    watch: {
      /* popover 打开即拉列表: 数据流驱动, 不依赖 EP 弹层事件名/触发时机 */
      popVisible(v) { if (v) { this.loadNotifications(); } }
    },
    mounted() {
      this.loadUnreadCount();
      /* 60s 轮询未读数: 后台标签页跳过(回前台由下一次 tick 或点击补齐), 面板打开时联动刷新列表 */
      const vm = this;
      this.pollTimer = setInterval(function () { vm.pollTick(); }, 60000);
    },
    beforeUnmount() {
      if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; }
    },
    methods: {
      /* 分类类型 -> 图标 path / 颜色令牌(未匹配类型回退系统色) */
      typeIcon(v) { const t = typeOf(v); return t ? t.icon : NOTIFY_TYPES[5].icon; },
      typeColor(v) { const t = typeOf(v); return t ? t.color : 'var(--yb-ink-3)'; },

      /* 通知标色分类: fee_alert 费用预警→橙; critical_value 及存量无 refType 的 TYPE_ALERT=4→红 */
      refKind(n) {
        const refType = String((n && n.refType) || '').toUpperCase();
        if (refType === 'FEE_ALERT') { return 'fee'; }
        if (refType === 'CRITICAL_VALUE' || Number(n && n.notifyType) === 4) { return 'critical'; }
        return '';
      },

      /* 条目图标色: 费用预警用橙色警示, 其余按类型令牌 */
      itemColor(n) {
        return this.refKind(n) === 'fee' ? 'var(--yb-warning)' : this.typeColor(n.notifyType);
      },

      /* 未读计数(角标 + 分类徽标): 增量弹新通知提醒; 失败静默(轮询不打扰用户) */
      loadUnreadCount() {
        const vm = this;
        return HIS.get('/api/his/inp/notification/unread-count').then(function (d) {
          const newTotal = ((d && d.total) || 0);
          vm.unreadByType = (d && d.byType) || {};
          if (vm.countLoaded && newTotal > vm.totalUnread) {
            if (window.ElementPlus && ElementPlus.ElNotification) {
              ElementPlus.ElNotification({ title: '新通知', message: '您有新的未读通知', type: 'info', duration: 5000 });
            }
          }
          vm.countLoaded = true;
          vm.totalUnread = newTotal;
        }).catch(function () { /* 静默: 角标轮询失败不打扰用户, 401 已由 HTTP 封装全局处理 */ });
      },

      /* 通知列表(第一页 20 条, 未读在前): type 空则不传该参数 */
      loadNotifications() {
        const vm = this;
        let url = '/api/his/inp/notification/list?page=1&size=20';
        if (this.activeTab !== 'all') { url += '&type=' + encodeURIComponent(this.activeTab); }
        return HIS.get(url).then(function (d) {
          vm.notifications = (d && d.records) || [];
        }).catch(function () { vm.notifications = []; });
      },

      pollTick() {
        if (document.hidden) { return; }
        this.loadUnreadCount();
        if (this.popVisible) { this.loadNotifications(); }
      },

      /* 点击通知: 未读则先标已读(乐观更新+刷新计数), 关闭面板并按业务关联跳转 */
      handleClick(n) {
        const vm = this;
        if (!n.isRead && n.id != null) {
          HIS.put('/api/his/inp/notification/' + n.id + '/read').then(function () {
            n.isRead = 1;
            vm.loadUnreadCount();
          }).catch(function () { /* 已读失败不阻断跳转 */ });
        }
        this.popVisible = false;
        this.navigate(n);
      },

      /* refType 命中跳转表且有对应入口时切业务页; 无导航能力(布局未挂载)时提示手动前往, 不静默 */
      navigate(n) {
        const refType = String((n && n.refType) || '').toUpperCase();
        const key = REF_ROUTES[refType];
        if (!key) { return; }
        if (typeof HIS.go === 'function') { HIS.go(key); return; }
        if (window.ElementPlus && ElementPlus.ElMessage) {
          if (refType === 'CRITICAL_VALUE') {
            ElementPlus.ElMessage.info('请前往「危急值管理」页面处理该告警');
          } else if (refType === 'FEE_ALERT') {
            ElementPlus.ElMessage.info('请前往「住院费用清单」页处理该费用预警');
          } else {
            ElementPlus.ElMessage.info('请前往对应业务页面查看');
          }
        }
      },

      /* 全部标记已读: 角标与分类徽标清零并重载列表 */
      markAllRead() {
        const vm = this;
        HIS.put('/api/his/inp/notification/read-all').then(function () {
          HIS.notifySuccess('已全部标记为已读');
          vm.totalUnread = 0;
          vm.unreadByType = {};
          vm.loadNotifications();
        }).catch(HIS.notifyError);
      },

      /* 查看全部: 清除分类过滤回到"全部"页签并刷新(通知无独立页面, 全部页签即全量视图) */
      showAll() {
        if (this.activeTab !== 'all') { this.activeTab = 'all'; }
        this.loadNotifications();
      },

      /* 相对时间: 1分钟内"刚刚" / 1小时内"X分钟前" / 今天"X小时前" / 昨天"昨天 HH:mm" / 更早"MM-dd HH:mm"(跨年补年) */
      formatTime(t) {
        if (!t) { return ''; }
        const d = new Date(String(t).replace(' ', 'T'));
        if (isNaN(d.getTime())) { return String(t).replace('T', ' ').substring(0, 16); }
        const now = new Date();
        const diff = Math.max(0, now.getTime() - d.getTime());
        if (diff < 60000) { return '刚刚'; }
        if (diff < 3600000) { return Math.floor(diff / 60000) + '分钟前'; }
        const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        if (d.getTime() >= startToday) { return Math.floor(diff / 3600000) + '小时前'; }
        const hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
        if (d.getTime() >= startToday - 86400000) { return '昨天 ' + hm; }
        const year = d.getFullYear() !== now.getFullYear() ? d.getFullYear() + '-' : '';
        return year + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + hm;
      }
    },
    template: `
<div class="notif-bell" style="display:inline-block;position:relative;margin-right:16px;">
  <el-popover placement="bottom-end" :width="380" trigger="click" v-model:visible="popVisible">
    <template #reference>
      <div class="nb-trigger" role="button" tabindex="0" title="通知中心" aria-label="通知中心">
        <el-badge class="nb-badge" :value="totalUnread" :hidden="!totalUnread" :max="99">
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" style="display:block;">
            <path fill="currentColor" d="M12 2.2a6.1 6.1 0 0 0-6.1 6.1v3l-1.25 2.5a1.05 1.05 0 0 0 .94 1.52h12.82a1.05 1.05 0 0 0 .94-1.52L18.1 11.3v-3A6.1 6.1 0 0 0 12 2.2z"></path>
            <path fill="currentColor" d="M9.8 17.1a2.2 2.2 0 0 0 4.4 0z"></path>
          </svg>
        </el-badge>
      </div>
    </template>
    <div class="nb-panel">
      <div class="nb-head">通知中心<span v-if="totalUnread" class="nb-head-sub">未读 {{ totalUnread }} 条</span></div>
      <el-tabs v-model="activeTab" @tab-change="loadNotifications" class="nb-tabs">
        <el-tab-pane label="全部" name="all"></el-tab-pane>
        <el-tab-pane v-for="t in types" :key="t.value" :name="String(t.value)">
          <template #label>
            <span>{{ t.label }}</span>
            <span v-if="unreadByType[t.value]" class="nb-tab-badge">{{ unreadByType[t.value] > 99 ? '99+' : unreadByType[t.value] }}</span>
          </template>
        </el-tab-pane>
      </el-tabs>
      <div class="nb-list">
        <div v-if="!notifications.length" class="nb-empty">暂无通知</div>
        <div v-for="n in notifications" :key="n.id" class="nb-item" :class="{ 'is-unread': !n.isRead, 'nb-item--critical': refKind(n) === 'critical', 'nb-item--fee-alert': refKind(n) === 'fee' }" @click="handleClick(n)">
          <svg class="nb-item-ico" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" :style="{ color: itemColor(n) }">
            <path fill="currentColor" fill-rule="evenodd" :d="typeIcon(n.notifyType)"></path>
          </svg>
          <div class="nb-item-main">
            <div class="nb-item-title">{{ n.title }}</div>
            <div v-if="n.content && n.content !== n.title" class="nb-item-desc">{{ n.content }}</div>
            <div class="nb-item-time">{{ formatTime(n.createTime) }}</div>
          </div>
          <span v-if="!n.isRead" class="nb-dot" aria-hidden="true"></span>
        </div>
      </div>
      <div class="nb-foot">
        <el-button link size="small" :disabled="!totalUnread" @click="markAllRead">全部标记已读</el-button>
        <el-button link size="small" type="primary" @click="showAll">查看全部</el-button>
      </div>
    </div>
  </el-popover>
</div>`
  };

  HIS.components.NotificationBell = NotificationBell;
})();
