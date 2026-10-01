package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.inpatient.InpMedRecordDTO;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.emr.EmrElementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 住院病历服务: 九类结构化文书的草稿(1)→已提交(2)→已审核(3)三级流转;
 * 仅草稿可编辑, 提交/审核用乐观更新(状态未变才流转), 病历归属跟随就诊机构。
 * 结构化扩展: 支持按模板建档(createFromTemplate, 骨架JSON+宏预填充+书写截止时间),
 * 保存后自动触发质控评分(evaluateQuietly), 提供书写时限告警(getDeadlineAlerts)。
 * 富文本扩展: content 以 '<' 开头视为富文书 HTML(前端 wangEditor v5),
 * 入库前经 sanitizeHtml 白名单净化(标签白名单/on*属性/脚本协议), 防存储型 XSS。
 * T43 扩展: 每次保存/提交/审核前落 his_emr_version 版本快照(EmrVersionService);
 * 查房记录(record_type=4)提交按当前用户职称走三级签名链(住院医师提交→主治签→主任签),
 * 并提供带电子签名留痕校验的 signRecord 补签入口。
 */
@Slf4j
@Service
public class InpMedRecordService extends ServiceImpl<HisInpMedicalRecordMapper, HisInpMedicalRecord> {

    /** 时限告警时间展示格式 */
    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final HisPatientMapper patientMapper;
    private final EmrTemplateService templateService;
    private final EmrQualityService qualityService;
    private final EmrMacroService macroService;
    private final EmrVersionService emrVersionService;
    private final HisStaffMapper staffMapper;
    private final SignatureService signatureService;
    private final SafeJsonTool safeJsonTool;
    private final EmrElementService emrElementService;

    public InpMedRecordService(HisInpVisitMapper visitMapper, OrgAccessGuard guard,
                               HisPatientMapper patientMapper, EmrTemplateService templateService,
                               EmrQualityService qualityService, EmrMacroService macroService,
                               EmrVersionService emrVersionService, HisStaffMapper staffMapper,
                               SignatureService signatureService, SafeJsonTool safeJsonTool,
                               EmrElementService emrElementService) {
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.patientMapper = patientMapper;
        this.templateService = templateService;
        this.qualityService = qualityService;
        this.macroService = macroService;
        this.emrVersionService = emrVersionService;
        this.staffMapper = staffMapper;
        this.signatureService = signatureService;
        this.safeJsonTool = safeJsonTool;
        this.emrElementService = emrElementService;
    }

    /** 病历列表(recordType 可选筛选, 按记录时间倒序) */
    public List<HisInpMedicalRecord> listByVisit(Long visitId, Integer recordType) {
        requireVisit(visitId);
        List<HisInpMedicalRecord> list = lambdaQuery()
                .eq(HisInpMedicalRecord::getInpVisitId, visitId)
                .eq(recordType != null, HisInpMedicalRecord::getRecordType, recordType)
                .orderByDesc(HisInpMedicalRecord::getRecordTime)
                .orderByDesc(HisInpMedicalRecord::getId)
                .list();
        list.forEach(this::normalizeQualityDetail);
        return list;
    }

    /**
     * 详情/变更返回统一入口(T57 存量兼容): quality_detail 以 JSON 字符串下发前端,
     * 旧数据内 19 位雪花 ruleId 为裸数字, 前端二次 JSON.parse 会丢精度;
     * 返回前"解析 + 安全重序列化"补引号(非法 JSON 原样放行, 幂等)。
     */
    @Override
    public HisInpMedicalRecord getById(Serializable id) {
        return normalizeQualityDetail(super.getById(id));
    }

    /** 存量 qualityDetail 安全化(空/非法原样返回, 幂等) */
    private HisInpMedicalRecord normalizeQualityDetail(HisInpMedicalRecord r) {
        if (r != null && StringUtils.hasText(r.getQualityDetail())) {
            r.setQualityDetail(safeJsonTool.normalizeEmbeddedJson(r.getQualityDetail()));
        }
        return r;
    }

