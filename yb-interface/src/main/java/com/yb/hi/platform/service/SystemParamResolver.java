package com.yb.hi.platform.service;

import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统参数四级解析器(全局0 → 租户1 → 机构2 → 科室3)。
 *
 * sys_param 已豁免 MyBatis-Plus 租户插件(全局行 tenant_id=0 与租户行共存), 故本类全部走
 * JdbcTemplate 手写 SQL 并显式携带 tenant_id 条件:
 *  - 全局行: tenant_id=0; 租户/机构/科室行: tenant_id=当前租户;
 *  - 租户级 scope_id=tenantId, 机构级 scope_id=orgId, 科室级 scope_id=deptId。
 *
 * 解析语义: 在"全局行 + 调用者命中的各层级覆盖行"中取 scope_level 最大的且 param_value 非空
 * 的行; 无任何命中时回退定义行(scope_level=0)的 default_value 作为内置默认; 参数未定义时返回 null。
 * "非空"按"空白即无效"口径实现(TRIM 后非空字符串): saveParam 写入空串代表清除该层覆盖并恢复继承
 * (见 ParamSaveReq 注释), 故空串行不得参与层级优先级竞争, 否则高层级空行会遮蔽低层级真值与全局默认。
 * orgId/deptId 为 null 时对应层级不参与解析, 故未登录(无租户上下文)时仅全局行可见。
 */
@Component
public class SystemParamResolver {

    private final JdbcTemplate jdbcTemplate;

    public SystemParamResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 解析结果: 生效值 + 来源作用域层级(0/1/2/3; -1=参数未定义) */
    public static class ParamHit {
        private final String value;
        private final int scopeLevel;

        public ParamHit(String value, int scopeLevel) {
            this.value = value;
            this.scopeLevel = scopeLevel;
        }

        public String getValue() {
            return value;
        }

        public int getScopeLevel() {
            return scopeLevel;
        }
    }

    /* ================= 单参数解析 ================= */

    /** 按当前登录用户上下文解析参数 */
    public String resolve(String paramKey) {
        return resolveHit(paramKey).getValue();
    }

    /** 按当前登录用户上下文解析参数(含来源级别), 供参数管理页展示"当前生效值" */
    public ParamHit resolveHit(String paramKey) {
        LoginUser u = UserContext.get();
        Long tenantId = u != null && u.getTenantId() != null ? u.getTenantId() : TenantContext.get();
        Long orgId = u == null ? null : u.getOrgId();
        Long deptId = u == null ? null : u.getDeptId();
        return resolveHit(paramKey, tenantId, orgId, deptId);
    }

    /** 指定作用域解析参数 */
    public String resolve(String paramKey, Long tenantId, Long orgId, Long deptId) {
        return resolveHit(paramKey, tenantId, orgId, deptId).getValue();
    }

