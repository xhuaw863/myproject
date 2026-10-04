/* 门诊医生站左侧候诊队列 */
;(function () {
  const QueuePanel = {
    name: 'DwQueuePanel',
    inject: ['currentVisit'],
    emits: ['select-visit', 'start-visit'],
    template: `
      <aside class="dw-left">
        <div class="dw-queue-header">
          <div class="dw-queue-title">
            <span class="dw-qt-text">候诊队列</span>
            <el-button link size="small" :loading="loading" title="刷新队列" @click="loadQueue">刷新</el-button>
          </div>
          <el-input v-model="keyword" size="small" clearable placeholder="姓名 / 序号" aria-label="搜索候诊患者">
            <template #prefix><span aria-hidden="true">⌕</span></template>
          </el-input>
        </div>

        <div class="dw-queue-content" v-loading="loading">
          <section class="dw-queue-group" v-for="group in queueGroups" :key="group.status">
            <div class="dw-queue-group-title">
              <span><i class="dw-status-dot" :class="group.dotClass"></i> {{ group.label }}</span>
              <span class="badge" :class="group.badgeClass">{{ group.items.length }}</span>
            </div>
            <!-- U3 重排: 两行卡片, 行1姓名+候时, 行2序号·性别年龄+支付标签 -->
            <div
              v-for="visit in group.items"
              :key="visit.id"
              class="dw-qi"
              :class="itemClass(visit)"
              :title="itemTitle(visit)"
              tabindex="0"
              @click="selectVisit(visit)"
              @dblclick="startVisit(visit)"
              @keyup.enter="selectVisit(visit)"
            >
              <div class="dw-qi-row1">
                <span class="dw-qi-name">
                  {{ visit.patientName || '-' }}
                  <span v-if="isPassed(visit)" class="dw-tag dw-tag--danger dw-tag--mini">过</span>
                  <span v-if="isEmergency(visit)" class="dw-tag dw-tag--danger dw-tag--mini">急</span>
                </span>
                <span v-if="visit.visitStatus === 1" class="dw-wait-time" :class="{ 'is-long': waitMinutes(visit) >= 30 }">候 {{ waitMinutes(visit) }}分</span>
              </div>
              <div class="dw-qi-row2">
                <span class="dw-qi-no">{{ visit.queueNo || visit.regNo || '-' }}</span>
                <span class="dw-qi-meta">{{ genderLabel(visit.gender) }}/{{ ageLabel(visit) }}</span>
                <span class="dw-qi-flags">
                  <span class="dw-tag dw-tag--mini" :class="isInsured(visit) ? 'dw-tag--success' : 'dw-tag--muted'">{{ isInsured(visit) ? '医保' : '自费' }}</span>
                </span>
              </div>
            </div>
          </section>
          <div v-if="!filteredQueue.length && !loading" class="dw-queue-empty">
            {{ keyword ? '未找到匹配患者' : '当日暂无候诊记录' }}
          </div>
        </div>

        <footer class="dw-queue-footer">
          <span>候:{{ statusCount(1) }}</span>
          <span>诊:{{ statusCount(2) }}</span>
          <span>完:{{ statusCount(3) }}</span>
        </footer>
      </aside>
    `,
    data: function () {
      return {
        queue: [],
        keyword: '',
        loading: false,
        timer: null,
        now: Date.now()
      };
    },
    computed: {
      selectedId: function () {
        return this.currentVisit && this.currentVisit.id;
      },
      filteredQueue: function () {
        var word = String(this.keyword || '').trim().toLowerCase();
        var list = (this.queue || []).filter(function (v) { return Number(v.visitStatus) !== 4; });
        if (!word) { return list; }
        return list.filter(function (v) {
          return [v.patientName, v.queueNo, v.regNo, v.patientNo].some(function (x) {
            return String(x || '').toLowerCase().indexOf(word) >= 0;
          });
        });
      },
      queueGroups: function () {
        var vm = this;
        var defs = [
          { status: 1, label: '候诊', badgeClass: '', dotClass: 'dw-status-dot--info' },
          { status: 2, label: '接诊中', badgeClass: 'orange', dotClass: 'dw-status-dot--warning' },
          { status: 3, label: '已完成', badgeClass: 'green', dotClass: 'dw-status-dot--success' }
        ];
        return defs.map(function (group) {
          var items = vm.filteredQueue.filter(function (v) { return Number(v.visitStatus) === group.status; });
          if (group.status === 1) {
            items.sort(function (a, b) {
              var emergencyDiff = Number(vm.isEmergency(b)) - Number(vm.isEmergency(a));
              return emergencyDiff || String(a.queueNo || '').localeCompare(String(b.queueNo || ''), 'zh-CN', { numeric: true });
            });
          }
          return Object.assign({}, group, { items: items });
        });
      }
    },
    created: function () {
      this.loadQueue();
      var vm = this;
      this.timer = window.setInterval(function () { vm.now = Date.now(); }, 60000);
    },
    beforeUnmount: function () {
      if (this.timer) { window.clearInterval(this.timer); }
    },
    methods: {
      loadQueue: function () {
        var vm = this;
        vm.loading = true;
        var date = new Date();
        var workDate = date.getFullYear() + '-' + ('0' + (date.getMonth() + 1)).slice(-2) + '-' + ('0' + date.getDate()).slice(-2);
        window.HIS.get('/api/his/visit/queue?page=1&size=200&workDate=' + workDate)
          .then(function (data) { vm.queue = (data && data.records) || []; })
          .catch(window.HIS.notifyError)
          .finally(function () { vm.loading = false; });
      },
      statusCount: function (status) {
        return (this.queue || []).filter(function (v) { return Number(v.visitStatus) === status; }).length;
      },
      selectVisit: function (visit) { this.$emit('select-visit', visit); },
      startVisit: function (visit) {
        if (Number(visit.visitStatus) !== 1) { return; }
        this.$emit('select-visit', visit);
        this.$emit('start-visit', visit);
      },
      itemClass: function (visit) {
        return {
          'is-active': visit.id === this.selectedId,
          'is-current': Number(visit.visitStatus) === 2,
          'is-consulting': Number(visit.visitStatus) === 2,
          'is-done': Number(visit.visitStatus) === 3,
          'is-completed': Number(visit.visitStatus) === 3
        };
      },
      isInsured: function (visit) { return !!(visit.psnNo || visit.insutype || visit.mdtrtId); },
      isEmergency: function (visit) {
        return String(visit.medType || '') === '13' || visit.emergencyFlag === 1 || visit.emergencyFlag === '1';
      },
      isPassed: function (visit) {
        return visit.passedFlag === 1 || visit.passedFlag === '1' || visit.callStatus === 'PASSED' || visit.queueStatus === 'PASSED';
      },
      genderLabel: function (value) {
        return value === '1' || value === '男' ? '男' : (value === '2' || value === '女' ? '女' : '-');
      },
      ageLabel: function (visit) { return visit.age === null || visit.age === undefined ? '-' : visit.age + '岁'; },
      waitMinutes: function (visit) {
        var start = visit.createTime || visit.regTime || visit.visitTime;
        if (!start) { return 0; }
        var value = new Date(String(start).replace(' ', 'T')).getTime();
        return isNaN(value) ? 0 : Math.max(0, Math.floor((this.now - value) / 60000));
      },
      itemTitle: function (visit) {
        return [visit.patientName, visit.deptName, visit.drName].filter(Boolean).join(' · ');
      }
    }
  };

  window.HIS = window.HIS || {};
  window.HIS.components = window.HIS.components || {};
  window.HIS.components.DwQueuePanel = QueuePanel;
})();