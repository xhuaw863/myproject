package com.yb.hi.mapper.emr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.emr.HisEmrWebhookSubscription;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历事件 Webhook 订阅 Mapper(P7a)
 */
@Mapper
public interface HisEmrWebhookSubscriptionMapper extends BaseMapper<HisEmrWebhookSubscription> {
}
