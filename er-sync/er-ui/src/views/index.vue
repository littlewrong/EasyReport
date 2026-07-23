<template>
  <div class="app-container dashboard">
    <!-- 第一行: KPI 卡片 -->
    <el-row :gutter="16" class="kpi-row">
      <el-col :xs="12" :sm="12" :md="6">
        <div class="kpi-card" style="border-left: 4px solid #409EFF;">
          <div class="kpi-body">
            <div class="kpi-info">
              <div class="kpi-value">{{ summary.datasourceTotal || 0 }}</div>
              <div class="kpi-label">数据源总数</div>
            </div>
            <div class="kpi-icon" style="background: #ecf5ff; color: #409EFF;">
              <i class="el-icon-connection"></i>
            </div>
          </div>
          <div class="kpi-footer">
            <span class="kpi-tag success">正常 {{ summary.datasourceConnected || 0 }}</span>
            <span class="kpi-tag danger">异常 {{ summary.datasourceFailed || 0 }}</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="kpi-card" style="border-left: 4px solid #67C23A;">
          <div class="kpi-body">
            <div class="kpi-info">
              <div class="kpi-value">{{ summary.taskTotal || 0 }}</div>
              <div class="kpi-label">同步任务数</div>
            </div>
            <div class="kpi-icon" style="background: #f0f9eb; color: #67C23A;">
              <i class="el-icon-refresh"></i>
            </div>
          </div>
          <div class="kpi-footer">
            <span class="kpi-tag warning">运行中 {{ summary.taskRunning || 0 }}</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="kpi-card" style="border-left: 4px solid #E6A23C;">
          <div class="kpi-body">
            <div class="kpi-info">
              <div class="kpi-value">{{ summary.todaySyncTotal || 0 }}</div>
              <div class="kpi-label">今日同步次数</div>
            </div>
            <div class="kpi-icon" style="background: #fdf6ec; color: #E6A23C;">
              <i class="el-icon-data-analysis"></i>
            </div>
          </div>
          <div class="kpi-footer">
            <span class="kpi-tag success">成功 {{ summary.todaySyncSuccess || 0 }}</span>
            <span class="kpi-tag danger">失败 {{ summary.todaySyncFail || 0 }}</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="kpi-card" style="border-left: 4px solid #909399;">
          <div class="kpi-body">
            <div class="kpi-info">
              <div class="kpi-value">{{ formatRows(summary.todaySyncRows) }}</div>
              <div class="kpi-label">今日同步行数</div>
            </div>
            <div class="kpi-icon" style="background: #f4f4f5; color: #909399;">
              <i class="el-icon-document-copy"></i>
            </div>
          </div>
          <div class="kpi-footer">
            <span class="kpi-tag info">records</span>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 第二行: 趋势图 + 类型分布 -->
    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :md="16">
        <el-card shadow="hover">
          <div slot="header" class="card-header">近7天同步趋势</div>
          <div ref="trendChart" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="hover">
          <div slot="header" class="card-header">数据源类型分布</div>
          <div ref="pieChart" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 第三行: 最近同步记录 -->
    <el-row :gutter="16" class="chart-row">
      <el-col :span="24">
        <el-card shadow="hover">
          <div slot="header" class="card-header">最近同步记录</div>
          <el-table :data="recentLogs" size="small" style="width: 100%;" max-height="320">
            <el-table-column label="任务名称" prop="transferName" min-width="120" :show-overflow-tooltip="true" />
            <el-table-column label="源表" prop="sourceTable" min-width="120" :show-overflow-tooltip="true" />
            <el-table-column label="目标表" prop="targetTable" min-width="120" :show-overflow-tooltip="true" />
            <el-table-column label="状态" prop="syncResult" width="70" align="center">
              <template slot-scope="scope">
                <el-tag v-if="scope.row.syncResult === '0'" type="success" size="mini">成功</el-tag>
                <el-tag v-else type="danger" size="mini">失败</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="行数" prop="successCount" width="80" align="right" />
            <el-table-column label="耗时" width="80" align="right">
              <template slot-scope="scope">
                {{ formatDuration(scope.row.executeTime) }}
              </template>
            </el-table-column>
            <el-table-column label="时间" prop="createTime" width="150" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { getSummary, getSyncTrend, getDsTypeStats, getRecentLogs } from '@/api/system/dashboard'

