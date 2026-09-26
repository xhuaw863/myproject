package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisStockCheckItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 盘点单明细 Mapper
 */
@Mapper
public interface HisStockCheckItemMapper extends BaseMapper<HisStockCheckItem> {
}
