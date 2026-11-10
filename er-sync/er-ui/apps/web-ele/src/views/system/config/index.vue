<template>
  <BiTablePage
    title="参数列表"
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
        <el-form-item label="参数名称" prop="configName">
          <el-input
            v-model="queryParams.configName"
            placeholder="请输入参数名称"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="参数键名" prop="configKey">
          <el-input
            v-model="queryParams.configKey"
            placeholder="请输入参数键名"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="系统内置" prop="configType">
          <el-select
            v-model="queryParams.configType"
            placeholder="系统内置"
            clearable
          >
            <el-option
              v-for="dict in dict.type.sys_yes_no"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="创建时间">
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
        type="primary"
        icon="el-icon-plus"
        @click="handleAdd"
        v-hasPermi="['system:config:add']"
        >新增参数</el-button
      >

      <el-button
        icon="el-icon-edit"
        :disabled="single"
        @click="handleUpdate"
        v-hasPermi="['system:config:edit']"
        >编辑</el-button
      >

      <el-button
        type="danger"
        icon="el-icon-delete"
        :disabled="multiple"
        @click="handleDelete"
        v-hasPermi="['system:config:remove']"
        >删除</el-button
      >

      <el-button
        icon="el-icon-download"
        @click="handleExport"
        v-hasPermi="['system:config:export']"
        >导出</el-button
      >

      <el-button
        icon="el-icon-refresh"
        @click="handleRefreshCache"
        v-hasPermi="['system:config:remove']"
        >刷新缓存</el-button
      >

      <right-toolbar
        v-model:showSearch="showSearch"
        @queryTable="getList"
      ></right-toolbar>
    </template>

    <el-table
      height="100%"
      v-loading="loading"
      :data="configList"
      @selection-change="handleSelectionChange"
    >
      <el-table-column
        header-align="center"
        type="selection"
        width="55"
        align="center"
      />
      <el-table-column
        header-align="center"
        label="参数主键"
        align="center"
        prop="configId"
      />
      <el-table-column
        header-align="center"
        label="参数名称"
        align="center"
        prop="configName"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        header-align="center"
        label="参数键名"
        align="center"
        prop="configKey"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        header-align="center"
        label="参数键值"
        align="center"
        prop="configValue"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        header-align="center"
        label="系统内置"
        align="center"
        prop="configType"
      >
        <template #default="scope">
          <dict-tag
            :options="dict.type.sys_yes_no"
            :value="scope.row.configType"
          />
        </template>
      </el-table-column>
      <el-table-column
        header-align="center"
        label="备注"
        align="center"
        prop="remark"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        header-align="center"
        label="创建时间"
        align="center"
        prop="createTime"
        width="180"
      >
        <template #default="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        header-align="center"
        label="操作"
        align="center"
        fixed="right"
        width="220"
      >
        <template #default="scope">
          <el-button
            size="small"
            link
            type="primary"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:config:edit']"
            >编辑</el-button
          >
          <el-button
            size="small"
            link
            type="primary"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:config:remove']"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <template #pagination>
      <pagination
        :total="total"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </template>

    <!-- 添加或修改参数配置对话框 -->
    <el-drawer
      class="bi-management-drawer"
      :title="title"
      v-model="open"
      size="600px"
      append-to-body
    >
      <el-form
        class="bi-editor-form"
        label-position="top"
        ref="form"
        :model="form"
        :rules="rules"
        label-width="80px"
      >
        <el-form-item label="参数名称" prop="configName">
          <el-input v-model="form.configName" placeholder="请输入参数名称" />
        </el-form-item>
        <el-form-item label="参数键名" prop="configKey">
          <el-input v-model="form.configKey" placeholder="请输入参数键名" />
        </el-form-item>
        <el-form-item label="参数键值" prop="configValue">
          <el-input
            v-model="form.configValue"
            type="textarea"
            :rows="3"
            placeholder="请输入参数键值"
          />
        </el-form-item>
        <el-form-item label="系统内置" prop="configType">
          <el-radio-group v-model="form.configType">
            <el-radio
              v-for="dict in dict.type.sys_yes_no"
              :key="dict.value"
              :value="dict.value"
              >{{ dict.label }}</el-radio
            >
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            placeholder="请输入内容"
          />
        </el-form-item>
      </el-form>
      <template #footer
        ><div class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div></template
      >
    </el-drawer>
  </BiTablePage>
</template>

<script>
import {
  listConfig,
  getConfig,
  delConfig,
  addConfig,
  updateConfig,
  refreshCache,
} from '#/legacy/api/system/config';

export default {
  name: 'Config',
  dicts: ['sys_yes_no'],
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 参数表格数据
      configList: [],
      // 弹出层标题
      title: '',
      // 是否显示弹出层
      open: false,
      // 日期范围
      dateRange: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        configName: undefined,
        configKey: undefined,
        configType: undefined,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        configName: [
          { required: true, message: '参数名称不能为空', trigger: 'blur' },
        ],
        configKey: [
          { required: true, message: '参数键名不能为空', trigger: 'blur' },
        ],
        configValue: [
          { required: true, message: '参数键值不能为空', trigger: 'blur' },
        ],
      },
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询参数列表 */
    getList() {
      this.loading = true;
      listConfig(this.addDateRange(this.queryParams, this.dateRange)).then(
        (response) => {
          this.configList = response.rows;
          this.total = response.total;
          this.loading = false;
        },
      );
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 表单重置
    reset() {
      this.form = {
        configId: undefined,
        configName: undefined,
        configKey: undefined,
        configValue: undefined,
        configType: 'Y',
        remark: undefined,
      };
      this.$refs.form?.clearValidate();
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
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = '添加参数';
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.configId);
      this.single = selection.length != 1;
      this.multiple = !selection.length;
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const configId = row.configId || this.ids;
      getConfig(configId).then((response) => {
        this.form = response.data;
        this.open = true;
        this.title = '修改参数';
      });
    },
    /** 提交按钮 */
    submitForm: function () {
      this.$refs['form'].validate((valid) => {
        if (valid) {
          if (this.form.configId != undefined) {
            updateConfig(this.form).then((response) => {
              this.$modal.msgSuccess('修改成功');
              this.open = false;
              this.getList();
            });
          } else {
            addConfig(this.form).then((response) => {
              this.$modal.msgSuccess('新增成功');
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const configIds = row.configId || this.ids;
      this.$modal
        .confirm('是否确认删除参数编号为"' + configIds + '"的数据项？')
        .then(function () {
          return delConfig(configIds);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download(
        'system/config/export',
        {
          ...this.queryParams,
        },
        `config_${new Date().getTime()}.xlsx`,
      );
    },
    /** 刷新缓存按钮操作 */
    handleRefreshCache() {
      refreshCache().then(() => {
        this.$modal.msgSuccess('刷新成功');
      });
    },
  },
};
</script>
