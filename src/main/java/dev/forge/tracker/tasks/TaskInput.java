package dev.forge.tracker.tasks;

import dev.forge.tracker.common.Problem;
import java.util.List;
import java.util.Set;

public record TaskInput(
    String title,
    String description,
    String type,
    String priority,
    String category,
    List<String> labels) {
  public TaskInput {
    if (title == null || title.isBlank() || title.length() > 240)
      throw Problem.bad("Title must contain 1-240 characters.");
    title = title.strip();
    description = description == null ? "" : description;
    category = category == null ? "" : category;
    type = type == null ? "FEATURE" : type;
    priority = priority == null ? "MEDIUM" : priority;
    if (labels != null && labels.stream().anyMatch(java.util.Objects::isNull))
      throw Problem.bad("Labels must be nonempty strings.");
    labels = labels == null ? List.of() : List.copyOf(labels);
    if (description.length() > 100000
        || category.length() > 120
        || labels.size() > 20
        || labels.stream().anyMatch(l -> l.isBlank() || l.length() > 40))
      throw Problem.bad("Description, category or labels exceed their limits.");
    if (!Set.of("FEATURE", "BUG", "IMPROVEMENT", "RESEARCH").contains(type)
        || !Set.of("URGENT", "HIGH", "MEDIUM", "LOW", "NONE").contains(priority))
      throw Problem.bad("Invalid type or priority.");
  }
}
