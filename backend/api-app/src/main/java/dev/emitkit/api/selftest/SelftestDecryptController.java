package dev.emitkit.api.selftest;

import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import io.swagger.v3.oas.annotations.Hidden;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restore-drill probe (OPS-02): proves that a project's webhook secret still decrypts after
 * Postgres and Vault were restored. Registered in the management child context only, so it is
 * served on the management port and nowhere else. It answers exactly {@code ok} or {@code fail} and
 * never returns plaintext, ciphertext or exception text.
 */
@ManagementContextConfiguration(value = ManagementContextType.CHILD)
@Hidden
@RestController
class SelftestDecryptController {

  private static final Logger LOG = LoggerFactory.getLogger(SelftestDecryptController.class);
  private static final String OK = "ok";
  private static final String FAIL = "fail";

  private final JdbcClient jdbc;
  private final SecretCipher cipher;

  SelftestDecryptController(JdbcClient jdbc, SecretCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  @GetMapping(path = "/internal/selftest/decrypt", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> decrypt(
      @RequestParam(name = "projectId", required = false) @Nullable String projectId) {
    try {
      if (projectId == null) {
        throw new IllegalArgumentException("projectId is required");
      }
      String ciphertext =
          jdbc.sql("select webhook_secret_enc from projects where id = :id")
              .param("id", projectId)
              .query(String.class)
              .optional()
              .orElseThrow(() -> new IllegalArgumentException("no such project"));
      cipher.decrypt(ciphertext, new SecretContext("projects", "webhook_secret_enc", projectId));
      return ResponseEntity.ok(OK);
    } catch (RuntimeException e) {
      LOG.warn(
          "selftest decrypt failed for project {}: {}", projectId, e.getClass().getSimpleName());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(FAIL);
    }
  }
}
