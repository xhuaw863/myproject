package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.inpatient.InpMedRecordDTO;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.emr.HisEmrQcDefect;
import com.yb.hi.entity.emr.HisEmrQcNode;
import com.yb.hi.entity.emr.HisEmrSignatureRule;
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
import com.yb.hi.mapper.emr.HisEmrQcNodeMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.emr.EmrAuditService;
import com.yb.hi.service.emr.EmrDocumentService;
import com.yb.hi.service.emr.EmrElementService;
import com.yb.hi.service.emr.EmrEventPublisher;
import com.yb.hi.service.emr.EmrEventType;
import com.yb.hi.service.emr.EmrSignatureService;
import com.yb.hi.service.emr.EmrTimelinessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.lang.reflect.Method;
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
 * 病历P2(Tiptap)扩展: 记录类型标签扩展至 15 类(10病案首页/11交接班/12转科/13知情同意/14讨论/15会诊);
 * 模板 Tiptap 文档双轨落库(EmrDocumentService AES-GCM 加密 content + 明文 structureData),
 * 详情按格式探测解密('<' HTML/{'/'[' 旧JSON 原样透传); 全类型操作审计留痕(EmrAuditService);
 * 提交/签署改为签名规则链驱动(EmrSignatureService.getSignatureChain/signByRule), 按链推送 SSE 提醒;
 * 新增医嘱联动建档(createFromOrder)与按文书类别分组列表(listGroupedByType)。
 */
@Slf4j
@Service
public class InpMedRecordService extends ServiceImpl<HisInpMedicalRecordMapper, HisInpMedicalRecord> {

    /** 时限告警时间展示格式 */
    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Tiptap 文档宏占位符 {macroCode}(与 EmrMacroService.MACRO_PATTERN 同口径) */
    private static final Pattern MACRO_TOKEN = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");

    /** 记录类型标签(1-15): 与 his_emr_signature_rule.record_type 口径一致(10-15 为病历P2扩展类型) */
    private static final Map<Integer, String> RECORD_TYPE_LABELS;
    static {
        Map<Integer, String> labels = new LinkedHashMap<>();
        labels.put(1, "入院记录");
        labels.put(2, "首次病程记录");
        labels.put(3, "日常病程记录");
        labels.put(4, "查房记录");
        labels.put(5, "术前小结");
        labels.put(6, "手术记录");
        labels.put(7, "术后病程记录");
        labels.put(8, "出院小结");
        labels.put(9, "死亡记录");
        labels.put(10, "病案首页");
        labels.put(11, "交接班记录");
        labels.put(12, "转科记录");
        labels.put(13, "知情同意书");
        labels.put(14, "讨论记录");
        labels.put(15, "会诊记录");
        RECORD_TYPE_LABELS = Collections.unmodifiableMap(labels);
    }

    /** 分组列表口径: 入院出院类[1,8,9,10] / 病程类[2,3,4,7] / 手术类[5,6] / 其余归"其他"[11-15] */
    private static final List<Integer> GROUP_ADMIT_DISCHARGE = Arrays.asList(1, 8, 9, 10);
    private static final List<Integer> GROUP_PROGRESS = Arrays.asList(2, 3, 4, 7);
    private static final List<Integer> GROUP_SURGERY = Arrays.asList(5, 6);

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
    private final EmrAuditService emrAuditService;
    private final EmrSignatureService emrSignatureService;
    private final EmrEventPublisher emrEventPublisher;
    private final EmrDocumentService emrDocumentService;
    private final HisEmrQcNodeMapper qcNodeMapper;

    /** P5a-2 并行交付的时效质控服务(required=false: 未就绪时为 null, 质控降级跳过不阻断签名) */
    @Autowired(required = false)
    private EmrTimelinessService emrTimelinessService;

    public InpMedRecordService(HisInpVisitMapper visitMapper, OrgAccessGuard guard,
                               HisPatientMapper patientMapper, EmrTemplateService templateService,
                               EmrQualityService qualityService, EmrMacroService macroService,
                               EmrVersionService emrVersionService, HisStaffMapper staffMapper,
                               SignatureService signatureService, SafeJsonTool safeJsonTool,
                               EmrElementService emrElementService, EmrAuditService emrAuditService,
                               EmrSignatureService emrSignatureService, EmrEventPublisher emrEventPublisher,
                               EmrDocumentService emrDocumentService, HisEmrQcNodeMapper qcNodeMapper) {
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
        this.emrAuditService = emrAuditService;
        this.emrSignatureService = emrSignatureService;
        this.emrEventPublisher = emrEventPublisher;
        this.emrDocumentService = emrDocumentService;
        this.qcNodeMapper = qcNodeMapper;
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

    /** 病历详情(格式探测): content 为密文时解密回明文 Tiptap JSON 供前端渲染;
     * '<' 开头(富文书 HTML)/'{'/'[' 开头(旧明文 JSON)原样返回; 落 VIEW 审计(仅显式详情, 列表不落)。 */
    public HisInpMedicalRecord detail(Long id) {
        HisInpMedicalRecord r = requireRecord(id);
        String content = r.getContent();
        if (StringUtils.hasText(content)) {
            char c = content.trim().charAt(0);
            if (c != '<' && c != '{' && c != '[' && looksLikeEncrypted(content.trim())) {
                r.setContent(emrDocumentService.loadDocument(1, id, content));
            }
        }
        auditQuietly(id, "VIEW", null);
        return r;
    }

    /** 新建病历(草稿态, 记录医生为当前登录医生; 模板 Tiptap 文档/前端 Tiptap JSON 经加密双轨落库) */
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
        /* Tiptap 双轨(病历P2): 初始内容优先取前端 Tiptap JSON, 否则取模板 document 并做宏解析 */
        String tiptapJson = null;
        if (StringUtils.hasText(dto.getContent()) && isTiptapDocument(dto.getContent())) {
            tiptapJson = resolveMacrosInTiptap(dto.getContent(), visit.getId());
        }
        if (dto.getTemplateId() != null) {
            HisEmrTemplate tpl = templateService.getTemplate(dto.getTemplateId()).getData();
            if (tpl.getStatus() != null && tpl.getStatus() == 0) {
                throw new BizException(400, "病历模板已停用: " + tpl.getTemplateCode());
            }
            r.setTemplateId(tpl.getId());
            if (tiptapJson == null && StringUtils.hasText(tpl.getDocument())) {
                tiptapJson = resolveMacrosInTiptap(tpl.getDocument(), visit.getId());
            }
        }
        if (tiptapJson != null) {
            r.setContent(emrDocumentService.encrypt(tiptapJson));
            r.setStructureData(tiptapJson);
        } else {
            r.setContent(sanitizeContent(dto.getContent()));
            if (StringUtils.hasText(dto.getStructureData())) {
                r.setStructureData(dto.getStructureData());
            }
        }
        r.setRecordTime(LocalDateTime.now());
        r.setDoctorId(InpOrderService.currentDoctorId());
        r.setStatus(1);
        save(r);
        log.info("新建住院病历: id={}, visitId={}, recordType={}, title={}, tiptap={}",
                r.getId(), visit.getId(), r.getRecordType(), r.getTitle(), tiptapJson != null);
        auditQuietly(r.getId(), "CREATE", null);
        evaluateQuietly(r.getId());
        emrElementService.syncInpRecord(r.getId());
        if (tiptapJson != null) {
            /* 要素同步顺序: syncInpRecord 对 Tiptap 文档为扁平口径(先删后插抽不到值), 需在其后按文档全量替换 */
            emrElementService.syncFromTiptap(1, r.getId(), tiptapJson);
        }
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
        if (dto.getTemplateId() != null) {
            r.setTemplateId(dto.getTemplateId());
        }
        /* Tiptap 双轨(病历P2): 前端提交 Tiptap JSON → 密文落 content + 明文落 structureData */
        String tiptapJson = null;
        if (dto.getContent() != null) {
            if (isTiptapDocument(dto.getContent())) {
                tiptapJson = StringUtils.hasText(dto.getStructureData()) ? dto.getStructureData() : dto.getContent();
                r.setContent(emrDocumentService.encrypt(tiptapJson));
                r.setStructureData(tiptapJson);
            } else {
                /* 旧口径兼容: 富文书 HTML 白名单净化; 旧 JSON/密文原样放行(密文不以'<'开头) */
                r.setContent(sanitizeContent(dto.getContent()));
            }
        }
        if (tiptapJson == null && StringUtils.hasText(dto.getStructureData())) {
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
        auditQuietly(id, "UPDATE", null);
        evaluateQuietly(id);
        emrElementService.syncInpRecord(id);
        if (tiptapJson != null) {
            emrElementService.syncFromTiptap(1, id, tiptapJson);
        }
        return getById(id);
    }

    /**
     * 提交病历(签名规则链驱动, 病历P2): 按 record_type 取 his_emr_signature_rule 链——
     * 链缺失/单环节: 直接 1草稿→2已提交; 多环节按当前用户职称档位匹配环节:
     * 作者/住院医师环节保持草稿(登记环节签名并 SSE 通知下一签署人), 主治环节落主治签名列并 1→2,
     * 主任环节落主任签名列并 2→3; 原查房记录三级签名链经规则链等价兼容。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord submit(Long id) {
        HisInpMedicalRecord exist = requireRecord(id);
        List<HisEmrSignatureRule> chain = requiredChainOf(exist);
        if (chain.size() <= 1) {
            return submitSimple(exist);
        }
        Long doctorId = InpOrderService.currentDoctorId();
        String titleCode = titleCodeOf(doctorId);
        int idx = matchStageIndex(chain, titleCode);
        if (idx < 0) {
            return submitSimple(exist);
        }
        String stage = chain.get(idx).getStage();
        if (isAuthorStage(stage)) {
            /* 作者/住院医师环节: 保持草稿可继续修改, 登记环节签名供后续顺序校验, 通知下一签署人 */
            if (exist.getStatus() == null || exist.getStatus() != 1) {
                throw new BizException("仅草稿状态的病历可提交, 当前状态: " + exist.getStatus());
            }
            snapshotVersion(id, "submit");
            boolean ok = lambdaUpdate()
                    .set(HisInpMedicalRecord::getAttendingSignId, null)
                    .set(HisInpMedicalRecord::getAttendingSignTime, null)
                    .set(HisInpMedicalRecord::getDirectorSignId, null)
                    .set(HisInpMedicalRecord::getDirectorSignTime, null)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 1)
                    .update();
            if (!ok) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            markStageSignature(id, stage);
            log.info("病历提交({}环节, 待上级签名): id={}, visitId={}, doctorId={}",
                    stage, id, exist.getInpVisitId(), doctorId);
        } else if ("attending".equals(stage)) {
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
            markStageSignature(id, "attending");
            log.info("病历提交(主治签名, 提交入口): id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
        } else if ("director".equals(stage)) {
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
            markStageSignature(id, "director");
            log.info("病历提交(主任签名, 提交入口): id={}, visitId={}, doctorId={}", id, exist.getInpVisitId(), doctorId);
        } else {
            return submitSimple(exist);
        }
        auditQuietly(id, "SUBMIT", null);
        publishNextSignatureRequired(exist, chain, idx + 1);
        return getById(id);
    }

    /** 常规提交(无多环节链/职称未匹配): 1草稿→2已提交, 落版本快照与审计 */
    private HisInpMedicalRecord submitSimple(HisInpMedicalRecord exist) {
        Long id = exist.getId();
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
        auditQuietly(id, "SUBMIT", null);
        emrElementService.syncInpRecord(id);
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
        auditQuietly(id, "AUDIT", null);
        return getById(id);
    }

    /** 删除病历(仅草稿态可删, 逻辑删除并落 DELETE 审计) */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        HisInpMedicalRecord exist = requireRecord(id);
        if (exist.getStatus() == null || exist.getStatus() != 1) {
            throw new BizException("仅草稿状态的病历可删除, 当前状态: " + exist.getStatus());
        }
        removeById(id);
        auditQuietly(id, "DELETE", null);
        log.info("删除住院病历: id={}, visitId={}, recordType={}", id, exist.getInpVisitId(), exist.getRecordType());
    }

    /* ================= 规则驱动签署(病历P2, 兼容原三级查房签名 T43) ================= */

    /**
     * 规则驱动签署(电子签名留痕校验): signLevel=author/resident/attending(主治签)/director(主任签)。
     * 经 EmrSignatureService.signByRule 校验环节顺序(前序必需环节须已签)与签署人职称档位(CV08.30.005);
     * 主治签名完成后 1→2, 主任签名完成后 2→3; 须已在签名板以 round_sign 场景(refType=medical_record,
     * refId=本记录)完成电子签名留痕(防绕过前端直调接口); 签署后按链推 SSE(下一环节待签/链完成)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpMedicalRecord signRecord(Long id, String signLevel) {
        HisInpMedicalRecord exist = requireRecord(id);
        String level = StringUtils.hasText(signLevel) ? signLevel.trim().toLowerCase() : "";
        if (!"author".equals(level) && !"resident".equals(level)
                && !"attending".equals(level) && !"director".equals(level)) {
            throw new BizException(400, "signLevel 仅支持 author/resident/attending(主治签)/director(主任签)");
        }
        if ("attending".equals(level)) {
            if (exist.getStatus() == null || exist.getStatus() != 1) {
                throw new BizException("主治签名要求记录处于草稿状态(住院医师已提交), 当前状态: " + exist.getStatus());
            }
            if (exist.getAttendingSignId() != null) {
                throw new BizException("主治签名已完成, 请勿重复签署");
            }
        } else if ("director".equals(level)) {
            if (exist.getStatus() == null || exist.getStatus() != 2) {
                throw new BizException("主任签名要求主治医师已完成签名, 当前状态: " + exist.getStatus());
            }
            if (exist.getDirectorSignId() != null) {
                throw new BizException("主任签名已完成, 请勿重复签署");
            }
        }
        requireRoundSignTrace(id);
        /* --- P5a-4: 签名前质控检查(禁止→拒绝/拦截→阻断, 均结构化返回不抛异常; 提醒→放行) --- */
        Map<String, Object> qcResult = runSignQualityCheck(id);
        List<Map<String, Object>> qcForbidden = qcList(qcResult.get("forbidden"));
        List<Map<String, Object>> qcBlocks = qcList(qcResult.get("blocks"));
        if (!qcForbidden.isEmpty() || !qcBlocks.isEmpty()) {
            log.info("签名被质控{}: id={}, visitId={}, forbidden={}, blocks={}, warnings={}",
                    qcForbidden.isEmpty() ? "拦截" : "禁止", id, exist.getInpVisitId(),
                    qcForbidden.size(), qcBlocks.size(), qcList(qcResult.get("warnings")).size());
            HisInpMedicalRecord unsigned = getById(id);
            unsigned.setQcResult(qcResult);
            return unsigned;
        }
        R<Map<String, Object>> result = emrSignatureService.signByRule(id, 1, level, null, null);
        if ("attending".equals(level)) {
            /* sign() 已回写主治签名列(reinforceChainColumns), 此处仅乐观推进状态 */
            boolean signed = lambdaUpdate()
                    .set(HisInpMedicalRecord::getStatus, 2)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 1)
                    .update();
            if (!signed) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("主治签名完成: id={}, visitId={}", id, exist.getInpVisitId());
        } else if ("director".equals(level)) {
            boolean signed = lambdaUpdate()
                    .set(HisInpMedicalRecord::getStatus, 3)
                    .eq(HisInpMedicalRecord::getId, id)
                    .eq(HisInpMedicalRecord::getStatus, 2)
                    .update();
            if (!signed) {
                throw new BizException("病历状态已变化, 请刷新后重试");
            }
            log.info("主任签名完成: id={}, visitId={}", id, exist.getInpVisitId());
        } else {
            log.info("病历环节签名完成: id={}, visitId={}, stage={}", id, exist.getInpVisitId(), level);
        }
        auditQuietly(id, "SIGN", "stage=" + level);
        publishSignatureOutcome(exist, result);
        HisInpMedicalRecord signed = getById(id);
        /* 提醒级缺陷随签名结果下发(签名已放行), 供前端提示整改; passed=true */
        signed.setQcResult(qcResult);
        return signed;
    }

    /* ================= 签名前质控拦截(P5a-4) ================= */

    /**
     * 签名前质控检查: 内涵质控走 EmrQualityService.evaluateContent(stage=1运行),
     * 时效质控走 EmrTimelinessService.checkTimeliness(P5a-2 并行交付, 未就绪/异常一律降级跳过, 不阻断签名);
     * severity 归级: 3禁止→拒绝签名 / 2拦截→阻断签名 / 其余提醒→放行; 结果落 his_emr_qc_node 签名节点留痕(静默)。
     * 返回 {passed, warnings[], blocks[], forbidden[]}。
     */
    private Map<String, Object> runSignQualityCheck(Long recordId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> forbidden = new ArrayList<>();
        try {
            /* 1. 内涵质控: 运行环节缺陷重评(完整/逻辑/规范/内涵等)。
             * evaluateContent 由 P5a-3 并行交付, 经桥接调用, 未就绪/异常时降级为空(不阻断签名);
             * 稳定交付后可还原为直接调用: qualityService.evaluateContent(recordId, 1) */
            List<HisEmrQcDefect> defects = evaluateContentQuietly(recordId);
            for (HisEmrQcDefect d : defects) {
                classifyDefect(defectItem(d), d.getSeverity(), warnings, blocks, forbidden);
            }
            /* 2. 时效质控(缺失即跳过): 超时→拦截级, 临近超时→提醒级 */
            if (emrTimelinessService != null) {
                collectTimeliness(emrTimelinessService.checkTimeliness(recordId), warnings, blocks, forbidden);
            }
        } catch (Exception e) {
            /* 质控失败降级: 与 evaluateQuietly 同姿势, 不阻断签名主流程 */
            log.warn("签名前质控检查异常, 降级跳过: recordId={}, err={}", recordId, e.getMessage());
        }
        result.put("passed", forbidden.isEmpty() && blocks.isEmpty());
        result.put("warnings", warnings);
        result.put("blocks", blocks);
        result.put("forbidden", forbidden);
        logQcNodeQuietly(recordId, result);
        return result;
    }

    /**
     * 内涵质控桥接(P5a-3 并行交付 evaluateContent): 反射定位方法后调用, 兼容 (Long,Integer)/(long,int) 两种参数声明;
     * 方法未交付/执行异常一律降级为空列表(质控跳过), 不阻断签名主流程。
     * P5a-3 稳定交付后可还原为直接调用 qualityService.evaluateContent(recordId, 1)。
     */
    @SuppressWarnings("unchecked")
    private List<HisEmrQcDefect> evaluateContentQuietly(Long recordId) {
        Method m = findEvaluateContent();
        if (m == null) {
            log.info("内涵质控 evaluateContent 尚未就绪(P5a-3 并行交付中), 本次签名质控跳过内涵检查: recordId={}", recordId);
            return Collections.emptyList();
        }
        try {
            Object out = m.invoke(qualityService, recordId, 1);
            return (out instanceof List) ? (List<HisEmrQcDefect>) out : Collections.<HisEmrQcDefect>emptyList();
        } catch (Exception e) {
            log.warn("内涵质控执行异常, 降级跳过: recordId={}, err={}", recordId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 反射定位 evaluateContent(覆盖 Long/long × Integer/int 参数声明组合) */
    private static Method findEvaluateContent() {
        Class<?>[][] variants = {
                {Long.class, Integer.class}, {Long.class, int.class},
                {long.class, Integer.class}, {long.class, int.class}};
        for (Class<?>[] ps : variants) {
            try {
                return EmrQualityService.class.getMethod("evaluateContent", ps);
            } catch (NoSuchMethodException ignored) {
                /* try next variant */
            }
        }
        return null;
    }

    /** 缺陷实体 → 前端展示项(defectId/ruleName/defectDesc/deductScore) */
    private static Map<String, Object> defectItem(HisEmrQcDefect d) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("defectId", d.getId());
        item.put("ruleName", d.getRuleName());
        item.put("defectDesc", d.getDefectDesc());
        item.put("deductScore", d.getDeductScore());
        return item;
    }

    /** 按 severity 归级: 3禁止/2拦截/其余提醒 */
    private static void classifyDefect(Map<String, Object> item, Integer severity,
                                       List<Map<String, Object>> warnings, List<Map<String, Object>> blocks,
                                       List<Map<String, Object>> forbidden) {
        if (severity != null && severity == 3) {
            forbidden.add(item);
        } else if (severity != null && severity == 2) {
            blocks.add(item);
        } else {
            warnings.add(item);
        }
    }

    /** qcResult 列表字段安全取用(null/类型不符回退空列表) */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> qcList(Object raw) {
        return (raw instanceof List) ? (List<Map<String, Object>>) raw : Collections.emptyList();
    }

    /** qcResult 计数(null 安全) */
    private static int qcCount(Object list) {
        return (list instanceof List) ? ((List<?>) list).size() : 0;
    }

    /**
     * 时效质控结果归集(对齐 EmrTimelinessService.checkTimeliness 契约):
     * {status: overdue/warning/normal/unknown, deadlineTime, remainingMinutes,
     *  rules: [{ruleCode, ruleName, severity, deadlineTime, remainingMinutes, status}]}。
     * 规则级归级: 显式 severity(3禁止/2拦截/其余提醒)优先, 缺省按 status(超时→拦截, 临近超时→提醒, 正常不计);
     * rules 为空而整体已超时时兜底单条拦截项。
     */
    private static void collectTimeliness(Map<String, Object> tl, List<Map<String, Object>> warnings,
                                          List<Map<String, Object>> blocks, List<Map<String, Object>> forbidden) {
        if (tl == null) {
            return;
        }
        boolean any = false;
        Object rulesRaw = tl.get("rules");
        if (rulesRaw instanceof List) {
            for (Object o : (List<?>) rulesRaw) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> r = (Map<?, ?>) o;
                String ruleStatus = String.valueOf(r.get("status"));
                if ("normal".equals(ruleStatus)) {
                    continue;                       /* 未临期规则不构成缺陷 */
                }
                Object name = r.get("ruleName");
                if (name == null) {
                    name = r.get("ruleCode");
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleName", "时效·" + name);
                item.put("defectDesc", timelinessDesc(r.get("deadlineTime"), r.get("remainingMinutes"), ruleStatus));
                item.put("deductScore", null);
                Object sev = r.get("severity");
                int severity = (sev instanceof Number) ? ((Number) sev).intValue()
                        : ("overdue".equals(ruleStatus) ? 2 : 1);
                classifyDefect(item, severity, warnings, blocks, forbidden);
                any = true;
            }
        }
        if (!any && "overdue".equals(String.valueOf(tl.get("status")))) {
            /* 兜底: 无规则明细但整体已超时 */
            Object label = tl.get("typeLabel");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleName", "时效·" + (label != null ? label : "病历书写"));
            item.put("defectDesc", timelinessDesc(tl.get("deadlineTime"), tl.get("remainingMinutes"), "overdue"));
            item.put("deductScore", null);
            classifyDefect(item, 2, warnings, blocks, forbidden);
        }
    }

    /** 时效缺陷描述: 已超时/临近超时 + 截止时间(与 getDeadlineAlerts 同口径的展示格式) */
    private static String timelinessDesc(Object deadline, Object remaining, String status) {
        String dl = (deadline instanceof LocalDateTime) ? DEADLINE_FMT.format((LocalDateTime) deadline)
                : (deadline != null ? String.valueOf(deadline) : "-");
        if ("overdue".equals(status)) {
            return "文书已超过书写时限(截止 " + dl + "), 须整改后方可签名";
        }
        long mins = (remaining instanceof Number) ? ((Number) remaining).longValue() : -1;
        return "临近书写时限" + (mins >= 0 ? "(剩余 " + mins + " 分钟)" : "") + ", 截止 " + dl;
    }

    /** 质控节点留痕(静默): 签名节点记归级计数与放行/阻断结论, 评分前后均取当前质控分(签名检查不改分) */
    private void logQcNodeQuietly(Long recordId, Map<String, Object> qcResult) {
        try {
            HisInpMedicalRecord rec = getById(recordId);
            LoginUser u = UserContext.get();
            HisEmrQcNode node = new HisEmrQcNode();
            node.setRecordId(recordId);
            node.setVisitId(rec != null ? rec.getInpVisitId() : null);
            node.setNodeType("签名");
            node.setNodeDesc("签名前质控: 禁止" + qcCount(qcResult.get("forbidden"))
                    + "项/拦截" + qcCount(qcResult.get("blocks"))
                    + "项/提醒" + qcCount(qcResult.get("warnings"))
                    + "项, " + (Boolean.TRUE.equals(qcResult.get("passed")) ? "放行" : "阻断"));
            node.setScoreBefore(rec != null ? rec.getQualityScore() : null);
            node.setScoreAfter(rec != null ? rec.getQualityScore() : null);
            node.setOperatorId(u != null ? u.getStaffId() : null);
            node.setOperatorName(u != null ? u.getRealName() : null);
            qcNodeMapper.insert(node);
        } catch (Exception e) {
            log.warn("签名质控节点日志写入失败(不影响签名): recordId={}, err={}", recordId, e.getMessage());
        }
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

    /* ---------- 私有助手: 审计留痕 / Tiptap 双轨 / 签名规则链 / SSE ---------- */

    /** 审计留痕(静默): 落 his_emr_audit_log; 失败仅告警, 不影响病历主流程 */
    private void auditQuietly(Long recordId, String action, String detail) {
        try {
            emrAuditService.log(EmrAuditService.SCOPE_INP, recordId, action, detail);
        } catch (Exception e) {
            log.warn("病历审计留痕失败(不影响主流程): recordId={}, action={}, err={}", recordId, action, e.getMessage());
        }
    }

    /** Tiptap/ProseMirror 文档判定: JSON 解析后 type=doc */
    private static boolean isTiptapDocument(String json) {
        if (!StringUtils.hasText(json)) {
            return false;
        }
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '{') {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(s);
            return obj != null && "doc".equals(String.valueOf(obj.get("type")));
        } catch (Exception e) {
            return false;
        }
    }

    /** 密文特征预检: 仅含 Base64 字符集且长度≥24(IV12+密文+tag16 的 Base64 最短 40 字符), 避免 legacy 明文误入解密告警 */
    private static boolean looksLikeEncrypted(String s) {
        if (s == null || s.length() < 24) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Tiptap JSON 宏解析: 递归收集字符串叶值中的 {macroCode} → 批量解析就诊宏 → 原位替换(未知宏保留; 失败原样返回) */
    private String resolveMacrosInTiptap(String tiptapJson, Long visitId) {
        if (!StringUtils.hasText(tiptapJson) || visitId == null) {
            return tiptapJson;
        }
        try {
            Object root = JSON.parse(tiptapJson);
            Set<String> codes = new LinkedHashSet<>();
            collectMacroCodes(root, codes);
            if (codes.isEmpty()) {
                return tiptapJson;
            }
            R<Map<String, String>> resolved = macroService.resolveMacros(visitId, new ArrayList<>(codes));
            Map<String, String> values = resolved == null ? null : resolved.getData();
            if (values == null || values.isEmpty()) {
                return tiptapJson;
            }
            replaceMacroValues(root, values);
            return JSON.toJSONString(root);
        } catch (Exception e) {
            log.warn("Tiptap 文档宏解析失败(原样返回): visitId={}, err={}", visitId, e.getMessage());
            return tiptapJson;
        }
    }

    /** 递归收集节点树字符串叶值中的 {macroCode} */
    private static void collectMacroCodes(Object node, Set<String> out) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (Map.Entry<String, Object> e : new ArrayList<>(obj.entrySet())) {
                Object v = e.getValue();
                if (v instanceof String) {
                    Matcher m = MACRO_TOKEN.matcher((String) v);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                } else {
                    collectMacroCodes(v, out);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                Object v = arr.get(i);
                if (v instanceof String) {
                    Matcher m = MACRO_TOKEN.matcher((String) v);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                } else {
                    collectMacroCodes(v, out);
                }
            }
        }
    }

    /** 递归原位替换节点树字符串叶值中的宏占位符(未知宏保留原样) */
    private static void replaceMacroValues(Object node, Map<String, String> values) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String key : new ArrayList<>(obj.keySet())) {
                obj.put(key, replaceNodeValue(obj.get(key), values));
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, replaceNodeValue(arr.get(i), values));
            }
        }
    }

    private static Object replaceNodeValue(Object v, Map<String, String> values) {
        if (v instanceof String) {
            Matcher m = MACRO_TOKEN.matcher((String) v);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String val = values.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof JSONObject || v instanceof JSONArray) {
            replaceMacroValues(v, values);
        }
        return v;
    }

    /* ---------- 签名规则链助手 ---------- */

    /** 病历必需签名环节链(required!=0, 按 stage 去重, stage_order 升序): 空链=未配置规则 */
    private List<HisEmrSignatureRule> requiredChainOf(HisInpMedicalRecord rec) {
        Long orgId = rec.getOrgId() != null ? rec.getOrgId() : guard.currentOrgId();
        List<HisEmrSignatureRule> rules = emrSignatureService.getSignatureChain(
                rec.getRecordType() == null ? 1 : rec.getRecordType(), orgId);
        List<HisEmrSignatureRule> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (HisEmrSignatureRule rule : rules) {
            if (rule.getRequired() != null && rule.getRequired() == 0) {
                continue;
            }
            if (StringUtils.hasText(rule.getStage()) && seen.add(rule.getStage())) {
                out.add(rule);
            }
        }
        return out;
    }

    /** 当前用户职称匹配规则链环节: 优先职称档位区间(title_code_min/max), 未命中按环节名兜底; 无法匹配返回 -1 */
    private int matchStageIndex(List<HisEmrSignatureRule> chain, String titleCode) {
        Integer code = parseTitleDigit(titleCode);
        if (code != null) {
            for (int i = 0; i < chain.size(); i++) {
                HisEmrSignatureRule rule = chain.get(i);
                Integer lo = parseTitleDigit(rule.getTitleCodeMin());
                Integer hi = parseTitleDigit(rule.getTitleCodeMax());
                if (lo == null && hi == null) {
                    continue; // 区间未配置, 交由环节名兜底
                }
                if ((lo == null || code >= lo) && (hi == null || code <= hi)) {
                    return i;
                }
            }
            for (int i = 0; i < chain.size(); i++) {
                String stage = chain.get(i).getStage();
                if (isAuthorStage(stage) && (code == 4 || code == 5)) {
                    return i;
                }
                if ("attending".equals(stage) && code == 3) {
                    return i;
                }
                if ("director".equals(stage) && (code == 1 || code == 2)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 作者/住院医师环节判定 */
    private static boolean isAuthorStage(String stage) {
        return "author".equals(stage) || "resident".equals(stage);
    }

    /** 职称编码解析: 取前导数字档位(CV08.30.005); 无法识别返回 null */
    private static Integer parseTitleDigit(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        Matcher m = Pattern.compile("^\\d+").matcher(code.trim());
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    /** 静默补记环节 SM2 签名(供签名链顺序校验/链展示留痕; 失败仅告警, 不影响提交主流程) */
    private void markStageSignature(Long recordId, String stage) {
        try {
            emrSignatureService.sign(1, recordId, stage, null);
        } catch (Exception e) {
            log.warn("病历环节签名登记失败(不影响流转): recordId={}, stage={}, err={}", recordId, stage, e.getMessage());
        }
    }

    /* ---------- SSE 提醒助手 ---------- */

    /** 环节完成后定位下一签署人并推送待签名提醒(定位失败静默跳过) */
    private void publishNextSignatureRequired(HisInpMedicalRecord rec, List<HisEmrSignatureRule> chain, int nextIdx) {
        if (nextIdx < 0 || nextIdx >= chain.size()) {
            return;
        }
        try {
            HisEmrSignatureRule nextRule = chain.get(nextIdx);
            Long signerId = resolveNextSignerId(rec, nextRule);
            if (signerId == null) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("recordId", rec.getId());
            data.put("recordType", rec.getRecordType());
            data.put("stage", nextRule.getStage());
            data.put("patientName", patientNameOf(rec.getInpVisitId()));
            emrEventPublisher.publish(signerId, EmrEventType.SIGNATURE_REQUIRED, data);
        } catch (Exception e) {
            log.warn("待签名提醒推送失败(不影响主流程): recordId={}, err={}", rec.getId(), e.getMessage());
        }
    }

    /** 签署结果 SSE: 链完成 → RECORD_SIGNED(通知记录医生); 有下一环节 → SIGNATURE_REQUIRED */
    private void publishSignatureOutcome(HisInpMedicalRecord rec, R<Map<String, Object>> result) {
        try {
            Map<String, Object> data = result == null ? null : result.getData();
            if (data == null) {
                return;
            }
            if (Boolean.TRUE.equals(data.get("allComplete"))) {
                Map<String, Object> signed = new LinkedHashMap<>();
                signed.put("recordId", rec.getId());
                signed.put("recordType", rec.getRecordType());
                signed.put("title", rec.getTitle());
                emrEventPublisher.publish(rec.getDoctorId(), EmrEventType.RECORD_SIGNED, signed);
                return;
            }
            Object nextStage = data.get("nextStage");
            if (nextStage == null) {
                return;
            }
            List<HisEmrSignatureRule> chain = requiredChainOf(rec);
            for (int i = 0; i < chain.size(); i++) {
                if (String.valueOf(nextStage).equals(chain.get(i).getStage())) {
                    publishNextSignatureRequired(rec, chain, i);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("签署结果事件推送失败(不影响主流程): recordId={}, err={}", rec.getId(), e.getMessage());
        }
    }

    /** 下一环节签署人定位: attending 优先病历主管/就诊主治; 其余按规则职称档位在科室/机构内匹配在职医师 */
    private Long resolveNextSignerId(HisInpMedicalRecord rec, HisEmrSignatureRule rule) {
        String stage = rule.getStage();
        if ("attending".equals(stage) && rec.getAttendingDoctorId() != null) {
            return rec.getAttendingDoctorId();
        }
        if (isAuthorStage(stage) && rec.getDoctorId() != null) {
            return rec.getDoctorId();
        }
        HisInpVisit visit = rec.getInpVisitId() == null ? null : visitMapper.selectById(rec.getInpVisitId());
        if ("attending".equals(stage) && visit != null && visit.getDoctorId() != null) {
            return visit.getDoctorId();
        }
        return findStaffByTitleRule(rule, visit == null ? null : visit.getDeptId(), rec.getOrgId());
    }

    /** 按规则职称档位匹配在职医师(先本科室后本机构, ID升序取首名; 查无返回 null) */
    private Long findStaffByTitleRule(HisEmrSignatureRule rule, Long deptId, Long orgId) {
        List<String> codes = titleCodesOf(rule);
        if (codes.isEmpty()) {
            return null;
        }
        if (deptId != null) {
            HisStaff staff = staffMapper.selectOne(new LambdaQueryWrapper<HisStaff>()
                    .eq(HisStaff::getStatus, 1)
                    .eq(HisStaff::getDeptId, deptId)
                    .in(HisStaff::getTitleCode, codes)
                    .orderByAsc(HisStaff::getId)
                    .last("LIMIT 1"));
            if (staff != null) {
                return staff.getId();
            }
        }
        if (orgId != null) {
            HisStaff staff = staffMapper.selectOne(new LambdaQueryWrapper<HisStaff>()
                    .eq(HisStaff::getStatus, 1)
                    .eq(HisStaff::getOrgId, orgId)
                    .in(HisStaff::getTitleCode, codes)
                    .orderByAsc(HisStaff::getId)
                    .last("LIMIT 1"));
            if (staff != null) {
                return staff.getId();
            }
        }
        return null;
    }

    /** 规则职称档位展开为编码列表(区间展开; 区间未配置按环节名兜底: 主治→3, 主任→1/2, 其余→4/5) */
    private static List<String> titleCodesOf(HisEmrSignatureRule rule) {
        Integer lo = parseTitleDigit(rule.getTitleCodeMin());
        Integer hi = parseTitleDigit(rule.getTitleCodeMax());
        if (lo != null || hi != null) {
            int from = lo == null ? 1 : Math.max(lo, 1);
            int to = hi == null ? 5 : Math.min(hi, 5);
            List<String> out = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                out.add(String.valueOf(i));
            }
            return out;
        }
        String stage = rule.getStage();
        if ("attending".equals(stage)) {
            return Collections.singletonList("3");
        }
        if ("director".equals(stage)) {
            return Arrays.asList("1", "2");
        }
        return Arrays.asList("4", "5");
    }

    /** 就诊患者姓名(展示用, 查无返回 null) */
    private String patientNameOf(Long visitId) {
        if (visitId == null) {
            return null;
        }
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null || v.getPatientId() == null) {
            return null;
        }
        HisPatient p = patientMapper.selectById(v.getPatientId());
        return p == null ? null : p.getName();
    }

    /* ---------- T43 版本快照 私有助手 ---------- */

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
     * 按结构化模板新建病历(病历P2 双轨升级): 模板含 Tiptap 文档时经宏解析→AES-GCM 加密
     * 落 content + 明文落 structureData(要素按文档全量同步); 无文档走旧扁平骨架口径
     * (defaultMacro 宏预填充); 按模板类别计算书写截止时间 → 落库草稿并即时质控。
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
        HisInpMedicalRecord r = new HisInpMedicalRecord();
        r.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        r.setInpVisitId(visit.getId());
        r.setRecordType(tpl.getRecordType());
        r.setTitle(tpl.getTemplateName());
        r.setTemplateId(tpl.getId());
        r.setRecordTime(LocalDateTime.now());
        r.setDoctorId(doctorId != null ? doctorId : InpOrderService.currentDoctorId());
        r.setDeadlineTime(computeDeadline(tpl.getTemplateCategory(), visit, LocalDateTime.now()));
        r.setStatus(1);
        boolean tiptap = false;
        if (StringUtils.hasText(tpl.getDocument())) {
            /* Tiptap 双轨: 模板文档宏解析 → 密文 content + 明文 structureData */
            String tiptapJson = resolveMacrosInTiptap(tpl.getDocument(), visit.getId());
            r.setContent(emrDocumentService.encrypt(tiptapJson));
            r.setStructureData(tiptapJson);
            tiptap = true;
        } else {
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
            r.setStructureData(JSON.toJSONString(data));
        }
        save(r);
        log.info("按模板建档: id={}, visitId={}, template={}, deadline={}, tiptap={}",
                r.getId(), visit.getId(), tpl.getTemplateCode(), r.getDeadlineTime(), tiptap);
        auditQuietly(r.getId(), "CREATE", null);
        evaluateQuietly(r.getId());
        emrElementService.syncInpRecord(r.getId());
        if (tiptap) {
            emrElementService.syncFromTiptap(1, r.getId(), r.getStructureData());
        }
        return getById(r.getId());
    }

    /**
     * 由医嘱联动建档(病历P2): 按记录类型取默认启用模板生成草稿, 并把来源医嘱ID标注入病历内容
     * (Tiptap 文档落 attrs.sourceOrderId, 扁平 JSON 落 $sourceOrderId), 供医嘱-病历溯源。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromOrder(Long visitId, Long orderId, int recordType) {
        requireVisit(visitId);
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        if (recordType < 1 || recordType > 15) {
            throw new BizException(400, "病历类型超出支持范围(1-15): " + recordType);
        }
        HisEmrTemplate tpl = defaultTemplateOf(recordType);
        HisInpMedicalRecord rec;
        if (tpl != null) {
            rec = createFromTemplate(visitId, tpl.getId(), null);
            attachOrderLinkage(rec.getId(), orderId);
        } else {
            InpMedRecordDTO dto = new InpMedRecordDTO();
            dto.setInpVisitId(visitId);
            dto.setRecordType(recordType);
            dto.setTitle(recordTypeLabel(recordType) + "-" + DEADLINE_FMT.format(LocalDateTime.now()));
            rec = create(dto);
        }
        log.info("医嘱联动建档: id={}, visitId={}, orderId={}, recordType={}",
                rec.getId(), visitId, orderId, recordType);
        return rec.getId();
    }

    /** 医嘱关联标注: Tiptap 文档写 attrs.sourceOrderId(重加密 content 同步双轨); 扁平 JSON 根写 $sourceOrderId */
    private void attachOrderLinkage(Long recordId, Long orderId) {
        HisInpMedicalRecord rec = getById(recordId);
        if (rec == null || !StringUtils.hasText(rec.getStructureData())) {
            return;
        }
        String structure = rec.getStructureData();
        String updated;
        try {
            JSONObject root = JSON.parseObject(structure);
            if (root == null) {
                return;
            }
            if (isTiptapDocument(structure)) {
                JSONObject attrs = root.getJSONObject("attrs");
                if (attrs == null) {
                    attrs = new JSONObject();
                    root.put("attrs", attrs);
                }
                attrs.put("sourceOrderId", String.valueOf(orderId));
            } else {
                root.put("$sourceOrderId", String.valueOf(orderId));
            }
            updated = root.toJSONString();
        } catch (Exception e) {
            log.warn("医嘱关联标注失败(JSON解析): recordId={}, err={}", recordId, e.getMessage());
            return;
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HisInpMedicalRecord> uw =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HisInpMedicalRecord>()
                        .set(HisInpMedicalRecord::getStructureData, updated)
                        .eq(HisInpMedicalRecord::getId, recordId);
        if (isTiptapDocument(updated) && StringUtils.hasText(rec.getContent())) {
            uw.set(HisInpMedicalRecord::getContent, emrDocumentService.encrypt(updated));
        }
        update(uw);
    }

    /** 记录类型默认启用模板(按模板层级最小优先: 全院<科室<个人; 无则返回 null) */
    private HisEmrTemplate defaultTemplateOf(int recordType) {
        R<List<HisEmrTemplate>> res = templateService.listTemplates(recordType, null, null, 1, false);
        List<HisEmrTemplate> list = res == null ? null : res.getData();
        if (list == null || list.isEmpty()) {
            return null;
        }
        HisEmrTemplate best = null;
        for (HisEmrTemplate t : list) {
            if (t.getStatus() != null && t.getStatus() == 0) {
                continue;
            }
            if (best == null || scopeLevelOf(t) < scopeLevelOf(best)) {
                best = t;
            }
        }
        return best;
    }

    private static int scopeLevelOf(HisEmrTemplate t) {
        return t.getScopeLevel() == null ? 0 : t.getScopeLevel();
    }

    /** 记录类型标签(未知类型回退"病历") */
    private static String recordTypeLabel(Integer recordType) {
        String label = recordType == null ? null : RECORD_TYPE_LABELS.get(recordType);
        return label == null ? "病历" : label;
    }

    /**
     * 按文书类别分组的病历列表(病历P2): 入院出院类[1,8,9,10] / 病程类[2,3,4,7] / 手术类[5,6] / 其他[11-15],
     * 组内按记录时间倒序; 每条含 id/title/recordType/typeLabel/status/recordTime/deadlineTime/doctorId。
     */
    public Map<String, Object> listGroupedByType(Long inpVisitId) {
        requireVisit(inpVisitId);
        List<HisInpMedicalRecord> records = lambdaQuery()
                .eq(HisInpMedicalRecord::getInpVisitId, inpVisitId)
                .orderByDesc(HisInpMedicalRecord::getRecordTime)
                .orderByDesc(HisInpMedicalRecord::getId)
                .list();
        List<Map<String, Object>> admitDischarge = new ArrayList<>();
        List<Map<String, Object>> progress = new ArrayList<>();
        List<Map<String, Object>> surgery = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        for (HisInpMedicalRecord rec : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.getId());
            m.put("title", rec.getTitle());
            m.put("recordType", rec.getRecordType());
            m.put("typeLabel", RECORD_TYPE_LABELS.get(rec.getRecordType()));
            m.put("status", rec.getStatus());
            m.put("recordTime", rec.getRecordTime());
            m.put("deadlineTime", rec.getDeadlineTime());
            m.put("doctorId", rec.getDoctorId());
            Integer type = rec.getRecordType();
            if (type != null && GROUP_ADMIT_DISCHARGE.contains(type)) {
                admitDischarge.add(m);
            } else if (type != null && GROUP_PROGRESS.contains(type)) {
                progress.add(m);
            } else if (type != null && GROUP_SURGERY.contains(type)) {
                surgery.add(m);
            } else {
                others.add(m);
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(groupEntry("admit_discharge", "入院出院类", admitDischarge));
        groups.add(groupEntry("progress", "病程类", progress));
        groups.add(groupEntry("surgery", "手术类", surgery));
        groups.add(groupEntry("other", "其他", others));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inpVisitId", inpVisitId);
        result.put("total", records.size());
        result.put("groups", groups);
        return result;
    }

    private static Map<String, Object> groupEntry(String key, String name, List<Map<String, Object>> records) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("key", key);
        g.put("name", name);
        g.put("count", records.size());
        g.put("records", records);
        return g;
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
