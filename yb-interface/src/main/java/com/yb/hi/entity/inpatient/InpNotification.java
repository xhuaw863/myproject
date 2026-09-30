package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院通知(user_id 级站内通知, 类型: 1医嘱/2会诊/3病历/4预警/5评估/6系统)
 * 支撑住院看板未读角标(按类型分组)与消息中心分页列表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_notification")
public class InpNotification extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 接收用户ID(sys_user.id) */
    private Long userId;
    /** 通知类型: 1医嘱 2会诊 3病历 4预警 5评估 6系统 */
    private Integer notifyType;
    /** 标题 */
    private String title;
    /** 内容 */
    private String content;
    /** 关联业务类型 */
    private String refType;
    /** 关联业务ID */
    private Long refId;
    /** 是否已读: 1是 0否 */
    private Integer isRead;
    /** 读取时间 */
    private LocalDateTime readTime;
}
