package org.example.workflow.config;

import lombok.Data;

/**
 * 额外字段配置类
 *
 * 定义消息中额外显示的字段，动态插入到链接行之前。
 * 格式：{label}: {value}
 *
 * @author system
 * @since 1.0
 */
@Data
public class ExtraField {

    /**
     * 字段名称
     *
     * 与Codebeamer tracker item中的字段名对应。
     * 可以是内置字段（如priority、severity）或自定义字段名。
     */
    private String field;

    /**
     * 显示标签
     *
     * 在消息中显示的字段名称，如"优先级"、"严重程度"等。
     */
    private String label;
}