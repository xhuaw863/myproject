/* 住院护士站: 病区护理工作台 —— 病区患者名单 + 医嘱审核 + 执行计划 + 护理记录(体温单折线+NRS疼痛趋势) + 交接班 + 床位一览 + 护理评估量表 + 护理计划。
 * 无构建架构: 6 个组件注册到 HIS.views, 主组件 InpNurseStation 通过 components 引用各子组件(doctor.js 同款模式)。
 * 后端接口: /api/his/inp/order-exec(审核/驳回/执行计划/执行/未执行), /api/his/inp/nursing, /api/his/inp/shift, /api/his/inp/bed, /api/his/inp/nurse。
 * 护士站看板: /api/his/inp/dashboard/nurse(5待办/危重清单/护理预警) + /api/his/inp/allergy/list(横幅过敏) + /api/his/inp/visit/{id}(护理等级)。
 * 另有 /api/his/inp/nursing-scale(list 定义/detail/assess/history/trend) 与 /api/his/inp/nursing-plan(模板 CRUD/计划实例/intervention/evaluate/close)。
 * 接口口径: /audit、/execute 请求体为裸数组 [id, ...]; /reject、/cancel-exec 参数走 query; 交接班危重数经 content JSON 的 criticalCount 传入;
 * T36 皮试/双人核对: 执行计划行携带 drugId/skinTestFlag/skinTestResult/highAlertFlag/doubleCheckFlag(皮试列+高危底色);
 * /execute 遇高警示/需皮试医嘱且无凭据时返回 {needDoubleCheck:true, execIds}, 前端弹双人核对框后携 ?verifyNurseId=&verifyNursePassword= 重发;
 * 皮试拦截(无记录/观察中/阳性/可疑)由后端 BizException 抛出, 前端以阻断式弹窗提示(皮试执行在门诊护士站皮试面板);
 * 量表评估 /assess 请求体 {inpVisitId, scaleCode, scaleDetail: JSON.stringify(answers)}(answers 值取选项 label, 多选维度传 label 数组);
 * 计划评价走 query ?result=xx; 记录措施 body {intervention, nurse}, time 由后端自动补。
 * InpNursingAssessment(量表工作台)/InpNursingPlan(计划管理) 为护士站页签子组件局部注册, 不挂 HIS.views。
 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* 护士站私有样式一次性注入(不触碰公共 css, 与其他会话并行开发互不干扰) */
  (function ensureInpNurseStyles() {
    if (document.getElementById('inp-nurse-style')) { return; }
    var st = document.createElement('style');
    st.id = 'inp-nurse-style';
    st.textContent = [
      /* 左栏患者列表 */
      '.inp-left { width:280px; flex:none; display:flex; flex-direction:column; min-height:0; border-right:1px solid var(--yb-border-light); background:var(--yb-surface); }',
      /* 左栏折叠: 头栏切换按钮 + 折叠态细导轨(与医生站同款) */
      '.inp-collapse-btn { width:22px; height:22px; line-height:1; flex:none; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); background:var(--yb-surface); color:var(--yb-ink-3); cursor:pointer; font-size:13px; transition:background var(--yb-dur) var(--yb-ease),color var(--yb-dur) var(--yb-ease); }',
      '.inp-collapse-btn:hover { background:var(--yb-surface-2); color:var(--yb-brand); }',
      '.inp-rail { width:34px; flex:none; display:flex; flex-direction:column; align-items:center; padding-top:10px; gap:12px; border-right:1px solid var(--yb-border-light); background:var(--yb-surface); }',
      '.inp-rail-text { writing-mode:vertical-rl; letter-spacing:.24em; font-size:12px; color:var(--yb-ink-3); }',
      '.inp-patient { padding:8px 12px; cursor:pointer; border-bottom:1px solid var(--yb-divider); transition:background var(--yb-dur) var(--yb-ease); }',
      '.inp-patient:hover { background:var(--yb-surface-2); }',
      '.inp-patient.is-active { background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }',
      '.inp-bed-no { display:inline-block; min-width:44px; text-align:center; font-weight:700; color:var(--yb-brand-strong); background:var(--yb-surface); border:1px solid var(--yb-brand-border); border-radius:var(--yb-r-sm); padding:0 6px; font-variant-numeric:tabular-nums; }',
      '.inp-patient .nm { font-weight:600; color:var(--yb-ink-1); margin-left:8px; }',
      '.inp-patient .sub { color:var(--yb-ink-3); font-size:12px; margin-top:2px; }',
      /* 待办统计卡片 */
      '.inp-todo { display:flex; gap:12px; }',
      '.inp-todo-card { flex:1; display:flex; align-items:baseline; gap:10px; padding:11px 14px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface); cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }',
      '.inp-todo-card:hover { box-shadow:var(--yb-sh-2); transform:translateY(-1px); }',
      '.inp-todo-card .num { font-size:var(--yb-fs-2xl); font-weight:700; font-variant-numeric:tabular-nums; letter-spacing:var(--yb-tracking-tight); }',
      '.inp-todo-card .lbl { color:var(--yb-ink-2); font-weight:600; }',
      '.inp-todo-card .hint { color:var(--yb-ink-4); font-size:var(--yb-fs-cap); margin-left:auto; }',
      '.inp-todo-card.is-warn { border-left:4px solid var(--yb-fill-warning); }',
      '.inp-todo-card.is-warn .num { color:var(--yb-warning-strong); }',
      '.inp-todo-card.is-brand { border-left:4px solid var(--yb-brand); }',
      '.inp-todo-card.is-brand .num { color:var(--yb-brand); }',
      /* 页签角标与工作区滚动 */
      '.inp-tab-num { display:inline-block; margin-left:4px; min-width:16px; padding:0 4px; height:16px; line-height:16px; border-radius:8px; background:var(--yb-fill-warning); color:#fff; font-size:10px; font-weight:700; text-align:center; vertical-align:1px; }',
      '.inp-tabs { flex:1; min-height:0; display:flex; flex-direction:column; padding:10px 14px 14px; }',
      '.inp-tabs > .el-tabs__header { margin:0 0 10px; }',
      '.inp-tabs > .el-tabs__content { flex:1; min-height:0; overflow:auto; }',
      /* 执行计划按 plan_time 分组的时间段标题 */
      '.inp-grp { display:flex; align-items:center; gap:8px; margin:12px 0 6px; color:var(--yb-ink-2); font-weight:600; }',
      '.inp-grp:first-child { margin-top:0; }',
      '.inp-grp::after { content:\'\'; flex:1; height:1px; background:var(--yb-divider); }',
      '.inp-ovd { color:var(--yb-danger); font-weight:700; }',
      /* 通用面板(交接班/体温单) */
      '.inp-panel { border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:12px 14px; margin-bottom:12px; background:var(--yb-surface); }',
      '.inp-panel h4 { margin:0 0 8px; font-size:var(--yb-fs-md); font-weight:700; color:var(--yb-ink-1); }',
      /* 床位卡片: 空床绿 / 占用蓝 / 停用灰 */
      '.inp-bed-card { height:100px; border-radius:var(--yb-r-md); border:2px solid var(--yb-border); background:var(--yb-surface); display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; overflow:hidden; transition:box-shadow var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease); }',
      '.inp-bed-card:hover { box-shadow:var(--yb-sh-2); transform:translateY(-1px); }',
      '.inp-bed-card .no { font-size:18px; font-weight:700; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; letter-spacing:var(--yb-tracking-tight); }',
      '.inp-bed-card .who { font-size:13px; font-weight:600; color:var(--yb-ink-2); max-width:100%; padding:0 6px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.inp-bed-card .sub { font-size:11px; color:var(--yb-ink-3); }',
      '.inp-bed-card.is-empty { border-color:var(--yb-success); }',
      '.inp-bed-card.is-empty .no { color:var(--yb-success-strong); }',
      '.inp-bed-card.is-occupied { border-color:var(--yb-link); background:var(--yb-info-light); }',
      '.inp-bed-card.is-occupied .who { color:var(--yb-brand-strong); }',
      '.inp-bed-card.is-disabled { border-color:var(--yb-ink-disabled); background:var(--yb-surface-2); }',
      '.inp-bed-card.is-disabled .no, .inp-bed-card.is-disabled .who { color:var(--yb-ink-disabled); }',
      /* 统计小卡(交接班/床位一览顶部) */
      '.inp-stat { text-align:center; padding:8px 4px; background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); }',
      '.inp-stat .v { font-size:18px; font-weight:700; color:var(--yb-brand-strong); font-variant-numeric:tabular-nums; }',
      '.inp-stat .k { margin-top:2px; color:var(--yb-ink-3); font-size:12px; }',
      /* ---- 床位一览状态板(对标商业 HIS 住院一览: 色彩条/标签云/房间分组/KPI条) ---- */
      '.bdo-band { margin-bottom:14px; }',
      '.bdo-band:last-child { margin-bottom:0; }',
      '.bdo-band-h { display:flex; align-items:baseline; gap:8px; margin:0 0 8px; padding-bottom:5px; border-bottom:1px solid var(--yb-divider); }',
      '.bdo-band-room { color:var(--yb-ink-1); font-weight:700; font-size:var(--yb-fs-md); }',
      '.bdo-band-room::before { content:"房间"; margin-right:6px; color:var(--yb-ink-3); font-weight:400; font-size:12px; }',
      '.bdo-band-cnt { color:var(--yb-ink-3); font-size:11px; }',
      '.bdo-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(208px,1fr)); gap:10px; }',
      '.bdo-card { position:relative; display:flex; min-height:130px; border:1px solid var(--yb-border); border-radius:var(--yb-r-md); background:var(--yb-surface); overflow:hidden; cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease), transform var(--yb-dur) var(--yb-ease), border-color var(--yb-dur) var(--yb-ease); }',
      '.bdo-card:hover { box-shadow:var(--yb-sh-2); transform:translateY(-1px); }',
      '.bdo-card.is-active { border-color:var(--yb-brand); }',
      '.bdo-card.is-off { background:var(--yb-surface-2); }',
      '.bdo-bar { width:6px; flex:none; }',
      '.bdo-body { flex:1; min-width:0; padding:7px 9px 6px; display:flex; flex-direction:column; gap:3px; }',
      '.bdo-head { display:flex; align-items:baseline; gap:5px; }',
      '.bdo-no { font-size:15px; font-weight:700; color:var(--yb-ink-1); font-variant-numeric:tabular-nums; }',
      '.bdo-meta { font-size:11px; color:var(--yb-ink-3); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
      '.bdo-badge { font-size:10px; line-height:14px; padding:0 4px; border-radius:3px; color:#fff; flex:none; }',
      '.bdo-b-red { background:var(--yb-danger); } .bdo-b-org { background:#E6A23C; } .bdo-b-blu { background:var(--yb-link); } .bdo-b-grn { background:var(--yb-success); }',
      '.bdo-name { font-size:14px; font-weight:700; color:var(--yb-brand-strong); line-height:18px; }',
      '.bdo-name.blk { color:var(--yb-ink-3); font-weight:600; }',
      '.bdo-name.off { color:var(--yb-ink-disabled); }',
      '@keyframes bdo-pulse { 0%,100% { opacity:1; } 50% { opacity:.45; } }',
      '.bdo-diag { font-size:11px; color:var(--yb-ink-3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.bdo-tags { display:flex; flex-wrap:wrap; gap:3px; min-height:16px; }',
      '.bdo-tag { font-size:10px; line-height:14px; padding:0 4px; border-radius:2px; border:1px solid transparent; white-space:nowrap; }',
      '.bdo-t-red { color:var(--yb-danger); border-color:var(--yb-danger); background:var(--yb-fill-danger); }',
      '.bdo-t-org { color:#B8820C; border-color:#E6A23C; background:var(--yb-fill-warning); }',
      '.bdo-t-grn { color:var(--yb-success-strong); border-color:var(--yb-success); background:var(--yb-fill-success); }',
      '.bdo-t-blu { color:var(--yb-link); border-color:var(--yb-link); background:var(--yb-info-light); }',
      '.bdo-t-pur { color:#7C3AED; border-color:#7C3AED; background:#F3E8FF; }',
      '.bdo-foot { margin-top:auto; display:flex; justify-content:space-between; font-size:11px; color:var(--yb-ink-3); }',
      '.bdo-kpis { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:8px; }',
      '.bdo-kpi { font-size:12px; color:var(--yb-ink-2); background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); padding:2px 8px; white-space:nowrap; }',
      '.bdo-kpi b { font-variant-numeric:tabular-nums; }',
      '.bdo-kpi-red b { color:var(--yb-danger); } .bdo-kpi-org b { color:#B8820C; } .bdo-kpi-grn b { color:var(--yb-success-strong); } .bdo-kpi-blu b { color:var(--yb-link); }',
      /* ---- 护理评估量表工作台(护士站页签, .ns-*) ---- */
      '.ns-wrap { display:flex; gap:12px; align-items:flex-start; }',
      '.ns-left { width:200px; flex:none; display:flex; flex-direction:column; gap:8px; }',
      '.ns-scale-card { padding:9px 11px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface); cursor:pointer; transition:box-shadow var(--yb-dur) var(--yb-ease), border-color var(--yb-dur) var(--yb-ease); }',
      '.ns-scale-card:hover { box-shadow:var(--yb-sh-2); }',
      '.ns-scale-card.is-active { border-color:var(--yb-brand); background:var(--yb-brand-subtle); box-shadow:inset 3px 0 0 var(--yb-brand); }',
      '.ns-scale-card .t { font-weight:600; color:var(--yb-ink-1); font-size:var(--yb-fs-base); }',
      '.ns-scale-card .sub { margin-top:4px; display:flex; align-items:center; justify-content:space-between; gap:6px; color:var(--yb-ink-3); font-size:11px; }',
      '.ns-main { flex:1; min-width:0; }',
      '.ns-desc { color:var(--yb-ink-3); font-weight:400; font-size:12px; margin-left:8px; }',
      '.ns-dim { display:flex; gap:10px; padding:9px 0; border-bottom:1px dashed var(--yb-divider); }',
      '.ns-dim:last-child { border-bottom:none; }',
      '.ns-dim-label { width:150px; flex:none; color:var(--yb-ink-1); font-weight:600; font-size:var(--yb-fs-base); padding-top:1px; }',
      '.ns-multi { margin-left:6px; color:var(--yb-link); font-weight:400; font-size:11px; }',
      '.ns-dim-opts { flex:1; min-width:0; display:flex; flex-wrap:wrap; gap:2px 16px; }',
      '.ns-dim-opts .el-radio-group, .ns-dim-opts .el-checkbox-group { display:inline-flex; flex-wrap:wrap; gap:6px 18px; }',
      '.ns-dim-opts .el-radio, .ns-dim-opts .el-checkbox { margin-right:0; height:auto; }',
      '.ns-score { color:var(--yb-ink-3); font-size:12px; margin-left:2px; }',
      '.ns-dim-score { width:64px; flex:none; text-align:right; color:var(--yb-brand-strong); font-weight:700; font-variant-numeric:tabular-nums; font-size:var(--yb-fs-base); padding-top:1px; }',
      '.ns-dim-score.is-zero { color:var(--yb-ink-disabled); font-weight:400; }',
      '.ns-total { display:flex; align-items:center; gap:14px; margin-top:12px; padding:12px 14px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface-2); }',
      '.ns-total .num { font-size:var(--yb-fs-2xl); font-weight:700; color:var(--yb-brand-strong); font-variant-numeric:tabular-nums; letter-spacing:var(--yb-tracking-tight); line-height:1; }',
      '.ns-level { display:inline-flex; align-items:center; padding:2px 10px; border-radius:var(--yb-r-pill); font-weight:700; font-size:var(--yb-fs-sm); }',
      '.ns-right { width:300px; flex:none; display:flex; flex-direction:column; gap:10px; }',
      '.ns-right .inp-panel { margin-bottom:0; }',
      '.ns-right .el-table { font-size:11px; }',
      '.ns-right .el-table .cell { padding:0 6px; }',
      '.ns-latest { border-left:4px solid var(--yb-brand); }',
      '.ns-latest .score { font-size:var(--yb-fs-2xl); font-weight:700; font-variant-numeric:tabular-nums; letter-spacing:var(--yb-tracking-tight); line-height:1.1; }',
      '.ns-trend { height:180px; }',
      /* ---- 护理计划管理(护士站页签, .np-*) ---- */
      '.np-head { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }',
      '.np-head .diag { font-weight:700; color:var(--yb-ink-1); font-size:var(--yb-fs-md); }',
      '.np-card { border-left:3px solid var(--yb-link); }',
      '.np-goal { color:var(--yb-ink-2); background:var(--yb-surface-2); border-radius:var(--yb-r-sm); padding:6px 8px; margin-top:6px; font-size:var(--yb-fs-base); }',
      '.np-sec { margin-top:8px; }',
      '.np-sec .cap { color:var(--yb-ink-3); font-size:12px; font-weight:600; margin-bottom:4px; }',
      '.np-ivs { display:flex; flex-direction:column; gap:2px; }',
      '.np-iv { display:flex; align-items:flex-start; gap:6px; color:var(--yb-ink-1); font-size:var(--yb-fs-base); }',
      '.np-iv.done { color:var(--yb-ink-3); }',
      '.np-act { display:flex; align-items:baseline; gap:8px; padding:3px 0; border-bottom:1px dashed var(--yb-divider); font-size:var(--yb-fs-sm); }',
      '.np-act:last-child { border-bottom:none; }',
      '.np-act .tm { flex:none; color:var(--yb-ink-3); font-variant-numeric:tabular-nums; }',
      '.np-act .txt { flex:1; min-width:0; color:var(--yb-ink-1); }',
      '.np-act .by { flex:none; color:var(--yb-ink-4); }',
      '.np-row-input { display:flex; gap:8px; margin-bottom:6px; }',
      /* ---- 待办5卡色变体: 蓝(审核)/绿(执行)/橙(评估)/紫(交班)/红(危重) ---- */
      '.inp-todo-card.is-success { border-left:4px solid var(--yb-fill-success); }',
      '.inp-todo-card.is-success .num { color:var(--yb-success-strong); }',
      '.inp-todo-card.is-purple { border-left:4px solid #8b5cf6; }',
      '.inp-todo-card.is-purple .num { color:#8b5cf6; }',
      '.inp-todo-card.is-danger { border-left:4px solid var(--yb-fill-danger); }',
      '.inp-todo-card.is-danger .num { color:var(--yb-danger-strong); }',
      '.inp-todo.is-5 .inp-todo-card { padding:10px 12px; gap:8px; }',
      /* ---- 危急值待处理卡(T37): 红(--yb-danger) + 有数时数字闪烁提醒; 6卡时收窄内边距并隐藏 hint ---- */
      '.inp-todo-card.is-critical { border-left:4px solid var(--yb-danger); }',
      '.inp-todo-card.is-critical .num { color:var(--yb-danger); }',
      '.inp-todo-card.is-critical .num.is-alerting { animation: inpCvBlink 1.1s ease-in-out infinite; }',
      '@keyframes inpCvBlink { 50% { opacity:.35; } }',
      '.inp-todo.is-6 .inp-todo-card { padding:9px 10px; gap:6px; }',
      '.inp-todo.is-6 .inp-todo-card .hint { display:none; }',
      /* ---- 危重患者提示条(默认收起 + el-collapse-transition 展开) ---- */
      '.inp-critbar { margin:8px 0; padding:8px 16px; border-radius:6px; background:var(--yb-danger-bg); border-left:4px solid var(--yb-danger); }',
      '.inp-crit-head { display:flex; align-items:center; gap:8px; cursor:pointer; }',
      '.inp-crit-head .ic { flex:none; display:inline-flex; color:var(--yb-danger); }',
      '.inp-crit-head .txt { flex:1; min-width:0; color:var(--yb-danger-strong); font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.inp-crit-head .toggle { flex:none; color:var(--yb-danger); font-size:12px; user-select:none; }',
      '.inp-crit-body { display:flex; flex-direction:column; gap:4px; margin-top:8px; padding-top:8px; border-top:1px dashed var(--yb-danger-border); }',
      '.inp-crit-row { display:flex; align-items:center; gap:10px; font-size:12px; color:var(--yb-ink-2); }',
      '.inp-crit-row .bd { flex:none; font-weight:700; color:var(--yb-danger-strong); font-variant-numeric:tabular-nums; }',
      '.inp-crit-row .nm { flex:none; font-weight:600; color:var(--yb-ink-1); }',
      '.inp-crit-row .diag { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      /* ---- 护理预警滚动条(<=2条静态, >=3条无缝 marquee) ---- */
      '.inp-alertbar { display:flex; align-items:center; gap:8px; height:32px; margin:8px 0; padding:0 12px; background:var(--yb-warning-bg); border:1px solid var(--yb-warning-border); border-radius:6px; overflow:hidden; }',
      '.inp-alertbar .ic { flex:none; display:inline-flex; color:var(--yb-warning); }',
      '.inp-alertbar .txt { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--yb-warning-strong); font-size:12px; }',
      '.inp-alert-mask { flex:1; min-width:0; overflow:hidden; white-space:nowrap; line-height:30px; }',
      '.inp-alert-track { display:inline-flex; align-items:center; vertical-align:top; }',
      '.inp-alert-track.is-marquee { animation:inp-marquee 30s linear infinite; }',
      '.inp-alert-track.is-marquee:hover { animation-play-state:paused; }',
      '.inp-alert-item { margin-right:56px; color:var(--yb-warning-strong); font-size:12px; white-space:nowrap; }',
      '@keyframes inp-marquee { 0% { transform:translateX(0); } 100% { transform:translateX(-50%); } }',
      /* ---- 患者信息固定横幅(护士站独立实现, 与医生站同款设计语言) ---- */
      '.inp-banner { display:flex; align-items:center; gap:10px; height:48px; padding:0 14px; background:var(--yb-surface); border-bottom:1px solid var(--yb-border); flex:none; overflow:hidden; }',
      '.inp-banner .bedblk { width:36px; height:36px; flex:none; display:flex; align-items:center; justify-content:center; border-radius:6px; background:var(--yb-brand); color:#fff; font-weight:700; font-size:13px; font-variant-numeric:tabular-nums; }',
      '.inp-banner .nm { font-weight:700; color:var(--yb-ink-1); font-size:var(--yb-fs-md); }',
      '.inp-banner .meta { color:var(--yb-ink-3); font-size:12px; }',
      '.inp-banner .sep { color:var(--yb-border); }',
      '.inp-banner .diag { max-width:280px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--yb-ink-2); font-size:12px; }',
      '.inp-banner .grow { flex:1; }',
      '.inp-allergy { flex:none; padding:1px 8px; border-radius:var(--yb-r-pill); font-size:11px; font-weight:700; }',
      '.inp-allergy.is-danger { background:var(--yb-fill-danger); color:#fff; animation:inp-blink 1.2s ease-in-out infinite; }',
      '.inp-allergy.is-safe { color:var(--yb-success-strong); background:var(--yb-success-bg); border:1px solid var(--yb-success-border); }',
      '@keyframes inp-blink { 0%, 100% { opacity:1; } 50% { opacity:.45; } }',
      '.inp-nurse-lv { flex:none; padding:2px 10px; border-radius:var(--yb-r-sm); color:#fff; font-size:12px; font-weight:700; }',
      /* ---- 表格操作列图标按钮 ---- */
      '.ns-ic { display:inline-flex; align-items:center; justify-content:center; padding:3px; margin:0 3px; border-radius:4px; cursor:pointer; transition:background var(--yb-dur) var(--yb-ease); }',
      '.ns-ic:hover { background:var(--yb-surface-3); }',
      '.ns-ic.is-success { color:var(--yb-success); }',
      '.ns-ic.is-danger { color:var(--yb-danger); }',
      '.ns-ic.is-primary { color:var(--yb-link); }',
      '.ns-ic.is-warning { color:var(--yb-warning); }',
      '.ns-ic.is-info { color:var(--yb-icon); }',
      /* ---- T36 执行面板: 皮试阳性行红底 / 高危(高警示·需皮试)行浅橙底 + 双人核对标签 ---- */
      '.inp-exec-row.is-high-alert > td.el-table__cell { background:var(--yb-warning-bg) !important; }',
      '.inp-exec-row.is-skin-positive > td.el-table__cell { background:var(--yb-danger-bg) !important; }',
      '.inp-exec-row.is-skin-positive .cell { color:var(--yb-danger-strong); }',
      '.inp-dc-tag { margin-left:6px; font-weight:700; }',
      /* ---- T45 SBAR 结构化交接班 ---- */
      '.inp-sbar-grid { display:flex; gap:14px; align-items:flex-start; }',
      '.inp-sbar-pat { width:340px; flex:none; }',
      '.inp-sbar-form { flex:1; min-width:0; }',
      '.inp-sbar-item { margin-bottom:10px; }',
      '.inp-sbar-item:last-child { margin-bottom:0; }',
      '.inp-sbar-label { display:flex; align-items:center; gap:8px; margin-bottom:4px; font-weight:600; color:var(--yb-ink-1); font-size:var(--yb-fs-sm); }',
      '.inp-sbar-label .bd { color:var(--yb-brand-strong); font-weight:800; }',
      '.inp-sbar-tag { flex:none; font-size:11px; font-weight:700; border-radius:var(--yb-r-pill); padding:0 8px; line-height:18px; }',
      '.inp-sbar-tag.s { background:var(--yb-danger-bg); color:var(--yb-danger-strong); }',
      '.inp-sbar-tag.b { background:var(--yb-info-light); color:var(--yb-brand-strong); }',
      '.inp-sbar-tag.a { background:var(--yb-warning-bg); color:var(--yb-warning-strong); }',
      '.inp-sbar-tag.r { background:var(--yb-success-bg); color:var(--yb-success-strong); }',
      /* 只读 SBAR 展示块(待接班/历史弹窗) */
      '.inp-sbar-view { white-space:pre-wrap; color:var(--yb-ink-1); background:var(--yb-surface-2); border-radius:var(--yb-r-sm); padding:8px 10px; margin-bottom:8px; font-size:var(--yb-fs-sm); line-height:1.7; }',
      '.inp-sbar-view.is-empty { color:var(--yb-ink-4); }',
      /* ---- T45 出入量: 平衡值负数红色 + 录入表单行 ---- */
      '.inp-stat.is-danger .v { color:var(--yb-danger); }',
      '.inp-io-form { display:flex; align-items:flex-end; gap:12px; flex-wrap:wrap; }',
      '.inp-io-form .fld { display:flex; flex-direction:column; gap:4px; }',
      '.inp-io-form .fld .cap { font-size:var(--yb-fs-sm); font-weight:600; color:var(--yb-ink-1); }',
      /* ================= 一体化升级私有样式: 页签分组带/入出转/费用/病人360/床卡右键与拖拽 ================= */
      /* 左侧患者视图 segment(在区/待入区/转出待办) */
      '.inp-seg { display:flex; gap:0; margin-top:8px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); overflow:hidden; }',
      '.inp-seg-item { flex:1; text-align:center; padding:5px 2px; font-size:12px; color:var(--yb-ink-2); cursor:pointer; background:var(--yb-surface); border-right:1px solid var(--yb-border-light); transition:background var(--yb-dur) var(--yb-ease); position:relative; }',
      '.inp-seg-item:last-child { border-right:none; }',
      '.inp-seg-item:hover { background:var(--yb-surface-2); }',
      '.inp-seg-item.is-active { background:var(--yb-brand); color:#fff; font-weight:600; }',
      '.inp-seg-item .dot { position:absolute; top:3px; right:5px; min-width:14px; height:14px; line-height:14px; padding:0 3px; border-radius:7px; background:var(--yb-danger); color:#fff; font-size:10px; font-weight:700; }',
      '.inp-seg-item.is-active .dot { background:#fff; color:var(--yb-brand); }',
      /* 页签分组小票(同一 group 归一色点) */
      '.inp-grp-dot { display:inline-block; width:6px; height:6px; border-radius:50%; margin-right:5px; vertical-align:2px; }',
      /* ---- 入出转工作台(.nfw-) ---- */
      '.nfw { display:flex; gap:12px; align-items:stretch; min-height:0; }',
      '.nfw-main { flex:1; min-width:0; display:flex; flex-direction:column; }',
      '.nfw-side { width:320px; flex:none; border-left:1px solid var(--yb-border-light); padding-left:12px; display:flex; flex-direction:column; gap:10px; }',
      '.nfw-toolbar { display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:8px; }',
      '.nfw-tl { display:flex; flex-direction:column; gap:0; }',
      '.nfw-tl-item { display:flex; gap:10px; padding:8px 0; border-bottom:1px dashed var(--yb-divider); }',
      '.nfw-tl-item:last-child { border-bottom:none; }',
      '.nfw-tl-item .dot { flex:none; width:10px; height:10px; margin-top:4px; border-radius:50%; background:var(--yb-border); }',
      '.nfw-tl-item.is-done .dot { background:var(--yb-success); }',
      '.nfw-tl-item .body { flex:1; min-width:0; }',
      '.nfw-tl-item .lb { font-weight:600; color:var(--yb-ink-1); font-size:var(--yb-fs-base); }',
      '.nfw-tl-item .nt { color:var(--yb-ink-3); font-size:12px; margin-top:2px; }',
      '.nfw-tl-item .tm { color:var(--yb-ink-4); font-size:11px; margin-top:2px; font-variant-numeric:tabular-nums; }',
      '.nfw-badge { display:inline-block; font-size:11px; line-height:16px; padding:0 6px; border-radius:3px; color:#fff; }',
      '.nfw-bedpick-empty { color:var(--yb-ink-4); font-size:12px; padding:8px 0; }',
      /* ---- 费用管理(.nfee-) ---- */
      '.nfee { display:flex; gap:12px; align-items:stretch; min-height:0; }',
      '.nfee-left { width:280px; flex:none; display:flex; flex-direction:column; gap:10px; }',
      '.nfee-main { flex:1; min-width:0; }',
      '.nfee-acct { border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); padding:12px 14px; background:var(--yb-surface); }',
      '.nfee-acct .bal { font-size:30px; font-weight:700; color:var(--yb-brand-strong); font-variant-numeric:tabular-nums; letter-spacing:var(--yb-tracking-tight); line-height:1.1; }',
      '.nfee-acct .bal.is-neg { color:var(--yb-danger); }',
      '.nfee-acct .lbl { color:var(--yb-ink-3); font-size:12px; }',
      '.nfee-kpis { display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }',
      '.nfee-kpi { font-size:12px; color:var(--yb-ink-2); background:var(--yb-surface-2); border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); padding:3px 8px; }',
      '.nfee-kpi b { color:var(--yb-brand-strong); font-variant-numeric:tabular-nums; }',
      '.nfee-sec { margin-top:10px; }',
      '.nfee-sec-h { display:flex; align-items:center; gap:8px; margin:0 0 8px; padding-bottom:5px; border-bottom:1px solid var(--yb-divider); }',
      '.nfee-sec-h .t { font-weight:700; color:var(--yb-ink-1); font-size:var(--yb-fs-md); }',
      '.nfee-money { font-variant-numeric:tabular-nums; color:var(--yb-brand-strong); font-weight:600; }',
      '.nfee-money.is-neg { color:var(--yb-danger); }',
      /* ---- 病人信息360(.np360-) ---- */
      '.np360 { display:flex; flex-direction:column; gap:10px; }',
      '.np360-hd { display:flex; align-items:center; gap:12px; padding:10px 14px; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-brand-subtle); }',
      '.np360-hd .bed { width:40px; height:40px; flex:none; display:flex; align-items:center; justify-content:center; border-radius:8px; background:var(--yb-brand); color:#fff; font-weight:700; }',
      '.np360-hd .nm { font-weight:700; color:var(--yb-ink-1); font-size:18px; }',
      '.np360-hd .meta { color:var(--yb-ink-3); font-size:12px; }',
      '.np360-cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(360px,1fr)); gap:10px; }',
      '.np360-card { border:1px solid var(--yb-border-light); border-radius:var(--yb-r-md); background:var(--yb-surface); overflow:hidden; }',
      '.np360-card.is-danger { border-left:4px solid var(--yb-danger); }',
      '.np360-card-h { display:flex; align-items:center; gap:8px; padding:9px 12px; cursor:pointer; background:var(--yb-surface-2); font-weight:600; color:var(--yb-ink-1); }',
      '.np360-card-h .cnt { margin-left:auto; color:var(--yb-ink-3); font-size:12px; font-weight:400; }',
      '.np360-card-b { padding:10px 12px; }',
      '.np360-kv { display:flex; justify-content:space-between; gap:10px; padding:3px 0; border-bottom:1px dashed var(--yb-divider); font-size:13px; }',
      '.np360-kv:last-child { border-bottom:none; }',
      '.np360-kv .k { color:var(--yb-ink-3); flex:none; }',
      '.np360-kv .v { color:var(--yb-ink-1); text-align:right; min-width:0; overflow:hidden; text-overflow:ellipsis; }',
      /* ---- 床位一览: 右键菜单/拖拽高亮/图例抽屉 ---- */
      '.bctx { position:fixed; z-index:3000; min-width:168px; background:var(--yb-surface); border:1px solid var(--yb-border); border-radius:var(--yb-r-sm); box-shadow:var(--yb-sh-2); padding:4px 0; }',
      '.bctx-item { display:flex; align-items:center; gap:8px; padding:6px 14px; font-size:13px; color:var(--yb-ink-1); cursor:pointer; }',
      '.bctx-item:hover { background:var(--yb-brand-subtle); color:var(--yb-brand-strong); }',
      '.bctx-item.is-danger { color:var(--yb-danger); }',
      '.bctx-sep { height:1px; margin:4px 0; background:var(--yb-divider); }',
      '.bdo-card.is-drag-over { border-color:var(--yb-brand); box-shadow:0 0 0 3px var(--yb-brand-subtle); }',
      '.bdo-card.is-dragging { opacity:.5; }',
      '.bdo-legend { display:flex; flex-direction:column; gap:8px; }',
      '.bdo-legend-row { display:flex; align-items:center; gap:10px; font-size:13px; color:var(--yb-ink-2); }',
      '.bdo-legend-sw { width:22px; height:14px; border-radius:3px; flex:none; }',
      '.bdo-view-seg { display:inline-flex; gap:0; border:1px solid var(--yb-border-light); border-radius:var(--yb-r-sm); overflow:hidden; }',
      '.bdo-view-seg span { padding:4px 10px; font-size:12px; cursor:pointer; color:var(--yb-ink-2); border-right:1px solid var(--yb-border-light); }',
      '.bdo-view-seg span:last-child { border-right:none; }',
      '.bdo-view-seg span.is-active { background:var(--yb-brand); color:#fff; font-weight:600; }',
      '.bdo-slim { display:flex; align-items:center; gap:10px; padding:6px 10px; border:1px solid var(--yb-border-light); border-left-width:5px; border-radius:var(--yb-r-sm); background:var(--yb-surface); margin-bottom:6px; cursor:pointer; }',
      '.bdo-slim.is-active { border-color:var(--yb-brand); }',
      '.bdo-slim .no { font-weight:700; width:52px; flex:none; font-variant-numeric:tabular-nums; }',
      '.bdo-slim .who { font-weight:600; width:88px; flex:none; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.bdo-slim .diag { flex:1; min-width:0; color:var(--yb-ink-3); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.bdo-slim .tags { flex:none; display:flex; gap:3px; }',
      '.bdo-search-hit { box-shadow:0 0 0 3px var(--yb-warning) !important; }'
    ].join('\n');
    document.head.appendChild(st);
  })();

  /* ===== 常量映射(与后端 InpOrderExecService / InpShiftService 枚举一致) ===== */
  var ORDER_TYPE = { 1: '长期', 2: '临时' };
  var ORDER_CATEGORY = { 1: '药品', 2: '检查', 3: '检验', 4: '治疗', 5: '护理', 6: '膳食', 7: '其他' };
  var EXEC_STATUS = { 1: '待执行', 2: '已执行', 3: '未执行' };
  var EXEC_STATUS_TYPE = { 1: 'warning', 2: 'success', 3: 'danger' };
  /* T36 皮试结果映射(与后端 SkinTestService 一致): 0观察中/1阴性/2阳性/3可疑, 无记录=待做 */
  var SKIN_TEST_RESULT = { 0: { label: '观察中', type: 'primary' }, 1: { label: '阴性', type: 'success' }, 2: { label: '阳性', type: 'danger' }, 3: { label: '可疑', type: 'warning' } };
  var SHIFT_TYPE = { 1: '白班', 2: '小夜班', 3: '大夜班' };
  var SHIFT_STATUS = { 1: '待接班', 2: '已交接' };
  var SHIFT_STATUS_TYPE = { 1: 'warning', 2: 'success' };
  var NURSING_TYPE = { 1: '体温单', 2: '护理评估', 3: '护理计划', 4: '护理措施', 5: '护理总结' };
  var NURSING_TYPE_TAG = { 1: 'danger', 2: 'primary', 3: 'success', 4: 'warning', 5: 'info' };
  /* 病情/护理/过敏映射(与后端 his_inp_visit / his_inp_allergy 枚举一致) */
  var CONDITION_LEVEL = { 1: '病危', 2: '病重', 3: '一般' };
  var NURSING_LEVELS = { 1: '特级护理', 2: '一级护理', 3: '二级护理', 4: '三级护理' };
  var ALLERGY_TYPE = { 1: '药物', 2: '食物', 3: '环境', 4: '其他' };
  /* ===== 入出转/费用/360 常量(与后端 his_inp_transfer / deposit / settle / daily-bill / fee-alert / diagnosis 枚举一致) ===== */
  /* 转科转床申请状态机: 1申请 2批准 3拒绝 4已执行 5取消 */
  var TRANSFER_STATUS = { 1: '待审批', 2: '已批准', 3: '已退回', 4: '已执行', 5: '已取消' };
  var TRANSFER_STATUS_TAG = { 1: 'warning', 2: 'primary', 3: 'danger', 4: 'success', 5: 'info' };
  /* 流转类型: 1转科 2转床 3加床 */
  var TRANSFER_TYPE = { 1: '转科', 2: '转床', 3: '加床' };
  /* 退回常见原因(护士可直选或自录) */
  var TRANSFER_REJECT_REASONS = ['床位已满', '诊断不符', '转科资料不全', '需先处理未完成业务', '目标科室拒收', '其他'];
  var FEE_TYPE_MAP = { 1: '西药费', 2: '中药费', 3: '检查费', 4: '检验费', 5: '治疗费', 6: '护理费', 7: '材料费', 8: '床位费', 9: '其他' };
  var FEE_TYPE_OPTIONS = [{ v: 1, l: '西药费' }, { v: 2, l: '中药费' }, { v: 3, l: '检查费' }, { v: 4, l: '检验费' }, { v: 5, l: '治疗费' }, { v: 6, l: '护理费' }, { v: 7, l: '材料费' }, { v: 8, l: '床位费' }, { v: 9, l: '其他' }];
  var PAY_TYPES = [{ v: 1, l: '现金' }, { v: 2, l: '微信' }, { v: 3, l: '支付宝' }, { v: 4, l: '银行卡' }];
  var DIRECTIONS = [{ v: 1, l: '缴纳' }, { v: 2, l: '退还' }];
  var SETTLE_TYPES = { 1: '出院结算', 2: '中途结算', 3: '退费' };
  var DIAG_TYPES = { 1: '入院诊断', 2: '补充诊断', 3: '术后诊断', 4: '出院诊断' };
  var BED_STATUS = { 0: '空床', 1: '占用', 2: '停用' };
  var BED_TYPES = { 1: '普通', 2: '抢救', 3: '监护', 4: '隔离' };
  /* 欠费预警处理结果: 1已催缴 2已减免 3已结清 4忽略 */
  var FEE_ALERT_HANDLE = { 1: '已催缴', 2: '已减免', 3: '已结清', 4: '暂不处理' };
  /* 一体化主页页签配置(顺序可自定义, localStorage 持久化): group 归组、iconKey 无则纯文字 */
  var HOME_TABS = [
    { key: 'flow', label: '入出转', group: '流转' },
    { key: 'audit', label: '医嘱审核', group: '医嘱' },
    { key: 'exec', label: '医嘱执行', group: '医嘱' },
    { key: 'fee', label: '费用管理', group: '费用' },
    { key: 'patient', label: '病人信息', group: '总览' },
    { key: 'nursing', label: '护理记录', group: '护理' },
    { key: 'io', label: '出入量', group: '护理' },
    { key: 'med', label: '给药记录', group: '护理' },
    { key: 'assess', label: '护理评估', group: '护理' },
    { key: 'plan', label: '护理计划', group: '护理' },
    { key: 'shift', label: '交接班', group: '病区' },
    { key: 'bed', label: '床位一览', group: '病区' }
  ];

  /* ===== 图标组件: 无构建环境未引入图标包, 内联 element-plus 官方 SVG path 自绘 ===== */
  var NS_ICON_PATHS = {
    'circle-check': [
      'M512 896a384 384 0 1 0 0-768 384 384 0 0 0 0 768m0 64a448 448 0 1 1 0-896 448 448 0 0 1 0 896',
      'M745.344 361.344a32 32 0 0 1 45.312 45.312l-288 288a32 32 0 0 1-45.312 0l-160-160a32 32 0 1 1 45.312-45.312L480 626.752l265.344-265.408z'
    ],
    'circle-close': [
      'm466.752 512-90.496-90.496a32 32 0 0 1 45.248-45.248L512 466.752l90.496-90.496a32 32 0 1 1 45.248 45.248L557.248 512l90.496 90.496a32 32 0 1 1-45.248 45.248L512 557.248l-90.496 90.496a32 32 0 0 1-45.248-45.248z',
      'M512 896a384 384 0 1 0 0-768 384 384 0 0 0 0 768m0 64a448 448 0 1 1 0-896 448 448 0 0 1 0 896'
    ],
    'check': ['M406.656 706.944 195.84 496.256a32 32 0 1 0-45.248 45.248l256 256 512-512a32 32 0 0 0-45.248-45.248L406.592 706.944z'],
    'edit': [
      'M832 512a32 32 0 1 1 64 0v352a32 32 0 0 1-32 32H160a32 32 0 0 1-32-32V160a32 32 0 0 1 32-32h352a32 32 0 0 1 0 64H192v640h640z',
      'm469.952 554.24 52.8-7.552L847.104 222.4a32 32 0 1 0-45.248-45.248L477.44 501.44l-7.552 52.8zm422.4-422.4a96 96 0 0 1 0 135.808l-331.84 331.84a32 32 0 0 1-18.112 9.088L436.8 623.68a32 32 0 0 1-36.224-36.224l15.104-105.6a32 32 0 0 1 9.024-18.112l331.904-331.84a96 96 0 0 1 135.744 0z'
    ],
    'delete': ['M160 256H96a32 32 0 0 1 0-64h256V95.936a32 32 0 0 1 32-32h256a32 32 0 0 1 32 32V192h256a32 32 0 1 1 0 64h-64v672a32 32 0 0 1-32 32H192a32 32 0 0 1-32-32zm448-64v-64H416v64zM224 896h576V256H224zm192-128a32 32 0 0 1-32-32V416a32 32 0 0 1 64 0v320a32 32 0 0 1-32 32m192 0a32 32 0 0 1-32-32V416a32 32 0 0 1 64 0v320a32 32 0 0 1-32 32'],
    'select': ['M77.248 415.04a64 64 0 0 1 90.496 0l226.304 226.304L846.528 188.8a64 64 0 1 1 90.56 90.496l-543.04 543.04-316.8-316.8a64 64 0 0 1 0-90.496z'],
    'view': ['M512 160c320 0 512 352 512 352S832 864 512 864 0 512 0 512s192-352 512-352m0 64c-225.28 0-384.128 208.064-436.8 288 52.608 79.872 211.456 288 436.8 288 225.28 0 384.128-208.064 436.8-288-52.608-79.872-211.456-288-436.8-288zm0 64a224 224 0 1 1 0 448 224 224 0 0 1 0-448m0 64a160.192 160.192 0 0 0-160 160c0 88.192 71.744 160 160 160s160-71.808 160-160-71.744-160-160-160'],
    'finished': ['M280.768 753.728 691.456 167.04a32 32 0 1 1 52.416 36.672L314.24 817.472a32 32 0 0 1-45.44 7.296l-230.4-172.8a32 32 0 0 1 38.4-51.2l203.968 152.96zM736 448a32 32 0 1 1 0-64h192a32 32 0 1 1 0 64zM608 640a32 32 0 0 1 0-64h319.936a32 32 0 1 1 0 64zM480 832a32 32 0 1 1 0-64h447.936a32 32 0 1 1 0 64z'],
    'more-filled': ['M176 416a112 112 0 1 1 0 224 112 112 0 0 1 0-224m336 0a112 112 0 1 1 0 224 112 112 0 0 1 0-224m336 0a112 112 0 1 1 0 224 112 112 0 0 1 0-224'],
    'close': ['M764.288 214.592 512 466.88 259.712 214.592a31.936 31.936 0 0 0-45.12 45.12L466.752 512 214.528 764.224a31.936 31.936 0 1 0 45.12 45.184L512 557.184l252.288 252.288a31.936 31.936 0 0 0 45.12-45.12L557.12 512.064l252.288-252.352a31.936 31.936 0 1 0-45.12-45.184z'],
    'warning': ['M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896m0 832a384 384 0 0 0 0-768 384 384 0 0 0 0 768m48-176a48 48 0 1 1-96 0 48 48 0 0 1 96 0m-48-464a32 32 0 0 1 32 32v288a32 32 0 0 1-64 0V288a32 32 0 0 1 32-32'],
    'warning-filled': ['M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896m0 192a58.432 58.432 0 0 0-58.24 63.744l23.36 256.384a35.072 35.072 0 0 0 69.76 0l23.296-256.384A58.432 58.432 0 0 0 512 256m0 512a51.2 51.2 0 1 0 0-102.4 51.2 51.2 0 0 0 0 102.4']
  };
  var NsIcon = {
    name: 'NsIcon',
    props: {
      name: { type: String, required: true },
      size: { type: [String, Number], default: 15 }
    },
    computed: {
      paths: function () { return NS_ICON_PATHS[this.name] || []; }
    },
    template: [
      '<svg viewBox="0 0 1024 1024" :width="size" :height="size" style="display:inline-block;vertical-align:-0.15em;" aria-hidden="true">',
      '  <path v-for="(d, i) in paths" :key="i" :d="d" fill="currentColor"></path>',
      '</svg>'
    ].join('\n')
  };

  /* ===== 通用工具 ===== */
  function pad2(n) { return ('0' + n).slice(-2); }
  function dateTimeStr(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
      + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  function todayStr() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function nowStr() { return dateTimeStr(new Date()); }
  function nowHM() {
    var d = new Date();
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  /* LocalDateTime 可能序列化为 ISO 字符串或时间戳, 统一转为 'yyyy-MM-dd HH:mm:ss' 展示 */
  function fmtTime(v) {
    if (v == null || v === '') { return '-'; }
    if (typeof v === 'number') { return dateTimeStr(new Date(v)); }
    return String(v).replace('T', ' ').substring(0, 19);
  }
  function datePart(v) {
    var t = fmtTime(v);
    return t === '-' ? '-' : t.substring(0, 10);
  }
  /* 入院天数(按自然日差) */
  function daysSince(v) {
    if (v == null || v === '') { return null; }
    var d = typeof v === 'number' ? new Date(v) : new Date(String(v).replace(' ', 'T'));
    if (isNaN(d.getTime())) { return null; }
    var t0 = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    var n = new Date();
    var tn = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
    return Math.max(0, Math.round((tn - t0) / 86400000));
  }
  /* 计划时间已过点且仍待执行(超时高亮) */
  function isOverdue(planTime) {
    if (!planTime) { return false; }
    var d = new Date(String(planTime).replace(' ', 'T'));
    return !isNaN(d.getTime()) && d.getTime() < Date.now();
  }
  /* 床位号自然序: 3 号在 10 号前, 无床号置后 */
  function byBedNo(a, b) {
    var x = a.bedNo == null ? '' : String(a.bedNo);
    var y = b.bedNo == null ? '' : String(b.bedNo);
    if (!x) { return 1; }
    if (!y) { return -1; }
    return x.localeCompare(y, 'zh-CN', { numeric: true });
  }
  function parseJson(text) {
    if (text == null || text === '') { return null; }
    try {
      var o = JSON.parse(text);
      return o && typeof o === 'object' ? o : null;
    } catch (e) { return null; }
  }
  /* 非体温单记录内容摘要(内容约定 JSON {"text": "..."}, 兼容纯文本存量) */
  function textOf(content) {
    var o = parseJson(content);
    if (o && o.text != null) { return String(o.text); }
    return content == null ? '' : String(content);
  }
  /* 体温单记录摘要 */
  function tempSummary(content) {
    var o = parseJson(content);
    if (!o) { return content == null ? '-' : String(content); }
    var s = [];
    if (o.temperature != null) { s.push('体温 ' + o.temperature + '℃'); }
    if (o.pulse != null) { s.push('脉搏 ' + o.pulse); }
    if (o.respiration != null) { s.push('呼吸 ' + o.respiration); }
    if (o.systolicBp != null || o.diastolicBp != null) {
      s.push('血压 ' + (o.systolicBp == null ? '-' : o.systolicBp) + '/' + (o.diastolicBp == null ? '-' : o.diastolicBp));
    }
    return s.length ? s.join(' · ') : '-';
  }
  function nursingSummary(row) {
    if (row.recordType === 1) { return tempSummary(row.content); }
    /* 量表评估记录(content 快照 {score, scaleCode, scaleName, level, color, answers}): 展示评分与风险等级, 避免裸 JSON */
    if (row.recordType === 2) {
      var ao = parseJson(row.content);
      if (ao && ao.score != null) {
        return '评分 ' + ao.score + ' 分' + (ao.level ? ' · ' + ao.level : '') + (ao.scaleName ? ' · ' + ao.scaleName : '');
      }
    }
    return textOf(row.content);
  }

  /* ================= 2. 医嘱审核(新开医嘱 批量审核 / 驳回作废) ================= */
  HIS.views.InpOrderAudit = {
    name: 'InpOrderAudit',
    props: { wardId: { type: [String, Number], default: null } },
    components: { 'ns-icon': NsIcon },
    data: function () {
      return {
        loading: false, auditing: false,
        list: [], total: 0, page: 1, size: 20,
        selRows: [],
        rejectVisible: false, rejectTargets: [], rejectReason: '', rejecting: false
      };
    },
    computed: {
      allSelected: function () { return this.list.length > 0 && this.selRows.length >= this.list.length; }
    },
    watch: {
      wardId: function () { this.page = 1; this.load(); }
    },
    created: function () { this.load(); },
    methods: {
      fmtTime: fmtTime,
      orderTypeLabel: function (v) { return v == null ? '-' : (ORDER_TYPE[v] || v); },
      orderTypeTag: function (v) { return v === 1 ? 'primary' : 'info'; },
      catLabel: function (v) { return v == null ? '' : (ORDER_CATEGORY[v] || ''); },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      load: function () {
        var vm = this;
        if (!vm.wardId) { vm.list = []; vm.total = 0; return; }
        vm.loading = true;
        HIS.get('/api/his/inp/order-exec/pending?wardId=' + HIS.idParam(vm.wardId) + '&page=' + vm.page + '&size=' + vm.size)
          .then(function (d) {
            vm.list = (d && d.records) || [];
            vm.total = (d && d.total) || 0;
            vm.selRows = [];
          }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      onSelChange: function (rows) { this.selRows = rows; },
      toggleAll: function () {
        var t = this.$refs.auditTable;
        if (!t) { return; }
        if (this.allSelected) { t.clearSelection(); } else { t.toggleAllSelection(); }
      },
      invertSelection: function () {
        var vm = this;
        var t = vm.$refs.auditTable;
        if (!t) { return; }
        var sel = {};
        vm.selRows.forEach(function (r) { sel[HIS.idKey(r.id)] = true; });
        vm.list.forEach(function (row) { t.toggleRowSelection(row, !sel[HIS.idKey(row.id)]); });
      },
      auditIds: function (ids) {
        var vm = this;
        if (!ids || !ids.length) { return; }
        vm.auditing = true;
        HIS.post('/api/his/inp/order-exec/audit', ids).then(function (d) {
          var audited = (d && d.audited) || 0;
          var plans = (d && d.execPlans) || 0;
          var skipped = (d && d.skipped) || [];
          var msg = '审核完成: ' + audited + ' 条已审核' + (plans ? '，生成执行计划 ' + plans + ' 条' : '');
          if (skipped.length) {
            ElementPlus.ElMessage.warning(msg + '，' + skipped.length + ' 条状态已变更被跳过');
          } else {
            HIS.notifySuccess(msg);
          }
          vm.load();
          vm.$emit('changed');
        }).catch(HIS.notifyError).finally(function () { vm.auditing = false; });
      },
      auditOne: function (row) { this.auditIds([row.id]); },
      auditSelected: function () { this.auditIds(this.selRows.map(function (r) { return r.id; })); },
      openReject: function (row) {
        this.rejectTargets = [row.id];
        this.rejectReason = '';
        this.rejectVisible = true;
      },
      openBatchReject: function () {
        if (!this.selRows.length) { return; }
        this.rejectTargets = this.selRows.map(function (r) { return r.id; });
        this.rejectReason = '';
        this.rejectVisible = true;
      },
      doReject: function () {
        var vm = this;
        var reason = (vm.rejectReason || '').trim();
        if (!reason) { ElementPlus.ElMessage.warning('请填写驳回原因'); return; }
        var ids = vm.rejectTargets.slice();
        vm.rejecting = true;
        var ok = 0;
        var fails = [];
        var i = 0;
        function step() {
          if (i >= ids.length) {
            var msg = '驳回成功 ' + ok + ' 条' + (fails.length ? '，失败 ' + fails.length + ' 条: ' + fails[0] : '');
            if (fails.length) { ElementPlus.ElMessage.warning(msg); } else { HIS.notifySuccess(msg); }
            vm.rejecting = false;
            vm.rejectVisible = false;
            vm.load();
            vm.$emit('changed');
            return;
          }
          var id = ids[i++];
          HIS.post('/api/his/inp/order-exec/reject?orderId=' + HIS.idParam(id) + '&reason=' + encodeURIComponent(reason))
            .then(function () { ok++; })
            .catch(function (e) { fails.push((e && e.message) || String(e)); })
            .finally(step);
        }
        step();
      }
    },
    template: [
      '<div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <el-button size="small" @click="toggleAll">{{ allSelected ? \'取消全选\' : \'全选\' }}</el-button>',
      '    <el-button size="small" @click="invertSelection">反选</el-button>',
      '    <el-button type="primary" size="small" :disabled="!selRows.length" :loading="auditing" @click="auditSelected">批量审核({{ selRows.length }})</el-button>',
      '    <el-button type="danger" plain size="small" :disabled="!selRows.length" @click="openBatchReject">批量驳回</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-3);font-size:12px;">先开先审 · 共 {{ total }} 条待审核</span>',
      '    <el-button size="small" @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table ref="auditTable" :data="list" v-loading="loading" border stripe size="small" max-height="calc(100vh - 300px)" @selection-change="onSelChange">',
      '    <el-table-column type="selection" width="42" align="center"></el-table-column>',
      '    <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '    <el-table-column label="床位号" width="80" align="center">',
      '      <template #default="s"><span class="inp-bed-no">{{ s.row.bedNo || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="患者姓名" width="120">',
      '      <template #default="s">{{ s.row.patientName || \'-\' }}<span style="color:var(--yb-ink-3);font-size:12px;"> {{ s.row.gender || \'\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="医嘱类型" width="86" align="center">',
      '      <template #default="s"><el-tag size="small" :type="orderTypeTag(s.row.orderType)">{{ orderTypeLabel(s.row.orderType) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="医嘱内容" min-width="260" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.orderContent || \'-\' }}<span style="color:var(--yb-ink-3);"> {{ s.row.spec || \'\' }}{{ s.row.dosage ? \' · 剂量 \' + s.row.dosage + (s.row.dosageUnit || \'\') : \'\' }}{{ s.row.usageCode ? \' · 用法 \' + s.row.usageCode : \'\' }}{{ s.row.freqCode ? \' · 频次 \' + s.row.freqCode : \'\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="分类" width="70" align="center">',
      '      <template #default="s"><span style="color:var(--yb-ink-3);">{{ catLabel(s.row.orderCategory) || \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="开嘱医生" width="100">',
      '      <template #default="s">{{ s.row.doctorName || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="开嘱时间" width="150" align="center">',
      '      <template #default="s">{{ fmtTime(s.row.createTime) }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="92" align="center" fixed="right">',
      '      <template #default="s">',
      '        <el-tooltip content="审核通过" placement="top">',
      '          <span class="ns-ic is-success" @click="auditOne(s.row)"><ns-icon name="circle-check"></ns-icon></span>',
      '        </el-tooltip>',
      '        <el-tooltip content="驳回" placement="top">',
      '          <span class="ns-ic is-danger" @click="openReject(s.row)"><ns-icon name="circle-close"></ns-icon></span>',
      '        </el-tooltip>',
      '      </template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-empty v-if="!loading && !list.length" description="本病区暂无待审核医嘱" :image-size="60"></el-empty>',
      '  <el-pagination v-if="total > 0" style="margin-top:10px;justify-content:flex-end;" background small layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :current-page="page" :page-sizes="[10, 20, 50, 100]" @size-change="onSize" @current-change="onPage"></el-pagination>',
      /* 驳回原因弹窗 */
      '  <el-dialog v-model="rejectVisible" title="驳回医嘱" width="460px">',
      '    <div style="font-weight:600;margin-bottom:6px;">共 {{ rejectTargets.length }} 条医嘱将作废(仅新开态可驳), 请填写驳回原因</div>',
      '    <el-input type="textarea" v-model="rejectReason" :rows="3" placeholder="如: 医嘱内容有误 / 与长期医嘱重复 / 剂量超限等"></el-input>',
      '    <template #footer>',
      '      <el-button @click="rejectVisible = false">取消</el-button>',
      '      <el-button type="danger" :loading="rejecting" @click="doReject">确认驳回</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 3. 医嘱执行(当天执行计划 按时间段分组 / 批量执行 / 标记未执行) ================= */
  HIS.views.InpOrderExecPanel = {
    name: 'InpOrderExecPanel',
    props: { wardId: { type: [String, Number], default: null } },
    components: { 'ns-icon': NsIcon },
    data: function () {
      return {
        loading: false, executing: false,
        date: todayStr(), list: [], total: 0, page: 1, size: 200,
        sel: {},
        cancelVisible: false, cancelRow: null, cancelRemark: '', cancelling: false,
        /* T36 双人核对: 待重发批次 + 需核对医嘱(去重)展示行 + 核对凭据 */
        checkVisible: false, checkIds: [], checkRows: [], checkForm: { userId: '', password: '' }, checking: false
      };
    },
    computed: {
      /* 按计划时间 HH:mm 分组(08:00/12:00/16:00/18:00/22:00 等), 时间升序 */
      groups: function () {
        var map = {};
        var order = [];
        (this.list || []).forEach(function (r) {
          var t = r.planTime ? String(r.planTime).substring(11, 16) : '--:--';
          if (!map[t]) { map[t] = { time: t, rows: [], pending: 0 }; order.push(t); }
          map[t].rows.push(r);
          if (r.execStatus === 1) { map[t].pending++; }
        });
        order.sort();
        return order.map(function (t) { return map[t]; });
      },
      /* 执行ID数组: 键即后端下发的19位雪花ID字符串, 原样传递(禁止 Number 化丢精度) */
      selectedIds: function () {
        var s = this.sel;
        return Object.keys(s).filter(function (k) { return s[k]; });
      },
      pendingCount: function () {
        return (this.list || []).filter(function (r) { return r.execStatus === 1; }).length;
      }
    },
    watch: {
      wardId: function () { this.page = 1; this.load(); }
    },
    created: function () { this.load(); },
    methods: {
      fmtTime: fmtTime,
      isOverdue: isOverdue,
      /* 雪花ID键规范化(供模板 sel[idKey(row.id)] 使用, 与 HIS.idKey 同源) */
      idKey: HIS.idKey,
      execStatusLabel: function (v) { return v == null ? '-' : (EXEC_STATUS[v] || v); },
      execStatusType: function (v) { return EXEC_STATUS_TYPE[v] || 'info'; },
      load: function () {
        var vm = this;
        if (!vm.wardId) { vm.list = []; vm.total = 0; vm.sel = {}; return; }
        vm.loading = true;
        vm.sel = {};
        HIS.get('/api/his/inp/order-exec/plan?wardId=' + HIS.idParam(vm.wardId) + '&date=' + vm.date + '&page=' + vm.page + '&size=' + vm.size)
          .then(function (d) {
            vm.list = (d && d.records) || [];
            vm.total = (d && d.total) || 0;
          }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onDateChange: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      toggleSel: function (row) {
        var m = Object.assign({}, this.sel);
        var k = HIS.idKey(row.id);
        if (m[k]) { delete m[k]; } else { m[k] = true; }
        this.sel = m;
      },
      selectAllPending: function () {
        var m = {};
        (this.list || []).forEach(function (r) { if (r.execStatus === 1) { m[HIS.idKey(r.id)] = true; } });
        this.sel = m;
      },
      clearSel: function () { this.sel = {}; },
      /* T36: verify 携带双人核对凭据时以 query 重发(执行接口 body 为裸数组);
       * 后端遇高危医嘱且无凭据返回 {needDoubleCheck:true, execIds} → 前端弹核对框重发;
       * 皮试拦截(无记录/观察中/阳性/可疑)走 BizException, 用阻断式弹窗明示 */
      executeIds: function (ids, verify) {
        var vm = this;
        if (!ids || !ids.length) { return; }
        vm.executing = true;
        var url = '/api/his/inp/order-exec/execute';
        if (verify && verify.userId) {
          url += '?verifyNurseId=' + encodeURIComponent(verify.userId)
            + '&verifyNursePassword=' + encodeURIComponent(verify.password || '');
        }
        return HIS.post(url, ids).then(function (d) {
          if (d && d.needDoubleCheck) {
            vm.openDoubleCheck(ids, d.execIds || []);
            return;
          }
          var executed = (d && d.executed) || 0;
          var skipped = (d && d.skipped) || [];
          var msg = '执行完成: ' + executed + ' 条' + (d && d.doubleCheck ? '(双人核对已留痕)' : '');
          if (skipped.length) {
            ElementPlus.ElMessage.warning(msg + '，' + skipped.length + ' 条状态已变更被跳过');
          } else {
            HIS.notifySuccess(msg);
          }
          vm.checkVisible = false;
          vm.checkForm = { userId: '', password: '' };
          vm.load();
          vm.$emit('changed');
        }).catch(function (err) {
          var msg = (err && err.message) || String(err);
          if (msg.indexOf('皮试') >= 0) {
            ElementPlus.ElMessageBox.alert(msg, '皮试校验未通过', { confirmButtonText: '知道了', type: 'warning' });
          } else {
            HIS.notifyError(err);
          }
        }).finally(function () { vm.executing = false; });
      },
      /* ---- T36 皮试/高危标识辅助 ---- */
      needSkinTest: function (row) { return !!row && row.drugId != null && row.skinTestFlag === 1; },
      skinTestTag: function (row) { return SKIN_TEST_RESULT[row.skinTestResult] || { label: '待做', type: 'warning' }; },
      isHighAlert: function (row) { return !!row && (row.highAlertFlag === 1 || row.doubleCheckFlag === 1); },
      /* 行底色: 皮试阳性(红) > 高危/需核对(浅橙); 不依赖 this(el-table 回调调用方不确定) */
      execRowClass: function (p) {
        var r = p && p.row;
        if (!r) { return ''; }
        if (r.drugId != null && r.skinTestFlag === 1 && r.skinTestResult === 2) { return 'inp-exec-row is-skin-positive'; }
        if (r.highAlertFlag === 1 || r.doubleCheckFlag === 1) { return 'inp-exec-row is-high-alert'; }
        return '';
      },
      /* 双人核对弹框: batchIds=整批重发(含无需核对的行), needIds=需核对行(按医嘱去重展示) */
      openDoubleCheck: function (batchIds, needIds) {
        var vm = this;
        var need = {};
        (needIds || []).forEach(function (v) { need[HIS.idKey(v)] = true; });
        var seen = {};
        var rows = [];
        (vm.list || []).forEach(function (r) {
          if (!need[HIS.idKey(r.id)] || seen[HIS.idKey(r.orderId)]) { return; }
          seen[HIS.idKey(r.orderId)] = true;
          rows.push(r);
        });
        vm.checkIds = (batchIds || []).slice();
        vm.checkRows = rows;
        vm.checkForm = { userId: '', password: '' };
        vm.checkVisible = true;
      },
      /* 确认核对: 重发执行请求携带 query 凭据; 密码错误时后端 403, 弹框保持打开可重输 */
      doDoubleCheck: function () {
        var vm = this;
        var userId = (vm.checkForm.userId || '').trim();
        var pwd = vm.checkForm.password || '';
        if (!/^\d+$/.test(userId)) { ElementPlus.ElMessage.warning('请输入核对护士的数字工号(用户ID)'); return; }
        if (!pwd) { ElementPlus.ElMessage.warning('请输入核对护士的登录密码'); return; }
        vm.checking = true;
        var p = vm.executeIds(vm.checkIds, { userId: userId, password: pwd });
        if (p && p.finally) { p.finally(function () { vm.checking = false; }); } else { vm.checking = false; }
      },
      openCancel: function (row) {
        this.cancelRow = row;
        this.cancelRemark = '';
        this.cancelVisible = true;
      },
      doCancel: function () {
        var vm = this;
        var remark = (vm.cancelRemark || '').trim();
        if (!remark) { ElementPlus.ElMessage.warning('请填写未执行原因'); return; }
        vm.cancelling = true;
        HIS.post('/api/his/inp/order-exec/cancel-exec?execId=' + HIS.idParam(vm.cancelRow.id) + '&remark=' + encodeURIComponent(remark))
          .then(function () {
            HIS.notifySuccess('已标记未执行');
            vm.cancelVisible = false;
            vm.load();
            vm.$emit('changed');
          }).catch(HIS.notifyError).finally(function () { vm.cancelling = false; });
      }
    },
    template: [
      '<div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">执行日期</span>',
      '    <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:150px;" @change="onDateChange"></el-date-picker>',
      '    <el-button size="small" @click="selectAllPending">全选待执行</el-button>',
      '    <el-button size="small" @click="clearSel">清空选择</el-button>',
      '    <el-button type="primary" size="small" :disabled="!selectedIds.length" :loading="executing" @click="executeIds(selectedIds)">批量执行({{ selectedIds.length }})</el-button>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-3);font-size:12px;">共 {{ total }} 条计划 · 待执行 {{ pendingCount }} 条</span>',
      '    <el-button size="small" @click="load">刷新</el-button>',
      '  </div>',
      '  <div v-for="g in groups" :key="g.time">',
      '    <div class="inp-grp"><span style="font-variant-numeric:tabular-nums;">{{ g.time }}</span>',
      '      <el-tag size="small" type="info">{{ g.rows.length }} 条</el-tag>',
      '      <el-tag v-if="g.pending" size="small" type="warning">待执行 {{ g.pending }}</el-tag>',
      '    </div>',
      '    <el-table :data="g.rows" border size="small" :row-class-name="execRowClass">',
      '      <el-table-column label="选择" width="52" align="center">',
      '        <template #default="s"><el-checkbox :model-value="!!sel[idKey(s.row.id)]" :disabled="s.row.execStatus !== 1" @change="toggleSel(s.row)"></el-checkbox></template>',
      '      </el-table-column>',
      '      <el-table-column label="计划时间" width="130" align="center">',
      '        <template #default="s"><span :class="{ \'inp-ovd\': s.row.execStatus === 1 && isOverdue(s.row.planTime) }">{{ s.row.planTime || \'-\' }}</span></template>',
      '      </el-table-column>',
      '      <el-table-column label="床位号" width="80" align="center">',
      '        <template #default="s">{{ s.row.bedNo || \'-\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="患者姓名" width="110">',
      '        <template #default="s">{{ s.row.patientName || \'-\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="医嘱内容" min-width="240" show-overflow-tooltip>',
      '        <template #default="s">{{ s.row.orderContent || \'-\' }}<span style="color:var(--yb-ink-3);"> {{ s.row.spec || \'\' }}{{ s.row.dosage ? \' · \' + s.row.dosage + (s.row.dosageUnit || \'\') : \'\' }}{{ s.row.freqCode ? \' · \' + s.row.freqCode : \'\' }}</span><el-tag v-if="isHighAlert(s.row)" class="inp-dc-tag" size="small" type="danger" effect="dark">⚠双人核对</el-tag></template>',
      '      </el-table-column>',
      '      <el-table-column label="皮试" width="80" align="center">',
      '        <template #default="s"><el-tag v-if="needSkinTest(s.row)" size="small" :type="skinTestTag(s.row).type">{{ skinTestTag(s.row).label }}</el-tag><span v-else style="color:var(--yb-ink-4);">-</span></template>',
      '      </el-table-column>',
      '      <el-table-column label="执行状态" width="90" align="center">',
      '        <template #default="s"><el-tag size="small" :type="execStatusType(s.row.execStatus)">{{ execStatusLabel(s.row.execStatus) }}</el-tag></template>',
      '      </el-table-column>',
      '      <el-table-column label="执行护士" width="100" align="center">',
      '        <template #default="s">{{ s.row.execNurseName || \'-\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="操作" width="160" align="center">',
      '        <template #default="s">',
      '          <template v-if="s.row.execStatus === 1">',
      '            <el-tooltip content="确认执行" placement="top">',
      '              <span class="ns-ic is-success" @click="executeIds([s.row.id])"><ns-icon name="select"></ns-icon></span>',
      '            </el-tooltip>',
      '            <el-tooltip content="标记未执行" placement="top">',
      '              <span class="ns-ic is-danger" @click="openCancel(s.row)"><ns-icon name="circle-close"></ns-icon></span>',
      '            </el-tooltip>',
      '          </template>',
      '          <span v-else-if="s.row.execStatus === 3" style="color:var(--yb-ink-3);font-size:12px;">{{ s.row.execRemark || \'未执行\' }}</span>',
      '          <span v-else style="color:var(--yb-ink-3);font-size:12px;">{{ s.row.execTime || \'\' }}</span>',
      '        </template>',
      '      </el-table-column>',
      '    </el-table>',
      '  </div>',
      '  <el-empty v-if="!loading && !list.length" description="该日期暂无执行计划(长期医嘱审核通过后自动生成)" :image-size="60"></el-empty>',
      '  <el-pagination v-if="total > size" style="margin-top:10px;justify-content:flex-end;" background small layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      /* 标记未执行弹窗 */
      '  <el-dialog v-model="cancelVisible" title="标记未执行" width="460px">',
      '    <div v-if="cancelRow" style="font-weight:600;margin-bottom:6px;">{{ cancelRow.bedNo || \'\' }} {{ cancelRow.patientName || \'\' }} · {{ cancelRow.planTime || \'\' }} · {{ cancelRow.orderContent || \'\' }}</div>',
      '    <el-input type="textarea" v-model="cancelRemark" :rows="3" placeholder="未执行原因(必填), 如: 患者外出 / 拒绝执行 / 医嘱已停等"></el-input>',
      '    <template #footer>',
      '      <el-button @click="cancelVisible = false">返回</el-button>',
      '      <el-button type="warning" :loading="cancelling" @click="doCancel">确认标记</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* T36 双人核对弹窗 */
      '  <el-dialog v-model="checkVisible" title="双人核对" width="560px" :close-on-click-modal="false">',
      '    <div style="margin-bottom:8px;color:var(--yb-ink-2);font-size:13px;">以下医嘱含<b style="color:var(--yb-danger);">高警示/需皮试</b>药品，须由另一名护士核对后方可执行：</div>',
      '    <el-table :data="checkRows" border size="small" max-height="220">',
      '      <el-table-column label="床号" width="70" align="center">',
      '        <template #default="s">{{ s.bedNo || \'-\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="患者" width="90">',
      '        <template #default="s">{{ s.patientName || \'-\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="药品/医嘱" min-width="150" show-overflow-tooltip>',
      '        <template #default="s">{{ s.orderContent || \'-\' }}{{ s.spec ? \' \' + s.spec : \'\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="剂量" width="90" align="center">',
      '        <template #default="s">{{ s.dosage != null ? s.dosage + (s.dosageUnit || \'\') : \'-\' }}</template>',
      '      </el-table-column>',
      '      <el-table-column label="用法" width="70" align="center">',
      '        <template #default="s">{{ s.usageCode || \'-\' }}</template>',
      '      </el-table-column>',
      '    </el-table>',
      '    <el-form label-width="100px" style="margin-top:12px;" @submit.prevent>',
      '      <el-form-item label="核对护士工号">',
      '        <el-input v-model="checkForm.userId" placeholder="核对护士的用户ID(数字)" style="width:240px;" clearable></el-input>',
      '      </el-form-item>',
      '      <el-form-item label="核对密码">',
      '        <el-input v-model="checkForm.password" type="password" show-password placeholder="核对护士的登录密码" style="width:240px;" @keyup.enter="doDoubleCheck"></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <div style="color:var(--yb-ink-3);font-size:12px;">核对护士不能是执行护士本人；密码验证通过后本批执行将记录核对留痕。</div>',
      '    <template #footer>',
      '      <el-button @click="checkVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="checking" @click="doDoubleCheck">确认核对</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ===== 护理记录 / 体温单辅助工具 ===== */
  var NURSING_EDIT_OPTIONS = [2, 3, 4, 5].map(function (v) { return { value: v, label: NURSING_TYPE[v] }; });
  /* 解析体温单 content JSON(兼容旧键 blood_pressure "120/80") */
  function tempFields(content) {
    var o = parseJson(content) || {};
    var sys = o.systolicBp, dia = o.diastolicBp;
    if ((sys == null || dia == null) && o.blood_pressure) {
      var m = String(o.blood_pressure).split('/');
      if (sys == null && m.length > 1) { sys = Number(m[0]); }
      if (dia == null && m.length > 1) { dia = Number(m[1]); }
    }
    return {
      time: o.time || '', temperature: o.temperature, pulse: o.pulse,
      respiration: o.respiration, systolicBp: sys, diastolicBp: dia
    };
  }
  /* 体温记录时间(content.time 优先, 回退 recordTime), 截断到分钟 */
  function tempTimeOf(row) {
    var f = tempFields(row ? row.content : null);
    var t = String(f.time || (row && row.recordTime) || '');
    return t.replace('T', ' ').substring(0, 16);
  }

  /* ================= 4. 护理记录(体温单折线图 · 评估/计划/措施/总结) ================= */
  HIS.views.InpNursingRecord = {
    name: 'InpNursingRecord',
    props: {
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    components: { 'ns-icon': NsIcon },
    data: function () {
      return {
        loading: false, saving: false,
        filterType: 0,
        records: [], temps: [], nrsTrend: [], chart: null,
        editOptions: NURSING_EDIT_OPTIONS,
        editVisible: false, editId: null, editType: 2, editText: '',
        tempVisible: false, tempId: null,
        tempForm: { time: '', temperature: 36.5, pulse: 80, respiration: 20, systolicBp: 120, diastolicBp: 80 }
      };
    },
    computed: {
      /* 体温记录按内容时间升序(与折线图同序, 早->晚) */
      tempRows: function () {
        var rows = (this.records || []).slice();
        rows.sort(function (a, b) {
          var x = tempTimeOf(a), y = tempTimeOf(b);
          return x < y ? -1 : (x > y ? 1 : 0);
        });
        return rows;
      }
    },
    watch: {
      visitId: function () {
        this.filterType = 0;
        this.disposeChart();
        this.load();
      }
    },
    created: function () {
      window.addEventListener('resize', this.onResize);
      this.load();
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.onResize);
      this.disposeChart();
    },
    methods: {
      fmtTime: fmtTime,
      datePart: datePart,
      nursingSummary: nursingSummary,
      tempFields: tempFields,
      tempTimeOf: tempTimeOf,
      typeLabel: function (v) { return v == null ? '-' : (NURSING_TYPE[v] || v); },
      typeTag: function (v) { return NURSING_TYPE_TAG[v] || 'info'; },
      val: function (v) { return v == null || v === '' ? '-' : v; },
      bpText: function (row) {
        var f = tempFields(row.content);
        if (f.systolicBp == null && f.diastolicBp == null) { return '-'; }
        return (f.systolicBp == null ? '-' : f.systolicBp) + '/' + (f.diastolicBp == null ? '-' : f.diastolicBp);
      },
      load: function () {
        var vm = this;
        if (!vm.visitId) { vm.records = []; vm.temps = []; vm.nrsTrend = []; vm.disposeChart(); return; }
        vm.loading = true;
        var url = '/api/his/inp/nursing/list/' + HIS.idParam(vm.visitId) + (vm.filterType ? '?recordType=' + vm.filterType : '');
        HIS.get(url).then(function (rows) {
          vm.records = rows || [];
          if (vm.filterType === 1) { return vm.loadTemps(); }
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadTemps: function () {
        var vm = this;
        /* 体温数据 + NRS疼痛趋势并行加载(趋势接口失败静默降级为空, 不影响体温曲线) */
        return Promise.all([
          HIS.get('/api/his/inp/nursing/temperature/' + HIS.idParam(vm.visitId)).catch(function () { return []; }),
          HIS.get('/api/his/inp/nursing-scale/trend?visitId=' + HIS.idParam(vm.visitId) + '&scaleCode=nrs').catch(function () { return []; })
        ]).then(function (rs) {
          vm.temps = rs[0] || [];
          vm.nrsTrend = rs[1] || [];
          vm.$nextTick(vm.renderChart);
        });
      },
      onFilter: function () {
        this.load();
        if (this.filterType === 1) { this.$nextTick(this.renderChart); }
        else { this.disposeChart(); }
      },
      disposeChart: function () {
        if (this.chart) { this.chart.dispose(); this.chart = null; }
      },
      onResize: function () { if (this.chart) { this.chart.resize(); } },
      renderChart: function () {
        var vm = this;
        var el = vm.$refs.tempChart;
        if (!el || !el.clientWidth) { return; }
        if (!vm.chart) { vm.chart = echarts.init(el, 'yb'); }
        /* 合并时间轴: 体温测量点与 NRS 评估点按 'MM-dd HH:mm' 取并集升序, 保证两条曲线时间对齐 */
        var keyOf = function (ts) {
          var t = String(ts || '').replace('T', ' ');
          return t.length >= 16 ? t.substring(5, 16) : t;
        };
        var tMap = {}, pMap = {}, nMap = {}, xs = [];
        (vm.temps || []).forEach(function (t) {
          var k = keyOf(t.time || t.recordTime);
          if (tMap[k] === undefined) { xs.push(k); }
          tMap[k] = t.temperature == null ? null : t.temperature;
          pMap[k] = t.pulse == null ? null : t.pulse;
        });
        var hasNrs = false;
        (vm.nrsTrend || []).forEach(function (n) {
          var k = keyOf(n.time);
          if (tMap[k] === undefined && nMap[k] === undefined) { xs.push(k); }
          nMap[k] = n.score == null ? null : Number(n.score);
          hasNrs = true;
        });
        xs.sort();
        var tData = [], pData = [], nData = [];
        xs.forEach(function (k) {
          tData.push(tMap[k] === undefined ? null : tMap[k]);
          pData.push(pMap[k] === undefined ? null : pMap[k]);
          nData.push(nMap[k] === undefined ? null : nMap[k]);
        });
        var legend = ['体温(℃)', '脉搏(次/分)'];
        var series = [
          { name: '体温(℃)', type: 'line', smooth: true, symbolSize: 7, data: tData, itemStyle: { color: HIS.theme.danger } },
          { name: '脉搏(次/分)', type: 'line', smooth: true, symbolSize: 6, data: pData, itemStyle: { color: HIS.theme.link }, lineStyle: { type: 'dashed' } }
        ];
        var yAxis = [
          { type: 'value', name: '体温', min: 36, max: 42, axisLabel: { formatter: '{value}℃' } },
          { type: 'value', name: '脉搏', min: 40, max: 160 }
        ];
        /* NRS 疼痛(0~10)与体温(36~42)量纲差异大, 叠加时用第三根 Y 轴(右侧偏置), 阶梯线展示评估点间持续水平 */
        if (hasNrs) {
          legend.push('NRS疼痛(分)');
          yAxis.push({ type: 'value', name: '疼痛', min: 0, max: 10, position: 'right', offset: 44, splitLine: { show: false } });
          series.push({ name: 'NRS疼痛(分)', type: 'line', yAxisIndex: 2, step: 'end', symbolSize: 6, data: nData, itemStyle: { color: HIS.theme.purple }, lineStyle: { type: 'dotted', width: 2 } });
        }
        vm.chart.setOption({
          tooltip: { trigger: 'axis' },
          legend: { top: 0, data: legend },
          grid: { left: 46, right: hasNrs ? 96 : 52, top: 36, bottom: 26 },
          xAxis: { type: 'category', boundaryGap: false, data: xs },
          yAxis: yAxis,
          series: series
        }, true);
        vm.chart.resize();
      },
      openCreate: function () {
        var vm = this;
        if (vm.filterType === 1) {
          vm.tempId = null;
          vm.tempForm = { time: nowStr().substring(0, 16), temperature: 36.5, pulse: 80, respiration: 20, systolicBp: 120, diastolicBp: 80 };
          vm.tempVisible = true;
        } else {
          vm.editId = null;
          vm.editType = vm.filterType || 2;
          vm.editText = '';
          vm.editVisible = true;
        }
      },
      openEdit: function (row) {
        var vm = this;
        /* 量表评估记录(recordType=2 且 content 含 score)由量表工作台生成, 禁止文本编辑覆盖结构化结果, 引导去护理评估页签 */
        if (row.recordType === 2) {
          var ao = parseJson(row.content);
          if (ao && ao.score != null) {
            ElementPlus.ElMessage.info('该记录为量表评估结果, 请在「护理评估」页签查看或重新评估');
            vm.$emit('go-assess');
            return;
          }
        }
        if (row.recordType === 1) {
          var f = tempFields(row.content);
          vm.tempId = row.id;
          vm.tempForm = {
            time: (f.time || fmtTime(row.recordTime)).replace('T', ' ').substring(0, 16),
            temperature: f.temperature == null ? 36.5 : f.temperature,
            pulse: f.pulse == null ? 80 : f.pulse,
            respiration: f.respiration == null ? 20 : f.respiration,
            systolicBp: f.systolicBp == null ? 120 : f.systolicBp,
            diastolicBp: f.diastolicBp == null ? 80 : f.diastolicBp
          };
          vm.tempVisible = true;
        } else {
          vm.editId = row.id;
          vm.editType = row.recordType;
          vm.editText = textOf(row.content);
          vm.editVisible = true;
        }
      },
      saveTemp: function () {
        var vm = this;
        var f = vm.tempForm;
        if (f.temperature == null || f.temperature < 30 || f.temperature > 45) {
          ElementPlus.ElMessage.warning('请填写有效体温(30~45℃)');
          return;
        }
        var content = JSON.stringify({
          time: f.time || nowStr().substring(0, 16), temperature: f.temperature, pulse: f.pulse,
          respiration: f.respiration, systolicBp: f.systolicBp, diastolicBp: f.diastolicBp
        });
        vm.saving = true;
        var req = vm.tempId
          ? HIS.put('/api/his/inp/nursing/' + HIS.idParam(vm.tempId), { recordType: 1, content: content })
          : HIS.post('/api/his/inp/nursing/', { inpVisitId: HIS.id(vm.visitId), recordType: 1, content: content });
        req.then(function () {
          HIS.notifySuccess(vm.tempId ? '体温记录已更新' : '体温记录已保存');
          vm.tempVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      saveText: function () {
        var vm = this;
        var text = (vm.editText || '').trim();
        if (!text) { ElementPlus.ElMessage.warning('请填写记录内容'); return; }
        var content = JSON.stringify({ text: text });
        vm.saving = true;
        var req = vm.editId
          ? HIS.put('/api/his/inp/nursing/' + HIS.idParam(vm.editId), { recordType: vm.editType, content: content })
          : HIS.post('/api/his/inp/nursing/', { inpVisitId: HIS.id(vm.visitId), recordType: vm.editType, content: content });
        req.then(function () {
          HIS.notifySuccess(vm.editId ? '记录已更新' : '记录已保存');
          vm.editVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      remove: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除该条护理记录？删除后不可恢复。', '删除确认', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/inp/nursing/' + HIS.idParam(row.id));
        }).then(function () {
          HIS.notifySuccess('已删除');
          vm.load();
        }).catch(function (e) {
          if (e === 'cancel' || e === 'close') { return; }
          HIS.notifyError(e);
        });
      }
    },
    template: [
      '<div>',
      '  <el-empty v-if="!visitId" description="请先在左侧患者列表中选择患者" :image-size="70"></el-empty>',
      '  <template v-else>',
      '    <div class="toolbar" style="margin-bottom:10px;">',
      '      <span style="font-weight:600;color:var(--yb-ink-1);margin-right:10px;">{{ patient && patient.patientName ? patient.patientName : \'\' }} · 护理记录</span>',
      '      <el-radio-group v-model="filterType" size="small" @change="onFilter">',
      '        <el-radio-button :label="0">全部</el-radio-button>',
      '        <el-radio-button :label="1">体温单</el-radio-button>',
      '        <el-radio-button :label="2">护理评估</el-radio-button>',
      '        <el-radio-button :label="3">护理计划</el-radio-button>',
      '        <el-radio-button :label="4">护理措施</el-radio-button>',
      '        <el-radio-button :label="5">护理总结</el-radio-button>',
      '      </el-radio-group>',
      '      <el-button size="small" type="primary" plain style="margin-left:10px;" title="量化评估请到「护理评估」量表工作台" @click="$emit(\'go-assess\')">评估</el-button>',
      '      <span style="flex:1;"></span>',
      '      <el-button type="primary" size="small" @click="openCreate">{{ filterType === 1 ? \'新增体温记录\' : \'新建记录\' }}</el-button>',
      '      <el-button size="small" @click="load">刷新</el-button>',
      '    </div>',
      '    <template v-if="filterType === 1">',
      '      <div class="inp-panel">',
      '        <h4>体温脉搏曲线(体温 {{ temps.length }} 次{{ nrsTrend.length ? \' · NRS疼痛 \' + nrsTrend.length + \' 次\' : \'\' }})</h4>',
      '        <div v-show="temps.length || nrsTrend.length" ref="tempChart" style="height:260px;"></div>',
      '        <el-empty v-if="!temps.length && !nrsTrend.length" description="暂无体温/NRS疼痛记录" :image-size="60"></el-empty>',
      '      </div>',
      '      <el-table :data="tempRows" v-loading="loading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column label="测量时间" width="150" align="center">',
      '          <template #default="s">{{ tempTimeOf(s.row) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="体温(℃)" width="90" align="center">',
      '          <template #default="s">{{ val(tempFields(s.row.content).temperature) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="脉搏(次/分)" width="110" align="center">',
      '          <template #default="s">{{ val(tempFields(s.row.content).pulse) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="呼吸(次/分)" width="110" align="center">',
      '          <template #default="s">{{ val(tempFields(s.row.content).respiration) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="血压(mmHg)" width="110" align="center">',
      '          <template #default="s">{{ bpText(s.row) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="记录护士" width="100" align="center">',
      '          <template #default="s">{{ s.row.createBy || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="操作" width="88" align="center">',
      '          <template #default="s">',
      '            <el-tooltip content="编辑" placement="top">',
      '              <span class="ns-ic is-primary" @click="openEdit(s.row)"><ns-icon name="edit"></ns-icon></span>',
      '            </el-tooltip>',
      '            <el-tooltip content="删除" placement="top">',
      '              <span class="ns-ic is-danger" @click="remove(s.row)"><ns-icon name="delete"></ns-icon></span>',
      '            </el-tooltip>',
      '          </template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!loading && temps.length && !tempRows.length" description="暂无体温记录, 点击右上角新增" :image-size="60"></el-empty>',
      '    </template>',
      '    <template v-else>',
      '      <el-table :data="records" v-loading="loading" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column label="记录时间" width="150" align="center">',
      '          <template #default="s">{{ s.row.recordTime || datePart(s.row.createTime) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="类型" width="100" align="center">',
      '          <template #default="s"><el-tag size="small" :type="typeTag(s.row.recordType)">{{ typeLabel(s.row.recordType) }}</el-tag></template>',
      '        </el-table-column>',
      '        <el-table-column label="内容" min-width="300" show-overflow-tooltip>',
      '          <template #default="s">{{ nursingSummary(s.row) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="护士" width="100" align="center">',
      '          <template #default="s">{{ s.row.createBy || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="操作" width="88" align="center">',
      '          <template #default="s">',
      '            <el-tooltip content="编辑" placement="top">',
      '              <span class="ns-ic is-primary" @click="openEdit(s.row)"><ns-icon name="edit"></ns-icon></span>',
      '            </el-tooltip>',
      '            <el-tooltip content="删除" placement="top">',
      '              <span class="ns-ic is-danger" @click="remove(s.row)"><ns-icon name="delete"></ns-icon></span>',
      '            </el-tooltip>',
      '          </template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!loading && !records.length" description="暂无护理记录" :image-size="60"></el-empty>',
      '    </template>',
      '  </template>',
      '  <el-dialog v-model="tempVisible" :title="tempId ? \'编辑体温记录\' : \'新增体温记录\'" width="520px">',
      '    <el-form label-width="96px">',
      '      <el-form-item label="测量时间">',
      '        <el-date-picker v-model="tempForm.time" type="datetime" value-format="YYYY-MM-DD HH:mm" format="YYYY-MM-DD HH:mm" :clearable="false" style="width:200px;"></el-date-picker>',
      '      </el-form-item>',
      '      <el-form-item label="体温(℃)">',
      '        <el-input-number v-model="tempForm.temperature" :min="30" :max="45" :step="0.1" :precision="1"></el-input-number>',
      '        <span style="margin-left:8px;color:var(--yb-ink-4);font-size:12px;">30~45, 步进 0.1</span>',
      '      </el-form-item>',
      '      <el-form-item label="脉搏(次/分)">',
      '        <el-input-number v-model="tempForm.pulse" :min="0" :max="300"></el-input-number>',
      '      </el-form-item>',
      '      <el-form-item label="呼吸(次/分)">',
      '        <el-input-number v-model="tempForm.respiration" :min="0" :max="100"></el-input-number>',
      '      </el-form-item>',
      '      <el-form-item label="血压(mmHg)">',
      '        <el-input-number v-model="tempForm.systolicBp" :min="0" :max="300" controls-position="right" style="width:110px;"></el-input-number>',
      '        <span style="margin:0 6px;">/</span>',
      '        <el-input-number v-model="tempForm.diastolicBp" :min="0" :max="200" controls-position="right" style="width:110px;"></el-input-number>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="tempVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="saveTemp">保存体温记录</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="editVisible" :title="editId ? \'编辑护理记录\' : \'新建护理记录\'" width="520px">',
      '    <el-form label-width="96px">',
      '      <el-form-item label="记录类型">',
      '        <el-select v-model="editType" style="width:200px;">',
      '          <el-option v-for="o in editOptions" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="记录内容">',
      '        <el-input type="textarea" v-model="editText" :rows="6" placeholder="填写护理记录内容..."></el-input>',
      '      </el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="editVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="saveText">保存</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 5. 交接班(当前班次统计 / SBAR结构化交班 / 接班确认 / 历史) ================= */
  HIS.views.InpShiftHandover = {
    name: 'InpShiftHandover',
    props: { wardId: { type: [String, Number], default: null } },
    components: { 'ns-icon': NsIcon },
    data: function () {
      return {
        loading: false, submitting: false, takingOver: false,
        cur: null,
        criticalCount: 0,
        /* T45 SBAR 结构化交班: mode ward 病区交班(自由填写) / patient 按患者交接(选患者自动预填) */
        mode: 'ward',
        sbar: { situation: '', background: '', assessment: '', recommendation: '' },
        patList: [], patTotal: 0, patPage: 1, patSize: 20, patLoading: false, patKeyword: '',
        selVisitId: null, prefilling: false, prefillFor: null,
        viewRow: null, viewVisible: false,
        histRecords: [], histTotal: 0, page: 1, size: 20
      };
    },
    computed: {
      /* 今日当前班次已有交班记录(含已交接; 后端按 病区×日期×班次 防重) */
      todayRec: function () {
        var c = this.cur;
        if (!c) { return null; }
        var rows = this.histRecords || [];
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].shiftDate === c.shiftDate && rows[i].shiftType === c.shiftType) { return rows[i]; }
        }
        return null;
      },
      /* 待接班记录(current.pendingShiftId 指向, 在历史首页匹配) */
      pendingRec: function () {
        var c = this.cur;
        var id = c ? c.pendingShiftId : null;
        if (id == null) { return null; }
        var rows = this.histRecords || [];
        for (var i = 0; i < rows.length; i++) {
          if (HIS.sameId(rows[i].id, id)) { return rows[i]; }
        }
        return null;
      },
      pendingDetail: function () {
        var r = this.pendingRec;
        return r ? (parseJson(r.content) || {}) : null;
      },
      criticalShown: function () {
        var r = this.pendingRec;
        return r ? (r.criticalCount || 0) : '—';
      },
      canHandover: function () { return !!this.wardId && !!this.cur && !this.todayRec; },
      /* SBAR 四段至少填一段才允许发起(结构化交接, 避免空交) */
      sbarFilled: function () {
        var s = this.sbar || {};
        return !!(s.situation || s.background || s.assessment || s.recommendation);
      }
    },
    watch: {
      wardId: function () {
        this.page = 1;
        this.patPage = 1;
        this.selVisitId = null;
        this.load();
        if (this.mode === 'patient') { this.loadPatients(); }
      },
      /* 切换到按患者交接时按需加载患者名单 */
      mode: function (m) {
        if (m === 'patient' && !this.patList.length) { this.loadPatients(); }
      }
    },
    created: function () { this.load(); },
    methods: {
      fmtTime: fmtTime,
      shiftLabel: function (v) { return v == null ? '-' : (SHIFT_TYPE[v] || v); },
      statusLabel: function (v) { return v == null ? '-' : (SHIFT_STATUS[v] || v); },
      statusType: function (v) { return SHIFT_STATUS_TYPE[v] || 'info'; },
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      patSeqNo: function (i) { return (this.patPage - 1) * this.patSize + i + 1; },
      detailOf: function (row) {
        var o = parseJson(row ? row.content : null) || {};
        return o.detail || '-';
      },
      /* SBAR 四段(新记录优先列字段, 旧记录回退 content JSON 的 sbar 键) */
      sbarOf: function (row) {
        var o = parseJson(row ? row.content : null) || {};
        var sb = o.sbar || {};
        return {
          s: row && row.sbarSituation != null && row.sbarSituation !== '' ? row.sbarSituation : (sb.situation || ''),
          b: row && row.sbarBackground != null && row.sbarBackground !== '' ? row.sbarBackground : (sb.background || ''),
          a: row && row.sbarAssessment != null && row.sbarAssessment !== '' ? row.sbarAssessment : (sb.assessment || ''),
          r: row && row.sbarRecommendation != null && row.sbarRecommendation !== '' ? row.sbarRecommendation : (sb.recommendation || '')
        };
      },
      hasSbar: function (row) {
        var s = this.sbarOf(row);
        return !!(s.s || s.b || s.a || s.r);
      },
      /* 历史列表交班详情摘要: 有 SBAR 取 S 段首行, 否则旧 detail */
      detailSummary: function (row) {
        if (this.hasSbar(row)) {
          var s = String(this.sbarOf(row).s || '').split('\n')[0];
          return s || 'SBAR 交班(见详情)';
        }
        return this.detailOf(row);
      },
      load: function () {
        var vm = this;
        if (!vm.wardId) { vm.cur = null; vm.histRecords = []; vm.histTotal = 0; return; }
        vm.loading = true;
        Promise.all([
          HIS.get('/api/his/inp/shift/current?wardId=' + HIS.idParam(vm.wardId)),
          HIS.get('/api/his/inp/shift/history?wardId=' + HIS.idParam(vm.wardId) + '&page=' + vm.page + '&size=' + vm.size)
        ]).then(function (rs) {
          vm.cur = rs[0] || null;
          var h = rs[1] || {};
          vm.histRecords = h.records || [];
          vm.histTotal = h.total || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      onPage: function (p) { this.page = p; this.load(); },
      /* ---- 按患者交接: 病区在院患者名单(分页+关键字) ---- */
      loadPatients: function () {
        var vm = this;
        if (!vm.wardId) { vm.patList = []; vm.patTotal = 0; return; }
        vm.patLoading = true;
        var params = new URLSearchParams({ wardId: vm.wardId, page: vm.patPage, size: vm.patSize });
        if (vm.patKeyword) { params.append('keyword', vm.patKeyword); }
        HIS.get('/api/his/inp/nurse/patients?' + params.toString())
          .then(function (d) {
            vm.patList = (d && d.records) || [];
            vm.patTotal = (d && d.total) || 0;
          }).catch(HIS.notifyError).finally(function () { vm.patLoading = false; });
      },
      onPatSearch: function () { this.patPage = 1; this.loadPatients(); },
      onPatPage: function (p) { this.patPage = p; this.loadPatients(); },
      /* 选中患者 → 调 /patient-detail 预填 SBAR 四段(可继续编辑) */
      pickPatient: function (row) {
        var vm = this;
        if (!row || !row.id) { return; }
        vm.selVisitId = HIS.id(row.id);
        vm.prefilling = true;
        vm.prefillFor = HIS.id(row.id);
        HIS.get('/api/his/inp/shift/patient-detail/' + HIS.idParam(row.id)).then(function (d) {
          if (!HIS.sameId(vm.prefillFor, row.id)) { return; }
          var o = d || {};
          vm.sbar = {
            situation: o.situation || '',
            background: o.background || '',
            assessment: o.assessment || '',
            recommendation: o.recommendation || ''
          };
          HIS.notifySuccess('已按 ' + (o.patientName || '患者') + (o.bedNo ? '(' + o.bedNo + '床)' : '') + ' 预填 SBAR, 请核对后补充');
        }).catch(HIS.notifyError).finally(function () {
          if (HIS.sameId(vm.prefillFor, row.id)) { vm.prefilling = false; }
        });
      },
      reprefill: function () {
        if (this.selVisitId == null) { return; }
        var rows = this.patList || [];
        for (var i = 0; i < rows.length; i++) {
          if (HIS.sameId(rows[i].id, this.selVisitId)) { this.pickPatient(rows[i]); return; }
        }
        this.pickPatient({ id: this.selVisitId });
      },
      clearSbar: function () {
        this.sbar = { situation: '', background: '', assessment: '', recommendation: '' };
      },
      /* ---- 发起交班: SBAR 四段落库(列+content 双留痕), content.detail 拼接四段兼容旧展示 ---- */
      submit: function () {
        var vm = this;
        if (!vm.canHandover) { return; }
        if (!vm.sbarFilled) {
          ElementPlus.ElMessage.warning('请至少填写一段 SBAR 交班内容(情景/背景/评估/建议)');
          return;
        }
        var sb = vm.sbar || {};
        var parts = [];
        if (sb.situation) { parts.push('【S-情景】' + sb.situation); }
        if (sb.background) { parts.push('【B-背景】' + sb.background); }
        if (sb.assessment) { parts.push('【A-评估】' + sb.assessment); }
        if (sb.recommendation) { parts.push('【R-建议】' + sb.recommendation); }
        var content = JSON.stringify({
          criticalCount: vm.criticalCount || 0,
          detail: parts.join('\n'),
          sbar: { situation: sb.situation || '', background: sb.background || '', assessment: sb.assessment || '', recommendation: sb.recommendation || '' }
        });
        vm.submitting = true;
        HIS.post('/api/his/inp/shift/handover', {
          wardId: HIS.id(vm.wardId), shiftType: vm.cur.shiftType, content: content,
          sbarSituation: sb.situation || '',
          sbarBackground: sb.background || '',
          sbarAssessment: sb.assessment || '',
          sbarRecommendation: sb.recommendation || ''
        }).then(function () {
            HIS.notifySuccess('交班已发起, 等待接班护士确认');
            vm.clearSbar();
            vm.criticalCount = 0;
            vm.selVisitId = null;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      /* 接班确认(row 为空时取当前待接班记录; 历史表格行直接传入) */
      takeover: function (row) {
        var vm = this;
        var r = (row && row.id != null) ? row : vm.pendingRec;
        if (!r) { return; }
        ElementPlus.ElMessageBox.confirm('确认接班？将以当前登录护士身份完成接班确认。', '接班确认', {
          type: 'info', confirmButtonText: '确认接班', cancelButtonText: '再想想'
        }).then(function () {
          vm.takingOver = true;
          return HIS.put('/api/his/inp/shift/' + HIS.idParam(r.id) + '/takeover')
            .then(function () {
              HIS.notifySuccess('接班确认完成');
              vm.load();
            }).catch(HIS.notifyError).finally(function () { vm.takingOver = false; });
        }).catch(function () { /* 取消 */ });
      },
      openView: function (row) {
        this.viewRow = row;
        this.viewVisible = true;
      }
    },
    template: [
      '<div v-loading="loading">',
      '  <el-empty v-if="!wardId" description="请先选择病区" :image-size="70"></el-empty>',
      '  <template v-else>',
      '    <div class="inp-panel">',
      '      <h4>当前班次 <span v-if="cur" style="color:var(--yb-ink-3);font-weight:400;font-size:13px;">{{ cur.wardName }}</span></h4>',
      '      <el-row v-if="cur" :gutter="12">',
      '        <el-col :span="4"><div class="inp-stat"><div class="v">{{ cur.shiftTypeName || shiftLabel(cur.shiftType) }}</div><div class="k">当前班次</div></div></el-col>',
      '        <el-col :span="4"><div class="inp-stat"><div class="v">{{ cur.totalPatients }}</div><div class="k">在院总数</div></div></el-col>',
      '        <el-col :span="4"><div class="inp-stat"><div class="v">{{ cur.newAdmit }}</div><div class="k">新入院(本班次)</div></div></el-col>',
      '        <el-col :span="4"><div class="inp-stat"><div class="v">{{ cur.discharged }}</div><div class="k">出院(本班次)</div></div></el-col>',
      '        <el-col :span="4"><div class="inp-stat"><div class="v">{{ criticalShown }}</div><div class="k">危重</div></div></el-col>',
      '        <el-col :span="4"><div class="inp-stat"><div class="v" style="font-size:14px;">{{ (cur.startTime || \'\').substring(11, 16) }} - {{ (cur.endTime || \'\').substring(11, 16) }}</div><div class="k">班次时段</div></div></el-col>',
      '      </el-row>',
      '    </div>',
      '    <div class="inp-panel">',
      '      <h4>交班内容(SBAR 结构化)</h4>',
      '      <div v-if="cur" style="margin-bottom:8px;color:var(--yb-ink-2);">自动统计: 在院 <b>{{ cur.totalPatients }}</b> 人，新入院 <b>{{ cur.newAdmit }}</b> 人，出院 <b>{{ cur.discharged }}</b> 人</div>',
      '      <el-alert v-if="todayRec" style="margin-bottom:8px;" :closable="false" show-icon :type="todayRec.status === 2 ? \'success\' : \'info\'" :title="todayRec.status === 2 ? \'本班次交班已完成接班确认, 无需重复发起\' : \'本班次已发起交班, 等待接班护士确认\'"></el-alert>',
      '      <div style="display:flex;align-items:center;gap:14px;margin-bottom:10px;flex-wrap:wrap;">',
      '        <div><span style="font-weight:600;margin-right:6px;">危重人数</span><el-input-number v-model="criticalCount" :min="0" :max="999" :disabled="!!todayRec"></el-input-number></div>',
      '        <el-radio-group v-model="mode" size="small" :disabled="!!todayRec">',
      '          <el-radio-button label="ward">病区交班</el-radio-button>',
      '          <el-radio-button label="patient">按患者交接</el-radio-button>',
      '        </el-radio-group>',
      '        <span v-if="mode === \'patient\'" style="color:var(--yb-ink-3);font-size:12px;">左侧选择患者后自动预填 SBAR, 预填后可继续编辑</span>',
      '      </div>',
      '      <div class="inp-sbar-grid">',
      '        <div v-if="mode === \'patient\'" class="inp-sbar-pat">',
      '          <div style="display:flex;gap:6px;margin-bottom:6px;">',
      '            <el-input v-model="patKeyword" placeholder="姓名 / 住院号" clearable size="small" @keyup.enter="onPatSearch" @clear="onPatSearch"></el-input>',
      '            <el-button size="small" @click="onPatSearch">搜索</el-button>',
      '          </div>',
      '          <el-table :data="patList" v-loading="patLoading" border size="small" highlight-current-row max-height="320" @row-click="pickPatient">',
      '            <el-table-column type="index" label="#" width="42" align="center" :index="patSeqNo"></el-table-column>',
      '            <el-table-column label="床位" width="64" align="center">',
      '              <template #default="s"><span class="inp-bed-no">{{ s.row.bedNo || \'-\' }}</span></template>',
      '            </el-table-column>',
      '            <el-table-column label="患者" min-width="80">',
      '              <template #default="s">{{ s.row.patientName || \'-\' }}</template>',
      '            </el-table-column>',
      '            <el-table-column label="诊断" min-width="110" show-overflow-tooltip>',
      '              <template #default="s">{{ s.row.admitDiag || \'-\' }}</template>',
      '            </el-table-column>',
      '          </el-table>',
      '          <el-pagination v-if="patTotal > patSize" style="margin-top:6px;justify-content:flex-end;" background small layout="total, prev, pager, next" :total="patTotal" :page-size="patSize" :current-page="patPage" @current-change="onPatPage"></el-pagination>',
      '        </div>',
      '        <div class="inp-sbar-form" v-loading="prefilling">',
      '          <div class="inp-sbar-item">',
      '            <div class="inp-sbar-label"><span class="inp-sbar-tag s">S</span><span>S - 情景(Situation)</span><span class="ns-desc">患者当前发生了什么</span></div>',
      '            <el-input type="textarea" v-model="sbar.situation" :rows="3" :disabled="!!todayRec" placeholder="如: 3床张XX, 入院诊断社区获得性肺炎, 目前咳嗽咳痰减轻..."></el-input>',
      '          </div>',
      '          <div class="inp-sbar-item">',
      '            <div class="inp-sbar-label"><span class="inp-sbar-tag b">B</span><span>B - 背景(Background)</span><span class="ns-desc">病史、近期治疗与医嘱</span></div>',
      '            <el-input type="textarea" v-model="sbar.background" :rows="3" :disabled="!!todayRec" placeholder="如: 既往高血压10年, 目前静脉滴注抗生素第3天, 近期医嘱..."></el-input>',
      '          </div>',
      '          <div class="inp-sbar-item">',
      '            <div class="inp-sbar-label"><span class="inp-sbar-tag a">A</span><span>A - 评估(Assessment)</span><span class="ns-desc">生命体征与护理评估结果</span></div>',
      '            <el-input type="textarea" v-model="sbar.assessment" :rows="3" :disabled="!!todayRec" placeholder="如: 体温36.8℃ 脉搏82次/分 血压135/85mmHg, Braden 18分(轻度风险)..."></el-input>',
      '          </div>',
      '          <div class="inp-sbar-item">',
      '            <div class="inp-sbar-label"><span class="inp-sbar-tag r">R</span><span>R - 建议(Recommendation)</span><span class="ns-desc">下一班关注要点与护理重点</span></div>',
      '            <el-input type="textarea" v-model="sbar.recommendation" :rows="3" :disabled="!!todayRec" placeholder="如: 关注咳嗽咳痰变化, 鼓励饮水拍背排痰, 明晨复查血常规..."></el-input>',
      '          </div>',
      '          <div style="margin-top:6px;">',
      '            <el-button type="primary" :disabled="!canHandover" :loading="submitting" @click="submit">发起交班</el-button>',
      '            <el-button v-if="mode === \'patient\' && selVisitId != null" size="small" @click="reprefill">重新预填</el-button>',
      '            <el-button size="small" :disabled="!!todayRec" @click="clearSbar">清空</el-button>',
      '            <span style="margin-left:8px;color:var(--yb-ink-4);font-size:12px;">发起后由接班护士确认, 同一班次仅能发起一次</span>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '    <div v-if="pendingRec" class="inp-panel">',
      '      <h4>待接班 · {{ pendingRec.handoverNurseName || \'-\' }} 交班</h4>',
      '      <div style="margin-bottom:6px;">',
      '        <el-tag size="small" type="warning">待接班</el-tag>',
      '        <span style="margin-left:8px;color:var(--yb-ink-2);">{{ pendingRec.shiftDate }} {{ shiftLabel(pendingRec.shiftType) }} · 在院 {{ pendingRec.totalPatients }} 人 · 新入院 {{ pendingRec.newAdmit }} · 出院 {{ pendingRec.discharged }} · 危重 {{ pendingRec.criticalCount || 0 }}</span>',
      '      </div>',
      '      <template v-if="hasSbar(pendingRec)">',
      '        <div v-if="sbarOf(pendingRec).s" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-danger-strong);margin-bottom:2px;">S - 情景(Situation)</div>{{ sbarOf(pendingRec).s }}</div>',
      '        <div v-if="sbarOf(pendingRec).b" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-brand-strong);margin-bottom:2px;">B - 背景(Background)</div>{{ sbarOf(pendingRec).b }}</div>',
      '        <div v-if="sbarOf(pendingRec).a" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-warning-strong);margin-bottom:2px;">A - 评估(Assessment)</div>{{ sbarOf(pendingRec).a }}</div>',
      '        <div v-if="sbarOf(pendingRec).r" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-success-strong);margin-bottom:2px;">R - 建议(Recommendation)</div>{{ sbarOf(pendingRec).r }}</div>',
      '      </template>',
      '      <div v-else class="inp-sbar-view is-empty">{{ pendingDetail && pendingDetail.detail ? pendingDetail.detail : \'未填写交班详情\' }}</div>',
      '      <el-button type="success" :loading="takingOver" @click="takeover()">接班确认</el-button>',
      '    </div>',
      '    <div class="inp-panel">',
      '      <h4>交接班历史</h4>',
      '      <el-table :data="histRecords" border stripe size="small">',
      '        <el-table-column type="index" label="序号" width="55" align="center" :index="seqNo"></el-table-column>',
      '        <el-table-column label="日期" width="110" align="center">',
      '          <template #default="s">{{ s.row.shiftDate || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="班次" width="90" align="center">',
      '          <template #default="s">{{ shiftLabel(s.row.shiftType) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="交班护士" width="110" align="center">',
      '          <template #default="s">{{ s.row.handoverNurseName || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="接班护士" width="110" align="center">',
      '          <template #default="s">{{ s.row.takeoverNurseName || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="在院数" width="80" align="center">',
      '          <template #default="s">{{ s.row.totalPatients }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="新入院" width="80" align="center">',
      '          <template #default="s">{{ s.row.newAdmit }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="出院" width="70" align="center">',
      '          <template #default="s">{{ s.row.discharged }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="危重" width="70" align="center">',
      '          <template #default="s">{{ s.row.criticalCount || 0 }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="交班详情" min-width="200" show-overflow-tooltip>',
      '          <template #default="s">{{ detailSummary(s.row) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="状态" width="90" align="center">',
      '          <template #default="s"><el-tag size="small" :type="statusType(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template>',
      '        </el-table-column>',
      '        <el-table-column label="发起时间" width="150" align="center">',
      '          <template #default="s">{{ fmtTime(s.row.createTime) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="操作" width="90" align="center" fixed="right">',
      '          <template #default="s">',
      '            <el-tooltip content="查看详情" placement="top">',
      '              <span class="ns-ic is-primary" @click="openView(s.row)"><ns-icon name="view"></ns-icon></span>',
      '            </el-tooltip>',
      '            <el-tooltip v-if="s.row.status === 1" content="接班确认" placement="top">',
      '              <span class="ns-ic is-success" @click="takeover(s.row)"><ns-icon name="finished"></ns-icon></span>',
      '            </el-tooltip>',
      '          </template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!loading && !histRecords.length" description="暂无交接班记录" :image-size="60"></el-empty>',
      '      <el-pagination v-if="histTotal > size" style="margin-top:10px;justify-content:flex-end;" background small layout="total, prev, pager, next" :total="histTotal" :page-size="size" :current-page="page" @current-change="onPage"></el-pagination>',
      '    </div>',
      '  </template>',
      /* 查看详情弹窗 */
      '  <el-dialog v-model="viewVisible" title="交接班详情" width="620px">',
      '    <div v-if="viewRow">',
      '      <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;">',
      '        <span style="font-weight:700;color:var(--yb-ink-1);">{{ viewRow.shiftDate }} {{ shiftLabel(viewRow.shiftType) }}</span>',
      '        <el-tag size="small" :type="statusType(viewRow.status)">{{ statusLabel(viewRow.status) }}</el-tag>',
      '      </div>',
      '      <div style="color:var(--yb-ink-2);font-size:13px;line-height:1.9;margin-bottom:8px;">',
      '        交班护士: {{ viewRow.handoverNurseName || \'-\' }} · 接班护士: {{ viewRow.takeoverNurseName || \'-\' }}<br>',
      '        在院 {{ viewRow.totalPatients }} 人 · 新入院 {{ viewRow.newAdmit }} · 出院 {{ viewRow.discharged }} · 危重 {{ viewRow.criticalCount || 0 }}',
      '      </div>',
      '      <template v-if="hasSbar(viewRow)">',
      '        <div v-if="sbarOf(viewRow).s" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-danger-strong);margin-bottom:2px;">S - 情景(Situation)</div>{{ sbarOf(viewRow).s }}</div>',
      '        <div v-if="sbarOf(viewRow).b" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-brand-strong);margin-bottom:2px;">B - 背景(Background)</div>{{ sbarOf(viewRow).b }}</div>',
      '        <div v-if="sbarOf(viewRow).a" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-warning-strong);margin-bottom:2px;">A - 评估(Assessment)</div>{{ sbarOf(viewRow).a }}</div>',
      '        <div v-if="sbarOf(viewRow).r" class="inp-sbar-view"><div style="font-weight:700;color:var(--yb-success-strong);margin-bottom:2px;">R - 建议(Recommendation)</div>{{ sbarOf(viewRow).r }}</div>',
      '      </template>',
      '      <div v-else class="inp-sbar-view is-empty">{{ detailOf(viewRow) }}</div>',
      '    </div>',
      '    <template #footer>',
      '      <el-button @click="viewVisible = false">关闭</el-button>',
      '      <el-button v-if="viewRow && viewRow.status === 1" type="success" :loading="takingOver" @click="takeover(viewRow); viewVisible = false">接班确认</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 5.5 出入量登记(T45): 逐笔录入 + 当日列表 + 日汇总三卡 =================
   * io_type 1进量 2出量(volume 单位 ml), 类别随类型联动(进量: 饮水/静脉输液/口服药/肠内营养/其他;
   * 出量: 尿量/引流/呕吐/咯血/其他); 日汇总 balance = 进量合计 - 出量合计, 负值红色提示。
   * 接口: /api/his/inp/io-record (POST 登记 / GET 当日列表 / GET daily-summary / DELETE 逻辑删)。
   */
  var IO_INTAKE_CATEGORIES = ['饮水', '静脉输液', '口服药', '肠内营养', '其他'];
  var IO_OUTPUT_CATEGORIES = ['尿量', '引流', '呕吐', '咯血', '其他'];
  var InpIoRecord = {
    name: 'InpIoRecord',
    props: {
      wardId: { type: [String, Number], default: null },
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    data: function () {
      return {
        date: todayStr(),
        selVisitId: null,
        form: { ioType: 1, category: '饮水', volume: null, route: '', note: '' },
        saving: false, loading: false, list: [],
        summary: { intakeTotal: 0, outputTotal: 0, balance: 0 }
      };
    },
    computed: {
      /* 类别选项随进/出量联动 */
      categoryOptions: function () {
        return this.form.ioType === 2 ? IO_OUTPUT_CATEGORIES : IO_INTAKE_CATEGORIES;
      }
    },
    watch: {
      /* 选患唯一入口 = 主组件左侧患者列表, 本面板仅跟随不自行切换 */
      visitId: function (v) { this.selVisitId = v; },
      selVisitId: function () { this.loadAll(); },
      date: function () { this.loadAll(); },
      /* 切换进/出量时类别回退到该类型默认项 */
      'form.ioType': function (v) {
        var opts = v === 2 ? IO_OUTPUT_CATEGORIES : IO_INTAKE_CATEGORIES;
        if (opts.indexOf(this.form.category) < 0) { this.form.category = opts[0]; }
      }
    },
    created: function () {
      this.selVisitId = this.visitId;
      this.loadAll();
    },
    methods: {
      loadAll: function () {
        var vm = this;
        if (vm.selVisitId == null) { vm.list = []; vm.summary = { intakeTotal: 0, outputTotal: 0, balance: 0 }; return; }
        vm.loading = true;
        var q = 'visitId=' + HIS.idParam(vm.selVisitId) + '&date=' + vm.date;
        Promise.all([
          HIS.get('/api/his/inp/io-record?' + q).catch(function () { return []; }),
          HIS.get('/api/his/inp/io-record/daily-summary?' + q).catch(function () { return null; })
        ]).then(function (rs) {
          vm.list = rs[0] || [];
          vm.summary = rs[1] || { intakeTotal: 0, outputTotal: 0, balance: 0 };
        }).finally(function () { vm.loading = false; });
      },
      onDateChange: function () { /* watch.date 已触发 loadAll */ },
      ioTypeLabel: function (v) { return Number(v) === 2 ? '出量' : '进量'; },
      ioTypeTag: function (v) { return Number(v) === 2 ? 'warning' : 'primary'; },
      /* ---- 逐笔登记 ---- */
      doSave: function () {
        var vm = this;
        if (vm.selVisitId == null) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        if (!vm.form.category) { ElementPlus.ElMessage.warning('请选择类别'); return; }
        var vol = vm.form.volume == null ? null : Number(vm.form.volume);
        if (vol == null || isNaN(vol) || vol <= 0) { ElementPlus.ElMessage.warning('量(ml)必须为大于 0 的数值'); return; }
        vm.saving = true;
        HIS.post('/api/his/inp/io-record', {
          visitId: HIS.id(vm.selVisitId),
          ioType: vm.form.ioType,
          category: vm.form.category,
          volume: vol,
          route: (vm.form.route || '').trim() || null,
          note: (vm.form.note || '').trim() || null
        }).then(function () {
          HIS.notifySuccess('出入量记录已登记');
          vm.form.volume = null;
          vm.form.route = '';
          vm.form.note = '';
          vm.loadAll();
        }).catch(HIS.notifyError).finally(function () { vm.saving = false; });
      },
      doRemove: function (row) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认删除该条出入量记录？(' + vm.ioTypeLabel(row.ioType) + ' · ' + row.category + ' · ' + row.volume + 'ml)', '删除确认', {
          type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
        }).then(function () {
          return HIS.del('/api/his/inp/io-record/' + HIS.idParam(row.id));
        }).then(function () {
          HIS.notifySuccess('已删除');
          vm.loadAll();
        }).catch(function (e) {
          if (e === 'cancel' || e === 'close') { return; }
          HIS.notifyError(e);
        });
      }
    },
    template: [
      '<div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">日期</span>',
      '    <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:150px;" @change="onDateChange"></el-date-picker>',
      '    <span style="flex:1;"></span>',
      '    <span v-if="patient" style="font-size:13px;color:var(--yb-ink-2);"><b style="color:var(--yb-ink-1);">{{ patient.patientName }}</b><span style="color:var(--yb-ink-3);"> · {{ patient.bedNo || \'—\' }}床 · {{ patient.inpNo || \'-\' }}</span><span v-if="patient.admitDiag" style="color:var(--yb-ink-3);"> · {{ patient.admitDiag }}</span></span>',
      '    <span v-else style="color:var(--yb-ink-3);font-size:12px;">请在左侧选择患者</span>',
      '    <el-button size="small" @click="loadAll">刷新</el-button>',
      '  </div>',
      /* 日汇总三卡: 进量合计 / 出量合计 / 平衡值(负值红色) */
      '  <el-row :gutter="12" style="margin-bottom:12px;">',
      '    <el-col :span="8"><div class="inp-stat"><div class="v">{{ summary.intakeTotal || 0 }}</div><div class="k">进量合计(ml)</div></div></el-col>',
      '    <el-col :span="8"><div class="inp-stat"><div class="v">{{ summary.outputTotal || 0 }}</div><div class="k">出量合计(ml)</div></div></el-col>',
      '    <el-col :span="8"><div class="inp-stat" :class="{ \'is-danger\': Number(summary.balance) < 0 }"><div class="v">{{ summary.balance != null ? summary.balance : \'-\' }}</div><div class="k">平衡值(ml, 进-出)</div></div></el-col>',
      '  </el-row>',
      /* 录入表单 */
      '  <div class="inp-panel">',
      '    <h4>登记出入量</h4>',
      '    <div class="inp-io-form">',
      '      <div class="fld">',
      '        <span class="cap">类型</span>',
      '        <el-radio-group v-model="form.ioType">',
      '          <el-radio-button :label="1">进量</el-radio-button>',
      '          <el-radio-button :label="2">出量</el-radio-button>',
      '        </el-radio-group>',
      '      </div>',
      '      <div class="fld">',
      '        <span class="cap">类别</span>',
      '        <el-select v-model="form.category" style="width:130px;">',
      '          <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c"></el-option>',
      '        </el-select>',
      '      </div>',
      '      <div class="fld">',
      '        <span class="cap">量(ml)</span>',
      '        <el-input-number v-model="form.volume" :min="1" :max="99999" :precision="0" controls-position="right" style="width:130px;"></el-input-number>',
      '      </div>',
      '      <div class="fld">',
      '        <span class="cap">途径(可选)</span>',
      '        <el-input v-model="form.route" placeholder="如 口服/静脉/留置导尿" style="width:150px;" clearable></el-input>',
      '      </div>',
      '      <div class="fld" style="flex:1;min-width:180px;">',
      '        <span class="cap">备注(可选)</span>',
      '        <el-input v-model="form.note" placeholder="如 进食后呕吐一次" clearable></el-input>',
      '      </div>',
      '      <el-button type="primary" :loading="saving" @click="doSave">登记</el-button>',
      '    </div>',
      '  </div>',
      /* 当日记录 */
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="#" width="50" align="center"></el-table-column>',
      '    <el-table-column label="记录时间" width="110" align="center">',
      '      <template #default="s">{{ s.row.recordTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="类型" width="70" align="center">',
      '      <template #default="s"><el-tag size="small" :type="ioTypeTag(s.row.ioType)">{{ ioTypeLabel(s.row.ioType) }}</el-tag></template>',
      '    </el-table-column>',
      '    <el-table-column label="类别" width="90" align="center">',
      '      <template #default="s">{{ s.row.category || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="量(ml)" width="90" align="center">',
      '      <template #default="s"><span style="font-weight:700;font-variant-numeric:tabular-nums;">{{ s.row.volume != null ? s.row.volume : \'-\' }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="途径" width="110">',
      '      <template #default="s">{{ s.row.route || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="备注" min-width="140" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.note || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="登记护士" width="100" align="center">',
      '      <template #default="s">{{ s.row.nurseName || s.row.createBy || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="操作" width="70" align="center">',
      '      <template #default="s"><el-button type="danger" size="small" text @click="doRemove(s.row)">删除</el-button></template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-empty v-if="!loading && !list.length" :description="selVisitId ? \'该日暂无出入量记录\' : \'请在左侧选择患者\'" :image-size="60"></el-empty>',
      '</div>'
    ].join('\n')
  };

  /* ================= 5.6 给药记录(T45, 只读): 药品类医嘱执行流水 =================
   * 口径: order_category=1 且执行单已执行(exec_status=2), 按执行时间升序; 只读追溯不可编辑。
   * 接口: GET /api/his/inp/nursing/medication-admin?visitId=&date=。
   */
  var InpMedAdmin = {
    name: 'InpMedAdmin',
    props: {
      wardId: { type: [String, Number], default: null },
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    data: function () {
      return {
        date: todayStr(),
        selVisitId: null,
        loading: false, list: []
      };
    },
    watch: {
      /* 选患唯一入口 = 主组件左侧患者列表, 本面板仅跟随不自行切换 */
      visitId: function (v) { this.selVisitId = v; },
      selVisitId: function () { this.load(); },
      date: function () { this.load(); }
    },
    created: function () {
      this.selVisitId = this.visitId;
      this.load();
    },
    methods: {
      load: function () {
        var vm = this;
        if (vm.selVisitId == null) { vm.list = []; return; }
        vm.loading = true;
        HIS.get('/api/his/inp/nursing/medication-admin?visitId=' + HIS.idParam(vm.selVisitId) + '&date=' + vm.date)
          .then(function (rows) { vm.list = rows || []; })
          .catch(HIS.notifyError).finally(function () { vm.loading = false; });
      }
    },
    template: [
      '<div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">日期</span>',
      '    <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:150px;"></el-date-picker>',
      '    <span v-if="patient" style="font-size:13px;color:var(--yb-ink-2);"><b style="color:var(--yb-ink-1);">{{ patient.patientName }}</b><span style="color:var(--yb-ink-3);"> · {{ patient.bedNo || \'—\' }}床 · {{ patient.inpNo || \'-\' }}</span></span>',
      '    <span v-else style="color:var(--yb-ink-3);font-size:12px;">请在左侧选择患者</span>',
      '    <span style="flex:1;"></span>',
      '    <span style="color:var(--yb-ink-3);font-size:12px;">当日药品类医嘱执行流水 · 只读</span>',
      '    <el-button size="small" @click="load">刷新</el-button>',
      '  </div>',
      '  <el-table :data="list" v-loading="loading" border stripe size="small">',
      '    <el-table-column type="index" label="#" width="50" align="center"></el-table-column>',
      '    <el-table-column label="执行时间" width="120" align="center">',
      '      <template #default="s">{{ s.row.execTime || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="药品名称" min-width="240" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.orderName || \'-\' }}<span v-if="s.row.spec" style="color:var(--yb-ink-3);"> {{ s.row.spec }}</span></template>',
      '    </el-table-column>',
      '    <el-table-column label="剂量" width="100" align="center">',
      '      <template #default="s">{{ s.row.dosage != null && s.row.dosage !== \'\' ? s.row.dosage + (s.row.dosageUnit || \'\') : \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="频次" width="80" align="center">',
      '      <template #default="s">{{ s.row.frequency || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="执行护士" width="110" align="center">',
      '      <template #default="s">{{ s.row.execNurse || \'-\' }}</template>',
      '    </el-table-column>',
      '    <el-table-column label="备注" min-width="140" show-overflow-tooltip>',
      '      <template #default="s">{{ s.row.execRemark || \'-\' }}</template>',
      '    </el-table-column>',
      '  </el-table>',
      '  <el-empty v-if="!loading && !list.length" :description="selVisitId ? \'该日暂无给药执行记录\' : \'请在左侧选择患者\'" :image-size="60"></el-empty>',
      '</div>'
    ].join('\n')
  };

  /* ================= 6. 床位一览(病区住院状态板, 对标商业 HIS) =================
   * 数据源: /api/his/inp/bed/list(床位属性) + /api/his/inp/nurse/patients(病情/护理等级/待办计数/过敏/手术/体温/量表)。
   * 交互: 单击卡片联动主组件选中患者(emit pick), 双击跳医嘱审核(emit open); 色彩条可切换护理等级/病情两种口径。 */
  HIS.views.InpBedOverview = {
    name: 'InpBedOverview',
    props: { wardId: { type: [String, Number], default: null } },
    emits: ['pick', 'open', 'flow'],
    data: function () {
      return {
        loading: false, wards: [], innerWardId: null, beds: [], patients: [],
        activeVisitId: null,
        colorMode: 'nurse',      /* 左侧色条口径: nurse=护理等级 / condition=病情 */
        groupByRoom: true,
        filter: 'all',
        /* 13.15.3 床位管理增强: 三态视图 / 精确定位 / 图例 / 右键菜单 / 拖拽转床 */
        viewMode: 'card',          /* card=细卡 / slim=简卡 / list=病人列表 */
        searchKey: '',
        legendOpen: false,
        ctx: { show: false, x: 0, y: 0, bed: null },
        dragFrom: null,            /* {visitId, bedId, bedNo, name} 拖拽源 */
        overBedId: null,           /* 当前拖拽悬停目标床位 */
        quickBedMap: {},           /* 简卡视图: 床位idKey → 待转空床选择(非响应式新增键经 Vue3 proxy 仍可响) */
        /* 图例抽屉字典(文件级常量模板不可见, 经 data 暴露) */
        dictBedType: BED_TYPES, dictBedStatus: BED_STATUS
      };
    },
    computed: {
      /* 未从外部传入病区时自带选择器 */
      needWardPicker: function () { return !this.wardId; },
      effectiveWardId: function () { return this.wardId || this.innerWardId; },
      visitMap: function () {
        var m = {};
        (this.patients || []).forEach(function (p) { m[HIS.idKey(p.id)] = p; });
        return m;
      },
      patientMap: function () {
        var m = {};
        (this.patients || []).forEach(function (p) { m[HIS.idKey(p.patientId)] = p; });
        return m;
      },
      /* KPI: 床位四态 +  occupancy 率(分母剔除停用) + 患者结构(危重/特级一级/新入/预出院/隔离/待办总量) */
      stats: function () {
        var s = { total: 0, empty: 0, occupied: 0, disabled: 0,
          critical: 0, severe: 0, spec: 0, lvl1: 0, newToday: 0, preDischarge: 0, isolation: 0,
          pendAudit: 0, pendExec: 0 };
        (this.beds || []).forEach(function (b) {
          s.total++;
          if (b.status === 0) { s.empty++; } else if (b.status === 1) { s.occupied++; } else if (b.status === 2) { s.disabled++; }
        });
        var vm = this;
        (this.patients || []).forEach(function (p) {
          if (p.conditionLevel === 1) { s.critical++; }
          if (p.conditionLevel === 2) { s.severe++; }
          if (p.nursingLevel === 1) { s.spec++; }
          if (p.nursingLevel === 2) { s.lvl1++; }
          if (daysSince(p.admitDate) === 0) { s.newToday++; }
          if (vm.isPreDischarge(p)) { s.preDischarge++; }
          if (p.isQuarantine === 1) { s.isolation++; }
          s.pendAudit += Number(p.pendingAudit) || 0;
          s.pendExec += Number(p.pendingExec) || 0;
        });
        var avail = s.total - s.disabled;
        s.occRate = avail > 0 ? Math.round(s.occupied * 100 / avail) : 0;
        return s;
      },
      filteredBeds: function () {
        var vm = this;
        var rows = (vm.beds || []).slice();
        rows.sort(byBedNo);
        var q = (vm.searchKey || '').trim();
        if (q) {
          rows = rows.filter(function (b) {
            var p = vm.bedPatient(b);
            var hay = [b.bedNo, p ? p.patientName : '', p ? p.inpNo : ''].join(' ');
            return hay && hay.indexOf(q) >= 0;
          });
        }
        var f = vm.filter;
        if (f === 'all') { return rows; }
        if (f === 'empty') { return rows.filter(function (b) { return b.status === 0; }); }
        return rows.filter(function (b) {
          if (b.status !== 1) { return false; }
          var p = vm.bedPatient(b);
          if (!p) { return f !== 'critical' && f !== 'pendAudit' && f !== 'pendExec' && f !== 'newToday' && f !== 'preDischarge' && f !== 'allergy' && f !== 'discharge'; }
          if (f === 'critical') { return p.conditionLevel === 1 || p.conditionLevel === 2; }
          if (f === 'pendAudit') { return (Number(p.pendingAudit) || 0) > 0; }
          if (f === 'pendExec') { return (Number(p.pendingExec) || 0) > 0; }
          if (f === 'newToday') { return daysSince(p.admitDate) === 0; }
          if (f === 'preDischarge') { return vm.isPreDischarge(p); }
          if (f === 'allergy') { return !!p.allergyFlag; }
          if (f === 'discharge') { return vm.isPreDischarge(p); }
          if (f === 'isolation') { return p.isQuarantine === 1 || b.bedType === 4; }
          return true;
        });
      },
      /* 病人列表视图数据: 仅占用且命中搜索的床位对应患者 */
      listPatients: function () {
        var vm = this;
        return (vm.filteredBeds || []).filter(function (b) { return b.status === 1 && vm.bedPatient(b); }).map(function (b) {
          var p = vm.bedPatient(b); return p;
        });
      },
      /* 房间分组: 每组=一个病区房间(含其全部床位); 不分组时整体作为单一扁平组(无标题)。 */
      roomGroups: function () {
        var beds = this.filteredBeds || [];
        if (!this.groupByRoom) { return [{ roomNo: null, beds: beds }]; }
        var out = [], map = {};
        beds.forEach(function (b) {
          var k = b.roomNo || '未分配房间';
          if (!map[k]) { map[k] = { roomNo: k, beds: [] }; out.push(map[k]); }
          map[k].beds.push(b);
        });
        return out;
      },
      /* 本病区空闲床(简卡/列表视图"转空床"下拉) */
      emptyBeds: function () {
        return (this.beds || []).filter(function (b) { return b.status === 0; });
      },
      /* 图例字典随色条口径切换 */
      legendDicts: function () {
        return this.colorMode === 'condition'
          ? { lvName: '病情', lv: CONDITION_LEVEL }
          : { lvName: '护理等级', lv: NURSING_LEVELS };
      }
    },
    watch: {
      effectiveWardId: function () { this.load(); }
    },
    created: function () {
      if (this.wardId) { this.load(); } else { this.loadWards(); }
    },
    methods: {
      /* 模板内键归一(HIS 对模板不可见) */
      idKey: function (v) { return HIS.idKey(v); },
      /* 占用床匹配在院患者(先 inpVisitId 后 patientId) */
      bedPatient: function (b) {
        if (b.status !== 1) { return null; }
        return (b.inpVisitId != null ? this.visitMap[HIS.idKey(b.inpVisitId)] : null)
          || (b.patientId != null ? this.patientMap[HIS.idKey(b.patientId)] : null) || null;
      },
      isActive: function (b) {
        var p = this.bedPatient(b);
        return !!(p && this.activeVisitId != null && HIS.sameId(this.activeVisitId, p.id));
      },
      isPreDischarge: function (p) {
        if (!p || !p.expectedDischargeDate) { return false; }
        var d = String(p.expectedDischargeDate).substring(0, 10);
        var n = new Date();
        var pad = function (x) { return (x < 10 ? '0' : '') + x; };
        return d <= (n.getFullYear() + '-' + pad(n.getMonth() + 1) + '-' + pad(n.getDate()));
      },
      /* 色条: 护理等级(特红/一橙/二黄/三绿, 与患者横幅同色口径) 或 病情(危红/重橙/一般绿) */
      barColor: function (b) {
        if (b.status === 2) { return 'var(--yb-ink-disabled)'; }
        if (b.status !== 1) { return 'var(--yb-success)'; }
        var p = this.bedPatient(b);
        if (!p) { return 'var(--yb-link)'; }
        if (this.colorMode === 'condition') {
          return { 1: 'var(--yb-danger)', 2: '#E6A23C', 3: 'var(--yb-success)' }[p.conditionLevel] || 'var(--yb-link)';
        }
        return { 1: 'var(--yb-danger)', 2: '#E6A23C', 3: '#d4a017', 4: 'var(--yb-success)' }[p.nursingLevel] || 'var(--yb-link)';
      },
      nameStyle: function (b) {
        var p = this.bedPatient(b);
        if (!p) { return {}; }
        if (p.conditionLevel === 1) { return { color: 'var(--yb-danger)', animation: 'bdo-pulse 1.6s ease-in-out infinite' }; }
        if (p.conditionLevel === 2) { return { color: '#E6A23C' }; }
        return {};
      },
      genderText: function (p) {
        var v = p ? p.gender : null;
        return (v === 1 || v === '1' || v === '男') ? '男' : ((v === 2 || v === '2' || v === '女') ? '女' : '');
      },
      stayText: function (p) {
        var d = daysSince(p.admitDate);
        if (d == null) { return ''; }
        return d === 0 ? '今日入院' : ('住院' + d + '天');
      },
      /* 标签云: 新入/过敏/隔离/术后/发热/压疮/跌倒/预出院/待审/待执 */
      tags: function (b) {
        var p = this.bedPatient(b);
        if (!p) { return []; }
        var arr = [];
        if (daysSince(p.admitDate) === 0) { arr.push({ t: '新入', c: 'bdo-t-blu' }); }
        if (p.allergyFlag) { arr.push({ t: '过敏', c: 'bdo-t-red' }); }
        if (p.isQuarantine === 1 || b.bedType === 4) { arr.push({ t: '隔离', c: 'bdo-t-red' }); }
        if (p.lastSurgeryDate) {
          var n = daysSince(p.lastSurgeryDate);
          arr.push({ t: n === 0 ? '今日手术' : ('术后' + (n == null ? '-' : n) + '天'), c: 'bdo-t-pur' });
        }
        var t = Number(p.lastTemp);
        if (p.lastTemp != null && !isNaN(t) && t >= 37.3) { arr.push({ t: 'T' + t.toFixed(1), c: t >= 38.5 ? 'bdo-t-red' : 'bdo-t-org' }); }
        var bd = Number(p.bradenScore);
        if (p.bradenScore != null && !isNaN(bd) && bd <= 14) { arr.push({ t: '压疮' + (bd <= 12 ? '高危' : '中危'), c: 'bdo-t-org' }); }
        var ms = Number(p.morseScore);
        if (p.morseScore != null && !isNaN(ms) && ms >= 25) { arr.push({ t: '跌倒' + (ms >= 45 ? '高危' : '中危'), c: 'bdo-t-org' }); }
        if (this.isPreDischarge(p)) { arr.push({ t: '预出院', c: 'bdo-t-grn' }); }
        if ((Number(p.pendingAudit) || 0) > 0) { arr.push({ t: '审' + p.pendingAudit, c: 'bdo-t-org' }); }
        if ((Number(p.pendingExec) || 0) > 0) { arr.push({ t: '执' + p.pendingExec, c: 'bdo-t-blu' }); }
        return arr;
      },
      /* 空床/停用副行: 房间·床位日费·性别限制 */
      blankSub: function (b) {
        if (b.status === 2) { return '床位维护中'; }
        var seg = [];
        if (b.roomNo) { seg.push(b.roomNo + '房'); }
        if (b.dailyPrice != null) { seg.push(b.dailyPrice + '元/日'); }
        if (Number(b.genderLimit) === 1) { seg.push('限男'); }
        if (Number(b.genderLimit) === 2) { seg.push('限女'); }
        return seg.join(' · ') || '空闲';
      },
      /* 悬停详情 */
      cardTip: function (b) {
        var p = this.bedPatient(b);
        if (!p) {
          if (b.status === 0) {
            var lv = { 2: '单间', 3: '监护', 4: '特需' }[b.bedLevel];
            return '空床' + (b.bedNo ? ' ' + b.bedNo : '') + (lv ? ' · ' + lv : '') + ' · ' + this.blankSub(b);
          }
          return '停用床位';
        }
        var seg = ['住院号: ' + (p.inpNo || '-')];
        seg.push('诊断: ' + (p.admitDiag || '-'));
        seg.push('护理等级: ' + (NURSING_LEVELS[p.nursingLevel] || '-'));
        seg.push('病情: ' + (CONDITION_LEVEL[p.conditionLevel] || '-'));
        if (p.dietType) { seg.push('饮食: ' + p.dietType); }
        if (p.lastTemp != null) { seg.push('末次体温: ' + p.lastTemp + '℃'); }
        if (p.expectedDischargeDate) { seg.push('预计出院: ' + String(p.expectedDischargeDate).substring(0, 10)); }
        seg.push('预交金: ' + (p.depositBalance == null ? '-' : p.depositBalance));
        return seg.join('  |  ');
      },
      onCard: function (b) {
        var p = this.bedPatient(b);
        if (!p) { return; }
        this.activeVisitId = HIS.idKey(p.id);
        this.$emit('pick', HIS.id(p.id));
      },
      onCardDbl: function (b) {
        var p = this.bedPatient(b);
        if (p) { this.$emit('open', HIS.id(p.id)); }
      },
      bedStateClass: function (b) {
        if (b.status === 2) { return 'is-off'; }
        if (b.status !== 1) { return 'is-blank'; }
        return 'is-on';
      },
      loadWards: function () {
        var vm = this;
        HIS.get('/api/his/inp/bed/ward/list').then(function (rows) {
          var list = rows || [];
          var enabled = list.filter(function (w) { return w.status === 1; });
          vm.wards = enabled.length ? enabled : list;
          if (!vm.innerWardId && vm.wards.length) { vm.innerWardId = HIS.id(vm.wards[0].id); }
        }).catch(HIS.notifyError);
      },
      load: function () {
        var vm = this;
        var wid = vm.effectiveWardId;
        if (!wid) { vm.beds = []; vm.patients = []; return; }
        vm.loading = true;
        Promise.all([
          HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(wid)),
          HIS.get('/api/his/inp/nurse/patients?wardId=' + HIS.idParam(wid) + '&page=1&size=200')
        ]).then(function (rs) {
          vm.beds = rs[0] || [];
          vm.patients = (rs[1] && rs[1].records) || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      /* ---- 13.15.3 增强: 搜索命中高亮 ---- */
      isHit: function (b) {
        var q = (this.searchKey || '').trim();
        if (!q) { return false; }
        var p = this.bedPatient(b);
        var hay = [b.bedNo, p ? p.patientName : '', p ? p.inpNo : ''].join(' ');
        return hay.indexOf(q) >= 0;
      },
      /* ---- 床卡右键菜单 ---- */
      openCtx: function (b, e) {
        this.ctx = { show: true, x: Math.min(e.clientX, window.innerWidth - 180), y: Math.min(e.clientY, window.innerHeight - 220), bed: b };
      },
      closeCtx: function () { this.ctx.show = false; },
      ctxPick: function () {
        if (this.ctx.bed) { this.onCard(this.ctx.bed); }
        this.ctx.show = false;
      },
      ctxOpenOrders: function () {
        var b = this.ctx.bed, p = b ? this.bedPatient(b) : null;
        this.ctx.show = false;
        if (p) { this.$emit('open', HIS.id(p.id)); }
      },
      /* 发起转科: 交给主组件切到入出转页签(病区选择/审批在 flow 面板完成) */
      ctxFlow: function (seg) {
        var b = this.ctx.bed, p = b ? this.bedPatient(b) : null;
        this.ctx.show = false;
        if (p) { this.$emit('pick', HIS.id(p.id)); }
        this.$emit('flow', seg || 'approval');
      },
      ctxWristband: function () {
        var b = this.ctx.bed, p = b ? this.bedPatient(b) : null;
        this.ctx.show = false;
        if (!p) { HIS.notifyError('该床位无在区患者'); return; }
        HIS.get('/api/his/inp/print/render/wristband?visitId=' + HIS.idParam(p.id)).then(function (html) {
          if (typeof HIS.printHtmlFrame === 'function' && html) { HIS.printHtmlFrame(html, '患者腕带'); }
          else { HIS.notifyError('打印组件未就绪'); }
        }).catch(HIS.notifyError);
      },
      /* ---- 拖拽转床/换床(HTML5 draggable) ---- */
      dragStart: function (b, e) {
        var p = this.bedPatient(b);
        if (b.status !== 1 || !p) { e.dataTransfer.effectAllowed = 'none'; return; }
        this.dragFrom = { visitId: HIS.id(p.id), bedId: HIS.idKey(b.id), bedNo: b.bedNo, name: p.patientName };
        e.dataTransfer.effectAllowed = 'move';
        try { e.dataTransfer.setData('text/plain', String(b.bedNo)); } catch (err) { /* IE 兜底忽略 */ }
      },
      dragEnd: function () { this.dragFrom = null; this.overBedId = null; },
      dragOver: function (b, e) {
        if (!this.dragFrom || HIS.idKey(b.id) === this.dragFrom.bedId) { return; }
        if (b.status === 0 || b.status === 1) { this.overBedId = HIS.idKey(b.id); e.dataTransfer.dropEffect = 'move'; }
      },
      dragLeave: function (b) { if (HIS.idKey(b.id) === this.overBedId) { this.overBedId = null; } },
      onDrop: function (b) {
        var vm = this;
        var from = vm.dragFrom;
        vm.dragFrom = null; vm.overBedId = null;
        if (!from || HIS.idKey(b.id) === from.bedId) { return; }
        if (b.status === 0) { vm.doDirectTransfer(from, b); }
        else if (b.status === 1) { vm.doSwapApply(from, b); }
      },
      /* 占用床 → 空床: 直接转床(PUT /visit/{id}/transfer, 乐观锁原子换床) */
      doDirectTransfer: function (from, toBed) {
        var vm = this;
        confirmBox('确认转床？', from.name + '(' + from.bedNo + '床) 直接转至 ' + (toBed.bedNo || '-') + '床, 立即生效(乐观锁抢占新床并释放旧床)。').then(function () {
          HIS.put('/api/his/inp/visit/' + HIS.idParam(from.visitId) + '/transfer', { targetBedId: HIS.id(toBed.id), reason: '护士站拖拽转床' }).then(function () {
            HIS.notifySuccess('转床完成'); vm.load();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      /* 占用床 → 占用床: 换床=生成两条转床申请, 至入出转审批中心批准执行 */
      doSwapApply: function (from, toBed) {
        var vm = this;
        var tp = vm.bedPatient(toBed);
        if (!tp) { return; }
        confirmBox('发起换床申请？', from.name + '(' + from.bedNo + '床) ⇄ ' + (toBed.bedNo || '-') + '床(' + (tp.patientName || '-') + '), 将生成两条转床申请, 请到「入出转 · 申请审批」批准并执行。').then(function () {
          Promise.all([
            HIS.post('/api/his/inp/transfer', { inpVisitId: HIS.id(from.visitId), transferType: 2, toWardId: toBed.wardId, toBedId: HIS.id(toBed.id), reason: '护士站拖拽换床' }),
            HIS.post('/api/his/inp/transfer', { inpVisitId: HIS.id(tp.id), transferType: 2, toWardId: toBed.wardId, toBedId: HIS.id(from.bedId), reason: '护士站拖拽换床(对调)' })
          ]).then(function () {
            HIS.notifySuccess('两条换床申请已提交, 待审批执行'); vm.$emit('flow', 'approval');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      /* 列表视图写操作: 单击选中 / 右键菜单(原生事件在第二参) */
      rowClick: function (row) { this.onCard(row); },
      rowCtx: function (row, column, e) { if (e && e.preventDefault) { e.preventDefault(); } this.openCtx(row, e); },
      /* 简卡/列表视图: 患者直接转至所选空床 */
      quickTransfer: function (b, targetBedId) {
        if (!targetBedId) { return; }
        var p = this.bedPatient(b);
        var tb = (this.beds || []).filter(function (x) { return HIS.idKey(x.id) === HIS.idKey(targetBedId); })[0];
        if (p && tb) { this.doDirectTransfer({ visitId: HIS.id(p.id), bedNo: b.bedNo, name: p.patientName }, tb); }
      }
    },
    template: [
      '<div v-loading="loading">',
      '  <div class="toolbar" style="margin-bottom:8px;">',
      '    <template v-if="needWardPicker">',
      '      <span style="font-weight:600;">病区</span>',
      '      <el-select v-model="innerWardId" placeholder="选择病区" style="width:180px;">',
      '        <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>',
      '      </el-select>',
      '    </template>',
      '    <span style="color:var(--yb-ink-2);">占用率 <b style="color:var(--yb-brand-strong);">{{ stats.occRate }}%</b> <span style="color:var(--yb-ink-4);">|</span> 总 <b>{{ stats.total }}</b> 空床 <b style="color:var(--yb-success-strong);">{{ stats.empty }}</b> 占用 <b style="color:var(--yb-link);">{{ stats.occupied }}</b> 停用 <b style="color:var(--yb-ink-3);">{{ stats.disabled }}</b></span>',
      '    <span style="flex:1;"></span>',
      '    <div class="bdo-view-seg">',
      '      <span v-for="vm2 in [[\'card\',\'\u7ec6\u5361\'],[\'slim\',\'\u7b80\u5361\'],[\'list\',\'\u5217\u8868\']]" :key="vm2[0]" :class="{ \'is-active\': viewMode === vm2[0] }" @click="viewMode = vm2[0]">{{ vm2[1] }}</span>',
      '    </div>',
      '    <el-input v-model="searchKey" size="small" placeholder="床号/病案号/姓名 定位" clearable style="width:170px;"></el-input>',
      '    <el-button size="small" @click="legendOpen = true">图例</el-button>',
      '    <el-button size="small" @click="load">刷新</el-button>',
      '  </div>',
      '  <div class="bdo-kpis">',
      '    <span class="bdo-kpi bdo-kpi-red">病危 <b>{{ stats.critical }}</b></span>',
      '    <span class="bdo-kpi bdo-kpi-org">病重 <b>{{ stats.severe }}</b></span>',
      '    <span class="bdo-kpi">特级护理 <b>{{ stats.spec }}</b> · 一级 <b>{{ stats.lvl1 }}</b></span>',
      '    <span class="bdo-kpi bdo-kpi-blu">新入 <b>{{ stats.newToday }}</b></span>',
      '    <span class="bdo-kpi bdo-kpi-grn">预出院 <b>{{ stats.preDischarge }}</b></span>',
      '    <span class="bdo-kpi bdo-kpi-red">隔离 <b>{{ stats.isolation }}</b></span>',
      '    <span class="bdo-kpi bdo-kpi-org">待审核 <b>{{ stats.pendAudit }}</b></span>',
      '    <span class="bdo-kpi bdo-kpi-blu">待执行 <b>{{ stats.pendExec }}</b></span>',
      '  </div>',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <el-radio-group v-model="filter" size="small">',
      '      <el-radio-button label="all">全部</el-radio-button>',
      '      <el-radio-button label="critical">危重</el-radio-button>',
      '      <el-radio-button label="pendAudit">待审核</el-radio-button>',
      '      <el-radio-button label="pendExec">待执行</el-radio-button>',
      '      <el-radio-button label="newToday">新入</el-radio-button>',
      '      <el-radio-button label="preDischarge">预出院</el-radio-button>',
      '      <el-radio-button label="allergy">过敏</el-radio-button>',
      '      <el-radio-button label="discharge">出院办理</el-radio-button>',
      '      <el-radio-button label="isolation">隔离</el-radio-button>',
      '      <el-radio-button label="empty">空床</el-radio-button>',
      '    </el-radio-group>',
      '    <span style="flex:1;"></span>',
      '    <span style="font-size:12px;color:var(--yb-ink-3);">色条</span>',
      '    <el-radio-group v-model="colorMode" size="small">',
      '      <el-radio-button label="nurse">护理等级</el-radio-button>',
      '      <el-radio-button label="condition">病情</el-radio-button>',
      '    </el-radio-group>',
      '    <span style="font-size:12px;color:var(--yb-ink-3);">布局</span>',
      '    <el-radio-group v-model="groupByRoom" size="small">',
      '      <el-radio-button :label="true">按房间分组</el-radio-button>',
      '      <el-radio-button :label="false">平铺显示</el-radio-button>',
      '    </el-radio-group>',
      '  </div>',
      '  <template v-if="viewMode === \'card\'">',
      '  <div class="bdo-bands">',
      '    <section v-for="(g, gi) in roomGroups" :key="gi" class="bdo-band">',
      '      <div v-if="g.roomNo" class="bdo-band-h"><span class="bdo-band-room">{{ g.roomNo }}</span><span class="bdo-band-cnt">{{ g.beds.length }} 床</span></div>',
      '      <div class="bdo-grid">',
      '        <el-tooltip v-for="(b, bi) in g.beds" :key="bi" :content="cardTip(b)" placement="top" :show-after="300">',
      '          <div class="bdo-card" :class="[bedStateClass(b), { \'is-active\': isActive(b), \'bdo-search-hit\': isHit(b), \'is-dragging\': dragFrom && dragFrom.bedId === idKey(b.id), \'is-drag-over\': overBedId === idKey(b.id) }]"',
      '               :draggable="b.status === 1" @click="onCard(b)" @dblclick="onCardDbl(b)" @contextmenu.prevent="openCtx(b, $event)"',
      '               @dragstart="dragStart(b, $event)" @dragend="dragEnd" @dragover="dragOver(b, $event)" @dragleave="dragLeave(b)" @drop.prevent="onDrop(b)">',
      '            <div class="bdo-bar" :style="{ background: barColor(b) }"></div>',
      '            <div class="bdo-body">',
      '              <div class="bdo-head">',
      '                <span class="bdo-no">{{ b.bedNo || \'-\' }}</span>',
      '                <template v-if="bedPatient(b)">',
      '                  <span class="bdo-meta">{{ genderText(bedPatient(b)) }}{{ bedPatient(b).age != null ? \' · \' + bedPatient(b).age + \'岁\' : \'\' }} · {{ stayText(bedPatient(b)) }}</span>',
      '                </template>',
      '                <span style="flex:1;"></span>',
      '                <span v-if="Number(b.isExtraBed) === 1" class="bdo-badge bdo-b-org">加床</span>',
      '                <span v-if="b.bedType === 2" class="bdo-badge bdo-b-red">抢救</span>',
      '                <span v-if="b.bedType === 3" class="bdo-badge bdo-b-blu">监护</span>',
      '                <span v-if="b.bedType === 4" class="bdo-badge bdo-b-grn">隔离</span>',
      '              </div>',
      '              <div v-if="bedPatient(b)" class="bdo-name" :style="nameStyle(b)">{{ bedPatient(b).patientName || \'-\' }}</div>',
      '              <div v-else class="bdo-name" :class="b.status === 2 ? \'off\' : \'blk\'">{{ b.status === 2 ? \'停用\' : \'空床\' }}</div>',
      '              <div class="bdo-diag">{{ bedPatient(b) ? (bedPatient(b).admitDiag || \'-\') : blankSub(b) }}</div>',
      '              <div v-if="bedPatient(b)" class="bdo-tags">',
      '                <span v-for="(tg, i) in tags(b)" :key="i" class="bdo-tag" :class="tg.c">{{ tg.t }}</span>',
      '              </div>',
      '              <div v-if="bedPatient(b)" class="bdo-foot">',
      '                <span>医·{{ bedPatient(b).doctorName || \'待分配\' }}</span>',
      '                <span>护·{{ bedPatient(b).nurseName || \'待指派\' }}</span>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </el-tooltip>',
      '      </div>',
      '    </section>',
      '  </div>',
      '  </template>',
      '  <div v-else-if="viewMode === \'slim\'" class="bdo-slims">',
      '    <div v-for="(b, si) in filteredBeds" :key="si" class="bdo-slim" :class="{ \'is-active\': isActive(b), \'bdo-search-hit\': isHit(b) }"',
      '         :style="{ borderLeftColor: barColor(b) }" :draggable="b.status === 1"',
      '         @click="onCard(b)" @dblclick="onCardDbl(b)" @contextmenu.prevent="openCtx(b, $event)"',
      '         @dragstart="dragStart(b, $event)" @dragend="dragEnd" @dragover.prevent="dragOver(b, $event)" @dragleave="dragLeave(b)" @drop.prevent="onDrop(b)">',
      '      <span class="no">{{ b.bedNo || \'-\' }}</span>',
      '      <span class="who">{{ bedPatient(b) ? bedPatient(b).patientName : dictBedStatus[b.status] }}</span>',
      '      <span style="width:120px;flex:none;font-size:12px;color:var(--yb-ink-3);">{{ bedPatient(b) ? genderText(bedPatient(b)) + (bedPatient(b).age != null ? \' · \' + bedPatient(b).age + \'岁\' : \'\') : (b.roomNo ? b.roomNo + \'房\' : \'\') }}</span>',
      '      <span class="diag">{{ bedPatient(b) ? (bedPatient(b).admitDiag || \'-\') : blankSub(b) }}</span>',
      '      <span v-if="bedPatient(b)" class="tags"><span v-for="(tg, i) in tags(b)" :key="i" class="bdo-tag" :class="tg.c">{{ tg.t }}</span></span>',
      '      <el-select v-if="bedPatient(b) && emptyBeds.length" v-model="quickBedMap[idKey(b.id)]" size="small" placeholder="转至空床" clearable filterable style="width:110px;flex:none;" @change="quickTransfer(b, $event)">',
      '        <el-option v-for="eb in emptyBeds" :key="eb.id" :label="(eb.bedNo || \'-\') + \'床\'" :value="idKey(eb.id)"></el-option>',
      '      </el-select>',
      '    </div>',
      '  </div>',
      '  <el-table v-else :data="filteredBeds" size="small" border stripe max-height="560" @row-click="rowClick" @row-contextmenu="rowCtx">',
      '    <el-table-column label="床号" width="70" align="center"><template #default="s">{{ s.row.bedNo || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="房间" width="80" align="center"><template #default="s">{{ s.row.roomNo || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="状态" width="70" align="center"><template #default="s"><el-tag size="small" :type="s.row.status === 0 ? \'success\' : (s.row.status === 1 ? \'primary\' : \'info\')">{{ dictBedStatus[s.row.status] }}</el-tag></template></el-table-column>',
      '    <el-table-column label="类型" width="60" align="center"><template #default="s">{{ dictBedType[s.row.bedType] || \'-\' }}</template></el-table-column>',
      '    <el-table-column label="患者" width="90"><template #default="s">{{ bedPatient(s.row) ? bedPatient(s.row).patientName : \'-\' }}</template></el-table-column>',
      '    <el-table-column label="住院号" width="120"><template #default="s">{{ bedPatient(s.row) ? (bedPatient(s.row).inpNo || \'-\') : \'-\' }}</template></el-table-column>',
      '    <el-table-column label="诊断" min-width="150" show-overflow-tooltip><template #default="s">{{ bedPatient(s.row) ? (bedPatient(s.row).admitDiag || \'-\') : blankSub(s.row) }}</template></el-table-column>',
      '    <el-table-column label="护理等级" width="90" align="center"><template #default="s">{{ bedPatient(s.row) ? legendDicts.lv[bedPatient(s.row).nursingLevel] || \'-\' : \'-\' }}</template></el-table-column>',
      '    <el-table-column label="病情" width="70" align="center"><template #default="s">{{ bedPatient(s.row) ? (s.row.status === 1 ? (legendDicts.lv[bedPatient(s.row).conditionLevel] || \'-\') : \'-\') : \'-\' }}</template></el-table-column>',
      '    <el-table-column label="主管医护" width="130"><template #default="s">{{ bedPatient(s.row) ? ((bedPatient(s.row).doctorName || \'待分配\') + \' / \' + (bedPatient(s.row).nurseName || \'待指派\')) : \'-\' }}</template></el-table-column>',
      '  </el-table>',
      '  <el-empty v-if="!loading && !filteredBeds.length" description="本病区暂无床位(可在床位管理中维护)" :image-size="60"></el-empty>',
      '  <!-- 床卡右键菜单 -->',
      '  <div v-if="ctx.show" class="bctx" :style="{ left: ctx.x + \'px\', top: ctx.y + \'px\' }" @click.stop>',
      '    <template v-if="ctx.bed && bedPatient(ctx.bed)">',
      '      <div class="bctx-item" @click="ctxPick">选中患者</div>',
      '      <div class="bctx-item" @click="ctxOpenOrders">直达医嘱审核</div>',
      '      <div class="bctx-sep"></div>',
      '      <div class="bctx-item" @click="ctxFlow(\'onward\')">发起转科/流转申请</div>',
      '      <div class="bctx-item" @click="ctxFlow(\'approval\')">流转审批中心</div>',
      '      <div class="bctx-sep"></div>',
      '      <div class="bctx-item" @click="ctxWristband">打印腕带</div>',
      '    </template>',
      '    <div v-else class="bctx-item" @click="ctxFlow(\'preadmit\')">查看待入区</div>',
      '  </div>',
      '  <!-- 图例抽屉 -->',
      '  <el-drawer v-model="legendOpen" title="图例说明" size="320px" :append-to-body="true">',
      '    <div class="bdo-legend">',
      '      <div class="bdo-legend-row"><span class="bdo-legend-sw" style="background:var(--yb-success);"></span>空床</div>',
      '      <div class="bdo-legend-row"><span class="bdo-legend-sw" style="background:var(--yb-link);"></span>占用(无患者匹配/色条兜底)</div>',
      '      <div class="bdo-legend-row"><span class="bdo-legend-sw" style="background:var(--yb-ink-disabled);"></span>停用床位</div>',
      '      <div class="bdo-legend-h" style="margin-top:8px;font-weight:700;">{{ legendDicts.lvName }}色条</div>',
      '      <div v-for="(t, i) in [1,2,3,4]" :key="\'lv\'+i" class="bdo-legend-row" v-show="legendDicts.lv[t]">',
      '        <span class="bdo-legend-sw" :style="{ background: [\'var(--yb-danger)\',\'#E6A23C\',\'#d4a017\',\'var(--yb-success)\'][t-1] }"></span>{{ legendDicts.lv[t] }}',
      '      </div>',
      '      <div class="bdo-legend-h" style="margin-top:8px;font-weight:700;">床位类型徽标</div>',
      '      <div class="bdo-legend-row"><span class="bdo-badge bdo-b-red">抢救</span>{{ dictBedType[2] }}床位</div>',
      '      <div class="bdo-legend-row"><span class="bdo-badge bdo-b-blu">监护</span>{{ dictBedType[3] }}床位</div>',
      '      <div class="bdo-legend-row"><span class="bdo-badge bdo-b-grn">隔离</span>{{ dictBedType[4] }}床位</div>',
      '      <div class="bdo-legend-row"><span class="bdo-badge bdo-b-org">加床</span>临时加床</div>',
      '      <div class="bdo-legend-h" style="margin-top:8px;font-weight:700;">患者标签</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-blu">新入</span>今日入院</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-red">过敏</span>有过敏史</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-red">隔离</span>隔离患者/隔离床</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-pur">术后N天</span>近期手术</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-org">T38.5</span>发热(末次体温)</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-org">压疮高危</span>Braden ≤ 14</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-org">跌倒高危</span>Morse ≥ 25</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-grn">预出院</span>预计出院日不晚于今日</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-org">审N</span>待审核医嘱 N 条</div>',
      '      <div class="bdo-legend-row"><span class="bdo-tag bdo-t-blu">执N</span>待执行医嘱 N 条</div>',
      '      <div style="color:var(--yb-ink-4);font-size:12px;margin-top:10px;">操作: 单击选中 · 双击直达医嘱 · 右键更多 · 拖占用床到空床=转床(立即), 到占用床=换床(两条申请待审批)</div>',
      '    </div>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };

  /* 十六进制色透明度(6位hex + alpha → 8位hex): 风险等级胶囊与趋势图风险带取后端分级色 */
  function hexAlpha(hex, a) {
    var c = String(hex || '#409EFF').replace('#', '');
    if (c.length === 3) { c = c.charAt(0) + c.charAt(0) + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2); }
    var al = Math.round(Math.max(0, Math.min(1, a)) * 255).toString(16);
    if (al.length < 2) { al = '0' + al; }
    return '#' + c + al;
  }

  /* 量表类型(与后端 HisNursingScaleDef.scaleType 一致): 1入院评估 2专科 3风险 */
  var SCALE_TYPE = { 1: '入院评估', 2: '专科评估', 3: '风险评估' };
  var SCALE_TYPE_TAG = { 1: 'info', 2: 'primary', 3: 'warning' };

  /* ================= 7. 护理评估量表工作台(三栏: 量表选择 / 动态评估表单 / 最新结果·趋势·历史; 护士站页签局部注册, 不挂 HIS.views) ================= */
  var InpNursingAssessment = {
    name: 'InpNursingAssessment',
    props: { visitId: { type: [String, Number], default: null } },
    data: function () {
      return {
        loadingScales: false, scales: [],
        activeCode: '', detail: null, loadingDetail: false,
        answers: {}, submitting: false,
        history: [], trend: [], loadingHis: false, chart: null
      };
    },
    computed: {
      dims: function () { return (this.detail && this.detail.dims) || []; },
      interps: function () { return (this.detail && this.detail.interps) || []; },
      activeScale: function () {
        var code = this.activeCode;
        var list = this.scales || [];
        for (var i = 0; i < list.length; i++) {
          if (list[i].scaleCode === code) { return list[i]; }
        }
        return (this.detail && this.detail.rec) || null;
      },
      /* 实时总分 = 各维度已选选项分值累加 */
      liveTotal: function () {
        var s = 0;
        for (var i = 0; i < this.dims.length; i++) { s += this.dimScore(this.dims[i]); }
        return s;
      },
      liveLevel: function () { return this.matchLevel(this.liveTotal); },
      /* 全部维度作答后方可提交(与后端逐维度校验口径一致) */
      canSubmit: function () {
        if (!this.visitId) { return false; }
        if (!this.dims.length) { return false; }
        for (var i = 0; i < this.dims.length; i++) {
          var d = this.dims[i], v = this.answers[d.key];
          if (d.multi) {
            if (!v || !v.length) { return false; }
          } else if (v == null || v === '') { return false; }
        }
        return true;
      },
      /* 最新评估(历史倒序首条) */
      latest: function () {
        var rows = this.history || [];
        if (!rows.length) { return null; }
        var o = parseJson(rows[0].content) || {};
        return {
          score: rows[0].scaleScore == null ? (o.score == null ? '-' : o.score) : rows[0].scaleScore,
          level: o.level, color: o.color, scaleName: o.scaleName,
          time: fmtTime(rows[0].recordTime || rows[0].createTime),
          by: rows[0].createBy || '-'
        };
      }
    },
    watch: {
      visitId: function () {
        this.resetAnswers();
        this.disposeChart();
        if (this.activeCode) { this.loadHistory(); }
      }
    },
    created: function () {
      window.addEventListener('resize', this.onResize);
      this.loadScales();
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.onResize);
      this.disposeChart();
    },
    methods: {
      fmtTime: fmtTime,
      typeLabel: function (v) { return v == null ? '评估' : (SCALE_TYPE[v] || '评估'); },
      typeTag: function (v) { return SCALE_TYPE_TAG[v] || 'info'; },
      levelStyle: function (lv) {
        if (!lv || !lv.color) { return {}; }
        return { color: lv.color, background: hexAlpha(lv.color, 0.1), border: '1px solid ' + hexAlpha(lv.color, 0.38) };
      },
      hInfo: function (row) { return parseJson(row ? row.content : null) || {}; },
      hTime: function (row) {
        var t = fmtTime(row ? (row.recordTime || row.createTime) : null);
        return t === '-' ? '-' : t.substring(5, 16);
      },
      levelTagType: function (color) {
        var c = String(color || '').toUpperCase();
        if (c === '#F56C6C' || c === '#C74F4F') { return 'danger'; }
        if (c === '#E6A23C' || c === '#A26B1B') { return 'warning'; }
        if (c === '#67C23A' || c === '#3C862D') { return 'success'; }
        if (c === '#409EFF' || c === '#2C78C7' || c === '#1A5C9E') { return 'primary'; }
        return 'info';
      },
      /* 重置作答(多选维度预置空数组, 保证 el-checkbox-group 受控) */
      resetAnswers: function () {
        var a = {};
        for (var i = 0; i < this.dims.length; i++) {
          if (this.dims[i].multi) { a[this.dims[i].key] = []; }
        }
        this.answers = a;
      },
      /* 单维度得分: 单选取选中选项分值; 多选按已勾选选项分值累加 */
      dimScore: function (dim) {
        var v = this.answers[dim.key], opts = dim.options || [], i;
        if (dim.multi) {
          if (!v || !v.length) { return 0; }
          var s = 0;
          v.forEach(function (label) {
            for (var j = 0; j < opts.length; j++) {
              if (opts[j].label === label) { s += Number(opts[j].score) || 0; break; }
            }
          });
          return s;
        }
        for (i = 0; i < opts.length; i++) {
          if (opts[i].label === v) { return Number(opts[i].score) || 0; }
        }
        return 0;
      },
      dimAnswered: function (dim) {
        var v = this.answers[dim.key];
        return dim.multi ? !!(v && v.length) : (v != null && v !== '');
      },
      dimLiveText: function (dim) {
        return this.dimAnswered(dim) ? (this.dimScore(dim) + ' 分') : '—';
      },
      /* 总分命中分级区间(min/max 含端点, 与后端 matchLevel 同口径) */
      matchLevel: function (t) {
        var arr = this.interps || [];
        for (var i = 0; i < arr.length; i++) {
          var seg = arr[i];
          var lo = seg.min == null ? 0 : seg.min;
          var hi = seg.max == null ? 9999 : seg.max;
          if (t >= lo && t <= hi) { return seg; }
        }
        return null;
      },
      loadScales: function () {
        var vm = this;
        vm.loadingScales = true;
        HIS.get('/api/his/inp/nursing-scale/list').then(function (rows) {
          vm.scales = rows || [];
          if (!vm.scales.length) { return; }
          var target = vm.scales[0];
          for (var i = 0; i < vm.scales.length; i++) {
            if (vm.scales[i].scaleCode === vm.activeCode) { target = vm.scales[i]; break; }
          }
          vm.selectScale(target);
        }).catch(HIS.notifyError).finally(function () { vm.loadingScales = false; });
      },
      selectScale: function (s) {
        var vm = this;
        if (!s || !s.scaleCode) { return; }
        if (vm.activeCode === s.scaleCode && vm.detail) { return; }
        if (vm.activeCode !== s.scaleCode) { vm.disposeChart(); }
        vm.activeCode = s.scaleCode;
        vm.detail = null;
        vm.resetAnswers();
        vm.loadingDetail = true;
        HIS.get('/api/his/inp/nursing-scale/' + encodeURIComponent(s.scaleCode)).then(function (d) {
          var dms = parseJson(d && d.dimensions);
          var its = parseJson(d && d.scoreInterpretation);
          vm.detail = { rec: d, dims: Array.isArray(dms) ? dms : [], interps: Array.isArray(its) ? its : [] };
          vm.resetAnswers();
          vm.loadHistory();
        }).catch(HIS.notifyError).finally(function () { vm.loadingDetail = false; });
      },
      loadHistory: function () {
        var vm = this;
        if (!vm.visitId || !vm.activeCode) { vm.history = []; vm.trend = []; vm.disposeChart(); return; }
        vm.loadingHis = true;
        var q = '?visitId=' + HIS.idParam(vm.visitId) + '&scaleCode=' + encodeURIComponent(vm.activeCode);
        Promise.all([
          HIS.get('/api/his/inp/nursing-scale/history' + q).catch(function () { return []; }),
          HIS.get('/api/his/inp/nursing-scale/trend' + q).catch(function () { return []; })
        ]).then(function (rs) {
          vm.history = rs[0] || [];
          vm.trend = rs[1] || [];
          vm.$nextTick(vm.renderTrend);
        }).finally(function () { vm.loadingHis = false; });
      },
      disposeChart: function () {
        if (this.chart && !this.chart.isDisposed()) { this.chart.dispose(); }
        this.chart = null;
      },
      onResize: function () { if (this.chart && !this.chart.isDisposed()) { this.chart.resize(); } },
      /* 趋势折线 + 按 score_interpretation 叠加风险区间背景带(markArea) */
      renderTrend: function () {
        var vm = this;
        var el = vm.$refs.trendChart;
        if (!el || !el.clientWidth) { return; }
        if (!vm.chart || vm.chart.isDisposed()) { vm.chart = echarts.init(el, 'yb'); }
        var x = [], y = [], maxS = 0;
        (vm.trend || []).forEach(function (p) {
          var t = String(p.time || '').replace('T', ' ');
          x.push(t.length >= 16 ? t.substring(5, 16) : t);
          var s = p.score == null ? null : Number(p.score);
          y.push(s);
          if (s != null && s > maxS) { maxS = s; }
        });
        var areas = [];
        (vm.interps || []).forEach(function (seg) {
          var lo = seg.min == null ? 0 : seg.min;
          var hi = seg.max == null ? Math.max(maxS + 1, lo + 1) : seg.max;
          if (hi <= lo) { hi = lo + 1; }
          areas.push([
            { yAxis: lo, itemStyle: { color: hexAlpha(seg.color || '#409EFF', 0.13) }, label: { show: true, position: 'insideTopRight', color: seg.color || '#409EFF', fontSize: 10, formatter: seg.level || '' } },
            { yAxis: hi }
          ]);
        });
        vm.chart.setOption({
          tooltip: { trigger: 'axis' },
          grid: { left: 34, right: 14, top: 14, bottom: 22 },
          xAxis: { type: 'category', boundaryGap: false, data: x },
          yAxis: { type: 'value', name: '评分', min: 0, minInterval: 1 },
          series: [{
            name: '评分', type: 'line', symbolSize: 6, data: y,
            itemStyle: { color: HIS.theme.brand }, lineStyle: { width: 2 },
            areaStyle: { opacity: 0.06 },
            markArea: { silent: true, data: areas }
          }]
        }, true);
        vm.chart.resize();
      },
      submit: function () {
        var vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先在左侧患者列表中选择患者'); return; }
        if (!vm.canSubmit) { ElementPlus.ElMessage.warning('请完成全部维度作答后再提交'); return; }
        var answers = {};
        vm.dims.forEach(function (d) {
          var v = vm.answers[d.key];
          if (d.multi) { if (v && v.length) { answers[d.key] = v.slice(); } }
          else if (v != null && v !== '') { answers[d.key] = v; }
        });
        vm.submitting = true;
        HIS.post('/api/his/inp/nursing-scale/assess', {
          inpVisitId: HIS.id(vm.visitId),
          scaleCode: vm.activeCode,
          scaleDetail: JSON.stringify(answers)
        }).then(function (rec) {
          var score = rec && rec.scaleScore != null ? rec.scaleScore : vm.liveTotal;
          var lv = vm.liveLevel;
          var msg = '评估已提交: ' + score + ' 分' + (lv ? ' · ' + lv.level : '');
          if (rec && rec.planTemplateId) { ElementPlus.ElMessage.success(msg + ', 已按风险区间自动创建护理计划'); }
          else { HIS.notifySuccess(msg); }
          vm.loadHistory();
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      }
    },
    template: [
      '<div class="ns-wrap">',
      '  <div class="ns-left">',
      '    <el-empty v-if="!loadingScales && !scales.length" description="暂无启用量表" :image-size="50"></el-empty>',
      '    <div v-for="s in scales" :key="s.scaleCode" class="ns-scale-card" :class="{ \'is-active\': s.scaleCode === activeCode }" @click="selectScale(s)">',
      '      <div class="t">{{ s.scaleName }}</div>',
      '      <div class="sub">',
      '        <span>{{ s.requiredFrequency || \'标准量表\' }}</span>',
      '        <el-tag size="small" :type="typeTag(s.scaleType)">{{ typeLabel(s.scaleType) }}</el-tag>',
      '      </div>',
      '    </div>',
      '  </div>',
      '  <div class="ns-main">',
      '    <el-empty v-if="!activeCode" description="请选择左侧量表开始评估" :image-size="70"></el-empty>',
      '    <template v-else>',
      '      <div class="inp-panel" v-loading="loadingDetail">',
      '        <el-alert v-if="!visitId" type="warning" :closable="false" show-icon',
      '            title="请先在左侧患者列表中选择一位患者后再进行评估" style="margin-bottom:12px;">',
      '        </el-alert>',
      '        <h4>{{ activeScale ? activeScale.scaleName : activeCode }}<span v-if="activeScale && activeScale.requiredFrequency" class="ns-desc">{{ activeScale.requiredFrequency }}</span></h4>',
      '        <div v-for="dim in dims" :key="dim.key" class="ns-dim">',
      '          <div class="ns-dim-label">{{ dim.name }}<span v-if="dim.multi" class="ns-multi">多选</span></div>',
      '          <div class="ns-dim-opts">',
      '            <el-checkbox-group v-if="dim.multi" v-model="answers[dim.key]">',
      '              <el-checkbox v-for="opt in (dim.options || [])" :key="opt.label" :label="opt.label">{{ opt.label }}<span class="ns-score">({{ opt.score }}分)</span></el-checkbox>',
      '            </el-checkbox-group>',
      '            <el-radio-group v-else v-model="answers[dim.key]">',
      '              <el-radio v-for="opt in (dim.options || [])" :key="opt.label" :label="opt.label">{{ opt.label }}<span class="ns-score">({{ opt.score }}分)</span></el-radio>',
      '            </el-radio-group>',
      '          </div>',
      '          <div class="ns-dim-score" :class="{ \'is-zero\': dimScore(dim) === 0 }">{{ dimLiveText(dim) }}</div>',
      '        </div>',
      '        <el-empty v-if="!loadingDetail && !dims.length" description="该量表暂无维度定义" :image-size="50"></el-empty>',
      '      </div>',
      '      <div class="ns-total">',
      '        <span style="color:var(--yb-ink-2);font-weight:600;">实时总分</span>',
      '        <span class="num">{{ liveTotal }}</span>',
      '        <span style="color:var(--yb-ink-3);">分</span>',
      '        <span v-if="liveLevel" class="ns-level" :style="levelStyle(liveLevel)">{{ liveLevel.level }}</span>',
      '        <span style="flex:1;"></span>',
      '        <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">提交评估</el-button>',
      '      </div>',
      '    </template>',
      '  </div>',
      '  <div class="ns-right">',
      '    <div class="inp-panel ns-latest">',
      '      <h4>最新评估</h4>',
      '      <template v-if="latest">',
      '        <div style="display:flex;align-items:baseline;gap:8px;">',
      '          <span class="score" :style="{ color: latest.color || \'var(--yb-brand-strong)\' }">{{ latest.score }}</span>',
      '          <span style="color:var(--yb-ink-3);">分</span>',
      '          <span v-if="latest.level" class="ns-level" :style="levelStyle(latest)">{{ latest.level }}</span>',
      '        </div>',
      '        <div style="color:var(--yb-ink-3);font-size:12px;margin-top:6px;">{{ latest.scaleName || (activeScale ? activeScale.scaleName : \'\') }} · {{ latest.time }} · {{ latest.by }}</div>',
      '      </template>',
      '      <el-empty v-else description="暂无评估记录" :image-size="50"></el-empty>',
      '    </div>',
      '    <div class="inp-panel">',
      '      <h4>评分趋势</h4>',
      '      <div v-show="trend.length" ref="trendChart" class="ns-trend"></div>',
      '      <el-empty v-if="!trend.length" description="完成评估后生成趋势曲线" :image-size="50"></el-empty>',
      '    </div>',
      '    <div class="inp-panel">',
      '      <h4>评估历史</h4>',
      '      <el-table :data="history" v-loading="loadingHis" size="small">',
      '        <el-table-column label="时间" width="78" align="center">',
      '          <template #default="s">{{ hTime(s.row) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="量表" min-width="46" show-overflow-tooltip>',
      '          <template #default="s">{{ hInfo(s.row).scaleName || s.row.scaleCode || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="评分" width="42" align="center">',
      '          <template #default="s"><b>{{ s.row.scaleScore == null ? \'-\' : s.row.scaleScore }}</b></template>',
      '        </el-table-column>',
      '        <el-table-column label="等级" min-width="58" align="center">',
      '          <template #default="s"><el-tag size="small" :type="levelTagType(hInfo(s.row).color)">{{ hInfo(s.row).level || \'-\' }}</el-tag></template>',
      '        </el-table-column>',
      '        <el-table-column label="评估人" min-width="48" show-overflow-tooltip>',
      '          <template #default="s">{{ s.row.createBy || \'-\' }}</template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!loadingHis && !history.length" description="暂无评估历史" :image-size="50"></el-empty>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* 护理计划状态(与后端 HisNursingPlanInstance.status 一致): 1执行中 2已评价 3已关闭 */
  var PLAN_STATUS = { 1: '执行中', 2: '已评价', 3: '已关闭' };
  var PLAN_STATUS_TAG = { 1: 'primary', 2: 'success', 3: 'info' };

  /* ================= 8. 护理计划管理(执行中计划卡片 + 历史表格 + 新建计划对话框; 护士站页签局部注册, 不挂 HIS.views) ================= */
  var InpNursingPlan = {
    name: 'InpNursingPlan',
    props: {
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    components: { 'ns-icon': NsIcon },
    data: function () {
      return {
        loading: false, plans: [],
        ivVisible: false, ivPlan: null, ivText: '', recording: false,
        evalVisible: false, evalPlan: null, evalText: '', evaluating: false,
        createVisible: false, createTab: 'tpl',
        scales: [], tplScaleCode: '', tplList: [], loadingTpl: false, tplId: null, tplDetail: null,
        mDia: '', mGoal: '', mIvs: [''], creating: false
      };
    },
    computed: {
      activePlans: function () {
        return (this.plans || []).filter(function (p) { return p.status === 1; });
      },
      /* 历史计划 = 已评价(status=2) + 已关闭(status=3) */
      donePlans: function () {
        return (this.plans || []).filter(function (p) { return p.status !== 1; });
      }
    },
    watch: {
      visitId: function () { this.resetCreate(); this.load(); }
    },
    created: function () { this.load(); },
    methods: {
      fmtTime: fmtTime,
      statusLabel: function (v) { return v == null ? '-' : (PLAN_STATUS[v] || v); },
      statusTag: function (v) { return PLAN_STATUS_TAG[v] || 'info'; },
      /* 计划措施 JSON 解析: 字符串数组或 {text|intervention|content|name} 对象数组统一为文本数组 */
      plannedOf: function (p) {
        var arr = parseJson(p ? p.plannedInterventions : null);
        if (!Array.isArray(arr)) { return []; }
        return arr.map(function (t) {
          if (t == null) { return ''; }
          if (typeof t === 'string') { return t; }
          return String(t.text || t.intervention || t.content || t.name || '');
        }).filter(function (t) { return !!t; });
      },
      /* 实际措施 JSON 解析: [{time, intervention, nurse}] 归一化(兼容文本数组存量) */
      actualOf: function (p) {
        var arr = parseJson(p ? p.actualInterventions : null);
        if (!Array.isArray(arr)) { return []; }
        return arr.map(function (rec) {
          if (rec == null) { return { time: '', text: '', nurse: '' }; }
          if (typeof rec === 'string') { return { time: '', text: rec, nurse: '' }; }
          return {
            time: fmtTime(rec.time || rec.recordTime || rec.execTime),
            text: String(rec.intervention || rec.text || rec.content || rec.name || ''),
            nurse: String(rec.nurse || rec.executor || rec.by || rec.createBy || '')
          };
        });
      },
      /* 计划措施已执行判定(按措施文本命中实际措施) */
      ivDone: function (p, text) {
        var acts = this.actualOf(p);
        for (var i = 0; i < acts.length; i++) {
          if (acts[i].text === text) { return true; }
        }
        return false;
      },
      /* 勾选计划措施 = 直接记录一条实际措施 */
      tickIv: function (p, text) {
        if (this.ivDone(p, text)) { return; }
        this.recordIv(p, text);
      },
      load: function () {
        var vm = this;
        if (!vm.visitId) { vm.plans = []; return; }
        vm.loading = true;
        HIS.get('/api/his/inp/nursing-plan/list?visitId=' + HIS.idParam(vm.visitId)).then(function (rows) {
          vm.plans = rows || [];
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      openIv: function (p) {
        this.ivPlan = p;
        this.ivText = '';
        this.ivVisible = true;
      },
      /* 记录措施(preset 为卡片勾选传入的预设文本), 执行护士取当前登录用户, time 由后端自动补 */
      recordIv: function (p, preset) {
        var vm = this;
        var text = preset || (vm.ivText || '').trim();
        if (!text) { ElementPlus.ElMessage.warning('请填写措施内容'); return; }
        var u = HIS.getUser();
        vm.recording = true;
        HIS.post('/api/his/inp/nursing-plan/' + HIS.idParam(p.id) + '/intervention', {
          intervention: text,
          nurse: (u && (u.realName || u.username)) || ''
        }).then(function () {
          HIS.notifySuccess('措施已记录');
          vm.ivVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.recording = false; });
      },
      openEval: function (p) {
        this.evalPlan = p;
        this.evalText = '';
        this.evalVisible = true;
      },
      doEval: function () {
        var vm = this;
        var result = (vm.evalText || '').trim();
        if (!result) { ElementPlus.ElMessage.warning('请填写评价内容'); return; }
        vm.evaluating = true;
        HIS.put('/api/his/inp/nursing-plan/' + HIS.idParam(vm.evalPlan.id) + '/evaluate?result=' + encodeURIComponent(result))
          .then(function () {
            HIS.notifySuccess('计划已评价');
            vm.evalVisible = false;
            vm.load();
          }).catch(HIS.notifyError).finally(function () { vm.evaluating = false; });
      },
      closePlan: function (p) {
        var vm = this;
        ElementPlus.ElMessageBox.confirm('确认关闭该护理计划？关闭后不可再评价。', '关闭确认', {
          type: 'warning', confirmButtonText: '关闭计划', cancelButtonText: '取消'
        }).then(function () {
          return HIS.put('/api/his/inp/nursing-plan/' + HIS.idParam(p.id) + '/close');
        }).then(function () {
          HIS.notifySuccess('计划已关闭');
          vm.load();
        }).catch(function (e) {
          if (e === 'cancel' || e === 'close') { return; }
          HIS.notifyError(e);
        });
      },
      /* ---- 新建计划(从模板 / 手动) ---- */
      resetCreate: function () {
        this.createTab = 'tpl';
        this.tplScaleCode = ''; this.tplList = []; this.tplId = null; this.tplDetail = null;
        this.mDia = ''; this.mGoal = ''; this.mIvs = [''];
      },
      openCreate: function () {
        var vm = this;
        vm.resetCreate();
        vm.createVisible = true;
        if (!vm.scales.length) {
          HIS.get('/api/his/inp/nursing-scale/list').then(function (rows) {
            vm.scales = rows || [];
            if (!vm.tplScaleCode && vm.scales.length) { vm.tplScaleCode = vm.scales[0].scaleCode; vm.loadTpls(); }
          }).catch(HIS.notifyError);
        } else if (!vm.tplScaleCode) {
          vm.tplScaleCode = vm.scales[0].scaleCode;
          vm.loadTpls();
        }
      },
      loadTpls: function () {
        var vm = this;
        if (!vm.tplScaleCode) { vm.tplList = []; return; }
        vm.loadingTpl = true;
        vm.tplId = null; vm.tplDetail = null;
        HIS.get('/api/his/inp/nursing-plan/templates?scaleCode=' + encodeURIComponent(vm.tplScaleCode))
          .then(function (rows) {
            vm.tplList = (rows || []).filter(function (t) { return t.status !== 0; });
          }).catch(HIS.notifyError).finally(function () { vm.loadingTpl = false; });
      },
      onTplChange: function (id) {
        this.tplId = HIS.id(id);
        var list = this.tplList || [], hit = null;
        for (var i = 0; i < list.length; i++) { if (HIS.sameId(list[i].id, id)) { hit = list[i]; break; } }
        if (hit) { this.pickTpl(hit); } else { this.tplDetail = null; }
      },
      pickTpl: function (t) {
        var arr = parseJson(t.interventions);
        this.tplDetail = {
          nursingDiagnosis: t.nursingDiagnosis || '',
          nursingGoal: t.nursingGoal || '',
          interventions: (Array.isArray(arr) ? arr : []).map(function (x) {
            return typeof x === 'string' ? x : String(x.text || x.intervention || '');
          }).filter(function (x) { return !!x; }),
          evaluationCriteria: t.evaluationCriteria || ''
        };
      },
      addIvRow: function () { this.mIvs.push(''); },
      removeIvRow: function (i) {
        this.mIvs.splice(i, 1);
        if (!this.mIvs.length) { this.mIvs.push(''); }
      },
      doCreate: function () {
        var vm = this;
        if (!vm.visitId) { ElementPlus.ElMessage.warning('请先选择患者'); return; }
        var body = { inpVisitId: HIS.id(vm.visitId) };
        if (vm.createTab === 'tpl') {
          if (vm.tplId == null || !vm.tplDetail) { ElementPlus.ElMessage.warning('请先选择护理计划模板'); return; }
          body.templateId = HIS.id(vm.tplId);
        } else {
          var dia = (vm.mDia || '').trim();
          if (!dia) { ElementPlus.ElMessage.warning('请填写护理诊断'); return; }
          var ivs = (vm.mIvs || []).map(function (t) { return (t || '').trim(); }).filter(function (t) { return !!t; });
          if (!ivs.length) { ElementPlus.ElMessage.warning('请至少填写一条护理措施'); return; }
          body.nursingDiagnosis = dia;
          body.nursingGoal = (vm.mGoal || '').trim();
          body.plannedInterventions = JSON.stringify(ivs);
        }
        vm.creating = true;
        HIS.post('/api/his/inp/nursing-plan/', body).then(function () {
          HIS.notifySuccess('护理计划已创建');
          vm.createVisible = false;
          vm.load();
        }).catch(HIS.notifyError).finally(function () { vm.creating = false; });
      }
    },
    template: [
      '<div v-loading="loading">',
      '  <div class="toolbar" style="margin-bottom:10px;">',
      '    <span style="font-weight:600;color:var(--yb-ink-1);">{{ patient && patient.patientName ? patient.patientName : \'\' }} · 护理计划</span>',
      '    <el-tag v-if="activePlans.length" size="small" type="primary" style="margin-left:8px;">执行中 {{ activePlans.length }}</el-tag>',
      '    <span style="flex:1;"></span>',
      '    <el-button type="primary" size="small" :disabled="!visitId" @click="openCreate">新建计划</el-button>',
      '    <el-button size="small" @click="load">刷新</el-button>',
      '  </div>',
      '  <el-empty v-if="!visitId" description="请先在左侧患者列表中选择患者" :image-size="70"></el-empty>',
      '  <template v-else>',
      '    <div class="inp-panel">',
      '      <h4>执行中计划({{ activePlans.length }})</h4>',
      '      <div v-for="p in activePlans" :key="p.id" class="inp-panel np-card">',
      '        <div class="np-head">',
      '          <span class="diag">{{ p.nursingDiagnosis || \'护理计划\' }}</span>',
      '          <el-tag size="small" :type="statusTag(p.status)">{{ statusLabel(p.status) }}</el-tag>',
      '          <span style="color:var(--yb-ink-3);font-size:12px;">开始 {{ fmtTime(p.startTime) }}</span>',
      '          <span style="flex:1;"></span>',
      '          <el-tooltip content="记录措施" placement="top">',
      '            <span class="ns-ic is-primary" @click="openIv(p)"><ns-icon name="edit"></ns-icon></span>',
      '          </el-tooltip>',
      '          <el-tooltip content="评价计划" placement="top">',
      '            <span class="ns-ic is-success" @click="openEval(p)"><ns-icon name="check"></ns-icon></span>',
      '          </el-tooltip>',
      '          <el-tooltip content="关闭计划" placement="top">',
      '            <span class="ns-ic is-warning" @click="closePlan(p)"><ns-icon name="close"></ns-icon></span>',
      '          </el-tooltip>',
      '        </div>',
      '        <div v-if="p.nursingGoal" class="np-goal">目标: {{ p.nursingGoal }}</div>',
      '        <div class="np-sec">',
      '          <div class="cap">计划措施(勾选即记录执行)</div>',
      '          <div class="np-ivs">',
      '            <div v-for="(t, i) in plannedOf(p)" :key="i" class="np-iv" :class="{ \'done\': ivDone(p, t) }">',
      '              <el-checkbox :model-value="ivDone(p, t)" :disabled="ivDone(p, t)" @change="tickIv(p, t)"></el-checkbox>',
      '              <span style="padding-top:2px;">{{ t }}</span>',
      '            </div>',
      '            <span v-if="!plannedOf(p).length" style="color:var(--yb-ink-4);font-size:12px;">暂无计划措施</span>',
      '          </div>',
      '        </div>',
      '        <div v-if="actualOf(p).length" class="np-sec">',
      '          <div class="cap">已执行措施({{ actualOf(p).length }})</div>',
      '          <div>',
      '            <div v-for="(a, ai) in actualOf(p)" :key="ai" class="np-act">',
      '              <span class="tm">{{ a.time }}</span>',
      '              <span class="txt">{{ a.text }}</span>',
      '              <span class="by">{{ a.nurse ? a.nurse + \' 执行\' : \'\' }}</span>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '      <el-empty v-if="!activePlans.length" description="暂无执行中的护理计划" :image-size="60"></el-empty>',
      '    </div>',
      '    <div class="inp-panel">',
      '      <h4>历史计划(已评价/已关闭)</h4>',
      '      <el-table :data="donePlans" border stripe size="small">',
      '        <el-table-column label="护理诊断" min-width="150" show-overflow-tooltip>',
      '          <template #default="s">{{ s.row.nursingDiagnosis || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="目标" min-width="170" show-overflow-tooltip>',
      '          <template #default="s">{{ s.row.nursingGoal || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="开始时间" width="150" align="center">',
      '          <template #default="s">{{ fmtTime(s.row.startTime) }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="评价时间" width="150" align="center">',
      '          <template #default="s">{{ s.row.evaluationTime ? fmtTime(s.row.evaluationTime) : \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="评价结果" min-width="150" show-overflow-tooltip>',
      '          <template #default="s">{{ s.row.evaluationResult || \'-\' }}</template>',
      '        </el-table-column>',
      '        <el-table-column label="状态" width="80" align="center">',
      '          <template #default="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template>',
      '        </el-table-column>',
      '        <el-table-column label="操作" width="76" align="center">',
      '          <template #default="s">',
      '            <el-tooltip v-if="s.row.status === 2" content="关闭计划" placement="top">',
      '              <span class="ns-ic is-warning" @click="closePlan(s.row)"><ns-icon name="close"></ns-icon></span>',
      '            </el-tooltip>',
      '            <span v-else style="color:var(--yb-ink-4);">-</span>',
      '          </template>',
      '        </el-table-column>',
      '      </el-table>',
      '      <el-empty v-if="!donePlans.length" description="暂无历史计划" :image-size="60"></el-empty>',
      '    </div>',
      '  </template>',
      '  <el-dialog v-model="ivVisible" :title="ivPlan ? (\'记录措施 · \' + (ivPlan.nursingDiagnosis || \'\')) : \'记录措施\'" width="460px">',
      '    <el-input type="textarea" v-model="ivText" :rows="3" placeholder="如: 每2小时协助翻身一次, 骶尾部减压"></el-input>',
      '    <template #footer>',
      '      <el-button @click="ivVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="recording" @click="recordIv(ivPlan)">确认记录</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="evalVisible" :title="evalPlan ? (\'评价计划 · \' + (evalPlan.nursingDiagnosis || \'\')) : \'评价计划\'" width="460px">',
      '    <div style="margin-bottom:6px;">',
      '      <el-button size="small" @click="evalText = \'目标达成\'">目标达成</el-button>',
      '      <el-button size="small" @click="evalText = \'部分达成\'">部分达成</el-button>',
      '      <el-button size="small" @click="evalText = \'未达成\'">未达成</el-button>',
      '    </div>',
      '    <el-input type="textarea" v-model="evalText" :rows="3" placeholder="评价内容(必填), 如: 患者住院期间未发生压疮, 目标达成"></el-input>',
      '    <template #footer>',
      '      <el-button @click="evalVisible = false">取消</el-button>',
      '      <el-button type="success" :loading="evaluating" @click="doEval">提交评价</el-button>',
      '    </template>',
      '  </el-dialog>',
      '  <el-dialog v-model="createVisible" title="新建护理计划" width="640px">',
      '    <el-tabs v-model="createTab">',
      '      <el-tab-pane label="从模板创建" name="tpl">',
      '        <el-form label-width="88px">',
      '          <el-form-item label="量表类型">',
      '            <el-select v-model="tplScaleCode" style="width:240px;" @change="loadTpls">',
      '              <el-option v-for="s in scales" :key="s.scaleCode" :label="s.scaleName" :value="s.scaleCode"></el-option>',
      '            </el-select>',
      '          </el-form-item>',
      '          <el-form-item label="计划模板">',
      '            <el-select v-model="tplId" placeholder="选择模板" style="width:100%;" :loading="loadingTpl" @change="onTplChange">',
      '              <el-option v-for="t in tplList" :key="t.id" :label="t.planName + (t.triggerScoreRange ? \' (\' + t.triggerScoreRange + \')\': \'\')" :value="t.id"></el-option>',
      '            </el-select>',
      '            <div v-if="!loadingTpl && tplScaleCode && !tplList.length" style="color:var(--yb-ink-4);font-size:12px;margin-top:4px;">该量表暂无启用模板, 可切到「手动创建」</div>',
      '          </el-form-item>',
      '        </el-form>',
      '        <div v-if="tplDetail" class="np-goal" style="margin-top:0;">',
      '          <div><b>护理诊断:</b> {{ tplDetail.nursingDiagnosis || \'-\' }}</div>',
      '          <div style="margin-top:4px;"><b>护理目标:</b> {{ tplDetail.nursingGoal || \'-\' }}</div>',
      '          <div style="margin-top:4px;"><b>计划措施:</b></div>',
      '          <div v-for="(t, i) in tplDetail.interventions" :key="i" style="margin-left:12px;">· {{ t }}</div>',
      '          <div v-if="tplDetail.evaluationCriteria" style="margin-top:4px;"><b>评价标准:</b> {{ tplDetail.evaluationCriteria }}</div>',
      '        </div>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="手动创建" name="manual">',
      '        <el-form label-width="88px">',
      '          <el-form-item label="护理诊断">',
      '            <el-input v-model="mDia" placeholder="如: 有皮肤完整性受损的危险"></el-input>',
      '          </el-form-item>',
      '          <el-form-item label="护理目标">',
      '            <el-input type="textarea" v-model="mGoal" :rows="2" placeholder="如: 住院期间不发生压疮"></el-input>',
      '          </el-form-item>',
      '          <el-form-item label="计划措施">',
      '            <div style="width:100%;">',
      '              <div v-for="(t, i) in mIvs" :key="i" class="np-row-input">',
      '                <el-input v-model="mIvs[i]" placeholder="措施内容"></el-input>',
      '                <el-button link type="danger" :disabled="mIvs.length <= 1" @click="removeIvRow(i)">删除</el-button>',
      '              </div>',
      '              <el-button size="small" @click="addIvRow">+ 添加措施</el-button>',
      '            </div>',
      '          </el-form-item>',
      '        </el-form>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '    <template #footer>',
      '      <el-button @click="createVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="creating" @click="doCreate">创建计划</el-button>',
      '    </template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 1. 护士站主页(病区患者名单 + 待办概览 + 工作页签) ================= */

  /* 当前登录用户 staffId(用于转科取消仅本人可见, 与后端 currentStaffId 同源自 sys_user.staff_id) */
  function currentStaffId() {
    try {
      var u = JSON.parse(localStorage.getItem('yb_his_user') || '{}');
      return u && u.staffId != null ? String(u.staffId) : null;
    } catch (e) { return null; }
  }

  /* 危险操作二次确认(项目无 HIS.confirmDanger, 统一走 ElementPlus.ElMessageBox): title 为标题, message 为正文 */
  function confirmBox(title, message) {
    return ElementPlus.ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' });
  }

  /* ================= 入出转管理 InpFlowPanel(13.15.2: 待入区/申请审批/在区流转) ================= */
  var InpFlowPanel = {
    name: 'InpFlowPanel',
    props: {
      wardId: { type: [String, Number], default: null },
      wards: { type: Array, default: function () { return []; } },
      patients: { type: Array, default: function () { return []; } },
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null },
      initialSeg: { type: String, default: 'preadmit' }
    },
    emits: ['changed', 'pick'],
    data: function () {
      return {
        seg: this.initialSeg || 'preadmit',
        loadingPA: false, preAdmits: [],
        loadingAP: false, apps: [], apStatus: 1, apPage: 1, apSize: 20, apTotal: 0,
        bedDlgVisible: false, bedDlgRow: null, bedDlgWardId: null, bedDlgBeds: [], bedDlgLoading: false, pickedBedId: null,
        /* 发起流转申请对话框 */
        applyDlg: false, applying: false,
        applyForm: { transferType: 1, toWardId: null, toBedId: null, reason: '' },
        applyBeds: [], applyBedLoading: false,
        /* 退回对话框 */
        rejectDlg: false, rejectRow: null, rejectReason: '',
        staffId: currentStaffId()
      };
    },
    computed: {
      /* visitId → 在区患者姓名映射(申请单展示用, 优先本地 patients, 回落空) */
      nameOf: function () {
        var m = {};
        (this.patients || []).forEach(function (p) { m[HIS.idKey(p.id)] = p.patientName || p.name || '-'; });
        return m;
      },
      wardMap: function () {
        var m = {};
        (this.wards || []).forEach(function (w) { m[HIS.idKey(w.id)] = w; });
        return m;
      }
    },
    watch: {
      seg: function (v) { if (v === 'preadmit') { this.loadPreAdmits(); } else if (v === 'approval') { this.loadApps(); } },
      wardId: function () { if (this.seg === 'preadmit') { this.loadPreAdmits(); } },
      initialSeg: function (v) { if (v) { this.seg = v; } }
    },
    created: function () {
      if (this.seg === 'preadmit') { this.loadPreAdmits(); } else if (this.seg === 'approval') { this.loadApps(); }
    },
    methods: {
      fmtTime: fmtTime, datePart: datePart,
      bedNoOf: function (visitId) {
        var p = (this.patients || []).filter(function (x) { return HIS.sameId(x.id, visitId); })[0];
        return p ? (p.bedNo || '-') : '—';
      },
      ptName: function (visitId) { return this.nameOf[HIS.idKey(visitId)] || ('就诊' + (visitId == null ? '' : visitId)); },
      wardName: function (wid) { var w = this.wardMap[HIS.idKey(wid)]; return w ? w.wardName : '—'; },
      /* ---------- 待入区 ---------- */
      loadPreAdmits: function () {
        var vm = this; vm.loadingPA = true;
        HIS.get('/api/his/inp/pre-admissions').then(function (rows) {
          var list = rows || [];
          /* 若已选病区, 优先展示拟入本病区的预入院(无 ward_id 的置后, 不过滤掉以便统一受理) */
          if (vm.wardId) {
            list.sort(function (a, b) {
              var am = HIS.sameId(a.ward_id, vm.wardId) ? 0 : 1;
              var bm = HIS.sameId(b.ward_id, vm.wardId) ? 0 : 1;
              return am - bm;
            });
          }
          vm.preAdmits = list;
        }).catch(HIS.notifyError).finally(function () { vm.loadingPA = false; });
      },
      openBedPick: function (row) {
        var vm = this;
        vm.bedDlgRow = row; vm.pickedBedId = null;
        vm.bedDlgWardId = row.ward_id ? HIS.id(row.ward_id) : (vm.wardId ? HIS.id(vm.wardId) : null);
        vm.bedDlgVisible = true; vm.loadBedDlgBeds();
      },
      loadBedDlgBeds: function () {
        var vm = this;
        if (!vm.bedDlgWardId) { vm.bedDlgBeds = []; return; }
        vm.bedDlgLoading = true;
        HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(vm.bedDlgWardId)).then(function (list) {
          vm.bedDlgBeds = (list || []).filter(function (b) { return b.status === 0; }).sort(byBedNo);
        }).catch(HIS.notifyError).finally(function () { vm.bedDlgLoading = false; });
      },
      onBedDlgWardChange: function () { this.pickedBedId = null; this.loadBedDlgBeds(); },
      confirmAdmit: function () {
        var vm = this;
        if (!vm.pickedBedId) { HIS.notifyError('请选择入区床位'); return; }
        HIS.post('/api/his/inp/pre-admit/' + HIS.idParam(vm.bedDlgRow.id) + '/confirm?bedId=' + HIS.idParam(vm.pickedBedId)).then(function () {
          HIS.notifySuccess('入区登记完成'); vm.bedDlgVisible = false; vm.loadPreAdmits(); vm.$emit('changed');
        }).catch(HIS.notifyError);
      },
      cancelPreAdmit: function (row) {
        var vm = this;
        confirmBox('确认取消该患者入院？', '取消后将释放预入院占位。').then(function () {
          HIS.post('/api/his/inp/pre-admit/' + HIS.idParam(row.id) + '/cancel').then(function () {
            HIS.notifySuccess('已取消入院'); vm.loadPreAdmits(); vm.$emit('changed');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      /* ---------- 申请审批中心 ---------- */
      loadApps: function () {
        var vm = this; vm.loadingAP = true;
        var p = new URLSearchParams({ page: vm.apPage, size: vm.apSize });
        if (vm.apStatus) { p.append('status', vm.apStatus); }
        HIS.get('/api/his/inp/transfer/list?' + p.toString()).then(function (d) {
          vm.apps = (d && d.records) || []; vm.apTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.loadingAP = false; });
      },
      onApStatusChange: function () { this.apPage = 1; this.loadApps(); },
      doApprove: function (row) {
        var vm = this;
        HIS.put('/api/his/inp/transfer/' + HIS.idParam(row.id) + '/approve').then(function () {
          HIS.notifySuccess('已批准'); vm.loadApps(); vm.$emit('changed');
        }).catch(HIS.notifyError);
      },
      openReject: function (row) { this.rejectRow = row; this.rejectReason = ''; this.rejectDlg = true; },
      doReject: function () {
        var vm = this;
        if (!vm.rejectReason) { HIS.notifyError('请填写退回原因'); return; }
        HIS.put('/api/his/inp/transfer/' + HIS.idParam(vm.rejectRow.id) + '/reject', { reason: vm.rejectReason }).then(function () {
          HIS.notifySuccess('已退回'); vm.rejectDlg = false; vm.loadApps(); vm.$emit('changed');
        }).catch(HIS.notifyError);
      },
      doExecute: function (row) {
        var vm = this;
        confirmBox('确认执行该流转申请？', '执行后新床占用、旧床释放并回写在院信息, 不可撤销。').then(function () {
          HIS.put('/api/his/inp/transfer/' + HIS.idParam(row.id) + '/execute').then(function () {
            HIS.notifySuccess('已执行流转'); vm.loadApps(); vm.$emit('changed');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      doCancel: function (row) {
        var vm = this;
        HIS.put('/api/his/inp/transfer/' + HIS.idParam(row.id) + '/cancel').then(function () {
          HIS.notifySuccess('已取消申请'); vm.loadApps();
        }).catch(HIS.notifyError);
      },
      canCancel: function (row) { return row.status === 1 && this.staffId != null && HIS.sameId(row.applyDoctorId, this.staffId); },
      /* ---------- 在区流转: 发起申请 / 出院 / 取消入院 ---------- */
      openApply: function () {
        if (!this.visitId) { HIS.notifyError('请先在左侧选择在区患者'); return; }
        this.applyForm = { transferType: 1, toWardId: null, toBedId: null, reason: '' };
        this.applyBeds = [];
        this.applyDlg = true;
      },
      onApplyWardChange: function () {
        var vm = this; vm.applyForm.toBedId = null; vm.applyBeds = [];
        if (!vm.applyForm.toWardId) { return; }
        vm.applyBedLoading = true;
        HIS.get('/api/his/inp/bed/list?wardId=' + HIS.idParam(vm.applyForm.toWardId)).then(function (list) {
          vm.applyBeds = (list || []).filter(function (b) { return b.status === 0; }).sort(byBedNo);
        }).catch(HIS.notifyError).finally(function () { vm.applyBedLoading = false; });
      },
      submitApply: function () {
        var vm = this;
        var f = vm.applyForm;
        var ward = vm.wardMap[HIS.idKey(f.toWardId)];
        var body = { inpVisitId: HIS.id(vm.visitId), transferType: f.transferType, toWardId: f.toWardId, toBedId: f.toBedId, reason: f.reason };
        if (f.transferType === 1) { body.toDeptId = ward ? ward.deptId : null; }
        vm.applying = true;
        HIS.post('/api/his/inp/transfer', body).then(function () {
          HIS.notifySuccess('流转申请已提交, 待审批'); vm.applyDlg = false; vm.$emit('changed');
        }).catch(HIS.notifyError).finally(function () { vm.applying = false; });
      },
      doDischarge: function () {
        var vm = this;
        if (!vm.visitId) { HIS.notifyError('请先选择在区患者'); return; }
        confirmBox('确认办理出院？', '出院前请确认医嘱已停/已执行、费用已结算(后端将二次校验)。').then(function () {
          HIS.put('/api/his/inp/visit/' + HIS.idParam(vm.visitId) + '/discharge-apply').then(function () {
            HIS.notifySuccess('已提交出院申请'); vm.$emit('changed');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      doCancelAdmit: function () {
        var vm = this;
        if (!vm.visitId) { HIS.notifyError('请先选择在区患者'); return; }
        confirmBox('确认取消入院？', '取消后患者离院并释放床位, 不可撤销。').then(function () {
          HIS.put('/api/his/inp/visit/' + HIS.idParam(vm.visitId) + '/cancel').then(function () {
            HIS.notifySuccess('已取消入院'); vm.$emit('changed');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      }
    },
    template: [
      '<div class="nfw" v-loading="loadingPA || loadingAP">',
      '  <div class="nfw-main">',
      '    <el-radio-group v-model="seg" size="small" style="margin-bottom:10px;">',
      '      <el-radio-button label="preadmit">待入区 ({{ preAdmits.length }})</el-radio-button>',
      '      <el-radio-button label="approval">申请审批 ({{ apTotal }})</el-radio-button>',
      '      <el-radio-button label="onward">在区流转</el-radio-button>',
      '    </el-radio-group>',
      /* 待入区 */
      '    <div v-show="seg===\'preadmit\'">',
      '      <el-table :data="preAdmits" border size="small" max-height="calc(100vh - 320px)">',
      '        <el-table-column type="index" label="序号" width="55" align="center"></el-table-column>',
      '        <el-table-column label="住院号" width="150" prop="inp_no"></el-table-column>',
      '        <el-table-column label="姓名" width="90"><template #default="s">{{ s.row.patient_name }}</template></el-table-column>',
      '        <el-table-column label="性别" width="60" align="center"><template #default="s">{{ s.row.gender_name || s.row.gender || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="年龄" width="60" align="center"><template #default="s">{{ s.row.age != null ? s.row.age : \'-\' }}</template></el-table-column>',
      '        <el-table-column label="入院诊断" min-width="180" show-overflow-tooltip><template #default="s">{{ s.row.admit_diag || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="拟入病区" width="120"><template #default="s">{{ wardName(s.row.ward_id) }}</template></el-table-column>',
      '        <el-table-column label="预入院时间" width="150" align="center"><template #default="s">{{ fmtTime(s.row.pre_admit_time) }}</template></el-table-column>',
      '        <el-table-column label="操作" width="150" align="center" fixed="right">',
      '          <template #default="s">',
      '            <el-button size="small" type="primary" link @click="openBedPick(s.row)">入区登记</el-button>',
      '            <el-button size="small" type="danger" link @click="cancelPreAdmit(s.row)">取消入院</el-button>',
      '          </template>',
      '        </el-table-column>',
      '        <template #empty><el-empty description="暂无待入区患者" :image-size="54"></el-empty></template>',
      '      </el-table>',
      '    </div>',
      /* 申请审批 */
      '    <div v-show="seg===\'approval\'">',
      '      <div class="nfw-toolbar">',
      '        <span style="font-size:13px;color:var(--yb-ink-3);">状态</span>',
      '        <el-select v-model="apStatus" size="small" style="width:130px;" @change="onApStatusChange">',
      '          <el-option :value="0" label="全部"></el-option>',
      '          <el-option v-for="(lbl,k) in {1:1,2:1,3:1,4:1,5:1}" :key="k" :value="Number(k)" :label="({1:\'待审批\',2:\'已批准\',3:\'已退回\',4:\'已执行\',5:\'已取消\'})[k]"></el-option>',
      '        </el-select>',
      '        <el-button size="small" @click="loadApps">刷新</el-button>',
      '      </div>',
      '      <el-table :data="apps" border size="small" max-height="calc(100vh - 360px)">',
      '        <el-table-column label="床号" width="70" align="center"><template #default="s">{{ bedNoOf(s.row.inpVisitId) }}</template></el-table-column>',
      '        <el-table-column label="患者" width="90"><template #default="s">{{ ptName(s.row.inpVisitId) }}</template></el-table-column>',
      '        <el-table-column label="类型" width="70" align="center"><template #default="s">{{ ({1:\'转科\',2:\'转床\',3:\'加床\'})[s.row.transferType] || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="原病区→目标" min-width="180"><template #default="s">{{ wardName(s.row.fromWardId) }} → {{ wardName(s.row.toWardId) }}</template></el-table-column>',
      '        <el-table-column label="原因" min-width="140" show-overflow-tooltip><template #default="s">{{ s.row.reason || \'-\' }}</template></el-table-column>',
      '        <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="({1:\'warning\',2:\'primary\',3:\'danger\',4:\'success\',5:\'info\'})[s.row.status]">{{ ({1:\'待审批\',2:\'已批准\',3:\'已退回\',4:\'已执行\',5:\'已取消\'})[s.row.status] }}</el-tag></template></el-table-column>',
      '        <el-table-column label="申请时间" width="150" align="center"><template #default="s">{{ fmtTime(s.row.applyTime) }}</template></el-table-column>',
      '        <el-table-column label="操作" width="200" align="center" fixed="right">',
      '          <template #default="s">',
      '            <el-button v-if="s.row.status===1" size="small" type="success" link @click="doApprove(s.row)">批准</el-button>',
      '            <el-button v-if="s.row.status===1" size="small" type="warning" link @click="openReject(s.row)">退回</el-button>',
      '            <el-button v-if="s.row.status===2" size="small" type="primary" link @click="doExecute(s.row)">执行</el-button>',
      '            <el-button v-if="canCancel(s.row)" size="small" type="danger" link @click="doCancel(s.row)">取消</el-button>',
      '            <span v-if="s.row.status>=3 && !canCancel(s.row)" style="color:var(--yb-ink-4);">—</span>',
      '          </template>',
      '        </el-table-column>',
      '        <template #empty><el-empty description="暂无流转申请" :image-size="54"></el-empty></template>',
      '      </el-table>',
      '      <el-pagination style="margin-top:8px;justify-content:flex-end;" layout="total,prev,pager,next" :total="apTotal" :page-size="apSize" :current-page="apPage" @current-change="(v)=>{ apPage=v; loadApps(); }"></el-pagination>',
      '    </div>',
      /* 在区流转 */
      '    <div v-show="seg===\'onward\'">',
      '      <template v-if="patient">',
      '        <div class="nfw-toolbar" style="padding:10px 12px;border:1px solid var(--yb-border-light);border-radius:var(--yb-r-md);background:var(--yb-surface);">',
      '          <span class="nfw-badge" style="background:var(--yb-brand);">{{ patient.bedNo || "—" }}</span>',
      '          <b style="color:var(--yb-ink-1);">{{ patient.patientName }}</b>',
      '          <span style="color:var(--yb-ink-3);font-size:12px;">住院号 {{ patient.inpNo || "-" }} · {{ patient.admitDiag || "诊断待补" }}</span>',
      '          <span style="flex:1;"></span>',
      '          <el-button size="small" type="primary" @click="openApply">发起转科/转床申请</el-button>',
      '          <el-button size="small" type="success" @click="doDischarge">办理出院</el-button>',
      '          <el-button size="small" type="danger" plain @click="doCancelAdmit">取消入院</el-button>',
      '        </div>',
      '        <div style="margin-top:6px;color:var(--yb-ink-4);font-size:12px;">提示: 转科/转床申请提交后至上方「申请审批」由医生/护士长批准并执行; 出院须先停嘱并结清费用, 后端二次校验。</div>',
      '      </template>',
      '      <el-empty v-else description="请先在左侧选择一位在区患者" :image-size="54"></el-empty>',
      '    </div>',
      '  </div>',
      /* 入区选床对话框 */
      '  <el-dialog v-model="bedDlgVisible" :title="\'入区登记 · \' + (bedDlgRow ? bedDlgRow.patient_name : \'\')" width="460px">',
      '    <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;">',
      '      <span style="font-size:13px;">病区</span>',
      '      <el-select v-model="bedDlgWardId" size="small" style="flex:1;" @change="onBedDlgWardChange">',
      '        <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>',
      '      </el-select>',
      '    </div>',
      '    <div v-loading="bedDlgLoading" style="max-height:280px;overflow:auto;">',
      '      <el-radio-group v-model="pickedBedId" style="display:flex;flex-wrap:wrap;gap:8px;">',
      '        <el-radio v-for="b in bedDlgBeds" :key="b.id" :label="b.id" :value="b.id" border size="small">{{ b.bedNo }}<template v-if="b.roomNo">·{{ b.roomNo }}</template></el-radio>',
      '      </el-radio-group>',
      '      <div v-if="!bedDlgLoading && !bedDlgBeds.length" class="nfw-bedpick-empty">本病区暂无空床, 请切换病区或在床位管理中加床。</div>',
      '    </div>',
      '    <template #footer><el-button @click="bedDlgVisible=false">取消</el-button><el-button type="primary" :disabled="!pickedBedId" @click="confirmAdmit">确认入区</el-button></template>',
      '  </el-dialog>',
      /* 发起流转申请对话框 */
      '  <el-dialog v-model="applyDlg" title="发起转科/转床/加床申请" width="480px">',
      '    <el-form label-width="88px">',
      '      <el-form-item label="类型"><el-radio-group v-model="applyForm.transferType"><el-radio :label="1" :value="1">转科</el-radio><el-radio :label="2" :value="2">转床</el-radio><el-radio :label="3" :value="3">加床</el-radio></el-radio-group></el-form-item>',
      '      <el-form-item label="目标病区"><el-select v-model="applyForm.toWardId" style="width:100%;" @change="onApplyWardChange"><el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="目标床位"><el-select v-model="applyForm.toBedId" :loading="applyBedLoading" style="width:100%;" placeholder="选择空床"><el-option v-for="b in applyBeds" :key="b.id" :label="b.bedNo + (b.roomNo ? (\' · \'+b.roomNo+\'房\') : \'\')" :value="b.id"></el-option></el-select></el-form-item>',
      '      <el-form-item label="原因"><el-input v-model="applyForm.reason" type="textarea" :rows="2" placeholder="如: 转专科治疗 / 床位调整"></el-input></el-form-item>',
      '    </el-form>',
      '    <div style="color:var(--yb-ink-4);font-size:12px;">转科须选目标病区(科室随病区带出)与目标床位; 提交后进入「申请审批」待批准执行。</div>',
      '    <template #footer><el-button @click="applyDlg=false">取消</el-button><el-button type="primary" :loading="applying" :disabled="!applyForm.toWardId || !applyForm.toBedId" @click="submitApply">提交申请</el-button></template>',
      '  </el-dialog>',
      /* 退回对话框 */
      '  <el-dialog v-model="rejectDlg" title="退回流转申请" width="440px">',
      '    <div style="margin-bottom:8px;">',
      '      <el-tag v-for="r in [\'床位已满\',\'诊断不符\',\'转科资料不全\',\'需先处理未完成业务\',\'目标科室拒收\']" :key="r" size="small" style="cursor:pointer;margin:0 6px 6px 0;" @click="rejectReason=r">{{ r }}</el-tag>',
      '    </div>',
      '    <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="选择常见原因或直接录入退回原因"></el-input>',
      '    <template #footer><el-button @click="rejectDlg=false">取消</el-button><el-button type="danger" :disabled="!rejectReason" @click="doReject">确认退回</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 费用管理 InpFeePanel(13.15.5: 账户/明细/补费/结算/日清单/欠费预警) ================= */
  var InpFeePanel = {
    name: 'InpFeePanel',
    props: {
      wardId: { type: [String, Number], default: null },
      patients: { type: Array, default: function () { return []; } },
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    emits: ['changed'],
    data: function () {
      return {
        PAY_TYPES: PAY_TYPES, FEE_TYPE_OPTIONS: FEE_TYPE_OPTIONS,
        loading: false,
        balance: null, summary: null,
        details: [], dPage: 1, dSize: 20, dTotal: 0, dFeeType: null,
        deposits: [], settlements: [],
        billDate: todayStr(), bill: null,
        alerts: [],
        depositDlg: false, depForm: { amount: null, payType: 1, direction: 1, remark: '' }, submitting: false,
        chargeDlg: false, chgForm: { itemName: '', feeType: 1, quantity: 1, unitPrice: null, remark: '' },
        preResult: null, preLoading: false
      };
    },
    computed: {
      totalFee: function () {
        var d = this.summary || {};
        var v = d.chargeTotalAmount != null ? d.chargeTotalAmount : d.totalAmount;
        return v == null ? null : Number(v);
      },
      needPay: function () {
        if (this.totalFee == null) { return null; }
        var b = this.balance == null ? 0 : Number(this.balance);
        return Math.max(0, this.totalFee - b);
      },
      amtPreview: function () {
        var q = Number(this.chgForm.quantity) || 0, p = Number(this.chgForm.unitPrice) || 0;
        return (q * p).toFixed(2);
      }
    },
    watch: {
      visitId: function () { this.reload(); }
    },
    created: function () { this.reload(); },
    methods: {
      fmtTime: fmtTime, datePart: datePart,
      money: function (v) { return v == null || v === '' ? '-' : Number(v).toFixed(2); },
      payTypeLabel: function (v) { var o = PAY_TYPES.filter(function (x) { return x.v === Number(v); })[0]; return o ? o.l : (v == null ? '-' : v); },
      directionLabel: function (v) { var o = DIRECTIONS.filter(function (x) { return x.v === Number(v); })[0]; return o ? o.l : (v == null ? '-' : v); },
      feeTypeLabel: function (v) { return FEE_TYPE_MAP[v] || (v == null ? '-' : v); },
      alertHandleLabel: function (v) { return v == null ? '待处理' : (FEE_ALERT_HANDLE[v] || '已处理'); },
      ptName: function (vid) { var p = (this.patients || []).filter(function (x) { return HIS.sameId(x.id, vid); })[0]; return p ? p.patientName : '—'; },
      bedNoOf: function (vid) { var p = (this.patients || []).filter(function (x) { return HIS.sameId(x.id, vid); })[0]; return p ? (p.bedNo || '—') : '—'; },
      reload: function () {
        if (!this.visitId) { this.balance = null; this.summary = null; this.details = []; this.deposits = []; this.settlements = []; this.alerts = []; this.bill = null; this.preResult = null; return; }
        this.loadBalance(); this.loadSummary(); this.loadDetails(); this.loadDeposits(); this.loadSettlements(); this.loadAlerts(); this.loadBill();
      },
      loadBalance: function () {
        var vm = this;
        HIS.get('/api/his/inp/deposit/balance/' + HIS.idParam(vm.visitId)).then(function (b) { vm.balance = b; }).catch(function () { vm.balance = null; });
      },
      loadSummary: function () {
        var vm = this;
        HIS.get('/api/his/inp/settle/charge/summary/' + HIS.idParam(vm.visitId)).then(function (d) { vm.summary = d || null; }).catch(function () { vm.summary = null; });
      },
      loadDetails: function () {
        var vm = this;
        var p = new URLSearchParams({ inpVisitId: HIS.id(vm.visitId), page: vm.dPage, size: vm.dSize });
        if (vm.dFeeType) { p.append('feeType', vm.dFeeType); }
        HIS.get('/api/his/inp/settle/charge/list?' + p.toString()).then(function (d) {
          vm.details = (d && d.records) || []; vm.dTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError);
      },
      onFeeFilter: function () { this.dPage = 1; this.loadDetails(); },
      loadDeposits: function () {
        var vm = this;
        HIS.get('/api/his/inp/deposit/list?inpVisitId=' + HIS.idParam(vm.visitId)).then(function (rows) { vm.deposits = rows || []; }).catch(function () { vm.deposits = []; });
      },
      loadSettlements: function () {
        var vm = this;
        HIS.get('/api/his/inp/settle/settlements/' + HIS.idParam(vm.visitId)).then(function (rows) { vm.settlements = rows || []; }).catch(function () { vm.settlements = []; });
      },
      loadAlerts: function () {
        var vm = this;
        HIS.get('/api/his/inp/fee-alert/list?visitId=' + HIS.idParam(vm.visitId) + '&page=1&size=50').then(function (d) { vm.alerts = (d && d.records) || []; }).catch(function () { vm.alerts = []; });
      },
      loadBill: function () {
        var vm = this;
        HIS.get('/api/his/inp/daily-bill?visitId=' + HIS.idParam(vm.visitId) + '&date=' + vm.billDate).then(function (d) { vm.bill = d || null; }).catch(function () { vm.bill = null; });
      },
      /* 预交金缴纳/退还 */
      openDeposit: function (dir) { this.depForm = { amount: null, payType: 1, direction: dir, remark: '' }; this.depositDlg = true; },
      submitDeposit: function () {
        var vm = this;
        if (!vm.depForm.amount) { HIS.notifyError('请输入金额'); return; }
        vm.submitting = true;
        HIS.post('/api/his/inp/deposit', { inpVisitId: HIS.id(vm.visitId), amount: Number(vm.depForm.amount), payType: vm.depForm.payType, direction: vm.depForm.direction, remark: vm.depForm.remark }).then(function () {
          HIS.notifySuccess(vm.depForm.direction === 1 ? '缴纳成功' : '退还成功'); vm.depositDlg = false; vm.loadBalance(); vm.loadDeposits(); vm.$emit('changed');
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      /* 手动记费/补费 */
      openAddCharge: function () { this.chgForm = { itemName: '', feeType: 1, quantity: 1, unitPrice: null, remark: '' }; this.chargeDlg = true; },
      submitAddCharge: function () {
        var vm = this;
        if (!vm.chgForm.itemName) { HIS.notifyError('请输入费用项目名称'); return; }
        var q = Number(vm.chgForm.quantity) || 0, p = Number(vm.chgForm.unitPrice) || 0;
        vm.submitting = true;
        HIS.post('/api/his/inp/settle/charge', { inpVisitId: HIS.id(vm.visitId), itemName: vm.chgForm.itemName, feeType: vm.chgForm.feeType, quantity: q, unitPrice: p, amount: q * p, chargeDate: todayStr() }).then(function () {
          HIS.notifySuccess('补费已记入'); vm.chargeDlg = false; vm.loadSummary(); vm.loadDetails(); vm.loadBalance(); vm.$emit('changed');
        }).catch(HIS.notifyError).finally(function () { vm.submitting = false; });
      },
      /* 结算 */
      doPreSettle: function () {
        var vm = this; vm.preLoading = true; vm.preResult = null;
        HIS.post('/api/his/inp/settle/pre?visitId=' + HIS.idParam(vm.visitId)).then(function (d) { vm.preResult = d || {}; }).catch(HIS.notifyError).finally(function () { vm.preLoading = false; });
      },
      doSettle: function (type) {
        var vm = this;
        confirmBox('确认' + (type === 1 ? '出院结算' : '中途结算') + '？', '结算将汇总费用并扣减预交金, 可后续作废冲正。').then(function () {
          HIS.post('/api/his/inp/settle', { inpVisitId: HIS.id(vm.visitId), settleType: type }).then(function (d) {
            HIS.notifySuccess('结算完成' + (d && d.settle ? (' 结算单号 ' + (d.settle.settleNo || '')) : '')); vm.loadBalance(); vm.loadSettlements(); vm.loadSummary(); vm.$emit('changed');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      cancelSettle: function (row) {
        var vm = this;
        confirmBox('确认作废该结算单？', '作废后费用与预交金将回滚, 可重新结算。').then(function () {
          HIS.post('/api/his/inp/settle/' + HIS.idParam(row.id) + '/cancel').then(function () {
            HIS.notifySuccess('已作废'); vm.loadBalance(); vm.loadSettlements(); vm.loadSummary(); vm.$emit('changed');
          }).catch(HIS.notifyError);
        }).catch(function () {});
      },
      /* 日清单 */
      onBillDate: function () { this.loadBill(); },
      genBill: function () {
        var vm = this;
        HIS.post('/api/his/inp/daily-bill/generate?visitId=' + HIS.idParam(vm.visitId) + '&date=' + vm.billDate).then(function () {
          HIS.notifySuccess('日清单已生成'); vm.loadBill();
        }).catch(HIS.notifyError);
      },
      printBill: function () {
        var vm = this;
        HIS.get('/api/his/inp/print/render/daily-bill?visitId=' + HIS.idParam(vm.visitId) + '&date=' + vm.billDate).then(function (html) {
          if (typeof HIS.printHtmlFrame === 'function') { HIS.printHtmlFrame(html, '每日清单'); } else { HIS.notifyError('打印组件未就绪'); }
          if (vm.bill && vm.bill.id) { HIS.put('/api/his/inp/daily-bill/' + HIS.idParam(vm.bill.id) + '/printed').catch(function () {}); }
        }).catch(HIS.notifyError);
      },
      /* 欠费预警处理 */
      handleAlert: function (row) {
        var vm = this;
        confirmBox('标记该预警为已催缴？', '将记录处理人与处理时间。').then(function () {
          HIS.put('/api/his/inp/fee-alert/' + HIS.idParam(row.id) + '/handle', { result: 1, reason: '护士站催缴' }).then(function () {
            HIS.notifySuccess('已处理'); vm.loadAlerts();
          }).catch(HIS.notifyError);
        }).catch(function () {});
      }
    },
    template: [
      '<div class="nfee">',
      '  <div class="nfee-left">',
      '    <div class="nfee-acct" v-if="visitId">',
      '      <div class="lbl">预交金余额</div>',
      '      <div class="bal" :class="{ \'is-neg\': Number(balance) < 0 }">¥{{ money(balance) }}</div>',
      '      <div class="lbl" style="margin-top:6px;">总费用 ¥{{ money(totalFee) }} · 应补缴 ¥{{ money(needPay) }}</div>',
      '      <div class="nfee-kpis"><span class="nfee-kpi">{{ patient ? patient.patientName : \'\' }}<template v-if="patient"> · 床{{ patient.bedNo }}</template></span></div>',
      '      <div style="display:flex;gap:6px;margin-top:10px;flex-wrap:wrap;">',
      '        <el-button size="small" type="primary" @click="openDeposit(1)">缴纳</el-button>',
      '        <el-button size="small" @click="openDeposit(2)">退还</el-button>',
      '        <el-button size="small" type="warning" plain @click="openAddCharge">手动补费</el-button>',
      '      </div>',
      '    </div>',
      '    <el-empty v-else description="请先在左侧选择在院患者" :image-size="50"></el-empty>',
      '    <div class="inp-panel" v-if="visitId" style="margin-bottom:0;">',
      '      <h4>结算</h4>',
      '      <div style="display:flex;gap:6px;flex-wrap:wrap;">',
      '        <el-button size="small" :loading="preLoading" @click="doPreSettle">预结算试算</el-button>',
      '        <el-button size="small" type="success" @click="doSettle(2)">中途结算</el-button>',
      '        <el-button size="small" type="primary" @click="doSettle(1)">出院结算</el-button>',
      '      </div>',
      '      <div v-if="preResult" style="margin-top:8px;font-size:12px;color:var(--yb-ink-2);line-height:1.7;">',
      '        <div>模式: {{ preResult.settleMode === \'MID\' ? \'中途\' : \'出院\' }} · 医保: {{ preResult.ybFlag ? \'已试算\' : \'未接入\' }}</div>',
      '        <div>费用合计 <b class="nfee-money">¥{{ money(preResult.totalAmount) }}</b> · 预交金 ¥{{ money(preResult.depositBalance) }} · 应纳 <b class="nfee-money">¥{{ money(preResult.needPay) }}</b></div>',
      '      </div>',
      '    </div>',
      '  </div>',
      '  <div class="nfee-main" v-loading="loading">',
      '    <el-tabs model-value="detail">',
      '      <el-tab-pane label="费用明细" name="detail">',
      '        <div class="nfee-sec-h"><span class="t">费用明细</span><span style="flex:1;"></span>',
      '          <el-select v-model="dFeeType" size="small" clearable placeholder="全部费别" style="width:120px;" @change="onFeeFilter"><el-option v-for="o in [1,2,3,4,5,6,7,8,9]" :key="o" :value="o" :label="({1:\'西药\',2:\'中药\',3:\'检查\',4:\'检验\',5:\'治疗\',6:\'护理\',7:\'材料\',8:\'床位\',9:\'其他\'})[o]"></el-option></el-select>',
      '        </div>',
      '        <el-table :data="details" border size="small" max-height="calc(100vh - 360px)">',
      '          <el-table-column label="日期" width="110" align="center"><template #default="s">{{ datePart(s.row.chargeDate) }}</template></el-table-column>',
      '          <el-table-column label="费别" width="80" align="center"><template #default="s">{{ feeTypeLabel(s.row.feeType) }}</template></el-table-column>',
      '          <el-table-column label="项目" min-width="180" show-overflow-tooltip><template #default="s">{{ s.row.itemName || s.row.itemCode || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="数量" width="80" align="center"><template #default="s">{{ s.row.quantity }}</template></el-table-column>',
      '          <el-table-column label="单价" width="90" align="right"><template #default="s">{{ money(s.row.unitPrice) }}</template></el-table-column>',
      '          <el-table-column label="金额" width="100" align="right"><template #default="s"><span class="nfee-money">{{ money(s.row.amount) }}</span></template></el-table-column>',
      '          <template #empty><el-empty description="暂无费用明细" :image-size="50"></el-empty></template>',
      '        </el-table>',
      '        <el-pagination style="margin-top:6px;justify-content:flex-end;" layout="total,prev,pager,next" :total="dTotal" :page-size="dSize" :current-page="dPage" @current-change="(v)=>{ dPage=v; loadDetails(); }"></el-pagination>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="缴款流水" name="dep">',
      '        <el-table :data="deposits" border size="small" max-height="calc(100vh - 360px)">',
      '          <el-table-column label="时间" width="150" align="center"><template #default="s">{{ fmtTime(s.row.createTime) }}</template></el-table-column>',
      '          <el-table-column label="方向" width="70" align="center"><template #default="s"><el-tag size="small" :type="s.row.direction===1?\'success\':\'info\'">{{ directionLabel(s.row.direction) }}</el-tag></template></el-table-column>',
      '          <el-table-column label="金额" width="110" align="right"><template #default="s"><span class="nfee-money">¥{{ money(s.row.amount) }}</span></template></el-table-column>',
      '          <el-table-column label="缴费后余额" width="120" align="right"><template #default="s">{{ money(s.row.balanceAfter) }}</template></el-table-column>',
      '          <el-table-column label="方式" width="80" align="center"><template #default="s">{{ payTypeLabel(s.row.payType) }}</template></el-table-column>',
      '          <el-table-column label="票号" width="120"><template #default="s">{{ s.row.receiptNo || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="备注" min-width="120" show-overflow-tooltip><template #default="s">{{ s.row.remark || \'-\' }}</template></el-table-column>',
      '          <template #empty><el-empty description="暂无缴款流水" :image-size="50"></el-empty></template>',
      '        </el-table>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="结算单" name="stl">',
      '        <el-table :data="settlements" border size="small" max-height="calc(100vh - 360px)">',
      '          <el-table-column label="结算单号" width="160"><template #default="s">{{ s.row.settleNo || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="类型" width="90" align="center"><template #default="s">{{ ({1:\'出院\',2:\'中途\',3:\'退费\'})[s.row.settleType] || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="总额" width="110" align="right"><template #default="s"><span class="nfee-money">¥{{ money(s.row.totalAmount) }}</span></template></el-table-column>',
      '          <el-table-column label="自付" width="100" align="right"><template #default="s">{{ money(s.row.selfPay) }}</template></el-table-column>',
      '          <el-table-column label="基金" width="100" align="right"><template #default="s">{{ money(s.row.fundPay) }}</template></el-table-column>',
      '          <el-table-column label="时间" width="150" align="center"><template #default="s">{{ fmtTime(s.row.settleTime) }}</template></el-table-column>',
      '          <el-table-column label="操作" width="90" align="center" fixed="right"><template #default="s"><el-button size="small" type="danger" link @click="cancelSettle(s.row)">作废</el-button></template></el-table-column>',
      '          <template #empty><el-empty description="暂无结算单" :image-size="50"></el-empty></template>',
      '        </el-table>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="每日清单" name="bill">',
      '        <div class="nfee-sec-h"><span class="t">每日清单</span>',
      '          <el-date-picker v-model="billDate" type="date" size="small" value-format="YYYY-MM-DD" style="width:150px;margin-left:8px;" @change="onBillDate"></el-date-picker>',
      '          <el-button size="small" style="margin-left:8px;" @click="genBill">生成</el-button>',
      '          <el-button size="small" type="primary" plain :disabled="!bill" @click="printBill">打印/预览</el-button>',
      '        </div>',
      '        <div v-if="bill" class="nfee-kpis">',
      '          <span class="nfee-kpi">当日合计 <b>¥{{ money(bill.totalAmount) }}</b></span>',
      '          <span class="nfee-kpi">累计 <b>¥{{ money(bill.cumulativeAmount) }}</b></span>',
      '          <span class="nfee-kpi">预交金余额 <b>¥{{ money(bill.depositBalance) }}</b></span>',
      '          <span class="nfee-kpi">{{ bill.printedFlag ? \'已打印\' : \'未打印\' }}</span>',
      '        </div>',
      '        <el-empty v-else description="该日尚无清单, 可点生成" :image-size="50"></el-empty>',
      '      </el-tab-pane>',
      '      <el-tab-pane label="欠费预警" name="alert">',
      '        <el-table :data="alerts" border size="small" max-height="calc(100vh - 360px)">',
      '          <el-table-column label="预警金额" width="110" align="right"><template #default="s"><span class="nfee-money is-neg">¥{{ money(s.row.alertAmount) }}</span></template></el-table-column>',
      '          <el-table-column label="阈值" width="100" align="right"><template #default="s">{{ money(s.row.thresholdAmount) }}</template></el-table-column>',
      '          <el-table-column label="处理" width="110" align="center"><template #default="s"><el-tag size="small" :type="s.row.handleResult?\'success\':\'warning\'">{{ s.row.handleResult ? alertHandleLabel(s.row.handleResult) : \'待处理\' }}</el-tag></template></el-table-column>',
      '          <el-table-column label="时间" width="150" align="center"><template #default="s">{{ fmtTime(s.row.handleTime || s.row.createTime) }}</template></el-table-column>',
      '          <el-table-column label="操作" width="90" align="center" fixed="right"><template #default="s"><el-button v-if="!s.row.handleResult" size="small" type="primary" link @click="handleAlert(s.row)">催缴</el-button><span v-else style="color:var(--yb-ink-4);">—</span></template></el-table-column>',
      '          <template #empty><el-empty description="暂无欠费预警" :image-size="50"></el-empty></template>',
      '        </el-table>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '  </div>',
      /* 缴纳/退还对话框 */
      '  <el-dialog v-model="depositDlg" :title="depForm.direction===1?\'缴纳预交金\':\'退还预交金\'" width="380px">',
      '    <el-form label-width="70px">',
      '      <el-form-item label="金额"><el-input-number v-model="depForm.amount" :min="0.01" :precision="2" :step="100" style="width:100%;"></el-input-number></el-form-item>',
      '      <el-form-item label="方式"><el-select v-model="depForm.payType" style="width:100%;"><el-option v-for="o in PAY_TYPES" :key="o.v" :value="o.v" :label="o.l"></el-option></el-select></el-form-item>',
      '      <el-form-item label="备注"><el-input v-model="depForm.remark" placeholder="选填"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="depositDlg=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitDeposit">确认</el-button></template>',
      '  </el-dialog>',
      /* 手动补费对话框 */
      '  <el-dialog v-model="chargeDlg" title="手动记费/补费" width="420px">',
      '    <el-form label-width="80px">',
      '      <el-form-item label="项目"><el-input v-model="chgForm.itemName" placeholder="费用项目名称"></el-input></el-form-item>',
      '      <el-form-item label="费别"><el-select v-model="chgForm.feeType" style="width:100%;"><el-option v-for="o in FEE_TYPE_OPTIONS" :key="o.v" :value="o.v" :label="o.l"></el-option></el-select></el-form-item>',
      '      <el-form-item label="数量"><el-input-number v-model="chgForm.quantity" :min="0" :precision="2" :step="1" style="width:100%;"></el-input-number></el-form-item>',
      '      <el-form-item label="单价"><el-input-number v-model="chgForm.unitPrice" :min="0" :precision="2" :step="1" style="width:100%;"></el-input-number></el-form-item>',
      '      <el-form-item label="金额"><span class="nfee-money">¥{{ amtPreview }}</span><span style="color:var(--yb-ink-4);font-size:12px;margin-left:8px;">(数量×单价自动计算)</span></el-form-item>',
      '    </el-form>',
      '    <template #footer><el-button @click="chargeDlg=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitAddCharge">记入</el-button></template>',
      '  </el-dialog>',
      '</div>'
    ].join('\n')
  };

  /* ================= 病人信息360 InpPatient360(13.15.4: 就诊/诊断/过敏/费用/流转集中查看) ================= */
  var InpPatient360 = {
    name: 'InpPatient360',
    props: {
      visitId: { type: [String, Number], default: null },
      patient: { type: Object, default: null }
    },
    emits: ['goto-fee'],
    data: function () {
      return {
        loading: false, detail: null, visits: null,
        allergies: [], summary: null, balance: null, transfers: [],
        open: { visit: true, diag: true, allergy: true, fee: true, flow: true }
      };
    },
    computed: {
      diagGroups: function () {
        var rows = (this.detail && this.detail.diagnoses) || [];
        var g = { 1: [], 2: [], 3: [], 4: [] };
        rows.forEach(function (d) { (g[d.diagType] = g[d.diagType] || []).push(d); });
        return Object.keys(g).filter(function (k) { return g[k] && g[k].length; }).map(function (k) { return { type: Number(k), name: DIAG_TYPES[k], rows: g[k] }; });
      }
    },
    watch: {
      visitId: function () { this.reload(); }
    },
    created: function () { this.reload(); },
    methods: {
      fmtTime: fmtTime, datePart: datePart,
      money: function (v) { return v == null || v === '' ? '-' : Number(v).toFixed(2); },
      toggle: function (k) { this.open[k] = !this.open[k]; },
      allergyTypeLabel: function (v) { return ALLERGY_TYPE[v] || '其他'; },
      severityLabel: function (v) { return { 1: '轻度', 2: '中度', 3: '重度' }[v] || (v == null ? '-' : v); },
      nurseLv: function (v) { return v == null ? '-' : (NURSING_LEVELS[v] || '-'); },
      condLv: function (v) { return v == null ? '-' : (CONDITION_LEVEL[v] || '-'); },
      reload: function () {
        var vm = this;
        if (!vm.visitId) { vm.detail = null; vm.allergies = []; vm.summary = null; vm.balance = null; vm.transfers = []; return; }
        vm.loading = true;
        Promise.all([
          HIS.get('/api/his/inp/visit/' + HIS.idParam(vm.visitId)).catch(function () { return null; }),
          HIS.get('/api/his/inp/allergy/list?visitId=' + HIS.idParam(vm.visitId)).catch(function () { return []; }),
          HIS.get('/api/his/inp/settle/charge/summary/' + HIS.idParam(vm.visitId)).catch(function () { return null; }),
          HIS.get('/api/his/inp/deposit/balance/' + HIS.idParam(vm.visitId)).catch(function () { return null; }),
          HIS.get('/api/his/inp/transfer/list?visitId=' + HIS.idParam(vm.visitId) + '&page=1&size=50').catch(function () { return { records: [] }; })
        ]).then(function (rs) {
          vm.detail = rs[0]; vm.allergies = (rs[1] || []).filter(function (a) { return a && a.status !== 0; });
          vm.summary = rs[2]; vm.balance = rs[3]; vm.transfers = (rs[4] && rs[4].records) || [];
        }).finally(function () { vm.loading = false; });
      },
      transferLabel: function (t) { return ({1:'转科',2:'转床',3:'加床'})[t.transferType] || '流转'; },
      transferStatus: function (s) { return ({1:'待审批',2:'已批准',3:'已退回',4:'已执行',5:'已取消'})[s] || '-'; }
    },
    template: [
      '<div class="np360" v-loading="loading">',
      '  <el-empty v-if="!visitId" description="请先在左侧选择一位患者" :image-size="60"></el-empty>',
      '  <template v-else>',
      '    <div class="np360-hd">',
      '      <div class="bed">{{ patient ? (patient.bedNo || "—") : "—" }}</div>',
      '      <div><div class="nm">{{ patient ? patient.patientName : "" }}</div>',
      '        <div class="meta">住院号 {{ patient ? (patient.inpNo||"-") : "-" }} · {{ patient ? (patient.gender||"") : "" }}{{ patient && patient.age!=null ? patient.age+"岁" : "" }}</div></div>',
      '      <span style="flex:1;"></span>',
      '      <el-tag v-if="patient && patient.allergyFlag" type="danger" effect="dark">过敏史</el-tag>',
      '      <el-button size="small" @click="$emit(\'goto-fee\')">前往费用管理</el-button>',
      '    </div>',
      '    <div class="np360-cards">',
      /* 就诊/住院信息 */
      '      <div class="np360-card">',
      '        <div class="np360-card-h" @click="toggle(\'visit\')">住院/就诊信息<span class="cnt">{{ open.visit ? "收起" : "展开" }}</span></div>',
      '        <div class="np360-card-b" v-show="open.visit">',
      '          <div class="np360-kv"><span class="k">入院日期</span><span class="v">{{ patient ? datePart(patient.admitDate) : "-" }}</span></div>',
      '          <div class="np360-kv"><span class="k">病区/床位</span><span class="v">{{ (detail && detail.ward ? detail.ward.wardName : "-") }} · {{ patient ? (patient.bedNo||"-") : "-" }}床</span></div>',
      '          <div class="np360-kv"><span class="k">主诊医生</span><span class="v">{{ patient ? (patient.doctorName||"待分配") : "-" }}</span></div>',
      '          <div class="np360-kv"><span class="k">责任护士</span><span class="v">{{ patient ? (patient.nurseName||"待指派") : "-" }}</span></div>',
      '          <div class="np360-kv"><span class="k">护理等级</span><span class="v">{{ patient ? nurseLv(patient.nursingLevel) : "-" }}</span></div>',
      '          <div class="np360-kv"><span class="k">病情</span><span class="v">{{ patient ? condLv(patient.conditionLevel) : "-" }}</span></div>',
      '          <div class="np360-kv"><span class="k">预计出院</span><span class="v">{{ patient && patient.expectedDischargeDate ? datePart(patient.expectedDischargeDate) : "-" }}</span></div>',
      '          <div class="np360-kv"><span class="k">入院诊断</span><span class="v">{{ patient ? (patient.admitDiag||"-") : "-" }}</span></div>',
      '        </div>',
      '      </div>',
      /* 诊断 */
      '      <div class="np360-card">',
      '        <div class="np360-card-h" @click="toggle(\'diag\')">诊断信息<span class="cnt">{{ (detail && detail.diagnoses ? detail.diagnoses.length : 0) }} 条</span></div>',
      '        <div class="np360-card-b" v-show="open.diag">',
      '          <template v-if="diagGroups.length">',
      '            <div v-for="g in diagGroups" :key="g.type" style="margin-bottom:8px;">',
      '              <div style="font-weight:600;color:var(--yb-ink-2);font-size:12px;margin-bottom:3px;">{{ g.name }}</div>',
      '              <div v-for="d in g.rows" :key="d.id" class="np360-kv"><span class="k">{{ d.diagCode || "" }}<template v-if="d.isMain===1"> ★主</template></span><span class="v">{{ d.diagName }}</span></div>',
      '            </div>',
      '          </template>',
      '          <div v-else style="color:var(--yb-ink-4);font-size:13px;">暂无诊断记录</div>',
      '        </div>',
      '      </div>',
      /* 过敏 */
      '      <div class="np360-card is-danger">',
      '        <div class="np360-card-h" @click="toggle(\'allergy\')">过敏信息<span class="cnt">{{ allergies.length }} 条</span></div>',
      '        <div class="np360-card-b" v-show="open.allergy">',
      '          <template v-if="allergies.length">',
      '            <div v-for="a in allergies" :key="a.id" class="np360-kv">',
      '              <span class="k">{{ allergyTypeLabel(a.allergyType) }} · {{ a.allergenName }}</span>',
      '              <span class="v">{{ severityLabel(a.severity) }}<template v-if="a.reactionDesc"> · {{ a.reactionDesc }}</template></span>',
      '            </div>',
      '          </template>',
      '          <div v-else style="color:var(--yb-success-strong);font-size:13px;">无已知过敏史</div>',
      '        </div>',
      '      </div>',
      /* 费用摘要 */
      '      <div class="np360-card">',
      '        <div class="np360-card-h" @click="toggle(\'fee\')">费用概览<span class="cnt">{{ open.fee ? "收起" : "展开" }}</span></div>',
      '        <div class="np360-card-b" v-show="open.fee">',
      '          <div class="np360-kv"><span class="k">总费用</span><span class="v nfee-money">¥{{ money(summary ? (summary.chargeTotalAmount!=null?summary.chargeTotalAmount:summary.totalAmount) : null) }}</span></div>',
      '          <div class="np360-kv"><span class="k">预交金余额</span><span class="v" :class="{ \'is-neg\': Number(balance)<0 }">¥{{ money(balance) }}</span></div>',
      '          <template v-if="summary && summary.feeItems && summary.feeItems.length">',
      '            <div v-for="f in summary.feeItems" :key="f.feeType" class="np360-kv"><span class="k">{{ f.feeTypeName }}</span><span class="v">¥{{ money(f.totalAmount) }}</span></div>',
      '          </template>',
      '        </div>',
      '      </div>',
      /* 流转记录 */
      '      <div class="np360-card">',
      '        <div class="np360-card-h" @click="toggle(\'flow\')">患者流转记录<span class="cnt">{{ transfers.length }} 条</span></div>',
      '        <div class="np360-card-b" v-show="open.flow">',
      '          <div v-if="transfers.length" class="nfw-tl">',
      '            <div v-for="t in transfers" :key="t.id" class="nfw-tl-item" :class="{ \'is-done\': t.status===4 }">',
      '              <span class="dot"></span>',
      '              <div class="body"><div class="lb">{{ transferLabel(t) }} · {{ transferStatus(t.status) }}</div>',
      '                <div class="nt">{{ t.reason || "" }}</div>',
      '                <div class="tm">{{ fmtTime(t.applyTime) }}</div></div>',
      '            </div>',
      '          </div>',
      '          <div v-else style="color:var(--yb-ink-4);font-size:13px;">暂无流转记录</div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </template>',
      '</div>'
    ].join('\n')
  };

  HIS.views.InpNurseStation = {
    name: 'InpNurseStation',
    data: function () {
      return {
        loadingWard: false, loadingPat: false,
        /* 左侧患者列表栏折叠态(true=收起为细导轨), 与医生站同款交互 */
        sideCollapsed: false,
        wardId: null, wards: [],
        keyword: '', patients: [], currentVisitId: null,
        /* 13.15.1 一体化主页: 默认落在「入出转」工作台; homeOrder 为用户自定义页签顺序(localStorage yb_nurse_home) */
        activeTab: 'flow',
        homeOrder: null,
        flowSeg: 'preadmit',
        preAdmitTotal: 0, pendingTransferTotal: 0,
        todo: { pendingAudit: 0, pendingExec: 0, pendingAssess: 0, pendingShift: 0, pendingVitals: 0 },
        criticalTodo: 0,
        criticalPatients: [], nursingAlerts: [], critOpen: false,
        patExtra: { nursingLevel: null, allergies: [] }, patExtraFor: null,
        todoTimer: null
      };
    },
    computed: {
      currentPatient: function () {
        var id = this.currentVisitId;
        if (id == null) { return null; }
        var rows = this.patients || [];
        for (var i = 0; i < rows.length; i++) {
          if (HIS.sameId(rows[i].id, id)) { return rows[i]; }
        }
        return null;
      },
      sortedPatients: function () {
        var rows = (this.patients || []).slice();
        rows.sort(byBedNo);
        return rows;
      },
      /* 危重摘要: 101-张三(病危) / 203-李四(病重) */
      critSummary: function () {
        return (this.criticalPatients || []).map(function (c) {
          return (c.bedNo || '—') + '-' + (c.patientName || '-') + '(' + (CONDITION_LEVEL[c.conditionLevel] || '-') + ')';
        }).join(' / ');
      },
      /* 护理预警条目: 评估到期(逐条) + 体温未测(汇总) */
      alertItems: function () {
        var arr = [];
        (this.nursingAlerts || []).forEach(function (a) {
          var n = a.lastAssessTime ? daysSince(a.lastAssessTime) : null;
          var last = a.lastAssessTime ? (n === 0 ? '今天' : (n == null ? '-' : n + '天前')) : '从未评估';
          arr.push('评估到期: ' + (a.bedNo || '—') + '-' + (a.patientName || '-') + ' 护理评估(上次' + last + ')');
        });
        var v = this.todo.pendingVitals || 0;
        if (v > 0) { arr.push('体温未测: 今日' + nowHM() + ' ' + v + '人未测'); }
        return arr;
      },
      /* 滚动时长随条目数伸缩(每条约7s, 止于16s) */
      alertDuration: function () {
        return Math.max(16, (this.alertItems || []).length * 7) + 's';
      },
      /* 过敏摘要 / 明细(title 悬浮提示) */
      allergyText: function () {
        return (this.patExtra.allergies || []).map(function (a) { return a.allergenName || ''; })
          .filter(function (t) { return !!t; }).join('、');
      },
      allergyDetail: function () {
        return (this.patExtra.allergies || []).map(function (a) {
          return (ALLERGY_TYPE[a.allergyType] || '其他') + '·' + (a.allergenName || '');
        }).join('，');
      },
      /* 可见页签: HOME_TABS 基准, 按 homeOrder(localStorage) 重排(未知 key 追加在后) */
      visibleTabs: function () {
        var base = HOME_TABS.slice();
        var order = this.homeOrder;
        if (!order || !order.length) { return base; }
        var byKey = {};
        base.forEach(function (t) { byKey[t.key] = t; });
        var out = [], used = {};
        order.forEach(function (k) { if (byKey[k] && !used[k]) { out.push(byKey[k]); used[k] = 1; } });
        base.forEach(function (t) { if (!used[t.key]) { out.push(t); } });
        return out;
      }
    },
    watch: {
      /* 选中患者变化 → 并行拉取护理等级(就诊详情)与过敏记录, 由 patExtraFor 防过期回写 */
      currentVisitId: function (id) { this.loadPatExtra(id); }
    },
    components: {
      'ns-icon': NsIcon,
      'inp-order-audit': HIS.views.InpOrderAudit,
      'inp-order-exec': HIS.views.InpOrderExecPanel,
      'inp-nursing-record': HIS.views.InpNursingRecord,
      'inp-shift-handover': HIS.views.InpShiftHandover,
      'inp-bed-overview': HIS.views.InpBedOverview,
      /* 新增页签(局部注册, 不挂 HIS.views): 量表工作台 + 计划管理 + 出入量 + 给药记录 */
      'inp-nursing-assessment': InpNursingAssessment,
      'inp-nursing-plan': InpNursingPlan,
      'inp-io-record': InpIoRecord,
      'inp-med-admin': InpMedAdmin,
      /* 13.15 一体化升级页签(局部注册, 不挂 HIS.views): 入出转 / 费用 / 病人360 */
      'inp-flow': InpFlowPanel,
      'inp-fee': InpFeePanel,
      'inp-patient360': InpPatient360
    },
    created: function () {
      var vm = this;
      /* 右键菜单点击任意处关闭(床位一览内多个同名监听由菜单自身 stop 兜底) */
      vm.ctxCloser = function () {
        var ov = vm.$refs.bedOv;
        if (Array.isArray(ov)) { ov = ov[0]; }
        if (ov && ov.ctx && ov.ctx.show) { ov.closeCtx(); }
      };
      document.addEventListener('click', vm.ctxCloser);
      /* 主页页签顺序持久化(localStorage yb_nurse_home): 非法/缺失回落默认顺序 */
      try {
        var ho = JSON.parse(localStorage.getItem('yb_nurse_home') || 'null');
        if (Array.isArray(ho) && ho.length) { vm.homeOrder = ho; }
      } catch (e) { /* 忽略脏数据 */ }
      vm.loadWards();
      /* 待办数 30s 轮询(静默失败不打扰), 页面卸载时清理 */
      vm.todoTimer = setInterval(function () { vm.loadTodo(); }, 30000);
    },
    beforeUnmount: function () {
      if (this.todoTimer) { clearInterval(this.todoTimer); this.todoTimer = null; }
      if (this.ctxCloser) { document.removeEventListener('click', this.ctxCloser); this.ctxCloser = null; }
    },
    methods: {
      datePart: datePart,
      daysSince: daysSince,
      /* 雪花ID判等(供模板 sameId(p.id, currentVisitId) 使用, 与 HIS.sameId 同源) */
      sameId: HIS.sameId,
      patientInfo: function (p) {
        var s = [];
        if (p.gender) { s.push(p.gender); }
        if (p.age != null && p.age !== '') { s.push(p.age + '岁'); }
        return s.join(' · ');
      },
      loadWards: function () {
        var vm = this;
        vm.loadingWard = true;
        HIS.get('/api/his/inp/bed/ward/list').then(function (rows) {
          var list = rows || [];
          var enabled = list.filter(function (w) { return w.status === 1; });
          vm.wards = enabled.length ? enabled : list;
          if (!vm.wardId && vm.wards.length) {
            vm.wardId = HIS.id(vm.wards[0].id);
            vm.refreshWardData();
          }
        }).catch(HIS.notifyError).finally(function () { vm.loadingWard = false; });
      },
      onWardChange: function () {
        this.currentVisitId = null;
        this.keyword = '';
        this.refreshWardData();
      },
      refreshWardData: function () {
        this.loadPatients();
        this.loadTodo();
      },
      loadPatients: function () {
        var vm = this;
        if (!vm.wardId) { vm.patients = []; return; }
        vm.loadingPat = true;
        var params = new URLSearchParams({ wardId: vm.wardId, page: 1, size: 200 });
        if (vm.keyword) { params.append('keyword', vm.keyword); }
        HIS.get('/api/his/inp/nurse/patients?' + params.toString()).then(function (d) {
          vm.patients = (d && d.records) || [];
          if (vm.currentVisitId != null) {
            var still = false;
            for (var i = 0; i < vm.patients.length; i++) {
              if (HIS.sameId(vm.patients[i].id, vm.currentVisitId)) { still = true; break; }
            }
            if (!still) { vm.currentVisitId = null; }
          }
        }).catch(HIS.notifyError).finally(function () { vm.loadingPat = false; });
      },
      /* 待办数据源: /api/his/inp/dashboard/nurse (todoStats/criticalPatients/nursingAlerts) */
      loadTodo: function () {
        var vm = this;
        if (!vm.wardId) {
          vm.todo = { pendingAudit: 0, pendingExec: 0, pendingAssess: 0, pendingShift: 0, pendingVitals: 0 };
          vm.criticalPatients = []; vm.nursingAlerts = [];
          vm.criticalTodo = 0;
          return;
        }
        /* 危急值未处理计数(T37): 按病区过滤, 并行拉取静默失败 */
        HIS.get('/api/his/inp/critical-value/unhandled-count?wardId=' + HIS.idParam(vm.wardId)).then(function (n) {
          vm.criticalTodo = Number(n) || 0;
        }).catch(function () { /* 静默 */ });
        /* 13.15.1 入出转待办聚合: 待入区(mid 病区级前端过滤蛇形键 ward_id) + 待审转科(status=1), 静默失败 */
        HIS.get('/api/his/inp/pre-admissions').then(function (rows) {
          var wid = String(vm.wardId);
          vm.preAdmitTotal = (rows || []).filter(function (r) {
            return r && (r.ward_id == null || String(r.ward_id) === wid);
          }).length;
        }).catch(function () { /* 静默 */ });
        HIS.get('/api/his/inp/transfer/list?status=1&page=1&size=200').then(function (d) {
          var recs = (d && d.records) || [];
          var wid = String(vm.wardId);
          vm.pendingTransferTotal = recs.filter(function (t) {
            return t && (t.fromWardId == null || String(t.fromWardId) === wid);
          }).length;
        }).catch(function () { /* 静默 */ });
        HIS.get('/api/his/inp/dashboard/nurse?wardId=' + HIS.idParam(vm.wardId)).then(function (d) {
          var o = d || {}, t = o.todoStats || {};
          vm.todo = {
            pendingAudit: t.pendingAudit || 0, pendingExec: t.pendingExec || 0,
            pendingAssess: t.pendingAssess || 0, pendingShift: t.pendingShift || 0,
            pendingVitals: t.pendingVitals || 0
          };
          vm.criticalPatients = o.criticalPatients || [];
          vm.nursingAlerts = o.nursingAlerts || [];
        }).catch(function () { /* 轮询静默失败不打扰 */ });
      },
      /* 患者横幅扩展数据: 护理等级(就诊详情) + 过敏(就诊维度仅有效) */
      loadPatExtra: function (id) {
        var vm = this;
        vm.patExtraFor = HIS.id(id);
        vm.patExtra = { nursingLevel: null, allergies: [] };
        if (id == null) { return; }
        HIS.get('/api/his/inp/visit/' + HIS.idParam(id)).then(function (d) {
          if (!HIS.sameId(vm.patExtraFor, id)) { return; }
          var v = (d && d.visit) || {};
          vm.patExtra.nursingLevel = v.nursingLevel == null ? null : v.nursingLevel;
        }).catch(function () { /* 详情失败不阻塞横幅 */ });
        HIS.get('/api/his/inp/allergy/list?visitId=' + HIS.idParam(id)).then(function (rows) {
          if (!HIS.sameId(vm.patExtraFor, id)) { return; }
          vm.patExtra.allergies = (rows || []).filter(function (a) { return a && a.status !== 0; });
        }).catch(function () { /* 过敏失败不阻塞横幅 */ });
      },
      conditionLabel: function (v) { return v == null ? '-' : (CONDITION_LEVEL[v] || '危重'); },
      nurseLvLabel: function (v) { return NURSING_LEVELS[v] || ''; },
      /* 护理等级色块: 特级红/一级橙/二级黄(无令牌, 字面量补位)/三级绿 */
      nurseLvStyle: function (v) {
        var bg = { 1: 'var(--yb-fill-danger)', 2: 'var(--yb-fill-warning)', 3: '#d4a017', 4: 'var(--yb-fill-success)' }[v];
        return bg ? { background: bg } : {};
      },
      admitDaysText: function (v) {
        var n = daysSince(v);
        return n == null ? '-' : n + ' 天';
      },
      selectPatient: function (p) { this.currentVisitId = HIS.id(p.id); },
      /* 床位一览联动: 单击选中患者, 双击直达医嘱审核页签 */
      bedPick: function (vid) { this.currentVisitId = vid; },
      bedOpen: function (vid) { this.currentVisitId = vid; this.activeTab = 'audit'; },
      /* 床位一览拖拽换床/右键菜单 → 直达入出转页签对应列表 */
      bedFlow: function (seg) { this.flowSeg = seg || 'approval'; this.activeTab = 'flow'; },
      /* 左侧 segment: 在区(onward) / 待入区(preadmit) / 转出待办(approval) → 切入出转页签对应列表 */
      segGo: function (seg) {
        this.flowSeg = seg;
        this.activeTab = 'flow';
      },
      /* 入出转面板数据变更(入区/审批/执行/出院): 刷新患者列表与待办聚合 */
      onFlowChanged: function () { this.loadPatients(); this.loadTodo(); },
      /* 页签分组色点(同组归一色) */
      grpColor: function (g) {
        return { '流转': 'var(--yb-brand)', '医嘱': '#E6A23C', '费用': 'var(--yb-success)', '总览': 'var(--yb-link)', '护理': '#7C3AED', '病区': 'var(--yb-ink-3)' }[g] || 'var(--yb-border)';
      },
      /* 页签角标(0/空不显) */
      tabBadge: function (key) {
        if (key === 'audit') { return this.todo.pendingAudit || 0; }
        if (key === 'exec') { return this.todo.pendingExec || 0; }
        if (key === 'assess') { return this.todo.pendingAssess || 0; }
        if (key === 'shift') { return this.todo.pendingShift || 0; }
        if (key === 'flow') { return (this.preAdmitTotal || 0) + (this.pendingTransferTotal || 0); }
        return 0;
      },
      /* 无图表/纯表格页签用 lazy 首次才挂载; 其余页签用 v-if+当前患者: 一激活即重挂载 */
      lazyTab: function (key) {
        return key === 'audit' || key === 'exec' || key === 'nursing' || key === 'shift' || key === 'bed';
      },
      /* 重置主页(清掉自定义页签顺序) */
      resetHome: function () {
        this.homeOrder = null;
        try { localStorage.removeItem('yb_nurse_home'); } catch (e) { /* 忽略 */ }
        HIS.notifySuccess('主页已重置为默认页签顺序');
      },
      /* 危急值待处理卡片点击: 跳转危急值管理页(T37, HIS.go 由 AppLayout 提供) */
      goCriticalValues: function () { if (typeof HIS.go === 'function') { HIS.go('critical-value'); } }
    },
    template: [
      '<div style="display:flex;height:calc(100vh - 88px);overflow:hidden;">',
      '  <div v-if="sideCollapsed" class="inp-rail" style="cursor:pointer" title="展开患者列表" @click="sideCollapsed=false">',
      '    <button class="inp-collapse-btn" @click.stop="sideCollapsed=false">≫</button>',
      '    <div class="inp-rail-text">患者列表 ({{ sortedPatients.length }})</div>',
      '  </div>',
      '  <div v-else class="inp-left">',
      '    <div style="padding:10px 12px 8px;">',
      '      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;">',
      '        <b style="font-size:var(--yb-fs-md);color:var(--yb-ink-1);">患者列表 ({{ sortedPatients.length }})</b>',
      '        <button class="inp-collapse-btn" title="折叠患者列表" @click="sideCollapsed=true">≪</button>',
      '      </div>',
      '      <el-select v-model="wardId" placeholder="选择病区" style="width:100%;" :loading="loadingWard" @change="onWardChange">',
      '        <el-option v-for="w in wards" :key="w.id" :label="w.wardName" :value="w.id"></el-option>',
      '      </el-select>',
      '      <el-input v-model="keyword" placeholder="姓名 / 住院号" clearable style="margin-top:8px;" @keyup.enter="loadPatients" @clear="loadPatients">',
      '        <template #append><el-button @click="loadPatients">查询</el-button></template>',
      '      </el-input>',
      '      <div class="inp-seg">',
      '        <span class="inp-seg-item" :class="{ \'is-active\': activeTab===\'flow\' && flowSeg===\'onward\' }" @click="segGo(\'onward\')">在区</span>',
      '        <span class="inp-seg-item" :class="{ \'is-active\': activeTab===\'flow\' && flowSeg===\'preadmit\' }" @click="segGo(\'preadmit\')">待入区<span v-if="preAdmitTotal" class="dot">{{ preAdmitTotal }}</span></span>',
      '        <span class="inp-seg-item" :class="{ \'is-active\': activeTab===\'flow\' && flowSeg===\'approval\' }" @click="segGo(\'approval\')">转出待办<span v-if="pendingTransferTotal" class="dot">{{ pendingTransferTotal }}</span></span>',
      '      </div>',
      '    </div>',
      '    <el-scrollbar style="flex:1;min-height:0;">',
      '      <div v-for="p in sortedPatients" :key="p.id" class="inp-patient" :class="{ \'is-active\': sameId(p.id, currentVisitId) }" @click="selectPatient(p)">',
      '        <div>',
      '          <span class="inp-bed-no">{{ p.bedNo || \'—\' }}</span>',
      '          <span class="nm">{{ p.patientName || \'-\' }}</span>',
      '          <span style="color:var(--yb-ink-3);font-size:12px;margin-left:6px;">{{ patientInfo(p) }}</span>',
      '        </div>',
      '        <div class="sub">住院号 {{ p.inpNo || \'-\' }} · 入院 {{ datePart(p.admitDate) }}</div>',
      '      </div>',
      '      <el-empty v-if="!loadingPat && !sortedPatients.length" :description="wardId ? \'本病区暂无在院患者\' : \'请先选择病区\'" :image-size="56"></el-empty>',
      '    </el-scrollbar>',
      '    <div style="padding:6px 12px;border-top:1px solid var(--yb-divider);color:var(--yb-ink-4);font-size:12px;display:flex;align-items:center;justify-content:space-between;">在院患者 {{ sortedPatients.length }} 人<a style="color:var(--yb-link);cursor:pointer;" @click="resetHome">重置主页</a></div>',
      '  </div>',
      '  <div style="flex:1;min-width:0;display:flex;flex-direction:column;overflow:hidden;">',
      /* 待办6卡: 品牌蓝(审核)/绿(执行)/橙(评估)/紫(交班)/红(危重)/红(危急值T37) */
      '    <div class="inp-todo is-6" style="padding:10px 14px 0;">',
      '      <div class="inp-todo-card is-brand" @click="segGo(\'preadmit\')">',
      '        <span class="num">{{ preAdmitTotal }}</span>',
      '        <span class="lbl">待入区</span>',
      '        <span class="hint">点击直达 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-warn" @click="segGo(\'approval\')">',
      '        <span class="num">{{ pendingTransferTotal }}</span>',
      '        <span class="lbl">待审转科</span>',
      '        <span class="hint">点击直达 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-brand" @click="activeTab = \'audit\'">',
      '        <span class="num">{{ todo.pendingAudit || 0 }}</span>',
      '        <span class="lbl">待审核医嘱</span>',
      '        <span class="hint">点击处理 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-success" @click="activeTab = \'exec\'">',
      '        <span class="num">{{ todo.pendingExec || 0 }}</span>',
      '        <span class="lbl">待执行医嘱</span>',
      '        <span class="hint">点击处理 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-warn" @click="activeTab = \'assess\'">',
      '        <span class="num">{{ todo.pendingAssess || 0 }}</span>',
      '        <span class="lbl">待评估患者</span>',
      '        <span class="hint">点击处理 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-purple" @click="activeTab = \'shift\'">',
      '        <span class="num">{{ todo.pendingShift || 0 }}</span>',
      '        <span class="lbl">交班待确认</span>',
      '        <span class="hint">点击处理 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-danger" @click="critOpen = !critOpen">',
      '        <span class="num">{{ criticalPatients.length }}</span>',
      '        <span class="lbl">危重患者</span>',
      '        <span class="hint">点击展开 →</span>',
      '      </div>',
      '      <div class="inp-todo-card is-critical" @click="goCriticalValues">',
      '        <span class="num" :class="{ \'is-alerting\': criticalTodo > 0 }">{{ criticalTodo }}</span>',
      '        <span class="lbl">危急值</span>',
      '        <span class="hint">点击处理 →</span>',
      '      </div>',
      '    </div>',
      /* 危重患者提示条(默认收起, 点击展开明细) + 护理预警滚动条 */
      '    <div style="padding:0 14px;">',
      '      <div v-if="criticalPatients.length" class="inp-critbar">',
      '        <div class="inp-crit-head" @click="critOpen = !critOpen">',
      '          <span class="ic"><ns-icon name="warning-filled"></ns-icon></span>',
      '          <span class="txt">当前病区 {{ criticalPatients.length }} 位危重患者: {{ critSummary }}</span>',
      '          <span class="toggle">{{ critOpen ? \'收起 ∧\' : \'展开 ∨\' }}</span>',
      '        </div>',
      '        <el-collapse-transition>',
      '          <div v-show="critOpen" class="inp-crit-body">',
      '            <div v-for="c in criticalPatients" :key="c.visitId" class="inp-crit-row">',
      '              <span class="bd">{{ c.bedNo || \'—\' }}</span>',
      '              <span class="nm">{{ c.patientName || \'-\' }}</span>',
      '              <el-tag size="small" type="danger" effect="plain">{{ conditionLabel(c.conditionLevel) }}</el-tag>',
      '              <span class="diag" :title="c.admitDiag || \'\'">诊断: {{ c.admitDiag || \'-\' }}</span>',
      '              <span v-if="c.nursingLevel" class="inp-nurse-lv" :style="nurseLvStyle(c.nursingLevel)">{{ nurseLvLabel(c.nursingLevel) }}</span>',
      '              <span>入院 {{ admitDaysText(c.admitDate) }}</span>',
      '            </div>',
      '          </div>',
      '        </el-collapse-transition>',
      '      </div>',
      '      <div v-if="alertItems.length" class="inp-alertbar">',
      '        <span class="ic"><ns-icon name="warning"></ns-icon></span>',
      '        <span v-if="alertItems.length <= 2" class="txt" :title="alertItems.join(\' | \')">{{ alertItems.join(\' | \') }}</span>',
      '        <div v-else class="inp-alert-mask">',
      '          <div class="inp-alert-track is-marquee" :style="{ animationDuration: alertDuration }">',
      '            <template v-for="rep in 2" :key="rep">',
      '              <span v-for="(a, i) in alertItems" :key="rep + \'-\' + i" class="inp-alert-item">{{ a }}</span>',
      '            </template>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      /* 患者信息固定横幅(Tab 页签上方, 切换页签不消失) */
      '    <div v-if="currentVisitId" class="inp-banner">',
      '      <div class="bedblk">{{ currentPatient ? (currentPatient.bedNo || \'—\') : \'—\' }}</div>',
      '      <span class="nm">{{ currentPatient ? currentPatient.patientName : \'\' }}</span>',
      '      <span class="meta">{{ currentPatient ? patientInfo(currentPatient) : \'\' }}</span>',
      '      <span class="sep">|</span>',
      '      <span class="meta">住院号 {{ currentPatient ? (currentPatient.inpNo || \'-\') : \'-\' }}</span>',
      '      <span class="sep">|</span>',
      '      <span class="diag" :title="currentPatient && currentPatient.admitDiag ? currentPatient.admitDiag : \'\'">诊断 {{ currentPatient && currentPatient.admitDiag ? currentPatient.admitDiag : \'-\' }}</span>',
      '      <span class="sep">|</span>',
      '      <span v-if="allergyText" class="inp-allergy is-danger" :title="allergyDetail">过敏 {{ allergyText }}</span>',
      '      <span v-else class="inp-allergy is-safe">无过敏</span>',
      '      <span class="grow"></span>',
      '      <span v-if="patExtra.nursingLevel" class="inp-nurse-lv" :style="nurseLvStyle(patExtra.nursingLevel)">{{ nurseLvLabel(patExtra.nursingLevel) }}</span>',
      '      <span class="meta">入院 {{ admitDaysText(currentPatient && currentPatient.admitDate) }}</span>',
      '    </div>',
      '    <el-tabs v-model="activeTab" class="inp-tabs">',
      /* 页签顺序与 HOME_TABS 一致(流转→医嘱→费用→总览→护理→病区); 分组色点经 grpColor 统一起义 */
      '      <el-tab-pane name="flow">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'流转\') }"></i>入出转<span v-if="tabBadge(\'flow\')" class="inp-tab-num">{{ tabBadge(\'flow\') }}</span></span></template>',
      '        <inp-flow v-if="activeTab===\'flow\'" :key="\'flow-\'+flowSeg" :ward-id="wardId" :wards="wards" :patients="patients" :visit-id="currentVisitId" :patient="currentPatient" :initial-seg="flowSeg" @changed="onFlowChanged" @pick="bedPick"></inp-flow>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="audit" lazy>',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'医嘱\') }"></i>医嘱审核<span v-if="todo.pendingAudit" class="inp-tab-num">{{ todo.pendingAudit }}</span></span></template>',
      '        <inp-order-audit :ward-id="wardId" @changed="loadTodo"></inp-order-audit>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="exec" lazy>',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'医嘱\') }"></i>医嘱执行<span v-if="todo.pendingExec" class="inp-tab-num">{{ todo.pendingExec }}</span></span></template>',
      '        <inp-order-exec :ward-id="wardId" @changed="loadTodo"></inp-order-exec>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="fee">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'费用\') }"></i>费用管理</span></template>',
      '        <inp-fee v-if="activeTab===\'fee\'" :key="\'fee-\'+(currentVisitId||0)" :ward-id="wardId" :patients="patients" :visit-id="currentVisitId" :patient="currentPatient" @changed="loadPatients"></inp-fee>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="patient">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'总览\') }"></i>病人信息</span></template>',
      '        <inp-patient360 v-if="activeTab===\'patient\'" :key="\'p360-\'+(currentVisitId||0)" :visit-id="currentVisitId" :patient="currentPatient" @goto-fee="activeTab=\'fee\'"></inp-patient360>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="nursing" lazy>',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'护理\') }"></i>护理记录</span></template>',
      '        <inp-nursing-record :visit-id="currentVisitId" :patient="currentPatient" @go-assess="activeTab = \'assess\'"></inp-nursing-record>',
      '      </el-tab-pane>',
      /* 出入量/给药记录(T45): v-if + :key 携带就诊ID, 切患者重挂载不串台 */
      '      <el-tab-pane name="io">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'护理\') }"></i>出入量</span></template>',
      '        <inp-io-record v-if="activeTab === \'io\'" :key="\'io-\' + (currentVisitId || 0)" :ward-id="wardId" :visit-id="currentVisitId" :patient="currentPatient"></inp-io-record>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="med">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'护理\') }"></i>给药记录</span></template>',
      '        <inp-med-admin v-if="activeTab === \'med\'" :key="\'med-\' + (currentVisitId || 0)" :ward-id="wardId" :visit-id="currentVisitId" :patient="currentPatient"></inp-med-admin>',
      '      </el-tab-pane>',
      /* 护理评估/护理计划: v-if + :key 模式(仅激活时挂载, 避免隐藏态 ECharts 零尺寸) */
      '      <el-tab-pane name="assess">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'护理\') }"></i>护理评估<span v-if="todo.pendingAssess" class="inp-tab-num">{{ todo.pendingAssess }}</span></span></template>',
      '        <inp-nursing-assessment v-if="activeTab === \'assess\'" :key="\'nsa-\' + (currentVisitId || 0)" :visit-id="currentVisitId"></inp-nursing-assessment>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="plan">',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'护理\') }"></i>护理计划</span></template>',
      '        <inp-nursing-plan v-if="activeTab === \'plan\'" :key="\'nsp-\' + (currentVisitId || 0)" :visit-id="currentVisitId" :patient="currentPatient"></inp-nursing-plan>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="shift" lazy>',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'病区\') }"></i>交接班<span v-if="todo.pendingShift" class="inp-tab-num">{{ todo.pendingShift }}</span></span></template>',
      '        <inp-shift-handover :ward-id="wardId"></inp-shift-handover>',
      '      </el-tab-pane>',
      '      <el-tab-pane name="bed" lazy>',
      '        <template #label><span><i class="inp-grp-dot" :style="{ background: grpColor(\'病区\') }"></i>床位一览</span></template>',
      '        <inp-bed-overview ref="bedOv" :ward-id="wardId" @pick="bedPick" @open="bedOpen" @flow="bedFlow"></inp-bed-overview>',
      '      </el-tab-pane>',
      '    </el-tabs>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

})();
