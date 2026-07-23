import { requestClient } from '#/api/request';

export namespace BiProjectApi {
  export interface ProjectListItem {
    createTime?: null | string;
    id: number;
    indexImage?: null | string;
    projectName: string;
    remarks?: null | string;
    state: -1 | 1 | number;
  }

  export interface ProjectListResult {
    count: number;
    list: ProjectListItem[];
  }

  export interface ProjectCreateParams {
    indexImage?: string;
    projectName: string;
    remarks?: string;
  }

  export interface ProjectEditParams {
    id: number | string;
    indexImage?: string;
    projectName?: string;
    remarks?: string;
  }

  export interface ProjectPublishParams {
    id: number | string;
    state: -1 | 1;
  }
}

async function createBiProject(data: BiProjectApi.ProjectCreateParams) {
  return requestClient.post<number>('/bi/project/create', data);
}

async function deleteBiProject(id: number | string) {
  return requestClient.delete<boolean>(`/bi/project/${id}`);
}

async function editBiProject(data: BiProjectApi.ProjectEditParams) {
  return requestClient.post<boolean>('/bi/project/edit', data);
}

async function getBiProjectList(params: { limit?: number; page?: number }) {
  return requestClient.get<BiProjectApi.ProjectListResult>('/bi/project/list', {
    params,
  });
}

async function publishBiProject(data: BiProjectApi.ProjectPublishParams) {
  return requestClient.post<boolean>('/bi/project/publish', data);
}

export {
  createBiProject,
  deleteBiProject,
  editBiProject,
  getBiProjectList,
  publishBiProject,
};
