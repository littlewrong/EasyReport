<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';

import { ElMessage, ElMessageBox } from 'element-plus';

import FieldControl from './field-control.vue';
import {
  buildModel,
  createField,
  fieldTypes,
  generateVueCode,
} from './form-schema';
import type { DesignerConfig, DesignerField } from './form-schema';

defineOptions({ name: 'FormBuilder' });
const storageKey = 'er-sync-form-designer-v1';
const fields = ref<DesignerField[]>([]);
const config = reactive<DesignerConfig>({
  labelPosition: 'right',
  labelWidth: 100,
  size: 'default',
});
try {
  const saved = JSON.parse(localStorage.getItem(storageKey) || 'null');
  if (saved && Array.isArray(saved.fields)) {
    fields.value = saved.fields
      .filter((field: DesignerField) =>
        fieldTypes.some((item) => item.type === field.type),
      )
      .map((field: DesignerField) => ({
        ...createField(field.type, field.id),
        ...field,
      }));
    if (saved.config) Object.assign(config, saved.config);
  }
} catch {
  /* Ignore obsolete or unavailable local drafts. */
}

let nextId = Math.max(0, ...fields.value.map((field) => field.id)) + 1;
const selectedId = ref<number | null>(fields.value[0]?.id ?? null);
const selected = computed(() =>
  fields.value.find((field) => field.id === selectedId.value),
);
const designValues = reactive<Record<string, any>>(buildModel(fields.value));
const previewValues = ref<Record<string, any>>({});
const previewVisible = ref(false);
const codeVisible = ref(false);
const settingsTab = ref('field');
const previewRef = ref();
const previewResult = ref('');
const code = computed(() => generateVueCode(fields.value, config));
const isOptions = computed(
  () =>
    selected.value &&
    ['select', 'radio', 'checkbox'].includes(selected.value.type),
);
const previewRules = computed(() =>
  Object.fromEntries(
    fields.value
      .filter((field) => field.required)
      .map((field) => [
        field.model,
        [
          {
            required: true,
            message: `请填写${field.label}`,
            trigger: ['blur', 'change'],
          },
        ],
      ]),
  ),
);

watch(
  [fields, config],
  () => {
    try {
      localStorage.setItem(
        storageKey,
        JSON.stringify({ fields: fields.value, config }),
      );
    } catch {
      /* Storage may be full or disabled. */
    }
  },
  { deep: true },
);

function addField(type: string, position = fields.value.length) {
  const field = createField(type, nextId++);
  fields.value.splice(position, 0, field);
  designValues[field.model] = buildModel([field])[field.model];
  selectedId.value = field.id;
  settingsTab.value = 'field';
}

function removeField(index: number) {
  const [field] = fields.value.splice(index, 1);
  if (field?.id === selectedId.value)
    selectedId.value =
      fields.value[index]?.id ?? fields.value[index - 1]?.id ?? null;
}

function duplicateField(index: number) {
  const original = fields.value[index];
  if (!original) return;
  const field = JSON.parse(JSON.stringify(original)) as DesignerField;
  field.id = nextId++;
  field.model = `field${field.id}`;
  fields.value.splice(index + 1, 0, field);
  designValues[field.model] = buildModel([field])[field.model];
  selectedId.value = field.id;
}

function moveField(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= fields.value.length) return;
  const [field] = fields.value.splice(index, 1);
  if (field) fields.value.splice(target, 0, field);
}

function dragStart(event: DragEvent, value: string) {
  event.dataTransfer?.setData('text/plain', value);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copyMove';
}

function dropField(event: DragEvent, target = fields.value.length) {
  const [kind, value] = (event.dataTransfer?.getData('text/plain') || '').split(
    ':',
  );
  if (kind === 'palette' && fieldTypes.some((item) => item.type === value))
    addField(value!, target);
  if (kind === 'field') {
    const index = fields.value.findIndex((field) => field.id === Number(value));
    if (index < 0) return;
    const [field] = fields.value.splice(index, 1);
    if (field)
      fields.value.splice(index < target ? target - 1 : target, 0, field);
  }
}

