<script lang="ts" setup>
import type { FormInstance } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import { LoaderCircle, RotateCw } from '@vben/icons';

import { ElButton, ElForm, ElFormItem, ElInput } from 'element-plus';
import { getCaptchaApi } from '#/api';
import { APP_NAME } from '#/branding';
import { useAuthStore } from '#/store';

defineOptions({ name: 'Login' });
const authStore = useAuthStore();
const formRef = ref<FormInstance>();
const form = reactive({ username: '', password: '', code: '', uuid: '' });
const captchaEnabled = ref(false);
const captchaImage = ref('');
const captchaTransparent = ref(false);
const captchaLoading = ref(true);
const captchaError = ref(false);

async function refreshCaptcha() {
  captchaLoading.value = true;
  captchaError.value = false;
  try {
    const result = await getCaptchaApi();
    captchaEnabled.value = result.captchaEnabled !== false;
    captchaTransparent.value = result.imageStyle === 'ersync';
    form.uuid = result.uuid || '';
    form.code = '';
    captchaImage.value = result.img
      ? `data:image/png;base64,${result.img}`
      : '';
  } catch {
    captchaError.value = true;
  } finally {
    captchaLoading.value = false;
  }
}
async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (
    !valid ||
    captchaLoading.value ||
    captchaError.value ||
    authStore.loginLoading
  )
    return;
  try {
    await authStore.authLogin({ ...form });
  } catch {
    await refreshCaptcha();
  }
}
onMounted(refreshCaptcha);
</script>

<template>
  <div class="ersync-login mx-auto w-full max-w-md">
    <h1 class="login-title">登录 {{ APP_NAME }}</h1>
    <p class="login-description">使用 {{ APP_NAME }} 账号登录</p>
    <ElForm
      ref="formRef"
      :model="form"
      size="large"
      label-position="top"
      hide-required-asterisk
      @submit.prevent="submit"
    >
      <ElFormItem
        prop="username"
        label="用户名"
        for="login-username"
        :rules="[{ required: true, message: '请输入用户名', trigger: 'blur' }]"
      >
        <ElInput
          id="login-username"
          v-model="form.username"
          placeholder="用户名"
          autocomplete="username"
          autocapitalize="none"
          :spellcheck="false"
        />
      </ElFormItem>
      <ElFormItem
        prop="password"
        label="密码"
        for="login-password"
        :rules="[{ required: true, message: '请输入密码', trigger: 'blur' }]"
      >
        <ElInput
          id="login-password"
          v-model="form.password"
          placeholder="密码"
          type="password"
          show-password
          autocomplete="current-password"
        />
      </ElFormItem>
      <ElFormItem
        v-if="captchaEnabled"
        prop="code"
        label="验证码"
        for="login-code"
        :rules="[{ required: true, message: '请输入验证码', trigger: 'blur' }]"
      >
        <ElInput
          id="login-code"
          v-model="form.code"
          class="login-code-input"
          placeholder="验证码"
          autocomplete="off"
          :spellcheck="false"
        >
          <template #suffix>
            <button
              type="button"
              class="login-captcha-button"
              :class="{ 'is-transparent': captchaTransparent }"
              title="看不清？点击换一张"
              aria-label="刷新验证码"
              :aria-busy="captchaLoading"
              :disabled="captchaLoading"
              @click="refreshCaptcha"
            >
              <span class="login-captcha-preview">
                <LoaderCircle
                  v-if="captchaLoading"
                  class="login-captcha-spinner"
                  aria-hidden="true"
                />
                <img
                  v-else-if="captchaImage && !captchaError"
                  :src="captchaImage"
                  alt="验证码"
                />
                <span v-else class="login-captcha-placeholder">点击重试</span>
              </span>
              <RotateCw class="login-captcha-refresh" aria-hidden="true" />
            </button>
          </template>
        </ElInput>
      </ElFormItem>
      <div v-if="captchaError" class="login-error" role="alert">
        无法连接后端，请确认接口服务已启动。
        <ElButton link type="primary" @click="refreshCaptcha">重试</ElButton>
      </div>
      <ElButton
        native-type="submit"
        type="primary"
        class="login-submit"
        :disabled="captchaLoading || captchaError"
        :loading="authStore.loginLoading"
      >
        登录
      </ElButton>
    </ElForm>
  </div>
