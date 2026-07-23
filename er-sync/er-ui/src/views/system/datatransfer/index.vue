<template>
  <div class="app-container">
    <!-- 查询表单 -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="88px">
      <el-form-item label="任务名称" prop="transferName">
        <el-input
          v-model="queryParams.transferName"
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
      <el-form-item label="同步模式" prop="syncMode">
        <el-select v-model="queryParams.syncMode" placeholder="请选择同步模式" clearable>
          <el-option label="初始化同步" value="0" />
          <el-option label="增量同步" value="1" />
        </el-select>
      </el-form-item>
      <template v-if="advanced">
        <el-form-item label="同步状态" prop="syncStatus">
          <el-select v-model="queryParams.syncStatus" placeholder="请选择同步状态" clearable>
            <el-option label="待同步" value="0" />
            <el-option label="同步中" value="1" />
            <el-option label="同步成功" value="2" />
            <el-option label="同步失败" value="3" />
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
          v-hasPermi="['system:datatransfer:add']"
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
          v-hasPermi="['system:datatransfer:edit']"
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
          v-hasPermi="['system:datatransfer:remove']"
        >删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="dataTransferList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="任务名称" align="center" prop="transferName" min-width="120" show-overflow-tooltip />
      <el-table-column label="源数据源" align="center" prop="sourceDatasourceName" min-width="100" show-overflow-tooltip />
      <el-table-column label="源表" align="center" prop="sourceTable" min-width="120" show-overflow-tooltip />
      <el-table-column label="目标数据源" align="center" prop="targetDatasourceName" min-width="100" show-overflow-tooltip />
      <el-table-column label="同步模式" align="center" prop="syncMode" width="100">
        <template slot-scope="scope">
          <el-tag :type="scope.row.syncMode === '0' ? 'info' : 'primary'" size="small">
            {{ scope.row.syncMode === '0' ? '初始化' : '增量' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步操作" align="center" width="120">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.syncInsert === '1'" type="success" size="mini" style="margin-right:2px;">I</el-tag>
          <el-tag v-if="scope.row.syncUpdate === '1'" type="warning" size="mini" style="margin-right:2px;">U</el-tag>
          <el-tag v-if="scope.row.syncDelete === '1'" type="danger" size="mini">D</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步状态" align="center" prop="syncStatus" width="90">
        <template slot-scope="scope">
          <el-tag
            :type="getSyncStatusType(scope.row.syncStatus)"
            size="small"
            :style="scope.row.syncStatus === '1' || scope.row.syncStatus === '2' || scope.row.syncStatus === '3' || scope.row.syncStatus === '4' ? 'cursor: pointer;' : ''"
            @click="scope.row.syncStatus !== '0' ? showSyncProgress(scope.row.transferId) : null">
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
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="490">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-view"
            @click="handlePreview(scope.row)"
            v-hasPermi="['system:datatransfer:query']"
          >预览</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-refresh"
            @click="handleExecute(scope.row)"
            v-hasPermi="['system:datatransfer:execute']"
            :disabled="scope.row.syncStatus === '1'"
          >同步</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-tickets"
            @click="handleLog(scope.row)"
            v-hasPermi="['system:datatransfer:log']"
          >日志</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-data-line"
            @click="showSyncProgress(scope.row.transferId)"
            v-hasPermi="['system:datatransfer:query']"
            v-if="scope.row.syncStatus === '1' || scope.row.syncStatus === '2' || scope.row.syncStatus === '3' || scope.row.syncStatus === '4'"
          >进度</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-time"
            @click="handleProgress(scope.row)"
            v-hasPermi="['system:datatransfer:query']"
          >时间戳</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-refresh"
            @click="handleRefreshTransferProgress(scope.row)"
            v-hasPermi="['system:datatransfer:execute']"
            :disabled="scope.row.syncStatus === '1'"
          >刷新TS</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:datatransfer:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-document-copy"
            @click="handleCopy(scope.row)"
            v-hasPermi="['system:datatransfer:add']"
          >复制</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:datatransfer:remove']"
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
    <el-dialog :title="title" :visible.sync="open" width="950px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="任务名称" prop="transferName">
              <el-input v-model="form.transferName" placeholder="请输入任务名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务编码" prop="transferCode">
              <el-input v-model="form.transferCode" placeholder="可选，用于标识任务" />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-section">源数据配置</div>

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

        <el-form-item label="源表名" prop="sourceTable">
          <el-input
            v-model="form.sourceTable"
            type="textarea"
            :rows="2"
            placeholder="多个模式用逗号分隔，支持通配符%，如: user_%, order_2024%, sys_user">
          </el-input>
        </el-form-item>

        <div class="form-section">目标数据配置</div>

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

        <div class="form-section">同步配置</div>

        <!-- 第一行：同步模式 -->
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="同步模式" prop="syncMode">
              <el-radio-group v-model="form.syncMode" @change="handleSyncModeChange">
                <el-radio label="0">初始化同步</el-radio>
                <el-radio label="1">增量同步</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 第二行：时间戳字段（独立一行，始终显示） -->
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="时间戳字段" prop="timestampField">
              <el-input v-model="form.timestampField" placeholder="默认: time_stamp">
                <template slot="append">
                  <el-tooltip content="同步时依据此字段判断时间窗口" placement="top">
                    <i class="el-icon-question"></i>
                  </el-tooltip>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 第三行：同步操作（仅增量）+ 批次大小（两种模式都显示） -->
        <el-row :gutter="20">
          <el-col :span="12" v-if="form.syncMode === '1'">
            <el-form-item label="同步操作" prop="syncOperations">
              <el-checkbox v-model="syncOperations.insert">INSERT</el-checkbox>
              <el-checkbox v-model="syncOperations.update">UPDATE</el-checkbox>
              <el-checkbox v-model="syncOperations.delete">DELETE</el-checkbox>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="批次大小" prop="batchSize">
              <el-input-number v-model="form.batchSize" :min="10" :max="10000" :step="10" />
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 第四行：删除保护窗口（增量模式显示） -->
        <el-row :gutter="20" v-if="form.syncMode === '1'">
          <el-col :span="12">
            <el-form-item label="删除窗口(年)" prop="deleteWindowYears">
              <el-input-number v-model="form.deleteWindowYears" :min="1" :max="10" :step="1" />
              <div style="color:#909399;font-size:12px;margin-top:4px;">
                只同步最近 N 年内的删除，默认 2 年
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-section">其他配置</div>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="Cron表达式" prop="cronExpression">
              <el-input v-model="form.cronExpression" placeholder="定时任务">
                <template slot="append">
                  <el-tooltip content="" placement="top">
                    <i class="el-icon-question"></i>
                  </el-tooltip>
                </template>
              </el-input>
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
        <el-form-item label="同步模式" prop="syncMode">
          <el-select v-model="logQueryParams.syncMode" placeholder="请选择" clearable style="width: 120px">
            <el-option label="初始化" value="0" />
            <el-option label="增量" value="1" />
          </el-select>
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
        <el-table-column label="模式" align="center" prop="syncMode" width="70">
          <template slot-scope="scope">
            <el-tag :type="scope.row.syncMode === '0' ? 'info' : 'primary'" size="mini">
              {{ scope.row.syncMode === '0' ? '初始化' : '增量' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" prop="syncAction" width="80" />
        <el-table-column label="总数" align="center" prop="totalCount" width="70" />
        <el-table-column label="成功" align="center" prop="successCount" width="70">
          <template slot-scope="scope">
            <span style="color: #67C23A;">{{ scope.row.successCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败" align="center" prop="failCount" width="70">
          <template slot-scope="scope">
            <span :style="{ color: scope.row.failCount > 0 ? '#F56C6C' : '' }">{{ scope.row.failCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" align="center" prop="syncResult" width="70">
          <template slot-scope="scope">
            <el-tag :type="scope.row.syncResult === '0' ? 'success' : 'danger'" size="mini">
              {{ scope.row.syncResult === '0' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时(ms)" align="center" prop="executeTime" width="90" />
        <el-table-column label="错误信息" align="center" prop="errorMessage" min-width="150" show-overflow-tooltip />
        <el-table-column label="时间" align="center" prop="createTime" width="160">
          <template slot-scope="scope">
            <span>{{ parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
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

    <!-- 表级时间戳对话框 -->
    <el-dialog title="表级同步时间戳" :visible.sync="progressOpen" width="820px" append-to-body>
      <el-table :data="progressList" max-height="500" border size="small" v-loading="progressLoading">
        <el-table-column label="源表" align="center" prop="sourceTable" min-width="200" show-overflow-tooltip />
        <el-table-column label="最后时间戳(ms)" align="center" prop="lastSyncValue" width="140" />
        <el-table-column label="最后时间" align="center" min-width="180">
          <template slot-scope="scope">
            <span v-if="scope.row.lastSyncValue">{{ parseTime(Number(scope.row.lastSyncValue), '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" align="center" min-width="160">
          <template slot-scope="scope">
            <span v-if="scope.row.updateTime">{{ parseTime(scope.row.updateTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div slot="footer" class="dialog-footer">
        <el-button
          type="primary"
          icon="el-icon-refresh"
          :loading="progressRefreshLoading"
          @click="handleRefreshTransferProgress({ transferId: progressTransferId })"
          v-hasPermi="['system:datatransfer:execute']"
        >刷新最大时间戳</el-button>
        <el-button @click="progressOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 同步进度对话框 -->
    <el-dialog
      title="同步进度"
      :visible.sync="syncProgressOpen"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="true"
      @close="handleSyncProgressClose"
      append-to-body>
      <div v-loading="syncProgressLoading">
        <el-alert
          :title="getSyncProgressTitle()"
          :type="getSyncProgressAlertType()"
          :closable="false"
          show-icon
          style="margin-bottom: 20px;">
        </el-alert>

        <div v-if="syncProgressData.syncProgress" style="padding: 0 20px;">
          <div style="margin-bottom: 15px;">
            <span style="font-weight: bold;">表进度：</span>
            <span>{{ syncProgressInfo.current || 0 }}{{ syncProgressInfo.total > 0 ? ' / ' + syncProgressInfo.total : '' }}</span>
          </div>
          <el-progress
            :percentage="syncProgressInfo.percent || 0"
            :status="getSyncProgressStatus()"
            :stroke-width="20"
            :show-text="false">
          </el-progress>
          <div style="margin-top: 15px; color: #909399; font-size: 13px;">
            {{ syncProgressInfo.message || '准备开始同步...' }}
          </div>
        </div>

        <!-- 表列表显示 -->
        <div v-if="syncProgressInfo.pendingTables || syncProgressInfo.completedTables" style="margin-top: 20px; padding: 0 20px;">
          <el-tabs type="border-card" style="box-shadow: none;">
            <el-tab-pane v-if="syncProgressInfo.completedTables && syncProgressInfo.completedTables.length > 0">
              <span slot="label"><i class="el-icon-check"></i> 已同步 ({{ syncProgressInfo.completedTables.length }})</span>
              <el-table
                :data="syncProgressInfo.completedTables"
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
            </el-tab-pane>
            <el-tab-pane v-if="syncProgressInfo.pendingTables && syncProgressInfo.pendingTables.length > 0">
              <span slot="label"><i class="el-icon-time"></i> 待同步 ({{ syncProgressInfo.pendingTables.length }})</span>
              <el-table
                :data="syncProgressInfo.pendingTables"
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
            </el-tab-pane>
          </el-tabs>
        </div>

        <div v-if="syncProgressData.lastSyncResult && (syncProgressData.syncStatus === '2' || syncProgressData.syncStatus === '3' || syncProgressData.syncStatus === '4')" style="margin-top: 20px; padding: 0 20px;">
          <div class="form-section">同步结果</div>
          <div style="background: #f5f7fa; padding: 10px; border-radius: 4px; font-size: 13px;">
            {{ formatSyncResult(syncProgressData.lastSyncResult) }}
          </div>
        </div>

        <div v-if="syncProgressData.lastSyncTime" style="margin-top: 15px; padding: 0 20px; font-size: 13px; color: #909399;">
          最后同步时间：{{ parseTime(syncProgressData.lastSyncTime, '{y}-{m}-{d} {h}:{i}:{s}') }}
        </div>
      </div>

      <div slot="footer" class="dialog-footer">
        <el-button @click="handleRefreshSyncProgress" icon="el-icon-refresh" :loading="syncProgressLoading">刷新进度</el-button>
        <el-button @click="handleStopSync" type="danger" v-if="syncProgressData.syncStatus === '1'" :loading="stopping">停止同步</el-button>
        <el-button @click="handleViewLogFromProgress" type="primary" v-if="syncProgressData.syncStatus === '2' || syncProgressData.syncStatus === '3' || syncProgressData.syncStatus === '4'">查看日志</el-button>
        <el-button @click="handleSyncProgressClose">关 闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listDataTransfer, getDataTransfer, delDataTransfer, addDataTransfer, updateDataTransfer, executeTransfer, previewTransfer, listTransferLog, getTransferProgress, refreshTransferProgress, getTransferSyncProgress, stopTransfer } from "@/api/system/datatransfer";
import { listDatasource } from "@/api/system/datasource";

export default {
  name: "DataTransfer",
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
      dataTransferList: [],
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
        transferId: null,
        sourceTable: null,
        targetTable: null,
        syncMode: null,
        syncResult: null,
        beginTime: null,
        endTime: null
      },
      logDateRange: [],
      // 时间戳弹出层
      progressOpen: false,
      progressLoading: false,
      progressRefreshLoading: false,
      progressTransferId: null,
      progressList: [],
      // 同步进度对话框
      syncProgressOpen: false,
      syncProgressLoading: false,
      syncProgressData: {},
      syncProgressTimer: null,
      stopping: false, // 停止中状态
      // 自动刷新定时器
      refreshTimer: null,
      // 同步操作复选框
      syncOperations: {
        insert: true,
        update: true,
        delete: false
      },
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        transferName: null,
        sourceDatasourceId: null,
        syncMode: null,
        syncStatus: null,
        status: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        transferName: [
          { required: true, message: "任务名称不能为空", trigger: "blur" }
        ],
        sourceDatasourceId: [
          { required: true, message: "请选择源数据源", trigger: "change" }
        ],
        sourceTable: [
          { required: true, message: "源表名不能为空", trigger: "blur" }
        ],
        targetDatasourceId: [
          { required: true, message: "请选择目标数据源", trigger: "change" }
        ]
      }
    };
  },
  computed: {
    /** 解析同步进度信息 */
    syncProgressInfo() {
      if (!this.syncProgressData.syncProgress) {
        return { current: 0, total: 0, percent: 0, message: '', pendingTables: [], completedTables: [] };
      }
      try {
        const parsed = JSON.parse(this.syncProgressData.syncProgress);
        return {
          current: parsed.current || 0,
          total: (parsed.total > 0) ? parsed.total : 0,
          percent: parsed.percent || 0,
          message: parsed.message || '',
          pendingTables: parsed.pendingTables || [],
          completedTables: parsed.completedTables || []
        };
      } catch (e) {
        return { current: 0, total: 0, percent: 0, message: '', pendingTables: [], completedTables: [] };
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
    this.stopSyncProgressPolling();
  },
  methods: {
    /** 查询数据同步任务列表 */
    getList() {
      this.loading = true;
      listDataTransfer(this.queryParams).then(response => {
        this.dataTransferList = response.rows;
        this.total = response.total;
        this.loading = false;
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
        listDataTransfer(this.queryParams).then(response => {
          this.dataTransferList = response.rows;
          this.total = response.total;
          // 检查是否还有同步中的任务
          const hasSyncing = this.dataTransferList.some(item => item.syncStatus === '1');
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
    /** 获取数据源选项 */
    getDatasourceOptions() {
      listDatasource({ status: '0' }).then(response => {
        this.datasourceOptions = response.rows;
      });
    },
    /** 取消按钮 */
    cancel() {
      this.open = false;
      this.reset();
    },
    /** 表单重置 */
    reset() {
      this.form = {
        transferId: null,
        transferName: null,
        transferCode: null,
        sourceDatasourceId: null,
        sourceSchemaPattern: null,
        sourceTable: null,
        targetDatasourceId: null,
        syncMode: "0",
        timestampField: "time_stamp",
        syncInsert: "1",
        syncUpdate: "1",
        syncDelete: "0",
        deleteWindowYears: 2,
        batchSize: 1000,
        cronExpression: null,
        status: "0",
        remark: null
      };
      this.syncOperations = {
        insert: true,
        update: true,
        delete: false
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
      this.ids = selection.map(item => item.transferId);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "新增同步任务";
      this.form.deleteWindowYears = 2;
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const transferId = row.transferId || this.ids;
      getDataTransfer(transferId).then(response => {
        this.form = response.data;
        // 设置同步操作复选框
        this.syncOperations.insert = this.form.syncInsert === '1';
        this.syncOperations.update = this.form.syncUpdate === '1';
        this.syncOperations.delete = this.form.syncDelete === '1';
        this.form.deleteWindowYears = this.form.deleteWindowYears != null ? this.form.deleteWindowYears : 2;
        this.open = true;
        this.title = "修改同步任务";
      });
    },
    /** 复制按钮操作 */
    handleCopy(row) {
      this.reset();
      const transferId = row.transferId;
      getDataTransfer(transferId).then(response => {
        this.form = response.data;
        this.form.transferId = null;
        this.form.transferCode = null;
        this.form.transferName = (this.form.transferName || '') + '_copy';
        // 设置同步操作复选框
        this.syncOperations.insert = this.form.syncInsert === '1';
        this.syncOperations.update = this.form.syncUpdate === '1';
        this.syncOperations.delete = this.form.syncDelete === '1';
        this.form.deleteWindowYears = this.form.deleteWindowYears != null ? this.form.deleteWindowYears : 2;
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
        // 设置同步操作
        this.form.syncInsert = this.syncOperations.insert ? '1' : '0';
        this.form.syncUpdate = this.syncOperations.update ? '1' : '0';
        this.form.syncDelete = this.syncOperations.delete ? '1' : '0';
        // deleteWindowYears 已绑定在 form 上，直接提交

          if (this.form.transferId != null) {
            updateDataTransfer(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addDataTransfer(this.form).then(response => {
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
      const transferIds = row.transferId || this.ids;
      this.$modal.confirm('是否确认删除同步任务编号为"' + transferIds + '"的数据项？').then(function() {
        return delDataTransfer(transferIds);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 状态修改 */
    handleStatusChange(row) {
      let text = row.status === "0" ? "启用" : "停用";
      this.$modal.confirm('确认要"' + text + '""' + row.transferName + '"任务吗？').then(function() {
        return updateDataTransfer({ transferId: row.transferId, status: row.status });
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
    /** 同步模式变更 */
    handleSyncModeChange(val) {
      if (val === '0') {
        // 初始化同步：禁用同步操作选项，全量同步
        this.syncOperations.insert = true;
        this.syncOperations.update = true;
        this.syncOperations.delete = false;
        if (!this.form.batchSize) {
          this.form.batchSize = 1000;
        }
      } else {
        // 增量同步：默认只开INSERT/UPDATE，可勾选DELETE
        this.syncOperations.insert = true;
        this.syncOperations.update = true;
        // 批次大小对增量不生效，但保留数值方便切回初始化
      }
    },
    /** 预览同步 */
    handlePreview(row) {
      this.previewOpen = true;
      this.previewLoading = true;
      this.previewTables = [];
      this.previewSchemaCount = 0;
      this.previewTableCount = 0;
      previewTransfer(row.transferId).then(response => {
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
      this.$modal.confirm('确认要执行同步任务"' + row.transferName + '"吗？').then(() => {
        return executeTransfer(row.transferId);
      }).then(response => {
        this.$modal.msgSuccess("同步任务已启动，请查看进度对话框");
        this.getList();
        // 显示进度对话框
        this.showSyncProgress(row.transferId);
      }).catch(() => {});
    },
    /** 查看日志 */
    handleLog(row) {
      // 重置查询参数
      this.logQueryParams = {
        pageNum: 1,
        pageSize: 10,
        transferId: row.transferId,
        sourceTable: null,
        targetTable: null,
        syncMode: null,
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
      listTransferLog(this.logQueryParams).then(response => {
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
      this.logQueryParams.syncMode = null;
      this.logQueryParams.syncResult = null;
      this.logQueryParams.beginTime = null;
      this.logQueryParams.endTime = null;
      this.handleLogQuery();
    },
    /** 查看表级时间戳 */
    handleProgress(row) {
      this.progressOpen = true;
      this.progressLoading = true;
      this.progressTransferId = row.transferId;
      this.progressList = [];
      getTransferProgress(row.transferId).then(response => {
        this.progressList = response.data || [];
        this.progressLoading = false;
      }).catch(() => {
        this.progressLoading = false;
      });
    },
    /** 从目标表刷新表级最大时间戳 */
    handleRefreshTransferProgress(row) {
      const transferId = row && row.transferId;
      if (!transferId) {
        return;
      }
      this.$modal.confirm('确认从目标表MAX时间戳刷新当前任务的表级增量进度吗？').then(() => {
        this.progressRefreshLoading = true;
        return refreshTransferProgress(transferId);
      }).then(response => {
        const data = response.data || {};
        this.$modal.msgSuccess(data.message || response.msg || '刷新成功');
        this.progressRefreshLoading = false;
        this.getList();
        if (this.progressOpen) {
          this.progressLoading = true;
          this.progressTransferId = transferId;
          return getTransferProgress(transferId).then(progressResponse => {
            this.progressList = progressResponse.data || [];
            this.progressLoading = false;
          }).catch(() => {
            this.progressLoading = false;
          });
        }
      }).catch(() => {
        this.progressRefreshLoading = false;
      });
    },
    /** 显示同步进度对话框 */
    showSyncProgress(transferId) {
      this.syncProgressOpen = true;
      this.syncProgressLoading = true;
      this.syncProgressData = {};
      // 立即获取一次进度
      this.fetchSyncProgress(transferId);
      // 启动轮询（每1秒刷新一次）
      this.startSyncProgressPolling(transferId);
    },
    /** 获取同步进度 */
    fetchSyncProgress(transferId) {
      getTransferSyncProgress(transferId).then(response => {
        this.syncProgressData = response.data || {};
        this.syncProgressLoading = false;
        // 如果同步完成，停止轮询
        if (this.syncProgressData.syncStatus !== '1') {
          this.stopSyncProgressPolling();
        }
      }).catch(() => {
        this.syncProgressLoading = false;
      });
    },
    /** 开始同步进度轮询 */
    startSyncProgressPolling(transferId) {
      this.stopSyncProgressPolling(); // 先停止已存在的
      this.syncProgressTimer = setInterval(() => {
        this.fetchSyncProgress(transferId);
      }, 1000); // 每1秒轮询一次
    },
    /** 停止同步进度轮询 */
    stopSyncProgressPolling() {
      if (this.syncProgressTimer) {
        clearInterval(this.syncProgressTimer);
        this.syncProgressTimer = null;
      }
    },
    /** 获取同步进度标题 */
    getSyncProgressTitle() {
      const statusMap = {
        '0': '准备同步',
        '1': '正在同步中...',
        '2': '同步成功',
        '3': '同步失败',
        '4': '已停止'
      };
      return statusMap[this.syncProgressData.syncStatus] || '同步状态未知';
    },
    /** 获取同步进度Alert类型 */
    getSyncProgressAlertType() {
      const typeMap = {
        '0': 'info',
        '1': 'warning',
        '2': 'success',
        '3': 'error',
        '4': 'warning'
      };
      return typeMap[this.syncProgressData.syncStatus] || 'info';
    },
    /** 获取同步进度条状态 */
    getSyncProgressStatus() {
      if (this.syncProgressData.syncStatus === '2') {
        return 'success';
      } else if (this.syncProgressData.syncStatus === '3') {
        return 'exception';
      } else if (this.syncProgressData.syncStatus === '4') {
        return 'warning';
      }
      return null;
    },
    /** 手动刷新同步进度 */
    handleRefreshSyncProgress() {
      if (this.syncProgressData.transferId) {
        this.syncProgressLoading = true;
        this.fetchSyncProgress(this.syncProgressData.transferId);
      }
    },
    /** 停止同步 */
    handleStopSync() {
      this.$modal.confirm('确认要停止当前同步任务吗？').then(() => {
        this.stopping = true;
        return stopTransfer(this.syncProgressData.transferId);
      }).then(() => {
        this.$modal.msgSuccess("任务已停止");
        this.stopping = false;
        // 自动刷新一次进度和列表
        this.handleRefreshSyncProgress();
        this.getList();
      }).catch(() => {
        this.stopping = false;
      });
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
    /** 关闭同步进度对话框 */
    handleSyncProgressClose() {
      this.stopSyncProgressPolling();
      this.syncProgressOpen = false;
      // 刷新列表以更新状态
      this.getList();
    },
    /** 从同步进度对话框查看日志 */
    handleViewLogFromProgress() {
      if (!this.syncProgressData.transferId) {
        return;
      }
      // 找到对应的行数据
      const row = this.dataTransferList.find(item => item.transferId === this.syncProgressData.transferId);
      if (row) {
        this.handleLog(row);
      }
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
/* 隐藏进度条右侧百分比文字和状态图标 */
::v-deep .el-progress .el-progress__text {
  display: none !important;
}
/* 表单分组标题：替代 el-divider，避免生产环境下伪元素渲染异常 */
.form-section {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  line-height: 1;
  padding: 8px 0 8px 10px;
  margin: 18px 0 14px 0;
  border-left: 3px solid #409EFF;
  background-color: #f5f7fa;
}
</style>
