package dev.emitkit.vcs.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.PullRequest;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link VcsClient} implementation must share. Implementations (the in-memory fake
 * and the GitHub client) extend this class and provide a client on a repo {@code acme/api} that has
 * a {@code main} branch and an {@code openapi.yaml} file whose content is {@link #OPENAPI_CONTENT}.
 */
public abstract class VcsClientContractTest {

  /** The content of {@code openapi.yaml} on {@code main} of the seeded repo. */
  public static final String OPENAPI_CONTENT = "openapi: 3.1.0";

  /** A client on the seeded repo {@code acme/api}. */
  protected abstract VcsClient client();

  @Test
  protected void getFile_returnsContentAndSha() {
    var f = client().getFile("acme/api", "openapi.yaml", "main");
    assertThat(f.content()).isEqualTo(OPENAPI_CONTENT);
    assertThat(f.sha()).isEqualTo(GitBlob.sha(f.content()));
  }

  @Test
  protected void getFile_missing_throwsNotFound() {
    assertThatThrownBy(() -> client().getFile("acme/api", "nope.yaml", "main"))
        .isInstanceOf(VcsNotFoundException.class);
  }

  @Test
  protected void putFile_withStaleSha_throwsConflict() {
    var f = client().getFile("acme/api", "openapi.yaml", "main");
    client().putFile("acme/api", "openapi.yaml", "v2", f.sha(), "m");
    assertThatThrownBy(() -> client().putFile("acme/api", "openapi.yaml", "v3", f.sha(), "m"))
        .isInstanceOf(VcsConflictException.class);
  }

  @Test
  protected void commitTree_thenListBlobShas() {
    client().createBranch("acme/api", "b1", mainHead());
    client().commitTree("acme/api", "b1", List.of(new CommitFile("out/a.ts", "x")), "m");
    assertThat(client().listBlobShas("acme/api", "b1", "out/"))
        .containsEntry("out/a.ts", GitBlob.sha("x"));
  }

  @Test
  protected void createTag_twice_secondReturnsFalse() {
    assertThat(client().createTag("acme/api", "v1.0.0", mainHead())).isTrue();
    assertThat(client().createTag("acme/api", "v1.0.0", mainHead())).isFalse();
  }

  @Test
  protected void branchHead_returnsTheBranchCommit() {
    String head = mainHead();
    client().createBranch("acme/api", "b0", head);
    assertThat(client().branchHead("acme/api", "b0")).isEqualTo(head);
  }

  @Test
  protected void branchHead_missingRepoOrBranch_throwsNotFound() {
    assertThatThrownBy(() -> client().branchHead("acme/api", "nope"))
        .isInstanceOf(VcsNotFoundException.class);
    assertThatThrownBy(() -> client().branchHead("acme/nope", "main"))
        .isInstanceOf(VcsNotFoundException.class);
  }

  @Test
  protected void createBranch_atSameSha_isNoOp() {
    String head = mainHead();
    client().createBranch("acme/api", "b1", head);
    assertThatCode(() -> client().createBranch("acme/api", "b1", head)).doesNotThrowAnyException();
    assertThat(client().branchHead("acme/api", "b1")).isEqualTo(head);
  }

  @Test
  protected void createBranch_atDifferentSha_throwsConflict() {
    client().createBranch("acme/api", "b2", mainHead());
    var f = client().getFile("acme/api", "openapi.yaml", "main");
    String newHead = client().putFile("acme/api", "openapi.yaml", "v2", f.sha(), "m").sha();
    assertThatThrownBy(() -> client().createBranch("acme/api", "b2", newHead))
        .isInstanceOf(VcsConflictException.class);
  }

  @Test
  protected void deleteBranch_missing_isNoOp() {
    assertThatCode(() -> client().deleteBranch("acme/api", "gone")).doesNotThrowAnyException();
  }

  @Test
  protected void openPr_sameHead_returnsTheExistingPr() {
    client().createBranch("acme/api", "feat", mainHead());
    PullRequest first = client().openPr("acme/api", "feat", "main", "t", "b");
    assertThat(client().openPr("acme/api", "feat", "main", "other title", "other body"))
        .isEqualTo(first);
  }

  private String mainHead() {
    return client().branchHead("acme/api", "main");
  }
}
