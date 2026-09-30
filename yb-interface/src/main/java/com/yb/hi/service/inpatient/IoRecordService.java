package com.yb.hi.service.inpatient;

import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院出入量记录服务(护士站「出入量」面板): his_inp_io_record 逐笔登记(JdbcTemplate 直连),
 * 进量/出量分类录入 + 当日记录列表 + 日汇总(进量合计/出量合计/平衡值)。
 * 说明:
 * 1) 表由 DictSchemaMigration(T41) 幂等创建: io_type 1进量 2出量, volume DECIMAL(10,2) 单位 ml;
 * 2) 写操作校验就诊归属机构与当前登录机构一致(平台超管放行), 沿用住院护士站口径;
 * 3) JdbcTemplate 原生 SQL 不走 MP 租户插件, 所有语句手工带 tenant_id/deleted 过滤;
 * 4) 逻辑删除(deleted=1), 汇总口径自动排除已删行。
 */
@Service
public class IoRecordService {

    /** 出入量类型: 1进量 2出量 */
    public static final int IO_INTAKE = 1;
    public static final int IO_OUTPUT = 2;

    private final JdbcTemplate jdbcTemplate;
    private final HisInpVisitMapper visitMapper;

    public IoRecordService(JdbcTemplate jdbcTemplate, HisInpVisitMapper visitMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.visitMapper = visitMapper;
    }

    /**
     * 新增出入量记录: params 键约定
     * visitId(必填) / ioType(必填 1进量 2出量) / category(必填: 饮水/静脉输液/口服药/尿量/引流/呕吐/其他)
     * / volume(必填 大于0, ml) / route(可选 途径) / note(可选 备注) / recordTime(可选 yyyy-MM-dd HH:mm, 缺省当前)。
     * 登记护士取当前登录职工, patient_id 从就诊主表回填。
     */
    @Transactional
    public Map<String, Object> save(Map<String, Object> params, Long nurseId) {
        if (params == null || params.get("visitId") == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        Long visitId = toLong(params.get("visitId"));
        Integer ioType = toInt(params.get("ioType"));
        if (ioType == null || (ioType != IO_INTAKE && ioType != IO_OUTPUT)) {
            throw new BizException(400, "出入量类型无效(1进量 2出量)");
        }
        String category = trimToNull(params.get("category"));
        if (category == null) {
            throw new BizException(400, "类别不能为空(饮水/静脉输液/口服药/尿量/引流/呕吐/其他)");
        }
        BigDecimal volume = toDecimal(params.get("volume"));
        if (volume == null || volume.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "量(ml)必须为大于 0 的数值");
        }
        if (volume.compareTo(new BigDecimal("99999")) > 0) {
            throw new BizException(400, "量(ml)超出合理范围(上限 99999)");
        }
        HisInpVisit visit = requireVisit(visitId);

        String route = trimToNull(params.get("route"));
        String note = trimToNull(params.get("note"));
        String recordTime = trimToNull(params.get("recordTime"));

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "INSERT INTO his_inp_io_record"
                        + " (visit_id, patient_id, record_time, io_type, category, volume, route, note,"
                        + "  nurse_id, tenant_id, create_by, create_time) VALUES (?, ?, ");
        if (recordTime != null) {
            sql.append("?");
            args.add(recordTime);
        } else {
            sql.append("NOW()");
        }
        sql.append(", ?, ?, ?, ?, ?, ?, ?, ?, NOW())");
        args.add(visit.getId());
        args.add(visit.getPatientId());
        args.add(ioType);
        args.add(category);
        args.add(volume);
        args.add(route);
        args.add(note);
        args.add(nurseId);
        args.add(tenantId());
        args.add(currentUserName());
        jdbcTemplate.update(sql.toString(), args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitId", visit.getId());
        out.put("ioType", ioType);
        out.put("category", category);
        out.put("volume", volume);
        return out;
    }

