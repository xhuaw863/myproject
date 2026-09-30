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
     * 整组导入(幂等): dictType=源键:分组码(如 cv_code:gend), 从对应 std 值域表读全组,
     * 按 (dict_type, code) 存量比对 upsert; 返回 total/inserted/updated。
     */
    public Map<String, Object> importFromStd(String dictType) {
        int sep = dictType == null ? -1 : dictType.indexOf(':');
        if (sep <= 0 || sep == dictType.length() - 1) {
            throw new BizException(400, "值域类别格式应为 标准源键:分组码, 如 cv_code:gend");
        }
        String srcKey = dictType.substring(0, sep);
        String groupCode = dictType.substring(sep + 1);
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
