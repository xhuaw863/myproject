package com.yb.hi.service.treatment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.treatment.HisTreatmentEquipment;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.treatment.HisTreatmentEquipmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 治疗设备服务(设备台账): 列表(科室/关键字筛选) / 新增 / 编辑 / 逻辑删除。
 * 说明:
 * 1) his_treatment_equipment 为机构级台账(MP 租户插件自动注入 tenant_id); 单表读写走 Mapper,
 *    列表 JOIN his_dept 取科室名走 JdbcTemplate 并显式带 tenant_id;
 * 2) 设备编码同机构内唯一(新增/编辑校验); 状态 1正常 / 2维修 / 0停用;
 * 3) 删除为逻辑删(deleted=1, TableLogic); 存在待执行/执行中的治疗单引用该设备时拒绝删除;
 * 4) 写操作机构校验: 仅允许维护当前登录机构的数据(防跨机构误操作)。
 */
@Slf4j
@Service
public class TreatmentEquipmentService {

    private final HisTreatmentEquipmentMapper equipMapper;
    private final JdbcTemplate jdbcTemplate;

    public TreatmentEquipmentService(HisTreatmentEquipmentMapper equipMapper, JdbcTemplate jdbcTemplate) {
        this.equipMapper = equipMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 设备列表(机构内, 可按科室/关键字[编码或名称]筛选; 正常在前、新建设备在前) */
    public List<Map<String, Object>> list(Long orgId, Long deptId, String keyword) {
        Long oid = requireOrg(orgId);
        StringBuilder sql = new StringBuilder(
                "SELECT eq.id, eq.org_id, eq.equip_code, eq.equip_name, eq.equip_type, eq.dept_id, eq.status,"
                        + " DATE_FORMAT(eq.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
                        + " d.dept_name"
                        + " FROM his_treatment_equipment eq"
                        + " LEFT JOIN his_dept d ON d.id = eq.dept_id AND d.deleted = 0"
                        + " WHERE eq.deleted = 0 AND eq.tenant_id = ? AND eq.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (deptId != null) {
            sql.append(" AND eq.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (eq.equip_code LIKE ? OR eq.equip_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY CASE eq.status WHEN 1 THEN 0 WHEN 2 THEN 1 ELSE 2 END, eq.id DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 新增设备: 编码/名称必填, 编码同机构唯一, 状态默认正常(1) */
    @Transactional
    public HisTreatmentEquipment create(Long orgId, HisTreatmentEquipment req) {
        if (req == null) {
            throw new BizException(400, "设备信息不能为空");
        }
        Long oid = requireOrg(orgId);
        requireSameOrg(oid);
        String code = trimRequired(req.getEquipCode(), "设备编码不能为空");
        String name = trimRequired(req.getEquipName(), "设备名称不能为空");
        requireUniqueCode(oid, code, null);
        HisTreatmentEquipment eq = new HisTreatmentEquipment();
        eq.setOrgId(oid);
        eq.setEquipCode(code);
        eq.setEquipName(name);
        eq.setEquipType(trimToNull(req.getEquipType()));
        eq.setDeptId(req.getDeptId());
        eq.setStatus(validStatus(req.getStatus()));
        equipMapper.insert(eq);
        return eq;
    }

    /** 编辑设备: 名称必填; 编码变更时校验同机构唯一; 仅允许维护本机构设备 */
    @Transactional
    public HisTreatmentEquipment update(Long id, HisTreatmentEquipment req) {
        if (req == null) {
            throw new BizException(400, "设备信息不能为空");
        }
        HisTreatmentEquipment eq = requireEquip(id);
        requireSameOrg(eq.getOrgId());
        String name = trimRequired(req.getEquipName(), "设备名称不能为空");
        if (StringUtils.hasText(req.getEquipCode())) {
            String code = req.getEquipCode().trim();
            if (!code.equals(eq.getEquipCode())) {
                requireUniqueCode(eq.getOrgId(), code, id);
                eq.setEquipCode(code);
            }
        }
        eq.setEquipName(name);
        eq.setEquipType(trimToNull(req.getEquipType()));
        if (req.getDeptId() != null) {
            eq.setDeptId(req.getDeptId());
        }
        if (req.getStatus() != null) {
            eq.setStatus(validStatus(req.getStatus()));
        }
        equipMapper.updateById(eq);
        return eq;
    }

    /** 逻辑删除设备: 存在待执行/执行中治疗单引用时拒绝 */
    @Transactional
    public void delete(Long id) {
        HisTreatmentEquipment eq = requireEquip(id);
        requireSameOrg(eq.getOrgId());
        Integer using = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_treatment_exec"
                        + " WHERE equipment_code = ? AND org_id = ? AND exec_status IN (0, 1)"
                        + " AND tenant_id = ? AND deleted = 0",
                Integer.class, eq.getEquipCode(), eq.getOrgId(), tenantId());
        if (using != null && using > 0) {
            throw new BizException(400, "该设备存在待执行/执行中的治疗单, 无法删除(可先停用)");
        }
        equipMapper.deleteById(id);
        log.info("治疗设备删除: code={}, name={}, operator={}", eq.getEquipCode(), eq.getEquipName(), currentUserName());
    }

    /* ================= 内部工具 ================= */

    /** 设备必读(MP 自动租户过滤 + 逻辑删), 不存在抛 404 */
    private HisTreatmentEquipment requireEquip(Long id) {
        if (id == null) {
            throw new BizException(400, "设备ID不能为空");
        }
        HisTreatmentEquipment eq = equipMapper.selectById(id);
        if (eq == null) {
            throw new BizException(404, "治疗设备不存在");
        }
        return eq;
    }

    /** 设备编码同机构唯一(编辑时排除自身) */
    private void requireUniqueCode(Long orgId, String code, Long excludeId) {
        String sql = "SELECT COUNT(*) FROM his_treatment_equipment"
                + " WHERE equip_code = ? AND org_id = ? AND tenant_id = ? AND deleted = 0";
        List<Object> args = new ArrayList<>();
        args.add(code);
        args.add(orgId);
        args.add(tenantId());
        if (excludeId != null) {
            sql += " AND id <> ?";
            args.add(excludeId);
        }
        Integer cnt = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        if (cnt != null && cnt > 0) {
            throw new BizException(409, "设备编码已存在: " + code);
        }
    }

    /** 状态校验: 仅允许 1正常 / 2维修 / 0停用; 为空默认 1 */
    private static Integer validStatus(Integer status) {
        if (status == null) {
            return 1;
        }
        if (status != 0 && status != 1 && status != 2) {
            throw new BizException(400, "设备状态取值不正确(0停用/1正常/2维修)");
        }
        return status;
    }

    /** 写操作机构校验: 设备归属机构须与当前登录机构一致 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作治疗设备");
        }
        if (orgId != null && !orgId.equals(u.getOrgId())) {
            throw new BizException(403, "该设备不属于当前登录机构, 无权操作");
        }
    }

    /** 机构作用域: 入参为空回退当前登录机构 */
    private static Long requireOrg(Long orgId) {
        if (orgId != null) {
            return orgId;
        }
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作治疗设备");
        }
        return u.getOrgId();
    }

    private static String trimRequired(String v, String msg) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(400, msg);
        }
        return v.trim();
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
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
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }
}

