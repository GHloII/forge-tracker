package dev.forge.tracker;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TrackerApplication {
  public static void main(String[] args) throws Exception {
    Files.createDirectories(Path.of(System.getenv().getOrDefault("DATA_DIR", "./data")));
    SpringApplication.run(TrackerApplication.class, args);
  }
}
