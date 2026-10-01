package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisDiseaseReport;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisDiseaseReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 疾病报卡服务(OP-B): 下达诊断时提示报卡并落库留痕, 按就诊查询。
 */
@Service
public class HisDiseaseReportService extends ServiceImpl<HisDiseaseReportMapper, HisDiseaseReport> {

    /** 查询某次就诊的报卡列表 */
    public List<HisDiseaseReport> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisDiseaseReport::getVisitId, visitId)
                .orderByDesc(HisDiseaseReport::getId).list();
    }

    /**
     * 新建报卡: 补全报告人/报告时间/状态缺省(0待报), 报卡编号空则自动生成本地流水号。
     */
    public HisDiseaseReport create(HisDiseaseReport report) {
        if (report == null || report.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        LoginUser user = UserContext.get();
        if (user != null && !StringUtils.hasText(report.getReporter())) {
            report.setReporter(user.getRealName());
        }
        if (report.getReportType() == null) {
            report.setReportType(1);
        }
        if (report.getReportStatus() == null) {
            report.setReportStatus(0);
        }
        if (report.getReportTime() == null) {
            report.setReportTime(LocalDateTime.now());
        }
        if (!StringUtils.hasText(report.getReportNo())) {
            report.setReportNo(genReportNo(report.getReportType()));
        }
        save(report);
        return report;
    }

    /** 本地报卡编号: BK + 类型 + 时间戳(占位, 后续可对接上报通道 upload-center) */
    private String genReportNo(Integer type) {
        return "BK" + (type == null ? 1 : type) + System.currentTimeMillis();
    }
}
