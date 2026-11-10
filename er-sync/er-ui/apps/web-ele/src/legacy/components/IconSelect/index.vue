<script setup>
import { computed, ref } from 'vue';
import SvgIcon from '../SvgIcon/index.vue';
const query = ref(''), emit = defineEmits(['selected']);
defineProps({ activeIcon: String });
const icons = Object.keys(import.meta.glob('../../assets/icons/svg/*.svg')).map(file => file.split('/').pop().replace('.svg', ''));
const filtered = computed(() => icons.filter(icon => icon.includes(query.value)));
defineExpose({ reset: () => { query.value = ''; } });
</script>
<template><div><el-input v-model="query" placeholder="搜索图标" clearable /><div class="icons"><button v-for="icon in filtered" :key="icon" type="button" :title="icon" :class="{ active: icon === activeIcon }" @click="emit('selected', icon)"><SvgIcon :icon-class="icon" /><span>{{ icon }}</span></button></div></div></template>
<style scoped>.icons{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;max-height:260px;overflow:auto;margin-top:12px}.icons button{display:flex;align-items:center;gap:6px;border:1px solid var(--el-border-color);padding:8px;border-radius:4px;font-size:12px;overflow:hidden}.icons button.active{color:var(--el-color-primary)}</style>
