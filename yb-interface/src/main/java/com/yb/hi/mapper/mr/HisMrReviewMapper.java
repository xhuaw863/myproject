package com.yb.hi.mapper.mr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.mr.HisMrReview;
import org.apache.ibatis.annotations.Mapper;

/** 病案审核确认与锁定 Mapper */
@Mapper
public interface HisMrReviewMapper extends BaseMapper<HisMrReview> {
}
