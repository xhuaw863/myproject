package com.yb.hi.entity.medtech;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 危急值记录(报告→复核→通知→接收→处置闭环留痕)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_critical_value")
public class HisCriticalValue extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 报告ID(his_exam_report.id) */
    private Long reportId;
    /** 医嘱单ID(his_order.id) */
    private Long orderId;
    /** 患者ID */
    private Long patientId;
    /** 项目编码 */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 结果值(触发危急值) */
    private String resultValue;
    /** 发现时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime discoverTime;
    /** 复核技师ID(his_staff.id) */
    private Long verifyTechId;
    /** 复核时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifyTime;
    /** 通知时间(电话通知临床) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime notifyTime;
    /** 通知对象(医生/护士) */
    private String notifyTarget;
    /** 接收时间(临床回执) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime receiveTime;
    /** 接收人 */
    private String receivePerson;
    /** 处置时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handleTime;
    /** 处置措施 */
    private String handleMeasures;
    /** 状态: 0待复核 1已通知 2已接收 3已处置 */
    private Integer status;
}
