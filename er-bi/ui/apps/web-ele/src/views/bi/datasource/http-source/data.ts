import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridColumns } from '#/adapter/vxe-table';
import type { BiDatasourceApi } from '#/api';

export const METHOD_OPTIONS = [
  { label: 'GET', value: 'GET' },
  { label: 'POST', value: 'POST' },
  { label: 'PUT', value: 'PUT' },
  { label: 'PATCH', value: 'PATCH' },
  { label: 'DELETE', value: 'DELETE' },
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
      label: '数据源名称',
      rules: 'required',
    },
    {
      component: 'Select',
      componentProps: {
        class: 'w-full',
        options: METHOD_OPTIONS,
      },
      defaultValue: 'GET',
      fieldName: 'requestMethod',
      label: '请求方法',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'requestUrl',
      label: '请求地址',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        autosize: { maxRows: 16, minRows: 8 },
        inputStyle: {
          fontFamily:
            'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace',
          lineHeight: '1.55',
          minHeight: '180px',
        },
        placeholder: '{"Authorization":"Bearer token"}',
        rows: 8,
        type: 'textarea',
      },
      fieldName: 'requestHeadersText',
      label: '请求头',
    },
    {
      component: 'Input',
      componentProps: {
        autosize: { maxRows: 34, minRows: 20 },
        inputStyle: {
          fontFamily:
            'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace',
          lineHeight: '1.55',
          minHeight: '460px',
        },
        rows: 20,
        type: 'textarea',
      },
      fieldName: 'requestBody',
      label: '请求体',
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
      label: '数据源名称',
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

export function useColumns<T = BiDatasourceApi.HttpDatasource>(
  onActionClick: OnActionClickFn<T>,
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
): VxeTableGridColumns {
  return [
    {
      field: 'name',
      minWidth: 180,
      title: '数据源名称',
    },
    {
      cellRender: {
        name: 'CellTag',
        options: METHOD_OPTIONS,
      },
      field: 'requestMethod',
      title: '方法',
      width: 110,
    },
    {
      field: 'requestUrl',
      minWidth: 260,
      title: '请求地址',
    },
    {
      field: 'publicPath',
      minWidth: 260,
      title: '访问地址',
      formatter: ({ cellValue }) =>
        cellValue ? `${window.location.origin}${cellValue}` : '-',
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
          {
            code: 'copy',
            show: (row: BiDatasourceApi.HttpDatasource) => !!row.publicPath,
            text: '复制地址',
            type: 'primary',
          },
          { code: 'versions', text: '版本', type: 'primary' },
          'delete',
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: '操作',
      width: 230,
    },
  ];
}
