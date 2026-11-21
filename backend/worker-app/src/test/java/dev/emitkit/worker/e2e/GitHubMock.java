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
 * run is visible to the next. Other requests get a 404 and are kept in {@link #unhandled()}. The
 * server starts on first use, so a skipped test class never opens a port.
 */
final class GitHubMock implements ResponseDefinitionTransformerV2, AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern REPO = Pattern.compile("/repos/([^/]+/[^/]+)(/.*)?");
  private static final Map<String, Object> NOT_FOUND = obj("message", "Not Found");

  /** A pull request. The mock never closes one. */
  record Pull(int number, String head, String base, String title, String body) {}

  private final String token;
  private WireMockServer server;

  /** Repo to branch to commit sha. The first branch is the default. */
  private final Map<String, Map<String, String>> branches = new HashMap<>();

  private final Map<String, List<Pull>> pulls = new HashMap<>();

  /** Commit sha to tree sha. */
  private final Map<String, String> commits = new HashMap<>();

  /** Tree sha to files, path to content. Each tree is a full snapshot. */
  private final Map<String, Map<String, String>> trees = new HashMap<>();

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
    branches.put(repo, new LinkedHashMap<>(Map.of(branch, newCommit(newTree(files)))));
    pulls.put(repo, new ArrayList<>());
  }

  /** Commits {@code content} at {@code path} on top of the head of {@code branch}. */
  synchronized void commit(String repo, String branch, String path, String content) {
    Map<String, String> files = new TreeMap<>(files(repo, branch));
    files.put(path, content);
    branches.get(repo).put(branch, newCommit(newTree(files)));
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
    String body = request.getBodyAsString();
    JsonNode in = body.isBlank() ? null : JSON.readTree(body);
    if (!("token " + token).equals(request.getHeader("Authorization"))) {
      return json(401, obj("message", "Bad credentials"));
    }
    String url = request.getUrl();
    int split = url.indexOf('?');
    String path = split < 0 ? url : url.substring(0, split);
    String query = split < 0 ? "" : url.substring(split + 1);
    String method = request.getMethod().getName();
    ResponseDefinition reply = route(method, path, query, in);
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
    Map<String, String> heads = branches.get(repo);
    if (heads == null) {
      return json(404, NOT_FOUND);
    }
    if (method.equals("GET") && rest.isEmpty()) {
      String owner = repo.substring(0, repo.indexOf('/'));
      String name = repo.substring(owner.length() + 1);
      String main = heads.keySet().iterator().next();
      return json(
          200,
          obj(
              "full_name",
              repo,
              "name",
              name,
              "owner",
              obj("login", owner),
              "default_branch",
              main));
    }
    if (rest.startsWith("/git/refs/heads/")) {
      String branch = decode(rest.substring("/git/refs/heads/".length()));
      return refRoute(method, repo, heads, branch, in);
    }
    if (method.equals("POST") && rest.equals("/git/refs")) {
      String branch = in.path("ref").stringValue().substring("refs/heads/".length());
      return refRoute(method, repo, heads, branch, in);
    }
    if (method.equals("GET") && rest.startsWith("/git/trees/")) {
      String commit = resolve(heads, decode(rest.substring("/git/trees/".length())));
      if (commit == null) {
        return json(404, NOT_FOUND);
      }
      String tree = commits.get(commit);
      List<Map<String, Object>> entries = new ArrayList<>();
      trees
          .get(tree)
          .forEach((name, content) ->
              entries.add(
                  obj(
                      "path",
                      name,
                      "mode",
                      "100644",
                      "type",
                      "blob",
                      "sha",
                      GitBlob.sha(content))));
      return json(200, obj("sha", tree, "truncated", false, "tree", entries));
    }
    if (method.equals("POST") && rest.equals("/git/trees")) {
      String base = in.path("base_tree").stringValue();
      Map<String, String> files = new TreeMap<>(base == null ? Map.of() : trees.get(base));
      in.path("tree")
          .forEach(entry ->
              files.put(entry.path("path").stringValue(), entry.path("content").stringValue()));
      return json(201, obj("sha", newTree(files), "truncated", false, "tree", List.of()));
    }
    if (method.equals("POST") && rest.equals("/git/commits")) {
      String tree = in.path("tree").stringValue();
      String commit = newCommit(tree);
      return json(
          201,
          obj(
              "sha",
              commit,
              "tree",
              obj("sha", tree),
              "html_url",
              "https://github.com/" + repo + "/commit/" + commit));
    }
    if (method.equals("GET") && rest.startsWith("/contents/")) {
      String file = decode(rest.substring("/contents/".length()));
      String commit = resolve(heads, param(query, "ref"));
      String content = commit == null ? null : trees.get(commits.get(commit)).get(file);
      if (content == null) {
        return json(404, NOT_FOUND);
      }
      byte[] bytes = content.getBytes(UTF_8);
      return json(
          200,
          obj(
              "type",
              "file",
              "name",
              file.substring(file.lastIndexOf('/') + 1),
              "path",
              file,
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
        return json(422, obj("message", "Validation Failed"));
      }
      List<Pull> open = pulls.get(repo);
      int number = open.size() + 1;
      open.add(
          new Pull(
              number, head, base, in.path("title").stringValue(), in.path("body").stringValue()));
      return json(
          201,
          obj(
              "number",
              number,
              "state",
              "open",
              "html_url",
              "https://github.com/" + repo + "/pull/" + number,
              "head",
              obj("ref", head)));
    }
    return null;
  }

  /** GET, create (POST) and move (PATCH) of one branch's ref. */
  private ResponseDefinition refRoute(
      String method, String repo, Map<String, String> heads, String branch, JsonNode in) {
    boolean exists = heads.containsKey(branch);
    String sha = in == null ? null : in.path("sha").stringValue();
    if (method.equals("GET") && exists) {
      return json(200, ref(repo, branch, heads.get(branch)));
    }
    if (method.equals("POST") && exists) {
      return json(422, obj("message", "Reference already exists"));
    }
    if (method.equals("POST") || (method.equals("PATCH") && exists && commits.containsKey(sha))) {
      heads.put(branch, sha);
      return json(method.equals("POST") ? 201 : 200, ref(repo, branch, sha));
    }
    return json(404, NOT_FOUND);
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

  private String newCommit(String tree) {
    String sha = nextSha();
    commits.put(sha, tree);
    return sha;
  }

  private String nextSha() {
    return String.format("%040x", ++sequence);
  }

  /** A ref as GitHub returns it. Its url is where kohsuke sends a ref update. */
  private Map<String, Object> ref(String repo, String branch, String sha) {
    String url = apiUrl() + "/repos/" + repo + "/git/refs/heads/" + branch;
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
