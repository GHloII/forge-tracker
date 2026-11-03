package dev.forge.tracker.persistence;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class Database {
  private final JdbcClient jdbc;

  public Database(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int execute(String sql, Object... args) {
    return jdbc.sql(sql).params(Arrays.asList(args)).update();
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.sql(sql).params(Arrays.asList(args)).query().listOfRows();
  }

  public <T> List<T> list(Class<T> type, String sql, Object... args) {
    return jdbc.sql(sql).params(Arrays.asList(args)).query(type).list();
  }

  public <T> Optional<T> optional(Class<T> type, String sql, Object... args) {
    return jdbc.sql(sql).params(Arrays.asList(args)).query(type).optional();
  }

  public <T> T one(Class<T> type, String sql, Object... args) {
    return jdbc.sql(sql).params(Arrays.asList(args)).query(type).single();
  }

  public long id() {
    return one(Long.class, "SELECT last_insert_rowid()");
  }
}
