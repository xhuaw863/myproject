package com.yb.hi.platform.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 闭环模拟短信网关(手麻P4a): 未配置 yb.notify.gateway 时的缺省实现, 零外部依赖、可测可观测。
 * <p>
 * 送达结果按接收号码尾号确定性派生并编码进网关回执ID(msgId), 使 send 与 queryDelivery 无状态、重启安全:
 * 尾号为 0 的号码模拟"送达失败"(回查返回失败带原因), 其余号码模拟"提交后已送达"。
 * msgId 形如 {@code MOCKSMS-<base36纳秒>-<0|1>}, 末段 0=将失败 1=将送达。
 * 显式配置 yb.notify.gateway=noop 走 Noop(纯留痕无回执), =http 走真实 Http 网关, 二者能力不变。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yb.notify.gateway", havingValue = "mock", matchIfMissing = true)
public class MockSmsNotifyGateway implements SmsNotifyGateway {

    private static final String PREFIX = "MOCKSMS-";
    /** 模拟"送达失败"的号码尾号。 */
    private static final char FAIL_TAIL = '0';

    @Override
    public Result send(String phone, String content) {
        if (!StringUtils.hasText(phone)) {
            return Result.fail("接收号码为空");
        }
        boolean willFail = phone.charAt(phone.length() - 1) == FAIL_TAIL;
        String msgId = PREFIX + Long.toString(System.nanoTime(), 36) + "-" + (willFail ? "0" : "1");
        log.info("[通知下发-Mock] to={}, msgId={}, 预计={}", phone, msgId, willFail ? "送达失败" : "已送达");
        return Result.ok(msgId);
    }

    @Override
    public DeliveryReport queryDelivery(String msgId) {
        if (msgId == null || !msgId.startsWith(PREFIX)) {
            return DeliveryReport.unknown();
        }
        int dash = msgId.lastIndexOf('-');
        String tail = dash < 0 ? "" : msgId.substring(dash + 1);
        if ("0".equals(tail)) {
            return DeliveryReport.failed("模拟送达失败: 号码无法接通(运营商回执)");
        }
        if ("1".equals(tail)) {
            return DeliveryReport.delivered();
        }
        return DeliveryReport.unknown();
    }
}