    /**
     * 指定作用域解析参数(含来源级别)。
     * tenantId 为 null 时租户/机构/科室三层均不参与; orgId/deptId 仅在 tenantId 给出时参与。
     */
    public ParamHit resolveHit(String paramKey, Long tenantId, Long orgId, Long deptId) {
        if (!StringUtils.hasText(paramKey)) {
            return new ParamHit(null, -1);
        }
        StringBuilder sql = new StringBuilder("SELECT param_value, scope_level FROM sys_param")
                .append(" WHERE param_key = ? AND deleted = 0 AND param_value IS NOT NULL AND TRIM(param_value) <> ''")
                .append(" AND ((scope_level = 0 AND tenant_id = 0)");
        List<Object> args = new ArrayList<>();
        args.add(paramKey);
        if (tenantId != null) {
            sql.append(" OR (scope_level = 1 AND tenant_id = ? AND scope_id = ?)");
            args.add(tenantId);
            args.add(tenantId);
            if (orgId != null) {
                sql.append(" OR (scope_level = 2 AND tenant_id = ? AND scope_id = ?)");
                args.add(tenantId);
                args.add(orgId);
            }
            if (deptId != null) {
                sql.append(" OR (scope_level = 3 AND tenant_id = ? AND scope_id = ?)");
                args.add(tenantId);
                args.add(deptId);
            }
        }
        sql.append(") ORDER BY scope_level DESC LIMIT 1");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            Object v = row.get("param_value");
            String sv = v == null ? null : String.valueOf(v);
            Number lv = (Number) row.get("scope_level");
            // SQL 已过滤空串, 此处再兼容历史脏数据(空白值视为未覆盖, 继续回退默认值)
            if (StringUtils.hasText(sv)) {
                return new ParamHit(sv, lv == null ? 0 : lv.intValue());
            }
        }
        // 无覆盖命中: 回退全局定义行的 default_value 兜底
        List<Map<String, Object>> defs = jdbcTemplate.queryForList(
                "SELECT default_value FROM sys_param"
                        + " WHERE param_key = ? AND scope_level = 0 AND tenant_id = 0 AND deleted = 0 LIMIT 1",
                paramKey);
        if (!defs.isEmpty()) {
            Object dv = defs.get(0).get("default_value");
            return new ParamHit(dv == null ? null : String.valueOf(dv), 0);
        }
        return new ParamHit(null, -1);
    }

    /* ================= 分组批量解析 ================= */

    /** 按当前登录用户上下文解析一组参数 */
    public Map<String, String> resolveGroup(String groupCode) {
        LoginUser u = UserContext.get();
        Long tenantId = u != null && u.getTenantId() != null ? u.getTenantId() : TenantContext.get();
        Long orgId = u == null ? null : u.getOrgId();
        Long deptId = u == null ? null : u.getDeptId();
        return resolveGroup(groupCode, tenantId, orgId, deptId);
    }

    /**
     * 指定作用域批量解析一组参数(共 2 条 SQL):
     * 先取分组下全部全局定义行(决定返回的键集合与 default 兜底), 再一条 SQL 批量取所有命中的
     * 覆盖行(按 scope_level 降序, 每键取最高优先级非空值), 最后按键合并。
     */
    public Map<String, String> resolveGroup(String groupCode, Long tenantId, Long orgId, Long deptId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(groupCode)) {
            return out;
        }
        List<Map<String, Object>> defs = jdbcTemplate.queryForList(
                "SELECT param_key, default_value FROM sys_param"
                        + " WHERE group_code = ? AND scope_level = 0 AND tenant_id = 0 AND deleted = 0"
                        + " ORDER BY param_key",
                groupCode);
        if (defs.isEmpty()) {
            return out;
        }
        Map<String, ParamHit> hits = batchResolve(defs, tenantId, orgId, deptId);
        for (Map<String, Object> def : defs) {
            String key = String.valueOf(def.get("param_key"));
            ParamHit hit = hits.get(key);
            if (hit != null) {
                out.put(key, hit.getValue());
            } else {
                Object dv = def.get("default_value");
                out.put(key, dv == null ? null : String.valueOf(dv));
            }
        }
        return out;
    }

    /** 批量解析命中行: 单条 SQL 查 IN(param_key) 的全部命中行, 每键保留最高 scope_level 值 */
    private Map<String, ParamHit> batchResolve(List<Map<String, Object>> defs, Long tenantId, Long orgId, Long deptId) {
        Map<String, ParamHit> hits = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT param_key, param_value, scope_level FROM sys_param")
                .append(" WHERE deleted = 0 AND param_value IS NOT NULL AND TRIM(param_value) <> '' AND param_key IN (");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            if (i > 0) {
                sql.append(",");
            }
            sql.append("?");
            args.add(defs.get(i).get("param_key"));
        }
        sql.append(") AND ((scope_level = 0 AND tenant_id = 0)");
        if (tenantId != null) {
            sql.append(" OR (scope_level = 1 AND tenant_id = ? AND scope_id = ?)");
            args.add(tenantId);
            args.add(tenantId);
            if (orgId != null) {
                sql.append(" OR (scope_level = 2 AND tenant_id = ? AND scope_id = ?)");
                args.add(tenantId);
                args.add(orgId);
            }
            if (deptId != null) {
                sql.append(" OR (scope_level = 3 AND tenant_id = ? AND scope_id = ?)");
                args.add(tenantId);
                args.add(deptId);
            }
        }
        sql.append(") ORDER BY param_key, scope_level DESC");
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            String key = String.valueOf(row.get("param_key"));
            Object v = row.get("param_value");
            String sv = v == null ? null : String.valueOf(v);
            // 空白值不产生命中(既不占优先级), 由调用方回退 default_value
            if (!StringUtils.hasText(sv)) {
                continue;
            }
            ParamHit exist = hits.get(key);
            if (exist == null) {
                Number lv = (Number) row.get("scope_level");
                hits.put(key, new ParamHit(sv, lv == null ? 0 : lv.intValue()));
            }
        }
        return hits;
    }
}
