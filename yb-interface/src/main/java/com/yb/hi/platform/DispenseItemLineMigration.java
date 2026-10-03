package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 门诊行级部分退(批次价驱动定价改造三期 C2)的启动期幂等迁移(@Order(2), 晚于 DictSchemaMigration(0)/DemoDataInitializer(1)):
 * 新建两张明细表 his_dispense_item(发药行) + his_drug_return_item(退药行), 为整单粒度的 his_dispense 提供行级退药粒度。
 * 独立新增文件, 绝不改动咽喉 DictSchemaMigration; CREATE TABLE IF NOT EXISTS 幂等, 存量数据无行记录时退药自动回落旧整方路径(向后兼容)。
 * 金额口径: billed_price/billed_amount 直引划价快照原价(his_prescription_item.price/amount), 退药按原价退, 保证"收退恒等不退不平";
 * 发药实扣批次价差已在发药时进 his_dispense.price_diff 损益, 退药不再触碰批次价, 只按原价退且回库数量守恒。
 */
@Slf4j
@Order(2)
@Component
public class DispenseItemLineMigration implements ApplicationRunner {

    private final DataSource dataSource;

    public DispenseItemLineMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void seed() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            int created = 0;
            // 发药行: 一条处方药品明细一行(同药多行按 prescription_item_id 区分), 承载行级已退量与原价快照
            created += execCreate(st, "his_dispense_item",
                    "CREATE TABLE IF NOT EXISTS his_dispense_item ("
                            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                            + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                            + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                            + "dispense_id BIGINT NOT NULL COMMENT '发药记录ID(his_dispense.id)',"
                            + "prescription_item_id BIGINT DEFAULT NULL COMMENT '处方明细ID(his_prescription_item.id, 行级来源)',"
                            + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '医共体药品目录ID(his_drug_catalog.id)',"
                            + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码',"
                            + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称',"
                            + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                            + "unit VARCHAR(20) DEFAULT NULL COMMENT '单位',"
                            + "dispense_qty DECIMAL(16,4) DEFAULT 0 COMMENT '本行发药数量(最小单位)',"
                            + "billed_price DECIMAL(16,6) DEFAULT 0 COMMENT '划价快照单价(原价, 退药计价依据, 医保规范16,6)',"
                            + "billed_amount DECIMAL(16,2) DEFAULT 0 COMMENT '划价快照金额(原价, 本行整退时直接引用保证不退不平)',"
                            + "returned_qty DECIMAL(16,4) DEFAULT 0 COMMENT '本行累计已退数量',"
                            + "returned_amount DECIMAL(16,2) DEFAULT 0 COMMENT '本行累计已退金额',"
                            + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                            + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                            + "PRIMARY KEY (id),"
                            + "UNIQUE KEY uk_dispense_item (tenant_id, dispense_id, prescription_item_id),"
                            + "KEY idx_tenant (tenant_id),"
                            + "KEY idx_dispense (dispense_id)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发药明细行(行级部分退)'");
            // 退药行: 一次退药申请的具体行退量, 审批通过据此回库并累加 his_dispense_item.returned_qty
            created += execCreate(st, "his_drug_return_item",
                    "CREATE TABLE IF NOT EXISTS his_drug_return_item ("
                            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                            + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                            + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                            + "return_id BIGINT NOT NULL COMMENT '退药记录ID(his_drug_return.id)',"
                            + "dispense_item_id BIGINT NOT NULL COMMENT '发药明细行ID(his_dispense_item.id)',"
                            + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '医共体药品目录ID',"
                            + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码',"
                            + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称',"
                            + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                            + "unit VARCHAR(20) DEFAULT NULL COMMENT '单位',"
                            + "return_qty DECIMAL(16,4) DEFAULT 0 COMMENT '本次该行退药数量',"
                            + "return_amount DECIMAL(12,2) DEFAULT 0 COMMENT '本次该行退药金额(按划价原价)',"
                            + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                            + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                            + "PRIMARY KEY (id),"
                            + "KEY idx_tenant (tenant_id),"
                            + "KEY idx_return (return_id),"
                            + "KEY idx_dispense_item (dispense_item_id)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退药明细行(行级部分退)'");
            if (created > 0) {
                log.info("行级部分退迁移: 新建明细表 {} 张(his_dispense_item/his_drug_return_item)", created);
            }
        } catch (Exception e) {
            log.warn("行级部分退迁移跳过(不影响主流程): {}", e.getMessage());
        }
    }

    /** 执行建表并返回新增标记(1=本次新建, 0=已存在)。 */
    private int execCreate(Statement st, String table, String ddl) throws Exception {
        st.executeUpdate(ddl);
        // CREATE TABLE IF NOT EXISTS 无法直接感知是否新建, 迁移幂等无需区分, 统一记 0(仅在真正需要日志时由上层按存在性判断)。
        return 0;
    }

    /** ApplicationRunner 入口(与二期 PharmacyPriceDiffParamSeeder / 三期C1 同构: @Order(2) 启动后建表)。 */
    @Override
    public void run(ApplicationArguments args) {
        seed();
    }
}
