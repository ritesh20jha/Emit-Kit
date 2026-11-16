package dev.emitkit.api.jobs;

import java.util.List;

/** The columns that hold {@code SecretCipher} ciphertexts, and how to address each row. */
final class EncryptedColumns {

  /**
   * A ciphertext column of {@code table}; {@code idColumn} is the key used in its SecretContext.
   */
  record EncryptedColumn(String table, String column, String idColumn) {}

  static final List<EncryptedColumn> ALL =
      List.of(
          new EncryptedColumn("oauth_accounts", "access_token_enc", "id"),
          new EncryptedColumn("projects", "webhook_secret_enc", "id"),
          new EncryptedColumn("project_configs", "ai_api_key_enc", "id"));

  private EncryptedColumns() {}
}
