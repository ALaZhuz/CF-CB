/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-25 17:42:24
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-26 18:35:27
 * @FilePath: \cf-cb\src\main\java\org\example\config\ReviewProperties.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codebeamer")
public class ReviewProperties {
    private String baseUrlPrefix;
    private String sqlitePath = "./data/review-notify.db";
    public String getListUrl() {
        return baseUrlPrefix + "/cb/api/reviews/list";
    }
    public String getLinkPrefix() {
        return baseUrlPrefix + "/cb/x/#/review/";
    }
}
