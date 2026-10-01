package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisRxReview;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisRxReviewMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院处方点评服务(T2 阶段3, 人工点评闭环 + 留痕)。
 * 流程: 待点评池(某就诊下尚未点评的药品医嘱) → 发起点评(冻结医嘱要素快照, status=1待点评) → 点评提交(结论/问题类型/评分/意见, status=2已点评)。
 * 点评单归属机构跟随就诊机构; 机构越权守卫沿用 {@link OrgAccessGuard}(与医嘱服务同口径)。
 * 说明: list/pool 走 MyBatis-Plus 租户插件自动过滤 tenant_id; pool 为手写 JdbcTemplate JOIN(显式带 tenant_id AND deleted=0)。
 */
@Slf4j
@Service
public class HisRxReviewService extends ServiceImpl<HisRxReviewMapper, HisRxReview> {

    private final JdbcTemplate jdbcTemplate;
    private final HisInpVisitMapper visitMapper;
    private final HisInpOrderMapper orderMapper;
    private final OrgAccessGuard guard;
    private final RxReviewRuleEngine ruleEngine;

    public HisRxReviewService(JdbcTemplate jdbcTemplate, HisInpVisitMapper visitMapper,
                              HisInpOrderMapper orderMapper, OrgAccessGuard guard, RxReviewRuleEngine ruleEngine) {
        this.jdbcTemplate = jdbcTemplate;
        this.visitMapper = visitMapper;
        this.orderMapper = orderMapper;
        this.guard = guard;
        this.ruleEngine = ruleEngine;
    }

