package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.basedata.HisChargeItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收费项目(本院目录)服务
 */
@Service
public class HisChargeItemService extends ServiceImpl<HisChargeItemMapper, HisChargeItem> {

    /** 分页查询收费项目(支持按名称/编码/医保对照编码模糊检索; mapped=1已对照/0未对照/空全部;
     *  四个分类维度过滤: 竖线分隔多值(级联子树), 特殊值 __EMPTY__ 表示未分类) */
    public IPage<HisChargeItem> pageQuery(long page, long size, String keyword, String itemType, String mapped,
                                          String invoiceClass, String acctClass, String mrCostClass, String catCodes) {
        LambdaQueryChainWrapper<HisChargeItem> q = lambdaQuery()
                .eq(StringUtils.hasText(itemType), HisChargeItem::getItemType, itemType);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisChargeItem::getItemName, keyword)
                    .or().like(HisChargeItem::getItemCode, keyword)
                    .or().like(HisChargeItem::getMedListCodg, keyword)
                    .or().like(HisChargeItem::getPyCode, keyword)
                    .or().like(HisChargeItem::getAbbrCode, keyword));
        }
        if ("1".equals(mapped)) {
            q.isNotNull(HisChargeItem::getMedListCodg).ne(HisChargeItem::getMedListCodg, "");
        } else if ("0".equals(mapped)) {
            q.and(w -> w.isNull(HisChargeItem::getMedListCodg).or().eq(HisChargeItem::getMedListCodg, ""));
        }
        inOrEmpty(q, HisChargeItem::getInvoiceClass, invoiceClass);
        inOrEmpty(q, HisChargeItem::getAcctClass, acctClass);
        inOrEmpty(q, HisChargeItem::getMrCostClass, mrCostClass);
        inOrEmpty(q, HisChargeItem::getCatCode, catCodes);
        return q.orderByDesc(HisChargeItem::getId).page(new Page<>(page, size));
    }

    /** 分类维度计数(供左目录树节点角标): 四列各一次 group by, 返回 {cat/invoice/acct/mr -> 值->条数} */
    public Map<String, Map<String, Long>> classCounts() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        out.put("cat", countBy("cat_code", "catCode"));
        out.put("invoice", countBy("invoice_class", "invoiceClass"));
        out.put("acct", countBy("acct_class", "acctClass"));
        out.put("mr", countBy("mr_cost_class", "mrCostClass"));
        return out;
    }

    private Map<String, Long> countBy(String col, String camelKey) {
        List<Map<String, Object>> rows = baseMapper.selectMaps(new QueryWrapper<HisChargeItem>()
                .select(col, "COUNT(*) AS cnt").groupBy(col));
        Map<String, Long> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            // mapUnderscoreToCamelCase 会把 selectMaps 键转驼峰, 两种口径都兼容
            Object k = r.containsKey(camelKey) ? r.get(camelKey) : r.get(col);
            Object c = r.get("cnt");
            m.put(k == null ? "" : String.valueOf(k), c == null ? 0L : ((Number) c).longValue());
        }
        return m;
    }

    /** 竖线分隔多值 IN 过滤; __EMPTY__ 代表空值分支(NULL 或 ''), 两者共存时 or 组合 */
    private void inOrEmpty(LambdaQueryChainWrapper<HisChargeItem> q, SFunction<HisChargeItem, ?> col, String csv) {
        if (!StringUtils.hasText(csv)) {
            return;
        }
        List<String> vals = new ArrayList<>();
        boolean empty = false;
        for (String s : csv.split("\\|")) {
            String v = s.trim();
            if (v.isEmpty()) {
                continue;
            }
            if ("__EMPTY__".equals(v)) {
                empty = true;
            } else {
                vals.add(v);
            }
        }
        if (!vals.isEmpty() && empty) {
            q.and(w -> w.in(col, vals).or().isNull(col).or().eq(col, ""));
        } else if (!vals.isEmpty()) {
            q.in(col, vals);
        } else if (empty) {
            q.and(w -> w.isNull(col).or().eq(col, ""));
        }
    }

    @Override
    public boolean save(HisChargeItem e) {
        fillPyCode(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisChargeItem e) {
        fillPyCode(e);
        return super.updateById(e);
    }

    /** 拼音简码随项目名称自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖 */
    private void fillPyCode(HisChargeItem e) {
        if (e != null && StringUtils.hasText(e.getItemName())) {
            e.setPyCode(PinyinUtil.initials(e.getItemName()));
        }
    }
}
