import { describe, expect, it } from 'vitest';
import { compileScript, compileTemplate, parse } from 'vue/compiler-sfc';

import {
  buildModel,
  createField,
  fieldTypes,
  generateVueCode,
} from '../apps/web-ele/src/views/tool/build/form-schema';

const config = {
  labelPosition: 'right' as const,
  labelWidth: 100,
  size: 'default' as const,
};

function compile(source: string) {
  const { descriptor, errors } = parse(source);
  expect(errors).toEqual([]);
  const script = compileScript(descriptor, { id: 'generated-form' });
  const template = compileTemplate({
    id: 'generated-form',
    filename: 'GeneratedForm.vue',
    source: descriptor.template!.content,
    compilerOptions: { bindingMetadata: script.bindings },
  });
  expect(template.errors).toEqual([]);
  return { descriptor, script, template };
}

describe('Vue 3 form export', () => {
  it('compiles a complete form containing every palette control', () => {
    const fields = fieldTypes.map((item, index) => ({
      ...createField(item.type, index + 1),
      required: true,
    }));
    const { script } = compile(generateVueCode(fields, config));
    expect(script.content).toContain('ElForm');
    expect(Object.keys(buildModel(fields))).toHaveLength(fieldTypes.length - 1);
  });

  it('keeps user text literal in exported script and template', () => {
    const input = createField('input', 1);
    input.defaultValue = '</script><template>broken</template>';
    input.required = true;
    input.label = '字符 "<>& {{label}}';
    const radio = createField('radio', 2);
    radio.options = '{{notAnExpression}}|value';
    const divider = createField('divider', 3);
    divider.label = '{{plain text}}';
    const { template } = compile(
      generateVueCode([input, radio, divider], config),
    );
    expect(template.code).not.toContain('_ctx.notAnExpression');
    expect(template.code).not.toContain('_ctx.plain');
  });

  it('separates array defaults between preview and stored schema', () => {
    const field = createField('checkbox', 1);
    const model = buildModel([field]);
    model.field1.push('1');
    expect(field.defaultValue).toEqual([]);
    expect(buildModel([field]).field1).toEqual([]);
  });
});
