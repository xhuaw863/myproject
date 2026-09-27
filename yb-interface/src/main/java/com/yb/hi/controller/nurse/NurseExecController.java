package com.yb.hi.controller.nurse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.nurse.HisNurseExec;
import com.yb.hi.entity.nurse.HisPatientAllergy;
import com.yb.hi.entity.nurse.HisSkinTest;
import com.yb.hi.entity.nurse.HisInfusionRecord;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.nurse.InfusionService;
import com.yb.hi.service.nurse.NurseExecService;
import com.yb.hi.service.nurse.SkinTestService;
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
 * 门诊护士站接口(待执行医嘱/注射/输液/皮试/过敏档案/执行记录):
 * 读按当前登录机构隔离(org_id), 操作人取登录用户关联职工(staffId, 可为空不阻断);
 * 状态流转全部为乐观锁 UPDATE ... WHERE exec_status=前值, 冲突返回业务错误提示刷新;
 * 日常护理业务不限角色(登录即可), 医生/护士菜单授权由 RBAC 控制(nurse-* 五菜单)。
 */
@RestController
@RequestMapping("/api/nurse")
public class NurseExecController {

    private final NurseExecService nurseExecService;
    private final SkinTestService skinTestService;
    private final InfusionService infusionService;

    public NurseExecController(NurseExecService nurseExecService, SkinTestService skinTestService,
                               InfusionService infusionService) {
        this.nurseExecService = nurseExecService;
        this.skinTestService = skinTestService;
        this.infusionService = infusionService;
    }

    /* ================= 待执行医嘱(工作台) ================= */

