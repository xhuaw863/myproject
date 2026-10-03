package com.yb.hi.service.emr;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历多方式签名与验签服务(P8b-1): 文字 / 图片 / CA 三种签名方式 + 单条/批量/归档前验签 +
 * 患者家属签名留存 + 签名信息回查, 全部落在 his_emr_signature(DDL 以 P8b 扩展 13 列承载)。
 *
 * 三种签名方式(均先经签名链校验后落库, 环节自动推进):
 * - 文字(sign_mode=1): 复用 EmrSignatureService.signByRule 现有 SM2 规则链(环节顺序/职称档位校验 +
 *   SM3 摘要 SM2 签名), 签完回填 sign_mode=1;
 * - 图片(sign_mode=2): 同一规则链校验与 SM2 签名, 追加手写签名图 base64 落 sign_image 列;
 * - CA(sign_mode=3): 规则链签名后另计算病历原文(content) SHA-256 哈希, 经 callCaService(当前模拟实现,
 *   预留厂商 SDK 对接)获取 CA 签名值/时间戳/证书数据, 落 ca_cert_sn、ca_signature_value、ca_timestamp、
 *   ca_cert_data、ca_original_hash, provider 置 ca。
 *
 * 环节自动推进: 三种签名均以签名链"下一待签必需环节"为目标(与 /api/emr/sign 规则链同一口径);
 * 未配置规则链的病历回退 author(自由签署口径); 必需环节已全部完成则拒绝自动重复签署(重签走 /api/emr/sign)。
 *
 * 验签(verify_result: 0未验 1通过 2失败): sign_mode 1/2 直接标记通过; sign_mode=3 重新计算病历原文
 * SHA-256 并与 ca_original_hash 比对, 一致通过、不一致判定失败(文档被篡改); 每次验签回写
 * verify_result/verify_time。归档前验签 verifyOnArchive 汇总该病历全部有效签名, 有签名且全部通过才
 * allPassed=true, 供归档前门控消费。
 *
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 全部显式携带 tenant_id 与 deleted=0;
 * 扩展列缺失(迁移未执行)时 try-catch BadSqlGrammarException 降级告警, 不影响签名主流程。
 */
@Slf4j
@Service
public class CaSignatureService {

    /** 签名方式: 文字 */
    private static final int SIGN_MODE_TEXT = 1;
    /** 签名方式: 图片(手写签名图) */
    private static final int SIGN_MODE_IMAGE = 2;
    /** 签名方式: CA 数字签名 */
    private static final int SIGN_MODE_CA = 3;
    /** 验签结果: 通过 */
    private static final int VERIFY_PASSED = 1;
    /** 验签结果: 失败 */
    private static final int VERIFY_FAILED = 2;
    /** CA 签名算法缺省(国产 CA 常见组合; 模拟模式不实际参与运算) */
    private static final String DEFAULT_CA_ALGORITHM = "SM2withSM3";
    /** CA 时间戳字符串格式(ca_timestamp VARCHAR(50)) */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final EmrSignatureService emrSignatureService;

    public CaSignatureService(JdbcTemplate jdbcTemplate, EmrSignatureService emrSignatureService) {
        this.jdbcTemplate = jdbcTemplate;
        this.emrSignatureService = emrSignatureService;
    }

    /* ==================== 三种签名方式 ==================== */

    /**
     * 文字签名: 复用 EmrSignatureService 现有签名链。
     * 环节自动推进到下一待签必需环节, 规则链完成顺序与职称档位校验后完成 SM2 摘要签名并落库,
     * 回填 sign_mode=1。
     *
     * @param recordId 住院病历ID(his_inp_medical_record.id)
     * @param signerId 签署职工ID(his_staff.id; 空则取当前登录职工, 禁止代签)
     */
    public Map<String, Object> signByText(Long recordId, Long signerId) {
        String stage = resolveNextStage(recordId);
        Map<String, Object> data = signByRuleChain(recordId, stage, signerId);
        Long signatureId = toLongObj(data.get("signatureId"));
        updateSignModeQuietly(signatureId, SIGN_MODE_TEXT);
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("signMode", SIGN_MODE_TEXT);
        out.put("signModeName", "文字签名");
        log.info("文字签名完成: recordId={}, signatureId={}, stage={}", recordId, signatureId, stage);
        return out;
    }

