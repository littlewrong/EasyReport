<script lang="ts" setup>
import type { SystemUserApi } from '#/api/system/user';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { createUser, updateUser } from '#/api/system/user';

import { useFormSchema } from '../data';

const emits = defineEmits(['success']);

const formData = ref<SystemUserApi.SystemUser>();
const id = ref<number>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(true),
  showDefaultActions: false,
});

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) return;
    const values =
      await formApi.getValues<Omit<SystemUserApi.SystemUser, 'id'>>();
    // 编辑时密码留空表示不修改
    if (!values.password) {
      delete values.password;
    }
    drawerApi.lock();
    (id.value ? updateUser(id.value, values) : createUser(values))
      .then(() => {
        emits('success');
        drawerApi.close();
      })
      .catch(() => {
        drawerApi.unlock();
      });
  },

  onOpenChange(isOpen) {
    if (isOpen) {
      const data = drawerApi.getData<SystemUserApi.SystemUser>();
      formApi.resetForm();

      if (data && data.id) {
        formData.value = data;
        id.value = data.id;
        formApi.updateSchema(useFormSchema(false));
        formApi.setValues({ ...data, password: undefined });
      } else {
        formData.value = undefined;
        id.value = undefined;
        formApi.updateSchema(useFormSchema(true));
      }
    }
  },
});

const getDrawerTitle = computed(() => {
  return formData.value?.id ? '编辑用户' : '新增用户';
});
</script>
<template>
  <Drawer :title="getDrawerTitle">
    <Form />
  </Drawer>
</template>
