import { ref, onMounted } from 'vue'
import { goDialog } from '@/utils'
import { DialogEnum } from '@/enums/pluginEnum'
import { ChartList } from '../../..'
import { getProjectListApi, deleteProjectApi, ProjectListItem } from '@/api/path'

// 数据初始化
export const useDataListInit = () => {
  const list = ref<ChartList>([])

  // 后端列表项 -> 卡片展示结构
  const mapItem = (item: ProjectListItem) => ({
    id: item.id,
    title: item.projectName,
    release: item.state === 1,
    label: item.remarks || '',
    indexImage: item.indexImage || ''
  })

  // 拉取列表
  const fetchList = async () => {
    try {
      const res = await getProjectListApi({ page: 1, limit: 100 })
      const data = res?.data?.list || []
      list.value = data.map(mapItem)
    } catch (error) {
      console.error(error)
      window['$message'].error('获取大屏列表失败！')
    }
  }

  onMounted(fetchList)

  // 删除
  const deleteHandle = (cardData: any, index: number) => {
    goDialog({
      type: DialogEnum.DELETE,
      promise: true,
      onPositiveCallback: () => deleteProjectApi(cardData.id),
      promiseResCallback: () => {
        window.$message.success('删除成功')
        list.value.splice(index, 1)
      }
    })
  }

  return {
    list,
    deleteHandle,
    fetchList
  }
}
