package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysParam;
import com.yb.hi.platform.entity.SysParamGroup;
import com.yb.hi.platform.mapper.SysParamGroupMapper;
import com.yb.hi.platform.mapper.SysParamMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统参数维护服务(分组 / 全局定义 / 各作用域覆盖值)。
 *
 * sys_param 与 sys_param_group 均已豁免 MyBatis-Plus 租户插件, 本服务遵循两条硬约束:
 *  1. 所有 SQL 显式携带 tenant_id: 定义行 tenant_id=0, 覆盖行 tenant_id=当前租户;
 *  2. 唯一键 uk_param_scope(param_key, scope_level, scope_id) 不含 deleted, 逻辑删除的行
 *     仍占键位, 故 UPSERT 必须先复活/命中已存在行, 不能简单 INSERT(否则唯一键冲突)。
 *
 * 作用域: 0=全局(定义行, 仅平台超管) 1=租户 2=机构 3=科室; 覆盖值空则回退继承。
 */
@Service
public class SystemParamService {

    /** 支持的数据类型(兼容 int/integer、bool/boolean 两种写法) */
    private static final Set<String> DATA_TYPES = new HashSet<>(Arrays.asList(
            "string", "int", "integer", "decimal", "bool", "boolean", "enum"));

    private static final String[] SCOPE_LABELS = {"全局", "租户", "机构", "科室"};

    private final SysParamMapper paramMapper;
    private final SysParamGroupMapper groupMapper;
    private final SystemParamResolver resolver;
    private final OrgAccessGuard orgAccessGuard;
    private final JdbcTemplate jdbcTemplate;

