package cn.easyreport.sync.mapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类型映射规则
 *
 * 用于定义从源数据类型到目标数据类型的映射规则
 * 支持正则表达式匹配和参数提取替换
 *
 * 示例：
 * <pre>
 * // INT(11) → INT
 * new TypeMappingRule("INT\\(\\d+\\)", "INT", 50, false, "去除显示宽度");
 *
 * // VARCHAR(50) → VARCHAR(50)
 * new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 100, false, "保持长度");
 *
 * // TEXT → STRING
 * new TypeMappingRule("TEXT", "STRING", 130, false, "转为STRING类型");
 * </pre>
 */
public class TypeMappingRule {

    /** 源类型匹配模式（正则表达式） */
    private final String sourcePattern;

    /** 目标类型模板（可包含 $1, $2 等占位符） */
    private final String targetTemplate;

    /** 优先级（数字越小越优先匹配） */
    private final int priority;

    /** 是否有损转换 */
    private final boolean lossy;

    /** 规则说明 */
    private final String notes;

    /** 编译后的正则表达式 */
    private final Pattern compiledPattern;

    /**
     * 构造函数
     *
     * @param sourcePattern 源类型正则模式
     * @param targetTemplate 目标类型模板
     * @param priority 优先级（越小越优先）
     * @param lossy 是否有损转换
     * @param notes 规则说明
     */
    public TypeMappingRule(String sourcePattern, String targetTemplate, int priority, boolean lossy, String notes) {
        this.sourcePattern = sourcePattern;
        this.targetTemplate = targetTemplate;
        this.priority = priority;
        this.lossy = lossy;
        this.notes = notes;
        this.compiledPattern = Pattern.compile(sourcePattern, Pattern.CASE_INSENSITIVE);
    }

    /**
     * 检查源类型是否匹配此规则
     *
     * @param sourceType 源类型
     * @return true表示匹配
     */
    public boolean matches(String sourceType) {
        if (sourceType == null) {
            return false;
        }
        return compiledPattern.matcher(sourceType.trim()).matches();
    }

    /**
     * 应用规则，将源类型转换为目标类型
     *
     * @param sourceType 源类型
     * @return 目标类型
     */
    public String apply(String sourceType) {
        if (sourceType == null) {
            return null;
        }

        Matcher matcher = compiledPattern.matcher(sourceType.trim());
        if (!matcher.matches()) {
            return sourceType; // 不匹配则返回原类型
        }

        String result = targetTemplate;

        // 先替换 $0（完整匹配）
        if (result.contains("$0")) {
            result = result.replace("$0", matcher.group(0));
        }

        // 替换捕获组 $1, $2, ...
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            if (group != null) {
                result = result.replace("$" + i, group);
            }
        }

        return result;
    }

    // ==================== Getters ====================

    public String getSourcePattern() {
        return sourcePattern;
    }

    public String getTargetTemplate() {
        return targetTemplate;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isLossy() {
        return lossy;
    }

    public String getNotes() {
        return notes;
    }

    public Pattern getCompiledPattern() {
        return compiledPattern;
    }

    @Override
    public String toString() {
        return String.format("TypeMappingRule{pattern='%s', target='%s', priority=%d, lossy=%s, notes='%s'}",
            sourcePattern, targetTemplate, priority, lossy, notes);
    }
}
