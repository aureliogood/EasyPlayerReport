package com.easyplayerreport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class Report {
    public enum Status { OPEN, TAKEN, CLOSED }

    public static final class Event {
        private final String type;
        private final UUID actor;
        private final String actorName;
        private final long timestamp;
        private final String detail;

        public Event(String type, UUID actor, String actorName, long timestamp, String detail) {
            this.type = type == null ? "" : type;
            this.actor = actor;
            this.actorName = actorName == null ? "Sistema" : actorName;
            this.timestamp = timestamp;
            this.detail = detail == null ? "" : detail;
        }

        public String getType() { return type; }
        public UUID getActor() { return actor; }
        public String getActorName() { return actorName; }
        public long getTimestamp() { return timestamp; }
        public String getDetail() { return detail; }
    }

    private final int id;
    private final UUID reporter;
    private final String reporterName;
    private final String target;
    private UUID targetUuid;
    private final String reason;
    private final String description;
    private final long timestamp;
    private long expiresAt;
    private String reporterWorld;
    private int reporterX, reporterY, reporterZ;
    private String targetWorld;
    private int targetX, targetY, targetZ;
    private Status status;
    private UUID assignedStaff;
    private String assignedStaffName;
    private long closedAt;
    private String staffNote = "";
    private String appealText = "";
    private UUID appealAuthor;
    private String appealAuthorName = "";
    private long appealAt;
    private String discordChannelId = "";
    private final List<Event> actionHistory = new ArrayList<>();

    public Report(int id, UUID reporter, String reporterName, String target, String reason, String description, long timestamp) {
        this.id = id;
        this.reporter = reporter;
        this.reporterName = reporterName;
        this.target = target;
        this.reason = reason;
        this.description = description == null ? "" : description;
        this.timestamp = timestamp == 0 ? System.currentTimeMillis() : timestamp;
        this.status = Status.OPEN;
    }

    public int getId() { return id; }
    public UUID getReporter() { return reporter; }
    public String getReporterName() { return reporterName; }
    public String getTarget() { return target; }
    public String getReason() { return reason; }
    public String getDescription() { return description; }
    public long getTimestamp() { return timestamp; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = Math.max(0, expiresAt); }
    public Status getStatus() { return status; }
    public UUID getAssignedStaff() { return assignedStaff; }
    public String getAssignedStaffName() { return assignedStaffName; }
    public long getClosedAt() { return closedAt; }
    public void setClosedAt(long closedAt) { this.closedAt = Math.max(0, closedAt); }
    public String getStaffNote() { return staffNote; }
    public String getAppealText() { return appealText; }
    public UUID getAppealAuthor() { return appealAuthor; }
    public String getAppealAuthorName() { return appealAuthorName; }
    public long getAppealAt() { return appealAt; }

    /**
     * Canal de Discord de este reporte, vacio si no tiene.
     *
     * Se guarda con el reporte y no aparte para que siga siendo suyo aunque se
     * reinicie el servidor: al arrancar se vuelve a leer ese canal y el chat
     * continua donde estaba.
     */
    public String getDiscordChannelId() { return discordChannelId; }
    public void setDiscordChannelId(String channelId) {
        this.discordChannelId = channelId == null ? "" : channelId.trim();
    }

    public List<Event> getActionHistory() { return Collections.unmodifiableList(actionHistory); }

    public UUID getTargetUuid() { return targetUuid; }
    public void setTargetUuid(UUID targetUuid) { this.targetUuid = targetUuid; }

    public boolean hasReporterLocation() { return reporterWorld != null; }
    public boolean hasTargetLocation() { return targetWorld != null; }

    public Location getReporterLocation() {
        return location(reporterWorld, reporterX, reporterY, reporterZ);
    }

    public Location getTargetLocation() {
        return location(targetWorld, targetX, targetY, targetZ);
    }

    public String getReporterWorld() { return reporterWorld; }
    public String getTargetWorld() { return targetWorld; }
    public int getReporterX() { return reporterX; }
    public int getReporterY() { return reporterY; }
    public int getReporterZ() { return reporterZ; }
    public int getTargetX() { return targetX; }
    public int getTargetY() { return targetY; }
    public int getTargetZ() { return targetZ; }

    public void setReporterLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        setReporterLocation(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void setTargetLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        setTargetLocation(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void setReporterLocation(String world, int x, int y, int z) {
        this.reporterWorld = world == null || world.isBlank() ? null : world;
        this.reporterX = x;
        this.reporterY = y;
        this.reporterZ = z;
    }

    public void setTargetLocation(String world, int x, int y, int z) {
        this.targetWorld = world == null || world.isBlank() ? null : world;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }


    public void setStaffNote(String staffNote) {
        this.staffNote = staffNote == null ? "" : staffNote.trim();
    }

    public void setAppealText(String appealText) {
        this.appealText = appealText == null ? "" : appealText.trim();
    }

    public void setAppealAuthor(UUID appealAuthor) {
        this.appealAuthor = appealAuthor;
    }

    public void setAppealAuthorName(String appealAuthorName) {
        this.appealAuthorName = appealAuthorName == null ? "" : appealAuthorName.trim();
    }

    public void setAppealAt(long appealAt) {
        this.appealAt = Math.max(0, appealAt);
    }

    public void setAppeal(String text, UUID author, String authorName, long timestamp) {
        setAppealText(text);
        setAppealAuthor(author);
        setAppealAuthorName(authorName);
        setAppealAt(timestamp);
    }

    // No hay forma de borrar una apelación a propósito: es de una sola vez por
    // reporte y así se queda.

    public boolean hasAppeal() {
        return !appealText.isBlank();
    }

    public void addEvent(String type, UUID actor, String actorName, String detail) {
        actionHistory.add(new Event(type, actor, actorName, System.currentTimeMillis(), detail));
    }

    public void addLoadedEvents(List<Event> events) {
        actionHistory.addAll(events);
    }

    public void setStatus(Status status) {
        this.status = status == null ? Status.OPEN : status;
    }

    public void setAssigned(UUID staff, String staffName) {
        this.assignedStaff = staff;
        this.assignedStaffName = staffName;
        if (staff != null) this.status = Status.TAKEN;
    }

    public void release() {
        this.assignedStaff = null;
        this.assignedStaffName = null;
        this.status = Status.OPEN;
    }

    public void close() {
        this.status = Status.CLOSED;
        this.closedAt = System.currentTimeMillis();
    }

    public boolean isActive() { return status != Status.CLOSED; }

    private Location location(String worldName, int x, int y, int z) {
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    public String toString() {
        return "Report #" + id + " [" + target + " - " + reason + " (" + status + ")] por " + reporterName + " el " + new Date(timestamp);
    }
}
