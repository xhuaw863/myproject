package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisInpTransfer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院转科转床申请 Mapper
 */
@Mapper
public interface HisInpTransferMapper extends BaseMapper<HisInpTransfer> {
}
