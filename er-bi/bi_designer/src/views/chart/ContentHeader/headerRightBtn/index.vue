<template>
  <n-space class="go-mt-0" :wrap="false">
    <n-button v-for="item in comBtnList" :key="item.title" :type="item.type" ghost @click="item.event">
      <template #icon>
        <component :is="item.icon"></component>
      </template>
      <span>{{ item.title }}</span>
    </n-button>
  </n-space>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { renderIcon, goDialog, fetchPathByName, routerTurnByPath, setSessionStorage, getSessionStorage, JSONStringify } from '@/utils'
import { PreviewEnum } from '@/enums/pageEnum'
import { StorageEnum } from '@/enums/storageEnum'
import { useRoute } from 'vue-router'
import { useChartEditStore } from '@/store/modules/chartEditStore/chartEditStore'
import { syncData } from '../../ContentEdit/components/EditTools/hooks/useSyncUpdate.hook'
import { icon } from '@/plugins'
import { cloneDeep } from 'lodash'
import { editProjectApi, saveProjectDataApi, publishProjectApi } from '@/api/path'
import { ResultEnum } from '@/enums/httpEnum'
import html2canvas from 'html2canvas'

const { BrowsersOutlineIcon, SendIcon, AnalyticsIcon, SaveIcon } = icon.ionicons5
const chartEditStore = useChartEditStore()

const routerParamsInfo = useRoute()

// 取当前大屏 id
const fetchProjectId = (): string => {
  const { id } = routerParamsInfo.params
  return typeof id === 'string' ? id : (id?.[0] ?? '')
}

const isEditorChrome = (element: Element) => {
  return Boolean(element.closest('.shape-modal, .shape-point, .go-edit-select, #go-edit-watermark'))
}

const captureCoverImage = async (): Promise<string | undefined> => {
  const range = document.querySelector('.go-edit-range') as HTMLElement | null
  if (!range) return undefined

  try {
    const coverScale = Math.min(1, 640 / (range.offsetWidth || 640))
    const canvas = await html2canvas(range, {
      allowTaint: false,
      backgroundColor: null,
      ignoreElements: isEditorChrome,
      logging: false,
      scale: coverScale,
      useCORS: true
    })
    return canvas.toDataURL('image/png')
  } catch (error) {
    console.warn('生成大屏封面失败', error)
    return undefined
  }
}

const buildSaveData = async (projectId: string) => {
  const content = JSONStringify(chartEditStore.getStorageInfo())
  const indexImage = await captureCoverImage()
  return indexImage ? { projectId, content, indexImage } : { projectId, content }
}

type ApiResponse = {
  code?: number
  error?: string
  msg?: string
  message?: string
}

const isApiSuccess = (response: unknown) => {
  if (response === false) return false
  if (!response || typeof response !== 'object') return true
  const { code } = response as ApiResponse
  return code === undefined || code === null || code === ResultEnum.DATA_SUCCESS || code === ResultEnum.SUCCESS
}

const assertApiSuccess = (response: unknown, fallbackMessage: string) => {
  if (isApiSuccess(response)) return
  if (response && typeof response === 'object') {
    const { error, msg, message } = response as ApiResponse
    throw new Error(error || msg || message || fallbackMessage)
  }
  throw new Error(fallbackMessage)
}

const saveProjectProfile = async (projectId: string) => {
  const projectName = chartEditStore.getEditCanvasConfig.projectName?.trim()
  if (!projectName) return
  const res = await editProjectApi({ id: projectId, projectName })
  assertApiSuccess(res, '保存大屏名称失败')
}

const saveProject = async (projectId: string) => {
  const saveRes = await saveProjectDataApi(await buildSaveData(projectId))
  assertApiSuccess(saveRes, '保存大屏数据失败')
  await saveProjectProfile(projectId)
}

const getErrorMessage = (error: unknown, fallbackMessage: string) => {
  return error instanceof Error ? error.message : fallbackMessage
}

// 保存：将画布数据落库
const saveHandle = async () => {
  const projectId = fetchProjectId()
  if (!projectId) {
    window['$message'].error('未找到大屏 id，保存失败！')
    return
  }
  try {
    await saveProject(projectId)
    window['$message'].success('保存成功！')
  } catch (error) {
    console.error(error)
    window['$message'].error(getErrorMessage(error, '保存失败，请稍后重试！'))
  }
}

// 预览
const previewHandle = () => {
  const path = fetchPathByName(PreviewEnum.CHART_PREVIEW_NAME, 'href')
  if (!path) return
  const { id } = routerParamsInfo.params
  // id 标识
  const previewId = typeof id === 'string' ? id : id[0]
  const storageInfo = chartEditStore.getStorageInfo()
  const sessionStorageInfo = getSessionStorage(StorageEnum.GO_CHART_STORAGE_LIST) || []

  if (sessionStorageInfo?.length) {
    const repeateIndex = sessionStorageInfo.findIndex((e: { id: string }) => e.id === previewId)
    // 重复替换
    if (repeateIndex !== -1) {
      sessionStorageInfo.splice(repeateIndex, 1, { id: previewId, ...storageInfo })
      setSessionStorage(StorageEnum.GO_CHART_STORAGE_LIST, sessionStorageInfo)
    } else {
      sessionStorageInfo.push({
        id: previewId,
        ...storageInfo
      })
      setSessionStorage(StorageEnum.GO_CHART_STORAGE_LIST, sessionStorageInfo)
    }
  } else {
    setSessionStorage(StorageEnum.GO_CHART_STORAGE_LIST, [{ id: previewId, ...storageInfo }])
  }
  // 跳转
  routerTurnByPath(path, [previewId], undefined, true)
}

// 发布：先保存画布，再置为已发布
const sendHandle = () => {
  const projectId = fetchProjectId()
  if (!projectId) {
    window['$message'].error('未找到大屏 id，发布失败！')
    return
  }
  goDialog({
    message: '确认发布该大屏吗？发布后将对外可见。',
    positiveText: '发布',
    negativeText: '取消',
    onPositiveCallback: async () => {
      try {
        await saveProject(projectId)
        const publishRes = await publishProjectApi({ id: projectId, state: 1 })
        assertApiSuccess(publishRes, '发布大屏失败')
        window['$message'].success('发布成功！')
      } catch (error) {
        console.error(error)
        window['$message'].error(getErrorMessage(error, '发布失败，请稍后重试！'))
      }
    }
  })
}

const btnList = [
  {
    select: true,
    title: '同步内容',
    type: 'primary',
    icon: renderIcon(AnalyticsIcon),
    event: syncData
  },
  {
    select: true,
    title: '保存',
    type: 'primary',
    icon: renderIcon(SaveIcon),
    event: saveHandle
  },
  {
    select: true,
    title: '预览',
    icon: renderIcon(BrowsersOutlineIcon),
    event: previewHandle
  },
  {
    select: true,
    title: '发布',
    icon: renderIcon(SendIcon),
    event: sendHandle
  }
]

const comBtnList = computed(() => {
  if (chartEditStore.getEditCanvas.isCodeEdit) {
    return btnList
  }
  const cloneList = cloneDeep(btnList)
  cloneList.shift()
  return cloneList
})
</script>

<style lang="scss" scoped>
.align-center {
  margin-top: -4px;
}
</style>
