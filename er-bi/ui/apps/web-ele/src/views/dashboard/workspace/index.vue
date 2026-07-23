<script lang="ts" setup>
import type { FormInstance, FormRules } from 'element-plus';

import type { BiProjectApi } from '#/api';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  CircleAlert,
  CircleCheckBig,
  Eraser,
  ExternalLink,
  ImagePlus,
  LayoutGrid,
  Plus,
  RotateCw,
  SquareCode,
} from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { openWindow } from '@vben/utils';

import {
  ElButton,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElSkeleton,
  ElSkeletonItem,
  ElTag,
} from 'element-plus';

import {
  createBiProject,
  deleteBiProject,
  editBiProject,
  getBiProjectList,
  publishBiProject,
} from '#/api';

const accessStore = useAccessStore();

const designerUrl =
  import.meta.env.VITE_BI_DESIGNER_URL || 'http://localhost:3020/index.html';

const loading = ref(false);
const projects = ref<BiProjectApi.ProjectListItem[]>([]);
const page = ref(1);
const pageSize = ref(12);
const total = ref(0);

const dialogVisible = ref(false);
const saving = ref(false);
const formRef = ref<FormInstance>();
const editingProject = ref<BiProjectApi.ProjectListItem>();

const form = reactive({
  projectName: '',
  remarks: '',
});

const rules: FormRules = {
  projectName: [
    { message: '请输入大屏名称', required: true, trigger: 'blur' },
  ],
};

const dialogTitle = computed(() =>
  editingProject.value ? '编辑大屏' : '新建大屏',
);

const publishedCount = computed(
  () => projects.value.filter((item) => item.state === 1).length,
);

function buildDesignerUrl(projectId?: number | string) {
  const url = new URL(designerUrl, window.location.origin);
  const token = accessStore.accessToken;
  if (token) url.searchParams.set('token', token);
  if (projectId) url.hash = `/chart/home/${projectId}`;
  return url.toString();
}

function openDesigner(projectId: number | string) {
  openWindow(buildDesignerUrl(projectId), { target: '_blank' });
}

function resetForm() {
  editingProject.value = undefined;
  form.projectName = '';
  form.remarks = '';
  formRef.value?.clearValidate();
}

async function fetchProjects() {
  loading.value = true;
  try {
    const data = await getBiProjectList({
      limit: pageSize.value,
      page: page.value,
    });
    projects.value = data.list ?? [];
    total.value = data.count ?? 0;
  } finally {
    loading.value = false;
  }
}

function onCreate() {
  resetForm();
  form.projectName = '新建大屏';
  dialogVisible.value = true;
}

function onEditMeta(project: BiProjectApi.ProjectListItem) {
  editingProject.value = project;
  form.projectName = project.projectName;
  form.remarks = project.remarks ?? '';
  dialogVisible.value = true;
}

async function submitForm() {
  await formRef.value?.validate();
  saving.value = true;
  try {
    if (editingProject.value) {
      await editBiProject({
        id: editingProject.value.id,
        projectName: form.projectName,
        remarks: form.remarks,
      });
      ElMessage.success('大屏信息已更新');
      dialogVisible.value = false;
      await fetchProjects();
      return;
    }

    const projectId = await createBiProject({
      projectName: form.projectName,
      remarks: form.remarks,
    });
    ElMessage.success('大屏已创建');
    dialogVisible.value = false;
    await fetchProjects();
    openDesigner(projectId);
  } finally {
    saving.value = false;
  }
}

async function onDelete(project: BiProjectApi.ProjectListItem) {
  await ElMessageBox.confirm(
    `确认删除「${project.projectName}」吗？`,
    '删除大屏',
    {
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
      type: 'warning',
    },
  );
  await deleteBiProject(project.id);
  ElMessage.success('大屏已删除');
  if (projects.value.length === 1 && page.value > 1) page.value -= 1;
  await fetchProjects();
}

async function onPublish(project: BiProjectApi.ProjectListItem) {
  const nextState = project.state === 1 ? -1 : 1;
  await publishBiProject({ id: project.id, state: nextState });
  ElMessage.success(nextState === 1 ? '大屏已发布' : '大屏已取消发布');
  await fetchProjects();
}

async function onCommand(command: string, project: BiProjectApi.ProjectListItem) {
  if (command === 'rename') {
    onEditMeta(project);
  } else if (command === 'publish') {
    await onPublish(project);
  } else if (command === 'delete') {
    await onDelete(project);
  }
}

function onPageChange(nextPage: number) {
  page.value = nextPage;
  fetchProjects();
}

function onSizeChange(nextSize: number) {
  pageSize.value = nextSize;
  page.value = 1;
  fetchProjects();
}

onMounted(fetchProjects);
</script>

