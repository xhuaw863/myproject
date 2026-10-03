package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingIoRecordDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingIoRecord;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingIoRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 护理出入量服务(P4b-2): 逐条入量/出量录入 + 24 小时出入量汇总 + 自定义时段统计。
 * 说明:
 * 1) 表 his_nursing_io_record 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离
 *    (tenant_id 由租户插件自动注入/过滤);
 * 2) 录入校验: ioType 限 1入量/2出量, volumeMl 必填且 0~99999ml, itemName 必填(≤100字),
 *    itemCategory 非空时须命中固定分类集(防拼写错误破坏汇总分组), patientId 缺省从就诊主表回填;
 * 3) 汇总口径: 按 ioType 分组求和(volume_ml), 组内再按 item_category 分组;
 *    分类为空的记录归入 other 桶, balance = 总入量 - 总出量;
 * 4) 写操作校验就诊/记录归属机构与当前登录机构一致(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingIoService {

    /** 类型: 1入量 2出量 */
    public static final int IO_INTAKE = 1;
    public static final int IO_OUTPUT = 2;

    /** 项目分类固定集(与 his_nursing_io_record.item_category 注释口径一致) */
    public static final Set<String> ITEM_CATEGORIES = new HashSet<>(Arrays.asList(
            "infusion", "oral", "urine", "drain", "gastric", "vomit", "stool", "blood", "other"));

    /** 单条/批量录入量上限(ml, 与生命体征尿量/引流量口径一致) */
    private static final int MAX_VOLUME_ML = 99999;
    /** 批量录入单次上限 */
    private static final int MAX_BATCH_SIZE = 100;
    /** 分类缺省桶(item_category 为空的记录归入) */
    private static final String CATEGORY_OTHER = "other";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisNursingIoRecordMapper ioRecordMapper;
    private final HisInpVisitMapper visitMapper;

    public NursingIoService(HisNursingIoRecordMapper ioRecordMapper, HisInpVisitMapper visitMapper) {
        this.ioRecordMapper = ioRecordMapper;
        this.visitMapper = visitMapper;
    }

    /* ================= 录入 ================= */

    /**
     * 录入单条出入量: 校验就诊归属与字段合法性 → 落库。
     * recordTime 缺省当前, patientId 缺省从就诊主表回填。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingIoRecord record(NursingIoRecordDTO dto) {
        return doRecord(dto);
    }

    /**
     * 批量录入(护士站逐项补录/多床巡回): 逐条走同一校验, 单条失败整体回滚(报错标注第几条)。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisNursingIoRecord> batchRecord(List<NursingIoRecordDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            throw new BizException(400, "批量录入清单不能为空");
        }
        if (dtos.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "单次批量录入最多 " + MAX_BATCH_SIZE + " 条");
        }
        HisNursingIoRecord[] out = new HisNursingIoRecord[dtos.size()];
        for (int i = 0; i < dtos.size(); i++) {
            try {
                out[i] = doRecord(dtos.get(i));
            } catch (BizException e) {
                throw new BizException(e.getCode(), "第 " + (i + 1) + " 条: " + e.getMessage());
            }
        }
        return Arrays.asList(out);
    }

    /** 单条录入主体(校验→组装→落库) */
    private HisNursingIoRecord doRecord(NursingIoRecordDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        validateItem(dto);

        Long patientId = dto.getPatientId() != null ? dto.getPatientId() : visit.getPatientId();
        if (patientId == null) {
            throw new BizException(400, "就诊记录缺少患者信息, 无法录入出入量");
        }

        HisNursingIoRecord r = new HisNursingIoRecord();
        r.setOrgId(visit.getOrgId());
        r.setInpVisitId(visit.getId());
        r.setPatientId(patientId);
        r.setRecordTime(dto.getRecordTime() != null ? dto.getRecordTime() : LocalDateTime.now());
        r.setIoType(dto.getIoType());
        r.setItemName(dto.getItemName().trim());
        r.setItemCategory(trimToNull(dto.getItemCategory()));
        r.setVolumeMl(dto.getVolumeMl());
        r.setRoute(trimToNull(dto.getRoute()));
        r.setNote(trimToNull(dto.getNote()));
        ioRecordMapper.insert(r);
        return r;
    }

    /* ================= 查询/汇总 ================= */

    /**
     * 出入量列表(按就诊): 可选时间范围过滤(闭区间), 记录时间倒序(最新在前)。
     */
    public List<HisNursingIoRecord> listByVisit(Long inpVisitId, LocalDateTime start, LocalDateTime end) {
        requireVisit(inpVisitId);
        if (start != null && end != null && start.isAfter(end)) {
            throw new BizException(400, "开始时间不能晚于结束时间");
        }
        return ioRecordMapper.selectList(Wrappers.<HisNursingIoRecord>lambdaQuery()
                .eq(HisNursingIoRecord::getInpVisitId, inpVisitId)
                .ge(start != null, HisNursingIoRecord::getRecordTime, start)
                .le(end != null, HisNursingIoRecord::getRecordTime, end)
                .orderByDesc(HisNursingIoRecord::getRecordTime)
                .orderByDesc(HisNursingIoRecord::getId));
    }

    /**
     * 24 小时出入量汇总(按自然日): date 缺省当日。
     * 返回 {date, totalIntake, totalOutput, balance, intakeByCategory, outputByCategory,
     *       intakeCount, outputCount, recordCount}; 分类为空归 other 桶。
     */
    public Map<String, Object> summary24h(Long inpVisitId, LocalDate date) {
        HisInpVisit visit = requireVisit(inpVisitId);
        LocalDate d = date != null ? date : LocalDate.now();
        return buildSummary(visit, d.atStartOfDay(), d.atTime(23, 59, 59));
    }

    /**
     * 自定义时段出入量统计: start/end 必填(闭区间), 返回结构同 24 小时汇总
     * (以 start/end 替代 date 键)。
     */
    public Map<String, Object> summaryRange(Long inpVisitId, LocalDateTime start, LocalDateTime end) {
        HisInpVisit visit = requireVisit(inpVisitId);
        if (start == null || end == null) {
            throw new BizException(400, "统计起止时间不能为空");
        }
        if (start.isAfter(end)) {
            throw new BizException(400, "开始时间不能晚于结束时间");
        }
        return buildSummary(visit, start, end);
    }

    /** 汇总主体: 按入量/出量分组求和, 组内按项目分类分组(分类空归 other; TreeMap 保证键序稳定) */
    private Map<String, Object> buildSummary(HisInpVisit visit, LocalDateTime start, LocalDateTime end) {
        List<HisNursingIoRecord> rows = ioRecordMapper.selectList(
                Wrappers.<HisNursingIoRecord>lambdaQuery()
                        .eq(HisNursingIoRecord::getInpVisitId, visit.getId())
                        .ge(HisNursingIoRecord::getRecordTime, start)
                        .le(HisNursingIoRecord::getRecordTime, end)
                        .orderByAsc(HisNursingIoRecord::getRecordTime)
                        .orderByAsc(HisNursingIoRecord::getId));
        Map<String, Integer> intakeByCategory = new TreeMap<>();
        Map<String, Integer> outputByCategory = new TreeMap<>();
        int totalIntake = 0;
        int totalOutput = 0;
        int intakeCount = 0;
        int outputCount = 0;
        for (HisNursingIoRecord r : rows) {
            int volume = r.getVolumeMl() == null ? 0 : r.getVolumeMl();
            String category = StringUtils.hasText(r.getItemCategory()) ? r.getItemCategory().trim() : CATEGORY_OTHER;
            if (r.getIoType() != null && r.getIoType() == IO_INTAKE) {
                totalIntake += volume;
                intakeCount++;
                intakeByCategory.merge(category, volume, Integer::sum);
            } else if (r.getIoType() != null && r.getIoType() == IO_OUTPUT) {
                totalOutput += volume;
                outputCount++;
                outputByCategory.merge(category, volume, Integer::sum);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", start.toLocalDate().toString());
        out.put("start", TIME_FMT.format(start));
        out.put("end", TIME_FMT.format(end));
        out.put("totalIntake", totalIntake);
        out.put("totalOutput", totalOutput);
        out.put("balance", totalIntake - totalOutput);
        out.put("intakeByCategory", intakeByCategory);
        out.put("outputByCategory", outputByCategory);
        out.put("intakeCount", intakeCount);
        out.put("outputCount", outputCount);
        out.put("recordCount", rows.size());
        return out;
    }

    /* ================= 删除 ================= */

    /** 删除单条出入量记录(逻辑删除, 误录/补录场景): 校验记录归属机构后删除。 */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "记录ID不能为空");
        }
        HisNursingIoRecord r = ioRecordMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "出入量记录不存在");
        }
        requireSameOrg(r.getOrgId());
        ioRecordMapper.deleteById(id);
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 录入字段校验: 类型限 1入量/2出量, 量必填 0~99999ml, 项目名必填 ≤100 字, 分类须命中固定集。 */
    private static void validateItem(NursingIoRecordDTO dto) {
        if (dto.getIoType() == null || (dto.getIoType() != IO_INTAKE && dto.getIoType() != IO_OUTPUT)) {
            throw new BizException(400, "出入量类型无效(1入量 2出量)");
        }
        if (!StringUtils.hasText(dto.getItemName())) {
            throw new BizException(400, "项目名称不能为空");
        }
        if (dto.getItemName().trim().length() > 100) {
            throw new BizException(400, "项目名称不能超过100字");
        }
        if (dto.getVolumeMl() == null) {
            throw new BizException(400, "量(ml)不能为空");
        }
        if (dto.getVolumeMl() < 0 || dto.getVolumeMl() > MAX_VOLUME_ML) {
            throw new BizException(400, "量(ml)超出有效范围(0~" + MAX_VOLUME_ML + ")");
        }
        if (StringUtils.hasText(dto.getItemCategory())
                && !ITEM_CATEGORIES.contains(dto.getItemCategory().trim())) {
            throw new BizException(400, "项目分类无效: " + dto.getItemCategory()
                    + "(有效值: infusion/oral/urine/drain/gastric/vomit/stool/blood/other)");
        }
        if (dto.getRoute() != null && dto.getRoute().trim().length() > 50) {
            throw new BizException(400, "途径不能超过50字");
        }
        if (dto.getNote() != null && dto.getNote().trim().length() > 500) {
            throw new BizException(400, "备注不能超过500字");
        }
    }

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行), 沿用住院护士站口径。 */
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

    /** 写操作机构校验: 记录归属机构须与当前登录机构一致(平台超管放行)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该出入量记录不属于当前登录机构, 无权操作");
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
