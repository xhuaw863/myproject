package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutItemReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.dto.warehouse.TransferReq;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.entity.warehouse.HisStockOutItem;
import com.yb.hi.entity.warehouse.HisTransfer;
import com.yb.hi.entity.warehouse.HisTransferItem;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisTransferItemMapper;
import com.yb.hi.mapper.warehouse.HisTransferMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 库存调拨服务(药库/药房库存位之间): create → ship(调出方 out_type=4 按指定批次扣减) → receive(调入方 in_type=4 入库, 保留同批次效期)。
 * 复用 DrugStockService 配对出入库机制; 跨库位必须同机构(from/to 均属该机构且启用)。
 */
@Slf4j
@Service
public class TransferService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisTransferMapper transferMapper;
    private final HisTransferItemMapper transferItemMapper;
    private final HisWarehouseDefMapper warehouseDefMapper;
    private final DrugStockService drugStockService;
    private final JdbcTemplate jdbcTemplate;

    private String seqDate;
    private int seqNo = 0;

    public TransferService(HisTransferMapper transferMapper, HisTransferItemMapper transferItemMapper,
                           HisWarehouseDefMapper warehouseDefMapper, DrugStockService drugStockService,
                           JdbcTemplate jdbcTemplate) {
        this.transferMapper = transferMapper;
        this.transferItemMapper = transferItemMapper;
        this.warehouseDefMapper = warehouseDefMapper;
        this.drugStockService = drugStockService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 查询 ================= */

    public IPage<HisTransfer> page(Long orgId, Integer status, String keyword, long page, long size) {
        return transferMapper.selectPage(new Page<>(page, size), Wrappers.<HisTransfer>lambdaQuery()
                .eq(orgId != null, HisTransfer::getOrgId, orgId)
                .eq(status != null, HisTransfer::getStatus, status)
                .like(StringUtils.hasText(keyword), HisTransfer::getTransferNo, keyword == null ? null : keyword.trim())
                .orderByDesc(HisTransfer::getId));
    }

    /** 可选库位列表(药库+药房库存位, 供调拨下拉): 按 kind/sort 排序 */
    public List<HisWarehouseDef> locations(Long orgId) {
        return warehouseDefMapper.selectList(Wrappers.<HisWarehouseDef>lambdaQuery()
                .eq(orgId != null, HisWarehouseDef::getOrgId, orgId)
                .eq(HisWarehouseDef::getStatus, 1)
                .orderByAsc(HisWarehouseDef::getKind)
                .orderByAsc(HisWarehouseDef::getSortNo)
                .orderByAsc(HisWarehouseDef::getId));
    }

    public Map<String, Object> detail(Long id) {
        HisTransfer main = transferMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调拨单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", listItems(id));
        return out;
    }

    /** 调出库位某药品批次可用量(供发起页显示/超量提示) */
    public BigDecimal availableQty(Long orgId, Long locationId, Long drugCatalogId, String batchNo) {
        BigDecimal q = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND org_id = ? AND warehouse_id = ? AND drug_catalog_id = ?"
                        + " AND batch_no = ? AND status = 1 AND deleted = 0",
                BigDecimal.class, tenantId(), orgId, locationId, drugCatalogId, batchNo);
        return q == null ? BigDecimal.ZERO : q;
    }

    /* ================= 建单 ================= */

    @Transactional(rollbackFor = Exception.class)
    public HisTransfer create(TransferReq req) {
        if (req == null || CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "调拨明细不能为空");
        }
        if (req.getFromLocationId() == null || req.getToLocationId() == null) {
            throw new BizException(400, "调出/调入库位不能为空");
        }
        if (req.getFromLocationId().equals(req.getToLocationId())) {
            throw new BizException(400, "调出与调入库位不能相同");
        }
        Long orgId = req.getOrgId() != null ? req.getOrgId() : currentOrgId();
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }
        HisWarehouseDef from = requireLocation(req.getFromLocationId(), orgId);
        HisWarehouseDef to = requireLocation(req.getToLocationId(), orgId);

        HisTransfer main = new HisTransfer();
        main.setOrgId(orgId);
        main.setTransferNo(generateNo());
        main.setFromLocationId(from.getId());
        main.setToLocationId(to.getId());
        main.setKind(resolveKind(from, to));
        main.setStatus(req.isSubmit() ? 1 : 0);
        main.setRemark(req.getRemark());
        main.setTotalAmount(BigDecimal.ZERO);
        transferMapper.insert(main);

        BigDecimal total = BigDecimal.ZERO;
        for (TransferReq.TransferItemReq it : req.getItems()) {
            if (it.getDrugCatalogId() == null || !StringUtils.hasText(it.getBatchNo())) {
                throw new BizException(400, "调拨明细需指定药品与批次");
            }
            if (it.getQty() == null || it.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "调拨数量必须大于0");
            }
            HisTransferItem item = new HisTransferItem();
            item.setTransferId(main.getId());
            item.setDrugCatalogId(it.getDrugCatalogId());
            item.setDrugCode(it.getDrugCode());
            item.setDrugName(it.getDrugName());
            item.setSpec(it.getSpec());
            item.setBatchNo(it.getBatchNo().trim());
            item.setQty(it.getQty());
            // 快照调出批次进/零售价(用于金额与调入价格一致)
            Map<String, Object> snap = stockSnapshot(orgId, from.getId(), it.getDrugCatalogId(), item.getBatchNo());
            if (snap != null) {
                item.setCostPrice(toBd(snap.get("cost_price")));
                item.setRetailPrice(toBd(snap.get("retail_price")));
                if (!StringUtils.hasText(item.getDrugName())) {
                    item.setDrugName(str(snap.get("drug_name")));
                }
                if (!StringUtils.hasText(item.getDrugCode())) {
                    item.setDrugCode(str(snap.get("drug_code")));
                }
                if (!StringUtils.hasText(item.getSpec())) {
                    item.setSpec(str(snap.get("spec")));
                }
            }
            if (item.getRetailPrice() != null) {
                item.setAmount(it.getQty().multiply(item.getRetailPrice()).setScale(2, RoundingMode.HALF_UP));
                total = total.add(item.getAmount());
            }
            transferItemMapper.insert(item);
        }
        main.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        transferMapper.updateById(main);
        log.info("创建调拨单: id={}, no={}, orgId={}, from={} to={} items={}",
                main.getId(), main.getTransferNo(), orgId, from.getId(), to.getId(), req.getItems().size());
        return transferMapper.selectById(main.getId());
    }

    /* ================= 调出 ================= */

    /** 调出: 对调出库位按指定批次创建出库单(out_type=4)并确认(乐观锁按批次扣), 置已调出(2)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisTransfer ship(Long id) {
        HisTransfer main = transferMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调拨单不存在");
        }
        if (main.getStatus() == null || (main.getStatus() != 0 && main.getStatus() != 1)) {
            throw new BizException("仅草稿/待调出的调拨单可调出: " + main.getTransferNo());
        }
        List<HisTransferItem> items = listItems(id);
        if (items.isEmpty()) {
            throw new BizException("调拨单无明细, 无法调出");
        }
        StockOutReq outReq = new StockOutReq();
        outReq.setOrgId(main.getOrgId());
        outReq.setWarehouseId(main.getFromLocationId());
        outReq.setOutType(4);
        outReq.setRefId(main.getId());
        outReq.setRefNo(main.getTransferNo());
        outReq.setRemark("库存调拨出: " + main.getTransferNo());
        List<StockOutItemReq> outItems = new ArrayList<>();
        for (HisTransferItem it : items) {
            Long stockId = findStockId(main.getOrgId(), main.getFromLocationId(), it.getDrugCatalogId(), it.getBatchNo());
            if (stockId == null) {
                throw new BizException("调出库位无该批次库存: " + it.getDrugName() + " 批号" + it.getBatchNo());
            }
            StockOutItemReq oi = new StockOutItemReq();
            oi.setDrugStockId(stockId);
            oi.setQty(it.getQty());
            outItems.add(oi);
        }
        outReq.setItems(outItems);
        HisStockOut stockOut = drugStockService.createStockOut(outReq);
        drugStockService.confirmStockOut(stockOut.getId());

        main.setStatus(2);
        main.setShipBy(currentUserName());
        main.setShipTime(LocalDateTime.now());
        main.setStockOutId(stockOut.getId());
        transferMapper.updateById(main);
        log.info("调拨出完成: no={}, stockOutId={}", main.getTransferNo(), stockOut.getId());
        return transferMapper.selectById(id);
    }

    /* ================= 调入 ================= */

    /** 调入: 读调出出库明细(实扣批次) → 对调入库位创建入库单(in_type=4)并确认(保留同批次效期), 置已调入(3)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisTransfer receive(Long id) {
        HisTransfer main = transferMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调拨单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 2) {
            throw new BizException("仅已调出的调拨单可调入: " + main.getTransferNo());
        }
        if (main.getStockOutId() == null) {
            throw new BizException("调拨单缺少调出出库单, 无法调入");
        }
        @SuppressWarnings("unchecked")
        List<HisStockOutItem> outItems = (List<HisStockOutItem>) drugStockService.stockOutDetail(main.getStockOutId()).get("items");
        if (CollectionUtils.isEmpty(outItems)) {
            throw new BizException("未找到调出出库明细, 无法调入");
        }
        StockInReq inReq = new StockInReq();
        inReq.setOrgId(main.getOrgId());
        inReq.setWarehouseId(main.getToLocationId());
        inReq.setInType(4);
        inReq.setRemark("库存调拨入: " + main.getTransferNo());
        List<StockInItemReq> inItems = new ArrayList<>();
        for (HisStockOutItem oi : outItems) {
            StockInItemReq ii = new StockInItemReq();
            ii.setDrugCatalogId(oi.getDrugCatalogId());
            ii.setDrugCode(oi.getDrugCode());
            ii.setDrugName(oi.getDrugName());
            ii.setSpec(oi.getSpec());
            ii.setBatchNo(oi.getBatchNo());
            ii.setQty(oi.getQty());
            ii.setCostPrice(oi.getCostPrice());
            ii.setRetailPrice(oi.getRetailPrice());
            applyBatchDates(ii, oi.getDrugStockId());
            if (oi.getQty() != null && oi.getRetailPrice() != null) {
                ii.setAmount(oi.getQty().multiply(oi.getRetailPrice()).setScale(2, RoundingMode.HALF_UP));
            }
            inItems.add(ii);
        }
        inReq.setItems(inItems);
        HisStockIn stockIn = drugStockService.createStockIn(inReq);
        drugStockService.confirmStockIn(stockIn.getId());

        main.setStatus(3);
        main.setReceiveBy(currentUserName());
        main.setReceiveTime(LocalDateTime.now());
        main.setStockInId(stockIn.getId());
        transferMapper.updateById(main);
        log.info("调拨入完成: no={}, stockInId={}", main.getTransferNo(), stockIn.getId());
        return transferMapper.selectById(id);
    }

    /** 作废草稿/待调出调拨单 */
    @Transactional(rollbackFor = Exception.class)
    public HisTransfer voidTransfer(Long id) {
        HisTransfer main = transferMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调拨单不存在");
        }
        if (main.getStatus() == null || (main.getStatus() != 0 && main.getStatus() != 1)) {
            throw new BizException("仅草稿/待调出的调拨单可作废: " + main.getTransferNo());
        }
        main.setStatus(-2);
        transferMapper.updateById(main);
        return main;
    }

    /* ================= 内部实现 ================= */

    private List<HisTransferItem> listItems(Long transferId) {
        return transferItemMapper.selectList(Wrappers.<HisTransferItem>lambdaQuery()
                .eq(HisTransferItem::getTransferId, transferId).orderByAsc(HisTransferItem::getId));
    }

    /** 库位存在且启用且属该机构(药库或药房库存位) */
    private HisWarehouseDef requireLocation(Long locationId, Long orgId) {
        HisWarehouseDef loc = warehouseDefMapper.selectById(locationId);
        if (loc == null || !orgId.equals(loc.getOrgId())) {
            throw new BizException(400, "库位不存在或不属于本机构: locationId=" + locationId);
        }
        if (loc.getStatus() == null || loc.getStatus() != 1) {
            throw new BizException(400, "库位已停用: " + loc.getName());
        }
        return loc;
    }

    private String resolveKind(HisWarehouseDef from, HisWarehouseDef to) {
        boolean fromPh = "PHARMACY".equals(from.getKind());
        boolean toPh = "PHARMACY".equals(to.getKind());
        if (fromPh && toPh) {
            return "PHARMACY2PHARMACY";
        }
        if (!fromPh && toPh) {
            return "WH2PHARMACY";
        }
        return "WH2WH";
    }

    private Long findStockId(Long orgId, Long locationId, Long drugCatalogId, String batchNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM his_drug_stock WHERE tenant_id = ? AND org_id = ? AND warehouse_id = ?"
                        + " AND drug_catalog_id = ? AND batch_no = ? AND status = 1 AND deleted = 0 LIMIT 1",
                tenantId(), orgId, locationId, drugCatalogId, batchNo);
        return rows.isEmpty() ? null : toLong(rows.get(0).get("id"));
    }

    private Map<String, Object> stockSnapshot(Long orgId, Long locationId, Long drugCatalogId, String batchNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT drug_code, drug_name, spec, cost_price, retail_price FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND org_id = ? AND warehouse_id = ? AND drug_catalog_id = ?"
                        + " AND batch_no = ? AND deleted = 0 LIMIT 1",
                tenantId(), orgId, locationId, drugCatalogId, batchNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void applyBatchDates(StockInItemReq ii, Long drugStockId) {
        if (drugStockId == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(prod_date, '%Y-%m-%d') AS prod_date, DATE_FORMAT(exp_date, '%Y-%m-%d') AS exp_date,"
                        + " manufacturer FROM his_drug_stock WHERE id = ? AND tenant_id = ? AND deleted = 0",
                drugStockId, tenantId());
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> r = rows.get(0);
        ii.setProdDate(str(r.get("prod_date")));
        ii.setExpDate(str(r.get("exp_date")));
        if (!StringUtils.hasText(ii.getManufacturer())) {
            ii.setManufacturer(str(r.get("manufacturer")));
        }
    }

    private synchronized String generateNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "DB" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "DB" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    private int maxSeqFromDb(String today) {
        HisTransfer one = transferMapper.selectOne(Wrappers.<HisTransfer>lambdaQuery()
                .likeRight(HisTransfer::getTransferNo, "DB" + today)
                .orderByDesc(HisTransfer::getTransferNo)
                .last("LIMIT 1"));
        if (one == null || one.getTransferNo() == null || one.getTransferNo().length() < 14) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getTransferNo().substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean noExists(String no) {
        return transferMapper.selectCount(Wrappers.<HisTransfer>lambdaQuery()
                .eq(HisTransfer::getTransferNo, no)) > 0;
    }

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getOrgId();
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
