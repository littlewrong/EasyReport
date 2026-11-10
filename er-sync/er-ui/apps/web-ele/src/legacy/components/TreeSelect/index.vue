<script setup>
import { computed } from 'vue';
const props = defineProps({ modelValue: [Number, String, Array], options: { type: Array, default: () => [] }, normalizer: Function, multiple: Boolean });
const emit = defineEmits(['update:modelValue', 'select']);
function normalize(nodes) { return nodes.map(node => { const item = props.normalizer ? props.normalizer(node) : node; return { ...node, ...item, children: item.children?.length ? normalize(item.children) : undefined }; }); }
const data = computed(() => normalize(props.options));
</script>
<template><el-tree-select :model-value="modelValue" :data="data" node-key="id" :props="{ label: 'label', value: 'id', children: 'children' }" :multiple="multiple" filterable check-strictly clearable default-expand-all style="width:100%" @update:model-value="emit('update:modelValue', $event)" @node-click="emit('select', $event)" /></template>
