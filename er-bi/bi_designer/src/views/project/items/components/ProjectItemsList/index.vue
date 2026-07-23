<template>
  <div class="go-items-list">
    <n-grid
      :x-gap="20"
      :y-gap="20"
      cols="2 s:2 m:3 l:4 xl:4 xxl:4"
      responsive="screen"
    >
      <n-grid-item v-for="(item, index) in list" :key="item.id">
        <project-items-card
          :cardData="item"
          @delete="deleteHandle($event, index)"
          @edit="editHandle"
        ></project-items-card>
      </n-grid-item>
    </n-grid>
    <div class="list-pagination">
      <n-pagination
        :item-count="10"
        :page-sizes="[10, 20, 30, 40]"
        show-size-picker
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ProjectItemsCard } from '../ProjectItemsCard/index'
import { useDataListInit } from './hooks/useData.hook'
import { ChartEnum } from '@/enums/pageEnum'
import { fetchPathByName, routerTurnByPath } from '@/utils'
import { Chartype } from '../../index.d'

const { list, deleteHandle } = useDataListInit()

const editHandle = (cardData: Chartype) => {
  if (!cardData) return
  const path = fetchPathByName(ChartEnum.CHART_HOME_NAME, 'href')
  routerTurnByPath(path, [cardData.id], undefined, true)
}
</script>

<style lang="scss" scoped>
$contentHeight: 250px;
@include go('items-list') {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: calc(100vh - #{$--header-height} * 2 - 2px);
  .list-content {
    position: relative;
    height: $contentHeight;
  }
  .list-pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 20px;
  }
}
</style>
