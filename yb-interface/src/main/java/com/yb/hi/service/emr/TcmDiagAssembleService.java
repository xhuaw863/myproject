package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中医诊断拼装服务(P8a): 从医共体诊断字典(his_diag_dict)取"中医诊断(dict_type=tcm)"与
 * "中医证候(dict_type=symp)", 拼装"病名 证候证"规范文本, 并落库:
 *  - 病案首页 his_case_front_page.tcm_diag (JSON 数组: 诊断+证候组合, 运行期幂等补列兜底);
 *  - 住院诊断 his_inp_diagnosis (diag_type=1 入院诊断, 附 syndrome_code/syndrome_name 两列)。
 *
 * 检索口径: 关键字匹配 名称/拼音简码/编码, 仅启用行(status=1), 租户隔离(his_diag_dict 为医共体共用字典,
 * 按 TenantContext 过滤)。证候推荐优先走标准对照表 std_tcm_mapping(map_type='证候', 按诊断名核心词
 * 模糊命中新老证候名), 无表/无命中退化到院内证候字典, 最终兜底返回证候字典前 20 条。
 * 所有 JdbcTemplate 直写均显式携带 tenant_id(绕开 MyBatis-Plus 租户插件), 异常兜底不阻断主链路。
 */
@Slf4j
@Service
public class TcmDiagAssembleService {

    /** 字典类别: 中医诊断(与 HisDiagDictService.TYPE_TCM 同值口径) */
    private static final String TYPE_TCM = "tcm";
    /** 字典类别: 中医证候(与 HisDiagDictService.TYPE_SYMP 同值口径) */
    private static final String TYPE_SYMP = "symp";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public TcmDiagAssembleService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    /* ================= 拼装 ================= */

