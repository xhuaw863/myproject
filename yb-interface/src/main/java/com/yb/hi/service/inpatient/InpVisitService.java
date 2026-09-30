package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.AllergyDTO;
import com.yb.hi.dto.inpatient.InpAdmitDTO;
import com.yb.hi.dto.inpatient.InpTransferDTO;
import com.yb.hi.entity.doctor.HisAdmissionCert;
import com.yb.hi.entity.inpatient.HisBed;
import com.yb.hi.entity.inpatient.HisInpDiagnosis;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.doctor.HisAdmissionCertMapper;
import com.yb.hi.mapper.inpatient.HisBedMapper;
import com.yb.hi.mapper.inpatient.HisInpDiagnosisMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 住院就诊服务: 入院登记(住院号生成+占床+入院诊断)、预入院登记(1待入院, 不占床)与确认入院(1->2+占床)、
 * 在院患者列表(JOIN患者/床位)、就诊详情、转科转床(换床原子链路)、出院申请(2->3)、取消入院(->5 释放床位)。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class InpVisitService {

    /** 住院号日期段格式 */
    private static final DateTimeFormatter INP_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 住院号流水内存计数器: 键=租户|机构|日期, 首次使用从当日 DB MAX 惰性播种。
     *  并发下裸 MAX+1 会重号, 内存计数与挂号候诊序号同模式; 多实例部署需单点生成或唯一键兜底。 */
    private final Map<String, AtomicInteger> INP_NO_SEQS = new ConcurrentHashMap<>();

    private final HisInpVisitMapper visitMapper;
    private final HisInpDiagnosisMapper diagnosisMapper;
    private final HisBedMapper bedMapper;
    private final HisWardMapper wardMapper;
    private final InpBedService bedService;
    private final InpAllergyService allergyService;
    private final JdbcTemplate jdbcTemplate;
    private final HisAdmissionCertMapper admissionCertMapper;

    public InpVisitService(HisInpVisitMapper visitMapper, HisInpDiagnosisMapper diagnosisMapper,
                           HisBedMapper bedMapper, HisWardMapper wardMapper,
                           InpBedService bedService, InpAllergyService allergyService,
                           JdbcTemplate jdbcTemplate, HisAdmissionCertMapper admissionCertMapper) {
        this.visitMapper = visitMapper;
        this.diagnosisMapper = diagnosisMapper;
        this.bedMapper = bedMapper;
        this.wardMapper = wardMapper;
        this.bedService = bedService;
        this.allergyService = allergyService;
        this.jdbcTemplate = jdbcTemplate;
        this.admissionCertMapper = admissionCertMapper;
    }

    /* ==================== 入院登记 ==================== */

    /**
     * 入院登记: 校验患者/床位/重复在院 -> 生成住院号(INP+yyyyMMdd+4位序号)
     * -> 创建visit(status=2在院) -> 乐观占用床位 -> 创建入院诊断(diagType=1, isMain=1)。
     * 持证传 certId 时: 校验证(存在/属同一患者/未作废未使用) -> 未填的科室/入院诊断按证预填
     * -> visit 记 admission_cert_id 溯源 -> 同事务内乐观回写证状态1->2(撞证拒绝, 登记回滚)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit admit(InpAdmitDTO dto, Long orgId) {
        if (dto == null || dto.getPatientId() == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        if (dto.getBedId() == null) {
            throw new BizException(400, "入院必须指定床位");
        }
        // 患者校验(存在性+姓名回显)
        String patientName = requirePatient(dto.getPatientId());

        // 床位校验: 归属本机构且为空床
        HisBed bed = requireAvailableBed(dto.getBedId(), orgId);

        // 重复在院拦截: 同患者已有未结束的住院(待入院/在院/出院办理中)
        ensureNoActiveVisit(dto.getPatientId(), null, patientName);

        // 持证入院: 校验住院证并用证上信息预填未录字段(拟收科室/入院诊断)
        HisAdmissionCert cert = resolveAdmissionCert(dto);
        if (cert != null) {
            if (dto.getDeptId() == null) {
                dto.setDeptId(cert.getAdmitDeptId());
            }
            if (!StringUtils.hasText(dto.getAdmitDiag())) {
                dto.setAdmitDiag(cert.getAdmitDiagnosis());
            }
        }

        // 病区: 床位所属病区为准(入参 wardId 缺失时自动回填)
        Long wardId = dto.getWardId() != null ? dto.getWardId() : bed.getWardId();

        HisInpVisit visit = new HisInpVisit();
        visit.setOrgId(orgId);
        visit.setInpNo(generateInpNo(orgId));
        visit.setPatientId(dto.getPatientId());
        visit.setWardId(wardId);
        visit.setBedId(dto.getBedId());
        visit.setDeptId(dto.getDeptId());
        visit.setDoctorId(dto.getDoctorId());
        visit.setAdmitDate(LocalDateTime.now());
        visit.setVisitStatus(2);
        visit.setAdmitDiag(dto.getAdmitDiag());
        visit.setTotalCost(BigDecimal.ZERO);
        visit.setDepositBalance(BigDecimal.ZERO);
        visit.setMedType(dto.getMedType());
        visit.setPsnNo(dto.getPsnNo());
        visit.setInsutype(dto.getInsutype());
        visit.setAdmissionCertId(cert == null ? null : cert.getId());
        visitMapper.insert(visit);

        // 乐观占用床位(affected=0 抛异常, 事务回滚 visit)
        bedService.allocateBed(dto.getBedId(), dto.getPatientId(), visit.getId());

        // 入院诊断(diagType=1入院诊断, 默认主诊断)
        if (StringUtils.hasText(dto.getAdmitDiag())) {
            HisInpDiagnosis diag = new HisInpDiagnosis();
            diag.setOrgId(orgId);
            diag.setInpVisitId(visit.getId());
            diag.setDiagType(1);
            diag.setDiagName(dto.getAdmitDiag().trim());
            diag.setIsMain(1);
            diag.setDiagDeptId(dto.getDeptId());
            diag.setDiagDoctorId(dto.getDoctorId());
            diag.setDiagTime(LocalDateTime.now());
            diag.setSortNo(1);
            diagnosisMapper.insert(diag);
        }

        // 联系人/担保人扩展信息(任一非空时回写, updateById 忽略null字段)
        saveContactInfo(visit, dto);

        // 入院登记时录入的过敏史逐条落库(驱动开嘱过敏拦截与腕带提示)
        saveAllergies(visit, dto);

        // 消费住院证: 乐观回写状态1->2并记溯源(affected=0 即证被并发使用/作废, 抛异常整事务回滚)
        if (cert != null) {
            int used = admissionCertMapper.update(null, new LambdaUpdateWrapper<HisAdmissionCert>()
                    .eq(HisAdmissionCert::getId, cert.getId())
                    .eq(HisAdmissionCert::getStatus, 1)
                    .set(HisAdmissionCert::getStatus, 2)
                    .set(HisAdmissionCert::getAdmittedVisitId, visit.getId()));
            if (used == 0) {
                throw new BizException("住院证已被使用或已作废, 请刷新后重试");
            }
        }

        log.info("入院登记成功: inpNo={}, patientId={}, patientName={}, bedNo={}, visitId={}, certId={}",
                visit.getInpNo(), dto.getPatientId(), patientName, bed.getBedNo(), visit.getId(),
                cert == null ? null : cert.getId());
        return visit;
    }

    /** 持证入院校验: certId 空返回 null(不持证); 否则逐条拦截不存在/跨患者/已作废/已使用 */
    private HisAdmissionCert resolveAdmissionCert(InpAdmitDTO dto) {
        if (dto.getCertId() == null) {
            return null;
        }
        HisAdmissionCert cert = admissionCertMapper.selectById(dto.getCertId());
        if (cert == null) {
            throw new BizException(400, "住院证不存在或已删除");
        }
        if (cert.getPatientId() == null || !cert.getPatientId().equals(dto.getPatientId())) {
            throw new BizException(400, "住院证不属于所选患者, 请核对证上姓名与登记患者");
        }
        if (cert.getStatus() != null && cert.getStatus() == 3) {
            throw new BizException("该住院证已作废, 不能持证入院");
        }
        if (cert.getStatus() != null && cert.getStatus() == 2) {
            throw new BizException("该住院证已办理过入院, 请勿重复登记");
        }
        return cert;
    }

    /** 患者校验(存在性+姓名回显) */
    private String requirePatient(Long patientId) {
        List<Map<String, Object>> pRows = jdbcTemplate.queryForList(
                "SELECT name FROM his_patient WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                patientId, tenantId());
        if (pRows.isEmpty()) {
            throw new BizException(404, "患者档案不存在, 请先建档");
        }
        return String.valueOf(pRows.get(0).get("name"));
    }

    /** 床位校验: 归属指定机构且为空床(orgId 非空时校验机构) */
    private HisBed requireAvailableBed(Long bedId, Long orgId) {
        HisBed bed = bedMapper.selectById(bedId);
        if (bed == null) {
            throw new BizException(404, "床位不存在或已删除");
        }
        if (orgId != null && !orgId.equals(bed.getOrgId())) {
            throw new BizException(403, "床位不属于本机构, 不能办理入院");
        }
        if (bed.getStatus() == null || bed.getStatus() != 0) {
            throw new BizException("床位不可用(占用中或已停用), 请选择空床");
        }
        return bed;
    }

    /** 重复在院拦截: 同患者已有未结束的住院(待入院/在院/出院办理中, excludeVisitId 用于排除自身) */
    private void ensureNoActiveVisit(Long patientId, Long excludeVisitId, String patientName) {
        LambdaQueryWrapper<HisInpVisit> qw = new LambdaQueryWrapper<HisInpVisit>()
                .eq(HisInpVisit::getPatientId, patientId)
                .in(HisInpVisit::getVisitStatus, 1, 2, 3);
        if (excludeVisitId != null) {
            qw.ne(HisInpVisit::getId, excludeVisitId);
        }
        long activeCnt = visitMapper.selectCount(qw);
        if (activeCnt > 0) {
            throw new BizException("患者【" + patientName + "】已有未结案的住院记录, 不能重复入院");
        }
    }

    /** 联系人/担保人扩展信息回写(任一非空时落库, updateById 忽略null字段) */
    private void saveContactInfo(HisInpVisit visit, InpAdmitDTO dto) {
        boolean hasContact = StringUtils.hasText(dto.getContactName()) || StringUtils.hasText(dto.getContactPhone())
                || StringUtils.hasText(dto.getContactRelation()) || StringUtils.hasText(dto.getGuarantorName())
                || StringUtils.hasText(dto.getGuarantorPhone()) || StringUtils.hasText(dto.getGuarantorIdNo());
        if (!hasContact) {
            return;
        }
        HisInpVisit ext = new HisInpVisit();
        ext.setId(visit.getId());
        ext.setContactName(dto.getContactName());
        ext.setContactPhone(dto.getContactPhone());
        ext.setContactRelation(dto.getContactRelation());
        ext.setGuarantorName(dto.getGuarantorName());
        ext.setGuarantorPhone(dto.getGuarantorPhone());
        ext.setGuarantorIdNo(dto.getGuarantorIdNo());
        visitMapper.updateById(ext);
        visit.setContactName(dto.getContactName());
        visit.setContactPhone(dto.getContactPhone());
        visit.setContactRelation(dto.getContactRelation());
        visit.setGuarantorName(dto.getGuarantorName());
        visit.setGuarantorPhone(dto.getGuarantorPhone());
        visit.setGuarantorIdNo(dto.getGuarantorIdNo());
    }

    /** 过敏史逐条落库(驱动开嘱过敏拦截与腕带提示) */
    private void saveAllergies(HisInpVisit visit, InpAdmitDTO dto) {
        if (dto.getAllergies() == null || dto.getAllergies().isEmpty()) {
            return;
        }
        for (AllergyDTO ad : dto.getAllergies()) {
            if (ad == null || !StringUtils.hasText(ad.getAllergenName())) {
                continue;
            }
            ad.setInpVisitId(visit.getId());
            if (ad.getPatientId() == null) {
                ad.setPatientId(dto.getPatientId());
            }
            if (ad.getDoctorId() == null) {
                ad.setDoctorId(dto.getDoctorId());
            }
            allergyService.add(ad);
        }
    }

    /**
     * 在院患者列表(分页, LEFT JOIN his_patient 取姓名, LEFT JOIN his_bed/his_ward 取床位):
     * 支持机构/病区/科室/状态筛选与关键字(姓名/住院号/患者号/身份证/拼音简码)。
     */
    public IPage<Map<String, Object>> listPatients(Long orgId, Long wardId, Long deptId,
                                                   Integer visitStatus, String keyword,
                                                   int page, int size) {
        long p = safePage(page);
        long s = safeSize(size);

        StringBuilder where = new StringBuilder(
                " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id"
                        + " LEFT JOIN his_bed b ON b.id = v.bed_id"
                        + " LEFT JOIN his_ward w ON w.id = v.ward_id"
                        + " WHERE v.deleted = 0 AND v.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            where.append(" AND v.org_id = ?");
            args.add(orgId);
        }
        if (wardId != null) {
            where.append(" AND v.ward_id = ?");
            args.add(wardId);
        }
        if (deptId != null) {
            where.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (visitStatus != null) {
            where.append(" AND v.visit_status = ?");
            args.add(visitStatus);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            where.append(" AND (p.name LIKE ? OR v.inp_no LIKE ? OR p.patient_no LIKE ?"
                    + " OR p.id_card LIKE ? OR p.py_code LIKE ?)");
            String like = "%" + kw + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + where, Long.class, args.toArray());
        long cnt = total == null ? 0L : total;

        String cols = "SELECT v.id, v.inp_no, v.patient_id, p.name patient_name, p.gender, p.gender_name,"
                + " p.age, p.phone, p.id_card, v.ward_id, w.ward_name, v.bed_id, b.bed_no, b.room_no,"
                + " v.dept_id, v.doctor_id, v.nurse_id, v.visit_status, v.admit_date, v.discharge_date,"
                + " v.admit_diag, v.total_cost, v.deposit_balance, v.med_type, v.mdtrt_id, v.psn_no";
        List<Map<String, Object>> records = cnt == 0 ? new ArrayList<>()
                : jdbcTemplate.queryForList(
                cols + where + " ORDER BY v.id DESC LIMIT ?, ?",
                appendOffset(args, (p - 1) * s, s));

        Page<Map<String, Object>> result = new Page<>(p, s, cnt);
        result.setRecords(records);
        return result;
    }

    /** 就诊详情: visit + 患者信息 + 床位/病区信息 + 诊断列表 */
    public Map<String, Object> getDetail(Long id) {
        HisInpVisit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("visit", visit);

        // 患者信息(含医保身份要素)
        if (visit.getPatientId() != null) {
            List<Map<String, Object>> pRows = jdbcTemplate.queryForList(
                    "SELECT * FROM his_patient WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    visit.getPatientId(), tenantId());
            detail.put("patient", pRows.isEmpty() ? null : pRows.get(0));
        }
        // 床位+病区信息
        if (visit.getBedId() != null) {
            detail.put("bed", bedMapper.selectById(visit.getBedId()));
        }
        if (visit.getWardId() != null) {
            detail.put("ward", wardMapper.selectById(visit.getWardId()));
        }
        // 诊断列表(按类型+排序号)
        List<HisInpDiagnosis> diags = diagnosisMapper.selectList(
                new LambdaQueryWrapper<HisInpDiagnosis>()
                        .eq(HisInpDiagnosis::getInpVisitId, id)
                        .orderByAsc(HisInpDiagnosis::getDiagType)
                        .orderByAsc(HisInpDiagnosis::getSortNo));
        detail.put("diagnoses", diags);
        return detail;
    }

    /* ==================== 转科/转床 ==================== */

    /**
     * 转科/转床: 先乐观占用新床(失败无副作用) -> 释放旧床 -> 回写visit(病区/床位/科室/医生)。
     * 顺序保证换床原子性: 新床抢占失败时旧床不受影响; 全程事务, 中途失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit transfer(Long id, InpTransferDTO dto) {
        HisInpVisit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() == null || visit.getVisitStatus() != 2) {
            throw new BizException("患者不在院(状态=" + visit.getVisitStatus() + "), 不能转科/转床");
        }
        if (dto == null || dto.getTargetBedId() == null) {
            throw new BizException(400, "目标床位不能为空");
        }
        if (dto.getTargetBedId().equals(visit.getBedId())) {
            throw new BizException("目标床位与当前床位相同");
        }
        HisBed targetBed = bedMapper.selectById(dto.getTargetBedId());
        if (targetBed == null) {
            throw new BizException(404, "目标床位不存在或已删除");
        }
        if (!visit.getOrgId().equals(targetBed.getOrgId())) {
            throw new BizException(403, "目标床位不属于本机构");
        }
        // 抢占新床(乐观锁, affected=0 抛"床位不可用或已被占用")
        bedService.allocateBed(dto.getTargetBedId(), visit.getPatientId(), visit.getId());
        // 释放旧床
        if (visit.getBedId() != null) {
            bedService.releaseBed(visit.getBedId());
        }
        // 回写visit: 病区以目标床位所属病区为准(入参 targetWardId 可选覆盖)
        HisInpVisit upd = new HisInpVisit();
        upd.setId(visit.getId());
        upd.setBedId(dto.getTargetBedId());
        upd.setWardId(dto.getTargetWardId() != null ? dto.getTargetWardId() : targetBed.getWardId());
        if (dto.getTargetDeptId() != null) {
            upd.setDeptId(dto.getTargetDeptId());
        }
        if (dto.getTargetDoctorId() != null) {
            upd.setDoctorId(dto.getTargetDoctorId());
        }
        visitMapper.updateById(upd);
        log.info("转科/转床完成: visitId={}, {} -> {}(病区{}), 原因: {}",
                id, visit.getBedId(), dto.getTargetBedId(), upd.getWardId(),
                StringUtils.hasText(dto.getReason()) ? dto.getReason() : "未填");
        return visitMapper.selectById(id);
    }

    /* ==================== 出院申请/取消入院 ==================== */

    /** 出院申请: visit_status 2在院 -> 3出院办理中(乐观更新, 并发下仅一次生效) */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit dischargeApply(Long id) {
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_visit SET visit_status = 3, update_time = NOW()"
                        + " WHERE id = ? AND visit_status = 2 AND deleted = 0 AND tenant_id = ?",
                id, tenantId());
        if (affected == 0) {
            throw new BizException("该就诊不在院或已提交出院申请, 无法重复操作");
        }
        return visitMapper.selectById(id);
    }

    /**
     * 取消入院: visit_status -> 5已取消(待入院/在院/出院办理中均可取消), 释放床位。
     * 已出院(4)的记录不可取消(撤销请走结算撤销链路)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit cancel(Long id) {
        HisInpVisit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() != null && visit.getVisitStatus() == 4) {
            throw new BizException("该就诊已出院结算, 不能取消入院(请走撤销结算流程)");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_visit SET visit_status = 5, update_time = NOW()"
                        + " WHERE id = ? AND visit_status IN (1, 2, 3) AND deleted = 0 AND tenant_id = ?",
                id, tenantId());
        if (affected == 0) {
            throw new BizException("该就诊已取消或状态已变化, 无法取消入院");
        }
        // 释放床位(取消入院后床位回到空床池)
        if (visit.getBedId() != null) {
            bedService.releaseBed(visit.getBedId());
        }
        log.info("取消入院: visitId={}, inpNo={}, 原状态={}", id, visit.getInpNo(), visit.getVisitStatus());
        return visitMapper.selectById(id);
    }

    /* ==================== 预入院管理 ==================== */

    /**
     * 预入院登记: 校验患者/重复在院 -> 生成住院号 -> 创建visit(status=1待入院, 不分配床位)
     * -> 记录预入院时间与可选检查项 -> 联系人/担保人/过敏史扩展落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit preAdmit(InpAdmitDTO dto, Long orgId) {
        if (dto == null || dto.getPatientId() == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        String patientName = requirePatient(dto.getPatientId());
        ensureNoActiveVisit(dto.getPatientId(), null, patientName);

        HisInpVisit visit = new HisInpVisit();
        visit.setOrgId(orgId);
        visit.setInpNo(generateInpNo(orgId));
        visit.setPatientId(dto.getPatientId());
        visit.setWardId(dto.getWardId());
        visit.setDeptId(dto.getDeptId());
        visit.setDoctorId(dto.getDoctorId());
        visit.setVisitStatus(1);
        visit.setPreAdmitTime(LocalDateTime.now());
        visit.setPreCheckItems(dto.getPreCheckItems());
        visit.setAdmitDiag(dto.getAdmitDiag());
        visit.setTotalCost(BigDecimal.ZERO);
        visit.setDepositBalance(BigDecimal.ZERO);
        visit.setMedType(dto.getMedType());
        visit.setPsnNo(dto.getPsnNo());
        visit.setInsutype(dto.getInsutype());
        visitMapper.insert(visit);

        saveContactInfo(visit, dto);
        saveAllergies(visit, dto);
        log.info("预入院登记成功: inpNo={}, patientId={}, patientName={}, deptId={}, visitId={}",
                visit.getInpNo(), dto.getPatientId(), patientName, dto.getDeptId(), visit.getId());
        return visit;
    }

    /**
     * 预入院确认入院: 校验待入院(1)+床位可用 -> 重复在院拦截(排除自身) -> 乐观状态翻转(1->2, 分配床位)
     * -> 占用床位 -> 补落入院诊断(预入院已录入且未落时)。并发重复确认/取消被状态锁拦截。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit confirmAdmit(Long visitId, Long bedId) {
        if (bedId == null) {
            throw new BizException(400, "确认入院必须指定床位");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() == null || visit.getVisitStatus() != 1) {
            throw new BizException("该就诊非待入院状态(状态=" + visit.getVisitStatus() + "), 不能确认入院");
        }
        String patientName = requirePatient(visit.getPatientId());
        ensureNoActiveVisit(visit.getPatientId(), visitId, patientName);
        HisBed bed = requireAvailableBed(bedId, visit.getOrgId());

        // 乐观状态翻转 1->2: 并发重复确认/取消在此被拦截; 病区以床位所属病区为准
        Long wardId = bed.getWardId() != null ? bed.getWardId() : visit.getWardId();
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_visit SET visit_status = 2, bed_id = ?, ward_id = ?, admit_date = NOW(), update_time = NOW()"
                        + " WHERE id = ? AND visit_status = 1 AND deleted = 0 AND tenant_id = ?",
                bedId, wardId, visitId, tenantId());
        if (affected == 0) {
            throw new BizException("该就诊已确认或已取消, 请刷新后重试");
        }
        // 占用床位(乐观锁, 失败整体回滚)
        bedService.allocateBed(bedId, visit.getPatientId(), visitId);

        // 入院诊断补落(diagType=1; 预入院录入了诊断且未落库时才插入)
        if (StringUtils.hasText(visit.getAdmitDiag())) {
            long diagCnt = diagnosisMapper.selectCount(new LambdaQueryWrapper<HisInpDiagnosis>()
                    .eq(HisInpDiagnosis::getInpVisitId, visitId)
                    .eq(HisInpDiagnosis::getDiagType, 1));
            if (diagCnt == 0) {
                HisInpDiagnosis diag = new HisInpDiagnosis();
                diag.setOrgId(visit.getOrgId());
                diag.setInpVisitId(visitId);
                diag.setDiagType(1);
                diag.setDiagName(visit.getAdmitDiag().trim());
                diag.setIsMain(1);
                diag.setDiagDeptId(visit.getDeptId());
                diag.setDiagDoctorId(visit.getDoctorId());
                diag.setDiagTime(LocalDateTime.now());
                diag.setSortNo(1);
                diagnosisMapper.insert(diag);
            }
        }
        log.info("预入院确认入院: visitId={}, inpNo={}, bedId={}, 患者={}",
                visitId, visit.getInpNo(), bedId, patientName);
        return visitMapper.selectById(visitId);
    }

    /** 取消预入院: visit_status 1待入院 -> 5已取消(乐观更新; 既未占床也无需释放床位) */
    @Transactional(rollbackFor = Exception.class)
    public HisInpVisit cancelPreAdmit(Long visitId) {
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_visit SET visit_status = 5, update_time = NOW()"
                        + " WHERE id = ? AND visit_status = 1 AND deleted = 0 AND tenant_id = ?",
                visitId, tenantId());
        if (affected == 0) {
            throw new BizException("该就诊非待入院状态或已取消, 无法取消预入院");
        }
        log.info("取消预入院: visitId={}, inpNo={}", visitId, visit.getInpNo());
        return visitMapper.selectById(visitId);
    }

    /**
     * 待入院(预入院)列表: JOIN his_patient 取患者信息, 按预入院时间倒序。
     * deptId 可选过滤(拟入科室), orgId 由控制层 scopeOrgId 传入(非牵头锁定本机构)。
     */
    public List<Map<String, Object>> listPreAdmissions(Long deptId, Long orgId) {
        StringBuilder sql = new StringBuilder(
                "SELECT v.id, v.inp_no, v.patient_id, p.name patient_name, p.gender, p.gender_name, p.age,"
                        + " p.id_card, p.phone, v.dept_id, v.doctor_id, v.ward_id, v.admit_diag, v.med_type,"
                        + " v.psn_no, v.insutype, v.org_id, v.pre_admit_time, v.pre_check_items"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id"
                        + " WHERE v.visit_status = 1 AND v.deleted = 0 AND v.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (orgId != null) {
            sql.append(" AND v.org_id = ?");
            args.add(orgId);
        }
        sql.append(" ORDER BY v.pre_admit_time DESC, v.id DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /* ==================== 在院信息维护 ==================== */

    /**
     * 在院信息更新(白名单字段): 护理等级/饮食类型/病情等级/预交金预警线/联系人/担保人/
     * 血型/入院来源/隔离标志/责任护士/预计出院日期。已出院(4)或已取消(5)的就诊不可更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateVisitInfo(Long id, Map<String, Object> fields) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (fields == null || fields.isEmpty()) {
            throw new BizException(400, "更新字段不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(id);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() != null && (visit.getVisitStatus() == 4 || visit.getVisitStatus() == 5)) {
            throw new BizException("该就诊已出院/已取消, 不能更新在院信息");
        }
        LambdaUpdateWrapper<HisInpVisit> uw = new LambdaUpdateWrapper<HisInpVisit>()
                .eq(HisInpVisit::getId, id);
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            String sv = String.valueOf(v).trim();
            if (sv.isEmpty()) {
                continue;
            }
            switch (k) {
                case "nursingLevel":
                    uw.set(HisInpVisit::getNursingLevel, toInt(v));
                    break;
                case "dietType":
                    uw.set(HisInpVisit::getDietType, sv);
                    break;
                case "conditionLevel":
                    uw.set(HisInpVisit::getConditionLevel, toInt(v));
                    break;
                case "depositWarningAmount":
                    uw.set(HisInpVisit::getDepositWarningAmount, toDecimal(v));
                    break;
                case "contactName":
                    uw.set(HisInpVisit::getContactName, sv);
                    break;
                case "contactPhone":
                    uw.set(HisInpVisit::getContactPhone, sv);
                    break;
                case "contactRelation":
                    uw.set(HisInpVisit::getContactRelation, sv);
                    break;
                case "guarantorName":
                    uw.set(HisInpVisit::getGuarantorName, sv);
                    break;
                case "guarantorPhone":
                    uw.set(HisInpVisit::getGuarantorPhone, sv);
                    break;
                case "guarantorIdNo":
                    uw.set(HisInpVisit::getGuarantorIdNo, sv);
                    break;
                case "bloodType":
                    uw.set(HisInpVisit::getBloodType, sv);
                    break;
                case "admitSource":
                    uw.set(HisInpVisit::getAdmitSource, toInt(v));
                    break;
                case "isQuarantine":
                    uw.set(HisInpVisit::getIsQuarantine, toInt(v));
                    break;
                case "nurseId":
                    uw.set(HisInpVisit::getNurseId, toLong(v));
                    break;
                case "expectedDischargeDate":
                    try {
                        uw.set(HisInpVisit::getExpectedDischargeDate, LocalDate.parse(sv));
                    } catch (Exception ex) {
                        throw new BizException(400, "预计出院日期格式不正确(yyyy-MM-dd): " + sv);
                    }
                    break;
                default:
                    throw new BizException(400, "不支持的更新字段: " + k);
            }
            applied.add(k);
        }
        if (applied.isEmpty()) {
            throw new BizException(400, "无可更新的字段值");
        }
        visitMapper.update(null, uw);
        log.info("更新在院信息: visitId={}, fields={}", id, applied);
        return R.ok();
    }

    /* ================= 看板状态计数 ================= */
    
    /**
     * 各状态计数(看板复用): 在院总数/今日入院/今日出院/危重/特级护理/I级护理/欠费。
     * wardId/deptId 可选过滤(医生站传 deptId, 护士站传 wardId, 同时传则取交集)。
     * 口径: 今日入院=admit_date落在当日且非取消(2在院/3出院办理中/4已出院);
     * 今日出院=visit_status=4且discharge_date落在当日; 欠费=在院且deposit_balance<0。
     */
    public Map<String, Integer> countByStatus(Long wardId, Long deptId) {
        StringBuilder from = new StringBuilder(
                " FROM his_inp_visit v WHERE v.deleted = 0 AND v.tenant_id = ?");
        List<Object> scope = new ArrayList<>();
        scope.add(tenantId());
        if (wardId != null) {
            from.append(" AND v.ward_id = ?");
            scope.add(wardId);
        }
        if (deptId != null) {
            from.append(" AND v.dept_id = ?");
            scope.add(deptId);
        }
        String base = from.toString();
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("total", countVisits(base + " AND v.visit_status = 2", scope));
        out.put("todayAdmit", countVisits(base + " AND v.visit_status IN (2, 3, 4)"
                + " AND v.admit_date >= CURDATE() AND v.admit_date < CURDATE() + INTERVAL 1 DAY", scope));
        out.put("todayDischarge", countVisits(base + " AND v.visit_status = 4"
                + " AND v.discharge_date >= CURDATE() AND v.discharge_date < CURDATE() + INTERVAL 1 DAY", scope));
        out.put("critical", countVisits(base + " AND v.visit_status = 2 AND v.condition_level IN (1, 2)", scope));
        out.put("specialNursing", countVisits(base + " AND v.visit_status = 2 AND v.nursing_level = 1", scope));
        out.put("firstNursing", countVisits(base + " AND v.visit_status = 2 AND v.nursing_level = 2", scope));
        out.put("arrears", countVisits(base + " AND v.visit_status = 2 AND v.deposit_balance < 0", scope));
        return out;
    }
    
    /** 计数辅助: from 片段以 FROM 开头并以 WHERE 条件结尾, scope 为已包含 tenant_id 的公共参数。 */
    private int countVisits(String from, List<Object> scope) {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from, Long.class, scope.toArray());
        return n == null ? 0 : n.intValue();
    }
    
    /* ================= 工具 ================= */

    /** 数值入参转 Int(兼容 JSON 反序列化后的各类 Number 与字符串) */
    private static Integer toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            throw new BizException(400, "数值字段格式不正确: " + v);
        }
    }

    /** 数值入参转 Long */
    private static Long toLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            throw new BizException(400, "数值字段格式不正确: " + v);
        }
    }

    /** 数值入参转 BigDecimal */
    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            throw new BizException(400, "金额字段格式不正确: " + v);
        }
    }

    /**
     * 生成住院号: INP + yyyyMMdd + 4位序号(如 INP202609280001)。
     * 当日同机构查 MAX 续接, 无记录从 0001 开始; 内存计数防并发重号。
     */
    private String generateInpNo(Long orgId) {
        String date = LocalDate.now().format(INP_NO_DATE);
        String key = tenantId() + "|" + orgId + "|" + date;
        AtomicInteger seq = INP_NO_SEQS.computeIfAbsent(key,
                k -> new AtomicInteger(queryMaxInpNoSeq(orgId, date)));
        int next = seq.incrementAndGet();
        if (next > 9999) {
            // 单日超9999序号极小概率, 抛异常避免号段溢出重复
            throw new BizException("当日住院号序号已用尽, 请联系管理员处理");
        }
        return "INP" + date + String.format("%04d", next);
    }

    /** 当日同机构已有最大住院号流水(仅首次播种时查询) */
    private int queryMaxInpNoSeq(Long orgId, String date) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT IFNULL(MAX(CAST(SUBSTRING(inp_no, 12) AS UNSIGNED)), 0)"
                        + " FROM his_inp_visit WHERE org_id = ? AND inp_no LIKE ?"
                        + " AND deleted = 0 AND tenant_id = ?",
                Integer.class, orgId, "INP" + date + "%", tenantId());
        return maxSeq == null ? 0 : maxSeq;
    }

    /** 分页参数安全化 */
    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    /** 追加分页偏移参数(共用 WHERE 参数列表拷贝) */
    private static Object[] appendOffset(List<Object> args, long offset, long size) {
        List<Object> all = new ArrayList<>(args);
        all.add(offset);
        all.add(size);
        return all.toArray();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
