package com.yb.hi.controller.inpatient;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.IoRecordService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 住院出入量记录接口(护士站「出入量」面板): 逐笔登记 / 当日列表 / 日汇总 / 逻辑删除。
 * 口径: io_type 1进量 2出量, volume 单位 ml; 日汇总 balance = 进量合计 - 出量合计(负值前端红色提示)。
 * 归属机构校验在 Service 内完成(就诊 org_id 对齐当前登录机构, 平台超管放行)。
 */
@RestController
@RequestMapping("/api/his/inp/io-record")
public class IoRecordController {

    private final IoRecordService ioRecordService;

    public IoRecordController(IoRecordService ioRecordService) {
        this.ioRecordService = ioRecordService;
    }

    /**
     * 新增出入量记录: params 键约定 visitId/ioType(1进量 2出量)/category/volume(ml)/route/note/recordTime(可选),
     * 登记护士取当前登录职工。
     */
    @PostMapping
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> params) {
        return R.ok(ioRecordService.save(params, InpNurseController.currentNurseId()));
    }

    /** 当日记录列表(按记录时间升序), 附登记护士姓名。 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam Long visitId, @RequestParam String date) {
        return R.ok(ioRecordService.list(visitId, date));
    }

    /** 日汇总: {date, intakeTotal, outputTotal, balance}, balance 负值为出量大于进量。 */
    @GetMapping("/daily-summary")
    public R<Map<String, Object>> dailySummary(@RequestParam Long visitId, @RequestParam String date) {
        return R.ok(ioRecordService.getDailySummary(visitId, date));
    }

    /** 删除出入量记录(逻辑删除, 汇总口径自动排除)。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ioRecordService.delete(id);
        return R.ok();
    }
}
