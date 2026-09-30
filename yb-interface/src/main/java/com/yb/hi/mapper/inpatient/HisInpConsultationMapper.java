package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisInpConsultation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院会诊记录 Mapper
 */
@Mapper
public interface HisInpConsultationMapper extends BaseMapper<HisInpConsultation> {
}
