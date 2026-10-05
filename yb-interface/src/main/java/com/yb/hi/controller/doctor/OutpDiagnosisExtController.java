package com.yb.hi.controller.doctor;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.doctor.HisDiseaseReport;
import com.yb.hi.entity.doctor.HisDiseaseReportDetail;
import com.yb.hi.entity.doctor.HisDiseaseReportSkip;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.doctor.DiseaseReportDict;
import com.yb.hi.service.doctor.HisDiagFreqService;
import com.yb.hi.service.doctor.HisDiagTemplateLinkService;
import com.yb.hi.service.doctor.HisDiseaseReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门诊医生站诊断进阶接口(OP-B): 诊断助手聚合 / 疾病报卡 / 诊断→医嘱处方模板关联。
 */
@RestController
@RequestMapping("/api/his")
public class OutpDiagnosisExtController {

    private final HisDiagFreqService diagFreqService;
    private final HisDiseaseReportService diseaseReportService;
    private final HisDiagTemplateLinkService diagTemplateLinkService;
    private final DiseaseReportDict diseaseReportDict;

    public OutpDiagnosisExtController(HisDiagFreqService diagFreqService,
                                      HisDiseaseReportService diseaseReportService,
                                      HisDiagTemplateLinkService diagTemplateLinkService,
                                      DiseaseReportDict diseaseReportDict) {
        this.diagFreqService = diagFreqService;
        this.diseaseReportService = diseaseReportService;
        this.diagTemplateLinkService = diagTemplateLinkService;
        this.diseaseReportDict = diseaseReportDict;
    }

    /* ---------- 诊断助手 ---------- */

