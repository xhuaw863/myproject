package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 手术通知下发留痕(手麻P4a): 每次下发/送达回查追加一行(outbox 语义), 承接 Mock/Http 网关回执与送达状态。
 * 与 his_surgery_notify 主行松耦合(notify_id 关联, 不做外键)。tenant_id 由 MP 租户插件注入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_notify_log")
public class HisSurgeryNotifyLog extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 通知ID(his_surgery_notify.id) */
    private Long notifyId;
    /** 接收号码快照 */
    private String phone;
    /** 渠道快照: 1短信 2电话 3诊间 4自助机 5APP 6公众号 */
    private Integer channel;
    /** 下发内容快照 */
    private String content;
    /** 网关回执ID(Mock/Http 回填) */
    private String gatewayMsgId;
    /** 本行留痕状态: 1已提交 2已送达 3失败 */
    private Integer sendStatus;
    /** 失败原因 */
    private String errorMsg;
}
