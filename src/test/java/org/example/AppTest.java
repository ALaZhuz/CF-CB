package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.model.dto.request.TrackerItemRequest;
import org.example.model.dto.response.DownstreamGroupResponse;
import org.example.model.dto.response.TrackerItemGroupResponse;
import org.example.service.TrackerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import org.example.service.CBSwaggerService;  
import org.example.model.dto.response.ItemInfoResponse;


@Slf4j
@SpringBootTest
class AppTest {
    @Autowired
    private TrackerService trackerService;
    @Autowired
    private CBSwaggerService cbSwaggerService;

    @Test
    void testDownstreamReferences() {
//        List<String> str1 = List.of("1963433", "1963427", "1963429", "1963431");
//        List<String> str = List.of("1963437","1963435");
//        List<DownstreamGroupResponse> res = trackerService.getDownstreamReferences(str);
//        log.info(res.toString());
    }

    @Test
    void testField(){
        List<TrackerItemRequest> items = new ArrayList<>();
        TrackerItemRequest item1 = new TrackerItemRequest();
//        item1.setId("1968088");
//        item1.setTitle("cts追溯test1");
//        item1.setTrackerId("2247342");
//        items.add(item1);
//        TrackerItemRequest item2 = new TrackerItemRequest();
//        item2.setId("1968090");
//        item2.setTitle("cts追溯test2");
//        item2.setTrackerId("2247342");
//        items.add(item2);
//        trackerService.updateField(items);
    }

    @Test
    void testGetAllUsers(){
        List<ItemInfoResponse.MemberInfo> res = cbSwaggerService.getAllUsers();
        log.info(res.toString());
    }
}