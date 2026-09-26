package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.community.OrgCatalogSaveReq;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisMedDict;
import com.yb.hi.entity.community.HisOrgCatalog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisOrgCatalogMapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.service.basedata.HisChargeItemService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机构开展目录服务(L3): 各机构(含牵头机构)从医共体目录勾选本院实际开展的项目, 只能启停选用, 不能改目录内容与价格。
 * 导入的 L2 目录面向整个医共体; 牵头机构同样只选用其中一部分, 不再默认全量开展。
 * 医生站/收费取数一律经此过滤: 仅返回本机构已勾选启用项, 收费项目附机构执行价 execPrice。
 */
@Service
public class OrgCatalogService {

    private final HisOrgCatalogMapper orgCatalogMapper;
    private final SysOrgMapper orgMapper;
    private final HisDrugCatalogService drugService;
    private final HisConsCatalogService consService;
    private final HisChargeItemService chargeService;
    private final HisMedDictService medDictService;

    public OrgCatalogService(HisOrgCatalogMapper orgCatalogMapper, SysOrgMapper orgMapper,
                             HisDrugCatalogService drugService, HisConsCatalogService consService,
                             HisChargeItemService chargeService, HisMedDictService medDictService) {
        this.orgCatalogMapper = orgCatalogMapper;
        this.orgMapper = orgMapper;
        this.drugService = drugService;
        this.consService = consService;
        this.chargeService = chargeService;
        this.medDictService = medDictService;
    }

    /* ================= 上下文 ================= */