export default {
  name: "Index",
  data() {
    return {
      summary: {},
      recentLogs: [],
      trendChartInstance: null,
      pieChartInstance: null
    };
  },
  mounted() {
    this.loadData();
    window.addEventListener('resize', this.handleResize);
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize);
    if (this.trendChartInstance) this.trendChartInstance.dispose();
    if (this.pieChartInstance) this.pieChartInstance.dispose();
  },
  methods: {
    loadData() {
      getSummary().then(res => { this.summary = res.data; });
      getSyncTrend().then(res => { this.renderTrendChart(res.data.list || []); });
      getDsTypeStats().then(res => { this.renderPieChart(res.data.list || []); });
      getRecentLogs().then(res => { this.recentLogs = res.data.list || []; });
    },
    /** 近7天趋势 - 折线图 */
    renderTrendChart(data) {
      const dates = data.map(d => {
        const s = String(d.syncDate || '');
        return s.length >= 10 ? s.substring(5, 10) : s;
      });
      const success = data.map(d => d.successCount || 0);
      const fail = data.map(d => d.failCount || 0);

      this.trendChartInstance = echarts.init(this.$refs.trendChart);
      this.trendChartInstance.setOption({
        tooltip: { trigger: 'axis' },
        legend: { data: ['成功', '失败'], bottom: 0 },
        grid: { top: 20, right: 20, bottom: 40, left: 50 },
        xAxis: { type: 'category', data: dates, boundaryGap: false },
        yAxis: { type: 'value', minInterval: 1 },
        series: [
          { name: '成功', type: 'line', data: success, smooth: true, itemStyle: { color: '#67C23A' }, areaStyle: { color: 'rgba(103,194,58,0.1)' } },
          { name: '失败', type: 'line', data: fail, smooth: true, itemStyle: { color: '#F56C6C' }, areaStyle: { color: 'rgba(245,108,108,0.1)' } }
        ]
      });
    },
    /** 数据源类型 - 饼图 */
    renderPieChart(data) {
      this.pieChartInstance = echarts.init(this.$refs.pieChart);
      this.pieChartInstance.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { orient: 'horizontal', bottom: 0 },
        series: [{
          type: 'pie',
          radius: ['40%', '65%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: true,
          label: { show: true, formatter: '{b}\n{c}' },
          data: data
        }]
      });
    },
    handleResize() {
      if (this.trendChartInstance) this.trendChartInstance.resize();
      if (this.pieChartInstance) this.pieChartInstance.resize();
    },
    formatRows(val) {
      if (!val || val === 0) return '0';
      if (val >= 10000) return (val / 10000).toFixed(1) + ' 万';
      return String(val);
    },
    formatDuration(ms) {
      if (!ms && ms !== 0) return '-';
      if (ms < 1000) return ms + 'ms';
      return (ms / 1000).toFixed(1) + 's';
    }
  }
};
</script>

<style scoped>
.dashboard {
  padding: 16px;
}
.kpi-row {
  margin-bottom: 16px;
}
.chart-row {
  margin-bottom: 16px;
}
.card-header {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

/* KPI 卡片 */
.kpi-card {
  background: #fff;
  border-radius: 4px;
  padding: 20px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  transition: box-shadow 0.3s;
}
.kpi-card:hover {
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.12);
}
.kpi-body {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.kpi-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}
.kpi-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
.kpi-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.kpi-footer {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  gap: 12px;
}
.kpi-tag {
  font-size: 12px;
}
.kpi-tag.success { color: #67C23A; }
.kpi-tag.danger { color: #F56C6C; }
.kpi-tag.warning { color: #E6A23C; }
.kpi-tag.info { color: #909399; }

/* 图表容器 */
.chart-container {
  width: 100%;
  height: 320px;
}
</style>
