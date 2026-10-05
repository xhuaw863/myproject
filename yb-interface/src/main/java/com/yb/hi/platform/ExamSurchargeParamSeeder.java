package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 门诊/住院检查"多部位加收"与"增强扫描加收"四个独立控费开关参数定义种子(全局行 tenant_id=0, 幂等)。
 *
 * 背景: 检查开立时服务端按 his_charge_addon_rule 自动追加"多部位加收(part)/增强扫描加收(contrast)"行
 * (依据国家医保局放射/超声检查类立项指南与湖北物价项目内涵)。控费口径是否执行因机构/场景而异, 故:
 *  - 按维度拆分: 多部位(part) 与 增强(contrast) 各一个独立开关, 互不影响;
 *  - 按场景拆分: 门诊(outpatient) 与 住院(inpatient) 各一套两个开关。
 * 本轮住院两参数先建好可读, 住院加收引擎接线另行落地; 门诊两开关即时生效。
 * 全部默认 false(停用): 关闭时检查单仅收主项目不自动加收, 需启用的机构在系统参数页逐级覆盖 true。
 *
 * 单独成类而非并入 DemoDataInitializer: 后者为多会话并发高频改动文件, 独立新增文件零冲突。
 * sys_param 表由 DictSchemaMigration(@Order(0)) 建立, outpatient 分组由 DemoDataInitializer(@Order(1)) 建立;
 * 本类 @Order(2) 在其后运行, 复用 outpatient 分组并自建 inpatient 分组。
 * 退役: 早期合并开关 outpatient.exam_surcharge_enabled 逻辑删除(定义行+各作用域覆盖行), 由两个新维度键替代。
 */
@Slf4j
@Order(2)
@Component
public class ExamSurchargeParamSeeder implements ApplicationRunner {

    /** 门诊多部位(part)加收开关 */
    public static final String KEY_OP_PART = "outpatient.exam_part_surcharge_enabled";
    /** 门诊增强(contrast)加收开关 */
    public static final String KEY_OP_CONTRAST = "outpatient.exam_contrast_surcharge_enabled";
    /** 住院多部位(part)加收开关 */
    public static final String KEY_IP_PART = "inpatient.exam_part_surcharge_enabled";
    /** 住院增强(contrast)加收开关 */
    public static final String KEY_IP_CONTRAST = "inpatient.exam_contrast_surcharge_enabled";
    /** 早期合并总开关(已由上面两个维度键替代, 本类负责退役) */
    private static final String LEGACY_KEY = "outpatient.exam_surcharge_enabled";

    private final JdbcTemplate jdbcTemplate;

    public ExamSurchargeParamSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureInpatientGroup();
            retireLegacyKey();
            seed();
        } catch (Exception e) {
            // 表/分组未建等场景静默跳过, 下次启动补种(与其余 Seeder 容错口径一致)
            log.warn("检查多部位/增强控费开关参数种子跳过: {}", e.getMessage());
        }
    }

    /** 自建住院业务参数分组(幂等; sys_param_group 为全局共享表, 无 tenant_id 列)。 */
    private void ensureInpatientGroup() {
        List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                "SELECT id, deleted FROM sys_param_group WHERE group_code = 'inpatient' LIMIT 1");
        if (!hit.isEmpty()) {
            Number deleted = (Number) hit.get(0).get("deleted");
            if (deleted != null && deleted.intValue() == 1) {
                jdbcTemplate.update("UPDATE sys_param_group SET group_name = ?, sort_no = ?, deleted = 0,"
                        + " update_by = 'exam-surcharge-seed', update_time = NOW() WHERE id = ?",
                        "住院业务", 11, hit.get(0).get("id"));
            }
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO sys_param_group (group_code, group_name, sort_no, remark,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES ('inpatient', '住院业务', 11, '住院医嘱与计费相关系统参数', 'exam-surcharge-seed', NOW(), 'exam-surcharge-seed', NOW(), 0)");
        log.info("检查控费开关参数种子: 新建住院业务参数分组(inpatient)");
    }

    /** 退役早期合并总开关: 逻辑删除其全局定义行与所有作用域覆盖行(uk_param_scope 含墓碑占键, 故用 UPDATE 而非物理删). */
    private void retireLegacyKey() {
        int n = jdbcTemplate.update(
                "UPDATE sys_param SET deleted = 1, update_by = 'exam-surcharge-seed', update_time = NOW()"
                        + " WHERE param_key = ? AND deleted = 0", LEGACY_KEY);
        if (n > 0) {
            log.info("检查控费开关参数种子: 退役合并开关 {} (影响 {} 行), 由 part/contrast 双维键替代", LEGACY_KEY, n);
        }
    }

    private void seed() {
        // {分组, 参数键, 名称, 数据类型, 默认值, 允许作用域, 备注}
        Object[][] params = {
                {"outpatient", KEY_OP_PART, "门诊检查多部位加收开关", "bool", "false", "0,1,2",
                        "启用后门诊检查开立按 his_charge_addon_rule(part维度) 自动追加多部位加收行(放射/超声每增一部位按主项目比例); 关闭则仅收主项目"},
                {"outpatient", KEY_OP_CONTRAST, "门诊检查增强扫描加收开关", "bool", "false", "0,1,2",
                        "启用后门诊检查选增强(平扫项目同时做增强)按 contrast 维度自动追加增强扫描加收行; 关闭则不加收"},
                {"inpatient", KEY_IP_PART, "住院检查多部位加收开关", "bool", "false", "0,1,2",
                        "启用后住院检查医嘱按 part 维度自动追加多部位加收(住院加收引擎接线落地后生效); 关闭则仅收主项目"},
                {"inpatient", KEY_IP_CONTRAST, "住院检查增强扫描加收开关", "bool", "false", "0,1,2",
                        "启用后住院检查选增强按 contrast 维度自动追加增强扫描加收(住院加收引擎接线落地后生效); 关闭则不加收"},
        };
        int added = 0;
        for (Object[] p : params) {
            String key = (String) p[1];
            String group = (String) p[0];
            String name = (String) p[2];
            String defVal = (String) p[4];
            String allowScope = (String) p[5];
            String remark = (String) p[6];
            List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                    "SELECT id, deleted FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 LIMIT 1", key);
            if (!hit.isEmpty()) {
                Number deleted = (Number) hit.get(0).get("deleted");
                if (deleted != null && deleted.intValue() == 1) {
                    jdbcTemplate.update(
                            "UPDATE sys_param SET tenant_id = 0, param_value = ?, scope_id = 0, group_code = ?,"
                                    + " param_name = ?, data_type = 'bool', default_value = ?, enum_options = NULL,"
                                    + " min_value = NULL, max_value = NULL, required = 0, allow_scope = ?, remark = ?,"
                                    + " deleted = 0, update_by = 'exam-surcharge-seed', update_time = NOW() WHERE id = ?",
                            defVal, group, name, defVal, allowScope, remark, hit.get(0).get("id"));
                }
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO sys_param (tenant_id, param_key, param_value, scope_level, scope_id, group_code,"
                            + " param_name, data_type, default_value, enum_options, min_value, max_value, required,"
                            + " allow_scope, remark, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (0, ?, ?, 0, 0, ?, ?, 'bool', ?, NULL, NULL, NULL, 0, ?, ?, 'exam-surcharge-seed', NOW(), 'exam-surcharge-seed', NOW(), 0)",
                    key, defVal, group, name, defVal, allowScope, remark);
            added++;
        }
        if (added > 0) {
            log.info("检查多部位/增强控费开关参数种子: 新增定义 {} 条(门诊2+住院2, 默认停用)", added);
        }
    }
}
