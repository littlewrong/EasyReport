<script lang="ts" setup>
import type { BiDatasourceApi } from '#/api';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { createDbConnector, updateDbConnector } from '#/api';

import { useFormSchema } from '../data';

const emits = defineEmits(['success']);

const formData = ref<BiDatasourceApi.DbConnector>();
const id = ref<number>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(),
  showDefaultActions: false,
});

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) return;
    const values =
      await formApi.getValues<Omit<BiDatasourceApi.DbConnector, 'id'>>();
    drawerApi.lock();
    (id.value ? updateDbConnector(id.value, values) : createDbConnector(values))
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
    const data = drawerApi.getData<BiDatasourceApi.DbConnector>();
    formApi.resetForm();

    if (data && data.id) {
      formData.value = data;
      id.value = data.id;
      formApi.setValues(data);
    } else {
      formData.value = undefined;
      id.value = undefined;
      formApi.setValues({
        dbType: 'MySQL',
        port: 3306,
        status: 1,
      });
    }
  },
});

const drawerTitle = computed(() => {
  return formData.value?.id ? '编辑DB连接器' : '新增DB连接器';
});
</script>

<template>
  <Drawer :title="drawerTitle">
    <Form />
  </Drawer>
</template>
