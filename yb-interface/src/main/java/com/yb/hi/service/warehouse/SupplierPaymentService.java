package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.SupplierPaymentItemReq;
import com.yb.hi.dto.warehouse.SupplierPaymentReq;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisSupplierPayment;
import com.yb.hi.entity.warehouse.HisSupplierPaymentItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisStockInMapper;
import com.yb.hi.mapper.warehouse.HisSupplierPaymentItemMapper;
import com.yb.hi.mapper.warehouse.HisSupplierPaymentMapper;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商付款/应付账款服务(批次C): 按供应商拉取未结入库单, 三种付款方式(全额/输入总额/部分分摊)
 * 计算分摊并校验不超应付, 确认付款回写来源入库单 paid_amount/paid_status; 并提供应付账款账龄分析。
 * 单号 FK+yyyyMMdd+4位, 租户内唯一; tenant_id 由租户插件自动注入/过滤, 本类不显式处理租户。
 */
@Slf4j
@Service
public class SupplierPaymentService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final HisSupplierPaymentMapper paymentMapper;
    private final HisSupplierPaymentItemMapper paymentItemMapper;
    private final HisStockInMapper stockInMapper;

    private String seqDate;
    private int seqNo = 0;

    public SupplierPaymentService(HisSupplierPaymentMapper paymentMapper,
                                  HisSupplierPaymentItemMapper paymentItemMapper,
                                  HisStockInMapper stockInMapper) {
        this.paymentMapper = paymentMapper;
        this.paymentItemMapper = paymentItemMapper;
        this.stockInMapper = stockInMapper;
    }

    /* ================= 未结入库单查询 ================= */

    /** 供应商未结(未付清)入库单分页: 已确认(status=1)、非红字、已付额<应付额 */
    public IPage<HisStockIn> unpaidPage(Long orgId, Long supplierId, long page, long size) {
        if (supplierId == null) {
            throw new BizException(400, "查询未结入库单需指定供应商");
        }
        List<HisStockIn> all = unpaidList(orgId, supplierId);
        Page<HisStockIn> result = new Page<>(page, size);
        int from = (int) Math.min((page - 1) * size, all.size());
        int to = (int) Math.min(from + size, all.size());
        result.setRecords(new ArrayList<>(all.subList(from, to)));
        result.setTotal(all.size());
        return result;
    }

    /** 供应商未结入库单全量列表(供付款建单选择) */
    public List<HisStockIn> unpaidList(Long orgId, Long supplierId) {
        LambdaQueryWrapper<HisStockIn> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockIn::getOrgId, orgId)
                .eq(supplierId != null, HisStockIn::getSupplierId, supplierId)
                .eq(HisStockIn::getStatus, 1)
                .isNull(HisStockIn::getRedOfId)
                .orderByAsc(HisStockIn::getId);
        List<HisStockIn> list = stockInMapper.selectList(w);
        List<HisStockIn> unpaid = new ArrayList<>();
        for (HisStockIn in : list) {
            if (unpaidAmount(in).compareTo(BigDecimal.ZERO) > 0) {
                unpaid.add(in);
            }
        }
        return unpaid;
    }

    /* ================= 付款单 CRUD ================= */

    public IPage<HisSupplierPayment> page(Long orgId, Long supplierId, Integer status, long page, long size) {
        LambdaQueryWrapper<HisSupplierPayment> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisSupplierPayment::getOrgId, orgId)
                .eq(supplierId != null, HisSupplierPayment::getSupplierId, supplierId)
                .eq(status != null, HisSupplierPayment::getStatus, status)
                .orderByDesc(HisSupplierPayment::getId);
        return paymentMapper.selectPage(new Page<>(page, size), w);
    }

    public Map<String, Object> detail(Long id) {
        HisSupplierPayment main = paymentMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "付款单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", paymentItemMapper.selectList(new LambdaQueryWrapper<HisSupplierPaymentItem>()
                .eq(HisSupplierPaymentItem::getPaymentId, id).orderByAsc(HisSupplierPaymentItem::getId)));
        return out;
    }

    /**
     * 创建付款单(草稿): 按付款方式计算各入库单分摊额并校验不超应付, 落主单+明细快照。
     * 方式1全额=各单选单付清未结额; 方式2输入总额=按 amount 顺序分摊(超出应付报错); 方式3部分分摊=逐单手工额。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSupplierPayment createPayment(SupplierPaymentReq req) {
        if (req.getSupplierId() == null) {
            throw new BizException(400, "付款单需指定供应商");
        }
        if (CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "付款单需选择至少一张入库单");
        }
        int method = req.getPayMethod() == null ? 1 : req.getPayMethod();
        if (method < 1 || method > 3) {
            throw new BizException(400, "付款方式非法(1全额 2输入总额 3部分分摊)");
        }
        Long orgId = req.getOrgId();

        // 载入并校验所选入库单, 保序去重
        List<HisStockIn> ins = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (SupplierPaymentItemReq ir : req.getItems()) {
            if (ir.getStockInId() == null) {
                throw new BizException(400, "结算明细缺少入库单ID");
            }
            if (ids.contains(ir.getStockInId())) {
                continue;
            }
            HisStockIn in = stockInMapper.selectById(ir.getStockInId());
            if (in == null) {
                throw new BizException(400, "入库单不存在: " + ir.getStockInId());
            }
            if (in.getStatus() == null || in.getStatus() != 1) {
                throw new BizException("仅已确认入库单可付款: " + in.getInNo());
            }
            if (in.getRedOfId() != null) {
                throw new BizException("红字冲账单不参与付款: " + in.getInNo());
            }
            if (orgId != null && !orgId.equals(in.getOrgId())) {
                throw new BizException("入库单不属于本机构: " + in.getInNo());
            }
            if (!req.getSupplierId().equals(in.getSupplierId())) {
                throw new BizException("入库单供应商与付款单不一致: " + in.getInNo());
            }
            ins.add(in);
            ids.add(in.getId());
        }

        // 按付款方式计算各行分摊额(方式1/2服务端算, 方式3取请求侧手工额并校验不超未结额)
        BigDecimal[] allocs = method == 3 ? manualAllocations(req, ins) : computeAllocations(method, req.getAmount(), ins);
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal a : allocs) {
            total = total.add(a);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("付款总额须大于0");
        }

        HisSupplierPayment main = new HisSupplierPayment();
        main.setOrgId(ins.get(0).getOrgId());
        main.setSupplierId(req.getSupplierId());
        main.setPayNo(generatePayNo());
        main.setPayDate(parseDate(req.getPayDate()));
        main.setPayMethod(method);
        main.setAmount(total);
        main.setPayChannel(StringUtils.hasText(req.getPayChannel()) ? req.getPayChannel().trim() : null);
        main.setStatus(0);
        main.setRemark(StringUtils.hasText(req.getRemark()) ? req.getRemark().trim() : null);
        paymentMapper.insert(main);

        for (int i = 0; i < ins.size(); i++) {
            HisStockIn in = ins.get(i);
            BigDecimal paidBefore = nvl(in.getPaidAmount());
            HisSupplierPaymentItem item = new HisSupplierPaymentItem();
            item.setPaymentId(main.getId());
            item.setStockInId(in.getId());
            item.setStockInNo(in.getInNo());
            item.setInAmount(nvl(in.getTotalAmount()).setScale(2, RoundingMode.HALF_UP));
            item.setPaidBefore(paidBefore.setScale(2, RoundingMode.HALF_UP));
            item.setPaidAmount(allocs[i].setScale(2, RoundingMode.HALF_UP));
            paymentItemMapper.insert(item);
        }
        log.info("付款单草稿: payNo={}, supplierId={}, method={}, amount={}", main.getPayNo(), main.getSupplierId(), method, total);
        return paymentMapper.selectById(main.getId());
    }

    /** 确认付款: 逐明细回写来源入库单 paid_amount/paid_status, 二次校验不超应付 */
    @Transactional(rollbackFor = Exception.class)
    public HisSupplierPayment confirmPayment(Long id) {
        HisSupplierPayment main = paymentMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "付款单不存在");
        }
        if (main.getStatus() != null && main.getStatus() == 1) {
            throw new BizException("付款单已确认, 不可重复确认");
        }
        List<HisSupplierPaymentItem> items = paymentItemMapper.selectList(new LambdaQueryWrapper<HisSupplierPaymentItem>()
                .eq(HisSupplierPaymentItem::getPaymentId, id).orderByAsc(HisSupplierPaymentItem::getId));
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException("付款单无结算明细, 不可确认");
        }
        for (HisSupplierPaymentItem item : items) {
            HisStockIn in = stockInMapper.selectById(item.getStockInId());
            if (in == null) {
                throw new BizException("来源入库单不存在: " + item.getStockInNo());
            }
            BigDecimal payable = nvl(in.getTotalAmount());
            BigDecimal paidNow = nvl(in.getPaidAmount());
            BigDecimal alloc = nvl(item.getPaidAmount());
            BigDecimal after = paidNow.add(alloc);
            if (after.compareTo(payable) > 0) {
                throw new BizException("确认后付款超过应付(入库单可能已被其他付款单结算): " + in.getInNo());
            }
            HisStockIn upd = new HisStockIn();
            upd.setId(in.getId());
            upd.setPaidAmount(after.setScale(2, RoundingMode.HALF_UP));
            upd.setPaidStatus(after.compareTo(payable) >= 0 ? 2 : 1);
            stockInMapper.updateById(upd);
        }
        main.setStatus(1);
        main.setConfirmBy(currentUserName());
        main.setConfirmTime(LocalDateTime.now());
        paymentMapper.updateById(main);
        log.info("付款单确认: payNo={}, amount={}", main.getPayNo(), main.getAmount());
        return paymentMapper.selectById(id);
    }

    /** 作废草稿付款单(仅未确认可作废) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        HisSupplierPayment main = paymentMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "付款单不存在");
        }
        if (main.getStatus() != null && main.getStatus() == 1) {
            throw new BizException("已确认付款单不可作废");
        }
        paymentMapper.deleteById(id);
        paymentItemMapper.delete(new LambdaQueryWrapper<HisSupplierPaymentItem>()
                .eq(HisSupplierPaymentItem::getPaymentId, id));
    }

    /* ================= 应付账款 + 账龄分析 ================= */

    /**
     * 应付账款查询: 未结入库单逐单应付额 + 汇总 + 账龄分档(按入库确认日期距今: 0-30/31-60/61-90/90+)。
     */
    public Map<String, Object> payable(Long orgId, Long supplierId) {
        List<HisStockIn> unpaid = unpaidList(orgId, supplierId);
        LocalDate today = LocalDate.now();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal b1 = BigDecimal.ZERO, b2 = BigDecimal.ZERO, b3 = BigDecimal.ZERO, b4 = BigDecimal.ZERO;
        List<Map<String, Object>> bills = new ArrayList<>();
        for (HisStockIn in : unpaid) {
            BigDecimal up = unpaidAmount(in).setScale(2, RoundingMode.HALF_UP);
            total = total.add(up);
            LocalDate base = in.getConfirmTime() != null ? in.getConfirmTime().toLocalDate()
                    : (in.getCreateTime() != null ? in.getCreateTime().toLocalDate() : today);
            long days = ChronoUnit.DAYS.between(base, today);
            if (days <= 30) {
                b1 = b1.add(up);
            } else if (days <= 60) {
                b2 = b2.add(up);
            } else if (days <= 90) {
                b3 = b3.add(up);
            } else {
                b4 = b4.add(up);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockInId", in.getId());
            row.put("inNo", in.getInNo());
            row.put("supplierId", in.getSupplierId());
            row.put("supplier", in.getSupplier());
            row.put("invoiceNo", in.getInvoiceNo());
            row.put("totalAmount", nvl(in.getTotalAmount()));
            row.put("paidAmount", nvl(in.getPaidAmount()));
            row.put("unpaidAmount", up);
            row.put("baseDate", base.toString());
            row.put("agingDays", days);
            bills.add(row);
        }
        Map<String, Object> aging = new LinkedHashMap<>();
        aging.put("d0_30", b1.setScale(2, RoundingMode.HALF_UP));
        aging.put("d31_60", b2.setScale(2, RoundingMode.HALF_UP));
        aging.put("d61_90", b3.setScale(2, RoundingMode.HALF_UP));
        aging.put("d90_plus", b4.setScale(2, RoundingMode.HALF_UP));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplierId", supplierId);
        out.put("billCount", unpaid.size());
        out.put("totalPayable", total.setScale(2, RoundingMode.HALF_UP));
        out.put("aging", aging);
        out.put("bills", bills);
        return out;
    }

    /* ================= 内部分摊计算 ================= */

    /**
     * 按付款方式计算各行分摊额(与 ins 同序), 校验不超各单未结额。
     */
    private BigDecimal[] computeAllocations(int method, BigDecimal inputAmount, List<HisStockIn> ins) {
        BigDecimal[] allocs = new BigDecimal[ins.size()];
        if (method == 1) {
            for (int i = 0; i < ins.size(); i++) {
                allocs[i] = unpaidAmount(ins.get(i));
            }
            return allocs;
        }
        if (method == 2) {
            BigDecimal left = nvl(inputAmount).setScale(2, RoundingMode.HALF_UP);
            if (left.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("输入总额方式须填写大于0的付款额");
            }
            BigDecimal totalPayable = BigDecimal.ZERO;
            for (HisStockIn in : ins) {
                totalPayable = totalPayable.add(unpaidAmount(in));
            }
            if (left.compareTo(totalPayable) > 0) {
                throw new BizException("付款额超过所选入库单应付总额: 应付 " + totalPayable.setScale(2, RoundingMode.HALF_UP));
            }
            for (int i = 0; i < ins.size(); i++) {
                BigDecimal up = unpaidAmount(ins.get(i));
                BigDecimal take = left.min(up);
                allocs[i] = take;
                left = left.subtract(take);
            }
            return allocs;
        }
        // method == 3: 逐单手工分摊(需回传 items[].paidAmount, 与 ins 同序去重后重取)
        return allocs;
    }

    /**
     * 方式3(部分分摊): 按请求逐单分摊额校验不超未结额。单独处理以拿到请求侧的手工额。
     */
    private BigDecimal[] manualAllocations(SupplierPaymentReq req, List<HisStockIn> ins) {
        BigDecimal[] allocs = new BigDecimal[ins.size()];
        Map<Long, BigDecimal> want = new LinkedHashMap<>();
        for (SupplierPaymentItemReq ir : req.getItems()) {
            if (ir.getStockInId() == null) {
                continue;
            }
            BigDecimal amt = nvl(ir.getPaidAmount()).setScale(2, RoundingMode.HALF_UP);
            want.merge(ir.getStockInId(), amt, BigDecimal::add);
        }
        for (int i = 0; i < ins.size(); i++) {
            HisStockIn in = ins.get(i);
            BigDecimal amt = want.getOrDefault(in.getId(), BigDecimal.ZERO);
            if (amt.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("分摊额不可为负: " + in.getInNo());
            }
            if (amt.compareTo(unpaidAmount(in)) > 0) {
                throw new BizException("分摊额超过该入库单未结额: " + in.getInNo());
            }
            allocs[i] = amt;
        }
        return allocs;
    }

    /* ================= 工具方法 ================= */

    private BigDecimal unpaidAmount(HisStockIn in) {
        return nvl(in.getTotalAmount()).subtract(nvl(in.getPaidAmount()));
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(s.trim(), DATE_ISO);
        } catch (Exception e) {
            throw new BizException(400, "付款日期格式应为 yyyy-MM-dd");
        }
    }

    private synchronized String generatePayNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "FK" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "FK" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    private int maxSeqFromDb(String today) {
        HisSupplierPayment one = paymentMapper.selectOne(new LambdaQueryWrapper<HisSupplierPayment>()
                .likeRight(HisSupplierPayment::getPayNo, "FK" + today)
                .orderByDesc(HisSupplierPayment::getPayNo)
                .last("LIMIT 1"));
        if (one == null || one.getPayNo() == null || one.getPayNo().length() < 12) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getPayNo().substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean noExists(String no) {
        return paymentMapper.selectCount(new LambdaQueryWrapper<HisSupplierPayment>()
                .eq(HisSupplierPayment::getPayNo, no)) > 0;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }
}
