package com.yb.hi.common;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 签名工具类
 * 按照医保接口规范: 剔除cainfo、input后，按数据元标识ASCII码递增排序，
 * 组合成"参数=参数值"格式，用&连接，生成待签名字符串，SM2运算得到签名值
 */
@Slf4j
public class SignUtil {

    /**
     * 对报文进行签名
     * 实际生产环境应使用SM2国密算法，此处提供框架，私钥配置后启用
     */
    public static String sign(JSONObject msg, String privateKey) {
        if (privateKey == null || privateKey.isEmpty()) {
            // 未配置私钥时，使用SM3摘要代替(开发测试用)
            return sm3Digest(buildSignContent(msg));
        }
        try {
            String signContent = buildSignContent(msg);
            return sm2Sign(signContent, privateKey);
        } catch (Exception e) {
            log.error("签名失败", e);
            return "";
        }
    }

    /**
     * 构建待签名字符串
     * 剔除cainfo和input，按key的ASCII升序排列，格式: key1=value1&key2=value2
     */
    private static String buildSignContent(JSONObject msg) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : msg.entrySet()) {
            String key = entry.getKey();
            if ("cainfo".equals(key) || "input".equals(key)) {
                continue;
            }
            Object val = entry.getValue();
            sorted.put(key, val == null ? "" : val.toString());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * SM2签名(生产环境需引入国密SDK)
     * 此处为占位实现，实际对接时替换为真实SM2签名
     */
    private static String sm2Sign(String content, String privateKey) {
        // TODO: 对接时替换为真实SM2签名实现
        // 可使用 BouncyCastle 或医保局提供的签名SDK
        log.warn("SM2签名未实现，使用SM3摘要代替(仅供开发测试)");
        return sm3Digest(content);
    }

    /**
     * SM3摘要(开发测试用)
     */
    private static String sm3Digest(String content) {
        try {
            // 使用SHA-256模拟SM3(开发阶段)，生产环境应替换为真实SM3
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("摘要计算失败", e);
            return "";
        }
    }
}
