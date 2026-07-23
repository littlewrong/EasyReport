<template>
  <n-space>
    <n-icon size="20" :depth="3">
      <fish-icon></fish-icon>
    </n-icon>
    <n-text @click="handleFocus">
      工作空间 -
      <n-button v-show="!focus" secondary size="tiny">
        <span class="title">
          {{ comTitle }}
        </span>
      </n-button>
    </n-text>

    <n-input
      v-show="focus"
      ref="inputInstRef"
      size="small"
      type="text"
      maxlength="16"
      show-count
      placeholder="请输入项目名称"
      v-model:value.trim="title"
      @keyup.enter="handleBlur"
      @blur="handleBlur"
    ></n-input>
  </n-space>
</template>

<script setup lang="ts">
import { ref, nextTick, computed, watch } from 'vue'
import { setTitle } from '@/utils'
import { useChartEditStore } from '@/store/modules/chartEditStore/chartEditStore'
import { EditCanvasConfigEnum } from '@/store/modules/chartEditStore/chartEditStore.d'
import { icon } from '@/plugins'

const { FishIcon } = icon.ionicons5
const chartEditStore = useChartEditStore()

const focus = ref<boolean>(false)
const inputInstRef = ref(null)
const title = ref<string>('')

const normalizeTitle = (value?: string) => (value || '').replace(/\s/g, '')

const comTitle = computed(() => {
  return normalizeTitle(title.value) || '新项目'
})

watch(
  () => chartEditStore.getEditCanvasConfig.projectName,
  value => {
    if (focus.value) return
    const newTitle = normalizeTitle(value) || '新项目'
    title.value = newTitle
    setTitle(`工作空间-${newTitle}`)
  },
  { immediate: true }
)

const handleFocus = () => {
  focus.value = true
  title.value = comTitle.value
  nextTick(() => {
    inputInstRef.value && (inputInstRef.value as any).focus()
  })
}

const handleBlur = () => {
  focus.value = false
  const newTitle = comTitle.value
  title.value = newTitle
  setTitle(`工作空间-${newTitle}`)
  chartEditStore.setEditCanvasConfig(EditCanvasConfigEnum.PROJECT_NAME, newTitle)
}
</script>
<style lang="scss" scoped>
.title {
  padding-left: 5px;
  padding-right: 5px;
  font-size: 15px;
}
</style>
