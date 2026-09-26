package com.yb.hi.service;

import com.yb.hi.stddict.StdDict;
import com.yb.hi.stddict.StdDictRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 标准字典查询服务: 统一返回 {code,name,spec,extra} 结构, 供业务对照选择。
 * 列名取自可信注册表(非用户输入), 关键字用参数占位, 无注入风险。
 */
@Slf4j
@Service
public class StdDictQueryService {

    private final DataSource dataSource;
    private final Map<String, StdDict> registry = StdDictRegistry.build();

    public StdDictQueryService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public StdDict get(String key) {
        return registry.get(key);
    }

    public List<String> keys() {
        return new ArrayList<>(registry.keySet());
    }

    /** 字典元信息列表: {key,name,stdType,srcDoc}, 供前端展示标准类型与来源文档 */
    public List<Map<String, Object>> typesMeta() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StdDict d : registry.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.getKey());
            m.put("name", d.getName());
            m.put("stdType", d.getStdType());
            m.put("srcDoc", d.getSrcDoc());
            list.add(m);
        }
        return list;
    }

    /** 分页查询标准字典 */
    public Map<String, Object> query(String key, String keyword, long page, long size) {
        Map<String, Object> out = new LinkedHashMap<>();
        StdDict dict = registry.get(key);
        if (dict == null) {
            out.put("error", "不支持的字典类型: " + key);
            return out;
        }
        boolean hasKw = StringUtils.hasText(keyword);
        // 前端展示的 code/name/spec/extra 列必须全部可检索, 再并入注册表额外配置的检索列(去重保序)
        List<String> searchCols = effectiveSearchCols(dict);
        StringBuilder where = new StringBuilder();
        if (hasKw && !searchCols.isEmpty()) {
            where.append(" WHERE ");
            for (int i = 0; i < searchCols.size(); i++) {
                if (i > 0) where.append(" OR ");
                where.append(searchCols.get(i)).append(" LIKE ?");
            }
        }
        String selectSql = "SELECT " + dict.getCodeCol() + " AS code, "
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
            log.error("查询标准字典[{}]失败", key, e);
            out.put("error", e.getMessage());
            return out;
        }
        out.put("records", records);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("stdType", dict.getStdType());
        out.put("srcDoc", dict.getSrcDoc());
        return out;
    }

    /**
     * 值域下拉取值: 按字典类型(cv_code/wst364/hbvalue/whvalue) + 分组编码(dict_code/cv_code)
     * 返回该值域下全部 [{code,name}](按编码升序), 供前端字典下拉选择录入。
     */
    public List<Map<String, Object>> values(String type, String code) {
        List<Map<String, Object>> list = new ArrayList<>();
        StdDict dict = registry.get(type);
        if (dict == null || !StringUtils.hasText(code)) {
            return list;
        }
        String sql = "SELECT " + dict.getCodeCol() + " AS code, " + dict.getNameCol() + " AS name FROM "
                + dict.getTable() + " WHERE " + dict.getExtraCol() + " = ? ORDER BY " + dict.getCodeCol();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    list.add(m);
                }
            }
        } catch (Exception e) {
            log.error("查询字典值域[{}:{}]失败", type, code, e);
        }
        return list;
    }

    /** 单值查名: 按 type + 分组编码 + 值编码 返回显示名(无匹配返回 null)。供保存时回填 *_name。 */
    public String nameOf(String type, String code, String valCode) {
        StdDict dict = registry.get(type);
        if (dict == null || !StringUtils.hasText(code) || !StringUtils.hasText(valCode)) {
            return null;
        }
        String sql = "SELECT " + dict.getNameCol() + " FROM " + dict.getTable()
                + " WHERE " + dict.getExtraCol() + " = ? AND " + dict.getCodeCol() + " = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, valCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (Exception e) {
            log.error("查询字典名称[{}:{}:{}]失败", type, code, valCode, e);
        }
        return null;
    }

    /** 有效检索列: 前端展示的 code/name/spec/extra 列全部纳入, 再并入注册表额外配置的检索列(去重保序), 末尾附拼音简码列。 */
    private List<String> effectiveSearchCols(StdDict dict) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (String c : new String[]{dict.getCodeCol(), dict.getNameCol(), dict.getSpecCol(), dict.getExtraCol()}) {
            if (StringUtils.hasText(c)) cols.add(c);
        }
        if (dict.getSearchCols() != null) {
            for (String c : dict.getSearchCols()) {
                if (StringUtils.hasText(c)) cols.add(c);
            }
        }
        // 拼音简码: 医保药品目录(drug_catalog)用源自带 pinyin 列, 其余 std_* 用迁移新增的 py_code
        cols.add("drug_catalog".equals(dict.getTable()) ? "pinyin" : "py_code");
        return new ArrayList<>(cols);
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
