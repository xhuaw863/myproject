package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.community.HisDictEditLog;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisDictEditLogMapper;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 医共体统一字典字段级修改留痕: 编辑保存时逐字段 diff 旧行/新行, 每个变化字段写一行 his_dict_edit_log。
 * 跟踪范围 = 三目录业务字段(见 TRACKED); 医保码字段(yb 开头码与 medListCodg/prevYbCode/ybMapEffTime)不在此列——
 * 对照变更走 his_yb_map_log(三目录对照工作台留痕页签), 避免一处变更两处记录;
 * 字典回填名称/来源标识(*Name/*Src)与系统溯源列(srcType/srcDoc/srcCode)为派生值, 同样不跟踪。
 * 注意: MP updateById 忽略 null 字段, 调用方须在保存后回读最新行再传入 diff, 否则会把"未提交字段"误判为置空。
 */
@Service
public class CommunityDictEditLogService {

    private final HisDictEditLogMapper logMapper;

    public CommunityDictEditLogService(HisDictEditLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    /** 目录 -> 跟踪字段元数据 {属性名, 中文名} */
    private static final Map<String, String[][]> TRACKED = new HashMap<String, String[][]>();

    static {
        TRACKED.put("charge", new String[][]{
                {"itemCode", "项目编码"}, {"itemName", "项目名称"}, {"itemType", "项目大类"}, {"itemCat", "细分类别"},
                {"spec", "规格"}, {"unit", "单位"}, {"price", "单价"}, {"priceL1", "一级价"}, {"priceL2", "二级价"}, {"priceL3", "三级价"},
                {"natItemCode", "全国编码"}, {"locItemCode", "湖北编码"}, {"itemContent", "项目内涵"}, {"itemExcluded", "除外内容"},
                {"invoiceClass", "票据分类"}, {"acctClass", "会计科目"}, {"mrCostClass", "病案首页归并"}, {"catCode", "物价分类"},
                {"deptCaty", "医疗科室类别"}, {"medinsListCodg", "医保机构目录编码"}, {"medChrgitmType", "收费项目类别"},
                {"chrgitmLv", "甲乙丙类"}, {"selfpayProp", "自付比例"}, {"effDate", "生效日期"}, {"endDate", "作废日期"},
                {"status", "状态"}, {"memo", "备注"},
        });
        TRACKED.put("drug", new String[][]{
                {"drugCode", "院内药品码"}, {"drugStdCode", "本位码"}, {"approvalNo", "批准文号"},
                {"genericName", "通用名"}, {"tradeName", "商品名"}, {"majorClass", "大类"}, {"dosform", "剂型"},
                {"spec", "规格"}, {"manufacturer", "生产企业"}, {"mktHolder", "上市许可持有人"},
                {"chrgitmLv", "甲乙丙类"}, {"selfpayProp", "自付比例"}, {"payStdPrep", "医保支付标准"},
                {"negoFlag", "谈判药品标识"}, {"msdFlag", "门特对应标识"}, {"ltdSelfFlag", "限定自费标识"}, {"limitScope", "限定支付范围"},
                {"doseUnit", "剂量单位"}, {"unitDose", "每最小单位含药量"}, {"minUnit", "最小单位"}, {"packUnit", "大包装单位"},
                {"packRatio", "包装换算比"}, {"roundRule", "发药取整规则"},
                {"purchasePrice", "进货价"}, {"retailPrice", "零售价"}, {"zeroMargin", "零差率"},
                {"drugClass", "药品管理类别"}, {"abxGrade", "抗菌药物分级"}, {"otcFlag", "OTC标志"}, {"essentialFlag", "基本药物标志"},
                {"pregClass", "妊娠用药分级"}, {"skinTestFlag", "皮试标志"}, {"storageCond", "储存条件"}, {"maxQtyOnce", "单次处方最大量"},
                {"effDate", "生效日期"}, {"endDate", "作废日期"}, {"status", "状态"}, {"memo", "备注"},
        });
        TRACKED.put("cons", new String[][]{
                {"consCode", "院内耗材码"}, {"regCertNo", "注册证号"}, {"name", "耗材名称"},
                {"cat1", "一级分类"}, {"cat2", "二级分类"}, {"cat3", "三级分类"},
                {"specModel", "规格型号"}, {"material", "材质"}, {"feature", "特征"}, {"manufacturer", "生产企业"},
                {"minUnit", "最小单位"}, {"packUnit", "采购单位"}, {"packRatio", "包装换算比"},
                {"purchasePrice", "进货价"}, {"chargePrice", "收费价"}, {"chargeFlag", "收费方式"},
                {"chrgitmLv", "甲乙丙类"}, {"selfpayProp", "自付比例"}, {"payStd", "医保支付标准"},
                {"highValueFlag", "高值耗材标志"}, {"implantFlag", "植入类标志"}, {"sterileFlag", "无菌标志"},
                {"effDate", "生效日期"}, {"endDate", "作废日期"}, {"status", "状态"}, {"memo", "备注"},
        });
    }

    /** 编辑保存留痕: oldEntity 为保存前旧行, newEntity 为保存后回读的新行(同类型) */
    public void logChanges(String catalog, Object oldEntity, Object newEntity, String source) {
        String[][] fields = TRACKED.get(catalog);
        if (fields == null || oldEntity == null || newEntity == null) {
            return;
        }
        BeanWrapper ow = new BeanWrapperImpl(oldEntity);
        BeanWrapper nw = new BeanWrapperImpl(newEntity);
        Long id = toLong(nw.getPropertyValue("id"));
        if (id == null) {
            return;
        }
        for (String[] f : fields) {
            String ov = disp(ow, f[0]);
            String nv = disp(nw, f[0]);
            if (ov.equals(nv)) {
                continue;
            }
            insert(catalog, id, nw, f[0], f[1], ov, nv, source);
        }
    }

    /** 新增留痕: 记录初始快照(修改前为空, 仅记非空字段) */
    public void logCreate(String catalog, Object entity) {
        String[][] fields = TRACKED.get(catalog);
        if (fields == null || entity == null) {
            return;
        }
        BeanWrapper nw = new BeanWrapperImpl(entity);
        Long id = toLong(nw.getPropertyValue("id"));
        if (id == null) {
            return;
        }
        for (String[] f : fields) {
            String nv = disp(nw, f[0]);
            if (nv.isEmpty()) {
                continue;
            }
            insert(catalog, id, nw, f[0], f[1], null, nv, HisDictEditLog.SRC_CREATE);
        }
    }

    /** 修改记录分页: catalog/条目编码名称/字段中文名关键字 + 日期段(含两端), 时间倒序 */
    public IPage<HisDictEditLog> page(String catalog, String keyword, LocalDate start, LocalDate end, long page, long size) {
        QueryWrapper<HisDictEditLog> q = new QueryWrapper<>();
        if (StringUtils.hasText(catalog)) {
            q.eq("catalog_type", catalog);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like("item_code", kw).or().like("item_name", kw).or().like("field_label", kw));
        }
        if (start != null) {
            q.ge("change_time", start.atStartOfDay());
        }
        if (end != null) {
            q.le("change_time", end.atTime(23, 59, 59));
        }
        q.orderByDesc("change_time").orderByDesc("id");
        return logMapper.selectPage(new Page<HisDictEditLog>(page, size), q);
    }

    private void insert(String catalog, Long id, BeanWrapper nw, String fieldName, String label,
                        String ov, String nv, String source) {
        HisDictEditLog rec = new HisDictEditLog();
        rec.setCatalogType(catalog);
        rec.setCatalogId(id);
        rec.setItemCode(str(nw.getPropertyValue(itemCodeProp(catalog))));
        rec.setItemName(str(nw.getPropertyValue(itemNameProp(catalog))));
        rec.setFieldName(fieldName);
        rec.setFieldLabel(label);
        rec.setOldValue(trunc(ov));
        rec.setNewValue(trunc(nv));
        rec.setSource(source);
        LoginUser lu = UserContext.get();
        if (lu != null) {
            rec.setOperator(lu.getUsername());
            rec.setOperatorName(lu.getRealName());
            rec.setOrgId(lu.getOrgId());
        }
        rec.setChangeTime(LocalDateTime.now());
        logMapper.insert(rec);
    }

    private static String itemCodeProp(String catalog) {
        return "drug".equals(catalog) ? "drugCode" : ("cons".equals(catalog) ? "consCode" : "itemCode");
    }

    private static String itemNameProp(String catalog) {
        return "drug".equals(catalog) ? "genericName" : ("cons".equals(catalog) ? "name" : "itemName");
    }

    /** 字段展示值: 空串归一; 布尔类标志/状态/取整规则译为中文; 数值去尾零; 日期 toString */
    private static String disp(BeanWrapper bw, String field) {
        return format(field, bw.getPropertyValue(field));
    }

    private static String format(String field, Object v) {
        if (v == null) {
            return "";
        }
        if ("status".equals(field)) {
            return "1".equals(String.valueOf(v)) ? "启用" : "停用";
        }
        if ("chargeFlag".equals(field)) {
            return "1".equals(String.valueOf(v)) ? "单独收费" : "包含性";
        }
        if ("roundRule".equals(field)) {
            switch (String.valueOf(v)) {
                case "1": return "向上取整";
                case "2": return "向下取整";
                case "3": return "四舍五入";
                default: return String.valueOf(v);
            }
        }
        if (field.endsWith("Flag") || "zeroMargin".equals(field)) {
            String s = String.valueOf(v);
            if ("1".equals(s) || "Y".equalsIgnoreCase(s)) return "是";
            if ("0".equals(s) || "N".equalsIgnoreCase(s)) return "否";
            return s;
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    private static Long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String trunc(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