    /** 当日记录列表(按记录时间升序), 附登记护士姓名。 */
    public List<Map<String, Object>> list(Long visitId, String date) {
        HisInpVisit visit = requireVisit(visitId);
        String day = requireDate(date);
        return jdbcTemplate.queryForList(
                "SELECT r.id, r.visit_id AS visitId, DATE_FORMAT(r.record_time, '%Y-%m-%d %H:%i') AS recordTime,"
                        + " r.io_type AS ioType, r.category, r.volume, r.route, r.note,"
                        + " s.staff_name AS nurseName, r.create_by AS createBy"
                        + " FROM his_inp_io_record r"
                        + " LEFT JOIN his_staff s ON r.nurse_id = s.id AND s.deleted = 0"
                        + " WHERE r.visit_id = ? AND DATE(r.record_time) = ? AND r.deleted = 0 AND r.tenant_id = ?"
                        + " ORDER BY r.record_time ASC, r.id ASC",
                visit.getId(), day, tenantId());
    }

    /**
     * 日汇总: 按 io_type 分组求和, 返回 {date, intakeTotal, outputTotal, balance};
     * balance = intakeTotal - outputTotal(负值为出量大于进量, 前端红色提示)。
     */
    public Map<String, Object> getDailySummary(Long visitId, String date) {
        HisInpVisit visit = requireVisit(visitId);
        String day = requireDate(date);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT io_type, SUM(volume) AS total FROM his_inp_io_record"
                        + " WHERE visit_id = ? AND DATE(record_time) = ? AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY io_type",
                visit.getId(), day, tenantId());
        BigDecimal intake = BigDecimal.ZERO;
        BigDecimal output = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Object total = row.get("total");
            BigDecimal v = total == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(total));
            Object type = row.get("io_type");
            if (type != null && Integer.parseInt(String.valueOf(type)) == IO_OUTPUT) {
                output = output.add(v);
            } else {
                intake = intake.add(v);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day);
        out.put("intakeTotal", intake.setScale(1, RoundingMode.HALF_UP));
        out.put("outputTotal", output.setScale(1, RoundingMode.HALF_UP));
        out.put("balance", intake.subtract(output).setScale(1, RoundingMode.HALF_UP));
        return out;
    }

    /** 逻辑删除(deleted=1), 校验记录归属机构; 汇总口径自动排除。 */
    @Transactional
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "记录ID不能为空");
        }
        List<Map<String, Object>> found = jdbcTemplate.queryForList(
                "SELECT id, visit_id FROM his_inp_io_record WHERE id = ? AND deleted = 0", id);
        if (found.isEmpty()) {
            throw new BizException(404, "出入量记录不存在");
        }
        Long visitId = toLong(found.get(0).get("visit_id"));
        if (visitId != null) {
            requireVisit(visitId);
        }
        int n = jdbcTemplate.update(
                "UPDATE his_inp_io_record SET deleted = 1, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                currentUserName(), id, tenantId());
        if (n == 0) {
            throw new BizException(409, "记录已被删除, 请刷新后重试");
        }
    }

    /* ================= 内部工具 ================= */

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行)。 */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (!u.hasRole(Roles.SUPER_ADMIN) && visit.getOrgId() != null && u.getOrgId() != null
                && !visit.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该就诊不属于当前登录机构, 无权操作");
        }
        return visit;
    }

    /** 日期参数校验(yyyy-MM-dd, 非法抛 400)。 */
    private static String requireDate(String date) {
        if (!StringUtils.hasText(date)) {
            throw new BizException(400, "日期不能为空(yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(date.trim()).toString();
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(应为 yyyy-MM-dd)");
        }
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
        Long v = toLong(o);
        return v == null ? null : v.intValue();
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        try {
            return new BigDecimal(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(Object o) {
        if (o == null) {
            return null;
        }
        String t = o.toString().trim();
        return t.isEmpty() ? null : t;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }
}
