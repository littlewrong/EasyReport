<script setup>
import { computed, nextTick, onActivated, onMounted, ref } from 'vue';
import { Page } from '@vben/common-ui';
import { RotateCw } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useWindowSize } from '@vueuse/core';
import { getCache } from '#/legacy/api/monitor/cache';

defineOptions({ name: 'Cache' });
const cache = ref({ info: {}, dbSize: 0, commandStats: [] });
const loading = ref(false);
const commandChart = ref(),
  memoryChart = ref();
const { renderEcharts: renderCommands } = useEcharts(commandChart);
const { renderEcharts: renderMemory } = useEcharts(memoryChart);
const { width } = useWindowSize();
const columns = computed(() =>
  width.value < 768 ? 1 : width.value < 1400 ? 2 : 4,
);
const shown = (value) =>
  value === undefined || value === null || value === '' ? '—' : value;
let hasLoaded = false;

async function drawCharts() {
  await nextTick();
  const info = cache.value.info || {};
  await Promise.all([
    renderCommands({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [
        {
          name: '命令',
          type: 'pie',
          radius: ['38%', '68%'],
          center: ['50%', '43%'],
          data: cache.value.commandStats || [],
        },
      ],
    }),
    renderMemory({
      tooltip: {
        formatter: () => `内存消耗：${shown(info.used_memory_human)}`,
      },
      series: [
        {
          name: '内存',
          type: 'gauge',
          min: 0,
          max: 1000,
          progress: { show: true },
          pointer: { show: false },
          axisLine: { lineStyle: { width: 16 } },
          axisTick: { show: false },
          splitLine: { length: 10 },
          axisLabel: { distance: 24 },
          title: { offsetCenter: [0, '25%'] },
          detail: {
            formatter: () => shown(info.used_memory_human),
            fontSize: 24,
            offsetCenter: [0, '-5%'],
          },
          data: [
            {
              name: '已用内存',
              value: Number.parseFloat(info.used_memory_human) || 0,
            },
          ],
        },
      ],
    }),
  ]);
}
async function loadData() {
  if (loading.value) return;
  loading.value = true;
  try {
    cache.value = (await getCache()).data || { info: {}, commandStats: [] };
    hasLoaded = true;
    await drawCharts();
  } finally {
    loading.value = false;
  }
}
onMounted(loadData);
onActivated(() => {
  if (hasLoaded) drawCharts();
});
</script>

<template>
  <Page title="缓存监控" description="Redis 运行状态、命令分布及内存使用情况">
    <template #extra
      ><el-button :icon="RotateCw" :loading="loading" @click="loadData"
        >刷新数据</el-button
      ></template
    >
    <div v-loading="loading" class="cache-overview">
      <el-card shadow="never">
        <template #header><h3>基本信息</h3></template>
        <el-descriptions :column="columns" direction="vertical" border>
          <el-descriptions-item label="Redis 版本">{{
            shown(cache.info?.redis_version)
          }}</el-descriptions-item>
          <el-descriptions-item label="运行模式">{{
            cache.info?.redis_mode
              ? cache.info.redis_mode === 'standalone'
                ? '单机'
                : '集群'
              : '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="端口">{{
            shown(cache.info?.tcp_port)
          }}</el-descriptions-item>
          <el-descriptions-item label="客户端数">{{
            shown(cache.info?.connected_clients)
          }}</el-descriptions-item>
          <el-descriptions-item label="运行时间（天）">{{
            shown(cache.info?.uptime_in_days)
          }}</el-descriptions-item>
          <el-descriptions-item label="使用内存">{{
            shown(cache.info?.used_memory_human)
          }}</el-descriptions-item>
          <el-descriptions-item label="CPU 使用">{{
            cache.info?.used_cpu_user_children === undefined
              ? '—'
              : Number.parseFloat(cache.info.used_cpu_user_children).toFixed(2)
          }}</el-descriptions-item>
          <el-descriptions-item label="内存配置">{{
            shown(cache.info?.maxmemory_human)
          }}</el-descriptions-item>
          <el-descriptions-item label="AOF 持久化">{{
            cache.info?.aof_enabled === undefined
              ? '—'
              : cache.info.aof_enabled === '0'
                ? '未开启'
                : '已开启'
          }}</el-descriptions-item>
          <el-descriptions-item label="RDB 状态">{{
            shown(cache.info?.rdb_last_bgsave_status)
          }}</el-descriptions-item>
          <el-descriptions-item label="Key 数量">{{
            shown(cache.dbSize)
          }}</el-descriptions-item>
          <el-descriptions-item label="网络入口 / 出口"
            >{{ shown(cache.info?.instantaneous_input_kbps) }} /
            {{
              shown(cache.info?.instantaneous_output_kbps)
            }}
            kbps</el-descriptions-item
          >
        </el-descriptions>
      </el-card>
      <div class="cache-charts">
        <el-card shadow="never">
          <template #header><h3>命令统计</h3></template>
          <EchartsUI ref="commandChart" height="340px" />
        </el-card>
        <el-card shadow="never">
          <template #header><h3>内存信息</h3></template>
          <EchartsUI ref="memoryChart" height="340px" />
        </el-card>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.cache-overview {
  display: grid;
  gap: 16px;
}
.cache-charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.cache-overview h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.cache-overview :deep(.el-card) {
  min-width: 0;
  border-color: var(--el-border-color-lighter);
}
@media (max-width: 900px) {
  .cache-charts {
    grid-template-columns: 1fr;
  }
}
</style>
