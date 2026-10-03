package com.yb.hi.controller.emr;

import com.alibaba.fastjson.JSON;
import com.yb.hi.entity.emr.HisEmrQcDefect;
import com.yb.hi.entity.emr.HisEmrQcNotice;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.emr.MrManualQcService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 病历人工质控接口(P5b-2): 抽检/分配/审核/缺陷管理/整改通知单闭环/申诉/进度统计。
 *
 * <p>权限: 病历/通知单归属机构须与当前登录机构一致(平台超管放行), 非法状态转换返回 400 中文错误。
 * 通知单编号 QC-{yyyyMMdd}-{序号}; SSE 推送 EMR_QC_NOTICE(下发/重新下发) 与
 * EMR_QC_APPEAL_RESULT(申诉处理结果) 至科室。</p>
 */
@RestController
@RequestMapping("/api/his/emr/manual-qc")
public class MrManualQcController {

    private final MrManualQcService manualQcService;

    public MrManualQcController(MrManualQcService manualQcService) {
        this.manualQcService = manualQcService;
    }

    /* ===================== 抽检与分配 ===================== */

    /** 按科室/月份/比例随机抽检归档病历(status 2已提交/3已审核), 返回抽取清单 */
    @PostMapping("/sample")
    public R<List<Map<String, Object>>> sample(@RequestParam Long deptId,
                                               @RequestParam String month,
                                               @RequestParam(required = false, defaultValue = "10") Integer sampleRate) {
        return R.ok(manualQcService.randomSample(deptId, month, sampleRate == null ? 10 : sampleRate));
    }

    /** 分配质控任务给指定质控员(body: {recordIds: [], qcUserId}) */
    @PostMapping("/assign")
    public R<Void> assign(@RequestBody Map<String, Object> body) {
        List<Long> recordIds = toLongList(body == null ? null : body.get("recordIds"));
        Long qcUserId = toLong(body == null ? null : body.get("qcUserId"));
        manualQcService.assignToQc(recordIds, qcUserId);
        return R.ok();
    }

    /* ===================== 审核 ===================== */

    /** 开始审核: 自动预评分(归档级内涵质控)+缺陷列表+预评级; qcUserId 空=当前登录人 */
    @PostMapping("/review/start/{recordId}")
    public R<Map<String, Object>> startReview(@PathVariable Long recordId,
                                              @RequestParam(required = false) Long qcUserId) {
        if (qcUserId == null) {
            LoginUser cur = UserContext.get();
            qcUserId = cur == null ? null : cur.getUserId();
        }
        return R.ok(manualQcService.startReview(recordId, qcUserId));
    }

    /** 完成审核: 汇总扣分+甲乙丙终评(grade=甲/乙/丙), 回写 quality_score/quality_detail */
    @PostMapping("/review/finish/{recordId}")
    public R<Map<String, Object>> finishReview(@PathVariable Long recordId,
                                               @RequestParam String grade) {
        return R.ok(manualQcService.finishReview(recordId, grade));
    }

    /* ===================== 缺陷管理 ===================== */

    /** 新增人工缺陷(auto_generated=0, 归档质控, body 须含 recordId) */
    @PostMapping("/defect")
    public R<HisEmrQcDefect> addDefect(@RequestBody HisEmrQcDefect defect) {
        if (defect == null || defect.getRecordId() == null) {
            throw new BizException(400, "recordId 不能为空");
        }
        return R.ok(manualQcService.addDefect(defect.getRecordId(), defect));
    }

    /** 编辑缺陷(白名单: defectType/defectDesc/deductScore/severity/ruleName) */
    @PutMapping("/defect/{id}")
    public R<Void> editDefect(@PathVariable Long id, @RequestBody HisEmrQcDefect dto) {
        manualQcService.editDefect(id, dto);
        return R.ok();
    }

    /** 删除缺陷(逻辑删除) */
    @DeleteMapping("/defect/{id}")
    public R<Void> deleteDefect(@PathVariable Long id) {
        manualQcService.deleteDefect(id);
        return R.ok();
    }

