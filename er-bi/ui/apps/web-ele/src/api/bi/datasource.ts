import type { Recordable } from '@vben/types';

import {
  httpDatasourceRequestClient as requestClient,
  pythonDatasourceRequestClient,
  requestClient as mainRequestClient,
  sqlDatasourceRequestClient,
} from '#/api/request';

export namespace BiDatasourceApi {
  export type DatasourceStatus = 0 | 1;
  export type DbType =
    | 'MySQL'
    | 'PostgreSQL'
    | 'SQLServer'
    | 'StarRocks'
    | 'TiDB';

  export interface DbConnector {
    database: string;
    dbType: DbType;
    extra?: null | Recordable<any>;
    host: string;
    hasPassword?: boolean;
    id: number;
    name: string;
    password?: null | string;
    port: number;
    remark?: null | string;
    status: DatasourceStatus;
    username: string;
  }

  export interface DbConnectorOption {
    database: string;
    dbType: DbType;
    host: string;
    id: number;
    name: string;
  }

  export interface HttpDatasource {
    id: number;
    name: string;
    publicPath?: null | string;
    remark?: null | string;
    requestBody?: null | string;
    requestHeaders?: null | Recordable<string>;
    requestMethod: string;
    requestUrl: string;
    status: DatasourceStatus;
  }

  export interface PythonDatasource {
    connectorId: number;
    connectorName?: null | string;
    endpointKey?: null | string;
    id: number;
    name: string;
    publicPath?: null | string;
    pythonCode: string;
    remark?: null | string;
    state: -1 | 1;
    status: DatasourceStatus;
  }

  export interface SqlDatasource {
    connectorId: number;
    connectorName?: null | string;
    id: number;
    name: string;
    publicPath?: null | string;
    remark?: null | string;
    sqlContent: string;
    status: DatasourceStatus;
  }

  export interface PageResult<T> {
    items: T[];
    total: number;
  }

  export interface TestResult {
    elapsedMs?: number;
    message: string;
    preview?: any;
    rowCount?: number;
    statusCode?: number;
    success: boolean;
  }

  export interface DatasourceVersion {
    connectorName?: null | string;
    createTime?: null | string;
    datasourceId: number;
    id: number;
    name?: null | string;
    requestMethod?: null | string;
    requestUrl?: null | string;
    pythonCode?: null | string;
    snapshot: Recordable<any>;
    sqlContent?: null | string;
    versionNo: number;
  }
}

async function getDbConnectorList(params: Recordable<any>) {
  return mainRequestClient.get<
    BiDatasourceApi.PageResult<BiDatasourceApi.DbConnector>
  >('/bi/datasource/db-connectors/list', { params });
}

async function getDbConnectorOptions() {
  return mainRequestClient.get<BiDatasourceApi.DbConnectorOption[]>(
    '/bi/datasource/db-connectors/options',
  );
}

async function createDbConnector(
  data: Omit<BiDatasourceApi.DbConnector, 'id'>,
) {
  return mainRequestClient.post<number>('/bi/datasource/db-connectors', data);
}

async function updateDbConnector(
  id: BiDatasourceApi.DbConnector['id'],
  data: Partial<Omit<BiDatasourceApi.DbConnector, 'id'>>,
) {
  return mainRequestClient.put<number>(`/bi/datasource/db-connectors/${id}`, data);
}

async function deleteDbConnector(id: BiDatasourceApi.DbConnector['id']) {
  return mainRequestClient.delete<boolean>(`/bi/datasource/db-connectors/${id}`);
}

async function testDbConnector(id: BiDatasourceApi.DbConnector['id']) {
  return mainRequestClient.post<BiDatasourceApi.TestResult>(
    `/bi/datasource/db-connectors/${id}/test`,
  );
}

async function getHttpDatasourceList(params: Recordable<any>) {
  return requestClient.get<
    BiDatasourceApi.PageResult<BiDatasourceApi.HttpDatasource>
  >('/bi/datasource/http-sources/list', { params });
}

