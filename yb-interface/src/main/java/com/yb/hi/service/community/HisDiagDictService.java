package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisDiagDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.community.HisDiagDictMapper;
import com.yb.hi.service.StdDictMaintainService;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.stddict.StdDict;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 医共体诊断字典服务(L2, 牵头机构维护): 西医诊断/中医诊断/症候/手术/肿瘤, 单表按 dict_type 区分。
 * 批量导入直接读 std_* 标准字典整表(数万行级), 按 (dict_type, code) 存量 Map 化幂等 upsert,
 * 分批 saveBatch/updateBatchById; 类别与导入源白名单校验, 防止把手术码导入到诊断类。
 */
@Service
public class HisDiagDictService extends ServiceImpl<HisDiagDictMapper, HisDiagDict> {

    public static final String TYPE_WEST = "west";
    public static final String TYPE_TCM = "tcm";
    public static final String TYPE_SYMP = "symp";
    public static final String TYPE_OPER = "oper";
    public static final String TYPE_TUMOR = "tumor";

    /** dict_type -> 允许的标准字典导入源(std dictKey): 医保版在前, 国标版(_nat)在后 */
    private static final Map<String, List<String>> TYPE_SOURCES = new LinkedHashMap<>();
    static {
        TYPE_SOURCES.put(TYPE_WEST, Arrays.asList("icd10", "icd10_nat"));
        TYPE_SOURCES.put(TYPE_TCM, Arrays.asList("tcm_disease_new", "tcm_disease"));
        TYPE_SOURCES.put(TYPE_SYMP, Arrays.asList("tcm_syndrome_new", "tcm_syndrome"));
        TYPE_SOURCES.put(TYPE_OPER, Arrays.asList("icd9", "icd9_nat"));
        TYPE_SOURCES.put(TYPE_TUMOR, Arrays.asList("morphology"));
    }

    private final StdDictQueryService stdQuery;
    private final StdDictMaintainService stdMaintain;
    private final DataSource dataSource;

    public HisDiagDictService(StdDictQueryService stdQuery,
                              StdDictMaintainService stdMaintain,
                              DataSource dataSource) {
        this.stdQuery = stdQuery;
        this.stdMaintain = stdMaintain;
        this.dataSource = dataSource;
    }

