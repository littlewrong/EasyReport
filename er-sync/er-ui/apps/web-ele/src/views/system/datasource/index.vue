<template>
  <BiTablePage
    title="数据源列表"
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
        <el-form-item label="数据源名称" prop="datasourceName">
          <el-input
            v-model="queryParams.datasourceName"
            placeholder="请输入数据源名称"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="数据源类型" prop="datasourceType">
          <el-select
            v-model="queryParams.datasourceType"
            placeholder="请选择数据源类型"
            clearable
          >
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="POSTGRESQL" />
            <el-option label="金仓 KingbaseES" value="KINGBASE" />
            <el-option label="达梦 DM" value="DM" />
            <el-option label="TiDB" value="TIDB" />
            <el-option label="StarRocks" value="STARROCKS" />
            <el-option label="Oracle" value="ORACLE" />
            <el-option label="SQLServer" value="SQLSERVER" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机地址" prop="host">
          <el-input
            v-model="queryParams.host"
            placeholder="请输入主机地址"
            clearable
            @keyup.enter="handleQuery"
          />
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
        <el-form-item>
          <el-button :icon="RotateCw" @click="resetQuery">重置</el-button>
          <el-button type="primary" :icon="Search" @click="handleQuery"
            >搜索</el-button
          >
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
            v-hasPermi="['system:datasource:add']"
            >新增</el-button
          >
        </el-col>
        <el-col class="toolbar-action">
          <el-button
            :icon="SquareCode"
            :disabled="single"
            @click="handleUpdate"
            v-hasPermi="['system:datasource:edit']"
            >修改</el-button
          >
        </el-col>
        <el-col class="toolbar-action">
          <el-button
            type="danger"
            :icon="Eraser"
            :disabled="multiple"
            @click="handleDelete"
            v-hasPermi="['system:datasource:remove']"
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
      :data="datasourceList"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column
        label="ID"
        align="center"
        prop="datasourceId"
        width="60"
      />
      <el-table-column
        label="数据源名称"
        align="center"
        prop="datasourceName"
        min-width="100"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        label="数据源类型"
        align="center"
        prop="datasourceType"
        width="110"
      >
        <template #default="scope">
          <el-tag
            :type="getDatasourceTypeTag(scope.row.datasourceType)"
            class="ds-type-tag"
            >{{ scope.row.datasourceType }}</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column
        label="主机地址"
        align="center"
        prop="host"
        min-width="120"
        :show-overflow-tooltip="true"
      />
      <el-table-column label="端口" align="center" prop="port" width="70" />
      <el-table-column
        label="数据库/模式"
        align="center"
        prop="databaseName"
        min-width="100"
        :show-overflow-tooltip="true"
      />
      <el-table-column
        label="用户名"
        align="center"
        prop="username"
        width="80"
        :show-overflow-tooltip="true"
      />
      <el-table-column label="状态" align="center" prop="status" width="60">
        <template #default="scope">
          <dict-tag
            :options="dict.type.sys_normal_disable"
            :value="scope.row.status"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="测试状态"
        align="center"
        prop="testStatus"
        width="90"
      >
        <template #default="scope">
          <el-tag
            v-if="scope.row.testStatus === '0'"
            type="success"
            size="small"
            >连接成功</el-tag
          >
          <el-tag
            v-else-if="scope.row.testStatus === '1'"
            type="danger"
            size="small"
            >连接失败</el-tag
          >
          <el-tag v-else type="info" size="small">未测试</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        align="center"
        prop="createTime"
        width="155"
      >
        <template #default="scope">
          <span>{{
            parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}')
          }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        align="center"
        class-name="small-padding fixed-width"
        width="260"
        fixed="right"
      >
        <template #default="scope">
          <el-button
            size="small"
            link
            type="primary"
            :icon="Link2"
            @click="handleTest(scope.row)"
            v-hasPermi="['system:datasource:test']"
            style="margin-right: 8px"
            >测试</el-button
          >
          <el-button
            size="small"
            link
            type="primary"
            :icon="SquareCode"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:datasource:edit']"
            style="margin-right: 8px"
            >修改</el-button
          >
          <el-button
            size="small"
            link
            type="primary"
            :icon="Copy"
            @click="handleCopy(scope.row)"
            v-hasPermi="['system:datasource:add']"
            style="margin-right: 8px"
            >复制</el-button
          >
          <el-button
            size="small"
            link
            type="primary"
            :icon="Eraser"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:datasource:remove']"
            >删除</el-button
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
      :title="title"
      v-model="open"
      size="700px"
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
        <el-row>
          <el-col :span="12">
            <el-form-item label="数据源名称" prop="datasourceName">
              <el-input
                v-model="form.datasourceName"
                placeholder="请输入数据源名称"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据源类型" prop="datasourceType">
              <el-select
                v-model="form.datasourceType"
                placeholder="请选择数据源类型"
                style="width: 100%"
                @change="handleDatasourceTypeChange"
              >
                <el-option label="MySQL" value="MYSQL" />
                <el-option label="PostgreSQL" value="POSTGRESQL" />
                <el-option label="金仓 KingbaseES" value="KINGBASE" />
                <el-option label="达梦 DM" value="DM" />
                <el-option label="TiDB" value="TIDB" />
                <el-option label="StarRocks" value="STARROCKS" />
                <el-option label="Oracle" value="ORACLE" />
                <el-option label="SQLServer" value="SQLSERVER" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="16">
            <el-form-item label="主机地址" prop="host">
              <el-input
                v-model="form.host"
                placeholder="请输入主机地址"
                @input="generateJdbcUrl"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="端口" prop="port" label-width="50px">
              <el-input
                v-model.number="form.port"
                placeholder="端口"
                @input="generateJdbcUrl"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item
              :label="form.datasourceType === 'DM' ? '默认模式' : '数据库名'"
              :required="form.datasourceType !== 'DM'"
              prop="databaseName"
            >
              <el-input
                v-model="form.databaseName"
                :placeholder="
                  form.datasourceType === 'DM'
                    ? '可选，留空使用登录用户的默认模式'
                    : '请输入数据库名'
                "
                @input="generateJdbcUrl"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="form.status">
                <el-radio value="0">正常</el-radio>
                <el-radio value="1">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入用户名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                show-password
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="24">
            <el-form-item label="驱动类名" prop="driverClass">
              <el-input
                v-model="form.driverClass"
                placeholder="根据数据源类型自动生成"
              >
                <template #append
                  ><el-button @click="generateDriverClass"
                    >自动生成</el-button
                  ></template
                >
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="24">
            <el-form-item label="JDBC连接URL" prop="jdbcUrl">
              <el-input
                v-model="form.jdbcUrl"
                type="textarea"
                :rows="2"
                placeholder="根据上方配置自动生成"
              >
              </el-input>
              <el-button
                link
                type="primary"
                size="small"
                @click="generateJdbcUrl"
                style="margin-top: 5px"
              >
                <RotateCw class="inline-icon" /> 重新生成URL
              </el-button>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="24">
            <el-form-item label="连接参数" prop="connectionParams">
              <el-input
                v-model="form.connectionParams"
                type="textarea"
                :rows="2"
                placeholder='JSON格式，如：{"ssl":"true"}'
                @input="handleConnectionParamsChange"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="24">
            <el-form-item label="备注" prop="remark">
              <el-input
                v-model="form.remark"
                type="textarea"
                placeholder="请输入内容"
              />
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
  </BiTablePage>
