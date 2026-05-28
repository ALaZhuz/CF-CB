/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-25 17:49:39
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-26 18:12:25
 * @FilePath: \cf-cb\src\main\java\org\example\model\cb\ReviewItem.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.model.cb;

import lombok.Data;

import java.util.List;

@Data
public class ReviewItem {
    private Review review;
    private Boolean reviewer;
    private Boolean moderator;

    @Data
    public static class Review {
        private Integer id;
        private String name;
        private String deadline;
        private Boolean closed;
        private Boolean canceled;
        private Person submitter;
        private List<Person> moderators;
        private List<Person> reviewers;
        private List<Person> viewers;
    }

    @Data
    public static class Person {
        private String id;
        private String name;
        private String email;
    }

}
