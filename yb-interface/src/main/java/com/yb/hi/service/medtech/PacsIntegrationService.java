package com.yb.hi.service.medtech;

import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SystemParamResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部影像 PACS/DICOMweb 集成·抽象层骨架(T2 阶段5-1)。
 * 现实边界: 本系统离线、无院外厂商端点, 故不做真 DICOM 解析; 提供可配置的"接入骨架":
 *  - mock 模式: 依据报告派生占位 Study 与内嵌查看器路由, 用于打通前端交互与验收;
 *  - dicomweb 模式: 依据系统参数 pacs.base_url / pacs.viewer_template 拼出外部 DICOMweb(WADO-RS) 查看器 URL,
 *    真实对接时仅需配置端点并保证报告已回填 pacs_study_uid(由影像设备/网关写入)。
 * 机构归属沿用 {@link OrgAccessGuard}: 仅可查看登录机构范围内报告(牵头机构可跨)。参数经四级解析器取值。
 */
@Slf4j
@Service
public class PacsIntegrationService {

    private final HisExamReportMapper reportMapper;
    private final OrgAccessGuard guard;
    private final SystemParamResolver paramResolver;

    public PacsIntegrationService(HisExamReportMapper reportMapper, OrgAccessGuard guard, SystemParamResolver paramResolver) {
        this.reportMapper = reportMapper;
        this.guard = guard;
        this.paramResolver = paramResolver;
    }

    /** PACS 集成配置视图(脱敏展示, 供前端提示与运维核对)。 */
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean enabled = enabled();
        String mode = mode();
        m.put("enabled", enabled ? 1 : 0);
        m.put("mode", mode);
        m.put("mock", "mock".equalsIgnoreCase(mode));
        m.put("baseUrlConfigured", StringUtils.hasText(param("pacs.base_url", "")));
        return m;
    }

    /**
     * 某报告的 DICOM Study 列表。仅影像类检查(reportType=exam)有影像; 检验(lab)返回空。
     * mock 模式派生单条占位 Study; dicomweb 模式亦返回同一挂接键(真实 Study 由外部 PACS 提供, 此处仅描述挂接)。
     */
    public List<Map<String, Object>> studies(Long reportId) {
        HisExamReport report = loadGuarded(reportId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!enabled() || isLab(report.getReportType())) {
            return out;
        }
        String uid = StringUtils.hasText(report.getPacsStudyUid())
                ? report.getPacsStudyUid()
                : mockStudyUid(report);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("studyUid", uid);
        s.put("studyDescription", StringUtils.hasText(report.getConclusion()) ? report.getConclusion() : "影像检查 " + report.getReportNo());
        s.put("modality", "OT");
        s.put("seriesCount", 1);
        s.put("pacsServer", report.getPacsServer());
        s.put("mock", "mock".equalsIgnoreCase(mode()) ? 1 : 0);
        out.add(s);
        return out;
    }

    /** 查看器 URL: 依据配置拼出(外部 DICOMweb)或内嵌 mock 路由; 未启用/非影像返回 null。 */
    public Map<String, Object> viewer(Long reportId) {
        HisExamReport report = loadGuarded(reportId);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean mock = "mock".equalsIgnoreCase(mode());
        out.put("enabled", enabled() ? 1 : 0);
        out.put("mode", mode());
        out.put("mock", mock ? 1 : 0);
        String url = null;
        if (enabled() && !isLab(report.getReportType())) {
            String uid = StringUtils.hasText(report.getPacsStudyUid()) ? report.getPacsStudyUid() : mockStudyUid(report);
            out.put("studyUid", uid);
            url = buildViewerUrl(uid);
        }
        out.put("viewerUrl", url);
        return out;
    }

    /** 依据模式与参数拼查看器 URL。 */
    private String buildViewerUrl(String uid) {
        String base = param("pacs.base_url", "");
        String tpl = param("pacs.viewer_template", "");
        if ("dicomweb".equalsIgnoreCase(mode()) && StringUtils.hasText(base)) {
            String t = StringUtils.hasText(tpl) ? tpl : (base.endsWith("/") ? base : base + "/") + "viewer?StudyInstanceUID={study}";
            return t.replace("{study}", uid).replace("{base}", base);
        }
        // mock: 站内占位路由(前端以 iframe/弹窗渲染示意)
        return "#/pacs-mock-viewer?studyUID=" + uid;
    }

    /** 载入报告并做机构范围守卫(镜像 ExamReportService 归属口径)。 */
    private HisExamReport loadGuarded(Long reportId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        Long scope = guard.scopeOrgId(report.getOrgId());
        if (scope == null || !scope.equals(report.getOrgId())) {
            throw new BizException(403, "无权访问该机构报告的外部影像");
        }
        return report;
    }

    /** 派生 mock DICOM StudyInstanceUID(1.2.840.113619. 前缀 + 报告ID, 仅示意)。 */
    private static String mockStudyUid(HisExamReport r) {
        return "1.2.840.113619.2." + r.getId();
    }

    private boolean enabled() {
        return !"0".equals(param("pacs.enabled", "1").trim());
    }

    private String mode() {
        String m = param("pacs.mode", "mock");
        return StringUtils.hasText(m) ? m.trim() : "mock";
    }

    private String param(String key, String def) {
        String v = paramResolver.resolve(key);
        return v == null ? def : v;
    }

    private static boolean isLab(String reportType) {
        return "lab".equalsIgnoreCase(reportType);
    }
}