function validateFields() {
  const models = fields.value
    .filter((field) => field.type !== 'divider')
    .map((field) => field.model);
  if (
    models.some((model) => !/^[A-Za-z_$][\w$]*$/.test(model)) ||
    new Set(models).size !== models.length
  ) {
    ElMessage.warning('字段名需为不重复的有效变量名，以字母、下划线或 $ 开头');
    return false;
  }
  return true;
}

function preview() {
  if (!validateFields()) return;
  previewValues.value = buildModel(fields.value);
  previewResult.value = '';
  previewVisible.value = true;
}

async function submitPreview() {
  if (await previewRef.value?.validate().catch(() => false)) {
    previewResult.value = JSON.stringify(previewValues.value, null, 2);
    ElMessage.success('校验通过');
  }
}

function download(
  content: string,
  filename: string,
  type = 'text/plain;charset=utf-8',
) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function exportVue() {
  if (validateFields()) download(code.value, 'GeneratedForm.vue');
}

async function copyCode() {
  if (!validateFields()) return;
  try {
    await navigator.clipboard.writeText(code.value);
    ElMessage.success('Vue 3 代码已复制');
  } catch {
    codeVisible.value = true;
    ElMessage.info('可在代码窗口中选中并复制');
  }
}

async function clearFields() {
  try {
    await ElMessageBox.confirm('确定清空当前表单设计？', '清空表单', {
      type: 'warning',
    });
    fields.value = [];
    selectedId.value = null;
  } catch {
    /* User cancelled. */
  }
}
</script>

