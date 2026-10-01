package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医生工作站医疗模板(个人/科室/全院三级)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_medical_template")
public class HisMedicalTemplate extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板类型: soap/rx_set/order_set/fragment */
    private String templateType;
    /** 模板名称 */
    private String name;
    /** 科室级模板所属科室(null=全院) */
    private Long deptId;
    /** 个人级模板所属职工(null=科室/全院) */
    private Long staffId;
    /** 模板内容(JSON) */
    private String content;
    /** 排序号 */
    private Integer sortOrder;
    /** 收藏标记: 1收藏(列表置顶) 0普通(OP-D 模板收藏) */
    private Integer isFav;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
