package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.OrderReq;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisOrderItem;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisOrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检查/检验/治疗单接口: 开单 / 查询就诊单据 / 查询明细
 */
@RestController
@RequestMapping("/api/his/order")
public class HisOrderController {

    private final HisOrderService service;

    public HisOrderController(HisOrderService service) {
        this.service = service;
    }

    /** 查询某次就诊的单据列表 */
    @GetMapping("/list")
    public R<List<HisOrder>> list(@RequestParam Long visitId) {
        return R.ok(service.listByVisit(visitId));
    }

    /** 查询单据明细 */
    @GetMapping("/items")
    public R<List<HisOrderItem>> items(@RequestParam Long orderId) {
        return R.ok(service.listItems(orderId));
    }

    /** 开检查/检验/治疗单 */
    @PostMapping("/create")
    public R<HisOrder> create(@RequestBody OrderReq req) {
        return R.ok(service.create(req));
    }

    /** 作废未收费医嘱单 */
    @PostMapping("/cancel")
    public R<HisOrder> cancel(@RequestParam Long id) {
        return R.ok(service.cancel(id));
    }

    /** 患者检查/检验/治疗报告(已执行, 含单据明细) */
    @GetMapping("/reports")
    public R<List<Map<String, Object>>> reports(@RequestParam Long patientId) {
        return R.ok(service.listReports(patientId));
    }
}
