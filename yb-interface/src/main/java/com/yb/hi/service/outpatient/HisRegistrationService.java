package com.yb.hi.service.outpatient;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.OutpatientRegisterCancelReq;
import com.yb.hi.dto.OutpatientRegisterReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisSchedule;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.outpatient.HisRegistration;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.outpatient.HisRegistrationMapper;
import com.yb.hi.service.OutpatientService;
import com.yb.hi.service.basedata.HisDeptService;
import com.yb.hi.service.basedata.HisScheduleService;
import com.yb.hi.service.basedata.HisStaffService;
import com.yb.hi.service.doctor.HisVisitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 挂号服务: 编排院内挂号记录 + 医保2201挂号 / 2202撤销。
 * 增强: 重复挂号拦截 / 同日同科室提示(不阻断) / 减免与实收金额 / 支付方式 /
 * 候诊序号(科室简码+流水号, 同步 his_visit) / 今日概览与多维统计。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class HisRegistrationService extends ServiceImpl<HisRegistrationMapper, HisRegistration> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 无减免(与前端约定, 统计时排除) */
    private static final String DISCOUNT_NONE = "none";
    /** 70岁及以上老人自动免挂号费 */
    private static final String DISCOUNT_AGE70 = "age70free";

    private final HisPatientService patientService;
    private final HisScheduleService scheduleService;
    private final HisStaffService staffService;
    private final HisDeptService deptService;
    private final OutpatientService outpatientService;
    private final HisVisitService visitService;
    private final JdbcTemplate jdbcTemplate;

    public HisRegistrationService(HisPatientService patientService, HisScheduleService scheduleService,
                                  HisStaffService staffService, HisDeptService deptService,
                                  OutpatientService outpatientService, HisVisitService visitService,
                                  JdbcTemplate jdbcTemplate) {
        this.patientService = patientService;
        this.scheduleService = scheduleService;
        this.staffService = staffService;
        this.deptService = deptService;
        this.outpatientService = outpatientService;
        this.visitService = visitService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 分页查询挂号记录(按日期区间/状态/患者关键字) */
    public IPage<HisRegistration> pageQuery(long page, long size, LocalDate from, LocalDate to,
                                            Integer status, String keyword) {
        LambdaQueryChainWrapper<HisRegistration> q = lambdaQuery()
                .ge(from != null, HisRegistration::getWorkDate, from)
                .le(to != null, HisRegistration::getWorkDate, to)
                .eq(status != null, HisRegistration::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisRegistration::getPatientName, keyword)
                    .or().like(HisRegistration::getRegNo, keyword)
                    .or().like(HisRegistration::getPatientNo, keyword)
                    .or().like(HisRegistration::getIptOtpNo, keyword)
                    /* 患者拼音简码检索: 挂号表仅冗余患者姓名, 子查询建档表 py_code 命中 */
                    .or().apply("EXISTS (SELECT 1 FROM his_patient hp WHERE hp.id = patient_id"
                            + " AND hp.py_code LIKE CONCAT('%', {0}, '%'))", keyword));
        }
        return q.orderByDesc(HisRegistration::getId).page(new Page<>(page, size));
    }

    /** 挂号(兼容旧签名): 不传减免/支付方式等增强参数 */
    @Transactional(rollbackFor = Exception.class)
    public HisRegistration register(Long patientId, Long scheduleId, String medType) {
        return register(patientId, scheduleId, medType, null, null, null, null, null, null);
    }

    /**
     * 挂号: 校验患者/排班/重复 -> 组装2201 -> 调用医保 -> 回填mdtrt_id -> 落库 -> 扣减号源 -> 建候诊。
     * 增强: 同号源重复挂号拦截; 同日同科室提示(不阻断, 瞬态字段返回);
     * 减免自动判定(年龄>=70 自动 age70free 全免, 其他类型由前端传入); 实收金额/支付方式;
     * 候诊序号(科室简码+4位流水号)生成并落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRegistration register(Long patientId, Long scheduleId, String medType,
                                    String discountType, String discountReason, BigDecimal discountAmount,
                                    String payMethod, String payDetail, String feeType) {
        HisPatient patient = patientService.getById(patientId);
        if (patient == null) {
            throw new BizException(400, "患者不存在");
        }
        HisSchedule schedule = scheduleService.getById(scheduleId);
        if (schedule == null) {
            throw new BizException(400, "排班号源不存在");
        }
        if (schedule.getStatus() == null || schedule.getStatus() != 1) {
            throw new BizException("该排班已停诊, 不能挂号");
        }
        if (schedule.getLeftNum() == null || schedule.getLeftNum() <= 0) {
            throw new BizException("该时段号源已用完");
        }

        // 重复挂号校验: 同患者+同号源已有有效挂号(status=1)则拦截
        long dupCnt = lambdaQuery()
                .eq(HisRegistration::getPatientId, patientId)
                .eq(HisRegistration::getScheduleId, scheduleId)
                .eq(HisRegistration::getStatus, 1)
                .count();
        if (dupCnt > 0) {
            throw new BizException("该患者已挂此号源，请勿重复挂号");
        }

        HisStaff staff = staffService.getById(schedule.getStaffId());
        HisDept dept = deptService.getById(schedule.getDeptId());

        // 医保报送守卫(2201): 科室/医师必须已维护医保编码, 不允许回退院内编码, 未配则拒挂
        if (dept == null) {
            throw new BizException("排班科室不存在或已删除, 不能挂号");
        }
        if (!StringUtils.hasText(dept.getYbDeptCode())) {
            throw new BizException("科室【" + dept.getDeptName() + "】未维护医保科室编码, 请先在[科室管理-编辑]从医保标准字典选择后再挂号");
        }
        if (staff == null) {
            throw new BizException("排班医师不存在或已删除, 不能挂号");
        }
        if (!StringUtils.hasText(staff.getAtddrNo())) {
            throw new BizException("医师【" + staff.getStaffName() + "】未维护医保医师编码, 请先在[职工管理]维护后再挂号");
        }

        // 同日同科室挂号检测(不阻断, 通过瞬态字段提示): 同患者+同出诊日+同科室已有有效挂号
        boolean sameDeptWarning = false;
        String sameDeptInfo = null;
        if (schedule.getWorkDate() != null && schedule.getDeptId() != null) {
            long sameDeptCnt = lambdaQuery()
                    .eq(HisRegistration::getPatientId, patientId)
                    .eq(HisRegistration::getWorkDate, schedule.getWorkDate())
                    .eq(HisRegistration::getDeptId, schedule.getDeptId())
                    .eq(HisRegistration::getStatus, 1)
                    .count();
            if (sameDeptCnt > 0) {
                sameDeptWarning = true;
                sameDeptInfo = "该患者当日已在" + (dept == null ? "本科室" : dept.getDeptName()) + "挂过号";
            }
        }

        String iptOtpNo = genNo("OT");
        String regNo = genNo("R");

        // 减免判定与金额计算(calcDiscount): 前端优先 -> 年龄>=70自动age70free -> 默认none
        BigDecimal regFee = schedule.getRegFee() == null ? BigDecimal.ZERO : schedule.getRegFee();
        Object[] discount = calcDiscount(patient, regFee, discountType, discountAmount);
        String effDiscountType = (String) discount[0];
        BigDecimal effDiscountAmount = (BigDecimal) discount[1];
        BigDecimal actualFee = regFee.subtract(effDiscountAmount);
        if (actualFee.signum() < 0) {
            actualFee = BigDecimal.ZERO;
        }

        // 候诊序号: 科室简码+4位流水号(当日同科室同时段 MAX+1, 如 NK-0015)
        String queueNo = generateQueueNo(schedule.getDeptId(), schedule.getTimeType(), schedule.getWorkDate());

        // 组装医保2201请求
        OutpatientRegisterReq req = new OutpatientRegisterReq();
        req.setPsnNo(patient.getPsnNo());
        req.setInsutype(patient.getInsutype());
        req.setBegntime(DateUtil.currentDateTime());
        req.setMdtrtCertType(StringUtils.hasText(patient.getMdtrtCertType()) ? patient.getMdtrtCertType() : "02");
        req.setMdtrtCertNo(StringUtils.hasText(patient.getMdtrtCertNo()) ? patient.getMdtrtCertNo() : patient.getIdCard());
        req.setIptOtpNo(iptOtpNo);
        req.setAtddrNo(staff.getAtddrNo());
        req.setDrName(staff.getStaffName());
        // 医保科室编码(守卫已保证非空): 不再回退院内 deptCode
        req.setDeptCode(dept.getYbDeptCode());
        req.setDeptName(dept.getDeptName());
        req.setCaty(dept.getDeptCaty());
        req.setMedType(StringUtils.hasText(medType) ? medType : "11");

        // 调用医保2201
        YbResponse resp = outpatientService.register(req);
        if (resp == null || !resp.isSuccess()) {
            String err = resp == null ? "医保无响应" : resp.getErrMsg();
            throw new BizException("医保挂号失败: " + err);
        }
        String mdtrtId = null;
        JSONObject dataNode = resp.getOutputNode("data");
        if (dataNode != null) {
            mdtrtId = dataNode.getString("mdtrt_id");
        }

        // 落库挂号记录
        HisRegistration reg = new HisRegistration();
        reg.setRegNo(regNo);
        reg.setPatientId(patient.getId());
        reg.setPatientNo(patient.getPatientNo());
        reg.setPatientName(patient.getName());
        reg.setPsnNo(patient.getPsnNo());
        reg.setInsutype(patient.getInsutype());
        reg.setMdtrtCertType(req.getMdtrtCertType());
        reg.setMdtrtCertNo(req.getMdtrtCertNo());
        reg.setDeptId(schedule.getDeptId());
        reg.setDeptCode(req.getDeptCode());
        reg.setDeptName(req.getDeptName());
        reg.setCaty(req.getCaty());
        reg.setStaffId(schedule.getStaffId());
        reg.setAtddrNo(req.getAtddrNo());
        reg.setDrName(req.getDrName());
        reg.setScheduleId(schedule.getId());
        reg.setWorkDate(schedule.getWorkDate());
        reg.setTimeType(schedule.getTimeType());
        reg.setRegLevelCode(schedule.getRegLevelCode());
        reg.setRegLevelName(schedule.getRegLevelName());
        reg.setRegFee(schedule.getRegFee());
        reg.setMedType(req.getMedType());
        reg.setIptOtpNo(iptOtpNo);
        reg.setMdtrtId(mdtrtId);
        reg.setRegTime(LocalDateTime.now());
        reg.setStatus(1);
        reg.setOperator(UserContext.username());
        reg.setFeeType(StringUtils.hasText(feeType) ? feeType.trim() : null);
        reg.setDiscountType(effDiscountType);
        reg.setDiscountReason(StringUtils.hasText(discountReason) ? discountReason.trim() : null);
        reg.setDiscountAmount(effDiscountAmount);
        reg.setActualFee(actualFee);
        reg.setPayMethod(StringUtils.hasText(payMethod) ? payMethod.trim() : null);
        reg.setPayDetail(StringUtils.hasText(payDetail) ? payDetail.trim() : null);
        reg.setQueueNo(queueNo);
        save(reg);

        // 扣减号源(原子UPDATE, 防并发超扣; affected=0 说明并发下号源已被抢完)
        int affected = jdbcTemplate.update(
                "UPDATE his_schedule SET left_num = left_num - 1, update_time = NOW() WHERE id = ? AND left_num > 0 AND deleted = 0",
                scheduleId);
        if (affected == 0) {
            throw new BizException("号源已满或排班不存在");
        }

        // 创建候诊就诊记录(医生站接诊来源; queue_no 同步 his_visit)
        visitService.createFromRegistration(reg, patient);

        // 同日同科室提示以瞬态字段返回(不落库)
        reg.setSameDeptWarning(sameDeptWarning);
        reg.setSameDeptInfo(sameDeptInfo);

        log.info("挂号成功: regNo={}, mdtrtId={}, patient={}, queueNo={}, discount={}, actualFee={}",
                regNo, mdtrtId, patient.getName(), queueNo, effDiscountType, actualFee);
        return reg;
    }

    /**
     * 退号: 校验状态 -> 调用医保2202 -> 更新状态 -> 取消候诊 -> 回滚号源。
     * 返回 {id, regNo, needRefund, payMethod, refundAmount}: pay_method 非空且非 free 时 needRefund=true。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(Long registrationId, String reason) {
        HisRegistration reg = getById(registrationId);
        if (reg == null) {
            throw new BizException(400, "挂号记录不存在");
        }
        if (reg.getStatus() == null || reg.getStatus() != 1) {
            throw new BizException("该挂号已退号或已就诊, 不能退号");
        }

        OutpatientRegisterCancelReq req = new OutpatientRegisterCancelReq();
        req.setPsnNo(reg.getPsnNo());
        req.setMdtrtId(reg.getMdtrtId());
        req.setIptOtpNo(reg.getIptOtpNo());
        YbResponse resp = outpatientService.cancelRegister(req);
        if (resp == null || !resp.isSuccess()) {
            String err = resp == null ? "医保无响应" : resp.getErrMsg();
            throw new BizException("医保退号失败: " + err);
        }

        reg.setStatus(2);
        reg.setCancelTime(LocalDateTime.now());
        reg.setCancelReason(reason);
        updateById(reg);

        // 取消候诊就诊记录
        visitService.cancelByRegistration(reg.getId());

        // 回滚号源(原子UPDATE, 防并发重复回滚超过总号源)
        if (reg.getScheduleId() != null) {
            jdbcTemplate.update(
                    "UPDATE his_schedule SET left_num = left_num + 1, update_time = NOW() WHERE id = ? AND left_num < total_num AND deleted = 0",
                    reg.getScheduleId());
        }

        boolean needRefund = StringUtils.hasText(reg.getPayMethod()) && !"free".equals(reg.getPayMethod().trim());
        log.info("退号成功: regNo={}, mdtrtId={}, needRefund={}", reg.getRegNo(), reg.getMdtrtId(), needRefund);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", reg.getId());
        result.put("regNo", reg.getRegNo());
        result.put("needRefund", needRefund);
        result.put("payMethod", reg.getPayMethod());
        result.put("refundAmount", reg.getActualFee());
        return result;
    }

    /** 重新挂号: 仅已退号(status=2)记录可重挂, 复用原患者/号源/减免/支付信息重走 register 链路 */
    @Transactional(rollbackFor = Exception.class)
    public HisRegistration reRegister(Long registrationId) {
        HisRegistration old = getById(registrationId);
        if (old == null) {
            throw new BizException(400, "原挂号记录不存在");
        }
        if (old.getStatus() == null || old.getStatus() != 2) {
            throw new BizException("仅已退号记录支持重新挂号");
        }
        if (old.getPatientId() == null || old.getScheduleId() == null) {
            throw new BizException(400, "原挂号记录缺少患者或号源信息, 不能重新挂号");
        }
        return register(old.getPatientId(), old.getScheduleId(), old.getMedType(),
                old.getDiscountType(), old.getDiscountReason(), old.getDiscountAmount(),
                old.getPayMethod(), old.getPayDetail(), old.getFeeType());
    }

    /**
     * 换号: 将有效挂号(status=1)迁移到目标号源 —— 同一事务内先退原号(2202+取消候诊+回滚号源),
     * 再按目标号源重新挂号(2201), 复用原患者/费别/减免/支付信息。
     * 返回 {oldId, oldRegNo, needRefund, refundAmount, newReg}: needRefund=true 表示原号已收非免收费用需退费。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeSlot(Long registrationId, Long newScheduleId, String reason) {
        HisRegistration old = getById(registrationId);
        if (old == null) {
            throw new BizException(400, "原挂号记录不存在");
        }
        if (old.getStatus() == null || old.getStatus() != 1) {
            throw new BizException("仅已挂号(未退号未就诊)记录可换号");
        }
        if (old.getPatientId() == null) {
            throw new BizException(400, "原挂号记录缺少患者信息, 不能换号");
        }
        if (newScheduleId == null || newScheduleId.equals(old.getScheduleId())) {
            throw new BizException("目标号源与原号源相同, 无需换号");
        }
        HisSchedule target = scheduleService.getById(newScheduleId);
        if (target == null) {
            throw new BizException(400, "目标号源不存在");
        }
        if (target.getStatus() == null || target.getStatus() != 1) {
            throw new BizException("目标排班已停诊, 不能换号");
        }
        if (target.getLeftNum() == null || target.getLeftNum() <= 0) {
            throw new BizException("目标时段号源已用完");
        }
        // 退原号(退号链路自带状态/医保校验, 换号原因并入退号原因留痕)
        String cancelReason = "换号" + (StringUtils.hasText(reason) ? ": " + reason.trim() : "");
        Map<String, Object> cancelResult = cancel(registrationId, cancelReason);
        // 挂目标号源: 复用原挂号业务参数, 号源/费用按新排班重新计算
        HisRegistration newReg = register(old.getPatientId(), newScheduleId, old.getMedType(),
                old.getDiscountType(), old.getDiscountReason(), old.getDiscountAmount(),
                old.getPayMethod(), old.getPayDetail(), old.getFeeType());
        newReg.setChangeFromRegNo(old.getRegNo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oldId", old.getId());
        result.put("oldRegNo", old.getRegNo());
        result.put("needRefund", cancelResult.get("needRefund"));
        result.put("refundAmount", cancelResult.get("refundAmount"));
        result.put("newReg", newReg);
        log.info("换号成功: 原单={}, 新单={}, patient={}", old.getRegNo(), newReg.getRegNo(), old.getPatientName());
        return result;
    }

    /** 今日挂号概览: 单条 CASE WHEN 聚合(挂号/退号/候诊/就诊/减免) */
    public Map<String, Object> todaySummary() {
        return jdbcTemplate.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(CASE WHEN status IN (1,3) THEN 1 ELSE 0 END), 0) AS registered,"
                        + " IFNULL(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS cancelled,"
                        + " IFNULL(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS waiting,"
                        + " IFNULL(SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END), 0) AS visited,"
                        + " IFNULL(SUM(CASE WHEN discount_type IS NOT NULL AND discount_type != 'none' THEN 1 ELSE 0 END), 0) AS discountCount,"
                        + " IFNULL(SUM(discount_amount), 0) AS discountTotal,"
                        + " IFNULL(SUM(CASE WHEN status IN (1,3) THEN actual_fee ELSE 0 END), 0) AS actualTotal"
                        + " FROM his_registration"
                        + " WHERE work_date = CURDATE() AND tenant_id = ? AND deleted = 0",
                tenantId());
    }

    /**
     * 挂号多维统计(JdbcTemplate 手写 SQL, 显式 tenant_id AND deleted=0):
     * byDept/byStaff 取 Top10; byDate 区分挂号/退号/减免; 费别/支付方式/减免类型带实收金额与减免金额。
     * from/to 为空默认当天, 支持 deptId/staffId 过滤。
     */
    public Map<String, Object> stats(String from, String to, Long deptId, Long staffId) {
        LocalDate fromDate = parseDate(from, "开始日期");
        LocalDate toDate = parseDate(to, "结束日期");
        if (fromDate == null) {
            fromDate = LocalDate.now();
        }
        if (toDate == null) {
            toDate = LocalDate.now();
        }
        if (toDate.isBefore(fromDate)) {
            throw new BizException(400, "结束日期不能早于开始日期");
        }
        StringBuilder where = new StringBuilder(
                " WHERE tenant_id = ? AND deleted = 0 AND work_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(fromDate);
        args.add(toDate);
        if (deptId != null) {
            where.append(" AND dept_id = ?");
            args.add(deptId);
        }
        if (staffId != null) {
            where.append(" AND staff_id = ?");
            args.add(staffId);
        }
        String w = where.toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byDept", jdbcTemplate.queryForList(
                "SELECT dept_id, dept_name, COUNT(*) AS `count`,"
                        + " IFNULL(SUM(reg_fee), 0) AS totalFee,"
                        + " IFNULL(SUM(discount_amount), 0) AS discountAmount"
                        + " FROM his_registration" + w
                        + " GROUP BY dept_id, dept_name ORDER BY `count` DESC LIMIT 10", args.toArray()));
        result.put("byTimeType", jdbcTemplate.queryForList(
                "SELECT time_type, COUNT(*) AS `count`"
                        + " FROM his_registration" + w
                        + " GROUP BY time_type ORDER BY FIELD(time_type, 'am', 'pm', 'night')", args.toArray()));
        result.put("byDate", jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(work_date, '%Y-%m-%d') AS work_date,"
                        + " IFNULL(SUM(CASE WHEN status IN (1,3) THEN 1 ELSE 0 END), 0) AS regCount,"
                        + " IFNULL(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS cancelCount,"
                        + " IFNULL(SUM(CASE WHEN discount_type IS NOT NULL AND discount_type != 'none' THEN 1 ELSE 0 END), 0) AS discountCount"
                        + " FROM his_registration" + w
                        + " GROUP BY work_date ORDER BY work_date ASC", args.toArray()));
        result.put("byStaff", jdbcTemplate.queryForList(
                "SELECT staff_id, dr_name, COUNT(*) AS `count`"
                        + " FROM his_registration" + w
                        + " GROUP BY staff_id, dr_name ORDER BY `count` DESC LIMIT 10", args.toArray()));
        result.put("byFeeType", jdbcTemplate.queryForList(
                "SELECT fee_type, COUNT(*) AS `count`, IFNULL(SUM(actual_fee), 0) AS totalActualFee"
                        + " FROM his_registration" + w
                        + " GROUP BY fee_type ORDER BY `count` DESC", args.toArray()));
        result.put("byPayMethod", jdbcTemplate.queryForList(
                "SELECT pay_method, COUNT(*) AS `count`, IFNULL(SUM(actual_fee), 0) AS totalActualFee"
                        + " FROM his_registration" + w
                        + " GROUP BY pay_method ORDER BY `count` DESC", args.toArray()));
        result.put("byDiscountType", jdbcTemplate.queryForList(
                "SELECT discount_type, COUNT(*) AS `count`, IFNULL(SUM(discount_amount), 0) AS totalAmount"
                        + " FROM his_registration" + w
                        + " GROUP BY discount_type ORDER BY `count` DESC", args.toArray()));
        return result;
    }

    /** 统计明细: 日期区间 + 科室/医师/状态/关键字筛选的分页查询(JdbcTemplate 显式租户过滤) */
    public IPage<Map<String, Object>> statDetail(String from, String to, Long deptId, Long staffId,
                                                 Integer status, String keyword, int page, int size) {
        long p = safePage(page);
        long s = safeSize(size);
        LocalDate fromDate = parseDate(from, "开始日期");
        LocalDate toDate = parseDate(to, "结束日期");
        if (fromDate == null) {
            fromDate = LocalDate.now();
        }
        if (toDate == null) {
            toDate = LocalDate.now();
        }
        StringBuilder where = new StringBuilder(
                " WHERE tenant_id = ? AND deleted = 0 AND work_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(fromDate);
        args.add(toDate);
        if (deptId != null) {
            where.append(" AND dept_id = ?");
            args.add(deptId);
        }
        if (staffId != null) {
            where.append(" AND staff_id = ?");
            args.add(staffId);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (patient_name LIKE ? OR reg_no LIKE ? OR patient_no LIKE ? OR ipt_otp_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_registration" + where, Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, reg_no, patient_id, patient_no, patient_name, dept_id, dept_name, staff_id, dr_name,"
                        + " schedule_id, DATE_FORMAT(work_date, '%Y-%m-%d') AS work_date, time_type, reg_level_code, reg_level_name,"
                        + " reg_fee, fee_type, discount_type, discount_reason, discount_amount, actual_fee,"
                        + " pay_method, pay_detail, queue_no, status, reg_time, cancel_time, cancel_reason,"
                        + " ipt_otp_no, mdtrt_id, operator"
                        + " FROM his_registration" + where
                        + " ORDER BY id DESC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /** 重复挂号预检: {duplicate: 同号源已挂, sameDept: 当日同科室已挂, sameDeptInfo: 提示文案} */
    public Map<String, Object> checkDuplicate(Long patientId, Long scheduleId) {
        boolean duplicate = false;
        boolean sameDept = false;
        String sameDeptInfo = null;
        if (patientId != null && scheduleId != null) {
            long dupCnt = lambdaQuery()
                    .eq(HisRegistration::getPatientId, patientId)
                    .eq(HisRegistration::getScheduleId, scheduleId)
                    .eq(HisRegistration::getStatus, 1)
                    .count();
            duplicate = dupCnt > 0;
            HisSchedule schedule = scheduleService.getById(scheduleId);
            if (schedule != null && schedule.getWorkDate() != null && schedule.getDeptId() != null) {
                long sameDeptCnt = lambdaQuery()
                        .eq(HisRegistration::getPatientId, patientId)
                        .eq(HisRegistration::getWorkDate, schedule.getWorkDate())
                        .eq(HisRegistration::getDeptId, schedule.getDeptId())
                        .eq(HisRegistration::getStatus, 1)
                        .count();
                sameDept = sameDeptCnt > 0;
                if (sameDept) {
                    HisDept dept = deptService.getById(schedule.getDeptId());
                    sameDeptInfo = "该患者当日已在" + (dept == null ? "本科室" : dept.getDeptName()) + "挂过号";
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("duplicate", duplicate);
        result.put("sameDept", sameDept);
        result.put("sameDeptInfo", sameDeptInfo);
        return result;
    }

    private String genNo(String prefix) {
        int s = SEQ.incrementAndGet() % 1000;
        return prefix + DateUtil.currentTimeCompact() + String.format("%03d", s);
    }

    /**
     * 减免计算: 生效类型与减免金额。
     * 规则: 前端传入 discountType 优先; 未传且患者年龄>=70 自动 age70free; 未传默认 none;
     * 非 none 类型默认全免(部分减免金额由前端显式传入), 金额夹取到 [0, regFee]。
     * 返回序位: [0]=生效减免类型(String), [1]=减免金额(BigDecimal)。
     */
    private Object[] calcDiscount(HisPatient patient, BigDecimal regFee, String discountType, BigDecimal discountAmount) {
        String effDiscountType = StringUtils.hasText(discountType) ? discountType.trim() : null;
        if (effDiscountType == null && patient.getAge() != null && patient.getAge() >= 70) {
            effDiscountType = DISCOUNT_AGE70;
        }
        if (!StringUtils.hasText(effDiscountType)) {
            effDiscountType = DISCOUNT_NONE;
        }
        BigDecimal effDiscountAmount = BigDecimal.ZERO;
        if (!DISCOUNT_NONE.equals(effDiscountType)) {
            effDiscountAmount = discountAmount != null ? discountAmount : regFee;
            if (effDiscountAmount.signum() < 0) {
                effDiscountAmount = BigDecimal.ZERO;
            }
            if (effDiscountAmount.compareTo(regFee) > 0) {
                effDiscountAmount = regFee;
            }
        }
        return new Object[]{effDiscountType, effDiscountAmount};
    }

    /** 生成候诊序号: 科室简码+4位流水号(当日同科室同时段 MAX+1), 如 NK-0015 */
    private String generateQueueNo(Long deptId, String timeType, LocalDate workDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(MAX(CAST(SUBSTRING_INDEX(queue_no, '-', -1) AS UNSIGNED)), 0)"
                        + " FROM his_registration WHERE tenant_id = ? AND deleted = 0 AND dept_id = ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (deptId == null) {
            sql.append("IS NULL");
        } else {
            sql.append("?");
            args.add(deptId);
        }
        if (StringUtils.hasText(timeType)) {
            sql.append(" AND time_type = ?");
            args.add(timeType.trim());
        }
        if (workDate != null) {
            sql.append(" AND work_date = ?");
            args.add(workDate);
        }
        Integer maxSeq = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        int next = (maxSeq == null ? 0 : maxSeq) + 1;
        return deptAbbr(deptId) + "-" + String.format("%04d", next);
    }

    /** 科室简码: his_dept.py_code 前2位大写, 无 py_code 退化 dept_id 前2位 */
    private String deptAbbr(Long deptId) {
        if (deptId != null) {
            List<String> pys = jdbcTemplate.queryForList(
                    "SELECT py_code FROM his_dept WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    String.class, deptId, tenantId());
            if (!pys.isEmpty() && StringUtils.hasText(pys.get(0))) {
                String up = pys.get(0).trim().toUpperCase();
                return up.length() > 2 ? up.substring(0, 2) : up;
            }
        }
        String idStr = String.valueOf(deptId == null ? 0L : deptId);
        return idStr.length() > 2 ? idStr.substring(0, 2) : idStr;
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 解析 yyyy-MM-dd(空返回 null, 格式错误抛400) */
    private static LocalDate parseDate(String d, String field) {
        if (!StringUtils.hasText(d)) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(400, field + "格式错误, 应为 yyyy-MM-dd: " + d);
        }
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }
}
