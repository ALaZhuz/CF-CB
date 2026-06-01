package org.example.model.dto.response;

import lombok.Data;

/**
 * Codebeamer条目历史变更DTO
 *
 * 表示条目一次修改中的单个字段变更。
 *
 * @author system
 * @since 1.0
 */
@Data
public class CBHistoryChange {

    /** 变更类型（如 TrackerItemChange） */
    private String type;

    /** 变更字段信息 */
    private FieldInfo field;

    /** 字段名称 */
    private String name;

    /** 变更前的值 */
    private ValueInfo oldValue;

    /** 变更后的值 */
    private ValueInfo newValue;

    /**
     * 字段信息类
     */
    @Data
    public static class FieldInfo {
        /** 字段ID */
        private Integer id;

        /** 字段名称 */
        private String name;

        /** 字段类型 */
        private String type;

        /** Tracker ID */
        private Integer trackerId;
    }

    /**
     * 字段值信息类
     */
    @Data
    public static class ValueInfo {
        /** 字段ID */
        private Integer fieldId;

        /** 字段名称 */
        private String name;

        /** 值列表（用于选择类型字段） */
        private java.util.List<ValueItem> values;

        /** 字段类型 */
        private String type;
    }

    /**
     * 值项类（选择选项）
     */
    @Data
    public static class ValueItem {
        /** 选项ID */
        private Integer id;

        /** 选项名称 */
        private String name;

        /** 选项类型 */
        private String type;
    }
}