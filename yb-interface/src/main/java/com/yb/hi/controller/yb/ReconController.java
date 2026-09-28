package com.yb.hi.controller.yb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.yb.HisReconDiff;
import com.yb.hi.entity.yb.HisReconTask;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.yb.HisReconDiffMapper;
import com.yb.hi.mapper.yb.HisReconTaskMapper;
import com.yb.hi.service.yb.ReconService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 医保对账台(M3): 手动触发 3201/3202 对账(默认对 T-1), 查看对账任务与差异明细, 差异人工核对处置。
 */
@RestController
@RequestMapping("/api/yb/recon")
public class ReconController {

    private final ReconService reconService;
    private final HisReconTaskMapper reconTaskMapper;
    private final HisReconDiffMapper reconDiffMapper;

    public ReconController(ReconService reconService, HisReconTaskMapper reconTaskMapper,
                           HisReconDiffMapper reconDiffMapper) {
        this.reconService = reconService;
        this.reconTaskMapper = reconTaskMapper;
        this.reconDiffMapper = reconDiffMapper;
    }

    /** 手动触发对账(默认 T-1; force=true 作废历史任务/差异后强制重跑; 租户自请求上下文) */
    @PostMapping("/run")
    public R<String> run(@RequestParam(required = false) String date,
                         @RequestParam(defaultValue = "false") boolean force) {
        LocalDate d = date == null ? LocalDate.now().minusDays(1) : parseDate(date);
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return R.fail(403, "无租户上下文");
        }
        try {
            reconService.reconcile(tenantId, d, force);
            return R.ok("对账已执行: " + d + (force ? "(强制重跑)" : ""));
        } catch (Exception e) {
            return R.fail(500, "对账执行失败: " + e.getMessage());
        }
    }

    /** 对账任务分页 */
    @GetMapping("/tasks")
    public R<IPage<HisReconTask>> tasks(@RequestParam(required = false) String date,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        LocalDate d = date == null ? null : parseDate(date);
        IPage<HisReconTask> p = reconTaskMapper.selectPage(new Page<>(page, size),
                Wrappers.<HisReconTask>lambdaQuery()
                        .eq(HisReconTask::getDeleted, 0)
                        .eq(d != null, HisReconTask::getStmtDate, d)
                        .eq(type != null && !type.isEmpty(), HisReconTask::getReconType, type)
                        .orderByDesc(HisReconTask::getId));
        return R.ok(p);
    }

    /** 差异明细分页(status 0待处理/1已核对/2已平账) */
    @GetMapping("/diffs")
    public R<IPage<HisReconDiff>> diffs(@RequestParam(required = false) Integer status,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        IPage<HisReconDiff> p = reconDiffMapper.selectPage(new Page<>(page, size),
                Wrappers.<HisReconDiff>lambdaQuery()
                        .eq(HisReconDiff::getDeleted, 0)
                        .eq(status != null, HisReconDiff::getStatus, status)
                        .orderByDesc(HisReconDiff::getId));
        return R.ok(p);
    }

    /** 差异人工核对处置: status 1已核对 / 2已平账 */
    @PostMapping("/diff/{id}/handle")
    public R<String> handleDiff(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        HisReconDiff diff = reconDiffMapper.selectById(id);
        if (diff == null) {
            return R.fail(404, "差异记录不存在");
        }
        Object s = body.get("status");
        int status = s == null ? 1 : Integer.parseInt(s.toString());
        if (status < 1 || status > 2) {
            return R.fail(400, "处理状态仅支持 1已核对/2已平账");
        }
        diff.setStatus(status);
        Object memo = body.get("handleMemo");
        if (memo != null) {
            diff.setHandleMemo(memo.toString());
        }
        diff.setHandleTime(LocalDateTime.now());
        reconDiffMapper.updateById(diff);
        return R.ok("已处置");
    }

    private LocalDate parseDate(String d) {
        try {
            return LocalDate.parse(d);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式不正确(yyyy-MM-dd)");
        }
    }
}
