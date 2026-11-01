package dev.emitkit.common.secret;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The row and column a secret belongs to. It is sent to Vault as the Transit context, so a
 * ciphertext cannot be decrypted under a different row or column.
 *
 * @param table the table holding the secret
 * @param column the column holding the secret
 * @param rowId the identifier of the row
 */
public record SecretContext(String table, String column, String rowId) {

  /** Base64 of {@code table:column:rowId} in UTF-8. */
  public String encoded() {
    return Base64.getEncoder().encodeToString(bytes());
  }

  /** The raw UTF-8 bytes of {@code table:column:rowId}; the Vault client Base64-encodes them. */
  byte[] bytes() {
    return (table + ":" + column + ":" + rowId).getBytes(StandardCharsets.UTF_8);
  }
}