    /** 待执行/执行中医嘱列表: 已缴费的注射/输液/皮试/换药执行单(急诊优先, 30秒轮询); 查询侧幂等兜底补建;
     *  execStatus 缺省=0待执行, 传1查执行中(注射/换药类的完成/取消闭环)。 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending(@RequestParam(required = false) Long deptId,
                                                @RequestParam(required = false) String execType,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer execStatus) {
        return R.ok(nurseExecService.listOrders(currentOrgId(), deptId, execType, keyword, execStatus));
    }

    /** 为医嘱单生成执行记录(收费完成联动入口, 幂等; 工作台查询时已自动兜底补建) */
    @PostMapping("/exec-records")
    public R<List<HisNurseExec>> createExecRecords(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body == null ? null : body.get("orderId"));
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        return R.ok(nurseExecService.createExecRecords(orderId));
    }

    /** 开始执行(三查七对确认后, 乐观锁 0->1) */
    @PostMapping("/exec/{id}/start")
    public R<Map<String, Object>> startExec(@PathVariable Long id) {
        return R.ok(nurseExecService.startExec(id, currentStaffId()));
    }

    /** 完成执行(乐观锁 1->2, 记录患者反应; 同医嘱单全完成回写 his_order.exec_status=2) */
    @PostMapping("/exec/{id}/finish")
    public R<Map<String, Object>> finishExec(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String response = body == null || body.get("response") == null ? null : body.get("response").toString();
        return R.ok(nurseExecService.finishExec(id, currentStaffId(), response));
    }

    /** 取消执行(乐观锁 0/1 -> -1, 必填原因) */
    @PostMapping("/exec/{id}/cancel")
    public R<Map<String, Object>> cancelExec(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? null : body.get("reason").toString();
        return R.ok(nurseExecService.cancelExec(id, reason));
    }

    /* ================= 患者过敏档案 ================= */

    /** 患者有效过敏记录(执行前核对/过敏档案) */
    @GetMapping("/allergy/{patientId}")
    public R<List<HisPatientAllergy>> patientAllergies(@PathVariable Long patientId) {
        return R.ok(nurseExecService.getPatientAllergies(patientId));
    }

    /** 手工登记过敏记录(默认来源 manual; 同患者同过敏原防重复) */
    @PostMapping("/allergy")
    public R<HisPatientAllergy> addAllergy(@RequestBody HisPatientAllergy allergy) {
        return R.ok(nurseExecService.addAllergy(allergy));
    }

    /** 过敏档案检索(患者姓名/患者ID/过敏原名称关键字, 分页含患者与登记人姓名) */
    @GetMapping("/allergies")
    public R<IPage<Map<String, Object>>> listAllergies(@RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(nurseExecService.listAllergies(keyword, page, size));
    }

    /* ================= 执行记录查询 ================= */

    /** 执行记录分页(执行类型/护士/日期区间; 行含创建->开始->完成时间线与操作人, 供展开详情) */
    @GetMapping("/exec-log")
    public R<IPage<Map<String, Object>>> execLog(@RequestParam(required = false) String execType,
                                                 @RequestParam(required = false) Long nurseId,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        return R.ok(nurseExecService.listExecLog(currentOrgId(), execType, nurseId, startDate, endDate, page, size));
    }

    /* ================= 皮试 ================= */

    /** 开始皮试(创建皮试记录, 观察窗20分钟; 联动执行单开始) */
    @PostMapping("/skin-test")
    public R<HisSkinTest> createSkinTest(@RequestBody Map<String, Object> body) {
        Long execId = toLong(body == null ? null : body.get("execId"));
        Long drugId = toLong(body == null ? null : body.get("drugId"));
        String drugName = str(body == null ? null : body.get("drugName"));
        String testDose = str(body == null ? null : body.get("testDose"));
        return R.ok(skinTestService.createSkinTest(execId, drugId, drugName, testDose));
    }

    /** 录入皮试结果(乐观锁; 阳性必填描述, 自动写入过敏档案并通知医生; 联动执行单完成) */
    @PostMapping("/skin-test/{id}/result")
    public R<Map<String, Object>> recordSkinTestResult(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer result = toInt(body == null ? null : body.get("result"));
        String resultDesc = str(body == null ? null : body.get("resultDesc"));
        return R.ok(skinTestService.recordResult(id, result, resultDesc));
    }

    /** 皮试分页(三页签: resultStatus 0待皮试/1观察中/2已完成; 观察中含剩余秒数供倒计时) */
    @GetMapping("/skin-tests")
    public R<IPage<Map<String, Object>>> skinTests(@RequestParam(required = false) Integer resultStatus,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        return R.ok(skinTestService.listSkinTests(currentOrgId(), resultStatus, page, size));
    }

    /* ================= 输液 ================= */

    /** 配液(创建输液记录: 座位/溶液/滴速; 联动执行单开始) */
    @PostMapping("/infusion")
    public R<HisInfusionRecord> createInfusion(@RequestBody Map<String, Object> body) {
        Long execId = toLong(body == null ? null : body.get("execId"));
        String seatNo = str(body == null ? null : body.get("seatNo"));
        String solution = str(body == null ? null : body.get("solution"));
        Integer dripRate = toInt(body == null ? null : body.get("dripRate"));
        return R.ok(infusionService.createInfusion(execId, seatNo, solution, dripRate));
    }

    /** 穿刺记录(乐观锁: 首次穿刺生效) */
    @PostMapping("/infusion/{id}/puncture")
    public R<HisInfusionRecord> recordPuncture(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String site = str(body == null ? null : body.get("site"));
        return R.ok(infusionService.recordPuncture(id, site, currentStaffId()));
    }

    /** 追加巡视记录(patrolJson: {dripRate,status,note}, 服务端补时间追加JSON数组) */
    @PostMapping("/infusion/{id}/patrol")
    public R<Map<String, Object>> addPatrol(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String patrolJson = str(body == null ? null : body.get("patrolJson"));
        return R.ok(infusionService.addPatrol(id, patrolJson));
    }

    /** 拔针(乐观锁; 联动执行单完成, 同医嘱单全完成回写 his_order.exec_status=2) */
    @PostMapping("/infusion/{id}/remove")
    public R<Map<String, Object>> recordRemove(@PathVariable Long id) {
        return R.ok(infusionService.recordRemove(id, currentStaffId()));
    }

    /** 输液分页(四页签: stage prepare待配液/infusing输液中/ready_remove待拔针/done已完成) */
    @GetMapping("/infusions")
    public R<IPage<Map<String, Object>>> infusions(@RequestParam(required = false) String stage,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        return R.ok(infusionService.listInfusions(currentOrgId(), stage, page, size));
    }

    /* ================= 辅助 ================= */

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站业务");
        }
        return u.getOrgId();
    }

    private Long currentStaffId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getStaffId();
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
