package com.yb.hi.controller.ris;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.ris.RisDeptConfigDTO;
import com.yb.hi.dto.ris.RisDeviceDTO;
import com.yb.hi.dto.ris.RisQcRuleDTO;
import com.yb.hi.dto.ris.RisReportTemplateDTO;
import com.yb.hi.entity.ris.HisExamDeptConfig;
import com.yb.hi.entity.ris.HisImagingDevice;
import com.yb.hi.entity.ris.HisRisCloudIndex;
import com.yb.hi.entity.ris.HisRisQcRule;
import com.yb.hi.entity.ris.HisRisReportElement;
import com.yb.hi.entity.ris.HisRisReportTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.ris.HisExamDeptConfigMapper;
import com.yb.hi.mapper.ris.HisImagingDeviceMapper;
import com.yb.hi.mapper.ris.HisRisCloudIndexMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.ris.RisCloudIndexService;
import com.yb.hi.service.ris.RisQcService;
import com.yb.hi.service.ris.RisReportTemplateService;
import com.yb.hi.service.ris.RisStatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 管理端接口: 影像设备台账 / 检查科室配置 / 报告模板 / 质控规则与记录 / 五类统计报表 / 医保影像云索引。
 * 口径:
 * 1) 机构隔离(读): scopeOrgId(orgId) + 空值回落 currentOrgId(与 RisRequestController.page 同口径),
 *    非牵头锁定本机构, 牵头未指定回落本机构、可带 orgId 查成员机构视角;
 * 2) 机构隔离(写): 设备/科室配置为机构自治数据(requireSelfOrgWrite, 仅本机构 ADMIN/ORG_ADMIN/超管),
 *    行级再校验归属(非牵头不得跨机构改删); 模板/质控规则由服务层按登录机构落 org_id;
 * 3) 统计报表: orgId 直传 RisStatisticsService(牵头空=医共体全部, 非牵头锁定本机构),
 *    日期缺省近 30 天; JdbcTemplate 原生 SQL 显式 tenant_id + deleted=0;
 * 4) 医保影像云: 列表联查申请单号/患者名, 重试仅对上传失败记录重置回待上传队列(上传管线接续上报)。
 */
