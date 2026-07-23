<script lang="ts" setup>
import type { BiDatasourceApi } from '#/api';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { ElMessage } from 'element-plus';

import { useVbenForm } from '#/adapter/form';
import {
  createSqlDatasource,
  testSqlDatasourceConfig,
  updateSqlDatasource,
} from '#/api';

import TestRunner from '../../components/TestRunner.vue';
import { useFormSchema } from '../data';

const emits = defineEmits(['success']);

const formData = ref<BiDatasourceApi.SqlDatasource>();
const id = ref<number>();
const testLoading = ref(false);
const testParamsText = ref('{}');
const testResult = ref<BiDatasourceApi.TestResult>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(),
  showDefaultActions: false,
});

function parseTestParams() {
  const text = testParamsText.value.trim();
  if (!text) return {};
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed;
    }
  } catch {
    // fall through
  }
  ElMessage.error('测试参数必须是 JSON 对象');
  return null;
}

async function getPayload() {
  const { valid } = await formApi.validate();
  if (!valid) return null;
  return await formApi.getValues<
    Omit<BiDatasourceApi.SqlDatasource, 'connectorName' | 'id' | 'publicPath'>
  >();
}

async function onRunTest() {
  const payload = await getPayload();
  if (!payload) return;
  const params = parseTestParams();
  if (params === null) return;
  testLoading.value = true;
  try {
    testResult.value = await testSqlDatasourceConfig({ ...payload, params });
  } finally {
    testLoading.value = false;
  }
}

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const values = await getPayload();
    if (!values) return;
    drawerApi.lock();
    (id.value ? updateSqlDatasource(id.value, values) : createSqlDatasource(values))
      .then(() => {
        emits('success');
        drawerApi.close();
      })
      .catch(() => {
        drawerApi.unlock();
      });
  },

  onOpenChange(isOpen) {
    if (!isOpen) return;
    const data = drawerApi.getData<BiDatasourceApi.SqlDatasource>();
    formApi.resetForm();
    testParamsText.value = '{}';
    testResult.value = undefined;

    if (data && data.id) {
      formData.value = data;
      id.value = data.id;
      formApi.setValues(data);
    } else {
      formData.value = undefined;
      id.value = undefined;
      formApi.setValues({
        status: 1,
      });
    }
  },
});

const drawerTitle = computed(() => {
  return formData.value?.id ? '编辑SQL数据源' : '新增SQL数据源';
});
</script>

<template>
  <Drawer :title="drawerTitle" class="w-[1180px]">
    <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_440px]">
      <Form />
      <div class="xl:sticky xl:top-0">
        <TestRunner
          v-model:params-text="testParamsText"
          :loading="testLoading"
          :params-rows="9"
          :result="testResult"
          :result-rows="20"
          @run="onRunTest"
        />
      </div>
    </div>
  </Drawer>
</template>
