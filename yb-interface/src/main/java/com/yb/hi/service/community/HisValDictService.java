package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisValDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.community.HisValDictMapper;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.stddict.StdDict;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 医共体值域字典服务(L2, 牵头机构维护): 医疗业务自由值域(性别/险种/剂型/号别等),
 * dict_type = 标准源键:分组码(如 cv_code:gend)。导入按组从 std_* 值域表整组读取,
 * 与存量按 (dict_type, code) Map 化幂等 upsert(组行数十~数百级, 量小直接单批)。
 * 业务下拉统一走 listValues(启用项), 标准字典仅作导入源——落实"医疗业务字典值一律取自医共体统一字典"原则。
 */
@Service
public class HisValDictService extends ServiceImpl<HisValDictMapper, HisValDict> {

    /** 允许作导入源的标准字典(值域四表注册键) */
    private static final Set<String> SOURCES = new HashSet<>(Arrays.asList("cv_code", "wst364", "hbvalue", "whvalue"));

    /** 卫健值域"国标>省标"合并参与源(市标 whvalue 按用户决策不并入) */
    private static final String WJ_NAT = "wst364";
    private static final String WJ_PROV = "hbvalue";

    private final StdDictQueryService stdQuery;
    private final DataSource dataSource;

    public HisValDictService(StdDictQueryService stdQuery, DataSource dataSource) {
        this.stdQuery = stdQuery;
        this.dataSource = dataSource;
    }