async function createHttpDatasource(
  data: Omit<BiDatasourceApi.HttpDatasource, 'id'>,
) {
  return requestClient.post<number>('/bi/datasource/http-sources', data);
}

async function updateHttpDatasource(
  id: BiDatasourceApi.HttpDatasource['id'],
  data: Partial<Omit<BiDatasourceApi.HttpDatasource, 'id'>>,
) {
  return requestClient.put<number>(`/bi/datasource/http-sources/${id}`, data);
}

async function deleteHttpDatasource(id: BiDatasourceApi.HttpDatasource['id']) {
  return requestClient.delete<boolean>(`/bi/datasource/http-sources/${id}`);
}

async function testHttpDatasource(id: BiDatasourceApi.HttpDatasource['id']) {
  return requestClient.post<BiDatasourceApi.TestResult>(
    `/bi/datasource/http-sources/${id}/test`,
  );
}

async function testHttpDatasourceConfig(
  data: Omit<BiDatasourceApi.HttpDatasource, 'id'> & {
    params?: Recordable<any>;
  },
) {
  return requestClient.post<BiDatasourceApi.TestResult>(
    '/bi/datasource/http-sources/test',
    data,
  );
}

async function getHttpDatasourceVersions(id: BiDatasourceApi.HttpDatasource['id']) {
  return requestClient.get<BiDatasourceApi.DatasourceVersion[]>(
    `/bi/datasource/http-sources/${id}/versions`,
  );
}

async function restoreHttpDatasourceVersion(
  id: BiDatasourceApi.HttpDatasource['id'],
  versionId: BiDatasourceApi.DatasourceVersion['id'],
) {
  return requestClient.post<boolean>(
    `/bi/datasource/http-sources/${id}/versions/${versionId}/restore`,
  );
}

async function getSqlDatasourceList(params: Recordable<any>) {
  return sqlDatasourceRequestClient.get<
    BiDatasourceApi.PageResult<BiDatasourceApi.SqlDatasource>
  >('/bi/datasource/sql-sources/list', { params });
}

async function createSqlDatasource(
  data: Omit<BiDatasourceApi.SqlDatasource, 'connectorName' | 'id' | 'publicPath'>,
) {
  return sqlDatasourceRequestClient.post<number>(
    '/bi/datasource/sql-sources',
    data,
  );
}

async function updateSqlDatasource(
  id: BiDatasourceApi.SqlDatasource['id'],
  data: Partial<
    Omit<BiDatasourceApi.SqlDatasource, 'connectorName' | 'id' | 'publicPath'>
  >,
) {
  return sqlDatasourceRequestClient.put<number>(
    `/bi/datasource/sql-sources/${id}`,
    data,
  );
}

async function deleteSqlDatasource(id: BiDatasourceApi.SqlDatasource['id']) {
  return sqlDatasourceRequestClient.delete<boolean>(
    `/bi/datasource/sql-sources/${id}`,
  );
}

async function testSqlDatasource(
  id: BiDatasourceApi.SqlDatasource['id'],
  params?: Recordable<any>,
) {
  return sqlDatasourceRequestClient.post<BiDatasourceApi.TestResult>(
    `/bi/datasource/sql-sources/${id}/test`,
    { params: params || {} },
  );
}

async function testSqlDatasourceConfig(
  data: Omit<
    BiDatasourceApi.SqlDatasource,
    'connectorName' | 'id' | 'publicPath'
  > & {
    params?: Recordable<any>;
  },
) {
  return sqlDatasourceRequestClient.post<BiDatasourceApi.TestResult>(
    '/bi/datasource/sql-sources/test',
    data,
  );
}

async function getSqlDatasourceVersions(id: BiDatasourceApi.SqlDatasource['id']) {
  return sqlDatasourceRequestClient.get<BiDatasourceApi.DatasourceVersion[]>(
    `/bi/datasource/sql-sources/${id}/versions`,
  );
}

