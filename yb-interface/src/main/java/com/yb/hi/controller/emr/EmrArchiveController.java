package com.yb.hi.controller.emr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrArchiveService;
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
 * 病历归档流程接口(病历P7a-2): 归档/批量归档/召回申请/召回审批/封存/解封、
 * 归档列表/逾期未归档清单/归档统计/单条详情。
 *
 * 状态机口径(1草稿/2已提交/3已审核/4已归档/5召回中/6已封存)由 EmrArchiveService 承担,
 * 全部乐观更新(WHERE status=旧值)防并发越权流转; 鉴权走 AuthInterceptor 统一 JWT,
 * 写操作在 Service 层做机构归属校验(requireSameOrg), 读操作按机构范围隔离(readScopeOrg);
 * JdbcTemplate 联查 SQL 内显式携带 tenant_id 条件。
 */
@RestController
@RequestMapping("/api/emr/archive")
public class EmrArchiveController {

    private final EmrArchiveService archiveService;

    public EmrArchiveController(EmrArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    /* ==================== 状态机操作 ==================== */

    /**
     * 提交归档: POST /api/emr/archive/submit/{recordId}
     * 3(已审核)→4(已归档); 须已出院。副作用: 版本快照/异步PDF/归档级质控/SSE通知(均best-effort)。
     */
    @PostMapping("/submit/{recordId}")
    public R<HisInpMedicalRecord> submit(@PathVariable Long recordId) {
        return R.ok(archiveService.submitForArchive(recordId));
    }

    /**
     * 批量归档: POST /api/emr/archive/batch-submit
     * Body 为病历ID数组(雪花ID字符串数组亦可, Jackson 自动绑定); 逐条归档, 单条失败不阻断,
     * 返回本次成功归档条数。
     */
    @PostMapping("/batch-submit")
    public R<Integer> batchSubmit(@RequestBody List<Long> recordIds) {
        return R.ok(archiveService.batchArchive(recordIds));
    }

    /**
     * 归档催促(P7a-5 联调补齐): POST /api/emr/archive/urge/{recordId}
     * 向记录医生 SSE 推送归档提醒(QC_REMINDER 通道), 未归档(status&lt;4)均可催; 审计留痕。
     */
    @PostMapping("/urge/{recordId}")
    public R<Void> urge(@PathVariable Long recordId) {
        archiveService.urge(recordId);
        return R.ok();
    }

    /**
     * 召回申请: POST /api/emr/archive/recall/{recordId}?reason=
     * 4(已归档)→5(召回中), recall_approved=0 待审批; 原因必填。SSE通知病案科。
     */
    @PostMapping("/recall/{recordId}")
    public R<HisInpMedicalRecord> recall(@PathVariable Long recordId,
                                         @RequestParam String reason) {
        return R.ok(archiveService.requestRecall(recordId, reason));
    }

    /**
     * 审批召回: PUT /api/emr/archive/recall/{recordId}/approve?approved=true|false
     * 仅 5(召回中) 且 recall_approved=0 可审; true→3(退回返修), false→4(驳回维持归档);
     * SSE通知召回申请人。
     */
    @PutMapping("/recall/{recordId}/approve")
    public R<HisInpMedicalRecord> approveRecall(@PathVariable Long recordId,
                                                @RequestParam boolean approved) {
        return R.ok(archiveService.approveRecall(recordId, approved));
    }

    /**
     * 封存: POST /api/emr/archive/seal/{recordId}?reason=
     * 4(已归档)→6(已封存), 原因必填(医疗纠纷争议固定证据)。事件 RECORD_SEALED。
     */
    @PostMapping("/seal/{recordId}")
    public R<HisInpMedicalRecord> seal(@PathVariable Long recordId,
                                       @RequestParam String reason) {
        return R.ok(archiveService.seal(recordId, reason));
    }

    /**
     * 解封: POST /api/emr/archive/unseal/{recordId}
     * 6(已封存)→4(已归档), 清空封存时间/操作人/原因。事件 RECORD_UNSEALED。
     */
    @PostMapping("/unseal/{recordId}")
    public R<HisInpMedicalRecord> unseal(@PathVariable Long recordId) {
        return R.ok(archiveService.unseal(recordId));
    }

    /* ==================== 查询与统计 ==================== */

    /**
     * 归档列表(多条件分页): GET /api/emr/archive/list?deptId=&status=&doctorName=&startDate=&endDate=&page=&size=
     * status/医生姓名(模糊)/科室/归档时间区间 可选; 归档时间倒序; 机构范围由登录身份收口。
     */
    @GetMapping("/list")
    public R<IPage<HisInpMedicalRecord>> list(@RequestParam(required = false) Long deptId,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String doctorName,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return R.ok(archiveService.list(deptId, status, doctorName, startDate, endDate, page, size));
    }

    /**
     * 逾期未归档清单: GET /api/emr/archive/overdue?deptId=
     * 出院超 3 天且 status&lt;4; 返回 recordId/patientName/deptName/doctorName/dischargeTime/overdueDays 等;
     * 出院时间升序(逾期最久在前)。
     */
    @GetMapping("/overdue")
    public R<List<Map<String, Object>>> overdue(@RequestParam(required = false) Long deptId) {
        return R.ok(archiveService.overdueList(deptId));
    }

    /**
     * 归档统计: GET /api/emr/archive/stats?deptId=&startDate=&endDate=
     * 返回 totalArchived/submissionRate72h/returnRate/monthlyArchived/overdueCount/deptProgress/monthlyTrend。
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) String startDate,
                                        @RequestParam(required = false) String endDate) {
        return R.ok(archiveService.archiveStats(deptId, startDate, endDate));
    }

    /**
     * 单条详情: GET /api/emr/archive/{recordId}
     * 存在 + 机构归属校验(不通过抛 403/404)。
     */
    @GetMapping("/{recordId}")
    public R<HisInpMedicalRecord> detail(@PathVariable Long recordId) {
        return R.ok(archiveService.detail(recordId));
    }
}
