package com.yb.hi.service.inpatient;

import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临床路径统计服务:
 * - totalInstances/completedCount/exitedCount: 实例总量与完成/退出数;
 * - avgStayDays: 已完成实例的平均在径天数 AVG(DATEDIFF(end_date, start_date));
 * - varianceRate: 变异率 = 变异(exec_status=4)记录数 / 总执行记录数。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class PathwayStatsService {

    private final JdbcTemplate jdbcTemplate;

    public PathwayStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 路径统计(orgId 为 null 时统计当前租户全部机构) */
    public Map<String, Object> getStats(Long orgId) {
        long tenant = tenantId();
        StringBuilder where = new StringBuilder(" WHERE deleted = 0 AND tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (orgId != null) {
            where.append(" AND org_id = ?");
            args.add(orgId);
        }
        String instWhere = where.toString();
        String execWhere = where.toString();

        long totalInstances = countOrZero("SELECT COUNT(*) FROM his_pathway_instance" + instWhere, args);
        long completedCount = countOrZero("SELECT COUNT(*) FROM his_pathway_instance" + instWhere + " AND status = 2", args);
        long exitedCount = countOrZero("SELECT COUNT(*) FROM his_pathway_instance" + instWhere + " AND status = 3", args);
        // 平均在径天数(仅已完成实例)
        Double avgDays = jdbcTemplate.queryForObject(
                "SELECT AVG(DATEDIFF(end_date, start_date)) FROM his_pathway_instance"
                        + instWhere + " AND status = 2 AND end_date IS NOT NULL AND start_date IS NOT NULL",
                Double.class, args.toArray());
        // 变异率(全体执行记录口径)
        long totalExec = countOrZero("SELECT COUNT(*) FROM his_pathway_exec" + execWhere, args);
        long varianceCount = countOrZero("SELECT COUNT(*) FROM his_pathway_exec" + execWhere + " AND exec_status = 4", args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgId", orgId);
        out.put("totalInstances", totalInstances);
        out.put("completedCount", completedCount);
        out.put("exitedCount", exitedCount);
        out.put("activeCount", totalInstances - completedCount - exitedCount);
        out.put("avgStayDays", avgDays == null ? 0.0 : Math.round(avgDays * 10.0) / 10.0);
        out.put("totalExec", totalExec);
        out.put("varianceCount", varianceCount);
        out.put("varianceRate", totalExec == 0 ? 0.0 : Math.round(varianceCount * 10000.0 / totalExec) / 10000.0);
        return out;
    }

    /** 计数查询(异常时归零, 保证统计接口可用性) */
    private long countOrZero(String sql, List<Object> args) {
        Long cnt = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return cnt == null ? 0L : cnt;
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
