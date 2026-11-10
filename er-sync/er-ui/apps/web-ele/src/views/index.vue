<script setup lang="ts">
import type { EchartsUIType } from '@vben/plugins/echarts';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  ref,
} from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import {
  ArrowRightLeft,
  Check,
  ChevronRight,
  CircleAlert,
  CircleCheckBig,
  Copy,
  Inbox,
  Link2,
  List,
  LoaderCircle,
  RotateCw,
  SquareCode,
} from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useAccessStore, useUserStore } from '@vben/stores';
import { APP_NAME } from '#/branding';

import {
  getDsTypeStats,
  getRecentLogs,
  getSummary,
  getSyncTrend,
  getTaskStatus,
} from '#/legacy/api/system/dashboard';

defineOptions({ name: 'Index' });

interface Summary {
  datasourceTotal?: number;
  datasourceConnected?: number;
  datasourceFailed?: number;
  taskTotal?: number;
  taskRunning?: number;
  todaySyncTotal?: number;
  todaySyncSuccess?: number;
  todaySyncFail?: number;
  todaySyncRows?: number;
}
interface TrendRow {
  syncDate: string;
  successCount: number;
  failCount: number;
}
interface DistributionRow {
  name: string;
  value: number;
}
interface RecentLog {
  logId?: number;
  transferName?: string;
  sourceTable?: string;
  targetTable?: string;
  syncResult?: string;
  successCount?: number;
  executeTime?: number;
  createTime?: string;
}
type SectionKey = 'summary' | 'trend' | 'types' | 'status' | 'logs';

const router = useRouter();
const accessStore = useAccessStore();
const userStore = useUserStore();
const loading = ref(false);
const summary = ref<Summary>();
const trendRows = ref<TrendRow[]>([]);
const typeRows = ref<DistributionRow[]>([]);
const statusData = ref<DistributionRow[]>([]);
const recentLogs = ref<RecentLog[]>([]);
const lastUpdated = ref<Date>();
const clock = ref(new Date());
const failures = ref<SectionKey[]>([]);
const loaded = ref(false);
const trendChart = ref<EchartsUIType>();
const { renderEcharts: renderTrend } = useEcharts(trendChart);
let pageActive = true;

