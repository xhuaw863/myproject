package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingTransferDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingTransfer;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingTransferMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 护理转运交接单服务(P4c): 转科/手术/血透/介入/内镜转运的核查单交接闭环。
 * 说明:
 * 1) 表 his_nursing_transfer 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离;
 * 2) 生命周期状态机: 创建(status=0 草稿, 交出人=当前登录职工) → 交接(status=1 已交接,
 *    回填接收人/交接时间) → 确认(status=2 已确认, 接收方二次点验);
 *    只有草稿可交接, 只有已交接可确认; checklist 缺省按 transfer_type 套用默认核查模板;
 * 3) transfer_type 限固定枚举集: dept_transfer/surgery/hemodialysis/intervention/endoscopy;
 * 4) 写操作校验就诊/记录归属机构与当前登录机构一致(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingTransferService {

    /** 状态: 0草稿 1已交接 2已确认 */
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_HANDED_OVER = 1;
    public static final int STATUS_CONFIRMED = 2;

    /** 转运类型固定集(与 his_nursing_transfer.transfer_type 注释口径一致) */
    public static final Set<String> TRANSFER_TYPES = new HashSet<>(Arrays.asList(
            "dept_transfer", "surgery", "hemodialysis", "intervention", "endoscopy"));

    private final HisNursingTransferMapper transferMapper;
    private final HisInpVisitMapper visitMapper;
    private final SafeJsonTool safeJsonTool;

    public NursingTransferService(HisNursingTransferMapper transferMapper, HisInpVisitMapper visitMapper,
                                  SafeJsonTool safeJsonTool) {
        this.transferMapper = transferMapper;
        this.visitMapper = visitMapper;
        this.safeJsonTool = safeJsonTool;
    }

    /* ================= 创建 / 交接 / 确认 ================= */

    /**
     * 创建交接单: 校验就诊归属与字段合法性 → checklist 缺省套用类型默认核查模板 →
     * 落库(status=0 草稿, 交出人=当前登录职工)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingTransfer create(NursingTransferDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        validateCreate(dto);

        Long patientId = dto.getPatientId() != null ? dto.getPatientId() : visit.getPatientId();
        if (patientId == null) {
            throw new BizException(400, "就诊记录缺少患者信息, 无法创建交接单");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }

        HisNursingTransfer t = new HisNursingTransfer();
        t.setOrgId(visit.getOrgId());
        t.setInpVisitId(visit.getId());
        t.setPatientId(patientId);
        t.setTransferType(dto.getTransferType().trim());
        String checklist = StringUtils.hasText(dto.getChecklist())
                ? dto.getChecklist().trim()
                : safeJsonTool.toJson(getChecklistTemplate(dto.getTransferType().trim()));
        t.setChecklist(checklist);
        t.setSenderId(u.getStaffId() != null ? u.getStaffId() : u.getUserId());
        t.setSenderName(u.getRealName());
        t.setFromDeptId(dto.getFromDeptId() != null ? dto.getFromDeptId() : visit.getDeptId());
        t.setToDeptId(dto.getToDeptId());
        t.setStatus(STATUS_DRAFT);
        t.setNote(trimToNull(dto.getNote()));
        transferMapper.insert(t);
        return t;
    }

    /**
     * 交接: 草稿单回填接收人/交接时间并置 status=1(交出方点击)。
     * 接收人尚不可用时也可先交接后确认(确认时以实际接班人复核)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingTransfer handover(Long id, Long receiverId, String receiverName) {
        if (receiverId == null && !StringUtils.hasText(receiverName)) {
            throw new BizException(400, "接收人不能为空");
        }
        HisNursingTransfer t = requireStatus(id, STATUS_DRAFT, "交接", "草稿");
        t.setReceiverId(receiverId);
        t.setReceiverName(trimToNull(receiverName));
        t.setHandoverTime(LocalDateTime.now());
        t.setStatus(STATUS_HANDED_OVER);
        transferMapper.updateById(t);
        return t;
    }

    /** 确认: 已交接单由接收方点验后置 status=2, 闭环留痕。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingTransfer confirm(Long id) {
        HisNursingTransfer t = requireStatus(id, STATUS_HANDED_OVER, "确认", "已交接");
        t.setStatus(STATUS_CONFIRMED);
        transferMapper.updateById(t);
        return t;
    }

    /* ================= 查询 ================= */

    /** 交接单列表(按就诊): 状态升序(草稿/已交接在前), 交接时间倒序(最新在前)。 */
    public List<HisNursingTransfer> listByVisit(Long inpVisitId) {
        requireVisit(inpVisitId);
        return transferMapper.selectList(Wrappers.<HisNursingTransfer>lambdaQuery()
                .eq(HisNursingTransfer::getInpVisitId, inpVisitId)
                .orderByAsc(HisNursingTransfer::getStatus)
                .orderByDesc(HisNursingTransfer::getHandoverTime)
                .orderByDesc(HisNursingTransfer::getId));
    }

    /** 交接单详情。 */
    public HisNursingTransfer getById(Long id) {
        if (id == null) {
            throw new BizException(400, "交接单ID不能为空");
        }
        HisNursingTransfer t = transferMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "交接单不存在");
        }
        requireSameOrg(t.getOrgId());
        return t;
    }

    /**
     * 交接核查模板(按类型): 返回 [{item, checked:false}] 结构, 供前端勾选面板直接渲染。
     * - dept_transfer 转科: 意识/瞳孔/生命体征/皮肤/管道/药物/病历/物品
     * - surgery 手术: 术前准备/禁食/备皮/静脉通路/知情同意/过敏/影像
     * - hemodialysis 血透: 干体重/血管通路/抗凝/检验报告/特殊医嘱
     * - intervention/endoscopy 复用转科模板(同为核心转运核查面)。
     */
    public List<Map<String, Object>> getChecklistTemplate(String transferType) {
        String type = StringUtils.hasText(transferType) ? transferType.trim() : "";
        if (!TRANSFER_TYPES.contains(type)) {
            throw new BizException(400, "转运类型无效: " + transferType
                    + "(有效值: dept_transfer/surgery/hemodialysis/intervention/endoscopy)");
        }
        String[] items;
        switch (type) {
            case "surgery":
                items = new String[]{"术前准备", "禁食", "备皮", "静脉通路", "知情同意", "过敏", "影像"};
                break;
            case "hemodialysis":
                items = new String[]{"干体重", "血管通路", "抗凝", "检验报告", "特殊医嘱"};
                break;
            default:
                items = new String[]{"意识", "瞳孔", "生命体征", "皮肤", "管道", "药物", "病历", "物品"};
                break;
        }
        List<Map<String, Object>> out = new ArrayList<>(items.length);
        for (String item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", item);
            row.put("checked", false);
            out.add(row);
        }
        return out;
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 创建字段校验: 类型限固定枚举集, 科室ID一致性, 备注 ≤500 字。 */
    private static void validateCreate(NursingTransferDTO dto) {
        if (!StringUtils.hasText(dto.getTransferType())) {
            throw new BizException(400, "转运类型不能为空");
        }
        if (!TRANSFER_TYPES.contains(dto.getTransferType().trim())) {
            throw new BizException(400, "转运类型无效: " + dto.getTransferType()
                    + "(有效值: dept_transfer/surgery/hemodialysis/intervention/endoscopy)");
        }
        if (dto.getFromDeptId() != null && dto.getToDeptId() != null
                && dto.getFromDeptId().equals(dto.getToDeptId())) {
            throw new BizException(400, "转出科室与转入科室相同, 无需交接");
        }
        if (dto.getNote() != null && dto.getNote().trim().length() > 500) {
            throw new BizException(400, "备注不能超过500字");
        }
    }

    /** 指定状态必读: 存在 + 归属机构一致 + 状态匹配(交接/确认前置校验)。 */
    private HisNursingTransfer requireStatus(Long id, int expectStatus, String action, String statusLabel) {
        if (id == null) {
            throw new BizException(400, "交接单ID不能为空");
        }
        HisNursingTransfer t = transferMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "交接单不存在");
        }
        requireSameOrg(t.getOrgId());
        if (t.getStatus() == null || t.getStatus() != expectStatus) {
            throw new BizException(400, "仅" + statusLabel + "交接单可" + action + "(当前状态: "
                    + statusName(t.getStatus()) + ")");
        }
        return t;
    }

    /** 状态中文名(报错展示) */
    private static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_DRAFT:
                return "草稿";
            case STATUS_HANDED_OVER:
                return "已交接";
            case STATUS_CONFIRMED:
                return "已确认";
            default:
                return "未知(" + status + ")";
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
            throw new BizException(403, "该交接单不属于当前登录机构, 无权操作");
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
