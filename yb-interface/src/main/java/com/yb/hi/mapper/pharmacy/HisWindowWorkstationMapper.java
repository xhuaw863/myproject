package com.yb.hi.mapper.pharmacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.pharmacy.HisWindowWorkstation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发药工作站↔窗口关联 Mapper
 */
@Mapper
public interface HisWindowWorkstationMapper extends BaseMapper<HisWindowWorkstation> {
}
