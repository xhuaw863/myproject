package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案借阅(P1 病案室日常作业): 病案实体借出/归还登记与借阅单打印数据源。
 * borrow_status: 1借出 2已归还 3逾期。归还时间/逾期由规则推算(expect_return_date 对比当天)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_borrow")
public class HisMrBorrow extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊ID(his_inp_visit.id) */
    private Long visitId;
    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 患者ID */
    private Long patientId;
    /** 机构ID(sys_org.id) */
    private Long orgId;
    /** 借阅单号 */
    private String borrowNo;
    /** 借阅人(his_staff.id) */
    private Long borrowerId;
    /** 借阅人姓名 */
    private String borrowerName;
    /** 借阅人科室ID(his_dept.id) */
    private Long borrowerDeptId;
    /** 借阅人科室名称 */
    private String borrowerDeptName;
    /** 借阅事由 */
    private String purpose;
    /** 借出时间 */
    private LocalDateTime borrowTime;
    /** 应归还日期 */
    private LocalDateTime expectReturnDate;
    /** 实际归还时间 */
    private LocalDateTime actualReturnTime;
    /** 借阅状态:1借出 2已归还 3逾期 */
    private Integer borrowStatus;
    /** 经办人姓名 */
    private String operatorName;
}
