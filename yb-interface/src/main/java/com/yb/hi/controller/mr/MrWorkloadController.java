package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.mr.HisMrWorkload;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrWorkloadService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 病案工作量统计接口(P2): 列表 / 新增 / 编辑 / 删除 / 单条与批量逻辑审核 / 概览汇总。
 * 写接口 requireSelfOrgWrite 在 Service 层。
 */
@RestController
@RequestMapping("/api/his/mr/workload")
public class MrWorkloadController {

    private final MrWorkloadService workloadService;

    public MrWorkloadController(MrWorkloadService workloadService) {
        this.workloadService = workloadService;
    }

    @GetMapping("/list")
    public R<IPage<HisMrWorkload>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String period,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) Integer auditStatus,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(workloadService.listPage(page, size, period, deptId, category, auditStatus, keyword));
    }

    @PostMapping
    public R<HisMrWorkload> create(@RequestBody Map<String, Object> body) {
        return R.ok(workloadService.create(body));
    }

    @PutMapping("/{id}")
    public R<HisMrWorkload> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(workloadService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        workloadService.delete(id);
        return R.ok();
    }

    /** 单条逻辑审核: body {approved:true/false, opinion}。 */
    @PutMapping("/{id}/audit")
    public R<HisMrWorkload> audit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean approved = toBool(body == null ? null : body.get("approved"));
        String opinion = body == null ? null : str(body.get("opinion"));
        return R.ok(workloadService.audit(id, approved, opinion));
    }

    /** 批量逻辑审核: body {ids:[...], approved, opinion}。 */
    @PostMapping("/batch-audit")
    public R<Map<String, Object>> batchAudit(@RequestBody Map<String, Object> body) {
        List<Long> ids = toLongList(body == null ? null : body.get("ids"));
        boolean approved = toBool(body == null ? null : body.get("approved"));
        String opinion = body == null ? null : str(body.get("opinion"));
        return R.ok(workloadService.batchAudit(ids, approved, opinion));
    }

    @GetMapping("/summary")
    public R<List<Map<String, Object>>> summary(@RequestParam(required = false) String period) {
        return R.ok(workloadService.summary(period));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue() != 0;
        }
        String s = o == null ? "" : String.valueOf(o).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                Long v = toLong(o);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

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
