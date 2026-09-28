package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医共体诊断字典(L2, 牵头机构统一维护): 西医诊断/中医诊断/症候/手术/肿瘤五类, 单表按 dict_type 区分。
 * 导入源(std_*): west←icd10/icd10_nat, tcm←tcm_disease_new/tcm_disease, symp←tcm_syndrome_new/tcm_syndrome,
 * oper←icd9/icd9_nat, tumor←morphology; 医保版源(非_nat)导入时 yb_code=code, 国标版源留空待人工补码。
 * 医生站诊断录入统一检索本字典启用项(status=1), 全医共体共用(目录行数十万级, 不做 L3 机构级启停)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_diag_dict")
public class HisDiagDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典类型:west-西医诊断(ICD-10) tcm-中医诊断 symp-中医症候 oper-手术操作(ICD-9) tumor-肿瘤形态学 */
    private String dictType;
    /** 院内编码(租户内同类型唯一, 导入时取标准字典编码) */
    private String code;
    /** 名称(诊断/术式/症候名) */
    private String name;
    /** 医保编码(医保版源导入时=code, 如E11.900; 仅国标来源时留空待补) */
    private String ybCode;
    /** 类目(标准字典附加列: 章节/系统类目/亚目等) */
    private String category;
    /** 排序号 */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;
    /** 来源标准字典key(icd10/icd10_nat/icd9/icd9_nat/morphology/tcm_disease_new/tcm_disease/tcm_syndrome_new/tcm_syndrome) */
    private String srcType;
    /** 来源文档(标准字典行src_doc, 逐行不同) */
    private String srcDoc;
    /** 来源编码(标准字典行编码) */
    private String srcCode;

    /** 拼音简码(名称首字母, 保存时自动生成只读) */
    private String pyCode;

    /** 自定义简码(维护页可编辑, 选填) */
    private String abbrCode;
}
