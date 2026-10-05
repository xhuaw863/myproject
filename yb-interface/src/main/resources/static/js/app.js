/* 医保原生HIS - 应用入口: 登录页 + 主布局 + 菜单路由 + Vue挂载 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};
  var createApp = Vue.createApp;

  /* ===== 菜单定义 =====
   * comp: HIS.views 中的组件键(有则渲染该页); 无则渲染 Placeholder(建设中)。
   */
  var MENU = [
    { key: 'dashboard', label: '工作台', comp: 'Dashboard' },
    {
      group: '医共体管理', children: [
        { key: 'tenant-info', label: '医院信息', comp: 'TenantInfo' },
        { key: 'user-manage', label: '用户管理', comp: 'UserManage' },
        { key: 'dept', label: '科室管理', comp: 'DeptManage' },
        { key: 'staff', label: '职工管理', comp: 'StaffManage' },
        { key: 'patient', label: '档案管理', comp: 'PatientManage' },
        { key: 'org-manage', label: '机构管理', comp: 'OrgManage' },
        { key: 'role-manage', label: '角色权限', comp: 'RoleManage' },
        { key: 'menu-manage', label: '菜单管理', comp: 'MenuManage' },
        { key: 'system-param', label: '系统参数', comp: 'SystemParam' },
        {
          group: '基础数据', children: [
            { key: 'catalog-map', label: '医保目录对照', comp: 'CatalogMap' },
            { key: 'diag-map', label: '医保疾病对照', comp: 'DiagMap' },
            { key: 'community-dict', label: '医共体字典', comp: 'CommunityDict' },
            { key: 'supplier-dict', label: '企业字典', comp: 'SupplierDict' },
            { key: 'fee-pay-dict', label: '费别与支付', comp: 'FeePayDict' },
            { key: 'med-type-dict', label: '医疗类别', comp: 'MedTypeDict' }
          ]
        },
        {
          group: '医保字典', children: [
            { key: 'dict-download', label: '字典下载', comp: 'DictDownload' },
            { key: 'dict-version', label: '版本状态', comp: 'DictVersion' },
            { key: 'upload-center', label: '医保上报中心', comp: 'UploadCenter' },
            { key: 'recon-console', label: '医保对账台', comp: 'ReconConsole' },
            { key: 'yb-txn-log', label: '医保接口日志', comp: 'YbTxnLog' },
            { key: 'verify-console', label: '医保验证台' }
          ]
        },
        {
          group: '标准字典', children: [
            { key: 'std-dict-browse', label: '字典浏览', comp: 'StdDictBrowse' },
            { key: 'std-dict-import', label: '提取入库', comp: 'StdDictImport' },
            { key: 'area-code', label: '行政区划', comp: 'AreaManage' }
          ]
        }
      ]
    },
    {
      group: '机构维护管理', children: [
        { key: 'org-catalog', label: '机构目录选用', comp: 'OrgCatalog' }
      ]
    },
    {
      group: '门诊挂号收费', children: [
        { key: 'register', label: '门诊挂号', comp: 'RegistrationDesk' },
        { key: 'unregister', label: '退号换号', comp: 'UnregisterDesk' },
        { key: 'schedule', label: '排班号源', comp: 'ScheduleManage' },
        { key: 'reg_stats', label: '挂号统计', comp: 'RegStatistics' },
        { key: 'reg_detail', label: '挂号明细', comp: 'RegDetailQuery' },
        { key: 'charge-ws', label: '门诊收费', comp: 'ChargeWorkstation' },
        { key: 'charge-todo', label: '待收费', comp: 'ChargeTodo' },
        { key: 'charge-setl', label: '收费结算记录', comp: 'ChargeSetl' },
        { key: 'charge-refund', label: '退费记录', comp: 'ChargeRefund' },
        { key: 'invoice-mgr', label: '发票管理', comp: 'InvoiceManage' },
        { key: 'charge-rpt', label: '收费统计', comp: 'ChargeReport' },
        { key: 'rpt-setl', label: '结算记录', comp: 'SettleRecords' },
        { key: 'rpt-daily', label: '门诊日结', comp: 'DailySettle' }
      ]
    },
    {
      group: '门诊医生站', children: [
        { key: 'doctor-ws', label: '门诊医生工作站', comp: 'DoctorWorkstation' },
        { key: 'doctor-worklog', label: '诊疗统计查询', comp: 'DoctorWorklog' },
        { key: 'medical-template', label: '医疗模板管理', comp: 'MedicalTemplateManage' }
      ]
    },
    /* 病历数据集管理(P1a-2, 注册键 emr-dataset 与 comp 同值): 章节/小节/数据元三级结构维护;
     * RbacInitializer 已补种(ensureEmrQualityMenus 挂 emr-quality 目录, 同 key 同名, 管理职能 all_menus 可见) */
    {
      group: '病历质控与数据元', children: [
        { key: 'emr-dataset', label: '数据集管理', comp: 'emr-dataset' },
        /* 患者全景时间线(注册键 emr-patient-timeline 与 comp 同值): 门诊+住院就诊史统一时间轴;
         * 只读聚合页, 后端 EmrTimelineController(须在 RbacInitializer 补种菜单与角色授权) */
        { key: 'emr-patient-timeline', label: '患者时间线', comp: 'emr-patient-timeline' },
        /* 类 Word 病历模板设计器：唯一模板设计入口；旧字段画布入口已隐藏。 */
        { key: 'emr-template-designer', label: '病历模板设计器', comp: 'emr-template-designer' },
        { key: 'emr-quality-rule', label: '质控规则维护', comp: 'EmrQualityRuleManage' },
        { key: 'emr-element-search', label: '病历检索上报', comp: 'EmrElementSearch' },
        { key: 'emr-quality-board', label: '质控评分看板', comp: 'EmrQualityBoard' },
        { key: 'emr-audit-log', label: '病历审计日志', comp: 'EmrAuditLog' },
        /* P7a-4 归档工作台(comp=EmrArchive): 顶部KPI + 待归档/归档管理/封存管理/统计/Webhook订阅五页签;
         * 动态菜单需 RbacInitializer 补种 emr-archive(后端任务), 此静态项为 /api/auth/menus 失败时兜底 */
        { key: 'emr-archive', label: '归档工作台', comp: 'EmrArchive' },
        /* P7b-3 等级自评(comp=EmrLevelAssess): 等级徽章+八维度SVG雷达+维度明细卡;
         * 动态菜单需 RbacInitializer 补种 emr-level-assess, 此静态项为 /api/auth/menus 失败时兜底 */
        { key: 'emr-level-assess', label: '等级自评', comp: 'EmrLevelAssess' }
      ]
    },
    {
      group: '病案统计', children: [
        { key: 'mr-assign', label: '病案分配', comp: 'MrAssign' },
        { key: 'mr-catalog', label: '首页编目', comp: 'MrCatalog' },
        { key: 'mr-review', label: '质量审核', comp: 'MrReview' },
        { key: 'mr-recall', label: '病案收回', comp: 'MrRecall' },
        { key: 'mr-borrow', label: '病案借阅', comp: 'MrBorrow' },
        { key: 'mr-annotation', label: '批注反馈', comp: 'MrAnnotation' },
        { key: 'mr-search', label: '检索查询', comp: 'MrSearch' },
        { key: 'mr-workload', label: '工作量统计', comp: 'MrWorkload' },
        { key: 'mr-maintain', label: '系统维护字典', comp: 'MrMaintain' },
        { key: 'mr-report', label: '报表统计', comp: 'MrReport' },
        { key: 'mr-submit', label: '上报闭环', comp: 'MrSubmit' },
                /* P3 集成 */
                { key: 'mr-drg', label: 'DRG分组对比', comp: 'MrDrg' },
                { key: 'mr-quality-diff', label: '质控前后对比', comp: 'MrQualityDiff' }
      ]
    },
    {
      group: '药房系统', children: [
        /* 门诊发药 */
        { key: 'dispense-todo', label: '待发药', comp: 'DispenseTodo' },
        { key: 'window-workstation', label: '发药工作站', comp: 'WindowWorkstation' },
        { key: 'dispense', label: '调剂发药', comp: 'DispenseRecord' },
        { key: 'rx-audit', label: '处方审核', comp: 'OutpRxAudit' },
        { key: 'drug-return', label: '退药', comp: 'DrugReturn' },
        /* 住院发药(原"住院药师站"顶级目录并入药房系统, 与动态菜单 ph-inp 同 key 同名) */
        { key: 'pharm-station', label: '药师审核', comp: 'pharm-station' },
        { key: 'inp-dispense-work', label: '住院发药工作台', comp: 'InpDispenseWork' },
        { key: 'inp-discharge-pickup', label: '出院带药核发', comp: 'InpDischargePickup' },
        { key: 'inp-dispense-history', label: '住院发药历史', comp: 'InpDispenseHistory' },
        /* 药房运营与追溯 */
        { key: 'pharmacy-def', label: '药房管理', comp: 'PharmacyDef' },
        { key: 'req-mgr', label: '药品请领', comp: 'RequisitionManage' },
        { key: 'pharmacy-window', label: '发药窗口', comp: 'PharmacyWindowManage' },
        { key: 'window-dept-rule', label: '科室定向窗口', comp: 'WindowDeptRule' },
        { key: 'pharmacy-cross', label: '跨药房配置', comp: 'PharmacyCrossConfig' },
        { key: 'rx-pharmacy-route', label: '处方发药默认药房', comp: 'RxPharmacyRouteConfig' },
        { key: 'trace-code', label: '药品追溯码', comp: 'TraceCodeManage' },
        /* 统计查询 */
        { key: 'pharmacy-rpt', label: '药房统计', comp: 'PharmacyReport' },
        { key: 'stat-usage', label: '药品消耗分析', comp: 'PharmacyUsageStat' },
        { key: 'stat-yb', label: '医保合规分析', comp: 'PharmacyYbStat' },
        { key: 'stat-quality', label: '处方与退药质量', comp: 'PharmacyQualityStat' }
      ]
    },
    {
      group: '药库系统', children: [
        /* 药品采购 */
        { key: 'supplier-mgr', label: '供应商管理', comp: 'SupplierManage' },
        { key: 'purchase-rule', label: '采购规则', comp: 'PurchaseRule' },
        { key: 'purchase-plan', label: '采购计划', comp: 'PurchasePlan' },
        { key: 'purchase-order', label: '采购订单', comp: 'PurchaseOrder' },
        /* 库存作业(采购入库已含购入方式/发票/定向出库/多单位/冲红) */
        { key: 'wh-in', label: '采购入库', comp: 'StockInManage' },
        { key: 'wh-out', label: '出库管理', comp: 'StockOutManage' },
        { key: 'trf-mgr', label: '库存调拨', comp: 'TransferManage' },
        { key: 'wh-check', label: '盘点', comp: 'StockCheck' },
        { key: 'wh-stock', label: '库存/流水', comp: 'DrugStock' },
        { key: 'warehouse-def', label: '药库管理', comp: 'WarehouseDef' },
        { key: 'wh-drug', label: '药品目录', comp: 'DrugCatalogView' },
        /* 财务结算 */
        { key: 'stock-accept', label: '财务验收', comp: 'StockAccept' },
        { key: 'supplier-pay', label: '供应商付款', comp: 'SupplierPayment' },
        { key: 'payable-rpt', label: '应付账款', comp: 'PayableReport' },
        { key: 'price-adjust', label: '药品调价', comp: 'PriceAdjust' },
        { key: 'month-end', label: '库房月结', comp: 'MonthEnd' },
        /* 账簿统计(进销存台账已含进价/零售口径与财务/实物账) */
        { key: 'stock-ledger', label: '进销存台账', comp: 'DrugLedger' },
        { key: 'warehouse-rpt', label: '药库统计', comp: 'WarehouseReport' },
        /* 养护管理 */
        { key: 'drug-maint', label: '药品养护', comp: 'DrugMaintenance' },
        { key: 'maint-template', label: '养护模板', comp: 'MaintenanceTemplate' }
      ]
    },
    /* 2026-09 三模块基座(静态兑底菜单, 与 RbacInitializer 动态菜单同 key 同名):
     * 护士站(nurse.js)/治疗管理(treatment.js)/医技管理(medtech.js) */
    {
      group: '门诊护士站', children: [
        { key: 'nurse-pending', label: '待执行医嘱', comp: 'NursePending' },
        { key: 'nurse-skin-test', label: '皮试管理', comp: 'NurseSkinTest' },
        { key: 'nurse-infusion', label: '输液管理', comp: 'NurseInfusion' },
        { key: 'nurse-allergy', label: '过敏档案', comp: 'NurseAllergy' },
        { key: 'nurse-exec-log', label: '执行记录查询', comp: 'NurseExecLog' }
      ]
    },
    {
      group: '治疗管理', children: [
        { key: 'treatment-pending', label: '待执行治疗', comp: 'TreatmentPending' },
        { key: 'treatment-plan', label: '疗程管理', comp: 'TreatmentPlan' },
        { key: 'treatment-equip', label: '设备管理', comp: 'TreatmentEquipment' },
        { key: 'treatment-log', label: '治疗记录查询', comp: 'TreatmentLog' }
      ]
    },
    {
      group: '医技管理', children: [
        { key: 'medtech-specimen', label: '标本管理', comp: 'MedtechSpecimen' },
        { key: 'medtech-report', label: '报告工作站', comp: 'MedtechReport' },
        { key: 'medtech-critical', label: '危急值管理', comp: 'MedtechCritical' },
        { key: 'medtech-critical-rule', label: '危急值规则', comp: 'MedtechCriticalRule' },
        { key: 'medtech-report-query', label: '报告查询', comp: 'MedtechReportQuery' }
      ]
    },
    /* 影像中心(RIS 2026-10 Task#7 集成, 静态兑底菜单, 与 RbacInitializer 动态菜单同 key 同名):
     * 检查申请/排程(ris-schedule.js 内同时注册 RisSchedule 与 RisScheduleTemplate 两组件)/技师工作台/
     * 报告书写/报告审核/设备/报告模板/质控/统计/影像云(ris/*.js 共10个文件);
     * 角色授权: 技师(ris-worklist/ris-schedule/ris-request)、医生(ris-report/ris-review/ris-request/ris-statistics)、
     * 护士(ris-request)、机构管理员(ris-device/ris-template/ris-qc/ris-statistics/ris-cloud) */
    {
      group: '影像中心', children: [
        { key: 'ris-request', label: '检查申请查询', comp: 'RisRequest' },
        { key: 'ris-schedule', label: '检查排程管理', comp: 'RisSchedule' },
        { key: 'ris-schedule-tpl', label: '排程模板管理', comp: 'RisScheduleTemplate' },
        { key: 'ris-worklist', label: '技师工作台', comp: 'RisWorklist' },
        { key: 'ris-report', label: '报告书写', comp: 'RisReport' },
        { key: 'ris-review', label: '报告审核', comp: 'RisReview' },
        { key: 'ris-device', label: '设备管理', comp: 'RisDevice' },
        { key: 'ris-template', label: '报告模板管理', comp: 'RisTemplate' },
        { key: 'ris-qc', label: '质控管理', comp: 'RisQc' },
        { key: 'ris-statistics', label: '统计分析', comp: 'RisStatistics' },
        { key: 'ris-cloud', label: '影像云管理', comp: 'RisCloud' }
      ]
    },
    /* 住院模块基座(静态兑底菜单, 与 RbacInitializer 动态菜单同 key 同名):
     * 登记结算(inpatient.js)/医生站(inp-doctor/*.js)/护士站(inp-nurse.js)。
     * 医生站/护士站多叶子共用同一工作站组件, 入口 key 区分定位页签。 */
    {
      group: '住院登记结算', children: [
        { key: 'inp-admission', label: '入院登记', comp: 'InpAdmission' },
        { key: 'inp-patient-list', label: '在院患者管理', comp: 'InpPatientList' },
        { key: 'inp-bed-manage', label: '床位管理', comp: 'InpBedManage' },
        { key: 'inp-deposit', label: '预交金管理', comp: 'InpDeposit' },
        { key: 'inp-charge-list', label: '住院费用清单', comp: 'InpChargeList' },
        { key: 'inp-settle', label: '出院结算', comp: 'InpSettle' },
        { key: 'inp-daily-summary', label: '住院日报', comp: 'InpDailySummary' }
      ]
    },
    {
      group: '住院医生站', children: [
        { key: 'inp-doctor-ws', label: '住院医生工作站', comp: 'InpDoctorWorkstation' },
        { key: 'inp-order-manage', label: '医嘱管理', comp: 'InpDoctorWorkstation' },
        { key: 'inp-order-template', label: '医嘱模板/套餐', comp: 'InpOrderTemplateManage' },
        { key: 'inp-diagnosis', label: '住院诊断', comp: 'InpDoctorWorkstation' },
        { key: 'inp-med-record', label: '住院病历', comp: 'InpDoctorWorkstation' },
        { key: 'inp-patient-overview', label: '患者概览', comp: 'InpDoctorWorkstation' }
      ]
    },
    {
      group: '住院护士站', children: [
        { key: 'inp-nurse-ws', label: '住院护士工作站', comp: 'InpNurseStation' },
        { key: 'inp-order-audit', label: '医嘱审核', comp: 'InpNurseStation' },
        { key: 'inp-order-exec', label: '医嘱执行', comp: 'InpNurseStation' },
        { key: 'inp-nursing-record', label: '护理记录', comp: 'InpNurseStation' },
        { key: 'inp-shift-handover', label: '交接班', comp: 'InpNurseStation' },
        { key: 'inp-bed-overview', label: '床位一览', comp: 'InpNurseStation' }
      ]
    },
    /* 住院危急值闭环(T37): 处理闭环(通知→确认→处置→关闭)+规则管理(critical-value.js)。
     * 通知铃铛/医生工作台/护士站待办均以 HIS.go('critical-value') 定位本页;
     * 注意: RbacInitializer 动态菜单暂未种植本菜单行, 动态菜单模式下入口待后端补种 */
    {
      group: '危急值闭环', children: [
        { key: 'critical-value', label: '危急值管理', comp: 'critical-value' }
      ]
    },
    /* 住院药师站(T35审核 + P4发药增强)已于 2026-11 药房整合并入"药房系统"分组(见上),
     * 独立顶级目录 inp-pharm-group 废弃; pharm-station/inp-dispense-work/inp-discharge-pickup/inp-dispense-history
     * 四条静态兑底入口现列于"药房系统"组下, 与 RbacInitializer 动态菜单 ph-inp 子域同 key 同名。 */
    /* 临床路径与手术麻醉(2026-09 集成, 与 RbacInitializer 动态菜单同 key 同名):
     * 路径模板管理(clinical-pathway.js)/路径统计质控(clinical-pathway-stats.js);
     * 手术管理·麻醉记录·手麻记费(surgery-manage.js); 手麻P0: 手术申请管理含通知管理(surgery-apply.js) */
    {
      group: '临床路径', children: [
        { key: 'pathway-template', label: '路径模板管理', comp: 'ClinicalPathwayManage' },
        { key: 'pathway-stats', label: '路径统计质控', comp: 'ClinicalPathwayStats' }
      ]
    },
    {
      group: '手术麻醉', children: [
        { key: 'surgery-manage', label: '手术管理', comp: 'SurgeryManage' },
        { key: 'surgery-apply', label: '手术申请管理', comp: 'SurgeryApply' },
        { key: 'anesthesia-record', label: '麻醉记录', comp: 'AnesthesiaRecord' },
        { key: 'surgery-fee', label: '手麻记费', comp: 'SurgeryFee' },
        { key: 'surgery-report', label: '手术统计报表', comp: 'SurgeryReport' }
      ]
    },
    /* 住院报表(2026-09 报表/打印模块, 与 RbacInitializer 动态菜单同 key 同名):
     * 报表中心(inp-report.js)/打印管理(inp-print.js) */
    {
      group: '住院报表', children: [
        { key: 'inp-report', label: '报表中心', comp: 'InpReportCenter' },
        { key: 'inp-print', label: '打印管理', comp: 'InpPrintCenter' }
      ]
    },
    /* 移动护理(T48 P3, 与 RbacInitializer 动态菜单同 key 同名):
     * PDA扫码工作台(pda-simulation.js); 移动护理终端为独立移动端入口(/mobile/index.html, 原生CSS单页不挂主框架), onSelect 外链新标签打开 */
    {
      group: '移动护理', children: [
        { key: 'pda-simulation', label: 'PDA扫码', comp: 'pda-simulation' },
        { key: 'mobile-nurse', label: '移动护理', comp: 'mobile-nurse' }
      ]
    },
    /* 会诊统一流程(P6, 与 RbacInitializer 动态菜单同 key 同名): 全院会诊流转驾驶舱
     * (多维查询/统计分析/超时预警三页签, consultation-manage.js); 住院/门诊统一接口 /api/his/consultation */
    {
      group: '会诊管理', children: [
        { key: 'consultation-manage', label: '会诊管理', comp: 'ConsultationManage' }
      ]
    },
    /* 公共卫生管理(报卡集中审核, 与 RbacInitializer 动态菜单 public-health 同 key 同名):
     * 传染病报卡审核(report-audit-inf.js ReportAuditInf, cats=[1])/慢病报卡审核(report-audit-chronic.js ReportAuditChronic, cats=[2,3,4,5]);
     * 报卡触发规则维护(trigger-rule-manage.js TriggerRuleManage, 全局规则表增删改启停);
     * ADMIN/SUPER_ADMIN 走 all_menus 可见, 后端 page/stats/export/trigger-rules 有 requireAdminOrSuper 守卫 */
    {
      group: '公共卫生管理', children: [
        { key: 'report-audit-inf', label: '传染病报卡审核', comp: 'ReportAuditInf' },
        { key: 'report-audit-chronic', label: '慢病报卡审核', comp: 'ReportAuditChronic' },
        { key: 'report-trigger-rule', label: '报卡触发规则维护', comp: 'TriggerRuleManage' }
      ]
    }
  ];

  function findItem(key) {
    return findItemIn(MENU, key);
  }

  /* 在给定菜单结构中按键查找项(递归, 兼容任意层级目录子项) */
  function findItemIn(menu, key) {
    for (var i = 0; i < (menu || []).length; i++) {
      var m = menu[i];
      if (m.key === key) { return m; }
      if (m.children) {
        var hit = findItemIn(m.children, key);
        if (hit) { return hit; }
      }
    }
    return null;
  }

  /* 取菜单首个可渲染项的键(递归, 动态菜单加载后校正默认激活项) */
  function firstKey(menu) {
    for (var i = 0; i < (menu || []).length; i++) {
      var m = menu[i];
      if (m.key) { return m.key; }
      if (m.children && m.children.length) {
        var k = firstKey(m.children);
        if (k) { return k; }
      }
    }
    return 'dashboard';
  }

  /* 将后端菜单树节点({menuKey,menuName,comp,phase,children})归一为渲染结构(递归, 支持任意层级) */
  function normalizeMenu(nodes) {
    var out = [];
    (nodes || []).forEach(function (n) {
      if (n.children && n.children.length) {
        out.push({ group: n.menuName, children: normalizeMenu(n.children) });
      } else {
        out.push({ key: n.menuKey, label: n.menuName, comp: n.comp, phase: n.phase });
      }
    });
    return out;
  }

  /* ===== 登录页 =====
   * 医院开通不再自助注册: 由平台超级管理员登录后台「医院管理」统一开通并分配权限。
   */
  var LoginPage = {
    emits: ['logged'],
    data: function () {
      return {
        loading: false,
        recent: [],
        recentIdx: 0,
        rememberPwd: false,
        loginForm: { tenantCode: 'H42010000000', username: 'admin', password: 'admin123' }
      };
    },
    created: function () {
      this.recent = HIS.getRecentAccounts();
      if (this.recent.length) { this.applyRecent(0); }
    },
    methods: {
      applyRecent: function (i) {
        var a = this.recent[i];
        if (!a) { return; }
        this.recentIdx = i;
        this.loginForm = { tenantCode: a.tenantCode, username: a.username, password: a.password || '' };
      },
      doLogin: function () {
        var vm = this;
        if (!vm.loginForm.tenantCode || !vm.loginForm.username || !vm.loginForm.password) {
          ElementPlus.ElMessage.warning('请填写医院码、账号与密码'); return;
        }
        vm.loading = true;
        HIS.post('/api/auth/login', vm.loginForm)
          .then(function (d) {
            HIS.setToken(d.token);
            HIS.setUser(d);
            HIS.rememberAccount(vm.loginForm, vm.rememberPwd);
            HIS.notifySuccess('登录成功');
            vm.$emit('logged');
          })
          .catch(HIS.notifyError)
          .finally(function () { vm.loading = false; });
      }
    },
    template: [
      '<div class="login-wrap">',
      '  <div class="login-box" style="width:420px;">',
      '    <h2>医保原生 HIS</h2>',
      '    <div class="sub">多租户 · 医院信息系统 · 医保接口原生对接</div>',
      '    <el-form :model="loginForm" label-width="80px" @submit.prevent>',
      '      <el-form-item label="最近账号" v-if="recent.length">',
      '        <el-select v-model="recentIdx" style="width:100%" placeholder="选择最近登录账号" @change="applyRecent">',
      '          <el-option v-for="(a,i) in recent" :key="i" :label="a.username + \' @ \' + a.tenantCode" :value="i"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-form-item label="医院码"><el-input v-model="loginForm.tenantCode" placeholder="医院登录码"></el-input></el-form-item>',
      '      <el-form-item label="账号"><el-input v-model="loginForm.username" placeholder="账号"></el-input></el-form-item>',
      '      <el-form-item label="密码"><el-input v-model="loginForm.password" type="password" show-password @keyup.enter="doLogin" placeholder="密码"></el-input></el-form-item>',
      '      <div style="margin:-6px 0 12px 80px;"><el-checkbox v-model="rememberPwd">记住密码</el-checkbox><span style="color:var(--yb-ink-2);font-size:12px;margin-left:8px;">默认不保存密码; 勾选后密码存于本机浏览器, 共享终端请勿勾选</span></div>',
      '      <el-button type="primary" style="width:100%" :loading="loading" @click="doLogin">登 录</el-button>',
      '      <div class="login-links"><span style="color:var(--yb-ink-2);">医院管理员：H42010000000 / admin / admin123</span></div>',
      '      <div class="login-links"><span style="color:var(--yb-ink-2);">平台超管(开通医院)：PLATFORM / superadmin / admin123</span></div>',
      '    </el-form>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ===== 递归菜单渲染组件(支持任意层级: 目录用 el-sub-menu 内嵌本组件, 叶子用 el-menu-item) =====
   * Element Plus 菜单靠 provide/inject 建立父子上下文, 跨本组件边界仍生效, 故嵌套安全;
   * 根节点为 <template v-for> 的 fragment(Vue3 多根), 输出直挂父 el-menu/el-sub-menu 的 <ul>。
   */
  var MenuNav = {
    name: 'MenuNav',
    props: { items: { type: Array, default: function () { return []; } } },
    template: [
      '<template v-for="m in items">',
      '  <el-menu-item v-if="!m.group" :key="m.key" :index="m.key">{{ m.label }}</el-menu-item>',
      '  <el-sub-menu v-else :key="m.group" :index="m.group">',
      '    <template #title><span>{{ m.group }}</span></template>',
      '    <menu-nav :items="m.children"></menu-nav>',
      '  </el-sub-menu>',
      '</template>'
    ].join('')
  };

  /* ===== 主布局 ===== */
  var AppLayout = {
    props: ['user'],
    emits: ['logout'],
    /* 顶栏通知铃铛(notification-bell.js 须先于本文件加载; 缺失时降级为不注册, 主布局不受影响);
     * emr-sse-bell 为病历实时通知铃铛(emr-sse-client.js 提供 HIS.EmrSseClient.NotificationBell, 同样须先加载) */
    components: {
      'notification-bell': (HIS.components || {}).NotificationBell,
      'emr-sse-bell': (HIS.EmrSseClient || {}).NotificationBell
    },
    data: function () {
      return {
        activeKey: 'dashboard', menu: MENU,
        /* 顶栏日期时钟: 每分钟刷新一次, 跨零点自动更新日期/星期 */
        now: new Date(),
        collapsed: localStorage.getItem('his-aside-collapsed') === '1',
        /* 固定开关: 默认不固定=菜单自动隐藏(2026-09); 固定后常驻展开不再自动隐藏 */
        pinned: localStorage.getItem('his-aside-pinned') === '1',
        hoverOpen: false,
        /* 三类临床工作站默认收起全局菜单；仅当前页面会话生效，不污染用户全局菜单偏好 */
        workstationMenuDefaultCollapsed: true,
        workstationMenuAutoCollapsed: false,
        workstationMenuTouched: false
      };
    },
    computed: {
      /* 三态: 固定且未手动收起=常规流内展开; 否则仅留 40px 入口条;
       * 悬停(且未处于展开态)时菜单以浮层面板弹出, 不挤压内容区 */
      asideCollapsed: function () { return this.collapsed || this.workstationMenuAutoCollapsed; },
      asideExpanded: function () { return this.pinned && !this.asideCollapsed; },
      asideFloating: function () { return this.hoverOpen && !this.asideExpanded; },
      currentItem: function () { return findItemIn(this.menu, this.activeKey); },
      currentComp: function () {
        var it = this.currentItem;
        if (it && it.comp && HIS.views[it.comp]) { return HIS.views[it.comp]; }
        return HIS.views.Placeholder;
      },
      currentProps: function () {
        var it = this.currentItem;
        if (it && !it.comp) { return { title: it.label, phase: it.phase || 'P1' }; }
        return {};
      },
      roleName: function () {
        var u = this.user || {};
        return u.roleName || HIS.roleLabel(u.role);
      },
      /* 顶栏头像圈取姓氏一字(中文姓名取首字; 无姓名则退到账号首字母) */
      avatarChar: function () {
        var u = this.user || {};
        var s = u.realName || u.username || '';
        return s.charAt(0).toUpperCase();
      },
      /* 顶栏日期+星期: 2026-09-27 周日 */
      dateText: function () {
        var d = this.now;
        var wk = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
        var m = d.getMonth() + 1, day = d.getDate();
        return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day) + ' ' + wk;
      },
      /* 可登录机构(多点执业): >1 时顶栏展示"切换机构" */
      allowedOrgs: function () { return (this.user || {}).allowedOrgs || []; }
    },
    watch: {
      activeKey: function (newKey, oldKey) {
        var entering = this.isClinicalWorkstationKey(newKey);
        var leaving = this.isClinicalWorkstationKey(oldKey);
        if (entering && !leaving) {
          this.workstationMenuTouched = false;
          this.applyWorkstationMenuDefault();
          return;
        }
        if (!entering) {
          this.workstationMenuAutoCollapsed = false;
          this.workstationMenuTouched = false;
        }
      }
    },
    methods: {
      /* 通过菜单组件识别工作站，兼容住院医嘱/病历等复用同一工作站组件的快捷入口 */
      isClinicalWorkstationKey: function (key) {
        var it = findItemIn(this.menu, key);
        var comp = it && it.comp;
        return comp === 'DoctorWorkstation' || comp === 'InpDoctorWorkstation' || comp === 'InpNurseStation';
      },
      applyWorkstationMenuDefault: function () {
        if (!this.isClinicalWorkstationKey(this.activeKey) || this.workstationMenuTouched) { return; }
        this.workstationMenuAutoCollapsed = this.workstationMenuDefaultCollapsed && !this.collapsed;
        if (this.workstationMenuAutoCollapsed) { this.hoverOpen = false; }
      },
      onSelect: function (key) {
        /* 医保验证台: 独立静态调试页外链, 新标签打开且不切换当前视图(验证台按当前登录机构的医保身份生效) */
        if (key === 'verify-console') {
          window.open('/verify/index.html', '_blank');
          /* el-menu 高亮由 default-active 的 watch 驱动, 同值不触发; 先置空再恢复以回退选中态 */
          var vm = this, prev = this.activeKey;
          vm.activeKey = '';
          vm.$nextTick(function () { vm.activeKey = prev; });
          return;
        }
        /* 移动护理终端(T48 P3): 独立移动端入口(原生CSS单页, 不挂主框架), 新标签打开且不切换当前视图 */
        if (key === 'mobile-nurse') {
          window.open('/mobile/index.html', '_blank');
          var mvm = this, mprev = this.activeKey;
          mvm.activeKey = '';
          mvm.$nextTick(function () { mvm.activeKey = mprev; });
          return;
        }
        this.activeKey = key;
        /* 自动隐藏模式下选完菜单即收回浮层, 避免面板持续遮挡内容区 */
        if (!this.asideExpanded) { this.hoverOpen = false; }
      },
      onCmd: function (c) { if (c === 'logout') { this.$emit('logout'); } },
      /* 切换活动机构: 重签令牌后整页重载(菜单/权限随新机构上下文重建) */
      onSwitchOrg: function (orgId) {
        if (!orgId || orgId === (this.user || {}).orgId) { return; }
        HIS.post('/api/auth/switch-org', { orgId: orgId })
          .then(function (d) {
            HIS.setToken(d.token);
            HIS.setUser(d);
            HIS.notifySuccess('已切换到 ' + (d.orgName || '目标机构'));
            setTimeout(function () { location.reload(); }, 300);
          })
          .catch(HIS.notifyError);
      },
      onAsideEnter: function () { this.hoverOpen = true; },
      onAsideLeave: function () { this.hoverOpen = false; },
      /* 固定/取消固定: 两种情况都回到"未手动收起"态, 固定后即常驻展开 */
      togglePin: function () {
        this.workstationMenuTouched = true;
        this.workstationMenuAutoCollapsed = false;
        this.pinned = !this.pinned;
        this.collapsed = false;
        localStorage.setItem('his-aside-pinned', this.pinned ? '1' : '0');
        localStorage.setItem('his-aside-collapsed', '0');
      },
      toggleAside: function () {
        this.workstationMenuTouched = true;
        if (this.workstationMenuAutoCollapsed) {
          this.workstationMenuAutoCollapsed = false;
          return;
        }
        this.collapsed = !this.collapsed;
        localStorage.setItem('his-aside-collapsed', this.collapsed ? '1' : '0');
      }
    },
    mounted: function () {
      var vm = this;
      /* 日期时钟定时器: 60s 一跳 */
      this.dateTimer = setInterval(function () { vm.now = new Date(); }, 60000);
      /* 全局视图跳转: 供列表页跳转到工作台等场景 */
      HIS.go = function (key) { vm.activeKey = key; };
      /* 临床工作站菜单策略：仅门诊医生、住院医生、住院护士工作站进入时默认最小化；
       * 参数只开放全局/租户级，接口不可用时按产品默认值 true 降级。 */
      HIS.params = HIS.params || {};
      HIS.get('/api/sys/param/resolve/system.clinical_workstation_menu_default_collapsed')
        .then(function (v) {
          var enabled = String(v).toLowerCase() !== 'false';
          HIS.params.clinicalWorkstationMenuDefaultCollapsed = enabled;
          vm.workstationMenuDefaultCollapsed = enabled;
          vm.applyWorkstationMenuDefault();
        })
        .catch(function () {
          HIS.params.clinicalWorkstationMenuDefaultCollapsed = true;
          vm.applyWorkstationMenuDefault();
        });
      /* 左菜单默认折叠参数(租户级可配, 机构级覆盖优先由后端四级解析器保证):
       * 参数值与上次应用记录不同→应用新默认; 未变→尊重用户本地手动选择。
       * 切换机构会整页重载, mounted 重跑, 新机构上下文自动命中机构级覆盖 */
      HIS.get('/api/sys/param/resolve/system.menu_default_collapsed')
        .then(function (v) {
          var want = v === 'true' ? '1' : '0';
          if (localStorage.getItem('his-aside-default') === want) { return; }
          localStorage.setItem('his-aside-default', want);
          localStorage.setItem('his-aside-collapsed', want);
          vm.collapsed = want === '1';
        })
        .catch(function () { /* 参数不可用时保持本地态, 不锁死菜单 */ });
      /* 列表显示策略参数(租户级可配): 挂 HIS.params 供各视图读取 */
      HIS.params = HIS.params || {};
      HIS.get('/api/sys/param/resolve/system.list_default_paged')
        .then(function (v) { HIS.params.listDefaultPaged = v || 'true'; })
        .catch(function () { });
      HIS.get('/api/sys/param/resolve/system.list_full_threshold')
        .then(function (v) { HIS.params.listFullThreshold = parseInt(v, 10) || 2000; })
        .catch(function () { });
      /* 动态菜单: 按角色从后端加载; 失败回退静态 MENU 防锁死 */
      HIS.get('/api/auth/menus')
        .then(function (nodes) {
          if (nodes && nodes.length) {
            vm.menu = normalizeMenu(nodes);
            if (!findItemIn(vm.menu, vm.activeKey)) { vm.activeKey = firstKey(vm.menu); }
          }
        })
        .catch(function () { /* 保留静态 MENU 兜底 */ });
    },
    beforeUnmount: function () {
      if (this.dateTimer) { clearInterval(this.dateTimer); }
    },
    template: [
      '<div class="layout">',
      '  <div class="layout-header">',
      '    <span class="logo">医保原生 HIS</span>',
      '    <span class="hosp">租户: {{ user.tenantName || "-" }}</span>',
      '    <el-dropdown v-if="allowedOrgs.length > 1" @command="onSwitchOrg" style="margin:0 4px;">',
      '      <span class="hosp" style="cursor:pointer;">机构: {{ user.orgName }} ▾</span>',
      '      <template #dropdown><el-dropdown-menu>',
      '        <el-dropdown-item v-for="o in allowedOrgs" :key="o.orgId" :command="o.orgId" :disabled="o.orgId === user.orgId">{{ o.orgName }}{{ o.home ? " (归属)" : "" }}</el-dropdown-item>',
      '      </el-dropdown-menu></template>',
      '    </el-dropdown>',
      '    <span class="hosp" v-else-if="user.orgName" style="opacity:.85;">机构: {{ user.orgName }}</span>',
      '    <span class="spacer"></span>',
      '    <span class="hosp hdr-date">{{ dateText }}</span>',
      /* 通知中心铃铛: 顶栏右侧、用户信息左侧; 其左为病历实时通知铃铛(SSE, emr-sse-client.js) */
      '    <emr-sse-bell></emr-sse-bell>',
      '    <notification-bell></notification-bell>',
      '    <el-dropdown @command="onCmd">',
      '      <span class="user" :data-avatar="avatarChar">{{ user.realName || user.username }}（{{ roleName }}）<span style="margin-left:4px;">▾</span></span>',
      '      <template #dropdown>',
      '        <el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu>',
      '      </template>',
      '    </el-dropdown>',
      '  </div>',
      '  <div class="layout-body">',
      '    <div class="layout-aside" :class="{\'is-collapsed\': !asideExpanded && !asideFloating, \'is-floating\': asideFloating}" @mouseenter="onAsideEnter" @mouseleave="onAsideLeave">',
      '      <div class="aside-bar">',
      /* 固定开关: 两态用形状区分而非仅靠颜色(用户反馈颜色不够明显)——
         未固定=倒向右倾的空心图钉(可滑走) + 钉板虚影; 已固定=正立实心图钉钉在横线上(钉住)。
         内联 SVG 手绘, 不走 CDN/图标包; 倒图钉用 SVG transform 属性旋转(避开 CSS transform-box 差异) */
      '        <el-button link size="small" class="aside-pin" :class="{\'is-on\':pinned}" :aria-label="pinned?\'取消固定菜单\':\'固定菜单\'" :title="pinned?\'当前: 已固定(菜单常驻) — 点击恢复自动隐藏\':\'当前: 未固定(菜单自动隐藏) — 点击固定常驻\'" @click="togglePin">',
      '          <svg v-if="pinned" class="pin-ico" viewBox="0 0 16 16" width="18" height="18" aria-hidden="true">',
      '            <path class="pin-head" d="M6.4 2.6h3.2l-.5 2.7 2.2 2.1H4.7l2.2-2.1z"></path>',
      '            <path class="pin-needle" d="M8 7.4v5.8"></path>',
      '            <path class="pin-board" d="M4 14.2h8"></path>',
      '          </svg>',
      '          <svg v-else class="pin-ico" viewBox="0 0 16 16" width="18" height="18" aria-hidden="true">',
      '            <g transform="rotate(24 8 13.2)">',
      '              <path class="pin-head" d="M6.4 2.6h3.2l-.5 2.7 2.2 2.1H4.7l2.2-2.1z"></path>',
      '              <path class="pin-needle" d="M8 7.4v5.8"></path>',
      '            </g>',
      '            <path class="pin-ghost" d="M3.6 14.2h8.8"></path>',
      '          </svg>',
      '        </el-button>',
      '        <el-button v-if="pinned" link size="small" :title="asideCollapsed?\'展开菜单\':\'收起菜单\'" @click="toggleAside">{{ asideCollapsed?"\u00bb":"\u00ab" }}</el-button>',
      '      </div>',
      /* 不用 v-show 隐藏: v-show 会触发 el-menu 内置 collapse 过渡, 展开后残留内联 width:0 裁掉全部菜单项;
         改由 CSS .layout-aside.is-collapsed .aside-menu-wrap{display:none} 控制显隐 */
      '      <div class="aside-menu-wrap">',
      '        <el-menu :default-active="activeKey" @select="onSelect">',
      '          <menu-nav :items="menu"></menu-nav>',
      '        </el-menu>',
      '      </div>',
      '    </div>',
      '    <div class="layout-main">',
      '      <component :is="currentComp" v-bind="currentProps" :key="activeKey"></component>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join('\n')
  };

  /* ===== 根组件 ===== */
  var Root = {
    data: function () {
      /* user 存为 data 快照: computed 直接读 localStorage 无响应式依赖,
       * 登出再登入同会话时会命中永久缓存(顶栏机构徽标显示上一个账号), 改在登录/登出时主动刷新 */
      return { logged: !!(HIS.getToken() && HIS.getUser()), user: HIS.getUser() || {} };
    },
    methods: {
      onLogged: function () { this.user = HIS.getUser() || {}; this.logged = true; },
      onLogout: function () { HIS.logout(); this.user = {}; this.logged = false; }
    },
    components: { 'login-page': LoginPage, 'app-layout': AppLayout },
    template: [
      '<login-page v-if="!logged" @logged="onLogged"></login-page>',
      '<app-layout v-else :user="user" @logout="onLogout"></app-layout>'
    ].join('\n')
  };

  /* 401 全局处理: 清除登录态并回到登录页 */
  HIS.onUnauthorized = function () {
    ElementPlus.ElMessage.error('登录已过期，请重新登录');
    setTimeout(function () { location.reload(); }, 800);
  };

  var app = createApp(Root);
  HIS.app = app;
  /* prod 构建的 Vue 不暴露 __vueParentComponent/_instance 回填，用全局 mixin 在根实例 created 时留句柄，供端到端验证/排障遍历组件树 */
  app.mixin({ created: function () { var i = this.$; while (i && i.parent) { i = i.parent; } if (i && !HIS.rootInstance) { HIS.rootInstance = i; } } });
  app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
  app.component('menu-nav', MenuNav);
  app.mount('#app');

  /* 全局快捷键系统(js/lib/his-interaction.js): 安装监听并注册默认快捷键
   * (init 内部幂等; _defaults 亦有幂等保护, 显式再调一次作双保险) */
  if (HIS.shortcuts && HIS.shortcuts.init) {
    HIS.shortcuts.init();
    if (HIS.shortcuts._defaults) { HIS.shortcuts._defaults(); }
  }
})();
