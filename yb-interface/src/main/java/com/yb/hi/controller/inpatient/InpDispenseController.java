package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.InpDispenseReq;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpDispenseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 住院发药接口(P4, 规范 16.3.1/16.3.2): 待发药队列 / 整包装取整预览 / 缺药替换候选 / 发药(集中批量) /
 * 退药(退回药房或暂存病区) / 出院带药取药+二次核发 / 历史发药多维查询 / 自动发药开关。
 * 机构隔离、租户过滤、库存联动与三量守恒均在 Service 层(InpDispenseService)。
 */
@RestController
@RequestMapping("/api/his/inp/dispense")
public class InpDispenseController {

    private final InpDispenseService dispenseService;

    public InpDispenseController(InpDispenseService dispenseService) {
        this.dispenseService = dispenseService;
    }

    /** 待发药队列: 病区/科室/关键词过滤, pharmacyId 影响库存与冲抵口径, 分页。 */
    @GetMapping("/queue")
    public R<Map<String, Object>> queue(@RequestParam(required = false) Long wardId,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Long pharmacyId,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(dispenseService.getQueue(wardId, deptId, keyword, pharmacyId, page, size));
    }

    /** 单条医嘱发药预览: 三量取整 + 库存充足性 + 可冲抵暂存量。 */
    @GetMapping("/preview")
    public R<Map<String, Object>> preview(@RequestParam Long orderId,
                                          @RequestParam(required = false) Long pharmacyId) {
        return R.ok(dispenseService.preview(orderId, pharmacyId));
    }

    /** 缺药替换候选: 同本位码同规格、不同厂家的启用且有库存药品。 */
    @GetMapping("/replace-candidates")
    public R<List<Map<String, Object>>> replaceCandidates(@RequestParam Long orderId,
                                                          @RequestParam(required = false) Long pharmacyId) {
        return R.ok(dispenseService.replaceCandidates(orderId, pharmacyId));
    }

    /** 住院发药(支持按病人集中批量): body 为 InpDispenseReq(items 至少一条)。 */
    @PostMapping("/dispense")
    public R<List<Map<String, Object>>> dispense(@RequestBody InpDispenseReq req) {
        return R.ok(dispenseService.dispense(req));
    }

    /** 退药: body { dispenseId, qty, reason, keepWard(bool) }。keepWard=true 暂存病区供冲抵, false 退回药房回库。 */
    @PostMapping("/return")
    public R<Map<String, Object>> returnDrug(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new BizException(400, "退药参数不能为空");
        }
        Long dispenseId = parseId(body.get("dispenseId"));
        BigDecimal qty = parseBd(body.get("qty"));
        Object reason = body.get("reason");
        boolean keepWard = truthy(body.get("keepWard"));
        return R.ok(dispenseService.returnDrug(dispenseId, qty, reason == null ? null : String.valueOf(reason), keepWard));
    }

    /** 出院带药取药/核发列表: inpVisitId 与 status 可选过滤。 */
    @GetMapping("/pickup")
    public R<List<Map<String, Object>>> pickup(@RequestParam(required = false) Long inpVisitId,
                                               @RequestParam(required = false) Integer status) {
        return R.ok(dispenseService.listPickup(inpVisitId, status));
    }

    /** 取药(第一段): body { pickupId, invoiceNo(可空自动生成), windowId }。 */
    @PostMapping("/pickup/confirm")
    public R<Map<String, Object>> confirmPickup(@RequestBody Map<String, Object> body) {
        Long pickupId = body == null ? null : parseId(body.get("pickupId"));
        Object invoiceNo = body == null ? null : body.get("invoiceNo");
        Long windowId = body == null ? null : parseId(body.get("windowId"));
        return R.ok(dispenseService.confirmPickup(pickupId, invoiceNo == null ? null : String.valueOf(invoiceNo), windowId));
    }

    /** 二次核发(第二段): body { pickupId }。 */
    @PostMapping("/pickup/verify2")
    public R<Map<String, Object>> verifyPickup2(@RequestBody Map<String, Object> body) {
        Long pickupId = body == null ? null : parseId(body.get("pickupId"));
        return R.ok(dispenseService.verifyPickup2(pickupId));
    }

    /** 历史发药多维查询: dimension=dispense|return, keyword, startDate/endDate(yyyy-MM-dd), 分页。 */
    @GetMapping("/history")
    public R<Map<String, Object>> history(@RequestParam(defaultValue = "dispense") String dimension,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(dispenseService.history(dimension, keyword, startDate, endDate, page, size));
    }

    /** 自动发药开关(system-param): 供前端是否展示一键自动发药。 */
    @GetMapping("/auto-param")
    public R<Boolean> autoParam() {
        return R.ok(dispenseService.autoDispenseEnabled());
    }

    /* ---------- 宽松入参解析(兼容 JSON 数字与字符串, 雪花ID以字符串承载) ---------- */

    private static Long parseId(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseBd(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return new BigDecimal(o.toString());
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new BizException(400, "数量格式不正确: " + s);
        }
    }

    private static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        String s = String.valueOf(o).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }
}