    /**
     * 图片签名: 保存手写签名图片。
     * 复用签名链校验(环节顺序/职称档位)与 SM2 签名, 手写签名图 base64 落 sign_image 列, sign_mode=2。
     *
     * @param imageBase64 手写签名图片 base64(必填, 建议 PNG 透明底)
     */
    public Map<String, Object> signByImage(Long recordId, Long signerId, String imageBase64) {
        if (!StringUtils.hasText(imageBase64)) {
            throw new BizException(400, "手写签名图片(imageBase64)不能为空");
        }
        String stage = resolveNextStage(recordId);
        Map<String, Object> data = signByRuleChain(recordId, stage, signerId);
        Long signatureId = toLongObj(data.get("signatureId"));
        updateSignImageQuietly(signatureId, imageBase64);
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("signMode", SIGN_MODE_IMAGE);
        out.put("signModeName", "图片签名");
        log.info("图片签名完成: recordId={}, signatureId={}, stage={}, imageChars={}",
                recordId, signatureId, stage, imageBase64.length());
        return out;
    }

    /**
     * CA 数字签名: 病历原文 SHA-256 哈希 + CA 签名值。
     * caReq: certSn(CA证书序列号, 必填), signatureAlgorithm(签名算法, 缺省 SM2withSM3)。
     * 流程: 计算原文哈希 → 环节推进校验 → callCaService 取签名值 → 复用签名链创建签名记录 →
     * 回填 CA 列(sign_mode=3, provider=ca)。
     */
    public Map<String, Object> signByCa(Long recordId, Long signerId, Map<String, Object> caReq) {
        Map<String, Object> req = caReq == null ? new LinkedHashMap<String, Object>() : caReq;
        String certSn = objToStr(req.get("certSn"));
        if (!StringUtils.hasText(certSn)) {
            throw new BizException(400, "CA证书序列号(certSn)不能为空");
        }
        String algorithm = objToStr(req.get("signatureAlgorithm"));
        if (!StringUtils.hasText(algorithm)) {
            algorithm = DEFAULT_CA_ALGORITHM;
        }
        // 1. 病历原文哈希(SHA-256): 读取 his_inp_medical_record.content
        String originalHash = sha256Hex(loadRecordContent(recordId));
        // 2. 环节推进(链校验前置, 避免链已完成时白调 CA 服务)
        String stage = resolveNextStage(recordId);
        // 3. 调用 CA 服务获取签名值(当前模拟, 生产环境对接厂商 SDK)
        Map<String, Object> caOut = callCaService(originalHash, certSn, algorithm);
        // 4. 复用签名链完成顺序/职称校验与签名记录创建
        Map<String, Object> data = signByRuleChain(recordId, stage, signerId);
        Long signatureId = toLongObj(data.get("signatureId"));
        // 5. 回填 CA 签名列
        boolean caStored = updateCaFieldsQuietly(signatureId, certSn, caOut, originalHash);
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("signMode", SIGN_MODE_CA);
        out.put("signModeName", "CA数字签名");
        out.put("certSn", certSn);
        out.put("signatureAlgorithm", algorithm);
        out.put("originalHash", originalHash);
        out.put("caTimestamp", caOut.get("timestamp"));
        out.put("caStored", caStored);
        log.info("CA签名完成: recordId={}, signatureId={}, stage={}, certSn={}, caStored={}",
                recordId, signatureId, stage, certSn, caStored);
        return out;
    }

    /* ==================== 验签 ==================== */