    /** 诊断助手: 聚合患者历史诊断 / 本科室高频 / 个人常用三类候选 */
    @GetMapping("/diagnosis/assistant")
    public R<Map<String, Object>> assistant(@RequestParam(required = false) Long patientId,
                                             @RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) Long staffId,
                                             @RequestParam(defaultValue = "15") int limit) {
        LoginUser user = UserContext.get();
        if (deptId == null && user != null) {
            deptId = user.getDeptId();
        }
        if (staffId == null && user != null) {
            staffId = user.getStaffId();
        }
        return R.ok(diagFreqService.assistant(patientId, deptId, staffId, limit));
    }

    /* ---------- 疾病报卡 ---------- */

    /** 报卡值域字典(报卡大类/精障6病/ICD-O-3部位形态学/诊断依据/危险等级等各枚举), 前端一次性拉取缓存 */
    @GetMapping("/disease-report/dicts")
    public R<Map<String, Object>> reportDicts() {
        return R.ok(diseaseReportDict.dicts());
    }

    /** 患者区自动带出预览(按 patientId 返回档案非空字段) */
    @GetMapping("/disease-report/patient-fill")
    public R<Map<String, Object>> patientFill(@RequestParam Long patientId) {
        return R.ok(diseaseReportService.patientSection(patientId));
    }

    @PostMapping("/disease-report")
    public R<HisDiseaseReport> createReport(@RequestBody HisDiseaseReport report) {
        return R.ok(diseaseReportService.create(report));
    }

    @GetMapping("/disease-report/list")
    public R<List<HisDiseaseReport>> listReport(@RequestParam Long visitId) {
        return R.ok(diseaseReportService.listByVisit(visitId));
    }

    /** 报卡明细(含 form_data 回显) */
    @GetMapping("/disease-report/detail")
    public R<HisDiseaseReportDetail> reportDetail(@RequestParam Long reportId) {
        return R.ok(diseaseReportService.detailOf(reportId));
    }

    /** 状态机: 标记已报 / 审核通过 / 退回(携原因) */
    @PostMapping("/disease-report/{id}/report")
    public R<HisDiseaseReport> markReported(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return R.ok(diseaseReportService.markReported(id));
    }

    @PostMapping("/disease-report/{id}/audit")
    public R<HisDiseaseReport> audit(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return R.ok(diseaseReportService.audit(id));
    }

    @PostMapping("/disease-report/{id}/return")
    public R<HisDiseaseReport> returnCard(@org.springframework.web.bind.annotation.PathVariable Long id,
                                          @RequestParam(required = false) String reason) {
        requireAdminOrSuper();
        return R.ok(diseaseReportService.returnCard(id, reason));
    }

    /* ---------- 报卡全流程: 触发判定 / 漏报留痕 / 集中审核 ---------- */

    /** 触发判定: 某就诊已存诊断中命中法定报卡的清单(供前端保存诊断后主动弹出) */
    @GetMapping("/disease-report/check-trigger")
    public R<List<Map<String, Object>>> checkTrigger(@RequestParam Long visitId) {
        return R.ok(diseaseReportService.checkTrigger(visitId));
    }

    /** 暂不报卡留痕(应报未报, 计入漏报监控) */
    @PostMapping("/disease-report/skip")
    public R<HisDiseaseReportSkip> saveSkip(@RequestBody HisDiseaseReportSkip skip) {
        return R.ok(diseaseReportService.saveSkip(skip));
    }

    /** 审核工作台分页(需 ADMIN/SUPER_ADMIN): cats 逗号分隔, from/to=yyyy-MM-dd */
    @GetMapping("/disease-report/page")
    public R<IPage<HisDiseaseReport>> page(@RequestParam(required = false) String cats,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) String reporter,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(defaultValue = "1") long pageNo,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        requireAdminOrSuper();
        return R.ok(diseaseReportService.auditPage(parseCats(cats), status, reporter, keyword, dayStart(from), dayEnd(to), pageNo, pageSize));
    }

    /** 审核统计卡片数据 */
    @GetMapping("/disease-report/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) String cats,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        requireAdminOrSuper();
        return R.ok(diseaseReportService.stats(parseCats(cats), dayStart(from), dayEnd(to)));
    }

    /** 按国标固定列导出报卡 CSV(返回文本, 前端 Blob 下载) */
    @GetMapping("/disease-report/export")
    public R<String> export(@RequestParam(required = false) Integer cat,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        requireAdminOrSuper();
        return R.ok(diseaseReportService.exportCsv(cat, dayStart(from), dayEnd(to)));
    }

    /* ---------- 诊断→医嘱/处方模板关联 ---------- */

    @GetMapping("/diag-template-link")
    public R<List<Map<String, Object>>> diagTemplateLink(@RequestParam String diagCode,
                                                         @RequestParam(required = false) Long deptId) {
        LoginUser user = UserContext.get();
        if (deptId == null && user != null) {
            deptId = user.getDeptId();
        }
        return R.ok(diagTemplateLinkService.listByDiag(diagCode, deptId));
    }

    /* ---------- 内部: 审核守卫与参数解析 ---------- */

    /** 集中审核类接口守卫: 仅放行 ADMIN/SUPER_ADMIN(照逐控制器自实现范式, 不改共享 OrgAccessGuard) */
    private void requireAdminOrSuper() {
        LoginUser lu = UserContext.get();
        if (lu == null || !lu.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "需报告卡审核权限(ADMIN/SUPER_ADMIN)");
        }
    }

    private List<Integer> parseCats(String cats) {
        List<Integer> list = new ArrayList<>();
        if (cats == null || cats.trim().isEmpty()) {
            return list;
        }
        for (String s : cats.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                try {
                    list.add(Integer.parseInt(t));
                } catch (NumberFormatException ignore) {
                    // 非法类别码跳过
                }
            }
        }
        return list;
    }

    private LocalDateTime dayStart(String d) {
        LocalDate ld = parseDate(d);
        return ld == null ? null : ld.atStartOfDay();
    }

    private LocalDateTime dayEnd(String d) {
        LocalDate ld = parseDate(d);
        return ld == null ? null : ld.atTime(23, 59, 59);
    }

    private LocalDate parseDate(String d) {
        if (d == null || d.trim().isEmpty()) {
            return null;
        }
        String s = d.trim();
        if (s.length() > 10) {
            s = s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
