package com.yb.hi.common;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithID;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 签名工具类
 * 按照医保接口规范第4章: 剔除cainfo、input后，按数据元标识ASCII码递增排序，
 * 组合成"参数=参数值"格式，用&连接，生成待签名字符串，SM2运算得到签名值(cainfo)。
 * SM2 按 GB/T 32918 实现(SM3 摘要, 默认用户标识 1234567812345678), 签名值输出大写hex。
 */
@Slf4j
public class SignUtil {

    /** 国密默认用户标识(GM/T 0009, 医保平台通行口径) */
    private static final byte[] DEFAULT_USER_ID = "1234567812345678".getBytes(StandardCharsets.UTF_8);

    /**
     * 对报文进行签名
     * 未配置私钥时降级为SM3摘要(仅供开发联调, mock模式不验签);
     * 配置私钥后执行真实SM2签名, 失败抛异常(空签名报文平台验签必失败, 不允许吞掉)。
     */
    public static String sign(JSONObject msg, String privateKey) {
        String signContent = buildSignContent(msg);
        if (privateKey == null || privateKey.isEmpty()) {
            log.warn("SM2私钥未配置, 使用SM3摘要代替签名(仅供开发联调, 生产必须配置私钥)");
            return sm3Hex(signContent);
        }
        return sm2Sign(signContent, privateKey);
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
     * SM2签名(GB/T 32918): SM3摘要 + 默认用户标识, 输出大写hex(r||s, 128位)
     */
    private static String sm2Sign(String content, String privateKey) {
        try {
            BigInteger d = parsePrivateKey(privateKey);
            X9ECParameters x9 = GMNamedCurves.getByName("sm2p256v1");
            ECDomainParameters domain = new ECDomainParameters(x9.getCurve(), x9.getG(), x9.getN(), x9.getH());
            ECPrivateKeyParameters priKey = new ECPrivateKeyParameters(d, domain);
            SM2Signer signer = new SM2Signer();
            CipherParameters withId = new ParametersWithID(priKey, DEFAULT_USER_ID);
            signer.init(true, withId);
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            signer.update(contentBytes, 0, contentBytes.length);
            byte[] sig = signer.generateSignature();
            return Hex.toHexString(sig).toUpperCase();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SM2签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * SM3摘要(GB/T 32905), 输出大写hex
     */
    private static String sm3Hex(String content) {
        SM3Digest digest = new SM3Digest();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        digest.update(bytes, 0, bytes.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return Hex.toHexString(out).toUpperCase();
    }

    /**
     * 解析SM2私钥, 兼容以下格式:
     * 1) 64位hex串(32字节标量d)
     * 2) Base64编码的32字节标量d
     * 3) Base64编码的64位hex串
     * 4) DER编码私钥(PKCS#8 PrivateKeyInfo 或 SEC1 ECPrivateKey)
     */
    private static BigInteger parsePrivateKey(String key) {
        String k = key.trim();
        if (k.length() == 64 && k.matches("(?i)[0-9a-f]{64}")) {
            return new BigInteger(k, 16);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(k);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SM2私钥格式不正确(应为64位hex或Base64编码的私钥)");
        }
        if (raw.length == 32) {
            return new BigInteger(1, raw);
        }
        String rawHex = Hex.toHexString(raw);
        if (rawHex.length() == 64 && rawHex.matches("(?i)[0-9a-f]{64}")) {
            return new BigInteger(rawHex, 16);
        }
        try {
            ASN1Primitive obj = ASN1Primitive.fromByteArray(raw);
            if (obj instanceof ASN1Sequence) {
                try {
                    PrivateKeyInfo pki = PrivateKeyInfo.getInstance(obj);
                    return ECPrivateKey.getInstance(pki.parsePrivateKey()).getKey();
                } catch (Exception ignored) {
                    return ECPrivateKey.getInstance(obj).getKey();
                }
            }
            return ECPrivateKey.getInstance(obj).getKey();
        } catch (Exception e) {
            throw new IllegalArgumentException("SM2私钥格式无法解析: " + e.getMessage());
        }
    }
}
