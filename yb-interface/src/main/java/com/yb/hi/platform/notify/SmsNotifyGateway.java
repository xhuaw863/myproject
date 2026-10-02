package com.yb.hi.platform.notify;

/**
 * 手术通知下发网关(P2c): 抽象真实短信/APP 通道。缺省走 NoopSmsNotifyGateway(仅留痕不阻断);
 * 配置 yb.notify.gateway=http 时启用 HttpSmsNotifyGateway 真实下发并回填网关回执。
 */
public interface SmsNotifyGateway {

    /**
     * 下发一条通知。
     *
     * @param phone   接收号码
     * @param content 通知内容
     * @return 下发结果(含成功标志与网关回执ID)
     */
    Result send(String phone, String content);

    /**
     * 送达回查(P4a): 依据下发返回的网关回执ID查询最终送达状态。
     * 缺省实现返回"未知"(Noop/Http 无回查能力时不影响主流程), Mock 网关覆盖为确定性派生。
     *
     * @param msgId 下发时返回的网关回执ID
     * @return 送达回查报告
     */
    default DeliveryReport queryDelivery(String msgId) {
        return DeliveryReport.unknown();
    }

    /** 下发结果。 */
    class Result {
        private final boolean success;
        private final String msgId;
        private final String error;

        private Result(boolean success, String msgId, String error) {
            this.success = success;
            this.msgId = msgId;
            this.error = error;
        }

        public static Result ok(String msgId) {
            return new Result(true, msgId, null);
        }

        public static Result fail(String error) {
            return new Result(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMsgId() {
            return msgId;
        }

        public String getError() {
            return error;
        }
    }

    /** 送达回查报告(P4a)。state: 1已送达 2送达失败 0未知/无可回查。 */
    class DeliveryReport {
        private final int state;
        private final String error;

        private DeliveryReport(int state, String error) {
            this.state = state;
            this.error = error;
        }

        public static DeliveryReport delivered() {
            return new DeliveryReport(1, null);
        }

        public static DeliveryReport failed(String error) {
            return new DeliveryReport(2, error);
        }

        public static DeliveryReport unknown() {
            return new DeliveryReport(0, null);
        }

        /** 1已送达 2送达失败 0未知。 */
        public int getState() {
            return state;
        }

        public String getError() {
            return error;
        }
    }
}