@Slf4j
@RestController
@RequestMapping("/api/ris/admin")
public class RisAdminController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Autowired
    private HisImagingDeviceMapper deviceMapper;
    @Autowired
    private HisExamDeptConfigMapper deptConfigMapper;
    @Autowired
    private HisRisCloudIndexMapper cloudIndexMapper;
    @Autowired
    private RisReportTemplateService templateService;
    @Autowired
    private RisQcService qcService;
    @Autowired
    private RisStatisticsService statisticsService;
    @Autowired
    private RisCloudIndexService cloudIndexService;
    @Autowired
    private OrgAccessGuard guard;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /* ================= 设备管理 ================= */

    /** 设备分页查询: 关键字(编号/名称/AE Title/厂商/型号/机房号/序列号) + 设备类型 + 状态筛选。 */
    @GetMapping("/devices")
    public R<IPage<HisImagingDevice>> listDevices(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String deviceType,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) Long orgId) {
        Long scope = scopeOf(orgId);
        LambdaQueryWrapper<HisImagingDevice> qw = Wrappers.lambdaQuery();
        if (scope != null) {
            qw.eq(HisImagingDevice::getOrgId, scope);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(HisImagingDevice::getDeviceCode, kw)
                    .or().like(HisImagingDevice::getDeviceName, kw)
                    .or().like(HisImagingDevice::getAeTitle, kw)
                    .or().like(HisImagingDevice::getManufacturer, kw)
                    .or().like(HisImagingDevice::getModel, kw)
                    .or().like(HisImagingDevice::getRoomNo, kw)
                    .or().like(HisImagingDevice::getSerialNo, kw));
        }
        if (StringUtils.hasText(deviceType)) {
            qw.eq(HisImagingDevice::getDeviceType, deviceType.trim());
        }
        if (status != null) {
            qw.eq(HisImagingDevice::getStatus, status);
        }
        qw.orderByAsc(HisImagingDevice::getDeviceCode).orderByDesc(HisImagingDevice::getId);
        return R.ok(deviceMapper.selectPage(
                new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 200)), qw));
    }

    /** 新增设备: 名称/类型必填, 编号空则服务端生成, 同租户防重; 落当前登录机构。 */
    @PostMapping("/device")
    public R<HisImagingDevice> createDevice(@RequestBody RisDeviceDTO dto) {
        guard.requireSelfOrgWrite();
        if (dto == null || !StringUtils.hasText(dto.getDeviceName())) {
            throw new BizException(400, "设备名称不能为空");
        }
        if (!StringUtils.hasText(dto.getDeviceType())) {
            throw new BizException(400, "设备类型不能为空");
        }
        Long orgId = guard.currentOrgId();
        String code = StringUtils.hasText(dto.getDeviceCode())
                ? dto.getDeviceCode().trim()
                : "DEV" + LocalDateTime.now().format(TS) + (int) (Math.random() * 90 + 10);
        Long dup = deviceMapper.selectCount(Wrappers.<HisImagingDevice>lambdaQuery()
                .eq(HisImagingDevice::getDeviceCode, code));
        if (dup != null && dup > 0) {
            throw new BizException("设备编号已存在: " + code);
        }
        HisImagingDevice d = new HisImagingDevice();
        d.setOrgId(orgId);
        d.setDeviceCode(code);
        d.setDeviceName(dto.getDeviceName().trim());
        d.setDeviceType(dto.getDeviceType().trim());
        d.setModality(StringUtils.hasText(dto.getModality()) ? dto.getModality().trim().toUpperCase() : null);
        d.setDeptId(dto.getDeptId());
        d.setRoomNo(dto.getRoomNo());
        d.setAeTitle(dto.getAeTitle());
        d.setIpAddress(dto.getIpAddress());
        d.setPort(dto.getPort());
        d.setManufacturer(dto.getManufacturer());
        d.setModel(dto.getModel());
        d.setSerialNo(dto.getSerialNo());
        d.setInstallDate(dto.getInstallDate());
        d.setMaxDailySlots(dto.getMaxDailySlots());
        d.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        d.setDoseTracking(dto.getDoseTracking() == null ? 0 : dto.getDoseTracking());
        deviceMapper.insert(d);
        log.info("RIS设备新增: id={}, code={}, name={}, orgId={}", d.getId(), d.getDeviceCode(), d.getDeviceName(), orgId);
        return R.ok(d);
    }

    /** 更新设备(编号不允许变更; 非空字段覆盖, null 不动)。 */
    @PutMapping("/device/{id}")
    public R<HisImagingDevice> updateDevice(@PathVariable Long id, @RequestBody RisDeviceDTO dto) {
        guard.requireSelfOrgWrite();
        if (dto == null) {
            throw new BizException(400, "设备内容不能为空");
        }
        HisImagingDevice d = deviceMapper.selectById(id);
        if (d == null) {
            throw new BizException(400, "设备不存在");
        }
        requireRowOrg(d.getOrgId(), "无权维护其他机构的设备");
        if (StringUtils.hasText(dto.getDeviceName())) {
            d.setDeviceName(dto.getDeviceName().trim());
        }
        if (StringUtils.hasText(dto.getDeviceType())) {
            d.setDeviceType(dto.getDeviceType().trim());
        }
        if (StringUtils.hasText(dto.getModality())) {
            d.setModality(dto.getModality().trim().toUpperCase());
        }
        if (dto.getDeptId() != null) {
            d.setDeptId(dto.getDeptId());
        }
        if (dto.getRoomNo() != null) {
            d.setRoomNo(dto.getRoomNo());
        }
        if (dto.getAeTitle() != null) {
            d.setAeTitle(dto.getAeTitle());
        }
        if (dto.getIpAddress() != null) {
            d.setIpAddress(dto.getIpAddress());
        }
        if (dto.getPort() != null) {
            d.setPort(dto.getPort());
        }
        if (dto.getManufacturer() != null) {
            d.setManufacturer(dto.getManufacturer());
        }
        if (dto.getModel() != null) {
            d.setModel(dto.getModel());
        }
        if (dto.getSerialNo() != null) {
            d.setSerialNo(dto.getSerialNo());
        }
        if (dto.getInstallDate() != null) {
            d.setInstallDate(dto.getInstallDate());
        }
        if (dto.getMaxDailySlots() != null) {
            d.setMaxDailySlots(dto.getMaxDailySlots());
        }
        if (dto.getStatus() != null) {
            d.setStatus(dto.getStatus());
        }
        if (dto.getDoseTracking() != null) {
            d.setDoseTracking(dto.getDoseTracking());
        }
        deviceMapper.updateById(d);
        log.info("RIS设备更新: id={}, code={}", id, d.getDeviceCode());
        return R.ok(deviceMapper.selectById(id));
    }

    /** 设备详情。 */
    @GetMapping("/device/{id}")
    public R<HisImagingDevice> deviceDetail(@PathVariable Long id) {
        HisImagingDevice d = deviceMapper.selectById(id);
        if (d == null) {
            throw new BizException(400, "设备不存在");
        }
        requireRowOrg(d.getOrgId(), "无权查看其他机构的设备");
        return R.ok(d);
    }

    /** 删除设备(逻辑删除, 已有排程/执行记录保留审计)。 */
    @DeleteMapping("/device/{id}")
    public R<Void> deleteDevice(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        HisImagingDevice d = deviceMapper.selectById(id);
        if (d == null) {
            throw new BizException(400, "设备不存在");
        }
        requireRowOrg(d.getOrgId(), "无权删除其他机构的设备");
        deviceMapper.deleteById(id);
        log.info("RIS设备删除(逻辑): id={}, code={}", id, d.getDeviceCode());
        return R.ok(null);
    }

    /* ================= 科室配置 ================= */

    /** 科室配置列表(按 deptType 过滤: RADIOLOGY/ULTRASOUND/ENDOSCOPY)。 */
    @GetMapping("/dept-configs")
    public R<List<HisExamDeptConfig>> listDeptConfigs(@RequestParam(required = false) String deptType,
                                                      @RequestParam(required = false) Long orgId) {
        Long scope = scopeOf(orgId);
        LambdaQueryWrapper<HisExamDeptConfig> qw = Wrappers.lambdaQuery();
        if (scope != null) {
            qw.eq(HisExamDeptConfig::getOrgId, scope);
        }
        if (StringUtils.hasText(deptType)) {
            qw.eq(HisExamDeptConfig::getDeptType, deptType.trim());
        }
        qw.orderByAsc(HisExamDeptConfig::getDeptType).orderByAsc(HisExamDeptConfig::getDeptId);
        return R.ok(deptConfigMapper.selectList(qw));
    }

    /** 科室配置保存(幂等: id 或 同机构+同科室 命中即更新, 否则新增)。 */
    @PostMapping("/dept-config")
    public R<HisExamDeptConfig> saveDeptConfig(@RequestBody RisDeptConfigDTO dto) {
        guard.requireSelfOrgWrite();
        if (dto == null || dto.getDeptId() == null) {
            throw new BizException(400, "科室不能为空");
        }
        if (!StringUtils.hasText(dto.getDeptType())) {
            throw new BizException(400, "科室类型不能为空");
        }
        if (dto.getDoubleReadRate() != null && (dto.getDoubleReadRate() < 0 || dto.getDoubleReadRate() > 100)) {
            throw new BizException(400, "双阅比例须在 0-100 之间");
        }
        Long orgId = guard.currentOrgId();
        HisExamDeptConfig row;
        if (dto.getId() != null) {
            row = deptConfigMapper.selectById(dto.getId());
            if (row == null) {
                throw new BizException(400, "科室配置不存在");
            }
            requireRowOrg(row.getOrgId(), "无权维护其他机构的科室配置");
        } else {
            row = deptConfigMapper.selectOne(Wrappers.<HisExamDeptConfig>lambdaQuery()
                    .eq(HisExamDeptConfig::getOrgId, orgId)
                    .eq(HisExamDeptConfig::getDeptId, dto.getDeptId())
                    .last("LIMIT 1"));
            if (row == null) {
                row = new HisExamDeptConfig();
                row.setOrgId(orgId);
                row.setDeptId(dto.getDeptId());
            }
        }
        row.setDeptType(dto.getDeptType().trim());
        row.setAutoAssignDevice(dto.getAutoAssignDevice() == null ? 0 : dto.getAutoAssignDevice());
        row.setDefaultReportTemplateId(dto.getDefaultReportTemplateId());
        row.setWorklistEnabled(dto.getWorklistEnabled() == null ? 1 : dto.getWorklistEnabled());
        row.setDoubleReadRate(dto.getDoubleReadRate() == null ? 0 : dto.getDoubleReadRate());
        row.setUrgentColor(dto.getUrgentColor());
        if (row.getId() == null) {
            deptConfigMapper.insert(row);
        } else {
            deptConfigMapper.updateById(row);
        }
        log.info("RIS科室配置保存: id={}, deptId={}, deptType={}", row.getId(), row.getDeptId(), row.getDeptType());
        return R.ok(row);
    }

    /* ================= 报告模板 ================= */

    /** 模板列表: 按模态(CT/MR/DR/US/ES/ALL) + 科室类型过滤(机构模板 + org_id=0 全局种子并集)。 */
    @GetMapping("/templates")
    public R<List<HisRisReportTemplate>> listTemplates(@RequestParam(required = false) String modality,
                                                        @RequestParam(required = false) String deptType) {
        return R.ok(templateService.listByModality(modality, deptType));
    }

    /** 模板详情(含挂载数据元)。 */
    @GetMapping("/template/{id}")
    public R<Map<String, Object>> templateDetail(@PathVariable Long id) {
        return R.ok(templateService.getById(id));
    }

    /** 模板数据元列表(FINDINGS/CONCLUSION/TECHNIQUE 按段排序)。 */
    @SuppressWarnings("unchecked")
    @GetMapping("/template/{id}/elements")
    public R<List<HisRisReportElement>> templateElements(@PathVariable Long id) {
        Map<String, Object> detail = templateService.getById(id);
        return R.ok((List<HisRisReportElement>) detail.get("elements"));
    }

    /** 新增模板(名称/模态/科室类型必填, 编码空则服务端生成, 数据元随模板整体保存)。 */
    @PostMapping("/template")
    public R<HisRisReportTemplate> createTemplate(@RequestBody RisReportTemplateDTO dto) {
        return R.ok(templateService.create(dto));
    }

    /** 更新模板(编码不允许变更; 数据元列表非空时整体替换, null 不动数据元)。 */
    @PutMapping("/template/{id}")
    public R<HisRisReportTemplate> updateTemplate(@PathVariable Long id, @RequestBody RisReportTemplateDTO dto) {
        return R.ok(templateService.update(id, dto));
    }

    /** 删除模板(逻辑删除, 挂数据元一并软删)。 */
    @DeleteMapping("/template/{id}")
    public R<Void> deleteTemplate(@PathVariable Long id) {
        templateService.delete(id);
        return R.ok(null);
    }

    /* ================= 质控管理 ================= */

    /** 质控规则列表(全部含停用; 登录机构 + org_id=0 全局种子并集)。 */
    @GetMapping("/qc-rules")
    public R<List<HisRisQcRule>> listQcRules() {
        return R.ok(qcService.listRules());
    }

    /** 新增质控规则(名称/类型/检查时点/表达式必填, 编码空则服务端生成)。 */
    @PostMapping("/qc-rule")
    public R<HisRisQcRule> createQcRule(@RequestBody RisQcRuleDTO dto) {
        return R.ok(qcService.createRule(dto));
    }

    /** 更新质控规则(编码不允许变更; 非空字段覆盖)。 */
    @PutMapping("/qc-rule/{id}")
    public R<HisRisQcRule> updateQcRule(@PathVariable Long id, @RequestBody RisQcRuleDTO dto) {
        return R.ok(qcService.updateRule(id, dto));
    }

    /** 规则启停(enabled=1启用/0停用, 亦可 PUT /qc-rule/{id} 传 enabled 字段达成)。 */
    @PutMapping("/qc-rule/{id}/toggle")
    public R<HisRisQcRule> toggleQcRule(@PathVariable Long id,
                                         @RequestParam(required = false) Integer enabled,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Integer target = enabled;
        if (target == null && body != null && body.get("enabled") != null) {
            target = Integer.valueOf(body.get("enabled").toString());
        }
        if (target == null || (target != 0 && target != 1)) {
            throw new BizException(400, "目标启停状态必须为 0 或 1");
        }
        RisQcRuleDTO dto = new RisQcRuleDTO();
        dto.setEnabled(target);
        return R.ok(qcService.updateRule(id, dto));
    }

    /**
     * 质控记录分页查询(只读留痕): 报告关键字(申请单号/患者姓名/报告ID) + 规则 + 日期区间 + 通过与否筛选,
     * 行联查规则编码名称/严重度/扣分与申请单号/患者姓名。
     */
    @GetMapping("/qc-records")
    public R<Map<String, Object>> qcRecords(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Long ruleId,
                                            @RequestParam(required = false) Integer passed,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) Long orgId) {
        Long scope = scopeOf(orgId);
        StringBuilder where = new StringBuilder(" WHERE qc.tenant_id = ? AND qc.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (scope != null) {
            where.append(" AND qc.org_id = ?");
            args.add(scope);
        }
        if (ruleId != null) {
            where.append(" AND qc.rule_id = ?");
            args.add(ruleId);
        }
        if (passed != null) {
            where.append(" AND qc.passed = ?");
            args.add(passed);
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND qc.check_time >= ?");
            args.add(startDate.trim() + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND qc.check_time <= ?");
            args.add(endDate.trim() + " 23:59:59");
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            String like = "%" + kw + "%";
            where.append(" AND (rq.request_no LIKE ? OR p.name LIKE ? OR CAST(qc.report_id AS CHAR) = ?)");
            args.add(like);
            args.add(like);
            args.add(kw);
        }
        String from = " FROM his_ris_qc_record qc"
                + " LEFT JOIN his_ris_qc_rule r ON r.id = qc.rule_id AND r.deleted = 0"
                + " LEFT JOIN his_exam_report rp ON rp.id = qc.report_id AND rp.deleted = 0"
                + " LEFT JOIN his_exam_request rq ON rq.id = rp.request_id AND rq.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = rq.patient_id AND p.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int safePage = Math.max(page, 1);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT qc.id, qc.report_id AS reportId, qc.rule_id AS ruleId, qc.org_id AS orgId,"
                        + " DATE_FORMAT(qc.check_time, '%Y-%m-%d %H:%i:%s') AS checkTime,"
                        + " qc.passed, qc.detail,"
                        + " r.rule_code AS ruleCode, r.rule_name AS ruleName, r.rule_type AS ruleType, r.severity,"
                        + " IFNULL(r.score_deduction, 0) AS scoreDeduction,"
                        + " rq.request_no AS requestNo, p.name AS patientName"
                        + from + where + " ORDER BY qc.check_time DESC, qc.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total == null ? 0 : total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return R.ok(out);
    }

    /* ================= 统计分析 ================= */

    /** 工作量统计: {byDoctor, byDept, byDevice}, groupBy 透传回显; 日期缺省近 30 天。 */
    @GetMapping("/stats/workload")
    public R<Map<String, Object>> workloadStats(@RequestParam(required = false) Long deptId,
                                                @RequestParam(required = false) Long doctorId,
                                                @RequestParam(required = false) String startDate,
                                                @RequestParam(required = false) String endDate,
                                                @RequestParam(required = false) String groupBy,
                                                @RequestParam(required = false) Long orgId) {
        Map<String, Object> result = statisticsService.workloadStats(orgId, deptId, doctorId,
                defaultStart(startDate), defaultEnd(endDate));
        if (StringUtils.hasText(groupBy)) {
            result.put("groupBy", groupBy.trim());
        }
        return R.ok(result);
    }

    /** 阳性率统计: {byExamType, byBodyPart}, deptType 过滤(RADIOLOGY/ULTRASOUND/ENDOSCOPY)。 */
    @GetMapping("/stats/positive-rate")
    public R<Map<String, Object>> positiveRateStats(@RequestParam(required = false) String deptType,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate,
                                                    @RequestParam(required = false) Long orgId) {
        String dept = StringUtils.hasText(deptType) ? deptType.trim() : null;
        return R.ok(statisticsService.positiveRateStats(orgId, dept,
                defaultStart(startDate), defaultEnd(endDate)));
    }

    /** 报告时效统计: 普通/急诊/危急值三类 检查完成->报告审核 平均时长与 2 小时达标率。 */
    @GetMapping("/stats/report-time")
    public R<List<Map<String, Object>>> reportTimeStats(@RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate,
                                                        @RequestParam(required = false) Long orgId) {
        return R.ok(statisticsService.reportTimeStats(orgId, defaultStart(startDate), defaultEnd(endDate)));
    }

    /** 设备利用率: 按设备统计检查量/占用时长/开机率(÷ 天数×8 小时标准工时)。 */
    @GetMapping("/stats/device-utilization")
    public R<List<Map<String, Object>>> deviceUtilizationStats(@RequestParam(required = false) String startDate,
                                                              @RequestParam(required = false) String endDate,
                                                              @RequestParam(required = false) Long orgId) {
        return R.ok(statisticsService.deviceUtilizationStats(orgId, defaultStart(startDate), defaultEnd(endDate)));
    }

    /** 收入统计: {byDept, byExamType}, 检查费用合计与申请单量(已取消单不计)。 */
    @GetMapping("/stats/revenue")
    public R<Map<String, Object>> revenueStats(@RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate,
                                               @RequestParam(required = false) Long orgId) {
        return R.ok(statisticsService.revenueStats(orgId, defaultStart(startDate), defaultEnd(endDate)));
    }

    /* ================= 医保影像云 ================= */

    /**
     * 影像云索引分页查询: 上传状态/关键字(申请单号/患者姓名/StudyUID/医保检查编码/云端索引ID/报告ID)/上传时间区间筛选,
     * 附机构范围汇总卡(待上传/今日已上传/上传失败, 不受列表筛选影响)。
     */
    @GetMapping("/cloud/list")
    public R<Map<String, Object>> cloudList(@RequestParam(required = false) Integer uploadStatus,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String startDate,
                                           @RequestParam(required = false) String endDate,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) Long orgId) {
        Long scope = scopeOf(orgId);
        StringBuilder where = new StringBuilder(" WHERE ci.tenant_id = ? AND ci.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (scope != null) {
            where.append(" AND ci.org_id = ?");
            args.add(scope);
        }
        if (uploadStatus != null) {
            where.append(" AND ci.upload_status = ?");
            args.add(uploadStatus);
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND ci.upload_time >= ?");
            args.add(startDate.trim() + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND ci.upload_time <= ?");
            args.add(endDate.trim() + " 23:59:59");
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            String like = "%" + kw + "%";
            where.append(" AND (rq.request_no LIKE ? OR p.name LIKE ? OR ci.study_uid LIKE ?")
                    .append(" OR ci.yb_exam_code LIKE ? OR ci.cloud_index_id LIKE ? OR CAST(ci.report_id AS CHAR) = ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(kw);
        }
        String from = " FROM his_ris_cloud_index ci"
                + " LEFT JOIN his_exam_request rq ON rq.id = ci.request_id AND rq.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = rq.patient_id AND p.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int safePage = Math.max(page, 1);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT ci.id, ci.report_id AS reportId, ci.request_id AS requestId, ci.org_id AS orgId,"
                        + " ci.study_uid AS studyUid, ci.yb_exam_code AS ybExamCode, ci.upload_status AS uploadStatus,"
                        + " DATE_FORMAT(ci.upload_time, '%Y-%m-%d %H:%i:%s') AS uploadTime,"
                        + " ci.cloud_index_id AS cloudIndexId, ci.upload_response AS uploadResponse,"
                        + " rq.request_no AS requestNo, p.name AS patientName"
                        + from + where + " ORDER BY ci.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        /* 汇总卡: 机构范围口径三计数 */
        StringBuilder sumWhere = new StringBuilder(" WHERE ci.tenant_id = ? AND ci.deleted = 0");
        List<Object> sumArgs = new ArrayList<>();
        sumArgs.add(tenantId());
        if (scope != null) {
            sumWhere.append(" AND ci.org_id = ?");
            sumArgs.add(scope);
        }
        Map<String, Object> summary = jdbcTemplate.queryForMap(
                "SELECT IFNULL(SUM(CASE WHEN ci.upload_status = 0 THEN 1 ELSE 0 END), 0) AS pendingCount,"
                        + " IFNULL(SUM(CASE WHEN ci.upload_status = 1 AND DATE(ci.upload_time) = CURDATE() THEN 1 ELSE 0 END), 0) AS uploadedToday,"
                        + " IFNULL(SUM(CASE WHEN ci.upload_status = 2 THEN 1 ELSE 0 END), 0) AS failedCount"
                        + " FROM his_ris_cloud_index ci" + sumWhere,
                sumArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total == null ? 0 : total);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("summary", summary);
        return R.ok(out);
    }

    /** 重试上传失败索引: 仅失败态可重试, 重置回待上传队列(回执/时间清空, 上传管线接续上报)。 */
    @PostMapping("/cloud/retry/{id}")
    public R<HisRisCloudIndex> retryCloudUpload(@PathVariable Long id) {
        HisRisCloudIndex index = cloudIndexMapper.selectById(id);
        if (index == null) {
            throw new BizException(400, "影像云索引不存在");
        }
        requireRowOrg(index.getOrgId(), "无权重试其他机构的影像云索引");
        if (index.getUploadStatus() == null || index.getUploadStatus() != RisCloudIndexService.ST_FAILED) {
            throw new BizException(400, "仅上传失败的索引可重试(当前状态: " + index.getUploadStatus() + ")");
        }
        cloudIndexMapper.update(null, Wrappers.<HisRisCloudIndex>lambdaUpdate()
                .eq(HisRisCloudIndex::getId, id)
                .set(HisRisCloudIndex::getUploadStatus, RisCloudIndexService.ST_PENDING)
                .set(HisRisCloudIndex::getCloudIndexId, null)
                .set(HisRisCloudIndex::getUploadTime, null)
                .set(HisRisCloudIndex::getUploadResponse, null));
        log.info("影像云索引重试(重置为待上传): id={}, reportId={}, studyUid={}",
                id, index.getReportId(), index.getStudyUid());
        return R.ok(cloudIndexMapper.selectById(id));
    }

    /* ================= 辅助 ================= */

    /** 读范围: 牵头未指定 orgId 回落本机构(与 RisRequestController.page 同口径), 非牵头锁定本机构。 */
    private Long scopeOf(Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        return scope == null ? guard.currentOrgId() : scope;
    }

    /** 行级机构守卫: 非牵头且行归属机构与当前登录机构不一致时拒绝(牵头可跨机构维护)。 */
    private void requireRowOrg(Long rowOrgId, String msg) {
        if (rowOrgId != null && !rowOrgId.equals(guard.currentOrgId()) && !guard.isLead()) {
            throw new BizException(403, msg);
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 统计起始日期缺省: 近 30 天首日。 */
    private static String defaultStart(String startDate) {
        return StringUtils.hasText(startDate) ? startDate.trim() : LocalDate.now().minusDays(29).toString();
    }

    /** 统计截止日期缺省: 今日。 */
    private static String defaultEnd(String endDate) {
        return StringUtils.hasText(endDate) ? endDate.trim() : LocalDate.now().toString();
    }
}
