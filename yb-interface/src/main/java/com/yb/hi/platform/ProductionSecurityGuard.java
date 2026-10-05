package com.yb.hi.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 生产安全启动闸: 非开发/测试 profile 下拒绝使用演示口令、空 SM2 私钥或弱 JWT 密钥启动。
 *
 * <p>开发 profile(dev/local)与测试 profile(test/it)允许演示配置; 其余 profile(含显式 prod
 * 或未指定 profile 时经 spring.profiles.default 落入 dev 除外)必须显式注入生产凭据。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionSecurityGuard implements ApplicationRunner {

    private static final Set<String> DEV_LIKE_PROFILES = new HashSet<>(Arrays.asList(
            "dev", "local", "test", "it"
    ));

    private final Environment environment;

    @Value("${yb.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${yb.sm2-private-key:}")
    private String sm2PrivateKey;

    @Value("${his.jwt.secret:}")
    private String jwtSecret;

    public ProductionSecurityGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> profiles = new HashSet<>();
        profiles.addAll(Arrays.asList(environment.getActiveProfiles()));
        profiles.addAll(Arrays.asList(environment.getDefaultProfiles()));
        if (profiles.stream().anyMatch(DEV_LIKE_PROFILES::contains)) {
            return;
        }

        String bootstrap = System.getenv(BootstrapPassword.ENV_NAME);
        if (!StringUtils.hasText(bootstrap)) {
            throw new IllegalStateException("生产 profile 必须设置 HIS_BOOTSTRAP_PASSWORD, 禁止使用内置演示口令启动");
        }
        if (!StringUtils.hasText(jwtSecret) || jwtSecret.length() < 32) {
            throw new IllegalStateException("生产 profile 必须设置长度不少于 32 字符的 HIS_JWT_SECRET");
        }
        if (!StringUtils.hasText(System.getenv("HIS_UPLOAD_URL_SECRET"))) {
            throw new IllegalStateException("生产 profile 必须设置 HIS_UPLOAD_URL_SECRET, 用于敏感上传文件 URL 签名");
        }
        if (!mockEnabled && !StringUtils.hasText(sm2PrivateKey)) {
            throw new IllegalStateException("生产 profile 关闭 mock 时必须设置 YB_SM2_PRIVATE_KEY, 不允许 SM3 摘要降级签名");
        }
    }
}
