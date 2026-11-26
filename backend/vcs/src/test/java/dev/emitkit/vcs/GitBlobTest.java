package dev.emitkit.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitBlobTest {

  // Expected values come from `git hash-object --stdin` on the same bytes.

  @Test
  void sha_matchesGitForTextWithNewline() {
    assertThat(GitBlob.sha("hello\n")).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
  }

  @Test
  void sha_matchesGitForEmptyContent() {
    assertThat(GitBlob.sha("")).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
  }

  @Test
  void sha_countsUtf8BytesNotChars() {
    assertThat(GitBlob.sha("é")).isEqualTo("4b04fff51468d8ab5201ab02b725dc477bc7cb45");
  }
}
