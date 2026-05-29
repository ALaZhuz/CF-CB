package org.example.workflow.config;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类规则配置类
 *
 * 定义按分类字段值匹配的通知规则。
 * 每个分类有不同的成员通知间隔和升级天数配置。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ClassifyRule {

    /** 分类名称，与 classify-field 字段值匹配 */
    private String category;

    /** 成员通知间隔天数（每N天通知一次） */
    private Integer memberIntervalDays;

    /** 科长升级天数（停留天数>=N时通知科长） */
    private Integer managerEscalateDays;

    /** 部长升级天数（停留天数>=N时通知部长，null表示不通知部长） */
    private Integer directorEscalateDays;
}