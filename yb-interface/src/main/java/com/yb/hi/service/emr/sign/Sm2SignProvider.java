package com.yb.hi.service.emr.sign;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.asymmetric.SM2;
import com.yb.hi.framework.common.BizException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 内置国密 SM2 签名提供者(Phase D): 基于 hutool-crypto(BC) 的 {@link SM2}, 对业务层规范化摘要加签/验签。
 * 无状态、线程安全(每次按密钥新建 SM2 实例); 密钥来自 {@link OrgSignKey}, 本类不负责密钥存储。
 */
@Component
public class Sm2SignProvider implements SignProvider {

    @Override
    public String providerId() {
        return "sm2";
    }

    @Override
    public String sign(byte[] data, OrgSignKey key) {
        if (key == null || key.getPrivHex() == null || key.getPrivHex().trim().isEmpty()) {
            throw new BizException(400, "签名缺少 SM2 私钥");
        }
        if (data == null) {
            throw new BizException(400, "待签数据为空");
        }
        try {
            SM2 sm2 = new SM2(key.getPrivHex().trim(), key.getPubHex() == null ? null : key.getPubHex().trim());
            byte[] sig = sm2.sign(data);
            return HexUtil.encodeHexStr(sig);
        } catch (Exception e) {
            throw new BizException("SM2 加签失败: " + e.getMessage());
        }
    }

    @Override
    public boolean verify(byte[] data, String sigHex, OrgSignKey key) {
        if (key == null || key.getPubHex() == null || key.getPubHex().trim().isEmpty()) {
            throw new BizException(400, "验签缺少 SM2 公钥");
        }
        if (data == null || sigHex == null || sigHex.trim().isEmpty()) {
            return false;
        }
        try {
            SM2 sm2 = new SM2(null, key.getPubHex().trim());
            byte[] sig = HexUtil.decodeHex(sigHex.trim());
            return sm2.verify(data, sig);
        } catch (Exception e) {
            // 签名值/公钥格式异常均按验签失败处理(可对抗篡改), 不外抛栈
            return false;
        }
    }

    @Override
    public OrgSignKey generateKey() {
        try {
            SM2 sm2 = new SM2().initKeys();
            String privHex = sm2.getDHex();
            String pubHex = HexUtil.encodeHexStr(sm2.getQ(false));
            return new OrgSignKey(privHex, pubHex, null);
        } catch (Exception e) {
            throw new BizException("SM2 密钥生成失败: " + e.getMessage());
        }
    }

    /** 便捷: 把摘要 hex 串按 UTF-8 转字节(供 sign/verify 入参统一口径) */
    public static byte[] digestBytes(String digestHex) {
        return digestHex == null ? new byte[0] : digestHex.getBytes(StandardCharsets.UTF_8);
    }
}
