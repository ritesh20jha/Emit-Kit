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
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * In-memory {@link VcsClient} for tests in other modules. Repos, branches, tags, commits and files
 * live in maps; each commit belongs to one repo. File shas come from {@link GitBlob#sha}, so they
 * match GitHub. Every public method is synchronized. Commit messages and webhook secrets and events
 * are not kept.
 */
public final class InMemoryVcsClient implements VcsClient {

  private static final String DEFAULT_BRANCH = "main";
  private static final String WEB = "https://github.com/";
  private static final int RATE_LIMIT_PER_HOUR = 5000;

  /** A call received by the fake: the method name and the repo it targeted, if any. */
  public record Call(String method, String repo) {}

  /**
   * A pull request as the fake keeps it, including the title and body that {@link PullRequest}
   * drops.
   */
  public record FakePullRequest(
      String repo, int number, String head, String base, String title, String body) {}

  /** One repository's state. Maps are keyed by name, or by sha for commits. */
  private static final class Repo {
    final VcsRepo info;
    final Map<String, String> branches = new LinkedHashMap<>();
    final Map<String, String> tags = new LinkedHashMap<>();

    /** Commit sha to its full file snapshot (path to content). */
    final Map<String, Map<String, String>> commits = new HashMap<>();

    final List<FakePullRequest> pulls = new ArrayList<>();
    final Map<Integer, List<String>> comments = new HashMap<>();
    final Map<Long, Webhook> webhooks = new LinkedHashMap<>();

    Repo(VcsRepo info) {
      this.info = info;
    }
  }

  private final Map<String, Repo> repos = new LinkedHashMap<>();
  private final Map<String, VcsOrg> orgs = new LinkedHashMap<>();
  private final List<Call> calls = new ArrayList<>();
  private final Map<String, RuntimeException> methodFailures = new HashMap<>();
  private RuntimeException failNext;
  private long commitCount;
  private long webhookCount;

  /** Makes the next call to any method throw {@code error}. Only one such failure is pending. */
  public synchronized void failNext(RuntimeException error) {
    this.failNext = Objects.requireNonNull(error, "error");
  }

  /** Makes the next call to {@code method} throw {@code error}. Other methods are not affected. */
  public synchronized void failNext(String method, RuntimeException error) {
    methodFailures.put(method, Objects.requireNonNull(error, "error"));
  }

  /** The calls received so far, in order. */
  public synchronized List<Call> calls() {
    return List.copyOf(calls);
  }

  /** The calls received so far to {@code method}, in order. */
  public synchronized List<Call> calls(String method) {
    return calls.stream().filter(call -> call.method().equals(method)).toList();
  }

  /** Replaces the organizations returned by {@link #listOrgs()}. */
  public synchronized void orgs(VcsOrg... next) {
    orgs.clear();
    for (VcsOrg org : next) {
      orgs.put(org.login(), org);
    }
  }

  /** Names of the branches of {@code repo}. */
  public synchronized Set<String> branches(String repo) {
    return new TreeSet<>(repo(repo).branches.keySet());
  }

  /** Names of the branches of all repos. */
  public synchronized Set<String> branches() {
    return repos.values().stream()
        .flatMap(r -> r.branches.keySet().stream())
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /** Tag names of {@code repo} mapped to the commit sha each points at. */
  public synchronized Map<String, String> tags(String repo) {
    return Map.copyOf(repo(repo).tags);
  }

  /** The webhooks registered on {@code repo} that are still present. */
  public synchronized List<Webhook> webhooks(String repo) {
    return List.copyOf(repo(repo).webhooks.values());
  }

  /** All pull requests of all repos, in creation order within each repo. */
  public synchronized List<FakePullRequest> prs() {
    return repos.values().stream().flatMap(r -> r.pulls.stream()).toList();
  }

  /** Comment bodies on pull request {@code prNumber} of {@code repo}, in order. */
  public synchronized List<String> comments(String repo, int prNumber) {
    return List.copyOf(repo(repo).comments.getOrDefault(prNumber, List.of()));
  }

  /** The file at {@code path} on the default branch of {@code repo}. Not recorded as a call. */
  public synchronized FileContent file(String repo, String path) {
    Repo r = repo(repo);
    return fileAt(r, path, r.info.defaultBranch());
  }

  @Override
  public synchronized List<VcsOrg> listOrgs() {
    recordCall("listOrgs", null);
    return List.copyOf(orgs.values());
  }

  @Override
  public synchronized List<VcsRepo> listRepos() {
    recordCall("listRepos", null);
    return repos.values().stream().map(r -> r.info).toList();
  }

  @Override
  public synchronized VcsRepo createRepo(String org, String name, boolean isPrivate) {
    String fullName = org + "/" + name;
    recordCall("createRepo", fullName);
    if (repos.containsKey(fullName)) {
      throw new VcsConflictException("repo already exists: " + fullName);
    }
    var repo = new Repo(new VcsRepo(fullName, DEFAULT_BRANCH, isPrivate, Instant.now(), org));
    repo.branches.put(DEFAULT_BRANCH, newCommit(repo, Map.of()));
    repos.put(fullName, repo);
    orgs.putIfAbsent(org, new VcsOrg(org, org, org, false, VcsOrg.VcsRole.OWNER));
    return repo.info;
  }

  @Override
  public synchronized FileContent getFile(String repo, String path, String ref) {
    recordCall("getFile", repo);
    return fileAt(repo(repo), path, ref);
  }

  @Override
  public synchronized CommitRef putFile(
      String repo, String path, String content, String expectedSha, String message) {
    recordCall("putFile", repo);
    Repo r = repo(repo);
    String branch = r.info.defaultBranch();
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
  public synchronized void createBranch(String repo, String name, String fromSha) {
    recordCall("createBranch", repo);
    Repo r = repo(repo);
    if (!r.commits.containsKey(fromSha)) {
      throw new VcsNotFoundException("commit not found: " + fromSha);
    }
    String existing = r.branches.putIfAbsent(name, fromSha);
    if (existing != null && !existing.equals(fromSha)) {
      throw new VcsConflictException("branch already exists at another commit: " + name);
    }
  }

  @Override
  public synchronized String branchHead(String repo, String branch) {
    recordCall("branchHead", repo);
    return requireBranch(repo(repo), branch);
  }

  @Override
  public synchronized void deleteBranch(String repo, String name) {
    recordCall("deleteBranch", repo);
    repo(repo).branches.remove(name);
  }

  @Override
  public synchronized Map<String, String> listBlobShas(String repo, String ref, String pathPrefix) {
    recordCall("listBlobShas", repo);
    Map<String, String> shas = new TreeMap<>();
    snapshot(repo(repo), ref)
        .forEach((path, content) -> {
          if (path.startsWith(pathPrefix)) {
            shas.put(path, GitBlob.sha(content));
          }
        });
    return shas;
  }

  @Override
  public synchronized CommitRef commitTree(
      String repo, String branch, List<CommitFile> files, String message) {
    recordCall("commitTree", repo);
    Repo r = repo(repo);
    Map<String, String> next = new HashMap<>(branchFiles(r, branch));
    files.forEach(file -> next.put(file.path(), file.content()));
    return moveBranch(r, branch, next);
  }

  @Override
  public synchronized PullRequest openPr(
      String repo, String head, String base, String title, String body) {
    recordCall("openPr", repo);
    Repo r = repo(repo);
    requireBranch(r, head);
    requireBranch(r, base);
    var open =
        r.pulls.stream().filter(p -> p.head().equals(head) && p.base().equals(base)).findFirst();
    if (open.isPresent()) {
      return pullRequest(open.get());
    }
    var pr = new FakePullRequest(repo, r.pulls.size() + 1, head, base, title, body);
    r.pulls.add(pr);
    return pullRequest(pr);
  }

  @Override
  public synchronized void comment(String repo, int prNumber, String body) {
    recordCall("comment", repo);
    Repo r = repo(repo);
    if (prNumber < 1 || prNumber > r.pulls.size()) {
      throw new VcsNotFoundException("pull request not found: " + prNumber);
    }
    r.comments.computeIfAbsent(prNumber, n -> new ArrayList<>()).add(body);
  }

  @Override
  public synchronized boolean createTag(String repo, String tag, String sha) {
    recordCall("createTag", repo);
    Repo r = repo(repo);
    if (!r.commits.containsKey(sha)) {
      throw new VcsNotFoundException("commit not found: " + sha);
    }
    return r.tags.putIfAbsent(tag, sha) == null;
  }

  @Override
  public synchronized Webhook registerWebhook(
      String repo, String url, String secret, Set<String> events) {
    recordCall("registerWebhook", repo);
    var hook = new Webhook(++webhookCount, url);
    repo(repo).webhooks.put(hook.id(), hook);
    return hook;
  }

  @Override
  public synchronized void deleteWebhook(String repo, long id) {
    recordCall("deleteWebhook", repo);
    if (repo(repo).webhooks.remove(id) == null) {
      throw new VcsNotFoundException("webhook not found: " + id);
    }
  }

  @Override
  public synchronized RateLimit rateLimit() {
    recordCall("rateLimit", null);
    return new RateLimit(
        RATE_LIMIT_PER_HOUR, RATE_LIMIT_PER_HOUR, Instant.now().plus(Duration.ofHours(1)));
  }

  private void recordCall(String method, String repo) {
    calls.add(new Call(method, repo));
    RuntimeException error = methodFailures.remove(method);
    if (error == null) {
      error = failNext;
      failNext = null;
    }
    if (error != null) {
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

  /**
   * The files at {@code ref}, which is a branch, a tag or a commit sha of {@code r}, in that order.
   */
  private static Map<String, String> snapshot(Repo r, String ref) {
    String sha = r.branches.getOrDefault(ref, r.tags.getOrDefault(ref, ref));
    Map<String, String> files = r.commits.get(sha);
    if (files == null) {
      throw new VcsNotFoundException("ref not found: " + ref);
    }
    return files;
  }

  private static FileContent fileAt(Repo r, String path, String ref) {
    String content = snapshot(r, ref).get(path);
    if (content == null) {
      throw new VcsNotFoundException("file not found: " + path);
    }
    return new FileContent(content, GitBlob.sha(content));
  }

  private static Map<String, String> branchFiles(Repo r, String branch) {
    return r.commits.get(requireBranch(r, branch));
  }

  private static String requireBranch(Repo r, String branch) {
    String sha = r.branches.get(branch);
    if (sha == null) {
      throw new VcsNotFoundException("branch not found: " + branch);
    }
    return sha;
  }

  private static PullRequest pullRequest(FakePullRequest pr) {
    return new PullRequest(pr.number(), WEB + pr.repo() + "/pull/" + pr.number(), pr.head());
  }

  private CommitRef moveBranch(Repo r, String branch, Map<String, String> files) {
    String sha = newCommit(r, files);
    r.branches.put(branch, sha);
    return new CommitRef(sha, WEB + r.info.fullName() + "/commit/" + sha);
  }

  private String newCommit(Repo r, Map<String, String> files) {
    String sha = String.format("%040x", ++commitCount);
    r.commits.put(sha, Map.copyOf(files));
    return sha;
  }
}
