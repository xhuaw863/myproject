package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.basedata.HisPayMethodDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.basedata.HisPayMethodDictMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 支付方式字典服务(机构级自定义, 门诊住院统一维护)。
 * code 为规范大写码(新数据统一落此码); legacy_codes 列承载历史旧值(挂号小写 cash / 预交金数字 1),
 * 供存量数据标签回显与录入归一(normalize), 存量数据不回迁。
 */
@Slf4j
@Service
public class PayMethodDictService {

    private static final Set<String> SCENE_TOKENS = new HashSet<>(Arrays.asList("OTP", "IPT", "BOTH"));

    private final HisPayMethodDictMapper mapper;
    private final OrgAccessGuard guard;

    public PayMethodDictService(HisPayMethodDictMapper mapper, OrgAccessGuard guard) {
        this.mapper = mapper;
        this.guard = guard;
    }

    public IPage<HisPayMethodDict> listPage(long page, long size, String keyword, String scope, Integer status) {
        QueryWrapper<HisPayMethodDict> qw = new QueryWrapper<>();
        applyOrgScope(qw);
        if (StringUtils.hasText(scope)) {
            qw.eq("scope", scope.trim().toUpperCase());
        }
        if (status != null) {
            qw.eq("status", status);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            qw.and(w -> w.like("code", like).or().like("name", like).or().like("py_code", like));
        }
        qw.orderByAsc("sort_no").orderByAsc("id");
        return mapper.selectPage(new Page<>(Math.max(1, page), size <= 0 ? 20 : Math.min(size, 500)), qw);
    }

    /** 业务下拉取数: 仅启用项, 场景命中(scope=BOTH 或含该场景)。INSURANCE 由医保结算自动落库, 消费端按需排除。 */
    public List<HisPayMethodDict> options(String scene) {
        QueryWrapper<HisPayMethodDict> qw = new QueryWrapper<>();
        applyOrgScope(qw);
        qw.eq("status", 1);
        if (StringUtils.hasText(scene)) {
            String s = scene.trim().toUpperCase();
            qw.and(w -> w.eq("scope", "BOTH").or().like("scope", s));
        }
        qw.orderByAsc("sort_no").orderByAsc("id");
        return mapper.selectList(qw);
    }

    /**
     * 旧值归一: 历史小写码(cash)/预交金数字码(1) 经 legacy_codes 命中规范码;
     * 已是规范码(忽略大小写)原样返回大写; 字典查不到时回落原值(不猜码, 未知值前端原样展示)。
     */
    public String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String v = raw.trim();
        QueryWrapper<HisPayMethodDict> qw = new QueryWrapper<>();
        applyOrgScope(qw);
        qw.and(w -> w.eq("code", v.toUpperCase()).or().apply("FIND_IN_SET({0}, legacy_codes) > 0", v));
        qw.last("LIMIT 1");
        HisPayMethodDict hit = mapper.selectOne(qw);
        return hit == null ? v : hit.getCode();
    }

    @Transactional(rollbackFor = Exception.class)
    public HisPayMethodDict create(HisPayMethodDict e) {
        guard.requireSelfOrgWrite();
        requireText(e.getCode(), "支付方式编码");
        requireText(e.getName(), "支付方式名称");
        e.setId(null);
        e.setOrgId(guard.currentOrgId());
        e.setCode(e.getCode().trim().toUpperCase());
        e.setScope(normalizeScope(e.getScope()));
        e.setAutoFlag(0);
        applyDefaults(e);
        if (existsCode(e.getOrgId(), e.getCode(), null)) {
            throw new BizException(409, "支付方式编码已存在: " + e.getCode());
        }
        e.setPyCode(PinyinUtil.initials(e.getName()));
        mapper.insert(e);
        return e;
    }

    @Transactional(rollbackFor = Exception.class)
    public HisPayMethodDict update(Long id, HisPayMethodDict body) {
        guard.requireSelfOrgWrite();
        HisPayMethodDict cur = mapper.selectById(id);
        if (cur == null) {
            throw new BizException(400, "支付方式字典项不存在");
        }
        if (StringUtils.hasText(body.getCode()) && !body.getCode().trim().toUpperCase().equals(cur.getCode())) {
            throw new BizException(409, "支付方式编码不允许修改(历史单据按编码引用)");
        }
        cur.setName(StringUtils.hasText(body.getName()) ? body.getName().trim() : cur.getName());
        cur.setScope(normalizeScope(body.getScope()));
        cur.setLegacyCodes(StringUtils.hasText(body.getLegacyCodes()) ? body.getLegacyCodes().trim() : null);
        cur.setPayKind(body.getPayKind());
        cur.setChangeFlag(body.getChangeFlag());
        cur.setDepositFlag(body.getDepositFlag());
        cur.setDayendFlag(body.getDayendFlag());
        cur.setRefundWay(body.getRefundWay());
        cur.setSortNo(body.getSortNo());
        cur.setStatus(body.getStatus());
        cur.setMemo(body.getMemo());
        applyDefaults(cur);
        cur.setPyCode(PinyinUtil.initials(cur.getName()));
        mapper.updateById(cur);
        return cur;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        guard.requireSelfOrgWrite();
        HisPayMethodDict cur = mapper.selectById(id);
        if (cur == null) {
            throw new BizException(400, "支付方式字典项不存在");
        }
        if (cur.getAutoFlag() != null && cur.getAutoFlag() == 1) {
            throw new BizException(409, "系统内置支付方式不允许删除, 可在编辑中停用");
        }
        mapper.deleteById(id);
    }

    private void applyOrgScope(QueryWrapper<HisPayMethodDict> qw) {
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            qw.eq("org_id", scopeOrg);
        }
    }

    private String normalizeScope(String scope) {
        if (!StringUtils.hasText(scope)) {
            return "BOTH";
        }
        Set<String> tokens = new HashSet<>();
        for (String t : scope.toUpperCase().split(",")) {
            String token = t.trim();
            if (!token.isEmpty()) {
                if (!SCENE_TOKENS.contains(token)) {
                    throw new BizException(400, "非法适用场景: " + token);
                }
                tokens.add(token);
            }
        }
        if (tokens.isEmpty() || tokens.contains("BOTH")) {
            return "BOTH";
        }
        return String.join(",", tokens);
    }

    private void applyDefaults(HisPayMethodDict e) {
        if (e.getStatus() == null) {
            e.setStatus(1);
        }
        if (e.getSortNo() == null) {
            e.setSortNo(0);
        }
        if (e.getChangeFlag() == null) {
            e.setChangeFlag(0);
        }
        if (e.getDepositFlag() == null) {
            e.setDepositFlag(0);
        }
        if (e.getDayendFlag() == null) {
            e.setDayendFlag(1);
        }
        if (!StringUtils.hasText(e.getRefundWay())) {
            e.setRefundWay("ORIGIN");
        }
        if (!StringUtils.hasText(e.getScope())) {
            e.setScope("BOTH");
        }
    }

    /** 编码唯一按机构判定(与 uk_pay_method_org_code(tenant_id,org_id,code) 口径一致) */
    private boolean existsCode(Long orgId, String code, Long excludeId) {
        QueryWrapper<HisPayMethodDict> qw = new QueryWrapper<>();
        qw.eq("org_id", orgId).eq("code", code);
        if (excludeId != null) {
            qw.ne("id", excludeId);
        }
        Long cnt = mapper.selectCount(qw);
        return cnt != null && cnt > 0;
    }

    private void requireText(String v, String label) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(400, label + "必填");
        }
    }
}
