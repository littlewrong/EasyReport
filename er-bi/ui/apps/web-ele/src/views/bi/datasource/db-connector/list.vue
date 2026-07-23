<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { BiDatasourceApi } from '#/api';

import { Page, useVbenDrawer } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { ElButton, ElMessage, ElMessageBox } from 'element-plus';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDbConnector,
  getDbConnectorList,
  testDbConnector,
  updateDbConnector,
} from '#/api';

import { useColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
    submitOnChange: true,
  },
  gridOptions: {
    columns: useColumns(onActionClick, onStatusChange),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getDbConnectorList({
            page: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: {
      keyField: 'id',
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: true,
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<BiDatasourceApi.DbConnector>,
});

function toPayload(row: BiDatasourceApi.DbConnector) {
  return {
    database: row.database,
    dbType: row.dbType,
    extra: row.extra,
    host: row.host,
    name: row.name,
    password: row.password,
    port: row.port,
    remark: row.remark,
    status: row.status,
    username: row.username,
  };
}

function onActionClick(e: OnActionClickParams<BiDatasourceApi.DbConnector>) {
  switch (e.code) {
    case 'delete': {
      onDelete(e.row);
      break;
    }
    case 'edit': {
      onEdit(e.row);
      break;
    }
    case 'test': {
      onTest(e.row);
      break;
    }
  }
}

async function onStatusChange(
  newStatus: number,
  row: BiDatasourceApi.DbConnector,
) {
  const statusText = newStatus === 1 ? '启用' : '禁用';
  try {
    await ElMessageBox.confirm(
      `你要将 ${row.name} 的状态切换为【${statusText}】吗？`,
      '切换状态',
      { type: 'warning' },
    );
    await updateDbConnector(row.id, {
      ...toPayload(row),
      status: newStatus as BiDatasourceApi.DatasourceStatus,
    });
    return true;
  } catch {
    return false;
  }
}

function onEdit(row: BiDatasourceApi.DbConnector) {
  formDrawerApi.setData(row).open();
}

async function onDelete(row: BiDatasourceApi.DbConnector) {
  try {
    await deleteDbConnector(row.id);
    ElMessage.success(`${row.name} 删除成功`);
    onRefresh();
  } catch {
    // 错误提示已由请求拦截器统一处理
  }
}

async function onTest(row: BiDatasourceApi.DbConnector) {
  const loadingMessage = ElMessage({
    duration: 0,
    message: `正在测试 ${row.name}...`,
    type: 'info',
  });
  try {
    const result = await testDbConnector(row.id);
    ElMessage.success(result.message || '连接成功');
  } catch {
    // 错误提示已由请求拦截器统一处理
  } finally {
    loadingMessage.close();
  }
}

function onRefresh() {
  gridApi.query();
}

function onCreate() {
  formDrawerApi.setData({}).open();
}
</script>

<template>
  <Page auto-content-height>
    <FormDrawer @success="onRefresh" />
    <Grid table-title="DB连接器">
      <template #toolbar-tools>
        <ElButton type="primary" @click="onCreate">
          <Plus class="size-5" />
          新增连接器
        </ElButton>
      </template>
    </Grid>
  </Page>
</template>
