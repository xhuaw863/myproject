package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.InpNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院通知 Mapper
 */
@Mapper
public interface InpNotificationMapper extends BaseMapper<InpNotification> {
}