const numberFormat = new Intl.NumberFormat('zh-CN');
const username = computed(
  () => userStore.userInfo?.realName || userStore.userInfo?.username || '用户',
);
const greeting = computed(() => {
  const hour = clock.value.getHours();
  return hour < 6
    ? '夜深了'
    : hour < 12
      ? '上午好'
      : hour < 18
        ? '下午好'
        : '晚上好';
});
const dateLabel = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(clock.value),
);
const updateLabel = computed(() =>
  lastUpdated.value
    ? new Intl.DateTimeFormat('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(lastUpdated.value)
    : '尚未更新',
);
const shortcuts = computed(() =>
  [
    {
      path: '/databasesource/datasource',
      title: '数据源管理',
      description: '管理数据库连接',
      icon: Link2,
      tone: 'blue',
    },
    {
      path: '/dbsync/datasync',
      title: '结构同步',
      description: '同步库表结构',
      icon: SquareCode,
      tone: 'violet',
    },
    {
      path: '/dbsync/datatransfer',
      title: '数据传输',
      description: '查看与执行传输任务',
      icon: ArrowRightLeft,
      tone: 'green',
    },
    {
      path: '/dbsync/combinesync',
      title: '合并同步',
      description: '汇集分库分表数据',
      icon: Copy,
      tone: 'amber',
    },
  ].filter((item) => accessStore.getMenuByPath(item.path)),
);
const transferEntry = computed(() =>
  shortcuts.value.find((item) => item.path === '/dbsync/datatransfer'),
);
const hasFailure = (section: SectionKey) => failures.value.includes(section);

function count(value: unknown): number {
  const result = Number(value);
  return Number.isFinite(result) ? Math.max(0, result) : 0;
}
function number(value: number | undefined) {
  return value === undefined ? '—' : numberFormat.format(count(value));
}
function compact(value: number | undefined) {
  if (value === undefined) return '—';
  const n = count(value);
  if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(1)} 亿`;
  if (n >= 10_000) return `${(n / 10_000).toFixed(1)} 万`;
  return numberFormat.format(n);
}
const metricCards = computed(() => {
  const data = hasFailure('summary') ? undefined : summary.value;
  return [
    {
      key: 'datasource',
      title: '启用数据源',
      value: number(data?.datasourceTotal),
      fullValue: number(data?.datasourceTotal),
      unit: '个',
      icon: Link2,
      tone: 'blue',
      note: '数据库连接',
      details: [
        {
          title: '正常',
          value: number(data?.datasourceConnected),
          tone: 'success',
        },
        {
          title: '异常',
          value: number(data?.datasourceFailed),
          tone: 'danger',
        },
      ],
    },
    {
      key: 'tasks',
      title: '启用任务',
      value: number(data?.taskTotal),
      fullValue: number(data?.taskTotal),
      unit: '个',
      icon: SquareCode,
      tone: 'violet',
      note: '结构同步 · 数据传输',
      details: [
        { title: '同步中', value: number(data?.taskRunning), tone: 'primary' },
      ],
    },
    {
      key: 'today',
      title: '今日传输记录',
      value: number(data?.todaySyncTotal),
      fullValue: number(data?.todaySyncTotal),
      unit: '条',
      icon: CircleCheckBig,
      tone: 'green',
      note: '表级传输日志',
      details: [
        {
          title: '成功',
          value: number(data?.todaySyncSuccess),
          tone: 'success',
        },
        { title: '失败', value: number(data?.todaySyncFail), tone: 'danger' },
      ],
    },
    {
      key: 'rows',
      title: '今日传输行数',
      value: compact(data?.todaySyncRows),
      fullValue: number(data?.todaySyncRows),
      unit: '行',
      icon: Copy,
      tone: 'amber',
      note: '成功写入目标端',
      details: [{ title: '统计成功传输记录', value: '', tone: 'muted' }],
    },
  ];
});
const trendSuccess = computed(() =>
  trendRows.value.reduce((total, item) => total + count(item.successCount), 0),
);
const trendFailed = computed(() =>
  trendRows.value.reduce((total, item) => total + count(item.failCount), 0),
);
const hasTrend = computed(() => trendSuccess.value + trendFailed.value > 0);
const typeTotal = computed(() =>
  typeRows.value.reduce((total, item) => total + count(item.value), 0),
);
const typePalette = [
  '#3b82f6',
  '#8b5cf6',
  '#10b981',
  '#f59e0b',
  '#06b6d4',
  '#ec4899',
  '#64748b',
  '#f97316',
];
const sourceTypes = computed(() =>
  typeRows.value.map((item, index) => ({
    ...item,
    value: count(item.value),
    color: typePalette[index % typePalette.length],
    percent: typeTotal.value
      ? Math.round((count(item.value) / typeTotal.value) * 100)
      : 0,
  })),
);
const statusDefinitions = [
  { name: '待同步', description: '等待下一次执行', icon: Inbox, tone: 'muted' },
  {
    name: '同步中',
    description: '正在处理数据',
    icon: LoaderCircle,
    tone: 'primary',
  },
  {
    name: '已成功',
    description: '最近一次执行成功',
    icon: CircleCheckBig,
    tone: 'success',
  },
  {
    name: '已失败',
    description: '最近一次执行失败',
    icon: CircleAlert,
    tone: 'danger',
  },
];
const statuses = computed(() =>
  statusDefinitions.map((item) => ({
    ...item,
    value: count(
      statusData.value.find((status) => status.name === item.name)?.value,
    ),
  })),
);
const hasStatus = computed(() => statuses.value.some((item) => item.value > 0));
const statusMax = computed(() =>
  Math.max(1, ...statuses.value.map((item) => item.value)),
);

function go(path: string) {
  void router.push(path);
}
function formatDuration(value?: number) {
  if (value === undefined || value === null) return '—';
  return value < 1000
    ? `${numberFormat.format(value)} ms`
    : `${(value / 1000).toFixed(1)} s`;
}
function formatTime(value?: string) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—';
}

async function drawTrend() {
  if (!pageActive || !hasTrend.value || hasFailure('trend')) return;
  await nextTick();
  await renderTrend({
    backgroundColor: 'transparent',
    animationDuration: 450,
    color: ['#3b82f6', '#f59e0b'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'line' }, confine: true },
    grid: { top: 24, right: 14, bottom: 24, left: 42 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trendRows.value.map((item) =>
        String(item.syncDate || '').slice(5, 10),
      ),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#8b95a5', fontSize: 13, margin: 14 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#8b95a5', fontSize: 13 },
      splitLine: {
        lineStyle: { color: 'rgba(148,163,184,0.12)', type: 'dashed' },
      },
    },
    series: [
      {
        name: '成功',
        type: 'line',
        smooth: 0.35,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: false,
        lineStyle: { width: 3 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(59,130,246,0.20)' },
              { offset: 1, color: 'rgba(59,130,246,0)' },
            ],
          },
        },
        data: trendRows.value.map((item) => count(item.successCount)),
      },
      {
        name: '失败',
        type: 'line',
        smooth: 0.35,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: false,
        lineStyle: { width: 2 },
        data: trendRows.value.map((item) => count(item.failCount)),
      },
    ],
  });
}

async function loadData() {
  if (loading.value) return;
  loading.value = true;
  clock.value = new Date();
  const results = await Promise.allSettled([
    getSummary(),
    getSyncTrend(),
    getDsTypeStats(),
    getTaskStatus(),
    getRecentLogs(),
  ]);
  const keys: SectionKey[] = ['summary', 'trend', 'types', 'status', 'logs'];
  failures.value = results.flatMap((result, index) =>
    result.status === 'rejected' ? [keys[index]!] : [],
  );
  const [summaryResult, trendResult, typeResult, statusResult, logsResult] =
    results;
  if (summaryResult?.status === 'fulfilled')
    summary.value = summaryResult.value.data || {};
  if (trendResult?.status === 'fulfilled')
    trendRows.value = trendResult.value.data?.list || [];
  if (typeResult?.status === 'fulfilled')
    typeRows.value = typeResult.value.data?.list || [];
  if (statusResult?.status === 'fulfilled')
    statusData.value = statusResult.value.data?.list || [];
  if (logsResult?.status === 'fulfilled')
    recentLogs.value = logsResult.value.data?.list || [];
  if (results.some((result) => result.status === 'fulfilled'))
    lastUpdated.value = new Date();
  clock.value = new Date();
  loading.value = false;
  loaded.value = true;
  void drawTrend().catch(() => {
    if (!failures.value.includes('trend')) failures.value.push('trend');
  });
}

onMounted(loadData);
onActivated(() => {
  pageActive = true;
  clock.value = new Date();
  if (loaded.value) void drawTrend().catch(() => {});
});
onDeactivated(() => {
  pageActive = false;
});
</script>

<template>
  <Page content-class="sync-workspace-page">
    <div class="sync-workspace">
      <section class="workspace-hero">
        <div class="hero-copy">
          <div class="hero-eyebrow">
            <span class="eyebrow-dot"></span> {{ APP_NAME }} · WORKSPACE
          </div>
          <h1>{{ APP_NAME }} 工作台</h1>
          <p>
            {{ greeting }}，{{
              username
            }}。查看连接状态、任务进度与最近的传输结果。
          </p>
          <div class="hero-meta">
            <span>{{ dateLabel }}</span
            ><span class="meta-separator">/</span>
            <span>{{
              lastUpdated ? `最近更新 ${updateLabel}` : updateLabel
            }}</span>
          </div>
        </div>
        <div class="hero-actions">
          <el-button :icon="RotateCw" :loading="loading" @click="loadData"
            >刷新数据</el-button
          >
          <el-button
            v-if="transferEntry"
            type="primary"
            @click="go(transferEntry.path)"
          >
            <ArrowRightLeft class="button-icon" />任务管理<ChevronRight
              class="button-icon"
            />
          </el-button>
        </div>
        <nav
          v-if="shortcuts.length"
          class="hero-shortcuts"
          aria-label="快捷入口"
        >
          <button
            v-for="item in shortcuts"
            :key="item.path"
            type="button"
            class="shortcut"
            @click="go(item.path)"
          >
            <span :class="['shortcut-icon', item.tone]"
              ><component :is="item.icon"
            /></span>
            <span class="shortcut-copy"
              ><strong>{{ item.title }}</strong
              ><small>{{ item.description }}</small></span
            >
            <ChevronRight class="shortcut-arrow" />
          </button>
        </nav>
      </section>

      <el-alert
        v-if="failures.length"
        class="workspace-warning"
        title="部分数据加载失败，请刷新重试。"
        type="warning"
        :closable="false"
        show-icon
      />

      <section class="metrics-grid" aria-label="关键指标">
        <article
          v-for="metric in metricCards"
          :key="metric.key"
          class="metric-card"
        >
          <div class="metric-heading">
            <span>{{ metric.title }}</span>
            <span :class="['metric-symbol', metric.tone]"
              ><component :is="metric.icon"
            /></span>
          </div>
          <div class="metric-number" :title="metric.fullValue">
            <span>{{ metric.value }}</span
            ><small>{{ metric.unit }}</small>
          </div>
          <div class="metric-footer">
            <span class="metric-note">{{ metric.note }}</span>
            <span class="metric-detail">
              <span
                v-for="detail in metric.details"
                :key="detail.title"
                :class="['detail', detail.tone]"
              >
                {{ detail.title }}<b v-if="detail.value">{{ detail.value }}</b>
              </span>
            </span>
          </div>
        </article>
      </section>

      <div class="workspace-columns">
        <section class="workspace-panel trend-panel">
          <div class="panel-heading">
            <div>
              <h2>传输趋势</h2>
              <p>近 7 天的成功与失败传输记录</p>
            </div>
            <span class="period-label">最近 7 天</span>
          </div>
          <div class="trend-summary">
            <div>
              <i class="legend-dot primary-dot"></i><span>成功记录</span
              ><strong>{{
                hasFailure('trend') || !loaded ? '—' : number(trendSuccess)
              }}</strong>
            </div>
            <div>
              <i class="legend-dot warning-dot"></i><span>失败记录</span
              ><strong>{{
                hasFailure('trend') || !loaded ? '—' : number(trendFailed)
              }}</strong>
            </div>
          </div>
          <div class="trend-body">
            <div v-if="loading && !loaded" class="panel-placeholder">
              <LoaderCircle class="loading-icon" /><span>正在加载传输趋势</span>
            </div>
            <div v-else-if="hasFailure('trend')" class="panel-placeholder">
              <CircleAlert /><strong>传输趋势加载失败</strong
              ><el-button link type="primary" @click="loadData"
                >重新加载</el-button
              >
            </div>
            <EchartsUI v-else-if="hasTrend" ref="trendChart" height="234px" />
            <div v-else class="panel-placeholder">
              <ArrowRightLeft /><strong>近 7 天暂无传输记录</strong
              ><span>传输任务执行后，趋势会显示在这里</span>
            </div>
          </div>
        </section>

        <section class="workspace-panel status-panel">
          <div class="panel-heading">
            <div>
              <h2>任务状态</h2>
              <p>结构同步与数据传输任务</p>
            </div>
            <span class="panel-icon"><SquareCode /></span>
          </div>
          <div
            v-if="loading && !loaded"
            class="panel-placeholder status-placeholder"
          >
            <LoaderCircle class="loading-icon" /><span>正在加载任务状态</span>
          </div>
          <div
            v-else-if="hasFailure('status')"
            class="panel-placeholder status-placeholder"
          >
            <CircleAlert /><strong>任务状态加载失败</strong
            ><el-button link type="primary" @click="loadData"
              >重新加载</el-button
            >
          </div>
          <div
            v-else-if="!hasStatus"
            class="panel-placeholder status-placeholder"
          >
            <Inbox /><strong>暂无任务状态</strong
            ><span>统计待同步、同步中、成功与失败状态</span>
          </div>
          <div v-else class="status-list">
            <div v-for="item in statuses" :key="item.name" class="status-row">
              <span :class="['status-symbol', item.tone]"
                ><component
                  :is="item.icon"
                  :class="{
                    'loading-icon': item.name === '同步中' && item.value > 0,
                  }"
              /></span>
              <div class="status-text">
                <span>{{ item.name }}</span
                ><small>{{ item.description }}</small>
              </div>
              <strong>{{ number(item.value) }}</strong>
              <div class="status-track">
                <span
                  :class="item.tone"
                  :style="{ width: `${(item.value / statusMax) * 100}%` }"
                ></span>
              </div>
            </div>
          </div>
          <div class="panel-footnote">
            启用任务 · 待同步 / 同步中 / 成功 / 失败
          </div>
        </section>
      </div>

      <div class="workspace-columns">
        <section class="workspace-panel records-panel">
          <div class="panel-heading">
            <div>
              <h2>最近传输记录</h2>
              <p>最近 10 条表级传输日志</p>
            </div>
            <el-button
              v-if="transferEntry"
              link
              type="primary"
              @click="go(transferEntry.path)"
              >查看任务<ChevronRight class="button-icon"
            /></el-button>
          </div>
          <div
            v-if="loading && !loaded"
            class="panel-placeholder records-placeholder"
          >
            <LoaderCircle class="loading-icon" /><span>正在加载传输记录</span>
          </div>
          <div
            v-else-if="hasFailure('logs')"
            class="panel-placeholder records-placeholder"
          >
            <CircleAlert /><strong>传输记录加载失败</strong
            ><el-button link type="primary" @click="loadData"
              >重新加载</el-button
            >
          </div>
          <div
            v-else-if="!recentLogs.length"
            class="panel-placeholder records-placeholder"
          >
            <List /><strong>暂无传输记录</strong
            ><span>最近的传输结果与执行耗时会显示在这里</span>
          </div>
          <el-table
            v-else
            :data="recentLogs"
            max-height="352"
            class="records-table"
            :row-key="(row: RecentLog) => row.logId"
          >
            <el-table-column label="任务 / 表" min-width="200">
              <template #default="{ row }"
                ><div class="record-task">
                  <span class="record-task-icon"><ArrowRightLeft /></span>
                  <div>
                    <strong :title="row.transferName">{{
                      row.transferName || '—'
                    }}</strong>
                    <p
                      :title="`${row.sourceTable || '—'} → ${row.targetTable || '—'}`"
                    >
                      {{ row.sourceTable || '—' }}<span>→</span
                      >{{ row.targetTable || '—' }}
                    </p>
                  </div>
                </div></template
              >
            </el-table-column>
            <el-table-column label="结果" width="92" align="center"
              ><template #default="{ row }"
                ><span
                  :class="[
                    'result-tag',
                    String(row.syncResult) === '0'
                      ? 'success'
                      : String(row.syncResult) === '1'
                        ? 'danger'
                        : 'muted',
                  ]"
                  ><Check v-if="String(row.syncResult) === '0'" /><CircleAlert
                    v-else-if="String(row.syncResult) === '1'"
                  />{{
                    String(row.syncResult) === '0'
                      ? '成功'
                      : String(row.syncResult) === '1'
                        ? '失败'
                        : '—'
                  }}</span
                ></template
              ></el-table-column
            >
            <el-table-column label="成功行数" width="105" align="right"
              ><template #default="{ row }">{{
                number(row.successCount)
              }}</template></el-table-column
            >
            <el-table-column label="耗时" width="95" align="right"
              ><template #default="{ row }">{{
                formatDuration(row.executeTime)
              }}</template></el-table-column
            >
            <el-table-column label="执行时间" width="180"
              ><template #default="{ row }"
                ><span class="record-time">{{
                  formatTime(row.createTime)
                }}</span></template
              ></el-table-column
            >
          </el-table>
        </section>

        <section class="workspace-panel sources-panel">
          <div class="panel-heading">
            <div>
              <h2>数据源分布</h2>
              <p>按数据库类型查看启用连接</p>
            </div>
            <span class="panel-icon"><Link2 /></span>
          </div>
          <div
            v-if="loading && !loaded"
            class="panel-placeholder sources-placeholder"
          >
            <LoaderCircle class="loading-icon" /><span>正在加载数据源</span>
          </div>
          <div
            v-else-if="hasFailure('types')"
            class="panel-placeholder sources-placeholder"
          >
            <CircleAlert /><strong>数据源分布加载失败</strong
            ><el-button link type="primary" @click="loadData"
              >重新加载</el-button
            >
          </div>
          <div
            v-else-if="!typeTotal"
            class="panel-placeholder sources-placeholder"
          >
            <Link2 /><strong>暂无启用的数据源</strong
            ><span>添加数据库连接，开始构建数据通道</span>
          </div>
          <template v-else>
            <div class="source-total">
              <strong>{{ number(typeTotal) }}</strong
              ><span>个启用连接</span
              ><small>{{ sourceTypes.length }} 种数据库类型</small>
            </div>
            <div class="source-list">
              <div
                v-for="item in sourceTypes"
                :key="item.name"
                class="source-item"
              >
                <div class="source-label">
                  <span
                    ><i :style="{ background: item.color }"></i
                    >{{ item.name }}</span
                  ><strong
                    >{{ item.value }}<small>{{ item.percent }}%</small></strong
                  >
                </div>
                <div class="source-track">
                  <span
                    :style="{
                      width: `${item.percent}%`,
                      background: item.color,
                    }"
                  ></span>
                </div>
              </div>
            </div>
          </template>
        </section>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.sync-workspace {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  color: hsl(var(--foreground));
}
.workspace-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 24px;
  padding: 24px;
  border: 1px solid hsl(var(--border) / 65%);
  border-radius: 10px;
  background:
    radial-gradient(
      ellipse at 100% 0%,
      hsl(var(--primary) / 9%),
      transparent 55%
    ),
    hsl(var(--card));
}
.hero-eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  color: hsl(var(--muted-foreground));
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1.6px;
}
.eyebrow-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: hsl(var(--primary));
}
.hero-copy h1 {
  margin: 10px 0 8px;
  font-size: 30px;
  font-weight: 650;
  letter-spacing: -0.6px;
  line-height: 1.3;
}
.hero-copy > p {
  overflow-wrap: anywhere;
  margin: 0;
  color: hsl(var(--muted-foreground));
  font-size: 16px;
  line-height: 1.6;
}
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 13px;
  color: hsl(var(--muted-foreground));
  font-size: 14px;
}
.meta-separator {
  opacity: 0.4;
}
.hero-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.button-icon {
  width: 15px;
  height: 15px;
}
.hero-actions .button-icon:first-child {
  margin-right: 7px;
}
.hero-actions .button-icon:last-child {
  margin-left: 8px;
}
.hero-shortcuts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-column: 1 / -1;
  gap: 16px;
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid hsl(var(--border) / 60%);
}
.shortcut {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  padding: 7px 8px;
  border: 0;
  border-radius: 8px;
  text-align: left;
  background: transparent;
  color: inherit;
  cursor: pointer;
  transition: background 0.2s;
}
.shortcut:hover {
  background: hsl(var(--muted) / 65%);
}
.shortcut:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: 2px;
}
.shortcut-icon,
.metric-symbol {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
}
.shortcut-icon {
  width: 40px;
  height: 40px;
}
.shortcut-icon svg {
  width: 20px;
  height: 20px;
}
.shortcut-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.shortcut-copy strong {
  font-size: 16px;
  font-weight: 600;
}
.shortcut-copy small {
  overflow: hidden;
  font-size: 14px;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
  text-overflow: ellipsis;
}
.shortcut-arrow {
  flex: none;
  width: 14px;
  height: 14px;
  color: hsl(var(--muted-foreground));
  opacity: 0.6;
}
.blue {
  color: #3b82f6;
  background: rgb(59 130 246 / 11%);
}
.violet {
  color: #8b5cf6;
  background: rgb(139 92 246 / 11%);
}
.green {
  color: #10b981;
  background: rgb(16 185 129 / 11%);
}
.amber {
  color: #f59e0b;
  background: rgb(245 158 11 / 11%);
}
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.metric-card {
  min-width: 0;
  padding: 18px 20px 15px;
  border-radius: 10px;
  background: hsl(var(--card));
}
.metric-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: hsl(var(--muted-foreground));
  font-size: 16px;
}
.metric-symbol {
  width: 32px;
  height: 32px;
  border-radius: 8px;
}
.metric-symbol svg {
  width: 17px;
  height: 17px;
}
.metric-number {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 4px;
  font-variant-numeric: tabular-nums;
}
.metric-number > span {
  font-size: 36px;
  font-weight: 650;
  line-height: 1.4;
  letter-spacing: -0.7px;
}
.metric-number small {
  color: hsl(var(--muted-foreground));
  font-size: 14px;
}
.metric-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 7px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid hsl(var(--border) / 55%);
  font-size: 14px;
  line-height: 1.5;
}
.metric-note {
  color: hsl(var(--muted-foreground));
}
.metric-detail {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 9px;
}
.detail b {
  margin-left: 4px;
  font-weight: 600;
}
.success {
  color: #10b981;
}
.danger {
  color: #f97373;
}
.primary {
  color: #3b82f6;
}
.muted {
  color: hsl(var(--muted-foreground));
}
.workspace-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 322px;
  gap: 16px;
  align-items: stretch;
}
.workspace-panel {
  min-width: 0;
  padding: 20px;
  border-radius: 10px;
  background: hsl(var(--card));
}
.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.panel-heading h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
}
.panel-heading p {
  margin: 4px 0 0;
  font-size: 14px;
  color: hsl(var(--muted-foreground));
  line-height: 1.6;
}
.period-label {
  flex: none;
  padding: 4px 9px;
  border: 1px solid hsl(var(--border));
  border-radius: 5px;
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}
.panel-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: hsl(var(--muted));
  color: hsl(var(--muted-foreground));
}
.panel-icon svg {
  width: 15px;
  height: 15px;
}
.trend-summary {
  display: flex;
  gap: 26px;
  margin-top: 22px;
}
.trend-summary > div {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  color: hsl(var(--muted-foreground));
}
.trend-summary strong {
  margin-left: 3px;
  font-size: 18px;
  font-weight: 600;
  color: hsl(var(--foreground));
  font-variant-numeric: tabular-nums;
}
.legend-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.primary-dot {
  background: #3b82f6;
}
.warning-dot {
  background: #f59e0b;
}
.trend-body {
  min-height: 234px;
  margin-top: 2px;
}
.panel-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-height: 232px;
  color: hsl(var(--muted-foreground));
  font-size: 15px;
  text-align: center;
}
.panel-placeholder > svg {
  width: 29px;
  height: 29px;
  margin-bottom: 2px;
  opacity: 0.5;
  stroke-width: 1.4;
}
.panel-placeholder strong {
  font-size: 16px;
  font-weight: 500;
  color: hsl(var(--foreground) / 85%);
}
.panel-placeholder > span {
  font-size: 14px;
  line-height: 1.6;
}
.loading-icon {
  animation: workspace-spin 1.2s linear infinite;
}
@keyframes workspace-spin {
  to {
    transform: rotate(360deg);
  }
}
.status-panel {
  display: flex;
  flex-direction: column;
}
.status-list {
  display: grid;
  gap: 15px;
  margin: 20px 0 16px;
}
.status-row {
  display: grid;
  grid-template-columns: 33px minmax(0, 1fr) auto;
  align-items: center;
  gap: 3px 9px;
}
.status-symbol {
  display: flex;
  grid-row: 1 / 3;
  align-items: center;
  justify-content: center;
  width: 31px;
  height: 31px;
  border-radius: 8px;
  background: hsl(var(--muted));
}
.status-symbol svg {
  width: 16px;
  height: 16px;
}
.status-text {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px;
  min-width: 0;
  font-size: 15px;
}
.status-text small {
  display: none;
  color: hsl(var(--muted-foreground));
  font-size: 13px;
}
.status-row > strong {
  font-size: 20px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.status-track {
  grid-column: 2 / 4;
  overflow: hidden;
  height: 4px;
  border-radius: 3px;
  background: hsl(var(--muted));
}
.status-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: currentColor;
}
.panel-footnote {
  margin-top: auto;
  padding-top: 13px;
  border-top: 1px solid hsl(var(--border) / 55%);
  color: hsl(var(--muted-foreground));
  font-size: 13px;
  line-height: 1.6;
}
.status-placeholder {
  flex: 1;
  min-height: 220px;
}
.records-panel {
  overflow: hidden;
}
.records-placeholder {
  min-height: 248px;
}
.records-table {
  margin-top: 18px;
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: hsl(var(--muted) / 50%);
  --el-table-border-color: hsl(var(--border) / 60%);
  font-size: 15px;
}
.records-table :deep(th.el-table__cell) {
  height: 44px;
  font-size: 14px;
  font-weight: 500;
  color: hsl(var(--muted-foreground));
}
.records-table :deep(td.el-table__cell) {
  height: 68px;
}
.records-table :deep(.cell) {
  padding: 0 8px;
}
.record-task {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.record-task-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
  color: hsl(var(--muted-foreground));
}
.record-task-icon svg {
  width: 15px;
  height: 15px;
}
.record-task > div {
  min-width: 0;
}
.record-task strong {
  display: block;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.record-task p {
  display: flex;
  gap: 6px;
  margin: 3px 0 0;
  overflow: hidden;
  font-size: 13px;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
  text-overflow: ellipsis;
}
.record-task p > span {
  opacity: 0.6;
}
.result-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  background: rgb(16 185 129 / 8%);
}
.result-tag.danger {
  background: rgb(249 115 115 / 9%);
}
.result-tag svg {
  width: 11px;
  height: 11px;
}
.record-time {
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}
.sources-panel {
  display: flex;
  flex-direction: column;
}
.source-total {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin: 20px 0;
}
.source-total > strong {
  font-size: 34px;
  font-weight: 650;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.source-total > span {
  font-size: 14px;
  color: hsl(var(--muted-foreground));
}
.source-total > small {
  margin-left: auto;
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}
.source-list {
  display: grid;
  gap: 19px;
  max-height: 223px;
  overflow-y: auto;
  padding: 0 2px 2px;
}
.source-label {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 14px;
}
.source-label > span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.source-label i {
  width: 6px;
  height: 6px;
  border-radius: 2px;
}
.source-label strong {
  font-size: 14px;
  font-weight: 500;
}
.source-label small {
  display: inline-block;
  min-width: 37px;
  margin-left: 9px;
  color: hsl(var(--muted-foreground));
  font-size: 13px;
  font-weight: 400;
  text-align: right;
}
.source-track {
  height: 5px;
  overflow: hidden;
  border-radius: 4px;
  background: hsl(var(--muted));
}
.source-track > span {
  display: block;
  height: 100%;
  border-radius: inherit;
}
.sources-placeholder {
  flex: 1;
  min-height: 248px;
}
@media (min-width: 1800px) {
  .workspace-columns {
    grid-template-columns: minmax(0, 1fr) 365px;
  }
  .status-text small {
    display: inline;
  }
}
@media (max-width: 1279px) {
  .workspace-columns {
    grid-template-columns: minmax(0, 1fr) 290px;
  }
  .metric-card {
    padding: 16px;
  }
  .metric-footer {
    flex-direction: column;
    gap: 5px;
  }
  .hero-shortcuts {
    gap: 8px;
  }
  .shortcut-copy small {
    display: none;
  }
}
@media (max-width: 1023px) {
  .workspace-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .status-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px 24px;
  }
  .status-placeholder {
    min-height: 160px;
  }
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .sources-placeholder {
    min-height: 180px;
  }
}
@media (max-width: 767px) {
  .workspace-hero {
    grid-template-columns: minmax(0, 1fr);
    gap: 18px;
    padding: 20px;
  }
  .hero-copy h1 {
    font-size: 28px;
  }
  .hero-actions {
    grid-row: 2;
    flex-wrap: wrap;
  }
  .hero-shortcuts {
    grid-row: 3;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    margin-top: 0;
    gap: 14px;
  }
  .shortcut {
    padding: 0;
    gap: 8px;
  }
  .shortcut-icon {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }
  .shortcut-icon svg {
    width: 17px;
    height: 17px;
  }
  .shortcut-copy strong {
    font-size: 15px;
  }
  .shortcut-arrow {
    display: none;
  }
  .metric-card {
    padding: 15px;
  }
  .metric-number > span {
    font-size: 32px;
  }
  .metric-symbol {
    width: 28px;
    height: 28px;
  }
  .metric-heading {
    font-size: 14px;
    gap: 5px;
  }
  .metric-footer {
    font-size: 13px;
  }
  .workspace-panel {
    padding: 18px;
  }
  .status-text small {
    display: none;
  }
  .source-list {
    max-height: none;
  }
}
@media (max-width: 400px) {
  .hero-copy h1 {
    font-size: 26px;
  }
  .hero-eyebrow {
    font-size: 12px;
  }
  .status-list {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-icon {
    animation: none;
  }
}
</style>
