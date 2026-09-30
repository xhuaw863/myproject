package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 临床路径节点(模板×第X天网格, 同天多节点按 sort_no 排序)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pathway_node")
public class HisPathwayNode extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板ID(his_pathway_template.id) */
    private Long templateId;
    /** 第X天 */
    private Integer dayNo;
    /** 节点名称 */
    private String nodeName;
    /** 节点描述 */
    private String nodeDesc;
    /** 排序号 */
    private Integer sortNo;
}
