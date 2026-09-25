package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.StockDeductResult;
import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutItemReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.warehouse.HisDrugStock;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockInItem;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.entity.warehouse.HisStockOutItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisDrugStockMapper;
import com.yb.hi.mapper.warehouse.HisStockInItemMapper;
import com.yb.hi.mapper.warehouse.HisStockInMapper;
import com.yb.hi.mapper.warehouse.HisStockOutItemMapper;
import com.yb.hi.mapper.warehouse.HisStockOutMapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 药库服务(库存/入库/出库/流水):
 * - 批次级库存记账, 确认入库按 (org+药品+批次) upsert 库存行;
 * - 扣减一律乐观锁 UPDATE ... WHERE qty >= ?, affected=0 即库存不足;
 * - 未指定批次的出库按有效期 FIFO(先到期先用)自动拆批扣减;
 * - 单号 前缀+yyyyMMdd+4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号;
 * - tenant_id 由租户插件自动注入/过滤, 本类不显式处理租户。
 */
@Slf4j
@Service
public class DrugStockService {

    private final HisDrugStockMapper stockMapper;
    private final HisStockInMapper stockInMapper;
    private final HisStockInItemMapper stockInItemMapper;
    private final HisStockOutMapper stockOutMapper;
    private final HisStockOutItemMapper stockOutItemMapper;
    private final SysOrgMapper orgMapper;

    /** 单号内存序号(synchronized 保证唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public DrugStockService(HisDrugStockMapper stockMapper, HisStockInMapper stockInMapper,
                            HisStockInItemMapper stockInItemMapper, HisStockOutMapper stockOutMapper,
                            HisStockOutItemMapper stockOutItemMapper, SysOrgMapper orgMapper) {
        this.stockMapper = stockMapper;
        this.stockInMapper = stockInMapper;
        this.stockInItemMapper = stockInItemMapper;
        this.stockOutMapper = stockOutMapper;
        this.stockOutItemMapper = stockOutItemMapper;
        this.orgMapper = orgMapper;
    }

    /* ================= 库存管理 ================= */

