package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.pharmacy.RequisitionItemReq;
import com.yb.hi.dto.pharmacy.RequisitionReq;
import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutItemReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.pharmacy.HisRequisition;
import com.yb.hi.entity.pharmacy.HisRequisitionItem;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.entity.warehouse.HisStockOutItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.pharmacy.HisRequisitionItemMapper;
import com.yb.hi.mapper.pharmacy.HisRequisitionMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import com.yb.hi.service.warehouse.DrugStockService;
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
import java.util.List;
import java.util.Map;

/**
 * 药品请领服务(药房→药库): 发起(草稿/提交) → 药库审核并发货(对来源药库 out_type=4 出库, FIFO 扣减) → 药房确认收货(对药房库存位 in_type=4 入库)。
 * 复用 DrugStockService 的单据机制与乐观锁/FIFO/流水, 不新增库存维度; 请领是"配对出入库单"的高层编排。
 * 机构自治业务(药房是本院过程): 写操作用 requireSelfOrgWrite(控制器层), 服务层以单据归属机构为准执行库存联动。
 */
@Slf4j
@Service
public class RequisitionService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisRequisitionMapper reqMapper;
    private final HisRequisitionItemMapper itemMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final HisWarehouseDefMapper warehouseDefMapper;
    private final PharmacyDefService pharmacyDefService;
    private final DrugStockService drugStockService;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public RequisitionService(HisRequisitionMapper reqMapper, HisRequisitionItemMapper itemMapper,
                              HisDrugCatalogMapper drugCatalogMapper, HisWarehouseDefMapper warehouseDefMapper,
                              PharmacyDefService pharmacyDefService, DrugStockService drugStockService,
                              JdbcTemplate jdbcTemplate) {
        this.reqMapper = reqMapper;
        this.itemMapper = itemMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.warehouseDefMapper = warehouseDefMapper;
        this.pharmacyDefService = pharmacyDefService;
        this.drugStockService = drugStockService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 查询 ================= */

    /** 请领单分页(机构/药房/状态/单号或药品关键字) */
    public IPage<HisRequisition> page(Long orgId, Long pharmacyId, Integer status, String keyword, long page, long size) {
        LambdaQueryWrapper<HisRequisition> w = Wrappers.<HisRequisition>lambdaQuery()
                .eq(orgId != null, HisRequisition::getOrgId, orgId)
                .eq(pharmacyId != null, HisRequisition::getPharmacyId, pharmacyId)
                .eq(status != null, HisRequisition::getStatus, status)
                .orderByDesc(HisRequisition::getId);
        return reqMapper.selectPage(new Page<>(page, size), w);
    }

    /** 请领单详情 {main, items} */
    public Map<String, Object> detail(Long id) {
        HisRequisition main = reqMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "请领单不存在");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("main", main);
        out.put("items", listItems(id));
        return out;
    }

    /** 库位(药库/药房库存位)某药品可用库存合计: 供请领发起页显示可用量与超量提示 */
    public BigDecimal availableQty(Long orgId, Long warehouseId, Long drugCatalogId) {
        if (drugCatalogId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal q = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND org_id = ? AND warehouse_id = ? AND drug_catalog_id = ? AND status = 1 AND deleted = 0",
                BigDecimal.class, tenantId(), orgId, warehouseId, drugCatalogId);
        return q == null ? BigDecimal.ZERO : q;
    }

    /* ================= 发起 ================= */

    /**
     * 药房发起请领: 校验药房归属机构/启停、来源药库存在且启用(默认取药房关联药库), 落主表+明细并汇总金额。
     * submit=true 直接置待审核(1)并记录申请人/时间, 否则草稿(0)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRequisition create(RequisitionReq req) {
        if (req == null || req.getPharmacyId() == null) {
            throw new BizException(400, "请领药房不能为空");
        }
        if (CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "请领明细不能为空");
        }
        Long orgId = req.getOrgId() != null ? req.getOrgId() : currentOrgId();
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }
        HisPharmacyDef pharmacy = pharmacyDefService.requireEnabled(req.getPharmacyId(), orgId);
        Long toWarehouseId = req.getToWarehouseId() != null ? req.getToWarehouseId() : pharmacy.getWarehouseId();
        if (toWarehouseId == null) {
            throw new BizException(400, "请指定发货来源药库(该药房尚未关联药库)");
        }
        if (!warehouseEnabled(toWarehouseId, orgId)) {
            throw new BizException(400, "来源药库不存在或已停用: warehouseId=" + toWarehouseId);
        }

        HisRequisition main = new HisRequisition();
        main.setOrgId(orgId);
        main.setReqNo(generateNo());
        main.setPharmacyId(pharmacy.getId());
        main.setToWarehouseId(toWarehouseId);
        main.setStatus(req.isSubmit() ? 1 : 0);
        main.setApplyBy(currentUserName());
        if (req.isSubmit()) {
            main.setApplyTime(LocalDateTime.now());
        }
        main.setRemark(req.getRemark());
        main.setTotalAmount(BigDecimal.ZERO);
        reqMapper.insert(main);

        BigDecimal total = BigDecimal.ZERO;
        for (RequisitionItemReq it : req.getItems()) {
            if (it.getDrugCatalogId() == null) {
                throw new BizException(400, "请领明细缺少药品目录ID");
            }
            if (it.getQtyApply() == null || it.getQtyApply().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "请领数量必须大于0");
            }
            HisRequisitionItem item = new HisRequisitionItem();
            item.setRequisitionId(main.getId());
            item.setDrugCatalogId(it.getDrugCatalogId());
            item.setDrugCode(it.getDrugCode());
            item.setDrugName(it.getDrugName());
            item.setSpec(it.getSpec());
            item.setQtyApply(it.getQtyApply());
            BigDecimal retail = it.getRetailPrice();
            if (retail == null) {
                HisDrugCatalog drug = drugCatalogMapper.selectById(it.getDrugCatalogId());
                retail = drug == null ? null : drug.getRetailPrice();
                if (!StringUtils.hasText(item.getDrugCode()) && drug != null) {
                    item.setDrugCode(drug.getDrugCode());
                }
                if (!StringUtils.hasText(item.getDrugName()) && drug != null) {
                    item.setDrugName(StringUtils.hasText(drug.getGenericName()) ? drug.getGenericName() : drug.getDrugCode());
                }
                if (!StringUtils.hasText(item.getSpec()) && drug != null) {
                    item.setSpec(drug.getSpec());
                }
            }
            item.setRetailPrice(retail);
            if (retail != null) {
                item.setAmount(it.getQtyApply().multiply(retail).setScale(2, RoundingMode.HALF_UP));
                total = total.add(item.getAmount());
            }
            itemMapper.insert(item);
        }
        main.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        reqMapper.updateById(main);
        log.info("创建请领单: id={}, reqNo={}, orgId={}, pharmacyId={}, toWarehouseId={}, items={}, total={}, status={}",
                main.getId(), main.getReqNo(), orgId, pharmacy.getId(), toWarehouseId, req.getItems().size(),
                main.getTotalAmount(), main.getStatus());
        return reqMapper.selectById(main.getId());
    }

    /* ================= 审核并发货 ================= */

    /**
     * 药库审核: approved=false → 置已驳回(-1); approved=true → 发货=对来源药库创建出库单(out_type=4 调拨出)并确认
     * (确认按有效期 FIFO 乐观扣减药库库存, 实扣批次回填 his_stock_out_item), 审核数量=请领数量, 置已发货(2)。
     * 药库库存不足时 FIFO 扣减抛错, 整体回滚(请领单状态不变)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRequisition approve(Long id, boolean approved) {
        HisRequisition main = reqMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "请领单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 1) {
            throw new BizException("仅待审核的请领单可审核: " + main.getReqNo());
        }
        if (!approved) {
            main.setStatus(-1);
            main.setApproveBy(currentUserName());
            main.setApproveTime(LocalDateTime.now());
            reqMapper.updateById(main);
            log.info("请领驳回: reqNo={}", main.getReqNo());
            return main;
        }
        List<HisRequisitionItem> items = listItems(id);
        if (items.isEmpty()) {
            throw new BizException("请领单无明细, 无法发货");
        }
        // 发货: 对来源药库创建调拨出库单并确认(FIFO 扣减, 实扣批次回填出库明细)
        StockOutReq outReq = new StockOutReq();
        outReq.setOrgId(main.getOrgId());
        outReq.setWarehouseId(main.getToWarehouseId());
        outReq.setOutType(4);
        outReq.setRefId(main.getId());
        outReq.setRefNo(main.getReqNo());
        outReq.setRemark("药品请领发货: " + main.getReqNo());
        List<StockOutItemReq> outItems = new ArrayList<>();
        for (HisRequisitionItem it : items) {
            StockOutItemReq oi = new StockOutItemReq();
            oi.setDrugCatalogId(it.getDrugCatalogId());
            oi.setDrugCode(StringUtils.hasText(it.getDrugCode()) ? it.getDrugCode() : "DRUG" + it.getDrugCatalogId());
            oi.setDrugName(it.getDrugName());
            oi.setSpec(it.getSpec());
            BigDecimal qty = it.getQtyApproved() != null ? it.getQtyApproved() : it.getQtyApply();
            oi.setQty(qty);
            outItems.add(oi);
            // 回写审核(发货)数量与金额
            it.setQtyApproved(qty);
            if (it.getRetailPrice() != null) {
                it.setAmount(qty.multiply(it.getRetailPrice()).setScale(2, RoundingMode.HALF_UP));
            }
            itemMapper.updateById(it);
        }
        outReq.setItems(outItems);
        HisStockOut stockOut = drugStockService.createStockOut(outReq);
        drugStockService.confirmStockOut(stockOut.getId());

        main.setStatus(2);
        main.setApproveBy(currentUserName());
        main.setApproveTime(LocalDateTime.now());
        main.setStockOutId(stockOut.getId());
        reqMapper.updateById(main);
        log.info("请领发货完成: reqNo={}, stockOutId={}, outNo={}", main.getReqNo(), stockOut.getId(), stockOut.getOutNo());
        return reqMapper.selectById(id);
    }

    /* ================= 确认收货 ================= */

    /**
     * 药房确认收货: 读发货出库明细(实扣批次) → 对药房库存位创建调拨入库单(in_type=4)并确认(按批次 upsert 加量, 保留同批次与效期),
     * 实收数量=发货数量, 置已收货(3)。药房库存位缺失时拒绝(请先完成两级库存迁移)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRequisition receive(Long id) {
        HisRequisition main = reqMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "请领单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 2) {
            throw new BizException("仅已发货的请领单可收货: " + main.getReqNo());
        }
        if (main.getStockOutId() == null) {
            throw new BizException("请领单缺少发货出库单, 无法收货");
        }
        HisPharmacyDef pharmacy = pharmacyDefService.find(main.getPharmacyId());
        if (pharmacy == null) {
            throw new BizException(400, "请领药房不存在");
        }
        Long stockLocationId = pharmacy.getStockLocationId();
        if (stockLocationId == null) {
            throw new BizException("该药房尚未建立库存位(两级库存), 无法收货, 请联系管理员完成库存位初始化");
        }
        @SuppressWarnings("unchecked")
        List<HisStockOutItem> outItems = (List<HisStockOutItem>) drugStockService.stockOutDetail(main.getStockOutId()).get("items");
        if (CollectionUtils.isEmpty(outItems)) {
            throw new BizException("未找到发货出库明细, 无法收货");
        }
        StockInReq inReq = new StockInReq();
        inReq.setOrgId(main.getOrgId());
        inReq.setWarehouseId(stockLocationId);
        inReq.setInType(4);
        inReq.setRemark("药品请领收货: " + main.getReqNo());
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
            // 保留来源批次效期(供药房库存近效期管理): 按扣减的库存批次回查
            applyBatchDates(ii, oi.getDrugStockId());
            if (oi.getQty() != null && oi.getRetailPrice() != null) {
                ii.setAmount(oi.getQty().multiply(oi.getRetailPrice()).setScale(2, RoundingMode.HALF_UP));
            }
            inItems.add(ii);
        }
        inReq.setItems(inItems);
        HisStockIn stockIn = drugStockService.createStockIn(inReq);
        drugStockService.confirmStockIn(stockIn.getId());

        // 回写实收数量(=发货数量)
        for (HisRequisitionItem it : listItems(id)) {
            it.setQtyReceived(it.getQtyApproved() != null ? it.getQtyApproved() : it.getQtyApply());
            itemMapper.updateById(it);
        }
        main.setStatus(3);
        main.setReceiveBy(currentUserName());
        main.setReceiveTime(LocalDateTime.now());
        main.setStockInId(stockIn.getId());
        reqMapper.updateById(main);
        log.info("请领收货完成: reqNo={}, stockInId={}, inNo={}, pharmacyStockLocation={}",
                main.getReqNo(), stockIn.getId(), stockIn.getInNo(), stockLocationId);
        return reqMapper.selectById(id);
    }

    /** 作废草稿请领单(仅草稿/待审核未发货可作废) */
    @Transactional(rollbackFor = Exception.class)
    public HisRequisition voidRequisition(Long id) {
        HisRequisition main = reqMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "请领单不存在");
        }
        if (main.getStatus() == null || (main.getStatus() != 0 && main.getStatus() != 1)) {
            throw new BizException("仅草稿/待审核的请领单可作废: " + main.getReqNo());
        }
        main.setStatus(-2);
        reqMapper.updateById(main);
        log.info("作废请领单: id={}, reqNo={}", id, main.getReqNo());
        return main;
    }

    /* ================= 内部实现 ================= */

    private List<HisRequisitionItem> listItems(Long requisitionId) {
        return itemMapper.selectList(Wrappers.<HisRequisitionItem>lambdaQuery()
                .eq(HisRequisitionItem::getRequisitionId, requisitionId)
                .orderByAsc(HisRequisitionItem::getId));
    }

    /** 按扣减库存批次回查生产日期/有效期, 供收货入库保留批次效期 */
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

    /** 来源药库是否存在于该机构且启用(原生SQL显式租户过滤) */
    private boolean warehouseEnabled(Long warehouseId, Long orgId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_warehouse_def"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND status = 1 AND deleted = 0",
                Long.class, warehouseId, tenantId(), orgId);
        return cnt != null && cnt > 0;
    }

    /** 单号生成: QL+yyyyMMdd+4位序号; synchronized 唯一, 跨日重置从DB回读当日最大序号兜底 */
    private synchronized String generateNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "QL" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "QL" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    private int maxSeqFromDb(String today) {
        HisRequisition one = reqMapper.selectOne(Wrappers.<HisRequisition>lambdaQuery()
                .likeRight(HisRequisition::getReqNo, "QL" + today)
                .orderByDesc(HisRequisition::getReqNo)
                .last("LIMIT 1"));
        if (one == null || one.getReqNo() == null || one.getReqNo().length() < 14) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getReqNo().substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean noExists(String no) {
        return reqMapper.selectCount(Wrappers.<HisRequisition>lambdaQuery()
                .eq(HisRequisition::getReqNo, no)) > 0;
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

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
