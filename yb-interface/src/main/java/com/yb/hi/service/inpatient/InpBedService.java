package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.entity.inpatient.HisBed;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisBedMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院病区/床位服务: 病区与床位 CRUD、床位占用状态机(0空床/1占用/2停用)、床位一览统计。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class InpBedService {

    private final HisWardMapper wardMapper;
    private final HisBedMapper bedMapper;
    private final JdbcTemplate jdbcTemplate;

    public InpBedService(HisWardMapper wardMapper, HisBedMapper bedMapper, JdbcTemplate jdbcTemplate) {
        this.wardMapper = wardMapper;
        this.bedMapper = bedMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 病区 ==================== */

    /** 病区列表(orgId=null 时查全医共体, 牵头机构传入) */
    public List<HisWard> wardList(Long orgId) {
        LambdaQueryWrapper<HisWard> q = new LambdaQueryWrapper<>();
        q.eq(orgId != null, HisWard::getOrgId, orgId)
                .orderByAsc(HisWard::getId);
        return wardMapper.selectList(q);
    }

    /** 新增/编辑病区(id=null 新增; 编辑校验归属机构, 防跨机构篡改) */
    @Transactional(rollbackFor = Exception.class)
    public HisWard saveWard(HisWard ward, Long orgId) {
        if (ward == null || !StringUtils.hasText(ward.getWardName())) {
            throw new BizException(400, "病区名称不能为空");
        }
        if (ward.getId() == null) {
            ward.setOrgId(orgId);
            if (ward.getStatus() == null) {
                ward.setStatus(1);
            }
            wardMapper.insert(ward);
            log.info("新增病区: id={}, name={}, orgId={}", ward.getId(), ward.getWardName(), orgId);
            return ward;
        }
        HisWard exist = wardMapper.selectById(ward.getId());
        if (exist == null) {
            throw new BizException(404, "病区不存在或已删除");
        }
        if (!orgId.equals(exist.getOrgId())) {
            throw new BizException(403, "不能修改其他机构的病区");
        }
        ward.setOrgId(orgId);
        // updateById 忽略 null 字段, 未传字段保持原值
        wardMapper.updateById(ward);
        return wardMapper.selectById(ward.getId());
    }

    /** 删除病区(逻辑删; 病区下仍有床位时禁止删除) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteWard(Long wardId) {
        HisWard exist = wardMapper.selectById(wardId);
        if (exist == null) {
            throw new BizException(404, "病区不存在或已删除");
        }
        long bedCnt = bedMapper.selectCount(new LambdaQueryWrapper<HisBed>().eq(HisBed::getWardId, wardId));
        if (bedCnt > 0) {
            throw new BizException("病区下存在 " + bedCnt + " 张床位, 请先删除或迁移床位后再删除病区");
        }
        wardMapper.deleteById(wardId);
        log.info("删除病区: id={}, name={}", wardId, exist.getWardName());
    }

    /* ==================== 床位 ==================== */

    /** 床位列表(wardId=null 时查机构全部床位) */
    public List<HisBed> bedList(Long wardId, Long orgId) {
        LambdaQueryWrapper<HisBed> q = new LambdaQueryWrapper<>();
        q.eq(orgId != null, HisBed::getOrgId, orgId)
                .eq(wardId != null, HisBed::getWardId, wardId)
                .orderByAsc(HisBed::getWardId).orderByAsc(HisBed::getBedNo);
        return bedMapper.selectList(q);
    }

    /** 新增/编辑床位(id=null 新增; 编辑校验归属机构; 床位号同病区内唯一) */
    @Transactional(rollbackFor = Exception.class)
    public HisBed saveBed(HisBed bed, Long orgId) {
        if (bed == null || !StringUtils.hasText(bed.getBedNo())) {
            throw new BizException(400, "床位号不能为空");
        }
        if (bed.getWardId() == null) {
            throw new BizException(400, "床位必须归属病区");
        }
        HisWard ward = wardMapper.selectById(bed.getWardId());
        if (ward == null) {
            throw new BizException(404, "归属病区不存在或已删除");
        }
        if (bed.getId() == null) {
            if (!orgId.equals(ward.getOrgId())) {
                throw new BizException(403, "床位归属病区不属于本机构");
            }
            long dupCnt = bedMapper.selectCount(new LambdaQueryWrapper<HisBed>()
                    .eq(HisBed::getWardId, bed.getWardId())
                    .eq(HisBed::getBedNo, bed.getBedNo().trim()));
            if (dupCnt > 0) {
                throw new BizException("病区内床位号已存在: " + bed.getBedNo());
            }
            bed.setOrgId(orgId);
            if (bed.getStatus() == null) {
                bed.setStatus(0);
            }
            // 新增床位不允许直接带入占用信息(占用由入院链路回写)
            if (bed.getStatus() == 1) {
                bed.setStatus(0);
            }
            bed.setPatientId(null);
            bed.setInpVisitId(null);
            bedMapper.insert(bed);
            log.info("新增床位: id={}, bedNo={}, wardId={}", bed.getId(), bed.getBedNo(), bed.getWardId());
            return bed;
        }
        HisBed exist = bedMapper.selectById(bed.getId());
        if (exist == null) {
            throw new BizException(404, "床位不存在或已删除");
        }
        if (!orgId.equals(exist.getOrgId())) {
            throw new BizException(403, "不能修改其他机构的床位");
        }
        if (!orgId.equals(ward.getOrgId())) {
            throw new BizException(403, "床位归属病区不属于本机构");
        }
        // 床位号变更时校验同病区唯一
        if (StringUtils.hasText(bed.getBedNo()) && !bed.getBedNo().trim().equals(exist.getBedNo())) {
            long dupCnt = bedMapper.selectCount(new LambdaQueryWrapper<HisBed>()
                    .eq(HisBed::getWardId, exist.getWardId())
                    .eq(HisBed::getBedNo, bed.getBedNo().trim())
                    .ne(HisBed::getId, bed.getId()));
            if (dupCnt > 0) {
                throw new BizException("病区内床位号已存在: " + bed.getBedNo());
            }
        }
        // 占用信息(status/patient_id/inp_visit_id)由入院/出院/转床链路维护, 编辑不允许覆盖
        bed.setOrgId(orgId);
        bed.setStatus(null);
        bed.setPatientId(null);
        bed.setInpVisitId(null);
        bedMapper.updateById(bed);
        return bedMapper.selectById(bed.getId());
    }

    /** 启用/停用床位(空床0<->停用2 翻转; 占用中禁止操作, 需先转床或出院) */
    @Transactional(rollbackFor = Exception.class)
    public HisBed toggleBedStatus(Long bedId) {
        HisBed exist = bedMapper.selectById(bedId);
        if (exist == null) {
            throw new BizException(404, "床位不存在或已删除");
        }
        if (exist.getStatus() != null && exist.getStatus() == 1) {
            throw new BizException("床位占用中, 请先转床或办理出院后再停用/启用");
        }
        int target = (exist.getStatus() != null && exist.getStatus() == 2) ? 0 : 2;
        int affected = jdbcTemplate.update(
                "UPDATE his_bed SET status = ?, update_time = NOW() WHERE id = ? AND deleted = 0 AND tenant_id = ?"
                        + " AND (status = 0 OR status = 2)",
                target, bedId, tenantId());
        if (affected == 0) {
            throw new BizException("床位状态已变化, 请刷新后重试");
        }
        return bedMapper.selectById(bedId);
    }

    /**
     * 床位一览(按病区分组统计): {wardId, wardName, total, empty, occupied, disabled}。
     * 停用病区也统计(便于维护界面看全量), 空值 COALESCE 兜底。
     */
    public List<Map<String, Object>> bedOverview(Long orgId) {
        StringBuilder sql = new StringBuilder(
                "SELECT w.id ward_id, w.ward_name, w.ward_code, w.status ward_status,"
                        + " COUNT(b.id) total,"
                        + " COALESCE(SUM(CASE WHEN b.status = 0 THEN 1 ELSE 0 END), 0) empty,"
                        + " COALESCE(SUM(CASE WHEN b.status = 1 THEN 1 ELSE 0 END), 0) occupied,"
                        + " COALESCE(SUM(CASE WHEN b.status = 2 THEN 1 ELSE 0 END), 0) disabled"
                        + " FROM his_ward w"
                        + " LEFT JOIN his_bed b ON b.ward_id = w.id AND b.deleted = 0"
                        + " WHERE w.deleted = 0 AND w.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            sql.append(" AND w.org_id = ?");
            args.add(orgId);
        }
        sql.append(" GROUP BY w.id, w.ward_name, w.ward_code, w.status ORDER BY w.id");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wardId", row.get("ward_id"));
            m.put("wardName", row.get("ward_name"));
            m.put("wardCode", row.get("ward_code"));
            m.put("wardStatus", row.get("ward_status"));
            m.put("total", ((Number) row.get("total")).longValue());
            m.put("empty", ((Number) row.get("empty")).longValue());
            m.put("occupied", ((Number) row.get("occupied")).longValue());
            m.put("disabled", ((Number) row.get("disabled")).longValue());
            result.add(m);
        }
        return result;
    }

    /* ==================== 床位等级/分级统计(模型增强) ==================== */

    /** 更新床位等级(1普通 2单间 3监护 4特需; 占用中不限制修改, 等级仅用于分级统计与择优推荐) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateBedLevel(Long id, Integer bedLevel) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (bedLevel == null || bedLevel < 1 || bedLevel > 4) {
            throw new BizException(400, "床位等级不合法(1普通 2单间 3监护 4特需)");
        }
        HisBed exist = bedMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "床位不存在或已删除");
        }
        bedMapper.update(null, new LambdaUpdateWrapper<HisBed>()
                .eq(HisBed::getId, id)
                .set(HisBed::getBedLevel, bedLevel));
        log.info("更新床位等级: id={}, bedNo={}, level={}", id, exist.getBedNo(), bedLevel);
        return R.ok();
    }

    /**
     * 床位分级统计(按床位等级分组): {items:[{bedLevel,bedLevelName,total,empty,occupied,disabled}], 汇总四元组}。
     * wardId=null 时统计租户全部床位; bed_level 为空按普通(1)归并。
     */
    public R<Map<String, Object>> getBedStatsByLevel(Long wardId) {
        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(bed_level, 1) bed_level, COUNT(*) total,"
                        + " COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) empty,"
                        + " COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) occupied,"
                        + " COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) disabled"
                        + " FROM his_bed WHERE deleted = 0 AND tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (wardId != null) {
            sql.append(" AND ward_id = ?");
            args.add(wardId);
        }
        sql.append(" GROUP BY IFNULL(bed_level, 1) ORDER BY bed_level");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        long total = 0L;
        long empty = 0L;
        long occupied = 0L;
        long disabled = 0L;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long t = ((Number) row.get("total")).longValue();
            long e = ((Number) row.get("empty")).longValue();
            long o = ((Number) row.get("occupied")).longValue();
            long d = ((Number) row.get("disabled")).longValue();
            total += t;
            empty += e;
            occupied += o;
            disabled += d;
            Map<String, Object> item = new LinkedHashMap<>();
            Integer lv = row.get("bed_level") == null ? null : ((Number) row.get("bed_level")).intValue();
            item.put("bedLevel", lv);
            item.put("bedLevelName", bedLevelName(lv));
            item.put("total", t);
            item.put("empty", e);
            item.put("occupied", o);
            item.put("disabled", d);
            items.add(item);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("wardId", wardId);
        stats.put("total", total);
        stats.put("empty", empty);
        stats.put("occupied", occupied);
        stats.put("disabled", disabled);
        stats.put("items", items);
        return R.ok(stats);
    }

    /** 床位等级名称 */
    private String bedLevelName(Integer level) {
        if (level == null) {
            return "普通";
        }
        switch (level) {
            case 1:
                return "普通";
            case 2:
                return "单间";
            case 3:
                return "监护";
            case 4:
                return "特需";
            default:
                return "未知";
        }
    }

    /* ==================== 占用/释放(入院/转床/出院/取消链路回写) ==================== */

    /**
     * 占用床位(乐观锁): UPDATE ... SET status=1 WHERE id=? AND status=0,
     * affected=0 说明床位已被抢占/停用, 抛异常回滚整个业务操作。
     */
    public void allocateBed(Long bedId, Long patientId, Long visitId) {
        if (bedId == null) {
            throw new BizException(400, "床位ID不能为空");
        }
        // 性别限制校验(仅配置了限制的床位): gender_limit 0无 1男 2女
        if (patientId != null) {
            HisBed bed = bedMapper.selectById(bedId);
            if (bed == null) {
                throw new BizException(404, "床位不存在或已删除");
            }
            Integer limit = bed.getGenderLimit();
            if (limit != null && limit != 0) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT gender, gender_name FROM his_patient WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                        patientId, tenantId());
                if (rows.isEmpty()) {
                    throw new BizException(404, "患者档案不存在, 无法核对床位性别限制");
                }
                String genderCode = rows.get(0).get("gender") == null ? null
                        : String.valueOf(rows.get(0).get("gender"));
                String genderName = rows.get(0).get("gender_name") == null ? null
                        : String.valueOf(rows.get(0).get("gender_name"));
                String expectName = limit == 1 ? "男" : "女";
                String expectCode = limit == 1 ? "1" : "2";
                boolean matched = (genderName != null && genderName.contains(expectName))
                        || expectCode.equals(genderCode);
                if (!matched) {
                    throw new BizException("床位【" + bed.getBedNo() + "】为" + expectName
                            + "性床位, 患者性别不符, 不能占用");
                }
            }
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_bed SET status = 1, patient_id = ?, inp_visit_id = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND deleted = 0 AND tenant_id = ?",
                patientId, visitId, bedId, tenantId());
        if (affected == 0) {
            throw new BizException("床位不可用或已被占用");
        }
    }

    /**
     * 释放床位(释放后回空床0): UPDATE ... SET status=0, patient_id=NULL WHERE id=? AND status=1。
     * affected=0 时兜底检查: 床位不存在抛异常; 已是空床(幂等重放)跳过。
     */
    public void releaseBed(Long bedId) {
        if (bedId == null) {
            return;
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_bed SET status = 0, patient_id = NULL, inp_visit_id = NULL, update_time = NOW()"
                        + " WHERE id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                bedId, tenantId());
        if (affected > 0) {
            return;
        }
        HisBed bed = bedMapper.selectById(bedId);
        if (bed == null) {
            throw new BizException(404, "床位不存在或已删除, 无法释放");
        }
        if (bed.getStatus() != null && bed.getStatus() == 0) {
            // 已是空床(如重复释放), 幂等跳过
            return;
        }
        throw new BizException("床位状态异常(status=" + bed.getStatus() + "), 无法释放");
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
