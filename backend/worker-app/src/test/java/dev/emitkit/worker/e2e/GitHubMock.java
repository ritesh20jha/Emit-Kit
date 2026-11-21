package dev.emitkit.worker.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import dev.emitkit.vcs.GitBlob;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * A stateful fake of the GitHub REST calls a run makes: /user, repos, git refs, trees, commits,
 * contents and pull requests. State is in memory, so a commit made by one run is visible to the
 * next. Any other request gets a 404 and is kept in {@link #unhandled()}.
 */
final class GitHubMock implements ResponseDefinitionTransformerV2, AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern REPO = Pattern.compile("/repos/([^/]+/[^/]+)(/.*)?");
  private static final Map<String, String> NOT_FOUND = Map.of("message", "Not Found");

  /** A pull request. The mock never closes one. */
  record Pull(int number, String head, String base, String title, String body) {}

  private final WireMockServer server;
  private final String token;

  /** Repo to branch to commit sha. The first branch is the default. */
  private final Map<String, Map<String, String>> branches = new HashMap<>();

  private final Map<String, List<Pull>> pulls = new HashMap<>();

  /** Commit sha to tree sha. */
  private final Map<String, String> commits = new HashMap<>();

  /** Tree sha to files, path to content. Each tree is a full snapshot. */
  private final Map<String, Map<String, String>> trees = new HashMap<>();

  private final List<String> unhandled = new ArrayList<>();
  private long sequence;

  /** Starts on a free port. Requests must carry {@code Authorization: token <token>}. */
  GitHubMock(String token) {
    this.token = token;
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(this));
    server.stubFor(any(anyUrl()).willReturn(aResponse()));
    server.start();
  }

  /** The API root, to use as the GitHub endpoint. */
  String apiUrl() {
    return server.baseUrl();
  }

  /** Creates {@code repo} with one commit on {@code branch} that holds {@code files}. */
  synchronized void createRepo(String repo, String branch, Map<String, String> files) {
    String commit = nextSha();
    commits.put(commit, newTree(files));
    branches.put(repo, new LinkedHashMap<>(Map.of(branch, commit)));
    pulls.put(repo, new ArrayList<>());
  }

  synchronized List<Pull> pulls(String repo) {
    return List.copyOf(pulls.get(repo));
  }

  /** The files at the head of {@code branch} of {@code repo}, path to content. */
  synchronized Map<String, String> files(String repo, String branch) {
    return Map.copyOf(trees.get(commits.get(branches.get(repo).get(branch))));
  }

  /** Requests the mock could not serve, as "METHOD url". Empty when only mocked calls ran. */
  synchronized List<String> unhandled() {
    return List.copyOf(unhandled);
  }

  @Override
  public void close() {
    server.stop();
  }

  @Override
  public String getName() {
    return "github-mock";
  }

  @Override
  public ResponseDefinition transform(ServeEvent event) {
    var request = event.getRequest();
    String body = request.getBodyAsString();
    return handle(
        request.getMethod().getName(),
        request.getUrl(),
        body.isBlank() ? null : JSON.readTree(body),
        request.getHeader("Authorization"));
  }

  private synchronized ResponseDefinition handle(
      String method, String url, JsonNode body, String authorization) {
    if (!("token " + token).equals(authorization)) {
      return json(401, Map.of("message", "Bad credentials"));
    }
    int split = url.indexOf('?');
    ResponseDefinition reply =
        route(
            method,
            split < 0 ? url : url.substring(0, split),
            split < 0 ? "" : url.substring(split + 1),
            body);
    if (reply == null) {
      unhandled.add(method + " " + url);
      return json(404, NOT_FOUND);
    }
    return reply;
  }

  /** The reply to one request, or null when no endpoint serves it. */
  private ResponseDefinition route(String method, String path, String query, JsonNode in) {
    if (method.equals("GET") && path.equals("/user")) {
      return json(200, Map.of("login", "emitkit-bot", "id", 1));
    }
    Matcher matcher = REPO.matcher(path);
    if (!matcher.matches()) {
      return null;
    }
    String repo = matcher.group(1);
    String rest = matcher.group(2) == null ? "" : matcher.group(2);
    Map<String, String> heads = branches.get(repo);
    if (heads == null) {
      return json(404, NOT_FOUND);
    }
    if (method.equals("GET") && rest.isEmpty()) {
      return json(
          200,
          Map.of(
              "name",
              repo.substring(repo.indexOf('/') + 1),
              "full_name",
              repo,
              "owner",
              Map.of("login", repo.substring(0, repo.indexOf('/'))),
              "default_branch",
              heads.keySet().iterator().next()));
    }
    if (rest.startsWith("/git/refs/heads/")) {
      String branch = decode(rest.substring("/git/refs/heads/".length()));
      String sha = in == null ? null : in.path("sha").stringValue();
      if (method.equals("GET") && heads.containsKey(branch)) {
        return json(200, ref(repo, branch, heads.get(branch)));
      }
      if (method.equals("PATCH") && heads.containsKey(branch) && commits.containsKey(sha)) {
        heads.put(branch, sha);
        return json(200, ref(repo, branch, sha));
      }
      return json(404, NOT_FOUND);
    }
    if (method.equals("POST") && rest.equals("/git/refs")) {
      String branch = in.path("ref").stringValue().substring("refs/heads/".length());
      if (heads.containsKey(branch)) {
        return json(422, Map.of("message", "Reference already exists"));
      }
      heads.put(branch, in.path("sha").stringValue());
      return json(201, ref(repo, branch, heads.get(branch)));
    }
    if (method.equals("GET") && rest.startsWith("/git/trees/")) {
      String commit = resolve(heads, decode(rest.substring("/git/trees/".length())));
      if (commit == null) {
        return json(404, NOT_FOUND);
      }
      String tree = commits.get(commit);
      List<Map<String, Object>> entries =
          trees.get(tree).entrySet().stream()
              .map(file ->
                  Map.<String, Object>of(
                      "path",
                      file.getKey(),
                      "mode",
                      "100644",
                      "type",
                      "blob",
                      "sha",
                      GitBlob.sha(file.getValue()),
                      "size",
                      file.getValue().getBytes(StandardCharsets.UTF_8).length))
              .toList();
      return json(200, Map.of("sha", tree, "truncated", false, "tree", entries));
    }
    if (method.equals("POST") && rest.equals("/git/trees")) {
      String base = in.path("base_tree").stringValue();
      Map<String, String> files = new TreeMap<>(base == null ? Map.of() : trees.get(base));
      in.path("tree")
          .forEach(entry ->
              files.put(entry.path("path").stringValue(), entry.path("content").stringValue()));
      return json(201, Map.of("sha", newTree(files), "truncated", false, "tree", List.of()));
    }
    if (method.equals("POST") && rest.equals("/git/commits")) {
      String tree = in.path("tree").stringValue();
      String commit = nextSha();
      commits.put(commit, tree);
      return json(
          201,
          Map.of(
              "sha",
              commit,
              "tree",
              Map.of("sha", tree),
              "html_url",
              "https://github.com/" + repo + "/commit/" + commit));
    }
    if (method.equals("GET") && rest.startsWith("/contents/")) {
      String path2 = decode(rest.substring("/contents/".length()));
      String commit = resolve(heads, decode(query.substring("ref=".length())));
      String content = commit == null ? null : trees.get(commits.get(commit)).get(path2);
      if (content == null) {
        return json(404, NOT_FOUND);
      }
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      return json(
          200,
          Map.of(
              "type",
              "file",
              "name",
              path2.substring(path2.lastIndexOf('/') + 1),
              "path",
              path2,
              "sha",
              GitBlob.sha(content),
              "size",
              bytes.length,
              "encoding",
              "base64",
              "content",
              Base64.getEncoder().encodeToString(bytes)));
    }
    if (method.equals("POST") && rest.equals("/pulls")) {
      String head = in.path("head").stringValue();
      String base = in.path("base").stringValue();
      if (!heads.containsKey(head) || !heads.containsKey(base)) {
        return json(422, Map.of("message", "Validation Failed"));
      }
      List<Pull> open = pulls.get(repo);
      int number = open.size() + 1;
      open.add(
          new Pull(
              number, head, base, in.path("title").stringValue(), in.path("body").stringValue()));
      return json(
          201,
          Map.of(
              "number",
              number,
              "html_url",
              "https://github.com/" + repo + "/pull/" + number,
              "head",
              Map.of("ref", head)));
    }
    return null;
  }

  /** Resolves a branch name or a commit sha to a commit sha, or null. */
  private String resolve(Map<String, String> heads, String ref) {
    if (heads.containsKey(ref)) {
      return heads.get(ref);
    }
    return commits.containsKey(ref) ? ref : null;
  }

  private String newTree(Map<String, String> files) {
    String sha = nextSha();
    trees.put(sha, new TreeMap<>(files));
    return sha;
  }

  private String nextSha() {
    return String.format("%040x", ++sequence);
  }

  /** A ref as GitHub returns it. Its url is where kohsuke sends a ref update. */
  private Map<String, Object> ref(String repo, String branch, String sha) {
    return Map.of(
        "ref", "refs/heads/" + branch,
        "url", apiUrl() + "/repos/" + repo + "/git/refs/heads/" + branch,
        "object", Map.of("sha", sha, "type", "commit"));
  }

  private static String decode(String segment) {
    return URLDecoder.decode(segment, StandardCharsets.UTF_8);
  }

  private static ResponseDefinition json(int status, Object body) {
    return ResponseDefinitionBuilder.responseDefinition()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(JSON.writeValueAsString(body))
        .build();
  }
}
