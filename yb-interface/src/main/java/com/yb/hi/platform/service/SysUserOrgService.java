package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysUserOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.mapper.SysUserOrgMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户可登录机构服务(多点执业):
 * - 归属机构(sys_user.org_id)为默认可登录机构, 始终允许;
 * - 额外授权机构存于 sys_user_org, 仅限本医共体(同租户, 由多租户插件保证);
 * - 解析/校验供登录、切换机构、用户维护共用。
 * 调用方须已设置 TenantContext(登录流程内已设置; 已鉴权请求由拦截器设置)。
 */
@Slf4j
@Service
public class SysUserOrgService {

    private final SysUserOrgMapper userOrgMapper;
    private final SysOrgMapper orgMapper;
    private final DataSource dataSource;

    public SysUserOrgService(SysUserOrgMapper userOrgMapper, SysOrgMapper orgMapper, DataSource dataSource) {
        this.userOrgMapper = userOrgMapper;
        this.orgMapper = orgMapper;
        this.dataSource = dataSource;
    }

    /** 幂等建表: sys_user_org(启动即确保存在, 免手工执行 SQL) */
    @PostConstruct
    public void ensureTable() {
        String ddl = "CREATE TABLE IF NOT EXISTS sys_user_org ("
                + " id BIGINT NOT NULL AUTO_INCREMENT,"
                + " user_id BIGINT NOT NULL,"
                + " org_id BIGINT NOT NULL,"
                + " tenant_id BIGINT DEFAULT NULL,"
                + " created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (id),"
                + " UNIQUE KEY uk_user_org (user_id, org_id),"
                + " KEY idx_uo_user (user_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户可登录机构(多点执业)'";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(ddl);
        } catch (Exception e) {
            log.warn("sys_user_org 建表检查失败(忽略): {}", e.getMessage());
        }
    }

    /** 用户显式授权的可登录机构ID(可能不含归属机构; 归属机构由调用方并入) */
    public List<Long> listOrgIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        if (userId == null) {
            return ids;
        }
        for (SysUserOrg uo : userOrgMapper.selectList(new QueryWrapper<SysUserOrg>().eq("user_id", userId))) {
            if (uo.getOrgId() != null) {
                ids.add(uo.getOrgId());
            }
        }
        return ids;
    }

    /** 批量回显: userId -> 显式授权机构ID(不含归属机构, 调用方按 allowedOrgIds 同口径并入), 列表场景消除逐行 N+1 */
    public Map<Long, List<Long>> mapGrantedOrgIds() {
        Map<Long, List<Long>> m = new HashMap<>();
        for (SysUserOrg uo : userOrgMapper.selectList(null)) {
            if (uo.getUserId() != null && uo.getOrgId() != null) {
                m.computeIfAbsent(uo.getUserId(), k -> new ArrayList<>()).add(uo.getOrgId());
            }
        }
        return m;
    }

    /** 批量回显(限定 userId 集): 分页场景仅取当前页用户的授权机构, 避免加载整表 */
    public Map<Long, List<Long>> mapGrantedOrgIds(Collection<Long> userIds) {
        Map<Long, List<Long>> m = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return m;
        }
        for (SysUserOrg uo : userOrgMapper.selectList(new QueryWrapper<SysUserOrg>().in("user_id", userIds))) {
            if (uo.getUserId() != null && uo.getOrgId() != null) {
                m.computeIfAbsent(uo.getUserId(), k -> new ArrayList<>()).add(uo.getOrgId());
            }
        }
        return m;
    }

    /**
     * 可登录机构ID(含归属机构默认, 去重): 供用户管理列表回显与前端选择。
     */
    public List<Long> allowedOrgIds(Long userId, Long homeOrgId) {
        Set<Long> set = new LinkedHashSet<>();
        if (homeOrgId != null) {
            set.add(homeOrgId);
        }
        set.addAll(listOrgIds(userId));
        return new ArrayList<>(set);
    }

    /**
     * 解析可登录机构实体列表(含归属机构默认), 仅返回本租户内真实存在的机构,
     * 排序: 层级升序(牵头在前) → id 升序。
     */
    public List<SysOrg> resolveAllowedOrgs(Long userId, Long homeOrgId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (homeOrgId != null) {
            ids.add(homeOrgId);
        }
        ids.addAll(listOrgIds(userId));
        List<SysOrg> orgs = new ArrayList<>();
        if (ids.isEmpty()) {
            return orgs;
        }
        orgs = orgMapper.selectList(new QueryWrapper<SysOrg>().in("id", ids));
        orgs.sort(Comparator
                .comparing((SysOrg o) -> o.getOrgLevel() == null ? 9 : o.getOrgLevel())
                .thenComparing(SysOrg::getId));
        return orgs;
    }

    /** 校验某机构是否在用户可登录范围内(含归属机构默认) */
    public boolean isAllowed(Long userId, Long homeOrgId, Long orgId) {
        if (orgId == null) {
            return false;
        }
        if (orgId.equals(homeOrgId)) {
            return true;
        }
        return listOrgIds(userId).contains(orgId);
    }

    /**
     * 覆盖设置用户可登录机构: 归属机构强制保留; 入参中跨租户/不存在的机构自动忽略。
     * orgIds 为 null 表示不改动(仅用于兼容未传该字段的调用方由上层判断)。
     */
    @Transactional
    public void setLoginOrgs(Long userId, Long homeOrgId, Collection<Long> orgIds) {
        if (userId == null) {
            return;
        }
        Set<Long> want = new LinkedHashSet<>();
        if (homeOrgId != null) {
            want.add(homeOrgId);
        }
        if (orgIds != null) {
            for (Long o : orgIds) {
                if (o != null) {
                    want.add(o);
                }
            }
        }
        // 仅保留本租户内真实存在的机构(防越权/脏数据)
        Set<Long> valid = new LinkedHashSet<>();
        if (!want.isEmpty()) {
            for (SysOrg o : orgMapper.selectList(new QueryWrapper<SysOrg>().in("id", want))) {
                valid.add(o.getId());
            }
        }
        userOrgMapper.delete(new QueryWrapper<SysUserOrg>().eq("user_id", userId));
        for (Long orgId : valid) {
            SysUserOrg uo = new SysUserOrg();
            uo.setUserId(userId);
            uo.setOrgId(orgId);
            userOrgMapper.insert(uo);
        }
    }
}
