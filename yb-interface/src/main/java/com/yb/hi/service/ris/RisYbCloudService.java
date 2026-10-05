package com.yb.hi.service.ris;

import com.yb.hi.common.YbHttpClient;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.ris.RisYbDuplicateCheckDTO;
import com.yb.hi.framework.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 医保影像云 48xx 实时接口服务
 * - 4801 获取影像索引查询页面 URL(患者/医生维度打开云端影像索引)
 * - 4802 上传预约影像信息(ordrRgstSn=院内申请单号)
 * - 4803 取消预约影像信息
 * - 4804 查询重复检查(开单拦截: 未配置规则 / 未查到重复 / 重复记录查看 URL 三态)
 *
 * 报文边界: 48xx 系列字段为 camelCase(与 45xx snake_case 严格区分), certno 例外保持小写全拼。
 * 查重规则未配置的口径: 影像检查类型映射不到医保字典(1-5)或患者实名信息不全, 无法构造 4804 查询入参。
 */
@Slf4j
@Service
public class RisYbCloudService {

    private final YbHttpClient ybHttpClient;
    private final JdbcTemplate jdbcTemplate;

    /** 4804 查重结果: 院内未配置查重规则(影像类型/实名信息缺失) */
    public static final String DUP_NO_RULE = "未配置规则";
    /** 4804 查重结果: 医保云未查到重复检查 */
    public static final String DUP_NOT_FOUND = "未查到重复";

