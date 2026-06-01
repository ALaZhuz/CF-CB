package org.example.model.dto.response;

import lombok.Data;
import java.util.List;

/**
 * Codebeamer History API响应DTO
 *
 * 对应 Codebeamer API: GET /v3/items/{itemId}/history
 * 用于获取条目的状态变更历史记录。
 *
 * @author system
 * @since 1.0
 */
@Data
public class CBHistoryResponse {

    /** 版本记录列表（从旧到新） */
    private List<CBHistoryVersion> versions;
}