    /** 分页查询(关键字: 名称/院内码/医保码/类目/简码; mapped=1已对照/0未对照/空全部) */
    public IPage<HisDiagDict> pageQuery(String dictType, String keyword, Integer status, String mapped, long page, long size) {
        LambdaQueryChainWrapper<HisDiagDict> q = lambdaQuery()
                .eq(StringUtils.hasText(dictType), HisDiagDict::getDictType, dictType)
                .eq(status != null, HisDiagDict::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisDiagDict::getName, keyword)
                    .or().like(HisDiagDict::getCode, keyword)
                    .or().like(HisDiagDict::getYbCode, keyword)
                    .or().like(HisDiagDict::getCategory, keyword)
                    .or().like(HisDiagDict::getPyCode, keyword)
                    .or().like(HisDiagDict::getAbbrCode, keyword));
        }
        if ("1".equals(mapped)) {
            q.isNotNull(HisDiagDict::getYbCode).ne(HisDiagDict::getYbCode, "");
        } else if ("0".equals(mapped)) {
            q.and(w -> w.isNull(HisDiagDict::getYbCode).or().eq(HisDiagDict::getYbCode, ""));
        }
        return q.orderByAsc(HisDiagDict::getSortNo).orderByAsc(HisDiagDict::getId)
                .page(new Page<>(page, size));
    }

    @Override
    public boolean save(HisDiagDict e) {
        fillPyCode(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisDiagDict e) {
        fillPyCode(e);
        return super.updateById(e);
    }

    /** 拼音简码随名称自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖 */
    private void fillPyCode(HisDiagDict e) {
        if (e != null && StringUtils.hasText(e.getName())) {
            e.setPyCode(PinyinUtil.initials(e.getName()));
        }
    }

    /** 单条保存(幂等): 同 dict_type+code 已存在则更新, 否则新增(供导入Tab逐行导入/人工新增) */
    public boolean saveOrUpdateByCode(HisDiagDict e) {
        if (e == null || !StringUtils.hasText(e.getDictType()) || !StringUtils.hasText(e.getCode())) {
            throw new BizException(400, "字典类别与编码不能为空");
        }
        if (!TYPE_SOURCES.containsKey(e.getDictType())) {
            throw new BizException(400, "未知诊断字典类别: " + e.getDictType());
        }
        HisDiagDict exist = lambdaQuery()
                .eq(HisDiagDict::getDictType, e.getDictType())
                .eq(HisDiagDict::getCode, e.getCode()).one();
        if (exist != null) {
            e.setId(exist.getId());
            return updateById(e);
        }
        e.setId(null);
        return save(e);
    }

    /** 类别/导入源合法性校验: dictType 必须在五类内, dictKey 必须是该类别允许的 std 字典 */
    public StdDict checkedSource(String dictType, String dictKey) {
        List<String> sources = TYPE_SOURCES.get(dictType);
        if (sources == null) {
            throw new BizException(400, "未知诊断字典类别: " + dictType);
        }
        if (!sources.contains(dictKey)) {
            throw new BizException(400, "类别 " + dictType + " 不支持从标准字典 " + dictKey + " 导入");
        }
        StdDict d = stdQuery.get(dictKey);
        if (d == null) {
            throw new BizException(400, "未知标准字典: " + dictKey);
        }
        return d;
    }

    /** 单行预览: std 行 -> 诊断字典条目(不落库), 供导入前补录查看 */
    public HisDiagDict previewFromStd(String dictType, String dictKey, long stdId) {
        StdDict d = checkedSource(dictType, dictKey);
        Map<String, Object> r = stdMaintain.row(dictKey, stdId);
        HisDiagDict e = mapStdRow(dictType, dictKey, d, str(r, d.getCodeCol()), str(r, d.getNameCol()),
                str(r, d.getSpecCol()), str(r, "src_doc"));
        return e;
    }

    /**
     * 批量导入(幂等): 全量读 std 表, 按 (dict_type, code) 与存量 Map 比对,
     * 已存在则更新名称/类目/来源(医保版补 yb_code), 否则新增; 返回 total/inserted/updated。
     */
    public Map<String, Object> importFromStd(String dictType, String dictKey) {
        StdDict d = checkedSource(dictType, dictKey);
        Map<String, Long> existId = new HashMap<>();
        for (HisDiagDict e : lambdaQuery()
                .select(HisDiagDict::getId, HisDiagDict::getCode)
                .eq(HisDiagDict::getDictType, dictType).list()) {
            existId.put(e.getCode(), e.getId());
        }
        int total = 0;
        int inserted = 0;
        int updated = 0;
        Set<String> seen = new HashSet<>();
        List<HisDiagDict> addBatch = new ArrayList<>();
        List<HisDiagDict> updBatch = new ArrayList<>();
        String sql = "SELECT " + d.getCodeCol() + ", " + d.getNameCol() + ", " + d.getSpecCol() + ", src_doc FROM " + d.getTable();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                total++;
                HisDiagDict e = mapStdRow(dictType, dictKey, d, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                if (e == null || !seen.add(e.getCode())) {
                    continue;
                }
                Long id = existId.get(e.getCode());
                if (id != null) {
                    e.setId(id);
                    updBatch.add(e);
                } else {
                    addBatch.add(e);
                }
            }
        } catch (SQLException ex) {
            throw new BizException(500, "读取标准字典失败: " + ex.getMessage());
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

    /** std 行 -> 实体映射: 编码/名称缺一不落条目; 医保版源(非_nat)yb_code=code, 国标版留空待补 */
    private HisDiagDict mapStdRow(String dictType, String dictKey, StdDict d,
                                  String code, String name, String category, String srcDoc) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(name)) {
            return null;
        }
        HisDiagDict e = new HisDiagDict();
        e.setDictType(dictType);
        e.setCode(code.trim());
        e.setName(name.trim());
        if (!dictKey.endsWith("_nat")) {
            e.setYbCode(e.getCode());
        }
        e.setCategory(category);
        e.setStatus(1);
        e.setSrcType(dictKey);
        e.setSrcDoc(srcDoc != null ? srcDoc : d.getSrcDoc());
        e.setSrcCode(e.getCode());
        e.setPyCode(PinyinUtil.initials(e.getName()));
        return e;
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r == null ? null : r.get(col);
        return v == null ? null : String.valueOf(v);
    }
}
