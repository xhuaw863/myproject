package com.yb.hi.service.ris;

import com.yb.hi.common.YbHttpClient;
import com.yb.hi.common.YbResponse;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 医保 45xx 系列报告批量上报服务
 * - 4501(A) 临床检查报告批量上报: examinfo(单行) + imageinfo(多行) + iteminfo(多行) + sampleinfo(多行, 检查类空)
 * - 4502(A) 临床检验报告批量上报: labinfo(单行) + iteminfo(检验结果明细多行) + sampleinfo(多行)
 * 45xx 报文字段为 snake_case(与 22xx/23xx 一致); 48xx 实时接口为 camelCase(见 {@link RisYbCloudService})。
 *
 * 结构边界(诚实): his_exam_report 的医保 4501/4502 补列(yb_upload_status/accession_no/exam_ccls 等)
 * 由 DictSchemaMigration.ensureRisTables 幂等落列, 实体未映射(共享实体不加列避免并行冲突),
 * 故本服务查询/回写统一走 JdbcTemplate 原生 SQL(显式 tenant_id + deleted=0, 插件不作用于原生语句)。
 * 医保网关统一走 YbHttpClient(交易日志 his_yb_txn_log 出站即落), mock 模式由 MockYbServer 受理。
 */
@Slf4j
@Service
public class RisYbUploadService {

    /** 医保上报状态: 未上报 */
    public static final int UP_ST_PENDING = 0;
    /** 医保上报状态: 已上报 */
    public static final int UP_ST_OK = 1;
    /** 医保上报状态: 上报失败(可重试) */
    public static final int UP_ST_FAIL = 2;

    private final YbHttpClient ybHttpClient;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard orgAccessGuard;

    public RisYbUploadService(YbHttpClient ybHttpClient, JdbcTemplate jdbcTemplate, OrgAccessGuard orgAccessGuard) {
        this.ybHttpClient = ybHttpClient;
        this.jdbcTemplate = jdbcTemplate;
        this.orgAccessGuard = orgAccessGuard;
    }

    /**
     * 【4501】临床检查报告批量上报(报告审核后 status=2 且未上报 yb_upload_status=0)
     * 机构范围: 牵头机构=医共体全部, 非牵头=锁定本机构({@link OrgAccessGuard#scopeOrgId})。
     * 行级独立上报: 单行失败不阻断其余行, 状态逐行回写(1成功/2失败)。
     *
     * @return {total, success, fail} 统计
     */
    public Map<String, Object> uploadExamReports() {
        return uploadExamReports(orgAccessGuard.scopeOrgId(null));
    }

    /** 4501(指定机构范围, 供定时任务/Controller 显式圈定; null=按登录身份圈定) */
    public Map<String, Object> uploadExamReports(Long orgId) {
        Long effOrgId = orgId != null ? orgId : orgAccessGuard.scopeOrgId(null);
        List<Map<String, Object>> rows = fetchPendingRows(effOrgId, "exam");
        int ok = 0;
        int fail = 0;
        for (Map<String, Object> row : rows) {
            try {
                String psnNo = str(row.get("psn_no"));
                // 规范表3: 交易输入含 psn_no 时 insuplc_admdvs 必填, 从患者参保记录回查(查不到由网关层告警)
                String insuplc = psnNo == null || psnNo.isEmpty() ? null : queryInsuplcAdmdvs(psnNo);

                Map<String, Object> input = new LinkedHashMap<>();
                input.put("examinfo", buildExamInfoNode(row));
                input.put("imageinfo", buildImageInfoNodes(row));
                input.put("iteminfo", buildItemInfoNodes(row));
                input.put("sampleinfo", new ArrayList<>()); // 检查类无标本, 空集合表示无
                YbResponse resp = ybHttpClient.call("4501", input, insuplc);
                if (resp.isSuccess()) {
                    markUploaded(row, UP_ST_OK);
                    ok++;
                } else {
                    log.warn("4501检查报告上报失败: reportNo={}, errMsg={}", str(row.get("report_no")), resp.getErrMsg());
                    markUploaded(row, UP_ST_FAIL);
                    fail++;
                }
            } catch (Exception e) {
                log.error("4501检查报告上报异常: reportNo={}", str(row.get("report_no")), e);
                markUploaded(row, UP_ST_FAIL);
                fail++;
            }
        }
        log.info("4501检查报告批量上报完成: 范围org={}, 待报{}行, 成功{}行, 失败{}行", effOrgId, rows.size(), ok, fail);
        return statResult(rows.size(), ok, fail);
    }

