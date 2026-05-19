package org.example.model.dto.response;

import lombok.Data;
import org.example.model.cb.TrackerItem;

import java.util.List;

@Data
public class CBQueryResponse {
    private int page;
    private int pageSize;
    private int total;
    private List<TrackerItem> items;
}
