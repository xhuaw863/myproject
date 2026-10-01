package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.community.HisConsCatalogMapper;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 医共体耗材目录服务(L2, 牵头机构维护)。保存时回填甲乙丙类名称与来源标识。
 */
@Service
public class HisConsCatalogService extends ServiceImpl<HisConsCatalogMapper, HisConsCatalog> {

    private final StdDictQueryService stdDict;

    public HisConsCatalogService(StdDictQueryService stdDict) {
        this.stdDict = stdDict;
    }

    /** 分页查询(关键字: 名称/院内码/医保码/注册证号) */
    public IPage<HisConsCatalog> pageQuery(long page, long size, String keyword, Integer status, String mapped) {
        LambdaQueryChainWrapper<HisConsCatalog> q = lambdaQuery()
                .eq(status != null, HisConsCatalog::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisConsCatalog::getName, keyword)
                    .or().like(HisConsCatalog::getConsCode, keyword)
                    .or().like(HisConsCatalog::getYbConsCode, keyword)
                    .or().like(HisConsCatalog::getRegCertNo, keyword)
                    .or().like(HisConsCatalog::getPyCode, keyword)
                    .or().like(HisConsCatalog::getAbbrCode, keyword));
        }
        if ("1".equals(mapped)) {
            q.isNotNull(HisConsCatalog::getYbConsCode).ne(HisConsCatalog::getYbConsCode, "");
        } else if ("0".equals(mapped)) {
            q.and(w -> w.isNull(HisConsCatalog::getYbConsCode).or().eq(HisConsCatalog::getYbConsCode, ""));
        }
        return q.orderByDesc(HisConsCatalog::getId).page(new Page<>(page, size));
    }

    @Override
    public boolean save(HisConsCatalog e) {
        backfillDict(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisConsCatalog e) {
        backfillDict(e);
        return super.updateById(e);
    }

    /** 甲乙丙类回填名称与来源标识 */
    public void backfillDict(HisConsCatalog e) {
        if (e == null) {
            return;
        }
        // 拼音简码随耗材名自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖
        if (StringUtils.hasText(e.getName())) {
            e.setPyCode(PinyinUtil.initials(e.getName()));
        }
        if (StringUtils.hasText(e.getChrgitmLv())) {
            e.setChrgitmLvName(stdDict.nameOf("cv_code", "chrgitm_lv", e.getChrgitmLv()));
            e.setChrgitmLvSrc("cv_code:chrgitm_lv");
        } else {
            e.setChrgitmLvName(null);
            e.setChrgitmLvSrc(null);
        }
    }
}
