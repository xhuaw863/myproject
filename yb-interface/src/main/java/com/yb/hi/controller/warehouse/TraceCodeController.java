package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.TraceBindReq;
import com.yb.hi.dto.warehouse.TraceCollectReq;
import com.yb.hi.dto.warehouse.TraceStatusReq;
import com.yb.hi.entity.warehouse.HisDrugTraceCode;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.TraceCodeService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 医保药品追溯码接口(药库/药房): 采集/绑定/状态流转/Mock报送 + 分页/统计。
 * 追溯码采集与绑定是本机构药事过程: 写走 requireSelfOrgWrite(不限牵头), 读走 scopeOrgId。
 */
@RestController
@RequestMapping("/api/his/trace")
public class TraceCodeController {

    private final TraceCodeService traceCodeService;
    private final OrgAccessGuard guard;

    public TraceCodeController(TraceCodeService traceCodeService, OrgAccessGuard guard) {
        this.traceCodeService = traceCodeService;
        this.guard = guard;
    }

    @GetMapping("/page")
    public R<IPage<HisDrugTraceCode>> page(@RequestParam(required = false) Long orgId,
                                           @RequestParam(required = false) Long locationId,
                                           @RequestParam(required = false) Long drugCatalogId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) Integer uploadStatus,
                                           @RequestParam(required = false) String batchNo,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long size) {
        return R.ok(traceCodeService.page(guard.scopeOrgId(orgId), locationId, drugCatalogId,
                status, uploadStatus, batchNo, keyword, page, size));
    }

    @GetMapping("/statistics")
    public R<Map<String, Object>> statistics(@RequestParam(required = false) Long orgId,
                                             @RequestParam(required = false) Long locationId) {
        return R.ok(traceCodeService.statistics(guard.scopeOrgId(orgId), locationId));
    }

    /** 入库采集(扫描录入或批量占位建码) */
    @PostMapping("/collect")
    public R<Map<String, Object>> collect(@RequestBody TraceCollectReq req) {
        guard.requireSelfOrgWrite();
        req.setOrgId(resolveOrgId(req.getOrgId()));
        return R.ok(traceCodeService.collect(req));
    }

    /** 发药绑定(置已发药 + 患者/就诊/发药记录) */
    @PostMapping("/bind")
    public R<Map<String, Object>> bind(@RequestBody TraceBindReq req) {
        guard.requireSelfOrgWrite();
        return R.ok(traceCodeService.bind(req));
    }

    /** 状态流转(退货/报废·调拨在途/回库) */
    @PostMapping("/status")
    public R<Integer> status(@RequestBody TraceStatusReq req) {
        guard.requireSelfOrgWrite();
        if (req.getStatus() == null) {
            throw new BizException(400, "目标状态不能为空");
        }
        return R.ok(traceCodeService.updateStatus(req.getTraceCodes(), req.getStatus(),
                req.getRefBillType(), req.getRefBillId()));
    }

    /** Mock 2404 报送(已发药未报送 → 已报送 + 回执) */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long locationId) {
        guard.requireSelfOrgWrite();
        return R.ok(traceCodeService.upload(resolveOrgId(orgId), locationId));
    }

    private Long resolveOrgId(Long requested) {
        Long oid = guard.scopeOrgId(requested);
        if (oid != null) {
            return oid;
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }
}