    /**
     * 单条验签: sign_mode 1/2 直接标记通过; sign_mode=3 重算病历原文 SHA-256 与 ca_original_hash 比对,
     * 一致通过、不一致(或无法比对)判失败; 回写 verify_result/verify_time。
     */
    public Map<String, Object> verify(Long signatureId) {
        if (signatureId == null) {
            throw new BizException(400, "签名ID不能为空");
        }
        Map<String, Object> row = loadSignature(signatureId);
        if (row == null) {
            throw new BizException(404, "签名记录不存在: " + signatureId);
        }
        int signMode = toInt(row.get("signMode"), SIGN_MODE_TEXT);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signatureId", signatureId);
        out.put("signMode", signMode);
        out.put("stage", row.get("stage"));
        out.put("signerName", row.get("signerName"));
        int verifyResult;
        boolean passed;
        if (signMode == SIGN_MODE_CA) {
            String storedHash = objToStr(row.get("caOriginalHash"));
            Long recordId = toLongObj(row.get("recordId"));
            String currentHash = null;
            boolean hashMatch = false;
            if (recordId != null) {
                try {
                    currentHash = sha256Hex(loadRecordContent(recordId));
                    hashMatch = StringUtils.hasText(storedHash) && storedHash.equalsIgnoreCase(currentHash);
                } catch (BizException e) {
                    log.warn("CA验签取原文失败: signatureId={}, recordId={}, err={}", signatureId, recordId, e.getMessage());
                }
            }
            verifyResult = hashMatch ? VERIFY_PASSED : VERIFY_FAILED;
            passed = hashMatch;
            out.put("originalHash", storedHash);
            out.put("currentHash", currentHash);
            out.put("hashMatch", hashMatch);
            out.put("message", hashMatch ? "CA签名验签通过: 原文哈希一致"
                    : "CA签名验签失败: 原文哈希不一致或无法比对(文档可能被篡改)");
        } else {
            verifyResult = VERIFY_PASSED;
            passed = true;
            out.put("message", signMode == SIGN_MODE_IMAGE ? "图片签名默认验签通过" : "文字签名默认验签通过");
        }
        updateVerifyResultQuietly(signatureId, verifyResult, now);
        out.put("verifyResult", verifyResult);
        out.put("verifyTime", now);
        out.put("passed", passed);
        log.info("签名验签完成: signatureId={}, signMode={}, verifyResult={}", signatureId, signMode, verifyResult);
        return out;
    }

