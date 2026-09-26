package com.yb.hi.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.dict.*;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.dict.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 医保字典查询接口(SPA用, 统一 R 响应)
 * 提供: 字典版本状态 / 目录分页浏览(供三目录医保对照选择)
 * 所有查询均按当前租户自动隔离(租户插件 + dict 表已加 tenant_id)。
 */
@RestController
@RequestMapping("/api/dict/query")
public class DictQueryController {

    private final DictVersionMapper dictVersionMapper;
    private final DrugCatalogMapper drugCatalogMapper;
    private final MedServiceCatalogMapper medServiceCatalogMapper;
    private final ConsumableCatalogMapper consumableCatalogMapper;
    private final DiseaseCatalogMapper diseaseCatalogMapper;

    public DictQueryController(DictVersionMapper dictVersionMapper,
                               DrugCatalogMapper drugCatalogMapper,
                               MedServiceCatalogMapper medServiceCatalogMapper,
                               ConsumableCatalogMapper consumableCatalogMapper,
                               DiseaseCatalogMapper diseaseCatalogMapper) {
        this.dictVersionMapper = dictVersionMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.medServiceCatalogMapper = medServiceCatalogMapper;
        this.consumableCatalogMapper = consumableCatalogMapper;
        this.diseaseCatalogMapper = diseaseCatalogMapper;
    }

    /** 字典版本状态(本院) */
    @GetMapping("/versions")
    public R<List<DictVersion>> versions() {
        return R.ok(dictVersionMapper.selectList(new QueryWrapper<DictVersion>().orderByAsc("dict_type")));
    }

    /** 目录分页浏览: type=drug|med_service|consumable|disease, 返回统一结构 {code,name,spec,extra} */
    @GetMapping("/catalog")
    public R<IPage<Map<String, Object>>> catalog(@RequestParam String type,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        boolean hasKw = StringUtils.hasText(keyword);
        switch (type) {
            case "drug": {
                QueryWrapper<DrugCatalog> qw = new QueryWrapper<>();
                qw.select(DrugCatalog.class, c -> !"raw_data".equals(c.getColumn()));
                if (hasKw) {
                    // 展示列: 编码/名称(商品名或通用名)/规格/生产企业, 均可检索
                    qw.and(w -> w.like("drug_prodname", keyword).or().like("drug_genname", keyword).or().like("med_list_codg", keyword)
                            .or().like("drug_spec", keyword).or().like("prod_entp_name", keyword).or().like("pinyin", keyword));
                }
                qw.orderByDesc("id");
                return R.ok(convert(drugCatalogMapper.selectPage(new Page<>(page, size), qw), e -> row(
                        e.getMedListCodg(), first(e.getDrugProdname(), e.getDrugGenname()), e.getDrugSpec(), e.getProdEntpName())));
            }
            case "med_service": {
                QueryWrapper<MedServiceCatalog> qw = new QueryWrapper<>();
                qw.select(MedServiceCatalog.class, c -> !"raw_data".equals(c.getColumn()));
                if (hasKw) {
                    // 展示列: 编码/项目名称/计价单位/项目类别, 均可检索
                    qw.and(w -> w.like("item_name", keyword).or().like("med_list_codg", keyword)
                            .or().like("prcunt_name", keyword).or().like("item_cat", keyword).or().like("py_code", keyword));
                }
                qw.orderByDesc("id");
                return R.ok(convert(medServiceCatalogMapper.selectPage(new Page<>(page, size), qw), e -> row(
                        e.getMedListCodg(), e.getItemName(), e.getPrcuntName(), e.getItemCat())));
            }
            case "consumable": {
                QueryWrapper<ConsumableCatalog> qw = new QueryWrapper<>();
                qw.select(ConsumableCatalog.class, c -> !"raw_data".equals(c.getColumn()));
                if (hasKw) {
                    // 展示列: 编码/耗材名称/规格/产品型号, 均可检索
                    qw.and(w -> w.like("cons_name", keyword).or().like("med_list_codg", keyword)
                            .or().like("spec", keyword).or().like("prod_model", keyword).or().like("py_code", keyword));
                }
                qw.orderByDesc("id");
                return R.ok(convert(consumableCatalogMapper.selectPage(new Page<>(page, size), qw), e -> row(
                        e.getMedListCodg(), e.getConsName(), e.getSpec(), e.getProdModel())));
            }
            case "disease": {
                QueryWrapper<DiseaseCatalog> qw = new QueryWrapper<>();
                qw.select(DiseaseCatalog.class, c -> !"raw_data".equals(c.getColumn()));
                if (hasKw) {
                    // 展示列: 诊断代码/诊断名称/疾病ID/类目名称, 均可检索
                    qw.and(w -> w.like("diag_name", keyword).or().like("diag_code", keyword).or().like("dise_code", keyword)
                            .or().like("cat_name", keyword).or().like("py_code", keyword));
                }
                qw.orderByDesc("id");
                return R.ok(convert(diseaseCatalogMapper.selectPage(new Page<>(page, size), qw), e -> row(
                        e.getDiagCode(), e.getDiagName(), e.getDiseCode(), e.getCatName())));
            }
            default:
                return R.fail("不支持的字典类型: " + type);
        }
    }

    private Map<String, Object> row(String code, String name, String spec, String extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("spec", spec);
        m.put("extra", extra);
        return m;
    }

    private String first(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private <T> IPage<Map<String, Object>> convert(IPage<T> src, Function<T, Map<String, Object>> fn) {
        Page<Map<String, Object>> out = new Page<>(src.getCurrent(), src.getSize(), src.getTotal());
        out.setRecords(src.getRecords().stream().map(fn).collect(Collectors.toList()));
        return out;
    }
}
