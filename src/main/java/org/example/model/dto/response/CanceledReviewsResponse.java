/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-06-08 10:41:20
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-06-08 10:55:58
 * @FilePath: \cf-cb\src\main\java\org\example\model\dto\response\CanceledReviewsResponse.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.model.dto.response;

import java.util.List;

import org.example.model.cb.ReviewItem;
import lombok.Data;

@Data
public class CanceledReviewsResponse {
    private Integer page;
    private Integer pageSize;
    private Integer total;

    private List<CanceledReview> canceledReviewList;

    @Data
    public static class CanceledReview {
        private Integer reviewId;
        private String reviewName;
        private List<ReviewItem.Person> moderators;
        private List<String> projectsName;
        private List<String> trackersName;
        private String canceledOn;
        private ReviewItem.Person canceledBy;
    }

}
