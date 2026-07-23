<template>
  <div class="app-container">
    <!-- 查询表单 -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="88px">
      <el-form-item label="任务名称" prop="combineName">
        <el-input
          v-model="queryParams.combineName"
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
          v-hasPermi="['system:combinesync:add']"
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
          v-hasPermi="['system:combinesync:edit']"
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
          v-hasPermi="['system:combinesync:remove']"
        >删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="combineSyncList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="任务名称" align="center" prop="combineName" min-width="120" show-overflow-tooltip />
      <el-table-column label="源数据源" align="center" prop="sourceDatasourceName" min-width="100" show-overflow-tooltip />
      <el-table-column label="源Schema模式" align="center" prop="sourceSchemaPattern" min-width="120" show-overflow-tooltip />
      <el-table-column label="源表名" align="center" prop="sourceTable" min-width="100" show-overflow-tooltip />
      <el-table-column label="目标数据源" align="center" prop="targetDatasourceName" min-width="100" show-overflow-tooltip />
      <el-table-column label="目标库名" align="center" prop="targetTable" min-width="100" show-overflow-tooltip />
      <el-table-column label="来源列" align="center" prop="sourceColumn" width="100" show-overflow-tooltip />
      <el-table-column label="同步模式" align="center" prop="syncMode" width="90">
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
            :style="scope.row.syncStatus !== '0' ? 'cursor: pointer;' : ''"
            @click="scope.row.syncStatus !== '0' ? showSyncProgress(scope.row.combineId) : null">
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
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="430">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-view"
            @click="handlePreview(scope.row)"
            v-hasPermi="['system:combinesync:query']"
          >预览</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-refresh"
            @click="handleExecute(scope.row)"
            v-hasPermi="['system:combinesync:execute']"
            :disabled="scope.row.syncStatus === '1'"
          >同步</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-tickets"
            @click="handleLog(scope.row)"
            v-hasPermi="['system:combinesync:log']"
          >日志</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-data-line"
            @click="showSyncProgress(scope.row.combineId)"
            v-hasPermi="['system:combinesync:query']"
            v-if="scope.row.syncStatus !== '0'"
          >进度</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-time"
            @click="handleProgress(scope.row)"
            v-hasPermi="['system:combinesync:query']"
          >时间戳</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-refresh"
            @click="handleRefreshCombineProgress(scope.row)"
            v-hasPermi="['system:combinesync:execute']"
            :disabled="scope.row.syncStatus === '1'"
          >刷新TS</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:combinesync:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:combinesync:remove']"
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
            <el-form-item label="任务名称" prop="combineName">
              <el-input v-model="form.combineName" placeholder="请输入任务名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务编码" prop="combineCode">
              <el-input v-model="form.combineCode" placeholder="可选，用于标识任务" />
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
            placeholder="多个模式用逗号分隔，支持通配符%，如: erp_charge_%（匹配erp_charge_100001, erp_charge_100002等分库）"
          ></el-input>
        </el-form-item>

        <el-form-item label="源表名" prop="sourceTable">
          <el-input
            v-model="form.sourceTable"
            type="textarea"
            :rows="2"
            placeholder="多个模式用逗号分隔，支持通配符%，如: tb_charge_fee（所有分库中的同名表将合并到目标表）"
          ></el-input>
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
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标库名" prop="targetTable">
              <el-input v-model="form.targetTable" placeholder="如: tidb_combine" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="来源列名" prop="sourceColumn">
          <el-input v-model="form.sourceColumn" placeholder="默认: source_table" style="width: 300px;">
            <template slot="append">
              <el-tooltip content="目标表中用于标识数据来源的列名，值格式为库名.表名" placement="top">
                <i class="el-icon-question"></i>
              </el-tooltip>
            </template>
          </el-input>
        </el-form-item>

        <div class="form-section">同步配置</div>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="同步模式" prop="syncMode">
              <el-radio-group v-model="form.syncMode" @change="handleSyncModeChange">
                <el-radio label="0">初始化同步</el-radio>
                <el-radio label="1">增量同步</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.syncMode === '0'">
            <el-form-item label="初始化清空" prop="isClear">
              <el-switch v-model="form.isClear" active-value="1" inactive-value="0" />
              <span style="margin-left: 8px; color: #909399; font-size: 12px;">清空目标表后重新导入</span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="时间戳字段" prop="timestampField">
              <el-input v-model="form.timestampField" placeholder="默认: time_stamp">
                <template slot="append">
                  <el-tooltip content="增量同步时依据此字段判断变化记录" placement="top">
                    <i class="el-icon-question"></i>
                  </el-tooltip>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12" v-if="form.syncMode === '1'">
            <el-form-item label="同步操作" prop="syncOperations">
              <el-checkbox v-model="syncOperations.insert">INSERT</el-checkbox>
              <el-checkbox v-model="syncOperations.update">UPDATE(INSERT覆盖)</el-checkbox>
              <el-checkbox v-model="syncOperations.delete">DELETE</el-checkbox>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="批次大小" prop="batchSize">
              <el-input-number v-model="form.batchSize" :min="100" :max="1000000" :step="100" />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-section">其他配置</div>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="Cron表达式" prop="cronExpression">
              <el-input v-model="form.cronExpression" placeholder="定时任务，可选" />
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
    <el-dialog title="合并预览 - 匹配的分库分表列表" :visible.sync="previewOpen" width="750px" append-to-body>
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
          <el-table-column prop="tableName" label="表名" min-width="150" show-overflow-tooltip />
          <el-table-column prop="sourceTag" label="来源标识" min-width="200" show-overflow-tooltip />
        </el-table>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button @click="previewOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 日志对话框 -->
    <el-dialog title="合并同步日志" :visible.sync="logOpen" width="1200px" append-to-body>
      <el-form :model="logQueryParams" ref="logQueryForm" size="small" :inline="true">
        <el-form-item label="源库名" prop="sourceDatabase">
          <el-input
            v-model="logQueryParams.sourceDatabase"
            placeholder="请输入源库名"
            clearable
            style="width: 180px"
            @keyup.enter.native="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="源表名" prop="sourceTable">
          <el-input
            v-model="logQueryParams.sourceTable"
            placeholder="请输入源表名"
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
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" size="mini" @click="handleLogQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" size="mini" @click="resetLogQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="logList" max-height="400" border size="small" v-loading="logLoading">
        <el-table-column label="源库" align="center" prop="sourceDatabase" min-width="120" show-overflow-tooltip />
        <el-table-column label="源表(库名.表名)" align="center" prop="sourceTable" min-width="150" show-overflow-tooltip />
        <el-table-column label="目标库名" align="center" prop="targetTable" min-width="120" show-overflow-tooltip />
        <el-table-column label="模式" align="center" prop="syncMode" width="70">
          <template slot-scope="scope">
            <el-tag :type="scope.row.syncMode === '0' ? 'info' : 'primary'" size="mini">
              {{ scope.row.syncMode === '0' ? '初始化' : '增量' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" prop="syncAction" width="90" />
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
        <el-table-column label="来源(库名.表名)" align="center" prop="sourceTable" min-width="200" show-overflow-tooltip />
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
          @click="handleRefreshCombineProgress({ combineId: progressCombineId })"
          v-hasPermi="['system:combinesync:execute']"
        >刷新最大时间戳</el-button>
        <el-button @click="progressOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 同步进度对话框 -->
    <el-dialog
      title="合并同步进度"
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
import { listCombineSync, getCombineSync, delCombineSync, addCombineSync, updateCombineSync, executeCombineSync, previewCombineSync, listCombineLog, getCombineProgress, refreshCombineProgress, getCombineSyncProgress, stopCombineSync } from "@/api/system/combinesync";
import { listDatasource } from "@/api/system/datasource";

export default {
  name: "CombineSync",
  data() {
    return {
      advanced: false,
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      combineSyncList: [],
      datasourceOptions: [],
      title: "",
      open: false,
      previewOpen: false,
      previewLoading: false,
      previewTables: [],
      previewSchemaCount: 0,
      previewTableCount: 0,
      logOpen: false,
      logLoading: false,
      logList: [],
      logTotal: 0,
      logQueryParams: {
        pageNum: 1,
        pageSize: 10,
        combineId: null,
        sourceDatabase: null,
        sourceTable: null,
        syncMode: null,
        syncResult: null
      },
      progressOpen: false,
      progressLoading: false,
      progressRefreshLoading: false,
      progressCombineId: null,
      progressList: [],
      syncProgressOpen: false,
      syncProgressLoading: false,
      syncProgressData: {},
      syncProgressTimer: null,
      stopping: false,
      syncOperations: {
        insert: true,
        update: true,
        delete: false
      },
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        combineName: null,
        sourceDatasourceId: null,
        syncMode: null,
        syncStatus: null,
        status: null
      },
      form: {},
      rules: {
        combineName: [
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
        ],
        targetTable: [
          { required: true, message: "目标表名不能为空", trigger: "blur" }
        ]
      }
    };
  },
  computed: {
    syncProgressInfo() {
      if (!this.syncProgressData.syncProgress) {
        return { current: 0, total: 0, percent: 0, message: '' };
      }
      try {
        const parsed = JSON.parse(this.syncProgressData.syncProgress);
        return {
          current: parsed.current || 0,
          total: (parsed.total > 0) ? parsed.total : 0,
          percent: parsed.percent || 0,
          message: parsed.message || ''
        };
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
    this.stopSyncProgressPolling();
  },
  methods: {
    getList() {
      this.loading = true;
      listCombineSync(this.queryParams).then(response => {
        this.combineSyncList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    getDatasourceOptions() {
      listDatasource({ status: '0' }).then(response => {
        // 合并同步功能只支持 StarRocks 类型的数据源
        this.datasourceOptions = response.rows.filter(item => 
          item.datasourceType && item.datasourceType.toUpperCase() === 'STARROCKS'
        );
      });
    },
    cancel() {
      this.open = false;
      this.reset();
    },
    reset() {
      this.form = {
        combineId: null,
        combineName: null,
        combineCode: null,
        sourceDatasourceId: null,
        sourceSchemaPattern: null,
        sourceTable: null,
        targetDatasourceId: null,
        targetTable: null,
        sourceColumn: "source_table",
        syncMode: "0",
        timestampField: "time_stamp",
        syncInsert: "1",
        syncUpdate: "1",
        syncDelete: "0",
        batchSize: 500000,
        isClear: "1",
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
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.combineId);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "新增合并同步任务";
    },
    handleUpdate(row) {
      this.reset();
      const combineId = row.combineId || this.ids;
      getCombineSync(combineId).then(response => {
        this.form = response.data;
        this.syncOperations.insert = this.form.syncInsert === '1';
        this.syncOperations.update = this.form.syncUpdate === '1';
        this.syncOperations.delete = this.form.syncDelete === '1';
        this.open = true;
        this.title = "修改合并同步任务";
      });
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          // 合并同步允许源和目标使用同一个数据源（从不同库/表合并到同一数据源的不同库/表）
          this.form.syncInsert = this.syncOperations.insert ? '1' : '0';
          this.form.syncUpdate = this.syncOperations.update ? '1' : '0';
          this.form.syncDelete = this.syncOperations.delete ? '1' : '0';

          if (this.form.combineId != null) {
            updateCombineSync(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addCombineSync(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    handleDelete(row) {
      const combineIds = row.combineId || this.ids;
      this.$modal.confirm('是否确认删除合并任务编号为"' + combineIds + '"的数据项？').then(function() {
        return delCombineSync(combineIds);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    handleStatusChange(row) {
      let text = row.status === "0" ? "启用" : "停用";
      this.$modal.confirm('确认要"' + text + '""' + row.combineName + '"任务吗？').then(function() {
        return updateCombineSync({ combineId: row.combineId, status: row.status });
      }).then(() => {
        this.$modal.msgSuccess(text + "成功");
      }).catch(function() {
        row.status = row.status === "0" ? "1" : "0";
      });
    },
    handleSourceChange(val) {
      // 合并同步允许源和目标使用同一个数据源，无需清空
    },
    handleSyncModeChange(val) {
      if (val === '0') {
        this.syncOperations.insert = true;
        this.syncOperations.update = true;
        this.syncOperations.delete = false;
      } else {
        this.syncOperations.insert = true;
        this.syncOperations.update = true;
      }
    },
    handlePreview(row) {
      this.previewOpen = true;
      this.previewLoading = true;
      this.previewTables = [];
      this.previewSchemaCount = 0;
      this.previewTableCount = 0;
      previewCombineSync(row.combineId).then(response => {
        const result = response.data || {};
        this.previewTables = result.tables || [];
        this.previewSchemaCount = result.schemaCount || 0;
        this.previewTableCount = result.tableCount || 0;
        this.previewLoading = false;
      }).catch(() => {
        this.previewLoading = false;
      });
    },
    handleExecute(row) {
      this.$modal.confirm('确认要执行合并同步任务"' + row.combineName + '"吗？').then(() => {
        return executeCombineSync(row.combineId);
      }).then(response => {
        this.$modal.msgSuccess("合并同步任务已启动，请查看进度对话框");
        this.getList();
        this.showSyncProgress(row.combineId);
      }).catch(() => {});
    },
    handleLog(row) {
      this.logQueryParams = {
        pageNum: 1,
        pageSize: 10,
        combineId: row.combineId,
        sourceDatabase: null,
        sourceTable: null,
        syncMode: null,
        syncResult: null
      };
      this.logOpen = true;
      this.getLogList();
    },
    getLogList() {
      this.logLoading = true;
      this.logList = [];
      listCombineLog(this.logQueryParams).then(response => {
        this.logList = response.rows || [];
        this.logTotal = response.total || 0;
        this.logLoading = false;
      }).catch(() => {
        this.logLoading = false;
      });
    },
    handleLogQuery() {
      this.logQueryParams.pageNum = 1;
      this.getLogList();
    },
    resetLogQuery() {
      this.logQueryParams.sourceDatabase = null;
      this.logQueryParams.sourceTable = null;
      this.logQueryParams.syncMode = null;
      this.logQueryParams.syncResult = null;
      this.handleLogQuery();
    },
    handleProgress(row) {
      this.progressOpen = true;
      this.progressLoading = true;
      this.progressCombineId = row.combineId;
      this.progressList = [];
      getCombineProgress(row.combineId).then(response => {
        this.progressList = response.data || [];
        this.progressLoading = false;
      }).catch(() => {
        this.progressLoading = false;
      });
    },
    handleRefreshCombineProgress(row) {
      const combineId = row && row.combineId;
      if (!combineId) {
        return;
      }
      this.$modal.confirm('确认从目标表MAX时间戳刷新当前任务的表级增量进度吗？').then(() => {
        this.progressRefreshLoading = true;
        return refreshCombineProgress(combineId);
      }).then(response => {
        const data = response.data || {};
        this.$modal.msgSuccess(data.message || response.msg || '刷新成功');
        this.progressRefreshLoading = false;
        this.getList();
        if (this.progressOpen) {
          this.progressLoading = true;
          this.progressCombineId = combineId;
          return getCombineProgress(combineId).then(progressResponse => {
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
    showSyncProgress(combineId) {
      this.syncProgressOpen = true;
      this.syncProgressLoading = true;
      this.syncProgressData = {};
      this.fetchSyncProgress(combineId);
      this.startSyncProgressPolling(combineId);
    },
    fetchSyncProgress(combineId) {
      getCombineSyncProgress(combineId).then(response => {
        this.syncProgressData = response.data || {};
        this.syncProgressLoading = false;
        if (this.syncProgressData.syncStatus !== '1') {
          this.stopSyncProgressPolling();
        }
      }).catch(() => {
        this.syncProgressLoading = false;
      });
    },
    startSyncProgressPolling(combineId) {
      this.stopSyncProgressPolling();
      this.syncProgressTimer = setInterval(() => {
        this.fetchSyncProgress(combineId);
      }, 1000);
    },
    stopSyncProgressPolling() {
      if (this.syncProgressTimer) {
        clearInterval(this.syncProgressTimer);
        this.syncProgressTimer = null;
      }
    },
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
    getSyncProgressStatus() {
      if (this.syncProgressData.syncStatus === '2') return 'success';
      if (this.syncProgressData.syncStatus === '3') return 'exception';
      if (this.syncProgressData.syncStatus === '4') return 'warning';
      return null;
    },
    handleRefreshSyncProgress() {
      if (this.syncProgressData.combineId) {
        this.syncProgressLoading = true;
        this.fetchSyncProgress(this.syncProgressData.combineId);
      }
    },
    handleStopSync() {
      this.$modal.confirm('确认要停止当前合并同步任务吗？').then(() => {
        this.stopping = true;
        return stopCombineSync(this.syncProgressData.combineId);
      }).then(() => {
        this.$modal.msgSuccess("任务已停止");
        this.stopping = false;
        this.handleRefreshSyncProgress();
        this.getList();
      }).catch(() => {
        this.stopping = false;
      });
    },
    formatSyncResult(result) {
      if (!result) return '无结果';
      try {
        const obj = JSON.parse(result);
        return obj.message || obj.error || JSON.stringify(obj);
      } catch (e) {
        return result;
      }
    },
    handleSyncProgressClose() {
      this.stopSyncProgressPolling();
      this.syncProgressOpen = false;
      this.getList();
    },
    handleViewLogFromProgress() {
      if (!this.syncProgressData.combineId) return;
      const row = this.combineSyncList.find(item => item.combineId === this.syncProgressData.combineId);
      if (row) {
        this.handleLog(row);
      }
    },
    getSyncStatusType(status) {
      const map = { '0': 'info', '1': 'warning', '2': 'success', '3': 'danger', '4': 'warning' };
      return map[status] || 'info';
    },
    getSyncStatusLabel(status) {
      const map = { '0': '待同步', '1': '同步中', '2': '同步成功', '3': '同步失败', '4': '已停止' };
      return map[status] || '未知';
    }
  }
};
</script>

<style scoped>
::v-deep .el-textarea__inner::placeholder {
  font-size: 10px;
}
::v-deep .el-progress .el-progress__text {
  display: none !important;
}
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
