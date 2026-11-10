<template>
  <n-modal v-model:show="showRef" class="go-create-modal" @afterLeave="closeHandle">
    <n-space size="large">
      <n-card class="card-box" hoverable>
        <template #header>
          <n-text class="card-box-tite">{{ $t('project.create_tip') }}</n-text>
        </template>
        <template #header-extra>
          <n-text @click="closeHandle">
            <n-icon size="20">
              <component :is="CloseIcon"></component>
            </n-icon>
          </n-text>
        </template>
        <n-space class="card-box-content" justify="center">
          <n-button
            size="large"
            :disabled="item.disabled"
            v-for="item in typeList"
            :key="item.key"
            @click="btnHandle"
          >
            <component :is="item.title"></component>
            <template #icon>
              <n-icon size="18">
                <component :is="item.icon"></component>
              </n-icon>
            </template>
          </n-button>
        </n-space>
        <template #action></template>
      </n-card>
    </n-space>
  </n-modal>
</template>

<script lang="ts" setup>
import { ref, watch, shallowRef } from 'vue'
import { icon } from '@/plugins'
import { PageEnum, ChartEnum } from '@/enums/pageEnum'
import { fetchPathByName, routerTurnByPath, renderLang } from '@/utils'
import { createProjectApi } from '@/api/path'

const { FishIcon, CloseIcon } = icon.ionicons5
const { ObjectStorageIcon } = icon.carbon
const showRef = ref(false)

const emit = defineEmits(['close'])
const props = defineProps({
  show: Boolean
})

const typeList = shallowRef([
  {
    title: renderLang('project.new_project'),
    key: ChartEnum.CHART_HOME_NAME,
    icon: FishIcon,
    disabled: false
  },
  {
    title: renderLang('project.my_template'),
    key: PageEnum.BASE_HOME_TEMPLATE_NAME,
    icon: ObjectStorageIcon,
    disabled: true
  }
])

watch(props, newValue => {
  showRef.value = newValue.show
})

// 关闭对话框
const closeHandle = () => {
  emit('close', false)
}

// 处理按钮点击：调后端新建大屏，拿到真实 id 后再进入编辑器
const btnHandle = async () => {
  closeHandle()
  try {
    const res = await createProjectApi({ projectName: '新建大屏' })
    const id = res?.data
    if (!id) {
      window['$message'].error('新建大屏失败！')
      return
    }
    const path = fetchPathByName(ChartEnum.CHART_HOME_NAME, 'href')
    routerTurnByPath(path, [id], undefined, true)
  } catch (error) {
    console.error(error)
    window['$message'].error('新建大屏失败，请稍后重试！')
  }
}
</script>
<style lang="scss" scoped>
$cardWidth: 570px;

@include go('create-modal') {
  position: fixed;
  top: 200px;
  left: 50%;
  transform: translateX(-50%);
  .card-box {
    width: $cardWidth;
    cursor: pointer;
    border: 1px solid rgba(0, 0, 0, 0);
    @extend .go-transition;
    &:hover {
      @include hover-border-color('hover-border-color');
    }
    &-tite {
      font-size: 14px;
    }
    &-content {
      padding: 0px 10px;
      width: 100%;
    }
  }
}
</style>
