<script lang="ts" setup>
import type { BiDatasourceApi } from '#/api';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { ElMessage } from 'element-plus';

import { useVbenForm } from '#/adapter/form';
import {
  createHttpDatasource,
  testHttpDatasourceConfig,
  updateHttpDatasource,
} from '#/api';

import TestRunner from '../../components/TestRunner.vue';
import { useFormSchema } from '../data';

const emits = defineEmits(['success']);

const formData = ref<BiDatasourceApi.HttpDatasource>();
const id = ref<number>();
const testLoading = ref(false);
const testParamsText = ref('{}');
const testResult = ref<BiDatasourceApi.TestResult>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(),
  showDefaultActions: false,
});

function normalizeHeaders(value?: string) {
  const text = value?.trim();
  if (!text) return undefined;
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed;
    }
  } catch {
    // fall through
  }
  ElMessage.error('请求头必须是 JSON 对象');
  return null;
}

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
  const values = await formApi.getValues<any>();
  const requestHeaders = normalizeHeaders(values.requestHeadersText);
  if (requestHeaders === null) return null;
  return {
    name: values.name,
    remark: values.remark,
    requestBody: values.requestBody,
    requestHeaders,
    requestMethod: values.requestMethod,
    requestUrl: values.requestUrl,
    status: values.status,
  };
}

async function onRunTest() {
  const payload = await getPayload();
  if (!payload) return;
  const params = parseTestParams();
  if (params === null) return;
  testLoading.value = true;
  try {
    testResult.value = await testHttpDatasourceConfig({ ...payload, params });
  } finally {
    testLoading.value = false;
  }
}

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const payload = await getPayload();
    if (!payload) return;

    drawerApi.lock();
    (id.value
      ? updateHttpDatasource(id.value, payload)
      : createHttpDatasource(payload)
    )
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
    const data = drawerApi.getData<BiDatasourceApi.HttpDatasource>();
    formApi.resetForm();
    testParamsText.value = '{}';
    testResult.value = undefined;

    if (data && data.id) {
      formData.value = data;
      id.value = data.id;
      formApi.setValues({
        ...data,
        requestHeadersText: data.requestHeaders
          ? JSON.stringify(data.requestHeaders, null, 2)
          : undefined,
      });
    } else {
      formData.value = undefined;
      id.value = undefined;
      formApi.setValues({
        requestMethod: 'GET',
        status: 1,
      });
    }
  },
});

const drawerTitle = computed(() => {
  return formData.value?.id ? '编辑HTTP数据源' : '新增HTTP数据源';
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
