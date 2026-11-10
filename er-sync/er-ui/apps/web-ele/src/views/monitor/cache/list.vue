<script setup>
import { computed, onMounted, ref } from 'vue';
import { Page } from '@vben/common-ui';
import { Eraser, RotateCw } from '@vben/icons';
import { ElMessage } from 'element-plus';
import {
  listCacheName,
  listCacheKey,
  getCacheValue,
  clearCacheName,
  clearCacheKey,
  clearCacheAll,
} from '#/legacy/api/monitor/cache';

defineOptions({ name: 'CacheList' });
const cacheNames = ref([]),
  cacheKeys = ref([]),
  cacheForm = ref({});
const loading = ref(false),
  keyLoading = ref(false),
  valueLoading = ref(false);
const selectedName = ref(''),
  selectedKey = ref('');
const keyRows = computed(() =>
  cacheKeys.value.map((cacheKey) => ({ cacheKey })),
);
const shortName = (name) => String(name || '').replace(':', '');
const shortKey = (key) => String(key || '').replace(selectedName.value, '');
let keyRequest = 0,
  valueRequest = 0;

async function loadNames() {
  loading.value = true;
  try {
    cacheNames.value = (await listCacheName()).data || [];
  } finally {
    loading.value = false;
  }
}
async function loadKeys(row) {
  const name = row?.cacheName || selectedName.value;
  if (!name) return;
  const request = ++keyRequest;
  selectedName.value = name;
  selectedKey.value = '';
  cacheKeys.value = [];
  cacheForm.value = {};
  valueRequest++;
  keyLoading.value = true;
  try {
    const result = await listCacheKey(name);
    if (request === keyRequest) cacheKeys.value = result.data || [];
  } finally {
    if (request === keyRequest) keyLoading.value = false;
  }
}
async function loadValue(row) {
  const name = selectedName.value,
    key = row.cacheKey;
  const request = ++valueRequest;
  selectedKey.value = key;
  valueLoading.value = true;
  try {
    const result = await getCacheValue(name, key);
    if (request === valueRequest) cacheForm.value = result.data || {};
  } finally {
    if (request === valueRequest) valueLoading.value = false;
  }
}
async function removeName(row) {
  await clearCacheName(row.cacheName);
  ElMessage.success(`缓存名称「${shortName(row.cacheName)}」已清理`);
  if (selectedName.value === row.cacheName) await loadKeys();
}
async function removeKey(row) {
  await clearCacheKey(row.cacheKey);
  ElMessage.success('缓存键名已清理');
  await loadKeys();
}
async function removeAll() {
  await clearCacheAll();
  ElMessage.success('全部缓存已清理');
  cacheKeys.value = [];
  cacheForm.value = {};
  selectedKey.value = '';
  valueRequest++;
  keyRequest++;
  keyLoading.value = false;
  valueLoading.value = false;
  await loadNames();
}
onMounted(loadNames);
</script>

<template>
  <Page auto-content-height>
    <div class="cache-browser">
      <el-card shadow="never" class="cache-panel">
        <template #header>
          <div class="panel-header">
            <h3>缓存列表</h3>
            <el-button
              :icon="RotateCw"
              circle
              :loading="loading"
              aria-label="刷新缓存列表"
              @click="loadNames"
            />
          </div>
        </template>
        <el-table
          height="100%"
          v-loading="loading"
          :data="cacheNames"
          row-key="cacheName"
          highlight-current-row
          @row-click="loadKeys"
        >
          <el-table-column
            label="序号"
            type="index"
            width="60"
            align="center"
          />
          <el-table-column
            label="缓存名称"
            prop="cacheName"
            min-width="130"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{
              shortName(row.cacheName)
            }}</template>
          </el-table-column>
          <el-table-column
            label="备注"
            prop="remark"
            min-width="130"
            show-overflow-tooltip
          />
          <el-table-column label="操作" width="70" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="danger"
                :icon="Eraser"
                aria-label="清理缓存名称"
                @click.stop="removeName(row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="cache-panel">
        <template #header>
          <div class="panel-header">
            <h3>键名列表</h3>
            <el-button
              :icon="RotateCw"
              circle
              :disabled="!selectedName"
              :loading="keyLoading"
              aria-label="刷新键名列表"
              @click="loadKeys()"
            />
          </div>
        </template>
        <el-table
          height="100%"
          v-loading="keyLoading"
          :data="keyRows"
          row-key="cacheKey"
          highlight-current-row
          :empty-text="selectedName ? '暂无缓存键名' : '请先选择左侧缓存名称'"
          @row-click="loadValue"
        >
          <el-table-column
            label="序号"
            type="index"
            width="60"
            align="center"
          />
          <el-table-column
            label="缓存键名"
            prop="cacheKey"
            min-width="170"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ shortKey(row.cacheKey) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="70" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="danger"
                :icon="Eraser"
                aria-label="清理缓存键名"
                @click.stop="removeKey(row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="cache-panel cache-value-panel">
        <template #header>
          <div class="panel-header">
            <h3>缓存内容</h3>
            <el-button type="danger" plain :icon="Eraser" @click="removeAll"
              >清理全部</el-button
            >
          </div>
        </template>
        <div v-loading="valueLoading" class="cache-value">
          <el-empty v-if="!selectedKey" description="选择键名查看缓存内容" />
          <el-form
            v-else
            :model="cacheForm"
            label-position="top"
            class="bi-editor-form"
          >
            <el-form-item label="缓存名称">
              <el-input
                :model-value="cacheForm.cacheName || selectedName"
                readonly
              />
            </el-form-item>
            <el-form-item label="缓存键名">
              <el-input
                :model-value="cacheForm.cacheKey || selectedKey"
                readonly
              />
            </el-form-item>
            <el-form-item label="缓存内容">
              <el-input
                :model-value="cacheForm.cacheValue"
                type="textarea"
                :autosize="{ minRows: 10, maxRows: 24 }"
                readonly
              />
            </el-form-item>
          </el-form>
        </div>
      </el-card>
    </div>
  </Page>
</template>

<style scoped>
.cache-browser {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(260px, 1fr) minmax(
      300px,
      1.1fr
    );
  gap: 16px;
  height: 100%;
  min-height: 0;
}
.cache-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-color: var(--el-border-color-lighter);
}
.cache-panel :deep(.el-card__header) {
  padding: 16px;
}
.cache-panel :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  padding: 0 10px 10px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 32px;
  gap: 12px;
}
.panel-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.cache-value {
  padding: 16px 10px;
  height: 100%;
  overflow: auto;
}
.cache-value :deep(.el-form-item) {
  margin-bottom: 24px;
}
.cache-value :deep(textarea) {
  font-family: ui-monospace, monospace;
}
@media (max-width: 1200px) {
  .cache-browser {
    grid-template-columns: minmax(220px, 1fr) minmax(220px, 1fr);
    height: auto;
  }
  .cache-panel {
    height: 430px;
  }
  .cache-value-panel {
    grid-column: 1 / -1;
    height: auto;
    min-height: 360px;
  }
}
@media (max-width: 700px) {
  .cache-browser {
    grid-template-columns: minmax(0, 1fr);
  }
  .cache-value-panel {
    grid-column: auto;
  }
}
</style>
