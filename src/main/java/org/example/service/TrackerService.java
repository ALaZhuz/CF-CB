package org.example.service;

import org.example.model.dto.request.TrackerItemRequest;
import org.example.model.dto.response.BuildUpstreamResponse;
import org.example.model.dto.response.DownstreamGroupResponse;
import org.example.model.dto.response.TrackerItemGroupResponse;

import java.util.List;

public interface TrackerService {
    List<DownstreamGroupResponse> getDownstreamReferences(TrackerItemRequest items);
    BuildUpstreamResponse updateField(TrackerItemRequest items);
}
