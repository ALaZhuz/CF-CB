package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownstreamGroupResponse {
    // 根据项目分组
    private String projectName;
    private Integer projectId;
    private List<TrackerItemGroupResponse> trackerItemGroupResponseList;
}
