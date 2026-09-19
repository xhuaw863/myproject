package com.yb.hi.platform;

import com.yb.hi.entity.dict.DiseaseCatalog;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.dict.DiseaseCatalogMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 医生站演示初始化: 为每个租户幂等填充常见疾病与诊断目录(供接诊工作台诊断检索)。
 * 在 OutpatientDemoInitializer(@Order(3)) 之后执行。可通过 his.demo-data.enabled=false 关闭。
 */
@Slf4j
@Order(4)
@Component
public class DoctorDemoInitializer implements ApplicationRunner {

    private final SysTenantService tenantService;
    private final DiseaseCatalogMapper diseaseCatalogMapper;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public DoctorDemoInitializer(SysTenantService tenantService, DiseaseCatalogMapper diseaseCatalogMapper) {
        this.tenantService = tenantService;
        this.diseaseCatalogMapper = diseaseCatalogMapper;
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
            log.warn("医生站演示初始化跳过(可能表未建): {}", e.getMessage());
        }
    }

    private void seedTenant(Long tenantId) {
        Long prev = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            if (diseaseCatalogMapper.selectCount(null) > 0) {
                return; // 已有疾病目录, 跳过
            }
            // 诊断代码, 诊断名称, 类目名称, 章名称
            String[][] ds = {
                    {"J06.900", "急性上呼吸道感染", "上呼吸道疾病", "呼吸系统疾病"},
                    {"J18.900", "肺炎", "下呼吸道疾病", "呼吸系统疾病"},
                    {"J45.900", "支气管哮喘", "慢性下呼吸道疾病", "呼吸系统疾病"},
                    {"J30.400", "过敏性鼻炎", "鼻和鼻窦疾病", "呼吸系统疾病"},
                    {"I10.x00", "高血压病", "高血压", "循环系统疾病"},
                    {"E11.900", "2型糖尿病", "糖尿病", "内分泌营养代谢疾病"},
                    {"K29.700", "胃炎", "胃炎和十二指肠炎", "消化系统疾病"},
                    {"K35.900", "急性阑尾炎", "阑尾疾病", "消化系统疾病"},
                    {"N39.000", "尿路感染", "泌尿系统其他疾病", "泌尿生殖系统疾病"},
                    {"M54.500", "腰痛", "背部疾患", "肌肉骨骼系统疾病"},
                    {"R51.x00", "头痛", "其他症状和体征", "症状体征异常"},
                    {"H10.900", "结膜炎", "结膜疾患", "眼和附器疾病"}
            };
            int srt = 1;
            for (String[] d : ds) {
                DiseaseCatalog e = new DiseaseCatalog();
                e.setDiseCode(String.valueOf(1000 + srt));
                e.setDiagCode(d[0]);
                e.setDiagName(d[1]);
                e.setCatName(d[2]);
                e.setChapterName(d[3]);
                e.setUseFlag("1");
                e.setValiFlag("1");
                e.setVer("Hubei-2023");
                e.setVerName("湖北省医保疾病目录");
                e.setRid("DEMO" + String.format("%04d", srt));
                diseaseCatalogMapper.insert(e);
                srt++;
            }
            log.info("租户[{}] 疾病诊断目录演示数据初始化完成({}条)", tenantId, ds.length);
        } finally {
            if (prev != null) {
                TenantContext.set(prev);
            } else {
                TenantContext.clear();
            }
        }
    }
}
