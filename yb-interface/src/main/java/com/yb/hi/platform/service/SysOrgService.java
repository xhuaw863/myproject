package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.OrgNode;
import com.yb.hi.platform.dto.OrgSaveReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.mapper.SysUserMapper;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构服务(医共体内县/乡/村三级树)。
 * sys_org 走租户插件自动过滤, 同医共体(租户)内所有机构共享一套机构树。
 * org_id 在业务表中仅作归属标注, 不参与隔离。
 */
@Service
public class SysOrgService {

    private final SysOrgMapper orgMapper;
    private final SysUserMapper userMapper;
    private final StdDictQueryService stdDict;
    private final SysTenantService tenantService;

    public SysOrgService(SysOrgMapper orgMapper, SysUserMapper userMapper, StdDictQueryService stdDict,
                         SysTenantService tenantService) {
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
        this.stdDict = stdDict;
        this.tenantService = tenantService;
    }

    /** 全部机构(当前租户, 按 sort_no,id) */
    public List<SysOrg> listAll() {
        return orgMapper.selectList(new QueryWrapper<SysOrg>().orderByAsc("sort_no", "id"));
    }

    /** 机构树 */
    public List<OrgNode> tree() {
        return buildTree(listAll());
    }

    /** 指定上级下的直接子机构 */
    public List<SysOrg> listByParent(Long parentId) {
        long pid = parentId == null ? 0L : parentId;
        return orgMapper.selectList(new QueryWrapper<SysOrg>()
                .eq("parent_id", pid).orderByAsc("sort_no", "id"));
    }

