package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 医共体用药字典(L2, 牵头机构统一维护): 用法(给药途径)/用药频次, 单表按 dict_type 区分。
 * 其它机构在 L3(his_org_catalog, catalog_type=usage/freq)选择性启用; 医生站仅可下拉本机构启用项。
 * 溯源: src_type/src_doc/src_code 记录取自哪个医保标准值域(用法←drug_medc_way_code/CV06.00.102;
 * 频次←used_frqu/CV06.00.228), 亦允许院内自定义补充。频次带 daily_times 驱动发药量换算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_med_dict")
public class HisMedDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典类型:usage-用法(给药途径) freq-用药频次 */
    private String dictType;
    /** 院内编码(租户内同类型唯一) */
    private String code;
    /** 名称(如口服/静脉注射; 每天三次tid) */
    private String name;
    /** 医保值域编码(用法:drug_medc_way_code/CV06.00.102; 频次:used_frqu/CV06.00.228) */
    private String ybCode;
    /** 每日次数(仅频次; 驱动发药量换算, 支持小数如0.5=隔日) */
    private BigDecimal dailyTimes;
    /** 排序号 */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;
    /** 来源标准字典key(cv_code/hbvalue/院内自定义) */
    private String srcType;
    /** 来源文档 */
    private String srcDoc;
    /** 来源编码(标准值域行编码) */
    private String srcCode;
}
