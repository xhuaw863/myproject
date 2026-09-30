package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.community.CatalogMapApplyReq;
import com.yb.hi.entity.community.HisDiagDict;
import com.yb.hi.entity.community.HisYbMapLog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisYbMapLogMapper;
import com.yb.hi.service.StdDictMaintainService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医保疾病对照服务(医共体统一诊断字典 -> 医保标准字典)。
 *
 * 参照三目录医保对照(CatalogMapService)的工作台模型, 面向 his_diag_dict 四类诊断字典:
 *  西医疾病(west)->std_icd10, 中医疾病(tcm)->std_tcm_disease, 中医症候(symp)->std_tcm_syndrome,
 *  手术编码(oper)->std_icd9。方向: 从院内诊断字典条目出发, 为其补/改医保标准编码(yb_code)。
 *
 * 与三目录对照的差异: his_diag_dict 仅有 yb_code 一列(无 prev_yb_code / yb_map_eff_time),
 *  标准 ICD/中医字典无作废标志(vali_flag)/失效时间(end_time), 故不提供"对照失效"过滤与生效时间维护;
 *  变更留痕复用 his_yb_map_log(catalog_type 取字典类别 west/tcm/symp/oper)。
 *  读标准字典复用 {@link StdDictMaintainService}(page 取候选 / row 取全列 / infoByCode 回显名称), 不新写 SQL。
 */
@Service
public class DiagMapService {

    public static final String T_WEST = "west";
    public static final String T_TCM = "tcm";
    public static final String T_SYMP = "symp";
    public static final String T_OPER = "oper";

    /** 导出行数上限(按当前筛选一次性导出, 不分页; 诊断表体量远大于三目录, 低于 catalog-map 的 20 万) */
    private static final long EXPORT_MAX = 50000L;

    /** 批量自动对照单次扫描/预览上限: 未对照全量跑名称匹配每条要 1~2 轮标准字典 LIKE 全表扫描, 必须设闸(超上限请勾选子集分批) */
    private static final int AUTO_SCAN_MAX = 2000;
    private static final int AUTO_PREVIEW_MAX = 500;

    /** 医保名称回显分片大小(std_* 主码无唯一约束, infoByCode 用 IN 拼接, 受 MySQL 预处理占位符上限约束) */
    private static final int INFO_CHUNK = 500;

    private final HisDiagDictService diagService;
    private final StdDictMaintainService stdMaintain;
    private final HisYbMapLogMapper ybMapLogMapper;

    public DiagMapService(HisDiagDictService diagService,
                          StdDictMaintainService stdMaintain,
                          HisYbMapLogMapper ybMapLogMapper) {
        this.diagService = diagService;
        this.stdMaintain = stdMaintain;
        this.ybMapLogMapper = ybMapLogMapper;
    }

    /** 类别合法性: 仅支持四类诊断对照(肿瘤形态学暂不纳入对照工作台) */
    private void requireType(String dictType) {
        if (!T_WEST.equals(dictType) && !T_TCM.equals(dictType)
                && !T_SYMP.equals(dictType) && !T_OPER.equals(dictType)) {
            throw new BizException(400, "不支持的诊断类别: " + dictType);
        }
    }

    /** 类别 -> 标准字典 key */
    private String stdKeyOf(String dictType) {
        requireType(dictType);
        if (T_WEST.equals(dictType)) {
            return "icd10";
        }
        if (T_TCM.equals(dictType)) {
            return "tcm_disease";
        }
        if (T_SYMP.equals(dictType)) {
            return "tcm_syndrome";
        }
        return "icd9";
    }

    /** 类别 -> 标准字典主码列名(row() 返回原始列名, 取新医保码用) */
    private String stdCodeCol(String dictType) {
        if (T_WEST.equals(dictType)) {
            return "diag_code";
        }
        if (T_TCM.equals(dictType)) {
            return "dis_class_code";
        }
        if (T_SYMP.equals(dictType)) {
            return "syn_class_code";
        }
        return "oper_code";
    }

    /* ================= 覆盖率看板 ================= */

