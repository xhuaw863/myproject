package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.warehouse.HisStockAccept;
import com.yb.hi.entity.warehouse.HisStockAcceptItem;
import com.yb.hi.entity.warehouse.HisStockBalance;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockInItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisStockAcceptItemMapper;
import com.yb.hi.mapper.warehouse.HisStockAcceptMapper;
import com.yb.hi.mapper.warehouse.HisStockBalanceMapper;
import com.yb.hi.mapper.warehouse.HisStockInItemMapper;
import com.yb.hi.mapper.warehouse.HisStockInMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务验收服务(批次B): 对已确认入库单做财务验收(单张/按供应商集中), 验收通过回写来源入库单 accept_status=1;
 * 并查询未验收药品出库产生的平账记录(his_stock_balance)。
 * 单号 YS+yyyyMMdd+4位, 租户内唯一; tenant_id 由租户插件自动注入/过滤, 本类不显式处理租户。
 */
@Slf4j
@Service
public class StockAcceptService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final HisStockAcceptMapper acceptMapper;
    private final HisStockAcceptItemMapper acceptItemMapper;
    private final HisStockInMapper stockInMapper;
    private final HisStockInItemMapper stockInItemMapper;
    private final HisStockBalanceMapper balanceMapper;

    private String seqDate;
    private int seqNo = 0;

    public StockAcceptService(HisStockAcceptMapper acceptMapper, HisStockAcceptItemMapper acceptItemMapper,
                              HisStockInMapper stockInMapper, HisStockInItemMapper stockInItemMapper,
                              HisStockBalanceMapper balanceMapper) {
        this.acceptMapper = acceptMapper;
        this.acceptItemMapper = acceptItemMapper;
        this.stockInMapper = stockInMapper;
        this.stockInItemMapper = stockInItemMapper;
        this.balanceMapper = balanceMapper;
    }

    /* ================= 待验收入库查询 ================= */

    /** 待验收入库单分页: 已确认(status=1) 且未验收(accept_status=0) 且非红字单(red_of_id 为空) */
    public IPage<HisStockIn> pendingPage(Long orgId, Long warehouseId, Long supplierId, long page, long size) {
        LambdaQueryWrapper<HisStockIn> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockIn::getOrgId, orgId)
                .eq(warehouseId != null, HisStockIn::getWarehouseId, warehouseId)
                .eq(supplierId != null, HisStockIn::getSupplierId, supplierId)
                .eq(HisStockIn::getStatus, 1)
                .eq(HisStockIn::getAcceptStatus, 0)
                .isNull(HisStockIn::getRedOfId)
                .orderByAsc(HisStockIn::getId);
        return stockInMapper.selectPage(new Page<>(page, size), w);
    }

    /* ================= 验收单查询 ================= */

    public IPage<HisStockAccept> page(Long orgId, Integer status, long page, long size) {
        LambdaQueryWrapper<HisStockAccept> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockAccept::getOrgId, orgId)
                .eq(status != null, HisStockAccept::getStatus, status)
                .orderByDesc(HisStockAccept::getId);
        return acceptMapper.selectPage(new Page<>(page, size), w);
    }

    public Map<String, Object> detail(Long id) {
        HisStockAccept main = acceptMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "验收单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", acceptItemMapper.selectList(new LambdaQueryWrapper<HisStockAcceptItem>()
                .eq(HisStockAcceptItem::getAcceptId, id).orderByAsc(HisStockAcceptItem::getId)));
        return out;
    }

    /* ================= 验收处理 ================= */

    /** 单张入库验收: 对一张待验收入库单生成验收单(accept_type=1)并回写其 accept_status=1 */
    @Transactional(rollbackFor = Exception.class)
    public HisStockAccept acceptSingle(Long stockInId, Integer conclusion, String remark) {
        HisStockIn in = stockInMapper.selectById(stockInId);
        if (in == null) {
            throw new BizException(400, "入库单不存在");
        }
        if (in.getStatus() == null || in.getStatus() != 1) {
            throw new BizException("仅已确认的入库单可验收: " + in.getInNo());
        }
        if (in.getAcceptStatus() != null && in.getAcceptStatus() == 1) {
            throw new BizException("入库单已验收, 不可重复验收: " + in.getInNo());
        }
        if (in.getRedOfId() != null) {
            throw new BizException("红字冲账单无需验收: " + in.getInNo());
        }
        HisStockAccept accept = newAccept(in.getOrgId(), in.getWarehouseId(), in.getSupplierId(), 1, stockInId, conclusion, remark);
        acceptMapper.insert(accept);
        BigDecimal total = snapshotItems(accept.getId(), java.util.Collections.singletonList(in));
        accept.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        accept.setBillCount(1);
        acceptMapper.updateById(accept);
        markAccepted(stockInId);
        log.info("单张验收完成: acceptNo={}, stockInId={}, inNo={}, total={}", accept.getAcceptNo(), stockInId, in.getInNo(), accept.getTotalAmount());
        return acceptMapper.selectById(accept.getId());
    }

    /** 按供应商集中验收: 覆盖该供应商全部待验收入库单(accept_type=2), 一次性回写各单 accept_status=1 */
    @Transactional(rollbackFor = Exception.class)
    public HisStockAccept acceptBySupplier(Long orgId, Long supplierId, Integer conclusion, String remark) {
        if (supplierId == null) {
            throw new BizException(400, "集中验收需指定供应商");
        }
        List<HisStockIn> pending = stockInMapper.selectList(new LambdaQueryWrapper<HisStockIn>()
                .eq(orgId != null, HisStockIn::getOrgId, orgId)
                .eq(HisStockIn::getSupplierId, supplierId)
                .eq(HisStockIn::getStatus, 1)
                .eq(HisStockIn::getAcceptStatus, 0)
                .isNull(HisStockIn::getRedOfId)
                .orderByAsc(HisStockIn::getId));
        if (CollectionUtils.isEmpty(pending)) {
            throw new BizException("该供应商无待验收入库单");
        }
        HisStockIn first = pending.get(0);
        HisStockAccept accept = newAccept(first.getOrgId(), first.getWarehouseId(), supplierId, 2, null, conclusion, remark);
        acceptMapper.insert(accept);
        BigDecimal total = snapshotItems(accept.getId(), pending);
        accept.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        accept.setBillCount(pending.size());
        acceptMapper.updateById(accept);
        for (HisStockIn in : pending) {
            markAccepted(in.getId());
        }
        log.info("集中验收完成: acceptNo={}, supplierId={}, bills={}, total={}", accept.getAcceptNo(), supplierId, pending.size(), accept.getTotalAmount());
        return acceptMapper.selectById(accept.getId());
    }

    /* ================= 平账记录查询 ================= */

    public IPage<HisStockBalance> balancePage(Long orgId, Long warehouseId, long page, long size) {
        LambdaQueryWrapper<HisStockBalance> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockBalance::getOrgId, orgId)
                .eq(warehouseId != null, HisStockBalance::getWarehouseId, warehouseId)
                .orderByDesc(HisStockBalance::getId);
        return balanceMapper.selectPage(new Page<>(page, size), w);
    }

    /* ================= 内部实现 ================= */

    private HisStockAccept newAccept(Long orgId, Long warehouseId, Long supplierId, int acceptType,
                                     Long stockInId, Integer conclusion, String remark) {
        HisStockAccept accept = new HisStockAccept();
        accept.setOrgId(orgId);
        accept.setWarehouseId(warehouseId);
        accept.setSupplierId(supplierId);
        accept.setAcceptNo(generateAcceptNo());
        accept.setAcceptType(acceptType);
        accept.setStockInId(stockInId);
        accept.setBillCount(0);
        accept.setTotalAmount(BigDecimal.ZERO);
        accept.setConclusion(conclusion == null ? 1 : conclusion);
        accept.setAcceptBy(currentUserName());
        accept.setAcceptTime(LocalDateTime.now());
        accept.setStatus(1);
        accept.setRemark(StringUtils.hasText(remark) ? remark.trim() : null);
        return accept;
    }

    /** 按入库单快照验收明细, 返回金额合计 */
    private BigDecimal snapshotItems(Long acceptId, List<HisStockIn> ins) {
        BigDecimal total = BigDecimal.ZERO;
        for (HisStockIn in : ins) {
            List<HisStockInItem> items = stockInItemMapper.selectList(new LambdaQueryWrapper<HisStockInItem>()
                    .eq(HisStockInItem::getStockInId, in.getId()).orderByAsc(HisStockInItem::getId));
            for (HisStockInItem it : items) {
                HisStockAcceptItem ai = new HisStockAcceptItem();
                ai.setAcceptId(acceptId);
                ai.setStockInId(in.getId());
                ai.setDrugCatalogId(it.getDrugCatalogId());
                ai.setDrugCode(it.getDrugCode());
                ai.setDrugName(it.getDrugName());
                ai.setSpec(it.getSpec());
                ai.setBatchNo(it.getBatchNo());
                ai.setManufacturer(it.getManufacturer());
                ai.setQty(it.getQty());
                ai.setCostPrice(it.getCostPrice());
                ai.setAmount(it.getAmount());
                acceptItemMapper.insert(ai);
                total = total.add(it.getAmount() == null ? BigDecimal.ZERO : it.getAmount());
            }
        }
        return total;
    }

    private void markAccepted(Long stockInId) {
        HisStockIn upd = new HisStockIn();
        upd.setId(stockInId);
        upd.setAcceptStatus(1);
        stockInMapper.updateById(upd);
    }

    private synchronized String generateAcceptNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "YS" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "YS" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    private int maxSeqFromDb(String today) {
        HisStockAccept one = acceptMapper.selectOne(new LambdaQueryWrapper<HisStockAccept>()
                .likeRight(HisStockAccept::getAcceptNo, "YS" + today)
                .orderByDesc(HisStockAccept::getAcceptNo)
                .last("LIMIT 1"));
        if (one == null || one.getAcceptNo() == null || one.getAcceptNo().length() < 12) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getAcceptNo().substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean noExists(String no) {
        return acceptMapper.selectCount(new LambdaQueryWrapper<HisStockAccept>()
                .eq(HisStockAccept::getAcceptNo, no)) > 0;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }
}
