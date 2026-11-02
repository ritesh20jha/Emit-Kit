package dev.emitkit.vcs.fake;

import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.PullRequest;
import dev.emitkit.vcs.RateLimit;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.vcs.Webhook;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory {@link VcsClient} for tests in other modules. Repos, branches, commits and files live
 * in maps. File shas come from {@link GitBlob#sha}, so they match GitHub. Commit messages, pull
 * request titles and bodies, comment bodies, and webhook secrets and events are not kept; every
 * call is recorded in {@link #calls()}. Not thread-safe.
 */
public final class InMemoryVcsClient implements VcsClient {

  private static final String DEFAULT_BRANCH = "main";
  private static final String WEB = "https://github.com/";
  private static final int RATE_LIMIT_PER_HOUR = 5000;

  /** A call received by the fake: the method name and the repo it targeted, if any. */
  public record Call(String method, String repo) {}

  /** One repository: its metadata, branch and tag heads (sha per name), pull requests, webhooks. */
  private record Repo(
      VcsRepo info,
      Map<String, String> branches,
      Map<String, String> tags,
      List<PullRequest> pulls,
      Map<Long, Webhook> webhooks) {}

  private final Map<String, Repo> repos = new LinkedHashMap<>();
  private final Map<String, VcsOrg> orgs = new LinkedHashMap<>();

  /** Commit sha to its full file snapshot (path to content). */
  private final Map<String, Map<String, String>> commits = new HashMap<>();

  private final List<Call> calls = new ArrayList<>();
  private RuntimeException failNext;
  private long commitCount;
  private long webhookCount;

  /**
   * Makes the next call to any method throw {@code error}. Only one failure is pending at a time.
   */
  public void failNext(RuntimeException error) {
    this.failNext = Objects.requireNonNull(error, "error");
  }

  /** The calls received so far, in order. */
  public List<Call> calls() {
    return List.copyOf(calls);
  }

  /** The commit sha at the head of {@code branch}. Not part of {@link VcsClient}. */
  public String headSha(String repo, String branch) {
    return requireBranch(repo(repo), branch);
  }

  @Override
  public List<VcsOrg> listOrgs() {
    recordCall("listOrgs", null);
    return List.copyOf(orgs.values());
  }

  @Override
  public List<VcsRepo> listRepos() {
    recordCall("listRepos", null);
    return repos.values().stream().map(Repo::info).toList();
  }

  @Override
  public VcsRepo createRepo(String org, String name, boolean isPrivate) {
    String fullName = org + "/" + name;
    recordCall("createRepo", fullName);
    if (repos.containsKey(fullName)) {
      throw new VcsConflictException("repo already exists: " + fullName);
    }
    var info = new VcsRepo(fullName, DEFAULT_BRANCH, isPrivate, Instant.now(), org);
    var repo =
        new Repo(
            info,
            new LinkedHashMap<>(Map.of(DEFAULT_BRANCH, newCommit(Map.of()))),
            new LinkedHashMap<>(),
            new ArrayList<>(),
            new LinkedHashMap<>());
    repos.put(fullName, repo);
    orgs.putIfAbsent(org, new VcsOrg(org, org, org, false, VcsOrg.VcsRole.OWNER));
    return info;
  }

  @Override
  public FileContent getFile(String repo, String path, String ref) {
    recordCall("getFile", repo);
    String content = snapshot(repo, ref).get(path);
    if (content == null) {
      throw new VcsNotFoundException("file not found: " + path);
    }
    return new FileContent(content, GitBlob.sha(content));
  }

  @Override
  public CommitRef putFile(
      String repo, String path, String content, String expectedSha, String message) {
    recordCall("putFile", repo);
    Repo r = repo(repo);
    String branch = r.info().defaultBranch();
    Map<String, String> files = new HashMap<>(branchFiles(r, branch));
    String current = files.get(path);
    if (expectedSha == null) {
      if (current != null) {
        throw new VcsConflictException("file already exists: " + path);
      }
    } else if (current == null) {
      throw new VcsNotFoundException("file not found: " + path);
    } else if (!expectedSha.equals(GitBlob.sha(current))) {
      throw new VcsConflictException("stale sha for " + path);
    }
    files.put(path, content);
    return moveBranch(r, branch, files);
  }

  @Override
  public void createBranch(String repo, String name, String fromSha) {
    recordCall("createBranch", repo);
    Repo r = repo(repo);
    if (r.branches().containsKey(name)) {
      throw new VcsConflictException("branch already exists: " + name);
    }
    if (!commits.containsKey(fromSha)) {
      throw new VcsNotFoundException("commit not found: " + fromSha);
    }
    r.branches().put(name, fromSha);
  }

  @Override
  public void deleteBranch(String repo, String name) {
    recordCall("deleteBranch", repo);
    if (repo(repo).branches().remove(name) == null) {
      throw new VcsNotFoundException("branch not found: " + name);
    }
  }

  @Override
  public Map<String, String> listBlobShas(String repo, String ref, String pathPrefix) {
    recordCall("listBlobShas", repo);
    Map<String, String> shas = new TreeMap<>();
    snapshot(repo, ref)
        .forEach((path, content) -> {
          if (path.startsWith(pathPrefix)) {
            shas.put(path, GitBlob.sha(content));
          }
        });
    return shas;
  }

  @Override
  public CommitRef commitTree(String repo, String branch, List<CommitFile> files, String message) {
    recordCall("commitTree", repo);
    Repo r = repo(repo);
    Map<String, String> next = new HashMap<>(branchFiles(r, branch));
    files.forEach(file -> next.put(file.path(), file.content()));
    return moveBranch(r, branch, next);
  }

  @Override
  public PullRequest openPr(String repo, String head, String base, String title, String body) {
    recordCall("openPr", repo);
    Repo r = repo(repo);
    requireBranch(r, head);
    requireBranch(r, base);
    int number = r.pulls().size() + 1;
    var pr = new PullRequest(number, WEB + repo + "/pull/" + number, head);
    r.pulls().add(pr);
    return pr;
  }

  @Override
  public void comment(String repo, int prNumber, String body) {
    recordCall("comment", repo);
    if (prNumber < 1 || prNumber > repo(repo).pulls().size()) {
      throw new VcsNotFoundException("pull request not found: " + prNumber);
    }
  }

  @Override
  public boolean createTag(String repo, String tag, String sha) {
    recordCall("createTag", repo);
    Repo r = repo(repo);
    if (r.tags().containsKey(tag)) {
      return false;
    }
    if (!commits.containsKey(sha)) {
      throw new VcsNotFoundException("commit not found: " + sha);
    }
    r.tags().put(tag, sha);
    return true;
  }

  @Override
  public Webhook registerWebhook(String repo, String url, String secret, Set<String> events) {
    recordCall("registerWebhook", repo);
    var hook = new Webhook(++webhookCount, url);
    repo(repo).webhooks().put(hook.id(), hook);
    return hook;
  }

  @Override
  public void deleteWebhook(String repo, long id) {
    recordCall("deleteWebhook", repo);
    if (repo(repo).webhooks().remove(id) == null) {
      throw new VcsNotFoundException("webhook not found: " + id);
    }
  }

  @Override
  public RateLimit rateLimit() {
    recordCall("rateLimit", null);
    return new RateLimit(
        RATE_LIMIT_PER_HOUR, RATE_LIMIT_PER_HOUR, Instant.now().plus(Duration.ofHours(1)));
  }

  private void recordCall(String method, String repo) {
    calls.add(new Call(method, repo));
    if (failNext != null) {
      RuntimeException error = failNext;
      failNext = null;
      throw error;
    }
  }

  private Repo repo(String fullName) {
    Repo repo = repos.get(fullName);
    if (repo == null) {
      throw new VcsNotFoundException("repo not found: " + fullName);
    }
    return repo;
  }

  /** The files at {@code ref}, which is a branch, a tag or a commit sha, in that order. */
  private Map<String, String> snapshot(String repo, String ref) {
    Repo r = repo(repo);
    String sha = r.branches().getOrDefault(ref, r.tags().getOrDefault(ref, ref));
    Map<String, String> files = commits.get(sha);
    if (files == null) {
      throw new VcsNotFoundException("ref not found: " + ref);
    }
    return files;
  }

  private Map<String, String> branchFiles(Repo r, String branch) {
    return commits.get(requireBranch(r, branch));
  }

  private static String requireBranch(Repo r, String branch) {
    String sha = r.branches().get(branch);
    if (sha == null) {
      throw new VcsNotFoundException("branch not found: " + branch);
    }
    return sha;
  }

  private CommitRef moveBranch(Repo r, String branch, Map<String, String> files) {
    String sha = newCommit(files);
    r.branches().put(branch, sha);
    return new CommitRef(sha, WEB + r.info().fullName() + "/commit/" + sha);
  }

  private String newCommit(Map<String, String> files) {
    String sha = String.format("%040x", ++commitCount);
    commits.put(sha, Map.copyOf(files));
    return sha;
  }
}