async function restoreSqlDatasourceVersion(
  id: BiDatasourceApi.SqlDatasource['id'],
  versionId: BiDatasourceApi.DatasourceVersion['id'],
) {
  return sqlDatasourceRequestClient.post<boolean>(
    `/bi/datasource/sql-sources/${id}/versions/${versionId}/restore`,
  );
}

async function getPythonDatasourceList(params: Recordable<any>) {
  return pythonDatasourceRequestClient.get<
    BiDatasourceApi.PageResult<BiDatasourceApi.PythonDatasource>
  >('/bi/datasource/python-sources/list', { params });
}

async function createPythonDatasource(
  data: Omit<
    BiDatasourceApi.PythonDatasource,
    'connectorName' | 'endpointKey' | 'id' | 'publicPath' | 'state'
  >,
) {
  return pythonDatasourceRequestClient.post<number>(
    '/bi/datasource/python-sources',
    data,
  );
}

async function updatePythonDatasource(
  id: BiDatasourceApi.PythonDatasource['id'],
  data: Partial<
    Omit<
      BiDatasourceApi.PythonDatasource,
      'connectorName' | 'endpointKey' | 'id' | 'publicPath' | 'state'
    >
  >,
) {
  return pythonDatasourceRequestClient.put<number>(
    `/bi/datasource/python-sources/${id}`,
    data,
  );
}

async function deletePythonDatasource(id: BiDatasourceApi.PythonDatasource['id']) {
  return pythonDatasourceRequestClient.delete<boolean>(
    `/bi/datasource/python-sources/${id}`,
  );
}

async function testPythonDatasource(
  id: BiDatasourceApi.PythonDatasource['id'],
  params?: Recordable<any>,
) {
  return pythonDatasourceRequestClient.post<BiDatasourceApi.TestResult>(
    `/bi/datasource/python-sources/${id}/test`,
    { params: params || {} },
  );
}

async function testPythonDatasourceConfig(
  data: Omit<
    BiDatasourceApi.PythonDatasource,
    'connectorName' | 'endpointKey' | 'id' | 'publicPath' | 'state'
  > & {
    params?: Recordable<any>;
  },
) {
  return pythonDatasourceRequestClient.post<BiDatasourceApi.TestResult>(
    '/bi/datasource/python-sources/test',
    data,
  );
}

async function getPythonDatasourceVersions(
  id: BiDatasourceApi.PythonDatasource['id'],
) {
  return pythonDatasourceRequestClient.get<BiDatasourceApi.DatasourceVersion[]>(
    `/bi/datasource/python-sources/${id}/versions`,
  );
}

async function restorePythonDatasourceVersion(
  id: BiDatasourceApi.PythonDatasource['id'],
  versionId: BiDatasourceApi.DatasourceVersion['id'],
) {
  return pythonDatasourceRequestClient.post<boolean>(
    `/bi/datasource/python-sources/${id}/versions/${versionId}/restore`,
  );
}

export {
  createDbConnector,
  createHttpDatasource,
  createPythonDatasource,
  createSqlDatasource,
  deleteDbConnector,
  deleteHttpDatasource,
  deletePythonDatasource,
  deleteSqlDatasource,
  getDbConnectorList,
  getDbConnectorOptions,
  getHttpDatasourceList,
  getHttpDatasourceVersions,
  getPythonDatasourceList,
  getPythonDatasourceVersions,
  getSqlDatasourceList,
  getSqlDatasourceVersions,
  restorePythonDatasourceVersion,
  testDbConnector,
  testHttpDatasource,
  testHttpDatasourceConfig,
  testPythonDatasource,
  testPythonDatasourceConfig,
  testSqlDatasource,
  testSqlDatasourceConfig,
  restoreHttpDatasourceVersion,
  restoreSqlDatasourceVersion,
  updateDbConnector,
  updateHttpDatasource,
  updatePythonDatasource,
  updateSqlDatasource,
};
