package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.inpatient.InpDiagnosisDTO;
import com.yb.hi.entity.inpatient.HisInpDiagnosis;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisInpDiagnosisMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院诊断服务: 入院/补充/术后/出院四类诊断维护, 主诊断标志在同类型内互斥,
 * 排序号自动递增, 逻辑删除; 诊断归属跟随就诊机构。
 * ICD编码校验(validateIcdCode): 格式(ICD-10)+标准字典存在性, 仅作录入提醒不阻断保存。
 */
@Slf4j
@Service
public class InpDiagnosisService extends ServiceImpl<HisInpDiagnosisMapper, HisInpDiagnosis> {

    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public InpDiagnosisService(HisInpVisitMapper visitMapper, OrgAccessGuard guard,
                               JdbcTemplate jdbcTemplate) {
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 诊断列表(按 diag_type 分组: 1入院 2补充 3术后 4出院, 组内按排序号) */
    public Map<String, List<HisInpDiagnosis>> listByVisit(Long visitId) {
        requireVisit(visitId);
        List<HisInpDiagnosis> all = lambdaQuery()
                .eq(HisInpDiagnosis::getInpVisitId, visitId)
                .orderByAsc(HisInpDiagnosis::getDiagType)
                .orderByAsc(HisInpDiagnosis::getSortNo)
                .orderByAsc(HisInpDiagnosis::getId)
                .list();
        Map<String, List<HisInpDiagnosis>> grouped = new LinkedHashMap<>();
        for (int t = 1; t <= 4; t++) {
            grouped.put(String.valueOf(t), new ArrayList<>());
        }
        for (HisInpDiagnosis d : all) {
            String key = String.valueOf(d.getDiagType() == null ? 0 : d.getDiagType());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }
        return grouped;
    }

    /**
     * 新增诊断: is_main=1 时将同就诊同诊断类型的其他主诊断改为0(类型内互斥);
     * 诊断科室取就诊科室, 诊断医生取当前登录医生, 排序号同类型内自动递增。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpDiagnosis save(InpDiagnosisDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (!StringUtils.hasText(dto.getDiagName())) {
            throw new BizException(400, "诊断名称不能为空");
        }
        if (dto.getAdmitCondition() != null && (dto.getAdmitCondition() < 1 || dto.getAdmitCondition() > 4)) {
            throw new BizException(400, "入院病情必须为1危急/2严重/3一般/4不适用");
        }
        // ICD编码可疑时仅记警告日志, 不阻断保存(前端录入侧另有 validate-icd 实时提醒)
        warnIfIcdSuspect(dto.getDiagCode(), "新增");
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        HisInpDiagnosis d = new HisInpDiagnosis();
        d.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        d.setInpVisitId(visit.getId());
        d.setDiagType(dto.getDiagType() == null ? 1 : dto.getDiagType());
        d.setDiagCode(dto.getDiagCode());
        d.setDiagName(dto.getDiagName().trim());
        d.setIsMain(dto.getIsMain() == null ? 0 : dto.getIsMain());
        // 入院病情(1危急 2严重 3一般 4不适用)与并发症标志(1是 0否)
        d.setAdmitCondition(dto.getAdmitCondition());
        d.setComplicationFlag(dto.getComplicationFlag() == null ? 0 : dto.getComplicationFlag());
        d.setDiagDeptId(visit.getDeptId());
        d.setDiagDoctorId(InpOrderService.currentDoctorId());
        d.setDiagTime(LocalDateTime.now());
        d.setSortNo(nextSortNo(visit.getId(), d.getDiagType()));
        save(d);
        if (Integer.valueOf(1).equals(d.getIsMain())) {
            clearOtherMain(d.getInpVisitId(), d.getDiagType(), d.getId());
        }
        log.info("新增住院诊断: id={}, visitId={}, diagType={}, isMain={}, diagName={}",
                d.getId(), visit.getId(), d.getDiagType(), d.getIsMain(), d.getDiagName());
        return d;
    }

    /** 修改诊断(部分字段非空更新); 主诊断互斥以更新后的类型与标志为准 */
    @Transactional(rollbackFor = Exception.class)
    public HisInpDiagnosis update(Long id, InpDiagnosisDTO dto) {
        HisInpDiagnosis exist = getById(id);
        if (exist == null) {
            throw new BizException(400, "诊断记录不存在");
        }
        requireVisit(exist.getInpVisitId());
        if (dto == null) {
            throw new BizException(400, "诊断内容不能为空");
        }
        HisInpDiagnosis d = new HisInpDiagnosis();
        d.setId(id);
        if (dto.getDiagType() != null) {
            d.setDiagType(dto.getDiagType());
        }
        if (StringUtils.hasText(dto.getDiagCode())) {
            warnIfIcdSuspect(dto.getDiagCode(), "修改");
            d.setDiagCode(dto.getDiagCode());
        }
        if (StringUtils.hasText(dto.getDiagName())) {
            d.setDiagName(dto.getDiagName().trim());
        }
        if (dto.getIsMain() != null) {
            d.setIsMain(dto.getIsMain());
        }
        // 入院病情/并发症标志(非空更新; 病情取值校验)
        if (dto.getAdmitCondition() != null) {
            if (dto.getAdmitCondition() < 1 || dto.getAdmitCondition() > 4) {
                throw new BizException(400, "入院病情必须为1危急/2严重/3一般/4不适用");
            }
            d.setAdmitCondition(dto.getAdmitCondition());
        }
        if (dto.getComplicationFlag() != null) {
            d.setComplicationFlag(dto.getComplicationFlag());
        }
        updateById(d);
        Integer finalType = dto.getDiagType() != null ? dto.getDiagType() : exist.getDiagType();
        boolean main = dto.getIsMain() != null ? dto.getIsMain() == 1
                : Integer.valueOf(1).equals(exist.getIsMain());
        if (main) {
            clearOtherMain(exist.getInpVisitId(), finalType, id);
        }
        return getById(id);
    }

    /** 删除诊断(逻辑删除, @TableLogic) */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        HisInpDiagnosis exist = getById(id);
        if (exist == null) {
            throw new BizException(400, "诊断记录不存在");
        }
        requireVisit(exist.getInpVisitId());
        removeById(id);
        log.info("删除住院诊断: id={}, visitId={}, diagName={}", id, exist.getInpVisitId(), exist.getDiagName());
    }

