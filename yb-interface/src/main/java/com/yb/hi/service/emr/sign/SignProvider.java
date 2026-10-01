package com.yb.hi.service.emr.sign;

/**
 * 电子签名提供者 SPI(Phase D): 抽象"对一段待签数据做数字签名/验签"的能力, 便于对接第三方 CA/TSA。
 * 内置实现为 {@link Sm2SignProvider}(国密 SM2, hutool+BC); 未来可扩展 UKey/云 CA/PKCS#7 富签名而不动业务层。
 *
 * 约定: 入参 data 为"规范化摘要字节"(业务层对 content+structure 做 SM3 后的字节), 本接口不重复做摘要;
 * 返回值 sigHex 为签名的十六进制编码, 可与 data、pubHex 一同供验签。
 */
public interface SignProvider {

    /** 提供者标识: sm2(内置国密)/ca/tsa, 落入 his_emr_signature.provider */
    String providerId();

    /**
     * 对 data 加签。
     *
     * @param data 待签字节(规范化摘要)
     * @param key  机构密钥(必须含私钥)
     * @return 签名值(hex)
     */
    String sign(byte[] data, OrgSignKey key);

    /**
     * 验签。
     *
     * @param data   待验字节(业务层重算的规范化摘要)
     * @param sigHex 原签名值(hex)
     * @param key    机构密钥(至少含公钥)
     * @return 签名是否有效(数据被篡改则返回 false)
     */
    boolean verify(byte[] data, String sigHex, OrgSignKey key);

    /**
     * 为机构生成一对全新的 SM2 密钥(仅内置国密实现支持), 供 sys_org 缺密钥时懒生成回写。
     * 第三方 CA 实现应抛不支持异常(证书须由 CA 签发)。
     *
     * @return 含 privHex/pubHex 的新密钥(certSn 由调用方按机构补)
     */
    OrgSignKey generateKey();
}
