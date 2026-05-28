package org.example.model.cb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracker类型引用类
 *
 * 对应Codebeamer API响应中的tracker.type字段。
 *
 * @author system
 * @since 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrackerType {
    /** Tracker类型ID */
    private Integer id;

    /** Tracker类型名称，如Bug、Requirement等 */
    private String name;

    /** 引用类型，固定为TrackerTypeReference */
    private String type;
}