package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.dto.OrgNode;
import com.yb.hi.platform.dto.OrgSaveReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.mapper.SysUserMapper;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

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

    public SysOrgService(SysOrgMapper orgMapper, SysUserMapper userMapper, StdDictQueryService stdDict) {
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
        this.stdDict = stdDict;
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

    public SysOrg getById(Long id) {
        return orgMapper.selectById(id);
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
            throw new BizException(400, "县级(牵头)机构上级必须为空");
        }
        QueryWrapper<SysOrg> q = new QueryWrapper<SysOrg>().eq("org_code", req.getOrgCode());
        if (!isCreate && req.getId() != null) {
            q.ne("id", req.getId());
        }
        if (orgMapper.selectCount(q) > 0) {
            throw new BizException("机构编码已存在: " + req.getOrgCode());
        }
    }
}
