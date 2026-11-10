<script lang="ts" setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { AuthenticationLoginExpiredModal } from '@vben/common-ui';
import { BasicLayout, LockScreen, UserDropdown } from '@vben/layouts';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';
import { useAuthStore } from '#/store';
import LoginForm from '#/views/_core/authentication/login.vue';

const router = useRouter();
const userStore = useUserStore();
const authStore = useAuthStore();
const accessStore = useAccessStore();
const avatar = computed(() => userStore.userInfo?.avatar || preferences.app.defaultAvatar);
const menus = [{
  handler: () => router.push({ name: 'Profile' }),
  icon: 'lucide:user',
  text: '个人中心',
}];
async function logout() { await authStore.logout(false); }
</script>

<template>
  <BasicLayout @clear-preferences-and-logout="logout">
    <template #user-dropdown>
      <UserDropdown :avatar="avatar" :menus="menus" :text="userStore.userInfo?.realName" :description="userStore.userInfo?.username" tag-text="" @logout="logout" @clear-preferences-and-logout="logout" />
    </template>
    <template #extra>
      <AuthenticationLoginExpiredModal v-model:open="accessStore.loginExpired" :avatar="avatar">
        <LoginForm />
      </AuthenticationLoginExpiredModal>
    </template>
    <template #lock-screen><LockScreen :avatar="avatar" @to-login="logout" /></template>
  </BasicLayout>
</template>
