package com.yb.hi.service.inpatient;

import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 抗菌药物处方权到期/越级 只读扫描(住院医生站 T2 阶段2b)。
 * 口径(纯读, 不改数据): 在院患者的"长期未停"药品医嘱(order_type=1长期, order_category=1药品,
 * order_status IN(1,2,3) 即 新开/已审核/执行中 未停止) 且所开药品为抗菌药(his_drug_catalog.abx_grade 非空),
 * 命中以下任一情形即告警:
 *   expired      —— 开嘱医生对该抗菌分级的授权已过期(优先取 his_staff_rx_auth 按级有效期, 无明细回落 his_staff.rx_valid_until);
 *   soon         —— rx_valid_until 在 soonDays(默认30)天内到期;
 *   no-auth      —— 开嘱医生无抗菌处方权级别(antibiotic_level 空);
 *   under-level  —— 医生抗菌级别 < 药品抗菌分级(抗菌分级 HBCV08.50.029: 11非限制/12限制/13特殊使用,
 *                   与 staff.antibiotic_level 同码, 数值越大级别越高, 需 医生级别 ≥ 药品分级)。
 * JdbcTemplate 手写 SQL 显式带 tenant_id AND deleted=0(不走多租户插件)。
 */
@Slf4j
@Service
public class InpAbxReviewService {

    private final JdbcTemplate jdbcTemplate;

    public InpAbxReviewService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 抗菌到期/越级告警清单。
     *
     * @param staffId   可选: 限定开嘱医生(医生站默认查本人); 为空则查本租户全部医生
     * @param inpVisitId 可选: 限定某住院就诊
     * @param soonDays  有效期"即将到期"提前天数, 空或负回落 30
     */
    public List<Map<String, Object>> abxEpiryAlerts(Long staffId, Long inpVisitId, Integer soonDays) {
        int soon = (soonDays == null || soonDays < 0) ? 30 : soonDays;
        StringBuilder sql = new StringBuilder(
                "SELECT o.id AS orderId, o.inp_visit_id AS inpVisitId, v.patient_id AS patientId,"
                        + " p.name AS patientName, v.dept_id AS deptId, o.order_content AS orderContent,"
                        + " o.drug_id AS drugId, d.generic_name AS drugName,"
                        + " d.abx_grade AS abxGrade, d.abx_grade_name AS abxGradeName,"
                        + " o.doctor_id AS doctorId, s.staff_name AS doctorName,"
                        + " s.antibiotic_level AS antibioticLevel, s.antibiotic_level_name AS antibioticLevelName,"
                        + " DATE_FORMAT(COALESCE((SELECT MAX(a.valid_until) FROM his_staff_rx_auth a WHERE a.deleted=0 AND a.tenant_id=o.tenant_id AND a.staff_id=s.id AND a.status=1 AND a.auth_kind='abx' AND CAST(a.auth_code AS UNSIGNED) >= CAST(d.abx_grade AS UNSIGNED)), s.rx_valid_until), '%Y-%m-%d') AS rxValidUntil,"
                        + " CASE"
                        + "   WHEN COALESCE((SELECT MAX(a.valid_until) FROM his_staff_rx_auth a WHERE a.deleted=0 AND a.tenant_id=o.tenant_id AND a.staff_id=s.id AND a.status=1 AND a.auth_kind='abx' AND CAST(a.auth_code AS UNSIGNED) >= CAST(d.abx_grade AS UNSIGNED)), s.rx_valid_until) IS NOT NULL AND COALESCE((SELECT MAX(a.valid_until) FROM his_staff_rx_auth a WHERE a.deleted=0 AND a.tenant_id=o.tenant_id AND a.staff_id=s.id AND a.status=1 AND a.auth_kind='abx' AND CAST(a.auth_code AS UNSIGNED) >= CAST(d.abx_grade AS UNSIGNED)), s.rx_valid_until) < CURDATE() THEN 'expired'"
                        + "   WHEN COALESCE((SELECT MAX(a.valid_until) FROM his_staff_rx_auth a WHERE a.deleted=0 AND a.tenant_id=o.tenant_id AND a.staff_id=s.id AND a.status=1 AND a.auth_kind='abx' AND CAST(a.auth_code AS UNSIGNED) >= CAST(d.abx_grade AS UNSIGNED)), s.rx_valid_until) IS NOT NULL AND COALESCE((SELECT MAX(a.valid_until) FROM his_staff_rx_auth a WHERE a.deleted=0 AND a.tenant_id=o.tenant_id AND a.staff_id=s.id AND a.status=1 AND a.auth_kind='abx' AND CAST(a.auth_code AS UNSIGNED) >= CAST(d.abx_grade AS UNSIGNED)), s.rx_valid_until) <= CURDATE() + INTERVAL ? DAY THEN 'soon'"
                        + "   WHEN s.antibiotic_level IS NULL OR s.antibiotic_level = '' THEN 'no-auth'"
                        + "   WHEN CAST(s.antibiotic_level AS UNSIGNED) < CAST(d.abx_grade AS UNSIGNED) THEN 'under-level'"
                        + "   ELSE NULL END AS alertType"
                        + " FROM his_inp_order o"
                        + " JOIN his_inp_visit v ON v.id = o.inp_visit_id AND v.deleted = 0 AND v.visit_status = 2"
                        + " JOIN his_drug_catalog d ON d.id = o.drug_id AND d.deleted = 0 AND d.abx_grade IS NOT NULL"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_staff s ON s.id = o.doctor_id AND s.deleted = 0"
                        + " WHERE o.deleted = 0 AND o.tenant_id = ? AND o.order_type = 1 AND o.order_category = 1"
                        + " AND o.order_status IN (1, 2, 3)");
        List<Object> args = new ArrayList<>();
        args.add(soon);
        args.add(tenantId());
        if (staffId != null) {
            sql.append(" AND o.doctor_id = ?");
            args.add(staffId);
        }
        if (inpVisitId != null) {
            sql.append(" AND o.inp_visit_id = ?");
            args.add(inpVisitId);
        }
        sql.append(" HAVING alertType IS NOT NULL"
                + " ORDER BY FIELD(alertType, 'expired', 'no-auth', 'under-level', 'soon'), o.id DESC"
                + " LIMIT 200");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 当前登录医生职工ID(供控制器默认按本人过滤)。 */
    public static Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }
}
