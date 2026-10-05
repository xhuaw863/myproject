package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.ris.RisRequestDTO;
import com.yb.hi.dto.ris.RisRequestQueryDTO;
import com.yb.hi.entity.ris.HisExamRequest;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.ris.HisExamRequestMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 检查申请单服务(门诊/住院/急诊/体检四源统一, 医保 4501 检查信息字段在申请侧落列)。
 * 口径:
 * 1) 单号: JC + yyyyMMdd + 4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号;
 * 2) 状态机: 0待预约 -> 1已预约 -> 2已登记 -> 3检查中 -> 4已完成 -> 5已报告 -> 6已审核(顺序前进, 乐观锁),
 *    0/1 可取消(->7, 已预约取消联动释放排程占位);
 * 3) 从医嘱开单: 门诊 his_order(order_type='检查'字符串) + his_order_item 取检查要素,
 *    住院 his_inp_order(order_category=2整型) 单表取要素; 医保检查项目代码取收费目录医保对照
 *    (his_charge_item.med_list_codg), 院内检查项目代码取 item_code;
 * 4) 医保三要素: mdtrt_id/psn_no 自就诊回填, mdtrt_sn 沿用追溯码口径(医保时=mdtrt_id, 自费时=院内流水 OP/IP+visitId);
 * 5) 机构隔离: 写取当前登录机构(科室归属机构优先), 查询按机构过滤(经 OrgAccessGuard)。
 */
