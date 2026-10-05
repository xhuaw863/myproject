package com.yb.hi.service.nurse;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.nurse.HisNurseExec;
import com.yb.hi.entity.nurse.HisPatientAllergy;
import com.yb.hi.entity.nurse.HisSkinTest;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.nurse.HisSkinTestMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 皮试服务(观察窗管理): 开始皮试 / 录入结果 / 皮试列表(含倒计时)。
 * 说明:
 * 1) 观察窗固定 20 分钟(observe_end = observe_start + 20min), 到窗须录入结果;
 * 2) 结果判定: 1阴性 2阳性 3可疑(任务口径; DDL 注释"3未做"以任务规格为准);
 *    阳性必填症状描述, 且自动写入患者过敏档案(allergen_type=drug, source=skin_test, source_id=皮试ID)
 *    并落通知医生时间(notify_doctor_time), 同名有效过敏原已存在时跳过防重复建档;
 * 3) 联动: 开始皮试 = 执行单开始(NurseExecService.startExec, 乐观锁 0->1);
 *    录入终态结果 = 执行单完成(finishExec, 1->2), 同医嘱单全完成回写 his_order.exec_status=2;
 * 4) 结果录入乐观锁: UPDATE ... WHERE result = 0, 冲突返回业务错误提示刷新。
 */
@Slf4j
@Service
public class SkinTestService {

    /** 皮试结果: 0观察中 1阴性 2阳性 3可疑 */
    public static final int RESULT_OBSERVING = 0;
    public static final int RESULT_NEGATIVE = 1;
    public static final int RESULT_POSITIVE = 2;
    public static final int RESULT_SUSPECT = 3;

    /** 观察窗时长(分钟) */
    private static final int OBSERVE_MINUTES = 20;

    private final HisSkinTestMapper skinTestMapper;
    private final NurseExecService nurseExecService;
    private final JdbcTemplate jdbcTemplate;

