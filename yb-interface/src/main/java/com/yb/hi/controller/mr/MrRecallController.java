package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.mr.HisMrRecall;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrRecallService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 病案收回接口(P1-A): 收回作业列表 / 批量建单 / 扫码回收 / 上架 / 逾期刷新 / 回收率报表。
 * 写接口 requireSelfOrgWrite(本机构管理员) 在 Service 层。
 */
@RestController
@RequestMapping("/api/his/mr/recall")
public class MrRecallController {

    private final MrRecallService recallService;

    public MrRecallController(MrRecallService recallService) {
        this.recallService = recallService;
    }

    /** 收回作业列表(分页, 状态/科室/关键字过滤)。 */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long deptId,
                                              @RequestParam(required = false) String keyword) {
        return R.ok(recallService.listPage(page, size, status, keyword, deptId));
    }

    /** 批量建单(收回登记): body {visitIds:[...]}。 */
    @PostMapping("/enroll")
    public R<Map<String, Object>> enroll(@RequestBody Map<String, Object> body) {
        return R.ok(recallService.enroll(toLongList(body == null ? null : body.get("visitIds"))));
    }

    /** 扫码/登记回收: body {barcode|visitId, shelfLocation?, remark?}。 */
    @PostMapping("/scan")
    public R<HisMrRecall> scan(@RequestBody Map<String, Object> body) {
        return R.ok(recallService.scanRecall(body));
    }

    /** 上架: body {shelfLocation}。 */
    @PutMapping("/{id}/shelf")
    public R<Void> shelf(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String location = body == null ? null : String.valueOf(body.get("shelfLocation"));
        recallService.shelf(id, location);
        return R.ok();
    }

    /** 逾期刷新: 将到期未收回置为逾期, 返回处理数。 */
    @PostMapping("/refresh-overdue")
    public R<Integer> refreshOverdue() {
        return R.ok(recallService.refreshOverdue());
    }

    /** 回收率报表: 按科室汇总 + 全院合计, 可选应回收日期区间 [from,to]。 */
    @GetMapping("/rate")
    public R<Map<String, Object>> rate(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        return R.ok(recallService.rate(from, to));
    }

    /** 宽松 Long 列表解析: 兼容 Number 与被全局序列化为字符串的雪花 ID。 */
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
