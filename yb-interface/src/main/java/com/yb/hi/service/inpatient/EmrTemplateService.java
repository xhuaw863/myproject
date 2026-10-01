package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.EmrFieldDefDTO;
import com.yb.hi.dto.inpatient.EmrTemplateDTO;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisEmrTemplateMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历结构化模板服务: 维护 8 类标准文书模板(入院记录/首次病程/日常病程/上级医师查房/手术记录/术后病程/出院小结/死亡记录)
 * 及其结构化字段定义, 并在启动时按租户幂等播种标准模板(@Order(7), 表由 DictSchemaMigration@0 建, 机构由 RbacInitializer@3 建)。
 *
 * 编码约定: his_emr_template 唯一键 uk_emr_tpl_code(template_code, deleted) 不含租户 —— 标准编码为全租户共享的
 * 全局主数据(tenant_id=1 优先获得标准编码); 播种前用 JdbcTemplate 全局查重(绕开租户插件), 编码已被其他租户
 * 占用则跳过, 各租户可自建扩展编码模板。creatTemplate 对重复编码返回友好 400。
 */
@Slf4j
@Order(7)
@Service
public class EmrTemplateService implements ApplicationRunner {

    /** 种子模板: {模板编码, 模板名称, record_type, template_category, scope(1住院 2门诊)} */
    private static final String[][] SEED_TEMPLATES = {
            {"EMR_ADMIT", "入院记录", "1", "1", "1"},
            {"EMR_FIRST_PROG", "首次病程记录", "1", "2", "1"},
            {"EMR_DAILY_PROG", "日常病程记录", "1", "3", "1"},
            {"EMR_SENIOR_ROUND", "上级医师查房记录", "1", "4", "1"},
            {"EMR_SURGERY", "手术记录", "6", "5", "1"},
            {"EMR_POST_SURGERY", "术后病程记录", "1", "6", "1"},
            {"EMR_DISCHARGE", "出院小结", "7", "7", "1"},
            {"EMR_DEATH", "死亡记录", "1", "8", "1"},
            {"EMR_OUTP_GENERAL", "门诊病历(通用)", "2", "21", "2"}
    };

    private final HisEmrTemplateMapper templateMapper;
    private final OrgAccessGuard guard;
    private final SysTenantService tenantService;
    private final JdbcTemplate jdbcTemplate;

    public EmrTemplateService(HisEmrTemplateMapper templateMapper, OrgAccessGuard guard,
                              SysTenantService tenantService, JdbcTemplate jdbcTemplate) {
        this.templateMapper = templateMapper;
        this.guard = guard;
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 启动播种 ================= */

    @Override
    public void run(ApplicationArguments args) {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("病历模板种子跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        tenants.sort(Comparator.comparing(SysTenant::getId));
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null) {
                continue;
            }
            if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue; // 平台运营方租户无医院业务数据
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                seedTenant(t.getId());
            } catch (Exception e) {
                log.warn("租户[{}] 病历模板种子跳过(可能表未建): {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 按租户播种缺失的标准模板(逐码判空, 部分缺失可补种; 全局编码被其他租户占用则跳过) */
    private void seedTenant(Long tenantId) {
        Long orgId = resolveLeadOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RbacInitializer 未跑完), 下次启动补种
        }
        int added = 0;
        int occupied = 0;
        for (String[] def : SEED_TEMPLATES) {
            String code = def[0];
            boolean localExists = !templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                    .eq(HisEmrTemplate::getTemplateCode, code)).isEmpty();
            if (localExists) {
                continue;
            }
            if (existsGlobal(code)) {
                occupied++; // 标准编码已被其他租户占用(全局唯一), 本租户跳过
                continue;
            }
            try {
                HisEmrTemplate t = new HisEmrTemplate();
                t.setOrgId(orgId);
                t.setTemplateCode(code);
                t.setTemplateName(def[1]);
                t.setRecordType(Integer.valueOf(def[2]));
                t.setTemplateCategory(Integer.valueOf(def[3]));
                t.setFields(buildSeedFields(code));
                t.setDeptId(0L);
                t.setScope(Integer.valueOf(def[4]));
                t.setVersion(1);
                t.setStatus(1);
                templateMapper.insert(t);
                added++;
            } catch (DuplicateKeyException e) {
                occupied++;
            }
        }
        if (added > 0) {
            log.info("租户[{}] 病历标准模板初始化完成(新增{}个)", tenantId, added);
        }
        if (occupied > 0) {
            log.info("租户[{}] 标准模板编码已由其他租户占用, 跳过{}个(标准编码全租户共享, 可自建扩展模板)", tenantId, occupied);
        }
    }

