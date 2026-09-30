package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 住院医嘱接口: 医嘱列表 / 开立 / 批量成组开立 / 停止 / 作废 / 模板(套餐)开嘱。
 * 机构隔离与医生身份校验在 Service 层(见 InpOrderService)。
 */
@RestController
@RequestMapping("/api/his/inp/order")
public class InpOrderController {

    private final InpOrderService inpOrderService;

    public InpOrderController(InpOrderService inpOrderService) {
        this.inpOrderService = inpOrderService;
    }

    /** 医嘱列表(inpVisitId必传, orderType/orderStatus可选筛选, 分页) */
    @GetMapping("/list")
    public R<IPage<HisInpOrder>> list(
            @RequestParam Long inpVisitId,
            @RequestParam(required = false) Integer orderType,
            @RequestParam(required = false) Integer orderStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(inpOrderService.listOrders(inpVisitId, orderType, orderStatus, page, size));
    }

    /** 开立医嘱(服务端自动查价格填入unit_price; 临时医嘱开立即记账) */
    @PostMapping
    public R<HisInpOrder> create(@RequestBody InpOrderDTO dto) {
        return R.ok(inpOrderService.createOrder(dto));
    }

    /** 批量开立(成组医嘱, 同批次共享group_no, 整批一个事务) */
    @PostMapping("/batch")
    public R<List<HisInpOrder>> batch(@RequestBody List<InpOrderDTO> dtos) {
        return R.ok(inpOrderService.batchCreate(dtos));
    }

    /** 停止医嘱(仅已审核/执行中; 记录停嘱时间与医生, 同group_no整组联动停止) */
    @PutMapping("/{id}/stop")
    public R<HisInpOrder> stop(@PathVariable Long id) {
        return R.ok(inpOrderService.stopOrder(id));
    }

    /** 作废医嘱(仅新开未审核; 同group_no整组联动, 已记账费用同步冲销) */
    @PutMapping("/{id}/cancel")
    public R<HisInpOrder> cancel(@PathVariable Long id) {
        return R.ok(inpOrderService.cancelOrder(id));
    }

    /** 续开医嘱(T42: 仅已停止的长期医嘱, 复制原医嘱要素重新开立, source_order_id回写溯源; 医生ID取自登录上下文) */
    @PostMapping("/renew/{orderId}")
    public R<HisInpOrder> renewOrder(@PathVariable Long orderId) {
        return R.ok(inpOrderService.renewOrder(orderId, InpOrderService.currentDoctorId()));
    }

    /** 医嘱副本数据(T42: 复制开立预填, 返回原医嘱核心要素与可续开标记) */
    @GetMapping("/copy-data/{orderId}")
    public R<Map<String, Object>> copyOrderData(@PathVariable Long orderId) {
        return R.ok(inpOrderService.copyOrderData(orderId));
    }

    /** 模板/套餐开嘱: 解析模板items逐条走标准开立链路(服务端定价; 停用/非本人守卫见InpOrderService) */
    @PostMapping("/from-template")
    public R<List<HisInpOrder>> createFromTemplate(@RequestBody Map<String, Object> body) {
        Long visitId = body.get("visitId") == null ? null : Long.valueOf(String.valueOf(body.get("visitId")));
        Long templateId = body.get("templateId") == null ? null : Long.valueOf(String.valueOf(body.get("templateId")));
        return inpOrderService.createFromTemplate(visitId, templateId, null);
    }

    /** 常用医嘱模板(旧占位接口, 已被 /api/his/inp/order-template 系列取代) */
    @GetMapping("/template")
    public R<List<Map<String, Object>>> template() {
        return R.ok(new ArrayList<>());
    }
}
