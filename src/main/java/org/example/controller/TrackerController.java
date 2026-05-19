package org.example.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.dto.request.TrackerItemRequest;
import org.example.model.dto.response.BuildUpstreamResponse;
import org.example.model.dto.response.DownstreamGroupResponse;
import org.example.model.dto.response.TrackerItemGroupResponse;
import org.example.service.TrackerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/tracker")
@RequiredArgsConstructor
public class TrackerController {

    private final TrackerService trackerService;

    @PostMapping("/downstreamReferences")
    public List<DownstreamGroupResponse> getDownstreamReferences(@RequestBody TrackerItemRequest trackerItemRequest){
        Integer trackerId = trackerItemRequest.getTrackerId();
        if (trackerItemRequest.getItems() == null || trackerItemRequest.getItems().isEmpty()) {
            log.info("===========跟踪器{},请求全量返回下游id===========", trackerId);
        }else {
            log.info("===========跟踪器{},请求批量返回下游id===========", trackerId);
        }
        List<DownstreamGroupResponse> downstreamReferences = trackerService.getDownstreamReferences(trackerItemRequest);
        return downstreamReferences;
    }

    @PostMapping("/field")
    public BuildUpstreamResponse updateField(@RequestBody TrackerItemRequest trackerItemRequest){
        Integer trackerId = trackerItemRequest.getTrackerId();
        if (trackerItemRequest.getItems() == null || trackerItemRequest.getItems().isEmpty()) {
            log.info("===========跟踪器{},请求全量建立追溯===========", trackerId);
        }else {
            log.info("===========跟踪器{},请求批量建立追溯===========", trackerId);
        }
        BuildUpstreamResponse res = trackerService.updateField(trackerItemRequest);
        return res;
    }
}
