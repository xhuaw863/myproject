package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 收费项目(医共体统一目录: 药品/诊疗/耗材, 牵头机构统一维护含分级价格)
 * med_list_codg 为与医保目录的对照编码; 一/二/三级价格由牵头机构统一定义,
 * 机构执行价按 sys_org.price_lv 取对应档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_charge_item")
public class HisChargeItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 院内收费项目编码 */
    private String itemCode;
    /** 院内项目名称 */
    private String itemName;
    /** 项目大类: 药品/诊疗/耗材/其他 */
    private String itemType;
    /** 细分类别 */
    private String itemCat;
    /** 规格 */
    private String spec;
    /** 单位 */
    private String unit;
    /** 单价(迁移期默认价; 分级价格启用后执行价见 execPrice) */
    private BigDecimal price;
    /** 一级机构价格(牵头机构统一定义, 调价走 his_price_adjust 留痕) */
    private BigDecimal priceL1;
    /** 二级机构价格 */
    private BigDecimal priceL2;
    /** 三级机构价格 */
    private BigDecimal priceL3;
    /** 全国医疗服务项目编码(std_msi_nat/msi_hb.item_code) */
    private String natItemCode;
    /** 湖北地方项目编码(std_med_service.loc_item_code) */
    private String locItemCode;
    /** 项目内涵 */
    private String itemContent;
    /** 除外内容 */
    private String itemExcluded;
    /** 收费票据分类 */
    private String invoiceClass;
    /** 会计科目分类 */
    private String acctClass;
    /** 病案首页费用分类(归并), 值域 std_mr_cost_class.raw_value */
    private String mrCostClass;
    /** 物价分类码(std_msi_cat.cat_code, 导入自 msi_nat.cat_code) */
    private String catCode;
    /** 医疗科室类别 */
    private String deptCaty;
    /** 来源标准字典key(med_service/msi_hb/msi_nat/院内自定义) */
    private String srcType;
    /** 来源文档 */
    private String srcDoc;
    /** 来源编码(标准字典行编码) */
    private String srcCode;
    /** 生效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effDate;
    /** 作废日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    /** 医保医疗目录编码(对照) */
    private String medListCodg;
    /** 医保目录名称(不落库: 分页后按 med_list_codg 回查 std_med_service.loc_item_name 实时回填) */
    @TableField(exist = false)
    private String ybName;
    /** 变更前医保码(上一次对照的医保编码, 对照变更留痕) */
    private String prevYbCode;
    /** 医保对照生效时间(当前医保码开始生效时刻) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime ybMapEffTime;
    /** 医保机构目录编码(对照) */
    private String medinsListCodg;
    /** 医疗收费项目类别: 01-药品 02-诊疗 03-耗材 */
    private String medChrgitmType;
    /** 收费项目等级: 01-甲 02-乙 03-丙 */
    private String chrgitmLv;
    /** 自付比例(0-1) */
    private BigDecimal selfpayProp;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
    /** 备注 */
    private String memo;

    /** 机构执行价(按查询机构 price_lv 取对应档, 派生不落库) */
    @TableField(exist = false)
    private BigDecimal execPrice;
    /** 执行价档次(派生不落库) */
    @TableField(exist = false)
    private Integer execPriceLv;
}
