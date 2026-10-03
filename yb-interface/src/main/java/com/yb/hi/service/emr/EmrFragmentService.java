package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisEmrFragment;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.EmrFragmentMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 病历片段服务: 维护可复用文档块(his_emr_fragment, 全院/科室/个人三级作用域)。
 *
 * 约定:
 * - 写操作(新建/更新/删除)仅牵头机构管理员({@link OrgAccessGuard#requireLeadOrg}), 新建行绑定当前机构;
 *   读操作(列表/详情/批量解析)受 MyBatis-Plus 租户插件隔离(tenant_id 自动注入/过滤, 实体不显式映射);
 * - 片段编码租户内唯一(应用层预检, 友好 400); 更新时版本号自动 +1(留存内容演进);
 * - {@link #resolveFragments} 为打印/导出展开口径: 仅返回启用(status=1)且文档非空的片段,
 *   已停用/已删除/文档空片段不产出条目, 由调用方保留引用占位节点。
 */
@Slf4j
@Service
public class EmrFragmentService {

    private final EmrFragmentMapper fragmentMapper;
    private final OrgAccessGuard guard;

    public EmrFragmentService(EmrFragmentMapper fragmentMapper, OrgAccessGuard guard) {
        this.fragmentMapper = fragmentMapper;
        this.guard = guard;
    }

    /* ================= 查询 ================= */

    /** 片段分页列表(scopeLevel/deptId/staffId 可选精确过滤, keyword 模糊匹配编码/名称; 按作用域层级→ID 升序) */
    public R<IPage<HisEmrFragment>> list(Integer scopeLevel, Long deptId, Long staffId,
                                         String keyword, long page, long size) {
        LambdaQueryWrapper<HisEmrFragment> qw = Wrappers.<HisEmrFragment>lambdaQuery()
                .eq(scopeLevel != null, HisEmrFragment::getScopeLevel, scopeLevel)
                .eq(deptId != null, HisEmrFragment::getDeptId, deptId)
                .eq(staffId != null, HisEmrFragment::getStaffId, staffId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(HisEmrFragment::getCode, kw).or().like(HisEmrFragment::getTitle, kw));
        }
        qw.orderByAsc(HisEmrFragment::getScopeLevel).orderByAsc(HisEmrFragment::getId);
        long p = page <= 0 ? 1 : page;
        long s = size <= 0 ? 20 : Math.min(size, 200);
        return R.ok(fragmentMapper.selectPage(new Page<>(p, s), qw));
    }

    /** 片段详情(含 document 内容) */
    public R<HisEmrFragment> get(Long id) {
        HisEmrFragment f = id == null ? null : fragmentMapper.selectById(id);
        if (f == null) {
            throw new BizException(400, "病历片段不存在");
        }
        return R.ok(f);
    }

    /* ================= 维护 ================= */

    /** 新建/更新片段(id 空=新建并绑定当前机构; 编码租户内唯一; 更新时 version 自增; 仅牵头机构管理员) */
    public R<HisEmrFragment> save(HisEmrFragment in) {
        if (in == null) {
            throw new BizException(400, "片段内容不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护病历片段");
        if (!StringUtils.hasText(in.getCode())) {
            throw new BizException(400, "片段编码不能为空");
        }
        if (!StringUtils.hasText(in.getTitle())) {
            throw new BizException(400, "片段名称不能为空");
        }
        String code = in.getCode().trim();
        if (in.getId() == null) {
            ensureCodeAvailable(code, null);
            HisEmrFragment f = new HisEmrFragment();
            f.setOrgId(guard.currentOrgId());
            f.setCode(code);
            f.setTitle(in.getTitle().trim());
            f.setScopeLevel(in.getScopeLevel() != null ? in.getScopeLevel() : 0);
            f.setDeptId(in.getDeptId());
            f.setStaffId(in.getStaffId());
            f.setDocument(in.getDocument());
            f.setVersion(in.getVersion() != null ? in.getVersion() : 1);
            f.setStatus(in.getStatus() != null ? in.getStatus() : 1);
            fragmentMapper.insert(f);
            log.info("新建病历片段: id={}, code={}, scopeLevel={}", f.getId(), code, f.getScopeLevel());
            return R.ok(fragmentMapper.selectById(f.getId()));
        }
        HisEmrFragment exist = fragmentMapper.selectById(in.getId());
        if (exist == null) {
            throw new BizException(400, "病历片段不存在");
        }
        if (!code.equals(exist.getCode())) {
            ensureCodeAvailable(code, exist.getId());
            exist.setCode(code);
        }
        exist.setTitle(in.getTitle().trim());
        if (in.getScopeLevel() != null) {
            exist.setScopeLevel(in.getScopeLevel());
        }
        if (in.getDeptId() != null) {
            exist.setDeptId(in.getDeptId());
        }
        if (in.getStaffId() != null) {
            exist.setStaffId(in.getStaffId());
        }
        if (in.getDocument() != null) {
            exist.setDocument(in.getDocument());
        }
        if (in.getStatus() != null) {
            exist.setStatus(in.getStatus());
        }
        // 版本号自增: 每次更新留存内容演进痕迹(引用方按 fragmentId 展开恒取最新版本)
        exist.setVersion((exist.getVersion() != null ? exist.getVersion() : 1) + 1);
        fragmentMapper.updateById(exist);
        log.info("更新病历片段: id={}, code={}, version={}", exist.getId(), exist.getCode(), exist.getVersion());
        return R.ok(fragmentMapper.selectById(exist.getId()));
    }

    /** 删除片段(逻辑删除; 仅牵头机构管理员) */
    public R<Void> delete(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历片段");
        HisEmrFragment exist = id == null ? null : fragmentMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历片段不存在");
        }
        fragmentMapper.deleteById(id);
        log.info("删除病历片段: id={}, code={}", id, exist.getCode());
        return R.ok();
    }

    /* ================= 打印/导出展开 ================= */

    /**
     * 批量解析片段内容(打印展开用): 返回 fragmentId→document JSON。
     * 仅解析启用(status=1)且文档非空的片段; 无效/停用/已删除ID不产出条目(调用方保留占位节点)。
     * 读操作, 不受牵头机构写守卫约束(医生打印需要)。
     */
    public Map<Long, String> resolveFragments(List<Long> fragmentIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (fragmentIds == null || fragmentIds.isEmpty()) {
            return out;
        }
        List<Long> ids = fragmentIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return out;
        }
        List<HisEmrFragment> rows = fragmentMapper.selectList(Wrappers.<HisEmrFragment>lambdaQuery()
                .in(HisEmrFragment::getId, ids)
                .eq(HisEmrFragment::getStatus, 1));
        for (HisEmrFragment f : rows) {
            if (StringUtils.hasText(f.getDocument())) {
                out.put(f.getId(), f.getDocument());
            }
        }
        if (out.size() < ids.size()) {
            log.info("片段批量解析: 请求{}个, 命中{}个(停用/已删/文档空片段不展开)", ids.size(), out.size());
        }
        return out;
    }

    /* ================= 内部实现 ================= */

    /** 片段编码租户内查重(逻辑删除行不参与) */
    private void ensureCodeAvailable(String code, Long excludeId) {
        boolean dup = !fragmentMapper.selectList(Wrappers.<HisEmrFragment>lambdaQuery()
                .eq(HisEmrFragment::getCode, code)
                .ne(excludeId != null, HisEmrFragment::getId, excludeId)).isEmpty();
        if (dup) {
            throw new BizException(400, "片段编码已存在: " + code);
        }
    }
}