    private LoginUser currentUser() {
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getOrgId() == null) {
            throw new BizException(400, "无法确定当前用户归属机构");
        }
        return lu;
    }

    private SysOrg currentOrg() {
        SysOrg org = orgMapper.selectById(currentUser().getOrgId());
        if (org == null) {
            throw new BizException(400, "当前用户归属机构不存在");
        }
        return org;
    }

    /** 是否牵头机构(默认全量开展): sys_org.is_lead=1 */
    private boolean isLead(SysOrg org) {
        return org.getIsLead() != null && org.getIsLead() == 1;
    }

    /** 本机构某类目录已勾选启用的 catalogId 集合(所有机构一视同仁, 含牵头机构; 未勾选=空集) */
    private Set<Long> enabledIds(Long orgId, String catalogType) {
        List<HisOrgCatalog> list = orgCatalogMapper.selectList(new QueryWrapper<HisOrgCatalog>()
                .eq("org_id", orgId).eq("catalog_type", catalogType).eq("enabled", 1));
        Set<Long> ids = new HashSet<>();
        for (HisOrgCatalog oc : list) {
            ids.add(oc.getCatalogId());
        }
        return ids;
    }

    /** 将 L2 查询按机构启用集过滤: 空集强制无结果; 否则 in 过滤(null 兼容不过滤) */
    private void applyEnabled(LambdaQueryChainWrapper<HisDrugCatalog> q, Set<Long> enabledIds) {
        if (enabledIds == null) {
            return;
        }
        if (enabledIds.isEmpty()) {
            q.apply("1 = 0");
        } else {
            q.in(HisDrugCatalog::getId, enabledIds);
        }
    }

    private void applyEnabledCharge(LambdaQueryChainWrapper<HisChargeItem> q, Set<Long> enabledIds) {
        if (enabledIds == null) {
            return;
        }
        if (enabledIds.isEmpty()) {
            q.apply("1 = 0");
        } else {
            q.in(HisChargeItem::getId, enabledIds);
        }
    }

    private void applyEnabledMedDict(LambdaQueryChainWrapper<HisMedDict> q, Set<Long> enabledIds) {
        if (enabledIds == null) {
            return;
        }
        if (enabledIds.isEmpty()) {
            q.apply("1 = 0");
        } else {
            q.in(HisMedDict::getId, enabledIds);
        }
    }

    /* ================= 机构选用列表(带启用状态) ================= */

    /** 机构目录选用分页: 返回 L2 项目 + 本机构开展状态; enabledFilter=null 全部 / 1 仅已开展 / 0 仅未开展 */
    public Map<String, Object> selectionPage(String catalogType, String keyword, Integer enabledFilter, long page, long size) {
        SysOrg org = currentOrg();
        boolean lead = isLead(org);
        Set<Long> enabled = enabledIds(org.getId(), catalogType);
        List<Map<String, Object>> records = new ArrayList<>();
        long total;
        switch (catalogType == null ? "" : catalogType) {
            case "drug": {
                IPage<HisDrugCatalog> r = drugSelQ(keyword, enabledFilter, enabled).page(new Page<>(page, size));
                total = r.getTotal();
                for (HisDrugCatalog d : r.getRecords()) {
                    records.add(row(d.getId(), d.getDrugCode(), d.getGenericName(), d.getSpec(),
                            d.getMinUnit(), price(d.getRetailPrice()), flag(enabled, d.getId())));
                }
                break;
            }
            case "cons": {
                IPage<HisConsCatalog> r = consSelQ(keyword, enabledFilter, enabled).page(new Page<>(page, size));
                total = r.getTotal();
                for (HisConsCatalog c : r.getRecords()) {
                    records.add(row(c.getId(), c.getConsCode(), c.getName(), c.getSpecModel(),
                            c.getMinUnit(), price(c.getChargePrice()), flag(enabled, c.getId())));
                }
                break;
            }
            case "charge": {
                IPage<HisChargeItem> r = chargeSelQ(keyword, enabledFilter, enabled).page(new Page<>(page, size));
                total = r.getTotal();
                for (HisChargeItem it : r.getRecords()) {
                    records.add(row(it.getId(), it.getItemCode(), it.getItemName(), it.getSpec(),
                            it.getUnit(), price(execPrice(it, org)), flag(enabled, it.getId())));
                }
                break;
            }
            case "usage":
            case "freq": {
                IPage<HisMedDict> r = medDictSelQ(catalogType, keyword, enabledFilter, enabled).page(new Page<>(page, size));
                total = r.getTotal();
                for (HisMedDict m : r.getRecords()) {
                    String spec = "freq".equals(catalogType) && m.getDailyTimes() != null
                            ? "每日" + m.getDailyTimes().stripTrailingZeros().toPlainString() + "次" : "";
                    records.add(row(m.getId(), m.getCode(), m.getName(), spec,
                            "", "", flag(enabled, m.getId())));
                }
                break;
            }
            default:
                throw new BizException(400, "不支持的目录类型: " + catalogType);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("lead", lead);
        return out;
    }

    private Map<String, Object> row(Long id, String code, String name, String spec, String unit,
                                    String priceText, int enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("code", code);
        m.put("name", name);
        m.put("spec", spec);
        m.put("unit", unit);
        m.put("priceText", priceText);
        m.put("enabled", enabled);
        return m;
    }

    private int flag(Set<Long> enabled, Long id) {
        return enabled != null && enabled.contains(id) ? 1 : 0;
    }

    private String price(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /* ================= 医生站/收费取数(仅本机构启用项) ================= */

    /** 可开药药品(本机构启用, 含换算字段与零售价) */
    public IPage<HisDrugCatalog> availableDrug(String keyword, long page, long size) {
        SysOrg org = currentOrg();
        Set<Long> enabled = enabledIds(org.getId(), "drug");
        LambdaQueryChainWrapper<HisDrugCatalog> q = drugService.lambdaQuery().eq(HisDrugCatalog::getStatus, 1);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisDrugCatalog::getGenericName, keyword)
                    .or().like(HisDrugCatalog::getTradeName, keyword)
                    .or().like(HisDrugCatalog::getDrugCode, keyword)
                    .or().like(HisDrugCatalog::getPyCode, keyword)
                    .or().like(HisDrugCatalog::getAbbrCode, keyword));
        }
        applyEnabled(q, enabled);
        IPage<HisDrugCatalog> r = q.orderByDesc(HisDrugCatalog::getId).page(new Page<>(page, size));
        r.getRecords().forEach(drugService::derivePackPrice);
        return r;
    }

    /** 可开收费项目(本机构启用, 附执行价 execPrice) */
    public IPage<HisChargeItem> availableCharge(String keyword, String itemType, long page, long size) {
        SysOrg org = currentOrg();
        Set<Long> enabled = enabledIds(org.getId(), "charge");
        LambdaQueryChainWrapper<HisChargeItem> q = chargeService.lambdaQuery()
                .eq(HisChargeItem::getStatus, 1)
                .eq(StringUtils.hasText(itemType), HisChargeItem::getItemType, itemType);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisChargeItem::getItemName, keyword)
                    .or().like(HisChargeItem::getItemCode, keyword)
                    .or().like(HisChargeItem::getPyCode, keyword)
                    .or().like(HisChargeItem::getAbbrCode, keyword));
        }
        applyEnabledCharge(q, enabled);
        IPage<HisChargeItem> r = q.orderByDesc(HisChargeItem::getId).page(new Page<>(page, size));
        r.getRecords().forEach(it -> fillExecPrice(it, org));
        return r;
    }

    /** 可用用药字典(本机构启用, 供医生站用法/频次下拉): dictType=usage/freq */
    public List<HisMedDict> availableMedDict(String dictType) {
        SysOrg org = currentOrg();
        Set<Long> enabled = enabledIds(org.getId(), dictType);
        LambdaQueryChainWrapper<HisMedDict> q = medDictService.lambdaQuery()
                .eq(StringUtils.hasText(dictType), HisMedDict::getDictType, dictType)
                .eq(HisMedDict::getStatus, 1);
        applyEnabledMedDict(q, enabled);
        return q.orderByAsc(HisMedDict::getSortNo).orderByAsc(HisMedDict::getId).list();
    }

    /* ================= 启停选用 ================= */

    /** 批量/单条启停本机构开展状态(所有机构含牵头机构均需勾选落记录) */
    @Transactional(rollbackFor = Exception.class)
    public void save(OrgCatalogSaveReq req) {
        if (req == null || !StringUtils.hasText(req.getCatalogType())) {
            throw new BizException(400, "目录类型不能为空");
        }
        SysOrg org = currentOrg();
        int enabled = req.getEnabled() != null && req.getEnabled() == 1 ? 1 : 0;
        List<Long> ids = new ArrayList<>();
        if (req.getCatalogId() != null) {
            ids.add(req.getCatalogId());
        }
        if (req.getIds() != null) {
            ids.addAll(req.getIds());
        }
        if (ids.isEmpty()) {
            throw new BizException(400, "未选择目录项");
        }
        for (Long cid : ids) {
            if (cid == null) {
                continue;
            }
            upsert(org, req.getCatalogType(), cid, nameOf(req.getCatalogType(), cid), enabled);
        }
    }

    private void upsert(SysOrg org, String catalogType, Long catalogId, String catalogName, int enabled) {
        HisOrgCatalog exist = orgCatalogMapper.selectOne(new QueryWrapper<HisOrgCatalog>()
                .eq("org_id", org.getId()).eq("catalog_type", catalogType)
                .eq("catalog_id", catalogId).last("LIMIT 1"));
        if (exist == null) {
            HisOrgCatalog oc = new HisOrgCatalog();
            oc.setOrgId(org.getId());
            oc.setOrgName(org.getOrgName());
            oc.setCatalogType(catalogType);
            oc.setCatalogId(catalogId);
            oc.setCatalogName(catalogName);
            oc.setEnabled(enabled);
            oc.setEffDate(enabled == 1 ? LocalDate.now() : null);
            orgCatalogMapper.insert(oc);
        } else {
            exist.setEnabled(enabled);
            exist.setCatalogName(catalogName);
            exist.setEffDate(enabled == 1 ? (exist.getEffDate() == null ? LocalDate.now() : exist.getEffDate()) : null);
            orgCatalogMapper.updateById(exist);
        }
    }

    private String nameOf(String catalogType, Long catalogId) {
        switch (catalogType) {
            case "drug": {
                HisDrugCatalog d = drugService.getById(catalogId);
                return d == null ? null : d.getGenericName();
            }
            case "cons": {
                HisConsCatalog c = consService.getById(catalogId);
                return c == null ? null : c.getName();
            }
            case "charge": {
                HisChargeItem it = chargeService.getById(catalogId);
                return it == null ? null : it.getItemName();
            }
            case "usage":
            case "freq": {
                HisMedDict m = medDictService.getById(catalogId);
                return m == null ? null : m.getName();
            }
            default:
                return null;
        }
    }

    /* ================= 目录项详情(L3 详情按钮) ================= */

    /** 目录项详情: 返回 L2 目录全部非空字段(中文标签), 供机构选用页「详情」弹窗展示 */
    public List<Map<String, Object>> detail(String catalogType, Long catalogId) {
        List<Map<String, Object>> out = new ArrayList<>();
        switch (catalogType == null ? "" : catalogType) {
            case "drug": {
                HisDrugCatalog d = drugService.getById(catalogId);
                if (d == null) {
                    throw new BizException(404, "药品目录不存在: " + catalogId);
                }
                put(out, "院内药品码", d.getDrugCode());
                put(out, "医保药品码", d.getYbDrugCode());
                put(out, "本位码", d.getDrugStdCode());
                put(out, "批准文号", d.getApprovalNo());
                put(out, "通用名", d.getGenericName());
                put(out, "商品名", d.getTradeName());
                put(out, "拼音简码", d.getPyCode());
                put(out, "自定义码", d.getAbbrCode());
                put(out, "大类", d.getMajorClass());
                put(out, "剂型", d.getDosformName() != null ? d.getDosformName() : d.getDosform());
                put(out, "规格", d.getSpec());
                put(out, "生产企业", d.getManufacturer());
                put(out, "上市持有人", d.getMktHolder());
                put(out, "甲乙丙类", d.getChrgitmLvName() != null ? d.getChrgitmLvName() : d.getChrgitmLv());
                put(out, "自付比例", dec(d.getSelfpayProp()));
                put(out, "支付标准", d.getPayStdPrep());
                put(out, "谈判药", d.getNegoFlag());
                put(out, "门特标识", d.getMsdFlag());
                put(out, "限自费标识", d.getLtdSelfFlag());
                put(out, "限定支付范围", d.getLimitScope());
                put(out, "剂量单位", d.getDoseUnit());
                put(out, "单位含药量", dec(d.getUnitDose()));
                put(out, "最小/发药单位", d.getMinUnit());
                put(out, "采购/大包装", d.getPackUnit());
                put(out, "包装换算比", d.getPackRatio() == null ? "" : String.valueOf(d.getPackRatio()));
                put(out, "发药取整", roundRuleText(d.getRoundRule()));
                put(out, "进货价(最小单位)", dec(d.getPurchasePrice()));
                put(out, "零售价(最小单位)", dec(d.getRetailPrice()));
                put(out, "零差率", yn(d.getZeroMargin()));
                put(out, "药品管理类别", d.getDrugClassName() != null ? d.getDrugClassName() : d.getDrugClass());
                put(out, "抗菌药分级", d.getAbxGradeName() != null ? d.getAbxGradeName() : d.getAbxGrade());
                put(out, "储存条件", d.getStorageCondName() != null ? d.getStorageCondName() : d.getStorageCond());
                put(out, "OTC", yn(d.getOtcFlag()));
                put(out, "基本药物", yn(d.getEssentialFlag()));
                put(out, "需皮试", yn(d.getSkinTestFlag()));
                put(out, "妊娠分级", d.getPregClass());
                put(out, "单次最大量", dec(d.getMaxQtyOnce()));
                put(out, "状态", statusText(d.getStatus()));
                put(out, "生效日期", d.getEffDate() == null ? "" : d.getEffDate().toString());
                put(out, "作废日期", d.getEndDate() == null ? "" : d.getEndDate().toString());
                put(out, "来源字典", d.getSrcType());
                put(out, "来源文档", d.getSrcDoc());
                put(out, "来源编码", d.getSrcCode());
                put(out, "备注", d.getMemo());
                break;
            }
            case "cons": {
                HisConsCatalog c = consService.getById(catalogId);
                if (c == null) {
                    throw new BizException(404, "耗材目录不存在: " + catalogId);
                }
                put(out, "院内耗材码", c.getConsCode());
                put(out, "医保耗材码", c.getYbConsCode());
                put(out, "注册证号", c.getRegCertNo());
                put(out, "耗材名称", c.getName());
                put(out, "拼音简码", c.getPyCode());
                put(out, "自定义码", c.getAbbrCode());
                put(out, "一级分类", c.getCat1());
                put(out, "二级分类", c.getCat2());
                put(out, "三级分类", c.getCat3());
                put(out, "规格型号", c.getSpecModel());
                put(out, "材质", c.getMaterial());
                put(out, "特征", c.getFeature());
                put(out, "生产企业", c.getManufacturer());
                put(out, "最小单位", c.getMinUnit());
                put(out, "采购单位", c.getPackUnit());
                put(out, "换算比", c.getPackRatio() == null ? "" : String.valueOf(c.getPackRatio()));
                put(out, "进货价", dec(c.getPurchasePrice()));
                put(out, "收费价", dec(c.getChargePrice()));
                put(out, "收费方式", c.getChargeFlag() == null ? "" : (c.getChargeFlag() == 1 ? "单独收费" : "包含性"));
                put(out, "甲乙丙类", c.getChrgitmLvName() != null ? c.getChrgitmLvName() : c.getChrgitmLv());
                put(out, "自付比例", dec(c.getSelfpayProp()));
                put(out, "支付标准", c.getPayStd());
                put(out, "高值耗材", yn(c.getHighValueFlag()));
                put(out, "植入类", yn(c.getImplantFlag()));
                put(out, "无菌", yn(c.getSterileFlag()));
                put(out, "状态", statusText(c.getStatus()));
                put(out, "生效日期", c.getEffDate() == null ? "" : c.getEffDate().toString());
                put(out, "作废日期", c.getEndDate() == null ? "" : c.getEndDate().toString());
                put(out, "来源字典", c.getSrcType());
                put(out, "来源文档", c.getSrcDoc());
                put(out, "来源编码", c.getSrcCode());
                put(out, "备注", c.getMemo());
                break;
            }
            case "charge": {
                HisChargeItem it = chargeService.getById(catalogId);
                if (it == null) {
                    throw new BizException(404, "收费项目不存在: " + catalogId);
                }
                put(out, "院内编码", it.getItemCode());
                put(out, "项目名称", it.getItemName());
                put(out, "拼音简码", it.getPyCode());
                put(out, "自定义码", it.getAbbrCode());
                put(out, "项目大类", it.getItemType());
                put(out, "细分类别", it.getItemCat());
                put(out, "规格", it.getSpec());
                put(out, "单位", it.getUnit());
                put(out, "默认单价", dec(it.getPrice()));
                put(out, "一级价", dec(it.getPriceL1()));
                put(out, "二级价", dec(it.getPriceL2()));
                put(out, "三级价", dec(it.getPriceL3()));
                put(out, "全国编码", it.getNatItemCode());
                put(out, "湖北编码", it.getLocItemCode());
                put(out, "项目内涵", it.getItemContent());
                put(out, "除外内容", it.getItemExcluded());
                put(out, "票据分类", it.getInvoiceClass());
                put(out, "会计科目", it.getAcctClass());
                put(out, "病案首页归并", it.getMrCostClass());
                put(out, "医疗科室类别", it.getDeptCaty());
                put(out, "医保目录编码", it.getMedListCodg());
                put(out, "医疗收费项目类别", it.getMedChrgitmType());
                put(out, "甲乙丙类", it.getChrgitmLv());
                put(out, "自付比例", dec(it.getSelfpayProp()));
                put(out, "状态", statusText(it.getStatus()));
                put(out, "生效日期", it.getEffDate() == null ? "" : it.getEffDate().toString());
                put(out, "作废日期", it.getEndDate() == null ? "" : it.getEndDate().toString());
                put(out, "来源字典", it.getSrcType());
                put(out, "来源文档", it.getSrcDoc());
                put(out, "来源编码", it.getSrcCode());
                put(out, "备注", it.getMemo());
                break;
            }
            case "usage":
            case "freq": {
                HisMedDict m = medDictService.getById(catalogId);
                if (m == null) {
                    throw new BizException(404, "用药字典不存在: " + catalogId);
                }
                put(out, "字典类型", "freq".equals(m.getDictType()) ? "用药频次" : "用法(给药途径)");
                put(out, "院内编码", m.getCode());
                put(out, "名称", m.getName());
                put(out, "拼音简码", m.getPyCode());
                put(out, "自定义码", m.getAbbrCode());
                put(out, "医保值域码", m.getYbCode());
                if ("freq".equals(m.getDictType())) {
                    put(out, "每日次数", dec(m.getDailyTimes()));
                }
                put(out, "排序号", m.getSortNo() == null ? "" : String.valueOf(m.getSortNo()));
                put(out, "状态", statusText(m.getStatus()));
                put(out, "来源类型", m.getSrcType());
                put(out, "来源文档", m.getSrcDoc());
                put(out, "来源编码", m.getSrcCode());
                put(out, "备注", m.getMemo());
                break;
            }
            default:
                throw new BizException(400, "不支持的目录类型: " + catalogType);
        }
        return out;
    }

    private void put(List<Map<String, Object>> out, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value.trim());
        out.add(m);
    }

    private String dec(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private String yn(Integer v) {
        return v == null ? "" : (v == 1 ? "是" : "否");
    }

    private String statusText(Integer v) {
        return v == null ? "" : (v == 1 ? "启用" : "停用");
    }

    private String roundRuleText(Integer v) {
        if (v == null) {
            return "";
        }
        switch (v) {
            case 1:
                return "向上取整";
            case 2:
                return "向下取整";
            case 3:
                return "四舍五入";
            default:
                return String.valueOf(v);
        }
    }

    /* ================= 执行价 ================= */

    /** 按机构 price_lv 取对应档执行价(缺档回退默认 price) */
    public BigDecimal execPrice(HisChargeItem it, SysOrg org) {
        Integer lv = org == null ? null : org.getPriceLv();
        BigDecimal v = null;
        if (lv != null) {
            switch (lv) {
                case 1:
                    v = it.getPriceL1();
                    break;
                case 2:
                    v = it.getPriceL2();
                    break;
                case 3:
                    v = it.getPriceL3();
                    break;
                default:
                    break;
            }
        }
        if (v == null) {
            v = it.getPrice();
        }
        return v;
    }

    private void fillExecPrice(HisChargeItem it, SysOrg org) {
        it.setExecPrice(execPrice(it, org));
        it.setExecPriceLv(org == null ? null : org.getPriceLv());
    }

    /* ================= 选用列表/导出共用: L2 查询(keyword + 开展状态过滤) ================= */

    /** 开展状态过滤: ef=null 不过滤; 1 仅已开展(in 启用集, 空集则无结果); 0 仅未开展(notIn 启用集, 空集则全量) */
    private <T> void applyEnabledFilter(LambdaQueryChainWrapper<T> q, Integer ef, Set<Long> enabled, SFunction<T, ?> idCol) {
        if (ef == null) {
            return;
        }
        boolean empty = enabled == null || enabled.isEmpty();
        if (ef == 1) {
            if (empty) {
                q.apply("1 = 0");
            } else {
                q.in(idCol, enabled);
            }
        } else if (ef == 0 && !empty) {
            q.notIn(idCol, enabled);
        }
    }

    private LambdaQueryChainWrapper<HisDrugCatalog> drugSelQ(String keyword, Integer ef, Set<Long> enabled) {
        LambdaQueryChainWrapper<HisDrugCatalog> q = drugService.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisDrugCatalog::getGenericName, keyword)
                    .or().like(HisDrugCatalog::getTradeName, keyword)
                    .or().like(HisDrugCatalog::getDrugCode, keyword)
                    .or().like(HisDrugCatalog::getYbDrugCode, keyword)
                    .or().like(HisDrugCatalog::getPyCode, keyword)
                    .or().like(HisDrugCatalog::getAbbrCode, keyword));
        }
        applyEnabledFilter(q, ef, enabled, HisDrugCatalog::getId);
        return q.orderByDesc(HisDrugCatalog::getId);
    }

    private LambdaQueryChainWrapper<HisConsCatalog> consSelQ(String keyword, Integer ef, Set<Long> enabled) {
        LambdaQueryChainWrapper<HisConsCatalog> q = consService.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisConsCatalog::getName, keyword)
                    .or().like(HisConsCatalog::getConsCode, keyword)
                    .or().like(HisConsCatalog::getYbConsCode, keyword)
                    .or().like(HisConsCatalog::getPyCode, keyword)
                    .or().like(HisConsCatalog::getAbbrCode, keyword));
        }
        applyEnabledFilter(q, ef, enabled, HisConsCatalog::getId);
        return q.orderByDesc(HisConsCatalog::getId);
    }

    private LambdaQueryChainWrapper<HisChargeItem> chargeSelQ(String keyword, Integer ef, Set<Long> enabled) {
        LambdaQueryChainWrapper<HisChargeItem> q = chargeService.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisChargeItem::getItemName, keyword)
                    .or().like(HisChargeItem::getItemCode, keyword)
                    .or().like(HisChargeItem::getMedListCodg, keyword)
                    .or().like(HisChargeItem::getPyCode, keyword)
                    .or().like(HisChargeItem::getAbbrCode, keyword));
        }
        applyEnabledFilter(q, ef, enabled, HisChargeItem::getId);
        return q.orderByDesc(HisChargeItem::getId);
    }

    private LambdaQueryChainWrapper<HisMedDict> medDictSelQ(String dictType, String keyword, Integer ef, Set<Long> enabled) {
        LambdaQueryChainWrapper<HisMedDict> q = medDictService.lambdaQuery()
                .eq(StringUtils.hasText(dictType), HisMedDict::getDictType, dictType);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisMedDict::getName, keyword)
                    .or().like(HisMedDict::getCode, keyword)
                    .or().like(HisMedDict::getYbCode, keyword)
                    .or().like(HisMedDict::getPyCode, keyword)
                    .or().like(HisMedDict::getAbbrCode, keyword));
        }
        applyEnabledFilter(q, ef, enabled, HisMedDict::getId);
        return q.orderByAsc(HisMedDict::getSortNo).orderByAsc(HisMedDict::getId);
    }

    /* ================= 导出(与选用列表同筛选, 一次性导出全部匹配行) ================= */

    /** 导出行数据: 返回 {head: List<List<String>>, rows: List<List<Object>>} */
    public Map<String, Object> exportRows(String catalogType, String keyword, Integer enabledFilter) {
        SysOrg org = currentOrg();
        Set<Long> enabled = enabledIds(org.getId(), catalogType);
        List<List<String>> head = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        switch (catalogType == null ? "" : catalogType) {
            case "drug": {
                head = headOf("院内药品码", "通用名", "规格", "最小单位", "零售价", "开展状态");
                for (HisDrugCatalog d : drugSelQ(keyword, enabledFilter, enabled).list()) {
                    rows.add(vals(d.getDrugCode(), d.getGenericName(), d.getSpec(), d.getMinUnit(),
                            price(d.getRetailPrice()), statText(flag(enabled, d.getId()))));
                }
                break;
            }
            case "cons": {
                head = headOf("院内耗材码", "耗材名称", "规格型号", "最小单位", "收费价", "开展状态");
                for (HisConsCatalog c : consSelQ(keyword, enabledFilter, enabled).list()) {
                    rows.add(vals(c.getConsCode(), c.getName(), c.getSpecModel(), c.getMinUnit(),
                            price(c.getChargePrice()), statText(flag(enabled, c.getId()))));
                }
                break;
            }
            case "charge": {
                head = headOf("院内编码", "项目名称", "规格", "单位", "执行价", "开展状态");
                for (HisChargeItem it : chargeSelQ(keyword, enabledFilter, enabled).list()) {
                    rows.add(vals(it.getItemCode(), it.getItemName(), it.getSpec(), it.getUnit(),
                            price(execPrice(it, org)), statText(flag(enabled, it.getId()))));
                }
                break;
            }
            case "usage": {
                head = headOf("院内编码", "用法名称", "开展状态");
                for (HisMedDict m : medDictSelQ(catalogType, keyword, enabledFilter, enabled).list()) {
                    rows.add(vals(m.getCode(), m.getName(), statText(flag(enabled, m.getId()))));
                }
                break;
            }
            case "freq": {
                head = headOf("院内编码", "频次名称", "每日次数", "开展状态");
                for (HisMedDict m : medDictSelQ(catalogType, keyword, enabledFilter, enabled).list()) {
                    rows.add(vals(m.getCode(), m.getName(),
                            m.getDailyTimes() == null ? "" : m.getDailyTimes().stripTrailingZeros().toPlainString(),
                            statText(flag(enabled, m.getId()))));
                }
                break;
            }
            default:
                throw new BizException(400, "不支持的目录类型: " + catalogType);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        return out;
    }

    private List<List<String>> headOf(String... cols) {
        List<List<String>> h = new ArrayList<>();
        for (String c : cols) {
            h.add(new ArrayList<>(Collections.singletonList(c)));
        }
        return h;
    }

    private List<Object> vals(Object... vs) {
        List<Object> r = new ArrayList<>();
        for (Object v : vs) {
            r.add(v == null ? "" : v);
        }
        return r;
    }

    private String statText(int enabled) {
        return enabled == 1 ? "已开展" : "未开展";
    }
}
