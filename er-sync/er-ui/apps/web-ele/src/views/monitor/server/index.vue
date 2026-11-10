<script setup>
import { computed, onMounted, ref } from 'vue';
import { Page } from '@vben/common-ui';
import { RotateCw } from '@vben/icons';
import { useWindowSize } from '@vueuse/core';
import { getServer } from '#/legacy/api/monitor/server';

defineOptions({ name: 'Server' });
const server = ref({});
const loading = ref(false);
const { width } = useWindowSize();
const columns = computed(() => (width.value < 768 ? 1 : 2));
const rate = (value) =>
  Math.max(0, Math.min(100, Number.parseFloat(value) || 0));
const shown = (value) =>
  value === undefined || value === null || value === '' ? '—' : value;

async function loadData() {
  if (loading.value) return;
  loading.value = true;
  try {
    server.value = (await getServer()).data || {};
  } finally {
    loading.value = false;
  }
}
onMounted(loadData);
</script>

<template>
  <Page title="服务监控" description="查看服务器资源使用情况和运行环境">
    <template #extra>
      <el-button :icon="RotateCw" :loading="loading" @click="loadData"
        >刷新数据</el-button
      >
    </template>
    <div v-loading="loading" class="monitor-grid">
      <div class="resource-grid">
        <el-card shadow="never" class="monitor-card">
          <p class="resource-label">CPU 核心</p>
          <strong class="resource-value">{{
            shown(server.cpu?.cpuNum)
          }}</strong>
          <p class="resource-note">空闲 {{ shown(server.cpu?.free) }}%</p>
        </el-card>
        <el-card shadow="never" class="monitor-card">
          <p class="resource-label">CPU 使用率</p>
          <strong class="resource-value"
            >{{ shown(server.cpu?.used) }}<small>%</small></strong
          >
          <el-progress
            :percentage="rate(server.cpu?.used)"
            :show-text="false"
            :stroke-width="6"
          />
        </el-card>
        <el-card shadow="never" class="monitor-card">
          <p class="resource-label">物理内存使用率</p>
          <strong class="resource-value"
            >{{ shown(server.mem?.usage) }}<small>%</small></strong
          >
          <el-progress
            :percentage="rate(server.mem?.usage)"
            :show-text="false"
            :stroke-width="6"
            :status="rate(server.mem?.usage) > 80 ? 'exception' : undefined"
          />
        </el-card>
        <el-card shadow="never" class="monitor-card">
          <p class="resource-label">JVM 内存使用率</p>
          <strong class="resource-value"
            >{{ shown(server.jvm?.usage) }}<small>%</small></strong
          >
          <el-progress
            :percentage="rate(server.jvm?.usage)"
            :show-text="false"
            :stroke-width="6"
            :status="rate(server.jvm?.usage) > 80 ? 'exception' : undefined"
          />
        </el-card>
      </div>

      <div class="detail-grid">
        <el-card shadow="never" class="monitor-card">
          <template #header><h3>CPU 信息</h3></template>
          <el-descriptions :column="columns" border>
            <el-descriptions-item label="核心数">{{
              shown(server.cpu?.cpuNum)
            }}</el-descriptions-item>
            <el-descriptions-item label="用户使用率"
              >{{ shown(server.cpu?.used) }}%</el-descriptions-item
            >
            <el-descriptions-item label="系统使用率"
              >{{ shown(server.cpu?.sys) }}%</el-descriptions-item
            >
            <el-descriptions-item label="当前空闲率"
              >{{ shown(server.cpu?.free) }}%</el-descriptions-item
            >
          </el-descriptions>
        </el-card>
        <el-card shadow="never" class="monitor-card">
          <template #header><h3>内存信息</h3></template>
          <el-descriptions :column="columns" border>
            <el-descriptions-item label="物理内存"
              >{{ shown(server.mem?.total) }} GB</el-descriptions-item
            >
            <el-descriptions-item label="JVM 总内存"
              >{{ shown(server.jvm?.total) }} MB</el-descriptions-item
            >
            <el-descriptions-item label="物理已用"
              >{{ shown(server.mem?.used) }} GB</el-descriptions-item
            >
            <el-descriptions-item label="JVM 已用"
              >{{ shown(server.jvm?.used) }} MB</el-descriptions-item
            >
            <el-descriptions-item label="物理剩余"
              >{{ shown(server.mem?.free) }} GB</el-descriptions-item
            >
            <el-descriptions-item label="JVM 剩余"
              >{{ shown(server.jvm?.free) }} MB</el-descriptions-item
            >
          </el-descriptions>
        </el-card>
      </div>

      <el-card shadow="never" class="monitor-card">
        <template #header><h3>服务器信息</h3></template>
        <el-descriptions :column="columns" border>
          <el-descriptions-item label="服务器名称">{{
            shown(server.sys?.computerName)
          }}</el-descriptions-item>
          <el-descriptions-item label="操作系统">{{
            shown(server.sys?.osName)
          }}</el-descriptions-item>
          <el-descriptions-item label="服务器 IP">{{
            shown(server.sys?.computerIp)
          }}</el-descriptions-item>
          <el-descriptions-item label="系统架构">{{
            shown(server.sys?.osArch)
          }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="monitor-card">
        <template #header><h3>Java 虚拟机</h3></template>
        <el-descriptions :column="columns" border>
          <el-descriptions-item label="Java 名称">{{
            shown(server.jvm?.name)
          }}</el-descriptions-item>
          <el-descriptions-item label="Java 版本">{{
            shown(server.jvm?.version)
          }}</el-descriptions-item>
          <el-descriptions-item label="启动时间">{{
            shown(server.jvm?.startTime)
          }}</el-descriptions-item>
          <el-descriptions-item label="运行时长">{{
            shown(server.jvm?.runTime)
          }}</el-descriptions-item>
          <el-descriptions-item label="安装路径" :span="columns">{{
            shown(server.jvm?.home)
          }}</el-descriptions-item>
          <el-descriptions-item label="项目路径" :span="columns">{{
            shown(server.sys?.userDir)
          }}</el-descriptions-item>
          <el-descriptions-item label="运行参数" :span="columns">{{
            shown(server.jvm?.inputArgs)
          }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="monitor-card">
        <template #header><h3>磁盘状态</h3></template>
        <el-table :data="server.sysFiles || []" empty-text="暂无磁盘信息">
          <el-table-column
            label="盘符路径"
            prop="dirName"
            min-width="120"
            show-overflow-tooltip
          />
          <el-table-column
            label="文件系统"
            prop="sysTypeName"
            min-width="120"
          />
          <el-table-column label="盘符类型" prop="typeName" min-width="120" />
          <el-table-column label="总大小" prop="total" min-width="100" />
          <el-table-column label="可用大小" prop="free" min-width="100" />
          <el-table-column label="已用大小" prop="used" min-width="100" />
          <el-table-column label="使用率" min-width="180">
            <template #default="{ row }">
              <el-progress
                :percentage="rate(row.usage)"
                :status="rate(row.usage) > 80 ? 'exception' : undefined"
              />
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </Page>
</template>

<style scoped>
.monitor-grid {
  display: grid;
  gap: 16px;
}
.resource-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.monitor-card {
  min-width: 0;
  border-color: var(--el-border-color-lighter);
}
.monitor-card h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.monitor-card :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
.resource-label {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
.resource-value {
  display: block;
  margin-bottom: 18px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}
.resource-value small {
  margin-left: 4px;
  font-size: 16px;
  font-weight: 400;
}
.resource-note {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
@media (max-width: 1100px) {
  .resource-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .resource-grid {
    grid-template-columns: 1fr;
  }
}
</style>
