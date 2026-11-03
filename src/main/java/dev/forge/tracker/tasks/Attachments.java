package dev.forge.tracker.tasks;

import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.notifications.Events;
import dev.forge.tracker.persistence.Database;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class Attachments {
  private final Path directory;
  private final TaskService tasks;
  private final Database db;
  private final Access access;
  private final Events events;

  public Attachments(
      @Value("${tracker.data-dir}") String directory,
      TaskService tasks,
      Database db,
      Access access,
      Events events)
      throws IOException {
    this.directory = Path.of(directory).toAbsolutePath().normalize().resolve("attachments");
    Files.createDirectories(this.directory);
    this.tasks = tasks;
    this.db = db;
    this.access = access;
    this.events = events;
  }

  @Transactional
  public Object upload(Actor actor, String identifier, MultipartFile file) throws IOException {
    Task task = tasks.get(actor, identifier);
    access.require(actor, task.projectId(), Access.Permission.WRITE);
    if (task.archived()) throw Problem.conflict("Restore the task before uploading.");
    if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
      throw Problem.bad("Image must be 1 byte to 5 MB.");
    if (db.one(Long.class, "SELECT count(*) FROM attachments WHERE task_id=?", task.id()) >= 100)
      throw Problem.bad("A task supports at most 100 images.");
    byte[] bytes = file.getBytes();
    String format;
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw Problem.bad("Use a valid PNG, JPEG or GIF image.");
      var reader = readers.next();
      try {
        reader.setInput(input);
        format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("png", "jpeg", "gif").contains(format)
            || (long) reader.getWidth(0) * reader.getHeight(0) > 25000000)
          throw Problem.bad("Use a PNG, JPEG or GIF of at most 25 megapixels.");
        reader.read(0);
      } catch (IOException ex) {
        throw Problem.bad("Image data is incomplete or invalid.");
      } finally {
        reader.dispose();
      }
    }
    String id = UUID.randomUUID().toString();
    Path path = directory.resolve(id);
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                Files.deleteIfExists(path);
              } catch (IOException ignored) {
              }
          }
        });
    String name =
        Optional.ofNullable(file.getOriginalFilename())
            .orElse("image")
            .replaceAll("[\\p{Cntrl}\\\\/]", "_");
    if (name.length() > 150) name = name.substring(name.length() - 150);
    db.execute(
        "INSERT INTO attachments(id,task_id,filename,media_type,size,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        task.id(),
        name,
        "image/" + format,
        bytes.length,
        Instant.now().toString());
    events.emit(actor, task, "IMAGE_ATTACHED", name, false);
    return Map.of("id", id);
  }

  public ResponseEntity<?> download(Actor actor, String id) {
    if (!id.matches("[a-f0-9-]{36}")) throw Problem.missing();
    var metadata =
        db
            .rows(
                "SELECT a.*,t.identifier FROM attachments a JOIN tasks t ON t.id=a.task_id WHERE"
                    + " a.id=?",
                id)
            .stream()
            .findFirst()
            .orElseThrow(Problem::missing);
    tasks.get(actor, (String) metadata.get("identifier"));
    Path path = directory.resolve(id);
    if (!Files.isRegularFile(path)) throw Problem.missing();
    return ResponseEntity.ok()
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-store")
        .contentType(MediaType.parseMediaType((String) metadata.get("media_type")))
        .body(new FileSystemResource(path));
  }

  @RestController
  public static class Controller {
    private final Attachments attachments;
    private final Identity identity;

    public Controller(Attachments attachments, Identity identity) {
      this.attachments = attachments;
      this.identity = identity;
    }

    @PostMapping("/api/tasks/{id}/attachments")
    public Object upload(
        Authentication auth, @PathVariable String id, @RequestParam MultipartFile file)
        throws IOException {
      return attachments.upload(identity.actor(auth), id, file);
    }

    @GetMapping("/api/attachments/{id}")
    public Object download(Authentication auth, @PathVariable String id) {
      return attachments.download(identity.actor(auth), id);
    }
  }
}
