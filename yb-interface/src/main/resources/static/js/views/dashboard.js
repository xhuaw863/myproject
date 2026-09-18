/* 工作台 + 通用占位页 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 工作台 ===== */
  HIS.views.Dashboard = {
    data: function () {
      return {
        user: HIS.getUser() || {},
        stats: [
          { lbl: '今日挂号', num: '—' },
          { lbl: '候诊人数', num: '—' },
          { lbl: '待发药处方', num: '—' },
          { lbl: '待收费单据', num: '—' }
        ]
      };
    },
    template: [
      '<div>',
      '  <div class="page-card">',
      '    <div class="page-title">工作台</div>',
      '    <div style="font-size:15px;color:#303133;">欢迎回来，{{ user.realName || user.username }}！</div>',
      '    <div style="color:#909399;font-size:13px;margin-top:6px;">',
      '      当前医院：{{ user.tenantName || "-" }}　|　角色：{{ roleName }}',
      '    </div>',
      '  </div>',
      '  <div class="stat-grid">',
      '    <div class="stat-card" v-for="s in stats" :key="s.lbl">',
      '      <div class="num">{{ s.num }}</div>',
      '      <div class="lbl">{{ s.lbl }}</div>',
      '    </div>',
      '  </div>',
      '  <div class="page-card">',
      '    <div class="page-title">系统建设进度</div>',
      '    <el-alert type="success" :closable="false" show-icon',
      '      title="P0 地基已完成：多租户框架 / JWT鉴权 / 平台管理 / 租户级医保配置 / 前端骨架"></el-alert>',
      '    <div style="margin-top:12px;color:#606266;font-size:13px;line-height:1.9;">',
      '      后续阶段：',
      '      <div>P1a 基础数据（科室 / 职工 / 排班 / 收费项目对照） + 医保字典租户化</div>',
      '      <div>P1b 患者建档 + 门诊挂号（医保2201） + 退号</div>',
      '      <div>P1c 医生站（接诊 / 病历 / 诊断 / 处方 / 检查单）</div>',
      '      <div>P1d 药库 + 药房（目录 / 入库 / 批次库存 / 调剂发药 / 退药）</div>',
      '      <div>P1e 收费结算（费用汇总 → 2204/2206/2207 → 票据 → 留存；退费2208）</div>',
      '      <div>P1f 端到端联调（mock门诊全链路） + 门诊日结报表</div>',
      '    </div>',
      '    <div style="margin-top:14px;">',
      '      <a href="/verify/index.html" target="_blank">',
      '        <el-button type="primary" plain>打开医保接口验证台</el-button>',
      '      </a>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n'),
    computed: {
      roleName: function () { return HIS.roleLabel(this.user.role); }
    }
  };

  /* ===== 通用占位页(建设中模块) ===== */
  HIS.views.Placeholder = {
    props: {
      title: { type: String, default: '功能模块' },
      phase: { type: String, default: 'P1' }
    },
    template: [
      '<div class="page-card">',
      '  <div class="placeholder">',
      '    <div class="big">🚧</div>',
      '    <h3 style="color:#303133;">{{ title }}</h3>',
      '    <p style="margin-top:10px;color:#909399;">该模块建设中，计划在 <b style="color:#1a5fb4;">{{ phase }}</b> 阶段交付。</p>',
      '  </div>',
      '</div>'
    ].join('\n')
  };
})();
