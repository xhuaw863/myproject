package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingConsentDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingConsent;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingConsentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 护理告知书/同意书服务(P4c): 告知内容登记 → 患者/家属手写签名(base64 图片) → 撤回三态闭环。
 * 说明:
 * 1) 表 his_nursing_consent 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离;
 * 2) 生命周期状态机: 创建(status=0 待签) → 签名(status=1 已签, 回填签名图片/签名人/关系/签名时间,
 *    患者或家属至少一方签名) → 撤回(status=2, 签名图片保留但标记失效留痕);
 *    仅待签可签, 待签/已签均可撤回(误签/告知内容变更场景);
 * 3) consent_type 限固定枚举集: admission/surgery/anesthesia/blood/special_drug/invasive/fall_risk/other;
 *    签名 base64 限 200KB(dataURL 全长), 防超大图片拖垮列表查询;
 * 4) 写操作校验就诊/记录归属机构与当前登录机构一致(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingConsentService {

    /** 状态: 0待签 1已签 2已撤回 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SIGNED = 1;
    public static final int STATUS_REVOKED = 2;

    /** 告知类型固定集(与 his_nursing_consent.consent_type 注释口径一致) */
    public static final Set<String> CONSENT_TYPES = new HashSet<>(Arrays.asList(
            "admission", "surgery", "anesthesia", "blood", "special_drug", "invasive", "fall_risk", "other"));

    /** 签名图片 base64 上限(约 200KB dataURL) */
    private static final int MAX_SIGNATURE_LENGTH = 200_000;
    /** 告知内容上限 */
    private static final int MAX_CONTENT_LENGTH = 20_000;

    private final HisNursingConsentMapper consentMapper;
    private final HisInpVisitMapper visitMapper;

    public NursingConsentService(HisNursingConsentMapper consentMapper, HisInpVisitMapper visitMapper) {
        this.consentMapper = consentMapper;
        this.visitMapper = visitMapper;
    }

    /* ================= 创建 / 签名 / 撤回 ================= */

    /** 创建告知书: 校验就诊归属与字段合法性 → 落库(status=0 待签)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingConsent create(NursingConsentDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        validateCreate(dto);

        Long patientId = dto.getPatientId() != null ? dto.getPatientId() : visit.getPatientId();
        if (patientId == null) {
            throw new BizException(400, "就诊记录缺少患者信息, 无法创建告知书");
        }

        HisNursingConsent c = new HisNursingConsent();
        c.setOrgId(visit.getOrgId());
        c.setInpVisitId(visit.getId());
        c.setPatientId(patientId);
        c.setConsentType(dto.getConsentType().trim());
        c.setConsentName(trimToNull(dto.getConsentName()));
        c.setContent(trimToNull(dto.getContent()));
        c.setWitnessName(trimToNull(dto.getWitnessName()));
        c.setStatus(STATUS_PENDING);
        consentMapper.insert(c);
        return c;
    }

    /**
     * 签名: 患者/家属手写签名图片(base64)落库, 回填签名人姓名/与患者关系/签名时间, 置 status=1 已签。
     * 患者与家属签名至少一方非空; 家属代签时 signerName/signerRelation 必填。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingConsent sign(Long id, String patientSigBase64, String familySigBase64,
                                  String signerName, String signerRelation) {
        boolean hasPatientSig = StringUtils.hasText(patientSigBase64);
        boolean hasFamilySig = StringUtils.hasText(familySigBase64);
        if (!hasPatientSig && !hasFamilySig) {
            throw new BizException(400, "患者签名与家属签名至少一方不能为空");
        }
        if (hasFamilySig && !StringUtils.hasText(signerName)) {
            throw new BizException(400, "家属代签时签名人姓名不能为空");
        }
        if (hasPatientSig && patientSigBase64.length() > MAX_SIGNATURE_LENGTH) {
            throw new BizException(400, "患者签名图片过大(限200KB)");
        }
        if (hasFamilySig && familySigBase64.length() > MAX_SIGNATURE_LENGTH) {
            throw new BizException(400, "家属签名图片过大(限200KB)");
        }
        if (StringUtils.hasText(signerRelation) && signerRelation.trim().length() > 50) {
            throw new BizException(400, "与患者关系不能超过50字");
        }

        HisNursingConsent c = requireStatus(id, STATUS_PENDING, "签名", "待签");
        c.setPatientSignatureBase64(hasPatientSig ? patientSigBase64.trim() : null);
        c.setFamilySignatureBase64(hasFamilySig ? familySigBase64.trim() : null);
        c.setSignerName(trimToNull(signerName));
        c.setSignerRelation(trimToNull(signerRelation));
        c.setSignTime(LocalDateTime.now());
        c.setStatus(STATUS_SIGNED);
        consentMapper.updateById(c);
        return c;
    }

    /** 撤回: 待签/已签告知书均可撤回置 status=2(签名图片保留留痕), 撤回后不可再签。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingConsent revoke(Long id) {
        HisNursingConsent c = consentMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "告知书不存在");
        }
        requireSameOrg(c.getOrgId());
        if (c.getStatus() == null || c.getStatus() == STATUS_REVOKED) {
            throw new BizException(400, "该告知书已撤回, 无需重复撤回");
        }
        c.setStatus(STATUS_REVOKED);
        consentMapper.updateById(c);
        return c;
    }

    /* ================= 查询 ================= */

    /** 告知书列表(按就诊): 状态升序(待签置顶), 签名时间/创建时间倒序(最新在前)。 */
    public List<HisNursingConsent> listByVisit(Long inpVisitId) {
        requireVisit(inpVisitId);
        return consentMapper.selectList(Wrappers.<HisNursingConsent>lambdaQuery()
                .eq(HisNursingConsent::getInpVisitId, inpVisitId)
                .orderByAsc(HisNursingConsent::getStatus)
                .orderByDesc(HisNursingConsent::getId));
    }

    /** 告知书详情。 */
    public HisNursingConsent getById(Long id) {
        if (id == null) {
            throw new BizException(400, "告知书ID不能为空");
        }
        HisNursingConsent c = consentMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "告知书不存在");
        }
        requireSameOrg(c.getOrgId());
        return c;
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 创建字段校验: 类型限固定枚举集, 名称 ≤100 字, 内容 ≤20000 字, 见证人 ≤50 字。 */
    private static void validateCreate(NursingConsentDTO dto) {
        if (!StringUtils.hasText(dto.getConsentType())) {
            throw new BizException(400, "告知类型不能为空");
        }
        if (!CONSENT_TYPES.contains(dto.getConsentType().trim())) {
            throw new BizException(400, "告知类型无效: " + dto.getConsentType()
                    + "(有效值: admission/surgery/anesthesia/blood/special_drug/invasive/fall_risk/other)");
        }
        if (dto.getConsentName() != null && dto.getConsentName().trim().length() > 100) {
            throw new BizException(400, "告知书名称不能超过100字");
        }
        if (dto.getContent() != null && dto.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new BizException(400, "告知内容过大(限2万字)");
        }
        if (dto.getWitnessName() != null && dto.getWitnessName().trim().length() > 50) {
            throw new BizException(400, "见证人不能超过50字");
        }
    }

    /** 指定状态必读: 存在 + 归属机构一致 + 状态匹配(签名前置校验)。 */
    private HisNursingConsent requireStatus(Long id, int expectStatus, String action, String statusLabel) {
        if (id == null) {
            throw new BizException(400, "告知书ID不能为空");
        }
        HisNursingConsent c = consentMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "告知书不存在");
        }
        requireSameOrg(c.getOrgId());
        if (c.getStatus() == null || c.getStatus() != expectStatus) {
            throw new BizException(400, "仅" + statusLabel + "告知书可" + action + "(当前状态: "
                    + statusName(c.getStatus()) + ")");
        }
        return c;
    }

    /** 状态中文名(报错展示) */
    private static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_PENDING:
                return "待签";
            case STATUS_SIGNED:
                return "已签";
            case STATUS_REVOKED:
                return "已撤回";
            default:
                return "未知(" + status + ")";
        }
    }

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行), 沿用住院护士站口径。 */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (!u.hasRole(Roles.SUPER_ADMIN) && visit.getOrgId() != null && u.getOrgId() != null
                && !visit.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该就诊不属于当前登录机构, 无权操作");
        }
        return visit;
    }

    /** 写操作机构校验: 记录归属机构须与当前登录机构一致(平台超管放行)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该告知书不属于当前登录机构, 无权操作");
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