    /** 激活自动缺陷(质控员确认保留: 重置为未整改并纳入归档质控) */
    @PutMapping("/defect/{id}/activate")
    public R<Void> activateDefect(@PathVariable Long id) {
        manualQcService.activateAutoDefect(id);
        return R.ok();
    }

    /** 临床医生整改缺陷(status→1已整改) */
    @PutMapping("/defect/{id}/rectify")
    public R<Void> rectifyDefect(@PathVariable Long id,
                                 @RequestParam(required = false) String rectifyNote) {
        manualQcService.rectifyDefect(id, rectifyNote);
        return R.ok();
    }

    /* ===================== 通知单闭环 ===================== */

    /** 下发整改通知单(编号 QC-{yyyyMMdd}-{序号}; SSE 推送 EMR_QC_NOTICE 至科室) */
    @PostMapping("/notice")
    public R<HisEmrQcNotice> issueNotice(@RequestBody HisEmrQcNotice notice) {
        return R.ok(manualQcService.issueNotice(notice));
    }

    /** 整改通知单列表(机构范围隔离: 牵头/超管全院, 成员机构仅本机构科室) */
    @GetMapping("/notices")
    public R<List<HisEmrQcNotice>> notices(@RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) String startDate,
                                           @RequestParam(required = false) String endDate) {
        return R.ok(manualQcService.listNotices(deptId, status, startDate, endDate));
    }

    /** 提交整改(通知单级, status→3已整改) */
    @PutMapping("/notice/{id}/submit-rectify")
    public R<Void> submitRectify(@PathVariable Long id) {
        manualQcService.submitRectify(id);
        return R.ok();
    }

    /** 质控员复核整改(passed=true 通过→已关闭; false 驳回→退回整改中) */
    @PutMapping("/notice/{id}/review-rectify")
    public R<Void> reviewRectify(@PathVariable Long id,
                                 @RequestParam(required = false, defaultValue = "true") Boolean passed,
                                 @RequestParam(required = false) String comment) {
        manualQcService.reviewRectify(id, passed == null || passed, comment);
        return R.ok();
    }

    /** 申诉(status→6申诉中, reason 必填) */
    @PostMapping("/notice/{id}/appeal")
    public R<Void> appeal(@PathVariable Long id, @RequestParam String reason) {
        manualQcService.appeal(id, reason);
        return R.ok();
    }

    /** 处理申诉(accepted=true 受理→关闭; false 驳回→重新下发; SSE 推送 EMR_QC_APPEAL_RESULT) */
    @PutMapping("/notice/{id}/handle-appeal")
    public R<Void> handleAppeal(@PathVariable Long id,
                                @RequestParam(required = false, defaultValue = "false") Boolean accepted,
                                @RequestParam(required = false) String comment) {
        manualQcService.handleAppeal(id, accepted != null && accepted, comment);
        return R.ok();
    }

    /* ===================== 进度统计 ===================== */

    /** 质控进度统计(month yyyy-MM, 空取当月): 应检/已抽/已审/待审/整改中/已完成 */
    @GetMapping("/progress")
    public R<Map<String, Object>> progress(@RequestParam(required = false) String month) {
        return R.ok(manualQcService.qcProgress(month));
    }

    /* ===================== 工具 ===================== */

    /** body 值 → Long(兼容数字与字符串雪花ID) */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new BizException(400, "无效的ID: " + s);
        }
    }

    /** recordIds 解析: JSON 数组(数字/字符串雪花ID)或逗号串均可 */
    private static List<Long> toLongList(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null) {
            return ids;
        }
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                Long v = toLong(o);
                if (v != null) {
                    ids.add(v);
                }
            }
            return ids;
        }
        String s = String.valueOf(raw).trim();
        if (s.startsWith("[")) {
            try {
                for (Object o : JSON.parseArray(s)) {
                    Long v = toLong(o);
                    if (v != null) {
                        ids.add(v);
                    }
                }
                return ids;
            } catch (Exception e) {
                throw new BizException(400, "recordIds 格式非法, 应为数组或逗号分隔的ID");
            }
        }
        if (!s.isEmpty()) {
            for (String part : s.split(",")) {
                Long v = toLong(part);
                if (v != null) {
                    ids.add(v);
                }
            }
        }
        return ids;
    }
}
