package com.yb.hi.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

/**
 * 医保接口统一响应
 */
@Data
public class YbResponse {

    /** 交易状态码: 0-成功, -1-失败 */
    private String infcode;

    /** 接收方报文ID */
    private String infRefmsgid;

    /** 接收报文时间 */
    private String refmsgTime;

    /** 响应报文时间 */
    private String respondTime;

    /** 错误信息 */
    private String errMsg;

    /** 交易输出(JSON字符串) */
    private String output;

    /** 原始响应JSON */
    private String rawJson;

    /** 发送的请求报文JSON(用于界面回显验证) */
    private String requestJson;

    /** 结果未知标志(批次4 M1): true=超时/网络异常, 平台侧是否受理不可知; false=平台已有明确回执(成功或拒绝) */
    private boolean unknown;

    public boolean isSuccess() {
        return "0".equals(infcode);
    }

    public boolean isUnknown() {
        return unknown;
    }

    /**
     * 获取output中的指定节点
     */
    public JSONObject getOutputNode(String nodeName) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        JSONObject outputObj = JSON.parseObject(output);
        return outputObj.getJSONObject(nodeName);
    }

    /**
     * 获取output中的指定节点(数组)
     */
    public com.alibaba.fastjson2.JSONArray getOutputArray(String nodeName) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        JSONObject outputObj = JSON.parseObject(output);
        return outputObj.getJSONArray(nodeName);
    }

    /**
     * 获取完整的output对象
     */
    public JSONObject getOutputObject() {
        if (output == null || output.isEmpty()) {
            return new JSONObject();
        }
        return JSON.parseObject(output);
    }

    public static YbResponse fail(String errMsg) {
        YbResponse resp = new YbResponse();
        resp.setInfcode("-1");
        resp.setErrMsg(errMsg);
        return resp;
    }

    /** 结果未知响应(超时/网络异常): 平台侧状态不可知, 补偿引擎据此走 UNKNOWN 决策树 */
    public static YbResponse unknown(String errMsg) {
        YbResponse resp = fail(errMsg);
        resp.setUnknown(true);
        return resp;
    }
}
