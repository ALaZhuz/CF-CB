package org.example.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.model.cb.TrackerItem;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrackerItemRequest {
    private Integer trackerId;
    private List<TrackerItem> items;

}