<template>
  <Page auto-content-height>
    <div class="bi-workspace">
      <header class="workspace-header">
        <div>
          <h1>我的大屏</h1>
          <div class="workspace-meta">
            <span>{{ total }} 个大屏</span>
            <span>本页 {{ publishedCount }} 个已发布</span>
          </div>
        </div>
        <div class="workspace-actions">
          <ElButton :loading="loading" @click="fetchProjects">
            <RotateCw class="size-4" />
            刷新
          </ElButton>
          <ElButton type="primary" @click="onCreate">
            <Plus class="size-4" />
            新建大屏
          </ElButton>
        </div>
      </header>

      <div v-if="loading && projects.length === 0" class="project-grid">
        <div v-for="item in 8" :key="item" class="project-card skeleton-card">
          <ElSkeleton animated>
            <template #template>
              <ElSkeletonItem class="cover-skeleton" variant="image" />
              <ElSkeletonItem class="mt-4" variant="h3" />
              <ElSkeletonItem class="mt-3" variant="text" />
            </template>
          </ElSkeleton>
        </div>
      </div>

      <ElEmpty
        v-else-if="projects.length === 0"
        class="empty-state"
        description="暂无大屏项目"
      >
        <ElButton type="primary" @click="onCreate">
          <Plus class="size-4" />
          新建大屏
        </ElButton>
      </ElEmpty>

      <template v-else>
        <div class="project-grid">
          <article
            v-for="project in projects"
            :key="project.id"
            class="project-card"
          >
            <button
              class="project-cover"
              type="button"
              @click="openDesigner(project.id)"
            >
              <img
                v-if="project.indexImage"
                :alt="project.projectName"
                :src="project.indexImage"
              />
              <div v-else class="project-cover-placeholder">
                <ImagePlus class="size-8" />
              </div>
            </button>

            <div class="project-body">
              <div class="project-title-row">
                <h2 :title="project.projectName">{{ project.projectName }}</h2>
                <ElTag
                  class="project-state-tag"
                  :type="project.state === 1 ? 'success' : 'warning'"
                  effect="plain"
                  size="small"
                >
                  <CircleCheckBig v-if="project.state === 1" class="size-3.5" />
                  <CircleAlert v-else class="size-3.5" />
                  {{ project.state === 1 ? '已发布' : '未发布' }}
                </ElTag>
              </div>
              <p class="project-remarks">
                {{ project.remarks || '未填写备注' }}
              </p>
              <div class="project-footer">
                <span>{{ project.createTime || '-' }}</span>
                <div class="project-tools">
                  <ElButton text type="primary" @click="openDesigner(project.id)">
                    <SquareCode class="size-4" />
                    编辑
                  </ElButton>
                  <ElDropdown
                    trigger="click"
                    @command="(command) => onCommand(command, project)"
                  >
                    <ElButton text>
                      <LayoutGrid class="size-4" />
                      更多
                    </ElButton>
                    <template #dropdown>
                      <ElDropdownMenu>
                        <ElDropdownItem command="rename">
                          <ExternalLink class="mr-2 size-4" />
                          信息
                        </ElDropdownItem>
                        <ElDropdownItem command="publish">
                          <CircleCheckBig class="mr-2 size-4" />
                          {{ project.state === 1 ? '取消发布' : '发布' }}
                        </ElDropdownItem>
                        <ElDropdownItem command="delete" divided>
                          <Eraser class="mr-2 size-4" />
                          删除
                        </ElDropdownItem>
                      </ElDropdownMenu>
                    </template>
                  </ElDropdown>
                </div>
              </div>
            </div>
          </article>
        </div>

        <div class="pagination-row">
          <ElPagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :page-sizes="[12, 24, 48]"
            :total="total"
            background
            layout="total, sizes, prev, pager, next"
            @current-change="onPageChange"
            @size-change="onSizeChange"
          />
        </div>
      </template>
    </div>

    <ElDialog v-model="dialogVisible" :title="dialogTitle" width="420px">
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <ElFormItem label="大屏名称" prop="projectName">
          <ElInput v-model="form.projectName" maxlength="64" />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput
            v-model="form.remarks"
            maxlength="255"
            resize="none"
            :rows="3"
            type="textarea"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton :loading="saving" type="primary" @click="submitForm">
          确定
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.bi-workspace {
  min-height: 100%;
  padding: 20px;
}

.workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 18px;
  border-bottom: 1px solid hsl(var(--border));
}

.workspace-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  line-height: 1.3;
}

.workspace-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 6px;
  color: hsl(var(--muted-foreground));
  font-size: 13px;
}

.workspace-actions,
.project-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.project-card {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
  background: hsl(var(--card));
}

.skeleton-card {
  padding: 12px;
}

.project-cover,
.cover-skeleton {
  width: 100%;
  aspect-ratio: 16 / 9;
}

.project-cover {
  display: block;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-bottom: 1px solid hsl(var(--border));
  background: hsl(var(--muted));
  cursor: pointer;
}

.project-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.project-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: hsl(var(--muted-foreground));
  background:
    linear-gradient(90deg, rgb(148 163 184 / 10%) 1px, transparent 1px),
    linear-gradient(rgb(148 163 184 / 10%) 1px, transparent 1px);
  background-size: 18px 18px;
}

.project-body {
  padding: 14px;
}

.project-title-row,
.project-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.project-title-row h2 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-title-row :deep(.project-state-tag) {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}

.project-title-row :deep(.project-state-tag .el-tag__content) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.project-remarks {
  height: 40px;
  margin: 8px 0 12px;
  overflow: hidden;
  color: hsl(var(--muted-foreground));
  font-size: 13px;
  line-height: 20px;
}

.project-footer {
  color: hsl(var(--muted-foreground));
  font-size: 12px;
}

.project-footer :deep(.el-button) {
  padding-right: 4px;
  padding-left: 4px;
}

.empty-state {
  min-height: 460px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

@media (width <= 640px) {
  .bi-workspace {
    padding: 14px;
  }

  .workspace-header,
  .project-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .workspace-actions {
    width: 100%;
  }

  .workspace-actions :deep(.el-button) {
    flex: 1;
  }
}
</style>
