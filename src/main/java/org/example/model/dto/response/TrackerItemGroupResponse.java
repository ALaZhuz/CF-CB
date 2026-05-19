package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrackerItemGroupResponse {
    private String trackerName;
    private List<Integer> trackerItemIds;
}
