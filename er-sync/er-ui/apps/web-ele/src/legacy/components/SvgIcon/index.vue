<script setup>
import { computed } from 'vue';
const props = defineProps({ iconClass: { type: String, default: '' }, className: String });
const sources = import.meta.glob('../../assets/icons/svg/*.svg', { query: '?raw', import: 'default', eager: true });
const svg = computed(() => sources[`../../assets/icons/svg/${props.iconClass}.svg`] || sources['../../assets/icons/svg/guide.svg'] || '');
</script>
<template><span class="legacy-svg" :class="className" aria-hidden="true" v-html="svg"></span></template>
<style scoped>.legacy-svg{display:inline-flex;width:1em;height:1em;vertical-align:-.15em;fill:currentColor}.legacy-svg :deep(svg){width:100%;height:100%}</style>
