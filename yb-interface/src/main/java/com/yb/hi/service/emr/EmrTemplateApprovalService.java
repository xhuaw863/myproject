package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrTemplateApproval;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.EmrTemplateApprovalMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 病历模板发布审批流水服务(高级版): 记录 提交→通过/驳回 的状态迁移留痕(his_emr_template_approval)。
 * 状态迁移与权限守卫在 {@code EmrTemplateService} 收敛, 本服务仅负责流水行写入与检索。
 * tenant_id 由租户插件注入; org_id 取当前登录机构。
 */
@Slf4j
@Service
public class EmrTemplateApprovalService {

    private final EmrTemplateApprovalMapper approvalMapper;
    private final OrgAccessGuard guard;

    public EmrTemplateApprovalService(EmrTemplateApprovalMapper approvalMapper, OrgAccessGuard guard) {
        this.approvalMapper = approvalMapper;
        this.guard = guard;
    }

    /** 记录一次提交(待审): 落提交人/时间, 审核字段留空。 */
    public void recordSubmit(Long templateId, Integer fromStatus) {
        LoginUser lu = UserContext.get();
        HisEmrTemplateApproval a = new HisEmrTemplateApproval();
        a.setOrgId(safeOrgId());
        a.setTemplateId(templateId);
        a.setFromStatus(fromStatus);
        a.setToStatus(1);
        a.setSubmitUserId(lu != null ? lu.getUserId() : null);
        a.setSubmitUserName(lu != null ? lu.getRealName() : null);
        a.setSubmitTime(LocalDateTime.now());
        approvalMapper.insert(a);
    }

    /** 记录一次审核(pass/reject): 复用最近一条未审核提交行回填审核字段; 无则新建。 */
    public void recordReview(Long templateId, Integer fromStatus, Integer toStatus, String action, String opinion) {
        LoginUser lu = UserContext.get();
        List<HisEmrTemplateApproval> pend = approvalMapper.selectList(Wrappers.<HisEmrTemplateApproval>lambdaQuery()
                .eq(HisEmrTemplateApproval::getTemplateId, templateId)
                .isNull(HisEmrTemplateApproval::getReviewAction)
                .orderByDesc(HisEmrTemplateApproval::getId)
                .last("LIMIT 1"));
        HisEmrTemplateApproval a;
        if (!pend.isEmpty()) {
            a = pend.get(0);
        } else {
            a = new HisEmrTemplateApproval();
            a.setOrgId(safeOrgId());
            a.setTemplateId(templateId);
            a.setFromStatus(fromStatus);
        }
        a.setToStatus(toStatus);
        a.setReviewUserId(lu != null ? lu.getUserId() : null);
        a.setReviewUserName(lu != null ? lu.getRealName() : null);
        a.setReviewTime(LocalDateTime.now());
        a.setReviewAction(action);
        a.setReviewOpinion(opinion);
        if (a.getId() == null) {
            approvalMapper.insert(a);
        } else {
            approvalMapper.updateById(a);
        }
    }

    /** 某模板的审批历史(按时间倒序)。 */
    public R<List<HisEmrTemplateApproval>> history(Long templateId) {
        List<HisEmrTemplateApproval> rows = approvalMapper.selectList(Wrappers.<HisEmrTemplateApproval>lambdaQuery()
                .eq(HisEmrTemplateApproval::getTemplateId, templateId)
                .orderByDesc(HisEmrTemplateApproval::getId));
        return R.ok(rows);
    }

    private Long safeOrgId() {
        try {
            return guard.currentOrgId();
        } catch (Exception e) {
            return null;
        }
    }
}
