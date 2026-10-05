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
     *  + 三目录标准字典(全局共享、无 tenant_id 列, 业务查询需以子查询校验医保码是否仍有效)
     *  + 医保目录下载/版本表(1301-1307 下载链路由全医共体共享、无 tenant_id 列, 由 DictDownloadService 经 MP 访问,
     *    此前未列入导致租户拦截器注入 tenant_id 使下载运行时即崩)
     *  + 系统参数(sys_param 含全局行 tenant_id=0 需跨租户可见且实体显式映射 tenant_id, 隔离由 Service 层手动处理; sys_param_group 为全局共享分组)。
     *  注意: 入此集合后 MP 不再注入 tenant_id 条件, 涉及租户隔离的查询必须在 Service/Wrapper 中显式处理。 */
    private static final Set<String> IGNORE_TABLES = new HashSet<>(Arrays.asList(
            "sys_tenant",
            "area_code_2021",
            "sys_menu",
            "sys_role",
            "sys_role_menu",
            "std_drug",
            "std_consumable",
            "std_med_service",
            // 医保药品目录其余四类(中药饮片/中药配方颗粒/医疗机构制剂/体外诊断试剂): 全局共享、无 tenant_id 列,
            // 对照有效性校验(validExistsSql)子查询经 MP 包装器执行, 不入此集合会被注入 tenant_id 使查询报错
            "std_tcm",
            "std_tcm_granule",
            "std_preparation",
            "std_ivd",
            "sys_param",
            "sys_param_group",
            // 1301-1307 目录下载全局共享表(dict_schema.sql 无 tenant_id 列, 下载全链路跨租户共享)
            "dict_version",
            "drug_catalog",
            "tcm_catalog",
            "preparation_catalog",
            "med_service_catalog",
            "consumable_catalog",
            "disease_catalog"
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

        // 分页插件: maxLimit 钉顶, 防前端/调用方传超大 size 退化为全量拉取(导出防护 e3)
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(20000L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
