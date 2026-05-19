package org.example.model.cb;

import lombok.Data;

@Data
public class TrackerItem {
    private Integer id;
    private String name;
    private Tracker tracker;
}
