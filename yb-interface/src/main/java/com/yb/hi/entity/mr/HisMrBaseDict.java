package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病案系统维护字典(P2): 单一通用码表, 按 dict_type 归类病案基础/卫统基础/病区/医疗小组/节假日。
 * 员工(his_staff)/科室(his_dept)复用既有主数据, 不在此表维护。ext1/ext2 泛化承载各类型附加语义列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_base_dict")
public class HisMrBaseDict extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID(NULL=全局可复用) */
    private Long orgId;
    /** 字典类别:case_base病案基础 wt_base卫统基础 ward病区 med_team医疗小组 holiday节假日 */
    private String dictType;
    /** 编码(节假日为yyyy-MM-dd) */
    private String code;
    /** 名称 */
    private String name;
    /** 上级编码(层级,可空) */
    private String parentCode;
    /** 附加1(病区所属科室/医疗小组组长等) */
    private String ext1;
    /** 附加2(医疗小组所属科室/节假日类型等) */
    private String ext2;
    /** 有效标志:1启用 0停用 */
    private Integer validFlag;
    /** 排序 */
    private Integer sortNo;
    /** 备注 */
    private String remark;
}
