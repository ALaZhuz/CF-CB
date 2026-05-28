/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-25 17:47:41
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-25 18:29:44
 * @FilePath: \cf-cb\src\main\java\org\example\model\dto\request\ReviewPollRequest.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.model.dto.request;

import lombok.Data;


@Data
public class ReviewPollRequest {
    private Integer pageNo = 1;
    private Integer pageSize = 100;
    private String grouping = "None";
}
