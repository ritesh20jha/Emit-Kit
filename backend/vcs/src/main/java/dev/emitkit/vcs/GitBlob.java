package dev.emitkit.vcs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes git blob shas, the same ids GitHub reports for file contents. */
public final class GitBlob {

  private GitBlob() {}

  /**
   * Returns the git blob sha of {@code content}: the hex SHA-1 of {@code "blob <byteLength>\0"}
   * followed by the UTF-8 bytes of the content.
   *
   * @param content the file content
   * @return the 40-character lowercase hex sha
   */
  public static String sha(String content) {
    byte[] body = content.getBytes(StandardCharsets.UTF_8);
    byte[] header = ("blob " + body.length + "\0").getBytes(StandardCharsets.US_ASCII);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      digest.update(header);
      digest.update(body);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is not available", e);
    }
  }
}
