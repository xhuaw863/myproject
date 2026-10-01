package com.yb.hi.service.emr;

import cn.hutool.crypto.SmUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.inpatient.HisEmrSignature;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisVisitMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历可靠电子签名服务(Phase D, SM2): 对病历 content+structure 的规范化 SM3 摘要做 SM2 签名并落
 * his_emr_signature, 支撑住院三级(author/resident/attending/director)与门诊(doctor)签名链、验签可对抗篡改。
 *
 * 摘要口径: canonical = content ⊫ structure(门诊为 SOAP 各段 ⊫ structure), 分隔符 \u0001; digest = SmUtil.sm3(canonical)hex。
 * 签名: Sm2SignProvider 对 digest 字节加签, sig_value 存 hex; 机构密钥取 sys_org.sm2_private_key/sm2_public_key, 缺则懒生成并回写。
 * 防篡改: 重签同环节旧行 valid=0; 验签用机构公钥对"当前重算摘要"验证, 内容被改则 sigValid=false, 并给出 digestMatch 指示。
 * 三级链补强: 住院 attending/director 环节签名同时回写 his_inp_medical_record 的主治/主任签名列, 与既有查房签名 UI 对齐。
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
    private final HisInpMedicalRecordMapper inpRecordMapper;
    private final HisInpVisitMapper inpVisitMapper;
    private final HisVisitMapper visitMapper;
    private final SysOrgMapper orgMapper;
    private final OrgAccessGuard guard;
    private final Sm2SignProvider provider;
    private final JdbcTemplate jdbcTemplate;

    public EmrSignatureService(HisEmrSignatureMapper sigMapper,
                               HisInpMedicalRecordMapper inpRecordMapper,
                               HisInpVisitMapper inpVisitMapper,
                               HisVisitMapper visitMapper,
                               SysOrgMapper orgMapper,
                               OrgAccessGuard guard,
                               Sm2SignProvider provider,
                               JdbcTemplate jdbcTemplate) {
        this.sigMapper = sigMapper;
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

    /* ==================== 内部 ==================== */

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
