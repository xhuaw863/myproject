package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingEducationDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingEducation;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingEducationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 护理健康宣教服务(P4c): 入院/疾病/用药/饮食/运动/出院六类知识宣教记录 + 知识分类库。
 * 说明:
 * 1) 表 his_nursing_education 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离;
 * 2) 宣教人取当前登录职工(staffId 优先, 回退用户ID), 宣教时间缺省当前;
 *    评价三态(understood 掌握 / partially 部分掌握 / not_understood 未掌握)支持出院前再宣教闭环;
 * 3) knowledge_category 限固定枚举集(与 getKnowledgeCategories 分类库一致),
 *    education_method 限 verbal/written/video/demo, evaluation_result 限三态;
 * 4) 删除为逻辑删除(误录场景), 校验记录归属机构(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingEducationService {

    /** 知识分类固定集(与 his_nursing_education.knowledge_category 注释口径一致) */
    public static final Set<String> KNOWLEDGE_CATEGORIES = new HashSet<>(Arrays.asList(
            "admission", "disease", "medication", "diet", "exercise", "discharge", "other"));

    /** 宣教方式固定集 */
    public static final Set<String> EDUCATION_METHODS = new HashSet<>(Arrays.asList(
            "verbal", "written", "video", "demo"));

    /** 评价固定集 */
    public static final Set<String> EVALUATION_RESULTS = new HashSet<>(Arrays.asList(
            "understood", "partially", "not_understood"));

    private final HisNursingEducationMapper educationMapper;
    private final HisInpVisitMapper visitMapper;

    public NursingEducationService(HisNursingEducationMapper educationMapper, HisInpVisitMapper visitMapper) {
        this.educationMapper = educationMapper;
        this.visitMapper = visitMapper;
    }

    /* ================= 登记 ================= */

    /** 登记宣教记录: 校验就诊归属与字段合法性 → 落库(宣教人=当前登录职工, 宣教时间缺省当前)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingEducation record(NursingEducationDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        validateRecord(dto);

        Long patientId = dto.getPatientId() != null ? dto.getPatientId() : visit.getPatientId();
        if (patientId == null) {
            throw new BizException(400, "就诊记录缺少患者信息, 无法登记宣教");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }

        HisNursingEducation e = new HisNursingEducation();
        e.setOrgId(visit.getOrgId());
        e.setInpVisitId(visit.getId());
        e.setPatientId(patientId);
        e.setKnowledgeCategory(dto.getKnowledgeCategory().trim());
        e.setTitle(trimToNull(dto.getTitle()));
        e.setContent(trimToNull(dto.getContent()));
        e.setEducationMethod(trimToNull(dto.getEducationMethod()));
        e.setEvaluationResult(trimToNull(dto.getEvaluationResult()));
        e.setEducatorId(u.getStaffId() != null ? u.getStaffId() : u.getUserId());
        e.setEducatorName(u.getRealName());
        e.setEducationTime(dto.getEducationTime() != null ? dto.getEducationTime() : LocalDateTime.now());
        educationMapper.insert(e);
        return e;
    }

    /* ================= 查询 / 删除 ================= */

    /** 宣教记录列表(按就诊): 按宣教时间倒序(最新在前)。 */
    public List<HisNursingEducation> listByVisit(Long inpVisitId) {
        requireVisit(inpVisitId);
        return educationMapper.selectList(Wrappers.<HisNursingEducation>lambdaQuery()
                .eq(HisNursingEducation::getInpVisitId, inpVisitId)
                .orderByDesc(HisNursingEducation::getEducationTime)
                .orderByDesc(HisNursingEducation::getId));
    }

    /** 宣教记录详情。 */
    public HisNursingEducation getById(Long id) {
        if (id == null) {
            throw new BizException(400, "宣教记录ID不能为空");
        }
        HisNursingEducation e = educationMapper.selectById(id);
        if (e == null) {
            throw new BizException(404, "宣教记录不存在");
        }
        requireSameOrg(e.getOrgId());
        return e;
    }

    /** 删除宣教记录(逻辑删除, 误录场景): 校验记录归属机构后删除。 */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "宣教记录ID不能为空");
        }
        HisNursingEducation e = educationMapper.selectById(id);
        if (e == null) {
            throw new BizException(404, "宣教记录不存在");
        }
        requireSameOrg(e.getOrgId());
        educationMapper.deleteById(id);
    }

    /**
     * 知识分类库: 返回 [{code, name, description, items[]}] 固定六类(另含 other 兜底),
     * 供前端宣教登记面板下拉与知识条目提示。
     */
    public List<Map<String, Object>> getKnowledgeCategories() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(category("admission", "入院宣教", "环境/制度/安全/饮食",
                new String[]{"病区环境与设施", "陪护与探视制度", "安全注意事项", "饮食安排"}));
        out.add(category("disease", "疾病知识", "病因/症状/治疗/预后",
                new String[]{"疾病病因与诱因", "常见症状识别", "治疗方案说明", "预后与随访"}));
        out.add(category("medication", "用药指导", "用法/注意事项/不良反应",
                new String[]{"用法用量", "用药注意事项", "不良反应观察", "漏服处理"}));
        out.add(category("diet", "饮食指导", "禁忌/推荐/特殊饮食",
                new String[]{"饮食禁忌", "推荐饮食", "特殊饮食要求"}));
        out.add(category("exercise", "活动指导", "功能锻炼/康复训练",
                new String[]{"活动强度与范围", "功能锻炼方法", "康复训练计划"}));
        out.add(category("discharge", "出院指导", "用药/复查/注意事项",
                new String[]{"出院带药用法", "复查时间与项目", "病情变化就医指征", "生活起居注意"}));
        out.add(category("other", "其他宣教", "上述分类未覆盖的宣教内容",
                new String[]{}));
        return out;
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 登记字段校验: 分类/方式/评价限固定枚举集, 标题 ≤100 字, 内容 ≤5000 字。 */
    private static void validateRecord(NursingEducationDTO dto) {
        if (!StringUtils.hasText(dto.getKnowledgeCategory())) {
            throw new BizException(400, "知识分类不能为空");
        }
        if (!KNOWLEDGE_CATEGORIES.contains(dto.getKnowledgeCategory().trim())) {
            throw new BizException(400, "知识分类无效: " + dto.getKnowledgeCategory()
                    + "(有效值: admission/disease/medication/diet/exercise/discharge/other)");
        }
        if (StringUtils.hasText(dto.getEducationMethod())
                && !EDUCATION_METHODS.contains(dto.getEducationMethod().trim())) {
            throw new BizException(400, "宣教方式无效: " + dto.getEducationMethod()
                    + "(有效值: verbal/written/video/demo)");
        }
        if (StringUtils.hasText(dto.getEvaluationResult())
                && !EVALUATION_RESULTS.contains(dto.getEvaluationResult().trim())) {
            throw new BizException(400, "评价结果无效: " + dto.getEvaluationResult()
                    + "(有效值: understood/partially/not_understood)");
        }
        if (dto.getTitle() != null && dto.getTitle().trim().length() > 100) {
            throw new BizException(400, "宣教标题不能超过100字");
        }
        if (dto.getContent() != null && dto.getContent().length() > 5000) {
            throw new BizException(400, "宣教内容不能超过5000字");
        }
        if (dto.getEducationTime() != null && dto.getEducationTime().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new BizException(400, "宣教时间不能晚于当前时间");
        }
    }

    /** 分类条目构造 */
    private static Map<String, Object> category(String code, String name, String description, String[] items) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("name", name);
        c.put("description", description);
        c.put("items", Arrays.asList(items));
        return c;
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
            throw new BizException(403, "该宣教记录不属于当前登录机构, 无权操作");
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
