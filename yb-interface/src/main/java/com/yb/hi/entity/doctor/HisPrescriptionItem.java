package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 处方明细表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_prescription_item")
public class HisPrescriptionItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 处方ID */
    private Long prescriptionId;
    /** 医共体药品目录ID(his_drug_catalog.id) */
    private Long drugId;
    /** 每最小包装单位含药量(换算快照) */
    private BigDecimal unitDose;
    /** 包装换算比(大包装→最小单位, 快照) */
    private Integer packRatio;
    /** 发药取整规则快照:1向上 2向下 3四舍五入 */
    private Integer roundRule;
    /** 收费项目ID(his_charge_item) */
    private Long itemId;
    /** 院内项目编码 */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 规格 */
    private String spec;
    /** 单位 */
    private String unit;
    /** 单价 */
    private BigDecimal price;
    /** 数量 */
    private BigDecimal quantity;
    /** 金额 */
    private BigDecimal amount;
    /** 单次剂量 */
    private String dosage;
    /** 剂量单位 */
    private String dosageUnit;
    /** 用法 */
    private String usageMethod;
    /** 频次 */
    private String frequency;
    /** 给药途径 */
    private String administration;
    /** 用药组号 */
    private String groupNo;
    /** 用药天数 */
    private Integer days;
    /** 医保目录编码 */
    private String medListCodg;

    // ===== 门诊医生站对标 OP-C: 草药/中成药专业化(需求2.2.2.3.14.3) =====
    /** 煎法代码: 先煎/后煎/包煎/烊化等 */
    private String decoction;
    /** 炮制代码: 炒/炙/煅/蒸等 */
    private String processing;
    /** 治法代码: 汗/吐/下/和/温/清/消/补等 */
    private String therapy;
    /** 药剂形式: 饮片/颗粒/成药/自备 */
    private String herbForm;
    /** 倍数基础量(0=不启用): 单味剂量须为其整数倍, 保存时服务端校验 */
    private Integer multipleBase;
}
