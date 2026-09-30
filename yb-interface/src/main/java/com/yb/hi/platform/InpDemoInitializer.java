package com.yb.hi.platform;

import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 住院模块演示初始化: 为每个租户幂等填充 住院科室 + 默认病区 + 床位 + 演示在院患者。
 * 在 DoctorDemoInitializer(@Order(4)) 之后执行(@Order(5)), 依赖链已就绪:
 * 表(DictSchemaMigration@0) / 租户+机构(DemoDataInitializer@1 + RbacInitializer@3) /
 * 科室与医生(BasedataDemoInitializer@2) / 演示患者(OutpatientDemoInitializer@3)。
 * 全程 JdbcTemplate 直写并显式落 tenant_id(不走 Mapper, 不受租户插件注入影响);
 * 关联数据未就绪时逐段判空跳过, 重启自动补种; 可通过 his.demo-data.enabled=false 关闭。
 */
@Slf4j
@Order(5)
@Component
public class InpDemoInitializer implements ApplicationRunner {

    /** 住院号日期段格式(与 InpVisitService.generateInpNo 同口径: INP+yyyyMMdd+4位序号) */
    private static final DateTimeFormatter INP_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SysTenantService tenantService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public InpDemoInitializer(SysTenantService tenantService, JdbcTemplate jdbcTemplate) {
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            for (SysTenant t : tenantService.listAll()) {
                if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                    continue; // 平台运营方租户无医院业务数据
                }
                try {
                    seedTenant(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 住院演示数据初始化跳过(可能表未建或关联数据未就绪): {}", t.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("住院演示初始化跳过(可能表未建): {}", e.getMessage());
        }
    }

    private void seedTenant(Long tenantId) {
        Long orgId = resolveOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RbacInitializer 未跑完), 下次启动补种
        }
        int done = 0;
        done += ensureInpatientDepts(tenantId, orgId);
        done += ensureWardsAndBeds(tenantId, orgId);
        done += seedDemoVisits(tenantId, orgId);
        if (done > 0) {
            log.info("租户[{}] 住院演示数据初始化完成(病区/床位/科室/在院患者共 {} 项新增)", tenantId, done);
        }
    }

    /** 解析租户默认机构: 优先牵头机构(is_lead=1), 兜底最小 id 机构 */
    private Long resolveOrgId(Long tenantId) {
        List<Map<String, Object>> lead = jdbcTemplate.queryForList(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 AND is_lead = 1 ORDER BY id LIMIT 1", tenantId);
        if (!lead.isEmpty()) {
            return ((Number) lead.get(0).get("id")).longValue();
        }
        List<Map<String, Object>> any = jdbcTemplate.queryForList(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1", tenantId);
        return any.isEmpty() ? null : ((Number) any.get(0).get("id")).longValue();
    }

    /**
     * 幂等补种住院科室(挂"住院科室"大类下, level=2): BasedataDemoInitializer 仅种门诊临床科室,
     * 住院科室大类下无具体科室, 在此补 ZY01 内科(住院)/ZY02 外科(住院)/ZY03 儿科(住院)。
     * 大类节点缺失(异常库)则先按五大类编码补建; 返回新增行数。
     */
    private int ensureInpatientDepts(Long tenantId, Long orgId) {
        Long catId = queryId("SELECT id FROM his_dept WHERE tenant_id = ? AND deleted = 0"
                + " AND dept_category = '住院科室' AND dept_level = 1 ORDER BY id LIMIT 1", tenantId);
        if (catId == null) {
            catId = insertDept(tenantId, orgId, "CAT-ZY", "住院科室", "行政", null, 0L, 1, 1);
        }
        int added = 0;
        // 科室编码, 科室名称, 医保科别(cv_code:caty, 与门诊同名科室对齐)
        String[][] depts = {
                {"ZY01", "内科(住院)", "A03"}, {"ZY02", "外科(住院)", "A04"}, {"ZY03", "儿科(住院)", "A07"}
        };
        int sort = 1;
        for (String[] d : depts) {
            Long exist = queryId("SELECT id FROM his_dept WHERE tenant_id = ? AND deleted = 0"
                    + " AND dept_code = ? ORDER BY id LIMIT 1", tenantId, d[0]);
            if (exist != null) {
                continue;
            }
            insertDept(tenantId, orgId, d[0], d[1], "临床", d[2], catId, 2, sort);
            added++;
            sort++;
        }
        return added;
    }

    /** 插入科室行, 返回自增 id */
    private Long insertDept(Long tenantId, Long orgId, String code, String name, String type,
                            String deptCaty, Long parentId, int level, int sortNo) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO his_dept (tenant_id, org_id, dept_code, dept_name, dept_type, dept_caty,"
                            + " parent_id, dept_category, dept_level, sort_no, status, create_by, create_time, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, '住院科室', ?, ?, 1, 'demo-seed', NOW(), 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, orgId);
            ps.setString(3, code);
            ps.setString(4, name);
            ps.setString(5, type);
            ps.setString(6, deptCaty);
            ps.setLong(7, parentId == null ? 0L : parentId);
            ps.setInt(8, level);
            ps.setInt(9, sortNo);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /**
     * 幂等补种默认三病区(各10床): 内科病区(101-110)/外科病区(201-210)/儿科病区(301-310),
     * 病区按唯一键 (tenant_id, org_id, ward_code) 判存, 床位按 (tenant_id, ward_id, bed_no) 判存。
     * 返回新增行数(病区+床位)。床位默认空床(status=0), 占用由演示在院患者与业务入院链路回写。
     */
    private int ensureWardsAndBeds(Long tenantId, Long orgId) {
        // 病区: 编码, 名称, 关联科室编码, 楼栋, 楼层, 床位日费用, 起始床号
        Object[][] wards = {
                {"IP-NK", "内科病区", "ZY01", "住院部1号楼", "1层", "50.00", 101},
                {"IP-WK", "外科病区", "ZY02", "住院部1号楼", "2层", "50.00", 201},
                {"IP-EK", "儿科病区", "ZY03", "住院部2号楼", "3层", "40.00", 301}
        };
        int added = 0;
        for (Object[] w : wards) {
            String wardCode = (String) w[0];
            Long wardId = queryId("SELECT id FROM his_ward WHERE tenant_id = ? AND org_id = ?"
                    + " AND ward_code = ? AND deleted = 0 LIMIT 1", tenantId, orgId, wardCode);
            Long deptId = queryId("SELECT id FROM his_dept WHERE tenant_id = ? AND deleted = 0"
                    + " AND dept_code = ? ORDER BY id LIMIT 1", tenantId, w[2]);
            if (wardId == null) {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbcTemplate.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO his_ward (tenant_id, org_id, ward_name, ward_code, dept_id,"
                                    + " building, floor, bed_count, status, create_by, create_time, deleted)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 10, 1, 'demo-seed', NOW(), 0)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, tenantId);
                    ps.setLong(2, orgId);
                    ps.setString(3, (String) w[1]);
                    ps.setString(4, wardCode);
                    if (deptId != null) {
                        ps.setLong(5, deptId);
                    } else {
                        ps.setNull(5, java.sql.Types.BIGINT);
                    }
                    ps.setString(6, (String) w[3]);
                    ps.setString(7, (String) w[4]);
                    return ps;
                }, kh);
                wardId = kh.getKey().longValue();
                added++;
            }
            // 床位: 起始床号~+9(各10张), 房间号=床号, 普通床
            int bedStart = ((Number) w[6]).intValue();
            for (int i = 0; i < 10; i++) {
                String bedNo = String.valueOf(bedStart + i);
                Long exist = queryId("SELECT id FROM his_bed WHERE tenant_id = ? AND ward_id = ?"
                        + " AND bed_no = ? AND deleted = 0 LIMIT 1", tenantId, wardId, bedNo);
                if (exist != null) {
                    continue;
                }
                jdbcTemplate.update(
                        "INSERT INTO his_bed (tenant_id, org_id, bed_no, ward_id, room_no, bed_type,"
                                + " status, daily_price, create_by, create_time, deleted)"
                                + " VALUES (?, ?, ?, ?, ?, 1, 0, ?, 'demo-seed', NOW(), 0)",
                        tenantId, orgId, bedNo, wardId, bedNo, new BigDecimal((String) w[5]));
                added++;
            }
        }
        return added;
    }

    /**
     * 幂等补种演示在院患者(3例): 复用门诊演示患者与医生(BasedataDemoInitializer/OutpatientDemoInitializer 种子),
     * 创建在院就诊(visit_status=2) + 乐观占用床位 + 入院诊断(diagType=1 主诊断), 与 InpVisitService.admit
     * 业务链路同口径。按 患者已有未结案住院(visit_status IN 1,2,3) 判存, 患者未建档时跳过该例(重启补种)。
     * 住院号用 INP+入院日+9001 段(避开业务 0001 起递增段, 防同日唯一键冲突)。
     */
    private int seedDemoVisits(Long tenantId, Long orgId) {
        // 患者, 主治工号, 病区编码, 床号, 入院诊断, 诊断编码, 入院天数前移
        Object[][] cases = {
                {"张三", "D001", "IP-NK", "101", "肺炎", "J18.900", 2},
                {"李四", "D002", "IP-WK", "201", "急性阑尾炎", "K35.900", 1},
                {"赵小儿", "D003", "IP-EK", "301", "急性上呼吸道感染", "J06.900", 3}
        };
        int added = 0;
        for (Object[] c : cases) {
            Long patientId = queryId("SELECT id FROM his_patient WHERE tenant_id = ? AND deleted = 0"
                    + " AND name = ? ORDER BY id LIMIT 1", tenantId, c[0]);
            if (patientId == null) {
                continue; // 演示患者未建档, 下次启动补种
            }
            Long active = queryId("SELECT id FROM his_inp_visit WHERE tenant_id = ? AND deleted = 0"
                    + " AND patient_id = ? AND visit_status IN (1,2,3) LIMIT 1", tenantId, patientId);
            if (active != null) {
                continue; // 已有未结案住院(含演示), 不重复入院
            }
            Long doctorId = queryId("SELECT id FROM his_staff WHERE tenant_id = ? AND deleted = 0"
                    + " AND staff_no = ? ORDER BY id LIMIT 1", tenantId, c[1]);
            Long wardId = queryId("SELECT id FROM his_ward WHERE tenant_id = ? AND org_id = ?"
                    + " AND ward_code = ? AND deleted = 0 LIMIT 1", tenantId, orgId, c[2]);
            Long bedId = wardId == null ? null : queryId(
                    "SELECT id FROM his_bed WHERE tenant_id = ? AND ward_id = ? AND bed_no = ?"
                            + " AND deleted = 0 LIMIT 1", tenantId, wardId, c[3]);
            if (wardId == null || bedId == null) {
                continue; // 病区/床位未就绪, 下次启动补种
            }
            Long deptId = queryId("SELECT dept_id AS id FROM his_ward WHERE id = ?", wardId);
            LocalDateTime admitDate = LocalDateTime.now().minusDays(((Number) c[6]).intValue())
                    .withHour(9).withMinute(30).withSecond(0).withNano(0);
            String inpNo = "INP" + admitDate.format(INP_NO_DATE) + String.format("%04d", 9001 + added);

            // 1) 在院就诊主记录(visit_status=2 在院, 与 admit 一致)
            KeyHolder kh = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO his_inp_visit (tenant_id, org_id, inp_no, patient_id, ward_id, bed_id,"
                                + " dept_id, doctor_id, admit_date, visit_status, admit_diag, total_cost,"
                                + " deposit_balance, create_by, create_time, deleted)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 2, ?, 0, 0, 'demo-seed', NOW(), 0)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, tenantId);
                ps.setLong(2, orgId);
                ps.setString(3, inpNo);
                ps.setLong(4, patientId);
                ps.setLong(5, wardId);
                ps.setLong(6, bedId);
                if (deptId != null) {
                    ps.setLong(7, deptId);
                } else {
                    ps.setNull(7, java.sql.Types.BIGINT);
                }
                if (doctorId != null) {
                    ps.setLong(8, doctorId);
                } else {
                    ps.setNull(8, java.sql.Types.BIGINT);
                }
                ps.setTimestamp(9, java.sql.Timestamp.valueOf(admitDate));
                ps.setString(10, (String) c[4]);
                return ps;
            }, kh);
            Long visitId = kh.getKey().longValue();

            // 2) 入院诊断(diagType=1 入院诊断, 主诊断, 与 admit 一致)
            jdbcTemplate.update(
                    "INSERT INTO his_inp_diagnosis (tenant_id, org_id, inp_visit_id, diag_type, diag_code,"
                            + " diag_name, is_main, diag_dept_id, diag_doctor_id, diag_time, sort_no,"
                            + " create_by, create_time, deleted)"
                            + " VALUES (?, ?, ?, 1, ?, ?, 1, ?, ?, ?, 1, 'demo-seed', NOW(), 0)",
                    tenantId, orgId, visitId, c[5], c[4],
                    deptId, doctorId, admitDate);

            // 3) 乐观占用床位(status 0→1; 被并发占走则回滚本例就诊记录, 下次启动重试)
            int bedTaken = jdbcTemplate.update(
                    "UPDATE his_bed SET status = 1, patient_id = ?, inp_visit_id = ?,"
                            + " update_by = 'demo-seed', update_time = NOW()"
                            + " WHERE id = ? AND status = 0 AND deleted = 0",
                    patientId, visitId, bedId);
            if (bedTaken == 0) {
                jdbcTemplate.update("DELETE FROM his_inp_visit WHERE id = ?", visitId);
                jdbcTemplate.update("DELETE FROM his_inp_diagnosis WHERE inp_visit_id = ?", visitId);
                log.warn("租户[{}] 演示床位 {} 已被占用, 患者演示在院记录回退跳过", tenantId, c[3]);
                continue;
            }
            log.info("租户[{}] 演示在院患者已创建: {} 住院号={} 床位={}", tenantId, c[0], inpNo, c[3]);
            added++;
        }
        return added;
    }

    /** 单值 id 查询(无命中返回 null) */
    private Long queryId(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty() || rows.get(0).get("id") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }
}