    /* ================= ICD 编码校验 ================= */

    /**
     * ICD编码校验(不阻断保存): 返回 {valid, warning, suggestions}。
     * - 格式: ICD-10 字母+2位数字, 可带 .1-4位细分(如 A01.0);
     * - 存在性: 院内诊断字典(his_diag_dict.west, 启用)或标准字典(std_icd10)命中即通过;
     * - 未通过时附最接近的标准编码建议(最多5条), 供前端点击回填编码与名称。
     */
    public Map<String, Object> validateIcdCode(String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("warning", null);
        out.put("suggestions", new ArrayList<Map<String, Object>>());
        if (!StringUtils.hasText(code)) {
            return out; // 未填编码不校验(允许仅按名称录入)
        }
        String c = code.trim().toUpperCase();
        String warning = icdWarningOf(c);
        if (warning != null) {
            out.put("valid", false);
            out.put("warning", warning);
            out.put("suggestions", suggestIcdCodes(c));
        }
        return out;
    }

    /** 编码校验核心(通过返回 null, 未通过返回提示语): 先格式后存在性 */
    private String icdWarningOf(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        String c = code.trim().toUpperCase();
        if (!c.matches("^[A-Z]\\d{2}(\\.\\d{1,4})?$")) {
            return "编码格式不符合ICD-10标准(应为字母+2位数字, 可带.1-4位细分, 如A01.0)";
        }
        if (!existsInDiagDict(c) && !existsInStdIcd10(c)) {
            return "该编码不在标准字典中, 建议从检索结果选择";
        }
        return null;
    }

    /** 保存链路非阻断提醒: 编码可疑时仅记日志, 不抛异常不改变响应 */
    private void warnIfIcdSuspect(String code, String scene) {
        String warning = icdWarningOf(code);
        if (warning != null) {
            log.warn("住院诊断[{}]编码校验提醒: code={}, warning={}", scene, code, warning);
        }
    }

