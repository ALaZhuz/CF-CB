package org.example.model.dto.response;

import lombok.Data;
import java.util.List;

/**
 * Codebeamer条目历史版本DTO
 *
 * 表示条目的一次修改记录，包含变更详情和时间。
 *
 * @author system
 * @since 1.0
 */
@Data
public class CBHistoryVersion {

    /** 条目版本信息 */
    private ItemRevision itemRevision;

    /** 本次修改的变更列表 */
    private List<CBHistoryChange> changes;

    /** 修改人信息 */
    private MemberInfo modifiedBy;

    /** 修改时间 */
    private String modifiedAt;

    /**
     * 条目版本信息类
     */
    @Data
    public static class ItemRevision {
        /** 条目版本ID */
        private Integer id;

        /** 条目公共ID */
        private Integer commonItemId;

        /** 版本号 */
        private Integer version;
    }

    /**
     * 成员信息类
     */
    @Data
    public static class MemberInfo {
        /** 用户ID */
        private Integer id;

        /** 用户名 */
        private String name;

        /** 用户类型 */
        private String type;

        /** 用户邮箱 */
        private String email;

        /** 显示名称 */
        private String displayName;
    }
}