package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingPipeDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingPipe;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingPipeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 护理管道服务(P4c): 置管/巡视评估/更换/拔管(意外脱出)全生命周期管理 + 在管清单 + 到期拔管预警。
 * 说明:
 * 1) 表 his_nursing_pipe 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离;
 *    到期预警跨表联查(his_nursing_pipe × his_inp_visit × his_patient)走 JdbcTemplate 原生 SQL,
 *    一律手工带 tenant_id/deleted 过滤;
 * 2) 生命周期状态机: 置管(status=1 在管) → 评估(回填 risk_level/last_assess_time) /
 *    更换(回填 last_replace_time) → 拔管(status=2 正常拔 / 3 意外脱出, 回填 actual_remove_time);
 *    已拔/脱出的管道不可再评估/更换/重复拔管;
 * 3) pipe_type 限固定枚举集(防拼写破坏分类统计), risk_level 限 1低/2中/3高(分级巡视依据);
 * 4) 写操作校验就诊/记录归属机构与当前登录机构一致(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingPipeService {

    /** 状态: 1在管 2已拔 3意外脱出 */
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_REMOVED = 2;
    public static final int STATUS_ACCIDENTAL = 3;

    /** 风险等级: 1低 2中 3高 */
    public static final Set<Integer> RISK_LEVELS = new HashSet<>(Arrays.asList(1, 2, 3));

    /** 管道类型固定集(与 his_nursing_pipe.pipe_type 注释口径一致) */
    public static final Set<String> PIPE_TYPES = new HashSet<>(Arrays.asList(
            "central_venous", "urinary", "nasogastric", "chest_tube", "drain", "tracheostomy", "picc", "other"));

    /** 到期预警提前天数(预计拔管日 <= 今日+N 视为临期) */
    private static final int OVERDUE_ADVANCE_DAYS = 1;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisNursingPipeMapper pipeMapper;
    private final HisInpVisitMapper visitMapper;
    private final JdbcTemplate jdbcTemplate;

    public NursingPipeService(HisNursingPipeMapper pipeMapper, HisInpVisitMapper visitMapper,
                              JdbcTemplate jdbcTemplate) {
        this.pipeMapper = pipeMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 置管 ================= */

    /**
     * 置管登记: 校验就诊归属与字段合法性 → 落库(status=1 在管)。
     * insertTime 缺省当前, patientId 缺省从就诊主表回填, riskLevel 缺省 2 中危。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingPipe insert(NursingPipeDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        validateInsert(dto);

        Long patientId = dto.getPatientId() != null ? dto.getPatientId() : visit.getPatientId();
        if (patientId == null) {
            throw new BizException(400, "就诊记录缺少患者信息, 无法登记管道");
        }

        HisNursingPipe p = new HisNursingPipe();
        p.setOrgId(visit.getOrgId());
        p.setInpVisitId(visit.getId());
        p.setPatientId(patientId);
        p.setPipeType(dto.getPipeType().trim());
        p.setPipeName(trimToNull(dto.getPipeName()));
        p.setInsertTime(dto.getInsertTime() != null ? dto.getInsertTime() : LocalDateTime.now());
        p.setInsertSite(trimToNull(dto.getInsertSite()));
        p.setBodyPartSvgData(trimToNull(dto.getBodyPartSvgData()));
        p.setRiskLevel(dto.getRiskLevel() != null ? dto.getRiskLevel() : 2);
        p.setExpectedRemoveDate(dto.getExpectedRemoveDate());
        p.setStatus(STATUS_ACTIVE);
        p.setNote(trimToNull(dto.getNote()));
        pipeMapper.insert(p);
        return p;
    }

    /* ================= 生命周期操作 ================= */

    /**
     * 巡视评估: 回填风险等级(分级巡视依据)与最后评估时间, 附评估备注。
     * 仅在管管道可评估。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingPipe assess(Long id, Integer riskLevel, String note) {
        HisNursingPipe p = requireActive(id, "评估");
        if (riskLevel != null) {
            if (!RISK_LEVELS.contains(riskLevel)) {
                throw new BizException(400, "风险等级无效(1低 2中 3高)");
            }
            p.setRiskLevel(riskLevel);
        }
        p.setLastAssessTime(LocalDateTime.now());
        if (StringUtils.hasText(note)) {
            p.setNote(note.trim());
        }
        pipeMapper.updateById(p);
        return p;
    }

    /** 更换管道: 回填最后更换时间(敷贴/接头/导管更换留痕), 附更换备注。仅在管管道可更换。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingPipe replace(Long id, String note) {
        HisNursingPipe p = requireActive(id, "更换");
        p.setLastReplaceTime(LocalDateTime.now());
        if (StringUtils.hasText(note)) {
            p.setNote(note.trim());
        }
        pipeMapper.updateById(p);
        return p;
    }

    /**
     * 拔管: removeType=2 正常拔 / 3 意外脱出, 回填实际拔管时间。
     * 仅在管管道可拔; 意外脱出建议在备注中补记脱出经过。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingPipe remove(Long id, Integer removeType) {
        if (removeType == null || (removeType != STATUS_REMOVED && removeType != STATUS_ACCIDENTAL)) {
            throw new BizException(400, "拔管类型无效(2正常拔 3意外脱出)");
        }
        HisNursingPipe p = requireActive(id, "拔管");
        p.setStatus(removeType);
        p.setActualRemoveTime(LocalDateTime.now());
        pipeMapper.updateById(p);
        return p;
    }

    /* ================= 查询 ================= */

    /**
     * 管道列表(按就诊): 在管(status=1)置顶, 其余按状态升序, 同状态按置管时间倒序(最新在前)。
     */
    public List<HisNursingPipe> listByVisit(Long inpVisitId) {
        requireVisit(inpVisitId);
        return pipeMapper.selectList(Wrappers.<HisNursingPipe>lambdaQuery()
                .eq(HisNursingPipe::getInpVisitId, inpVisitId)
                .orderByAsc(HisNursingPipe::getStatus)
                .orderByDesc(HisNursingPipe::getInsertTime)
                .orderByDesc(HisNursingPipe::getId));
    }

    /** 仅在管管道(status=1): 交接班/术前核查用, 按置管时间倒序。 */
    public List<HisNursingPipe> listActive(Long inpVisitId) {
        requireVisit(inpVisitId);
        return pipeMapper.selectList(Wrappers.<HisNursingPipe>lambdaQuery()
                .eq(HisNursingPipe::getInpVisitId, inpVisitId)
                .eq(HisNursingPipe::getStatus, STATUS_ACTIVE)
                .orderByDesc(HisNursingPipe::getInsertTime)
                .orderByDesc(HisNursingPipe::getId));
    }

    /**
     * 到期拔管预警(按病区): 在管且预计拔管日 <= 今日+1 的管道清单,
     * 联查就诊/患者取床位号与姓名(护理站预警看板数据源)。
     * 返回字段: id/inpVisitId/patientName/inpNo/bedId/pipeType/pipeName/insertSite/riskLevel/
     * expectedRemoveDate/insertTime/overdueDays(已逾期天数, 0=今日到期, 负数=未到期但临期)。
     */
    public List<Map<String, Object>> overdueAlerts(Long wardId) {
        if (wardId == null) {
            throw new BizException(400, "病区ID不能为空");
        }
        LocalDate deadline = LocalDate.now().plusDays(OVERDUE_ADVANCE_DAYS);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.inp_visit_id AS inpVisitId, p.pipe_type AS pipeType, p.pipe_name AS pipeName,"
                        + " p.insert_site AS insertSite, p.risk_level AS riskLevel,"
                        + " DATE_FORMAT(p.insert_time, '%Y-%m-%d %H:%i') AS insertTime,"
                        + " DATE_FORMAT(p.expected_remove_date, '%Y-%m-%d') AS expectedRemoveDate,"
                        + " p.expected_remove_date AS expectedRemoveDateRaw,"
                        + " v.patient_id AS patientId, pt.name AS patientName, v.inp_no AS inpNo, v.bed_id AS bedId"
                        + " FROM his_nursing_pipe p"
                        + " JOIN his_inp_visit v ON p.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                        + " LEFT JOIN his_patient pt ON v.patient_id = pt.id AND pt.deleted = 0"
                        + " WHERE p.status = 1 AND p.deleted = 0 AND p.tenant_id = ?"
                        + " AND v.ward_id = ? AND p.expected_remove_date IS NOT NULL"
                        + " AND p.expected_remove_date <= ?"
                        + " ORDER BY p.expected_remove_date ASC, p.risk_level DESC, p.id ASC",
                tenantId(), tenantId(), wardId, deadline);
        for (Map<String, Object> row : rows) {
            Object raw = row.remove("expectedRemoveDateRaw");
            long overdueDays = 0;
            if (raw instanceof java.sql.Date) {
                overdueDays = java.time.temporal.ChronoUnit.DAYS.between(
                        ((java.sql.Date) raw).toLocalDate(), LocalDate.now());
            }
            row.put("overdueDays", (int) overdueDays);
        }
        return rows;
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 置管字段校验: 类型限固定枚举集, 名称/部位 ≤100 字, 风险等级 1-3, SVG 标注 ≤20000 字符。 */
    private static void validateInsert(NursingPipeDTO dto) {
        if (!StringUtils.hasText(dto.getPipeType())) {
            throw new BizException(400, "管道类型不能为空");
        }
        if (!PIPE_TYPES.contains(dto.getPipeType().trim())) {
            throw new BizException(400, "管道类型无效: " + dto.getPipeType()
                    + "(有效值: central_venous/urinary/nasogastric/chest_tube/drain/tracheostomy/picc/other)");
        }
        if (dto.getPipeName() != null && dto.getPipeName().trim().length() > 100) {
            throw new BizException(400, "管道名称不能超过100字");
        }
        if (dto.getInsertSite() != null && dto.getInsertSite().trim().length() > 100) {
            throw new BizException(400, "置管部位不能超过100字");
        }
        if (dto.getRiskLevel() != null && !RISK_LEVELS.contains(dto.getRiskLevel())) {
            throw new BizException(400, "风险等级无效(1低 2中 3高)");
        }
        if (dto.getBodyPartSvgData() != null && dto.getBodyPartSvgData().length() > 20000) {
            throw new BizException(400, "人体图标注数据过大(限20000字符)");
        }
        if (dto.getInsertTime() != null && dto.getInsertTime().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new BizException(400, "置管时间不能晚于当前时间");
        }
        if (dto.getNote() != null && dto.getNote().trim().length() > 500) {
            throw new BizException(400, "备注不能超过500字");
        }
    }

    /** 在管管道必读: 存在 + 归属机构一致 + 状态为在管(评估/更换/拔管前置校验)。 */
    private HisNursingPipe requireActive(Long id, String action) {
        if (id == null) {
            throw new BizException(400, "管道记录ID不能为空");
        }
        HisNursingPipe p = pipeMapper.selectById(id);
        if (p == null) {
            throw new BizException(404, "管道记录不存在");
        }
        requireSameOrg(p.getOrgId());
        if (p.getStatus() == null || p.getStatus() != STATUS_ACTIVE) {
            throw new BizException(400, "仅在管管道可" + action + "(当前状态: " + statusName(p.getStatus()) + ")");
        }
        return p;
    }

    /** 状态中文名(报错展示) */
    private static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_ACTIVE:
                return "在管";
            case STATUS_REMOVED:
                return "已拔";
            case STATUS_ACCIDENTAL:
                return "意外脱出";
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
            throw new BizException(403, "该管道记录不属于当前登录机构, 无权操作");
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
