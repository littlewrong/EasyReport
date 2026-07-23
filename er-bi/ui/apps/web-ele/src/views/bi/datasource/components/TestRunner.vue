<script lang="ts" setup>
import { computed } from 'vue';

import { ElButton, ElInput } from 'element-plus';

const props = defineProps<{
  loading?: boolean;
  paramsText: string;
  paramsRows?: number;
  result?: any;
  resultRows?: number;
}>();

const emits = defineEmits<{
  run: [];
  'update:paramsText': [value: string];
}>();

const resultText = computed(() =>
  props.result === undefined ? '' : JSON.stringify(props.result, null, 2),
);
</script>

<template>
  <div class="rounded-md border p-4">
    <div class="mb-2 flex items-center justify-between">
      <span class="text-base font-semibold">测试运行</span>
      <ElButton type="primary" :loading="loading" @click="emits('run')">
        运行测试
      </ElButton>
    </div>
    <div class="mb-1 text-sm text-muted-foreground">测试入参 JSON</div>
    <ElInput
      :model-value="paramsText"
      type="textarea"
      :rows="paramsRows || 8"
      placeholder='{"keyword":"SQL"}'
      @update:model-value="(value) => emits('update:paramsText', String(value))"
    />
    <div v-if="resultText" class="mb-1 mt-3 text-sm text-muted-foreground">
      运行返回结果
    </div>
    <ElInput
      v-if="resultText"
      :model-value="resultText"
      type="textarea"
      :rows="resultRows || 18"
      readonly
    />
  </div>
</template>
