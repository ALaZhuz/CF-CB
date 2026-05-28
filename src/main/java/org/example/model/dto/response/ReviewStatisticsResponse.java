package org.example.model.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class ReviewStatisticsResponse {
    private ReviewInfo review;
    private List<StatsPerUser> statsPerUser;

    @Data
    public static class ReviewInfo {
        private List<String> projectNames;
        private List<String> trackerNames;
    }

    @Data
    public static class StatsPerUser {
        private LoggedInUser loggedInUser;
        private ReviewStat reviewStat;
    }

    @Data
    public static class LoggedInUser {
        private Integer id;
        private String userName;
        private String email;
    }

    @Data
    public static class ReviewStat {
        private Integer total;
        private Integer accepted;
        private Integer rejected;
        private Integer notReviewed;
//        private Integer comments;
//        private Integer approvedComments;
//        private Integer replyComments;
    }
}