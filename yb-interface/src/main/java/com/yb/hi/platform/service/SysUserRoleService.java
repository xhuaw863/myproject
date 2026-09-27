package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.platform.entity.SysUserRole;
import com.yb.hi.platform.mapper.SysUserRoleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户-角色关联服务(医共体一人多角色):
 * - 权限 = 全部角色并集; sys_user.role/role_id 降级为"主角色"(显示与无关联行时兜底);
 * - tenant_id 由多租户插件自动注入/过滤(同 sys_user_org 套路, 不入 IGNORE_TABLES);
 * - 启动幂等回填(@Order(5), 在 RBAC/演示数据初始化之后): 每个存量用户按主角色落一条关联。
 */
@Slf4j
@Service
@Order(5)
public class SysUserRoleService implements ApplicationRunner {

    private final SysUserRoleMapper userRoleMapper;
    private final DataSource dataSource;

    public SysUserRoleService(SysUserRoleMapper userRoleMapper, DataSource dataSource) {
        this.userRoleMapper = userRoleMapper;
        this.dataSource = dataSource;
    }

    /** 幂等建表: sys_user_org 同款 DDL, 免手工执行 SQL */
    @PostConstruct
    public void ensureTable() {
        String ddl = "CREATE TABLE IF NOT EXISTS sys_user_role ("
                + " id BIGINT NOT NULL AUTO_INCREMENT,"
                + " user_id BIGINT NOT NULL,"
                + " role_id BIGINT NOT NULL,"
                + " tenant_id BIGINT DEFAULT NULL,"
                + " created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (id),"
                + " UNIQUE KEY uk_user_role (user_id, role_id),"
                + " KEY idx_ur_user (user_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联(一人多角色)'";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(ddl);
        } catch (Exception e) {
            log.warn("sys_user_role 建表检查失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 存量回填: 无任何关联行的用户, 按 role_id(优先)或 role 编码映射的全局角色补一条关联,
     * 保证单角色时代账号行为零变化。原生 SQL 幂等(NOT EXISTS + INSERT IGNORE), 跨租户一次完成。
     */
    @Override
    public void run(ApplicationArguments args) {
        String[] sqls = {
                "INSERT IGNORE INTO sys_user_role (user_id, role_id, tenant_id) "
                        + "SELECT u.id, u.role_id, u.tenant_id FROM sys_user u "
                        + "WHERE u.deleted = 0 AND u.role_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_user_role x WHERE x.user_id = u.id)",
                "INSERT IGNORE INTO sys_user_role (user_id, role_id, tenant_id) "
                        + "SELECT u.id, r.id, u.tenant_id FROM sys_user u "
                        + "JOIN sys_role r ON r.role_code = u.role AND r.deleted = 0 "
                        + "AND (r.tenant_id IS NULL OR r.tenant_id = u.tenant_id) "
                        + "WHERE u.deleted = 0 AND (u.role_id IS NULL OR u.role_id = 0) "
                        + "AND u.role IS NOT NULL AND u.role <> '' "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_user_role x WHERE x.user_id = u.id)"
        };
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            int total = 0;
            for (String sql : sqls) {
                total += st.executeUpdate(sql);
            }
            if (total > 0) {
                log.info("sys_user_role 存量回填: 新增 {} 条用户-角色关联", total);
            }
        } catch (Exception e) {
            log.warn("sys_user_role 回填失败(下次启动重试): {}", e.getMessage());
        }
    }

    /** 用户全部角色ID(关联表口径; 空列表=未配置多角色, 调用方回落主角色) */
    public List<Long> listRoleIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        if (userId == null) {
            return ids;
        }
        for (SysUserRole ur : userRoleMapper.selectList(new QueryWrapper<SysUserRole>().eq("user_id", userId))) {
            if (ur.getRoleId() != null) {
                ids.add(ur.getRoleId());
            }
        }
        return ids;
    }

    /**
     * 覆盖设置用户角色集合: 主角色强制包含并置于首位(显示口径);
     * 跨租户/不存在的 roleId 由调用方(Controller 经 roleMapper 复核)过滤后传入。
     */
    @Transactional
    public void replaceRoles(Long userId, Long primaryRoleId, Collection<Long> roleIds) {
        if (userId == null) {
            return;
        }
        Set<Long> want = new LinkedHashSet<>();
        if (primaryRoleId != null) {
            want.add(primaryRoleId);
        }
        if (roleIds != null) {
            for (Long r : roleIds) {
                if (r != null) {
                    want.add(r);
                }
            }
        }
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        for (Long roleId : want) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }
}
