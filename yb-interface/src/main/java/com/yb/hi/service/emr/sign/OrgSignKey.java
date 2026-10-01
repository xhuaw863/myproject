package com.yb.hi.service.emr.sign;

/**
 * 机构签名密钥载体(Phase D 可靠电子签名): 承载某机构的 SM2 公私钥与证书编号, 供 {@link SignProvider} 加签/验签。
 * privHex 仅在有私钥的场景(签名)填充; 验签可只用 pubHex。不可序列化为 null 字符串。
 */
public class OrgSignKey {

    /** SM2 私钥(hex, 可空: 验签场景不需要) */
    private final String privHex;
    /** SM2 公钥(hex, 可空: 若两者皆空则签名时由 provider 生成) */
    private final String pubHex;
    /** 证书/签章编号(sys_org.sign_no, 占位) */
    private final String certSn;

    public OrgSignKey(String privHex, String pubHex, String certSn) {
        this.privHex = privHex;
        this.pubHex = pubHex;
        this.certSn = certSn;
    }

    public String getPrivHex() {
        return privHex;
    }

    public String getPubHex() {
        return pubHex;
    }

    public String getCertSn() {
        return certSn;
    }

    /** 是否已具备可用的公私钥对 */
    public boolean isComplete() {
        return privHex != null && !privHex.trim().isEmpty()
                && pubHex != null && !pubHex.trim().isEmpty();
    }

    /** 私钥对外的脱敏展示(不落日志/不回传前端): 仅保留首尾各4位 */
    public String maskedPriv() {
        if (privHex == null || privHex.length() <= 8) {
            return "***";
        }
        return privHex.substring(0, 4) + "..." + privHex.substring(privHex.length() - 4);
    }
}
