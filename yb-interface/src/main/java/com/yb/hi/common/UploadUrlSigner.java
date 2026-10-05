package com.yb.hi.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 受保护上传文件的 URL 签名器: 敏感文件静态访问地址追加签名 token,
 * 由 UploadAccessFilter 校验, 避免企业资质、签名图片和归档 PDF 匿名直读。
 */
@Component
public class UploadUrlSigner {

    @Value("${his.upload.url-secret:yb-upload-default-secret-2026}")
    private String secret;

    /** 为 URL 追加签名 token(签名对象为去除 query 的路径)。 */
    public String appendToken(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String token = sign(pathOnly(url));
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + "token=" + token;
    }

    /** 校验请求路径与 token 是否匹配(常数时间比较)。 */
    public boolean verify(String uri, String token) {
        if (uri == null || token == null || token.isEmpty()) {
            return false;
        }
        String expected = sign(pathOnly(uri));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** 去除 URL 中的 query 与 fragment, 仅保留路径。 */
    public String pathOnly(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int f = s.indexOf('#');
        if (f >= 0) {
            s = s.substring(0, f);
        }
        return s;
    }

    private String sign(String path) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(path.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("上传 URL 签名失败", e);
        }
    }
}