    /**
     * 【4502】临床检验报告批量上报(结构同 4501, 主节点 labinfo, 明细取 his_exam_result_item 的 4502 补列)
     *
     * @return {total, success, fail} 统计
     */
    public Map<String, Object> uploadLabReports() {
        return uploadLabReports(orgAccessGuard.scopeOrgId(null));
    }

    /** 4502(指定机构范围) */
    public Map<String, Object> uploadLabReports(Long orgId) {
        Long effOrgId = orgId != null ? orgId : orgAccessGuard.scopeOrgId(null);
        List<Map<String, Object>> rows = fetchPendingRows(effOrgId, "lab");
        int ok = 0;
        int fail = 0;
        for (Map<String, Object> row : rows) {
            try {
                String psnNo = str(row.get("psn_no"));
                String insuplc = psnNo == null || psnNo.isEmpty() ? null : queryInsuplcAdmdvs(psnNo);

                Map<String, Object> input = new LinkedHashMap<>();
                input.put("labinfo", buildLabInfoNode(row));
                input.put("iteminfo", fetchLabDetailNodes(row));
                input.put("sampleinfo", new ArrayList<>());
                YbResponse resp = ybHttpClient.call("4502", input, insuplc);
                if (resp.isSuccess()) {
                    markUploaded(row, UP_ST_OK);
                    ok++;
                } else {
                    log.warn("4502检验报告上报失败: reportNo={}, errMsg={}", str(row.get("report_no")), resp.getErrMsg());
                    markUploaded(row, UP_ST_FAIL);
                    fail++;
                }
            } catch (Exception e) {
                log.error("4502检验报告上报异常: reportNo={}", str(row.get("report_no")), e);
                markUploaded(row, UP_ST_FAIL);
                fail++;
            }
        }
        log.info("4502检验报告批量上报完成: 范围org={}, 待报{}行, 成功{}行, 失败{}行", effOrgId, rows.size(), ok, fail);
        return statResult(rows.size(), ok, fail);
    }

    /** 重试上报失败的记录(yb_upload_status=2 重置回待上报后重跑两通道) */
    public Map<String, Object> retryFailed() {
        Long effOrgId = orgAccessGuard.scopeOrgId(null);
        Long tenantId = currentTenantId();
        int resetExam = jdbcTemplate.update(
                "UPDATE his_exam_report SET yb_upload_status = " + UP_ST_PENDING
                        + " WHERE tenant_id = ? AND deleted = 0 AND yb_upload_status = " + UP_ST_FAIL
                        + " AND status = 2 AND report_type = 'exam'"
                        + (effOrgId != null ? " AND org_id = ?" : ""),
                effOrgId != null ? new Object[]{tenantId, effOrgId} : new Object[]{tenantId});
        int resetLab = jdbcTemplate.update(
                "UPDATE his_exam_report SET yb_upload_status = " + UP_ST_PENDING
                        + " WHERE tenant_id = ? AND deleted = 0 AND yb_upload_status = " + UP_ST_FAIL
                        + " AND status = 2 AND report_type = 'lab'"
                        + (effOrgId != null ? " AND org_id = ?" : ""),
                effOrgId != null ? new Object[]{tenantId, effOrgId} : new Object[]{tenantId});
        log.info("450x失败重试: 重置检查{}行/检验{}行回待上报", resetExam, resetLab);
        Map<String, Object> result = uploadExamReports(effOrgId);
        result.putAll(uploadLabReports(effOrgId));
        result.put("resetExam", resetExam);
        result.put("resetLab", resetLab);
        return result;
    }