    /** 批量验签: 逐条调用 verify, 单条异常不阻断(异常记入该条结果 message)。 */
    public List<Map<String, Object>> batchVerify(List<Long> signatureIds) {
        if (signatureIds == null || signatureIds.isEmpty()) {
            throw new BizException(400, "请至少选择一条签名记录进行验签");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : signatureIds) {
            if (id == null) {
                continue;
            }
            try {
                out.add(verify(id));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("signatureId", id);
                err.put("passed", false);
                err.put("verifyResult", VERIFY_FAILED);
                err.put("message", "验签异常: " + e.getMessage());
                out.add(err);
                log.warn("批量验签单条失败(不阻断): signatureId={}, err={}", id, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 归档前自动验签: 取该病历全部有效签名(valid=1)逐条验签并汇总。
     * 返回 {recordId, total, passed, failed, allPassed, details}; 有签名且全部通过才 allPassed=true,
     * 无有效签名时 allPassed=false 并给出提示(归档门控不应把"无签名"视为通过)。
     */
    public Map<String, Object> verifyOnArchive(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        List<Long> ids;
        try {
            ids = jdbcTemplate.queryForList(
                    "SELECT id FROM his_emr_signature WHERE scope = 1 AND record_id = ? AND valid = 1 "
                            + "AND tenant_id = ? AND deleted = 0 ORDER BY sign_time ASC, id ASC",
                    Long.class, recordId, tenantId());
        } catch (BadSqlGrammarException e) {
            throw new BizException(500, "归档前验签失败: 签名表不可用(" + e.getMessage() + ")");
        }
        List<Map<String, Object>> details = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                Map<String, Object> v = verify(id);
                details.add(v);
                if (Boolean.TRUE.equals(v.get("passed"))) {
                    passed++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("signatureId", id);
                err.put("passed", false);
                err.put("message", "验签异常: " + e.getMessage());
                details.add(err);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", recordId);
        out.put("total", ids.size());
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("allPassed", !ids.isEmpty() && failed == 0);
        if (ids.isEmpty()) {
            out.put("message", "该病历尚无有效签名, 无法通过归档前验签");
        }
        out.put("details", details);
        log.info("归档前验签: recordId={}, total={}, passed={}, failed={}", recordId, ids.size(), passed, failed);
        return out;
    }

    /* ==================== 患者/家属签名与查询 ==================== */

    /**
     * 保存患者/家属签名图片: 仅更新提供了图片的项(对应时间戳同步落库), 另一项保持原值;
     * 至少需提供一项。扩展列缺失(迁移未执行)时抛 500 提示。
     */
    public void savePatientSign(Long signatureId, String patientImage, String familyImage) {
        if (signatureId == null) {
            throw new BizException(400, "签名ID不能为空");
        }
        boolean hasPatient = StringUtils.hasText(patientImage);
        boolean hasFamily = StringUtils.hasText(familyImage);
        if (!hasPatient && !hasFamily) {
            throw new BizException(400, "请至少提供患者或家属签名图片");
        }
        if (loadSignature(signatureId) == null) {
            throw new BizException(404, "签名记录不存在: " + signatureId);
        }
        Long tenant = tenantId();
        Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
        try {
            if (hasPatient) {
                jdbcTemplate.update("UPDATE his_emr_signature SET patient_sign_image = ?, patient_sign_time = ?, "
                                + "update_time = ? WHERE id = ? AND tenant_id = ? AND deleted = 0",
                        patientImage, ts, ts, signatureId, tenant);
            }
            if (hasFamily) {
                jdbcTemplate.update("UPDATE his_emr_signature SET family_sign_image = ?, family_sign_time = ?, "
                                + "update_time = ? WHERE id = ? AND tenant_id = ? AND deleted = 0",
                        familyImage, ts, ts, signatureId, tenant);
            }
        } catch (BadSqlGrammarException e) {
            throw new BizException(500, "患者/家属签名保存失败: 签名表缺少扩展列(迁移未执行): " + e.getMessage());
        }
        log.info("患者/家属签名保存完成: signatureId={}, patient={}, family={}", signatureId, hasPatient, hasFamily);
    }

    /** 记录签名信息列表(时间正序, 含已失效行由 valid 列区分): 全列回查供签名详情/展示消费。 */
    public List<Map<String, Object>> getSignInfo(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, sign_mode AS signMode, scope, record_id AS recordId, visit_id AS visitId, "
                            + "patient_id AS patientId, stage, signer_id AS signerId, signer_name AS signerName, "
                            + "digest, cert_sn AS certSn, provider, sign_img AS signImg, sign_time AS signTime, valid, "
                            + "sign_image AS signImage, ca_cert_sn AS caCertSn, ca_signature_value AS caSignatureValue, "
                            + "ca_timestamp AS caTimestamp, ca_cert_data AS caCertData, ca_original_hash AS caOriginalHash, "
                            + "verify_result AS verifyResult, verify_time AS verifyTime, "
                            + "patient_sign_image AS patientSignImage, patient_sign_time AS patientSignTime, "
                            + "family_sign_image AS familySignImage, family_sign_time AS familySignTime, "
                            + "create_time AS createTime "
                            + "FROM his_emr_signature WHERE record_id = ? AND tenant_id = ? AND deleted = 0 "
                            + "ORDER BY create_time ASC, id ASC",
                    recordId, tenantId());
        } catch (BadSqlGrammarException e) {
            // 旧库缺扩展列: 退化为基础列查询并补默认签名方式, 保证迁移未执行时详情仍可用
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, scope, record_id AS recordId, visit_id AS visitId, patient_id AS patientId, stage, "
                            + "signer_id AS signerId, signer_name AS signerName, digest, cert_sn AS certSn, provider, "
                            + "sign_img AS signImg, sign_time AS signTime, valid, create_time AS createTime "
                            + "FROM his_emr_signature WHERE record_id = ? AND tenant_id = ? AND deleted = 0 "
                            + "ORDER BY create_time ASC, id ASC",
                    recordId, tenantId());
            for (Map<String, Object> r : rows) {
                r.putIfAbsent("signMode", SIGN_MODE_TEXT);
            }
            return rows;
        }
    }

    /* ==================== 内部: 环节推进与链签名 ==================== */

    /**
     * 自动推进签名环节: 取签名链"首个未完成的必需环节"作为本次签署目标(与规则链顺序校验同一口径);
     * 未配置规则链的病历回退 author(自由签署口径); 必需环节已全部完成时拒绝自动重复签署。
     */
    private String resolveNextStage(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        Map<String, Object> status = emrSignatureService.getSignatureStatus(recordId, 1);
        Object chainObj = status == null ? null : status.get("chain");
        boolean hasChain = chainObj instanceof List && !((List<?>) chainObj).isEmpty();
        if (chainObj instanceof List) {
            for (Object o : (List<?>) chainObj) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (Integer.valueOf(1).equals(m.get("required")) && !Boolean.TRUE.equals(m.get("completed"))) {
                        return String.valueOf(m.get("stage"));
                    }
                }
            }
        }
        if (!hasChain) {
            return "author";
        }
        throw new BizException(400, "签名链必需环节已全部完成, 无需重复签署; 如需重签请使用 /api/emr/sign 指定环节接口");
    }

