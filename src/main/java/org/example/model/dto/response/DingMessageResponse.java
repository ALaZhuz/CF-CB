package org.example.model.dto.response;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DingMessageResponse {
    private Integer errcode;
    private String errmsg;
    private Long taskId;
    private String requestId;

    public boolean isSuccess() {
        return errcode != null && errcode == 0;
    }
}