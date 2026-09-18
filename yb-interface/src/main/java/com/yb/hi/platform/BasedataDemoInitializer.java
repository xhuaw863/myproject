package com.yb.hi.platform;

import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisSchedule;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import com.yb.hi.service.basedata.HisChargeItemService;
import com.yb.hi.service.basedata.HisDeptService;
import com.yb.hi.service.basedata.HisScheduleService;
import com.yb.hi.service.basedata.HisStaffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 基础数据演示初始化: 为每个租户幂等填充 科室/职工/排班/收费项目 演示数据。
 * 在 DemoDataInitializer(@Order(1)) 之后执行。可通过 his.demo-data.enabled=false 关闭。
 */
@Slf4j
@Order(2)
@Component
public class BasedataDemoInitializer implements ApplicationRunner {

    private final SysTenantService tenantService;
    private final HisDeptService deptService;
    private final HisStaffService staffService;
    private final HisScheduleService scheduleService;
    private final HisChargeItemService chargeItemService;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public BasedataDemoInitializer(SysTenantService tenantService, HisDeptService deptService,
                                   HisStaffService staffService, HisScheduleService scheduleService,
                                   HisChargeItemService chargeItemService) {
        this.tenantService = tenantService;
        this.deptService = deptService;
        this.staffService = staffService;
        this.scheduleService = scheduleService;
        this.chargeItemService = chargeItemService;
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
            log.warn("基础数据演示初始化跳过(可能表未建): {}", e.getMessage());
        }
    }

    private void seedTenant(Long tenantId) {
        Long prev = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            if (deptService.count() > 0) {
                return; // 已有基础数据, 跳过
            }
            seedDepts();
            seedStaffAndSchedule();
            seedChargeItems();
            log.info("租户[{}] 基础数据演示初始化完成", tenantId);
        } finally {
            if (prev != null) {
                TenantContext.set(prev);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void seedDepts() {
        Object[][] depts = {
                {"01", "内科", "普通"}, {"02", "外科", "普通"}, {"03", "儿科", "普通"},
                {"04", "妇产科", "普通"}, {"05", "急诊科", "急诊"}, {"06", "中医科", "中医"}
        };
        int sort = 1;
        for (Object[] d : depts) {
            HisDept e = new HisDept();
            e.setDeptCode((String) d[0]);
            e.setDeptName((String) d[1]);
            e.setDeptType("临床");
            e.setDeptCaty((String) d[2]);
            e.setSortNo(sort++);
            e.setStatus(1);
            deptService.save(e);
        }
    }

    private void seedStaffAndSchedule() {
        List<HisDept> depts = deptService.listAll();
        // 医师: 工号, 姓名, 科室编码, 职称, 号别名称, 挂号费
        Object[][] doctors = {
                {"D001", "张医生", "01", "主治医师", "普通号", "10.00"},
                {"D002", "李医生", "02", "副主任医师", "副主任号", "20.00"},
                {"D003", "王医生", "03", "主治医师", "普通号", "10.00"},
                {"D004", "赵医生", "04", "主任医师", "主任号", "30.00"},
                {"D005", "刘医生", "05", "主治医师", "普通号", "15.00"},
                {"D006", "陈医生", "06", "副主任医师", "副主任号", "20.00"}
        };
        List<HisSchedule> schedules = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Object[] d : doctors) {
            HisDept dept = findDept(depts, (String) d[2]);
            HisStaff s = new HisStaff();
            s.setStaffNo((String) d[0]);
            s.setStaffName((String) d[1]);
            s.setStaffType("医师");
            s.setTitleName((String) d[3]);
            s.setDeptId(dept == null ? null : dept.getId());
            s.setAtddrNo((String) d[0]);
            s.setDiseDorNo((String) d[0]);
            s.setCanRegister(1);
            s.setRegFee(new BigDecimal((String) d[5]));
            s.setStatus(1);
            staffService.save(s);
            // 排班: 今起3天, 上午/下午
            for (int day = 0; day < 3; day++) {
                for (String tt : new String[]{"am", "pm"}) {
                    HisSchedule sc = new HisSchedule();
                    sc.setDeptId(s.getDeptId());
                    sc.setStaffId(s.getId());
                    sc.setWorkDate(today.plusDays(day));
                    sc.setTimeType(tt);
                    sc.setRegLevelCode("01");
                    sc.setRegLevelName((String) d[4]);
                    sc.setRegFee(new BigDecimal((String) d[5]));
                    sc.setTotalNum(30);
                    sc.setLeftNum(30);
                    sc.setStatus(1);
                    schedules.add(sc);
                }
            }
        }
        scheduleService.saveBatch(schedules);
    }

    private void seedChargeItems() {
        // 编码, 名称, 大类, 规格, 单位, 单价, 医保目录编码, 收费类别, 等级, 自付比例
        Object[][] items = {
                {"YP0001", "阿莫西林胶囊", "药品", "0.25g*24粒", "盒", "12.50", "XJ01CAA04501010113", "01", "01", "0.00"},
                {"YP0002", "布洛芬缓释胶囊", "药品", "0.3g*20粒", "盒", "18.00", "XM01AE07101010113", "01", "02", "0.05"},
                {"YP0003", "氯化钠注射液", "药品", "0.9% 250ml", "袋", "4.50", "XJ03BAA0601010113", "01", "01", "0.00"},
                {"ZL0001", "普通门诊诊查费", "诊疗", "", "次", "10.00", "01010101000000001", "02", "01", "0.00"},
                {"ZL0002", "血常规", "诊疗", "", "次", "25.00", "02010101000000001", "02", "01", "0.00"},
                {"ZL0003", "静脉输液", "诊疗", "", "次", "15.00", "03030303000000001", "02", "02", "0.10"},
                {"HC0001", "一次性使用注射器", "耗材", "5ml", "支", "1.20", "C0207011020000001", "03", "01", "0.00"}
        };
        for (Object[] it : items) {
            HisChargeItem e = new HisChargeItem();
            e.setItemCode((String) it[0]);
            e.setItemName((String) it[1]);
            e.setItemType((String) it[2]);
            e.setSpec((String) it[3]);
            e.setUnit((String) it[4]);
            e.setPrice(new BigDecimal((String) it[5]));
            e.setMedListCodg((String) it[6]);
            e.setMedChrgitmType((String) it[7]);
            e.setChrgitmLv((String) it[8]);
            e.setSelfpayProp(new BigDecimal((String) it[9]));
            e.setStatus(1);
            chargeItemService.save(e);
        }
    }

    private HisDept findDept(List<HisDept> depts, String code) {
        for (HisDept d : depts) {
            if (code.equals(d.getDeptCode())) {
                return d;
            }
        }
        return null;
    }
}
