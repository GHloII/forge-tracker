package dev.forge.tracker.tasks;

public record Task(
    long id,
    long projectId,
    String identifier,
    String title,
    String description,
    String type,
    String priority,
    String status,
    String approval,
    long creatorId,
    Long assigneeId,
    String category,
    String labels,
    boolean archived,
    int version,
    int revision,
    String createdAt,
    String updatedAt) {}
