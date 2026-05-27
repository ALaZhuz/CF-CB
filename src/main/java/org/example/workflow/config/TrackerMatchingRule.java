package org.example.workflow.config;

import lombok.Data;

/**
 * Tracker批量匹配规则类
 *
 * 定义按规则自动将tracker匹配到工作流模板的配置。
 * 支持按tracker-id精确匹配和按tracker-type类型匹配。
 * 匹配规则按配置顺序执行，使用第一条匹配成功的规则。
 *
 * @author system
 * @since 1.0
 */
@Data
public class TrackerMatchingRule {

    /**
     * Tracker ID精确匹配条件
     *
     * 与Codebeamer中的tracker ID完全一致才算匹配成功。
     * 精确匹配优先于类型匹配。
     */
    private Integer trackerId;

    /**
     * Tracker类型匹配条件
     *
     * 与Codebeamer中的tracker typeName字段匹配（忽略大小写）。
     * 例如：Bug、Requirement、Task等。
     * 在trackerId未匹配时使用。
     */
    private String trackerType;

    /**
     * 匹配成功后引用的工作流模板名称
     *
     * 可以是全局工作流模板名称或项目内工作流模板名称。
     */
    private String workflow;

    /**
     * 判断是否按tracker-id精确匹配
     *
     * @return true表示使用trackerId匹配
     */
    public boolean isIdMatch() {
        return trackerId != null;
    }

    /**
     * 判断是否按tracker类型匹配
     *
     * @return true表示使用trackerType匹配
     */
    public boolean isTypeMatch() {
        return trackerType != null && !trackerType.isEmpty();
    }

    /**
     * 判断tracker ID是否匹配
     *
     * @param id tracker的ID
     * @return true表示ID完全一致
     */
    public boolean matchesTrackerId(Integer id) {
        if (!isIdMatch()) {
            return false;
        }
        return trackerId.equals(id);
    }

    /**
     * 判断tracker类型是否匹配
     *
     * @param trackerTypeName tracker的类型名称
     * @return true表示匹配成功
     */
    public boolean matchesTrackerType(String trackerTypeName) {
        if (!isTypeMatch()) {
            return false;
        }
        return trackerType.equalsIgnoreCase(trackerTypeName);
    }
}