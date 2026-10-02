package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryPacu;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryPacuMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 术毕复苏(PACU)服务(手麻P3b): 独立旁路单据, 不插主状态机新枚举值。
 * 流转: admit 入复苏(守卫手术 status=4 术后窗口 + 同手术无在途单) -> recordVital 生命体征时间点追加
 * -> discharge 出复苏(Aldrete>=9 放行, <9 必须填理由)。
 * 主状态机软门禁在 SurgeryService.complete/cancel: 存在 status=1 在途复苏单则拒绝完成/取消。
 * 机构隔离: 写以 currentOrgId 校验, 读按 scopeOrgId(牵头可跨机构)。
 * JdbcTemplate 手写 SQL 不走租户插件, 必须显式 tenant_id AND deleted=0, 参数一律 .toArray()。
 */
@Slf4j
@Service
public class PacuService {

    /** 复苏单状态: 1复苏中 2已出 */
    private static final int PACU_IN_PROGRESS = 1;
    private static final int PACU_DISCHARGED = 2;
    /** 出复苏 Aldrete 放行线: >=9 直接出, <9 须说明理由 */
    private static final int ALDRETE_PASS = 9;

    private final HisSurgeryPacuMapper pacuMapper;
    private final HisSurgeryMapper surgeryMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public PacuService(HisSurgeryPacuMapper pacuMapper, HisSurgeryMapper surgeryMapper,
                       OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.pacuMapper = pacuMapper;
        this.surgeryMapper = surgeryMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 入复苏: 守卫 = 手术 status=4(已结束未完成的术后窗口) + 该手术无 status=1 在途复苏单 + 同机构写;
     * 建单携患者三快照(自 surgery/inp_visit/patient 三表 JOIN 取, 列表页免 JOIN)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryPacu admit(Long surgeryId, Integer aldreteAdmit, String admitNote) {
        if (surgeryId == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        HisSurgery s = surgeryMapper.selectById(surgeryId);
        if (s == null) {
            throw new BizException(404, "手术记录不存在");
        }
        requireSameOrg(s.getOrgId());
        if (!Integer.valueOf(4).equals(s.getStatus())) {
            throw new BizException("仅已结束手术(术后状态)可入复苏, 当前状态: " + s.getStatus());
        }
        if (hasInProgress(surgeryId)) {
            throw new BizException("该手术已有复苏中的记录, 不可重复入复苏");
        }
        Long staffId = currentStaffId();
        HisSurgeryPacu pacu = new HisSurgeryPacu();
        pacu.setOrgId(s.getOrgId());
        pacu.setSurgeryId(surgeryId);
        pacu.setInpVisitId(s.getInpVisitId());
        fillPatientSnapshot(pacu, s);
        pacu.setAdmitTime(LocalDateTime.now());
        pacu.setAdmitNurseId(staffId);
        pacu.setAdmitNote(truncate(admitNote, 200));
        pacu.setAldreteAdmit(checkAldrete(aldreteAdmit, false));
        pacu.setStatus(PACU_IN_PROGRESS);
        pacuMapper.insert(pacu);
        log.info("PACU入复苏: pacuId={}, surgeryId={}, patient={}, staffId={}",
                pacu.getId(), surgeryId, pacu.getPatientName(), staffId);
        return pacu;
    }

    /**
     * 生命体征登记: 仅 status=1 可追加; 白名单取值 {t,hp,hr,p,s,tm} 构建新对象后 Java 侧
     * parseArray 校验存量再序列化回写(防注入畸形 JSON)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryPacu recordVital(Long pacuId, Map<String, Object> item) {
        HisSurgeryPacu pacu = requirePacu(pacuId);
        if (!Integer.valueOf(PACU_IN_PROGRESS).equals(pacu.getStatus())) {
            throw new BizException("已出复苏的单据不可再登记生命体征");
        }
        if (item == null || item.isEmpty()) {
            throw new BizException(400, "体征项目不能为空");
        }
        JSONObject vital = new JSONObject();
        vital.put("t", item.get("t") == null ? LocalDateTime.now().toString() : String.valueOf(item.get("t")));
        vital.put("hp", item.get("hp"));
        vital.put("hr", item.get("hr"));
        vital.put("p", item.get("p"));
        vital.put("s", item.get("s"));
        vital.put("tm", item.get("tm"));
        JSONArray arr;
        if (StringUtils.hasText(pacu.getVitalsJson())) {
            try {
                arr = JSON.parseArray(pacu.getVitalsJson());
            } catch (Exception e) {
                throw new BizException("存量体征数据解析失败, 请联系管理员");
            }
        } else {
            arr = new JSONArray();
        }
        arr.add(vital);
        int n = pacuMapper.update(null, new LambdaUpdateWrapper<HisSurgeryPacu>()
                .set(HisSurgeryPacu::getVitalsJson, arr.toJSONString())
                .set(HisSurgeryPacu::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryPacu::getId, pacuId)
                .eq(HisSurgeryPacu::getStatus, PACU_IN_PROGRESS));
        if (n == 0) {
            throw new BizException("复苏单状态已变化(可能已出复苏), 请刷新后重试");
        }
        return pacuMapper.selectById(pacuId);
    }

    /**
     * 出复苏: 仅 status=1; Aldrete>=9 直接放行, <9 必须 discharge_note 填理由;
     * 乐观置2 + 去向/时间/护士留痕。去向: 1回病房 2转ICU 3门诊随访。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryPacu discharge(Long pacuId, Integer aldreteDischarge, Integer dischargeDest, String note) {
        HisSurgeryPacu pacu = requirePacu(pacuId);
        if (!Integer.valueOf(PACU_IN_PROGRESS).equals(pacu.getStatus())) {
            throw new BizException("该复苏单已出复苏, 请勿重复操作");
        }
        Integer score = checkAldrete(aldreteDischarge, true);
        if (score < ALDRETE_PASS && !StringUtils.hasText(note)) {
            throw new BizException("Aldrete评分低于" + ALDRETE_PASS + "分, 出复苏须在备注中说明理由");
        }
        if (dischargeDest == null || dischargeDest < 1 || dischargeDest > 3) {
            throw new BizException(400, "请选择出复苏去向(1回病房 2转ICU 3门诊随访)");
        }
        int n = pacuMapper.update(null, new LambdaUpdateWrapper<HisSurgeryPacu>()
                .set(HisSurgeryPacu::getStatus, PACU_DISCHARGED)
                .set(HisSurgeryPacu::getAldreteDischarge, score)
                .set(HisSurgeryPacu::getDischargeDest, dischargeDest)
                .set(HisSurgeryPacu::getDischargeNote, truncate(note, 200))
                .set(HisSurgeryPacu::getDischargeTime, LocalDateTime.now())
                .set(HisSurgeryPacu::getDischargeNurseId, currentStaffId())
                .set(HisSurgeryPacu::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryPacu::getId, pacuId)
                .eq(HisSurgeryPacu::getStatus, PACU_IN_PROGRESS));
        if (n == 0) {
            throw new BizException("复苏单状态已变化, 请刷新后重试");
        }
        log.info("PACU出复苏: pacuId={}, surgeryId={}, aldrete={}, dest={}", pacuId, pacu.getSurgeryId(), score, dischargeDest);
        return pacuMapper.selectById(pacuId);
    }

    /** 复苏工作台列表(分页): status/kw(姓名或住院号)过滤, JOIN 手术取手术名称与日期; 前导%通配已参数化 */
    public IPage<Map<String, Object>> list(Long orgId, Integer status, String kw, long page, long size) {
        long p = Math.max(1, page);
        long s = Math.max(1, Math.min(size, 200));
        StringBuilder sql = new StringBuilder("SELECT pc.id, pc.surgery_id AS surgeryId, pc.inp_visit_id AS inpVisitId,"
                + " pc.patient_id AS patientId, pc.patient_name AS patientName, pc.inp_no AS inpNo,"
                + " pc.admit_time AS admitTime, pc.admit_nurse_id AS admitNurseId, pc.aldrete_admit AS aldreteAdmit,"
                + " pc.vitals_json AS vitalsJson, pc.status, pc.discharge_time AS dischargeTime,"
                + " pc.aldrete_discharge AS aldreteDischarge, pc.discharge_dest AS dischargeDest,"
                + " pc.discharge_note AS dischargeNote, sg.surgery_name AS surgeryName, sg.schedule_date AS scheduleDate"
                + " FROM his_surgery_pacu pc"
                + " LEFT JOIN his_surgery sg ON sg.id = pc.surgery_id AND sg.deleted = 0"
                + " WHERE pc.deleted = 0 AND pc.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(orgId);
        if (scope != null) {
            sql.append(" AND pc.org_id = ?");
            args.add(scope);
        }
        if (status != null) {
            sql.append(" AND pc.status = ?");
            args.add(status);
        }
        if (StringUtils.hasText(kw)) {
            sql.append(" AND (pc.patient_name LIKE ? OR pc.inp_no LIKE ?)");
            String like = "%" + kw.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY pc.status, pc.admit_time DESC, pc.id DESC");
        String countSql = "SELECT COUNT(*) FROM his_surgery_pacu pc WHERE pc.deleted = 0 AND pc.tenant_id = ?"
                + (scope != null ? " AND pc.org_id = ?" : "")
                + (status != null ? " AND pc.status = ?" : "")
                + (StringUtils.hasText(kw) ? " AND (pc.patient_name LIKE ? OR pc.inp_no LIKE ?)" : "");
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(tenantId());
        if (scope != null) {
            countArgs.add(scope);
        }
        if (status != null) {
            countArgs.add(status);
        }
        if (StringUtils.hasText(kw)) {
            String like = "%" + kw.trim() + "%";
            countArgs.add(like);
            countArgs.add(like);
        }
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, countArgs.toArray());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql.append(" LIMIT ? OFFSET ?").toString(), dilutedArgs(args, p, s));
        Page<Map<String, Object>> out = new Page<>(p, s);
        out.setTotal(total == null ? 0L : total);
        out.setRecords(rows);
        return out;
    }

    /** 按手术查询复苏单(详情抽屉回显, 历史可多条, 最近在前) */
    public List<HisSurgeryPacu> listBySurgery(Long surgeryId) {
        if (surgeryId == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        return pacuMapper.selectList(new LambdaQueryWrapper<HisSurgeryPacu>()
                .eq(HisSurgeryPacu::getSurgeryId, surgeryId)
                .orderByDesc(HisSurgeryPacu::getId));
    }

    /** 存在在途复苏单(status=1): SurgeryService complete/cancel 软门禁依据(租户插件自动过滤 tenant_id) */
    public boolean hasInProgress(Long surgeryId) {
        if (surgeryId == null) {
            return false;
        }
        Long cnt = pacuMapper.selectCount(new LambdaQueryWrapper<HisSurgeryPacu>()
                .eq(HisSurgeryPacu::getSurgeryId, surgeryId)
                .eq(HisSurgeryPacu::getStatus, PACU_IN_PROGRESS));
        return cnt != null && cnt > 0;
    }

    /* ==================== 内部工具 ==================== */

    /** 患者三快照: 自 surgery.inp_visit_id -> his_inp_visit -> his_patient JOIN 取(门诊/无住院就诊则留空) */
    private void fillPatientSnapshot(HisSurgeryPacu pacu, HisSurgery s) {
        if (s.getInpVisitId() == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.patient_id AS patientId, v.inp_no AS inpNo, p.name AS patientName"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?",
                s.getInpVisitId(), tenantId());
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            pacu.setPatientId(toLong(row.get("patientId")));
            pacu.setInpNo(row.get("inpNo") == null ? null : String.valueOf(row.get("inpNo")));
            pacu.setPatientName(row.get("patientName") == null ? null : String.valueOf(row.get("patientName")));
        }
    }

    private HisSurgeryPacu requirePacu(Long pacuId) {
        if (pacuId == null) {
            throw new BizException(400, "复苏单ID不能为空");
        }
        HisSurgeryPacu pacu = pacuMapper.selectById(pacuId);
        if (pacu == null) {
            throw new BizException(404, "复苏单不存在");
        }
        requireSameOrg(pacu.getOrgId());
        return pacu;
    }

    /** Aldrete 评分区间校验(0-10); required=true 时不可为空 */
    private Integer checkAldrete(Integer score, boolean required) {
        if (score == null) {
            if (required) {
                throw new BizException(400, "Aldrete评分不能为空(0-10)");
            }
            return null;
        }
        if (score < 0 || score > 10) {
            throw new BizException("Aldrete评分须在0-10之间");
        }
        return score;
    }

    private void requireSameOrg(Long orgId) {
        Long cur = guard.currentOrgId();
        if (cur == null || orgId == null || !cur.equals(orgId)) {
            throw new BizException(403, "无权操作其他机构的复苏记录");
        }
    }

    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getStaffId() == null) {
            throw new BizException(401, "未登录或账号未关联职工档案");
        }
        return lu.getStaffId();
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    /** 列表页参数 = 过滤参数 + 尾部 LIMIT/OFFSET(必须 .toArray(), 不可传 List) */
    private static Object[] dilutedArgs(List<Object> args, long page, long size) {
        Object[] out = new Object[args.size() + 2];
        for (int i = 0; i < args.size(); i++) {
            out[i] = args.get(i);
        }
        out[args.size()] = size;
        out[args.size() + 1] = (page - 1) * size;
        return out;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
