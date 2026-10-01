package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrBaseDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrBaseDictMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 病案系统维护字典服务(P2, 收尾 P1 遗留 F): 单一通用码表按 dict_type 归类
 * 病案基础(case_base)/卫统基础(wt_base)/病区(ward)/医疗小组(med_team)/节假日(holiday)。
 * 员工/科室复用既有主数据(his_staff/his_dept), 不在此维护。写操作 requireSelfOrgWrite。
 */
@Slf4j
@Service
public class MrDictService {

    /** 支持的字典类别白名单(防误配)。 */
    private static final List<String> TYPES = Arrays.asList("case_base", "wt_base", "ward", "med_team", "holiday");

    private final HisMrBaseDictMapper dictMapper;
    private final OrgAccessGuard guard;

    public MrDictService(HisMrBaseDictMapper dictMapper, OrgAccessGuard guard) {
        this.dictMapper = dictMapper;
        this.guard = guard;
    }

    public List<String> types() {
        return TYPES;
    }

    /** 分页列表(dictType 必填, 关键字/有效标志过滤)。org 维度: 全局(org_id IS NULL) + 本机构。 */
    public IPage<HisMrBaseDict> listPage(long page, long size, String dictType, String keyword, Integer validFlag) {
        if (!StringUtils.hasText(dictType) || !TYPES.contains(dictType.trim())) {
            throw new BizException(400, "非法的字典类别");
        }
        QueryWrapper<HisMrBaseDict> qw = new QueryWrapper<>();
        qw.eq("dict_type", dictType.trim());
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            qw.and(w -> w.isNull("org_id").or().eq("org_id", scopeOrg));
        }
        if (validFlag != null) {
            qw.eq("valid_flag", validFlag);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            qw.and(w -> w.like("code", like).or().like("name", like));
        }
        qw.orderByAsc("sort_no", "code");
        long p = Math.max(1, page);
        long s = size <= 0 ? 50 : Math.min(size, 500);
        return dictMapper.selectPage(new Page<>(p, s), qw);
    }

    /** 扁平列表(供业务下拉消费, 仅启用项)。 */
    public List<HisMrBaseDict> options(String dictType) {
        if (!StringUtils.hasText(dictType) || !TYPES.contains(dictType.trim())) {
            throw new BizException(400, "非法的字典类别");
        }
        QueryWrapper<HisMrBaseDict> qw = new QueryWrapper<>();
        qw.eq("dict_type", dictType.trim()).eq("valid_flag", 1);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            qw.and(w -> w.isNull("org_id").or().eq("org_id", scopeOrg));
        }
        qw.orderByAsc("sort_no", "code");
        return dictMapper.selectList(qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public HisMrBaseDict create(String dictType, java.util.Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        String type = normalizeType(dictType);
        LoginUser lu = UserContext.get();
        HisMrBaseDict d = new HisMrBaseDict();
        d.setDictType(type);
        d.setOrgId(lu.getOrgId());
        applyBody(d, body);
        if (!StringUtils.hasText(d.getCode()) || !StringUtils.hasText(d.getName())) {
            throw new BizException(400, "编码与名称必填");
        }
        if (d.getValidFlag() == null) {
            d.setValidFlag(1);
        }
        if (d.getSortNo() == null) {
            d.setSortNo(0);
        }
        // 同类别内编码唯一
        if (existsCode(type, d.getCode(), d.getOrgId(), null)) {
            throw new BizException(409, "该类别下编码已存在: " + d.getCode());
        }
        dictMapper.insert(d);
        return d;
    }

    @Transactional(rollbackFor = Exception.class)
    public HisMrBaseDict update(Long id, java.util.Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        HisMrBaseDict d = dictMapper.selectById(id);
        if (d == null) {
            throw new BizException(400, "字典项不存在");
        }
        applyBody(d, body);
        if (existsCode(d.getDictType(), d.getCode(), d.getOrgId(), id)) {
            throw new BizException(409, "该类别下编码已存在: " + d.getCode());
        }
        dictMapper.updateById(d);
        return d;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        guard.requireSelfOrgWrite();
        HisMrBaseDict d = dictMapper.selectById(id);
        if (d == null) {
            throw new BizException(400, "字典项不存在");
        }
        dictMapper.deleteById(id);
    }

    private boolean existsCode(String type, String code, Long orgId, Long excludeId) {
        QueryWrapper<HisMrBaseDict> qw = new QueryWrapper<>();
        qw.eq("dict_type", type).eq("code", code);
        if (orgId == null) {
            qw.isNull("org_id");
        } else {
            qw.eq("org_id", orgId);
        }
        if (excludeId != null) {
            qw.ne("id", excludeId);
        }
        return dictMapper.selectCount(qw) > 0;
    }

    private String normalizeType(String dictType) {
        String t = dictType == null ? null : dictType.trim();
        if (!StringUtils.hasText(t) || !TYPES.contains(t)) {
            throw new BizException(400, "非法的字典类别");
        }
        return t;
    }

    private void applyBody(HisMrBaseDict d, java.util.Map<String, Object> body) {
        if (body == null) {
            return;
        }
        if (body.containsKey("code")) {
            d.setCode(trim(str(body.get("code"))));
        }
        if (body.containsKey("name")) {
            d.setName(trim(str(body.get("name"))));
        }
        if (body.containsKey("parentCode")) {
            d.setParentCode(trim(str(body.get("parentCode"))));
        }
        if (body.containsKey("ext1")) {
            d.setExt1(trim(str(body.get("ext1"))));
        }
        if (body.containsKey("ext2")) {
            d.setExt2(trim(str(body.get("ext2"))));
        }
        if (body.containsKey("validFlag")) {
            d.setValidFlag(MrCatalogService.intOrNull(body.get("validFlag")));
        }
        if (body.containsKey("sortNo")) {
            d.setSortNo(MrCatalogService.intOrNull(body.get("sortNo")));
        }
        if (body.containsKey("remark")) {
            d.setRemark(trim(str(body.get("remark"))));
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }
}
