package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.basedata.HisFeeTypeDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.basedata.HisFeeTypeDictMapper;
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
 * 患者费别字典服务(机构级自定义, 门诊住院统一维护)。
 * 读隔离沿用 MrDictService 范式(guard.scopeOrgId: 牵头可按入参查/全部, 非牵头锁定本机构);
 * 写守卫 requireSelfOrgWrite(本机构管理员自治)。内置项(auto_flag=1)禁删、编码不可改。
 */
@Slf4j
@Service
public class FeeTypeDictService {

    /** 合法场景 token(存 scope/ctl_scene) */
    private static final Set<String> SCENE_TOKENS = new HashSet<>(Arrays.asList("OTP", "IPT", "BOTH"));

    private final HisFeeTypeDictMapper mapper;
    private final OrgAccessGuard guard;

    public FeeTypeDictService(HisFeeTypeDictMapper mapper, OrgAccessGuard guard) {
        this.mapper = mapper;
        this.guard = guard;
    }

    /** 分页列表(keyword 命中 code/name/py_code; scope 精确过滤; status 过滤)。 */
    public IPage<HisFeeTypeDict> listPage(long page, long size, String keyword, String scope, Integer status) {
        QueryWrapper<HisFeeTypeDict> qw = new QueryWrapper<>();
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

    /** 业务下拉取数: 仅启用项, 场景命中(scope=BOTH 或含该场景), 按 sort_no 排序。 */
    public List<HisFeeTypeDict> options(String scene) {
        QueryWrapper<HisFeeTypeDict> qw = new QueryWrapper<>();
        applyOrgScope(qw);
        qw.eq("status", 1);
        applySceneMatch(qw, scene);
        qw.orderByAsc("sort_no").orderByAsc("id");
        return mapper.selectList(qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public HisFeeTypeDict create(HisFeeTypeDict e) {
        guard.requireSelfOrgWrite();
        requireText(e.getCode(), "费别编码");
        requireText(e.getName(), "费别名称");
        e.setId(null);
        e.setOrgId(guard.currentOrgId());
        e.setCode(e.getCode().trim());
        e.setScope(normalizeScope(e.getScope()));
        e.setAutoFlag(0);
        applyDefaults(e);
        if (existsCode(e.getOrgId(), e.getCode(), null)) {
            throw new BizException(409, "费别编码已存在: " + e.getCode());
        }
        e.setPyCode(PinyinUtil.initials(e.getName()));
        mapper.insert(e);
        return e;
    }

    @Transactional(rollbackFor = Exception.class)
    public HisFeeTypeDict update(Long id, HisFeeTypeDict body) {
        guard.requireSelfOrgWrite();
        HisFeeTypeDict cur = mapper.selectById(id);
        if (cur == null) {
            throw new BizException(400, "费别字典项不存在");
        }
        // 编码不可改(历史单据以 code 为引用锚点); 内置项额外锁 auto_flag
        boolean auto = cur.getAutoFlag() != null && cur.getAutoFlag() == 1;
        if (StringUtils.hasText(body.getCode()) && !body.getCode().trim().equals(cur.getCode())) {
            throw new BizException(409, "费别编码不允许修改(历史数据按编码引用)");
        }
        cur.setName(StringUtils.hasText(body.getName()) ? body.getName().trim() : cur.getName());
        cur.setScope(normalizeScope(body.getScope()));
        cur.setChannel(body.getChannel());
        cur.setInsutype(body.getInsutype());
        cur.setCtlFlag(body.getCtlFlag());
        cur.setCtlHard(body.getCtlHard());
        cur.setCtlScene(StringUtils.hasText(body.getCtlScene()) ? normalizeCtlScene(body.getCtlScene()) : null);
        cur.setCtlAmount(body.getCtlAmount());
        cur.setCtlIptAmount(body.getCtlIptAmount());
        cur.setCtlDayAmount(body.getCtlDayAmount());
        cur.setSelfpayRate(body.getSelfpayRate());
        cur.setPrepayRate(body.getPrepayRate());
        cur.setDiscountMode(body.getDiscountMode());
        cur.setDiscountRate(body.getDiscountRate());
        cur.setDiscountAmount(body.getDiscountAmount());
        cur.setDiscountJson(body.getDiscountJson());
        cur.setPayLimitJson(body.getPayLimitJson());
        cur.setSortNo(body.getSortNo());
        cur.setStatus(body.getStatus());
        cur.setMemo(body.getMemo());
        if (auto) {
            cur.setAutoFlag(1);
        } else {
            cur.setAutoFlag(0);
        }
        applyDefaults(cur);
        cur.setPyCode(PinyinUtil.initials(cur.getName()));
        mapper.updateById(cur);
        return cur;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        guard.requireSelfOrgWrite();
        HisFeeTypeDict cur = mapper.selectById(id);
        if (cur == null) {
            throw new BizException(400, "费别字典项不存在");
        }
        if (cur.getAutoFlag() != null && cur.getAutoFlag() == 1) {
            throw new BizException(409, "系统内置费别不允许删除, 可在编辑中停用");
        }
        mapper.deleteById(id);
    }

    /** 按编码查本机构启用项(挂号/结算消费; 查不到返回 null, 历史值 self/insurance 无字典行时行为不变)。 */
    public HisFeeTypeDict findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        QueryWrapper<HisFeeTypeDict> qw = new QueryWrapper<>();
        applyOrgScope(qw);
        qw.eq("code", code.trim()).eq("status", 1).last("LIMIT 1");
        return mapper.selectOne(qw);
    }

    private void applyOrgScope(QueryWrapper<HisFeeTypeDict> qw) {
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            qw.eq("org_id", scopeOrg);
        }
    }

    /** 场景命中: scope=BOTH 或逗号分隔含该场景(如 OTP,IPT) */
    private void applySceneMatch(QueryWrapper<HisFeeTypeDict> qw, String scene) {
        if (!StringUtils.hasText(scene)) {
            return;
        }
        String s = scene.trim().toUpperCase();
        qw.and(w -> w.eq("scope", "BOTH").or().like("scope", s));
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
        if (tokens.contains("BOTH")) {
            return "BOTH";
        }
        if (tokens.isEmpty()) {
            return "BOTH";
        }
        return String.join(",", tokens);
    }

    private String normalizeCtlScene(String scene) {
        return normalizeScope(scene);
    }

    private void applyDefaults(HisFeeTypeDict e) {
        if (e.getStatus() == null) {
            e.setStatus(1);
        }
        if (e.getSortNo() == null) {
            e.setSortNo(0);
        }
        if (e.getCtlFlag() == null) {
            e.setCtlFlag(0);
        }
        if (e.getCtlHard() == null) {
            e.setCtlHard(0);
        }
        if (!StringUtils.hasText(e.getDiscountMode())) {
            e.setDiscountMode("NONE");
        }
        if (!StringUtils.hasText(e.getScope())) {
            e.setScope("BOTH");
        }
    }

    /** 编码唯一按机构判定(与 uk_fee_type_org_code(tenant_id,org_id,code) 口径一致) */
    private boolean existsCode(Long orgId, String code, Long excludeId) {
        QueryWrapper<HisFeeTypeDict> qw = new QueryWrapper<>();
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
