package com.yb.hi.controller.ris;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.ris.RisRequestDTO;
import com.yb.hi.dto.ris.RisRequestQueryDTO;
import com.yb.hi.entity.ris.HisExamRequest;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.ris.RisRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RIS 检查申请单接口:
 * 读按当前登录机构隔离(OrgAccessGuard.scopeOrgId, 非牵头忽略入参锁定本机构;
 * 牵头可带 orgId 查成员机构视角, 未指定回落本机构), 写取当前登录机构;
 * 状态流转仅顺序前进+取消两口径(取消联动释放排程占位)。
 */
@RestController
@RequestMapping("/api/ris/request")
public class RisRequestController {

    private final RisRequestService requestService;
    private final OrgAccessGuard guard;

    public RisRequestController(RisRequestService requestService, OrgAccessGuard guard) {
        this.requestService = requestService;
        this.guard = guard;
    }

    /**
     * 创建申请单: 携带 orderId(门诊医嘱)/inpOrderId(住院医嘱)时从医嘱侧提取检查要素幂等开单,
     * 否则按 DTO 手工开单(体检/急诊等无医嘱来源)。
     */
    @PostMapping
    public R<HisExamRequest> create(@RequestBody RisRequestDTO dto) {
        if (dto == null) {
            throw new BizException(400, "申请单参数不能为空");
        }
        if (dto.getOrderId() != null) {
            return R.ok(requestService.createFromOutpatientOrder(dto.getOrderId()));
        }
        if (dto.getInpOrderId() != null) {
            return R.ok(requestService.createFromInpatientOrder(dto.getInpOrderId()));
        }
        return R.ok(requestService.create(dto));
    }

    /**
     * 申请单分页查询: 支持关键字/来源/检查类型/状态/急诊/患者/科室/设备/缴费/上报状态/申请日期区间筛选。
     * 机构守卫: scopeOrgId(orgId) —— 非牵头锁定本机构, 牵头指定 orgId 可查成员机构, 未指定回落本机构。
     */
    @GetMapping("/page")
    public R<IPage<Map<String, Object>>> page(RisRequestQueryDTO query,
                                              @RequestParam(required = false) Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            scope = guard.currentOrgId();
        }
        return R.ok(requestService.page(scope, query));
    }

    /** 申请单详情(含患者/设备/排程时段信息) */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(requestService.getDetail(id));
    }

    /** 状态变更(顺序前进 0->1->2->3->4->5->6, 乐观锁; 取消走 /cancel) */
    @PutMapping("/{id}/status")
    public R<HisExamRequest> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer status = body == null || body.get("status") == null ? null
                : Integer.valueOf(body.get("status").toString());
        if (status == null) {
            throw new BizException(400, "目标状态不能为空");
        }
        return R.ok(requestService.updateStatus(id, status));
    }

    /** 取消申请单(仅待预约/已预约可取消, 已预约联动释放排程占位) */
    @PutMapping("/{id}/cancel")
    public R<HisExamRequest> cancel(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? null : body.get("reason").toString();
        return R.ok(requestService.cancel(id, reason));
    }

    /** 按执行科室+日期+状态查询(登记台/技师工作台视角) */
    @GetMapping("/by-dept")
    public R<List<HisExamRequest>> listByDept(@RequestParam Long deptId,
                                              @RequestParam(required = false) String date,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            scope = guard.currentOrgId();
        }
        return R.ok(requestService.listByDept(scope, deptId, date, status));
    }

    /** 按分配设备+日期查询(技师工作台视角, 未取消的已预约/登记/检查中/完成单) */
    @GetMapping("/by-device")
    public R<List<HisExamRequest>> listByDevice(@RequestParam Long deviceId,
                                                @RequestParam(required = false) String date,
                                                @RequestParam(required = false) Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            scope = guard.currentOrgId();
        }
        return R.ok(requestService.listByDevice(scope, deviceId, date));
    }
}