    /** 当前登录职工ID(点评人; 无职工档案则拒绝)。 */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行处方点评");
        }
        return lu.getStaffId();
    }

    /** 点评单分页列表(inpVisitId 可选: 按就诊过滤; status 可选: 1待点评 2已点评)。 */
    public IPage<HisRxReview> listReviews(Long inpVisitId, Integer status, long page, long size) {
        return lambdaQuery()
                .eq(inpVisitId != null, HisRxReview::getInpVisitId, inpVisitId)
                .eq(status != null, HisRxReview::getStatus, status)
                .orderByDesc(HisRxReview::getCreateTime)
                .orderByDesc(HisRxReview::getId)
                .page(new Page<>(page, size));
    }

    /**
     * 待点评池: 指定就诊下"药品类医嘱(order_category=1)"且尚未发起过点评的候选(左连 rx_review 排除已点评的 order_id)。
     * 手写 JdbcTemplate(不走租户插件), 显式带 tenant_id AND deleted=0; 归属机构守卫: 校验就诊在登录机构范围内。
     */
    public List<Map<String, Object>> pool(Long inpVisitId) {
        if (inpVisitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        requireVisit(inpVisitId);
        String sql = "SELECT o.id AS orderId, o.inp_visit_id AS inpVisitId, o.order_content AS orderContent,"
                + " o.drug_id AS drugId, d.generic_name AS drugName, o.spec AS spec, o.dosage AS dosage,"
                + " o.dosage_unit AS dosageUnit, o.usage_code AS usageCode, o.freq_code AS freqCode,"
                + " o.order_type AS orderType, o.doctor_id AS doctorId, s.staff_name AS doctorName,"
                + " d.abx_grade AS abxGrade, d.abx_grade_name AS abxGradeName,"
                + " DATE_FORMAT(o.start_time, '%Y-%m-%d %H:%i') AS startTime"
                + " FROM his_inp_order o"
                + " JOIN his_inp_visit v ON v.id = o.inp_visit_id AND v.deleted = 0"
                + " LEFT JOIN his_drug_catalog d ON d.id = o.drug_id AND d.deleted = 0"
                + " LEFT JOIN his_staff s ON s.id = o.doctor_id AND s.deleted = 0"
                + " LEFT JOIN his_rx_review r ON r.order_id = o.id AND r.deleted = 0 AND r.tenant_id = ?"
                + " WHERE o.deleted = 0 AND o.tenant_id = ? AND o.inp_visit_id = ? AND o.order_category = 1"
                + " AND r.id IS NULL"
                + " ORDER BY o.start_time DESC, o.id DESC LIMIT 200";
        long tid = tenantId();
        return jdbcTemplate.queryForList(sql, tid, tid, inpVisitId);
    }

    /**
     * 发起点评: 从医嘱创建点评单(status=1待点评), 冻结医嘱核心要素快照; 同一医嘱不可重复发起。
     * 机构守卫: 校验医嘱所属就诊在登录机构范围内; 点评单归属机构跟随就诊机构。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRxReview initiate(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        HisInpOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(400, "医嘱不存在");
        }
        if (order.getOrderCategory() == null || order.getOrderCategory() != 1) {
            throw new BizException("仅药品类医嘱可发起处方点评");
        }
        HisInpVisit visit = requireVisit(order.getInpVisitId());
        // 防重复发起
        Long existed = lambdaQuery()
                .eq(HisRxReview::getOrderId, orderId)
                .count();
        if (existed != null && existed > 0) {
            throw new BizException("该医嘱已发起过点评, 请勿重复操作");
        }
        HisRxReview r = new HisRxReview();
        r.setInpVisitId(order.getInpVisitId());
        r.setPatientId(visit.getPatientId());
        r.setOrderId(orderId);
        r.setDoctorId(order.getDoctorId());
        r.setOrgId(visit.getOrgId());
        r.setRxItemSnapshot(JSON.toJSONString(snapshot(order)));
        r.setStatus(1);
        // 阶段5-2: 发起时跑规则引擎自动预打分(建议, 供人工点评参考; 引擎只读不落库, 结果冻结到 auto_* 列)
        try {
            Map<String, Object> sug = ruleEngine.evaluate(order);
            r.setAutoFindings(JSON.toJSONString(sug.get("findings")));
            r.setAutoResult((Integer) sug.get("result"));
            r.setAutoScore((Integer) sug.get("score"));
            r.setAutoProblemType((String) sug.get("problemType"));
            r.setAutoEvaluated(1);
        } catch (Exception e) {
            log.warn("处方点评规则引擎预打分失败(不阻断发起): orderId={}, err={}", orderId, e.toString());
            r.setAutoEvaluated(0);
        }
        save(r);
        return r;
    }

    /**
     * 点评提交: 填写结论(1合理/2不规范/3不合理)/问题类型/评分/意见, 记录点评人与时间, status→2已点评。
     * result=1(合理)时问题类型可不填; result=2/3 时问题类型必填(闭环留痕要求)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisRxReview submitReview(Long id, Integer result, String problemType, Integer score, String comment) {
        if (id == null) {
            throw new BizException(400, "点评单ID不能为空");
        }
        if (result == null || result < 1 || result > 3) {
            throw new BizException(400, "点评结论必须为1合理/2不规范/3不合理");
        }
        if ((result == 2 || result == 3) && (problemType == null || problemType.trim().isEmpty())) {
            throw new BizException(400, "不规范/不合理结论必须填写问题类型");
        }
        if (score != null && (score < 0 || score > 100)) {
            throw new BizException(400, "评分须在0-100之间");
        }
        HisRxReview r = getById(id);
        if (r == null) {
            throw new BizException(400, "点评单不存在");
        }
        requireVisit(r.getInpVisitId());
        r.setResult(result);
        r.setProblemType(problemType == null ? null : problemType.trim());
        r.setScore(score);
        r.setComment(comment == null ? null : comment.trim());
        r.setReviewStaffId(currentStaffId());
        r.setReviewTime(java.time.LocalDateTime.now());
        r.setStatus(2);
        updateById(r);
        return r;
    }

    /** 删除点评单(逻辑删除; 仅本机构就诊范围内的可删)。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeReview(Long id) {
        HisRxReview r = getById(id);
        if (r == null) {
            return;
        }
        requireVisit(r.getInpVisitId());
        removeById(id);
    }

    /** 医嘱要素快照(冻结点评依据, 展示时反序列化)。 */
    private Map<String, Object> snapshot(HisInpOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderContent", o.getOrderContent());
        m.put("drugId", o.getDrugId());
        m.put("spec", o.getSpec());
        m.put("dosage", o.getDosage());
        m.put("dosageUnit", o.getDosageUnit());
        m.put("usageCode", o.getUsageCode());
        m.put("freqCode", o.getFreqCode());
        m.put("orderType", o.getOrderType());
        m.put("doctorId", o.getDoctorId());
        m.put("startTime", o.getStartTime() == null ? null : o.getStartTime().toString());
        return m;
    }

    /** 就诊存在性 + 机构范围守卫(镜像 InpOrderService.requireVisit)。 */
    private HisInpVisit requireVisit(Long visitId) {
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问处方点评");
        }
        if (!scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的处方点评数据");
        }
        return v;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 供控制器: 当前登录医生职工ID(缺省点评人过滤)。 */
    public static Long currentStaffIdStatic() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }
}
