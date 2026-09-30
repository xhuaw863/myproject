package com.yb.hi.service.cashier;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.cashier.HisInvoice;
import com.yb.hi.entity.cashier.HisInvoicePool;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.cashier.HisChargeBillMapper;
import com.yb.hi.mapper.cashier.HisInvoiceMapper;
import com.yb.hi.mapper.cashier.HisInvoicePoolMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发票服务(收费开票闭环): 号段管理(新建/编辑/启用) -> 取号开票 -> 作废/红冲 -> 记录查询导出。
 * 说明:
 * 1) 号段同机构全局唯一编码且区间不交叉, 同机构同发票类型同时仅一个使用中(status=1)号段;
 * 2) 取号 current_no 原子自增(synchronized 单实例串行 + UPDATE current_no=current_no+1 行锁兜底多实例,
 *    并发阻塞排队不跳号), 用完自动转已用完(status=2);
 * 3) {@link #createInvoice} 刻意不加 @Transactional: 由调用方(CashierService 收费事务)携带事务,
 *    取号失败(未配置/已用完)由调用方捕获记日志, 不阻塞收费主流程;
 *    若标 @Transactional(REQUIRED), 异常穿过代理边界会把外层事务标记 rollback-only 导致收费整体回滚。
 */
@Slf4j
@Service
public class InvoiceService {

    private final HisInvoicePoolMapper poolMapper;
    private final HisInvoiceMapper invoiceMapper;
    private final HisChargeBillMapper billMapper;
    private final OrgAccessGuard orgAccessGuard;

    public InvoiceService(HisInvoicePoolMapper poolMapper, HisInvoiceMapper invoiceMapper,
                          HisChargeBillMapper billMapper, OrgAccessGuard orgAccessGuard) {
        this.poolMapper = poolMapper;
        this.invoiceMapper = invoiceMapper;
        this.billMapper = billMapper;
        this.orgAccessGuard = orgAccessGuard;
    }

    // ==================== 号段管理 ====================

    /** 号段分页(按分配时间降序); 记录携带派生统计(totalQty/usedQty/usedPercent), 前端只展示不计算 */
    public IPage<HisInvoicePool> poolPage(Long orgId, long page, long size) {
        LambdaQueryWrapper<HisInvoicePool> w = Wrappers.<HisInvoicePool>lambdaQuery()
                .eq(orgId != null, HisInvoicePool::getOrgId, orgId)
                .orderByDesc(HisInvoicePool::getAllocTime)
                .orderByDesc(HisInvoicePool::getId);
        IPage<HisInvoicePool> result = poolMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
        if (result != null && result.getRecords() != null) {
            for (HisInvoicePool p : result.getRecords()) {
                fillPoolStats(p);
            }
        }
        return result;
    }

    /**
     * 新增/编辑号段。校验: poolCode 同机构唯一; startNo < endNo;
     * 号段区间不与同机构已有号段交叉; 编辑不得把已分配号移出区间。
     * 新增时 currentNo = startNo - 1, status = 0(未启用)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInvoicePool savePool(HisInvoicePool pool) {
        if (pool == null) {
            throw new BizException(400, "号段信息不能为空");
        }
        if (pool.getOrgId() == null) {
            throw new BizException(400, "机构ID不能为空");
        }
        if (!StringUtils.hasText(pool.getPoolCode())) {
            throw new BizException(400, "号段编码不能为空");
        }
        if (!StringUtils.hasText(pool.getInvoiceType())) {
            throw new BizException(400, "发票类型不能为空(NORMAL/ELECTRONIC)");
        }
        if (pool.getStartNo() == null || pool.getEndNo() == null) {
            throw new BizException(400, "起始号/结束号不能为空");
        }
        if (pool.getStartNo() >= pool.getEndNo()) {
            throw new BizException(400, "起始号必须小于结束号");
        }
        pool.setPoolCode(pool.getPoolCode().trim());
        // poolCode 同机构唯一(编辑排除自身)
        Long dup = poolMapper.selectCount(Wrappers.<HisInvoicePool>lambdaQuery()
                .eq(HisInvoicePool::getOrgId, pool.getOrgId())
                .eq(HisInvoicePool::getPoolCode, pool.getPoolCode())
                .ne(pool.getId() != null, HisInvoicePool::getId, pool.getId()));
        if (dup != null && dup > 0) {
            throw new BizException("号段编码已存在: " + pool.getPoolCode());
        }
        // 区间不与同机构已有号段交叉(发票号同机构唯一号空间, 不区分纸质/电子)
        List<HisInvoicePool> siblings = poolMapper.selectList(Wrappers.<HisInvoicePool>lambdaQuery()
                .eq(HisInvoicePool::getOrgId, pool.getOrgId())
                .ne(pool.getId() != null, HisInvoicePool::getId, pool.getId()));
        for (HisInvoicePool s : siblings) {
            if (s.getStartNo() == null || s.getEndNo() == null) {
                continue;
            }
            boolean noOverlap = pool.getEndNo() < s.getStartNo() || pool.getStartNo() > s.getEndNo();
            if (!noOverlap) {
                throw new BizException("号段区间与已有号段重叠: " + s.getPoolCode()
                        + "[" + s.getStartNo() + "-" + s.getEndNo() + "]");
            }
        }
        if (pool.getId() == null) {
            pool.setCurrentNo(pool.getStartNo() - 1);
            pool.setStatus(0);
            poolMapper.insert(pool);
            log.info("新增发票号段: orgId={}, poolCode={}, [{}-{}], type={}",
                    pool.getOrgId(), pool.getPoolCode(), pool.getStartNo(), pool.getEndNo(), pool.getInvoiceType());
            return pool;
        }
        // 编辑: 归属机构不可变更; 已分配号(startNo..currentNo)必须仍落在新区间内
        HisInvoicePool db = poolMapper.selectById(pool.getId());
        if (db == null) {
            throw new BizException(400, "号段不存在");
        }
        if (!pool.getOrgId().equals(db.getOrgId())) {
            throw new BizException(400, "号段归属机构不允许变更");
        }
        long used = db.getCurrentNo() == null ? db.getStartNo() - 1 : db.getCurrentNo();
        if (pool.getStartNo() > used + 1) {
            throw new BizException("起始号不能大于当前已用号(已用至 " + used + ")");
        }
        if (pool.getEndNo() <= used) {
            throw new BizException("结束号必须大于当前已用号(已用至 " + used + ")");
        }
        // 已用进度与状态不随编辑覆盖
        pool.setCurrentNo(db.getCurrentNo());
        pool.setStatus(db.getStatus());
        poolMapper.updateById(pool);
        log.info("编辑发票号段: id={}, poolCode={}, [{}-{}]", pool.getId(), pool.getPoolCode(), pool.getStartNo(), pool.getEndNo());
        return pool;
    }

    /**
     * 启用号段: 同机构同发票类型唯一使用中——旧使用中号段按余量转已用完(用尽)或未启用(停用),
     * 目标号段置 status=1 并记录分配人/时间。已用完号段不可再启用。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInvoicePool activatePool(Long poolId) {
        if (poolId == null) {
            throw new BizException(400, "号段ID不能为空");
        }
        HisInvoicePool pool = poolMapper.selectById(poolId);
        if (pool == null) {
            throw new BizException(400, "号段不存在");
        }
        if (pool.getStatus() != null && pool.getStatus() == 2) {
            throw new BizException("该号段已用完, 不能再启用");
        }
        checkOrgScope(pool.getOrgId());
        // 旧使用中号段让位: 已分完则转已用完, 否则转未启用(可再次启用)
        List<HisInvoicePool> actives = poolMapper.selectList(Wrappers.<HisInvoicePool>lambdaQuery()
                .eq(HisInvoicePool::getOrgId, pool.getOrgId())
                .eq(HisInvoicePool::getInvoiceType, pool.getInvoiceType())
                .eq(HisInvoicePool::getStatus, 1)
                .ne(HisInvoicePool::getId, pool.getId()));
        for (HisInvoicePool old : actives) {
            long used = old.getCurrentNo() == null ? old.getStartNo() - 1 : old.getCurrentNo();
            HisInvoicePool upd = new HisInvoicePool();
            upd.setId(old.getId());
            upd.setStatus(used >= old.getEndNo() ? 2 : 0);
            poolMapper.updateById(upd);
        }
        String allocBy = UserContext.username();
        LocalDateTime allocTime = LocalDateTime.now();
        HisInvoicePool upd = new HisInvoicePool();
        upd.setId(pool.getId());
        upd.setStatus(1);
        upd.setAllocBy(allocBy);
        upd.setAllocTime(allocTime);
        poolMapper.updateById(upd);
        pool.setStatus(1);
        pool.setAllocBy(allocBy);
        pool.setAllocTime(allocTime);
        log.info("启用发票号段: orgId={}, poolCode={}, type={}, 旧使用中号段让位 {} 个",
                pool.getOrgId(), pool.getPoolCode(), pool.getInvoiceType(), actives.size());
        return pool;
    }

    // ==================== 取号与开票 ====================

    /**
     * 取发票号(前缀 + 8位序号)。synchronized 保证单实例串行, DB 行锁原子自增兑底多实例并发。
     * 无使用中号段/号段已用完抛 BizException, 由调用方决定是否阻塞业务。
     */
    public synchronized String allocateInvoiceNo(Long orgId, String invoiceType) {
        return doAllocate(orgId, invoiceType).invoiceNo;
    }

    /**
     * 收费开票: 按机构使用中的号段取号(纸质 NORMAL 优先, 未配置则电子 ELECTRONIC),
     * 落发票记录(type=NORMAL, status=1)并回写收费单 invoice_no。
     * 注意: 不加 @Transactional, 事务由收费方(CashierService.doCharge)携带;
     * 取号失败(未配置/用完)抛 BizException, 调用方捕获后仅记日志不阻塞收费。
     */
    public HisInvoice createInvoice(Long billId) {
        if (billId == null) {
            throw new BizException(400, "收费单ID不能为空");
        }
        HisChargeBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new BizException(400, "收费单不存在");
        }
        AllocatedNo no;
        try {
            no = doAllocate(bill.getOrgId(), "NORMAL");
        } catch (BizException e) {
            // 纸质号段未配置/用完时尝试电子发票号段
            no = doAllocate(bill.getOrgId(), "ELECTRONIC");
        }
        HisInvoice inv = new HisInvoice();
        inv.setOrgId(bill.getOrgId());
        inv.setInvoiceNo(no.invoiceNo);
        inv.setPoolId(no.pool.getId());
        inv.setBillId(bill.getId());
        inv.setInvoiceType("NORMAL");
        inv.setAmount(bill.getTotalAmount());
        inv.setPatientName(bill.getPatientName());
        inv.setStatus(1);
        invoiceMapper.insert(inv);
        // 回写收费单发票号
        HisChargeBill upd = new HisChargeBill();
        upd.setId(bill.getId());
        upd.setInvoiceNo(no.invoiceNo);
        billMapper.updateById(upd);
        log.info("开票完成: billNo={}, invoiceNo={}, patient={}", bill.getBillNo(), no.invoiceNo, bill.getPatientName());
        return inv;
    }

    /**
     * 发票作废: 原发票须为正常(status=1)。原发票置已作废(status=2),
     * 并生成冲销记录(type=VOID, 原号+"V", 金额取负, originalInvoiceId 指向原发票)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInvoice voidInvoice(Long invoiceId, String reason) {
        return voidOrRed(invoiceId, reason, false);
    }

    /**
     * 发票红冲: 原发票须为正常(status=1)。原发票置已红冲(status=3),
     * 并生成冲销记录(type=RED, 原号+"R", 金额取负, originalInvoiceId 指向原发票)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInvoice redInvoice(Long invoiceId, String reason) {
        return voidOrRed(invoiceId, reason, true);
    }

    /** 作废/红冲同构实现(red=false 作废 / red=true 红冲), 返回更新后的原发票 */
    private HisInvoice voidOrRed(Long invoiceId, String reason, boolean red) {
        if (invoiceId == null) {
            throw new BizException(400, "发票ID不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, red ? "红冲原因不能为空" : "作废原因不能为空");
        }
        HisInvoice origin = invoiceMapper.selectById(invoiceId);
        if (origin == null) {
            throw new BizException(400, "发票不存在");
        }
        if (origin.getStatus() == null || origin.getStatus() != 1) {
            throw new BizException("仅正常状态的发票可作废/红冲");
        }
        checkOrgScope(origin.getOrgId());
        String opBy = UserContext.username();
        LocalDateTime opTime = LocalDateTime.now();
        // 原发票置状态
        HisInvoice upd = new HisInvoice();
        upd.setId(origin.getId());
        upd.setStatus(red ? 3 : 2);
        upd.setVoidReason(reason);
        upd.setVoidBy(opBy);
        upd.setVoidTime(opTime);
        invoiceMapper.updateById(upd);
        // 冲销记录(负数金额, 指向原发票)
        HisInvoice n = new HisInvoice();
        n.setOrgId(origin.getOrgId());
        n.setInvoiceNo(origin.getInvoiceNo() + (red ? "R" : "V"));
        n.setPoolId(origin.getPoolId());
        n.setBillId(origin.getBillId());
        n.setInvoiceType(red ? "RED" : "VOID");
        n.setAmount(origin.getAmount() == null ? null : origin.getAmount().negate());
        n.setPatientName(origin.getPatientName());
        n.setStatus(red ? 3 : 2);
        n.setVoidReason(reason);
        n.setVoidBy(opBy);
        n.setVoidTime(opTime);
        n.setOriginalInvoiceId(origin.getId());
        invoiceMapper.insert(n);
        // 冲销后收费单不再持有有效发票号(避免单据仍显示已开票), 置回 NULL 须用 UpdateWrapper(null 不被字段更新策略忽略)
        if (origin.getBillId() != null) {
            billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getInvoiceNo, null)
                    .eq(HisChargeBill::getId, origin.getBillId()));
        }
        origin.setStatus(red ? 3 : 2);
        origin.setVoidReason(reason);
        origin.setVoidBy(opBy);
        origin.setVoidTime(opTime);
        log.info("发票{}: invoiceNo={}, 原因={}, 操作人={}", red ? "红冲" : "作废", n.getInvoiceNo(), reason, opBy);
        // 返回给前端的原发票同步清空发票号展示(已冲销, 收费单也不再持有该号)
        origin.setInvoiceNo(null);
        return origin;
    }

    /**
     * 退费联动发票冲销: 收费单存在正常(status=1)发票时按红冲处理(退费场景标准做法, 保留票据 traces)。
     * 无发票记录直接返回; 状态异常不抛错由调用方决定(退费主流程不应被发票状态阻塞)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void redFlushForBill(Long billId, String reason) {
        if (billId == null) {
            return;
        }
        HisInvoice inv = invoiceMapper.selectOne(Wrappers.<HisInvoice>lambdaQuery()
                .eq(HisInvoice::getBillId, billId)
                .eq(HisInvoice::getStatus, 1)
                .orderByDesc(HisInvoice::getId)
                .last("LIMIT 1"));
        if (inv == null) {
            return;
        }
        redInvoice(inv.getId(), reason);
    }

    // ==================== 发票记录 ====================

    /** 发票记录分页(按创建时间降序, 状态/创建日期区间过滤) */
    public IPage<HisInvoice> invoicePage(Long orgId, Integer status, String startDate, String endDate,
                                         long page, long size) {
        LambdaQueryWrapper<HisInvoice> w = Wrappers.<HisInvoice>lambdaQuery()
                .eq(orgId != null, HisInvoice::getOrgId, orgId)
                .eq(status != null, HisInvoice::getStatus, status);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisInvoice::getCreateTime, from)
                .le(to != null, HisInvoice::getCreateTime, to)
                .orderByDesc(HisInvoice::getCreateTime)
                .orderByDesc(HisInvoice::getId);
        return invoiceMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
    }

    /** 发票导出数据(head/rows, 与列表同筛选口径, 上限5000行) */
    public Map<String, Object> exportInvoices(Long orgId, String startDate, String endDate) {
        LambdaQueryWrapper<HisInvoice> w = Wrappers.<HisInvoice>lambdaQuery()
                .eq(orgId != null, HisInvoice::getOrgId, orgId);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisInvoice::getCreateTime, from)
                .le(to != null, HisInvoice::getCreateTime, to)
                .orderByDesc(HisInvoice::getId)
                .last("LIMIT 5000");
        List<HisInvoice> invoices = invoiceMapper.selectList(w);
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"发票号", "类型", "金额", "患者姓名", "状态", "作废/红冲原因", "创建时间"}) {
            head.add(java.util.Collections.singletonList(h));
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<Object>> rows = new ArrayList<>();
        for (HisInvoice inv : invoices) {
            List<Object> row = new ArrayList<>();
            row.add(inv.getInvoiceNo());
            row.add(typeText(inv.getInvoiceType()));
            row.add(inv.getAmount() == null ? "0.00" : inv.getAmount().toPlainString());
            row.add(inv.getPatientName());
            row.add(statusText(inv.getStatus()));
            row.add(inv.getVoidReason());
            row.add(inv.getCreateTime() == null ? "" : inv.getCreateTime().format(fmt));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", head);
        result.put("rows", rows);
        result.put("total", rows.size());
        return result;
    }

    // ==================== 内部工具 ====================

    /** 取号核心: 查使用中号段 -> current_no 原子自增(越界转已用完并拒绝) -> 前缀+8位序号。
     *  synchronized 单实例串行 + DB 行锁原子自增兑底多实例并发(阻塞排队, 不跳号不冲突)。 */
    private synchronized AllocatedNo doAllocate(Long orgId, String invoiceType) {
        if (orgId == null) {
            throw new BizException(400, "机构ID不能为空");
        }
        HisInvoicePool pool = poolMapper.selectOne(Wrappers.<HisInvoicePool>lambdaQuery()
                .eq(HisInvoicePool::getOrgId, orgId)
                .eq(HisInvoicePool::getInvoiceType, invoiceType)
                .eq(HisInvoicePool::getStatus, 1)
                .last("LIMIT 1"));
        if (pool == null) {
            throw new BizException("机构无使用中的[" + invoiceType + "]发票号段, 请先配置并启用号段");
        }
        // 原子推进 current_no(行锁串行, 并发自动排队): current_no < end_no 才允许推进,
        // rows=0 即已用到最后一个号, 号段转已用完并拒绝
        int rows = poolMapper.update(null, Wrappers.<HisInvoicePool>lambdaUpdate()
                .setSql("current_no = current_no + 1")
                .eq(HisInvoicePool::getId, pool.getId())
                .lt(HisInvoicePool::getCurrentNo, pool.getEndNo()));
        if (rows == 0) {
            // 复读最新状态再判定: 可能已被并发用尽(置已用完), 也可能号段刚被停用/换段(误封会把可用号段永久封死)
            HisInvoicePool latest = poolMapper.selectById(pool.getId());
            if (latest != null && latest.getCurrentNo() != null && latest.getEndNo() != null
                    && latest.getCurrentNo() >= latest.getEndNo()) {
                HisInvoicePool upd = new HisInvoicePool();
                upd.setId(pool.getId());
                upd.setStatus(2);
                poolMapper.updateById(upd);
                throw new BizException("发票号段已用完");
            }
            throw new BizException("发票号段已变更(停用或换段), 请重试");
        }
        // 回读取号结果(同事务可见自身更新)
        HisInvoicePool after = poolMapper.selectById(pool.getId());
        String prefix = pool.getPrefix() == null ? "" : pool.getPrefix();
        AllocatedNo r = new AllocatedNo();
        r.pool = pool;
        r.invoiceNo = prefix + String.format("%08d", after.getCurrentNo());
        return r;
    }

    /** 机构隔离: 非牵头机构仅可操作本机构发票/号段(牵头可跨机构) */
    private void checkOrgScope(Long orgId) {
        LoginUser lu = UserContext.get();
        if (lu != null && lu.getOrgId() != null && orgId != null
                && !orgId.equals(lu.getOrgId()) && !orgAccessGuard.isLead()) {
            throw new BizException(403, "仅可操作本机构发票");
        }
    }

    /** 取号结果(号段快照+发票号, 供开票记录回填 poolId) */
    private static class AllocatedNo {
        HisInvoicePool pool;
        String invoiceNo;
    }

    private static String typeText(String type) {
        if ("VOID".equals(type)) {
            return "作废";
        }
        if ("RED".equals(type)) {
            return "红冲";
        }
        return "正常";
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case 1: return "正常";
            case 2: return "已作废";
            case 3: return "已红冲";
            default: return String.valueOf(status);
        }
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    /** 填充号段派生统计(总号数/已用号数/已用百分比): 大整数号段一律服务端运算, 前端仅字符串展示 */
    private static void fillPoolStats(HisInvoicePool p) {
        long total = 0;
        if (p.getStartNo() != null && p.getEndNo() != null) {
            long q = p.getEndNo() - p.getStartNo() + 1;
            total = q > 0 ? q : 0;
        }
        long used = 0;
        if (p.getCurrentNo() != null && p.getStartNo() != null) {
            used = p.getCurrentNo() - p.getStartNo() + 1;
            if (used < 0) {
                used = 0;
            }
            if (total > 0 && used > total) {
                used = total;
            }
        }
        p.setTotalQty(total);
        p.setUsedQty(used);
        p.setUsedPercent(total > 0 ? (int) Math.round(used * 100.0 / total) : 0);
    }

    private static LocalDate parseDate(String d) {
        if (!StringUtils.hasText(d)) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim());
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(yyyy-MM-dd): " + d);
        }
    }

    private static LocalDateTime parseStart(String d) {
        LocalDate x = parseDate(d);
        return x == null ? null : x.atStartOfDay();
    }

    private static LocalDateTime parseEnd(String d) {
        LocalDate x = parseDate(d);
        return x == null ? null : x.atTime(23, 59, 59);
    }
}
