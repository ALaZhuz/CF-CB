package org.example.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrganizationManagerResponse {
    private List<String> sectionManager;
    private List<String> departmentManager;
    private List<String> director;
}
