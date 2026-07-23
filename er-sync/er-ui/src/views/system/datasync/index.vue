<template>
  <div class="app-container">
    <!-- 查询表单 -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="88px">
      <el-form-item label="任务名称" prop="syncName">
        <el-input
          v-model="queryParams.syncName"
          placeholder="请输入任务名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="源数据源" prop="sourceDatasourceId">
        <el-select v-model="queryParams.sourceDatasourceId" placeholder="请选择源数据源" clearable>
          <el-option
            v-for="item in datasourceOptions"
            :key="item.datasourceId"
            :label="item.datasourceName"
            :value="item.datasourceId"
          />
        </el-select>
      </el-form-item>
      <template v-if="advanced">
        <el-form-item label="同步状态" prop="syncStatus">
          <el-select v-model="queryParams.syncStatus" placeholder="请选择同步状态" clearable>
            <el-option label="待同步" value="0" />
            <el-option label="同步中" value="1" />
            <el-option label="同步成功" value="2" />
            <el-option label="同步失败" value="3" />
            <el-option label="已停止" value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable>
            <el-option label="正常" value="0" />
            <el-option label="停用" value="1" />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        <el-button type="text" @click="advanced = !advanced">
          {{ advanced ? '收起' : '展开' }}
          <i :class="advanced ? 'el-icon-arrow-up' : 'el-icon-arrow-down'"></i>
        </el-button>
      </el-form-item>
    </el-form>

    <!-- 操作按钮栏 -->
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['system:datasync:add']"
        >新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['system:datasync:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['system:datasync:remove']"
        >删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="dataSyncList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="任务名称" align="center" prop="syncName" min-width="120" show-overflow-tooltip />
      <el-table-column label="源数据源" align="center" prop="sourceDatasourceName" min-width="100" show-overflow-tooltip />
      <el-table-column label="源表匹配" align="center" prop="sourceTablePattern" min-width="120" show-overflow-tooltip />
      <el-table-column label="目标数据源" align="center" prop="targetDatasourceName" min-width="100" show-overflow-tooltip />
      <el-table-column label="表已存在" align="center" prop="ifExistsAction" width="90">
        <template slot-scope="scope">
          <el-tag :type="scope.row.ifExistsAction === '0' ? 'info' : 'warning'" size="small">
            {{ scope.row.ifExistsAction === '0' ? '跳过' : '删除重建' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步状态" align="center" prop="syncStatus" width="90">
        <template slot-scope="scope">
          <el-tag
            :type="getSyncStatusType(scope.row.syncStatus)"
            size="small"
            :style="scope.row.syncStatus === '1' || scope.row.syncStatus === '2' || scope.row.syncStatus === '3' || scope.row.syncStatus === '4' ? 'cursor: pointer;' : ''"
            @click="scope.row.syncStatus !== '0' ? showProgress(scope.row.syncId) : null">
            {{ getSyncStatusLabel(scope.row.syncStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" prop="status" width="80">
        <template slot-scope="scope">
          <el-switch
            v-model="scope.row.status"
            active-value="0"
            inactive-value="1"
            @change="handleStatusChange(scope.row)"
          ></el-switch>
        </template>
      </el-table-column>
      <el-table-column label="最后同步时间" align="center" prop="lastSyncTime" width="160">
        <template slot-scope="scope">
          <span v-if="scope.row.lastSyncTime">{{ parseTime(scope.row.lastSyncTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="380">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-view"
            @click="handlePreview(scope.row)"
            v-hasPermi="['system:datasync:query']"
          >预览</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-time"
            @click="showProgress(scope.row.syncId)"
            v-hasPermi="['system:datasync:query']"
            :disabled="scope.row.syncStatus === '0'"
          >进度</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-refresh"
            @click="handleExecute(scope.row)"
            v-hasPermi="['system:datasync:execute']"
            :disabled="scope.row.syncStatus === '1'"
          >同步</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-tickets"
            @click="handleLog(scope.row)"
            v-hasPermi="['system:datasync:log']"
          >日志</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:datasync:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-document-copy"
            @click="handleCopy(scope.row)"
            v-hasPermi="['system:datasync:add']"
          >复制</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:datasync:remove']"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页组件 -->
    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 添加/修改对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="900px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="任务名称" prop="syncName">
              <el-input v-model="form.syncName" placeholder="请输入任务名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务编码" prop="syncCode">
              <el-input v-model="form.syncCode" placeholder="可选，用于标识任务" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">源数据源配置</el-divider>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="源数据源" prop="sourceDatasourceId">
              <el-select v-model="form.sourceDatasourceId" placeholder="请选择源数据源" style="width: 100%;" @change="handleSourceChange">
                <el-option
                  v-for="item in datasourceOptions"
                  :key="item.datasourceId"
                  :label="item.datasourceName + ' (' + item.datasourceType + ')'"
                  :value="item.datasourceId"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="源Schema模式" prop="sourceSchemaPattern">
          <el-input
            v-model="form.sourceSchemaPattern"
            type="textarea"
            :rows="2"
            placeholder="多个模式用逗号分隔，支持通配符%，如: db_%, schema_2024%, test_db（可选，留空表示所有Schema）">
          </el-input>
          
        </el-form-item>

        <el-form-item label="源表匹配模式" prop="sourceTablePattern">
          <el-input
            v-model="form.sourceTablePattern"
            type="textarea"
            :rows="2"
            placeholder="多个模式用逗号分隔，支持通配符%，如: user_%, order_2024%, sys_user">
          </el-input>
          
        </el-form-item>

        <el-divider content-position="left">目标数据源配置</el-divider>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="目标数据源" prop="targetDatasourceId">
              <el-select v-model="form.targetDatasourceId" placeholder="请选择目标数据源" style="width: 100%;">
                <el-option
                  v-for="item in datasourceOptions"
                  :key="item.datasourceId"
                  :label="item.datasourceName + ' (' + item.datasourceType + ')'"
                  :value="item.datasourceId"
                  :disabled="item.datasourceId === form.sourceDatasourceId"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">同步选项</el-divider>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="表已存在时" prop="ifExistsAction">
              <el-radio-group v-model="form.ifExistsAction">
                <el-radio label="0">跳过</el-radio>
                <el-radio label="1">删除重建</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="form.status">
                <el-radio label="0">正常</el-radio>
                <el-radio label="1">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 预览对话框 -->
    <el-dialog title="同步预览 - 匹配的表列表" :visible.sync="previewOpen" width="700px" append-to-body>
      <div v-loading="previewLoading">
        <el-alert
          v-if="previewTables.length > 0"
          :title="'共匹配到 ' + previewSchemaCount + ' 个库，' + previewTableCount + ' 张表'"
          type="success"
          :closable="false"
          show-icon
          style="margin-bottom: 15px;"
        />
        <el-alert
          v-else
          title="未匹配到任何表，请检查源数据源和匹配模式"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 15px;"
        />
        <el-table :data="previewTables" max-height="400" border size="small">
          <el-table-column type="index" label="#" width="50" align="center" />
          <el-table-column prop="schemaName" label="库名" width="180" show-overflow-tooltip />
          <el-table-column prop="tableName" label="表名" show-overflow-tooltip />
        </el-table>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button @click="previewOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 日志对话框 -->
    <el-dialog title="同步日志" :visible.sync="logOpen" width="1200px" append-to-body>
      <!-- 搜索条件 -->
      <el-form :model="logQueryParams" ref="logQueryForm" size="small" :inline="true">
        <el-form-item label="源表名称" prop="sourceTable">
          <el-input
            v-model="logQueryParams.sourceTable"
            placeholder="请输入源表名称"
            clearable
            style="width: 180px"
            @keyup.enter.native="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="目标表名称" prop="targetTable">
          <el-input
            v-model="logQueryParams.targetTable"
            placeholder="请输入目标表名称"
            clearable
            style="width: 180px"
            @keyup.enter.native="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="同步结果" prop="syncResult">
          <el-select v-model="logQueryParams.syncResult" placeholder="请选择" clearable style="width: 120px">
            <el-option label="成功" value="0" />
            <el-option label="失败" value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="创建时间">
          <el-date-picker
            v-model="logDateRange"
            style="width: 240px"
            value-format="yyyy-MM-dd"
            type="daterange"
            range-separator="-"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          ></el-date-picker>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" size="mini" @click="handleLogQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" size="mini" @click="resetLogQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 日志列表 -->
      <el-table :data="logList" max-height="400" border size="small" v-loading="logLoading">
        <el-table-column label="源表" align="center" prop="sourceTable" min-width="120" show-overflow-tooltip />
        <el-table-column label="目标表" align="center" prop="targetTable" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" align="center" prop="syncAction" width="80" />
        <el-table-column label="结果" align="center" prop="syncResult" width="70">
          <template slot-scope="scope">
            <el-tag :type="scope.row.syncResult === '0' ? 'success' : 'danger'" size="mini">
              {{ scope.row.syncResult === '0' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="影响行数" align="center" prop="rowsAffected" width="90" />
        <el-table-column label="耗时(ms)" align="center" prop="executeTime" width="90" />
        <el-table-column label="错误信息" align="center" prop="errorMessage" min-width="150" show-overflow-tooltip />
        <el-table-column label="时间" align="center" prop="createTime" width="160">
          <template slot-scope="scope">
            <span>{{ parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="80" fixed="right">
          <template slot-scope="scope">
            <el-button size="mini" type="text" icon="el-icon-view" @click="handleLogDetail(scope.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <pagination
        v-show="logTotal > 0"
        :total="logTotal"
        :page.sync="logQueryParams.pageNum"
        :limit.sync="logQueryParams.pageSize"
        @pagination="getLogList"
      />

      <div slot="footer" class="dialog-footer">
        <el-button @click="logOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 同步进度对话框 -->
    <el-dialog
      title="同步进度"
      :visible.sync="progressOpen"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="true"
      @close="handleProgressClose"
      append-to-body>
      <div v-loading="progressLoading">
        <el-alert
          :title="getProgressTitle()"
          :type="getProgressAlertType()"
          :closable="false"
          show-icon
          style="margin-bottom: 20px;">
        </el-alert>

        <div v-if="progressData.syncProgress" style="padding: 0 20px;">
          <div style="margin-bottom: 15px;">
            <span style="font-weight: bold;">进度：</span>
            <span>{{ progressInfo.current || 0 }} / {{ progressInfo.total || 0 }}</span>
          </div>
          <el-progress
            :percentage="progressInfo.percent || 0"
            :status="getProgressStatus()"
            :stroke-width="20"
            :show-text="false">
          </el-progress>
          <div v-if="progressData.syncStatus === '1'" style="margin-top: 15px; color: #909399; font-size: 13px;">
            {{ progressInfo.message || '准备开始同步...' }}
          </div>
        </div>

        <!-- 已同步的表列表 -->
        <div v-if="progressInfo.completedTables && progressInfo.completedTables.length > 0" style="margin-top: 20px; padding: 0 20px;">
          <el-divider content-position="left">已同步 ({{ progressInfo.completedTables.length }})</el-divider>
          <el-table
            :data="progressInfo.completedTables"
            max-height="200"
            border
            size="mini"
            style="width: 100%;">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="table" label="表名" show-overflow-tooltip>
              <template slot-scope="scope">
                {{ scope.row }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 待同步的表列表 -->
        <div v-if="progressInfo.pendingTables && progressInfo.pendingTables.length > 0" style="margin-top: 15px; padding: 0 20px;">
          <el-divider content-position="left">待同步 ({{ progressInfo.pendingTables.length }})</el-divider>
          <el-table
            :data="progressInfo.pendingTables"
            max-height="150"
            border
            size="mini"
            style="width: 100%;">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="table" label="表名" show-overflow-tooltip>
              <template slot-scope="scope">
                {{ scope.row }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="progressData.lastSyncResult && (progressData.syncStatus === '2' || progressData.syncStatus === '3' || progressData.syncStatus === '4')" style="margin-top: 20px; padding: 0 20px;">
          <el-divider content-position="left">同步结果</el-divider>
          <div style="background: #f5f7fa; padding: 10px; border-radius: 4px; font-size: 13px;">
            {{ formatSyncResult(progressData.lastSyncResult) }}
          </div>
        </div>

        <div v-if="progressData.lastSyncTime" style="margin-top: 15px; padding: 0 20px; font-size: 13px; color: #909399;">
          最后同步时间：{{ parseTime(progressData.lastSyncTime, '{y}-{m}-{d} {h}:{i}:{s}') }}
        </div>
      </div>

      <div slot="footer" class="dialog-footer">
        <el-button @click="handleProgressClose">关 闭</el-button>
        <el-button @click="handleStopSync" type="danger" v-if="progressData.syncStatus === '1'">停止同步</el-button>
        <el-button @click="handleViewLog" type="primary" v-if="progressData.syncStatus === '2' || progressData.syncStatus === '3' || progressData.syncStatus === '4'">查看日志</el-button>
      </div>
    </el-dialog>

    <!-- SQL详情抽屉 -->
    <el-drawer
      title="SQL执行详情"
      :visible.sync="logDetailOpen"
      direction="rtl"
      size="50%"
      :before-close="handleLogDetailClose">
      <div style="padding: 20px;">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="源表名称">{{ logDetail.sourceTable }}</el-descriptions-item>
          <el-descriptions-item label="目标表名称">{{ logDetail.targetTable }}</el-descriptions-item>
          <el-descriptions-item label="同步操作">{{ logDetail.syncAction }}</el-descriptions-item>
          <el-descriptions-item label="同步结果">
            <el-tag :type="logDetail.syncResult === '0' ? 'success' : 'danger'" size="small">
              {{ logDetail.syncResult === '0' ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="影响行数">{{ logDetail.rowsAffected }}</el-descriptions-item>
          <el-descriptions-item label="执行耗时">{{ logDetail.executeTime }} ms</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">
            {{ parseTime(logDetail.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}
          </el-descriptions-item>
          <el-descriptions-item label="错误信息" :span="2" v-if="logDetail.errorMessage">
            <el-alert :title="logDetail.errorMessage" type="error" :closable="false" />
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">执行SQL</el-divider>
        <div style="position: relative;">
          <el-button
            size="mini"
            type="primary"
            icon="el-icon-document-copy"
            style="position: absolute; top: 5px; right: 5px; z-index: 10;"
            @click="copySql">
            复制
          </el-button>
          <pre style="background: #f5f7fa; padding: 15px; border-radius: 4px; overflow-x: auto; margin-top: 10px;">{{ logDetail.executeSql || '无SQL记录' }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { listDataSync, getDataSync, delDataSync, addDataSync, updateDataSync, executeSync, previewSync, listSyncLog, getSyncProgress, stopSync } from "@/api/system/datasync";
import { getDatasourceOptions } from "@/api/system/datasource";

export default {
  name: "DataSync",
  data() {
    return {
      // 高级搜索展开
      advanced: false,
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
      // 数据同步任务表格数据
      dataSyncList: [],
      // 数据源选项
      datasourceOptions: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 预览弹出层
      previewOpen: false,
      previewLoading: false,
      previewTables: [],
      previewSchemaCount: 0,
      previewTableCount: 0,
      // 日志弹出层
      logOpen: false,
      logLoading: false,
      logList: [],
      logTotal: 0,
      logQueryParams: {
        pageNum: 1,
        pageSize: 10,
        syncId: null,
        sourceTable: null,
        targetTable: null,
        syncResult: null,
        beginTime: null,
        endTime: null
      },
      logDateRange: [],
      // 日志详情抽屉
      logDetailOpen: false,
      logDetail: {},
      // 自动刷新定时器
      refreshTimer: null,
      // 进度对话框
      progressOpen: false,
      progressLoading: false,
      progressData: {},
      progressTimer: null,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        syncName: null,
        sourceDatasourceId: null,
        syncStatus: null,
        status: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        syncName: [
          { required: true, message: "任务名称不能为空", trigger: "blur" }
        ],
        sourceDatasourceId: [
          { required: true, message: "请选择源数据源", trigger: "change" }
        ],
        sourceTablePattern: [
          { required: true, message: "源表匹配模式不能为空", trigger: "blur" }
        ],
        targetDatasourceId: [
          { required: true, message: "请选择目标数据源", trigger: "change" }
        ]
      }
    };
  },
  computed: {
    /** 解析进度信息 */
    progressInfo() {
      if (!this.progressData.syncProgress) {
        return { current: 0, total: 0, percent: 0, message: '' };
      }
      try {
        return JSON.parse(this.progressData.syncProgress);
      } catch (e) {
        return { current: 0, total: 0, percent: 0, message: '' };
      }
    }
  },
  created() {
    this.getList();
    this.getDatasourceOptions();
  },
  beforeDestroy() {
    // 清除定时器
    this.stopAutoRefresh();
    this.stopProgressPolling();
  },
  methods: {
    /** 查询数据同步任务列表 */
    getList() {
      this.loading = true;
      listDataSync(this.queryParams).then(response => {
        this.dataSyncList = response.rows;
        this.total = response.total;
        this.loading = false;

        // 检查是否有同步中的任务
        const hasSyncing = this.dataSyncList.some(item => item.syncStatus === '1');
        if (hasSyncing) {
          this.startAutoRefresh();
        } else {
          this.stopAutoRefresh();
        }
      });
    },
    /** 获取数据源选项 */
    getDatasourceOptions() {
      getDatasourceOptions().then(response => {
        this.datasourceOptions = response.data || [];
      });
    },
    /** 开始自动刷新 */
    startAutoRefresh() {
      if (this.refreshTimer) {
        return; // 已经在运行
      }
      this.refreshTimer = setInterval(() => {
        // 静默刷新，不显示loading
        const originalLoading = this.loading;
        listDataSync(this.queryParams).then(response => {
          this.dataSyncList = response.rows;
          this.total = response.total;
          // 检查是否还有同步中的任务
          const hasSyncing = this.dataSyncList.some(item => item.syncStatus === '1');
          if (!hasSyncing) {
            this.stopAutoRefresh();
          }
        }).finally(() => {
          this.loading = originalLoading;
        });
      }, 5000); // 每5秒刷新一次
    },
    /** 停止自动刷新 */
    stopAutoRefresh() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer);
        this.refreshTimer = null;
      }
    },
    /** 取消按钮 */
    cancel() {
      this.open = false;
      this.reset();
    },
    /** 表单重置 */
    reset() {
      this.form = {
        syncId: null,
        syncName: null,
        syncCode: null,
        sourceDatasourceId: null,
        sourceSchemaPattern: null,
        sourceTablePattern: null,
        targetDatasourceId: null,
        ifExistsAction: "0",
        status: "0",
        remark: null
      };
      this.resetForm("form");
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    /** 多选框选中数据 */
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.syncId);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加同步任务";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const syncId = row.syncId || this.ids;
      getDataSync(syncId).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改同步任务";
      });
    },
    /** 复制按钮操作 */
    handleCopy(row) {
      this.reset();
      const syncId = row.syncId;
      getDataSync(syncId).then(response => {
        this.form = response.data;
        this.form.syncId = null;
        this.form.syncCode = null;
        this.form.syncName = (this.form.syncName || '') + '_copy';
        this.open = true;
        this.title = "复制同步任务";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          // 检查源和目标数据源不能相同
          if (this.form.sourceDatasourceId === this.form.targetDatasourceId) {
            this.$modal.msgError("源数据源和目标数据源不能相同");
            return;
          }
          if (this.form.syncId != null) {
            updateDataSync(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addDataSync(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const syncIds = row.syncId || this.ids;
      this.$modal.confirm('是否确认删除同步任务编号为"' + syncIds + '"的数据项？').then(function() {
        return delDataSync(syncIds);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 状态修改 */
    handleStatusChange(row) {
      let text = row.status === "0" ? "启用" : "停用";
      this.$modal.confirm('确认要"' + text + '""' + row.syncName + '"任务吗？').then(function() {
        return updateDataSync({ syncId: row.syncId, status: row.status });
      }).then(() => {
        this.$modal.msgSuccess(text + "成功");
      }).catch(function() {
        row.status = row.status === "0" ? "1" : "0";
      });
    },
    /** 源数据源变更 */
    handleSourceChange(val) {
      // 清空目标数据源（如果与源相同）
      if (this.form.targetDatasourceId === val) {
        this.form.targetDatasourceId = null;
      }
    },
    /** 预览同步 */
    handlePreview(row) {
      this.previewOpen = true;
      this.previewLoading = true;
      this.previewTables = [];
      this.previewSchemaCount = 0;
      this.previewTableCount = 0;
      previewSync(row.syncId).then(response => {
        const result = response.data || {};
        this.previewTables = result.tables || [];
        this.previewSchemaCount = result.schemaCount || 0;
        this.previewTableCount = result.tableCount || 0;
        this.previewLoading = false;
      }).catch(() => {
        this.previewLoading = false;
      });
    },
    /** 执行同步 */
    handleExecute(row) {
      this.$modal.confirm('确认要执行同步任务"' + row.syncName + '"吗？').then(() => {
        return executeSync(row.syncId);
      }).then(response => {
        this.$modal.msgSuccess(response.data.message || "同步请求已提交");
        this.getList();
        // 显示进度对话框
        this.showProgress(row.syncId);
      }).catch(() => {});
    },
    /** 显示进度对话框 */
    showProgress(syncId) {
      this.progressOpen = true;
      this.progressLoading = true;
      this.progressData = {};
      // 立即获取一次进度
      this.fetchProgress(syncId);
      // 开始轮询
      this.startProgressPolling(syncId);
    },
    /** 获取进度 */
    fetchProgress(syncId) {
      getSyncProgress(syncId).then(response => {
        this.progressData = response.data || {};
        this.progressLoading = false;
        // 如果同步完成，停止轮询
        if (this.progressData.syncStatus !== '1') {
          this.stopProgressPolling();
        }
      }).catch(() => {
        this.progressLoading = false;
      });
    },
    /** 开始进度轮询 */
    startProgressPolling(syncId) {
      this.stopProgressPolling(); // 先停止已存在的
      this.progressTimer = setInterval(() => {
        this.fetchProgress(syncId);
      }, 2000); // 每2秒轮询一次
    },
    /** 停止进度轮询 */
    stopProgressPolling() {
      if (this.progressTimer) {
        clearInterval(this.progressTimer);
        this.progressTimer = null;
      }
    },
    /** 获取进度标题 */
    getProgressTitle() {
      const statusMap = {
        '0': '准备同步',
        '1': '正在同步中...',
        '2': '同步成功',
        '3': '同步失败',
        '4': '已停止'
      };
      return statusMap[this.progressData.syncStatus] || '同步状态未知';
    },
    /** 获取进度Alert类型 */
    getProgressAlertType() {
      const typeMap = {
        '0': 'info',
        '1': 'warning',
        '2': 'success',
        '3': 'error',
        '4': 'warning'
      };
      return typeMap[this.progressData.syncStatus] || 'info';
    },
    /** 获取进度条状态 */
    getProgressStatus() {
      if (this.progressData.syncStatus === '2') {
        return 'success';
      } else if (this.progressData.syncStatus === '3') {
        return 'exception';
      } else if (this.progressData.syncStatus === '4') {
        return 'warning';
      }
      return null;
    },
    /** 格式化同步结果 */
    formatSyncResult(result) {
      if (!result) {
        return '无结果';
      }
      try {
        const obj = JSON.parse(result);
        return obj.message || obj.error || JSON.stringify(obj);
      } catch (e) {
        return result;
      }
    },
    /** 从进度对话框查看日志 */
    handleProgressClose() {
      this.stopProgressPolling();
      this.progressOpen = false;
    },
    handleViewLog() {
      if (!this.progressData.syncId) {
        return;
      }
      // 找到对应的行数据
      const row = this.dataSyncList.find(item => item.syncId === this.progressData.syncId);
      if (row) {
        this.handleLog(row);
      }
    },
    /** 停止同步 */
    handleStopSync() {
      if (!this.progressData.syncId) {
        return;
      }
      this.$modal.confirm('确认要停止正在执行的同步任务吗？').then(() => {
        return stopSync(this.progressData.syncId);
      }).then(response => {
        this.$modal.msgSuccess(response.data.message || '同步已停止');
        // 立即刷新进度
        this.fetchProgress(this.progressData.syncId);
        // 刷新列表
        this.getList();
      }).catch(() => {});
    },
    /** 查看日志 */
    handleLog(row) {
      // 重置查询参数
      this.logQueryParams = {
        pageNum: 1,
        pageSize: 10,
        syncId: row.syncId,
        sourceTable: null,
        targetTable: null,
        syncResult: null,
        beginTime: null,
        endTime: null
      };
      this.logDateRange = [];
      this.logOpen = true;
      this.getLogList();
    },
    /** 查询日志列表 */
    getLogList() {
      this.logLoading = true;
      this.logList = [];
      // 处理时间范围
      if (this.logDateRange && this.logDateRange.length === 2) {
        this.logQueryParams.beginTime = this.logDateRange[0];
        this.logQueryParams.endTime = this.logDateRange[1];
      } else {
        this.logQueryParams.beginTime = null;
        this.logQueryParams.endTime = null;
      }
      listSyncLog(this.logQueryParams).then(response => {
        this.logList = response.rows || [];
        this.logTotal = response.total || 0;
        this.logLoading = false;
      }).catch(() => {
        this.logLoading = false;
      });
    },
    /** 搜索日志 */
    handleLogQuery() {
      this.logQueryParams.pageNum = 1;
      this.getLogList();
    },
    /** 重置日志查询 */
    resetLogQuery() {
      this.logDateRange = [];
      this.logQueryParams.sourceTable = null;
      this.logQueryParams.targetTable = null;
      this.logQueryParams.syncResult = null;
      this.logQueryParams.beginTime = null;
      this.logQueryParams.endTime = null;
      this.handleLogQuery();
    },
    /** 查看日志详情 */
    handleLogDetail(row) {
      this.logDetail = { ...row };
      this.logDetailOpen = true;
    },
    /** 关闭详情抽屉 */
    handleLogDetailClose() {
      this.logDetailOpen = false;
      this.logDetail = {};
    },
    /** 复制SQL */
    copySql() {
      const sql = this.logDetail.executeSql || '';
      if (!sql) {
        this.$modal.msgWarning('无SQL内容可复制');
        return;
      }
      // 创建临时输入框
      const textarea = document.createElement('textarea');
      textarea.value = sql;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        this.$modal.msgSuccess('SQL已复制到剪贴板');
      } catch (err) {
        this.$modal.msgError('复制失败，请手动复制');
      }
      document.body.removeChild(textarea);
    },
    /** 获取同步状态类型 */
    getSyncStatusType(status) {
      const map = { '0': 'info', '1': 'warning', '2': 'success', '3': 'danger', '4': 'warning' };
      return map[status] || 'info';
    },
    /** 获取同步状态标签 */
    getSyncStatusLabel(status) {
      const map = { '0': '待同步', '1': '同步中', '2': '同步成功', '3': '同步失败', '4': '已停止' };
      return map[status] || '未知';
    }
  }
};
</script>

<style scoped>
/* 缩小 textarea placeholder 字体大小 */
::v-deep .el-textarea__inner::placeholder {
  font-size: 10px;
}
/* 隐藏进度条右侧状态图标（show-text=false 时 Element UI 仍会渲染图标容器） */
::v-deep .el-progress .el-progress__text {
  display: none;
}
</style>