    public RisYbCloudService(YbHttpClient ybHttpClient, JdbcTemplate jdbcTemplate) {
        this.ybHttpClient = ybHttpClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 【4801】获取影像索引查询页面 URL
     * 输入节点: data(camelCase: psnNo/psnName/certType/certno/drCode/drName); 输出: url。
     */
    public String getImageIndexPageUrl(String psnNo, String psnName, String certType, String certno,
                                       String drCode, String drName) {
        log.info("4801影像索引页查询: psnNo={}, drCode={}", psnNo, drCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psnNo", psnNo);
        data.put("psnName", psnName);
        data.put("certType", certType);
        data.put("certno", certno);
        data.put("drCode", drCode);
        data.put("drName", drName);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", data);
        YbResponse resp = ybHttpClient.call("4801", input);
        if (!resp.isSuccess()) {
            throw new BizException(500, "4801影像索引页查询失败: " + resp.getErrMsg());
        }
        String url = resp.getOutputObject().getString("url");
        if (url == null || url.isEmpty()) {
            throw new BizException(500, "医保平台未返回影像索引页面 URL(请核对 4801 接口配置)");
        }
        return url;
    }

    /**
     * 【4802】上传预约影像信息
     * 输入节点: data(camelCase); ordrRgstSn = 院内检查申请单号(requestNo)。
     */
    public YbResponse uploadAppointmentInfo(String requestNo, String psnName, String certType, String certno,
                                            String hospitalCode, String hospitalName, Date appointmentTime) {
        log.info("4802上传预约影像: ordrRgstSn={}, psnName={}, appointmentTime={}",
                requestNo, psnName, appointmentTime == null ? null : fmt(appointmentTime));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ordrRgstSn", requestNo);
        data.put("psnName", psnName);
        data.put("certType", certType);
        data.put("certno", certno);
        data.put("hospitalCode", hospitalCode);
        data.put("hospitalName", hospitalName);
        data.put("appointmentTime", appointmentTime == null ? null : fmt(appointmentTime));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", data);
        return ybHttpClient.call("4802", input);
    }

    /**
     * 【4803】取消预约影像信息
     * 输入节点: data(ordrRgstSn = 院内检查申请单号)。
     */
    public YbResponse cancelAppointment(String requestNo) {
        log.info("4803取消预约影像: ordrRgstSn={}", requestNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ordrRgstSn", requestNo);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", data);
        return ybHttpClient.call("4803", input);
    }

    /**
     * 【4804】查询重复检查(开单前拦截)
     * 输入节点: data(psnName/psnCertType/certno/imgExamType/examDate/examPart/admdvs, camelCase);
     * imgExamType 取医保影像检查类型字典(1 X线/2 CT/3 MRI/4 US/5 ECT), 多值用 | 分隔。
     *
     * @return 三态之一: {@link #DUP_NO_RULE}(未配置规则) / {@link #DUP_NOT_FOUND}(未查到重复) / 重复记录查看 URL
     */
    public String checkDuplicateExam(RisYbDuplicateCheckDTO dto) {
        // 1. 实名信息补齐: DTO 只带 patientId 时从患者档案回查姓名/证件
        Map<String, Object> psn = resolvePatientInfo(dto);
        String psnName = str(psn.get("psnName"));
        String certType = str(psn.get("psnCertType"));
        String certno = str(psn.get("certno"));
        if (isBlank(psnName) || isBlank(certno)) {
            log.info("4804查重跳过: 患者实名信息不全(patientId={}), 无法构造云查重入参", dto.getPatientId());
            return DUP_NO_RULE;
        }
        // 2. 影像检查类型映射(院内 XRAY/CT/MRI/US/DSA/ENDO -> 医保字典 1-5), 映射不到即"未配置规则"
        String imgExamType = mapImgExamType(dto.getExamType());
        if (isBlank(imgExamType)) {
            log.info("4804查重跳过: 检查类型[{}]未配置医保影像字典映射", dto.getExamType());
            return DUP_NO_RULE;
        }
        // 3. 检查日期(缺省当天)与部位(WS/T 364.8-2023 编码)
        String examDate = dto.getPlanExamDate() != null ? dto.getPlanExamDate().toString() : today();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psnName", psnName);
        data.put("psnCertType", certType);
        data.put("certno", certno);
        data.put("imgExamType", imgExamType);
        data.put("examDate", examDate);
        data.put("examPart", str(psn.get("examPart")));
        data.put("admdvs", str(psn.get("admdvs")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", data);

        log.info("4804重复检查查询: psnName={}, imgExamType={}, examDate={}, examPart={}",
                psnName, imgExamType, examDate, data.get("examPart"));
        YbResponse resp = ybHttpClient.call("4804", input);
        if (!resp.isSuccess()) {
            throw new BizException(500, "4804重复检查查询失败: " + resp.getErrMsg());
        }
        // 平台查到重复返回查看 URL; 无重复返回空 url
        String url = resp.getOutputObject().getString("url");
        return (url == null || url.isEmpty()) ? DUP_NOT_FOUND : url;
    }

    // ---------- 内部实现 ----------

    /** 实名信息补齐: DTO 缺失字段从患者档案(his_patient)回查, 部位/区划透传 DTO */
    private Map<String, Object> resolvePatientInfo(RisYbDuplicateCheckDTO dto) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("psnName", null);
        info.put("psnCertType", null);
        info.put("certno", null);
        info.put("examPart", dto.getBodyPart());
        info.put("admdvs", null);
        if (dto.getPatientId() == null) {
            return info;
        }
        try {
            Long tenantId = currentTenantId();
            List<Map<String, Object>> rows = tenantId != null
                    ? jdbcTemplate.queryForList("SELECT name AS psnName, cert_type AS psnCertType, id_card AS certno, org_id FROM his_patient"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1", dto.getPatientId(), tenantId)
                    : jdbcTemplate.queryForList("SELECT name AS psnName, cert_type AS psnCertType, id_card AS certno, org_id FROM his_patient"
                            + " WHERE id = ? AND deleted = 0 LIMIT 1", dto.getPatientId());
            if (!rows.isEmpty()) {
                Map<String, Object> p = rows.get(0);
                info.put("psnName", str(p.get("psnName")));
                info.put("psnCertType", firstNonEmpty(str(p.get("psnCertType")), "01")); // 缺省居民身份证
                info.put("certno", str(p.get("certno")));
            }
        } catch (Exception e) {
            log.warn("4804患者档案回查失败(按信息不全处理): patientId={}, 原因: {}", dto.getPatientId(), e.getMessage());
        }
        return info;
    }

    /** 院内检查类型 -> 医保影像检查类型字典(1 X线成像/2 CT/3 MRI/4 US 超声/5 ECT 核医学) */
    private String mapImgExamType(String examType) {
        if (isBlank(examType)) {
            return null;
        }
        switch (examType.trim().toUpperCase()) {
            case "XRAY":
            case "DR":
            case "CR":
            case "XA":
                return "1";
            case "CT":
                return "2";
            case "MRI":
            case "MR":
                return "3";
            case "US":
                return "4";
            case "ECT":
                return "5";
            default:
                return null; // ENDO 等非医保影像五类 -> 未配置规则
        }
    }

    private Long currentTenantId() {
        com.yb.hi.framework.tenant.LoginUser lu = com.yb.hi.framework.tenant.UserContext.get();
        return lu == null ? null : lu.getTenantId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static String fmt(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }
}
