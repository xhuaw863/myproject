package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.AreaCode;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.AreaCodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 行政区划服务(area_code_2021)。
 * 全局共享参考表, 提供分页检索(关键字/级别/父级)、级联下钻、祖先路径与增删改管理。
 */
@Service
public class AreaCodeService extends ServiceImpl<AreaCodeMapper, AreaCode> {

    /**
     * 分页查询。
     *
     * @param keyword 关键字: 名称模糊 或 区划代码模糊
     * @param level   级别过滤(1-5), null 不限
     * @param pcode   父级代码过滤(下钻), null 不限
     */
    public IPage<AreaCode> pageQuery(long page, long size, String keyword, Integer level, Long pcode) {
        LambdaQueryWrapper<AreaCode> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(AreaCode::getName, kw).or().like(AreaCode::getCode, kw));
        }
        if (level != null) {
            qw.eq(AreaCode::getLevel, level);
        }
        if (pcode != null) {
            qw.eq(AreaCode::getPcode, pcode);
        }
        qw.orderByAsc(AreaCode::getCode);
        return page(new Page<>(page, size), qw);
    }

    /** 祖先路径(根→当前节点), 供前端面包屑展示。code 为空返回空列表。 */
    public List<AreaCode> path(Long code) {
        LinkedList<AreaCode> list = new LinkedList<>();
        Long cur = code;
        int guard = 0;
        while (cur != null && cur != 0L && guard++ < 10) {
            AreaCode a = getById(cur);
            if (a == null) {
                break;
            }
            list.addFirst(a);
            cur = a.getPcode();
        }
        return list;
    }

    /** 各级别数量统计(概览用)。 */
    public List<Map<String, Object>> levelStats() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int lv = 1; lv <= 5; lv++) {
            long n = lambdaQuery().eq(AreaCode::getLevel, lv).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", lv);
            m.put("count", n);
            out.add(m);
        }
        return out;
    }

    /** 新增行政区划(校验代码/名称/级别)。 */
    public void createArea(AreaCode e) {
        validate(e, true);
        if (getById(e.getCode()) != null) {
            throw new BizException("区划代码已存在: " + e.getCode());
        }
        if (e.getPcode() == null) {
            e.setPcode(0L);
        }
        save(e);
    }

    /** 修改行政区划(仅改名称/级别/父级, 代码为主键不可改)。 */
    public void updateArea(AreaCode e) {
        validate(e, false);
        if (getById(e.getCode()) == null) {
            throw new BizException("区划不存在: " + e.getCode());
        }
        updateById(e);
    }

    /** 删除行政区划(存在下级时禁止删除)。 */
    public void deleteArea(Long code) {
        long children = lambdaQuery().eq(AreaCode::getPcode, code).count();
        if (children > 0) {
            throw new BizException("该节点下还有 " + children + " 个下级区划, 请先删除下级");
        }
        removeById(code);
    }

    private void validate(AreaCode e, boolean isCreate) {
        if (e == null) {
            throw new BizException("参数为空");
        }
        if (e.getCode() == null || e.getCode() <= 0) {
            throw new BizException("区划代码必填且为正整数");
        }
        if (!StringUtils.hasText(e.getName())) {
            throw new BizException("名称必填");
        }
        if (e.getLevel() == null || e.getLevel() < 1 || e.getLevel() > 5) {
            throw new BizException("级别必须为 1-5");
        }
        if (isCreate && e.getPcode() != null && e.getPcode() != 0L && getById(e.getPcode()) == null) {
            throw new BizException("父级区划代码不存在: " + e.getPcode());
        }
    }
}
