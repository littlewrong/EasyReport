<script setup>
import { computed } from 'vue';
const props = defineProps({ options: { type: Array, default: () => [] }, value: [Number, String, Array, Boolean], showValue: { type: Boolean, default: true }, separator: { type: String, default: ',' } });
const values = computed(() => props.value == null || props.value === '' ? [] : (Array.isArray(props.value) ? props.value : String(props.value).split(props.separator)).map(String));
const items = computed(() => values.value.map(value => props.options.find(item => String(item.value) === value) || { label: props.showValue ? value : '', raw: {} }));
function tagType(item) { const type = item.raw?.listClass; return ['success', 'warning', 'danger', 'info', 'primary'].includes(type) ? type : 'primary'; }
</script>
<template><span class="dict-tags"><template v-for="(item, index) in items" :key="index"><el-tag v-if="item.raw?.listClass && item.raw.listClass !== 'default'" :type="tagType(item)" :class="item.raw.cssClass">{{ item.label }}</el-tag><span v-else :class="item.raw?.cssClass">{{ item.label }}</span></template></span></template>
<style scoped>.dict-tags { display:inline-flex;gap:6px;flex-wrap:wrap; }</style>
