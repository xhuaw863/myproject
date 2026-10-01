package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案分配: 按数量均分或按编码员科室偏好, 将待编目病案分配给编目员, 支持释放/重分配。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_assign")
public class HisMrAssign extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 分配方式:1偏好科室 2随机均分 */
    private Integer assignType;
    /** 分配编目员(his_staff.id) */
    private Long catalogerId;
    /** 编目员姓名 */
    private String catalogerName;
    /** 优先级 */
    private Integer priority;
    /** 分配状态:1已分配 2已释放 3已编目 */
    private Integer assignStatus;
    /** 分配操作人 */
    private String assignBy;
    /** 分配时间 */
    private LocalDateTime assignTime;
}
