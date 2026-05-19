package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BuildUpstreamResponse {
    public boolean success;
    public String message;          // 总体提示
    // 以下条目未查询到和需求池的关联关系
    public List<Integer> errOutIdList;

    // 以下条目的需求池条目未查询到上游追溯
    public List<Integer> errUpstreamIdList;

    // 以下条目的需求池上游条目未被复制到项目
    public List<Integer> errInIdList;

    // 以下条目更新追溯字段失败
    public List<Integer> errUpdateIdList;
}
