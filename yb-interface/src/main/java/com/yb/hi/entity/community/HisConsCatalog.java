package com.yb.hi.entity.community;

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
 * 医共体耗材目录(L2, 牵头机构统一维护, 含价格)
 * 溯源: yb_cons_code=医保20位码(std_consumable.cons_code), src_type/src_doc/src_code 三件套。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_cons_catalog")
public class HisConsCatalog extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 院内耗材编码(租户内唯一) */
    private String consCode;
    /** 医保耗材代码(20位) */
    private String ybConsCode;
    /** 变更前医保码(上一次对照的医保编码, 对照变更留痕) */
    private String prevYbCode;
    /** 医保对照生效时间(当前医保码开始生效时刻) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime ybMapEffTime;
    /** 医保目录名称(不落库: 分页后按 yb_cons_code 回查 std_consumable.hi_genname 实时回填) */
    @TableField(exist = false)
    private String ybName;
    /** 注册证号 */
    private String regCertNo;

    /** 耗材通用名 */
    private String name;
    /** 医保一级分类 */
    private String cat1;
    /** 医保二级分类 */
    private String cat2;
    /** 医保三级分类 */
    private String cat3;
    /** 规格型号(补充录入) */
    private String specModel;
    /** 材质 */
    private String material;
    /** 特征 */
    private String feature;
    /** 生产企业 */
    private String manufacturer;

    /* ---- 单位与包装换算(同药品规则) ---- */
    /** 最小计价单位(个/套) */
    private String minUnit;
    /** 采购单位(盒/包) */
    private String packUnit;
    /** 包装换算比(采购单位→最小单位) */
    private Integer packRatio;

    /* ---- 价格与医保 ---- */
    /** 进货价(最小单位) */
    private BigDecimal purchasePrice;
    /** 收费价(单独收费项) */
    private BigDecimal chargePrice;
    /** 收费方式:1单独收费 0包含性(不单独收费) */
    private Integer chargeFlag;
    /** 甲乙丙类编码(cv_code:chrgitm_lv) */
    private String chrgitmLv;
    /** 甲乙丙类名称(字典回填) */
    private String chrgitmLvName;
    /** 甲乙丙类来源标识 */
    private String chrgitmLvSrc;
    /** 自付比例(0-1) */
    private BigDecimal selfpayProp;
    /** 医保支付标准 */
    private String payStd;

    /* ---- 管理属性 ---- */
    /** 高值耗材标志:1是 0否 */
    private Integer highValueFlag;
    /** 植入类标志:1是 0否 */
    private Integer implantFlag;
    /** 无菌标志:1是 0否 */
    private Integer sterileFlag;

    /* ---- 生命周期与溯源 ---- */
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 生效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effDate;
    /** 作废日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    /** 备注 */
    private String memo;
    /** 来源标准字典key(consumable/院内自定义) */
    private String srcType;
    /** 来源文档 */
    private String srcDoc;
    /** 来源编码(标准字典行编码) */
    private String srcCode;
}
