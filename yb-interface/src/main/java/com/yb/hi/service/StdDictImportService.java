package com.yb.hi.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.yb.hi.config.StdDictProperties;
import com.yb.hi.stddict.StdDict;
import com.yb.hi.stddict.StdDictRegistry;
import com.yb.hi.stddict.StdSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准字典导入服务: 用 EasyExcel 流式读取 xlsx(低内存, 支持数十万行), JDBC 批量写入 std_* 表。
 * 特点:
 *  - 全局标准数据, 走原生 JDBC(不经 MyBatis-Plus), 天然不受多租户插件影响;
 *  - 幂等: 每类字典导入前先 TRUNCATE 目标表, 再全量写入, 并登记 std_dict_version;
 *  - 单类/单源失败不影响其他字典, 结果逐条返回。
 */
@Slf4j
@Service
public class StdDictImportService {

    private final StdDictProperties props;
    private final DataSource dataSource;
    private final com.yb.hi.platform.PyCodeBackfillService pyBackfill;
    private final Map<String, StdDict> registry = StdDictRegistry.build();

    public StdDictImportService(StdDictProperties props, DataSource dataSource,
                               com.yb.hi.platform.PyCodeBackfillService pyBackfill) {
        this.props = props;
        this.dataSource = dataSource;
        this.pyBackfill = pyBackfill;
    }

    /** 全部字典标识(有序) */
    public List<String> keys() {
        return new ArrayList<>(registry.keySet());
    }

    /** 字典定义(可能为 null) */
    public StdDict get(String key) {
        return registry.get(key);
    }

