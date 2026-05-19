package org.example.model.dto.response;

import lombok.Data;
@Data
public class CBTrackerConfigurationResponse {
    private Integer projectId;
    private Integer fieldId;
    // 拆解valueModel得到
    private String fieldType;
    private String trackerItemType;
}
