package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrSignature;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历可靠电子签名(SM2) Mapper
 */
@Mapper
public interface HisEmrSignatureMapper extends BaseMapper<HisEmrSignature> {
}
