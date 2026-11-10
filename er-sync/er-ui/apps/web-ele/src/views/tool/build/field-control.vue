<script setup lang="ts">
import { computed } from 'vue';

import type { DesignerField } from './form-schema';

const props = defineProps<{ field: DesignerField; modelValue?: any }>();
const emit = defineEmits<{ 'update:modelValue': [value: any] }>();
const value = computed({
  get: () => props.modelValue,
  set: (next) => emit('update:modelValue', next),
});
const options = computed(() =>
  props.field.options
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [label, optionValue] = line.split('|');
      return { label: label || '', value: optionValue ?? label ?? '' };
    }),
);
</script>

<template>
  <el-input
    v-if="['input', 'textarea', 'password'].includes(field.type)"
    v-model="value"
    :type="field.type === 'input' ? 'text' : field.type"
    :placeholder="field.placeholder"
    :disabled="field.disabled"
    :clearable="field.clearable"
    :rows="3"
    :show-password="field.type === 'password'"
  />
  <el-input-number
    v-else-if="field.type === 'number'"
    v-model="value"
    :min="field.min"
    :max="field.max"
    :disabled="field.disabled"
  />
  <el-select
    v-else-if="field.type === 'select'"
    v-model="value"
    :placeholder="field.placeholder"
    :disabled="field.disabled"
    :clearable="field.clearable"
    style="width: 100%"
  >
    <el-option
      v-for="option in options"
      :key="option.value"
      :label="option.label"
      :value="option.value"
    />
  </el-select>
  <el-radio-group
    v-else-if="field.type === 'radio'"
    v-model="value"
    :disabled="field.disabled"
  >
    <el-radio
      v-for="option in options"
      :key="option.value"
      :value="option.value"
      >{{ option.label }}</el-radio
    >
  </el-radio-group>
  <el-checkbox-group
    v-else-if="field.type === 'checkbox'"
    v-model="value"
    :disabled="field.disabled"
  >
    <el-checkbox
      v-for="option in options"
      :key="option.value"
      :value="option.value"
      >{{ option.label }}</el-checkbox
    >
  </el-checkbox-group>
  <el-switch
    v-else-if="field.type === 'switch'"
    v-model="value"
    :disabled="field.disabled"
  />
  <el-date-picker
    v-else-if="['date', 'datetime', 'daterange'].includes(field.type)"
    v-model="value"
    :type="field.type"
    :placeholder="field.placeholder"
    start-placeholder="开始日期"
    end-placeholder="结束日期"
    :disabled="field.disabled"
    :clearable="field.clearable"
    :value-format="
      field.type === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'
    "
    style="width: 100%"
  />
  <el-time-picker
    v-else-if="field.type === 'time'"
    v-model="value"
    :placeholder="field.placeholder"
    :disabled="field.disabled"
    value-format="HH:mm:ss"
  />
  <el-slider
    v-else-if="field.type === 'slider'"
    v-model="value"
    :min="field.min"
    :max="field.max"
    :disabled="field.disabled"
  />
  <el-rate
    v-else-if="field.type === 'rate'"
    v-model="value"
    :disabled="field.disabled"
  />
  <el-color-picker
    v-else-if="field.type === 'color'"
    v-model="value"
    :disabled="field.disabled"
  />
</template>
