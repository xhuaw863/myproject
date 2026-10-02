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

    /** 2404 报送(批次5 M1: 两阶段认领+状态机留痕; 发送当前 mock 直通, M2 接真实交易链) */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long locationId) {
        guard.requireSelfOrgWrite();
        return R.ok(traceCodeService.upload(resolveOrgId(orgId), locationId));
    }

    /** 3506A 退货报送(批次5 M3): 已退货且销售已报送的码按原销售批次补报退货(退货联动已自动, 本端点为重扫/补扫入口) */
    @PostMapping("/upload-returns")
    public R<Map<String, Object>> uploadReturns(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) Long locationId) {
        guard.requireSelfOrgWrite();
        return R.ok(traceCodeService.uploadReturns(resolveOrgId(orgId), locationId));
    }

    /** 3507A 销售批次删除冲正(批次5 M4): 错报整批冲正后码行归 0 待重报; 平台侧级联删该批次销售/退货数据 */
    @PostMapping("/delete-upload")
    public R<Map<String, Object>> deleteUpload(@RequestParam(required = false) Long orgId,
                                               @RequestParam String batch) {
        guard.requireSelfOrgWrite();
        return R.ok(traceCodeService.deleteSalesBatch(resolveOrgId(orgId), batch));
    }

    /** 3512/3513 平台追溯信息对账查询(批次5 M4, 只读): 输出驼峰字段直透; 条件校验在服务层(三选一/五选一) */
    @GetMapping("/trac-query")
    public R<Map<String, Object>> tracQuery(@RequestParam String infno,
                                            @RequestParam(required = false) String medinsListCodg,
                                            @RequestParam(required = false) String fixmedinsBchno,
                                            @RequestParam(required = false) String medListCodg,
                                            @RequestParam(required = false) String drugTracCodg,
                                            @RequestParam(required = false) String mdtrtId,
                                            @RequestParam(required = false) String certno,
                                            @RequestParam(required = false) String begndate,
                                            @RequestParam(required = false) String enddate) {
        Map<String, String> p = new java.util.HashMap<>();
        p.put("medinsListCodg", medinsListCodg);
        p.put("fixmedinsBchno", fixmedinsBchno);
        p.put("medListCodg", medListCodg);
        p.put("drugTracCodg", drugTracCodg);
        p.put("mdtrtId", mdtrtId);
        p.put("certno", certno);
        p.put("begndate", begndate);
        p.put("enddate", enddate);
        return R.ok(traceCodeService.queryTrac(infno, p));
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
