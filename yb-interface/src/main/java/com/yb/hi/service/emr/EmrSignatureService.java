package com.yb.hi.service.emr;

import cn.hutool.crypto.SmUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.emr.HisEmrSignatureRule;
import com.yb.hi.entity.inpatient.HisEmrSignature;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.emr.EmrSignatureRuleMapper;
import com.yb.hi.mapper.inpatient.HisEmrSignatureMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.emr.sign.OrgSignKey;
import com.yb.hi.service.emr.sign.Sm2SignProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 病历可靠电子签名服务(Phase D, SM2): 对病历 content+structure 的规范化 SM3 摘要做 SM2 签名并落
 * his_emr_signature, 支撑住院三级(author/resident/attending/director)与门诊(doctor)签名链、验签可对抗篡改。
 *
 * 摘要口径: canonical = content ⊫ structure(门诊为 SOAP 各段 ⊫ structure), 分隔符 \u0001; digest = SmUtil.sm3(canonical)hex。
 * 签名: Sm2SignProvider 对 digest 字节加签, sig_value 存 hex; 机构密钥取 sys_org.sm2_private_key/sm2_public_key, 缺则懒生成并回写。
 * 防篡改: 重签同环节旧行 valid=0; 验签用机构公钥对"当前重算摘要"验证, 内容被改则 sigValid=false, 并给出 digestMatch 指示。
 * 三级链补强: 住院 attending/director 环节签名同时回写 his_inp_medical_record 的主治/主任签名列, 与既有查房签名 UI 对齐。
 * 签名规则链(病历P2): 按 his_emr_signature_rule(record_type 1-15, stage_order 升序)校验签署顺序(前序必需环节须已签)
 * 与签署人职称档位(CV08.30.005, title_code_min/max 数值区间), 规则缺失时退化为既有自由签署口径。
 */
@Slf4j
@Service
public class EmrSignatureService {

    private static final String SEP = "\u0001";
    private static final int INP = 1;
    private static final int OUTP = 2;
    private static final List<String> INP_STAGES = Arrays.asList("author", "resident", "attending", "director");
    private static final List<String> OUTP_STAGES = Arrays.asList("doctor");

    private final HisEmrSignatureMapper sigMapper;
    private final EmrSignatureRuleMapper signatureRuleMapper;
    private final HisInpMedicalRecordMapper inpRecordMapper;
    private final HisInpVisitMapper inpVisitMapper;
    private final HisVisitMapper visitMapper;
    private final SysOrgMapper orgMapper;
    private final OrgAccessGuard guard;
    private final Sm2SignProvider provider;
    private final JdbcTemplate jdbcTemplate;

