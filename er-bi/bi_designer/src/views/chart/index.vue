<template>
  <!-- 工作台相关 -->
  <div class="go-chart">
    <n-layout>
      <layout-header-pro>
        <template #left>
          <header-left-btn></header-left-btn>
        </template>
        <template #center>
          <header-title></header-title>
        </template>
        <template #ri-left>
          <header-right-btn></header-right-btn>
        </template>
      </layout-header-pro>
      <n-layout-content content-style="overflow:hidden; display: flex">
        <div style="overflow:hidden; display: flex">
          <content-charts></content-charts>
          <content-layers></content-layers>
        </div>
        <content-configurations></content-configurations>
      </n-layout-content>
    </n-layout>
  </div>
  <!-- 右键 -->
  <n-dropdown
    placement="bottom-start"
    trigger="manual"
    size="small"
    :x="mousePosition.x"
    :y="mousePosition.y"
    :options="menuOptions"
    :show="chartEditStore.getRightMenuShow"
    :on-clickoutside="onClickOutSide"
    @select="handleMenuSelect"
  ></n-dropdown>
  <!-- 加载蒙层 -->
  <content-load></content-load>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { loadAsyncComponent, fetchRouteParamsLocation, JSONParse, setTitle } from '@/utils'
import { LayoutHeaderPro } from '@/layout/components/LayoutHeaderPro'
import { useContextMenu } from './hooks/useContextMenu.hook'
import { useSync } from './hooks/useSync.hook'
import { useChartEditStore } from '@/store/modules/chartEditStore/chartEditStore'
import { EditCanvasConfigEnum } from '@/store/modules/chartEditStore/chartEditStore.d'
import { useChartHistoryStore } from '@/store/modules/chartHistoryStore/chartHistoryStore'
import { getProjectDataApi } from '@/api/path'

const chartHistoryStoreStore = useChartHistoryStore()
const chartEditStore = useChartEditStore()
const { updateComponent } = useSync()

// 记录初始化
chartHistoryStoreStore.canvasInit(chartEditStore.getEditCanvas)

// 进入编辑器：按路由 id 拉取已存画布数据并还原
onMounted(async () => {
  const id = fetchRouteParamsLocation()
  if (!id) return
  try {
    const res = await getProjectDataApi(id)
    const detail = res?.data
    if (detail?.content) {
      await updateComponent(JSONParse(detail.content), true)
    }
    if (detail?.projectName && !chartEditStore.getEditCanvasConfig.projectName) {
      chartEditStore.setEditCanvasConfig(EditCanvasConfigEnum.PROJECT_NAME, detail.projectName)
    }
    const projectName = chartEditStore.getEditCanvasConfig.projectName || detail?.projectName
    if (projectName) setTitle(`编辑-${projectName}`)
  } catch (error) {
    console.error(error)
    window['$message'].error('加载大屏数据失败！')
  }
})

const HeaderLeftBtn = loadAsyncComponent(() => import('./ContentHeader/headerLeftBtn/index.vue'))
const HeaderRightBtn = loadAsyncComponent(() => import('./ContentHeader/headerRightBtn/index.vue'))
const HeaderTitle = loadAsyncComponent(() => import('./ContentHeader/headerTitle/index.vue'))
const ContentLayers = loadAsyncComponent(() => import('./ContentLayers/index.vue'))
const ContentCharts = loadAsyncComponent(() => import('./ContentCharts/index.vue'))
const ContentConfigurations = loadAsyncComponent(() => import('./ContentConfigurations/index.vue'))
const ContentLoad = loadAsyncComponent(() => import('./ContentLoad/index.vue'))

// 右键
const {
  menuOptions,
  onClickOutSide,
  mousePosition,
  handleMenuSelect
} = useContextMenu()
</script>

<style lang="scss" scoped>
@include go("chart") {
  height: 100vh;
  width: 100vw;
  overflow: hidden;
  @include background-image("background-image");
}
</style>
