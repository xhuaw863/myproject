package com.yb.hi.mapper.pharmacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.pharmacy.HisDispenseItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发药明细行 Mapper(三期 C2)
 */
@Mapper
public interface HisDispenseItemMapper extends BaseMapper<HisDispenseItem> {
}
