package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.model.cb.Project;
import org.example.model.cb.TrackerType;

/**
 * 根据TrackerId获取Tracker信息响应类
 *
 * 对应Codebeamer API: /v3/trackers/{trackerId}
 * 包含tracker的基本信息、项目信息和类型信息。
 *
 * @author system
 * @since 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CBTrackerInfoResponse {
    /** Tracker ID */
    private Integer id;

    /** Tracker名称 */
    private String name;

    /** Tracker所属项目 */
    private Project project;

    /** Tracker类型 */
    private TrackerType type;
}
