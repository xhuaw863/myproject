package com.yb.hi.platform;

import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import com.yb.hi.service.outpatient.HisPatientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 门诊演示初始化: 为每个租户幂等填充 演示患者档案。
 * 在 BasedataDemoInitializer(@Order(2)) 之后执行。可通过 his.demo-data.enabled=false 关闭。
 */
@Slf4j
@Order(3)
@Component
public class OutpatientDemoInitializer implements ApplicationRunner {

    private final SysTenantService tenantService;
    private final HisPatientService patientService;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public OutpatientDemoInitializer(SysTenantService tenantService, HisPatientService patientService) {
        this.tenantService = tenantService;
        this.patientService = patientService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            List<SysTenant> tenants = tenantService.listAll();
            for (SysTenant t : tenants) {
                seedTenant(t.getId());
            }
        } catch (Exception e) {
            log.warn("门诊演示初始化跳过(可能表未建): {}", e.getMessage());
        }
    }

    private void seedTenant(Long tenantId) {
        Long prev = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            if (patientService.count() > 0) {
                return; // 已有患者, 跳过
            }
            // 姓名, 性别, 出生日期, 身份证, 电话, 险种, 医保人员编号, 参保地
            Object[][] ps = {
                    {"张三", "男", "1985-03-12", "420106198503121234", "13800000001", "310", "PSN420100001", "420100"},
                    {"李四", "女", "1990-07-22", "420106199007222345", "13800000002", "390", "PSN420100002", "420100"},
                    {"王五", "男", "1978-11-05", "420106197811053456", "13800000003", "310", "PSN420100003", "420100"},
                    {"赵小儿", "女", "2016-01-20", "420106201601204567", "13800000004", "390", "PSN420100004", "420100"},
                    {"测试患者", "男", "2000-01-01", "420106200001015678", "13800000005", "310", "PSN420100005", "420100"}
            };
            for (Object[] p : ps) {
                HisPatient e = new HisPatient();
                e.setName((String) p[0]);
                e.setGender((String) p[1]);
                e.setBirthDate(LocalDate.parse((String) p[2]));
                e.setAge(calcAge((String) p[2]));
                e.setIdCard((String) p[3]);
                e.setPhone((String) p[4]);
                e.setInsutype((String) p[5]);
                e.setPsnNo((String) p[6]);
                e.setInsuplcAdmdvs((String) p[7]);
                e.setMdtrtCertType("02");
                e.setMdtrtCertNo((String) p[3]);
                e.setStatus(1);
                patientService.createPatient(e);
            }
            log.info("租户[{}] 门诊演示患者初始化完成({}人)", tenantId, ps.length);
        } finally {
            if (prev != null) {
                TenantContext.set(prev);
            } else {
                TenantContext.clear();
            }
        }
    }

    private int calcAge(String birth) {
        LocalDate b = LocalDate.parse(birth);
        LocalDate now = LocalDate.now();
        int age = now.getYear() - b.getYear();
        if (now.getMonthValue() < b.getMonthValue()
                || (now.getMonthValue() == b.getMonthValue() && now.getDayOfMonth() < b.getDayOfMonth())) {
            age--;
        }
        return Math.max(age, 0);
    }
}