    public SystemParamService(SysParamMapper paramMapper, SysParamGroupMapper groupMapper,
                              SystemParamResolver resolver, OrgAccessGuard orgAccessGuard,
                              JdbcTemplate jdbcTemplate) {
        this.paramMapper = paramMapper;
        this.groupMapper = groupMapper;
        this.resolver = resolver;
        this.orgAccessGuard = orgAccessGuard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 分组维护(全局共享, 无 tenant_id 列) ================= */

    /** 分组列表(按 sort_no, id) */
    public List<SysParamGroup> listGroups() {
        return groupMapper.selectList(new QueryWrapper<SysParamGroup>().orderByAsc("sort_no", "id"));
    }

    /** 新增/更新分组(id 为空则新增, 非空则更新; 分组编码唯一) */
    @Transactional
    public void saveGroup(SysParamGroup group) {
        if (group == null || !StringUtils.hasText(group.getGroupCode())) {
            throw new BizException("分组编码不能为空");
        }
        group.setGroupCode(group.getGroupCode().trim());
        if (group.getId() == null) {
            Long n = groupMapper.selectCount(new QueryWrapper<SysParamGroup>().eq("group_code", group.getGroupCode()));
            if (n != null && n > 0) {
                throw new BizException("分组编码已存在: " + group.getGroupCode());
            }
            groupMapper.insert(group);
        } else {
            SysParamGroup db = groupMapper.selectById(group.getId());
            if (db == null) {
                throw new BizException("分组不存在: " + group.getId());
            }
            Long n = groupMapper.selectCount(new QueryWrapper<SysParamGroup>()
                    .eq("group_code", group.getGroupCode()).ne("id", group.getId()));
            if (n != null && n > 0) {
                throw new BizException("分组编码已存在: " + group.getGroupCode());
            }
            groupMapper.updateById(group);
        }
    }

    /** 逻辑删除分组 */
    @Transactional
    public void deleteGroup(Long groupId) {
        if (groupId == null) {
            throw new BizException("分组ID不能为空");
        }
        groupMapper.deleteById(groupId);
    }

    /* ================= 全局参数定义(scope_level=0, tenant_id=0) ================= */

    /** 全局参数定义列表(含全部元数据字段), 可按分组编码过滤 */
    public List<Map<String, Object>> listDefinitions(String groupCode) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, param_key, param_name, group_code, data_type, default_value, param_value,"
                        + " enum_options, min_value, max_value, required, allow_scope, remark, update_by, update_time"
                        + " FROM sys_param WHERE scope_level = 0 AND tenant_id = 0 AND deleted = 0");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(groupCode)) {
            sql.append(" AND group_code = ?");
            args.add(groupCode);
        }
        sql.append(" ORDER BY group_code, param_key");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 创建参数定义行: 强制 scope_level=0, scope_id=0, tenant_id=0, param_value=default_value */
    @Transactional
    public void createDefinition(SysParam param) {
        if (param == null || !StringUtils.hasText(param.getParamKey())) {
            throw new BizException("参数键不能为空");
        }
        String key = param.getParamKey().trim();
        param.setParamKey(key);
        String dataType = StringUtils.hasText(param.getDataType()) ? param.getDataType().trim().toLowerCase() : "string";
        if (!DATA_TYPES.contains(dataType)) {
            throw new BizException("不支持的数据类型: " + param.getDataType() + "(可选: string/int/decimal/bool/enum)");
        }
        param.setDataType(dataType);
        if (StringUtils.hasText(param.getGroupCode())) {
            param.setGroupCode(param.getGroupCode().trim());
            Long n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_param_group WHERE group_code = ? AND deleted = 0",
                    Long.class, param.getGroupCode());
            if (n == null || n == 0) {
                throw new BizException("分组不存在: " + param.getGroupCode());
            }
        }
        if (param.getRequired() == null) {
            param.setRequired(0);
        }
        if (!StringUtils.hasText(param.getAllowScope())) {
            param.setAllowScope("0,1,2,3");
        } else {
            param.setAllowScope(param.getAllowScope().trim());
        }
        validateValue(param.getDefaultValue(), param);
        // 强制全局定义行
        param.setTenantId(0L);
        param.setScopeLevel(0);
        param.setScopeId(0L);
        param.setParamValue(param.getDefaultValue());
        // 唯一键不含 deleted: 已存在行(含逻辑删除)时复活重置, 未删除时拒绝重键
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 LIMIT 1", key);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            Number del = (Number) row.get("deleted");
            if (del != null && del.intValue() == 0) {
                throw new BizException("参数键已存在: " + key);
            }
            Long id = ((Number) row.get("id")).longValue();
            jdbcTemplate.update(
                    "UPDATE sys_param SET tenant_id = 0, param_name = ?, param_value = ?, group_code = ?,"
                            + " data_type = ?, default_value = ?, enum_options = ?, min_value = ?, max_value = ?,"
                            + " required = ?, allow_scope = ?, remark = ?, deleted = 0, update_by = ?, update_time = NOW()"
                            + " WHERE id = ?",
                    param.getParamName(), param.getParamValue(), param.getGroupCode(), param.getDataType(),
                    param.getDefaultValue(), param.getEnumOptions(), param.getMinValue(), param.getMaxValue(),
                    param.getRequired(), param.getAllowScope(), param.getRemark(), UserContext.username(), id);
            return;
        }
        paramMapper.insert(param);
    }

    /** 更新定义行元数据(param_name/data_type/default_value/enum_options/min/max/required/allow_scope/remark), 不改 param_key */
    @Transactional
    public void updateDefinition(SysParam param) {
        if (param == null || !StringUtils.hasText(param.getParamKey())) {
            throw new BizException("参数键不能为空");
        }
        SysParam def = findDefinition(param.getParamKey().trim());
        if (def == null) {
            throw new BizException("参数定义不存在: " + param.getParamKey());
        }
        if (StringUtils.hasText(param.getDataType())) {
            String dt = param.getDataType().trim().toLowerCase();
            if (!DATA_TYPES.contains(dt)) {
                throw new BizException("不支持的数据类型: " + param.getDataType() + "(可选: string/int/decimal/bool/enum)");
            }
            def.setDataType(dt);
        }
        def.setParamName(param.getParamName());
        def.setEnumOptions(param.getEnumOptions());
        def.setMinValue(param.getMinValue());
        def.setMaxValue(param.getMaxValue());
        def.setRequired(param.getRequired());
        def.setAllowScope(param.getAllowScope());
        def.setRemark(param.getRemark());
        if (param.getDefaultValue() != null) {
            def.setDefaultValue(param.getDefaultValue());
            // 定义行 param_value 与 default_value 保持同步, 保证全局生效值一致
            def.setParamValue(param.getDefaultValue());
        }
        validateValue(def.getDefaultValue(), def);
        paramMapper.updateById(def);
    }

    /** 删除参数定义: 逻辑删除定义行 + 级联删除该参数的全部覆盖行(含他租户覆盖, 保证解析不再命中) */
    @Transactional
    public void deleteDefinition(String paramKey) {
        if (!StringUtils.hasText(paramKey)) {
            throw new BizException("参数键不能为空");
        }
        SysParam def = findDefinition(paramKey.trim());
        if (def == null) {
            throw new BizException("参数定义不存在: " + paramKey);
        }
        jdbcTemplate.update(
                "UPDATE sys_param SET deleted = 1, update_by = ?, update_time = NOW() WHERE param_key = ? AND deleted = 0",
                UserContext.username(), def.getParamKey());
    }

    /* ================= 作用域视图(全局定义 LEFT JOIN 该作用域覆盖行) ================= */

    /**
     * 查询指定作用域的参数视图: 每个参数返回 {..., override_value, override_level, effective_value}
     * (effective_value = 覆盖值优先, 未覆盖回退 default_value)。scopeId 为空时按上下文兜底。
     */
    public List<Map<String, Object>> listByScope(int scopeLevel, Long scopeId, String groupCode) {
        if (scopeLevel < 0 || scopeLevel > 3) {
            throw new BizException("作用域层级非法: " + scopeLevel);
        }
        Long tenantId;
        long sid;
        if (scopeLevel == 0) {
            // 全局视图: 覆盖行即定义行自身(tenant_id=0, scope_id=0)
            tenantId = 0L;
            sid = 0L;
        } else {
            tenantId = TenantContext.get();
            if (tenantId == null) {
                throw new BizException("租户上下文缺失");
            }
            sid = normalizeScopeId(scopeLevel, scopeId, tenantId);
        }
        StringBuilder sql = new StringBuilder(
                "SELECT d.param_key, d.param_name, d.group_code, d.data_type, d.default_value,"
                        + " d.enum_options, d.min_value, d.max_value, d.required, d.allow_scope, d.remark,"
                        + " o.param_value AS override_value, o.scope_level AS override_level,"
                        + " COALESCE(o.param_value, d.default_value) AS effective_value"
                        + " FROM sys_param d"
                        + " LEFT JOIN sys_param o ON o.param_key = d.param_key"
                        + " AND o.scope_level = ? AND o.scope_id = ? AND o.deleted = 0 AND o.tenant_id = ?"
                        + " WHERE d.scope_level = 0 AND d.tenant_id = 0 AND d.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(scopeLevel);
        args.add(sid);
        args.add(tenantId);
        if (StringUtils.hasText(groupCode)) {
            sql.append(" AND d.group_code = ?");
            args.add(groupCode);
        }
        sql.append(" ORDER BY d.group_code, d.param_key");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 生效参数列表: 对每个全局定义行解析出最终生效值与来源级别(source_level), 供展示"当前生效" */
    public List<Map<String, Object>> getEffectiveParams(Long tenantId, Long orgId, Long deptId, String groupCode) {
        StringBuilder sql = new StringBuilder(
                "SELECT param_key, param_name, group_code, data_type, default_value, enum_options,"
                        + " min_value, max_value, required, allow_scope, remark"
                        + " FROM sys_param WHERE scope_level = 0 AND tenant_id = 0 AND deleted = 0");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(groupCode)) {
            sql.append(" AND group_code = ?");
            args.add(groupCode);
        }
        sql.append(" ORDER BY group_code, param_key");
        List<Map<String, Object>> defs = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>(defs.size());
        for (Map<String, Object> def : defs) {
            String key = String.valueOf(def.get("param_key"));
            SystemParamResolver.ParamHit hit = resolver.resolveHit(key, tenantId, orgId, deptId);
            Map<String, Object> row = new LinkedHashMap<>(def);
            row.put("effective_value", hit.getValue());
            row.put("source_level", hit.getScopeLevel());
            out.add(row);
        }
        return out;
    }

    /* ================= 覆盖值维护 ================= */

    /**
     * 保存参数值(按作用域 UPSERT):
     *  - scopeLevel=0: 更新定义行 default_value + param_value(全局生效值);
     *  - scopeLevel>0: 校验 allow_scope 与归属后, 按唯一键 uk_param_scope 新增/更新/复活覆盖行。
     */
    @Transactional
    public void saveParam(String paramKey, String value, int scopeLevel, Long scopeId) {
        if (!StringUtils.hasText(paramKey)) {
            throw new BizException("参数键不能为空");
        }
        if (scopeLevel < 0 || scopeLevel > 3) {
            throw new BizException("作用域层级非法: " + scopeLevel);
        }
        String key = paramKey.trim();
        SysParam def = findDefinition(key);
        if (def == null) {
            throw new BizException("参数定义不存在: " + key);
        }
        assertScopeAllowed(def, scopeLevel);
        validateValue(value, def);
        String user = UserContext.username();
        if (scopeLevel == 0) {
            jdbcTemplate.update(
                    "UPDATE sys_param SET default_value = ?, param_value = ?, update_by = ?, update_time = NOW() WHERE id = ?",
                    value, value, user, def.getId());
            return;
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BizException("租户上下文缺失");
        }
        Long sid = normalizeScopeId(scopeLevel, scopeId, tenantId);
        assertScopeOwnership(scopeLevel, sid);
        // uk_param_scope 不含 deleted: ON DUPLICATE KEY 一条 SQL 覆盖 新增/更新/复活(逻辑删除后重设)
        jdbcTemplate.update(
                "INSERT INTO sys_param (tenant_id, param_key, param_value, scope_level, scope_id, group_code,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, NOW(), 0)"
                        + " ON DUPLICATE KEY UPDATE tenant_id = ?, param_value = ?, deleted = 0, update_by = ?, update_time = NOW()",
                tenantId, key, value, scopeLevel, sid, def.getGroupCode(), user, user,
                tenantId, value, user);
    }

    /** 删除覆盖值(逻辑删除, 恢复继承); 幂等: 无覆盖行时静默返回 */
    @Transactional
    public void deleteOverride(String paramKey, int scopeLevel, Long scopeId) {
        if (!StringUtils.hasText(paramKey)) {
            throw new BizException("参数键不能为空");
        }
        if (scopeLevel < 1 || scopeLevel > 3) {
            throw new BizException("仅支持删除租户/机构/科室级覆盖值, 全局默认值请通过定义维护修改");
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BizException("租户上下文缺失");
        }
        Long sid = normalizeScopeId(scopeLevel, scopeId, tenantId);
        assertScopeOwnership(scopeLevel, sid);
        jdbcTemplate.update(
                "UPDATE sys_param SET deleted = 1, update_by = ?, update_time = NOW()"
                        + " WHERE param_key = ? AND scope_level = ? AND scope_id = ? AND tenant_id = ? AND deleted = 0",
                UserContext.username(), paramKey.trim(), scopeLevel, sid, tenantId);
    }

    /* ================= 值校验 ================= */

    /** 按 data_type / min / max / required / enum_options 校验参数值合法性 */
    public void validateValue(String value, SysParam meta) {
        if (meta == null) {
            throw new BizException("参数元数据缺失");
        }
        String name = StringUtils.hasText(meta.getParamName()) ? meta.getParamName() : meta.getParamKey();
        boolean required = meta.getRequired() != null && meta.getRequired() == 1;
        if (value == null || value.trim().isEmpty()) {
            if (required) {
                throw new BizException("参数[" + name + "]为必填, 不能为空");
            }
            return;
        }
        String v = value.trim();
        String dt = StringUtils.hasText(meta.getDataType()) ? meta.getDataType().trim().toLowerCase() : "string";
        BigDecimal num = null;
        if ("int".equals(dt) || "integer".equals(dt)) {
            try {
                num = new BigDecimal(Integer.parseInt(v));
            } catch (NumberFormatException e) {
                throw new BizException("参数[" + name + "]必须为整数: " + value);
            }
        } else if ("decimal".equals(dt)) {
            try {
                num = new BigDecimal(v);
            } catch (NumberFormatException e) {
                throw new BizException("参数[" + name + "]必须为数值: " + value);
            }
        } else if ("bool".equals(dt) || "boolean".equals(dt)) {
            if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                throw new BizException("参数[" + name + "]必须为 true/false: " + value);
            }
        } else if ("enum".equals(dt)) {
            String opts = meta.getEnumOptions();
            boolean hit = false;
            if (StringUtils.hasText(opts)) {
                for (String o : opts.split(",")) {
                    if (o.trim().equals(v)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                throw new BizException("参数[" + name + "]取值必须在枚举范围内(" + opts + "): " + value);
            }
        }
        if (num != null) {
            if (meta.getMinValue() != null && num.compareTo(meta.getMinValue()) < 0) {
                throw new BizException("参数[" + name + "]不能小于 " + meta.getMinValue().stripTrailingZeros().toPlainString());
            }
            if (meta.getMaxValue() != null && num.compareTo(meta.getMaxValue()) > 0) {
                throw new BizException("参数[" + name + "]不能大于 " + meta.getMaxValue().stripTrailingZeros().toPlainString());
            }
        }
    }

    /* ================= 私有辅助 ================= */

    /** 取全局定义行(scope_level=0, tenant_id=0, 未删除) */
    private SysParam findDefinition(String paramKey) {
        return paramMapper.selectOne(new QueryWrapper<SysParam>()
                .eq("param_key", paramKey)
                .eq("scope_level", 0)
                .eq("tenant_id", 0)
                .last("LIMIT 1"));
    }

    /** 校验目标作用域在参数的 allow_scope 允许列表内 */
    private void assertScopeAllowed(SysParam def, int scopeLevel) {
        String allow = StringUtils.hasText(def.getAllowScope()) ? def.getAllowScope() : "0,1,2,3";
        for (String s : allow.split(",")) {
            if (s.trim().equals(String.valueOf(scopeLevel))) {
                return;
            }
        }
        throw new BizException("参数[" + def.getParamName() + "]不允许在" + scopeLabel(scopeLevel) + "级设置");
    }

    /** scopeId 为空时按层级从上下文兜底; 租户级强制 scopeId=当前租户 */
    private Long normalizeScopeId(int scopeLevel, Long scopeId, Long tenantId) {
        Long sid = scopeId;
        if (sid == null) {
            LoginUser u = UserContext.get();
            if (scopeLevel == 1) {
                sid = tenantId;
            } else if (scopeLevel == 2) {
                sid = u == null ? null : u.getOrgId();
            } else if (scopeLevel == 3) {
                sid = u == null ? null : u.getDeptId();
            }
        }
        if (sid == null) {
            throw new BizException("作用域ID(scopeId)不能为空");
        }
        if (scopeLevel == 1 && !sid.equals(tenantId)) {
            throw new BizException(403, "租户级参数只能操作当前租户");
        }
        return sid;
    }

    /**
     * 作用域归属校验(机构/科室级):
     *  - 平台超管不限; 牵头机构管理员可维护本租户内任意机构/科室;
     *  - 非牵头机构管理员仅可维护本机构及其科室。
     */
    private void assertScopeOwnership(int scopeLevel, Long scopeId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (Roles.SUPER_ADMIN.equals(u.getRole())) {
            return;
        }
        Long tenantId = u.getTenantId();
        if (scopeLevel == 2) {
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_org WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    Long.class, scopeId, tenantId);
            if (cnt == null || cnt == 0) {
                throw new BizException(403, "目标机构不存在或不属于当前医院");
            }
            if (!orgAccessGuard.isLead(u) && !scopeId.equals(u.getOrgId())) {
                throw new BizException(403, "非牵头机构管理员仅可维护本机构参数");
            }
        } else if (scopeLevel == 3) {
            List<Long> orgIds = jdbcTemplate.queryForList(
                    "SELECT org_id FROM his_dept WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    Long.class, scopeId, tenantId);
            if (orgIds.isEmpty() || orgIds.get(0) == null) {
                throw new BizException(403, "目标科室不存在或不属于当前医院");
            }
            if (!orgAccessGuard.isLead(u) && !orgIds.get(0).equals(u.getOrgId())) {
                throw new BizException(403, "非牵头机构管理员仅可维护本机构科室参数");
            }
        }
    }

    private String scopeLabel(int scopeLevel) {
        return scopeLevel >= 0 && scopeLevel < SCOPE_LABELS.length ? SCOPE_LABELS[scopeLevel] : ("层级" + scopeLevel);
    }
}
