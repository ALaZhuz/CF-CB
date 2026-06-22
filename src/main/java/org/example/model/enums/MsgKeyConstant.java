package org.example.model.enums;

/**
 * 钉钉机器人消息类型常量
 *
 * 用于 sendRobotMessage 方法的 msgKey 参数
 *
 * @author system
 * @since 1.0
 */
public class MsgKeyConstant {
    /**
     * 纯文本消息
     * msgParam 格式：{"content": "文本内容"}
     */
    public static final String SAMPLE_TEXT = "sampleText";

    /**
     * Markdown消息
     * msgParam 格式：{"title": "标题", "text": "Markdown内容"}
     */
    public static final String SAMPLE_MARKDOWN = "sampleMarkdown";
}