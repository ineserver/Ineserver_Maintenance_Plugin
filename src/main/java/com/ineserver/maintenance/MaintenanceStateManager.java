package com.ineserver.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class MaintenanceStateManager {

    private final Path dataDirectory;
    private final Logger logger;
    private final Gson gson;
    private final File stateFile;

    public MaintenanceStateManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.stateFile = dataDirectory.resolve("maintenance-state.json").toFile();
    }

    public void saveState(MaintenanceState state) {
        try (FileWriter writer = new FileWriter(stateFile)) {
            gson.toJson(state, writer);
            logger.info("Maintenance state saved to file (" + state.events.size() + " events)");
        } catch (IOException e) {
            logger.error("Failed to save maintenance state", e);
        }
    }

    public MaintenanceState loadState() {
        if (!stateFile.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(stateFile)) {
            MaintenanceState state = gson.fromJson(reader, MaintenanceState.class);
            logger.info("Maintenance state loaded from file");
            return state;
        } catch (Exception e) {
            logger.error("Failed to load maintenance state", e);
            return null;
        }
    }

    public void clearState() {
        if (stateFile.exists()) {
            if (stateFile.delete()) {
                logger.info("Maintenance state file deleted");
            }
        }
    }

    public static class MaintenanceState {
        private boolean maintenanceMode;
        private List<EventData> events;
        private Map<String, Boolean> discordNotificationSentMap;

        public MaintenanceState() {
            this.events = new ArrayList<>();
            this.discordNotificationSentMap = new HashMap<>();
        }

        public MaintenanceState(boolean maintenanceMode, List<MaintenanceEvent> events, Map<String, Boolean> discordNotificationSentMap) {
            this.maintenanceMode = maintenanceMode;
            this.events = new ArrayList<>();
            this.discordNotificationSentMap = new HashMap<>(discordNotificationSentMap);
            
            for (MaintenanceEvent event : events) {
                this.events.add(new EventData(event));
            }
        }

        public boolean isMaintenanceMode() {
            return maintenanceMode;
        }

        public List<MaintenanceEvent> toEvents() {
            List<MaintenanceEvent> result = new ArrayList<>();
            for (EventData data : events) {
                result.add(data.toEvent());
            }
            return result;
        }

        public Map<String, Boolean> getDiscordNotificationSentMap() {
            return new HashMap<>(discordNotificationSentMap);
        }

        private static class EventData {
            private String eventId;
            private String eventTitle;
            private String eventDescription;
            private String startTime;
            private String endTime;

            public EventData() {
            }

            public EventData(MaintenanceEvent event) {
                this.eventId = event.getId();
                this.eventTitle = event.getTitle();
                this.eventDescription = event.getDescription();
                this.startTime = event.getStartTime().toString();
                this.endTime = event.getEndTime().toString();
            }

            public MaintenanceEvent toEvent() {
                return new MaintenanceEvent(
                        eventId,
                        eventTitle,
                        eventDescription,
                        Instant.parse(startTime),
                        Instant.parse(endTime)
                );
            }
        }
    }
}
