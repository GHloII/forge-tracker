package dev.forge.tracker.auth;

import dev.forge.tracker.persistence.Database;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class Bootstrap {
  @Bean
  ApplicationRunner initialOwner(
      Database db,
      PasswordEncoder passwords,
      @Value("${tracker.bootstrap-user}") String username,
      @Value("${tracker.bootstrap-password}") String password) {
    return args -> {
      if (db.one(Long.class, "SELECT count(*) FROM users") == 0) {
        if (password.length() < 12 || password.length() > 72)
          throw new IllegalStateException(
              "Set BOOTSTRAP_PASSWORD to 12-72 characters before first startup.");
        db.execute(
            "INSERT INTO users(username,display_name,password_hash,kind) VALUES(?,?,?,'OWNER')",
            username,
            "Workspace owner",
            passwords.encode(password));
      }
    };
  }
}
