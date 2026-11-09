package dev.emitkit.vcs.github;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.PullRequest;
import dev.emitkit.vcs.RateLimit;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsOrg.VcsRole;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.vcs.Webhook;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHCreateRepositoryBuilder;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHMembership;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitCommit;
import org.kohsuke.github.GitHub;

/**
 * Repository, file, organization and quota operations over one GitHub identity. Branch, tag,
 * commit, pull-request and webhook operations are not implemented yet and throw {@link
 * UnsupportedOperationException} naming the ticket that adds them.
 */
final class GitHubVcsClient implements VcsClient {

  /** The contents API serves files up to 1 MB. Larger files go through the blob API. */
  private static final long CONTENTS_API_MAX_BYTES = 1024 * 1024;

  private static final int REPO_PAGE_SIZE = 100;
  private static final int REPO_LIMIT = 300;

  private final GitHub github;

  GitHubVcsClient(GitHub github) {
    this.github = github;
  }

  @Override
  public List<VcsOrg> listOrgs() {
    try {
      GHMyself me = github.getMyself();
      var orgs = new ArrayList<VcsOrg>();
      orgs.add(
          new VcsOrg(String.valueOf(me.getId()), me.getLogin(), me.getName(), true, VcsRole.OWNER));
      for (GHMembership membership : me.listOrgMemberships(GHMembership.State.ACTIVE)) {
        GHOrganization org = membership.getOrganization();
        // Membership payloads carry no name, and getName() would fetch the org per call. The login
        // doubles as the display name so that listing costs no extra requests.
        var role = membership.getRole() == GHMembership.Role.ADMIN ? VcsRole.OWNER : VcsRole.MEMBER;
        orgs.add(
            new VcsOrg(String.valueOf(org.getId()), org.getLogin(), org.getLogin(), false, role));
      }
      return orgs;
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public List<VcsRepo> listRepos() {
    try {
      // The API has no server-side sort for this listing, so every page is read before sorting.
      var pushable = new ArrayList<GHRepository>();
      for (GHRepository repo : github.getMyself().listRepositories(REPO_PAGE_SIZE)) {
        if (repo.hasPushAccess()) {
          pushable.add(repo);
        }
      }
      Comparator<Date> newestFirst = Comparator.nullsLast(Comparator.<Date>reverseOrder());
      pushable.sort(Comparator.comparing(GHRepository::getPushedAt, newestFirst));
      return pushable.stream().limit(REPO_LIMIT).map(GitHubVcsClient::toRepo).toList();
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public VcsRepo createRepo(String org, String name, boolean isPrivate) {
    try {
      GHCreateRepositoryBuilder builder =
          org.equalsIgnoreCase(github.getMyself().getLogin())
              ? github.createRepository(name)
              : github.getOrganization(org).createRepository(name);
      return toRepo(builder.private_(isPrivate).create());
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public FileContent getFile(String repo, String path, String ref) {
    try {
      GHRepository target = github.getRepository(repo);
      GHContent file = target.getFileContent(path, ref);
      byte[] bytes =
          file.getSize() <= CONTENTS_API_MAX_BYTES
              ? file.read().readAllBytes()
              : target.getBlob(file.getSha()).read().readAllBytes();
      return new FileContent(new String(bytes, UTF_8), file.getSha());
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public CommitRef putFile(
      String repo, String path, String content, String expectedSha, String message) {
    try {
      GHContentBuilder write =
          github.getRepository(repo).createContent().path(path).content(content).message(message);
      if (expectedSha != null) {
        write = write.sha(expectedSha);
      }
      GitCommit commit = write.commit().getCommit();
      return new CommitRef(commit.getSHA1(), commit.getHtmlUrl());
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public void createBranch(String repo, String name, String fromSha) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public String branchHead(String repo, String branch) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public void deleteBranch(String repo, String name) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public Map<String, String> listBlobShas(String repo, String ref, String pathPrefix) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public CommitRef commitTree(String repo, String branch, List<CommitFile> files, String message) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public PullRequest openPr(String repo, String head, String base, String title, String body) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public void comment(String repo, int prNumber, String body) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public boolean createTag(String repo, String tag, String sha) {
    throw new UnsupportedOperationException("VCS-03");
  }

  @Override
  public Webhook registerWebhook(String repo, String url, String secret, Set<String> events) {
    throw new UnsupportedOperationException("VCS-04");
  }

  @Override
  public void deleteWebhook(String repo, long id) {
    throw new UnsupportedOperationException("VCS-04");
  }

  @Override
  public RateLimit rateLimit() {
    try {
      GHRateLimit.Record core = github.getRateLimit().getCore();
      return new RateLimit(
          core.getLimit(), core.getRemaining(), Instant.ofEpochSecond(core.getResetEpochSeconds()));
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
  }

  private static VcsRepo toRepo(GHRepository repo) {
    Date pushed = repo.getPushedAt();
    return new VcsRepo(
        repo.getFullName(),
        repo.getDefaultBranch(),
        repo.isPrivate(),
        pushed == null ? null : pushed.toInstant(),
        repo.getOwnerName());
  }
}
