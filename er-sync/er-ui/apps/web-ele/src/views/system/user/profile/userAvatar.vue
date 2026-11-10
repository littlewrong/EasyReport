<script setup>
import { apiBaseURL } from '#/api/request';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useUserStore } from '@vben/stores';
import { ElMessage } from 'element-plus';
import { uploadAvatar } from '#/legacy/api/system/user';
const userStore = useUserStore();
const open = ref(false),
  canvas = ref(),
  zoom = ref(1),
  uploading = ref(false);
let image, objectURL;
const avatar = computed(() => userStore.userInfo?.avatar || '');
function draw() {
  if (!image || !canvas.value) return;
  const context = canvas.value.getContext('2d'),
    side = Math.min(image.naturalWidth, image.naturalHeight) / zoom.value;
  context.clearRect(0, 0, 240, 240);
  context.drawImage(
    image,
    (image.naturalWidth - side) / 2,
    (image.naturalHeight - side) / 2,
    side,
    side,
    0,
    0,
    240,
    240,
  );
}
async function select(event) {
  const file = event.target.files[0];
  if (!file) return;
  if (!file.type.startsWith('image/') || file.size > 5 * 1024 * 1024) {
    ElMessage.error('请选择不超过5MB的图片');
    return;
  }
  if (objectURL) URL.revokeObjectURL(objectURL);
  objectURL = URL.createObjectURL(file);
  image = new Image();
  image.onload = async () => {
    zoom.value = 1;
    open.value = true;
    await nextTick();
    draw();
  };
  image.src = objectURL;
  event.target.value = '';
}
async function save() {
  uploading.value = true;
  try {
    const blob = await new Promise((resolve) =>
      canvas.value.toBlob(resolve, 'image/jpeg', 0.92),
    );
    const body = new FormData();
    body.append('avatarfile', blob, 'avatar.jpg');
    const result = await uploadAvatar(body),
      src = result.imgUrl?.startsWith('http')
        ? result.imgUrl
        : `${apiBaseURL}${result.imgUrl}`;
    userStore.setUserInfo({ ...userStore.userInfo, avatar: src });
    open.value = false;
    ElMessage.success('头像修改成功');
  } finally {
    uploading.value = false;
  }
}
watch(zoom, draw);
onBeforeUnmount(() => {
  if (objectURL) URL.revokeObjectURL(objectURL);
});
</script>
<template>
  <div>
    <label class="avatar-picker" title="点击修改头像"
      ><el-avatar :size="110" :src="avatar">用户</el-avatar
      ><input type="file" accept="image/*" hidden @change="select" /><span
        class="avatar-edit-hint"
        >点击更换头像</span
      ></label
    ><el-drawer
      class="bi-management-drawer"
      v-model="open"
      title="修改头像"
      size="420px"
      append-to-body
      @opened="draw"
      ><canvas
        ref="canvas"
        width="240"
        height="240"
        style="margin: auto; border-radius: 50%; display: block"
      ></canvas
      ><el-slider v-model="zoom" :min="1" :max="4" :step="0.05" /><template
        #footer
        ><el-button @click="open = false">取消</el-button
        ><el-button type="primary" :loading="uploading" @click="save"
          >保存</el-button
        ></template
      ></el-drawer
    >
  </div>
</template>
<style scoped>
.avatar-picker {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}
.avatar-edit-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
