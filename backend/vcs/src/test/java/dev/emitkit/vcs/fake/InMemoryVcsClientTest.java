package dev.emitkit.vcs.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.contract.VcsClientContractTest;
import dev.emitkit.vcs.error.VcsAuthException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import dev.emitkit.vcs.error.VcsRateLimitException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryVcsClientTest extends VcsClientContractTest {

  private InMemoryVcsClient fake;

  @BeforeEach
  void seed() {
    fake = new InMemoryVcsClient();
    fake.createRepo("acme", "api", false);
    fake.putFile("acme/api", "openapi.yaml", OPENAPI_CONTENT, null, "init");
  }

  @Override
  protected VcsClient client() {
    return fake;
  }

  @Test
  void failNext_failsOnlyTheNextCall() {
    var error = new VcsRateLimitException("slow down", Instant.parse("2026-11-02T10:00:00Z"));
    fake.failNext(error);

    assertThatThrownBy(() -> fake.listRepos()).isSameAs(error);
    assertThat(fake.listRepos()).hasSize(1);
  }

  @Test
  void failNext_byMethod_failsOnlyThatMethod() {
    var error = new VcsAuthException("bad credentials");
    fake.createBranch("acme/api", "feat", fake.branchHead("acme/api", "main"));
    fake.failNext("openPr", error);

    assertThat(fake.listRepos()).hasSize(1);
    assertThatThrownBy(() -> fake.openPr("acme/api", "feat", "main", "t", "b")).isSameAs(error);
    assertThat(fake.openPr("acme/api", "feat", "main", "t", "b").number()).isEqualTo(1);
  }

  @Test
  void calls_recordsEachCallInOrder_andFiltersByMethod() {
    fake.createBranch("acme/api", "feature", fake.branchHead("acme/api", "main"));
    fake.openPr("acme/api", "feature", "main", "title", "body");
    fake.comment("acme/api", 1, "hello");

    assertThat(fake.calls())
        .containsExactly(
            new InMemoryVcsClient.Call("createRepo", "acme/api"),
            new InMemoryVcsClient.Call("putFile", "acme/api"),
            new InMemoryVcsClient.Call("branchHead", "acme/api"),
            new InMemoryVcsClient.Call("createBranch", "acme/api"),
            new InMemoryVcsClient.Call("openPr", "acme/api"),
            new InMemoryVcsClient.Call("comment", "acme/api"));
    assertThat(fake.calls("openPr")).hasSize(1);
  }

  @Test
  void commitsAndTags_areScopedToTheirRepo() {
    fake.createRepo("acme", "web", false);
    String apiHead = fake.branchHead("acme/api", "main");

    assertThatThrownBy(() -> fake.createBranch("acme/web", "x", apiHead))
        .isInstanceOf(VcsNotFoundException.class);
    assertThatThrownBy(() -> fake.createTag("acme/web", "v1", apiHead))
        .isInstanceOf(VcsNotFoundException.class);
    assertThatThrownBy(() -> fake.getFile("acme/web", "openapi.yaml", apiHead))
        .isInstanceOf(VcsNotFoundException.class);
  }

  @Test
  void inspection_exposesStateWithoutRecordingCalls() {
    String head = fake.branchHead("acme/api", "main");
    fake.createBranch("acme/api", "feat", head);
    fake.openPr("acme/api", "feat", "main", "Add thing", "Body text");
    fake.comment("acme/api", 1, "looks good");
    fake.createTag("acme/api", "v1", head);
    fake.registerWebhook("acme/api", "https://hook", "secret", Set.of("push"));

    assertThat(fake.prs())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.head()).isEqualTo("feat");
          assertThat(pr.title()).isEqualTo("Add thing");
          assertThat(pr.body()).isEqualTo("Body text");
        });
    assertThat(fake.comments("acme/api", 1)).containsExactly("looks good");
    assertThat(fake.tags("acme/api")).containsEntry("v1", head);
    assertThat(fake.webhooks("acme/api"))
        .singleElement()
        .satisfies(hook -> assertThat(hook.url()).isEqualTo("https://hook"));
    assertThat(fake.branches("acme/api")).containsExactly("feat", "main");
    assertThat(fake.branches()).containsExactly("feat", "main");
    assertThat(fake.file("acme/api", "openapi.yaml").content()).isEqualTo(OPENAPI_CONTENT);
    assertThat(fake.calls("file")).isEmpty();
  }

  @Test
  void orgs_replacesTheListedOrgs() {
    fake.orgs(new VcsOrg("1", "beta", "Beta", false, VcsOrg.VcsRole.MEMBER));

    assertThat(fake.listOrgs()).extracting(VcsOrg::login).containsExactly("beta");
  }
}
