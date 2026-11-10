export interface DesignerField {
  clearable: boolean;
  defaultValue: any;
  disabled: boolean;
  id: number;
  label: string;
  max: number;
  min: number;
  model: string;
  options: string;
  placeholder: string;
  required: boolean;
  span: number;
  type: string;
}

export interface DesignerConfig {
  labelPosition: 'left' | 'right' | 'top';
  labelWidth: number;
  size: 'default' | 'large' | 'small';
}

export const fieldTypes = [
  { label: '单行文本', type: 'input' },
  { label: '多行文本', type: 'textarea' },
  { label: '密码输入', type: 'password' },
  { label: '数字输入', type: 'number' },
  { label: '下拉选择', type: 'select' },
  { label: '单选框组', type: 'radio' },
  { label: '多选框组', type: 'checkbox' },
  { label: '开关', type: 'switch' },
  { label: '日期选择', type: 'date' },
  { label: '日期时间', type: 'datetime' },
  { label: '日期范围', type: 'daterange' },
  { label: '时间选择', type: 'time' },
  { label: '滑块', type: 'slider' },
  { label: '评分', type: 'rate' },
  { label: '颜色选择', type: 'color' },
  { label: '分割线', type: 'divider' },
];

export function fieldDefault(type: string): any {
  if (type === 'checkbox' || type === 'daterange') return [];
  if (type === 'switch') return false;
  if (['number', 'slider', 'rate'].includes(type)) return 0;
  return '';
}

export function createField(type: string, id: number): DesignerField {
  const label = fieldTypes.find((item) => item.type === type)?.label || '文本';
  return {
    clearable: true,
    defaultValue: fieldDefault(type),
    disabled: false,
    id,
    label,
    max: 100,
    min: 0,
    model: `field${id}`,
    options: '选项一|1\n选项二|2',
    placeholder: `请${['select', 'date', 'time', 'datetime', 'daterange'].includes(type) ? '选择' : '输入'}${label}`,
    required: false,
    span: 24,
    type,
  };
}

export function buildModel(fields: DesignerField[]): Record<string, any> {
  return Object.fromEntries(
    fields
      .filter((field) => field.type !== 'divider')
      .map((field) => [
        field.model,
        Array.isArray(field.defaultValue)
          ? [...field.defaultValue]
          : field.defaultValue,
      ]),
  );
}

function escapeAttribute(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function escapeText(value: string) {
  return escapeAttribute(value)
    .replaceAll('{', '&#123;')
    .replaceAll('}', '&#125;');
}

function scriptJson(value: unknown) {
  return JSON.stringify(value, null, 2).replaceAll('<', '\\u003c');
}

/** Generate a standalone Vue 3 + Element Plus single-file component. */
export function generateVueCode(
  fields: DesignerField[],
  config: DesignerConfig,
) {
  const rules = Object.fromEntries(
    fields
      .filter((field) => field.required && field.type !== 'divider')
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
  );
  const items = fields
    .map((field) => {
      const label = escapeAttribute(field.label);
      if (field.type === 'divider')
        return `      <el-col :span="${field.span}"><el-divider>${escapeText(field.label)}</el-divider></el-col>`;
      const binding = `v-model="form.${field.model}"`;
      const disabled = field.disabled ? ' disabled' : '';
      const clearable = field.clearable ? ' clearable' : '';
      const placeholder = ` placeholder="${escapeAttribute(field.placeholder)}"`;
      let control = '';
      if (['input', 'textarea', 'password'].includes(field.type)) {
        const type = field.type === 'input' ? '' : ` type="${field.type}"`;
        control = `<el-input ${binding}${type}${placeholder}${disabled}${clearable}${field.type === 'textarea' ? ' :rows="3"' : ''}${field.type === 'password' ? ' show-password' : ''} />`;
      } else if (['select', 'radio', 'checkbox'].includes(field.type)) {
        const tag = {
          select: 'el-select',
          radio: 'el-radio-group',
          checkbox: 'el-checkbox-group',
        }[field.type];
        const options = field.options
          .split('\n')
          .filter(Boolean)
          .map((line) => {
            const [text, optionValue] = line.split('|');
            const value = escapeAttribute(optionValue ?? text ?? '');
            const optionLabel = escapeAttribute(text || '');
            return field.type === 'select'
              ? `            <el-option label="${optionLabel}" value="${value}" />`
              : `            <el-${field.type} value="${value}">${escapeText(text || '')}</el-${field.type}>`;
          })
          .join('\n');
        control = `<${tag} ${binding}${disabled}${field.type === 'select' ? `${placeholder}${clearable} style="width: 100%"` : ''}>\n${options}\n          </${tag}>`;
      } else if (['date', 'datetime', 'daterange'].includes(field.type)) {
        control = `<el-date-picker ${binding} type="${field.type}" value-format="${field.type === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'}"${placeholder}${disabled}${clearable} style="width: 100%" />`;
      } else if (field.type === 'time') {
        control = `<el-time-picker ${binding} value-format="HH:mm:ss"${placeholder}${disabled}${clearable} />`;
      } else {
        const tag =
          {
            number: 'el-input-number',
            switch: 'el-switch',
            slider: 'el-slider',
            rate: 'el-rate',
            color: 'el-color-picker',
          }[field.type] || 'el-input';
        const range = ['number', 'slider'].includes(field.type)
          ? ` :min="${field.min}" :max="${field.max}"`
          : '';
        control = `<${tag} ${binding}${disabled}${range} />`;
      }
      return `      <el-col :span="${field.span}">\n        <el-form-item label="${label}" prop="${field.model}">\n          ${control}\n        </el-form-item>\n      </el-col>`;
    })
    .join('\n');

  // Split the closing script tag so this helper can also be embedded in an SFC.
  return (
    `<script setup>\nimport { reactive, ref } from 'vue';\nimport { ElButton, ElCheckbox, ElCheckboxGroup, ElCol, ElColorPicker, ElDatePicker, ElDivider, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage, ElOption, ElRadio, ElRadioGroup, ElRate, ElRow, ElSelect, ElSlider, ElSwitch, ElTimePicker } from 'element-plus';\nimport 'element-plus/dist/index.css';\n\nconst formRef = ref();\nconst form = reactive(${scriptJson(buildModel(fields))});\nconst rules = ${scriptJson(rules)};\n\nasync function submit() {\n  if (await formRef.value.validate().catch(() => false)) {\n    ElMessage.success('校验通过，请在此连接提交接口');\n    console.log({ ...form });\n  }\n}\n</scr` +
    `ipt>\n\n<template>\n  <el-form ref="formRef" :model="form" :rules="rules" label-width="${config.labelWidth}px" label-position="${config.labelPosition}" size="${config.size}">\n    <el-row :gutter="20">\n${items}\n      <el-col :span="24">\n        <el-form-item>\n          <el-button type="primary" @click="submit">提交</el-button>\n          <el-button @click="formRef.resetFields()">重置</el-button>\n        </el-form-item>\n      </el-col>\n    </el-row>\n  </el-form>\n</template>\n`
  );
}
