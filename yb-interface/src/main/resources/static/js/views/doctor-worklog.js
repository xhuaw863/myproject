/* 门诊诊疗统计查询：本人/管理范围总览、趋势、诊断、处方、医技分析与接诊明细。 */
(function () {
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  function pad2(n) { return ('0' + n).slice(-2); }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function today() { return fmtDate(new Date()); }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return fmtDate(d); }
  function money(n) { return n === null || n === undefined || n === '' ? '0.00' : Number(n).toFixed(2); }
  function number(n) { return Number(n) || 0; }
  function emptyAnalytics() {
    return {
      overview: {}, trend: [], diagnosisTop: [], diagnosisClass: [], prescriptionType: [],
      prescriptionAudit: [], prescriptionDispense: [], orderType: [], orderExec: [], orderPaid: []
    };
  }
  function asList(v) { return Array.isArray(v) ? v : []; }
  function present(v) { return v !== null && v !== undefined && String(v).trim() !== ''; }
  function docFields(rows) {
    return rows.filter(function (row) { return present(row[1]); }).map(function (row) {
      return { label: row[0], value: row[1], wide: !!row[2] };
    });
  }
  function docCard(key, title, subtitle, status, tone, time, fields) {
    return { key: key, title: title, subtitle: subtitle || '', status: status || '', tone: tone || 'info', time: time || '', fields: docFields(fields) };
  }
  function group(label, items) { return { label: label, items: items }; }
  function buildHistoryDocumentGroups(detail) {
    var d = detail || {};
    var visit = d.visit || {};
    var groups = [];
    groups.push(group('住院证', asList(d.admissionCerts).map(function (x) {
      var st = ({ 1: ['待入院', 'warning'], 2: ['已入院', 'success'], 3: ['已作废', 'info'] })[Number(x.status)] || ['状态未知', 'info'];
      return docCard('admit-' + x.id, '住院证 · ' + (x.admitDeptName || '拟收科室未记录'), (x.applyDrName || '开具医师未记录'), st[0], st[1], x.applyTime,
        [['入院诊断', x.admitDiagnosis, true], ['病情摘要', x.conditionSummary, true], ['入院目的', x.admitPurpose, true], ['紧急程度', ({ 1: '普通', 2: '急', 3: '危急' })[Number(x.urgency)]], ['预开卡', Number(x.preFlag) === 1 ? '是' : '否'], ['住院关联号', x.admittedVisitId]]);
    })));
    groups.push(group('诊断证明', asList(d.medicalCerts).map(function (x) {
      var name = ({ 1: '诊断证明', 2: '病假条', 3: '转诊证明' })[Number(x.certType)] || '诊断证明';
      var st = ({ 0: ['无须审核', 'info'], 1: ['待审核', 'warning'], 2: ['已通过', 'success'], 3: ['已驳回', 'danger'] })[Number(x.auditStatus)] || ['无须审核', 'info'];
      return docCard('cert-' + x.id, name, x.issueDrName || '开具医师未记录', st[0], st[1], x.issueTime,
        [['诊断', x.diagnosis, true], ['证明内容', x.certContent, true], ['建议病假', present(x.sickLeaveDays) ? x.sickLeaveDays + ' 天' : '', false], ['备注', x.remark, true], ['审核人员', x.auditorName], ['审核时间', x.auditTime], ['审核意见', x.auditRemark, true]]);
    })));
    groups.push(group('会诊申请', asList(d.consultRequests).map(function (x) {
      var st = ({ 1: ['已申请', 'warning'], 2: ['已接受', 'primary'], 3: ['已完成', 'success'], 4: ['已拒绝', 'danger'], 5: ['已取消', 'info'] })[Number(x.status)] || ['待处理', 'info'];
      var urgency = x.urgencyLevel != null ? x.urgencyLevel : x.urgency;
      return docCard('consult-' + x.id, '会诊申请 · ' + (x.targetDeptName || x.consultDeptName || '会诊科室未记录'), (x.applyDeptName || '') + (x.applyDoctorName || x.applyDrName ? ' · ' + (x.applyDoctorName || x.applyDrName) : ''), st[0], st[1], x.applyTime || x.createTime,
        [['会诊范围', ({ within_dept: '科内', cross_dept: '科间', external: '院外', mdt: 'MDT', 1: '科内', 2: '科间', 3: '院外', 4: 'MDT' })[x.consultCategory]], ['紧急程度', ({ 1: '普通', 2: '急', 3: '特急' })[Number(urgency)]], ['申请理由', x.applyReason || x.consultPurpose, true], ['病情摘要', x.applySummary || x.conditionSummary, true], ['会诊意见', x.consultOpinion, true], ['期望时间', x.expectedTime], ['完成时间', x.consultTime]]);
    })));
    groups.push(group('知情同意书', asList(d.consents).map(function (x) {
      var st = ({ 1: ['待签', 'warning'], 2: ['已签', 'success'], 3: ['已撤销', 'info'] })[Number(x.status)] || ['待签', 'warning'];
      return docCard('consent-' + x.id, x.title || '知情同意书', x.doctorName || '谈话医师未记录', st[0], st[1], x.doctorSignTime || x.createTime,
        [['同意书类型', ({ 1: '特殊检查', 2: '特殊治疗', 3: '输血', 4: '自费', 5: '病危' })[Number(x.consentType)]], ['告知事项', x.content, true], ['签署人', x.patientSignName], ['与患者关系', x.relation], ['见证人', x.witnessName], ['患者签署时间', x.signTime]]);
    })));
    groups.push(group('代办登记', asList(d.agents).map(function (x) {
      return docCard('agent-' + x.id, '代办登记 · ' + (x.agentName || '代办人未记录'), x.relation || '', Number(x.status) === 0 ? '已作废' : '有效', Number(x.status) === 0 ? 'info' : 'success', x.createTime,
        [['代办事由', x.reason, true], ['身份证号', x.agentIdCard], ['联系电话', x.agentPhone]]);
    })));
    groups.push(group('转诊登记', asList(d.referrals).map(function (x) {
      var st = ({ 1: ['已申请', 'warning'], 2: ['已接收', 'primary'], 3: ['已完成', 'success'], 4: ['已取消', 'info'] })[Number(x.status)] || ['已申请', 'warning'];
      return docCard('referral-' + x.id, (Number(x.direction) === 2 ? '下转/接收 · ' : '上转/转出 · ') + (x.toHospital || '目标医院未记录'), x.toDept || '', st[0], st[1], x.createTime,
        [['转诊原因', x.reason, true], ['病情摘要', x.summary, true], ['联系电话', x.contactPhone]]);
    })));
    groups.push(group('绿色通道', asList(d.greenCredits).map(function (x) {
      return docCard('green-' + x.id, '绿色通道信用', x.reason || '', Number(x.status) === 1 ? '启用' : '关闭', Number(x.status) === 1 ? 'success' : 'info', x.createTime,
        [['信用额度', '¥' + money(x.creditLimit)], ['已使用', '¥' + money(x.usedAmount)], ['开通原因', x.reason, true]]);
    })));
    groups.push(group('犬伤登记', asList(d.dogbiteRegisters).map(function (x) {
      return docCard('dogbite-' + x.id, '犬伤暴露登记 · ' + (({ 1: 'Ⅰ级', 2: 'Ⅱ级', 3: 'Ⅲ级' })[Number(x.woundGrade)] || '未分级'), x.doctorName || '', Number(x.status) === 0 ? '已作废' : '已登记', Number(x.status) === 0 ? 'info' : 'success', x.exposeTime || x.createTime,
        [['致伤动物', x.animalType], ['动物情况', x.dogInfo, true], ['暴露部位', x.woundParts], ['伤口数量', x.woundCount], ['伤口处置', x.woundHandling, true], ['免疫程序', x.vaccinePlan], ['首针时间', x.vaccineFirstTime], ['下次接种', x.vaccineNextDate], ['被动免疫制剂', Number(x.immunoglobulin) === 1 ? '已注射' : '未注射']]);
    })));
    groups.push(group('疾病报卡', asList(d.diseaseReports).map(function (x) {
      var st = ({ '-1': ['已退回', 'danger'], 0: ['待报', 'warning'], 1: ['已报', 'primary'], 2: ['已审核', 'success'] })[String(x.reportStatus)] || ['状态未知', 'info'];
      var dt = x.detail || {};
      var category = ({ 1: '法定传染病', 2: '严重精神障碍', 3: '恶性肿瘤', 4: '高血压', 5: '糖尿病', 9: '其他' })[Number(x.reportCategory)] || '疾病';
      return docCard('disease-' + x.id, category + '报告卡 · ' + (x.cardNo || x.reportNo || ''), x.reporter || '填卡医生未记录', st[0], st[1], x.reportTime,
        [['诊断', (x.diagName || dt.diseaseName || '') + (x.diagCode ? '（' + x.diagCode + '）' : ''), true], ['病种', dt.diseaseName], ['疾病编码', dt.diseaseCode || dt.icdCode], ['危险等级', dt.riskLevel], ['临床分期', dt.stage], ['肿瘤部位', dt.tumorSite], ['发病时间', x.onsetDate], ['诊断时间', x.diagTime], ['报卡摘要', x.reportContent, true], ['退卡原因', x.returnReason, true]]);
    })));
    if (present(visit.followupDate) || present(visit.followupNote)) {
      groups.push(group('复诊预约', [docCard('followup-' + visit.id, '复诊预约', visit.drName || '', '已安排', 'success', visit.followupDate,
        [['复诊日期', visit.followupDate], ['复诊备注', visit.followupNote, true]])]));
    }
    return groups.filter(function (item) { return item.items.length; });
  }

  HIS.views.DoctorWorklog = {
    data: function () {
      return {
        loading: false,
        detailLoading: false,
        exporting: false,
        activeTab: 'overview',
        dateRange: [daysAgo(29), today()],
        deptId: '',
        staffId: '',
        keyword: '',
        analytics: emptyAnalytics(),
        summaryList: [],
        detailList: [],
        detailTotal: 0,
        detailPage: 1,
        detailSize: 20,
        deptOptions: [],
        staffOptions: [],
        charts: {},
        historyVisible: false,
        historyLoading: false,
        historyDetailLoading: false,
        historyAnchorId: null,
        historyPatient: {},
        historyVisits: [],
        historySelectedId: null,
        historyDetail: null,
        historyTab: 'record',
        historyRecordHtml: ''
      };
    },
    computed: {
      user: function () { return typeof HIS.getUser === 'function' ? (HIS.getUser() || {}) : {}; },
      isAdmin: function () {
        return !!(HIS.hasRole && (HIS.hasRole('ADMIN') || HIS.hasRole('SUPER_ADMIN')));
      },
      startDate: function () { return (this.dateRange && this.dateRange[0]) || ''; },
      endDate: function () { return (this.dateRange && this.dateRange[1]) || ''; },
      overview: function () { return this.analytics.overview || {}; },
      historySelected: function () {
        var id = this.historySelectedId;
        return this.historyVisits.find(function (item) { return String(item.visitId) === String(id); }) || {};
      },
      historySoap: function () { return (this.historyDetail && this.historyDetail.soap) || {}; },
      historyRecord: function () { return (this.historyDetail && this.historyDetail.record) || {}; },
      historyRecordRows: function () {
        var soap = this.historySoap;
        var record = this.historyRecord;
        return [
          ['主诉', soap.chiefComplaint || record.subjective],
          ['现病史', soap.presentIllness],
          ['既往史', soap.pastHistory],
          ['过敏史', soap.allergyHistory || record.allergyHistory],
          ['体格检查', soap.physicalExam || record.objective],
          ['辅助检查', soap.auxExam || soap.auxiliaryExam || record.auxExam],
          ['临床评估', soap.diagnosis || record.assessment],
          ['处理意见', soap.treatmentOpinion || soap.treatment || record.plan],
          ['随访记录', this.historyDetail && this.historyDetail.visit && this.historyDetail.visit.followupNote]
        ].filter(function (row) { return row[1] !== null && row[1] !== undefined && String(row[1]).trim() !== ''; });
      },
      historyDocumentGroups: function () { return buildHistoryDocumentGroups(this.historyDetail); },
      historyDocumentCount: function () {
        return this.historyDocumentGroups.reduce(function (sum, item) { return sum + item.items.length; }, 0);
      },
      scopeLabel: function () {
        if (!this.isAdmin) { return '本人数据 · ' + (this.user.realName || this.user.username || '当前医生'); }
        var staff = this.staffOptions.find(function (x) { return String(x.id) === String(this.staffId); }, this);
        var dept = this.deptOptions.find(function (x) { return String(x.id) === String(this.deptId); }, this);
        if (staff) { return '医生 · ' + staff.name; }
        if (dept) { return '科室 · ' + dept.name; }
        return '全部医生';
      }
    },
    mounted: function () {
      if (this.isAdmin) { this.loadDepts(); }
      this.loadData();
      window.addEventListener('resize', this.resizeCharts);
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.resizeCharts);
      this.disposeCharts();
    },
    methods: {
      buildParams: function (includeStaff, includeKeyword) {
        var p = [];
        if (this.startDate) { p.push('startDate=' + encodeURIComponent(this.startDate)); }
        if (this.endDate) { p.push('endDate=' + encodeURIComponent(this.endDate)); }
        if (this.isAdmin && includeStaff && this.staffId) { p.push('staffId=' + encodeURIComponent(this.staffId)); }
        if (this.isAdmin && this.deptId) { p.push('deptId=' + encodeURIComponent(this.deptId)); }
        if (includeKeyword && this.keyword) { p.push('keyword=' + encodeURIComponent(this.keyword.trim())); }
        return p.join('&');
      },
      loadDepts: function () {
        var vm = this;
        HIS.get('/api/his/dept/enabled').then(function (rows) {
          vm.deptOptions = (rows || []).map(function (x) { return { id: x.id, name: x.deptName }; });
        }).catch(function () { vm.deptOptions = []; });
      },
      loadData: function () {
        var vm = this;
        vm.loading = true;
        var analyticsReq = HIS.get('/api/his/report/doctor-worklog-analytics?' + vm.buildParams(true, false));
        var staffReq = vm.isAdmin
          ? HIS.get('/api/his/report/doctor-worklog?' + vm.buildParams(false, false))
          : Promise.resolve([]);
        Promise.all([analyticsReq, staffReq]).then(function (result) {
          vm.analytics = Object.assign(emptyAnalytics(), result[0] || {});
          vm.summaryList = result[1] || [];
          vm.staffOptions = vm.summaryList.filter(function (r) { return r.staffId != null; }).map(function (r) {
            return { id: r.staffId, name: r.drName || ('医生' + r.staffId), deptName: r.deptName || '' };
          });
          vm.$nextTick(vm.renderVisibleCharts);
        }).catch(HIS.notifyError).finally(function () { vm.loading = false; });
      },
      loadDetail: function () {
        var vm = this;
        vm.detailLoading = true;
        var q = '/api/his/report/doctor-worklog-detail?page=' + vm.detailPage + '&size=' + vm.detailSize;
        var params = vm.buildParams(true, true);
        if (params) { q += '&' + params; }
        HIS.get(q).then(function (d) {
          vm.detailList = (d && d.records) || [];
          vm.detailTotal = (d && d.total) || 0;
        }).catch(HIS.notifyError).finally(function () { vm.detailLoading = false; });
      },
      search: function () {
        this.detailPage = 1;
        this.loadData();
        if (this.activeTab === 'detail') { this.loadDetail(); }
      },
      reset: function () {
        this.dateRange = [daysAgo(29), today()];
        this.deptId = '';
        this.staffId = '';
        this.keyword = '';
        this.search();
      },
      setQuickRange: function (kind) {
        var end = new Date();
        var start = new Date();
        if (kind === 'month') { start = new Date(end.getFullYear(), end.getMonth(), 1); }
        else { start.setDate(end.getDate() - (Number(kind) - 1)); }
        this.dateRange = [fmtDate(start), fmtDate(end)];
        this.search();
      },
      onDeptChange: function () {
        this.staffId = '';
        this.search();
      },
      onTabChange: function (tab) {
        if (tab === 'detail') { this.loadDetail(); }
        this.$nextTick(this.renderVisibleCharts);
      },
      onPage: function (page) { this.detailPage = page; this.loadDetail(); },
      onSize: function (size) { this.detailSize = size; this.detailPage = 1; this.loadDetail(); },
      exportExcel: function () {
        var vm = this;
        vm.exporting = true;
        var params = vm.buildParams(true, false);
        HIS.download('/api/his/report/export/doctor-worklog' + (params ? '?' + params : ''), '诊疗统计查询.xlsx')
          .then(function (name) { HIS.notifySuccess('已导出：' + name); })
          .catch(HIS.notifyError)
          .finally(function () { vm.exporting = false; });
      },
      openHistory: function (row) {
        var vm = this;
        if (!row || row.visitId == null) { return; }
        vm.historyVisible = true;
        vm.historyLoading = true;
        vm.historyAnchorId = row.visitId;
        vm.historyPatient = { name: row.patientName, patientNo: row.patientNo, gender: row.gender, age: row.age };
        vm.historyVisits = [];
        vm.historySelectedId = null;
        vm.historyDetail = null;
        vm.historyRecordHtml = '';
        HIS.get('/api/his/report/doctor-worklog-patient-history?anchorVisitId=' + HIS.idParam(row.visitId))
          .then(function (data) {
            vm.historyPatient = (data && data.patient) || vm.historyPatient;
            vm.historyVisits = (data && data.visits) || [];
            var selected = vm.historyVisits.find(function (item) { return String(item.visitId) === String(row.visitId); }) || vm.historyVisits[0];
            if (selected) { vm.selectHistory(selected); }
          }).catch(function (e) {
            vm.historyVisible = false;
            HIS.notifyError(e);
          }).finally(function () { vm.historyLoading = false; });
      },
      selectHistory: function (item) {
        var vm = this;
        if (!item || item.visitId == null) { return; }
        vm.historySelectedId = item.visitId;
        vm.historyDetail = null;
        vm.historyRecordHtml = '';
        vm.historyTab = 'record';
        vm.historyDetailLoading = true;
        var q = '/api/his/report/doctor-worklog-visit-detail?anchorVisitId=' + HIS.idParam(vm.historyAnchorId)
          + '&visitId=' + HIS.idParam(item.visitId);
        HIS.get(q).then(function (data) {
          if (String(vm.historySelectedId) !== String(item.visitId)) { return; }
          vm.historyDetail = data || null;
          vm.renderHistoryRecord(data || {});
        }).catch(HIS.notifyError).finally(function () {
          if (String(vm.historySelectedId) === String(item.visitId)) { vm.historyDetailLoading = false; }
        });
      },
      renderHistoryRecord: function (data) {
        var vm = this;
        var visit = (data && data.visit) || {};
        if (Number(visit.emrFormat) !== 1 || !visit.content) { return; }
        var json = visit.content;
        try { if (typeof json === 'string') { json = JSON.parse(json); } } catch (e) { return; }
        var preview = HIS.EmrEditor && HIS.EmrEditor.EmrPrintPreview;
        if (!preview) { return; }
        var selectedId = vm.historySelectedId;
        try {
          var rendered = typeof preview.generateHTMLAsync === 'function'
            ? preview.generateHTMLAsync(json, {}) : Promise.resolve(preview.generateHTML(json, {}));
          Promise.resolve(rendered).then(function (html) {
            if (String(vm.historySelectedId) === String(selectedId)) { vm.historyRecordHtml = html || ''; }
          }).catch(function () { vm.historyRecordHtml = ''; });
        } catch (e) { vm.historyRecordHtml = ''; }
      },
      closeHistory: function () {
        this.historyVisible = false;
        this.historyDetail = null;
        this.historyRecordHtml = '';
      },
      dateTime: function (v) { return v == null || v === '' ? '—' : String(v).replace('T', ' ').slice(0, 16); },
      prescriptionStatus: function (v) { return Number(v) < 0 ? '已作废' : '有效'; },
      dispenseStatus: function (v) { return Number(v) === 1 ? '已发药' : (Number(v) === 2 ? '已退药' : '未发药'); },
      orderStatus: function (v) { return Number(v) < 0 || Number(v) === 3 ? '已退/作废' : (Number(v) === 2 ? '已执行' : '已开'); },
      execStatus: function (v) { return Number(v) === 2 ? '已完成' : (Number(v) === 1 ? '执行中' : '未执行'); },
      reportType: function (v) { return String(v || '').toLowerCase() === 'lab' ? '检验' : '检查'; },
      reportStatus: function (v) { return ({ 1: '已报告', 2: '已审核', 3: '已作废' })[Number(v)] || '未发布'; },
      reportStatusTone: function (v) { return ({ 1: 'warning', 2: 'success', 3: 'info' })[Number(v)] || 'info'; },
      abnormalText: function (v) { return ({ 1: '↑偏高', 2: '↓偏低', 3: '↑危急', 4: '↓危急' })[Number(v)] || ''; },
      resultField: function (row, camel, snake) { return row && (row[camel] !== undefined ? row[camel] : row[snake]); },
      referenceRange: function (row) {
        var low = this.resultField(row, 'refRangeLow', 'ref_range_low');
        var high = this.resultField(row, 'refRangeHigh', 'ref_range_high');
        return low == null && high == null ? '—' : (low == null ? '' : low) + ' ～ ' + (high == null ? '' : high);
      },
      reportImages: function (report) {
        var value = report && report.keyImages;
        if (!value) { return []; }
        var parsed = value;
        if (!Array.isArray(parsed)) {
          try { parsed = JSON.parse(value); } catch (e) { parsed = String(value).split(','); }
        }
        if (!Array.isArray(parsed)) { return []; }
        return parsed.map(function (item) {
          if (typeof item === 'string') { return { url: item.trim(), desc: '' }; }
          return item && { url: item.url || item.image || item.path || item.src, desc: item.desc || item.description || item.name || '' };
        }).filter(function (item) { return item && item.url; });
      },
      initChart: function (id) {
        var dom = document.getElementById(id);
        if (!dom || !window.echarts) { return null; }
        if (!this.charts[id]) {
          var chart = window.echarts.init(dom, 'yb');
          this.charts[id] = window.Vue && window.Vue.markRaw ? window.Vue.markRaw(chart) : chart;
        }
        return this.charts[id];
      },
      setChart: function (id, option, hasData) {
        var chart = this.initChart(id);
        if (!chart) { return; }
        chart.clear();
        if (!hasData) {
          chart.setOption({ title: { text: '暂无统计数据', left: 'center', top: 'middle', textStyle: { color: '#8994a5', fontSize: 13, fontWeight: 400 } } });
          return;
        }
        chart.setOption(option, true);
        chart.resize();
      },
      renderVisibleCharts: function () {
        if (this.activeTab === 'overview') { this.renderTrend(); }
        if (this.activeTab === 'diagnosis') { this.renderDiagnosis(); }
        if (this.activeTab === 'prescription') { this.renderPrescription(); }
        if (this.activeTab === 'order') { this.renderOrder(); }
      },
      renderTrend: function () {
        var rows = this.analytics.trend || [];
        var T = HIS.theme || {};
        this.setChart('dts-trend-chart', {
          color: T.palette,
          tooltip: Object.assign({ trigger: 'axis' }, T.tooltip ? T.tooltip() : {}),
          legend: Object.assign({ top: 2, type: 'scroll' }, T.legend ? T.legend() : {}),
          grid: { left: 48, right: 54, top: 46, bottom: 34 },
          xAxis: Object.assign({ type: 'category', data: rows.map(function (r) { return r.workDate.slice(5); }) }, T.catAxis ? T.catAxis() : {}),
          yAxis: [
            Object.assign({ type: 'value', name: '数量', minInterval: 1 }, T.valAxis ? T.valAxis() : {}),
            Object.assign({ type: 'value', name: '金额(元)', splitLine: { show: false } }, T.valAxis ? T.valAxis() : {})
          ],
          series: [
            { name: '接诊', type: 'bar', barMaxWidth: 18, data: rows.map(function (r) { return number(r.visitCount); }) },
            { name: '完成', type: 'bar', barMaxWidth: 18, data: rows.map(function (r) { return number(r.finishCount); }) },
            { name: '处方数', type: 'line', symbolSize: 5, data: rows.map(function (r) { return number(r.rxCount); }) },
            { name: '医技数', type: 'line', symbolSize: 5, data: rows.map(function (r) { return number(r.orderCount); }) },
            { name: '处方金额', type: 'line', yAxisIndex: 1, symbol: 'none', lineStyle: { type: 'dashed' }, data: rows.map(function (r) { return number(r.rxAmount); }) },
            { name: '医技金额', type: 'line', yAxisIndex: 1, symbol: 'none', lineStyle: { type: 'dashed' }, data: rows.map(function (r) { return number(r.orderAmount); }) }
          ]
        }, rows.some(function (r) { return number(r.visitCount) || number(r.rxCount) || number(r.orderCount); }));
      },
      renderDiagnosis: function () {
        this.renderHorizontalBar('dts-diag-top', this.analytics.diagnosisTop, '就诊人次');
        this.renderDonut('dts-diag-class', this.analytics.diagnosisClass, '诊断类别');
      },
      renderPrescription: function () {
        this.renderAmountBar('dts-rx-type', this.analytics.prescriptionType, '处方');
        this.renderDonut('dts-rx-audit', this.analytics.prescriptionAudit, '审核状态');
        this.renderDonut('dts-rx-dispense', this.analytics.prescriptionDispense, '发药状态');
      },
      renderOrder: function () {
        this.renderAmountBar('dts-order-type', this.analytics.orderType, '申请');
        this.renderDonut('dts-order-exec', this.analytics.orderExec, '执行状态');
        this.renderDonut('dts-order-paid', this.analytics.orderPaid, '收费状态');
      },
      renderHorizontalBar: function (id, source, seriesName) {
        var rows = (source || []).slice().reverse();
        var T = HIS.theme || {};
        this.setChart(id, {
          tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' } }, T.tooltip ? T.tooltip() : {}),
          grid: { left: 122, right: 42, top: 14, bottom: 28 },
          xAxis: Object.assign({ type: 'value', minInterval: 1 }, T.valAxis ? T.valAxis() : {}),
          yAxis: Object.assign({ type: 'category', data: rows.map(function (r) { return r.label; }), axisLabel: { width: 106, overflow: 'truncate' } }, T.catAxis ? T.catAxis() : {}),
          series: [{ name: seriesName, type: 'bar', barMaxWidth: 18, data: rows.map(function (r) { return number(r.itemCount); }), label: { show: true, position: 'right', color: T.ink3 || '#5a6a7e' } }]
        }, rows.length > 0);
      },
      renderAmountBar: function (id, source, seriesName) {
        var rows = source || [];
        var T = HIS.theme || {};
        this.setChart(id, {
          tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' } }, T.tooltip ? T.tooltip() : {}),
          legend: Object.assign({ top: 0 }, T.legend ? T.legend() : {}),
          grid: { left: 45, right: 48, top: 42, bottom: 42 },
          xAxis: Object.assign({ type: 'category', data: rows.map(function (r) { return r.label; }), axisLabel: { interval: 0, rotate: rows.length > 4 ? 24 : 0 } }, T.catAxis ? T.catAxis() : {}),
          yAxis: [Object.assign({ type: 'value', name: '数量', minInterval: 1 }, T.valAxis ? T.valAxis() : {}), Object.assign({ type: 'value', name: '金额', splitLine: { show: false } }, T.valAxis ? T.valAxis() : {})],
          series: [
            { name: seriesName + '数', type: 'bar', barMaxWidth: 28, data: rows.map(function (r) { return number(r.itemCount); }) },
            { name: seriesName + '金额', type: 'line', yAxisIndex: 1, data: rows.map(function (r) { return number(r.itemAmount); }) }
          ]
        }, rows.length > 0);
      },
      renderDonut: function (id, source, centerText) {
        var rows = source || [];
        var T = HIS.theme || {};
        this.setChart(id, {
          color: T.palette,
          tooltip: Object.assign({ trigger: 'item', formatter: '{b}<br/>{c}（{d}%）' }, T.tooltip ? T.tooltip() : {}),
          legend: Object.assign({ bottom: 0, type: 'scroll' }, T.legend ? T.legend() : {}),
          graphic: [{ type: 'text', left: 'center', top: '42%', style: { text: centerText, fill: T.ink3 || '#5a6a7e', fontSize: 12 } }],
          series: [{ type: 'pie', radius: ['46%', '68%'], center: ['50%', '43%'], minAngle: 3, label: { formatter: '{b}\n{d}%', fontSize: 11 }, data: rows.map(function (r) { return { name: r.label, value: number(r.itemCount) }; }) }]
        }, rows.length > 0);
      },
      resizeCharts: function () {
        Object.keys(this.charts).forEach(function (key) { if (this.charts[key]) { this.charts[key].resize(); } }, this);
      },
      disposeCharts: function () {
        Object.keys(this.charts).forEach(function (key) { if (this.charts[key]) { this.charts[key].dispose(); } }, this);
        this.charts = {};
      },
      money: money,
      pct: function (n) { return number(n).toFixed(1) + '%'; },
      minutes: function (n) { return n === null || n === undefined ? '—' : number(n).toFixed(1) + ' 分钟'; },
      genderLabel: function (v) { var s = String(v == null ? '' : v); return s === '1' ? '男' : (s === '2' ? '女' : (s || '—')); },
      statusLabel: function (v) { return Number(v) === 1 ? '候诊' : (Number(v) === 2 ? '接诊中' : (Number(v) === 3 ? '已完成' : '—')); },
      statusTag: function (v) { return Number(v) === 3 ? 'success' : (Number(v) === 2 ? 'warning' : 'info'); }
    },
    template: [
      '<div class="doctor-stats cd-fill" v-loading="loading">',
      '  <section class="dts-head">',
      '    <div><div class="dts-title">诊疗统计查询</div><div class="dts-subtitle">门诊诊疗效率、疾病谱与处方医技结构分析</div></div>',
      '    <div class="dts-scope"><span class="dts-scope-dot"></span>{{ scopeLabel }}</div>',
      '  </section>',
      '  <section class="dts-toolbar">',
      '    <div class="dts-quick"><button @click="setQuickRange(7)">近7天</button><button @click="setQuickRange(30)">近30天</button><button @click="setQuickRange(90)">近90天</button><button @click="setQuickRange(\'month\')">本月</button></div>',
      '    <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width:242px"></el-date-picker>',
      '    <el-select v-if="isAdmin" v-model="deptId" placeholder="全部科室" clearable filterable style="width:150px" @change="onDeptChange"><el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id"></el-option></el-select>',
      '    <el-select v-if="isAdmin" v-model="staffId" placeholder="全部医生" clearable filterable style="width:140px"><el-option v-for="s in staffOptions" :key="s.id" :label="s.name" :value="s.id"></el-option></el-select>',
      '    <el-input v-if="activeTab===\'detail\'" v-model="keyword" placeholder="患者姓名/患者号" clearable style="width:170px" @keyup.enter="search"></el-input>',
      '    <el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button>',
      '    <el-button type="success" plain :loading="exporting" @click="exportExcel">导出 Excel</el-button>',
      '  </section>',
      '  <section class="dts-kpis">',
      '    <div class="dts-kpi is-primary"><span>接诊人次</span><strong>{{ overview.visitCount || 0 }}</strong><small>完成 {{ overview.finishCount || 0 }} 人次</small></div>',
      '    <div class="dts-kpi"><span>完成率</span><strong>{{ pct(overview.finishRate) }}</strong><small>有效就诊口径</small></div>',
      '    <div class="dts-kpi"><span>平均接诊时长</span><strong>{{ minutes(overview.avgVisitMinutes) }}</strong><small>仅已完成就诊</small></div>',
      '    <div class="dts-kpi"><span>处方数</span><strong>{{ overview.rxCount || 0 }}</strong><small>均次 ¥{{ money(overview.avgRxAmount) }}</small></div>',
      '    <div class="dts-kpi is-money"><span>处方金额</span><strong>¥{{ money(overview.rxAmount) }}</strong><small>有效处方</small></div>',
      '    <div class="dts-kpi"><span>医技申请</span><strong>{{ overview.orderCount || 0 }}</strong><small>检查 / 检验 / 治疗</small></div>',
      '    <div class="dts-kpi is-money"><span>医技金额</span><strong>¥{{ money(overview.orderAmount) }}</strong><small>未含退单</small></div>',
      '  </section>',
      '  <el-tabs v-model="activeTab" class="dts-tabs" @tab-change="onTabChange">',
      '    <el-tab-pane label="诊疗总览" name="overview"><div class="dts-panel dts-panel--hero"><div class="dts-panel-head"><b>每日诊疗趋势</b><span>数量与金额双轴</span></div><div id="dts-trend-chart" class="dts-chart dts-chart--hero"></div></div>',
      '      <div v-if="isAdmin" class="dts-panel dts-doctor-rank"><div class="dts-panel-head"><b>医生工作量</b><span>{{ summaryList.length }} 名医生</span></div>',
      '        <el-table :data="summaryList" border stripe size="small" max-height="300"><el-table-column prop="drName" label="医生" min-width="90"></el-table-column><el-table-column prop="deptName" label="科室" min-width="110"></el-table-column><el-table-column prop="visitCount" label="接诊" width="70" align="right"></el-table-column><el-table-column prop="finishRate" label="完成率" width="82" align="right"></el-table-column><el-table-column prop="rxCount" label="处方" width="70" align="right"></el-table-column><el-table-column prop="rxAmount" label="处方金额" width="105" align="right"><template #default="s">{{ money(s.row.rxAmount) }}</template></el-table-column><el-table-column prop="orderCount" label="医技" width="70" align="right"></el-table-column><el-table-column prop="orderAmount" label="医技金额" width="105" align="right"><template #default="s">{{ money(s.row.orderAmount) }}</template></el-table-column></el-table>',
      '      </div></el-tab-pane>',
      '    <el-tab-pane label="诊断分析" name="diagnosis"><div class="dts-grid dts-grid--split"><div class="dts-panel"><div class="dts-panel-head"><b>主诊断 TOP 10</b><span>按就诊人次</span></div><div id="dts-diag-top" class="dts-chart dts-chart--tall"></div></div><div class="dts-panel"><div class="dts-panel-head"><b>诊断类别构成</b><span>主诊断口径</span></div><div id="dts-diag-class" class="dts-chart dts-chart--tall"></div></div></div></el-tab-pane>',
      '    <el-tab-pane label="处方分析" name="prescription"><div class="dts-grid dts-grid--triple"><div class="dts-panel"><div class="dts-panel-head"><b>处方类型</b><span>数量与金额</span></div><div id="dts-rx-type" class="dts-chart"></div></div><div class="dts-panel"><div class="dts-panel-head"><b>审核状态</b><span>处方数</span></div><div id="dts-rx-audit" class="dts-chart"></div></div><div class="dts-panel"><div class="dts-panel-head"><b>发药状态</b><span>处方数</span></div><div id="dts-rx-dispense" class="dts-chart"></div></div></div></el-tab-pane>',
      '    <el-tab-pane label="医技分析" name="order"><div class="dts-grid dts-grid--triple"><div class="dts-panel"><div class="dts-panel-head"><b>申请类型</b><span>数量与金额</span></div><div id="dts-order-type" class="dts-chart"></div></div><div class="dts-panel"><div class="dts-panel-head"><b>执行状态</b><span>有效申请</span></div><div id="dts-order-exec" class="dts-chart"></div></div><div class="dts-panel"><div class="dts-panel-head"><b>收费状态</b><span>有效申请</span></div><div id="dts-order-paid" class="dts-chart"></div></div></div></el-tab-pane>',
      '    <el-tab-pane label="接诊明细" name="detail"><div class="dts-panel dts-detail"><div class="dts-panel-head"><b>接诊明细</b><span>共 {{ detailTotal }} 条</span></div>',
      '      <el-table :data="detailList" v-loading="detailLoading" border stripe size="small" height="calc(100vh - 362px)" @row-dblclick="openHistory">',
      '        <el-table-column type="index" label="序号" width="56" :index="(detailPage-1)*detailSize+1" fixed></el-table-column><el-table-column prop="workDate" label="日期" width="100" fixed></el-table-column><el-table-column prop="patientNo" label="患者号" width="105" show-overflow-tooltip></el-table-column><el-table-column prop="patientName" label="患者" width="84" fixed></el-table-column>',
      '        <el-table-column label="性别" width="52" align="center"><template #default="s">{{ genderLabel(s.row.gender) }}</template></el-table-column><el-table-column prop="age" label="年龄" width="52" align="center"></el-table-column><el-table-column prop="mainDiagnosis" label="主诊断" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.mainDiagnosis || \'—\' }}</template></el-table-column><el-table-column prop="chiefComplaint" label="主诉" min-width="150" show-overflow-tooltip><template #default="s">{{ s.row.chiefComplaint || \'—\' }}</template></el-table-column>',
      '        <el-table-column prop="drName" label="医生" width="84"></el-table-column><el-table-column prop="deptName" label="科室" width="110" show-overflow-tooltip></el-table-column><el-table-column label="状态" width="78" align="center"><template #default="s"><el-tag size="small" :type="statusTag(s.row.visitStatus)">{{ statusLabel(s.row.visitStatus) }}</el-tag></template></el-table-column><el-table-column prop="durationMinutes" label="耗时(分)" width="78" align="right"><template #default="s">{{ s.row.durationMinutes == null ? \'—\' : s.row.durationMinutes }}</template></el-table-column>',
      '        <el-table-column label="处方" align="center"><el-table-column prop="rxCount" label="数" width="55" align="right"></el-table-column><el-table-column prop="rxAmount" label="金额" width="88" align="right"><template #default="s">{{ money(s.row.rxAmount) }}</template></el-table-column></el-table-column>',
      '        <el-table-column label="检查" align="center"><el-table-column prop="checkCount" label="数" width="55" align="right"></el-table-column><el-table-column prop="checkAmount" label="金额" width="88" align="right"><template #default="s">{{ money(s.row.checkAmount) }}</template></el-table-column></el-table-column>',
      '        <el-table-column label="检验" align="center"><el-table-column prop="labCount" label="数" width="55" align="right"></el-table-column><el-table-column prop="labAmount" label="金额" width="88" align="right"><template #default="s">{{ money(s.row.labAmount) }}</template></el-table-column></el-table-column>',
      '        <el-table-column label="治疗" align="center"><el-table-column prop="treatCount" label="数" width="55" align="right"></el-table-column><el-table-column prop="treatAmount" label="金额" width="88" align="right"><template #default="s">{{ money(s.row.treatAmount) }}</template></el-table-column></el-table-column>',
      '        <el-table-column label="就诊历史" width="96" fixed="right" align="center"><template #default="s"><el-button link type="primary" @click.stop="openHistory(s.row)">查看完整</el-button></template></el-table-column>',
      '      </el-table><el-pagination class="dts-pagination" background layout="total, sizes, prev, pager, next" :total="detailTotal" :page-size="detailSize" :page-sizes="[20,50,100]" :current-page="detailPage" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </div></el-tab-pane>',
      '  </el-tabs>',
      '  <el-drawer v-model="historyVisible" size="92%" append-to-body destroy-on-close class="dts-history-drawer" @closed="closeHistory">',
      '    <template #header><div class="dts-history-head"><div><b>{{ historyPatient.name || \'患者\' }}</b><span>{{ genderLabel(historyPatient.gender) }} · {{ historyPatient.age == null ? \'—\' : historyPatient.age + \'岁\' }} · 患者号 {{ historyPatient.patientNo || \'—\' }}</span></div><el-tag type="info" effect="plain">只读查询</el-tag></div></template>',
      '    <div class="dts-history-shell" v-loading="historyLoading">',
      '      <aside class="dts-history-list"><div class="dts-history-list-title">门诊历史 <span>{{ historyVisits.length }} 次</span></div>',
      '        <div class="dts-history-empty" v-if="!historyLoading && !historyVisits.length">暂无可查看的门诊历史</div>',
      '        <button type="button" v-for="item in historyVisits" :key="item.visitId" class="dts-history-item" :class="{\'is-active\': String(item.visitId)===String(historySelectedId)}" @click="selectHistory(item)">',
      '          <span class="dts-history-date">{{ item.workDate || dateTime(item.visitTime).slice(0,10) }}</span><el-tag size="small" :type="statusTag(item.visitStatus)">{{ statusLabel(item.visitStatus) }}</el-tag>',
      '          <b>{{ item.mainDiagnosis || \'未记录诊断\' }}</b><span>{{ item.deptName || \'—\' }} · {{ item.doctorName || \'—\' }}</span><small>{{ item.chiefComplaint || \'未记录主诉\' }}</small>',
      '        </button>',
      '      </aside>',
      '      <main class="dts-history-main" v-loading="historyDetailLoading">',
      '        <el-empty v-if="!historyDetail && !historyDetailLoading" description="请选择一条就诊记录"></el-empty>',
      '        <template v-if="historyDetail">',
      '          <div class="dts-history-meta"><div><b>{{ historySelected.workDate || dateTime(historySelected.visitTime).slice(0,10) }} 门诊记录</b><span>{{ historySelected.deptName || \'—\' }} · {{ historySelected.doctorName || \'—\' }}</span></div><div><el-tag :type="statusTag(historySelected.visitStatus)">{{ statusLabel(historySelected.visitStatus) }}</el-tag><span>接诊 {{ dateTime(historySelected.visitTime) }}</span><span>完成 {{ dateTime(historySelected.finishTime) }}</span></div></div>',
      '          <el-tabs v-model="historyTab" class="dts-history-tabs">',
      '            <el-tab-pane label="门诊病历" name="record"><div class="dts-readonly-note">完整病历只读展示，不提供修改、复制为当前病历或重新开立功能。</div>',
      '              <div v-if="historyRecordHtml" class="dts-record-html" v-html="historyRecordHtml"></div>',
      '              <div v-else-if="historyRecordRows.length" class="dts-record-rows"><div v-for="row in historyRecordRows" :key="row[0]"><label>{{ row[0] }}</label><p>{{ row[1] }}</p></div></div>',
      '              <el-empty v-else description="本次就诊未记录门诊病历"></el-empty>',
      '            </el-tab-pane>',
      '            <el-tab-pane :label="\'诊断 \' + ((historyDetail.diagnoses || []).length)" name="diagnosis"><el-table :data="historyDetail.diagnoses || []" border stripe size="small"><el-table-column type="index" label="序号" width="58"></el-table-column><el-table-column label="主诊断" width="76" align="center"><template #default="s"><el-tag v-if="String(s.row.maindiagFlag)===\'1\'" size="small" type="danger">主诊断</el-tag><span v-else>—</span></template></el-table-column><el-table-column prop="diagCode" label="诊断编码" width="130"></el-table-column><el-table-column prop="diagName" label="诊断名称" min-width="180"></el-table-column><el-table-column prop="diagDept" label="诊断科室" width="130"></el-table-column><el-table-column prop="diseDorName" label="诊断医生" width="100"></el-table-column><el-table-column prop="diagTime" label="诊断时间" width="150"><template #default="s">{{ dateTime(s.row.diagTime) }}</template></el-table-column></el-table></el-tab-pane>',
      '            <el-tab-pane :label="\'处方 \' + ((historyDetail.prescriptions || []).length)" name="prescription"><div v-if="(historyDetail.prescriptions || []).length" class="dts-doc-list"><section v-for="wrap in historyDetail.prescriptions" :key="wrap.prescription.id" class="dts-doc-card"><header><div><b>{{ wrap.prescription.rxNo || \'处方\' }}</b><span>{{ wrap.prescription.rxType || \'普通处方\' }} · {{ wrap.prescription.diagName || \'未记录诊断\' }}</span></div><div><el-tag size="small" :type="Number(wrap.prescription.status)<0 ? \'danger\' : \'success\'">{{ prescriptionStatus(wrap.prescription.status) }}</el-tag><el-tag size="small" effect="plain">{{ dispenseStatus(wrap.prescription.dispenseStatus) }}</el-tag><strong>¥{{ money(wrap.prescription.totalAmount) }}</strong></div></header><el-table :data="wrap.items || []" border size="small"><el-table-column prop="itemName" label="药品名称" min-width="180"></el-table-column><el-table-column prop="spec" label="规格" width="110"></el-table-column><el-table-column prop="dosage" label="单次剂量" width="90"></el-table-column><el-table-column prop="usageMethod" label="用法" width="100"></el-table-column><el-table-column prop="frequency" label="频次" width="90"></el-table-column><el-table-column prop="days" label="天数" width="65" align="right"></el-table-column><el-table-column label="数量" width="90" align="right"><template #default="s">{{ s.row.quantity }} {{ s.row.unit || \'\' }}</template></el-table-column><el-table-column prop="amount" label="金额" width="90" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column></el-table></section></div><el-empty v-else description="本次就诊无处方"></el-empty></el-tab-pane>',
      '            <el-tab-pane :label="\'医技申请 \' + ((historyDetail.orders || []).length)" name="order"><div v-if="(historyDetail.orders || []).length" class="dts-doc-list"><section v-for="wrap in historyDetail.orders" :key="wrap.order.id" class="dts-doc-card"><header><div><b>{{ wrap.order.orderNo || \'申请单\' }}</b><span>{{ wrap.order.orderType || \'医技\' }} · {{ wrap.order.diagName || \'未记录诊断\' }}</span></div><div><el-tag size="small" :type="Number(wrap.order.status)<0 || Number(wrap.order.status)===3 ? \'danger\' : \'success\'">{{ orderStatus(wrap.order.status) }}</el-tag><el-tag size="small" effect="plain">{{ execStatus(wrap.order.execStatus) }}</el-tag><strong>¥{{ money(wrap.order.totalAmount) }}</strong></div></header><el-table :data="wrap.items || []" border size="small"><el-table-column prop="itemName" label="项目名称" min-width="190"></el-table-column><el-table-column prop="spec" label="规格" width="110"></el-table-column><el-table-column prop="examPart" label="检查部位" width="110"></el-table-column><el-table-column prop="execDept" label="执行科室" width="120"></el-table-column><el-table-column label="数量" width="90" align="right"><template #default="s">{{ s.row.quantity }} {{ s.row.unit || \'\' }}</template></el-table-column><el-table-column prop="amount" label="金额" width="90" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column></el-table></section></div><el-empty v-else description="本次就诊无检查、检验或治疗申请"></el-empty></el-tab-pane>',
      '            <el-tab-pane :label="\'报告单 \'+((historyDetail.reports||[]).length)" name="report"><div class="dts-readonly-note">检查、检验报告按本次就诊医嘱精确关联，仅展示已出具、已审核及已作废报告。</div><div v-if="(historyDetail.reports||[]).length" class="dts-doc-list">',
      '              <section v-for="report in historyDetail.reports" :key="report.id" class="dts-doc-card dts-report-card"><header><div><b>{{ report.reportNo || \'报告单\' }}</b><span>{{ reportType(report.reportType) }} · {{ report.orderNo || \'医嘱号未记录\' }}<template v-if="report.diagName"> · {{ report.diagName }}</template></span></div><div><el-tag v-if="Number(report.criticalFlag)===1" type="danger" size="small">危急值</el-tag><el-tag :type="reportStatusTone(report.status)" size="small">{{ reportStatus(report.status) }}</el-tag><span>{{ dateTime(report.reportTime || report.createTime) }}</span></div></header>',
      '                <div class="dts-report-sign"><span>报告医师：{{ report.reportDoctorName || \'—\' }}</span><span>审核医师：{{ report.reviewDoctorName || \'—\' }}</span><span v-if="Number(report.status)===3">作废时间：{{ dateTime(report.revokeTime) }}</span></div>',
      '                <div class="dts-report-text" v-if="report.findings"><label>检查/检验所见</label><p>{{ report.findings }}</p></div><div class="dts-report-text is-conclusion" v-if="report.conclusion"><label>报告结论</label><p>{{ report.conclusion }}</p></div><div class="dts-report-text is-revoked" v-if="Number(report.status)===3 && report.revokeReason"><label>作废原因</label><p>{{ report.revokeReason }}</p></div>',
      '                <div v-if="reportImages(report).length" class="dts-report-images"><div v-for="(image,i) in reportImages(report)" :key="i"><el-image :src="image.url" :preview-src-list="reportImages(report).map(function(x){return x.url;})" :initial-index="i" fit="cover" preview-teleported></el-image><small v-if="image.desc">{{ image.desc }}</small></div></div>',
      '                <el-table v-if="(report.resultItems||[]).length" :data="report.resultItems" border size="small"><el-table-column label="项目" min-width="160"><template #default="s">{{ resultField(s.row,\'itemName\',\'item_name\') || \'—\' }}</template></el-table-column><el-table-column label="结果" width="120"><template #default="s"><b :class="{\'dts-result-alert\':Number(resultField(s.row,\'abnormalFlag\',\'abnormal_flag\'))>0,\'is-critical\':Number(resultField(s.row,\'abnormalFlag\',\'abnormal_flag\'))>=3}">{{ resultField(s.row,\'resultValue\',\'result_value\') || \'—\' }}</b> <small>{{ abnormalText(resultField(s.row,\'abnormalFlag\',\'abnormal_flag\')) }}</small></template></el-table-column><el-table-column label="单位" width="90"><template #default="s">{{ resultField(s.row,\'resultUnit\',\'result_unit\') || \'—\' }}</template></el-table-column><el-table-column label="参考范围" width="150"><template #default="s">{{ referenceRange(s.row) }}</template></el-table-column><el-table-column label="备注" min-width="120"><template #default="s">{{ resultField(s.row,\'remark\',\'remark\') || \'—\' }}</template></el-table-column></el-table>',
      '              </section></div><el-empty v-else description="本次就诊暂无已出具报告"></el-empty></el-tab-pane>',
      '            <el-tab-pane :label="\'医疗文书 \'+historyDocumentCount" name="document"><div class="dts-readonly-note">诊疗过程中形成的证明、同意书、会诊及专项登记均为原始状态只读呈现，不提供开具、审核、签署、作废或打印操作。</div><div v-if="historyDocumentGroups.length" class="dts-document-groups">',
      '              <section v-for="group in historyDocumentGroups" :key="group.label" class="dts-document-group"><div class="dts-document-group-title"><b>{{ group.label }}</b><span>{{ group.items.length }} 份</span></div><article v-for="doc in group.items" :key="doc.key" class="dts-document-card"><header><div><b>{{ doc.title }}</b><span>{{ doc.subtitle }}</span></div><div><el-tag :type="doc.tone" size="small">{{ doc.status }}</el-tag><span>{{ dateTime(doc.time) }}</span></div></header><div class="dts-document-fields"><div v-for="field in doc.fields" :key="field.label" :class="{\'is-wide\':field.wide}"><label>{{ field.label }}</label><p>{{ field.value }}</p></div></div></article></section>',
      '              </div><el-empty v-else description="本次就诊暂无其他医疗文书"></el-empty></el-tab-pane>',
      '          </el-tabs>',
      '        </template>',
      '      </main>',
      '    </div>',
      '  </el-drawer>',
      '</div>'
    ].join('\n')
  };
})();
