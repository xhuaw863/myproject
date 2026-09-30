package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.inpatient.HisBed;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.inpatient.HisBedMapper;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpDiagnosisService;
import com.yb.hi.service.inpatient.InpOrderService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 住院医生工作站: 我的在院患者列表(当前登录医生管床) + 患者概览(基本信息/诊断/医嘱统计/费用汇总)。
 * 机构隔离: 非牵头机构仅本机构(牵头机构全医共体); 医生身份取当前登录用户关联职工(LoginUser.staffId)。
 */
@RestController
@RequestMapping("/api/his/inp/doctor")
public class InpDoctorController {

    private final InpOrderService orderService;
    private final InpDiagnosisService diagnosisService;
    private final HisInpVisitMapper visitMapper;
    private final HisInpChargeDetailMapper chargeDetailMapper;
    private final HisPatientMapper patientMapper;
    private final HisBedMapper bedMapper;
    private final HisWardMapper wardMapper;
    private final HisStaffMapper staffMapper;
    private final OrgAccessGuard guard;

    public InpDoctorController(InpOrderService orderService, InpDiagnosisService diagnosisService,
                               HisInpVisitMapper visitMapper, HisInpChargeDetailMapper chargeDetailMapper,
                               HisPatientMapper patientMapper, HisBedMapper bedMapper, HisWardMapper wardMapper,
                               HisStaffMapper staffMapper, OrgAccessGuard guard) {
        this.orderService = orderService;
        this.diagnosisService = diagnosisService;
        this.visitMapper = visitMapper;
        this.chargeDetailMapper = chargeDetailMapper;
        this.patientMapper = patientMapper;
        this.bedMapper = bedMapper;
        this.wardMapper = wardMapper;
        this.staffMapper = staffMapper;
        this.guard = guard;
    }

