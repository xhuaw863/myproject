package com.yb.hi.service;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.stddict.StdDict;
import com.yb.hi.stddict.StdDictRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标准字典维护服务(仅平台超级管理员使用): 对 std_* 表按注册表元数据做通用 CRUD。
 *
 * 安全设计:
 *  - 表名取自可信注册表(StdDictRegistry), 非用户输入;
 *  - 列名先经 information_schema 白名单校验(拒绝未知列), 再拼入 SQL;
 *  - 列值一律用 PreparedStatement 占位符绑定, 杜绝注入;
 *  - id 为自增主键, 不参与新增/修改的列赋值(修改按路径 id 定位)。
 */
@Slf4j
@Service
public class StdDictMaintainService {

    private final DataSource dataSource;
    private final Map<String, StdDict> registry = StdDictRegistry.build();
    /** 字典列名缓存(小写): 避免每次有效性判定都查 information_schema */
    private final Map<String, Set<String>> colNameCache = new ConcurrentHashMap<>();

    public StdDictMaintainService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private StdDict requireDict(String key) {
        StdDict dict = registry.get(key);
        if (dict == null) {
            throw new BizException(400, "不支持的字典类型: " + key);
        }
        return dict;
    }

    /** 表列元数据: [{name,comment,type,nullable,pk,auto}], 供前端渲染动态编辑表单 */
    public List<Map<String, Object>> columns(String key) {
        StdDict dict = requireDict(key);
        String sql = "SELECT column_name, column_comment, data_type, is_nullable, column_key, extra "
                + "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? "
                + "ORDER BY ordinal_position";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dict.getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String name = rs.getString("column_name");
                    String extra = rs.getString("extra");
                    m.put("name", name);
                    m.put("comment", rs.getString("column_comment"));
                    m.put("type", rs.getString("data_type"));
                    m.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    m.put("pk", "PRI".equalsIgnoreCase(rs.getString("column_key")));
                    m.put("auto", extra != null && extra.toLowerCase().contains("auto_increment"));
                    list.add(m);
                }
            }
        } catch (Exception e) {
            log.error("读取字典[{}]列元数据失败", key, e);
            throw new BizException("读取列元数据失败: " + e.getMessage());
        }
        if (list.isEmpty()) {
            throw new BizException("字典表不存在或无列: " + dict.getTable());
        }
        return list;
    }

    /** 分页列表(含 id, 供 CRUD 定位): {records:[{id,code,name,spec,extra}], total} */
    public Map<String, Object> page(String key, String keyword, long page, long size) {
        StdDict dict = requireDict(key);
        boolean hasKw = StringUtils.hasText(keyword);
        List<String> searchCols = effectiveSearchCols(dict);
        StringBuilder where = new StringBuilder();
        if (hasKw && !searchCols.isEmpty()) {
            where.append(" WHERE ");
            for (int i = 0; i < searchCols.size(); i++) {
                if (i > 0) where.append(" OR ");
                where.append(searchCols.get(i)).append(" LIKE ?");
            }
        }
        String selectSql = "SELECT id, " + dict.getCodeCol() + " AS code, "
                + dict.getNameCol() + " AS name, "
                + dict.getSpecCol() + " AS spec, "
                + dict.getExtraCol() + " AS extra, "
                // 拼音简码随行带出供前端展示(医保药品目录用源自带 pinyin, 其余 std_* 用迁移新增 py_code)
                + ("drug_catalog".equals(dict.getTable()) ? "pinyin" : "py_code") + " AS pyCode FROM " + dict.getTable() + where + " ORDER BY id LIMIT ?,?";
        String countSql = "SELECT COUNT(*) FROM " + dict.getTable() + where;
        long offset = (page - 1) * size;
        List<Map<String, Object>> records = new ArrayList<>();
        long total = 0;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                bindKw(ps, searchCols, keyword, hasKw, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                int p = bindKw(ps, searchCols, keyword, hasKw, 1);
                ps.setLong(p++, offset);
                ps.setLong(p, size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("code", rs.getString("code"));
                        m.put("name", rs.getString("name"));
                        m.put("spec", rs.getString("spec"));
                        m.put("extra", rs.getString("extra"));
                        m.put("pyCode", rs.getString("pyCode"));
                        records.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.error("分页查询字典[{}]失败", key, e);
            throw new BizException("查询失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    /** 单行完整数据(所有列以字符串返回, 便于表单绑定) */
    public Map<String, Object> row(String key, long id) {
        StdDict dict = requireDict(key);
        String sql = "SELECT * FROM " + dict.getTable() + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new BizException("记录不存在: id=" + id);
                }
                ResultSetMetaData md = rs.getMetaData();
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    m.put(md.getColumnLabel(i), rs.getString(i));
                }
                return m;
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取字典[{}]行[{}]失败", key, id, e);
            throw new BizException("读取记录失败: " + e.getMessage());
        }
    }

    /** 按编码批量取名称(code -> name), 供业务列表回显医保/标准名称 */
    public Map<String, String> namesByCode(String key, List<String> codes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        StdDict dict = requireDict(key);
        StringBuilder sb = new StringBuilder("SELECT ").append(dict.getCodeCol()).append(" AS c, ")
                .append(dict.getNameCol()).append(" AS n FROM ").append(dict.getTable())
                .append(" WHERE ").append(dict.getCodeCol()).append(" IN (");
        for (int i = 0; i < codes.size(); i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        sb.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("c"), rs.getString("n"));
                }
            }
        } catch (Exception e) {
            log.warn("标准字典按编码取名称失败 key={}: {}", key, e.getMessage());
        }
        return out;
    }

    /** 按编码取标准字典指定列的原始值(列名经 information_schema 白名单校验, 防注入); 查不到返回 null。
     *  同码多行(历史版本)时取任一行非空值, 全为空则返回空串对应的首个值。供业务侧按医保码回查附加属性(如甲乙丙类)。 */
    public String valueByCode(String key, String code, String col) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(col)) {
            return null;
        }
        StdDict dict = requireDict(key);
        if (!hasColumn(key, col)) {
            log.warn("valueByCode: 字典[{}]无列[{}]", key, col);
            return null;
        }
        String sql = "SELECT " + col + " AS v FROM " + dict.getTable() + " WHERE " + dict.getCodeCol() + " = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("v");
                }
            }
        } catch (Exception e) {
            log.warn("标准字典按编码取列失败 key={} col={}: {}", key, col, e.getMessage());
        }
        return null;
    }

    /** 按名称模糊反查字典编码(供留痕按医保名称检索), 最多返回 limit 个码 */
    public List<String> codesByNameLike(String key, String kw, int limit) {
        List<String> out = new ArrayList<>();
        if (kw == null || kw.trim().isEmpty()) {
            return out;
        }
        StdDict dict = requireDict(key);
        String sql = "SELECT " + dict.getCodeCol() + " AS c FROM " + dict.getTable()
                + " WHERE " + dict.getNameCol() + " LIKE ? LIMIT " + limit;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + kw.trim() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("c"));
                }
            }
        } catch (Exception e) {
            log.warn("标准字典按名称反查编码失败 key={}: {}", key, e.getMessage());
        }
        return out;
    }

    /** 按编码批量取名称与有效性: code -> {name, valid, invalidReason}。
     *  作废判定: vali_flag='0'(字典已作废) 或 end_time 已到(已过期);
     *  返回中缺失的编码表示字典中已查不到(被删除), 由调用方自行判定。 */
    public Map<String, Map<String, Object>> infoByCode(String key, List<String> codes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        StdDict dict = requireDict(key);
        boolean hasVali = hasColumn(key, "vali_flag");
        boolean hasEnd = hasColumn(key, "end_time");
        StringBuilder sb = new StringBuilder("SELECT ").append(dict.getCodeCol()).append(" AS c, ")
                .append(dict.getNameCol()).append(" AS n");
        if (hasVali) {
            sb.append(", vali_flag AS vf");
        }
        if (hasEnd) {
            sb.append(", end_time AS et");
        }
        sb.append(" FROM ").append(dict.getTable()).append(" WHERE ").append(dict.getCodeCol()).append(" IN (");
        for (int i = 0; i < codes.size(); i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        sb.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString("c");
                    Map<String, Object> prev = out.get(code);
                    /* 同码多行时以"任一行仍有效"为准, 避免历史版本行误判为作废 */
                    if (prev != null && Boolean.TRUE.equals(prev.get("valid"))) {
                        continue;
                    }
                    String vf = hasVali ? trimToNull(rs.getString("vf")) : null;
                    Timestamp et = hasEnd ? rs.getTimestamp("et") : null;
                    String reason = null;
                    if (vf != null && !"1".equals(vf)) {
                        reason = "已作废";
                    } else if (et != null && et.toLocalDateTime().isBefore(LocalDateTime.now())) {
                        reason = "已过期";
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("n"));
                    m.put("valid", reason == null);
                    m.put("invalidReason", reason);
                    out.put(code, m);
                }
            }
        } catch (Exception e) {
            log.warn("标准字典按编码取有效性失败 key={}: {}", key, e.getMessage());
        }
        return out;
    }

    /** 构造"该医保码在标准字典中仍有效"的 EXISTS 子查询 SQL(表名/列名取自可信注册表, 非用户输入)。
     *  用于业务侧过滤"对照失效"条目: 外层 NOT EXISTS 即医保码已作废/已过期/已删除。
     *
     * @param outerCol 外层表的医保码列(需带表名限定, 如 his_drug_catalog.yb_drug_code) */
    public String validExistsSql(String key, String outerCol) {
        StdDict dict = requireDict(key);
        StringBuilder sb = new StringBuilder("SELECT 1 FROM ").append(dict.getTable()).append(" s WHERE s.")
                .append(dict.getCodeCol()).append(" = ").append(outerCol);
        if (hasColumn(key, "vali_flag")) {
            sb.append(" AND (s.vali_flag IS NULL OR s.vali_flag = '' OR s.vali_flag = '1')");
        }
        if (hasColumn(key, "end_time")) {
            sb.append(" AND (s.end_time IS NULL OR s.end_time > NOW())");
        }
        return sb.toString();
    }

    /** 字典表是否存在指定列(部分字典无 vali_flag/end_time) */
    private boolean hasColumn(String key, String col) {
        Set<String> names = colNameCache.computeIfAbsent(key, k -> {
            Set<String> s = new HashSet<>();
            for (Map<String, Object> c : columns(k)) {
                s.add(String.valueOf(c.get("name")).toLowerCase());
            }
            return s;
        });
        return names.contains(col.toLowerCase());
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    /** 单行详情(带中文列注释): [{field,label,value}], 仅返回非空字段且跳过 id, 供前端展示标准字典(如物价)完整内容 */
    public List<Map<String, Object>> detail(String key, long id) {
        Map<String, Object> row = row(key, id);
        Map<String, String> labelByLower = new LinkedHashMap<>();
        for (Map<String, Object> c : columns(key)) {
            String n = String.valueOf(c.get("name"));
            String comment = c.get("comment") == null ? "" : String.valueOf(c.get("comment")).trim();
            labelByLower.put(n.toLowerCase(), comment.isEmpty() ? n : comment);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String field = e.getKey();
            if ("id".equalsIgnoreCase(field)) {
                continue;
            }
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (v.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("label", labelByLower.getOrDefault(field.toLowerCase(), field));
            m.put("value", v);
            out.add(m);
        }
        return out;
    }

    /** 新增一行, 返回自增 id */
    public long insert(String key, Map<String, Object> data) {
        StdDict dict = requireDict(key);
        List<Map<String, Object>> cols = columns(key);
        Map<String, String> actualByLower = new LinkedHashMap<>();
        Map<String, String> typeByLower = new LinkedHashMap<>();
        for (Map<String, Object> c : cols) {
            String n = String.valueOf(c.get("name"));
            actualByLower.put(n.toLowerCase(), n);
            typeByLower.put(n.toLowerCase(), String.valueOf(c.get("type")));
        }
        List<String> colNames = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String lower = e.getKey().toLowerCase();
            if (lower.equals("id")) {
                continue;
            }
            String actual = actualByLower.get(lower);
            if (actual == null) {
                throw new BizException(400, "非法列名: " + e.getKey());
            }
            colNames.add(actual);
            values.add(normalize(e.getValue(), typeByLower.get(lower)));
        }
        if (colNames.isEmpty()) {
            throw new BizException(400, "没有可写入的列");
        }
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(dict.getTable()).append(" (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(colNames.get(i));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString(), Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.size(); i++) {
                bind(ps, i + 1, values.get(i));
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (Exception e) {
            log.error("新增字典[{}]记录失败", key, e);
            throw new BizException("新增失败: " + rootMsg(e));
        }
        throw new BizException("新增失败: 未取得自增主键");
    }

    /** 按 id 修改一行 */
    public void update(String key, long id, Map<String, Object> data) {
        StdDict dict = requireDict(key);
        List<Map<String, Object>> cols = columns(key);
        Map<String, String> actualByLower = new LinkedHashMap<>();
        Map<String, String> typeByLower = new LinkedHashMap<>();
        for (Map<String, Object> c : cols) {
            String n = String.valueOf(c.get("name"));
            actualByLower.put(n.toLowerCase(), n);
            typeByLower.put(n.toLowerCase(), String.valueOf(c.get("type")));
        }
        List<String> colNames = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String lower = e.getKey().toLowerCase();
            if (lower.equals("id")) {
                continue;
            }
            String actual = actualByLower.get(lower);
            if (actual == null) {
                throw new BizException(400, "非法列名: " + e.getKey());
            }
            colNames.add(actual);
            values.add(normalize(e.getValue(), typeByLower.get(lower)));
        }
        if (colNames.isEmpty()) {
            throw new BizException(400, "没有可更新的列");
        }
        StringBuilder sb = new StringBuilder("UPDATE ").append(dict.getTable()).append(" SET ");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(colNames.get(i)).append(" = ?");
        }
        sb.append(" WHERE id = ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int p = 1;
            for (Object v : values) {
                bind(ps, p++, v);
            }
            ps.setLong(p, id);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new BizException("记录不存在或无变更: id=" + id);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("修改字典[{}]记录[{}]失败", key, id, e);
            throw new BizException("修改失败: " + rootMsg(e));
        }
    }

    /** 按 id 删除一行 */
    public void delete(String key, long id) {
        StdDict dict = requireDict(key);
        String sql = "DELETE FROM " + dict.getTable() + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new BizException("记录不存在: id=" + id);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除字典[{}]记录[{}]失败", key, id, e);
            throw new BizException("删除失败: " + rootMsg(e));
        }
    }

    /* ============ 内部工具 ============ */

    /** 空字符串写入数值/时间列时转 null, 避免类型转换错误 */
    private Object normalize(Object v, String type) {
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            String s = (String) v;
            if (s.trim().isEmpty() && isNumericOrTemporal(type)) {
                return null;
            }
            return s;
        }
        return v;
    }

    private boolean isNumericOrTemporal(String type) {
        if (type == null) {
            return false;
        }
        switch (type.toLowerCase()) {
            case "int":
            case "integer":
            case "bigint":
            case "smallint":
            case "tinyint":
            case "mediumint":
            case "decimal":
            case "numeric":
            case "float":
            case "double":
            case "date":
            case "datetime":
            case "timestamp":
            case "time":
            case "year":
                return true;
            default:
                return false;
        }
    }

    private void bind(PreparedStatement ps, int idx, Object v) throws Exception {
        if (v == null) {
            ps.setNull(idx, Types.NULL);
        } else if (v instanceof Number) {
            ps.setObject(idx, v);
        } else if (v instanceof Boolean) {
            ps.setBoolean(idx, (Boolean) v);
        } else {
            ps.setString(idx, String.valueOf(v));
        }
    }

    private String rootMsg(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private List<String> effectiveSearchCols(StdDict dict) {
        List<String> cols = new ArrayList<>();
        for (String c : new String[]{dict.getCodeCol(), dict.getNameCol(), dict.getSpecCol(), dict.getExtraCol()}) {
            if (StringUtils.hasText(c) && !cols.contains(c)) {
                cols.add(c);
            }
        }
        if (dict.getSearchCols() != null) {
            for (String c : dict.getSearchCols()) {
                if (StringUtils.hasText(c) && !cols.contains(c)) {
                    cols.add(c);
                }
            }
        }
        // 拼音简码: drug_catalog 用源自带 pinyin, 其余 std_* 用新增 py_code
        String py = "drug_catalog".equals(dict.getTable()) ? "pinyin" : "py_code";
        if (!cols.contains(py)) {
            cols.add(py);
        }
        return cols;
    }

    private int bindKw(PreparedStatement ps, List<String> searchCols, String keyword, boolean hasKw, int start) throws Exception {
        int p = start;
        if (hasKw && !searchCols.isEmpty()) {
            String like = "%" + keyword + "%";
            for (int i = 0; i < searchCols.size(); i++) {
                ps.setString(p++, like);
            }
        }
        return p;
    }
}
