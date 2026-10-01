package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 病案工作量统计录入(P2): 病案室按统计期/科室/责任人登记门诊·住院病区·医技·其他项工作量, 支持逻辑审核。
 * 独立于编目主表, 不回写临床首页。audit_status: 1待审 2通过 3驳回(逻辑审核=合理性复核而非临床质控)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_workload")
public class HisMrWorkload extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID(sys_org.id) */
    private Long orgId;
    /** 统计期(yyyy-MM) */
    private String period;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 科室名称 */
    private String deptName;
    /** 责任人(his_staff.id) */
    private Long staffId;
    /** 责任人姓名 */
    private String staffName;
    /** 工作量类别:outp门诊 inp住院病区 tech医技 other其他项 */
    private String category;
    /** 项目编码 */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 数量 */
    private BigDecimal qty;
    /** 金额(可空) */
    private BigDecimal amount;
    /** 逻辑审核状态:1待审 2通过 3驳回 */
    private Integer auditStatus;
    /** 审核人姓名 */
    private String auditUserName;
    /** 审核时间 */
    private LocalDateTime auditTime;
    /** 审核意见 */
    private String auditOpinion;
    /** 备注 */
    private String remark;
}
