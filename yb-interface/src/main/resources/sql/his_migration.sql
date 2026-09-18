-- ============================================================
-- 医保原生多租户HIS - 现有表多租户化迁移脚本(一次性执行)
-- 为已建成的医保字典表与结算表增加 tenant_id 字段
-- ============================================================
USE yb_interface;

-- 字典版本表: 增加 tenant_id, 唯一键改为 (tenant_id, dict_type)
ALTER TABLE dict_version ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;
ALTER TABLE dict_version DROP INDEX uk_dict_type;
ALTER TABLE dict_version ADD UNIQUE KEY uk_tenant_dict (tenant_id, dict_type);

-- 6张医保字典表: 增加 tenant_id
ALTER TABLE drug_catalog         ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;
ALTER TABLE tcm_catalog          ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;
ALTER TABLE preparation_catalog  ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;
ALTER TABLE med_service_catalog  ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;
ALTER TABLE consumable_catalog   ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;
ALTER TABLE disease_catalog      ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;

-- 结算记录表: 增加 tenant_id
ALTER TABLE setl_record ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER id;

-- 为字典表补充租户索引
ALTER TABLE drug_catalog        ADD KEY idx_tenant (tenant_id);
ALTER TABLE tcm_catalog         ADD KEY idx_tenant (tenant_id);
ALTER TABLE preparation_catalog ADD KEY idx_tenant (tenant_id);
ALTER TABLE med_service_catalog ADD KEY idx_tenant (tenant_id);
ALTER TABLE consumable_catalog  ADD KEY idx_tenant (tenant_id);
ALTER TABLE disease_catalog     ADD KEY idx_tenant (tenant_id);
ALTER TABLE setl_record         ADD KEY idx_tenant (tenant_id);

-- 说明: 演示租户(tenant_id=1/2)与管理员账号由应用启动时的 DemoDataInitializer 初始化,
--       以确保 BCrypt 口令散列正确生成。已有历史数据默认归属 tenant_id=0, 可按需手工调整。
