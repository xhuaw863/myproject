package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrAnnotation;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.EmrAnnotationMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 病历批注与修订线程服务(高级版): 维护 his_emr_annotation, 供设计器/文书协作审阅。
 *
 * 约定:
 * - 修订留痕本体在文档内以 emrTrack mark 表达并随模板保存, 本服务只存批注文本、回复与修订元数据线程;
 * - 批注是协作行为, 目标(模板/病历)的可访问者均可新增/回复/解决, 不加牵头写守卫(读由租户插件隔离);
 * - target_type=template 时 target_id=模板id; target_type=record 时 target_id=病历/就诊id。
 */
@Slf4j
@Service
public class EmrAnnotationService {

    private final EmrAnnotationMapper annotationMapper;
    private final OrgAccessGuard guard;

    public EmrAnnotationService(EmrAnnotationMapper annotationMapper, OrgAccessGuard guard) {
        this.annotationMapper = annotationMapper;
        this.guard = guard;
    }

    /** 拉取某目标的全部批注线程(按创建时序; 含 open 与 resolved)。 */
    public R<List<HisEmrAnnotation>> listByTarget(String targetType, Long targetId) {
        if (!StringUtils.hasText(targetType) || targetId == null) {
            throw new BizException(400, "targetType/targetId 不能为空");
        }
        List<HisEmrAnnotation> rows = annotationMapper.selectList(Wrappers.<HisEmrAnnotation>lambdaQuery()
                .eq(HisEmrAnnotation::getTargetType, targetType)
                .eq(HisEmrAnnotation::getTargetId, targetId)
                .orderByAsc(HisEmrAnnotation::getCreateTime)
                .orderByAsc(HisEmrAnnotation::getId));
        return R.ok(rows);
    }

    /** 新增批注/回复(annoType=comment 为主线程; parentId 非空=回复)。 */
    public R<HisEmrAnnotation> add(HisEmrAnnotation in) {
        if (in == null || !StringUtils.hasText(in.getTargetType()) || in.getTargetId() == null) {
            throw new BizException(400, "批注目标(targetType/targetId)不能为空");
        }
        if (!StringUtils.hasText(in.getContent())) {
            throw new BizException(400, "批注内容不能为空");
        }
        LoginUser lu = UserContext.get();
        HisEmrAnnotation a = new HisEmrAnnotation();
        a.setOrgId(guard.currentOrgId());
        a.setTargetType(in.getTargetType());
        a.setTargetId(in.getTargetId());
        a.setAnnoType(StringUtils.hasText(in.getAnnoType()) ? in.getAnnoType() : "comment");
        a.setAnchor(in.getAnchor());
        a.setContent(in.getContent());
        a.setParentId(in.getParentId());
        a.setStatus(in.getParentId() != null ? null : "open");
        a.setAuthorId(lu != null ? lu.getUserId() : null);
        a.setAuthorName(lu != null ? lu.getRealName() : null);
        annotationMapper.insert(a);
        return R.ok(annotationMapper.selectById(a.getId()));
    }

    /** 标记批注为已解决/重新打开(仅根线程有状态语义)。 */
    public R<Void> setStatus(Long id, String status) {
        HisEmrAnnotation exist = id == null ? null : annotationMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "批注不存在");
        }
        if (!"open".equals(status) && !"resolved".equals(status)) {
            throw new BizException(400, "status 仅支持 open/resolved");
        }
        HisEmrAnnotation upd = new HisEmrAnnotation();
        upd.setId(id);
        upd.setStatus(status);
        annotationMapper.updateById(upd);
        return R.ok();
    }

    /** 删除批注(逻辑删除; 仅作者本人)。 */
    public R<Void> delete(Long id) {
        HisEmrAnnotation exist = id == null ? null : annotationMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "批注不存在");
        }
        LoginUser lu = UserContext.get();
        boolean owner = lu != null && exist.getAuthorId() != null && exist.getAuthorId().equals(lu.getUserId());
        boolean admin = lu != null && lu.hasAnyRole(com.yb.hi.framework.common.Roles.ADMIN,
                com.yb.hi.framework.common.Roles.SUPER_ADMIN);
        if (!owner && !admin) {
            throw new BizException(403, "仅批注作者或管理员可删除");
        }
        annotationMapper.deleteById(id);
        return R.ok();
    }
}