    /** 解析租户牵头机构: 优先 is_lead=1, 兜底最小 id(his_emr_template.org_id 非空) */
    private Long resolveLeadOrgId(Long tenantId) {
        List<Long> lead = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 AND is_lead = 1 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        if (!lead.isEmpty()) {
            return lead.get(0);
        }
        List<Long> any = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        return any.isEmpty() ? null : any.get(0);
    }

    /** 全局(跨租户)编码查重: JdbcTemplate 直查 deliberate 绕开租户插件, 与 uk_emr_tpl_code 口径一致 */
    private boolean existsGlobal(String code) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_emr_template WHERE template_code = ? AND deleted = 0", Integer.class, code);
        return n != null && n > 0;
    }

    /* ================= 模板查询 ================= */

    /** 模板列表(recordType/category/scope 可选筛选; mine=true 仅返回当前用户可见的启用模板[全院+本科室+本人]; 否则 deptId 非空时含全院 dept_id=0) */
    public R<List<HisEmrTemplate>> listTemplates(Integer recordType, Integer category, Long deptId, Integer scope, boolean mine) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisEmrTemplate> qw =
                Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(recordType != null, HisEmrTemplate::getRecordType, recordType)
                .eq(category != null, HisEmrTemplate::getTemplateCategory, category)
                .eq(scope != null, HisEmrTemplate::getScope, scope);
        LoginUser lu = UserContext.get();
        if (mine && lu != null) {
            final Long sId = lu.getStaffId();
            final Long dId = lu.getDeptId();
            qw.eq(HisEmrTemplate::getStatus, 1);
            qw.and(w -> {
                w.nested(g -> g.eq(HisEmrTemplate::getDeptId, 0L).isNull(HisEmrTemplate::getStaffId));
                if (dId != null) {
                    w.or(d -> d.eq(HisEmrTemplate::getDeptId, dId).isNull(HisEmrTemplate::getStaffId));
                }
                if (sId != null) {
                    w.or(s -> s.eq(HisEmrTemplate::getStaffId, sId));
                }
            });
        } else if (deptId != null) {
            qw.and(w -> w.eq(HisEmrTemplate::getDeptId, deptId).or().eq(HisEmrTemplate::getDeptId, 0L));
        }
        qw.orderByAsc(HisEmrTemplate::getScope)
                .orderByAsc(HisEmrTemplate::getRecordType)
                .orderByAsc(HisEmrTemplate::getTemplateCategory)
                .orderByAsc(HisEmrTemplate::getId);
        return R.ok(templateMapper.selectList(qw));
    }

    /** 模板详情 */
    public R<HisEmrTemplate> getTemplate(Long id) {
        HisEmrTemplate t = id == null ? null : templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "病历模板不存在");
        }
        return R.ok(t);
    }

    /** 按编码获取模板(取版本号最大的启用版本) */
    public R<HisEmrTemplate> getTemplateByCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(400, "模板编码不能为空");
        }
        List<HisEmrTemplate> list = templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getTemplateCode, code.trim())
                .orderByDesc(HisEmrTemplate::getVersion)
                .orderByDesc(HisEmrTemplate::getId));
        if (list.isEmpty()) {
            throw new BizException(400, "模板编码不存在: " + code);
        }
        return R.ok(list.get(0));
    }

    /* ================= 模板维护 ================= */

    /** 创建模板(fields 列表序列化为规范字段定义 JSON) */
    public R<HisEmrTemplate> createTemplate(EmrTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getTemplateCode())) {
            throw new BizException(400, "模板编码不能为空");
        }
        if (!StringUtils.hasText(dto.getTemplateName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        String code = dto.getTemplateCode().trim();
        ensureCodeAvailable(code);
        LoginUser lu = UserContext.get();
        String ownerScope = StringUtils.hasText(dto.getOwnerScope()) ? dto.getOwnerScope().trim().toLowerCase() : "global";
        Long staffId = null;
        Long deptId;
        if ("personal".equals(ownerScope)) {
            if (lu == null || lu.getStaffId() == null) {
                throw new BizException(400, "个人模板须绑定当前登录职工");
            }
            staffId = lu.getStaffId();
            deptId = lu.getDeptId() != null ? lu.getDeptId() : 0L;
        } else if ("dept".equals(ownerScope)) {
            if (lu == null || lu.getDeptId() == null) {
                throw new BizException(400, "科室模板须指定归属科室");
            }
            deptId = dto.getDeptId() != null ? dto.getDeptId() : lu.getDeptId();
            if (!deptId.equals(lu.getDeptId()) && !isAdmin(lu)) {
                throw new BizException(403, "无权为其他科室创建模板");
            }
        } else {
            guard.requireLeadOrg("仅牵头机构管理员可维护全院病历模板");
            deptId = 0L;
        }
        HisEmrTemplate t = new HisEmrTemplate();
        t.setOrgId(guard.currentOrgId());
        t.setTemplateCode(code);
        t.setTemplateName(dto.getTemplateName().trim());
        t.setRecordType(dto.getRecordType());
        t.setTemplateCategory(dto.getTemplateCategory());
        t.setFields(resolveFieldsJson(dto));
        t.setLayout(dto.getLayout());
        t.setScope(dto.getScope() != null ? dto.getScope() : 1);
        t.setStaffId(staffId);
        t.setDeptId(deptId);
        t.setVersion(dto.getVersion() != null ? dto.getVersion() : 1);
        t.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        try {
            templateMapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板编码已存在: " + code);
        }
        log.info("新建病历模板: id={}, code={}, name={}, owner={}", t.getId(), code, t.getTemplateName(), ownerScope);
        return R.ok(templateMapper.selectById(t.getId()));
    }

    /** 更新模板(版本号自增, 支持停用/启用) */
    public R<Void> updateTemplate(Long id, EmrTemplateDTO dto) {
        HisEmrTemplate exist = id == null ? null : templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        if (dto == null) {
            throw new BizException(400, "模板内容不能为空");
        }
        if (StringUtils.hasText(dto.getTemplateCode())) {
            String code = dto.getTemplateCode().trim();
            if (!code.equals(exist.getTemplateCode())) {
                ensureCodeAvailable(code);
                exist.setTemplateCode(code);
            }
        }
        if (StringUtils.hasText(dto.getTemplateName())) {
            exist.setTemplateName(dto.getTemplateName().trim());
        }
        if (dto.getRecordType() != null) {
            exist.setRecordType(dto.getRecordType());
        }
        if (dto.getTemplateCategory() != null) {
            exist.setTemplateCategory(dto.getTemplateCategory());
        }
        if (dto.getFields() != null || dto.getRawFields() != null) {
            exist.setFields(resolveFieldsJson(dto));
        }
        if (dto.getLayout() != null) {
            exist.setLayout(dto.getLayout());
        }
        if (dto.getScope() != null) {
            exist.setScope(dto.getScope());
        }
        if (dto.getDeptId() != null) {
            exist.setDeptId(dto.getDeptId());
        }
        if (dto.getStatus() != null) {
            exist.setStatus(dto.getStatus());
        }
        exist.setVersion((exist.getVersion() == null ? 1 : exist.getVersion()) + 1);
        try {
            templateMapper.updateById(exist);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板编码已存在: " + exist.getTemplateCode());
        }
        log.info("更新病历模板: id={}, code={}, version={}", id, exist.getTemplateCode(), exist.getVersion());
        return R.ok();
    }

    /** 删除模板(逻辑删除; 按归属层级鉴权) */
    public R<Void> removeTemplate(Long id) {
        HisEmrTemplate exist = id == null ? null : templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        templateMapper.deleteById(id);
        log.info("删除病历模板: id={}", id);
        return R.ok();
    }

    /** 获取模板完整字段定义 JSON(含 dictRef/subFields/section 等新属性, 供设计器/增强渲染器使用) */
    public R<Object> getDefs(Long id) {
        HisEmrTemplate t = id == null ? null : templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "病历模板不存在");
        }
        return R.ok(parseArraySafe(t.getFields()));
    }

    /** 获取模板字段定义(供前端渲染表单): 规范化为纯字符串键值对列表 */
    public List<Map<String, String>> getFieldDefs(Long templateId) {
        HisEmrTemplate t = templateId == null ? null : templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(400, "病历模板不存在");
        }
        List<Map<String, String>> list = new ArrayList<>();
        JSONArray arr = parseArraySafe(t.getFields());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject f = arr.getJSONObject(i);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("fieldKey", text(f.get("fieldKey")));
            m.put("label", text(f.get("label")));
            m.put("type", text(f.get("type")));
            m.put("required", String.valueOf(Boolean.TRUE.equals(asBoolean(f.get("required")))));
            m.put("maxLength", text(f.get("maxLength")));
            m.put("options", joinOptions(f.get("options")));
            m.put("defaultValue", text(f.get("defaultValue")));
            m.put("defaultMacro", text(f.get("defaultMacro")));
            m.put("placeholder", text(f.get("placeholder")));
            list.add(m);
        }
        return list;
    }

    /* ================= 内部实现 ================= */

    /** 编码可用性校验: 本租户重复 + 全局唯一键预检(友好 400 避免落库才报唯一键冲突) */
    private void ensureCodeAvailable(String code) {
        boolean localDup = !templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getTemplateCode, code)).isEmpty();
        if (localDup) {
            throw new BizException(400, "模板编码已存在: " + code);
        }
        if (existsGlobal(code)) {
            throw new BizException(400, "模板编码已被占用(标准编码全租户共享): " + code);
        }
    }

    /** 字段 JSON 落库: 设计器 rawFields 优先(原样序列化保留 dictRef/subFields/section), 否则回退 EmrFieldDefDTO 规范形状 */
    private String resolveFieldsJson(EmrTemplateDTO dto) {
        if (dto.getRawFields() != null) {
            return JSON.toJSONString(dto.getRawFields());
        }
        return JSON.toJSONString(toFieldJsonArray(dto.getFields()));
    }

    private boolean isAdmin(LoginUser lu) {
        return lu != null && lu.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN, Roles.ORG_ADMIN);
    }

    /** 模板可编辑性守卫: 个人=本人或管理员; 科室=同科室或管理员; 全院=牵头机构管理员 */
    private void guardEditable(HisEmrTemplate t) {
        LoginUser lu = UserContext.get();
        if (t.getStaffId() != null) {
            if (!isAdmin(lu) && (lu == null || !t.getStaffId().equals(lu.getStaffId()))) {
                throw new BizException(403, "无权编辑他人个人模板");
            }
        } else if (t.getDeptId() != null && t.getDeptId() != 0L) {
            if (!isAdmin(lu) && (lu == null || !t.getDeptId().equals(lu.getDeptId()))) {
                throw new BizException(403, "无权编辑其他科室模板");
            }
        } else {
            guard.requireLeadOrg("仅牵头机构管理员可维护全院病历模板");
        }
    }

    /** EmrFieldDefDTO 列表 → 模板 fields JSON 数组(按 sortNo 排序, 规范形状 fieldKey/label/type/required/...) */
    private JSONArray toFieldJsonArray(List<EmrFieldDefDTO> defs) {
        JSONArray arr = new JSONArray();
        if (defs == null) {
            return arr;
        }
        List<EmrFieldDefDTO> sorted = new ArrayList<>(defs);
        sorted.sort(Comparator.comparingInt(d -> d.getSortNo() == null ? Integer.MAX_VALUE : d.getSortNo()));
        for (EmrFieldDefDTO d : sorted) {
            if (!StringUtils.hasText(d.getFieldCode())) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("fieldKey", d.getFieldCode().trim());
            o.put("label", d.getFieldName());
            o.put("type", StringUtils.hasText(d.getFieldType()) ? d.getFieldType().trim() : "text");
            o.put("required", d.getRequired() != null && d.getRequired() == 1);
            if (StringUtils.hasText(d.getOptions())) {
                o.put("options", splitOptions(d.getOptions()));
            }
            if (StringUtils.hasText(d.getDefaultValue())) {
                o.put("defaultValue", d.getDefaultValue());
            }
            if (StringUtils.hasText(d.getPlaceholder())) {
                o.put("placeholder", d.getPlaceholder());
            }
            arr.add(o);
        }
        return arr;
    }

    /** 选项归一: 支持 JSON 数组字符串或逗号/分号分隔文本 */
    private JSONArray splitOptions(String s) {
        String t = s.trim();
        if (t.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(t);
                if (arr != null) {
                    return arr;
                }
            } catch (Exception ignore) {
                // 非合法 JSON 则按分隔符拆分
            }
        }
        JSONArray arr = new JSONArray();
        for (String p : t.split("[,，;；]")) {
            if (StringUtils.hasText(p)) {
                arr.add(p.trim());
            }
        }
        return arr;
    }

    private String joinOptions(Object options) {
        if (options == null) {
            return "";
        }
        if (options instanceof JSONArray) {
            JSONArray arr = (JSONArray) options;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(text(arr.get(i)));
            }
            return sb.toString();
        }
        return text(options);
    }

    private static String text(Object v) {
        return v == null ? "" : (v instanceof String ? (String) v : String.valueOf(v));
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private JSONArray parseArraySafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            log.warn("模板字段定义JSON解析失败: {}", e.getMessage());
            return new JSONArray();
        }
    }

    /* ================= 种子字段定义 ================= */

    private static JSONObject f(String key, String label, String type, boolean required, Object... extra) {
        JSONObject o = new JSONObject();
        o.put("fieldKey", key);
        o.put("label", label);
        o.put("type", type);
        o.put("required", required);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            o.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return o;
    }

    /** 8 类标准模板的字段定义(与任务规格逐字段一致) */
    private static String buildSeedFields(String code) {
        JSONArray a = new JSONArray();
        switch (code) {
            case "EMR_ADMIT":
                a.add(f("chiefComplaint", "主诉", "textarea", true, "maxLength", 200));
                a.add(f("presentIllness", "现病史", "textarea", true));
                a.add(f("pastHistory", "既往史", "textarea", false, "defaultMacro", "past_history"));
                a.add(f("personalHistory", "个人史", "textarea", false));
                a.add(f("familyHistory", "家族史", "textarea", false));
                a.add(f("allergyHistory", "过敏史", "textarea", true, "defaultMacro", "allergy_info"));
                a.add(f("physicalExam", "体格检查", "textarea", true));
                a.add(f("specialExam", "专科检查", "textarea", false));
                a.add(f("auxiliaryExam", "辅助检查", "textarea", false));
                a.add(f("admitDiagnosis", "初步诊断", "textarea", true, "defaultMacro", "admit_diag"));
                a.add(f("treatPlan", "诊疗计划", "textarea", true));
                break;
            case "EMR_FIRST_PROG":
                a.add(f("caseFeatures", "病例特点", "textarea", true));
                a.add(f("diagBasis", "诊断依据", "textarea", true));
                a.add(f("diffDiag", "鉴别诊断", "textarea", true));
                a.add(f("treatPlan", "诊疗计划", "textarea", true));
                break;
            case "EMR_DAILY_PROG":
                a.add(f("subjective", "主观(S)", "textarea", true));
                a.add(f("objective", "客观(O)", "textarea", true, "defaultMacro", "vital_signs"));
                a.add(f("assessment", "评估(A)", "textarea", true));
                a.add(f("plan", "计划(P)", "textarea", true));
                break;
            case "EMR_SENIOR_ROUND": {
                JSONArray roundOpts = new JSONArray();
                roundOpts.add("主治医师查房");
                roundOpts.add("副主任医师查房");
                roundOpts.add("主任医师查房");
                a.add(f("roundLevel", "查房级别", "select", true, "options", roundOpts));
                a.add(f("attendingDoctor", "查房医师", "text", true, "defaultMacro", "attending_doctor"));
                a.add(f("patientCondition", "病情汇报", "textarea", true));
                a.add(f("attendingOpinion", "上级医师意见", "textarea", true));
                a.add(f("treatAdjust", "诊疗调整", "textarea", false));
                break;
            }
            case "EMR_SURGERY":
                a.add(f("surgeryName", "手术名称", "text", true, "defaultMacro", "surgery_name"));
                a.add(f("surgeryDate", "手术日期", "date", true, "defaultMacro", "surgery_date"));
                a.add(f("surgeon", "术者", "text", true, "defaultMacro", "surgeon_name"));
                a.add(f("assistant", "助手", "text", false));
                a.add(f("anesthesia", "麻醉方式", "text", true));
                a.add(f("preOpDiag", "术前诊断", "textarea", true));
                a.add(f("postOpDiag", "术后诊断", "textarea", true));
                a.add(f("surgeryProcess", "手术经过", "textarea", true));
                a.add(f("specimen", "术中标本", "textarea", false));
                a.add(f("bleeding", "术中出血量(ml)", "number", true));
                a.add(f("infusion", "术中输液量(ml)", "number", false));
                break;
            case "EMR_POST_SURGERY":
                a.add(f("surgeryName", "手术名称", "text", true));
                a.add(f("anesthesiaRecovery", "麻醉恢复", "textarea", true));
                a.add(f("postCondition", "术后情况", "textarea", true));
                a.add(f("postOrders", "术后医嘱", "textarea", true));
                a.add(f("attention", "注意事项", "textarea", false));
                break;
            case "EMR_DISCHARGE":
                a.add(f("admitDate", "入院日期", "date", true, "defaultMacro", "admit_date"));
                a.add(f("dischargeDate", "出院日期", "date", true, "defaultMacro", "discharge_date"));
                a.add(f("admitDiag", "入院诊断", "textarea", true, "defaultMacro", "admit_diag"));
                a.add(f("dischargeDiag", "出院诊断", "textarea", true, "defaultMacro", "discharge_diag"));
                a.add(f("treatSummary", "诊疗经过", "textarea", true));
                a.add(f("dischargeCondition", "出院情况", "textarea", true));
                a.add(f("dischargeOrders", "出院医嘱", "textarea", true));
                a.add(f("followUp", "随访计划", "textarea", false));
                break;
            case "EMR_DEATH":
                a.add(f("admitDate", "入院日期", "date", true, "defaultMacro", "admit_date"));
                a.add(f("deathTime", "死亡时间", "datetime", true));
                a.add(f("admitDiag", "入院诊断", "textarea", true));
                a.add(f("deathDiag", "死亡诊断", "textarea", true));
                a.add(f("deathCause", "死亡原因", "textarea", true));
                a.add(f("treatProcess", "诊疗经过", "textarea", true));
                a.add(f("rescueProcess", "抢救经过", "textarea", false));
                break;
            case "EMR_OUTP_GENERAL":
                a.add(f("sec_1", "主诉与病史", "section", false));
                a.add(f("chiefComplaint", "主诉", "textarea", true, "maxLength", 200));
                a.add(f("presentIllness", "现病史", "textarea", true));
                a.add(f("pastHistory", "既往史", "textarea", false, "defaultMacro", "past_history"));
                a.add(f("allergyHistory", "过敏史", "textarea", false, "defaultMacro", "allergy_info"));
                a.add(f("sec_2", "体格检查", "section", false));
                a.add(f("vitals", "生命体征", "vitals", false));
                a.add(f("physicalExam", "体格检查", "textarea", true));
                a.add(f("auxExam", "辅助检查", "textarea", false));
                a.add(f("sec_3", "诊断与处理", "section", false));
                a.add(f("diagnosis", "门诊诊断", "diagnosis", false, "defaultMacro", "main_diag"));
                a.add(f("treatmentOpinion", "处理意见", "textarea", true));
                a.add(f("followupNote", "随访建议", "textarea", false));
                break;
            default:
                break;
        }
        return JSON.toJSONString(a);
    }
}
