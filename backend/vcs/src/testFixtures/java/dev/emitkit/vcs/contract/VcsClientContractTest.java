package dev.emitkit.vcs.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link VcsClient} implementation must share. Implementations (the in-memory fake
 * and the GitHub client) extend this class and provide a client on a repo {@code acme/api} that has
 * a {@code main} branch and an {@code openapi.yaml} file.
 */
public abstract class VcsClientContractTest {

  /** A client on the seeded repo {@code acme/api}. */
  protected abstract VcsClient client();

  /** The commit sha at the head of {@code main} in the seeded repo. */
  protected abstract String head();

  @Test
  void getFile_returnsContentAndSha() {
    var f = client().getFile("acme/api", "openapi.yaml", "main");
    assertThat(f.sha()).isNotBlank();
  }

  @Test
  void getFile_missing_throwsNotFound() {
    assertThatThrownBy(() -> client().getFile("acme/api", "nope.yaml", "main"))
        .isInstanceOf(VcsNotFoundException.class);
  }

  @Test
  void putFile_withStaleSha_throwsConflict() {
    var f = client().getFile("acme/api", "openapi.yaml", "main");
    client().putFile("acme/api", "openapi.yaml", "v2", f.sha(), "m");
    assertThatThrownBy(() -> client().putFile("acme/api", "openapi.yaml", "v3", f.sha(), "m"))
        .isInstanceOf(VcsConflictException.class);
  }

  @Test
  void commitTree_thenListBlobShas() {
    client().createBranch("acme/api", "b1", head());
    client().commitTree("acme/api", "b1", List.of(new CommitFile("out/a.ts", "x")), "m");
    assertThat(client().listBlobShas("acme/api", "b1", "out/"))
        .containsEntry("out/a.ts", GitBlob.sha("x"));
  }

  @Test
  void createTag_twice_secondReturnsFalse() {
    assertThat(client().createTag("acme/api", "v1.0.0", head())).isTrue();
    assertThat(client().createTag("acme/api", "v1.0.0", head())).isFalse();
  }
}
