package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.entity.inpatient.HisEmrTemplateVersion;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.EmrTemplateVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 病历模板版本快照服务(高级版): 为模板保存/发布/回滚留存 document/fields/printConfig/lockedSections 四载荷,
 * 提供版本列表(轻量, 不回大字段)、单版本详情与两版取数(逐行 diff 由前端 flattenDoc 承担)。
 *
 * 约定:
 * - snapshotBy 由 {@code EmrTemplateService} 在守卫与写库成功后调用(权限判定在调用方收敛, 本服务不重复守卫);
 * - 版本追加式留存, 回滚生成新版本而非覆盖历史(验收口径);
 * - tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 读隔离天然按租户。
 */
@Slf4j
@Service
public class EmrTemplateVersionService {

    private final EmrTemplateVersionMapper versionMapper;

    public EmrTemplateVersionService(EmrTemplateVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    /** 落一条版本快照(取当前模板行四载荷 + 版本号), operateType: save/publish/rollback。 */
    public void snapshot(HisEmrTemplate state, String summary, String operateType) {
        if (state == null || state.getId() == null || state.getVersion() == null) {
            return;
        }
        LoginUser lu = UserContext.get();
        HisEmrTemplateVersion v = new HisEmrTemplateVersion();
        v.setOrgId(state.getOrgId());
        v.setTemplateId(state.getId());
        v.setVersionNo(state.getVersion());
        v.setDocument(state.getDocument());
        v.setFields(state.getFields());
        v.setPrintConfig(state.getPrintConfig());
        v.setLockedSections(state.getLockedSections());
        v.setChangeSummary(summary);
        v.setOperatorId(lu != null ? lu.getUserId() : null);
        v.setOperatorName(lu != null ? lu.getRealName() : null);
        v.setOperateType(operateType == null ? "save" : operateType);
        versionMapper.insert(v);
    }

    /** 版本列表(轻量元数据: id/versionNo/operateType/变更说明/操作人/时间, 不回 document/fields 等大字段)。 */
    public R<List<HisEmrTemplateVersion>> list(Long templateId) {
        if (templateId == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        List<HisEmrTemplateVersion> rows = versionMapper.selectList(
                Wrappers.<HisEmrTemplateVersion>lambdaQuery()
                        .select(HisEmrTemplateVersion::getId, HisEmrTemplateVersion::getTemplateId,
                                HisEmrTemplateVersion::getVersionNo, HisEmrTemplateVersion::getOperateType,
                                HisEmrTemplateVersion::getChangeSummary, HisEmrTemplateVersion::getOperatorName,
                                HisEmrTemplateVersion::getCreateTime)
                        .eq(HisEmrTemplateVersion::getTemplateId, templateId)
                        .orderByDesc(HisEmrTemplateVersion::getVersionNo)
                        .orderByDesc(HisEmrTemplateVersion::getId));
        return R.ok(rows);
    }

    /** 单版本详情(含 document/fields 四载荷, 供对比与回滚取数)。 */
    public R<HisEmrTemplateVersion> getVersion(Long versionId) {
        HisEmrTemplateVersion v = versionId == null ? null : versionMapper.selectById(versionId);
        if (v == null) {
            throw new BizException(400, "模板版本不存在");
        }
        return R.ok(v);
    }

    /** 按模板ID+版本号取快照(回滚内部用, 找不到抛 400)。 */
    public HisEmrTemplateVersion getVersionByNo(Long templateId, Integer versionNo) {
        List<HisEmrTemplateVersion> rows = versionMapper.selectList(
                Wrappers.<HisEmrTemplateVersion>lambdaQuery()
                        .eq(HisEmrTemplateVersion::getTemplateId, templateId)
                        .eq(HisEmrTemplateVersion::getVersionNo, versionNo)
                        .orderByDesc(HisEmrTemplateVersion::getId)
                        .last("LIMIT 1"));
        if (rows.isEmpty()) {
            throw new BizException(400, "模板版本不存在: version=" + versionNo);
        }
        return rows.get(0);
    }
}
