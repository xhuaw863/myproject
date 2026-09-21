package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
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

    private final StdDictQueryService stdDict;

    public HisDrugCatalogService(StdDictQueryService stdDict) {
        this.stdDict = stdDict;
    }

    /** 分页查询(关键字: 通用名/商品名/院内码/医保码) */
    public IPage<HisDrugCatalog> pageQuery(long page, long size, String keyword, Integer status) {
        LambdaQueryChainWrapper<HisDrugCatalog> q = lambdaQuery()
                .eq(status != null, HisDrugCatalog::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisDrugCatalog::getGenericName, keyword)
                    .or().like(HisDrugCatalog::getTradeName, keyword)
                    .or().like(HisDrugCatalog::getDrugCode, keyword)
                    .or().like(HisDrugCatalog::getYbDrugCode, keyword));
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
        fillCv(e.getDosform(), "dosform", e::setDosformName, e::setDosformSrc);
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
}
