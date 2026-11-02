package dev.emitkit.vcs.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.contract.VcsClientContractTest;
import dev.emitkit.vcs.error.VcsRateLimitException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryVcsClientTest extends VcsClientContractTest {

  private InMemoryVcsClient fake;

  @BeforeEach
  void seed() {
    fake = new InMemoryVcsClient();
    fake.createRepo("acme", "api", false);
    fake.putFile("acme/api", "openapi.yaml", "openapi: 3.1.0", null, "init");
  }

  @Override
  protected VcsClient client() {
    return fake;
  }

  @Override
  protected String head() {
    return fake.headSha("acme/api", "main");
  }

  @Test
  void failNext_failsOnlyTheNextCall() {
    var error = new VcsRateLimitException("slow down", Instant.parse("2026-11-02T10:00:00Z"));
    fake.failNext(error);

    assertThatThrownBy(() -> fake.listRepos()).isSameAs(error);
    assertThat(fake.listRepos()).hasSize(1);
  }

  @Test
  void calls_recordsEachCallInOrder() {
    fake.createBranch("acme/api", "feature", fake.headSha("acme/api", "main"));
    fake.openPr("acme/api", "feature", "main", "title", "body");
    fake.comment("acme/api", 1, "hello");

    assertThat(fake.calls())
        .containsExactly(
            new InMemoryVcsClient.Call("createRepo", "acme/api"),
            new InMemoryVcsClient.Call("putFile", "acme/api"),
            new InMemoryVcsClient.Call("createBranch", "acme/api"),
            new InMemoryVcsClient.Call("openPr", "acme/api"),
            new InMemoryVcsClient.Call("comment", "acme/api"));
  }
}
