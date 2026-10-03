package com.yb.hi.entity.emr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病历事件 Webhook 订阅(P7a): 外部系统按事件类型(逗号分隔, 对应 EmrEventType 枚举名)订阅
 * 病历归档/召回/封存/解封通知, 平台向 callback_url 推送 HMAC-SHA256 签名报文;
 * fail_count 连续失败计数供熔断降频。
 * 表由 DictSchemaMigration 启动期幂等建出; tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_webhook_subscription")
public class HisEmrWebhookSubscription extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 订阅方名称 */
    private String subscriberName;
    /** 回调地址 */
    private String callbackUrl;
    /** 订阅事件类型(逗号分隔, 对应 EmrEventType 枚举名) */
    private String eventTypes;
    /** HMAC签名密钥 */
    private String secretKey;
    /** 状态: 1启用 0禁用 */
    private Integer status;
    /** 最近推送时间 */
    private LocalDateTime lastPushTime;
    /** 连续失败次数 */
    private Integer failCount;
}