    /** 库存分页查询(keyword: 名称/编码/批号/厂家; lowStock=true 时 qty<=warn_qty) */
    public IPage<HisDrugStock> stockPage(Long orgId, String keyword, Boolean lowStock, long page, long size) {
        LambdaQueryWrapper<HisDrugStock> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisDrugStock::getOrgId, orgId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(HisDrugStock::getDrugName, kw)
                    .or().like(HisDrugStock::getDrugCode, kw)
                    .or().like(HisDrugStock::getBatchNo, kw)
                    .or().like(HisDrugStock::getManufacturer, kw));
        }
        if (Boolean.TRUE.equals(lowStock)) {
            w.apply("qty <= warn_qty");
        }
        w.orderByAsc(HisDrugStock::getDrugName).orderByAsc(HisDrugStock::getExpDate).orderByAsc(HisDrugStock::getId);
        return stockMapper.selectPage(new Page<>(page, size), w);
    }

    /** 低库存预警列表(qty<=warn_qty 且未停用) */
    public List<HisDrugStock> lowStockAlert(Long orgId) {
        LambdaQueryWrapper<HisDrugStock> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisDrugStock::getOrgId, orgId)
                .eq(HisDrugStock::getStatus, 1)
                .apply("qty <= warn_qty")
                .orderByAsc(HisDrugStock::getDrugName);
        return stockMapper.selectList(w);
    }

    /** 库存导出数据 {head, rows}: 与列表同口径, 机构名列映射机构名 */
    public Map<String, Object> exportStock(Long orgId) {
        LambdaQueryWrapper<HisDrugStock> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisDrugStock::getOrgId, orgId)
                .orderByAsc(HisDrugStock::getOrgId)
                .orderByAsc(HisDrugStock::getDrugName)
                .orderByAsc(HisDrugStock::getExpDate);
        List<HisDrugStock> list = stockMapper.selectList(w);
        Map<Long, String> orgNames = new LinkedHashMap<>();
        for (SysOrg o : orgMapper.selectList(null)) {
            orgNames.put(o.getId(), o.getOrgName());
        }
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"所属机构", "药品编码", "药品名称", "规格", "剂型", "批号", "生产厂家",
                "库存数量", "进价", "零售价", "生产日期", "有效期", "预警量", "状态"}) {
            head.add(Collections.singletonList(h));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (HisDrugStock s : list) {
            rows.add(Arrays.asList(
                    s.getOrgId() == null ? "" : nz(orgNames.get(s.getOrgId())),
                    nz(s.getDrugCode()), nz(s.getDrugName()), nz(s.getSpec()), nz(s.getDosform()),
                    nz(s.getBatchNo()), nz(s.getManufacturer()),
                    s.getQty() == null ? BigDecimal.ZERO : s.getQty(),
                    s.getCostPrice() == null ? "" : s.getCostPrice(),
                    s.getRetailPrice() == null ? "" : s.getRetailPrice(),
                    s.getProdDate() == null ? "" : s.getProdDate().toString(),
                    s.getExpDate() == null ? "" : s.getExpDate().toString(),
                    s.getWarnQty() == null ? "" : s.getWarnQty(),
                    s.getStatus() != null && s.getStatus() == 1 ? "正常" : "停用"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /* ================= 入库管理 ================= */

    /** 入库单分页(status; 单据创建日期范围 yyyy-MM-dd) */
    public IPage<HisStockIn> stockInPage(Long orgId, Integer status, String startDate, String endDate, long page, long size) {
        LambdaQueryWrapper<HisStockIn> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockIn::getOrgId, orgId)
                .eq(status != null, HisStockIn::getStatus, status);
        LocalDateTime[] range = parseDateRange(startDate, endDate);
        if (range[0] != null) {
            w.ge(HisStockIn::getCreateTime, range[0]);
        }
        if (range[1] != null) {
            w.lt(HisStockIn::getCreateTime, range[1]);
        }
        w.orderByDesc(HisStockIn::getId);
        return stockInMapper.selectPage(new Page<>(page, size), w);
    }

    /** 入库单详情 {main, items} */
    public Map<String, Object> stockInDetail(Long id) {
        HisStockIn main = stockInMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "入库单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", listInItems(id));
        return out;
    }

    /** 创建入库单(草稿): 单号 RK+yyyyMMdd+4位序号, 明细落库并汇总总金额 */
    @Transactional(rollbackFor = Exception.class)
    public HisStockIn createStockIn(StockInReq req) {
        if (req == null || CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "入库明细不能为空");
        }
        if (req.getOrgId() == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (req.getInType() == null) {
            req.setInType(1);
        }
        HisStockIn main = new HisStockIn();
        main.setOrgId(req.getOrgId());
        main.setInNo(generateNo("RK"));
        main.setInType(req.getInType());
        main.setSupplier(req.getSupplier());
        main.setSupplierContact(req.getSupplierContact());
        main.setStatus(0);
        main.setRemark(req.getRemark());
        main.setTotalAmount(BigDecimal.ZERO);
        stockInMapper.insert(main);

        BigDecimal total = BigDecimal.ZERO;
        for (StockInItemReq it : req.getItems()) {
            HisStockInItem item = buildInItem(main.getId(), it);
            stockInItemMapper.insert(item);
            total = total.add(nvl(item.getAmount()));
        }
        main.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        stockInMapper.updateById(main);
        log.info("创建入库单: id={}, inNo={}, items={}, total={}", main.getId(), main.getInNo(), req.getItems().size(), main.getTotalAmount());
        return main;
    }

    /**
     * 确认入库(幂等): 草稿→已确认。
     * 遍历明细按 (org+药品+批次) upsert 库存: 存在原子加量, 不存在新建(并发撞唯一键转原子加量)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmStockIn(Long id) {
        HisStockIn main = stockInMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "入库单不存在");
        }
        if (main.getStatus() != null && main.getStatus() == 1) {
            log.info("入库单已确认, 幂等返回: id={}, inNo={}", id, main.getInNo());
            return;
        }
        if (main.getStatus() == null || main.getStatus() != 0) {
            throw new BizException("入库单已作废, 不可确认: " + main.getInNo());
        }
        List<HisStockInItem> items = listInItems(id);
        for (HisStockInItem item : items) {
            upsertStock(main.getOrgId(), item);
        }
        HisStockIn upd = new HisStockIn();
        upd.setId(id);
        upd.setStatus(1);
        upd.setConfirmBy(currentUserName());
        upd.setConfirmTime(LocalDateTime.now());
        stockInMapper.updateById(upd);
        log.info("确认入库: id={}, inNo={}, items={}", id, main.getInNo(), items.size());
    }

    /** 作废入库单: 仅草稿态可作废(已确认单据已影响库存, 不可作废) */
    @Transactional(rollbackFor = Exception.class)
    public void voidStockIn(Long id) {
        HisStockIn main = stockInMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "入库单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 0) {
            throw new BizException("仅草稿态入库单可作废: " + main.getInNo());
        }
        HisStockIn upd = new HisStockIn();
        upd.setId(id);
        upd.setStatus(2);
        stockInMapper.updateById(upd);
        log.info("作废入库单: id={}, inNo={}", id, main.getInNo());
    }

    /* ================= 出库管理 ================= */

    /** 出库单分页(status; 单据创建日期范围 yyyy-MM-dd) */
    public IPage<HisStockOut> stockOutPage(Long orgId, Integer status, String startDate, String endDate, long page, long size) {
        LambdaQueryWrapper<HisStockOut> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockOut::getOrgId, orgId)
                .eq(status != null, HisStockOut::getStatus, status);
        LocalDateTime[] range = parseDateRange(startDate, endDate);
        if (range[0] != null) {
            w.ge(HisStockOut::getCreateTime, range[0]);
        }
        if (range[1] != null) {
            w.lt(HisStockOut::getCreateTime, range[1]);
        }
        w.orderByDesc(HisStockOut::getId);
        return stockOutMapper.selectPage(new Page<>(page, size), w);
    }

    /** 出库单详情 {main, items} */
    public Map<String, Object> stockOutDetail(Long id) {
        HisStockOut main = stockOutMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "出库单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", stockOutItemMapper.selectList(new LambdaQueryWrapper<HisStockOutItem>()
                .eq(HisStockOutItem::getStockOutId, id).orderByAsc(HisStockOutItem::getId)));
        return out;
    }

    /** 创建出库单(草稿): 单号 CK+yyyyMMdd+4位序号; 明细可指定批次或仅指定药品(确认时FIFO) */
    @Transactional(rollbackFor = Exception.class)
    public HisStockOut createStockOut(StockOutReq req) {
        if (req == null || CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "出库明细不能为空");
        }
        if (req.getOrgId() == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (req.getOutType() == null) {
            req.setOutType(1);
        }
        HisStockOut main = new HisStockOut();
        main.setOrgId(req.getOrgId());
        main.setOutNo(generateNo("CK"));
        main.setOutType(req.getOutType());
        main.setRefId(req.getRefId());
        main.setRefNo(req.getRefNo());
        main.setStatus(0);
        main.setRemark(req.getRemark());
        main.setTotalAmount(BigDecimal.ZERO);
        stockOutMapper.insert(main);

        BigDecimal total = BigDecimal.ZERO;
        for (StockOutItemReq it : req.getItems()) {
            HisStockOutItem item = buildOutItem(main, it);
            stockOutItemMapper.insert(item);
            total = total.add(nvl(item.getAmount()));
        }
        main.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        stockOutMapper.updateById(main);
        log.info("创建出库单: id={}, outNo={}, items={}, total={}", main.getId(), main.getOutNo(), req.getItems().size(), main.getTotalAmount());
        return main;
    }

    /**
     * 确认出库(幂等): 草稿→已确认。
     * 指定批次明细: 乐观锁直接扣该批次(affected=0 抛库存不足);
     * 未指定批次明细: FIFO 扣减, 首批回填原明细行、拆批追加新明细行, 保证明细与实际扣减一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmStockOut(Long id) {
        HisStockOut main = stockOutMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "出库单不存在");
        }
        if (main.getStatus() != null && main.getStatus() == 1) {
            log.info("出库单已确认, 幂等返回: id={}, outNo={}", id, main.getOutNo());
            return;
        }
        if (main.getStatus() == null || main.getStatus() != 0) {
            throw new BizException("出库单已作废, 不可确认: " + main.getOutNo());
        }
        List<HisStockOutItem> items = stockOutItemMapper.selectList(new LambdaQueryWrapper<HisStockOutItem>()
                .eq(HisStockOutItem::getStockOutId, id).orderByAsc(HisStockOutItem::getId));
        BigDecimal total = BigDecimal.ZERO;
        for (HisStockOutItem item : items) {
            if (item.getDrugStockId() != null) {
                confirmDeductBatch(item);
                total = total.add(nvl(item.getAmount()));
            } else {
                List<StockDeductResult> deducts = deductFifo(main.getOrgId(), item.getDrugCatalogId(), item.getQty());
                StockDeductResult first = deducts.get(0);
                item.setDrugStockId(first.getStockId());
                item.setBatchNo(first.getBatchNo());
                item.setCostPrice(first.getCostPrice());
                item.setRetailPrice(first.getRetailPrice());
                item.setAmount(calcOutAmount(first.getDeductQty(), first.getRetailPrice(), first.getCostPrice()));
                stockOutItemMapper.updateById(item);
                total = total.add(nvl(item.getAmount()));
                // FIFO 拆批: 首批以外的扣减追加为明细行(先进先出可追溯)
                for (int i = 1; i < deducts.size(); i++) {
                    StockDeductResult r = deducts.get(i);
                    HisStockOutItem extra = new HisStockOutItem();
                    extra.setStockOutId(id);
                    extra.setDrugStockId(r.getStockId());
                    extra.setDrugCatalogId(item.getDrugCatalogId());
                    extra.setDrugCode(item.getDrugCode());
                    extra.setDrugName(item.getDrugName());
                    extra.setSpec(item.getSpec());
                    extra.setBatchNo(r.getBatchNo());
                    extra.setQty(r.getDeductQty());
                    extra.setCostPrice(r.getCostPrice());
                    extra.setRetailPrice(r.getRetailPrice());
                    extra.setAmount(calcOutAmount(r.getDeductQty(), r.getRetailPrice(), r.getCostPrice()));
                    stockOutItemMapper.insert(extra);
                    total = total.add(nvl(extra.getAmount()));
                }
            }
        }
        HisStockOut upd = new HisStockOut();
        upd.setId(id);
        upd.setStatus(1);
        upd.setConfirmBy(currentUserName());
        upd.setConfirmTime(LocalDateTime.now());
        upd.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        stockOutMapper.updateById(upd);
        log.info("确认出库: id={}, outNo={}, items={}, total={}", id, main.getOutNo(), items.size(), upd.getTotalAmount());
    }

    /** 作废出库单: 仅草稿态可作废 */
    @Transactional(rollbackFor = Exception.class)
    public void voidStockOut(Long id) {
        HisStockOut main = stockOutMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "出库单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 0) {
            throw new BizException("仅草稿态出库单可作废: " + main.getOutNo());
        }
        HisStockOut upd = new HisStockOut();
        upd.setId(id);
        upd.setStatus(2);
        stockOutMapper.updateById(upd);
        log.info("作废出库单: id={}, outNo={}", id, main.getOutNo());
    }

    /* ================= 核心扣减/回库(供药房调用) ================= */

    /**
     * FIFO 扣减库存: 按有效期升序逐批扣减(MySQL ASC 排序 NULL 在前, 无效期批次优先出),
     * 每批乐观锁 UPDATE, 返回各批次扣减明细供出库明细/发药追溯落库; 不足抛"库存不足"。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<StockDeductResult> deductStock(Long orgId, Long drugCatalogId, BigDecimal qty) {
        if (orgId == null || drugCatalogId == null) {
            throw new BizException(400, "扣减库存缺少机构或药品参数");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "扣减数量必须大于0");
        }
        return deductFifo(orgId, drugCatalogId, qty);
    }

    /** 退药回库: 指定批次原子加量; 批次行不存在时按同药品库存信息新建该批次 */
    @Transactional(rollbackFor = Exception.class)
    public void returnStock(Long orgId, Long drugCatalogId, String batchNo, BigDecimal qty) {
        if (orgId == null || drugCatalogId == null) {
            throw new BizException(400, "回库缺少机构或药品参数");
        }
        if (!StringUtils.hasText(batchNo)) {
            throw new BizException(400, "回库批次号不能为空");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "回库数量必须大于0");
        }
        int affected = stockMapper.addQty(orgId, drugCatalogId, batchNo.trim(), qty);
        if (affected > 0) {
            log.info("退药回库: orgId={}, drugCatalogId={}, batchNo={}, qty={}", orgId, drugCatalogId, batchNo, qty);
            return;
        }
        // 批次行不存在: 取同药品任一库存行复制药品信息新建该批次(退药批次此前必有出库记录, 同药品必有库存行)
        HisDrugStock template = stockMapper.selectOne(new LambdaQueryWrapper<HisDrugStock>()
                .eq(HisDrugStock::getOrgId, orgId)
                .eq(HisDrugStock::getDrugCatalogId, drugCatalogId)
                .last("LIMIT 1"));
        if (template == null) {
            throw new BizException(400, "该药品无库存记录, 无法回库: drugCatalogId=" + drugCatalogId);
        }
        HisDrugStock stock = new HisDrugStock();
        stock.setOrgId(orgId);
        stock.setDrugCatalogId(drugCatalogId);
        stock.setDrugCode(template.getDrugCode());
        stock.setDrugName(template.getDrugName());
        stock.setSpec(template.getSpec());
        stock.setDosform(template.getDosform());
        stock.setBatchNo(batchNo.trim());
        stock.setManufacturer(template.getManufacturer());
        stock.setQty(qty);
        stock.setCostPrice(template.getCostPrice());
        stock.setRetailPrice(template.getRetailPrice());
        stock.setStatus(1);
        try {
            stockMapper.insert(stock);
        } catch (DuplicateKeyException e) {
            // 唯一键被逻辑删除行占用: 显式报错而非静默丢库存
            throw new BizException("回库失败, 批次行已删除: " + template.getDrugName() + " 批号" + batchNo);
        }
        log.info("退药回库(新建批次行): orgId={}, drugCatalogId={}, batchNo={}, qty={}", orgId, drugCatalogId, batchNo, qty);
    }

    /* ================= 出入库流水 ================= */

    /** 出入库流水(已确认单据明细 UNION, 确认时间倒序): {itemId,billId,billNo,flowType,flowTypeName,billType,drug*,qty,prices,amount,opTime} */
    public IPage<Map<String, Object>> stockFlow(Long orgId, Long drugCatalogId, String startDate, String endDate, long page, long size) {
        return stockInItemMapper.selectFlowPage(new Page<>(page, size), orgId, drugCatalogId,
                blankToNull(startDate), blankToNull(endDate));
    }

    /* ================= 内部实现 ================= */

    /** 按批次 upsert 库存行: 存在原子加量; 不存在新建, 并发撞 uk_tenant_org_drug_batch 时转原子加量 */
    private void upsertStock(Long orgId, HisStockInItem item) {
        HisDrugStock exist = stockMapper.selectOne(new LambdaQueryWrapper<HisDrugStock>()
                .eq(HisDrugStock::getOrgId, orgId)
                .eq(HisDrugStock::getDrugCatalogId, item.getDrugCatalogId())
                .eq(HisDrugStock::getBatchNo, item.getBatchNo())
                .last("LIMIT 1"));
        if (exist != null) {
            int affected = stockMapper.addQty(orgId, item.getDrugCatalogId(), item.getBatchNo(), item.getQty());
            if (affected == 0) {
                throw new BizException("库存批次加量失败: " + item.getDrugName() + " 批号" + item.getBatchNo());
            }
            return;
        }
        HisDrugStock stock = new HisDrugStock();
        stock.setOrgId(orgId);
        stock.setDrugCatalogId(item.getDrugCatalogId());
        stock.setDrugCode(item.getDrugCode());
        stock.setDrugName(item.getDrugName());
        stock.setSpec(item.getSpec());
        stock.setBatchNo(item.getBatchNo());
        stock.setManufacturer(item.getManufacturer());
        stock.setQty(item.getQty());
        stock.setCostPrice(item.getCostPrice());
        stock.setRetailPrice(item.getRetailPrice());
        stock.setProdDate(item.getProdDate());
        stock.setExpDate(item.getExpDate());
        stock.setStatus(1);
        try {
            stockMapper.insert(stock);
        } catch (DuplicateKeyException e) {
            // 并发确认撞唯一键: 转原子加量
            int affected = stockMapper.addQty(orgId, item.getDrugCatalogId(), item.getBatchNo(), item.getQty());
            if (affected == 0) {
                throw new BizException("库存批次入库失败: " + item.getDrugName() + " 批号" + item.getBatchNo());
            }
        }
    }

    /**
     * FIFO 扣减核心: 查可用批次(status=1且qty>0, exp_date ASC)逐批乐观锁扣减;
     * affected=0(批次被并发抢先扣减)时重查重试, 上限3轮防死循环。
     */
    private List<StockDeductResult> deductFifo(Long orgId, Long drugCatalogId, BigDecimal qty) {
        List<StockDeductResult> results = new ArrayList<>();
        BigDecimal need = qty;
        for (int attempt = 0; need.compareTo(BigDecimal.ZERO) > 0; attempt++) {
            if (attempt >= 3) {
                throw new BizException("库存不足: " + drugNameOf(orgId, drugCatalogId) + ", 需 " + qty + " (并发扣减冲突)");
            }
            List<HisDrugStock> batches = stockMapper.selectList(new LambdaQueryWrapper<HisDrugStock>()
                    .eq(HisDrugStock::getOrgId, orgId)
                    .eq(HisDrugStock::getDrugCatalogId, drugCatalogId)
                    .eq(HisDrugStock::getStatus, 1)
                    .gt(HisDrugStock::getQty, BigDecimal.ZERO)
                    .orderByAsc(HisDrugStock::getExpDate)
                    .orderByAsc(HisDrugStock::getId));
            if (CollectionUtils.isEmpty(batches)) {
                throw new BizException("库存不足: " + drugNameOf(orgId, drugCatalogId));
            }
            boolean progressed = false;
            for (HisDrugStock b : batches) {
                if (need.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal take = b.getQty().min(need);
                if (take.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                int affected = stockMapper.deductQty(b.getId(), take);
                if (affected > 0) {
                    need = need.subtract(take);
                    progressed = true;
                    results.add(new StockDeductResult(b.getId(), b.getBatchNo(), take, b.getCostPrice(), b.getRetailPrice()));
                }
            }
            if (!progressed) {
                throw new BizException("库存不足: " + drugNameOf(orgId, drugCatalogId) + ", 需 " + qty);
            }
        }
        return results;
    }

    /** 确认出库-指定批次明细: 乐观锁直接扣减并回填小计金额 */
    private void confirmDeductBatch(HisStockOutItem item) {
        int affected = stockMapper.deductQty(item.getDrugStockId(), item.getQty());
        if (affected == 0) {
            throw new BizException("库存不足: " + item.getDrugName() + " 批号" + item.getBatchNo()
                    + ", 需 " + item.getQty());
        }
        if (item.getAmount() == null) {
            item.setAmount(calcOutAmount(item.getQty(), item.getRetailPrice(), item.getCostPrice()));
            stockOutItemMapper.updateById(item);
        }
    }

    /** 组装入库明细(校验必填/数量, 解析日期, 缺省小计=数量*进价) */
    private HisStockInItem buildInItem(Long stockInId, StockInItemReq it) {
        if (it.getDrugCatalogId() == null) {
            throw new BizException(400, "入库明细缺少药品目录ID(drugCatalogId)");
        }
        if (!StringUtils.hasText(it.getDrugCode())) {
            throw new BizException(400, "入库明细缺少药品编码(drugCode)");
        }
        if (!StringUtils.hasText(it.getDrugName())) {
            throw new BizException(400, "入库明细缺少药品名称(drugName)");
        }
        if (!StringUtils.hasText(it.getBatchNo())) {
            throw new BizException(400, "入库明细缺少批次号(batchNo): " + it.getDrugName());
        }
        if (it.getQty() == null || it.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "入库数量必须大于0: " + it.getDrugName());
        }
        HisStockInItem item = new HisStockInItem();
        item.setStockInId(stockInId);
        item.setDrugCatalogId(it.getDrugCatalogId());
        item.setDrugCode(it.getDrugCode());
        item.setDrugName(it.getDrugName());
        item.setSpec(it.getSpec());
        item.setBatchNo(it.getBatchNo().trim());
        item.setManufacturer(it.getManufacturer());
        item.setQty(it.getQty());
        item.setCostPrice(it.getCostPrice());
        item.setRetailPrice(it.getRetailPrice());
        item.setProdDate(parseDate(it.getProdDate(), "生产日期"));
        item.setExpDate(parseDate(it.getExpDate(), "有效期"));
        BigDecimal amount = it.getAmount();
        if (amount == null && it.getCostPrice() != null) {
            amount = it.getQty().multiply(it.getCostPrice()).setScale(2, RoundingMode.HALF_UP);
        }
        item.setAmount(amount);
        return item;
    }

    /** 组装出库明细: 指定批次则校验归属并带出药品/价格; 否则要求药品三要素, 批次留待确认FIFO回填 */
    private HisStockOutItem buildOutItem(HisStockOut main, StockOutItemReq it) {
        if (it.getQty() == null || it.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "出库数量必须大于0");
        }
        HisStockOutItem item = new HisStockOutItem();
        item.setStockOutId(main.getId());
        item.setQty(it.getQty());
        if (it.getDrugStockId() != null) {
            HisDrugStock stock = stockMapper.selectById(it.getDrugStockId());
            if (stock == null || !Objects.equals(stock.getOrgId(), main.getOrgId())) {
                throw new BizException(400, "库存批次不存在或不属于本机构: drugStockId=" + it.getDrugStockId());
            }
            item.setDrugStockId(stock.getId());
            item.setDrugCatalogId(stock.getDrugCatalogId());
            item.setDrugCode(stock.getDrugCode());
            item.setDrugName(stock.getDrugName());
            item.setSpec(stock.getSpec());
            item.setBatchNo(stock.getBatchNo());
            item.setCostPrice(stock.getCostPrice());
            item.setRetailPrice(stock.getRetailPrice());
            item.setAmount(calcOutAmount(item.getQty(), stock.getRetailPrice(), stock.getCostPrice()));
            return item;
        }
        if (it.getDrugCatalogId() == null || !StringUtils.hasText(it.getDrugCode()) || !StringUtils.hasText(it.getDrugName())) {
            throw new BizException(400, "出库明细需指定库存批次(drugStockId)或药品三要素(drugCatalogId+drugCode+drugName)");
        }
        item.setDrugCatalogId(it.getDrugCatalogId());
        item.setDrugCode(it.getDrugCode());
        item.setDrugName(it.getDrugName());
        item.setSpec(it.getSpec());
        return item;
    }

    /** 单号生成: 前缀+yyyyMMdd+4位序号(如 RK202609250001); synchronized 唯一, 跨日重置时DB回读当日最大序号兜底重启 */
    private synchronized String generateNo(String prefix) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(prefix, today);
        }
        seqNo++;
        String no = prefix + today + String.format("%04d", seqNo);
        // 租户内唯一键兜底: 若序号已被占用(极端并发/脏数据)则继续自增直至可用
        while (noExists(prefix, no)) {
            seqNo++;
            no = prefix + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有单号最大序号(重启后防撞号) */
    private int maxSeqFromDb(String prefix, String today) {
        String like = prefix + today + "%";
        String maxNo;
        if ("RK".equals(prefix)) {
            HisStockIn one = stockInMapper.selectOne(new LambdaQueryWrapper<HisStockIn>()
                    .likeRight(HisStockIn::getInNo, like)
                    .orderByDesc(HisStockIn::getInNo)
                    .last("LIMIT 1"));
            maxNo = one == null ? null : one.getInNo();
        } else {
            HisStockOut one = stockOutMapper.selectOne(new LambdaQueryWrapper<HisStockOut>()
                    .likeRight(HisStockOut::getOutNo, like)
                    .orderByDesc(HisStockOut::getOutNo)
                    .last("LIMIT 1"));
            maxNo = one == null ? null : one.getOutNo();
        }
        if (maxNo == null || maxNo.length() < prefix.length() + 12) {
            return 0;
        }
        try {
            return Integer.parseInt(maxNo.substring(prefix.length() + 8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 单号是否已存在(RK查入库单, 其余查出库单) */
    private boolean noExists(String prefix, String no) {
        if ("RK".equals(prefix)) {
            return stockInMapper.selectCount(new LambdaQueryWrapper<HisStockIn>().eq(HisStockIn::getInNo, no)) > 0;
        }
        return stockOutMapper.selectCount(new LambdaQueryWrapper<HisStockOut>().eq(HisStockOut::getOutNo, no)) > 0;
    }

    /** 入库单明细(按ID升序) */
    private List<HisStockInItem> listInItems(Long stockInId) {
        return stockInItemMapper.selectList(new LambdaQueryWrapper<HisStockInItem>()
                .eq(HisStockInItem::getStockInId, stockInId).orderByAsc(HisStockInItem::getId));
    }

    /** 出库小计金额: 优先 数量*零售价, 零售价空则 数量*进价, 均空为null */
    private BigDecimal calcOutAmount(BigDecimal qty, BigDecimal retailPrice, BigDecimal costPrice) {
        if (retailPrice != null) {
            return qty.multiply(retailPrice).setScale(2, RoundingMode.HALF_UP);
        }
        if (costPrice != null) {
            return qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    /** 解析 yyyy-MM-dd 日期字符串(空返回null, 格式错误抛400) */
    private LocalDate parseDate(String date, String field) {
        if (!StringUtils.hasText(date)) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(400, field + "格式错误, 应为 yyyy-MM-dd: " + date);
        }
    }

    /** 解析日期范围: [start当日0点, end次日0点), 均可选 */
    private LocalDateTime[] parseDateRange(String startDate, String endDate) {
        LocalDateTime start = null;
        LocalDateTime end = null;
        try {
            if (StringUtils.hasText(startDate)) {
                start = LocalDate.parse(startDate.trim()).atStartOfDay();
            }
            if (StringUtils.hasText(endDate)) {
                end = LocalDate.parse(endDate.trim()).plusDays(1).atStartOfDay();
            }
        } catch (DateTimeParseException e) {
            throw new BizException(400, "日期格式错误, 应为 yyyy-MM-dd");
        }
        return new LocalDateTime[]{start, end};
    }

    /** 药品名称(库存行取, 用于异常提示; 无库存行时退化为目录ID表述) */
    private String drugNameOf(Long orgId, Long drugCatalogId) {
        HisDrugStock one = stockMapper.selectOne(new LambdaQueryWrapper<HisDrugStock>()
                .eq(HisDrugStock::getOrgId, orgId)
                .eq(HisDrugStock::getDrugCatalogId, drugCatalogId)
                .last("LIMIT 1"));
        return one == null ? ("药品#" + drugCatalogId) : one.getDrugName();
    }

    /** 当前操作人姓名(优先真名) */
    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}