    /** 指定机构的子树机构 id 集合(含自身), 用于按上级机构级联过滤业务数据(如职工) */
    public List<Long> subtreeIds(Long rootId) {
        List<Long> out = new ArrayList<>();
        if (rootId == null) {
            return out;
        }
        out.add(rootId);
        List<SysOrg> all = listAll();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (SysOrg o : all) {
                if (o.getId() != null && o.getParentId() != null && out.contains(o.getParentId()) && !out.contains(o.getId())) {
                    out.add(o.getId());
                    grew = true;
                }
            }
        }
        return out;
    }

    public SysOrg getById(Long id) {
        return orgMapper.selectById(id);
    }

    /** 导出机构列表(xlsx 行集): 层级树 DFS 摊平, 与页面查询同口径(名称/编码/负责人关键字 + 级别 + 状态) */
    public Map<String, Object> exportRows(String keyword, Integer orgLevel, Integer status) {
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"机构名称", "机构编码", "上级机构", "级别", "牵头", "机构类型", "定点机构编号", "定点机构名称",
                "统一社会信用代码", "医院等级", "负责人", "联系电话", "机构地址", "编制床位数", "排序号", "状态"}) {
            head.add(Collections.singletonList(h));
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
        List<List<Object>> rows = new ArrayList<>();
        flattenForExport(tree(), null, rows, kw, orgLevel, status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /** DFS 摊平导出行: 命中才写入但仍递归子级(与前端树过滤保留层级上下文同口径) */
    private void flattenForExport(List<OrgNode> nodes, String parentName, List<List<Object>> rows,
                                  String kw, Integer orgLevel, Integer status) {
        for (OrgNode n : nodes) {
            if (matchExport(n, kw, orgLevel, status)) {
                rows.add(Arrays.asList(
                        nz(n.getOrgName()), nz(n.getOrgCode()), parentName == null ? "" : parentName,
                        orgLevelText(n.getOrgLevel()),
                        n.getIsLead() != null && n.getIsLead() == 1 ? "是" : "否",
                        StringUtils.hasText(n.getOrgTypeName()) ? n.getOrgTypeName() : nz(n.getOrgType()),
                        nz(n.getFixmedinsCode()), nz(n.getFixmedinsName()), nz(n.getUscc()),
                        StringUtils.hasText(n.getHospLvName()) ? n.getHospLvName() : nz(n.getHospLv()),
                        nz(n.getLeader()), nz(n.getPhone()), nz(n.getAddress()),
                        n.getBedCnt(), n.getSortNo(),
                        n.getStatus() != null && n.getStatus() == 1 ? "启用" : "停用"));
            }
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                flattenForExport(n.getChildren(), n.getOrgName(), rows, kw, orgLevel, status);
            }
        }
    }

    private static boolean matchExport(OrgNode n, String kw, Integer orgLevel, Integer status) {
        if (kw != null && !nz(n.getOrgName()).toLowerCase().contains(kw)
                && !nz(n.getOrgCode()).toLowerCase().contains(kw)
                && !nz(n.getLeader()).toLowerCase().contains(kw)) {
            return false;
        }
        if (orgLevel != null && !orgLevel.equals(n.getOrgLevel())) {
            return false;
        }
        return status == null || status.equals(n.getStatus());
    }

    private static String orgLevelText(Integer lv) {
        if (lv == null) {
            return "";
        }
        switch (lv) {
            case 1: return "县级";
            case 2: return "乡镇";
            case 3: return "村";
            default: return String.valueOf(lv);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private List<OrgNode> buildTree(List<SysOrg> orgs) {
        List<OrgNode> nodes = new ArrayList<>();
        for (SysOrg o : orgs) {
            nodes.add(toNode(o));
        }
        List<OrgNode> roots = new ArrayList<>();
        for (OrgNode n : nodes) {
            Long pid = n.getParentId();
            OrgNode parent = pid == null || pid == 0L ? null : findNode(nodes, pid);
            if (parent != null) {
                parent.getChildren().add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }

    private OrgNode toNode(SysOrg o) {
        OrgNode n = new OrgNode();
        n.setId(o.getId());
        n.setParentId(o.getParentId());
        n.setOrgCode(o.getOrgCode());
        n.setOrgName(o.getOrgName());
        n.setOrgLevel(o.getOrgLevel());
        n.setIsLead(o.getIsLead());
        n.setOrgType(o.getOrgType());
        n.setOrgTypeName(o.getOrgTypeName());
        n.setOrgTypeSrc(o.getOrgTypeSrc());
        n.setFixmedinsCode(o.getFixmedinsCode());
        n.setFixmedinsName(o.getFixmedinsName());
        n.setUscc(o.getUscc());
        n.setFixmedinsType(o.getFixmedinsType());
        n.setFixmedinsTypeName(o.getFixmedinsTypeName());
        n.setFixmedinsTypeSrc(o.getFixmedinsTypeSrc());
        n.setHospLv(o.getHospLv());
        n.setHospLvName(o.getHospLvName());
        n.setHospLvSrc(o.getHospLvSrc());
        n.setPdLicenseNo(o.getPdLicenseNo());
        n.setBedCnt(o.getBedCnt());
        n.setPriceLv(o.getPriceLv());
        n.setAdmvsCode(o.getAdmvsCode());
        n.setLeader(o.getLeader());
        n.setPhone(o.getPhone());
        n.setAddress(o.getAddress());
        // 机构级医保接口配置(供编辑回显)
        n.setMdtrtareaAdmvs(o.getMdtrtareaAdmvs());
        n.setInsuplcAdmdvs(o.getInsuplcAdmdvs());
        n.setApiUrl(o.getApiUrl());
        n.setFileDownloadUrl(o.getFileDownloadUrl());
        n.setRecerSysCode(o.getRecerSysCode());
        n.setInfver(o.getInfver());
        n.setOpterType(o.getOpterType());
        n.setOpter(o.getOpter());
        n.setOpterName(o.getOpterName());
        n.setSignNo(o.getSignNo());
        n.setSm2PublicKey(o.getSm2PublicKey());
        n.setEncType(o.getEncType());
        n.setMockEnabled(o.getMockEnabled());
        n.setSortNo(o.getSortNo());
        n.setStatus(o.getStatus());
        return n;
    }

    private OrgNode findNode(List<OrgNode> nodes, Long id) {
        for (OrgNode n : nodes) {
            if (n.getId().equals(id)) {
                return n;
            }
        }
        return null;
    }

    /** 新增机构 */
    public Long create(OrgSaveReq req) {
        validate(req, true);
        SysOrg o = new SysOrg();
        applyReq(o, req);
        orgMapper.insert(o);
        return o.getId();
    }

    /** 修改机构 */
    public void update(OrgSaveReq req) {
        if (req.getId() == null) {
            throw new BizException(400, "机构ID不能为空");
        }
        validate(req, false);
        SysOrg o = orgMapper.selectById(req.getId());
        if (o == null) {
            throw new BizException("机构不存在");
        }
        applyReq(o, req);
        orgMapper.updateById(o);
    }

    /** 删除机构: 有下级机构或被用户引用则拒绝 */
    public void delete(Long id) {
        long children = orgMapper.selectCount(new QueryWrapper<SysOrg>().eq("parent_id", id));
        if (children > 0) {
            throw new BizException("存在下级机构, 不可删除");
        }
        long refs = userMapper.selectCount(new QueryWrapper<SysUser>().eq("org_id", id));
        if (refs > 0) {
            throw new BizException("该机构下存在用户, 不可删除");
        }
        orgMapper.deleteById(id);
    }

    private void applyReq(SysOrg o, OrgSaveReq req) {
        o.setOrgCode(req.getOrgCode());
        o.setOrgName(req.getOrgName());
        o.setOrgLevel(req.getOrgLevel() == null ? 1 : req.getOrgLevel());
        // 牵头标识: null=不变更(编辑表单未带时保留原值); 唯一性由 validate 保证
        if (req.getIsLead() != null) {
            o.setIsLead(req.getIsLead() == 1 ? 1 : 0);
        }
        o.setParentId(req.getParentId() == null ? 0L : req.getParentId());
        o.setOrgType(req.getOrgType());
        if (StringUtils.hasText(req.getOrgType())) {
            // 机构类型取医保字典 MEDINS_TYPE, 服务端回填名称与来源标识
            o.setOrgTypeName(stdDict.nameOf("cv_code", "MEDINS_TYPE", req.getOrgType()));
            o.setOrgTypeSrc("cv_code:MEDINS_TYPE");
        } else {
            o.setOrgTypeName(null);
            o.setOrgTypeSrc(null);
        }
        o.setFixmedinsCode(req.getFixmedinsCode());
        o.setFixmedinsName(req.getFixmedinsName());
        o.setUscc(req.getUscc());
        // 定点医疗服务机构类型取医保字典 fixmedins_type, 服务端回填名称与来源标识
        o.setFixmedinsType(req.getFixmedinsType());
        if (StringUtils.hasText(req.getFixmedinsType())) {
            o.setFixmedinsTypeName(stdDict.nameOf("cv_code", "fixmedins_type", req.getFixmedinsType()));
            o.setFixmedinsTypeSrc("cv_code:fixmedins_type");
        } else {
            o.setFixmedinsTypeName(null);
            o.setFixmedinsTypeSrc(null);
        }
        // 医院等级取医保字典 hosp_lv, 服务端回填名称与来源标识
        o.setHospLv(req.getHospLv());
        if (StringUtils.hasText(req.getHospLv())) {
            o.setHospLvName(stdDict.nameOf("cv_code", "hosp_lv", req.getHospLv()));
            o.setHospLvSrc("cv_code:hosp_lv");
        } else {
            o.setHospLvName(null);
            o.setHospLvSrc(null);
        }
        o.setPdLicenseNo(req.getPdLicenseNo());
        o.setBedCnt(req.getBedCnt());
        o.setPriceLv(req.getPriceLv());
        o.setAdmvsCode(req.getAdmvsCode());
        o.setLeader(req.getLeader());
        o.setPhone(req.getPhone());
        o.setAddress(req.getAddress());
        // 机构级医保接口配置(空值=继承租户/全局, 由 TenantYbConfigResolver 合并生效)
        o.setMdtrtareaAdmvs(req.getMdtrtareaAdmvs());
        o.setInsuplcAdmdvs(req.getInsuplcAdmdvs());
        o.setApiUrl(req.getApiUrl());
        o.setFileDownloadUrl(req.getFileDownloadUrl());
        o.setRecerSysCode(req.getRecerSysCode());
        o.setInfver(req.getInfver());
        o.setOpterType(req.getOpterType());
        o.setOpter(req.getOpter());
        o.setOpterName(req.getOpterName());
        o.setSignNo(req.getSignNo());
        // SM2私钥为只写敏感项: 仅当提交非空时覆盖, 留空=保留原值(避免回显为空时误清)
        if (StringUtils.hasText(req.getSm2PrivateKey())) {
            o.setSm2PrivateKey(req.getSm2PrivateKey());
        }
        o.setSm2PublicKey(req.getSm2PublicKey());
        o.setEncType(req.getEncType());
        o.setMockEnabled(req.getMockEnabled());
        o.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        o.setStatus(req.getStatus() == null ? 1 : req.getStatus());
    }

    private void validate(OrgSaveReq req, boolean isCreate) {
        if (!StringUtils.hasText(req.getOrgCode()) || !StringUtils.hasText(req.getOrgName())) {
            throw new BizException(400, "机构编码与名称不能为空");
        }
        if (req.getOrgLevel() != null && req.getOrgLevel() == 1
                && req.getParentId() != null && req.getParentId() != 0L) {
            throw new BizException(400, "县级机构上级必须为空");
        }
        // 牵头唯一性: is_lead=1 租户内唯一(县级机构可有多个成员, 牵头以 is_lead 标志区分)
        if (req.getIsLead() != null && req.getIsLead() == 1) {
            QueryWrapper<SysOrg> leadQ = new QueryWrapper<SysOrg>().eq("is_lead", 1);
            if (!isCreate && req.getId() != null) {
                leadQ.ne("id", req.getId());
            }
            if (orgMapper.selectCount(leadQ) > 0) {
                throw new BizException("一个医共体(租户)内只能有一个牵头机构, 已存在牵头机构");
            }
        }
        QueryWrapper<SysOrg> q = new QueryWrapper<SysOrg>().eq("org_code", req.getOrgCode());
        if (!isCreate && req.getId() != null) {
            q.ne("id", req.getId());
        }
        if (orgMapper.selectCount(q) > 0) {
            throw new BizException("机构编码已存在: " + req.getOrgCode());
        }
    }

    /* ===================== 本机构医保接口配置 ===================== */

    /**
     * 当前登录机构的医保接口配置(供"医院信息/医保接口配置"页回显):
     * 返回本机构自有值 + 机构/租户身份 + 租户级继承默认(inherited, 供前端空值占位提示)。
     * 生效优先级仍为 全局<租户<机构(TenantYbConfigResolver), 此处仅回显存储值不做合并。
     */
    public Map<String, Object> currentYbConfig() {
        SysOrg o = requireCurrentOrg();
        LoginUser lu = UserContext.get();
        SysTenant t = tenantService.getById(lu.getTenantId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", o.getId());
        m.put("orgName", o.getOrgName());
        m.put("orgLevel", o.getOrgLevel());
        m.put("tenantCode", t == null ? null : t.getTenantCode());
        m.put("tenantName", t == null ? null : t.getTenantName());
        m.put("leader", o.getLeader());
        m.put("phone", o.getPhone());
        m.put("address", o.getAddress());
        m.put("fixmedinsCode", o.getFixmedinsCode());
        m.put("fixmedinsName", o.getFixmedinsName());
        m.put("mdtrtareaAdmvs", o.getMdtrtareaAdmvs());
        m.put("insuplcAdmdvs", o.getInsuplcAdmdvs());
        m.put("apiUrl", o.getApiUrl());
        m.put("fileDownloadUrl", o.getFileDownloadUrl());
        m.put("recerSysCode", o.getRecerSysCode());
        m.put("infver", o.getInfver());
        m.put("opterType", o.getOpterType());
        m.put("opter", o.getOpter());
        m.put("opterName", o.getOpterName());
        m.put("signNo", o.getSignNo());
        m.put("sm2PublicKey", o.getSm2PublicKey());
        m.put("encType", o.getEncType());
        m.put("mockEnabled", o.getMockEnabled());
        // 租户级继承默认(本机构留空时运行时回落到这些值)
        Map<String, Object> inh = new LinkedHashMap<>();
        inh.put("fixmedinsCode", t == null ? null : t.getFixmedinsCode());
        inh.put("fixmedinsName", t == null ? null : t.getFixmedinsName());
        inh.put("mdtrtareaAdmvs", t == null ? null : t.getMdtrtareaAdmvs());
        inh.put("insuplcAdmdvs", t == null ? null : t.getInsuplcAdmdvs());
        inh.put("apiUrl", t == null ? null : t.getApiUrl());
        inh.put("fileDownloadUrl", t == null ? null : t.getFileDownloadUrl());
        inh.put("recerSysCode", t == null ? null : t.getRecerSysCode());
        inh.put("infver", t == null ? null : t.getInfver());
        inh.put("opterType", t == null ? null : t.getOpterType());
        inh.put("opter", t == null ? null : t.getOpter());
        inh.put("opterName", t == null ? null : t.getOpterName());
        inh.put("signNo", t == null ? null : t.getSignNo());
        inh.put("encType", t == null ? null : t.getEncType());
        inh.put("mockEnabled", t == null ? null : t.getMockEnabled());
        m.put("inherited", inh);
        return m;
    }

    /** 维护当前登录机构的医保接口配置(仅写本机构 sys_org 字段, 不动医共体/租户默认) */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentYbConfig(OrgSaveReq req) {
        SysOrg o = requireCurrentOrg();
        o.setFixmedinsCode(req.getFixmedinsCode());
        o.setFixmedinsName(req.getFixmedinsName());
        o.setMdtrtareaAdmvs(req.getMdtrtareaAdmvs());
        o.setInsuplcAdmdvs(req.getInsuplcAdmdvs());
        o.setApiUrl(req.getApiUrl());
        o.setFileDownloadUrl(req.getFileDownloadUrl());
        o.setRecerSysCode(req.getRecerSysCode());
        o.setInfver(req.getInfver());
        o.setOpterType(req.getOpterType());
        o.setOpter(req.getOpter());
        o.setOpterName(req.getOpterName());
        o.setSignNo(req.getSignNo());
        // SM2私钥只写: 仅当提交非空时覆盖, 留空=保留原值
        if (StringUtils.hasText(req.getSm2PrivateKey())) {
            o.setSm2PrivateKey(req.getSm2PrivateKey());
        }
        o.setSm2PublicKey(req.getSm2PublicKey());
        o.setEncType(req.getEncType());
        o.setMockEnabled(req.getMockEnabled());
        o.setLeader(req.getLeader());
        o.setPhone(req.getPhone());
        o.setAddress(req.getAddress());
        orgMapper.updateById(o);
    }

    /** 取当前登录用户绑定的机构, 未绑定抛错 */
    private SysOrg requireCurrentOrg() {
        LoginUser lu = UserContext.get();
        Long orgId = lu == null ? null : lu.getOrgId();
        SysOrg o = orgId == null ? null : orgMapper.selectById(orgId);
        if (o == null) {
            throw new BizException("当前用户未绑定机构, 无法维护本机构医保配置");
        }
        return o;
    }
}
