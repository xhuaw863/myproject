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
 * 医共体药品目录(L2, 牵头机构统一维护, 含价格)
 * 核心: 三级单位体系 dose_unit(剂量) -> min_unit(发药/药房计量) -> pack_unit(采购/药库记账),
 * 换算: 发药数量 = 医嘱剂量 / unit_dose (按 round_rule 取整); 最小单位数 = 大包装数 * pack_ratio。
 * 溯源: src_type/src_doc/src_code 记录取自哪个标准字典(如 drug=湖北医保药品编码库)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_catalog")
public class HisDrugCatalog extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 院内药品编码(租户内唯一) */
    private String drugCode;
    /** 医保药品代码(std_drug.drug_code) */
    private String ybDrugCode;
    /** 变更前医保码(上一次对照的医保编码, 对照变更留痕) */
    private String prevYbCode;
    /** 医保对照生效时间(当前医保码开始生效时刻) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime ybMapEffTime;
    /** 医保目录名称(不落库: 分页后按 yb_drug_code 回查 std_drug.reg_name 实时回填) */
    @TableField(exist = false)
    private String ybName;
    /** 药品本位码 */
    private String drugStdCode;
    /** 批准文号 */
    private String approvalNo;

    /** 通用名 */
    private String genericName;
    /** 商品名 */
    private String tradeName;
    /** 大类(西药/中成药等) */
    private String majorClass;
    /** 剂型编码(cv_code:dosform) */
    private String dosform;
    /** 剂型名称(字典回填) */
    private String dosformName;
    /** 剂型来源标识 */
    private String dosformSrc;
    /** 规格(如0.25g*24粒) */
    private String spec;
    /** 生产企业 */
    private String manufacturer;
    /** 上市许可持有人 */
    private String mktHolder;

    /** 甲乙丙类编码(cv_code:chrgitm_lv) */
    private String chrgitmLv;
    /** 甲乙丙类名称(字典回填) */
    private String chrgitmLvName;
    /** 甲乙丙类来源标识 */
    private String chrgitmLvSrc;
    /** 自付比例(0-1) */
    private BigDecimal selfpayProp;
    /** 医保支付标准(最小制剂单位) */
    private String payStdPrep;
    /** 谈判药品标识 */
    private String negoFlag;
    /** 门诊特殊疾病对应标识 */
    private String msdFlag;
    /** 限定支付范围自费标识 */
    private String ltdSelfFlag;
    /** 限定支付范围说明 */
    private String limitScope;

    /* ---- 三级单位与包装/剂量换算 ---- */
    /** 剂量单位(cv_code:dose_unit g/mg/IU/mL等) */
    private String doseUnit;
    /** 每最小包装单位含药量(如0.25g/粒) */
    private BigDecimal unitDose;
    /** 最小包装/发药单位(片/粒/支, 药房计量基准) */
    private String minUnit;
    /** 采购/大包装单位(盒/瓶/箱, 药库记账单位) */
    private String packUnit;
    /** 包装换算比(大包装→最小单位, 如24粒/盒) */
    private Integer packRatio;
    /** 发药取整规则:1向上 2向下 3四舍五入 */
    private Integer roundRule;

    /* ---- 价格(按最小单位, 调价走 his_price_adjust 留痕) ---- */
    /** 进货价(最小单位) */
    private BigDecimal purchasePrice;
    /** 零售价(最小单位) */
    private BigDecimal retailPrice;
    /** 零差率标志:1是 0否 */
    private Integer zeroMargin;

    /* ---- 药事管理分类 ---- */
    /** 药品管理类别编码(cv_code:drug_class) */
    private String drugClass;
    /** 药品管理类别名称(字典回填) */
    private String drugClassName;
    /** 药品管理类别来源标识 */
    private String drugClassSrc;
    /** 抗菌药物分级(hbvalue:HBCV08.50.029 11/12/13) */
    private String abxGrade;
    /** 抗菌药物分级名称(字典回填) */
    private String abxGradeName;
    /** 抗菌药物分级来源标识 */
    private String abxGradeSrc;
    /** OTC标志:1是 0否 */
    private Integer otcFlag;
    /** 基本药物标志:1是 0否 */
    private Integer essentialFlag;
    /** 妊娠用药分级(A/B/C/D/X) */
    private String pregClass;
    /** 皮试标志:1需皮试 0否 */
    private Integer skinTestFlag;
    /** 储存条件编码(cv_code:storage_cond) */
    private String storageCond;
    /** 储存条件名称(字典回填) */
    private String storageCondName;
    /** 储存条件来源标识 */
    private String storageCondSrc;
    /** 单次处方最大量(最小单位, 管制药品) */
    private BigDecimal maxQtyOnce;

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
    /** 来源标准字典key(drug/tcm/preparation/院内自定义) */
    private String srcType;
    /** 来源文档 */
    private String srcDoc;
    /** 来源编码(标准字典行编码) */
    private String srcCode;

    /** 大包装参考价 = retail_price * pack_ratio(派生展示, 不落库) */
    @TableField(exist = false)
    private BigDecimal packPrice;

    /** 发药药房生效零售价(覆盖价优先, 回落本行 retailPrice; 医生站按房取数瞬态回填, 不落库) */
    @TableField(exist = false)
    private BigDecimal effPrice;

    /** 指定药房库存位在库总量(医生站库存软提示瞬态字段, 不落库) */
    @TableField(exist = false)
    private BigDecimal stockQty;

    /** 拼音简码(通用名首字母, 保存时自动生成只读) */
    private String pyCode;

    /** 自定义简码(维护页可编辑, 选填) */
    private String abbrCode;
}
