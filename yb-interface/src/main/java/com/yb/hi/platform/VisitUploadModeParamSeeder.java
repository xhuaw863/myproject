package com.yb.hi.platform;

import com.yb.hi.service.doctor.HisVisitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 医保上报方式参数定义种子(yb.visit.upload.mode, 全局行 tenant_id=0, 幂等)。
 *
 * 单独成类而非并入 DemoDataInitializer: 后者为多会话并发高频改动文件, 独立新增文件零冲突;
 * sys_param 表由 DictSchemaMigration(@Order(0)) 建立, 本类 @Order(2) 自建 insurance 分组。
 *
 * 语义: realtime=完成接诊即时调 2203(与历史行为一致, 零变化);
 * deferred=不即时调医保接口, 落 his_upload_status 待传(0)队列, 由 UploadStatusSweeper
 * 定时批量补传(收费入口守卫 ensureVisitUploaded 仍保证结算前 2203 必达)。
 * 机制通用: 后续 2401/2402 等上报类接口按同一排队器加 key 即可推广。
 */
@Slf4j
@Order(2)
@Component
public class VisitUploadModeParamSeeder implements ApplicationRunner {

    /** 医保对接参数分组编码 */
    public static final String GROUP_CODE = "insurance";

    private final JdbcTemplate jdbcTemplate;

    public VisitUploadModeParamSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            // 表未建等场景静默跳过, 下次启动补种(与 PharmacyPriceDiffParamSeeder 容错口径一致)
            log.warn("医保上报方式参数种子跳过: {}", e.getMessage());
        }
    }

    private void seed() {
        ensureGroup();
        String key = HisVisitService.P_INSURANCE_VISIT_UPLOAD_MODE;
        // {分组, 参数键, 名称, 数据类型, 默认值, 枚举选项, 必填, 允许作用域, 备注}
        Object[] p = {GROUP_CODE, key, "医保就诊信息(2203)上传方式", "enum", "realtime",
                "realtime,deferred", 0, "0,1,2",
                "realtime=完成接诊即时上传2203(默认, 与历史行为一致); deferred=不即时调用医保接口,"
                        + " 落待传队列由定时任务批量补传(收费前守卫仍强制补传兜底); 机构可按需覆盖"};
        List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                "SELECT id, deleted FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 LIMIT 1", key);
        if (!hit.isEmpty()) {
            Number deleted = (Number) hit.get(0).get("deleted");
            if (deleted != null && deleted.intValue() == 1) {
                jdbcTemplate.update(
                        "UPDATE sys_param SET tenant_id = 0, param_value = ?, scope_id = 0, group_code = ?,"
                                + " param_name = ?, data_type = ?, default_value = ?, enum_options = ?,"
                                + " required = ?, allow_scope = ?, remark = ?, deleted = 0,"
                                + " update_by = 'visit-upload-mode-seed', update_time = NOW() WHERE id = ?",
                        p[4], p[0], p[2], p[3], p[4], p[5], p[6], p[7], p[8], hit.get(0).get("id"));
                log.info("医保上报方式参数种子: 复活已删除定义 {}", key);
            }
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO sys_param (tenant_id, param_key, param_value, scope_level, scope_id, group_code,"
                        + " param_name, data_type, default_value, enum_options, min_value, max_value, required,"
                        + " allow_scope, remark, create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (0, ?, ?, 0, 0, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, 'visit-upload-mode-seed', NOW(),"
                        + " 'visit-upload-mode-seed', NOW(), 0)",
                key, p[4], p[0], p[2], p[3], p[4], p[5], p[6], p[7], p[8]);
        log.info("医保上报方式参数种子: 新增定义 {}(默认 realtime, 机构可覆盖 deferred)", key);
    }

    /** 自建医保对接参数分组(幂等; sys_param_group 为全局共享表, 无 tenant_id 列)。 */
    private void ensureGroup() {
        List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                "SELECT id, deleted FROM sys_param_group WHERE group_code = ? LIMIT 1", GROUP_CODE);
        if (!hit.isEmpty()) {
            Number deleted = (Number) hit.get(0).get("deleted");
            if (deleted != null && deleted.intValue() == 1) {
                jdbcTemplate.update("UPDATE sys_param_group SET deleted = 0 WHERE id = ?", hit.get(0).get("id"));
            }
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO sys_param_group (group_code, group_name, sort_no, remark,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, '医保对接', 10, '医保平台接口上报方式与通道参数', 'visit-upload-mode-seed',"
                        + " NOW(), 'visit-upload-mode-seed', NOW(), 0)", GROUP_CODE);
    }
}
