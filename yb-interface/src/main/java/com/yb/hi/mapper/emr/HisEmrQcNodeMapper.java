package com.yb.hi.mapper.emr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.emr.HisEmrQcNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历质控节点日志 Mapper
 */
@Mapper
public interface HisEmrQcNodeMapper extends BaseMapper<HisEmrQcNode> {
}
