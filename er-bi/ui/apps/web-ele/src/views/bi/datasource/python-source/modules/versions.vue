<script lang="ts" setup>
import type { BiDatasourceApi } from '#/api';

import { ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { ElButton, ElMessage, ElMessageBox, ElTable, ElTableColumn } from 'element-plus';

import {
  getPythonDatasourceVersions,
  restorePythonDatasourceVersion,
} from '#/api';

const emits = defineEmits(['success']);

const datasource = ref<BiDatasourceApi.PythonDatasource>();
const versions = ref<BiDatasourceApi.DatasourceVersion[]>([]);
const loading = ref(false);

const [Drawer, drawerApi] = useVbenDrawer({
  onOpenChange(isOpen) {
    if (!isOpen) return;
    datasource.value = drawerApi.getData<BiDatasourceApi.PythonDatasource>();
    loadVersions();
  },
});

async function loadVersions() {
  if (!datasource.value?.id) return;
  loading.value = true;
  try {
    versions.value = await getPythonDatasourceVersions(datasource.value.id);
  } finally {
    loading.value = false;
  }
}

async function onRestore(row: BiDatasourceApi.DatasourceVersion) {
  if (!datasource.value?.id) return;
  try {
    await ElMessageBox.confirm(
      `确认恢复到 V${row.versionNo} 吗？当前配置会生成新的历史版本。`,
      '恢复版本',
      { type: 'warning' },
    );
    await restorePythonDatasourceVersion(datasource.value.id, row.id);
    ElMessage.success('恢复成功');
    emits('success');
    loadVersions();
  } catch {
    // 用户取消或请求失败
  }
}
</script>

<template>
  <Drawer :title="`${datasource?.name || 'Python数据源'} - 版本`" class="w-[860px]">
    <ElTable v-loading="loading" :data="versions" border>
      <ElTableColumn prop="versionNo" label="版本" width="90">
        <template #default="{ row }"> V{{ row.versionNo }} </template>
      </ElTableColumn>
      <ElTableColumn prop="createTime" label="创建时间" width="180" />
      <ElTableColumn prop="connectorName" label="DB连接器" width="160" />
      <ElTableColumn prop="pythonCode" label="Python代码" min-width="320" show-overflow-tooltip />
      <ElTableColumn label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <ElButton
            link
            type="primary"
            size="small"
            @click="onRestore(row as BiDatasourceApi.DatasourceVersion)"
          >
            恢复
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </Drawer>
</template>
