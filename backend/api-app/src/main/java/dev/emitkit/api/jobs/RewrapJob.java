package dev.emitkit.api.jobs;

import dev.emitkit.api.jobs.EncryptedColumns.EncryptedColumn;
import dev.emitkit.common.secret.CipherItem;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves encrypted values to the latest Vault key version after a key rotation (ov section 3.1).
 * Rows are read in pages ordered by id. A row is written back only if it still holds the ciphertext
 * that was read, so a value saved in the meantime is never replaced by an older one.
 */
@Component
public class RewrapJob {

  static final int PAGE_SIZE = 200;
  private static final Logger LOG = LoggerFactory.getLogger(RewrapJob.class);
  private static final Pattern VERSION_PREFIX = Pattern.compile("^[a-z]+:v(\\d+):");

  private final JdbcClient jdbc;
  private final SecretCipher cipher;

  RewrapJob(JdbcClient jdbc, SecretCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  /** Scheduled re-wrap. Off unless {@code emitkit.jobs.rewrap.cron} is set ("-" disables it). */
  @Scheduled(cron = "${emitkit.jobs.rewrap.cron:-}", zone = "UTC")
  @SchedulerLock(name = "rewrap", lockAtMostFor = "PT30M")
  public void runScheduled() {
    LOG.info("scheduled key re-wrap, still below latest per column: {}", rewrapAll());
  }

  /**
   * Re-wraps every encrypted value below the latest key version. Rows already at that version are
   * skipped, so the job can run again at any time.
   *
   * @return for each encrypted column, how many of its values are still below the latest version
   */
  public Map<String, Long> rewrapAll() {
    int latest = cipher.latestKeyVersion();
    for (EncryptedColumn column : EncryptedColumns.ALL) {
      rewrapColumn(column, latest);
    }
    // Read the version again: a rotation during the run leaves values below the newer latest.
    int latestNow = cipher.latestKeyVersion();
    Map<String, Long> remaining = new LinkedHashMap<>();
    for (EncryptedColumn column : EncryptedColumns.ALL) {
      remaining.put(column.column(), countBelow(column, latestNow));
    }
    return remaining;
  }

  private void rewrapColumn(EncryptedColumn column, int latest) {
    forEachPage(
        column,
        page -> {
          List<Row> stale = belowLatest(column, page, latest);
          if (stale.isEmpty()) {
            return;
          }
          List<CipherItem> items =
              stale.stream()
                  .map(row -> new CipherItem(row.ciphertext(), contextOf(column, row)))
                  .toList();
          List<String> rewrapped = cipher.rewrapBatch(items);
          for (int i = 0; i < stale.size(); i++) {
            update(column, stale.get(i), rewrapped.get(i));
          }
        });
  }

  private long countBelow(EncryptedColumn column, int latest) {
    AtomicLong count = new AtomicLong();
    forEachPage(column, page -> count.addAndGet(belowLatest(column, page, latest).size()));
    return count.get();
  }

  private void forEachPage(EncryptedColumn column, Consumer<List<Row>> action) {
    String after = "";
    List<Row> page = page(column, after);
    while (!page.isEmpty()) {
      action.accept(page);
      after = page.getLast().id();
      page = page(column, after);
    }
  }

  private List<Row> page(EncryptedColumn column, String after) {
    String sql =
        ("select %1$s, %2$s from %3$s where %2$s is not null and %1$s > :after"
                + " order by %1$s limit %4$d")
            .formatted(column.idColumn(), column.column(), column.table(), PAGE_SIZE);
    return jdbc.sql(sql)
        .param("after", after)
        .query((rs, rowNum) -> new Row(rs.getString(1), rs.getString(2)))
        .list();
  }

  /** Writes back only if the row still holds the ciphertext that was re-wrapped. */
  private void update(EncryptedColumn column, Row row, String rewrapped) {
    String sql =
        ("update %1$s set %2$s = :new where %3$s = :id and %2$s = :old")
            .formatted(column.table(), column.column(), column.idColumn());
    jdbc.sql(sql)
        .param("new", rewrapped)
        .param("id", row.id())
        .param("old", row.ciphertext())
        .update();
  }

  private static List<Row> belowLatest(EncryptedColumn column, List<Row> page, int latest) {
    return page.stream().filter(row -> versionOf(column, row) < latest).toList();
  }

  private static SecretContext contextOf(EncryptedColumn column, Row row) {
    return new SecretContext(column.table(), column.column(), row.id());
  }

  /**
   * The key version in a ciphertext prefix, such as 2 for {@code vault:v2:...}. The error names the
   * row, never its value.
   */
  private static int versionOf(EncryptedColumn column, Row row) {
    Matcher matcher = VERSION_PREFIX.matcher(row.ciphertext());
    if (!matcher.find()) {
      throw new IllegalStateException(
          "no key version prefix in %s.%s for row %s"
              .formatted(column.table(), column.column(), row.id()));
    }
    return Integer.parseInt(matcher.group(1));
  }

  private record Row(String id, String ciphertext) {}
}
