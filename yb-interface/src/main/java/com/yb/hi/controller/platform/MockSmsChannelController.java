package com.yb.hi.controller.platform;

import com.yb.hi.framework.common.R;
import com.yb.hi.platform.notify.SmsNotifyGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟短信通道端点(手麻P4a): 站内暴露 Mock 网关的下发/回执查询能力, 供联调与取证直接观测闭环, 零外部依赖。
 * 仅在缺省(未配置)或显式 yb.notify.gateway=mock 时装配, 与 MockSmsNotifyGateway 同门控。
 * 仅薄门面转发 SmsNotifyGateway, 不落库、不改业务状态。
 */
@RestController
@RequestMapping("/api/mock/sms")
@ConditionalOnProperty(name = "yb.notify.gateway", havingValue = "mock", matchIfMissing = true)
public class MockSmsChannelController {

    private final SmsNotifyGateway gateway;

    public MockSmsChannelController(SmsNotifyGateway gateway) {
        this.gateway = gateway;
    }

    /** 模拟下发: body {phone, content} -> {success, msgId, error}。 */
    @PostMapping("/send")
    public R<Map<String, Object>> send(@RequestBody Map<String, String> body) {
        String phone = body == null ? null : body.get("phone");
        String content = body == null ? null : body.get("content");
        SmsNotifyGateway.Result r = gateway.send(phone, content);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", r.isSuccess());
        data.put("msgId", r.getMsgId());
        data.put("error", r.getError());
        return R.ok(data);
    }

    /** 模拟回执查询: 路径 msgId -> {state(1已送达 2失败 0未知), error}。 */
    @GetMapping("/report/{msgId}")
    public R<Map<String, Object>> report(@PathVariable String msgId) {
        SmsNotifyGateway.DeliveryReport rep = gateway.queryDelivery(msgId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", rep.getState());
        data.put("error", rep.getError());
        return R.ok(data);
    }
}
