package org.example.model.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Codebeamer条目详情响应DTO
 *
 * 封装从Codebeamer API获取的单个tracker item的完整信息，
 * 用于beforeEvent校验和afterEvent通知处理。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ItemInfoResponse {

    /** 条目ID */
    private Integer id;

    /** 条目名称/标题（summary） */
    private String name;

    /** 条目描述 */
    private String description;

    /** 当前状态名称 */
    private String status;

    /** Tracker信息 */
    private TrackerInfo tracker;

    /** Tracker类型名称（如Bug、Requirement等） */
    private String trackerType;

    /** 项目信息 */
    private ProjectInfo project;

    /** 指派给成员列表（assignedTo字段） */
    private List<MemberInfo> assignedTo;

    /** 负责人列表（owners字段，对应supervisors） */
    private List<MemberInfo> owners;

    /** 提交人信息 */
    private MemberInfo submitter;

    /** 创建人信息 */
    private MemberInfo createdBy;

    /** 修改人信息 */
    private MemberInfo modifiedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 修改时间 */
    private LocalDateTime modifiedAt;

    /** 优先级（CodeBeamer默认字段） */
    private ChoiceOption priority;

    /** 分类列表（CodeBeamer默认字段） */
    private List<ChoiceOption> categories;

    /** 严重程度列表（CodeBeamer默认字段） */
    private List<ChoiceOption> severities;

    /** 团队列表（CodeBeamer默认字段） */
    private List<ChoiceOption> teams;

    /** 版本列表（CodeBeamer默认字段） */
    private List<ChoiceOption> versions;

    /** 自定义字段列表 */
    private List<CustomField> customFields;

    /** 条目链接（用于构建钉钉消息中的跳转链接） */
    private String itemLink;

    /**
     * Tracker信息类
     */
    @Data
    public static class TrackerInfo {
        /** Tracker ID */
        private Integer id;

        /** Tracker名称 */
        private String name;

        /** Tracker类型 */
        private String typeName;
    }

    /**
     * 项目信息类
     */
    @Data
    public static class ProjectInfo {
        /** 项目ID */
        private Integer id;

        /** 项目名称 */
        private String name;
    }

    /**
     * 成员信息类
     */
    @Data
    public static class MemberInfo {
        /** 用户ID（与钉钉userid对应） */
        private String userId;

        /** 用户显示名称 */
        private String name;

        /** 用户姓名 */
        private String displayName;

        /** 用户邮箱 */
        private String email;
    }

    /**
     * 选择选项类（用于priority、severity、categories等默认字段）
     */
    @Data
    public static class ChoiceOption {
        /** 选项ID */
        private Integer id;

        /** 选项名称 */
        private String name;

        /** 选项类型 */
        private String type;
    }

    /**
     * 自定义字段类
     */
    @Data
    public static class CustomField {
        /** 字段ID */
        private Integer fieldId;

        /** 字段名称 */
        private String name;

        /** 字段标签（显示名称） */
        private String label;

        /** 字段值列表（成员类型字段，如 UserReference） */
        private List<MemberInfo> values;

        /** 字段值（文本、日期、选择等非成员类型字段） */
        private String value;

        /** 字段类型（如 TextFieldValue、DateFieldValue 等） */
        private String type;
    }

    /**
     * 根据字段名称获取自定义字段的成员列表
     *
     * @param fieldName 字段名称
     * @return 成员列表，未找到返回空列表
     */
    public List<MemberInfo> getCustomFieldMembers(String fieldName) {
        if (customFields == null || fieldName == null) {
            return List.of();
        }

        return customFields.stream()
                .filter(field -> field.getName().equals(fieldName) ||
                        (field.getLabel() != null && field.getLabel().equals(fieldName)))
                .findFirst()
                .map(CustomField::getValues)
                .orElse(List.of());
    }

    /**
     * 根据字段名称获取成员列表（包括内置字段和自定义字段）
     *
     * @param fieldName 字段名称，如assignedTo、submitter、supervisors或自定义字段名
     * @return 成员列表
     */
    public List<MemberInfo> getMembersByField(String fieldName) {
        if (fieldName == null) {
            return List.of();
        }

        // 内置字段：assignedTo
        if ("assignedTo".equals(fieldName) && assignedTo != null) {
            return assignedTo;
        }

        // 内置字段：supervisors（映射到owners）
        if ("supervisors".equals(fieldName) && owners != null) {
            return owners;
        }

        // 内置字段：submitter（映射到createdBy）
        if ("submitter".equals(fieldName) && createdBy != null) {
            return List.of(createdBy);
        }

        // 内置字段：createdBy
        if ("createdBy".equals(fieldName) && createdBy != null) {
            return List.of(createdBy);
        }

        // 内置字段：modifiedBy
        if ("modifiedBy".equals(fieldName) && modifiedBy != null) {
            return List.of(modifiedBy);
        }

        // 自定义字段
        return getCustomFieldMembers(fieldName);
    }
}