package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrSubmitService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 病案上报三段闭环接口(P2): 类型 / 批次列表 / 建批次 / 审核→转换→上报 / 详情 / 删除。
 * 写接口 requireSelfOrgWrite 在 Service 层; 状态严格前进(1→2→3→4), 跳档返回 409。
 */
@RestController
@RequestMapping("/api/his/mr/submit")
public class MrSubmitController {

    private final MrSubmitService submitService;

    public MrSubmitController(MrSubmitService submitService) {
        this.submitService = submitService;
    }

    @GetMapping("/types")
    public R<List<String>> types() {
        return R.ok(submitService.types());
    }

    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String reportType,
                                              @RequestParam(required = false) Integer status) {
        return R.ok(submitService.listPage(page, size, reportType, status));
    }

    /** 建批次: body {reportType, from, to}。 */
    @PostMapping("/batch")
    public R<Map<String, Object>> createBatch(@RequestBody Map<String, Object> body) {
        return R.ok(submitService.createBatch(body));
    }

    /** 第一段·数据审核。 */
    @PutMapping("/batch/{id}/audit")
    public R<Map<String, Object>> audit(@PathVariable Long id) {
        return R.ok(submitService.audit(id));
    }

    /** 第二段·数据转换。 */
    @PutMapping("/batch/{id}/convert")
    public R<Map<String, Object>> convert(@PathVariable Long id) {
        return R.ok(submitService.convert(id));
    }

    /** 第三段·上报。 */
    @PutMapping("/batch/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable Long id) {
        return R.ok(submitService.submit(id));
    }

    @GetMapping("/batch/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(submitService.detail(id));
    }

    @DeleteMapping("/batch/{id}")
    public R<Void> delete(@PathVariable Long id) {
        submitService.deleteBatch(id);
        return R.ok();
    }
}
