package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 职工服务
 */
@Service
public class HisStaffService extends ServiceImpl<HisStaffMapper, HisStaff> {

    /** 按科室/类别过滤职工 */
    public List<HisStaff> listByFilter(Long deptId, String staffType, String keyword) {
        return lambdaQuery()
                .eq(deptId != null, HisStaff::getDeptId, deptId)
                .eq(StringUtils.hasText(staffType), HisStaff::getStaffType, staffType)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(HisStaff::getStaffName, keyword)
                        .or().like(HisStaff::getStaffNo, keyword))
                .orderByAsc(HisStaff::getSortNo)
                .orderByAsc(HisStaff::getId)
                .list();
    }
}
