package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.model.cb.Project;

/**
 * 根据TrackerId获取项目id+name
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CBTrackerInfoResponse {
    // trackerId
    private Integer id;
    // tracker名称
    private String name;
    private Project project;
}