@Slf4j
@Service
public class RisRequestService extends ServiceImpl<HisExamRequestMapper, HisExamRequest> {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 申请单号前缀 */
    private static final String REQ_PREFIX = "JC";
    /** 状态: 0待预约/1已预约/2已登记/3检查中/4已完成/5已报告/6已审核/7已取消 */
    private static final int ST_PENDING = 0;
    private static final int ST_BOOKED = 1;
    private static final int ST_CANCELLED = 7;

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;
    private final RisScheduleService scheduleService;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public RisRequestService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard, RisScheduleService scheduleService) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
        this.scheduleService = scheduleService;
    }

    /* ================= 创建 ================= */

    /**
     * 手工创建申请单(体检/急诊等无医嘱来源, 或医生站直开):
     * 申请医生缺省取当前登录职工, 医保三要素缺省从就诊/患者回填。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamRequest create(RisRequestDTO dto) {
        if (dto == null) {
            throw new BizException(400, "申请单参数不能为空");
        }
        if (dto.getSourceType() == null) {
            throw new BizException(400, "申请来源不能为空(1门诊/2住院/3急诊/4体检)");
        }
        if (!StringUtils.hasText(dto.getExamType())) {
            throw new BizException(400, "检查类型不能为空(XRAY/CT/MRI/US/DSA/ENDO)");
        }
        if (dto.getPatientId() == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        HisExamRequest r = new HisExamRequest();
        r.setOrgId(guard.currentOrgId());
        r.setRequestNo(nextRequestNo());
        r.setSourceType(dto.getSourceType());
        r.setOrderId(dto.getOrderId());
        r.setInpOrderId(dto.getInpOrderId());
        r.setPatientId(dto.getPatientId());
        r.setVisitId(dto.getVisitId());
        r.setInpVisitId(dto.getInpVisitId());
        // 医保三要素: 入参优先, 缺省从就诊回填
        fillInsuranceInfo(r, dto.getMdtrtSn(), dto.getMdtrtId(), dto.getPsnNo());
        r.setChargeItemId(dto.getChargeItemId());
        r.setChargeItemCode(dto.getChargeItemCode());
        r.setChargeItemName(dto.getChargeItemName());
        r.setExamItemCode(dto.getExamItemCode());
        r.setExamItemName(dto.getExamItemName());
        r.setInhospExamItemCode(dto.getInhospExamItemCode());
        r.setInhospExamItemName(dto.getInhospExamItemName());
        r.setExamType(dto.getExamType().trim());
        r.setExamTypeCode(dto.getExamTypeCode());
        r.setExamTypeName(dto.getExamTypeName());
        r.setImgExamType(dto.getImgExamType());
        r.setModality(dto.getModality());
        r.setBodyPart(dto.getBodyPart());
        r.setBodyPartCode(dto.getBodyPartCode());
        r.setSiteCount(dto.getSiteCount() == null || dto.getSiteCount() < 1 ? 1 : dto.getSiteCount());
        r.setContrastMode(dto.getContrastMode());
        r.setClinicalDiagnosis(dto.getClinicalDiagnosis());
        r.setExamPurpose(dto.getExamPurpose());
        r.setClinicalHistory(dto.getClinicalHistory());
        r.setIsUrgent(dto.getIsUrgent() == null ? 0 : dto.getIsUrgent());
        r.setIsIsolation(dto.getIsIsolation() == null ? 0 : dto.getIsIsolation());
        r.setAllergyInfo(dto.getAllergyInfo());
        r.setPregnantFlag(dto.getPregnantFlag() == null ? 0 : dto.getPregnantFlag());
        // 申请医生: 入参优先, 缺省当前登录职工
        if (dto.getApplyDoctorId() != null) {
            r.setApplyDoctorId(dto.getApplyDoctorId());
            r.setApplyDoctorCode(dto.getApplyDoctorCode());
            r.setApplyDoctorName(dto.getApplyDoctorName());
        } else {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                r.setApplyDoctorId(lu.getStaffId());
                r.setApplyDoctorName(lu.getRealName());
            }
        }
        r.setApplyDeptId(dto.getApplyDeptId());
        r.setApplyDeptCode(dto.getApplyDeptCode());
        r.setApplyDeptName(dto.getApplyDeptName());
        r.setTargetDeptId(dto.getTargetDeptId());
        r.setTargetDeptCode(dto.getTargetDeptCode());
        r.setTargetDeptName(dto.getTargetDeptName());
        r.setPriority(dto.getPriority() == null ? 5 : dto.getPriority());
        r.setNotes(dto.getNotes());
        r.setApplyTime(LocalDateTime.now());
        r.setStatus(ST_PENDING);
        r.setPaidFlag(0);
        r.setYbUploadStatus(0);
        r.setValiFlag("1");
        save(r);
        log.info("检查申请单创建: requestNo={}, sourceType={}, patientId={}, examType={}",
                r.getRequestNo(), r.getSourceType(), r.getPatientId(), r.getExamType());
        return r;
    }

    /**
     * 从门诊医嘱创建检查申请单(his_order.order_type='检查'):
     * 取首条明细的检查要素(收费项目/部位/部位数/造影方式), 医保编码取收费目录对照,
     * 医保三要素/临床信息自 his_visit 回填, 幂等(同医嘱已有申请单直接返回)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamRequest createFromOutpatientOrder(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "门诊医嘱ID不能为空");
        }
        long tid = tenantId();
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT id, visit_id, order_no, patient_id, patient_name, dept_id, dept_name, dr_id, dr_name,"
                        + " order_type, diag_name, total_amount, status, exec_dept_id, paid_flag"
                        + " FROM his_order WHERE id = ? AND tenant_id = ? AND deleted = 0", orderId, tid);
        if (orders.isEmpty()) {
            throw new BizException(400, "门诊医嘱单不存在");
        }
        Map<String, Object> order = orders.get(0);
        if (!"检查".equals(str(order.get("order_type")))) {
            throw new BizException(400, "仅检查类医嘱可创建检查申请单(当前单据类型: " + str(order.get("order_type")) + ")");
        }
        // 幂等: 同一医嘱已有未删除申请单直接返回
        HisExamRequest existed = lambdaQuery().eq(HisExamRequest::getOrderId, orderId)
                .orderByDesc(HisExamRequest::getId).last("LIMIT 1").one();
        if (existed != null) {
            return existed;
        }

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT item_id, item_code, item_name, exam_part, site_count, contrast_mode, amount"
                        + " FROM his_order_item WHERE order_id = ? AND tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1",
                orderId, tid);
        Map<String, Object> item = items.isEmpty() ? null : items.get(0);

        Map<String, Object> visit = one("SELECT id, patient_id, patient_name, mdtrt_id, psn_no, dept_id, dept_code,"
                + " dept_name, staff_id, atddr_no, dr_name, med_type, chief_complaint, present_illness, allergy_history"
                + " FROM his_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                toLong(order.get("visit_id")), tid);

        HisExamRequest r = new HisExamRequest();
        r.setOrgId(resolveOrgId(toLong(order.get("dept_id"))));
        r.setRequestNo(nextRequestNo());
        r.setSourceType(1);
        r.setOrderId(orderId);
        r.setVisitId(toLong(order.get("visit_id")));
        if (visit != null) {
            r.setPatientId(toLong(visit.get("patient_id")));
            // 医保三要素: 医保就诊ID/人员编号自就诊回填, 就医流水号沿用追溯码口径(医保时=mdtrt_id, 自费=OP+visitId)
            String mdtrtId = str(visit.get("mdtrt_id"));
            r.setMdtrtId(mdtrtId);
            r.setPsnNo(str(visit.get("psn_no")));
            r.setMdtrtSn(StringUtils.hasText(mdtrtId) ? mdtrtId : "OP" + visit.get("id"));
            // 临床信息: 主诉+现病史拼接为简要病史, 过敏史直取
            String cc = str(visit.get("chief_complaint"));
            String pi = str(visit.get("present_illness"));
            r.setClinicalHistory(joinText(cc, pi));
            r.setAllergyInfo(str(visit.get("allergy_history")));
            r.setIsUrgent("14".equals(str(visit.get("med_type"))) ? 1 : 0);
            r.setApplyDoctorCode(str(visit.get("atddr_no")));
        }
        if (r.getPatientId() == null) {
            r.setPatientId(toLong(order.get("patient_id")));
        }
        // 检查项目要素(门诊医嘱明细 + 收费目录医保对照)
        fillChargeItemInfo(r, toLong(item == null ? null : item.get("item_id")),
                str(item == null ? null : item.get("item_code")), str(item == null ? null : item.get("item_name")));
        if (item != null) {
            r.setBodyPart(str(item.get("exam_part")));
            r.setSiteCount(item.get("site_count") == null ? 1
                    : Integer.parseInt(item.get("site_count").toString()));
            r.setContrastMode(normalizeContrast(str(item.get("contrast_mode"))));
            r.setExamCharge(toBd(item.get("amount")));
        }
        // 检查类型自项目名称推断(含 Modality 与医保影像检查类型)
        inferExamType(r, firstText(str(item == null ? null : item.get("item_name")), r.getChargeItemName()));
        // 申请医生/科室: 医嘱开单人开单科室为准
        r.setApplyDoctorId(toLong(order.get("dr_id")));
        r.setApplyDoctorName(str(order.get("dr_name")));
        r.setApplyDeptId(toLong(order.get("dept_id")));
        r.setApplyDeptName(str(order.get("dept_name")));
        if (visit != null) {
            r.setApplyDeptCode(str(visit.get("dept_code")));
        }
        fillDeptCodes(r, toLong(order.get("dept_id")), toLong(order.get("exec_dept_id")));
        r.setClinicalDiagnosis(str(order.get("diag_name")));
        r.setPaidFlag(order.get("paid_flag") == null ? 0
                : Integer.parseInt(order.get("paid_flag").toString()));
        r.setExamCharge(r.getExamCharge() == null ? toBd(order.get("total_amount")) : r.getExamCharge());
        r.setPriority(5);
        r.setApplyTime(LocalDateTime.now());
        r.setStatus(ST_PENDING);
        r.setYbUploadStatus(0);
        r.setValiFlag("1");
        save(r);
        log.info("门诊检查申请创建: requestNo={}, orderId={}, patientId={}, examType={}",
                r.getRequestNo(), orderId, r.getPatientId(), r.getExamType());
        return r;
    }

    /**
     * 从住院医嘱创建检查申请单(his_inp_order.order_category=2):
     * 医嘱单表自带检查要素(部位/部位数/造影方式), 医保三要素自 his_inp_visit 回填,
     * 住院科室落 ipt_dept_*, 幂等(同医嘱已有申请单直接返回)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamRequest createFromInpatientOrder(Long inpOrderId) {
        if (inpOrderId == null) {
            throw new BizException(400, "住院医嘱ID不能为空");
        }
        long tid = tenantId();
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT id, inp_visit_id, order_type, order_category, order_content, charge_item_id, doctor_id,"
                        + " order_dept_id, quantity, unit_price, exam_part, site_count, contrast_mode, order_status"
                        + " FROM his_inp_order WHERE id = ? AND tenant_id = ? AND deleted = 0", inpOrderId, tid);
        if (orders.isEmpty()) {
            throw new BizException(400, "住院医嘱不存在");
        }
        Map<String, Object> order = orders.get(0);
        Number category = (Number) order.get("order_category");
        if (category == null || category.intValue() != 2) {
            throw new BizException(400, "仅检查类住院医嘱可创建检查申请单(医嘱分类: " + category + ")");
        }
        Number orderStatus = (Number) order.get("order_status");
        if (orderStatus != null && orderStatus.intValue() == 6) {
            throw new BizException("该住院医嘱已作废, 无法创建检查申请单");
        }
        // 幂等: 同一住院医嘱已有未删除申请单直接返回
        HisExamRequest existed = lambdaQuery().eq(HisExamRequest::getInpOrderId, inpOrderId)
                .orderByDesc(HisExamRequest::getId).last("LIMIT 1").one();
        if (existed != null) {
            return existed;
        }

        Map<String, Object> visit = one("SELECT id, org_id, patient_id, psn_no, mdtrt_id, dept_id, admit_diag,"
                + " is_quarantine, visit_status FROM his_inp_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                toLong(order.get("inp_visit_id")), tid);

        HisExamRequest r = new HisExamRequest();
        r.setOrgId(visit != null && visit.get("org_id") != null ? toLong(visit.get("org_id")) : guard.currentOrgId());
        r.setRequestNo(nextRequestNo());
        r.setSourceType(2);
        r.setInpOrderId(inpOrderId);
        r.setInpVisitId(toLong(order.get("inp_visit_id")));
        if (visit != null) {
            r.setPatientId(toLong(visit.get("patient_id")));
            String mdtrtId = str(visit.get("mdtrt_id"));
            r.setMdtrtId(mdtrtId);
            r.setPsnNo(str(visit.get("psn_no")));
            // 就医流水号口径与门诊一致: 医保时=mdtrt_id, 自费时=院内流水 IP+inpVisitId
            r.setMdtrtSn(StringUtils.hasText(mdtrtId) ? mdtrtId : "IP" + visit.get("id"));
            r.setClinicalDiagnosis(str(visit.get("admit_diag")));
            r.setIsIsolation(visit.get("is_quarantine") == null ? 0
                    : Integer.parseInt(visit.get("is_quarantine").toString()));
        }
        if (r.getPatientId() == null) {
            throw new BizException(400, "住院医嘱缺少患者信息, 无法创建检查申请单");
        }
        // 检查项目要素: 收费目录医保对照 + 医嘱自带检查要素
        fillChargeItemInfo(r, toLong(order.get("charge_item_id")), null, str(order.get("order_content")));
        r.setBodyPart(str(order.get("exam_part")));
        r.setSiteCount(order.get("site_count") == null ? 1 : Integer.parseInt(order.get("site_count").toString()));
        r.setContrastMode(normalizeContrast(str(order.get("contrast_mode"))));
        // 检查费用 = 单价 x 数量(住院检查医嘱逐条计价)
        BigDecimal price = toBd(order.get("unit_price"));
        BigDecimal qty = toBd(order.get("quantity"));
        if (price != null && qty != null) {
            r.setExamCharge(price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP));
        }
        inferExamType(r, firstText(str(order.get("order_content")), r.getChargeItemName()));
        // 申请医生: 开嘱医生(his_staff 回填姓名/医保编码)
        Long doctorId = toLong(order.get("doctor_id"));
        r.setApplyDoctorId(doctorId);
        if (doctorId != null) {
            Map<String, Object> staff = one("SELECT staff_name, atddr_no FROM his_staff"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", doctorId, tid);
            if (staff != null) {
                r.setApplyDoctorName(str(staff.get("staff_name")));
                r.setApplyDoctorCode(str(staff.get("atddr_no")));
            }
        }
        // 申请科室/住院科室: 医嘱开单科室优先, 回退住院科室
        Long applyDeptId = toLong(order.get("order_dept_id"));
        if (applyDeptId == null && visit != null) {
            applyDeptId = toLong(visit.get("dept_id"));
        }
        r.setApplyDeptId(applyDeptId);
        Long iptDeptId = visit == null ? null : toLong(visit.get("dept_id"));
        if (applyDeptId != null) {
            Map<String, Object> dept = one("SELECT dept_code, dept_name, dept_caty FROM his_dept"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", applyDeptId, tid);
            if (dept != null) {
                r.setApplyDeptCode(str(dept.get("dept_code")));
                r.setApplyDeptName(str(dept.get("dept_name")));
            }
        }
        if (iptDeptId != null) {
            Map<String, Object> iptDept = one("SELECT dept_code, dept_name FROM his_dept"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", iptDeptId, tid);
            if (iptDept != null) {
                r.setIptDeptCode(str(iptDept.get("dept_code")));
                r.setIptDeptName(str(iptDept.get("dept_name")));
            }
        }
        r.setPriority(5);
        r.setApplyTime(LocalDateTime.now());
        r.setStatus(ST_PENDING);
        r.setPaidFlag(1);
        r.setYbUploadStatus(0);
        r.setValiFlag("1");
        save(r);
        log.info("住院检查申请创建: requestNo={}, inpOrderId={}, patientId={}, examType={}",
                r.getRequestNo(), inpOrderId, r.getPatientId(), r.getExamType());
        return r;
    }

    /* ================= 状态机 ================= */

    /**
     * 状态流转: 顺序前进(0->1->2->3->4->5->6, 乐观锁), 0/1 可取消(->7 走 cancel 联动释放占位)。
     * 取消(7)不入本方法, 由 {@link #cancel} 承担(需联动排程占位释放)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamRequest updateStatus(Long id, Integer newStatus) {
        if (id == null || newStatus == null) {
            throw new BizException(400, "申请单ID与目标状态不能为空");
        }
        if (newStatus == ST_CANCELLED) {
            throw new BizException(400, "取消申请单请走取消接口(cancel)");
        }
        HisExamRequest r = getById(id);
        if (r == null) {
            throw new BizException(400, "申请单不存在");
        }
        Integer cur = r.getStatus();
        if (cur == null) {
            cur = ST_PENDING;
        }
        if (cur == ST_CANCELLED) {
            throw new BizException("该申请单已取消, 不可再流转");
        }
        if (newStatus != cur + 1) {
            throw new BizException("状态流转非法: " + statusLabel(cur) + " -> " + statusLabel(newStatus)
                    + "(仅允许顺序前进或取消)");
        }
        // 已预约->已登记由登记台执行; 已预约时段信息保留
        int affected = jdbcTemplate.update(
                "UPDATE his_exam_request SET status = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = ? AND tenant_id = ? AND deleted = 0",
                newStatus, currentUserName(), id, cur, tenantId());
        if (affected == 0) {
            throw new BizException("申请单状态已变更, 请刷新后重试");
        }
        log.info("检查申请状态流转: id={}, {} -> {}", id, cur, newStatus);
        return getById(id);
    }

    /**
     * 取消申请单: 仅待预约(0)/已预约(1)可取消; 已预约时联动释放排程占位(时段余量回退/已满恢复可约),
     * 留痕取消原因, 置状态 7。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamRequest cancel(Long id, String reason) {
        if (id == null) {
            throw new BizException(400, "申请单ID不能为空");
        }
        HisExamRequest r = getById(id);
        if (r == null) {
            throw new BizException(400, "申请单不存在");
        }
        Integer cur = r.getStatus();
        if (cur == null || cur > ST_BOOKED) {
            throw new BizException("仅待预约/已预约的申请单可取消(当前状态: " + statusLabel(cur) + ")");
        }
        if (cur == ST_BOOKED) {
            // 已预约: 先释放排程占位(时段余量回退/已满恢复可约/申请单回退待预约), 再落取消
            scheduleService.cancelBookingByRequest(id);
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_exam_request SET status = ?, cancel_reason = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status IN (0, 1) AND tenant_id = ? AND deleted = 0",
                ST_CANCELLED, StringUtils.hasText(reason) ? reason.trim() : "医生站取消", currentUserName(),
                id, tenantId());
        if (affected == 0) {
            throw new BizException("申请单状态已变更, 取消失败请刷新重试");
        }
        log.info("检查申请取消: id={}, requestNo={}, 原状态={}, 原因={}", id, r.getRequestNo(), cur, reason);
        return getById(id);
    }

    /* ================= 查询 ================= */

    /**
     * 申请单分页(机构级): 支持 keyword(申请单号/患者姓名/检查项目)/来源/检查类型/状态/急诊/
     * 患者/申请科室/执行科室/设备/缴费/上报状态/申请日期区间筛选, JOIN 患者与设备取名称。
     */
    public IPage<Map<String, Object>> page(Long orgId, RisRequestQueryDTO query) {
        if (orgId == null) {
            throw new BizException(400, "机构范围不能为空");
        }
        long p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        long s = query.getSize() == null || query.getSize() < 1 ? 20
                : (query.getSize() > 200 ? 200 : query.getSize());
        StringBuilder where = new StringBuilder(" WHERE r.deleted = 0 AND r.tenant_id = ? AND r.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(orgId);
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = "%" + query.getKeyword().trim() + "%";
            where.append(" AND (r.request_no LIKE ? OR p.name LIKE ? OR r.charge_item_name LIKE ?)");
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        if (query.getSourceType() != null) {
            where.append(" AND r.source_type = ?");
            args.add(query.getSourceType());
        }
        if (StringUtils.hasText(query.getExamType())) {
            where.append(" AND r.exam_type = ?");
            args.add(query.getExamType().trim());
        }
        if (query.getStatus() != null) {
            where.append(" AND r.status = ?");
            args.add(query.getStatus());
        }
        if (query.getIsUrgent() != null) {
            where.append(" AND r.is_urgent = ?");
            args.add(query.getIsUrgent());
        }
        if (query.getPatientId() != null) {
            where.append(" AND r.patient_id = ?");
            args.add(query.getPatientId());
        }
        if (query.getApplyDeptId() != null) {
            where.append(" AND r.apply_dept_id = ?");
            args.add(query.getApplyDeptId());
        }
        if (query.getTargetDeptId() != null) {
            where.append(" AND r.target_dept_id = ?");
            args.add(query.getTargetDeptId());
        }
        if (query.getDeviceId() != null) {
            where.append(" AND r.device_id = ?");
            args.add(query.getDeviceId());
        }
        if (query.getPaidFlag() != null) {
            where.append(" AND r.paid_flag = ?");
            args.add(query.getPaidFlag());
        }
        if (query.getYbUploadStatus() != null) {
            where.append(" AND r.yb_upload_status = ?");
            args.add(query.getYbUploadStatus());
        }
        if (StringUtils.hasText(query.getDateFrom())) {
            where.append(" AND DATE(r.apply_time) >= ?");
            args.add(query.getDateFrom().trim());
        }
        if (StringUtils.hasText(query.getDateTo())) {
            where.append(" AND DATE(r.apply_time) <= ?");
            args.add(query.getDateTo().trim());
        }
        String joins = " FROM his_exam_request r"
                + " LEFT JOIN his_patient p ON p.id = r.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_imaging_device d ON d.id = r.device_id AND d.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        String dataSql = "SELECT r.id, r.request_no AS requestNo, r.source_type AS sourceType,"
                + " r.order_id AS orderId, r.inp_order_id AS inpOrderId, r.patient_id AS patientId,"
                + " p.name AS patientName, p.gender_name AS genderName, p.age,"
                + " r.charge_item_name AS chargeItemName, r.exam_item_code AS examItemCode,"
                + " r.exam_type AS examType, r.body_part AS bodyPart, r.is_urgent AS isUrgent,"
                + " r.apply_dept_name AS applyDeptName, r.target_dept_name AS targetDeptName,"
                + " d.device_name AS deviceName, r.scheduled_time AS scheduledTime,"
                + " DATE_FORMAT(r.apply_time, '%Y-%m-%d %H:%i:%s') AS applyTime,"
                + " r.status, r.paid_flag AS paidFlag, r.exam_charge AS examCharge, r.cancel_reason AS cancelReason"
                + joins + where + " ORDER BY r.id DESC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /** 按执行科室+日期+状态查询(技师/登记台工作台视角, 日期为申请日期) */
    public List<HisExamRequest> listByDept(Long orgId, Long deptId, String date, Integer status) {
        if (orgId == null) {
            throw new BizException(400, "机构范围不能为空");
        }
        return lambdaQuery()
                .eq(HisExamRequest::getOrgId, orgId)
                .eq(deptId != null, HisExamRequest::getTargetDeptId, deptId)
                .eq(status != null, HisExamRequest::getStatus, status)
                .apply(StringUtils.hasText(date), "DATE(apply_time) = {0}", date)
                .orderByAsc(HisExamRequest::getApplyTime)
                .list();
    }

    /** 按分配设备+日期查询(技师工作台视角, 日期为预约检查时间当天) */
    public List<HisExamRequest> listByDevice(Long orgId, Long deviceId, String date) {
        if (orgId == null || deviceId == null) {
            throw new BizException(400, "机构与设备不能为空");
        }
        return lambdaQuery()
                .eq(HisExamRequest::getOrgId, orgId)
                .eq(HisExamRequest::getDeviceId, deviceId)
                .apply(StringUtils.hasText(date), "DATE(scheduled_time) = {0}", date)
                .in(HisExamRequest::getStatus, ST_BOOKED, 2, 3, 4)
                .orderByAsc(HisExamRequest::getScheduledTime)
                .list();
    }

    /** 申请单详情(含患者/设备/排程时段信息) */
    public Map<String, Object> getDetail(Long id) {
        if (id == null) {
            throw new BizException(400, "申请单ID不能为空");
        }
        HisExamRequest r = getById(id);
        if (r == null) {
            throw new BizException(400, "申请单不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("request", r);
        if (r.getPatientId() != null) {
            detail.put("patient", one("SELECT id, name, gender_name AS genderName, age, patient_no AS patientNo,"
                    + " phone FROM his_patient WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    r.getPatientId(), tenantId()));
        }
        if (r.getDeviceId() != null) {
            detail.put("device", one("SELECT id, device_code AS deviceCode, device_name AS deviceName,"
                    + " device_type AS deviceType, modality, room_no AS roomNo FROM his_imaging_device"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", r.getDeviceId(), tenantId()));
        }
        detail.put("schedule", one("SELECT id, schedule_date AS scheduleDate, time_slot AS timeSlot,"
                + " slot_start AS slotStart, slot_end AS slotEnd, booked_count AS bookedCount,"
                + " max_patients AS maxPatients, technician_id AS technicianId FROM his_exam_schedule"
                + " WHERE request_id = ? AND tenant_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                id, tenantId()));
        return detail;
    }

    /* ================= 单号 ================= */

    /** 单号生成: JC+yyyyMMdd+4位序号, synchronized 唯一, 跨日重置时DB回读当日最大序号兜底重启, 占用冲突再自增 */
    private synchronized String nextRequestNo() {
        String today = LocalDateTime.now().format(DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = REQ_PREFIX + today + String.format("%04d", seqNo);
        while (requestNoExists(no)) {
            seqNo++;
            no = REQ_PREFIX + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有单号最大序号(重启后防撞号; Mapper 查询经租户插件自动按当前租户过滤) */
    private int maxSeqFromDb(String today) {
        HisExamRequest one = lambdaQuery()
                .likeRight(HisExamRequest::getRequestNo, REQ_PREFIX + today)
                .orderByDesc(HisExamRequest::getRequestNo)
                .last("LIMIT 1").one();
        if (one == null || one.getRequestNo() == null || one.getRequestNo().length() < 10) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getRequestNo().substring(one.getRequestNo().length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean requestNoExists(String requestNo) {
        return lambdaQuery().eq(HisExamRequest::getRequestNo, requestNo).count() > 0;
    }

    /* ================= 要素回填 ================= */

    /**
     * 医保检查项目对照回填: exam_item_code 取收费目录医保对照编码(med_list_codg),
     * 院内检查项目代码/名称取收费目录 item_code/item_name;
     * 医保检查项目名称 best-effort 回查标准字典 std_med_service.nat_item_name(按医保码), 查不到回落院内名称。
     */
    private void fillChargeItemInfo(HisExamRequest r, Long chargeItemId, String itemCode, String itemName) {
        Map<String, Object> cat = chargeItemId == null ? null
                : one("SELECT item_code, item_name, med_list_codg FROM his_charge_item"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0", chargeItemId, tenantId());
        if (cat != null) {
            r.setChargeItemId(chargeItemId);
            r.setChargeItemCode(str(cat.get("item_code")));
            r.setChargeItemName(str(cat.get("item_name")));
            String medListCodg = str(cat.get("med_list_codg"));
            if (StringUtils.hasText(medListCodg)) {
                r.setExamItemCode(medListCodg);
                Map<String, Object> std = one("SELECT nat_item_name FROM std_med_service"
                        + " WHERE nat_item_code = ? LIMIT 1", medListCodg);
                r.setExamItemName(std == null ? str(cat.get("item_name")) : str(std.get("nat_item_name")));
            }
            r.setInhospExamItemCode(str(cat.get("item_code")));
            r.setInhospExamItemName(str(cat.get("item_name")));
        } else {
            // 目录缺失(手工开单/医嘱未关联收费项目): 透传入参, 医保编码留空待对照
            r.setChargeItemId(chargeItemId);
            r.setChargeItemCode(itemCode);
            r.setChargeItemName(itemName);
            r.setInhospExamItemCode(itemCode);
            r.setInhospExamItemName(itemName);
        }
    }

    /**
     * 检查类型推断(名称关键词 -> exam_type/modality/医保影像检查类型):
     * CT/磁共振/超声/血管造影/内镜可识别, 其余放射类缺省 XRAY。
     */
    private void inferExamType(HisExamRequest r, String text) {
        String name = text == null ? "" : text;
        String upper = name.toUpperCase();
        if (upper.contains("CT")) {
            r.setExamType("CT");
            r.setModality("CT");
            r.setImgExamType("2");
        } else if (upper.contains("MRI") || upper.contains("MR") || name.contains("磁共振")) {
            r.setExamType("MRI");
            r.setModality("MR");
            r.setImgExamType("3");
        } else if (upper.contains("US") || name.contains("超声") || name.contains("彩超") || name.contains("B超")) {
            r.setExamType("US");
            r.setModality("US");
            r.setImgExamType("4");
        } else if (upper.contains("DSA") || name.contains("血管造影")) {
            r.setExamType("DSA");
            r.setModality("XA");
        } else if (name.contains("内镜") || name.contains("胃镜") || name.contains("肠镜")
                || name.contains("支气管镜") || name.contains("喉镜") || name.contains("镜检")) {
            r.setExamType("ENDO");
            r.setModality("ES");
        } else {
            // 放射类缺省: X线摄片/DR/CR/透视
            r.setExamType("XRAY");
            r.setModality("DX");
            r.setImgExamType("1");
        }
        if (r.getExamTypeCode() == null && r.getImgExamType() != null) {
            r.setExamTypeCode(r.getImgExamType());
            r.setExamTypeName(imgExamTypeName(r.getImgExamType()));
        }
    }

    private static String imgExamTypeName(String code) {
        if (code == null) {
            return null;
        }
        switch (code) {
            case "1": return "X线";
            case "2": return "CT";
            case "3": return "MRI";
            case "4": return "US";
            case "5": return "ECT";
            default: return null;
        }
    }

    /** 造影方式归一: 医嘱侧"平扫/增强"口径 -> 申请单 NONE/IV(实体值域) */
    private static String normalizeContrast(String mode) {
        if (!StringUtils.hasText(mode)) {
            return null;
        }
        String m = mode.trim();
        if ("增强".equals(m)) {
            return "IV";
        }
        if ("平扫".equals(m) || "NONE".equalsIgnoreCase(m)) {
            return "NONE";
        }
        if ("IV".equalsIgnoreCase(m)) {
            return "IV";
        }
        if ("ORAL".equalsIgnoreCase(m)) {
            return "ORAL";
        }
        if ("BOTH".equalsIgnoreCase(m)) {
            return "BOTH";
        }
        return null;
    }

    /** 手工开单的医保三要素回填: 门诊就诊回填 mdtrt_id/psn_no, 住院就诊回填, 均无则就医流水号透传入参 */
    private void fillInsuranceInfo(HisExamRequest r, String mdtrtSn, String mdtrtId, String psnNo) {
        if (r.getVisitId() != null) {
            Map<String, Object> visit = one("SELECT mdtrt_id, psn_no FROM his_visit"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", r.getVisitId(), tenantId());
            if (visit != null) {
                String vid = str(visit.get("mdtrt_id"));
                mdtrtId = StringUtils.hasText(mdtrtId) ? mdtrtId : vid;
                psnNo = StringUtils.hasText(psnNo) ? psnNo : str(visit.get("psn_no"));
                mdtrtSn = StringUtils.hasText(mdtrtSn) ? mdtrtSn
                        : (StringUtils.hasText(vid) ? vid : "OP" + r.getVisitId());
            }
        } else if (r.getInpVisitId() != null) {
            Map<String, Object> visit = one("SELECT mdtrt_id, psn_no FROM his_inp_visit"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", r.getInpVisitId(), tenantId());
            if (visit != null) {
                String vid = str(visit.get("mdtrt_id"));
                mdtrtId = StringUtils.hasText(mdtrtId) ? mdtrtId : vid;
                psnNo = StringUtils.hasText(psnNo) ? psnNo : str(visit.get("psn_no"));
                mdtrtSn = StringUtils.hasText(mdtrtSn) ? mdtrtSn
                        : (StringUtils.hasText(vid) ? vid : "IP" + r.getInpVisitId());
            }
        }
        r.setMdtrtSn(mdtrtSn);
        r.setMdtrtId(mdtrtId);
        r.setPsnNo(psnNo);
    }

    /** 申请/执行科室代码回填: 申请科室代码与执行科室代码/名称自 his_dept 取 */
    private void fillDeptCodes(HisExamRequest r, Long applyDeptId, Long targetDeptId) {
        if (applyDeptId != null && !StringUtils.hasText(r.getApplyDeptCode())) {
            Map<String, Object> dept = one("SELECT dept_code, dept_name FROM his_dept"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", applyDeptId, tenantId());
            if (dept != null) {
                r.setApplyDeptCode(str(dept.get("dept_code")));
                if (!StringUtils.hasText(r.getApplyDeptName())) {
                    r.setApplyDeptName(str(dept.get("dept_name")));
                }
            }
        }
        if (targetDeptId != null) {
            Map<String, Object> dept = one("SELECT dept_code, dept_name FROM his_dept"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", targetDeptId, tenantId());
            if (dept != null) {
                r.setTargetDeptId(targetDeptId);
                r.setTargetDeptCode(str(dept.get("dept_code")));
                r.setTargetDeptName(str(dept.get("dept_name")));
            }
        }
    }

    /**
     * 申请单机构归属: 就诊(开单)科室归属机构优先, 回退当前登录机构
     * (与医嘱定价机构口径一致; 门诊就诊表无机构列, 机构由科室归属推导)。
     */
    private Long resolveOrgId(Long deptId) {
        if (deptId != null) {
            Map<String, Object> dept = one("SELECT org_id FROM his_dept"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", deptId, tenantId());
            if (dept != null && dept.get("org_id") != null) {
                return toLong(dept.get("org_id"));
            }
        }
        return guard.currentOrgId();
    }

    /* ================= 辅助 ================= */

    private static String statusLabel(Integer status) {
        if (status == null) {
            return "-";
        }
        switch (status) {
            case 0: return "待预约";
            case 1: return "已预约";
            case 2: return "已登记";
            case 3: return "检查中";
            case 4: return "已完成";
            case 5: return "已报告";
            case 6: return "已审核";
            case 7: return "已取消";
            default: return String.valueOf(status);
        }
    }

    private static String joinText(String a, String b) {
        boolean ha = StringUtils.hasText(a);
        boolean hb = StringUtils.hasText(b);
        if (ha && hb) {
            return a.trim() + "; " + b.trim();
        }
        return ha ? a.trim() : (hb ? b.trim() : null);
    }

    private static String firstText(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

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
            return null;
        }
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return new BigDecimal(o.toString());
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
