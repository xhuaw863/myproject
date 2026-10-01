package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.entity.mr.HisMrAnnotation;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrAnnotationMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 病案批注与反馈服务(P1-C): 编目员与责任编码员围绕某份病案的沟通线程(parentId 回复串联)。
 * 铁律: 不回写临床首页, 仅在编目侧记录; 归属机构取自编目快照, 写操作 requireSelfOrgWrite。
 */
@Slf4j
@Service
public class MrAnnotationService {

    private final HisMrAnnotationMapper annMapper;
    private final MrCatalogService catalogService;
    private final OrgAccessGuard guard;

    public MrAnnotationService(HisMrAnnotationMapper annMapper, MrCatalogService catalogService, OrgAccessGuard guard) {
        this.annMapper = annMapper;
        this.catalogService = catalogService;
        this.guard = guard;
    }

    /** 某病案全部批注(顶层+回复), 按时间升序; 前端按 parentId 组装线程。 */
    public List<HisMrAnnotation> listByVisit(Long visitId) {
        TenantContext.require();
        return annMapper.selectList(new QueryWrapper<HisMrAnnotation>()
                .eq("visit_id", visitId).orderByAsc("create_time").orderByAsc("id"));
    }

    /** 我的未处理批注(接收人=当前登录职工, resolved=0), 供反馈提醒。 */
    public List<HisMrAnnotation> myTodo() {
        TenantContext.require();
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getStaffId() == null) {
            return java.util.Collections.emptyList();
        }
        return annMapper.selectList(new QueryWrapper<HisMrAnnotation>()
                .eq("to_staff_id", lu.getStaffId()).eq("resolved", 0)
                .orderByDesc("create_time").last("LIMIT 100"));
    }

    /** 新增批注/回复: body {visitId, parentId?, toStaffId?, toStaffName?, annType?, targetField?, content}。 */
    @Transactional(rollbackFor = Exception.class)
    public HisMrAnnotation create(Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        if (body == null) {
            throw new BizException(400, "请求体为空");
        }
        Long visitId = MrCatalogService.longOf(body.get("visitId"));
        if (visitId == null) {
            throw new BizException(400, "缺少就诊ID");
        }
        String content = MrCatalogService.strOf(body.get("content"));
        if (!StringUtils.hasText(content)) {
            throw new BizException(400, "批注内容不能为空");
        }
        HisMrCatalog c = catalogService.findByVisit(visitId);
        LoginUser lu = UserContext.get();

        HisMrAnnotation a = new HisMrAnnotation();
        a.setVisitId(visitId);
        a.setCatalogId(c == null ? null : c.getId());
        a.setOrgId(c == null ? (lu == null ? null : lu.getOrgId()) : c.getOrgId());
        Long parentId = MrCatalogService.longOf(body.get("parentId"));
        if (parentId != null) {
            HisMrAnnotation parent = annMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException(404, "被回复的批注不存在");
            }
            a.setParentId(parentId);
            // 回复默认回给父批注发起人
            a.setToStaffId(parent.getFromStaffId());
            a.setToStaffName(parent.getFromStaffName());
        }
        a.setFromStaffId(lu == null ? null : lu.getStaffId());
        a.setFromStaffName(lu == null ? null : lu.getRealName());
        if (body.containsKey("toStaffId")) {
            a.setToStaffId(MrCatalogService.longOf(body.get("toStaffId")));
            a.setToStaffName(MrCatalogService.strOf(body.get("toStaffName")));
        }
        a.setAnnType(StringUtils.hasText(MrCatalogService.strOf(body.get("annType")))
                ? MrCatalogService.strOf(body.get("annType")) : "feedback");
        a.setTargetField(MrCatalogService.strOf(body.get("targetField")));
        a.setContent(content);
        a.setResolved(0);
        annMapper.insert(a);
        return a;
    }

    /** 标记处理状态: resolved 0未处理 1已处理。 */
    @Transactional(rollbackFor = Exception.class)
    public void setResolved(Long id, Integer resolved) {
        guard.requireSelfOrgWrite();
        HisMrAnnotation a = annMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "批注不存在");
        }
        a.setResolved(resolved != null && resolved == 1 ? 1 : 0);
        annMapper.updateById(a);
    }

    /** 删除批注(逻辑删, 连同其直接回复一并删除)。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        guard.requireSelfOrgWrite();
        HisMrAnnotation a = annMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "批注不存在");
        }
        annMapper.delete(new QueryWrapper<HisMrAnnotation>().eq("parent_id", id));
        annMapper.deleteById(id);
    }
}
