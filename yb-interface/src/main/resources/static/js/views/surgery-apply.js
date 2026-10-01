/* ============================================================================
 * 手麻P0: 手术申请管理(SurgeryApply) — 规范2.2.2.3.7.7/14/22 + 通知管理(2.2.2.3.7.6)
 * 后端契约:
 *   /api/his/surgery-apply            list(visitType/status/deptId/日期/keyword) / {id} / POST / visit-search
 *                                     {id}/reconfirm 复核 | {id}/reject 退回 | {id}/void 作废 | {id}/resubmit 重提
 *   /api/his/surgery-apply/notify     list / send(批量, body=ids[]) / {id}/reply(回复登记)
 *   /api/his/surgery-auth-rule/candidates  主刀候选人(带 allowed/reason 权限标记)
 * 状态机: 1待复核 → 2已复核待安排 → (安排后)4已安排 → 5已完成; 1→3退回(可重提); 1/2/3→6作废
 * 字段口径: list/notify 为手写 SQL 蛇形键; 提交体为 DTO 驼峰键(expectTime "yyyy-MM-dd HH:mm:ss")。
 * 注册: HIS.views.SurgeryApply(菜单 surgery-apply, comp 同名)
 * ========================================================================== */
;(function () {
  'use strict';
  var HIS = (window.HIS = window.HIS || {});
  HIS.views = HIS.views || {};

  /* ===== 常量映射 ===== */
  const APPLY_STATUS = { 1: '待复核', 2: '待安排', 3: '已退回', 4: '已安排', 5: '已完成', 6: '已作废' };
  const APPLY_STATUS_TYPE = { 1: 'warning', 2: '', 3: 'danger', 4: 'primary', 5: 'success', 6: 'info' };
  const VISIT_TYPE = { 1: '住院', 2: '门诊', 3: '日间' };
  const DEADLINE = { 1: '择期', 2: '限期', 3: '急诊' };
  const DEADLINE_TYPE = { 1: 'info', 2: 'warning', 3: 'danger' };
  const SURGERY_LEVEL = { 1: '一级', 2: '二级', 3: '三级', 4: '四级' };
  const ANESTHESIA_TYPE = { 1: '全身麻醉', 2: '局部麻醉', 3: '椎管内麻醉', 4: '神经阻滞', 5: '复合麻醉', 6: '其他' };
  const NOTIFY_TYPE = { 1: '预约成功', 2: '安排变动', 3: '术前提醒' };
  const NOTIFY_STATUS = { 1: '待通知', 2: '已通知', 3: '已回复' };
  const NOTIFY_STATUS_TYPE = { 1: 'warning', 2: 'primary', 3: 'success' };
  const NOTIFY_CHANNEL = { 1: '短信', 2: '电话', 3: '站内' };

  function optsOf(map) {
    return Object.keys(map).map(function (k) { return { value: Number(k), label: map[k] }; });
  }
  const VISIT_TYPE_OPTS = optsOf(VISIT_TYPE);
  const LEVEL_OPTS = optsOf(SURGERY_LEVEL);
  const ANE_OPTS = optsOf(ANESTHESIA_TYPE);
  const DEADLINE_OPTS = optsOf(DEADLINE);
  const NOTIFY_TYPE_OPTS = optsOf(NOTIFY_TYPE);
  const NOTIFY_STATUS_OPTS = optsOf(NOTIFY_STATUS);

  /* ===== 工具 ===== */
  function orDash(v) { return (v === null || v === undefined || v === '') ? '-' : v; }
  function fmtDT(v) { return (v === null || v === undefined || v === '') ? '-' : String(v).replace('T', ' ').slice(0, 16); }
  function escHtml(v) {
    return String(v === null || v === undefined ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function confirmBox(msg, title) {
    return ElementPlus.ElMessageBox.confirm(msg, title || '操作确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' });
  }
  function isCancel(e) { return e === 'cancel' || e === 'close'; }
  function promptReason(title, placeholder) {
    return ElementPlus.ElMessageBox.prompt(placeholder || '请填写原因', title, {
      inputType: 'textarea', inputValidator: function (v) { return (v && v.trim()) ? true : '内容不能为空'; }
    });
  }
  function asaStatusLabel(s) { return APPLY_STATUS[s] || orDash(s); }
  function stTag(s) { var t = APPLY_STATUS_TYPE[s]; return t === undefined ? 'info' : t; }

  /* 申请表单空白模板 */
  function blankForm() {
    return {
      visitType: 1, inpVisitId: null, visitId: null,
      surgeryCode: '', surgeryName: '', surgeryLevel: null,
      anesthesiaType: null, surgeonId: null, expectTime: '',
      deadlineType: 1, preOpDiag: '', applyReason: '', specialReq: ''
    };
  }

  /* ========================================================================
   * SurgeryApply 手术申请管理(申请单 Tab + 通知管理 Tab)
   * ====================================================================== */
  HIS.views.SurgeryApply = {
    template: [
      '<div class="surg-apply">',
      '  <div class="page-title">手术申请管理</div>',
      '  <el-tabs v-model="tab" @tab-change="onTab">',
      '    <el-tab-pane label="申请单管理" name="apply">',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;flex-wrap:wrap;gap:8px;align-items:center">',
      '        <el-radio-group v-model="filters.status" size="small" @change="onSearch">',
      '          <el-radio-button :value="null">全部</el-radio-button>',
      '          <el-radio-button v-for="o in statusOpts" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>',
      '        </el-radio-group>',
      '        <el-select v-model="filters.visitType" clearable placeholder="就诊类型" size="small" style="width:110px">',
      '          <el-option v-for="o in visitTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '        <el-date-picker v-model="filters.dateRange" type="daterange" size="small" value-format="YYYY-MM-DD"',
      '          range-separator="至" start-placeholder="申请日" end-placeholder="申请日" style="width:230px"></el-date-picker>',
      '        <el-input v-model="filters.kw" clearable placeholder="单号/患者/手术/病历号" size="small" style="width:200px" @keyup.enter="onSearch"></el-input>',
      '        <el-button type="primary" size="small" @click="onSearch">查询</el-button>',
      '        <el-button type="primary" size="small" plain @click="openCreate" v-if="canApply">新增申请</el-button>',
      '      </div>',
      '      <el-table :data="rows" border size="small" v-loading="loading" max-height="calc(100vh - 268px)">',
      '        <el-table-column label="序号" width="52" align="center"><template #default="s">{{ seqNo(s.$index) }}</template></el-table-column>',
      '        <el-table-column prop="apply_no" label="申请单号" width="140"></el-table-column>',
      '        <el-table-column label="类型" width="60" align="center"><template #default="s"><el-tag size="small" :type="s.row.visit_type === 1 ? \'\' : \'warning\'">{{ vtLabel(s.row.visit_type) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者" width="86"></el-table-column>',
      '        <el-table-column label="性别/年龄" width="86" align="center"><template #default="s">{{ orDash(s.row.gender) }} / {{ orDash(s.row.age) }}</template></el-table-column>',
      '        <el-table-column prop="medical_no" label="病历号" width="110"><template #default="s">{{ orDash(s.row.medical_no) }}</template></el-table-column>',
      '        <el-table-column prop="bed_no" label="床号" width="70"><template #default="s">{{ orDash(s.row.bed_no) }}</template></el-table-column>',
      '        <el-table-column prop="surgery_name" label="手术名称" min-width="150" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="级别" width="64" align="center"><template #default="s">{{ levelLabel(s.row.surgery_level) }}</template></el-table-column>',
      '        <el-table-column prop="surgeon_name" label="拟主刀" width="86"><template #default="s">{{ orDash(s.row.surgeon_name) }}</template></el-table-column>',
      '        <el-table-column label="期望时间" width="130"><template #default="s">{{ fmtDT(s.row.expect_time) }}</template></el-table-column>',
      '        <el-table-column label="时限" width="64" align="center"><template #default="s"><el-tag size="small" :type="dlTag(s.row.deadline_type)">{{ dlLabel(s.row.deadline_type) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="apply_dept_name" label="申请科室" width="100" show-overflow-tooltip></el-table-column>',
      '        <el-table-column prop="apply_by_name" label="申请人" width="80"></el-table-column>',
      '        <el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag size="small" :type="stTag(s.row.status)">{{ asaStatusLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column label="操作" width="190" fixed="right"><template #default="s">',
      '          <span class="surg-op">',
      '            <template v-if="s.row.status === 1">',
      '              <el-button v-if="canReconfirm" link type="success" @click="doReconfirm(s.row)">复核通过</el-button>',
      '              <el-button v-if="canReconfirm" link type="danger" @click="doReject(s.row)">退回</el-button>',
      '            </template>',
      '            <template v-else-if="s.row.status === 3">',
      '              <el-button v-if="canApply" link type="primary" @click="doResubmit(s.row)">重新提交</el-button>',
      '            </template>',
      '            <template v-else-if="s.row.status === 2">',
      '              <span style="font-size:12px;color:var(--yb-ink-3)">手术室"手术未安排"池安排</span>',
      '            </template>',
      '            <el-button v-if="s.row.status !== 4 && s.row.status !== 5 && s.row.status !== 6 && canApply" link type="info" @click="doVoid(s.row)">作废</el-button>',
      '            <el-button link type="primary" @click="openDetail(s.row.id)">查看</el-button>',
      '          </span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, sizes, prev, pager, next"',
      '        :total="total" :page-size="size" :page-sizes="[10, 20, 50]" :current-page="page" @current-change="onPage" @size-change="onSize"></el-pagination>',
      '    </el-tab-pane>',
      '    <el-tab-pane name="notify">',
      '      <template #label><span>通知管理<el-badge v-if="pendingNotify > 0" :value="pendingNotify" style="margin-left:6px"/></span></template>',
      '      <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center">',
      '        <el-select v-model="nFilters.status" clearable placeholder="通知状态" size="small" style="width:120px" @change="nSearch">',
      '          <el-option v-for="o in notifyStatusOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '        <el-select v-model="nFilters.notifyType" clearable placeholder="通知类型" size="small" style="width:120px" @change="nSearch">',
      '          <el-option v-for="o in notifyTypeOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '        </el-select>',
      '        <el-button type="primary" size="small" @click="nSearch">查询</el-button>',
      '        <el-button type="success" size="small" :disabled="!nSel.length" @click="doSend">批量发送({{ nSel.length }})</el-button>',
      '        <span style="font-size:12px;color:var(--yb-ink-3)">短信通道未接入, "发送"为状态留痕(模拟)</span>',
      '      </div>',
      '      <el-table :data="nRows" border size="small" v-loading="nLoading" @selection-change="function (v) { nSel = v; }" max-height="calc(100vh - 268px)">',
      '        <el-table-column type="selection" width="42" :selectable="function (r) { return r.status === 1; }"></el-table-column>',
      '        <el-table-column label="序号" width="52" align="center"><template #default="s">{{ (nP - 1) * nSize + s.$index + 1 }}</template></el-table-column>',
      '        <el-table-column prop="patient_name" label="患者" width="86"></el-table-column>',
      '        <el-table-column prop="phone" label="电话" width="116"><template #default="s">{{ orDash(s.row.phone) }}</template></el-table-column>',
      '        <el-table-column label="类型" width="90" align="center"><template #default="s">{{ ntLabel(s.row.notify_type) }}</template></el-table-column>',
      '        <el-table-column label="渠道" width="70" align="center"><template #default="s">{{ chLabel(s.row.channel) }}</template></el-table-column>',
      '        <el-table-column prop="content" label="通知内容" min-width="240" show-overflow-tooltip></el-table-column>',
      '        <el-table-column label="状态" width="84" align="center"><template #default="s"><el-tag size="small" :type="nsTag(s.row.status)">{{ nsLabel(s.row.status) }}</el-tag></template></el-table-column>',
      '        <el-table-column prop="reply_content" label="患者回复" width="140" show-overflow-tooltip><template #default="s">{{ orDash(s.row.reply_content) }}</template></el-table-column>',
      '        <el-table-column label="发送人/时间" width="150"><template #default="s">{{ orDash(s.row.send_by) }} {{ s.row.send_time ? fmtDT(s.row.send_time) : "" }}</template></el-table-column>',
      '        <el-table-column label="操作" width="110" fixed="right"><template #default="s">',
      '          <el-button v-if="s.row.status !== 3" link type="primary" @click="doReply(s.row)">回复登记</el-button>',
      '          <span v-else style="font-size:12px;color:var(--yb-ink-3)">已回复</span>',
      '        </template></el-table-column>',
      '      </el-table>',
      '      <el-pagination style="margin-top:12px;justify-content:flex-end;" background layout="total, prev, pager, next"',
      '        :total="nTotal" :page-size="nSize" :current-page="nP" @current-change="onNPage"></el-pagination>',
      '    </el-tab-pane>',
      '  </el-tabs>',
      /* ===== 新增申请对话框 ===== */
      '  <el-dialog v-model="dlgVisible" title="新增手术申请" width="820px" :close-on-click-modal="false" append-to-body>',
      '    <el-form :model="form" label-width="100px">',
      '      <el-form-item label="就诊类型" required>',
      '        <el-radio-group v-model="form.visitType" @change="onVisitType">',
      '          <el-radio-button :value="1">住院</el-radio-button><el-radio-button :value="2">门诊</el-radio-button><el-radio-button :value="3">日间</el-radio-button>',
      '        </el-radio-group>',
      '      </el-form-item>',
      '      <el-form-item :label="form.visitType === 1 ? \'住院患者\' : \'门诊患者\'" required>',
      '        <el-select v-if="form.visitType === 1" v-model="form.inpVisitId" filterable remote reserve-keyword',
      '          :remote-method="searchInp" :loading="ptLoading" placeholder="输入姓名/住院号检索在院患者" style="width:100%" @change="onPickInp">',
      '          <el-option v-for="p in inpPatients" :key="p.id" :label="inpLabel(p)" :value="p.id"></el-option>',
      '        </el-select>',
      '        <el-select v-else v-model="form.visitId" filterable remote reserve-keyword',
      '          :remote-method="searchOutp" :loading="ptLoading" placeholder="输入姓名/门诊号检索就诊记录" style="width:100%" @change="onPickOutp">',
      '          <el-option v-for="v in outpVisits" :key="v.visit_id" :label="outpLabel(v)" :value="v.visit_id"></el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <div v-if="pickedInfo" class="surg-info" style="margin:0 0 12px 100px;max-width:640px">{{ pickedInfo }}</div>',
      '      <el-row :gutter="16">',
      '        <el-col :span="12"><el-form-item label="手术编码"><el-input v-model="form.surgeryCode" maxlength="50" placeholder="ICD-9-CM-3"></el-input></el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="手术名称" required><el-input v-model="form.surgeryName" maxlength="100" placeholder="手术名称" @change="loadCandidates"></el-input></el-form-item></el-col>',
      '      </el-row>',
      '      <el-row :gutter="16">',
      '        <el-col :span="12"><el-form-item label="手术级别">',
      '          <el-select v-model="form.surgeryLevel" clearable placeholder="选择级别" style="width:100%" @change="loadCandidates">',
      '            <el-option v-for="o in levelOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="拟麻醉方式">',
      '          <el-select v-model="form.anesthesiaType" clearable placeholder="选择麻醉方式" style="width:100%">',
      '            <el-option v-for="o in aneOpts" :key="o.value" :label="o.label" :value="o.value"></el-option>',
      '          </el-select>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="拟主刀医师" required>',
      '        <el-select v-model="form.surgeonId" filterable :loading="candLoading" placeholder="按手术级别自动过滤权限, 无权限医师灰显并标注原因" style="width:100%">',
      '          <el-option v-for="c in candidates" :key="c.id" :label="candLabel(c)" :value="c.id" :disabled="!c.allowed">',
      '            <span :style="c.allowed ? \'\' : \'color:var(--yb-ink-4)\'">{{ c.staff_name }}</span>',
      '            <span style="float:right;font-size:12px" :class="c.allowed ? \'\' : \'surg-money-red\'">{{ c.allowed ? (orDash(c.surgery_level) + \'级\') : c.reason }}</span>',
      '          </el-option>',
      '        </el-select>',
      '      </el-form-item>',
      '      <el-row :gutter="16">',
      '        <el-col :span="12"><el-form-item label="期望手术时间">',
      '          <el-date-picker v-model="form.expectTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="精确到分" style="width:100%"></el-date-picker>',
      '        </el-form-item></el-col>',
      '        <el-col :span="12"><el-form-item label="手术时限">',
      '          <el-radio-group v-model="form.deadlineType">',
      '            <el-radio-button :value="1">择期</el-radio-button><el-radio-button :value="2">限期</el-radio-button><el-radio-button :value="3">急诊</el-radio-button>',
      '          </el-radio-group>',
      '        </el-form-item></el-col>',
      '      </el-row>',
      '      <el-form-item label="术前诊断"><el-input v-model="form.preOpDiag" maxlength="100"></el-input></el-form-item>',
      '      <el-form-item label="病情简介"><el-input v-model="form.applyReason" type="textarea" :rows="2" maxlength="300"></el-input></el-form-item>',
      '      <el-form-item label="特殊要求"><el-input v-model="form.specialReq" type="textarea" :rows="2" maxlength="200" placeholder="体位/特殊耗材/自体血回输等"></el-input></el-form-item>',
      '    </el-form>',
      '    <template #footer>',
      '      <el-button @click="dlgVisible = false">取消</el-button>',
      '      <el-button type="primary" :loading="saving" @click="submit">提交申请</el-button>',
      '    </template>',
      '  </el-dialog>',
      /* ===== 申请单详情抽屉 ===== */
      '  <el-drawer v-model="detailVisible" title="手术申请单" size="620px" :destroy-on-close="true">',
      '    <div v-loading="detailLoading">',
      '      <template v-if="detail">',
      '        <el-descriptions :column="2" border size="small">',
      '          <el-descriptions-item label="申请单号">{{ detail.applyNo }}</el-descriptions-item>',
      '          <el-descriptions-item label="状态"><el-tag size="small" :type="stTag(detail.status)">{{ asaStatusLabel(detail.status) }}</el-tag></el-descriptions-item>',
      '          <el-descriptions-item label="就诊类型">{{ vtLabel(detail.visitType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="时限">{{ dlLabel(detail.deadlineType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="患者">{{ detail.patientName }} {{ orDash(detail.gender) }} {{ orDash(detail.age) }}</el-descriptions-item>',
      '          <el-descriptions-item label="病历号">{{ orDash(detail.medicalNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="床号">{{ orDash(detail.bedNo) }}</el-descriptions-item>',
      '          <el-descriptions-item label="电话">{{ orDash(detail.phone) }}</el-descriptions-item>',
      '          <el-descriptions-item label="手术名称">{{ detail.surgeryName }}</el-descriptions-item>',
      '          <el-descriptions-item label="级别/编码">{{ levelLabel(detail.surgeryLevel) }} / {{ orDash(detail.surgeryCode) }}</el-descriptions-item>',
      '          <el-descriptions-item label="拟麻醉">{{ aneLabel(detail.anesthesiaType) }}</el-descriptions-item>',
      '          <el-descriptions-item label="拟主刀">{{ orDash(detail.surgeonName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="期望时间">{{ fmtDT(detail.expectTime) }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请科室">{{ orDash(detail.applyDeptName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请人">{{ orDash(detail.applyByName) }}</el-descriptions-item>',
      '          <el-descriptions-item label="申请时间">{{ fmtDT(detail.applyTime) }}</el-descriptions-item>',
      '          <el-descriptions-item label="复核人">{{ orDash(detail.reconfirmBy) }}</el-descriptions-item>',
      '          <el-descriptions-item label="复核时间">{{ fmtDT(detail.reconfirmTime) }}</el-descriptions-item>',
      '        </el-descriptions>',
      '        <div v-if="detail.rejectReason" class="surg-info" style="margin-top:12px;border-color:var(--yb-danger)"><b style="color:var(--yb-danger)">退回原因:</b> {{ detail.rejectReason }}</div>',
      '        <div v-if="detail.cancelReason" class="surg-info" style="margin-top:12px"><b>作废原因:</b> {{ detail.cancelReason }}</div>',
      '        <div v-if="detail.preOpDiag || detail.applyReason || detail.specialReq" class="surg-info" style="margin-top:12px">',
      '          <div><b>术前诊断:</b> {{ orDash(detail.preOpDiag) }}</div>',
      '          <div><b>病情简介:</b> {{ orDash(detail.applyReason) }}</div>',
      '          <div><b>特殊要求:</b> {{ orDash(detail.specialReq) }}</div>',
      '        </div>',
      '        <div v-if="detail.surgeryId" style="margin-top:12px">',
      '          <el-button type="primary" plain size="small" @click="goSurgery">查看已安排手术</el-button>',
      '        </div>',
      '        <div class="surg-section-title" style="margin-top:14px">关联术前医嘱（共 {{ preOpOrders.length }} 条）</div>',
      '        <el-table v-if="preOpOrders.length" :data="preOpOrders" border size="small" max-height="220">',
      '          <el-table-column prop="orderContent" label="医嘱内容" min-width="160" show-overflow-tooltip></el-table-column>',
      '          <el-table-column label="状态" width="80" align="center"><template #default="s">{{ ({1:\"新开\",2:\"已审核\",3:\"执行中\",4:\"已完成\",5:\"已停止\",6:\"已作废\"})[s.row.orderStatus] || \'-\' }}</template></el-table-column>',
      '          <el-table-column label="代开" width="60" align="center"><template #default="s">{{ s.row.proxyDoctorId ? \'是\' : \'-\' }}</template></el-table-column>',
      '        </el-table>',
      '        <div v-else class="surg-info" style="margin-top:6px">该申请暂无关联术前医嘱。</div>',
      '      </template>',
      '    </div>',
      '  </el-drawer>',
      '</div>'
    ].join('\n'),
    data: function () {
      return {
        tab: 'apply',
        rows: [], total: 0, page: 1, size: 20, loading: false,
        filters: { status: null, visitType: null, dateRange: [], kw: '' },
        statusOpts: optsOf(APPLY_STATUS),
        visitTypeOpts: VISIT_TYPE_OPTS,
        /* 新增申请 */
        dlgVisible: false, saving: false, form: blankForm(),
        inpPatients: [], outpVisits: [], ptLoading: false, pickedInfo: '',
        candidates: [], candLoading: false,
        levelOpts: LEVEL_OPTS, aneOpts: ANE_OPTS,
        /* 详情 */
        detailVisible: false, detailLoading: false, detail: null,
        preOpOrders: [],
        /* 通知管理 */
        nRows: [], nTotal: 0, nP: 1, nSize: 20, nLoading: false, nSel: [], pendingNotify: 0,
        nFilters: { status: 1, notifyType: null },
        notifyStatusOpts: NOTIFY_STATUS_OPTS, notifyTypeOpts: NOTIFY_TYPE_OPTS
      };
    },
    computed: {
      canApply: function () {
        return HIS.hasRole('DOCTOR') || HIS.hasRole('NURSE') || HIS.hasRole('ADMIN')
          || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      },
      canReconfirm: function () {
        return HIS.hasRole('NURSE') || HIS.hasRole('DOCTOR') || HIS.hasRole('ADMIN')
          || HIS.hasRole('ORG_ADMIN') || HIS.hasRole('SUPER_ADMIN');
      }
    },
    methods: {
      orDash: orDash, fmtDT: fmtDT,
      seqNo: function (i) { return (this.page - 1) * this.size + i + 1; },
      vtLabel: function (v) { return VISIT_TYPE[v] || orDash(v); },
      dlLabel: function (v) { return DEADLINE[v] || orDash(v); },
      dlTag: function (v) { return DEADLINE_TYPE[v] || 'info'; },
      levelLabel: function (v) { return SURGERY_LEVEL[v] || orDash(v); },
      aneLabel: function (v) { return ANESTHESIA_TYPE[v] || orDash(v); },
      ntLabel: function (v) { return NOTIFY_TYPE[v] || orDash(v); },
      nsLabel: function (v) { return NOTIFY_STATUS[v] || orDash(v); },
      nsTag: function (v) { return NOTIFY_STATUS_TYPE[v] || 'info'; },
      chLabel: function (v) { return NOTIFY_CHANNEL[v] || orDash(v); },
      asaStatusLabel: asaStatusLabel, stTag: stTag,
      candLabel: function (c) { return c.staff_name + (c.allowed ? '' : '（无权限）'); },
      /* ===== 申请单列表 ===== */
      load: function () {
        var vm = this;
        vm.loading = true;
        var q = '/api/his/surgery-apply/list?page=' + vm.page + '&size=' + vm.size;
        if (vm.filters.status !== null && vm.filters.status !== '') { q += '&status=' + vm.filters.status; }
        if (vm.filters.visitType) { q += '&visitType=' + vm.filters.visitType; }
        if (vm.filters.dateRange && vm.filters.dateRange.length === 2) {
          q += '&startDate=' + vm.filters.dateRange[0] + '&endDate=' + vm.filters.dateRange[1];
        }
        if (vm.filters.kw) { q += '&keyword=' + encodeURIComponent(vm.filters.kw); }
        HIS.get(q).then(function (d) {
          vm.rows = (d && d.records) || [];
          vm.total = (d && d.total) || 0;
          vm.loading = false;
        }).catch(function (e) { vm.loading = false; HIS.notifyError(e); });
      },
      onSearch: function () { this.page = 1; this.load(); },
      onPage: function (p) { this.page = p; this.load(); },
      onSize: function (s) { this.size = s; this.page = 1; this.load(); },
      onTab: function (name) {
        if ((name || this.tab) === 'notify') { this.nLoad(); }
      },
      /* ===== 复核 / 退回 / 作废 / 重提 ===== */
      doReconfirm: function (row) {
        var vm = this;
        confirmBox('确认复核通过申请单【' + escHtml(row.apply_no) + ' - ' + escHtml(row.patient_name) + '】？通过后将合并确认该申请关联的术前医嘱(新开→已审核), 并进入手术室待安排池。', '病区复核(合并确认手术医嘱)')
          .then(function () { return HIS.put('/api/his/surgery-apply/' + row.id + '/reconfirm'); })
          .then(function () { HIS.notifySuccess('已复核通过, 关联术前医嘱已合并审核, 进入待安排池'); vm.load(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doReject: function (row) {
        var vm = this;
        promptReason('退回申请单【' + row.apply_no + '】', '请填写退回原因(退回复核医师可见)')
          .then(function (r) { return HIS.put('/api/his/surgery-apply/' + row.id + '/reject', { reason: r.value }); })
          .then(function () { HIS.notifySuccess('已退回'); vm.load(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doVoid: function (row) {
        var vm = this;
        promptReason('作废申请单【' + row.apply_no + '】', '请填写作废原因')
          .then(function (r) { return HIS.put('/api/his/surgery-apply/' + row.id + '/void', { reason: r.value }); })
          .then(function () { HIS.notifySuccess('已作废'); vm.load(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doResubmit: function (row) {
        var vm = this;
        confirmBox('确认将退回的申请单【' + escHtml(row.apply_no) + '】原内容重新提交复核？', '重新提交')
          .then(function () { return HIS.put('/api/his/surgery-apply/' + row.id + '/resubmit', {}); })
          .then(function () { HIS.notifySuccess('已重新提交待复核'); vm.load(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      /* ===== 新增申请 ===== */
      openCreate: function () {
        this.form = blankForm();
        this.pickedInfo = '';
        this.candidates = [];
        this.dlgVisible = true;
        this.searchInp('');
        this.loadCandidates();
      },
      onVisitType: function () {
        this.form.inpVisitId = null;
        this.form.visitId = null;
        this.pickedInfo = '';
        if (this.form.visitType === 1) { this.searchInp(''); } else { this.searchOutp(''); }
      },
      searchInp: function (kw) {
        var vm = this;
        vm.ptLoading = true;
        var q = '/api/his/inp/patients?visitStatus=2&page=1&size=30';
        var org = HIS.currentOrgId();
        if (org) { q += '&orgId=' + org; }
        if (kw) { q += '&keyword=' + encodeURIComponent(kw); }
        HIS.get(q).then(function (d) {
          vm.inpPatients = (d && d.records) || [];
          vm.ptLoading = false;
        }).catch(function (e) { vm.ptLoading = false; HIS.notifyError(e); });
      },
      inpLabel: function (p) {
        return orDash(p.patient_name) + (p.gender_name ? '·' + p.gender_name : '')
          + (p.age !== null && p.age !== undefined ? '·' + p.age + '岁' : '')
          + '  住院号 ' + orDash(p.inp_no) + (p.bed_no ? ' 床 ' + p.bed_no : '');
      },
      onPickInp: function (id) {
        var p = null;
        for (var i = 0; i < this.inpPatients.length; i++) {
          if (this.inpPatients[i].id === id) { p = this.inpPatients[i]; break; }
        }
        this.pickedInfo = p ? (p.patient_name + ' ' + orDash(p.gender_name) + ' ' + orDash(p.age) + '岁　住院号: '
          + orDash(p.inp_no) + '　科室: ' + orDash(p.dept_name) + '　床号: ' + orDash(p.bed_no)) : '';
      },
      searchOutp: function (kw) {
        var vm = this;
        vm.ptLoading = true;
        var q = '/api/his/surgery-apply/visit-search';
        if (kw) { q += '?keyword=' + encodeURIComponent(kw); }
        HIS.get(q).then(function (l) {
          vm.outpVisits = l || [];
          vm.ptLoading = false;
        }).catch(function (e) { vm.ptLoading = false; HIS.notifyError(e); });
      },
      outpLabel: function (v) {
        return orDash(v.patient_name) + (v.gender_name ? '·' + v.gender_name : '')
          + '  门诊号 ' + orDash(v.ipt_otp_no) + '  ' + orDash(v.dept_name);
      },
      onPickOutp: function (id) {
        var v = null;
        for (var i = 0; i < this.outpVisits.length; i++) {
          if (this.outpVisits[i].visit_id === id) { v = this.outpVisits[i]; break; }
        }
        this.pickedInfo = v ? (v.patient_name + ' ' + orDash(v.gender_name) + ' ' + orDash(v.age)
          + '　门诊号: ' + orDash(v.ipt_otp_no) + '　科室: ' + orDash(v.dept_name)) : '';
      },
      /* 主刀候选: 按手术编码/名称/级别调用权限规则, 返回带 allowed/reason 标记 */
      loadCandidates: function () {
        var vm = this;
        vm.candLoading = true;
        var q = '/api/his/surgery-auth-rule/candidates?';
        if (vm.form.surgeryCode) { q += 'surgeryCode=' + encodeURIComponent(vm.form.surgeryCode) + '&'; }
        if (vm.form.surgeryName) { q += 'surgeryName=' + encodeURIComponent(vm.form.surgeryName) + '&'; }
        if (vm.form.surgeryLevel) { q += 'surgeryLevel=' + vm.form.surgeryLevel; }
        HIS.get(q).then(function (l) {
          vm.candidates = l || [];
          vm.candLoading = false;
          /* 已选主刀若因级别变化失去权限, 清空强制重选 */
          if (vm.form.surgeonId) {
            var hit = vm.candidates.filter(function (c) { return c.id === vm.form.surgeonId && c.allowed; });
            if (!hit.length && vm.candidates.length) { vm.form.surgeonId = null; }
          }
        }).catch(function (e) { vm.candLoading = false; HIS.notifyError(e); });
      },
      submit: function () {
        var vm = this;
        var f = vm.form;
        if (f.visitType === 1 && !f.inpVisitId) { HIS.notifyError('请选择住院患者'); return; }
        if (f.visitType !== 1 && !f.visitId) { HIS.notifyError('请选择门诊就诊记录'); return; }
        if (!f.surgeryName) { HIS.notifyError('请填写手术名称'); return; }
        if (!f.surgeonId) { HIS.notifyError('请选择拟主刀医师'); return; }
        var body = {
          visitType: f.visitType,
          inpVisitId: f.visitType === 1 ? f.inpVisitId : null,
          visitId: f.visitType === 1 ? null : f.visitId,
          surgeryCode: f.surgeryCode, surgeryName: f.surgeryName, surgeryLevel: f.surgeryLevel,
          anesthesiaType: f.anesthesiaType, surgeonId: f.surgeonId,
          expectTime: f.expectTime || null, deadlineType: f.deadlineType,
          preOpDiag: f.preOpDiag, applyReason: f.applyReason, specialReq: f.specialReq
        };
        vm.saving = true;
        HIS.post('/api/his/surgery-apply', body).then(function (a) {
          vm.saving = false;
          vm.dlgVisible = false;
          HIS.notifySuccess('申请单已提交: ' + (a && a.applyNo ? a.applyNo : ''));
          vm.page = 1;
          vm.load();
        }).catch(function (e) { vm.saving = false; HIS.notifyError(e); });
      },
      /* ===== 详情 ===== */
      openDetail: function (id) {
        var vm = this;
        vm.detail = null;
        vm.detailLoading = true;
        vm.detailVisible = true;
        HIS.get('/api/his/surgery-apply/' + id).then(function (d) {
          vm.detail = d || null;
          vm.detailLoading = false;
          vm.preOpOrders = [];
          HIS.get('/api/his/surgery/apply/' + HIS.idParam(id) + '/orders').then(function (l) { vm.preOpOrders = l || []; }).catch(function () { vm.preOpOrders = []; });
        }).catch(function (e) { vm.detailLoading = false; HIS.notifyError(e); });
      },
      goSurgery: function () {
        /* 联动跳转手术管理页(菜单 key 存在时): 广播定位由 app.js 菜单切换承担, 此处仅提示单号 */
        HIS.notifySuccess('手术 #' + (this.detail && this.detail.surgeryId) + ', 请到"手术管理-手术已安排"查看');
      },
      /* ===== 通知管理 ===== */
      nLoad: function () {
        var vm = this;
        vm.nLoading = true;
        var q = '/api/his/surgery-apply/notify/list?page=' + vm.nP + '&size=' + vm.nSize;
        if (vm.nFilters.status !== null && vm.nFilters.status !== '') { q += '&status=' + vm.nFilters.status; }
        if (vm.nFilters.notifyType) { q += '&notifyType=' + vm.nFilters.notifyType; }
        HIS.get(q).then(function (d) {
          vm.nRows = (d && d.records) || [];
          vm.nTotal = (d && d.total) || 0;
          vm.nLoading = false;
          if (vm.nFilters.status === 1) { vm.pendingNotify = vm.nTotal; }
        }).catch(function (e) { vm.nLoading = false; HIS.notifyError(e); });
      },
      nSearch: function () { this.nP = 1; this.nLoad(); },
      onNPage: function (p) { this.nP = p; this.nLoad(); },
      doSend: function () {
        var vm = this;
        var ids = vm.nSel.map(function (r) { return r.id; });
        confirmBox('确认批量发送 ' + ids.length + ' 条通知？(短信通道未接入, 仅状态留痕)', '批量发送')
          .then(function () { return HIS.put('/api/his/surgery-apply/notify/send', ids); })
          .then(function (n) { HIS.notifySuccess('已发送 ' + n + ' 条'); vm.nLoad(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      },
      doReply: function (row) {
        var vm = this;
        promptReason('登记患者回复【' + row.patient_name + '】', '如: 已知晓, 按时到院 / 申请改期')
          .then(function (r) { return HIS.put('/api/his/surgery-apply/notify/' + row.id + '/reply',
            { replyContent: r.value, channel: 2 }); })
          .then(function () { HIS.notifySuccess('回复已登记'); vm.nLoad(); })
          .catch(function (e) { if (!isCancel(e)) { HIS.notifyError(e); } });
      }
    },
    mounted: function () {
      this.load();
      /* 待通知角标(进入通知 Tab 前也可见) */
      var vm = this;
      HIS.get('/api/his/surgery/notify/pending-count').then(function (n) { vm.pendingNotify = n || 0; }).catch(function () { });
    }
  };
})();
