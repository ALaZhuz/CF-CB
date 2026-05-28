package org.example.model.dto.response;

import lombok.Data;
import org.example.model.cb.TrackerItem;

import java.util.List;

@Data
public class CBReviewListResponse {
    private List<GroupedReview> groupedReviews;
    private Integer totalCount;

    @Data
    public static class GroupedReview {
        private List<ReviewItemWrap> listOfReviewItems;
    }

    @Data
    public static class ReviewItemWrap {
        private Review review;
        private Boolean reviewer;
        private Boolean moderator;
    }

    @Data
    public static class Review {
        private Integer id;
        private String name;
        private String deadline;
        private Person submitter;
        private List<Person> moderators;
        private List<Person> reviewers;
        private List<Person> viewers;
        private Boolean closed;
        private Boolean canceled;
    }

    @Data
    public static class Person {
        private String id;
        private String name;
        private String realName;
        private String email;
    }
}