    /** 导入全部标准字典 */
    public Map<String, Object> importAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> details = new ArrayList<>();
        for (String key : registry.keySet()) {
            details.add(importOne(key));
        }
        result.put("total", details.size());
        result.put("elapsedMs", System.currentTimeMillis() - t0);
        result.put("details", details);
        return result;
    }

    /** 导入单类标准字典 */
    public Map<String, Object> importOne(String key) {
        Map<String, Object> r = new LinkedHashMap<>();
        StdDict dict = registry.get(key);
        long t0 = System.currentTimeMillis();
        r.put("dict", key);
        if (dict == null) {
            r.put("status", "FAIL");
            r.put("message", "未知字典标识: " + key);
            return r;
        }
        r.put("name", dict.getName());
        r.put("table", dict.getTable());

        File base = new File(props.getBasePath());
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // 批量导入放宽 sql_mode, 避免个别超长字段导致整批失败(超长自动截断)
                st.execute("SET SESSION sql_mode = ''");
                st.execute("TRUNCATE TABLE " + dict.getTable());
                st.execute("DELETE FROM std_dict_version WHERE dict_key = '" + key + "'");
            }

            long total = 0;
            List<String> sourceMsgs = new ArrayList<>();
            if (dict.getSources().isEmpty()) {
                // 种子文件导入(无 xlsx 源): 如医保字典值域代码、WS/T 364 值域代码,
                // 已离线抽取为 classpath:seed/<table>.tsv(首行为表头), 幂等全量写入。
                total = importFromSeed(conn, dict);
                sourceMsgs.add("seed " + total + "行");
                recordVersionSeed(conn, key, dict, total, "SUCCESS", dict.getSeedVer(), dict.getSeedLabel());
            } else {
                for (StdSource src : dict.getSources()) {
                    // 字典标准类型作为常量列写入每行(随 consts 流入 INSERT)
                    src.cst("std_type", dict.getStdType());
                    // 来源文档: 多源字典已在注册表按源设 src_doc, 单源字典则用字典级默认值
                    if (!src.getConsts().containsKey("src_doc")) {
                        src.cst("src_doc", dict.getSrcDoc());
                    }
                    File f = new File(base, src.getRelPath());
                    if (!f.exists()) {
                        sourceMsgs.add("sheet[" + src.getSheetNo() + "] 源文件不存在: " + src.getRelPath());
                        recordVersion(conn, key, dict, src, f.getName(), 0, "FAIL", "源文件不存在");
                        continue;
                    }
                    long rows = readAndInsert(conn, dict, src, f);
                    total += rows;
                    String ver = src.getConsts().getOrDefault("ver", "");
                    sourceMsgs.add("sheet[" + src.getSheetNo() + "] " + rows + "行");
                    recordVersion(conn, key, dict, src, f.getName(), rows, "SUCCESS", ver);
                }
            }
            conn.commit();
            r.put("status", "SUCCESS");
            r.put("rows", total);
            r.put("message", String.join("; ", sourceMsgs));
            // 重导为 TRUNCATE 重写, 立即按名称回填 py_code(drug_catalog 无该列则内部跳过), 免等下次重启
            pyBackfill.backfillOne(dict.getTable(), "id", dict.getNameCol());
        } catch (Exception e) {
            log.error("标准字典[{}]导入失败", key, e);
            r.put("status", "FAIL");
            r.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        r.put("elapsedMs", System.currentTimeMillis() - t0);
        log.info("标准字典[{}]导入完成: {}", key, r);
        return r;
    }

    /** EasyExcel 流式读取一个 sheet 并批量写入 */
    private long readAndInsert(Connection conn, StdDict dict, StdSource src, File f) throws Exception {
        List<String> cols = new ArrayList<>(src.getDbCols());
        cols.addAll(src.getConsts().keySet());
        String sql = buildInsert(dict.getTable(), cols);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            BatchListener listener = new BatchListener(ps, src, cols.size(), props.getBatchSize());
            EasyExcel.read(f, listener).sheet(src.getSheetNo()).headRowNumber(src.getHeadRow()).doRead();
            listener.flush();
            return listener.getCount();
        }
    }

    /** 构建 INSERT INTO tbl (c1,c2,...) VALUES (?,?,...) */
    private String buildInsert(String table, List<String> cols) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cols.get(i));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        return sb.toString();
    }

    /** 登记导入版本(独立提交, 便于失败也可追溯) */
    private void recordVersion(Connection conn, String key, StdDict dict, StdSource src,
                               String fileName, long rows, String status, String message) {
        String sql = "INSERT INTO std_dict_version (dict_key, dict_name, source_file, sheet, ver, row_count, status, message, import_time) "
                + "VALUES (?,?,?,?,?,?,?,?,NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, dict.getName());
            ps.setString(3, fileName);
            ps.setString(4, "sheet[" + src.getSheetNo() + "]");
            ps.setString(5, src.getConsts().get("ver"));
            ps.setLong(6, rows);
            ps.setString(7, status);
            ps.setString(8, message);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("登记标准字典版本失败: {}", e.getMessage());
        }
    }

    /**
     * 从 classpath 种子文件 seed/<table>.tsv 批量导入(制表符分隔, UTF-8)。
     * 用于无 xlsx 源、由 PDF 离线抽取的标准字典(如医保字典值域代码、WS/T 364 值域代码)。
     * 表头驱动: 首行为列名, INSERT 列 = 表头列 + 注入常量列(ver/std_type/src_doc, 表头未含时)。
     */
    private long importFromSeed(Connection conn, StdDict dict) throws Exception {
        String res = "seed/" + dict.getTable() + ".tsv";
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(res);
        if (in == null) {
            throw new IllegalStateException("种子文件不存在: " + res);
        }
        long count = 0;
        int pending = 0;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null || header.trim().isEmpty()) {
                return 0;
            }
            String[] headCols = header.split("\t", -1);
            for (int i = 0; i < headCols.length; i++) {
                headCols[i] = headCols[i].trim();
            }
            // INSERT 列 = 表头列 + 注入常量列(若表头未含)
            List<String> cols = new ArrayList<>(Arrays.asList(headCols));
            List<String> constVals = new ArrayList<>();
            if (!cols.contains("ver")) { cols.add("ver"); constVals.add(dict.getSeedVer()); }
            if (!cols.contains("std_type")) { cols.add("std_type"); constVals.add(dict.getStdType()); }
            if (!cols.contains("src_doc")) { cols.add("src_doc"); constVals.add(dict.getSrcDoc()); }
            String sql = buildInsert(dict.getTable(), cols);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String[] p = line.split("\t", -1);
                    int idx = 1;
                    for (int i = 0; i < headCols.length; i++) {
                        ps.setString(idx++, i < p.length ? p[i] : "");
                    }
                    for (String cv : constVals) {
                        ps.setString(idx++, cv);
                    }
                    ps.addBatch();
                    count++;
                    if (++pending >= props.getBatchSize()) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }
        }
        return count;
    }

    /** 登记种子文件导入版本 */
    private void recordVersionSeed(Connection conn, String key, StdDict dict, long rows,
                                   String status, String ver, String message) {
        String sql = "INSERT INTO std_dict_version (dict_key, dict_name, source_file, sheet, ver, row_count, status, message, import_time) "
                + "VALUES (?,?,?,?,?,?,?,?,NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, dict.getName());
            ps.setString(3, "seed/" + dict.getTable() + ".tsv");
            ps.setString(4, dict.getSeedLabel());
            ps.setString(5, ver);
            ps.setLong(6, rows);
            ps.setString(7, status);
            ps.setString(8, message);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("登记标准字典版本失败: {}", e.getMessage());
        }
    }

    /** 查询导入登记 */
    public List<Map<String, Object>> versions() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT dict_key, dict_name, source_file, sheet, ver, row_count, status, message, import_time "
                + "FROM std_dict_version ORDER BY dict_key, id";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dictKey", rs.getString("dict_key"));
                m.put("dictName", rs.getString("dict_name"));
                m.put("sourceFile", rs.getString("source_file"));
                m.put("sheet", rs.getString("sheet"));
                m.put("ver", rs.getString("ver"));
                m.put("rowCount", rs.getLong("row_count"));
                m.put("status", rs.getString("status"));
                m.put("message", rs.getString("message"));
                m.put("importTime", rs.getString("import_time"));
                list.add(m);
            }
        } catch (Exception e) {
            log.error("查询标准字典版本失败", e);
        }
        return list;
    }

    /**
     * EasyExcel 监听器: 逐行按列索引映射为 PreparedStatement 参数, 满批即 executeBatch。
     * 以 Map<Integer,String> 接收行数据, 不依赖表头文字。
     */
    private static class BatchListener extends AnalysisEventListener<Map<Integer, String>> {
        private final PreparedStatement ps;
        private final StdSource src;
        private final int colCount;
        private final int batchSize;
        private final List<String> constVals;
        private long count = 0;
        private int pending = 0;

        BatchListener(PreparedStatement ps, StdSource src, int colCount, int batchSize) {
            this.ps = ps;
            this.src = src;
            this.colCount = colCount;
            this.batchSize = batchSize;
            this.constVals = new ArrayList<>(src.getConsts().values());
        }

        long getCount() {
            return count;
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            try {
                List<Integer> idx = src.getSrcIdx();
                // 全空行跳过
                boolean blank = true;
                for (Integer i : idx) {
                    String v = data.get(i);
                    if (v != null && !v.trim().isEmpty()) {
                        blank = false;
                        break;
                    }
                }
                if (blank) {
                    return;
                }
                int p = 1;
                for (Integer i : idx) {
                    ps.setString(p++, norm(data.get(i)));
                }
                for (String cv : constVals) {
                    ps.setString(p++, cv);
                }
                ps.addBatch();
                count++;
                if (++pending >= batchSize) {
                    ps.executeBatch();
                    pending = 0;
                }
            } catch (Exception e) {
                throw new RuntimeException("写入标准字典行失败: " + e.getMessage(), e);
            }
        }

        void flush() throws Exception {
            if (pending > 0) {
                ps.executeBatch();
                pending = 0;
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // no-op, flush 由外部调用
        }

        private String norm(String v) {
            if (v == null) return "";
            String s = v.trim();
            return "null".equals(s) ? "" : s;
        }
    }
}