    /**
     * 从标准字典拼装"诊断+证候"文本。
     * 返回 {diagCode, diagName, syndromeCode, syndromeName, assembledText}; assembledText 形如
     * "感冒 风寒感冒证"(证候名已含"证"字不重复追加)。
     */
    public Map<String, Object> assembleDiagnosis(String tcmDiagCode, String syndromeCode) {
        if (!StringUtils.hasText(tcmDiagCode)) {
            throw new BizException(400, "中医诊断编码不能为空");
        }
        Map<String, Object> diag = findDiagDict(TYPE_TCM, tcmDiagCode);
        if (diag == null) {
            throw new BizException(400, "中医诊断不存在: " + tcmDiagCode);
        }
        String diagName = String.valueOf(diag.get("name"));
        String syndromeName = null;
        if (StringUtils.hasText(syndromeCode)) {
            Map<String, Object> synd = findDiagDict(TYPE_SYMP, syndromeCode);
            if (synd == null) {
                throw new BizException(400, "中医证候不存在: " + syndromeCode);
            }
            syndromeName = String.valueOf(synd.get("name"));
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("diagCode", tcmDiagCode);
        ret.put("diagName", diagName);
        ret.put("syndromeCode", StringUtils.hasText(syndromeCode) ? syndromeCode : null);
        ret.put("syndromeName", syndromeName);
        ret.put("assembledText", joinText(diagName, syndromeName));
        return ret;
    }

    /** "病名 + 证候证"拼装(证候名尾部已有"证"字不重复追加; 无证候时仅返回诊断名) */
    private static String joinText(String diagName, String syndromeName) {
        if (!StringUtils.hasText(diagName)) {
            return null;
        }
        if (!StringUtils.hasText(syndromeName)) {
            return diagName;
        }
        String suffix = syndromeName.endsWith("证") ? syndromeName : syndromeName + "证";
        return diagName + " " + suffix;
    }

    /* ================= 检索 ================= */

    /** 搜索中医诊断(dict_type='tcm', 名称/拼音简码/编码模糊匹配, LIMIT 50) */
    public List<Map<String, Object>> searchTcmDiag(String keyword) {
        return searchDict(TYPE_TCM, keyword, 50);
    }

    /** 搜索中医证候(dict_type='symp', 名称/拼音简码/编码模糊匹配, LIMIT 50) */
    public List<Map<String, Object>> searchSyndrome(String keyword) {
        return searchDict(TYPE_SYMP, keyword, 50);
    }

    /**
     * 按中医诊断推荐常见证候:
     *  1) 标准对照表 std_tcm_mapping(map_type='证候') 按诊断名核心词模糊命中新/老证候名(表缺失或无命中向下);
     *  2) 院内证候字典按核心词匹配(名称/简码/编码);
     *  3) 兜底: 证候字典按拼音简码排序前 20 条。
     * 每段异常(表/列缺失 BadSqlGrammarException)均降级到下一段, 不向上抛出。
     */
    public List<Map<String, Object>> suggestSyndromes(String tcmDiagCode) {
        if (!StringUtils.hasText(tcmDiagCode)) {
            throw new BizException(400, "中医诊断编码不能为空");
        }
        Map<String, Object> diag = findDiagDict(TYPE_TCM, tcmDiagCode);
        String core = coreWord(diag == null ? null : String.valueOf(diag.get("name")));
        // 1) 标准对照表关联(老库可能无此表/无证候映射)
        if (StringUtils.hasText(core)) {
            try {
                String like = "%" + core + "%";
                List<Map<String, Object>> byMapping = jdbcTemplate.query(
                        "SELECT new_code AS code, new_name AS name, py_code, NULL AS category "
                                + "FROM std_tcm_mapping "
                                + "WHERE map_type = '证候' AND (new_name LIKE ? OR old_name LIKE ?) "
                                + "ORDER BY id LIMIT 20",
                        (rs, i) -> mapRow(rs), like, like);
                if (!byMapping.isEmpty()) {
                    return byMapping;
                }
            } catch (BadSqlGrammarException e) {
                log.debug("std_tcm_mapping 证候关联查询跳过(表/列缺失): {}", e.getMessage());
            }
        }
        // 2) 院内证候字典按核心词命中
        if (StringUtils.hasText(core)) {
            List<Map<String, Object>> byName = searchDict(TYPE_SYMP, core, 20);
            if (!byName.isEmpty()) {
                return byName;
            }
        }
        // 3) 兜底: 证候字典按拼音简码排序前 20 条
        try {
            return jdbcTemplate.query(
                    "SELECT code, name, py_code, category FROM his_diag_dict "
                            + "WHERE tenant_id = ? AND dict_type = ? AND status = 1 AND deleted = 0 "
                            + "ORDER BY py_code, id LIMIT 20",
                    (rs, i) -> mapRow(rs), tenantId(), TYPE_SYMP);
        } catch (BadSqlGrammarException e) {
            log.warn("证候字典兜底查询失败(表结构缺失): {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 诊断名核心词: 去掉尾部分类后缀"病"(如"感冒病"->"感冒"), 用于证候模糊关联 */
    private static String coreWord(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String w = name.trim();
        if (w.length() > 2 && w.endsWith("病")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** 字典检索(租户隔离 + 仅启用行; keyword 为空时返回前 limit 条) */
    private List<Map<String, Object>> searchDict(String dictType, String keyword, int limit) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        try {
            return jdbcTemplate.query(
                    "SELECT code, name, py_code, category FROM his_diag_dict "
                            + "WHERE tenant_id = ? AND dict_type = ? AND status = 1 AND deleted = 0 "
                            + "AND (name LIKE ? OR py_code LIKE ? OR code LIKE ?) "
                            + "ORDER BY sort_no, id LIMIT " + limit,
                    (rs, i) -> mapRow(rs), tenantId(), dictType, like, like, like);
        } catch (BadSqlGrammarException e) {
            log.warn("诊断字典检索失败(表结构缺失): type={}, kw={}", dictType, keyword);
            return new ArrayList<>();
        }
    }

    /** 按编码取字典行(租户隔离; 不过滤 status, 兼容历史停用码的拼装) */
    private Map<String, Object> findDiagDict(String dictType, String code) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT code, name, py_code, category FROM his_diag_dict "
                        + "WHERE tenant_id = ? AND dict_type = ? AND code = ? AND deleted = 0 LIMIT 1",
                (rs, i) -> mapRow(rs), tenantId(), dictType, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 结果行归一: code/name/pyCode/category(前端小驼峰口径) */
    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", rs.getString("code"));
        m.put("name", rs.getString("name"));
        m.put("pyCode", rs.getString("py_code"));
        m.put("category", rs.getString("category"));
        return m;
    }

    /* ================= 落库 ================= */

    /**
     * 将中医诊断写入病案首页(his_case_front_page.tcm_diag JSON)。
     * 每条: {diagCode, diagName, syndromeCode, syndromeName, assembledText}; 传入项缺名称时实时反查字典补全。
     */
    public void assembleToCasePage(Long casePageId, List<Map<String, Object>> tcmDiags) {
        if (casePageId == null) {
            throw new BizException(400, "病案首页ID不能为空");
        }
        List<Map<String, Object>> items = normalizeItems(tcmDiags);
        if (items.isEmpty()) {
            throw new BizException(400, "无可写入的中医诊断");
        }
        addColumnIfNotExists("his_case_front_page", "tcm_diag",
                "TEXT DEFAULT NULL COMMENT '中医诊断证候组合JSON(P8 拼装)'");
        int n = jdbcTemplate.update(
                "UPDATE his_case_front_page SET tcm_diag = ?, update_time = NOW() "
                        + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                JSON.toJSONString(items), casePageId, tenantId());
        if (n == 0) {
            throw new BizException(400, "病案首页不存在: " + casePageId);
        }
        log.info("中医诊断写入病案首页: casePageId={}, 条数={}", casePageId, items.size());
    }

    /**
     * 将中医诊断写入住院入院诊断(his_inp_diagnosis, diag_type=1 入院诊断; 附加诊断 is_main=0)。
     * recordId 兼容住院病历记录ID(his_inp_medical_record.id)或住院就诊ID(his_inp_visit.id)。
     */
    public void assembleToAdmission(Long recordId, List<Map<String, Object>> tcmDiags) {
        if (recordId == null) {
            throw new BizException(400, "住院记录ID不能为空");
        }
        Long tid = tenantId();
        Long visitId = findVisitId(recordId, tid);
        if (visitId == null) {
            throw new BizException(400, "未找到住院记录: " + recordId);
        }
        List<Map<String, Object>> items = normalizeItems(tcmDiags);
        if (items.isEmpty()) {
            throw new BizException(400, "无可写入的中医诊断(诊断名为空)");
        }
        Long orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM his_inp_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                Long.class, visitId, tid);
        addColumnIfNotExists("his_inp_diagnosis", "syndrome_code",
                "VARCHAR(30) DEFAULT NULL COMMENT '证候编码(his_diag_dict.dict_type=symp; P8)'");
        addColumnIfNotExists("his_inp_diagnosis", "syndrome_name",
                "VARCHAR(100) DEFAULT NULL COMMENT '证候名称(P8 中医诊断拼装, 字典回填)'");
        Integer maxSort = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_no), 0) FROM his_inp_diagnosis WHERE inp_visit_id = ? AND tenant_id = ?",
                Integer.class, visitId, tid);
        int sort = maxSort == null ? 0 : maxSort;
        int inserted = 0;
        for (Map<String, Object> item : items) {
            String diagName = str(item.get("diagName"));
            if (!StringUtils.hasText(diagName)) {
                continue; // 名称无法补全的条目跳过(无名诊断不可落库)
            }
            sort++;
            jdbcTemplate.update(
                    "INSERT INTO his_inp_diagnosis (tenant_id, org_id, inp_visit_id, diag_type, diag_code, diag_name, "
                            + "syndrome_code, syndrome_name, is_main, sort_no, diag_time, create_time) "
                            + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, 0, ?, NOW(), NOW())",
                    tid, orgId, visitId, str(item.get("diagCode")), diagName,
                    str(item.get("syndromeCode")), str(item.get("syndromeName")), sort);
            inserted++;
        }
        if (inserted == 0) {
            throw new BizException(400, "无可写入的中医诊断(诊断名为空)");
        }
        log.info("中医诊断写入住院入院诊断: visitId={}, 条数={}", visitId, inserted);
    }

    /** 归一待落库条目: 缺 diagName/syndromeName 时按编码反查字典补全, 缺 assembledText 时本地拼装 */
    private List<Map<String, Object>> normalizeItems(List<Map<String, Object>> tcmDiags) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (tcmDiags == null) {
            return items;
        }
        for (Map<String, Object> d : tcmDiags) {
            if (d == null) {
                continue;
            }
            String diagCode = str(d.get("diagCode"));
            String diagName = str(d.get("diagName"));
            if (!StringUtils.hasText(diagName) && StringUtils.hasText(diagCode)) {
                Map<String, Object> dict = findDiagDict(TYPE_TCM, diagCode);
                if (dict != null) {
                    diagName = String.valueOf(dict.get("name"));
                }
            }
            String syndromeCode = str(d.get("syndromeCode"));
            String syndromeName = str(d.get("syndromeName"));
            if (!StringUtils.hasText(syndromeName) && StringUtils.hasText(syndromeCode)) {
                Map<String, Object> dict = findDiagDict(TYPE_SYMP, syndromeCode);
                if (dict != null) {
                    syndromeName = String.valueOf(dict.get("name"));
                }
            }
            String text = str(d.get("assembledText"));
            if (!StringUtils.hasText(text)) {
                text = joinText(diagName, syndromeName);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("diagCode", StringUtils.hasText(diagCode) ? diagCode : null);
            item.put("diagName", diagName);
            item.put("syndromeCode", StringUtils.hasText(syndromeCode) ? syndromeCode : null);
            item.put("syndromeName", syndromeName);
            item.put("assembledText", text);
            items.add(item);
        }
        return items;
    }

    /** recordId 为病历记录 id 时取其就诊 id; 否则视为就诊 id 直接校验(均租户隔离) */
    private Long findVisitId(Long recordId, Long tid) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT inp_visit_id FROM his_inp_medical_record WHERE id = ? AND tenant_id = ? AND deleted = 0",
                (rs, i) -> rs.getLong(1), recordId, tid);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        List<Long> v = jdbcTemplate.query(
                "SELECT id FROM his_inp_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                (rs, i) -> rs.getLong(1), recordId, tid);
        return v.isEmpty() ? null : v.get(0);
    }

    /* ================= 运行期兜底补列(等价 DictSchemaMigration.addColumnIfNotExists) ================= */

    /** 幂等补列: 表/列缺失时 ALTER 新增; 任何异常仅告警不阻断(启动迁移未及的老库自愈) */
    private void addColumnIfNotExists(String table, String column, String definition) {
        try (Connection conn = dataSource.getConnection()) {
            if (!tableExists(conn, table) || columnExists(conn, table, column)) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("中医诊断运行期补列: {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("中医诊断补列跳过 {}.{}: {}", table, column, e.getMessage());
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /* ================= 工具 ================= */

    private static Long tenantId() {
        return TenantContext.require();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
