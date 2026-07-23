import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridColumns } from '#/adapter/vxe-table';
import type { BiDatasourceApi } from '#/api';

export const DB_TYPE_OPTIONS = [
  { label: 'MySQL', value: 'MySQL' },
  { label: 'TiDB', value: 'TiDB' },
  { label: 'StarRocks', value: 'StarRocks' },
  { label: 'PostgreSQL', value: 'PostgreSQL' },
  { label: 'SQL Server', value: 'SQLServer' },
];

export const STATUS_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
];

export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'name',
      label: '连接器名称',
      rules: 'required',
    },
    {
      component: 'Select',
      componentProps: {
        class: 'w-full',
        options: DB_TYPE_OPTIONS,
      },
      defaultValue: 'MySQL',
      fieldName: 'dbType',
      label: '数据库类型',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'host',
      label: '主机地址',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        controlsPosition: 'right',
        max: 65_535,
        min: 1,
      },
      defaultValue: 3306,
      fieldName: 'port',
      label: '端口',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'database',
      label: '数据库',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'username',
      label: '用户名',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        showPassword: true,
        type: 'password',
      },
      fieldName: 'password',
      label: '密码',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        isButton: true,
        options: STATUS_OPTIONS,
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
      fieldName: 'name',
      label: '连接器名称',
    },
    {
      component: 'Select',
      componentProps: {
        class: 'w-full',
        clearable: true,
        options: DB_TYPE_OPTIONS,
      },
      fieldName: 'dbType',
      label: '数据库类型',
    },
    {
      component: 'Select',
      componentProps: {
        class: 'w-full',
        clearable: true,
        options: STATUS_OPTIONS,
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

export function useColumns<T = BiDatasourceApi.DbConnector>(
  onActionClick: OnActionClickFn<T>,
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
): VxeTableGridColumns {
  return [
    {
      field: 'name',
      minWidth: 180,
      title: '连接器名称',
    },
    {
      cellRender: {
        name: 'CellTag',
        options: DB_TYPE_OPTIONS,
      },
      field: 'dbType',
      title: '类型',
      width: 130,
    },
    {
      field: 'host',
      minWidth: 220,
      title: '连接地址',
      formatter: ({ row }) => `${row.host}:${row.port}/${row.database}`,
    },
    {
      field: 'username',
      title: '用户名',
      width: 140,
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
      minWidth: 160,
      title: '备注',
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 180,
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'name',
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          'edit',
          { code: 'test', text: '测试', type: 'success' },
          'delete',
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: '操作',
      width: 180,
    },
  ];
}