<template>
  <div class="form-designer">
    <header class="designer-toolbar">
      <div>
        <h2>表单构建</h2>
        <span>拖拽设计表单，生成 Vue 3 + Element Plus 代码</span>
      </div>
      <div class="toolbar-actions">
        <el-button @click="preview">预览</el-button>
        <el-button @click="copyCode">复制代码</el-button>
        <el-button
          @click="
            download(
              JSON.stringify({ fields, config }, null, 2),
              'form-schema.json',
              'application/json',
            )
          "
          >导出配置</el-button
        >
        <el-button type="primary" @click="exportVue">导出 Vue 文件</el-button>
        <el-button type="danger" plain @click="clearFields">清空</el-button>
      </div>
    </header>
    <div class="designer-body">
      <aside class="designer-palette">
        <h3>表单组件</h3>
        <p>点击添加，或拖入画布</p>
        <div class="palette-list">
          <button
            v-for="item in fieldTypes"
            :key="item.type"
            draggable="true"
            type="button"
            @click="addField(item.type)"
            @dragstart="dragStart($event, `palette:${item.type}`)"
          >
            {{ item.label }}
          </button>
        </div>
      </aside>

      <main
        class="designer-canvas"
        @dragover.prevent
        @drop.prevent="dropField($event)"
      >
        <div v-if="!fields.length" class="empty-canvas">
          <strong>从这里开始设计</strong>
          <p>从左侧添加组件，在右侧调整字段属性</p>
        </div>
        <el-form
          :model="designValues"
          :label-width="`${config.labelWidth}px`"
          :label-position="config.labelPosition"
          :size="config.size"
          @submit.prevent
        >
          <el-row :gutter="16">
            <el-col
              v-for="(field, index) in fields"
              :key="field.id"
              :span="field.span"
            >
              <div
                class="canvas-field"
                :class="{ selected: selectedId === field.id }"
                draggable="true"
                tabindex="0"
                @click="
                  selectedId = field.id;
                  settingsTab = 'field';
                "
                @focus="selectedId = field.id"
                @dragstart.stop="dragStart($event, `field:${field.id}`)"
                @dragover.prevent.stop
                @drop.prevent.stop="dropField($event, index)"
              >
                <div class="field-actions" @click.stop>
                  <el-button
                    text
                    size="small"
                    :disabled="index === 0"
                    @click="moveField(index, -1)"
                    >上移</el-button
                  >
                  <el-button
                    text
                    size="small"
                    :disabled="index === fields.length - 1"
                    @click="moveField(index, 1)"
                    >下移</el-button
                  >
                  <el-button text size="small" @click="duplicateField(index)"
                    >复制</el-button
                  >
                  <el-button
                    text
                    size="small"
                    type="danger"
                    @click="removeField(index)"
                    >删除</el-button
                  >
                </div>
                <el-divider v-if="field.type === 'divider'">{{
                  field.label
                }}</el-divider>
                <el-form-item
                  v-else
                  :label="field.label"
                  :required="field.required"
                >
                  <FieldControl
                    v-model="designValues[field.model]"
                    :field="field"
                  />
                </el-form-item>
                <span class="field-name">{{ field.model }}</span>
              </div>
            </el-col>
          </el-row>
        </el-form>
      </main>

      <aside class="designer-settings">
        <el-tabs v-model="settingsTab">
          <el-tab-pane label="字段属性" name="field">
            <el-form v-if="selected" label-position="top" size="small">
              <el-form-item label="字段类型"
                ><el-input
                  :model-value="
                    fieldTypes.find((item) => item.type === selected?.type)
                      ?.label
                  "
                  disabled
              /></el-form-item>
              <el-form-item label="标签名称"
                ><el-input v-model="selected.label"
              /></el-form-item>
              <el-form-item label="字段名"
                ><el-input v-model="selected.model"
              /></el-form-item>
              <el-form-item label="占据列数"
                ><el-slider
                  v-model="selected.span"
                  :min="1"
                  :max="24"
                  show-input
              /></el-form-item>
              <template v-if="selected.type !== 'divider'">
                <el-form-item label="占位文字"
                  ><el-input v-model="selected.placeholder"
                /></el-form-item>
                <el-form-item v-if="selected.type === 'switch'" label="默认值"
                  ><el-switch v-model="selected.defaultValue"
                /></el-form-item>
                <el-form-item
                  v-else-if="
                    ['number', 'slider', 'rate'].includes(selected.type)
                  "
                  label="默认值"
                  ><el-input-number v-model="selected.defaultValue"
                /></el-form-item>
                <el-form-item
                  v-else-if="!['checkbox', 'daterange'].includes(selected.type)"
                  label="默认值"
                  ><el-input v-model="selected.defaultValue"
                /></el-form-item>
                <el-form-item v-if="isOptions" label="选项（每行：名称|值）"
                  ><el-input
                    v-model="selected.options"
                    type="textarea"
                    :rows="5"
                /></el-form-item>
                <template v-if="['number', 'slider'].includes(selected.type)">
                  <el-form-item label="最小值"
                    ><el-input-number v-model="selected.min"
                  /></el-form-item>
                  <el-form-item label="最大值"
                    ><el-input-number
                      v-model="selected.max"
                      :min="selected.min"
                  /></el-form-item>
                </template>
                <el-form-item label="必填"
                  ><el-switch v-model="selected.required"
                /></el-form-item>
                <el-form-item label="禁用"
                  ><el-switch v-model="selected.disabled"
                /></el-form-item>
                <el-form-item label="可清空"
                  ><el-switch v-model="selected.clearable"
                /></el-form-item>
              </template>
            </el-form>
            <el-empty
              v-else
              description="请选择画布中的字段"
              :image-size="72"
            />
          </el-tab-pane>
          <el-tab-pane label="表单属性" name="form">
            <el-form label-position="top" size="small">
              <el-form-item label="标签对齐"
                ><el-select v-model="config.labelPosition"
                  ><el-option label="右对齐" value="right" /><el-option
                    label="左对齐"
                    value="left" /><el-option
                    label="顶部对齐"
                    value="top" /></el-select
              ></el-form-item>
              <el-form-item label="标签宽度"
                ><el-input-number
                  v-model="config.labelWidth"
                  :min="0"
                  :max="300"
                  :step="10"
              /></el-form-item>
              <el-form-item label="组件尺寸"
                ><el-select v-model="config.size"
                  ><el-option label="默认" value="default" /><el-option
                    label="大"
                    value="large" /><el-option
                    label="小"
                    value="small" /></el-select
              ></el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </div>

    <el-dialog
      v-model="previewVisible"
      title="表单预览"
      width="760px"
      append-to-body
      destroy-on-close
    >
      <el-form
        ref="previewRef"
        :model="previewValues"
        :rules="previewRules"
        :label-width="`${config.labelWidth}px`"
        :label-position="config.labelPosition"
        :size="config.size"
        @submit.prevent
      >
        <el-row :gutter="16">
          <el-col v-for="field in fields" :key="field.id" :span="field.span">
            <el-divider v-if="field.type === 'divider'">{{
              field.label
            }}</el-divider>
            <el-form-item v-else :label="field.label" :prop="field.model"
              ><FieldControl
                v-model="previewValues[field.model]"
                :field="field"
            /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item
          ><el-button type="primary" @click="submitPreview">提交</el-button
          ><el-button
            @click="
              previewValues = buildModel(fields);
              previewResult = '';
              previewRef?.clearValidate();
            "
            >重置</el-button
          ></el-form-item
        >
      </el-form>
      <pre v-if="previewResult" class="preview-result">{{ previewResult }}</pre>
    </el-dialog>
    <el-dialog
      v-model="codeVisible"
      title="生成的 Vue 3 代码"
      width="85%"
      append-to-body
    >
      <el-input :model-value="code" type="textarea" :rows="24" readonly />
    </el-dialog>
  </div>
