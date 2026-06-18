package org.example.model.dto.response;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 钉钉机器人消息响应DTO
 *
 * 封装机器人消息API的响应字段：
 * - invalidStaffIdList: 无效用户列表
 * - flowControlledStaffIdList: 被限流用户列表
 * - filteredStaffIdList: 被过滤用户列表
 * - processQueryKey: 处理查询键
 *
 * @author system
 * @since 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DingRobotMessageResponse {
    /** 无效用户ID列表 */
    private List<String> invalidStaffIdList;

    /** 被限流用户ID列表 */
    private List<String> flowControlledStaffIdList;

    /** 被过滤用户ID列表 */
    private List<String> filteredStaffIdList;

    /** 处理查询键 */
    private String processQueryKey;

    /**
     * 判断响应是否包含警告信息
     *
     * @return true表示有无效用户或被限流用户
     */
    public boolean hasWarnings() {
        return (invalidStaffIdList != null && !invalidStaffIdList.isEmpty())
            || (flowControlledStaffIdList != null && !flowControlledStaffIdList.isEmpty());
    }
}