    /** 分页查询(关键字: 值名称/编码/医保码/值域名/简码) */
    public IPage<HisValDict> pageQuery(String dictType, String keyword, Integer status, long page, long size) {
        LambdaQueryChainWrapper<HisValDict> q = lambdaQuery()
                .eq(StringUtils.hasText(dictType), HisValDict::getDictType, dictType)
                .eq(status != null, HisValDict::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisValDict::getName, keyword)
                    .or().like(HisValDict::getCode, keyword)
                    .or().like(HisValDict::getYbCode, keyword)
                    .or().like(HisValDict::getTypeName, keyword)
                    .or().like(HisValDict::getPyCode, keyword)
                    .or().like(HisValDict::getAbbrCode, keyword));
        }
        return q.orderByAsc(HisValDict::getSortNo).orderByAsc(HisValDict::getCode)
                .page(new Page<>(page, size));
    }

    /** 业务下拉取值: 指定值域全部启用项 [{code,name}](排序同展示口径), 供 HIS.stdValues 统一取数 */
    public List<Map<String, Object>> listValues(String dictType) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!StringUtils.hasText(dictType)) {
            return list;
        }
        for (HisValDict e : lambdaQuery()
                .eq(HisValDict::getDictType, dictType)
                .eq(HisValDict::getStatus, 1)
                .orderByAsc(HisValDict::getSortNo).orderByAsc(HisValDict::getCode).list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.getCode());
            m.put("name", e.getName());
            list.add(m);
        }
        return list;
    }

    /**
     * 维护页类别下拉动态源: 当前租户 his_val_dict 已存在的分组(dict_type + 展示名 + 行数)。
     * 展示名优先取组内 type_name, 缺失时回落 dict_type。返回 [{v,l,count}]。
     */
    public List<Map<String, Object>> listTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : baseMapper.selectDistinctTypes()) {
            Object v = r.get("v");
            if (v == null) {
                continue;
            }
            Object l = r.get("l");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", v.toString());
            m.put("l", (l == null || l.toString().trim().isEmpty()) ? v.toString() : l.toString().trim());
            m.put("count", r.get("cnt"));
            out.add(m);
        }
        return out;
    }

    /** 值域编码取名称(当前租户): 无则 null */
    public String nameOf(String dictType, String code) {
        if (!StringUtils.hasText(dictType) || !StringUtils.hasText(code)) {
            return null;
        }
        HisValDict e = lambdaQuery()
                .eq(HisValDict::getDictType, dictType)
                .eq(HisValDict::getCode, code.trim()).last("LIMIT 1").one();
        return e == null ? null : e.getName();
    }

    /**
     * 值域归一: 传入值可能是编码或名称(如医保接口文本)。命中编码直接返回原值;
     * 否则按名称唯一匹配返回其编码; 无匹配或同名多义(层级码重名)返回 null(不猜)。
     */
    public String normalizeToCode(String dictType, String val) {
        if (!StringUtils.hasText(dictType) || !StringUtils.hasText(val)) {
            return null;
        }
        String v = val.trim();
        long byCode = lambdaQuery().eq(HisValDict::getDictType, dictType).eq(HisValDict::getCode, v).count();
        if (byCode > 0) {
            return v;
        }
        List<HisValDict> byName = lambdaQuery()
                .eq(HisValDict::getDictType, dictType).eq(HisValDict::getName, v).list();
        return byName.size() == 1 ? byName.get(0).getCode() : null;
    }

    @Override
    public boolean save(HisValDict e) {
        fillPyCode(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisValDict e) {
        fillPyCode(e);
        return super.updateById(e);
    }

    /** 拼音简码随名称自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖 */
    private void fillPyCode(HisValDict e) {
        if (e != null && StringUtils.hasText(e.getName())) {
            e.setPyCode(PinyinUtil.initials(e.getName()));
        }
    }

    /** 单条保存(幂等): 同 dict_type+code 已存在则更新, 否则新增(人工维护/补录) */
    public boolean saveOrUpdateByCode(HisValDict e) {
        if (e == null || !StringUtils.hasText(e.getDictType()) || !StringUtils.hasText(e.getCode())) {
            throw new BizException(400, "值域类别与编码不能为空");
        }
        HisValDict exist = lambdaQuery()
                .eq(HisValDict::getDictType, e.getDictType())
                .eq(HisValDict::getCode, e.getCode()).one();
        if (exist != null) {
            e.setId(exist.getId());
            return updateById(e);
        }
        e.setId(null);
        return save(e);
    }

    /**
     * 整组导入(幂等): dictType=源键:分组码(如 cv_code:gend), 解析后委托 importGroup。
     * 医保/自定义维护页沿用此入口, dict_type 保留"源键:分组码"格式。
     */
    public Map<String, Object> importFromStd(String dictType) {
        int sep = dictType == null ? -1 : dictType.indexOf(':');
        if (sep <= 0 || sep == dictType.length() - 1) {
            throw new BizException(400, "值域类别格式应为 标准源键:分组码, 如 cv_code:gend");
        }
        return importGroup(dictType.substring(0, sep), dictType.substring(sep + 1), dictType);
    }

    /**
     * 从指定标准源某分组读全组值域, 以 dictType 落 his_val_dict, 按 (dict_type, code) 幂等 upsert。
     * dictType 可为"源键:分组码"(医保)或"归一化域名"(卫健按域合并)。返回 total/inserted/updated。
     */
    public Map<String, Object> importGroup(String srcKey, String groupCode, String dictType) {
        if (!StringUtils.hasText(srcKey) || !StringUtils.hasText(groupCode)) {
            throw new BizException(400, "标准源与分组码不能为空");
        }
        if (!SOURCES.contains(srcKey)) {
            throw new BizException(400, "值域字典仅支持从 " + SOURCES + " 导入, 不支持源: " + srcKey);
        }
        StdDict d = stdQuery.get(srcKey);
        if (d == null) {
            throw new BizException(400, "未知标准字典: " + srcKey);
        }
        Map<String, Long> existId = new HashMap<>();
        for (HisValDict e : lambdaQuery()
                .select(HisValDict::getId, HisValDict::getCode)
                .eq(HisValDict::getDictType, dictType).list()) {
            existId.put(e.getCode(), e.getId());
        }
        int total = 0;
        int inserted = 0;
        int updated = 0;
        String typeName = null;
        Set<String> seen = new HashSet<>();
        List<HisValDict> rows = new ArrayList<>();
        String sql = "SELECT " + d.getCodeCol() + ", " + d.getNameCol() + ", " + d.getSpecCol()
                + " FROM " + d.getTable() + " WHERE " + d.getExtraCol() + " = ? ORDER BY " + d.getCodeCol();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total++;
                    String code = rs.getString(1);
                    String name = rs.getString(2);
                    if (typeName == null && rs.getString(3) != null) {
                        typeName = rs.getString(3);
                    }
                    if (!StringUtils.hasText(code) || !StringUtils.hasText(name) || !seen.add(code.trim())) {
                        continue;
                    }
                    rows.add(mapRow(dictType, typeName, srcKey, d, code.trim(), name.trim()));
                }
            }
        } catch (SQLException ex) {
            throw new BizException(500, "读取标准值域失败: " + ex.getMessage());
        }
        if (rows.isEmpty() && total > 0) {
            throw new BizException(400, "标准值域组无有效行: " + dictType);
        }
        List<HisValDict> addBatch = new ArrayList<>();
        List<HisValDict> updBatch = new ArrayList<>();
        for (HisValDict e : rows) {
            Long id = existId.get(e.getCode());
            if (id != null) {
                e.setId(id);
                updBatch.add(e);
            } else {
                addBatch.add(e);
            }
        }
        if (!addBatch.isEmpty()) {
            saveBatch(addBatch, 500);
            inserted = addBatch.size();
        }
        if (!updBatch.isEmpty()) {
            updateBatchById(updBatch, 500);
            updated = updBatch.size();
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", total);
        ret.put("inserted", inserted);
        ret.put("updated", updated);
        return ret;
    }

    /* ================= 卫健值域"国标>省标 归一化名称"按域挑选导入 ================= */

    /**
     * 归一化域名称: 去空白与括号注释、去前缀"人的"、反复剥离尾缀(代码表|代码|编码|值域|字典|表)。
     * 与 tools 原型算法一致, 用于跨国标/省标识别同一逻辑值域。
     */
    public static String normalizeDomainName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim();
        s = s.replaceAll("\\s+", "");
        s = s.replaceAll("[（(].*?[)）]", "");
        if (s.startsWith("人的")) {
            s = s.substring(2);
        }
        for (int i = 0; i < 4; i++) {
            String s2 = s.replaceAll("(代码表|代码|编码|值域|字典|表)$", "");
            if (s2.equals(s)) {
                break;
            }
            s = s2;
        }
        return s;
    }

    /** 读某源全部值域分组: groupCode -> [规范名(出现最多非空), 值行数] */
    private Map<String, Object[]> scanGroups(String srcKey) {
        StdDict d = stdQuery.get(srcKey);
        if (d == null) {
            throw new BizException(400, "未知标准字典: " + srcKey);
        }
        Map<String, Map<String, Integer>> nameCnt = new HashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        String sql = "SELECT " + d.getExtraCol() + ", " + d.getSpecCol() + ", COUNT(*) FROM " + d.getTable()
                + " WHERE " + d.getExtraCol() + " IS NOT NULL AND " + d.getExtraCol() + " <> ''"
                + " GROUP BY " + d.getExtraCol() + ", " + d.getSpecCol();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String gc = rs.getString(1);
                String nm = rs.getString(2);
                int c = rs.getInt(3);
                if (!StringUtils.hasText(gc)) {
                    continue;
                }
                gc = gc.trim();
                totals.merge(gc, c, Integer::sum);
                if (StringUtils.hasText(nm)) {
                    nameCnt.computeIfAbsent(gc, k -> new HashMap<>()).merge(nm.trim(), c, Integer::sum);
                }
            }
        } catch (SQLException ex) {
            throw new BizException(500, "扫描标准值域分组失败: " + ex.getMessage());
        }
        Map<String, Object[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> en : totals.entrySet()) {
            String gc = en.getKey();
            Map<String, Integer> nm = nameCnt.get(gc);
            String best = "";
            if (nm != null) {
                int max = -1;
                for (Map.Entry<String, Integer> e2 : nm.entrySet()) {
                    if (e2.getValue() > max) {
                        max = e2.getValue();
                        best = e2.getKey();
                    }
                }
            }
            out.put(gc, new Object[]{best, en.getValue()});
        }
        return out;
    }

    /** 国标 归一化名 -> groupCode 索引(用于省标覆盖判定与国标优先) */
    private Map<String, String> natNormIndex() {
        Map<String, String> idx = new HashMap<>();
        for (Map.Entry<String, Object[]> en : scanGroups(WJ_NAT).entrySet()) {
            String norm = normalizeDomainName((String) en.getValue()[0]);
            if (!norm.isEmpty() && !idx.containsKey(norm)) {
                idx.put(norm, en.getKey());
            }
        }
        return idx;
    }

    /**
     * 卫健值域"域清单"(供按域挑选): src=wst364(国标)/hbvalue(省标)。
     * 每域返回 {groupCode, rawName, normName, count, status, covered, review}。
     * 省标域若国标已有同名: 同码=covered(导入将取国标), 不同码=review(待核,不自动并)。
     */
    public List<Map<String, Object>> listWjDomains(String srcKey, String keyword) {
        if (!WJ_NAT.equals(srcKey) && !WJ_PROV.equals(srcKey)) {
            throw new BizException(400, "卫健值域仅支持源: " + WJ_NAT + " / " + WJ_PROV);
        }
        Map<String, String> nat = natNormIndex();
        Map<String, Object[]> groups = scanGroups(srcKey);
        boolean isProv = WJ_PROV.equals(srcKey);
        String kw = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> en : groups.entrySet()) {
            String gc = en.getKey();
            String raw = (String) en.getValue()[0];
            int cnt = (Integer) en.getValue()[1];
            String norm = normalizeDomainName(raw);
            if (norm.isEmpty()) {
                continue;
            }
            boolean covered = false;
            boolean review = false;
            String status = "国标(优先)";
            if (isProv) {
                String natGc = nat.get(norm);
                if (natGc != null) {
                    if (natGc.equals(gc)) {
                        covered = true;
                        status = "国标已有·导入取国标";
                    } else {
                        review = true;
                        status = "待核·同名不同码(国标" + natGc + ")";
                    }
                } else {
                    status = "省标补充";
                }
            }
            if (!kw.isEmpty() && !(raw.contains(kw) || norm.contains(kw) || gc.contains(kw))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupCode", gc);
            m.put("rawName", raw);
            m.put("normName", norm);
            m.put("count", cnt);
            m.put("status", status);
            m.put("covered", covered);
            m.put("review", review);
            out.add(m);
        }
        out.sort((a, b) -> ((String) a.get("normName")).compareTo((String) b.get("normName")));
        return out;
    }

    /**
     * 按域挑选导入(卫健): refs=[{src, groupCode, normName}...]。
     * 省标域若国标同名同码 -> 重定向取国标; 同名不同码 -> 归入 needsReview 不自动并。
     * 落 his_val_dict dict_type=normName。返回 imported/rowsInserted/rowsUpdated/redirected/needsReview。
     */
    public Map<String, Object> importWjDomains(List<Map<String, String>> refs) {
        if (refs == null || refs.isEmpty()) {
            throw new BizException(400, "未选择任何值域");
        }
        Map<String, String> nat = natNormIndex();
        int rowsIns = 0;
        int rowsUpd = 0;
        List<String> imported = new ArrayList<>();
        List<String> redirected = new ArrayList<>();
        List<Map<String, Object>> needsReview = new ArrayList<>();
        for (Map<String, String> ref : refs) {
            String src = ref.get("src");
            String gc = ref.get("groupCode");
            String norm = ref.get("normName");
            if (!StringUtils.hasText(norm)) {
                norm = normalizeDomainName(ref.get("rawName"));
            }
            if (!StringUtils.hasText(src) || !StringUtils.hasText(gc) || norm == null || norm.isEmpty()) {
                continue;
            }
            String effSrc = src;
            String effGc = gc;
            if (WJ_PROV.equals(src)) {
                String natGc = nat.get(norm);
                if (natGc != null) {
                    if (natGc.equals(gc)) {
                        effSrc = WJ_NAT;
                        effGc = natGc;
                        redirected.add(norm);
                    } else {
                        Map<String, Object> rv = new LinkedHashMap<>();
                        rv.put("normName", norm);
                        rv.put("provCode", gc);
                        rv.put("natCode", natGc);
                        needsReview.add(rv);
                        continue;
                    }
                }
            }
            Map<String, Object> r = importGroup(effSrc, effGc, norm);
            rowsIns += (Integer) r.get("inserted");
            rowsUpd += (Integer) r.get("updated");
            imported.add(norm);
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("imported", imported.size());
        ret.put("rowsInserted", rowsIns);
        ret.put("rowsUpdated", rowsUpd);
        ret.put("redirected", redirected);
        ret.put("needsReview", needsReview);
        return ret;
    }

    /** std 值域行 -> 实体: cv_code(医保字典)源 yb_code=code, 其余源留空; 来源三件套溯源 */
    private HisValDict mapRow(String dictType, String typeName, String srcKey, StdDict d, String code, String name) {
        HisValDict e = new HisValDict();
        e.setDictType(dictType);
        e.setTypeName(typeName);
        e.setCode(code);
        e.setName(name);
        if ("cv_code".equals(srcKey)) {
            e.setYbCode(code);
        }
        e.setStatus(1);
        e.setSrcType(srcKey);
        e.setSrcDoc(d.getSrcDoc());
        e.setSrcCode(code);
        e.setPyCode(PinyinUtil.initials(name));
        return e;
    }
}
