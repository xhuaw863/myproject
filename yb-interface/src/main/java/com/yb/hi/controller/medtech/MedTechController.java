package com.yb.hi.controller.medtech;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.medtech.HisCriticalRule;
import com.yb.hi.entity.medtech.HisCriticalValue;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.entity.medtech.HisSpecimen;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.medtech.CriticalValueService;
import com.yb.hi.service.medtech.ExamReportService;
import com.yb.hi.service.medtech.SpecimenService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 医技管理接口(标本/报告/危急值):
 * 读按当前登录机构隔离(org_id), 写操作人取登录用户关联职工(staffId, 可为空不阻断);
 * 状态流转全部为乐观锁 UPDATE ... WHERE status=前值, 冲突返回业务错误提示刷新。
 */
@RestController
@RequestMapping("/api/medtech")
public class MedTechController {

    private final SpecimenService specimenService;
    private final ExamReportService examReportService;
    private final CriticalValueService criticalValueService;

    public MedTechController(SpecimenService specimenService, ExamReportService examReportService,
                             CriticalValueService criticalValueService) {
        this.specimenService = specimenService;
        this.examReportService = examReportService;
        this.criticalValueService = criticalValueService;
    }

    /* ================= 标本 ================= */

    /** 标本分页(status 支持多值逗号分隔: 0待采集 / 1,2运送中 / 3已签收) */
    @GetMapping("/specimens")
    public R<IPage<Map<String, Object>>> listSpecimens(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(specimenService.listSpecimens(currentOrgId(), parseStatuses(status), page, size));
    }