</template>

<style scoped>
.form-designer {
  margin: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  overflow: hidden;
}
.designer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--el-border-color-light);
  flex-wrap: wrap;
}
.designer-toolbar h2 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}
.designer-toolbar span,
.designer-palette p {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.toolbar-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.toolbar-actions .el-button {
  margin-left: 0;
}
.designer-body {
  display: grid;
  grid-template-columns: 210px minmax(320px, 1fr) 280px;
  min-height: 650px;
}
.designer-palette,
.designer-settings {
  padding: 16px;
}
.designer-palette {
  border-right: 1px solid var(--el-border-color-light);
}
.designer-palette h3 {
  font-weight: 600;
  margin-bottom: 6px;
}
.palette-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 18px;
}
.palette-list button {
  padding: 10px 4px;
  font-size: 12px;
  cursor: grab;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  color: var(--el-text-color-primary);
}
.palette-list button:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}
.designer-canvas {
  padding: 20px;
  background: var(--el-fill-color-lighter);
  position: relative;
  min-height: 650px;
}
.empty-canvas {
  padding: 100px 12px;
  text-align: center;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
}
.empty-canvas strong {
  color: var(--el-text-color-primary);
  font-size: 16px;
}
.empty-canvas p {
  margin-top: 12px;
  font-size: 13px;
}
.canvas-field {
  padding: 38px 12px 22px;
  margin-bottom: 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  position: relative;
  background: var(--el-bg-color);
  cursor: grab;
}
.canvas-field.selected {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7);
}
.canvas-field :deep(.el-form-item) {
  margin-bottom: 0;
}
.field-actions {
  position: absolute;
  top: 3px;
  right: 5px;
  display: flex;
}
.field-actions .el-button {
  margin-left: 0;
  padding: 5px;
}
.field-name {
  position: absolute;
  bottom: 4px;
  right: 10px;
  font-size: 10px;
  color: var(--el-text-color-placeholder);
}
.designer-settings {
  border-left: 1px solid var(--el-border-color-light);
}
.designer-settings :deep(.el-form-item) {
  margin-bottom: 16px;
}
.preview-result {
  background: var(--el-fill-color-light);
  padding: 16px;
  overflow: auto;
  border-radius: 4px;
}
@media (max-width: 1200px) {
  .designer-body {
    grid-template-columns: 170px minmax(240px, 1fr) 240px;
  }
}
@media (max-width: 900px) {
  .designer-body {
    grid-template-columns: 1fr;
  }
  .designer-palette,
  .designer-settings {
    border: 0;
  }
  .palette-list {
    grid-template-columns: repeat(4, 1fr);
  }
  .designer-canvas {
    min-height: 320px;
  }
}
</style>
