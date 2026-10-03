-- 医保原生精度对齐(规范 2204/2301 费用明细: pric 16,6 / cnt 16,4 / det_item_fee_sumamt 16,2)
-- 原则: 仅加宽小数位, 逐列保留原可空性与默认值(经 information_schema 实查核对)
-- 门诊费用链
ALTER TABLE his_prescription_item
  MODIFY COLUMN price    DECIMAL(16,6) NULL DEFAULT 0.000000 COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity DECIMAL(16,4) NULL DEFAULT 0.0000 COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount   DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '金额(医保规范16,2)';
ALTER TABLE his_order_item
  MODIFY COLUMN price    DECIMAL(16,6) NULL DEFAULT 0.000000 COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity DECIMAL(16,4) NULL DEFAULT 0.0000 COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount   DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '金额(医保规范16,2)';
ALTER TABLE his_charge_bill_item
  MODIFY COLUMN price        DECIMAL(16,6) NOT NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN qty          DECIMAL(16,4) NOT NULL COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount       DECIMAL(16,2) NOT NULL COMMENT '金额(医保规范16,2)',
  MODIFY COLUMN refunded_qty DECIMAL(16,4) NULL DEFAULT 0.0000 COMMENT '已退数量(16,4)';
ALTER TABLE his_prescription MODIFY COLUMN total_amount DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '总额(16,2)';
ALTER TABLE his_charge_bill  MODIFY COLUMN total_amount DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '总额(16,2)';
-- 住院费用链(2301 源)
ALTER TABLE his_inp_order
  MODIFY COLUMN unit_price DECIMAL(16,6) NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity   DECIMAL(16,4) NULL COMMENT '数量(医保规范16,4)';
ALTER TABLE his_inp_charge_detail
  MODIFY COLUMN unit_price DECIMAL(16,6) NOT NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity   DECIMAL(16,4) NULL DEFAULT 1.0000 COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount     DECIMAL(16,2) NOT NULL COMMENT '金额(医保规范16,2)';
ALTER TABLE his_pathway_task
  MODIFY COLUMN unit_price DECIMAL(16,6) NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity   DECIMAL(16,4) NULL COMMENT '数量(医保规范16,4)';
-- 手术计费链(入费用明细)
ALTER TABLE his_surgery_fee
  MODIFY COLUMN unit_price DECIMAL(16,6) NOT NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity   DECIMAL(16,4) NULL DEFAULT 1.0000 COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount     DECIMAL(16,2) NOT NULL COMMENT '金额(医保规范16,2)';
ALTER TABLE his_surgery_fee_tpl_item
  MODIFY COLUMN unit_price DECIMAL(16,6) NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity   DECIMAL(16,4) NULL DEFAULT 1.0000 COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount     DECIMAL(16,2) NULL COMMENT '金额(医保规范16,2)';
ALTER TABLE his_surgery_material
  MODIFY COLUMN unit_price DECIMAL(16,6) NULL COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN quantity   DECIMAL(16,4) NULL DEFAULT 1.0000 COMMENT '数量(医保规范16,4)',
  MODIFY COLUMN amount     DECIMAL(16,2) NULL COMMENT '金额(医保规范16,2)';
-- 发药/请领(计费与退药数量口径)
ALTER TABLE his_dispense_item MODIFY COLUMN billed_price DECIMAL(16,6) NULL DEFAULT 0.000000 COMMENT '计费单价(医保规范16,6)';
ALTER TABLE his_requisition_item
  MODIFY COLUMN retail_price DECIMAL(16,6) NULL COMMENT '零售价(医保规范16,6)',
  MODIFY COLUMN qty_apply    DECIMAL(16,4) NULL DEFAULT 0.0000 COMMENT '请领数量(16,4)',
  MODIFY COLUMN qty_approved DECIMAL(16,4) NULL COMMENT '批准数量(16,4)',
  MODIFY COLUMN qty_received DECIMAL(16,4) NULL COMMENT '实领数量(16,4)';
-- 定价字典(收费 pric 源头, 医保目录价格本身 16,6)
ALTER TABLE his_charge_item
  MODIFY COLUMN price    DECIMAL(16,6) NULL DEFAULT 0.000000 COMMENT '单价(医保规范16,6)',
  MODIFY COLUMN price_l1 DECIMAL(16,6) NULL COMMENT '一级价格(16,6)',
  MODIFY COLUMN price_l2 DECIMAL(16,6) NULL COMMENT '二级价格(16,6)',
  MODIFY COLUMN price_l3 DECIMAL(16,6) NULL COMMENT '三级价格(16,6)';
ALTER TABLE his_drug_catalog
  MODIFY COLUMN retail_price   DECIMAL(16,6) NULL COMMENT '零售价(医保规范16,6)',
  MODIFY COLUMN purchase_price DECIMAL(16,6) NULL COMMENT '购进价(医保规范16,6)';
ALTER TABLE his_pharmacy_drug_price MODIFY COLUMN retail_price DECIMAL(16,6) NOT NULL COMMENT '零售价(医保规范16,6)';
ALTER TABLE his_cons_catalog
  MODIFY COLUMN charge_price   DECIMAL(16,6) NULL COMMENT '收费标准(16,6)',
  MODIFY COLUMN purchase_price DECIMAL(16,6) NULL COMMENT '购进价(16,6)';
ALTER TABLE his_price_adjust
  MODIFY COLUMN new_price DECIMAL(16,6) NULL COMMENT '新价(16,6)',
  MODIFY COLUMN old_price DECIMAL(16,6) NULL COMMENT '原价(16,6)';
ALTER TABLE his_charge_addon_rule MODIFY COLUMN unit_price DECIMAL(16,6) NULL COMMENT '单价(16,6)';
ALTER TABLE his_bed MODIFY COLUMN daily_price DECIMAL(16,6) NULL DEFAULT 0.000000 COMMENT '床日单价(16,6)';
-- 补遗漏: 发药行金额与住院限额(费用口径统一 16,2)
ALTER TABLE his_dispense_item
  MODIFY COLUMN billed_amount   DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '划价快照金额(16,2)',
  MODIFY COLUMN returned_amount DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '已退金额(16,2)';
ALTER TABLE his_inp_charge_detail
  MODIFY COLUMN daily_limit DECIMAL(16,2) NULL COMMENT '日限额(16,2)',
  MODIFY COLUMN total_limit DECIMAL(16,2) NULL COMMENT '总限额(16,2)';
ALTER TABLE his_requisition     MODIFY COLUMN total_amount DECIMAL(16,2) NULL DEFAULT 0.00 COMMENT '请领金额合计(16,2)';
ALTER TABLE his_requisition_item MODIFY COLUMN amount DECIMAL(16,2) NULL COMMENT '小计金额(16,2)';
ALTER TABLE his_drug_catalog MODIFY COLUMN max_qty_once DECIMAL(16,4) NULL COMMENT '单次最大用量(16,4)';
ALTER TABLE his_drug_stock     MODIFY COLUMN retail_price DECIMAL(16,6) NULL COMMENT '零售价(划价源头,医保规范16,6)';
ALTER TABLE his_stock_in_item  MODIFY COLUMN retail_price DECIMAL(16,6) NULL COMMENT '零售价(划价源头,医保规范16,6)';
ALTER TABLE his_stock_out_item MODIFY COLUMN retail_price DECIMAL(16,6) NULL COMMENT '零售价(划价源头,医保规范16,6)';
ALTER TABLE his_transfer_item  MODIFY COLUMN retail_price DECIMAL(16,6) NULL COMMENT '零售价(医保规范16,6)';
