package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 科室服务
 */
@Service
public class HisDeptService extends ServiceImpl<HisDeptMapper, HisDept> {

    /** 全部科室(排序) */
    public List<HisDept> listAll() {
        return lambdaQuery()
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }

    /** 启用状态科室(下拉选择用) */
    public List<HisDept> listEnabled() {
        return lambdaQuery()
                .eq(HisDept::getStatus, 1)
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }
}