    /** 病历详情 */
    public HisInpMedicalRecord detail(Long id) {
        return requireRecord(id);
    }

    /** 新建病历(草稿态, 记录医生为当前登录医生) */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord create(InpMedRecordDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (dto.getRecordType() == null) {
            throw new BizException(400, "病历类型不能为空");
        }
        if (!StringUtils.hasText(dto.getTitle())) {
            throw new BizException(400, "病历标题不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        HisInpMedicalRecord r = new HisInpMedicalRecord();
        r.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        r.setInpVisitId(visit.getId());
        r.setRecordType(dto.getRecordType());
        r.setTitle(dto.getTitle().trim());
        r.setContent(sanitizeContent(dto.getContent()));
        if (StringUtils.hasText(dto.getStructureData())) {
            r.setStructureData(dto.getStructureData());
        }
        r.setRecordTime(LocalDateTime.now());
        r.setDoctorId(InpOrderService.currentDoctorId());
        r.setStatus(1);
        save(r);
        log.info("新建住院病历: id={}, visitId={}, recordType={}, title={}",
                r.getId(), visit.getId(), r.getRecordType(), r.getTitle());
        evaluateQuietly(r.getId());
        emrElementService.syncInpRecord(r.getId());
        return getById(r.getId());
    }

    /** 编辑病历(仅草稿态可编辑, 乐观更新保证并发提交安全) */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord update(Long id, InpMedRecordDTO dto) {
        HisInpMedicalRecord exist = requireRecord(id);
        if (exist.getStatus() == null || exist.getStatus() != 1) {
            throw new BizException("仅草稿状态的病历可编辑, 当前状态: " + exist.getStatus());
        }
        if (dto == null) {
            throw new BizException(400, "病历内容不能为空");
        }
        HisInpMedicalRecord r = new HisInpMedicalRecord();
        r.setId(id);
        if (dto.getRecordType() != null) {
            r.setRecordType(dto.getRecordType());
        }
        if (StringUtils.hasText(dto.getTitle())) {
            r.setTitle(dto.getTitle().trim());
        }
        if (dto.getContent() != null) {
            r.setContent(sanitizeContent(dto.getContent()));
        }
        if (StringUtils.hasText(dto.getStructureData())) {
            r.setStructureData(dto.getStructureData());
        }
        /* 版本快照(T43): 更新前落旧内容版本, 供版本历史回溯 */
        snapshotVersion(id, "save");
        boolean ok = update(r, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HisInpMedicalRecord>()
                .eq(HisInpMedicalRecord::getId, id)
                .eq(HisInpMedicalRecord::getStatus, 1));
        if (!ok) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }
        evaluateQuietly(id);
        emrElementService.syncInpRecord(id);
        return getById(id);
    }

    /**
     * 提交病历: 普通文书 1草稿→2已提交; 查房记录(record_type=4)按当前用户职称走三级签名链(T43)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord submit(Long id) {
        HisInpMedicalRecord exist = requireRecord(id);
        if (exist.getRecordType() != null && exist.getRecordType() == 4) {
            return submitRoundRecord(exist);
        }
        if (exist.getStatus() == null || exist.getStatus() != 1) {
            throw new BizException("仅草稿状态的病历可提交, 当前状态: " + exist.getStatus());
        }
        InpOrderService.currentDoctorId();
        /* 版本快照(T43): 提交前落内容版本 */
        snapshotVersion(id, "submit");
        boolean ok = lambdaUpdate()
                .set(HisInpMedicalRecord::getStatus, 2)
                .eq(HisInpMedicalRecord::getId, id)
                .eq(HisInpMedicalRecord::getStatus, 1)
                .update();
        if (!ok) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }
        log.info("提交住院病历: id={}, visitId={}", id, exist.getInpVisitId());
        emrElementService.syncInpRecord(id);
        return getById(id);
    }

    /**
     * 查房记录提交(三级签名链, T43): 按当前用户职称(CV08.30.005)决定当前动作——
     * 职称3中级(主治): 落主治签名 attending_sign_id/time 并推进 1→2(待主任签);
     * 职称1正高/2副高(主任): 落主任签名 director_sign_id/time 并推进 2→3(已审核);
     * 职称4师级/助理(住院医师级): 登记"待上级签"(重置主治签位), 状态保持1可继续修改;
     * 其余职称(未配职称/士级): 沿用普通文书 1→2 提交流程。
     */
    private HisInpMedicalRecord submitRoundRecord(HisInpMedicalRecord exist) {
        Long id = exist.getId();
        Long doctorId = InpOrderService.currentDoctorId();
        String titleCode = titleCodeOf(doctorId);
        if ("3".equals(titleCode)) {
            if (exist.getStatus() == null || exist.getStatus() != 1) {
                throw new BizException("主治签名要求记录处于草稿状态(住院医师已提交), 当前状态: " + exist.getStatus());
            }
            if (exist.getAttendingSignId() != null) {
                throw new BizException("主治签名已完成, 请勿重复签署");
            }
            snapshotVersion(id, "submit");
            boolean signed = lambdaUpdate()
                    .set(HisInpMedicalRecord::getAttendingSignId, doctorId)
                    .set(HisInpMedicalRecord::getAttendingSignTime, LocalDateTime.now())
                    .set(HisInpMedicalRecord::getStatus, 2)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 1)
                    .update();
            if (!signed) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("查房记录主治签名(提交入口): id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
            return getById(id);
        }
        if ("1".equals(titleCode) || "2".equals(titleCode)) {
            if (exist.getStatus() == null || exist.getStatus() != 2) {
                throw new BizException("主任签名要求主治医师已完成签名, 当前状态: " + exist.getStatus());
            }
            if (exist.getDirectorSignId() != null) {
                throw new BizException("主任签名已完成, 请勿重复签署");
            }
            snapshotVersion(id, "submit");
            boolean signed = lambdaUpdate()
                    .set(HisInpMedicalRecord::getDirectorSignId, doctorId)
                    .set(HisInpMedicalRecord::getDirectorSignTime, LocalDateTime.now())
                    .set(HisInpMedicalRecord::getStatus, 3)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 2)
                    .update();
            if (!signed) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("查房记录主任签名(提交入口): id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
            return getById(id);
        }
        if ("4".equals(titleCode)) {
            if (exist.getStatus() == null || exist.getStatus() != 1) {
                throw new BizException("仅草稿状态的病历可提交, 当前状态: " + exist.getStatus());
            }
            snapshotVersion(id, "submit");
            /* 重置主治签位并保持草稿态: 供上级医师继续修改后签名 */
            boolean ok = lambdaUpdate()
                    .set(HisInpMedicalRecord::getAttendingSignId, null)
                    .set(HisInpMedicalRecord::getAttendingSignTime, null)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 1)
                    .update();
            if (!ok) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("查房记录提交(住院医师级, 待上级签名): id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
            return getById(id);
        }
        if (exist.getStatus() == null || exist.getStatus() != 1) {
            throw new BizException("仅草稿状态的病历可提交, 当前状态: " + exist.getStatus());
        }
        snapshotVersion(id, "submit");
        boolean ok = lambdaUpdate()
                .set(HisInpMedicalRecord::getStatus, 2)
                .eq(HisInpMedicalRecord::getId, id)
                .eq(HisInpMedicalRecord::getStatus, 1)
                .update();
        if (!ok) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }
        log.info("提交住院病历(未配职称, 普通流程): id={}, visitId={}", id, exist.getInpVisitId());
        return getById(id);
    }

    /** 审核病历(2已提交 → 3已审核, 记录审核医生与审核时间, 乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord audit(Long id) {
        HisInpMedicalRecord exist = requireRecord(id);
        if (exist.getStatus() == null || exist.getStatus() != 2) {
            throw new BizException("仅已提交状态的病历可审核, 当前状态: " + exist.getStatus());
        }
        Long auditDoctorId = InpOrderService.currentDoctorId();
        /* 版本快照(T43): 审核前落内容版本 */
        snapshotVersion(id, "audit");
        boolean ok = lambdaUpdate()
                .set(HisInpMedicalRecord::getStatus, 3)
                .set(HisInpMedicalRecord::getAuditDoctorId, auditDoctorId)
                .set(HisInpMedicalRecord::getAuditTime, LocalDateTime.now())
                .eq(HisInpMedicalRecord::getId, id)
                .eq(HisInpMedicalRecord::getStatus, 2)
                .update();
        if (!ok) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }
        log.info("审核住院病历: id={}, visitId={}, auditDoctorId={}", id, exist.getInpVisitId(), auditDoctorId);
        return getById(id);
    }

    /* ================= 三级查房签名(T43) ================= */

    /**
     * 三级查房签名(电子签名留痕校验): signLevel=attending 主治签(1→2) / director 主任签(2→3)。
     * 校验: 仅查房记录(record_type=4)支持; 当前用户职称匹配(主治需中级3, 主任需正高1/副高2);
     * 前置状态与防重复签署; 须已在签名板以 round_sign 场景(refType=medical_record, refId=本记录)
     * 完成电子签名留痕(防绕过前端直调接口)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord signRecord(Long id, String signLevel) {
        HisInpMedicalRecord exist = requireRecord(id);
        if (exist.getRecordType() == null || exist.getRecordType() != 4) {
            throw new BizException(400, "仅查房记录(record_type=4)支持三级签名");
        }
        Long doctorId = InpOrderService.currentDoctorId();
        String titleCode = titleCodeOf(doctorId);
        if ("attending".equalsIgnoreCase(signLevel)) {
            if (!"3".equals(titleCode)) {
                throw new BizException(403, "仅主治医师(中级职称)可执行主治签名");
            }
            if (exist.getStatus() == null || exist.getStatus() != 1) {
                throw new BizException("主治签名要求记录处于草稿状态(住院医师已提交), 当前状态: " + exist.getStatus());
            }
            if (exist.getAttendingSignId() != null) {
                throw new BizException("主治签名已完成, 请勿重复签署");
            }
            requireRoundSignTrace(id);
            boolean signed = lambdaUpdate()
                    .set(HisInpMedicalRecord::getAttendingSignId, doctorId)
                    .set(HisInpMedicalRecord::getAttendingSignTime, LocalDateTime.now())
                    .set(HisInpMedicalRecord::getStatus, 2)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 1)
                    .isNull(HisInpMedicalRecord::getAttendingSignId)
                    .update();
            if (!signed) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("主治签名完成: id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
            return getById(id);
        }
        if ("director".equalsIgnoreCase(signLevel)) {
            if (!"1".equals(titleCode) && !"2".equals(titleCode)) {
                throw new BizException(403, "仅主任医师(正高/副高职称)可执行主任签名");
            }
            if (exist.getStatus() == null || exist.getStatus() != 2) {
                throw new BizException("主任签名要求主治医师已完成签名, 当前状态: " + exist.getStatus());
            }
            if (exist.getDirectorSignId() != null) {
                throw new BizException("主任签名已完成, 请勿重复签署");
            }
            requireRoundSignTrace(id);
            boolean signed = lambdaUpdate()
                    .set(HisInpMedicalRecord::getDirectorSignId, doctorId)
                    .set(HisInpMedicalRecord::getDirectorSignTime, LocalDateTime.now())
                    .set(HisInpMedicalRecord::getStatus, 3)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 2)
                    .isNull(HisInpMedicalRecord::getDirectorSignId)
                    .update();
            if (!signed) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("主任签名完成: id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
            return getById(id);
        }
        throw new BizException(400, "signLevel 仅支持 attending(主治签) 或 director(主任签)");
    }

    /* ================= 版本历史(T43) ================= */

    /** 病历版本列表(经归属校验后按版本号倒序) */
    public List<Map<String, Object>> versionList(Long recordId) {
        requireRecord(recordId);
        return emrVersionService.listVersions(recordId);
    }

    /** 指定版本详情(经版本→病历做机构归属校验) */
    public Map<String, Object> versionDetail(Long versionId) {
        Map<String, Object> version = emrVersionService.getVersion(versionId);
        requireRecord(asLong(version.get("recordId")));
        return version;
    }

    /** 两版本行级差异(限定同一病历, 防跨病历比较) */
    public Map<String, Object> versionDiff(Long v1, Long v2) {
        Map<String, Object> ver1 = emrVersionService.getVersion(v1);
        Map<String, Object> ver2 = emrVersionService.getVersion(v2);
        Long recordId1 = asLong(ver1.get("recordId"));
        Long recordId2 = asLong(ver2.get("recordId"));
        if (recordId1 == null || !recordId1.equals(recordId2)) {
            throw new BizException(400, "仅支持同一病历的版本对比");
        }
        requireRecord(recordId1);
        return emrVersionService.diffVersions(v1, v2);
    }

    /* ================= 内部实现 ================= */

    /** 病历存在性 + 经就诊做机构归属校验 */
    private HisInpMedicalRecord requireRecord(Long id) {
        HisInpMedicalRecord r = getById(id);
        if (r == null) {
            throw new BizException(400, "病历记录不存在");
        }
        if (r.getInpVisitId() != null) {
            requireVisit(r.getInpVisitId());
        }
        return r;
    }

    /** 就诊归属校验: 不存在报400; 非牵头机构仅可访问本机构就诊(牵头机构全医共体) */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问住院数据");
        }
        if (!scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        return v;
    }

    /* ---------- T43 三级签名/版本快照 私有助手 ---------- */

    /** 版本快照(静默): 落 his_emr_version 一行旧内容版本; 失败仅告警, 不影响病历主流程 */
    private void snapshotVersion(Long recordId, String operateType) {
        LoginUser user = UserContext.get();
        if (user == null) {
            return;
        }
        try {
            emrVersionService.saveVersion(recordId, operateType, user.getUserId(),
                    StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername());
        } catch (Exception e) {
            log.warn("病历版本快照失败(不影响主流程): recordId={}, operateType={}, err={}",
                    recordId, operateType, e.getMessage());
        }
    }

    /** 职工职称编码(CV08.30.005: 1正高 2副高 3中级 4师级/助理 5士级), 无档案返回 null */
    private String titleCodeOf(Long staffId) {
        if (staffId == null) {
            return null;
        }
        HisStaff staff = staffMapper.selectById(staffId);
        return staff == null ? null : staff.getTitleCode();
    }

    /** 电子签名留痕校验: 当前用户须已在签名板以 round_sign 场景对本文书完成电子签名 */
    private void requireRoundSignTrace(Long recordId) {
        Long userId = UserContext.userId();
        List<Map<String, Object>> logs = signatureService.getSignatureLogs("medical_record", recordId);
        for (Map<String, Object> logRow : logs) {
            Object uid = logRow.get("userId");
            Object action = logRow.get("actionType");
            if (uid != null && String.valueOf(uid).equals(String.valueOf(userId))
                    && "round_sign".equals(String.valueOf(action))) {
                return;
            }
        }
        throw new BizException(400, "未检测到本次电子签名留痕, 请通过签名板完成签署后再提交");
    }

    /** 数字对象安全转 Long(版本 Map 取值), 非法返回 null */
    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ================= 结构化模板建档 / 时限质控 ================= */

    /**
     * 按结构化模板新建病历: 读取模板字段定义 → defaultMacro 宏预填充骨架 JSON →
     * 按模板类别计算书写截止时间 → 落库草稿并即时质控。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord createFromTemplate(Long visitId, Long templateId, Long doctorId) {
        HisInpVisit visit = requireVisit(visitId);
        if (templateId == null) {
            throw new BizException(400, "病历模板ID不能为空");
        }
        HisEmrTemplate tpl = templateService.getTemplate(templateId).getData();
        if (tpl.getStatus() != null && tpl.getStatus() == 0) {
            throw new BizException(400, "病历模板已停用: " + tpl.getTemplateCode());
        }
        List<Map<String, String>> fieldDefs = templateService.getFieldDefs(templateId);
        /* 批量解析 defaultMacro 值(患者/就诊/诊断等即时取值) */
        Set<String> macroCodes = new LinkedHashSet<>();
        for (Map<String, String> f : fieldDefs) {
            String macro = f.get("defaultMacro");
            if (StringUtils.hasText(macro)) {
                macroCodes.add(macro);
            }
        }
        Map<String, String> macroValues = new LinkedHashMap<>();
        if (!macroCodes.isEmpty()) {
            R<Map<String, String>> resolved = macroService.resolveMacros(visitId, new ArrayList<>(macroCodes));
            if (resolved != null && resolved.getData() != null) {
                macroValues.putAll(resolved.getData());
            }
        }
        JSONObject data = new JSONObject();
        for (Map<String, String> f : fieldDefs) {
            String key = f.get("fieldKey");
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String val = "";
            String macro = f.get("defaultMacro");
            if (StringUtils.hasText(macro) && StringUtils.hasText(macroValues.get(macro))) {
                val = macroValues.get(macro);
            }
            if (!StringUtils.hasText(val) && StringUtils.hasText(f.get("defaultValue"))) {
                val = f.get("defaultValue");
            }
            data.put(key, val);
        }
        HisInpMedicalRecord r = new HisInpMedicalRecord();
        r.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        r.setInpVisitId(visit.getId());
        r.setRecordType(tpl.getRecordType());
        r.setTitle(tpl.getTemplateName());
        r.setTemplateId(tpl.getId());
        r.setStructureData(JSON.toJSONString(data));
        r.setRecordTime(LocalDateTime.now());
        r.setDoctorId(doctorId != null ? doctorId : InpOrderService.currentDoctorId());
        r.setDeadlineTime(computeDeadline(tpl.getTemplateCategory(), visit, LocalDateTime.now()));
        r.setStatus(1);
        save(r);
        log.info("按模板建档: id={}, visitId={}, template={}, deadline={}",
                r.getId(), visit.getId(), tpl.getTemplateCode(), r.getDeadlineTime());
        evaluateQuietly(r.getId());
        emrElementService.syncInpRecord(r.getId());
        return getById(r.getId());
    }

    /** 书写时限告警: 草稿态且(已超时 或 2小时内将超时), 按截止时间升序, 附就诊/患者信息 */
    public R<List<Map<String, Object>>> getDeadlineAlerts(Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        LocalDateTime now = LocalDateTime.now();
        List<HisInpMedicalRecord> records = lambdaQuery()
                .eq(scope != null, HisInpMedicalRecord::getOrgId, scope)
                .eq(HisInpMedicalRecord::getStatus, 1)
                .isNotNull(HisInpMedicalRecord::getDeadlineTime)
                .le(HisInpMedicalRecord::getDeadlineTime, now.plusHours(2))
                .orderByAsc(HisInpMedicalRecord::getDeadlineTime)
                .list();
        List<Map<String, Object>> list = new ArrayList<>();
        for (HisInpMedicalRecord rec : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", rec.getId());
            m.put("visitId", rec.getInpVisitId());
            m.put("recordType", rec.getRecordType());
            m.put("title", rec.getTitle());
            m.put("deadlineTime", rec.getDeadlineTime().format(DEADLINE_FMT));
            m.put("overdue", rec.getDeadlineTime().isBefore(now));
            m.put("remainingMinutes", Duration.between(now, rec.getDeadlineTime()).toMinutes());
            if (rec.getInpVisitId() != null) {
                HisInpVisit v = visitMapper.selectById(rec.getInpVisitId());
                if (v != null) {
                    m.put("inpNo", v.getInpNo());
                    if (v.getPatientId() != null) {
                        HisPatient p = patientMapper.selectById(v.getPatientId());
                        m.put("patientName", p == null ? null : p.getName());
                    }
                }
            }
            list.add(m);
        }
        return R.ok(list);
    }

    /** 按模板类别计算书写截止时间: 入院记录+24h / 首次病程+8h / 查房+48h / 出院小结+3d / 其余+24h */
    private LocalDateTime computeDeadline(Integer category, HisInpVisit visit, LocalDateTime now) {
        if (category == null) {
            return null;
        }
        switch (category) {
            case 1:
                return visit.getAdmitDate() != null ? visit.getAdmitDate().plusHours(24) : now.plusHours(24);
            case 2:
                return visit.getAdmitDate() != null ? visit.getAdmitDate().plusHours(8) : now.plusHours(8);
            case 4:
                return visit.getAdmitDate() != null ? visit.getAdmitDate().plusHours(48) : now.plusHours(48);
            case 7:
                return visit.getDischargeDate() != null ? visit.getDischargeDate().plusDays(3) : now.plusDays(3);
            case 3:
            case 5:
            case 6:
            case 8:
                return now.plusHours(24);
            default:
                return null;
        }
    }

    /** 静默质控: 评分失败仅告警, 不影响病历保存主流程 */
    private void evaluateQuietly(Long recordId) {
        try {
            qualityService.evaluateQuality(recordId);
        } catch (Exception e) {
            log.warn("病历质控评分失败(不影响保存): recordId={}, err={}", recordId, e.getMessage());
        }
    }

    /* ================= 富文本 HTML 净化(白名单, 防存储型 XSS) ================= */

    /** 富文书标签白名单: 仅病历排版标签, 不含任何可执行/可提交语义标签 */
    private static final Set<String> HTML_TAG_WHITELIST = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "b", "em", "i", "u", "s", "sub", "sup",
            "ul", "ol", "li", "blockquote", "pre",
            "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "colgroup", "col",
            "span", "div", "img")));

    /** 成对危险标签(内容即代码, 连同内容整体移除); form/textarea/svg 等内容可能是正文, 由白名单剥离保留文字 */
    private static final Pattern DANGEROUS_PAIR = Pattern.compile(
            "<\\s*(script|iframe|object|embed|style|noscript|template)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** void 型危险标签(自闭合/无内容语义, 整体移除) */
    private static final Pattern DANGEROUS_VOID = Pattern.compile(
            "<\\s*/?\\s*(link|meta|input|base|basefont|frame|frameset|applet|param|source|track|area|isindex)\\b[^>]*/?\\s*>",
            Pattern.CASE_INSENSITIVE);

    /** on* 事件属性(双引号/单引号/裸值三态) */
    private static final Pattern ON_ATTR = Pattern.compile(
            "\\s+on[a-zA-Z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** 可承载 URL 的属性(做危险协议检测) */
    private static final Pattern URL_ATTR = Pattern.compile(
            "(\\s(?:href|src|action|formaction|background|poster|cite|longdesc|usemap|data|xlink\\:href)\\s*=\\s*)(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
            Pattern.CASE_INSENSITIVE);

    /** style 属性 */
    private static final Pattern STYLE_ATTR = Pattern.compile(
            "(\\sstyle\\s*=\\s*)(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** 标签整体匹配(开放/闭合/自闭合, 属性区容忍引号内任意字符) */
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)\\s*>");

    /**
     * 富文本 HTML 净化(无 jsoup, 正则白名单实现):
     * ① 移除注释与危险标签块(script/iframe/style 等内容即代码的连同内容移除) → ② 标签级白名单过滤
     * (白名单外剥离标签保留文字, 含 form/textarea/svg 等) → ③ 属性级清理(on* 事件/脚本协议/expression 样式);
     * img 仅放行 data:|/api/ 开头 src, span 仅保留 style 属性。
     */
    public String sanitizeHtml(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String s = html.replaceAll("(?is)<!--.*?-->", "");
        s = DANGEROUS_PAIR.matcher(s).replaceAll("");
        s = DANGEROUS_VOID.matcher(s).replaceAll("");
        Matcher m = TAG_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(sanitizeTag(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** content 统一净化入口: '<' 开头(富文书 HTML)净化后返回, JSON/纯文本(结构化与旧数据)原样放行 */
    private String sanitizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        if (content.trim().charAt(0) == '<') {
            return sanitizeHtml(content);
        }
        return content;
    }

    /** 单标签净化: 白名单外返回空串(保留标签间文字); img 校验 src 来源; span 仅留 style; 其余重建安全属性 */
    private String sanitizeTag(Matcher m) {
        String closing = m.group(1);
        String tag = m.group(2).toLowerCase();
        String attrs = m.group(3);
        if (!HTML_TAG_WHITELIST.contains(tag)) {
            return "";
        }
        if (!closing.isEmpty()) {
            return "</" + tag + ">";
        }
        if ("img".equals(tag)) {
            String src = attrValue(attrs, "src");
            if (src == null || !(startsWithIgnoreCase(src, "data:") || src.startsWith("/api/"))) {
                return "";
            }
            return "<img" + keepAttrs(attrs, "src", "alt", "width", "height") + ">";
        }
        if ("span".equals(tag)) {
            return "<span" + keepStyleAttr(attrs) + ">";
        }
        return "<" + tag + cleanAttrs(attrs) + (m.group(4).isEmpty() ? ">" : " />");
    }

    /** 白名单标签属性清理: 去 on* 事件属性、危险协议属性值置空、style 值去 expression/脚本协议 */
    private String cleanAttrs(String attrs) {
        String s = ON_ATTR.matcher(attrs).replaceAll("");
        Matcher proto = URL_ATTR.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (proto.find()) {
            String rep = hasDangerProto(stripQuotes(proto.group(2))) ? proto.group(1) + "\"\"" : proto.group(0);
            proto.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        proto.appendTail(sb);
        Matcher style = STYLE_ATTR.matcher(sb.toString());
        StringBuffer out = new StringBuffer(sb.length());
        while (style.find()) {
            String rep = style.group(1) + "\"" + cleanStyleValue(stripQuotes(style.group(2))).replace("\"", "&quot;") + "\"";
            style.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        style.appendTail(out);
        return out.toString();
    }

    /** 仅保留 style 属性(span 专用): 值经 expression/脚本协议清理 */
    private String keepStyleAttr(String attrs) {
        Matcher m = STYLE_ATTR.matcher(attrs);
        if (!m.find()) {
            return "";
        }
        return " style=\"" + cleanStyleValue(stripQuotes(m.group(2))).replace("\"", "&quot;") + "\"";
    }

    /** 保留指定属性并重建为双引号形式(未命中的属性丢弃) */
    private String keepAttrs(String attrs, String... names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            Matcher m = Pattern.compile("\\s" + Pattern.quote(name) + "\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
                    Pattern.CASE_INSENSITIVE).matcher(attrs);
            if (m.find()) {
                sb.append(' ').append(name).append("=\"")
                        .append(stripQuotes(m.group(1)).replace("\"", "&quot;")).append('"');
            }
        }
        return sb.toString();
    }

    /** 提取属性值(去引号/trim), 属性不存在返回 null */
    private String attrValue(String attrs, String name) {
        Matcher m = Pattern.compile("\\s" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                Pattern.CASE_INSENSITIVE).matcher(attrs);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1).trim() : (m.group(2) != null ? m.group(2).trim() : m.group(3).trim());
    }

    /** 属性值是否以危险协议开头(去空白防拆分绕过) */
    private boolean hasDangerProto(String value) {
        String v = value.replaceAll("\\s+", "").toLowerCase();
        return v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("livescript:")
                || v.startsWith("data:text/html") || v.startsWith("mocha:");
    }

    /** style 值清理: 去 IE expression() 与脚本协议前缀 */
    private String cleanStyleValue(String raw) {
        return raw.replaceAll("(?i)expression\\s*\\(", "(").replaceAll("(?i)(javascript|vbscript|livescript)\\s*:", "");
    }

    /** 去除属性值两端配对引号 */
    private String stripQuotes(String v) {
        String s = v == null ? "" : v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private boolean startsWithIgnoreCase(String s, String prefix) {
        return s != null && s.length() >= prefix.length() && s.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }
}
