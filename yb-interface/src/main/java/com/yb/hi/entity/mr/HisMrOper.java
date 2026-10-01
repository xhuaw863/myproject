package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案编目手术操作明细(多条): 国临版(ICD-9-CM-3)↔医保版对照、位序、上报、主手术标志。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_oper")
public class HisMrOper extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 国临版手术操作编码(ICD-9-CM-3) */
    private String clinicalCode;
    /** 国临版手术操作名称 */
    private String clinicalName;
    /** 医保版手术编码 */
    private String ybCode;
    /** 医保版手术名称 */
    private String ybName;
    /** 医保位序 */
    private Integer ybSortNo;
    /** 是否医保上报:0否 1是 */
    private Integer reportFlag;
    /** 主手术标志:0否 1是 */
    private Integer mainFlag;
    /** 医保灰码:0否 1是 */
    private Integer grayFlag;
    /** 手术日期 */
    private LocalDateTime operDate;
    /** 手术医师 */
    private String surgeonName;
    /** 麻醉方式 */
    private String anesthesia;
    /** 切口类型 */
    private String incisionType;
    /** 愈合等级 */
    private String healLevel;
    /** 序号 */
    private Integer sortNo;
}