    /** 从检验类医嘱生成标本(按标本类型+管色分组; 幂等, 已生成直接返回已有) */
    @PostMapping("/specimen/generate")
    public R<List<HisSpecimen>> generateSpecimens(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body == null ? null : body.get("orderId"));
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        return R.ok(specimenService.generateSpecimens(orderId));
    }

    /** 采集标本(乐观锁 0->1) */
    @PostMapping("/specimen/{barcode}/collect")
    public R<HisSpecimen> collectSpecimen(@PathVariable String barcode) {
        return R.ok(specimenService.collectSpecimen(barcode, currentStaffId()));
    }

    /** 批量采集(逐条乐观锁, 部分成功不回滚, 返回 {success, failed}) */
    @PostMapping("/specimen/batch-collect")
    public R<Map<String, Object>> batchCollect(@RequestBody Map<String, Object> body) {
        List<String> barcodes = new ArrayList<>();
        Object raw = body == null ? null : body.get("barcodes");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null) {
                    barcodes.add(o.toString());
                }
            }
        }
        return R.ok(specimenService.batchCollect(barcodes, currentStaffId()));
    }

    /** 签收标本(乐观锁 1->3) */
    @PostMapping("/specimen/{barcode}/receive")
    public R<HisSpecimen> receiveSpecimen(@PathVariable String barcode) {
        return R.ok(specimenService.receiveSpecimen(barcode, currentStaffId()));
    }

    /** 拒收标本(乐观锁 0/1 -> -1, 记录拒收原因) */
    @PostMapping("/specimen/{barcode}/reject")
    public R<HisSpecimen> rejectSpecimen(@PathVariable String barcode, @RequestBody Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? null : body.get("reason").toString();
        return R.ok(specimenService.rejectSpecimen(barcode, currentStaffId(), reason));
    }

    /* ================= 报告 ================= */

    /** 报告分页(reportType/status 可选; keyword 匹配患者姓名/报告单号/患者ID; from/to 过滤报告日期 yyyy-MM-dd) */
    @GetMapping("/reports")
    public R<IPage<Map<String, Object>>> listReports(
            @RequestParam(required = false) String reportType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(examReportService.listReports(currentOrgId(), reportType, status, keyword, from, to, page, size));
    }

    /** 按患者查询历史报告(排除作废; 供医生站) */
    @GetMapping("/reports/patient/{patientId}")
    public R<List<Map<String, Object>>> listReportsByPatient(
            @PathVariable Long patientId,
            @RequestParam(required = false) String reportType) {
        return R.ok(examReportService.listReportsByPatient(patientId, reportType));
    }

    /** 报告详情(含结果明细子项) */
    @GetMapping("/report/{id}")
    public R<Map<String, Object>> getReportDetail(@PathVariable Long id) {
        return R.ok(examReportService.getReportDetail(id));
    }

    /** 创建报告草稿(幂等: 该医嘱已有报告直接返回) */
    @PostMapping("/report/draft")
    public R<HisExamReport> createDraft(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body == null ? null : body.get("orderId"));
        String reportType = body == null || body.get("reportType") == null ? null : body.get("reportType").toString();
        return R.ok(examReportService.createDraft(orderId, reportType));
    }

    /** 保存报告草稿(仅草稿可保存; 检验类结果明细整体替换) */
    @PostMapping("/report/{id}/save")
    @SuppressWarnings("unchecked")
    public R<HisExamReport> saveDraft(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String findings = body == null || body.get("findings") == null ? null : body.get("findings").toString();
        String conclusion = body == null || body.get("conclusion") == null ? null : body.get("conclusion").toString();
        List<Map<String, Object>> resultItems = null;
        Object raw = body == null ? null : body.get("resultItems");
        if (raw instanceof List) {
            resultItems = (List<Map<String, Object>>) raw;
        }
        return R.ok(examReportService.saveDraft(id, findings, conclusion, resultItems));
    }

    /** 提交审核(乐观锁 0->1; 自动检测危急值, 返回 {reportId,reportNo,status,criticalFlag,criticals}) */
    @PostMapping("/report/{id}/submit")
    public R<Map<String, Object>> submitForReview(@PathVariable Long id) {
        return R.ok(examReportService.submitForReview(id, currentStaffId()));
    }

    /** 审核报告(通过 1->2 并回写医嘱执行状态 / 退回 1->0) */
    @PostMapping("/report/{id}/review")
    public R<HisExamReport> reviewReport(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean approved = body != null && Boolean.TRUE.equals(body.get("approved"));
        return R.ok(examReportService.reviewReport(id, currentStaffId(), approved));
    }

    /* ================= 危急值 ================= */

    /** 危急值分页(status: 0已发现 1已复核 2已通知 3已接收 4已处置) */
    @GetMapping("/critical-values")
    public R<IPage<Map<String, Object>>> listCriticalValues(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(criticalValueService.listCriticalValues(currentOrgId(), status, page, size));
    }

    /** 复核(0->1) */
    @PostMapping("/critical/{id}/verify")
    public R<HisCriticalValue> verifyCritical(@PathVariable Long id) {
        return R.ok(criticalValueService.verifyCritical(id, currentStaffId()));
    }

    /** 通知临床(1->2) */
    @PostMapping("/critical/{id}/notify")
    public R<HisCriticalValue> notifyClinical(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String target = body == null || body.get("target") == null ? null : body.get("target").toString();
        return R.ok(criticalValueService.notifyClinical(id, target));
    }

    /** 确认接收(2->3) */
    @PostMapping("/critical/{id}/confirm")
    public R<HisCriticalValue> confirmReceive(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String person = body == null || body.get("person") == null ? null : body.get("person").toString();
        return R.ok(criticalValueService.confirmReceive(id, person));
    }

    /** 记录处置(3->4) */
    @PostMapping("/critical/{id}/handle")
    public R<HisCriticalValue> recordHandle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String measures = body == null || body.get("measures") == null ? null : body.get("measures").toString();
        return R.ok(criticalValueService.recordHandle(id, measures));
    }

    /* ================= 危急值规则 ================= */

    /** 规则列表 */
    @GetMapping("/critical-rules")
    public R<List<HisCriticalRule>> listRules() {
        return R.ok(criticalValueService.listRules());
    }

    /** 新增规则 */
    @PostMapping("/critical-rules")
    public R<HisCriticalRule> createRule(@RequestBody HisCriticalRule rule) {
        return R.ok(criticalValueService.createRule(rule));
    }

    /** 编辑规则 */
    @PutMapping("/critical-rules/{id}")
    public R<HisCriticalRule> updateRule(@PathVariable Long id, @RequestBody HisCriticalRule rule) {
        return R.ok(criticalValueService.updateRule(id, rule));
    }

    /** 删除规则(物理删除) */
    @DeleteMapping("/critical-rules/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        criticalValueService.deleteRule(id);
        return R.ok(null);
    }

    /* ================= 辅助 ================= */

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作医技业务");
        }
        return u.getOrgId();
    }

    private Long currentStaffId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getStaffId();
    }

    /** status 参数解析: "1,2" -> [1,2]; 空/非法返回空列表(不加状态过滤) */
    private static List<Integer> parseStatuses(String status) {
        if (status == null || status.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> list = new ArrayList<>();
        for (String part : status.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                list.add(Integer.valueOf(s));
            } catch (NumberFormatException e) {
                throw new BizException(400, "status 参数非法: " + s);
            }
        }
        return list;
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
