package dev.emitkit.worker.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import dev.emitkit.vcs.GitBlob;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stateful fake of the GitHub REST calls a run makes. State is in memory, so a commit made by one
 * run is visible to the next. Other requests get a 404 and are recorded in {@link #unhandled()}.
 * The server starts on first use, so a skipped test class never opens a port.
 */
final class GitHubMock implements ResponseDefinitionTransformerV2, AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern REPO = Pattern.compile("/repos/([^/]+/[^/]+)(/.*)?");
  private static final Pattern ISSUE = Pattern.compile("/issues/(\\d+)(/comments)?");
  private static final String HEADS = "/git/refs/heads/";
  private static final Map<String, Object> NOT_FOUND = obj("message", "Not Found");

  /** A pull request. The mock never closes one. */
  record Pull(int number, String head, String base, String title, String body) {}

  private final String token;
  private WireMockServer server;

  /** Repo to branch to commit sha. The first branch is the default. */
  private final Map<String, Map<String, String>> branches = new HashMap<>();

  private final Map<String, List<Pull>> pulls = new HashMap<>();

  /** Repo and pull number, as "repo#number", to the bodies of the comments on it. */
  private final Map<String, List<String>> comments = new HashMap<>();

  /** Commit or tree sha to its files (path to content). A commit's files are its tree's files. */
  private final Map<String, Map<String, String>> snapshots = new HashMap<>();

  private final List<String> unhandled = new ArrayList<>();
  private long sequence;

  /** Answers requests that carry {@code Authorization: token <token>}. */
  GitHubMock(String token) {
    this.token = token;
  }

  /** The API root to use as the GitHub endpoint. Starts the server on first call. */
  synchronized String apiUrl() {
    if (server == null) {
      server = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(this));
      server.stubFor(any(anyUrl()).willReturn(aResponse()));
      server.start();
    }
    return server.baseUrl();
  }

  /** Creates {@code repo} with one commit on {@code branch} that holds {@code files}. */
  synchronized void createRepo(String repo, String branch, Map<String, String> files) {
    branches.put(repo, new LinkedHashMap<>(Map.of(branch, snapshot(files))));
    pulls.put(repo, new ArrayList<>());
  }

  /** Commits {@code content} at {@code path} on top of the head of {@code branch}. */
  synchronized void commit(String repo, String branch, String path, String content) {
    Map<String, String> files = new TreeMap<>(files(repo, branch));
    files.put(path, content);
    branches.get(repo).put(branch, snapshot(files));
  }

  synchronized List<Pull> pulls(String repo) {
    return List.copyOf(pulls.get(repo));
  }

  /** The bodies of the comments on a pull request, oldest first. */
  synchronized List<String> comments(String repo, int number) {
    return List.copyOf(comments.getOrDefault(repo + "#" + number, List.of()));
  }

  /** The files at the head of {@code branch} of {@code repo}, path to content. */
  synchronized Map<String, String> files(String repo, String branch) {
    return Map.copyOf(snapshots.get(branches.get(repo).get(branch)));
  }

  /** Requests the mock could not serve, as "METHOD url". Empty when only mocked calls ran. */
  synchronized List<String> unhandled() {
    return List.copyOf(unhandled);
  }

  @Override
  public synchronized void close() {
    if (server != null) {
      server.stop();
    }
  }

  @Override
  public String getName() {
    return "github-mock";
  }

  @Override
  public synchronized ResponseDefinition transform(ServeEvent event) {
    var request = event.getRequest();
    if (!("token " + token).equals(request.getHeader("Authorization"))) {
      return json(401, obj("message", "Bad credentials"));
    }
    String url = request.getUrl();
    String[] parts = url.split("\\?", 2);
    String method = request.getMethod().getName();
    String body = request.getBodyAsString();
    JsonNode in = body.isBlank() ? null : JSON.readTree(body);
    ResponseDefinition reply = route(method, parts[0], parts.length > 1 ? parts[1] : "", in);
    if (reply == null) {
      unhandled.add(method + " " + url);
      return json(404, NOT_FOUND);
    }
    return reply;
  }

  /** The reply to one request, or null when no endpoint serves it. */
  private ResponseDefinition route(String method, String path, String query, JsonNode in) {
    if (method.equals("GET") && path.equals("/user")) {
      return json(200, obj("login", "emitkit-bot", "id", 1));
    }
    Matcher matcher = REPO.matcher(path);
    if (!matcher.matches()) {
      return null;
    }
    String repo = matcher.group(1);
    String rest = matcher.group(2) == null ? "" : matcher.group(2);
    Map<String, String> refs = branches.get(repo);
    if (refs == null) {
      return json(404, NOT_FOUND);
    }
    if (method.equals("GET") && rest.isEmpty()) {
      String owner = repo.substring(0, repo.indexOf('/'));
      String name = repo.substring(owner.length() + 1);
      String main = refs.keySet().iterator().next();
      return json(
          200,
          obj(
              "full_name", repo,
              "name", name,
              "owner", obj("login", owner),
              "default_branch", main));
    }
    if (rest.startsWith(HEADS)) {
      return refRoute(method, repo, refs, decode(rest.substring(HEADS.length())), in);
    }
    // No repo here has tags. GitHub answers 404 for an empty tags namespace, which CALC_VERSION
    // reads as "no earlier releases".
    if (method.equals("GET") && rest.equals("/git/refs/tags")) {
      return json(404, NOT_FOUND);
    }
    if (method.equals("POST") && rest.equals("/git/refs")) {
      String branch = in.path("ref").stringValue().substring("refs/heads/".length());
      return refRoute(method, repo, refs, branch, in);
    }
    if (method.equals("GET") && rest.startsWith("/git/trees/")) {
      String commit = resolve(refs, decode(rest.substring("/git/trees/".length())));
      if (commit == null) {
        return json(404, NOT_FOUND);
      }
      var tree =
          snapshots.get(commit).entrySet().stream()
              .map(e -> obj("path", e.getKey(), "type", "blob", "sha", GitBlob.sha(e.getValue())))
              .toList();
      return json(200, obj("sha", commit, "tree", tree));
    }
    if (method.equals("POST") && rest.equals("/git/trees")) {
      String base = in.path("base_tree").stringValue();
      var files = new TreeMap<>(snapshots.getOrDefault(base, Map.of()));
      for (JsonNode entry : in.path("tree")) {
        files.put(entry.path("path").stringValue(), entry.path("content").stringValue());
      }
      return json(201, obj("sha", snapshot(files), "tree", List.of()));
    }
    if (method.equals("POST") && rest.equals("/git/commits")) {
      String commit = snapshot(snapshots.get(in.path("tree").stringValue()));
      String link = "https://github.com/" + repo + "/commit/" + commit;
      return json(201, obj("sha", commit, "html_url", link));
    }
    if (method.equals("GET") && rest.startsWith("/contents/")) {
      String file = decode(rest.substring("/contents/".length()));
      String commit = resolve(refs, param(query, "ref"));
      String content = commit == null ? null : snapshots.get(commit).get(file);
      if (content == null) {
        return json(404, NOT_FOUND);
      }
      byte[] bytes = content.getBytes(UTF_8);
      String sha = GitBlob.sha(content);
      String encoded = Base64.getEncoder().encodeToString(bytes);
      return json(
          200, obj("sha", sha, "size", bytes.length, "encoding", "base64", "content", encoded));
    }
    if (method.equals("POST") && rest.equals("/pulls")) {
      String head = in.path("head").stringValue();
      String base = in.path("base").stringValue();
      if (!refs.containsKey(head) || !refs.containsKey(base)) {
        return json(422, obj("message", "Validation Failed"));
      }
      List<Pull> open = pulls.get(repo);
      int number = open.size() + 1;
      String link = "https://github.com/" + repo + "/pull/" + number;
      String title = in.path("title").stringValue();
      String body = in.path("body").stringValue();
      open.add(new Pull(number, head, base, title, body));
      return json(201, obj("number", number, "html_url", link, "head", obj("ref", head)));
    }
    Matcher issue = ISSUE.matcher(rest);
    if (issue.matches() && method.equals("GET")) {
      int number = Integer.parseInt(issue.group(1));
      String link = "https://github.com/" + repo + "/issues/" + number;
      return json(200, obj("number", number, "state", "open", "html_url", link));
    }
    if (issue.matches() && issue.group(2) != null && method.equals("POST")) {
      String key = repo + "#" + issue.group(1);
      String body = in.path("body").stringValue();
      comments.computeIfAbsent(key, k -> new ArrayList<>()).add(body);
      return json(201, obj("id", ++sequence, "body", body));
    }
    return null;
  }

  /** GET, create (POST) and move (PATCH) of one branch's ref. */
  private ResponseDefinition refRoute(
      String method, String repo, Map<String, String> refs, String branch, JsonNode in) {
    boolean exists = refs.containsKey(branch);
    String sha = in == null ? null : in.path("sha").stringValue();
    if (method.equals("GET") && exists) {
      return json(200, ref(repo, branch, refs.get(branch)));
    }
    if (method.equals("POST") && exists) {
      return json(422, obj("message", "Reference already exists"));
    }
    if (method.equals("POST") || (method.equals("PATCH") && exists && snapshots.containsKey(sha))) {
      refs.put(branch, sha);
      return json(method.equals("POST") ? 201 : 200, ref(repo, branch, sha));
    }
    return json(404, NOT_FOUND);
  }

  /** Resolves a branch name or a commit sha to a commit sha, or null. */
  private String resolve(Map<String, String> refs, String ref) {
    if (refs.containsKey(ref)) {
      return refs.get(ref);
    }
    return snapshots.containsKey(ref) ? ref : null;
  }

  /** Stores {@code files} under a new sha, which serves as both its tree and its commit. */
  private String snapshot(Map<String, String> files) {
    String sha = String.format("%040x", ++sequence);
    snapshots.put(sha, new TreeMap<>(files));
    return sha;
  }

  /** A ref as GitHub returns it. Its url is where kohsuke sends a ref update. */
  private Map<String, Object> ref(String repo, String branch, String sha) {
    String url = apiUrl() + "/repos/" + repo + HEADS + branch;
    return obj(
        "ref", "refs/heads/" + branch, "url", url, "object", obj("sha", sha, "type", "commit"));
  }

  /** The value of {@code name} in a query string, or null when it is absent. */
  private static String param(String query, String name) {
    for (String pair : query.split("&")) {
      if (pair.startsWith(name + "=")) {
        return decode(pair.substring(name.length() + 1));
      }
    }
    return null;
  }

  /** A map that keeps its keys in the order given. */
  private static Map<String, Object> obj(Object... keysAndValues) {
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  private static String decode(String segment) {
    return URLDecoder.decode(segment, UTF_8);
  }

  private static ResponseDefinition json(int status, Object body) {
    return ResponseDefinitionBuilder.responseDefinition()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(JSON.writeValueAsString(body))
        .build();
  }
}
