import { defineComponent, h } from 'vue';
export const Splitpanes = defineComponent({ props: { horizontal: Boolean }, setup(props, { slots }) { return () => h('div', { class: 'legacy-splitpanes', style: { display: 'flex', flexDirection: props.horizontal ? 'column' : 'row', gap: '20px', minWidth: 0 } }, slots.default?.()); } });
export const Pane = defineComponent({ props: { size: [Number, String] }, setup(props, { slots }) { return () => h('section', { style: { flex: `${props.size || 1} 1 0`, minWidth: 0 } }, slots.default?.()); } });
