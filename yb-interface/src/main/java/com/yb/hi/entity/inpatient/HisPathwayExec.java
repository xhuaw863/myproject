package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 临床路径执行记录(实例×任务逐日执行, 待执行/已执行/跳过/变异四态, 变异留原因并回链医嘱)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pathway_exec")
public class HisPathwayExec extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 路径实例ID(his_pathway_instance.id) */
    private Long instanceId;
    /** 任务ID(his_pathway_task.id) */
    private Long taskId;
    /** 节点ID(his_pathway_node.id) */
    private Long nodeId;
    /** 第X天 */
    private Integer dayNo;
    /** 执行日期 */
    private LocalDate execDate;
    /** 执行状态: 1待执行 2已执行 3跳过 4变异 */
    private Integer execStatus;
    /** 关联医嘱ID(his_inp_order.id) */
    private Long orderId;
    /** 变异原因(自由文本备注) */
    private String varianceReason;
    /** 变异原因分类码(cv_code:pathway_var_reason), 供质控柏拉图构成分析 */
    private String varianceType;
    /** 变异原因分类名称(字典回填) */
    private String varianceTypeName;
    /** 操作员ID(his_staff.id) */
    private Long operatorId;
}
