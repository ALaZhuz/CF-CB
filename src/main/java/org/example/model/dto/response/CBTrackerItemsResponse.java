package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.model.cb.TrackerItem;

import java.util.List;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CBTrackerItemsResponse {
    private int page;
    private int pageSize;
    private int total;
    private List<TrackerItem> itemRefs;
}
