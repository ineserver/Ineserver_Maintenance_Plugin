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
        // 件名・説明のない予定でも比較や表示で落ちないよう、nullは空文字にする
        this.title = title != null ? title : "";
        this.description = description != null ? description : "";
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

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        MaintenanceEvent that = (MaintenanceEvent) o;

        if (!id.equals(that.id))
            return false;
        if (!title.equals(that.title))
            return false;
        if (!description.equals(that.description))
            return false;
        if (!startTime.equals(that.startTime))
            return false;
        return endTime.equals(that.endTime);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + title.hashCode();
        result = 31 * result + description.hashCode();
        result = 31 * result + startTime.hashCode();
        result = 31 * result + endTime.hashCode();
        return result;
    }
}
