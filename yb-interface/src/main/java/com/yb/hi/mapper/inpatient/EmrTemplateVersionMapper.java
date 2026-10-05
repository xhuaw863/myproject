package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrTemplateVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历模板版本快照 Mapper
 */
@Mapper
public interface EmrTemplateVersionMapper extends BaseMapper<HisEmrTemplateVersion> {
}
