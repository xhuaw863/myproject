package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科室服务
 */
@Service
public class HisDeptService extends ServiceImpl<HisDeptMapper, HisDept> {

    private final StdDictQueryService stdDict;

    public HisDeptService(StdDictQueryService stdDict) {
        this.stdDict = stdDict;
    }

    /**
     * 字典字段回填: 医保科别取医保字典 cv_code:caty(2201/2203必填), 回填名称与来源标识。
     */
    public void enrichDict(HisDept d) {
        if (d == null) {
            return;
        }
        if (StringUtils.hasText(d.getDeptCaty())) {
            d.setDeptCatyName(stdDict.nameOf("cv_code", "caty", d.getDeptCaty()));
            d.setDeptCatySrc("cv_code:caty");
        }
    }

    /** 新增科室(回填字典名称与来源标识) */
    public void saveDept(HisDept d) {
        normalizeLevel(d);
        enrichDict(d);
        save(d);
    }

    /** 修改科室(回填字典名称与来源标识) */
    public void updateDept(HisDept d) {
        normalizeLevel(d);
        enrichDict(d);
        updateById(d);
    }

    /**
     * 规范化层级字段: parent_id null→ 0(顶级); dept_level 缺省按有无上级推断(顶级=1大类, 否则=2科室)。
     */
    private void normalizeLevel(HisDept d) {
        if (d.getParentId() == null) {
            d.setParentId(0L);
        }
        if (d.getDeptLevel() == null) {
            d.setDeptLevel(d.getParentId() == 0L ? 1 : 2);
        }
    }

    /** 全部科室(排序) */
    public List<HisDept> listAll() {
        return listAll(null);
    }

    /** 全部科室(可按归属机构过滤, orgId 为空则不限) */
    public List<HisDept> listAll(Long orgId) {
        return lambdaQuery()
                .eq(orgId != null, HisDept::getOrgId, orgId)
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }

    /** 启用状态科室(下拉选择用) */
    public List<HisDept> listEnabled() {
        return listEnabled(null);
    }

    /** 启用状态科室(可按归属机构过滤, orgId 为空则不限) */
    public List<HisDept> listEnabled(Long orgId) {
        return lambdaQuery()
                .eq(HisDept::getStatus, 1)
                .eq(orgId != null, HisDept::getOrgId, orgId)
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }

    /**
     * 构建科室层级树(大类→科室→窗口/诊室)。
     * 以 parent_id 组装; 仅挂载到现有父节点的子节点入树, 孤儿节点(父不存在)归为顶级, 避免丢失。
     */
    public List<HisDept> buildTree(List<HisDept> flat) {
        List<HisDept> roots = new ArrayList<>();
        if (flat == null || flat.isEmpty()) {
            return roots;
        }
        Map<Long, HisDept> byId = new LinkedHashMap<>();
        for (HisDept d : flat) {
            d.setChildren(new ArrayList<>());
            byId.put(d.getId(), d);
        }
        for (HisDept d : flat) {
            Long pid = d.getParentId();
            HisDept parent = (pid == null || pid == 0L) ? null : byId.get(pid);
            if (parent != null) {
                parent.getChildren().add(d);
            } else {
                roots.add(d);
            }
        }
        return roots;
    }

    /** 科室层级树(可按归属机构过滤) */
    public List<HisDept> listTree(Long orgId) {
        return buildTree(listAll(orgId));
    }
}
