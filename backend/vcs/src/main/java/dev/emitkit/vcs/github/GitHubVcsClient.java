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
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHCreateRepositoryBuilder;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHHook;
import org.kohsuke.github.GHMembership;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeBuilder;
import org.kohsuke.github.GHTreeEntry;
import org.kohsuke.github.GitCommit;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;

/**
 * Repository, file, organization and quota operations for one identity, plus the git data behind
 * branches, commits, pull requests and tags.
 */
final class GitHubVcsClient implements VcsClient {

  /** The contents API serves files up to 1 MB. Larger files go through the blob API. */
  private static final long CONTENTS_API_MAX_BYTES = 1024 * 1024;

  private static final int REPO_PAGE_SIZE = 100;
  private static final int REPO_LIMIT = 300;

  /** Entries per tree write. Larger commits chain several trees, one per chunk. */
  private static final int TREE_ENTRY_LIMIT = 1000;

  private final GitHub github;

  GitHubVcsClient(GitHub github) {
    this.github = github;
  }

  @Override
  public List<VcsOrg> listOrgs() {
    try {
      GHMyself me = github.getMyself();
      // The name column is NOT NULL, and a user may have no display name.
      String personalName = Objects.requireNonNullElse(me.getName(), me.getLogin());
      var orgs = new ArrayList<VcsOrg>();
      orgs.add(
          new VcsOrg(String.valueOf(me.getId()), me.getLogin(), personalName, true, VcsRole.OWNER));
      for (GHMembership membership : me.listOrgMemberships(GHMembership.State.ACTIVE)) {
        // getName() would fetch each org, so the login doubles as the display name.
        GHOrganization org = membership.getOrganization();
        orgs.add(
            new VcsOrg(
                String.valueOf(org.getId()),
                org.getLogin(),
                org.getLogin(),
                false,
                roleOf(membership)));
      }
      return orgs;
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public List<VcsRepo> listRepos() {
    try {
      // kohsuke omits the sort=pushed parameter, so every page is read and sorted here.
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
    try {
      GHRepository target = github.getRepository(repo);
      if (createRef(target, "refs/heads/" + name, fromSha)) {
        return;
      }
      // The branch exists. Creating it again is a no-op only when it already points at fromSha.
      String current = target.getRef("heads/" + name).getObject().getSha();
      if (!current.equals(fromSha)) {
        throw new VcsConflictException("branch " + name + " exists at another commit");
      }
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public String branchHead(String repo, String branch) {
    try {
      return github.getRepository(repo).getRef("heads/" + branch).getObject().getSha();
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public void deleteBranch(String repo, String name) {
    try {
      github.getRepository(repo).getRef("heads/" + name).delete();
    } catch (IOException | GHException e) {
      // A missing branch is a no-op: the lookup answers 404, and a delete can answer 422.
      int status = GitHubErrors.statusOf(e);
      if (status != 404 && status != 422) {
        throw GitHubErrors.map(e);
      }
    }
  }

  /**
   * Reads the blob shas under {@code pathPrefix}. A recursive listing is one call; when GitHub
   * truncates it, the subtrees under the prefix are read one directory at a time.
   */
  @Override
  public Map<String, String> listBlobShas(String repo, String ref, String pathPrefix) {
    try {
      GHRepository target = github.getRepository(repo);
      GHTree tree = target.getTreeRecursive(ref, 1);
      var shas = new TreeMap<String, String>();
      if (tree.isTruncated()) {
        collectBlobShas(target, target.getTree(ref), "", pathPrefix, shas);
      } else {
        for (GHTreeEntry entry : tree.getTree()) {
          if ("blob".equals(entry.getType()) && entry.getPath().startsWith(pathPrefix)) {
            shas.put(entry.getPath(), entry.getSha());
          }
        }
      }
      return shas;
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  /**
   * Commits {@code files} on the head of {@code branch}. The new trees start from the parent's
   * tree, so files not named here are kept. Each tree write takes at most {@link #TREE_ENTRY_LIMIT}
   * entries, and each chunk's tree is the base of the next.
   */
  @Override
  public CommitRef commitTree(String repo, String branch, List<CommitFile> files, String message) {
    try {
      GHRepository target = github.getRepository(repo);
      GHRef head = target.getRef("heads/" + branch);
      String parent = head.getObject().getSha();
      // The tree endpoint takes a commit sha and returns its root tree. The commits endpoint would
      // also compute the commit's diff, which is slow on large merges and can fail with a 422.
      String tree = target.getTree(parent).getSha();
      for (int start = 0; start < files.size(); start += TREE_ENTRY_LIMIT) {
        GHTreeBuilder builder = target.createTree().baseTree(tree);
        for (CommitFile file :
            files.subList(start, Math.min(files.size(), start + TREE_ENTRY_LIMIT))) {
          // textEntry inlines the content. add() would post each file as a blob first.
          builder.textEntry(file.path(), file.content(), false);
        }
        tree = builder.create().getSha();
      }
      GHCommit commit = target.createCommit().message(message).parent(parent).tree(tree).create();
      head.updateTo(commit.getSHA1());
      return new CommitRef(commit.getSHA1(), commit.getHtmlUrl().toString());
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  /**
   * Opens the pull request. If one is already open for the same head and base, GitHub refuses the
   * create with "already exists", and the open pull request is returned instead.
   */
  @Override
  public PullRequest openPr(String repo, String head, String base, String title, String body) {
    try {
      GHRepository target = github.getRepository(repo);
      GHPullRequest pr;
      try {
        pr = target.createPullRequest(title, head, base, body);
      } catch (HttpException e) {
        if (!GitHubErrors.alreadyExists(e)) {
          throw e;
        }
        pr = openPrFor(target, head, base);
      }
      return new PullRequest(pr.getNumber(), pr.getHtmlUrl().toString(), pr.getHead().getRef());
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public void comment(String repo, int prNumber, String body) {
    try {
      github.getRepository(repo).getIssue(prNumber).comment(body);
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  /** Creates the tag ref. Returns false when GitHub reports that the ref already exists. */
  @Override
  public boolean createTag(String repo, String tag, String sha) {
    try {
      return createRef(github.getRepository(repo), "refs/tags/" + tag, sha);
    } catch (IOException | GHException e) {
      throw GitHubErrors.map(e);
    }
  }

  @Override
  public Webhook registerWebhook(String repo, String url, String secret, Set<String> events) {
    try {
      GHHook hook =
          github
              .getRepository(repo)
              .createHook(
                  "web",
                  Map.of("url", url, "content_type", "json", "secret", secret, "insecure_ssl", "0"),
                  events.stream().map(GitHubVcsClient::toEvent).toList(),
                  true);
      return new Webhook(hook.getId(), url);
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
  }

  /** A missing hook is the outcome the caller asked for, so only a 404 from the hook is a no-op. */
  @Override
  public void deleteWebhook(String repo, long id) {
    GHRepository target;
    try {
      target = github.getRepository(repo);
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
    try {
      target.deleteHook(Math.toIntExact(id));
    } catch (IOException e) {
      RuntimeException failure = GitHubErrors.map(e);
      if (failure instanceof VcsNotFoundException) {
        return;
      }
      throw failure;
    }
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

  /**
   * Creates {@code ref} at {@code sha}. Returns false, rather than failing, when the ref already
   * exists.
   */
  private static boolean createRef(GHRepository target, String ref, String sha) throws IOException {
    try {
      target.createRef(ref, sha);
      return true;
    } catch (HttpException e) {
      if (GitHubErrors.alreadyExists(e)) {
        return false;
      }
      throw e;
    }
  }

  /**
   * Adds the blobs under {@code prefix} in {@code tree}, which sits at directory {@code dir}. Only
   * directories that can hold such a path are read.
   */
  private static void collectBlobShas(
      GHRepository target, GHTree tree, String dir, String prefix, Map<String, String> shas)
      throws IOException {
    for (GHTreeEntry entry : tree.getTree()) {
      String path = dir + entry.getPath();
      if ("blob".equals(entry.getType())) {
        if (path.startsWith(prefix)) {
          shas.put(path, entry.getSha());
        }
      } else if ("tree".equals(entry.getType())
          && (path.startsWith(prefix) || prefix.startsWith(path + "/"))) {
        collectBlobShas(target, target.getTree(entry.getSha()), path + "/", prefix, shas);
      }
    }
  }

  /**
   * The open pull request from {@code head} into {@code base}. GitHub filters on both, and the
   * match is checked again here.
   */
  private static GHPullRequest openPrFor(GHRepository target, String head, String base)
      throws IOException {
    return target
        .queryPullRequests()
        .state(GHIssueState.OPEN)
        .head(target.getOwnerName() + ":" + head)
        .base(base)
        .list()
        .toList()
        .stream()
        .filter(pr -> head.equals(pr.getHead().getRef()) && base.equals(pr.getBase().getRef()))
        .findFirst()
        .orElseThrow(
            () -> new VcsConflictException("no open pull request from " + head + " into " + base));
  }

  /** Admins own the org. Roles GitHub adds later, such as billing_manager, count as members. */
  private static VcsRole roleOf(GHMembership membership) {
    try {
      return membership.getRole() == GHMembership.Role.ADMIN ? VcsRole.OWNER : VcsRole.MEMBER;
    } catch (IllegalArgumentException unknownRole) {
      return VcsRole.MEMBER;
    }
  }

  /** Hook events are named by their lower-case GitHub names, such as "pull_request". */
  private static GHEvent toEvent(String name) {
    return GHEvent.valueOf(name.toUpperCase(Locale.ROOT));
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