</template>

<style scoped>
.ersync-login {
  --login-field-bg: hsl(var(--background));
  --login-field-height: 48px;

  color: hsl(var(--foreground));
}

.login-title {
  margin-bottom: 8px;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: -0.5px;
}

.login-description {
  margin-bottom: 30px;
  font-size: 16px;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
}

.ersync-login :deep(.el-form-item) {
  margin-bottom: 24px;
}

.ersync-login :deep(.el-form-item__label) {
  height: auto;
  padding: 0;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
  color: hsl(var(--foreground));
}

.ersync-login :deep(.el-input) {
  --el-input-bg-color: var(--login-field-bg);
  --el-input-text-color: hsl(var(--foreground));
  --el-input-placeholder-color: hsl(var(--muted-foreground));

  min-width: 0;
  height: var(--login-field-height);
  font-size: 16px;
}

.ersync-login :deep(.el-input__wrapper) {
  padding: 1px 14px;
  background: var(--login-field-bg);
  border-radius: 10px;
  box-shadow: 0 0 0 1px hsl(var(--border)) inset;
  transition: box-shadow 0.2s;
}

.ersync-login :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px hsl(var(--muted-foreground) / 65%) inset;
}

.ersync-login :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px hsl(var(--primary)) inset,
    0 0 0 3px hsl(var(--primary) / 12%);
}

.ersync-login :deep(.el-input__inner) {
  height: calc(var(--login-field-height) - 2px);
  line-height: normal;
  background: transparent;
}

/* Keep saved credentials the same color as the whole field in both themes. */
.ersync-login :deep(.el-input__inner:-webkit-autofill),
.ersync-login :deep(.el-input__inner:-webkit-autofill:hover),
.ersync-login :deep(.el-input__inner:-webkit-autofill:focus) {
  -webkit-text-fill-color: hsl(var(--foreground));
  caret-color: hsl(var(--foreground));
  box-shadow: 0 0 0 1000px var(--login-field-bg) inset;
}

.ersync-login :deep(.el-input__password) {
  font-size: 18px;
}

.ersync-login :deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.ersync-login :deep(.el-form-item__error) {
  padding-top: 5px;
  font-size: 13px;
}

.ersync-login :deep(.login-code-input .el-input__wrapper) {
  padding-right: 8px;
}

.login-captcha-button {
  display: flex;
  gap: 8px;
  align-items: center;
  height: 36px;
  padding: 0 4px 0 12px;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-left: 1px solid hsl(var(--border));
}

.login-captcha-button:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: 1px;
  border-radius: 4px;
}

.login-captcha-button:disabled {
  cursor: wait;
}

.login-captcha-preview {
  display: flex;
  flex: 0 0 108px;
  align-items: center;
  justify-content: center;
  height: 36px;
  overflow: hidden;
  border-radius: 4px;
}

.login-captcha-preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.dark .login-captcha-button.is-transparent img {
  opacity: 0.88;
  filter: brightness(0) invert(1);
}

.login-captcha-refresh,
.login-captcha-spinner {
  flex: none;
  width: 16px;
  height: 16px;
  color: hsl(var(--muted-foreground));
}

.login-captcha-refresh {
  transition:
    color 0.2s,
    transform 0.2s;
}

.login-captcha-button:hover:not(:disabled) .login-captcha-refresh,
.login-captcha-button:focus-visible .login-captcha-refresh {
  color: hsl(var(--primary));
  transform: rotate(90deg);
}

.login-captcha-spinner {
  color: hsl(var(--primary));
  animation: login-spin 1s linear infinite;
}

.login-captcha-placeholder {
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}

.login-error {
  margin-bottom: 20px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--el-color-danger);
}

.ersync-login .login-submit {
  width: 100%;
  height: var(--login-field-height);
  margin-top: 4px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 2px;
  border-radius: 10px;
}

@keyframes login-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 480px) {
  .login-title {
    font-size: 28px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-captcha-refresh {
    transition: none;
  }
}
</style>
