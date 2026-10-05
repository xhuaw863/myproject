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
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public PacsIntegrationService(HisExamReportMapper reportMapper, OrgAccessGuard guard,
                                  SystemParamResolver paramResolver,
                                  org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.reportMapper = reportMapper;
        this.guard = guard;
        this.paramResolver = paramResolver;
        this.jdbcTemplate = jdbcTemplate;
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

    /* ================= DICOMweb(WADO-RS) 风格查询接口(T3 任务书4, mock 实现) ================= */

    /**
     * WADO-RS Study 查询(mock): 按患者ID + 报告日期范围查询机构范围内影像报告,
     * 派生结构化 Study 列表(studyUid/studyDate/modality/description/seriesCount);
     * dicomweb 模式附 WADO-RS 检索 URL(真实对接时仅需配置端点)。
     * dateRange 入参格式: "2026-01-01~2026-10-05"(支持 ~ 或 至 分隔, 单日期表示当日)。
     */
    public List<Map<String, Object>> queryStudies(String patientId, String dateRange) {
        if (!StringUtils.hasText(patientId)) {
            throw new BizException(400, "患者ID不能为空");
        }
        Long pid = toLong(patientId.trim());
        if (pid == null) {
            throw new BizException(400, "患者ID格式不正确(需为数字ID)");
        }
        String[] range = parseDateRange(dateRange);
        StringBuilder sql = new StringBuilder(
                "SELECT id, report_no AS reportNo, pacs_study_uid AS pacsStudyUid, modality, body_part AS bodyPart,"
                        + " conclusion, report_type AS reportType,"
                        + " DATE_FORMAT(IFNULL(report_time, create_time), '%Y-%m-%d') AS studyDate"
                        + " FROM his_exam_report"
                        + " WHERE deleted = 0 AND tenant_id = ? AND patient_id = ? AND report_type = 'exam'");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId());
        args.add(pid);
        if (range[0] != null) {
            sql.append(" AND DATE(IFNULL(report_time, create_time)) >= ?");
            args.add(range[0]);
        }
        if (range[1] != null) {
            sql.append(" AND DATE(IFNULL(report_time, create_time)) <= ?");
            args.add(range[1]);
        }
        sql.append(" ORDER BY IFNULL(report_time, create_time) DESC, id DESC LIMIT 100");
        List<Map<String, Object>> reports = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        Long scope = guard.scopeOrgId(null);
        boolean dicomweb = "dicomweb".equalsIgnoreCase(mode());
        String base = param("pacs.base_url", "");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : reports) {
            Long reportId = toLong(r.get("id"));
            String uid = str(r.get("pacsStudyUid"));
            if (!StringUtils.hasText(uid)) {
                uid = "1.2.840.113619.2." + reportId;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("studyUid", uid);
            s.put("reportId", reportId);
            s.put("reportNo", str(r.get("reportNo")));
            s.put("studyDate", str(r.get("studyDate")));
            s.put("modality", StringUtils.hasText(str(r.get("modality"))) ? str(r.get("modality")) : "OT");
            s.put("bodyPart", str(r.get("bodyPart")));
            s.put("studyDescription", StringUtils.hasText(str(r.get("conclusion")))
                    ? str(r.get("conclusion")) : "影像检查 " + str(r.get("reportNo")));
            s.put("seriesCount", 2);
            s.put("mock", dicomweb ? 0 : 1);
            if (dicomweb && StringUtils.hasText(base)) {
                String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                s.put("wadoRsUrl", b + "/studies/" + uid);
            }
            // 机构可见性标注(牵头可跨, 非牵头仅本机构报告可见后仍可能越界查到, 此处按 scope 标注供前端过滤)
            s.put("scopeOrgId", scope);
            out.add(s);
        }
        return out;
    }

    /** 查询 Study 下的 Series 列表(mock 派生: 定位像/轴位/重建三序列, UID 由 StudyUID 派生)。 */
    public List<Map<String, Object>> querySeriesByStudy(String studyUid) {
        if (!StringUtils.hasText(studyUid)) {
            throw new BizException(400, "StudyUID不能为空");
        }
        String uid = studyUid.trim();
        String[][] defs = {
                {"1", "LOCALIZER", "定位像", "4"},
                {"2", "AXIAL", "轴位图像", "24"},
                {"3", "CORONAL RECON", "冠状位重建", "12"},
        };
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] d : defs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seriesUid", uid + "." + d[0]);
            s.put("seriesNumber", Integer.valueOf(d[0]));
            s.put("modality", "OT");
            s.put("seriesDescription", d[2]);
            s.put("instanceCount", Integer.valueOf(d[3]));
            out.add(s);
        }
        return out;
    }

    /** 查询 Series 下的 Instance 列表(mock 派生: instanceUid = seriesUid.i)。 */
    public List<Map<String, Object>> queryInstancesBySeries(String studyUid, String seriesUid) {
        if (!StringUtils.hasText(seriesUid)) {
            throw new BizException(400, "SeriesUID不能为空");
        }
        String su = seriesUid.trim();
        int count = 6;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("instanceUid", su + "." + i);
            ins.put("instanceNumber", i);
            ins.put("sopClassUid", "1.2.840.10008.5.1.4.1.1.2");
            ins.put("sopClassDescription", "CT Image Storage");
            ins.put("studyUid", StringUtils.hasText(studyUid) ? studyUid.trim() : null);
            ins.put("thumbnailUrl", getThumbnailUrl(studyUid, su, su + "." + i));
            out.add(ins);
        }
        return out;
    }

    /**
     * 缩略图 URL: dicomweb 模式拼 WADO-RS 渲染端点({base}/studies/{s}/series/{se}/instances/{i}/thumbnail),
     * mock 模式站内占位路由。
     */
    public String getThumbnailUrl(String studyUid, String seriesUid, String instanceUid) {
        String base = param("pacs.base_url", "");
        if ("dicomweb".equalsIgnoreCase(mode()) && StringUtils.hasText(base)) {
            String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            return b + "/studies/" + safeUid(studyUid) + "/series/" + safeUid(seriesUid)
                    + "/instances/" + safeUid(instanceUid) + "/thumbnail";
        }
        return "#/pacs-mock-thumb?study=" + safeUid(studyUid)
                + "&series=" + safeUid(seriesUid) + "&instance=" + safeUid(instanceUid);
    }

    /** 影像查看器 URL(复用既有查看器路由构建逻辑)。 */
    public String getViewerUrl(String studyUid) {
        if (!StringUtils.hasText(studyUid)) {
            throw new BizException(400, "StudyUID不能为空");
        }
        return buildViewerUrl(studyUid.trim());
    }

    /* ================= 辅助 ================= */

    /** 日期范围解析: 支持 "yyyy-MM-dd~yyyy-MM-dd" / "yyyy-MM-dd至yyyy-MM-dd" / 单日期; 返回 [from, to]。 */
    private static String[] parseDateRange(String dateRange) {
        String[] out = {null, null};
        if (!StringUtils.hasText(dateRange)) {
            return out;
        }
        String s = dateRange.trim();
        String[] parts = s.contains("~") ? s.split("~") : (s.contains("至") ? s.split("至") : new String[]{s});
        if (parts.length >= 2) {
            out[0] = normalizeDate(parts[0]);
            out[1] = normalizeDate(parts[1]);
        } else {
            out[0] = normalizeDate(parts[0]);
            out[1] = out[0];
        }
        return out;
    }

    private static String normalizeDate(String d) {
        if (!StringUtils.hasText(d)) {
            return null;
        }
        String s = d.trim().replace("/", "-");
        return s.matches("\\d{4}-\\d{2}-\\d{2}") ? s : null;
    }

    /** UID 防注入: 仅保留数字与点号。 */
    private static String safeUid(String uid) {
        if (!StringUtils.hasText(uid)) {
            return "";
        }
        return uid.trim().replaceAll("[^0-9.]", "");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
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

    private static long tenantId() {
        Long t = com.yb.hi.framework.tenant.TenantContext.get();
        return t == null ? 0L : t;
    }
}
