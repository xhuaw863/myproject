package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.entity.ris.HisRisConsult;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import com.yb.hi.mapper.ris.HisRisConsultMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * RIS 双阅/远程会诊服务(发起 -> 接单/答复 -> 完成闭环)。
 * 口径:
 * 1) 会诊类型: 1 双阅(机构内第二医师复核) / 2 远程会诊(跨机构, consult_org_id 指向会诊机构) / 3 科内讨论;
 * 2) 状态机: 0 待会诊 -> 1 已完成(答复时回填意见/同意标志/会诊医生/时间); 2 已取消;
 * 3) 双阅发起不指派具体医师(consult_doctor_id 空), listPending 对本科室全员可见可接单,
 *    答复时回填当前登录医师; 防重复: 同报告同类型存在待处理单时拒绝重复发起;
 * 4) 会诊号: HZ + yyyyMMdd + 4位序号, synchronized 内存序号 + 跨日 DB 回读当日最大序号兜底重启防撞号;
 * 5) 发起双阅回写 his_exam_report.double_read_flag=1, 远程会诊回写 consult_flag=1(报告列表角标)。
 */
@Slf4j
@Service
public class RisConsultService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 会诊单号前缀 */
    private static final String CONSULT_PREFIX = "HZ";

    private final HisRisConsultMapper consultMapper;
    private final HisExamReportMapper reportMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public RisConsultService(HisRisConsultMapper consultMapper,
                             HisExamReportMapper reportMapper,
                             JdbcTemplate jdbcTemplate) {
        this.consultMapper = consultMapper;
        this.reportMapper = reportMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 发起 ================= */

    /** 指派双阅(consult_type=1): 同报告待处理双阅防重复; 回写报告 double_read_flag=1。 */
    @Transactional(rollbackFor = Exception.class)
    public HisRisConsult requestDoubleRead(Long reportId) {
        return request(reportId, 1, null, null);
    }

    /** 远程会诊申请(consult_type=2): 会诊机构与原因必填; 回写报告 consult_flag=1。 */
    @Transactional(rollbackFor = Exception.class)
    public HisRisConsult requestRemoteConsult(Long reportId, Long consultOrgId, String reason) {
        if (consultOrgId == null) {
            throw new BizException(400, "会诊机构不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "会诊原因不能为空");
        }
        return request(reportId, 2, consultOrgId, reason.trim());
    }

    /** 发起公共实现: 校验报告 -> 同报告同类型待处理防重 -> 建单(状态0待会诊) -> 回写报告标志。 */
    private HisRisConsult request(Long reportId, int consultType, Long consultOrgId, String reason) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        Long dup = consultMapper.selectCount(Wrappers.<HisRisConsult>lambdaQuery()
                .eq(HisRisConsult::getReportId, reportId)
                .eq(HisRisConsult::getConsultType, consultType)
                .eq(HisRisConsult::getStatus, 0));
        if (dup != null && dup > 0) {
            throw new BizException(consultType == 1 ? "该报告已存在待处理的双阅任务, 请勿重复发起" : "该报告已存在待处理的远程会诊, 请勿重复发起");
        }
        HisRisConsult c = new HisRisConsult();
        c.setOrgId(report.getOrgId());
        c.setConsultNo(nextConsultNo());
        c.setReportId(reportId);
        c.setRequestDoctorId(currentStaffId());
        c.setRequestReason(reason);
        c.setConsultType(consultType);
        c.setConsultOrgId(consultOrgId);
        c.setStatus(0);
        consultMapper.insert(c);
        if (consultType == 1) {
            jdbcTemplate.update(
                    "UPDATE his_exam_report SET double_read_flag = 1, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ?",
                    reportId, tenantId());
        } else {
            jdbcTemplate.update(
                    "UPDATE his_exam_report SET consult_flag = 1, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ?",
                    reportId, tenantId());
        }
        log.info("RIS会诊发起: consultNo={}, reportId={}, type={}, consultOrgId={}",
                c.getConsultNo(), reportId, consultType, consultOrgId);
        return c;
    }

    /* ================= 答复 ================= */

    /**
     * 提交会诊意见: 乐观锁 status 0->1, 回填意见/同意标志/会诊医生(当前登录)/会诊时间。
     * agreeFlag: 1 同意原诊断 / 0 不同意(需在意见中说明)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRisConsult submitOpinion(Long consultId, String opinion, Integer agreeFlag) {
        if (consultId == null) {
            throw new BizException(400, "会诊记录ID不能为空");
        }
        if (!StringUtils.hasText(opinion)) {
            throw new BizException(400, "会诊意见不能为空");
        }
        HisRisConsult c = consultMapper.selectById(consultId);
        if (c == null) {
            throw new BizException(400, "会诊记录不存在");
        }
        if (c.getStatus() == null || c.getStatus() != 0) {
            throw new BizException("该会诊已处理或已取消, 无法重复答复");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_ris_consult SET consult_opinion = ?, agree_flag = ?, consult_doctor_id = ?,"
                        + " consult_time = NOW(), status = 1, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                opinion.trim(), agreeFlag == null ? 1 : agreeFlag, currentStaffId(),
                currentUserName(), consultId, tenantId());
        if (affected == 0) {
            throw new BizException("会诊答复失败(状态已变更), 请刷新后重试");
        }
        log.info("RIS会诊答复: consultNo={}, agreeFlag={}, doctorId={}",
                c.getConsultNo(), agreeFlag, currentStaffId());
        return consultMapper.selectById(consultId);
    }

    /* ================= 查询 ================= */

    /** 报告的会诊记录(按发起时间倒序)。 */
    public List<HisRisConsult> listByReport(Long reportId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        return consultMapper.selectList(Wrappers.<HisRisConsult>lambdaQuery()
                .eq(HisRisConsult::getReportId, reportId)
                .orderByDesc(HisRisConsult::getId));
    }

    /**
     * 待处理会诊任务: 未指派(consult_doctor_id 空, 科室可接单)或指派给本人, 状态0待会诊;
     * 附报告号/患者信息供工作台列表直显。
     */
    public List<java.util.Map<String, Object>> listPending(Long doctorId) {
        String sql = "SELECT c.id, c.consult_no AS consultNo, c.report_id AS reportId, c.consult_type AS consultType,"
                + " c.request_reason AS requestReason, c.consult_org_id AS consultOrgId,"
                + " c.request_doctor_id AS requestDoctorId,"
                + " DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i:%s') AS createTime,"
                + " r.report_no AS reportNo, r.report_type AS reportType, r.patient_id AS patientId,"
                + " p.name AS patientName, p.gender_name AS genderName, p.age,"
                + " rd.staff_name AS requestDoctorName"
                + " FROM his_ris_consult c"
                + " JOIN his_exam_report r ON r.id = c.report_id AND r.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = r.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_staff rd ON rd.id = c.request_doctor_id AND rd.deleted = 0"
                + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.status = 0"
                + " AND (c.consult_doctor_id IS NULL"
                + (doctorId == null ? ")" : " OR c.consult_doctor_id = ?)")
                + " ORDER BY c.id DESC LIMIT 100";
        return doctorId == null
                ? jdbcTemplate.queryForList(sql, tenantId())
                : jdbcTemplate.queryForList(sql, tenantId(), doctorId);
    }

    /* ================= 会诊单号 ================= */

    /** 会诊单号: HZ+yyyyMMdd+4位序号, synchronized 唯一, 跨日重置时DB回读当日最大序号兜底重启防撞号 */
    private synchronized String nextConsultNo() {
        String today = LocalDate.now().format(DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = CONSULT_PREFIX + today + String.format("%04d", seqNo);
        while (consultNoExists(no)) {
            seqNo++;
            no = CONSULT_PREFIX + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有单号最大序号(重启后防撞号; Mapper 查询经租户插件自动按当前租户过滤) */
    private int maxSeqFromDb(String today) {
        HisRisConsult one = consultMapper.selectOne(Wrappers.<HisRisConsult>lambdaQuery()
                .likeRight(HisRisConsult::getConsultNo, CONSULT_PREFIX + today)
                .orderByDesc(HisRisConsult::getConsultNo)
                .last("LIMIT 1"));
        if (one == null || one.getConsultNo() == null || one.getConsultNo().length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getConsultNo().substring(one.getConsultNo().length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean consultNoExists(String consultNo) {
        return consultMapper.selectCount(Wrappers.<HisRisConsult>lambdaQuery()
                .eq(HisRisConsult::getConsultNo, consultNo)) > 0;
    }

    /* ================= 辅助 ================= */

    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
