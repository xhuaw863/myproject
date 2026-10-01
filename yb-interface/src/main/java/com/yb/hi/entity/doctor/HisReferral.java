package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门诊转诊登记(上转/下转双向, 申请→接收→完成状态机)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_referral")
public class HisReferral extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联门诊就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 方向: 1-上转/转出 2-下转/接收 */
    private Integer direction;
    /** 目标医院 */
    private String toHospital;
    /** 目标科室 */
    private String toDept;
    /** 转诊原因/病情 */
    private String reason;
    /** 病情摘要(转诊单正文) */
    private String summary;
    /** 联系电话 */
    private String contactPhone;
    /** 状态: 1-已申请 2-已接收 3-已完成 4-已取消 */
    private Integer status;
    /** 机构ID(转出/接收方归属) */
    private Long orgId;
}