    public SkinTestService(HisSkinTestMapper skinTestMapper, NurseExecService nurseExecService,
                           JdbcTemplate jdbcTemplate) {
        this.skinTestMapper = skinTestMapper;
        this.nurseExecService = nurseExecService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 开始皮试 ================= */

    /**
     * 开始皮试(皮内注射): 创建皮试记录, observe_start=NOW, observe_end=NOW+20分钟, result=0观察中;
     * 同一执行单已有皮试记录则拒绝(防重复开窗); 联动执行单 0->1(开始执行)。
     */
    @Transactional
    public HisSkinTest createSkinTest(Long execId, Long drugId, String drugName, String testDose) {
        HisNurseExec exec = nurseExecService.requireExec(execId);
        nurseExecService.requireSameOrg(exec.getOrgId());
        if (!NurseExecService.TYPE_SKIN_TEST.equals(exec.getExecType())) {
            throw new BizException(400, "该执行单非皮试类医嘱, 不能开始皮试");
        }
        Integer st = exec.getExecStatus();
        if (st != null && (st == NurseExecService.ST_FINISHED || st == NurseExecService.ST_CANCELLED)) {
            throw new BizException(409, "该执行单已完成/已取消, 不能开始皮试");
        }
        if (!StringUtils.hasText(drugName)) {
            throw new BizException(400, "皮试药品名称不能为空");
        }
        // 幂等: 同执行单已有皮试记录直接拒绝(与工作台"待皮试"页签互补)
        Long existed = skinTestMapper.selectCount(Wrappers.<HisSkinTest>lambdaQuery()
                .eq(HisSkinTest::getExecId, execId));
        if (existed != null && existed > 0) {
            throw new BizException(400, "该执行单已有皮试记录, 请在观察中/已完成页签查看");
        }
        LocalDateTime now = LocalDateTime.now();
        HisSkinTest test = new HisSkinTest();
        test.setExecId(execId);
        test.setDrugId(drugId);
        test.setDrugName(drugName.trim());
        test.setTestDose(StringUtils.hasText(testDose) ? testDose.trim() : "0.1mL");
        test.setObserveStart(now);
        test.setObserveEnd(now.plusMinutes(OBSERVE_MINUTES));
        test.setResult(RESULT_OBSERVING);
        skinTestMapper.insert(test);
        // 联动: 皮内注射即开始执行(0->1); 已在执行中(1)则不重复流转
        if (exec.getExecStatus() != null && exec.getExecStatus() == NurseExecService.ST_PENDING) {
            nurseExecService.startExec(execId, NurseExecService.currentStaffId());
        }
        log.info("皮试开窗: execId={}, 药品={}, 剂量={}, 观察窗 {} ~ {}",
                execId, test.getDrugName(), test.getTestDose(), test.getObserveStart(), test.getObserveEnd());
        return skinTestMapper.selectById(test.getId());
    }

    /* ================= 录入结果 ================= */

    /**
     * 录入皮试结果(乐观锁 result 0->终态): 阳性(2)必填症状描述;
     * 阳性联动: 自动写入患者过敏档案 + 记录通知医生时间;
     * 终态联动: 执行单完成(未开始则先补开始, 保证台账闭环)。
     */
    @Transactional
    public Map<String, Object> recordResult(Long testId, Integer result, String resultDesc) {
        if (testId == null) {
            throw new BizException(400, "皮试记录ID不能为空");
        }
        if (result == null || (result != RESULT_NEGATIVE && result != RESULT_POSITIVE && result != RESULT_SUSPECT)) {
            throw new BizException(400, "皮试结果非法(仅支持 1阴性/2阳性/3可疑)");
        }
        HisSkinTest test = skinTestMapper.selectById(testId);
        if (test == null) {
            throw new BizException(404, "皮试记录不存在");
        }
        if (test.getResult() != null && test.getResult() != RESULT_OBSERVING) {
            throw new BizException(409, "该皮试已录入结果, 不能重复录入");
        }
        String desc = resultDesc == null ? null : resultDesc.trim();
        if (result == RESULT_POSITIVE && (desc == null || desc.isEmpty())) {
            throw new BizException(400, "阳性结果必须填写症状描述(局部反应等)");
        }
        // 乐观锁 result 0->终态; 阳性同时落通知医生时间
        int n = jdbcTemplate.update(
                "UPDATE his_skin_test SET result = ?, result_desc = ?,"
                        + " notify_doctor_time = CASE WHEN ? = 2 THEN NOW() ELSE notify_doctor_time END,"
                        + " update_time = NOW()"
                        + " WHERE id = ? AND result = 0 AND tenant_id = ? AND deleted = 0",
                result, (desc == null || desc.isEmpty()) ? null : desc, result, testId, tenantId());
        if (n == 0) {
            throw new BizException(409, "皮试结果已被录入(可能他人已操作), 请刷新后重试");
        }
        // 阳性联动: 自动写入过敏档案(同名有效过敏原已存在则跳过)
        boolean allergyWritten = false;
        boolean allergySkipped = false;
        if (result == RESULT_POSITIVE) {
            HisNurseExec exec = nurseExecService.requireExec(test.getExecId());
            if (exec.getPatientId() != null) {
                Long dup = countActiveAllergy(exec.getPatientId(), test.getDrugName());
                if (dup != null && dup > 0) {
                    allergySkipped = true;
                    log.info("皮试阳性但过敏档案已有同名过敏原, 跳过写入: patientId={}, allergen={}",
                            exec.getPatientId(), test.getDrugName());
                } else {
                    HisPatientAllergy allergy = new HisPatientAllergy();
                    allergy.setPatientId(exec.getPatientId());
                    allergy.setAllergenType("drug");
                    allergy.setAllergenName(test.getDrugName());
                    allergy.setSeverity("moderate");
                    allergy.setSource("skin_test");
                    allergy.setSourceId(testId);
                    allergy.setRecordTime(LocalDateTime.now());
                    allergy.setRecordBy(NurseExecService.currentStaffId());
                    allergy.setIsActive(1);
                    jdbcTemplate.update(
                            "INSERT INTO his_patient_allergy (tenant_id, patient_id, allergen_type, allergen_name,"
                                    + " severity, source, source_id, record_time, record_by, is_active, create_time)"
                                    + " VALUES (?, ?, 'drug', ?, 'moderate', 'skin_test', ?, NOW(), ?, 1, NOW())",
                            tenantId(), exec.getPatientId(), test.getDrugName(), testId,
                            NurseExecService.currentStaffId());
                    allergyWritten = true;
                    log.warn("皮试阳性已自动写入过敏档案: patientId={}, 过敏原={}, 皮试ID={}",
                            exec.getPatientId(), test.getDrugName(), testId);
                }
            }
        }
        // 终态联动: 执行单完成(先确保开始, 再完成; 患者反应带结果摘要)
        HisNurseExec exec = nurseExecService.requireExec(test.getExecId());
        String response = "皮试" + resultLabel(result) + (desc == null || desc.isEmpty() ? "" : ": " + desc);
        if (exec.getExecStatus() != null && exec.getExecStatus() == NurseExecService.ST_PENDING) {
            nurseExecService.startExec(test.getExecId(), NurseExecService.currentStaffId());
        }
        Map<String, Object> finishOut = null;
        if (exec.getExecStatus() != null && exec.getExecStatus() == NurseExecService.ST_RUNNING) {
            finishOut = nurseExecService.finishExec(test.getExecId(), NurseExecService.currentStaffId(), response);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("testId", testId);
        out.put("execId", test.getExecId());
        out.put("result", result);
        out.put("resultLabel", resultLabel(result));
        out.put("allergyWritten", allergyWritten);
        out.put("allergySkipped", allergySkipped);
        out.put("execStatus", finishOut == null ? exec.getExecStatus() : NurseExecService.ST_FINISHED);
        out.put("orderFinished", finishOut != null && Boolean.TRUE.equals(finishOut.get("orderFinished")));
        return out;
    }

    /* ================= 皮试列表(含倒计时) ================= */

    /**
     * 皮试分页(三页签): resultStatus 0待皮试(执行单在途且未开窗) / 1观察中(result=0) / 2已完成(终态结果);
     * 观察中行返回 remaining_seconds(距观察结束剩余秒, 前端据此渲染倒计时进度条)。
     */
    public Page<Map<String, Object>> listSkinTests(Long orgId, Integer resultStatus, long page, long size) {
        Long oid = requireOrg(orgId);
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.org_id = ? AND e.exec_type = 'skin_test'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (resultStatus != null) {
            if (resultStatus == 0) {
                where.append(" AND st.id IS NULL AND e.exec_status IN (0, 1)");
            } else if (resultStatus == 1) {
                where.append(" AND st.result = 0");
            } else if (resultStatus == 2) {
                where.append(" AND st.result IN (1, 2, 3)");
            } else {
                throw new BizException(400, "resultStatus 参数非法(仅支持 0待皮试/1观察中/2已完成)");
            }
        }
        String joins = " FROM his_nurse_exec e"
                + " JOIN his_visit v ON v.id = e.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_skin_test st ON st.exec_id = e.id AND st.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = e.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = e.order_id AND o.deleted = 0"
                + " LEFT JOIN his_order_item oi ON oi.id = e.order_item_id AND oi.deleted = 0"
                + " LEFT JOIN his_staff ns ON ns.id = e.exec_nurse_id AND ns.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT st.id AS testId, e.id AS execId, st.drug_name AS drugName, st.drug_id AS drugId, st.test_dose AS testDose,"
                        + " DATE_FORMAT(st.observe_start, '%Y-%m-%d %H:%i:%s') AS observeStart,"
                        + " DATE_FORMAT(st.observe_end, '%Y-%m-%d %H:%i:%s') AS observeEnd,"
                        + " st.result, st.result_desc AS resultDesc,"
                        + " DATE_FORMAT(st.notify_doctor_time, '%Y-%m-%d %H:%i:%s') AS notifyDoctorTime,"
                        + " DATE_FORMAT(st.doctor_confirm_time, '%Y-%m-%d %H:%i:%s') AS doctorConfirmTime,"
                        + " TIMESTAMPDIFF(SECOND, NOW(), st.observe_end) AS remainingSeconds,"
                        + " TIMESTAMPDIFF(SECOND, st.observe_start, NOW()) AS elapsedSeconds,"
                        + " e.exec_no AS execNo, e.exec_status AS execStatus, e.exec_nurse_id AS execNurseId,"
                        + " ns.staff_name AS nurseName,"
                        + " e.patient_id AS patientId, p.name AS patientName, p.patient_no AS patientNo,"
                        + " CASE p.gender WHEN '1' THEN '男' WHEN '2' THEN '女' ELSE IFNULL(p.gender, '-') END AS genderName,"
                        + " p.age, oi.item_name AS itemName, oi.spec, oi.quantity, oi.unit,"
                        + " o.order_no AS orderNo, o.dr_name AS doctorName, o.dept_name AS orderDeptName,"
                        + " DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i:%s') AS createTime"
                        + joins + where + " ORDER BY COALESCE(st.observe_start, e.create_time) ASC, e.id ASC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 内部工具 ================= */

    /** 同患者同过敏原的有效记录数(防重复建档) */
    private Long countActiveAllergy(Long patientId, String allergenName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_patient_allergy"
                        + " WHERE patient_id = ? AND allergen_name = ? AND is_active = 1 AND tenant_id = ? AND deleted = 0",
                Long.class, patientId, allergenName, tenantId());
    }

    public static String resultLabel(Integer result) {
        if (result == null) {
            return "-";
        }
        switch (result) {
            case RESULT_OBSERVING:
                return "观察中";
            case RESULT_NEGATIVE:
                return "阴性";
            case RESULT_POSITIVE:
                return "阳性";
            case RESULT_SUSPECT:
                return "可疑";
            default:
                return String.valueOf(result);
        }
    }

    private static Long requireOrg(Long orgId) {
        if (orgId != null) {
            return orgId;
        }
        com.yb.hi.framework.tenant.LoginUser u = com.yb.hi.framework.tenant.UserContext.get();
        if (u != null && u.getOrgId() != null) {
            return u.getOrgId();
        }
        throw new BizException(403, "当前账号未归属任何机构, 无法查询皮试数据");
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
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
