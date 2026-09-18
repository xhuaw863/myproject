package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisSchedule;
import com.yb.hi.mapper.basedata.HisScheduleMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 排班号源服务
 */
@Service
public class HisScheduleService extends ServiceImpl<HisScheduleMapper, HisSchedule> {

    /** 按科室/职工/日期区间查询排班 */
    public List<HisSchedule> listByFilter(Long deptId, Long staffId, LocalDate from, LocalDate to) {
        return lambdaQuery()
                .eq(deptId != null, HisSchedule::getDeptId, deptId)
                .eq(staffId != null, HisSchedule::getStaffId, staffId)
                .ge(from != null, HisSchedule::getWorkDate, from)
                .le(to != null, HisSchedule::getWorkDate, to)
                .orderByAsc(HisSchedule::getWorkDate)
                .orderByAsc(HisSchedule::getId)
                .list();
    }
}
