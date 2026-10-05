package com.yb.hi.controller.ris;

import com.yb.hi.dto.ris.RisExecutionDTO;
import com.yb.hi.entity.ris.HisExamExecution;
import com.yb.hi.entity.ris.HisExamWorklist;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.ris.RisExecutionService;
import com.yb.hi.service.ris.RisWorklistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RIS 技师工作站接口(检查执行与 DICOM Worklist):
 * 1) 今日任务板: today-tasks 合并 Worklist 条目(含到检/执行进度)与无 Worklist 的执行记录, 按预约时间排序;
 * 2) 执行流转: 到检签到 -> 开始检查 -> 完成检查(剂量/图像信息), 状态回写申请单 2/3/4与Worklist 0/1/2;
 * 3) Worklist 管理: 生成(GET查询/回填StudyUID/MPPS回调);
 * 4) 读写均按登录机构范围隔离(OrgAccessGuard, 牵头可跨机构, 非牵头仅本机构)。
 */
@RestController
@RequestMapping("/api/ris/workstation")
public class RisWorkstationController {

    private final RisWorklistService worklistService;
    private final RisExecutionService executionService;

    public RisWorkstationController(RisWorklistService worklistService, RisExecutionService executionService) {
        this.worklistService = worklistService;
        this.executionService = executionService;
    }

    /* ================= 技师工作台 ================= */

    /**
     * 今日检查任务列表: 今日该设备 Worklist(含最新执行进度) + 无 Worklist 的执行记录合并,
     * 按预约时间(scheduledAt)升序、其次 Accession 号; 行内含 executionId/checkInTime 供到检/开始/完成操作。
     */
    @GetMapping("/today-tasks")
    public R<List<Map<String, Object>>> todayTasks(@RequestParam Long deviceId) {
        List<Map<String, Object>> tasks = new ArrayList<>(worklistService.listTodayByDevice(deviceId));
        Set<Long> covered = new HashSet<>();
        for (Map<String, Object> t : tasks) {
            if (t.get("requestId") instanceof Number) {
                covered.add(((Number) t.get("requestId")).longValue());
            }
        }
        for (Map<String, Object> row : executionService.listTodayByDevice(deviceId)) {
            Object rid = row.get("requestId");
            if (!(rid instanceof Number)) {
                tasks.add(row);
            } else if (!covered.contains(((Number) rid).longValue())) {
                covered.add(((Number) rid).longValue());
                tasks.add(row);
            }
        }
        tasks.sort(Comparator
                .comparing((Map<String, Object> m) -> (LocalDateTime) m.get("scheduledAt"),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(m -> Objects.toString(m.get("accessionNo"), "")));
        return R.ok(tasks);
    }

    /** 到检签到: 创建执行记录(check_in_time=now, status=0), 申请单 -> 2已登记; 幂等(已有未完成执行记录直接返回)。 */
    @PostMapping("/check-in/{requestId}")
    public R<HisExamExecution> checkIn(@PathVariable Long requestId) {
        return R.ok(executionService.checkIn(requestId));
    }

    /** 开始检查: 执行 0/3 -> 1检查中, 申请单 -> 3检查中, Worklist -> 1检查中; 技师缺省取当前登录职工。 */
    @PostMapping("/start-exam/{executionId}")
    public R<HisExamExecution> startExam(@PathVariable Long executionId,
                                         @RequestParam(required = false) Long deviceId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Long dev = deviceId != null ? deviceId : toLong(body == null ? null : body.get("deviceId"));
        Long technicianId = toLong(body == null ? null : body.get("technicianId"));
        return R.ok(executionService.startExam(executionId, dev, technicianId));
    }

    /** 完成检查: body=RisExecutionDTO(剂量/图像/StudyUID信息), 执行 -> 2已完成, 申请单 -> 4, Worklist -> 2 + StudyUID。 */
    @PostMapping("/complete-exam/{executionId}")
    public R<HisExamExecution> completeExam(@PathVariable Long executionId,
                                            @RequestBody(required = false) RisExecutionDTO dto) {
        return R.ok(executionService.completeExam(executionId, dto));
    }

    /** DICOM Worklist 查询(供 MWL SCP 调用): 按设备 AE Title + 日期(yyyyMMdd/yyyy-MM-dd, 缺省当日)查待检查。 */
    @GetMapping("/worklist")
    public R<List<HisExamWorklist>> queryWorklist(@RequestParam(required = false) String aeTitle,
                                                  @RequestParam(required = false) String date) {
        return R.ok(worklistService.queryWorklist(aeTitle, date));
    }

    /* ================= Worklist 管理 ================= */

    /** 生成 Worklist: 从检查申请单生成 DICOM Worklist 条目(幂等, 已有未取消条目直接返回)。 */
    @PostMapping("/worklist/generate/{requestId}")
    public R<HisExamWorklist> generateWorklist(@PathVariable Long requestId) {
        return R.ok(worklistService.generateWorklist(requestId));
    }

    /** 回填 Study Instance UID(body={"studyUid":"..."})。 */
    @PutMapping("/worklist/{id}/study-uid")
    public R<HisExamWorklist> updateStudyUid(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        String uid = body == null || body.get("studyUid") == null ? null : body.get("studyUid").toString();
        return R.ok(worklistService.updateStudyUid(id, uid));
    }

    /** MPPS 状态回调(body={"mppsStatus":"IN PROGRESS|COMPLETED|DISCONTINUED"})。 */
    @PutMapping("/worklist/{id}/mpps")
    public R<HisExamWorklist> updateMpps(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String mpps = body == null || body.get("mppsStatus") == null ? null : body.get("mppsStatus").toString();
        return R.ok(worklistService.updateMppsStatus(id, mpps));
    }

    /* ================= 辅助 ================= */

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            throw new BizException(400, "参数格式非法: " + o);
        }
    }
}
