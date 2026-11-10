<template>
  <BiTablePage
    title="调度日志"
    :show-search="showSearch"
    :show-pagination="total > 0"
  >
    <template #search>
      <el-form
        :model="queryParams"
        ref="queryForm"
        :inline="true"
        v-show="showSearch"
      >
        <el-form-item label="任务名称" prop="jobName">
          <el-input
            v-model="queryParams.jobName"
            placeholder="请输入任务名称"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="任务组名" prop="jobGroup">
          <el-select
            v-model="queryParams.jobGroup"
            placeholder="请选择任务组名"
            clearable
          >
            <el-option
              v-for="dict in dict.type.sys_job_group"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="执行状态" prop="status">
          <el-select
            v-model="queryParams.status"
            placeholder="请选择执行状态"
            clearable
          >
            <el-option
              v-for="dict in dict.type.sys_common_status"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="执行时间">
          <el-date-picker
            v-model="dateRange"
            value-format="YYYY-MM-DD"
            type="daterange"
            range-separator="-"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          ></el-date-picker>
        </el-form-item>
        <el-form-item class="bi-search-actions">
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery"
            >搜索</el-button
          >
        </el-form-item>
      </el-form>
    </template>
    <template #toolbar>
      <el-button
        type="danger"
        icon="el-icon-delete"
        :disabled="multiple"
        @click="handleDelete"
        v-hasPermi="['monitor:job:remove']"
        >删除</el-button
      >
      <el-button
        type="danger"
        icon="el-icon-delete"
        @click="handleClean"
        v-hasPermi="['monitor:job:remove']"
        >清空</el-button
      >
      <el-button
        icon="el-icon-download"
        @click="handleExport"
        v-hasPermi="['monitor:job:export']"
        >导出</el-button
      >
      <el-button icon="el-icon-close" @click="handleClose">关闭</el-button>
      <right-toolbar
        v-model:showSearch="showSearch"
        @queryTable="getList"
      ></right-toolbar>
    </template>
    <el-table
      height="100%"
      v-loading="loading"
      :data="jobLogList"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column
        label="日志编号"
        width="80"
        align="center"
        prop="jobLogId"
      />
      <el-table-column
        label="任务名称"
        align="center"
        prop="jobName"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        label="任务组名"
        align="center"
        prop="jobGroup"
        :show-overflow-tooltip="true"
      >
        <template #default="scope">
          <dict-tag
            :options="dict.type.sys_job_group"
            :value="scope.row.jobGroup"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="调用目标字符串"
        align="center"
        prop="invokeTarget"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        label="日志信息"
        align="center"
        prop="jobMessage"
        :show-overflow-tooltip="true"
      />
      <el-table-column label="执行状态" align="center" prop="status">
        <template #default="scope">
          <dict-tag
            :options="dict.type.sys_common_status"
            :value="scope.row.status"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="执行时间"
        align="center"
        prop="createTime"
        width="180"
      >
        <template #default="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        align="center"
        class-name="small-padding fixed-width"
      >
        <template #default="scope">
          <el-button
            size="small"
            link
            type="primary"
            icon="el-icon-view"
            @click="handleView(scope.row)"
            v-hasPermi="['monitor:job:query']"
            >详细</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <template #pagination>
      <pagination
        v-show="total > 0"
        :total="total"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </template>
    <el-drawer
      class="bi-management-drawer"
      title="调度日志详细"
      v-model="open"
      size="700px"
      append-to-body
    >
      <el-form
        class="bi-editor-form"
        label-position="top"
        ref="form"
        :model="form"
        label-width="100px"
      >
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="日志序号：">{{ form.jobLogId }}</el-form-item>
            <el-form-item label="任务名称：">{{ form.jobName }}</el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务分组：">{{ form.jobGroup }}</el-form-item>
            <el-form-item label="执行时间：">{{
              form.createTime
            }}</el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="调用方法：">{{
              form.invokeTarget
            }}</el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="日志信息：">{{
              form.jobMessage
            }}</el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="执行状态：">
              <div v-if="form.status == 0">正常</div>
              <div v-else-if="form.status == 1">失败</div>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="异常信息：" v-if="form.status == 1">{{
              form.exceptionInfo
            }}</el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer
        ><div class="dialog-footer">
          <el-button @click="open = false">关 闭</el-button>
        </div></template
      >
    </el-drawer>
  </BiTablePage>
</template>

<script>
import { getJob } from '#/legacy/api/monitor/job';
import {
  listJobLog,
  delJobLog,
  cleanJobLog,
} from '#/legacy/api/monitor/jobLog';

export default {
  name: 'JobLog',
  dicts: ['sys_common_status', 'sys_job_group'],
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 调度日志表格数据
      jobLogList: [],
      // 是否显示弹出层
      open: false,
      // 日期范围
      dateRange: [],
      // 表单参数
      form: {},
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        jobName: undefined,
        jobGroup: undefined,
        status: undefined,
      },
    };
  },
  created() {
    const jobId = this.$route.params && this.$route.params.jobId;
    if (jobId !== undefined && jobId != 0) {
      getJob(jobId).then((response) => {
        this.queryParams.jobName = response.data.jobName;
        this.queryParams.jobGroup = response.data.jobGroup;
        this.getList();
      });
    } else {
      this.getList();
    }
  },
  methods: {
    /** 查询调度日志列表 */
    getList() {
      this.loading = true;
      listJobLog(this.addDateRange(this.queryParams, this.dateRange)).then(
        (response) => {
          this.jobLogList = response.rows;
          this.total = response.total;
          this.loading = false;
        },
      );
    },
    // 返回按钮
    handleClose() {
      const obj = { path: '/monitor/job' };
      this.$tab.closeOpenPage(obj);
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.dateRange = [];
      this.resetForm('queryForm');
      this.handleQuery();
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.jobLogId);
      this.multiple = !selection.length;
    },
    /** 详细按钮操作 */
    handleView(row) {
      this.open = true;
      this.form = row;
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const jobLogIds = this.ids;
      this.$modal
        .confirm('是否确认删除调度日志编号为"' + jobLogIds + '"的数据项？')
        .then(function () {
          return delJobLog(jobLogIds);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
    /** 清空按钮操作 */
    handleClean() {
      this.$modal
        .confirm('是否确认清空所有调度日志数据项？')
        .then(function () {
          return cleanJobLog();
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('清空成功');
        })
        .catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download(
        '/monitor/jobLog/export',
        {
          ...this.queryParams,
        },
        `log_${new Date().getTime()}.xlsx`,
      );
    },
  },
};
</script>
