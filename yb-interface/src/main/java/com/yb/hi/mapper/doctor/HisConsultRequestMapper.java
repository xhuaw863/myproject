package com.yb.hi.mapper.doctor;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.doctor.HisConsultRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会诊申请 Mapper
 */
@Mapper
public interface HisConsultRequestMapper extends BaseMapper<HisConsultRequest> {
}