    /**
     * 构建 4501 examinfo 节点(单行, snake_case, 字段严格按任务映射:
     * appy_no=申请单号, rpotc_no=报告单号, exam_part=部位, bilg_dr_codg=申请医生代码)
     */
    public Map<String, Object> buildExamInfoNode(Map<String, Object> row) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("mdtrt_sn", str(row.get("mdtrt_sn")));
        node.put("mdtrt_id", str(row.get("mdtrt_id")));
        node.put("psn_no", str(row.get("psn_no")));
        node.put("appy_no", str(row.get("request_no")));
        node.put("rpotc_no", str(row.get("report_no")));
        node.put("rpotc_type_code", str(row.get("rpotc_type_code")));
        node.put("exam_rpotc_name", str(row.get("exam_rpotc_name")));
        node.put("exam_date", dateStr(row.get("exam_date")));
        node.put("rpt_date", dateStr(row.get("rpt_date")));
        node.put("exam_type_code", str(row.get("exam_type_code")));
        node.put("exam_item_code", str(row.get("exam_item_code")));
        node.put("exam_item_name", str(row.get("exam_item_name")));
        node.put("inhosp_exam_item_code", str(row.get("inhosp_exam_item_code")));
        node.put("inhosp_exam_item_name", str(row.get("inhosp_exam_item_name")));
        node.put("exam_part", str(row.get("body_part")));
        node.put("exam_rslt_poit_flag", str(row.get("exam_rslt_poit_flag")));
        node.put("exam_rslt_abn", str(row.get("exam_rslt_abn")));
        node.put("exam_ccls", str(row.get("exam_ccls")));
        node.put("appy_dept_code", str(row.get("apply_dept_code")));
        node.put("exam_dept_code", str(row.get("target_dept_code")));
        node.put("bilg_dr_codg", str(row.get("apply_doctor_code")));
        node.put("bilg_dr_name", str(row.get("apply_doctor_name")));
        node.put("exe_org_name", str(row.get("exe_org_name")));
        node.put("vali_flag", emptyToDefault(str(row.get("vali_flag")), "1"));
        return node;
    }

    /**
     * 构建 4501 imageinfo 节点(多行, 当前每报告一条影像记录):
     * study_uid=pacs_study_uid, patient_id=检查号(accession_no), study_time=检查完成时间
     */
    public Map<String, Object> buildImageInfoNode(Map<String, Object> row) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rpotc_no", str(row.get("report_no")));
        node.put("study_uid", firstNonEmpty(str(row.get("pacs_study_uid")), str(row.get("exec_study_uid"))));
        node.put("patient_id", str(row.get("accession_no")));
        node.put("patient_name", str(row.get("patient_name")));
        node.put("acession_no", str(row.get("accession_no"))); // 规范字段名即此拼写(单 s)
        node.put("study_time", timeStr(row.get("exam_end_time")));
        node.put("modality", firstNonEmpty(str(row.get("modality")), str(row.get("req_modality"))));
        node.put("store_path", str(row.get("store_path")));
        node.put("series_count", row.get("series_count") == null ? 0 : row.get("series_count"));
        node.put("image_count", row.get("image_count") == null ? 0 : row.get("image_count"));
        return node;
    }

    /** imageinfo 多行(当前 1 报告 1 影像记录, 预留多 Study 扩展) */
    public List<Map<String, Object>> buildImageInfoNodes(Map<String, Object> row) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(buildImageInfoNode(row));
        return nodes;
    }

    /** 构建 4501 iteminfo 节点(收费项目维度多行) */
    public List<Map<String, Object>> buildItemInfoNodes(Map<String, Object> row) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("appy_no", str(row.get("request_no")));
        node.put("rpotc_no", str(row.get("report_no")));
        node.put("exam_item_code", str(row.get("exam_item_code")));
        node.put("exam_item_name", str(row.get("exam_item_name")));
        node.put("inhosp_exam_item_code", str(row.get("inhosp_exam_item_code")));
        node.put("inhosp_exam_item_name", str(row.get("inhosp_exam_item_name")));
        node.put("exam_charge", row.get("exam_charge") == null ? "0.00" : String.valueOf(row.get("exam_charge")));
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node);
        return nodes;
    }

    /** 构建 4502 labinfo 节点(检验主行, 语义同 examinfo 报告字段) */
    public Map<String, Object> buildLabInfoNode(Map<String, Object> row) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("mdtrt_sn", str(row.get("mdtrt_sn")));
        node.put("mdtrt_id", str(row.get("mdtrt_id")));
        node.put("psn_no", str(row.get("psn_no")));
        node.put("appy_no", str(row.get("request_no")));
        node.put("rpotc_no", str(row.get("report_no")));
        node.put("rpotc_type_code", str(row.get("rpotc_type_code")));
        node.put("lab_rpotc_name", firstNonEmpty(str(row.get("exam_rpotc_name")), "临床检验报告单"));
        node.put("exam_date", dateStr(row.get("exam_date")));
        node.put("rpt_date", dateStr(row.get("rpt_date")));
        node.put("exam_item_code", str(row.get("exam_item_code")));
        node.put("exam_item_name", str(row.get("exam_item_name")));
        node.put("inhosp_exam_item_code", str(row.get("inhosp_exam_item_code")));
        node.put("inhosp_exam_item_name", str(row.get("inhosp_exam_item_name")));
        node.put("exam_rslt_poit_flag", str(row.get("exam_rslt_poit_flag")));
        node.put("exam_rslt_abn", str(row.get("exam_rslt_abn")));
        node.put("exam_ccls", str(row.get("exam_ccls")));
        node.put("appy_dept_code", str(row.get("apply_dept_code")));
        node.put("lab_dodg", str(row.get("target_dept_code"))); // 检验科室代码
        node.put("bilg_dr_codg", str(row.get("apply_doctor_code")));
        node.put("bilg_dr_name", str(row.get("apply_doctor_name")));
        node.put("rpot_doc", str(row.get("rpot_doc")));
        node.put("exe_org_name", str(row.get("exe_org_name")));
        node.put("vali_flag", emptyToDefault(str(row.get("vali_flag")), "1"));
        return node;
    }

    // ---------- 内部实现 ----------

    /** 查待上报行(报告×申请单×患者×执行 LEFT JOIN, 医保4501字段在申请侧落列) */
    private List<Map<String, Object>> fetchPendingRows(Long orgId, String reportType) {
        Long tenantId = currentTenantId();
        String sql = "SELECT r.id AS rpt_id, r.report_no, r.report_type, r.pacs_study_uid, r.accession_no, r.modality,"
                + " r.exam_date, r.rpt_date, r.exam_ccls, r.exam_rslt_poit_flag, r.exam_rslt_abn,"
                + " r.rpotc_type_code, r.exam_rpotc_name, r.store_path, r.body_part, r.rpot_doc, r.critical_flag,"
                + " q.id AS req_id, q.request_no, q.mdtrt_sn, q.mdtrt_id, q.psn_no, q.exam_type_code,"
                + " q.exam_item_code, q.exam_item_name, q.inhosp_exam_item_code, q.inhosp_exam_item_name,"
                + " q.body_part AS req_body_part, q.exam_charge, q.img_exam_type, q.vali_flag,"
                + " q.apply_doctor_code, q.apply_doctor_name, q.apply_dept_code, q.target_dept_code, q.exe_org_name,"
                + " q.modality AS req_modality,"
                + " p.name AS patient_name,"
                + " e.image_count, e.series_count, e.study_uid AS exec_study_uid, e.exam_end_time"
                + " FROM his_exam_report r"
                + " JOIN his_exam_request q ON r.request_id = q.id"
                + " LEFT JOIN his_patient p ON q.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_exam_execution e ON e.request_id = q.id AND e.deleted = 0"
                + " WHERE r.tenant_id = ? AND r.deleted = 0"
                + " AND r.yb_upload_status = " + UP_ST_PENDING + " AND r.status = 2 AND r.report_type = ?"
                + (orgId != null ? " AND r.org_id = ?" : "")
                + " ORDER BY r.id LIMIT 500";
        return orgId != null
                ? jdbcTemplate.queryForList(sql, tenantId, reportType, orgId)
                : jdbcTemplate.queryForList(sql, tenantId, reportType);
    }

    /** 4502 检验结果明细节点(his_exam_result_item 的 4502 补列) */
    private List<Map<String, Object>> fetchLabDetailNodes(Map<String, Object> row) {
        Long rptId = ((Number) row.get("rpt_id")).longValue();
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT exam_item_detl_code, exam_item_detl_name, exam_unt, exam_rslt_val, exam_rslt_dicm,"
                        + " exam_mtd, ref_val, exam_rslt_abn"
                        + " FROM his_exam_result_item WHERE report_id = ? AND deleted = 0 ORDER BY id", rptId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("appy_no", str(row.get("request_no")));
            node.put("rpotc_no", str(row.get("report_no")));
            node.put("exam_item_detl_code", str(it.get("exam_item_detl_code")));
            node.put("exam_item_detl_name", firstNonEmpty(str(it.get("exam_item_detl_name")), str(it.get("item_name"))));
            node.put("exam_unt", str(it.get("exam_unt")));
            node.put("exam_rslt_val", it.get("exam_rslt_val") == null ? null : String.valueOf(it.get("exam_rslt_val")));
            node.put("exam_rslt_dicm", firstNonEmpty(str(it.get("exam_rslt_dicm")), str(it.get("result_value"))));
            node.put("exam_mtd", str(it.get("exam_mtd")));
            node.put("ref_val", firstNonEmpty(str(it.get("ref_val")), str(it.get("result_value"))));
            node.put("exam_rslt_abn", str(it.get("exam_rslt_abn")));
            nodes.add(node);
        }
        return nodes;
    }

    /** 回写上报状态与时间 */
    private void markUploaded(Map<String, Object> row, int status) {
        jdbcTemplate.update("UPDATE his_exam_report SET yb_upload_status = ?, yb_upload_time = NOW() WHERE id = ?",
                status, ((Number) row.get("rpt_id")).longValue());
    }

    /** 患者参保地区划(规范表3): 取该 psn_no 最新参保记录 */
    private String queryInsuplcAdmdvs(String psnNo) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT insuplc_admdvs FROM his_patient_insu WHERE psn_no = ? AND deleted = 0"
                            + " ORDER BY id DESC LIMIT 1", psnNo);
            return rows.isEmpty() ? null : str(rows.get(0).get("insuplc_admdvs"));
        } catch (Exception e) {
            log.warn("查询参保地区划失败(不阻断上报): psnNo={}, 原因: {}", psnNo, e.getMessage());
            return null;
        }
    }

    private Long currentTenantId() {
        com.yb.hi.framework.tenant.LoginUser lu = com.yb.hi.framework.tenant.UserContext.get();
        return lu == null ? null : lu.getTenantId();
    }

    private static Map<String, Object> statResult(int total, int ok, int fail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", ok);
        result.put("fail", fail);
        return result;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static String emptyToDefault(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** 日期列转 yyyy-MM-dd 报文格式(DATE/TIMESTAMP 兼容) */
    private static String dateStr(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    /** 时间列转 yyyy-MM-dd HH:mm:ss 报文格式 */
    private static String timeStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
