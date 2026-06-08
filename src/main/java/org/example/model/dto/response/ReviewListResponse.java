package org.example.model.dto.response;


import lombok.Data;
import org.example.model.cb.ReviewItem;

import java.util.List;

@Data
public class ReviewListResponse {
    private List<GroupedReview> groupedReviews;
    private Integer totalCount;

    @Data
    public static class GroupedReview {
        private ReviewItem.Review idName;
        private List<ReviewItemWrap> listOfReviewItems;
    }

    @Data
    public static class ReviewItemWrap {
        private ReviewItem.Review review;
        private Boolean reviewer;
        private Boolean moderator;
    }
}
