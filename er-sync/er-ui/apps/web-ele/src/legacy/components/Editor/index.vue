<script setup>
import { apiBaseURL } from '#/api/request';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import Quill from 'quill';
import 'quill/dist/quill.snow.css';
import request from '../../utils/request';
const props = defineProps({ modelValue: { type: String, default: '' }, height: Number, minHeight: Number, readOnly: Boolean, fileSize: { type: Number, default: 5 }, type: { type: String, default: 'url' } });
const emit = defineEmits(['update:modelValue', 'on-change']);
const editor = ref(), input = ref();
let quill;
async function insertImage(file) {
  if (!file?.type.startsWith('image/')) return;
  if (file.size > props.fileSize * 1024 * 1024) { ElMessage.error(`图片不能超过 ${props.fileSize} MB`); return; }
  const body = new FormData(); body.append('file', file);
  try {
    const result = await request({ url: '/common/upload', method: 'post', data: body });
    const index = quill.getSelection(true)?.index ?? quill.getLength();
    quill.insertEmbed(index, 'image', result.url || `${apiBaseURL}${result.fileName}`); quill.setSelection(index + 1);
  } finally { if (input.value) input.value.value = ''; }
}
function paste(event) {
  if (props.type !== 'url') return;
  const item = [...(event.clipboardData?.items || [])].find(entry => entry.type.startsWith('image/'));
  if (item) { event.preventDefault(); insertImage(item.getAsFile()); }
}
onMounted(() => {
  quill = new Quill(editor.value, { theme: 'snow', readOnly: props.readOnly, placeholder: '请输入内容', modules: { toolbar: [[{ header: [1, 2, 3, false] }], ['bold', 'italic', 'underline', 'strike'], [{ list: 'ordered' }, { list: 'bullet' }], [{ color: [] }, { background: [] }], [{ align: [] }], ['link', 'image', 'video'], ['clean']] } });
  quill.clipboard.dangerouslyPasteHTML(props.modelValue || '');
  quill.on('text-change', () => { const html = quill.root.innerHTML; emit('update:modelValue', html); emit('on-change', { html, text: quill.getText(), quill }); });
  if (props.type === 'url') quill.getModule('toolbar').addHandler('image', () => input.value.click());
  quill.root.addEventListener('paste', paste, true);
});
watch(() => props.modelValue, value => { if (quill && value !== quill.root.innerHTML) quill.clipboard.dangerouslyPasteHTML(value || ''); });
onBeforeUnmount(() => { quill?.root.removeEventListener('paste', paste, true); quill = undefined; });
</script>
<template><div class="legacy-editor"><input ref="input" type="file" accept="image/*" hidden @change="insertImage($event.target.files[0])" /><div ref="editor" :style="{ height: height ? `${height}px` : undefined, minHeight: `${minHeight || 180}px` }"></div></div></template>
<style scoped>.legacy-editor{width:100%;line-height:normal}.legacy-editor :deep(.ql-editor){min-height:inherit}</style>
