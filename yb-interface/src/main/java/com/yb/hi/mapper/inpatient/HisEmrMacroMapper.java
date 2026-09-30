package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrMacro;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历宏变量定义 Mapper
 */
@Mapper
public interface HisEmrMacroMapper extends BaseMapper<HisEmrMacro> {
}
