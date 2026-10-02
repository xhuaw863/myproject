package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpAbxReviewService;
import com.yb.hi.service.inpatient.InpOrderService;
import com.yb.hi.service.inpatient.SurgeryOrderService;
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
    private final InpAbxReviewService abxReviewService;
    private final SurgeryOrderService surgeryOrderService;

    public InpOrderController(InpOrderService inpOrderService, InpAbxReviewService abxReviewService,
                             SurgeryOrderService surgeryOrderService) {
        this.inpOrderService = inpOrderService;
        this.abxReviewService = abxReviewService;
        this.surgeryOrderService = surgeryOrderService;
    }

    /** 手术药品医嘱发送药房(仅手术关联医嘱, 0/2->1): 置后方进入住院发药队列 */
    @PostMapping("/{id}/send-pharmacy")
    public R<Integer> sendPharmacy(@PathVariable Long id) {
        return R.ok(surgeryOrderService.sendPharmacy(id));
    }

    /** 手术药品医嘱撤回发送(1->2, 仅未发药可撤, 已发药拒绝) */
    @PostMapping("/{id}/recall-pharmacy")
    public R<Integer> recallPharmacy(@PathVariable Long id) {
        return R.ok(surgeryOrderService.recallPharmacy(id));
    }

    /** 手术医嘱执行留痕(手麻P3a): 手术侧直写执行记录(转抄即执行), 已执行后锁定作废/发送撤回 */
    @PostMapping("/{id}/execute")
    public R<Object> execute(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("remark");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("orderId", id);
        out.put("execTime", surgeryOrderService.execute(id, remark).getExecTime());
        return R.ok(out);
    }

    /** 手术药品医嘱退药申请(手麻P3a): 已发药未执行可发起, 药房 returnDrug 完成时闭环回写 */
    @PostMapping("/{id}/return-apply")
    public R<Integer> returnApply(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return R.ok(surgeryOrderService.returnApply(id, reason));
    }

    /**
     * 抗菌到期/越级告警扫描(只读, 医生站工作台待办): staffId 缺省回落登录医生本人; all=true 则不限医生, 查本租户全部医生。
     */
    @GetMapping("/abx-expiry-alerts")
    public R<java.util.List<Map<String, Object>>> abxEpiryAlerts(
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) Long inpVisitId,
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) Integer soonDays) {
        Long sid = all ? null : (staffId != null ? staffId : InpAbxReviewService.currentStaffId());
        return R.ok(abxReviewService.abxEpiryAlerts(sid, inpVisitId, soonDays));
    }

    /** 医嘱列表(inpVisitId必传, orderType/orderStatus/deptId 可选筛选, 分页) */
    @GetMapping("/list")
    public R<IPage<HisInpOrder>> list(
            @RequestParam Long inpVisitId,
            @RequestParam(required = false) Integer orderType,
            @RequestParam(required = false) Integer orderStatus,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(inpOrderService.listOrders(inpVisitId, orderType, orderStatus, deptId, page, size));
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