    /** 每类别 {total, mapped, unmapped}: mapped = yb_code 非空 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(T_WEST, countOf(T_WEST));
        out.put(T_TCM, countOf(T_TCM));
        out.put(T_SYMP, countOf(T_SYMP));
        out.put(T_OPER, countOf(T_OPER));
        return out;
    }

    private Map<String, Object> countOf(String dictType) {
        long total = diagService.count(new LambdaQueryWrapper<HisDiagDict>().eq(HisDiagDict::getDictType, dictType));
        long mapped = diagService.count(new LambdaQueryWrapper<HisDiagDict>()
                .eq(HisDiagDict::getDictType, dictType)
                .isNotNull(HisDiagDict::getYbCode).ne(HisDiagDict::getYbCode, ""));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("mapped", mapped);
        m.put("unmapped", total - mapped);
        return m;
    }

    /* ================= 院内工作队列 ================= */

    /** 院内诊断条目归一视图分页: {id,code,name,category,ybCode,ybName,mapped} */
    public IPage<Map<String, Object>> items(String dictType, long page, long size, Integer mapped, String keyword) {
        requireType(dictType);
        boolean hasKw = StringUtils.hasText(keyword);
        LambdaQueryChainWrapper<HisDiagDict> q = diagService.lambdaQuery().eq(HisDiagDict::getDictType, dictType);
        if (mapped != null) {
            if (mapped == 1) {
                q.isNotNull(HisDiagDict::getYbCode).ne(HisDiagDict::getYbCode, "");
            } else {
                q.and(w -> w.isNull(HisDiagDict::getYbCode).or().eq(HisDiagDict::getYbCode, ""));
            }
        }
        if (hasKw) {
            q.and(w -> w.like(HisDiagDict::getName, keyword)
                    .or().like(HisDiagDict::getCode, keyword)
                    .or().like(HisDiagDict::getYbCode, keyword)
                    .or().like(HisDiagDict::getCategory, keyword)
                    .or().like(HisDiagDict::getPyCode, keyword)
                    .or().like(HisDiagDict::getAbbrCode, keyword));
        }
        IPage<HisDiagDict> p = q.orderByAsc(HisDiagDict::getSortNo).orderByAsc(HisDiagDict::getId)
                .page(new Page<>(page, size));
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        String stdKey = stdKeyOf(dictType);
        List<Map<String, Object>> recs = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (HisDiagDict e : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("code", e.getCode());
            m.put("name", e.getName());
            m.put("category", e.getCategory());
            m.put("ybCode", e.getYbCode());
            m.put("mapped", StringUtils.hasText(e.getYbCode()));
            if (StringUtils.hasText(e.getYbCode())) {
                codes.add(e.getYbCode());
            }
            recs.add(m);
        }
        // 分片回显: 导出路径单页可达 EXPORT_MAX 行, 一次性 IN 会顶穿预处理占位符上限(65535)
        Map<String, Map<String, Object>> info = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i += INFO_CHUNK) {
            info.putAll(stdMaintain.infoByCode(stdKey, codes.subList(i, Math.min(i + INFO_CHUNK, codes.size()))));
        }
        for (Map<String, Object> m : recs) {
            String c = str(m.get("ybCode"));
            Map<String, Object> si = c.isEmpty() ? null : info.get(c);
            m.put("ybName", si == null ? null : si.get("name"));
        }
        out.setRecords(recs);
        return out;
    }

    /* ================= 候选打分 ================= */

    /** 打分候选: [{stdId,code,name,spec,extra,score,reasons}]。
     *  未传 keyword 时按院内条目名称自动推荐(仅保留 score>=0.5); 传 keyword 时为人工检索标准字典, 返回全部命中行。 */
    public List<Map<String, Object>> candidates(String dictType, Long itemId, String keyword, int limit) {
        String stdKey = stdKeyOf(dictType);
        HisDiagDict e = diagService.getById(itemId);
        if (e == null) {
            throw new BizException(404, "诊断条目不存在: " + itemId);
        }
        // 单表异值类别: 条目 dict_type 必须与请求类别一致(含防把四目录之外的 tumor 混入)
        if (!dictType.equals(e.getDictType())) {
            throw new BizException(400, "条目 " + itemId + " 属类别 " + e.getDictType() + ", 与请求类别 " + dictType + " 不符");
        }
        boolean manual = StringUtils.hasText(keyword);
        String base = manual ? keyword : e.getName();
        List<Map<String, Object>> stdRows = fetchStdCandidates(stdKey, base, limit * 2);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : stdRows) {
            // 疾病/症候/手术仅按名称比对(编码是待对的目标, 不参与相似度)
            CatalogMapMatcher.Result r = CatalogMapMatcher.score(e.getName(), "", "",
                    str(row.get("name")), "", "", true);
            if (!manual && r.getScore() < 0.5) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stdId", row.get("id"));
            m.put("code", row.get("code"));
            m.put("name", row.get("name"));
            m.put("spec", row.get("spec"));
            m.put("extra", row.get("extra"));
            m.put("score", round2(r.getScore()));
            m.put("reasons", r.getReasons());
            out.add(m);
        }
        out.sort((a, b) -> {
            double d = -((Number) a.get("score")).doubleValue() + ((Number) b.get("score")).doubleValue();
            return d != 0 ? (d < 0 ? -1 : 1) : String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code")));
        });
        List<Map<String, Object>> fin = out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        // 不再逐行 row() 回补整行: 前端右栏仅消费归一列(code/name/spec/extra/score/reasons), 省 N 次单行查询
        return fin;
    }

    /** 拉取标准字典候选行(全名 + 半名前缀两次检索取并集) */
    private List<Map<String, Object>> fetchStdCandidates(String stdKey, String base, int limit) {
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<>();
        List<String> attempts = new ArrayList<>();
        if (StringUtils.hasText(base)) {
            attempts.add(base);
            if (base.length() > 4) {
                attempts.add(base.substring(0, base.length() / 2));
            }
        }
        for (String kw : attempts) {
            Map<String, Object> p = stdMaintain.page(stdKey, kw, 1, limit);
            Object recs = p.get("records");
            if (recs instanceof List) {
                for (Object o : (List<?>) recs) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) o;
                        byId.putIfAbsent(row.get("id"), row);
                    }
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /* ================= 写入对照 ================= */

    /** 写入对照并留痕(事务: 批量中途 409/异常整批回滚, 不留半截写入); force=false 时, 已对照且目标医保码不同的条目视为"变更对照", 直接拒绝(需前端二次确认后带 force=true) */
    @Transactional(rollbackFor = Exception.class)
    public int apply(String dictType, List<CatalogMapApplyReq.Item> items, String src, boolean force) {
        String stdKey = stdKeyOf(dictType);
        int applied = 0;
        for (CatalogMapApplyReq.Item it : items) {
            if (it == null || it.getItemId() == null || it.getStdId() == null) {
                continue;
            }
            Map<String, Object> std = stdMaintain.row(stdKey, it.getStdId());
            if (applyOne(dictType, it.getItemId(), str(std.get(stdCodeCol(dictType))), src, force, it.getScore())) {
                applied++;
            }
        }
        return applied;
    }

    /** 单条写入: 仅更新 yb_code; 码变化时写变更留痕(MAP/CHANGE); 已对照改码需 force=true; 条目类别与请求类别不符拒绝(防跨类别污染)。 */
    private boolean applyOne(String dictType, Long itemId, String neu, String src, boolean force, Double score) {
        HisDiagDict e = diagService.getById(itemId);
        if (e == null) {
            throw new BizException(404, "诊断条目不存在: " + itemId);
        }
        if (!dictType.equals(e.getDictType())) {
            throw new BizException(400, "条目 " + itemId + " 属类别 " + e.getDictType() + ", 与请求类别 " + dictType + " 不符");
        }
        String old = str(e.getYbCode());
        if (neu == null || neu.isEmpty()) {
            throw new BizException(400, "标准字典行缺少医保编码, 无法对照");
        }
        if (!force && !old.isEmpty() && !old.equals(neu)) {
            String oldName = stdMaintain.namesByCode(stdKeyOf(dictType), Collections.singletonList(old)).get(old);
            throw new BizException(409, "诊断条目「" + e.getName() + "」已对照医保码 " + old
                    + (oldName == null || oldName.isEmpty() ? "" : "(" + oldName + ")") + ", 变更对照需二次确认");
        }
        if (neu.equals(old)) {
            return false;
        }
        e.setYbCode(neu);
        boolean ok = diagService.updateById(e);
        if (ok) {
            logChange(dictType, e.getId(), e.getCode(), e.getName(), old, neu,
                    old.isEmpty() ? HisYbMapLog.TYPE_MAP : HisYbMapLog.TYPE_CHANGE, score, src);
        }
        return ok;
    }

    /* ================= 批量自动对照 ================= */

    /** 批量自动对照: dryRun 仅预览; 否则写入达阈值项(带置信度留痕, src=auto)。返回 {dryRun,threshold,matched,reviewed,skipped,truncated,preview} */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> auto(String dictType, List<Long> itemIds, Double threshold, boolean dryRun) {
        String stdKey = stdKeyOf(dictType);
        double thr = threshold != null ? threshold : CatalogMapMatcher.DEFAULT_AUTO_THRESHOLD;
        List<HisDiagDict> scope = loadUnmapped(dictType, itemIds);
        List<Map<String, Object>> preview = new ArrayList<>();
        int reviewed = 0;
        boolean truncated = false;
        for (HisDiagDict e : scope) {
            if (preview.size() >= AUTO_PREVIEW_MAX) {
                truncated = true;
                break;
            }
            Map<String, Object> best = bestMatch(stdKey, e, thr);
            if (best == null) {
                reviewed++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", e.getId());
            m.put("itemCode", e.getCode());
            m.put("itemName", e.getName());
            m.put("stdId", best.get("stdId"));
            m.put("stdCode", best.get("code"));
            m.put("stdName", best.get("name"));
            m.put("score", best.get("score"));
            preview.add(m);
        }
        int skipped = (itemIds != null && !itemIds.isEmpty()) ? itemIds.size() - scope.size() : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("threshold", thr);
        out.put("matched", preview.size());
        out.put("reviewed", reviewed);
        out.put("skipped", skipped);
        // 扫描设闸: 命中上限说明确有未扫完的存量, 前端提示勾选子集分批处理
        out.put("truncated", truncated || scope.size() >= AUTO_SCAN_MAX);
        out.put("scanMax", AUTO_SCAN_MAX);
        out.put("preview", preview);
        if (dryRun) {
            return out;
        }
        int written = 0;
        for (Map<String, Object> m : preview) {
            double sc = ((Number) m.get("score")).doubleValue();
            if (applyOne(dictType, ((Number) m.get("itemId")).longValue(), str(m.get("stdCode")),
                    HisYbMapLog.SRC_AUTO, true, sc)) {
                written++;
            }
        }
        out.put("matched", written);
        return out;
    }

    /** 取未对照条目(可选 id 子集), 单次扫描上限 AUTO_SCAN_MAX 条(防全表 LIKE 匹配风暴打满连接池) */
    private List<HisDiagDict> loadUnmapped(String dictType, List<Long> itemIds) {
        return diagService.lambdaQuery()
                .eq(HisDiagDict::getDictType, dictType)
                .and(w -> w.isNull(HisDiagDict::getYbCode).or().eq(HisDiagDict::getYbCode, ""))
                .in(itemIds != null && !itemIds.isEmpty(), HisDiagDict::getId, itemIds)
                .orderByAsc(HisDiagDict::getId)
                .page(new Page<>(1, AUTO_SCAN_MAX, false))
                .getRecords();
    }

    /** 单条目最优匹配(达阈值才返回) */
    private Map<String, Object> bestMatch(String stdKey, HisDiagDict e, double thr) {
        List<Map<String, Object>> rows = fetchStdCandidates(stdKey, e.getName(), 10);
        Map<String, Object> best = null;
        double bestScore = 0;
        for (Map<String, Object> row : rows) {
            CatalogMapMatcher.Result r = CatalogMapMatcher.score(e.getName(), "", "", str(row.get("name")), "", "", true);
            if (r.getScore() > bestScore) {
                bestScore = r.getScore();
                best = row;
            }
        }
        if (best == null || bestScore < thr) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stdId", best.get("id"));
        m.put("code", best.get("code"));
        m.put("name", best.get("name"));
        m.put("score", round2(bestScore));
        return m;
    }

    /* ================= 清除对照 ================= */

    /** 将指定诊断条目的医保对照码置空, 返回处理条数; 清除同样留痕(CLEAR); 事务 + 类别双重拦截 */
    @Transactional(rollbackFor = Exception.class)
    public int clear(String dictType, List<Long> itemIds) {
        requireType(dictType);
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long itemId : itemIds) {
            HisDiagDict e = diagService.getById(itemId);
            // 跳不存在/未对照/跨类别条目(后者若不清除会把留痕类别记错)
            if (e == null || !dictType.equals(e.getDictType()) || !StringUtils.hasText(e.getYbCode())) {
                continue;
            }
            String old = e.getYbCode();
            boolean ok = diagService.update(null, new LambdaUpdateWrapper<HisDiagDict>()
                    .set(HisDiagDict::getYbCode, null)
                    .eq(HisDiagDict::getId, itemId)
                    .eq(HisDiagDict::getDictType, dictType));
            if (ok) {
                logChange(dictType, itemId, e.getCode(), e.getName(), old, null,
                        HisYbMapLog.TYPE_CLEAR, null, HisYbMapLog.SRC_MANUAL);
                n++;
            }
        }
        return n;
    }

    /* ================= 变更留痕查询 ================= */

    /** 对照变更留痕分页: dictType 必填(四类之一, 防经本接口直读三目录 charge/drug/cons 留痕); itemId 可选过滤; kw 匹配院内码/院内名/医保码; start/end 为变更日期(含两端)。 */
    public IPage<HisYbMapLog> logs(String dictType, Long itemId, String kw, LocalDate start, LocalDate end, long page, long size) {
        requireType(dictType);
        LambdaQueryWrapper<HisYbMapLog> q = new LambdaQueryWrapper<HisYbMapLog>()
                .eq(HisYbMapLog::getCatalogType, dictType)
                .eq(itemId != null, HisYbMapLog::getCatalogId, itemId)
                .ge(start != null, HisYbMapLog::getChangeTime, start == null ? null : start.atStartOfDay())
                .lt(end != null, HisYbMapLog::getChangeTime, end == null ? null : end.plusDays(1).atStartOfDay())
                .orderByDesc(HisYbMapLog::getChangeTime)
                .orderByDesc(HisYbMapLog::getId);
        if (StringUtils.hasText(kw)) {
            final String k = kw.trim();
            q.and(w -> w.like(HisYbMapLog::getItemCode, k).or().like(HisYbMapLog::getItemName, k)
                    .or().like(HisYbMapLog::getOldCode, k).or().like(HisYbMapLog::getNewCode, k));
        }
        return ybMapLogMapper.selectPage(new Page<>(page, size), q);
    }

    /* ================= 导出 ================= */

    private static final String[] EXPORT_HEAD = {"院内码", "名称", "类目", "医保码", "医保名称", "对照状态"};

    /** 导出对照结果: 复用列表查询与医保名称回显, 返回 {head, rows, total} */
    public Map<String, Object> exportRows(String dictType, Integer mapped, String keyword) {
        IPage<Map<String, Object>> pg = items(dictType, 1, EXPORT_MAX, mapped, keyword);
        List<List<String>> head = new ArrayList<>();
        for (String h : EXPORT_HEAD) {
            head.add(Collections.singletonList(h));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : pg.getRecords()) {
            List<Object> r = new ArrayList<>();
            r.add(str(m.get("code")));
            r.add(str(m.get("name")));
            r.add(str(m.get("category")));
            r.add(str(m.get("ybCode")));
            r.add(str(m.get("ybName")));
            r.add(Boolean.TRUE.equals(m.get("mapped")) ? "已对照" : "未对照");
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", pg.getTotal());
        return out;
    }

    /* ================= 内部工具 ================= */

    /** 写对照变更留痕(新增/变更/清除均记一行; 疾病对照不触发 3301/3302 上报) */
    private void logChange(String dictType, Long itemId, String itemCode, String itemName,
                           String oldCode, String newCode, String type, Double score, String src) {
        HisYbMapLog rec = new HisYbMapLog();
        rec.setCatalogType(dictType);
        rec.setCatalogId(itemId);
        rec.setItemCode(itemCode);
        rec.setItemName(itemName);
        rec.setOldCode(StringUtils.hasText(oldCode) ? oldCode : null);
        rec.setNewCode(StringUtils.hasText(newCode) ? newCode : null);
        rec.setChangeType(type);
        rec.setScore(score);
        rec.setSrc(src);
        LoginUser lu = UserContext.get();
        if (lu != null) {
            rec.setOperator(lu.getUsername());
            rec.setOperatorName(lu.getRealName());
            rec.setOrgId(lu.getOrgId());
        }
        rec.setChangeTime(LocalDateTime.now());
        ybMapLogMapper.insert(rec);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static double round2(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
