package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病案编目诊断明细(多条): 携带国临版↔医保版对照、医保位序、上报勾选、灰码标识。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_diag")
public class HisMrDiag extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 诊断类别:outp门急诊 adm入院 dmain出院主 dother出院次 path病理 injure损伤中毒外因 infect院内感染 */
    private String diagType;
    /** 国临版诊断编码 */
    private String clinicalCode;
    /** 国临版诊断名称 */
    private String clinicalName;
    /** 医保版诊断编码 */
    private String ybCode;
    /** 医保版诊断名称 */
    private String ybName;
    /** 医保位序 */
    private Integer ybSortNo;
    /** 是否医保上报:0否 1是 */
    private Integer reportFlag;
    /** 医保灰码:0否 1是 */
    private Integer grayFlag;
    /** 主诊断标志:0否 1是 */
    private Integer mainFlag;
    /** 医师诊断描述 */
    private String doctorDesc;
    /** 同类别内序号 */
    private Integer sortNo;
}
