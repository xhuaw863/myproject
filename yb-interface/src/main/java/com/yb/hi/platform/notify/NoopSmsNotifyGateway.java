package com.yb.hi.platform.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 显式 noop 通知下发网关(P2c): 不接真实通道, 仅记录日志并返回成功(无回执ID), 保持"发送即留痕"语义不阻断业务。
 * 仅在显式配置 yb.notify.gateway=noop 时生效; 未配置时缺省走 MockSmsNotifyGateway(P4a, 可观测闭环)。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yb.notify.gateway", havingValue = "noop", matchIfMissing = false)
public class NoopSmsNotifyGateway implements SmsNotifyGateway {

    @Override
    public Result send(String phone, String content) {
        log.info("[通知留痕-Noop] to={}, content={}", phone, content);
        return Result.ok(null);
    }
}
