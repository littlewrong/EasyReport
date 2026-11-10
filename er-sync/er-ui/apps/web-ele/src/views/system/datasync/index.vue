<template>
  <BiTablePage
    title="结构同步任务"
    :show-search="showSearch"
    :show-pagination="total > 0"
  >
    <template #search>
      <el-form
        :model="queryParams"
        ref="queryForm"
        :inline="true"
        v-show="showSearch"
        label-width="88px"
      >
        <el-form-item label="任务名称" prop="syncName">
          <el-input
            v-model="queryParams.syncName"
            placeholder="请输入任务名称"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="源数据源" prop="sourceDatasourceId">
          <el-select
            v-model="queryParams.sourceDatasourceId"
            placeholder="请选择源数据源"
            clearable
          >
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
            <el-select
              v-model="queryParams.syncStatus"
              placeholder="请选择同步状态"
              clearable
            >
              <el-option label="待同步" value="0" />
              <el-option label="同步中" value="1" />
              <el-option label="同步成功" value="2" />
              <el-option label="同步失败" value="3" />
              <el-option label="已停止" value="4" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select
              v-model="queryParams.status"
              placeholder="请选择状态"
              clearable
            >
              <el-option label="正常" value="0" />
              <el-option label="停用" value="1" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery"
            >搜索</el-button
          >
          <el-button :icon="RotateCw" @click="resetQuery">重置</el-button>
          <el-button link type="primary" @click="advanced = !advanced">
            {{ advanced ? '收起' : '展开' }}
            <i
              :class="advanced ? 'el-icon-arrow-up' : 'el-icon-arrow-down'"
            ></i>
          </el-button>
        </el-form-item>
      </el-form>
    </template>
    <template #toolbar>
      <el-row :gutter="10" class="mb8">
        <el-col class="toolbar-action">
          <el-button
            type="primary"
            :icon="Plus"
            @click="handleAdd"
            v-hasPermi="['system:datasync:add']"
            >新增</el-button
          >
        </el-col>
        <el-col class="toolbar-action">
          <el-button
            :icon="SquareCode"
            :disabled="single"
            @click="handleUpdate"
            v-hasPermi="['system:datasync:edit']"
            >修改</el-button
          >
        </el-col>
        <el-col class="toolbar-action">
          <el-button
            type="danger"
            :icon="Eraser"
            :disabled="multiple"
            @click="handleDelete"
            v-hasPermi="['system:datasync:remove']"
            >删除</el-button
          >
        </el-col>
        <right-toolbar
          v-model:show-search="showSearch"
          @queryTable="getList"
        ></right-toolbar>
      </el-row>
    </template>
    <el-table
      height="100%"
      v-loading="loading"
      :data="dataSyncList"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column
        label="任务名称"
        align="center"
        prop="syncName"
        min-width="120"
        show-overflow-tooltip
      />
      <el-table-column
        label="源数据源"
        align="center"
        prop="sourceDatasourceName"
        min-width="100"
        show-overflow-tooltip
      />
      <el-table-column
        label="源表匹配"
        align="center"
        prop="sourceTablePattern"
        min-width="120"
        show-overflow-tooltip
      />
      <el-table-column
        label="目标数据源"
        align="center"
        prop="targetDatasourceName"
        min-width="100"
        show-overflow-tooltip
      />
      <el-table-column
        label="表已存在"
        align="center"
        prop="ifExistsAction"
        width="90"
      >
        <template #default="scope">
          <el-tag
            :type="scope.row.ifExistsAction === '0' ? 'info' : 'warning'"
            size="small"
          >
            {{ scope.row.ifExistsAction === '0' ? '跳过' : '删除重建' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="同步状态"
        align="center"
        prop="syncStatus"
        width="90"
      >
        <template #default="scope">
          <el-tag
            :type="getSyncStatusType(scope.row.syncStatus)"
            size="small"
            :style="
              scope.row.syncStatus === '1' ||
              scope.row.syncStatus === '2' ||
              scope.row.syncStatus === '3' ||
              scope.row.syncStatus === '4'
                ? 'cursor: pointer;'
                : ''
            "
            @click="
              scope.row.syncStatus !== '0'
                ? showProgress(scope.row.syncId)
                : null
            "
          >
            {{ getSyncStatusLabel(scope.row.syncStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" prop="status" width="80">
        <template #default="scope">
          <el-switch
            v-model="scope.row.status"
            active-value="0"
            inactive-value="1"
            @change="handleStatusChange(scope.row)"
          ></el-switch>
        </template>
      </el-table-column>
      <el-table-column
        label="最后同步时间"
        align="center"
        prop="lastSyncTime"
        width="160"
      >
        <template #default="scope">
          <span v-if="scope.row.lastSyncTime">{{
            parseTime(scope.row.lastSyncTime, '{y}-{m}-{d} {h}:{i}:{s}')
          }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        align="center"
        width="280"
        fixed="right"
        class-name="bi-actions-column"
      >
        <template #default="scope"
          ><div class="bi-row-actions">
            <el-button
              size="small"
              link
              type="primary"
              :icon="Eye"
              @click="handlePreview(scope.row)"
              v-hasPermi="['system:datasync:query']"
              >预览</el-button
            >
            <el-button
              size="small"
              link
              type="primary"
              :icon="RotateCw"
              @click="handleExecute(scope.row)"
              v-hasPermi="['system:datasync:execute']"
              :disabled="scope.row.syncStatus === '1'"
              >同步</el-button
            >
            <el-button
              size="small"
              link
              type="primary"
              :icon="List"
              @click="handleLog(scope.row)"
              v-hasPermi="['system:datasync:log']"
              >日志</el-button
            >
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              v-hasPermi="[
                'system:datasync:query',
                'system:datasync:edit',
                'system:datasync:add',
                'system:datasync:remove',
              ]"
            >
              <el-button link type="primary" aria-label="更多操作"
                >更多<ChevronDown class="bi-row-actions-chevron"
              /></el-button>
              <template #dropdown
                ><el-dropdown-menu>
                  <el-dropdown-item
                    :icon="Circle"
                    v-hasPermi="['system:datasync:query']"
                    :disabled="scope.row.syncStatus === '0'"
                    @click="showProgress(scope.row.syncId)"
                    >进度</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="SquareCode"
                    v-hasPermi="['system:datasync:edit']"
                    @click="handleUpdate(scope.row)"
                    >修改</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="Copy"
                    v-hasPermi="['system:datasync:add']"
                    @click="handleCopy(scope.row)"
                    >复制</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="Eraser"
                    v-hasPermi="['system:datasync:remove']"
                    @click="handleDelete(scope.row)"
                    class="bi-row-action-delete"
                    :divided="
                      $auth.hasPermiOr([
                        'system:datasync:query',
                        'system:datasync:edit',
                        'system:datasync:add',
                      ])
                    "
                    >删除</el-dropdown-item
                  >
                </el-dropdown-menu></template
              >
            </el-dropdown>
          </div></template
        >
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
      :title="title"
      v-model="open"
      size="900px"
      append-to-body
    >
      <el-form
        class="bi-editor-form"
        label-position="top"
        ref="form"
        :model="form"
        :rules="rules"
        label-width="110px"
      >
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="任务名称" prop="syncName">
              <el-input v-model="form.syncName" placeholder="请输入任务名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务编码" prop="syncCode">
              <el-input
                v-model="form.syncCode"
                placeholder="可选，用于标识任务"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">源数据源配置</el-divider>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="源数据源" prop="sourceDatasourceId">
              <el-select
                v-model="form.sourceDatasourceId"
                placeholder="请选择源数据源"
                style="width: 100%"
                @change="handleSourceChange"
              >
                <el-option
                  v-for="item in datasourceOptions"
                  :key="item.datasourceId"
                  :label="
                    item.datasourceName + ' (' + item.datasourceType + ')'
                  "
                  :value="item.datasourceId"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="分片库架构创建" prop="structureReuseMode">
          <el-switch
            v-model="form.structureReuseMode"
            active-value="1"
            inactive-value="0"
            :disabled="!supportsShardedSchema"
          />
          <div class="form-tip" v-if="!supportsShardedSchema">
            请选择 MySQL、TiDB、StarRocks、PostgreSQL、SQL Server 或 Oracle
            源数据源。
          </div>
          <div class="form-tip" v-else-if="form.structureReuseMode === '1'">
            按库名或 Schema
            名排序选择第一个匹配范围，只采集其表结构并用于所有匹配范围；多个模式共用一份结构，结构不同请拆分任务。
          </div>
          <div class="form-tip" v-else>关闭时逐库采集表结构。</div>
        </el-form-item>

        <el-form-item label="源Schema模式" prop="sourceSchemaPattern">
          <el-input
            v-model="form.sourceSchemaPattern"
            type="textarea"
            :rows="2"
            :placeholder="
              form.structureReuseMode === '1'
                ? '多个模式用逗号分隔，支持通配符%，留空范围见下方提示'
                : '多个模式用逗号分隔，支持通配符%，如: db_%, schema_2024%, test_db（可选，留空表示所有Schema）'
            "
          >
          </el-input>
          <div class="form-tip" v-if="form.structureReuseMode === '1'">
            {{ shardScopeHint }}
          </div>
        </el-form-item>

        <el-form-item label="源表匹配模式" prop="sourceTablePattern">
          <el-input
            v-model="form.sourceTablePattern"
            type="textarea"
            :rows="2"
            placeholder="多个模式用逗号分隔，支持通配符%，如: user_%, order_2024%, sys_user"
          >
          </el-input>
        </el-form-item>

        <el-divider content-position="left">目标数据源配置</el-divider>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="目标数据源" prop="targetDatasourceId">
              <el-select
                v-model="form.targetDatasourceId"
                placeholder="请选择目标数据源"
                style="width: 100%"
              >
                <el-option
                  v-for="item in datasourceOptions"
                  :key="item.datasourceId"
                  :label="
                    item.datasourceName + ' (' + item.datasourceType + ')'
                  "
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
                <el-radio value="0">跳过</el-radio>
                <el-radio value="1">删除重建</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="请输入备注"
          />
        </el-form-item>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="form.status">
                <el-radio value="0">正常</el-radio>
                <el-radio value="1">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer
        ><div class="dialog-footer">
          <el-button @click="cancel">取 消</el-button>
          <el-button type="primary" @click="submitForm">确 定</el-button>
        </div></template
      >
    </el-drawer>
    <el-dialog
      title="同步预览 - 匹配的表列表"
      v-model="previewOpen"
      width="700px"
      append-to-body
    >
      <div v-loading="previewLoading">
        <el-alert
          v-if="previewTemplateSchema"
          :title="'本次模板库：' + previewTemplateSchema"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 15px"
        />
        <el-alert
          v-if="previewTables.length > 0"
          :title="
            '共匹配到 ' +
            previewSchemaCount +
            ' 个库，' +
            previewTableCount +
            ' 张表'
          "
          type="success"
          :closable="false"
          show-icon
          style="margin-bottom: 15px"
        />
        <el-alert
          v-else
          title="未匹配到任何表，请检查源数据源和匹配模式"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 15px"
        />
        <el-table :data="previewTables" max-height="400" border size="small">
          <el-table-column type="index" label="#" width="50" align="center" />
          <el-table-column
            prop="schemaName"
            label="库名"
            width="180"
            show-overflow-tooltip
          />
          <el-table-column
            prop="tableName"
            label="表名"
            show-overflow-tooltip
          />
        </el-table>
      </div>
      <template #footer
        ><div class="dialog-footer">
          <el-button @click="previewOpen = false">关 闭</el-button>
        </div></template
      >
    </el-dialog>
    <el-dialog title="同步日志" v-model="logOpen" width="1200px" append-to-body>
      <!-- 搜索条件 -->
      <el-form
        :model="logQueryParams"
        ref="logQueryForm"
        size="small"
        :inline="true"
      >
        <el-form-item label="源表名称" prop="sourceTable">
          <el-input
            v-model="logQueryParams.sourceTable"
            placeholder="请输入源表名称"
            clearable
            style="width: 180px"
            @keyup.enter="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="目标表名称" prop="targetTable">
          <el-input
            v-model="logQueryParams.targetTable"
            placeholder="请输入目标表名称"
            clearable
            style="width: 180px"
            @keyup.enter="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="同步结果" prop="syncResult">
          <el-select
            v-model="logQueryParams.syncResult"
            placeholder="请选择"
            clearable
            style="width: 120px"
          >
            <el-option label="成功" value="0" />
            <el-option label="失败" value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="创建时间">
          <el-date-picker
            v-model="logDateRange"
            style="width: 240px"
            value-format="YYYY-MM-DD"
            type="daterange"
            range-separator="-"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          ></el-date-picker>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :icon="Search"
            size="small"
            @click="handleLogQuery"
            >搜索</el-button
          >
          <el-button :icon="RotateCw" size="small" @click="resetLogQuery"
            >重置</el-button
          >
        </el-form-item>
      </el-form>

      <!-- 日志列表 -->
      <el-table
        :data="logList"
        max-height="400"
        border
        size="small"
        v-loading="logLoading"
      >
        <el-table-column
          label="源表"
          align="center"
          prop="sourceTable"
          min-width="120"
          show-overflow-tooltip
        />
        <el-table-column
          label="目标表"
          align="center"
          prop="targetTable"
          min-width="120"
          show-overflow-tooltip
        />
        <el-table-column
          label="操作"
          align="center"
          prop="syncAction"
          width="80"
        />
        <el-table-column
          label="结果"
          align="center"
          prop="syncResult"
          width="70"
        >
          <template #default="scope">
            <el-tag
              :type="scope.row.syncResult === '0' ? 'success' : 'danger'"
              size="small"
            >
              {{ scope.row.syncResult === '0' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="影响行数"
          align="center"
          prop="rowsAffected"
          width="90"
        />
        <el-table-column
          label="耗时(ms)"
          align="center"
          prop="executeTime"
          width="90"
        />
        <el-table-column
          label="错误信息"
          align="center"
          prop="errorMessage"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          label="时间"
          align="center"
          prop="createTime"
          width="160"
        >
          <template #default="scope">
            <span>{{
              parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}')
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="80" fixed="right">
          <template #default="scope">
            <el-button
              size="small"
              link
              type="primary"
              :icon="Eye"
              @click="handleLogDetail(scope.row)"
              >详情</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <pagination
        v-show="logTotal > 0"
        :total="logTotal"
        v-model:page="logQueryParams.pageNum"
        v-model:limit="logQueryParams.pageSize"
        @pagination="getLogList"
      />

      <template #footer
        ><div class="dialog-footer">
          <el-button @click="logOpen = false">关 闭</el-button>
        </div></template
      >
    </el-dialog>
    <el-dialog
      title="同步进度"
      v-model="progressOpen"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="true"
      @close="handleProgressClose"
      append-to-body
    >
      <div v-loading="progressLoading">
        <el-alert
          :title="getProgressTitle()"
          :type="getProgressAlertType()"
          :closable="false"
          show-icon
          style="margin-bottom: 20px"
        >
        </el-alert>

        <el-alert
          v-if="progressTemplateSchema"
          :title="'本次模板库：' + progressTemplateSchema"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 15px"
        />

        <div v-if="progressData.syncProgress" style="padding: 0 20px">
          <div style="margin-bottom: 15px">
            <span style="font-weight: bold">进度：</span>
            <span
              >{{ progressInfo.current || 0 }} /
              {{ progressInfo.total || 0 }}</span
            >
          </div>
          <el-progress
            :percentage="progressInfo.percent || 0"
            :status="getProgressStatus()"
            :stroke-width="20"
            :show-text="false"
          >
          </el-progress>
          <div
            v-if="progressData.syncStatus === '1'"
            style="
              margin-top: 15px;
              color: hsl(var(--muted-foreground));
              font-size: 13px;
            "
          >
            {{ progressInfo.message || '准备开始同步...' }}
          </div>
        </div>

        <!-- 已同步的表列表 -->
        <div
          v-if="
            progressInfo.completedTables &&
            progressInfo.completedTables.length > 0
          "
          style="margin-top: 20px; padding: 0 20px"
        >
          <el-divider content-position="left"
            >已同步 ({{ progressInfo.completedTables.length }})</el-divider
          >
          <el-table
            :data="progressInfo.completedTables"
            max-height="200"
            border
            size="small"
            style="width: 100%"
          >
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="table" label="表名" show-overflow-tooltip>
              <template #default="scope">
                {{ scope.row }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 待同步的表列表 -->
        <div
          v-if="
            progressInfo.pendingTables && progressInfo.pendingTables.length > 0
          "
          style="margin-top: 15px; padding: 0 20px"
        >
          <el-divider content-position="left"
            >待同步 ({{ progressInfo.pendingTables.length }})</el-divider
          >
          <el-table
            :data="progressInfo.pendingTables"
            max-height="150"
            border
            size="small"
            style="width: 100%"
          >
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="table" label="表名" show-overflow-tooltip>
              <template #default="scope">
                {{ scope.row }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div
          v-if="
            progressData.lastSyncResult &&
            (progressData.syncStatus === '2' ||
              progressData.syncStatus === '3' ||
              progressData.syncStatus === '4')
          "
          style="margin-top: 20px; padding: 0 20px"
        >
          <el-divider content-position="left">同步结果</el-divider>
          <div
            style="
              background: hsl(var(--muted));
              padding: 10px;
              border-radius: 4px;
              font-size: 13px;
            "
          >
            {{ formatSyncResult(progressData.lastSyncResult) }}
          </div>
        </div>

        <div
          v-if="progressData.lastSyncTime"
          style="
            margin-top: 15px;
            padding: 0 20px;
            font-size: 13px;
            color: hsl(var(--muted-foreground));
          "
        >
          最后同步时间：{{
            parseTime(progressData.lastSyncTime, '{y}-{m}-{d} {h}:{i}:{s}')
          }}
        </div>
      </div>

      <template #footer
        ><div class="dialog-footer">
          <el-button @click="handleProgressClose">关 闭</el-button>
          <el-button
            @click="handleStopSync"
            type="danger"
            v-if="progressData.syncStatus === '1'"
            >停止同步</el-button
          >
          <el-button
            @click="handleViewLog"
            type="primary"
            v-if="
              progressData.syncStatus === '2' ||
              progressData.syncStatus === '3' ||
              progressData.syncStatus === '4'
            "
            >查看日志</el-button
          >
        </div></template
      >
    </el-dialog>
    <el-drawer
      title="SQL执行详情"
      v-model="logDetailOpen"
      direction="rtl"
      size="50%"
      @closed="handleLogDetailClose"
    >
      <div style="padding: 20px">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="源表名称">{{
            logDetail.sourceTable
          }}</el-descriptions-item>
          <el-descriptions-item label="目标表名称">{{
            logDetail.targetTable
          }}</el-descriptions-item>
          <el-descriptions-item label="同步操作">{{
            logDetail.syncAction
          }}</el-descriptions-item>
          <el-descriptions-item label="同步结果">
            <el-tag
              :type="logDetail.syncResult === '0' ? 'success' : 'danger'"
              size="small"
            >
              {{ logDetail.syncResult === '0' ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="影响行数">{{
            logDetail.rowsAffected
          }}</el-descriptions-item>
          <el-descriptions-item label="执行耗时"
            >{{ logDetail.executeTime }} ms</el-descriptions-item
          >
          <el-descriptions-item label="创建时间" :span="2">
            {{ parseTime(logDetail.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}
          </el-descriptions-item>
          <el-descriptions-item
            label="错误信息"
            :span="2"
            v-if="logDetail.errorMessage"
          >
            <el-alert
              :title="logDetail.errorMessage"
              type="error"
              :closable="false"
            />
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">执行SQL</el-divider>
        <div style="position: relative">
          <el-button
            size="small"
            type="primary"
            :icon="Copy"
            style="position: absolute; top: 5px; right: 5px; z-index: 10"
            @click="copySql"
          >
            复制
          </el-button>
          <pre
            style="
              background: hsl(var(--muted));
              padding: 15px;
              border-radius: 4px;
              overflow-x: auto;
              margin-top: 10px;
            "
            >{{ logDetail.executeSql || '无SQL记录' }}</pre
          >
        </div>
      </div>
    </el-drawer>
  </BiTablePage>
</template>

<script>
import { Page } from '@vben/common-ui';
import {
  ChevronDown,
  Circle,
  Copy,
  Eraser,
  Eye,
  List,
  Plus,
  RotateCw,
  Search,
  SquareCode,
} from '@vben/icons';
import {
  listDataSync,
  getDataSync,
  delDataSync,
  addDataSync,
  updateDataSync,
  executeSync,
  previewSync,
  listSyncLog,
  getSyncProgress,
  stopSync,
} from '#/legacy/api/system/datasync';
import { getDatasourceOptions } from '#/legacy/api/system/datasource';

export default {
  components: {
    ChevronDown,
    Circle,
    Copy,
    Eraser,
    Eye,
    List,
    Plus,
    RotateCw,
    Search,
    SquareCode,
  },
  setup() {
    return {
      ChevronDown,
      Circle,
      Copy,
      Eraser,
      Eye,
      List,
      Plus,
      RotateCw,
      Search,
      SquareCode,
    };
  },
  name: 'DataSync',
  data() {
    return {
      pageActive: true,
      activeProgressTaskId: null,
      progressRequestVersion: 0,
      progressRequestTaskId: null,
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
      title: '',
      // 是否显示弹出层
      open: false,
      // 预览弹出层
      previewOpen: false,
      previewLoading: false,
      previewTables: [],
      previewSchemaCount: 0,
      previewTableCount: 0,
      previewTemplateSchema: '',
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
        endTime: null,
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
        status: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        syncName: [
          { required: true, message: '任务名称不能为空', trigger: 'blur' },
        ],
        sourceDatasourceId: [
          { required: true, message: '请选择源数据源', trigger: 'change' },
        ],
        sourceTablePattern: [
          { required: true, message: '源表匹配模式不能为空', trigger: 'blur' },
        ],
        targetDatasourceId: [
          { required: true, message: '请选择目标数据源', trigger: 'change' },
        ],
      },
    };
  },
  computed: {
    sourceDatasourceType() {
      const source = this.datasourceOptions.find(
        (item) =>
          String(item.datasourceId) === String(this.form.sourceDatasourceId),
      );
      return source ? String(source.datasourceType || '').toUpperCase() : '';
    },
    supportsShardedSchema() {
      return [
        'MYSQL',
        'TIDB',
        'STARROCKS',
        'POSTGRESQL',
        'SQLSERVER',
        'ORACLE',
        'KINGBASE',
        'DM',
      ].includes(this.sourceDatasourceType);
    },
    shardScopeHint() {
      if (this.sourceDatasourceType === 'DM') {
        return '匹配当前达梦实例内的模式，留空使用当前连接的默认模式；名称保持实际大小写。';
      }
      if (this.sourceDatasourceType === 'KINGBASE') {
        return '匹配当前连接数据库内的 Schema，留空使用当前连接的默认 Schema；名称保持实际大小写。';
      }
      if (this.sourceDatasourceType === 'POSTGRESQL') {
        return '匹配当前连接数据库内的 Schema，留空默认 public。';
      }
      if (this.sourceDatasourceType === 'SQLSERVER') {
        return '匹配数据库名，采集首库 dbo 下的表结构；留空默认当前数据库。';
      }
      if (this.sourceDatasourceType === 'ORACLE') {
        return '匹配用户 / Owner，留空默认当前用户。';
      }
      return '匹配数据库名，留空匹配所有可见数据库。';
    },
    progressTemplateSchema() {
      if (this.progressInfo.templateSchema) {
        return this.progressInfo.templateSchema;
      }
      if (!['2', '3', '4'].includes(this.progressData.syncStatus)) {
        return '';
      }
      try {
        return (
          JSON.parse(this.progressData.lastSyncResult || '{}').templateSchema ||
          ''
        );
      } catch (e) {
        return '';
      }
    },
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
    },
  },
  created() {
    this.getList();
    this.getDatasourceOptions();
  },
  activated() {
    if (!this.pageActive) {
      this.pageActive = true;
      this.getList();
      if (this.progressOpen && this.progressData.syncId) {
        this.fetchProgress(this.progressData.syncId);
        this.startProgressPolling(this.progressData.syncId);
      }
    }
  },
  deactivated() {
    this.pageActive = false;
    this.progressRequestVersion += 1;
    this.progressRequestTaskId = null;
    this.stopAutoRefresh();
    this.stopProgressPolling();
  },
  beforeUnmount() {
    this.pageActive = false;
    this.progressRequestVersion += 1;
    this.progressRequestTaskId = null;
    // 清除定时器
    this.stopAutoRefresh();
    this.stopProgressPolling();
  },
  methods: {
    /** 查询数据同步任务列表 */
    getList() {
      if (!this.pageActive) return;
      this.loading = true;
      listDataSync(this.queryParams)
        .then((response) => {
          this.dataSyncList = response.rows || [];
          this.total = response.total;
          this.loading = false;

          // 检查是否有同步中的任务
          const hasSyncing = this.dataSyncList.some(
            (item) => item.syncStatus === '1',
          );
          if (hasSyncing) {
            this.startAutoRefresh();
          } else {
            this.stopAutoRefresh();
          }
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false;
        });
    },
    /** 获取数据源选项 */
    getDatasourceOptions() {
      getDatasourceOptions().then((response) => {
        this.datasourceOptions = response.data || [];
      });
    },
    /** 开始自动刷新 */
    startAutoRefresh() {
      if (!this.pageActive) return;
      if (this.refreshTimer) {
        return; // 已经在运行
      }
      this.refreshTimer = setInterval(() => {
        // 静默刷新，不显示loading
        const originalLoading = this.loading;
        listDataSync(this.queryParams)
          .then((response) => {
            this.dataSyncList = response.rows || [];
            this.total = response.total;
            // 检查是否还有同步中的任务
            const hasSyncing = this.dataSyncList.some(
              (item) => item.syncStatus === '1',
            );
            if (!hasSyncing) {
              this.stopAutoRefresh();
            }
          })
          .catch(() => {})
          .finally(() => {
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
        structureReuseMode: '0',
        targetDatasourceId: null,
        ifExistsAction: '0',
        status: '0',
        remark: null,
      };
      this.$nextTick(() => this.$refs.form?.clearValidate());
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm('queryForm');
      this.handleQuery();
    },
    /** 多选框选中数据 */
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.syncId);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = '添加同步任务';
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const syncId = row.syncId || this.ids;
      getDataSync(syncId).then((response) => {
        this.form = {
          ...response.data,
          structureReuseMode: response.data.structureReuseMode || '0',
        };
        this.open = true;
        this.title = '修改同步任务';
      });
    },
    /** 复制按钮操作 */
    handleCopy(row) {
      this.reset();
      const syncId = row.syncId;
      getDataSync(syncId).then((response) => {
        this.form = {
          ...response.data,
          structureReuseMode: response.data.structureReuseMode || '0',
        };
        this.form.syncId = null;
        this.form.syncCode = null;
        this.form.syncName = (this.form.syncName || '') + '_copy';
        this.open = true;
        this.title = '复制同步任务';
      });
    },
    /** 提交按钮 */
    submitForm() {
      if (!this.$refs.form) return;
      this.$refs.form.validate((valid) => {
        if (valid) {
          // 检查源和目标数据源不能相同
          if (this.form.sourceDatasourceId === this.form.targetDatasourceId) {
            this.$modal.msgError('源数据源和目标数据源不能相同');
            return;
          }
          if (!this.supportsShardedSchema) {
            this.form.structureReuseMode = '0';
          }
          if (this.form.syncId != null) {
            updateDataSync(this.form).then((response) => {
              this.$modal.msgSuccess('修改成功');
              this.open = false;
              this.getList();
            });
          } else {
            addDataSync(this.form).then((response) => {
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
      const syncIds = row.syncId || this.ids;
      this.$modal
        .confirm('是否确认删除同步任务编号为"' + syncIds + '"的数据项？')
        .then(function () {
          return delDataSync(syncIds);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
    /** 状态修改 */
    handleStatusChange(row) {
      let text = row.status === '0' ? '启用' : '停用';
      this.$modal
        .confirm('确认要"' + text + '""' + row.syncName + '"任务吗？')
        .then(function () {
          return updateDataSync({ syncId: row.syncId, status: row.status });
        })
        .then(() => {
          this.$modal.msgSuccess(text + '成功');
        })
        .catch(function () {
          row.status = row.status === '0' ? '1' : '0';
        });
    },
    /** 源数据源变更 */
    handleSourceChange(val) {
      this.form.structureReuseMode = '0';
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
      this.previewTemplateSchema = '';
      previewSync(row.syncId)
        .then((response) => {
          const result = response.data || {};
          this.previewTables = result.tables || [];
          this.previewSchemaCount = result.schemaCount || 0;
          this.previewTableCount = result.tableCount || 0;
          this.previewTemplateSchema = result.templateSchema || '';
          this.previewLoading = false;
        })
        .catch(() => {
          this.previewLoading = false;
        });
    },
    /** 执行同步 */
    handleExecute(row) {
      this.$modal
        .confirm('确认要执行同步任务"' + row.syncName + '"吗？')
        .then(() => {
          return executeSync(row.syncId);
        })
        .then((response) => {
          this.$modal.msgSuccess(response.data.message || '同步请求已提交');
          this.getList();
          // 显示进度对话框
          this.showProgress(row.syncId);
        })
        .catch(() => {});
    },
    /** 显示进度对话框 */
    showProgress(syncId) {
      this.activeProgressTaskId = syncId;
      this.progressRequestVersion += 1;
      this.progressRequestTaskId = null;
      this.progressOpen = true;
      this.progressLoading = true;
      this.progressData = { syncId };
      // 立即获取一次进度
      this.fetchProgress(syncId);
      // 开始轮询
      this.startProgressPolling(syncId);
    },
    /** 获取进度 */
    fetchProgress(syncId) {
      if (
        !this.pageActive ||
        !this.progressOpen ||
        this.activeProgressTaskId !== syncId ||
        this.progressRequestTaskId === syncId
      )
        return;
      const requestVersion = ++this.progressRequestVersion;
      this.progressRequestTaskId = syncId;
      getSyncProgress(syncId)
        .then((response) => {
          if (
            requestVersion !== this.progressRequestVersion ||
            !this.pageActive ||
            !this.progressOpen
          )
            return;
          this.progressData = response.data || { syncId };
          this.progressLoading = false;
          // 如果同步完成，停止轮询
          if (this.progressData.syncStatus !== '1') {
            this.stopProgressPolling();
          }
        })
        .catch(() => {
          if (requestVersion !== this.progressRequestVersion) return;
          this.progressLoading = false;
        })
        .finally(() => {
          if (requestVersion === this.progressRequestVersion)
            this.progressRequestTaskId = null;
        });
    },
    /** 开始进度轮询 */
    startProgressPolling(syncId) {
      if (!this.pageActive) return;
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
        0: '准备同步',
        1: '正在同步中...',
        2: '同步成功',
        3: '同步失败',
        4: '已停止',
      };
      return statusMap[this.progressData.syncStatus] || '同步状态未知';
    },
    /** 获取进度Alert类型 */
    getProgressAlertType() {
      const typeMap = {
        0: 'info',
        1: 'warning',
        2: 'success',
        3: 'error',
        4: 'warning',
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
      this.activeProgressTaskId = null;
      this.progressRequestVersion += 1;
      this.progressRequestTaskId = null;
      this.stopProgressPolling();
      this.progressOpen = false;
    },
    handleViewLog() {
      if (!this.progressData.syncId) {
        return;
      }
      // 找到对应的行数据
      const row = this.dataSyncList.find(
        (item) => item.syncId === this.progressData.syncId,
      );
      if (row) {
        this.handleLog(row);
      }
    },
    /** 停止同步 */
    handleStopSync() {
      if (!this.progressData.syncId) {
        return;
      }
      this.$modal
        .confirm('确认要停止正在执行的同步任务吗？')
        .then(() => {
          return stopSync(this.progressData.syncId);
        })
        .then((response) => {
          this.$modal.msgSuccess(response.data.message || '同步已停止');
          // 立即刷新进度
          this.fetchProgress(this.progressData.syncId);
          // 刷新列表
          this.getList();
        })
        .catch(() => {});
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
        endTime: null,
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
      listSyncLog(this.logQueryParams)
        .then((response) => {
          this.logList = response.rows || [];
          this.logTotal = response.total || 0;
          this.logLoading = false;
        })
        .catch(() => {
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
      const map = {
        0: 'info',
        1: 'warning',
        2: 'success',
        3: 'danger',
        4: 'warning',
      };
      return map[status] || 'info';
    },
    /** 获取同步状态标签 */
    getSyncStatusLabel(status) {
      const map = {
        0: '待同步',
        1: '同步中',
        2: '同步成功',
        3: '同步失败',
        4: '已停止',
      };
      return map[status] || '未知';
    },
  },
};
</script>

<style scoped>
.toolbar-action {
  flex: 0 0 auto;
  max-width: none;
  width: auto;
}
.inline-icon {
  display: inline-block;
  width: 16px;
  height: 16px;
  vertical-align: middle;
}
.app-container > .el-form :deep(.el-select) {
  min-width: 180px;
}

.form-tip {
  margin-top: 4px;
  color: hsl(var(--muted-foreground));
  font-size: 12px;
  line-height: 20px;
}
/* 缩小 textarea placeholder 字体大小 */
:deep(.el-textarea__inner::placeholder) {
  font-size: 10px;
}
/* 隐藏进度条右侧状态图标（show-text=false 时 Element UI 仍会渲染图标容器） */
:deep(.el-progress .el-progress__text) {
  display: none;
}
</style>
