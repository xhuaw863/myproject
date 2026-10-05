package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.entity.ris.HisExamRequest;
import com.yb.hi.entity.ris.HisRisCloudIndex;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import com.yb.hi.mapper.ris.HisExamRequestMapper;
import com.yb.hi.mapper.ris.HisRisCloudIndexMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 医保影像云索引服务(his_ris_cloud_index)
 * 结算明细 × StudyUID 的索引上传台账: 报告审核后建索引(待上传) -> 影像云上报(成功/失败留痕)。
 * 索引取数口径: study_uid 取报告 pacs_study_uid(外部 PACS 挂接键), yb_exam_code 取申请单 exam_item_code(医保检查项目代码)。
 */
@Slf4j
@Service
public class RisCloudIndexService {

    /** 上传状态: 待上传 */
    public static final int ST_PENDING = 0;
    /** 上传状态: 已上传 */
    public static final int ST_UPLOADED = 1;
    /** 上传状态: 上传失败 */
    public static final int ST_FAILED = 2;

    private final HisRisCloudIndexMapper cloudIndexMapper;
    private final HisExamReportMapper examReportMapper;
    private final HisExamRequestMapper examRequestMapper;
    private final OrgAccessGuard orgAccessGuard;

    public RisCloudIndexService(HisRisCloudIndexMapper cloudIndexMapper,
                                HisExamReportMapper examReportMapper,
                                HisExamRequestMapper examRequestMapper,
                                OrgAccessGuard orgAccessGuard) {
        this.cloudIndexMapper = cloudIndexMapper;
        this.examReportMapper = examReportMapper;
        this.examRequestMapper = examRequestMapper;
        this.orgAccessGuard = orgAccessGuard;
    }

    /**
     * 创建影像云索引记录(报告审核后调用, 幂等: 同报告已有索引则返回既有记录)
     * - study_uid <- his_exam_report.pacs_study_uid(空 StudyUID 拒绝建索引, 影像云无影像可索引);
     * - yb_exam_code <- his_exam_request.exam_item_code。
     */
    public HisRisCloudIndex createIndex(Long reportId, Long requestId) {
        HisRisCloudIndex existing = getByReport(reportId);
        if (existing != null) {
            log.info("影像云索引已存在(幂等返回): reportId={}, indexId={}", reportId, existing.getId());
            return existing;
        }
        HisExamReport report = examReportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(404, "检查报告不存在: " + reportId);
        }
        HisExamRequest request = requestId != null ? examRequestMapper.selectById(requestId) : null;
        String studyUid = report.getPacsStudyUid();
        if (studyUid == null || studyUid.isEmpty()) {
            throw new BizException(400, "报告无 DICOM StudyUID, 无法创建影像云索引: " + reportId);
        }
        HisRisCloudIndex index = new HisRisCloudIndex();
        index.setOrgId(request != null && request.getOrgId() != null ? request.getOrgId() : report.getOrgId());
        index.setReportId(reportId);
        index.setRequestId(requestId);
        index.setYbExamCode(request != null ? request.getExamItemCode() : null);
        index.setStudyUid(studyUid);
        index.setUploadStatus(ST_PENDING);
        cloudIndexMapper.insert(index);
        log.info("影像云索引创建: reportId={}, requestId={}, studyUid={}, ybExamCode={}",
                reportId, requestId, studyUid, index.getYbExamCode());
        return index;
    }

    /** 标记为已上传(记录平台回执的云端索引ID与上传时间) */
    public void markUploaded(Long id, String cloudIndexId) {
        HisRisCloudIndex index = requireIndex(id);
        index.setUploadStatus(ST_UPLOADED);
        index.setCloudIndexId(cloudIndexId);
        index.setUploadTime(LocalDateTime.now());
        cloudIndexMapper.updateById(index);
        log.info("影像云索引已上传: id={}, cloudIndexId={}", id, cloudIndexId);
    }

    /** 标记上传失败(错误信息落 upload_response, 供重试与排查) */
    public void markFailed(Long id, String errorMsg) {
        HisRisCloudIndex index = requireIndex(id);
        index.setUploadStatus(ST_FAILED);
        index.setUploadResponse(errorMsg);
        index.setUploadTime(LocalDateTime.now());
        cloudIndexMapper.updateById(index);
        log.warn("影像云索引上传失败: id={}, errorMsg={}", id, errorMsg);
    }

    /** 待上传列表(upload_status=0, 机构边界 scopeOrgId: 牵头可看医共体全部/null, 非牵头锁定本机构) */
    public List<HisRisCloudIndex> listPending(Long orgId) {
        Long effOrgId = orgAccessGuard.scopeOrgId(orgId);
        LambdaQueryWrapper<HisRisCloudIndex> qw = new LambdaQueryWrapper<>();
        qw.eq(HisRisCloudIndex::getUploadStatus, ST_PENDING);
        if (effOrgId != null) {
            qw.eq(HisRisCloudIndex::getOrgId, effOrgId);
        }
        qw.orderByAsc(HisRisCloudIndex::getId);
        return cloudIndexMapper.selectList(qw);
    }

    /** 按报告查索引(无索引返回 null) */
    public HisRisCloudIndex getByReport(Long reportId) {
        LambdaQueryWrapper<HisRisCloudIndex> qw = new LambdaQueryWrapper<>();
        qw.eq(HisRisCloudIndex::getReportId, reportId);
        qw.orderByDesc(HisRisCloudIndex::getId);
        qw.last("LIMIT 1");
        return cloudIndexMapper.selectOne(qw);
    }

    private HisRisCloudIndex requireIndex(Long id) {
        HisRisCloudIndex index = cloudIndexMapper.selectById(id);
        if (index == null) {
            throw new BizException(404, "影像云索引不存在: " + id);
        }
        return index;
    }
}
