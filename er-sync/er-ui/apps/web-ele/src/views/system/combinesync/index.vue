<template>
  <BiTablePage
    title="合并同步任务"
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
        <el-form-item label="任务名称" prop="combineName">
          <el-input
            v-model="queryParams.combineName"
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
        <el-form-item label="同步模式" prop="syncMode">
          <el-select
            v-model="queryParams.syncMode"
            placeholder="请选择同步模式"
            clearable
          >
            <el-option label="初始化同步" value="0" />
            <el-option label="增量同步" value="1" />
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
            v-hasPermi="['system:combinesync:add']"
            >新增</el-button
          >
        </el-col>
        <el-col class="toolbar-action">
          <el-button
            :icon="SquareCode"
            :disabled="single"
            @click="handleUpdate"
            v-hasPermi="['system:combinesync:edit']"
            >修改</el-button
          >
        </el-col>
        <el-col class="toolbar-action">
          <el-button
            type="danger"
            :icon="Eraser"
            :disabled="multiple"
            @click="handleDelete"
            v-hasPermi="['system:combinesync:remove']"
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
      :data="combineSyncList"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column
        label="任务名称"
        align="center"
        prop="combineName"
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
        label="源Schema模式"
        align="center"
        prop="sourceSchemaPattern"
        min-width="120"
        show-overflow-tooltip
      />
      <el-table-column
        label="源表名"
        align="center"
        prop="sourceTable"
        min-width="100"
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
        label="目标库名"
        align="center"
        prop="targetTable"
        min-width="100"
        show-overflow-tooltip
      />
      <el-table-column
        label="来源列"
        align="center"
        prop="sourceColumn"
        width="100"
        show-overflow-tooltip
      />
      <el-table-column
        label="同步模式"
        align="center"
        prop="syncMode"
        width="90"
      >
        <template #default="scope">
          <el-tag
            :type="scope.row.syncMode === '0' ? 'info' : 'primary'"
            size="small"
          >
            {{ scope.row.syncMode === '0' ? '初始化' : '增量' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步操作" align="center" width="120">
        <template #default="scope">
          <el-tag
            v-if="scope.row.syncInsert === '1'"
            type="success"
            size="small"
            style="margin-right: 2px"
            >I</el-tag
          >
          <el-tag
            v-if="scope.row.syncUpdate === '1'"
            type="warning"
            size="small"
            style="margin-right: 2px"
            >U</el-tag
          >
          <el-tag v-if="scope.row.syncDelete === '1'" type="danger" size="small"
            >D</el-tag
          >
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
            :style="scope.row.syncStatus !== '0' ? 'cursor: pointer;' : ''"
            @click="
              scope.row.syncStatus !== '0'
                ? showSyncProgress(scope.row.combineId)
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
              v-hasPermi="['system:combinesync:query']"
              >预览</el-button
            >
            <el-button
              size="small"
              link
              type="primary"
              :icon="RotateCw"
              @click="handleExecute(scope.row)"
              v-hasPermi="['system:combinesync:execute']"
              :disabled="scope.row.syncStatus === '1'"
              >同步</el-button
            >
            <el-button
              size="small"
              link
              type="primary"
              :icon="List"
              @click="handleLog(scope.row)"
              v-hasPermi="['system:combinesync:log']"
              >日志</el-button
            >
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              v-hasPermi="[
                'system:combinesync:query',
                'system:combinesync:execute',
                'system:combinesync:edit',
                'system:combinesync:remove',
              ]"
            >
              <el-button link type="primary" aria-label="更多操作"
                >更多<ChevronDown class="bi-row-actions-chevron"
              /></el-button>
              <template #dropdown
                ><el-dropdown-menu>
                  <el-dropdown-item
                    :icon="ArrowRightLeft"
                    v-hasPermi="['system:combinesync:query']"
                    v-if="scope.row.syncStatus !== '0'"
                    @click="showSyncProgress(scope.row.combineId)"
                    >进度</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="Circle"
                    v-hasPermi="['system:combinesync:query']"
                    @click="handleProgress(scope.row)"
                    >时间戳</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="RotateCw"
                    v-hasPermi="['system:combinesync:execute']"
                    :disabled="scope.row.syncStatus === '1'"
                    @click="handleRefreshCombineProgress(scope.row)"
                    >刷新TS</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="SquareCode"
                    v-hasPermi="['system:combinesync:edit']"
                    @click="handleUpdate(scope.row)"
                    >修改</el-dropdown-item
                  >
                  <el-dropdown-item
                    :icon="Eraser"
                    v-hasPermi="['system:combinesync:remove']"
                    @click="handleDelete(scope.row)"
                    class="bi-row-action-delete"
                    :divided="
                      $auth.hasPermiOr([
                        'system:combinesync:query',
                        'system:combinesync:execute',
                        'system:combinesync:edit',
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
      size="950px"
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
            <el-form-item label="任务名称" prop="combineName">
              <el-input
                v-model="form.combineName"
                placeholder="请输入任务名称"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务编码" prop="combineCode">
              <el-input
                v-model="form.combineCode"
                placeholder="可选，用于标识任务"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-section">源数据配置</div>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="源数据源" prop="sourceDatasourceId">
              <el-select
                v-model="form.sourceDatasourceId"
                placeholder="请选择源数据源"
                filterable
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
              <el-select
                v-model="form.targetDatasourceId"
                placeholder="请选择目标数据源"
                filterable
                style="width: 100%"
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
          <el-col :span="12">
            <el-form-item label="目标库名" prop="targetTable">
              <el-input
                v-model="form.targetTable"
                placeholder="如: tidb_combine"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="来源列名" prop="sourceColumn">
          <el-input
            v-model="form.sourceColumn"
            placeholder="默认: source_table"
            style="width: 300px"
          >
            <template #append>
              <el-tooltip
                content="目标表中用于标识数据来源的列名，值格式为库名.表名"
                placement="top"
              >
                <CircleHelp class="inline-icon" />
              </el-tooltip>
            </template>
          </el-input>
        </el-form-item>

        <div class="form-section">同步配置</div>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="同步模式" prop="syncMode">
              <el-radio-group
                v-model="form.syncMode"
                @change="handleSyncModeChange"
              >
                <el-radio value="0">初始化同步</el-radio>
                <el-radio value="1">增量同步</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.syncMode === '0'">
            <el-form-item label="初始化清空" prop="isClear">
              <el-switch
                v-model="form.isClear"
                active-value="1"
                inactive-value="0"
              />
              <span
                style="
                  margin-left: 8px;
                  color: hsl(var(--muted-foreground));
                  font-size: 12px;
                "
                >清空目标表后重新导入</span
              >
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="时间戳字段" prop="timestampField">
              <el-input
                v-model="form.timestampField"
                placeholder="默认: time_stamp"
              >
                <template #append>
                  <el-tooltip
                    content="增量同步时依据此字段判断变化记录"
                    placement="top"
                  >
                    <CircleHelp class="inline-icon" />
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
              <el-checkbox v-model="syncOperations.update"
                >UPDATE(INSERT覆盖)</el-checkbox
              >
              <el-checkbox v-model="syncOperations.delete">DELETE</el-checkbox>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="批次大小" prop="batchSize">
              <el-input-number
                v-model="form.batchSize"
                :min="100"
                :max="1000000"
                :step="100"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-section">其他配置</div>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="Cron表达式" prop="cronExpression">
              <el-input
                v-model="form.cronExpression"
                placeholder="定时任务，可选"
              />
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
      title="合并预览 - 匹配的分库分表列表"
      v-model="previewOpen"
      width="750px"
      append-to-body
    >
      <div v-loading="previewLoading">
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
            min-width="150"
            show-overflow-tooltip
          />
          <el-table-column
            prop="sourceTag"
            label="来源标识"
            min-width="200"
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
    <el-dialog
      title="合并同步日志"
      v-model="logOpen"
      width="1200px"
      append-to-body
    >
      <el-form
        :model="logQueryParams"
        ref="logQueryForm"
        size="small"
        :inline="true"
      >
        <el-form-item label="源库名" prop="sourceDatabase">
          <el-input
            v-model="logQueryParams.sourceDatabase"
            placeholder="请输入源库名"
            clearable
            style="width: 180px"
            @keyup.enter="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="源表名" prop="sourceTable">
          <el-input
            v-model="logQueryParams.sourceTable"
            placeholder="请输入源表名"
            clearable
            style="width: 180px"
            @keyup.enter="handleLogQuery"
          />
        </el-form-item>
        <el-form-item label="同步模式" prop="syncMode">
          <el-select
            v-model="logQueryParams.syncMode"
            placeholder="请选择"
            clearable
            style="width: 120px"
          >
            <el-option label="初始化" value="0" />
            <el-option label="增量" value="1" />
          </el-select>
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

      <el-table
        :data="logList"
        max-height="400"
        border
        size="small"
        v-loading="logLoading"
      >
        <el-table-column
          label="源库"
          align="center"
          prop="sourceDatabase"
          min-width="120"
          show-overflow-tooltip
        />
        <el-table-column
          label="源表(库名.表名)"
          align="center"
          prop="sourceTable"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          label="目标库名"
          align="center"
          prop="targetTable"
          min-width="120"
          show-overflow-tooltip
        />
        <el-table-column label="模式" align="center" prop="syncMode" width="70">
          <template #default="scope">
            <el-tag
              :type="scope.row.syncMode === '0' ? 'info' : 'primary'"
              size="small"
            >
              {{ scope.row.syncMode === '0' ? '初始化' : '增量' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          align="center"
          prop="syncAction"
          width="90"
        />
        <el-table-column
          label="总数"
          align="center"
          prop="totalCount"
          width="70"
        />
        <el-table-column
          label="成功"
          align="center"
          prop="successCount"
          width="70"
        >
          <template #default="scope">
            <span style="color: #67c23a">{{ scope.row.successCount }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="失败"
          align="center"
          prop="failCount"
          width="70"
        >
          <template #default="scope">
            <span
              :style="{ color: scope.row.failCount > 0 ? '#F56C6C' : '' }"
              >{{ scope.row.failCount }}</span
            >
          </template>
        </el-table-column>
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
      </el-table>

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
      title="表级同步时间戳"
      v-model="progressOpen"
      width="820px"
      append-to-body
    >
      <el-table
        :data="progressList"
        max-height="500"
        border
        size="small"
        v-loading="progressLoading"
      >
        <el-table-column
          label="来源(库名.表名)"
          align="center"
          prop="sourceTable"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column
          label="最后时间戳(ms)"
          align="center"
          prop="lastSyncValue"
          width="140"
        />
        <el-table-column label="最后时间" align="center" min-width="180">
          <template #default="scope">
            <span v-if="scope.row.lastSyncValue">{{
              parseTime(
                Number(scope.row.lastSyncValue),
                '{y}-{m}-{d} {h}:{i}:{s}',
              )
            }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" align="center" min-width="160">
          <template #default="scope">
            <span v-if="scope.row.updateTime">{{
              parseTime(scope.row.updateTime, '{y}-{m}-{d} {h}:{i}:{s}')
            }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer
        ><div class="dialog-footer">
          <el-button
            type="primary"
            :icon="RotateCw"
            :loading="progressRefreshLoading"
            @click="
              handleRefreshCombineProgress({ combineId: progressCombineId })
            "
            v-hasPermi="['system:combinesync:execute']"
            >刷新最大时间戳</el-button
          >
          <el-button @click="progressOpen = false">关 闭</el-button>
        </div></template
      >
    </el-dialog>
    <el-dialog
      title="合并同步进度"
      v-model="syncProgressOpen"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="true"
      @close="handleSyncProgressClose"
      append-to-body
    >
      <div v-loading="syncProgressLoading">
        <el-alert
          :title="getSyncProgressTitle()"
          :type="getSyncProgressAlertType()"
          :closable="false"
          show-icon
          style="margin-bottom: 20px"
        >
        </el-alert>

        <div v-if="syncProgressData.syncProgress" style="padding: 0 20px">
          <div style="margin-bottom: 15px">
            <span style="font-weight: bold">表进度：</span>
            <span
              >{{ syncProgressInfo.current || 0
              }}{{
                syncProgressInfo.total > 0 ? ' / ' + syncProgressInfo.total : ''
              }}</span
            >
          </div>
          <el-progress
            :percentage="syncProgressInfo.percent || 0"
            :status="getSyncProgressStatus()"
            :stroke-width="20"
            :show-text="false"
          >
          </el-progress>
          <div
            style="
              margin-top: 15px;
              color: hsl(var(--muted-foreground));
              font-size: 13px;
            "
          >
            {{ syncProgressInfo.message || '准备开始同步...' }}
          </div>
        </div>

        <div
          v-if="
            syncProgressData.lastSyncResult &&
            (syncProgressData.syncStatus === '2' ||
              syncProgressData.syncStatus === '3' ||
              syncProgressData.syncStatus === '4')
          "
          style="margin-top: 20px; padding: 0 20px"
        >
          <div class="form-section">同步结果</div>
          <div
            style="
              background: hsl(var(--muted));
              padding: 10px;
              border-radius: 4px;
              font-size: 13px;
            "
          >
            {{ formatSyncResult(syncProgressData.lastSyncResult) }}
          </div>
        </div>

        <div
          v-if="syncProgressData.lastSyncTime"
          style="
            margin-top: 15px;
            padding: 0 20px;
            font-size: 13px;
            color: hsl(var(--muted-foreground));
          "
        >
          最后同步时间：{{
            parseTime(syncProgressData.lastSyncTime, '{y}-{m}-{d} {h}:{i}:{s}')
          }}
        </div>
      </div>

      <template #footer
        ><div class="dialog-footer">
          <el-button
            @click="handleRefreshSyncProgress"
            :icon="RotateCw"
            :loading="syncProgressLoading"
            >刷新进度</el-button
          >
          <el-button
            @click="handleStopSync"
            type="danger"
            v-if="syncProgressData.syncStatus === '1'"
            :loading="stopping"
            >停止同步</el-button
          >
          <el-button
            @click="handleViewLogFromProgress"
            type="primary"
            v-if="
              syncProgressData.syncStatus === '2' ||
              syncProgressData.syncStatus === '3' ||
              syncProgressData.syncStatus === '4'
            "
            >查看日志</el-button
          >
          <el-button @click="handleSyncProgressClose">关 闭</el-button>
        </div></template
      >
    </el-dialog>
  </BiTablePage>
</template>

<script>
import { Page } from '@vben/common-ui';
import {
  ChevronDown,
  ArrowRightLeft,
  Circle,
  CircleHelp,
  Eraser,
  Eye,
  List,
  Plus,
  RotateCw,
  Search,
  SquareCode,
} from '@vben/icons';
import {
  listCombineSync,
  getCombineSync,
  delCombineSync,
  addCombineSync,
  updateCombineSync,
  executeCombineSync,
  previewCombineSync,
  listCombineLog,
  getCombineProgress,
  refreshCombineProgress,
  getCombineSyncProgress,
  stopCombineSync,
} from '#/legacy/api/system/combinesync';
import { getDatasourceOptions } from '#/legacy/api/system/datasource';

export default {
  components: {
    ChevronDown,
    ArrowRightLeft,
    Circle,
    CircleHelp,
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
      ArrowRightLeft,
      Circle,
      CircleHelp,
      Eraser,
      Eye,
      List,
      Plus,
      RotateCw,
      Search,
      SquareCode,
    };
  },
  name: 'CombineSync',
  data() {
    return {
      pageActive: true,
      activeProgressTaskId: null,
      progressRequestVersion: 0,
      progressRequestTaskId: null,
      advanced: false,
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      combineSyncList: [],
      datasourceOptions: [],
      title: '',
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
        syncResult: null,
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
        delete: false,
      },
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        combineName: null,
        sourceDatasourceId: null,
        syncMode: null,
        syncStatus: null,
        status: null,
      },
      form: {},
      rules: {
        combineName: [
          { required: true, message: '任务名称不能为空', trigger: 'blur' },
        ],
        sourceDatasourceId: [
          { required: true, message: '请选择源数据源', trigger: 'change' },
        ],
        sourceTable: [
          { required: true, message: '源表名不能为空', trigger: 'blur' },
        ],
        targetDatasourceId: [
          { required: true, message: '请选择目标数据源', trigger: 'change' },
        ],
        targetTable: [
          { required: true, message: '目标表名不能为空', trigger: 'blur' },
        ],
      },
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
          total: parsed.total > 0 ? parsed.total : 0,
          percent: parsed.percent || 0,
          message: parsed.message || '',
        };
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
      if (this.syncProgressOpen && this.syncProgressData.combineId) {
        this.fetchSyncProgress(this.syncProgressData.combineId);
        this.startSyncProgressPolling(this.syncProgressData.combineId);
      }
    }
  },
  deactivated() {
    this.pageActive = false;
    this.progressRequestVersion += 1;
    this.progressRequestTaskId = null;
    this.stopSyncProgressPolling();
  },
  beforeUnmount() {
    this.pageActive = false;
    this.progressRequestVersion += 1;
    this.progressRequestTaskId = null;
    this.stopSyncProgressPolling();
  },
  methods: {
    getList() {
      if (!this.pageActive) return;
      this.loading = true;
      listCombineSync(this.queryParams)
        .then((response) => {
          this.combineSyncList = response.rows || [];
          this.total = response.total;
          this.loading = false;
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false;
        });
    },
    getDatasourceOptions() {
      getDatasourceOptions().then((response) => {
        // 合并同步功能只支持 StarRocks 类型的数据源
        this.datasourceOptions = (response.data || []).filter(
          (item) =>
            item.datasourceType &&
            item.datasourceType.toUpperCase() === 'STARROCKS',
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
        sourceColumn: 'source_table',
        syncMode: '0',
        timestampField: 'time_stamp',
        syncInsert: '1',
        syncUpdate: '1',
        syncDelete: '0',
        batchSize: 500000,
        isClear: '1',
        cronExpression: null,
        status: '0',
        remark: null,
      };
      this.syncOperations = {
        insert: true,
        update: true,
        delete: false,
      };
      this.$nextTick(() => this.$refs.form?.clearValidate());
    },
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    resetQuery() {
      this.resetForm('queryForm');
      this.handleQuery();
    },
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.combineId);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = '新增合并同步任务';
    },
    handleUpdate(row) {
      this.reset();
      const combineId = row.combineId || this.ids;
      getCombineSync(combineId).then((response) => {
        this.form = response.data;
        this.syncOperations.insert = this.form.syncInsert === '1';
        this.syncOperations.update = this.form.syncUpdate === '1';
        this.syncOperations.delete = this.form.syncDelete === '1';
        this.open = true;
        this.title = '修改合并同步任务';
      });
    },
    submitForm() {
      if (!this.$refs.form) return;
      this.$refs.form.validate((valid) => {
        if (valid) {
          // 合并同步允许源和目标使用同一个数据源（从不同库/表合并到同一数据源的不同库/表）
          this.form.syncInsert = this.syncOperations.insert ? '1' : '0';
          this.form.syncUpdate = this.syncOperations.update ? '1' : '0';
          this.form.syncDelete = this.syncOperations.delete ? '1' : '0';

          if (this.form.combineId != null) {
            updateCombineSync(this.form).then((response) => {
              this.$modal.msgSuccess('修改成功');
              this.open = false;
              this.getList();
            });
          } else {
            addCombineSync(this.form).then((response) => {
              this.$modal.msgSuccess('新增成功');
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    handleDelete(row) {
      const combineIds = row.combineId || this.ids;
      this.$modal
        .confirm('是否确认删除合并任务编号为"' + combineIds + '"的数据项？')
        .then(function () {
          return delCombineSync(combineIds);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
    handleStatusChange(row) {
      let text = row.status === '0' ? '启用' : '停用';
      this.$modal
        .confirm('确认要"' + text + '""' + row.combineName + '"任务吗？')
        .then(function () {
          return updateCombineSync({
            combineId: row.combineId,
            status: row.status,
          });
        })
        .then(() => {
          this.$modal.msgSuccess(text + '成功');
        })
        .catch(function () {
          row.status = row.status === '0' ? '1' : '0';
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
      previewCombineSync(row.combineId)
        .then((response) => {
          const result = response.data || {};
          this.previewTables = result.tables || [];
          this.previewSchemaCount = result.schemaCount || 0;
          this.previewTableCount = result.tableCount || 0;
          this.previewLoading = false;
        })
        .catch(() => {
          this.previewLoading = false;
        });
    },
    handleExecute(row) {
      this.$modal
        .confirm('确认要执行合并同步任务"' + row.combineName + '"吗？')
        .then(() => {
          return executeCombineSync(row.combineId);
        })
        .then((response) => {
          this.$modal.msgSuccess('合并同步任务已启动，请查看进度对话框');
          this.getList();
          this.showSyncProgress(row.combineId);
        })
        .catch(() => {});
    },
    handleLog(row) {
      this.logQueryParams = {
        pageNum: 1,
        pageSize: 10,
        combineId: row.combineId,
        sourceDatabase: null,
        sourceTable: null,
        syncMode: null,
        syncResult: null,
      };
      this.logOpen = true;
      this.getLogList();
    },
    getLogList() {
      this.logLoading = true;
      this.logList = [];
      listCombineLog(this.logQueryParams)
        .then((response) => {
          this.logList = response.rows || [];
          this.logTotal = response.total || 0;
          this.logLoading = false;
        })
        .catch(() => {
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
      getCombineProgress(row.combineId)
        .then((response) => {
          this.progressList = response.data || [];
          this.progressLoading = false;
        })
        .catch(() => {
          this.progressLoading = false;
        });
    },
    handleRefreshCombineProgress(row) {
      const combineId = row && row.combineId;
      if (!combineId) {
        return;
      }
      this.$modal
        .confirm('确认从目标表MAX时间戳刷新当前任务的表级增量进度吗？')
        .then(() => {
          this.progressRefreshLoading = true;
          return refreshCombineProgress(combineId);
        })
        .then((response) => {
          const data = response.data || {};
          this.$modal.msgSuccess(data.message || response.msg || '刷新成功');
          this.progressRefreshLoading = false;
          this.getList();
          if (this.progressOpen) {
            this.progressLoading = true;
            this.progressCombineId = combineId;
            return getCombineProgress(combineId)
              .then((progressResponse) => {
                this.progressList = progressResponse.data || [];
                this.progressLoading = false;
              })
              .catch(() => {
                this.progressLoading = false;
              });
          }
        })
        .catch(() => {
          this.progressRefreshLoading = false;
        });
    },
    showSyncProgress(combineId) {
      this.activeProgressTaskId = combineId;
      this.progressRequestVersion += 1;
      this.progressRequestTaskId = null;
      this.syncProgressOpen = true;
      this.syncProgressLoading = true;
      this.syncProgressData = { combineId };
      this.fetchSyncProgress(combineId);
      this.startSyncProgressPolling(combineId);
    },
    fetchSyncProgress(combineId) {
      if (
        !this.pageActive ||
        !this.syncProgressOpen ||
        this.activeProgressTaskId !== combineId ||
        this.progressRequestTaskId === combineId
      )
        return;
      const requestVersion = ++this.progressRequestVersion;
      this.progressRequestTaskId = combineId;
      getCombineSyncProgress(combineId)
        .then((response) => {
          if (
            requestVersion !== this.progressRequestVersion ||
            !this.pageActive ||
            !this.syncProgressOpen
          )
            return;
          this.syncProgressData = response.data || { combineId };
          this.syncProgressLoading = false;
          if (this.syncProgressData.syncStatus !== '1') {
            this.stopSyncProgressPolling();
          }
        })
        .catch(() => {
          if (requestVersion !== this.progressRequestVersion) return;
          this.syncProgressLoading = false;
        })
        .finally(() => {
          if (requestVersion === this.progressRequestVersion)
            this.progressRequestTaskId = null;
        });
    },
    startSyncProgressPolling(combineId) {
      if (!this.pageActive) return;
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
        0: '准备同步',
        1: '正在同步中...',
        2: '同步成功',
        3: '同步失败',
        4: '已停止',
      };
      return statusMap[this.syncProgressData.syncStatus] || '同步状态未知';
    },
    getSyncProgressAlertType() {
      const typeMap = {
        0: 'info',
        1: 'warning',
        2: 'success',
        3: 'error',
        4: 'warning',
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
      this.$modal
        .confirm('确认要停止当前合并同步任务吗？')
        .then(() => {
          this.stopping = true;
          return stopCombineSync(this.syncProgressData.combineId);
        })
        .then(() => {
          this.$modal.msgSuccess('任务已停止');
          this.stopping = false;
          this.handleRefreshSyncProgress();
          this.getList();
        })
        .catch(() => {
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
      this.activeProgressTaskId = null;
      this.progressRequestVersion += 1;
      this.progressRequestTaskId = null;
      this.stopSyncProgressPolling();
      this.syncProgressOpen = false;
      this.getList();
    },
    handleViewLogFromProgress() {
      if (!this.syncProgressData.combineId) return;
      const row = this.combineSyncList.find(
        (item) => item.combineId === this.syncProgressData.combineId,
      );
      if (row) {
        this.handleLog(row);
      }
    },
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

:deep(.el-textarea__inner::placeholder) {
  font-size: 10px;
}
:deep(.el-progress .el-progress__text) {
  display: none !important;
}
.form-section {
  font-size: 14px;
  font-weight: 600;
  color: hsl(var(--foreground));
  line-height: 1;
  padding: 8px 0 8px 10px;
  margin: 18px 0 14px 0;
  border-left: 3px solid #409eff;
  background-color: hsl(var(--muted));
}
</style>
