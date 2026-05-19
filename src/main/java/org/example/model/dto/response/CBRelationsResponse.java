package org.example.model.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CBRelationsResponse {
    private ItemRevision itemId;
    private List<Association> upstreamReferences;
    private List<Association> incomingAssociations;
    private List<Association> outgoingAssociations;
    private Integer page;
    private Integer pageSize;
    private Integer itemCount;
    @JsonProperty("isLastPage")
    private Boolean lastPage;

    @Data
    public static class ItemRevision {
        private Integer id;
        private Integer commonItemId;
        private Integer version;
    }

    @Data
    public static class Association {
        private String id;
        private ItemRevision itemRevision;
        private String type;
    }
}