    public EmrSignatureService(HisEmrSignatureMapper sigMapper,
                               EmrSignatureRuleMapper signatureRuleMapper,
                               HisInpMedicalRecordMapper inpRecordMapper,
                               HisInpVisitMapper inpVisitMapper,
                               HisVisitMapper visitMapper,
                               SysOrgMapper orgMapper,
                               OrgAccessGuard guard,
                               Sm2SignProvider provider,
                               JdbcTemplate jdbcTemplate) {
        this.sigMapper = sigMapper;
        this.signatureRuleMapper = signatureRuleMapper;
        this.inpRecordMapper = inpRecordMapper;
        this.inpVisitMapper = inpVisitMapper;
        this.visitMapper = visitMapper;
        this.orgMapper = orgMapper;
        this.guard = guard;
        this.provider = provider;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 签名 ==================== */

    /**
     * 对病历某环节做 SM2 可靠电子签名。
     *
     * @param scope    1住院(定位病历ID) 2门诊(定位就诊ID)
     * @param targetId 住院 his_inp_medical_record.id / 门诊 his_visit.id
     * @param stage    签名环节: 住院 author/resident/attending/director, 门诊 doctor
     * @param signImg  签名图URL(可空, 空则回退当前签名人 his_staff.sign_img_url)
     */
    public R<Map<String, Object>> sign(int scope, Long targetId, String stage, String signImg) {
        LoginUser cur = UserContext.get();
        if (cur == null) {
            throw new BizException(401, "未登录");
        }
        String st = normalizeStage(scope, stage);
        Target t = loadTarget(scope, targetId);
        OrgSignKey key = ensureOrgKey(t.orgId);

        String digestHex = SmUtil.sm3(t.canonical);
        byte[] data = digestHex.getBytes(StandardCharsets.UTF_8);
        String sigHex = provider.sign(data, key);

        // 重签: 同环节旧有效行置 valid=0
        sigMapper.update(null, new LambdaUpdateWrapper<HisEmrSignature>()
                .eq(HisEmrSignature::getScope, scope)
                .eq(INP == scope, HisEmrSignature::getRecordId, targetId)
                .eq(OUTP == scope, HisEmrSignature::getVisitId, targetId)
                .eq(HisEmrSignature::getStage, st)
                .eq(HisEmrSignature::getValid, 1)
                .set(HisEmrSignature::getValid, 0));

        LocalDateTime now = LocalDateTime.now();
        HisEmrSignature row = new HisEmrSignature();
        row.setOrgId(t.orgId);
        row.setScope(scope);
        row.setRecordId(INP == scope ? targetId : null);
        row.setVisitId(INP == scope ? t.visitId : targetId);
        row.setPatientId(t.patientId);
        row.setStage(st);
        row.setSignerId(cur.getStaffId());
        row.setSignerName(displayName(cur));
        row.setDigest(digestHex);
        row.setSigValue(sigHex);
        row.setCertSn(key.getCertSn());
        row.setProvider(provider.providerId());
        row.setSignImg(StringUtils.hasText(signImg) ? signImg : lookupSignImg(cur.getStaffId()));
        row.setSignTime(now);
        row.setValid(1);
        sigMapper.insert(row);

        reinforceChainColumns(scope, targetId, st, cur.getStaffId(), now);

        log.info("病历SM2签名完成: scope={}, target={}, stage={}, signer={}, sigId={}",
                scope, targetId, st, cur.getUsername(), row.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signatureId", row.getId());
        out.put("scope", scope);
        out.put("stage", st);
        out.put("signerName", row.getSignerName());
        out.put("digest", digestHex);
        out.put("certSn", row.getCertSn());
        out.put("signTime", now);
        return R.ok(out);
    }

    /* ==================== 验签 ==================== */

    /**
     * 验签: 用机构公钥对"当前重算摘要"验证签名有效性, 并对抗篡改。
     *
     * @param stage 指定环节; 为空则验最新一条有效签名
     */
    public R<Map<String, Object>> verify(int scope, Long targetId, String stage) {
        Target t = loadTarget(scope, targetId);
        String st = StringUtils.hasText(stage) ? normalizeStage(scope, stage) : null;
        HisEmrSignature sig = sigMapper.selectOne(Wrappers.<HisEmrSignature>lambdaQuery()
                .eq(HisEmrSignature::getScope, scope)
                .eq(INP == scope, HisEmrSignature::getRecordId, targetId)
                .eq(OUTP == scope, HisEmrSignature::getVisitId, targetId)
                .eq(st != null, HisEmrSignature::getStage, st)
                .eq(HisEmrSignature::getValid, 1)
                .orderByDesc(HisEmrSignature::getSignTime)
                .last("LIMIT 1"));
        if (sig == null) {
            throw new BizException(404, "未找到有效签名");
        }
        OrgSignKey key = resolveOrgKey(t.orgId);
        String currentDigest = SmUtil.sm3(t.canonical);
        boolean digestMatch = currentDigest.equals(sig.getDigest());
        boolean sigValid;
        try {
            sigValid = provider.verify(currentDigest.getBytes(StandardCharsets.UTF_8), sig.getSigValue(), key);
        } catch (Exception e) {
            sigValid = false;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signatureId", sig.getId());
        out.put("stage", sig.getStage());
        out.put("signerName", sig.getSignerName());
        out.put("certSn", sig.getCertSn());
        out.put("signTime", sig.getSignTime());
        out.put("storedDigest", sig.getDigest());
        out.put("currentDigest", currentDigest);
        out.put("digestMatch", digestMatch);
        out.put("sigValid", sigValid);
        out.put("passed", sigValid && digestMatch);
        return R.ok(out);
    }

    /* ==================== 签名链 ==================== */

    /** 病历有效签名链(时间正序), 供前端展示三级/门诊多环节签署过程 */
    public R<List<Map<String, Object>>> chain(int scope, Long targetId) {
        loadTarget(scope, targetId);
        List<HisEmrSignature> rows = sigMapper.selectList(Wrappers.<HisEmrSignature>lambdaQuery()
                .eq(HisEmrSignature::getScope, scope)
                .eq(INP == scope, HisEmrSignature::getRecordId, targetId)
                .eq(OUTP == scope, HisEmrSignature::getVisitId, targetId)
                .eq(HisEmrSignature::getValid, 1)
                .orderByAsc(HisEmrSignature::getSignTime));
        List<Map<String, Object>> list = new ArrayList<>();
        for (HisEmrSignature s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("stage", s.getStage());
            m.put("signerId", s.getSignerId());
            m.put("signerName", s.getSignerName());
            m.put("certSn", s.getCertSn());
            m.put("provider", s.getProvider());
            m.put("signImg", s.getSignImg());
            m.put("signTime", s.getSignTime());
            m.put("digest", s.getDigest());
            list.add(m);
        }
        return R.ok(list);
    }

    /* ==================== 签名规则链(病历P2) ==================== */

    /**
     * 按病历类型取签名规则链(stage_order 升序): 机构级规则(org_id 匹配)优先, 无则连同全局(org_id IS NULL)规则;
     * 仍为空时回退该租户同类型的任意规则(种子链挂在牵头机构名下, 充当全租户默认链)。租户隔离由租户插件注入。
     *
     * @param recordType 病历类型 1-15(见 HisEmrSignatureRule 注释)
     * @param orgId      病历归属机构ID(空则不做机构过滤)
     */
    public List<HisEmrSignatureRule> getSignatureChain(int recordType, Long orgId) {
        List<HisEmrSignatureRule> rules = signatureRuleMapper.selectList(Wrappers.<HisEmrSignatureRule>lambdaQuery()
                .eq(HisEmrSignatureRule::getRecordType, recordType)
                .and(orgId != null, w -> w.eq(HisEmrSignatureRule::getOrgId, orgId)
                        .or().isNull(HisEmrSignatureRule::getOrgId))
                .orderByAsc(HisEmrSignatureRule::getStageOrder));
        if (!rules.isEmpty()) {
            return rules;
        }
        return signatureRuleMapper.selectList(Wrappers.<HisEmrSignatureRule>lambdaQuery()
                .eq(HisEmrSignatureRule::getRecordType, recordType)
                .orderByAsc(HisEmrSignatureRule::getStageOrder));
    }

    /**
     * 签名完成度: 以规则链(住院按病历 record_type 取链, 门诊单环节 doctor)比对既有有效签名,
     * 输出 chain(环节/顺序/必需/是否已签/签署人/签署时间)与 allComplete(全部必需环节已签)。
     * 未配置规则链时(住院 record_type 无规则) totalCount=0, allComplete=false 如实呈现。
     */
    public Map<String, Object> getSignatureStatus(Long targetId, int scope) {
        if (targetId == null) {
            throw new BizException(400, "病历/就诊ID不能为空");
        }
        if (scope != INP && scope != OUTP) {
            throw new BizException(400, "scope 必须为 1(住院) 或 2(门诊)");
        }
        Integer recordType = null;
        List<HisEmrSignatureRule> rules;
        if (scope == INP) {
            HisInpMedicalRecord rec = inpRecordMapper.selectById(targetId);
            if (rec == null) {
                throw new BizException(404, "住院病历不存在: " + targetId);
            }
            recordType = rec.getRecordType();
            rules = getSignatureChain(recordType == null ? 1 : recordType,
                    rec.getOrgId() != null ? rec.getOrgId() : guard.currentOrgId());
        } else {
            if (visitMapper.selectById(targetId) == null) {
                throw new BizException(404, "门诊就诊不存在: " + targetId);
            }
            rules = Collections.emptyList();
        }
        Map<String, HisEmrSignature> signed = latestSignedByStage(scope, targetId);
        List<Map<String, Object>> chain = new ArrayList<>();
        if (scope == INP) {
            for (HisEmrSignatureRule r : rules) {
                chain.add(chainEntry(r.getStage(), r.getStageOrder(), r.getRequired(), signed.get(r.getStage())));
            }
        } else {
            chain.add(chainEntry("doctor", 1, 1, signed.get("doctor")));
        }
        int requiredCount = 0;
        int requiredDone = 0;
        for (Map<String, Object> e : chain) {
            if (Integer.valueOf(1).equals(e.get("required"))) {
                requiredCount++;
                if (Boolean.TRUE.equals(e.get("completed"))) {
                    requiredDone++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("recordId", targetId);
        out.put("recordType", recordType);
        out.put("chain", chain);
        out.put("completedCount", requiredDone);
        out.put("totalCount", requiredCount);
        out.put("allComplete", requiredCount > 0 && requiredDone == requiredCount);
        return out;
    }

    /**
     * 规则驱动签署: 校验①环节顺序(前序必需环节须已签)与②签署人职称档位(CV08.30.005,
     * 档位数值介于 title_code_min/max 区间为达标, 空=不限), 通过后复用既有 SM2 签名,
     * 结果附 nextStage(下一待签必需环节, 供 SSE/前端提示)与 allComplete。
     * signerId 仅允许为空或等于当前登录职工(禁止代签); 职称以 his_staff.title_code 库值为准,
     * 入参 titleCode 仅作库值缺失时兜底, 防止客户端伪造档位。
     * 规则链未配置或该环节无规则时退化为既有自由签署口径(仍做环节合法性校验)。
     */
    public R<Map<String, Object>> signByRule(Long targetId, int scope, String stage, Long signerId, String titleCode) {
        LoginUser cur = UserContext.get();
        if (cur == null) {
            throw new BizException(401, "未登录");
        }
        if (signerId != null && cur.getStaffId() != null && !signerId.equals(cur.getStaffId())) {
            throw new BizException(403, "禁止代签: 签署人须为当前登录职工");
        }
        String st = normalizeStage(scope, stage);
        // 规则定位: 住院按病历 record_type 取链; 门诊无规则链
        List<HisEmrSignatureRule> rules = Collections.emptyList();
        if (scope == INP) {
            HisInpMedicalRecord rec = inpRecordMapper.selectById(targetId);
            if (rec == null) {
                throw new BizException(404, "住院病历不存在: " + targetId);
            }
            Integer rt = rec.getRecordType();
            rules = getSignatureChain(rt == null ? 1 : rt,
                    rec.getOrgId() != null ? rec.getOrgId() : guard.currentOrgId());
        }
        HisEmrSignatureRule rule = null;
        for (HisEmrSignatureRule r : rules) {
            if (st.equals(r.getStage())) {
                rule = r;
                break;
            }
        }
        if (rule != null) {
            Map<String, HisEmrSignature> signed = latestSignedByStage(scope, targetId);
            // ① 前序必需环节须已完成
            for (HisEmrSignatureRule r : rules) {
                boolean before = r.getStageOrder() != null && rule.getStageOrder() != null
                        && r.getStageOrder() < rule.getStageOrder();
                boolean required = r.getRequired() == null || r.getRequired() == 1;
                if (before && required && !signed.containsKey(r.getStage())) {
                    throw new BizException(400, "签署顺序不符: 须先完成[" + r.getStage() + "]环节签名");
                }
            }
            // ② 职称档位校验(库值优先, 入参兜底)
            Long opStaffId = signerId != null ? signerId : cur.getStaffId();
            String tc = lookupTitleCode(opStaffId);
            if (!StringUtils.hasText(tc)) {
                tc = titleCode;
            }
            checkTitleAgainstRule(rule, tc);
        }
        // 复用既有 SM2 签名(重签同环节旧行 valid=0 逻辑一并复用)
        R<Map<String, Object>> out = sign(scope, targetId, st, null);
        // 附下一待签环节(计算失败不影响签名结果)
        try {
            Map<String, Object> status = getSignatureStatus(targetId, scope);
            out.getData().put("nextStage", nextPendingStage(status));
            out.getData().put("allComplete", status.get("allComplete"));
        } catch (Exception e) {
            log.warn("签名后完成度计算失败(不影响签名结果): scope={}, target={}, {}", scope, targetId, e.getMessage());
        }
        return out;
    }

    /* ==================== 内部 ==================== */

    /** 目标病历各环节最新有效签名(时间升序遍历, 同环节后签覆盖先签) */
    private Map<String, HisEmrSignature> latestSignedByStage(int scope, Long targetId) {
        List<HisEmrSignature> sigs = sigMapper.selectList(Wrappers.<HisEmrSignature>lambdaQuery()
                .eq(HisEmrSignature::getScope, scope)
                .eq(INP == scope, HisEmrSignature::getRecordId, targetId)
                .eq(OUTP == scope, HisEmrSignature::getVisitId, targetId)
                .eq(HisEmrSignature::getValid, 1)
                .orderByAsc(HisEmrSignature::getSignTime));
        Map<String, HisEmrSignature> byStage = new LinkedHashMap<>();
        for (HisEmrSignature s : sigs) {
            byStage.put(s.getStage(), s);
        }
        return byStage;
    }

    /** 规则链条目(完成度输出) */
    private Map<String, Object> chainEntry(String stage, Integer stageOrder, Integer required, HisEmrSignature sig) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("stageOrder", stageOrder);
        m.put("required", required == null ? 1 : required);
        m.put("completed", sig != null);
        if (sig != null) {
            m.put("signatureId", sig.getId());
            m.put("signerId", sig.getSignerId());
            m.put("signerName", sig.getSignerName());
            m.put("signTime", sig.getSignTime());
        }
        return m;
    }

    /** 职称档位校验(CV08.30.005: 1正高 2副高 3中级 4师级/助理 5士级; min/max 空表示不限) */
    private void checkTitleAgainstRule(HisEmrSignatureRule rule, String titleCode) {
        String min = rule.getTitleCodeMin();
        String max = rule.getTitleCodeMax();
        if (!StringUtils.hasText(min) && !StringUtils.hasText(max)) {
            return;
        }
        if (!StringUtils.hasText(titleCode)) {
            throw new BizException(403, "签署人职称缺失, 不满足该环节资质要求(要求档位 "
                    + (StringUtils.hasText(min) ? min : "") + "~" + (StringUtils.hasText(max) ? max : "") + ")");
        }
        Integer code = parseTitleCode(titleCode);
        if (code == null) {
            throw new BizException(403, "签署人职称编码无法识别: " + titleCode);
        }
        Integer lo = StringUtils.hasText(min) ? parseTitleCode(min) : null;
        Integer hi = StringUtils.hasText(max) ? parseTitleCode(max) : null;
        if (lo != null && code < lo) {
            throw new BizException(403, "签署人职称档位不足: 环节要求档位不低于 " + min + ", 实际档位 " + titleCode);
        }
        if (hi != null && code > hi) {
            throw new BizException(403, "签署人职称档位超出: 环节要求档位不高于 " + max + ", 实际档位 " + titleCode);
        }
    }

    /** 职称编码解析(CV08.30.005 取前导数字档位); 无法识别返回 null */
    private Integer parseTitleCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        Matcher m = Pattern.compile("^\\d+").matcher(code.trim());
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    /** 签署人职称(his_staff.title_code, 查询失败返回 null 由入参兜底) */
    private String lookupTitleCode(Long staffId) {
        if (staffId == null) {
            return null;
        }
        try {
            List<String> codes = jdbcTemplate.queryForList(
                    "SELECT title_code FROM his_staff WHERE id = ? AND deleted = 0 LIMIT 1",
                    String.class, staffId);
            return codes.isEmpty() ? null : codes.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 下一待签必需环节(无则为 null) */
    private String nextPendingStage(Map<String, Object> status) {
        Object chainObj = status.get("chain");
        if (!(chainObj instanceof List)) {
            return null;
        }
        for (Object o : (List<?>) chainObj) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (Integer.valueOf(1).equals(m.get("required")) && !Boolean.TRUE.equals(m.get("completed"))) {
                    return String.valueOf(m.get("stage"));
                }
            }
        }
        return null;
    }

    /** 载入签名目标病历并计算规范化摘要串(canonical), 附 org/visit/patient 上下文 */
    private Target loadTarget(int scope, Long targetId) {
        if (targetId == null) {
            throw new BizException(400, "病历/就诊ID不能为空");
        }
        if (scope != INP && scope != OUTP) {
            throw new BizException(400, "scope 必须为 1(住院) 或 2(门诊)");
        }
        Target t = new Target();
        t.scope = scope;
        t.targetId = targetId;
        if (scope == INP) {
            HisInpMedicalRecord rec = inpRecordMapper.selectById(targetId);
            if (rec == null) {
                throw new BizException(404, "住院病历不存在: " + targetId);
            }
            t.orgId = rec.getOrgId() != null ? rec.getOrgId() : guard.currentOrgId();
            t.visitId = rec.getInpVisitId();
            t.patientId = rec.getInpVisitId() == null ? null : patientOfInpVisit(rec.getInpVisitId());
            t.canonical = nullToEmpty(rec.getContent()) + SEP + nullToEmpty(rec.getStructureData());
        } else {
            HisVisit v = visitMapper.selectById(targetId);
            if (v == null) {
                throw new BizException(404, "门诊就诊不存在: " + targetId);
            }
            t.orgId = guard.currentOrgId();
            t.visitId = v.getId();
            t.patientId = v.getPatientId();
            StringBuilder sb = new StringBuilder();
            sb.append(nullToEmpty(v.getChiefComplaint())).append(SEP)
                    .append(nullToEmpty(v.getPresentIllness())).append(SEP)
                    .append(nullToEmpty(v.getPastHistory())).append(SEP)
                    .append(nullToEmpty(v.getPhysicalExam())).append(SEP)
                    .append(nullToEmpty(v.getAuxExam())).append(SEP)
                    .append(nullToEmpty(v.getAllergyHistory())).append(SEP)
                    .append(nullToEmpty(v.getTreatmentOpinion())).append(SEP)
                    .append(nullToEmpty(v.getStructure()));
            t.canonical = sb.toString();
        }
        return t;
    }

    private Long patientOfInpVisit(Long inpVisitId) {
        HisInpVisit iv = inpVisitMapper.selectById(inpVisitId);
        return iv == null ? null : iv.getPatientId();
    }

    private String normalizeStage(int scope, String stage) {
        if (!StringUtils.hasText(stage)) {
            throw new BizException(400, "签名环节(stage)不能为空");
        }
        String s = stage.trim().toLowerCase();
        List<String> allowed = scope == INP ? INP_STAGES : OUTP_STAGES;
        if (!allowed.contains(s)) {
            throw new BizException(400, "非法签名环节: " + stage + "(" + (scope == INP ? "住院" : "门诊")
                    + "允许: " + String.join("/", allowed) + ")");
        }
        return s;
    }

    /** 机构 SM2 密钥(可能缺私钥/公钥, 交由 provider 懒生成) */
    private OrgSignKey resolveOrgKey(Long orgId) {
        SysOrg org = orgId == null ? null : orgMapper.selectById(orgId);
        if (org == null) {
            throw new BizException(403, "机构不存在或未归属, 无法签名");
        }
        return new OrgSignKey(org.getSm2PrivateKey(), org.getSm2PublicKey(), org.getSignNo());
    }

    /**
     * 签名用密钥: 机构已备齐公私钥则直接用; 缺则懒生成一对 SM2 密钥并定向回写 sys_org
     * (仅更新 sm2_private_key/sm2_public_key/sign_no 三列, 不触碰其它字段)。证书编号缺失以 SM2-{orgId} 占位。
     */
    private OrgSignKey ensureOrgKey(Long orgId) {
        SysOrg org = orgId == null ? null : orgMapper.selectById(orgId);
        if (org == null) {
            throw new BizException(403, "机构不存在或未归属, 无法签名");
        }
        if (StringUtils.hasText(org.getSm2PrivateKey()) && StringUtils.hasText(org.getSm2PublicKey())) {
            return new OrgSignKey(org.getSm2PrivateKey(), org.getSm2PublicKey(), org.getSignNo());
        }
        OrgSignKey gen = provider.generateKey();
        String certSn = StringUtils.hasText(org.getSignNo()) ? org.getSignNo() : ("SM2-" + orgId);
        orgMapper.update(null, new LambdaUpdateWrapper<SysOrg>()
                .eq(SysOrg::getId, orgId)
                .set(SysOrg::getSm2PrivateKey, gen.getPrivHex())
                .set(SysOrg::getSm2PublicKey, gen.getPubHex())
                .set(!StringUtils.hasText(org.getSignNo()), SysOrg::getSignNo, certSn));
        log.info("机构 SM2 签名密钥已懒生成并回写: orgId={}, certSn={}", orgId, certSn);
        return new OrgSignKey(gen.getPrivHex(), gen.getPubHex(), certSn);
    }

    /** 三级链补强: 住院主治/主任签名回写病历签名列(与既有查房签名 UI 对齐) */
    private void reinforceChainColumns(int scope, Long recordId, String stage, Long signerId, LocalDateTime time) {
        if (scope != INP) {
            return;
        }
        LambdaUpdateWrapper<HisInpMedicalRecord> w = new LambdaUpdateWrapper<HisInpMedicalRecord>()
                .eq(HisInpMedicalRecord::getId, recordId);
        if ("attending".equals(stage)) {
            w.set(HisInpMedicalRecord::getAttendingSignId, signerId).set(HisInpMedicalRecord::getAttendingSignTime, time);
        } else if ("director".equals(stage)) {
            w.set(HisInpMedicalRecord::getDirectorSignId, signerId).set(HisInpMedicalRecord::getDirectorSignTime, time);
        } else {
            return;
        }
        inpRecordMapper.update(null, w);
    }

    private String lookupSignImg(Long staffId) {
        if (staffId == null) {
            return null;
        }
        try {
            List<String> urls = jdbcTemplate.queryForList(
                    "SELECT sign_img_url FROM his_staff WHERE id = ? AND deleted = 0 LIMIT 1",
                    String.class, staffId);
            return urls.isEmpty() ? null : urls.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String displayName(LoginUser cur) {
        String name = StringUtils.hasText(cur.getRealName()) ? cur.getRealName() : cur.getUsername();
        if (name == null) {
            name = "user-" + cur.getUserId();
        }
        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 签名目标上下文 */
    private static class Target {
        int scope;
        Long targetId;
        Long orgId;
        Long visitId;
        Long patientId;
        String canonical;
    }
}
