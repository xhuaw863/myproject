package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisStockCheck;
import org.apache.ibatis.annotations.Mapper;

/**
 * 盘点单主表 Mapper
 */
@Mapper
public interface HisStockCheckMapper extends BaseMapper<HisStockCheck> {
}
