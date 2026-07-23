import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridColumns } from '#/adapter/vxe-table';
import type { SystemUserApi } from '#/api';

import { getRoleList } from '#/api';

/** 获取全部启用的角色作为下拉选项 */
async function getRoleOptions() {
  const { items } = await getRoleList({ page: 1, pageSize: 500, status: 1 });
  return items;
}

export function useFormSchema(isCreate: boolean): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'username',
      label: '用户名',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: isCreate ? '不填默认为 123456' : '不填则不修改密码',
        showPassword: true,
        type: 'password',
      },
      fieldName: 'password',
      label: '密码',
    },
    {
      component: 'Input',
      fieldName: 'realName',
      label: '姓名',
      rules: 'required',
    },
    {
      component: 'ApiSelect',
      componentProps: {
        api: getRoleOptions,
        class: 'w-full',
        clearable: true,
        labelField: 'name',
        multiple: true,
        valueField: 'id',
      },
      fieldName: 'roles',
      label: '角色',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '登录后跳转的页面，如 /workspace',
      },
      fieldName: 'homePath',
      label: '首页路径',
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
      component: 'Input',
      componentProps: {
        rows: 3,
        type: 'textarea',
      },
      fieldName: 'remark',
      label: '备注',
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'username',
      label: '用户名',
    },
    {
      component: 'Input',
      fieldName: 'realName',
      label: '姓名',
    },
    {
      component: 'Select',
      componentProps: {
        class: 'w-full',
        clearable: true,
        options: [
          { label: '启用', value: 1 },
          { label: '禁用', value: 0 },
        ],
      },
      fieldName: 'status',
      label: '状态',
    },
    {
      component: 'DatePicker',
      componentProps: {
        type: 'daterange',
      },
      fieldName: 'createTime',
      label: '创建时间',
    },
  ];
}

export function useColumns<T = SystemUserApi.SystemUser>(
  onActionClick: OnActionClickFn<T>,
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
): VxeTableGridColumns {
  return [
    {
      field: 'id',
      title: '用户ID',
      width: 100,
    },
    {
      field: 'username',
      title: '用户名',
      width: 160,
    },
    {
      field: 'realName',
      title: '姓名',
      width: 160,
    },
    {
      field: 'roleNames',
      formatter: ({ cellValue }) => {
        return Array.isArray(cellValue) ? cellValue.join('、') : '';
      },
      title: '角色',
      width: 200,
    },
    {
      cellRender: {
        attrs: { beforeChange: onStatusChange },
        name: onStatusChange ? 'CellSwitch' : 'CellTag',
      },
      field: 'status',
      title: '状态',
      width: 100,
    },
    {
      field: 'remark',
      minWidth: 100,
      title: '备注',
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 200,
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'username',
          onClick: onActionClick,
        },
        name: 'CellOperation',
      },
      field: 'operation',
      fixed: 'right',
      title: '操作',
      width: 130,
    },
  ];
}
