package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.community.PriceAdjustReq;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisPriceAdjust;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisChargeItemMapper;
import com.yb.hi.mapper.community.HisConsCatalogMapper;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.community.HisPriceAdjustMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 医共体目录调价留痕服务(三目录统一)。
 * 调价是价格变更的唯一入口: 校验调价文号+生效日期必录, 写 his_price_adjust 留痕, 再同步目录当前价。
 */
@Service
public class CommunityPriceAdjustService {

    private final HisPriceAdjustMapper adjustMapper;
    private final HisChargeItemMapper chargeMapper;
    private final HisDrugCatalogMapper drugMapper;
    private final HisConsCatalogMapper consMapper;

    public CommunityPriceAdjustService(HisPriceAdjustMapper adjustMapper,
                                       HisChargeItemMapper chargeMapper,
                                       HisDrugCatalogMapper drugMapper,
                                       HisConsCatalogMapper consMapper) {
        this.adjustMapper = adjustMapper;
        this.chargeMapper = chargeMapper;
        this.drugMapper = drugMapper;
        this.consMapper = consMapper;
    }

    /** 调价记录分页(catalogType/catalogId 可选) */
    public IPage<HisPriceAdjust> pageQuery(long page, long size, String catalogType, Long catalogId) {
        QueryWrapper<HisPriceAdjust> q = new QueryWrapper<>();
        if (StringUtils.hasText(catalogType)) {
            q.eq("catalog_type", catalogType);
        }
        if (catalogId != null) {
            q.eq("catalog_id", catalogId);
        }
        q.orderByDesc("id");
        return adjustMapper.selectPage(new Page<>(page, size), q);
    }

    /** 执行调价: 写留痕 + 同步目录当前价(事务) */
    @Transactional(rollbackFor = Exception.class)
    public void adjust(PriceAdjustReq req) {
        if (req == null || !StringUtils.hasText(req.getCatalogType()) || req.getCatalogId() == null) {
            throw new BizException(400, "调价参数不完整");
        }
        if (req.getNewPrice() == null) {
            throw new BizException(400, "新价不能为空");
        }
        if (!StringUtils.hasText(req.getAdjustDocNo())) {
            throw new BizException(400, "调价文号必须录入");
        }
        if (req.getEffDate() == null) {
            throw new BizException(400, "生效日期必须录入");
        }
        if (!StringUtils.hasText(req.getPriceField())) {
            throw new BizException(400, "调价字段不能为空");
        }

        HisPriceAdjust log = new HisPriceAdjust();
        log.setCatalogType(req.getCatalogType());
        log.setCatalogId(req.getCatalogId());
        log.setPriceField(req.getPriceField());
        log.setNewPrice(req.getNewPrice());
        log.setAdjustDocNo(req.getAdjustDocNo());
        log.setEffDate(req.getEffDate());
        log.setReason(req.getReason());
        LoginUser lu = UserContext.get();
        log.setOperatorName(lu == null ? null : (StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername()));

        switch (req.getCatalogType()) {
            case "charge":
                applyCharge(req, log);
                break;
            case "drug":
                applyDrug(req, log);
                break;
            case "cons":
                applyCons(req, log);
                break;
            default:
                throw new BizException(400, "不支持的目录类型: " + req.getCatalogType());
        }
        adjustMapper.insert(log);
    }

    private void applyCharge(PriceAdjustReq req, HisPriceAdjust log) {
        HisChargeItem item = chargeMapper.selectById(req.getCatalogId());
        if (item == null) {
            throw new BizException("收费项目不存在");
        }
        int lv = req.getOrgLevel() == null ? levelOf(req.getPriceField()) : req.getOrgLevel();
        BigDecimal old;
        switch (lv) {
            case 1:
                old = item.getPriceL1();
                item.setPriceL1(req.getNewPrice());
                break;
            case 2:
                old = item.getPriceL2();
                item.setPriceL2(req.getNewPrice());
                break;
            case 3:
                old = item.getPriceL3();
                item.setPriceL3(req.getNewPrice());
                break;
            default:
                throw new BizException(400, "非法价格档次: " + lv);
        }
        item.setPrice(req.getNewPrice());
        chargeMapper.updateById(item);
        log.setCatalogName(item.getItemName());
        log.setOrgLevel(lv);
        log.setPriceField("price_l" + lv);
        log.setPriceLabel(lv + "级机构价格");
        log.setOldPrice(old);
    }

    private void applyDrug(PriceAdjustReq req, HisPriceAdjust log) {
        HisDrugCatalog d = drugMapper.selectById(req.getCatalogId());
        if (d == null) {
            throw new BizException("药品目录不存在");
        }
        BigDecimal old;
        if ("retail_price".equals(req.getPriceField())) {
            old = d.getRetailPrice();
            d.setRetailPrice(req.getNewPrice());
            log.setPriceLabel("零售价(最小单位)");
        } else if ("purchase_price".equals(req.getPriceField())) {
            old = d.getPurchasePrice();
            d.setPurchasePrice(req.getNewPrice());
            log.setPriceLabel("进货价(最小单位)");
        } else {
            throw new BizException(400, "非法药品调价字段: " + req.getPriceField());
        }
        drugMapper.updateById(d);
        log.setCatalogName(d.getGenericName());
        log.setOldPrice(old);
    }

    private void applyCons(PriceAdjustReq req, HisPriceAdjust log) {
        HisConsCatalog c = consMapper.selectById(req.getCatalogId());
        if (c == null) {
            throw new BizException("耗材目录不存在");
        }
        BigDecimal old;
        if ("charge_price".equals(req.getPriceField())) {
            old = c.getChargePrice();
            c.setChargePrice(req.getNewPrice());
            log.setPriceLabel("收费价");
        } else if ("purchase_price".equals(req.getPriceField())) {
            old = c.getPurchasePrice();
            c.setPurchasePrice(req.getNewPrice());
            log.setPriceLabel("进货价");
        } else {
            throw new BizException(400, "非法耗材调价字段: " + req.getPriceField());
        }
        consMapper.updateById(c);
        log.setCatalogName(c.getName());
        log.setOldPrice(old);
    }

    /** 从 price_l{n} 解析档次 */
    private int levelOf(String priceField) {
        if (priceField != null && priceField.length() == 8 && priceField.startsWith("price_l")) {
            char c = priceField.charAt(7);
            if (c >= '1' && c <= '3') {
                return c - '0';
            }
        }
        throw new BizException(400, "无法解析收费项目价格档次: " + priceField);
    }
}
