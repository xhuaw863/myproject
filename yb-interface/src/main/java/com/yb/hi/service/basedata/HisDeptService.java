package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.pharmacy.HisPharmacyDefMapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.service.SysOrgService;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科室服务
 */
@Service
public class HisDeptService extends ServiceImpl<HisDeptMapper, HisDept> {

    /** 可排班/可挂号的科室大类: 门诊科室(科室大类支持多选, 只要含此标签即视为可门诊) */
    public static final String CATEGORY_OUTPATIENT = "门诊科室";
    /** 可对外挂号的层级: 2-科室 / 3-诊室(1-大类仅为导航节点, 窗口级挂号无意义) */
    private static final List<Integer> SCHEDULE_LEVELS = Arrays.asList(2, 3);

    /**
     * 科室大类包含判定: dept_category 为逗号分隔的多选标签集合(如 "门诊科室,病区护理"),
     * 单值历史数据等价于只含一个标签的集合。判断 stored 是否包含 cat。
     */
    public static boolean hasCategory(String stored, String cat) {
        if (!StringUtils.hasText(stored) || !StringUtils.hasText(cat)) {
            return false;
        }
        for (String s : stored.split(",")) {
            if (s.trim().equals(cat)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 科室类型由科室大类主标签(首个)派生: 医技科室→医技, 行政后勤→行政, 其余(门诊/住院/病区护理)→临床。
     * 无大类时返回 null(不覆盖既有类型)。与前端 typeFromCat 口径一致。
     */
    public static String deriveDeptType(String deptCategory) {
        if (!StringUtils.hasText(deptCategory)) {
            return null;
        }
        String primary = null;
        for (String s : deptCategory.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                primary = t;
                break;
            }
        }
        if (primary == null) {
            return null;
        }
        if (primary.contains("医技")) {
            return "医技";
        }
        if (primary.contains("行政")) {
            return "行政";
        }
        return "临床";
    }

    private final StdDictQueryService stdDict;
    private final SysOrgService orgService;
    private final HisPharmacyDefMapper pharmacyDefMapper;

    public HisDeptService(StdDictQueryService stdDict, SysOrgService orgService,
                          HisPharmacyDefMapper pharmacyDefMapper) {
        this.stdDict = stdDict;
        this.orgService = orgService;
        this.pharmacyDefMapper = pharmacyDefMapper;
    }

    /**
     * 字典字段回填: 医保科别取医保字典 cv_code:caty(2201/2203必填), 回填名称与来源标识。
     */
    public void enrichDict(HisDept d) {
        if (d == null) {
            return;
        }
        // 拼音简码随科室名自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖
        d.setPyCode(PinyinUtil.initials(d.getDeptName()));
        if (StringUtils.hasText(d.getDeptCaty())) {
            d.setDeptCatyName(stdDict.nameOf("cv_code", "caty", d.getDeptCaty()));
            d.setDeptCatySrc("cv_code:caty");
        }
        // 医保科室编码(yb_dept_code)不再单独维护: 由医保科别(dept_caty)镜像同步。
        // 二者取同一 cv_code:caty 字典, 语义均为"院内科室->医保科室", 2201 的 dept_code 槽位改直接读 dept_caty。
        if (d.getDeptCaty() != null) {
            d.setYbDeptCode(d.getDeptCaty());
        }
    }

    /**
     * 可排班/可挂号科室(严格限机构): 本机构科室大类集合含门诊科室 且层级为科室/诊室 且 启用。
     * 门诊开诊标志(open_clinic)仅约束科室级(2)——未开诊的门诊科室不参与排班/挂号;
     * 诊室(3)是科室的物理子级, 其可选性只看启用/停用, 不受开诊标志限制(诊室通常不带该标志)。
     * orgId 为空则返回空集: 机构是排班的业务边界, 不允许退化为全医共体查询。
     */
    public List<HisDept> listSchedulable(Long orgId) {
        if (orgId == null) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .eq(HisDept::getOrgId, orgId)
                // 科室大类多选(逗号分隔): 含"门诊科室"即可排班, 用 FIND_IN_SET 精确匹配集合成员
                .apply("FIND_IN_SET({0}, dept_category) > 0", CATEGORY_OUTPATIENT)
                .in(HisDept::getDeptLevel, SCHEDULE_LEVELS)
                .eq(HisDept::getStatus, 1)
                // 诊室(3)直接放行; 科室(2)须开诊(open_clinic 缺省 NULL 视为开诊)
                .and(w -> w.eq(HisDept::getDeptLevel, 3)
                        .or().isNull(HisDept::getOpenClinic)
                        .or().ne(HisDept::getOpenClinic, 0))
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }

    /** 新增科室(回填字典名称与来源标识) */
    public void saveDept(HisDept d) {
        normalizeLevel(d);
        normalizeOpenClinic(d);
        deriveType(d);
        validateDefaultPharmacies(d);
        enrichDict(d);
        save(d);
    }

    /**
     * 修改科室(回填字典名称与来源标识)。
     * 默认发药药房清空需显式置 NULL: updateById 忽略 null 字段, 不补写则编辑页无法解除绑定
     * (调用方约定: 编辑表单透传完整实体, null 即"清空该渠道默认药房"的业务语义)。
     */
    public void updateDept(HisDept d) {
        normalizeLevel(d);
        normalizeOpenClinic(d);
        deriveType(d);
        validateDefaultPharmacies(d);
        enrichDict(d);
        updateById(d);
        if (d.getId() != null) {
            if (d.getDefPharmacyWest() == null) {
                lambdaUpdate().set(HisDept::getDefPharmacyWest, null).eq(HisDept::getId, d.getId()).update();
            }
            if (d.getDefPharmacyTcm() == null) {
                lambdaUpdate().set(HisDept::getDefPharmacyTcm, null).eq(HisDept::getId, d.getId()).update();
            }
        }
    }

    /** 默认发药药房配置校验(三期): 非空须药房存在、与科室同机构、启用; 不校验 pharmacyType 与渠道匹配(前端弱提示) */
    private void validateDefaultPharmacies(HisDept d) {
        checkDefaultPharmacy(d.getDefPharmacyWest(), d.getOrgId(), "西药渠道默认发药药房");
        checkDefaultPharmacy(d.getDefPharmacyTcm(), d.getOrgId(), "中药渠道默认发药药房");
    }

    private void checkDefaultPharmacy(Long pharmacyId, Long orgId, String label) {
        if (pharmacyId == null) {
            return;
        }
        HisPharmacyDef def = pharmacyDefMapper.selectById(pharmacyId);
        if (def == null) {
            throw new BizException(label + "不存在: " + pharmacyId);
        }
        if (orgId != null && !orgId.equals(def.getOrgId())) {
            throw new BizException(label + "须与科室归属同一机构(药房在: " + def.getName() + "所属机构)");
        }
        if (def.getStatus() == null || def.getStatus() != 1) {
            throw new BizException(label + "已停用: " + def.getName());
        }
    }

    /**
     * 规范化层级字段: parent_id null→ 0(顶级); dept_level 缺省按有无上级推断(顶级=1大类, 否则=2科室)。
     */
    private void normalizeLevel(HisDept d) {
        if (d.getParentId() == null) {
            d.setParentId(0L);
        }
        if (d.getDeptLevel() == null) {
            d.setDeptLevel(d.getParentId() == 0L ? 1 : 2);
        }
    }

    /**
     * 规范化门诊开诊标志: 缺省视为开诊(1), 与建列 DEFAULT 1 一致; 非门诊科室大类不参与排班。
     */
    private void normalizeOpenClinic(HisDept d) {
        if (d.getOpenClinic() == null) {
            d.setOpenClinic(1);
        }
    }

    /**
     * 科室类型不再单独录入, 由科室大类主标签派生(见 deriveDeptType); 无大类时保留传入值。
     */
    private void deriveType(HisDept d) {
        String dt = deriveDeptType(d.getDeptCategory());
        if (dt != null) {
            d.setDeptType(dt);
        }
    }

    /** 全部科室(排序) */
    public List<HisDept> listAll() {
        return listAll(null);
    }

    /** 全部科室(可按归属机构过滤, orgId 为空则不限) */
    public List<HisDept> listAll(Long orgId) {
        return lambdaQuery()
                .eq(orgId != null, HisDept::getOrgId, orgId)
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }

    /** 启用状态科室(下拉选择用) */
    public List<HisDept> listEnabled() {
        return listEnabled(null);
    }

    /** 启用状态科室(可按归属机构过滤, orgId 为空则不限) */
    public List<HisDept> listEnabled(Long orgId) {
        return lambdaQuery()
                .eq(HisDept::getStatus, 1)
                .eq(orgId != null, HisDept::getOrgId, orgId)
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list();
    }

    /**
     * 构建科室层级树(大类→科室→窗口/诊室)。
     * 以 parent_id 组装; 仅挂载到现有父节点的子节点入树, 孤儿节点(父不存在)归为顶级, 避免丢失。
     */
    public List<HisDept> buildTree(List<HisDept> flat) {
        List<HisDept> roots = new ArrayList<>();
        if (flat == null || flat.isEmpty()) {
            return roots;
        }
        Map<Long, HisDept> byId = new LinkedHashMap<>();
        for (HisDept d : flat) {
            d.setChildren(new ArrayList<>());
            byId.put(d.getId(), d);
        }
        for (HisDept d : flat) {
            Long pid = d.getParentId();
            HisDept parent = (pid == null || pid == 0L) ? null : byId.get(pid);
            if (parent != null) {
                parent.getChildren().add(d);
            } else {
                roots.add(d);
            }
        }
        return roots;
    }

    /** 科室层级树(可按归属机构过滤) */
    public List<HisDept> listTree(Long orgId) {
        return buildTree(listAll(orgId));
    }

    /** 科室层级树; withSubOrgs=true 且 orgId 非空时级联含下级机构(县→乡→村)的科室 */
    public List<HisDept> listTree(Long orgId, boolean withSubOrgs) {
        if (orgId == null || !withSubOrgs) {
            return listTree(orgId);
        }
        List<Long> orgIds = orgService.subtreeIds(orgId);
        return buildTree(lambdaQuery()
                .in(HisDept::getOrgId, orgIds)
                .orderByAsc(HisDept::getSortNo)
                .orderByAsc(HisDept::getId)
                .list());
    }

    /** 指定科室的子树 id 集合(含自身), 用于按上级科室级联过滤业务数据(如职工/排班) */
    public List<Long> subtreeIds(Long rootId) {
        List<Long> out = new ArrayList<>();
        if (rootId == null) {
            return out;
        }
        out.add(rootId);
        List<HisDept> all = listAll();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (HisDept d : all) {
                if (d.getId() != null && d.getParentId() != null && out.contains(d.getParentId()) && !out.contains(d.getId())) {
                    out.add(d.getId());
                    grew = true;
                }
            }
        }
        return out;
    }

    /** 导出科室列表(head/rows/total): 与列表同筛选(机构/级联 + 名称编码关键字/大类/状态), 层级树 DFS 摊平保持大类→科室→窗口/诊室次序 */
    public Map<String, Object> exportRows(Long orgId, boolean withSubOrgs, String keyword, String deptCategory, Integer status) {
        List<HisDept> tree = listTree(orgId, withSubOrgs);
        Map<Long, String> orgNames = new LinkedHashMap<>();
        for (SysOrg o : orgService.listAll()) {
            orgNames.put(o.getId(), o.getOrgName());
        }
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"科室名称", "科室编码", "层级", "上级科室", "所属大类", "所属机构", "类型", "医保科别", "联系电话", "位置", "状态", "门诊开诊", "排序号"}) {
            head.add(Collections.singletonList(h));
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
        String cat = StringUtils.hasText(deptCategory) ? deptCategory : null;
        List<List<Object>> rows = new ArrayList<>();
        flattenForExport(tree, null, null, orgNames, rows, kw, cat, status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /** DFS 摊平导出行: rootName=链顶大类名, parentName=直接上级科室名; 带查询条件时仅导出命中行(仍递归子级) */
    private void flattenForExport(List<HisDept> nodes, String parentName, String rootName, Map<Long, String> orgNames, List<List<Object>> rows,
                                  String kw, String cat, Integer status) {
        for (HisDept d : nodes) {
            String root = rootName == null ? d.getDeptName() : rootName;
            if (matchExport(d, kw, cat, status)) {
                rows.add(Arrays.asList(
                        d.getDeptName(), nz(d.getDeptCode()), levelText(d.getDeptLevel()),
                        parentName == null ? "" : parentName, root,
                        d.getOrgId() == null ? "" : nz(orgNames.get(d.getOrgId())), nz(d.getDeptType()),
                        StringUtils.hasText(d.getDeptCatyName()) ? d.getDeptCatyName() : nz(d.getDeptCaty()),
                        nz(d.getPhone()), nz(d.getLocDesc()),
                        d.getStatus() != null && d.getStatus() == 1 ? "启用" : "停用",
                        openClinicText(d),
                        d.getSortNo() == null ? "" : d.getSortNo()));
            }
            if (d.getChildren() != null && !d.getChildren().isEmpty()) {
                flattenForExport(d.getChildren(), d.getDeptName(), root, orgNames, rows, kw, cat, status);
            }
        }
    }

    /** 导出行匹配: 关键字命中名称或编码(忽略大小写) + 大类精确 + 状态精确; 条件为空即不限 */
    private static boolean matchExport(HisDept d, String kw, String cat, Integer status) {
        if (kw != null) {
            String name = d.getDeptName() == null ? "" : d.getDeptName().toLowerCase();
            String code = d.getDeptCode() == null ? "" : d.getDeptCode().toLowerCase();
            String py = d.getPyCode() == null ? "" : d.getPyCode().toLowerCase();
            String ab = d.getAbbrCode() == null ? "" : d.getAbbrCode().toLowerCase();
            if (!name.contains(kw) && !code.contains(kw) && !py.contains(kw) && !ab.contains(kw)) {
                return false;
            }
        }
        if (cat != null && !hasCategory(d.getDeptCategory(), cat)) {
            return false;
        }
        return status == null || status.equals(d.getStatus());
    }

    private static String levelText(Integer lv) {
        if (lv == null) {
            return "";
        }
        return lv == 1 ? "大类" : (lv == 2 ? "科室" : (lv == 3 ? "窗口/诊室" : String.valueOf(lv)));
    }

    /** 导出用开诊文案: 非门诊科室大类不参与排班, 一律显示为“-” */
    private static String openClinicText(HisDept d) {
        if (!hasCategory(d.getDeptCategory(), CATEGORY_OUTPATIENT)) {
            return "-";
        }
        return d.getOpenClinic() != null && d.getOpenClinic() == 0 ? "未开诊" : "开诊";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
