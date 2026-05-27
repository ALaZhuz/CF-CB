package org.example.model.dto.response;

import lombok.Data;
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

    /** 提交人信息 */
    private MemberInfo submitter;

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
    }

    /**
     * 自定义字段类
     */
    @Data
    public static class CustomField {
        /** 字段名称 */
        private String name;

        /** 字段标签（显示名称） */
        private String label;

        /** 字段值列表 */
        private List<MemberInfo> values;
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
     * @param fieldName 字段名称，如assignedTo、submitter或自定义字段名
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

        // 内置字段：submitter
        if ("submitter".equals(fieldName) && submitter != null) {
            return List.of(submitter);
        }

        // 自定义字段
        return getCustomFieldMembers(fieldName);
    }
}