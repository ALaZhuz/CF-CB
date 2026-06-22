package org.example.workflow.config;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流模板配置类
 *
 * 定义一个工作流的状态通知规则。
 * 可用于全局工作流模板或项目内工作流模板。
 *
 * @author system
 * @since 1.0
 */
@Data
public class WorkflowTemplate {

    /** 工作流模板名称，用于tracker引用 */
    private String name;

    /** 状态配置列表 */
    private List<StateConfig> states = new ArrayList<>();

    /**
     * 状态配置类
     *
     * 定义某个状态的通知规则。
     * notify-field：指定通知字段，发送通知给该字段的成员。
     * notify: false：明确声明不发送通知，但不阻止保存。
     * 未配置：未在states列表中出现的状态，将阻止保存并提示错误。
     */
    @Data
    public static class StateConfig {

        /** 状态名称，与Codebeamer中的状态名称对应 */
        private String name;

        /** 即时通知字段列表（单值或列表） */
        private List<String> notifyField;

        /** 定时通知字段列表（可选，默认继承 notifyField） */
        private List<String> scheduledNotifyField;

        /** 是否发送通知：false表示明确不通知 */
        private Boolean notify;

        /** 是否启用定时通知：true表示开启定时通知 */
        private Boolean scheduledNotify;
    }
}