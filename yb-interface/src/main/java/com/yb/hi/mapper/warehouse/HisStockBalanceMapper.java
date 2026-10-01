package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisStockBalance;
import org.apache.ibatis.annotations.Mapper;

/**
 * 未验收药品出库平账记录 Mapper
 */
@Mapper
public interface HisStockBalanceMapper extends BaseMapper<HisStockBalance> {
}
