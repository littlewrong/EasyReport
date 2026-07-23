<script lang="ts" setup>
import type { Recordable } from '@vben/types';

import type { VbenFormSchema } from '#/adapter/form';
import type { SystemMenuApi } from '#/api/system/menu';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { useVbenForm, z } from '#/adapter/form';
import {
  createMenu,
  getMenuList,
  isMenuNameExists,
  isMenuPathExists,
  updateMenu,
} from '#/api/system/menu';
import { $t } from '#/locales';
import { componentKeys } from '#/router/routes';

import { getMenuTypeOptions } from '../data';

const emit = defineEmits<{
  success: [];
}>();
const formData = ref<SystemMenuApi.SystemMenu>();

/** 父级菜单树，标题做国际化转换便于阅读 */
async function getMenuTree() {
  const menus = await getMenuList();
  const translate = (
    items: SystemMenuApi.SystemMenu[],
  ): SystemMenuApi.SystemMenu[] =>
    items
      .filter((item) => item.type !== 'button')
      .map((item) => ({
        ...item,
        label: $t(item.meta?.title ?? item.name),
        children: item.children ? translate(item.children) : undefined,
      }));
  return translate(menus);
}

const schema: VbenFormSchema[] = [
  {
    component: 'RadioGroup',
    componentProps: {
      isButton: true,
      options: getMenuTypeOptions(),
    },
    defaultValue: 'menu',
    fieldName: 'type',
    formItemClass: 'col-span-2',
    label: '菜单类型',
  },
  {
    component: 'Input',
    fieldName: 'name',
    label: '路由名称',
    rules: z
      .string()
      .min(2, '路由名称至少2个字符')
      .max(30, '路由名称最多30个字符')
      .refine(
        async (value: string) => {
          return !(await isMenuNameExists(value, formData.value?.id));
        },
        (value) => ({ message: `路由名称 ${value} 已存在` }),
      ),
  },
  {
    component: 'ApiTreeSelect',
    componentProps: {
      api: getMenuTree,
      checkStrictly: true,
      childrenField: 'children',
      class: 'w-full',
      clearable: true,
      filterable: true,
      labelField: 'label',
      valueField: 'id',
    },
    fieldName: 'pid',
    label: '父级菜单',
  },
  {
    component: 'Input',
    fieldName: 'meta.title',
    label: '菜单标题',
    rules: 'required',
  },
  {
    component: 'Input',
    dependencies: {
      show: (values) => {
        return ['catalog', 'embedded', 'menu'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'path',
    label: '路由路径',
    rules: z
      .string()
      .min(2, '路由路径至少2个字符')
      .max(100, '路由路径最多100个字符')
      .refine((value: string) => {
        return value.startsWith('/');
      }, '路由路径必须以 / 开头')
      .refine(
        async (value: string) => {
          return !(await isMenuPathExists(value, formData.value?.id));
        },
        (value) => ({ message: `路由路径 ${value} 已存在` }),
      ),
  },
  {
    component: 'IconPicker',
    componentProps: {
      prefix: 'carbon',
    },
    dependencies: {
      show: (values) => {
        return ['catalog', 'embedded', 'link', 'menu'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.icon',
    label: '图标',
  },
  {
    component: 'Select',
    componentProps: {
      allowCreate: true,
      class: 'w-full',
      clearable: true,
      filterable: true,
      options: componentKeys.map((v) => ({ label: v, value: v })),
    },
    dependencies: {
      rules: (values) => {
        return values.type === 'menu' ? 'required' : null;
      },
      show: (values) => {
        return values.type === 'menu';
      },
      triggerFields: ['type'],
    },
    fieldName: 'component',
    label: '页面组件',
  },
  {
    component: 'Input',
    dependencies: {
      show: (values) => {
        return ['embedded', 'link'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'linkSrc',
    label: '链接地址',
    rules: z.string().url('请输入合法的URL'),
  },
  {
    component: 'Input',
    dependencies: {
      rules: (values) => {
        return values.type === 'button' ? 'required' : null;
      },
      show: (values) => {
        return ['button', 'catalog', 'embedded', 'menu'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'authCode',
    label: '权限标识',
  },
  {
    component: 'InputNumber',
    dependencies: {
      show: (values) => {
        return values.type !== 'button';
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.order',
    label: '排序',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      isButton: true,
      options: [
        { label: '启用', value: 1 },
        { label: '禁用', value: 0 },
      ],
    },
    defaultValue: 1,
    fieldName: 'status',
    label: '状态',
  },
  {
    component: 'Divider',
    dependencies: {
      show: (values) => {
        return !['button', 'link'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'divider1',
    formItemClass: 'col-span-2 pb-0',
    hideLabel: true,
    renderComponentContent() {
      return {
        default: () => '其它设置',
      };
    },
  },
  {
    component: 'Checkbox',
    dependencies: {
      show: (values) => {
        return ['menu'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.keepAlive',
    renderComponentContent() {
      return {
        default: () => '缓存页面',
      };
    },
  },
  {
    component: 'Checkbox',
    dependencies: {
      show: (values) => {
        return ['embedded', 'menu'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.affixTab',
    renderComponentContent() {
      return {
        default: () => '固定在标签栏',
      };
    },
  },
  {
    component: 'Checkbox',
    dependencies: {
      show: (values) => {
        return !['button'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.hideInMenu',
    renderComponentContent() {
      return {
        default: () => '在菜单中隐藏',
      };
    },
  },
  {
    component: 'Checkbox',
    dependencies: {
      show: (values) => {
        return ['catalog', 'menu'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.hideChildrenInMenu',
    renderComponentContent() {
      return {
        default: () => '在菜单中隐藏下级',
      };
    },
  },
  {
    component: 'Checkbox',
    dependencies: {
      show: (values) => {
        return !['button', 'link'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.hideInBreadcrumb',
    renderComponentContent() {
      return {
        default: () => '在面包屑中隐藏',
      };
    },
  },
  {
    component: 'Checkbox',
    dependencies: {
      show: (values) => {
        return !['button', 'link'].includes(values.type);
      },
      triggerFields: ['type'],
    },
    fieldName: 'meta.hideInTab',
    renderComponentContent() {
      return {
        default: () => '在标签栏中隐藏',
      };
    },
  },
];

const [Form, formApi] = useVbenForm({
  commonConfig: {
    formItemClass: 'col-span-2 md:col-span-1',
  },
  schema,
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const [Drawer, drawerApi] = useVbenDrawer({
  onConfirm: onSubmit,
  onOpenChange(isOpen) {
    if (isOpen) {
      const data = drawerApi.getData<SystemMenuApi.SystemMenu>();
      if (data?.type === 'link') {
        data.linkSrc = data.meta?.link;
      } else if (data?.type === 'embedded') {
        data.linkSrc = data.meta?.iframeSrc;
      }
      formApi.resetForm();
      if (data && Object.keys(data).length > 0) {
        formData.value = data;
        formApi.setValues(data);
      } else {
        formData.value = undefined;
      }
    }
  },
});

async function onSubmit() {
  const { valid } = await formApi.validate();
  if (!valid) return;
  drawerApi.lock();
  const data = await formApi.getValues<
    Omit<SystemMenuApi.SystemMenu, 'children' | 'id'> & {
      linkSrc?: string;
    }
  >();
  if (data.type === 'link') {
    data.meta = { ...data.meta, link: data.linkSrc };
  } else if (data.type === 'embedded') {
    data.meta = { ...data.meta, iframeSrc: data.linkSrc };
  }
  delete data.linkSrc;
  try {
    await (formData.value?.id
      ? updateMenu(formData.value.id, data as Recordable<any>)
      : createMenu(data as Recordable<any>));
    drawerApi.close();
    emit('success');
  } finally {
    drawerApi.unlock();
  }
}

const getDrawerTitle = computed(() =>
  formData.value?.id ? '编辑菜单' : '新增菜单',
);
</script>
<template>
  <Drawer class="w-full max-w-[800px]" :title="getDrawerTitle">
    <Form class="mx-4" />
  </Drawer>
</template>
