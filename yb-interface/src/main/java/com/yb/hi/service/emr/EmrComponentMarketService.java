package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrComponentShare;
import com.yb.hi.entity.inpatient.HisEmrDrawingTemplate;
import com.yb.hi.entity.inpatient.HisEmrFragment;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.EmrComponentShareMapper;
import com.yb.hi.mapper.inpatient.EmrDrawingTemplateMapper;
import com.yb.hi.mapper.inpatient.EmrFragmentMapper;
import com.yb.hi.mapper.inpatient.HisEmrTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历组件与模板市场服务(高级版): 目录登记(his_emr_component_share) + 克隆独立副本 + 引用反查分析。
 *
 * 约定:
 * - 上架(publish)只登记目录行, 不改母件; 下架/重新上架由登记人或管理员操作;
 * - 克隆(cloneToMine)按 comp_type 生成归属克隆者的全新独立副本(编码重排、母件 download_count+1), 之后与母件脱钩;
 * - 引用分析(referenceAnalysis)走 his_emr_ref_index 反查引用某片段/数据元/图示/宏的全部模板;
 * - 读(市场浏览/引用分析)由租户插件隔离; 写(上架/下架)要求登录态, 全院级额外要求牵头机构。
 */
@Slf4j
@Service
public class EmrComponentMarketService {

    private final EmrComponentShareMapper shareMapper;
    private final EmrFragmentMapper fragmentMapper;
    private final EmrDrawingTemplateMapper drawingMapper;
    private final HisEmrTemplateMapper templateMapper;
    private final EmrRefIndexService refIndexService;
    private final OrgAccessGuard guard;

    public EmrComponentMarketService(EmrComponentShareMapper shareMapper, EmrFragmentMapper fragmentMapper,
                                     EmrDrawingTemplateMapper drawingMapper, HisEmrTemplateMapper templateMapper,
                                     EmrRefIndexService refIndexService, OrgAccessGuard guard) {
        this.shareMapper = shareMapper;
        this.fragmentMapper = fragmentMapper;
        this.drawingMapper = drawingMapper;
        this.templateMapper = templateMapper;
        this.refIndexService = refIndexService;
        this.guard = guard;
    }

    /* ================= 市场浏览 ================= */

