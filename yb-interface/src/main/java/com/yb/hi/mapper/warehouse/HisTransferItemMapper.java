package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisTransferItem;
import org.apache.ibatis.annotations.Mapper;

/** 库存调拨明细 Mapper */
@Mapper
public interface HisTransferItemMapper extends BaseMapper<HisTransferItem> {
}