    /** 复用 EmrSignatureService.signByRule 现有签名链(规则链校验 + SM2 签名 + 重签置旧行失效)。 */
    private Map<String, Object> signByRuleChain(Long recordId, String stage, Long signerId) {
        R<Map<String, Object>> r = emrSignatureService.signByRule(recordId, 1, stage, signerId, null);
        Map<String, Object> data = r == null ? null : r.getData();
        if (data == null || data.get("signatureId") == null) {
            throw new BizException(500, "签名链执行失败: 未返回签名记录");
        }
        return data;
    }

    /* ==================== 内部: CA 服务对接(预留) ==================== */

    /**
     * 预留 CA 服务对接: 提交签名原文哈希/证书序列号/签名算法, 返回 signatureValue/certData/timestamp。
     * TODO: 生产环境对接具体 CA 厂商 SDK(替换本模拟实现)。
     * 当前模拟模式: signatureValue = "SIMULATED_" + 原文哈希, certData = "SIMULATED_CERT", timestamp = 当前时间。
     */
    private Map<String, Object> callCaService(String originalHash, String certSn, String algorithm) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signatureValue", "SIMULATED_" + originalHash);
        out.put("certData", "SIMULATED_CERT");
        out.put("timestamp", LocalDateTime.now().format(TS_FMT));
        log.info("CA签名服务(模拟模式): certSn={}, algorithm={}, hash={}", certSn, algorithm, originalHash);
        return out;
    }

    /* ==================== 内部: 回填与载入 ==================== */

    /** 回填签名方式(文字); 旧库缺列时告警不阻断。 */
    private void updateSignModeQuietly(Long signatureId, int signMode) {
        if (signatureId == null) {
            return;
        }
        try {
            jdbcTemplate.update("UPDATE his_emr_signature SET sign_mode = ?, update_time = NOW() "
                            + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    signMode, signatureId, tenantId());
        } catch (BadSqlGrammarException e) {
            log.warn("签名方式回填失败(旧库缺列, 不影响签名): signatureId={}, err={}", signatureId, e.getMessage());
        }
    }

    /** 回填手写签名图片并置 sign_mode=2; 旧库缺列时告警不阻断。 */
    private void updateSignImageQuietly(Long signatureId, String imageBase64) {
        if (signatureId == null) {
            return;
        }
        try {
            jdbcTemplate.update("UPDATE his_emr_signature SET sign_mode = ?, sign_image = ?, update_time = NOW() "
                            + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    SIGN_MODE_IMAGE, imageBase64, signatureId, tenantId());
        } catch (BadSqlGrammarException e) {
            log.warn("手写签名图片回填失败(旧库缺列, 不影响签名): signatureId={}, err={}", signatureId, e.getMessage());
        }
    }

    /** 回填 CA 签名列(sign_mode=3, provider=ca); 成功返回 true, 旧库缺列返回 false。 */
    private boolean updateCaFieldsQuietly(Long signatureId, String certSn,
                                          Map<String, Object> caOut, String originalHash) {
        if (signatureId == null) {
            return false;
        }
        try {
            int n = jdbcTemplate.update(
                    "UPDATE his_emr_signature SET sign_mode = ?, ca_cert_sn = ?, ca_signature_value = ?, "
                            + "ca_timestamp = ?, ca_cert_data = ?, ca_original_hash = ?, provider = 'ca', "
                            + "update_time = NOW() WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    SIGN_MODE_CA, certSn, objToStr(caOut.get("signatureValue")), objToStr(caOut.get("timestamp")),
                    objToStr(caOut.get("certData")), originalHash, signatureId, tenantId());
            return n > 0;
        } catch (BadSqlGrammarException e) {
            log.warn("CA签名列回填失败(旧库缺列): signatureId={}, err={}", signatureId, e.getMessage());
            return false;
        }
    }

    /** 回填验签结果与时间; 旧库缺列时告警(验签返回值仍有效)。 */
    private void updateVerifyResultQuietly(Long signatureId, int verifyResult, LocalDateTime verifyTime) {
        try {
            Timestamp ts = Timestamp.valueOf(verifyTime);
            jdbcTemplate.update("UPDATE his_emr_signature SET verify_result = ?, verify_time = ?, update_time = ? "
                            + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    verifyResult, ts, ts, signatureId, tenantId());
        } catch (BadSqlGrammarException e) {
            log.warn("验签结果回填失败(旧库缺列, 不影响验签计算): signatureId={}, err={}", signatureId, e.getMessage());
        }
    }

    /** 载入签名记录关键列(扩展列缺失时退化基础列), 不存在返回 null。 */
    private Map<String, Object> loadSignature(Long signatureId) {
        Long tenant = tenantId();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, sign_mode AS signMode, stage, signer_name AS signerName, record_id AS recordId, "
                            + "visit_id AS visitId, ca_original_hash AS caOriginalHash "
                            + "FROM his_emr_signature WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    signatureId, tenant);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (BadSqlGrammarException e) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, stage, signer_name AS signerName, record_id AS recordId, visit_id AS visitId "
                            + "FROM his_emr_signature WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    signatureId, tenant);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    /** 读取住院病历原文(content); 病历不存在抛 404。 */
    private String loadRecordContent(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT content FROM his_inp_medical_record WHERE id = ? AND tenant_id = ? AND deleted = 0",
                String.class, recordId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(404, "住院病历不存在: " + recordId);
        }
        return rows.get(0) == null ? "" : rows.get(0);
    }

    /* ==================== 内部: 通用助手 ==================== */

    /** SHA-256 十六进制摘要(小写; null 视为空串)。 */
    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(500, "SHA-256 哈希计算失败: " + e.getMessage());
        }
    }

    /** 当前租户ID(未初始化直接失败, 防跨租户读写)。 */
    private static Long tenantId() {
        Long t = TenantContext.get();
        if (t == null) {
            throw new BizException(500, "租户上下文未初始化");
        }
        return t;
    }

    /** 对象转 Long(null/非法返回 null; 兼容雪花ID字符串入参)。 */
    private static Long toLongObj(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 对象转 int(null/非法返回缺省值)。 */
    private static int toInt(Object v, int def) {
        Long l = toLongObj(v);
        return l == null ? def : l.intValue();
    }

    /** 对象转字符串(null 返回 null)。 */
    private static String objToStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