    /** 市场目录列表(compType/category/keyword 可选, 仅上架行; 按下载量降序→时间降序) */
    public R<List<HisEmrComponentShare>> listMarket(String compType, String category, String keyword) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisEmrComponentShare> qw =
                Wrappers.<HisEmrComponentShare>lambdaQuery()
                        .eq(HisEmrComponentShare::getStatus, 1)
                        .eq(StringUtils.hasText(compType), HisEmrComponentShare::getCompType, compType)
                        .eq(StringUtils.hasText(category), HisEmrComponentShare::getCategory, category);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(HisEmrComponentShare::getTitle, kw).or().like(HisEmrComponentShare::getSummary, kw));
        }
        qw.orderByDesc(HisEmrComponentShare::getDownloadCount).orderByDesc(HisEmrComponentShare::getId);
        return R.ok(shareMapper.selectList(qw));
    }

    /* ================= 上架/下架 ================= */

    /**
     * 上架母件为可共享组件(幂等: 同 compType+refId 已有登记行则更新元信息并重新上架)。
     * fragment/drawing 母件写守卫沿用片段服务的牵头机构口径由调用方保证; template 要求已发布且启用。
     */
    public R<HisEmrComponentShare> publish(HisEmrComponentShare in) {
        if (in == null || !StringUtils.hasText(in.getCompType()) || in.getRefId() == null) {
            throw new BizException(400, "compType/refId 不能为空");
        }
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        String compType = in.getCompType().trim();
        HisEmrComponentShare row = shareMapper.selectOne(Wrappers.<HisEmrComponentShare>lambdaQuery()
                .eq(HisEmrComponentShare::getCompType, compType)
                .eq(HisEmrComponentShare::getRefId, in.getRefId())
                .last("LIMIT 1"));
        boolean isNew = row == null;
        if (isNew) {
            row = new HisEmrComponentShare();
            row.setCompType(compType);
            row.setRefId(in.getRefId());
            row.setDownloadCount(0);
        }
        switch (compType) {
            case "fragment": {
                HisEmrFragment f = fragmentMapper.selectById(in.getRefId());
                if (f == null) {
                    throw new BizException(400, "片段母件不存在");
                }
                row.setTitle(f.getTitle());
                row.setScopeLevel(f.getScopeLevel());
                row.setDeptId(f.getDeptId());
                break;
            }
            case "drawing": {
                HisEmrDrawingTemplate d = drawingMapper.selectById(in.getRefId());
                if (d == null) {
                    throw new BizException(400, "图示母件不存在");
                }
                row.setTitle(d.getTitle());
                row.setCategory(StringUtils.hasText(in.getCategory()) ? in.getCategory() : d.getCategory());
                row.setScopeLevel(0);
                break;
            }
            case "template": {
                HisEmrTemplate t = templateMapper.selectById(in.getRefId());
                if (t == null) {
                    throw new BizException(400, "模板母件不存在");
                }
                boolean published = t.getPublishStatus() == null || (t.getPublishStatus() == 3);
                if (!published || (t.getStatus() != null && t.getStatus() != 1)) {
                    throw new BizException(400, "仅已发布且启用的模板可上架市场");
                }
                if (t.getScopeLevel() != null && t.getScopeLevel() == 2) {
                    throw new BizException(400, "个人模板须先提升为科室/全院模板后方可上架");
                }
                row.setTitle(t.getTemplateName());
                row.setScopeLevel(t.getScopeLevel());
                row.setDeptId(t.getDeptId());
                if (!StringUtils.hasText(row.getCategory()) && t.getTemplateCategory() != null) {
                    row.setCategory("cat_" + t.getTemplateCategory());
                }
                break;
            }
            default:
                throw new BizException(400, "不支持的组件类型: " + compType);
        }
        if (StringUtils.hasText(in.getCategory()) && !"template".equals(compType)) {
            row.setCategory(in.getCategory());
        }
        row.setSummary(in.getSummary() != null ? in.getSummary() : row.getSummary());
        row.setShareScope(in.getShareScope() != null ? in.getShareScope()
                : (row.getShareScope() != null ? row.getShareScope() : 0));
        row.setStatus(1);
        if (isNew) {
            row.setOrgId(guard.currentOrgId());
            row.setSourceOrgId(guard.currentOrgId());
            shareMapper.insert(row);
            log.info("市场上架组件: id={}, compType={}, refId={}", row.getId(), compType, in.getRefId());
        } else {
            shareMapper.updateById(row);
            log.info("市场重新上架组件: id={}, compType={}, refId={}", row.getId(), compType, in.getRefId());
        }
        return R.ok(shareMapper.selectById(row.getId()));
    }

    /** 下架(登记人本人或管理员)。 */
    public R<Void> offline(Long shareId) {
        HisEmrComponentShare exist = shareId == null ? null : shareMapper.selectById(shareId);
        if (exist == null) {
            throw new BizException(400, "市场登记不存在");
        }
        LoginUser lu = UserContext.get();
        boolean admin = lu != null && lu.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN);
        boolean selfOwned = lu != null && exist.getCreateBy() != null && exist.getCreateBy().equals(lu.getUsername());
        if (!admin && !selfOwned) {
            throw new BizException(403, "仅登记人或管理员可下架");
        }
        HisEmrComponentShare upd = new HisEmrComponentShare();
        upd.setId(shareId);
        upd.setStatus(0);
        shareMapper.updateById(upd);
        return R.ok();
    }

    /* ================= 克隆为独立副本 ================= */

    /**
     * 克隆市场组件为克隆者自己的独立副本: targetScope 2个人(默认)/1科室(牵头机构)/0全院(管理员)。
     * 片段→新 his_emr_fragment, 图示→新 his_emr_drawing_template, 模板→新 his_emr_template; 均编码重排、不改母件。
     */
    public R<Map<String, Object>> cloneToMine(Long shareId, Integer targetScope) {
        HisEmrComponentShare share = shareId == null ? null : shareMapper.selectById(shareId);
        if (share == null || share.getStatus() == null || share.getStatus() != 1) {
            throw new BizException(400, "市场组件不存在或已下架");
        }
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        int scope = targetScope != null ? targetScope : 2;
        if (scope != 2 && scope != 1 && scope != 0) {
            throw new BizException(400, "targetScope 仅支持 0全院/1科室/2个人");
        }
        if (scope <= 1) {
            guard.requireLeadOrg("克隆为科室/全院组件需牵头机构权限");
        }
        Object copy = null;
        String suffix = "_CP" + System.currentTimeMillis();
        switch (share.getCompType() == null ? "" : share.getCompType()) {
            case "fragment": {
                HisEmrFragment src = fragmentMapper.selectById(share.getRefId());
                if (src == null || !StringUtils.hasText(src.getDocument())) {
                    throw new BizException(400, "母件片段已失效");
                }
                HisEmrFragment f = new HisEmrFragment();
                f.setOrgId(guard.currentOrgId());
                f.setCode(src.getCode() + suffix);
                f.setTitle(src.getTitle() + "(副本)");
                f.setScopeLevel(scope);
                f.setDeptId(scope == 1 ? lu.getDeptId() : (scope == 0 ? 0L : null));
                f.setStaffId(scope == 2 ? lu.getStaffId() : null);
                f.setDocument(src.getDocument());
                f.setVersion(1);
                f.setStatus(1);
                fragmentMapper.insert(f);
                copy = f;
                break;
            }
            case "drawing": {
                HisEmrDrawingTemplate src = drawingMapper.selectById(share.getRefId());
                if (src == null || !StringUtils.hasText(src.getSvgTemplate())) {
                    throw new BizException(400, "母件图示已失效");
                }
                HisEmrDrawingTemplate d = new HisEmrDrawingTemplate();
                d.setOrgId(guard.currentOrgId());
                d.setCode(src.getCode() + suffix);
                d.setTitle(src.getTitle() + "(副本)");
                d.setCategory(src.getCategory());
                d.setSvgTemplate(src.getSvgTemplate());
                d.setDescription(src.getDescription());
                d.setStatus(1);
                drawingMapper.insert(d);
                copy = d;
                break;
            }
            case "template": {
                HisEmrTemplate src = templateMapper.selectById(share.getRefId());
                if (src == null) {
                    throw new BizException(400, "母件模板已失效");
                }
                HisEmrTemplate t = new HisEmrTemplate();
                t.setOrgId(guard.currentOrgId());
                t.setTemplateCode(src.getTemplateCode() + suffix);
                t.setTemplateName(src.getTemplateName() + "(副本)");
                t.setRecordType(src.getRecordType());
                t.setTemplateCategory(src.getTemplateCategory());
                t.setFields(src.getFields());
                t.setScope(src.getScope());
                t.setLayout(src.getLayout());
                t.setParentTemplateId(null);
                t.setScopeLevel(scope);
                t.setLockedSections(scope == 0 ? src.getLockedSections() : null);
                t.setDocument(src.getDocument());
                t.setPrintScript(src.getPrintScript());
                t.setPrintConfig(src.getPrintConfig());
                t.setDatasetId(src.getDatasetId());
                t.setStaffId(scope == 2 ? lu.getStaffId() : null);
                t.setDeptId(scope == 1 ? lu.getDeptId() : (scope == 0 ? 0L : null));
                t.setVersion(1);
                t.setStatus(1);
                // 克隆副本视为个人/科室草稿工作件: 全院级直接已发布, 其余默认草稿(不污染书写器可选集)
                t.setPublishStatus(scope == 0 ? 3 : 0);
                templateMapper.insert(t);
                copy = t;
                break;
            }
            default:
                throw new BizException(400, "该组件类型暂不支持克隆: " + share.getCompType());
        }
        // 下载计数 +1(冗余自增, 失败不影响克隆结果)
        try {
            HisEmrComponentShare upd = new HisEmrComponentShare();
            upd.setId(share.getId());
            upd.setDownloadCount((share.getDownloadCount() != null ? share.getDownloadCount() : 0) + 1);
            shareMapper.updateById(upd);
        } catch (Exception e) {
            log.warn("市场组件克隆计数更新失败: id={}, {}", share.getId(), e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compType", share.getCompType());
        out.put("sourceShareId", share.getId());
        out.put("copy", copy);
        log.info("市场克隆: shareId={}, compType={}, targetScope={}, by={}", shareId, share.getCompType(), scope, lu.getUserId());
        return R.ok(out);
    }

    /* ================= 引用反查分析 ================= */

    /** 引用分析: 某片段/数据元/图示/宏被哪些模板引用(his_emr_ref_index 反查, 返回模板轻量列表)。 */
    public R<List<HisEmrTemplate>> referenceAnalysis(String refType, String refKey) {
        if (!StringUtils.hasText(refType) || !StringUtils.hasText(refKey)) {
            throw new BizException(400, "refType/refKey 不能为空");
        }
        List<Long> ids = refIndexService.findSourceTemplateIds(refType.trim(), refKey.trim());
        if (ids.isEmpty()) {
            return R.ok(java.util.Collections.emptyList());
        }
        List<HisEmrTemplate> rows = templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .select(HisEmrTemplate::getId, HisEmrTemplate::getTemplateCode, HisEmrTemplate::getTemplateName,
                        HisEmrTemplate::getScopeLevel, HisEmrTemplate::getDeptId, HisEmrTemplate::getStaffId,
                        HisEmrTemplate::getVersion, HisEmrTemplate::getStatus, HisEmrTemplate::getPublishStatus)
                .in(HisEmrTemplate::getId, ids));
        return R.ok(rows);
    }
}
