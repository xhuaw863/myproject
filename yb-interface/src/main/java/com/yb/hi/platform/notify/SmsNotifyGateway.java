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
}
