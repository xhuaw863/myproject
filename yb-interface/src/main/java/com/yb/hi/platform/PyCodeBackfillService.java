package com.yb.hi.platform;

import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.stddict.StdDict;
import com.yb.hi.stddict.StdDictRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字典简码回填(@Order(7), 在演示数据/DictDataBackfill 之后执行, 幂等)。
 * 为配置清单内每张表的 py_code 空值行按名称列生成拼音首字母(只补空不覆盖), 分批游标处理,
 * 单表扫完即止; 表不存在或缺 py_code/名称列时跳过该表。std_* 重导(TRUNCATE 重写)后下次启动自动再补。
 */
@Slf4j
@Order(7)
@Component
public class PyCodeBackfillService implements ApplicationRunner {

    private static final int BATCH = 1000;

    private final DataSource dataSource;

    @Value("${his.dict-migration.enabled:true}")
    private boolean enabled;

    public PyCodeBackfillService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        List<String[]> jobs = buildJobs();
        long total = 0;
        try (Connection conn = dataSource.getConnection()) {
            for (String[] job : jobs) {
                total += backfillTable(conn, job[0], job[1], job[2]);
            }
        } catch (Exception e) {
            log.warn("字典简码回填跳过: {}", e.getMessage());
            return;
        }
        if (total > 0) {
            log.info("字典简码(py_code)回填完成, 共 {} 行", total);
        }
    }

    /** 供 StdDictImportService 重导后立即对单表补码(避免等到下次重启才生效)。 */
    public void backfillOne(String table, String pkCol, String nameCol) {
        try (Connection conn = dataSource.getConnection()) {
            backfillTable(conn, table, pkCol, nameCol);
        } catch (Exception e) {
            log.warn("字典简码回填表[{}]跳过: {}", table, e.getMessage());
        }
    }

    /** 待补表清单: 院内 6 表 + 机构/患者/区划/医保目录 3 表 + 16 张 std_*(去重, drug_catalog 用源 pinyin 跳过)。 */
    private List<String[]> buildJobs() {
        List<String[]> jobs = new ArrayList<>();
        jobs.add(new String[]{"his_staff", "id", "staff_name"});
        jobs.add(new String[]{"his_dept", "id", "dept_name"});
        jobs.add(new String[]{"his_drug_catalog", "id", "generic_name"});
        jobs.add(new String[]{"his_charge_item", "id", "item_name"});
        jobs.add(new String[]{"his_cons_catalog", "id", "name"});
        jobs.add(new String[]{"his_med_dict", "id", "name"});
        jobs.add(new String[]{"sys_org", "id", "org_name"});
        jobs.add(new String[]{"his_patient", "id", "name"});
        jobs.add(new String[]{"area_code_2021", "code", "name"});
        jobs.add(new String[]{"med_service_catalog", "id", "item_name"});
        jobs.add(new String[]{"consumable_catalog", "id", "cons_name"});
        jobs.add(new String[]{"disease_catalog", "id", "diag_name"});
        Set<String> seen = new LinkedHashSet<>();
        for (StdDict d : StdDictRegistry.build().values()) {
            String table = d.getTable();
            String nameCol = d.getNameCol();
            if (!StringUtils.hasText(table) || !StringUtils.hasText(nameCol)) {
                continue;
            }
            if ("drug_catalog".equals(table) || !seen.add(table + "|" + nameCol)) {
                continue;
            }
            jobs.add(new String[]{table, "id", nameCol});
        }
        return jobs;
    }

    /** 单表 keyset 回填: 按主键升序单遍扫描补码(避免反复全表重扫空值行, 大表 O(N) 秒级完成)。返回补码行数。 */
    private long backfillTable(Connection conn, String table, String pkCol, String nameCol) {
        if (!hasTableColumn(conn, table, "py_code") || !hasTableColumn(conn, table, nameCol)) {
            return 0;
        }
        String sel = "SELECT " + pkCol + " AS pk, " + nameCol + " AS nm FROM " + table
                + " WHERE " + pkCol + " > ? AND (py_code IS NULL OR py_code='') AND " + nameCol + " IS NOT NULL AND " + nameCol + " <> ''"
                + " ORDER BY " + pkCol + " LIMIT " + BATCH;
        String upd = "UPDATE " + table + " SET py_code=? WHERE " + pkCol + "=?";
        long filled = 0;
        try {
            // keyset 起点: 整型主键用 0, 字符串主键(如 area_code_2021.code)用空串
            Object lastPk = isIntColumn(conn, table, pkCol) ? (Object) 0L : "";
            while (true) {
                List<Object[]> rows = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(sel)) {
                    ps.setObject(1, lastPk);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String py = PinyinUtil.initials(rs.getString("nm"));
                            if (py == null) {
                                // 名称无可提取字符(纯符号), 置空串占位避免重扫
                                py = "";
                            }
                            Object pk = rs.getObject("pk");
                            rows.add(new Object[]{py, pk});
                            lastPk = pk;
                        }
                    }
                }
                if (rows.isEmpty()) {
                    break;
                }
                // 每批一提交: 避免逐行 autocommit fsync 拖慢大表回填
                boolean ac = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(upd)) {
                    for (Object[] r : rows) {
                        ps.setString(1, (String) r[0]);
                        ps.setObject(2, r[1]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(ac);
                }
                filled += rows.size();
            }
        } catch (Exception e) {
            log.warn("字典简码回填表[{}]中断: {}", table, e.getMessage());
        }
        return filled;
    }

    /** 主键是否整型(决定 keyset 起点比较值类型, 避免 varchar 列与 0 隐式转换全表误匹配)。 */
    private boolean isIntColumn(Connection conn, String table, String column) {
        String sql = "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dt = rs.getString(1);
                    return dt != null && (dt.contains("int") || dt.contains("decimal") || dt.contains("numeric"));
                }
            }
        } catch (Exception e) {
            // 探测失败按整型处理(行为等同旧版, 不阻断启动)
        }
        return true;
    }

    private boolean hasTableColumn(Connection conn, String table, String column) {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
