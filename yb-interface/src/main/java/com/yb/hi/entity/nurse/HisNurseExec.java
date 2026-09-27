package com.yb.hi.entity.nurse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 护士执行记录(注射/输液/皮试等医嘱执行统一台账)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nurse_exec")
public class HisNurseExec extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 医嘱单ID(his_order.id) */
    private Long orderId;
    /** 医嘱明细ID(his_order_item.id) */
    private Long orderItemId;
    /** 患者ID */
    private Long patientId;
    /** 执行类型: injection注射/infusion输液/skin_test皮试/other其他 */
    private String execType;
    /** 执行单号(EX+日期+序号) */
    private String execNo;
    /** 执行状态: 0待执行 1执行中 2已完成 3已取消 */
    private Integer execStatus;
    /** 执行护士ID(his_staff.id) */
    private Long execNurseId;
    /** 执行(开始)时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime execTime;
    /** 核对护士ID(高危操作双人核对) */
    private Long verifyNurseId;
    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    /** 患者反应 */
    private String patientResponse;
    /** 备注 */
    private String remark;
}
