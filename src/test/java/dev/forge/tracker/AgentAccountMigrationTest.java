package dev.forge.tracker;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentAccountMigrationTest {
  @TempDir Path directory;

  @Test
  void existingAgentsBindToRecordedHumanTokenCreatorOnly() throws Exception {
    String url = "jdbc:sqlite:" + directory.resolve("migration.db");
    Flyway.configure().dataSource(url, null, null).target("4").load().migrate();
    try (var connection = DriverManager.getConnection(url);
        var sql = connection.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO users(id,username,display_name,kind)"
              + " VALUES(1,'owner','Owner','OWNER'),(2,'human','Human','DEVELOPER'),(3,'agent','Agent','AGENT'),(4,'unbound','Unbound','AGENT')");
      sql.executeUpdate("INSERT INTO projects(id,key,name) VALUES(1,'TEST','Test')");
      sql.executeUpdate(
          "INSERT INTO tokens(id,user_id,project_id,name,hash,permission,created_at)"
              + " VALUES(1,3,1,'Test','fixture-hash','WRITE','2026-01-01T00:00:00Z')");
      sql.executeUpdate(
          "INSERT INTO audit(actor_id,action,target,created_at)"
              + " VALUES(2,'TOKEN_CREATED','1','2026-01-01T00:00:00Z')");
    }
    Flyway.configure().dataSource(url, null, null).load().migrate();
    try (var connection = DriverManager.getConnection(url);
        var sql = connection.createStatement();
        var rows = sql.executeQuery("SELECT id,account_id FROM users ORDER BY id")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getObject("account_id")).isNull();
      assertThat(rows.next()).isTrue();
      assertThat(rows.getObject("account_id")).isNull();
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong("account_id")).isEqualTo(2);
      assertThat(rows.next()).isTrue();
      assertThat(rows.getObject("account_id")).isNull();
    }
  }
}
