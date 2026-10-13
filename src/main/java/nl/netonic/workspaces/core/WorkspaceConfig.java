package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contents of {@code <workspace>/.workspace.json}.
 *
 * <pre>
 * {
 *   "name": "klant-x",
 *   "branchPattern": "feature/{ticket}",
 *   "repos": {
 *     "backend":  { "baseBranch": "develop", "copy": [".env", "auth.json", "vendor"] },
 *     "frontend": { "baseBranch": "main",    "copy": [".env", "node_modules"] }
 *   }
 * }
 * </pre>
 */
public final class WorkspaceConfig {
  public static final String FILE_NAME = ".workspace.json";
  public static final String DEFAULT_BRANCH_PATTERN = "feature/{ticket}";
  /** Copied (APFS clone on macOS) from main/&lt;repo&gt; into every new worktree, when present. */
  public static final List<String> DEFAULT_COPY =
      List.of(".env", ".env.local", "auth.json", "vendor", "node_modules");

  private String name;
  private String branchPattern = DEFAULT_BRANCH_PATTERN;
  private final LinkedHashMap<String, RepoConfig> repos = new LinkedHashMap<>();

  public WorkspaceConfig(String name) {
    this.name = name;
  }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getBranchPattern() { return branchPattern; }
  public void setBranchPattern(String pattern) {
    this.branchPattern = pattern == null || pattern.isBlank() ? DEFAULT_BRANCH_PATTERN : pattern.trim();
  }

  /** Repos in the order they were added (backend before frontend, etc.). */
  public Map<String, RepoConfig> getRepos() { return repos; }

  public String branchFor(String ticket) {
    return branchPattern.replace("{ticket}", ticket);
  }

  // ------------------------------------------------------------------ JSON

  public static WorkspaceConfig load(Path file) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    Object parsed;
    try {
      parsed = Json.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid " + file + ": " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> root)) throw new IOException(file + " must contain a JSON object");

    String fallbackName = file.getParent() != null && file.getParent().getFileName() != null
        ? file.getParent().getFileName().toString() : "workspace";
    WorkspaceConfig cfg = new WorkspaceConfig(str(root.get("name"), fallbackName));
    cfg.setBranchPattern(str(root.get("branchPattern"), DEFAULT_BRANCH_PATTERN));
    if (root.get("repos") instanceof Map<?, ?> repos) {
      for (Map.Entry<?, ?> e : repos.entrySet()) {
        RepoConfig rc = new RepoConfig();
        if (e.getValue() instanceof Map<?, ?> r) {
          rc.setBaseBranch(str(r.get("baseBranch"), null));
          rc.setUrl(str(r.get("url"), null));
          if (r.get("copy") instanceof List<?> l) {
            List<String> copy = new ArrayList<>();
            for (Object o : l) if (o != null && !o.toString().isBlank()) copy.add(o.toString().trim());
            rc.setCopy(copy);
          }
        }
        cfg.repos.put(String.valueOf(e.getKey()), rc);
      }
    }
    return cfg;
  }

  public void save(Path file) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", name);
    root.put("branchPattern", branchPattern);
    Map<String, Object> rs = new LinkedHashMap<>();
    for (Map.Entry<String, RepoConfig> e : repos.entrySet()) {
      Map<String, Object> r = new LinkedHashMap<>();
      RepoConfig rc = e.getValue();
      if (rc.getUrl() != null) r.put("url", rc.getUrl());
      if (rc.getBaseBranch() != null) r.put("baseBranch", rc.getBaseBranch());
      r.put("copy", new ArrayList<Object>(rc.getCopy()));
      rs.put(e.getKey(), r);
    }
    root.put("repos", rs);
    Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
  }

  private static String str(Object o, String fallback) {
    if (o == null) return fallback;
    String s = o.toString().trim();
    return s.isEmpty() ? fallback : s;
  }

  /** Per-repo settings. */
  public static final class RepoConfig {
    private String baseBranch;
    private String url;
    private List<String> copy = new ArrayList<>(DEFAULT_COPY);

    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String b) { baseBranch = b == null || b.isBlank() ? null : b.trim(); }

    public String getUrl() { return url; }
    public void setUrl(String u) { url = u == null || u.isBlank() ? null : u.trim(); }

    public List<String> getCopy() { return copy; }
    public void setCopy(List<String> c) { copy = c == null ? new ArrayList<>() : new ArrayList<>(c); }
  }
}
