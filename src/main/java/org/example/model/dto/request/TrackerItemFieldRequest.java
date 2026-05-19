package org.example.model.dto.request;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@NoArgsConstructor
@AllArgsConstructor
@Data
public class TrackerItemFieldRequest {
    private List<FieldValue> fieldValues;

    @Data
    public static class FieldValue {
        private List<ValueItem> values;
        private Integer fieldId;
        private String type;
    }

    @Data
    public static class ValueItem {
        private Integer id;
        private String type;
    }
}
