package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 调价分域与定时自动生效的启动期幂等迁移(@Order(2), 晚于 DictSchemaMigration(0) 建表与 DemoDataInitializer(1)):
 * 为既有 his_drug_price_adjust 表补充"调价域 + 目标库位/药房 + 自动生效标志"四列, 并种入自动生效全局开关(默认关)。
 * 独立新增文件, 绝不改动咽喉 DictSchemaMigration; 全部幂等: 列已存在则跳过, 参数已存在则不重复插。
 * 默认 price_domain='ALL' 保持改造前"目录+全院批次"无差别刷价的旧语义, 存量草稿行为不变(opt-in 才启用分域)。
 */
@Slf4j
@Order(2)
@Component
public class DrugPriceAdjustDomainMigration implements ApplicationRunner {

    private final DataSource dataSource;

    public DrugPriceAdjustDomainMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 表名, 列名, 列定义 */
    private static final String[][] COLS = {
            {"his_drug_price_adjust", "price_domain", "VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT '调价域: CATALOG目录/WAREHOUSE药库/PHARMACY药房/ALL全部(默认向后兼容)'"},
            {"his_drug_price_adjust", "target_warehouse_id", "BIGINT NULL COMMENT '药库域目标库位(his_warehouse_def.id)'"},
            {"his_drug_price_adjust", "target_pharmacy_id", "BIGINT NULL COMMENT '药房域目标药房(his_pharmacy_def.id)'"},
            {"his_drug_price_adjust", "auto_effect", "TINYINT NOT NULL DEFAULT 0 COMMENT '到生效日是否自动生效: 1=调度器认领生效, 0=仅手动'"},
    };

    public void seed() {
        try (Connection conn = dataSource.getConnection()) {
            int added = 0;
            for (String[] c : COLS) {
                if (!columnExists(conn, c[0], c[1])) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2]);
                        added++;
                    }
                }
            }
            boolean seeded = seedAutoEffectSwitch(conn);
            if (added > 0 || seeded) {
                log.info("调价分域迁移: 新增列={}, 自动生效开关种子={}", added, seeded);
            }
        } catch (Exception e) {
            log.warn("调价分域迁移跳过(不影响主流程): {}", e.getMessage());
        }
    }

    /** 幂等种全局自动生效开关(默认 false=关, opt-in): 已存在(含墓碑复活)则不重复插。 */
    private boolean seedAutoEffectSwitch(Connection conn) throws Exception {
        String key = "warehouse.price_adjust_auto_effect_enabled";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, deleted FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    if (rs.getInt("deleted") != 0) {
                        try (Statement st = conn.createStatement()) {
                            st.executeUpdate("UPDATE sys_param SET deleted = 0, param_value = 'false' WHERE id = " + id);
                            return true;
                        }
                    }
                    return false;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sys_param(tenant_id, param_key, param_value, scope_level, scope_id, group_code, param_name,"
                        + " data_type, default_value, required, allow_scope, remark, create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES(0, ?, 'false', 0, 0, 'warehouse', '调价单到生效日自动生效开关', 'bool', 'false', 0, '0,1,2',"
                        + " '开启后调度器对 auto_effect=1 且已到生效日的草稿单自动执行调价; 默认关闭仅手动生效', 'priceadjust-domain-seed', NOW(), 'priceadjust-domain-seed', NOW(), 0)")) {
            ps.setString(1, key);
            ps.executeUpdate();
            return true;
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** ApplicationRunner 入口(与二期 PharmacyPriceDiffParamSeeder 同构: @Order(2) 启动后种列/参数)。 */
    @Override
    public void run(ApplicationArguments args) {
        seed();
    }
}
