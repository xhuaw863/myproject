package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 医共体药品目录服务(L2, 牵头机构维护)。
 * 保存时按字典回填 *_name/*_src 三件套, 分页派生大包装参考价 packPrice = retailPrice * packRatio。
 */
@Service
public class HisDrugCatalogService extends ServiceImpl<HisDrugCatalogMapper, HisDrugCatalog> {

    /** 抗菌药物分级值域(湖北采集规范) */
    public static final String ABX_DICT_TYPE = "hbvalue";
    public static final String ABX_DICT_CODE = "HBCV08.50.029";

    /** 剂型统一取数源: 医共体统一字典卫健国标「药品剂型」(dict_type=归一化域名) */
    public static final String DOSFORM_DICT_TYPE = "药品剂型";

    private final StdDictQueryService stdDict;
    private final HisValDictService valDict;

    public HisDrugCatalogService(StdDictQueryService stdDict, HisValDictService valDict) {
        this.stdDict = stdDict;
        this.valDict = valDict;
    }

    /** 分页查询(关键字: 通用名/商品名/院内码/医保码; mapped=1已对照/0未对照/空全部) */
    public IPage<HisDrugCatalog> pageQuery(long page, long size, String keyword, Integer status, String mapped) {
        LambdaQueryChainWrapper<HisDrugCatalog> q = lambdaQuery()
                .eq(status != null, HisDrugCatalog::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisDrugCatalog::getGenericName, keyword)
                    .or().like(HisDrugCatalog::getTradeName, keyword)
                    .or().like(HisDrugCatalog::getDrugCode, keyword)
                    .or().like(HisDrugCatalog::getYbDrugCode, keyword)
                    .or().like(HisDrugCatalog::getPyCode, keyword)
                    .or().like(HisDrugCatalog::getAbbrCode, keyword));
        }
        if ("1".equals(mapped)) {
            q.isNotNull(HisDrugCatalog::getYbDrugCode).ne(HisDrugCatalog::getYbDrugCode, "");
        } else if ("0".equals(mapped)) {
            q.and(w -> w.isNull(HisDrugCatalog::getYbDrugCode).or().eq(HisDrugCatalog::getYbDrugCode, ""));
        }
        IPage<HisDrugCatalog> r = q.orderByDesc(HisDrugCatalog::getId).page(new Page<>(page, size));
        r.getRecords().forEach(this::derivePackPrice);
        return r;
    }

    /** 大包装参考价 = 零售价 * 包装换算比(派生展示, 不落库) */
    public void derivePackPrice(HisDrugCatalog d) {
        if (d != null && d.getRetailPrice() != null && d.getPackRatio() != null && d.getPackRatio() > 0) {
            d.setPackPrice(d.getRetailPrice().multiply(BigDecimal.valueOf(d.getPackRatio())));
        }
    }

    @Override
    public boolean save(HisDrugCatalog e) {
        backfillDict(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisDrugCatalog e) {
        backfillDict(e);
        return super.updateById(e);
    }

    /** 字典值回填名称与来源标识(遵循全局规范: 存编码 + 三件套) */
    public void backfillDict(HisDrugCatalog e) {
        if (e == null) {
            return;
        }
        // 拼音简码随通用名自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖
        if (StringUtils.hasText(e.getGenericName())) {
            e.setPyCode(PinyinUtil.initials(e.getGenericName()));
        }
        fillDosform(e);
        fillCv(e.getChrgitmLv(), "chrgitm_lv", e::setChrgitmLvName, e::setChrgitmLvSrc);
        fillCv(e.getDrugClass(), "drug_class", e::setDrugClassName, e::setDrugClassSrc);
        fillCv(e.getStorageCond(), "storage_cond", e::setStorageCondName, e::setStorageCondSrc);
        // 抗菌药物分级取湖北采集规范值域
        if (StringUtils.hasText(e.getAbxGrade())) {
            e.setAbxGradeName(stdDict.nameOf(ABX_DICT_TYPE, ABX_DICT_CODE, e.getAbxGrade()));
            e.setAbxGradeSrc(ABX_DICT_TYPE + ":" + ABX_DICT_CODE);
        } else {
            e.setAbxGradeName(null);
            e.setAbxGradeSrc(null);
        }
    }

    private void fillCv(String valCode, String dictCode,
                        java.util.function.Consumer<String> nameSetter,
                        java.util.function.Consumer<String> srcSetter) {
        if (StringUtils.hasText(valCode)) {
            nameSetter.accept(stdDict.nameOf("cv_code", dictCode, valCode));
            srcSetter.accept("cv_code:" + dictCode);
        } else {
            nameSetter.accept(null);
            srcSetter.accept(null);
        }
    }

    /**
     * 剂型回填(口径: 医共体统一字典用卫健国标「药品剂型」)。
     * 传入值可能是国标码或医保/接口中文文本: 先按码/唯一名归一到国标码, 命中则回填国标名与 src=药品剂型;
     * 归一不到(同名多义/国标无此剂型)则保留原值、名称按原样、src 标 '药品剂型:待核' 交人工确认(不猜码)。
     */
    private void fillDosform(HisDrugCatalog e) {
        String df = e.getDosform();
        if (!StringUtils.hasText(df)) {
            e.setDosformName(null);
            e.setDosformSrc(null);
            return;
        }
        String code = valDict.normalizeToCode(DOSFORM_DICT_TYPE, df);
        if (code != null) {
            e.setDosform(code);
            e.setDosformName(valDict.nameOf(DOSFORM_DICT_TYPE, code));
            e.setDosformSrc(DOSFORM_DICT_TYPE);
        } else {
            e.setDosformName(df.trim());
            e.setDosformSrc(DOSFORM_DICT_TYPE + ":待核");
        }
    }
}
