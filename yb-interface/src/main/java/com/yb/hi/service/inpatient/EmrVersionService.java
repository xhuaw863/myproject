package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历版本快照服务(T43): 每次 save/submit/audit 落一行 his_emr_version,
 * content/structure 双快照支持富文书(HTML)与结构化(JSON)双模式回溯;
 * 版本对比为行级 LCS diff(增行/删行/改行标记, HTML 先按块级标签拆行, JSON 先美化逐字段成行)。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 全部显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class EmrVersionService {

    private final JdbcTemplate jdbcTemplate;

    public EmrVersionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 快照 ================= */

    /**
     * 保存当前版本快照(在修改前调用): 读取 his_inp_medical_record 的 content/structure_data,
     * version_no 按 record 维度自增(含逻辑删除行取 MAX, 避免版本号重用), 落一行 his_emr_version。
     */
    public void saveVersion(Long recordId, String operateType, Long operatorId, String operatorName) {
        if (recordId == null || operatorId == null) {
            log.warn("病历版本快照跳过(参数缺失): recordId={}, operatorId={}", recordId, operatorId);
            return;
        }
        Long tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content, structure_data FROM his_inp_medical_record"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                recordId, tenantId);
        if (rows.isEmpty()) {
            return; // 记录不存在(异常路径), 不落快照
        }
        Map<String, Object> row = rows.get(0);
        Integer maxNo = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM his_emr_version WHERE record_id = ? AND tenant_id = ?",
                Integer.class, recordId, tenantId);
        int nextNo = (maxNo == null ? 0 : maxNo) + 1;
        jdbcTemplate.update("INSERT INTO his_emr_version"
                        + " (record_id, version_no, content_snapshot, structure_snapshot, operator_id, operator_name,"
                        + "  operate_time, operate_type, tenant_id, create_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, ?, NOW(), 0)",
                recordId, nextNo, text(row.get("content")), text(row.get("structure_data")),
                operatorId, StringUtils.hasText(operatorName) ? operatorName : ("user-" + operatorId),
                operateType, tenantId);
        log.info("病历版本快照: recordId={}, versionNo={}, operateType={}, operatorId={}",
                recordId, nextNo, operateType, operatorId);
    }

    /* ================= 查询 ================= */

    /** 版本列表(按版本号倒序): 元信息 + 内容长度, 不含快照正文(列表轻量) */
    public List<Map<String, Object>> listVersions(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        return jdbcTemplate.queryForList(
                "SELECT id, version_no AS versionNo, operator_id AS operatorId, operator_name AS operatorName,"
                        + " DATE_FORMAT(operate_time, '%Y-%m-%d %H:%i:%s') AS operateTime, operate_type AS operateType,"
                        + " CHAR_LENGTH(COALESCE(content_snapshot, structure_snapshot, '')) AS contentLength"
                        + " FROM his_emr_version WHERE record_id = ? AND tenant_id = ? AND deleted = 0"
                        + " ORDER BY version_no DESC, id DESC",
                recordId, TenantContext.require());
    }

    /** 指定版本详情(含双快照正文), 不存在抛 400 */
    public Map<String, Object> getVersion(Long versionId) {
        if (versionId == null) {
            throw new BizException(400, "版本ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, record_id AS recordId, version_no AS versionNo, content_snapshot AS contentSnapshot,"
                        + " structure_snapshot AS structureSnapshot, operator_id AS operatorId, operator_name AS operatorName,"
                        + " DATE_FORMAT(operate_time, '%Y-%m-%d %H:%i:%s') AS operateTime, operate_type AS operateType"
                        + " FROM his_emr_version WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                versionId, TenantContext.require());
        if (rows.isEmpty()) {
            throw new BizException(400, "病历版本不存在");
        }
        return rows.get(0);
    }

    /**
     * 比较两个版本差异: 取双方快照展示文本(content 优先, 空回退 structure),
     * 行级 LCS diff 输出 {v1, v2, summary, diffs[{line, type(same/add/del/modify), v1Text, v2Text}]}。
     */
    public Map<String, Object> diffVersions(Long v1Id, Long v2Id) {
        if (v1Id == null || v2Id == null) {
            throw new BizException(400, "对比版本ID不能为空");
        }
        Map<String, Object> a = getVersion(v1Id);
        Map<String, Object> b = getVersion(v2Id);
        List<Map<String, Object>> diffs = lineDiff(displayText(a), displayText(b));
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("same", 0);
        summary.put("add", 0);
        summary.put("del", 0);
        summary.put("modify", 0);
        for (Map<String, Object> d : diffs) {
            summary.merge(String.valueOf(d.get("type")), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", brief(a));
        out.put("v2", brief(b));
        out.put("summary", summary);
        out.put("diffs", diffs);
        return out;
    }

    /* ================= 内部实现 ================= */

    /** 版本元信息(对比弹窗头部展示) */
    private static Map<String, Object> brief(Map<String, Object> v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.get("id"));
        m.put("versionNo", v.get("versionNo"));
        m.put("operatorName", v.get("operatorName"));
        m.put("operateTime", v.get("operateTime"));
        m.put("operateType", v.get("operateType"));
        return m;
    }

    /** 快照展示文本: content_snapshot 优先, 空则 structure_snapshot; JSON 美化 / HTML 拆行 / 纯文本原样 */
    private static String displayText(Map<String, Object> version) {
        String content = text(version.get("contentSnapshot"));
        String structure = text(version.get("structureSnapshot"));
        String raw = StringUtils.hasText(content) ? content : structure;
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String t = raw.trim();
        char first = t.charAt(0);
        if (first == '{' || first == '[') {
            return prettyJson(t);
        }
        if (first == '<') {
            return htmlToText(t);
        }
        return t;
    }

    /** JSON 美化(失败原样返回, 不影响对比) */
    private static String prettyJson(String json) {
        try {
            Object o = JSON.parse(json);
            return o == null ? json : JSON.toJSONString(o, JSONWriter.Feature.PrettyFormat);
        } catch (Exception e) {
            return json;
        }
    }

    /** HTML → 文本行: 块级标签(br/p/div/li/tr/h1-6 等)转换行, 剥离其余标签并解码常见实体 */
    private static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<\\s*br\\s*/?\\s*>", "\n");
        s = s.replaceAll("(?is)</\\s*(?:p|div|li|tr|h[1-6]|blockquote|pre|table|thead|tbody|ul|ol|caption)\\s*>", "\n");
        s = s.replaceAll("(?s)<[^>]*>", "");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return s;
    }

    /** 行级 LCS diff: 相同行标记 same; 连续删段与紧邻增段一对一配对为 modify, 余量保留 del/add */
    private static List<Map<String, Object>> lineDiff(String t1, String t2) {
        String[] a = toLines(t1);
        String[] b = toLines(t2);
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        /* 回溯原始操作: 0=same 1=del 2=add(不等时倾向先删后增, 便于配对为改行) */
        List<int[]> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                ops.add(new int[]{0, i++, j++});
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new int[]{1, i++, -1});
            } else {
                ops.add(new int[]{2, -1, j++});
            }
        }
        while (i < n) {
            ops.add(new int[]{1, i++, -1});
        }
        while (j < m) {
            ops.add(new int[]{2, -1, j++});
        }
        /* 组装输出行(连续删段+紧邻增段配对为 modify) */
        List<Map<String, Object>> out = new ArrayList<>();
        int k = 0;
        while (k < ops.size()) {
            int[] op = ops.get(k);
            if (op[0] == 0) {
                out.add(row(out.size() + 1, "same", a[op[1]], b[op[2]]));
                k++;
                continue;
            }
            if (op[0] == 2) {
                out.add(row(out.size() + 1, "add", null, b[op[2]]));
                k++;
                continue;
            }
            List<Integer> delIdx = new ArrayList<>();
            while (k < ops.size() && ops.get(k)[0] == 1) {
                delIdx.add(ops.get(k)[1]);
                k++;
            }
            List<Integer> addIdx = new ArrayList<>();
            while (k < ops.size() && ops.get(k)[0] == 2) {
                addIdx.add(ops.get(k)[2]);
                k++;
            }
            int pair = Math.min(delIdx.size(), addIdx.size());
            for (int p = 0; p < pair; p++) {
                out.add(row(out.size() + 1, "modify", a[delIdx.get(p)], b[addIdx.get(p)]));
            }
            for (int p = pair; p < delIdx.size(); p++) {
                out.add(row(out.size() + 1, "del", a[delIdx.get(p)], null));
            }
            for (int p = pair; p < addIdx.size(); p++) {
                out.add(row(out.size() + 1, "add", null, b[addIdx.get(p)]));
            }
        }
        return out;
    }

    private static Map<String, Object> row(int line, String type, String v1Text, String v2Text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", line);
        m.put("type", type);
        m.put("v1Text", v1Text);
        m.put("v2Text", v2Text);
        return m;
    }

    /** 文本按行拆分并去除首尾空行(兼容 \r\n / \r; 保留中间空行以体现段落结构) */
    private static String[] toLines(String text) {
        if (!StringUtils.hasText(text)) {
            return new String[0];
        }
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int from = 0;
        int to = raw.length;
        while (from < to && raw[from].trim().isEmpty()) {
            from++;
        }
        while (to > from && raw[to - 1].trim().isEmpty()) {
            to--;
        }
        return Arrays.copyOfRange(raw, from, to);
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
