package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.mapper.basedata.HisChargeItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 收费项目(本院目录)服务
 */
@Service
public class HisChargeItemService extends ServiceImpl<HisChargeItemMapper, HisChargeItem> {

    /** 分页查询收费项目(支持按名称/编码/医保对照编码模糊检索) */
    public IPage<HisChargeItem> pageQuery(long page, long size, String keyword, String itemType) {
        LambdaQueryChainWrapper<HisChargeItem> q = lambdaQuery()
                .eq(StringUtils.hasText(itemType), HisChargeItem::getItemType, itemType);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisChargeItem::getItemName, keyword)
                    .or().like(HisChargeItem::getItemCode, keyword)
                    .or().like(HisChargeItem::getMedListCodg, keyword));
        }
        return q.orderByDesc(HisChargeItem::getId).page(new Page<>(page, size));
    }
}
