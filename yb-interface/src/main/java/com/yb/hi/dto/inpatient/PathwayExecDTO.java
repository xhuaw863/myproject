package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 临床路径任务执行/变异登记请求
 */
@Data
public class PathwayExecDTO {

    /** 路径实例ID(his_pathway_instance.id) */
    private Long instanceId;
    /** 任务ID(his_pathway_task.id) */
    private Long taskId;
    /** 执行状态: 1待执行 2已执行 3跳过 4变异 */
    private Integer execStatus;
    /** 变异原因 */
    private String varianceReason;
}
