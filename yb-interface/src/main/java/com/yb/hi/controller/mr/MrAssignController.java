package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrAssignService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 病案分配接口: 待分配池查询 / 分配(均分·偏好) / 释放 / 编目员剩余工作量 / 编目员候选。
 * 写接口 requireSelfOrgWrite(本机构管理员) 在 Service 层。
 */
@RestController
@RequestMapping("/api/his/mr/assign")
public class MrAssignController {

    private final MrAssignService assignService;

    public MrAssignController(MrAssignService assignService) {
        this.assignService = assignService;
    }

    /** 待分配池(已出院未编目患者)。 */
    @GetMapping("/pending")
    public R<IPage<Map<String, Object>>> pending(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) Long deptId,
                                                 @RequestParam(required = false) String keyword) {
        return R.ok(assignService.pendingPage(page, size, keyword, deptId));
    }

    /** 分配: body {visitIds:[], mode:1偏好|2随机均分, catalogers:[{catalogerId,catalogerName,priority}]} */
    @PostMapping("/assign")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> assign(@RequestBody Map<String, Object> body) {
        List<Object> raw = (List<Object>) body.get("visitIds");
        List<Long> visitIds = new java.util.ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                Long v = toLong(o);
                if (v != null) {
                    visitIds.add(v);
                }
            }
        }
        int mode = body.get("mode") == null ? 2 : toLong(body.get("mode")).intValue();
        List<Map<String, Object>> catalogers = (List<Map<String, Object>>) body.get("catalogers");
        return R.ok(assignService.assign(visitIds, mode, catalogers));
    }

    /** 释放: body {visitId, reason} */
    @PostMapping("/release")
    public R<Void> release(@RequestBody Map<String, Object> body) {
        Long visitId = toLong(body.get("visitId"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        assignService.release(visitId, reason);
        return R.ok();
    }

    /** 编目员剩余工作量。 */
    @GetMapping("/workload")
    public R<List<Map<String, Object>>> workload() {
        return R.ok(assignService.workload());
    }

    /** 编目员候选(职工)。 */
    @GetMapping("/catalogers")
    public R<List<Map<String, Object>>> catalogers(@RequestParam(required = false) String keyword) {
        return R.ok(assignService.catalogers(keyword));
    }

    /** 宽松 Long 解析: 兼容 Number 与被全局序列化为字符串的雪花 ID。 */
    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
