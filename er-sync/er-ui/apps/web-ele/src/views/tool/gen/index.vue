<template>
  <BiTablePage
    title="代码生成"
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
        <el-form-item label="表名称" prop="tableName">
          <el-input
            v-model="queryParams.tableName"
            placeholder="请输入表名称"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="表描述" prop="tableComment">
          <el-input
            v-model="queryParams.tableComment"
            placeholder="请输入表描述"
            clearable
            @keyup.enter="handleQuery"
          />
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
        icon="el-icon-download"
        :disabled="multiple"
        @click="handleGenTable"
        v-hasPermi="['tool:gen:code']"
        >生成</el-button
      >
      <el-button
        type="primary"
        icon="el-icon-plus"
        @click="openCreateTable"
        v-hasRole="['admin']"
        >创建</el-button
      >
      <el-button
        icon="el-icon-upload"
        @click="openImportTable"
        v-hasPermi="['tool:gen:import']"
        >导入</el-button
      >
      <el-button
        icon="el-icon-edit"
        :disabled="single"
        @click="handleEditTable"
        v-hasPermi="['tool:gen:edit']"
        >修改</el-button
      >
      <el-button
        type="danger"
        icon="el-icon-delete"
        :disabled="multiple"
        @click="handleDelete"
        v-hasPermi="['tool:gen:remove']"
        >删除</el-button
      >
      <right-toolbar
        v-model:showSearch="showSearch"
        @queryTable="getList"
      ></right-toolbar>
    </template>
    <el-table
      height="100%"
      ref="tables"
      v-loading="loading"
      :data="tableList"
      @selection-change="handleSelectionChange"
      :default-sort="defaultSort"
      @sort-change="handleSortChange"
    >
      <el-table-column
        type="selection"
        align="center"
        width="55"
      ></el-table-column>
      <el-table-column label="序号" type="index" width="50" align="center">
        <template #default="scope">
          <span>{{
            (queryParams.pageNum - 1) * queryParams.pageSize + scope.$index + 1
          }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="表名称"
        align="center"
        prop="tableName"
        :show-overflow-tooltip="true"
        width="120"
      />
      <el-table-column
        label="表描述"
        align="center"
        prop="tableComment"
        :show-overflow-tooltip="true"
        width="120"
      />
      <el-table-column
        label="实体"
        align="center"
        prop="className"
        :show-overflow-tooltip="true"
        width="120"
      />
      <el-table-column
        label="创建时间"
        align="center"
        prop="createTime"
        sortable="custom"
        :sort-orders="['descending', 'ascending']"
        width="160"
      />
      <el-table-column
        label="更新时间"
        align="center"
        prop="updateTime"
        sortable="custom"
        :sort-orders="['descending', 'ascending']"
        width="160"
      />
      <el-table-column
        label="操作"
        align="center"
        class-name="small-padding fixed-width"
      >
        <template #default="scope">
          <el-button
            link
            type="primary"
            size="small"
            icon="el-icon-view"
            @click="handlePreview(scope.row)"
            v-hasPermi="['tool:gen:preview']"
            >预览</el-button
          >
          <el-button
            link
            type="primary"
            size="small"
            icon="el-icon-edit"
            @click="handleEditTable(scope.row)"
            v-hasPermi="['tool:gen:edit']"
            >编辑</el-button
          >
          <el-button
            link
            type="primary"
            size="small"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['tool:gen:remove']"
            >删除</el-button
          >
          <el-button
            link
            type="primary"
            size="small"
            icon="el-icon-refresh"
            @click="handleSynchDb(scope.row)"
            v-hasPermi="['tool:gen:edit']"
            >同步</el-button
          >
          <el-button
            link
            type="primary"
            size="small"
            icon="el-icon-download"
            @click="handleGenTable(scope.row)"
            v-hasPermi="['tool:gen:code']"
            >生成代码</el-button
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
      :title="preview.title"
      v-model="preview.open"
      size="min(1100px, 92vw)"
      append-to-body
      class="scrollbar bi-management-drawer"
    >
      <el-tabs v-model="preview.activeName">
        <el-tab-pane
          v-for="(value, key) in preview.data"
          :label="key.substring(key.lastIndexOf('/') + 1, key.indexOf('.vm'))"
          :name="key.substring(key.lastIndexOf('/') + 1, key.indexOf('.vm'))"
          :key="key"
        >
          <el-link
            :underline="false"
            icon="el-icon-document-copy"
            v-clipboard:copy="value"
            v-clipboard:success="clipboardSuccess"
            style="float: right"
            >复制</el-link
          >
          <pre><code class="hljs" v-html="highlightedCode(value, key)"></code></pre>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <import-table ref="import" @ok="handleQuery" />

    <create-table ref="create" @ok="handleQuery" />
  </BiTablePage>
</template>

<script>
import {
  listTable,
  previewTable,
  delTable,
  genCode,
  synchDb,
} from '#/legacy/api/tool/gen';
import importTable from './importTable.vue';
import createTable from './createTable.vue';

export default {
  name: 'Gen',
  components: { importTable, createTable },
  data() {
    return {
      // 遮罩层
      loading: true,
      // 唯一标识符
      uniqueId: '',
      // 选中数组
      ids: [],
      // 选中表数组
      tableNames: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 表数据
      tableList: [],
      // 日期范围
      dateRange: '',
      // 默认排序
      defaultSort: { prop: 'createTime', order: 'descending' },
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        tableName: undefined,
        tableComment: undefined,
      },
      // 预览参数
      preview: {
        open: false,
        title: '代码预览',
        data: {},
        activeName: 'domain.java',
      },
    };
  },
  created() {
    this.queryParams.orderByColumn = this.defaultSort.prop;
    this.queryParams.isAsc = this.defaultSort.order;
    this.getList();
  },
  activated() {
    const time = this.$route.query.t;
    if (time != null && time != this.uniqueId) {
      this.uniqueId = time;
      this.queryParams.pageNum = Number(this.$route.query.pageNum);
      this.getList();
    }
  },
  methods: {
    /** 查询表集合 */
    getList() {
      this.loading = true;
      listTable(this.addDateRange(this.queryParams, this.dateRange)).then(
        (response) => {
          this.tableList = response.rows;
          this.total = response.total;
          this.loading = false;
        },
      );
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 生成代码操作 */
    handleGenTable(row) {
      const tableNames = row.tableName || this.tableNames;
      if (tableNames == '') {
        this.$modal.msgError('请选择要生成的数据');
        return;
      }
      if (row.genType === '1') {
        genCode(row.tableName).then((response) => {
          this.$modal.msgSuccess('成功生成到自定义路径：' + row.genPath);
        });
      } else {
        const zipName = Array.isArray(tableNames)
          ? 'ruoyi.zip'
          : tableNames + '.zip';
        this.$download.zip(
          '/tool/gen/batchGenCode?tables=' + tableNames,
          zipName,
        );
      }
    },
    /** 同步数据库操作 */
    handleSynchDb(row) {
      const tableName = row.tableName;
      this.$modal
        .confirm('确认要强制同步"' + tableName + '"表结构吗？')
        .then(function () {
          return synchDb(tableName);
        })
        .then(() => {
          this.$modal.msgSuccess('同步成功');
        })
        .catch(() => {});
    },
    /** 打开导入表弹窗 */
    openImportTable() {
      this.$refs.import.show();
    },
    /** 打开创建表弹窗 */
    openCreateTable() {
      this.$refs.create.show();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.dateRange = [];
      this.resetForm('queryForm');
      this.queryParams.pageNum = 1;
      this.$refs.tables.sort(this.defaultSort.prop, this.defaultSort.order);
    },
    /** 预览按钮 */
    handlePreview(row) {
      previewTable(row.tableId).then((response) => {
        this.preview.data = response.data;
        this.preview.open = true;
        this.preview.activeName = 'domain.java';
      });
    },
    /** 高亮显示 */
    highlightedCode(code, key) {
      return String(code || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    },
    /** 复制代码成功 */
    clipboardSuccess() {
      this.$modal.msgSuccess('复制成功');
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.tableId);
      this.tableNames = selection.map((item) => item.tableName);
      this.single = selection.length != 1;
      this.multiple = !selection.length;
    },
    /** 排序触发事件 */
    handleSortChange(column, prop, order) {
      this.queryParams.orderByColumn = column.prop;
      this.queryParams.isAsc = column.order;
      this.getList();
    },
    /** 修改按钮操作 */
    handleEditTable(row) {
      const tableId = row.tableId || this.ids[0];
      const tableName = row.tableName || this.tableNames[0];
      const params = { pageNum: this.queryParams.pageNum };
      this.$tab.openPage(
        '修改[' + tableName + ']生成配置',
        '/tool/gen-edit/index/' + tableId,
        params,
      );
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const tableIds = row.tableId || this.ids;
      this.$modal
        .confirm('是否确认删除表编号为"' + tableIds + '"的数据项？')
        .then(function () {
          return delTable(tableIds);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
  },
};
</script>
