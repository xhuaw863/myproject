package com.yb.hi.mapper.mr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.mr.HisMrWorkload;
import org.apache.ibatis.annotations.Mapper;

/** 病案工作量统计录入 Mapper */
@Mapper
public interface HisMrWorkloadMapper extends BaseMapper<HisMrWorkload> {
}