    /** 存在性: 院内诊断字典(西医, 启用) */
    private boolean existsInDiagDict(String code) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_diag_dict WHERE dict_type = 'west' AND code = ? AND status = 1"
                        + " AND deleted = 0 AND tenant_id = ?",
                Integer.class, code, tenantId());
        return cnt != null && cnt > 0;
    }

    /** 存在性: 标准字典 std_icd10(全局表, vali_flag 非 0 视为有效) */
    private boolean existsInStdIcd10(String code) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM std_icd10 WHERE diag_code = ? AND (vali_flag IS NULL OR vali_flag <> '0')",
                Integer.class, code);
        return cnt != null && cnt > 0;
    }

    /**
     * 相近标准编码建议(最多5条): 按 完整输入 -> 字母+2位+细分首位 -> 字母+2位 逐级放宽前缀,
     * 院内字典优先、标准字典兜底, 按编码去重; 建议仅用于提示, 回填后仍可人工修改。
     */
    private List<Map<String, Object>> suggestIcdCodes(String code) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add(code);
        if (code.length() >= 5 && code.charAt(3) == '.') {
            prefixes.add(code.substring(0, 5));
        }
        if (code.length() > 3) {
            prefixes.add(code.substring(0, 3));
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (String prefix : prefixes) {
            collectIcdSuggest(merged, prefix);
            if (merged.size() >= 5) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 单前缀建议收集: 院内字典(status=1)优先, 标准字典(std_icd10)兜底, 按编码去重 */
    private void collectIcdSuggest(Map<String, Map<String, Object>> merged, String prefix) {
        List<Map<String, Object>> local = jdbcTemplate.queryForList(
                "SELECT code AS code, name AS name FROM his_diag_dict"
                        + " WHERE dict_type = 'west' AND status = 1 AND deleted = 0 AND tenant_id = ?"
                        + " AND code LIKE ? ORDER BY code LIMIT 5",
                tenantId(), prefix + "%");
        for (Map<String, Object> row : local) {
            putSuggest(merged, row);
        }
        List<Map<String, Object>> std = jdbcTemplate.queryForList(
                "SELECT diag_code AS code, diag_name AS name FROM std_icd10"
                        + " WHERE diag_code LIKE ? AND (vali_flag IS NULL OR vali_flag <> '0')"
                        + " ORDER BY diag_code LIMIT 5",
                prefix + "%");
        for (Map<String, Object> row : std) {
            putSuggest(merged, row);
        }
    }

    /** 建议条目去重写入(编码为键) */
    private void putSuggest(Map<String, Map<String, Object>> merged, Map<String, Object> row) {
        Object codeObj = row.get("code");
        String c = codeObj == null ? null : String.valueOf(codeObj);
        if (StringUtils.hasText(c) && !merged.containsKey(c)) {
            merged.put(c, row);
        }
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /* ================= 内部实现 ================= */

    /** 同就诊同类型内主诊断互斥: 其他 is_main=1 行置0 */
    private void clearOtherMain(Long visitId, Integer diagType, Long excludeId) {
        lambdaUpdate()
                .set(HisInpDiagnosis::getIsMain, 0)
                .eq(HisInpDiagnosis::getInpVisitId, visitId)
                .eq(diagType != null, HisInpDiagnosis::getDiagType, diagType)
                .eq(HisInpDiagnosis::getIsMain, 1)
                .ne(excludeId != null, HisInpDiagnosis::getId, excludeId)
                .update();
    }

    /** 同就诊同类型内排序号自动递增(当前最大+1) */
    private Integer nextSortNo(Long visitId, Integer diagType) {
        HisInpDiagnosis last = lambdaQuery()
                .eq(HisInpDiagnosis::getInpVisitId, visitId)
                .eq(HisInpDiagnosis::getDiagType, diagType)
                .orderByDesc(HisInpDiagnosis::getSortNo)
                .last("LIMIT 1")
                .one();
        return last == null || last.getSortNo() == null ? 1 : last.getSortNo() + 1;
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
}