</template>

<script>
import { Page } from '@vben/common-ui';
import {
  Copy,
  Eraser,
  Link2,
  Plus,
  RotateCw,
  Search,
  SquareCode,
} from '@vben/icons';
import {
  listDatasource,
  getDatasource,
  delDatasource,
  addDatasource,
  updateDatasource,
  testConnection,
} from '#/legacy/api/system/datasource';

export default {
  components: { Copy, Eraser, Link2, Plus, RotateCw, Search, SquareCode },
  setup() {
    return { Copy, Eraser, Link2, Plus, RotateCw, Search, SquareCode };
  },
  name: 'Datasource',
  dicts: ['sys_normal_disable'],
  data() {
    return {
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      datasourceList: [],
      title: '',
      open: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        datasourceName: null,
        datasourceType: null,
        host: null,
        status: null,
      },
      form: {},
      rules: {
        datasourceName: [
          { required: true, message: '数据源名称不能为空', trigger: 'blur' },
        ],
        datasourceType: [
          { required: true, message: '数据源类型不能为空', trigger: 'change' },
        ],
        host: [
          { required: true, message: '主机地址不能为空', trigger: 'blur' },
        ],
        port: [
          { required: true, message: '端口号不能为空', trigger: 'blur' },
          { type: 'number', message: '端口号必须为数字', trigger: 'blur' },
        ],
        databaseName: [
          {
            validator: (rule, value, callback) => {
              if (
                this.form.datasourceType === 'DM' &&
                value &&
                String(value).includes('&')
              ) {
                callback(new Error('达梦默认模式不能包含连接参数分隔符 &'));
              } else if (
                this.form.datasourceType === 'DM' ||
                (value && String(value).trim())
              )
                callback();
              else callback(new Error('数据库名不能为空'));
            },
            trigger: 'blur',
          },
        ],
        username: [
          { required: true, message: '用户名不能为空', trigger: 'blur' },
        ],
      },
    };
  },
  created() {
    this.getList();
  },
  methods: {
    getList() {
      this.loading = true;
      listDatasource(this.queryParams)
        .then((response) => {
          this.datasourceList = response.rows || [];
          this.total = response.total;
          this.loading = false;
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false;
        });
    },
    cancel() {
      this.open = false;
      this.reset();
    },
    reset() {
      this.form = {
        datasourceId: null,
        datasourceName: null,
        datasourceType: null,
        host: null,
        port: null,
        databaseName: null,
        username: null,
        password: null,
        driverClass: null,
        jdbcUrl: null,
        connectionParams: null,
        status: '0',
        remark: null,
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
      this.ids = selection.map((item) => item.datasourceId);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = '添加数据源';
    },
    handleUpdate(row) {
      this.reset();
      const datasourceId = row.datasourceId || this.ids;
      getDatasource(datasourceId).then((response) => {
        this.form = response.data;
        this.open = true;
        this.title = '修改数据源';
      });
    },
    handleCopy(row) {
      this.reset();
      const datasourceId = row.datasourceId;
      getDatasource(datasourceId).then((response) => {
        this.form = response.data;
        this.form.datasourceId = null;
        this.form.datasourceName = (this.form.datasourceName || '') + '_copy';
        this.open = true;
        this.title = '复制数据源';
      });
    },
    submitForm() {
      if (!this.$refs.form) return;
      this.$refs.form.validate((valid) => {
        if (valid) {
          if (this.form.datasourceId != null) {
            updateDatasource(this.form).then((response) => {
              this.$modal.msgSuccess('修改成功');
              this.open = false;
              this.getList();
            });
          } else {
            addDatasource(this.form).then((response) => {
              this.$modal.msgSuccess('新增成功');
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    handleDelete(row) {
      const datasourceIds = row.datasourceId || this.ids;
      this.$modal
        .confirm('是否确认删除数据源编号为"' + datasourceIds + '"的数据项？')
        .then(function () {
          return delDatasource(datasourceIds);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
    handleTest(row) {
      this.$modal.loading('正在测试连接...');
      testConnection(row)
        .then((response) => {
          this.$modal.closeLoading();
          this.$modal.msgSuccess(response.msg);
          this.getList();
        })
        .catch(() => {
          this.$modal.closeLoading();
        });
    },
    getDatasourceTypeTag(type) {
      const typeMap = {
        MYSQL: 'primary',
        POSTGRESQL: 'success',
        KINGBASE: 'success',
        DM: 'warning',
        TIDB: 'warning',
        STARROCKS: 'danger',
        ORACLE: 'info',
        SQLSERVER: '',
      };
      return typeMap[type] || '';
    },
    /** 数据源类型变更时的处理 */
    handleDatasourceTypeChange(type) {
      // 设置默认端口
      const defaultPorts = {
        MYSQL: 3306,
        POSTGRESQL: 5432,
        KINGBASE: 54321,
        DM: 5236,
        TIDB: 4000,
        STARROCKS: 9030,
        ORACLE: 1521,
        SQLSERVER: 1433,
      };
      if (!this.form.port) {
        this.form.port = defaultPorts[type] || 3306;
      }
      // 自动生成驱动类名和JDBC URL
      this.generateDriverClass();
      this.generateJdbcUrl();
    },
    /** 生成驱动类名 */
    generateDriverClass() {
      const driverMap = {
        MYSQL: 'com.mysql.cj.jdbc.Driver',
        POSTGRESQL: 'org.postgresql.Driver',
        KINGBASE: 'com.kingbase8.Driver',
        DM: 'dm.jdbc.driver.DmDriver',
        TIDB: 'com.mysql.cj.jdbc.Driver',
        STARROCKS: 'com.mysql.cj.jdbc.Driver',
        ORACLE: 'oracle.jdbc.OracleDriver',
        SQLSERVER: 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
      };
      if (this.form.datasourceType) {
        this.form.driverClass = driverMap[this.form.datasourceType] || '';
      }
    },
    handleConnectionParamsChange() {
      if (['KINGBASE', 'DM'].includes(this.form.datasourceType))
        this.generateJdbcUrl();
    },
    /** 生成JDBC URL */
    generateJdbcUrl() {
      const type = this.form.datasourceType;
      const host = this.form.host;
      const port = this.form.port;
      const db = this.form.databaseName;

      if (!type || !host || !port || (!db && type !== 'DM')) {
        return;
      }

      let url = '';
      switch (type) {
        case 'MYSQL':
          // MySQL JDBC URL格式
          url = `jdbc:mysql://${host}:${port}/${db}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8`;
          break;
        case 'POSTGRESQL':
          // PostgreSQL JDBC URL格式
          url = `jdbc:postgresql://${host}:${port}/${db}?stringtype=unspecified`;
          break;
        case 'KINGBASE': {
          const params = { stringtype: 'unspecified' };
          const raw = (this.form.connectionParams || '').trim();
          let query;
          if (raw.startsWith('{')) {
            try {
              Object.assign(params, JSON.parse(raw));
            } catch (e) {
              return; // 参数编辑中保留上一次有效 URL。
            }
          } else if (raw) {
            query = raw.replace(/^\?/, '');
            if (!/(^|&)stringtype=/i.test(query))
              query += '&stringtype=unspecified';
          }
          if (!query) {
            query = Object.keys(params)
              .filter((key) => params[key] != null)
              .map(
                (key) =>
                  `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`,
              )
              .join('&');
          }
          url = `jdbc:kingbase8://${host}:${port}/${db}?${query}`;
          break;
        }
        case 'DM': {
          const params = { convertToTimestamp: 'true' };
          if (db) params.schema = db;
          const raw = (this.form.connectionParams || '').trim();
          let query;
          if (raw.startsWith('{')) {
            try {
              Object.assign(params, JSON.parse(raw));
            } catch (e) {
              return;
            }
          } else if (raw) {
            query = raw.replace(/^\?/, '');
            Object.keys(params).forEach((key) => {
              if (!new RegExp(`(^|&)${key}=`, 'i').test(query)) {
                if (String(params[key]).includes('&')) return;
                query += `${query ? '&' : ''}${key}=${params[key]}`;
              }
            });
          }
          if (!query) {
            if (
              Object.keys(params).some(
                (key) =>
                  !/^[A-Za-z][A-Za-z0-9_]*$/.test(key) ||
                  String(params[key]).includes('&'),
              )
            ) {
              this.form.jdbcUrl = '';
              return;
            }
            query = Object.keys(params)
              .filter((key) => params[key] != null)
              .map((key) => `${key}=${params[key]}`)
              .join('&');
          }
          url = `jdbc:dm://${host}:${port}${query ? '?' + query : ''}`;
          break;
        }
        case 'TIDB':
          // TiDB 兼容 MySQL 协议，使用类似 MySQL 的 URL
          url = `jdbc:mysql://${host}:${port}/${db}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8`;
          break;
        case 'STARROCKS':
          // StarRocks 使用 MySQL 协议连接
          url = `jdbc:mysql://${host}:${port}/${db}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8`;
          break;
        case 'ORACLE':
          // Oracle JDBC URL格式 (使用 Service Name)
          url = `jdbc:oracle:thin:@//${host}:${port}/${db}`;
          break;
        case 'SQLSERVER':
          // SQL Server JDBC URL格式
          url = `jdbc:sqlserver://${host}:${port};DatabaseName=${db};encrypt=false;trustServerCertificate=true;integratedSecurity=false`;
          break;
        default:
          url = '';
      }

      this.form.jdbcUrl = url;
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

/* 数据源类型标签居中显示 */
.ds-type-tag {
  display: inline-block;
  min-width: 80px;
  text-align: center;
}
</style>
