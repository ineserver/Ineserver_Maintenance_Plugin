package com.ineserver.maintenance;

import java.time.Instant;

public class MaintenanceEvent {

    private final String id;
    private final String title;
    private final String description;
    private final Instant startTime;
    private final Instant endTime;

    public MaintenanceEvent(String id, String title, String description, Instant startTime, Instant endTime) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }
}
