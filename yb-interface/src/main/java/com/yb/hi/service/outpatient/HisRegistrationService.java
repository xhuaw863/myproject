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
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.outpatient.HisRegistrationMapper;
import com.yb.hi.service.OutpatientService;
import com.yb.hi.service.basedata.HisDeptService;
import com.yb.hi.service.basedata.HisScheduleService;
import com.yb.hi.service.basedata.HisStaffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 挂号服务: 编排院内挂号记录 + 医保2201挂号 / 2202撤销
 */
@Slf4j
@Service
public class HisRegistrationService extends ServiceImpl<HisRegistrationMapper, HisRegistration> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private final HisPatientService patientService;
    private final HisScheduleService scheduleService;
    private final HisStaffService staffService;
    private final HisDeptService deptService;
    private final OutpatientService outpatientService;

    public HisRegistrationService(HisPatientService patientService, HisScheduleService scheduleService,
                                  HisStaffService staffService, HisDeptService deptService,
                                  OutpatientService outpatientService) {
        this.patientService = patientService;
        this.scheduleService = scheduleService;
        this.staffService = staffService;
        this.deptService = deptService;
        this.outpatientService = outpatientService;
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
                    .or().like(HisRegistration::getIptOtpNo, keyword));
        }
        return q.orderByDesc(HisRegistration::getId).page(new Page<>(page, size));
    }

    /**
     * 挂号: 校验患者/排班 -> 组装2201 -> 调用医保 -> 回填mdtrt_id -> 落库 -> 扣减号源
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRegistration register(Long patientId, Long scheduleId, String medType) {
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
        HisStaff staff = staffService.getById(schedule.getStaffId());
        HisDept dept = deptService.getById(schedule.getDeptId());

        String iptOtpNo = genNo("OT");
        String regNo = genNo("R");

        // 组装医保2201请求
        OutpatientRegisterReq req = new OutpatientRegisterReq();
        req.setPsnNo(patient.getPsnNo());
        req.setInsutype(patient.getInsutype());
        req.setBegntime(DateUtil.currentDateTime());
        req.setMdtrtCertType(StringUtils.hasText(patient.getMdtrtCertType()) ? patient.getMdtrtCertType() : "02");
        req.setMdtrtCertNo(StringUtils.hasText(patient.getMdtrtCertNo()) ? patient.getMdtrtCertNo() : patient.getIdCard());
        req.setIptOtpNo(iptOtpNo);
        if (staff != null) {
            req.setAtddrNo(staff.getAtddrNo());
            req.setDrName(staff.getStaffName());
        }
        if (dept != null) {
            req.setDeptCode(StringUtils.hasText(dept.getYbDeptCode()) ? dept.getYbDeptCode() : dept.getDeptCode());
            req.setDeptName(dept.getDeptName());
            req.setCaty(dept.getDeptCaty());
        }
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
        save(reg);

        // 扣减号源
        schedule.setLeftNum(schedule.getLeftNum() - 1);
        scheduleService.updateById(schedule);

        log.info("挂号成功: regNo={}, mdtrtId={}, patient={}", regNo, mdtrtId, patient.getName());
        return reg;
    }

    /**
     * 退号: 校验状态 -> 调用医保2202 -> 更新状态 -> 回滚号源
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long registrationId, String reason) {
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

        // 回滚号源
        if (reg.getScheduleId() != null) {
            HisSchedule schedule = scheduleService.getById(reg.getScheduleId());
            if (schedule != null) {
                schedule.setLeftNum((schedule.getLeftNum() == null ? 0 : schedule.getLeftNum()) + 1);
                scheduleService.updateById(schedule);
            }
        }
        log.info("退号成功: regNo={}, mdtrtId={}", reg.getRegNo(), reg.getMdtrtId());
    }

    private String genNo(String prefix) {
        int s = SEQ.incrementAndGet() % 1000;
        return prefix + DateUtil.currentTimeCompact() + String.format("%03d", s);
    }
}
