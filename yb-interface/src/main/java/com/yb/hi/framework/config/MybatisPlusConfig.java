package com.yb.hi.framework.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.yb.hi.framework.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MyBatis-Plus 配置: 多租户插件 + 分页插件
 * 租户插件须置于分页插件之前。
 */
@Configuration
public class MybatisPlusConfig {

    /** 全局表(不做租户隔离): 租户注册表 + 行政区划国家标准表 + RBAC 全局/半全局表(菜单真源、角色含全局角色、角色菜单关联)
     *  + 三目录标准字典(全局共享、无 tenant_id 列, 业务查询需以子查询校验医保码是否仍有效) */
    private static final Set<String> IGNORE_TABLES = new HashSet<>(Arrays.asList(
            "sys_tenant",
            "area_code_2021",
            "sys_menu",
            "sys_role",
            "sys_role_menu",
            "std_drug",
            "std_consumable",
            "std_med_service"
    ));

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户插件
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tid = TenantContext.get();
                return new LongValue(tid == null ? 0L : tid);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return tableName != null && IGNORE_TABLES.contains(tableName.toLowerCase());
            }
        }));

        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
