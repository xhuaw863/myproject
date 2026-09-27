package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.platform.entity.StaffDeptGrant;
import com.yb.hi.platform.entity.StaffEmployment;
import com.yb.hi.platform.mapper.StaffDeptGrantMapper;
import com.yb.hi.platform.mapper.StaffEmploymentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工任职与科室授权服务(多科室归员工维度的落地基石):
 * - staff_employment: 任职事实(多点执业/兼科室), 主任职行回写 his_staff 归属机构+科室展示列;
 * - staff_dept_grant: 临调授权(替班/兼权, 带期限), 任职之外的代理授权层;
 * - 建表幂等(@PostConstruct) + 存量回填(@Order(6) ApplicationRunner, 在 RBAC/角色回填之后):
 *   主任职 ← his_staff(org_id, dept_id); 授权 ← sys_user.dept_scope 拆解(原用户级科室授权迁至员工维度);
 * - tenant_id 由多租户插件自动注入/过滤(两表均不入 IGNORE_TABLES), 天然限定同医共体。
 * 原生 SQL 回填走 JdbcTemplate/DataSource, 不受 MP 租户拦截器影响(启动期无 TenantContext)。
 */
@Slf4j
@Service
@Order(6)
public class StaffEmploymentService implements ApplicationRunner {

    private final StaffEmploymentMapper employmentMapper;
    private final StaffDeptGrantMapper grantMapper;
    private final HisStaffMapper staffMapper;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public StaffEmploymentService(StaffEmploymentMapper employmentMapper, StaffDeptGrantMapper grantMapper,
                                  HisStaffMapper staffMapper, DataSource dataSource, JdbcTemplate jdbc) {
        this.employmentMapper = employmentMapper;
        this.grantMapper = grantMapper;
        this.staffMapper = staffMapper;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** 幂等建表: staff_employment + staff_dept_grant */
    @PostConstruct
    public void ensureTable() {
        String ddlEmp = "CREATE TABLE IF NOT EXISTS staff_employment ("
                + " id BIGINT NOT NULL AUTO_INCREMENT,"
                + " staff_id BIGINT NOT NULL,"
                + " org_id BIGINT NOT NULL,"
                + " dept_id BIGINT NOT NULL,"
                + " is_primary TINYINT DEFAULT 0,"
                + " tenant_id BIGINT DEFAULT NULL,"
                + " created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (id),"
                + " UNIQUE KEY uk_staff_org_dept (staff_id, org_id, dept_id),"
                + " KEY idx_se_staff (staff_id),"
                + " KEY idx_se_staff_org (staff_id, org_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工任职(多点执业/兼科室)'";
        String ddlGrant = "CREATE TABLE IF NOT EXISTS staff_dept_grant ("
                + " id BIGINT NOT NULL AUTO_INCREMENT,"
                + " staff_id BIGINT NOT NULL,"
                + " org_id BIGINT NOT NULL,"
                + " dept_id BIGINT NOT NULL,"
                + " valid_from DATE DEFAULT NULL,"
                + " valid_to DATE DEFAULT NULL,"
                + " grant_by VARCHAR(50) DEFAULT NULL,"
                + " remark VARCHAR(200) DEFAULT NULL,"
                + " tenant_id BIGINT DEFAULT NULL,"
                + " created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (id),"
                + " KEY idx_sg_staff_org (staff_id, org_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室临调授权(带期限)'";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(ddlEmp);
            st.execute(ddlGrant);
        } catch (Exception e) {
            log.warn("staff_employment/staff_dept_grant 建表检查失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 存量回填(幂等, 可重复执行):
     * 1) 主任职: 每条 his_staff(org_id, dept_id 均非空) 落一条 is_primary=1 任职;
     * 2) 授权: sys_user.dept_scope 逗号串按 (staff_id, org_id=用户归属机构) 拆解落 staff_dept_grant。
     */
    @Override
    public void run(ApplicationArguments args) {
        // 1) 主任职回填(集合式, 原生 SQL 幂等)
        String empSql = "INSERT IGNORE INTO staff_employment (staff_id, org_id, dept_id, is_primary, tenant_id) "
                + "SELECT s.id, s.org_id, s.dept_id, 1, s.tenant_id FROM his_staff s "
                + "WHERE s.deleted = 0 AND s.org_id IS NOT NULL AND s.dept_id IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM staff_employment e WHERE e.staff_id = s.id AND e.org_id = s.org_id AND e.dept_id = s.dept_id)";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            int n = st.executeUpdate(empSql);
            if (n > 0) {
                log.info("staff_employment 主任职回填: 新增 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("staff_employment 回填失败(下次启动重试): {}", e.getMessage());
        }
        // 2) dept_scope → 临调授权回填(需逐行拆解逗号串)
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT staff_id, org_id, dept_scope FROM sys_user "
                            + "WHERE deleted = 0 AND staff_id IS NOT NULL AND org_id IS NOT NULL "
                            + "AND dept_scope IS NOT NULL AND dept_scope <> ''");
            int g = 0;
            LocalDate today = LocalDate.now();
            for (Map<String, Object> r : rows) {
                Long staffId = toLong(r.get("staff_id"));
                Long orgId = toLong(r.get("org_id"));
                String scope = r.get("dept_scope") == null ? "" : r.get("dept_scope").toString();
                for (String s : scope.split(",")) {
                    String t = s.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    Long deptId;
                    try {
                        deptId = Long.parseLong(t);
                    } catch (NumberFormatException ignore) {
                        continue;
                    }
                    // 与主任职重复的授权行无害(并集), 保留以保证回填后授权集与存量 dept_scope 严格等价
                    Integer exist = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM staff_dept_grant WHERE staff_id = ? AND org_id = ? AND dept_id = ? "
                                    + "AND (valid_from IS NULL OR valid_from <= ?) AND (valid_to IS NULL OR valid_to >= ?)",
                            Integer.class, staffId, orgId, deptId, today, today);
                    if (exist != null && exist > 0) {
                        continue;
                    }
                    jdbc.update("INSERT INTO staff_dept_grant (staff_id, org_id, dept_id, grant_by, remark) "
                            + "VALUES (?, ?, ?, 'dept_scope_migrate', '存量用户科室授权迁移')", staffId, orgId, deptId);
                    g++;
                }
            }
            if (g > 0) {
                log.info("staff_dept_grant 存量授权回填: 新增 {} 条(来自 sys_user.dept_scope)", g);
            }
        } catch (Exception e) {
            log.warn("staff_dept_grant 回填失败(下次启动重试): {}", e.getMessage());
        }
    }

    /** 某员工在某机构的任职科室集合(数据权限的权威来源; 不含临调授权) */
    public Set<Long> employmentDeptIds(Long staffId, Long orgId) {
        Set<Long> set = new LinkedHashSet<>();
        if (staffId == null || orgId == null) {
            return set;
        }
        for (StaffEmployment e : employmentMapper.selectList(new QueryWrapper<StaffEmployment>()
                .eq("staff_id", staffId).eq("org_id", orgId))) {
            if (e.getDeptId() != null) {
                set.add(e.getDeptId());
            }
        }
        return set;
    }

    /** 某员工在某机构于指定日期生效的临调授权科室集合 */
    public Set<Long> grantDeptIds(Long staffId, Long orgId, LocalDate on) {
        Set<Long> set = new LinkedHashSet<>();
        if (staffId == null || orgId == null) {
            return set;
        }
        LocalDate d = on == null ? LocalDate.now() : on;
        for (StaffDeptGrant gt : grantMapper.selectList(new QueryWrapper<StaffDeptGrant>()
                .eq("staff_id", staffId).eq("org_id", orgId)
                .and(w -> w.isNull("valid_from").or().le("valid_from", d))
                .and(w -> w.isNull("valid_to").or().ge("valid_to", d)))) {
            if (gt.getDeptId() != null) {
                set.add(gt.getDeptId());
            }
        }
        return set;
    }

    /** 员工全部任职行(管理页回显) */
    public List<StaffEmployment> listByStaff(Long staffId) {
        if (staffId == null) {
            return java.util.Collections.emptyList();
        }
        return employmentMapper.selectList(new QueryWrapper<StaffEmployment>()
                .eq("staff_id", staffId).orderByDesc("is_primary").orderByAsc("id"));
    }

    /** 主任职科室(第一条 is_primary=1); 无则 null */
    public Long primaryDeptId(Long staffId) {
        if (staffId == null) {
            return null;
        }
        List<StaffEmployment> ps = employmentMapper.selectList(new QueryWrapper<StaffEmployment>()
                .eq("staff_id", staffId).eq("is_primary", 1).orderByAsc("id").last("LIMIT 1"));
        return ps.isEmpty() ? null : ps.get(0).getDeptId();
    }

    /**
     * 覆盖设置员工任职: 每个 staff 强制至多一条主任职(入参首条视为主任职);
     * 保存后按主任职单向回写 his_staff 的 org_id/dept_id 展示列(禁止反向改)。
     */
    @Transactional
    public void setEmployments(Long staffId, List<StaffEmployment> rows) {
        if (staffId == null) {
            return;
        }
        employmentMapper.delete(new QueryWrapper<StaffEmployment>().eq("staff_id", staffId));
        StaffEmployment primary = null;
        Set<String> seen = new LinkedHashSet<>();
        if (rows != null) {
            boolean firstValid = true;
            for (StaffEmployment r : rows) {
                if (r.getOrgId() == null || r.getDeptId() == null) {
                    continue;
                }
                String key = r.getOrgId() + ":" + r.getDeptId();
                if (!seen.add(key)) {
                    continue; // 去重(staff,org,dept)
                }
                StaffEmployment e = new StaffEmployment();
                e.setStaffId(staffId);
                e.setOrgId(r.getOrgId());
                e.setDeptId(r.getDeptId());
                e.setIsPrimary(firstValid ? 1 : 0); // 首条有效行视为主任职
                if (firstValid) {
                    firstValid = false;
                    primary = e;
                }
                employmentMapper.insert(e);
            }
        }
        if (primary != null) {
            HisStaff s = staffMapper.selectById(staffId);
            if (s != null) {
                s.setOrgId(primary.getOrgId());
                s.setDeptId(primary.getDeptId());
                staffMapper.updateById(s);
            }
        }
    }

    /** 员工全部临调授权行(管理页回显) */
    public List<StaffDeptGrant> listGrants(Long staffId) {
        if (staffId == null) {
            return java.util.Collections.emptyList();
        }
        return grantMapper.selectList(new QueryWrapper<StaffDeptGrant>()
                .eq("staff_id", staffId).orderByAsc("org_id").orderByAsc("id"));
    }

    /** 新增临调授权(同 staff/org/dept 已存在则覆盖期限与备注) */
    public void addGrant(StaffDeptGrant g) {
        if (g == null || g.getStaffId() == null || g.getOrgId() == null || g.getDeptId() == null) {
            throw new com.yb.hi.framework.common.BizException(400, "授权参数不完整(职工/机构/科室均必填)");
        }
        if (g.getValidFrom() != null && g.getValidTo() != null && g.getValidFrom().isAfter(g.getValidTo())) {
            throw new com.yb.hi.framework.common.BizException(400, "生效日不能晚于失效日");
        }
        List<StaffDeptGrant> exist = grantMapper.selectList(new QueryWrapper<StaffDeptGrant>()
                .eq("staff_id", g.getStaffId()).eq("org_id", g.getOrgId()).eq("dept_id", g.getDeptId())
                .orderByAsc("id").last("LIMIT 1"));
        if (!exist.isEmpty()) {
            StaffDeptGrant e = exist.get(0);
            e.setValidFrom(g.getValidFrom());
            e.setValidTo(g.getValidTo());
            e.setRemark(g.getRemark());
            grantMapper.updateById(e);
            return;
        }
        if (!StringUtils.hasText(g.getGrantBy())) {
            g.setGrantBy("manual");
        }
        grantMapper.insert(g);
    }

    /** 删除临调授权行 */
    public void removeGrant(Long id) {
        if (id != null) {
            grantMapper.deleteById(id);
        }
    }

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
