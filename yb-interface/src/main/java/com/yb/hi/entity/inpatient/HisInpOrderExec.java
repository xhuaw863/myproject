package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院医嘱执行记录(长期医嘱按频次生成执行计划, 护士逐次签名执行)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_order_exec")
public class HisInpOrderExec extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 医嘱ID(his_inp_order.id) */
    private Long orderId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 计划执行时间 */
    private LocalDateTime planTime;
    /** 实际执行时间 */
    private LocalDateTime execTime;
    /** 执行护士ID(his_staff.id) */
    private Long execNurseId;
    /** 状态: 1待执行 2已执行 3未执行 */
    private Integer execStatus;
    /** 执行备注 */
    private String execRemark;
}
