package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手术通知记录(预约成功/安排变动/术前提醒; 短信无真实通道, 发送仅置状态留痕模拟)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_notify")
public class HisSurgeryNotify extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 申请单ID(可空) */
    private Long applyId;
    /** 手术ID(可空) */
    private Long surgeryId;
    /** 患者姓名 */
    private String patientName;
    /** 联系电话 */
    private String phone;
    /** 通知类型: 1预约成功 2安排变动 3术前提醒 */
    private Integer notifyType;
    /** 渠道: 1短信 2电话 3诊间 */
    private Integer channel;
    /** 通知内容 */
    private String content;
    /** 状态: 1待通知 2已通知 3已回复 */
    private Integer status;
    /** 患者回复内容 */
    private String replyContent;
    /** 发送/处理人 */
    private String sendBy;
    /** 发送时间 */
    private LocalDateTime sendTime;
    /** 通知下发重试次数(P2c) */
    private Integer retryCount;
    /** 短信/APP网关回执ID(真实通道回填, P2c) */
    private String gatewayMsgId;
}
