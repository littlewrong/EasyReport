import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridColumns } from '#/adapter/vxe-table';
import type { BiDatasourceApi } from '#/api';

import { getDbConnectorOptions } from '#/api';

export const STATUS_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
];

const connectorSelectProps = {
  api: getDbConnectorOptions,
  class: 'w-full',
  clearable: true,
  labelField: 'name',
  valueField: 'id',
};

const DEFAULT_PYTHON_CODE = `def main(connector, query, params):
    keyword = params.get("keyword") or ""
    rows = query("select :keyword as keyword", {"keyword": keyword})
    return {"keyword": keyword, "items": rows}`;

export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'name',
      label: '数据源名称',
      rules: 'required',
    },
    {
      component: 'ApiSelect',
      componentProps: connectorSelectProps,
      fieldName: 'connectorId',
      label: 'DB连接器',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        autosize: { maxRows: 32, minRows: 24 },
        inputStyle: {
          fontFamily:
            'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace',
          lineHeight: '1.55',
          minHeight: '520px',
        },
        rows: 24,
        type: 'textarea',
      },
      defaultValue: DEFAULT_PYTHON_CODE,
      fieldName: 'pythonCode',
      label: 'Python代码',
      rules: 'required',
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
      component: 'ApiSelect',
      componentProps: connectorSelectProps,
      fieldName: 'connectorId',
      label: 'DB连接器',
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

export function useColumns<T = BiDatasourceApi.PythonDatasource>(
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
      field: 'connectorName',
      minWidth: 160,
      title: 'DB连接器',
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
            show: (row: BiDatasourceApi.PythonDatasource) => !!row.publicPath,
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
