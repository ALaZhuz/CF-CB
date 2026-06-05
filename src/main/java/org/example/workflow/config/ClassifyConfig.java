package org.example.workflow.config;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类配置类
 *
 * 定义定时通知的分类规则配置。
 * 三级优先级：tracker级 > 项目级 > 全局级
 *
 * @author system
 * @since 1.0
 */
@Data
public class ClassifyConfig {

    /** 分类字段名称，用于从条目中提取分类值 */
    private String classifyField;

    /** 分类规则列表，按分类值匹配不同的通知频率 */
    private List<ClassifyRule> classifyRules = new ArrayList<>();
}