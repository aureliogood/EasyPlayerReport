package com.easyplayerreport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DatabaseData {
    private int nextId = 1;
    private final List<Report> reports = new ArrayList<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> appealCooldowns = new HashMap<>();
    private final Map<UUID, Long> falseReportBlocks = new HashMap<>();
    private final Map<Integer, List<String>> chatLog = new HashMap<>();
    private final Map<Integer, List<Report.Event>> deletedEvents = new HashMap<>();

    public int getNextId() { return nextId; }
    public void setNextId(int nextId) { this.nextId = Math.max(1, nextId); }
    public List<Report> getReports() { return reports; }
    public Map<UUID, Long> getCooldowns() { return cooldowns; }
    public Map<UUID, Long> getAppealCooldowns() { return appealCooldowns; }
    public Map<UUID, Long> getFalseReportBlocks() { return falseReportBlocks; }
    public Map<Integer, List<String>> getChatLog() { return chatLog; }
    public Map<Integer, List<Report.Event>> getDeletedEvents() { return deletedEvents; }

    public boolean isEmpty() {
        return reports.isEmpty() && cooldowns.isEmpty() && appealCooldowns.isEmpty()
                && falseReportBlocks.isEmpty() && chatLog.isEmpty() && deletedEvents.isEmpty();
    }
}
