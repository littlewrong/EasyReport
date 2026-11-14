<template>
  <n-dropdown
    trigger="hover"
    @select="handleSelect"
    :show-arrow="true"
    :options="options"
  >
    <div class="user-info-box">
      <person-icon v-if="fallback"></person-icon>
      <n-avatar
        v-if="!fallback"
        round
        object-fit="cover"
        size="small"
        :src="avatar"
        @error="errorHandle"
      ></n-avatar>
      <span v-if="displayName" class="user-name">{{ displayName }}</span>
    </div>
  </n-dropdown>

  <!-- 系统设置 model -->
  <go-system-set v-model:modelShow="modelShow"></go-system-set>
</template>

<script lang="ts" setup>
import { computed, h, onMounted, ref } from 'vue'
import { NAvatar, NText } from 'naive-ui'
import { renderIcon } from '@/utils'
import { logout, renderLang } from '@/utils'
import { getUserInfoApi, type UserInfoResult } from '@/api/user'
import { GoSystemSet } from '@/components/GoSystemSet/index'

import { icon } from '@/plugins'
const {
  PersonIcon,
  LogOutOutlineIcon,
  SettingsSharpIcon
} = icon.ionicons5

const t = window['$t']

const modelShow = ref(false)

// 是否失败
const fallback = ref(false)

// 当前登录用户，取自主系统 /user/info
const userInfo = ref<UserInfoResult | null>(null)

// 与主系统(@vben/preferences)一致的默认头像，加载失败时回退为图标
const DEFAULT_AVATAR =
  'https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v1.webp'

const avatar = computed(() => userInfo.value?.avatar || DEFAULT_AVATAR)

// 主系统右上角展示 realName，为空时退回 username
const displayName = computed(
  () => userInfo.value?.realName || userInfo.value?.username || ''
)

onMounted(async () => {
  try {
    const res = await getUserInfoApi()
    userInfo.value = res?.data ?? null
  } catch (error) {
    console.error('[user] 获取用户信息失败', error)
  }
})

// 用户图标渲染
const renderUserInfo = () => {
  return h(
    'div',
    {
      style: 'display: flex; align-items: center; padding: 8px 12px;'
    },
    [
      h(NAvatar, {
        round: true,
        style: 'margin-right: 12px;',
        src: avatar.value
      }),
      h('div', null, [
        h('div', null, [
          h(NText, { depth: 2 }, { default: () => displayName.value || '未登录' })
        ]),
        userInfo.value?.username
          ? h(
              'div',
              { style: 'font-size: 12px; opacity: 0.6;' },
              `@${userInfo.value.username}`
            )
          : null
      ])
    ]
  )
}
const options = ref([
  {
    label: '我的信息',
    key: 'info',
    type: 'render',
    render: renderUserInfo
  },
  {
    type: 'divider',
    key: 'd1'
  },
  {
    label: renderLang('global.sys_set'),
    key: 'sysSet',
    icon: renderIcon(SettingsSharpIcon)
  },
  {
    type: 'divider',
    key: 'd3'
  },
  {
    label: renderLang('global.logout'),
    key: 'logout',
    icon: renderIcon(LogOutOutlineIcon)
  }
])

// 图片渲染错误
const errorHandle = (e: Event) => {
  fallback.value = true
}

// 系统设置
const sysSetHandle = () => {
  modelShow.value = true
}

const handleSelect = (key: string) => {
  switch (key) {
    case 'sysSet':
      sysSetHandle()
      break
    case 'logout':
      logout()
      break
  }
}
</script>

<style lang="scss" scoped>
.user-info-box {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  .user-name {
    font-size: 14px;
  }
}
</style>
