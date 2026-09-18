package com.yb.hi.platform;

import com.yb.hi.platform.dto.TenantRegisterReq;
import com.yb.hi.platform.service.AuthService;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 演示数据初始化: 应用启动时幂等创建演示医院(租户)与管理员账号
 * 用于多租户隔离验证与开发调试。可通过 his.demo-data.enabled=false 关闭。
 */
@Slf4j
@Order(1)
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private final SysTenantService tenantService;
    private final AuthService authService;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public DemoDataInitializer(SysTenantService tenantService, AuthService authService) {
        this.tenantService = tenantService;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            seedTenant("H42010000000", "测试医院", "H42010000000", "420100", "admin", "admin123", "张管理");
            seedTenant("DEMO_B", "演示医院B", "H42020000000", "420200", "admin", "admin123", "李管理");
        } catch (Exception e) {
            log.warn("演示数据初始化跳过(可能表未建或已存在): {}", e.getMessage());
        }
    }

    private void seedTenant(String code, String name, String fixmedinsCode, String admvs,
                            String adminUser, String adminPwd, String adminName) {
        if (tenantService.codeExists(code)) {
            return;
        }
        TenantRegisterReq req = new TenantRegisterReq();
        req.setTenantCode(code);
        req.setTenantName(name);
        req.setFixmedinsCode(fixmedinsCode);
        req.setFixmedinsName(name);
        req.setMdtrtareaAdmvs(admvs);
        req.setInsuplcAdmdvs(admvs);
        req.setMockEnabled(1);
        req.setContact(adminName);
        req.setAdminUsername(adminUser);
        req.setAdminPassword(adminPwd);
        req.setAdminName(adminName);
        authService.register(req);
        log.info("演示医院已创建: {} ({}), 管理员 {}/{}", name, code, adminUser, adminPwd);
    }
}
