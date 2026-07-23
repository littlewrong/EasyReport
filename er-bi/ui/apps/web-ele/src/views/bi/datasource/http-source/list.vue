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
  deleteHttpDatasource,
  getHttpDatasourceList,
  updateHttpDatasource,
} from '#/api';

import { useColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';
import Versions from './modules/versions.vue';

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [VersionsDrawer, versionsDrawerApi] = useVbenDrawer({
  connectedComponent: Versions,
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
          return await getHttpDatasourceList({
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
  } as VxeTableGridOptions<BiDatasourceApi.HttpDatasource>,
});

function toPayload(row: BiDatasourceApi.HttpDatasource) {
  return {
    name: row.name,
    remark: row.remark,
    requestBody: row.requestBody,
    requestHeaders: row.requestHeaders,
    requestMethod: row.requestMethod,
    requestUrl: row.requestUrl,
    status: row.status,
  };
}

function getFullUrl(row: BiDatasourceApi.HttpDatasource) {
  if (!row.publicPath) return '';
  if (/^https?:\/\//.test(row.publicPath)) return row.publicPath;
  return `${window.location.origin}${row.publicPath}`;
}

function onActionClick(e: OnActionClickParams<BiDatasourceApi.HttpDatasource>) {
  switch (e.code) {
    case 'copy': {
      onCopy(e.row);
      break;
    }
    case 'delete': {
      onDelete(e.row);
      break;
    }
    case 'edit': {
      onEdit(e.row);
      break;
    }
    case 'versions': {
      onVersions(e.row);
      break;
    }
  }
}

async function onStatusChange(
  newStatus: number,
  row: BiDatasourceApi.HttpDatasource,
) {
  const statusText = newStatus === 1 ? '启用' : '禁用';
  try {
    await ElMessageBox.confirm(
      `你要将 ${row.name} 的状态切换为【${statusText}】吗？`,
      '切换状态',
      { type: 'warning' },
    );
    await updateHttpDatasource(row.id, {
      ...toPayload(row),
      status: newStatus as BiDatasourceApi.DatasourceStatus,
    });
    return true;
  } catch {
    return false;
  }
}

function onEdit(row: BiDatasourceApi.HttpDatasource) {
  formDrawerApi.setData(row).open();
}

async function onDelete(row: BiDatasourceApi.HttpDatasource) {
  try {
    await deleteHttpDatasource(row.id);
    ElMessage.success(`${row.name} 删除成功`);
    onRefresh();
  } catch {
    // 错误提示已由请求拦截器统一处理
  }
}

async function onCopy(row: BiDatasourceApi.HttpDatasource) {
  const url = getFullUrl(row);
  if (!url) return;
  await navigator.clipboard.writeText(url);
  ElMessage.success('访问地址已复制');
}

function onVersions(row: BiDatasourceApi.HttpDatasource) {
  versionsDrawerApi.setData(row).open();
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
    <VersionsDrawer @success="onRefresh" />
    <Grid table-title="HTTP数据源">
      <template #toolbar-tools>
        <ElButton type="primary" @click="onCreate">
          <Plus class="size-5" />
          新增HTTP数据源
        </ElButton>
      </template>
    </Grid>
  </Page>
</template>