    /**
     * 我的在院患者列表: 按当前登录医生(doctor_id=LoginUser.staffId)筛选在院就诊(visit_status=2),
     * 支持 wardId/keyword(住院号或患者姓名)筛选, 分页; 行内补齐患者/床位/病区冗余信息。
     */
    @GetMapping("/patients")
    public R<IPage<Map<String, Object>>> patients(
            @RequestParam(required = false) Long wardId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false, defaultValue = "mine") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        LoginUser lu = UserContext.get();
        Long myStaffId = lu == null ? null : lu.getStaffId();
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg == null && !guard.isLead()) {
            throw new BizException(403, "当前账号未归属任何机构, 无法查询在院患者");
        }

        /* 视图范围: mine=本人管床(默认); dept=本科室; all=本机构(医共体)全部在院患者。
         * dept/all 仅管理员角色(ADMIN/ORG_ADMIN/SUPER_ADMIN)可用, 普通医生强制回落 mine。 */
        boolean canBroaden = lu != null && lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.SUPER_ADMIN);
        String effScope = (canBroaden && ("dept".equals(scope) || "all".equals(scope))) ? scope : "mine";
        if ("mine".equals(effScope) && myStaffId == null) {
            // 未关联职工档案的账号看"本人管床"恒空(管理员可切 dept/all 查看他人管床)
            return R.ok(emptyPage(page, size));
        }

        List<Long> nameMatchedPatientIds = null;
        final String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (kw != null) {
            nameMatchedPatientIds = patientMapper.selectList(new LambdaQueryWrapper<HisPatient>()
                            .select(HisPatient::getId)
                            .like(HisPatient::getName, kw))
                    .stream().map(HisPatient::getId).distinct().collect(Collectors.toList());
        }
        final List<Long> matchedIds = nameMatchedPatientIds;
        LambdaQueryWrapper<HisInpVisit> w = new LambdaQueryWrapper<HisInpVisit>()
                .eq(HisInpVisit::getVisitStatus, 2)
                .eq(scopeOrg != null, HisInpVisit::getOrgId, scopeOrg)
                .eq(wardId != null, HisInpVisit::getWardId, wardId);
        if ("mine".equals(effScope)) {
            w.eq(HisInpVisit::getDoctorId, myStaffId);
        } else if ("dept".equals(effScope)) {
            Long d = deptId != null ? deptId : (lu == null ? null : lu.getDeptId());
            w.eq(d != null, HisInpVisit::getDeptId, d);
        }
        // effScope=all: 不加 doctor/dept 约束, 仅机构隔离 + 病区 + 关键字
        if (kw != null) {
            if (matchedIds == null || matchedIds.isEmpty()) {
                w.like(HisInpVisit::getInpNo, kw);
            } else {
                w.and(q -> q.like(HisInpVisit::getInpNo, kw)
                        .or().in(HisInpVisit::getPatientId, matchedIds));
            }
        }
        w.orderByDesc(HisInpVisit::getAdmitDate).orderByDesc(HisInpVisit::getId);
        Page<HisInpVisit> p = visitMapper.selectPage(new Page<>(page, size), w);

        List<HisInpVisit> records = p.getRecords();
        Map<Long, HisPatient> patients = loadPatients(records);
        Map<Long, HisBed> beds = loadBeds(records);
        Map<Long, HisWard> wards = loadWards(records);
        Map<Long, String> doctorNames = loadDoctorNames(records);
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (HisInpVisit v : records) {
            rows.add(toPatientRow(v,
                    v.getPatientId() == null ? null : patients.get(v.getPatientId()),
                    v.getBedId() == null ? null : beds.get(v.getBedId()),
                    v.getWardId() == null ? null : wards.get(v.getWardId()),
                    v.getDoctorId() == null ? null : doctorNames.get(v.getDoctorId())));
        }
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setPages(p.getPages());
        out.setRecords(rows);
        return R.ok(out);
    }

    /** 患者概览: 基本信息(就诊+患者+床位+病区) + 诊断列表(按类型分组) + 医嘱统计 + 费用汇总 */
    @GetMapping("/patient/{visitId}/summary")
    public R<Map<String, Object>> summary(@PathVariable Long visitId) {
        HisInpVisit visit = requireVisit(visitId);
        HisPatient patient = visit.getPatientId() == null ? null : patientMapper.selectById(visit.getPatientId());
        HisBed bed = visit.getBedId() == null ? null : bedMapper.selectById(visit.getBedId());
        HisWard ward = visit.getWardId() == null ? null : wardMapper.selectById(visit.getWardId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visit", visit);
        out.put("patient", patient);
        out.put("bed", bed);
        out.put("ward", ward);
        out.put("diagnoses", diagnosisService.listByVisit(visitId));
        out.put("orderStats", buildOrderStats(visitId));
        out.put("feeSummary", buildFeeSummary(visit, visitId));
        return R.ok(out);
    }

    /* ================= 内部实现 ================= */

    /** 在院患者行组装: 就诊字段平铺 + 患者/床位/病区冗余信息 */
    private Map<String, Object> toPatientRow(HisInpVisit v, HisPatient pt, HisBed bed, HisWard ward, String doctorName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", v.getId());
        row.put("inpNo", v.getInpNo());
        row.put("patientId", v.getPatientId());
        row.put("patientName", pt == null ? null : pt.getName());
        row.put("gender", pt == null ? null : pt.getGender());
        row.put("age", pt == null ? null : pt.getAge());
        row.put("phone", pt == null ? null : pt.getPhone());
        row.put("wardId", v.getWardId());
        row.put("wardName", ward == null ? null : ward.getWardName());
        row.put("bedId", v.getBedId());
        row.put("bedNo", bed == null ? null : bed.getBedNo());
        row.put("roomNo", bed == null ? null : bed.getRoomNo());
        row.put("deptId", v.getDeptId());
        row.put("doctorId", v.getDoctorId());
        row.put("doctorName", doctorName);
        row.put("nurseId", v.getNurseId());
        row.put("admitDate", v.getAdmitDate());
        row.put("admitDiag", v.getAdmitDiag());
        row.put("totalCost", v.getTotalCost());
        row.put("depositBalance", v.getDepositBalance());
        row.put("medType", v.getMedType());
        row.put("psnNo", v.getPsnNo());
        row.put("insutype", v.getInsutype());
        row.put("mdtrtId", v.getMdtrtId());
        row.put("visitStatus", v.getVisitStatus());
        return row;
    }

    /** 医嘱统计: 总数/长期/临时/在执行 + 按状态分组计数 + 成组数 */
    private Map<String, Object> buildOrderStats(Long visitId) {
        List<HisInpOrder> orders = orderService.lambdaQuery()
                .eq(HisInpOrder::getInpVisitId, visitId)
                .list();
        Map<Integer, Long> byStatus = orders.stream()
                .filter(o -> o.getOrderStatus() != null)
                .collect(Collectors.groupingBy(HisInpOrder::getOrderStatus, TreeMap::new, Collectors.counting()));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", orders.size());
        stats.put("longTerm", orders.stream()
                .filter(o -> Integer.valueOf(1).equals(o.getOrderType())).count());
        stats.put("temp", orders.stream()
                .filter(o -> Integer.valueOf(2).equals(o.getOrderType())).count());
        stats.put("active", orders.stream()
                .filter(o -> o.getOrderStatus() != null && o.getOrderStatus() >= 1 && o.getOrderStatus() <= 3).count());
        stats.put("byStatus", byStatus);
        stats.put("groupCount", orders.stream()
                .filter(o -> StringUtils.hasText(o.getGroupNo()))
                .map(HisInpOrder::getGroupNo).distinct().count());
        return stats;
    }

    /** 费用汇总: 就诊总费用/预交金余额 + 未退费用明细按费用类别汇总 */
    private Map<String, Object> buildFeeSummary(HisInpVisit visit, Long visitId) {
        List<HisInpChargeDetail> details = chargeDetailMapper.selectList(
                new LambdaQueryWrapper<HisInpChargeDetail>()
                        .eq(HisInpChargeDetail::getInpVisitId, visitId));
        List<HisInpChargeDetail> valid = details.stream()
                .filter(d -> d.getStatus() == null || d.getStatus() == 1)
                .collect(Collectors.toList());
        Map<Integer, BigDecimal> byFeeType = valid.stream()
                .filter(d -> d.getFeeType() != null)
                .collect(Collectors.groupingBy(HisInpChargeDetail::getFeeType, TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                d -> d.getAmount() == null ? BigDecimal.ZERO : d.getAmount(),
                                BigDecimal::add)));
        Map<String, Object> fee = new LinkedHashMap<>();
        fee.put("totalCost", visit.getTotalCost());
        fee.put("depositBalance", visit.getDepositBalance());
        fee.put("chargeDetailCount", valid.size());
        fee.put("refundedCount", details.size() - valid.size());
        fee.put("byFeeType", byFeeType);
        return fee;
    }

    private Map<Long, HisPatient> loadPatients(List<HisInpVisit> records) {
        List<Long> ids = distinctIds(records, HisInpVisit::getPatientId);
        return ids.isEmpty() ? Collections.emptyMap()
                : patientMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(HisPatient::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, HisBed> loadBeds(List<HisInpVisit> records) {
        List<Long> ids = distinctIds(records, HisInpVisit::getBedId);
        return ids.isEmpty() ? Collections.emptyMap()
                : bedMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(HisBed::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, HisWard> loadWards(List<HisInpVisit> records) {
        List<Long> ids = distinctIds(records, HisInpVisit::getWardId);
        return ids.isEmpty() ? Collections.emptyMap()
                : wardMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(HisWard::getId, Function.identity(), (a, b) -> a));
    }

    /** 主治医生ID→姓名映射(dept/all 视图行内展示管床医生; mine 视图亦无害) */
    private Map<Long, String> loadDoctorNames(List<HisInpVisit> records) {
        List<Long> ids = distinctIds(records, HisInpVisit::getDoctorId);
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return staffMapper.selectBatchIds(ids).stream()
                .filter(s -> s.getStaffName() != null)
                .collect(Collectors.toMap(HisStaff::getId, HisStaff::getStaffName, (a, b) -> a));
    }

    private List<Long> distinctIds(List<HisInpVisit> records, Function<HisInpVisit, Long> getter) {
        return records.stream().map(getter).filter(Objects::nonNull)
                .distinct().collect(Collectors.toList());
    }

    /** 就诊归属校验: 不存在报400; 非牵头机构仅可访问本机构就诊(牵头机构全医共体) */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问住院数据");
        }
        if (!scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        return v;
    }

    private IPage<Map<String, Object>> emptyPage(int page, int size) {
        Page<Map<String, Object>> p = new Page<>(page, size);
        p.setRecords(new ArrayList<>());
        p.setTotal(0);
        return p;
    }
}
